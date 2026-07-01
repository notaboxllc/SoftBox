package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * SPHERE-HEAD anchor motor (PHASE-2, 2026-06-30) — the clean form of BoA Run 2 (neck-swing, 8.5 µm/s).
 *
 * The motor domain is a SPHERE / point anchor at the converter/neck tip (lever.end2): NO head axis,
 * NO F9 alignUVecTorque, NO head-to-filament angle (the head-angle was a non-physical modeling
 * artifact — the motor domain is a compact blob, not a rod). One compliant Hookean attachment binds
 * the sphere to a FIXED material site on the filament (posOnSeg constant once bound, v1's pattern).
 * The POWER STROKE is the lever swinging about the bed-anchored rod: a torque drives the lever's angle
 * to the FILAMENT AXIS toward a state-switched rest (uncocked θ_u ↔ cocked θ_c, 70° apart, centred on
 * 90° for maximal axial throw). The swing axis is defined EXPLICITLY relative to the filament axis
 * (ŝ = lever × fhat, ⟂ filament) — NOT a head×lever cross-product that rotates with the pose (the BoA
 * pin-absorption failure). With the rod tail-anchored and the sphere bound, the swing translates the
 * anchored sphere (and the filament) axially. Step ≈ 2·LEVER_LEN·sin(35°) ≈ 9 nm (skeletal).
 *
 * NO rigid pin, NO orientation pin, NO two-point attachment — a single compliant anchor only. The
 * catch-slip load is the anchor-spring force along the filament axis (v1's real attachment load).
 *
 * sphereParams: [0]=myoSpring(N/µm) [1]=dt(s) [2]=swingCoeff(=j1FracMoveTorq) [3]=θ_uncocked(deg)
 *               [4]=θ_cocked(deg) [5]=stallPN [6]=swingSign(+1/−1; the verify-the-sign handle).
 *
 * This CPU-measurement build writes BOTH sides directly (lever forceSum/torqueSum + filament
 * forceSum/torqueSum) — race-free for one motor per segment; the GPU port routes the seg side through
 * the validated CSR gather. Per-head pure (no atomics/KernelContext) ⇒ CPU≡GPU when ported.
 */
public final class SphereHeadSystem {
    private SphereHeadSystem() {}

    // Device-safe acos, VERBATIM ChainBendingForceSystem/MotorJointSystem.accurateAcos.
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
        double sgn = Math.sin(y);
        if (sgn > 1.0e-12 || sgn < -1.0e-12) { y = y + (Math.cos(y) - x) / sgn; }
        sgn = Math.sin(y);
        if (sgn > 1.0e-12 || sgn < -1.0e-12) { y = y + (Math.cos(y) - x) / sgn; }
        return y;
    }

    /** The single compliant anchor spring: HEAD-sphere center ↔ fixed material site on the filament.
     *  The attachment lives on the HEAD body (the catalytic domain that binds actin), applied at the head
     *  CENTER (a point contact ⇒ NO torque on the head: the sphere is free to rotate — no orientation pin).
     *  Reaction on the filament segment. bondData[d+12] = forceDotFil (the catch-slip load). */
    public static void sphereBond(
            FloatArray coord, FloatArray uVec, FloatArray segLength, FloatArray bRotGam,
            FloatArray filCoord, FloatArray filUVec, FloatArray filSegLength,
            FloatArray forceSum, FloatArray torqueSum,
            FloatArray filForceSum, FloatArray filTorqueSum,
            IntArray boundSeg, FloatArray bindArc, FloatArray bondData, FloatArray sphereParams,
            IntArray counts) {

        int nB = coord.getSize() / 3;
        int nSeg = filCoord.getSize() / 3;
        int nM = counts.get(0);
        double myoSpring = sphereParams.get(0);

        for (@Parallel int m = 0; m < nM; m++) {
            int d = m * CrossBridgeSystem.STRIDE;
            for (int k = 0; k < CrossBridgeSystem.STRIDE; k++) bondData.set(d + k, 0f);
            int s = boundSeg.get(m);
            if (s < 0) continue;
            int head = 3 * m + 2;

            // the anchor point = the HEAD-sphere CONTACT on actin = head TIP (off-centre, = v1's F8 site).
            // Off-centre ⇒ the anchor couples the head's ROTATION to actin (the converter can't spin the head
            // freely past its actin contact). Centre-anchor (R=0) would let a free sphere spin in place ⇒ absorb.
            double hcx = coord.get(head), hcy = coord.get(nB + head), hcz = coord.get(2 * nB + head);
            double hux = uVec.get(head), huy = uVec.get(nB + head), huz = uVec.get(2 * nB + head);
            double hl = segLength.get(head);
            double ax = hcx + 0.5 * hl * hux, ay = hcy + 0.5 * hl * huy, az = hcz + 0.5 * hl * huz;

            double scx = filCoord.get(s), scy = filCoord.get(nSeg + s), scz = filCoord.get(2 * nSeg + s);
            double sux = filUVec.get(s), suy = filUVec.get(nSeg + s), suz = filUVec.get(2 * nSeg + s);
            double slen = filSegLength.get(s);
            double off = bindArc.get(m) - 0.5 * slen;
            double spx = scx + off * sux, spy = scy + off * suy, spz = scz + off * suz;   // fixed material site

            double dx = spx - ax, dy = spy - ay, dz = spz - az;        // head → site
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double fmag = myoSpring * dist;
            double Fx = 0, Fy = 0, Fz = 0;
            if (dist > 0.0) { double inv = fmag / dist; Fx = inv * dx; Fy = inv * dy; Fz = inv * dz; }

            // motor side: +F at the head TIP; R = (tip − head.center) ⇒ torque R×F couples rotation to actin
            double RHx = (ax - hcx) * 1e-6, RHy = (ay - hcy) * 1e-6, RHz = (az - hcz) * 1e-6;
            double THx = RHy * Fz - RHz * Fy, THy = RHz * Fx - RHx * Fz, THz = RHx * Fy - RHy * Fx;
            forceSum.set(head,          (float) (forceSum.get(head)          + Fx));
            forceSum.set(nB + head,     (float) (forceSum.get(nB + head)     + Fy));
            forceSum.set(2 * nB + head, (float) (forceSum.get(2 * nB + head) + Fz));
            torqueSum.set(head,          (float) (torqueSum.get(head)          + THx));
            torqueSum.set(nB + head,     (float) (torqueSum.get(nB + head)     + THy));
            torqueSum.set(2 * nB + head, (float) (torqueSum.get(2 * nB + head) + THz));

            // seg side: −F at the material site. R = (site − seg.center)
            double RSx = (spx - scx) * 1e-6, RSy = (spy - scy) * 1e-6, RSz = (spz - scz) * 1e-6;
            double nFx = -Fx, nFy = -Fy, nFz = -Fz;
            double TSx = RSy * nFz - RSz * nFy, TSy = RSz * nFx - RSx * nFz, TSz = RSx * nFy - RSy * nFx;
            filForceSum.set(s,            (float) (filForceSum.get(s)            + nFx));
            filForceSum.set(nSeg + s,     (float) (filForceSum.get(nSeg + s)     + nFy));
            filForceSum.set(2 * nSeg + s, (float) (filForceSum.get(2 * nSeg + s) + nFz));
            filTorqueSum.set(s,            (float) (filTorqueSum.get(s)            + TSx));
            filTorqueSum.set(nSeg + s,     (float) (filTorqueSum.get(nSeg + s)     + TSy));
            filTorqueSum.set(2 * nSeg + s, (float) (filTorqueSum.get(2 * nSeg + s) + TSz));

            // forceDotFil = Dot(F, seg.uVec) — the along-filament anchor load (catch-slip input)
            double fdf = Fx * sux + Fy * suy + Fz * suz;
            bondData.set(d,     (float) Fx); bondData.set(d + 1, (float) Fy); bondData.set(d + 2, (float) Fz);
            bondData.set(d + 12, (float) fdf);
        }
    }

    /** PAIRS-style orientation torque maintaining the BINDING POSE: drives the head axis (head.uVec)
     *  toward PERPENDICULAR (90°) to the filament axis (seg.uVec) — a fixed-rest port of v1's F9
     *  alignUVecTorque, applied at the head/tip cross-bridge. It is a compliant fracMove-normalized
     *  couple (NOT a rigid clamp): head gets −T·(seg×head), the segment the +T reaction (v1 F9 signs).
     *  Combined with the tip anchor (which holds the bound contact), this reorients the head ABOUT its
     *  bound tip toward ⊥ — supplying the head's actin-orientation reference (the powerstroke's axial
     *  directedness) without pinning the head. coeff = sphereParams[2] (the PAIRS fracMoveTorq). */
    public static void perpTorque(
            FloatArray coord, FloatArray uVec, FloatArray bRotGam,
            FloatArray filUVec, FloatArray filBRotGam,
            FloatArray torqueSum, FloatArray filTorqueSum,
            IntArray boundSeg, FloatArray sphereParams, IntArray counts) {

        int nB = coord.getSize() / 3;
        int nSeg = filUVec.getSize() / 3;
        int nM = counts.get(0);
        double dt = sphereParams.get(1);
        double coeff = (sphereParams.getSize() > 2) ? sphereParams.get(2) : 0.4;
        double restDeg = 90.0;                                   // perpendicular = the binding orientation
        double DEG2RAD = Math.PI / 180.0, RAD2DEG = 180.0 / Math.PI;

        for (@Parallel int m = 0; m < nM; m++) {
            int s = boundSeg.get(m);
            if (s < 0) continue;
            int head = 3 * m + 2;
            double hux = uVec.get(head), huy = uVec.get(nB + head), huz = uVec.get(2 * nB + head);
            double sux = filUVec.get(s), suy = filUVec.get(nSeg + s), suz = filUVec.get(2 * nSeg + s);
            double t9x = suy * huz - suz * huy, t9y = suz * hux - sux * huz, t9z = sux * huy - suy * hux;  // seg × head
            double m9 = t9x * t9x + t9y * t9y + t9z * t9z;
            if (m9 <= 1.0e-30) continue;                         // head ∥ seg: axis undefined (already at 0/180, not 90)
            double im = 1.0 / Math.sqrt(m9); t9x *= im; t9y *= im; t9z *= im;
            double dot = sux * hux + suy * huy + suz * huz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
            double angD = accurateAcos(dot) * RAD2DEG - restDeg; // toward 90°
            double tm = coeff * DEG2RAD * angD / ((1.0 / bRotGam.get(nB + head) + 1.0 / filBRotGam.get(nSeg + s)) * dt);
            torqueSum.set(head,          (float) (torqueSum.get(head)          - tm * t9x));
            torqueSum.set(nB + head,     (float) (torqueSum.get(nB + head)     - tm * t9y));
            torqueSum.set(2 * nB + head, (float) (torqueSum.get(2 * nB + head) - tm * t9z));
            filTorqueSum.set(s,            (float) (filTorqueSum.get(s)            + tm * t9x));
            filTorqueSum.set(nSeg + s,     (float) (filTorqueSum.get(nSeg + s)     + tm * t9y));
            filTorqueSum.set(2 * nSeg + s, (float) (filTorqueSum.get(2 * nSeg + s) + tm * t9z));
        }
    }

    /** The POWER STROKE: a torque swinging the lever toward θ_rest relative to the FILAMENT axis, about
     *  ŝ = lever × fhat (⟂ filament). +T on the lever, −T on the rod (the bed-anchored reaction point —
     *  Newton's 3rd at the body, the rod's reaction is absorbed by the tail anchor). NO force; the step
     *  EMERGES from the swing geometry through the anchor spring. */
    public static void swing(
            FloatArray coord, FloatArray uVec, FloatArray segLength, FloatArray bRotGam,
            FloatArray forceSum, FloatArray torqueSum, FloatArray filTorqueSum,
            FloatArray filUVec, IntArray boundSeg, IntArray nucleotideState,
            FloatArray sphereParams, IntArray counts) {

        int nB = coord.getSize() / 3;
        int nSeg = filUVec.getSize() / 3;
        int nM = counts.get(0);
        double dt = sphereParams.get(1);
        double coeff = sphereParams.get(2);
        double thetaU = sphereParams.get(3), thetaC = sphereParams.get(4);
        double stallPN = sphereParams.get(5);
        double swingSign = sphereParams.get(6);
        // reaction target: 0 = rod (needs a rigid tail), 1 = filament (v1's F9 reacts on actin — compliant-tail-safe)
        int reactOnFil = (sphereParams.getSize() > 7) ? (int) sphereParams.get(7) : 0;
        double DEG2RAD = Math.PI / 180.0, RAD2DEG = 180.0 / Math.PI;

        for (@Parallel int m = 0; m < nM; m++) {
            int s = boundSeg.get(m);
            if (s < 0) continue;
            int rod = 3 * m, lever = 3 * m + 1;

            double lux = uVec.get(lever), luy = uVec.get(nB + lever), luz = uVec.get(2 * nB + lever);
            double fhx = filUVec.get(s), fhy = filUVec.get(nSeg + s), fhz = filUVec.get(2 * nSeg + s);

            // θ = angle(lever.uVec, fhat); swing axis ŝ = lever × fhat (⟂ filament, the swing-plane normal)
            double tvx = luy * fhz - luz * fhy, tvy = luz * fhx - lux * fhz, tvz = lux * fhy - luy * fhx;
            double tvm2 = tvx * tvx + tvy * tvy + tvz * tvz;
            if (tvm2 <= 1.0e-30) continue;                         // lever ∥ filament: swing plane degenerate
            double im = 1.0 / Math.sqrt(tvm2); tvx *= im; tvy *= im; tvz *= im;
            double dot = lux * fhx + luy * fhy + luz * fhz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
            double theta = accurateAcos(dot) * RAD2DEG;

            double rest = (nucleotideState.get(m) != MotorStore.NUC_ADPPI) ? thetaC : thetaU;
            double invBRG = 1.0 / bRotGam.get(nB + lever) + 1.0 / bRotGam.get(nB + rod);
            double mag = swingSign * coeff * DEG2RAD * (theta - rest) / (invBRG * dt);
            double maxMag = stallPN * 0.5 * segLength.get(lever) * 1.0e-18;   // cap at the stall-force torque
            if (mag > maxMag) mag = maxMag;
            if (mag < -maxMag) mag = -maxMag;

            // +T on the lever (the converter)
            torqueSum.set(lever,          (float) (torqueSum.get(lever)          + tvx * mag));
            torqueSum.set(nB + lever,     (float) (torqueSum.get(nB + lever)     + tvy * mag));
            torqueSum.set(2 * nB + lever, (float) (torqueSum.get(2 * nB + lever) + tvz * mag));
            // −T reaction: on the FILAMENT (v1-faithful, compliant-tail-safe) OR on the rod (needs a rigid tail)
            if (reactOnFil != 0) {
                filTorqueSum.set(s,            (float) (filTorqueSum.get(s)            - tvx * mag));
                filTorqueSum.set(nSeg + s,     (float) (filTorqueSum.get(nSeg + s)     - tvy * mag));
                filTorqueSum.set(2 * nSeg + s, (float) (filTorqueSum.get(2 * nSeg + s) - tvz * mag));
            } else {
                torqueSum.set(rod,          (float) (torqueSum.get(rod)          - tvx * mag));
                torqueSum.set(nB + rod,     (float) (torqueSum.get(nB + rod)     - tvy * mag));
                torqueSum.set(2 * nB + rod, (float) (torqueSum.get(2 * nB + rod) - tvz * mag));
            }
        }
    }
}
