package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * System (increment 3): device-resident uniform spatial grid (CSR) + broad-phase,
 * plus an O(N²) brute-force reference. INFRASTRUCTURE, NOT PHYSICS — it writes no
 * forces; it emits candidate interaction pairs for a future narrow-phase consumer
 * (motors, inc 4).
 *
 * Operates ONLY on the entity-agnostic SpatialBodyView (bounding spheres: center +
 * boundingRadius). Knows nothing about FilSegment. Ported from v1's grid build —
 * GPUMotorBinding.gridAssembleKernel (the serial CSR oracle) + the two-level
 * parallel prefix-sum (gridScanLocal/gridScanChunks/gridScanAdd) + the broad-phase
 * 27-cell stencil with per-body owned output slices (filFilCandidateKernel).
 *
 * BINNING — each body is binned into the single cell containing its CENTER. The
 * cell size is chosen as cellSize = 2·maxBoundingRadius + cutoff (set by the host),
 * so the 27-cell stencil is PROVABLY COMPLETE: any pair within reach
 * (centerDist ≤ rᵢ+rⱼ+cutoff ≤ 2·maxR+cutoff = cellSize) has center cells differing
 * by ≤1 in every axis, hence jⱼ's cell ∈ iᵢ's 27-cell neighborhood. The exact
 * predicate is re-applied before emitting, so the broad-phase candidate set EXACTLY
 * equals the brute-force set (extra cells scanned are filtered, none missed). Center
 * binning ⇒ each body occupies exactly one cell ⇒ a pair (i,j) is discovered once by
 * thread i, so the i<j guard dedups with no min-corner logic.
 *
 * DEVICE-AGNOSTIC — every kernel is a plain @Parallel method over the SoA arrays:
 * NO KernelContext, NO atomics, NO TaskGraph constructs. The histogram and scatter
 * are single-threaded (@Parallel range 1, serial inner loop) like v1's
 * gridAssembleKernel oracle — race-free and O(N), so they run bit-identically on the
 * GPU TaskGraph and the sequential -cpu runner. The prefix-sum (the hard primitive)
 * is the genuinely parallel two-level block scan. Histogram + order-independent scan
 * + serial scatter (bodies visited in index order) ⇒ the CSR is BIT-IDENTICAL
 * CPU↔GPU (offsets and within-cell order), not merely multiset-equal.
 *
 * gridParams (float): [0]=xMin [1]=yMin [2]=zMin [3]=cellSize [4]=invCellSize [5]=cutoff
 * gridDims (int):     [0]=nXBins [1]=nYBins [2]=nZBins [3]=totalCells
 * counts (int):       [1]=S (active body count)  (shares the FilamentStore counts layout)
 */
public final class SpatialGrid {
    private SpatialGrid() {}

    /** Two-level scan chunk size (matches v1 GPUMotorBinding.GRID_SCAN_CHUNK). */
    public static final int GRID_SCAN_CHUNK = 512;
    /** Max candidate partners per body (per-body owned output slice). Overflow is reported. */
    public static final int MAX_CAND = 256;

    /** Per-body center cell index (clamped to the grid). -1 for padding slots [S,cap). */
    public static void bodyCell(
            FloatArray center,
            FloatArray gridParams,
            IntArray   gridDims,
            IntArray   counts,
            IntArray   bodyCell) {

        int cap = center.getSize() / 3;
        int S   = counts.get(1);
        float xMin = gridParams.get(0), yMin = gridParams.get(1), zMin = gridParams.get(2);
        float invCell = gridParams.get(4);
        int nX = gridDims.get(0), nY = gridDims.get(1), nZ = gridDims.get(2);
        int nXY = nX * nY;

        for (@Parallel int i = 0; i < cap; i++) {
            if (i >= S) { bodyCell.set(i, -1); continue; }
            int ix = (int) ((center.get(i)           - xMin) * invCell);
            int iy = (int) ((center.get(cap + i)     - yMin) * invCell);
            int iz = (int) ((center.get(2 * cap + i) - zMin) * invCell);
            if (ix < 0) ix = 0; if (ix >= nX) ix = nX - 1;
            if (iy < 0) iy = 0; if (iy >= nY) iy = nY - 1;
            if (iz < 0) iz = 0; if (iz >= nZ) iz = nZ - 1;
            bodyCell.set(i, ix + iy * nX + iz * nXY);
        }
    }

    /** Zero the per-cell counter (parallel over cells). */
    public static void gridZero(IntArray gridDims, IntArray cellCount) {
        int totalCells = gridDims.get(3);
        for (@Parallel int c = 0; c < totalCells; c++) {
            cellCount.set(c, 0);
        }
    }

    /** Histogram: count bodies per cell. Single-thread serial (race-free, O(N)) — v1's
     *  gridAssembleKernel histogram pass, but over single-cell binning. */
    public static void gridHistogram(IntArray bodyCell, IntArray counts, IntArray cellCount) {
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int S = counts.get(1);
            for (int s = 0; s < S; s++) {
                int c = bodyCell.get(s);
                if (c >= 0) { cellCount.set(c, cellCount.get(c) + 1); }
            }
        }
    }

    /** Parallel prefix-sum, level 1: per-chunk exclusive scan of cellCount into
     *  gridCellOffsets (chunk-relative), per-chunk total into chunkSum. THE hard primitive. */
    public static void gridScanLocal(
            IntArray gridDims,
            IntArray cellCount,
            IntArray gridCellOffsets,
            IntArray chunkSum) {
        int totalCells = gridDims.get(3);
        int numChunks  = (totalCells + GRID_SCAN_CHUNK - 1) / GRID_SCAN_CHUNK;
        for (@Parallel int ch = 0; ch < numChunks; ch++) {
            int start = ch * GRID_SCAN_CHUNK;
            int end   = start + GRID_SCAN_CHUNK;
            if (end > totalCells) end = totalCells;
            int acc = 0;
            for (int c = start; c < end; c++) {
                gridCellOffsets.set(c, acc);
                acc += cellCount.get(c);
            }
            chunkSum.set(ch, acc);
        }
    }

    /** Parallel prefix-sum, level 2: single-thread exclusive scan of the per-chunk totals
     *  (chunk base offsets); chunkSum[numChunks] = grand total. */
    public static void gridScanChunks(IntArray gridDims, IntArray chunkSum) {
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int totalCells = gridDims.get(3);
            int numChunks  = (totalCells + GRID_SCAN_CHUNK - 1) / GRID_SCAN_CHUNK;
            int acc = 0;
            for (int ch = 0; ch < numChunks; ch++) {
                int t = chunkSum.get(ch);
                chunkSum.set(ch, acc);
                acc += t;
            }
            chunkSum.set(numChunks, acc);
        }
    }

    /** Parallel prefix-sum, level 3: add each chunk's base offset back, reset cellCount to a
     *  zeroed write cursor, and write the CSR terminator gridCellOffsets[totalCells]. */
    public static void gridScanAdd(
            IntArray gridDims,
            IntArray gridCellOffsets,
            IntArray gridCellContents,
            IntArray cellCount,
            IntArray chunkSum) {
        int totalCells  = gridDims.get(3);
        int numChunks   = (totalCells + GRID_SCAN_CHUNK - 1) / GRID_SCAN_CHUNK;
        int contentsCap = gridCellContents.getSize();
        for (@Parallel int ch = 0; ch < numChunks; ch++) {
            int start = ch * GRID_SCAN_CHUNK;
            int end   = start + GRID_SCAN_CHUNK;
            if (end > totalCells) end = totalCells;
            int base = chunkSum.get(ch);
            for (int c = start; c < end; c++) {
                gridCellOffsets.set(c, gridCellOffsets.get(c) + base);
                cellCount.set(c, 0);   // reset to per-cell write cursor for the scatter
            }
            if (ch == 0) {
                int total = chunkSum.get(numChunks);
                gridCellOffsets.set(totalCells, total < contentsCap ? total : contentsCap);
            }
        }
    }

    /** Scatter body ids into the sorted CSR contents. Single-thread serial — bodies visited
     *  in index order ⇒ deterministic within-cell order ⇒ CSR bit-identical to any runner.
     *  cellCount is the per-cell write cursor (private, no atomics). */
    public static void gridScatter(
            IntArray bodyCell,
            IntArray counts,
            IntArray gridCellOffsets,
            IntArray gridCellContents,
            IntArray cellCount) {
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int S = counts.get(1);
            int contentsCap = gridCellContents.getSize();
            for (int s = 0; s < S; s++) {
                int c = bodyCell.get(s);
                if (c < 0) continue;
                int writePos = gridCellOffsets.get(c) + cellCount.get(c);
                if (writePos < contentsCap) { gridCellContents.set(writePos, s); }
                cellCount.set(c, cellCount.get(c) + 1);
            }
        }
    }

    // =========================================================================
    // PARALLEL grid build (atomic-free counting sort) — retires the single-threaded
    // gridHistogram + gridScatter above (which are @Parallel(gid<1) O(N) serial
    // passes, fine at inc-3's N=512 but the dominant bottleneck at 50k–800k bodies,
    // 2026-06-18 dense-gliding benchmark). The two-level prefix-sum (gridScanLocal/
    // gridScanChunks/gridScanAdd) is ALREADY parallel and is reused VERBATIM between
    // the histogram and the scatter; only the two ends are parallelized here.
    //
    // BoA's parallel grid build (GPUMotorBinding.gridHistogramKernel/gridScatterChunk)
    // can use atomics because its segments span an AABB of several cells; SoftBox uses
    // CENTER binning (each body in exactly ONE cell), and atomics/KernelContext are
    // forbidden (they break the -cpu runner). So the bodies are partitioned into
    // numBodyChunks contiguous chunks and each chunk gets a PRIVATE per-cell counter
    // row in chunkCellCount[numBodyChunks × totalCells] — a segmented histogram with
    // no shared writes. The per-cell column sum (over chunks) gives the cell totals;
    // the in-place exclusive column-prefix gives each chunk its base offset within the
    // cell, which (after the cell scan) becomes a private write cursor for a stable
    // counting-sort scatter. Bodies are visited in increasing index order within each
    // chunk and chunks are laid out in index order, so the within-cell order is the
    // SAME body-index order the serial gridScatter produces ⇒ the CSR is BIT-IDENTICAL
    // to the serial build (and CPU↔GPU), not merely multiset-equal.
    //
    //   gridChunkZero       — parallel over the flat matrix: chunkCellCount[*] = 0
    //   gridChunkHistogram  — parallel over body-chunks: each chunk counts its bodies
    //                         into its OWN row (private, race-free, no atomics)
    //   gridChunkReduce     — parallel over cells: cellCount[c] = Σ_chunks row[c];
    //                         overwrite each row[c] with its exclusive column-prefix
    //                         (the chunk's base within the cell)
    //   [gridScanLocal → gridScanChunks → gridScanAdd]  — REUSED: cellCount → CSR offsets
    //   gridChunkScatter    — parallel over body-chunks: writePos = offsets[c] +
    //                         row[c]++ (private cursor per (chunk,cell)) — stable sort
    //
    // chunkParams (int): [0]=bodyChunkSize  [1]=numBodyChunks
    // -------------------------------------------------------------------------

    /** Build-time sizing: bodies-per-chunk balancing the histogram/scatter wall-clock
     *  (∝ bodyChunkSize) against the reduce wall-clock (∝ numBodyChunks) at ~√bodyCount,
     *  while capping the chunkCellCount matrix to MATRIX_BUDGET_INTS. */
    public static final long MATRIX_BUDGET_INTS = 64L << 20;   // 64M ints = 256 MB

    public static int bodyChunkSize(int bodyCount, int totalCells) {
        if (bodyCount <= 0 || totalCells <= 0) return 1;
        int sqrtN  = (int) Math.round(Math.sqrt((double) bodyCount));
        long memMin = (long) Math.ceil((double) bodyCount * (double) totalCells / (double) MATRIX_BUDGET_INTS);
        int bcs = (int) Math.max((long) sqrtN, memMin);
        return Math.max(1, bcs);
    }

    public static int numBodyChunks(int bodyCount, int bodyChunkSize) {
        return (bodyCount + bodyChunkSize - 1) / bodyChunkSize;
    }

    /** Zero the segmented-histogram matrix (parallel over all numBodyChunks×totalCells entries). */
    public static void gridChunkZero(IntArray chunkParams, IntArray gridDims, IntArray chunkCellCount) {
        int totalCells = gridDims.get(3);
        int numChunks  = chunkParams.get(1);
        int total = numChunks * totalCells;
        for (@Parallel int e = 0; e < total; e++) {
            chunkCellCount.set(e, 0);
        }
    }

    /** Segmented histogram: each body-chunk counts its bodies into its OWN private cell row.
     *  No shared writes (chunk bc owns row [bc*totalCells, (bc+1)*totalCells)) ⇒ race-free, no atomics. */
    public static void gridChunkHistogram(
            IntArray bodyCell,
            IntArray counts,
            IntArray chunkParams,
            IntArray gridDims,
            IntArray chunkCellCount) {

        int totalCells    = gridDims.get(3);
        int bodyChunkSize = chunkParams.get(0);
        int numChunks     = chunkParams.get(1);
        int S             = counts.get(1);

        for (@Parallel int bc = 0; bc < numChunks; bc++) {
            int start = bc * bodyChunkSize;
            int end   = start + bodyChunkSize;
            if (end > S) end = S;
            int rowBase = bc * totalCells;
            for (int s = start; s < end; s++) {
                int c = bodyCell.get(s);
                if (c >= 0) {
                    int idx = rowBase + c;
                    chunkCellCount.set(idx, chunkCellCount.get(idx) + 1);
                }
            }
        }
    }

    /** Per-cell merge: cellCount[c] = Σ over chunks of row[c]; overwrite each chunk's row[c]
     *  with its EXCLUSIVE column-prefix (the chunk's base offset within cell c). Parallel over
     *  cells — column c is disjoint across threads ⇒ race-free. */
    public static void gridChunkReduce(
            IntArray gridDims,
            IntArray chunkParams,
            IntArray chunkCellCount,
            IntArray cellCount) {

        int totalCells = gridDims.get(3);
        int numChunks  = chunkParams.get(1);
        for (@Parallel int c = 0; c < totalCells; c++) {
            int acc = 0;
            for (int bc = 0; bc < numChunks; bc++) {
                int idx = bc * totalCells + c;
                int v = chunkCellCount.get(idx);
                chunkCellCount.set(idx, acc);   // exclusive prefix over chunks → chunk base within cell
                acc += v;
            }
            cellCount.set(c, acc);              // cell total (fed to the existing scan)
        }
    }

    /** Counting-sort scatter: each body-chunk places its bodies (index order) at
     *  gridCellOffsets[cell] + row[cell]++, the per-(chunk,cell) cursor private to chunk bc.
     *  Distinct chunks → distinct base ranges; in-order within a chunk ⇒ stable, bit-identical
     *  to the serial gridScatter. Race-free, no atomics. */
    public static void gridChunkScatter(
            IntArray bodyCell,
            IntArray counts,
            IntArray chunkParams,
            IntArray gridDims,
            IntArray gridCellOffsets,
            IntArray gridCellContents,
            IntArray chunkCellCount) {

        int totalCells    = gridDims.get(3);
        int bodyChunkSize = chunkParams.get(0);
        int numChunks     = chunkParams.get(1);
        int S             = counts.get(1);
        int contentsCap   = gridCellContents.getSize();

        for (@Parallel int bc = 0; bc < numChunks; bc++) {
            int start = bc * bodyChunkSize;
            int end   = start + bodyChunkSize;
            if (end > S) end = S;
            int rowBase = bc * totalCells;
            for (int s = start; s < end; s++) {
                int c = bodyCell.get(s);
                if (c < 0) continue;
                int idx = rowBase + c;
                int writePos = gridCellOffsets.get(c) + chunkCellCount.get(idx);
                if (writePos < contentsCap) { gridCellContents.set(writePos, s); }
                chunkCellCount.set(idx, chunkCellCount.get(idx) + 1);
            }
        }
    }

    /**
     * Broad-phase: per body i, scan the 27-cell neighborhood of its center cell, emit each
     * partner j>i whose bounding spheres are within cutoff. Output is per-body owned slices
     * (race-free, no atomics): candPartner[i*MAX_CAND + k], candCount[i] = realized count
     * (may exceed MAX_CAND → overflow, surplus dropped, host reports). Predicate is the SAME
     * one brute force uses, so the candidate set == brute-force set exactly.
     */
    public static void broadPhase(
            FloatArray center,
            FloatArray boundingRadius,
            IntArray   bodyCell,
            IntArray   gridCellOffsets,
            IntArray   gridCellContents,
            IntArray   gridDims,
            FloatArray gridParams,
            IntArray   counts,
            IntArray   candPartner,
            IntArray   candCount) {

        int cap = center.getSize() / 3;
        int S   = counts.get(1);
        int nX = gridDims.get(0), nY = gridDims.get(1), nZ = gridDims.get(2);
        int nXY = nX * nY;
        float cutoff = gridParams.get(5);
        int MAXC = SpatialGrid.MAX_CAND;

        for (@Parallel int i = 0; i < cap; i++) {
            candCount.set(i, 0);
            if (i >= S) continue;
            int c = bodyCell.get(i);
            if (c < 0) continue;
            int cz = c / nXY;
            int rem = c - cz * nXY;
            int cy = rem / nX;
            int cx = rem - cy * nX;

            float icx = center.get(i), icy = center.get(cap + i), icz = center.get(2 * cap + i);
            float ri  = boundingRadius.get(i);

            int x0 = cx - 1; if (x0 < 0) x0 = 0;
            int x1 = cx + 1; if (x1 >= nX) x1 = nX - 1;
            int y0 = cy - 1; if (y0 < 0) y0 = 0;
            int y1 = cy + 1; if (y1 >= nY) y1 = nY - 1;
            int z0 = cz - 1; if (z0 < 0) z0 = 0;
            int z1 = cz + 1; if (z1 >= nZ) z1 = nZ - 1;

            int cnt = 0;
            for (int zz = z0; zz <= z1; zz++) {
                int zOff = zz * nXY;
                for (int yy = y0; yy <= y1; yy++) {
                    int yOff = yy * nX;
                    for (int xx = x0; xx <= x1; xx++) {
                        int cc = xx + yOff + zOff;
                        int start = gridCellOffsets.get(cc);
                        int end   = gridCellOffsets.get(cc + 1);
                        for (int idx = start; idx < end; idx++) {
                            int j = gridCellContents.get(idx);
                            if (j <= i) continue;
                            float dx = icx - center.get(j);
                            float dy = icy - center.get(cap + j);
                            float dz = icz - center.get(2 * cap + j);
                            float distSq = dx * dx + dy * dy + dz * dz;
                            float reach = ri + boundingRadius.get(j) + cutoff;
                            if (distSq <= reach * reach) {
                                if (cnt < MAXC) candPartner.set(i * MAXC + cnt, j);
                                cnt++;
                            }
                        }
                    }
                }
            }
            candCount.set(i, cnt);
        }
    }

    /**
     * O(N²) brute-force reference: per body i, test every j>i with the SAME predicate.
     * Per-body owned output slices, identical format to broadPhase. The grid candidate set
     * must EXACTLY equal this set (order-independent).
     */
    public static void bruteForce(
            FloatArray center,
            FloatArray boundingRadius,
            FloatArray gridParams,
            IntArray   counts,
            IntArray   brutePartner,
            IntArray   bruteCount) {

        int cap = center.getSize() / 3;
        int S   = counts.get(1);
        float cutoff = gridParams.get(5);
        int MAXC = SpatialGrid.MAX_CAND;

        for (@Parallel int i = 0; i < cap; i++) {
            bruteCount.set(i, 0);
            if (i >= S) continue;
            float icx = center.get(i), icy = center.get(cap + i), icz = center.get(2 * cap + i);
            float ri  = boundingRadius.get(i);
            int cnt = 0;
            for (int j = i + 1; j < S; j++) {
                float dx = icx - center.get(j);
                float dy = icy - center.get(cap + j);
                float dz = icz - center.get(2 * cap + j);
                float distSq = dx * dx + dy * dy + dz * dz;
                float reach = ri + boundingRadius.get(j) + cutoff;
                if (distSq <= reach * reach) {
                    if (cnt < MAXC) brutePartner.set(i * MAXC + cnt, j);
                    cnt++;
                }
            }
            bruteCount.set(i, cnt);
        }
    }
}
