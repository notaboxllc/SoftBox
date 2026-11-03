package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * RELEASE-FORCE SHADOW DIAGNOSTIC (2026-10-26, jba). Diagnostic only: writes nothing any other system reads, so the
 * trajectory is unchanged. Both runners, same method; runs immediately before the chemistry kernel and reads the same
 * forceDotFil the catch-slip ADP release reads.
 *
 * <p>Question. The ADP -> NONE release is k_ADP * g(F) with g(F) = aC exp(-F xC/kT) + aS exp(F xS/kT), evaluated on the
 * INSTANTANEOUS axial bond force every step. g is convex, so thermal force noise raises the mean rate (Jensen). Guo &
 * Guilford 2006 fitted their lifetimes against the trap-reported (bead-averaged) load. This kernel accumulates, on every
 * bound ADP motor-step, g(F_inst) and g(EMA_tau(F)) for NT running-average windows, plus the moments of F and of each EMA,
 * so the effective release rate under each input and the force's correlation time come from ONE trajectory.
 * The EMAs run over the whole bound episode (as the chemistry's own -tauavg EMA does), seeded at bind, reset when free.
 *
 * <p>rdP: 0 dt | 1 NT | 2.. alpha_k = dt / tau_k.
 * rdS (NT x N): EMA state per window; rdI (N): 1 = seeded.
 * rdA rows (x N): 0 ADP steps | 1 sum F | 2 sum F^2 | 3 sum g(F) | 4+k sum g(EMA_k) | 4+NT+k sum EMA_k | 4+2NT+k sum EMA_k^2
 * | 4+3NT sum g(forceDotAvg) = the chemistry's own -tauavg input (previous step's EMA; meaningful only when -release-tau-us > 0)
 * | 5+3NT sum |forceDotAvg - EMA_k| for the window k that matches the chemistry's alpha (consistency check; N).
 */
final class ReleaseDiag {
    static boolean ON = false;
    static double[] TAUS_US = {1, 3, 10, 30, 100, 300};

    static int rows(int nt) { return 6 + 3 * nt; }

    static void alloc(ExplicitCompleteMatHarness.ExMat e, double dt) {
        int nt = TAUS_US.length;
        e.rdP = new DoubleArray(2 + nt); e.rdP.set(0, dt); e.rdP.set(1, nt);
        for (int k = 0; k < nt; k++) e.rdP.set(2 + k, Math.min(1.0, dt / (TAUS_US[k] * 1e-6)));
        e.rdS = new DoubleArray(nt * e.N); e.rdS.init(0.0);
        e.rdI = new IntArray(e.N); e.rdI.init(0);
        e.rdA = new DoubleArray(rows(nt) * e.N); e.rdA.init(0.0);
    }

    public static void releaseDiag(IntArray nuc, IntArray boundSeg, FloatArray forceDotFil, FloatArray forceDotAvg, FloatArray kinParams,
                                   DoubleArray rdP, DoubleArray rdS, IntArray rdI, DoubleArray rdA, IntArray counts) {
        int N = counts.get(0);
        int nt = (int) rdP.get(1);
        float alphaChem = kinParams.get(17);
        int kMatch = -1; for (int k = 0; k < nt; k++) { double d = rdP.get(2 + k) - alphaChem; if (d < 1e-9 && d > -1e-9) kMatch = k; }
        float aC = kinParams.get(1), aS = kinParams.get(2), xC = kinParams.get(3), xS = kinParams.get(4), kT = kinParams.get(5);
        for (@Parallel int m = 0; m < N; m++) {
            if (boundSeg.get(m) < 0) { rdI.set(m, 0); continue; }
            double F = forceDotFil.get(m);
            boolean seed = rdI.get(m) == 0;
            for (int k = 0; k < nt; k++) {
                double v = seed ? F : rdS.get(k * N + m) + rdP.get(2 + k) * (F - rdS.get(k * N + m));
                rdS.set(k * N + m, v);
            }
            rdI.set(m, 1);
            if (nuc.get(m) != MotorStore.NUC_ADP) continue;
            float fa = forceDotAvg.get(m);
            float ga = aC * (float) Math.exp(-fa * xC / kT) + aS * (float) Math.exp(fa * xS / kT);
            rdA.set((4 + 3 * nt) * N + m, rdA.get((4 + 3 * nt) * N + m) + ga);
            if (kMatch >= 0) { double dd = fa - rdS.get(kMatch * N + m); rdA.set((5 + 3 * nt) * N + m, rdA.get((5 + 3 * nt) * N + m) + (dd < 0 ? -dd : dd)); }
            rdA.set(m, rdA.get(m) + 1.0);
            rdA.set(N + m, rdA.get(N + m) + F);
            rdA.set(2 * N + m, rdA.get(2 * N + m) + F * F);
            float Ff = (float) F;
            float gi = aC * (float) Math.exp(-Ff * xC / kT) + aS * (float) Math.exp(Ff * xS / kT);
            rdA.set(3 * N + m, rdA.get(3 * N + m) + gi);
            for (int k = 0; k < nt; k++) {
                double v = rdS.get(k * N + m);
                float vf = (float) v;
                float g = aC * (float) Math.exp(-vf * xC / kT) + aS * (float) Math.exp(vf * xS / kT);
                rdA.set((4 + k) * N + m, rdA.get((4 + k) * N + m) + g);
                rdA.set((4 + nt + k) * N + m, rdA.get((4 + nt + k) * N + m) + v);
                rdA.set((4 + 2 * nt + k) * N + m, rdA.get((4 + 2 * nt + k) * N + m) + v * v);
            }
        }
    }

    static double sum(ExplicitCompleteMatHarness.ExMat e, int row) { double s = 0; for (int m = 0; m < e.N; m++) s += e.rdA.get(row * e.N + m); return s; }

    /** Summary: mean g under each input (= effective ADP release / k_ADP), F and EMA spread, implied correlation time. */
    static String report(ExplicitCompleteMatHarness.ExMat e) {
        int nt = TAUS_US.length;
        double n = sum(e, 0);
        StringBuilder b = new StringBuilder();
        if (n <= 0) return "RELEASE DIAG: no bound ADP motor-steps\n";
        double mF = sum(e, 1) / n, vF = sum(e, 2) / n - mF * mF;
        b.append(String.format(java.util.Locale.US, "RELEASE DIAG (shadow; %.0f bound ADP motor-steps):%n  instantaneous F: mean %+.3f pN, sd %.3f pN, mean g %.3f%n",
                n, mF * 1e12, Math.sqrt(Math.max(0, vF)) * 1e12, sum(e, 3) / n));
        b.append("  EMA window   mean g   sd(EMA) pN   var(EMA)/var(F)   tau_c est (us, = ratio * 2 tau)\n");
        for (int k = 0; k < nt; k++) {
            double mv = sum(e, 4 + nt + k) / n, vv = sum(e, 4 + 2 * nt + k) / n - mv * mv;
            double r = vF > 0 ? vv / vF : Double.NaN;
            b.append(String.format(java.util.Locale.US, "  %7.0f us   %6.3f   %8.3f      %8.4f          %8.2f%n",
                    TAUS_US[k], sum(e, 4 + k) / n, Math.sqrt(Math.max(0, vv)) * 1e12, r, r * 2 * TAUS_US[k]));
        }
        b.append(String.format(java.util.Locale.US, "  chemistry input (forceDotAvg, -release-tau-us only): mean g %.3f | mean |forceDotAvg - matching EMA| %.3g pN%n", sum(e, 4 + 3 * nt) / n, sum(e, 5 + 3 * nt) / n * 1e12));
        b.append("  (effective ADP release = k_ADP x mean g; tau_c estimate valid where tau >> tau_c; EMA variance also keeps real slow load swings)\n");
        return b.toString();
    }
}
