package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 6c — actin POLYMERIZATION state (growth-only). The growth-function attached to a FilamentStore +
 * the actin pool (seam #2). Holds the per-cadence scalar params (the [actin]-dependent growth probability, the
 * split geometry, the device drag-recompute constants) + the grew-counter scratch reused by csrScan. The growth
 * mechanics ride on the EXISTING FilamentStore lifecycle (filState + the scan-rank allocator) and the B2
 * seedNode (the node bond identifies the growing tip) — this store adds only growth params, no new entity state.
 *
 *   growParams (float): [0]=P_grow (= onRate·conc·biochemDeltaT, HOST-refreshed each cadence) [1]=actinMonoRadius
 *                       [2]=maxMon (= 2·stdSegLength; grow stops here, waits for the split)
 *   splitParams(float): [0]=actinMonoRadius [1]=splitThresh (= 2·stdSegLength = 64) [2]=childMon (= stdSegLength = 32)
 *   dragParams (float): [0]=actinMonoRadius [1]=aeta [2]=radius [3]=kT [4]=aParallel [5]=aOrthog [6]=aTurning
 *                       [7]=stdSegLength [8]=minMonomerCt    (the device recomputeDrag constants)
 *   growCounts (int)  : [0]=C(capacity) [1]=stepCount [2]=runSeed [3]=fires (1 on a biochem-cadence step, else 0)
 *
 * The actin pool is the seam-#2 host scalar (ActinPool): the rate READS conc() (growth is first-order in
 * [actin], unlike B2's [actin]-independent nucleation), and each grown monomer DEPLETES it. biochemCheckInt =
 * round(biochemDeltaT/dt) sets the cadence (growth fires once per biochem interval; the per-event probability
 * carries the biochemDeltaT factor, so the per-unit-time rate is onRate·conc).
 */
public final class GrowthStore {

    public final int filCap;
    public final double onRate;          // µM⁻¹s⁻¹ (kATPOn2WithFormin)
    public final double biochemDeltaT;
    public final int    biochemCheckInt; // steps per biochem interval
    public final ActinPool pool;         // seam #2

    public final FloatArray growParams;  // 3
    public final FloatArray splitParams; // 3
    public final FloatArray dragParams;  // 9
    public final IntArray   growCounts;  // 4

    // ---- grew-counter scratch (csrScan over grewFlag → grewOffsets[C] = nGrew, for host pool depletion) ----
    public final IntArray   grewFlag;       // filCap; grow writes 1 per grown tip
    public final IntArray   grewOffsets;    // filCap+1; exclusive prefix; [C] = nGrew
    public final IntArray   grewScanCounts; // 4; csrScan bound ([3]=C)

    public GrowthStore(int filCap, double onRate, double dt, double pool0, double boxVolUm3) {
        this.filCap = filCap;
        this.onRate = onRate;
        this.biochemDeltaT = Constants.biochemDeltaT;
        this.biochemCheckInt = Math.max(1, (int) Math.round(Constants.biochemDeltaT / dt));
        this.pool = new ActinPool(pool0, boxVolUm3);

        growParams = new FloatArray(3);
        growParams.set(1, (float) Constants.actinMonoRadius);
        growParams.set(2, (float) (2 * Constants.stdSegLength));

        splitParams = new FloatArray(3);
        splitParams.set(0, (float) Constants.actinMonoRadius);
        splitParams.set(1, (float) (2 * Constants.stdSegLength));
        splitParams.set(2, (float) Constants.stdSegLength);

        dragParams = new FloatArray(9);
        dragParams.set(0, (float) Constants.actinMonoRadius);
        dragParams.set(1, (float) Constants.aeta);
        dragParams.set(2, (float) Constants.radius);
        dragParams.set(3, (float) Constants.kT);
        dragParams.set(4, (float) Constants.aParallel);
        dragParams.set(5, (float) Constants.aOrthog);
        dragParams.set(6, (float) Constants.aTurning);
        dragParams.set(7, (float) Constants.stdSegLength);
        dragParams.set(8, (float) Constants.minMonomerCt);

        growCounts = new IntArray(4);
        growCounts.set(0, filCap);

        grewFlag = new IntArray(filCap);
        grewOffsets = new IntArray(filCap + 1);
        grewScanCounts = new IntArray(4);
        grewScanCounts.set(3, filCap);
    }

    /** Host, per cadence: refresh the [actin]-dependent growth probability P = onRate·conc·biochemDeltaT (seam #2
     *  READ). growthOn=false ⇒ P=0 (no growth). conc in µM, onRate in µM⁻¹s⁻¹ ⇒ P dimensionless per event. */
    public void refreshRate(boolean growthOn) {
        double P = growthOn ? onRate * pool.conc() * biochemDeltaT : 0.0;
        growParams.set(0, (float) P);
    }

    /** Host: set the per-step counters + the cadence fire flag (fires=1 on biochem-cadence steps). */
    public void setCounts(int step, int seed, boolean fires) {
        growCounts.set(1, step);
        growCounts.set(2, seed);
        growCounts.set(3, fires ? 1 : 0);
    }
    public boolean firesAt(int step) { return (step % biochemCheckInt) == 0; }

    /** Host, per cadence: deplete the pool by the monomers grown this step (seam #2). nGrew = grewOffsets[C]
     *  after the csrScan over grewFlag. Returns nGrew. */
    public int depletePoolForGrows() {
        int nGrew = grewOffsets.get(filCap);
        if (nGrew > 0) pool.take(nGrew);
        return nGrew;
    }
}
