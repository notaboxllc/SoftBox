package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Motor-SPECIFIC system (increment 4b-i): the rod-tail bed anchor. Faithful port of v1
 * MyosinFixed.applyRodFixedPtForce (MyosinFixed.java:49-72): a PAIRS connection spring pulling
 * the rod's end1 toward the fixed bed point, with the fixed point immovable (moveC1 = 0) so its
 * reaction is discarded. The force is applied to the rod CENTER with NO torque (v1's torque
 * line is commented out). One thread per motor, writing only its own rod sub-body's forceSum
 * (+=, race-free). Anchor spring coefficient = myoJ2FracMove (jointParams[9]).
 *
 * anchorPt is planar SoA per MOTOR (stride nM); the rod sub-body is 3m in the body arrays
 * (stride nB = 3·nM).
 */
public final class TailAnchorSystem {
    private TailAnchorSystem() {}

    /** v1 moveCoeff — see MotorJointSystem.moveC (duplicated to keep the kernel self-contained). */
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

    public static void anchor(
            FloatArray coord, FloatArray uVec, FloatArray segLength,
            FloatArray bTransGam, FloatArray bRotGam,
            FloatArray forceSum, FloatArray anchorPt,
            FloatArray jointParams, IntArray counts) {

        int nB = coord.getSize() / 3;       // 3·nMotors sub-bodies
        int nM = anchorPt.getSize() / 3;    // nMotors
        double dt = jointParams.get(0);
        double anchorFracMove = jointParams.get(9);

        for (@Parallel int mtr = 0; mtr < nM; mtr++) {
            int rod = 3 * mtr;
            double rcx = coord.get(rod), rcy = coord.get(nB + rod), rcz = coord.get(2 * nB + rod);
            double rux = uVec.get(rod),  ruy = uVec.get(nB + rod),  ruz = uVec.get(2 * nB + rod);
            double rlen = segLength.get(rod);
            // rod.end1 = center - ½·len·uVec
            double e1x = rcx - 0.5 * rlen * rux, e1y = rcy - 0.5 * rlen * ruy, e1z = rcz - 0.5 * rlen * ruz;
            double ax = anchorPt.get(mtr), ay = anchorPt.get(nM + mtr), az = anchorPt.get(2 * nM + mtr);
            double dx = e1x - ax, dy = e1y - ay, dz = e1z - az;   // linkUVec1 = unit(rod.end1 - fixedPt)
            double strain = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double inv = (strain > 0.0) ? 1.0 / strain : 0.0;
            double lx = dx * inv, ly = dy * inv, lz = dz * inv;
            double mc2 = moveC(rux, ruy, ruz, lx, ly, lz,
                    bTransGam.get(rod), bTransGam.get(nB + rod), bRotGam.get(nB + rod), rlen);
            double denom = dt * mc2;                              // moveC1 = 0 (fixed point)
            double forceMag = (denom > 0.0) ? (anchorFracMove * 1.0e-6 * strain / denom) : 0.0;
            // F on rod = -forceMag·linkUVec1 (pulls rod.end1 toward fixedPt), at center, NO torque
            forceSum.set(rod,          (float) (forceSum.get(rod)          - forceMag * lx));
            forceSum.set(nB + rod,     (float) (forceSum.get(nB + rod)     - forceMag * ly));
            forceSum.set(2 * nB + rod, (float) (forceSum.get(2 * nB + rod) - forceMag * lz));
        }
    }
}
