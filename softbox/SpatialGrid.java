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
