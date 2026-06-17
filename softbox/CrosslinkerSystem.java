package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 5a: the passive crosslinker static translational spring + the DOUBLE-ENDED
 * filament↔filament force+torque gather (the design centerpiece flagged in the recon §2:
 * the motor→segment CSR-inverse is single-ended; a crosslinker is double-ended).
 *
 * FORCE LAW — faithful port of v1 FilLink.applyTransForce (BoA-v1ref/boxOfActin/FilLink.java:198-217),
 * MINUS the strain track + ckLinkBreak (Bell unbinding = 5b) and torsion (deferred):
 *
 *   pt1 = end1(filA) + loc1·uVec(filA),   pt2 = end1(filB) + loc2·uVec(filB)
 *   linkVec = pt2 − pt1,   linkLength = |linkVec|
 *   stretch = max(linkLength − restLength, 0)              (restLength = 0.0125 µm)
 *   fracMove = 0.4 / max(linkCt(A), linkCt(B), 1)
 *   curForceMag = fracMove·1e-6·stretch / ( dt · (1/γ1 + 1/γ2) )   (γ = bTransGam.x, the
 *                                                                    PARALLEL drag of each filament)
 *   forceVec = curForceMag · linkVec        ← NOTE: linkVec is NOT normalized (v1 quirk — the
 *                                             force carries an extra linkLength factor; ported
 *                                             verbatim, do not "fix" it)
 *   A-side: +forceVec at pt1 (force + positional torque about filA COM)
 *   B-side: −forceVec at pt2 (force + positional torque about filB COM)
 *
 * THE PORTING SUBTLETY (the bail-critical one). v1's force is "damping-limited": the
 * /dt and the 1/(1/γ1+1/γ2) in curForceMag look like a force but encode "relax toward
 * rest by fracMove per step". When this force is consumed by the overdamped integrator
 * (RigidRodLangevinIntegrationSystem: v = 1e6·F/γ; pos += dt·v — line-for-line v1
 * FilSegment.moveThing), the /dt cancels the ·dt and the per-step relaxation is
 * dt-INDEPENDENT. Drag appears in BOTH the force-law denominator and the integrator —
 * this is v1's DESIGN, not a double-count: v2 reproduces v1's effective relaxation
 * exactly because it ports BOTH the force law (with its 1/Σγ⁻¹ denominator) AND the
 * integrator (with its ÷γ), unchanged. (Verified: the measured decay constant is
 * dt-invariant and matches the analytic form derived from v1's arithmetic — JOURNAL.)
 *
 * THE DOUBLE-ENDED GATHER. Each crosslinker computes its reaction ONCE and SELF-WRITES
 * both side-reactions into its own xlinkData row (race-free, like motor bondData). Then a
 * TWO-PASS single-ended gather: the validated CrossBridgeSystem CSR-inverse template
 * (csrHistogram→csrScan→csrScatter) is run VERBATIM twice — pass A keyed by linkFilA,
 * pass B keyed by linkFilB — and each pass sums the matching side-reaction into the
 * filament's forceSum/torqueSum (+=). Both passes are structurally identical to the
 * proven motor→segment gather, so race-free / no-atomics / no-KernelContext /
 * bit-identical-CPU↔GPU carry over by construction. Keyed by integer filament slots only.
 */
public final class CrosslinkerSystem {
    private CrosslinkerSystem() {}

    /** Compute each crosslinker's spring reaction ONCE; self-write A-side + B-side into xlinkData. */
    public static void linkForces(
            FloatArray filCoord, FloatArray filUVec, FloatArray filEnd1, FloatArray filBTransGam,
            IntArray linkFilA, IntArray linkFilB, FloatArray loc1, FloatArray loc2, IntArray filLinkCt,
            FloatArray xlinkData, FloatArray xlParams) {

        int nSeg = filCoord.getSize() / 3;
        int nLinks = linkFilA.getSize();
        double restLength = xlParams.get(0);
        double fracMoveBase = xlParams.get(1);
        double dt = xlParams.get(2);

        for (@Parallel int k = 0; k < nLinks; k++) {
            int d = k * CrosslinkerStore.STRIDE;
            for (int j = 0; j < CrosslinkerStore.STRIDE; j++) xlinkData.set(d + j, 0f);

            int a = linkFilA.get(k), b = linkFilB.get(k);
            // 5c-i: Design-A introduces mid-array FREE holes (key = -1). Skip them so we never index a
            // filament at -1 (OOB). Dead-but-keyed links (key>=0, linkState<0) are still computed here but
            // dropped by the gather's if(active) guard — bit-identical to 5b (their payload is never gathered).
            if (a < 0 || b < 0) continue;   // zeroed payload above ⇒ hole contributes nothing

            // pt1 on filA = end1(A) + loc1·uVec(A)
            double l1 = loc1.get(k);
            double uax = filUVec.get(a), uay = filUVec.get(nSeg + a), uaz = filUVec.get(2 * nSeg + a);
            double p1x = filEnd1.get(a) + l1 * uax;
            double p1y = filEnd1.get(nSeg + a) + l1 * uay;
            double p1z = filEnd1.get(2 * nSeg + a) + l1 * uaz;
            // pt2 on filB = end1(B) + loc2·uVec(B)
            double l2 = loc2.get(k);
            double ubx = filUVec.get(b), uby = filUVec.get(nSeg + b), ubz = filUVec.get(2 * nSeg + b);
            double p2x = filEnd1.get(b) + l2 * ubx;
            double p2y = filEnd1.get(nSeg + b) + l2 * uby;
            double p2z = filEnd1.get(2 * nSeg + b) + l2 * ubz;

            double lvx = p2x - p1x, lvy = p2y - p1y, lvz = p2z - p1z;
            double linkLength = Math.sqrt(lvx * lvx + lvy * lvy + lvz * lvz);
            double stretch = linkLength - restLength;
            if (stretch < 0.0) stretch = 0.0;

            int ctA = filLinkCt.get(a), ctB = filLinkCt.get(b);
            int maxLinks = ctA > ctB ? ctA : ctB;
            if (maxLinks < 1) maxLinks = 1;
            double fracMove = fracMoveBase / maxLinks;

            double g1 = filBTransGam.get(a);   // plane X = parallel drag (v1 bTransGam.x)
            double g2 = filBTransGam.get(b);
            double curForceMag = (fracMove * 1.0e-6 * stretch / dt) / (1.0 / g1 + 1.0 / g2);

            // forceVec = curForceMag · linkVec  (linkVec NOT normalized — v1 verbatim)
            double Fx = curForceMag * lvx, Fy = curForceMag * lvy, Fz = curForceMag * lvz;

            // A-side: +F at pt1, torque rA×F about filA COM (rA in metres)
            double rax = (p1x - filCoord.get(a)) * 1e-6;
            double ray = (p1y - filCoord.get(nSeg + a)) * 1e-6;
            double raz = (p1z - filCoord.get(2 * nSeg + a)) * 1e-6;
            double TAx = ray * Fz - raz * Fy;
            double TAy = raz * Fx - rax * Fz;
            double TAz = rax * Fy - ray * Fx;

            // B-side: −F at pt2, torque rB×(−F) about filB COM
            double nFx = -Fx, nFy = -Fy, nFz = -Fz;
            double rbx = (p2x - filCoord.get(b)) * 1e-6;
            double rby = (p2y - filCoord.get(nSeg + b)) * 1e-6;
            double rbz = (p2z - filCoord.get(2 * nSeg + b)) * 1e-6;
            double TBx = rby * nFz - rbz * nFy;
            double TBy = rbz * nFx - rbx * nFz;
            double TBz = rbx * nFy - rby * nFx;

            xlinkData.set(d,      (float) Fx);  xlinkData.set(d + 1,  (float) Fy);  xlinkData.set(d + 2,  (float) Fz);
            xlinkData.set(d + 3,  (float) TAx); xlinkData.set(d + 4,  (float) TAy); xlinkData.set(d + 5,  (float) TAz);
            xlinkData.set(d + 6,  (float) nFx); xlinkData.set(d + 7,  (float) nFy); xlinkData.set(d + 8,  (float) nFz);
            xlinkData.set(d + 9,  (float) TBx); xlinkData.set(d + 10, (float) TBy); xlinkData.set(d + 11, (float) TBz);
        }
    }

    /** Pass-A gather: each filament sums the A-side reactions (xlinkData[0..5]) of the
     *  crosslinkers keyed to it (linkFilA == s) into its own forceSum/torqueSum (+=).
     *  CSR built by the reused CrossBridgeSystem.csr* keyed by linkFilA. */
    public static void segGatherA(IntArray segOffsets, IntArray segLinkIdx, FloatArray xlinkData,
                                  IntArray linkState, FloatArray filForceSum, FloatArray filTorqueSum, IntArray counts) {
        int nSeg = counts.get(3);
        for (@Parallel int s = 0; s < nSeg; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            int start = segOffsets.get(s), end = segOffsets.get(s + 1);
            for (int k = start; k < end; k++) {
                int li = segLinkIdx.get(k);
                if (linkState.get(li) < 0) continue;     // 5b lifecycle guard: dead/free links contribute 0
                int d = li * CrosslinkerStore.STRIDE;
                fx += xlinkData.get(d);     fy += xlinkData.get(d + 1); fz += xlinkData.get(d + 2);
                tx += xlinkData.get(d + 3); ty += xlinkData.get(d + 4); tz += xlinkData.get(d + 5);
            }
            filForceSum.set(s,             (float) (filForceSum.get(s)             + fx));
            filForceSum.set(nSeg + s,      (float) (filForceSum.get(nSeg + s)      + fy));
            filForceSum.set(2 * nSeg + s,  (float) (filForceSum.get(2 * nSeg + s)  + fz));
            filTorqueSum.set(s,            (float) (filTorqueSum.get(s)            + tx));
            filTorqueSum.set(nSeg + s,     (float) (filTorqueSum.get(nSeg + s)     + ty));
            filTorqueSum.set(2 * nSeg + s, (float) (filTorqueSum.get(2 * nSeg + s) + tz));
        }
    }

    /** Pass-B gather: each filament sums the B-side reactions (xlinkData[6..11]) of the
     *  crosslinkers keyed to it (linkFilB == s) into the SAME forceSum/torqueSum (+=). */
    public static void segGatherB(IntArray segOffsets, IntArray segLinkIdx, FloatArray xlinkData,
                                  IntArray linkState, FloatArray filForceSum, FloatArray filTorqueSum, IntArray counts) {
        int nSeg = counts.get(3);
        for (@Parallel int s = 0; s < nSeg; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            int start = segOffsets.get(s), end = segOffsets.get(s + 1);
            for (int k = start; k < end; k++) {
                int li = segLinkIdx.get(k);
                if (linkState.get(li) < 0) continue;     // 5b lifecycle guard: dead/free links contribute 0
                int d = li * CrosslinkerStore.STRIDE;
                fx += xlinkData.get(d + 6); fy += xlinkData.get(d + 7);  fz += xlinkData.get(d + 8);
                tx += xlinkData.get(d + 9); ty += xlinkData.get(d + 10); tz += xlinkData.get(d + 11);
            }
            filForceSum.set(s,             (float) (filForceSum.get(s)             + fx));
            filForceSum.set(nSeg + s,      (float) (filForceSum.get(nSeg + s)      + fy));
            filForceSum.set(2 * nSeg + s,  (float) (filForceSum.get(2 * nSeg + s)  + fz));
            filTorqueSum.set(s,            (float) (filTorqueSum.get(s)            + tx));
            filTorqueSum.set(nSeg + s,     (float) (filTorqueSum.get(nSeg + s)     + ty));
            filTorqueSum.set(2 * nSeg + s, (float) (filTorqueSum.get(2 * nSeg + s) + tz));
        }
    }

    /** O(nLinks·nSeg) brute-force reference: each filament sums BOTH sides over ALL links
     *  (A-side where linkFilA==s, B-side where linkFilB==s). Writes (set) the reference for
     *  the gather-exactness gate. */
    public static void bruteGather(IntArray linkFilA, IntArray linkFilB, FloatArray xlinkData,
                                   IntArray linkState, FloatArray bForceSum, FloatArray bTorqueSum, IntArray counts) {
        int nSeg = counts.get(3), nLinks = counts.get(0);
        for (@Parallel int s = 0; s < nSeg; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            for (int k = 0; k < nLinks; k++) {
                if (linkState.get(k) < 0) continue;      // 5b lifecycle guard (matches the gather)
                int d = k * CrosslinkerStore.STRIDE;
                if (linkFilA.get(k) == s) {
                    fx += xlinkData.get(d);     fy += xlinkData.get(d + 1); fz += xlinkData.get(d + 2);
                    tx += xlinkData.get(d + 3); ty += xlinkData.get(d + 4); tz += xlinkData.get(d + 5);
                }
                if (linkFilB.get(k) == s) {
                    fx += xlinkData.get(d + 6); fy += xlinkData.get(d + 7);  fz += xlinkData.get(d + 8);
                    tx += xlinkData.get(d + 9); ty += xlinkData.get(d + 10); tz += xlinkData.get(d + 11);
                }
            }
            bForceSum.set(s, (float) fx); bForceSum.set(nSeg + s, (float) fy); bForceSum.set(2 * nSeg + s, (float) fz);
            bTorqueSum.set(s, (float) tx); bTorqueSum.set(nSeg + s, (float) ty); bTorqueSum.set(2 * nSeg + s, (float) tz);
        }
    }

    // ===================== 5b: Bell-model unbinding + EWMA(boxcar) strain track =====================

    /** Wang hash — the reused v1 device-RNG primitive (VERBATIM BrownianForceSystem/NucleotideCycleSystem).
     *  Integer arithmetic ⇒ bit-identical CPU↔GPU, race-free (no shared state, no atomics, no KernelContext). */
    private static int wangHash(int seed) {
        seed = (seed ^ 61) ^ (seed >>> 16);
        seed *= 9; seed = seed ^ (seed >>> 4);
        seed *= 0x27d4eb2d; seed = seed ^ (seed >>> 15);
        return seed;
    }

    /**
     * Bell-model unbinding (faithful port of v1 FilLink: applyTransForce strain register +
     * ckLinkBreak — FilLink.java:200-203/182-191), the DEATH half of the link lifecycle.
     *
     * Per ACTIVE link, exactly mirroring v1's per-step order (registerValue → averageVal → P_break):
     *   1. strain = max(linkLength − restLength, 0) / restLength            (v1 applyTransForce:200-202)
     *   2. push strain into the 10-slot BOXCAR ring (v1 ValueTracker.registerValue, circular write)
     *   3. aveStrain = sum(all 10)/10                                       (v1 ValueTracker.averageVal)
     *   4. k_off = linkOffConst + linkOffCoeff·exp(aveStrain·linkOffExp);  P_break = k_off·dt  (ckLinkBreak)
     *   5. draw u via wang-hash per (link, step, seed); if u < P_break ⇒ DEATH self-write (sentinel).
     *
     * v1 calls ckLinkBreak BEFORE applying force (returns on break ⇒ no force that step). Mirrored by
     * running `unbind` BEFORE `linkForces` in the step: a link breaking this step is FREE before the
     * force pass, so the gather guard drops it ⇒ no force this step. A dead link is skipped entirely
     * (no strain update, no break draw, no force) — it is inert. RNG salt 0x584C4B42 ("XLKB"), distinct
     * from the motor salts (NUC 0x4E55 / refractory 0x52465241 / release 0x4D54).
     *
     * One RNG draw per link per step ⇒ a WorkerGrid1D localWork=64 is required for this kernel
     * (CLAUDE.md RNG gotcha; the harness sets it).
     */
    public static void unbind(
            FloatArray filCoord, FloatArray filUVec, FloatArray filEnd1,
            IntArray linkFilA, IntArray linkFilB, FloatArray loc1, FloatArray loc2,
            IntArray linkState, FloatArray strainHist, IntArray strainPlace,
            FloatArray offParams, IntArray counts) {

        int nSeg = filCoord.getSize() / 3;
        int nLinks = counts.get(0);
        int step = counts.get(1), seed = counts.get(2);
        double offConst = offParams.get(0), offCoeff = offParams.get(1), offExp = offParams.get(2);
        double dt = offParams.get(3), restLength = offParams.get(4);
        int W = CrosslinkerStore.STRAIN_WIN;

        for (@Parallel int k = 0; k < nLinks; k++) {
            if (linkState.get(k) < 0) continue;     // FREE/DEAD: inert (no strain update, no break)

            int a = linkFilA.get(k), b = linkFilB.get(k);
            double l1 = loc1.get(k);
            double uax = filUVec.get(a), uay = filUVec.get(nSeg + a), uaz = filUVec.get(2 * nSeg + a);
            double p1x = filEnd1.get(a) + l1 * uax;
            double p1y = filEnd1.get(nSeg + a) + l1 * uay;
            double p1z = filEnd1.get(2 * nSeg + a) + l1 * uaz;
            double l2 = loc2.get(k);
            double ubx = filUVec.get(b), uby = filUVec.get(nSeg + b), ubz = filUVec.get(2 * nSeg + b);
            double p2x = filEnd1.get(b) + l2 * ubx;
            double p2y = filEnd1.get(nSeg + b) + l2 * uby;
            double p2z = filEnd1.get(2 * nSeg + b) + l2 * ubz;
            double lvx = p2x - p1x, lvy = p2y - p1y, lvz = p2z - p1z;
            double linkLength = Math.sqrt(lvx * lvx + lvy * lvy + lvz * lvz);
            double stretch = linkLength - restLength;
            if (stretch < 0.0) stretch = 0.0;
            double strain = stretch / restLength;

            // boxcar register (v1 ValueTracker.registerValue: write at place, advance circularly)
            int base = k * W;
            int p = strainPlace.get(k);
            strainHist.set(base + p, (float) strain);
            strainPlace.set(k, (p + 1) % W);

            // averageVal: sum all W slots / W (v1 divides by stepsToTrack always; initial zeros included)
            double ave = 0.0;
            for (int j = 0; j < W; j++) ave += strainHist.get(base + j);
            ave /= W;

            double kOff = offConst + offCoeff * Math.exp(ave * offExp);
            double pBreak = kOff * dt;
            int h = wangHash((k * 1000003) ^ (step * 999983) ^ (seed * 7919) ^ 0x584C4B42);  // XLKB break salt
            float u = (h >>> 1) / 2147483647.0f;
            if (u < pBreak) linkState.set(k, CrosslinkerStore.LINK_FREE);   // death = self-write sentinel
        }
    }

    // ============== 5c-i: Design-A scan-rank free-list allocator (no compaction, no atomics) ==============
    //
    // A formation phase reuses the validated single-threaded CrossBridgeSystem.csrScan prefix-sum VERBATIM
    // for BOTH prefix sums (free-list build + request rank). The two thin companions below (a flag kernel
    // and a stream-compaction scatter) are the standard prefix-sum-compaction idiom — single-threaded /
    // index-ordered ⇒ bit-identical CPU↔GPU, like csrScatter. Per step:
    //   freeFlags    : freeCount[s] = (linkState[s] is FREE) ? 1 : 0                 (s in [0,C))
    //   csrScan      : freeOffsets = exclusive prefix sum of freeCount; freeOffsets[C] = nFree   (REUSED)
    //   freeScatter  : freeList[freeOffsets[s]] = s for each FREE s (index order)
    //   csrScan      : rankOffsets = exclusive prefix sum of acceptFlag; rankOffsets[K] = nAccepted (REUSED)
    //   allocate     : request r (accepted) claims freeList[rankOffsets[r]] if rank < nFree (clamp), writes
    //                  its payload, inits a fresh strain ring, flips linkState FREE→ACTIVE. Distinct ranks
    //                  → distinct free slots ⇒ one writer per slot, race-free.

    /** freeCount[s] = 1 if slot s is FREE (linkState<0), else 0. Input to the free-list csrScan. */
    public static void freeFlags(IntArray linkState, IntArray freeCount, IntArray allocCounts) {
        int C = allocCounts.get(0);
        for (@Parallel int s = 0; s < C; s++) {
            freeCount.set(s, linkState.get(s) < 0 ? 1 : 0);
        }
    }

    /** Stream-compaction scatter: write each FREE slot index into freeList at its prefix-sum position
     *  (index order). Single-threaded (like csrScatter) ⇒ deterministic, bit-identical CPU↔GPU. Reads
     *  linkState directly (csrScan has zeroed freeCount as its cursor side-effect). */
    public static void freeScatter(IntArray linkState, IntArray freeOffsets, IntArray freeList, IntArray allocCounts) {
        int C = allocCounts.get(0);
        for (@Parallel int gid = 0; gid < 1; gid++) {
            for (int s = 0; s < C; s++) {
                if (linkState.get(s) < 0) freeList.set(freeOffsets.get(s), s);
            }
        }
    }

    /** Allocate: request r (accepted ⇒ rankOffsets[r+1] > rankOffsets[r]) with dense rank
     *  rank = rankOffsets[r] claims freeList[rank] when rank < nFree (= freeOffsets[C], the overflow
     *  clamp), writes its payload into that slot, inits a FRESH strain ring (all-zero + place 0), and
     *  flips linkState FREE→ACTIVE. Distinct accepted requests have distinct ranks → distinct freeList
     *  entries → distinct slots ⇒ one writer per slot (race-free, no atomics). Over-clamp requests
     *  (rank >= nFree) form nothing (reported not-formed by the caller via rank >= nFree). */
    public static void allocate(
            IntArray reqFilA, IntArray reqFilB, FloatArray reqLoc1, FloatArray reqLoc2,
            IntArray rankOffsets, IntArray freeList, IntArray freeOffsets,
            IntArray linkFilA, IntArray linkFilB, FloatArray loc1, FloatArray loc2,
            IntArray linkState, FloatArray strainHist, IntArray strainPlace, IntArray allocCounts) {
        int C = allocCounts.get(0), K = allocCounts.get(1), W = allocCounts.get(2);
        int nFree = freeOffsets.get(C);
        for (@Parallel int r = 0; r < K; r++) {
            int rank = rankOffsets.get(r);
            boolean accepted = rankOffsets.get(r + 1) > rank;   // accept-flag recovered from the rank scan
            if (!accepted) continue;
            if (rank >= nFree) continue;                        // overflow clamp: lowest nFree ranks form
            int slot = freeList.get(rank);
            linkFilA.set(slot, reqFilA.get(r));
            linkFilB.set(slot, reqFilB.get(r));
            loc1.set(slot, reqLoc1.get(r));
            loc2.set(slot, reqLoc2.get(r));
            int base = slot * W;
            for (int j = 0; j < W; j++) strainHist.set(base + j, 0f);   // fresh strain ring
            strainPlace.set(slot, 0);
            linkState.set(slot, CrosslinkerStore.LINK_ACTIVE);          // FREE → ACTIVE (self-write into the claimed slot)
        }
    }

    // ============== 5c-ii: crosslinker FORMATION (broad-phase FIL×FIL + checkToLink gates + P_form) ==============
    //
    // Replaces the 5c-i SYNTHETIC fillRequests with real formation that fills the SAME request arrays; the
    // 5c-i scan-rank allocator underneath is UNCHANGED. Per step (after 5b unbind, before the 5c-i allocator):
    //   filFilCandidates : broad-phase — distinct unordered cross-filament segment pairs within a coarse
    //                      capsule-bound → reqFilA[c]/reqFilB[c] (the candidate set; unused slots key −1).
    //   formGates        : per-candidate-LOCAL gates (each writes its own flag/payload — race-free): the v1
    //                      checkToLink alignment (mode 0/1/−1, fastAcos), the line-segment closest-approach
    //                      (lineSegmentIntersectTest) crossLinkGrabDist test, orientSame, loc1/loc2 (+v1 jitter),
    //                      and the P_form wang-hash draw. gatePass[c] = all passed.
    //   formAdmitReduce  : per-segment min gate-passing candidate index (admission — one new link/seg/step).
    //   countActiveLinks : start-of-step ACTIVE links per segment (saturation).
    //   formAdmit        : c admitted iff it is the min-candidate-index for BOTH its segments AND both pass
    //                      saturation (count<cap) AND spacing (≥minSep from every existing link), all vs
    //                      start-of-step state ⇒ exact with NO same-step cross-candidate dependency. → acceptFlag.
    //   [5c-i allocator]  : csrScan(rank) → allocate (unchanged); then placeOrient persists orientSame.
    //
    // The one-per-segment-per-step cap is a DELIBERATE non-binding divergence from v1 (which admits all that
    // pass, in scan order), justified only while same-step same-segment multi-formation is ~0 (self-checked).
    // RNG = reused wang-hash, salts XLFP/XLJ1/XLJ2 (distinct from break XLKB + the motor salts). localWork=64.

    /** v1 Pt3D.fastAcos — VERBATIM (sqrt small-angle approx for |dot|>0.95, Math.acos in the middle).
     *  The default maxXLinkBondAngle=π/12 lands the alignment threshold inside the dot>0.95 sqrt branch, so
     *  the gate decision is bit-exact there; in the middle (|dot|≤0.95) the angle always exceeds the default
     *  threshold ⇒ gate fails either way (decision bit-exact regardless of the acos value). */
    private static double fastAcos(double dot) {
        if (dot > 0.95) { double t = 1.0 - dot; if (t < 0) t = 0; return Math.sqrt(2.0 * t); }
        if (dot < -0.95) { double t = 1.0 + dot; if (t < 0) t = 0; return Math.PI - Math.sqrt(2.0 * t); }
        return accurateAcos(dot);   // PTX-safe acos (Math.acos does not lower on the PTX backend)
    }

    /** PTX-safe acos (same polynomial + Newton refinement as CrossBridgeSystem.accurateAcos). v1's
     *  fastAcos uses the |dot|>0.95 sqrt approx for the alignment threshold (the default maxXLinkBondAngle
     *  = π/12 lands there, so that decision is bit-exact); the middle branch only decides far-misaligned
     *  pairs (always > the default threshold ⇒ fail either way), so this stands in for Math.acos there. */
    private static double accurateAcos(double x) {
        if (x > 1.0) x = 1.0;
        if (x < -1.0) x = -1.0;
        double y;
        if (x > 0.95) { double t = 1.0 - x; if (t < 0.0) t = 0.0; y = Math.sqrt(2.0 * t); }
        else if (x < -0.95) { double t = 1.0 + x; if (t < 0.0) t = 0.0; y = 3.141592653589793 - Math.sqrt(2.0 * t); }
        else {
            double ax = (x < 0.0) ? -x : x;
            double p = (-0.0187293 * ax + 0.0742610) * ax - 0.2121144;
            p = (p * ax + 1.5707963); p = p * Math.sqrt(1.0 - ax);
            y = (x < 0.0) ? (3.141592653589793 - p) : p;
        }
        double s = Math.sin(y);
        if (s > 1.0e-12 || s < -1.0e-12) { y = y + (Math.cos(y) - x) / s; }
        s = Math.sin(y);
        if (s > 1.0e-12 || s < -1.0e-12) { y = y + (Math.cos(y) - x) / s; }
        return y;
    }

    /** Broad-phase FIL×FIL branch: emit distinct unordered cross-filament segment pairs (i<j,
     *  filID[i]≠filID[j]) whose capsule bounding spheres are within crossLinkGrabDist (a complete superset
     *  of the fine-gate passers, since closest-approach ≥ centerDist − ½lenI − ½lenJ). Single-threaded ⇒
     *  deterministic index order, bit-identical CPU↔GPU. Unused candidate slots keyed −1 (skipped downstream). */
    public static void filFilCandidates(FloatArray filCoord, FloatArray filSegLength, IntArray filID,
                                        IntArray reqFilA, IntArray reqFilB, FloatArray formParams, IntArray formCounts) {
        int nSeg = filCoord.getSize() / 3;
        int reqCap = reqFilA.getSize();
        double grab = formParams.get(6);
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int c = 0;
            for (int i = 0; i < nSeg; i++) {
                double cix = filCoord.get(i), ciy = filCoord.get(nSeg + i), ciz = filCoord.get(2 * nSeg + i);
                double hi = 0.5 * filSegLength.get(i);
                for (int j = i + 1; j < nSeg; j++) {
                    if (filID.get(i) == filID.get(j)) continue;                  // exclude same-filament (v1 filID!=filID)
                    double dx = cix - filCoord.get(j), dy = ciy - filCoord.get(nSeg + j), dz = ciz - filCoord.get(2 * nSeg + j);
                    double cd = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double bound = hi + 0.5 * filSegLength.get(j) + grab;
                    if (cd > bound) continue;                                    // coarse capsule-bound prune
                    if (c < reqCap) { reqFilA.set(c, i); reqFilB.set(c, j); c++; }
                }
            }
            formCounts.set(0, c);
            for (int k = c; k < reqCap; k++) { reqFilA.set(k, -1); reqFilB.set(k, -1); }
        }
    }

    /** Per-candidate-LOCAL gates + P_form (each candidate writes its own slot ⇒ race-free). Ports v1
     *  checkToLink: alignment (mode), lineSegmentIntersectTest closest-approach vs crossLinkGrabDist,
     *  orientSame, loc±jitter clamp, P_form = 1−exp(−kon·conc·dtCheck). */
    public static void formGates(FloatArray filUVec, FloatArray filEnd1, FloatArray filEnd2, FloatArray filSegLength,
                                 IntArray reqFilA, IntArray reqFilB, FloatArray reqLoc1, FloatArray reqLoc2,
                                 IntArray reqOrient, IntArray gatePass, FloatArray formParams, IntArray formCounts) {
        int nSeg = filUVec.getSize() / 3;
        int reqCap = reqFilA.getSize();
        int step = formCounts.get(1), seed = formCounts.get(2), mode = formCounts.get(3);
        double maxAngle = formParams.get(0), grabSq = formParams.get(1);
        double pForm = formParams.get(4), jit = formParams.get(5);

        for (@Parallel int c = 0; c < reqCap; c++) {
            gatePass.set(c, 0); reqOrient.set(c, 0);
            int a = reqFilA.get(c), b = reqFilB.get(c);
            if (a < 0 || b < 0) continue;                                  // unused candidate slot

            // alignment gate (v1 checkToLink mode switch)
            double uax = filUVec.get(a), uay = filUVec.get(nSeg + a), uaz = filUVec.get(2 * nSeg + a);
            double ubx = filUVec.get(b), uby = filUVec.get(nSeg + b), ubz = filUVec.get(2 * nSeg + b);
            double dot = uax * ubx + uay * uby + uaz * ubz;
            double angT = fastAcos(dot), angTR = fastAcos(-dot);
            boolean alignOk;
            if (mode == 1)       alignOk = angT  <= maxAngle;
            else if (mode == -1) alignOk = angTR <= maxAngle;
            else                 alignOk = (angT <= maxAngle) || (angTR <= maxAngle);   // mode 0 (both)
            if (!alignOk) continue;
            reqOrient.set(c, dot >= 0.0 ? 1 : 0);                          // v1 FilLink.set: orientSame = (angle≤π/2)

            // line-segment closest approach (v1 Thing.lineSegmentIntersectTest), end2 = end1 + len·uVec
            double e1ax = filEnd1.get(a), e1ay = filEnd1.get(nSeg + a), e1az = filEnd1.get(2 * nSeg + a);
            double e2ax = filEnd2.get(a), e2ay = filEnd2.get(nSeg + a), e2az = filEnd2.get(2 * nSeg + a);
            double e1bx = filEnd1.get(b), e1by = filEnd1.get(nSeg + b), e1bz = filEnd1.get(2 * nSeg + b);
            double e2bx = filEnd2.get(b), e2by = filEnd2.get(nSeg + b), e2bz = filEnd2.get(2 * nSeg + b);
            double r1x = e2ax - e1ax, r1y = e2ay - e1ay, r1z = e2az - e1az;   // ray1 = fil_a dir
            double r2x = e2bx - e1bx, r2y = e2by - e1by, r2z = e2bz - e1bz;   // ray2 = fil_b dir
            double r3x = e1bx - e1ax, r3y = e1by - e1ay, r3z = e1bz - e1az;   // ray3 = b.end1 − a.end1
            double r4x = r1y * r2z - r1z * r2y, r4y = r1z * r2x - r1x * r2z, r4z = r1x * r2y - r1y * r2x;  // ray4 = ray1×ray2
            double smallNum = 1e-20;
            if (r4x < smallNum && r4y < smallNum && r4z < smallNum) continue;  // v1 parallel test (verbatim)
            double denom = r4x * r4x + r4y * r4y + r4z * r4z;
            // alpha = ray4·(ray3×ray2)/denom
            double c32x = r3y * r2z - r3z * r2y, c32y = r3z * r2x - r3x * r2z, c32z = r3x * r2y - r3y * r2x;
            double alpha = (r4x * c32x + r4y * c32y + r4z * c32z) / denom;
            if (alpha < 0.0 || alpha > 1.0) continue;
            double c31x = r3y * r1z - r3z * r1y, c31y = r3z * r1x - r3x * r1z, c31z = r3x * r1y - r3y * r1x;
            double beta = (r4x * c31x + r4y * c31y + r4z * c31z) / denom;
            if (beta < 0.0 || beta > 1.0) continue;
            double p1x = e1ax + alpha * r1x, p1y = e1ay + alpha * r1y, p1z = e1az + alpha * r1z;
            double p2x = e1bx + beta * r2x,  p2y = e1by + beta * r2y,  p2z = e1bz + beta * r2z;
            double ddx = p1x - p2x, ddy = p1y - p2y, ddz = p1z - p2z;
            double conDistSq = ddx * ddx + ddy * ddy + ddz * ddz;
            if (conDistSq >= grabSq) continue;                              // crossLinkGrabDist distance gate

            // loc = ptDist(end1, conPt) + jitter, clamped to [0,length]  (v1 checkToLink; jitter via wang-hash)
            int base = (c * 1000003) ^ (step * 999983) ^ (seed * 7919);
            double da = Math.sqrt((p1x - e1ax) * (p1x - e1ax) + (p1y - e1ay) * (p1y - e1ay) + (p1z - e1az) * (p1z - e1az));
            double db = Math.sqrt((p2x - e1bx) * (p2x - e1bx) + (p2y - e1by) * (p2y - e1by) + (p2z - e1bz) * (p2z - e1bz));
            float uj1 = (wangHash(base ^ 0x584C4A31) >>> 1) / 2147483647.0f;   // XLJ1
            float uj2 = (wangHash(base ^ 0x584C4A32) >>> 1) / 2147483647.0f;   // XLJ2
            double loc1 = da + (2.0 * uj1 - 1.0) * jit;
            double loc2 = db + (2.0 * uj2 - 1.0) * jit;
            double lenA = filSegLength.get(a), lenB = filSegLength.get(b);
            if (loc1 > lenA) loc1 = lenA; if (loc1 < 0.0) loc1 = 0.0;
            if (loc2 > lenB) loc2 = lenB; if (loc2 < 0.0) loc2 = 0.0;
            reqLoc1.set(c, (float) loc1); reqLoc2.set(c, (float) loc2);

            // P_form (v1: form iff rng.nextDouble() < pForm)
            float up = (wangHash(base ^ 0x584C4650) >>> 1) / 2147483647.0f;    // XLFP
            if (up < pForm) gatePass.set(c, 1);
        }
    }

    /** Admission reduce: per segment, the MIN gate-passing candidate index targeting it (either side).
     *  Single-threaded ⇒ deterministic per-segment min-reduction (the CSR-by-segment slice min, without
     *  materializing the CSR). Sentinel = reqCap (> any candidate index). */
    public static void formAdmitReduce(IntArray reqFilA, IntArray reqFilB, IntArray gatePass, IntArray minCand, IntArray formCounts) {
        int reqCap = reqFilA.getSize(), nSeg = minCand.getSize();
        for (@Parallel int gid = 0; gid < 1; gid++) {
            for (int s = 0; s < nSeg; s++) minCand.set(s, reqCap);
            for (int c = 0; c < reqCap; c++) {
                if (gatePass.get(c) == 0) continue;
                int a = reqFilA.get(c), b = reqFilB.get(c);
                if (c < minCand.get(a)) minCand.set(a, c);
                if (c < minCand.get(b)) minCand.set(b, c);
            }
        }
    }

    /** Start-of-step ACTIVE links per segment (saturation count). Single-threaded. */
    public static void countActiveLinks(IntArray linkState, IntArray linkFilA, IntArray linkFilB,
                                        IntArray activeLinkCount, IntArray formCounts) {
        int C = linkState.getSize(), nSeg = activeLinkCount.getSize();
        for (@Parallel int gid = 0; gid < 1; gid++) {
            for (int s = 0; s < nSeg; s++) activeLinkCount.set(s, 0);
            for (int L = 0; L < C; L++) {
                if (linkState.get(L) < 0) continue;
                int a = linkFilA.get(L), b = linkFilB.get(L);
                activeLinkCount.set(a, activeLinkCount.get(a) + 1);
                activeLinkCount.set(b, activeLinkCount.get(b) + 1);
            }
        }
    }

    /** Admission: candidate c is admitted (acceptFlag=1) iff it passed the local gates AND it is the
     *  min-candidate-index for BOTH its segments (one new link per segment per step) AND both segments
     *  pass saturation (count<cap) AND spacing (loc ≥ minSep from every existing ACTIVE link on that
     *  segment) — all checked against start-of-step state. Per-candidate parallel; reads-only the shared
     *  state ⇒ race-free. */
    public static void formAdmit(IntArray reqFilA, IntArray reqFilB, FloatArray reqLoc1, FloatArray reqLoc2,
                                 IntArray gatePass, IntArray minCand, IntArray activeLinkCount,
                                 IntArray linkState, IntArray linkFilA, IntArray linkFilB, FloatArray loc1, FloatArray loc2,
                                 IntArray acceptFlag, FloatArray formParams, IntArray formCounts) {
        int reqCap = reqFilA.getSize(), C = linkState.getSize();
        double minSep = formParams.get(2);
        int maxLinks = (int) formParams.get(3);
        for (@Parallel int c = 0; c < reqCap; c++) {
            acceptFlag.set(c, 0);
            if (gatePass.get(c) == 0) continue;
            int segA = reqFilA.get(c), segB = reqFilB.get(c);
            if (minCand.get(segA) != c || minCand.get(segB) != c) continue;                 // one-per-segment cap
            if (activeLinkCount.get(segA) >= maxLinks || activeLinkCount.get(segB) >= maxLinks) continue;  // saturation
            double la = reqLoc1.get(c), lb = reqLoc2.get(c);
            boolean ok = true;
            for (int L = 0; L < C; L++) {                                                   // spacing vs existing links
                if (linkState.get(L) < 0) continue;
                int fa = linkFilA.get(L), fb = linkFilB.get(L);
                if (fa == segA && Math.abs(la - loc1.get(L)) < minSep) ok = false;
                if (fb == segA && Math.abs(la - loc2.get(L)) < minSep) ok = false;
                if (fa == segB && Math.abs(lb - loc1.get(L)) < minSep) ok = false;
                if (fb == segB && Math.abs(lb - loc2.get(L)) < minSep) ok = false;
            }
            if (ok) acceptFlag.set(c, 1);
        }
    }

    /** Persist orientSame into the formed link's slot, using the SAME rank→freeList mapping as `allocate`
     *  (keeps the 5c-i allocate unchanged / ≤15 args). One writer per slot ⇒ race-free. */
    public static void placeOrient(IntArray reqOrient, IntArray rankOffsets, IntArray freeList, IntArray freeOffsets,
                                   IntArray linkOrientSame, IntArray allocCounts) {
        int C = allocCounts.get(0), K = allocCounts.get(1);
        int nFree = freeOffsets.get(C);
        for (@Parallel int r = 0; r < K; r++) {
            int rank = rankOffsets.get(r);
            if (rankOffsets.get(r + 1) <= rank) continue;
            if (rank >= nFree) continue;
            linkOrientSame.set(freeList.get(rank), reqOrient.get(r));
        }
    }
}
