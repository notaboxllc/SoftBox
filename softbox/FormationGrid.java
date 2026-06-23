package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 5d: the dedicated, entity-agnostic spatial grid for crosslinker FORMATION (the STORE_CROSSLINKER
 * publisher). This retires the O(N²) single-threaded all-pairs enumeration (CrosslinkerSystem.filFilCandidates,
 * reqCap = nSeg(nSeg−1)/2 ≈ 288M @1×) — the last quadratic/serial kernel in the crosslinker pipeline — by
 * applying the validated binding broad-phase pattern (the parallel grid build + a fused per-segment 27-cell
 * neighborhood query) to formation. Holds all grid scratch + provides the build + candidate-query sequence.
 *
 * It is a DEDICATED grid (NOT the motor-binding grid): formation needs cell size ≥ maxSegLength + crossLinkGrabDist
 * (segment↔segment reach), whereas the binding grid is sized for motor-head↔segment reach (myoColTol) and bins
 * BOTH motors and segments. Cleanest + correct to keep them separate (the recon §"dedicated pass" branch). The
 * memory is modest (one body per segment): gridCellContents = nSeg ints, gridCellOffsets = totalCells+1, the
 * chunk matrix = numBodyChunks×totalCells; with the larger formation cell size totalCells is SMALL. Flagged as a
 * dedicated grid in the findings.
 *
 * The body view contains ONLY segments (body i ↔ segment i via FilamentStore.publishToBodyView), so
 * gridCellContents[idx] is the segment slot directly — the fused query (CrosslinkerSystem.gridForm*) needs no
 * ownerStore indirection. cellSize = 2·segBoundR + grab (= maxSegLength + 2·actinRadius + grab ≥ maxSegLength +
 * grab) makes the 27-cell stencil provably complete for the coarse capsule bound hi+½lenJ+grab.
 *
 * Both runners: every method is the standard SpatialGrid/CrosslinkerSystem kernel sequence over the SoA arrays
 * (no KernelContext / atomics) — bit-identical CPU↔GPU by the inc-3 counting-sort + the lexicographic emit.
 */
public final class FormationGrid {

    public final int nSeg;
    public final SpatialBodyView view;
    public final FloatArray viewParams;          // [0] = actinRadius
    public final FloatArray gridParams;          // [0..2]=origin [3]=cellSize [4]=invCell [5]=cutoff(=grab)
    public final IntArray   gridDims;            // [0..2]=nXYZ [3]=totalCells
    public final IntArray   gridCounts;          // [1] = S (= nSeg, all live)
    public final int        totalCells;

    public final IntArray   bodyCell;
    public final IntArray   cellCount;
    public final IntArray   chunkSum;
    public final IntArray   chunkParams;         // [0]=bodyChunkSize [1]=numBodyChunks
    public final IntArray   chunkCellCount;
    public final IntArray   gridCellOffsets;
    public final IntArray   gridCellContents;
    public final int        numBodyChunks;

    // fused-query scratch (the formation candidate fill)
    public final IntArray   candCountSeg;        // nSeg
    public final IntArray   candBaseSeg;         // nSeg+1

    /** @param gx,gy,gz  half-extents of the grid box (centred at origin); MUST contain every segment centre
     *                   (+cellSize margin), else a clamped out-of-box centre can miss partners.
     *  @param cellSize  ≥ maxSegLength + grab (completeness); cutoff stored = grab (the coarse bound). */
    public FormationGrid(int nSeg, double gx, double gy, double gz, double cellSize, double grab, double actinRadius) {
        this.nSeg = nSeg;
        view = new SpatialBodyView(nSeg); view.count = nSeg;
        viewParams = FloatArray.fromElements((float) actinRadius);
        int nX = 1 + (int) Math.ceil(2 * gx / cellSize);
        int nY = 1 + (int) Math.ceil(2 * gy / cellSize);
        int nZ = 1 + (int) Math.ceil(2 * gz / cellSize);
        totalCells = nX * nY * nZ;
        gridParams = FloatArray.fromElements((float) -gx, (float) -gy, (float) -gz,
                (float) cellSize, (float) (1.0 / cellSize), (float) grab);
        gridDims = IntArray.fromElements(nX, nY, nZ, totalCells);
        gridCounts = new IntArray(4); gridCounts.set(1, nSeg);
        bodyCell = new IntArray(nSeg); bodyCell.init(-1);
        cellCount = new IntArray(totalCells);
        chunkSum = new IntArray((totalCells + SpatialGrid.GRID_SCAN_CHUNK - 1) / SpatialGrid.GRID_SCAN_CHUNK + 1);
        int gbcs = SpatialGrid.bodyChunkSize(nSeg, totalCells);
        numBodyChunks = SpatialGrid.numBodyChunks(nSeg, gbcs);
        chunkParams = IntArray.fromElements(gbcs, numBodyChunks);
        chunkCellCount = new IntArray(numBodyChunks * totalCells); chunkCellCount.init(0);
        gridCellOffsets = new IntArray(totalCells + 1);
        gridCellContents = new IntArray(nSeg); gridCellContents.init(-1);
        candCountSeg = new IntArray(nSeg);
        candBaseSeg = new IntArray(nSeg + 1);
    }

    /** Build the grid (publish segments → bodyCell → parallel counting-sort) on the host (sequential runner). */
    public void buildCpu(FilamentStore fil) {
        FilamentStore.publishToBodyView(fil.coord, fil.segLength, view.center, view.boundingRadius,
                view.ownerStore, view.ownerSlot, viewParams, gridCounts);
        SpatialGrid.bodyCell(view.center, gridParams, gridDims, gridCounts, bodyCell);
        SpatialGrid.gridChunkZero(chunkParams, gridDims, chunkCellCount);
        SpatialGrid.gridChunkHistogram(bodyCell, gridCounts, chunkParams, gridDims, chunkCellCount);
        SpatialGrid.gridChunkReduce(gridDims, chunkParams, chunkCellCount, cellCount);
        SpatialGrid.gridScanLocal(gridDims, cellCount, gridCellOffsets, chunkSum);
        SpatialGrid.gridScanChunks(gridDims, chunkSum);
        SpatialGrid.gridScanAdd(gridDims, gridCellOffsets, gridCellContents, cellCount, chunkSum);
        SpatialGrid.gridChunkScatter(bodyCell, gridCounts, chunkParams, gridDims, gridCellOffsets, gridCellContents, chunkCellCount);
    }

    /** Fill xl.reqFilA/reqFilB (lexicographic, bit-identical to filFilCandidates) via the fused per-segment
     *  grid query on the host. The grid must already be built (buildCpu) for the current pose. */
    public void formCandidatesCpu(FilamentStore fil, IntArray filID, CrosslinkerStore xl) {
        CrosslinkerSystem.gridFormCount(fil.coord, fil.segLength, filID, gridParams, gridDims,
                gridCellOffsets, gridCellContents, candCountSeg, xl.formParams, xl.formCounts);
        CrosslinkerSystem.gridFormScan(candCountSeg, candBaseSeg, xl.reqFilA, xl.reqFilB, xl.formCounts);
        CrosslinkerSystem.gridFormEmit(fil.coord, fil.segLength, filID, gridParams, gridDims,
                gridCellOffsets, gridCellContents, candBaseSeg, candCountSeg, xl.reqFilA, xl.reqFilB, xl.formParams, xl.formCounts);
    }

    /** Largest true per-segment partner count this build (host diagnostic) — to detect FORM_MAXC overflow. */
    public int maxCandPerSeg() {
        int mx = 0;
        for (int s = 0; s < nSeg; s++) mx = Math.max(mx, candCountSeg.get(s));
        return mx;
    }
}
