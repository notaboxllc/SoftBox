package softbox;

import softbox.TwoBodyConverterMotor.Glide2D;
import uk.ac.manchester.tornado.api.*;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Locale;

/**
 * ISOLATED LOWERING PROBE for {@link TwoBodyBeamAnalyticGpu#matS2SolveStepTilt} — the chi-dynamic 3-D head
 * solver. This answers ONE question before any device wiring is attempted:
 *
 *   <b>does the 15x16 tilt solver lower and execute on the TornadoVM PTX backend?</b>
 *
 * <p>Motivation: {@code buildGlidingGraph} REFUSES {@code siteNormalOn()} because the entire chi-dynamic head
 * stack has never been on the device graph (docs/motor/SITE_NORMAL_HEAD_BINDING.md §16). Of the five kernels
 * that port would need, this is the largest and the only one with real lowering risk: it replaces the existing
 * {@code s2solve} task with ONE extra generalized coordinate (chi), taking the augmented Gauss-Jordan system
 * from 14x15 to 15x16 and per-motor scratch from 210 to 240 doubles, on a backend where the SMALLER version
 * already requires the documented {@code -Dtornado.enable.fma=false} workaround. Its signature is also exactly
 * 15 arguments — TornadoVM's {@code task()} ceiling, with zero headroom.
 *
 * <p>Structure mirrors {@link ExplicitMatSolveHarness} (the non-tilt gate) deliberately, so the two are
 * comparable. ONE difference in what is testable: the non-tilt gate has a scalar CPU twin ({@code s2SolveM})
 * to check the port against, and the tilt path has none — so this gate is <b>GPU vs CPU-mirror only</b>, i.e.
 * the same kernel on two runners, which is the project's CPU=GPU standard.
 *
 * <p>NOTHING is wired into any production graph and no equation is touched. Run via:
 * {@code ./scripts/run_gpu_monitored.sh ./scripts/run_mats2solve_tilt_gate.sh}
 */
public final class ExplicitMatSolveTiltHarness {
    static final double DT = 2.5e-6;
    static final String OUT = "RUN_LOGS/explicit_mats2_tilt";

    public static void main(String[] args) {
        try { Files.createDirectories(Path.of(OUT)); } catch (IOException e) { throw new UncheckedIOException(e); }
        if (!"false".equals(System.getProperty("tornado.recover.bailout")))
            System.out.println("!! run with -Dtornado.recover.bailout=false (else a bailout hides as a silent CPU fallback)");
        StringBuilder log = new StringBuilder("# matS2SolveStepTilt — ISOLATED lowering probe + CPU/GPU one-step gate\n\n");
        boolean ok = gate(log);
        try { Files.writeString(Path.of(OUT, "MATS2SOLVE_TILT_GATE.md"), log.toString()); } catch (IOException e) { throw new UncheckedIOException(e); }
        System.out.println("# report: " + Path.of(OUT, "MATS2SOLVE_TILT_GATE.md").toAbsolutePath());
        System.out.println(ok ? "=== MATS2SOLVE-TILT GATE: PASS ===" : "=== MATS2SOLVE-TILT GATE: REVIEW ===");
        System.exit(ok ? 0 : 1);
    }

    static boolean gate(StringBuilder log) {
        int t = 7, seed = 101, K = 5;
        double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        Glide2D G = TwoBodyConverterMotor.buildS2Mat(200.0, DT, 40.0, slack, seed);
        int M = G.g4M, N = G.N; if (K > N) K = N;
        int nF = 3 * M, n = nF + 3, W = n + 1;          // the TILT system: 15x16 at M=4
        int tiltStride = n * W;                          // 240 at M=4 (vs SYS_STRIDE = 210 non-tilt)
        String[] label = { "relaxed-bound", "high-axial", "transverse-bend", "near-taut", "post-stroke" };

        log.append(String.format(Locale.US,
                "Scene: buildS2Mat(200 heads/um^2, dt=%.1e, L_S2=40nm), M=%d beam segments, K=%d motors.\n"
              + "TILT system: nF=%d, n=**%d**, W=**%d** => augmented %dx%d, per-motor scratch **%d** doubles "
              + "(non-tilt SYS_STRIDE = %d).\n"
              + "Signature is **15 args** = TornadoVM task() cap, zero headroom.\n\n",
                DT, M, K, nF, n, W, n, W, tiltStride, TwoBodyBeamAnalyticGpu.SYS_STRIDE));

        for (int m = 0; m < K; m++) {
            int s = nearestSegSafe(G, m);
            G.mot.boundSeg.set(m, s < 0 ? 0 : s);
            perturb(G, m, m % 5);
            double[] bh = G.bhat; double fp = (1.0 + m) * 1e-12;
            int d = m * 13; double sgn = (m % 2 == 0) ? -1 : 1;
            G.bondData.set(d, (float) (sgn * fp * bh[0])); G.bondData.set(d + 1, (float) (sgn * fp * bh[1])); G.bondData.set(d + 2, (float) (sgn * fp * bh[2]));
            G.bondData.set(d + 12, (float) (sgn * fp));
            TwoBodyConverterMotor.geom2D(G, m);
        }

        // ---- pack the SoA ------------------------------------------------------------------------------
        DoubleArray nodes = new DoubleArray(3 * (M + 1) * K), frame = new DoubleArray(15 * K);
        DoubleArray q = new DoubleArray(4 * K), params = new DoubleArray(19 * K);   // row 17 leverRest, row 18 gamma_r
        DoubleArray outGeom = new DoubleArray(9 * K);
        FloatArray bond = new FloatArray(13 * K), forceDotFil = new FloatArray(K), forceMag = new FloatArray(K);
        IntArray boundSeg = new IntArray(K);
        // counts[4] = myWorker. restC row 8 must equal it or the motor is skipped as culled.
        IntArray matc = IntArray.fromElements(t, seed, 1, 0, TwoBodyConverterMotor.F8_AXIS_LEGACY ? 0 : 1);
        IntArray counts = IntArray.fromElements(K, 1, M, 0, 0);
        DoubleArray convId = TwoBodyBeamAnalyticGpu.identityConvFrame(K);

        double[] pr = paramArr(G);
        double Rh = TwoBodyConverterMotor.RHEAD_3C, rc2 = G.rConv[0] * G.rConv[0] + G.rConv[1] * G.rConv[1];
        double rotFrac = (8.0 * Rh * Rh * Rh) / (8.0 * Rh * Rh * Rh + 6.0 * Rh * rc2);
        for (int m = 0; m < K; m++) {
            for (int j = 0; j <= M; j++) for (int k = 0; k < 3; k++) nodes.set((3 * j + k) * K + m, G.g4Node[m][j][k]);
            double[] fr = frameArr(G, m); for (int c = 0; c < 15; c++) frame.set(c * K + m, fr[c]);
            for (int c = 0; c < 17; c++) params.set(c * K + m, pr[c]);
            params.set(17 * K + m, leverRest0(G, m));
            params.set(18 * K + m, pr[9] * rotFrac);            // gamma_r, as ExplicitCompleteMatHarness builds it
            q.set(m, G.phi[m]); q.set(K + m, G.psi[m]); q.set(2 * K + m, G.thetaS[m]); q.set(3 * K + m, G.psiActin[m]);
            boundSeg.set(m, G.mot.boundSeg.get(m));
            for (int c = 0; c < 13; c++) bond.set(m * 13 + c, G.bondData.get(m * 13 + c));
        }

        // chi and restC — the two buffers the tilt kernel adds. chi is varied so the run genuinely exercises
        // chi != 0 (at chi == 0 the tilt solver reduces to the non-tilt one and would prove nothing new).
        // restC rows 0..2 = the detached target in the neck frame; (1,0,0) is the straight-stick value and is
        // well conditioned (all-zero would normalize a zero vector). Rows 3..7 = the site-normal bound target;
        // motors 1 and 3 carry flag row 6 = 1 so BOTH bound branches are exercised. Row 8 = worker tag = 0.
        DoubleArray chiHead = new DoubleArray(K), restC = new DoubleArray(9 * K);
        chiHead.init(0.0); restC.init(0.0);
        for (int m = 0; m < K; m++) {
            chiHead.set(m, 0.15 * (m - 2));                       // -0.30 .. +0.30 rad
            restC.set(m, 1.0); restC.set(K + m, 0.0); restC.set(2 * K + m, 0.0);
            if (m == 1 || m == 3) {                               // site-normal bound target branch
                double[] u = G.eup;
                restC.set(3 * K + m, -u[0]); restC.set(4 * K + m, -u[1]); restC.set(5 * K + m, -u[2]);
                restC.set(6 * K + m, 1.0);
                restC.set(7 * K + m, 0.0);
            }
            restC.set(8 * K + m, 0.0);                            // worker tag == counts[4] == 0
        }

        // ---- CPU-mirror: the same kernel called directly (plain Java = the CPU runner) ------------------
        DoubleArray nMir = copy(nodes), qMir = copy(q), oMir = new DoubleArray(9 * K), chiMir = copy(chiHead);
        DoubleArray sMir = new DoubleArray(tiltStride * K); sMir.init(0.0);
        FloatArray fdfMir = new FloatArray(K), fmMir = new FloatArray(K);
        TwoBodyBeamAnalyticGpu.matS2SolveStepTilt(nMir, copy(frame), qMir, bond, boundSeg, copy(params), sMir,
                oMir, fdfMir, fmMir, matc, counts, TwoBodyBeamAnalyticGpu.identityConvFrame(K), chiMir, copy(restC));

        // ---- §1 LOWERING PROBE -------------------------------------------------------------------------
        log.append("## 1. Lowering probe (single-task device TaskGraph, no silent fallback)\n\n");
        boolean lowered; String err = null; double gpuMs = 0;
        DoubleArray nGpu = copy(nodes), qGpu = copy(q), oGpu = new DoubleArray(9 * K), chiGpu = copy(chiHead);
        DoubleArray sGpu = new DoubleArray(tiltStride * K); sGpu.init(0.0);
        DoubleArray rcGpu = copy(restC), frGpu = copy(frame), paGpu = copy(params);
        FloatArray fdfGpu = new FloatArray(K), fmGpu = new FloatArray(K);
        try {
            TaskGraph tg = new TaskGraph("mats2tilt")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, nGpu, frGpu, qGpu, bond, boundSeg, paGpu, sGpu, matc, counts, convId, chiGpu, rcGpu)
                .task("s2tilt", TwoBodyBeamAnalyticGpu::matS2SolveStepTilt,
                        nGpu, frGpu, qGpu, bond, boundSeg, paGpu, sGpu, oGpu, fdfGpu, fmGpu, matc, counts, convId, chiGpu, rcGpu)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, nGpu, qGpu, oGpu, fdfGpu, fmGpu, chiGpu);
            WorkerGrid wg = new WorkerGrid1D(K); wg.setLocalWork(Math.min(64, K), 1, 1);
            GridScheduler gs = new GridScheduler("mats2tilt.s2tilt", wg);
            TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(gs);
            long t0 = System.nanoTime(); plan.execute(); gpuMs = (System.nanoTime() - t0) / 1e6;
            lowered = true;
        } catch (Throwable e) {
            lowered = false; Throwable r = e; while (r.getCause() != null && r.getCause() != r) r = r.getCause();
            err = r.getClass().getName() + ": " + oneLine(r.getMessage());
        }

        if (!lowered) {
            log.append("- **LOWERS: NO**\n- `" + err + "`\n\n"
                     + "**Verdict: the chi-dynamic head stack cannot go on the device as written.** Permitted responses are a\n"
                     + "storage/expression refactor ONLY (do NOT change the equations), or accepting that the site-normal\n"
                     + "assay stays on the CPU sequential runner. The other four kernels of the port were NOT attempted —\n"
                     + "this probe exists so that decision costs an afternoon rather than a port.\n");
            System.out.println("  LOWERING: **NO** — " + err);
            return false;
        }
        int nanG = 0; for (int i = 0; i < 9 * K; i++) if (!Double.isFinite(oGpu.get(i))) nanG++;
        log.append(String.format(Locale.US,
                "- **LOWERS + EXECUTES: YES** (%.1f ms build+exec)\n- non-finite values in outGeom: **%d**\n\n", gpuMs, nanG));
        System.out.printf(Locale.US, "  LOWERING: YES (%.1f ms build+exec), non-finite outGeom=%d%n", gpuMs, nanG);

        // ---- §2 CPU/GPU one-step parity ----------------------------------------------------------------
        log.append("## 2. One-step CPU/GPU parity (same kernel, two runners)\n\n");
        log.append("| motor | shape | chi_in (rad) | maxD node (um) | D phi | D psi | D chi | D forceDotFil | D forceMag |\n");
        log.append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        double mxNode = 0, mxAng = 0;
        for (int m = 0; m < K; m++) {
            double dN = 0;
            for (int j = 1; j <= M; j++) for (int k = 0; k < 3; k++)
                dN = Math.max(dN, Math.abs(nGpu.get((3 * j + k) * K + m) - nMir.get((3 * j + k) * K + m)));
            double dPhi = Math.abs(qGpu.get(m) - qMir.get(m));
            double dPsi = Math.abs(qGpu.get(K + m) - qMir.get(K + m));
            double dChi = Math.abs(chiGpu.get(m) - chiMir.get(m));
            double dFdf = Math.abs(fdfGpu.get(m) - fdfMir.get(m)), dFm = Math.abs(fmGpu.get(m) - fmMir.get(m));
            mxNode = Math.max(mxNode, dN); mxAng = Math.max(mxAng, Math.max(dPhi, Math.max(dPsi, dChi)));
            log.append(String.format(Locale.US, "| %d | %s | %+.3f | %.2e | %.2e | %.2e | %.2e | %.2e | %.2e |\n",
                    m, label[m], chiHead.get(m), dN, dPhi, dPsi, dChi, dFdf, dFm));
        }
        boolean faithful = mxNode < 1e-6 && mxAng < 1e-6 && nanG == 0;
        log.append(String.format(Locale.US,
                "\n- MAX GPU-vs-CPU: node **%.2e um**, angle **%.2e rad**\n"
              + "- reference scale: the non-tilt gate reports ~1e-9 um for the same comparison.\n"
              + "- **VERDICT: %s**\n", mxNode, mxAng, faithful ? "PASS" : "REVIEW"));
        System.out.printf(Locale.US, "  PARITY: maxDnode=%.2e um | maxDangle=%.2e rad => %s%n",
                mxNode, mxAng, faithful ? "PASS" : "REVIEW");
        log.append("\n> Scope: this probe wires NOTHING into buildGlidingGraph and changes no equation. A PASS derisks the\n"
                 + "> largest of the five kernels the site-normal device port needs; the remaining four are a separate task.\n");
        return faithful;
    }

    static void perturb(Glide2D G, int m, int kind) {
        int M = G.g4M; double[][] nd = G.g4Node[m];
        switch (kind) {
            case 1 -> { for (int j = 1; j < M; j++) for (int k = 0; k < 3; k++) nd[j][k] += 0.002 * G.bhat[k]; }
            case 2 -> { int jm = M / 2; for (int k = 0; k < 3; k++) nd[jm][k] += 0.004 * G.eup[k]; }
            case 3 -> { for (int j = 1; j < M; j++) for (int k = 0; k < 3; k++) nd[j][k] = nd[0][k] + (nd[M][k] - nd[0][k]) * j / (double) M; }
            case 4 -> { G.thetaS[m] = TwoBodyConverterMotor.ADP_THETAS; G.phi[m] += 0.1; G.psi[m] += 0.1; }
            default -> { }
        }
    }
    static int nearestSegSafe(Glide2D G, int m) { try { TwoBodyConverterMotor.geom2D(G, m); return TwoBodyConverterMotor.nearestSeg2D(G, m); } catch (Throwable e) { return 0; } }
    static double[] frameArr(Glide2D G, int m) { return new double[]{ G.bhat[0], G.bhat[1], G.bhat[2], G.econv[0], G.econv[1], G.econv[2],
        G.eup[0], G.eup[1], G.eup[2], G.g4E[m][0], G.g4E[m][1], G.g4E[m][2], G.g4Tan[0], G.g4Tan[1], G.g4Tan[2] }; }
    static double leverRest0(Glide2D G, int m) {
        return (ExplicitCompleteMatHarness.leverJointOn() && G.leverRest0 != null) ? G.leverRest0[m] : -1.0;
    }
    static double[] paramArr(Glide2D G) { return new double[]{ G.lb, G.rF8[0], G.rF8[1], G.rConv[0], G.rConv[1],
        G.kF8Code, G.kconvCode, G.kbindCode, G.gammaPhi, G.gammaPsi, G.dt, G.g4ks, G.g4l0, G.g4kb, G.g4floorZ, G.g4kfloor, G.g4gammaNode }; }
    static DoubleArray copy(DoubleArray a) { DoubleArray c = new DoubleArray(a.getSize()); for (int i = 0; i < a.getSize(); i++) c.set(i, a.get(i)); return c; }
    static String oneLine(String s) { return s == null ? "(none)" : s.replaceAll("\\s+", " ").trim(); }
}
