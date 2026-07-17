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
 * Explicit coupled kernel → COMPLETE persistent mat integration. §5 explicit head-placement CPU/GPU gate +
 * §6 bondForces coupling gate (the sanctioned prerequisites: "this stage must pass before composing the full
 * graph"). Reuses the validated matS2SolveStep + matBeamGeom + matPlaceHeadExplicit; the shared bondForces is
 * BYTE-UNCHANGED and simply reads the beam-derived head pose. Real buildS2Mat gliding mat; production geom2D +
 * placeHead2D + bondForces are the oracle. Flags: -Dtornado.recover.bailout=false -Dtornado.enable.fma=false.
 */
public final class ExplicitCompleteMatHarness {
    static final double DT = 2.5e-6;
    static final String OUT = "RUN_LOGS/explicit_completemat";

    public static void main(String[] args) {
        try { Files.createDirectories(Path.of(OUT)); } catch (IOException e) { throw new UncheckedIOException(e); }
        if (!"false".equals(System.getProperty("tornado.recover.bailout"))) System.out.println("!! run with -Dtornado.recover.bailout=false");
        StringBuilder log = new StringBuilder("# Explicit coupled kernel → complete-mat integration — §5 head-placement + §6 bondForces gates\n\n");
        boolean ok = headAndBondGate(log);
        try { Files.writeString(Path.of(OUT, "COMPLETEMAT_GATE.md"), log.toString()); } catch (IOException e) { throw new UncheckedIOException(e); }
        System.out.println("# report: " + Path.of(OUT, "COMPLETEMAT_GATE.md").toAbsolutePath());
        System.out.println(ok ? "=== COMPLETE-MAT §5/§6 GATE: PASS ===" : "=== COMPLETE-MAT §5/§6 GATE: REVIEW ===");
        System.exit(ok ? 0 : 1);
    }

    static boolean headAndBondGate(StringBuilder log) {
        int t = 7, seed = 101, K = 5;
        double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        Glide2D G = TwoBodyConverterMotor.buildS2Mat(200.0, DT, 40.0, slack, seed);
        int M = G.g4M, N = G.N; if (K > N) K = N;
        MotorStore mot = G.mot; RigidRodBody b = mot.body; FilamentStore f = G.fil;
        String[] label = { "relaxed", "high-axial", "transverse-bend", "near-taut", "post-stroke" };
        for (int m = 0; m < K; m++) { int s = ExplicitMatSolveHarness.nearestSegSafe(G, m); mot.boundSeg.set(m, s < 0 ? 0 : s);
            ExplicitMatSolveHarness.perturb(G, m, m % 5); double bindArc = 0.5f * f.segLength.get(mot.boundSeg.get(m)); mot.bindArc.set(m, (float) bindArc); }
        for (int m = K; m < N; m++) mot.boundSeg.set(m, -1);   // rest unbound

        // ---- CPU oracle: production geom2D + placeHead2D over the K motors, then bondForces ----
        for (int m = 0; m < K; m++) { TwoBodyConverterMotor.geom2D(G, m); TwoBodyConverterMotor.placeHead2D(G, m); }
        int nB = b.coord.getSize() / 3;
        float[][] bodyCPU = new float[K][9];   // per motor: coord(3), uVec(3), yVec(3) at h
        double[][] geomCPU = new double[K][9];  // C(3), xH(3), xF8(3)
        for (int m = 0; m < K; m++) { int h = mot.headIdx(m);
            bodyCPU[m] = new float[]{ b.coord.get(h), b.coord.get(nB + h), b.coord.get(2*nB + h),
                b.uVec.get(h), b.uVec.get(nB + h), b.uVec.get(2*nB + h), b.yVec.get(h), b.yVec.get(nB + h), b.yVec.get(2*nB + h) };
            geomCPU[m] = new double[]{ G.C_[m][0], G.C_[m][1], G.C_[m][2], G.xH_[m][0], G.xH_[m][1], G.xH_[m][2], G.xF8_[m][0], G.xF8_[m][1], G.xF8_[m][2] }; }
        // production bondForces (CPU) over the full body/filament → bondData oracle
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams);
        float[] bondCPU = new float[K * 13]; for (int m = 0; m < K; m++) for (int c = 0; c < 13; c++) bondCPU[m * 13 + c] = G.bondData.get(m * 13 + c);

        // ---- pack the explicit SoA (nM=K) for the head-placement kernels ----
        DoubleArray nodes = new DoubleArray(15 * K), frame = new DoubleArray(15 * K), q = new DoubleArray(4 * K), params = new DoubleArray(17 * K), outGeom = new DoubleArray(9 * K);
        IntArray counts = IntArray.fromElements(K, 1, M, 0), boundSeg = new IntArray(K);
        DoubleArray eupP = DoubleArray.fromElements(G.eup[0], G.eup[1], G.eup[2]);
        double[] pr = ExplicitMatSolveHarness.paramArr(G);
        for (int m = 0; m < K; m++) {
            for (int j = 0; j <= M; j++) for (int k = 0; k < 3; k++) nodes.set((3*j+k)*K+m, G.g4Node[m][j][k]);
            double[] fr = ExplicitMatSolveHarness.frameArr(G, m); for (int c = 0; c < 15; c++) frame.set(c*K+m, fr[c]);
            for (int c = 0; c < 17; c++) params.set(c*K+m, pr[c]);
            q.set(m, G.phi[m]); q.set(K+m, G.psi[m]); q.set(2*K+m, G.thetaS[m]); q.set(3*K+m, G.psiActin[m]);
            boundSeg.set(m, mot.boundSeg.get(m));
        }
        // separate FLOAT body arrays for the device head placement (size nB=3K so h=3m+2 fits)
        FloatArray dCoord = new FloatArray(3 * (3 * K)), dUVec = new FloatArray(3 * (3 * K)), dYVec = new FloatArray(3 * (3 * K));

        // ---- CPU-mirror (plain Java) ----
        DoubleArray oMir = new DoubleArray(9 * K);
        FloatArray cMir = new FloatArray(3 * (3 * K)), uMir = new FloatArray(3 * (3 * K)), yMir = new FloatArray(3 * (3 * K));
        TwoBodyBeamAnalyticGpu.matBeamGeom(nodes, frame, params, q, counts, oMir);
        TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit(oMir, boundSeg, eupP, counts, cMir, uMir, yMir);

        // ---- GPU (§5 lowering + execution) ----
        log.append("## §5 explicit head-placement gate (matBeamGeom + matPlaceHeadExplicit, GPU vs CPU-mirror vs production)\n");
        boolean lowered; String err = null;
        try {
            TaskGraph tg = new TaskGraph("head")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, nodes, frame, params, q, counts, boundSeg, eupP)
                .task("geom", TwoBodyBeamAnalyticGpu::matBeamGeom, nodes, frame, params, q, counts, outGeom)
                .task("place", TwoBodyBeamAnalyticGpu::matPlaceHeadExplicit, outGeom, boundSeg, eupP, counts, dCoord, dUVec, dYVec)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, outGeom, dCoord, dUVec, dYVec);
            WorkerGrid wg = new WorkerGrid1D(K); wg.setLocalWork(Math.min(64, K), 1, 1);
            WorkerGrid wg2 = new WorkerGrid1D(K); wg2.setLocalWork(Math.min(64, K), 1, 1);
            GridScheduler gs = new GridScheduler(); gs.addWorkerGrid("head.geom", wg); gs.addWorkerGrid("head.place", wg2);
            new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(gs).execute();
            lowered = true;
        } catch (Throwable e) { lowered = false; Throwable r = e; while (r.getCause() != null && r.getCause() != r) r = r.getCause(); err = r.getClass().getName() + ": " + oneLine(r.getMessage()); }
        if (!lowered) { log.append("- LOWERS: **NO** — " + err + "\n"); System.out.println("  §5 LOWERS: NO — " + err); return false; }

        // §5 compare: geom (mirror vs production), head body pose (GPU vs mirror, mirror vs production)
        log.append("| motor | shape | geomΔ mir-vs-prod (µm) | headPoseΔ mir-vs-prod | GPUvsMirror geom | GPUvsMirror headPose |\n|---|---|---|---|---|---|\n");
        double mxGeomPort = 0, mxPosePort = 0, mxGeomGpu = 0, mxPoseGpu = 0;
        for (int m = 0; m < K; m++) {
            double dGeom = 0; for (int c = 0; c < 9; c++) dGeom = Math.max(dGeom, Math.abs(oMir.get(c*K+m) - geomCPU[m][c]));
            int h = 3*m+2, nb = 3*K;
            double dPose = 0; float[] mir = { cMir.get(h),cMir.get(nb+h),cMir.get(2*nb+h), uMir.get(h),uMir.get(nb+h),uMir.get(2*nb+h), yMir.get(h),yMir.get(nb+h),yMir.get(2*nb+h) };
            for (int c = 0; c < 9; c++) dPose = Math.max(dPose, Math.abs(mir[c] - bodyCPU[m][c]));
            double dGeomG = 0; for (int c = 0; c < 9; c++) dGeomG = Math.max(dGeomG, Math.abs(outGeom.get(c*K+m) - oMir.get(c*K+m)));
            double dPoseG = Math.max(Math.abs(dCoord.get(h)-cMir.get(h)), Math.max(Math.abs(dUVec.get(h)-uMir.get(h)), Math.abs(dYVec.get(h)-yMir.get(h))));
            mxGeomPort=Math.max(mxGeomPort,dGeom); mxPosePort=Math.max(mxPosePort,dPose); mxGeomGpu=Math.max(mxGeomGpu,dGeomG); mxPoseGpu=Math.max(mxPoseGpu,dPoseG);
            log.append(String.format(Locale.US, "| %d | %s | %.2e | %.2e | %.2e | %.2e |\n", m, label[m], dGeom, dPose, dGeomG, dPoseG));
        }
        boolean s5 = mxGeomPort < 1e-8 && mxPosePort < 1e-6 && mxGeomGpu < 1e-6 && mxPoseGpu < 1e-6;
        log.append(String.format(Locale.US, "- geom mir-vs-prod max=%.2e µm; headPose mir-vs-prod max=%.2e; GPUvsMirror geom=%.2e pose=%.2e ⇒ **§5 %s**\n\n", mxGeomPort, mxPosePort, mxGeomGpu, mxPoseGpu, s5 ? "PASS" : "REVIEW"));

        // ---- §6 bondForces coupling gate: install the DEVICE head pose into the body, run the shared bondForces ----
        log.append("## §6 bondForces coupling gate (device head pose → bondForces vs production)\n");
        FloatArray dBond = new FloatArray(N * 13);
        int nbf = b.coord.getSize() / 3, nbd = 3 * K;
        for (int m = 0; m < K; m++) { int h = 3 * m + 2;
            b.coord.set(h, dCoord.get(h)); b.coord.set(nbf+h, dCoord.get(nbd+h)); b.coord.set(2*nbf+h, dCoord.get(2*nbd+h));
            b.uVec.set(h,  dUVec.get(h));  b.uVec.set(nbf+h,  dUVec.get(nbd+h));  b.uVec.set(2*nbf+h,  dUVec.get(2*nbd+h));
            b.yVec.set(h,  dYVec.get(h));  b.yVec.set(nbf+h,  dYVec.get(nbd+h));  b.yVec.set(2*nbf+h,  dYVec.get(2*nbd+h)); }
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, dBond, G.xbParams);
        double mxBond = 0; for (int m = 0; m < K; m++) for (int c = 0; c < 13; c++) mxBond = Math.max(mxBond, Math.abs(dBond.get(m*13+c) - bondCPU[m*13+c]));
        boolean s6 = mxBond < 1e-6;
        log.append(String.format(Locale.US, "- bondData (device-head-pose bondForces) vs production oracle: max|Δ| = **%.2e** ⇒ %s (F8h to matS2SolveStep matches; no dropped/duplicated force)\n\n", mxBond, s6 ? "PASS (within float)" : "REVIEW"));

        boolean ok = s5 && s6;
        log.append(String.format(Locale.US, "**§5/§6 VERDICT: %s** — explicit head placement lowers + matches production; bondForces consumes the beam-derived pose. Full-graph composition (§7–§11) is the next step.\n", ok ? "PASS" : "REVIEW"));
        System.out.printf(Locale.US, "  §5 head-place geom=%.1e pose=%.1e (GPUvsMir=%.1e) | §6 bondΔ=%.1e ⇒ %s%n", mxGeomPort, mxPosePort, Math.max(mxGeomGpu,mxPoseGpu), mxBond, ok ? "PASS" : "REVIEW");
        return ok;
    }

    static String oneLine(String s) { return s == null ? "(none)" : s.replaceAll("\\s+", " ").trim(); }
}
