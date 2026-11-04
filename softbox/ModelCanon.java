package softbox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * CANONICAL MODEL MANIFEST for the site-normal gliding / twirling assay (SiteNormalLongGlideHarness).
 *
 * <p>Why this exists (jba, 2026-11-02/03): realism features kept being dropped silently when other parts of the model
 * changed (the contact-patch triad on 2026-10-25 is the case that forced this). Every decided feature of the current
 * model is one ROW here: its canonical value, a getter that reads the value the run ACTUALLY resolved (after argument
 * parsing and after cfg(), which clobbers several fields), and the basis for the choice. Each run:
 * <ol>
 *   <li>starts from the canonical preset ({@link #PRESET}, prepended to the command line; later flags override it);</li>
 *   <li>after the scene is resolved, compares every row against canon ({@link #verify}); any row that differs and is not
 *       named in {@code -deviate <id>[,<id>...]} STOPS the run before any wall-clock is spent;</li>
 *   <li>prints the manifest and writes it to run_config.json ({@link #json}).</li>
 * </ol>
 * {@code -canon none} turns all of this off and prints a NON-CANONICAL banner (old scripts, regression replays).
 *
 * <p>Rules for changing the model: a change that alters a row's canonical value, adds a row, or removes one bumps
 * {@link #VERSION}, regenerates the golden trajectory (scripts/canon_gate.sh), and is recorded in
 * docs/MODEL_CANON.md and JOURNAL.md as a decision by jba. A new realism feature is not part of the model until it
 * has a row. Removing a row is a decision, never a side effect.
 *
 * <p>Scope: MODEL rows are the motor/actin physics; FIXTURE rows are the assay physics we hold fixed (solvent, walls,
 * surface); NUMERICS rows are dt-type choices. Assay CONDITIONS (ATP, density, lawn, filament length, seeds, steps) and
 * the RUNNER (GPU flags) are recorded but not checked: they are what a campaign varies.
 */
final class ModelCanon {
    static final String VERSION = "v5";
    static final String DATE = "2026-11-03";

    /** CANON V4 swing-plane tilt (deg). Sign from structure: right-handed swing (Klebl 2025; 8R9V->8RBF measured +4.5..6.4,
     *  jba 2026-11-03). The twirl handedness is now an OUTPUT; Beausang 2008 measures left-handed for myosin II and V. */
    static final double PSI_DEG = 4.0;

    static boolean ACTIVE = true;                       // -canon none => false
    static final Set<String> DEVIATE = new TreeSet<>();  // -deviate id,id,...
    static List<String> deviations = new ArrayList<>(); // filled by verify()
    static boolean VERIFIED = false;                    // verify() ran (SiteNormal only); gates verifyKernel

    /** Canonical flags, prepended to the user's command line. Everything else canonical is already a code default. */
    static final String[] PRESET = {
            "-eta", "0.01", "-dt", "1.25e-7", "-atp-k", "5",
            "-head-chain", "-swing-tilt", String.valueOf(PSI_DEG), "-stroke-skew", "0",
            "-rate-bind", "1e6", "-swivel-dock", "-head-floor", "-f8-tip-fix",
            "-stroke-window", "-115,-45", "-convaz-nocomp",
            "-no-rupture",
            "-z-slab-hi-nm", "200",
            "-rollspring", "-rollstiff", "40", "-pairs", "0.1", "1.0", "0.003" };

    enum Kind { MODEL, FIXTURE, NUMERICS }
    record Row(String id, Kind kind, String canon, Supplier<String> now, String basis) {}

    static String on(boolean b) { return b ? "on" : "off"; }
    /** Compact 6-significant-figure form (23.4, -4, 1000000, 0.000000125, NaN): the comparison key for numeric rows. */
    static String g(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
        return new java.math.BigDecimal(String.format(Locale.US, "%.6g", v)).stripTrailingZeros().toPlainString();
    }

    static final List<Row> ROWS = List.of(
        // ---- motor head and its actin interface
        new Row("head_chain", Kind.MODEL, "on", () -> on(HeadChainSystem.ON),
                "converter axis fixed in the head; bound head docked on its full frame (2026-10-25)"),
        new Row("pliant_hinge_kappa_pNnm", Kind.MODEL, "23.4", () -> g(HeadChainSystem.KAPPA_PN_NM),
                "head-lever pliant hinge, Billington 2014"),
        new Row("dock", Kind.MODEL, "on", () -> on(ExplicitCompleteMatHarness.SWIVEL_DOCK),
                "stereospecific dock, k_bind 512 pN.nm/rad^2; rigor FWHM ~15 deg (Thomas & Cooke 1980) => >~330"),
        new Row("triad", Kind.MODEL, "off", () -> on(ExplicitCompleteMatHarness.TRIAD_ON),
                "contact-patch triad backed out: redundant with the dock (jba 2026-11-02, JOURNAL)"),
        new Row("f8_tip_fix", Kind.MODEL, "on", () -> on(ExplicitCompleteMatHarness.F8_TIP_FIX),
                "bond acts at the geometric F8 tip (7 nm), not 20 nm HEAD_LEN"),
        new Row("swing_tilt_deg", Kind.MODEL, g(PSI_DEG), () -> g(ExplicitCompleteMatHarness.SWING_TILT_DEG),
                "lever swing plane off the actin axis, right-handed; Klebl 2025 (Nature 642:519) 4 deg right-handed; sign from structure (v4)"),
        new Row("stroke_skew_deg", Kind.MODEL, "0", () -> g(SiteNormalLongGlideHarness.STROKE_SKEW_DEG),
                "eps anchor shift retired (jba 2026-11-03); 8R9V/8RBF conserved-core contacts do not move (-0.2 deg)"),
        new Row("binding_skew_deg", Kind.MODEL, "0", () -> g(ExplicitCompleteMatHarness.EPS_BIND_DEG), "no chiral input at binding"),
        new Row("conv_skew_deg", Kind.MODEL, "0", () -> g(SiteNormalLongGlideHarness.CONV_SKEW_DEG), "superseded by swing_tilt"),
        new Row("mirror", Kind.MODEL, "1", () -> g(SiteNormalLongGlideHarness.MIRROR), "native handedness (no mirror runs, jba)"),
        new Row("registry_k", Kind.MODEL, "0", () -> g(SiteNormalLongGlideHarness.REG_K_NM), "registry spring inert"),
        new Row("f8_tan_nm", Kind.MODEL, "0", () -> g(SiteNormalLongGlideHarness.F8_TAN_CLI), "no head-side tangential shift"),
        new Row("head_tilt_deg", Kind.MODEL, "0", () -> g(SiteNormalLongGlideHarness.HEAD_TILT_CLI), "-headtilt artifact (free pre-stroke swing) excluded"),
        new Row("two_point_bond", Kind.MODEL, "off", () -> on(SiteNormalLongGlideHarness.TWO_POINT), "exploratory, not canon"),
        new Row("soft_scales", Kind.MODEL, "1 1 1 1", () -> g(SiteNormalLongGlideHarness.SOFT_CONV) + " " + g(SiteNormalLongGlideHarness.SOFT_S2A)
                + " " + g(SiteNormalLongGlideHarness.SOFT_S2B) + " " + g(SiteNormalLongGlideHarness.SOFT_KBIND), "no stiffness softening"),
        new Row("kbind_bound_only", Kind.MODEL, "off", () -> on(SiteNormalLongGlideHarness.KBIND_BOUND_ONLY_CLI), "-"),
        new Row("straight_rest", Kind.MODEL, "on", () -> on(SiteNormalLongGlideHarness.STRAIGHTREST), "detached head collinear with the neck (2026-08-17)"),
        // ---- stroke and chemistry
        new Row("stroke_window_deg", Kind.MODEL, "-115,-45", () -> g(SiteNormalLongGlideHarness.STROKE_WINDOW_PRE_CLI) + ","
                + g(SiteNormalLongGlideHarness.STROKE_WINDOW_POST_CLI), "70 deg lever swing ADP.Pi -> ADP"),
        new Row("stroke_swing_override", Kind.MODEL, "NaN", () -> g(SiteNormalLongGlideHarness.STROKE_SWING_CLI), "no swing override"),
        new Row("conv_az_comp", Kind.MODEL, "off", () -> on(SiteNormalLongGlideHarness.CONV_AZ_COMP_CLI), "-convaz-nocomp"),
        new Row("conv_az_deg", Kind.MODEL, "0", () -> g(SiteNormalLongGlideHarness.CONV_AZ_CLI), "-"),
        new Row("rate_bind_k0", Kind.MODEL, "1000000", () -> RateBindSystem.ON ? g(RateBindSystem.K0) : "off",
                "single-stage Duke-style capture, k0 per site; capture scored against the psi-turned dock target (v3 fix)"),
        new Row("rate_window_nm", Kind.MODEL, "8", () -> g(RateBindSystem.WINDOW_NM), "computational window only"),
        new Row("rate_kori", Kind.MODEL, "derived", () -> RateBindSystem.KORI_PN_NM > 0 ? g(RateBindSystem.KORI_PN_NM) : "derived",
                "dock in series with the pliant hinge (22.4 pN.nm/rad^2)"),
        new Row("weak_bind", Kind.MODEL, "off", () -> on(WeakBindSystem.ON), "dropped for rate capture (2026-10-26)"),
        new Row("capture_lit", Kind.MODEL, "off", () -> on(ExplicitCompleteMatHarness.CAPTURE_LIT), "-"),
        new Row("release_tau_us", Kind.MODEL, "30", () -> g(SiteNormalLongGlideHarness.RELEASE_TAU_US), "catch-slip reads a 30 us bond-force average (2026-10-27)"),
        new Row("rigor_rupture", Kind.MODEL, "off", () -> on(ChiralSiteHarness.RUPTURE),
                "OPEN (jba): off in every glide/twirl run since the head chain; decide before low-ATP canon"),
        new Row("atp_k_per_uM_s", Kind.MODEL, "5", () -> ChiralSiteHarness.ATP_LEGACY_LINEAR ? "legacy-linear" : g(ChiralSiteHarness.ATP_K_PER_UM_S),
                "single-molecule fast skeletal myosin, Capitanio 2012 raw data (PMC6565606): 76 /s at 15 uM, zero load (v5; was 2.4 solution acto-S1, Ritchie 1993); used only with -atp-uM"),
        new Row("s2_catch", Kind.MODEL, "1 1", () -> g(SiteNormalLongGlideHarness.S2_CATCH) + " " + g(SiteNormalLongGlideHarness.S2_LOADCATCH), "inert"),
        // ---- motor body, S2, lawn
        new Row("motor_steric", Kind.MODEL, "off", () -> on(ExplicitCompleteMatHarness.MOTOR_STERIC), "no motor-actin sterics (jba 2026-10-28)"),
        new Row("head_floor", Kind.MODEL, "on", () -> on(ExplicitCompleteMatHarness.HEAD_FLOOR), "glass floor on head + lever end"),
        new Row("s2_swivel", Kind.MODEL, "on", () -> on(SiteNormalLongGlideHarness.SWIVEL_CLI), "S2 base joint azimuth-free (2026-10-08)"),
        new Row("s2_one_surface", Kind.FIXTURE, "on", () -> on(SiteNormalLongGlideHarness.S2_ONE_SURFACE_CLI), "S2 floor = coverslip (2026-10-08)"),
        new Row("s2_hinge_x_kb", Kind.MODEL, "0.1", () -> g(SiteNormalLongGlideHarness.S2_HINGE_CLI), "soft base hinge (unmeasured; scan deferred, jba)"),
        new Row("s2_lift_init", Kind.MODEL, "on", () -> on(SiteNormalLongGlideHarness.S2_LIFT_INIT_CLI), "per-motor hemisphere initial lift"),
        new Row("rand_base", Kind.FIXTURE, "on", () -> on(SiteNormalLongGlideHarness.RANDBASE), "random motor orientation (2026-10-04)"),
        new Row("cull_sites_rotated", Kind.NUMERICS, "on", () -> on(ExplicitCompleteMatHarness.RANDBASE_CULL_FIX), "-"),
        new Row("cull_queryr_nm", Kind.NUMERICS, "110 110", () -> g(SiteNormalLongGlideHarness.CULL_QUERYR_CLI) + " " + g(SiteNormalLongGlideHarness.SWIVEL_QUERYR_CLI), "-"),
        new Row("s2dir_gate", Kind.MODEL, "off", () -> SiteNormalLongGlideHarness.S2DIR_GATE_CLI >= 0 ? g(SiteNormalLongGlideHarness.S2DIR_GATE_CLI) : "off", "diagnostic only"),
        // ---- actin
        new Row("site_lattice", Kind.MODEL, "every4 two-strand", () -> (ChiralSiteHarness.PATH_B_SITE_MODE == 3 ? "every4" : "mode" + ChiralSiteHarness.PATH_B_SITE_MODE)
                + (ExplicitCompleteMatHarness.TWO_STRAND_SITES ? " two-strand" : " one-strand"), "sparse helical sites"),
        new Row("helix_flip", Kind.MODEL, "off", () -> on(SiteNormalLongGlideHarness.FLIP_HELIX_CLI), "native actin handedness"),
        new Row("pairs_chain", Kind.MODEL, "0.1 1 0.003", () -> SiteNormalLongGlideHarness.PAIRS_CLI == null ? (SiteNormalLongGlideHarness.PAIRS_TABLE != null ? "table" : "default")
                : g(SiteNormalLongGlideHarness.PAIRS_CLI[0]) + " " + g(SiteNormalLongGlideHarness.PAIRS_CLI[1]) + " " + g(SiteNormalLongGlideHarness.PAIRS_CLI[2]),
                "flexible chain, thermal Lp ~10 um (2026-11-01)"),
        new Row("roll_spring", Kind.MODEL, "40", () -> SiteNormalLongGlideHarness.ROLL_SPRING_CLI ? g(SiteNormalLongGlideHarness.ROLL_STIFF_CLI) : "off",
                "inter-segment twist coupling; without it chain roll is uncoupled"),
        new Row("roll_rest", Kind.MODEL, "derived", () -> Double.isNaN(SiteNormalLongGlideHarness.ROLL_REST_CLI) ? "derived" : g(SiteNormalLongGlideHarness.ROLL_REST_CLI), "-"),
        new Row("rod_rot_fdt", Kind.MODEL, "on", () -> on(ChiralSiteHarness.ROD_FDT), "rotational Brownian at FDT (2026-10-04)"),
        new Row("brownian", Kind.MODEL, "all", () -> SiteNormalLongGlideHarness.FIL_BROWN_ALL && SiteNormalLongGlideHarness.ROLL_BROWN ? "all" : "ABLATED", "-"),
        // ---- fixture and numerics
        new Row("eta_Pa_s", Kind.FIXTURE, "0.01", () -> g(SiteNormalLongGlideHarness.ETA), "publication viscosity"),
        new Row("z_slab", Kind.FIXTURE, "ends 0-200nm", () -> (SiteNormalLongGlideHarness.Z_SLAB_CLI ? (SiteNormalLongGlideHarness.Z_SLAB_ENDS_CLI ? "ends " : "centre ") : "OFF ")
                + g(ExplicitCompleteMatHarness.Z_SLAB_LO_NM) + "-" + g(SiteNormalLongGlideHarness.Z_SLAB_HI_CLI) + "nm", "hard slab, walls at rod ends; ceiling numerical"),
        new Row("dt_s", Kind.NUMERICS, "0.000000125", () -> g(SiteNormalLongGlideHarness.DT), "-"));

    // ------------------------------------------------------------------ command line
    /** Strip -canon / -deviate; prepend the preset when active and the mode runs the assay (-run / -chain-equiv). */
    static String[] preprocess(String[] args) {
        List<String> rest = new ArrayList<>();
        boolean assay = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-canon" -> { String v = args[++i];
                    if (v.equals("none")) ACTIVE = false;
                    else if (!v.equals(VERSION)) throw new IllegalArgumentException("-canon " + v + ": this binary carries canon " + VERSION + " (or 'none')"); }
                case "-deviate" -> DEVIATE.addAll(Arrays.asList(args[++i].split(",")));
                default -> { if (args[i].equals("-run") || args[i].equals("-chain-equiv")) assay = true; rest.add(args[i]); }
            }
        }
        for (String d : DEVIATE)
            if (ROWS.stream().noneMatch(r -> r.id.equals(d))) throw new IllegalArgumentException("-deviate " + d + ": no such manifest row");
        if (!ACTIVE || !assay) return rest.toArray(new String[0]);
        List<String> out = new ArrayList<>(Arrays.asList(PRESET));
        out.addAll(rest);
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------------ check + report
    /** Call once the scene is fully resolved. Prints the manifest; throws if an undeclared deviation is present. */
    static void verify() {
        deviations = new ArrayList<>(); VERIFIED = true;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%n  ===== MODEL MANIFEST canon %s (%s) : %s =====%n", VERSION, DATE,
                ACTIVE ? "CANONICAL PRESET" : "*** NON-CANONICAL (-canon none): preset and check OFF ***"));
        List<String> undeclared = new ArrayList<>();
        for (Row r : ROWS) {
            String now = r.now.get();
            boolean dev = !now.equals(r.canon);
            if (dev) deviations.add(r.id);
            if (dev && ACTIVE && !DEVIATE.contains(r.id)) undeclared.add(r.id + " = " + now + " (canon " + r.canon + ")");
            if (dev || r.kind != Kind.NUMERICS)
                sb.append(String.format(Locale.US, "  %-8s %-24s %-20s %s%n", r.kind, r.id,
                        dev ? now + "  [DEVIATES from " + r.canon + (DEVIATE.contains(r.id) ? ", declared" : "") + "]" : now,
                        dev ? "" : r.basis));
        }
        for (String d : DEVIATE) if (!deviations.contains(d)) sb.append("  NOTE: -deviate " + d + " declared but the row is canonical\n");
        sb.append(String.format(Locale.US, "  deviations: %s%n", deviations.isEmpty() ? "none" : String.join(", ", deviations)));
        System.out.print(sb);
        if (!undeclared.isEmpty())
            throw new IllegalArgumentException("canon " + VERSION + ": undeclared deviation(s) from the canonical model -- "
                    + String.join("; ", undeclared) + ". Declare with -deviate <id>[,<id>] or run -canon none (docs/MODEL_CANON.md).");
    }

    /** Kernel-level cross-check, called where the head-chain parameters are packed (hp, sbP): the canonical values
     *  must reach the buffers the kernels read, not just the static fields. */
    static void verifyKernel(double kappaJ, double kDockJ, double psiRad, double captureKoriJ) {
        if (!ACTIVE || !VERIFIED) return;
        List<String> bad = new ArrayList<>();
        if (!deviations.contains("pliant_hinge_kappa_pNnm") && Math.abs(kappaJ * 1e21 - 23.4) > 1e-9) bad.add("hp[0] kappa " + kappaJ * 1e21);
        if (!deviations.contains("dock") && Math.abs(kDockJ * 1e21 - 512.0) > 1e-6) bad.add("dock k " + kDockJ * 1e21);
        if (!deviations.contains("swing_tilt_deg") && !deviations.contains("mirror") && Math.abs(psiRad * 180 / Math.PI - PSI_DEG) > 1e-9) bad.add("hp[3] psi " + psiRad * 180 / Math.PI);
        if (!deviations.contains("rate_kori") && !deviations.contains("pliant_hinge_kappa_pNnm") && captureKoriJ > 0
                && Math.abs(captureKoriJ * 1e21 - 1.0 / (1.0 / 512.0 + 1.0 / 23.4)) > 1e-6) bad.add("capture k_ori " + captureKoriJ * 1e21);
        if (!bad.isEmpty()) throw new IllegalStateException("canon " + VERSION + ": kernel buffers disagree with the manifest: " + String.join("; ", bad));
        System.out.printf(Locale.US, "  MANIFEST KERNEL CHECK: kappa %.1f, dock %.0f, psi %+.2f deg, capture k_ori %.2f pN.nm/rad^2 -- as packed%n",
                kappaJ * 1e21, kDockJ * 1e21, psiRad * 180 / Math.PI, captureKoriJ * 1e21);
    }

    /** JSON block for run_config.json. */
    static String json() {
        StringBuilder sb = new StringBuilder("{\"version\": \"" + VERSION + "\", \"active\": " + ACTIVE + ", \"deviations\": [");
        for (int i = 0; i < deviations.size(); i++) sb.append(i > 0 ? ", " : "").append('"').append(deviations.get(i)).append('"');
        sb.append("], \"rows\": {");
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            sb.append(i > 0 ? ", " : "").append('"').append(r.id).append("\": \"").append(r.now.get().replace("\"", "'")).append('"');
        }
        return sb.append("}}").toString();
    }
}
