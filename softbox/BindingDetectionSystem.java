package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 4a: binding detection + bind/unbind kinetics — the FIRST narrow-phase
 * consumer of the broad-phase. This is the only place motor↔segment type logic lives;
 * the spatial grid / broad-phase stayed entity-agnostic and UNCHANGED.
 *
 * Pipeline (all device-resident, dual-runner — same methods on the GPU TaskGraph and
 * the -cpu runner):
 *
 *   invertCandidates  consume the broad-phase candidate pairs (per-body owned slices),
 *                     FILTER by ownerStore to motor↔segment pairs (either ordering), and
 *                     scatter them into per-motor candidate-segment lists. Single-thread
 *                     serial ⇒ race-free, deterministic, bit-identical CPU↔GPU.
 *   computeReachable  per motor, apply the EXACT v1 bind-reach predicate to its candidate
 *                     segments ⇒ the reachable set (grid path, the exactness-gate output).
 *   bruteReachable    per motor, apply the SAME predicate to EVERY segment ⇒ the O(N²)
 *                     reachable-set reference. computeReachable must equal this EXACTLY.
 *   bindKinetics      per motor, faithful v1 mechanism: a FREE motor with a segment in
 *                     reach binds DETERMINISTICALLY to the nearest; a BOUND motor releases
 *                     STOCHASTICALLY via the Guo–Guilford catch-slip rate (Env.java). At
 *                     zero force (no power stroke this increment) the release probability
 *                     reduces EXACTLY to p = kOff·dt. RNG is the reused v1 wang-hash keyed
 *                     (slot, stepCount, runSeed) ⇒ reproducible on both runners.
 *
 * The bind-reach predicate (reachTestDistSq) is v1 MyoMotor.checkFilSegCollision
 * (MyoMotor.java:381-423): perpendicular drop of the motor head onto the segment, gated
 * by α∈[0,1] (head projects onto the segment), conDist < myoColTol, the head-axis
 * alignment gate (motDotFil ≥ alignTol), and the rod-not-pointing-away gate
 * (rodDotFil ≥ 0). ONE predicate, called by both the grid path and the brute reference,
 * so the gate tests the broad-phase superset, not the predicate.
 *
 * BOUND MOTORS APPLY NO FORCE THIS INCREMENT — power stroke, surface confinement, and
 * gliding velocity are 4b. So FDT / deflection / chain are unaffected.
 */
public final class BindingDetectionSystem {
    private BindingDetectionSystem() {}

    // Wang hash — the reused v1 device-RNG primitive (GPUMoveThing.java:1083-1089;
    // identical to BrownianForceSystem.wangHash). Integer arithmetic ⇒ bit-identical
    // on the GPU TaskGraph and the -cpu runner.
    private static int wangHash(int seed) {
        seed = (seed ^ 61) ^ (seed >>> 16);
        seed *= 9;
        seed = seed ^ (seed >>> 4);
        seed *= 0x27d4eb2d;
        seed = seed ^ (seed >>> 15);
        return seed;
    }

    /**
     * The exact v1 bind-reach predicate. Returns the perpendicular distance² (≥0) if the
     * (motor head, segment) pair is bindable, else -1. Port of MyoMotor.checkFilSegCollision.
     */
    private static float reachTestDistSq(
            float mx, float my, float mz,        // motor head (bindTip)
            float mux, float muy, float muz,     // motor head axis
            float rux, float ruy, float ruz,     // motor rod axis
            float e1x, float e1y, float e1z,     // segment end1
            float e2x, float e2y, float e2z,     // segment end2
            float myoColTol, float alignTol) {

        float r1x = e2x - e1x, r1y = e2y - e1y, r1z = e2z - e1z;     // segment vector
        float denom = r1x * r1x + r1y * r1y + r1z * r1z;
        if (denom <= 0f) return -1f;
        float r2x = mx - e1x, r2y = my - e1y, r2z = mz - e1z;
        float numer = r2x * r1x + r2y * r1y + r2z * r1z;
        float alpha = numer / denom;
        if (alpha < 0f || alpha > 1f) return -1f;                    // head off the segment ends
        float cpx = e1x + alpha * r1x, cpy = e1y + alpha * r1y, cpz = e1z + alpha * r1z;
        float dx = cpx - mx, dy = cpy - my, dz = cpz - mz;
        float conDistSq = dx * dx + dy * dy + dz * dz;
        if (conDistSq >= myoColTol * myoColTol) return -1f;          // out of reach
        float inv = 1.0f / (float) Math.sqrt(denom);
        float fux = r1x * inv, fuy = r1y * inv, fuz = r1z * inv;     // filament unit axis
        float motDotFil = mux * fux + muy * fuy + muz * fuz;
        if (motDotFil < alignTol) return -1f;                        // head misaligned
        float rodDotFil = rux * fux + ruy * fuy + ruz * fuz;
        if (rodDotFil < 0f) return -1f;                              // rod points away
        return conDistSq;
    }

    /**
     * Consume the broad-phase candidate pairs and FILTER by ownerStore to motor↔segment
     * pairs, scattering each into the partner motor's candidate-segment list. Handles
     * BOTH pair orderings (motor=i/seg=j and seg=i/motor=j), so it is independent of how
     * the two publishers were laid out in the body view. Single-thread serial ⇒ race-free
     * and deterministic (bodies in index order, candidates in slice order). motorCandCount
     * may exceed MAX_CAND (overflow) — the host detects and reports it.
     */
    public static void invertCandidates(
            IntArray candPartner, IntArray candCount,   // broad-phase output (per body)
            IntArray ownerStore,  IntArray ownerSlot,   // body-view back-pointers
            IntArray viewCounts,                         // [1] = S (active bodies)
            IntArray motorCounts,                        // [0] = nMotors
            IntArray motorCandSeg, IntArray motorCandCount) {
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int S  = viewCounts.get(1);
            int nM = motorCounts.get(0);
            int MAXC = SpatialGrid.MAX_CAND;
            for (int m = 0; m < nM; m++) motorCandCount.set(m, 0);
            for (int i = 0; i < S; i++) {
                int storeI = ownerStore.get(i);
                int cnt = candCount.get(i);
                if (cnt > MAXC) cnt = MAXC;
                int base = i * MAXC;
                for (int k = 0; k < cnt; k++) {
                    int j = candPartner.get(base + k);
                    int storeJ = ownerStore.get(j);
                    int motor = -1, seg = -1;
                    if (storeI == SpatialBodyView.STORE_MOTOR && storeJ == SpatialBodyView.STORE_FILAMENT) {
                        motor = ownerSlot.get(i); seg = ownerSlot.get(j);
                    } else if (storeI == SpatialBodyView.STORE_FILAMENT && storeJ == SpatialBodyView.STORE_MOTOR) {
                        motor = ownerSlot.get(j); seg = ownerSlot.get(i);
                    }
                    if (motor >= 0) {
                        int c = motorCandCount.get(motor);
                        if (c < MAXC) motorCandSeg.set(motor * MAXC + c, seg);
                        motorCandCount.set(motor, c + 1);
                    }
                }
            }
        }
    }

    /** Grid path: per motor, the exact predicate over its broad-phase candidate segments. */
    public static void computeReachable(
            FloatArray head, FloatArray uVec, FloatArray rodUVec,
            FloatArray segEnd1, FloatArray segEnd2,
            IntArray motorCandSeg, IntArray motorCandCount,
            IntArray motorReachSeg, IntArray motorReachCount,
            FloatArray kinParams, IntArray counts) {
        int nM = counts.get(0);
        int nSeg = segEnd1.getSize() / 3;
        int MAXC = SpatialGrid.MAX_CAND;
        float myoColTol = kinParams.get(7), alignTol = kinParams.get(8);
        for (@Parallel int m = 0; m < nM; m++) {
            float mx = head.get(m), my = head.get(nM + m), mz = head.get(2 * nM + m);
            float mux = uVec.get(m), muy = uVec.get(nM + m), muz = uVec.get(2 * nM + m);
            float rux = rodUVec.get(m), ruy = rodUVec.get(nM + m), ruz = rodUVec.get(2 * nM + m);
            int cnt = motorCandCount.get(m); if (cnt > MAXC) cnt = MAXC;
            int out = 0;
            for (int k = 0; k < cnt; k++) {
                int s = motorCandSeg.get(m * MAXC + k);
                float e1x = segEnd1.get(s), e1y = segEnd1.get(nSeg + s), e1z = segEnd1.get(2 * nSeg + s);
                float e2x = segEnd2.get(s), e2y = segEnd2.get(nSeg + s), e2z = segEnd2.get(2 * nSeg + s);
                float d = reachTestDistSq(mx, my, mz, mux, muy, muz, rux, ruy, ruz,
                        e1x, e1y, e1z, e2x, e2y, e2z, myoColTol, alignTol);
                if (d >= 0f) { if (out < MAXC) motorReachSeg.set(m * MAXC + out, s); out++; }
            }
            motorReachCount.set(m, out);
        }
    }

    /** O(N²) reference: per motor, the same exact predicate over EVERY segment. */
    public static void bruteReachable(
            FloatArray head, FloatArray uVec, FloatArray rodUVec,
            FloatArray segEnd1, FloatArray segEnd2,
            IntArray bruteReachSeg, IntArray bruteReachCount,
            FloatArray kinParams, IntArray counts) {
        int nM = counts.get(0);
        int nSeg = segEnd1.getSize() / 3;
        int MAXC = SpatialGrid.MAX_CAND;
        float myoColTol = kinParams.get(7), alignTol = kinParams.get(8);
        for (@Parallel int m = 0; m < nM; m++) {
            float mx = head.get(m), my = head.get(nM + m), mz = head.get(2 * nM + m);
            float mux = uVec.get(m), muy = uVec.get(nM + m), muz = uVec.get(2 * nM + m);
            float rux = rodUVec.get(m), ruy = rodUVec.get(nM + m), ruz = rodUVec.get(2 * nM + m);
            int out = 0;
            for (int s = 0; s < nSeg; s++) {
                float e1x = segEnd1.get(s), e1y = segEnd1.get(nSeg + s), e1z = segEnd1.get(2 * nSeg + s);
                float e2x = segEnd2.get(s), e2y = segEnd2.get(nSeg + s), e2z = segEnd2.get(2 * nSeg + s);
                float d = reachTestDistSq(mx, my, mz, mux, muy, muz, rux, ruy, ruz,
                        e1x, e1y, e1z, e2x, e2y, e2z, myoColTol, alignTol);
                if (d >= 0f) { if (out < MAXC) bruteReachSeg.set(m * MAXC + out, s); out++; }
            }
            bruteReachCount.set(m, out);
        }
    }

    /**
     * Faithful v1 kinetics. FREE_BINDABLE + a reachable segment ⇒ deterministic bind to the
     * nearest (no RNG, v1 binds on contact). BOUND ⇒ stochastic release at the catch-slip
     * rate (zero force ⇒ p = kOff·dt). One-step rebind refractory via the FREE_COOLDOWN
     * sentinel (v1 myoRebindTime 1e-5 s < dt). Per-motor stats accumulate (race-free):
     * stats[2m] = bound-step count, stats[2m+1] = release-event count.
     */
    public static void bindKinetics(
            FloatArray head, FloatArray uVec, FloatArray rodUVec,
            FloatArray segEnd1, FloatArray segEnd2,
            IntArray motorCandSeg, IntArray motorCandCount,
            IntArray boundSeg, FloatArray bindArc,
            IntArray stats,
            FloatArray kinParams, IntArray counts) {
        int nM = counts.get(0);
        int nSeg = segEnd1.getSize() / 3;
        int step = counts.get(1), seed = counts.get(2);
        int MAXC = SpatialGrid.MAX_CAND;
        float kOff = kinParams.get(0), aCatch = kinParams.get(1), aSlip = kinParams.get(2);
        float xCatch = kinParams.get(3), xSlip = kinParams.get(4), kT = kinParams.get(5), dt = kinParams.get(6);
        float myoColTol = kinParams.get(7), alignTol = kinParams.get(8), F = kinParams.get(9);

        // per-step release probability — v1 linear form p = rate·dt (MyoFilLink.ckRelease).
        // forceDotFil F = 0 this increment ⇒ catch+slip = aCatch+aSlip = 1 ⇒ p = kOff·dt.
        float rate = kOff * (aCatch * (float) Math.exp(-F * xCatch / kT) + aSlip * (float) Math.exp(F * xSlip / kT));
        float pOff = rate * dt;

        for (@Parallel int m = 0; m < nM; m++) {
            int bs = boundSeg.get(m);
            if (bs >= 0) {
                stats.set(2 * m, stats.get(2 * m) + 1);              // began the step bound
                int base = (m * 1000003) ^ (step * 999983) ^ (seed * 7919) ^ 0x4D54; // MOTOR salt
                int h = wangHash(base);
                float u = (h >>> 1) / 2147483647.0f;
                if (u < pOff) {
                    boundSeg.set(m, MotorStore.FREE_COOLDOWN);       // release → refractory
                    stats.set(2 * m + 1, stats.get(2 * m + 1) + 1);
                }
            } else if (bs == MotorStore.FREE_COOLDOWN) {
                boundSeg.set(m, MotorStore.FREE_BINDABLE);           // refractory elapsed
            } else { // FREE_BINDABLE — deterministic bind to the nearest reachable segment
                float mx = head.get(m), my = head.get(nM + m), mz = head.get(2 * nM + m);
                float mux = uVec.get(m), muy = uVec.get(nM + m), muz = uVec.get(2 * nM + m);
                float rux = rodUVec.get(m), ruy = rodUVec.get(nM + m), ruz = rodUVec.get(2 * nM + m);
                int cnt = motorCandCount.get(m); if (cnt > MAXC) cnt = MAXC;
                int bestSeg = -1; float bestD = 1.0e30f; float bestArc = 0f;
                for (int k = 0; k < cnt; k++) {
                    int s = motorCandSeg.get(m * MAXC + k);
                    float e1x = segEnd1.get(s), e1y = segEnd1.get(nSeg + s), e1z = segEnd1.get(2 * nSeg + s);
                    float e2x = segEnd2.get(s), e2y = segEnd2.get(nSeg + s), e2z = segEnd2.get(2 * nSeg + s);
                    float d = reachTestDistSq(mx, my, mz, mux, muy, muz, rux, ruy, ruz,
                            e1x, e1y, e1z, e2x, e2y, e2z, myoColTol, alignTol);
                    if (d >= 0f && d < bestD) {
                        bestD = d; bestSeg = s;
                        float r1x = e2x - e1x, r1y = e2y - e1y, r1z = e2z - e1z;
                        float denom = r1x * r1x + r1y * r1y + r1z * r1z;
                        float numer = (mx - e1x) * r1x + (my - e1y) * r1y + (mz - e1z) * r1z;
                        bestArc = numer / (float) Math.sqrt(denom);  // arc-length bind site
                    }
                }
                if (bestSeg >= 0) { boundSeg.set(m, bestSeg); bindArc.set(m, bestArc); }
            }
        }
    }

    /**
     * Geometric bind-only (increment 4b-iv): a FREE_BINDABLE motor with a reachable segment binds to
     * the nearest (the v1 predicate). Unbinding is handled separately by the F-dependent catch-slip
     * (NucleotideCycleSystem.catchSlipRelease) — so this is the bind half, the release the F-dependent
     * half. FREE_COOLDOWN → FREE_BINDABLE is also done by catchSlipRelease.
     */
    public static void bindNearest(
            FloatArray head, FloatArray uVec, FloatArray rodUVec,
            FloatArray segEnd1, FloatArray segEnd2,
            IntArray motorCandSeg, IntArray motorCandCount,
            IntArray boundSeg, FloatArray bindArc,
            FloatArray kinParams, IntArray counts) {
        int nM = counts.get(0);
        int nSeg = segEnd1.getSize() / 3;
        int MAXC = SpatialGrid.MAX_CAND;
        float myoColTol = kinParams.get(7), alignTol = kinParams.get(8);
        for (@Parallel int m = 0; m < nM; m++) {
            if (boundSeg.get(m) != MotorStore.FREE_BINDABLE) continue;
            float mx = head.get(m), my = head.get(nM + m), mz = head.get(2 * nM + m);
            float mux = uVec.get(m), muy = uVec.get(nM + m), muz = uVec.get(2 * nM + m);
            float rux = rodUVec.get(m), ruy = rodUVec.get(nM + m), ruz = rodUVec.get(2 * nM + m);
            int cnt = motorCandCount.get(m); if (cnt > MAXC) cnt = MAXC;
            int bestSeg = -1; float bestD = 1.0e30f; float bestArc = 0f;
            for (int k = 0; k < cnt; k++) {
                int s = motorCandSeg.get(m * MAXC + k);
                float e1x = segEnd1.get(s), e1y = segEnd1.get(nSeg + s), e1z = segEnd1.get(2 * nSeg + s);
                float e2x = segEnd2.get(s), e2y = segEnd2.get(nSeg + s), e2z = segEnd2.get(2 * nSeg + s);
                float d = reachTestDistSq(mx, my, mz, mux, muy, muz, rux, ruy, ruz,
                        e1x, e1y, e1z, e2x, e2y, e2z, myoColTol, alignTol);
                if (d >= 0f && d < bestD) {
                    bestD = d; bestSeg = s;
                    float r1x = e2x - e1x, r1y = e2y - e1y, r1z = e2z - e1z;
                    float denom = r1x * r1x + r1y * r1y + r1z * r1z;
                    float numer = (mx - e1x) * r1x + (my - e1y) * r1y + (mz - e1z) * r1z;
                    bestArc = numer / (float) Math.sqrt(denom);
                }
            }
            if (bestSeg >= 0) { boundSeg.set(m, bestSeg); bindArc.set(m, bestArc); }
        }
    }
}
