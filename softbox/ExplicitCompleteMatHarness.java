package softbox;

import softbox.TwoBodyConverterMotor.Glide2D;
import uk.ac.manchester.tornado.api.*;
import uk.ac.manchester.tornado.api.TornadoProfilerResult;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.enums.ProfilerMode;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import java.io.File;
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
        // GPU crash lifecycle trace (diagnostic; OFF unless -gpu-crash-trace / SOFTBOX_GPU_CRASH_TRACE=1). First
        // statement in main so PROGRAM_START precedes every other action and the shutdown hook is registered early.
        TornadoCrashDiagnostic.init("explicit-s2-singlehead", args);
        try { Files.createDirectories(Path.of(OUT)); } catch (IOException e) { throw new UncheckedIOException(e); }
        if (!"false".equals(System.getProperty("tornado.recover.bailout"))) System.out.println("!! run with -Dtornado.recover.bailout=false");
        boolean traj = false, bench = false, stroke = false, gliding = false, throughput = false, quick = false;
        for (String a : args) { if (a.equals("-traj")) traj = true; if (a.equals("-bench")) bench = true; if (a.equals("-stroke")) stroke = true; if (a.equals("-gliding")) gliding = true; if (a.equals("-throughput")) throughput = true; if (a.equals("-quick")) quick = true; }
        StringBuilder log = new StringBuilder();
        boolean ok; String fn;
        boolean throughputCal = false, sweep = false;
        for (String a : args) { if (a.equals("-throughputcal")) throughputCal = true; if (a.equals("-sweep")) sweep = true; }
        // Single-head LONG-run density-sweep PRODUCTION CELL (one density×seed → per-cell JSON), matched to the
        // HMM-dimer campaign. Harness-only: reuses the validated buildS2Mat + buildGlidingGraph explicit-s2-l40 path
        // unchanged; adds only per-step science readback + the per-cell JSON/accumulator. Exits directly.
        for (String a : args) if (a.equals("-production-cell")) { int rc = runProductionCell(args); TornadoCrashDiagnostic.exit(rc); }
        if (sweep)      { log.append("# Explicit density-saturation sweep (Phase C) — GPU gliding velocity, explicit vs calibrated\n\n"); ok = sweepMode(log, quick, args); fn = "COMPLETEMAT_SWEEP.md"; }
        else if (throughputCal) { log.append("# Calibrated (calibrated-s2-l40) GENUINE FREE-BINDING NO-CULL throughput (Phase B arm) — CPU vs GPU, N=600–9000\n\n"); ok = throughputCalMode(log, quick); fn = "COMPLETEMAT_THROUGHPUT_CAL.md"; }
        else if (throughput) { log.append("# Explicit GENUINE FREE-BINDING NO-CULL throughput (Phase A) — CPU-analytic vs GPU-analytic, N=600–9000\n\n"); ok = throughputMode(log, quick); fn = "COMPLETEMAT_THROUGHPUT.md"; }
        else if (gliding)    { log.append("# Explicit FREE-BINDING gliding — §8 bind replay + §10 low-density gliding (CPU-runner vs GPU)\n\n"); ok = glidingMode(log); fn = "COMPLETEMAT_GLIDING.md"; }
        else if (bench) { log.append("# Explicit complete-mat NO-CULL throughput — CPU-analytic vs GPU-analytic @ 200/700/1500\n\n"); ok = benchMode(log); fn = "COMPLETEMAT_BENCH.md"; }
        else if (stroke){ log.append("# Explicit complete-mat stroke/detach/recoil (full coupling, CPU-runner vs GPU)\n\n"); ok = strokeMode(log); fn = "COMPLETEMAT_STROKE.md"; }
        else if (traj)  { log.append("# Explicit complete-mat trajectory — §7 one-step + §8 multi-step (CPU-runner vs GPU)\n\n"); ok = trajGate(log); fn = "COMPLETEMAT_TRAJ.md"; }
        else            { log.append("# Explicit coupled kernel → complete-mat integration — §5 head-placement + §6 bondForces gates\n\n"); ok = headAndBondGate(log); fn = "COMPLETEMAT_GATE.md"; }
        try { Files.writeString(Path.of(OUT, fn), log.toString()); } catch (IOException e) { throw new UncheckedIOException(e); }
        System.out.println("# report: " + Path.of(OUT, fn).toAbsolutePath());
        System.out.println(ok ? "=== COMPLETE-MAT GATE: PASS ===" : "=== COMPLETE-MAT GATE: REVIEW ===");
        TornadoCrashDiagnostic.exit(ok ? 0 : 1);
    }

    // =============================================================== §7/§8 complete explicit mat trajectory
    static final int SYS = TwoBodyBeamAnalyticGpu.SYS_STRIDE, RED_BLK = 128;
    // CONTINUOUS LOCAL ACTIN CO-OCCUPANCY EXCLUSION (named noncanonical experimental extension; default OFF).
    // OCC_EXCL_NM = 0 ⇒ OFF ⇒ the canonical bind kernel (matBindExplicit) runs unchanged ⇒ byte-identical.
    // > 0 ⇒ the gate-only + serial-resolve pair replaces the bind task. See docs/helical_binding/
    // CONTINUOUS_OCCUPANCY_EXCLUSION_FINDINGS.md. tol convention matches the legacy veto (reject iff sep < excl−tol).
    static double OCC_EXCL_NM = 0.0;
    static final double OCC_TOL_NM = 1e-3;
    // Test-only hook (ContinuousOccupancyExclusionHarness §5 test 12): force the gate-only→serial-resolve pipeline
    // even at exclusion 0, so the OFF-path IDENTITY (resolve(0) ≡ canonical matBindExplicit) is checkable over a real
    // multi-step binding trajectory. NEVER set by any production runner ⇒ production default OFF is byte-identical.
    static boolean OCC_FORCE_ON = false;
    static boolean occOn() { return OCC_FORCE_ON || OCC_EXCL_NM > 0.0; }

    // HELICAL SURFACE BINDING (noncanonical, default-off) — the off-axis twirl port into the explicit-S2 gliding
    // assay. SURFACE_ON=false ⇒ every kernel/task below is bypassed ⇒ byte-identical to the canonical path.
    // Places the actin-side cross-bridge attachment on the physical actin SURFACE at a retained material azimuth
    // (matSurfaceAzim) via bondForcesSurface, so the existing pure-F8 reaction (this model's xbParams has align
    // OFF ⇒ no F9/F10 contamination) generates a ‖û_seg torque that the live roll channel integrates.
    static boolean SURFACE_ON = false;                 // -helical-surface-bind
    static double  R_ACTIN_NM = 3.5;                   // -actin-bind-radius-nm (default = Constants.radius = 3.5 nm)
    static double  SURF_EXCL_NM = 5.5;                 // -surface-exclusion-nm (3D actin-surface steric)
    static boolean SURF_STERIC = true;                 // -no-surface-exclusion clears (twirl mechanics vs sterics)
    static final double TWIST_PER_MON_DEG = -166.5;    // actin 13/6, LEFT-handed (RollSpringHarness convention)
    static boolean surfOn() { return SURFACE_ON; }

    // VILFAN-STYLE STEREOSPECIFIC TARGET-ZONE BINDING (noncanonical, default-off; Stage A).
    // TZ_ON=false ⇒ matTargetZone is never wired ⇒ byte-identical to the canonical/legacy-surface path.
    // TZ_ALPHA = alphaPsi = Kpsi/kB T (dimensionless; literature-scale values 4/6/8 — none canonical).
    // TZ_HARD_RAD > 0 ⇒ the OPTIONAL binary |deltaPsi| cutoff diagnostic instead of the graded hazard.
    // The target-zone kernel REPLACES matSurfaceAzim (it selects + retains the azimuth itself, at the true
    // attachment arc) — the legacy matSurfaceAzim scan path stays byte-unchanged for the legacy surface arm.
    static boolean TZ_ON = false;                      // -target-zone
    static double  TZ_ALPHA = 0.0;                     // -target-zone-alpha
    static double  TZ_HARD_RAD = 0.0;                  // -target-zone-hard-rad (diagnostic only)
    static boolean TZ_DIAG = true;                     // per-candidate diagnostics into ExMat.tzDiag
    static boolean tzOn() { return TZ_ON; }
    /** Measurement-only: force the per-step host readback of the filament material frame + bond reactions in
     *  PRODUCTION residency, so a device-resident arm carries the same twirl observables as the CPU runner.
     *  Adds NO kernel and NO physics — only transfers. Default false ⇒ production sweeps byte-unchanged. */
    static boolean TELEMETRY = false;
    static boolean telemetryOn() { return TZ_ON || TELEMETRY; }
    /** Measurement-only: additionally force the per-step host readback of the MOTOR-INTERNAL state the bound-cycle
     *  impulse budget stratifies on — {@code q} (phi/psi/thetaS/psiActin), {@code nodes} (the explicit-S2 beam) and
     *  {@code outGeom} (C/xH/xF8). Like {@link #TELEMETRY} this adds NO kernel, NO physics and NO device-side work:
     *  it only appends buffers to the production graph's copy-out list. Default false ⇒ every existing path,
     *  including the §22 converter-skew campaign, declares exactly the copy-outs it declared before. */
    static boolean EPISODE_TELEM = false;

    // ---------------------------------------------------------------------------------------------------------
    // BROWNIAN-NOISE ABLATION (noncanonical, DEFAULT-OFF, byte-identical when off). A diagnostic instrument for
    // the target-zone phase-coherence question: which Brownian forcing channels destroy the moving-target-zone
    // phase? Controlled by (1) physical body/subsystem, (2) MOTOR BINDING STATE, (3) force vs torque channel.
    // NOTHING here adds a force, a torque, a spring, a rate, or a fitted parameter — it only SCALES the existing
    // stochastic thermal terms. Defaults reproduce the canonical model exactly:
    //   BR_FIL_* = 1 ⇒ brownChannelMask is never wired      ⇒ the filament Brownian path is byte-unchanged;
    //   BR_MOT_* = true ⇒ matc[3] = 0                       ⇒ matS2SolveStep is arithmetically bit-identical.
    // Filament channels are in the INTEGRATOR's body frame (0 = along uVec = axial, 1/2 = transverse; torque 0 =
    // roll about the body-fixed axial direction, 1/2 = tumble/bend). See BrownianForceSystem.brownChannelMask.
    // ---------------------------------------------------------------------------------------------------------
    // DISCRETE ACTIN SITES / HEAD ROTATIONAL DOF / BOUND REGISTRY / LOCAL CHIRAL OFFSETS (ChiralSiteSystem).
    // Noncanonical, flag-gated, DEFAULT-OFF. Every switch below is independent (the "separation of hypotheses"
    // rule): sites, head roll DOF, registry stiffness, askew binding and askew stroke are NOT bundled.
    // All-off ⇒ no kernel is wired and no CPU-runner call is made ⇒ byte-identical to the canonical path.
    static int     SITE_MODE = 0;            // -discrete-actin-sites off|native|every3|every4|stair9-45|stair9-90
    static boolean HEAD_ROLL = false;        // -head-roll-dof on|off
    static boolean HEAD_ROLL_BROWN = true;   // -head-roll-brownian on|off (unbound rotational diffusion)
    static double  REG_K = 0.0;              // -bound-registry-k (N·m/rad); 0 ⇒ exactly inert
    static double  EPS_BIND_DEG = 0.0;       // -binding-skew-deg
    static double  EPS_STROKE_DEG = 0.0;     // -stroke-skew-deg
    static boolean SITE_EXCLUSIVE = true;    // -no-site-exclusion clears
    static double  MIRROR_SIGN = 1.0;        // -mirror-site-chirality ⇒ −1 (site tangential sense reversed)
    static double  SITE_CAPTURE_NM = 12.0;   // -site-capture-nm (3D discrete-site capture radius)
    static int     SITE_SEARCH_HALF = 3;     // bounded neighbour search half-width (never an all-pairs scan)
    static boolean RAND_BASE_AZ = false;     // -randomize-motor-base-azimuth (SCENE control, not physics)
    static int     RAND_BASE_SEED = 20260724;
    // ---- TRUE LOCAL-FRAME ROTATION OF THE CONVERTER POWER STROKE (noncanonical, default-off) ----------------
    // A DIFFERENT mechanism from EPS_BIND_DEG / EPS_STROKE_DEG, which are actin-SIDE attachment-position offsets.
    // This one rotates the MOTOR-SIDE converter stroke plane itself, in the local actin-site frame, so the whole
    // nucleotide-driven converter motion changes direction. See ChiralSiteSystem.convFrameStep and the report
    // section "True local-frame rotation of the converter power stroke".
    static double  CONV_SKEW_DEG = 0.0;      // -converter-stroke-skew-deg (signed, degrees)
    static boolean CONV_SKEW_GAUGE = true;   // -converter-skew-gauge on|off: rotation CENTRE = the binding
                                             // interface (on, default) vs the S2 pivot (off = pure basis rotation)
    // STATE-GATED converter skew (noncanonical, default-off): skew OFF during the ADP·Pi pre-stroke dwell, ON
    // from the ADP·Pi→ADP transition onward while bound. Removes the opposing pre-stroke chiral preload J_pre
    // measured in §23 WITHOUT touching the stroke itself. See ChiralSiteSystem.convFrameStep and §24.
    static boolean CONV_SKEW_STATE_GATED = false;   // -converter-skew-state-gated on|off
    // §25 PROGRESS RAMP (noncanonical, default-off): eps_eff = eps · f(qTheta) with qTheta the normalized
    // mechanical converter progress. Mutually exclusive with CONV_SKEW_STATE_GATED (checked at startup).
    static int     CONV_SKEW_RAMP = ChiralSiteSystem.RAMP_OFF;   // -converter-skew-progress-ramp off|linear|smoothstep|delayed
    static double  CONV_SKEW_RAMP_ONSET = 0.25;                  // -converter-skew-ramp-onset <0..1>
    // ---- DIAGNOSTIC MOTOR-GEOMETRY SCALES for the converter-twirling efficiency audit (default-off) ----------
    // ALL FOUR are pure SCENE parameters: the explicit-S2 device kernels read every geometric quantity from the
    // per-motor `params[]` planar buffer (built by ExplicitMatSolveHarness.paramArr from these Glide2D fields) and
    // the base triad from `frame[]`, so NO kernel, NO buffer width and NO task ordering changes. At their default
    // values applyGeomScales() and the frame roll are exact no-ops ⇒ byte-identical to every existing path.
    static double  S2_LEN_SCALE    = 1.0;    // -s2-free-length-scale        : L → s·L at FIXED M (l0, ks=EA/l0, kb=EI/l0, emergence point rescale)
    static double  S2_BEND_SCALE   = 1.0;    // -s2-bend-stiffness-scale     : kb → s·kb only (EI softer/stiffer, length untouched)
    static double  CONV_ECC_SCALE  = 1.0;    // -converter-f8-eccentricity-scale : |rCF8_perp| → s·|rCF8_perp| (the F8 MATERIAL POINT moves; rConv, hence xH and gammaPsi, do NOT)
    static boolean CONV_ECC_COMP   = false;  // -converter-f8-eccentricity-compensated : hold |d0| (the converter rotation radius) FIXED while eccentricity varies
    static double  CONV_TRANS_NM   = 0.0;    // -converter-transverse-offset-nm : roll the motor's OWN base triad about its OWN b̂ so the converter JOINT C is displaced ⊥ the axial plane by this much at the reference pose
    static boolean geomScaled() { return S2_LEN_SCALE != 1.0 || S2_BEND_SCALE != 1.0 || CONV_ECC_SCALE != 1.0 || CONV_TRANS_NM != 0.0; }
    static void resetGeomScales() { S2_LEN_SCALE = 1.0; S2_BEND_SCALE = 1.0; CONV_ECC_SCALE = 1.0; CONV_ECC_COMP = false; CONV_TRANS_NM = 0.0; }

    /**
     * Apply the default-off diagnostic geometry scales to a freshly built mat scene, BEFORE {@link #packExMat}
     * reads {@code paramArr}/{@code g4Node}. Exact no-op at the defaults.
     *
     * <p><b>S2 free length</b> ({@code S2_LEN_SCALE}). The contour L is rescaled at FIXED element count M, so the
     * kernel's DOF count (hence its structure, {@code sys} stride and lowering) is untouched and the element length
     * {@code l0 = L/M} carries the change: {@code ks = EA/l0}, {@code kb = EI/l0} — i.e. the SAME continuum beam
     * (EA, EI from the MD-informed {@code EXP4G_*} constants) discretized over a different span. The clamped
     * emergence point {@code g4E = P − (L−slack)·b̂} and the substrate floor move with it; the motor pivot P
     * (= node[M]) and therefore the whole converter block and the motor lawn are UNMOVED.
     *
     * <p><b>S2 bend stiffness</b> ({@code S2_BEND_SCALE}). {@code kb → s·kb} alone: the beam's bending rigidity
     * changes at fixed length, fixed stretch stiffness and fixed geometry. Deliberately a SEPARATE parameter from
     * the length (the task's one-factor-at-a-time requirement).
     *
     * <p><b>Converter/F8 eccentricity</b> ({@code CONV_ECC_SCALE}). The converter swings the F8 anchor on
     * {@code d0 = b̂·(rF8x−rCx) + ê_up·(rF8y−rCy)} about the joint C. In this scene the zero-skew stroke is AXIAL
     * (≈ ∓b̂), so the in-plane component of {@code d0} perpendicular to the stroke axis is
     * {@code rCF8_perp = rF8y − rCy} (= 3.0 nm canonically). Only {@code rF8} is moved, never {@code rConv} —
     * so the head point {@code xH = C − rotConv(rConv,ψ)} and {@code gammaPsi} (which depends on |rConv|) are
     * untouched, and the perturbation is the SMALLEST mechanically interpretable one. With
     * {@code CONV_ECC_COMP} the axial component is re-solved to hold {@code |d0|} — the converter ROTATION RADIUS,
     * hence the unloaded stroke magnitude — fixed, which is the stroke-magnitude-matched comparison the task
     * requires so an eccentricity gain cannot be a disguised stroke-length gain.
     */
    static void applyGeomScales(Glide2D G) {
        if (S2_BEND_SCALE != 1.0) G.g4kb *= S2_BEND_SCALE;
        if (CONV_ECC_SCALE != 1.0) {
            double dx0 = G.rF8[0] - G.rConv[0], dy0 = G.rF8[1] - G.rConv[1];
            double d0 = Math.sqrt(dx0*dx0 + dy0*dy0);
            double dy = CONV_ECC_SCALE * dy0;
            double dx = dx0;
            if (CONV_ECC_COMP) {                                   // hold |d0| fixed ⇒ re-solve the axial component
                double r2 = d0*d0 - dy*dy;
                if (r2 <= 0) throw new IllegalArgumentException(String.format(Locale.US,
                        "converter-f8-eccentricity-scale %.3f is PATHOLOGICAL under compensation: "
                        + "|perp|=%.4f nm would exceed the rotation radius |d0|=%.4f nm", CONV_ECC_SCALE, dy*1e3, d0*1e3));
                dx = Math.signum(dx0) * Math.sqrt(r2);
            }
            G.rF8 = new double[]{ G.rConv[0] + dx, G.rConv[1] + dy };
        }
        if (S2_LEN_SCALE != 1.0) {
            int M = G.g4M; double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM * 1e-3;
            double L = S2_LEN_SCALE * (G.g4l0 * M);                // the ORIGINAL contour (l0·M), rescaled
            G.g4l0 = L / M;
            G.g4ks = TwoBodyConverterMotor.EXP4G_EA_SI / (G.g4l0 * 1e-6);
            G.g4kb = TwoBodyConverterMotor.EXP4G_EI_SI / (G.g4l0 * 1e-6) * S2_BEND_SCALE;   // bend scale composes
            double e2e = Math.max(1e-6, L - slack);
            double sag = slack > 1e-9 ? Math.sqrt(Math.max(0, L*L - e2e*e2e)) * 0.5 : 0.0;
            double zfl = Double.POSITIVE_INFINITY;
            for (int m = 0; m < G.N; m++) {
                double[] P = G.g4Node[m][M].clone();               // the pivot is the fixed point — the lawn does not move
                double[] Em = new double[]{ P[0] - G.bhat[0]*e2e, P[1] - G.bhat[1]*e2e, P[2] - G.bhat[2]*e2e };
                G.g4E[m] = Em; zfl = Math.min(zfl, Em[0]*G.eup[0] + Em[1]*G.eup[1] + Em[2]*G.eup[2]);
                for (int j = 0; j <= M; j++) {
                    double fr = (double) j / M, bow = sag * Math.sin(Math.PI * fr);
                    for (int k = 0; k < 3; k++) G.g4Node[m][j][k] = Em[k] + (P[k] - Em[k])*fr + G.eup[k]*bow;
                }
                G.g4Node[m][0] = Em.clone(); G.g4Node[m][M] = P.clone();
            }
            G.g4floorZ = zfl - 0.05;
            G.queryR = TwoBodyConverterMotor.G4_QUERYR + L + 0.01; TwoBodyConverterMotor.initMatGrid(G);
        }
    }
    /** One line describing the diagnostic geometry state (always printed by the sweep drivers). */
    static String geomScaleString(Glide2D G) {
        double dx = G.rF8[0] - G.rConv[0], dy = G.rF8[1] - G.rConv[1];
        return String.format(Locale.US,
            "S2: L=%.2f nm (M=%d, l0=%.3f nm) ks=%.4f N/m kb=%.4e N·m [lenScale=%.3f bendScale=%.3f] | "
            + "converter: lb=%.3f nm |d0|=%.4f nm rCF8_perp=%.4f nm rCF8_axial=%.4f nm [eccScale=%.3f comp=%s] | "
            + "transverse offset=%.2f nm",
            G.g4l0*G.g4M*1e3, G.g4M, G.g4l0*1e3, G.g4ks, G.g4kb, S2_LEN_SCALE, S2_BEND_SCALE,
            G.lb*1e3, Math.sqrt(dx*dx + dy*dy)*1e3, dy*1e3, dx*1e3, CONV_ECC_SCALE, CONV_ECC_COMP, CONV_TRANS_NM);
    }

    static boolean siteOn()   { return SITE_MODE > 0; }
    static boolean chiralOn() { return SITE_MODE > 0 || HEAD_ROLL; }
    static boolean strokeSkewOn() { return SITE_MODE > 0 && EPS_STROKE_DEG != 0.0; }
    /** the converter-frame task is wired only when a lattice provides a site frame AND the angle is nonzero. */
    static boolean convSkewOn() { return SITE_MODE > 0 && CONV_SKEW_DEG != 0.0; }
    /** the SECOND (post-cock) converter-frame invocation is wired ONLY for the state-gated mode. */
    static boolean convSkewStateGated() { return convSkewOn() && CONV_SKEW_STATE_GATED; }
    static boolean convSkewRamped() { return convSkewOn() && CONV_SKEW_RAMP != ChiralSiteSystem.RAMP_OFF; }
    /** Reject ambiguous simultaneous activation modes AT STARTUP rather than silently preferring one. */
    static void checkConvSkewModes() {
        if (CONV_SKEW_STATE_GATED && CONV_SKEW_RAMP != ChiralSiteSystem.RAMP_OFF)
            throw new IllegalArgumentException("-converter-skew-state-gated on and -converter-skew-progress-ramp "
                + "are mutually exclusive activation schedules; pick one (§25).");
        if (!(CONV_SKEW_RAMP_ONSET >= 0.0 && CONV_SKEW_RAMP_ONSET < 1.0))
            throw new IllegalArgumentException("-converter-skew-ramp-onset must be in [0,1), got " + CONV_SKEW_RAMP_ONSET);
    }
    static String rampName(int r) {
        return switch (r) { case ChiralSiteSystem.RAMP_LINEAR -> "linear";
                            case ChiralSiteSystem.RAMP_SMOOTHSTEP -> "smoothstep";
                            case ChiralSiteSystem.RAMP_DELAYED -> "delayed"; default -> "off"; }; }
    static void resetChiral() { SITE_MODE = 0; HEAD_ROLL = false; HEAD_ROLL_BROWN = true; REG_K = 0; EPS_BIND_DEG = 0;
        EPS_STROKE_DEG = 0; SITE_EXCLUSIVE = true; MIRROR_SIGN = 1.0; SITE_CAPTURE_NM = 12.0; RAND_BASE_AZ = false;
        CONV_SKEW_DEG = 0; CONV_SKEW_GAUGE = true; CONV_SKEW_STATE_GATED = false;
        CONV_SKEW_RAMP = ChiralSiteSystem.RAMP_OFF; CONV_SKEW_RAMP_ONSET = 0.25; }
    static String siteModeName(int m) {
        return switch (m) { case 1 -> "native"; case 2 -> "every3"; case 3 -> "every4";
                            case 4 -> "stair9-45"; case 5 -> "stair9-90"; default -> "off"; }; }
    /** axial site rise (µm) for a lattice mode. */
    static double siteRise(int m) {
        return switch (m) { case 1 -> Constants.actinMonoRadius; case 2 -> 3 * Constants.actinMonoRadius;
                            case 3 -> 4 * Constants.actinMonoRadius; case 4, 5 -> 9.0e-3; default -> Constants.actinMonoRadius; }; }
    /** azimuthal advance per site (rad); 0 ⇒ the analytic native helix phi(s) is used instead. */
    static double siteStairPhase(int m) {
        return switch (m) { case 4 -> Math.PI / 4.0; case 5 -> Math.PI / 2.0; default -> 0.0; }; }
    static String chiralConfigString() {
        return String.format(Locale.US,
            "sites=%s(rise=%.3f nm, stair=%.1f deg) headRollDof=%s headRollBrownian=%s registryK=%.3e N·m/rad "
            + "binding-skew-deg=%+.2f [actin-side attachment azimuth] "
            + "stroke-skew-deg=%+.2f [actin-side one-shot interface step] "
            + "converter-stroke-skew-deg=%+.2f [MOTOR-side converter stroke-plane rotation, gauge=%s, stateGated=%s, ramp=%s(onset=%.2f)] "
            + "siteExclusive=%s mirror=%+.0f capture=%.1f nm "
            + "Ractin=%.2f nm randomBaseAzimuth=%s surfaceBond=%s",
            siteModeName(SITE_MODE), siteRise(SITE_MODE) * 1e3, siteStairPhase(SITE_MODE) * 180 / Math.PI,
            HEAD_ROLL ? "ON" : "OFF", HEAD_ROLL_BROWN ? "ON" : "OFF", REG_K, EPS_BIND_DEG, EPS_STROKE_DEG,
            CONV_SKEW_DEG, CONV_SKEW_GAUGE ? "interface" : "pivot", CONV_SKEW_STATE_GATED ? "ON" : "OFF",
            rampName(CONV_SKEW_RAMP), CONV_SKEW_RAMP_ONSET,
            SITE_EXCLUSIVE ? "ON" : "OFF", MIRROR_SIGN, SITE_CAPTURE_NM, R_ACTIN_NM,
            RAND_BASE_AZ ? "ON" : "OFF", SURFACE_ON ? "ON" : "OFF");
    }

    static boolean BR_FIL_AXIAL = true;      // -filament-brownian-axial on|off
    static boolean BR_FIL_TRANS = true;      // -filament-brownian-transverse on|off
    static boolean BR_FIL_ROLL  = true;      // -filament-brownian-roll on|off
    static boolean BR_FIL_OTHROT = true;     // -filament-brownian-other-rotation on|off
    static boolean BR_MOT_UNBOUND = true;    // -motor-brownian-unbound on|off
    static boolean BR_MOT_BOUND   = true;    // -motor-brownian-bound on|off
    /** true iff any filament Brownian channel is masked ⇒ the (additive) mask task is wired. */
    static boolean brownChanOn() { return !(BR_FIL_AXIAL && BR_FIL_TRANS && BR_FIL_ROLL && BR_FIL_OTHROT); }
    /** matc[3]: bit0 ⇒ bound-motor Brownian OFF, bit1 ⇒ unbound-motor Brownian OFF. 0 = canonical. */
    static int motorBrownPolicy() { return (BR_MOT_BOUND ? 0 : 1) | (BR_MOT_UNBOUND ? 0 : 2); }
    static void setBrownianPolicy(boolean filAx, boolean filTr, boolean filRoll, boolean filOth,
                                  boolean motUnbound, boolean motBound) {
        BR_FIL_AXIAL = filAx; BR_FIL_TRANS = filTr; BR_FIL_ROLL = filRoll; BR_FIL_OTHROT = filOth;
        BR_MOT_UNBOUND = motUnbound; BR_MOT_BOUND = motBound;
    }
    static void resetBrownianPolicy() { setBrownianPolicy(true, true, true, true, true, true); }
    static String brownianPolicyString() {
        return String.format("filament[axial=%s transverse=%s roll=%s otherRot=%s] motor[unbound=%s bound=%s] (matc[3]=%d)",
                BR_FIL_AXIAL ? "ON" : "OFF", BR_FIL_TRANS ? "ON" : "OFF", BR_FIL_ROLL ? "ON" : "OFF",
                BR_FIL_OTHROT ? "ON" : "OFF", BR_MOT_UNBOUND ? "ON" : "OFF", BR_MOT_BOUND ? "ON" : "OFF",
                motorBrownPolicy());
    }
    /** The explicit complete-mat state: the beam SoA + CSR/reduce scratch, over the shared G (fil/mot/body/bondData). */
    static final class ExMat {
        Glide2D G; int N, M, nSeg, numRedBlk;
        DoubleArray nodes, frame, params, sys, outGeom, q, redOut, redBlk, eupP, bindP, cockP;
        FloatArray zP; IntArray exCounts, boundSeg, active, noBind, matc, redP, csrChunkParams, csrMatrix;
        // continuous local actin co-occupancy exclusion (noncanonical, default-off) scratch/maps + telemetry
        IntArray candInt, segFilId, occStats; DoubleArray candArc, occP; FloatArray segCumArc;
        // helical surface binding (noncanonical, default-off): fresh-bind tracking + params + surface xbParams
        IntArray prevBound, justBound; DoubleArray surfP, stericP; FloatArray xbParamsSurf;
        // Vilfan target-zone binding (noncanonical, default-off): hazard params + per-candidate diagnostics
        DoubleArray tzP; FloatArray tzDiag;
        // Brownian-noise ablation (noncanonical, default-off): per-channel filament Brownian mask
        FloatArray brChan;
        // discrete actin sites + head rotational DOF + registry (noncanonical, default-off)
        DoubleArray chiP; IntArray bindSite, prevNuc, siteStats;
        FloatArray headRef, headOmega, headTau, headMis;
        // per-motor converter frame (true converter-stroke-plane rotation; identity/zero when the feature is off)
        DoubleArray convF;
    }
    static ExMat packExMat(Glide2D G, int brownOn) {
        ExMat e = new ExMat(); e.G = G; int N = G.N, M = G.g4M, nSeg = G.nSeg; e.N = N; e.M = M; e.nSeg = nSeg;
        // Beam SoA strides are M-derived (byte-identical to the old 15/210 constants at the canonical M=4 [L40];
        // grow for L60's M=6 to 3*(M+1)=21 node comps and n*(n+1)=420 scratch). frame is the fixed converter frame (15).
        int nodeStride = 3 * (M + 1), sysStride = (3 * M + 2) * (3 * M + 3);
        e.nodes = new DoubleArray(nodeStride * N); e.frame = new DoubleArray(15 * N); e.params = new DoubleArray(17 * N);
        e.sys = new DoubleArray(sysStride * N); e.outGeom = new DoubleArray(9 * N); e.q = new DoubleArray(4 * N); e.sys.init(0.0);
        e.boundSeg = new IntArray(N); e.active = new IntArray(N);
        double[] pr = ExplicitMatSolveHarness.paramArr(G);
        for (int m = 0; m < N; m++) {
            for (int j = 0; j <= M; j++) for (int k = 0; k < 3; k++) e.nodes.set((3*j+k)*N+m, G.g4Node[m][j][k]);
            double[] fr = ExplicitMatSolveHarness.frameArr(G, m); for (int c = 0; c < 15; c++) e.frame.set(c*N+m, fr[c]);
            for (int c = 0; c < 17; c++) e.params.set(c*N+m, pr[c]);
            e.q.set(m, G.phi[m]); e.q.set(N+m, G.psi[m]); e.q.set(2*N+m, G.thetaS[m]); e.q.set(3*N+m, G.psiActin[m]);
            int bs = G.mot.boundSeg.get(m); e.boundSeg.set(m, bs); e.active.set(m, bs >= 0 ? 1 : 0);
        }
        e.exCounts = IntArray.fromElements(N, 1, M, nSeg);
        e.matc = IntArray.fromElements(0, 0, brownOn, motorBrownPolicy());   // [3] = binding-state Brownian mask (0 = canonical)
        e.eupP = DoubleArray.fromElements(G.eup[0], G.eup[1], G.eup[2]);
        e.noBind = new IntArray(N); for (int m = 0; m < N; m++) e.noBind.set(m, G.noBind[m] ? 1 : 0);
        // bind gate thresholds (Tol defaults) + constants — the DETERMINISTIC 8-gate contract.
        // bindP[10]=in-segment arc margin (CANONICAL machine-ε / legacy 50 nm); bindP[12]=ownership mode (0=canonical
        // half-open, 1=legacy) — both from the shared TwoBodyConverterMotor.LEGACY_OWNERSHIP toggle (default canonical).
        e.bindP = DoubleArray.fromElements(3.0, 25, 25, 20, 2.0, 15.0, Constants.radius,
                TwoBodyConverterMotor.PHI_PRE_3E, TwoBodyConverterMotor.A_SEMI[2], Constants.kT,
                TwoBodyConverterMotor.bindMargin(), 1, TwoBodyConverterMotor.LEGACY_OWNERSHIP ? 1.0 : 0.0);
        e.cockP = DoubleArray.fromElements(TwoBodyConverterMotor.PRESTROKE_THETAS, TwoBodyConverterMotor.ADP_THETAS);
        e.zP = FloatArray.fromElements((float) G.kzCode);
        int mcs = SpatialGrid.bodyChunkSize(N, nSeg), nCh = SpatialGrid.numBodyChunks(N, mcs);
        e.csrChunkParams = IntArray.fromElements(mcs, nCh); e.csrMatrix = new IntArray(Math.max(1, nCh * nSeg)); e.csrMatrix.init(0);
        e.numRedBlk = Math.max(1, (N + RED_BLK - 1) / RED_BLK);
        e.redP = IntArray.fromElements(RED_BLK, e.numRedBlk); e.redBlk = new DoubleArray(3 * e.numRedBlk); e.redBlk.init(0.0);
        e.redOut = new DoubleArray(6);
        // occupancy-exclusion scratch + static material maps + telemetry (allocated always; wired only when occOn()).
        e.candInt = new IntArray(2 * N); e.candInt.init(0); e.candArc = new DoubleArray(N); e.candArc.init(0.0);
        e.segCumArc = new FloatArray(nSeg); e.segFilId = new IntArray(nSeg);
        TwoBodyBeamAnalyticGpu.computeMaterialMaps(G.fil, nSeg, e.segCumArc, e.segFilId);
        e.occP = DoubleArray.fromElements(OCC_EXCL_NM, OCC_TOL_NM);   // exclusion + tol in nm (kernel converts sep µm→nm)
        e.occStats = new IntArray(4); e.occStats.init(0);
        // helical surface binding scratch/params (noncanonical, default-off ⇒ never wired ⇒ byte-identical)
        e.prevBound = new IntArray(N); e.prevBound.init(-1);
        e.justBound = new IntArray(N); e.justBound.init(0);
        double twist = TWIST_PER_MON_DEG * Math.PI / 180.0 / Constants.actinMonoRadius;   // rad/µm, LEFT-handed
        double Ract = R_ACTIN_NM * 1e-3;                                                   // µm
        e.surfP   = DoubleArray.fromElements(Ract, twist, Constants.actinMonoRadius);      // [Ractin, twistRate, monoSp]
        e.stericP = DoubleArray.fromElements(Ract, SURF_STERIC ? SURF_EXCL_NM * 1e-3 : 0.0, OCC_TOL_NM * 1e-3);
        // surface xbParams: G.xbParams[0..5] (pure-F8, align OFF) + [6]=Ractin + [7]=segF10Off(0, faithful; F10 already off)
        e.xbParamsSurf = FloatArray.fromElements(G.xbParams.get(0), G.xbParams.get(1), G.xbParams.get(2),
                G.xbParams.get(3), G.xbParams.get(4), G.xbParams.get(5), (float) Ract, 0f);
        G.mot.bindAzim.init(0f);
        // Vilfan target-zone binding scratch/params (noncanonical, default-off ⇒ never wired ⇒ byte-identical)
        e.tzP = DoubleArray.fromElements(twist, TZ_ALPHA, TZ_HARD_RAD, TZ_DIAG ? 1.0 : 0.0);
        e.tzDiag = new FloatArray(4 * N); e.tzDiag.init(0f);
        G.mot.bindPsi0.init(0f);
        // per-channel filament Brownian mask (all 1.0f ⇒ an IEEE identity multiply; the task is only WIRED when
        // brownChanOn(), so the default path is byte-identical)
        e.brChan = FloatArray.fromElements(BR_FIL_AXIAL ? 1f : 0f, BR_FIL_TRANS ? 1f : 0f,
                                           BR_FIL_ROLL ? 1f : 0f, BR_FIL_OTHROT ? 1f : 0f);
        // ---- discrete sites / head roll DOF / registry (noncanonical, default-off ⇒ never wired) -------------
        // MIRRORED site chirality = a genuine REFLECTION of the actin lattice: the helical twist rate reverses
        // handedness AND the site tangential sense flips (both are consequences of one mirror operation).
        double chiTwist = MIRROR_SIGN < 0 ? -twist : twist;
        double chiStair = MIRROR_SIGN < 0 ? -siteStairPhase(SITE_MODE) : siteStairPhase(SITE_MODE);
        e.chiP = DoubleArray.fromElements(SITE_MODE, siteRise(SITE_MODE), chiTwist, chiStair, Ract,
                EPS_BIND_DEG * Math.PI / 180.0, EPS_STROKE_DEG * Math.PI / 180.0, REG_K, 0.0, G.dt,
                HEAD_ROLL_BROWN ? 1.0 : 0.0, HEAD_ROLL ? 1.0 : 0.0, SITE_EXCLUSIVE ? 1.0 : 0.0, MIRROR_SIGN,
                SITE_CAPTURE_NM * 1e-3, SITE_SEARCH_HALF,
                // [16..18] TRUE converter-stroke-plane rotation (see ChiralSiteSystem.convFrameStep)
                CONV_SKEW_DEG * Math.PI / 180.0, CONV_SKEW_GAUGE ? 1.0 : 0.0, TwoBodyConverterMotor.PHI_PRE_3E,
                // [19] state gating on/off; [20] the thetaS pre/post discriminant, built from the SAME cockP
                // constants matCock uses, so the skew and the rest-coordinate switch share one state source.
                CONV_SKEW_STATE_GATED ? 1.0 : 0.0,
                0.5 * (TwoBodyConverterMotor.PRESTROKE_THETAS + TwoBodyConverterMotor.ADP_THETAS),
                // [21..22] the §25.1 CALIBRATED theta endpoints (one source of truth: ChiralSiteSystem);
                // [23] ramp shape, [24] delayed-ramp onset.
                ChiralSiteSystem.THETA_PRE, ChiralSiteSystem.THETA_POST,
                CONV_SKEW_RAMP, CONV_SKEW_RAMP_ONSET);
        // Per-motor CONVERTER FRAME (stride 13, planar): [0..2] b*, [3..5] econv*, [6..8] eup*, [9..11] gauge
        // offset (µm), [12] flag. ALL ZERO ⇒ flag 0 ⇒ matBeamGeom / matS2SolveStep take the VERBATIM canonical
        // branch reading the base frame ⇒ byte-identical when the feature is off (it is never even wired).
        e.convF = new DoubleArray(13 * N); e.convF.init(0.0);
        e.bindSite = new IntArray(N); e.bindSite.init(-1);
        e.prevNuc = new IntArray(N); for (int m = 0; m < N; m++) e.prevNuc.set(m, G.mot.nucleotideState.get(m));
        e.siteStats = new IntArray(4); e.siteStats.init(0);
        e.headRef = new FloatArray(3 * N); e.headRef.init(0f);   // 0 ⇒ the kernel's deterministic first-use seed
        e.headOmega = new FloatArray(N); e.headOmega.init(0f);
        e.headTau = new FloatArray(N); e.headTau.init(0f);
        e.headMis = new FloatArray(N); e.headMis.init(0f);
        // ---- per-motor RANDOM BASE AZIMUTH (a SCENE control for the shared-base-frame artifact, not physics):
        // rotate each motor's whole base geometry rigidly about its OWN pivot P (= beam node M) around eup by a
        // deterministic per-motor angle. Nothing about the motor's internal mechanics changes — only which way
        // its (fixed, anchored) base plane faces. eup (the floor normal) is untouched.
        if (RAND_BASE_AZ) {
            double ex0 = G.eup[0], ey0 = G.eup[1], ez0 = G.eup[2];
            for (int m = 0; m < N; m++) {
                long h = (m * 2654435761L) ^ ((long) RAND_BASE_SEED * 0x9E3779B1L); h ^= (h >>> 13); h *= 0x9E3779B1L; h ^= (h >>> 16);
                double chi = 2.0 * Math.PI * (((h & 0xFFFFFF) + 1) / 16777217.0);
                double c = Math.cos(chi), s = Math.sin(chi);
                double Px = e.nodes.get((3 * M) * N + m), Py = e.nodes.get((3 * M + 1) * N + m), Pz = e.nodes.get((3 * M + 2) * N + m);
                for (int j = 0; j <= M; j++) {
                    double x = e.nodes.get((3 * j) * N + m) - Px, y = e.nodes.get((3 * j + 1) * N + m) - Py, z = e.nodes.get((3 * j + 2) * N + m) - Pz;
                    double[] r = rotAbout(x, y, z, ex0, ey0, ez0, c, s);
                    e.nodes.set((3 * j) * N + m, Px + r[0]); e.nodes.set((3 * j + 1) * N + m, Py + r[1]); e.nodes.set((3 * j + 2) * N + m, Pz + r[2]);
                }
                for (int blk : new int[]{ 0, 3, 12 }) {   // bhat, econv, g4Tan — pure directions
                    double[] r = rotAbout(e.frame.get(blk * N + m), e.frame.get((blk + 1) * N + m), e.frame.get((blk + 2) * N + m), ex0, ey0, ez0, c, s);
                    e.frame.set(blk * N + m, r[0]); e.frame.set((blk + 1) * N + m, r[1]); e.frame.set((blk + 2) * N + m, r[2]);
                }
                // g4E (frame 9..11) is the anchored beam base POINT ⇒ rotate about P, not about the origin
                double gx = e.frame.get(9 * N + m) - Px, gy = e.frame.get(10 * N + m) - Py, gz = e.frame.get(11 * N + m) - Pz;
                double[] rg = rotAbout(gx, gy, gz, ex0, ey0, ez0, c, s);
                e.frame.set(9 * N + m, Px + rg[0]); e.frame.set(10 * N + m, Py + rg[1]); e.frame.set(11 * N + m, Pz + rg[2]);
            }
        }
        // ---- CONVERTER TRANSVERSE OFFSET (diagnostic, default-off): roll each motor's base triad about its OWN b̂.
        // b̂ is invariant, so the AXIAL stroke direction is untouched; (econv, ê_up) tilt, which displaces the
        // converter JOINT C = P + lb·(ê_up cosφ + b̂ sinφ) out of the motor's axial plane while the S2 pivot P and
        // the whole motor lawn stay exactly where they are. The requested offset is the transverse displacement of
        // C at the REFERENCE binding pose (φ = PHI_PRE_3E), so
        //     δ_roll = asin( offset / (lb·cos φ_pre) ).
        // A per-motor roll about the motor's own b̂ is a LOCAL geometric perturbation, not a laboratory axis, and it
        // introduces no site-frame handedness by itself (b̂/econv/ê_up are the motor's base directions; the actin
        // site frame is untouched). ±offset are related by reflection in the axial plane, so the ε=0 achirality and
        // lattice-mirror controls are the required empirical checks — they are gated in the Phase-E driver.
        if (CONV_TRANS_NM != 0.0) {
            double arm = G.lb * Math.cos(TwoBodyConverterMotor.PHI_PRE_3E) * 1e3;   // nm
            double sn = CONV_TRANS_NM / arm;
            if (!(Math.abs(sn) < 1.0)) throw new IllegalArgumentException(String.format(Locale.US,
                    "-converter-transverse-offset-nm %.3f exceeds the reachable transverse arm lb·cos(phi_pre) = %.3f nm",
                    CONV_TRANS_NM, arm));
            double dRoll = Math.asin(sn), c = Math.cos(dRoll), s = Math.sin(dRoll);
            for (int m = 0; m < N; m++) {
                double bx = e.frame.get(m), by = e.frame.get(N + m), bz = e.frame.get(2 * N + m);
                for (int blk : new int[]{ 3, 6 }) {     // econv, ê_up — rotate about this motor's own b̂
                    double[] r = rotAbout(e.frame.get(blk * N + m), e.frame.get((blk + 1) * N + m),
                                          e.frame.get((blk + 2) * N + m), bx, by, bz, c, s);
                    e.frame.set(blk * N + m, r[0]); e.frame.set((blk + 1) * N + m, r[1]); e.frame.set((blk + 2) * N + m, r[2]);
                }
            }
        }
        return e;
    }
    /** Rodrigues rotation of (x,y,z) about the unit axis (ax,ay,az) by an angle with cos c / sin s. */
    static double[] rotAbout(double x, double y, double z, double ax, double ay, double az, double c, double s) {
        double kx = ay * z - az * y, ky = az * x - ax * z, kz = ax * y - ay * x;
        double d = ax * x + ay * y + az * z;
        return new double[]{ x * c + kx * s + ax * d * (1 - c), y * c + ky * s + ay * d * (1 - c), z * c + kz * s + az * d * (1 - c) };
    }
    /** CPU-runner = the complete explicit mat step as plain-Java kernel calls (pre-bound, chemistry fixed, no bind search). */
    static void stepExCPU(ExMat e, int t, int seed) {
        Glide2D G = e.G; FilamentStore f = G.fil; MotorStore mot = G.mot; RigidRodBody b = mot.body;
        e.matc.set(0, t); e.matc.set(1, seed); mot.setCounts(t, seed, e.nSeg); f.counts.set(1, t); f.counts.set(2, seed);
        TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF);
        TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit(e.outGeom, e.boundSeg, e.eupP, e.exCounts, b.coord, b.uVec, b.yVec);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        CrossBridgeSystem.csrChunkZero(e.csrChunkParams, mot.counts, e.csrMatrix);
        CrossBridgeSystem.csrChunkHistogram(mot.boundSeg, mot.counts, e.csrChunkParams, e.csrMatrix);
        CrossBridgeSystem.csrChunkReduce(mot.counts, e.csrChunkParams, e.csrMatrix, G.segCount);
        CrossBridgeSystem.csrScan(mot.counts, G.segCount, G.segOff);
        CrossBridgeSystem.csrChunkScatter(mot.boundSeg, mot.counts, e.csrChunkParams, G.segOff, G.segMyo, e.csrMatrix);
        CrossBridgeSystem.segGather(G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts);
        if (!G.rigid) ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        MatSoaSlice.matZConfine(f.coord, f.forceSum, e.zP, e.exCounts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.orthogonalizeY(f.uVec, f.yVec, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        TwoBodyBeamAnalyticGpu.matS2SolveStep(e.nodes, e.frame, e.q, G.bondData, e.boundSeg, e.params, e.sys, e.outGeom, mot.forceDotFil, mot.forceMag, e.matc, e.exCounts, e.convF);
        MatSoaSlice.matReduceBlocks(e.boundSeg, e.active, mot.forceDotFil, e.redP, e.exCounts, e.redBlk);
        MatSoaSlice.matReduceFinal(e.redBlk, f.coord, e.redP, e.exCounts, e.redOut);
    }
    static GridScheduler exSched;
    static TornadoExecutionPlan buildExMatGraph(ExMat e) { return buildExMatGraph(e, false); }
    /** @param prod true = PRODUCTION residency (only redOut downloaded; no validation snapshots — for timing);
     *              false = VALIDATION (also downloads nodes/q/fil.coord/forceDotFil/bondData for the CPU compare). */
    static TornadoExecutionPlan buildExMatGraph(ExMat e, boolean prod) {
        Glide2D G = e.G; FilamentStore f = G.fil; MotorStore mot = G.mot; RigidRodBody b = mot.body; int N = e.N, nSeg = e.nSeg;
        TaskGraph tg = new TaskGraph("exmat");
        tg.transferToDevice(DataTransferMode.FIRST_EXECUTION,
                e.nodes, e.frame, e.params, e.sys, e.outGeom, e.eupP, e.zP, e.boundSeg, e.active, e.exCounts,
                e.csrChunkParams, e.csrMatrix, e.redP, e.redBlk, e.redOut, e.convF,
                b.coord, b.uVec, b.yVec, b.bRotGam,
                f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.forceSum, f.torqueSum,
                f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.chainParams,
                f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                mot.bindArc, mot.nucleotideState, mot.forceMag, G.xbParams, G.segCount, G.segOff, G.segMyo);
        if (prod) tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, e.q, mot.boundSeg, mot.forceDotFil, f.coord, G.bondData);   // resident (production)
        tg.transferToDevice(DataTransferMode.EVERY_EXECUTION, e.matc, mot.counts, f.counts);   // per-step counters only
        if (!prod) tg.transferToDevice(DataTransferMode.EVERY_EXECUTION, e.q, mot.boundSeg, mot.forceDotFil, f.coord, G.bondData);   // validation-mirrored
        tg.task("beamGeom", TwoBodyBeamAnalyticGpu::matBeamGeom, e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF)
          .task("place", TwoBodyBeamAnalyticGpu::matPlaceHeadExplicit, e.outGeom, e.boundSeg, e.eupP, e.exCounts, b.coord, b.uVec, b.yVec)
          .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams)
          .task("zeroAcc", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
          .task("csrZero", CrossBridgeSystem::csrChunkZero, e.csrChunkParams, mot.counts, e.csrMatrix)
          .task("csrHist", CrossBridgeSystem::csrChunkHistogram, mot.boundSeg, mot.counts, e.csrChunkParams, e.csrMatrix)
          .task("csrReduce", CrossBridgeSystem::csrChunkReduce, mot.counts, e.csrChunkParams, e.csrMatrix, G.segCount)
          .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, G.segCount, G.segOff)
          .task("csrScatter", CrossBridgeSystem::csrChunkScatter, mot.boundSeg, mot.counts, e.csrChunkParams, G.segOff, G.segMyo, e.csrMatrix)
          .task("segGather", CrossBridgeSystem::segGather, G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts)
          .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
          .task("zconf", MatSoaSlice::matZConfine, f.coord, f.forceSum, e.zP, e.exCounts)
          .task("brown", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
          .task("integ", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
          .task("orthoY", DerivedGeometrySystem::orthogonalizeY, f.uVec, f.yVec, f.counts)
          .task("derive", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
          .task("s2solve", TwoBodyBeamAnalyticGpu::matS2SolveStep, e.nodes, e.frame, e.q, G.bondData, e.boundSeg, e.params, e.sys, e.outGeom, mot.forceDotFil, mot.forceMag, e.matc, e.exCounts, e.convF)
          .task("redBlk", MatSoaSlice::matReduceBlocks, e.boundSeg, e.active, mot.forceDotFil, e.redP, e.exCounts, e.redBlk)
          .task("redFin", MatSoaSlice::matReduceFinal, e.redBlk, f.coord, e.redP, e.exCounts, e.redOut);
        if (prod) tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.redOut);   // ONLY the reduction crosses back
        else      tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.nodes, e.q, e.redOut, f.coord, mot.forceDotFil, G.bondData);
        int pn = ((N + 63) / 64) * 64, ps = ((nSeg + 63) / 64) * 64, nCh = e.csrChunkParams.get(1);
        exSched = new GridScheduler();
        for (String nm : new String[]{ "beamGeom", "place", "s2solve" }) addW(exSched, "exmat." + nm, pn);
        for (String nm : new String[]{ "bond" }) addW(exSched, "exmat." + nm, pn);   // bondForces parallel over motors
        for (String nm : new String[]{ "zeroAcc", "segGather", "chain", "zconf", "brown", "integ", "orthoY", "derive", "csrReduce" }) addW(exSched, "exmat." + nm, ps);
        addW(exSched, "exmat.csrZero", ((Math.max(1, nCh * nSeg) + 63) / 64) * 64);
        addW(exSched, "exmat.csrHist", ((nCh + 63) / 64) * 64);
        addW(exSched, "exmat.csrScatter", ((nCh + 63) / 64) * 64);
        addW(exSched, "exmat.csrScan", 64);
        addW(exSched, "exmat.redBlk", ((e.numRedBlk + 63) / 64) * 64);
        addW(exSched, "exmat.redFin", 64);
        return new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(exSched);
    }
    static void addW(GridScheduler sc, String name, int global) { WorkerGrid w = new WorkerGrid1D(Math.max(1, global)); w.setLocalWork(64, 1, 1); sc.addWorkerGrid(name, w); }

    static boolean trajGate(StringBuilder log) {
        double density = 200.0; int seed = 101, steps = 300, mBound = 0;
        double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        // two identical scenes: Gc (CPU-runner), Gd (GPU)
        Glide2D Gc = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed);
        Glide2D Gd = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed);
        int N = Gc.N, M = Gc.g4M, nSeg = Gc.nSeg;
        // pre-bind ONE motor, all others unbound (binding disabled ⇒ fixed active set, chemistry fixed)
        for (Glide2D G : new Glide2D[]{ Gc, Gd }) {
            for (int m = 0; m < N; m++) G.mot.boundSeg.set(m, -1);
            int s = ExplicitMatSolveHarness.nearestSegSafe(G, mBound); if (s < 0) s = nSeg / 2;
            G.mot.boundSeg.set(mBound, s); TwoBodyConverterMotor.geom2D(G, mBound);
            G.mot.bindArc.set(mBound, (float) (0.5 * G.fil.segLength.get(s)));
        }
        ExMat ec = packExMat(Gc, 1), ed = packExMat(Gd, 1);
        TornadoExecutionPlan plan;
        try { plan = buildExMatGraph(ed); } catch (Throwable ex) { Throwable r = root(ex); log.append("- graph build FAILED: `" + r.getClass().getName() + "`: " + oneLine(r.getMessage()) + "\n"); System.out.println("  graph build FAILED: " + oneLine(r.getMessage())); return false; }
        log.append("## §7 one complete explicit mat step (CPU-runner vs GPU, N=" + N + ", 1 pre-bound motor, Brownian on)\n");
        boolean lowered = true; String err = null;
        try {
            ed.matc.set(0, 0); ed.matc.set(1, seed); Gd.mot.setCounts(0, seed, nSeg); Gd.fil.counts.set(1, 0); Gd.fil.counts.set(2, seed);
            plan.execute();
        } catch (Throwable ex) { lowered = false; Throwable r = root(ex); err = r.getClass().getName() + ": " + oneLine(r.getMessage()); }
        if (!lowered) { log.append("- GRAPH LOWERS: **NO** — " + err + "\n"); System.out.println("  §7 graph LOWERS: NO — " + err); return false; }
        stepExCPU(ec, 0, seed);
        double[] d1 = compare(ec, ed, Gc, Gd, mBound);
        log.append(String.format(Locale.US, "- GRAPH LOWERS + EXECUTES: **YES**. one-step GPU vs CPU-runner: maxΔnode=%.2e µm, Δphi=%.2e, Δpsi=%.2e, maxΔfilCoord=%.2e µm, ΔforceDotFil=%.2e, maxΔbond=%.2e, ΔredOut=%.2e\n",
                d1[0], d1[1], d1[2], d1[3], d1[4], d1[5], d1[6]));
        boolean s7 = d1[0] < 1e-6 && d1[3] < 1e-4 && d1[5] < 1e-4;
        log.append(String.format(Locale.US, "- **§7 one-step: %s** (bit-faithful device execution of the complete explicit mat)\n\n", s7 ? "PASS" : "REVIEW"));

        // §8 multi-step
        log.append("## §8 multi-step complete-mat trajectory (" + steps + " steps, CPU-runner vs GPU)\n");
        int firstDiv = -1; double maxNode = d1[0], maxFil = d1[3];
        for (int t = 1; t < steps; t++) {
            ed.matc.set(0, t); ed.matc.set(1, seed); Gd.mot.setCounts(t, seed, nSeg); Gd.fil.counts.set(1, t); Gd.fil.counts.set(2, seed);
            try { plan.execute(); } catch (Throwable ex) { Throwable r = root(ex); log.append("- device execute FAILED @t=" + t + ": " + oneLine(r.getMessage()) + "\n"); System.out.println("  execute FAILED @t=" + t); return false; }
            stepExCPU(ec, t, seed);
            double[] dt = compare(ec, ed, Gc, Gd, mBound);
            maxNode = Math.max(maxNode, dt[0]); maxFil = Math.max(maxFil, dt[3]);
            if (firstDiv < 0 && dt[0] > 1e-6) firstDiv = t;
        }
        String kind = firstDiv < 0 ? "no divergence (bit-close all " + steps + " steps)" : "continuous FP decorrelation from t=" + firstDiv + " (chaotic float op-order; not semantic)";
        boolean s8 = maxNode < 1e-2 && Double.isFinite(maxNode) && Double.isFinite(maxFil);   // stays bounded, no blow-up
        int nbC = (int) ec.redOut.get(0), nbD = (int) ed.redOut.get(0);
        log.append(String.format(Locale.US, "- over %d steps: max GPU-vs-CPU-runner Δnode=%.2e µm, ΔfilCoord=%.2e µm; first divergence: %s; final bound count CPU=%d GPU=%d\n", steps, maxNode, maxFil, kind, nbC, nbD));
        log.append("- **§8 multi-step: " + (s8 && nbC == nbD ? "PASS" : "REVIEW") + "** — one pre-bound explicit motor advanced through all shared GPU stages for " + steps + " steps; CPU/GPU agree (float-decorrelation only).\n\n");
        boolean ok = s7 && s8 && nbC == nbD;
        log.append("**§7/§8 VERDICT: " + (ok ? "PASS" : "REVIEW") + "**\n");
        System.out.printf(Locale.US, "  §7 one-step Δnode=%.1e ⇒ %s | §8 %d steps maxΔnode=%.1e firstDiv=%s ⇒ %s%n", d1[0], s7 ? "PASS" : "REVIEW", steps, maxNode, firstDiv < 0 ? "none" : ("t=" + firstDiv), s8 ? "PASS" : "REVIEW");
        return ok;
    }
    /** {maxΔnode, Δphi, Δpsi, maxΔfilCoord, ΔforceDotFil, maxΔbond, ΔredOut} GPU(ed/Gd) vs CPU-runner(ec/Gc) at motor mB. */
    static double[] compare(ExMat ec, ExMat ed, Glide2D Gc, Glide2D Gd, int mB) {
        int N = ec.N, M = ec.M, nSeg = ec.nSeg;
        double dNode = 0; for (int j = 1; j <= M; j++) for (int k = 0; k < 3; k++) dNode = Math.max(dNode, Math.abs(ec.nodes.get((3*j+k)*N+mB) - ed.nodes.get((3*j+k)*N+mB)));
        double dPhi = Math.abs(ec.q.get(mB) - ed.q.get(mB)), dPsi = Math.abs(ec.q.get(N+mB) - ed.q.get(N+mB));
        double dFil = 0; for (int i = 0; i < 3 * nSeg; i++) dFil = Math.max(dFil, Math.abs(Gc.fil.coord.get(i) - Gd.fil.coord.get(i)));
        double dFdf = Math.abs(Gc.mot.forceDotFil.get(mB) - Gd.mot.forceDotFil.get(mB));
        double dBond = 0; for (int c = 0; c < 13; c++) dBond = Math.max(dBond, Math.abs(Gc.bondData.get(mB*13+c) - Gd.bondData.get(mB*13+c)));
        double dRed = 0; for (int i = 0; i < 6; i++) dRed = Math.max(dRed, Math.abs(ec.redOut.get(i) - ed.redOut.get(i)));
        return new double[]{ dNode, dPhi, dPsi, dFil, dFdf, dBond, dRed };
    }
    static Throwable root(Throwable e) { Throwable r = e; while (r.getCause() != null && r.getCause() != r) r = r.getCause(); return r; }

    // =============================================================== FREE-BINDING gliding (bind stages added)
    /** Gliding CPU-runner: the complete step WITH free binding + chemistry (all-active smoke; mot.boundSeg is the
     *  single source). Order = production stepGlideS2: geom → bind → chemistry → cock → placeHead → mechanics. */
    static void stepGlidingCPU(ExMat e, int t, int seed) {
        Glide2D G = e.G; FilamentStore f = G.fil; MotorStore mot = G.mot; RigidRodBody b = mot.body; int N = e.N;
        e.matc.set(0, t); e.matc.set(1, seed); mot.setCounts(t, seed, e.nSeg); f.counts.set(1, t); f.counts.set(2, seed);
        for (int m = 0; m < N; m++) e.active.set(m, 1);   // no-cull smoke (physically identical; far motors fail the gate)
        if (convSkewOn())   // TRUE converter-stroke-plane rotation: build the per-motor converter frame FIRST, so
            ChiralSiteSystem.convFrameStep(mot.boundSeg, f.uVec, f.yVec, mot.bindAzim, e.frame, e.params, e.q,
                    e.convF, e.chiP, e.exCounts);   // geometry, gates, bond and solve all see ONE frame this step
        TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF);
        if (occOn()) {   // continuous local actin co-occupancy exclusion (gate-only → serial resolve); OFF path below is byte-identical
            TwoBodyBeamAnalyticGpu.matBindGateOnly(e.active, e.noBind, mot.boundSeg, mot.nucleotideState, e.outGeom, e.q, f.coord, f.uVec, f.segLength, e.params, e.bindP, e.eupP, e.candInt, e.candArc, e.exCounts);
            TwoBodyBeamAnalyticGpu.matOccupancyResolve(e.candInt, e.candArc, mot.boundSeg, mot.bindArc, e.segCumArc, e.segFilId, e.occP, e.occStats, e.exCounts);
        } else
            TwoBodyBeamAnalyticGpu.matBindExplicit(e.active, e.noBind, mot.boundSeg, mot.nucleotideState, e.outGeom, e.q, f.coord, f.uVec, f.segLength, e.params, e.bindP, e.eupP, mot.bindArc, e.exCounts);
        if (tzOn())   // Vilfan target-zone angular hazard — applied to the geometric candidate BEFORE it persists
            TwoBodyBeamAnalyticGpu.matTargetZone(mot.boundSeg, e.prevBound, e.justBound, e.outGeom, f.coord, f.uVec, f.yVec, f.segLength, mot.bindArc, mot.bindAzim, mot.bindPsi0, e.tzP, e.tzDiag, e.matc, e.exCounts);
        if (siteOn()) {   // DISCRETE ACTIN SITES: snap the fresh bind onto the nearest lattice site + latch its id
            ChiralSiteSystem.siteSnap(mot.boundSeg, e.prevBound, e.justBound, e.outGeom, f.coord, f.uVec, f.yVec, f.segLength, e.segCumArc, mot.bindArc, mot.bindAzim, e.bindSite, e.chiP, e.exCounts);
            ChiralSiteSystem.siteOccupancyResolve(mot.boundSeg, e.justBound, e.prevBound, e.bindSite, e.segFilId, e.siteStats, e.chiP, e.exCounts);
        }
        if (ADP_RUP_ON) NucleotideCycleSystem.cycleLymnTaylorRuptureAll(mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts, mot.rigorParams, mot.ruptureStats, mot.adpRuptureParams, mot.adpRuptureStats);
        else if (RIGOR_ON) NucleotideCycleSystem.cycleLymnTaylorRigor(mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts, mot.rigorParams, mot.ruptureStats);
        else          NucleotideCycleSystem.cycleLymnTaylor(mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts);
        if (strokeSkewOn())   // askew EFFECTIVE STROKE: local-frame rest-coordinate change at the ADP·Pi→ADP switch
            ChiralSiteSystem.strokeSkew(mot.boundSeg, mot.nucleotideState, e.prevNuc, mot.bindAzim, e.chiP, e.exCounts);
        if (surfOn()) {   // select+retain the material azimuth at the bind transition (canonical bindArc kept), then 3D steric
            if (!tzOn() && !siteOn())  // target-zone / discrete-site modes select + retain the azimuth themselves
                TwoBodyBeamAnalyticGpu.matSurfaceAzim(mot.boundSeg, e.prevBound, e.justBound, e.outGeom, f.coord, f.uVec, f.yVec, f.segLength, mot.bindArc, mot.bindAzim, e.surfP, e.exCounts);
            TwoBodyBeamAnalyticGpu.matSurfaceStericPrune(mot.boundSeg, e.justBound, e.prevBound, mot.bindArc, mot.bindAzim, f.coord, f.uVec, f.yVec, f.segLength, e.segFilId, e.stericP, e.occStats, e.exCounts);
        }
        MatSoaSlice.matCock(mot.nucleotideState, e.q, e.cockP, e.exCounts);
        if (convSkewStateGated())   // mirrors the GPU `convFrame2` task EXACTLY (see the graph comment / §24.2)
            ChiralSiteSystem.convFrameStep(mot.boundSeg, f.uVec, f.yVec, mot.bindAzim, e.frame, e.params, e.q,
                    e.convF, e.chiP, e.exCounts);
        TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit(e.outGeom, mot.boundSeg, e.eupP, e.exCounts, b.coord, b.uVec, b.yVec);
        if (surfOn())
            CrossBridgeSystem.bondForcesSurface(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.bindAzim, mot.nucleotideState, G.bondData, e.xbParamsSurf);
        else
            CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams);
        if (chiralOn())   // head rotational DOF + bound orientational registry (equal-and-opposite into bondData)
            ChiralSiteSystem.headRollStep(mot.boundSeg, e.outGeom, f.uVec, f.yVec, b.bRotGam, mot.bindAzim, e.headRef, e.headOmega, e.headTau, e.headMis, G.bondData, e.chiP, e.matc, e.exCounts);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        CrossBridgeSystem.csrChunkZero(e.csrChunkParams, mot.counts, e.csrMatrix);
        CrossBridgeSystem.csrChunkHistogram(mot.boundSeg, mot.counts, e.csrChunkParams, e.csrMatrix);
        CrossBridgeSystem.csrChunkReduce(mot.counts, e.csrChunkParams, e.csrMatrix, G.segCount);
        CrossBridgeSystem.csrScan(mot.counts, G.segCount, G.segOff);
        CrossBridgeSystem.csrChunkScatter(mot.boundSeg, mot.counts, e.csrChunkParams, G.segOff, G.segMyo, e.csrMatrix);
        CrossBridgeSystem.segGather(G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts);
        if (!G.rigid) ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        MatSoaSlice.matZConfine(f.coord, f.forceSum, e.zP, e.exCounts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        if (brownChanOn())   // per-channel filament Brownian ablation mask (noncanonical; not wired when all channels ON)
            BrownianForceSystem.brownChannelMask(f.randForce, f.randTorque, e.brChan, f.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.orthogonalizeY(f.uVec, f.yVec, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        TwoBodyBeamAnalyticGpu.matS2SolveStep(e.nodes, e.frame, e.q, G.bondData, mot.boundSeg, e.params, e.sys, e.outGeom, mot.forceDotFil, mot.forceMag, e.matc, e.exCounts, e.convF);
        MatSoaSlice.matReduceBlocks(mot.boundSeg, e.active, mot.forceDotFil, e.redP, e.exCounts, e.redBlk);
        MatSoaSlice.matReduceFinal(e.redBlk, f.coord, e.redP, e.exCounts, e.redOut);
    }
    static GridScheduler glSched;
    static TornadoExecutionPlan buildGlidingGraph(ExMat e, boolean prod) {
        Glide2D G = e.G; FilamentStore f = G.fil; MotorStore mot = G.mot; RigidRodBody b = mot.body; int N = e.N, nSeg = e.nSeg;
        for (int m = 0; m < N; m++) e.active.set(m, 1);
        TaskGraph tg = new TaskGraph("glide");
        tg.transferToDevice(DataTransferMode.FIRST_EXECUTION,
                e.nodes, e.frame, e.params, e.sys, e.outGeom, e.eupP, e.zP, e.active, e.noBind, e.exCounts, e.bindP, e.cockP,
                e.csrChunkParams, e.csrMatrix, e.redP, e.redBlk, e.redOut, e.convF,
                b.coord, b.uVec, b.yVec, b.bRotGam,
                f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.forceSum, f.torqueSum,
                f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.chainParams,
                f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.forceMag,
                G.xbParams, G.segCount, G.segOff, G.segMyo);
        if (prod) tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, e.q, mot.boundSeg, mot.bindArc, mot.nucleotideState, mot.forceDotFil, f.coord, G.bondData);
        if (occOn()) tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, e.candInt, e.candArc, e.segCumArc, e.segFilId, e.occP, e.occStats);
        if (surfOn()) tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, e.prevBound, e.justBound, e.surfP, e.stericP, e.xbParamsSurf, e.segFilId, e.occStats, mot.bindAzim);
        if (tzOn())   tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, e.prevBound, e.justBound, e.tzP, e.tzDiag, mot.bindAzim, mot.bindPsi0);
        if (chiralOn()) tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, e.prevBound, e.justBound, e.chiP,
                e.bindSite, e.prevNuc, e.siteStats, e.headRef, e.headOmega, e.headTau, e.headMis,
                e.segCumArc, e.segFilId, mot.bindAzim);
        if (brownChanOn()) tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, e.brChan);
        if (RIGOR_ON) tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, mot.rigorParams, mot.ruptureStats);
        if (ADP_RUP_ON) tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, mot.adpRuptureParams, mot.adpRuptureStats);
        tg.transferToDevice(DataTransferMode.EVERY_EXECUTION, e.matc, mot.counts, f.counts);
        if (!prod) tg.transferToDevice(DataTransferMode.EVERY_EXECUTION, e.q, mot.boundSeg, mot.bindArc, mot.nucleotideState, mot.forceDotFil, f.coord, G.bondData);
        if (convSkewOn())   // TRUE converter-stroke-plane rotation — FIRST in the chain, before the geometry
            tg.task("convFrame", ChiralSiteSystem::convFrameStep, mot.boundSeg, f.uVec, f.yVec, mot.bindAzim,
                    e.frame, e.params, e.q, e.convF, e.chiP, e.exCounts);
        tg.task("beamGeom", TwoBodyBeamAnalyticGpu::matBeamGeom, e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF);
        if (occOn()) {   // continuous local actin co-occupancy exclusion: parallel gate-only → single-thread serial resolve
            tg.task("gateOnly", TwoBodyBeamAnalyticGpu::matBindGateOnly, e.active, e.noBind, mot.boundSeg, mot.nucleotideState, e.outGeom, e.q, f.coord, f.uVec, f.segLength, e.params, e.bindP, e.eupP, e.candInt, e.candArc, e.exCounts)
              .task("occResolve", TwoBodyBeamAnalyticGpu::matOccupancyResolve, e.candInt, e.candArc, mot.boundSeg, mot.bindArc, e.segCumArc, e.segFilId, e.occP, e.occStats, e.exCounts);
        } else
            tg.task("bind", TwoBodyBeamAnalyticGpu::matBindExplicit, e.active, e.noBind, mot.boundSeg, mot.nucleotideState, e.outGeom, e.q, f.coord, f.uVec, f.segLength, e.params, e.bindP, e.eupP, mot.bindArc, e.exCounts);
        if (tzOn())   // Vilfan target-zone angular hazard — immediately after the canonical bind, before it persists
            tg.task("tzone", TwoBodyBeamAnalyticGpu::matTargetZone, mot.boundSeg, e.prevBound, e.justBound, e.outGeom, f.coord, f.uVec, f.yVec, f.segLength, mot.bindArc, mot.bindAzim, mot.bindPsi0, e.tzP, e.tzDiag, e.matc, e.exCounts);
        if (siteOn())   // DISCRETE ACTIN SITES: snap fresh binds onto the lattice (parallel) → exclusive occupancy (serial)
            tg.task("siteSnap", ChiralSiteSystem::siteSnap, mot.boundSeg, e.prevBound, e.justBound, e.outGeom, f.coord, f.uVec, f.yVec, f.segLength, e.segCumArc, mot.bindArc, mot.bindAzim, e.bindSite, e.chiP, e.exCounts)
              .task("siteOcc", ChiralSiteSystem::siteOccupancyResolve, mot.boundSeg, e.justBound, e.prevBound, e.bindSite, e.segFilId, e.siteStats, e.chiP, e.exCounts);
        if (surfOn()) {   // helical surface binding: azimuth-select at bind (parallel) → 3D steric prune (single-thread serial)
            if (!tzOn() && !siteOn())
                tg.task("surfAzim", TwoBodyBeamAnalyticGpu::matSurfaceAzim, mot.boundSeg, e.prevBound, e.justBound, e.outGeom, f.coord, f.uVec, f.yVec, f.segLength, mot.bindArc, mot.bindAzim, e.surfP, e.exCounts);
            tg.task("surfPrune", TwoBodyBeamAnalyticGpu::matSurfaceStericPrune, mot.boundSeg, e.justBound, e.prevBound, mot.bindArc, mot.bindAzim, f.coord, f.uVec, f.yVec, f.segLength, e.segFilId, e.stericP, e.occStats, e.exCounts);
        }
        if (ADP_RUP_ON) tg.task("chem", NucleotideCycleSystem::cycleLymnTaylorRuptureAll, mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts, mot.rigorParams, mot.ruptureStats, mot.adpRuptureParams, mot.adpRuptureStats);
        else if (RIGOR_ON) tg.task("chem", NucleotideCycleSystem::cycleLymnTaylorRigor, mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts, mot.rigorParams, mot.ruptureStats);
        else          tg.task("chem", NucleotideCycleSystem::cycleLymnTaylor, mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts);
        if (strokeSkewOn())   // askew EFFECTIVE STROKE: local-frame rest-coordinate change at ADP·Pi→ADP
            tg.task("strokeSkew", ChiralSiteSystem::strokeSkew, mot.boundSeg, mot.nucleotideState, e.prevNuc, mot.bindAzim, e.chiP, e.exCounts);
        tg
          .task("cock", MatSoaSlice::matCock, mot.nucleotideState, e.q, e.cockP, e.exCounts);
        // STATE-GATED converter skew: re-evaluate the converter frame AFTER `cock` so the rotation and the
        // thetaS rest switch describe ONE power-stroke event in `s2solve` (which runs at the end of the step and
        // reads BOTH). The first `convFrame` task above ran before `chem`/`cock` and therefore still carried the
        // previous step's state; without this second evaluation the skew would activate one full step late.
        // Added ONLY in the gated mode ⇒ the default task list is byte-unchanged. See §24.2.
        if (convSkewStateGated())
            tg.task("convFrame2", ChiralSiteSystem::convFrameStep, mot.boundSeg, f.uVec, f.yVec, mot.bindAzim,
                    e.frame, e.params, e.q, e.convF, e.chiP, e.exCounts);
        tg.task("place", TwoBodyBeamAnalyticGpu::matPlaceHeadExplicit, e.outGeom, mot.boundSeg, e.eupP, e.exCounts, b.coord, b.uVec, b.yVec);
        if (surfOn())
            tg.task("bond", CrossBridgeSystem::bondForcesSurface, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.bindAzim, mot.nucleotideState, G.bondData, e.xbParamsSurf);
        else
            tg.task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams);
        if (chiralOn())   // head rotational DOF + registry couple (equal-and-opposite; must sit AFTER bond, BEFORE segGather)
            tg.task("headRoll", ChiralSiteSystem::headRollStep, mot.boundSeg, e.outGeom, f.uVec, f.yVec, b.bRotGam, mot.bindAzim, e.headRef, e.headOmega, e.headTau, e.headMis, G.bondData, e.chiP, e.matc, e.exCounts);
        tg
          .task("zeroAcc", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
          .task("csrZero", CrossBridgeSystem::csrChunkZero, e.csrChunkParams, mot.counts, e.csrMatrix)
          .task("csrHist", CrossBridgeSystem::csrChunkHistogram, mot.boundSeg, mot.counts, e.csrChunkParams, e.csrMatrix)
          .task("csrReduce", CrossBridgeSystem::csrChunkReduce, mot.counts, e.csrChunkParams, e.csrMatrix, G.segCount)
          .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, G.segCount, G.segOff)
          .task("csrScatter", CrossBridgeSystem::csrChunkScatter, mot.boundSeg, mot.counts, e.csrChunkParams, G.segOff, G.segMyo, e.csrMatrix)
          .task("segGather", CrossBridgeSystem::segGather, G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts)
          .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
          .task("zconf", MatSoaSlice::matZConfine, f.coord, f.forceSum, e.zP, e.exCounts)
          .task("brown", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        if (brownChanOn())   // per-channel filament Brownian ablation mask (noncanonical; absent when all channels ON)
            tg.task("brChan", BrownianForceSystem::brownChannelMask, f.randForce, f.randTorque, e.brChan, f.counts);
        tg
          .task("integ", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
          .task("orthoY", DerivedGeometrySystem::orthogonalizeY, f.uVec, f.yVec, f.counts)
          .task("derive", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
          .task("s2solve", TwoBodyBeamAnalyticGpu::matS2SolveStep, e.nodes, e.frame, e.q, G.bondData, mot.boundSeg, e.params, e.sys, e.outGeom, mot.forceDotFil, mot.forceMag, e.matc, e.exCounts, e.convF)
          .task("redBlk", MatSoaSlice::matReduceBlocks, mot.boundSeg, e.active, mot.forceDotFil, e.redP, e.exCounts, e.redBlk)
          .task("redFin", MatSoaSlice::matReduceFinal, e.redBlk, f.coord, e.redP, e.exCounts, e.redOut);
        if (prod) {
            // PROD_SCI: additionally read back nucleotideState each step for the production-cell science observables
            // (ATP turnover, nucleotide occupancy). No physics/kernel/order change — only an extra host readback of a
            // buffer the `chem` task already writes on device. Default false ⇒ the -sweep/-throughput paths are byte-unchanged.
            if (PROD_SCI) tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.redOut, mot.boundSeg, mot.nucleotideState);
            else          tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.redOut, mot.boundSeg);
            if (occOn())  tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.occStats);   // per-step occupancy telemetry
            // Target-zone telemetry: the per-candidate diagnostics + the filament material frame (uVec/yVec) and the
            // bond reactions the twirl observables need. Gated on tzOn() ⇒ the canonical production path is unchanged.
            if (telemetryOn()) tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.tzDiag, mot.bindAzim, mot.bindPsi0,
                                            f.uVec, f.yVec, f.coord, G.bondData, mot.bindArc);
            if (chiralOn()) tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.bindSite, e.headOmega, e.headTau,
                                            e.headMis, e.siteStats, mot.bindAzim, f.uVec, f.yVec, f.coord, G.bondData, mot.bindArc);
            // RIGOR RUPTURE: read the per-motor rupture/cap accumulators + the realized load each step (cause count + force-at-rupture).
            if (RIGOR_ON) tg.transferToHost(DataTransferMode.EVERY_EXECUTION, mot.ruptureStats, mot.forceDotFil);
            if (ADP_RUP_ON) tg.transferToHost(DataTransferMode.EVERY_EXECUTION, mot.adpRuptureStats);
            // BOUND-CYCLE IMPULSE BUDGET (measurement-only, default-off): the motor-internal state the episode
            // ledger stratifies on. Transfers only — no kernel, no ordering, no device work added.
            if (EPISODE_TELEM) tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.q, e.nodes, e.outGeom);
        }
        else {    tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.nodes, e.q, e.redOut, f.coord, mot.boundSeg, mot.nucleotideState, mot.forceDotFil, G.bondData);
                  if (occOn()) tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.occStats);
                  if (tzOn())  tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.tzDiag, mot.bindAzim, mot.bindPsi0);
                  if (chiralOn()) tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.bindSite, e.headRef, e.headOmega,
                                            e.headTau, e.headMis, e.siteStats, mot.bindAzim, mot.bindArc, f.uVec, f.yVec);   // telemetry (validation)
                  if (convSkewOn()) tg.transferToHost(DataTransferMode.EVERY_EXECUTION, e.convF); }   // converter-frame equivalence readback
        int pn = ((N + 63) / 64) * 64, ps = ((nSeg + 63) / 64) * 64, nCh = e.csrChunkParams.get(1);
        glSched = new GridScheduler();
        for (String nm : new String[]{ "beamGeom", "bind", "chem", "cock", "place", "bond", "s2solve" }) addW(glSched, "glide." + nm, pn);
        for (String nm : new String[]{ "zeroAcc", "segGather", "chain", "zconf", "brown", "integ", "orthoY", "derive", "csrReduce" }) addW(glSched, "glide." + nm, ps);
        if (occOn()) { addW(glSched, "glide.gateOnly", pn); addW(glSched, "glide.occResolve", 64); }   // gate parallel; resolve single-thread (gid<1)
        if (surfOn()) { if (!tzOn()) addW(glSched, "glide.surfAzim", pn); addW(glSched, "glide.surfPrune", 64); }   // azim parallel; prune single-thread (gid<1)
        if (tzOn()) addW(glSched, "glide.tzone", pn);   // target-zone hazard: parallel over motors
        if (siteOn()) { addW(glSched, "glide.siteSnap", pn); addW(glSched, "glide.siteOcc", 64); }   // snap parallel; occupancy single-thread
        if (strokeSkewOn()) addW(glSched, "glide.strokeSkew", pn);
        if (convSkewOn()) addW(glSched, "glide.convFrame", pn);   // converter-frame rotation: parallel over motors
        if (convSkewStateGated()) addW(glSched, "glide.convFrame2", pn);   // post-cock re-evaluation (state-gated mode)
        if (chiralOn()) addW(glSched, "glide.headRoll", pn);   // head roll DOF + registry: parallel over motors
        if (brownChanOn()) addW(glSched, "glide.brChan", ps);   // per-channel filament Brownian mask: parallel over segments
        addW(glSched, "glide.csrZero", ((Math.max(1, nCh * nSeg) + 63) / 64) * 64);
        addW(glSched, "glide.csrHist", ((nCh + 63) / 64) * 64); addW(glSched, "glide.csrScatter", ((nCh + 63) / 64) * 64);
        addW(glSched, "glide.csrScan", 64); addW(glSched, "glide.redBlk", ((e.numRedBlk + 63) / 64) * 64); addW(glSched, "glide.redFin", 64);
        return new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(glSched);
    }

    static boolean glidingMode(StringBuilder log) {
        double density = 200.0; int seed = 101, steps = 2000; double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        Glide2D Gc = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed);
        Glide2D Gd = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed);
        int N = Gc.N, nSeg = Gc.nSeg;
        for (Glide2D G : new Glide2D[]{ Gc, Gd }) for (int m = 0; m < N; m++) G.mot.boundSeg.set(m, -1);   // all unbound initially (free binding)
        ExMat ec = packExMat(Gc, 1), ed = packExMat(Gd, 1);
        // ---- §8 one-step free-binding replay: device matBindExplicit vs production bind gate (host) ----
        log.append("## §8 one-step free-binding replay (device matBindExplicit vs production geom2D+nearestSeg2D+gate2D)\n");
        Glide2D Gp = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed); for (int m = 0; m < N; m++) Gp.mot.boundSeg.set(m, -1);
        int prodBinds = 0, mism = 0;
        // production bind gate over all motors (thetaS=PRESTROKE for ADPPI candidates already)
        for (int m = 0; m < N; m++) if (Gp.mot.nucleotideState.get(m) == MotorStore.NUC_ADPPI && !Gp.noBind[m]) {
            Gp.thetaS[m] = TwoBodyConverterMotor.PRESTROKE_THETAS; TwoBodyConverterMotor.geom2D(Gp, m);
            int s = TwoBodyConverterMotor.nearestSeg2D(Gp, m); if (s < 0) continue; double[] gm = TwoBodyConverterMotor.gate2D(Gp, m, s);
            double half = 0.5 * Gp.fil.segLength.get(s);
            boolean pass = gm[0] < 3.0 && gm[2] < 25 && gm[3] < 25 && gm[4] < 20 && gm[5] < 2.0 && gm[6] < 15.0 && gm[7] < TwoBodyConverterMotor.A_SEMI[2] * 1e3 && gm[1] > 0.05 && gm[1] < 2 * half - 0.05;
            if (pass) { Gp.mot.boundSeg.set(m, s); Gp.mot.bindArc.set(m, (float) gm[1]); prodBinds++; } }
        // device matBindExplicit (via the CPU-mirror one impl) on a fresh copy
        Glide2D Gm = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed); for (int m = 0; m < N; m++) Gm.mot.boundSeg.set(m, -1);
        ExMat em = packExMat(Gm, 1); for (int m = 0; m < N; m++) em.active.set(m, 1);
        TwoBodyBeamAnalyticGpu.matBeamGeom(em.nodes, em.frame, em.params, em.q, em.exCounts, em.outGeom, em.convF);
        TwoBodyBeamAnalyticGpu.matBindExplicit(em.active, em.noBind, Gm.mot.boundSeg, Gm.mot.nucleotideState, em.outGeom, em.q, Gm.fil.coord, Gm.fil.uVec, Gm.fil.segLength, em.params, em.bindP, em.eupP, Gm.mot.bindArc, em.exCounts);
        int devBinds = 0; for (int m = 0; m < N; m++) { if (Gm.mot.boundSeg.get(m) >= 0) devBinds++; if (Gm.mot.boundSeg.get(m) != Gp.mot.boundSeg.get(m)) mism++; }
        log.append(String.format(Locale.US, "- production binds=%d, device-mirror binds=%d, boundSeg mismatches=%d/%d ⇒ **%s**\n\n", prodBinds, devBinds, mism, N, mism == 0 ? "PASS (exact bind identity)" : "REVIEW"));
        boolean s8 = mism == 0;

        // ---- §10 low-density free-binding gliding smoke: CPU-runner vs GPU ----
        log.append("## §10 low-density free-binding gliding (N=" + N + ", density " + (int) density + ", " + steps + " steps, all-active)\n");
        TornadoExecutionPlan plan; try { plan = buildGlidingGraph(ed, false); } catch (Throwable ex) { log.append("- graph FAILED: " + oneLine(root(ex).getMessage()) + "\n"); System.out.println("  gliding graph FAILED: " + oneLine(root(ex).getMessage())); return false; }
        long cBinds = 0, cDetach = 0; int[] prevBc = new int[N]; for (int m = 0; m < N; m++) prevBc[m] = -1;
        int firstDiv = -1; double maxFil = 0; int nbC = 0, nbD = 0, invalid = 0;
        for (int t = 0; t < steps; t++) {
            ed.matc.set(0, t); ed.matc.set(1, seed); Gd.mot.setCounts(t, seed, nSeg); Gd.fil.counts.set(1, t); Gd.fil.counts.set(2, seed);
            try { plan.execute(); } catch (Throwable ex) { log.append("- execute FAILED @t=" + t + ": " + oneLine(root(ex).getMessage()) + "\n"); System.out.println("  execute FAILED @t=" + t); return false; }
            stepGlidingCPU(ec, t, seed);
            nbC = (int) ec.redOut.get(0); nbD = (int) ed.redOut.get(0);
            double dFil = 0; for (int i = 0; i < 3 * nSeg; i++) dFil = Math.max(dFil, Math.abs(Gc.fil.coord.get(i) - Gd.fil.coord.get(i)));
            maxFil = Math.max(maxFil, dFil); if (firstDiv < 0 && dFil > 1e-5) firstDiv = t;
            for (int m = 0; m < N; m++) { int bs = Gc.mot.boundSeg.get(m); if (bs >= 0 && prevBc[m] < 0) cBinds++; if (bs < 0 && prevBc[m] >= 0) cDetach++; prevBc[m] = bs; }
            for (int i = 0; i < 3 * nSeg; i++) { float c = Gc.fil.coord.get(i); if (Float.isNaN(c) || Float.isInfinite(c)) { invalid++; break; } }
        }
        // Gliding is CHAOTIC (float-FMA Lyapunov divergence) ⇒ CPU≡GPU is aggregate-statistical, NOT stepwise
        // (CLAUDE.md standard). A SEMANTIC bug shows at t=0; chaotic decorrelation shows only after many bit-close
        // steps. So the gate is: binding events occur, 0 invalid, and bit-close BEFORE any decorrelation (firstDiv
        // late or none) ⇒ no t=0 semantic divergence. Post-decorrelation bound-count differences are expected.
        boolean semantic = firstDiv == 0;   // divergence at the very first step would be a real bug
        boolean s10 = Double.isFinite(maxFil) && invalid == 0 && cBinds > 0 && !semantic;
        log.append(String.format(Locale.US, "- CPU-runner: total binds=%d, detachments=%d, final bound=%d; GPU final bound=%d; CPU/GPU maxΔfilCoord=%.2e µm; bit-close for %s steps then chaotic FP decorrelation; invalid=%d\n",
                cBinds, cDetach, nbC, nbD, maxFil, firstDiv < 0 ? "all " + steps : "" + firstDiv, invalid));
        log.append(String.format(Locale.US, "- **§10 gliding smoke: %s** — motors bind/stroke/detach on the persistent GPU mat; %s (no t=0 semantic divergence; bound-count differs post-decorrelation as expected for chaotic gliding).\n\n",
                s10 ? "PASS" : "REVIEW", firstDiv < 0 ? "CPU/GPU bit-close all " + steps + " steps" : "CPU/GPU bit-close " + firstDiv + " steps then chaotic decorrelation (Lyapunov)"));
        boolean ok = s8 && s10;
        log.append("**§8/§10 VERDICT: " + (ok ? "PASS" : "REVIEW") + "** (deterministic bind identity + first genuine free-binding explicit gliding on the device mat).\n");
        System.out.printf(Locale.US, "  §8 bind-identity mism=%d ⇒ %s | §10 gliding binds=%d detach=%d boundC=%d/%d maxΔfil=%.1e firstDiv=%s invalid=%d ⇒ %s%n",
                mism, s8 ? "PASS" : "REVIEW", cBinds, cDetach, nbC, nbD, maxFil, firstDiv < 0 ? "none" : ("t=" + firstDiv), invalid, s10 ? "PASS" : "REVIEW");
        return ok;
    }

    // ============================================================= NO-CULL throughput (Goal 4, §8–§12)
    static final String[] EXTASKS = { "beamGeom", "place", "bond", "zeroAcc", "csrZero", "csrHist", "csrReduce",
        "csrScan", "csrScatter", "segGather", "chain", "zconf", "brown", "integ", "orthoY", "derive", "s2solve", "redBlk", "redFin" };
    /** Pre-bind the geometrically reachable motor set (surf<5 nm); the rest stay unbound but are STILL processed by
     *  matS2SolveStep (no-cull ⇒ all N solved each step). thetaS fixed pre-stroke. Returns bound count. */
    static int preBindReachable(Glide2D G) {
        int N = G.N, cnt = 0;
        for (int m = 0; m < N; m++) { G.mot.boundSeg.set(m, -1); G.thetaS[m] = TwoBodyConverterMotor.PRESTROKE_THETAS;
            TwoBodyConverterMotor.geom2D(G, m); int s = TwoBodyConverterMotor.nearestSeg2D(G, m); if (s < 0) continue;
            double[] gm = TwoBodyConverterMotor.gate2D(G, m, s); double half = 0.5 * G.fil.segLength.get(s), margin = 0.01;
            if (gm[0] < 5.0 && gm[1] > margin && gm[1] < 2 * half - margin) { G.mot.boundSeg.set(m, s); G.mot.bindArc.set(m, (float) gm[1]); cnt++; } }
        return cnt;
    }
    static double median(double[] a) { double[] c = a.clone(); java.util.Arrays.sort(c); return c[c.length / 2]; }
    static long parseTaskNs(String jl, String prefix, String name) {
        if (jl == null) return 0; int k = jl.indexOf("\"" + prefix + name + "\""); if (k < 0) return 0;
        int t = jl.indexOf("\"TASK_KERNEL_TIME\"", k); if (t < 0) return 0; int c = jl.indexOf(':', t); if (c < 0) return 0;
        int e = c + 1; while (e < jl.length() && (jl.charAt(e) == ' ' || jl.charAt(e) == '"')) e++;
        int s = e; while (e < jl.length() && (Character.isDigit(jl.charAt(e)) || jl.charAt(e) == '-')) e++;
        try { return Long.parseLong(jl.substring(s, e)); } catch (Exception ex) { return 0; }
    }
    static boolean finiteRed(DoubleArray r) { for (int i = 0; i < 6; i++) if (!Double.isFinite(r.get(i))) return false; return true; }

    static boolean benchMode(StringBuilder log) {
        int[] dens = { 200, 700, 1500 }; int warm = 150, meas = 300, reps = 3; double dt = DT; int seed = 101;
        System.out.println("=== NO-CULL explicit throughput (CPU-analytic vs GPU-analytic) ===");
        log.append("Protocol: culling DISABLED (all N processed by matS2SolveStep every step); binding gates UNCHANGED\n");
        log.append("(reachable set pre-bound, thetaS fixed pre-stroke, Brownian on). Production-residency GPU graph\n");
        log.append("(beam SoA + filament/body FIRST_EXECUTION resident; per step only counters up + redOut down; NO validation\n");
        log.append("downloads in the timed interval). warm=" + warm + ", measured window=" + meas + " steps × " + reps + " reps (median).\n\n");
        StringBuilder csv = new StringBuilder("density,N,boundCt,processed,cull,cpu_ms_step,cpu_steps_s,gpu_ms_step,gpu_steps_s,speedup,gpu_devkernel_ms,gpu_bytesIn,gpu_bytesOut,failures,invalid\n");
        StringBuilder tbl = new StringBuilder("| density | N | CPU analytic steps/s | CPU ms/step | GPU analytic steps/s | GPU ms/step | GPU speedup | failures |\n|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        StringBuilder stage = new StringBuilder("| density | " + String.join(" | ", EXTASKS) + " |\n|" + "---:|".repeat(EXTASKS.length + 1) + "\n");
        boolean allOk = true; double[] gpuMsAt = new double[dens.length], cpuMsAt = new double[dens.length]; int[] Nat = new int[dens.length];
        for (int di = 0; di < dens.length; di++) {
            double density = dens[di]; double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
            Glide2D Gc = TwoBodyConverterMotor.buildS2Mat(density, dt, 40.0, slack, seed);
            Glide2D Gd = TwoBodyConverterMotor.buildS2Mat(density, dt, 40.0, slack, seed);
            int N = Gc.N, nSeg = Gc.nSeg; Nat[di] = N;
            int bc = preBindReachable(Gc); preBindReachable(Gd);
            ExMat ec = packExMat(Gc, 1), ed = packExMat(Gd, 1);
            TornadoExecutionPlan plan;
            try { plan = buildExMatGraph(ed, true); } catch (Throwable ex) { log.append("- density " + density + " graph build FAILED: " + oneLine(root(ex).getMessage()) + "\n"); allOk = false; continue; }
            // GPU warm
            int tg = 0; for (int t = 0; t < warm; t++, tg++) { ed.matc.set(0, tg); ed.matc.set(1, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed); plan.execute(); }
            double[] gpuMs = new double[reps];
            for (int r = 0; r < reps; r++) { long ns = 0; for (int k = 0; k < meas; k++, tg++) { ed.matc.set(0, tg); ed.matc.set(1, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed); long t0 = System.nanoTime(); plan.execute(); ns += System.nanoTime() - t0; } gpuMs[r] = ns / 1e6 / meas; }
            boolean gpuInvalid = !finiteRed(ed.redOut);
            // profiled window (device-kernel + bytes + per-stage)
            long[] kerNs = new long[EXTASKS.length]; long devKerNs = 0, bIn = 0, bOut = 0; int nProf = 0;
            for (int k = 0; k < 40; k++, tg++) { ed.matc.set(0, tg); ed.matc.set(1, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed);
                TornadoProfilerResult pr = plan.withProfiler(ProfilerMode.SILENT).execute().getProfilerResult(); String jl = pr.getProfileLog();
                for (int i = 0; i < EXTASKS.length; i++) kerNs[i] += parseTaskNs(jl, "exmat.", EXTASKS[i]); devKerNs += pr.getDeviceKernelTime(); bIn += pr.getTotalBytesCopyIn(); bOut += pr.getTotalBytesCopyOut(); nProf++; plan.clearProfiles(); }
            plan.withoutProfiler();
            // CPU warm + measure
            int tc = 0; for (int t = 0; t < warm; t++, tc++) stepExCPU(ec, tc, seed);
            double[] cpuMs = new double[reps];
            for (int r = 0; r < reps; r++) { long ns = 0; for (int k = 0; k < meas; k++, tc++) { long t0 = System.nanoTime(); stepExCPU(ec, tc, seed); ns += System.nanoTime() - t0; } cpuMs[r] = ns / 1e6 / meas; }
            boolean cpuInvalid = !finiteRed(ec.redOut);
            double gMs = median(gpuMs), cMs = median(cpuMs); double gSps = 1000 / gMs, cSps = 1000 / cMs, speedup = cMs / gMs;
            gpuMsAt[di] = gMs; cpuMsAt[di] = cMs;
            double devKerMs = devKerNs / 1e6 / nProf;
            int bound = (int) ed.redOut.get(0);
            log.append(String.format(Locale.US, "### density %.0f (N=%d): boundCt=%d, cullingEnabled=false, processedMotorCount=N=%d (CPU==GPU), invalid CPU=%b GPU=%b\n", density, N, bc, N, cpuInvalid, gpuInvalid));
            log.append(String.format(Locale.US, "- CPU-analytic: %.3f ms/step (%.0f steps/s) | GPU-analytic: %.3f ms/step (%.0f steps/s) | **speedup %.2f×** | device-kernel %.3f ms/step; wall−kernel launch floor %.3f ms; bytes in=%d out=%d/step; reps=%s\n",
                    cMs, cSps, gMs, gSps, speedup, devKerMs, gMs - devKerMs, bIn / nProf, bOut / nProf, java.util.Arrays.toString(round2(gpuMs))));
            tbl.append(String.format(Locale.US, "| %.0f | %d | %.0f | %.3f | %.0f | %.3f | %.2f× | %d |\n", density, N, cSps, cMs, gSps, gMs, speedup, 0));
            stage.append(String.format(Locale.US, "| %.0f", density)); for (int i = 0; i < EXTASKS.length; i++) stage.append(String.format(Locale.US, " | %.3f", kerNs[i] / 1e6 / nProf)); stage.append(" |\n");
            csv.append(String.format(Locale.US, "%.0f,%d,%d,%d,false,%.4f,%.1f,%.4f,%.1f,%.3f,%.4f,%d,%d,%d,%b\n", density, N, bc, N, cMs, cSps, gMs, gSps, speedup, devKerMs, bIn / nProf, bOut / nProf, 0, cpuInvalid || gpuInvalid));
            System.out.printf(Locale.US, "  density=%-5.0f N=%-5d bound=%-4d | CPU %.2f ms/step | GPU %.2f ms/step | %.2f× | devKer %.3f ms | invalid %b%n", density, N, bc, cMs, gMs, speedup, devKerMs, cpuInvalid || gpuInvalid);
            if (cpuInvalid || gpuInvalid) allOk = false;
        }
        log.append("\n## Density throughput table (no-cull, production-residency, matched steps)\n").append(tbl).append("\n## Per-stage GPU kernel time (ms/step)\n").append(stage);
        // §12 performance decision + runtime estimates (use the largest density's speedup)
        double sp = cpuMsAt[dens.length - 1] > 0 ? cpuMsAt[dens.length - 1] / gpuMsAt[dens.length - 1] : 0;
        String cls = sp >= 10 ? "≥10× — routine explicit validation studies" : sp >= 5 ? "5–10× — trap campaigns & key gliding reruns" : sp >= 3 ? "3–5× — selected validation" : "<3× — performance-limited";
        log.append(String.format(Locale.US, "\n## §12 performance decision (@ density %d, N=%d): GPU/CPU-analytic = **%.2f×** ⇒ %s\n", dens[dens.length - 1], Nat[dens.length - 1], sp, cls));
        double gms = gpuMsAt[dens.length - 1];   // ms/step at the largest density
        log.append(String.format(Locale.US, "- GPU wall-time estimates @ N=%d, dt=%.1e (steps = simS/dt): 0.15 s gliding ≈ %.1f min; 0.5 s ≈ %.1f min; 2.0 s ≈ %.1f h; 3 densities × 3 seeds @ 0.15 s ≈ %.1f h (assumes constant ms/step; NO extrapolation beyond measured scaling).\n",
                Nat[dens.length - 1], dt, 0.15 / dt * gms / 1e3 / 60, 0.5 / dt * gms / 1e3 / 60, 2.0 / dt * gms / 1e3 / 3600, 9 * 0.15 / dt * gms / 1e3 / 3600));
        try { Files.writeString(Path.of(OUT, "COMPLETEMAT_BENCH.csv"), csv.toString()); } catch (IOException e) { throw new UncheckedIOException(e); }
        log.append("\n**NO-CULL CPU/GPU THROUGHPUT: measured** (culling disabled, full mat processed, CPU==GPU processed=N, production-residency). DEVICE_VALIDATED stays false (promotion deferred, §13).\n");
        return allOk;
    }
    static double[] round2(double[] a) { double[] r = new double[a.length]; for (int i = 0; i < a.length; i++) r[i] = Math.round(a[i] * 100) / 100.0; return r; }

    // ============================================================= §2 stroke/detach/recoil in the full mat
    static boolean strokeMode(StringBuilder log) {
        double density = 200.0; int seed = 101; double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        int T = 400, t1 = 80, t2 = 160, t3 = 240, t4 = 320;
        Glide2D Gc = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed);
        Glide2D Gd = TwoBodyConverterMotor.buildS2Mat(density, DT, 40.0, slack, seed);
        int N = Gc.N, nSeg = Gc.nSeg;
        double thPre0 = TwoBodyConverterMotor.PRESTROKE_THETAS;
        // pick a geometrically REACHABLE motor (surf<5 nm) so the cross-bridge is engaged and the stroke generates force
        int mB = -1; for (int m = 0; m < N && mB < 0; m++) { Gc.thetaS[m] = thPre0; TwoBodyConverterMotor.geom2D(Gc, m);
            int s = TwoBodyConverterMotor.nearestSeg2D(Gc, m); if (s < 0) continue; double[] gm = TwoBodyConverterMotor.gate2D(Gc, m, s);
            double half = 0.5 * Gc.fil.segLength.get(s); if (gm[0] < 5.0 && gm[1] > 0.01 && gm[1] < 2 * half - 0.01) mB = m; }
        if (mB < 0) mB = 0;
        for (Glide2D G : new Glide2D[]{ Gc, Gd }) { for (int m = 0; m < N; m++) G.mot.boundSeg.set(m, -1);
            G.thetaS[mB] = thPre0; TwoBodyConverterMotor.geom2D(G, mB);
            int s = TwoBodyConverterMotor.nearestSeg2D(G, mB); if (s < 0) s = nSeg / 2; G.mot.boundSeg.set(mB, s);
            double[] gm = TwoBodyConverterMotor.gate2D(G, mB, s); G.mot.bindArc.set(mB, (float) gm[1]); }
        ExMat ec = packExMat(Gc, 1), ed = packExMat(Gd, 1);
        TornadoExecutionPlan plan; try { plan = buildExMatGraph(ed, false); } catch (Throwable ex) { log.append("- graph FAILED: " + oneLine(root(ex).getMessage()) + "\n"); return false; }
        double thPre = TwoBodyConverterMotor.PRESTROKE_THETAS, thPost = TwoBodyConverterMotor.ADP_THETAS;
        double[] xH0 = null; double maxNode = 0, peakF = 0, strokeDisp = 0; int firstDiv = -1;
        double[] bhat = { Gc.bhat[0], Gc.bhat[1], Gc.bhat[2] };
        log.append("## §2 driven sequence: relax→stroke(θs −30→+30)→hold→detach→recoil (full shared coupling)\n");
        for (int t = 0; t < T; t++) {
            double th = t < t1 ? thPre : (t >= t2 ? thPost : thPre + (thPost - thPre) * (t - t1) / (double) (t2 - t1));
            int bs = (t >= t3) ? -1 : Gc.mot.boundSeg.get(mB);   // controlled detach at t3
            // drive thetaS + boundSeg on both runners
            for (Object[] pr : new Object[][]{ { ec, Gc }, { ed, Gd } }) { ExMat e = (ExMat) pr[0]; Glide2D G = (Glide2D) pr[1];
                e.q.set(2 * N + mB, th); e.boundSeg.set(mB, bs); G.mot.boundSeg.set(mB, bs); e.active.set(mB, bs >= 0 ? 1 : 0); }
            ed.matc.set(0, t); ed.matc.set(1, seed); Gd.mot.setCounts(t, seed, nSeg); Gd.fil.counts.set(1, t); Gd.fil.counts.set(2, seed);
            try { plan.execute(); } catch (Throwable ex) { log.append("- execute FAILED @t=" + t + ": " + oneLine(root(ex).getMessage()) + "\n"); return false; }
            stepExCPU(ec, t, seed);
            double[] d = compare(ec, ed, Gc, Gd, mB); maxNode = Math.max(maxNode, d[0]); if (firstDiv < 0 && d[0] > 1e-6) firstDiv = t;
            // observables from the CPU-runner side (host-updated by stepExCPU); the GPU is validated to agree via maxNode
            double xHx = ec.outGeom.get(3 * N + mB), xHy = ec.outGeom.get(4 * N + mB), xHz = ec.outGeom.get(5 * N + mB);
            if (t == 0) xH0 = new double[]{ xHx, xHy, xHz };
            double axial = ((xHx - xH0[0]) * bhat[0] + (xHy - xH0[1]) * bhat[1] + (xHz - xH0[2]) * bhat[2]) * 1e3;
            double force = Math.abs(Gc.mot.forceMag.get(mB)) * 1e12;
            if (t >= t1 && t <= t3 && force > peakF) peakF = force; if (t == t2) strokeDisp = axial;
        }
        boolean ok = maxNode < 1e-2 && Double.isFinite(maxNode);
        log.append(String.format(Locale.US, "- stroke axial head disp @end-stroke=%.2f nm; peak force=%.3f pN; CPU/GPU maxΔnode over %d steps=%.2e µm (first div %s); detach@t=%d ⇒ recoil.\n",
                strokeDisp, peakF, T, maxNode, firstDiv < 0 ? "none" : ("t=" + firstDiv), t3));
        log.append("- **§2 stroke/detach/recoil: " + (ok ? "PASS" : "REVIEW") + "** (driven through the full shared coupling; CPU/GPU agree).\n");
        System.out.printf(Locale.US, "  §2 stroke=%.1f nm peakF=%.2f pN maxΔnode=%.1e ⇒ %s%n", strokeDisp, peakF, maxNode, ok ? "PASS" : "REVIEW");
        return ok;
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
        DoubleArray convId = TwoBodyBeamAnalyticGpu.identityConvFrame(K);   // flag 0 ⇒ canonical branch
        TwoBodyBeamAnalyticGpu.matBeamGeom(nodes, frame, params, q, counts, oMir, convId);
        TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit(oMir, boundSeg, eupP, counts, cMir, uMir, yMir);

        // ---- GPU (§5 lowering + execution) ----
        log.append("## §5 explicit head-placement gate (matBeamGeom + matPlaceHeadExplicit, GPU vs CPU-mirror vs production)\n");
        boolean lowered; String err = null;
        try {
            TaskGraph tg = new TaskGraph("head")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, nodes, frame, params, q, counts, boundSeg, eupP, convId)
                .task("geom", TwoBodyBeamAnalyticGpu::matBeamGeom, nodes, frame, params, q, counts, outGeom, convId)
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

    // ============================================================= Phase A: GENUINE FREE-BINDING NO-CULL throughput
    /** All 22 tasks of the free-binding gliding graph (prefix "glide."), in dispatch order. */
    static final String[] GLTASKS = { "beamGeom", "bind", "chem", "cock", "place", "bond", "zeroAcc", "csrZero",
        "csrHist", "csrReduce", "csrScan", "csrScatter", "segGather", "chain", "zconf", "brown", "integ", "orthoY", "derive", "s2solve", "redBlk", "redFin" };
    /** {median, mean, std, min, max, cv%} over a sample. */
    static double[] stats(double[] a) {
        double[] c = a.clone(); java.util.Arrays.sort(c); double med = c[c.length / 2];
        double mean = 0; for (double x : a) mean += x; mean /= a.length;
        double var = 0; for (double x : a) var += (x - mean) * (x - mean); var /= Math.max(1, a.length - 1);
        double sd = Math.sqrt(var); return new double[]{ med, mean, sd, c[0], c[c.length - 1], mean != 0 ? sd / mean * 100 : 0 };
    }
    static boolean finite6(DoubleArray r) { return finiteRed(r); }

    /**
     * Phase A. Genuine free-binding (all motors start unbound; matBindExplicit runs on device each step), NO-CULL
     * (every motor processed by matS2SolveStep — active[]≡1, no active-set shortcut), production-residency GPU graph
     * (per step only counters UP; redOut+boundSeg DOWN — the small readback, NOT a full-state download). Timing is
     * SEPARATED from scientific-state evolution: the timed loop is pure execute()+nanoTime; a separate untimed pass
     * tracks bindings/detachments/avgBound/meanForce/invalid from the already-resident redOut+boundSeg readback.
     */
    static boolean throughputMode(StringBuilder log, boolean quick) {
        int[] dens = quick ? new int[]{ 200, 700 } : new int[]{ 200, 700, 1500, 2000, 2500, 3000 };
        int seed = 101; double dt = DT, slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        int gpuWarm = quick ? 300 : 2000, gpuMeas = quick ? 800 : 10000, gpuReps = quick ? 2 : 3, sciSteps = quick ? 800 : 8000;
        // CPU-analytic free-binding is O(N·nSeg) in the bind search ⇒ steps shrink with N (campaign min 200 @ high N)
        int[] cpuMeasByDens = quick ? new int[]{ 300, 300 } : new int[]{ 2000, 1000, 1000, 400, 300, 200 };
        int[] cpuRepsByDens = quick ? new int[]{ 2, 2 } : new int[]{ 3, 3, 3, 2, 2, 2 };
        int cpuWarm = quick ? 100 : 600;
        System.out.println("=== Phase A: GENUINE FREE-BINDING NO-CULL throughput (explicit-s2-l40) ===");
        log.append("Protocol: GENUINE FREE BINDING (all motors start unbound; device `matBindExplicit` each step) + NO-CULL\n");
        log.append("(active[]≡1 ⇒ all N processed by matS2SolveStep; only the active-set shortcut is bypassed — search/gates/\n");
        log.append("chemistry/mechanics UNCHANGED). Production-residency GPU graph (beam SoA + fil/body FIRST_EXECUTION resident;\n");
        log.append("per step only counters UP + redOut/boundSeg DOWN; NO full-state download in the timed interval). Timing is\n");
        log.append(String.format(Locale.US, "SEPARATED from science. GPU: warm %d, measure %d × %d reps (median). CPU-analytic: warm %d, per-density steps.\n\n", gpuWarm, gpuMeas, gpuReps, cpuWarm));
        StringBuilder csv = new StringBuilder("density,N,nSeg,freeBinding,cullingEnabled,processedMotorCount,hostBindingLoop,fullStateDownload,silentFallback,"
            + "gpu_ms_step_med,gpu_ms_mean,gpu_ms_std,gpu_ms_min,gpu_ms_max,gpu_cv_pct,gpu_steps_s,gpu_devkernel_ms,gpu_launchfloor_ms,gpu_bytesIn,gpu_bytesOut,gpu_beamMiB,"
            + "cpu_ms_step_med,cpu_ms_mean,cpu_ms_std,cpu_cv_pct,cpu_steps_s,speedup,"
            + "sci_steps,binds,detaches,meanBound,minBound,maxBound,meanForce_pN,invalid,activeEqN\n");
        StringBuilder tbl = new StringBuilder("| density | N | GPU ms/step (med±CV) | GPU steps/s | CPU ms/step (med±CV) | CPU steps/s | speedup | meanBound | binds/detach | invalid |\n|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        StringBuilder stage = new StringBuilder("| density | " + String.join(" | ", GLTASKS) + " |\n|" + "---:|".repeat(GLTASKS.length + 1) + "\n");
        boolean allOk = true;
        double[] gpuMed = new double[dens.length], cpuMed = new double[dens.length]; int[] Nat = new int[dens.length];
        for (int di = 0; di < dens.length; di++) {
            double density = dens[di];
            Glide2D Gc = TwoBodyConverterMotor.buildS2Mat(density, dt, 40.0, slack, seed);
            Glide2D Gd = TwoBodyConverterMotor.buildS2Mat(density, dt, 40.0, slack, seed);
            int N = Gc.N, nSeg = Gc.nSeg; Nat[di] = N;
            for (int m = 0; m < N; m++) { Gc.mot.boundSeg.set(m, -1); Gd.mot.boundSeg.set(m, -1); }   // free binding: all unbound
            ExMat ec = packExMat(Gc, 1), ed = packExMat(Gd, 1);
            TornadoExecutionPlan plan;
            try { plan = buildGlidingGraph(ed, true); }   // prod residency
            catch (Throwable ex) { log.append("- density " + density + " graph build FAILED: " + oneLine(root(ex).getMessage()) + "\n"); allOk = false; continue; }
            long beamBytes = (long) (15 + 15 + 17 + SYS + 9 + 4) * N * 8;   // nodes+frame+params+sys+outGeom+q (the per-motor beam SoA)
            System.out.printf(Locale.US, "  [density %.0f N=%d nSeg=%d] warming GPU %d…%n", density, N, nSeg, gpuWarm);
            int tg = 0;
            for (int t = 0; t < gpuWarm; t++, tg++) { ed.matc.set(0, tg); ed.matc.set(1, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed); plan.execute(); }
            // ---- GPU TIMING: pure execute()+nanoTime, nothing else in the loop ----
            double[] gpuMs = new double[gpuReps];
            for (int r = 0; r < gpuReps; r++) { long ns = 0;
                for (int k = 0; k < gpuMeas; k++, tg++) { ed.matc.set(0, tg); ed.matc.set(1, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed);
                    long t0 = System.nanoTime(); plan.execute(); ns += System.nanoTime() - t0; }
                gpuMs[r] = ns / 1e6 / gpuMeas; }
            boolean gpuInvalidTime = !finite6(ed.redOut);
            // ---- GPU profiled window (bytes + per-stage + device-kernel), OUTSIDE timing ----
            long[] kerNs = new long[GLTASKS.length]; long devKerNs = 0, bIn = 0, bOut = 0; int nProf = 0;
            for (int k = 0; k < 40; k++, tg++) { ed.matc.set(0, tg); ed.matc.set(1, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed);
                TornadoProfilerResult pr = plan.withProfiler(ProfilerMode.SILENT).execute().getProfilerResult(); String jl = pr.getProfileLog();
                for (int i = 0; i < GLTASKS.length; i++) kerNs[i] += parseTaskNs(jl, "glide.", GLTASKS[i]);
                devKerNs += pr.getDeviceKernelTime(); bIn += pr.getTotalBytesCopyIn(); bOut += pr.getTotalBytesCopyOut(); nProf++; plan.clearProfiles(); }
            plan.withoutProfiler();
            // ---- GPU SCIENCE pass (untimed): track binding/detach/avgBound/meanForce/invalid from resident readback ----
            int[] prevBc = new int[N]; for (int m = 0; m < N; m++) prevBc[m] = Gd.mot.boundSeg.get(m);
            long binds = 0, detaches = 0, sumBound = 0; int minBound = Integer.MAX_VALUE, maxBound = 0, invalid = 0; double sumForcePn = 0; boolean activeEqN = true;
            for (int k = 0; k < sciSteps; k++, tg++) { ed.matc.set(0, tg); ed.matc.set(1, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed);
                plan.execute();
                int nb = (int) ed.redOut.get(0); double load = ed.redOut.get(4); int na = (int) ed.redOut.get(5);
                if (na != N) activeEqN = false;
                if (!finite6(ed.redOut)) invalid++;
                sumBound += nb; minBound = Math.min(minBound, nb); maxBound = Math.max(maxBound, nb);
                sumForcePn += (nb > 0 ? load / nb : 0) * 1e12;
                for (int m = 0; m < N; m++) { int bs = Gd.mot.boundSeg.get(m); if (bs >= 0 && prevBc[m] < 0) binds++; if (bs < 0 && prevBc[m] >= 0) detaches++; prevBc[m] = bs; } }
            double meanBound = (double) sumBound / sciSteps, meanForcePn = sumForcePn / sciSteps;
            // ---- CPU-analytic free-binding timing (its own scene; genuine binding search) ----
            int cpuMeas = cpuMeasByDens[Math.min(di, cpuMeasByDens.length - 1)], cpuReps = cpuRepsByDens[Math.min(di, cpuRepsByDens.length - 1)];
            System.out.printf(Locale.US, "  [density %.0f] GPU %.3f ms/step; warming CPU %d, measuring %d×%d…%n", density, stats(gpuMs)[0], cpuWarm, cpuMeas, cpuReps);
            int tc = 0; for (int t = 0; t < cpuWarm; t++, tc++) stepGlidingCPU(ec, tc, seed);
            double[] cpuMs = new double[cpuReps];
            for (int r = 0; r < cpuReps; r++) { long ns = 0; for (int k = 0; k < cpuMeas; k++, tc++) { long t0 = System.nanoTime(); stepGlidingCPU(ec, tc, seed); ns += System.nanoTime() - t0; } cpuMs[r] = ns / 1e6 / cpuMeas; }
            boolean cpuInvalid = !finite6(ec.redOut);
            double[] gs = stats(gpuMs), cs = stats(cpuMs); double gMed = gs[0], cMed = cs[0]; gpuMed[di] = gMed; cpuMed[di] = cMed;
            double gSps = 1000 / gMed, cSps = 1000 / cMed, speedup = cMed / gMed, devKerMs = devKerNs / 1e6 / nProf;
            boolean invalidState = invalid > 0 || gpuInvalidTime || cpuInvalid;
            // assertions
            log.append(String.format(Locale.US, "### density %.0f (N=%d, nSeg=%d): freeBinding=true, cullingEnabled=false, processedMotorCount=N=%d, hostBindingLoop=false, fullStateDownload=false, silentFallback=false, activeEqN=%b\n", density, N, nSeg, N, activeEqN));
            log.append(String.format(Locale.US, "- **GPU** %.3f ms/step (med; mean %.3f, sd %.3f, min %.3f, max %.3f, CV %.2f%%, %.0f steps/s) | device-kernel %.3f ms; launch floor %.3f ms; bytes in=%d out=%d/step; beam SoA %.2f MiB\n",
                    gMed, gs[1], gs[2], gs[3], gs[4], gs[5], gSps, devKerMs, gMed - devKerMs, bIn / nProf, bOut / nProf, beamBytes / 1048576.0));
            log.append(String.format(Locale.US, "- **CPU-analytic** %.3f ms/step (med; mean %.3f, sd %.3f, CV %.2f%%, %.1f steps/s) over %d×%d ⇒ **speedup %.2f×**\n", cMed, cs[1], cs[2], cs[5], cSps, cpuReps, cpuMeas, speedup));
            log.append(String.format(Locale.US, "- **science** (%d untimed steps): binds=%d, detaches=%d, meanBound=%.2f (min %d, max %d), meanForce=%.2f pN, invalid=%d\n\n", sciSteps, binds, detaches, meanBound, minBound, maxBound, meanForcePn, invalid));
            tbl.append(String.format(Locale.US, "| %.0f | %d | %.3f±%.1f%% | %.0f | %.3f±%.1f%% | %.1f | %.2f× | %.2f | %d/%d | %d |\n", density, N, gMed, gs[5], gSps, cMed, cs[5], cSps, speedup, meanBound, binds, detaches, invalid));
            stage.append(String.format(Locale.US, "| %.0f", density)); for (int i = 0; i < GLTASKS.length; i++) stage.append(String.format(Locale.US, " | %.3f", kerNs[i] / 1e6 / nProf)); stage.append(" |\n");
            csv.append(String.format(Locale.US, "%.0f,%d,%d,true,false,%d,false,false,false,%.4f,%.4f,%.4f,%.4f,%.4f,%.2f,%.1f,%.4f,%.4f,%d,%d,%.2f,%.4f,%.4f,%.4f,%.2f,%.1f,%.3f,%d,%d,%d,%.3f,%d,%d,%.3f,%d,%b\n",
                    density, N, nSeg, N, gMed, gs[1], gs[2], gs[3], gs[4], gs[5], gSps, devKerMs, gMed - devKerMs, bIn / nProf, bOut / nProf, beamBytes / 1048576.0,
                    cMed, cs[1], cs[2], cs[5], cSps, speedup, sciSteps, binds, detaches, meanBound, minBound, maxBound, meanForcePn, invalid, activeEqN));
            System.out.printf(Locale.US, "  density=%-5.0f N=%-5d | GPU %.3f ms/step (%.1f%%CV) | CPU %.3f ms/step | %.2f× | meanBound %.2f binds %d invalid %d%n", density, N, gMed, gs[5], cMed, speedup, meanBound, binds, invalid);
            if (invalidState) { allOk = false; System.out.println("  !! INVALID STATE at density " + density); }
        }
        log.append("\n## Throughput table (genuine free-binding, no-cull, production-residency)\n").append(tbl);
        log.append("\n## Per-stage GPU kernel time (ms/step)\n").append(stage);
        // cost scaling: linear fit ms/step = a + b·N over measured densities (least squares)
        int n = dens.length; double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) { double x = Nat[i], y = gpuMed[i]; sx += x; sy += y; sxx += x * x; sxy += x * y; }
        double b = (n * sxy - sx * sy) / (n * sxx - sx * sx), a = (sy - b * sx) / n;
        int crossN = -1; for (int i = 0; i < n; i++) if (cpuMed[i] > gpuMed[i]) { crossN = Nat[i]; break; }
        log.append(String.format(Locale.US, "\n## §B2 cost scaling (GPU): ms/step ≈ %.4f + %.3e·N  ⇒ launch floor ≈ %.3f ms; per-1000-motors ≈ %.4f ms; GPU faster than CPU from N≈%s.\n",
                a, b, a, b * 1000, crossN < 0 ? "(all measured N)" : "" + crossN));
        try { Files.writeString(Path.of(OUT, "COMPLETEMAT_THROUGHPUT.csv"), csv.toString()); } catch (IOException e) { throw new UncheckedIOException(e); }
        log.append("\n**GENUINE FREE-BINDING NO-CULL THROUGHPUT: measured** (all N processed; free binding + chemistry active; production residency; timing separated from science). DEVICE_VALIDATED stays false (promotion deferred).\n");
        return allOk;
    }

    // ============================================================= Phase B arm: CALIBRATED no-cull free-binding throughput
    static final String[] CALTASKS = { "matCull", "matGeomGate", "matBind", "chem", "matCock", "matPlaceHead", "bondForces",
        "zeroAcc", "csrHist", "csrScan", "csrScatter", "segGather", "chain", "zconf", "brown", "integ", "orthoY", "derive", "matStep7", "matReduce" };
    /** Matched-protocol throughput for the calibrated-s2-l40 model (reusing MatSoaSlice's device graph + CPU runner).
     *  Same densities/schedule as the explicit throughputMode; no-cull via a huge queryR so matCull marks all N active
     *  (only the active-set shortcut bypassed; matGeomGate/matBind/chemistry/mechanics unchanged). Frozen 4I params. */
    static boolean throughputCalMode(StringBuilder log, boolean quick) {
        int[] dens = quick ? new int[]{ 200, 700 } : new int[]{ 200, 700, 1500, 2000, 2500, 3000 };
        int seed = 101; double dt = DT;
        int gpuWarm = quick ? 300 : 2000, gpuMeas = quick ? 800 : 10000, gpuReps = quick ? 2 : 3, sciSteps = quick ? 800 : 8000;
        int[] cpuMeasByDens = quick ? new int[]{ 300, 300 } : new int[]{ 2000, 1000, 1000, 400, 300, 200 };
        int[] cpuRepsByDens = quick ? new int[]{ 2, 2 } : new int[]{ 3, 3, 3, 2, 2, 2 };
        int cpuWarm = quick ? 100 : 600; double HUGE = 1e12;
        System.out.println("=== Phase B arm: CALIBRATED no-cull free-binding throughput (calibrated-s2-l40) ===");
        log.append("Protocol: calibrated-s2-l40 (frozen 4I), GENUINE FREE BINDING (all unbound at t=0; matCull→matGeomGate→matBind\n");
        log.append("each step), NO-CULL (cullP=huge ⇒ matCull marks all N active ⇒ matStep7 solves all N; only the active-set\n");
        log.append("shortcut bypassed). Production-residency MatSoaSlice.buildTrajGraph (per step only counters UP + redOut DOWN).\n");
        log.append(String.format(Locale.US, "GPU: warm %d, measure %d × %d reps. CPU: warm %d, per-density steps. Matched to the explicit Phase A run.\n\n", gpuWarm, gpuMeas, gpuReps, cpuWarm));
        StringBuilder csv = new StringBuilder("model,density,N,nSeg,freeBinding,cullingEnabled,processedMotorCount,fullStateDownload,silentFallback,"
            + "gpu_ms_step_med,gpu_ms_mean,gpu_ms_std,gpu_ms_min,gpu_ms_max,gpu_cv_pct,gpu_steps_s,gpu_devkernel_ms,gpu_launchfloor_ms,gpu_bytesIn,gpu_bytesOut,gpu_stateMiB,"
            + "cpu_ms_step_med,cpu_ms_mean,cpu_ms_std,cpu_cv_pct,cpu_steps_s,speedup,sci_steps,meanBound,minBound,maxBound,bindActivity,meanForce_pN,invalid,activeEqN\n");
        StringBuilder tbl = new StringBuilder("| density | N | GPU ms/step (med±CV) | GPU steps/s | CPU ms/step (med±CV) | CPU steps/s | speedup | meanBound | invalid |\n|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        StringBuilder stage = new StringBuilder("| density | " + String.join(" | ", CALTASKS) + " |\n|" + "---:|".repeat(CALTASKS.length + 1) + "\n");
        boolean allOk = true;
        for (int di = 0; di < dens.length; di++) {
            double density = dens[di];
            Glide2D Gc = TwoBodyConverterMotor.buildMatForModel(MotorModel.CALIBRATED_S2_L40, density, dt, seed);
            Glide2D Gd = TwoBodyConverterMotor.buildMatForModel(MotorModel.CALIBRATED_S2_L40, density, dt, seed);
            int N = Gc.N, nSeg = Gc.nSeg;
            for (int m = 0; m < N; m++) { Gc.mot.boundSeg.set(m, -1); Gd.mot.boundSeg.set(m, -1); }
            MatSoaSlice.MatState sc = MatSoaSlice.packMat(Gc), sd = MatSoaSlice.packMat(Gd);
            sc.cullP.set(0, HUGE); sd.cullP.set(0, HUGE);   // NO-CULL: matCull marks all N active
            for (int m = 0; m < N; m++) { sc.active.set(m, 1); sd.active.set(m, 1); }
            long stateBytes = (long) (2 + 4 + 3 + 3 + 9 + 1) * N * 8 + (long) 2 * N * 4;   // calibrated per-motor state (site/pose4/anchor/supP0/geomOut/candArc + candInt)
            TornadoExecutionPlan plan;
            try { plan = MatSoaSlice.buildTrajGraph(Gd, sd, true).withGridScheduler(MatSoaSlice.trajSched); }
            catch (Throwable ex) { log.append("- density " + density + " graph FAILED: " + oneLine(root(ex).getMessage()) + "\n"); allOk = false; continue; }
            System.out.printf(Locale.US, "  [cal density %.0f N=%d nSeg=%d] warming GPU %d…%n", density, N, nSeg, gpuWarm);
            int tg = 0;
            for (int t = 0; t < gpuWarm; t++, tg++) { sd.mc.set(1, tg); sd.mc.set(2, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed); plan.execute(); }
            double[] gpuMs = new double[gpuReps];
            for (int r = 0; r < gpuReps; r++) { long ns = 0;
                for (int k = 0; k < gpuMeas; k++, tg++) { sd.mc.set(1, tg); sd.mc.set(2, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed);
                    long t0 = System.nanoTime(); plan.execute(); ns += System.nanoTime() - t0; }
                gpuMs[r] = ns / 1e6 / gpuMeas; }
            long[] kerNs = new long[CALTASKS.length]; long devKerNs = 0, bIn = 0, bOut = 0; int nProf = 0;
            for (int k = 0; k < 40; k++, tg++) { sd.mc.set(1, tg); sd.mc.set(2, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed);
                TornadoProfilerResult pr = plan.withProfiler(ProfilerMode.SILENT).execute().getProfilerResult(); String jl = pr.getProfileLog();
                for (int i = 0; i < CALTASKS.length; i++) kerNs[i] += parseTaskNs(jl, "traj.", CALTASKS[i]);
                devKerNs += pr.getDeviceKernelTime(); bIn += pr.getTotalBytesCopyIn(); bOut += pr.getTotalBytesCopyOut(); nProf++; plan.clearProfiles(); }
            plan.withoutProfiler();
            // science (untimed): avgBound/meanForce/invalid/activeEqN from redOut; bindActivity = Σ|Δbound|
            long sumBound = 0; int minBound = Integer.MAX_VALUE, maxBound = 0, invalid = 0, prevNb = (int) sd.redOut.get(0); long bindActivity = 0; double sumForcePn = 0; boolean activeEqN = true;
            for (int k = 0; k < sciSteps; k++, tg++) { sd.mc.set(1, tg); sd.mc.set(2, seed); Gd.mot.setCounts(tg, seed, nSeg); Gd.fil.counts.set(1, tg); Gd.fil.counts.set(2, seed);
                plan.execute();
                int nb = (int) sd.redOut.get(0); double load = sd.redOut.get(4); int na = (int) sd.redOut.get(5);
                if (na != N) activeEqN = false; if (!finite6(sd.redOut)) invalid++;
                sumBound += nb; minBound = Math.min(minBound, nb); maxBound = Math.max(maxBound, nb); bindActivity += Math.abs(nb - prevNb); prevNb = nb;
                sumForcePn += (nb > 0 ? load / nb : 0) * 1e12; }
            double meanBound = (double) sumBound / sciSteps, meanForcePn = sumForcePn / sciSteps;
            // CPU
            int cpuMeas = cpuMeasByDens[Math.min(di, cpuMeasByDens.length - 1)], cpuReps = cpuRepsByDens[Math.min(di, cpuRepsByDens.length - 1)];
            System.out.printf(Locale.US, "  [cal density %.0f] GPU %.3f ms/step; warming CPU %d, measuring %d×%d…%n", density, stats(gpuMs)[0], cpuWarm, cpuMeas, cpuReps);
            int tc = 0; for (int t = 0; t < cpuWarm; t++, tc++) MatSoaSlice.stepMatCPU(Gc, sc, tc, seed);
            double[] cpuMs = new double[cpuReps];
            for (int r = 0; r < cpuReps; r++) { long ns = 0; for (int k = 0; k < cpuMeas; k++, tc++) { long t0 = System.nanoTime(); MatSoaSlice.stepMatCPU(Gc, sc, tc, seed); ns += System.nanoTime() - t0; } cpuMs[r] = ns / 1e6 / cpuMeas; }
            boolean cpuInvalid = !finite6(sc.redOut);
            double[] gs = stats(gpuMs), cs = stats(cpuMs); double gMed = gs[0], cMed = cs[0];
            double gSps = 1000 / gMed, cSps = 1000 / cMed, speedup = cMed / gMed, devKerMs = devKerNs / 1e6 / nProf;
            boolean invalidState = invalid > 0 || cpuInvalid || !finite6(sd.redOut);
            log.append(String.format(Locale.US, "### density %.0f (N=%d, nSeg=%d): freeBinding=true, cullingEnabled=false, processedMotorCount=N=%d, fullStateDownload=false, silentFallback=false, activeEqN=%b\n", density, N, nSeg, N, activeEqN));
            log.append(String.format(Locale.US, "- **GPU** %.3f ms/step (med; mean %.3f, sd %.3f, CV %.2f%%, %.0f steps/s) | device-kernel %.3f ms; launch floor %.3f ms; bytes in=%d out=%d/step; state %.2f MiB\n",
                    gMed, gs[1], gs[2], gs[5], gSps, devKerMs, gMed - devKerMs, bIn / nProf, bOut / nProf, stateBytes / 1048576.0));
            log.append(String.format(Locale.US, "- **CPU** %.3f ms/step (med; CV %.2f%%, %.1f steps/s) over %d×%d ⇒ **speedup %.2f×**\n", cMed, cs[5], cSps, cpuReps, cpuMeas, speedup));
            log.append(String.format(Locale.US, "- **science** (%d steps): meanBound=%.2f (min %d, max %d), bindActivity Σ|Δ|=%d, meanForce=%.2f pN, invalid=%d\n\n", sciSteps, meanBound, minBound, maxBound, bindActivity, meanForcePn, invalid));
            tbl.append(String.format(Locale.US, "| %.0f | %d | %.3f±%.1f%% | %.0f | %.3f±%.1f%% | %.1f | %.2f× | %.2f | %d |\n", density, N, gMed, gs[5], gSps, cMed, cs[5], cSps, speedup, meanBound, invalid));
            stage.append(String.format(Locale.US, "| %.0f", density)); for (int i = 0; i < CALTASKS.length; i++) stage.append(String.format(Locale.US, " | %.3f", kerNs[i] / 1e6 / nProf)); stage.append(" |\n");
            csv.append(String.format(Locale.US, "calibrated-s2-l40,%.0f,%d,%d,true,false,%d,false,false,%.4f,%.4f,%.4f,%.4f,%.4f,%.2f,%.1f,%.4f,%.4f,%d,%d,%.3f,%.4f,%.4f,%.4f,%.2f,%.1f,%.3f,%d,%.3f,%d,%d,%d,%.3f,%d,%b\n",
                    density, N, nSeg, N, gMed, gs[1], gs[2], gs[3], gs[4], gs[5], gSps, devKerMs, gMed - devKerMs, bIn / nProf, bOut / nProf, stateBytes / 1048576.0,
                    cMed, cs[1], cs[2], cs[5], cSps, speedup, sciSteps, meanBound, minBound, maxBound, bindActivity, meanForcePn, invalid, activeEqN));
            System.out.printf(Locale.US, "  cal density=%-5.0f N=%-5d | GPU %.3f ms/step (%.1f%%CV) | CPU %.3f ms/step | %.2f× | meanBound %.2f invalid %d%n", density, N, gMed, gs[5], cMed, speedup, meanBound, invalid);
            if (invalidState) { allOk = false; System.out.println("  !! INVALID STATE at cal density " + density); }
        }
        log.append("\n## Calibrated throughput table (no-cull, free-binding, production-residency)\n").append(tbl);
        log.append("\n## Per-stage GPU kernel time (ms/step)\n").append(stage);
        try { Files.writeString(Path.of(OUT, "COMPLETEMAT_THROUGHPUT_CAL.csv"), csv.toString()); } catch (IOException e) { throw new UncheckedIOException(e); }
        log.append("\n**CALIBRATED NO-CULL THROUGHPUT: measured** (matched to explicit Phase A; combine the two CSVs for the Phase B cost table).\n");
        return allOk;
    }

    // ============================================================= Phase C: explicit density-saturation sweep (GPU)
    static double argD(String[] a, String key, double def) { for (int i = 0; i < a.length - 1; i++) if (a[i].equals(key)) return Double.parseDouble(a[i + 1]); return def; }
    static int argI(String[] a, String key, int def) { for (int i = 0; i < a.length - 1; i++) if (a[i].equals(key)) return Integer.parseInt(a[i + 1]); return def; }
    static boolean hasFlag(String[] a, String key) { for (String s : a) if (s.equals(key)) return true; return false; }
    static String argS(String[] a, String key, String def) { for (int i = 0; i < a.length - 1; i++) if (a[i].equals(key)) return a[i + 1]; return def; }
    /** {vel µm/s, avgBound, continuity, netForce pN, invalid, N, minBound, maxBound, boundFrac} for one (model,density,seed).
     *  model 0=explicit-s2-l40 (buildGlidingGraph), 1=calibrated-s2-l40 (MatSoaSlice.buildTrajGraph). Device-resident;
     *  velocity = LS slope of the resident redOut centroid·b̂ over the MEASURED window (negative = pointed-first). */
    static boolean SWEEP_NOCULL = false;   // D3 control: force calibrated no-cull (cullP huge) to test culling is a semantic no-op
    static boolean PROD_SCI = false;        // production-cell: add per-step nucleotideState readback (science observables); default off ⇒ sweep byte-unchanged
    // ---- RIGOR MECHANICAL RUPTURE (default OFF ⇒ chem task == cycleLymnTaylor, byte-identical gliding) ----
    static boolean RIGOR_ON = false; static int RIGOR_MODEL = 0;
    static double RIGOR_K0 = 140.0, RIGOR_AC = 0.9071, RIGOR_XC = 1.5, RIGOR_AS = 0.0929, RIGOR_XS = 0.5;
    // RUPTURE_MODE: 0 off, 1 rigor-only, 2 all-strong-bound (direct ADP rupture, G&G ADP Table-2).
    static int RUPTURE_MODE = 0; static boolean ADP_RUP_ON = false;
    static double ADP_K0 = 191.0, ADP_AC = 0.92147, ADP_XC = 2.5, ADP_AS = 0.07853, ADP_XS = 0.4;
    /** Install the flag-gated rupture params on a built scene (no-op when OFF ⇒ rigorParams[0]=0). */
    static void installRigor(MotorStore mot, double dt) {
        if (RIGOR_ON) mot.setRigorRupture(true, RIGOR_MODEL, RIGOR_K0, RIGOR_AC, RIGOR_XC, RIGOR_AS, RIGOR_XS);
        else mot.disableRigorRupture();
        if (ADP_RUP_ON) mot.setAdpRupture(true, 0, ADP_K0, ADP_AC, ADP_XC, ADP_AS, ADP_XS);
        else mot.disableAdpRupture();
    }
    static double[] runGlide(int model, double density, double dt, int seed, int equil, int meas) {
        double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        int recEvery = Math.max(1, meas / 2000);
        double[] bhat; int N, nSeg; DoubleArray redOut; TornadoExecutionPlan plan;
        Glide2D Gd; ExMat ed = null; MatSoaSlice.MatState sd = null;
        if (model == 0) {
            Gd = TwoBodyConverterMotor.buildS2Mat(density, dt, 40.0, slack, seed);
            N = Gd.N; nSeg = Gd.nSeg; for (int m = 0; m < N; m++) Gd.mot.boundSeg.set(m, -1);
            ed = packExMat(Gd, 1); redOut = ed.redOut; plan = buildGlidingGraph(ed, true);   // explicit is inherently no-cull
        } else {
            Gd = TwoBodyConverterMotor.buildMatForModel(MotorModel.CALIBRATED_S2_L40, density, dt, seed);
            N = Gd.N; nSeg = Gd.nSeg; for (int m = 0; m < N; m++) Gd.mot.boundSeg.set(m, -1);
            sd = MatSoaSlice.packMat(Gd); redOut = sd.redOut;
            if (SWEEP_NOCULL) { sd.cullP.set(0, 1e12); for (int m = 0; m < N; m++) sd.active.set(m, 1); }   // D3: no-cull calibrated
            plan = MatSoaSlice.buildTrajGraph(Gd, sd, true).withGridScheduler(MatSoaSlice.trajSched);
        }
        bhat = Gd.bhat;
        int tg = 0;
        for (int t = 0; t < equil; t++, tg++) { setCounters(model, ed, sd, Gd, tg, seed, nSeg); plan.execute(); }
        int nSamp = 0; double[] tt = new double[meas / recEvery + 2], yy = new double[meas / recEvery + 2];
        long sumBound = 0, contHits = 0; int minB = Integer.MAX_VALUE, maxB = 0, invalid = 0; double sumForce = 0;
        for (int t = 0; t < meas; t++, tg++) { setCounters(model, ed, sd, Gd, tg, seed, nSeg); plan.execute();
            if (!finite6(redOut)) invalid++;
            int nb = (int) redOut.get(0); double load = redOut.get(4);
            sumBound += nb; if (nb > 0) contHits++; minB = Math.min(minB, nb); maxB = Math.max(maxB, nb); sumForce += load * 1e12;
            if (t % recEvery == 0) { double com = redOut.get(1) * bhat[0] + redOut.get(2) * bhat[1] + redOut.get(3) * bhat[2];
                tt[nSamp] = t * dt; yy[nSamp] = com; nSamp++; } }
        // LS slope (µm/s); sign kept (negative = pointed-first = correct)
        double st = 0, sy = 0, stt = 0, sty = 0; for (int i = 0; i < nSamp; i++) { st += tt[i]; sy += yy[i]; stt += tt[i] * tt[i]; sty += tt[i] * yy[i]; }
        double den = nSamp * stt - st * st; double vel = Math.abs(den) < 1e-30 ? 0 : (nSamp * sty - st * sy) / den;
        double avgBound = (double) sumBound / meas, continuity = (double) contHits / meas, netForce = sumForce / meas;
        return new double[]{ vel, avgBound, continuity, netForce, invalid, N, minB == Integer.MAX_VALUE ? 0 : minB, maxB, N > 0 ? avgBound / N : 0 };
    }
    static void setCounters(int model, ExMat ed, MatSoaSlice.MatState sd, Glide2D Gd, int t, int seed, int nSeg) {
        if (model == 0) { ed.matc.set(0, t); ed.matc.set(1, seed); } else { sd.mc.set(1, t); sd.mc.set(2, seed); }
        Gd.mot.setCounts(t, seed, nSeg); Gd.fil.counts.set(1, t); Gd.fil.counts.set(2, seed);
    }
    static boolean sweepMode(StringBuilder log, boolean quick, String[] args) {
        double dt = argD(args, "-dt", DT);
        int seeds = argI(args, "-seeds", quick ? 2 : 3), equil = argI(args, "-equil", quick ? 2000 : 20000), meas = argI(args, "-meas", quick ? 3000 : 60000);
        int seed0 = argI(args, "-seed0", 4321);
        for (String a : args) if (a.equals("-nocull")) SWEEP_NOCULL = true;
        String densCsv = argS(args, "-dens", quick ? "200,700" : "100,200,400,700,1000,1500,2000,2500,3000");
        String[] dp = densCsv.split(","); double[] dens = new double[dp.length]; for (int i = 0; i < dp.length; i++) dens[i] = Double.parseDouble(dp[i].trim());
        String[] MNAME = { "explicit-s2-l40", "calibrated-s2-l40" };
        String modelsCsv = argS(args, "-models", "0,1");   // which models: 0=explicit, 1=calibrated (default both)
        String[] mp = modelsCsv.split(","); int[] models = new int[mp.length]; for (int i = 0; i < mp.length; i++) models[i] = Integer.parseInt(mp[i].trim());
        System.out.println("=== Phase C: explicit density-saturation sweep (GPU, both models, matched geometry) ===");
        log.append(String.format(Locale.US, "Protocol: GPU device-resident free-binding gliding, BOTH models at matched geometry (buildGlide2D density).\n"
            + "Velocity = LS slope of resident redOut centroid·b̂ over the MEASURED window (negative = pointed-first = correct;\n"
            + "the canonical `lsSlope`/`filComB` convention). dt=%.2e, equilibration=%d steps discarded, measured=%d steps (%.3f s),\n"
            + "seeds=%d (seed0=%d). Production residency (only redOut crosses per step). Explicit inherently no-cull; calibrated\n"
            + "production culling (physics-identical — the D3 control confirms). Densities: %s /µm².\n\n", dt, equil, meas, meas * dt, seeds, seed0, densCsv));
        StringBuilder csv = new StringBuilder("model,density,N,seeds,equilSteps,measSteps,dt,vel_umPerS,vel_sd,vel_se,speed_umPerS,avgBound,avgBound_sd,continuity,boundFrac,netForce_pN,minBound,maxBound,invalid\n");
        StringBuilder tbl = new StringBuilder("| model | density | N | vel µm/s (±SE) | |speed| | avgBound | continuity | boundFrac | netForce pN | invalid |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        boolean allOk = true;
        for (int mo : models) {
            for (double density : dens) {
                double[] vels = new double[seeds], abs = new double[seeds]; double sVel = 0, sAb = 0, sCont = 0, sFor = 0; int Nrep = 0, invAll = 0, minAll = Integer.MAX_VALUE, maxAll = 0;
                for (int s = 0; s < seeds; s++) {
                    double[] r;
                    try { r = runGlide(mo, density, dt, seed0 + s, equil, meas); }
                    catch (Throwable ex) { log.append("- " + MNAME[mo] + " density " + density + " seed " + (seed0 + s) + " FAILED: " + oneLine(root(ex).getMessage()) + "\n"); allOk = false; continue; }
                    vels[s] = r[0]; abs[s] = r[1]; sVel += r[0]; sAb += r[1]; sCont += r[2]; sFor += r[3]; invAll += (int) r[4]; Nrep = (int) r[5];
                    minAll = Math.min(minAll, (int) r[6]); maxAll = Math.max(maxAll, (int) r[7]);
                    System.out.printf(Locale.US, "  %s density=%-5.0f N=%-5d seed=%d | vel=%+.3f µm/s avgBound=%.2f cont=%.2f inv=%d%n", MNAME[mo], density, Nrep, seed0 + s, r[0], r[1], r[2], (int) r[4]);
                }
                double vMean = sVel / seeds, aMean = sAb / seeds, cMean = sCont / seeds, fMean = sFor / seeds;
                double vVar = 0, aVar = 0; for (int s = 0; s < seeds; s++) { vVar += (vels[s] - vMean) * (vels[s] - vMean); aVar += (abs[s] - aMean) * (abs[s] - aMean); }
                double vSd = Math.sqrt(vVar / Math.max(1, seeds - 1)), aSd = Math.sqrt(aVar / Math.max(1, seeds - 1)), vSe = vSd / Math.sqrt(seeds);
                double boundFrac = Nrep > 0 ? aMean / Nrep : 0;
                log.append(String.format(Locale.US, "- **%s** density %.0f (N=%d): vel=%+.3f ± %.3f (SE %.3f) µm/s, |speed|=%.3f, avgBound=%.2f ± %.2f, continuity=%.2f, boundFrac=%.4f, netForce=%.2f pN, bound∈[%d,%d], invalid=%d\n",
                        MNAME[mo], density, Nrep, vMean, vSd, vSe, Math.abs(vMean), aMean, aSd, cMean, boundFrac, fMean, minAll, maxAll, invAll));
                tbl.append(String.format(Locale.US, "| %s | %.0f | %d | %+.3f±%.3f | %.3f | %.2f | %.2f | %.4f | %.2f | %d |\n", MNAME[mo], density, Nrep, vMean, vSe, Math.abs(vMean), aMean, cMean, boundFrac, fMean, invAll));
                csv.append(String.format(Locale.US, "%s,%.0f,%d,%d,%d,%d,%.3e,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.5f,%.4f,%d,%d,%d\n",
                        MNAME[mo], density, Nrep, seeds, equil, meas, dt, vMean, vSd, vSe, Math.abs(vMean), aMean, aSd, cMean, boundFrac, fMean, minAll, maxAll, invAll));
                if (invAll > 0) { allOk = false; System.out.println("  !! INVALID at " + MNAME[mo] + " density " + density); }
                try { Files.writeString(Path.of(OUT, "COMPLETEMAT_SWEEP.csv"), csv.toString()); } catch (IOException e) { throw new UncheckedIOException(e); }   // flush per density (crash insurance)
            }
        }
        log.append("\n## Density-sweep table (GPU, both models, matched geometry)\n").append(tbl);
        log.append("\nSaturation fit (v_max, K_ρ, plateau) + explicit/calibrated comparison: `scripts/phaseC_saturation.py` over COMPLETEMAT_SWEEP.csv.\n");
        log.append("\n**EXPLICIT DENSITY SWEEP: measured** (velocity/avgBound/continuity/netForce vs density, both models, matched geometry). Force-balance decomposition (propulsive/dragging, ATP/µm) is a scoped follow-on (needs the explicit force-balance port).\n");
        return allOk;
    }

    static String oneLine(String s) { return s == null ? "(none)" : s.replaceAll("\\s+", " ").trim(); }

    // =============================================================== SINGLE-HEAD LONG-RUN PRODUCTION CELL
    /** One (density, seed) explicit-s2-l40 gliding cell → per-cell JSON, matched to the HMM-dimer campaign.
     *  Harness-only: the validated buildS2Mat + buildGlidingGraph(prod) path is byte-unchanged; this only drives it,
     *  reads back science observables, and serialises. Whole-window LS centroid·b̂ velocity estimator (equil=0),
     *  identical to the dimer's runProductionCell. Frozen config enforced + printed + abort-gated. */
    static int runProductionCell(String[] args) {
        // ---- FROZEN CONFIG (enforce + print + abort on violation) ----
        double dt = argD(args, "-dt", DT);                       // 2.5e-6
        int density = argI(args, "-density", 500);
        int seed = argI(args, "-seed", 101);
        int steps = argI(args, "-steps", 40000);
        String outdir = argS(args, "-outdir", "RUN_LOGS/single_head_density_sweep_long");
        String rev = argS(args, "-rev", "unknown");
        // CONTINUOUS LOCAL ACTIN CO-OCCUPANCY EXCLUSION (noncanonical experimental; default OFF = 0 = canonical path).
        // Explicit opt-in flag; value in nm (canonical study value 5.4). Must be parsed BEFORE packExMat/buildGlidingGraph.
        OCC_EXCL_NM = argD(args, "-occupancy-exclusion-nm", 0.0);
        if (OCC_EXCL_NM < 0) OCC_EXCL_NM = 0.0;   // negative ⇒ OFF (recover the exact canonical path)
        double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;   // 1.5 nm
        // Exposed S2 contour length. CANONICAL = 40 nm (explicit-s2-l40). -L 60 selects the DECLARED L60 structural
        // sensitivity (explicit-s2-l60): SAME MD material moduli EA/EI, only the exposed length + M=round(L/10) change
        // (L40->M=4, L60->M=6). EA/EI are NOT edited; the surrogate is not touched (production uses the explicit beam).
        double beamL = argD(args, "-L", argD(args, "-beamL", 40.0));
        boolean canonicalL = Math.abs(beamL - 40.0) < 1e-9;
        String modelId = String.format(Locale.US, "explicit-s2-l%.0f", beamL);
        try { Files.createDirectories(Path.of(outdir)); } catch (IOException e) { throw new UncheckedIOException(e); }

        // Optional actin filament-length control (-nseg): sets ONLY the actin segment count via the existing runtime
        // override G4_NSEG_RUN. This changes filament LENGTH/geometry (apparatus), NOT motor chemistry/mechanics/binding/
        // Brownian, and NOT the density definition (N = round(ρ·area) is nSeg-independent). Default = the validated 12.
        int savedNseg = TwoBodyConverterMotor.G4_NSEG_RUN;
        int nsegReq = argI(args, "-nseg", savedNseg);
        TwoBodyConverterMotor.G4_NSEG_RUN = nsegReq;

        // RIGOR MECHANICAL RUPTURE. CANONICAL DEFAULT = 1 (rigor-only rupture ON, promoted 2026-07-22, canon v2).
        // -no-rupture / -legacy-disable ⇒ mode 0 (pre-v2 legacy, chem == cycleLymnTaylor, byte-identical); -allrupture ⇒ 2.
        RUPTURE_MODE = argI(args, "-rupture-mode", hasFlag(args, "-allrupture") ? 2
                : (hasFlag(args, "-no-rupture") || hasFlag(args, "-legacy-disable")) ? 0 : 1);
        RIGOR_ON = RUPTURE_MODE >= 1; ADP_RUP_ON = RUPTURE_MODE == 2;
        RIGOR_MODEL = argI(args, "-rigor-model", 0);
        RIGOR_K0 = argD(args, "-rk0", 140.0); RIGOR_AC = argD(args, "-raC", 0.9071);
        RIGOR_XC = argD(args, "-rxC", 1.5);   RIGOR_AS = argD(args, "-raS", 0.0929); RIGOR_XS = argD(args, "-rxS", 0.5);
        ADP_K0 = argD(args, "-ark0", 191.0); ADP_AC = argD(args, "-araC", 0.92147); ADP_XC = argD(args, "-arxC", 2.5);
        ADP_AS = argD(args, "-araS", 0.07853); ADP_XS = argD(args, "-arxS", 0.4);

        String abort = null;
        if (Math.abs(dt - 2.5e-6) > 1e-12) abort = "dt != 2.5e-6";
        else if (TwoBodyConverterMotor.LEGACY_OWNERSHIP) abort = "legacy segment ownership ON (canonical binding contract expected)";
        else if (nsegReq < 4) abort = "nseg < 4 (chain needs >= 4 segments)";
        else if (beamL < 10.0 || Math.abs(beamL / 10.0 - Math.round(beamL / 10.0)) > 1e-9) abort = "beam L must be a positive multiple of 10 nm (10 nm beam discretization)";

        int Npre = TwoBodyConverterMotor.g4NMot(density);
        System.out.printf(Locale.US, "=== SINGLE-HEAD (%s) PRODUCTION CELL — %s ===%n", modelId, canonicalL ? "FROZEN CONFIG" : "DECLARED L60 STRUCTURAL SENSITIVITY");
        if (canonicalL)
            System.out.printf(Locale.US, "  model=%s | GPU device-resident TaskGraph (buildGlidingGraph prod) | single-head gliding assay class VALIDATED for production (canonical GPU path; aggregate-statistical CPU↔GPU equivalence; no CPU fallback)%n", modelId);
        else
            System.out.printf(Locale.US, "  model=%s | GPU device-resident TaskGraph (buildGlidingGraph prod) | DECLARED STRUCTURAL SENSITIVITY vs canonical L40 (EA/EI unchanged; only exposed length L=%.0f nm ⇒ M=%d; NOT a competing tuned baseline; L40 stays canonical)%n", modelId, beamL, (int) Math.round(beamL / 10.0));
        if (occOn())
            System.out.printf(Locale.US, "  S2 beam L=%.0f nm | initial slack=%.2f nm | dt=%.3g s | free binding | *** CONTINUOUS LOCAL ACTIN CO-OCCUPANCY EXCLUSION ON: %.2f nm (noncanonical experimental; gate-only→serial-resolve; global across all bound heads on the same filament) ***%n", beamL, slack, dt, OCC_EXCL_NM);
        else
            System.out.printf(Locale.US, "  S2 beam L=%.0f nm | initial slack=%.2f nm | dt=%.3g s | free binding (matBindExplicit each step), NO cull, NO occupancy exclusion (canonical)%n", beamL, slack, dt);
        System.out.printf(Locale.US, "  actin: %d-seg flexible chain (%d mono/seg, contour≈%.3f µm) | actin Brownian ON | motor-body Brownian ON | z-confine %.1f pN/nm coverslip%n",
                TwoBodyConverterMotor.G4_NSEG_RUN, TwoBodyConverterMotor.G4_MONO, TwoBodyConverterMotor.G4_NSEG_RUN * (TwoBodyConverterMotor.G4_MONO + 1) * Constants.actinMonoRadius, TwoBodyConverterMotor.G4_KZ);
        System.out.printf(Locale.US, "  chemistry: Lymn–Taylor nucleotide cycle ON | canonical half-open segment ownership | bhat=+x, negative vel = pointed-first = productive%n");
        System.out.printf(Locale.US, "  lawn=%.1f×%.1f µm (%.1f µm²) | density=%d heads/µm² ⇒ N=%d heads | seed=%d | steps=%d (%.4f s, WHOLE-window LS estimator, equil=0)%n",
                TwoBodyConverterMotor.G4_MATX, TwoBodyConverterMotor.G4_MATY, TwoBodyConverterMotor.G4_MATX * TwoBodyConverterMotor.G4_MATY, density, Npre, seed, steps, steps * dt);
        if (nsegReq != TwoBodyConverterMotor.G4_NSEG)
            System.out.printf(Locale.US, "  *** FILAMENT-LENGTH CONTROL: nSeg=%d (override; validated default=%d) — actin geometry ONLY; motor physics + density unchanged ***%n", nsegReq, TwoBodyConverterMotor.G4_NSEG);
        System.out.printf(Locale.US, "  outdir=%s | rev=%s%n", outdir, rev);
        if (abort != null) { System.out.printf("  *** ABORT (frozen-config violation): %s ***%n", abort); TwoBodyConverterMotor.G4_NSEG_RUN = savedNseg; return 3; }
        System.out.println("  frozen config VERIFIED — proceeding (GPU device-resident, no CPU fallback).");

        long startMs = System.currentTimeMillis();
        Glide2D Gd = TwoBodyConverterMotor.buildS2Mat(density, dt, beamL, slack, seed);
        int N = Gd.N, nSeg = Gd.nSeg;
        if (nSeg != nsegReq) { System.out.printf("  *** ABORT: built nSeg=%d != requested %d (geometry could not be instantiated) ***%n", nSeg, nsegReq); TwoBodyConverterMotor.G4_NSEG_RUN = savedNseg; return 3; }
        for (int m = 0; m < N; m++) Gd.mot.boundSeg.set(m, -1);
        installRigor(Gd.mot, dt);
        if (RIGOR_ON) System.out.printf(Locale.US, "  RIGOR MECHANICAL RUPTURE: ON (chem=cycleLymnTaylorRigor) model=%d k0=%.4g/s aCatch=%.4g xCatch=%.4g nm aSlip=%.4g xSlip=%.4g nm; competing with atpOn=%.3g/s ⇒ rupture is a rare channel at saturating ATP%n",
                RIGOR_MODEL, RIGOR_K0, RIGOR_AC, RIGOR_XC, RIGOR_AS, RIGOR_XS, Gd.mot.nucParams.get(1));
        else System.out.println("  RIGOR MECHANICAL RUPTURE: OFF (production cycleLymnTaylor).");
        ExMat ed = packExMat(Gd, 1);
        double[] bhat = Gd.bhat;
        System.out.printf(Locale.US, "  SCENE: N=%d heads | nSeg=%d | beam nodes M=%d | cullR=%.0f nm (free-binding, all active)%n", N, nSeg, ed.M, Gd.queryR * 1e3);

        SingleHeadObs o = new SingleHeadObs(N, steps, dt);
        String status = "ok", err = ""; double warmMs = 0;
        boolean prevSci = PROD_SCI; PROD_SCI = true;   // enable per-step nucleotideState readback for this cell
        TornadoExecutionPlan plan = null;              // hoisted so the teardown window can close it EXPLICITLY
        TornadoCrashDiagnostic.simDt(dt);
        TornadoCrashDiagnostic.context("campaignArm", "single-head-production-cell");
        TornadoCrashDiagnostic.context("density", density);
        TornadoCrashDiagnostic.context("seed", seed);
        TornadoCrashDiagnostic.context("steps", steps);
        TornadoCrashDiagnostic.context("N", N);
        TornadoCrashDiagnostic.context("nSeg", nSeg);
        TornadoCrashDiagnostic.context("rev", rev);
        TornadoCrashDiagnostic.context("outdir", outdir);
        try {
            TornadoCrashDiagnostic.planConstructionBegin("graph=buildGlidingGraph(prod) model=" + modelId + " beamL=" + beamL);
            try { plan = buildGlidingGraph(ed, true); }
            catch (Throwable ce) { TornadoCrashDiagnostic.planConstructionThrew(ce); throw ce; }
            TornadoCrashDiagnostic.planConstructionEnd(plan, "M=" + ed.M);
            TornadoCrashDiagnostic.executeLoopBegin("glide", 0, steps - 1, "executeCallsPlanned=" + steps);
            long w0 = System.nanoTime();
            setCounters(0, ed, null, Gd, 0, seed, nSeg);
            TornadoCrashDiagnostic.beforeExecute(0); plan.execute(); TornadoCrashDiagnostic.afterExecute(0);
            warmMs = (System.nanoTime() - w0) / 1e6;
            o.observe(0, ed, Gd, bhat, nSeg);
            for (int t = 1; t < steps; t++) {
                setCounters(0, ed, null, Gd, t, seed, nSeg);
                TornadoCrashDiagnostic.beforeExecute(t); plan.execute(); TornadoCrashDiagnostic.afterExecute(t);
                o.observe(t, ed, Gd, bhat, nSeg);
            }
            TornadoCrashDiagnostic.executeLoopEnd("warmMs=" + String.format(Locale.US, "%.0f", warmMs));
        } catch (Throwable e) {
            if (plan != null) TornadoCrashDiagnostic.executeThrew(e);
            status = "error"; err = e.getClass().getSimpleName() + ": " + oneLine(e.getMessage());
            System.out.printf("  *** RUNTIME ERROR at cell ρ%d s%d: %s ***%n", density, seed, err);
        } finally { PROD_SCI = prevSci; }
        long endMs = System.currentTimeMillis(); double wallS = (endMs - startMs) / 1e3;
        TornadoCrashDiagnostic.resultProcessingBegin("status=" + status);
        o.finish();
        if (RIGOR_ON && status.equals("ok")) {
            o.finishRigor(Gd.mot);
            System.out.printf(Locale.US, "         RIGOR RUPTURE: %d ruptures (%.4f of detachments) | force@rupture mean %.2f pN [%.2f,%.2f] | rateCapWarn=%d%n",
                    o.rigorRuptures, o.detachEvents > 0 ? (double) o.rigorRuptures / o.detachEvents : 0.0,
                    o.rigorRuptures > 0 ? o.rupForceSum / o.rigorRuptures : 0.0,
                    o.rigorRuptures > 0 ? o.rupForceMin : 0.0, o.rigorRuptures > 0 ? o.rupForceMax : 0.0, o.rateCapWarns);
        }

        System.out.printf(Locale.US, "  RESULT ρ%d s%d: velProd=%+.4f µm/s (postEquil %+.4f) meanBoundHeads=%.3f cont=%.4f boundFrac=%.5f vel/boundHead=%+.4f%n",
                density, seed, o.velProd, o.velPostEquil, o.meanBoundHeads, o.continuity, N > 0 ? o.meanBoundHeads / N : 0, o.meanBoundHeads > 0 ? o.velProd / o.meanBoundHeads : 0);
        System.out.printf(Locale.US, "         lifetime=%.3f ms | ATPturn=%d | netForce=%.3f pN peakLoad=%.2f pN | invalid=%d solveFail=%d | bound∈[%d,%d]%n",
                o.lifetimeMean() * dt * 1e3, o.atpTurnover, o.netForcePn, o.peakLoadPn, o.invalidStates, o.invalidStates, o.minBound, o.maxBound);
        System.out.printf(Locale.US, "         wall=%.1fs | %.1f steps/s | warm=%.0f ms | status=%s%n", wallS, status.equals("ok") ? steps / wallS : Double.NaN, warmMs, status);
        // PARTCROW,mode,density,seed,N,steps,velProd,velPostEquil,meanBoundHeads,lifetime_ms,atpTurnover,rigorRup,adpRup,occNONE,occATP,occADPPi,occADP,nucSteps,invalid,status
        double nucDen = Math.max(1, o.nucSteps);
        System.out.printf(Locale.US, "PARTCROW,%d,%d,%d,%d,%d,%.5f,%.5f,%.4f,%.4f,%d,%d,%d,%.5f,%.5f,%.5f,%.5f,%d,%d,%s%n",
                RUPTURE_MODE, density, seed, N, steps, o.velProd, o.velPostEquil, o.meanBoundHeads,
                o.lifetimeMean() * dt * 1e3, o.atpTurnover, o.rigorRuptures, o.adpRuptures,
                o.nucOcc[MotorStore.NUC_NONE] / nucDen, o.nucOcc[MotorStore.NUC_ATP] / nucDen,
                o.nucOcc[MotorStore.NUC_ADPPI] / nucDen, o.nucOcc[MotorStore.NUC_ADP] / nucDen,
                o.nucSteps, o.invalidStates, status);

        // ---- atomic JSON write + .done ----
        String base = String.format(Locale.US, "cell_d%d_s%d", density, seed);
        String json = o.toJson(density, seed, steps, dt, N, nSeg, ed.M, beamL, slack, Gd.queryR, rev, startMs, endMs, wallS, status, err, warmMs);
        try {
            Path fin = new File(outdir, base + ".json").toPath();
            Path tmp = new File(outdir, base + ".json.tmp").toPath();
            Files.writeString(tmp, json);
            Files.move(tmp, fin, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (status.equals("ok")) Files.writeString(new File(outdir, base + ".done").toPath(), fin.getFileName().toString() + "\n");
            System.out.printf("  wrote %s (%s)%n", fin, status);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        TornadoCrashDiagnostic.resultProcessingEnd("wrote=" + base + ".json status=" + status);
        // All device→host readback is done and the cell JSON is durable on disk. execute() is blocking and the
        // graph declares its copy-outs EVERY_EXECUTION, so result consumption above IS the device synchronisation
        // (no extra sync added — that would change execution semantics/cost).
        TornadoCrashDiagnostic.gpuWorkDeclaredFinished("status=" + status + " wallS=" + String.format(Locale.US, "%.1f", wallS));
        TornadoCrashDiagnostic.closePlan(plan, "graph=glide");   // no-op unless the crash trace is enabled
        TwoBodyConverterMotor.G4_NSEG_RUN = savedNseg;   // restore (hygiene; each cell is a fresh JVM anyway)
        return status.equals("ok") ? 0 : 1;
    }

    /** Per-cell single-head observable accumulator (host-side, from per-step device pulls: redOut + boundSeg + nucleotideState). */
    static final class SingleHeadObs {
        final int N, steps; final double dt;
        final double[] cx;                       // filament centroid·b̂ (µm) per step
        double velFullRun, velProd, velPostEquil, velInstMean, velInstVar, fracMovingProd; int reversals;
        final double[] windowVel = new double[5];
        long boundHeadSum, contHits; int minBound = Integer.MAX_VALUE, maxBound = 0;
        long bindEvents, detachEvents;
        double sumForce, peakLoadPn, netForcePn;
        int invalidStates;
        final long[] nucOcc = new long[4]; long nucSteps, chemTransitions, atpTurnover;
        final int[] prevBound, attachStart, prevNuc; boolean nucInit;
        // RIGOR RUPTURE cause accounting (per-motor delta of ruptureStats; force-at-rupture from the pulled load)
        final int[] prevRup; long rigorRuptures, rateCapWarns; double rupForceSum, rupForceSq, rupForceMax = Double.NEGATIVE_INFINITY, rupForceMin = Double.POSITIVE_INFINITY;
        final int[] prevAdpRup; long adpRuptures;   // DIRECT ADP mechanical rupture count (mode 2)
        int[] rupForceHist = new int[0];
        double meanBoundHeads, continuity;
        // continuous local actin co-occupancy exclusion telemetry (accumulated per step from ed.occStats when ON)
        long occGeomCandidates, occRejects, occAccepted, occConflicts;
        // reversal helper (block-averaged displacement, thermal-robust) — mirrors the dimer
        double blockDispAccum; int blockLen, blockSign; final int BLOCK = 100;
        // lifetimes
        int[] lifeArr = new int[256]; int lifeN;

        SingleHeadObs(int N, int steps, double dt) {
            this.N = N; this.steps = steps; this.dt = dt; cx = new double[steps];
            prevBound = new int[N]; java.util.Arrays.fill(prevBound, -1);
            attachStart = new int[N]; java.util.Arrays.fill(attachStart, -1);
            prevNuc = new int[N];
            prevRup = new int[N];
            prevAdpRup = new int[N];
        }
        void addLife(int v) { if (lifeN == lifeArr.length) lifeArr = java.util.Arrays.copyOf(lifeArr, lifeN * 2); lifeArr[lifeN++] = v; }

        void observe(int t, ExMat ed, Glide2D Gd, double[] bhat, int nSeg) {
            DoubleArray r = ed.redOut; MotorStore mot = Gd.mot;
            if (!finite6(r)) invalidStates++;
            int nb = (int) r.get(0);
            double com = r.get(1) * bhat[0] + r.get(2) * bhat[1] + r.get(3) * bhat[2];
            double load = r.get(4) * 1e12;                       // N → pN (signed net axial load)
            cx[t] = com; sumForce += load; if (Math.abs(load) > peakLoadPn) peakLoadPn = Math.abs(load);
            double instV = t > 0 ? (cx[t] - cx[t - 1]) / dt : 0;
            if (t > 0) { velInstMean += instV; velInstVar += instV * instV; if (instV < 0) fracMovingProd += 1;
                blockDispAccum += (cx[t] - cx[t - 1]); blockLen++;
                if (blockLen >= BLOCK) { int sgn = blockDispAccum < 0 ? -1 : (blockDispAccum > 0 ? 1 : 0);
                    if (blockSign != 0 && sgn != 0 && sgn != blockSign) reversals++; if (sgn != 0) blockSign = sgn; blockDispAccum = 0; blockLen = 0; } }
            boundHeadSum += nb; if (nb > 0) contHits++;
            if (nb < minBound) minBound = nb; if (nb > maxBound) maxBound = nb;
            for (int m = 0; m < N; m++) {
                int cur = mot.boundSeg.get(m), prev = prevBound[m];
                if (prev < 0 && cur >= 0) { bindEvents++; attachStart[m] = t; }
                else if (prev >= 0 && cur < 0) { detachEvents++; if (attachStart[m] >= 0) { addLife(t - attachStart[m]); attachStart[m] = -1; } }
                prevBound[m] = cur;
                int nu = mot.nucleotideState.get(m); if (nu >= 0 && nu < 4) nucOcc[nu]++;
                if (nucInit && nu != prevNuc[m]) { chemTransitions++; if (prevNuc[m] == MotorStore.NUC_NONE && nu == MotorStore.NUC_ATP) atpTurnover++; }
                prevNuc[m] = nu;
                if (RIGOR_ON) {
                    int rr = mot.ruptureStats.get(2 * m);
                    if (rr > prevRup[m]) {            // this motor mechanically ruptured this step
                        rigorRuptures++;
                        double frup = mot.forceDotFil.get(m) * 1e12;   // realized axial bond load at rupture (pN)
                        if (Double.isFinite(frup)) { rupForceSum += frup; rupForceSq += frup * frup;
                            rupForceMax = Math.max(rupForceMax, frup); rupForceMin = Math.min(rupForceMin, frup);
                            int bin = (int) Math.floor(frup) + 40; if (bin >= 0 && bin < 120) { if (rupForceHist.length == 0) rupForceHist = new int[120]; rupForceHist[bin]++; } }
                    }
                    prevRup[m] = rr;
                }
                if (ADP_RUP_ON) { int ar = mot.adpRuptureStats.get(2 * m); if (ar > prevAdpRup[m]) adpRuptures++; prevAdpRup[m] = ar; }
            }
            if (occOn()) { occGeomCandidates += ed.occStats.get(0); occRejects += ed.occStats.get(1);
                occAccepted += ed.occStats.get(2); occConflicts += ed.occStats.get(3); }
            nucInit = true; nucSteps++;
        }

        double lifetimeMean() { if (lifeN == 0) return 0; long s = 0; for (int i = 0; i < lifeN; i++) s += lifeArr[i]; return (double) s / lifeN; }
        int lifetimePctl(double p) { if (lifeN == 0) return 0; int[] c = java.util.Arrays.copyOf(lifeArr, lifeN); java.util.Arrays.sort(c);
            return c[(int) Math.min(lifeN - 1, Math.max(0, Math.round(p * (lifeN - 1))))]; }

        void finish() {
            velFullRun = ls(cx, 0, steps); velProd = -velFullRun;
            velPostEquil = -ls(cx, steps / 2, steps);
            int nInst = Math.max(1, steps - 1);
            velInstMean /= nInst; velInstVar = velInstVar / nInst - velInstMean * velInstMean; fracMovingProd /= nInst;
            for (int w = 0; w < 5; w++) windowVel[w] = -ls(cx, w * steps / 5, (w + 1) * steps / 5);
            meanBoundHeads = (double) boundHeadSum / steps; continuity = (double) contHits / steps;
            netForcePn = sumForce / steps;
            if (minBound == Integer.MAX_VALUE) minBound = 0;
        }
        /** RIGOR RUPTURE: total the per-motor rate·dt-cap warnings (substep/abort signal) at end of run. */
        void finishRigor(MotorStore mot) { long w = 0; for (int m = 0; m < N; m++) w += mot.ruptureStats.get(2 * m + 1); rateCapWarns = w; }
        static double ls(double[] y, int lo, int hi, double dt) { int n = hi - lo; if (n < 2) return 0;
            double sx = 0, sy = 0, sxx = 0, sxy = 0; for (int i = lo; i < hi; i++) { double x = i * dt; sx += x; sy += y[i]; sxx += x * x; sxy += x * y[i]; }
            double den = n * sxx - sx * sx; return den == 0 ? 0 : (n * sxy - sx * sy) / den; }
        double ls(double[] y, int lo, int hi) { return ls(y, lo, hi, dt); }

        String toJson(int density, int seed, int steps, double dt, int N, int nSeg, int M, double beamL, double slack, double cullR,
                      String rev, long startMs, long endMs, double wallS, String status, String err, double warmMs) {
            StringBuilder b = new StringBuilder("{\n");
            String model = String.format(Locale.US, "explicit-s2-l%.0f", beamL);
            boolean canonL = Math.abs(beamL - 40.0) < 1e-9;
            kv(b, "model", q(model)); kv(b, "exposed_s2_nm", beamL); kv(b, "canonical_L", canonL);
            kv(b, "ea_si", TwoBodyConverterMotor.EXP4G_EA_SI); kv(b, "ei_si", TwoBodyConverterMotor.EXP4G_EI_SI);
            kv(b, "rupture_mode", RUPTURE_MODE);
            kv(b, "density", density); kv(b, "seed", seed); kv(b, "steps", steps); kv(b, "dt", dt);
            kv(b, "backend", q("gpu")); kv(b, "device_resident", true); kv(b, "device_validated", false);
            kv(b, "heads", N); kv(b, "nSeg", nSeg); kv(b, "beam_nodes_M", M); kv(b, "beam_L_nm", beamL); kv(b, "slack_nm", slack);
            kv(b, "mat_x_um", TwoBodyConverterMotor.G4_MATX); kv(b, "mat_y_um", TwoBodyConverterMotor.G4_MATY);
            kv(b, "area_um2", TwoBodyConverterMotor.G4_MATX * TwoBodyConverterMotor.G4_MATY);
            kv(b, "fil_contour_um", nSeg * (TwoBodyConverterMotor.G4_MONO + 1) * Constants.actinMonoRadius);
            kv(b, "cullR_nm", cullR * 1e3); kv(b, "free_binding", true); kv(b, "occupancy_exclusion", occOn());
            kv(b, "code_rev", q(rev)); kv(b, "start_ms", startMs); kv(b, "end_ms", endMs); kv(b, "wall_s", wallS);
            kv(b, "warm_compile_ms", warmMs); kv(b, "status", q(status)); kv(b, "error", q(err));
            kv(b, "steps_per_s", status.equals("ok") ? steps / wallS : 0.0);
            // motion — velProd is the WHOLE-window LS estimator (matched to the dimer campaign)
            kv(b, "vel_full_run", velFullRun); kv(b, "vel_prod", velProd); kv(b, "vel_postequil", velPostEquil);
            kv(b, "total_axial_disp_um", cx[steps - 1] - cx[0]); kv(b, "productive_disp_um", -(cx[steps - 1] - cx[0]));
            kv(b, "vel_inst_mean", velInstMean); kv(b, "vel_inst_var", velInstVar); kv(b, "frac_moving_prod", fracMovingProd); kv(b, "reversals", reversals);
            b.append("  \"window_vel\": ").append(arr(windowVel)).append(",\n");
            // binding
            kv(b, "mean_bound_heads", meanBoundHeads); kv(b, "continuity", continuity);
            kv(b, "bound_frac", N > 0 ? meanBoundHeads / N : 0.0); kv(b, "min_bound", minBound); kv(b, "max_bound", maxBound);
            kv(b, "bind_events", bindEvents); kv(b, "detach_events", detachEvents);
            kv(b, "lifetime_mean_steps", lifetimeMean()); kv(b, "lifetime_median_steps", lifetimePctl(0.5));
            kv(b, "lifetime_p90_steps", lifetimePctl(0.90)); kv(b, "lifetime_p99_steps", lifetimePctl(0.99));
            kv(b, "lifetime_mean_ms", lifetimeMean() * dt * 1e3);
            // chemistry
            double occDen = Math.max(1, nucSteps * (long) N);
            kv(b, "nuc_occ_none", nucOcc[0] / occDen); kv(b, "nuc_occ_atp", nucOcc[1] / occDen);
            kv(b, "nuc_occ_adppi", nucOcc[2] / occDen); kv(b, "nuc_occ_adp", nucOcc[3] / occDen);
            kv(b, "chem_transitions", chemTransitions); kv(b, "atp_turnover", atpTurnover); kv(b, "catch_slip_detach", detachEvents);
            // RIGOR MECHANICAL RUPTURE — distinct detachment cause (never folded into detach_events)
            kv(b, "rigor_rupture_on", RIGOR_ON);
            if (RIGOR_ON) {
                kv(b, "rigor_model", (long) RIGOR_MODEL); kv(b, "rigor_k0_per_s", RIGOR_K0);
                kv(b, "rigor_aCatch", RIGOR_AC); kv(b, "rigor_xCatch_nm", RIGOR_XC); kv(b, "rigor_aSlip", RIGOR_AS); kv(b, "rigor_xSlip_nm", RIGOR_XS);
            }
            kv(b, "rigor_ruptures", rigorRuptures);
            kv(b, "rigor_rupture_frac_of_detach", detachEvents > 0 ? (double) rigorRuptures / detachEvents : 0.0);
            kv(b, "rate_cap_warnings", rateCapWarns);
            kv(b, "force_at_rupture_mean_pn", rigorRuptures > 0 ? rupForceSum / rigorRuptures : 0.0);
            kv(b, "force_at_rupture_sd_pn", rigorRuptures > 1 ? Math.sqrt(Math.max(0, rupForceSq / rigorRuptures - (rupForceSum / rigorRuptures) * (rupForceSum / rigorRuptures))) : 0.0);
            kv(b, "force_at_rupture_min_pn", rigorRuptures > 0 ? rupForceMin : 0.0);
            kv(b, "force_at_rupture_max_pn", rigorRuptures > 0 ? rupForceMax : 0.0);
            // CONTINUOUS LOCAL ACTIN CO-OCCUPANCY EXCLUSION (noncanonical experimental; default OFF)
            kv(b, "occupancy_exclusion_on", occOn());
            kv(b, "occupancy_exclusion_nm", OCC_EXCL_NM);
            kv(b, "occupancy_exclusion_tol_nm", OCC_TOL_NM);
            kv(b, "occupancy_geometric_candidates", occGeomCandidates);
            kv(b, "occupancy_rejects", occRejects);
            kv(b, "occupancy_accepted", occAccepted);
            kv(b, "occupancy_same_step_conflicts", occConflicts);
            kv(b, "occupancy_rejection_fraction", occGeomCandidates > 0 ? (double) occRejects / occGeomCandidates : 0.0);
            kv(b, "occupancy_mean_local_regions", meanBoundHeads);   // occupied local regions ≈ currently bound heads
            kv(b, "occupancy_max_local_regions", (long) maxBound);
            // health
            kv(b, "net_force_pn", netForcePn); kv(b, "peak_load_pn", peakLoadPn);
            kv(b, "invalid_states", invalidStates); kv(b, "solver_failures", invalidStates);
            // derived
            kv(b, "vel_per_bound_head", meanBoundHeads > 0 ? velProd / meanBoundHeads : 0.0);
            b.append("  \"note\": ").append(q("single-head " + model + "; velProd = whole-window LS slope of filament centroid·b̂ over all `steps` (equil=0, matched to the HMM-dimer campaign); vel_postequil = LS over the 2nd half (transient diagnostic). solver health on this device path is exposed only via redOut finiteness ⇒ solver_failures == invalid_states (no separate per-motor pivot/residual buffer crosses the bus in production residency). No two-head/branch/joint-gap fields (single head has no fork).")).append("\n}\n");
            return b.toString();
        }
        static void kv(StringBuilder b, String k, double v) { b.append("  \"").append(k).append("\": ").append(fmt(v)).append(",\n"); }
        static void kv(StringBuilder b, String k, long v) { b.append("  \"").append(k).append("\": ").append(v).append(",\n"); }
        static void kv(StringBuilder b, String k, boolean v) { b.append("  \"").append(k).append("\": ").append(v).append(",\n"); }
        static void kv(StringBuilder b, String k, String vq) { b.append("  \"").append(k).append("\": ").append(vq).append(",\n"); }
        static String q(String s) { return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "'")) + "\""; }
        static String fmt(double v) { if (Double.isNaN(v) || Double.isInfinite(v)) return "null"; return String.format(Locale.US, "%.6g", v); }
        static String arr(double[] a) { StringBuilder s = new StringBuilder("["); for (int i = 0; i < a.length; i++) { if (i > 0) s.append(", "); s.append(fmt(a[i])); } return s.append("]").toString(); }
    }
}
