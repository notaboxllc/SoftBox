package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 7 Severing build (B) — cofilin en-masse whole-segment dissolve (the SEVERING trigger + flagging). Two
 * device-agnostic kernels over the SoA arrays (GPU TaskGraph ≡ -cpu). A faithful AGGREGATE port of v1's cofilin
 * path (recon §1e/§3b): the per-segment cofilin FRACTION accumulates off the segment's ADP-fraction, and a segment
 * crossing cofilinRatio is flagged to dissolve. The dissolve itself REUSES the validated Stage-1 death path
 * (DepolySystem.applyDeath: markFree + en-masse pool.put + break BOTH neighbor links → two valid reciprocal
 * sub-chains + clear seedNode) — NOT re-derived here. ADDITIVE — touches no existing kernel; writes only its own
 * scratch (cofFrac + severDeathFlag/severReturnedMon).
 *
 * Race-free: cofilinAccumulate is a per-active-slot self-write; cofilinDissolve is a per-active-slot self-write of
 * its own death scratch; the link-break is in applyDeath (the gate-3b/4 disjoint-neighbor scatter — distinct
 * dissolving segments ⇒ distinct neighbor triples; one dissolve per filament per cadence is NOT guaranteed for an
 * arbitrary cofilin field, see the note). No atomics/KernelContext. No-op when fires==0 / p_cof==0 / cofilinRatio
 * ≥ 1 (cofFrac ≤ f_ADP ≤ 1 ⇒ never crosses) ⇒ a severing-OFF run is bit-identical to the aging baseline.
 *
 * NEIGHBOR-SCATTER SAFETY (flagged). applyDeath breaks a dissolving segment's links by writing its neighbors'
 * back-pointers. If TWO ADJACENT segments dissolve in the SAME cadence, both write the link between them — but to
 * the SAME value (−1), so the scatter is idempotent (race-free even for adjacent co-dissolves). A segment's OTHER
 * neighbor (away from the co-dissolving one) is a distinct slot. So the dissolve link-break stays race-free for an
 * arbitrary cofilin field (stronger than depoly's one-tip-per-filament guarantee). Validated CPU≡GPU (gate 4).
 */
public final class SeveringSystem {
    private SeveringSystem() {}

    /**
     * Accumulate the per-segment cofilin fraction one biochem cadence: f_cof += (f_ADP − f_cof)·p_cof — the
     * aggregate of v1's per-monomer Bernoulli binding over the ADP-not-yet-cofilin monomers (cofilin binds only
     * ADP ⇒ f_cof ≤ f_ADP). Reads nucFrac[2C+s] (f_ADP) + cofFrac, writes cofFrac. Per-active-slot self-write.
     * No-op when fires==0 or filState<0. cofilinParams[0]=p_cof; severCounts[1]=fires.
     */
    public static void cofilinAccumulate(IntArray filState, FloatArray nucFrac, FloatArray cofFrac,
                                         FloatArray cofilinParams, IntArray severCounts) {
        int C = filState.getSize();
        int fires = severCounts.get(1);
        float pCof = cofilinParams.get(0);
        for (@Parallel int s = 0; s < C; s++) {
            if (fires == 0) continue;
            if (filState.get(s) < 0) continue;
            float fADP = nucFrac.get(2 * C + s);
            float fCof = cofFrac.get(s);
            cofFrac.set(s, fCof + (fADP - fCof) * pCof);
        }
    }

    /**
     * Flag the dissolve: per ACTIVE segment, reset the sever death scratch, then if the cofilin fraction exceeds
     * cofilinRatio mark it to dissolve EN MASSE (severDeathFlag=1, severReturnedMon=monomerCount). Bit-for-decision
     * with v1's cofilinCt/monomerCt > cofilinRatio. The subsequent csrScan + pool.put + applyDeath (REUSED from
     * Stage 1) perform the actual dissolve. No-op (only zeros its scratch) when fires==0; never fires when
     * cofilinRatio ≥ 1. cofilinParams[1]=cofilinRatio; severCounts[1]=fires.
     */
    public static void cofilinDissolve(IntArray filState, IntArray monomerCount, FloatArray cofFrac,
                                       IntArray severDeathFlag, IntArray severReturnedMon,
                                       FloatArray cofilinParams, IntArray severCounts) {
        int C = filState.getSize();
        int fires = severCounts.get(1);
        float ratio = cofilinParams.get(1);
        for (@Parallel int s = 0; s < C; s++) {
            severDeathFlag.set(s, 0);
            severReturnedMon.set(s, 0);
            if (fires == 0) continue;
            if (filState.get(s) < 0) continue;            // FREE (incl. just depoly-killed this cadence)
            if (cofFrac.get(s) > ratio) {
                severDeathFlag.set(s, 1);
                severReturnedMon.set(s, monomerCount.get(s));   // en-masse return (whole segment)
            }
        }
    }
}
