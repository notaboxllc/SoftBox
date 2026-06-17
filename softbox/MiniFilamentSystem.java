package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Minifilament-SPECIFIC system (increment 6b): the backbone↔dimer tether + the SINGLE-ENDED one-pass
 * gather. Faithful port of v1 MyoMiniFilament.constrainEnd1Dimers / constrainEnd2Dimers (:436-528):
 * each dimer's myo1.myoRod.end1 is tethered to a body-local (axial) backbone attachment point by a
 * damping-limited PAIRS spring + an alignment torque toward the ±backbone axis (rest angle 0).
 *
 * COUPLING (recon §2 — the central favorable finding): SINGLE-ENDED, one pass. The backbone OWNS its
 * dimers (one consumer, many writers, each dimer keyed to exactly one backbone via headBackboneSlot)
 * — the SAME shape as motor→segment (boundSeg), NOT the crosslinker double-ended two-pass. So the
 * gather reuses the CrossBridge CSR-inverse (csrHistogram/csrScan/csrScatter) run ONCE, keyed by
 * headBackboneSlot; each backbone sums its dimers' stored backbone-side reactions (segGather pattern).
 *   1. tether (here): each dimer self-writes the rod-side force+torque into its own rod slot and stores
 *      the backbone-side reaction in miniData[6*d..] (one writer/rod ⇒ race-free, no atomics).
 *   2. CrossBridge.csrHistogram/csrScan/csrScatter keyed by headBackboneSlot (REUSED VERBATIM).
 *   3. backboneGather (here): each backbone sums its dimers' miniData reactions into its forceSum/torqueSum.
 *
 * Gated bit-identical vs bruteGather (a brute per-dimer sum), CPU≡GPU. No new gather machinery.
 *
 * Tether force law (v1): F = miniFilFracMove·1e-6·strain / (dt·(1/rod.bTransGam.y + 1/bb.bTransGam.y))
 *   — plain perpendicular drag (NO moveCoeff projection, unlike the dimer rod-coupling). Torsion:
 *   miniFilAlign·(π/180)·ang / ((1/rod.bRotGam.y + 1/bb.bRotGam.y)·dt), restoring rod.uVec → ±bb axis.
 *
 * TornadoVM: the tether math is inlined in the top-level @Parallel kernel (the 6a 600-node inline-cap
 * pattern); only accurateAcos is an inlined helper.
 */
public final class MiniFilamentSystem {
    private MiniFilamentSystem() {}

    // Device-safe acos, VERBATIM DimerCouplingSystem.accurateAcos (v1 GPUMoveThing).
    private static double accurateAcos(double x) {
        if (x > 1.0)  x = 1.0;
        if (x < -1.0) x = -1.0;
        double y;
        if (x > 0.95) {
            double t = 1.0 - x; if (t < 0.0) t = 0.0; y = Math.sqrt(2.0 * t);
        } else if (x < -0.95) {
            double t = 1.0 + x; if (t < 0.0) t = 0.0; y = 3.141592653589793 - Math.sqrt(2.0 * t);
        } else {
            double ax = (x < 0.0) ? -x : x;
            double p = (-0.0187293 * ax + 0.0742610) * ax - 0.2121144;
            p = (p * ax + 1.5707963);
            p = p * Math.sqrt(1.0 - ax);
            y = (x < 0.0) ? (3.141592653589793 - p) : p;
        }
        double s = Math.sin(y);
        if (s > 1.0e-12 || s < -1.0e-12) { y = y + (Math.cos(y) - x) / s; }
        s = Math.sin(y);
        if (s > 1.0e-12 || s < -1.0e-12) { y = y + (Math.cos(y) - x) / s; }
        return y;
    }

    /**
     * Per-dimer tether: rodA (= 3·motorA[d]) end1 ↔ the backbone's axial attach point. Self-writes the
     * rod-side force+torque into the motor body; stores the backbone-side reaction into miniData[6*d..]
     * for the gather. One thread per dimer; rod slots are uniquely owned ⇒ race-free, no atomics.
     *  args (15): motor body coord/uVec/segLength/bTransGam/bRotGam/forceSum/torqueSum (7),
     *            backbone coord/uVec/invDragY (3), headBackboneSlot/motorA/attachAxial/miniData/miniParams (5).
     */
    public static void tether(
            FloatArray mCoord, FloatArray mUVec, FloatArray mSegLength,
            FloatArray mBTransGam, FloatArray mBRotGam, FloatArray mForceSum, FloatArray mTorqueSum,
            FloatArray bbCoord, FloatArray bbUVec, FloatArray bbInvDragY,
            IntArray headBackboneSlot, IntArray motorA, FloatArray attachAxial,
            FloatArray miniData, FloatArray miniParams) {

        int nMB = mCoord.getSize() / 3;     // motor body sub-bodies (= 3·nMotors)
        int nBb = bbCoord.getSize() / 3;    // backbones
        int nD  = headBackboneSlot.getSize();
        double dt = miniParams.get(0), fracMove = miniParams.get(1), align = miniParams.get(2);

        for (@Parallel int d = 0; d < nD; d++) {
            int bb = headBackboneSlot.get(d);
            int rodA = 3 * motorA.get(d);
            double ax = attachAxial.get(d);

            // backbone pose + attach point (axial offset along the backbone uVec)
            double bcx = bbCoord.get(bb), bcy = bbCoord.get(nBb + bb), bcz = bbCoord.get(2 * nBb + bb);
            double bux = bbUVec.get(bb),  buy = bbUVec.get(nBb + bb),  buz = bbUVec.get(2 * nBb + bb);
            double pax = bcx + ax * bux, pay = bcy + ax * buy, paz = bcz + ax * buz;

            // rod pose + end1 (= coord − ½·len·uVec)
            double rcx = mCoord.get(rodA), rcy = mCoord.get(nMB + rodA), rcz = mCoord.get(2 * nMB + rodA);
            double rux = mUVec.get(rodA),  ruy = mUVec.get(nMB + rodA),  ruz = mUVec.get(2 * nMB + rodA);
            double rlen = mSegLength.get(rodA);
            double re1x = rcx - 0.5 * rlen * rux, re1y = rcy - 0.5 * rlen * ruy, re1z = rcz - 0.5 * rlen * ruz;

            double fbx = 0, fby = 0, fbz = 0, tbx = 0, tby = 0, tbz = 0;   // backbone-side reaction

            // ---- tether spring: F_rod = forceMag·l (toward attach), F_bb = −F_rod ----
            double dx = pax - re1x, dy = pay - re1y, dz = paz - re1z;      // v1 linkUVec1 = unit(attach − rodEnd1)
            double strain = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (strain > 0.0) {
                double inv = 1.0 / strain;
                double lx = dx * inv, ly = dy * inv, lz = dz * inv;
                double denom = dt * (1.0 / mBTransGam.get(nMB + rodA) + bbInvDragY.get(bb));   // perpendicular drags
                double forceMag = (denom > 0.0) ? (fracMove * 1.0e-6 * strain / denom) : 0.0;
                double Frx = forceMag * lx, Fry = forceMag * ly, Frz = forceMag * lz;
                mForceSum.set(rodA,           (float) (mForceSum.get(rodA)           + Frx));
                mForceSum.set(nMB + rodA,     (float) (mForceSum.get(nMB + rodA)     + Fry));
                mForceSum.set(2 * nMB + rodA, (float) (mForceSum.get(2 * nMB + rodA) + Frz));
                fbx = -Frx; fby = -Fry; fbz = -Frz;
            }

            // ---- alignment torque: rod.uVec → ±backbone axis (− at end1, + at end2), rest angle 0 ----
            double alignSign = (ax < 0.0) ? -1.0 : 1.0;
            double tux = alignSign * bux, tuy = alignSign * buy, tuz = alignSign * buz;
            double tvx = ruy * tuz - ruz * tuy, tvy = ruz * tux - rux * tuz, tvz = rux * tuy - ruy * tux;  // cross(rod.u, target)
            double tvm2 = tvx * tvx + tvy * tvy + tvz * tvz;
            if (tvm2 > 1.0e-30) {
                double im = 1.0 / Math.sqrt(tvm2); tvx *= im; tvy *= im; tvz *= im;
                double dot = rux * tux + ruy * tuy + ruz * tuz;
                if (dot > 1.0) dot = 1.0; if (dot < -1.0) dot = -1.0;
                double angDeg = accurateAcos(dot) * 180.0 / Math.PI;
                double denomR = (1.0 / mBRotGam.get(nMB + rodA) + bbInvDragY.get(nBb + bb)) * dt;
                double tmag = align * (Math.PI / 180.0) * angDeg / denomR;
                mTorqueSum.set(rodA,           (float) (mTorqueSum.get(rodA)           + tvx * tmag));
                mTorqueSum.set(nMB + rodA,     (float) (mTorqueSum.get(nMB + rodA)     + tvy * tmag));
                mTorqueSum.set(2 * nMB + rodA, (float) (mTorqueSum.get(2 * nMB + rodA) + tvz * tmag));
                tbx = -tvx * tmag; tby = -tvy * tmag; tbz = -tvz * tmag;
            }

            int o = MiniFilamentStore.MINI_STRIDE * d;
            miniData.set(o,     (float) fbx); miniData.set(o + 1, (float) fby); miniData.set(o + 2, (float) fbz);
            miniData.set(o + 3, (float) tbx); miniData.set(o + 4, (float) tby); miniData.set(o + 5, (float) tbz);
        }
    }

    /** Backbone-side GATHER (single-ended, one pass): each backbone sums its dimers' stored backbone-side
     *  reactions into its own forceSum/torqueSum. CSR built by the REUSED CrossBridge.csr* keyed by
     *  headBackboneSlot. Race-free (backbone writes self), no atomics — the segGather pattern. */
    public static void backboneGather(IntArray bbDimerOffsets, IntArray bbDimerList, FloatArray miniData,
                                      FloatArray bbForceSum, FloatArray bbTorqueSum, IntArray miniCounts) {
        int nBb = miniCounts.get(3);
        for (@Parallel int s = 0; s < nBb; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            int start = bbDimerOffsets.get(s), end = bbDimerOffsets.get(s + 1);
            for (int k = start; k < end; k++) {
                int o = bbDimerList.get(k) * MiniFilamentStore.MINI_STRIDE;
                fx += miniData.get(o);     fy += miniData.get(o + 1); fz += miniData.get(o + 2);
                tx += miniData.get(o + 3); ty += miniData.get(o + 4); tz += miniData.get(o + 5);
            }
            bbForceSum.set(s,             (float) (bbForceSum.get(s)             + fx));
            bbForceSum.set(nBb + s,       (float) (bbForceSum.get(nBb + s)       + fy));
            bbForceSum.set(2 * nBb + s,   (float) (bbForceSum.get(2 * nBb + s)   + fz));
            bbTorqueSum.set(s,            (float) (bbTorqueSum.get(s)            + tx));
            bbTorqueSum.set(nBb + s,      (float) (bbTorqueSum.get(nBb + s)      + ty));
            bbTorqueSum.set(2 * nBb + s,  (float) (bbTorqueSum.get(2 * nBb + s)  + tz));
        }
    }

    /** O(nDimers·nBackbones) brute-force reference: each backbone sums over ALL dimers keyed to it. */
    public static void bruteGather(IntArray headBackboneSlot, FloatArray miniData,
                                   FloatArray bbForceSum, FloatArray bbTorqueSum, IntArray miniCounts) {
        int nBb = miniCounts.get(3), nD = miniCounts.get(0);
        for (@Parallel int s = 0; s < nBb; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            for (int d = 0; d < nD; d++) {
                if (headBackboneSlot.get(d) != s) continue;
                int o = d * MiniFilamentStore.MINI_STRIDE;
                fx += miniData.get(o);     fy += miniData.get(o + 1); fz += miniData.get(o + 2);
                tx += miniData.get(o + 3); ty += miniData.get(o + 4); tz += miniData.get(o + 5);
            }
            bbForceSum.set(s, (float) fx); bbForceSum.set(nBb + s, (float) fy); bbForceSum.set(2 * nBb + s, (float) fz);
            bbTorqueSum.set(s, (float) tx); bbTorqueSum.set(nBb + s, (float) ty); bbTorqueSum.set(2 * nBb + s, (float) tz);
        }
    }
}
