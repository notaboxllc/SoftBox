package softbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * FIRST device-resident motor-mat SoA VERTICAL SLICE (calibrated-only, experimental).
 *
 * <p>Builds the calibrated gliding mat (`buildSupMat`) as the host oracle and validates each new device
 * kernel in ISOLATION (Part 5): identical flat SoA in → host stage (the FROZEN semantics in
 * `TwoBodyConverterMotor`) + GPU stage → compare every changed field, verify unrelated fields unchanged.
 * All GPU runs require `-Dtornado.recover.bailout=false` (a lowering failure throws — no silent CPU
 * fallback). Motor SoA stays device-resident; only compact filament state + scalar controls + reduced
 * outputs cross the bus. Does NOT flip {@code MotorGpuParams.DEVICE_VALIDATED} (experimental).
 *
 * <p>Stage 1 (matCull) implemented + gated here. Cull distance is computed in DOUBLE to match the host
 * `siteSegDist2` bit-for-decision (the active SET must be identical). The grid acceleration
 * (`initMatGrid`) is a pure optimization — the per-motor brute union gather here yields the IDENTICAL
 * active set (cullMode=1 grid ≡ cullMode=2 brute), and is race-free (per-motor gather, no scatter).
 */
public final class MatSoaSlice {
    private MatSoaSlice() {}

    static final String OUTDIR = "RUN_LOGS/matsoa";

    // ===============================================================================================
    // KERNEL — Stage 1: matCull.  active[m] = (boundSeg[m] >= 0) OR (site_m within queryR of ANY segment).
    //   Reproduces unionActive (TwoBodyConverterMotor.L4721) as a race-free per-motor gather.
    //   site: DoubleArray planar 2N (x=[m], y=[N+m]) — the fixed ideal head site (host G.siteX/Y are double).
    //   filCoord/filUVec/filSegLen: FilamentStore FloatArrays (planar: X=[s], Y=[nSeg+s]).
    //   cullParams[0] = queryR^2 (double, = G.queryR^2). counts = {N, t, seed, nSeg}. active[N] written.
    // ===============================================================================================
    public static void matCull(IntArray boundSeg, DoubleArray site,
                               FloatArray filCoord, FloatArray filUVec, FloatArray filSegLen,
                               DoubleArray cullParams, IntArray counts, IntArray active) {
        int N = counts.get(0);
        int nSeg = counts.get(3);
        double qr2 = cullParams.get(0);
        for (@Parallel int m = 0; m < N; m++) {
            int a;
            if (boundSeg.get(m) >= 0) {
                a = 1;
            } else {
                double sx = site.get(m), sy = site.get(N + m);
                a = 0;
                for (int s = 0; s < nSeg; s++) {
                    double half = 0.5 * (double) filSegLen.get(s);
                    double cx = filCoord.get(s), cy = filCoord.get(nSeg + s);
                    double ux = filUVec.get(s), uy = filUVec.get(nSeg + s);
                    double dx = sx - cx, dy = sy - cy;
                    double foot = dx * ux + dy * uy;
                    foot = foot < -half ? -half : (foot > half ? half : foot);   // == Math.max(-half,Math.min(half,foot)), reinterpret-free
                    double px = dx - foot * ux, py = dy - foot * uy;
                    double d2 = px * px + py * py;
                    if (d2 <= qr2) a = 1;
                }
            }
            active.set(m, a);
        }
    }

    static double dabs(double x) { return x < 0 ? -x : x; }              // reinterpret-free |x| (Math.abs uses doubleToRawLongBits)
    static double deg(double x) { return x * 180.0 / Math.PI; }          // == JDK Math.toDegrees(angrad) = angrad*180.0/PI

    // ===============================================================================================
    // KERNEL — Stage 2: matGeomGate (DOUBLE, bit-for-decision).  geom2D → nearestSeg2D → gate2D → 8-gate AND.
    //   pose: phi=[m], psi=[N+m], psiActin=[2N+m].  anchor: planar 3N.
    //   filCoord/filUVec: planar 3*nSeg (X=[s],Y=[nSeg+s],Z=[2nSeg+s]).  filSegLen: [s].
    //   params: bhat(0..2) econv(3..5) eup(6..8) lb(9) rF8x(10) rF8y(11) rCx(12) rCy(13) FIL_R(14) A_SEMI2(15)
    //           kF8Code(16) kconvCode(17) kbindCode(18) PHI_PRE(19) kT(20) prestrokeThetaS(21)
    //           dBindNm(22) psiDeg(23) phiDeg(24) thetaDeg(25) preloadPn(26) energyKt(27) margin(28) nearMargin(29)
    //   geomOut: Cx=[m] Cy=[N+m] Cz=[2N+m] xF8x=[3N+m] xF8y=[4N+m] xF8z=[5N+m] xHx=[6N+m] xHy=[7N+m] xHz=[8N+m]
    //   candInt: candSeg=[m] (-1 if none) candAccept=[N+m] (0/1).  candBindArc: [m].
    // ===============================================================================================
    public static void matGeomGate(DoubleArray anchor, DoubleArray pose,
                                   FloatArray filCoord, FloatArray filUVec, FloatArray filSegLen,
                                   DoubleArray params, IntArray counts,
                                   DoubleArray geomOut, IntArray candInt, DoubleArray candBindArc) {
        int N = counts.get(0), nSeg = counts.get(3);
        double bx = params.get(0), by = params.get(1), bz = params.get(2);
        double ex = params.get(3), ey = params.get(4), ez = params.get(5);
        double ux0 = params.get(6), uy0 = params.get(7), uz0 = params.get(8);
        double lb = params.get(9), rF8x = params.get(10), rF8y = params.get(11), rCx = params.get(12), rCy = params.get(13);
        double FIL_R = params.get(14), A2 = params.get(15), kF8 = params.get(16), kc = params.get(17), kb = params.get(18);
        double PHI_PRE = params.get(19), kT = params.get(20), pth = params.get(21);
        double dBind = params.get(22), psiDeg = params.get(23), phiDeg = params.get(24), thetaDeg = params.get(25);
        double preloadPn = params.get(26), energyKt = params.get(27), margin = params.get(28), nearMargin = params.get(29);
        for (@Parallel int m = 0; m < N; m++) {
            double phi = pose.get(m), psi = pose.get(N + m), psiAct = pose.get(2 * N + m);
            double Ax = anchor.get(m), Ay = anchor.get(N + m), Az = anchor.get(2 * N + m);
            // --- geom2D ---
            double cphi = Math.cos(phi), sphi = Math.sin(phi);
            double uBx = ux0 * cphi + bx * sphi, uBy = uy0 * cphi + by * sphi, uBz = uz0 * cphi + bz * sphi;
            double Cx = Ax + uBx * lb, Cy = Ay + uBy * lb, Cz = Az + uBz * lb;
            double cpsi = Math.cos(psi), spsi = Math.sin(psi);
            double d0x = bx * (rF8x - rCx) + ux0 * (rF8y - rCy), d0y = by * (rF8x - rCx) + uy0 * (rF8y - rCy), d0z = bz * (rF8x - rCx) + uz0 * (rF8y - rCy);
            double xF8x = Cx + (d0x * cpsi + (ey * d0z - ez * d0y) * spsi);
            double xF8y = Cy + (d0y * cpsi + (ez * d0x - ex * d0z) * spsi);
            double xF8z = Cz + (d0z * cpsi + (ex * d0y - ey * d0x) * spsi);
            double r0x = bx * rCx + ux0 * rCy, r0y = by * rCx + uy0 * rCy, r0z = bz * rCx + uz0 * rCy;
            double xHx = Cx - (r0x * cpsi + (ey * r0z - ez * r0y) * spsi);
            double xHy = Cy - (r0y * cpsi + (ez * r0x - ex * r0z) * spsi);
            double xHz = Cz - (r0z * cpsi + (ex * r0y - ey * r0x) * spsi);
            geomOut.set(m, Cx); geomOut.set(N + m, Cy); geomOut.set(2 * N + m, Cz);
            geomOut.set(3 * N + m, xF8x); geomOut.set(4 * N + m, xF8y); geomOut.set(5 * N + m, xF8z);
            geomOut.set(6 * N + m, xHx); geomOut.set(7 * N + m, xHy); geomOut.set(8 * N + m, xHz);
            // --- nearestSeg2D over xF8 ---
            int best = -1; double bd = 1e9;
            for (int s = 0; s < nSeg; s++) {
                double half = 0.5 * (double) filSegLen.get(s);
                double cx = filCoord.get(s), cy = filCoord.get(nSeg + s), cz = filCoord.get(2 * nSeg + s);
                double su = filUVec.get(s), sv = filUVec.get(nSeg + s), sw = filUVec.get(2 * nSeg + s);
                double dx = xF8x - cx, dy = xF8y - cy, dz = xF8z - cz;
                double foot = dx * su + dy * sv + dz * sw;
                if (dabs(foot) > half + 0.02) continue;
                double px = dx - foot * su, py = dy - foot * sv, pz = dz - foot * sw;
                double d2 = px * px + py * py + pz * pz;
                if (d2 < bd) { bd = d2; best = s; }
            }
            int accept = 0; double bindArc = 0;
            if (best >= 0) {
                // --- gate2D on 'best' ---
                double half = 0.5 * (double) filSegLen.get(best);
                double cx = filCoord.get(best), cy = filCoord.get(nSeg + best), cz = filCoord.get(2 * nSeg + best);
                double su = filUVec.get(best), sv = filUVec.get(nSeg + best), sw = filUVec.get(2 * nSeg + best);
                double e1x = cx - half * su, e1y = cy - half * sv, e1z = cz - half * sw;
                double foot = (xF8x - cx) * su + (xF8y - cy) * sv + (xF8z - cz) * sw;
                double axx = cx + foot * su, axy = cy + foot * sv, axz = cz + foot * sw;
                double conDist = Math.sqrt((xF8x - axx) * (xF8x - axx) + (xF8y - axy) * (xF8y - axy) + (xF8z - axz) * (xF8z - axz));
                bindArc = (xF8x - e1x) * su + (xF8y - e1y) * sv + (xF8z - e1z) * sw;
                double surf = (conDist - FIL_R) * 1e3;
                double psiErr = deg(dabs(psi - psiAct));
                double phiErr = deg(dabs(phi - PHI_PRE));
                double th = (psi - phi) - pth;
                double thetaErr = deg(dabs(th));
                double preload = kF8 * conDist * 1e12;
                double eKt = (0.5 * kc * (th * th) + 0.5 * kb * ((psi - psiAct) * (psi - psiAct))) / kT;
                double headSide = ((xHx - cx) * ux0 + (xHy - cy) * uy0 + (xHz - cz) * uz0) * 1e3;
                boolean g0 = surf < dBind, g1 = psiErr < psiDeg, g2 = phiErr < phiDeg, g3 = thetaErr < thetaDeg;
                boolean g4 = preload < preloadPn, g5 = eKt < energyKt, g6 = headSide < A2 * 1e3, g7 = bindArc > margin && bindArc < 2 * half - margin;
                if (g0 && g1 && g2 && g3 && g4 && g5 && g6 && g7) accept = 1;
            }
            candInt.set(m, best); candInt.set(N + m, accept); candBindArc.set(m, bindArc);
        }
    }

    // ===============================================================================================
    public static void main(String[] args) {
        Path dir = Path.of(OUTDIR);
        try { Files.createDirectories(dir); } catch (IOException ex) { throw new UncheckedIOException(ex); }
        boolean bailoutOff = "false".equals(System.getProperty("tornado.recover.bailout"));
        if (!bailoutOff) System.out.println("!! WARNING: run with -Dtornado.recover.bailout=false — else a lowering failure SILENTLY falls back to CPU.");
        System.out.println("=== MAT-SOA VERTICAL SLICE (calibrated) — Part 5 isolated stage gates ===");
        StringBuilder log = new StringBuilder();
        log.append("# MAT-SOA VERTICAL SLICE — isolated stage gates (calibrated, RTX 5070 / PTX)\n");
        log.append("# bailout disabled: ").append(bailoutOff).append("  | device mem start ").append(gpuMemUsed()).append(" MiB\n\n");

        boolean cullOk = gateCull(log);
        boolean geomOk = gateGeomGate(log);

        try { Files.writeString(dir.resolve("STAGE_GATES.md"), log.toString()); } catch (IOException ex) { throw new UncheckedIOException(ex); }
        System.out.println("\n=== SLICE STATUS ===");
        System.out.printf(Locale.US, "Stage 1 matCull      (active-set identity):      %s%n", cullOk ? "PASS" : "FAIL");
        System.out.printf(Locale.US, "Stage 2 matGeomGate  (geom+nearest+gate identity): %s%n", geomOk ? "PASS" : "FAIL");
        System.out.println("# report: " + dir.resolve("STAGE_GATES.md").toAbsolutePath());
        System.exit(cullOk && geomOk ? 0 : 1);
    }

    // ---------- Part 5.2 — geom + nearest + gate IDENTITY ----------
    static boolean gateGeomGate(StringBuilder log) {
        System.out.println("\n--- Part 5.2: matGeomGate vs host geom2D+nearestSeg2D+gate2D ---");
        log.append("## Stage 2 — matGeomGate (geom + nearest-seg discrete selection + 8-gate identity)\n");
        double dt = 2.5e-6;
        boolean allPass = true;
        for (double density : new double[]{ 200, 700 }) {
            for (int seed : new int[]{ 11, 12, 13 }) {
                TwoBodyConverterMotor.Glide2D G = TwoBodyConverterMotor.buildSupMat(density, dt, 0.0, seed);
                int N = G.N, nSeg = G.nSeg; FilamentStore f = G.fil;
                // --- host oracle: geom2D → nearest → gate → 8-gate, with thetaS=PRESTROKE (the bind-block value) ---
                int[] hSeg = new int[N], hAcc = new int[N]; double[] hArc = new double[N];
                double[][] hC = new double[N][3], hF8 = new double[N][3], hH = new double[N][3];
                for (int m = 0; m < N; m++) {
                    G.thetaS[m] = TwoBodyConverterMotor.PRESTROKE_THETAS;
                    TwoBodyConverterMotor.geom2D(G, m);
                    hC[m] = G.C_[m].clone(); hF8[m] = G.xF8_[m].clone(); hH[m] = G.xH_[m].clone();
                    int s = TwoBodyConverterMotor.nearestSeg2D(G, m); hSeg[m] = s;
                    if (s >= 0) {
                        double[] gm = TwoBodyConverterMotor.gate2D(G, m, s); double half = 0.5 * f.segLength.get(s);
                        boolean g0 = gm[0] < 3.0, g1 = gm[2] < 25, g2 = gm[3] < 25, g3 = gm[4] < 20, g4 = gm[5] < 2.0, g5 = gm[6] < 15.0,
                                g6 = gm[7] < A_SEMI2 * 1e3, g7 = gm[1] > 0.05 && gm[1] < 2 * half - 0.05;
                        hAcc[m] = (g0 && g1 && g2 && g3 && g4 && g5 && g6 && g7) ? 1 : 0; hArc[m] = gm[1];
                    }
                }
                // --- pack device SoA ---
                DoubleArray anchor = new DoubleArray(3 * N), pose = new DoubleArray(3 * N);
                for (int m = 0; m < N; m++) {
                    anchor.set(m, G.A[m][0]); anchor.set(N + m, G.A[m][1]); anchor.set(2 * N + m, G.A[m][2]);
                    pose.set(m, G.phi[m]); pose.set(N + m, G.psi[m]); pose.set(2 * N + m, G.psiActin[m]);
                }
                DoubleArray params = packGeomGateParams(G);
                IntArray counts = new IntArray(4); counts.set(0, N); counts.set(1, 0); counts.set(2, seed); counts.set(3, nSeg);
                DoubleArray geomOut = new DoubleArray(9 * N); IntArray candInt = new IntArray(2 * N); DoubleArray candArc = new DoubleArray(N);
                long dev0 = System.nanoTime();
                try {
                    TaskGraph tg = new TaskGraph("geomgate")
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, anchor, pose, f.coord, f.uVec, f.segLength, params)
                            .transferToDevice(DataTransferMode.EVERY_EXECUTION, counts)
                            .task("matGeomGate", MatSoaSlice::matGeomGate, anchor, pose, f.coord, f.uVec, f.segLength, params, counts, geomOut, candInt, candArc)
                            .transferToHost(DataTransferMode.EVERY_EXECUTION, geomOut, candInt, candArc);
                    GridScheduler sched = new GridScheduler();
                    WorkerGrid w = new WorkerGrid1D(N); w.setLocalWork(1, 1, 1); sched.addWorkerGrid("geomgate.matGeomGate", w);
                    new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(sched).execute();
                } catch (Throwable ex) {
                    Throwable root = ex; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    System.out.printf(Locale.US, "  density=%.0f seed=%d N=%d: LOWERS=NO — %s: %s%n", density, seed, N, ex.getClass().getName(), oneLine(ex.getMessage()));
                    log.append(String.format(Locale.US, "- density=%.0f seed=%d N=%d: **LOWERS=NO** — `%s`: %s\n", density, seed, N, root.getClass().getName(), oneLine(root.getMessage())));
                    return false;
                }
                double devMs = (System.nanoTime() - dev0) / 1e6;
                // --- compare ---
                int segMis = 0, accMis = 0; double maxGeom = 0, maxArc = 0; int firstSeg = -1, firstAcc = -1;
                for (int m = 0; m < N; m++) {
                    if (candInt.get(m) != hSeg[m]) { segMis++; if (firstSeg < 0) firstSeg = m; }
                    if (candInt.get(N + m) != hAcc[m]) { accMis++; if (firstAcc < 0) firstAcc = m; }
                    maxGeom = Math.max(maxGeom, Math.abs(geomOut.get(m) - hC[m][0]));
                    maxGeom = Math.max(maxGeom, Math.abs(geomOut.get(3 * N + m) - hF8[m][0]));
                    maxGeom = Math.max(maxGeom, Math.abs(geomOut.get(4 * N + m) - hF8[m][1]));
                    maxGeom = Math.max(maxGeom, Math.abs(geomOut.get(6 * N + m) - hH[m][0]));
                    if (hSeg[m] >= 0) maxArc = Math.max(maxArc, Math.abs(candArc.get(m) - hArc[m]));
                }
                boolean pass = (segMis == 0 && accMis == 0);
                allPass &= pass;
                System.out.printf(Locale.US, "  density=%.0f seed=%d N=%d: segMism=%d acceptMism=%d maxGeomΔ=%.2e maxArcΔ=%.2e %s [%.1f ms]%n",
                        density, seed, N, segMis, accMis, maxGeom, maxArc, pass ? "PASS" : ("FAIL seg@" + firstSeg + " acc@" + firstAcc), devMs);
                log.append(String.format(Locale.US, "- density=%.0f seed=%d N=%d: segMism=%d acceptMism=%d maxGeomΔ=%.2e (double bit-for-decision) maxArcΔ=%.2e → %s\n",
                        density, seed, N, segMis, accMis, maxGeom, maxArc, pass ? "PASS" : "FAIL"));
            }
        }
        log.append("- Discrete identity (nearest-seg selection + 8-gate accept) required exact; geom Δ is double last-bit (op-order).\n\n");
        return allPass;
    }

    static final double A_SEMI2 = 0.00225;   // A_SEMI[2] µm (steric gate g6)

    static DoubleArray packGeomGateParams(TwoBodyConverterMotor.Glide2D G) {
        DoubleArray p = new DoubleArray(30);
        p.set(0, G.bhat[0]); p.set(1, G.bhat[1]); p.set(2, G.bhat[2]);
        p.set(3, G.econv[0]); p.set(4, G.econv[1]); p.set(5, G.econv[2]);
        p.set(6, G.eup[0]); p.set(7, G.eup[1]); p.set(8, G.eup[2]);
        p.set(9, G.lb); p.set(10, G.rF8[0]); p.set(11, G.rF8[1]); p.set(12, G.rConv[0]); p.set(13, G.rConv[1]);
        p.set(14, Constants.radius); p.set(15, A_SEMI2); p.set(16, G.kF8Code); p.set(17, G.kconvCode); p.set(18, G.kbindCode);
        p.set(19, TwoBodyConverterMotor.PHI_PRE_3E); p.set(20, Constants.kT); p.set(21, TwoBodyConverterMotor.PRESTROKE_THETAS);
        p.set(22, 3.0); p.set(23, 25); p.set(24, 25); p.set(25, 20); p.set(26, 2.0); p.set(27, 15.0); p.set(28, 0.05); p.set(29, 0.02);
        return p;
    }

    // ---------- Part 5.1 — cull active-set IDENTITY ----------
    static boolean gateCull(StringBuilder log) {
        System.out.println("\n--- Part 5.1: matCull vs host unionActive (active-set identity) ---");
        log.append("## Stage 1 — matCull (active-set identity vs unionActive)\n");
        double dt = 2.5e-6;
        boolean allPass = true;
        // scenarios: small deterministic mats at several densities/seeds; exercises active/inactive/bound/boundary.
        int[] seeds = { 11, 12, 13 };
        double[] densities = { 200, 700 };
        for (double density : densities) {
            for (int seed : seeds) {
                TwoBodyConverterMotor.Glide2D G = TwoBodyConverterMotor.buildSupMat(density, dt, 0.0, seed);
                int N = G.N, nSeg = G.nSeg;
                // exercise the bound branch too: deterministically pre-bind ~1/8 of motors to a valid segment
                for (int m = 0; m < N; m += 8) G.mot.boundSeg.set(m, m % nSeg);
                // --- host oracle ---
                TwoBodyConverterMotor.unionActive(G);
                // --- pack flat SoA (motor state stays here after this; only counts re-upload per step) ---
                DoubleArray site = new DoubleArray(2 * N);
                IntArray boundSeg = new IntArray(N);
                for (int m = 0; m < N; m++) { site.set(m, G.siteX[m]); site.set(N + m, G.siteY[m]); boundSeg.set(m, G.mot.boundSeg.get(m)); }
                FilamentStore f = G.fil;
                DoubleArray cullParams = DoubleArray.fromElements(G.queryR * G.queryR);
                IntArray counts = new IntArray(4); counts.set(0, N); counts.set(1, 0); counts.set(2, seed); counts.set(3, nSeg);
                IntArray active = new IntArray(N); active.init(0);

                // --- device: real TaskGraph, motor SoA persistent, filament small, only counts EVERY_EXECUTION ---
                long dev0 = System.nanoTime();
                try {
                    TaskGraph tg = new TaskGraph("cull")
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, boundSeg, site, f.coord, f.uVec, f.segLength, cullParams)
                            .transferToDevice(DataTransferMode.EVERY_EXECUTION, counts)
                            .task("matCull", MatSoaSlice::matCull, boundSeg, site, f.coord, f.uVec, f.segLength, cullParams, counts, active)
                            .transferToHost(DataTransferMode.EVERY_EXECUTION, active);
                    GridScheduler sched = new GridScheduler();
                    WorkerGrid w = new WorkerGrid1D(N); w.setLocalWork(1, 1, 1); sched.addWorkerGrid("cull.matCull", w);
                    TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot());
                    plan.withGridScheduler(sched).execute();
                } catch (Throwable ex) {
                    Throwable root = ex; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    System.out.printf(Locale.US, "  density=%.0f seed=%d N=%d: LOWERS=NO — %s: %s%n", density, seed, N, ex.getClass().getName(), oneLine(ex.getMessage()));
                    log.append(String.format(Locale.US, "- density=%.0f seed=%d N=%d: **LOWERS=NO** — `%s`: %s\n", density, seed, N, root.getClass().getName(), oneLine(root.getMessage())));
                    return false;
                }
                double devMs = (System.nanoTime() - dev0) / 1e6;

                int nActiveHost = 0, mism = 0, firstMism = -1;
                for (int m = 0; m < N; m++) {
                    int h = G.active[m] ? 1 : 0;
                    if (h == 1) nActiveHost++;
                    if (active.get(m) != h) { mism++; if (firstMism < 0) firstMism = m; }
                }
                boolean pass = (mism == 0);
                allPass &= pass;
                System.out.printf(Locale.US, "  density=%.0f seed=%d N=%d nSeg=%d: activeHost=%d mismatches=%d %s [%.1f ms]%n",
                        density, seed, N, nSeg, nActiveHost, mism, pass ? "PASS" : ("FAIL@" + firstMism), devMs);
                log.append(String.format(Locale.US, "- density=%.0f seed=%d N=%d nSeg=%d: activeHost=%d, mismatches=%d → %s (bytes/step: counts=16 up, active=%d down; no full mat transfer)\n",
                        density, seed, N, nSeg, nActiveHost, mism, pass ? "PASS" : "FAIL", 4 * N));
            }
        }
        log.append("- Residency: boundSeg/site/fil.coord/fil.uVec/fil.segLength/cullParams uploaded FIRST_EXECUTION (once); ")
           .append("only `counts` (16 B) re-uploads EVERY_EXECUTION and `active` (4N B) is read back — NO full per-motor state transfer.\n\n");
        return allPass;
    }

    static String oneLine(String s) { return s == null ? "(none)" : s.replaceAll("\\s+", " ").trim(); }
    static String gpuMemUsed() {
        try { Process p = new ProcessBuilder("nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits").start();
            String out = new String(p.getInputStream().readAllBytes()).trim(); p.waitFor();
            return out.isEmpty() ? "?" : out.split("\\R")[0].trim(); } catch (Exception e) { return "?"; }
    }
}
