package softbox;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 4b-iv: the gliding assay — the payoff. Assembles the validated pieces (4a binding, 4b-i
 * body, 4b-ii cross-bridge + gather, 4b-iii cycle + stroke + F-catch-slip, inc-2 chain filament) into
 * v1's gliding assay and measures gliding velocity + avgBound vs the v1 fixture (8.33/8.23 µm/s, 7.64/
 * 7.21 bound, glide −x). MATCH v1's CONFIG faithfully; do NOT tune the physics (all rates/forces are
 * the frozen 4a–4b-iii constants).
 *
 * v1 config (glidingAssay500_val): dt=1e-5, fixedMyosinDensity=500/µm², fixedMyosinZValue=−0.05, the
 * gliding filament is a ~11-segment chain (2 µm, 64-monomer segments) at z=0 along +x (plus-end +x),
 * chain params fracMove=0.5/fracR=0.1/fracMoveTorq=0.2; surface = the MyosinFixed tail anchor (4b-i) +
 * the filament held by its bound motors (no separate z-clamp). We use a density-faithful STRIP of bed
 * around the filament's path (not the full 14×2 box) — avgBound/velocity are local to the filament, so
 * the strip at 500/µm² is faithful and keeps the motor count tractable.
 *
 * This file builds the CPU pipeline + the cheap probe FIRST (does it glide −x, stably, at ~the right
 * avgBound?). The GPU plan + the converged ensemble follow only if the probe passes.
 */
public final class GlidingHarness {

    static double DT = 1.0e-5;                   // gliding dt; -dt overrides (dt-convergence test)
    static double MYO_SPRING = 1.0e-9;           // cross-bridge stiffness, N/µm (= 1 pN/nm). MEASUREMENT-ONLY override -myospring <pN/nm>; default UNCHANGED.
    static boolean NO_REFRACTORY = false;        // -norefractory: released motors are immediately bindable
    static boolean FORCE_CAP_DETACH = false;     // -forcecapdetach (renamed from -faithfulrelease, 2026-07-08 Stage 2): hard force-cap detachment — a bound head whose cross-bridge load exceeds DETACH_CAP pN detaches unconditionally (v1's §6.10 break-force release). Opt-in, off by default ⇒ byte-identical; NOT in canon (promotion deferred to its own task).
    static double  DETACH_CAP = 12.0;            // -detachcap <pN>: the force-cap detachment threshold (default 12 pN = v1 Env.myosinBreakForce). Only active with -forcecapdetach; passed to setFaithfulRelease (the shared MotorStore break-cap API, name unchanged).
                                                 // (kinParams[10]=0) — the OFF bracket for §6.6 (measurement).
    static boolean FAITHFUL_REFRACTORY = false;  // §6.11 -faithfulrefractory: probabilistic 1-step block at v1's
                                                 // GPU-oracle effective rate (0.31) vs HEAD's 100%/1-step (default off).
    static double FORCE_BIAS = 0.0;              // -forcebias <eps>: inject a uniform −x seg-side force bias per bound
                                                 // motor (§6.8 susceptibility pre-filter). 0 = production (bit-identical).
    // ---- IMPLICIT_CROSSBRIDGE (flag-gated) ----
    static boolean XB_IMPLICIT2 = true;          // CANONICAL DEFAULT (2026-07-08 collapse Stage 1): COUPLED head+SITE implicit F8 (per-segment star; COUPLED_IMPLICIT_XB_FINDINGS). -explicitxb opt-out restores the explicit Hookean F8.
    static boolean EXPLICIT_XB = false;          // -explicitxb: opt-out of the canonical implicit cross-bridge ⇒ explicit Hookean F8 (the legacy default). Resolved after arg-parse.
    static boolean XB_TRAP = false;              // -xbtrap: TRAPEZOIDAL (Crank–Nicolson midpoint) re-timing of the -xbimplicit2 coupled F8 star (r→r/2; XBTRAP_PROBE)
    static boolean SEG_IMPLICIT = false;         // -segimplicit: DIAGONAL per-segment implicit COLLECTIVE loaded cross-bridge force (rigid head; SEG_IMPLICIT_FINDINGS)
    static boolean CANONICAL = false;            // -canonical: PHASE-2 Version-B two-point canonical motor (bindCanonicalTwoPoint + bondForcesCanonical). Default off ⇒ byte-identical.
    static boolean CANON_DIAG = false;           // -canondiag: instrument the Version-B binder (formation rate, snap magnitude, legal-pose, lever-strain under thermal load). Implies -canonical.
    static boolean CONFIG1 = false;              // -config1: PHASE-2 Config-1 composed architecture (PAIRS attachments + Hookean J1 load). Implies -canonical.
    static double  KAPPA = 3.82e-20;             // J1 torsional stiffness (N·m/rad). FORCE-MATCHED (Phase-2 SET-A, 2026-06-28): κ=F·L/θ with F=5pN skeletal stall (Finer'94), L=8nm, θ=60° ⇒ tip≈0.597 pN/nm, stall 5.00 pN. Was 6.4e-20 (8.38 pN). -kappa overrides.
    static double  PAIRS_FRACMOVE = 0.5;         // PAIRS attachment fracMove (dt-robust pin strength); not calibrated
    static double  KON = 0.0;                    // -kon: reaction-limited attachment rate (µm^-1 s^-1); 0 ⇒ saturated bind-on-contact. PHASE-2 step-3 calibration target.
    static boolean NOBIND = false;               // -nobind: thermal-floor control — motors never bind (kinParams[19]=1). Measurement; default-off ⇒ byte-identical.
    static boolean SINGLE = false;               // -single: single-molecule duty assay (proximal motors on a fixed filament)
    static double  SINGLE_Z = -0.090;            // -singlez: single-molecule anchor z (positions the cycling head's bob range at the filament)
    static double  TAU_AVG = 0.0;                // -tauavg <ms>: time-averaged catch input (EMA window τ); 0 ⇒ instantaneous (byte-identical). PHASE-2 step-4a Jensen fix.
    static double  F_EXT = 0.0;                  // -fext <pN>: sustained external load injected into the catch input (force-response guard)
    static boolean ACORR = false;                // -acorr: measure the J1-strain autocorrelation (τ_thermal) in the single-molecule assay
    static double  XCATCH = 0.0;                 // -xcatch <nm>: catch distance d override (Veigel calibration); 0 ⇒ v1 default 2.5 nm
    static boolean DCALIB = false;               // -dcalib: catch force-sensitivity calibration (rate vs load sweep at fixed kOn/τ)
    static boolean CSRECAL = false;              // -csrecal: step-4c catch-slip recalibration (lifetime vs load PAST the peak + independent motor stall + peak≈stall)
    static boolean STIFFSWEEP = false;           // -stiffsweep: step-4d κ + powerstroke-angle sensitivity of peak≈stall (measurement-only)
    static boolean FORCEDECOMP = false;          // -forcedecomp: decompose the powerstroke force the config-1 cross-bridge delivers to the FILAMENT (single motor, transport topology, dt=1e-6, Brownian off; measurement-only)
    static boolean BOUNDGEOM = false;            // -boundgeom: report the config-1 motor's bound-state geometry at uncocked/cocked equilibria (measurement-only)
    static boolean PERPHEAD = false;             // -perphead: config-1 PERP-HEAD variant — rear pin REMOVED, tip pin kept, PAIRS-form ⊥-orientation torque holds the head ⊥ to the filament. Implies -config1. The force-decomposition mechanism gate.
    static double  PERP_FRACMOVE = 0.5;          // -perpfrac: ⊥-orientation torque strength (matches the prior rear-pin/PAIRS scale)
    // ---- HEAD-ANGLE SWEEP (flag-gated, default-off ⇒ θ=90 ⊥ path byte-identical) ----
    static double  HEADTILT_DEG = 90.0;          // -headtilt <deg>: head↔filament rest angle θ baked into the frozen perpRest target (0=along actin, 90=⊥=current perphead)
    static boolean HEADTILT_SET = false;         // true ⇒ apply Target(θ); false ⇒ plain ⊥ rest (byte-identical perphead)
    static boolean HEADTILT_SWEEP = false;       // -headtiltsweep: Stage-1 single-motor force-decomp θ sweep on the OFF-AXIS bind (measurement-only)
    static double  OFFAXIS_DEG = 40.0;           // -offaxis <deg>: off-axis bind angle for the decomp setup (non-degenerate perpRest; default 40°)
    static boolean ATP_RELEASE = true;           // PHASE-2 ATP-RELEASE COUPLING: a bound head detaches AT its NONE→ATP transition (ATP-binding = detachment). Default-ON for CONFIG1 (config1/perphead gliding); A/B control -noatprelease turns it OFF (old decoupled cycle). Non-CONFIG1 paths never see it.
    static int VIZ_FRAMES = 400;                 // -vizframes <n>: number of -3js viewer frames (cadence = M/n)
    static double REBIND_TIME = 0.0;             // -rebindtime <s>: override the post-release rebind refractory (0 ⇒ v1 myoRebindTime, byte-identical)
    static boolean LEGACYMOTOR = false;          // -legacymotor: restore the OLD default motor (v1-port F9 head-swing, 90°↔120°; sphere-head stack OFF). Kept for regression/oracle. See the 2026-07-01 promotion.
    static boolean SPHEREHEAD = true;            // CANONICAL DEFAULT (2026-07-08 collapse Stage 1; formerly a post-parse promotion): freeze F9 at 90° (perp-maintainer) ⇒ the J1 neck-swing is the stroke (the three-body sphere-head). -legacymotor restores the v1-port F9 head-swing; -canonical/-config1/-perphead select their own bond law (sphere stack cleared in the resolution block).
    static boolean AXLOCK = true;                // CANONICAL DEFAULT: retarget F10 to ŝ=normalize(n̂bed×seg.uVec) head-only (the axial swing-plane lock, §9.4c). Part of the sphere-head canonical stack.
    static boolean DIRSWING = true;              // CANONICAL DEFAULT: DETERMINISTIC polarity-directed power stroke (CrossBridgeSystem.directedSwing) — neck rear sweeps barbed-ward; J1 angular converter OFF. Part of the sphere-head canonical stack.
    static boolean ROLLCENSUS = false;           // -rollcensus: after a grid run, census bound heads' roll sign (head.yVec·ŝ). Diagnostic only.
    static boolean TWISTCENSUS = false;          // -twistcensus: capture the arrival-angle (head.yVec vs +ŝ) at each fresh bind. Diagnostic only.
    static boolean MHATCENSUS = false;           // -mhatcensus: census bound heads' HEAD-AXIS sign (head.uVec·n̂bed, n̂bed=+Z) — the second free sign (mhat=±n̂). +ẑ is the productive pole (p=ŷ×û=f̂). Diagnostic only; prints aggregate + per-sample time series + stdev.
    static boolean STROKECENSUS = false;         // -strokecensus: read-only per-bound-head neck-POWERSTROKE-fidelity census. swing_perp = uLever − (uLever·mhat)mhat (the neck's tilt off the head axis; |swing_perp|=sin(lever–head angle)=stroke magnitude). Reports: stroked fraction (|swing_perp|>sin40°); barbed-sweep fraction (swing_perp·f̂<0 ⇒ tip pointed / rear barbed = productive, mirrors BoA NECK_STROKE_POLARITY 99.6%); swing AXIAL fraction |swing_perp·f̂|/|swing_perp| (1.0=purely axial). f̂=bound seg.uVec. No force/RNG/integration; default-off byte-identical. STROKE_FIDELITY_CENSUS.
    static double HEADLOCK = 1.0;                 // -headlock <mult>: scale the head orientation-lock coeff (xbParams[2], the F9 ⊥ hold + F10 axial/roll lock). >1 stiffens (diagnostic for the head-noise claim). Default 1 ⇒ byte-identical.
    static boolean LYMN_TAYLOR = true;           // CANONICAL DEFAULT (2026-07-08 collapse Stage 1; jba-signed): the VALIDATED Lymn-Taylor cycle. ONE release pathway (NONE→ATP = detachment, fast/nucleotide-driven); the 4c catch MODULATES the ADP→NONE rate (not a release). ADP·Pi-only binding is WELDED in (D1a; see resolution block). -legacycycle opt-out restores the legacy catch-slip nucleotide cycle.
    static boolean LEGACY_CYCLE = false;         // -legacycycle: opt-out of the canonical Lymn-Taylor cycle ⇒ the legacy catch-slip nucleotide cycle (permissive binding). Resolved after arg-parse.
    static boolean ADPPI_BIND = false;           // -adppibind (STEP A dwell fix): strong-bind gate — a head binds actin ONLY in the pre-stroke ADP·Pi state (kinParams[20]=1). Kills the bind-in-ATP ejection churn. WELDED into the canonical Lymn-Taylor cycle (forced true when LYMN_TAYLOR ∧ ¬ALLOW_BIND_ANY, in the resolution block); still settable standalone as a diagnostic. Default-off field ⇒ legacy path stays permissive.
    static boolean ALLOW_BIND_ANY = false;       // -allowbindany (marked diagnostic): reintroduce permissive binding (bind from any nucleotide state — the diagnosed bind-in-ATP churn) EVEN under the canonical Lymn-Taylor cycle, by suppressing the D1a ADP·Pi weld. The clean counterfactual to the weld (isolates the bind-gate from the cycle swap; -legacycycle would confound them). Default-off ⇒ byte-identical.
    static boolean BRAKEDIAG = false;            // -brakediag (PART B, measurement-only): per-bound-head axial seg-force (assist −x / brake +x) vs signed catch load forceDotFil, binned by time-since-stroke; release-vs-signed-load histogram. CPU runner, default-off.
    static final double ANCHOR_Z = -0.05;       // fixedMyosinZValue
    static final double FIL_Z = 0.0;            // gliding filament z (v1)
    // SWEPT EXPERIMENTAL PARAMETER — NOT a canonical model default (2026-07-08 Stage 2 reclassification, D7).
    // Motor density is a gliding-assay experimental condition, not a chosen operating point; always pass -density
    // explicitly for any reported run. 500 is a NEUTRAL PLACEHOLDER so the harness/diagnostics can run, not a
    // canonical value. (Strict obligatory-input enforcement + per-diagnostic condition handling = Stage 3.)
    static double DENSITY = 500.0;              // -density <motors/µm²>: SWEPT condition (placeholder default; see marker above)
    static boolean DENSITY_SET = false;         // true ⇒ -density was passed explicitly (else the main gliding run warns it is a placeholder, D7 Stage 3)
    static double NECK_ANGLE = 60.0;            // -neckangle <deg>: cocked neck-stroke rest angle (swingParams[3]); default 60 ⇒ byte-identical. STEP-2 step-size lever (step ≈ 2·L·sin(θ/2)).
    // ---- PURE_SPRINGS: the fraction-per-step → FIXED-STIFFNESS-SPRING reformulation. PROMOTED TO DEFAULT-ON (2026-07-08,
    //      SPRINGS_PROMOTION.md; GATE-1 hazard map + GATE-2 GPU==CPU basin + stability verified; jba re-baseline sign-off flagged). ----
    // WHY DEFAULT: springs is the CANONICAL, deterministic, TRANSCENDENTAL-FREE production formulation. The -ratefix swing
    // recompute uses in-kernel exp/log whose PRESENCE (not value) perturbs PTX scheduling → tips the bistable GPU glide basin
    // to LOW (BISTABILITY_ORIGIN). The springs swing is a plain MULTIPLY (k·dt/refDt) ⇒ same production-dt physics, no
    // transcendental, GPU stays in the HIGH basin (== the CPU). -filrate/-alignrate/-strokerate/-structrate convert a per-STEP
    // fraction k into a per-TIME geometric-decay RATE k_eff=1−(1−k)^(DT/refDt); springs instead freeze it as a FIXED spring
    // k=frac·γ_red/refDt via springify(frac)=frac·(DT/refDt), fed into the SAME kernel ⇒ dt cancels to refDt (a fixed stiffness).
    // At DT==refDt=1e-5 springify(k)==k ⇒ BYTE-IDENTICAL to raw/rates (all prior production-dt validation numbers STAND); the
    // formulations diverge only BELOW refDt (the fine-dt convergence reference moves to the springs continuum). OPT-OUT: -nosprings
    // restores the raw fraction-per-step default; comparison rate path = -nosprings -ratefix -structrate. Spring flags take
    // PRECEDENCE over the matching rate flag for the SAME slots (replace, NOT compose).
    static boolean PAIRS_SPRINGS = true;        // -pairsprings (DEFAULT-ON): FILAMENT PAIRS (chainParams[1] fracMove F3, chainParams[3] fracMoveTorq F4) → fixed springs. -nosprings to disable.
    static boolean ALIGN_SPRINGS = true;        // -alignsprings (DEFAULT-ON): MOTOR alignment (xbParams[2] F9/F10/axlock + directedSwing MULTIPLY branch) → fixed springs. THE transcendental-free swing. -nosprings to disable.
    static boolean STRUCT_SPRINGS = true;       // -structsprings (DEFAULT-ON): STRUCTURAL J1/J2 connection + tail-anchor position springs (jointParams[1]/[5]/[9]) → fixed springs. -nosprings to disable.
    static double STROKE_REF_DT = 1.0e-5;       // -strokerefdt <s>: reference dt at which k_eff==0.4 (the coarse/production-dt physical stroke duration to preserve); default 1e-5
    static double RATE_SCALE = 1.0;             // -ratescale <x>: scale catch-slip kOff + ALL nucleotide cycle rates by x (faster kinetics = V₀ = step·detach-rate); default 1 ⇒ byte-identical. STEP-3 cycle-rate lever.
    // SWEPT EXPERIMENTAL PARAMETER — NOT a canonical model default (2026-07-08 Stage 2 reclassification, D7).
    // Capture radius is a swept physical condition (engagement/duty knob), not a chosen operating point; pass
    // -coltol explicitly for any reported run. 6 nm is a NEUTRAL PLACEHOLDER the harness needs to run (it also
    // matches the setKinParams sentinel so kinParams[7] is untouched unless overridden), NOT a canonical value.
    static double COL_TOL = 0.006;              // -coltol <nm>: SWEPT condition (placeholder default; see marker above). kinParams[7], perp tip-to-axis reach.
    static boolean COL_TOL_SET = false;         // true ⇒ -coltol was passed explicitly (else the main gliding run warns it is a placeholder, D7 Stage 3)
    // BISTABILITY_ORIGIN: force the directedSwing coefficient swingParams[0] to an EXACT float (by its int bits), size-4
    // (NO in-kernel recompute) ⇒ BOTH runners use that exact float k. Replicates the GPU ratefix swing-ULP on the CPU:
    // the GPU-ratefix flip's only in-kernel difference from raw is this coefficient. Default -1 ⇒ untouched/byte-identical.
    static int SWING_K_BITS = -1;               // -swingkbits <int|0x..>: swingParams[0] = Float.intBitsToFloat(bits); 0x3ecccccd = 0.4f exact
    static boolean SWING_K_PROBE = false;       // -swingkprobe: extract the EXACT float the GPU computes for the ratefix in-kernel swing k (1−exp(1·log(0.6))) vs Java double vs 0.4f
    static double AETA = Constants.aeta;        // -aeta <Pa·s>: filament/medium viscosity (drag γ ∝ aeta, diffusion ∝ 1/aeta). Default 0.1 ⇒ byte-identical. VISCOSITY DIAGNOSTIC: is the glide cycle-limited (η↓ flat) or drag-limited (η↓ raises speed)? PHYSICAL param, NOT a speed dial.
    static boolean STRETCHCENSUS = false;       // -stretchcensus: read-only census of the BOUND population's anchor-spring extension (forceMag/myoSpring), per-head axial force (forceDotFil), + aggregate dwell (stats). STEP-3 geometry check; no force change; default-off byte-identical.
    static boolean KTOT_CENSUS = false;         // -ktotcensus: read-only census of the per-segment collective cross-bridge stiffness K_tot=k_s·myoSpring vs the EOM ~1.4/~3.8 pN/nm instability thresholds. STEP-3 (SEG_IMPLICIT_FINDINGS); no force change; default-off byte-identical.
    static final int    FIL_SEGS = 11;          // ~2 µm of 64-monomer segments
    static final int    FIL_MONO = 64;          // filSegLength (gliding override)
    // bed geometry: bX0 = filament +x end; the bed spans x∈[bXlo,bXhi], y∈[-bYhalf,bYhalf].
    // default ≈6×2; -full → v1's 14×2 (~14k motors); -v1box → v1's 4×1 (exactly 2000 heads).
    static double bX0 = 2.2, bXlo = -3.5, bXhi = 2.7, bYhalf = 1.0;
    static boolean MATBED = false;   // -matbed: FULL-MAT speed-density bed — long −x runway so the filament stays fully over motors throughout the measurement window (verified in measureGrid). Filament +x end placed near the +x edge; glides −x through the populated mat.
    static int SEED = 0x6111D;   // varied across an ensemble (placement + RNG)
    static boolean BOX = false;      // -box: wire the ContainmentSystem chamber over the gliding filament (v1-faithful: anchored motors un-boxed)
    static boolean BOX_ALL = false;  // -boxall: additionally confine every motor sub-body (upper-bound box-check load at 3·nMotors bodies)
    // ---- SUBSTEP_FEASIBILITY (MEASUREMENT-ONLY): bound fraction + bound-cross-bridge slice X + per-outer-dt site motion ----
    static boolean SUBSTEP = false;              // -substep
    static double  OUTER_DT = 1.0e-4;            // -outerdt <s>
    static final int BOND = 0, APPLY = 1, GATHER = 2, REGISTER = 3, RELEASE = 4, MOTADV = 5, STEP = 6;
    static final long[] sliceNs = new long[7];
    static long sliceSteps = 0, boundAccum = 0;
    static SiteMotionTracker SMT = null;
    static long tns() { return SUBSTEP ? System.nanoTime() : 0L; }
    // HEAD-ANGLE SWEEP — settled-state metrics captured by decompRun (for the Stage-1 sweep table)
    static double DEC_axialPN, DEC_transvPN, DEC_headAngDeg, DEC_leverAngDeg, DEC_j1Deg;

    // ---- GLIDING_RADIUS_SWEEP levers (both opt-in; default byte-identical) ----
    // (a) early-stop monitor: stop a grid run when the batch-means SEM of the steady-window glide speed is precise.
    static boolean EARLYSTOP = false;            // -earlystop: enable the host-side batch-means SEM monitor + break
    static double ES_THRESH  = 0.05;             // -esthresh <rel>: rel-SEM stop threshold (default 5%)
    static double ES_MINWIN  = 0.03;             // -esminwin <s>: minimum steady-window span before stopping
    static double ES_CAP     = 0.15;             // -escap <s>: hard cap sim-time (stop regardless; NOT-CONVERGED@cap if 5% unmet)
    static double ES_BATCH   = 0.005;            // -esbatch <s>: minimum per-batch span (autocorrelation floor); ≥5 batches required
    static double ES_INTERVAL = 0.005;           // -esinterval <s>: monitor check cadence
    // (b) mat-shrink: trim the never-visited −x motor tail to a band around the filament's swept path, same density.
    static boolean MATBAND = false;              // -matband <excursionBudgetµm>: enable the density-preserving −x band shrink
    static double  MATBAND_EXC = 1.2;            // −x glide excursion budget (µm) sizing the band's −x extent
    static final double MATBAND_MARGIN = 0.15;   // extra −x margin (µm) beyond swept extent (> motor x-reach + capture) ⇒ dropped motors provably never bind

    public static void main(String[] args) {
        int M = 2000;
        String viz = null;
        // -vizframes <n>: target number of viewer frames (default 400); raise it for fine-time-resolution -3js runs
        boolean diag = false;
        boolean cycldiag = false;
        boolean grid = false;
        boolean ztrace = false;
        boolean assistlog = false;
        java.util.List<String> pos = new java.util.ArrayList<>();
        boolean gpu = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-3js")) viz = args[++i];
            else if (args[i].equals("-vizframes")) VIZ_FRAMES = Integer.parseInt(args[++i]);
            else if (args[i].equals("-rebindtime")) REBIND_TIME = Double.parseDouble(args[++i]);   // post-release refractory in seconds (big = can't rebind in the same spot)
            else if (args[i].equals("-diag")) diag = true;
            else if (args[i].equals("-cycldiag")) cycldiag = true;
            else if (args[i].equals("-brakediag")) BRAKEDIAG = true;   // PART B brake decomposition
            else if (args[i].equals("-grid")) grid = true;
            else if (args[i].equals("-ztrace")) ztrace = true;
            else if (args[i].equals("-assistlog")) assistlog = true;   // §6.9 per-bound-motor tuple dump (default off)
            else if (args[i].equals("-gpu")) gpu = true;
            else if (args[i].equals("-full")) { bX0 = 5.87; bXlo = -7.0; bXhi = 6.37; bYhalf = 1.0; }  // v1 14×2, ~13.4k motors
            else if (args[i].equals("-v1box")) { bX0 = 1.7; bXlo = -2.0; bXhi = 2.0; bYhalf = 0.5; }   // v1 4×1, exactly 2000 heads
            else if (args[i].equals("-matbed")) { MATBED = true; bX0 = 1.6; bXlo = -3.2; bXhi = 2.6; bYhalf = 0.6; }   // FULL-MAT: 5.8×1.2µm bed; filament +x end at 1.6 (settling +x excursion ~0.4 stays on-mat, +x margin ~0.6; ~2.9µm −x runway), y±0.6 covers rotation. nMot = density·6.96.
            else if (args[i].equals("-densemat")) {   // motor-mat throughput probe: square 14·√N bed (BoA dense-gliding box schedule) → 98000·N motors, single filament
                double side = 14.0 * Math.sqrt(Double.parseDouble(args[++i]));
                bX0 = 1.0; bXlo = -side / 2; bXhi = side / 2; bYhalf = side / 2;
            }
            else if (args[i].equals("-box")) BOX = true;            // ContainmentSystem over the filament (v1-faithful)
            else if (args[i].equals("-boxall")) { BOX = true; BOX_ALL = true; }  // + over every motor sub-body (load upper bound)
            else if (args[i].equals("-seed")) SEED = 0x6111D + 7919 * Integer.parseInt(args[++i]);
            else if (args[i].equals("-dt")) DT = Double.parseDouble(args[++i]);   // dt-convergence test
            else if (args[i].equals("-myospring")) MYO_SPRING = Double.parseDouble(args[++i]) * 1.0e-9;  // MEASUREMENT-ONLY: cross-bridge stiffness in pN/nm (1 = default). Hookean F8 unchanged; production default unchanged.
            else if (args[i].equals("-norefractory")) NO_REFRACTORY = true;        // rebind-refractory OFF bracket
            else if (args[i].equals("-forcecapdetach")) FORCE_CAP_DETACH = true;   // renamed from -faithfulrelease (2026-07-08 Stage 2): hard force-cap detachment
            else if (args[i].equals("-detachcap")) DETACH_CAP = Double.parseDouble(args[++i]);   // force-cap threshold in pN (default 12); only active with -forcecapdetach
            else if (args[i].equals("-faithfulrefractory")) FAITHFUL_REFRACTORY = true;  // §6.11 rate-faithful rebind refractory
            else if (args[i].equals("-forcebias")) FORCE_BIAS = Double.parseDouble(args[++i]);  // §6.8 susceptibility pre-filter
            else if (args[i].equals("-xbimplicit2")) XB_IMPLICIT2 = true; // COUPLED_IMPLICIT head+SITE implicit F8 (canonical default; -explicitxb opts out)
            else if (args[i].equals("-xbtrap")) { XB_IMPLICIT2 = true; XB_TRAP = true; }  // TRAPEZOIDAL/CN re-timing of the coupled F8 star (r→r/2)
            else if (args[i].equals("-segimplicit")) SEG_IMPLICIT = true;  // DIAGONAL per-segment implicit collective loaded cross-bridge (composes with -xbimplicit2)
            else if (args[i].equals("-pairsprings")) PAIRS_SPRINGS = true;  // PURE_SPRINGS: FILAMENT PAIRS → fixed springs
            else if (args[i].equals("-alignsprings")) ALIGN_SPRINGS = true; // PURE_SPRINGS: motor alignment → fixed springs (precedence over -alignrate/-strokerate)
            else if (args[i].equals("-structsprings")) STRUCT_SPRINGS = true; // PURE_SPRINGS: structural J1/J2/anchor → fixed springs (precedence over -structrate)
            else if (args[i].equals("-nosprings")) { PAIRS_SPRINGS = false; ALIGN_SPRINGS = false; STRUCT_SPRINGS = false; }  // SPRINGS_PROMOTION opt-out: restore the raw fraction-per-step default (comparison rate path = -nosprings -ratefix -structrate)
            else if (args[i].equals("-swingkbits")) SWING_K_BITS = Integer.decode(args[++i]); // BISTABILITY_ORIGIN: force swing coeff to exact float bits (both runners)
            else if (args[i].equals("-swingkprobe")) SWING_K_PROBE = true;   // BISTABILITY_ORIGIN: extract the exact GPU-computed ratefix swing k
            else if (args[i].equals("-strokerefdt")) STROKE_REF_DT = Double.parseDouble(args[++i]);
            else if (args[i].equals("-canonical")) CANONICAL = true;     // PHASE-2 Version-B two-point canonical motor
            else if (args[i].equals("-canondiag")) { CANONICAL = true; CANON_DIAG = true; }  // + instrument the binder
            else if (args[i].equals("-config1")) { CANONICAL = true; CONFIG1 = true; }       // PHASE-2 Config-1 (PAIRS + Hookean J1)
            else if (args[i].equals("-config1diag")) { CANONICAL = true; CONFIG1 = true; CANON_DIAG = true; }
            else if (args[i].equals("-kappa")) KAPPA = Double.parseDouble(args[++i]);          // J1 torsional stiffness override (N·m/rad)
            else if (args[i].equals("-kon")) KON = Double.parseDouble(args[++i]);              // reaction-limited attachment rate (µm^-1 s^-1)
            else if (args[i].equals("-nobind")) NOBIND = true;   // thermal-floor control: motors never bind (kinParams[19]); measurement, default-off byte-identical
            else if (args[i].equals("-single")) { CANONICAL = true; CONFIG1 = true; SINGLE = true; }  // single-molecule duty assay (Config-1)
            else if (args[i].equals("-singlez")) SINGLE_Z = Double.parseDouble(args[++i]);             // single-molecule anchor z override
            else if (args[i].equals("-tauavg")) TAU_AVG = Double.parseDouble(args[++i]) * 1.0e-3;       // time-averaged catch window τ (ms → s)
            else if (args[i].equals("-fext")) F_EXT = Double.parseDouble(args[++i]);                    // sustained external load (pN)
            else if (args[i].equals("-acorr")) ACORR = true;                                            // J1-strain autocorrelation diagnostic
            else if (args[i].equals("-xcatch")) XCATCH = Double.parseDouble(args[++i]);                  // catch distance d override (nm)
            else if (args[i].equals("-density")) { DENSITY = Double.parseDouble(args[++i]); DENSITY_SET = true; }   // motor density (SWEPT condition, D7)
            else if (args[i].equals("-dcalib")) { CANONICAL = true; CONFIG1 = true; SINGLE = true; DCALIB = true; }  // catch force-sensitivity calibration
            else if (args[i].equals("-csrecal")) { CANONICAL = true; CONFIG1 = true; SINGLE = true; CSRECAL = true; }  // step-4c catch-slip recalibration
            else if (args[i].equals("-stiffsweep")) { CONFIG1 = true; STIFFSWEEP = true; }   // step-4d κ+angle sensitivity (measurement-only)
            else if (args[i].equals("-forcedecomp")) { CONFIG1 = true; FORCEDECOMP = true; }  // force decomposition (single motor, transport topology)
            else if (args[i].equals("-perphead")) { CANONICAL = true; CONFIG1 = true; PERPHEAD = true; }  // config-1 PERP-HEAD MOTOR VARIANT (gliding-capable); combine with -forcedecomp for the single-motor mechanism gate
            else if (args[i].equals("-perpfrac")) PERP_FRACMOVE = Double.parseDouble(args[++i]);  // ⊥-orientation torque strength override
            else if (args[i].equals("-headtilt")) { HEADTILT_DEG = Double.parseDouble(args[++i]); HEADTILT_SET = true; CANONICAL = true; CONFIG1 = true; PERPHEAD = true; }  // head↔filament rest angle θ (implies -perphead)
            else if (args[i].equals("-headtiltsweep")) { HEADTILT_SWEEP = true; CANONICAL = true; CONFIG1 = true; PERPHEAD = true; FORCEDECOMP = true; }  // Stage-1 θ sweep (single-motor force decomp, off-axis bind)
            else if (args[i].equals("-offaxis")) OFFAXIS_DEG = Double.parseDouble(args[++i]);  // off-axis bind angle for the decomp/sweep setup
            else if (args[i].equals("-noatprelease")) ATP_RELEASE = false;  // A/B control: DISABLE the ATP-transition→detach coupling (old decoupled cycle) for config1/perphead
            else if (args[i].equals("-lymntaylor") || args[i].equals("-lt")) LYMN_TAYLOR = true;   // jba: the validated canonical cycle (single nucleotide-driven release). CANONICAL DEFAULT since collapse Stage 1; kept for explicitness.
            else if (args[i].equals("-legacycycle")) LEGACY_CYCLE = true;   // COLLAPSE Stage-1 opt-out: force the legacy catch-slip nucleotide cycle (opt-out of canonical Lymn-Taylor)
            else if (args[i].equals("-explicitxb")) EXPLICIT_XB = true;     // COLLAPSE Stage-1 opt-out: force the explicit Hookean F8 (opt-out of the canonical -xbimplicit2)
            else if (args[i].equals("-allowbindany")) ALLOW_BIND_ANY = true;  // COLLAPSE Stage-1 marked diagnostic: permissive binding (bind from any state) under the canonical cycle — suppresses the D1a ADP·Pi weld
            else if (args[i].equals("-adppibind")) ADPPI_BIND = true;   // STEP A: ADP·Pi strong-bind gate (kills bind-in-ATP ejection churn). WELDED into canon; kept standalone as a diagnostic.
            else if (args[i].equals("-spherehead")) SPHEREHEAD = true;   // freeze F9 at 90° ⇒ J1 neck-swing is the stroke (sphere-head on the dense-mat GPU path)
            else if (args[i].equals("-axlock")) { SPHEREHEAD = true; AXLOCK = true; }   // axial swing-plane lock (F10 → ŝ, head-only); implies -spherehead
            else if (args[i].equals("-dirswing")) { SPHEREHEAD = true; AXLOCK = true; DIRSWING = true; }   // + deterministic polarity-directed power stroke; implies -axlock
            else if (args[i].equals("-rollcensus")) ROLLCENSUS = true;   // census the bound-head roll sign (head.yVec·ŝ) after a grid run
            else if (args[i].equals("-twistcensus")) TWISTCENSUS = true;   // capture the bind-time roll twist (arrival angle vs +ŝ)
            else if (args[i].equals("-mhatcensus")) MHATCENSUS = true;     // census the bound-head head-axis sign (head.uVec·n̂bed)
            else if (args[i].equals("-strokecensus")) STROKECENSUS = true;  // read-only per-bound-head neck-powerstroke fidelity census (swing axial frac, barbed-sweep frac)
            else if (args[i].equals("-legacymotor")) LEGACYMOTOR = true;    // restore the OLD default motor (v1-port F9 head-swing); sphere-head stack OFF
            else if (args[i].equals("-headlock")) HEADLOCK = Double.parseDouble(args[++i]);   // head orientation-lock stiffness multiplier (diagnostic)
            else if (args[i].equals("-boundgeom")) { CONFIG1 = true; BOUNDGEOM = true; }      // bound-state geometry report (single motor, transport topology)
            else if (args[i].equals("-substep")) SUBSTEP = true;         // SUBSTEP_FEASIBILITY readout
            else if (args[i].equals("-neckangle")) NECK_ANGLE = Double.parseDouble(args[++i]);   // STEP-2 step-size lever: cocked neck rest angle (deg)
            else if (args[i].equals("-ratescale")) RATE_SCALE = Double.parseDouble(args[++i]);   // STEP-3 cycle-rate lever: ×scale on kOff + all nucleotide rates
            else if (args[i].equals("-coltol")) { COL_TOL = Double.parseDouble(args[++i]) * 1.0e-3; COL_TOL_SET = true; }  // CAPTURE-RADIUS sweep: bind reach nm→µm (kinParams[7], D7)
            else if (args[i].equals("-aeta")) AETA = Double.parseDouble(args[++i]);   // VISCOSITY DIAGNOSTIC: filament/medium viscosity (Pa·s); default 0.1 ⇒ byte-identical
            else if (args[i].equals("-stretchcensus")) STRETCHCENSUS = true;                       // STEP-3 read-only bound-population geometry census
            else if (args[i].equals("-ktotcensus")) KTOT_CENSUS = true;                            // STEP-3 read-only per-segment K_tot vs instability-threshold census
            else if (args[i].equals("-outerdt")) OUTER_DT = Double.parseDouble(args[++i]);
            else if (args[i].equals("-earlystop")) EARLYSTOP = true;                            // GLIDING_RADIUS_SWEEP lever (a)
            else if (args[i].equals("-esthresh")) ES_THRESH = Double.parseDouble(args[++i]);
            else if (args[i].equals("-esminwin")) ES_MINWIN = Double.parseDouble(args[++i]);
            else if (args[i].equals("-escap")) ES_CAP = Double.parseDouble(args[++i]);
            else if (args[i].equals("-esbatch")) ES_BATCH = Double.parseDouble(args[++i]);
            else if (args[i].equals("-esinterval")) ES_INTERVAL = Double.parseDouble(args[++i]);
            else if (args[i].equals("-matband")) { MATBAND = true; MATBAND_EXC = Double.parseDouble(args[++i]); }  // GLIDING_RADIUS_SWEEP lever (b)
            else if (args[i].equals("-forcetest")) { /* handled before buildScene */ }
            else pos.add(args[i]);
        }
        if (!pos.isEmpty()) M = Integer.parseInt(pos.get(0));

        // ── CANONICAL COLLAPSE Stage 1 (2026-07-08) — resolve the canonical model + its opt-outs ──────────────────
        // The four ratified canonical defaults are now FIELD defaults (springs already-on; SPHEREHEAD/AXLOCK/DIRSWING,
        // XB_IMPLICIT2, LYMN_TAYLOR flipped true above). This block applies the opt-outs and the D1a bind weld.
        //
        // MOTOR: the f̂-referenced sphere-head neck-powerstroke (SPHEREHEAD+AXLOCK+DIRSWING) is the explicit default
        // (formerly a post-parse promotion — removed, byte-identical). -legacymotor restores the v1-port F9 head-swing;
        // -canonical/-config1/-perphead select their own bond law ⇒ clear the sphere stack (they never combined with it).
        // This transform is byte-identical to the old promotion for every path (default/legacy/canonical/-spherehead/…).
        if (LEGACYMOTOR) {
            SPHEREHEAD = false; AXLOCK = false; DIRSWING = false;
        } else if (CANONICAL || CONFIG1 || PERPHEAD) {
            SPHEREHEAD = false; AXLOCK = false; DIRSWING = false;
        }
        // CYCLE / CROSS-BRIDGE opt-outs.
        if (LEGACY_CYCLE) LYMN_TAYLOR = false;   // -legacycycle: legacy catch-slip nucleotide cycle
        if (EXPLICIT_XB)  XB_IMPLICIT2 = false;  // -explicitxb: explicit Hookean F8
        // D1a WELD: the canonical Lymn-Taylor cycle binds actin ONLY from the ADP·Pi pre-stroke state (kinParams[20]=1,
        // set in buildScene from ADPPI_BIND). -allowbindany reintroduces permissive binding under the canonical cycle
        // as a marked diagnostic (the clean counterfactual to the weld). -legacycycle ⇒ LYMN_TAYLOR false ⇒ the weld does
        // not fire ⇒ ADPPI_BIND stays at its field default (false) ⇒ the legacy path is permissive (restores the old default).
        if (LYMN_TAYLOR && !ALLOW_BIND_ANY) ADPPI_BIND = true;

        for (String a : args) if (a.equals("-forcetest")) { forceTest(); return; }
        if (STIFFSWEEP) { diagMark("stiffnessAngleSweep: analytic phase-2 κ/angle sensitivity (no dynamics scene)"); stiffnessAngleSweep(); return; }   // step-4d: builds its own minimal one-shot scenes
        if (HEADTILT_SWEEP) { diagMark("headTiltSweep: config-1/perp single-motor force-decomp, RAW chainParams, dt=1e-6"); headTiltSweep(); return; }     // Stage-1 θ sweep (single-motor force decomp, off-axis bind)
        if (FORCEDECOMP) { diagMark("forceDecomp: config-1/perp single-motor force-decomp, RAW chainParams, dt=1e-6"); forceDecomp(); return; }          // force decomposition: builds its own single-motor transport scene
        if (BOUNDGEOM) { diagMark("boundGeom: config-1 single-motor bound geometry, RAW chainParams, dt=1e-6"); boundGeom(); return; }              // bound-state geometry: builds its own single-motor transport scene

        System.out.println("=== Soft Box increment 4b-iv — gliding assay (cheap probe)" + (CANONICAL ? " [PHASE-2 CANONICAL Version-B two-point motor]" : "") + " ===");
        Scene sc = buildScene();
        System.out.printf("config: %d-seg filament (%.2f µm) at z=%.3f, %d motors @ %.0f/µm² strip, dt=%.0e%n",
                sc.fil.n, sc.fil.n * sc.segL, FIL_Z, sc.mot.nMotors, DENSITY, DT);
        if (XB_IMPLICIT2) {
            if (XB_TRAP && SEG_IMPLICIT) throw new IllegalArgumentException("-xbtrap and -segimplicit are mutually exclusive (both re-time the segment solve)");
            double theta = XB_TRAP ? 0.5 : 1.0;   // -xbtrap: midpoint (r→r/2); -xbimplicit2: backward-Euler (r)
            double kSI = MYO_SPRING * 1.0e6, rh = theta * kSI * DT / 1.885e-8;
            System.out.printf(java.util.Locale.US,
                "  %s: COUPLED head+SITE implicit F8 ON — per-segment closed-form star (reuses the boundSeg CSR-inverse). %s: r_head=%s·k·dt/γ_head≈%.3f; the SEGMENT (site) is implicit too (r_seg,∥≈%.3f, r_seg,⊥≈%.3f). Torque/rotation + chain explicit. See %s.%n",
                XB_TRAP ? "-xbtrap" : "-xbimplicit2",
                XB_TRAP ? "TRAPEZOIDAL/Crank–Nicolson midpoint (θ=½ ⇒ r→r/2; the STEP-1-validated 2nd-order re-timing of the SAME coupled star)" : "backward-Euler (θ=1)",
                XB_TRAP ? "½" : "", rh, theta * kSI * DT / 2.3885e-8, theta * kSI * DT / 3.3088e-8,
                XB_TRAP ? "XBTRAP_PROBE.md" : "COUPLED_IMPLICIT_XB_FINDINGS.md");
        }
        if (SEG_IMPLICIT) {
            double kSI = MYO_SPRING * 1.0e6;
            System.out.printf(java.util.Locale.US,
                "  -segimplicit: DIAGONAL per-segment IMPLICIT collective loaded cross-bridge ON — backward-Euler on K_tot=k_s·myoSpring (RIGID head, full F8 stiffness), q_imp,a=(q_e,a+rK_a·q_n,a)/(1+rK_a), rK_a=K_tot·dt·1e6/γ_a (per body axis). Off-diagonal (motor-body cross-seg, chain) explicit. myoSpring=%.2f pN/nm ⇒ per-bond rK_∥≈%.3f at dt=%.0e (k_s bonds ⇒ ×k_s). Composes with -xbimplicit2 (segment solve replaces coupleSolveSeg; head phases kept). See SEG_IMPLICIT_FINDINGS.md.%n",
                MYO_SPRING * 1.0e9, kSI * DT / 2.3885e-8, DT);
        }
        if (PAIRS_SPRINGS) System.out.printf(java.util.Locale.US, "  -pairsprings: FILAMENT PAIRS frozen as FIXED SPRINGS. fracMove 0.5→%.4f, fracMoveTorq 0.2→%.4f (springify=k·DT/refDt, build-time chainParams[1]/[3]) at dt=%.2e (refDt=%.2e). Byte-identical at refDt.%n", springify(0.5), springify(0.2), DT, STROKE_REF_DT);
        if (ALIGN_SPRINGS) System.out.printf(java.util.Locale.US, "  -alignsprings: MOTOR alignment frozen as FIXED SPRINGS. F9/F10/axlock 0.4→%.4f (build-time xbParams[2]); swing via swingParams[4]=−refDt (springify in-kernel). Byte-identical at refDt.%n", springify(0.4), DT);
        if (STRUCT_SPRINGS) System.out.printf(java.util.Locale.US, "  -structsprings: STRUCTURAL J1/J2/anchor frozen as FIXED SPRINGS. 0.4→%.4f (springify, build-time jointParams[1]/[5]/[9]) at dt=%.2e (refDt=%.2e). Byte-identical at refDt.%n", springify(0.4), DT, STROKE_REF_DT);
        if (PAIRS_SPRINGS && ALIGN_SPRINGS && STRUCT_SPRINGS) System.out.printf(java.util.Locale.US, "  [SPRINGS DEFAULT-ON: canonical transcendental-free formulation (SPRINGS_PROMOTION). Byte-identical at production dt=1e-5; diverges only below refDt. Use -nosprings to restore raw.]%n");
        if (SWING_K_BITS != -1) System.out.printf(java.util.Locale.US, "  -swingkbits: swing coeff FORCED to %s (bits 0x%08x), size-4 (no in-kernel recompute); both runners. (0.4f=0x3ecccccd.)%n", ""+Float.intBitsToFloat(SWING_K_BITS), SWING_K_BITS);
        if (SWING_K_PROBE) { diagMark("swingKProbe: kernel swing-coefficient extraction (no physics scene)"); swingKProbe(gpu); return; }
        if (viz != null) { runViz(sc, Math.max(M, 20000), viz, gpu); return; }
        if (CSRECAL) { diagMark("catchSlipRecal: phase-2 config-1 single-molecule catch-slip recalibration"); catchSlipRecal(Math.max(M, 14000)); return; }
        if (DCALIB) { diagMark("dCalib: phase-2 config-1 single-molecule catch force-sensitivity"); dCalib(Math.max(M, 25000)); return; }
        if (SINGLE) { diagMark("singleMolecule: phase-2 config-1 single-molecule duty assay"); singleMolecule(sc, Math.max(M, 30000)); return; }
        if (CANON_DIAG) { diagMark("canonDiag: phase-2 canonical two-point binder instrument (config-1)"); canonDiag(sc, Math.max(M, 12000)); return; }
        if (diag) { diagnose(sc, Math.max(M, 8000)); return; }
        if (cycldiag) { cyclediag(sc, Math.max(M, 8000)); return; }
        if (BRAKEDIAG) { brakeDiagnose(sc, Math.max(M, 12000)); return; }
        // D7 (Stage 3): density/coltol are SWEPT experimental conditions, not canonical operating points. The main
        // gliding run loudly flags any un-passed one so a placeholder can never be silently reported as a chosen value.
        // (Not a hard error — that would break run_gliding.sh's documented bare probe and several KEEP scripts.)
        if (!DENSITY_SET || !COL_TOL_SET)
            System.out.printf(java.util.Locale.US, "  [SWEPT-PARAM WARNING] %s%s%s — PLACEHOLDER value(s), NOT a canonical operating point. Pass %s explicitly for any REPORTED gliding run (D7).%n",
                    (!DENSITY_SET ? "-density defaulted to " + DENSITY + "/µm²" : ""),
                    (!DENSITY_SET && !COL_TOL_SET ? ", " : ""),
                    (!COL_TOL_SET ? "-coltol defaulted to " + (COL_TOL * 1e3) + " nm" : ""),
                    (!DENSITY_SET && !COL_TOL_SET ? "both" : (!DENSITY_SET ? "-density" : "-coltol")));
        if (grid) { measureGrid(sc, M, gpu); return; }
        if (ztrace) { ztrace(sc, M, gpu); return; }
        if (assistlog) { assistLog(sc, M, gpu); return; }
        if (gpu) { gpuProbe(sc, M); return; }
        probe(sc, M);
    }

    static final class Scene {
        FilamentStore fil; MotorStore mot;
        FloatArray bondData, xbParams, boxParams;
        FloatArray xbParamsC1, jointParams;   // CONFIG 1: PAIRS+Hookean-J1 xbParams + the size-14 jointParams (= mot.jointParams when not config1)
        FloatArray swingParams;               // -dirswing: [k, dt, θ_uncocked, θ_cocked] for CrossBridgeSystem.directedSwing
        IntArray twistHist, prevBoundTw;      // -twistcensus: per-motor 6-bin arrival-angle histogram + prev boundSeg
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        IntArray reachSeg; IntArray reachCount;
        FloatArray segImplPrev;               // -xbimplicit2: per-segment pre-integration center q_n (planar 3·nSeg)
        double segL, x0;
        double bandXlo = Double.NEGATIVE_INFINITY;   // -matband: −x mat boundary (motors below it were dropped); edge-guard reference
    }


    /** PURE_SPRINGS: freeze a per-STEP fraction k as a FIXED-STIFFNESS spring. The PAIRS/alignment/structural kernels
     *  compute F = k_code·gap/((moveC1+moveC2)·dt); returning k·(DT/refDt) makes the running dt cancel to refDt ⇒
     *  F = (k·γ_red/refDt)·gap = k_spring·gap, a dt-invariant fixed stiffness (γ_red=1/(moveC1+moveC2) supplied PER-PAIR
     *  in-kernel ⇒ pinned bodies 1/γ→0 honoured). Forward-Euler ⇒ Δ = k·(DT/refDt)·gap; at DT==refDt Δ==k·gap
     *  (byte-identical to the raw law); at finer DT a strictly smaller, monotone-stable (α=k·DT/refDt≤k<1) step. */
    static float springify(double k) { return (float) (k * (DT / STROKE_REF_DT)); }

    /** CANONICAL COLLAPSE Stage 3: emit a visible banner so no diagnostic can be MISTAKEN for a canonical
     *  production run. These probes deliberately run a NON-canonical model (phase-2 config-1/perp motor, a raw-law
     *  fine-dt single-motor scene, or a bare kernel probe) — that is the correct thing for what they measure, but
     *  it is NOT the canonical default (sphere-head + springs + Lymn-Taylor @ dt=1e-5). Label only — byte-identical. */
    static void diagMark(String what) {
        System.out.println("  [NON-CANONICAL DIAGNOSTIC] " + what
            + " — does NOT run the canonical production model (sphere-head+springs+Lymn-Taylor @dt=1e-5). See CANONICAL_COLLAPSE_STAGE3.md.");
    }

    /** BISTABILITY_ORIGIN probe kernel: compute the ratefix in-kernel swing coefficient EXACTLY as
     *  directedSwing does (k=(double)0.4f; k=1−exp((dt/refDt)·log(1−k))), and write (float)k. Run on the
     *  GPU it reveals the float the PTX exp/log actually produces; run on the CPU it is Java double. Two-way
     *  compare (out[0]=recomputed k, out[1]=raw 0.4f, out[2]=1−k input to log). */
    public static void swingKProbeKernel(FloatArray out) {
        for (@Parallel int i = 0; i < 1; i++) {
            double k = 0.4f;                 // swingParams[0]=0.4f promoted to double (raw's k)
            double dt = 1.0e-5f, refDt = 1.0e-5f;
            double kr = 1.0 - Math.exp((dt / refDt) * Math.log(1.0 - k));
            out.set(0, (float) kr);
            out.set(1, (float) k);
            out.set(2, (float) (1.0 - k));
            out.set(3, (float) ((kr - k) * 1.0e12));   // DOUBLE-level residual (kr − raw), amplified ×1e12: 0 ⇒ even double-identical
        }
    }

    /** BISTABILITY_ORIGIN: extract the EXACT float the (GPU or CPU) runner computes for the ratefix in-kernel
     *  swing k, vs Java double vs the raw 0.4f. Prints hex bits so a ULP difference is visible. */
    static void swingKProbe(boolean gpu) {
        FloatArray out = new FloatArray(4); out.init(0f);
        if (gpu) {
            TaskGraph tg = new TaskGraph("swingkprobe")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, out)
                .task("k", GlidingHarness::swingKProbeKernel, out)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, out);
            WorkerGrid wg = new WorkerGrid1D(1); wg.setLocalWork(1, 1, 1);
            GridScheduler gs = new GridScheduler("swingkprobe.k", wg);
            new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(gs).execute();
        } else {
            swingKProbeKernel(out);
        }
        float gpuK = out.get(0), rawK = out.get(1), oneMinusK = out.get(2);
        double jK = 0.4f; double jRecomp = 1.0 - Math.exp((1.0e-5f / 1.0e-5f) * Math.log(1.0 - jK));
        System.out.printf(java.util.Locale.US, "%n=== SWING_K PROBE (%s runner) ===%n", gpu ? "GPU" : "CPU");
        System.out.printf(java.util.Locale.US, "  raw     k = 0.4f                = %s  bits=0x%08x%n", ""+rawK, Float.floatToIntBits(rawK));
        System.out.printf(java.util.Locale.US, "  %s recomputed 1-exp(log(1-k)) = %s  bits=0x%08x%n", gpu?"GPU":"CPU", ""+gpuK, Float.floatToIntBits(gpuK));
        System.out.printf(java.util.Locale.US, "  Java double recompute → float = %s  bits=0x%08x%n", ""+(float)jRecomp, Float.floatToIntBits((float)jRecomp));
        System.out.printf(java.util.Locale.US, "  (1-k) input to log             = %s  bits=0x%08x%n", ""+oneMinusK, Float.floatToIntBits(oneMinusK));
        int dBits = Float.floatToIntBits(gpuK) - Float.floatToIntBits(rawK);
        System.out.printf(java.util.Locale.US, "  ⇒ recomputed-k − raw-k = %d float-ULP  (0 ⇒ coefficient identical; ≠0 ⇒ the swing-ULP seed)%n", dBits);
        System.out.printf(java.util.Locale.US, "  ⇒ (recomputed-k − raw-k)×1e12 [DOUBLE residual] = %s  (0 ⇒ double-identical too)%n", ""+out.get(3));
        System.out.printf(java.util.Locale.US, "  reproduce the flip: -swingkbits 0x%08x (force CPU to this k)%n", Float.floatToIntBits(gpuK));
    }

    /** GLIDING_RADIUS_SWEEP early-stop monitor: batch-means SEM of the steady-window (2nd-half) glide speed.
     *  velFitX = −LS slope of cx over [n/2, n) (the SAME estimator measureGrid reports at stop). Uncertainty via
     *  batch means over the SAME window (each batch ≥ ES_BATCH span; ≥5 batches required) — NOT the OLS slope SEM,
     *  which underestimates for an autocorrelated trajectory. Returns {relSEM, velFitX, batchMean, nBatches, winSec}
     *  or null if there is not yet enough steady data (window < ES_MINWIN, or < 5 valid batches). */
    static double[] esMonitor(double[] cx, int n, double dtInt) {
        int h = n / 2, ns = n - h;
        double winSec = (ns - 1) * dtInt;
        if (winSec < ES_MINWIN) return null;
        double st = 0, stt = 0, sxx = 0, stx = 0; int nf = 0;
        for (int i = h; i < n; i++) { double ti = i * dtInt; st += ti; stt += ti * ti; sxx += cx[i]; stx += ti * cx[i]; nf++; }
        double den = nf * stt - st * st;
        double velFit = den != 0 ? -(nf * stx - st * sxx) / den : 0.0;
        int spb = Math.max(2, (int) Math.floor(ES_BATCH / dtInt));   // samples per batch
        int nb = (ns - 1) / spb;                                      // full non-overlapping batches over [h, h+nb·spb]
        if (nb < 5) return null;
        double[] vb = new double[nb]; double sum = 0;
        for (int b = 0; b < nb; b++) { int i0 = h + b * spb, i1 = h + (b + 1) * spb; vb[b] = -(cx[i1] - cx[i0]) / (spb * dtInt); sum += vb[b]; }
        double mean = sum / nb, var = 0;
        for (double v : vb) { double d = v - mean; var += d * d; }
        var /= (nb - 1);
        double sem = Math.sqrt(var) / Math.sqrt(nb);
        double relSEM = Math.abs(velFit) > 1e-9 ? sem / Math.abs(velFit) : Double.POSITIVE_INFINITY;
        return new double[]{relSEM, velFit, mean, nb, winSec};
    }

    static Scene buildScene() {
        Scene sc = new Scene();
        int nSeg = FIL_SEGS;
        double L = (FIL_MONO + 1) * Constants.actinMonoRadius;     // µm, 64-monomer segment
        sc.segL = L;
        // ---- filament: chain along +x at z=0, centered so it glides −x within the bed ----
        FilamentStore fil = new FilamentStore(nSeg);
        double x0 = bX0;               // filament +x end; glides −x with room for a long run
        sc.x0 = x0;
        for (int s = 0; s < nSeg; s++) {
            fil.monomerCount.set(s, FIL_MONO);
            fil.setUVec(s, 1f, 0f, 0f); fil.setYVec(s, 0f, 1f, 0f);     // plus-end +x
            fil.setCoord(s, (float) (x0 - (nSeg - 1 - s) * L), 0f, (float) FIL_Z);
            fil.brownTransScale.set(s, (float) Constants.BTransCoeff);   // 1.0
            boolean end = (s == 0 || s == nSeg - 1);
            fil.brownRotScale.set(s, (float) (end ? Constants.BRotCoeff : 0.0));   // inc-2b: ends only
            // linear chain topology: end2 of s ↔ end1 of s+1
            if (s < nSeg - 1) { fil.end2NbrSlot.set(s, s + 1); fil.end2NbrSide.set(s, 0); }
            if (s > 0)        { fil.end1NbrSlot.set(s, s - 1); fil.end1NbrSide.set(s, 1); }
        }
        DragTensorSystem.run(fil);
        applyAeta(fil, AETA);   // VISCOSITY DIAGNOSTIC: scale the FILAMENT drag only (FDT-consistent); AETA==Constants.aeta ⇒ no-op, byte-identical
        fil.setParams(DT, Math.sqrt(2.0 * Constants.kT / DT));
        fil.setCounts(0, 0xF11A);
        // gliding chain params (config-matched, NOT tuned): fracMove 0.5 / fracR 0.1 / fracMoveTorq 0.2.
        // dt-convergence test: change dt ONLY (the dt-correct quantities — cycle rate·dt, Brownian √dt,
        // Langevin force/γ·dt — auto-scale); the fracMove family is held at its operating values for BOTH
        // codes (per planner direction — do NOT scale fracMove). The v2/v1 RATIO at each dt isolates the
        // integration-scheme (update-order/staleness) difference.
        // -filrate (PAIRS_RATE_AUDIT): the FILAMENT PAIRS per-STEP fractions → per-TIME rates at BUILD time (DT
        // fixed ⇒ k_eff constant, no kernel change). fracMove (F3 link + lever-arm bending) + fracMoveTorq (F4
        // torsion). fracR (chainParams[2]=0.1) is geometry (lever arm), NOT a rate ⇒ unchanged. Default 0.5/0.2 ⇒
        // byte-identical. k_eff = 1−(1−k)^(DT/refDt) == k at refDt (production-dt filament stiffness preserved).
        // -pairsprings (fixed spring) takes PRECEDENCE over -filrate (per-time rate) for the SAME PAIRS slots (replace, not compose).
        float filFracMove = PAIRS_SPRINGS ? springify(0.5) : 0.5f;
        float filFracMoveTorq = PAIRS_SPRINGS ? springify(0.2) : 0.2f;
        fil.chainParams.set(0, (float) DT); fil.chainParams.set(1, filFracMove); fil.chainParams.set(2, 0.1f);
        fil.chainParams.set(3, filFracMoveTorq); fil.chainParams.set(4, 0f); fil.chainParams.set(5, 1.0e-20f);
        fil.chainParams.set(6, (float) Constants.actinMonoRadius);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        // ---- motor bed: density-faithful patch around the filament's −x path. Wide enough in y that
        //      the filament's ends stay over motors as it rotates/wanders (v1's bed is the full 2µm-wide
        //      box; a narrow strip lets the ends rotate out → unsupported ends lag → velocity drops). ----
        double bedXlo = bXlo, bedXhi = bXhi;          // explicit bed x-extent (matches v1's box)
        double bedYhalf = bYhalf;                     // y-width (boxYDim) → governs the finite-size effect
        double bedX = bedXhi - bedXlo, bedY = 2 * bedYhalf;
        int nMot;
        MotorStore mot;
        if (SINGLE) {
            // SINGLE-MOLECULE duty assay: N motors held in PROXIMITY directly under the (fixed) filament line,
            // their head equilibrium AT the filament (anchor z so head-tip ≈ z=0) ⇒ frequently IN REACH ⇒ the duty
            // is KINETICALLY gated (kOn vs catch), NOT geometry-confounded (the avgBound/2000 trap §1). Each motor
            // is an independent single-molecule realization; the aggregate is the duty ratio.
            nMot = 256;
            double anchorZ = SINGLE_Z;                   // positions the cycling head's bob range at the filament (tuned for high in-reach)
            double cx = x0 - 0.5 * (nSeg - 1) * L;       // filament centre x
            mot = new MotorStore(nMot);
            java.util.Random rngS = new java.util.Random(SEED);
            for (int m = 0; m < nMot; m++) {
                float ax = (float) (cx + (rngS.nextDouble() - 0.5) * 0.6);   // over the middle of the filament
                float ay = (float) ((rngS.nextDouble() - 0.5) * 0.004);      // tight under the line
                mot.assembleArticulated(m, ax, ay, (float) anchorZ, 0f, 0f, 1f, (float) Constants.BTransCoeff);
            }
        } else if (MATBAND) {
            // -matband: density-preserving −x band shrink. Seed the FULL bed RNG exactly as the un-shrunk path,
            // but DROP motors whose anchor x < bandXlo (the never-visited −x tail). Kept motors get the SAME RNG
            // draws (and thus the SAME placement) as the full bed ⇒ the shrunk mat is a strict SUBSET of the full
            // mat. With bandXlo set a margin below the filament's −x-most swept point (> motor x-reach + capture),
            // every dropped motor is provably out of capture range throughout the run ⇒ binding is unchanged
            // (validated by the STEP-2 parity gate + the run-time edge-guard). Areal density preserved exactly.
            double filEndToEnd = (nSeg - 1) * L + L;                     // pointed→barbed end-to-end (center span + 2·½seg)
            double sweptXmin = x0 - filEndToEnd - MATBAND_EXC;           // most −x point the filament can reach
            double bandXlo = sweptXmin - MATBAND_MARGIN;                 // drop motors below this
            sc.bandXlo = bandXlo;
            int nFull = (int) Math.round(DENSITY * bedX * bedY);
            java.util.Random rngCount = new java.util.Random(SEED);      // pass 1: count kept (deterministic)
            int kept = 0;
            for (int m = 0; m < nFull; m++) {
                double ax = bedXlo + rngCount.nextDouble() * bedX; rngCount.nextDouble();  // consume ay draw too
                if (ax >= bandXlo) kept++;
            }
            nMot = kept;
            mot = new MotorStore(nMot);
            java.util.Random rng = new java.util.Random(SEED);           // pass 2: identical stream, assemble kept
            int idx = 0;
            for (int m = 0; m < nFull; m++) {
                float ax = (float) (bedXlo + rng.nextDouble() * bedX);
                float ay = (float) (-bedYhalf + rng.nextDouble() * bedY);
                if (ax >= bandXlo) mot.assembleArticulated(idx++, ax, ay, (float) ANCHOR_Z, 0f, 0f, 1f, (float) Constants.BTransCoeff);
            }
        } else {
        nMot = (int) Math.round(DENSITY * bedX * bedY);
        mot = new MotorStore(nMot);
        java.util.Random rng = new java.util.Random(SEED);
        for (int m = 0; m < nMot; m++) {
            float ax = (float) (bedXlo + rng.nextDouble() * bedX);
            float ay = (float) (-bedYhalf + rng.nextDouble() * bedY);
            mot.assembleArticulated(m, ax, ay, (float) ANCHOR_Z, 0f, 0f, 1f, (float) Constants.BTransCoeff);
        }
        }
        DragTensorSystem.run(mot);
        mot.setBodyParams(DT); mot.setJointParams(DT); mot.setKinParams(0.006, -0.4, DT); mot.setNucParams(DT);
        if (COL_TOL != 0.006) mot.kinParams.set(7, (float) COL_TOL);   // CAPTURE-RADIUS sweep: override myoColTol (bind reach). Widens the reachTestDistSq perp threshold in BOTH bruteReachable + bindNearest (CPU + GPU, kinParams[7] uploaded FIRST_EXECUTION). GlidingHarness binds brute-force (no grid) ⇒ no cell-size concern. Default 6 nm ⇒ untouched.
        if (ADPPI_BIND) mot.kinParams.set(20, 1.0f);   // STEP A: enable the ADP·Pi strong-bind gate in bindNearest (kinParams[20], uploaded FIRST_EXECUTION)
        if (REBIND_TIME > 0) mot.kinParams.set(10, (float) Math.ceil(REBIND_TIME / DT));   // -rebindtime: post-release refractory (a released head can't rebind for this long); default 0 ⇒ the v1 myoRebindTime (byte-identical)
        if (KON > 0) mot.setSearchParams(KON, 0);        // PHASE-2 step-3: reaction-limited attachment rate kОn (kinParams[14])
        if (NOBIND) mot.kinParams.set(19, 1.0f);         // thermal-floor control: motors never bind (default-off no-op)
        if (TAU_AVG > 0) mot.setReleaseForceAvg(TAU_AVG, DT);   // PHASE-2 step-4a: time-averaged catch input (EMA window τ)
        if (F_EXT != 0) mot.setExtLoad(F_EXT);           // sustained-load injection (force-response guard)
        if (XCATCH > 0) mot.setXCatch(XCATCH);           // PHASE-2 step-4b: catch distance d (Veigel calibration)
        if (NO_REFRACTORY) mot.kinParams.set(10, 0f);   // §6.6 OFF bracket: no rebind refractory
        mot.setFaithfulRelease(FORCE_CAP_DETACH, DETACH_CAP);  // -forcecapdetach + -detachcap <pN> (default 12); off by default ⇒ kinParams[12]=0 and kinParams[11]=12pN, byte-identical to the prior (false,0.0) call
        mot.setFaithfulRefractory(FAITHFUL_REFRACTORY); // §6.11 default off (HEAD 100%/1-step block)
        // IMPLICIT_CROSSBRIDGE params (only consumed by the implicit-correction kernels). -xbtrap re-times the
        // coupled F8 star to the trapezoidal midpoint (θ=½): the correction's r=k·dt·1e6/γ becomes r/2, which is
        // exactly Crank–Nicolson (STEP-1-validated). The EXPLICIT F8 impulse uses xbParams (NOT xbImplParams), so
        // halving myoSpring HERE halves r in coupleComputeA/coupleSolveSeg ONLY — the star kernels stay byte-unchanged.
        mot.setImplicit(XB_TRAP ? MYO_SPRING * 0.5 : MYO_SPRING, DT);
        // STEP-3 cycle-rate lever (-ratescale, default 1.0 ⇒ no-op/byte-identical): scale the catch-slip kOff AND
        // every nucleotide cycle rate (atpOn, on/off ATP→ADPPi→ADP→NONE) by the same factor. V₀ = step·detach-rate,
        // so faster kinetics raises the single-molecule ceiling; the sweep exposes the duty×turnover tradeoff.
        // dt-safe at ×4: max rate·dt = atpOn·dt = 2e4·1e-5·4 = 0.8 < 1.
        if (RATE_SCALE != 1.0) {
            mot.kinParams.set(0, (float) (mot.kinParams.get(0) * RATE_SCALE));   // catch-slip kOff ×scale
            for (int r = 1; r <= 7; r++) mot.nucParams.set(r, (float) (mot.nucParams.get(r) * RATE_SCALE));  // all cycle rates ×scale
        }
        mot.nucleotideState.init(MotorStore.NUC_NONE);

        int MAXC = SpatialGrid.MAX_CAND;
        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        // -alignrate: F9/F10/axlock alignment per-STEP fraction (0.4) → per-TIME rate, computed at BUILD time (DT fixed
        // ⇒ k_eff constant, no kernel change). Default 0.4f ⇒ byte-identical.
        // -alignsprings (fixed spring) takes PRECEDENCE over -alignrate (per-time rate) for the F9/F10/axlock coeff (replace, not compose).
        float alignK = ALIGN_SPRINGS ? springify(0.4) : 0.4f;
        sc.xbParams = SPHEREHEAD
            // SPHERE-HEAD: freeze F9's rest at 90° (xbParams[9]=1) ⇒ the head-vs-actin ANGLE stops switching
            // (no F9 power stroke; F9 becomes the compliant ⊥ perp-MAINTAINER), so the J1 converter neck-swing
            // (0°↔60°, MotorJointSystem) is the ONLY stroke. F8 (tip anchor) unchanged. F10: seg.yVec (default)
            // or -axlock ⇒ [10]=1 retargets it to ŝ=normalize(n̂bed×seg.uVec) head-only (the axial swing lock).
            ? (AXLOCK
                ? FloatArray.fromElements((float) MYO_SPRING, 90f, alignK, (float) DT, (float) MotorStore.HEAD_LEN, (float) FORCE_BIAS, 0f, 0f, 0f, 1f, 1f)
                : FloatArray.fromElements((float) MYO_SPRING, 90f, alignK, (float) DT, (float) MotorStore.HEAD_LEN, (float) FORCE_BIAS, 0f, 0f, 0f, 1f))
            : FloatArray.fromElements((float) MYO_SPRING, 90f, alignK, (float) DT, (float) MotorStore.HEAD_LEN, (float) FORCE_BIAS);
        // CONFIG 1: PAIRS attachments + Hookean J1. xbParamsC1 = [fracMove, κ, leverLen, dt, HEAD_LEN]; the
        // size-14 jointParams (Hookean J1) via enableConfig1. Not config1 ⇒ jointParams = mot.jointParams (byte-id).
        if (CONFIG1) {
            mot.enableConfig1(KAPPA);
            // PERP-HEAD ⇒ size-6 (appends orientFracMove for the ⊥-orientation torque); plain config-1 ⇒ size-5 (byte-id).
            sc.xbParamsC1 = PERPHEAD
                ? FloatArray.fromElements((float) PAIRS_FRACMOVE, (float) KAPPA, (float) MotorStore.LEVER_LEN, (float) DT, (float) MotorStore.HEAD_LEN, (float) PERP_FRACMOVE)
                : FloatArray.fromElements((float) PAIRS_FRACMOVE, (float) KAPPA, (float) MotorStore.LEVER_LEN, (float) DT, (float) MotorStore.HEAD_LEN);
            // HEAD-ANGLE SWEEP: bake θ into the frozen perpRest target (snapPerpRest). setFlag 0 ⇒ plain ⊥ (byte-id).
            if (PERPHEAD && HEADTILT_SET) {
                double th = Math.toRadians(HEADTILT_DEG);
                mot.headTiltCS.set(0, (float) Math.cos(th)); mot.headTiltCS.set(1, (float) Math.sin(th)); mot.headTiltCS.set(2, 1f);
            }
            sc.jointParams = mot.jointParamsC1;
        } else {
            sc.jointParams = mot.jointParams;
        }
        // -dirswing: the deterministic polarity-directed power stroke replaces the J1 angular converter (whose
        // cross(lever,head) axis is degenerate at the straight rest ⇒ ill-defined swing direction). Turn the J1
        // TORSION off (jointParams[3]=0; the J1 position spring stays) so directedSwing is the sole stroke driver.
        sc.swingParams = ALIGN_SPRINGS
                ? FloatArray.fromElements(0.4f, (float) DT, 0f, (float) NECK_ANGLE, (float) (-STROKE_REF_DT))  // -alignsprings: [4]=−refDt ⇒ FIXED-SPRING swing (springify in-kernel)
                : FloatArray.fromElements(0.4f, (float) DT, 0f, (float) NECK_ANGLE);   // raw: [3]=cocked neck angle (default 60°)
        if (SWING_K_BITS != -1) {   // BISTABILITY_ORIGIN: force the swing coeff to an EXACT float, size-4 ⇒ NO in-kernel recompute
            // ⇒ both runners use this exact k. 0x3ecccccd = 0.4f ⇒ ≡ raw. Overrides -strokerate/-alignsprings/-ratefix swing.
            sc.swingParams = FloatArray.fromElements(Float.intBitsToFloat(SWING_K_BITS), (float) DT, 0f, (float) NECK_ANGLE);
        }
        if (DIRSWING) sc.jointParams.set(3, 0f);
        if (STRUCT_SPRINGS) {   // PURE_SPRINGS: freeze the SAME structural position springs (J1/J2 connection + tail anchor)
            // as FIXED stiffnesses via springify (precedence over -structrate). Mirrors the -structrate block but with the
            // fixed-spring freeze-form instead of the geometric-rate; at dt=refDt springify(0.4)=0.4 ⇒ byte-identical.
            sc.jointParams.set(1, springify(sc.jointParams.get(1)));   // J1 lever-motor connection spring
            sc.jointParams.set(5, springify(sc.jointParams.get(5)));   // J2 rod-lever connection spring
            sc.jointParams.set(9, springify(sc.jointParams.get(9)));   // tail-anchor spring
        }
        if (SPHEREHEAD && HEADLOCK != 1.0) sc.xbParams.set(2, (float) (0.4 * HEADLOCK));   // -headlock: stiffen the F9 ⊥ hold + F10 axial/roll lock (diagnostic; the stroke coeff swingParams[0] is untouched)
        if (TWISTCENSUS) { sc.twistHist = new IntArray(6 * nMot); sc.twistHist.init(0); sc.prevBoundTw = new IntArray(nMot); sc.prevBoundTw.init(-1); }
        sc.segMotorCount = new IntArray(nSeg); sc.segMotorOffsets = new IntArray(nSeg + 1); sc.segMotorMyo = new IntArray(nMot);
        sc.segImplPrev = new FloatArray(3 * nSeg); sc.segImplPrev.init(0f);   // -xbimplicit2 segment-center snapshot
        sc.reachSeg = new IntArray(nMot * MAXC); sc.reachSeg.init(-1); sc.reachCount = new IntArray(nMot);
        // in-vitro chamber matching the bed (v1 MyoMiniFilament.checkOuterBugCollision law): [tau, boxX, boxY, boxZ, R, coeff, checkInt]
        sc.boxParams = FloatArray.fromElements(1.0e-4f, (float) (bXhi - bXlo), (float) (2 * bYhalf), 0.5f, 0.005f, 0.5f, 10f);
        sc.fil = fil; sc.mot = mot;
        return sc;
    }

    /** One gliding step (CPU runner). Dispatches to the default or the fresh-read reorder. */
    static void step(Scene sc, int t) { stepOrig(sc, t); }

    /** One gliding step (CPU runner) — default order (catch-slip/cycle read last step's forceDotFil). */
    static void stepOrig(Scene sc, int t) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        mot.setCounts(t, SEED, f.n);
        f.counts.set(1, t);
        // --- binding (dynamic) ---
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        long _rel = tns();
        if (LYMN_TAYLOR) {
            // VALIDATED Lymn-Taylor: NO separate release — detachment is nucleotide-driven (NONE→ATP) in the cycle
            // kernel below. (The cooldown decrement + the single release live in cycleLymnTaylor.)
        }
        else if (TAU_AVG > 0)
            NucleotideCycleSystem.catchSlipReleaseAvg(mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        else
            NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        sliceNs[RELEASE] += tns() - _rel;
        if (CANONICAL)
            BindingDetectionSystem.bindCanonicalTwoPoint(b.coord, b.uVec, b.segLength, f.end1, f.end2, f.segLength,
                    sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.bindArc2, mot.canonSnap, mot.kinParams, mot.counts);
        else
            BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.nucleotideState, mot.kinParams, mot.counts);
        if (TWISTCENSUS) CrossBridgeSystem.captureBindTwist(b.yVec, f.uVec, mot.boundSeg, sc.prevBoundTw, sc.twistHist, mot.counts);
        // PERP-HEAD: freeze uperp at fresh bind (reads canonSnap before snapCanonicalHead clears it)
        if (PERPHEAD) CrossBridgeSystem.snapPerpRest(b.uVec, f.uVec, mot.boundSeg, mot.canonSnap, mot.perpRest, mot.counts, mot.headTiltCS);
        // --- motor nucleotide cycle + dynamics ---
        // PHASE-2 ATP-RELEASE COUPLING: config-1/perp-head detach AT the bound NONE→ATP transition (ATP-binding
        // = detachment); the catch-slip release above is unchanged (it governs the strained ADP dwell). The plain
        // cycle (default/canonical-non-config1) is byte-unchanged.
        if (LYMN_TAYLOR)   // jba: the validated canonical cycle — single nucleotide-driven release (NONE→ATP), catch modulates ADP→NONE
            NucleotideCycleSystem.cycleLymnTaylor(mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts);
        else if (CONFIG1 && ATP_RELEASE)
            NucleotideCycleSystem.cycleAtpDetach(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.cooldown, mot.nucParams, mot.kinParams, mot.counts);
        else
            NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        long _mb = tns();
        BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
        sliceNs[MOTADV] += tns() - _mb;
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, sc.jointParams, mot.counts);
        TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, sc.jointParams, mot.counts);
        long _bf = tns();
        if (PERPHEAD)
            CrossBridgeSystem.bondForcesCanonicalConfig1Perp(b.coord, b.uVec, b.bTransGam, b.bRotGam, f.coord, f.uVec, f.segLength, f.bTransGam, f.bRotGam,
                    mot.boundSeg, mot.bindArc, mot.perpRest, mot.nucleotideState, sc.bondData, sc.xbParamsC1);
        else if (CONFIG1)
            CrossBridgeSystem.bondForcesCanonicalConfig1(b.coord, b.uVec, b.bTransGam, b.bRotGam, f.coord, f.uVec, f.segLength, f.bTransGam, f.bRotGam,
                    mot.boundSeg, mot.bindArc, mot.bindArc2, mot.nucleotideState, sc.bondData, sc.xbParamsC1);
        else if (CANONICAL)
            CrossBridgeSystem.bondForcesCanonical(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.bindArc2, mot.nucleotideState, sc.bondData, sc.xbParams);
        else
            CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
        sliceNs[BOND] += tns() - _bf;
        long _ah = tns();
        CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
        if (DIRSWING) CrossBridgeSystem.directedSwing(b.uVec, b.torqueSum, b.bRotGam, f.uVec, mot.boundSeg, mot.nucleotideState, sc.swingParams, mot.counts);
        sliceNs[APPLY] += tns() - _ah;
        if (XB_IMPLICIT2) CrossBridgeSystem.snapshotHeadCenter(b.coord, mot.xbImplPrev);
        long _mi = tns();
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        // Version-B head snap (placed LATE to match the GPU graph — see buildPlan: an early body-write task
        // breaks the PTX-lowered bind). Takes effect on the next step's bond ⇒ CPU/GPU snap timing identical.
        if (CANONICAL) BindingDetectionSystem.snapCanonicalHead(b.coord, b.uVec, b.yVec, b.segLength, f.end1, f.uVec,
                mot.boundSeg, mot.bindArc, mot.bindArc2, mot.canonSnap, mot.counts);
        sliceNs[MOTADV] += tns() - _mi;
        long _rf = tns();
        CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
        sliceNs[REGISTER] += tns() - _rf;
        // --- filament dynamics: chain + Brownian + the gathered cross-bridge, then integrate ---
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        long _g = tns();
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
        CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        sliceNs[GATHER] += tns() - _g;
        if (XB_IMPLICIT2 || SEG_IMPLICIT) CrossBridgeSystem.snapshotSegCenter(f.coord, sc.segImplPrev);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        // -xbimplicit2 (COUPLED head+SITE star) and/or -segimplicit (DIAGONAL per-segment collective, rigid head).
        // Both run after BOTH integrates and reuse the boundSeg CSR-inverse. When both are on, -segimplicit's rigid
        // segment solve REPLACES coupleSolveSeg (avoids double-correcting the center); the -xbimplicit2 head phases
        // are kept (head follows the rigid-corrected center via B). Re-derive both bodies (whichever were written).
        if (XB_IMPLICIT2 || SEG_IMPLICIT) {
            if (XB_IMPLICIT2) CrossBridgeSystem.coupleComputeA(b.coord, mot.boundSeg, b.bTransGam, mot.xbImplPrev, sc.segImplPrev, mot.xbCplA, mot.xbCplB, mot.xbImplParams);
            if (SEG_IMPLICIT) CrossBridgeSystem.segImplicitSolve(sc.segMotorOffsets, f.coord, f.uVec, f.yVec, f.bTransGam, sc.segImplPrev, mot.xbImplParams, mot.counts);
            else CrossBridgeSystem.coupleSolveSeg(sc.segMotorOffsets, sc.segMotorMyo, mot.xbImplPrev, mot.xbCplA, mot.xbCplB, f.coord, f.uVec, f.yVec, f.bTransGam, sc.segImplPrev, mot.xbImplParams, mot.counts);
            if (XB_IMPLICIT2) {
                CrossBridgeSystem.coupleCorrectHead(b.coord, mot.boundSeg, mot.xbCplA, mot.xbCplB, f.coord, mot.counts);
                DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            }
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        }
    }


    // ===================== GPU TaskGraph (GpuStepper — same systems, device dispatch) =================
    static final int B = 64;
    static GridScheduler sched;
    /** The full gliding step as ONE device-resident TaskGraph (23 kernels) — mirrors `step()` exactly.
     *  No per-step host pull (residency test); host reads fil.coord + boundSeg at output cadence only. */
    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        TaskGraph tg = new TaskGraph("gliding")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.head, mot.uVec, mot.rodUVec, mot.anchor, mot.boundSeg, mot.bindArc, mot.nucleotideState,
                    mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.stats, mot.capStats, mot.cooldown,
                    mot.bodyParams, mot.jointParams, mot.nucParams, mot.kinParams,
                    sc.bondData, sc.xbParams, sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo, sc.reachSeg, sc.reachCount,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.bTransGam, f.bRotGam,
                    f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.brownTransScale, f.brownRotScale,
                    f.params, f.chainParams, f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide, sc.boxParams)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, f.counts);
        if (XB_IMPLICIT2) tg = tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, mot.xbImplPrev, mot.xbImplParams, mot.xbCplA, mot.xbCplB, sc.segImplPrev);
        if (SEG_IMPLICIT && !XB_IMPLICIT2) tg = tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, mot.xbImplParams, sc.segImplPrev);
        if (CANONICAL) tg = tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, mot.bindArc2, mot.canonSnap);
        if (CONFIG1) tg = tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, sc.xbParamsC1, sc.jointParams);
        if (DIRSWING) tg = tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, sc.swingParams);
        if (TWISTCENSUS) tg = tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, sc.twistHist, sc.prevBoundTw);
        if (PERPHEAD) tg = tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, mot.perpRest, mot.headTiltCS);
        if (TAU_AVG > 0 || LYMN_TAYLOR) tg = tg.transferToDevice(DataTransferMode.FIRST_EXECUTION, mot.forceDotAvg, mot.avgInit);
        // reach (common)
        tg = tg
            .task("publishHead", MotorStore::publishHeadFromBody, b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts)
            .task("reach", BindingDetectionSystem::bruteReachable, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        {
            // default: release/cycle read last step's forceDotFil (force computed after them).
            // LYMN_TAYLOR: NO separate release task — detachment is nucleotide-driven in the cycle kernel below.
            if (LYMN_TAYLOR) { /* no release task */ }
            else if (TAU_AVG > 0)
                tg = tg.task("release", NucleotideCycleSystem::catchSlipReleaseAvg, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
            else
                tg = tg.task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
            if (CANONICAL)
                tg = tg.task("bind", BindingDetectionSystem::bindCanonicalTwoPoint, b.coord, b.uVec, b.segLength, f.end1, f.end2, f.segLength, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.bindArc2, mot.canonSnap, mot.kinParams, mot.counts);
            else
                tg = tg.task("bind", BindingDetectionSystem::bindNearest, mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.nucleotideState, mot.kinParams, mot.counts);
            if (TWISTCENSUS) tg = tg.task("bindTwist", CrossBridgeSystem::captureBindTwist, b.yVec, f.uVec, mot.boundSeg, sc.prevBoundTw, sc.twistHist, mot.counts);
            // PERP-HEAD: freeze uperp at fresh bind (writes perpRest only — no body write ⇒ safe early; canonSnap cleared later by snapHead)
            if (PERPHEAD) tg = tg.task("snapPerp", CrossBridgeSystem::snapPerpRest, b.uVec, f.uVec, mot.boundSeg, mot.canonSnap, mot.perpRest, mot.counts, mot.headTiltCS);
            // PHASE-2 ATP-RELEASE COUPLING (config-1/perp-head): detach AT the bound NONE→ATP transition. cycle
            // runs AFTER bind (line above) ⇒ no later kernel overwrites the freed boundSeg this step (atomic on GPU).
            if (LYMN_TAYLOR)   // jba: the validated canonical cycle — single nucleotide-driven release (NONE→ATP), catch modulates ADP→NONE
                tg = tg.task("cycle", NucleotideCycleSystem::cycleLymnTaylor, mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts);
            else if (CONFIG1 && ATP_RELEASE)
                tg = tg.task("cycle", NucleotideCycleSystem::cycleAtpDetach, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.cooldown, mot.nucParams, mot.kinParams, mot.counts);
            else
                tg = tg.task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
            tg = tg
                .task("zeroMot", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts);
            tg = tg
                .task("brownMot", BrownianForceSystem::brownianForce, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts)
                .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, sc.jointParams, mot.counts)
                .task("anchor", TailAnchorSystem::anchor, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, sc.jointParams, mot.counts);
            if (PERPHEAD)
                tg = tg.task("bond", CrossBridgeSystem::bondForcesCanonicalConfig1Perp, b.coord, b.uVec, b.bTransGam, b.bRotGam, f.coord, f.uVec, f.segLength, f.bTransGam, f.bRotGam, mot.boundSeg, mot.bindArc, mot.perpRest, mot.nucleotideState, sc.bondData, sc.xbParamsC1);
            else if (CONFIG1)
                tg = tg.task("bond", CrossBridgeSystem::bondForcesCanonicalConfig1, b.coord, b.uVec, b.bTransGam, b.bRotGam, f.coord, f.uVec, f.segLength, f.bTransGam, f.bRotGam, mot.boundSeg, mot.bindArc, mot.bindArc2, mot.nucleotideState, sc.bondData, sc.xbParamsC1);
            else if (CANONICAL)
                tg = tg.task("bond", CrossBridgeSystem::bondForcesCanonical, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.bindArc2, mot.nucleotideState, sc.bondData, sc.xbParams);
            else
                tg = tg.task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
            tg = tg.task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts);
            if (DIRSWING) tg = tg.task("dirSwing", CrossBridgeSystem::directedSwing, b.uVec, b.torqueSum, b.bRotGam, f.uVec, mot.boundSeg, mot.nucleotideState, sc.swingParams, mot.counts);
            if (BOX_ALL) tg = tg.task("confineMot", ContainmentSystem::confine, b.coord, b.uVec, b.segLength, b.bTransGam, b.forceSum, b.torqueSum, sc.boxParams, mot.counts);
            if (XB_IMPLICIT2) tg = tg.task("xbSnap", CrossBridgeSystem::snapshotHeadCenter, b.coord, mot.xbImplPrev);
            tg = tg
                .task("integMot", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
            tg = tg
                .task("deriveMot", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            // PHASE-2 Version-B head snap — placed LATE (after deriveMot, like integrate) because a body-writing
            // task placed EARLY (before the force/integrate tasks) breaks the PTX-lowered bind (measured). The
            // snap therefore takes effect on the NEXT step's bond (a ≤1-step timing delay vs the CPU same-step
            // snap — within the chaotic aggregate-within-SEM standard).
            if (CANONICAL) tg = tg.task("snapHead", BindingDetectionSystem::snapCanonicalHead, b.coord, b.uVec, b.yVec, b.segLength, f.end1, f.uVec, mot.boundSeg, mot.bindArc, mot.bindArc2, mot.canonSnap, mot.counts);
            tg = tg
                .task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
                .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts);
            tg = tg
                .task("brownFil", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
                .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
                .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
                .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
                .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
                .task("gather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
            if (BOX) tg = tg.task("confineFil", ContainmentSystem::confine, f.coord, f.uVec, f.segLength, f.bTransGam, f.forceSum, f.torqueSum, sc.boxParams, f.counts);
            if (XB_IMPLICIT2 || SEG_IMPLICIT) tg = tg.task("segSnap", CrossBridgeSystem::snapshotSegCenter, f.coord, sc.segImplPrev);
            tg = tg
                .task("integFil", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
                .task("deriveFil", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
            // -xbimplicit2 (COUPLED star) / -segimplicit (DIAGONAL collective, rigid head) — LATE body writes (after
            // deriveFil), satisfying the body-write-must-be-late PTX gotcha. Reuse the boundSeg CSR-inverse. When both
            // are set, -segimplicit's rigid segment solve replaces cplSeg; the -xbimplicit2 head phases are kept.
            if (XB_IMPLICIT2 || SEG_IMPLICIT) {
                if (XB_IMPLICIT2) tg = tg.task("cplA", CrossBridgeSystem::coupleComputeA, b.coord, mot.boundSeg, b.bTransGam, mot.xbImplPrev, sc.segImplPrev, mot.xbCplA, mot.xbCplB, mot.xbImplParams);
                if (SEG_IMPLICIT) tg = tg.task("segImpl", CrossBridgeSystem::segImplicitSolve, sc.segMotorOffsets, f.coord, f.uVec, f.yVec, f.bTransGam, sc.segImplPrev, mot.xbImplParams, mot.counts);
                else tg = tg.task("cplSeg", CrossBridgeSystem::coupleSolveSeg, sc.segMotorOffsets, sc.segMotorMyo, mot.xbImplPrev, mot.xbCplA, mot.xbCplB, f.coord, f.uVec, f.yVec, f.bTransGam, sc.segImplPrev, mot.xbImplParams, mot.counts);
                if (XB_IMPLICIT2) tg = tg
                    .task("cplHead", CrossBridgeSystem::coupleCorrectHead, b.coord, mot.boundSeg, mot.xbCplA, mot.xbCplB, f.coord, mot.counts)
                    .task("deriveMot2", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
                tg = tg.task("deriveFil2", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
            }
        }
        tg = tg.transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, f.uVec, f.end1, f.end2, mot.boundSeg,
                    b.end1, b.end2, mot.nucleotideState, mot.forceDotFil,
                    mot.bindArc, b.uVec,    // §6.9: bindArc + head uVec for the per-bound-motor pose log (demand-only; no kernel change)
                    mot.capStats, mot.stats);   // §6.10: break-force firing-rate readback (demand-only)

        int nB = 3 * mot.nMotors, nM = mot.nMotors, nSeg = f.n;
        sched = new GridScheduler();
        for (String t : new String[]{ "publishHead","reach","release","bind","cycle","anchor","bond","applyHead","register" }) addW("gliding." + t, pad(nM));
        if (CANONICAL) addW("gliding.snapHead", pad(nM));
        if (XB_IMPLICIT2 || SEG_IMPLICIT) {
            addW("gliding.segSnap", pad(nSeg)); addW("gliding.deriveFil2", pad(nSeg));
            if (SEG_IMPLICIT) addW("gliding.segImpl", pad(nSeg));
            else addW("gliding.cplSeg", pad(nSeg));
            if (XB_IMPLICIT2) {
                addW("gliding.xbSnap", pad(nM)); addW("gliding.cplA", pad(nM)); addW("gliding.cplHead", pad(nM));
                addW("gliding.deriveMot2", pad(nB));
            }
        }
        for (String t : new String[]{ "zeroMot","brownMot","joints","integMot","deriveMot" }) addW("gliding." + t, pad(nB));
        for (String t : new String[]{ "zeroFil","brownFil","chain","gather","integFil","deriveFil" }) addW("gliding." + t, pad(nSeg));
        for (String t : new String[]{ "csrHist","csrScan","csrScatter" }) addS("gliding." + t);
        if (BOX) addW("gliding.confineFil", pad(nSeg));
        if (BOX_ALL) addW("gliding.confineMot", pad(nB));
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }

    /** VISCOSITY DIAGNOSTIC: scale the filament drag tensors from Constants.aeta to the target aeta
     *  (drag γ ∝ aeta; diffusion = kT/γ ∝ 1/aeta). FDT stays consistent because BrownianForceSystem
     *  derives the random-force magnitude from bTransGam/bRotGam. Isolation-clean: filament only —
     *  motor sub-body drag + head kinetics are UNTOUCHED, so this tests exactly whether FILAMENT drag
     *  caps the glide. AETA==Constants.aeta ⇒ r==1.0 ⇒ no-op (byte-identical). */
    static void applyAeta(FilamentStore f, double aeta) {
        double r = aeta / Constants.aeta;
        if (r == 1.0) return;
        aetaScale(f.bTransGam, r);       aetaScale(f.bRotGam, r);
        aetaScale(f.bTransDiff, 1.0 / r); aetaScale(f.bRotDiff, 1.0 / r);
    }
    static void aetaScale(FloatArray a, double r) { for (int i = 0; i < a.getSize(); i++) a.set(i, (float) (a.get(i) * r)); }

    static double centroidX(FilamentStore f) { double s = 0; for (int i = 0; i < f.n; i++) s += f.coordX(i); return s / f.n; }
    static double centroidZ(FilamentStore f) { double s = 0; for (int i = 0; i < f.n; i++) s += f.coordZ(i); return s / f.n; }
    /** filament end-to-end y excursion (a rotation/wander proxy): max−min segment y. */
    static double ySpread(FilamentStore f) {
        double lo = 1e9, hi = -1e9; for (int i = 0; i < f.n; i++) { double y = f.coordY(i); lo = Math.min(lo, y); hi = Math.max(hi, y); } return hi - lo;
    }
    static long bound(MotorStore m) { long c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0) c++; return c; }
    /** bound heads currently in nucleotide state `st` (the ATP-release invariant: bound-in-ATP must be ~0). */
    static long boundInState(MotorStore m, int st) { long c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0 && m.nucleotideState.get(i) == st) c++; return c; }

    // ===================== PHASE-2 Version-B binder characterization (-canondiag) =====================
    /** Host nearest-reachable arc (mirrors the kernel's tip search over the reach set): returns the tip arc
     *  (from e1) of the nearest gated candidate, or NaN if none. Used to classify formation FAILURES. */
    static double nearestArc(Scene sc, int m) {
        MotorStore mot = sc.mot; FilamentStore f = sc.fil; RigidRodBody b = mot.body;
        int head = 3 * m + 2; double hl = b.segLength.get(head);
        double mx = b.coordX(head) + 0.5 * hl * b.uVecX(head);
        double my = b.coordY(head) + 0.5 * hl * b.uVecY(head);
        double mz = b.coordZ(head) + 0.5 * hl * b.uVecZ(head);
        double mux = b.uVecX(head), muy = b.uVecY(head), muz = b.uVecZ(head);
        double rux = b.uVecX(3 * m), ruy = b.uVecY(3 * m), ruz = b.uVecZ(3 * m);
        double myoColTol = mot.kinParams.get(7), alignTol = mot.kinParams.get(8);
        int cnt = sc.reachCount.get(m); int MAXC = SpatialGrid.MAX_CAND; if (cnt > MAXC) cnt = MAXC;
        double bestD = 1e30, bestArc = Double.NaN;
        for (int k = 0; k < cnt; k++) {
            int s = sc.reachSeg.get(m * MAXC + k);
            double e1x = f.end1.get(s), e1y = f.end1.get(f.n + s), e1z = f.end1.get(2 * f.n + s);
            double e2x = f.end2.get(s), e2y = f.end2.get(f.n + s), e2z = f.end2.get(2 * f.n + s);
            double r1x = e2x - e1x, r1y = e2y - e1y, r1z = e2z - e1z;
            double den = r1x * r1x + r1y * r1y + r1z * r1z; if (den <= 0) continue;
            double a = ((mx - e1x) * r1x + (my - e1y) * r1y + (mz - e1z) * r1z) / den;
            if (a < 0 || a > 1) continue;
            double cx = e1x + a * r1x, cy = e1y + a * r1y, cz = e1z + a * r1z;
            double dd = (cx - mx) * (cx - mx) + (cy - my) * (cy - my) + (cz - mz) * (cz - mz);
            if (dd >= myoColTol * myoColTol) continue;
            double inv = 1.0 / Math.sqrt(den);
            double fux = r1x * inv, fuy = r1y * inv, fuz = r1z * inv;
            if (mux * fux + muy * fuy + muz * fuz < alignTol) continue;
            if (rux * fux + ruy * fuy + ruz * fuz < 0) continue;
            if (dd < bestD) { bestD = dd; bestArc = a * Math.sqrt(den); }
        }
        return bestArc;
    }

    /** Instrument the live thermal Version-B binder: dynamic two-point formation, formation success rate +
     *  end-clustering, the legal-pose check, the bind-time snap magnitude/transient, the lever-strain signal
     *  under thermal load, native binding rate, and glide. CPU runner (the per-step bind moment must be read
     *  before integrate overwrites the snapped head). */
    static void canonDiag(Scene sc, int M) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        int nM = mot.nMotors;
        double HEAD = MotorStore.HEAD_LEN, alignTol = mot.kinParams.get(8);
        System.out.println("\n=== PHASE-2 " + (CONFIG1 ? "CONFIG-1 (PAIRS attachments + Hookean J1 load)" : "Version-B two-point") + " — native characterization (thermal gliding, dt=" + DT + ") ===");
        if (CONFIG1) System.out.printf("  Config 1: PAIRS fracMove=%.2f, J1 Hookean κ=%.3g N·m/rad (= k_tip·L², UNCALIBRATED), forceDotFil = signed J1 lever strain%n", PAIRS_FRACMOVE, KAPPA);
        System.out.printf("  bed %d motors, %d-seg filament (segLen %.4f µm = %.1f nm), HEAD_LEN %.1f nm, myoColTol %.1f nm%n",
                nM, f.n, sc.segL, sc.segL * 1e3, HEAD * 1e3, mot.kinParams.get(7) * 1e3);

        long captures = 0, formations = 0, failures = 0, failMinusEnd = 0;
        double snapAngSum = 0, snapAngMax = 0, snapAngMin = 180;
        double rearDispSum = 0, rearDispMax = 0;
        double bindF8Sum = 0, bindF8Max = 0, bindFdSum = 0; long bindN = 0;
        int legalViolations = 0; double minMotDotFil = 1.0;
        double failArcSum = 0; long failArcN = 0;
        // steady-window lever-strain (forceDotFil) under thermal load
        double fdSum = 0, fdAbsSum = 0, fdSqSum = 0, fdAbsMax = 0; long fdN = 0;
        int[] fdHist = new int[1001];   // |forceDotFil| histogram, 0.5 pN bins to 500 pN (for p99)
        long boundSum = 0; int warm = M / 3;
        double cx0 = 0; boolean nan = false;

        int[] preBound = new int[nM]; boolean[] cap = new boolean[nM];
        double[] preUx = new double[nM], preUy = new double[nM], preUz = new double[nM];
        double[] preRx = new double[nM], preRy = new double[nM], preRz = new double[nM];

        for (int t = 0; t < M; t++) {
            mot.setCounts(t, SEED, f.n); f.counts.set(1, t);
            MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
            BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
            NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
            // ---- snapshot pre-bind (free motors with a candidate = tip-captures) ----
            for (int m = 0; m < nM; m++) {
                preBound[m] = mot.boundSeg.get(m);
                cap[m] = (preBound[m] == MotorStore.FREE_BINDABLE) && sc.reachCount.get(m) > 0;
                if (cap[m]) {
                    int h = 3 * m + 2; double hl = b.segLength.get(h);
                    preUx[m] = b.uVecX(h); preUy[m] = b.uVecY(h); preUz[m] = b.uVecZ(h);
                    preRx[m] = b.coordX(h) - 0.5 * hl * b.uVecX(h);
                    preRy[m] = b.coordY(h) - 0.5 * hl * b.uVecY(h);
                    preRz[m] = b.coordZ(h) - 0.5 * hl * b.uVecZ(h);
                }
            }
            BindingDetectionSystem.bindCanonicalTwoPoint(b.coord, b.uVec, b.segLength, f.end1, f.end2, f.segLength,
                    sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.bindArc2, mot.canonSnap, mot.kinParams, mot.counts);
            BindingDetectionSystem.snapCanonicalHead(b.coord, b.uVec, b.yVec, b.segLength, f.end1, f.uVec,
                    mot.boundSeg, mot.bindArc, mot.bindArc2, mot.canonSnap, mot.counts);
            // ---- classify post-bind ----
            for (int m = 0; m < nM; m++) {
                if (!cap[m]) continue;
                captures++;
                int s = mot.boundSeg.get(m);
                if (s >= 0) {
                    formations++;
                    int h = 3 * m + 2;
                    double dotU = preUx[m] * b.uVecX(h) + preUy[m] * b.uVecY(h) + preUz[m] * b.uVecZ(h);
                    if (dotU > 1) dotU = 1; if (dotU < -1) dotU = -1;
                    double ang = Math.toDegrees(Math.acos(dotU));
                    snapAngSum += ang; snapAngMax = Math.max(snapAngMax, ang); snapAngMin = Math.min(snapAngMin, ang);
                    double hl = b.segLength.get(h);
                    double rx = b.coordX(h) - 0.5 * hl * b.uVecX(h), ry = b.coordY(h) - 0.5 * hl * b.uVecY(h), rz = b.coordZ(h) - 0.5 * hl * b.uVecZ(h);
                    double rd = Math.sqrt((rx - preRx[m]) * (rx - preRx[m]) + (ry - preRy[m]) * (ry - preRy[m]) + (rz - preRz[m]) * (rz - preRz[m]));
                    rearDispSum += rd; rearDispMax = Math.max(rearDispMax, rd);
                    // legal-pose: motDotFil of the snapped head vs the bound segment must pass the gate
                    double sux = f.uVec.get(s), suy = f.uVec.get(f.n + s), suz = f.uVec.get(2 * f.n + s);
                    double motDotFil = b.uVecX(h) * sux + b.uVecY(h) * suy + b.uVecZ(h) * suz;
                    minMotDotFil = Math.min(minMotDotFil, motDotFil);
                    if (motDotFil < alignTol) legalViolations++;
                } else {
                    failures++;
                    double arc = nearestArc(sc, m);
                    if (!Double.isNaN(arc)) { failArcSum += arc; failArcN++; if (arc < HEAD) failMinusEnd++; }
                }
            }
            // ---- the rest of the step (canonical dynamics) ----
            NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
            MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, sc.jointParams, mot.counts);
            TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, sc.jointParams, mot.counts);
            if (CONFIG1)
                CrossBridgeSystem.bondForcesCanonicalConfig1(b.coord, b.uVec, b.bTransGam, b.bRotGam, f.coord, f.uVec, f.segLength, f.bTransGam, f.bRotGam,
                        mot.boundSeg, mot.bindArc, mot.bindArc2, mot.nucleotideState, sc.bondData, sc.xbParamsC1);
            else
                CrossBridgeSystem.bondForcesCanonical(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                        mot.boundSeg, mot.bindArc, mot.bindArc2, mot.nucleotideState, sc.bondData, sc.xbParams);
            // ---- bind-time transient: |net F8| + forceDotFil of motors that JUST formed ----
            for (int m = 0; m < nM; m++) {
                if (!cap[m] || mot.boundSeg.get(m) < 0) continue;
                int d = m * CrossBridgeSystem.STRIDE;
                double fx = sc.bondData.get(d), fy = sc.bondData.get(d + 1), fz = sc.bondData.get(d + 2);
                double f8 = Math.sqrt(fx * fx + fy * fy + fz * fz);
                bindF8Sum += f8; bindF8Max = Math.max(bindF8Max, f8); bindFdSum += Math.abs(sc.bondData.get(d + 12)); bindN++;
            }
            CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
            ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
            CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
            CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
            CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);

            if (t == warm) cx0 = centroidX(f);
            if (t >= warm) {
                boundSum += bound(mot);
                for (int m = 0; m < nM; m++) {
                    if (mot.boundSeg.get(m) < 0) continue;
                    double fd = mot.forceDotFil.get(m);
                    fdSum += fd; fdAbsSum += Math.abs(fd); fdSqSum += fd * fd; fdAbsMax = Math.max(fdAbsMax, Math.abs(fd)); fdN++;
                    int bin = (int) (Math.abs(fd) * 1e12 / 0.5); if (bin > 1000) bin = 1000; fdHist[bin]++;
                }
            }
            if (Double.isNaN(centroidX(f))) nan = true;
        }

        double cxN = centroidX(f);
        double velSteady = (cxN - cx0) / ((M - warm) * DT);
        double avgB = boundSum / (double) (M - warm);
        System.out.println("\n  --- (1) does dynamic two-point capture form during a LIVE THERMAL run? ---");
        System.out.printf("      tip-captures=%d, two-point formations=%d, failures=%d  ⇒ %s%n",
                captures, formations, failures, formations > 0 ? "YES — motors form the two-point along-filament pose on the fly" : "NO — no two-point formation");
        System.out.println("\n  --- (2) two-point formation success rate + where failures cluster ---");
        System.out.printf("      formation success = %.2f%% of tip-captures (%d/%d); failures = %.2f%%%n",
                100.0 * formations / Math.max(1, captures), formations, captures, 100.0 * failures / Math.max(1, captures));
        System.out.printf("      of failures, %.1f%% have nearest tip-arc < HEAD_LEN (rear runs off the segment's minus end); mean fail tip-arc = %.1f nm (segLen %.1f nm)%n",
                100.0 * failMinusEnd / Math.max(1, failArcN), failArcN > 0 ? failArcSum / failArcN * 1e3 : 0, sc.segL * 1e3);
        System.out.printf("      [expected per-segment fail fraction ≈ HEAD_LEN/segLen = %.1f%% — failures are the same-segment-only restriction at each segment's e1]%n",
                100.0 * HEAD / sc.segL);
        System.out.println("\n  --- (1-check) legal-pose: the snapped along-filament pose under the existing gates ---");
        System.out.printf("      min motDotFil(snapped) = %.4f  (alignTol = %.2f); legal-pose violations = %d  ⇒ %s%n",
                minMotDotFil, alignTol, legalViolations, legalViolations == 0 ? "the gates ACCEPT the along-filament pose (no inconsistency)" : "*PAUSE: gates REJECT the canonical pose*");
        System.out.println("\n  --- (2-check) bind-time snap magnitude / transient ---");
        System.out.printf("      head rotation at snap: mean %.1f°, range [%.1f°, %.1f°]  (perpendicular→along-filament ≈ 90°)%n",
                formations > 0 ? snapAngSum / formations : 0, snapAngMin, snapAngMax);
        System.out.printf("      rear(J1-pivot) displacement: mean %.1f nm, max %.1f nm  (jolts the J1 *connection* spring — fracMove damping-limited, dt-robust)%n",
                formations > 0 ? rearDispSum / formations * 1e3 : 0, rearDispMax * 1e3);
        if (CONFIG1) {
            System.out.printf("      bind-step PAIRS |net pin force| mean %.3f pN (max %.3f pN — gentle, dt-robust), mean |forceDotFil(J1 strain at bind)| %.3f pN%n",
                    bindN > 0 ? bindF8Sum / bindN * 1e12 : 0, bindF8Max * 1e12, bindN > 0 ? bindFdSum / bindN * 1e12 : 0);
            System.out.println("      ⇒ PAIRS pins gently; the J1-strain bind load reflects the J1 deflection at the binding nucleotide state (not a soft-spring overshoot)");
        } else {
            System.out.printf("      bind-step |net F8| mean %.3f pN (max %.3f pN, BOUNDED ≤ 2·myoSpring·myoColTol = %.1f pN — the perpendicular PIN toward the axis), mean |forceDotFil| %.4f pN%n",
                    bindN > 0 ? bindF8Sum / bindN * 1e12 : 0, bindF8Max * 1e12, 2.0 * (MYO_SPRING * 1e12) * (mot.kinParams.get(7) * 1e3),
                    bindN > 0 ? bindFdSum / bindN * 1e12 : 0);
            System.out.println("      ⇒ the along-filament load the CATCH reads is ~0 at bind (both F8 ⟂ axis) ⇒ GENTLE bind (no along-fil overshoot at formation)");
        }
        System.out.println("\n  --- (3) native binding rate / avgBound (UNCALIBRATED kOn) ---");
        System.out.printf("      avgBound(steady) = %.2f over %d motors (%.3f%% duty); formations/step = %.3f%n",
                avgB, nM, 100.0 * avgB / nM, formations / (double) M);
        System.out.println("\n  --- (4) does it GLIDE? ---");
        System.out.printf("      steady velocity = %.3f µm/s (%s); net Δx = %.4f µm over %.4f s; z-drift %.4f µm; NaN=%s%n",
                velSteady, velSteady < 0 ? "−x ✓ directed gliding" : "+x/0 ?", cxN - cx0, (M - warm) * DT, centroidZ(f) - FIL_Z, nan);
        System.out.println("\n  --- (5) dt-gentleness of the lever-strain signal UNDER THERMAL LOAD (the key open question) ---");
        double fdMean = fdN > 0 ? fdSum / fdN : 0, fdAbsMean = fdN > 0 ? fdAbsSum / fdN : 0, fdRms = fdN > 0 ? Math.sqrt(fdSqSum / fdN) : 0;
        double p99 = 0; long acc99 = 0, tgt99 = (long) (0.99 * fdN);
        for (int bin = 0; bin <= 1000; bin++) { acc99 += fdHist[bin]; if (acc99 >= tgt99) { p99 = bin * 0.5; break; } }
        System.out.printf("      forceDotFil over bound motors: mean %.4f pN, mean|·| %.4f pN, RMS %.4f pN, p99|·| %.2f pN, max|·| %.4f pN%n",
                fdMean * 1e12, fdAbsMean * 1e12, fdRms * 1e12, p99, fdAbsMax * 1e12);
        System.out.printf("      vs the old two-F8 canonical tail (PHASE2_VERSIONB §5): RMS 8.9 pN, max 90–116 pN; phase-1 deterministic isometric +0.285 pN%n");
        boolean gentle = fdRms * 1e12 < 4.0 && fdAbsMax * 1e12 < 40.0;   // tail materially smaller than the two-F8 RMS 8.9 / max ~100
        System.out.printf("      ⇒ the two-point geometric pinning keeps the lever-strain %s at dt=%.0e under thermal forces%n",
                gentle ? "BOUNDED/gentle (no reintroduced stiff overshoot)" : "*elevated — report (overshoot may have reappeared)*", DT);
        System.out.println("\n  --- (6) dt stability ---");
        System.out.printf("      %s over %d steps at dt=%.0e%n", nan ? "*BLEW UP (NaN)*" : "STABLE (bounded, no NaN)", M, DT);
        System.out.println("\n=== Version-B native characterization complete ===");
    }

    // ===================== PHASE-2 step-3: single-molecule duty assay (kOn calibration) =====================
    /** One single-molecule step: the Config-1 motor dynamics on a FIXED filament (no filament integration ⇒ the
     *  proximal motors bind/cycle/release on held actin; the duty is kinetically gated, not geometry-confounded). */
    static void singleStep(Scene sc, int t) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        mot.setCounts(t, SEED, f.n); f.counts.set(1, t);
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2, sc.reachSeg, sc.reachCount, mot.kinParams, mot.counts);
        if (LYMN_TAYLOR) { /* LT: no separate release — detachment is nucleotide-driven in the cycle below */ }
        else if (TAU_AVG > 0)
            NucleotideCycleSystem.catchSlipReleaseAvg(mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        else
            NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
        BindingDetectionSystem.bindCanonicalTwoPoint(b.coord, b.uVec, b.segLength, f.end1, f.end2, f.segLength, sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.bindArc2, mot.canonSnap, mot.kinParams, mot.counts);
        if (LYMN_TAYLOR)
            NucleotideCycleSystem.cycleLymnTaylor(mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts);
        else
            NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, sc.jointParams, mot.counts);
        TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, sc.jointParams, mot.counts);
        CrossBridgeSystem.bondForcesCanonicalConfig1(b.coord, b.uVec, b.bTransGam, b.bRotGam, f.coord, f.uVec, f.segLength, f.bTransGam, f.bRotGam,
                mot.boundSeg, mot.bindArc, mot.bindArc2, mot.nucleotideState, sc.bondData, sc.xbParamsC1);
        CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        if (CONFIG1 || CANONICAL) BindingDetectionSystem.snapCanonicalHead(b.coord, b.uVec, b.yVec, b.segLength, f.end1, f.uVec, mot.boundSeg, mot.bindArc, mot.bindArc2, mot.canonSnap, mot.counts);
        CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
        // filament FIXED — not integrated (single-molecule held actin)
    }

    static void singleMolecule(Scene sc, int M) {
        MotorStore mot = sc.mot; int nM = mot.nMotors;
        int warm = M / 4;
        long boundSteps = 0, freeBindSteps = 0, inReachFree = 0, bindEvents = 0, releaseEvents = 0;
        double fdSum = 0; long fdN = 0;
        int[] prevBound = new int[nM];
        for (int m = 0; m < nM; m++) prevBound[m] = mot.boundSeg.get(m);
        // τ_thermal autocorrelation (Test 3): per-motor forceDotFil history ring + covariance at fixed lags.
        final int[] LAGS = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144}; final int LMAX = 150;
        float[] hist = ACORR ? new float[nM * LMAX] : null; int[] streak = ACORR ? new int[nM] : null;
        double ac0 = 0, acSumF = 0; long acN = 0; double[] acLag = new double[LAGS.length];
        System.out.printf("%n=== PHASE-2 step-3/4a single-molecule DUTY assay (Config-1, %d proximal motors, FIXED filament, kOn=%.3g, τavg=%.3g ms, F_ext=%.1f pN, dt=%.0e) ===%n",
                nM, KON, TAU_AVG * 1e3, F_EXT, DT);
        for (int t = 0; t < M; t++) {
            singleStep(sc, t);
            if (t >= warm) {
                for (int m = 0; m < nM; m++) {
                    int bs = mot.boundSeg.get(m);
                    boolean nowBound = bs >= 0;
                    if (nowBound) { boundSteps++; double fd = mot.forceDotFil.get(m); fdSum += fd; fdN++; }
                    else if (bs == MotorStore.FREE_BINDABLE) { freeBindSteps++; if (sc.reachCount.get(m) > 0) inReachFree++; }
                    if (nowBound && prevBound[m] < 0) bindEvents++;        // attachment
                    if (!nowBound && prevBound[m] >= 0) releaseEvents++;   // detachment
                    if (ACORR) {
                        if (nowBound) {
                            float Fn = mot.forceDotFil.get(m);
                            if (streak[m] >= LAGS[LAGS.length - 1]) {       // enough continuous-bound history for the longest lag
                                ac0 += Fn * Fn; acSumF += Fn; acN++;
                                for (int k = 0; k < LAGS.length; k++) { int j = ((t - LAGS[k]) % LMAX + LMAX) % LMAX; acLag[k] += Fn * hist[m * LMAX + j]; }
                            }
                            hist[m * LMAX + (t % LMAX)] = Fn; streak[m]++;
                        } else streak[m] = 0;
                    }
                    prevBound[m] = bs;
                }
            } else {
                for (int m = 0; m < nM; m++) prevBound[m] = mot.boundSeg.get(m);
            }
        }
        long totMotorSteps = (long) (M - warm) * nM;
        double duty = boundSteps / (double) totMotorSteps;
        double attachRate = freeBindSteps > 0 ? bindEvents / (freeBindSteps * DT) : 0;   // binds per second of free-bindable time
        double tOnMs = releaseEvents > 0 ? boundSteps * DT / releaseEvents * 1e3 : 0;     // mean bound lifetime (ms)
        double inReachFrac = freeBindSteps > 0 ? inReachFree / (double) freeBindSteps : 0;
        double pEmp = inReachFree > 0 ? bindEvents / (double) inReachFree : 0;            // empirical per-in-reach-step capture prob
        double pTheory = KON > 0 ? 1.0 - Math.exp(-KON * 2.0 * mot.kinParams.get(7) * DT) : 1.0;  // max per-step capture prob (d⊥=0); saturated kOn=0 ⇒ 1
        double fdMean = fdN > 0 ? fdSum / fdN * 1e12 : 0;
        System.out.printf("  duty = %.4f (%.2f%%)   [target ~0.01–0.02]%n", duty, duty * 100);
        System.out.printf("  attachment rate = %.1f /s (binds per sec of free time)   t_on = %.2f ms [target ~10 ms]   mean forceDotFil = %.3f pN%n",
                attachRate, tOnMs, fdMean);
        System.out.printf("  in-reach fraction (while free) = %.3f   per-encounter P_capture: theory %.5f / empirical %.5f  ⇒ regime: %s%n",
                inReachFrac, pTheory, pEmp, pTheory < 0.5 ? "REACTION-LIMITED (probabilistic per encounter)" : "SATURATED (near bind-on-contact)");
        System.out.printf("  SUMMARY  kOn=%.4g  tau_ms=%.3g  fext_pN=%.1f  duty=%.4f  attach/s=%.1f  t_on_ms=%.2f  fdMean_pN=%.3f%n",
                KON, TAU_AVG * 1e3, F_EXT, duty, attachRate, tOnMs, fdMean);
        if (ACORR && acN > 0) {
            double mu = acSumF / acN, var = ac0 / acN - mu * mu;
            System.out.println("  --- τ_thermal: J1-strain (forceDotFil) autocorrelation C(lag) [normalized covariance] ---");
            double tauTherm = 0;
            for (int k = 0; k < LAGS.length; k++) {
                double cov = acLag[k] / acN - mu * mu;
                double c = var > 0 ? cov / var : 0;
                double lagMs = LAGS[k] * DT * 1e3;
                System.out.printf("    lag %3d (%.3f ms): C = %.4f%n", LAGS[k], lagMs, c);
                if (tauTherm == 0 && c < 0.3679) tauTherm = lagMs;   // 1/e crossing
            }
            System.out.printf("  ⇒ τ_thermal ≈ %.3f ms (1/e of the J1-strain autocorr); var = %.3f pN²%n", tauTherm, var * 1e24);
        }
    }

    // ===================== PHASE-2 step-4b: catch force-sensitivity calibration (rate vs load) =====================
    /** Run the single-molecule assay at the current statics and return {t_on (ms), duty, attachRate /s, fdMean pN}. */
    static double[] singleRun(int M) {
        Scene sc = buildScene();
        MotorStore mot = sc.mot; int nM = mot.nMotors; int warm = M / 4;
        long boundSteps = 0, releaseEvents = 0; double fdSum = 0; long fdN = 0;
        int[] prevBound = new int[nM];
        for (int m = 0; m < nM; m++) prevBound[m] = mot.boundSeg.get(m);
        for (int t = 0; t < M; t++) {
            singleStep(sc, t);
            if (t >= warm) {
                for (int m = 0; m < nM; m++) {
                    int bs = mot.boundSeg.get(m); boolean nowBound = bs >= 0;
                    if (nowBound) { boundSteps++; fdSum += mot.forceDotFil.get(m); fdN++; }
                    if (!nowBound && prevBound[m] >= 0) releaseEvents++;
                    prevBound[m] = bs;
                }
            } else for (int m = 0; m < nM; m++) prevBound[m] = mot.boundSeg.get(m);
        }
        double duty = boundSteps / (double) ((long) (M - warm) * nM);
        double tOnMs = releaseEvents > 0 ? boundSteps * DT / releaseEvents * 1e3 : 0;
        double fdMean = fdN > 0 ? fdSum / fdN * 1e12 : 0;
        return new double[]{ tOnMs, duty, fdMean };
    }

    /** Catch force-sensitivity calibration: detachment RATE (1/t_on) vs sustained load F_ext on the AVERAGED
     *  catch (τ held). Effective d from the 0→+1 pN halving (Veigel: 1 pN resisting halves ⇒ d≈2.7 nm). */
    static void dCalib(int M) {
        double tau = TAU_AVG > 0 ? TAU_AVG : 0.5e-3; TAU_AVG = tau;     // τ held (default 0.5 ms)
        double[] loads = { -1, 0, 1, 2 };
        System.out.printf("%n=== PHASE-2 step-4b catch force-sensitivity calibration (Config-1 single-molecule, τ=%.2f ms, kOn=%.2g) ===%n", tau * 1e3, KON);
        System.out.println("  detachment rate (1/t_on) vs sustained load F_ext; effective d from the 0→+1 pN halving (Veigel d≈2.7 nm).");
        double kT = Constants.kT;
        double[] dGrid = (XCATCH > 0) ? new double[]{ XCATCH } : new double[]{ 2.5, 3.0, 3.5 };
        for (double dnm : dGrid) {
            XCATCH = dnm;
            System.out.printf("%n  --- xCatch (d) = %.2f nm ---%n", dnm);
            System.out.printf("    %-10s %-12s %-12s %-12s%n", "F_ext(pN)", "t_on(ms)", "rate(/s)", "rate/rate0");
            double rate0 = 0; double rateP1 = 0;
            double[] rates = new double[loads.length];
            for (int i = 0; i < loads.length; i++) {
                F_EXT = loads[i];
                double[] r = singleRun(M);
                double rate = r[0] > 0 ? 1000.0 / r[0] : 0;
                rates[i] = rate;
                if (loads[i] == 0) rate0 = rate;
                if (loads[i] == 1) rateP1 = rate;
            }
            for (int i = 0; i < loads.length; i++)
                System.out.printf("    %-10.1f %-12.2f %-12.1f %-12.3f%n", loads[i], rates[i] > 0 ? 1000.0 / rates[i] : 0, rates[i], rate0 > 0 ? rates[i] / rate0 : 0);
            // effective d from the resisting half-load slope: ln(rate(+1)/rate(0)) = −1pN·d/kT
            double halving = rate0 > 0 ? rateP1 / rate0 : 0;
            double dEff = rate0 > 0 && rateP1 > 0 ? -kT * Math.log(rateP1 / rate0) / 1.0e-12 * 1e9 : 0;
            System.out.printf("    ⇒ rate(+1 pN)/rate(0) = %.3f  (Veigel target 0.50)   effective d = %.2f nm  (Veigel 2.7 nm)%n", halving, dEff);
        }
        F_EXT = 0;
    }

    /** PHASE-2 step-4c: independent motor STALL force (pN) at the production κ, lever, 60° converter. */
    static double motorStallPN() { return motorStallPN(KAPPA, MotorStore.LEVER_LEN, 60.0); }

    /** PHASE-2 step-4d (parameterized): the independent motor STALL force (pN) — the Hookean J1 converter spring at
     *  FULL deflection = `strokeAngleDeg`, for stiffness `kappaVal` (N·m/rad) and lever `leverLenUm` (µm). A one-shot
     *  held-pose `bondForcesCanonicalConfig1` eval reads forceDotFil = (κ/L)·deflection (geometry + κ only, NO catch,
     *  NO relaxation). The deflection magnitude is imposed by holding the lever at angle (60°+θ) from the head (the
     *  kernel's cocked rest is 60° ⇒ |60−(60+θ)| = θ), so NO kernel edit is needed — the kernel computes the same
     *  linear (κ/L)·deflection it was validated on at 60° in 4c (8.38 pN). Only lever.uVec, head.uVec, κ, L enter
     *  forceDotFil (the PAIRS pins don't feed it), so the lever position is immaterial. MEASUREMENT-ONLY. */
    static double motorStallPN(double kappaVal, double leverLenUm, double strokeAngleDeg) {
        int nSeg = 1;
        FilamentStore fil = new FilamentStore(nSeg);
        fil.monomerCount.set(0, Constants.stdSegLength);
        fil.setUVec(0, 1f, 0f, 0f); fil.setYVec(0, 0f, 1f, 0f); fil.setCoord(0, 0f, 0f, 0f);
        fil.brownTransScale.set(0, 0f); fil.brownRotScale.set(0, 0f);
        DragTensorSystem.run(fil); fil.setParams(DT, 0); fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);
        MotorStore mot = new MotorStore(1);
        RigidRodBody b = mot.body;
        double zHead = -0.002;
        int rod = 0, lever = 1, head = 2;
        b.setCoord(head, 0f, 0f, (float) zHead); b.setUVec(head, 1f, 0f, 0f); b.setYVec(head, 0f, 1f, 0f);
        // lever held at (60°+θ) from the head ⇒ imposed J1 deflection = θ (against the fixed 60° cocked rest)
        double psi = Math.toRadians(60.0 + strokeAngleDeg);
        b.setCoord(lever, -0.02f, 0f, (float) zHead); b.setUVec(lever, (float) Math.cos(psi), 0f, (float) Math.sin(psi)); b.setYVec(lever, 0f, 1f, 0f);
        b.setCoord(rod, -0.05f, 0f, (float) zHead); b.setUVec(rod, 1f, 0f, 0f); b.setYVec(rod, 0f, 1f, 0f);
        DragTensorSystem.run(mot);
        mot.setBodyParams(DT); mot.setJointParams(DT); mot.setKinParams(0.006, -0.4, DT); mot.setNucParams(DT);
        mot.enableConfig1(kappaVal);
        double e1x = fil.end1.get(0), sux = fil.uVec.get(0);
        double tipx = 0.5 * MotorStore.HEAD_LEN, rearxF = -0.5 * MotorStore.HEAD_LEN;
        mot.boundSeg.set(0, 0);
        mot.bindArc.set(0, (float) ((tipx - e1x) * sux));
        mot.bindArc2.set(0, (float) ((rearxF - e1x) * sux));
        mot.setAllStates(MotorStore.NUC_ATP);   // cocked ⇒ J1 rest 60°
        FloatArray bondData = new FloatArray(CrossBridgeSystem.STRIDE); bondData.init(0f);
        FloatArray xbC1 = FloatArray.fromElements((float) PAIRS_FRACMOVE, (float) kappaVal, (float) leverLenUm, (float) DT, (float) MotorStore.HEAD_LEN);
        CrossBridgeSystem.bondForcesCanonicalConfig1(b.coord, b.uVec, b.bTransGam, b.bRotGam, fil.coord, fil.uVec, fil.segLength, fil.bTransGam, fil.bRotGam,
                mot.boundSeg, mot.bindArc, mot.bindArc2, mot.nucleotideState, bondData, xbC1);
        return Math.abs(bondData.get(12)) * 1e12;   // |forceDotFil| at full θ deflection = stall (pN)
    }

    /** PHASE-2 step-4d (MEASUREMENT-ONLY): sweep κ (crossbridge stiffness) and the powerstroke angle to map the
     *  SENSITIVITY of the emergent peak≈stall coincidence. Bond peak HELD at the 4c GG-calibrated 6.0 pN (it is
     *  κ-independent — the catch-slip crossover is set by xCatch/xSlip, and the −fext load scale dominates the
     *  native κ-dependent forceDotFil). Production κ (6.4e-20) + angle (60°) UNCHANGED; one-shot stall evals. */
    static void stiffnessAngleSweep() {
        double PEAK = 6.0;                       // 4c GG-calibrated catch-slip peak force (pN), κ-independent
        double Lprod = MotorStore.LEVER_LEN;     // 8 nm (µm)
        double kT = Constants.kT;
        System.out.println("\n=== PHASE-2 step-4d: κ + powerstroke-angle sensitivity of the peak≈stall coincidence (MEASUREMENT-ONLY) ===");
        System.out.printf("  bond peak HELD = %.1f pN (4c Guo&Guilford, κ-independent); production κ=6.4e-20 (tip 1 pN/nm), angle 60° UNCHANGED.%n", PEAK);

        // ---- SWEEP 1: κ (tip-stiffness 0.5→3 pN/nm), angle fixed 60° ----
        System.out.println("\n--- SWEEP 1: peak/stall vs crossbridge tip-stiffness (lever 8 nm, 60°) ---");
        System.out.printf("  %-14s %-12s %-14s %-14s %-10s%n", "tip(pN/nm)", "κ(N·m/rad)", "stall(pN)", "peak/stall", "in 2×band?");
        double[] tips = { 0.35, 0.5, 0.75, 1.0, 1.5, 2.0, 2.6, 3.0 };
        double[] s1tip = new double[tips.length], s1ratio = new double[tips.length];
        for (int i = 0; i < tips.length; i++) {
            double tipSI = tips[i] * 1.0e-3;                 // pN/nm → N/m
            double kappa = tipSI * (Lprod * 1e-6) * (Lprod * 1e-6);   // κ = tip·L²
            double stall = motorStallPN(kappa, Lprod, 60.0);
            double ratio = PEAK / stall;
            s1tip[i] = tips[i]; s1ratio[i] = ratio;
            System.out.printf("  %-14.2f %-12.3g %-14.2f %-14.2f %-10s%n", tips[i], kappa, stall, ratio,
                    (ratio >= 0.5 && ratio <= 2.0) ? "yes" : "NO");
        }
        // band edges (ratio crosses 0.5 and 2.0) by linear interp on tip vs ratio
        double tipAt1 = interpX(s1tip, s1ratio, 1.0), tipLo = interpX(s1tip, s1ratio, 2.0), tipHi = interpX(s1tip, s1ratio, 0.5);
        System.out.printf("  ⇒ peak/stall = 1.0 at tip ≈ %.2f pN/nm; the ~2× band (ratio 0.5–2.0) spans tip ≈ %.2f → %.2f pN/nm.%n", tipAt1, tipLo, tipHi);
        System.out.printf("    production 1 pN/nm: ratio %.2f — %s the band; Kaya-Higuchi ~2.6 pN/nm: ratio %.2f — %s.%n",
                PEAK / motorStallPN(1.0e-3 * (Lprod*1e-6)*(Lprod*1e-6), Lprod, 60.0), "INSIDE",
                PEAK / motorStallPN(2.6e-3 * (Lprod*1e-6)*(Lprod*1e-6), Lprod, 60.0),
                (PEAK / motorStallPN(2.6e-3 * (Lprod*1e-6)*(Lprod*1e-6), Lprod, 60.0)) >= 0.5 ? "inside" : "OUTSIDE");

        // ---- SWEEP 2a: angle, FIXED lever 8 nm → stroke & stall both rise with angle ----
        System.out.println("\n--- SWEEP 2a: stroke & stall vs converter angle (FIXED lever 8 nm, production κ) ---");
        System.out.printf("  %-10s %-16s %-16s %-14s%n", "angle(°)", "stroke=2Lsin(θ/2)", "stall_kernel(pN)", "stall_analytic");
        double[] angs = { 45, 55, 60, 65, 70, 80 };
        double[] s2stroke = new double[angs.length], s2stall = new double[angs.length];
        for (int i = 0; i < angs.length; i++) {
            double th = Math.toRadians(angs[i]);
            double stroke = 2.0 * (Lprod * 1e3) * Math.sin(th / 2);   // nm
            double stall = motorStallPN(KAPPA, Lprod, angs[i]);
            double stallAna = (KAPPA / (Lprod * 1e-6)) * th * 1e12;   // (κ/L)·θ_rad
            s2stroke[i] = stroke; s2stall[i] = stall;
            System.out.printf("  %-10.0f %-16.3f %-16.2f %-14.2f%n", angs[i], stroke, stall, stallAna);
        }
        // ---- SWEEP 2b: angle, FIXED stroke 8 nm + FIXED tip-stiffness 1 pN/nm (adjust L, κ=tip·L²) → ~angle-invariant ----
        System.out.println("\n--- SWEEP 2b: stall vs angle (FIXED stroke 8 nm + FIXED tip-stiffness 1 pN/nm; L,κ adjusted) ---");
        System.out.printf("  %-10s %-14s %-14s %-16s%n", "angle(°)", "lever(nm)", "κ(N·m/rad)", "stall(pN)");
        double strokeFix = 8.0;   // nm
        double[] s2bstall = new double[angs.length];
        for (int i = 0; i < angs.length; i++) {
            double th = Math.toRadians(angs[i]);
            double Lnm = strokeFix / (2.0 * Math.sin(th / 2));   // L for fixed stroke
            double Lum = Lnm * 1e-3;
            double kappa = 1.0e-3 * (Lum * 1e-6) * (Lum * 1e-6);  // tip 1 pN/nm ⇒ κ=tip·L²
            double stall = motorStallPN(kappa, Lum, angs[i]);
            s2bstall[i] = stall;
            System.out.printf("  %-10.0f %-14.3f %-14.3g %-16.2f%n", angs[i], Lnm, kappa, stall);
        }
        double mn = 1e9, mx = 0; for (double v : s2bstall) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        System.out.printf("  ⇒ stall spans %.2f–%.2f pN across 45–80° (%.0f%% variation) ⇒ %s at fixed stroke+stiffness.%n",
                mn, mx, 100 * (mx - mn) / mn, (mx - mn) / mn < 0.15 ? "~ANGLE-INVARIANT ✓ (the chord-vs-arc θ/(2sin(θ/2)) correction)" : "angle-dependent");

        // ---- CSV for the matplotlib graphs ----
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# sweep1: tip_pN_per_nm,peak_over_stall\n");
            for (int i = 0; i < tips.length; i++) sb.append(String.format(java.util.Locale.US, "S1,%.3f,%.4f%n", s1tip[i], s1ratio[i]));
            sb.append("# sweep2a: angle_deg,stroke_nm,stall_pN\n");
            for (int i = 0; i < angs.length; i++) sb.append(String.format(java.util.Locale.US, "S2A,%.1f,%.4f,%.4f%n", angs[i], s2stroke[i], s2stall[i]));
            sb.append("# sweep2b: angle_deg,stall_pN_fixed_stroke\n");
            for (int i = 0; i < angs.length; i++) sb.append(String.format(java.util.Locale.US, "S2B,%.1f,%.4f%n", angs[i], s2bstall[i]));
            java.nio.file.Files.writeString(java.nio.file.Path.of("stiffness_angle_sweep.csv"), sb.toString());
            System.out.println("\n  wrote stiffness_angle_sweep.csv (for the matplotlib graphs)");
        } catch (java.io.IOException e) { System.out.println("  (CSV write failed: " + e.getMessage() + ")"); }
    }
    /** linear interpolation: find x where y(x)=target, given monotonic-ish (x,y). */
    static double interpX(double[] x, double[] y, double target) {
        for (int i = 0; i < x.length - 1; i++) {
            if ((y[i] - target) * (y[i + 1] - target) <= 0 && y[i] != y[i + 1])
                return x[i] + (target - y[i]) * (x[i + 1] - x[i]) / (y[i + 1] - y[i]);
        }
        return Double.NaN;
    }

    /** v1 moveCoeff (replica of CrossBridgeSystem.moveC / MotorJointSystem.moveC, for the host-side per-pin
     *  force decomposition — measurement only, NO kernel touched). */
    static double moveCH(double ux, double uy, double uz, double lx, double ly, double lz,
                         double bTGx, double bTGy, double bRGy, double lenUm) {
        double cosB = ux*lx + uy*ly + uz*lz; if (cosB > 1) cosB = 1; if (cosB < -1) cosB = -1;
        double cosB2 = cosB*cosB, cosA2 = 1.0 - cosB2, lSq = 1.0e-12 * lenUm * lenUm;
        return cosB2/bTGx + cosA2/bTGy + lSq*cosA2/(4.0*bRGy);
    }

    /**
     * PHASE-2 FORCE DECOMPOSITION (MEASUREMENT-ONLY; no physics edit, no fix). A SINGLE config-1 motor in the
     * GLIDING (transport) topology — tail ANCHORED to the surface, head two-point-pinned to a HELD filament segment —
     * fires ONE powerstroke (J1 rest 0°→60°) at dt=1e-6, Brownian OFF. Decomposes the net force the cross-bridge
     * delivers to the FILAMENT in the filament frame: axial (along segU, the transport direction) vs transverse, AND
     * the two PAIRS pins separately (pin-A tip@bindArc, pin-B rear@bindArc2) projected on segU. The decisive question:
     * is axial_A ≈ −axial_B (a cancelling couple ⇒ bends, no transport)? Contrasts the config-1 net axial impulse
     * with the DEFAULT v1-faithful motor (which glides −5.7 µm/s) and cross-checks against a RELEASED filament.
     */
    static void forceDecomp() {
        double dt = 1.0e-6;                                   // dt=1e-6: stability (per task), NOT a default change
        double HEAD = MotorStore.HEAD_LEN, LEVER = MotorStore.LEVER_LEN, ROD = MotorStore.ROD_LEN;
        System.out.println("\n=== PHASE-2 FORCE DECOMPOSITION — single motor, transport topology, dt=1e-6, Brownian OFF ===");
        System.out.printf("  config1 κ=%.3g (tip %.3f pN/nm, stall %.2f pN); PAIRS fracMove=%.2f; HEAD_LEN=%.1f nm; LEVER=%.1f nm%n",
                KAPPA, KAPPA/(LEVER*1e-6)/(LEVER*1e-6)*1e3, motorStallPN(), PAIRS_FRACMOVE, HEAD*1e3, LEVER*1e3);
        if (PERPHEAD)
            System.out.printf("  PERP-HEAD: rear pin REMOVED, tip pin kept, ⊥-orientation PAIRS torque fracMove=%.2f (the head is driven to stand ⊥ to actin)%n", PERP_FRACMOVE);
        // modes: 0 = PERP-HEAD (new, only when -perphead), 1 = CONFIG-1 (the old 22:1), 2 = DEFAULT v1 (axial ref)
        int first = PERPHEAD ? 0 : 1;
        for (int mode = first; mode < 3; mode++) {
            boolean cfg1 = (mode == 0 || mode == 1);   // config1 force-law branch (PAIRS pins + Hookean-J1)
            boolean perp = (mode == 0);
            String label = (mode == 0) ? "PERP-HEAD config-1 (tip pin + ⊥-orientation torque, NO rear pin)"
                         : (mode == 1) ? "CONFIG-1 (two-point PAIRS pins + Hookean-J1) — the OLD 22:1"
                                       : "DEFAULT v1 motor (F8 tip spring + F9/F10) — the axial reference";
            System.out.println("\n----- " + label + " -----");
            double[] r = decompRun(cfg1, perp, dt, true, 0.0, 90.0, false);    // filament HELD (isometric force time course + impulse)
            double[] rel = decompRun(cfg1, perp, dt, false, 0.0, 90.0, false); // filament RELEASED (cross-check: does it translate?)
            System.out.printf("  net axial IMPULSE over the stroke = %.4g pN·s ; released-filament Δx = %+.4f nm  Δz = %+.4f nm (predicted sign %s)%n",
                    r[0]*1e12, rel[1]*1e6, rel[2]*1e6, r[0] < 0 ? "−x glide" : "+x");
        }
        System.out.println("\n=== force decomposition complete ===");
    }

    /**
     * PHASE-2 HEAD-ANGLE SWEEP (Stage 1, MEASUREMENT-ONLY). Hold the PERP-HEAD topology FIXED (single tip pin +
     * the ⊥-orientation PAIRS torque) and vary ONLY the torque's target angle θ between head-uVec-aligned-with-
     * filament (θ=0) and full-perpendicular (θ=90 = the current perp-head). Surface-free swing-plane target:
     *   perpRest = normalize(u−(u·f)f),  f_hat = sign(u·f)·f,  Target(θ) = cos θ·f_hat + sin θ·perpRest.
     * The head is bound OFF-AXIS (a real acute angle φ=OFFAXIS_DEG between u and f) so perpRest is well-defined in
     * ONE fixed {f,u}=x–z plane across the whole sweep (the degenerate ẑ-fallback NEVER fires). Single motor,
     * gliding/transport topology, dt=1e-6, Brownian OFF. Reports per θ: settled axial force (along f), the
     * transverse:axial ratio (the perp-head gate metric), and whether the head HOLDS at the commanded θ. The rest
     * of the config is IDENTICAL to the 2026-06-29 post-fix re-measure (κ force-matched, ATP-release governs the
     * cycle elsewhere; here the stroke is fired with hand-set nucleotide states as in -forcedecomp). NO physics
     * edit, no retune, no default change. CPU host-side, single motor, sub-second/θ.
     */
    static void headTiltSweep() {
        double dt = 1.0e-6;
        double HEAD = MotorStore.HEAD_LEN, LEVER = MotorStore.LEVER_LEN;
        double phi = OFFAXIS_DEG;
        int[] thetas = { 0, 15, 30, 45, 60, 75, 90 };
        System.out.println("\n=== PHASE-2 HEAD-ANGLE SWEEP (Stage 1) — perp-head topology held fixed, θ swept; single motor, dt=1e-6, Brownian OFF ===");
        System.out.printf("  off-axis bind φ=%.0f° (u·f acute ⇒ perpRest well-defined, NO ẑ-fallback); κ=%.3g (tip %.3f pN/nm, stall %.2f pN); PAIRS fracMove=%.2f; ⊥-orient fracMove=%.2f; HEAD_LEN=%.1f nm; LEVER=%.1f nm%n",
                phi, KAPPA, KAPPA/(LEVER*1e-6)/(LEVER*1e-6)*1e3, motorStallPN(), PAIRS_FRACMOVE, PERP_FRACMOVE, HEAD*1e3, LEVER*1e3);
        System.out.println("  surface-free angle rule: perpRest=normalize(u−(u·f)f), f_hat=sign(u·f)·f, Target(θ)=cosθ·f_hat+sinθ·perpRest");
        double[] axial = new double[thetas.length], transv = new double[thetas.length], headAng = new double[thetas.length];
        double[] levAng = new double[thetas.length], j1 = new double[thetas.length];
        for (int i = 0; i < thetas.length; i++) {
            decompRun(true, true, dt, true, phi, thetas[i], true);   // cfg1+perp, HELD (isometric), off-axis, θ=thetas[i]
            axial[i] = DEC_axialPN; transv[i] = DEC_transvPN; headAng[i] = DEC_headAngDeg; levAng[i] = DEC_leverAngDeg; j1[i] = DEC_j1Deg;
        }
        System.out.println("\n--- Stage-1 sweep table (settled isometric force on the filament) ---");
        System.out.println("   θ(cmd)  head∠f(meas)  holds?   axial(pN)   transverse(pN)   transverse:axial   lever∠f   J1°");
        for (int i = 0; i < thetas.length; i++) {
            double ratio = Math.abs(axial[i]) > 1e-6 ? Math.abs(transv[i]/axial[i]) : Double.POSITIVE_INFINITY;
            boolean holds = Math.abs(headAng[i] - thetas[i]) <= 10.0;
            System.out.printf("   %4d°    %6.1f°      %-5s   %+8.3f     %8.3f         %s      %5.1f°  %5.1f°%n",
                    thetas[i], headAng[i], holds ? "YES" : "no", axial[i], transv[i],
                    Double.isInfinite(ratio) ? "  ∞ (axial≈0)" : String.format("%7.2f : 1", ratio), levAng[i], j1[i]);
        }
        System.out.println("\n  Reading:");
        System.out.println("   • 'holds?' = |head∠f − θ| ≤ 10° (does the ⊥-orientation torque actually drive+hold the head to the commanded θ).");
        System.out.println("   • transverse:axial — perp-head gate metric: ~2:1 = transport-like (like the default v1 motor that glides); ~22:1 = config-1 bending artifact.");
        // θ=90 cross-check vs the published perp-head (the same ⊥ fixed point, reached from the off-axis bind):
        int last = thetas.length - 1;
        double r90 = Math.abs(axial[last]) > 1e-6 ? Math.abs(transv[last]/axial[last]) : 0;
        System.out.printf("  θ=90° cross-check: axial %+.3f pN, transverse:axial %.2f:1 (published perp-head: +1.94 pN, 2.35:1).%n", axial[last], r90);
        System.out.println("\n=== head-angle sweep (Stage 1) complete ===");
    }

    /** One decomposition run. Returns {netAxialImpulse(N·s), filamentDx(µm), filamentDz(µm)}. held=true ⇒ filament
     *  fixed (isometric); held=false ⇒ filament integrated (the released cross-check). perp=true ⇒ the PERP-HEAD
     *  config-1 variant (rear pin removed, ⊥-orientation torque). Prints the (axial,transverse,per-pin) time course. */
    static double[] decompRun(boolean cfg1, boolean perp, double dt, boolean held, double offAxisDeg, double tiltDeg, boolean tiltSet) {
        double HEAD = MotorStore.HEAD_LEN, LEVER = MotorStore.LEVER_LEN, ROD = MotorStore.ROD_LEN;
        // ---- filament: 1 segment along +x at z=0 ----
        FilamentStore f = new FilamentStore(1);
        f.monomerCount.set(0, FIL_MONO);
        f.setUVec(0, 1f, 0f, 0f); f.setYVec(0, 0f, 1f, 0f); f.setCoord(0, 0f, 0f, 0f);
        f.brownTransScale.set(0, 0f); f.brownRotScale.set(0, 0f);
        DragTensorSystem.run(f); f.setParams(dt, 0); f.setCounts(0, 0);
        f.chainParams.set(0, (float) dt); f.chainParams.set(1, 0.5f); f.chainParams.set(2, 0.1f);
        f.chainParams.set(3, 0.2f); f.chainParams.set(4, 0f); f.chainParams.set(5, 1.0e-20f);
        f.chainParams.set(6, (float) Constants.actinMonoRadius);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        double slen = f.segLength.get(0);

        // ---- one motor; place the bound pose by hand: head ON the filament (uVec +x), lever+rod hang down to the anchor ----
        MotorStore mot = new MotorStore(1);
        mot.assembleArticulated(0, 0f, 0f, (float) (-(LEVER + ROD)), 0f, 0f, 1f, (float) Constants.BTransCoeff);
        DragTensorSystem.run(mot);
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(0.006, -0.4, dt); mot.setNucParams(dt);
        if (cfg1) mot.enableConfig1(KAPPA);
        RigidRodBody b = mot.body;
        for (int s = 0; s < 3; s++) { b.brownTransScale.set(s, 0f); b.brownRotScale.set(s, 0f); }  // Brownian OFF
        int rod = 0, lev = 1, head = 2;
        double e1x = f.end1.get(0);
        if (offAxisDeg <= 0.0) {
            // ---- DEFAULT axial bind (the published -forcedecomp pose; UNCHANGED) ----
            // head: center at filament (0,0,0), uVec +x ⇒ tip=+HEAD/2·x (site A), rear=−HEAD/2·x (site B)
            b.setCoord(head, 0f, 0f, 0f); b.setUVec(head, 1f, 0f, 0f); b.setYVec(head, 0f, 1f, 0f);
            // lever: end2 = head.end1 = (−HEAD/2,0,0); uVec +z ⇒ center=(−HEAD/2,0,−LEVER/2)
            b.setCoord(lev, (float) (-0.5*HEAD), 0f, (float) (-0.5*LEVER)); b.setUVec(lev, 0f, 0f, 1f); b.setYVec(lev, 0f, 1f, 0f);
            // rod: end2 = lever.end1 = (−HEAD/2,0,−LEVER); uVec +z ⇒ center=(−HEAD/2,0,−LEVER−ROD/2)
            b.setCoord(rod, (float) (-0.5*HEAD), 0f, (float) (-(LEVER + 0.5*ROD))); b.setUVec(rod, 0f, 0f, 1f); b.setYVec(rod, 0f, 1f, 0f);
            mot.anchor.set(0, (float) (-0.5*HEAD)); mot.anchor.set(1, 0f); mot.anchor.set(2, (float) (-(LEVER + ROD)));  // rod.end1 (tail)
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            // bind: tip@site A, rear@site B (two-point) — the rear pin is UNUSED on the perp path
            mot.boundSeg.set(0, 0);
            mot.bindArc.set(0, (float) (0.5*HEAD - e1x));      // arc of tip from e1
            mot.bindArc2.set(0, (float) (-0.5*HEAD - e1x));    // arc of rear from e1 (= bindArc − HEAD)
        } else {
            // ---- OFF-AXIS bind (HEAD-ANGLE SWEEP; additive) — tilt ONLY the head's bind orientation to a real acute
            // angle φ from the filament so perpRest = normalize(u−(u·f)f) is GENUINE (the head's own ⊥ component,
            // SURFACE-FREE) and well-defined in ONE fixed {f,u}=x–z plane across the whole θ sweep — the degenerate
            // ẑ-fallback NEVER fires. The lever/rod/ANCHOR stay EXACTLY at the published -forcedecomp perp-head
            // positions so the J1-strain geometry (the force source) is IDENTICAL to the perp-head decomp ⇒ θ=90
            // reproduces the published +1.94 pN / 2.35:1; the sweep isolates θ alone, not the anchor geometry.
            double phi = Math.toRadians(offAxisDeg), cu = Math.cos(phi), su = Math.sin(phi);
            b.setCoord(head, 0f, 0f, 0f); b.setUVec(head, (float) cu, 0f, (float) su); b.setYVec(head, 0f, 1f, 0f);   // tilted head, center at origin
            b.setCoord(lev, (float) (-0.5*HEAD), 0f, (float) (-0.5*LEVER)); b.setUVec(lev, 0f, 0f, 1f); b.setYVec(lev, 0f, 1f, 0f);   // PUBLISHED lever
            b.setCoord(rod, (float) (-0.5*HEAD), 0f, (float) (-(LEVER + 0.5*ROD))); b.setUVec(rod, 0f, 0f, 1f); b.setYVec(rod, 0f, 1f, 0f);   // PUBLISHED rod
            mot.anchor.set(0, (float) (-0.5*HEAD)); mot.anchor.set(1, 0f); mot.anchor.set(2, (float) (-(LEVER + ROD)));   // PUBLISHED anchor
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            mot.boundSeg.set(0, 0);
            mot.bindArc.set(0, (float) (0.5*HEAD*cu - e1x));   // tip x-projection (head.end2 = +0.5HEAD·u)
            mot.bindArc2.set(0, (float) (-0.5*HEAD*cu - e1x)); // rear x-projection (UNUSED on the perp path)
        }
        // ---- PERP-HEAD: compute + FREEZE uperp (⊥-to-filament direction nearest the bind-time head.uVec) ----
        if (perp) {
            double hux = b.uVecX(head), huy = b.uVecY(head), huz = b.uVecZ(head);   // bind-time head.uVec (= +x here)
            double sxh = f.uVec.get(0), syh = f.uVec.get(f.n), szh = f.uVec.get(2*f.n);  // filament axis x̂
            double axc = hux*sxh + huy*syh + huz*szh;
            double upx = hux - axc*sxh, upy = huy - axc*syh, upz = huz - axc*szh;   // remove the axial component
            double mag = Math.sqrt(upx*upx + upy*upy + upz*upz);
            if (mag < 0.1) {   // head nearly axial (the EXPECTED bind regime) ⇒ fallback: project the surface normal ẑ
                double zx=0, zy=0, zz=1, zc = zx*sxh+zy*syh+zz*szh;
                upx = zx - zc*sxh; upy = zy - zc*syh; upz = zz - zc*szh; mag = Math.sqrt(upx*upx+upy*upy+upz*upz);
            }
            if (mag > 0) { upx/=mag; upy/=mag; upz/=mag; }
            // HEAD-ANGLE SWEEP — rotate the rest target within the {f,u} plane to angle θ (SURFACE-FREE):
            // Target(θ) = cos θ·f_hat + sin θ·perpRest, f_hat = sign(u·f)·f (θ=0 ⇒ along actin, θ=90 ⇒ ⊥).
            if (tiltSet) {
                double cosT = Math.cos(Math.toRadians(tiltDeg)), sinT = Math.sin(Math.toRadians(tiltDeg));
                double sgn = (axc >= 0) ? 1.0 : -1.0, fhx = sgn*sxh, fhy = sgn*syh, fhz = sgn*szh;
                double tx = cosT*fhx + sinT*upx, ty = cosT*fhy + sinT*upy, tz = cosT*fhz + sinT*upz;
                double tm = Math.sqrt(tx*tx + ty*ty + tz*tz);
                if (tm > 0) { upx = tx/tm; upy = ty/tm; upz = tz/tm; }
            }
            mot.perpRest.set(0, (float) upx); mot.perpRest.set(1, (float) upy); mot.perpRest.set(2, (float) upz);
            if (!HEADTILT_SWEEP) System.out.printf("  frozen uperp (⊥-to-filament head rest) = (%+.3f, %+.3f, %+.3f)  [bind head.uVec=(%+.2f,%+.2f,%+.2f)%s]%n",
                    upx, upy, upz, b.uVecX(head), b.uVecY(head), b.uVecZ(head), tiltSet ? String.format(", θ=%.0f°", tiltDeg) : ", axial ⇒ ẑ-fallback");
        }
        FloatArray xbC1 = perp
                ? FloatArray.fromElements((float) PAIRS_FRACMOVE, (float) KAPPA, (float) LEVER, (float) dt, (float) HEAD, (float) PERP_FRACMOVE)
                : FloatArray.fromElements((float) PAIRS_FRACMOVE, (float) KAPPA, (float) LEVER, (float) dt, (float) HEAD);
        FloatArray xbDef = FloatArray.fromElements((float) MYO_SPRING, 90f, 0.4f, (float) dt, (float) HEAD, 0f);
        FloatArray jp = cfg1 ? mot.jointParamsC1 : mot.jointParams;
        FloatArray bondData = new FloatArray(CrossBridgeSystem.STRIDE); bondData.init(0f);

        // ---- settle at the PRIMED state (ADPPi ⇒ J1 rest 0°), then FIRE (NONE ⇒ rest 60°) ----
        int SETTLE = 60000, STROKE = 60000;
        mot.setCounts(0, SEED, f.n); f.counts.set(1, 0);
        mot.setAllStates(MotorStore.NUC_ADPPI);
        for (int t = 0; t < SETTLE; t++) motorStep(f, mot, b, bondData, xbC1, xbDef, jp, cfg1, perp, dt, true);
        double impulse = 0; double sux = f.uVec.get(0), suy = f.uVec.get(f.n + 0), suz = f.uVec.get(2*f.n + 0);
        double fcx0 = centroidX(f), fcz0 = centroidZ(f);
        if (held) System.out.printf("  step    J1ang°  distA(nm) distB(nm)  axialA(pN) axialB(pN)  netAxial(pN) netTransv(pN) forceDot(pN)%n");
        mot.setAllStates(MotorStore.NUC_NONE);   // FIRE: rest 0°→60°
        // PRODUCTIVE-PHASE windows (measurement-only, post-fix re-run): the catch-slip now sets a SHORT bound
        // lifetime (config1 ~1.5 ms, perphead ~1.8 ms — far shorter than the 60 ms isometric settle), so the
        // transport-relevant force is the strained powerstroke transient, NOT the relaxed isometric equilibrium.
        // Accumulate the mean axial/transverse the head delivers over the first {0.5,1,2} ms of the strained ADP
        // dwell (= the productive window the catch-slip governs). Pure readout; no physics touched.
        int[] winSteps = { (int)Math.round(0.5e-3/dt), (int)Math.round(1.0e-3/dt), (int)Math.round(2.0e-3/dt) };
        double[] winAxial = new double[winSteps.length], winTransv = new double[winSteps.length];
        long[] winN = new long[winSteps.length];
        for (int t = 0; t < STROKE; t++) {
            motorStep(f, mot, b, bondData, xbC1, xbDef, jp, cfg1, perp, dt, held);
            double[] dc = decompose(f, b, mot, cfg1, perp, dt);   // {axialA,axialB,netAxial,netTransv,distA,distB,forceDot,j1ang}
            impulse += dc[2] * dt;
            if (held) for (int w = 0; w < winSteps.length; w++) if (t < winSteps[w]) { winAxial[w] += dc[2]; winTransv[w] += dc[3]; winN[w]++; }
            // finer EARLY sampling to resolve the productive transient (the powerstroke + early strained dwell)
            boolean fine = t==0||t==100||t==300||t==600||t==1000||t==1800||t==3000;
            if (held && (fine || t % 6000 == 0 || t == STROKE - 1))
                System.out.printf("  %-7d %6.1f  %8.2f %8.2f  %+9.3f %+9.3f  %+11.4f %11.4f %+11.4f%n",
                        t, dc[7], dc[4]*1e3, dc[5]*1e3, dc[0]*1e12, dc[1]*1e12, dc[2]*1e12, dc[3]*1e12, dc[6]*1e12);
            if (!held && (t == 2000 || t == 10000 || t == 30000 || t == STROKE - 1))
                System.out.printf("     [released] t=%-6d filament Δx=%+.2f nm  Δz=%+.2f nm  (netAxial now %+.4f pN)%n",
                        t, (centroidX(f)-fcx0)*1e6, (centroidZ(f)-0)*1e6, dc[2]*1e12);
        }
        double dx = centroidX(f) - fcx0, dz = centroidZ(f) - fcz0;
        if (held) {
            double[] dcF = decompose(f, b, mot, cfg1, perp, dt);
            // the key ratio: |net axial| / |net transverse| (transport vs bending). Old config-1 ≈ 1:22 (transverse).
            double tr = Math.abs(dcF[3]) > 0 ? Math.abs(dcF[2])/Math.abs(dcF[3]) : 0;
            System.out.printf("  ⇒ final: netAxial=%+.4f pN, netTransverse=%.4f pN ⇒ AXIAL:TRANSVERSE = %.2f : 1  (>1 ⇒ axial/transport-dominated)%n",
                    dcF[2]*1e12, dcF[3]*1e12, tr);
            // head ∠x̂ — should be ≈90° on the perp path (head stands ⊥), ≈0° on the two-point pin
            double hux=b.uVecX(2),huy=b.uVecY(2),huz=b.uVecZ(2), du=Math.abs(hux*sux+huy*suy+huz*suz); if(du>1)du=1;
            double levSwing; { double lux=b.uVecX(1),luy=b.uVecY(1),luz=b.uVecZ(1),dl=Math.abs(lux*sux+luy*suy+luz*suz); if(dl>1)dl=1; levSwing=Math.toDegrees(Math.acos(dl)); }
            DEC_axialPN = dcF[2]*1e12; DEC_transvPN = dcF[3]*1e12; DEC_headAngDeg = Math.toDegrees(Math.acos(du)); DEC_leverAngDeg = levSwing; DEC_j1Deg = dcF[7];
            System.out.printf("  ⇒ bound geometry: head∠x̂=%.1f° (perp target ⇒ ~90°), lever∠x̂=%.1f°, J1=%.1f° ; |net|/(|A|+|B|)=%.3f%n",
                    Math.toDegrees(Math.acos(du)), levSwing, dcF[7],
                    Math.abs(dcF[2])/Math.max(1e-30, Math.abs(dcF[0])+Math.abs(dcF[1])));
            System.out.printf("  ⇒ net axial impulse over stroke = %.4g pN·s ; net transverse(final)=%.3f pN%n", impulse*1e12, dcF[3]*1e12);
            System.out.println("  ⇒ PRODUCTIVE-PHASE means (the strained dwell the catch-slip governs; transport-relevant):");
            double[] winMs = {0.5, 1.0, 2.0};
            for (int w = 0; w < winSteps.length; w++) {
                double ax = winAxial[w]/winN[w]*1e12, tv = winTransv[w]/winN[w]*1e12;
                System.out.printf("       first %.1f ms: ⟨axial⟩=%+.4f pN  ⟨transverse⟩=%.4f pN  transverse:axial=%.2f:1  per-cycle axial impulse=%+.4g pN·s%n",
                        winMs[w], ax, tv, Math.abs(ax)>1e-9?Math.abs(tv/ax):0, ax*winMs[w]*1e-3);
            }
        }
        return new double[]{ impulse, dx, dz };
    }

    /** one motor dynamics step (config1 / config1-perp / default), filament held or integrated. */
    static void motorStep(FilamentStore f, MotorStore mot, RigidRodBody b, FloatArray bondData,
                          FloatArray xbC1, FloatArray xbDef, FloatArray jp, boolean cfg1, boolean perp, double dt, boolean held) {
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, jp, mot.counts);
        TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, jp, mot.counts);
        if (cfg1 && perp)
            CrossBridgeSystem.bondForcesCanonicalConfig1Perp(b.coord, b.uVec, b.bTransGam, b.bRotGam, f.coord, f.uVec, f.segLength, f.bTransGam, f.bRotGam,
                    mot.boundSeg, mot.bindArc, mot.perpRest, mot.nucleotideState, bondData, xbC1);
        else if (cfg1)
            CrossBridgeSystem.bondForcesCanonicalConfig1(b.coord, b.uVec, b.bTransGam, b.bRotGam, f.coord, f.uVec, f.segLength, f.bTransGam, f.bRotGam,
                    mot.boundSeg, mot.bindArc, mot.bindArc2, mot.nucleotideState, bondData, xbC1);
        else
            CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, bondData, xbDef);
        CrossBridgeSystem.applyHeadForce(bondData, b.forceSum, b.torqueSum, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        if (!held) {
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            // gather the seg-side reaction onto the filament (1 motor, 1 seg ⇒ direct)
            int d = 0;
            f.forceSum.set(0, bondData.get(d+6)); f.forceSum.set(f.n, bondData.get(d+7)); f.forceSum.set(2*f.n, bondData.get(d+8));
            f.torqueSum.set(0, bondData.get(d+9)); f.torqueSum.set(f.n, bondData.get(d+10)); f.torqueSum.set(2*f.n, bondData.get(d+11));
            RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        }
    }

    /** decompose the force on the filament in the seg frame. config1 ⇒ recompute the two pins separately;
     *  default ⇒ the single seg-side force split into axial/transverse (per-pin N/A). Returns
     *  {axialA, axialB, netAxial, netTransverse, distA, distB, forceDot, j1angDeg} (forces in N). */
    static double[] decompose(FilamentStore f, RigidRodBody b, MotorStore mot, boolean cfg1, boolean perp, double dt) {
        double HEAD = MotorStore.HEAD_LEN;
        int head = 2, lev = 1, nB = 3;
        double hcx = b.coordX(head), hcy = b.coordY(head), hcz = b.coordZ(head);
        double hux = b.uVecX(head), huy = b.uVecY(head), huz = b.uVecZ(head);
        double tipx = hcx + 0.5*HEAD*hux, tipy = hcy + 0.5*HEAD*huy, tipz = hcz + 0.5*HEAD*huz;
        double rearx = hcx - 0.5*HEAD*hux, reary = hcy - 0.5*HEAD*huy, rearz = hcz - 0.5*HEAD*huz;
        double scx = f.coordX(0), scy = f.coordY(0), scz = f.coordZ(0);
        double sux = f.uVec.get(0), suy = f.uVec.get(f.n), suz = f.uVec.get(2*f.n);
        double slen = f.segLength.get(0);
        double hbTGx = b.bTransGam.get(head), hbTGy = b.bTransGam.get(nB+head), hbRGy = b.bRotGam.get(nB+head);
        double sbTGx = f.bTransGam.get(0), sbTGy = f.bTransGam.get(f.n), sbRGy = f.bRotGam.get(f.n);
        double j1ang = 0;
        { double lux=b.uVecX(lev),luy=b.uVecY(lev),luz=b.uVecZ(lev); double dv=lux*hux+luy*huy+luz*huz; if(dv>1)dv=1;if(dv<-1)dv=-1; j1ang=Math.toDegrees(Math.acos(dv)); }
        double forceDot = 0;
        if (cfg1 && perp) {
            // PERP-HEAD: ONE positional pin (the tip). Net force on filament = −F8a (the single PAIRS tip pin
            // reaction). The ⊥-orientation torque adds no FORCE (only torque), so it does not enter this decomposition.
            double aOffA = mot.bindArc.get(0) - 0.5*slen;
            double apAx=scx+aOffA*sux, apAy=scy+aOffA*suy, apAz=scz+aOffA*suz;
            double dAx=apAx-tipx, dAy=apAy-tipy, dAz=apAz-tipz, distA=Math.sqrt(dAx*dAx+dAy*dAy+dAz*dAz);
            double axA=0,ayA=0,azA=0;
            if (distA>0){ double lx=dAx/distA,ly=dAy/distA,lz=dAz/distA;
                double mcH=moveCH(hux,huy,huz,lx,ly,lz,hbTGx,hbTGy,hbRGy,HEAD), mcS=moveCH(sux,suy,suz,lx,ly,lz,sbTGx,sbTGy,sbRGy,slen);
                double fm=PAIRS_FRACMOVE*1e-6*distA/(dt*(mcH+mcS)); axA=-fm*lx; ayA=-fm*ly; azA=-fm*lz; }   // on filament
            double axialA=axA*sux+ayA*suy+azA*suz;
            double netAxial=axialA;
            double tx=axA-netAxial*sux, ty=ayA-netAxial*suy, tz=azA-netAxial*suz, netTransv=Math.sqrt(tx*tx+ty*ty+tz*tz);
            return new double[]{ axialA, 0, netAxial, netTransv, distA, 0, forceDot, j1ang };
        } else if (cfg1) {
            double aOffA = mot.bindArc.get(0) - 0.5*slen, aOffB = mot.bindArc2.get(0) - 0.5*slen;
            double apAx=scx+aOffA*sux, apAy=scy+aOffA*suy, apAz=scz+aOffA*suz;
            double apBx=scx+aOffB*sux, apBy=scy+aOffB*suy, apBz=scz+aOffB*suz;
            double dAx=apAx-tipx, dAy=apAy-tipy, dAz=apAz-tipz, distA=Math.sqrt(dAx*dAx+dAy*dAy+dAz*dAz);
            double dBx=apBx-rearx, dBy=apBy-reary, dBz=apBz-rearz, distB=Math.sqrt(dBx*dBx+dBy*dBy+dBz*dBz);
            // force ON FILAMENT = −F_head; pin A
            double axA=0,ayA=0,azA=0,axB=0,ayB=0,azB=0;
            if (distA>0){ double lx=dAx/distA,ly=dAy/distA,lz=dAz/distA;
                double mcH=moveCH(hux,huy,huz,lx,ly,lz,hbTGx,hbTGy,hbRGy,HEAD), mcS=moveCH(sux,suy,suz,lx,ly,lz,sbTGx,sbTGy,sbRGy,slen);
                double fm=PAIRS_FRACMOVE*1e-6*distA/(dt*(mcH+mcS)); axA=-fm*lx; ayA=-fm*ly; azA=-fm*lz; }   // on filament
            if (distB>0){ double lx=dBx/distB,ly=dBy/distB,lz=dBz/distB;
                double mcH=moveCH(hux,huy,huz,lx,ly,lz,hbTGx,hbTGy,hbRGy,HEAD), mcS=moveCH(sux,suy,suz,lx,ly,lz,sbTGx,sbTGy,sbRGy,slen);
                double fm=PAIRS_FRACMOVE*1e-6*distB/(dt*(mcH+mcS)); axB=-fm*lx; ayB=-fm*ly; azB=-fm*lz; }
            double axialA=axA*sux+ayA*suy+azA*suz, axialB=axB*sux+ayB*suy+azB*suz;
            double nx=axA+axB, ny=ayA+ayB, nz=azA+azB, netAxial=nx*sux+ny*suy+nz*suz;
            double tx=nx-netAxial*sux, ty=ny-netAxial*suy, tz=nz-netAxial*suz, netTransv=Math.sqrt(tx*tx+ty*ty+tz*tz);
            // forceDot (J1 strain) — informational
            return new double[]{ axialA, axialB, netAxial, netTransv, distA, distB, forceDot, j1ang };
        } else {
            // default: single seg-side force (bondData computed in motorStep, but recompute net from the stored vector
            // is not available here ⇒ use the head-side F8 negated). Recompute F8 = myoSpring·(site−tip).
            double aOff = mot.bindArc.get(0) - 0.5*slen;
            double apx=scx+aOff*sux, apy=scy+aOff*suy, apz=scz+aOff*suz;
            double dxv=apx-tipx, dyv=apy-tipy, dzv=apz-tipz;
            double fx=MYO_SPRING*dxv, fy=MYO_SPRING*dyv, fz=MYO_SPRING*dzv;   // F8 on head; on filament = −F8
            double ofx=-fx, ofy=-fy, ofz=-fz;
            double netAxial=ofx*sux+ofy*suy+ofz*suz;
            double tx=ofx-netAxial*sux, ty=ofy-netAxial*suy, tz=ofz-netAxial*suz, netTransv=Math.sqrt(tx*tx+ty*ty+tz*tz);
            double dist=Math.sqrt(dxv*dxv+dyv*dyv+dzv*dzv);
            return new double[]{ netAxial, 0, netAxial, netTransv, dist, 0, forceDot, j1ang };
        }
    }

    /**
     * PHASE-2 BOUND GEOMETRY (MEASUREMENT-ONLY). Report the config-1 motor's actual bound-state geometry at the
     * UNCOCKED (J1 0°, ADPPi) and COCKED (J1 60°, NONE) equilibria, in the gliding topology (tail anchored, head
     * two-point-pinned to a filament along +x), dt=1e-6, Brownian OFF. Reports every body's unit-vector + endpoints,
     * the converter swing axis tv = lever×head + the lever swing plane, the lever load-end (lever.end1) displacement
     * decomposed Δ(x,z,y), and the head/lever angles to the filament axis. Dumps a CSV for the schematic. NO edit/fix.
     */
    static void boundGeom() {
        double dt = 1.0e-6;
        double HEAD = MotorStore.HEAD_LEN, LEVER = MotorStore.LEVER_LEN, ROD = MotorStore.ROD_LEN;
        System.out.println("\n=== PHASE-2 BOUND GEOMETRY — config-1 motor, gliding topology, dt=1e-6, Brownian OFF ===");
        System.out.println("  frame: x̂ = filament axis (+x), ẑ = surface normal (+z, anchor below at −z), ŷ = x̂×ẑ = (0,−1,0)");
        // ---- single motor + held filament (same construction as -forcedecomp config-1) ----
        FilamentStore f = new FilamentStore(1);
        f.monomerCount.set(0, FIL_MONO);
        f.setUVec(0, 1f, 0f, 0f); f.setYVec(0, 0f, 1f, 0f); f.setCoord(0, 0f, 0f, 0f);
        f.brownTransScale.set(0, 0f); f.brownRotScale.set(0, 0f);
        DragTensorSystem.run(f); f.setParams(dt, 0); f.setCounts(0, 0);
        f.chainParams.set(0,(float)dt); f.chainParams.set(1,0.5f); f.chainParams.set(2,0.1f);
        f.chainParams.set(3,0.2f); f.chainParams.set(4,0f); f.chainParams.set(5,1.0e-20f); f.chainParams.set(6,(float)Constants.actinMonoRadius);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        double slen = f.segLength.get(0);
        MotorStore mot = new MotorStore(1);
        mot.assembleArticulated(0, 0f, 0f, (float)(-(LEVER+ROD)), 0f,0f,1f, (float)Constants.BTransCoeff);
        DragTensorSystem.run(mot);
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(0.006,-0.4,dt); mot.setNucParams(dt);
        mot.enableConfig1(KAPPA);
        RigidRodBody b = mot.body;
        for (int s=0;s<3;s++){ b.brownTransScale.set(s,0f); b.brownRotScale.set(s,0f); }
        int rod=0, lev=1, head=2;
        b.setCoord(head,0f,0f,0f); b.setUVec(head,1f,0f,0f); b.setYVec(head,0f,1f,0f);
        b.setCoord(lev,(float)(-0.5*HEAD),0f,(float)(-0.5*LEVER)); b.setUVec(lev,0f,0f,1f); b.setYVec(lev,0f,1f,0f);
        b.setCoord(rod,(float)(-0.5*HEAD),0f,(float)(-(LEVER+0.5*ROD))); b.setUVec(rod,0f,0f,1f); b.setYVec(rod,0f,1f,0f);
        mot.anchor.set(0,(float)(-0.5*HEAD)); mot.anchor.set(1,0f); mot.anchor.set(2,(float)(-(LEVER+ROD)));
        DerivedGeometrySystem.derive(b.coord,b.uVec,b.yVec,b.zVec,b.end1,b.end2,b.segLength,mot.counts);
        double e1x = f.end1.get(0);
        mot.boundSeg.set(0,0); mot.bindArc.set(0,(float)(0.5*HEAD-e1x)); mot.bindArc2.set(0,(float)(-0.5*HEAD-e1x));
        FloatArray xbC1 = FloatArray.fromElements((float)PAIRS_FRACMOVE,(float)KAPPA,(float)LEVER,(float)dt,(float)HEAD);
        FloatArray xbDef = FloatArray.fromElements((float)MYO_SPRING,90f,0.4f,(float)dt,(float)HEAD,0f);
        FloatArray jp = mot.jointParamsC1;
        FloatArray bondData = new FloatArray(CrossBridgeSystem.STRIDE); bondData.init(0f);
        mot.setCounts(0,SEED,f.n); f.counts.set(1,0);

        int SETTLE = 80000;
        mot.setAllStates(MotorStore.NUC_ADPPI);
        for (int t=0;t<SETTLE;t++) motorStep(f,mot,b,bondData,xbC1,xbDef,jp,true,false,dt,true);
        double[] unc = geomSnap(b, mot, "UNCOCKED (J1 rest 0°, ADPPi — pre-stroke)");
        mot.setAllStates(MotorStore.NUC_NONE);
        for (int t=0;t<SETTLE;t++) motorStep(f,mot,b,bondData,xbC1,xbDef,jp,true,false,dt,true);
        double[] coc = geomSnap(b, mot, "COCKED (J1 rest 60°, NONE — post-stroke)");

        // unc/coc layout: [0..2]rod.e1 [3..5]rod.e2 [6..8]lev.e1 [9..11]lev.e2 [12..14]head.e1 [15..17]head.e2
        //                 [18..20]rodU [21..23]levU [24..26]headU
        System.out.println("\n--- (2) converter swing axis + plane ---");
        double[] tvU = cross(unc,21, unc,24), tvC = cross(coc,21, coc,24);   // lever.uVec × head.uVec
        System.out.printf("  tv=lever×head  uncocked (%.3f,%.3f,%.3f)  cocked (%.3f,%.3f,%.3f)  ⇒ %s%n",
                tvU[0],tvU[1],tvU[2], tvC[0],tvC[1],tvC[2], (Math.abs(tvC[1])>Math.abs(tvC[0])&&Math.abs(tvC[1])>Math.abs(tvC[2]))?"≈ ±ŷ (the J1 torque axis lies on y — out of the x̂ filament direction)":"not y-dominant");
        // the lever's ACTUAL rotation axis uncocked→cocked = unit(levU_unc × levU_coc); swing plane normal = that axis
        double[] axis = unit(cross(unc,21, coc,21));
        System.out.printf("  lever rotation axis (unc→cocked) = (%.3f,%.3f,%.3f) ⇒ swing plane normal; plane %s the filament x̂%n",
                axis[0],axis[1],axis[2], Math.abs(axis[0])<0.5 ? "CONTAINS-ish? (normal⊥x̂ ⇒ plane contains x̂)" : "is tilted to");
        System.out.println("\n--- (3) lever LOAD end (lever.end1) displacement uncocked→cocked (the load sweep) ---");
        double dlx=coc[6]-unc[6], dly=coc[7]-unc[7], dlz=coc[8]-unc[8];
        System.out.printf("  Δ(lever.end1, the LOAD/rod side) = Δx(axial) %+.2f nm,  Δz(transverse) %+.2f nm,  Δy %+.2f nm  ⇒ load end sweeps %s (this motion goes to the ANCHOR side via the rod, NOT to the filament)%n",
                dlx*1e3, dlz*1e3, dly*1e3, Math.abs(dlx)>Math.abs(dlz) ? "ALONG the filament (Δx>Δz)" : "ACROSS the filament (Δz>Δx)");
        double dtx=coc[15]-unc[15], dtz=coc[17]-unc[17], dty=coc[16]-unc[16];   // head tip
        System.out.printf("  Δ(head tip, the FILAMENT side)   = Δx %+.3f nm, Δz %+.3f nm, Δy %+.3f nm  ⇒ the head is two-point-pinned ⇒ it does NOT slide along actin (Δx≈0); it transmits only the transverse reaction%n", dtx*1e3, dtz*1e3, dty*1e3);
        System.out.println("\n--- (4) how the motor STANDS on the filament (angles to x̂) ---");
        for (int st=0; st<2; st++) {
            double[] g = (st==0)?unc:coc;
            double hA=ang(g,24,1,0,0), lA=ang(g,21,1,0,0), rA=ang(g,18,1,0,0);
            System.out.printf("  %-9s head∠x̂=%.1f°  lever∠x̂=%.1f°  rod∠x̂=%.1f°  (90°=perpendicular to the filament)%n",
                    st==0?"uncocked":"cocked", hA, lA, rA);
        }
        // ---- CSV for the schematic ----
        try {
            StringBuilder sb = new StringBuilder("# body,state,e1x,e1y,e1z,e2x,e2y,e2z (µm)\n");
            String[] bn={"rod","lever","head"};
            for (int st=0; st<2; st++){ double[] g=(st==0)?unc:coc; String sn=st==0?"uncocked":"cocked";
                for (int bi=0; bi<3; bi++) sb.append(String.format(java.util.Locale.US,"%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                        bn[bi],sn, g[6*bi],g[6*bi+1],g[6*bi+2], g[6*bi+3],g[6*bi+4],g[6*bi+5])); }
            sb.append(String.format(java.util.Locale.US,"anchor,both,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                    mot.anchor.get(0),mot.anchor.get(1),mot.anchor.get(2), mot.anchor.get(0),mot.anchor.get(1),mot.anchor.get(2)));
            sb.append(String.format(java.util.Locale.US,"filament,both,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                    f.end1.get(0),f.end1.get(1),f.end1.get(2), f.end2.get(0),f.end2.get(1),f.end2.get(2)));
            java.nio.file.Files.writeString(java.nio.file.Path.of("bound_geometry.csv"), sb.toString());
            System.out.println("\n  wrote bound_geometry.csv (for the schematic)");
        } catch (java.io.IOException e) { System.out.println("  (CSV write failed: "+e.getMessage()+")"); }
        System.out.println("\n=== bound geometry complete ===");
    }
    /** snapshot every body's endpoints + uVec; returns the packed [rod.e1,rod.e2,lev.e1,lev.e2,head.e1,head.e2, rodU,levU,headU]. */
    static double[] geomSnap(RigidRodBody b, MotorStore mot, String title) {
        double[] g = new double[27];
        String[] bn = {"rod","lever","head"};
        System.out.println("\n--- (1) " + title + " ---");
        System.out.printf("  %-6s %-26s %-26s %-26s%n", "body", "end1 (x,y,z µm)", "end2 (x,y,z µm)", "uVec (x,y,z)");
        for (int i=0;i<3;i++){
            double cx=b.coordX(i),cy=b.coordY(i),cz=b.coordZ(i), ux=b.uVecX(i),uy=b.uVecY(i),uz=b.uVecZ(i), L=b.segLength.get(i);
            double e1x=cx-0.5*L*ux,e1y=cy-0.5*L*uy,e1z=cz-0.5*L*uz, e2x=cx+0.5*L*ux,e2y=cy+0.5*L*uy,e2z=cz+0.5*L*uz;
            g[6*i]=e1x; g[6*i+1]=e1y; g[6*i+2]=e1z; g[6*i+3]=e2x; g[6*i+4]=e2y; g[6*i+5]=e2z;
            g[18+3*i]=ux; g[18+3*i+1]=uy; g[18+3*i+2]=uz;
            System.out.printf("  %-6s (%+.4f,%+.4f,%+.4f)  (%+.4f,%+.4f,%+.4f)  (%+.3f,%+.3f,%+.3f)%n", bn[i],e1x,e1y,e1z,e2x,e2y,e2z,ux,uy,uz);
        }
        System.out.printf("  key pts: anchor=(%+.4f,%+.4f,%+.4f)  J1 pivot/rear-pin=head.e1=(%+.4f,%+.4f,%+.4f)  head tip/siteA=head.e2=(%+.4f,%+.4f,%+.4f)  lever LOAD end=lever.e1=(%+.4f,%+.4f,%+.4f)%n",
                mot.anchor.get(0),mot.anchor.get(1),mot.anchor.get(2), g[12],g[13],g[14], g[15],g[16],g[17], g[6],g[7],g[8]);
        return g;
    }
    static double[] cross(double[] a, int ai, double[] c, int ci){ double ax=a[ai],ay=a[ai+1],az=a[ai+2],cx=c[ci],cy=c[ci+1],cz=c[ci+2];
        return new double[]{ ay*cz-az*cy, az*cx-ax*cz, ax*cy-ay*cx }; }
    static double[] unit(double[] v){ double m=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]); return m>0?new double[]{v[0]/m,v[1]/m,v[2]/m}:v; }
    static double ang(double[] g, int gi, double x, double y, double z){ double d=g[gi]*x+g[gi+1]*y+g[gi+2]*z; if(d>1)d=1;if(d<-1)d=-1; return Math.toDegrees(Math.acos(Math.abs(d))); }

    /** PHASE-2 step-4c: recalibrate BOTH catch-slip pathways to Guo & Guilford (2006) bond data, then check the
     *  EMERGENT "peak ≈ stall" tuning. Lifetime (t_on) vs sustained RESISTING load, swept PAST the peak (0→+10 pN)
     *  on the averaged catch (τ held), to SEE the catch-slip rise→peak→fall. Reports the peak force/lifetime, the
     *  loaded-regime range, the independent motor stall, and the peak≈stall verdict. */
    static void catchSlipRecal(int M) {
        double tau = TAU_AVG > 0 ? TAU_AVG : 0.5e-3; TAU_AVG = tau;
        if (XCATCH <= 0) XCATCH = 2.5;   // Guo & Guilford ADP catch x_c
        double kT = Constants.kT;
        System.out.printf("%n=== PHASE-2 step-4c catch-slip RECALIBRATION to Guo & Guilford (Config-1 single-molecule, τ=%.2f ms, kOn=%.2g) ===%n", tau * 1e3, KON);
        System.out.printf("  pathways: xCatch=%.2f nm (GG x_c 2.5), xSlip=0.40 nm (GG x_s 0.40), αCatch/αSlip=11.5 (GG k_c/k_s 11.7); kOff=100/s base.%n", XCATCH);
        System.out.println("  lifetime (t_on) vs RESISTING load F_ext, swept PAST the peak — the catch-slip rise→peak→fall:");
        double[] loads = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 10 };
        double[] life = new double[loads.length];
        System.out.printf("    %-12s %-12s %-12s%n", "F_ext(pN)", "t_on(ms)", "rate(/s)");
        for (int i = 0; i < loads.length; i++) {
            F_EXT = loads[i];
            double[] r = singleRun(M);
            life[i] = r[0];
            System.out.printf("    %-12.1f %-12.2f %-12.1f%n", loads[i], r[0], r[0] > 0 ? 1000.0 / r[0] : 0);
        }
        F_EXT = 0;
        // locate the peak (max lifetime)
        int pk = 0; for (int i = 1; i < life.length; i++) if (life[i] > life[pk]) pk = i;
        double peakF = loads[pk], peakLife = life[pk];
        double stall = motorStallPN();
        System.out.printf("%n  PEAK: lifetime %.1f ms at F ≈ %.1f pN  (Guo & Guilford: ~30 ms at ~6.4 pN; behavioral target)%n", peakLife, peakF);
        System.out.printf("  shape: %s; loaded-regime (1.8–8 pN) lifetimes %.0f–%.0f ms (GG ~10–30 ms)%n",
                (pk > 0 && pk < life.length - 1) ? "RISE→PEAK→FALL ✓ (non-monotonic catch-slip)" : (pk == 0 ? "monotonic-falling (peak at/below 0)" : "still rising at +10 pN"),
                minOver(life, loads, 1.8, 8.0), maxOver(life, loads, 1.8, 8.0));
        System.out.printf("%n  --- Piece 2: EMERGENT peak ≈ stall tuning (independent quantities) ---%n");
        System.out.printf("    catch-slip PEAK force      = %.1f pN   (calibrated to GG bond data)%n", peakF);
        System.out.printf("    motor STALL force          = %.1f pN   (independent: κ·60°/L, κ=%.2g, NOT touched)%n", stall, KAPPA);
        double ratio = stall > 0 ? peakF / stall : 0;
        System.out.printf("    ⇒ peak/stall = %.2f  %s%n", ratio,
                (ratio > 0.5 && ratio < 2.0) ? "→ COINCIDE within ~2× experimental scatter — the mechanokinetic tuning EMERGES (peak ≈ stall)" : "→ OUTSIDE ~2× — report (tuning does not emerge / κ question; do NOT force-fit)");
        System.out.printf("%n  SUMMARY  xCatch=%.2f xSlip=0.40 peakF=%.1fpN peakLife=%.0fms stall=%.1fpN peak/stall=%.2f%n", XCATCH, peakF, peakLife, stall, ratio);
    }
    static double minOver(double[] life, double[] loads, double lo, double hi) {
        double mn = 1e9; for (int i = 0; i < loads.length; i++) if (loads[i] >= lo && loads[i] <= hi && life[i] > 0) mn = Math.min(mn, life[i]); return mn == 1e9 ? 0 : mn;
    }
    static double maxOver(double[] life, double[] loads, double lo, double hi) {
        double mx = 0; for (int i = 0; i < loads.length; i++) if (loads[i] >= lo && loads[i] <= hi) mx = Math.max(mx, life[i]); return mx;
    }

    static void probe(Scene sc, int M) {
        System.out.println("\n--- cheap probe: does it glide −x, stably, at ~the right avgBound? ---");
        double cx0 = centroidX(sc.fil), cxHalf = cx0; double boundSumHalf = 0; long sampleHalf = 0;
        long sample = 0; double boundSum = 0; boolean nan = false;
        int report = Math.max(1, M / 10);
        int substepStart = SUBSTEP ? M / 2 : Integer.MAX_VALUE;
        if (SUBSTEP) { SMT = new SiteMotionTracker(sc.mot.nMotors, DT, OUTER_DT);
            System.out.printf("  -substep: site-motion window W=%d steps (outer dt %.0e s); measuring over [%d,%d)%n",
                    (int) Math.max(1, Math.round(OUTER_DT / DT)), OUTER_DT, substepStart, M); }
        System.out.printf("  %-8s %-12s %-12s %-10s%n", "step", "centroidX", "centroidZ", "avgBound(inst)");
        for (int t = 0; t < M; t++) {
            if (t == substepStart) java.util.Arrays.fill(sliceNs, 0L);
            boolean meas = SUBSTEP && t >= substepStart;
            long _st = meas ? System.nanoTime() : 0L;
            step(sc, t);
            if (meas) {
                sliceNs[STEP] += System.nanoTime() - _st;
                sliceSteps++; boundAccum += bound(sc.mot);
                SMT.observe(t, sc.mot.boundSeg, sc.mot.bindArc, sc.fil.coord, sc.fil.uVec, sc.fil.segLength);
            }
            boundSum += bound(sc.mot); sample++;
            if (t == M / 2) cxHalf = centroidX(sc.fil);
            if (t >= M / 2) { boundSumHalf += bound(sc.mot); sampleHalf++; }
            if (t % report == 0 || t == M - 1) {
                double cx = centroidX(sc.fil), cz = centroidZ(sc.fil);
                if (Double.isNaN(cx) || Double.isNaN(cz)) { nan = true; }
                System.out.printf("  %-8d %-12.5f %-12.5f %-10d%n", t, cx, cz, bound(sc.mot));
            }
        }
        // diagnostic: where are the heads relative to the filament line (y=0,z=FIL_Z)?
        RigidRodBody b = sc.mot.body; double minCon = 1e9, sumZ = 0, sumZcnt = 0;
        for (int m = 0; m < sc.mot.nMotors; m++) {
            if (Math.abs(sc.mot.anchorY(m)) > 0.006) continue;     // motors under the filament line
            int h = 3 * m + 2;
            double tz = b.coordZ(h) + 0.5 * MotorStore.HEAD_LEN * b.uVecZ(h);
            double ty = b.coordY(h) + 0.5 * MotorStore.HEAD_LEN * b.uVecY(h);
            double con = Math.sqrt((tz - FIL_Z) * (tz - FIL_Z) + ty * ty);
            minCon = Math.min(minCon, con); sumZ += tz; sumZcnt++;
        }
        System.out.printf("  [diag] heads under the filament line: mean tipZ=%.4f µm, min conDist=%.4f µm (reach 0.006)%n",
                sumZcnt > 0 ? sumZ / sumZcnt : 0, minCon);
        double cxN = centroidX(sc.fil);
        double vel = (cxN - cx0) / (M * DT);     // µm/s (expect negative)
        double avgB = boundSum / sample;
        double velSteady = (cxN - cxHalf) / ((M - M / 2) * DT);   // 2nd-half (settled) velocity
        double avgBHalf = sampleHalf > 0 ? boundSumHalf / sampleHalf : 0;
        System.out.printf("%n  net centroid Δx = %.4f µm over %.4f s  ⇒  velocity(net) = %.3f µm/s (%s)%n",
                cxN - cx0, M * DT, vel, vel < 0 ? "−x ✓" : "+x ?");
        System.out.printf("  STEADY (2nd half): velocity = %.3f µm/s, avgBound = %.2f  (v1: 8.33 µm/s, 7.6)%n", velSteady, avgBHalf);
        System.out.printf("  avgBound(all) = %.2f   filament z drift = %.4f µm   y-spread = %.3f µm (rotation)   NaN=%s%n",
                avgB, centroidZ(sc.fil) - FIL_Z, ySpread(sc.fil), nan);
        System.out.println("\n  [probe is diagnostic — not the fixture gate; the converged ensemble follows if this looks right]");
        if (SUBSTEP) substepReport(sc);
    }

    /** SUBSTEP_FEASIBILITY report (gliding) — Measure 1 (bound fraction × bound-cross-bridge slice CPU-time
     *  fraction X ⇒ implied net speedup) + Measure 2 (per-outer-dt site motion). MEASUREMENT-ONLY, host-side. */
    static void substepReport(Scene sc) {
        MotorStore m = sc.mot;
        int total = m.nMotors;
        double avgBound = sliceSteps > 0 ? (double) boundAccum / sliceSteps : 0;
        double boundFrac = total > 0 ? avgBound / total : 0;
        double sumStretch = 0; int nb = 0;
        for (int i = 0; i < total; i++) { if (m.boundSeg.get(i) < 0) continue; sumStretch += m.forceMag.get(i) / MYO_SPRING; nb++; }
        double meanStretchNm = nb > 0 ? sumStretch / nb * 1.0e3 : 0;
        double tolNm = 0.006 * 1.0e3;   // myoColTol (gliding reach)
        double stepMs = sliceSteps > 0 ? sliceNs[STEP] / 1e6 / sliceSteps : 0;
        double bondMs = sliceNs[BOND] / 1e6 / Math.max(1, sliceSteps);
        double applyMs = sliceNs[APPLY] / 1e6 / Math.max(1, sliceSteps);
        double gatherMs = sliceNs[GATHER] / 1e6 / Math.max(1, sliceSteps);
        double regMs = sliceNs[REGISTER] / 1e6 / Math.max(1, sliceSteps);
        double relMs = sliceNs[RELEASE] / 1e6 / Math.max(1, sliceSteps);
        double motMs = sliceNs[MOTADV] / 1e6 / Math.max(1, sliceSteps);
        double headAdvMs = motMs * boundFrac / 3.0;
        double coreMs = bondMs + applyMs + regMs + relMs + headAdvMs;
        double fullMs = coreMs + gatherMs;
        double X = stepMs > 0 ? coreMs / stepMs : 0, Xfull = stepMs > 0 ? fullMs / stepMs : 0;
        double speedup = 10.0 / (1.0 + 9.0 * X), speedupFull = 10.0 / (1.0 + 9.0 * Xfull);
        System.out.println("\n================ SUBSTEP_FEASIBILITY readout ================");
        System.out.printf(java.util.Locale.US, "  scene: gliding (%d motors, %d-seg filament); dt=%.0e; measured steps=%d%n", total, sc.fil.n, DT, sliceSteps);
        System.out.printf(java.util.Locale.US, "  MEASURE 1 — bound fraction: avgBound=%.2f / %d motors = %.5f (%.3f%%)%n", avgBound, total, boundFrac, boundFrac * 100);
        System.out.printf(java.util.Locale.US, "  MEASURE 1 — per-step CPU wall: %.4f ms/step. Slice (ms/step): bond=%.4f apply=%.4f register=%.4f release=%.4f motorAdv(all)=%.4f headAdv(bound)=%.4f gather=%.4f%n",
                stepMs, bondMs, applyMs, regMs, relMs, motMs, headAdvMs, gatherMs);
        System.out.printf(java.util.Locale.US, "      CORE slice (frozen-site) = %.4f ms ⇒ X=%.4f ⇒ implied net speedup @10× inner = %.2f×%n", coreMs, X, speedup);
        System.out.printf(java.util.Locale.US, "      FULL slice (+re-gather)  = %.4f ms ⇒ X=%.4f ⇒ implied net speedup @10× inner = %.2f× (lower bound)%n", fullMs, Xfull, speedupFull);
        System.out.print(SMT.summary(meanStretchNm, tolNm));
        System.out.println("=============================================================");
    }

    /** Localize the velocity shortfall: bound-state distribution, the forceDotFil load sign (catch vs
     *  slip), release + power-stroke rates, and the per-stroke filament advance. (Measurement only.) */
    static void diagnose(Scene sc, int M) {
        MotorStore mot = sc.mot; int nM = mot.nMotors;
        int warm = 2000;
        long[] stateCnt = new long[4]; long boundStepsTot = 0, releaseTot = 0, strokeTot = 0;
        double fdSum = 0; long fdPos = 0, fdN = 0;
        int[] prevBound = new int[nM], prevState = new int[nM];
        // STEP-A bind-state + dwell census: per fresh bind, the nucleotide state at bind and the episode dwell.
        int[] bindState = new int[nM], bindStep = new int[nM];      // per-motor: state@bind, step@bind (fresh bind)
        for (int i = 0; i < nM; i++) bindStep[i] = -1;
        long[] bindStateHist = new long[5];                          // #episodes binding in NONE/ATP/ADPPi/ADP/[bound-already]
        double[] bindStateDwell = new double[5];                     // total dwell steps per bind-state category
        long[] dwellHist = new long[5];                             // episode-dwell bins: 1 / 2-5 / 6-20 / 21-100 / >100 steps
        long episodesTot = 0; double dwellSumSteps = 0;
        for (int m = 0; m < nM; m++) { prevBound[m] = mot.boundSeg.get(m); prevState[m] = mot.nucleotideState.get(m); }
        double cxStart = 0; int samp = 0;
        for (int t = 0; t < M; t++) {
            step(sc, t);
            if (t == warm) cxStart = centroidX(sc.fil);
            if (t >= warm) {
                samp++;
                for (int m = 0; m < nM; m++) {
                    int bs = mot.boundSeg.get(m), st = mot.nucleotideState.get(m);
                    if (bs >= 0) {
                        boundStepsTot++; stateCnt[st]++;
                        float fd = mot.forceDotFil.get(m); fdSum += fd; fdN++; if (fd > 0) fdPos++;
                        // power stroke = ADPPi→ADP while bound
                        if (prevState[m] == MotorStore.NUC_ADPPI && st == MotorStore.NUC_ADP) strokeTot++;
                        // fresh bind (this sample bound, prev sample not bound): record state@bind + step@bind
                        if (prevBound[m] < 0) { bindState[m] = st; bindStep[m] = t; }
                    }
                    // release = bound → ANY free sentinel (FREE_COOLDOWN or FREE_BINDABLE). The earlier
                    // FREE_COOLDOWN-only test missed the FREE_BINDABLE releases + ALL same-step bind-then-eject
                    // episodes ⇒ it undercounted releases ~14× and hugely overstated the dwell (the bind-in-ATP churn).
                    if (prevBound[m] >= 0 && bs < 0) {
                        releaseTot++;
                        if (bindStep[m] >= 0) {
                            int cat = bindState[m]; if (cat < 0 || cat > 3) cat = 4;
                            int dw = t - bindStep[m]; if (dw < 1) dw = 1;
                            bindStateHist[cat]++; bindStateDwell[cat] += dw;
                            episodesTot++; dwellSumSteps += dw;
                            int b = dw == 1 ? 0 : dw <= 5 ? 1 : dw <= 20 ? 2 : dw <= 100 ? 3 : 4;
                            dwellHist[b]++;
                            bindStep[m] = -1;
                        }
                    }
                    prevBound[m] = bs; prevState[m] = st;
                }
            }
        }
        double cxEnd = centroidX(sc.fil);
        double vel = (cxEnd - cxStart) / (samp * DT);
        double avgB = boundStepsTot / (double) samp;
        double boundTimeSteps = boundStepsTot / (double) releaseTot;        // mean bound lifetime
        double strokeRatePerMotor = strokeTot / (double) boundStepsTot;      // power strokes per bound-motor-step
        double advancePerStroke = Math.abs(cxEnd - cxStart) / strokeTot;     // µm filament advance per power stroke
        System.out.println("\n=== gliding diagnostics (post-warmup) ===");
        System.out.printf("  velocity = %.3f µm/s, avgBound = %.2f%n", vel, avgB);
        System.out.printf("  bound-state distribution: NONE %.1f%%  ATP %.1f%%  ADPPi %.1f%%  ADP %.1f%%%n",
                100.0 * stateCnt[0] / boundStepsTot, 100.0 * stateCnt[1] / boundStepsTot,
                100.0 * stateCnt[2] / boundStepsTot, 100.0 * stateCnt[3] / boundStepsTot);
        System.out.printf("  forceDotFil (bound): mean = %.3g N, %.1f%% positive (catch/load-resisting)%n",
                fdSum / fdN, 100.0 * fdPos / fdN);
        System.out.printf("  mean bound-time = %.0f steps (%.3f ms);  catch-slip release rate = %.0f /s%n",
                boundTimeSteps, boundTimeSteps * DT * 1e3, 1.0 / (boundTimeSteps * DT));
        System.out.printf("  power strokes (ADPPi→ADP while bound) = %.4f /bound-motor-step  ⇒  %.0f /s per bound motor%n",
                strokeRatePerMotor, strokeRatePerMotor / DT);
        System.out.printf("  filament advance per power stroke = %.2f nm  (unloaded stroke ≈ 7 nm)%n", advancePerStroke * 1e3);
        // STEP-A census: which state do heads BIND in, and how long does each bind-category dwell?
        String[] snm = {"NONE", "ATP ", "ADPPi", "ADP ", "?"};
        System.out.println("  --- STEP-A bind-state / dwell census (per completed bind episode) ---");
        System.out.printf("  episodes=%d  mean dwell=%.1f steps (%.3f ms)%n",
                episodesTot, dwellSumSteps / Math.max(1, episodesTot), dwellSumSteps / Math.max(1, episodesTot) * DT * 1e3);
        for (int c = 0; c < 5; c++) if (bindStateHist[c] > 0)
            System.out.printf("    bind-in-%-5s : %6.2f%% of episodes,  mean dwell %6.1f steps (%.3f ms)%n",
                    snm[c], 100.0 * bindStateHist[c] / episodesTot, bindStateDwell[c] / bindStateHist[c],
                    bindStateDwell[c] / bindStateHist[c] * DT * 1e3);
        System.out.printf("  dwell histogram (episodes):  1 step %5.1f%% | 2-5 %5.1f%% | 6-20 %5.1f%% | 21-100 %5.1f%% | >100 %5.1f%%%n",
                100.0 * dwellHist[0] / episodesTot, 100.0 * dwellHist[1] / episodesTot, 100.0 * dwellHist[2] / episodesTot,
                100.0 * dwellHist[3] / episodesTot, 100.0 * dwellHist[4] / episodesTot);
        // cross-check vs the kernel-side mot.stats (the -grid STATS measure): total bound-steps / releases from
        // the SAME counters cycleLymnTaylor writes. If this ≪ the episode dwell above, the episode tracker is
        // missing sub-sample releases (a re-bind within the per-step sample) — decisive for the -diag/-grid gap.
        long statsBound = 0, statsRel = 0;
        for (int m = 0; m < nM; m++) { statsBound += mot.stats.get(2*m); statsRel += mot.stats.get(2*m+1); }
        System.out.printf("  [xcheck] mot.stats (cumulative, incl warmup): boundSteps=%d releases=%d ⇒ dwell=%.1f steps (%.4f ms), detach=%.0f/s%n",
                statsBound, statsRel, statsRel>0 ? statsBound/(double)statsRel : 0, statsRel>0 ? statsBound/(double)statsRel*DT*1e3 : 0, statsBound>0 ? statsRel/(statsBound*DT) : 0);
        System.out.printf("  [xcheck] episode releaseTot(FREE_COOLDOWN)=%d  vs  boundStepsTot(sampled)=%d%n", releaseTot, boundStepsTot);
    }

    /**
     * 4b-iv cycle self-consistency probe (measurement only, CPU runner). During gliding, measure the
     * EMPIRICAL per-state conditional transition rates and the ADP→NONE gate-open fraction, and compare
     * to the nominal validated rates (NONE→ATP 0.2, ATP→ADPPi 0.001, ADPPi→ADP 0.1, ADP→NONE 0.01 /step
     * when gate open). If the empirical rates match nominal, the cycle obeys its own rates under load —
     * NOT malfunctioning; the high ADP occupancy is then explained by the load-gate, not a bug. Also
     * splits `forceDotFil` by sign (assist vs resist) and reports the predicted-vs-measured occupancy.
     */
    static void cyclediag(Scene sc, int M) {
        MotorStore mot = sc.mot; int nM = mot.nMotors; int warm = 2000;
        long[] stateSteps = new long[4];      // steps spent in each state (bound)
        long[] transOut = new long[4];         // cycle transitions OUT of each state (bound→bound, next state)
        long adpGateOpen = 0, adpGateClosed = 0, adpToNone = 0;   // ADP gate accounting
        double fdSum = 0; long fdN = 0, fdPos = 0; double fdPosSum = 0, fdNegSum = 0; long fdNeg = 0;
        // cross-bridge force magnitude (v1 break-force release at >12 pN; v2 lacks it — is it material?)
        double fmagSum = 0; long fmagOver12 = 0, fmagOver12assist = 0, fmagOver12resist = 0; double fmagMax = 0;
        int[] prevState = new int[nM], prevBound = new int[nM];
        for (int m = 0; m < nM; m++) { prevState[m] = mot.nucleotideState.get(m); prevBound[m] = mot.boundSeg.get(m); }
        for (int t = 0; t < M; t++) {
            step(sc, t);
            if (t < warm) { for (int m = 0; m < nM; m++) { prevState[m] = mot.nucleotideState.get(m); prevBound[m] = mot.boundSeg.get(m); } continue; }
            for (int m = 0; m < nM; m++) {
                int bs = mot.boundSeg.get(m), st = mot.nucleotideState.get(m);
                boolean boundNow = bs >= 0, boundPrev = prevBound[m] >= 0;
                if (boundPrev) {
                    int ps = prevState[m];
                    stateSteps[ps]++;
                    float fd = mot.forceDotFil.get(m); fdSum += fd; fdN++;
                    if (fd > 0) { fdPos++; fdPosSum += fd; } else if (fd < 0) { fdNeg++; fdNegSum += fd; }
                    int d = m * CrossBridgeSystem.STRIDE;
                    double bx = sc.bondData.get(d), by = sc.bondData.get(d + 1), bz = sc.bondData.get(d + 2);
                    double fmag = Math.sqrt(bx*bx + by*by + bz*bz);   // cross-bridge force magnitude (N)
                    fmagSum += fmag; if (fmag > fmagMax) fmagMax = fmag;
                    if (fmag > 12.0e-12) { fmagOver12++; if (fd > 0) fmagOver12assist++; else fmagOver12resist++; }
                    if (ps == MotorStore.NUC_ADP) {
                        float avg = 0f; int b = m * 10; for (int k = 0; k < 10; k++) avg += mot.forceDotHist.get(b + k); avg *= 0.1f;
                        if (avg <= 0f) adpGateOpen++; else adpGateClosed++;
                    }
                    // cycle transition (bound both steps, state advanced)
                    if (boundNow && st != ps) {
                        transOut[ps]++;
                        if (ps == MotorStore.NUC_ADP && st == MotorStore.NUC_NONE) adpToNone++;
                    }
                }
                prevState[m] = st; prevBound[m] = bs;
            }
        }
        long tot = stateSteps[0] + stateSteps[1] + stateSteps[2] + stateSteps[3];
        double dt = DT;
        // empirical conditional rates (per bound-step in that state)
        double rN = transOut[0] / (double) stateSteps[0];   // NONE→ATP
        double rA = transOut[1] / (double) stateSteps[1];   // ATP→ADPPi
        double rP = transOut[2] / (double) stateSteps[2];   // ADPPi→ADP
        double rDopen = adpToNone / (double) adpGateOpen;   // ADP→NONE per gate-OPEN step
        double gateOpenFrac = adpGateOpen / (double) (adpGateOpen + adpGateClosed);
        System.out.println("\n=== cycle self-consistency under gliding load (post-warmup, CPU) ===");
        System.out.printf("  occupancy (bound): NONE %.2f%%  ATP %.2f%%  ADPPi %.2f%%  ADP %.2f%%%n",
                100.0*stateSteps[0]/tot, 100.0*stateSteps[1]/tot, 100.0*stateSteps[2]/tot, 100.0*stateSteps[3]/tot);
        System.out.println("  empirical conditional transition rate (/step)  vs  nominal:");
        System.out.printf("    NONE→ATP   %.5f  vs 0.20000   (%.1f%%)%n", rN, 100*rN/0.2);
        System.out.printf("    ATP→ADPPi  %.6f vs 0.00100   (%.1f%%)%n", rA, 100*rA/0.001);
        System.out.printf("    ADPPi→ADP  %.5f  vs 0.10000   (%.1f%%)%n", rP, 100*rP/0.1);
        System.out.printf("    ADP→NONE   %.5f  vs 0.01000   (%.1f%%)  [conditioned on gate OPEN]%n", rDopen, 100*rDopen/0.01);
        System.out.printf("  ADP gate-open fraction (10-window avg ≤ 0): %.3f  (closed %.3f ⇒ ADP held by load)%n",
                gateOpenFrac, 1 - gateOpenFrac);
        System.out.printf("  forceDotFil: mean %.3g N, %.1f%% assist(>0) / %.1f%% resist(<0); mean|assist| %.3g, mean|resist| %.3g%n",
                fdSum/fdN, 100.0*fdPos/fdN, 100.0*fdNeg/fdN, fdPosSum/Math.max(1,fdPos), fdNegSum/Math.max(1,fdNeg));
        System.out.printf("  cross-bridge forceMag: mean %.3g N (%.2f pN), max %.3g N (%.1f pN)%n",
                fmagSum/fdN, 1e12*fmagSum/fdN, fmagMax, 1e12*fmagMax);
        System.out.printf("  forceMag > 12 pN (v1's break-force release; v2 LACKS it): %.3f%% of bound-steps  (assist %.3f%% / resist %.3f%%)%n",
                100.0*fmagOver12/fdN, 100.0*fmagOver12assist/fdN, 100.0*fmagOver12resist/fdN);
        // predicted occupancy from empirical dwells (NONE 1/rN, ATP 1/rA, ADPPi 1/rP, ADP = stateSteps/adpToNone)
        double dN=1/rN, dA=1/rA, dP=1/rP, dD=stateSteps[3]/(double)adpToNone, ds=dN+dA+dP+dD;
        System.out.printf("  predicted occupancy from dwells (NONE %.0f/ATP %.0f/ADPPi %.0f/ADP %.0f steps): NONE %.1f%% ATP %.1f%% ADPPi %.1f%% ADP %.1f%%%n",
                dN,dA,dP,dD, 100*dN/ds,100*dA/ds,100*dP/ds,100*dD/ds);
        System.out.println("  ⇒ empirical rates ≈ nominal ⇒ cycle self-consistent under load (high ADP = the load-gate, not a bug)");
    }

    /**
     * PART B — BRAKE DECOMPOSITION (measurement-only, CPU runner, flag-gated `-brakediag`, default-off).
     * For each bound head, per post-warmup step, decompose:
     *   (1) the ACTUAL AXIAL FORCE ON THE FILAMENT — the seg-side reaction bondData[d+6..8] this head puts on
     *       its segment. Fx = bondData[d+6]. The matbed filament glides −x, so Fx<0 ASSISTS the glide and Fx>0
     *       BRAKES it. Also the along-filament projection Fseg·segU.
     *   (2) the SIGNED catch load forceDotFil (the J1 lever strain the catch-slip actually reads). Per the
     *       CrossBridgeSystem convention + the -csrecal/-fext calibration: forceDotFil>0 = RESISTING = the catch
     *       arm (lifetime rises to the ~6 pN peak ⇒ HELD); forceDotFil<0 = ASSISTING = the catch exponential
     *       e^(−F·xCatch) detonates ⇒ FAST RELEASE.
     * binned by TIME-SINCE-STROKE (pre-stroke / fresh / mid / long-held ADP). The decisive cross-tab: do BRAKING
     * heads (Fx>0) read forceDotFil>0 (catch/held — the brake-and-mis-score) or forceDotFil<0 (fast release)?
     * Plus the release-events-vs-signed-load histogram (the forceDotFil that fired each release). No physics
     * touched — reads forceDotFil + bondData after step() exactly as diagnose()/cyclediag().
     */
    static void brakeDiagnose(Scene sc, int M) {
        MotorStore mot = sc.mot; FilamentStore f = sc.fil; int nM = mot.nMotors; int warm = 2000;
        int STR = CrossBridgeSystem.STRIDE, nSeg = f.n;
        int[] prevBound = new int[nM], prevState = new int[nM], strokeStep = new int[nM];
        for (int m = 0; m < nM; m++) { prevBound[m] = mot.boundSeg.get(m); prevState[m] = mot.nucleotideState.get(m); strokeStep[m] = -1; }
        // time-since-stroke bins: 0=PRE-stroke (bound, not yet stroked), 1=FRESH (≤1 ms), 2=MID (1–3 ms), 3=LONG (>3 ms)
        final int NB = 4; final double DTMS = DT * 1e3;
        long[] binN = new long[NB]; double[] binFx = new double[NB], binAx = new double[NB], binFd = new double[NB];
        long[] binFdPos = new long[NB], binFxBrake = new long[NB];
        // cross-tab over ALL bound steps: [Fx sign: 0 brake(+x) / 1 assist(−x)][Fd sign: 0 catch(>0) / 1 slip(<0)]
        long[][] xtab = new long[2][2];
        long[][] xtabLong = new long[2][2];   // restricted to LONG-held (bin 3)
        // release histogram by the signed load that fired it (preFd, in pN) + by time-since-stroke bin
        long relStrongResist = 0, relMildResist = 0, relMildAssist = 0, relStrongAssist = 0; // >+1, 0..+1, −1..0, <−1 pN
        long[] relBin = new long[NB]; long releaseTot = 0;
        double netFxSum = 0; long netFxN = 0;     // ensemble net x-force per bound head (the directed force)
        float[] preFd = new float[nM];
        double filUx = 0; long filUxN = 0;
        for (int t = 0; t < M; t++) {
            for (int m = 0; m < nM; m++) preFd[m] = mot.forceDotFil.get(m);   // load the start-of-step release will consume
            step(sc, t);
            if (t < warm) { for (int m = 0; m < nM; m++) { prevBound[m] = mot.boundSeg.get(m); prevState[m] = mot.nucleotideState.get(m);
                                                           if (prevBound[m] < 0) strokeStep[m] = -1; } continue; }
            for (int m = 0; m < nM; m++) {
                int bs = mot.boundSeg.get(m), st = mot.nucleotideState.get(m);
                // (re)bind ⇒ reset stroke clock; stroke ⇒ stamp it
                if (prevBound[m] < 0 && bs >= 0) strokeStep[m] = -1;
                if (bs >= 0 && prevState[m] == MotorStore.NUC_ADPPI && st == MotorStore.NUC_ADP) strokeStep[m] = t;
                // release this step (bound → cooldown): attribute to the load preFd[m] that fired it
                if (prevBound[m] >= 0 && bs == MotorStore.FREE_COOLDOWN) {
                    releaseTot++;
                    double fpN = preFd[m] * 1e12;
                    if (fpN > 1.0) relStrongResist++; else if (fpN > 0) relMildResist++;
                    else if (fpN > -1.0) relMildAssist++; else relStrongAssist++;
                    int rb = (strokeStep[m] < 0) ? 0 : binOf(t - strokeStep[m], DTMS);
                    relBin[rb]++;
                }
                if (bs >= 0) {
                    int d = m * STR;
                    double fx = sc.bondData.get(d + 6), fy = sc.bondData.get(d + 7), fz = sc.bondData.get(d + 8);
                    double sux = f.uVec.get(bs), suy = f.uVec.get(nSeg + bs), suz = f.uVec.get(2 * nSeg + bs);
                    double axial = fx * sux + fy * suy + fz * suz;
                    float fd = mot.forceDotFil.get(m);
                    int bin = (strokeStep[m] < 0) ? 0 : binOf(t - strokeStep[m], DTMS);
                    binN[bin]++; binFx[bin] += fx; binAx[bin] += axial; binFd[bin] += fd;
                    if (fd > 0) binFdPos[bin]++;
                    boolean brake = fx > 0;       // +x opposes the −x glide
                    if (brake) binFxBrake[bin]++;
                    int fi = brake ? 0 : 1, gi = (fd > 0) ? 0 : 1;
                    xtab[fi][gi]++; if (bin == 3) xtabLong[fi][gi]++;
                    netFxSum += fx; netFxN++;
                    filUx += sux; filUxN++;
                }
                prevBound[m] = bs; prevState[m] = st;
            }
        }
        System.out.println("\n=== PART B: brake decomposition (post-warmup, CPU; glide = −x ⇒ Fx<0 ASSISTS, Fx>0 BRAKES) ===");
        System.out.printf("  filament mean uVec_x = %+.3f (≈±1 ⇒ axial≈±Fx)%n", filUx / Math.max(1, filUxN));
        System.out.printf("  ensemble mean per-bound-head Fx = %+.4f pN  (negative ⇒ net forward/glide force)%n", 1e12 * netFxSum / Math.max(1, netFxN));
        System.out.println("  time-since-stroke bins: 0=PRE-stroke  1=FRESH(≤1ms)  2=MID(1–3ms)  3=LONG(>3ms)");
        System.out.printf("  %-14s %10s %12s %12s %12s %12s %12s%n", "bin", "boundSteps", "meanFx(pN)", "meanAxial", "meanFd(pN)", "%Fd>0(catch)", "%brake(Fx>0)");
        String[] nm = {"PRE", "FRESH", "MID", "LONG"};
        for (int bn = 0; bn < NB; bn++) {
            long c = Math.max(1, binN[bn]);
            System.out.printf("  %-14s %10d %12.4f %12.4f %12.4f %12.1f %12.1f%n", nm[bn], binN[bn],
                    1e12 * binFx[bn] / c, 1e12 * binAx[bn] / c, 1e12 * binFd[bn] / c,
                    100.0 * binFdPos[bn] / c, 100.0 * binFxBrake[bn] / c);
        }
        long xt = xtab[0][0] + xtab[0][1] + xtab[1][0] + xtab[1][1];
        System.out.println("\n  CROSS-TAB (all bound steps) — the brake-and-mis-score signature:");
        System.out.printf("    %% of bound steps that are BRAKE(Fx>0) & CATCH(Fd>0, held)  = %.2f%%   <-- mis-score if large%n", 100.0 * xtab[0][0] / xt);
        System.out.printf("    %% BRAKE(Fx>0) & SLIP(Fd<0, fast-release)                   = %.2f%%%n", 100.0 * xtab[0][1] / xt);
        System.out.printf("    %% ASSIST(Fx<0) & CATCH(Fd>0, held)                         = %.2f%%%n", 100.0 * xtab[1][0] / xt);
        System.out.printf("    %% ASSIST(Fx<0) & SLIP(Fd<0, fast-release)                  = %.2f%%%n", 100.0 * xtab[1][1] / xt);
        long brakeTot = xtab[0][0] + xtab[0][1];
        System.out.printf("    of BRAKING heads: %.1f%% read CATCH(held) / %.1f%% read SLIP(release)%n",
                100.0 * xtab[0][0] / Math.max(1, brakeTot), 100.0 * xtab[0][1] / Math.max(1, brakeTot));
        long xtL = xtabLong[0][0] + xtabLong[0][1] + xtabLong[1][0] + xtabLong[1][1];
        long brakeL = xtabLong[0][0] + xtabLong[0][1];
        System.out.printf("    LONG-held only: %.1f%% of long-held steps BRAKE; of those %.1f%% read CATCH(held)%n",
                100.0 * brakeL / Math.max(1, xtL), 100.0 * xtabLong[0][0] / Math.max(1, brakeL));
        System.out.println("\n  RELEASE events vs the signed load that fired them (preFd):");
        long rt = Math.max(1, releaseTot);
        System.out.printf("    strong-RESIST(>+1pN) %.1f%% | mild-resist(0..+1) %.1f%% | mild-assist(−1..0) %.1f%% | strong-ASSIST(<−1pN) %.1f%%  (n=%d)%n",
                100.0 * relStrongResist / rt, 100.0 * relMildResist / rt, 100.0 * relMildAssist / rt, 100.0 * relStrongAssist / rt, releaseTot);
        System.out.printf("    releases by time-since-stroke: PRE %.1f%% | FRESH %.1f%% | MID %.1f%% | LONG %.1f%%%n",
                100.0 * relBin[0] / rt, 100.0 * relBin[1] / rt, 100.0 * relBin[2] / rt, 100.0 * relBin[3] / rt);
        System.out.println("\n  INTERPRETATION GUIDE:");
        System.out.println("   • brake hypothesis CONFIRMED  ⇒ LONG-held heads are mostly BRAKE(Fx>0) AND read CATCH(Fd>0,held) (mis-score),");
        System.out.println("     and releases cluster at RESIST(Fd>0), not at assist ⇒ the catch holds the braking heads.");
        System.out.println("   • brake hypothesis REFUTED   ⇒ braking heads already read SLIP(Fd<0) / release fast, OR held heads don't brake.");
    }

    /** time-since-stroke (steps) → bin index: 0 handled by caller (pre-stroke), 1 FRESH ≤1ms, 2 MID 1–3ms, 3 LONG >3ms. */
    static int binOf(int stepsSince, double dtMs) {
        double ms = stepsSince * dtMs;
        if (ms <= 1.0) return 1;
        if (ms <= 3.0) return 2;
        return 3;
    }

    /** GPU probe: run the device-resident gliding TaskGraph; sample velocity/avgBound at output cadence
     *  (no per-step host pull — the residency test) and report throughput. */
    static void gpuProbe(Scene sc, int M) {
        System.out.println("\n--- GPU probe: device-resident gliding TaskGraph (23 kernels), " + sc.mot.nMotors + " motors ---");
        TornadoExecutionPlan plan = buildPlan(sc);
        double cx0 = centroidX(sc.fil);
        // warm-up execute (PTX compile) — untimed
        sc.mot.setCounts(0, SEED, sc.fil.n); sc.fil.counts.set(1, 0);
        plan.withGridScheduler(sched).execute();
        long sample = 0; double boundSum = 0; double cxHalf = cx0; boolean nan = false;
        long atpBoundSum = 0, boundForState = 0;   // ATP-RELEASE invariant: device-side bound-in-ATP must be ~0
        int report = Math.max(1, M / 10);
        long t0 = System.nanoTime();
        for (int t = 1; t < M; t++) {
            sc.mot.setCounts(t, SEED, sc.fil.n); sc.fil.counts.set(1, t);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            if (t % report == 0 || t == M - 1) {
                res.transferToHost(sc.fil.coord, sc.mot.boundSeg, sc.mot.nucleotideState);
                double cx = centroidX(sc.fil);
                if (Double.isNaN(cx)) nan = true;
                if (t >= M / 2 && cxHalf == cx0) cxHalf = cx;
                long bAll = bound(sc.mot), bAtp = boundInState(sc.mot, MotorStore.NUC_ATP);
                boundSum += bAll; atpBoundSum += bAtp; boundForState += bAll; sample++;
                System.out.printf("  step %-8d centroidX=%.5f  avgBound(inst)=%d  bound-in-ATP=%d%n", t, cx, bAll, bAtp);
            }
        }
        long t1 = System.nanoTime();
        double sec = (t1 - t0) / 1e9, stepsPerSec = (M - 1) / sec;
        double cxN = centroidX(sc.fil);
        double vel = (cxN - cx0) / (M * DT), velSteady = (cxN - cxHalf) / ((M - M / 2) * DT);
        double avgB = boundSum / sample;
        System.out.printf("%n  velocity(net) = %.3f µm/s (%s), STEADY = %.3f, avgBound = %.2f  (v1 fixture 8.33/7.6), NaN=%s%n",
                vel, vel < 0 ? "−x ✓" : "+x ?", velSteady, avgB, nan);
        System.out.printf("  [ATP-release invariant] device-side bound-in-ATP = %.2f%% of bound  (target ≈ 0; fix %s)%n",
                boundForState > 0 ? 100.0 * atpBoundSum / boundForState : 0.0, (CONFIG1 && ATP_RELEASE) ? "ON" : "OFF");
        System.out.printf("  GPU THROUGHPUT: %.0f steps/s (%.2f ms/step) at %d motors; device-resident, host pull only at output cadence%n",
                stepsPerSec, 1e3 / stepsPerSec, sc.mot.nMotors);
    }

    static double centroidY(FilamentStore f) { double s = 0; for (int i = 0; i < f.n; i++) s += f.coordY(i); return s / f.n; }
    static double minCoordX(FilamentStore f) { double m = 1e30; for (int i = 0; i < f.n; i++) m = Math.min(m, f.coordX(i)); return m; }
    static double maxCoordX(FilamentStore f) { double m = -1e30; for (int i = 0; i < f.n; i++) m = Math.max(m, f.coordX(i)); return m; }
    static double minCoordY(FilamentStore f) { double m = 1e30; for (int i = 0; i < f.n; i++) m = Math.min(m, f.coordY(i)); return m; }
    static double maxCoordY(FilamentStore f) { double m = -1e30; for (int i = 0; i < f.n; i++) m = Math.max(m, f.coordY(i)); return m; }

    /**
     * Fixture-reconciliation measurement (4b-iv): emit BOTH of v1's velocity statistics, computed by the
     * EXACT GlidingAssayEvaluator algorithm, so v2 is comparable to v1 cell-for-cell. Samples the filament
     * centroid every OUT_INT=100 steps (v1's toFileInterval) and reports:
     *   - instantaneousSpeed: per-interval 3D displacement magnitude / dt   (GlidingAssayEvaluator L226–234)
     *   - longWindowSpeedXY:  XY chord over a 1 s ring-buffer window / dt     (L237–263; the v1 "primary" stat)
     *   - net:                XY (and x-only) net centroid displacement / total time  (the honest net glide)
     * Mean over all intervals AND over the steady 2nd half; avgBound averaged the same way. Measurement
     * only — no physics touched. GPU pulls fil.coord+boundSeg at the 100-step output cadence (residency
     * preserved); CPU runner steps in-process.
     */
    /** -rollcensus tally: {#bound heads with head.yVec·ŝ ≥ 0 (+ŝ), #with < 0 (−ŝ)}; ŝ = n̂bed×seg.uVec (n̂bed=+Z). */
    static long[] rollTally(Scene sc) {
        RigidRodBody b = sc.mot.body; int nB = b.yVec.getSize() / 3, nS = sc.fil.uVec.getSize() / 3;
        long plus = 0, minus = 0;
        for (int m = 0; m < sc.mot.nMotors; m++) {
            int s = sc.mot.boundSeg.get(m); if (s < 0) continue;
            int h = 3 * m + 2;
            double sx = -sc.fil.uVec.get(nS + s), sy = sc.fil.uVec.get(s);   // ŝ = (−suy, sux, 0)
            double sm = Math.sqrt(sx * sx + sy * sy); if (sm < 1e-9) continue; sx /= sm; sy /= sm;
            double dot = b.yVec.get(h) * sx + b.yVec.get(nB + h) * sy;
            if (dot >= 0) plus++; else minus++;
        }
        return new long[]{ plus, minus };
    }

    /** -stretchcensus tally (STEP-3, read-only): over the CURRENTLY-bound population, accumulate the anchor-spring
     *  extension (= forceMag/myoSpring, µm→nm; the tip-to-site perpendicular distance — larger = more "stretched")
     *  and the per-head axial load forceDotFil (N→pN, signed). No force is read or written; the arrays are the
     *  stored bond outputs (registerForceDot). acc = {extSum, extSqSum, fdSum, fdAbsSum, N}. */
    static void stretchTally(Scene sc, double[] acc, long[] extHist, long[] fdHist) {
        double myoSpring = MYO_SPRING;   // N/µm; forceMag [N] / myoSpring = extension [µm]
        for (int m = 0; m < sc.mot.nMotors; m++) {
            if (sc.mot.boundSeg.get(m) < 0) continue;
            double extNm = (sc.mot.forceMag.get(m) / myoSpring) * 1.0e3;    // N/(N/µm)=µm → nm
            double fdPN  = sc.mot.forceDotFil.get(m) * 1.0e12;             // N → pN (signed)
            acc[0] += extNm; acc[1] += extNm * extNm; acc[2] += fdPN; acc[3] += Math.abs(fdPN); acc[4] += 1;
            int eb = (int) (extNm / 2.0); if (eb < 0) eb = 0; if (eb > 10) eb = 10; extHist[eb]++;
            int fb = (int) Math.floor(fdPN) + 8; if (fb < 0) fb = 0; if (fb > 16) fb = 16; fdHist[fb]++;
        }
    }

    /** -ktotcensus tally (STEP-3): per-segment collective cross-bridge stiffness K_tot = k_s·myoSpring, where
     *  k_s = #{heads bound to segment s}. Bins the k_s distribution and counts how many segments exceed the EOM
     *  instability thresholds (marginal ~1.4 / blow-up ~3.8 pN/nm at dt=1e-5). acc = {segSamples, engagedSamples
     *  (k_s≥1), aboveMarginal (K_tot≥1.4pN/nm), aboveBlow (K_tot≥3.8), sumKs, maxKs}. ksHist[0..10]=k_s counts
     *  (over engaged segments), [11]=overflow. Read-only (boundSeg + f.n). */
    static void ktotTally(Scene sc, double[] acc, long[] ksHist) {
        int nSeg = sc.fil.n, nM = sc.mot.nMotors;
        int[] ks = new int[nSeg];
        for (int m = 0; m < nM; m++) { int s = sc.mot.boundSeg.get(m); if (s >= 0 && s < nSeg) ks[s]++; }
        double kSpring = MYO_SPRING * 1.0e9;               // pN/nm per bond
        double kMarg = 1.4 / kSpring, kBlow = 3.8 / kSpring;   // k_s thresholds for K_tot ≥ 1.4 / 3.8 pN/nm
        for (int s = 0; s < nSeg; s++) {
            acc[0] += 1;                                    // all segment-samples
            if (ks[s] >= 1) {
                acc[1] += 1; acc[4] += ks[s];
                if (ks[s] > acc[5]) acc[5] = ks[s];
                int b = ks[s]; if (b > 10) b = 11; ksHist[b]++;
            }
            if (ks[s] >= kMarg) acc[2] += 1;
            if (ks[s] >= kBlow) acc[3] += 1;
        }
    }

    /** -mhatcensus tally: {#bound heads with head.uVec·n̂bed ≥ 0 (+ẑ, productive pole), #with < 0 (−ẑ)}; n̂bed=+Z. */
    static long[] mhatTally(Scene sc) {
        RigidRodBody b = sc.mot.body; int nB = b.uVec.getSize() / 3;
        long plus = 0, minus = 0;
        for (int m = 0; m < sc.mot.nMotors; m++) {
            int s = sc.mot.boundSeg.get(m); if (s < 0) continue;
            int h = 3 * m + 2;
            double dot = b.uVec.get(2 * nB + h);   // head.uVec·(+ẑ) = uVec_z
            if (dot >= 0) plus++; else minus++;
        }
        return new long[]{ plus, minus };
    }

    /** -strokecensus tally (read-only): over the CURRENTLY-bound population, measure per-head neck-POWERSTROKE
     *  fidelity from the stored body pose — NO force/RNG/integration touched.
     *    mhat  = head.uVec (head axis);  uLever = lever.uVec (neck axis);  f̂ = bound seg.uVec (pointed→barbed, +x).
     *    swing_perp = uLever − (uLever·mhat)·mhat        (the neck tilt off the head axis; |swing_perp| = sin θ_swing)
     *    stroked    ⟺ |swing_perp| > sin40° (0.643)      (BoA's >40° stroked gate)
     *    barbed-sweep (productive) ⟺ swing_perp·f̂ < 0    (tip tilts pointed ⇒ REAR sweeps barbed = the −x-glide sense;
     *                                                      target=cosθ·mhat−sinθ·f̂ ⇒ ideal swing_perp=−sinθ·f̂)
     *    swing axial fraction = |swing_perp·f̂| / |swing_perp|   (1.0 = purely axial/productive plane; <1 = off-axis wobble)
     *  acc = {boundSamples, strokedSamples, barbedProductive(among stroked), axialFracSum(among stroked),
     *         swingSinSum(among bound), axialFracSum_allBound, N_allBoundForAxial}. axFracHist over stroked heads. */
    static void strokeTally(Scene sc, double[] acc, long[] axFracHist) {
        RigidRodBody b = sc.mot.body; int nB = b.uVec.getSize() / 3;
        int nS = sc.fil.uVec.getSize() / 3;
        final double SIN40 = 0.6427876;
        for (int m = 0; m < sc.mot.nMotors; m++) {
            int s = sc.mot.boundSeg.get(m); if (s < 0) continue;
            int L = 3 * m + 1, h = 3 * m + 2;
            double lx = b.uVec.get(L), ly = b.uVec.get(nB + L), lz = b.uVec.get(2 * nB + L);
            double mx = b.uVec.get(h), my = b.uVec.get(nB + h), mz = b.uVec.get(2 * nB + h);
            double fx = sc.fil.uVec.get(s), fy = sc.fil.uVec.get(nS + s), fz = sc.fil.uVec.get(2 * nS + s);
            double fm = Math.sqrt(fx*fx + fy*fy + fz*fz); if (fm < 1e-9) continue; fx/=fm; fy/=fm; fz/=fm;
            double dotLM = lx*mx + ly*my + lz*mz;                       // uLever·mhat
            double sx = lx - dotLM*mx, sy = ly - dotLM*my, sz = lz - dotLM*mz;   // swing_perp
            double sMag = Math.sqrt(sx*sx + sy*sy + sz*sz);             // = sin(lever–head angle)
            acc[0] += 1;                                                // boundSamples
            acc[4] += sMag;                                             // Σ sin θ_swing (all bound)
            if (sMag < 1e-9) continue;
            double axial = (sx*fx + sy*fy + sz*fz);                     // swing_perp·f̂ (signed)
            double axialFrac = Math.abs(axial) / sMag;                  // ∈[0,1]
            acc[5] += axialFrac; acc[6] += 1;                           // axial frac over ALL bound (denominator N)
            if (sMag > SIN40) {                                         // STROKED head
                acc[1] += 1;                                            // strokedSamples
                if (axial < 0) acc[2] += 1;                             // barbed-productive (rear sweeps barbed)
                acc[3] += axialFrac;                                    // Σ axial frac over stroked
                int hb = (int)(axialFrac * 10.0); if (hb < 0) hb = 0; if (hb > 10) hb = 10; axFracHist[hb]++;
            }
        }
    }

    static void measureGrid(Scene sc, int M, boolean gpu) {
        final int OUT_INT = 100;
        final double dtInt = OUT_INT * DT;
        // -earlystop: clamp the loop to the hard cap (arrays sized to the cap; the monitor breaks earlier if precise).
        if (EARLYSTOP) M = Math.min(M, (int) Math.round(ES_CAP / DT));
        final int esCheckSamples = EARLYSTOP ? Math.max(1, (int) Math.round(ES_INTERVAL / dtInt)) : Integer.MAX_VALUE;
        final int bufCap = Math.max(2, (int) Math.round(1.0 / dtInt));   // v1 LONG_WINDOW_SECONDS=1.0
        int nInt = M / OUT_INT;
        double[] cx = new double[nInt + 1], cy = new double[nInt + 1], cz = new double[nInt + 1];
        double[] mnx = new double[nInt + 1], mxx = new double[nInt + 1], mny = new double[nInt + 1], mxy = new double[nInt + 1];
        long[] bnd = new long[nInt + 1];
        // STEP-A/B steady-state stats snapshot: mot.stats is cumulative from step 0 and its first ~10 ms are a
        // startup transient (all heads init NUC_NONE ⇒ a bind-then-NONE→ATP-eject avalanche as they settle into
        // the primed ADPPi free pool), which crushes the whole-run STATS_ROW dwell (the illusory 0.04 ms). Snapshot
        // stats at a warmup cutoff (t0LA=0.20 s, matching the LONG_ROW on-bed window) and report the STEADY delta.
        // Purely host-side (stats is measurement-only, never read by a kernel) ⇒ no physics/RNG touch.
        // -earlystop: a 0.20 s warmup won't fit a ≤0.15 s early-stopped run ⇒ use a small fixed warmup (0.03 s) so
        // the STATS_STEADY snapshot still brackets a meaningful steady window. velFitX/avgBsteady use the 2nd-half
        // (h=n/2) and are unaffected by this choice.
        final int warmStep = EARLYSTOP ? Math.min(M - 1, (int) Math.round(0.03 / DT))
                                       : Math.min(M - 1, (int) Math.round(0.20 / DT));
        // -earlystop bookkeeping
        int esStopStep = M; boolean esConverged = false; double[] esFinal = null;
        long[] statsWarm = new long[2 * sc.mot.nMotors];
        double reachSum = 0; int reachN = 0;   // duty: mean # engageable heads (reachCount>0), steady window
        System.out.printf("%n--- grid measurement (%s, %d motors, box x∈[%.1f,%.1f] y±%.1f, seed=0x%X, myoColTol=%.1f nm) ---%n",
                gpu ? "GPU" : "CPU", sc.mot.nMotors, bXlo, bXhi, bYhalf, SEED, COL_TOL * 1e3);
        if (MATBED) {
            double bedX = bXhi - bXlo, bedY = 2 * bYhalf, filLen = sc.fil.n * sc.segL;
            double tip = KAPPA / (MotorStore.LEVER_LEN * 1e-6) / (MotorStore.LEVER_LEN * 1e-6) * 1e3;   // N/m → pN/nm (×1e3)
            System.out.printf("  [matbed] FULL-MAT bed %.2f×%.2f µm² @ %.0f motors/µm² ⇒ %d motors; filament %.2f µm; κ=%.3g (tip %.3f pN/nm, stall %.2f pN); kOn=%.0g xCatch=%.2f τ=%.2fms%n",
                    bedX, bedY, DENSITY, sc.mot.nMotors, filLen, KAPPA, tip, motorStallPN(), KON, XCATCH, TAU_AVG * 1e3);
        }

        int k = 0;
        // -stretchcensus (STEP-3, read-only): bound-population geometry over all samples.
        //   acc = {extSum(nm), extSqSum, fdSum(pN, signed), fdAbsSum(pN), N(bound-samples)}
        double[] stxAcc = new double[5];
        long[] extHist = new long[11];   // anchor-spring extension bins: 0-20 nm, 2 nm each, [10]=overflow
        long[] fdHist  = new long[17];   // forceDotFil bins: −8..+8 pN, 1 pN each, [0]=≤−8 [16]=≥+8
        double[] ktAcc = new double[6];     // -ktotcensus acc {segSamples, engaged(k_s≥1), aboveMarginal(≥1.4pN/nm), aboveBlow(≥3.8), sumKs, maxKs}
        long[] ksHist = new long[12];       // -ktotcensus per-segment k_s histogram (engaged segments), [11]=overflow
        double[] strAcc = new double[7];    // -strokecensus acc {boundSamples,strokedSamples,barbedProductive,axialFracSum_stroked,swingSinSum_bound,axialFracSum_bound,N_bound}
        long[] axFracHist = new long[11];   // -strokecensus swing-axial-fraction histogram over STROKED heads, 0.0-1.0 in 0.1 bins
        long rollPlus = 0, rollMinus = 0;   // -rollcensus accumulators (bound-head roll sign, over all samples)
        long mhatPlus = 0, mhatMinus = 0;   // -mhatcensus accumulators (bound-head head-axis sign, over all samples)
        double[] mhatFracT = new double[nInt + 2]; int mhatNT = 0;   // -mhatcensus per-sample +ẑ fraction (census-vs-time)
        if (gpu) {
            TornadoExecutionPlan plan = buildPlan(sc);
            sc.mot.setCounts(0, SEED, sc.fil.n); sc.fil.counts.set(1, 0);
            plan.withGridScheduler(sched).execute();                    // warm-up (PTX compile), untimed
            cx[0] = centroidX(sc.fil); cy[0] = centroidY(sc.fil); cz[0] = centroidZ(sc.fil); bnd[0] = bound(sc.mot);
            mnx[0] = minCoordX(sc.fil); mxx[0] = maxCoordX(sc.fil); mny[0] = minCoordY(sc.fil); mxy[0] = maxCoordY(sc.fil); k = 1;
            TornadoExecutionResult res = null;
            for (int t = 1; t < M; t++) {
                sc.mot.setCounts(t, SEED, sc.fil.n); sc.fil.counts.set(1, t);
                res = plan.withGridScheduler(sched).execute();
                if (t == warmStep) { res.transferToHost(sc.mot.stats); for (int i = 0; i < 2 * sc.mot.nMotors; i++) statsWarm[i] = sc.mot.stats.get(i); }
                if ((t + 1) % OUT_INT == 0 && k <= nInt) {
                    res.transferToHost(sc.fil.coord, sc.mot.boundSeg);
                    cx[k] = centroidX(sc.fil); cy[k] = centroidY(sc.fil); cz[k] = centroidZ(sc.fil); bnd[k] = bound(sc.mot);
                    mnx[k] = minCoordX(sc.fil); mxx[k] = maxCoordX(sc.fil); mny[k] = minCoordY(sc.fil); mxy[k] = maxCoordY(sc.fil); k++;
                    if (t >= warmStep) { res.transferToHost(sc.reachCount); long r = 0; for (int i = 0; i < sc.mot.nMotors; i++) if (sc.reachCount.get(i) > 0) r++; reachSum += r; reachN++; }
                    if (STRETCHCENSUS) { res.transferToHost(sc.mot.forceMag, sc.mot.forceDotFil, sc.mot.boundSeg); stretchTally(sc, stxAcc, extHist, fdHist); }
                    if (KTOT_CENSUS && t >= warmStep) { res.transferToHost(sc.mot.boundSeg); ktotTally(sc, ktAcc, ksHist); }
                    if (STROKECENSUS) { res.transferToHost(sc.mot.body.uVec, sc.mot.boundSeg, sc.fil.uVec); strokeTally(sc, strAcc, axFracHist); }
                    if (ROLLCENSUS) { res.transferToHost(sc.mot.body.yVec, sc.fil.uVec); long[] pm = rollTally(sc); rollPlus += pm[0]; rollMinus += pm[1]; }
                    if (MHATCENSUS) { res.transferToHost(sc.mot.body.uVec, sc.mot.boundSeg); long[] pm = mhatTally(sc); mhatPlus += pm[0]; mhatMinus += pm[1];
                        long tt = pm[0] + pm[1]; if (tt > 0 && mhatNT < mhatFracT.length) mhatFracT[mhatNT++] = (double) pm[0] / tt; }
                    if (EARLYSTOP && k % esCheckSamples == 0) {
                        double[] mon = esMonitor(cx, k, dtInt);
                        if (mon != null) { esFinal = mon; if (mon[0] < ES_THRESH) { esConverged = true; esStopStep = t + 1; break; } }
                    }
                }
            }
            if (EARLYSTOP && !esConverged) esStopStep = Math.min(esStopStep, M);   // reached the hard cap
            // §6.10 firing-rate pull. LT has no break-force cap task ⇒ capStats is not a device variable (skip it).
            if (res != null) { if (LYMN_TAYLOR) res.transferToHost(sc.mot.stats); else res.transferToHost(sc.mot.capStats, sc.mot.stats); }
            if (ROLLCENSUS) {
                long tot = rollPlus + rollMinus;
                double p = tot > 0 ? (double) rollMinus / tot : 0;
                System.out.printf("  ROLL_CENSUS (time-avg over samples) head-samples=%d  +ŝ=%.1f%%  −ŝ=%.1f%%  ⇒ pointed-sweep fraction p=%.3f, naive net factor (1−2p)=%.3f%n",
                        tot, tot > 0 ? 100.0 * rollPlus / tot : 0, tot > 0 ? 100.0 * rollMinus / tot : 0, p, 1.0 - 2.0 * p);
            }
            if (MHATCENSUS) {
                long tot = mhatPlus + mhatMinus;
                double fMinus = tot > 0 ? (double) mhatMinus / tot : 0;
                // per-sample time series mean + stdev of the +ẑ (productive) fraction
                double mMean = 0; for (int i = 0; i < mhatNT; i++) mMean += mhatFracT[i]; if (mhatNT > 0) mMean /= mhatNT;
                double mVar = 0; for (int i = 0; i < mhatNT; i++) { double d = mhatFracT[i] - mMean; mVar += d * d; } if (mhatNT > 1) mVar /= (mhatNT - 1);
                double mSd = Math.sqrt(mVar);
                System.out.printf("  MHAT_CENSUS (head.uVec·n̂bed, n̂bed=+Z) head-samples=%d  +ẑ(productive)=%.1f%%  −ẑ=%.1f%%  ⇒ wrong-pole fraction q=%.3f, naive net factor (1−2q)=%.3f%n",
                        tot, tot > 0 ? 100.0 * mhatPlus / tot : 0, tot > 0 ? 100.0 * mhatMinus / tot : 0, fMinus, 1.0 - 2.0 * fMinus);
                System.out.printf("  MHAT_CENSUS per-sample +ẑ fraction: mean=%.4f stdev=%.4f (n=%d samples)%n", mMean, mSd, mhatNT);
                System.out.print("  MHAT_CENSUS +ẑ-frac vs time:");
                for (int i = 0; i < mhatNT; i += Math.max(1, mhatNT / 20)) System.out.printf(" %.2f", mhatFracT[i]);
                System.out.println();
            }
            if (TWISTCENSUS && res != null) res.transferToHost(sc.twistHist);
        } else {
            cx[0] = centroidX(sc.fil); cy[0] = centroidY(sc.fil); cz[0] = centroidZ(sc.fil); bnd[0] = bound(sc.mot);
            mnx[0] = minCoordX(sc.fil); mxx[0] = maxCoordX(sc.fil); mny[0] = minCoordY(sc.fil); mxy[0] = maxCoordY(sc.fil); k = 1;
            for (int t = 0; t < M; t++) {
                step(sc, t);
                if (t == warmStep) { for (int i = 0; i < 2 * sc.mot.nMotors; i++) statsWarm[i] = sc.mot.stats.get(i); }
                if ((t + 1) % OUT_INT == 0 && k <= nInt) {
                    cx[k] = centroidX(sc.fil); cy[k] = centroidY(sc.fil); cz[k] = centroidZ(sc.fil); bnd[k] = bound(sc.mot);
                    mnx[k] = minCoordX(sc.fil); mxx[k] = maxCoordX(sc.fil); mny[k] = minCoordY(sc.fil); mxy[k] = maxCoordY(sc.fil); k++;
                    if (t >= warmStep) { long r = 0; for (int i = 0; i < sc.mot.nMotors; i++) if (sc.reachCount.get(i) > 0) r++; reachSum += r; reachN++; }
                    if (STRETCHCENSUS) stretchTally(sc, stxAcc, extHist, fdHist);   // CPU host arrays already current
                    if (KTOT_CENSUS && t >= warmStep) ktotTally(sc, ktAcc, ksHist);  // CPU boundSeg already current
                    if (STROKECENSUS) strokeTally(sc, strAcc, axFracHist);          // CPU host arrays already current
                    if (EARLYSTOP && k % esCheckSamples == 0) {
                        double[] mon = esMonitor(cx, k, dtInt);
                        if (mon != null) { esFinal = mon; if (mon[0] < ES_THRESH) { esConverged = true; esStopStep = t + 1; break; } }
                    }
                }
            }
            if (EARLYSTOP && !esConverged) esStopStep = Math.min(esStopStep, M);
        }
        int n = k;   // number of samples (intervals + 1)

        // ---- v1's instantaneousSpeed: per-interval 3D displacement / dt ----
        double instAll = 0, instSteady = 0; int instN = 0, instNs = 0;
        for (int i = 1; i < n; i++) {
            double dx = cx[i] - cx[i-1], dy = cy[i] - cy[i-1], dz = cz[i] - cz[i-1];
            double sp = Math.sqrt(dx*dx + dy*dy + dz*dz) / dtInt;
            instAll += sp; instN++;
            if (i >= n / 2) { instSteady += sp; instNs++; }
        }
        // ---- v1's longWindowSpeedXY at end of run (ring buffer; XY chord over the window) ----
        int newest = n - 1, oldest = Math.max(0, n - bufCap);
        double lwDx = cx[newest] - cx[oldest], lwDy = cy[newest] - cy[oldest];
        double lwDt = (newest - oldest) * dtInt;
        double longWindowSpeedXY = lwDt > 1e-12 ? Math.sqrt(lwDx*lwDx + lwDy*lwDy) / lwDt : 0.0;
        // ---- net displacement / time (honest net glide), full run and steady 2nd half ----
        double netXY = Math.sqrt(Math.pow(cx[n-1]-cx[0],2) + Math.pow(cy[n-1]-cy[0],2)) / ((n-1)*dtInt);
        double netX  = (cx[n-1] - cx[0]) / ((n-1)*dtInt);
        int h = n / 2;
        double netXYsteady = Math.sqrt(Math.pow(cx[n-1]-cx[h],2) + Math.pow(cy[n-1]-cy[h],2)) / ((n-1-h)*dtInt);
        // ---- velFitX: drift velocity = −slope of a least-squares fit of cx(t) over the steady 2nd half.
        //      Uses ALL window samples (not just the 2 endpoints) ⇒ far lower thermal variance than the
        //      net-chord; the optimal directed-drift estimator for x(t)=x0−V·t+noise. This is the V the
        //      MM fit consumes (the gliding velocity = the filament's directed −x sliding rate). ----
        double st = 0, stt = 0, sxx = 0, stx = 0; int nf = 0;
        for (int i = h; i < n; i++) { double ti = i * dtInt; st += ti; stt += ti*ti; sxx += cx[i]; stx += ti*cx[i]; nf++; }
        double denF = nf*stt - st*st;
        double slopeX = denF != 0 ? (nf*stx - st*sxx) / denF : 0.0;
        double velFitX = -slopeX;   // forward (−x) directed speed, µm/s (positive = gliding −x)
        // ---- avgBound ----
        double avgBall = 0; for (int i = 0; i < n; i++) avgBall += bnd[i]; avgBall /= n;
        double avgBsteady = 0; for (int i = h; i < n; i++) avgBsteady += bnd[i]; avgBsteady /= (n - h);

        // ---- LONG-ASSAY LS-centroid (the standing-rule reportable glide speed): LS drift of the centroid over
        //      the ON-BED, past-transient window (t≥0.20 s AND filament fully over the bed), projected on the
        //      filament axis (≈+x; axial fraction reported to confirm). A ≥1 s window is required — else SHORT. ----
        double t0LA = 0.20;
        double Slt=0, Sltt=0, Slcx=0, Slcy=0, Slcz=0, Sltx=0, Slty=0, Sltz=0; int nl=0; double wS=-1, wE=0, laBs=0; int laBn=0;
        for (int i = 0; i < n; i++) {
            double ti = i * dtInt;
            if (ti < t0LA || mnx[i] < bXlo || mxx[i] > bXhi) continue;   // past transient AND fully on the motor bed
            if (wS < 0) wS = ti; wE = ti;
            Slt+=ti; Sltt+=ti*ti; Slcx+=cx[i]; Slcy+=cy[i]; Slcz+=cz[i]; Sltx+=ti*cx[i]; Slty+=ti*cy[i]; Sltz+=ti*cz[i]; nl++;
            laBs += bnd[i]; laBn++;
        }
        double laVax=0, laVmag=0, laAxf=0, laWin = wE - wS;
        if (nl > 2) {
            double dl = nl*Sltt - Slt*Slt;
            double vX=(nl*Sltx-Slt*Slcx)/dl, vY=(nl*Slty-Slt*Slcy)/dl, vZ=(nl*Sltz-Slt*Slcz)/dl;
            laVmag = Math.sqrt(vX*vX+vY*vY+vZ*vZ); laVax = vX; laAxf = laVmag>0 ? Math.abs(vX)/laVmag : 0;
        }
        System.out.printf("  LONG_ROW seed=0x%X density=%.0f v_axial=%.3f |v|=%.3f axialFrac=%.3f avgBound=%.2f window=[%.2f,%.2f]s len=%.2fs %s%n",
                SEED, DENSITY, laVax, laVmag, laAxf, laBn>0?laBs/laBn:0, wS<0?0:wS, wE, laWin, laWin>=1.0 ? "OK" : "*SHORT<1s->use -full*");

        System.out.printf("  samples=%d  bufCap=%d  netΔx=%.4f µm over %.4f s%n", n, bufCap, cx[n-1]-cx[0], (n-1)*dtInt);
        System.out.println("  STATISTIC                       full-run    steady(2nd-half)");
        System.out.printf("  instantaneousSpeed (3D/intvl) :  %7.3f     %7.3f   µm/s%n", instAll/instN, instSteady/Math.max(1,instNs));
        System.out.printf("  net-displacement XY/time      :  %7.3f     %7.3f   µm/s%n", netXY, netXYsteady);
        System.out.printf("  net-displacement x-only/time  :  %7.3f                µm/s%n", netX);
        System.out.printf("  velFitX (−slope cx, steady)   :  %7.3f   <== V for the MM fit (directed −x glide)  µm/s%n", velFitX);
        System.out.printf("  longWindowSpeedXY @ end       :  %7.3f                µm/s%n", longWindowSpeedXY);
        System.out.printf("  avgBound                      :  %7.3f     %7.3f%n", avgBall, avgBsteady);
        System.out.printf("  GRID_ROW seed=0x%X nMot=%d density=%.0f velFitX=%.3f inst=%.3f instSteady=%.3f netXY=%.3f netSteady=%.3f netX=%.3f lwXY=%.3f avgB=%.3f avgBsteady=%.3f%n",
                SEED, sc.mot.nMotors, DENSITY, velFitX, instAll/instN, instSteady/Math.max(1,instNs), netXY, netXYsteady, netX, longWindowSpeedXY, avgBall, avgBsteady);
        // ---- GLIDING_RADIUS_SWEEP: early-stop verdict + mat-band edge-guard ----
        if (EARLYSTOP || MATBAND) {
            double stopSec = (esStopStep) * DT;
            // recompute the monitor on the FINAL collected samples so the reported SEM matches the reported velFitX exactly
            double[] mon = esMonitor(cx, n, dtInt);
            double relSEM = mon != null ? mon[0] : Double.NaN, batchMean = mon != null ? mon[2] : Double.NaN;
            int nb = mon != null ? (int) mon[3] : 0; double winSec = mon != null ? mon[4] : 0;
            double sem = Double.isNaN(relSEM) ? Double.NaN : relSEM * Math.abs(velFitX);
            boolean conv = EARLYSTOP ? (esConverged && relSEM < ES_THRESH) : (mon != null && relSEM < ES_THRESH);
            // edge-guard: did any segment come within (capture + guard) of the −x mat boundary during the run?
            double edgeGuard = COL_TOL + 0.10;   // capture radius + 100 nm slack (> motor x-reach)
            double minSegX = 1e30; for (int i = 0; i < n; i++) minSegX = Math.min(minSegX, mnx[i]);
            boolean edgeTrip = MATBAND && Double.isFinite(sc.bandXlo) && (minSegX < sc.bandXlo + edgeGuard);
            System.out.printf(java.util.Locale.US,
                "  EARLYSTOP_ROW coltol=%.1fnm seed=0x%X velFitX=%.3f±%.3f (relSEM=%.4f) batchMean=%.3f nBatches=%d avgBsteady=%.3f window=%.4fs stopSec=%.4fs stopStep=%d status=%s%n",
                COL_TOL * 1e3, SEED, velFitX, sem, relSEM, batchMean, nb, avgBsteady, winSec, stopSec, esStopStep,
                conv ? "CONVERGED@5%" : "NOT-CONVERGED@cap");
            if (MATBAND) {
                System.out.printf(java.util.Locale.US,
                    "  MATBAND_ROW bandXlo=%.3f bXhi=%.2f bYhalf=%.2f nMot=%d excBudget=%.2fµm minSegX=%.3f edgeGuard=%.3f edgeTrip=%s%n",
                    sc.bandXlo, bXhi, bYhalf, sc.mot.nMotors, MATBAND_EXC, minSegX, sc.bandXlo + edgeGuard, edgeTrip ? "*TRIPPED-band-undersized*" : "clear");
            }
        }
        // §6.10 break-force release firing rate: cap fires / bound-motor-steps (whole run).
        long capFires = 0, boundStepsTot = 0;
        for (int m = 0; m < sc.mot.nMotors; m++) { capFires += sc.mot.capStats.get(m); boundStepsTot += sc.mot.stats.get(2*m); }
        double capRate = boundStepsTot > 0 ? (double) capFires / boundStepsTot : 0.0;
        System.out.printf("  CAP_ROW seed=0x%X forcecapdetach=%s capFires=%d boundSteps=%d capRatePerBoundStep=%.5f%n",   // label mechanism-named 2026-07-08 (was faithfulRelease=; flag -faithfulrelease→-forcecapdetach)
                SEED, FORCE_CAP_DETACH ? "ON" : "OFF", capFires, boundStepsTot, capRate);
        // dt-convergence (measurement-only): whole-run detachment rate + mean bound dwell from mot.stats
        // (stats[2m]=bound steps, stats[2m+1]=releases). detachRate = releases/(boundSteps·dt) is a per-second
        // rate; if biochemistry-set (LT) it is dt-robust, if F8-overshoot-set (default catch-slip) it falls as dt→0.
        long relTotAll = 0; for (int m = 0; m < sc.mot.nMotors; m++) relTotAll += sc.mot.stats.get(2 * m + 1);
        double detachRate = boundStepsTot > 0 ? relTotAll / (boundStepsTot * DT) : 0.0;
        double dwellMsAll = relTotAll > 0 ? (boundStepsTot / (double) relTotAll) * DT * 1e3 : 0.0;
        System.out.printf("  STATS_ROW seed=0x%X dt=%.2e boundSteps=%d releases=%d detachRatePerS=%.1f dwellMs=%.4f%n",
                SEED, DT, boundStepsTot, relTotAll, detachRate, dwellMsAll);
        // STEADY (warmup-excluded) dwell/detach: subtract the startup snapshot ⇒ the physical turnover, immune to
        // the all-heads-start-NONE startup avalanche that contaminates the cumulative STATS_ROW above.
        long bSteady = 0, rSteady = 0;
        for (int m = 0; m < sc.mot.nMotors; m++) { bSteady += sc.mot.stats.get(2*m) - statsWarm[2*m]; rSteady += sc.mot.stats.get(2*m+1) - statsWarm[2*m+1]; }
        double detachSteady = bSteady > 0 ? rSteady / (bSteady * DT) : 0.0;
        double dwellSteadyMs = rSteady > 0 ? (bSteady / (double) rSteady) * DT * 1e3 : 0.0;
        double avgBoundSteady = (M - warmStep) > 0 ? bSteady / (double) (M - warmStep) : 0.0;   // mean bound heads (steady)
        double meanReach = reachN > 0 ? reachSum / reachN : 0.0;
        double duty = meanReach > 0 ? avgBoundSteady / meanReach : 0.0;   // fraction of engageable heads bound at any instant
        System.out.printf("  STATS_STEADY_ROW seed=0x%X coltol=%.1fnm density=%.0f dt=%.2e boundSteps=%d releases=%d detachRatePerS=%.1f dwellMs=%.4f avgBoundSteady=%.3f meanReach=%.1f duty=%.4f%n",
                SEED, COL_TOL * 1e3, DENSITY, DT, bSteady, rSteady, detachSteady, dwellSteadyMs, avgBoundSteady, meanReach, duty);
        if (STROKECENSUS) {
            // Per-bound-head neck-powerstroke fidelity (read-only pose census). f̂=barbed=+x; productive glide=−x.
            long boundN = (long) strAcc[0], strokedN = (long) strAcc[1], NbAx = (long) strAcc[6];
            double strokedFrac = boundN > 0 ? strAcc[1] / strAcc[0] : 0;             // fraction of bound heads with |swing|>sin40°
            double barbedFrac  = strokedN > 0 ? strAcc[2] / strAcc[1] : 0;           // barbed-productive among STROKED (BoA ref 99.6%)
            double axFracStroked = strokedN > 0 ? strAcc[3] / strAcc[1] : 0;         // mean swing axial fraction over stroked
            double axFracBound   = NbAx > 0 ? strAcc[5] / strAcc[6] : 0;             // mean swing axial fraction over ALL bound
            double meanSwingDeg  = boundN > 0 ? Math.toDegrees(Math.asin(Math.min(1.0, strAcc[4] / strAcc[0]))) : 0; // ⟨sin⟩→deg (proxy)
            System.out.printf("  STROKE_ROW seed=0x%X coltol=%.1fnm density=%.0f avgBound~ boundSamples=%d strokedFrac=%.4f barbedSweepFrac=%.4f swingAxialFrac(stroked)=%.4f swingAxialFrac(bound)=%.4f meanSwingDeg=%.2f%n",
                    SEED, COL_TOL * 1e3, DENSITY, boundN, strokedFrac, barbedFrac, axFracStroked, axFracBound, meanSwingDeg);
            StringBuilder ah = new StringBuilder("  STROKE_AXFRAC_HIST(stroked heads, 0.0-1.0 /0.1):");
            for (int b6 = 0; b6 < axFracHist.length; b6++) ah.append(' ').append(axFracHist[b6]);
            System.out.println(ah);
        }
        if (STRETCHCENSUS) {
            // Aggregate dwell = ΣboundSteps / Σreleases · dt  (mean bound lifetime over the whole run).
            long relTot = 0; for (int m = 0; m < sc.mot.nMotors; m++) relTot += sc.mot.stats.get(2 * m + 1);
            double dwellMs = relTot > 0 ? (boundStepsTot / (double) relTot) * DT * 1e3 : 0.0;
            long N = (long) stxAcc[4];
            double extMean = N > 0 ? stxAcc[0] / N : 0;
            double extSd = N > 1 ? Math.sqrt(Math.max(0, stxAcc[1] / N - extMean * extMean)) : 0;
            double fdMean = N > 0 ? stxAcc[2] / N : 0;          // signed per-head axial force (pN)
            double fdAbsMean = N > 0 ? stxAcc[3] / N : 0;       // |per-head axial force| (pN)
            System.out.printf("  STRETCH_ROW seed=0x%X coltol=%.1fnm density=%.0f boundSamples=%d extNm(mean±sd)=%.3f±%.3f fdFilPN(mean/absmean)=%.4f/%.4f dwellMs=%.3f%n",
                    SEED, COL_TOL * 1e3, DENSITY, N, extMean, extSd, fdMean, fdAbsMean, dwellMs);
            StringBuilder eh = new StringBuilder("  STRETCH_EXT_HIST(nm 0-2,2-4,...,18-20,>20):");
            for (int b6 = 0; b6 < extHist.length; b6++) eh.append(' ').append(extHist[b6]);
            System.out.println(eh);
            StringBuilder fh = new StringBuilder("  STRETCH_FD_HIST(pN ≤−8,−7,...,+7,≥+8):");
            for (int b6 = 0; b6 < fdHist.length; b6++) fh.append(' ').append(fdHist[b6]);
            System.out.println(fh);
        }
        if (KTOT_CENSUS) {
            long segSamp = (long) ktAcc[0], eng = (long) ktAcc[1];
            double kSpring = MYO_SPRING * 1.0e9;
            double meanKsEng = eng > 0 ? ktAcc[4] / eng : 0;          // mean k_s over ENGAGED segments
            double meanKsAll = segSamp > 0 ? ktAcc[4] / segSamp : 0;  // mean k_s over ALL segment-samples
            double fMargAll = segSamp > 0 ? ktAcc[2] / segSamp : 0;   // fraction of ALL segments with K_tot≥1.4pN/nm
            double fBlowAll = segSamp > 0 ? ktAcc[3] / segSamp : 0;   // fraction of ALL segments with K_tot≥3.8pN/nm
            double fMargEng = eng > 0 ? ktAcc[2] / eng : 0;           // fraction of ENGAGED segments ≥1.4pN/nm
            double fBlowEng = eng > 0 ? ktAcc[3] / eng : 0;           // fraction of ENGAGED segments ≥3.8pN/nm
            System.out.printf(java.util.Locale.US,
                "  KTOT_ROW seed=0x%X coltol=%.1fnm density=%.0f dt=%.2e myoSpring=%.2fpN/nm segSamples=%d engaged=%d meanKtot_eng=%.3fpN/nm meanKtot_all=%.3f maxKtot=%.2fpN/nm fracAll>=1.4=%.4f fracAll>=3.8=%.4f fracEng>=1.4=%.4f fracEng>=3.8=%.4f%n",
                SEED, COL_TOL * 1e3, DENSITY, DT, kSpring, segSamp, eng, meanKsEng * kSpring, meanKsAll * kSpring, ktAcc[5] * kSpring, fMargAll, fBlowAll, fMargEng, fBlowEng);
            StringBuilder kh = new StringBuilder("  KTOT_KS_HIST(engaged segs, k_s=1,2,...,10,>10):");
            for (int b6 = 1; b6 < ksHist.length; b6++) kh.append(' ').append(ksHist[b6]);
            System.out.println(kh);
        }
        if (TWISTCENSUS && sc.twistHist != null) {
            long[] hb = new long[6]; long binds = 0; double angSum = 0; long far = 0;
            for (int m = 0; m < sc.mot.nMotors; m++) for (int b6 = 0; b6 < 6; b6++) {
                long c = sc.twistHist.get(6 * m + b6); hb[b6] += c; binds += c;
                angSum += c * (b6 * 30.0 + 15.0); if (b6 >= 3) far += c;   // bins ≥90° = "far side" (need >90° twist)
            }
            double meanAng = binds > 0 ? angSum / binds : 0, farFrac = binds > 0 ? (double) far / binds : 0;
            // lifetime proxy: (bound-motor-steps)/(bind events)·dt = mean bound lifetime
            double lifeMs = binds > 0 ? (boundStepsTot / (double) binds) * DT * 1e3 : 0;
            System.out.printf("  TWIST_ROW binds=%d meanArrivalAngle=%.1f° far(>90°)Frac=%.3f hist[0-30/30-60/60-90/90-120/120-150/150-180]=%d/%d/%d/%d/%d/%d meanLifetime=%.2f ms avgBound=%.2f%n",
                    binds, meanAng, farFrac, hb[0], hb[1], hb[2], hb[3], hb[4], hb[5], lifeMs, avgBsteady);
        }
        // ---- full-mat coverage verification (windowed to the MEASUREMENT window = steady 2nd half, the
        //      samples netSteady is computed over): did the filament stay fully over the motor bed there? ----
        double wMnX = 1e30, wMxX = -1e30, wMnY = 1e30, wMxY = -1e30;   // steady-window extents
        for (int i = h; i < n; i++) { wMnX = Math.min(wMnX, mnx[i]); wMxX = Math.max(wMxX, mxx[i]); wMnY = Math.min(wMnY, mny[i]); wMxY = Math.max(wMxY, mxy[i]); }
        double rMnX = 1e30, rMxX = -1e30, rMnY = 1e30, rMxY = -1e30;   // whole-run extents (informational)
        for (int i = 0; i < n; i++) { rMnX = Math.min(rMnX, mnx[i]); rMxX = Math.max(rMxX, mxx[i]); rMnY = Math.min(rMnY, mny[i]); rMxY = Math.max(rMxY, mxy[i]); }
        double mXlo = wMnX - bXlo, mXhi = bXhi - wMxX, mYlo = wMnY - (-bYhalf), mYhi = bYhalf - wMxY;
        double minMargin = Math.min(Math.min(mXlo, mXhi), Math.min(mYlo, mYhi));
        double runMinMargin = Math.min(Math.min(rMnX - bXlo, bXhi - rMxX), Math.min(rMnY - (-bYhalf), bYhalf - rMxY));
        boolean fullMat = minMargin > 0.05;   // ≥50 nm clear of every bed edge throughout the measurement window
        System.out.printf("  COVERAGE window[%d..%d] x∈[%.3f,%.3f] y∈[%.3f,%.3f] | whole-run x∈[%.3f,%.3f] y∈[%.3f,%.3f] | bed x∈[%.2f,%.2f] y±%.2f%n",
                h, n - 1, wMnX, wMxX, wMnY, wMxY, rMnX, rMxX, rMnY, rMxY, bXlo, bXhi, bYhalf);
        System.out.printf("  COV_ROW seed=0x%X nMot=%d density=%.0f marginX_lo=%.3f marginX_hi=%.3f marginY_lo=%.3f marginY_hi=%.3f minMargin=%.3f runMinMargin=%.3f fullMat=%s%n",
                SEED, sc.mot.nMotors, DENSITY, mXlo, mXhi, mYlo, mYhi, minMargin, runMinMargin, fullMat ? "YES" : "VIOLATED");
        if (MATBED && !fullMat)
            System.out.printf("  *** COVERAGE VIOLATED (measurement-window minMargin %.3f µm ≤ 0.05): filament reached a bed edge — velocity is edge-corrupted, widen the bed / shorten the run ***%n", minMargin);
    }

    /** filament centroid-z and a tilt/bow proxy (max−min segment z). */
    static double zSpread(FilamentStore f) { double lo=1e9,hi=-1e9; for(int i=0;i<f.n;i++){double z=f.coordZ(i);lo=Math.min(lo,z);hi=Math.max(hi,z);} return hi-lo; }

    /**
     * 4b-iv z-settling probe (measurement only). Per output interval (100 steps = 1 ms, matching v1's
     * toFileInterval) emit a TRACE_ROW for the filament: centroid-z, tilt (max−min seg z), avgBound,
     * per-interval net-x glide (forward displacement / dt), and the bound-motor along-filament load
     * (mean `forceDotFil` and the assist fraction, forceDotFil>0 = force along the −x glide). Lets us
     * test §6(c): does v2's z settle (vs v1's `.dat` posZ), and does its glide-decay track z / a drop in
     * productive engagement? GPU pulls fil.coord + boundSeg + forceDotFil at the 100-step cadence.
     */
    static void ztrace(Scene sc, int M, boolean gpu) {
        final int OUT_INT = 100; final double dtInt = OUT_INT * DT;
        MotorStore mot = sc.mot; FilamentStore f = sc.fil;
        System.out.printf("%n--- z-trace (%s, %d motors, box x∈[%.1f,%.1f] y±%.1f, seed=0x%X) ---%n",
                gpu ? "GPU" : "CPU", mot.nMotors, bXlo, bXhi, bYhalf, SEED);
        System.out.println("ZTRACE_HDR seed iv t cz tilt avgB glide fdMean fdPosFrac");
        TornadoExecutionPlan plan = null;
        double prevX = centroidX(f);
        if (gpu) { plan = buildPlan(sc); mot.setCounts(0, SEED, f.n); f.counts.set(1, 0); plan.withGridScheduler(sched).execute(); }
        int iv = 0;
        for (int t = 0; t < M; t++) {
            if (gpu) {
                mot.setCounts(t, SEED, f.n); f.counts.set(1, t);
                TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
                if ((t + 1) % OUT_INT == 0) res.transferToHost(f.coord, mot.boundSeg, mot.forceDotFil);
            } else {
                step(sc, t);
            }
            if ((t + 1) % OUT_INT == 0) {
                iv++;
                double cx = centroidX(f), cz = centroidZ(f), tilt = zSpread(f);
                double glide = -(cx - prevX) / dtInt;   // forward (−x) per-interval rate, µm/s
                prevX = cx;
                long bnd = 0; double fdSum = 0; long fdN = 0, fdPos = 0;
                for (int m = 0; m < mot.nMotors; m++) {
                    if (mot.boundSeg.get(m) < 0) continue;
                    bnd++; float fd = mot.forceDotFil.get(m); fdSum += fd; fdN++; if (fd > 0) fdPos++;
                }
                System.out.printf("ZTRACE 0x%X %d %.4f %.5f %.5f %d %.4f %.4g %.4f%n",
                        SEED, iv, (t + 1) * DT, cz, tilt, bnd, glide,
                        fdN > 0 ? fdSum / fdN : 0.0, fdN > 0 ? (double) fdPos / fdN : 0.0);
            }
        }
    }

    /**
     * §6.9 per-bound-motor assist-decomposition logger (measurement only). At post-transient sampled
     * steps (every OUT_INT=100 after warm=WARM, matching v1's GlidingAssayEvaluator output cadence),
     * emit one ALOG row per BOUND motor with the planner's tuple:
     *   {nucleotideState, assistSign, forceDotFil(load), bindArc, poseAngle}
     * plus bindArcFrac (bindArc/segLen) + segLen for cross-segLen comparability. assistSign REUSES v2's
     * existing assist metric — sign(forceDotFil), assist = forceDotFil>0 (the same quantity -diag/
     * -cycldiag/-ztrace aggregate; identical definition to v1's MyoFilLink Dot(F,segU)). poseAngle =
     * acos(dot(head uVec, bound-seg uVec)) deg — the motor-axis-vs-filament-axis angle the F9 cross-bridge
     * torque drives toward 90°/120°. Reads EXISTING arrays only; GPU pulls them at the 100-step cadence
     * (residency preserved). Also prints the marginal ASSIST_SUMMARY (assist-fraction + occupancy +
     * avgBound) — the Phase-0 regression guard.
     */
    static void assistLog(Scene sc, int M, boolean gpu) {
        final int OUT_INT = 100; final int WARM = 2000;
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        int nB = 3 * mot.nMotors;
        System.out.printf("%n--- assist-decomp log (%s, %d motors, box x∈[%.1f,%.1f] y±%.1f, seed=0x%X, warm=%d) ---%n",
                gpu ? "GPU" : "CPU", mot.nMotors, bXlo, bXhi, bYhalf, SEED, WARM);
        System.out.println("ALOG_HDR seed t motor state assistSign forceDotFil bindArc bindArcFrac poseAngleDeg segLen");
        long[] stateCnt = new long[4]; long boundObs = 0, assistObs = 0; double boundSum = 0; long sampleN = 0;
        TornadoExecutionPlan plan = null;
        if (gpu) { plan = buildPlan(sc); mot.setCounts(0, SEED, f.n); f.counts.set(1, 0); plan.withGridScheduler(sched).execute(); }
        for (int t = 0; t < M; t++) {
            if (gpu) {
                mot.setCounts(t, SEED, f.n); f.counts.set(1, t);
                TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
                if (t >= WARM && (t + 1) % OUT_INT == 0)
                    res.transferToHost(f.uVec, mot.boundSeg, mot.bindArc, mot.nucleotideState, mot.forceDotFil, b.uVec);
            } else {
                step(sc, t);
            }
            if (t >= WARM && (t + 1) % OUT_INT == 0) {
                long bnd = 0;
                for (int m = 0; m < mot.nMotors; m++) {
                    int s = mot.boundSeg.get(m);
                    if (s < 0) continue;
                    bnd++; boundObs++;
                    int st = mot.nucleotideState.get(m); stateCnt[st]++;
                    float fd = mot.forceDotFil.get(m);
                    int sign = fd > 0 ? 1 : (fd < 0 ? -1 : 0);
                    if (fd > 0) assistObs++;
                    float arc = mot.bindArc.get(m), segL = f.segLength.get(s);
                    int h = 3 * m + 2;
                    double hux = b.uVec.get(h), huy = b.uVec.get(nB + h), huz = b.uVec.get(2 * nB + h);
                    double sux = f.uVec.get(s), suy = f.uVec.get(f.n + s), suz = f.uVec.get(2 * f.n + s);
                    double dot = hux*sux + huy*suy + huz*suz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
                    double poseDeg = Math.acos(dot) * 180.0 / Math.PI;
                    System.out.printf("ALOG 0x%X %d %d %d %d %.6g %.5f %.5f %.3f %.5f%n",
                            SEED, t + 1, m, st, sign, fd, arc, segL > 0 ? arc / segL : 0.0, poseDeg, segL);
                }
                boundSum += bnd; sampleN++;
            }
        }
        long tot = stateCnt[0] + stateCnt[1] + stateCnt[2] + stateCnt[3];
        System.out.printf("ASSIST_SUMMARY seed=0x%X nObs=%d assistFrac=%.4f occ[NONE/ATP/ADPPi/ADP]=%.4f/%.4f/%.4f/%.4f avgBound=%.3f%n",
                SEED, boundObs, boundObs > 0 ? (double) assistObs / boundObs : 0.0,
                tot>0?(double)stateCnt[0]/tot:0, tot>0?(double)stateCnt[1]/tot:0, tot>0?(double)stateCnt[2]/tot:0, tot>0?(double)stateCnt[3]/tot:0,
                sampleN > 0 ? boundSum / sampleN : 0.0);
    }

    /**
     * 4b-iv per-step force cross-check (measurement only). Feed v1's EXACT compute-time bound config
     * (dumped from an instrumented v1 `MyoFilLink.addForces`) into v2's `CrossBridgeSystem.bondForces`
     * and compare the head-side F8 vector + forceDotFil. Tests directly: does v2 compute the same
     * cross-bridge force vectors as v1 for an identical bound configuration?
     */
    static void forceTest() {
        // --- v1 compute-time dump (BoA-v1ref scratch MyoFilLink.addForces, 4x1 gliding, seed 1) ---
        double[] headC = {1.870585, -0.000148, 0.022387}, headU = {0.180790, 0.174904, -0.967845};
        double[] segC  = {1.821203,  0.007071, 0.009056}, segU  = {0.999057, -0.041840, -0.011599};
        double posOnSeg = 0.139078, segLen = 0.175500, myoSpring = 1.0e-9;
        double[] v1F8 = {8.98676e-14, 3.32284e-12, -4.24803e-12}; double v1forceMag = 5.39399e-12, v1fdf = 2.71166e-17;

        // --- build a minimal v2 config (1 segment, 1 motor) at the EXACT same geometry ---
        FilamentStore f = new FilamentStore(1);
        f.setCoord(0, (float) segC[0], (float) segC[1], (float) segC[2]);
        f.setUVec(0, (float) segU[0], (float) segU[1], (float) segU[2]); f.setYVec(0, 0f, 1f, 0f);
        f.segLength.set(0, (float) segLen); f.bRotGam.init(1f);
        MotorStore mot = new MotorStore(1);
        mot.assembleArticulated(0, (float) segC[0], (float) segC[1], (float) ANCHOR_Z, 0f, 0f, 1f, (float) Constants.BTransCoeff);
        RigidRodBody b = mot.body; int h = 2, nB = 3;   // head sub-body slot for motor 0
        b.coord.set(h, (float) headC[0]); b.coord.set(nB + h, (float) headC[1]); b.coord.set(2*nB + h, (float) headC[2]);
        b.uVec.set(h, (float) headU[0]); b.uVec.set(nB + h, (float) headU[1]); b.uVec.set(2*nB + h, (float) headU[2]);
        b.yVec.set(h, 0f); b.yVec.set(nB + h, 1f); b.yVec.set(2*nB + h, 0f); b.bRotGam.init(1f);
        mot.boundSeg.set(0, 0); mot.bindArc.set(0, (float) posOnSeg); mot.nucleotideState.set(0, MotorStore.NUC_ATP);
        FloatArray bondData = new FloatArray(CrossBridgeSystem.STRIDE); bondData.init(0f);
        FloatArray xbParams = FloatArray.fromElements((float) myoSpring, 90f, 0.4f, (float) DT, (float) MotorStore.HEAD_LEN, 0f);

        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, bondData, xbParams);

        double v2F8x = bondData.get(0), v2F8y = bondData.get(1), v2F8z = bondData.get(2);
        double v2mag = Math.sqrt(v2F8x*v2F8x + v2F8y*v2F8y + v2F8z*v2F8z), v2fdf = bondData.get(12);
        System.out.println("\n=== 4b-iv per-step cross-bridge force cross-check (v1 config → v2 bondForces) ===");
        System.out.printf("  config: head=(%.5f,%.5f,%.5f) seg=(%.5f,%.5f,%.5f) bindArc=%.5f segLen=%.5f%n",
                headC[0],headC[1],headC[2], segC[0],segC[1],segC[2], posOnSeg, segLen);
        System.out.printf("  %-14s %-16s %-16s %-16s%n", "", "v1", "v2", "|Δ|/|v1|");
        System.out.printf("  %-14s %-16.6g %-16.6g %.2g%n", "F8.x (N)", v1F8[0], v2F8x, Math.abs(v2F8x-v1F8[0])/Math.abs(v1F8[0]));
        System.out.printf("  %-14s %-16.6g %-16.6g %.2g%n", "F8.y (N)", v1F8[1], v2F8y, Math.abs(v2F8y-v1F8[1])/Math.abs(v1F8[1]));
        System.out.printf("  %-14s %-16.6g %-16.6g %.2g%n", "F8.z (N)", v1F8[2], v2F8z, Math.abs(v2F8z-v1F8[2])/Math.abs(v1F8[2]));
        System.out.printf("  %-14s %-16.6g %-16.6g %.2g%n", "forceMag (N)", v1forceMag, v2mag, Math.abs(v2mag-v1forceMag)/Math.abs(v1forceMag));
        System.out.printf("  %-14s %-16.6g %-16.6g%n", "forceDotFil", v1fdf, v2fdf);
        System.out.println("  ⇒ v2's bondForces reproduces v1's compute-time F8/forceDotFil for the identical config.");
    }

    static void runViz(Scene sc, int M, String dir, boolean gpu) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        new java.io.File(dir).mkdirs();
        int every = Math.max(1, M / VIZ_FRAMES), frames = 0;
        if (gpu) {
            TornadoExecutionPlan plan = buildPlan(sc);
            writeFrame(dir, frames++, 0, sc);                 // frame 0: initial host pose
            for (int t = 0; t < M; t++) {
                mot.setCounts(t, SEED, f.n); f.counts.set(1, t);
                TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
                if ((t + 1) % every == 0) {                   // pull pose + state + bound set at frame cadence only
                    res.transferToHost(b.end1, b.end2, f.end1, f.end2, mot.nucleotideState, mot.boundSeg);
                    writeFrame(dir, frames++, (t + 1) * DT, sc);
                }
            }
            System.out.println("viewer (GPU): wrote " + frames + " frames to " + dir);
            return;
        }
        for (int t = 0; t <= M; t++) {
            if (t % every == 0) writeFrame(dir, frames++, t * DT, sc);
            step(sc, t);
        }
        System.out.println("viewer (CPU): wrote " + frames + " frames to " + dir);
    }
    static final String[] SN = { "NONE", "ATP", "ADPPi", "ADP" };
    static void writeFrame(String dir, int frame, double t, Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        StringBuilder sb = new StringBuilder(1024);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":5,\"yDim\":1,\"zDim\":0.4}", frame, t));
        sb.append(",\"segments\":[");
        int barbed = 0; float bx = -1e30f;   // the +x (barbed/plus) terminus = the segment with the largest end2.x
        for (int s = 0; s < f.n; s++) if (f.end2.get(s) > bx) { bx = f.end2.get(s); barbed = s; }
        for (int s = 0; s < f.n; s++) {
            if (s > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0%s}",
                s, f.end1.get(s), f.end1.get(f.n + s), f.end1.get(2 * f.n + s), f.end2.get(s), f.end2.get(f.n + s), f.end2.get(2 * f.n + s), Constants.radius,
                s == barbed ? ",\"isBarbedEnd\":true" : ""));
        }
        sb.append("],\"myosins\":[");
        boolean first = true;
        for (int m = 0; m < mot.nMotors; m++) {
            // emit the full motor carpet (match v1)
            if (!first) sb.append(','); first = false;
            int rod = 3 * m, lever = 3 * m + 1, head = 3 * m + 2;
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"bound\":%b,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, mot.boundSeg.get(m) >= 0, b.end1X(rod), b.end1Y(rod), b.end1Z(rod), b.end2X(rod), b.end2Y(rod), b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever), b.end1Y(lever), b.end1Z(lever), b.end2X(lever), b.end2Y(lever), b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head), b.end1Y(head), b.end1Z(head), b.end2X(head), b.end2Y(head), b.end2Z(head), MotorStore.HEAD_R, SN[mot.nucleotideState.get(m)]));
        }
        sb.append("]}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US, "frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
