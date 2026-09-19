package softbox;

import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.types.arrays.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LONG, HIGH-DENSITY, FULL-MAT SITE-NORMAL GLIDING ASSAY — "does the revised site-normal motor still glide?"
 *
 * <p>A deliberately crude, deliberately LONG compatibility test, not a velocity campaign. Nothing is tuned:
 * every molecular parameter, gate, rest pose, rate and chemistry setting is the frozen current motor. The only
 * things this harness chooses are SCENE (a 7 × 1 µm lawn at 3000 heads/µm²) and DURATION (up to 10^6 steps,
 * 2.5 s of simulated time), and the only machinery it adds is bookkeeping.
 *
 * <h3>Execution path</h3>
 * <b>CPU sequential runner.</b> {@code ExplicitCompleteMatHarness.buildGlidingGraph} REFUSES the site-normal
 * path by design (the four kernels {@code matBeamGeomTilt} / {@code matS2SolveStepTilt} / {@code headAxisStep} /
 * {@code siteCoupleStep} have no validated device lowering), so there is no GPU option and none is faked. The
 * old non-chi / non-site-normal GPU motor is NEVER substituted.
 *
 * <h3>Culling — why it is load-bearing here, and what it does NOT change</h3>
 * The step is completely dominated by {@code matS2SolveStepTilt} (measured 279 µs per motor-step, &gt;99.9 % of
 * the step at every density), and the explicit-S2 path historically solved ALL N motors every step. At N =
 * 21 000 that is 5.9 s/step and the assay is impossible. This harness therefore runs the validated production
 * <b>per-segment UNION cull</b> ({@link MatSoaSlice#matCull}: active iff BOUND or the motor site lies within
 * {@code queryR} of the clamped closest point of ANY live segment — never a filament-midpoint window) and
 * advances only the active motors' beams. A culled motor is by construction unbound and out of binding range;
 * it simply does not thermally fluctuate while it waits. The gates below check the two things that could make
 * that wrong: (i) no motor the brute (all-active) gate would accept is ever culled, and (ii) an all-active
 * control reproduces the culled run's aggregate behaviour.
 *
 * <p>The same mechanism supplies parallelism: kept motors are tagged round-robin with a worker id and the SAME
 * kernel is called once per worker. Every motor is solved exactly once with identical arithmetic; gate W below
 * requires the multi-worker step to be BIT-IDENTICAL to the single-worker step.
 *
 * <pre>
 *   java --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.SiteNormalLongGlideHarness -gates
 *   java ... softbox.SiteNormalLongGlideHarness -run [-steps N] [-seed S] [-workers T] [-density D] [-matx X]
 *   java ... softbox.SiteNormalLongGlideHarness -report
 * </pre>
 */
public class SiteNormalLongGlideHarness {

    // ------------------------------------------------------------------ PHASE 4: the pinned assay parameters
    static double       DT      = ExplicitCompleteMatHarness.DT;   // 2.5e-6 s (-dt)
    static double       ETA     = Constants.aeta;                  // 0.1 Pa·s, the canonical solvent (-eta)
    static final double MAT_X   = 7.0, MAT_Y = 1.0;                // µm
    static final double DENSITY = 3000.0;                          // HEADS per µm² (this motor is single-headed)
    // -filsegs <n>: mechanical segments in the filament. 1 => the RIGID SINGLE-ROD branch (same total contour,
    // drag from the full length): no joints, no chain forces, no roll spring => the only torque on the filament
    // is from the motors. That makes it the clean TOUCHSTONE for filament rotation.
    static int          FIL_SEGS = 12;                             // ~2.106 µm flexible chain
    static final int    MAX_STEPS = 1_000_000;                     // 2.5 s simulated
    static double TARGET_UM = 2.000;                               // success / reversal threshold (-target)
    static final int    CKPT_EVERY = 10_000;                       // progress.json + trajectory row
    static final int    STATE_EVERY = 25_000;                      // resumable binary checkpoint
    static final int    VIZ_STRIDE_DEFAULT = 250;                  // decimated long movie
    static final int    HIRES_HALF = 350;                          // Phase 15 window half-width (steps)
    // PHASE 9 health thresholds. A myosin cross-bridge runs at a few pN and the model's own faithful release
    // cap is 12 pN, so 200 pN sustained is unambiguously numerical, not biological. The chamber is nanometres
    // deep, so a centroid 0.5 µm off the lawn plane is a divergence, not a fluctuation.
    static final double FORCE_ABSURD_PN = 200.0;
    static final int    FORCE_STRIKES   = 20;
    static final double Z_ESCAPE_UM     = 0.5;

    static String OUT = "RUN_LOGS/motor_audit/site_normal_long_glide";
    static int    SEED = 20260813;
    static int    STEPS = MAX_STEPS;
    static int    WORKERS = 16;
    static double DENS = DENSITY, MX = MAT_X, MY = MAT_Y;
    static int    VIZ_STRIDE = VIZ_STRIDE_DEFAULT;
    static boolean VIZ = true;
    static String  VIZ_DIR = null;   // -3js <dir>: explicit viewer output dir (default <OUT>/simviewer/longrun)
    static boolean RESUME = false;
    static boolean GATE_A = false;
    // -randbase: per-motor RANDOM BASE AZIMUTH (ExplicitCompleteMatHarness.RAND_BASE_AZ). SCENE control, not
    // physics — it rotates each motor's whole base geometry rigidly about its OWN pivot around eup, so the
    // lawn stops sharing one lab-fixed base triad. The S2 beam already supplies a wide ANNEALED orientational
    // cone (Lp = EI/kT = 174 nm over a 40 nm free S2 ⇒ theta_rms = 39 deg, re-randomized every tau_S2 = 415 us,
    // i.e. ~1500x between captures), but the neck axis n1 and the binding-plane normal econv are built from the
    // STORED base triad (neckFrame / eBindOf) and no amount of beam flexing rotates them. This flag is the only
    // lever on that channel. Default OFF ⇒ the historical path is byte-identical.
    static boolean RANDBASE = false;
    // CANONICAL DEFAULT since 2026-08-17: the DETACHED head rests collinear with the neck
    // (ExplicitCompleteMatHarness.STRAIGHT_REST). -straightrest is retained as an accepted no-op so existing
    // command lines keep working; -nativerest reproduces the superseded (phi_pre, psiActin, chi=0) pose.
    static boolean STRAIGHTREST = true;
    // -gpu: run the trajectory on the DEVICE-RESIDENT site-normal graph (the newly wired chi-dynamic stack).
    // There is NO cull on the device path — the whole mat is solved every step, in parallel, which is the point.
    // EXPERIMENTAL: the whole-step CPU/GPU gate must be consulted before any result from this path is quoted.
    static boolean GPU_MODE = false;
    // -nohires: disable the per-step milestone pre-trigger ring. The ring builds a full frameJson EVERY step
    // (measured ~half the device step cost); a run that will not reach the 1.00/2.00 um milestones gains ~2x.
    // The decimated -3js movie and the milestone bookkeeping itself are UNAFFECTED.
    static boolean NO_HIRES = false;
    // -filx <um>: translate the whole filament along +x after the scene is built, so it starts near the
    // DOWNSTREAM-most edge and has the full mat to glide across. The assay glides -x (pointed-leading), so a
    // POSITIVE offset puts the filament at the right edge and maximises runway. SCENE placement only: the mat,
    // the motors and every physical parameter are untouched, and centroid0/fwd are captured from the moved
    // scene, so all displacement bookkeeping stays self-consistent.
    static double FIL_X0 = 0.0;
    /** Head render radius = the DRAG radius, so the picture matches the dynamics (see frameJson). */
    static final double HEAD_DRAW_R = TwoBodyConverterMotor.RHEAD_UM;
    /** -norollbrownian: kill ONLY the Brownian torque about the body-fixed axial direction (chanMask[2]). */
    static boolean ROLL_BROWN = true;
    /** -nofilbrownian: kill ALL four filament Brownian channels. */
    static boolean FIL_BROWN_ALL = true;
    /** -mirror: run on a MIRRORED actin lattice (helical twist handedness reversed). The chirality control:
     *  a genuinely chiral twirl must REVERSE sign; an achiral artifact will not. Passed as cfg()'s mirror arg. */
    static double MIRROR = +1.0;
    /** -stroke-skew <deg>: askew STROKE offset in the local site tangent plane (cfg arg 5, EPS_STROKE_DEG).
     *  Gives the stroke a circumferential component of Ractin*eps -- the candidate twirl mechanism, and unlike
     *  lattice chirality it needs NO site-register tracking: every stroke contributes the same sign.
     *  NOTE it enters as bindAzim += MIRROR_SIGN*epsStroke, so it is mirror-COUPLED; do not treat +/-eps and
     *  +/-chirality as independent axes. Reverse eps at FIXED chirality: the eps-odd difference cancels the
     *  achiral rotational diffusion that dominates the eps=0 null. */
    static double STROKE_SKEW_DEG = 0.0;
    /** -conv-skew <deg>: MOTOR-side converter stroke-plane rotation (CONV_SKEW_DEG), the OTHER chirality
     *  channel. Structurally different from STROKE_SKEW_DEG: it rotates the plane in which the converter
     *  swing happens, so the stroke DISPLACEMENT acquires a circumferential component, while the actin site
     *  is untouched (bindArc/bindAzim never written). Run with the LINEAR progress ramp to match the
     *  2026-07-27 eta-map arm, which is this campaign's historical comparator. Reaches the kernel via
     *  ChiralSiteHarness.EPS_CONV_ARM, which cfg() reads into ExplicitCompleteMatHarness.CONV_SKEW_DEG. */
    static double CONV_SKEW_DEG = 0.0;
    /** -registry-switch <deg>: ATLAS MECHANISM A4 — the bound registry's REST ORIENTATION switches by this
     *  angle at the ADP·Pi→ADP transition, delivering a DIRECT AXIAL COUPLE to the filament instead of a
     *  tangential force at the moment arm (which is what -stroke-skew does). Requires -registry-k > 0.
     *  Mirror-coupled like the stroke skew; reverse it at FIXED chirality for the eps-odd estimator. */
    static double REG_SWITCH_DEG = 0.0;
    /** -registry-k <N·m/rad>: bound orientational registry stiffness. 0 (default) ⇒ registry exactly inert.
     *  Fixture-precedent values: soft 2.00e-21, stiff 4.12e-19 (DISCRETE_ACTIN_SITE §12-17). */
    static double REG_K_NM = 0.0;
    /** -s2-catch <factor>: SPECULATIVE binding-triggered catch-stiffening of the S2 BENDING stiffness.
     *  Bound motors get g4kb x factor; free motors keep the canonical value, so DIFFUSIVE SEARCH IS
     *  UNTOUCHED -- which is the whole point (Exp 4E: recruitment and transmission share one compliance;
     *  no PASSIVE tail does both; "what would work (not built): a state-dependent catch-STIFFENING tail").
     *  NOT PHYSICALLY JUSTIFIED. No measurement of a state-dependent S2 stiffness is known to us; this
     *  ASSUMES an unknown biochemical effect at binding. It is a "what would the motor need" probe of the
     *  series-compliance explanation for the converter-skew null, not a proposed model change. 1.0 = inert. */
    static double S2_CATCH = 1.0;
    /** -s2-loadcatch <factor> / -s2-loadf0 <pN>: LOAD-gated S2 bending stiffening. kb ramps kb0 -> kb0*factor
     *  as the cross-bridge load |F8| goes 0 -> F0. PHYSICALLY MOTIVATED, unlike -s2-catch: Scholz 2005
     *  (Biophys J 88:360) MEASURED a 10x extension/compression stiffness asymmetry in a myosin tether,
     *  localised OUTSIDE the head. A free searching head is unloaded and stays soft; a bound head bearing
     *  tension stiffens; a bound but lightly loaded head ALSO stays soft -- which -s2-catch did not do, and is
     *  the likely reason x20 binding-gating REVERSED gliding. Factor 10 = the measured value. 1.0 = inert. */
    static double S2_LOADCATCH = 1.0;
    static double S2_LOADF0_PN = 2.0;
    /** -twopoint: EXPLORATORY two-point surface cross-bridge to protomers n and n-2. Chirality from the
     *  lattice geometry, NOT from an imposed skew -- so this is the one mechanism that can be run at eps=0. */
    static boolean TWO_POINT = false;
    static double TWO_POINT_FOOT_NM = 2.82;
    static boolean KEEP_F9_CLI = false;
    static int PAIR_CLI = 2;   // monomer separation of site B from site A; 0 = SAME site
    static double ANC_W_CLI = 0.25;   // ancillary spring weight; 0 = single spring at the OFFSET anchor
    static double SOFT_CONV = 1.0, SOFT_S2A = 1.0, SOFT_S2B = 1.0, SOFT_KBIND = 1.0;
    static boolean CONV_DIAG_CLI = false;
    static boolean HEADAXIS_CLI  = false;   // -headaxis-diag: histogram xHeadHat.eup, bound vs unbound
    static boolean FLIP_HELIX_CLI = false;   // -flip-helix: mirror the ACTIN LATTICE handedness
    // SIGN verified empirically 2026-09-10: converter axial = -7.0*sin(tilt) nm, + = BARBED, so a
    // NEGATIVE tilt is the biological (converter barbed-side) pose. Earlier comment had it backwards.
    static double  HEAD_TILT_CLI  = 0.0;     // -headtilt <deg>: NEGATIVE = converter barbed-proximal
    static boolean TILT_GATE_CLI  = false;   // -tiltgate: admit on the TILTED target (removes the free pre-stroke swing)
    static double  CONV_AZ_CLI    = 0.0;     // -convaz <deg>: converter azimuth ON the head (head stays put)
    static boolean CONV_AZ_COMP_CLI = true;  // -convaz-nocomp: drop the stroke-amplitude compensation
    static double  STROKE_SWING_CLI = Double.NaN;  // -stroke-swing <deg>: 60=canonical, 0=OFF, -60=REVERSED
    static double  STROKE_PHASE_CLI = Double.NaN;  // -stroke-phase <deg>: rotate the stroke window (re-aim)
    // -kbind-bound-only: wire matKbindGate so the head-orientation stiffness params[7] is BOUND-STATE GATED
    // (bound -> kBind, detached -> the much weaker kDet). WITHOUT this the gate never runs and params[7] keeps
    // whatever it was filled with for EVERY motor, bound or free -- so -soft-kbind silently stiffens the
    // DETACHED head too and suppresses capture. Default false = the historical behaviour.
    static boolean KBIND_BOUND_ONLY_CLI = false;
    // -rollspring: inter-segment torsional-roll constraint (RollSpringSystem). Without it segment roll is a
    // free DOF and the filament accumulates unbounded internal twist instead of rotating as a body.
    static boolean ROLL_SPRING_CLI = false;
    static double  ROLL_STIFF_CLI  = 0.5;
    static double  ROLL_REST_CLI   = Double.NaN;   // -rollrest <deg>; NaN = derive from segment length
    static double  F8_TAN_CLI      = 0.0;          // -f8tan <nm>: head-side conformational tangential shift
    // ---- FLUORESCENT PROBES (-probes N): sparse single-fluorophore labels, the experimental twirl readout.
    // Each probe is a MATERIAL point on the actin surface (fixed arc + fixed phase in the segment material
    // frame), so it ORBITS the filament axis as the filament rolls -- exactly what Sase 1997 / Beausang 2008
    // observe. Rhodamine-phalloidin is the label used throughout that literature (see
    // docs/twirl/ACTIN_SITE_LATTICE_LITERATURE_BASIS.md s9.2), hence the orange-red default in the viewer.
    // Viewer-only: probes are written to the -3js JSON and affect NO physics.
    static int    PROBES = 0;              // number of labels along the contour (0 = off)
    static double PROBE_PHASE_DEG = 0.0;   // material phase of the label line, degrees
    // Radial offset of the label from the filament centreline. DEFAULT = the actin radius (3.5 nm), which is
    // where a real fluorophore sits -- but a 3.5 nm orbit is only a few pixels on a 2 um filament. The real
    // experiments do not SEE an orbit either; they infer rotation from dipole polarisation. So for a movie the
    // offset may be EXAGGERATED for legibility; the factor is written into every frame as `probeExag` so the
    // visualisation is never silently misleading. -probe-radius <um>; <=0 means 'use the actin radius'.
    static double PROBE_RADIUS_UM = -1.0;


    public static void main(String[] args) throws Exception {
        boolean gates = false, run = false, report = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-gates" -> gates = true;
                case "-run" -> run = true;
                case "-report" -> report = true;
                case "-out" -> OUT = args[++i];
                case "-seed" -> SEED = Integer.parseInt(args[++i]);
                case "-steps" -> STEPS = Integer.parseInt(args[++i]);
                case "-workers" -> WORKERS = Integer.parseInt(args[++i]);
                case "-density" -> DENS = Double.parseDouble(args[++i]);
                case "-matx" -> MX = Double.parseDouble(args[++i]);
                case "-maty" -> MY = Double.parseDouble(args[++i]);
                case "-viz-stride" -> VIZ_STRIDE = Integer.parseInt(args[++i]);
                case "-noviz" -> VIZ = false;
                case "-3js" -> { VIZ = true; VIZ_DIR = args[++i]; }
                case "-eta" -> ETA = Double.parseDouble(args[++i]);
                case "-dt" -> DT = Double.parseDouble(args[++i]);
                case "-resume" -> RESUME = true;
                case "-gate-a" -> { GATE_A = true; }
                case "-randbase" -> RANDBASE = true;
                case "-straightrest" -> STRAIGHTREST = true;   // now the default; kept so old invocations still parse
                case "-nativerest" -> STRAIGHTREST = false;    // the superseded pre-2026-08-17 rest pose
                case "-bothstrands" -> ExplicitCompleteMatHarness.TWO_STRAND_SITES = true;  // now default; kept so old invocations parse
                case "-onestrand" -> ExplicitCompleteMatHarness.TWO_STRAND_SITES = false;   // superseded single-strand lattice
                case "-gpu" -> GPU_MODE = true;
                case "-devicecull" -> ExplicitCompleteMatHarness.DEVICE_CULL = true;
                case "-nohires" -> NO_HIRES = true;
                case "-filx" -> FIL_X0 = Double.parseDouble(args[++i]);
                case "-filsegs" -> FIL_SEGS = Integer.parseInt(args[++i]);
                // FILAMENT BROWNIAN ABLATION (noncanonical, default-off, diagnostic). Reaches the existing,
                // fixture-verified BrownianForceSystem.brownChannelMask through ExplicitCompleteMatHarness's
                // BR_FIL_* policy - no new physics, no new RNG, no kernel edit. -norollbrownian kills ONLY the
                // Brownian torque about the body-fixed axial direction (chanMask[2]), leaving translation and
                // tumble canonical, so accumulated roll becomes pure motor-generated signal. NOT FDT-consistent
                // for the roll DOF BY CONSTRUCTION: that is the point of the ablation, and it is why this must
                // never be a production path. Drift is unbiased to linear order (gamma_roll*dtheta/dt = tau_motor
                // + xi; removing xi leaves tau_motor/gamma_roll) PROVIDED tau_motor does not depend on the roll
                // fluctuations - which is NOT guaranteed here, since roll sets which helical sites face the bed.
                // ALWAYS report avgBound against the roll-on control before reading the drift.
                case "-norollbrownian" -> ROLL_BROWN = false;
                case "-nofilbrownian" -> FIL_BROWN_ALL = false;
                case "-mirror" -> MIRROR = -1.0;
                case "-stroke-skew" -> STROKE_SKEW_DEG = Double.parseDouble(args[++i]);
                case "-conv-skew" -> CONV_SKEW_DEG = Double.parseDouble(args[++i]);
                case "-registry-switch" -> REG_SWITCH_DEG = Double.parseDouble(args[++i]);
                case "-registry-k" -> REG_K_NM = Double.parseDouble(args[++i]);
                case "-s2-catch" -> S2_CATCH = Double.parseDouble(args[++i]);
                case "-s2-loadcatch" -> S2_LOADCATCH = Double.parseDouble(args[++i]);
                case "-s2-loadf0" -> S2_LOADF0_PN = Double.parseDouble(args[++i]);
                case "-twopoint" -> TWO_POINT = true;
                case "-twopoint-foot" -> TWO_POINT_FOOT_NM = Double.parseDouble(args[++i]);
                case "-twopoint-keepf9" -> KEEP_F9_CLI = true;
                case "-twopoint-nocouple" -> ExplicitCompleteMatHarness.TWO_POINT_DROP_COUPLE = true;
                case "-twopoint-alignfeet" -> ExplicitCompleteMatHarness.TWO_POINT_ALIGN_FEET = true;
                case "-twopoint-straddle" -> ExplicitCompleteMatHarness.TWO_POINT_STRADDLE = true;
                case "-triad" -> ExplicitCompleteMatHarness.TRIAD_ON = true;
                case "-headtilt" -> HEAD_TILT_CLI = Double.parseDouble(args[++i]);
                case "-tiltgate" -> TILT_GATE_CLI = true;   // capture gate + energy charge evaluated at the TILTED target
                case "-convaz" -> CONV_AZ_CLI = Double.parseDouble(args[++i]);
                case "-stroke-swing" -> STROKE_SWING_CLI = Double.parseDouble(args[++i]);   // 60=canonical, 0=off, -60=reversed
                case "-stroke-phase" -> STROKE_PHASE_CLI = Double.parseDouble(args[++i]);   // RE-AIM: rotate the stroke window, swing fixed
                case "-dtheta" -> throw new IllegalArgumentException(
                        "-dtheta is REMOVED: it wrote TwoBodyConverterMotor.DTHETA_MOTOR, which the mat path "
                      + "never reads, so it was silently INERT. Use -stroke-swing <deg> (60=canonical, 0=off, -60=reversed).");
                case "-convaz-nocomp" -> CONV_AZ_COMP_CLI = false;
                case "-flip-helix" -> FLIP_HELIX_CLI = true;
                case "-triad-rho" -> ExplicitCompleteMatHarness.TRIAD_RHO_NM = Double.parseDouble(args[++i]);
                // -triad-conform is now the DEFAULT (canonical 2026-09-19); kept as an explicit no-op so
                // existing scripts and logs that name it keep working. -triad-flat is the regression opt-out.
                case "-triad-conform" -> ExplicitCompleteMatHarness.TRIAD_CONFORM = true;
                case "-triad-flat" -> ExplicitCompleteMatHarness.TRIAD_CONFORM = false;
                case "-triad-zerostrain" -> { triadZeroStrainGate(); return; }
                case "-triad-tiltscan" -> { triadTiltScan(); return; }
                case "-triad-labpatch" -> ExplicitCompleteMatHarness.TRIAD_LAB_PATCH = true;   // legacy lab-fixed patch basis (regression control)
                case "-twopoint-ancw" -> ANC_W_CLI = Double.parseDouble(args[++i]);
                case "-twopoint-pair" -> PAIR_CLI = Integer.parseInt(args[++i]);
                case "-soft-conv" -> SOFT_CONV = Double.parseDouble(args[++i]);
                case "-soft-s2a"  -> SOFT_S2A  = Double.parseDouble(args[++i]);
                case "-soft-s2b"  -> SOFT_S2B  = Double.parseDouble(args[++i]);
                case "-soft-kbind" -> SOFT_KBIND = Double.parseDouble(args[++i]);
                case "-headaxis-diag" -> { HEADAXIS_CLI = true; ExplicitCompleteMatHarness.HEADAXIS_DIAG = true; }
                case "-convdiag" -> CONV_DIAG_CLI = true;
                case "-kbind-bound-only" -> KBIND_BOUND_ONLY_CLI = true;
                case "-rollspring" -> ROLL_SPRING_CLI = true;
                case "-rollstiff" -> ROLL_STIFF_CLI = Double.parseDouble(args[++i]);
                case "-rollrest" -> ROLL_REST_CLI = Double.parseDouble(args[++i]);
                case "-f8tan" -> F8_TAN_CLI = Double.parseDouble(args[++i]);
                case "-tgt-daz"   -> ExplicitCompleteMatHarness.TGT_DAZ_DEG  = Double.parseDouble(args[++i]);
                case "-tgt-darc"  -> ExplicitCompleteMatHarness.TGT_DARC_NM  = Double.parseDouble(args[++i]);
                case "-tgt-rscale"-> ExplicitCompleteMatHarness.TGT_RSCALE   = Double.parseDouble(args[++i]);
                case "-probes" -> PROBES = Integer.parseInt(args[++i]);
                case "-probe-phase" -> PROBE_PHASE_DEG = Double.parseDouble(args[++i]);
                case "-probe-radius" -> PROBE_RADIUS_UM = Double.parseDouble(args[++i]);
                // Viewer density controls (movie legibility; NO physics effect -- they only change which
                // motors the -3js frame writer emits). -viz-showr raises the full-articulation radius,
                // -viz-postsub 1 emits EVERY motor as an anchor post so the lawn is actually visible.
                case "-viz-showr" -> SHOW_R = Double.parseDouble(args[++i]);
                case "-viz-postsub" -> POST_SUB = Math.max(1, Integer.parseInt(args[++i]));
                case "-target" -> TARGET_UM = Double.parseDouble(args[++i]);   // skip the per-step pre-trigger ring (~2x on viz runs)
                // DIAGNOSTIC: enable the rigor mechanical-rupture pathway. RUPTURE_MODE's *parsed* default is 1
                // (canon v2), but that parse lives in ExplicitCompleteMatHarness.runProductionCell, which this
                // lineage never calls — so the field initializer 0 wins and rigor bonds have NO force-dependent
                // escape. Tests whether that is what produces the 500+ pN excursions.
                case "-rupture" -> { ExplicitCompleteMatHarness.RUPTURE_MODE = 1;
                                     ExplicitCompleteMatHarness.RIGOR_ON = true;
                                     ExplicitCompleteMatHarness.ADP_RUP_ON = false; }
                case "-randbase-seed" -> ExplicitCompleteMatHarness.RAND_BASE_SEED = Integer.parseInt(args[++i]);
                // A silently-swallowed unknown flag has now cost two campaign relaunches (a stale build
                // missing -mirror/-target, and -norollbrownian clobbered by cfg()). Anything that LOOKS like
                // a flag but matched no case is almost always a typo or a stale binary: say so loudly.
                default -> { if (args[i].startsWith("-"))
                                 System.err.printf(Locale.US, "  WARNING: unrecognised flag '%s' IGNORED "
                                     + "(typo, or this binary predates the flag - rebuild?)%n", args[i]); }
            }
        }
        if (!gates && !run && !report) { gates = true; run = true; }
        banner();
        Files.createDirectories(Path.of(OUT));
        if (gates) { if (!runGates()) { System.out.println("\n*** GATES FAILED — the long run is NOT started. ***"); return; } }
        if (run) runLong();
        if (report && !run) reportOnly();
    }

    /**
     * ZERO-STRAIN GATE (-triad-zerostrain). Pure geometry, no scene and no runner: place ONE head at the exact
     * canonical bound pose (tip ON the site point, head axis along -n_site, patch self-seeded from uSite) and
     * call the REAL CrossBridgeSystem.bondForcesSurfaceTriad with conform OFF, then ON. myoSpring is set to 3 so
     * k3 = 1 and the reported force IS the vector sum of the three spring extensions, in length units -- a bond
     * whose rest state is attainable must read exactly zero there.
     */
    static void triadZeroStrainGate() {
        double Ractin  = ExplicitCompleteMatHarness.R_ACTIN_NM * 1e-3;
        double rho     = ExplicitCompleteMatHarness.TRIAD_RHO_NM * 1e-3;
        double headLen = 7.0e-3, segLen = 0.100;

        System.out.printf(Locale.US, "%nTRIAD ZERO-STRAIN GATE   Ractin=%.2f nm  rho=%.2f nm  (vertex arcs %+.1f / %+.1f / %+.1f deg)%n",
                Ractin * 1e3, rho * 1e3, Math.toDegrees(rho / Ractin),
                Math.toDegrees(-0.5 * rho / Ractin), Math.toDegrees(-0.5 * rho / Ractin));
        System.out.println("  Head placed EXACTLY on-site. A zero-rest 3-spring bond must read 0 here.\n");
        System.out.printf(Locale.US, "  %-10s %14s %14s %14s%n", "conform", "|sum ext| nm", "roll torque", "verdict");

        for (int mode = 0; mode < 2; mode++) {
            FloatArray motorCoord = new FloatArray(9), motorUVec = new FloatArray(9), motorYVec = new FloatArray(9);
            FloatArray filCoord = new FloatArray(3), filUVec = new FloatArray(3), filYVec = new FloatArray(3);
            FloatArray filSegLength = new FloatArray(1), bindArc = new FloatArray(1), bindAzim = new FloatArray(1);
            IntArray boundSeg = new IntArray(1);
            FloatArray bondData = new FloatArray(CrossBridgeSystem.STRIDE);
            FloatArray xbParams = new FloatArray(8), headPatch = new FloatArray(3);

            // filament: centre at the origin, axis +x, yVec +y  =>  zVec = u x y = +z
            filUVec.set(0, 1f); filYVec.set(1, 1f);
            filSegLength.set(0, (float) segLen);
            bindArc.set(0, (float) (0.5 * segLen));   // arc0 = bindArc - halfLen = 0 (segment centre)
            bindAzim.set(0, 0f);                      // az0 = 0 => outward normal = +y, azimuthal tangent = +z
            boundSeg.set(0, 0);

            // head: tip ON the site point (0, Ractin, 0); axis = -n_site = -y
            motorUVec.set(2, 0f); motorUVec.set(5, -1f); motorUVec.set(8, 0f);
            motorCoord.set(2, 0f); motorCoord.set(5, (float) (Ractin + 0.5 * headLen)); motorCoord.set(8, 0f);

            xbParams.set(0, 3f);                      // myoSpring: k3 = 1 => force == summed extension
            xbParams.set(4, (float) headLen);
            xbParams.set(6, (float) Ractin);
            FloatArray triP = FloatArray.fromElements((float) rho, 1f, 0f, (float) mode);

            CrossBridgeSystem.bondForcesSurfaceTriad(motorCoord, motorUVec, motorYVec,
                    filCoord, filUVec, filYVec, filSegLength, boundSeg, bindArc, bindAzim,
                    bondData, xbParams, triP, headPatch);

            double fx = bondData.get(0), fy = bondData.get(1), fz = bondData.get(2);
            double ext = Math.sqrt(fx * fx + fy * fy + fz * fz) * 1e3;      // nm
            double roll = bondData.get(9);                                   // segment torque . filament axis (+x)
            System.out.printf(Locale.US, "  %-10s %14.4f %14.3e %14s%n", mode == 0 ? "OFF" : "ON",
                    ext, roll, ext < 1e-6 ? "STRAIN-FREE" : "FRUSTRATED");
        }
        System.out.printf(Locale.US, "%n  RESOLVED DEFAULT for this build: conform=%s  (canonical since 2026-09-19;%n"
                + "  -triad-flat restores the OFF row, which is the geometry every run before that date used).%n",
                ExplicitCompleteMatHarness.TRIAD_CONFORM ? "ON" : "OFF");
    }

    /**
     * TILT SCAN (-triad-tiltscan). The zero-strain gate only probes the IDEAL pose. A ratchet needs the
     * frustration torque to survive AVERAGING over the poses a bound head actually explores, so sweep the head
     * axis away from -n_site and read the roll torque (segment torque . filament axis) at each tilt, conform
     * OFF vs ON. Two planes: AXIAL (head axis tilts toward the filament axis -- the channel HEADAXIS_DIAG
     * measured) and AZIMUTHAL (tilts around the cylinder). The tip is held ON the site point throughout, and
     * the patch self-seeds RELAXED at each pose, so what is measured is the geometric frustration alone.
     * The symmetric mean over +-b is the rectification-relevant number: an ODD torque averages to zero and
     * cannot ratchet; a non-zero mean can.
     */
    static void triadTiltScan() {
        double Ractin  = ExplicitCompleteMatHarness.R_ACTIN_NM * 1e-3;
        double rho     = ExplicitCompleteMatHarness.TRIAD_RHO_NM * 1e-3;
        double headLen = 7.0e-3, segLen = 0.100;
        double[] tilts = {-40,-30,-20,-10,-5,0,5,10,20,30,40};

        System.out.printf(Locale.US, "%nTRIAD TILT SCAN   Ractin=%.2f nm  rho=%.2f nm   (tip held ON the site point)%n",
                Ractin * 1e3, rho * 1e3);
        System.out.println("  roll torque = segment torque . filament axis, in the k3=1 convention (contrast only, not physical units)\n");

        for (int plane = 0; plane < 2; plane++) {
            System.out.printf(Locale.US, "  --- %s tilt%n", plane == 0 ? "AXIAL (toward the filament axis)" : "AZIMUTHAL (around the cylinder)");
            System.out.printf(Locale.US, "  %8s %16s %16s %13s %13s%n", "tilt deg", "roll tau OFF", "roll tau ON", "axial F OFF", "axial F ON");
            double sumOff = 0, sumOn = 0; int n = 0;
            for (double bdeg : tilts) {
                double b = Math.toRadians(bdeg);
                double[] r = new double[4];
                for (int mode = 0; mode < 2; mode++) {
                    FloatArray motorCoord = new FloatArray(9), motorUVec = new FloatArray(9), motorYVec = new FloatArray(9);
                    FloatArray filCoord = new FloatArray(3), filUVec = new FloatArray(3), filYVec = new FloatArray(3);
                    FloatArray filSegLength = new FloatArray(1), bindArc = new FloatArray(1), bindAzim = new FloatArray(1);
                    IntArray boundSeg = new IntArray(1);
                    FloatArray bondData = new FloatArray(CrossBridgeSystem.STRIDE);
                    FloatArray xbParams = new FloatArray(8), headPatch = new FloatArray(3);

                    filUVec.set(0, 1f); filYVec.set(1, 1f);            // axis +x, yVec +y => zVec +z
                    filSegLength.set(0, (float) segLen);
                    bindArc.set(0, (float) (0.5 * segLen)); bindAzim.set(0, 0f);
                    boundSeg.set(0, 0);
                    // site point (0, Ractin, 0); outward normal +y; azimuthal tangent +z
                    double hux, huy, huz;
                    if (plane == 0) { hux = Math.sin(b); huy = -Math.cos(b); huz = 0; }
                    else            { hux = 0;           huy = -Math.cos(b); huz = Math.sin(b); }
                    double px = 0, py = Ractin, pz = 0;
                    motorUVec.set(2, (float) hux); motorUVec.set(5, (float) huy); motorUVec.set(8, (float) huz);
                    motorCoord.set(2, (float) (px - 0.5 * headLen * hux));
                    motorCoord.set(5, (float) (py - 0.5 * headLen * huy));
                    motorCoord.set(8, (float) (pz - 0.5 * headLen * huz));

                    xbParams.set(0, 3f); xbParams.set(4, (float) headLen); xbParams.set(6, (float) Ractin);
                    FloatArray triP = FloatArray.fromElements((float) rho, 1f, 0f, (float) mode);
                    CrossBridgeSystem.bondForcesSurfaceTriad(motorCoord, motorUVec, motorYVec,
                            filCoord, filUVec, filYVec, filSegLength, boundSeg, bindArc, bindAzim,
                            bondData, xbParams, triP, headPatch);
                    double fx = bondData.get(0), fy = bondData.get(1), fz = bondData.get(2);
                    r[mode] = bondData.get(9);                                       // roll torque (about +x)
                    r[2 + mode] = fx * 1e3;   // AXIAL force component (filament axis = +x): the GLIDE channel
                }
                System.out.printf(Locale.US, "  %8.0f %16.4e %16.4e %13.5f %13.5f%n", bdeg, r[0], r[1], r[2], r[3]);
                sumOff += r[0]; sumOn += r[1]; n++;
            }
            System.out.printf(Locale.US, "  %8s %16.4e %16.4e%n%n", "MEAN", sumOff / n, sumOn / n);
        }
        System.out.println("  A symmetric tilt distribution gives MEAN = 0 for an odd torque. A non-zero MEAN is the");
        System.out.println("  rectifiable component: a bias that survives averaging over the poses a bound head explores.");
    }

    static void banner() {
        System.out.println();
        // 2026-09-18: this banner used to assert "EXECUTION = CPU ... no GPU work is launched"
        // UNCONDITIONALLY, which was false whenever -gpu was passed: runLong() force-sets
        // SITE_NORMAL_DEVICE_OK and builds the device graph. A whole roll campaign was read as CPU
        // output on the strength of this text. It now reports the path that will ACTUALLY be taken.
        System.out.println("################################################################################");
        if (GPU_MODE) {
            System.out.println("#  EXECUTION = GPU DEVICE-RESIDENT SITE-NORMAL GRAPH  ***EXPERIMENTAL***        #");
            System.out.println("#  runLong() force-sets SITE_NORMAL_DEVICE_OK, so buildGlidingGraph does NOT    #");
            System.out.println("#  refuse. The whole-step CPU/GPU equivalence gate for the bound branch is NOT  #");
            System.out.println("#  green. Quote DIFFERENCES between arms on this same path, never absolute      #");
            System.out.println("#  levels. Drop -gpu for the validated CPU sequential runner.                   #");
        } else {
            System.out.println("#  EXECUTION = CPU SITE-NORMAL SEQUENTIAL RUNNER (the validated path)           #");
            System.out.println("#  buildGlidingGraph REFUSES siteNormalOn() unless -gpu force-enables it.       #");
        }
        System.out.println("################################################################################");
        System.out.printf(Locale.US, "  LONG HIGH-DENSITY SITE-NORMAL GLIDING ASSAY%n");
        System.out.printf(Locale.US, "  mat %.1f x %.1f um | density %.0f heads/um^2 | dt %.3e s | eta %.4g Pa.s%n",
                MX, MY, DENS, DT, ETA);
        System.out.printf(Locale.US, "  filament %d segments (~2.106 um), Brownian ON; motor Brownian ON%n", FIL_SEGS);
        System.out.printf(Locale.US, "  SITE_NORMAL_BIND ON, g6 RETIRED, g2 RETIRED, every4 sparse helical sites%n");
        System.out.printf(Locale.US, "  RAND_BASE_AZ %s%s, no phenomenological twirl torque, no steric%n",
                RANDBASE ? "ON" : "OFF", RANDBASE ? " (seed " + ExplicitCompleteMatHarness.RAND_BASE_SEED + ")" : "");
        System.out.printf(Locale.US, "  STROKE SKEW eps = %+.3f deg (EPS_STROKE_DEG, mirror-coupled) | MIRROR = %+.1f%n",
                STROKE_SKEW_DEG, MIRROR);
        System.out.printf(Locale.US, "  CONVERTER SKEW  = %+.3f deg (CONV_SKEW_DEG, ramp %s)%n",
                CONV_SKEW_DEG, CONV_SKEW_DEG != 0.0 ? "LINEAR" : "off");
        System.out.printf(Locale.US, "  REGISTRY (A4)   = switch %+.3f deg, kOmega %.3e N.m/rad -> %s%n",
                REG_SWITCH_DEG, REG_K_NM, (REG_SWITCH_DEG != 0.0 && REG_K_NM > 0) ? "ACTIVE" : "inert");
        System.out.printf(Locale.US, "  S2 CATCH        = x%.2f on BOUND motors (bending) -> %s  [SPECULATIVE]%n",
                S2_CATCH, S2_CATCH != 1.0 ? "ACTIVE" : "inert");
        System.out.printf(Locale.US, "  S2 LOAD-CATCH   = x%.2f ramped over |F8| 0..%.2f pN -> %s  [Scholz 2005]%n",
                S2_LOADCATCH, S2_LOADF0_PN, S2_LOADCATCH != 1.0 ? "ACTIVE" : "inert");
        System.out.printf(Locale.US, "  TWO-POINT BOND  = %s (n and n-2, foot half %.2f nm)  [EXPLORATORY]%n",
                TWO_POINT ? "ACTIVE" : "inert", TWO_POINT_FOOT_NM);
        System.out.printf(Locale.US, "  DETACHED REST = %s%n", STRAIGHTREST
                ? "STRAIGHT STICK (restC = (1,0,0), head collinear with the neck)"
                : "native (phi_pre, psiActin, chi=0)");
        System.out.printf(Locale.US, "  max %d steps = %.3f s simulated ; STOP at |forward| >= %.3f um%n",
                STEPS, STEPS * DT, TARGET_UM);
        System.out.println();
    }

    // ============================================================================ the scene, built ONE way
    static TwoBodyConverterMotor.Glide2D scene(int seed, double density, double mx, double my) {
        TwoBodyConverterMotor.G4_MX = mx; TwoBodyConverterMotor.G4_MY = my;
        ChiralSiteHarness.SITE_NORMAL = true;
        ChiralSiteHarness.HEAD_TILT = true;
        ExplicitCompleteMatHarness.SITE_AWARE = true;
        ChiralSiteHarness.ETA = ETA;
        ChiralSiteHarness.DTR = DT;
        ChiralSiteHarness.DENSITY = density;
        ChiralSiteHarness.FIL_SEGS = FIL_SEGS;
        ChiralSiteHarness.FIL_BROWN = true;
        // cfg(): every4 sparse helical lattice (PATH_B_SITE_MODE = 3), head roll ON, no registry spring,
        // NO binding skew, NO stroke skew, NO randomized base azimuth, mirror = +1 (native), head Brownian ON.
        // BEFORE cfg(): cfg() reads EPS_CONV_ARM into CONV_SKEW_DEG and CONV_RAMP into CONV_SKEW_RAMP.
        // Default RAMP_OFF is preserved when no converter skew is requested, so -conv-skew 0 is an exact no-op.
        ChiralSiteHarness.EPS_CONV_ARM = CONV_SKEW_DEG;
        if (CONV_SKEW_DEG != 0.0) ChiralSiteHarness.CONV_RAMP = ChiralSiteSystem.RAMP_LINEAR;
        ChiralSiteHarness.cfg(ChiralSiteHarness.PATH_B_SITE_MODE, true, 0.0, 0.0, STROKE_SKEW_DEG, RANDBASE, MIRROR, true);
        // AFTER cfg(): cfg -> resetChiral() clears REG_K / REG_SWITCH_DEG too, so assert them here.
        ExplicitCompleteMatHarness.REG_K = REG_K_NM;
        ExplicitCompleteMatHarness.REG_SWITCH_DEG = REG_SWITCH_DEG;
        ExplicitCompleteMatHarness.S2_CATCH_FACTOR = S2_CATCH;
        ExplicitCompleteMatHarness.S2_LOAD_FACTOR = S2_LOADCATCH;
        ExplicitCompleteMatHarness.S2_LOAD_F0_PN = S2_LOADF0_PN;
        ExplicitCompleteMatHarness.TWO_POINT_BOND = TWO_POINT;
        ExplicitCompleteMatHarness.TWO_POINT_FOOT_NM = TWO_POINT_FOOT_NM;
        ExplicitCompleteMatHarness.TWO_POINT_KEEP_F9 = KEEP_F9_CLI;
        ExplicitCompleteMatHarness.TWO_POINT_ANC_W = ANC_W_CLI;
        ExplicitCompleteMatHarness.TWO_POINT_PAIR = PAIR_CLI;
        ExplicitCompleteMatHarness.SOFT_CONV = SOFT_CONV;   // AFTER cfg(): resetChiral() would clobber these
        ExplicitCompleteMatHarness.SOFT_S2A  = SOFT_S2A;
        ExplicitCompleteMatHarness.SOFT_S2B  = SOFT_S2B;
        ExplicitCompleteMatHarness.SOFT_KBIND = SOFT_KBIND;
        ExplicitCompleteMatHarness.CONV_DIAG = CONV_DIAG_CLI;
        ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = KBIND_BOUND_ONLY_CLI;   // AFTER cfg(): resetChiral() clears it
        ExplicitCompleteMatHarness.ROLL_SPRING = ROLL_SPRING_CLI;
        ExplicitCompleteMatHarness.ROLL_STIFF  = ROLL_STIFF_CLI;
        ExplicitCompleteMatHarness.ROLL_REST_DEG = ROLL_REST_CLI;
        ExplicitCompleteMatHarness.F8_TAN_NM = F8_TAN_CLI;   // AFTER cfg()
        ExplicitCompleteMatHarness.HEAD_TILT_DEG = HEAD_TILT_CLI;   // AFTER cfg()
        ExplicitCompleteMatHarness.TILT_GATE = TILT_GATE_CLI;       // AFTER cfg(): resetChiral would clobber it
        ExplicitCompleteMatHarness.CONV_AZ_DEG   = CONV_AZ_CLI;     // AFTER cfg(): resetGeomScales clobbers
        ExplicitCompleteMatHarness.CONV_AZ_COMP  = CONV_AZ_COMP_CLI;
        ExplicitCompleteMatHarness.STROKE_SWING_DEG = STROKE_SWING_CLI;   // AFTER cfg()
        ExplicitCompleteMatHarness.STROKE_PHASE_DEG = STROKE_PHASE_CLI;   // AFTER cfg()
        if (FLIP_HELIX_CLI) {   // AFTER cfg(): mirror the lattice, the only valid control at eps=0
            ExplicitCompleteMatHarness.TWIST_PER_MON_DEG = -ExplicitCompleteMatHarness.TWIST_PER_MON_DEG;
            System.out.printf(java.util.Locale.US, "  HELIX FLIPPED   = twistPerMon %+.1f deg (MIRRORED LATTICE)%n",
                    ExplicitCompleteMatHarness.TWIST_PER_MON_DEG);
        }
        // AFTER cfg(): cfg -> resetChiral() clears STRAIGHT_REST, so it must be asserted here, not before.
        ExplicitCompleteMatHarness.STRAIGHT_REST = STRAIGHTREST;
        // SAME TRAP for the Brownian channels: cfg() ends with setBrownianPolicy(FIL_BROWN x4, ...), which
        // overwrites anything the CLI set during arg parsing. Re-assert AFTER cfg() and BEFORE build() (build ->
        // packExMat is what freezes e.brChan). Defaults (both true) reproduce cfg()'s own call exactly, so this
        // line is byte-identical on the canonical path.
        ExplicitCompleteMatHarness.setBrownianPolicy(FIL_BROWN_ALL, FIL_BROWN_ALL,
                FIL_BROWN_ALL && ROLL_BROWN, FIL_BROWN_ALL, true, true);
        System.out.printf(Locale.US, "  BROWNIAN POLICY: %s%n", ExplicitCompleteMatHarness.brownianPolicyString());
        var Gs = ChiralSiteHarness.build(seed);
        // The rigor kernel gates on the DATA field rigorParams[0] (NucleotideCycleSystem:519), not on the Java
        // static RIGOR_ON — setting the static alone dispatches the rigor kernel but it then takes the branch its
        // own comment calls "BYTE-IDENTICAL to cycleLymnTaylorRigor NONE block". installRigor writes the frozen
        // Guo-Guilford constants; it is called ONLY from runProductionCell and the HMM-dimer harnesses, never
        // from this lineage, which is why rigor rupture has been inert here.
        if (ExplicitCompleteMatHarness.RIGOR_ON) ExplicitCompleteMatHarness.installRigor(Gs.mot, DT);
        if (FIL_X0 != 0.0) {
            var ff = Gs.fil; int ns = Gs.nSeg;
            for (int s2 = 0; s2 < ns; s2++) ff.coord.set(ff.planeX(s2), (float) (ff.coord.get(ff.planeX(s2)) + FIL_X0));
            // end1/end2 are DERIVED from coord+uVec+segLength; refresh them so the pre-loop geometry reads
            // (contour, e2e, lawn-margin) see the moved filament rather than the stale as-built ends.
            DerivedGeometrySystem.derive(ff.coord, ff.uVec, ff.yVec, ff.zVec, ff.end1, ff.end2, ff.segLength, ff.counts);
        }
        return Gs;
    }

    // ================================================================================================ GATES
    static boolean runGates() {
        System.out.println("================================================================================");
        System.out.println("=== PHASE 0/3 GATES — execution path, cull completeness, worker bit-identity");
        System.out.println("================================================================================");
        StringBuilder log = new StringBuilder();
        boolean ok = true;

        // ---- GATE P0: the device path must REFUSE, not silently fall back --------------------------------
        var Gp = scene(SEED, 400.0, 3.0, 1.0);
        var ep = ExplicitCompleteMatHarness.packExMat(Gp, 1);
        boolean refused = false; String refMsg = "";
        try { ExplicitCompleteMatHarness.buildGlidingGraph(ep, true); }
        catch (IllegalStateException ex) { refused = true; refMsg = ex.getMessage(); }
        catch (Throwable ex) { refMsg = "unexpected: " + ex; }
        System.out.printf(Locale.US, "  P0 device path refuses site-normal          : %s%n", refused ? "PASS" : "FAIL");
        System.out.printf(Locale.US, "      \"%s\"%n", refMsg);
        log.append("## GATE P0 — execution path\n\nbuildGlidingGraph(siteNormalOn) threw: ")
           .append(refused ? "YES" : "NO").append(" — \"").append(refMsg).append("\"\n")
           .append("Verdict: **").append(refused ? "PASS" : "FAIL").append("** — the run executes on the CPU sequential runner.\n\n");
        ok &= refused;

        // ---- GATE C: cull completeness on the REAL production scene --------------------------------------
        // Requirement: no motor whose candidate the BRUTE (all-active) site gate ACCEPTS may be culled.
        System.out.println("  C  cull completeness (brute vs union cull, production scene) ...");
        var G = scene(SEED, DENS, MX, MY);
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        var cp = new ExplicitCompleteMatHarness.MatCullPlan(e, WORKERS);
        IntArray bruteActive = new IntArray(e.N); bruteActive.init(1);
        IntArray bruteCand = new IntArray(2 * e.N);
        DoubleArray bruteArc = new DoubleArray(2 * e.N), bruteAzim = new DoubleArray(e.N);
        long omitted = 0, accepted = 0; double worstKeptNm = 0, closestOmittedNm = 1e9;
        double minCulledHeadNm = 1e9; long culledSamples = 0;   // the well-powered geometric margin (see below)
        int cSteps = 3000, cSample = 25;
        long cw0 = System.currentTimeMillis();
        for (int t = 0; t < cSteps; t++) {
            ExplicitCompleteMatHarness.stepGlidingCPU(e, t, SEED, cp);
            if (t > 0 && t % 500 == 0) {
                double ms = (System.currentTimeMillis() - cw0) / (double) t;
                System.out.printf(Locale.US, "      ... step %d/%d  %.1f ms/step  active=%d  minCulledHeadDist=%.2f nm%n",
                        t, cSteps, ms, cp.lastActive, minCulledHeadNm);
            }
            if (t % cSample != 0) continue;
            // (b) THE WELL-POWERED TEST. The literal requirement below counts only fully accepted candidates,
            // which are rare. The geometric necessary condition is far better powered: gate g0 needs the live
            // head point xF8 within 3 nm of a real site, so if NO culled motor's xF8 ever comes near the
            // filament at all, no culled motor can possibly have been a candidate. Every culled motor is
            // measured, every sampled step.
            for (int m = 0; m < e.N; m++) {
                if (e.active.get(m) == 1) continue;
                culledSamples++;
                double fx = e.outGeom.get(6*e.N+m), fy = e.outGeom.get(7*e.N+m), fz = e.outGeom.get(8*e.N+m);
                minCulledHeadNm = Math.min(minCulledHeadNm, pointToFilNm(G, fx, fy, fz));
            }
            // brute: same gate, every motor active, on the SAME live state
            ChiralSiteSystem.siteGateA(bruteActive, e.noBind, G.mot.boundSeg, G.mot.nucleotideState, e.outGeom,
                    G.fil.coord, G.fil.uVec, G.fil.yVec, G.fil.segLength, e.segCumArc, e.sbP,
                    bruteCand, bruteArc, bruteAzim, e.exCounts);
            for (int m = 0; m < e.N; m++) {
                if (bruteCand.get(m) < 0) continue;
                accepted++;
                double dNm = siteToFilNm(G, m);
                if (e.active.get(m) != 1) { omitted++; closestOmittedNm = Math.min(closestOmittedNm, dNm); }
                else worstKeptNm = Math.max(worstKeptNm, dNm);
            }
        }
        double g0Nm = e.sbP.get(0);   // the g0 acceptance radius, in nm, exactly as siteGateA reads it
        boolean cOk = omitted == 0 && minCulledHeadNm > g0Nm;
        System.out.printf(Locale.US, "  C  brute-accepted candidates %d over %d sampled steps ; OMITTED by cull = %d  ⇒ %s%n",
                accepted, cSteps / cSample, omitted, cOk ? "PASS" : "FAIL");
        System.out.printf(Locale.US, "      geometric margin: over %d culled motor-samples the CLOSEST live head (xF8) came %.2f nm"
                + " from the filament ; the g0 acceptance radius is %.2f nm ⇒ margin x%.1f%n",
                culledSamples, minCulledHeadNm, g0Nm, minCulledHeadNm / Math.max(1e-9, g0Nm));
        System.out.printf(Locale.US, "      queryR = %.1f nm ; worst KEPT accepted-candidate site distance = %.2f nm (headroom %.2f nm)%n",
                G.queryR * 1e3, worstKeptNm, G.queryR * 1e3 - worstKeptNm);
        System.out.printf(Locale.US, "      mean active set = %.1f / %d motors (%.2f %%)%n",
                cp.meanActive(), e.N, 100.0 * cp.meanActive() / e.N);
        log.append("## GATE C — cull completeness (the Phase-3 hard requirement)\n\n")
           .append(String.format(Locale.US,
                   "Protocol: the production scene (%d motors, %.0f/µm², %.1f×%.1f µm) stepped %d steps with the cull ON;\n"
                 + "every %d steps the SAME `ChiralSiteSystem.siteGateA` is re-evaluated with ALL motors active (brute) on the\n"
                 + "identical live state, and every motor whose candidate the brute gate ACCEPTS is checked against the cull.\n\n"
                 + "- brute-accepted candidates: **%d**\n- accepted-but-CULLED: **%d**\n- queryR = **%.1f nm**"
                 + " (= G4_QUERYR 30 nm + free S2 length 40 nm + 10 nm margin, the value `buildS2Mat` itself sets)\n"
                 + "- worst site→filament distance among KEPT accepted candidates: **%.2f nm** (headroom to queryR: %.2f nm)\n"
                 + "- mean active set: **%.1f / %d** motors (%.2f %%)\n\n**%s**\n\n",
                   e.N, DENS, MX, MY, cSteps, cSample, accepted, omitted, G.queryR * 1e3,
                   worstKeptNm, G.queryR * 1e3 - worstKeptNm, cp.meanActive(), e.N, 100.0 * cp.meanActive() / e.N,
                   cOk ? "PASS — zero brute-accepted candidates are omitted by culling." : "FAIL"));
        log.append(String.format(Locale.US,
                "**Geometric margin (the well-powered form of the same requirement).** Gate `g0` cannot fire unless the LIVE\n"
              + "head point `xF8` is within %.2f nm of a real site. Over **%d culled motor-samples** the closest any culled\n"
              + "motor's `xF8` came to the filament was **%.2f nm** — a factor **%.1f** clear of the acceptance radius. So no\n"
              + "culled motor could have been a candidate, independently of how many candidates happened to be accepted.\n\n",
                g0Nm, culledSamples, minCulledHeadNm, minCulledHeadNm / Math.max(1e-9, g0Nm)));
        if (omitted > 0)
            log.append(String.format(Locale.US, "Closest omitted candidate sat %.2f nm from the filament — queryR must be enlarged past that.\n\n", closestOmittedNm));
        cp.shutdown();
        ok &= cOk;

        // ---- GATE W: worker striping must be BIT-IDENTICAL to the single-worker culled step ---------------
        System.out.println("  W  worker striping bit-identity ...");
        var G1 = scene(SEED + 7, 1000.0, 3.0, 1.0); var e1 = ExplicitCompleteMatHarness.packExMat(G1, 1);
        var G8 = scene(SEED + 7, 1000.0, 3.0, 1.0); var e8 = ExplicitCompleteMatHarness.packExMat(G8, 1);
        var cp1 = new ExplicitCompleteMatHarness.MatCullPlan(e1, 1);
        var cp8 = new ExplicitCompleteMatHarness.MatCullPlan(e8, 8);
        int wSteps = 1500;
        for (int t = 0; t < wSteps; t++) {
            ExplicitCompleteMatHarness.stepGlidingCPU(e1, t, SEED + 7, cp1);
            ExplicitCompleteMatHarness.stepGlidingCPU(e8, t, SEED + 7, cp8);
        }
        double dq = maxAbsDiff(e1.q, e8.q), dn = maxAbsDiff(e1.nodes, e8.nodes), dc = maxAbsDiff(e1.chiHead, e8.chiHead);
        double df = maxAbsDiff(G1.fil.coord, G8.fil.coord);
        int dbs = 0; for (int m = 0; m < e1.N; m++) if (G1.mot.boundSeg.get(m) != G8.mot.boundSeg.get(m)) dbs++;
        boolean wOk = dq == 0 && dn == 0 && dc == 0 && df == 0 && dbs == 0;
        System.out.printf(Locale.US, "  W  1 worker vs 8 workers over %d steps: max|dq|=%.3g max|dnodes|=%.3g max|dchi|=%.3g max|dfil|=%.3g boundSeg mismatches=%d ⇒ %s%n",
                wSteps, dq, dn, dc, df, dbs, wOk ? "PASS (bit-identical)" : "FAIL");
        log.append(String.format(Locale.US,
                "## GATE W — worker striping is arithmetic-neutral\n\n"
              + "%d steps of the SAME culled scene, 1 worker vs %d workers: max|Δq| = %.3g, max|Δnodes| = %.3g,\n"
              + "max|Δchi| = %.3g, max|Δfilament coord| = %.3g, boundSeg mismatches = %d.\n\n**%s**\n\n",
                wSteps, 8, dq, dn, dc, df, dbs, wOk ? "PASS — BIT-IDENTICAL." : "FAIL"));
        cp1.shutdown(); cp8.shutdown();
        ok &= wOk;

        // ---- GATE A: culled vs ALL-ACTIVE control (affordable at low density; -gate-a, it costs ~25 min) --
        if (!GATE_A) { write("gates.md", "# Site-normal long gliding assay — pre-run gates\n\n" + log);
                       System.out.printf(Locale.US, "%n  GATES: %s  (gate A skipped — run with -gate-a)%n", ok ? "PASS" : "FAIL");
                       return ok; }
        System.out.println("  A  culled vs all-active (no-cull) control at 400/µm² on a 3×1 lawn ...");
        int aSteps = 6000;
        double[] rc = arm(SEED + 11, 400.0, 3.0, 1.0, aSteps, true, 4);
        double[] ra = arm(SEED + 11, 400.0, 3.0, 1.0, aSteps, false, 1);
        System.out.printf(Locale.US, "  A  culled : avgBound %.4f  captures %.0f  bound-steps %.0f  netFwd %.5f um%n", rc[0], rc[1], rc[2], rc[3]);
        System.out.printf(Locale.US, "  A  no-cull: avgBound %.4f  captures %.0f  bound-steps %.0f  netFwd %.5f um%n", ra[0], ra[1], ra[2], ra[3]);
        log.append(String.format(Locale.US,
                "## GATE A — culled vs ALL-ACTIVE control\n\n"
              + "The cull freezes a far motor's beam/angles while it waits. This control runs the SAME seed/scene\n"
              + "(400 heads/µm², 3×1 µm, %d steps) with the cull ON and with every motor solved every step.\n\n"
              + "| arm | avgBound | captures | bound-steps | net forward (µm) |\n|---|---:|---:|---:|---:|\n"
              + "| culled | %.4f | %.0f | %.0f | %.5f |\n| all-active | %.4f | %.0f | %.0f | %.5f |\n\n"
              + "These are CHAOTIC many-body trajectories: they are NOT expected to be bit-identical, and this is an\n"
              + "order-of-magnitude / regime check, not a bit gate. It is reported, not gated.\n\n",
                aSteps, rc[0], rc[1], rc[2], rc[3], ra[0], ra[1], ra[2], ra[3]));

        write("gates.md", "# Site-normal long gliding assay — pre-run gates\n\n" + log);
        System.out.printf(Locale.US, "%n  GATES: %s%n", ok ? "PASS" : "FAIL");
        return ok;
    }

    /** {avgBound, captures, boundSteps, netForwardUm} for a short arm. */
    static double[] arm(int seed, double dens, double mx, double my, int steps, boolean cull, int workers) {
        var G = scene(seed, dens, mx, my);
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        var cp = cull ? new ExplicitCompleteMatHarness.MatCullPlan(e, workers) : null;
        double[] c0 = centroid(G);
        long boundSteps = 0, captures = 0;
        int[] prev = new int[e.N];
        for (int m = 0; m < e.N; m++) prev[m] = G.mot.boundSeg.get(m);
        for (int t = 0; t < steps; t++) {
            ExplicitCompleteMatHarness.stepGlidingCPU(e, t, seed, cp);
            for (int m = 0; m < e.N; m++) {
                int bs = G.mot.boundSeg.get(m);
                if (bs >= 0) boundSteps++;
                if (bs >= 0 && prev[m] < 0) captures++;
                prev[m] = bs;
            }
        }
        double[] c1 = centroid(G);
        if (cp != null) cp.shutdown();
        return new double[]{ (double) boundSteps / steps, captures, boundSteps,
                -( (c1[0]-c0[0])*G.bhat[0] + (c1[1]-c0[1])*G.bhat[1] + (c1[2]-c0[2])*G.bhat[2] ) };
    }

    // ============================================================================================ THE RUN
    static void runLong() throws IOException {
        long wall0 = System.currentTimeMillis();
        var G = scene(SEED, DENS, MX, MY);
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        int N = e.N, nSeg = e.nSeg;
        var cp = new ExplicitCompleteMatHarness.MatCullPlan(e, WORKERS);

        // -------- PHASE 5: the expected pointed-leading direction, established from the BUILT scene --------
        // barbed = end2 = +uVec (audited convention); a free filament glides POINTED-first, so the expected
        // translation direction is -u_fil(0). dxForward is the signed projection onto THAT fixed axis.
        double[] fwd = { -G.bhat[0], -G.bhat[1], -G.bhat[2] };
        double[] c0 = centroid(G);
        double[] pol0 = polarity(G);                       // initial end1->end2 unit vector (barbed direction)
        System.out.printf(Locale.US, "  SCENE: N=%d motors, nSeg=%d, beam M=%d, queryR=%.1f nm, contour=%.4f um%n",
                N, nSeg, e.M, G.queryR * 1e3, contour(G));
        System.out.printf(Locale.US, "  mat x in [%.3f, %.3f], y in [%.3f, %.3f] um ; filament centroid0 = (%.4f, %.4f, %.4f)%n",
                G.matXlo, G.matXhi, G.matYlo, G.matYhi, c0[0], c0[1], c0[2]);
        System.out.printf(Locale.US, "  polarity: barbed(end2) = (%.3f,%.3f,%.3f) ; EXPECTED FORWARD (pointed-leading) = (%.3f,%.3f,%.3f)%n",
                pol0[0], pol0[1], pol0[2], fwd[0], fwd[1], fwd[2]);
        System.out.printf(Locale.US, "  lawn margin at +/-%.3f um of translation: %.3f um%n",
                TARGET_UM, 0.5 * MX - 0.5 * contour(G) - TARGET_UM);
        TornadoExecutionPlan gpuPlan = null;
        if (GPU_MODE) {
            ExplicitCompleteMatHarness.SITE_NORMAL_DEVICE_OK = true;   // experimental opt-in; see the gate
            gpuPlan = ExplicitCompleteMatHarness.buildGlidingGraph(e, false);
            System.out.printf(Locale.US,
                    "  RUNNER: **GPU DEVICE-RESIDENT** site-normal graph (chi-dynamic head wired), %d motors, cull "
                  + (ExplicitCompleteMatHarness.DEVICE_CULL ? "ON (device matCull + matCullTag)" : "OFF (whole mat every step)") + ".%n"
                  + "          EXPERIMENTAL PATH — the bound-branch CPU/GPU gate is not green; treat trajectories "
                  + "as a device smoke/visualisation, not a measurement.%n%n", N);
        } else
        System.out.printf(Locale.US, "  RUNNER: CPU sequential runner, %d S2 workers, cull ON (mean active ~%.0f motors)%n%n",
                WORKERS, DENS * (2 * G.queryR * contour(G) + Math.PI * G.queryR * G.queryR));

        Files.createDirectories(Path.of(OUT));
        String vizDir = VIZ_DIR != null ? VIZ_DIR : OUT + "/simviewer/longrun";
        if (VIZ) Files.createDirectories(Path.of(vizDir));
        writeRunConfig(G, e, c0, fwd, pol0);
        writeCoverage("coverage_t0.tsv", G, e);

        StringBuilder csv = new StringBuilder(
                "step\tt_s\twall_s\tfwd_um\tdisp_um\tcx\tcy\tcz\tvfit_um_s\tvwin_um_s\tavgBound\tnbNow\tmaxNb\t"
              + "f0\tf1\tf2p\tf5p\tcaptures\tdetach\tstrokes\tdetachAtp\tfaxMean_pN\tfaxPos_pN\tfaxNeg_pN\t"
              + "azLo\tazSide\tazUp\tdistinctSites\tangDeg\tzmin\tzmax\tcontour_um\te2e_um\tbendDeg\tactive\tinvalid\tsolverFail\trollRad\trollTurns\tyMargin_um\n");
        Files.writeString(Path.of(OUT, "trajectory_summary.csv"), csv.toString());

        // -------- accumulators ------------------------------------------------------------------------
        long boundStepsAcc = 0, captures = 0, detach = 0, strokes = 0, detachAtp = 0, detachRigor = 0, detachOther = 0;
        long[] nbHist = new long[3];            // 0 bound / 1 bound / >=2 bound
        long nb5 = 0; int maxNb = 0;
        long[] nucOcc = new long[4];
        long[] azOcc = new long[3];             // lower / side / upper
        double faxAcc = 0, faxPos = 0, faxNeg = 0; long faxN = 0, nPos = 0, nNeg = 0;
        // STATE-RESOLVED axial force (read-only diagnostic, 2026-09-12). Separates a BINDING-GEOMETRY strain
        // from STROKE AIMING as the source of an alpha-dependent velocity gain: strain stored when the head
        // binds is already pushing in the PRE-stroke state (nuc = ADP.Pi, index 2), whereas the stroke can only
        // act AFTER the ADP.Pi->ADP transition (index 3). Indexed by nucleotideState.
        double[] faxByNuc = new double[4]; long[] nByNuc = new long[4];
        // HEAD-AXIS DIAGNOSTIC: d = xHeadHat . eup, binned over [-1,1]. d>0 = head axis points AWAY from the
        // mat (binds the NEAR/lower face); d<0 = points toward the mat (binds the FAR/upper face), because
        // the bound head sits 3.5 nm outward along the site normal with xHeadHat ~ -n_site.
        final int HB = 20; long[] haxBound = new long[HB], haxFree = new long[HB];
        double haxSumB = 0, haxSumF = 0; long haxNB = 0, haxNF = 0; final int HAX_STRIDE = 100;
        // FORCE-BALANCE AUDIT (2026-09-13). Two channels the census already had the ingredients for:
        //  (1) netAcc: the TIME-AVERAGE of the per-step SUM of cross-bridge axial force on the filament. In a
        //      steady glide this must equal gamma_par * v -- the test that the alpha-effect is a real force
        //      balance and not a bookkeeping artefact. NOTE this is NOT faxMean*avgBound: the per-sample mean
        //      weights long-lived drag states and is not the time-mean of the instantaneous sum.
        //  (2) fmagByNuc: |F| per state, so the axial FRACTION (axial/|F|) separates the two "why" hypotheses --
        //      a BETTER-AIMED stroke (same |F|, larger axial share) vs MORE STRAIN (larger |F|).
        double netAcc = 0; long netSteps = 0;
        double[] fmagByNuc = new double[4];
        double phiAcc = 0, phiSq = 0, psiAcc = 0, psiSq = 0; long convN = 0;
        double footAcc = 0, footSq = 0; long footN = 0, footAligned = 0;   // -convdiag: bound-state converter angles
        long residSteps = 0, residN = 0;
        double pathLen = 0, curvFwd = 0;        // centroid path length ; cumulative LIVE-axis forward displacement
        double sT = 0, sT2 = 0, sX = 0, sTX = 0; long sN = 0;   // full-run LS on (t, fwd)
        int invalid = 0, solverFail = 0;
        double zmin = 1e9, zmax = -1e9, maxFaxPn = 0, worstForcePn = 0;
        // ---- TWIRL: body-fixed axial roll of the filament ------------------------------------------------
        // angleDeg() is atan2 on the POLARITY vector = yaw in the xy-plane; it says nothing about rotation
        // about the filament's OWN axis. Twirl needs the material frame, so accumulate the per-step
        // rotation-minimizing-frame roll increment (ChiralSiteHarness.rollIncrementTransported: transports the
        // previous yVec onto the plane perp to the CURRENT u and takes the signed angle about u, so a pure
        // tumble contributes zero to first order and what remains is the body-fixed spin tau.u dt / gamma_roll).
        // Averaged over segments = the filament's net twist. Reported as turns and as turns per um of travel,
        // the metric the viscosity/mirror twirling work uses.
        double rollAcc = 0; double[][] prevY = new double[nSeg][3];
        for (int s2 = 0; s2 < nSeg; s2++) ChiralSiteHarness.seedPrevY(G.fil, s2, prevY[s2]);
        // force-excursion telemetry
        long fx12 = 0, fx50 = 0, fx200 = 0, fxEpisodes = 0; boolean inExcursion = false;
        double fxPeak = 0; int fxPeakStep = -1, fxPeakMotor = -1, fxPeakNb = -1, fxPeakAge = -1, fxPeakNuc = -1, fxPeakSeg = -1;
        float fxPeakArc = Float.NaN;
        int forceStrikes = 0;
        int[] prevBs = new int[N], age = new int[N], prevNu = new int[N];
        for (int m = 0; m < N; m++) { prevBs[m] = G.mot.boundSeg.get(m); prevNu[m] = G.mot.nucleotideState.get(m); age[m] = -1; }
        double[] cPrev = c0.clone();
        ArrayDeque<double[]> win = new ArrayDeque<>();     // rolling (t, fwd) for the windowed velocity
        List<double[]> milestones = new ArrayList<>();
        double[] MS = { 0.25, 0.50, 1.00, 1.50, 2.00 }; boolean[] msHit = new boolean[MS.length];
        StringBuilder msLog = new StringBuilder("milestone_um\tstep\tt_s\tfwd_um\tvwin_um_s\tnbNow\tavgBound\tfaxSum_pN\tangDeg\tboundSites\n");

        // Phase 15 rolling hi-res buffer (near-field-only frames, cheap)
        ArrayDeque<String> ring = new ArrayDeque<>();
        int hiResPending = 0; String hiResDir = null; int hiResIdx = 0; int hiResDone = 0;

        int t0 = 0;
        if (RESUME) { t0 = readState(G, e); System.out.printf(Locale.US, "  RESUMED at step %d%n", t0); }

        String stop = "MAX_STEPS";
        int vizFrames = 0;
        double fwd_um = 0;
        int t = t0;
        for (; t < STEPS; t++) {
            if (GPU_MODE) {
                e.matc.set(0, t); e.matc.set(1, SEED);
                G.mot.setCounts(t, SEED, nSeg); G.fil.counts.set(1, t); G.fil.counts.set(2, SEED);
                gpuPlan.execute();
                if ((t & 1023) == 0) drainPlanResults(gpuPlan);
                // TornadoExecutionPlan.execute() UNCONDITIONALLY builds a trace String
                // (getTraceExecutionPlan -> GridScheduler.toString) and appends the result to its internal
                // planResults List, which is never drained. Over millions of executes that exhausts the heap:
                // the 2026-08-22 campaign died with OutOfMemoryError in GridScheduler.toString at ~3.5M steps.
                // withoutProfiler() does NOT help (the string is built regardless of profiler mode); only
                // clearProfiles() empties the list. Every other GPU harness here already does this.
                gpuPlan.clearProfiles();
            } else
            ExplicitCompleteMatHarness.stepGlidingCPU(e, t, SEED, cp);

            double[] c = centroid(G);
            fwd_um = (c[0]-c0[0])*fwd[0] + (c[1]-c0[1])*fwd[1] + (c[2]-c0[2])*fwd[2];
            double dx = c[0]-cPrev[0], dy = c[1]-cPrev[1], dz = c[2]-cPrev[2];
            pathLen += Math.sqrt(dx*dx + dy*dy + dz*dz);
            double[] pl = polarity(G);
            curvFwd += -(dx*pl[0] + dy*pl[1] + dz*pl[2]);      // instantaneous forward along the LIVE polarity
            cPrev = c;

            // ---- per-step motor census -------------------------------------------------------------
            int nb = 0; double faxSum = 0, faxP = 0, faxN2 = 0, maxFstep = 0; int maxFm = -1;
            java.util.HashSet<Integer> sites = new java.util.HashSet<>();
            for (int m = 0; m < N; m++) {
                int bs = G.mot.boundSeg.get(m), nu = G.mot.nucleotideState.get(m);
                if (bs >= 0 && prevBs[m] < 0) captures++;
                if (bs < 0 && prevBs[m] >= 0) {
                    detach++;
                    if (age[m] >= 0) { residSteps += age[m]; residN++; }
                    if (nu == MotorStore.NUC_ATP) detachAtp++;
                    else if (nu == MotorStore.NUC_NONE) detachRigor++; else detachOther++;
                }
                if (bs >= 0 && prevBs[m] == bs && prevNu[m] == MotorStore.NUC_ADPPI && nu == MotorStore.NUC_ADP) strokes++;
                age[m] = bs < 0 ? -1 : (prevBs[m] == bs ? age[m] + 1 : 0);
                if (HEADAXIS_CLI && (t % HAX_STRIDE) == 0) {
                    double ax = e.outGeom.get(9*N+m), ay = e.outGeom.get(10*N+m), az = e.outGeom.get(11*N+m);
                    double al = Math.sqrt(ax*ax + ay*ay + az*az);
                    if (al > 0.5) {   // filters culled/uninitialised slots, whose outGeom is stale or zero
                        double dd = (ax*G.eup[0] + ay*G.eup[1] + az*G.eup[2]) / al;
                        int bin = (int) ((dd + 1.0) * 0.5 * HB); if (bin < 0) bin = 0; if (bin >= HB) bin = HB-1;
                        if (bs >= 0) { haxBound[bin]++; haxSumB += dd; haxNB++; }
                        else         { haxFree[bin]++;  haxSumF += dd; haxNF++; }
                    }
                }
                prevBs[m] = bs; prevNu[m] = nu;
                if (bs < 0) continue;
                nb++;
                if (nu >= 0 && nu < 4) nucOcc[nu]++;
                if (ExplicitCompleteMatHarness.CONV_DIAG) {
                    int nMot = e.exCounts.get(0);
                    double ph = e.q.get(m), ps = e.q.get(nMot + m);
                    phiAcc += ph; phiSq += ph*ph; psiAcc += ps; psiSq += ps*ps; convN++;

                    // FOOT-AXIS AUDIT: is the line joining the two two-point feet (head yVec) aligned with the
                    // actin site chord pA-pB it is supposed to span? If motYVec is an arbitrary lab-derived
                    // perpendicular rather than a material head axis, this angle is broadly distributed.
                    int nB3 = G.mot.body.coord.getSize() / 3;
                    int hh = 3 * m + 2;
                    double fyx = G.mot.body.yVec.get(hh), fyy = G.mot.body.yVec.get(nB3 + hh), fyz = G.mot.body.yVec.get(2 * nB3 + hh);
                    double sux2 = G.fil.uVec.get(bs), suy2 = G.fil.uVec.get(nSeg + bs), suz2 = G.fil.uVec.get(2 * nSeg + bs);
                    double syx2 = G.fil.yVec.get(bs), syy2 = G.fil.yVec.get(nSeg + bs), syz2 = G.fil.yVec.get(2 * nSeg + bs);
                    double szx2 = suy2*syz2 - suz2*syy2, szy2 = suz2*syx2 - sux2*syz2, szz2 = sux2*syy2 - suy2*syx2;
                    double szl2 = Math.sqrt(szx2*szx2 + szy2*szy2 + szz2*szz2);
                    if (szl2 > 1e-30) { szx2/=szl2; szy2/=szl2; szz2/=szl2; }
                    double tpm2 = ExplicitCompleteMatHarness.TWO_POINT_PAIR * (ExplicitCompleteMatHarness.TWIST_PER_MON_DEG * Math.PI / 180.0);
                    double azA2 = G.mot.bindAzim.get(m), azB2 = azA2 - tpm2;
                    double dAx = ExplicitCompleteMatHarness.TWO_POINT_PAIR * Constants.actinMonoRadius * sux2
                               + Constants.radius * ((Math.cos(azA2)-Math.cos(azB2))*syx2 + (Math.sin(azA2)-Math.sin(azB2))*szx2);
                    double dAy = ExplicitCompleteMatHarness.TWO_POINT_PAIR * Constants.actinMonoRadius * suy2
                               + Constants.radius * ((Math.cos(azA2)-Math.cos(azB2))*syy2 + (Math.sin(azA2)-Math.sin(azB2))*szy2);
                    double dAz = ExplicitCompleteMatHarness.TWO_POINT_PAIR * Constants.actinMonoRadius * suz2
                               + Constants.radius * ((Math.cos(azA2)-Math.cos(azB2))*syz2 + (Math.sin(azA2)-Math.sin(azB2))*szz2);
                    double dl = Math.sqrt(dAx*dAx + dAy*dAy + dAz*dAz);
                    if (dl > 1e-30) {
                        double cosang = Math.abs((fyx*dAx + fyy*dAy + fyz*dAz) / dl);
                        if (cosang > 1) cosang = 1;
                        double ang = Math.acos(cosang) * 180.0 / Math.PI;   // 0 = foot axis spans the chord
                        footAcc += ang; footSq += ang*ang; footN++;
                        if (ang < 20.0) footAligned++;
                    }
                }
                sites.add(e.bindSite.get(m));
                int d = m * 13;
                double fax = G.bondData.get(d+6)*G.fil.uVec.get(bs)
                           + G.bondData.get(d+7)*G.fil.uVec.get(nSeg+bs)
                           + G.bondData.get(d+8)*G.fil.uVec.get(2*nSeg+bs);
                faxSum += fax; faxAcc += fax; faxN++;
                int nuState = G.mot.nucleotideState.get(m);
                if (nuState >= 0 && nuState < 4) { faxByNuc[nuState] += fax; nByNuc[nuState]++; }
                if (nuState >= 0 && nuState < 4)
                    fmagByNuc[nuState] += Math.sqrt(sq(G.bondData.get(d+6)) + sq(G.bondData.get(d+7)) + sq(G.bondData.get(d+8)));
                if (fax > 0) { faxPos += fax; nPos++; faxP += fax; } else if (fax < 0) { faxNeg += fax; nNeg++; faxN2 += fax; }
                maxFaxPn = Math.max(maxFaxPn, Math.abs(fax) * 1e12);
                double fmag = Math.sqrt(sq(G.bondData.get(d+6)) + sq(G.bondData.get(d+7)) + sq(G.bondData.get(d+8))) * 1e12;
                if (fmag > maxFstep) { maxFstep = fmag; maxFm = m; }
                azOcc[azClass(G, m, bs, nSeg)]++;
            }
            boundStepsAcc += nb; maxNb = Math.max(maxNb, nb);
            netAcc += faxSum; netSteps++;      // per-step SUM => time-average is the true net motor force
            nbHist[Math.min(nb, 2)]++; if (nb >= 5) nb5++;

            double rollStep = 0;
            for (int s = 0; s < nSeg; s++) rollStep += ChiralSiteHarness.rollIncrementTransported(G.fil, s, prevY[s]);
            rollAcc += rollStep / nSeg;
            for (int s = 0; s < nSeg; s++) { double z = G.fil.coord.get(2*nSeg+s); zmin = Math.min(zmin, z); zmax = Math.max(zmax, z); }

            // ---- PHASE 9 health: finiteness is NOT enough ---------------------------------------------
            // The η = 0.01 / dt = 2.5e-6 failure (§ report) stayed finite the whole way while a single bond
            // grew from 6 pN to 134 pN by period-2 explicit overshoot and threw the filament 16 µm out of a
            // chamber that is nanometres deep. Both of those are watched directly.
            // ---- FORCE-EXCURSION TELEMETRY (H1 numerical overshoot vs H2 rare physical high-strain) --------
            // The pre-existing `worstForcePn` only updates ABOVE FORCE_ABSURD_PN and `forceStrikes` resets on
            // any step below it, so a transient excursion leaves no trace of when, how often, or which motor.
            // This records the distribution and the argmax so the two hypotheses can be separated.
            if (maxFstep > 12.0)  fx12++;
            if (maxFstep > 50.0)  fx50++;
            if (maxFstep > 200.0) { fx200++; if (!inExcursion) { fxEpisodes++; inExcursion = true; } }
            else inExcursion = false;
            if (maxFstep > fxPeak) {
                fxPeak = maxFstep; fxPeakStep = t; fxPeakMotor = maxFm; fxPeakNb = nb;
                fxPeakAge = (maxFm >= 0) ? age[maxFm] : -1;
                fxPeakNuc = (maxFm >= 0) ? G.mot.nucleotideState.get(maxFm) : -1;
                fxPeakSeg = (maxFm >= 0) ? G.mot.boundSeg.get(maxFm) : -1;
                fxPeakArc = (maxFm >= 0) ? G.mot.bindArc.get(maxFm) : Float.NaN;
            }
            if (maxFstep > FORCE_ABSURD_PN) { forceStrikes++; if (maxFstep > worstForcePn) worstForcePn = maxFstep; }
            else forceStrikes = 0;
            if (forceStrikes >= FORCE_STRIKES) {
                stop = "CATASTROPHIC_FORCE";
                System.out.printf(Locale.US, "%n  *** STOP: |bond force| > %.0f pN on %d consecutive steps (peak %.1f pN) at step %d ***%n",
                        FORCE_ABSURD_PN, forceStrikes, worstForcePn, t);
                break;
            }
            if (Math.abs(c[2]) > Z_ESCAPE_UM || zmax - zmin > 2 * Z_ESCAPE_UM) {
                stop = "Z_ESCAPE";
                System.out.printf(Locale.US, "%n  *** STOP: filament left the lawn plane (cz=%.4f um, z span %.4f um) at step %d ***%n",
                        c[2], zmax - zmin, t);
                break;
            }
            if (t > t0 + 1000 && (c[0] < G.matXlo - 0.2 || c[0] > G.matXhi + 0.2
                                || c[1] < G.matYlo - 0.6 || c[1] > G.matYhi + 0.6)) {
                stop = "LEFT_LAWN";
                System.out.printf(Locale.US, "%n  *** STOP: filament centroid left the lawn footprint (%.4f, %.4f) at step %d ***%n",
                        c[0], c[1], t);
                break;
            }
            if ((t & 255) == 0) {
                boolean fin = true;
                for (int i = 0; i < 3*nSeg; i++) if (!Float.isFinite(G.fil.coord.get(i))) fin = false;
                for (int i = 0; i < 6; i++) if (!Double.isFinite(e.redOut.get(i))) { fin = false; solverFail++; }
                if (!fin) { invalid++; if (invalid > 3) { stop = "NUMERICAL_FAILURE"; break; } }
            }

            // ---- LS accumulators + rolling window ----------------------------------------------------
            if (t % 20 == 0) {
                double ts = t * DT;
                sT += ts; sT2 += ts*ts; sX += fwd_um; sTX += ts*fwd_um; sN++;
                win.addLast(new double[]{ ts, fwd_um });
                while (!win.isEmpty() && ts - win.peekFirst()[0] > 0.050) win.pollFirst();   // 50 ms window
            }

            // ---- milestones --------------------------------------------------------------------------
            for (int k = 0; k < MS.length; k++) if (!msHit[k] && fwd_um >= MS[k]) {
                msHit[k] = true;
                double vw = slope(win);
                msLog.append(String.format(Locale.US, "%.2f\t%d\t%.6f\t%.5f\t%.4f\t%d\t%.4f\t%.4f\t%.3f\t%d%n",
                        MS[k], t, t*DT, fwd_um, vw, nb, (double) boundStepsAcc/(t-t0+1), faxSum*1e12, angleDeg(G), sites.size()));
                milestones.add(new double[]{ MS[k], t, t*DT, fwd_um, vw, nb });
                writeMilestoneSnapshot(G, e, MS[k], t, fwd_um, nb, faxSum);
                write("milestones.tsv", msLog.toString());      // durable: written AT the milestone, not at the end
                System.out.printf(Locale.US, "  *** MILESTONE +%.2f um at step %d (t=%.4f s), v_win=%.3f um/s, bound=%d ***%n",
                        MS[k], t, t*DT, vw, nb);
                if (VIZ && (MS[k] == 1.00 || MS[k] == 2.00) && hiResPending == 0) {
                    hiResDir = OUT + String.format(Locale.US, "/simviewer/hires_%.0fum", MS[k]*100);
                    try { Files.createDirectories(Path.of(hiResDir)); } catch (IOException ignored) { }
                    hiResIdx = 0;
                    for (String s : ring) { writeFrame(hiResDir, hiResIdx++, s); }
                    hiResPending = HIRES_HALF; hiResDone = 0;
                }
            }

            // ---- viewer ------------------------------------------------------------------------------
            if (VIZ) {
                boolean hires = hiResPending > 0;
                if (hires) {
                    writeFrame(hiResDir, hiResIdx++, frameJson(G, e, t*DT, true));
                    hiResPending--; hiResDone++;
                    if (hiResPending == 0) System.out.printf(Locale.US, "      hi-res window written: %d frames -> %s%n", hiResIdx, hiResDir);
                }
                // Rolling 1-frame-per-step pre-trigger buffer, so a milestone can dump the HIRES_HALF steps
                // BEFORE it fired. It calls frameJson EVERY step, which measured at ~half the total step cost
                // on the device path (40 vs 87 steps/s). A run that will not reach the 1.00/2.00 um milestones
                // pays that for nothing — hence -nohires.
                if (!hires && !NO_HIRES) {
                    ring.addLast(frameJson(G, e, t*DT, true));
                    while (ring.size() > HIRES_HALF) ring.pollFirst();
                }
                if (t % VIZ_STRIDE == 0) { writeFrame(vizDir, vizFrames++, frameJson(G, e, t*DT, false)); }
            }

            // ---- checkpoint --------------------------------------------------------------------------
            if ((t % CKPT_EVERY == 0 || (t - t0 < 20_000 && t % 2_000 == 0)) && t > t0) {
                if (ExplicitCompleteMatHarness.CONV_DIAG && convN > 0) {
            double mp = phiAcc/convN, mq = psiAcc/convN;
            double sp = Math.sqrt(Math.max(0, phiSq/convN - mp*mp)), sq2 = Math.sqrt(Math.max(0, psiSq/convN - mq*mq));
            double R2D = 180.0/Math.PI;
            System.out.printf(Locale.US,
                "[CONVDIAG] boundSamples=%d  phi=%.2f+/-%.2f deg  psi=%.2f+/-%.2f deg  theta=psi-phi=%.2f deg%n",
                convN, mp*R2D, sp*R2D, mq*R2D, sq2*R2D, (mq-mp)*R2D);
            if (footN > 0) {
                double mf = footAcc/footN, sf = Math.sqrt(Math.max(0, footSq/footN - mf*mf));
                System.out.printf(Locale.US, "[FOOTAXIS] n=%d  angle(footAxis, siteChord) = %.2f +/- %.2f deg   within20deg = %.1f%%   (0=spans chord, 90=perpendicular, ~57=random)%n",
                    footN, mf, sf, 100.0*footAligned/footN);
            }
        }
        double wall = (System.currentTimeMillis() - wall0) / 1000.0;
                double vfit = lsSlope(sN, sT, sT2, sX, sTX), vwin = slope(win);
                double avgB = (double) boundStepsAcc / (t - t0 + 1);
                String row = String.format(Locale.US,
                        "%d\t%.6f\t%.1f\t%.5f\t%.5f\t%.5f\t%.5f\t%.5f\t%.4f\t%.4f\t%.4f\t%d\t%d\t"
                      + "%.4f\t%.4f\t%.4f\t%.4f\t%d\t%d\t%d\t%d\t%.4f\t%.4f\t%.4f\t"
                      + "%d\t%d\t%d\t%d\t%.2f\t%.5f\t%.5f\t%.5f\t%.5f\t%.2f\t%d\t%d\t%d\t%.6f\t%.6f\t%.5f%n",
                        t, t*DT, wall, fwd_um, dist(c, c0), c[0], c[1], c[2], vfit, vwin, avgB, nb, maxNb,
                        (double) nbHist[0]/(t-t0+1), (double) nbHist[1]/(t-t0+1), (double) nbHist[2]/(t-t0+1), (double) nb5/(t-t0+1),
                        captures, detach, strokes, detachAtp,
                        faxN > 0 ? faxAcc/faxN*1e12 : 0, faxPos*1e12/Math.max(1,t-t0+1), faxNeg*1e12/Math.max(1,t-t0+1),
                        azOcc[0], azOcc[1], azOcc[2], sites.size(), angleDeg(G), zmin, zmax, contour(G), e2e(G), bendDeg(G),
                        cp.lastActive, invalid, solverFail, rollAcc, rollAcc/(2*Math.PI), yMargin(G, c));
                Files.writeString(Path.of(OUT, "trajectory_summary.csv"), row,
                        java.nio.file.StandardOpenOption.APPEND);
                writeProgress(t, t0, wall, fwd_um, dist(c, c0), vfit, vwin, avgB, nb, maxNb, captures, detach,
                        strokes, detachAtp, faxN > 0 ? faxAcc/faxN*1e12 : 0, zmin, zmax, invalid, solverFail, cp, stop, "RUNNING");
                System.out.printf(Locale.US,
                        "  step %7d  t=%.4f s  wall=%.0f s  forward=%+.4f um  |d|=%.4f  v_fit=%+.3f  v_win=%+.3f um/s  "
                      + "avgBound=%.3f (now %d, max %d)  cap=%d  det=%d  strokes=%d  active=%d  inv=%d/solv=%d  "
                      + "%.0f ms/step  ang=%.1f deg roll=%+.4f turns e2e=%.3f bend=%.1f z=[%.4f,%.4f]%n",
                        t, t*DT, wall, fwd_um, dist(c, c0), vfit, vwin, avgB, nb, maxNb, captures, detach, strokes,
                        cp.lastActive, invalid, solverFail, 1000.0*wall/(t-t0), angleDeg(G), rollAcc/(2*Math.PI), e2e(G), bendDeg(G), zmin, zmax);
            }
            if (t % STATE_EVERY == 0 && t > t0) writeState(G, e, t);

            // ---- PHASE 5 stop rule -------------------------------------------------------------------
            if (fwd_um >= TARGET_UM) { stop = "TARGET_REACHED"; break; }
            if (fwd_um <= -TARGET_UM) { stop = "POLARITY_REVERSAL"; break; }
        }

        double wall = (System.currentTimeMillis() - wall0) / 1000.0;
        int nSteps = t - t0 + 1;
        double vfit = lsSlope(sN, sT, sT2, sX, sTX);
        double[] c = centroid(G);
        writeState(G, e, t);
        Files.writeString(Path.of(OUT, "milestones.tsv"), msLog.toString());

        // -------- PHASE 16: mat coverage / per-segment candidate counts --------------------------------
        writeCoverage("coverage_final.tsv", G, e);

        writeProgress(t, t0, wall, fwd_um, dist(c, c0), vfit, slope(win), (double) boundStepsAcc/nSteps,
                0, maxNb, captures, detach, strokes, detachAtp, faxN > 0 ? faxAcc/faxN*1e12 : 0,
                zmin, zmax, invalid, solverFail, cp, stop, "FINISHED");

        // -------- summary ------------------------------------------------------------------------------
        String cls = classify(stop, fwd_um, vfit, (double) boundStepsAcc/nSteps, nbHist, nSteps, invalid, solverFail);
        StringBuilder sum = new StringBuilder();
        sum.append(String.format(Locale.US,
                "STOP=%s  steps=%d  t=%.4f s  wall=%.0f s%n"
              + "net forward = %+.5f um   |centroid displacement| = %.5f um   path length = %.4f um%n"
              + "live-axis cumulative forward = %+.5f um%n"
              + "full-run LS velocity = %+.4f um/s%n"
              + "avgBound = %.4f  max simultaneous = %d   captures = %d  detachments = %d  strokes = %d%n"
              + "P(0 bound)=%.4f P(1)=%.4f P(>=2)=%.4f P(>=5)=%.4f%n"
              + "mean axial force per bound head = %+.4f pN  (pos %d / neg %d samples)%n"
              + "azimuth occupancy lower/side/upper = %d / %d / %d%n"
              + "nucleotide occupancy (bound) NONE/ATP/ADPPi/ADP = %d / %d / %d / %d%n"
              + "mean residence = %.1f steps (%.2f us)   detach cause ATP/rigor/other = %d / %d / %d%n"
              + "z range = [%.5f, %.5f] um   contour = %.5f um   end-to-end = %.5f um   mean bend = %.2f deg%n"
              + "TWIRL: net roll = %+.4f rad (%+.4f turns)  Omega = %+.4f rad/s  turns/um = %+.4f%n"
              + "force excursions: steps >12pN = %d, >50pN = %d, >200pN = %d in %d episodes (of %d steps)%n"
              + "  peak %.1f pN at step %d, motor %d, seg %d, arc %.4f um, boundCount %d, bondAge %d steps, nuc %d%n"
              + "invalid = %d  solverFail = %d  max |axial force| = %.2f pN  peak |bond force| = %.2f pN%n"
              + "CLASS = %s%n",
                stop, nSteps, t*DT, wall, fwd_um, dist(c, c0), pathLen, curvFwd, vfit,
                (double) boundStepsAcc/nSteps, maxNb, captures, detach, strokes,
                (double) nbHist[0]/nSteps, (double) nbHist[1]/nSteps, (double) nbHist[2]/nSteps, (double) nb5/nSteps,
                faxN > 0 ? faxAcc/faxN*1e12 : 0, nPos, nNeg, azOcc[0], azOcc[1], azOcc[2],
                nucOcc[0], nucOcc[1], nucOcc[2], nucOcc[3],
                residN > 0 ? (double) residSteps/residN : 0, residN > 0 ? (double) residSteps/residN*DT*1e6 : 0,
                detachAtp, detachRigor, detachOther,
                zmin, zmax, contour(G), e2e(G), bendDeg(G),
                rollAcc, rollAcc/(2*Math.PI), rollAcc/Math.max(1e-12, nSteps*DT),
                (rollAcc/(2*Math.PI))/(Math.abs(fwd_um) > 1e-9 ? Math.abs(fwd_um) : Double.NaN),
                fx12, fx50, fx200, fxEpisodes, nSteps,
                fxPeak, fxPeakStep, fxPeakMotor, fxPeakSeg, fxPeakArc, fxPeakNb, fxPeakAge, fxPeakNuc,
                invalid, solverFail, maxFaxPn, worstForcePn, cls));
        System.out.printf(Locale.US,
            "  axial force by nucleotide state (bound): PRE-stroke ADP.Pi %+.4f pN (n=%d) | POST-stroke ADP %+.4f pN (n=%d)"
          + " | NONE %+.4f pN (n=%d)%n"
          + "    => a binding-geometry strain shows up PRE-stroke; stroke aiming can only show up POST-stroke.%n",
            nByNuc[2] > 0 ? faxByNuc[2]/nByNuc[2]*1e12 : 0.0, nByNuc[2],
            nByNuc[3] > 0 ? faxByNuc[3]/nByNuc[3]*1e12 : 0.0, nByNuc[3],
            nByNuc[0] > 0 ? faxByNuc[0]/nByNuc[0]*1e12 : 0.0, nByNuc[0]);
        if (HEADAXIS_CLI && (haxNB + haxNF) > 0) {
            System.out.printf(Locale.US,
                "  HEAD-AXIS DIAGNOSTIC  d = xHeadHat.eup   (d<0 => axis points TOWARD the mat => binds the FAR/upper face)%n"
              + "    BOUND   mean d = %+.4f  (n=%d)%n    UNBOUND mean d = %+.4f  (n=%d)%n",
                haxNB > 0 ? haxSumB/haxNB : 0.0, haxNB, haxNF > 0 ? haxSumF/haxNF : 0.0, haxNF);
            StringBuilder hb = new StringBuilder("    bin(d):");
            for (int k = 0; k < HB; k++) hb.append(String.format(Locale.US, " %+.2f", -1.0 + (k + 0.5) * 2.0 / HB));
            System.out.println(hb);
            StringBuilder b1 = new StringBuilder("    bound %:"), b2 = new StringBuilder("    free  %:");
            for (int k = 0; k < HB; k++) {
                b1.append(String.format(Locale.US, " %5.1f", haxNB > 0 ? 100.0*haxBound[k]/haxNB : 0.0));
                b2.append(String.format(Locale.US, " %5.1f", haxNF > 0 ? 100.0*haxFree[k]/haxNF : 0.0));
            }
            System.out.println(b1); System.out.println(b2);
            System.out.println("    => if BOUND is skewed but UNBOUND is not, the capture GATE selects the axis;");
            System.out.println("       if UNBOUND is already skewed, the resting S2/lever conformation supplies it.");
        }
        {   // ---- FORCE BALANCE: time-mean net motor force vs gamma_par * v ----
            double netPn = netSteps > 0 ? netAcc/netSteps*1e12 : 0.0;          // + = toward BARBED = backward
            double Lf = contour(G)*1e-6, rf = 3.5e-9;
            double gammaPar = 2*Math.PI*ETA*Lf/Math.log(Lf/(2*rf));
            double vPredict = -netPn*1e-12/gammaPar*1e6;                       // um/s, forward positive
            System.out.printf(Locale.US,
                "  FORCE BALANCE: time-mean NET cross-bridge axial force = %+.4f pN (+ = backward)%n"
              + "    gamma_par = %.3e N.s/m  =>  predicted v = %+.3f um/s   vs measured %+.3f um/s   ratio %.2f%n"
              + "    (steady state REQUIRES these to agree; a mismatch means the glide is not force-balanced%n"
              + "     over this window -- either not steady, or not yet resolved.)%n",
                netPn, gammaPar, vPredict, vfit, (vfit != 0 ? vPredict/vfit : Double.NaN));
            System.out.printf(Locale.US,
                "  FORCE DECOMPOSITION by state: |F| pN / axial pN / axial fraction%n"
              + "    PRE-stroke ADP.Pi  %7.4f %8.4f %8.3f%n"
              + "    POST-stroke ADP    %7.4f %8.4f %8.3f%n"
              + "    NONE               %7.4f %8.4f %8.3f%n"
              + "    (a BETTER-AIMED stroke raises the axial FRACTION at similar |F|; MORE STRAIN raises |F|.)%n",
                nByNuc[2]>0 ? fmagByNuc[2]/nByNuc[2]*1e12 : 0.0, nByNuc[2]>0 ? faxByNuc[2]/nByNuc[2]*1e12 : 0.0,
                nByNuc[2]>0 && fmagByNuc[2]>0 ? faxByNuc[2]/fmagByNuc[2] : 0.0,
                nByNuc[3]>0 ? fmagByNuc[3]/nByNuc[3]*1e12 : 0.0, nByNuc[3]>0 ? faxByNuc[3]/nByNuc[3]*1e12 : 0.0,
                nByNuc[3]>0 && fmagByNuc[3]>0 ? faxByNuc[3]/fmagByNuc[3] : 0.0,
                nByNuc[0]>0 ? fmagByNuc[0]/nByNuc[0]*1e12 : 0.0, nByNuc[0]>0 ? faxByNuc[0]/nByNuc[0]*1e12 : 0.0,
                nByNuc[0]>0 && fmagByNuc[0]>0 ? faxByNuc[0]/fmagByNuc[0] : 0.0);
        }
        Files.writeString(Path.of(OUT, "summary.txt"), sum.toString());
        System.out.println();
        System.out.println("================================================================================");
        System.out.print(sum);
        System.out.printf(Locale.US, "viewer frames: %d (stride %d) -> %s%n", vizFrames, VIZ_STRIDE, vizDir);
        System.out.println("================================================================================");
        cp.shutdown();
    }

    static String classify(String stop, double fwd, double vfit, double avgB, long[] nbHist, int n,
                           int invalid, int solverFail) {
        if (invalid > 3 || solverFail > 3) return "G — NUMERICAL FAILURE";
        if (stop.equals("CATASTROPHIC_FORCE") || stop.equals("Z_ESCAPE") || stop.equals("NUMERICAL_FAILURE"))
            return "G — NUMERICAL FAILURE (" + stop + ")";
        if (stop.equals("LEFT_LAWN")) return "G — the filament left the lawn footprint; the assay cannot be interpreted";
        if (stop.equals("TARGET_REACHED")) return "A — STRONG GLIDE (+2.000 um reached in the expected polarity)";
        if (stop.equals("POLARITY_REVERSAL")) return "D — REVERSED (-2.000 um reached)";
        double zeroFrac = (double) nbHist[0] / n;
        if (avgB < 0.2 && zeroFrac > 0.9) return "F — RECRUITMENT-LIMITED (avgBound << 1, zero-bound intervals dominate)";
        if (fwd > 0.25 && vfit > 0) return "B — WEAK GLIDE (positive drift, did not reach 2 um in the horizon)";
        if (Math.abs(fwd) < 0.25) return avgB > 1.0
                ? "E — NONMOTILE / STALLED (adequate attachment, no sustained translation)"
                : "C — UNRESOLVED (net displacement comparable to Brownian wandering)";
        return "C — UNRESOLVED";
    }

    // ===================================================================================== small helpers
    static double[] centroid(TwoBodyConverterMotor.Glide2D G) {
        int n = G.nSeg; double x = 0, y = 0, z = 0;
        for (int s = 0; s < n; s++) { x += G.fil.coordX(s); y += G.fil.coordY(s); z += G.fil.coordZ(s); }
        return new double[]{ x/n, y/n, z/n };
    }
    static double[] polarity(TwoBodyConverterMotor.Glide2D G) {
        int n = G.nSeg; var f = G.fil;
        double h0 = 0.5*f.segLength.get(0), h1 = 0.5*f.segLength.get(n-1);
        double x = (f.coordX(n-1)+h1*f.uVecX(n-1)) - (f.coordX(0)-h0*f.uVecX(0));
        double y = (f.coordY(n-1)+h1*f.uVecY(n-1)) - (f.coordY(0)-h0*f.uVecY(0));
        double z = (f.coordZ(n-1)+h1*f.uVecZ(n-1)) - (f.coordZ(0)-h0*f.uVecZ(0));
        double L = Math.sqrt(x*x+y*y+z*z); if (L < 1e-12) return new double[]{1,0,0};
        return new double[]{ x/L, y/L, z/L };
    }
    static double contour(TwoBodyConverterMotor.Glide2D G) {
        double c = 0; for (int s = 0; s < G.nSeg; s++) c += G.fil.segLength.get(s); return c; }
    static double e2e(TwoBodyConverterMotor.Glide2D G) {
        int n = G.nSeg; var f = G.fil; double h0 = 0.5*f.segLength.get(0), h1 = 0.5*f.segLength.get(n-1);
        double x = (f.coordX(n-1)+h1*f.uVecX(n-1)) - (f.coordX(0)-h0*f.uVecX(0));
        double y = (f.coordY(n-1)+h1*f.uVecY(n-1)) - (f.coordY(0)-h0*f.uVecY(0));
        double z = (f.coordZ(n-1)+h1*f.uVecZ(n-1)) - (f.coordZ(0)-h0*f.uVecZ(0));
        return Math.sqrt(x*x+y*y+z*z);
    }
    static double bendDeg(TwoBodyConverterMotor.Glide2D G) {
        var f = G.fil; int n = G.nSeg; if (n < 2) return 0; double b = 0;
        for (int s = 0; s < n-1; s++) {
            double d = f.uVecX(s)*f.uVecX(s+1) + f.uVecY(s)*f.uVecY(s+1) + f.uVecZ(s)*f.uVecZ(s+1);
            b += Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, d))));
        }
        return b/(n-1);
    }
    static double angleDeg(TwoBodyConverterMotor.Glide2D G) {
        double[] p = polarity(G); return Math.toDegrees(Math.atan2(p[1], p[0])); }
    /**
     * Drain TornadoExecutionPlan.planResults — the unbounded-growth defect behind the 2026-08-22
     * OutOfMemoryError (GridScheduler.toString, ~3.5M steps into a 12 GB heap).
     *
     * execute() unconditionally builds a trace String and appends a TornadoExecutionResult to a PRIVATE
     * List that NO public API drains: clearProfiles() only reaches TornadoExecutor and measurably left
     * ~2.3 KB/step of growth (verified, 705->937 MB over 100k steps), and withoutProfiler() does nothing
     * because the trace is built regardless of profiler mode. Reflection is the only lever from outside
     * the API. Failures are swallowed deliberately: this is a memory hygiene measure, and a TornadoVM
     * version that renames or fixes the field must not break the physics run.
     */
    private static java.lang.reflect.Field PLAN_RESULTS;
    private static boolean planResultsUnavailable = false;
    static void drainPlanResults(TornadoExecutionPlan plan) {
        if (planResultsUnavailable) return;
        try {
            if (PLAN_RESULTS == null) {
                PLAN_RESULTS = TornadoExecutionPlan.class.getDeclaredField("planResults");
                PLAN_RESULTS.setAccessible(true);
            }
            Object v = PLAN_RESULTS.get(plan);
            if (v instanceof java.util.List<?> l) l.clear(); else planResultsUnavailable = true;
        } catch (Throwable ex) {
            planResultsUnavailable = true;
            System.err.println("  NOTE: cannot drain planResults (" + ex + ") - long GPU runs may OOM.");
        }
    }
    /**
     * Signed clearance, in um, between the filament's most off-axis point and the motor lawn's y edge.
     *
     * NEGATIVE means part of the filament has wandered OFF the motor strip and is binding nothing, which
     * silently mimics a decaying glide. This bit us on 2026-08-22: -maty 0.5 was chosen because binding
     * reach is only ~0.183 um, but that assumed the filament stays at y=0. It does not -- it diffuses
     * laterally AND yaws, and a few degrees of yaw throws a 2.1 um filament's ends ~0.16 um off axis.
     * Capture rate collapsed 1.01 -> 0.41 /ms exactly when the ends crossed the edge. Size MAT_Y from the
     * MEASURED wander (sqrt(2*D_y*T) + yaw), never from binding reach.
     */
    static boolean yWarned = false;
    static double yMargin(TwoBodyConverterMotor.Glide2D G, double[] c) {
        double yaw = Math.toRadians(angleDeg(G));
        double endOff = Math.abs(c[1]) + 0.5 * e2e(G) * Math.abs(Math.sin(yaw));
        double margin = 0.5 * MY - endOff;
        if (margin < 0 && !yWarned) {
            yWarned = true;
            System.err.printf(Locale.US, "  WARNING: filament has reached the lawn y-edge (margin %.3f um, "
                + "maty=%.2f). Binding will decay because motors are ABSENT, not because the motor is failing. "
                + "Widen -maty.%n", margin, MY);
        }
        return margin;
    }
    static double dist(double[] a, double[] b) {
        return Math.sqrt(sq(a[0]-b[0]) + sq(a[1]-b[1]) + sq(a[2]-b[2])); }
    static double sq(double v) { return v*v; }
    /** helical azimuth class of motor m's bound site: 0 = lower, 1 = side, 2 = upper (against +eup). */
    static int azClass(TwoBodyConverterMotor.Glide2D G, int m, int bs, int nSeg) {
        var f = G.fil; double a = G.mot.bindAzim.get(m), ca = Math.cos(a), sa = Math.sin(a);
        double ux = f.uVec.get(bs), uy = f.uVec.get(nSeg+bs), uz = f.uVec.get(2*nSeg+bs);
        double yx = f.yVec.get(bs), yy = f.yVec.get(nSeg+bs), yz = f.yVec.get(2*nSeg+bs);
        double zx = uy*yz-uz*yy, zy = uz*yx-ux*yz, zz = ux*yy-uy*yx;
        double nx = ca*yx + sa*zx, ny = ca*yy + sa*zy, nz = ca*yz + sa*zz;
        double d = nx*G.eup[0] + ny*G.eup[1] + nz*G.eup[2];
        return d > 0.5 ? 2 : (d < -0.5 ? 0 : 1);
    }
    /** shortest distance (nm) from an arbitrary point to the live filament axis (clamped to each segment). */
    static double pointToFilNm(TwoBodyConverterMotor.Glide2D G, double px, double py, double pz) {
        var f = G.fil; double best = 1e9;
        for (int s = 0; s < G.nSeg; s++) {
            double half = 0.5*f.segLength.get(s);
            double dx = px - f.coordX(s), dy = py - f.coordY(s), dz = pz - f.coordZ(s);
            double ux = f.uVecX(s), uy = f.uVecY(s), uz = f.uVecZ(s);
            double foot = dx*ux + dy*uy + dz*uz;
            foot = foot < -half ? -half : (foot > half ? half : foot);
            double qx = dx - foot*ux, qy = dy - foot*uy, qz = dz - foot*uz;
            best = Math.min(best, Math.sqrt(qx*qx + qy*qy + qz*qz));
        }
        return best * 1e3;
    }
    static double siteToFilNm(TwoBodyConverterMotor.Glide2D G, int m) {
        double best = 1e9;
        for (int s = 0; s < G.nSeg; s++) best = Math.min(best, Math.sqrt(TwoBodyConverterMotor.siteSegDist2(G, m, s)));
        return best * 1e3;
    }
    static void writeCoverage(String rel, TwoBodyConverterMotor.Glide2D G, ExplicitCompleteMatHarness.ExMat e) {
        int[] per = perSegCandidates(G, e);
        StringBuilder cov = new StringBuilder("seg\tcandidates\tcx\tcy\tcz\n");
        for (int s = 0; s < G.nSeg; s++)
            cov.append(String.format(Locale.US, "%d\t%d\t%.5f\t%.5f\t%.5f%n", s, per[s],
                    G.fil.coordX(s), G.fil.coordY(s), G.fil.coordZ(s)));
        write(rel, cov.toString());
    }
    static int[] perSegCandidates(TwoBodyConverterMotor.Glide2D G, ExplicitCompleteMatHarness.ExMat e) {
        int[] per = new int[G.nSeg]; double R2 = G.queryR * G.queryR;
        for (int m = 0; m < G.N; m++) for (int s = 0; s < G.nSeg; s++)
            if (TwoBodyConverterMotor.siteSegDist2(G, m, s) <= R2) per[s]++;
        return per;
    }
    static double maxAbsDiff(DoubleArray a, DoubleArray b) {
        double d = 0; for (int i = 0; i < a.getSize(); i++) d = Math.max(d, Math.abs(a.get(i) - b.get(i))); return d; }
    static double maxAbsDiff(FloatArray a, FloatArray b) {
        double d = 0; for (int i = 0; i < a.getSize(); i++) d = Math.max(d, Math.abs(a.get(i) - b.get(i))); return d; }
    static double lsSlope(long n, double sT, double sT2, double sX, double sTX) {
        if (n < 3) return 0; double den = n*sT2 - sT*sT; return Math.abs(den) < 1e-30 ? 0 : (n*sTX - sT*sX)/den; }
    static double slope(ArrayDeque<double[]> w) {
        if (w.size() < 3) return 0; double sT=0,sT2=0,sX=0,sTX=0; long n=0;
        for (double[] p : w) { sT+=p[0]; sT2+=p[0]*p[0]; sX+=p[1]; sTX+=p[0]*p[1]; n++; }
        return lsSlope(n, sT, sT2, sX, sTX);
    }

    // ============================================================================ persistence / reporting
    static void writeRunConfig(TwoBodyConverterMotor.Glide2D G, ExplicitCompleteMatHarness.ExMat e,
                               double[] c0, double[] fwd, double[] pol0) {
        String j = String.format(Locale.US,
                "{\n  \"assay\": \"site-normal long gliding\",\n  \"runner\": \"CPU sequential (site-normal has no validated device path)\",\n"
              + "  \"seed\": %d,\n  \"max_steps\": %d,\n  \"dt_s\": %.6e,\n  \"eta_Pa_s\": %.6g,\n"
              + "  \"mat_x_um\": %.4f,\n  \"mat_y_um\": %.4f,\n  \"density_heads_per_um2\": %.1f,\n  \"n_motors\": %d,\n"
              + "  \"fil_segments\": %d,\n  \"contour_um\": %.6f,\n  \"beam_nodes_M\": %d,\n  \"queryR_nm\": %.3f,\n"
              + "  \"cull\": \"per-segment UNION (MatSoaSlice.matCull)\",\n  \"s2_workers\": %d,\n"
              + "  \"site_normal_bind\": true,\n  \"head_tilt_3d\": true,\n  \"g6_retired\": true,\n  \"g2_retired\": true,\n"
              + "  \"site_lattice\": \"" + (ExplicitCompleteMatHarness.TWO_STRAND_SITES ? "every4+partner(two-strand)" : "every4")
              + "\",\n  \"rand_base_azimuth\": " + (RANDBASE ? "true" : "false")
              + ",\n  \"rand_base_seed\": " + (RANDBASE ? String.valueOf(ExplicitCompleteMatHarness.RAND_BASE_SEED) : "null")
              + ",\n  \"detached_rest\": \"" + (STRAIGHTREST ? "straight-stick restC=(1,0,0)" : "native phi_pre/psiActin/chi0")
              + "\",\n  \"binding_skew_deg\": 0.0,\n"
              + String.format(Locale.US, "  \"stroke_skew_deg\": %.4f,\n  \"mirror_sign\": %+.1f,\n", STROKE_SKEW_DEG, MIRROR)
              + String.format(Locale.US, "  \"conv_skew_deg\": %.4f,\n  \"conv_skew_ramp\": \"%s\",\n",
                              CONV_SKEW_DEG, CONV_SKEW_DEG != 0.0 ? "linear" : "off")
              + String.format(Locale.US, "  \"registry_switch_deg\": %.4f,\n  \"registry_k_Nm_per_rad\": %.6e,\n",
                              REG_SWITCH_DEG, REG_K_NM)
              + String.format(Locale.US, "  \"s2_catch_factor\": %.4f,\n  \"s2_loadcatch_factor\": %.4f,\n  \"s2_loadcatch_F0_pN\": %.4f,\n  \"two_point_bond\": %s,\n  \"two_point_foot_nm\": %.4f,\n", S2_CATCH, S2_LOADCATCH, S2_LOADF0_PN, TWO_POINT ? "true" : "false", TWO_POINT_FOOT_NM)
              + "  \"steric\": false,\n"
              + "  \"z_support\": \"matZConfine (z-slab off)\",\n  \"filament_brownian\": true,\n  \"motor_brownian\": true,\n"
              + "  \"capture_tol_deg\": 25.0,\n  \"target_um\": %.3f,\n"
              + "  \"centroid0\": [%.6f, %.6f, %.6f],\n  \"barbed_dir0\": [%.6f, %.6f, %.6f],\n"
              + "  \"expected_forward_dir\": [%.6f, %.6f, %.6f]\n}\n",
                SEED, STEPS, DT, ETA, MX, MY, DENS, G.N, FIL_SEGS, contour(G), e.M, G.queryR*1e3, WORKERS,
                TARGET_UM, c0[0], c0[1], c0[2], pol0[0], pol0[1], pol0[2], fwd[0], fwd[1], fwd[2]);
        write("run_config.json", j);
    }

    static void writeProgress(int t, int t0, double wall, double fwd, double disp, double vfit, double vwin,
                              double avgB, int nb, int maxNb, long cap, long det, long strokes, long detAtp,
                              double faxPn, double zmin, double zmax, int invalid, int solverFail,
                              ExplicitCompleteMatHarness.MatCullPlan cp, String stop, String state) {
        write("progress.json", String.format(Locale.US,
                "{\n  \"state\": \"%s\",\n  \"stop_reason\": \"%s\",\n  \"wall_s\": %.1f,\n  \"step\": %d,\n"
              + "  \"steps_done\": %d,\n  \"sim_time_s\": %.6f,\n  \"forward_um\": %.6f,\n  \"abs_displacement_um\": %.6f,\n"
              + "  \"v_fit_um_s\": %.5f,\n  \"v_window_um_s\": %.5f,\n  \"avgBound\": %.5f,\n  \"bound_now\": %d,\n"
              + "  \"max_simultaneous_bound\": %d,\n  \"captures\": %d,\n  \"detachments\": %d,\n  \"strokes\": %d,\n"
              + "  \"detach_atp\": %d,\n  \"mean_axial_force_pN\": %.5f,\n  \"z_min_um\": %.6f,\n  \"z_max_um\": %.6f,\n"
              + "  \"active_motors\": %d,\n  \"mean_active_motors\": %.1f,\n  \"invalid\": %d,\n  \"solver_failures\": %d,\n"
              + "  \"steps_per_s\": %.3f,\n  \"eta_to_2um_h\": %.2f\n}\n",
                state, stop, wall, t, t - t0, t*DT, fwd, disp, vfit, vwin, avgB, nb, maxNb, cap, det, strokes,
                detAtp, faxPn, zmin, zmax, cp.lastActive, cp.meanActive(), invalid, solverFail,
                wall > 0 ? (t - t0)/wall : 0,
                (vfit > 1e-6 && wall > 0 && t > t0) ? ((TARGET_UM - fwd)/vfit)/DT/((t - t0)/wall)/3600.0 : Double.NaN));
    }

    static void writeMilestoneSnapshot(TwoBodyConverterMotor.Glide2D G, ExplicitCompleteMatHarness.ExMat e,
                                       double ms, int t, double fwd, int nb, double faxSum) {
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.US, "# milestone %.2f um  step %d  t %.6f s  forward %.5f um  bound %d  faxSum %.4f pN%n",
                ms, t, t*DT, fwd, nb, faxSum*1e12));
        b.append(String.format(Locale.US, "# filament angle %.2f deg  contour %.5f  e2e %.5f  bend %.2f deg%n",
                angleDeg(G), contour(G), e2e(G), bendDeg(G)));
        int[] per = perSegCandidates(G, e);
        b.append("# per-segment cull-candidate counts:");
        for (int s = 0; s < e.nSeg; s++) b.append(' ').append(per[s]);
        b.append('\n');
        b.append("motor\tseg\tsite\tazim_deg\tazClass\tnuc\tfax_pN\n");
        for (int m = 0; m < e.N; m++) {
            int bs = G.mot.boundSeg.get(m); if (bs < 0) continue;
            int d = m*13;
            double fax = G.bondData.get(d+6)*G.fil.uVec.get(bs) + G.bondData.get(d+7)*G.fil.uVec.get(e.nSeg+bs)
                       + G.bondData.get(d+8)*G.fil.uVec.get(2*e.nSeg+bs);
            b.append(String.format(Locale.US, "%d\t%d\t%d\t%.2f\t%d\t%d\t%.5f%n", m, bs, e.bindSite.get(m),
                    Math.toDegrees(G.mot.bindAzim.get(m)), azClass(G, m, bs, e.nSeg), G.mot.nucleotideState.get(m), fax*1e12));
        }
        write(String.format(Locale.US, "milestone_%03.0fnm.tsv", ms*1000), b.toString());
    }

    /** Resumable state: everything the step reads that is not recomputed within the step. */
    static void writeState(TwoBodyConverterMotor.Glide2D G, ExplicitCompleteMatHarness.ExMat e, int t) {
        try {
            Path tmp = Path.of(OUT, "checkpoint.bin.tmp");
            try (var o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
                o.writeInt(t); o.writeInt(e.N); o.writeInt(e.nSeg); o.writeInt(e.M);
                putD(o, e.nodes); putD(o, e.q); putD(o, e.chiHead); putD(o, e.restC);
                putF(o, G.fil.coord); putF(o, G.fil.uVec); putF(o, G.fil.yVec);
                putI(o, G.mot.boundSeg); putI(o, G.mot.nucleotideState); putI(o, e.bindSite);
                putI(o, e.prevBound); putI(o, e.justBound); putI(o, e.prevNuc);
                putF(o, G.mot.bindArc); putF(o, G.mot.bindAzim); putF(o, G.mot.forceDotFil);
                putF(o, G.mot.forceDotAvg); putI(o, G.mot.avgInit); putI(o, G.mot.cooldown);
                putF(o, e.headRef); putF(o, e.headOmega); putF(o, e.headTau); putF(o, e.headMis);
            }
            Files.move(tmp, Path.of(OUT, "checkpoint.bin"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) { System.out.println("  [checkpoint write failed: " + ex + "]"); }
    }
    static int readState(TwoBodyConverterMotor.Glide2D G, ExplicitCompleteMatHarness.ExMat e) {
        try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(Path.of(OUT, "checkpoint.bin"))))) {
            int t = in.readInt(); in.readInt(); in.readInt(); in.readInt();
            getD(in, e.nodes); getD(in, e.q); getD(in, e.chiHead); getD(in, e.restC);
            getF(in, G.fil.coord); getF(in, G.fil.uVec); getF(in, G.fil.yVec);
            getI(in, G.mot.boundSeg); getI(in, G.mot.nucleotideState); getI(in, e.bindSite);
            getI(in, e.prevBound); getI(in, e.justBound); getI(in, e.prevNuc);
            getF(in, G.mot.bindArc); getF(in, G.mot.bindAzim); getF(in, G.mot.forceDotFil);
            getF(in, G.mot.forceDotAvg); getI(in, G.mot.avgInit); getI(in, G.mot.cooldown);
            getF(in, e.headRef); getF(in, e.headOmega); getF(in, e.headTau); getF(in, e.headMis);
            DerivedGeometrySystem.derive(G.fil.coord, G.fil.uVec, G.fil.yVec, G.fil.zVec, G.fil.end1, G.fil.end2,
                    G.fil.segLength, G.fil.counts);
            return t;
        } catch (IOException ex) { System.out.println("  [no checkpoint to resume: " + ex + "]"); return 0; }
    }
    static void putD(java.io.DataOutputStream o, DoubleArray a) throws IOException {
        o.writeInt(a.getSize()); for (int i = 0; i < a.getSize(); i++) o.writeDouble(a.get(i)); }
    static void putF(java.io.DataOutputStream o, FloatArray a) throws IOException {
        o.writeInt(a.getSize()); for (int i = 0; i < a.getSize(); i++) o.writeFloat(a.get(i)); }
    static void putI(java.io.DataOutputStream o, IntArray a) throws IOException {
        o.writeInt(a.getSize()); for (int i = 0; i < a.getSize(); i++) o.writeInt(a.get(i)); }
    static void getD(java.io.DataInputStream in, DoubleArray a) throws IOException {
        int n = in.readInt(); for (int i = 0; i < n; i++) { double v = in.readDouble(); if (i < a.getSize()) a.set(i, v); } }
    static void getF(java.io.DataInputStream in, FloatArray a) throws IOException {
        int n = in.readInt(); for (int i = 0; i < n; i++) { float v = in.readFloat(); if (i < a.getSize()) a.set(i, v); } }
    static void getI(java.io.DataInputStream in, IntArray a) throws IOException {
        int n = in.readInt(); for (int i = 0; i < n; i++) { int v = in.readInt(); if (i < a.getSize()) a.set(i, v); } }

    static void reportOnly() {
        try { System.out.println(Files.readString(Path.of(OUT, "summary.txt"))); }
        catch (IOException ex) { System.out.println("no summary.txt in " + OUT); }
    }

    static void write(String rel, String s) {
        try { Files.createDirectories(Path.of(OUT)); Files.writeString(Path.of(OUT, rel), s); }
        catch (IOException ex) { throw new RuntimeException(ex); }
    }
    static void writeFrame(String dir, int idx, String json) {
        try { Files.writeString(Path.of(dir, String.format(Locale.US, "frame_%06d.json", idx)), json); }
        catch (IOException ex) { throw new RuntimeException(ex); }
    }

    // ================================================================================== PHASE 14 viewer
    static double SHOW_R   = 0.030;   // full articulation radius from ANY live segment (µm) — union, no midpoint
    static int    POST_SUB = 24;      // far-field anchor-post subsampling (keeps the 7 µm lawn visible)

    /**
     * One canonical {@code sim_viewer_boa.html} frame. Articulation is chosen by the PER-SEGMENT UNION rule
     * ({@code site → ANY live segment ≤ SHOW_R}) plus every bound motor — never a filament-midpoint window, so the
     * 4D viewer defect cannot return. Articulated motors carry the FULL explicit S2 beam (all M elements), the
     * lever P→C, the head ellipsoid drawn along {@code xHeadHat} centred on the true head centre {@code xH}, and
     * the F8 point. Distant motors render as anchor posts (subsampled) so the lawn extent stays visible.
     */
    static String frameJson(TwoBodyConverterMotor.Glide2D G, ExplicitCompleteMatHarness.ExMat e, double t, boolean nearOnly) {
        var f = G.fil; int nSeg = G.nSeg, N = G.N, M = e.M, nodeStride = 3*(M+1);
        double Ract = ExplicitCompleteMatHarness.R_ACTIN_NM * 1e-3;
        StringBuilder b = new StringBuilder(1 << 19);
        b.append(String.format(Locale.US, "{\"frame\":0,\"t\":%.7g,\"bounds\":{\"xDim\":%.4g,\"yDim\":%.4g,\"zDim\":0.2},\"segments\":[",
                t, MX, MY));
        int sid = 0;
        for (int s = 0; s < nSeg; s++) {
            double h = 0.5*f.segLength.get(s);
            b.append(SingleMotorMovieHarness.seg(sid++,
                    new double[]{ f.coordX(s)-h*f.uVecX(s), f.coordY(s)-h*f.uVecY(s), f.coordZ(s)-h*f.uVecZ(s) },
                    new double[]{ f.coordX(s)+h*f.uVecX(s), f.coordY(s)+h*f.uVecY(s), f.coordZ(s)+h*f.uVecZ(s) },
                    Constants.radius, false, 1.00, sid > 1));
        }
        // sparse every4 lattice stubs — the real binding sites
        double rise = e.sbP.get(16), stepPhase = e.sbP.get(18) != 0.0 ? e.sbP.get(18) : e.sbP.get(17)*e.sbP.get(16);
        for (int s = 0; s < nSeg; s++) {
            double L = f.segLength.get(s), cum = e.segCumArc.get(s);
            int k0 = (int) (cum / rise);
            for (int k = k0; k*rise - cum <= L; k++) {
                double la = k*rise - cum; if (la < 0) continue;
                double tw = k*stepPhase, ph = tw - 2*Math.PI*Math.floor(tw/(2*Math.PI));
                double[] p = SingleMotorMovieHarness.sitePosNormal(G, nSeg, s, la, ph, Ract);
                b.append(SingleMotorMovieHarness.seg(sid++, new double[]{p[0],p[1],p[2]},
                        new double[]{p[0]+0.0008*p[3], p[1]+0.0008*p[4], p[2]+0.0008*p[5]}, 0.00025, true, 0.45, true));
            }
        }
        // per-motor union-cull articulation
        // SHOW_R < queryR, so the step's own cull set (e.active, a per-segment UNION at queryR, with every bound
        // motor forced in) is a strict SUPERSET of the articulation set — refine inside it instead of rescanning
        // all N motors, which keeps a per-timestep frame affordable.
        double R2 = SHOW_R*SHOW_R;
        int[] artic = new int[N]; int nArtic = 0;
        for (int m = 0; m < N; m++) {
            if (e.active.get(m) != 1) continue;
            boolean near = G.mot.boundSeg.get(m) >= 0;
            for (int s = 0; s < nSeg && !near; s++) if (TwoBodyConverterMotor.siteSegDist2(G, m, s) <= R2) near = true;
            if (near) artic[nArtic++] = m;
        }
        for (int i = 0; i < nArtic; i++) {   // the FULL explicit S2 beam of every articulated motor
            int m = artic[i];
            for (int j = 0; j < M; j++)
                b.append(SingleMotorMovieHarness.seg(sid++,
                        new double[]{ e.nodes.get((3*j)*N+m),     e.nodes.get((3*j+1)*N+m),     e.nodes.get((3*j+2)*N+m) },
                        new double[]{ e.nodes.get((3*(j+1))*N+m), e.nodes.get((3*(j+1)+1)*N+m), e.nodes.get((3*(j+1)+2)*N+m) },
                        0.0009, true, 0.55, true));
        }
        // ---- FLUORESCENT PROBES (viewer-only; sparse single-fluorophore labels) --------------------
        // Each probe is a MATERIAL point: fixed contour arc + fixed phase in the segment material frame
        // (uVec, yVec, u x y), so it ORBITS the axis as the filament rolls. `I` is a polarisation-like
        // intensity cos^2(lab azimuth about the glide axis) mimicking the dipole-modulated TIRF signal the
        // experiments actually record; `phase` is the lab-frame azimuth in degrees for plotting.
        String probesJson = "", probeMeta = "";
        if (PROBES > 0 && nSeg > 0) {
            StringBuilder pb = new StringBuilder(256);
            double total = 0; for (int s = 0; s < nSeg; s++) total += f.segLength.get(s);
            double ph0 = PROBE_PHASE_DEG * Math.PI / 180.0;
            for (int i = 0; i < PROBES; i++) {
                double target = total * (i + 0.5) / PROBES;
                double acc = 0; int seg = nSeg - 1; double la = 0;
                for (int s = 0; s < nSeg; s++) {
                    double L = f.segLength.get(s);
                    if (target <= acc + L || s == nSeg - 1) { seg = s; la = target - acc; break; }
                    acc += L;
                }
                if (la < 0) la = 0;
                double rOff = PROBE_RADIUS_UM > 0 ? PROBE_RADIUS_UM : Ract;
                double[] q = SingleMotorMovieHarness.sitePosNormal(G, nSeg, seg, la, ph0, rOff);
                // lab-frame azimuth of the radial offset, measured in the plane transverse to the glide axis (x)
                double azLab = Math.atan2(q[5], q[4]);
                double inten = Math.cos(azLab) * Math.cos(azLab);
                if (i > 0) pb.append(',');
                // `c` = the CENTRELINE point, `n` = the radial unit vector. Emitting both lets the VIEWER
                // place the label at c + r*n for ANY r at playback time, so one run serves a wide shot (offset
                // exaggerated for legibility) and a close-up (the true 3.5 nm) without re-simulating. `p` is
                // kept for backward compatibility with frames/readers that expect an absolute position.
                pb.append(String.format(Locale.US,
                        "{\"id\":%d,\"p\":[%.6f,%.6f,%.6f],\"c\":[%.6f,%.6f,%.6f],\"n\":[%.5f,%.5f,%.5f],"
                      + "\"I\":%.4f,\"phase\":%.2f}",
                        i, q[0], q[1], q[2],
                        q[0]-rOff*q[3], q[1]-rOff*q[4], q[2]-rOff*q[5],
                        q[3], q[4], q[5], inten, azLab * 180.0 / Math.PI));
            }
            probesJson = pb.toString();
            double rOffAll = PROBE_RADIUS_UM > 0 ? PROBE_RADIUS_UM : Ract;
            probeMeta = String.format(Locale.US, ",\"probeExag\":%.3f,\"probeROffset_um\":%.6f,\"actinRadius_um\":%.6f", rOffAll / Ract, rOffAll, Ract);
        }
        b.append("],\"myosins\":[");
        boolean first = true;
        for (int i = 0; i < nArtic; i++) {
            int m = artic[i]; int bs = G.mot.boundSeg.get(m); boolean bnd = bs >= 0;
            double Cx = e.outGeom.get(m), Cy = e.outGeom.get(N+m), Cz = e.outGeom.get(2*N+m);
            double hx = e.outGeom.get(3*N+m), hy = e.outGeom.get(4*N+m), hz = e.outGeom.get(5*N+m);
            double fx = e.outGeom.get(6*N+m), fy = e.outGeom.get(7*N+m), fz = e.outGeom.get(8*N+m);
            double ax = e.outGeom.get(9*N+m), ay = e.outGeom.get(10*N+m), az = e.outGeom.get(11*N+m);
            double Px = e.nodes.get((3*M)*N+m), Py = e.nodes.get((3*M+1)*N+m), Pz = e.nodes.get((3*M+2)*N+m);
            if (!first) b.append(','); first = false;
            b.append(String.format(Locale.US,
                    "{\"id\":%d,\"bound\":%s,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":0.0009},"
                  + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":0.0011},"
                  + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"state\":\"%s\"}}",
                    m, bnd ? "true" : "false",
                    Px, Py, Pz, Px, Py, Pz,                       // (the beam is drawn in `segments`)
                    Px, Py, Pz, Cx, Cy, Cz,                       // lever P -> C
                    // HEAD = SPHERE (2026-09-08). The head is ALREADY a sphere dynamically: its drag is
                    // 6*pi*eta*RHEAD_UM (5 nm) and A_SEMI never enters the drag or the bond. Drawing an
                    // ellipsoid advertised a long-axis orientation the mechanics never uses -- and one that is
                    // biologically wrong: in the real acto-myosin complex the motor domain's long axis lies
                    // roughly ALONG the filament with the converter barbed-proximal, whereas the kbind latch
                    // here holds the head radial ("sticking straight out"). It also disagreed with the drag
                    // body (drawn 4.5 nm semi-major vs 5 nm drag sphere vs 3.03 nm equal-volume).
                    // end1 == end2 makes the viewer render an isotropic sphere (its len<=1e-10 branch).
                    hx, hy, hz,
                    hx, hy, hz,
                    HEAD_DRAW_R, SingleMotorMovieHarness.nucName(G.mot.nucleotideState.get(m))));
            b.append(String.format(Locale.US,                     // the F8 point, at the filament-facing head tip
                    ",{\"id\":%d,\"bound\":false,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":0.0003},"
                  + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":0.0003},"
                  + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":0.0007,\"state\":\"%s\"}}",
                    N + m, hx, hy, hz, fx, fy, fz, hx, hy, hz, fx, fy, fz, fx, fy, fz, fx, fy, fz,
                    bnd ? "NONE" : "ATP"));
        }
        if (!nearOnly) {                                          // far-field anchor posts (subsampled)
            for (int m = 0; m < N; m += POST_SUB) {
                double[] A = G.A[m];
                if (!first) b.append(','); first = false;
                b.append(String.format(Locale.US,
                        "{\"id\":%d,\"bound\":false,\"rod\":{\"end1\":[%.5g,%.5g,-0.005],\"end2\":[%.5g,%.5g,-0.003],\"r\":0.0007},"
                      + "\"lever\":{\"end1\":[%.5g,%.5g,-0.003],\"end2\":[%.5g,%.5g,-0.002],\"r\":0.0007},"
                      + "\"motor\":{\"end1\":[%.5g,%.5g,-0.002],\"end2\":[%.5g,%.5g,-0.001],\"r\":0.0009,\"state\":\"anchor\"}}",
                        2*N + m, A[0], A[1], A[0], A[1], A[0], A[1], A[0], A[1], A[0], A[1], A[0], A[1]));
            }
        }
        b.append("]");
        if (!probesJson.isEmpty()) b.append(",\"probes\":[").append(probesJson).append("]").append(probeMeta);
        b.append(String.format(Locale.US,
                ",\"polarity\":{\"barbedEnd\":\"end2\",\"barbedDir\":[%.4g,%.4g,%.4g],\"glideAxis\":\"pointed-leading = -x\"}}",
                G.bhat[0], G.bhat[1], G.bhat[2]));
        return b.toString();
    }
}
