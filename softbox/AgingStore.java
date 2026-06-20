package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 7 Aging build (A) — per-SEGMENT nucleotide-composition proxy state, attached to a FilamentStore.
 * Holds the 3-component composition (f_ATP, f_ADPPi, f_ADP) per segment + the cascade aging params + the
 * nucleotide-dependent depoly rate params. A NEW v2 representation (v1 has no per-segment proxy — it carries a
 * per-monomer Monomer list with a 3-state nucleotideState; the inc-6 assays ran noMonomersSimd=true ⇒ no
 * monomers at all). The proxy is faithful to v1's per-monomer cascade KINETICS in AGGREGATE, NOT a per-monomer
 * bit-match (recon §2 / CLAUDE.md §8). Default-off overall (an assay opts in).
 *
 * REPRESENTATION (3-component, jba's confirmed choice). Planar SoA, one extra scalar over the cheaper single
 * ADP-fraction so the viewer can show the full ATP→ADP-Pi→ADP cascade band:
 *   nucFrac[0*C + s] = f_ATP    (fraction of the segment's monomers still ATP)
 *   nucFrac[1*C + s] = f_ADPPi  (fraction in the ADP-Pi intermediate)
 *   nucFrac[2*C + s] = f_ADP    (fraction aged to ADP)   — sum == 1 per ACTIVE segment (conserved by the update)
 * The PHYSICS reads only f_ADP (the depoly rate); the intermediate f_ADPPi is carried for the visible cascade
 * (the viewer hook + a future band-aware viewer / analysis).
 *
 * AGING (the cascade, a deterministic forward-Euler ODE step per biochem cadence — NOT a stochastic draw; the
 * composition is a population fraction):
 *   pH = kHydrolysis·biochemDeltaT,  pD = kDissociation·biochemDeltaT   (DERIVED each cadence, never stale)
 *   f_ATP'   = f_ATP·(1 − pH)
 *   f_ADPPi' = f_ADPPi·(1 − pD) + f_ATP·pH
 *   f_ADP'   = 1 − f_ATP' − f_ADPPi'   (≡ f_ADP + f_ADPPi·pD in real arithmetic; the remainder form ANCHORS the
 *                                        per-segment sum to 1 each cadence, removing float32 drift accumulation)
 * Small p (3e-4 / 1e-3 per cadence) ⇒ the forward-Euler map tracks the continuous two-step analytic solution to
 * ~1e-4 (validated vs the analytic in AgingHarness gate A).
 *
 * NUCLEOTIDE-DEPENDENT DEPOLY RATE (depolyRateParams, DERIVED): the pointed terminal's ADP probability ≈ the
 * pointed segment's f_ADP interpolates the off-rate between ATP-off and ADP-off:
 *   P_depoly = pATP·(1 − f_ADP) + pADP·f_ADP,   pATP = kATPOff1·biochemDeltaT,  pADP = kADPOff1·biochemDeltaT.
 * DepolySystem.depolyProxy consumes this; DepolySystem.depoly (the Stage-1 FIXED rate) stays byte-unchanged.
 */
public final class AgingStore {

    public final int filCap;
    public final double biochemDeltaT;

    public final FloatArray nucFrac;          // 3·filCap (planar: ATP | ADPPi | ADP)
    public final FloatArray agingParams;      // 2: [0]=pH=kHydrolysis·bcΔt  [1]=pD=kDissociation·bcΔt
    public final IntArray   agingCounts;      // 2: [0]=C(capacity)  [1]=fires (1 on a biochem-cadence step, else 0)
    public final FloatArray depolyRateParams; // 2: [0]=pATP=kATPOff1·bcΔt  [1]=pADP=kADPOff1·bcΔt

    public AgingStore(int filCap) {
        this.filCap = filCap;
        this.biochemDeltaT = Constants.biochemDeltaT;
        nucFrac = new FloatArray(3 * filCap);
        agingParams = new FloatArray(2);
        agingCounts = new IntArray(2);
        agingCounts.set(0, filCap);
        depolyRateParams = new FloatArray(2);
        // the depoly proxy rate constants are FIXED (not pool-dependent) ⇒ derive once; refresh() re-derives anyway
        depolyRateParams.set(0, (float) (Constants.kATPOff1 * biochemDeltaT));
        depolyRateParams.set(1, (float) (Constants.kADPOff1 * biochemDeltaT));
    }

    /** Host, per cadence: DERIVE the cascade per-event probabilities from (kHydrolysis, kDissociation, biochemDeltaT)
     *  — never a stale copy (lock-step discipline). agingOn=false ⇒ pH=pD=0 (aging frozen). */
    public void refresh(boolean agingOn) {
        double pH = agingOn ? Constants.kHydrolysis   * biochemDeltaT : 0.0;
        double pD = agingOn ? Constants.kDissociation * biochemDeltaT : 0.0;
        agingParams.set(0, (float) pH);
        agingParams.set(1, (float) pD);
    }

    /** Host: set the cadence fire flag (fires=1 on biochem-cadence steps; the aging shares the depoly cadence). */
    public void setFires(boolean fires) { agingCounts.set(1, fires ? 1 : 0); }

    /** Host helper: initialise slot s to a pure ATP composition (a fresh-polymerized segment, v1 Monomer:62). */
    public void setATP(int s) { nucFrac.set(s, 1f); nucFrac.set(filCap + s, 0f); nucFrac.set(2 * filCap + s, 0f); }

    /** Host helper: set slot s to an explicit (ATP, ADPPi, ADP) composition (caller ensures sum≈1). */
    public void set(int s, float fATP, float fADPPi, float fADP) {
        nucFrac.set(s, fATP); nucFrac.set(filCap + s, fADPPi); nucFrac.set(2 * filCap + s, fADP);
    }

    public float fATP(int s)   { return nucFrac.get(s); }
    public float fADPPi(int s) { return nucFrac.get(filCap + s); }
    public float fADP(int s)   { return nucFrac.get(2 * filCap + s); }
}
