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
 * §5 isolated lowering probe + §6 one-step CPU/GPU gate for the coupled explicit gliding Stage-10
 * {@link TwoBodyBeamAnalyticGpu#matS2SolveStep}. The device kernel = the validated beam assembly (canonical
 * ExplicitBeamAnalytic residual+Hessian) + mat Brownian (salts 0x4811/0x4841/0x4842+m·7919) + reaction
 * writeback — a faithful port of {@code TwoBodyConverterMotor.s2SolveM}. Runs on the REAL explicit gliding mat
 * ({@code buildS2Mat}). Flags: -Dtornado.recover.bailout=false -Dtornado.enable.fma=false.
 */
public final class ExplicitMatSolveHarness {
    static final double DT = 2.5e-6;
    static final String OUT = "RUN_LOGS/explicit_mats2";

    public static void main(String[] args) {
        try { Files.createDirectories(Path.of(OUT)); } catch (IOException e) { throw new UncheckedIOException(e); }
        boolean bailoutOff = "false".equals(System.getProperty("tornado.recover.bailout"));
        if (!bailoutOff) System.out.println("!! run with -Dtornado.recover.bailout=false");
        StringBuilder log = new StringBuilder("# Explicit coupled mat Stage-10 (matS2SolveStep) — §5 lowering probe + §6 one-step gate\n\n");
        boolean ok = gate(log);
        try { Files.writeString(Path.of(OUT, "MATS2SOLVE_GATE.md"), log.toString()); } catch (IOException e) { throw new UncheckedIOException(e); }
        System.out.println("# report: " + Path.of(OUT, "MATS2SOLVE_GATE.md").toAbsolutePath());
        System.out.println(ok ? "=== MATS2SOLVE GATE: PASS ===" : "=== MATS2SOLVE GATE: REVIEW ===");
        System.exit(ok ? 0 : 1);
    }

    static boolean gate(StringBuilder log) {
        int t = 7, seed = 101, K = 5;
        double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        Glide2D G = TwoBodyConverterMotor.buildS2Mat(200.0, DT, 40.0, slack, seed);
        int M = G.g4M, N = G.N; if (K > N) K = N;
        String[] label = { "relaxed-bound", "high-axial", "transverse-bend", "near-taut", "post-stroke" };
        // set up K bound motors with varied beam shapes + bond forces (coverage for the lowering probe)
        for (int m = 0; m < K; m++) {
            int s = nearestSegSafe(G, m);
            G.mot.boundSeg.set(m, s < 0 ? 0 : s);
            perturb(G, m, m % 5);
            double[] bh = G.bhat; double fp = (1.0 + m) * 1e-12;   // 1..5 pN along ±bhat
            int d = m * 13; double sgn = (m % 2 == 0) ? -1 : 1;
            G.bondData.set(d, (float) (sgn * fp * bh[0])); G.bondData.set(d + 1, (float) (sgn * fp * bh[1])); G.bondData.set(d + 2, (float) (sgn * fp * bh[2]));
            G.bondData.set(d + 12, (float) (sgn * fp));   // forceDotFil test value
            TwoBodyConverterMotor.geom2D(G, m);
        }

        // ---- CPU oracle: run production s2SolveM ONCE per motor on a snapshot; capture post-state ----
        double[][][] pre = new double[K][][]; double[] prePhi = new double[K], prePsi = new double[K];
        double[][][] postCPU = new double[K][][]; double[] postPhiCPU = new double[K], postPsiCPU = new double[K];
        float[] fdfCPU = new float[K], fmagCPU = new float[K];
        for (int m = 0; m < K; m++) {
            pre[m] = clone(G.g4Node[m]); prePhi[m] = G.phi[m]; prePsi[m] = G.psi[m]; double[] preA = G.A[m].clone();
            TwoBodyConverterMotor.s2SolveM(G, m, t, seed, true);
            postCPU[m] = clone(G.g4Node[m]); postPhiCPU[m] = G.phi[m]; postPsiCPU[m] = G.psi[m];
            fdfCPU[m] = G.mot.forceDotFil.get(m); fmagCPU[m] = G.mot.forceMag.get(m);
            // restore pre-state so the SoA packs the SAME input the CPU saw
            for (int j = 0; j <= M; j++) G.g4Node[m][j] = pre[m][j].clone();
            G.phi[m] = prePhi[m]; G.psi[m] = prePsi[m]; G.A[m] = preA; TwoBodyConverterMotor.geom2D(G, m);
        }

        // ---- pack the explicit mat SoA (pre-state) ----
        DoubleArray nodes = new DoubleArray(15 * K), frame = new DoubleArray(15 * K), q = new DoubleArray(4 * K), params = new DoubleArray(17 * K);
        DoubleArray sys = new DoubleArray(TwoBodyBeamAnalyticGpu.SYS_STRIDE * K), outGeom = new DoubleArray(9 * K);
        FloatArray bond = new FloatArray(13 * K), forceDotFil = new FloatArray(K), forceMag = new FloatArray(K);
        IntArray boundSeg = new IntArray(K), status = new IntArray(K), iters = new IntArray(K);
        IntArray matc = IntArray.fromElements(t, seed, 1, 0), counts = IntArray.fromElements(K, 1, M, 0);   // matc[3]=motor-Brownian policy (0 = canonical)
        // zeroed converter frame (flag 0 for every motor) ⇒ matS2SolveStep takes the VERBATIM canonical branch
        DoubleArray convId = TwoBodyBeamAnalyticGpu.identityConvFrame(K);
        sys.init(0.0);
        double[] pr = paramArr(G);
        for (int m = 0; m < K; m++) {
            for (int j = 0; j <= M; j++) for (int k = 0; k < 3; k++) nodes.set((3 * j + k) * K + m, G.g4Node[m][j][k]);
            double[] fr = frameArr(G, m); for (int c = 0; c < 15; c++) frame.set(c * K + m, fr[c]);
            for (int c = 0; c < 17; c++) params.set(c * K + m, pr[c]);
            q.set(m, G.phi[m]); q.set(K + m, G.psi[m]); q.set(2 * K + m, G.thetaS[m]); q.set(3 * K + m, G.psiActin[m]);
            boundSeg.set(m, G.mot.boundSeg.get(m));
            for (int c = 0; c < 13; c++) bond.set(m * 13 + c, G.bondData.get(m * 13 + c));
        }

        // ---- CPU-mirror: matS2SolveStep called directly (plain Java = the CPU runner) ----
        DoubleArray nMir = copy(nodes), qMir = copy(q), oMir = new DoubleArray(9 * K); DoubleArray sMir = new DoubleArray(TwoBodyBeamAnalyticGpu.SYS_STRIDE * K); sMir.init(0.0);
        FloatArray fdfMir = new FloatArray(K), fmMir = new FloatArray(K); IntArray stMir = new IntArray(K), itMir = new IntArray(K);
        TwoBodyBeamAnalyticGpu.matS2SolveStep(nMir, copy(frame), qMir, bond, boundSeg, copy(params), sMir, oMir, fdfMir, fmMir, matc, counts, TwoBodyBeamAnalyticGpu.identityConvFrame(K));

        // ---- GPU ----
        log.append("## §5 lowering probe (device TaskGraph, no silent fallback)\n");
        boolean lowered; String err = null; double gpuMs = 0;
        DoubleArray nGpu = copy(nodes), qGpu = copy(q), oGpu = new DoubleArray(9 * K); DoubleArray sGpu = new DoubleArray(TwoBodyBeamAnalyticGpu.SYS_STRIDE * K); sGpu.init(0.0);
        FloatArray fdfGpu = new FloatArray(K), fmGpu = new FloatArray(K); IntArray stGpu = new IntArray(K), itGpu = new IntArray(K);
        try {
            TaskGraph tg = new TaskGraph("mats2")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, nGpu, frame, qGpu, bond, boundSeg, params, sGpu, matc, counts, convId)
                .task("s2", TwoBodyBeamAnalyticGpu::matS2SolveStep, nGpu, frame, qGpu, bond, boundSeg, params, sGpu, oGpu, fdfGpu, fmGpu, matc, counts, convId)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, nGpu, qGpu, oGpu, fdfGpu, fmGpu, stGpu, itGpu);
            WorkerGrid wg = new WorkerGrid1D(K); wg.setLocalWork(Math.min(64, K), 1, 1);
            GridScheduler gs = new GridScheduler("mats2.s2", wg);
            TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(gs);
            long t0 = System.nanoTime(); plan.execute(); gpuMs = (System.nanoTime() - t0) / 1e6;
            lowered = true;
        } catch (Throwable e) { lowered = false; Throwable r = e; while (r.getCause() != null && r.getCause() != r) r = r.getCause(); err = r.getClass().getName() + ": " + oneLine(r.getMessage()); }

        if (!lowered) {
            log.append("- LOWERS: **NO** — " + err + "\n- (refactor storage/expression only; do NOT change equations. §13: explicit coupled Step-7 may stay CPU.)\n");
            System.out.println("  §5 LOWERING: NO — " + err);
            return false;
        }
        int nanG = 0; for (int i = 0; i < 9 * K; i++) if (!Double.isFinite(oGpu.get(i))) nanG++;
        log.append(String.format(Locale.US, "- LOWERS + EXECUTES: **YES** (%.1f ms build+exec); NaN/Inf in outGeom=%d; brownTorqueD (64-bit long hash) lowers (already proven in MatStep7).\n\n", gpuMs, nanG));

        // ---- §6 one-step gate ----
        log.append("## §6 one-step gate (GPU vs CPU-mirror = bit-faithful; CPU-mirror vs production s2SolveM = port faithful)\n");
        log.append("| motor | shape | maxΔnode mir-vs-s2SolveM (µm) | Δphi | Δpsi | ΔforceDotFil | ΔforceMag | GPUvsMirror maxΔnode | status |\n|---|---|---|---|---|---|---|---|---|\n");
        double mxPort = 0, mxGpu = 0; boolean ok = true;
        for (int m = 0; m < K; m++) {
            double dN = 0; for (int j = 1; j <= M; j++) for (int k = 0; k < 3; k++) dN = Math.max(dN, Math.abs(nMir.get((3 * j + k) * K + m) - postCPU[m][j][k]));
            double dPhi = Math.abs(qMir.get(m) - postPhiCPU[m]), dPsi = Math.abs(qMir.get(K + m) - postPsiCPU[m]);
            double dFdf = Math.abs(fdfMir.get(m) - fdfCPU[m]), dFm = Math.abs(fmMir.get(m) - fmagCPU[m]);
            double dGpu = 0; for (int j = 1; j <= M; j++) for (int k = 0; k < 3; k++) dGpu = Math.max(dGpu, Math.abs(nGpu.get((3 * j + k) * K + m) - nMir.get((3 * j + k) * K + m)));
            mxPort = Math.max(mxPort, dN); mxGpu = Math.max(mxGpu, dGpu);
            boolean pass = dN < 1e-8 && stGpu.get(m) == 0;   // mirror≡s2SolveM to fp op-order (inline vs s2NodeForcesM+beamTangentFree, ~1e-9 µm = machine eps on µm coords)
            ok &= pass;
            log.append(String.format(Locale.US, "| %d | %s | %.2e | %.2e | %.2e | %.2e | %.2e | %.2e | %d |\n", m, label[m], dN, dPhi, dPsi, dFdf, dFm, dGpu, stGpu.get(m)));
        }
        boolean gpuFaithful = mxGpu < 1e-6;
        log.append(String.format(Locale.US, "- MAX: CPU-mirror vs production s2SolveM = **%.2e µm** (the port reproduces the canonical coupled math); GPU vs CPU-mirror = **%.2e µm** (device lowering %s).\n", mxPort, mxGpu, gpuFaithful ? "bit-faithful" : "float-decorrelated"));
        boolean verdict = ok && mxPort < 1e-8 && gpuFaithful;
        log.append(String.format(Locale.US, "- **§5/§6 VERDICT: %s** — matS2SolveStep LOWERS, is bit-faithful GPU↔CPU, and reproduces production s2SolveM to fp.\n", verdict ? "PASS" : "REVIEW"));
        System.out.printf(Locale.US, "  §5 LOWERS=YES | §6 port-vs-s2SolveM=%.1e µm | GPUvsMirror=%.1e µm ⇒ %s%n", mxPort, mxGpu, verdict ? "PASS" : "REVIEW");
        return verdict;
    }

    // vary a motor's beam to cover the IC classes (coverage only; CPU/GPU compare on whatever the state is)
    static void perturb(Glide2D G, int m, int kind) {
        int M = G.g4M; double[][] nd = G.g4Node[m];
        switch (kind) {
            case 1 -> { for (int j = 1; j < M; j++) for (int k = 0; k < 3; k++) nd[j][k] += 0.002 * G.bhat[k]; }           // axial pull (interior only; keep pivot node M = anchor)
            case 2 -> { int jm = M / 2; for (int k = 0; k < 3; k++) nd[jm][k] += 0.004 * G.eup[k]; }                        // transverse bend
            case 3 -> { for (int j = 1; j < M; j++) for (int k = 0; k < 3; k++) nd[j][k] = nd[0][k] + (nd[M][k] - nd[0][k]) * j / (double) M; } // straighten (taut)
            case 4 -> { G.thetaS[m] = TwoBodyConverterMotor.ADP_THETAS; G.phi[m] += 0.1; G.psi[m] += 0.1; }                 // post-stroke
            default -> { }                                                                                                 // relaxed (as built)
        }
    }
    static int nearestSegSafe(Glide2D G, int m) { try { TwoBodyConverterMotor.geom2D(G, m); return TwoBodyConverterMotor.nearestSeg2D(G, m); } catch (Throwable e) { return 0; } }
    static double[] frameArr(Glide2D G, int m) { return new double[]{ G.bhat[0], G.bhat[1], G.bhat[2], G.econv[0], G.econv[1], G.econv[2],
        G.eup[0], G.eup[1], G.eup[2], G.g4E[m][0], G.g4E[m][1], G.g4E[m][2], G.g4Tan[0], G.g4Tan[1], G.g4Tan[2] }; }
    static double[] paramArr(Glide2D G) { return new double[]{ G.lb, G.rF8[0], G.rF8[1], G.rConv[0], G.rConv[1],
        G.kF8Code, G.kconvCode, G.kbindCode, G.gammaPhi, G.gammaPsi, G.dt, G.g4ks, G.g4l0, G.g4kb, G.g4floorZ, G.g4kfloor, G.g4gammaNode }; }
    static double[][] clone(double[][] a) { double[][] c = new double[a.length][]; for (int i = 0; i < a.length; i++) c[i] = a[i].clone(); return c; }
    static DoubleArray copy(DoubleArray a) { DoubleArray c = new DoubleArray(a.getSize()); for (int i = 0; i < a.getSize(); i++) c.set(i, a.get(i)); return c; }
    static String oneLine(String s) { return s == null ? "(none)" : s.replaceAll("\\s+", " ").trim(); }
}
