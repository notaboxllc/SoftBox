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
                                  FloatArray filForceSum, FloatArray filTorqueSum, IntArray counts) {
        int nSeg = counts.get(3);
        for (@Parallel int s = 0; s < nSeg; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            int start = segOffsets.get(s), end = segOffsets.get(s + 1);
            for (int k = start; k < end; k++) {
                int d = segLinkIdx.get(k) * CrosslinkerStore.STRIDE;
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
                                  FloatArray filForceSum, FloatArray filTorqueSum, IntArray counts) {
        int nSeg = counts.get(3);
        for (@Parallel int s = 0; s < nSeg; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            int start = segOffsets.get(s), end = segOffsets.get(s + 1);
            for (int k = start; k < end; k++) {
                int d = segLinkIdx.get(k) * CrosslinkerStore.STRIDE;
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
                                   FloatArray bForceSum, FloatArray bTorqueSum, IntArray counts) {
        int nSeg = counts.get(3), nLinks = counts.get(0);
        for (@Parallel int s = 0; s < nSeg; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            for (int k = 0; k < nLinks; k++) {
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
}
