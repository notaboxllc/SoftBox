package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * BINDING-ARTEFACT LEDGER (2026-10-26, jba): does binding deliver forces or roll torque the motor should not have?
 * Diagnostic only; reads bondData after every bond-side writer (bond, weakForce, headRoll, siteCouple) and before
 * the segment gather, writes nothing any other system reads. Both runners, same method.
 *
 * <p>Per motor, per step, with key gk = the gather key (boundSeg, or the weak segment when -weak-bind):
 * the segment reaction F = bondData[6..8] and its torque about the segment centre T = bondData[9..11]; the ROLL
 * torque on the filament is T . u_seg (the segment centre lies on the filament axis). Phases: WEAK (gk >= 0,
 * boundSeg < 0), STRONG pre-stroke (bound, ADP.Pi), STRONG post-stroke (bound, other nucleotide).
 *
 * Rows of lg (x N, motor-minor):
 *   0 weak roll impulse  | 1 strong pre-stroke roll impulse | 2 strong post-stroke roll impulse    (N m s)
 *   3 weak sum |F| (N)   | 4 weak max |F|                   | 5 weak steps
 *   6 births             | 7 sum E_birth (kT)               | 8 max E_birth (kT)  | 9 last birth step
 *   10 max |F| in the first window after a birth | 11 sum |F| in that window | 12 window steps
 *   13 weak sum |roll torque| dt | 14 strong pre-stroke sum |roll torque| dt
 * lgP: 0 dt | 1 kF8 (N/m) | 2 kT (J) | 3 window steps
 */
final class BindLedger {
    static boolean ON = false;
    static double WINDOW_S = 1.0e-4;
    static final int ROWS = 15;

    static void alloc(ExplicitCompleteMatHarness.ExMat e, double dt, double kF8NperUm, double kT) {
        e.lg = new DoubleArray(ROWS * e.N); e.lg.init(0.0);
        for (int m = 0; m < e.N; m++) e.lg.set(9 * e.N + m, -1e18);
        e.lgP = new DoubleArray(4);
        e.lgP.set(0, dt); e.lgP.set(1, kF8NperUm * 1e6); e.lgP.set(2, kT); e.lgP.set(3, Math.max(1.0, Math.rint(WINDOW_S / dt)));
    }

    public static void ledger(IntArray gk, IntArray boundSeg, IntArray nuc, IntArray justBound, FloatArray bondData,
                              FloatArray filUVec, DoubleArray lg, DoubleArray lgP, IntArray matc, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3), STRIDE = 13;
        double dt = lgP.get(0), k = lgP.get(1), kT = lgP.get(2), win = lgP.get(3);
        double tt = matc.get(0);
        for (@Parallel int m = 0; m < N; m++) {
            int s = gk.get(m);
            if (s < 0) continue;
            int d = m * STRIDE;
            double fx = bondData.get(d + 6), fy = bondData.get(d + 7), fz = bondData.get(d + 8);
            double f = Math.sqrt(fx * fx + fy * fy + fz * fz);
            double roll = bondData.get(d + 9) * filUVec.get(s) + bondData.get(d + 10) * filUVec.get(nSeg + s) + bondData.get(d + 11) * filUVec.get(2 * nSeg + s);
            double ar = roll < 0 ? -roll : roll;
            if (boundSeg.get(m) < 0) {   // weak
                lg.set(m, lg.get(m) + roll * dt);
                lg.set(3 * N + m, lg.get(3 * N + m) + f);
                if (f > lg.get(4 * N + m)) lg.set(4 * N + m, f);
                lg.set(5 * N + m, lg.get(5 * N + m) + 1.0);
                lg.set(13 * N + m, lg.get(13 * N + m) + ar * dt);
            } else {
                boolean pre = nuc.get(m) == MotorStore.NUC_ADPPI;
                if (pre) { lg.set(N + m, lg.get(N + m) + roll * dt); lg.set(14 * N + m, lg.get(14 * N + m) + ar * dt); }
                else lg.set(2 * N + m, lg.get(2 * N + m) + roll * dt);
                if (justBound.get(m) == 1) {
                    double eb = 0.5 * f * f / k / kT;
                    lg.set(6 * N + m, lg.get(6 * N + m) + 1.0);
                    lg.set(7 * N + m, lg.get(7 * N + m) + eb);
                    if (eb > lg.get(8 * N + m)) lg.set(8 * N + m, eb);
                    lg.set(9 * N + m, tt);
                }
                if (tt - lg.get(9 * N + m) < win) {
                    if (f > lg.get(10 * N + m)) lg.set(10 * N + m, f);
                    lg.set(11 * N + m, lg.get(11 * N + m) + f);
                    lg.set(12 * N + m, lg.get(12 * N + m) + 1.0);
                }
            }
        }
    }

    static double sum(ExplicitCompleteMatHarness.ExMat e, int row) { double s = 0; for (int m = 0; m < e.N; m++) s += e.lg.get(row * e.N + m); return s; }
    static double max(ExplicitCompleteMatHarness.ExMat e, int row) { double s = 0; for (int m = 0; m < e.N; m++) s = Math.max(s, e.lg.get(row * e.N + m)); return s; }

    /** Summary. simT = simulated seconds covered. Roll impulses in pN nm ms; forces in pN. */
    static String report(ExplicitCompleteMatHarness.ExMat e, double simT) {
        double c = 1e21 * 1e3;   // N m s -> pN nm ms
        double ws = sum(e, 5), nb = sum(e, 6), wn = sum(e, 12);
        double dt = e.lgP.get(0);
        return String.format(java.util.Locale.US,
                "BIND LEDGER (%.2f ms simulated):%n"
              + "  roll impulse on the filament [pN nm ms]: weak %+.4f (|.| %.4f) | strong pre-stroke %+.4f (|.| %.4f) | strong post-stroke %+.4f%n"
              + "  mean roll torque [pN nm]: weak %+.3f | strong pre-stroke %+.3f | strong post-stroke %+.3f%n"
              + "  weak tether: mean |F| %.2f pN, max %.2f pN over %.0f weak motor-steps%n"
              + "  strong births %.0f: strain energy at birth mean %.2f kT, max %.2f kT | first %.0f us: mean |F| %.2f pN, max %.2f pN%n",
                simT * 1e3, sum(e, 0) * c, sum(e, 13) * c, sum(e, 1) * c, sum(e, 14) * c, sum(e, 2) * c,
                sum(e, 0) * 1e21 / simT, sum(e, 1) * 1e21 / simT, sum(e, 2) * 1e21 / simT,
                ws > 0 ? sum(e, 3) / ws * 1e12 : Double.NaN, max(e, 4) * 1e12, ws,
                nb, nb > 0 ? sum(e, 7) / nb : Double.NaN, max(e, 8), e.lgP.get(3) * dt * 1e6, wn > 0 ? sum(e, 11) / wn * 1e12 : Double.NaN, max(e, 10) * 1e12);
    }
}
