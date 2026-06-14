package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 4b-ii: the myosin cross-bridge + the CROSS-ENTITY force+torque gather (motor→segment).
 *
 * Cross-bridge (faithful port of v1 MyoFilLink, FIXED uncocked rest angle — no stroke this
 * increment): for each bound motor, between its head tip and the bound site on the segment —
 *   F8  cross-bridge spring   F = myoSpring·dist toward the bound site (addForces:187), applied at
 *       the head tip / the bound site (each end gets the positional torque R×F, R in metres).
 *   F9  uVec alignment torque toward the motor–actin rest angle (90° uncocked; alignUVecTorque).
 *   F10 yVec alignment torque toward 0° (alignYVecTorque).
 * Equal-and-opposite: the head gets +F / −torsion (self-write — one bond per head, race-free); the
 * segment gets −F / +torsion (cross-entity → the gather). The bond is computed ONCE in `bondForces`;
 * the head-side is applied there and the seg-side REACTION is stored in `bondSeg6[m*6..]` for the gather.
 *
 * THE CROSS-ENTITY GATHER (the centerpiece, reusable infrastructure). Motors write force to segments
 * in a DIFFERENT store. Race-free WITHOUT atomics (the dual-runner constraint) by a SEGMENT-SIDE
 * gather over a segment→bound-motors CSR-inverse index (v1 segMotorOffsets/segMotorMyo; the inc-3 CSR
 * pattern keyed by boundSeg instead of grid cell): histogram → prefix-scan → scatter (single-thread,
 * race-free, no atomics — exactly inc-3's gridHistogram/gridScatter), then each segment (one thread)
 * sums its bound motors' stored reactions into its OWN forceSum/torqueSum. The scatter visits motors in
 * index order, so the per-segment motor list is sorted by m and the gather sums in the same order as
 * the brute reference ⇒ BIT-identical (not merely modulo-ordering).
 *
 * xbParams: [0]=myoSpring (N/µm) [1]=restAngleDeg [2]=j1FracMoveTorq [3]=dt [4]=HEAD_LEN(µm).
 * counts (cross-bridge): [0]=nMotors [1]=stepCount [2]=runSeed [3]=nSeg.
 */
public final class CrossBridgeSystem {
    private CrossBridgeSystem() {}

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

    /**
     * Per bound motor: compute the cross-bridge bond, APPLY the head-side force+torque to the head
     * sub-body (3m+2; self-write +=), and STORE the seg-side reaction in bondSeg6[m*6..] for the gather.
     * Free motors store zero. One thread per motor.
     */
    public static void bondForces(
            FloatArray motorCoord, FloatArray motorUVec, FloatArray motorYVec, FloatArray motorBRotGam,
            FloatArray filCoord, FloatArray filUVec, FloatArray filYVec, FloatArray filBRotGam, FloatArray filSegLength,
            IntArray boundSeg, FloatArray bindArc,
            FloatArray bodyForceSum, FloatArray bodyTorqueSum,
            FloatArray bondSeg6, FloatArray xbParams) {

        int nB = motorCoord.getSize() / 3;
        int nSeg = filCoord.getSize() / 3;
        double myoSpring = xbParams.get(0), restDeg = xbParams.get(1), j1FMT = xbParams.get(2);
        double dt = xbParams.get(3), headLen = xbParams.get(4);
        double DEG2RAD = Math.PI / 180.0, RAD2DEG = 180.0 / Math.PI;
        int nM = nB / 3;

        for (@Parallel int m = 0; m < nM; m++) {
            int b6 = m * 6;
            bondSeg6.set(b6, 0f); bondSeg6.set(b6 + 1, 0f); bondSeg6.set(b6 + 2, 0f);
            bondSeg6.set(b6 + 3, 0f); bondSeg6.set(b6 + 4, 0f); bondSeg6.set(b6 + 5, 0f);
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
            double aOff = bindArc.get(m) - 0.5 * slen;            // attachPt = coord + aOff·uVec
            double apx = scx + aOff * sux, apy = scy + aOff * suy, apz = scz + aOff * suz;

            // ---- F8 cross-bridge spring (toward the bound site) ----
            double dx = apx - htipx, dy = apy - htipy, dz = apz - htipz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double fmag = myoSpring * dist;
            double Fx = 0, Fy = 0, Fz = 0;
            if (dist > 0.0) { double inv = fmag / dist; Fx = inv * dx; Fy = inv * dy; Fz = inv * dz; }
            // head-side: +F at tip, torque RH×F (RH = (tip-coord)·1e-6 m)
            double RHx = (htipx - hcx) * 1e-6, RHy = (htipy - hcy) * 1e-6, RHz = (htipz - hcz) * 1e-6;
            double THx = RHy * Fz - RHz * Fy, THy = RHz * Fx - RHx * Fz, THz = RHx * Fy - RHy * Fx;
            // seg-side: -F at attachPt, torque RS×(-F) (RS = (attachPt-coord)·1e-6 m)
            double RSx = (apx - scx) * 1e-6, RSy = (apy - scy) * 1e-6, RSz = (apz - scz) * 1e-6;
            double nFx = -Fx, nFy = -Fy, nFz = -Fz;
            double TSx = RSy * nFz - RSz * nFy, TSy = RSz * nFx - RSx * nFz, TSz = RSx * nFy - RSy * nFx;

            // ---- F9 uVec alignment torque (rest = restDeg) ----
            double t9x = suy * huz - suz * huy, t9y = suz * hux - sux * huz, t9z = sux * huy - suy * hux;
            double m9 = t9x * t9x + t9y * t9y + t9z * t9z;
            double T9x = 0, T9y = 0, T9z = 0;
            if (m9 > 1.0e-30) {
                double im = 1.0 / Math.sqrt(m9); t9x *= im; t9y *= im; t9z *= im;
                double dot = sux * hux + suy * huy + suz * huz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
                double angD = accurateAcos(dot) * RAD2DEG - restDeg;
                double tm = j1FMT * DEG2RAD * angD / ((1.0 / hbRGy + 1.0 / sbRGy) * dt);
                T9x = tm * t9x; T9y = tm * t9y; T9z = tm * t9z;       // seg gets +; head gets -
            }
            // ---- F10 yVec alignment torque (rest 0) ----
            double t10x = syy * hyz - syz * hyy, t10y = syz * hyx - syx * hyz, t10z = syx * hyy - syy * hyx;
            double m10 = t10x * t10x + t10y * t10y + t10z * t10z;
            double T10x = 0, T10y = 0, T10z = 0;
            if (m10 > 1.0e-30) {
                double im = 1.0 / Math.sqrt(m10); t10x *= im; t10y *= im; t10z *= im;
                double dot = syx * hyx + syy * hyy + syz * hyz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
                double ang = accurateAcos(dot) * RAD2DEG;
                double tm = j1FMT * DEG2RAD * ang / ((1.0 / hbRGx + 1.0 / sbRGx) * dt);
                T10x = tm * t10x; T10y = tm * t10y; T10z = tm * t10z;  // seg gets +; head gets -
            }

            // apply head-side (self-write +=): force +F, torque TH - T9 - T10
            bodyForceSum.set(h,          (float) (bodyForceSum.get(h)          + Fx));
            bodyForceSum.set(nB + h,     (float) (bodyForceSum.get(nB + h)     + Fy));
            bodyForceSum.set(2 * nB + h, (float) (bodyForceSum.get(2 * nB + h) + Fz));
            bodyTorqueSum.set(h,          (float) (bodyTorqueSum.get(h)          + THx - T9x - T10x));
            bodyTorqueSum.set(nB + h,     (float) (bodyTorqueSum.get(nB + h)     + THy - T9y - T10y));
            bodyTorqueSum.set(2 * nB + h, (float) (bodyTorqueSum.get(2 * nB + h) + THz - T9z - T10z));

            // store seg-side reaction: force -F, torque TS + T9 + T10
            bondSeg6.set(b6,     (float) nFx); bondSeg6.set(b6 + 1, (float) nFy); bondSeg6.set(b6 + 2, (float) nFz);
            bondSeg6.set(b6 + 3, (float) (TSx + T9x + T10x));
            bondSeg6.set(b6 + 4, (float) (TSy + T9y + T10y));
            bondSeg6.set(b6 + 5, (float) (TSz + T9z + T10z));
        }
    }

    // ===================== segment→bound-motors CSR-inverse (inc-3 pattern, no atomics) =============
    /** Histogram: count bound motors per segment. Single-thread serial (race-free, O(nMotors)). */
    public static void csrHistogram(IntArray boundSeg, IntArray counts, IntArray segMotorCount) {
        int nSeg = counts.get(3);
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int nM = counts.get(0);
            for (int s = 0; s < nSeg; s++) segMotorCount.set(s, 0);
            for (int m = 0; m < nM; m++) { int s = boundSeg.get(m); if (s >= 0) segMotorCount.set(s, segMotorCount.get(s) + 1); }
        }
    }
    /** Exclusive prefix scan → offsets[nSeg]=total; reset segMotorCount to a write cursor. */
    public static void csrScan(IntArray counts, IntArray segMotorCount, IntArray segMotorOffsets) {
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int nSeg = counts.get(3);
            int acc = 0;
            for (int s = 0; s < nSeg; s++) { segMotorOffsets.set(s, acc); acc += segMotorCount.get(s); segMotorCount.set(s, 0); }
            segMotorOffsets.set(nSeg, acc);
        }
    }
    /** Scatter motor ids into the CSR contents, motors visited in index order ⇒ sorted by m. */
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

    /** Segment-side GATHER: each segment sums its bound motors' stored reactions into its own
     *  forceSum/torqueSum (+= onto whatever the filament's own systems wrote; here the filament is
     *  pinned so these start zeroed). Race-free (segment writes self), no atomics. */
    public static void segGather(IntArray segMotorOffsets, IntArray segMotorMyo, FloatArray bondSeg6,
                                 FloatArray filForceSum, FloatArray filTorqueSum, IntArray counts) {
        int nSeg = counts.get(3);
        for (@Parallel int s = 0; s < nSeg; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            int start = segMotorOffsets.get(s), end = segMotorOffsets.get(s + 1);
            for (int k = start; k < end; k++) {
                int m = segMotorMyo.get(k); int b6 = m * 6;
                fx += bondSeg6.get(b6);     fy += bondSeg6.get(b6 + 1); fz += bondSeg6.get(b6 + 2);
                tx += bondSeg6.get(b6 + 3); ty += bondSeg6.get(b6 + 4); tz += bondSeg6.get(b6 + 5);
            }
            filForceSum.set(s,          (float) (filForceSum.get(s)          + fx));
            filForceSum.set(nSeg + s,   (float) (filForceSum.get(nSeg + s)   + fy));
            filForceSum.set(2 * nSeg + s,(float) (filForceSum.get(2 * nSeg + s)+ fz));
            filTorqueSum.set(s,          (float) (filTorqueSum.get(s)          + tx));
            filTorqueSum.set(nSeg + s,   (float) (filTorqueSum.get(nSeg + s)   + ty));
            filTorqueSum.set(2 * nSeg + s,(float) (filTorqueSum.get(2 * nSeg + s)+ tz));
        }
    }

    /** O(nMotors·nSeg) brute-force reference: each segment sums over ALL motors with boundSeg==s
     *  (same per-bond reactions, same motor-index order as the CSR) ⇒ must equal segGather bit-for-bit. */
    public static void bruteGather(IntArray boundSeg, FloatArray bondSeg6,
                                   FloatArray bForceSum, FloatArray bTorqueSum, IntArray counts) {
        int nSeg = counts.get(3), nM = counts.get(0);
        for (@Parallel int s = 0; s < nSeg; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            for (int m = 0; m < nM; m++) {
                if (boundSeg.get(m) != s) continue;
                int b6 = m * 6;
                fx += bondSeg6.get(b6);     fy += bondSeg6.get(b6 + 1); fz += bondSeg6.get(b6 + 2);
                tx += bondSeg6.get(b6 + 3); ty += bondSeg6.get(b6 + 4); tz += bondSeg6.get(b6 + 5);
            }
            bForceSum.set(s, (float) fx); bForceSum.set(nSeg + s, (float) fy); bForceSum.set(2 * nSeg + s, (float) fz);
            bTorqueSum.set(s, (float) tx); bTorqueSum.set(nSeg + s, (float) ty); bTorqueSum.set(2 * nSeg + s, (float) tz);
        }
    }
}
