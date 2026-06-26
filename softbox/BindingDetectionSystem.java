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

    /**
     * FUSED per-motor grid query (replaces broadPhase + invertCandidates + computeReachable for
     * the binding path). Parallel over MOTORS — each motor computes its center cell (from its head,
     * the SAME center+binning the body view publishes, so identical to bodyCell[motorSlot]), scans
     * the 27-cell neighborhood of the device-resident grid, and applies the EXACT reach predicate to
     * every SEGMENT body found, writing its own reachSeg/reachCount slice. No inversion, no atomics
     * (each motor owns its output slice) ⇒ great occupancy (nMotors threads) and the single-threaded
     * invertCandidates bottleneck is gone. Faithful to v1 GPUMotorBinding.bindKernel (the fused
     * broad+narrow per-motor grid walk). The reachable set is IDENTICAL to broadPhase→invert→
     * computeReachable (same 27 cells, same predicate) — gated grid==brute.
     *
     * Reuses the body view's publish convention: motor center = head; segments are STORE_FILAMENT.
     */
    public static void gridReachable(
            FloatArray head, FloatArray uVec, FloatArray rodUVec,
            FloatArray segEnd1, FloatArray segEnd2,
            FloatArray gridParams, IntArray gridDims,
            IntArray gridCellOffsets, IntArray gridCellContents,
            IntArray ownerStore, IntArray ownerSlot,
            IntArray motorReachSeg, IntArray motorReachCount,
            FloatArray kinParams, IntArray counts) {

        int nM   = counts.get(0);
        int nSeg = segEnd1.getSize() / 3;
        int MAXC = SpatialGrid.MAX_CAND;
        float myoColTol = kinParams.get(7), alignTol = kinParams.get(8);
        float xMin = gridParams.get(0), yMin = gridParams.get(1), zMin = gridParams.get(2);
        float invCell = gridParams.get(4);
        int nX = gridDims.get(0), nY = gridDims.get(1), nZ = gridDims.get(2);
        int nXY = nX * nY;

        for (@Parallel int m = 0; m < nM; m++) {
            float mx = head.get(m), my = head.get(nM + m), mz = head.get(2 * nM + m);
            float mux = uVec.get(m), muy = uVec.get(nM + m), muz = uVec.get(2 * nM + m);
            float rux = rodUVec.get(m), ruy = rodUVec.get(nM + m), ruz = rodUVec.get(2 * nM + m);

            // motor center cell (identical math to SpatialGrid.bodyCell over the published center=head)
            int cx = (int) ((mx - xMin) * invCell);
            int cy = (int) ((my - yMin) * invCell);
            int cz = (int) ((mz - zMin) * invCell);
            if (cx < 0) cx = 0; if (cx >= nX) cx = nX - 1;
            if (cy < 0) cy = 0; if (cy >= nY) cy = nY - 1;
            if (cz < 0) cz = 0; if (cz >= nZ) cz = nZ - 1;

            int x0 = cx - 1; if (x0 < 0) x0 = 0;
            int x1 = cx + 1; if (x1 >= nX) x1 = nX - 1;
            int y0 = cy - 1; if (y0 < 0) y0 = 0;
            int y1 = cy + 1; if (y1 >= nY) y1 = nY - 1;
            int z0 = cz - 1; if (z0 < 0) z0 = 0;
            int z1 = cz + 1; if (z1 >= nZ) z1 = nZ - 1;

            int out = 0;
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
                            if (ownerStore.get(j) == SpatialBodyView.STORE_FILAMENT) {
                                int s = ownerSlot.get(j);
                                // inlined reachTestDistSq (no helper call / early-return inside the deep
                                // nest — that triggers TornadoVM's "invalid variable" PTX lowering bug;
                                // broadPhase inlines for the same reason)
                                float e1x = segEnd1.get(s), e1y = segEnd1.get(nSeg + s), e1z = segEnd1.get(2 * nSeg + s);
                                float e2x = segEnd2.get(s), e2y = segEnd2.get(nSeg + s), e2z = segEnd2.get(2 * nSeg + s);
                                float r1x = e2x - e1x, r1y = e2y - e1y, r1z = e2z - e1z;
                                float denom = r1x * r1x + r1y * r1y + r1z * r1z;
                                boolean reach = false;
                                if (denom > 0f) {
                                    float r2x = mx - e1x, r2y = my - e1y, r2z = mz - e1z;
                                    float alpha = (r2x * r1x + r2y * r1y + r2z * r1z) / denom;
                                    if (alpha >= 0f && alpha <= 1f) {
                                        float cpx = e1x + alpha * r1x, cpy = e1y + alpha * r1y, cpz = e1z + alpha * r1z;
                                        float dx = cpx - mx, dy = cpy - my, dz = cpz - mz;
                                        float conDistSq = dx * dx + dy * dy + dz * dz;
                                        if (conDistSq < myoColTol * myoColTol) {
                                            float inv = 1.0f / (float) Math.sqrt(denom);
                                            float fux = r1x * inv, fuy = r1y * inv, fuz = r1z * inv;
                                            float motDotFil = mux * fux + muy * fuy + muz * fuz;
                                            if (motDotFil >= alignTol) {
                                                float rodDotFil = rux * fux + ruy * fuy + ruz * fuz;
                                                if (rodDotFil >= 0f) reach = true;
                                            }
                                        }
                                    }
                                }
                                if (reach) { if (out < MAXC) motorReachSeg.set(m * MAXC + out, s); out++; }
                            }
                        }
                    }
                }
            }
            motorReachCount.set(m, out);
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

    // ============================================================================================
    // BINDING-SEARCH REFORMULATION (flag-gated, default-off; the geometric bindNearest stays the
    // default v1-faithful comparator). The geometric search is a per-STEP geometric event — it
    // under-resolves binding at coarse dt (a head Brownian-jumps THROUGH the tight reach shell between
    // step boundaries, ∝√dt, never tested in-reach ⇒ binding/sim-time rises ~2.6× as dt→0; B*≈1050 at
    // the continuum). This reformulation makes the SEARCH a physical per-sim-time ENCOUNTER RATE at the
    // SAME tight capture geometry (NOT a fattened radius — see MYOSIN_BINDING_RATE_FORMULATION.md §4.2,
    // the fat-radius hack matched count but bound an over-stretched fat-tailed set). Borrowed from the
    // model-class-agnostic SEARCH kinetics of Cytosim (binding_rate·capture-volume) and MEDYAN
    // (k_on·Δl over the filament chord through the capture zone): P_bind = 1 − exp(−k_on·Δl·dt), Δl the
    // chord of filament through a small capture sphere (radius = myoColTol, fine-dt-tight). dt-invariance
    // is by construction: halving dt halves per-step P and doubles attempts ⇒ binding/sim-time is flat.
    //
    // SWEPT capture (formulation B): the instantaneous chord at the step-END head misses fast fly-bys
    // (the very encounters the coarse-dt geometric test drops). bindRate therefore uses the path-AVERAGE
    // chord over the head's swept segment [headPrev → head] this step — a Riemann sub-integral of the
    // continuous ∫chord(t)dt, so the per-encounter exposure is dt-INVARIANT even when the head crosses
    // the zone within one step. Formulation A (instantaneous) is the headPrev≡head special case (zero
    // sweep ⇒ point chord). NO atomics / KernelContext (race-free, per-motor-owned slot; bit-reproducible
    // RNG on both runners — CPU≡GPU aggregate-within-SEM like the stochastic catch-slip release).

    /** Snapshot the head pose into headPrev (the swept-search formulation B needs the PREVIOUS step's
     *  published head). Parallel copy of the 3·nMotors planar head buffer; no physics. */
    public static void snapshotHead(FloatArray head, FloatArray headPrev, IntArray counts) {
        int n = 3 * counts.get(0);
        for (@Parallel int i = 0; i < n; i++) headPrev.set(i, head.get(i));
    }

    /** gridReachable with the WIDENED candidate-gather radius kinParams[15] (the swept rate search needs
     *  segments the head's path may have crossed, not just those in-reach at the step end). IDENTICAL to
     *  gridReachable except the distance threshold (candReach instead of myoColTol[7]); the chord PHYSICS
     *  in bindRate keeps the tight myoColTol. Inlined reach test (the deep-nest helper-call PTX trap). */
    public static void gridReachableWide(
            FloatArray head, FloatArray uVec, FloatArray rodUVec,
            FloatArray segEnd1, FloatArray segEnd2,
            FloatArray gridParams, IntArray gridDims,
            IntArray gridCellOffsets, IntArray gridCellContents,
            IntArray ownerStore, IntArray ownerSlot,
            IntArray motorReachSeg, IntArray motorReachCount,
            FloatArray kinParams, IntArray counts) {

        int nM   = counts.get(0);
        int nSeg = segEnd1.getSize() / 3;
        int MAXC = SpatialGrid.MAX_CAND;
        float candReach = kinParams.get(15), alignTol = kinParams.get(8);
        if (candReach < kinParams.get(7)) candReach = kinParams.get(7);   // never tighter than myoColTol
        float xMin = gridParams.get(0), yMin = gridParams.get(1), zMin = gridParams.get(2);
        float invCell = gridParams.get(4);
        int nX = gridDims.get(0), nY = gridDims.get(1), nZ = gridDims.get(2);
        int nXY = nX * nY;

        for (@Parallel int m = 0; m < nM; m++) {
            float mx = head.get(m), my = head.get(nM + m), mz = head.get(2 * nM + m);
            float mux = uVec.get(m), muy = uVec.get(nM + m), muz = uVec.get(2 * nM + m);
            float rux = rodUVec.get(m), ruy = rodUVec.get(nM + m), ruz = rodUVec.get(2 * nM + m);

            int cx = (int) ((mx - xMin) * invCell);
            int cy = (int) ((my - yMin) * invCell);
            int cz = (int) ((mz - zMin) * invCell);
            if (cx < 0) cx = 0; if (cx >= nX) cx = nX - 1;
            if (cy < 0) cy = 0; if (cy >= nY) cy = nY - 1;
            if (cz < 0) cz = 0; if (cz >= nZ) cz = nZ - 1;
            int x0 = cx - 1; if (x0 < 0) x0 = 0;
            int x1 = cx + 1; if (x1 >= nX) x1 = nX - 1;
            int y0 = cy - 1; if (y0 < 0) y0 = 0;
            int y1 = cy + 1; if (y1 >= nY) y1 = nY - 1;
            int z0 = cz - 1; if (z0 < 0) z0 = 0;
            int z1 = cz + 1; if (z1 >= nZ) z1 = nZ - 1;

            int out = 0;
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
                            if (ownerStore.get(j) == SpatialBodyView.STORE_FILAMENT) {
                                int s = ownerSlot.get(j);
                                float e1x = segEnd1.get(s), e1y = segEnd1.get(nSeg + s), e1z = segEnd1.get(2 * nSeg + s);
                                float e2x = segEnd2.get(s), e2y = segEnd2.get(nSeg + s), e2z = segEnd2.get(2 * nSeg + s);
                                float r1x = e2x - e1x, r1y = e2y - e1y, r1z = e2z - e1z;
                                float denom = r1x * r1x + r1y * r1y + r1z * r1z;
                                boolean reach = false;
                                if (denom > 0f) {
                                    float r2x = mx - e1x, r2y = my - e1y, r2z = mz - e1z;
                                    float alpha = (r2x * r1x + r2y * r1y + r2z * r1z) / denom;
                                    if (alpha >= 0f && alpha <= 1f) {
                                        float cpx = e1x + alpha * r1x, cpy = e1y + alpha * r1y, cpz = e1z + alpha * r1z;
                                        float dx = cpx - mx, dy = cpy - my, dz = cpz - mz;
                                        float conDistSq = dx * dx + dy * dy + dz * dz;
                                        if (conDistSq < candReach * candReach) {
                                            float inv = 1.0f / (float) Math.sqrt(denom);
                                            float fux = r1x * inv, fuy = r1y * inv, fuz = r1z * inv;
                                            float motDotFil = mux * fux + muy * fuy + muz * fuz;
                                            if (motDotFil >= alignTol) {
                                                float rodDotFil = rux * fux + ruy * fuy + ruz * fuz;
                                                if (rodDotFil >= 0f) reach = true;
                                            }
                                        }
                                    }
                                }
                                if (reach) { if (out < MAXC) motorReachSeg.set(m * MAXC + out, s); out++; }
                            }
                        }
                    }
                }
            }
            motorReachCount.set(m, out);
        }
    }

    /**
     * The RATE-BASED stochastic capture (binding-search reformulation). A FREE_BINDABLE motor binds with
     * P = 1 − exp(−k_on·Δl_eff·dt), where Δl_eff is the path-AVERAGE chord of filament through the tight
     * capture sphere (radius = myoColTol) over the head's swept segment [headPrev → head] this step
     * (formulation B; formulation A = headPrev≡head ⇒ point chord at the step-end head). k_on = kinParams[14]
     * (µm^-1 s^-1), the physical encounter-rate handle. The capture GEOMETRY stays fine-dt-tight (the chord
     * uses myoColTol, NOT a fattened radius) ⇒ the bind site is a close approach (low cross-bridge stretch),
     * not the hack's over-stretched far pair. If binding fires, the segment is chosen ∝ its rate (a second
     * independent draw) and the bond site is the perpendicular foot of the CURRENT head. Per-motor-owned
     * slot, no atomics; wang-hash keyed (motor, step, seed) with the "BRAT" salt (distinct from the MOTOR
     * bind salt 0x4D54 and the catch-slip salt) ⇒ reproducible on both runners.
     */
    public static void bindRate(
            FloatArray head, FloatArray headPrev, FloatArray uVec, FloatArray rodUVec,
            FloatArray segEnd1, FloatArray segEnd2,
            IntArray motorCandSeg, IntArray motorCandCount,
            IntArray boundSeg, FloatArray bindArc,
            FloatArray kinParams, IntArray counts) {
        int nM = counts.get(0);
        int nSeg = segEnd1.getSize() / 3;
        int step = counts.get(1), seed = counts.get(2);
        int MAXC = SpatialGrid.MAX_CAND;
        float myoColTol = kinParams.get(7), alignTol = kinParams.get(8);
        float dt = kinParams.get(6);
        float kOn = kinParams.get(14);
        float r2 = myoColTol * myoColTol;
        final int NSAMP = 8;                              // swept-path quadrature samples (point chord when sweep=0)
        for (@Parallel int m = 0; m < nM; m++) {
            if (boundSeg.get(m) != MotorStore.FREE_BINDABLE) continue;
            float p1x = head.get(m), p1y = head.get(nM + m), p1z = head.get(2 * nM + m);
            float p0x = headPrev.get(m), p0y = headPrev.get(nM + m), p0z = headPrev.get(2 * nM + m);
            float mux = uVec.get(m), muy = uVec.get(nM + m), muz = uVec.get(2 * nM + m);
            float rux = rodUVec.get(m), ruy = rodUVec.get(nM + m), ruz = rodUVec.get(2 * nM + m);
            int cnt = motorCandCount.get(m); if (cnt > MAXC) cnt = MAXC;

            // pass 1: total effective encounter rate over the candidate segments (path-average chord)
            float totalRate = 0f;
            for (int k = 0; k < cnt; k++) {
                int s = motorCandSeg.get(m * MAXC + k);
                float e1x = segEnd1.get(s), e1y = segEnd1.get(nSeg + s), e1z = segEnd1.get(2 * nSeg + s);
                float e2x = segEnd2.get(s), e2y = segEnd2.get(nSeg + s), e2z = segEnd2.get(2 * nSeg + s);
                float r1x = e2x - e1x, r1y = e2y - e1y, r1z = e2z - e1z;
                float denom = r1x * r1x + r1y * r1y + r1z * r1z;
                if (denom <= 0f) continue;
                float inv = 1.0f / (float) Math.sqrt(denom);
                float fux = r1x * inv, fuy = r1y * inv, fuz = r1z * inv;
                float motDotFil = mux * fux + muy * fuy + muz * fuz;
                if (motDotFil < alignTol) continue;
                float rodDotFil = rux * fux + ruy * fuy + ruz * fuz;
                if (rodDotFil < 0f) continue;
                float chordSum = 0f;
                for (int i = 0; i < NSAMP; i++) {
                    float lam = (i + 0.5f) / NSAMP;
                    float px = p0x + lam * (p1x - p0x), py = p0y + lam * (p1y - p0y), pz = p0z + lam * (p1z - p0z);
                    float alpha = ((px - e1x) * r1x + (py - e1y) * r1y + (pz - e1z) * r1z) / denom;
                    if (alpha < 0f || alpha > 1f) continue;
                    float cpx = e1x + alpha * r1x, cpy = e1y + alpha * r1y, cpz = e1z + alpha * r1z;
                    float dx = cpx - px, dy = cpy - py, dz = cpz - pz;
                    float cds = dx * dx + dy * dy + dz * dz;
                    if (cds < r2) chordSum += 2.0f * (float) Math.sqrt(r2 - cds);
                }
                totalRate += kOn * (chordSum / NSAMP);
            }
            if (totalRate <= 0f) continue;
            float pBind = 1.0f - (float) Math.exp(-totalRate * dt);
            int base = (m * 1000003) ^ (step * 999983) ^ (seed * 7919) ^ 0x42524154;   // "BRAT" salt
            int h = wangHash(base);
            float u = (h >>> 1) / 2147483647.0f;
            if (u >= pBind) continue;

            // pass 2: select the segment ∝ its rate (second independent draw), bind at the CURRENT-head foot
            int h2 = wangHash(base ^ 0x5A5A5A5A);
            float target = (h2 >>> 1) / 2147483647.0f * totalRate;
            float acc = 0f; int chosen = -1; float chosenArc = 0f;
            for (int k = 0; k < cnt; k++) {
                int s = motorCandSeg.get(m * MAXC + k);
                float e1x = segEnd1.get(s), e1y = segEnd1.get(nSeg + s), e1z = segEnd1.get(2 * nSeg + s);
                float e2x = segEnd2.get(s), e2y = segEnd2.get(nSeg + s), e2z = segEnd2.get(2 * nSeg + s);
                float r1x = e2x - e1x, r1y = e2y - e1y, r1z = e2z - e1z;
                float denom = r1x * r1x + r1y * r1y + r1z * r1z;
                if (denom <= 0f) continue;
                float inv = 1.0f / (float) Math.sqrt(denom);
                float fux = r1x * inv, fuy = r1y * inv, fuz = r1z * inv;
                float motDotFil = mux * fux + muy * fuy + muz * fuz;
                if (motDotFil < alignTol) continue;
                float rodDotFil = rux * fux + ruy * fuy + ruz * fuz;
                if (rodDotFil < 0f) continue;
                float chordSum = 0f;
                for (int i = 0; i < NSAMP; i++) {
                    float lam = (i + 0.5f) / NSAMP;
                    float px = p0x + lam * (p1x - p0x), py = p0y + lam * (p1y - p0y), pz = p0z + lam * (p1z - p0z);
                    float alpha = ((px - e1x) * r1x + (py - e1y) * r1y + (pz - e1z) * r1z) / denom;
                    if (alpha < 0f || alpha > 1f) continue;
                    float cpx = e1x + alpha * r1x, cpy = e1y + alpha * r1y, cpz = e1z + alpha * r1z;
                    float dx = cpx - px, dy = cpy - py, dz = cpz - pz;
                    float cds = dx * dx + dy * dy + dz * dz;
                    if (cds < r2) chordSum += 2.0f * (float) Math.sqrt(r2 - cds);
                }
                acc += kOn * (chordSum / NSAMP);
                if (chosen < 0 && acc >= target && chordSum > 0f) {
                    chosen = s;
                    chosenArc = ((p1x - e1x) * r1x + (p1y - e1y) * r1y + (p1z - e1z) * r1z) * inv;
                }
            }
            if (chosen >= 0) { boundSeg.set(m, chosen); bindArc.set(m, chosenArc); }
        }
    }

    /**
     * Increment 6c (faithfulness fix): the v1 NODE-HELD binding exclusion, ported from
     * MyoMotor.checkFilSegCollision (BoA-v1ref boxOfActin/MyoMotor.java:391-392):
     *
     *     if (FilSegment.soaNodeAtEnd2[filId]) { return; }    // formin/node-held filament excluded
     *
     * A filament segment whose barbed end is held by a node's formin is excluded from ALL myosin binding. The
     * v2 analog of v1's per-segment barbed `nodeAtEnd2` is the node-held TIP: seedNode[s] >= 0 (the segment is
     * tethered to a node; split children / released / non-node filaments carry -1). This is the rule jba
     * remembered (v1's comment names an original own-node `&& myNode` form, superseded to exclude every
     * node-held filament). The skip is TIP-ONLY — v1 excludes only the barbed segment, leaving OUTER segments
     * bindable, so cross-capture survives on a held filament's overshoot/outer (seedNode<0) segments.
     *
     * Data-driven, no new kernel: a one-line skip in the candidate loop. For seedNode[s] < 0 (gliding /
     * contractile / Test A / any non-node-held filament) it never fires — those scenes call the original
     * (byte-unchanged) overloads and are unaffected. These node-aware overloads are used only by node-bearing
     * binding scenes (Test B). The reach overload is the candidate FILTER (the excluded tip never enters the
     * reach set); bindNearestNodeAware re-applies the skip defensively (faithful to v1's single shared predicate).
     */
    public static void bruteReachableNodeAware(
            FloatArray head, FloatArray uVec, FloatArray rodUVec,
            FloatArray segEnd1, FloatArray segEnd2,
            IntArray bruteReachSeg, IntArray bruteReachCount,
            IntArray seedNode,
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
                if (seedNode.get(s) >= 0) continue;          // v1 nodeAtEnd2 exclusion — node-held tip, not bindable
                float e1x = segEnd1.get(s), e1y = segEnd1.get(nSeg + s), e1z = segEnd1.get(2 * nSeg + s);
                float e2x = segEnd2.get(s), e2y = segEnd2.get(nSeg + s), e2z = segEnd2.get(2 * nSeg + s);
                float d = reachTestDistSq(mx, my, mz, mux, muy, muz, rux, ruy, ruz,
                        e1x, e1y, e1z, e2x, e2y, e2z, myoColTol, alignTol);
                if (d >= 0f) { if (out < MAXC) bruteReachSeg.set(m * MAXC + out, s); out++; }
            }
            bruteReachCount.set(m, out);
        }
    }

    /** bindNearest with the same v1 node-held (seedNode>=0) exclusion — defensive (the node-aware reach already
     *  filters the candidate set; this keeps the bind faithful to v1's single shared predicate). */
    public static void bindNearestNodeAware(
            FloatArray head, FloatArray uVec, FloatArray rodUVec,
            FloatArray segEnd1, FloatArray segEnd2,
            IntArray motorCandSeg, IntArray motorCandCount,
            IntArray boundSeg, FloatArray bindArc,
            IntArray seedNode,
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
                if (seedNode.get(s) >= 0) continue;          // v1 nodeAtEnd2 exclusion — node-held tip, not bindable
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
