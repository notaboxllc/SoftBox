package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 4b-iii: the myosin nucleotide cycle (the mechanochemical engine of the stroke) + the
 * full force-dependent catch-slip release.
 *
 * Faithful port of v1 MyoMotor.biochemStep (MyoMotor.java:221-275): the per-motor 4-state machine
 * NONE→ATP→ADPPi→ADP→NONE, run EVERY step (biochemStep cadence = 1; per-step transition probability
 * rate·dt), with on/off-filament rate constants (Env.java:836-855) and the load-gated ADP→NONE step
 * (dissociateADP returns while the cross-bridge load forceDotFil's 10-window average > 0 —
 * MyoMotor.java:271). `isCocked() = !isADPPi`; the state drives the rest-angle switch (the stroke is
 * emergent from this, not a force). Stochastic transitions via the REUSED v1 wang-hash device RNG
 * keyed (slot, stepCount, runSeed) ⇒ reproducible on both runners.
 *
 * The release (catch-slip) is now the FULL Guo–Guilford form with F = forceDotFil (4a/4b-ii used the
 * F=0 limit): rate = kOff·(αCatch·e^(−F·xCatch/kT) + αSlip·e^(+F·xSlip/kT)). Load-dependent unbind.
 */
public final class NucleotideCycleSystem {
    private NucleotideCycleSystem() {}

    private static int wangHash(int seed) {
        seed = (seed ^ 61) ^ (seed >>> 16);
        seed *= 9; seed = seed ^ (seed >>> 4);
        seed *= 0x27d4eb2d; seed = seed ^ (seed >>> 15);
        return seed;
    }

    /** The 4-state cycle, one thread per motor, one RNG draw per step. */
    public static void cycle(IntArray nucleotideState, IntArray boundSeg,
                             FloatArray forceDotHist, FloatArray nucParams, IntArray counts) {
        int nM = nucleotideState.getSize();
        int step = counts.get(1), seed = counts.get(2);
        float dt = nucParams.get(0);
        float atpOn = nucParams.get(1);
        float onATP = nucParams.get(2), offATP = nucParams.get(3);
        float onPi = nucParams.get(4),  offPi = nucParams.get(5);
        float onADP = nucParams.get(6), offADP = nucParams.get(7);

        for (@Parallel int m = 0; m < nM; m++) {
            int state = nucleotideState.get(m);
            boolean bound = boundSeg.get(m) >= 0;
            int h = wangHash((m * 1000003) ^ (step * 999983) ^ (seed * 7919) ^ 0x4E55);  // NUC salt
            float u = (h >>> 1) / 2147483647.0f;

            if (state == MotorStore.NUC_NONE) {
                if (u < atpOn * dt) state = MotorStore.NUC_ATP;
            } else if (state == MotorStore.NUC_ATP) {
                float rate = bound ? onATP : offATP;
                if (u < rate * dt) state = MotorStore.NUC_ADPPI;
            } else if (state == MotorStore.NUC_ADPPI) {
                float rate = bound ? onPi : offPi;
                if (u < rate * dt) state = MotorStore.NUC_ADP;
            } else { // ADP → NONE, gated on the cross-bridge load (10-window forceDotFil average > 0)
                float avg = 0f;
                int b = m * 10;
                for (int k = 0; k < 10; k++) avg += forceDotHist.get(b + k);
                avg *= 0.1f;
                if (avg <= 0f) {
                    float rate = bound ? onADP : offADP;
                    if (u < rate * dt) state = MotorStore.NUC_NONE;
                }
            }
            nucleotideState.set(m, state);
        }
    }

    /**
     * Force-dependent catch-slip release (MyoFilLink.ckRelease, full Guo–Guilford form). A bound motor
     * releases at rate kOff·(αCatch·e^(−F·xCatch/kT)+αSlip·e^(+F·xSlip/kT)), F = forceDotFil; one-step
     * cooldown via FREE_COOLDOWN. RNG keyed (slot, step, seed) with a distinct salt. stats[2m]=bound
     * steps, stats[2m+1]=releases. (Binding remains geometric, handled by BindingDetectionSystem.)
     */
    public static void catchSlipRelease(IntArray boundSeg, FloatArray forceDotFil, FloatArray forceMag, IntArray cooldown,
                                        IntArray stats, IntArray capStats, FloatArray kinParams, IntArray counts) {
        int nM = boundSeg.getSize();
        int step = counts.get(1), seed = counts.get(2);
        float kOff = kinParams.get(0), aCatch = kinParams.get(1), aSlip = kinParams.get(2);
        float xCatch = kinParams.get(3), xSlip = kinParams.get(4), kT = kinParams.get(5), dt = kinParams.get(6);
        int refractorySteps = (int) kinParams.get(10);   // ceil(myoRebindTime/dt); 0 = no refractory (-norefractory)
        float breakForceN = kinParams.get(11);           // §6.10 v1 myosinBreakForce·1e-12 (N)
        boolean capOn = kinParams.get(12) > 0.5f;         // §6.10 -faithfulrelease toggle (default off)
        float blockProb = kinParams.get(13);             // §6.11 P(release enters the 1-step refractory); 1.0 = HEAD

        for (@Parallel int m = 0; m < nM; m++) {
            int bs = boundSeg.get(m);
            if (bs >= 0) {
                stats.set(2 * m, stats.get(2 * m) + 1);
                // §6.11 rate-faithful refractory: decide ONCE per motor/step whether a release this step
                // enters the dt-correct cooldown. HEAD (blockProb≥1) ⇒ always enter (deterministic, the
                // RNG draw is unused ⇒ bit-identical). -faithfulrefractory (blockProb=0.31) ⇒ enter only
                // ~31% of releases, matching v1's GPU-oracle effective block rate (§6.6). Race-free:
                // per-(motor,step,seed) wang-hash with a distinct salt — perturbs no other motor's draw.
                boolean enterRefractory = refractorySteps > 0;
                if (blockProb < 1.0f) {
                    int rh = wangHash((m * 1000003) ^ (step * 999983) ^ (seed * 7919) ^ 0x52465241);  // refractory salt
                    float ru = (rh >>> 1) / 2147483647.0f;
                    enterRefractory = enterRefractory && (ru < blockProb);
                }
                // §6.10 — v1 MyoFilLink.ckRelease FIRST branch: deterministic force-cap release. If the
                // cross-bridge spring magnitude exceeds myosinBreakForce, detach this step and SKIP the
                // catch-slip draw (v1's `release(); return;`). v1's inRigor gate has no v2 analog (v2 has
                // no rigor state) so the order collapses to break-force → catch-slip. The release target +
                // refractory match the catch-slip path exactly (v1 routes both through the same release()).
                // RNG is wang-hash-keyed per (motor,step), so pre-empting the draw perturbs no other motor.
                if (capOn && forceMag.get(m) > breakForceN) {
                    capStats.set(m, capStats.get(m) + 1);
                    if (enterRefractory) { boundSeg.set(m, MotorStore.FREE_COOLDOWN); cooldown.set(m, refractorySteps); }
                    else { boundSeg.set(m, MotorStore.FREE_BINDABLE); }
                    continue;
                }
                float F = forceDotFil.get(m);
                float rate = kOff * (aCatch * (float) Math.exp(-F * xCatch / kT) + aSlip * (float) Math.exp(F * xSlip / kT));
                int h = wangHash((m * 1000003) ^ (step * 999983) ^ (seed * 7919) ^ 0x4D54);  // release salt
                float u = (h >>> 1) / 2147483647.0f;
                if (u < rate * dt) {
                    stats.set(2 * m + 1, stats.get(2 * m + 1) + 1);
                    // dt-correct refractory: hold for refractorySteps steps (fixed PHYSICAL time). At the
                    // production dt this is 1 ⇒ FREE_COOLDOWN for one step, bit-identical to the old
                    // unconditional one-step transition. 0 ⇒ immediately bindable (-norefractory bracket).
                    // §6.11: enterRefractory folds in the probabilistic (rate-faithful) entry above.
                    if (enterRefractory) { boundSeg.set(m, MotorStore.FREE_COOLDOWN); cooldown.set(m, refractorySteps); }
                    else { boundSeg.set(m, MotorStore.FREE_BINDABLE); }
                }
            } else if (bs == MotorStore.FREE_COOLDOWN) {
                int c = cooldown.get(m) - 1;
                if (c <= 0) { boundSeg.set(m, MotorStore.FREE_BINDABLE); }   // refractory elapsed
                else { cooldown.set(m, c); }
            }
        }
    }
}
