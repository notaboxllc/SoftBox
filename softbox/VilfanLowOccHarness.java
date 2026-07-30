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
        return L;
    }

    /* ---------------- Stage 9: mechanism pilot ----------------
     * Populated from the calibration map. Left as a named list so the selected parameter values are
     * frozen in code before the pilot runs. */
    static final Object[][] REGIMES = {
        // {label, "dens"|"duty", value}
        {"H",  "dens", 20.0},
        {"M",  "dens", 2.0},
        {"S1", "dens", 0.35},
        {"S1d","duty", 1800.0},
    };

    static List<Arm> pilotArms() {
        List<Arm> L = new ArrayList<>();
        for (Object[] r : REGIMES) {
            String lab = (String) r[0], axis = (String) r[1]; double v = (Double) r[2];
            for (long s : new long[]{101, 102}) {
                for (int sign : new int[]{-1, +1}) {
                    Config c = base();
                    if (axis.equals("dens")) c.densityPerUm = v; else c.kD = v;
                    c.seed = (sign < 0) ? s : s + 500_000L;   // independent-noise mirror
                    c.latSign = sign;
                    L.add(new Arm(String.format(Locale.ROOT, "pil_%s_s%d_%s", lab, s,
                            sign < 0 ? "native" : "mirror"), c));
                }
                Config sh = base();
                if (axis.equals("dens")) sh.densityPerUm = v; else sh.kD = v;
                sh.seed = s; sh.noDepletionControl = true;
                L.add(new Arm(String.format(Locale.ROOT, "pilshadow_%s_s%d", lab, s), sh));
            }
        }
        return L;
    }

    static List<Arm> listFor(String st) {
        return switch (st) { case "calib" -> calibArms(); case "pilot" -> pilotArms();
                             default -> allArms(); };
    }
    static List<Arm> allArms() {
        List<Arm> L = new ArrayList<>(); L.addAll(calibArms()); L.addAll(pilotArms()); return L;
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
