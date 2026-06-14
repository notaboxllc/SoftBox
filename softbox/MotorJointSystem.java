package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Motor-SPECIFIC system (increment 4b-i): the two articulated-myosin joints. Faithful port
 * of v1 Myosin.applyRodLeverJointForce (J2) + applyLeverMotorJointForce/Torque (J1)
 * (Myosin.java:185-318). Structurally the inc-2 chain joint (a moveCoeff-normalized PAIRS
 * connection spring + a bend torque toward a rest angle), specialized to the myosin
 * sub-body topology and rest angles.
 *
 * Sub-body flat index: 3m=rod, 3m+1=lever, 3m+2=head.
 *   J2 (rod-lever):    connection rod.end2 ↔ lever.end1; angular spring OFF (fracMoveTorq=0).
 *   J1 (lever-motor):  connection lever.end2 ↔ motor.end1; angular spring rest 0° (uncocked),
 *                      capped at the stall-force torque (Myosin.java:241-242).
 *
 * OWNERSHIP (race-free, no atomics — like the chain): one thread per sub-body; each computes
 * the joint contributions ON ITSELF and writes only its own forceSum/torqueSum (+=). A joint
 * is evaluated from BOTH endpoints; the connection forceMag is symmetric (same strain, same
 * moveC sum) so the two sides are exactly equal-and-opposite (Newton's 3rd), and the bend
 * torque is computed identically by both, applied +to one side / -to the other.
 *
 * The PAIRS connection force is applied at the body CENTER (no positional torque) plus an
 * explicit fractional lever-arm torque R×F, R = ½·len·fracR·uVec toward the body's joint end
 * (+uVec for an end2 joint, -uVec for an end1 joint) — exactly v1's incForceSum(F) + R×F pair.
 *
 * jointParams: [0]=dt; J1 [1]=fracMove [2]=fracR [3]=fracMoveTorq [4]=restDeg;
 *              J2 [5]=fracMove [6]=fracR [7]=fracMoveTorq [8]=restDeg; [9]=anchorFracMove
 *              [10]=stallForcePN.
 */
public final class MotorJointSystem {
    private MotorJointSystem() {}

    // Device-safe acos, VERBATIM ChainBendingForceSystem.accurateAcos (v1 GPUMoveThing).
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

    /** v1 moveCoeff (MyoRod/MyoLever/MyoMotor.moveCoeff): effective mobility along the link.
     *  Sign-independent in cosB (squared), so the end argument is immaterial. */
    private static double moveC(double ux, double uy, double uz,
                                double lx, double ly, double lz,
                                double bTGx, double bTGy, double bRGy, double lenUm) {
        double cosB = ux * lx + uy * ly + uz * lz;
        if (cosB > 1.0) cosB = 1.0; if (cosB < -1.0) cosB = -1.0;
        double cosB2 = cosB * cosB;
        double cosA2 = 1.0 - cosB2;
        double lSq = 1.0e-12 * lenUm * lenUm;
        return cosB2 / bTGx + cosA2 / bTGy + lSq * cosA2 / (4.0 * bRGy);
    }

    public static void joints(
            FloatArray coord, FloatArray uVec, FloatArray segLength,
            FloatArray bTransGam, FloatArray bRotGam,
            FloatArray forceSum, FloatArray torqueSum,
            IntArray nucleotideState, FloatArray jointParams, IntArray counts) {

        int nB = coord.getSize() / 3;
        double dt = jointParams.get(0);
        double j1FracMove = jointParams.get(1), j1FracR = jointParams.get(2);
        double j1FracMoveTorq = jointParams.get(3);
        double j2FracMove = jointParams.get(5), j2FracR = jointParams.get(6);
        double j2FracMoveTorq = jointParams.get(7), j2Rest = jointParams.get(8);
        double stallPN = jointParams.get(10);
        double DEG2RAD = Math.PI / 180.0, RAD2DEG = 180.0 / Math.PI;

        for (@Parallel int s = 0; s < nB; s++) {
            int m = s / 3;
            int role = s - 3 * m;
            // J1 lever-motor rest angle switches by nucleotide state (the stroke): cocked (≠ADPPi) 60°, uncocked 0°.
            double j1Rest = (nucleotideState.get(m) != MotorStore.NUC_ADPPI) ? 60.0 : 0.0;
            int rod = 3 * m, lever = 3 * m + 1, head = 3 * m + 2;

            // sub-body poses (double, like the chain)
            double rcx = coord.get(rod), rcy = coord.get(nB + rod), rcz = coord.get(2 * nB + rod);
            double rux = uVec.get(rod),  ruy = uVec.get(nB + rod),  ruz = uVec.get(2 * nB + rod);
            double rlen = segLength.get(rod);
            double lcx = coord.get(lever), lcy = coord.get(nB + lever), lcz = coord.get(2 * nB + lever);
            double lux = uVec.get(lever),  luy = uVec.get(nB + lever),  luz = uVec.get(2 * nB + lever);
            double llen = segLength.get(lever);
            double hcx = coord.get(head), hcy = coord.get(nB + head), hcz = coord.get(2 * nB + head);
            double hux = uVec.get(head),  huy = uVec.get(nB + head),  huz = uVec.get(2 * nB + head);
            double hlen = segLength.get(head);

            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;

            // ===================== J2 (rod-lever): rod.end2 ↔ lever.end1 =====================
            if (role == 0 || role == 1) {
                double aEx = rcx + 0.5 * rlen * rux, aEy = rcy + 0.5 * rlen * ruy, aEz = rcz + 0.5 * rlen * ruz; // rod.end2
                double bEx = lcx - 0.5 * llen * lux, bEy = lcy - 0.5 * llen * luy, bEz = lcz - 0.5 * llen * luz; // lever.end1
                double dx = aEx - bEx, dy = aEy - bEy, dz = aEz - bEz;
                double strain = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double inv = (strain > 0.0) ? 1.0 / strain : 0.0;
                double lx = dx * inv, ly = dy * inv, lz = dz * inv;
                double mcRod   = moveC(rux, ruy, ruz, lx, ly, lz, bTransGam.get(rod),   bTransGam.get(nB + rod),   bRotGam.get(nB + rod),   rlen);
                double mcLever = moveC(lux, luy, luz, lx, ly, lz, bTransGam.get(lever), bTransGam.get(nB + lever), bRotGam.get(nB + lever), llen);
                double denom = dt * (mcRod + mcLever);
                double forceMag = (denom > 0.0) ? (j2FracMove * 1.0e-6 * strain / denom) : 0.0;
                // bend torque (rest 96°; fracMoveTorq=0 ⇒ zero, ported faithfully)
                double tvx = ruy * luz - ruz * luy, tvy = ruz * lux - rux * luz, tvz = rux * luy - ruy * lux;
                double tvm2 = tvx * tvx + tvy * tvy + tvz * tvz;
                double torsionMag = 0.0;
                if (tvm2 > 1.0e-30) {
                    double im = 1.0 / Math.sqrt(tvm2); tvx *= im; tvy *= im; tvz *= im;
                    double dotV = rux * lux + ruy * luy + ruz * luz;
                    if (dotV > 1.0) dotV = 1.0; if (dotV < -1.0) dotV = -1.0;
                    double ang = accurateAcos(dotV) * RAD2DEG;
                    double invBRG = 1.0 / bRotGam.get(nB + rod) + 1.0 / bRotGam.get(nB + lever);
                    torsionMag = j2FracMoveTorq * DEG2RAD * (ang - j2Rest) / (invBRG * dt);
                }
                if (role == 0) {
                    // ROD side: end2 → -forceMag·lu, R = +½·rlen·j2FracR·rod.uVec; torque +tv (v1: rod gets +)
                    double Fx = -forceMag * lx, Fy = -forceMag * ly, Fz = -forceMag * lz;
                    fx += Fx; fy += Fy; fz += Fz;
                    double Rs = 0.5e-6 * rlen * j2FracR, Rx = Rs * rux, Ry = Rs * ruy, Rz = Rs * ruz;
                    tx += Ry * Fz - Rz * Fy; ty += Rz * Fx - Rx * Fz; tz += Rx * Fy - Ry * Fx;
                    tx += tvx * torsionMag; ty += tvy * torsionMag; tz += tvz * torsionMag;
                } else {
                    // LEVER side: end1 → +forceMag·lu, R = -½·llen·j2FracR·lever.uVec; torque -tv (v1: lever gets -)
                    double Fx = forceMag * lx, Fy = forceMag * ly, Fz = forceMag * lz;
                    fx += Fx; fy += Fy; fz += Fz;
                    double Rs = -0.5e-6 * llen * j2FracR, Rx = Rs * lux, Ry = Rs * luy, Rz = Rs * luz;
                    tx += Ry * Fz - Rz * Fy; ty += Rz * Fx - Rx * Fz; tz += Rx * Fy - Ry * Fx;
                    tx -= tvx * torsionMag; ty -= tvy * torsionMag; tz -= tvz * torsionMag;
                }
            }

            // ===================== J1 (lever-motor): lever.end2 ↔ motor.end1 =================
            if (role == 1 || role == 2) {
                double aEx = lcx + 0.5 * llen * lux, aEy = lcy + 0.5 * llen * luy, aEz = lcz + 0.5 * llen * luz; // lever.end2
                double bEx = hcx - 0.5 * hlen * hux, bEy = hcy - 0.5 * hlen * huy, bEz = hcz - 0.5 * hlen * huz; // motor.end1
                double dx = aEx - bEx, dy = aEy - bEy, dz = aEz - bEz;
                double strain = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double inv = (strain > 0.0) ? 1.0 / strain : 0.0;
                double lx = dx * inv, ly = dy * inv, lz = dz * inv;
                double mcLever = moveC(lux, luy, luz, lx, ly, lz, bTransGam.get(lever), bTransGam.get(nB + lever), bRotGam.get(nB + lever), llen);
                double mcHead  = moveC(hux, huy, huz, lx, ly, lz, bTransGam.get(head),  bTransGam.get(nB + head),  bRotGam.get(nB + head),  hlen);
                double denom = dt * (mcLever + mcHead);
                double forceMag = (denom > 0.0) ? (j1FracMove * 1.0e-6 * strain / denom) : 0.0;
                // bend torque toward rest j1Rest, torsionVec = cross(lever.uVec, motor.uVec), capped at stall
                double tvx = luy * huz - luz * huy, tvy = luz * hux - lux * huz, tvz = lux * huy - luy * hux;
                double tvm2 = tvx * tvx + tvy * tvy + tvz * tvz;
                double torsionMag = 0.0;
                if (tvm2 > 1.0e-30) {
                    double im = 1.0 / Math.sqrt(tvm2); tvx *= im; tvy *= im; tvz *= im;
                    double dotV = lux * hux + luy * huy + luz * huz;
                    if (dotV > 1.0) dotV = 1.0; if (dotV < -1.0) dotV = -1.0;
                    double ang = accurateAcos(dotV) * RAD2DEG;
                    double invBRG = 1.0 / bRotGam.get(nB + lever) + 1.0 / bRotGam.get(nB + head);
                    torsionMag = j1FracMoveTorq * DEG2RAD * (ang - j1Rest) / (invBRG * dt);
                    double maxMag = stallPN * 0.5 * hlen * 1.0e-18;     // Myosin.java:241 (pN·µm → N·m)
                    if (torsionMag > maxMag) torsionMag = maxMag;
                }
                if (role == 1) {
                    // LEVER side: end2 → -forceMag·lu, R = +½·llen·j1FracR·lever.uVec; torque +tv (v1: lever gets +)
                    double Fx = -forceMag * lx, Fy = -forceMag * ly, Fz = -forceMag * lz;
                    fx += Fx; fy += Fy; fz += Fz;
                    double Rs = 0.5e-6 * llen * j1FracR, Rx = Rs * lux, Ry = Rs * luy, Rz = Rs * luz;
                    tx += Ry * Fz - Rz * Fy; ty += Rz * Fx - Rx * Fz; tz += Rx * Fy - Ry * Fx;
                    tx += tvx * torsionMag; ty += tvy * torsionMag; tz += tvz * torsionMag;
                } else {
                    // HEAD side: end1 → +forceMag·lu, R = -½·hlen·j1FracR·head.uVec; torque -tv (v1: motor gets -)
                    double Fx = forceMag * lx, Fy = forceMag * ly, Fz = forceMag * lz;
                    fx += Fx; fy += Fy; fz += Fz;
                    double Rs = -0.5e-6 * hlen * j1FracR, Rx = Rs * hux, Ry = Rs * huy, Rz = Rs * huz;
                    tx += Ry * Fz - Rz * Fy; ty += Rz * Fx - Rx * Fz; tz += Rx * Fy - Ry * Fx;
                    tx -= tvx * torsionMag; ty -= tvy * torsionMag; tz -= tvz * torsionMag;
                }
            }

            forceSum.set(s,          (float) (forceSum.get(s)          + fx));
            forceSum.set(nB + s,     (float) (forceSum.get(nB + s)     + fy));
            forceSum.set(2 * nB + s, (float) (forceSum.get(2 * nB + s) + fz));
            torqueSum.set(s,          (float) (torqueSum.get(s)          + tx));
            torqueSum.set(nB + s,     (float) (torqueSum.get(nB + s)     + ty));
            torqueSum.set(2 * nB + s, (float) (torqueSum.get(2 * nB + s) + tz));
        }
    }
}
