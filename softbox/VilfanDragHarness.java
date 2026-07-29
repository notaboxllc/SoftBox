package softbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import softbox.VilfanCompleteSystem.Config;
import softbox.VilfanCompleteSystem.Result;

/**
 * FINITE FILAMENT DRAG — deterministic overdamped Vilfan reference. CPU only, default off.
 * <p>
 * Restores exactly ONE physical assumption relative to the validated complete reference: the
 * filament no longer equilibrates instantaneously. Vilfan's Eq (5) is replaced by
 * <pre>
 *   gammaX     dX/dt     = sum_j F_j
 *   gammaTheta dTheta/dt = sum_j M_j
 * </pre>
 * with his force and torque laws unchanged. No Brownian motion, no inertia, no extra degree of
 * freedom, no chemistry change, no load dependence of any rate.
 * <pre>
 *   -vilfan-drag -drag-audit      Stage 0: whole-filament drag audit (SI and working units)
 *   -vilfan-drag -drag-gates      Stage 2: numerical validation gates A-G
 *   -vilfan-drag -od-paper        Stage 3: paper-exact lattice, overdamped
 *   -vilfan-drag -od-native       Stage 4: SoftBox native lattice, overdamped
 *   -vilfan-drag -od-controls     Stage 5: alpha = 0, no-depletion, d = 0
 *   -vilfan-drag -od-visc         Stage 6: bounded viscosity sensitivity
 *   -vilfan-drag -list &lt;stage&gt;    pending arm ids   |   -arm &lt;id&gt;   run one arm
 * </pre>
 */
public final class VilfanDragHarness {

    static final String RUNDIR = "RUN_LOGS/vilfan_drag";
    static final double TWO_PI = 2.0 * Math.PI;

    public static void main(String[] args) throws Exception {
        List<String> a = List.of(args);
        if (!a.contains("-vilfan-drag")) {
            System.out.println("VilfanDragHarness: refusing to run without the explicit -vilfan-drag flag.");
            return;
        }
        Files.createDirectories(Path.of(RUNDIR));
        System.err.println("=== VILFAN OVERDAMPED-DRAG STUDY — CPU only, no CUDA/TornadoVM/TaskGraph ===");

        if (a.contains("-drag-audit")) { dragAudit(); return; }
        if (a.contains("-drag-gates")) { gates(); return; }
        if (a.contains("-od-paper"))    { campaign(paperArms()); return; }
        if (a.contains("-od-native"))   { campaign(nativeArms()); return; }
        if (a.contains("-od-controls")) { campaign(controlArms()); return; }
        if (a.contains("-od-visc"))     { campaign(viscArms()); return; }
        int il = a.indexOf("-list");
        if (il >= 0) {
            String st = (il + 1 < a.size()) ? a.get(il + 1) : "all";
            for (Arm arm : listFor(st)) if (!Files.exists(Path.of(RUNDIR, arm.id() + ".json"))) System.out.println(arm.id());
            return;
        }
        int ia = a.indexOf("-arm");
        if (ia >= 0 && ia + 1 < a.size()) { runOneArm(a.get(ia + 1)); return; }
        System.out.println("no mode selected.");
    }

    /* =============================== configuration bases =============================== */

    /** the frozen Vilfan science, plus overdamped mechanics at the assay viscosity. */
    static Config odPaper() {
        Config c = new Config();
        c.latP = 13; c.latQ = 28; c.aNm = 2.75; c.latSign = -1;
        c.alpha = 4.0; c.kD = 5.0;                       // kD/kA = 0.1, [ATP] = 1 uM
        c.mechanics = "overdamped";
        c.etaPaS = VilfanDrag.ETA_ASSAY;                 // 0.01 Pa*s
        return c;
    }
    static Config odNative() {
        Config c = odPaper();
        c.latP = 37; c.latQ = 80; c.aNm = 2.7;           // theta0 = -166.5 deg, rise 2.7 nm
        return c;
    }

    record Arm(String id, Config cfg) { }

    static List<Arm> mirrored(String tag, Config base, long[] seeds) {
        List<Arm> L = new ArrayList<>();
        for (long s : seeds) for (int sign : new int[]{-1, +1}) {
            Config c = base.copy(); c.seed = s; c.latSign = sign;
            L.add(new Arm(String.format(Locale.ROOT, "%s_s%d_%s", tag, s, sign < 0 ? "native" : "mirror"), c));
        }
        return L;
    }

    static final long[] SEEDS4 = {101, 102, 103, 104};
    static final long[] SEEDS2 = {101, 102};

    static List<Arm> paperArms()  { return mirrored("odpaper_e0.01", odPaper(), SEEDS4); }
    static List<Arm> nativeArms() { return mirrored("odnative_e0.01", odNative(), SEEDS4); }

    static List<Arm> controlArms() {
        List<Arm> L = new ArrayList<>();
        Config a0 = odNative(); a0.alpha = 0.0;
        L.addAll(mirrored("odctl_alpha0", a0, SEEDS2));
        Config d0 = odNative(); d0.dNm = 0.0; d0.warmupUm = -1.0; d0.travelUm = 1e9; d0.maxSimTimeS = 200.0;
        L.addAll(mirrored("odctl_d0", d0, SEEDS2));
        for (long s : SEEDS4) {
            Config nd = odNative(); nd.seed = s; nd.noDepletionControl = true;
            L.add(new Arm("odctl_nodep_s" + s + "_native", nd));
        }
        return L;
    }

    static final double[] VISC = {0.001, 0.01, 0.1};
    static List<Arm> viscArms() {
        List<Arm> L = new ArrayList<>();
        for (double e : VISC) {
            Config c = odNative(); c.etaPaS = e;
            L.addAll(mirrored(String.format(Locale.ROOT, "odvisc_e%.4g", e), c, SEEDS2));
        }
        return L;
    }

    static List<Arm> listFor(String stage) {
        return switch (stage) {
            case "paper" -> paperArms(); case "native" -> nativeArms();
            case "controls" -> controlArms(); case "visc" -> viscArms();
            default -> allArms();
        };
    }
    static List<Arm> allArms() {
        List<Arm> L = new ArrayList<>();
        L.addAll(paperArms()); L.addAll(nativeArms()); L.addAll(controlArms()); L.addAll(viscArms());
        return L;
    }

    /* =============================== campaign driver =============================== */

    static void campaign(List<Arm> arms) throws IOException {
        for (Arm arm : arms) {
            if (Files.exists(Path.of(RUNDIR, arm.id() + ".json"))) { System.out.printf("  [skip] %s%n", arm.id()); continue; }
            runArm(arm);
        }
    }
    static void runOneArm(String id) throws IOException {
        for (Arm arm : allArms()) if (arm.id().equals(id)) {
            if (Files.exists(Path.of(RUNDIR, id + ".json"))) { System.out.printf("[skip] %s%n", id); return; }
            runArm(arm); return;
        }
        System.out.println("unknown arm id: " + id);
    }
    static void runArm(Arm arm) throws IOException {
        System.out.printf("  [run] %s ... ", arm.id()); System.out.flush();
        VilfanCompleteSystem sys = new VilfanCompleteSystem(arm.cfg());
        Result R = sys.run();
        R.armId = arm.id();
        Path dst = Path.of(RUNDIR, arm.id() + ".json"), tmp = Path.of(dst + ".tmp");
        Files.writeString(tmp, VilfanCompleteHarness.toJson(R, sys));
        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.printf("v=%.4g om=%.4g pitch=%.4g <xA>=%.3f turns=%.1f [%.0fs]%n",
                R.velUmPerS, R.omegaRadPerS, R.pitchUmPerTurn, R.meanXa, R.turns, R.wallClockS);
    }

    /* =============================== Stage 0 — drag audit =============================== */

    static void dragAudit() {
        System.out.println("\n== STAGE 0 — WHOLE-FILAMENT DRAG AUDIT ==\n");
        System.out.println("Source: DragTensorSystem.rodDragSI (port of BoA-v1ref FilSegment.calculateProperties:420-435).");
        System.out.println("Used:   bTGx (axial translation) and bRGx (ROLL about the long axis) ONLY.\n");
        double L = 5.5, Rr = VilfanDrag.RADIUS_UM;
        for (double eta : new double[]{0.001, 0.01, 0.1}) {
            System.out.printf("-- eta = %.4g Pa*s --%n%s%n", eta, VilfanDrag.audit(eta, L, Rr));
        }
        // gate DRAG-1: exact agreement with a literal transcription of rodDragSI at Constants.aeta
        double eta = VilfanDrag.AETA_SOFTBOX, LM = L * 1e-6, RM = Rr * 1e-6;
        double logT = Math.log(LM / (2 * RM));
        double refX  = (2 * Math.PI * eta * LM) / (logT + (-0.20));
        double refTh = 4 * Math.PI * eta * RM * RM * LM;
        double gx = VilfanDrag.gammaXSI(eta, L, Rr), gt = VilfanDrag.gammaThetaSI(eta, L, Rr);
        gate("DRAG-1 formula == literal rodDragSI transcription at Constants.aeta",
                gx == refX && gt == refTh,
                String.format(Locale.ROOT, "gammaX %.12g vs %.12g ; gammaTheta %.12g vs %.12g", gx, refX, gt, refTh));
        // the timescale separation that decides this whole study
        System.out.println("\n-- timescale separation at the primary point (eta = 0.01, N_b ~ 80) --");
        double gX = VilfanDrag.gammaXwork(0.01, L, Rr), gT = VilfanDrag.gammaThetaWork(0.01, L, Rr);
        double tX = gX / (80 * 0.5), tT = gT / (80 * 4 * 4.14);
        System.out.printf("   tauX     = gammaX/(Nb K)      = %.4g s%n", tX);
        System.out.printf("   tauTheta = gammaTheta/(Nb Kth)= %.4g s%n", tT);
        System.out.printf("   mean chemical inter-event interval (quasi-static reference) ~ 6.9e-4 s%n");
        System.out.printf("   ratios: tauX/dt = %.3g , tauTheta/dt = %.3g%n", tX / 6.9e-4, tT / 6.9e-4);
        System.out.printf("   => drag becomes comparable to the chemistry near eta ~ %.3g Pa*s%n", 0.01 * 6.9e-4 / tT);
    }

    /* =============================== Stage 2 — gates =============================== */

    static int pass = 0, fail = 0;
    static void gate(String name, boolean ok, String detail) {
        System.out.printf("  [%s] %-56s %s%n", ok ? "PASS" : "FAIL", name, detail);
        if (ok) pass++; else fail++;
    }

    static void gates() throws IOException {
        System.out.println("\n== STAGE 2 — NUMERICAL VALIDATION OF THE OVERDAMPED MODE ==\n");
        gateA(); gateB(); gateC(); gateD(); gateE(); gateF(); gateG();
        System.out.printf("%n  gates: %d PASS, %d FAIL%n", pass, fail);
        if (fail > 0) System.out.println("  *** NOT CLEAN — do not launch physics campaigns ***");
    }

    /* ---- A. mechanical relaxation: analytic solution vs RK4 of the same ODE ---- */
    static void gateA() {
        System.out.println("A. mechanical relaxation (analytic vs RK4 of the same equations of motion)");
        Config c = odPaper();
        VilfanCompleteSystem s = new VilfanCompleteSystem(c);
        double gX = VilfanDrag.gammaXwork(c.etaPaS, c.lengthUm, c.filRadiusUm);
        double gT = VilfanDrag.gammaThetaWork(c.etaPaS, c.lengthUm, c.filRadiusUm);

        // a prepared fixed bound set: nb heads on assorted sites with assorted anchors
        int nb = 7;
        int[] site = {100, 213, 377, 514, 668, 811, 940};
        double[] anch = new double[nb];
        for (int j = 0; j < nb; j++) anch[j] = site[j] * c.aNm + 0.37 * (j - 3);
        double d = c.dNm;

        // targets and time constants
        double Xeq = 0, Teq = 0;
        for (int j = 0; j < nb; j++) Xeq += anch[j] + d - site[j] * c.aNm;
        Xeq /= nb;
        double X0 = Xeq + 3.0, T0 = 0.11;
        int[] nbr = new int[nb];
        for (int j = 0; j < nb; j++) nbr[j] = -(int) Math.rint((T0 + s.baseAzim(site[j])) / TWO_PI);
        for (int j = 0; j < nb; j++) Teq += s.baseAzim(site[j]) + TWO_PI * nbr[j];
        Teq = -Teq / nb;
        double tauX = gX / (nb * c.K), tauT = gT / (nb * c.kTheta());

        // RK4 of  gX dX/dt = sum F ,  gT dTheta/dt = sum M   (torque via wrapPi — no branch integers)
        int N = 2_000_000;
        double tEnd = 8 * Math.max(tauX, tauT), h = tEnd / N;
        double x = X0, th = T0;
        for (int n = 0; n < N; n++) {
            double k1x = fX(s, anch, site, nb, d, c, x) / gX,        k1t = fT(s, site, nb, c, th) / gT;
            double k2x = fX(s, anch, site, nb, d, c, x + h/2*k1x)/gX, k2t = fT(s, site, nb, c, th + h/2*k1t)/gT;
            double k3x = fX(s, anch, site, nb, d, c, x + h/2*k2x)/gX, k3t = fT(s, site, nb, c, th + h/2*k2t)/gT;
            double k4x = fX(s, anch, site, nb, d, c, x + h*k3x)/gX,   k4t = fT(s, site, nb, c, th + h*k3t)/gT;
            x  += h/6*(k1x + 2*k2x + 2*k3x + k4x);
            th += h/6*(k1t + 2*k2t + 2*k3t + k4t);
        }
        double xa = VilfanCompleteSystem.relaxPublic(Xeq, X0, tauX, tEnd);
        double ta = VilfanCompleteSystem.relaxPublic(Teq, T0, tauT, tEnd);
        gate("A1 X(t): analytic exponential == RK4", Math.abs(xa - x) < 1e-9 * Math.max(1, Math.abs(x)),
                String.format(Locale.ROOT, "analytic %.12f vs RK4 %.12f nm (tauX = %.4g s)", xa, x, tauX));
        gate("A2 Theta(t): analytic exponential == RK4", Math.abs(ta - th) < 1e-9 * Math.max(1, Math.abs(th)),
                String.format(Locale.ROOT, "analytic %.12f vs RK4 %.12f rad (tauTheta = %.4g s)", ta, th, tauT));

        // first-order dynamic balance at a sampled time
        double sMid = 0.7 * tauT;
        double xm = VilfanCompleteSystem.relaxPublic(Xeq, X0, tauX, sMid);
        double tm = VilfanCompleteSystem.relaxPublic(Teq, T0, tauT, sMid);
        double xdot = -(X0 - Xeq) / tauX * Math.exp(-sMid / tauX);
        double tdot = -(T0 - Teq) / tauT * Math.exp(-sMid / tauT);
        double rF = Math.abs(gX * xdot - fX(s, anch, site, nb, d, c, xm));
        double rM = Math.abs(gT * tdot - fT(s, site, nb, c, tm));
        gate("A3 gammaX*Xdot == sum F (dynamic balance)", rF < 1e-12,
                String.format(Locale.ROOT, "residual %.3g pN", rF));
        gate("A4 gammaTheta*Thetadot == sum M", rM < 1e-12,
                String.format(Locale.ROOT, "residual %.3g pN*nm", rM));

        // A5/A6 branch crossings: an independent segmented-analytic propagation (locate each
        // +-pi crossing, relax exponentially to it, re-assign the branch, continue) must agree
        // with a brute-force RK4 that uses the wrapPi torque directly and knows nothing about
        // branch integers. This validates the branch algebra the production path relies on;
        // gate B3 then ties the production code itself to the same invariant.
        // A wrapped start bounds |ThetaEq - Theta0| by pi, so crossings are possible but not
        // guaranteed for an arbitrary start. Scan for the start angle that produces the most.
        double T0b = Teq + 3.0; int bestCross = -1;
        for (int q2 = 0; q2 < 400; q2++) {
            double cand = -Math.PI + TWO_PI * q2 / 400.0;
            int[] nc = new int[1];
            propagateThetaRef(s, site, nb, gT, c.kTheta(), cand, 40 * (gT / (nb * c.kTheta())), nc);
            if (nc[0] > bestCross) { bestCross = nc[0]; T0b = cand; }
        }
        double tEnd2 = 40 * tauT;
        int Nb2 = 4_000_000; double h2 = tEnd2 / Nb2;
        double thb = T0b;
        for (int n = 0; n < Nb2; n++) {
            double k1 = fT(s, site, nb, c, thb) / gT;
            double k2 = fT(s, site, nb, c, thb + h2/2*k1) / gT;
            double k3 = fT(s, site, nb, c, thb + h2/2*k2) / gT;
            double k4 = fT(s, site, nb, c, thb + h2*k3) / gT;
            thb += h2/6*(k1 + 2*k2 + 2*k3 + k4);
        }
        int[] nCross = new int[1];
        double thRef = propagateThetaRef(s, site, nb, gT, c.kTheta(), T0b, tEnd2, nCross);
        gate("A5 segmented-analytic branch propagation == RK4",
                nCross[0] > 0 && Math.abs(thRef - thb) < 1e-7 * Math.max(1, Math.abs(thb)),
                String.format(Locale.ROOT, "analytic %.10f vs RK4 %.10f rad, %d branch crossings from Theta0 = %.4f",
                        thRef, thb, nCross[0], T0b));
        gate("A6 branch-crossing endpoint is torque-balanced", Math.abs(fT(s, site, nb, c, thRef)) < 1e-9,
                String.format(Locale.ROOT, "|sum M| = %.3g pN*nm at Theta = %.6f", Math.abs(fT(s, site, nb, c, thRef)), thRef));
    }

    /** independent reference: propagate Theta through +-pi branch crossings analytically. */
    static double propagateThetaRef(VilfanCompleteSystem s, int[] site, int nb, double gT, double kTh,
                                    double T0, double tEnd, int[] nCross) {
        int[] n = new int[nb];
        double th = T0;
        for (int j = 0; j < nb; j++) n[j] = -(int) Math.rint((th + s.baseAzim(site[j])) / TWO_PI);
        double tau = gT / (nb * kTh), t = 0;
        for (int guard = 0; guard < 100000 && t < tEnd; guard++) {
            double sum = 0; for (int j = 0; j < nb; j++) sum += s.baseAzim(site[j]) + TWO_PI * n[j];
            double Teq = -sum / nb;
            if (th == Teq) break;
            boolean up = Teq > th;
            double best = Double.POSITIVE_INFINITY; int bj = -1;
            for (int j = 0; j < nb; j++) {
                double off = s.baseAzim(site[j]) + TWO_PI * n[j];
                double Tc = (up ? Math.PI : -Math.PI) - off;
                if (up ? !(Tc > th && Tc < Teq) : !(Tc < th && Tc > Teq)) continue;
                double ss = -tau * Math.log((Tc - Teq) / (th - Teq));
                if (ss > 0 && ss < best) { best = ss; bj = j; }
            }
            double step = Math.min(best, tEnd - t);
            th = Teq + (th - Teq) * Math.exp(-step / tau);
            t += step;
            if (bj >= 0 && step == best) { n[bj] += up ? -1 : +1; nCross[0]++; }
        }
        return th;
    }

    static double fX(VilfanCompleteSystem s, double[] anch, int[] site, int nb, double d, Config c, double x) {
        double f = 0; for (int j = 0; j < nb; j++) f += s.headForce(x, anch[j], site[j], d); return f;
    }
    static double fT(VilfanCompleteSystem s, int[] site, int nb, Config c, double th) {
        double m = 0; for (int j = 0; j < nb; j++) m += s.headTorque(th, site[j]); return m;
    }

    /* ---- B. continuity of X and Theta through every chemical event ---- */
    static void gateB() {
        System.out.println("B. continuity through chemical events");
        Config c = odPaper(); c.travelUm = 0.20; c.warmupUm = 0.02; c.turnsTarget = 0;
        c.travelCapUm = 2.0; c.seed = 4242;
        Result R = new VilfanCompleteSystem(c).run();
        gate("B1 X is continuous at every event (no applied displacement)", R.maxEventJumpX == 0.0,
                String.format(Locale.ROOT, "max |dX| across an event = %.3g nm over %d events", R.maxEventJumpX, R.nEvents));
        gate("B2 Theta is continuous at every event", R.maxEventJumpTheta == 0.0,
                String.format(Locale.ROOT, "max |dTheta| = %.3g rad", R.maxEventJumpTheta));
        gate("B3 dynamic closure holds over the whole run", R.maxDynResidF < 1e-9 && R.maxDynResidM < 1e-9,
                String.format(Locale.ROOT, "max |gammaX*Xdot - sumF| = %.3g pN ; |gammaTh*Thdot - sumM| = %.3g pN*nm",
                        R.maxDynResidF, R.maxDynResidM));
    }

    /* ---- C. the time-dependent hazard sampler ---- */
    static void gateC() {
        System.out.println("C. piecewise-deterministic hazard sampler");
        // C1 constant-hazard fixture: K = 0 and alpha = 0 make every site hazard exactly kA and
        // remove all force, so k_total is state-independent and waiting times must be exponential.
        Config f = odPaper(); f.K = 0; f.alpha = 0; f.window = 3; f.lengthUm = 0.5;
        f.turnsTarget = 0; f.warmupUm = -1.0; f.travelUm = 1e9; f.travelCapUm = 5.0; f.maxSimTimeS = 60.0;
        Result F = new VilfanCompleteSystem(f).run();
        long mn = Math.min(Math.min(F.nAttach, F.nStroke), Math.min(F.nAdpRelease, F.nDetach));
        long mx = Math.max(Math.max(F.nAttach, F.nStroke), Math.max(F.nAdpRelease, F.nDetach));
        gate("C1 constant-hazard fixture: cycle counts equal", mn > 500 && (mx - mn) <= 0.03 * mx + 5,
                String.format(Locale.ROOT, "attach %d PS %d ADP %d detach %d", F.nAttach, F.nStroke, F.nAdpRelease, F.nDetach));
        double rigorTime = F.stateOccupancy[3] * (F.tEnd - F.tWarm) * F.nMotors;
        double dwell = (F.nDetach > 0) ? rigorTime / F.nDetach : 0;
        gate("C2 constant-hazard fixture: rigor dwell == 1/kD", Math.abs(dwell - 1 / f.kD) / (1 / f.kD) < 0.05,
                String.format(Locale.ROOT, "%.5g s vs %.5g s", dwell, 1 / f.kD));

        // C3 independent fine-grid cross-check of the located cumulative hazard, on the real path
        Config x = odPaper(); x.travelUm = 0.05; x.warmupUm = 0.005; x.turnsTarget = 0;
        x.travelCapUm = 1.0; x.seed = 909; x.hazardCrossCheck = true; x.hazardCrossGrid = 20000;
        Result X = new VilfanCompleteSystem(x).run();
        gate("C3 cumulative hazard == independent 20k-point trapezoid", X.xcheckMaxRel < 1e-6 && X.xcheckN > 100,
                String.format(Locale.ROOT, "max rel dev %.3g over %d checked events", X.xcheckMaxRel, X.xcheckN));

        // C4 convergence under tighter tolerances and a longer settle horizon
        System.out.println("     tolerance convergence (paper lattice, seed 777, 0.4 um travel):");
        double ref = 0; boolean conv = true;
        double[] tols = {1e-6, 1e-8, 1e-10, 1e-12};
        double[] got = new double[tols.length];
        for (int i = 0; i < tols.length; i++) {
            Config t = odPaper(); t.travelUm = 0.4; t.warmupUm = 0.05; t.turnsTarget = 0;
            t.travelCapUm = 2.0; t.seed = 777; t.hazTolRel = tols[i];
            got[i] = new VilfanCompleteSystem(t).run().omegaRadPerS;
        }
        ref = got[tols.length - 1];
        for (int i = 0; i < tols.length; i++) {
            double rel = Math.abs(got[i] - ref) / Math.abs(ref);
            System.out.printf("       hazTolRel=%-7.0e  omega = %.12f  rel dev %.3g%n", tols[i], got[i], rel);
            if (tols[i] <= 1e-10 && rel > 1e-9) conv = false;
        }
        gate("C4 result converged at the production tolerance 1e-10", conv, "see table above");
        double[] sfGot = new double[3]; double[] sfs = {20, 40, 60};
        for (int i = 0; i < 3; i++) {
            Config t = odPaper(); t.travelUm = 0.4; t.warmupUm = 0.05; t.turnsTarget = 0;
            t.travelCapUm = 2.0; t.seed = 777; t.settleFactor = sfs[i];
            sfGot[i] = new VilfanCompleteSystem(t).run().omegaRadPerS;
        }
        gate("C5 result independent of the settle horizon (20/40/60 tau)",
                Math.abs(sfGot[0] - sfGot[2]) / Math.abs(sfGot[2]) < 1e-9 && Math.abs(sfGot[1] - sfGot[2]) / Math.abs(sfGot[2]) < 1e-9,
                String.format(Locale.ROOT, "omega %.12f / %.12f / %.12f", sfGot[0], sfGot[1], sfGot[2]));
    }

    /* ---- D. quasi-static limit recovery ---- */
    static void gateD() {
        System.out.println("D. quasi-static limit: dragScale -> 0 must recover the validated reference");
        // Compared at a MATCHED EVENT COUNT, not a travel threshold. A travel threshold ends the
        // two runs at different events (the last event straddles the boundary), which shows up as a
        // ~1/nEvents window-boundary offset in v and omega and masks the actual convergence.
        Config q = odPaper(); q.mechanics = "quasistatic";
        q.warmupUm = -1.0; q.travelUm = 1e9; q.turnsTarget = 0; q.travelCapUm = 2.0;
        q.maxEvents = 12000; q.seed = 909;   // 12000 events travel ~0.3 um, far inside the cap
        Result Q = new VilfanCompleteSystem(q).run();
        System.out.printf("     quasi-static @ 12000 events: X = %.12f nm  Theta = %.12f rad  t = %.12f s%n",
                Q.xEnd, Q.thetaEnd, Q.tEnd);
        double lx = 1, lt = 1, lth = 1, dxAbs = 1, dthAbs = 1, rxa = 1, rth = 1;
        for (double sc : new double[]{1.0, 1e-1, 1e-2, 1e-3, 1e-4, 1e-6}) {
            Config o = q.copy(); o.mechanics = "overdamped"; o.dragScale = sc; o.etaPaS = VilfanDrag.ETA_ASSAY;
            Result O = new VilfanCompleteSystem(o).run();
            lx  = Math.abs(O.xEnd - Q.xEnd) / Math.abs(Q.xEnd);
            lth = Math.abs(O.thetaEnd - Q.thetaEnd) / Math.abs(Q.thetaEnd);
            lt  = Math.abs(O.tEnd - Q.tEnd) / Q.tEnd;
            dxAbs = Math.abs(O.xEnd - Q.xEnd); dthAbs = Math.abs(O.thetaEnd - Q.thetaEnd);
            rxa = rel(O.meanXa, Q.meanXa); rth = rel(O.meanThA, Q.meanThA);
            System.out.printf("       dragScale=%-7.0e t %.12f (%.2e)  <xA> %.12f (%.1e)  dX %.4f nm  dTheta %.5f rad%n",
                    sc, O.tEnd, lt, O.meanXa, rxa, dxAbs, dthAbs);
        }
        // What must converge is the EVENT SEQUENCE and the EVENT TIMES. X_end and Theta_end retain a
        // one-event offset by construction: the quasi-static run calls equilibrate() AFTER its last
        // event, so it reports the NEW equilibrium, whereas the overdamped run stops AT the event and
        // has not yet been given the time to relax into it. The offset must therefore be bounded by
        // one event's shift of the target, ~d/N_b.
        double oneEvent = 8.0 / Math.max(1, Q.medianNb);
        gate("D1 event times converge to the quasi-static values", lt < 1e-10,
                String.format(Locale.ROOT, "rel dt = %.2e at dragScale = 1e-6 (scales exactly with dragScale)", lt));
        gate("D2 attachment statistics converge to the quasi-static values", rxa < 1e-12 && rth < 1e-12,
                String.format(Locale.ROOT, "<xA> rel dev %.2e, <thA> rel dev %.2e (bit-identical sequence)", rxa, rth));
        gate("D3 residual X, Theta offset is the one-event relaxation lag", dxAbs < oneEvent && dthAbs < 0.02,
                String.format(Locale.ROOT, "dX = %.4f nm vs one-event d/N_b = %.4f nm ; dTheta = %.5f rad (N_b = %.0f)",
                        dxAbs, oneEvent, dthAbs, Q.medianNb));
    }

    /* ---- E. reproducibility ---- */
    static void gateE() {
        System.out.println("E. reproducibility");
        Config c = odPaper(); c.travelUm = 0.3; c.warmupUm = 0.05; c.turnsTarget = 0;
        c.travelCapUm = 2.0; c.seed = 31337;
        Result A = new VilfanCompleteSystem(c).run();
        Result B = new VilfanCompleteSystem(c.copy()).run();
        gate("E1 same seed is bit-reproducible", A.xEnd == B.xEnd && A.thetaEnd == B.thetaEnd && A.nEvents == B.nEvents,
                String.format(Locale.ROOT, "X %.17g vs %.17g, %d vs %d events", A.xEnd, B.xEnd, A.nEvents, B.nEvents));
    }

    /* ---- F. mirror symmetry ---- */
    static void gateF() {
        System.out.println("F. mirror symmetry under finite drag");
        Config n = odPaper(); n.travelUm = 0.4; n.warmupUm = 0.05; n.turnsTarget = 0;
        n.travelCapUm = 2.0; n.seed = 909;
        Config m = n.copy(); m.latSign = +1;
        Result N = new VilfanCompleteSystem(n).run();
        Result M = new VilfanCompleteSystem(m).run();
        gate("F1 X trajectories identical under mirroring", Math.abs(N.xEnd - M.xEnd) < 1e-9,
                String.format(Locale.ROOT, "dX = %.3g nm", N.xEnd - M.xEnd));
        gate("F2 Theta trajectories are exact negatives", Math.abs(N.thetaEnd + M.thetaEnd) < 1e-9,
                String.format(Locale.ROOT, "%.10f vs %.10f", N.thetaEnd, M.thetaEnd));
        gate("F3 Omega_even is numerical zero", Math.abs(0.5 * (N.omegaRadPerS + M.omegaRadPerS)) < 1e-12,
                String.format(Locale.ROOT, "Omega_even = %.3g rad/s", 0.5 * (N.omegaRadPerS + M.omegaRadPerS)));
        gate("F4 <x_A> preserved, <theta_A> reversed", Math.abs(N.meanXa - M.meanXa) < 1e-9
                        && Math.abs(N.meanThA + M.meanThA) < 1e-12,
                String.format(Locale.ROOT, "<xA> %.6f/%.6f  <thA> %+.6f/%+.6f", N.meanXa, M.meanXa, N.meanThA, M.meanThA));
    }

    /* ---- G. the existing reference is untouched ---- */
    static double rel(double a, double b) { return Math.abs(a - b) / Math.max(1e-300, Math.abs(b)); }

    static void gateG() throws IOException {
        System.out.println("G. the validated quasi-static reference is unchanged");
        Path ref = Path.of("REFERENCE_RECORDS", "paper_kd0.1000_a4.00_s101_native.json");
        if (!Files.exists(ref)) { gate("G1 quasi-static arm reproduces its committed record", false,
                "REFERENCE_RECORDS not present — copy RUN_LOGS/vilfan_complete from the parent worktree"); return; }
        var want = VilfanCompleteHarness.parse(Files.readString(ref));
        Config c = VilfanCompleteHarness.paperBase(); c.seed = 101; c.latSign = -1;
        Result R = new VilfanCompleteSystem(c).run();
        // The stored record is written with %.10g, so it carries only 10 significant digits.
        // Exact equality is therefore unattainable BY THE RECORD FORMAT, not by the physics:
        // agreement to the record's printed precision is the strongest available statement.
        double rv  = rel(R.velUmPerS,    VilfanCompleteHarness.d(want, "velUmPerS"));
        double rom = rel(R.omegaRadPerS, VilfanCompleteHarness.d(want, "omegaRadPerS"));
        double rxa = rel(R.meanXa,       VilfanCompleteHarness.d(want, "meanXaNm"));
        gate("G1 quasi-static arm reproduces its record to its printed precision",
                rv < 1e-9 && rom < 1e-9 && rxa < 1e-9,
                String.format(Locale.ROOT, "rel dev v %.2e, omega %.2e, <xA> %.2e (record holds 10 sig figs)",
                        rv, rom, rxa));
    }
}
