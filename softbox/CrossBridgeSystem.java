package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 4b-ii/4b-iii: the myosin cross-bridge + the CROSS-ENTITY force+torque gather (motor→segment).
 *
 * Cross-bridge (faithful port of v1 MyoFilLink): for each bound motor, between its head tip and the
 * bound site on the segment —
 *   F8  cross-bridge spring   F = myoSpring·dist toward the bound site (addForces:187), at the head tip
 *       / bound site (each end gets the positional torque R×F, R in metres).
 *   F9  uVec alignment torque toward the motor–actin rest angle — STATE-DEPENDENT (4b-iii): uncocked
 *       (ADPPi) 90°, cocked 120° (alignUVecTorque:239-240). The power stroke emerges from this switch.
 *   F10 yVec alignment torque toward 0° (alignYVecTorque).
 * The head gets +F / −torsion; the segment gets −F / +torsion. forceDotFil = Dot(F, seg.uVec) (the
 * along-filament load; feeds the catch-slip + the ADP→NONE gate).
 *
 * `bondForces` computes the bond ONCE and stores head-side (6) + seg-side (6) + forceDotFil (1) in
 * bondData[m*13..]. `applyHeadForce` does the head self-write (one bond per head, race-free);
 * `registerForceDot` tracks the load; `segGather` sums the seg-side over the CSR-inverse (the
 * cross-entity gather — race-free, no atomics; see below).
 *
 * THE CROSS-ENTITY GATHER. Race-free WITHOUT atomics by a SEGMENT-SIDE gather over a
 * segment→bound-motors CSR-inverse (inc-3 histogram/scan/scatter keyed by boundSeg). The scatter
 * visits motors in index order ⇒ the gather sums in the same order as the brute reference ⇒
 * bit-identical. General infrastructure — crosslinkers / nodes / membrane reuse it.
 *
 * bondData stride 13: [0..2]=head force [3..5]=head torque [6..8]=seg force [9..11]=seg torque
 *   [12]=forceDotFil. xbParams: [0]=myoSpring [1]=(unused, F9 rest is state-dependent) [2]=j1FracMoveTorq
 *   [3]=dt [4]=HEAD_LEN.
 */
public final class CrossBridgeSystem {
    private CrossBridgeSystem() {}
    public static final int STRIDE = 13;

    private static double accurateAcos(double x) {
        if (x > 1.0)  x = 1.0;
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

    /** Compute each bound motor's cross-bridge bond ONCE; store head-side + seg-side + forceDotFil. */
    public static void bondForces(
            FloatArray motorCoord, FloatArray motorUVec, FloatArray motorYVec, FloatArray motorBRotGam,
            FloatArray filCoord, FloatArray filUVec, FloatArray filYVec, FloatArray filBRotGam, FloatArray filSegLength,
            IntArray boundSeg, FloatArray bindArc, IntArray nucleotideState,
            FloatArray bondData, FloatArray xbParams) {

        int nB = motorCoord.getSize() / 3;
        int nSeg = filCoord.getSize() / 3;
        double myoSpring = xbParams.get(0), j1FMT = xbParams.get(2);
        double dt = xbParams.get(3), headLen = xbParams.get(4);
        double DEG2RAD = Math.PI / 180.0, RAD2DEG = 180.0 / Math.PI;
        int nM = nB / 3;

        for (@Parallel int m = 0; m < nM; m++) {
            int d = m * STRIDE;
            for (int k = 0; k < STRIDE; k++) bondData.set(d + k, 0f);
            int s = boundSeg.get(m);
            if (s < 0) continue;

            int h = 3 * m + 2;
            double hcx = motorCoord.get(h), hcy = motorCoord.get(nB + h), hcz = motorCoord.get(2 * nB + h);
            double hux = motorUVec.get(h), huy = motorUVec.get(nB + h), huz = motorUVec.get(2 * nB + h);
            double hyx = motorYVec.get(h), hyy = motorYVec.get(nB + h), hyz = motorYVec.get(2 * nB + h);
            double hbRGx = motorBRotGam.get(h), hbRGy = motorBRotGam.get(nB + h);
            double htipx = hcx + 0.5 * headLen * hux, htipy = hcy + 0.5 * headLen * huy, htipz = hcz + 0.5 * headLen * huz;

            double scx = filCoord.get(s), scy = filCoord.get(nSeg + s), scz = filCoord.get(2 * nSeg + s);
            double sux = filUVec.get(s), suy = filUVec.get(nSeg + s), suz = filUVec.get(2 * nSeg + s);
            double syx = filYVec.get(s), syy = filYVec.get(nSeg + s), syz = filYVec.get(2 * nSeg + s);
            double sbRGx = filBRotGam.get(s), sbRGy = filBRotGam.get(nSeg + s);
            double slen = filSegLength.get(s);
            double aOff = bindArc.get(m) - 0.5 * slen;
            double apx = scx + aOff * sux, apy = scy + aOff * suy, apz = scz + aOff * suz;

            // F8 spring (toward the bound site)
            double dx = apx - htipx, dy = apy - htipy, dz = apz - htipz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double fmag = myoSpring * dist;
            double Fx = 0, Fy = 0, Fz = 0;
            if (dist > 0.0) { double inv = fmag / dist; Fx = inv * dx; Fy = inv * dy; Fz = inv * dz; }
            double RHx = (htipx - hcx) * 1e-6, RHy = (htipy - hcy) * 1e-6, RHz = (htipz - hcz) * 1e-6;
            double THx = RHy * Fz - RHz * Fy, THy = RHz * Fx - RHx * Fz, THz = RHx * Fy - RHy * Fx;
            double RSx = (apx - scx) * 1e-6, RSy = (apy - scy) * 1e-6, RSz = (apz - scz) * 1e-6;
            double nFx = -Fx, nFy = -Fy, nFz = -Fz;
            double TSx = RSy * nFz - RSz * nFy, TSy = RSz * nFx - RSx * nFz, TSz = RSx * nFy - RSy * nFx;

            // F9 uVec alignment torque — STATE-DEPENDENT rest angle (the stroke switch)
            double restF9 = (nucleotideState.get(m) != MotorStore.NUC_ADPPI) ? 120.0 : 90.0;
            double t9x = suy * huz - suz * huy, t9y = suz * hux - sux * huz, t9z = sux * huy - suy * hux;
            double m9 = t9x * t9x + t9y * t9y + t9z * t9z;
            double T9x = 0, T9y = 0, T9z = 0;
            if (m9 > 1.0e-30) {
                double im = 1.0 / Math.sqrt(m9); t9x *= im; t9y *= im; t9z *= im;
                double dot = sux * hux + suy * huy + suz * huz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
                double angD = accurateAcos(dot) * RAD2DEG - restF9;
                double tm = j1FMT * DEG2RAD * angD / ((1.0 / hbRGy + 1.0 / sbRGy) * dt);
                T9x = tm * t9x; T9y = tm * t9y; T9z = tm * t9z;
            }
            // F10 yVec alignment torque (rest 0)
            double t10x = syy * hyz - syz * hyy, t10y = syz * hyx - syx * hyz, t10z = syx * hyy - syy * hyx;
            double m10 = t10x * t10x + t10y * t10y + t10z * t10z;
            double T10x = 0, T10y = 0, T10z = 0;
            if (m10 > 1.0e-30) {
                double im = 1.0 / Math.sqrt(m10); t10x *= im; t10y *= im; t10z *= im;
                double dot = syx * hyx + syy * hyy + syz * hyz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
                double ang = accurateAcos(dot) * RAD2DEG;
                double tm = j1FMT * DEG2RAD * ang / ((1.0 / hbRGx + 1.0 / sbRGx) * dt);
                T10x = tm * t10x; T10y = tm * t10y; T10z = tm * t10z;
            }

            // head-side: +F, torque TH - T9 - T10
            bondData.set(d,     (float) Fx);  bondData.set(d + 1, (float) Fy);  bondData.set(d + 2, (float) Fz);
            bondData.set(d + 3, (float) (THx - T9x - T10x));
            bondData.set(d + 4, (float) (THy - T9y - T10y));
            bondData.set(d + 5, (float) (THz - T9z - T10z));
            // seg-side: -F, torque TS + T9 + T10
            bondData.set(d + 6, (float) nFx); bondData.set(d + 7, (float) nFy); bondData.set(d + 8, (float) nFz);
            bondData.set(d + 9,  (float) (TSx + T9x + T10x));
            bondData.set(d + 10, (float) (TSy + T9y + T10y));
            bondData.set(d + 11, (float) (TSz + T9z + T10z));
            // forceDotFil = Dot(F, seg.uVec) — the along-filament load (motor-side force)
            bondData.set(d + 12, (float) (Fx * sux + Fy * suy + Fz * suz));
        }
    }

    /** Head self-write: apply the head-side force+torque to the head sub-body (3m+2), += (race-free). */
    public static void applyHeadForce(FloatArray bondData, FloatArray bodyForceSum, FloatArray bodyTorqueSum, IntArray counts) {
        int nB = bodyForceSum.getSize() / 3;
        int nM = nB / 3;
        for (@Parallel int m = 0; m < nM; m++) {
            int h = 3 * m + 2, d = m * STRIDE;
            bodyForceSum.set(h,          (float) (bodyForceSum.get(h)          + bondData.get(d)));
            bodyForceSum.set(nB + h,     (float) (bodyForceSum.get(nB + h)     + bondData.get(d + 1)));
            bodyForceSum.set(2 * nB + h, (float) (bodyForceSum.get(2 * nB + h) + bondData.get(d + 2)));
            bodyTorqueSum.set(h,          (float) (bodyTorqueSum.get(h)          + bondData.get(d + 3)));
            bodyTorqueSum.set(nB + h,     (float) (bodyTorqueSum.get(nB + h)     + bondData.get(d + 4)));
            bodyTorqueSum.set(2 * nB + h, (float) (bodyTorqueSum.get(2 * nB + h) + bondData.get(d + 5)));
        }
    }

    /** Track forceDotFil: instantaneous (catch-slip) + a 10-window ring (the ADP→NONE gate average,
     *  v1 ValueTracker(10)). Free motors reset the tracker (v1 release().zero()). */
    public static void registerForceDot(FloatArray bondData, IntArray boundSeg,
                                        FloatArray forceDotFil, FloatArray forceDotHist, IntArray forceDotPlace, IntArray counts) {
        int nM = boundSeg.getSize();
        for (@Parallel int m = 0; m < nM; m++) {
            if (boundSeg.get(m) >= 0) {
                float fd = bondData.get(m * STRIDE + 12);
                forceDotFil.set(m, fd);
                int p = forceDotPlace.get(m);
                forceDotHist.set(m * 10 + p, fd);
                forceDotPlace.set(m, (p + 1) % 10);
            } else {
                forceDotFil.set(m, 0f);
                forceDotPlace.set(m, 0);
                int b = m * 10;
                for (int k = 0; k < 10; k++) forceDotHist.set(b + k, 0f);
            }
        }
    }

    // ===================== segment→bound-motors CSR-inverse (inc-3 pattern, no atomics) =============
    public static void csrHistogram(IntArray boundSeg, IntArray counts, IntArray segMotorCount) {
        int nSeg = counts.get(3);
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int nM = counts.get(0);
            for (int s = 0; s < nSeg; s++) segMotorCount.set(s, 0);
            for (int m = 0; m < nM; m++) { int s = boundSeg.get(m); if (s >= 0) segMotorCount.set(s, segMotorCount.get(s) + 1); }
        }
    }
    public static void csrScan(IntArray counts, IntArray segMotorCount, IntArray segMotorOffsets) {
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int nSeg = counts.get(3);
            int acc = 0;
            for (int s = 0; s < nSeg; s++) { segMotorOffsets.set(s, acc); acc += segMotorCount.get(s); segMotorCount.set(s, 0); }
            segMotorOffsets.set(nSeg, acc);
        }
    }
    public static void csrScatter(IntArray boundSeg, IntArray counts, IntArray segMotorOffsets,
                                  IntArray segMotorCount, IntArray segMotorMyo) {
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int nM = counts.get(0);
            for (int m = 0; m < nM; m++) {
                int s = boundSeg.get(m); if (s < 0) continue;
                int pos = segMotorOffsets.get(s) + segMotorCount.get(s);
                segMotorMyo.set(pos, m);
                segMotorCount.set(s, segMotorCount.get(s) + 1);
            }
        }
    }

    /** Segment-side GATHER: each segment sums its bound motors' seg-side reactions into its own
     *  forceSum/torqueSum (+=). Race-free (segment writes self), no atomics. */
    public static void segGather(IntArray segMotorOffsets, IntArray segMotorMyo, FloatArray bondData,
                                 FloatArray filForceSum, FloatArray filTorqueSum, IntArray counts) {
        int nSeg = counts.get(3);
        for (@Parallel int s = 0; s < nSeg; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            int start = segMotorOffsets.get(s), end = segMotorOffsets.get(s + 1);
            for (int k = start; k < end; k++) {
                int d = segMotorMyo.get(k) * STRIDE;
                fx += bondData.get(d + 6); fy += bondData.get(d + 7); fz += bondData.get(d + 8);
                tx += bondData.get(d + 9); ty += bondData.get(d + 10); tz += bondData.get(d + 11);
            }
            filForceSum.set(s,           (float) (filForceSum.get(s)            + fx));
            filForceSum.set(nSeg + s,    (float) (filForceSum.get(nSeg + s)     + fy));
            filForceSum.set(2 * nSeg + s,(float) (filForceSum.get(2 * nSeg + s) + fz));
            filTorqueSum.set(s,           (float) (filTorqueSum.get(s)            + tx));
            filTorqueSum.set(nSeg + s,    (float) (filTorqueSum.get(nSeg + s)     + ty));
            filTorqueSum.set(2 * nSeg + s,(float) (filTorqueSum.get(2 * nSeg + s) + tz));
        }
    }

    /** O(nMotors·nSeg) brute-force reference: each segment sums over ALL motors with boundSeg==s. */
    public static void bruteGather(IntArray boundSeg, FloatArray bondData,
                                   FloatArray bForceSum, FloatArray bTorqueSum, IntArray counts) {
        int nSeg = counts.get(3), nM = counts.get(0);
        for (@Parallel int s = 0; s < nSeg; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            for (int m = 0; m < nM; m++) {
                if (boundSeg.get(m) != s) continue;
                int d = m * STRIDE;
                fx += bondData.get(d + 6); fy += bondData.get(d + 7); fz += bondData.get(d + 8);
                tx += bondData.get(d + 9); ty += bondData.get(d + 10); tz += bondData.get(d + 11);
            }
            bForceSum.set(s, (float) fx); bForceSum.set(nSeg + s, (float) fy); bForceSum.set(2 * nSeg + s, (float) fz);
            bTorqueSum.set(s, (float) tx); bTorqueSum.set(nSeg + s, (float) ty); bTorqueSum.set(2 * nSeg + s, (float) tz);
        }
    }
}
