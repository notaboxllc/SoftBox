package softbox;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import softbox.VilfanCompleteSystem.Config;
import softbox.VilfanCompleteSystem.Result;

/**
 * VILFAN LOW-OCCUPANCY BROWNIAN CONFINEMENT AND PHASE-MEMORY STUDY — CPU only, default off.
 * <p>
 * The parent roll-Brownian result was obtained at a median of ~82 bound heads, where collective
 * stiffness confines axial motion to 0.32 nm and roll to 0.056 rad. This study asks whether that
 * robustness is intrinsic to target-zone depletion or merely a consequence of collective clamping.
 * <pre>
 *   -vilfan-lowocc -calib        Stage 8 occupancy calibration (short arms, both control axes)
 *   -vilfan-lowocc -pilot        Stage 9 two-seed mechanism pilot at the selected regimes
 *   -vilfan-lowocc -list &lt;st&gt; | -arm &lt;id&gt;
 * </pre>
 * Two INDEPENDENT axes lower occupancy and are never assumed equivalent:
 * <ul>
 *   <li><b>density</b> — fewer motors under the filament, chemistry untouched;</li>
 *   <li><b>duty</b> — the motor pool kept, {@code kD} raised so bound lifetime falls.</li>
 * </ul>
 * At matched mean occupancy these give very different zero-bound gap durations, and therefore
 * different phase memory. That is the study's central preregistered prediction.
 */
public final class VilfanLowOccHarness {

    static final String RUNDIR = "RUN_LOGS/vilfan_lowocc";
    /** calibration arms are short: occupancy statistics converge far faster than Omega. */
    static final double CAL_WARM = 2.0, CAL_ANALYSIS = 8.0;
    /** pilot/production window, matched to the parent studies. */
    static final double WARM = 24.0, ANALYSIS = 120.0;

    public static void main(String[] a0) throws Exception {
        List<String> a = List.of(a0);
        if (!a.contains("-vilfan-lowocc")) { System.out.println("refusing: need -vilfan-lowocc"); return; }
        Files.createDirectories(Path.of(RUNDIR));
        System.err.println("=== VILFAN LOW-OCCUPANCY — CPU only, no CUDA/TornadoVM/TaskGraph ===");
        if (a.contains("-scales")) { scales(); return; }
        if (a.contains("-calib"))  { camp(calibArms()); return; }
        if (a.contains("-pilot"))  { camp(pilotArms()); return; }
        if (a.contains("-gates"))  { camp(gateArms()); return; }
        if (a.contains("-s1ext")) { camp(s1ExtArms()); return; }
        int il = a.indexOf("-list");
        if (il >= 0) { for (Arm m : listFor(il+1 < a.size() ? a.get(il+1) : "all"))
            if (!Files.exists(Path.of(RUNDIR, m.id()+".json"))) System.out.println(m.id()); return; }
        int ia = a.indexOf("-arm");
        if (ia >= 0 && ia+1 < a.size()) { runOne(a.get(ia+1)); return; }
        System.out.println("no mode");
    }

    /* ============================ base configuration ============================ */

    /** the frozen parent condition: native lattice, BOTH noises on, repaired roll controller. */
    static Config base() {
        Config c = new Config();
        c.latP=37; c.latQ=80; c.aNm=2.7; c.latSign=-1;
        c.alpha=4.0; c.kD=5.0;
        c.mechanics="overdamped"; c.etaPaS=VilfanDrag.ETA_ASSAY;
        c.axialBrownian=true; c.rollBrownian=true;      // full Brownian, per the study freeze
        c.anchorDtS=5.0e-6; c.refineLevel=0; c.rollSigCapRad=0.05;
        c.occDiagnostics=true;
        c.warmupTimeS=WARM; c.analysisTimeS=ANALYSIS;
        c.travelCapUm=20.0; c.maxEvents=40_000_000L;
        return c;
    }

    record Arm(String id, Config cfg) {}

    /* ---------------- Stage 8: occupancy calibration ----------------
     * Density axis: scale rho down. Duty axis: raise kD at fixed rho.
     * Targets: Regime H (~80), M (~10), S3 (~3), S2 (~2), S1 (~1), Z (substantial P(0)).
     * Values are SELECTED from this map, not assumed. */
    static final double[] CAL_RHO = {20.0, 6.0, 2.0, 1.0, 0.6, 0.35, 0.2};
    static final double[] CAL_KD  = {5.0, 40.0, 150.0, 400.0, 900.0, 1800.0, 3600.0};
    /** Regime-K axis: lower kA at FIXED density and kD. Velocity v = kD(d + xi_A) is independent of
     *  kA, so this lowers occupancy WITHOUT the ~100x velocity increase the kD axis introduces. */
    static final double[] CAL_KA  = {50.0, 5.0, 1.0, 0.5, 0.25, 0.12, 0.06};

    static List<Arm> calibArms() {
        List<Arm> L = new ArrayList<>();
        for (double r : CAL_RHO) {
            Config c = base(); c.densityPerUm = r; c.seed = 101;
            c.warmupTimeS = CAL_WARM; c.analysisTimeS = CAL_ANALYSIS;
            L.add(new Arm(String.format(Locale.ROOT, "cal_dens_r%.3g", r), c));
        }
        for (double k : CAL_KD) {
            Config c = base(); c.kD = k; c.seed = 101;
            c.warmupTimeS = CAL_WARM; c.analysisTimeS = CAL_ANALYSIS;
            L.add(new Arm(String.format(Locale.ROOT, "cal_duty_kd%.4g", k), c));
        }

        for (double k : CAL_KA) {
            Config c = base(); c.kA = k; c.seed = 101;
            // longer window: at low kA the gaps are ~100 ms, so 8 s would give too few
            c.warmupTimeS = CAL_WARM; c.analysisTimeS = 20.0;
            L.add(new Arm(String.format(Locale.ROOT, "cal_ka_ka%.4g", k), c));
        }
        Config g = base(); g.kD = 1800.0; g.seed = 101;
        g.warmupTimeS = 1.0; g.analysisTimeS = 2.0;
        L.add(new Arm("dbg_acct", g));
        return L;
    }

    /* ---------------- Stage 9: mechanism pilot ----------------
     * THREE regimes, FROZEN from the Stage-8 calibration map before any mechanism arm was run.
     *
     *   H   rho = 20,   kA = 50    N_b = 82.99  P(0) = 0        no gaps at all
     *   S1  rho = 0.35, kA = 50    N_b =  1.58  P(0) = 0.031    mean gap 20.5 ms
     *   K   rho = 20,   kA = 0.12  N_b =  1.40  P(0) = 0.216    mean gap 140 ms
     *
     * Regime K was selected on the two PREREGISTERED channels only -- mean N_b in [1,3], and zone
     * passage time (or velocity) within 2x of S1:
     *   N_b 1.396 in [1,3]; v 0.0641 vs S1 0.0652 um/s = 0.98x; zone passage 0.454 vs 0.767 s = 1.69x.
     * kA = 0.25 was rejected (zone passage 5.4x, velocity 72x off); kA = 0.5 gives N_b = 5.2 (too
     * high); kA = 0.06 gives N_b = 0.55 (too low). No twirling or depletion quantity was consulted.
     *
     * STRUCTURAL FINDING, reported rather than tuned around: lowering kA LENGTHENS the zero-bound gap
     * (140 ms) instead of shortening it, because the reattachment flux at N_b = 0 is rho_reach * kA --
     * 20 * 0.12 = 2.4 in K against 0.35 * 50 = 17.5 in S1. So K is NOT the "fast reattachment" control
     * the brief anticipated; no such control exists in this model, since low occupancy at native
     * velocity requires low attachment flux (rho or kA) while short gaps require high attachment flux,
     * and the only lever that shortens bound lifetime without touching flux is kD, which sets the
     * velocity v = kD (d + <xi_A>) directly. What K actually delivers is better suited to the question:
     * a MATCHED-OCCUPANCY, MATCHED-VELOCITY regime with a 7x LONGER gap than S1, so K vs S1 isolates
     * gap duration -- hence phase memory -- at fixed occupancy and fixed velocity.
     */
    /**
     * @param pad extra motor field on BOTH sides, um. Only the SPARSE regime needs it: at
     *   rho = 0.35 /um the 5.5 um filament has just 1.9 motors under it, so a Poisson field leaves it
     *   MOTOR-FREE e^-1.925 = 15 % of the time, and it then free-diffuses sqrt(2 D_X t) ~ 4.7 um over
     *   the window, in either direction -- off the unpadded field, which begins at -margin. At
     *   rho = 20 /um there are ~110 motors under the filament at all times, a motor-free stretch is
     *   impossible, and the pad would only add ~800 motors to every O(n_M) per-substep scan (2.6x
     *   slower) for no effect.
     */
    record Regime(String label, double rho, double kA, double pad) {}
    static final Regime[] REGIMES = {
        new Regime("H",  20.0, 50.0,  0.0),
        new Regime("S1",  0.35, 50.0, 20.0),
        new Regime("K",  20.0,  0.12,  0.0),
    };

    /**
     * 12 arms = 3 regimes x 2 mirror signs x 2 seeds. Each arm carries its OWN matched-path shadow
     * (noDepletionControl is a purely additive read-out: it consumes no RNG, removes no motor and
     * applies no force), so the shadow is measured on the SAME realised X and Theta trajectory and is
     * labelled with that trajectory's own gap history -- rather than on a separately generated path.
     * Gate MP-1 checks the trajectory is bit-identical with the read-out on and off.
     */
    static List<Arm> pilotArms() {
        List<Arm> L = new ArrayList<>();
        for (Regime g : REGIMES)
            for (long s : new long[]{101, 102})
                for (int sign : new int[]{-1, +1}) {
                    Config c = base();
                    c.densityPerUm = g.rho(); c.kA = g.kA(); c.fieldPadUm = g.pad();
                    c.latSign = sign;
                    c.seed = (sign < 0) ? s : s + 500_000L;   // independent-noise mirror
                    c.fieldSeed = s;                         // ...on the SAME motor field
                    c.noDepletionControl = true;              // matched shadow, same path
                    L.add(new Arm(String.format(Locale.ROOT, "pil_%s_s%d_%s", g.label(), s,
                            sign < 0 ? "native" : "mirror"), c));
                }
        return L;
    }

    /* ---------------- short gates ----------------
     * MP-1  matched-path integrity: shadow read-out on vs off must leave the path bit-identical.
     * PW-*  PATHWISE mirror: same seed, lattice mirrored, roll noise sign flipped (mirror-ODD) and
     *       axial noise left alone (mirror-EVEN). Under an exact mirror the roll must reverse and the
     *       even part must sit at the numerical floor. Short by design -- these test symmetry of the
     *       map, not an ensemble average.
     */
    static List<Arm> gateArms() {
        List<Arm> L = new ArrayList<>();
        for (Regime g : REGIMES) {
            Config a = base(); a.densityPerUm = g.rho(); a.kA = g.kA(); a.fieldPadUm = g.pad();
            a.seed = 101;
            a.warmupTimeS = 2.0; a.analysisTimeS = 6.0;
            Config b = cloneOf(a); b.noDepletionControl = true;
            L.add(new Arm("gate_mp1off_" + g.label(), a));
            L.add(new Arm("gate_mp1on_"  + g.label(), b));
            Config n = cloneOf(a);                                  // pathwise mirror pair
            Config m = cloneOf(a); m.latSign = +1; m.rollNoiseSign = -1; m.fieldSeed = a.seed;
            L.add(new Arm("gate_pw_nat_" + g.label(), n));
            L.add(new Arm("gate_pw_mir_" + g.label(), m));
        }
        return L;
    }

    static Config cloneOf(Config a) {
        Config c = base();
        c.densityPerUm = a.densityPerUm; c.kA = a.kA; c.kD = a.kD; c.latSign = a.latSign;
        c.fieldPadUm = a.fieldPadUm;
        c.fieldSeed = a.fieldSeed;
        c.seed = a.seed; c.warmupTimeS = a.warmupTimeS; c.analysisTimeS = a.analysisTimeS;
        c.noDepletionControl = a.noDepletionControl; c.rollNoiseSign = a.rollNoiseSign;
        return c;
    }

    /* ---------------- S1 sufficiency extension ----------------
     * Runs only if the pilot's S1 gap-count / first-post-gap gates fall short. The brief's remedy is a
     * longer fixed duration in all three regimes; this instead extends the DURATION 4x and the number
     * of FIELD REALISATIONS to 8, in S1 only, for two stated reasons:
     *   1. what limits S1 is not run length but the motor-field realisation -- with only rho*l = 1.9
     *      motors under the filament, seeds 101 and 102 gave N_b = 3.74 and 3.57 with 6 and 9 gaps,
     *      while another field gave N_b = 0.72 with 443 gaps. A longer run on one field measures one
     *      local environment more precisely, which is the wrong quantity;
     *   2. K costs ~3 h of CPU per 120 s of simulated time, so matching a 4x extension in all three
     *      regimes would cost ~50 h for no gain in the load-bearing comparison, which is within-S1.
     * H and K already pass their own sufficiency gates at 120 s.
     */
    static List<Arm> s1ExtArms() {
        List<Arm> L = new ArrayList<>();
        Regime g = REGIMES[1];                        // S1
        for (long s : new long[]{101, 102, 103, 104, 105, 106, 107, 108})
            for (int sign : new int[]{-1, +1}) {
                Config c = base();
                c.densityPerUm = g.rho(); c.kA = g.kA(); c.fieldPadUm = g.pad();
                c.latSign = sign;
                c.seed = (sign < 0) ? s : s + 500_000L;
                c.fieldSeed = s;
                c.noDepletionControl = true;
                c.warmupTimeS = 24.0; c.analysisTimeS = 480.0;
                L.add(new Arm(String.format(Locale.ROOT, "s1x_s%d_%s", s,
                        sign < 0 ? "native" : "mirror"), c));
            }
        return L;
    }

    static List<Arm> listFor(String st) {
        return switch (st) { case "calib" -> calibArms(); case "pilot" -> pilotArms(); case "gates" -> gateArms(); case "s1ext" -> s1ExtArms();
                             default -> allArms(); };
    }
    static List<Arm> allArms() {
        List<Arm> L = new ArrayList<>(); L.addAll(calibArms()); L.addAll(gateArms()); L.addAll(pilotArms());
        L.addAll(s1ExtArms()); return L;
    }

    static void camp(List<Arm> arms) throws IOException {
        for (Arm m : arms) {
            if (Files.exists(Path.of(RUNDIR, m.id()+".json"))) { System.out.printf("  [skip] %s%n", m.id()); continue; }
            run(m);
        }
    }
    static void runOne(String id) throws IOException {
        for (Arm m : allArms()) if (m.id().equals(id)) {
            if (Files.exists(Path.of(RUNDIR, id+".json"))) { System.out.printf("[skip] %s%n", id); return; }
            run(m); return; }
        System.out.println("unknown arm: " + id);
    }
    static void run(Arm m) throws IOException {
        System.out.printf("  [run] %s ... ", m.id()); System.out.flush();
        VilfanCompleteSystem sys = new VilfanCompleteSystem(m.cfg());
        Result R = sys.run(); R.armId = m.id();
        Path d = Path.of(RUNDIR, m.id()+".json"), t = Path.of(d+".tmp");
        Files.writeString(t, VilfanCompleteHarness.toJson(R, sys));
        Files.move(t, d, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.printf("Nb=%.2f(med %.0f) P0=%.4f gaps=%d gap=%.3gs C_Th=%.3f C_X=%.3f "
                        + "v=%.5f om=%+.4f <xA>=%.3f [%.0fs]%n",
                R.occMean, R.medianNb, R.pZero, R.nGaps, R.meanGapS, R.cThetaMem, R.cXMem,
                R.velUmPerS, R.omegaRadPerS, R.meanXa, R.wallClockS);
    }

    /* ============================ Stage 0/1 analytic scales ============================ */

    static void scales() {
        Config c = base();
        double gX = VilfanDrag.gammaXwork(c.etaPaS, c.lengthUm, c.filRadiusUm);
        double gT = VilfanDrag.gammaThetaWork(c.etaPaS, c.lengthUm, c.filRadiusUm);
        double DX = c.kBT / gX, DT = c.kBT / gT, L = 36.0;
        System.out.println("\n== CONFINEMENT AND PHASE-MEMORY SCALES (preregistered) ==\n");
        System.out.printf("  D_X = %.4g nm^2/s   D_Theta = %.4g rad^2/s   L_zone = %.1f nm%n%n", DX, DT, L);
        System.out.println("  Nb    sd_X (nm)  sd_Theta (rad)  tau_X (s)    tau_Theta (s)");
        for (int nb : new int[]{80, 10, 3, 2, 1}) {
            System.out.printf("  %-5d %9.3f  %14.4f  %.3e   %.3e%n", nb,
                    Math.sqrt(c.kBT/(nb*c.K)), Math.sqrt(c.kBT/(nb*c.kTheta())),
                    gX/(nb*c.K), gT/(nb*c.kTheta()));
        }
        double tTh = 1.0/DT, tX = 1.0/(Math.pow(2*Math.PI/L,2)*DX);
        System.out.printf("%n  free-roll 1/e angular memory time = %.3f ms%n", tTh*1e3);
        System.out.printf("  free-axial 1/e memory time        = %.3f ms  (axial lost %.1fx faster)%n",
                tX*1e3, tTh/tX);
        System.out.println("\n  gap (ms)   C_Theta   C_X");
        for (double t : new double[]{0.05,0.1,0.2,0.5,1,2,5,10,20})
            System.out.printf("  %-9.2f  %.4f    %.4f%n", t,
                    Math.exp(-DT*t*1e-3), Math.exp(-Math.pow(2*Math.PI/L,2)*DX*t*1e-3));
    }
}
