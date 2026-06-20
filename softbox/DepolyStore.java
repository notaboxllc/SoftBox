package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 7 Stage 1 — actin TURNOVER state: the depoly-function attached to a FilamentStore + an ActinPool
 * (seam #2). Holds the per-cadence scalar params (the FIXED depoly probability, the death floor, the device
 * drag-recompute constants reused from growth) + the returned-monomer counter scratch (reused csrScan). The
 * depoly mechanics ride on the EXISTING FilamentStore lifecycle (filState + markFree + the scan-rank free-list).
 *
 *   depolyParams (float): [0]=P_depoly (= offRate·biochemDeltaT, HOST-refreshed each cadence; FIXED rate Stage 1)
 *                         [1]=actinMonoRadius (= halfmono)  [2]=actinSeed (the death floor)
 *   dragParams   (float): same 9 as GrowthStore (the device recomputeDrag constants — reused verbatim)
 *   depolyCounts (int)  : [0]=C(capacity) [1]=stepCount [2]=runSeed [3]=fires (1 on a biochem-cadence step, else 0)
 *
 * CADENCE / DERIVED PROBABILITY. biochemDeltaT (the 1e-3 s biochem clock) is a DECLARED coarser cadence over the
 * force dt (v2's first real multi-cadence). The depoly probability P = offRate·biochemDeltaT is DERIVED here from
 * (offRate, biochemDeltaT) — never a stale copy — and biochemCheckInt = round(biochemDeltaT/dt) sets the fire
 * cadence (depoly fires once per biochem interval; the per-event probability carries the biochemDeltaT factor, so
 * the per-unit-time rate is offRate). The pool is the seam-#2 host scalar: depoly RETURNS monomers (put) — the
 * reverse of growth's take.
 */
public final class DepolyStore {

    public final int filCap;
    public final double offRate;          // /s (Stage 1 fixed; default kATPOff1 — the pointed-end ATP-off rate)
    public final double biochemDeltaT;
    public final int    biochemCheckInt;  // steps per biochem interval
    public final ActinPool pool;          // seam #2 (shared with a GrowthStore, or own)

    public final FloatArray depolyParams; // 3
    public final FloatArray dragParams;   // 9 (reused recomputeDrag constants)
    public final IntArray   depolyCounts; // 4

    // ---- returned-monomer counter scratch (csrScan over returnedMon → returnedOffsets[C] = nReturned) ----
    public final IntArray   returnedMon;     // filCap; depoly writes 1 (shrank) or monomerCount (died) per slot
    public final IntArray   returnedOffsets; // filCap+1; exclusive prefix; [C] = nReturned
    public final IntArray   returnScanCounts;// 4; csrScan bound ([3]=C)
    public final IntArray   deathFlag;       // filCap; depoly writes 1 per dying slot; applyDeath consumes it

    public DepolyStore(int filCap, double offRate, double dt, ActinPool pool) {
        this.filCap = filCap;
        this.offRate = offRate;
        this.biochemDeltaT = Constants.biochemDeltaT;
        this.biochemCheckInt = Math.max(1, (int) Math.round(Constants.biochemDeltaT / dt));
        this.pool = pool;

        depolyParams = new FloatArray(3);
        depolyParams.set(1, (float) Constants.actinMonoRadius);
        depolyParams.set(2, (float) Constants.actinSeed);

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

        depolyCounts = new IntArray(4);
        depolyCounts.set(0, filCap);

        returnedMon = new IntArray(filCap);
        returnedOffsets = new IntArray(filCap + 1);
        returnScanCounts = new IntArray(4);
        returnScanCounts.set(3, filCap);
        deathFlag = new IntArray(filCap);
    }

    /** Host, per cadence: DERIVE the fixed depoly probability P = offRate·biochemDeltaT (never a stale copy).
     *  depolyOn=false ⇒ P=0 (no depoly). offRate in /s, biochemDeltaT in s ⇒ P dimensionless per event. */
    public void refreshRate(boolean depolyOn) {
        double P = depolyOn ? offRate * biochemDeltaT : 0.0;
        depolyParams.set(0, (float) P);
    }

    /** Host: set the per-step counters + the cadence fire flag (fires=1 on biochem-cadence steps). */
    public void setCounts(int step, int seed, boolean fires) {
        depolyCounts.set(1, step);
        depolyCounts.set(2, seed);
        depolyCounts.set(3, fires ? 1 : 0);
    }
    public boolean firesAt(int step) { return (step % biochemCheckInt) == 0; }

    /** Host, per cadence: RETURN to the pool the monomers freed this step (seam #2). nReturned = returnedOffsets[C]
     *  after the csrScan over returnedMon (depoly per-monomer + death en-masse). Returns nReturned. */
    public int returnPoolForDepoly() {
        int nReturned = returnedOffsets.get(filCap);
        if (nReturned > 0) pool.put(nReturned);
        return nReturned;
    }
}
