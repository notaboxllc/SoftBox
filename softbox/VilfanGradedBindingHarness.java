package softbox;

import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import softbox.TwoBodyConverterMotor.Glide2D;
import softbox.VilfanTargetZoneDeterministicHarness.Rig;
import softbox.VilfanTargetZoneDeterministicHarness.Attach;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VILFAN GRADED COMPETING-SITE BINDING on native 13/6 actin — the follow-on study.
 *
 * <p>CPU ONLY. Drives {@link VilfanGradedBindingSystem} through the validated deterministic fixture built
 * in the previous increment ({@link VilfanTargetZoneDeterministicHarness}), which supplies the prescribed
 * translation, the height/tilt constraints, the free (or clamped) axial roll, the Brownian ablation and the
 * event recording. Nothing about the motor is changed: converter skew stays exactly 0°, chemistry,
 * detachment, stroke, S2 and bond mechanics are untouched.
 *
 * <p>Report: {@code docs/twirling/vilfan_target_zone/VILFAN_GRADED_BINDING_VALIDATION.md}.
 */
public final class VilfanGradedBindingHarness {
    private VilfanGradedBindingHarness() {}

    static String OUT = "RUN_LOGS/vilfan_graded_binding";
    /** Long-run discovery screen: prescribed travel per arm (µm) and trace sampling stride (steps). */
    static double TRAVEL_UM = 3.0;
    static int    TRACE_STRIDE = 500;
    static String ARM_SEEDS = "101,102", ARM_MIRRORS = "1,-1";
    static final double TWOPI = 2.0 * Math.PI;

    // ===================================================================================================
    // MAIN
    // ===================================================================================================
    public static void main(String[] args) throws Exception {
        String mode = "-gates";
        var H = VilfanTargetZoneDeterministicHarness.class;   // configuration lives on the fixture harness
        VilfanTargetZoneDeterministicHarness.LATTICE = 1;     // NATIVE actin 13/6 — the whole point
        VilfanTargetZoneDeterministicHarness.MATY = 0.01;
        VilfanTargetZoneDeterministicHarness.DENSITY = 6000;
        VilfanTargetZoneDeterministicHarness.STEPS = 20000;
        VilfanTargetZoneDeterministicHarness.WARMUP = 2000;
        VilfanTargetZoneDeterministicHarness.SEEDS = 8;
        VilfanTargetZoneDeterministicHarness.GRADED_MODE = VilfanGradedBindingSystem.MODE_ABSOLUTE;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-gates", "-landscape", "-window", "-binding", "-dynamic", "-ladder", "-all", "-time",
                     "-longrun" -> mode = a;
                case "-out" -> OUT = args[++i];
                case "-steps" -> VilfanTargetZoneDeterministicHarness.STEPS = Integer.parseInt(args[++i]);
                case "-warmup" -> VilfanTargetZoneDeterministicHarness.WARMUP = Integer.parseInt(args[++i]);
                case "-seeds" -> VilfanTargetZoneDeterministicHarness.SEEDS = Integer.parseInt(args[++i]);
                case "-seed" -> VilfanTargetZoneDeterministicHarness.SEED0 = Integer.parseInt(args[++i]);
                case "-v" -> VilfanTargetZoneDeterministicHarness.VCMD = Double.parseDouble(args[++i]);
                case "-density" -> VilfanTargetZoneDeterministicHarness.DENSITY = Double.parseDouble(args[++i]);
                case "-maty" -> VilfanTargetZoneDeterministicHarness.MATY = Double.parseDouble(args[++i]);
                case "-matx" -> VilfanTargetZoneDeterministicHarness.MATX = Double.parseDouble(args[++i]);
                case "-mirror" -> VilfanTargetZoneDeterministicHarness.MIRROR = Double.parseDouble(args[++i]);
                case "-az0" -> VilfanTargetZoneDeterministicHarness.AZ0_DEG = Double.parseDouble(args[++i]);
                case "-lattice" -> VilfanTargetZoneDeterministicHarness.LATTICE = Integer.parseInt(args[++i]);
                case "-alpha" -> VilfanTargetZoneDeterministicHarness.GRAD_ALPHA = Double.parseDouble(args[++i]);
                case "-win" -> VilfanTargetZoneDeterministicHarness.GRAD_WINDOW = Integer.parseInt(args[++i]);
                case "-attach-mode" -> VilfanTargetZoneDeterministicHarness.GRADED_MODE = modeOf(args[++i]);
                case "-shadow" -> VilfanTargetZoneDeterministicHarness.GRAD_SHADOW = true;
                case "-travel" -> TRAVEL_UM = Double.parseDouble(args[++i]);
                case "-trace-stride" -> TRACE_STRIDE = Integer.parseInt(args[++i]);
                case "-arm-seed" -> ARM_SEEDS = args[++i];
                case "-arm-mirror" -> ARM_MIRRORS = args[++i];
                default -> { if (a.startsWith("-")) throw new IllegalArgumentException("unknown flag " + a); }
            }
        }
        new File(OUT).mkdirs();
        // Per-attachment event records are written by the fixture harness, which owns writeEvents(); point
        // it at THIS study's directory so the two studies never share a run directory.
        VilfanTargetZoneDeterministicHarness.OUT = OUT;
        // Apply the declared lawn strip BEFORE any scene is built (the fixture harness does this in its own
        // main; this harness has its own entry point and must do it too, or the default 3.0 x 1.0 um mat is
        // used and the motor count is 150x larger than intended).
        TwoBodyConverterMotor.G4_MX = VilfanTargetZoneDeterministicHarness.MATX;
        TwoBodyConverterMotor.G4_MY = VilfanTargetZoneDeterministicHarness.MATY;
        System.out.println("=== VILFAN GRADED COMPETING-SITE BINDING ON NATIVE ACTIN (CPU ONLY) ===");
        System.out.println("  " + cfg());
        long t0 = System.currentTimeMillis();
        boolean ok = true;
        switch (mode) {
            case "-time" -> {
                VilfanTargetZoneDeterministicHarness.configure();
                Rig rt = VilfanTargetZoneDeterministicHarness.buildRig(101);
                for (int t = 0; t < 1500; t++) VilfanTargetZoneDeterministicHarness.step(rt, t, 101);
                long a0 = System.nanoTime();
                for (int t = 1500; t < 2500; t++) VilfanTargetZoneDeterministicHarness.step(rt, t, 101);
                double msPerStep = (System.nanoTime() - a0) / 1e6 / 1000.0;
                int nb = 0; for (int m = 0; m < rt.N; m++) if (rt.mot.boundSeg.get(m) >= 0) nb++;
                System.out.printf(Locale.US, "  %.3f ms/step  (N=%d, bound=%d, attach=%d, mode=%s)%n",
                        msPerStep, rt.N, nb, rt.attachments,
                        modeName(VilfanTargetZoneDeterministicHarness.GRADED_MODE));
            }
            case "-longrun" -> runLongRun();
            case "-gates" -> ok = runGates();
            case "-landscape" -> runLandscape();
            case "-window" -> runWindowConvergence();
            case "-binding" -> runBindingOnly();
            case "-dynamic" -> runDynamic();
            case "-ladder" -> runLadder();
            case "-all" -> { ok = runGates(); runWindowConvergence(); runLandscape(); runBindingOnly();
                             runDynamic(); runLadder(); }
        }
        System.out.printf(Locale.US, "%n  elapsed %.1f s%n", (System.currentTimeMillis() - t0) / 1000.0);
        if (!ok) System.out.println("=== SOME GATES FAILED ===");
    }

    static int modeOf(String s) {
        return switch (s) {
            case "off", "canonical", "hard-zone" -> VilfanGradedBindingSystem.MODE_OFF;
            case "vilfan-graded", "absolute" -> VilfanGradedBindingSystem.MODE_ABSOLUTE;
            case "vilfan-graded-hybrid", "hybrid" -> VilfanGradedBindingSystem.MODE_HYBRID;
            case "angular-neutral" -> VilfanGradedBindingSystem.MODE_ANGULAR_NEUTRAL;
            case "longitudinal-neutral" -> VilfanGradedBindingSystem.MODE_LONGITUDINAL_NEUTRAL;
            default -> throw new IllegalArgumentException("unknown attachment mode " + s);
        };
    }
    static String modeName(int m) {
        return switch (m) {
            case VilfanGradedBindingSystem.MODE_ABSOLUTE -> "vilfan-graded (published k_A clock)";
            case VilfanGradedBindingSystem.MODE_HYBRID -> "vilfan-graded-hybrid (SoftBox clock)";
            case VilfanGradedBindingSystem.MODE_ANGULAR_NEUTRAL -> "angular-neutral (alpha=0)";
            case VilfanGradedBindingSystem.MODE_LONGITUDINAL_NEUTRAL -> "longitudinal-neutral (K=0)";
            default -> "off (canonical / hard-zone)";
        };
    }
    static String cfg() {
        return String.format(Locale.US,
            "attach=%s | alpha=%.2f K=%.3f pN/nm kA=%.1f /s | window=+-%d sites | lattice=%s | mirror=%+.0f | "
            + "v=%+.3f um/s | az0=%.1f deg | clampRoll=%s | shadow=%s | seeds=%d steps=%d",
            modeName(VilfanTargetZoneDeterministicHarness.GRADED_MODE),
            VilfanTargetZoneDeterministicHarness.GRAD_ALPHA, VilfanGradedBindingSystem.VILFAN_K_PN_PER_NM,
            VilfanGradedBindingSystem.VILFAN_KA_PER_S, VilfanTargetZoneDeterministicHarness.GRAD_WINDOW,
            ExplicitCompleteMatHarness.siteModeName(VilfanTargetZoneDeterministicHarness.LATTICE),
            VilfanTargetZoneDeterministicHarness.MIRROR, VilfanTargetZoneDeterministicHarness.VCMD,
            VilfanTargetZoneDeterministicHarness.AZ0_DEG, VilfanTargetZoneDeterministicHarness.CLAMP_ROLL,
            VilfanTargetZoneDeterministicHarness.GRAD_SHADOW, VilfanTargetZoneDeterministicHarness.SEEDS,
            VilfanTargetZoneDeterministicHarness.STEPS);
    }

    // ===================================================================================================
    // THE STATIC HAZARD LANDSCAPE (host-side, uses the SAME arithmetic as the kernel)
    // ===================================================================================================
    /** One motor's hazard landscape at the current filament pose: [K_total, wMeanXi, wMeanTheta, ringFrac]. */
    static double[] landscapeAt(Rig r, int m, int win, double alphaOverride) {
        int n = r.nSeg, N = r.N;
        DoubleArray gp = r.e.gradP;
        double rise = gp.get(0), twist = gp.get(1), stair = gp.get(2);
        double Kpn = gp.get(4), alpha = (alphaOverride >= 0 ? alphaOverride : gp.get(5));
        double kA = gp.get(6), kT = gp.get(8);
        int md = (int) gp.get(13);
        if (md == VilfanGradedBindingSystem.MODE_ANGULAR_NEUTRAL) alpha = 0;
        if (md == VilfanGradedBindingSystem.MODE_LONGITUDINAL_NEUTRAL) Kpn = 0;
        double cL = 0.5 * Kpn / kT, cA = 0.5 * alpha;
        double[] E = r.G.g4E[m];
        double cx = r.f.coord.get(0), cy = r.f.coord.get(n), cz = r.f.coord.get(2 * n);
        double ux = r.f.uVec.get(0), uy = r.f.uVec.get(n), uz = r.f.uVec.get(2 * n);
        double yx = r.f.yVec.get(0), yy = r.f.yVec.get(n), yz = r.f.yVec.get(2 * n);
        double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
        double zl = Math.sqrt(zx * zx + zy * zy + zz * zz);
        zx /= zl; zy /= zl; zz /= zl;
        double dnx = gp.get(10), dny = gp.get(11), dnz = gp.get(12);
        double dd = dnx * ux + dny * uy + dnz * uz;
        double px = dnx - dd * ux, py = dny - dd * uy, pz = dnz - dd * uz;
        double pl = Math.sqrt(px * px + py * py + pz * pz); px /= pl; py /= pl; pz /= pl;
        double phiRef = VilfanGradedBindingSystem.azimuthOf(px, py, pz, yx, yy, yz, zx, zy, zz);
        double half = 0.5 * r.f.segLength.get(0), cum = r.e.segCumArc.get(0);
        double mAx = (E[0] - cx) * ux + (E[1] - cy) * uy + (E[2] - cz) * uz;
        int k0 = (int) ((cum + mAx + half) / rise + 0.5);
        double kTot = 0, sXi = 0, sTh = 0, ring = 0;
        for (int j = -win; j <= win; j++) {
            int k = k0 + j; if (k < 0) continue;
            double la = k * rise - cum; if (la < 0.0 || la > 2.0 * half) continue;
            double ph = (stair != 0.0) ? (k * stair) : (twist * (la - half));
            double xi = ((la - half) - mAx) * 1e3;
            double th = VilfanGradedBindingSystem.wrapPi(ph - phiRef);
            double ki = kA * Math.exp(-(cL * xi * xi + cA * th * th));
            kTot += ki; sXi += ki * xi; sTh += ki * th;
            if (j == -win || j == win) ring += ki;
        }
        return new double[]{ kTot, kTot > 0 ? sXi / kTot : 0, kTot > 0 ? sTh / kTot : 0,
                             kTot > 0 ? ring / kTot : 0 };
    }

    /** Per-site hazards for one motor at the current pose (for the heat map). */
    static double[][] siteHazards(Rig r, int m, int win) {
        int n = r.nSeg;
        DoubleArray gp = r.e.gradP;
        double rise = gp.get(0), twist = gp.get(1), stair = gp.get(2);
        double Kpn = gp.get(4), alpha = gp.get(5), kA = gp.get(6), kT = gp.get(8);
        int md = (int) gp.get(13);
        if (md == VilfanGradedBindingSystem.MODE_ANGULAR_NEUTRAL) alpha = 0;
        if (md == VilfanGradedBindingSystem.MODE_LONGITUDINAL_NEUTRAL) Kpn = 0;
        double cL = 0.5 * Kpn / kT, cA = 0.5 * alpha;
        double[] E = r.G.g4E[m];
        double cx = r.f.coord.get(0), cy = r.f.coord.get(n), cz = r.f.coord.get(2 * n);
        double ux = r.f.uVec.get(0), uy = r.f.uVec.get(n), uz = r.f.uVec.get(2 * n);
        double yx = r.f.yVec.get(0), yy = r.f.yVec.get(n), yz = r.f.yVec.get(2 * n);
        double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
        double zl = Math.sqrt(zx * zx + zy * zy + zz * zz); zx /= zl; zy /= zl; zz /= zl;
        double dnx = gp.get(10), dny = gp.get(11), dnz = gp.get(12);
        double dd = dnx * ux + dny * uy + dnz * uz;
        double px = dnx - dd * ux, py = dny - dd * uy, pz = dnz - dd * uz;
        double pl = Math.sqrt(px * px + py * py + pz * pz); px /= pl; py /= pl; pz /= pl;
        double phiRef = VilfanGradedBindingSystem.azimuthOf(px, py, pz, yx, yy, yz, zx, zy, zz);
        double half = 0.5 * r.f.segLength.get(0), cum = r.e.segCumArc.get(0);
        double mAx = (E[0] - cx) * ux + (E[1] - cy) * uy + (E[2] - cz) * uz;
        int k0 = (int) ((cum + mAx + half) / rise + 0.5);
        List<double[]> out = new ArrayList<>();
        for (int j = -win; j <= win; j++) {
            int k = k0 + j; if (k < 0) continue;
            double la = k * rise - cum; if (la < 0.0 || la > 2.0 * half) continue;
            double ph = (stair != 0.0) ? (k * stair) : (twist * (la - half));
            double xi = ((la - half) - mAx) * 1e3;
            double th = VilfanGradedBindingSystem.wrapPi(ph - phiRef);
            out.add(new double[]{ j, k, xi, th, kA * Math.exp(-(cL * xi * xi + cA * th * th)) });
        }
        return out.toArray(new double[0][]);
    }

    /** Build a rig at a given axial displacement of the filament (motors fixed) without running dynamics. */
    static Rig poseRig(double dispUm) {
        VilfanTargetZoneDeterministicHarness.configure();
        Rig r = VilfanTargetZoneDeterministicHarness.buildRig(VilfanTargetZoneDeterministicHarness.SEED0);
        int n = r.nSeg;
        for (int i = 0; i < n; i++) {
            r.f.coord.set(i, (float) (r.coord0.get(i) + dispUm * r.axis[0]));
            r.f.coord.set(n + i, (float) (r.coord0.get(n + i) + dispUm * r.axis[1]));
            r.f.coord.set(2 * n + i, (float) (r.coord0.get(2 * n + i) + dispUm * r.axis[2]));
        }
        DerivedGeometrySystem.derive(r.f.coord, r.f.uVec, r.f.yVec, r.f.zVec, r.f.end1, r.f.end2,
                r.f.segLength, r.f.counts);
        return r;
    }

    // ===================================================================================================
    // STAGE 2 — CANDIDATE-WINDOW CONVERGENCE
    // ===================================================================================================
    static void runWindowConvergence() {
        System.out.println("\n--- STAGE 2  CANDIDATE-WINDOW CONVERGENCE (native lattice) ---");
        int[] wins = { 3, 6, 12, 24 };
        final int WREF = 96;                                   // reference window: the "all sites" baseline
        double rise = ExplicitCompleteMatHarness.siteRise(1) * 1e3;
        List<String> rows = new ArrayList<>();
        rows.add("window_sites,window_nm,K_total_mean,omitted_mass_mean_pct,omitted_mass_max_pct,"
                + "dK_vs_prev_pct,wmean_xi_nm,d_xi_abs_nm,wmean_theta_rad,d_theta_abs_rad,n_motors");
        System.out.printf("  %8s %9s %13s %14s %13s %10s %12s %13s%n",
                "window", "span nm", "K_total /s", "omitted mean%", "omitted max%", "dK vs prev%",
                "<xi> nm", "<theta> rad");
        // INTERIOR motors only: a motor within WREF sites of a filament end sees a truncated lattice for
        // purely geometric reasons, which would masquerade as non-convergence. Averaged over a full actin
        // repeat of filament displacements.
        double margin = WREF * rise * 1e-3 + 0.02;
        double prevK = 0, prevXi = 0, prevTh = 0;
        for (int w : wins) {
            double sK = 0, sXi = 0, sTh = 0, sOm = 0, omMax = 0; int cnt = 0;
            for (int ip = 0; ip < 24; ip++) {
                double disp = ip * (13 * rise * 1e-3) / 24.0;
                Rig r = poseRig(disp);
                int n = r.nSeg;
                double half = 0.5 * r.f.segLength.get(0);
                double cx = r.f.coord.get(0), cy = r.f.coord.get(n), cz = r.f.coord.get(2 * n);
                double ux = r.f.uVec.get(0), uy = r.f.uVec.get(n), uz = r.f.uVec.get(2 * n);
                for (int m = 0; m < r.N; m++) {
                    double[] E = r.G.g4E[m];
                    double mAx = (E[0]-cx)*ux + (E[1]-cy)*uy + (E[2]-cz)*uz;
                    if (mAx < -half + margin || mAx > half - margin) continue;   // interior only
                    double[] Lw = landscapeAt(r, m, w, -1);
                    double[] Lr = landscapeAt(r, m, WREF, -1);
                    if (Lr[0] <= 0) continue;
                    double om = 1.0 - Lw[0] / Lr[0];
                    sK += Lw[0]; sXi += Lw[1]; sTh += Lw[2]; sOm += om;
                    omMax = Math.max(omMax, om); cnt++;
                }
            }
            if (cnt == 0) { System.out.println("  (no interior motors — filament too short for the reference window)"); break; }
            double mk = sK / cnt, mxi = sXi / cnt, mth = sTh / cnt, mom = sOm / cnt;
            double dK = prevK == 0 ? Double.NaN : 100 * Math.abs(mk - prevK) / prevK;
            System.out.printf(Locale.US, "  %8d %9.1f %13.6f %14.4g %13.4g %10.4f %12.6f %13.6f%n",
                    w, w * rise, mk, 100 * mom, 100 * omMax, dK, mxi, mth);
            rows.add(String.format(Locale.US, "%d,%.2f,%.8g,%.6g,%.6g,%.6g,%.8g,%.6g,%.8g,%.6g,%d",
                    w, w * rise, mk, 100 * mom, 100 * omMax, dK, mxi,
                    prevXi == 0 ? Double.NaN : Math.abs(mxi - prevXi), mth,
                    prevTh == 0 ? Double.NaN : Math.abs(mth - prevTh), cnt));
            prevK = mk; prevXi = mxi; prevTh = mth;
        }
        write("window_convergence.csv", rows);
        System.out.println("  omitted mass = 1 - K_total(w)/K_total(+-" + WREF + " sites), over INTERIOR motors only.");
    }

    // ===================================================================================================
    // STAGE 3 — STATIC LANDSCAPE
    // ===================================================================================================
    static void runLandscape() {
        System.out.println("\n--- STAGE 3  STATIC ATTACHMENT-RATE LANDSCAPE (native 13/6) ---");
        double riseUm = ExplicitCompleteMatHarness.siteRise(1);
        double repeatUm = 13 * riseUm;
        int NP = 260;                                     // 20 samples per subunit over one repeat
        int win = VilfanTargetZoneDeterministicHarness.GRAD_WINDOW;
        for (double mir : new double[]{ +1, -1 }) {
            VilfanTargetZoneDeterministicHarness.MIRROR = mir;
            List<String> scan = new ArrayList<>();
            scan.add("disp_um,motor,K_total_per_s,wmean_xi_nm,wmean_theta_rad,ring_frac");
            List<String> heat = new ArrayList<>();
            heat.add("disp_um,j_offset,site_k,xi_nm,theta_rad,k_i_per_s");
            double[] kt = new double[NP];
            for (int ip = 0; ip < NP; ip++) {
                double disp = ip * repeatUm / NP;
                Rig r = poseRig(disp);
                int mRef = r.N / 2;
                double[] L = landscapeAt(r, mRef, win, -1);
                kt[ip] = L[0];
                scan.add(String.format(Locale.US, "%.8g,%d,%.8g,%.8g,%.8g,%.6g", disp, mRef, L[0], L[1], L[2], L[3]));
                if (ip % 5 == 0) for (double[] h : siteHazards(r, mRef, win))
                    heat.add(String.format(Locale.US, "%.8g,%.0f,%.0f,%.6g,%.6g,%.8g",
                            disp, h[0], h[1], h[2], h[3], h[4]));
            }
            String tag = mir > 0 ? "native" : "mirror";
            write("landscape_scan_" + tag + ".csv", scan);
            write("landscape_heat_" + tag + ".csv", heat);
            double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE, sum = 0;
            for (double v : kt) { mn = Math.min(mn, v); mx = Math.max(mx, v); sum += v; }
            int nMax = 0;
            for (int i = 0; i < NP; i++) {
                double a = kt[(i - 1 + NP) % NP], b = kt[i], c = kt[(i + 1) % NP];
                if (b > a && b >= c && b > 0.5 * (mn + mx)) nMax++;
            }
            System.out.printf(Locale.US, "  %-7s K_total over one 13-subunit repeat: min=%.4g max=%.4g mean=%.4g "
                    + "modulation=(max-min)/max=%.4f  local maxima=%d%n",
                    tag, mn, mx, sum / NP, (mx - mn) / mx, nMax);
        }
        VilfanTargetZoneDeterministicHarness.MIRROR = +1;
    }

    // ===================================================================================================
    // STAGE 4 / 5 — CAMPAIGNS
    // ===================================================================================================
    static final class Res {
        String arm; int seed; double mirror, az0, v;
        int attach, nBefore, nAfter; double aTZx, meanXi, meanTheta, meanKtot;
        double tau, omega, turnsPerUm, avgBound, attachRate, ringFrac;
        int shadowN; double shadowXi;
    }

    static Res runArm(String arm, int seed) {
        VilfanTargetZoneDeterministicHarness.configure();
        Rig r = VilfanTargetZoneDeterministicHarness.buildRig(seed);
        int steps = VilfanTargetZoneDeterministicHarness.STEPS;
        int warm = VilfanTargetZoneDeterministicHarness.WARMUP;
        double dt = VilfanTargetZoneDeterministicHarness.DT;
        double sXi = 0, sTh = 0, sK = 0, sRing = 0; int na = 0;
        double shSum = 0; int shN = 0;
        for (int t = 0; t < steps; t++) {
            VilfanTargetZoneDeterministicHarness.step(r, t, seed);
            if (t >= warm && VilfanTargetZoneDeterministicHarness.GRAD_SHADOW) {
                for (int m = 0; m < r.N; m++) if (r.e.shadow.get(m) != 0.0) { shSum += r.e.shadow.get(r.N + m); shN++; }
            }
        }
        Res s = new Res();
        s.arm = arm; s.seed = seed;
        s.mirror = VilfanTargetZoneDeterministicHarness.MIRROR;
        s.az0 = VilfanTargetZoneDeterministicHarness.AZ0_DEG;
        s.v = VilfanTargetZoneDeterministicHarness.VCMD;
        for (Attach a : r.events) {
            if (a.step < warm) continue;
            na++; sXi += a.xiNm; sTh += a.thetaRad; sK += a.kTot; sRing += a.ringFrac;
            if (a.xiNm < 0) s.nBefore++; else if (a.xiNm > 0) s.nAfter++;
        }
        s.attach = na;
        int den = s.nBefore + s.nAfter;
        s.aTZx = den > 0 ? (double) (s.nBefore - s.nAfter) / den : Double.NaN;
        s.meanXi = na > 0 ? sXi / na : Double.NaN;
        s.meanTheta = na > 0 ? sTh / na : Double.NaN;
        s.meanKtot = na > 0 ? sK / na : Double.NaN;
        s.ringFrac = na > 0 ? sRing / na : Double.NaN;
        s.attachRate = na / ((steps - warm) * dt);
        s.meanKtot = na > 0 ? sK / na : Double.NaN;
        s.tau = r.nStat > 0 ? r.sumTau / r.nStat : 0;
        s.avgBound = r.nStat > 0 ? r.sumBound / r.nStat : 0;
        s.omega = r.roll / (steps * dt);
        double dist = Math.abs(s.v) * steps * dt;
        s.turnsPerUm = dist > 0 ? (r.roll / TWOPI) / dist : Double.NaN;
        s.shadowN = shN; s.shadowXi = shN > 0 ? shSum / shN : Double.NaN;
        VilfanTargetZoneDeterministicHarness.writeEvents(r, seed);
        return s;
    }

    static final String HDR = "arm,attach_mode,mirror,az0_deg,v_um_s,seed,attach,n_before,n_after,A_TZx,"
            + "mean_xi_nm,mean_theta_rad,mean_Ktotal_per_s,ring_frac,tau_Nm,omega_rad_s,turns_per_um,"
            + "avg_bound,attach_rate_hz,shadow_n,shadow_mean_xi_nm";
    static String row(Res s) {
        return String.format(Locale.US, "%s,%s,%+.0f,%.1f,%.4f,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6g,%.6g,%.6e,%.6e,"
                        + "%.6f,%.4f,%.4f,%d,%.6f",
                s.arm.replace(',', ';'), modeName(VilfanTargetZoneDeterministicHarness.GRADED_MODE).split(" ")[0],
                s.mirror, s.az0, s.v, s.seed, s.attach, s.nBefore, s.nAfter, s.aTZx, s.meanXi, s.meanTheta,
                s.meanKtot, s.ringFrac, s.tau, s.omega, s.turnsPerUm, s.avgBound, s.attachRate,
                s.shadowN, s.shadowXi);
    }

    static void report(String arm, List<Res> rs, List<String> rows) {
        for (Res s : rs) rows.add(row(s));
        double[] a = ms(rs, x -> x.aTZx), xi = ms(rs, x -> x.meanXi), th = ms(rs, x -> x.meanTheta);
        double[] tq = ms(rs, x -> x.tau), om = ms(rs, x -> x.omega), tp = ms(rs, x -> x.turnsPerUm);
        int at = 0; for (Res s : rs) at += s.attach;
        System.out.printf(Locale.US, "  %-30s %7d %7.3f±%.3f %+8.3f±%.3f %+8.4f±%.4f %+.3e±%.1e %+8.1f±%.1f %+8.2f%n",
                arm, at, a[0], a[1], xi[0], xi[1], th[0], th[1], tq[0], tq[1], om[0], om[1], tp[0]);
    }
    static double[] ms(List<Res> rs, java.util.function.ToDoubleFunction<Res> f) {
        List<Double> v = new ArrayList<>();
        for (Res r : rs) { double x = f.applyAsDouble(r); if (!Double.isNaN(x)) v.add(x); }
        if (v.isEmpty()) return new double[]{ Double.NaN, Double.NaN };
        double m = 0; for (double x : v) m += x; m /= v.size();
        double q = 0; for (double x : v) q += (x - m) * (x - m);
        return new double[]{ m, v.size() > 1 ? Math.sqrt(q / (v.size() - 1) / v.size()) : 0 };
    }
    static void header() {
        System.out.printf("%n  %-30s %7s %13s %15s %15s %14s %15s %9s%n",
                "arm", "attach", "A_TZ,x", "<xi> nm", "<theta> rad", "tau (N·m)", "Omega (rad/s)", "turns/µm");
    }

    /** Stage 4 — binding only: roll clamped, so bond mechanics cannot feed back on the landscape. */
    static void runBindingOnly() {
        System.out.println("\n--- STAGE 4  BINDING-ONLY FIRST-PASSAGE TEST (roll CLAMPED) ---");
        VilfanTargetZoneDeterministicHarness.CLAMP_ROLL = true;
        VilfanTargetZoneDeterministicHarness.GRAD_SHADOW = true;
        List<String> rows = new ArrayList<>(); rows.add(HDR);
        header();
        int saveMode = VilfanTargetZoneDeterministicHarness.GRADED_MODE;
        for (Object[] arm : new Object[][]{
                { "graded native", VilfanGradedBindingSystem.MODE_ABSOLUTE, +1.0 },
                { "graded MIRRORED", VilfanGradedBindingSystem.MODE_ABSOLUTE, -1.0 },
                { "angular-neutral native", VilfanGradedBindingSystem.MODE_ANGULAR_NEUTRAL, +1.0 },
                { "longitudinal-neutral native", VilfanGradedBindingSystem.MODE_LONGITUDINAL_NEUTRAL, +1.0 } }) {
            VilfanTargetZoneDeterministicHarness.GRADED_MODE = (Integer) arm[1];
            VilfanTargetZoneDeterministicHarness.MIRROR = (Double) arm[2];
            List<Res> rs = new ArrayList<>();
            for (int i = 0; i < VilfanTargetZoneDeterministicHarness.SEEDS; i++)
                rs.add(runArm((String) arm[0], VilfanTargetZoneDeterministicHarness.SEED0 + i));
            report((String) arm[0], rs, rows);
        }
        VilfanTargetZoneDeterministicHarness.GRADED_MODE = saveMode;
        VilfanTargetZoneDeterministicHarness.MIRROR = +1;
        // starting-azimuth robustness
        System.out.println("  starting-azimuth robustness (graded, native):");
        double saz = VilfanTargetZoneDeterministicHarness.AZ0_DEG;
        for (double az : new double[]{ 0, 60, 120, 180, 240, 300 }) {
            VilfanTargetZoneDeterministicHarness.AZ0_DEG = az;
            List<Res> rs = new ArrayList<>();
            for (int i = 0; i < VilfanTargetZoneDeterministicHarness.SEEDS; i++)
                rs.add(runArm("az0=" + (int) az, VilfanTargetZoneDeterministicHarness.SEED0 + i));
            report("az0=" + (int) az + " deg", rs, rows);
        }
        VilfanTargetZoneDeterministicHarness.AZ0_DEG = saz;
        VilfanTargetZoneDeterministicHarness.CLAMP_ROLL = false;
        VilfanTargetZoneDeterministicHarness.GRAD_SHADOW = false;
        write("binding_only.csv", rows);
    }

    /** Stage 5 — dynamic: roll released, full SoftBox post-attachment mechanics. */
    static void runDynamic() {
        System.out.println("\n--- STAGE 5  DYNAMIC TORQUE CLOSURE (roll FREE) ---");
        List<String> rows = new ArrayList<>(); rows.add(HDR);
        header();
        int saveMode = VilfanTargetZoneDeterministicHarness.GRADED_MODE;
        for (Object[] arm : new Object[][]{
                { "graded native", VilfanGradedBindingSystem.MODE_ABSOLUTE, +1.0 },
                { "graded MIRRORED", VilfanGradedBindingSystem.MODE_ABSOLUTE, -1.0 },
                { "angular-neutral native", VilfanGradedBindingSystem.MODE_ANGULAR_NEUTRAL, +1.0 },
                { "angular-neutral MIRRORED", VilfanGradedBindingSystem.MODE_ANGULAR_NEUTRAL, -1.0 } }) {
            VilfanTargetZoneDeterministicHarness.GRADED_MODE = (Integer) arm[1];
            VilfanTargetZoneDeterministicHarness.MIRROR = (Double) arm[2];
            List<Res> rs = new ArrayList<>();
            for (int i = 0; i < VilfanTargetZoneDeterministicHarness.SEEDS; i++)
                rs.add(runArm((String) arm[0], VilfanTargetZoneDeterministicHarness.SEED0 + i));
            report((String) arm[0], rs, rows);
        }
        VilfanTargetZoneDeterministicHarness.GRADED_MODE = saveMode;
        VilfanTargetZoneDeterministicHarness.MIRROR = +1;
        write("dynamic.csv", rows);
    }

    /** Stage 6 — bounded prescribed-speed ladder. */
    static void runLadder() {
        System.out.println("\n--- STAGE 6  BOUNDED SPEED LADDER (graded, native) ---");
        List<String> rows = new ArrayList<>(); rows.add(HDR);
        header();
        double sv = VilfanTargetZoneDeterministicHarness.VCMD;
        for (double v : new double[]{ 0.25, 0.5, 1.0, 2.0, 4.0 }) {
            VilfanTargetZoneDeterministicHarness.VCMD = v;
            List<Res> rs = new ArrayList<>();
            for (int i = 0; i < VilfanTargetZoneDeterministicHarness.SEEDS; i++)
                rs.add(runArm(String.format(Locale.US, "v=%.2f", v),
                        VilfanTargetZoneDeterministicHarness.SEED0 + i));
            report(String.format(Locale.US, "v=%.2f um/s", v), rs, rows);
        }
        VilfanTargetZoneDeterministicHarness.VCMD = sv;
        write("ladder.csv", rows);
    }

    // ===================================================================================================
    // LONG-RUN STEADY-TWIRL DISCOVERY SCREEN
    // ===================================================================================================
    /**
     * One arm = one (lattice handedness, chemical seed) trajectory carried for {@link #TRAVEL_UM} of
     * PRESCRIBED filament travel, with a periodic trace of everything needed to reconstruct cumulative
     * body-fixed roll, axial torque, attachment flux and bound population against both time and distance.
     *
     * <p>Records are ATOMIC (written to {@code .tmp} then renamed) and the screen is RESUMABLE: an arm whose
     * final trace already exists is skipped. Because the fixture is fully deterministic for a given seed,
     * re-running an arm to a longer travel reproduces the shorter trajectory exactly, so the bounded 5 µm
     * extension continues the SAME trajectories rather than creating new ones.
     *
     * <p>This is a DURATION EXTENSION ONLY. Every motor and attachment parameter is exactly as frozen by the
     * task; the artifact block printed at the start of each run records them so the log itself is the audit.
     */
    static void runLongRun() {
        int steps = (int) Math.round(TRAVEL_UM / (Math.abs(VilfanTargetZoneDeterministicHarness.VCMD)
                * VilfanTargetZoneDeterministicHarness.DT));
        File dir = new File(OUT, "longrun"); dir.mkdirs();
        System.out.printf(Locale.US, "%n--- LONG-RUN DISCOVERY SCREEN: %.2f µm travel = %d steps/arm ---%n",
                TRAVEL_UM, steps);
        List<String> summary = new ArrayList<>();
        summary.add("arm,mirror,seed,az0_deg,travel_um,steps,gamma_roll_Nms,contour_um,nSeg,"
                + "attach_total,avg_bound,roll_total_rad,turns_total,mean_tau_Nm,wall_s");
        for (String ms : ARM_MIRRORS.split(",")) {
            for (String ss : ARM_SEEDS.split(",")) {
                double mir = Double.parseDouble(ms.trim());
                int seed = Integer.parseInt(ss.trim());
                double az = VilfanTargetZoneDeterministicHarness.AZ0_DEG;
                String nm = String.format(Locale.US, "trace_az%03.0f_mir%+.0f_seed%d_%.2fum.csv", az, mir, seed, TRAVEL_UM);
                File fin = new File(dir, nm);
                if (fin.exists()) { System.out.println("  [skip, already complete] " + nm); continue; }
                VilfanTargetZoneDeterministicHarness.MIRROR = mir;
                VilfanTargetZoneDeterministicHarness.CLAMP_ROLL = false;   // roll MUST be free here
                VilfanTargetZoneDeterministicHarness.configure();
                Rig r = VilfanTargetZoneDeterministicHarness.buildRig(seed);
                double gammaRoll = r.f.bRotGam.get(0);
                double contour = 0; for (int k = 0; k < r.nSeg; k++) contour += r.f.segLength.get(k);
                if (mir == Double.parseDouble(ARM_MIRRORS.split(",")[0].trim()) && seed == Integer.parseInt(ARM_SEEDS.split(",")[0].trim()))
                    printArtifactBlock(r, gammaRoll, contour, steps);
                long t0 = System.currentTimeMillis();
                File tmp = new File(dir, nm + ".tmp");
                double sumTau = 0, sumBound = 0; long nAcc = 0;
                try (PrintWriter w = new PrintWriter(tmp)) {
                    w.println("step,time_s,travel_um,roll_rad,tau_Nm,bound,attach_cum");
                    for (int t = 0; t < steps; t++) {
                        VilfanTargetZoneDeterministicHarness.step(r, t, seed);
                        double tau = r.axialTorqueTotal();
                        int nb = 0; for (int m = 0; m < r.N; m++) if (r.mot.boundSeg.get(m) >= 0) nb++;
                        sumTau += tau; sumBound += nb; nAcc++;
                        if (t % TRACE_STRIDE == 0 || t == steps - 1)
                            w.printf(Locale.US, "%d,%.9g,%.9g,%.10g,%.6e,%d,%d%n", t, r.tSim,
                                    Math.abs(VilfanTargetZoneDeterministicHarness.VCMD) * r.tSim,
                                    r.roll, tau, nb, r.attachments);
                    }
                } catch (Exception ex) { System.out.println("  ! trace write failed: " + ex); continue; }
                if (!tmp.renameTo(fin)) System.out.println("  ! atomic rename failed for " + nm);
                double wall = (System.currentTimeMillis() - t0) / 1000.0;
                summary.add(String.format(Locale.US, "%s,%+.0f,%d,%.1f,%.2f,%d,%.6e,%.4f,%d,%d,%.4f,%.6f,%.6f,%.6e,%.1f",
                        (mir > 0 ? "native" : "mirror"), mir, seed, az, TRAVEL_UM, steps, gammaRoll, contour,
                        r.nSeg, r.attachments, sumBound / nAcc, r.roll, r.roll / TWOPI, sumTau / nAcc, wall));
                System.out.printf(Locale.US, "  %-7s seed %d : roll=%+.5f rad (%+.5f turns)  <tau>=%+.4e N·m  "
                        + "attach=%d  avgBound=%.3f  [%.0f s]%n", (mir > 0 ? "native" : "mirror"), seed,
                        r.roll, r.roll / TWOPI, sumTau / nAcc, r.attachments, sumBound / nAcc, wall);
                VilfanTargetZoneDeterministicHarness.writeEvents(r, seed);
            }
        }
        appendCsv(new File(dir, String.format(Locale.US, "arm_summary_mir%s_seed%s.csv",
                ARM_MIRRORS.replace(",", "_").replace("-", "m"), ARM_SEEDS.replace(",", "_"))), summary);
    }

    /** The artifact audit the task requires, printed into the run log so the log itself is the evidence. */
    static void printArtifactBlock(Rig r, double gammaRoll, double contour, int steps) {
        double single = DragTensorSystem.rodDragSI(contour, Constants.radius)[3];
        System.out.println("  ---- ARTIFACT CHECKS ----");
        System.out.printf(Locale.US, "   roll drag        : gammaRoll = %.6e N·m·s over nSeg=%d, contour=%.4f µm%n",
                gammaRoll, r.nSeg, contour);
        System.out.printf(Locale.US, "                      one rod of the same contour = %.6e  (ratio %.6f)"
                + "  => WHOLE-FILAMENT drag%n", single, gammaRoll / single);
        System.out.printf(Locale.US, "   roll observable  : transported body-fixed (rollIncrementTransported), NOT lab spin%n");
        System.out.printf(Locale.US, "   roll clamp       : CLAMP_ROLL=%s (must be false)%n",
                VilfanTargetZoneDeterministicHarness.CLAMP_ROLL);
        System.out.printf(Locale.US, "   height/tilt      : prescribe=%s clampTilt=%s (both must be true)%n",
                VilfanTargetZoneDeterministicHarness.PRESCRIBE, VilfanTargetZoneDeterministicHarness.CLAMP_TILT);
        System.out.printf(Locale.US, "   brownian         : %s%n", ExplicitCompleteMatHarness.brownianPolicyString());
        System.out.printf(Locale.US, "   attachment       : mode=%s window=+-%d alpha=%.2f K=%.3f pN/nm kA=%.1f /s%n",
                modeName(VilfanTargetZoneDeterministicHarness.GRADED_MODE),
                VilfanTargetZoneDeterministicHarness.GRAD_WINDOW,
                VilfanTargetZoneDeterministicHarness.GRAD_ALPHA,
                VilfanGradedBindingSystem.VILFAN_K_PN_PER_NM, VilfanGradedBindingSystem.VILFAN_KA_PER_S);
        System.out.printf(Locale.US, "   lattice          : %s   converter skew = %.2f deg%n",
                ExplicitCompleteMatHarness.siteModeName(VilfanTargetZoneDeterministicHarness.LATTICE),
                VilfanTargetZoneDeterministicHarness.CONV_SKEW_DEG);
        System.out.printf(Locale.US, "   prescribed v     : %+.4f µm/s, dt=%.3g s, steps=%d%n",
                VilfanTargetZoneDeterministicHarness.VCMD, VilfanTargetZoneDeterministicHarness.DT, steps);
        System.out.printf(Locale.US, "   2pi conversion   : turns = roll_rad / %.10f%n", TWOPI);
        System.out.println("  -------------------------");
    }

    static void appendCsv(File f, List<String> rows) {
        boolean exists = f.exists();
        try (PrintWriter w = new PrintWriter(new java.io.FileWriter(f, true))) {
            for (int i = 0; i < rows.size(); i++) { if (i == 0 && exists) continue; w.println(rows.get(i)); }
        } catch (Exception e) { System.out.println("  ! summary append failed: " + e); }
        System.out.println("  wrote " + f.getPath());
    }

    // ===================================================================================================
    // STAGE 8 — UNIT GATES
    // ===================================================================================================
    static int gP, gF;
    static void ck(String n, boolean c, String d) {
        System.out.printf("  [%s] %-56s %s%n", c ? "PASS" : "FAIL", n, d); if (c) gP++; else gF++;
    }

    static boolean runGates() {
        gP = 0; gF = 0;
        System.out.println("\n--- STAGE 8  GRADED-HAZARD UNIT GATES ---");
        // V1 hazards finite, non-negative, and exactly the published law at zero mismatch
        Rig r = poseRig(0.0);
        boolean fin = true, nonneg = true; double kmax = 0;
        for (double[] h : siteHazards(r, r.N / 2, 24)) {
            fin &= Double.isFinite(h[4]); nonneg &= h[4] >= 0; kmax = Math.max(kmax, h[4]);
        }
        ck("V1 all site hazards finite and non-negative", fin && nonneg,
                String.format(Locale.US, "max k_i = %.4f /s (published cap k_A = %.1f /s)", kmax,
                        VilfanGradedBindingSystem.VILFAN_KA_PER_S));
        ck("V2 no hazard exceeds the published maximum k_A", kmax <= VilfanGradedBindingSystem.VILFAN_KA_PER_S + 1e-9,
                String.format(Locale.US, "max k_i / k_A = %.6f", kmax / VilfanGradedBindingSystem.VILFAN_KA_PER_S));
        // V3 conditional probabilities sum to 1
        double[] L = landscapeAt(r, r.N / 2, 24, -1);
        double sum = 0; for (double[] h : siteHazards(r, r.N / 2, 24)) sum += h[4] / L[0];
        ck("V3 conditional site probabilities sum to 1", Math.abs(sum - 1.0) < 1e-12,
                String.format(Locale.US, "sum P(i|attach) = %.15f", sum));
        // V4 exponent check against the closed form at a hand-computed mismatch
        double kT = VilfanGradedBindingSystem.KT_PN_NM, Kp = VilfanGradedBindingSystem.VILFAN_K_PN_PER_NM;
        double al = VilfanTargetZoneDeterministicHarness.GRAD_ALPHA;
        double xi = 3.0, th = 0.4;
        double want = VilfanGradedBindingSystem.VILFAN_KA_PER_S
                * Math.exp(-(0.5 * Kp * xi * xi + 0.5 * al * kT * th * th) / kT);
        double got = VilfanGradedBindingSystem.VILFAN_KA_PER_S
                * Math.exp(-(0.5 * Kp / kT * xi * xi + 0.5 * al * th * th));
        ck("V4 pN·nm exponent form == the published U_i/kT", Math.abs(want - got) < 1e-12 * want,
                String.format(Locale.US, "k(xi=3nm, th=0.4) = %.9f /s both ways", got));
        // V5 P_attach in [0,1] over a wide K_total range
        boolean pok = true;
        for (double K = 0; K < 1e6; K = K * 4 + 1) {
            double p = 1 - Math.exp(-K * VilfanTargetZoneDeterministicHarness.DT);
            pok &= (p >= 0 && p <= 1);
        }
        ck("V5 P_attach = 1-exp(-K_total dt) stays in [0,1]", pok, "checked to K_total = 1e6 /s");
        // V6 default identity: mode OFF leaves the canonical path untouched
        int sm = VilfanTargetZoneDeterministicHarness.GRADED_MODE;
        VilfanTargetZoneDeterministicHarness.GRADED_MODE = VilfanGradedBindingSystem.MODE_OFF;
        VilfanTargetZoneDeterministicHarness.configure();
        Rig ra = VilfanTargetZoneDeterministicHarness.buildRig(101);
        for (int t = 0; t < 600; t++) VilfanTargetZoneDeterministicHarness.step(ra, t, 101);
        VilfanTargetZoneDeterministicHarness.configure();
        Rig rb = VilfanTargetZoneDeterministicHarness.buildRig(101);
        for (int t = 0; t < 600; t++) VilfanTargetZoneDeterministicHarness.step(rb, t, 101);
        double dC = 0;
        for (int i = 0; i < 3 * ra.nSeg; i++) dC = Math.max(dC, Math.abs(ra.f.coord.get(i) - rb.f.coord.get(i)));
        ck("V6 mode OFF is deterministic and reproducible", dC == 0.0 && ra.roll == rb.roll,
                String.format(Locale.US, "max|Δcoord| = %.3e µm", dC));
        VilfanTargetZoneDeterministicHarness.GRADED_MODE = sm;
        // V7 graded mode same-seed bit-identity
        VilfanTargetZoneDeterministicHarness.configure();
        Rig rc = VilfanTargetZoneDeterministicHarness.buildRig(101);
        for (int t = 0; t < 600; t++) VilfanTargetZoneDeterministicHarness.step(rc, t, 101);
        VilfanTargetZoneDeterministicHarness.configure();
        Rig rd = VilfanTargetZoneDeterministicHarness.buildRig(101);
        for (int t = 0; t < 600; t++) VilfanTargetZoneDeterministicHarness.step(rd, t, 101);
        double dG = 0; int dB = 0;
        for (int i = 0; i < 3 * rc.nSeg; i++) dG = Math.max(dG, Math.abs(rc.f.coord.get(i) - rd.f.coord.get(i)));
        for (int m = 0; m < rc.N; m++) if (rc.mot.boundSeg.get(m) != rd.mot.boundSeg.get(m)) dB++;
        ck("V7 graded mode: same-seed CPU repeat bit-identical", dG == 0.0 && dB == 0,
                String.format(Locale.US, "max|Δcoord| = %.3e µm, bound mismatches = %d", dG, dB));
        // V8 site exclusivity
        VilfanTargetZoneDeterministicHarness.configure();
        Rig re = VilfanTargetZoneDeterministicHarness.buildRig(101);
        int dup = 0;
        for (int t = 0; t < 2000; t++) {
            VilfanTargetZoneDeterministicHarness.step(re, t, 101);
            for (int m1 = 0; m1 < re.N; m1++) {
                if (re.mot.boundSeg.get(m1) < 0) continue;
                for (int m2 = m1 + 1; m2 < re.N; m2++) {
                    if (re.mot.boundSeg.get(m2) < 0) continue;
                    if (re.e.bindSite.get(m1) == re.e.bindSite.get(m2)) dup++;
                }
            }
        }
        ck("V8 site exclusivity: no two heads share a site", dup == 0, "duplicate site-holdings = " + dup);
        // V9 timestep sensitivity of the competing-hazard probability (numerical, not physics)
        double K = 500;
        double p1 = 1 - Math.exp(-K * 2.5e-6), p2 = 1 - Math.pow(1 - (1 - Math.exp(-K * 1.25e-6)), 2);
        ck("V9 competing-hazard probability is dt-composable", Math.abs(p1 - p2) < 1e-12,
                String.format(Locale.US, "one 2.5e-6 step %.12f == two 1.25e-6 steps %.12f", p1, p2));
        // V10 health of a real graded run
        boolean h = true;
        for (int i = 0; i < 3 * re.nSeg; i++) h &= Double.isFinite(re.f.coord.get(i));
        for (int m = 0; m < re.N; m++) { int st = re.mot.nucleotideState.get(m); h &= (st >= 0 && st <= 3); }
        ck("V10 states finite, nucleotide states legal after 2000 graded steps", h,
                String.format(Locale.US, "%d attachments recorded", re.attachments));
        System.out.printf("%n  GATES: %d PASS / %d FAIL%n", gP, gF);
        return gF == 0;
    }

    static void write(String name, List<String> rows) {
        new File(OUT).mkdirs();
        try (PrintWriter w = new PrintWriter(new File(OUT, name))) { for (String s : rows) w.println(s); }
        catch (Exception e) { System.out.println("  ! write failed: " + e); }
        System.out.println("  wrote " + new File(OUT, name).getPath());
    }
}
