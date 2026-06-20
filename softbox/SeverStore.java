package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 7 Severing build (B) — cofilin en-masse whole-segment dissolve state, attached to a FilamentStore +
 * its AgingStore (reads f_ADP) + the shared ActinPool. A faithful aggregate port of v1's cofilin path
 * (FilSegment.checkCofilinDissolve:3741 + Monomer.cofilinBinding:243; recon §1e/§3b): cofilin decorates ADP
 * monomers, and a segment whose cofilin fraction exceeds cofilinRatio dissolves EN MASSE (all monomers to the
 * pool, the two neighbor sub-chains become separate filaments — the validated Stage-1 death path, REUSED).
 *
 * THE PROXY (a per-segment cofilin FRACTION, the aggregate of v1's per-monomer Bernoulli binding):
 *   p_cof = cofilinConc·cofilinRate·biochemDeltaT   (DERIVED each cadence; bundling-resisted /(bundleStableFactor·
 *           linkCt) when crosslinked — faithful formula, but the turnover assay carries NO crosslinkers ⇒ linkCt=0
 *           ⇒ unbundled, matching v1's linkCt=0 path exactly. Flagged: bundling is exercised only with the
 *           crosslinker subsystem present — a ring-integration concern.)
 *   f_cof' = f_cof + (f_ADP − f_cof)·p_cof   (only ADP-not-yet-cofilin monomers can bind ⇒ f_cof ≤ f_ADP)
 *   DISSOLVE when f_cof > cofilinRatio   (bit-for-decision with v1's cofilinCt/monomerCt > cofilinRatio).
 *
 * The dissolve REUSES the Stage-1 death path (DepolySystem.applyDeath): markFree + en-masse pool.put(monomerCount)
 * + break BOTH neighbor links → two valid reciprocal sub-chains + clear seedNode. SEPARATE death scratch from
 * depoly (severDeathFlag/severReturnedMon) so a slot that both depoly'd and dissolves in one cadence is counted
 * once per pass (conservation exact; the depoly pass runs to completion — markFree — BEFORE the sever pass, which
 * skips already-FREE slots).
 *
 * NOT MODELED (flagged, recon §4 / the "don't invent state" constraint): tropomyosin protection. In v1 tropo
 * (tropoOnRate=1.0, tropoOffRate=0 ⇒ sticks) competes with cofilin for monomers; the per-segment proxy carries no
 * tropo state, so cofilin binds unimpeded here. A Stage-4-style per-monomer fidelity item; the v1 AGGREGATE
 * length-distribution comparison (which would feel tropo) is the flagged FOLLOW-ON.
 */
public final class SeverStore {

    public final int filCap;
    public final double biochemDeltaT;

    public final FloatArray cofFrac;          // filCap; per-segment cofilin fraction (≤ f_ADP)
    public final FloatArray cofilinParams;    // 2: [0]=p_cof (derived)  [1]=cofilinRatio (threshold)
    public final IntArray   severCounts;      // 2: [0]=C  [1]=fires
    public final IntArray   severDeathFlag;   // filCap; cofilinDissolve writes 1 per dissolving segment
    public final IntArray   severReturnedMon; // filCap; = monomerCount of a dissolving segment (en-masse return)
    public final IntArray   severReturnedOffsets; // filCap+1; csrScan prefix; [C]=nReturned
    public final IntArray   severScanCounts;  // 4; csrScan bound ([3]=C)
    public double cofilinRatio;

    public SeverStore(int filCap, double cofilinRatio) {
        this.filCap = filCap;
        this.biochemDeltaT = Constants.biochemDeltaT;
        this.cofilinRatio = cofilinRatio;
        cofFrac = new FloatArray(filCap);
        cofilinParams = new FloatArray(2);
        cofilinParams.set(1, (float) cofilinRatio);
        severCounts = new IntArray(2);
        severCounts.set(0, filCap);
        severDeathFlag = new IntArray(filCap);
        severReturnedMon = new IntArray(filCap);
        severReturnedOffsets = new IntArray(filCap + 1);
        severScanCounts = new IntArray(4);
        severScanCounts.set(3, filCap);
    }

    /** Host, per cadence: DERIVE the cofilin binding probability p_cof = cofilinConc·cofilinRate·biochemDeltaT
     *  (never a stale copy). severOn=false ⇒ p_cof=0 (cofilin frozen). The unbundled rate (linkCt=0 here). */
    public void refresh(boolean severOn) {
        double p = severOn ? Constants.cofilinConc * Constants.cofilinRate * biochemDeltaT : 0.0;
        cofilinParams.set(0, (float) p);
        cofilinParams.set(1, (float) cofilinRatio);
    }

    public void setFires(boolean fires) { severCounts.set(1, fires ? 1 : 0); }
    public void setCofilinRatio(double r) { cofilinRatio = r; cofilinParams.set(1, (float) r); }

    public float fCof(int s) { return cofFrac.get(s); }
}
