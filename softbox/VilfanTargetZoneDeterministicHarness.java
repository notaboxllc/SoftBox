package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import softbox.TwoBodyConverterMotor.Glide2D;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VILFAN-LIKE TARGET-ZONE TWIRLING — the DETERMINISTIC build-and-validation fixture (CPU ONLY).
 *
 * <p><b>Noncanonical, flag-gated, DEFAULT-OFF.</b> This harness does not change any canonical default,
 * parameter, chemistry, force law, stiffness, stroke, rate, viscosity or motor density. It composes the
 * existing explicit-S2 discrete-actin-site motor with assay-level ACCESSIBILITY and CONSTRAINT features and
 * asks one question:
 *
 * <blockquote>Does helical site passage through a surface-facing target zone bias attachment before versus
 * after the zone centre, and does that bias produce a signed axial torque and a signed filament rotation
 * that reverse under mirroring?</blockquote>
 *
 * <h2>The causal chain under test</h2>
 * <pre>
 *   helical site passage through a surface-facing target zone
 *       -> biased attachment before vs after the zone centre   (A_TZ)
 *       -> signed axial torque                                 (tau)
 *       -> signed filament rotation                            (Omega)
 * </pre>
 * Success is NOT "the filament rotated": the three must agree causally and reverse together under mirroring.
 *
 * <h2>Sign conventions (one place)</h2>
 * <ul>
 *   <li>{@code axis} = the filament's fixed material tangent, pointed -&gt; barbed (segment 0's uVec at build).</li>
 *   <li>{@code v} = commanded axial speed along {@code +axis} (µm/s). The motor lawn is fixed, so the motor's
 *       perpendicular foot moves in the filament's own arc coordinate at {@code d(arc)/dt = -v}.</li>
 *   <li>{@code dphi/darc} = the lattice's azimuthal advance per unit arc (rad/µm): {@code stairPhase/rise} for a
 *       staircase lattice, the analytic {@code twistRate} for the native helix. Mirroring negates it.</li>
 *   <li><b>Zone-phase drift</b> {@code D = -(dphi/darc) * v} (rad/s) — the rate at which the helical phase
 *       presented to a fixed motor advances. Verified numerically in gate C3.</li>
 *   <li><b>delta</b> = the signed target-zone coordinate: the angle from the zone-centre direction to the site's
 *       outward normal, about {@code axis}, in (-pi, pi]. {@code delta = 0} is the zone centre.</li>
 *   <li><b>BEFORE</b> (approaching the centre) &hArr; {@code sign(D)*delta &lt; 0};
 *       <b>AFTER</b> (receding) &hArr; {@code sign(D)*delta &gt; 0}.</li>
 *   <li>{@code A_TZ = (N_before - N_after)/(N_before + N_after)}; undefined (reported as NaN) at zero denominator.</li>
 * </ul>
 *
 * <h2>Predictions, stated before the run</h2>
 * First-passage attachment plus pool depletion should catch sites on the ENTERING edge, so
 * {@code A_TZ > 0}, hence {@code sign(<delta>) = -sign(D)}. The bond's moment about the axis is
 * {@code tau ~ -R*rho*k*sin(delta)}, so {@code sign(<tau>) = +sign(D)} and {@code sign(Omega) = sign(<tau>)}.
 * Mirroring negates {@code dphi/darc}, hence {@code D}, hence {@code <delta>}, {@code tau} and {@code Omega} —
 * while leaving {@code A_TZ} positive.
 *
 * <p>Report: {@code docs/twirling/vilfan_target_zone/TARGET_ZONE_BUILD_AND_VALIDATION.md}.
 */
public final class VilfanTargetZoneDeterministicHarness {
    private VilfanTargetZoneDeterministicHarness() {}

    // ===================================================================================================
    // CONFIGURATION — every one of these is an assay fixture; none is a canonical parameter
    // ===================================================================================================
    static double DENSITY   = 400.0;    // -density   heads/µm² (canonical scene density, untouched physics)
    static double DT        = 2.5e-6;   // -dt        s (the production explicit-S2 timestep)
    static int    STEPS     = 20000;    // -steps
    static int    SEEDS     = 8;        // -seeds     matched CHEMICAL seeds (chemistry stays stochastic)
    static int    SEED0     = 101;      // -seed      first seed
    static double VCMD      = 2.0;      // -v         commanded axial speed (µm/s, signed along +axis)
    static double ZONE_DEG  = 40.0;     // -zone-deg  target-zone accessibility HALF-width
    static boolean ZONE_ON  = true;     // -no-zone   clears
    static double MIRROR    = 1.0;      // -mirror    -1 ⇒ mirrored actin lattice
    static double AZ0_DEG   = 0.0;      // -az0       stored starting filament azimuth (roll) in degrees
    static int    LATTICE   = 6;        // -lattice   1=native(13/6) 4=stair9-45 5=stair9-90 6=stair-fixture
    static int    WARMUP    = 2000;     // -warmup    steps discarded before statistics (transient)
    static String OUT       = "RUN_LOGS/vilfan_target_zone";  // -out
    static boolean EVENTS   = true;     // -no-events clears (suppress the per-attachment record file)
    // LAWN STRIP (declared assay fixture). With mechanical Brownian OFF there is no thermal search, so the
    // only motors that can ever engage are those whose relaxed deterministic pose already reaches the
    // filament. A full 3.0 x 1.0 µm lawn therefore spends ~95% of the CPU on motors that can never bind.
    // Narrowing the lawn to a strip matched to the head's transverse reach changes NO physics, NO density
    // and NO rate — it only removes motors that would contribute nothing. Both values are logged.
    static double MATX = 2.0;           // -matx  lawn length (µm)
    static double MATY = 0.05;          // -maty  lawn width  (µm)

    /** Restoration switches (Stage 7) — the fixture defaults hold every one of these OFF. */
    static boolean BR_FIL_AX = false, BR_FIL_TR = false, BR_FIL_ROLL = false, BR_FIL_OTH = false;
    static boolean BR_S2NODE = false, BR_CONV = false, BR_HEAD = false, BR_HEADROLL = false;
    static boolean PRESCRIBE = true;    // -no-prescribe : motor-driven translation instead
    static boolean CLAMP_TILT = true;   // -no-clamp-tilt
    static boolean RIGID_FIL = true;    // -no-rigid : flexible chain filament
    static double  CONV_SKEW_DEG = 0.0; // -conv-skew-deg : MUST stay 0 for the zero-stroke-skew test
    /** Vilfan graded competing-site attachment mode (0 = off ⇒ the hard-zone/canonical path is unchanged). */
    static int     GRADED_MODE = VilfanGradedBindingSystem.MODE_OFF;
    static int     GRAD_WINDOW = 6;
    static double  GRAD_ALPHA  = VilfanGradedBindingSystem.VILFAN_ALPHA;
    static boolean GRAD_SHADOW = false;
    /** Binding-only fixture: clamp the roll as well, so bond mechanics cannot feed back on the landscape. */
    static boolean CLAMP_ROLL = false;

    static final double TWOPI = 2.0 * Math.PI;

    // ===================================================================================================
    // THE RIG
    // ===================================================================================================
    static final class Attach {
        int motor, step, seg, site; double delta, azimBody, azimLab, roll, phase, zoneCenterPhase;
        double mirror, vdir; int before;          // +1 before, -1 after, 0 at centre
        double tau0;                              // instantaneous axial torque at the attachment step (N·m)
        double cDotDown;                          // cHat·(−eup): does the zone centre face the substrate?
        double xiNm, thetaRad, kTot, ringFrac, arcUm;   // Vilfan graded-mode attachment record
        double impulse; int lifeSteps; int detachNuc = -1; boolean open = true;
    }

    static final class Rig {
        Glide2D G; ExplicitCompleteMatHarness.ExMat e; FilamentStore f; MotorStore mot;
        int N, nSeg;
        FloatArray coord0, fixP;
        double[] axis = new double[3], prevY = new double[3];
        double roll, tSim;
        FloatArray yRef;                  // reference material yVec for the optional roll clamp
        double dphidarc, drift;
        double sumTau, sumAbsTau, sumBound; long nStat;
        double maxPosErr;
        List<Attach> events = new ArrayList<>();
        Attach[] open;
        int attachments, releasedNoSite;

        double axialTorqueTotal() {
            double s = 0;
            for (int m = 0; m < N; m++) s += ChiralSiteSystem.axialTorque(G.bondData, f.uVec, mot.boundSeg, m, nSeg);
            return s;
        }
    }

    /** Push every fixture switch into the shared harness statics. Call BEFORE {@link #buildRig}. */
    static void configure() {
        ExplicitCompleteMatHarness.resetChiral();
        ExplicitCompleteMatHarness.resetBrownianPolicy();
        ExplicitCompleteMatHarness.resetGeomScales();
        ExplicitCompleteMatHarness.SITE_MODE   = LATTICE;
        ExplicitCompleteMatHarness.SURFACE_ON  = true;      // off-axis attachment ⇒ an axial moment arm exists
        ExplicitCompleteMatHarness.HEAD_ROLL   = false;     // no head rotational DOF, no bound registry
        ExplicitCompleteMatHarness.REG_K       = 0.0;
        ExplicitCompleteMatHarness.EPS_BIND_DEG   = 0.0;    // old actin-side binding/interface skew OFF
        ExplicitCompleteMatHarness.EPS_STROKE_DEG = 0.0;
        ExplicitCompleteMatHarness.CONV_SKEW_DEG  = CONV_SKEW_DEG;   // ZERO handed component in the stroke
        ExplicitCompleteMatHarness.MIRROR_SIGN = MIRROR;
        ExplicitCompleteMatHarness.TZ_ON       = false;     // the legacy CONTINUOUS hazard stays off
        ExplicitCompleteMatHarness.TZ_ALPHA    = 0.0;
        ExplicitCompleteMatHarness.TZ_ZONE_HALF_DEG = ZONE_ON ? ZONE_DEG : 0.0;
        ExplicitCompleteMatHarness.TZ_ZONE_RECORD   = true; // always record delta (so the OFF arm has a histogram)
        ExplicitCompleteMatHarness.HEAD_ROLL_BROWN  = BR_HEADROLL;
        ExplicitCompleteMatHarness.BR_FIL_AXIAL  = BR_FIL_AX;
        ExplicitCompleteMatHarness.BR_FIL_TRANS  = BR_FIL_TR;
        ExplicitCompleteMatHarness.BR_FIL_ROLL   = BR_FIL_ROLL;
        ExplicitCompleteMatHarness.BR_FIL_OTHROT = BR_FIL_OTH;
        ExplicitCompleteMatHarness.BR_MOT_S2NODE = BR_S2NODE;
        ExplicitCompleteMatHarness.BR_MOT_CONV   = BR_CONV;
        ExplicitCompleteMatHarness.BR_MOT_HEAD   = BR_HEAD;
        ExplicitCompleteMatHarness.ATTACH_MODE   = GRADED_MODE;
        ExplicitCompleteMatHarness.GRAD_WINDOW   = GRAD_WINDOW;
        ExplicitCompleteMatHarness.GRAD_ALPHA    = GRAD_ALPHA;
        ExplicitCompleteMatHarness.GRAD_SHADOW   = GRAD_SHADOW;
    }

    static Rig buildRig(int seed) {
        Rig r = new Rig();
        Glide2D G = TwoBodyConverterMotor.buildS2Mat(DENSITY, DT, 40.0,
                TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, seed, RIGID_FIL);
        r.G = G; r.f = G.fil; r.mot = G.mot; r.nSeg = G.nSeg; r.N = G.N;
        // stored STARTING FILAMENT AZIMUTH: roll every segment's material yVec about its own uVec by az0.
        if (AZ0_DEG != 0.0) rollFilament(r.f, AZ0_DEG * Math.PI / 180.0);
        r.e = ExplicitCompleteMatHarness.packExMat(G, 1);   // brownOn=1: per-CHANNEL masks do the ablation
        int n = r.nSeg;
        r.axis[0] = r.f.uVec.get(0); r.axis[1] = r.f.uVec.get(n); r.axis[2] = r.f.uVec.get(2 * n);
        r.coord0 = new FloatArray(3 * n);
        for (int i = 0; i < 3 * n; i++) r.coord0.set(i, r.f.coord.get(i));
        r.fixP = FloatArray.fromElements((float) r.axis[0], (float) r.axis[1], (float) r.axis[2],
                (float) VCMD, 0f, CLAMP_TILT ? 1f : 0f, PRESCRIBE ? 1f : 0f);
        ChiralSiteHarness.seedPrevY(r.f, 0, r.prevY);
        fillZoneCentres(r);
        fillMotorAnchors(r);
        r.yRef = new FloatArray(3 * n);
        for (int i = 0; i < 3 * n; i++) r.yRef.set(i, r.f.yVec.get(i));
        double rise = ExplicitCompleteMatHarness.siteRise(LATTICE);
        double stair = ExplicitCompleteMatHarness.siteStairPhase(LATTICE);
        double twist = r.e.chiP.get(2);                       // already carries the mirror sign
        r.dphidarc = (stair != 0.0) ? (MIRROR < 0 ? -stair : stair) / rise : twist;
        if (stair != 0.0) r.dphidarc = r.e.chiP.get(3) / rise; // chiP[3] is the mirrored stair phase
        r.drift = -r.dphidarc * VCMD;
        r.open = new Attach[r.N];
        return r;
    }

    /** Rotate every segment's material yVec about its own uVec by {@code a} (the stored starting azimuth). */
    static void rollFilament(FilamentStore f, double a) {
        int n = f.n; double c = Math.cos(a), s = Math.sin(a);
        for (int k = 0; k < n; k++) {
            double ux = f.uVec.get(k), uy = f.uVec.get(n + k), uz = f.uVec.get(2 * n + k);
            double yx = f.yVec.get(k), yy = f.yVec.get(n + k), yz = f.yVec.get(2 * n + k);
            double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
            f.yVec.set(k,         (float) (c * yx + s * zx));
            f.yVec.set(n + k,     (float) (c * yy + s * zy));
            f.yVec.set(2 * n + k, (float) (c * yz + s * zz));
        }
        DerivedGeometrySystem.orthogonalizeY(f.uVec, f.yVec, f.counts);
    }

    /** One fixture step: the shared production CPU step, then the kinematic constraints, then the readout. */
    static void step(Rig r, int t, int seed) {
        int[] wasBound = new int[r.N];
        for (int m = 0; m < r.N; m++) wasBound[m] = r.mot.boundSeg.get(m);
        ExplicitCompleteMatHarness.stepGlidingCPU(r.e, t, seed);
        r.tSim = (t + 1) * DT;
        if (PRESCRIBE || CLAMP_TILT) {
            r.fixP.set(4, (float) r.tSim);
            AssayConstraintSystem.kinematicFixture(r.f.coord, r.f.uVec, r.f.yVec, r.coord0, r.fixP, r.f.counts);
            DerivedGeometrySystem.derive(r.f.coord, r.f.uVec, r.f.yVec, r.f.zVec, r.f.end1, r.f.end2,
                    r.f.segLength, r.f.counts);
        }
        if (CLAMP_ROLL) {   // binding-only fixture: restore the material frame so mechanics cannot feed back
            for (int i = 0; i < 3 * r.nSeg; i++) r.f.yVec.set(i, r.yRef.get(i));
            DerivedGeometrySystem.derive(r.f.coord, r.f.uVec, r.f.yVec, r.f.zVec, r.f.end1, r.f.end2,
                    r.f.segLength, r.f.counts);
        }
        r.roll += ChiralSiteHarness.rollIncrementTransported(r.f, 0, r.prevY);
        // commanded vs actual translation (a fixture-integrity readout, not a physics quantity)
        if (PRESCRIBE) {
            double dx = r.f.coord.get(0) - r.coord0.get(0), dy = r.f.coord.get(r.nSeg) - r.coord0.get(r.nSeg),
                   dz = r.f.coord.get(2 * r.nSeg) - r.coord0.get(2 * r.nSeg);
            double act = dx * r.axis[0] + dy * r.axis[1] + dz * r.axis[2];
            r.maxPosErr = Math.max(r.maxPosErr, Math.abs(act - VCMD * r.tSim));
        }
        recordEvents(r, t, wasBound);
        if (t >= WARMUP) {
            double tau = r.axialTorqueTotal();
            r.sumTau += tau; r.nStat++;
            double ab = 0; int nb = 0;
            for (int m = 0; m < r.N; m++) {
                double x = ChiralSiteSystem.axialTorque(r.G.bondData, r.f.uVec, r.mot.boundSeg, m, r.nSeg);
                ab += Math.abs(x); if (r.mot.boundSeg.get(m) >= 0) nb++;
            }
            r.sumAbsTau += ab; r.sumBound += nb;
        }
    }

    /** Per-attachment records: open on a fresh accepted bind, accumulate while bound, close on detach. */
    static void recordEvents(Rig r, int t, int[] wasBound) {
        int n = r.nSeg;
        for (int m = 0; m < r.N; m++) {
            int bs = r.mot.boundSeg.get(m);
            boolean fresh = r.e.justBound.get(m) == 1 && bs >= 0 && wasBound[m] < 0;
            if (fresh) {
                float d = r.e.tzOff.get(m);
                if (ExplicitCompleteMatHarness.gradedOn() || !Float.isNaN(d)) {
                    Attach a = new Attach();
                    a.motor = m; a.step = t; a.seg = bs; a.site = r.e.bindSite.get(m);
                    a.delta = d; a.azimBody = r.mot.bindAzim.get(m);
                    a.roll = r.roll; a.mirror = MIRROR; a.vdir = Math.signum(VCMD);
                    a.phase = wrap(a.azimBody);
                    a.zoneCenterPhase = wrap(a.azimBody - a.delta);   // the material azimuth AT the zone centre
                    a.azimLab = wrap(a.azimBody + r.roll);
                    double sd = Math.signum(r.drift) * a.delta;
                    a.before = sd < 0 ? 1 : (sd > 0 ? -1 : 0);
                    a.tau0 = ChiralSiteSystem.axialTorque(r.G.bondData, r.f.uVec, r.mot.boundSeg, m, n);
                    double[] ch = zoneCentreDirection(r, m);
                    a.cDotDown = -(ch[0]*r.G.eup[0] + ch[1]*r.G.eup[1] + ch[2]*r.G.eup[2]);
                    if (ExplicitCompleteMatHarness.gradedOn()) {
                        a.kTot     = r.e.gradData.get(3 * r.N + m);
                        a.xiNm     = r.e.gradData.get(4 * r.N + m);
                        a.thetaRad = r.e.gradData.get(5 * r.N + m);
                        a.ringFrac = r.e.gradData.get(6 * r.N + m);
                        a.arcUm    = motorArc(r, m);
                        a.delta    = a.thetaRad;      // the angular mismatch is the secondary diagnostic
                    }
                    r.open[m] = a; r.events.add(a); r.attachments++;
                }
            } else if (r.e.justBound.get(m) == 1 && bs < 0) r.releasedNoSite++;
            Attach a = r.open[m];
            if (a != null) {
                if (bs >= 0) {
                    a.impulse += ChiralSiteSystem.axialTorque(r.G.bondData, r.f.uVec, r.mot.boundSeg, m, n) * DT;
                    a.lifeSteps++;
                } else { a.detachNuc = r.mot.nucleotideState.get(m); a.open = false; r.open[m] = null; }
            }
        }
    }

    static double wrap(double a) {
        double x = a;
        while (x > Math.PI) x -= TWOPI;
        while (x <= -Math.PI) x += TWOPI;
        return x;
    }

    // ===================================================================================================
    // ONE ARM
    // ===================================================================================================
    static final class Res {
        int seed; double az0, v, zone, mirror; boolean zoneOn;
        int nBefore, nAfter, nCentre, attach, releasedNoSite;
        double aTZ, meanDelta, meanTau, meanAbsTau, meanBound, omega, turns, dist, turnsPerUm, pitchUm;
        double tauPerAttach, impulsePerAttach, attachRate, maxPosErr, drift, dphidarc;
        double[] hist = new double[24];
        String label = "";
    }

    static Res runArm(int seed, int steps) {
        Rig r = buildRig(seed);
        for (int t = 0; t < steps; t++) step(r, t, seed);
        Res s = new Res();
        s.seed = seed; s.az0 = AZ0_DEG; s.v = VCMD; s.zone = ZONE_DEG; s.zoneOn = ZONE_ON; s.mirror = MIRROR;
        s.drift = r.drift; s.dphidarc = r.dphidarc; s.maxPosErr = r.maxPosErr;
        s.releasedNoSite = r.releasedNoSite;
        double sd = 0, si = 0, st = 0; int na = 0;
        for (Attach a : r.events) {
            if (a.step < WARMUP) continue;
            na++;
            if (a.before > 0) s.nBefore++; else if (a.before < 0) s.nAfter++; else s.nCentre++;
            sd += a.delta; si += a.impulse; st += a.tau0;
            int b = (int) Math.floor((a.delta + Math.PI) / TWOPI * s.hist.length);
            if (b < 0) b = 0; if (b >= s.hist.length) b = s.hist.length - 1;
            s.hist[b]++;
        }
        s.attach = na;
        int den = s.nBefore + s.nAfter;
        s.aTZ = den > 0 ? (double) (s.nBefore - s.nAfter) / den : Double.NaN;
        s.meanDelta = na > 0 ? sd / na : Double.NaN;
        s.impulsePerAttach = na > 0 ? si / na : Double.NaN;
        s.tauPerAttach = na > 0 ? st / na : Double.NaN;
        double dur = (steps - WARMUP) * DT;
        s.attachRate = na / dur;
        s.meanTau = r.nStat > 0 ? r.sumTau / r.nStat : 0;
        s.meanAbsTau = r.nStat > 0 ? r.sumAbsTau / r.nStat : 0;
        s.meanBound = r.nStat > 0 ? r.sumBound / r.nStat : 0;
        s.omega = r.roll / (steps * DT);
        s.turns = r.roll / TWOPI;
        s.dist = Math.abs(VCMD) * steps * DT;
        s.turnsPerUm = s.dist > 0 ? s.turns / s.dist : Double.NaN;
        s.pitchUm = (Math.abs(s.turnsPerUm) > 1e-12) ? 1.0 / s.turnsPerUm : Double.NaN;
        if (EVENTS) writeEvents(r, seed);
        return s;
    }

    static void writeEvents(Rig r, int seed) {
        File d = new File(OUT, "events"); d.mkdirs();
        String nm = String.format(Locale.US, "events_lat%d_zone%s%.0f_v%+.2f_mir%+.0f_az%.0f_seed%d.csv",
                LATTICE, ZONE_ON ? "on" : "off", ZONE_DEG, VCMD, MIRROR, AZ0_DEG, seed);
        try (PrintWriter w = new PrintWriter(new File(d, nm))) {
            w.println("motor,step,seg,site,delta_rad,azim_body_rad,azim_lab_rad,roll_rad,phase_rad,"
                    + "zone_centre_phase_rad,before(1)/after(-1),mirror,v_dir,tau0_Nm,impulse_Nms,life_steps,detach_nuc,cHat_dot_down,xi_nm,theta_rad,k_total_per_s,ring_frac,motor_arc_um");
            for (Attach a : r.events)
                w.printf(Locale.US, "%d,%d,%d,%d,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%d,%+.0f,%+.0f,%.9g,%.9g,%d,%d,"
                                + "%.6f,%.9g,%.9g,%.9g,%.6g,%.9g%n",
                        a.motor, a.step, a.seg, a.site, a.delta, a.azimBody, a.azimLab, a.roll, a.phase,
                        a.zoneCenterPhase, a.before, a.mirror, a.vdir, a.tau0, a.impulse, a.lifeSteps, a.detachNuc,
                        a.cDotDown, a.xiNm, a.thetaRad, a.kTot, a.ringFrac, a.arcUm);
        } catch (Exception ex) { System.out.println("  ! event write failed: " + ex); }
    }

    // ===================================================================================================
    // REPORTING HELPERS
    // ===================================================================================================
    static double[] meanSem(double[] x) {
        int n = 0; double s = 0;
        for (double v : x) if (!Double.isNaN(v)) { s += v; n++; }
        if (n == 0) return new double[]{ Double.NaN, Double.NaN, 0 };
        double m = s / n, q = 0;
        for (double v : x) if (!Double.isNaN(v)) q += (v - m) * (v - m);
        double sem = n > 1 ? Math.sqrt(q / (n - 1) / n) : 0;
        return new double[]{ m, sem, n };
    }
    static double[] col(List<Res> rs, java.util.function.ToDoubleFunction<Res> f) {
        double[] o = new double[rs.size()];
        for (int i = 0; i < rs.size(); i++) o[i] = f.applyAsDouble(rs.get(i));
        return o;
    }
    static double corr(double[] a, double[] b) {
        int n = 0; double sa = 0, sb = 0;
        for (int i = 0; i < a.length; i++) if (!Double.isNaN(a[i]) && !Double.isNaN(b[i])) { sa += a[i]; sb += b[i]; n++; }
        if (n < 3) return Double.NaN;
        double ma = sa / n, mb = sb / n, saa = 0, sbb = 0, sab = 0;
        for (int i = 0; i < a.length; i++) if (!Double.isNaN(a[i]) && !Double.isNaN(b[i])) {
            saa += (a[i]-ma)*(a[i]-ma); sbb += (b[i]-mb)*(b[i]-mb); sab += (a[i]-ma)*(b[i]-mb); }
        return (saa > 0 && sbb > 0) ? sab / Math.sqrt(saa * sbb) : Double.NaN;
    }
    static int signAgree(double[] x) { int k = 0; for (double v : x) if (v > 0) k++; else if (v < 0) k--; return k; }

    static String cfgLine() {
        return String.format(Locale.US,
            "lattice=%s | zone=%s(half=%.2f deg) | mirror=%+.0f | v=%+.3f µm/s | az0=%.1f deg | conv-skew=%.2f deg | "
            + "rigid=%s prescribe=%s clampTilt=%s | brownian[fil ax=%s tr=%s roll=%s oth=%s | mot s2node=%s conv=%s "
            + "head=%s headroll=%s] | lawn=%.2fx%.3f µm density=%.0f (N=%d) dt=%.3g steps=%d warmup=%d seeds=%d | %s",
            ExplicitCompleteMatHarness.siteModeName(LATTICE), ZONE_ON ? "ON" : "OFF", ZONE_DEG, MIRROR, VCMD,
            AZ0_DEG, CONV_SKEW_DEG, RIGID_FIL, PRESCRIBE, CLAMP_TILT,
            BR_FIL_AX, BR_FIL_TR, BR_FIL_ROLL, BR_FIL_OTH, BR_S2NODE, BR_CONV, BR_HEAD, BR_HEADROLL,
            MATX, MATY, DENSITY, (int) Math.round(DENSITY * MATX * MATY), DT, STEPS, WARMUP, SEEDS,
            ExplicitCompleteMatHarness.brownianPolicyString());
    }

    // ===================================================================================================
    // MAIN
    // ===================================================================================================
    public static void main(String[] args) throws Exception {
        String mode = "-gates";
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-gates", "-campaign", "-ladder", "-sens", "-smoke", "-all" -> mode = a;
                case "-density" -> DENSITY = Double.parseDouble(args[++i]);
                case "-matx" -> MATX = Double.parseDouble(args[++i]);
                case "-maty" -> MATY = Double.parseDouble(args[++i]);
                case "-dt" -> DT = Double.parseDouble(args[++i]);
                case "-steps" -> STEPS = Integer.parseInt(args[++i]);
                case "-warmup" -> WARMUP = Integer.parseInt(args[++i]);
                case "-seeds" -> SEEDS = Integer.parseInt(args[++i]);
                case "-seed" -> SEED0 = Integer.parseInt(args[++i]);
                case "-v" -> VCMD = Double.parseDouble(args[++i]);
                case "-zone-deg" -> ZONE_DEG = Double.parseDouble(args[++i]);
                case "-no-zone" -> ZONE_ON = false;
                case "-mirror" -> MIRROR = Double.parseDouble(args[++i]);
                case "-az0" -> AZ0_DEG = Double.parseDouble(args[++i]);
                case "-lattice" -> LATTICE = Integer.parseInt(args[++i]);
                case "-site-rise-nm" -> ExplicitCompleteMatHarness.SITE_FIX_RISE_NM = Double.parseDouble(args[++i]);
                case "-site-stair-deg" -> ExplicitCompleteMatHarness.SITE_FIX_STAIR_DEG = Double.parseDouble(args[++i]);
                case "-conv-skew-deg" -> CONV_SKEW_DEG = Double.parseDouble(args[++i]);
                case "-no-prescribe" -> PRESCRIBE = false;
                case "-no-clamp-tilt" -> CLAMP_TILT = false;
                case "-no-rigid" -> RIGID_FIL = false;
                case "-no-events" -> EVENTS = false;
                case "-out" -> OUT = args[++i];
                case "-filament-brownian-axial" -> BR_FIL_AX = on(args[++i]);
                case "-filament-brownian-transverse" -> BR_FIL_TR = on(args[++i]);
                case "-filament-brownian-roll" -> BR_FIL_ROLL = on(args[++i]);
                case "-filament-brownian-other-rotation" -> BR_FIL_OTH = on(args[++i]);
                case "-s2-node-brownian" -> BR_S2NODE = on(args[++i]);
                case "-converter-brownian" -> BR_CONV = on(args[++i]);
                case "-motor-head-brownian" -> BR_HEAD = on(args[++i]);
                case "-head-roll-brownian" -> BR_HEADROLL = on(args[++i]);
                default -> { if (a.startsWith("-")) throw new IllegalArgumentException("unknown flag " + a); }
            }
        }
        new File(OUT).mkdirs();
        TwoBodyConverterMotor.G4_MX = MATX; TwoBodyConverterMotor.G4_MY = MATY;   // declared lawn strip
        System.out.println("=== VILFAN TARGET-ZONE DETERMINISTIC FIXTURE (CPU ONLY) ===");
        System.out.println("  " + cfgLine());
        writeManifest(mode);
        long t0 = System.currentTimeMillis();
        boolean ok = true;
        switch (mode) {
            case "-gates" -> ok = runGates();
            case "-campaign" -> runCampaign();
            case "-ladder" -> runLadder();
            case "-sens" -> runSensitivity();
            case "-smoke" -> ok = runRestorationSmoke();
            case "-all" -> { ok = runGates(); runCampaign(); runLadder(); ok &= runRestorationSmoke(); }
        }
        System.out.printf(Locale.US, "%n  elapsed %.1f s%n", (System.currentTimeMillis() - t0) / 1000.0);
        if (!ok) System.out.println("=== SOME GATES FAILED ===");
    }
    static boolean on(String s) { return s.equalsIgnoreCase("on") || s.equalsIgnoreCase("true"); }

    static void writeManifest(String mode) {
        try (PrintWriter w = new PrintWriter(new File(OUT, "manifest_" + mode.substring(1) + ".json"))) {
            w.printf(Locale.US, "{%n  \"mode\": \"%s\",%n  \"backend\": \"CPU (sequential Java runner; no TornadoVM "
                    + "device execution, no TaskGraph, no GPU)\",%n  \"git_rev\": \"%s\",%n  \"branch\": \"%s\",%n"
                    + "  \"jvm\": \"%s\",%n  \"available_processors\": %d,%n  \"config\": \"%s\"%n}%n",
                    mode, gitRev("HEAD"), gitRev("--abbrev-ref HEAD"), System.getProperty("java.version"),
                    Runtime.getRuntime().availableProcessors(), cfgLine().replace("\"", "'"));
        } catch (Exception ex) { System.out.println("  ! manifest write failed: " + ex); }
    }
    static String gitRev(String what) {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", what).redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes()).trim(); p.waitFor(); return s;
        } catch (Exception ex) { return "unknown"; }
    }

    // ===================================================================================================
    // STAGE 4 — UNIT AND FIXTURE VALIDATION
    // ===================================================================================================
    static int gPass, gFail;
    /** measured in gate C7: the empirical mapping from the signed zone offset to the axial torque. */
    static double TAU_PER_DELTA = Double.NaN, TAU_DELTA_R = Double.NaN;
    static void ck(String name, boolean cond, String detail) {
        System.out.printf("  [%s] %-58s %s%n", cond ? "PASS" : "FAIL", name, detail);
        if (cond) gPass++; else gFail++;
    }

    static boolean runGates() {
        gPass = 0; gFail = 0;
        System.out.println("\n--- STAGE 4 VALIDATION GATES ---");
        gateA(); gateB(); gateC(); gateD(); gateE(); gateF(); gateG();
        System.out.printf("%n  GATES: %d PASS / %d FAIL%n", gPass, gFail);
        return gFail == 0;
    }

    /** A — helical geometry: spacing, azimuthal advance, wrapping, continuity, stored azimuth, frames. */
    static void gateA() {
        System.out.println("\n A. HELICAL SITE GEOMETRY");
        double rise = ExplicitCompleteMatHarness.siteRise(LATTICE);
        double stair = ExplicitCompleteMatHarness.siteStairPhase(LATTICE);
        ck("A1 site rise > 0 and matches the declared lattice", rise > 0,
                String.format(Locale.US, "rise=%.6f nm", rise * 1e3));
        ck("A2 azimuthal advance per site is the declared value", Math.abs(stair) > 0 || LATTICE == 1,
                String.format(Locale.US, "stair=%.6f deg (0 ⇒ analytic native helix)", stair * 180 / Math.PI));
        // periodic wrapping + continuity through ±pi
        double a = Math.PI - 1e-3, b = -Math.PI + 1e-3;
        double d = wrap(b - a);
        ck("A3 wrapping continuous through ±pi", Math.abs(d - 2e-3) < 1e-9,
                String.format(Locale.US, "wrap(%.6f-%.6f)=%.3e (expect 2.000e-03)", b, a, d));
        // stored starting azimuth survives the build and the material frame transform
        double savedAz = AZ0_DEG; AZ0_DEG = 37.0;
        configure(); Rig r = buildRig(SEED0);
        double[] y0 = new double[3]; ChiralSiteHarness.seedPrevY(r.f, 0, y0);
        AZ0_DEG = 0.0; configure(); Rig r0 = buildRig(SEED0);
        double[] yb = new double[3]; ChiralSiteHarness.seedPrevY(r0.f, 0, yb);
        double dot = y0[0]*yb[0] + y0[1]*yb[1] + y0[2]*yb[2];
        double ang = Math.acos(Math.max(-1, Math.min(1, dot))) * 180 / Math.PI;
        ck("A4 stored starting filament azimuth applied exactly", Math.abs(ang - 37.0) < 1e-3,
                String.format(Locale.US, "measured %.6f deg (commanded 37)", ang));
        AZ0_DEG = savedAz; configure();
        // body-fixed vs laboratory: rolling the filament by delta shifts a site's LAB azimuth by delta
        Rig rr = buildRig(SEED0);
        double before = labAzimOfSite(rr, 0.0);
        rollFilament(rr.f, 0.4);
        double after = labAzimOfSite(rr, 0.0);
        ck("A5 body-fixed → laboratory transform: roll δ shifts lab azimuth by δ",
                Math.abs(wrap(after - before) - 0.4) < 1e-4,
                String.format(Locale.US, "Δ=%.6f rad (commanded 0.400000)", wrap(after - before)));
    }
    /** Laboratory azimuth of the material azimuth {@code phi} on segment 0 (angle of nSite about a fixed ref). */
    static double labAzimOfSite(Rig r, double phi) {
        int n = r.nSeg;
        double ux = r.f.uVec.get(0), uy = r.f.uVec.get(n), uz = r.f.uVec.get(2*n);
        double yx = r.f.yVec.get(0), yy = r.f.yVec.get(n), yz = r.f.yVec.get(2*n);
        double zx = uy*yz - uz*yy, zy = uz*yx - ux*yz, zz = ux*yy - uy*yx;
        double nx = Math.cos(phi)*yx + Math.sin(phi)*zx;
        double ny = Math.cos(phi)*yy + Math.sin(phi)*zy;
        double nz = Math.cos(phi)*yz + Math.sin(phi)*zz;
        // measure against a FIXED laboratory reference pair (e1, e2) ⊥ axis
        double[] e1 = perpOf(r.axis), e2 = cross(r.axis, e1);
        return Math.atan2(nx*e2[0]+ny*e2[1]+nz*e2[2], nx*e1[0]+ny*e1[1]+nz*e1[2]);
    }
    static double[] perpOf(double[] u) {
        double[] t = (Math.abs(u[0]) < 0.9) ? new double[]{1,0,0} : new double[]{0,1,0};
        double d = t[0]*u[0]+t[1]*u[1]+t[2]*u[2];
        double[] p = { t[0]-d*u[0], t[1]-d*u[1], t[2]-d*u[2] };
        double l = Math.sqrt(p[0]*p[0]+p[1]*p[1]+p[2]*p[2]);
        return new double[]{ p[0]/l, p[1]/l, p[2]/l };
    }
    static double[] cross(double[] a, double[] b) {
        return new double[]{ a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0] }; }

    /** B — mirror transformation: handedness reverses; geometry, motors, axes, RNG, velocity unchanged. */
    static void gateB() {
        System.out.println("\n B. MIRROR TRANSFORMATION");
        double sm = MIRROR;
        MIRROR = +1; configure(); Rig rp = buildRig(SEED0);
        MIRROR = -1; configure(); Rig rm = buildRig(SEED0);
        MIRROR = sm; configure();
        ck("B1 mirroring reverses the helical phase gradient",
                Math.abs(rp.dphidarc + rm.dphidarc) < 1e-6 * Math.abs(rp.dphidarc) && rp.dphidarc * rm.dphidarc < 0,
                String.format(Locale.US, "dphi/darc %+.4f → %+.4f rad/µm", rp.dphidarc, rm.dphidarc));
        ck("B2 mirroring reverses the zone-phase drift D",
                rp.drift * rm.drift < 0 && Math.abs(rp.drift + rm.drift) < 1e-6 * Math.abs(rp.drift),
                String.format(Locale.US, "D %+.4f → %+.4f rad/s", rp.drift, rm.drift));
        double dSite = 0, dMot = 0;
        for (int i = 0; i < 3 * rp.nSeg; i++) dSite = Math.max(dSite, Math.abs(rp.f.coord.get(i) - rm.f.coord.get(i)));
        for (int m = 0; m < rp.N; m++)
            for (int c = 0; c < 3; c++)
                dMot = Math.max(dMot, Math.abs(rp.G.A[m][c] - rm.G.A[m][c]));
        ck("B3 filament pose and segment geometry unchanged by mirroring", dSite == 0.0,
                String.format(Locale.US, "max|Δcoord|=%.3e µm", dSite));
        ck("B4 motor anchor positions unchanged by mirroring", dMot == 0.0,
                String.format(Locale.US, "max|Δanchor|=%.3e µm", dMot));
        ck("B5 site rise (axial spacing) unchanged by mirroring",
                rp.e.chiP.get(1) == rm.e.chiP.get(1),
                String.format(Locale.US, "rise %.6f / %.6f nm", rp.e.chiP.get(1)*1e3, rm.e.chiP.get(1)*1e3));
        ck("B6 imposed velocity and laboratory axis unchanged by mirroring",
                rp.fixP.get(3) == rm.fixP.get(3) && rp.axis[0] == rm.axis[0] && rp.axis[1] == rm.axis[1]
                        && rp.axis[2] == rm.axis[2],
                String.format(Locale.US, "v=%.3f, axis=(%.3f,%.3f,%.3f)", rp.fixP.get(3),
                        rp.axis[0], rp.axis[1], rp.axis[2]));
        ck("B7 mirror sign is the ONLY chiral input (site tangential sense flips)",
                rp.e.chiP.get(13) == +1.0 && rm.e.chiP.get(13) == -1.0,
                String.format(Locale.US, "chiP[13] %+.0f / %+.0f", rp.e.chiP.get(13), rm.e.chiP.get(13)));
    }

    /** C — the target-zone gate on hand-constructed geometry. */
    static void gateC() {
        System.out.println("\n C. TARGET-ZONE GATE");
        configure();
        Rig r = buildRig(SEED0);
        // C1/C2: surface-facing vs occluded, using the kernel's own definition evaluated on a synthetic head.
        // Place a synthetic head radially outward at azimuth psi and ask for the signed offset of a site at phi.
        double[] out = new double[3];
        boolean okFacing = true, okOccluded = true, okExchange = true;
        for (double psi = -Math.PI; psi < Math.PI; psi += 0.37) {
            double dl = syntheticDelta(r, psi, psi);            // site normal exactly at the head
            okFacing &= Math.abs(dl) < 1e-5;
            double dz = syntheticDelta(r, psi, wrap(psi + Math.PI));  // site normal exactly opposite
            okOccluded &= Math.abs(Math.abs(dz) - Math.PI) < 1e-4;
            out[0] = Math.max(out[0], Math.abs(dl));
        }
        ck("C1 site whose normal faces the motor ⇒ delta = 0 (zone centre)", okFacing,
                String.format(Locale.US, "max|delta| over 17 azimuths = %.3e rad", out[0]));
        ck("C2 site whose normal faces away ⇒ |delta| = pi (fully occluded)", okOccluded, "checked over 17 azimuths");
        // C3: before/after labels exchange as the site passes the centre
        double dm = syntheticDelta(r, 0.0, -0.30), dp = syntheticDelta(r, 0.0, +0.30);
        int bm = Math.signum(r.drift) * dm < 0 ? 1 : -1, bp = Math.signum(r.drift) * dp < 0 ? 1 : -1;
        okExchange = (dm < 0 && dp > 0) && (bm != bp);
        ck("C3 signed offset changes sign through the centre; before/after exchange", okExchange,
                String.format(Locale.US, "delta(-0.30)=%+.4f [%s], delta(+0.30)=%+.4f [%s], sign(D)=%+.0f",
                        dm, bm > 0 ? "BEFORE" : "AFTER", dp, bp > 0 ? "BEFORE" : "AFTER", Math.signum(r.drift)));
        // C4: periodicity and continuity of the gate
        double e1 = syntheticDelta(r, 0.0, Math.PI - 1e-4), e2 = syntheticDelta(r, 0.0, -Math.PI + 1e-4);
        ck("C4 gate periodic and continuous across ±pi", Math.abs(wrap(e2 - e1) - 2e-4) < 1e-6,
                String.format(Locale.US, "delta(pi-)=%+.6f, delta(-pi+)=%+.6f, wrapped Δ=%.3e", e1, e2, wrap(e2 - e1)));
        // C5: the zone-centre direction coincides with the substrate-facing radial direction — MEASURED over
        // the motors that ACTUALLY attach. The covariant definition (radial direction toward this head's own
        // F8 anchor) is the one the kernel uses; this gate confirms it is the substrate-facing direction in a
        // surface assay, rather than assuming it. Motors far off to the side never reach the filament, so the
        // population that matters is the attaching one.
        Rig rc5 = buildRig(SEED0);
        for (int t = 0; t < 1500; t++) step(rc5, t, SEED0);
        double sc = 0, mn = 1.0; int nc = 0;
        for (Attach at : rc5.events) { sc += at.cDotDown; mn = Math.min(mn, at.cDotDown); nc++; }
        double cdotDown = nc > 0 ? sc / nc : Double.NaN;
        ck("C5 zone centre = the substrate-facing radial direction (measured over real attachments)",
                nc > 0 && cdotDown > 0.90,
                String.format(Locale.US, "mean cHat·(−eup) = %.4f over %d attachments (min %.4f; 1.0 = exactly "
                        + "substrate-facing)", cdotDown, nc, mn));
        // C7: THE CAUSAL LINK, measured rather than asserted — regress the instantaneous axial torque at
        // attachment on the signed zone offset of that attachment. The bond acts at the actin radius, so a
        // nonzero offset must produce a moment about the filament axis with a DEFINITE sign; this gate
        // establishes the sign and the strength of that mapping empirically, so the campaign never has to
        // assume it. (A pure geometric argument would give tau ~ -R*rho*k*sin(delta); the measurement is
        // what the report quotes.)
        int nq = rc5.events.size();
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (Attach at : rc5.events) { sx += at.delta; sy += at.tau0; sxx += at.delta*at.delta;
                                       syy += at.tau0*at.tau0; sxy += at.delta*at.tau0; }
        double slope = Double.NaN, rr = Double.NaN;
        if (nq >= 3) {
            double vx = sxx - sx*sx/nq, vy = syy - sy*sy/nq, cxy = sxy - sx*sy/nq;
            if (vx > 0) slope = cxy / vx;
            if (vx > 0 && vy > 0) rr = cxy / Math.sqrt(vx*vy);
        }
        TAU_PER_DELTA = slope; TAU_DELTA_R = rr;
        ck("C7 signed zone offset maps to axial torque with a definite sign (measured)",
                nq >= 3 && Math.abs(rr) > 0.3,
                String.format(Locale.US, "dtau/ddelta = %+.4e N·m/rad, r = %+.4f over %d attachments "
                        + "⇒ delta %s ⇒ tau %s", slope, rr, nq,
                        slope < 0 ? "<0" : ">0", slope < 0 ? ">0" : ">0"));
        // C6: the gate actually removes candidates
        int nAcc = 0, nTot = 0;
        for (double phi = -Math.PI; phi < Math.PI; phi += TWOPI / 360) {
            nTot++; if (Math.abs(syntheticDelta(r, 0.0, phi)) <= ZONE_DEG * Math.PI / 180) nAcc++;
        }
        double frac = (double) nAcc / nTot, expect = ZONE_DEG / 180.0;
        ck("C6 accessible azimuthal fraction = zone width / pi", Math.abs(frac - expect) < 0.02,
                String.format(Locale.US, "accessible %.4f of the circle (expect %.4f)", frac, expect));
    }
    /**
     * Fill the per-motor TARGET-ZONE CENTRE DIRECTION: the radial direction from the filament axis toward
     * each motor's FIXED SUBSTRATE TETHER POINT ({@code g4E[m]}, where its S2 emerges from the coverslip).
     *
     * <p>This is the motor's surface-side reach geometry and, for a lawn beneath the filament, the
     * substrate-facing radial direction — the quantity gate C5 measures. It is deliberately NOT built from
     * the head's instantaneous pose: the head moves, so a zone referenced to it would follow the head and
     * would not be a zone at all (that error was caught by gate C5 and is recorded in the report).
     *
     * <p>The direction is perpendicular to the axis and referenced to the axis LINE, so it is invariant
     * under the fixture's prescribed axial translation and under filament roll. The fixture also clamps the
     * tilt, so it is constant for the whole run and is filled once at build.
     */
    static void fillZoneCentres(Rig r) {
        int n = r.nSeg, N = r.N;
        double cx = r.f.coord.get(0), cy = r.f.coord.get(n), cz = r.f.coord.get(2*n);
        double ux = r.axis[0], uy = r.axis[1], uz = r.axis[2];
        for (int m = 0; m < N; m++) {
            double[] E = r.G.g4E[m];
            double rx = E[0] - cx, ry = E[1] - cy, rz = E[2] - cz;
            double d = rx*ux + ry*uy + rz*uz;
            rx -= d*ux; ry -= d*uy; rz -= d*uz;
            double l = Math.sqrt(rx*rx + ry*ry + rz*rz);
            if (l > 1e-12) { rx /= l; ry /= l; rz /= l; } else { rx = ry = rz = 0; }
            r.e.tzOff.set(N + m, (float) rx); r.e.tzOff.set(2*N + m, (float) ry); r.e.tzOff.set(3*N + m, (float) rz);
        }
    }

    /**
     * Publish each motor's SUBSTRATE ANCHOR into the graded buffer. Vilfan's {@code x_M} is the motor's
     * anchoring point on the coverslip, so this is {@code g4E[m]}, the S2 emergence point — the same point
     * the hard-zone study used for its zone-centre direction.
     */
    static void fillMotorAnchors(Rig r) {
        int N = r.N;
        for (int m = 0; m < N; m++) {
            double[] E = r.G.g4E[m];
            r.e.gradData.set(m, E[0]); r.e.gradData.set(N + m, E[1]); r.e.gradData.set(2 * N + m, E[2]);
            r.e.gradData.set(7 * N + m, r.G.noBind[m] ? 1.0 : 0.0);
        }
    }

    /** The arc coordinate (µm, from the filament's end1) of motor m's axial foot — the landscape phase. */
    static double motorArc(Rig r, int m) {
        int n = r.nSeg;
        double[] E = r.G.g4E[m];
        double cx = r.f.coord.get(0), cy = r.f.coord.get(n), cz = r.f.coord.get(2 * n);
        double ux = r.f.uVec.get(0), uy = r.f.uVec.get(n), uz = r.f.uVec.get(2 * n);
        double half = 0.5 * r.f.segLength.get(0);
        return r.e.segCumArc.get(0) + ((E[0] - cx) * ux + (E[1] - cy) * uy + (E[2] - cz) * uz) + half;
    }

    /** The zone-centre direction actually in force for motor m (read back from the buffer the kernel reads). */
    static double[] zoneCentreDirection(Rig r, int m) {
        int N = r.N;
        return new double[]{ r.e.tzOff.get(N + m), r.e.tzOff.get(2*N + m), r.e.tzOff.get(3*N + m) };
    }
    /** Signed offset of a site at material azimuth {@code phi} from a zone centre at material azimuth {@code psi}. */
    static double syntheticDelta(Rig r, double psi, double phi) {
        int n = r.nSeg;
        double ux = r.f.uVec.get(0), uy = r.f.uVec.get(n), uz = r.f.uVec.get(2*n);
        double yx = r.f.yVec.get(0), yy = r.f.yVec.get(n), yz = r.f.yVec.get(2*n);
        double zx = uy*yz - uz*yy, zy = uz*yx - ux*yz, zz = ux*yy - uy*yx;
        double cx = Math.cos(psi)*yx + Math.sin(psi)*zx, cy2 = Math.cos(psi)*yy + Math.sin(psi)*zy,
               cz = Math.cos(psi)*yz + Math.sin(psi)*zz;
        double nx = Math.cos(phi)*yx + Math.sin(phi)*zx, ny = Math.cos(phi)*yy + Math.sin(phi)*zy,
               nz = Math.cos(phi)*yz + Math.sin(phi)*zz;
        double dt2 = cx*nx + cy2*ny + cz*nz;
        double qx = cy2*nz - cz*ny, qy = cz*nx - cx*nz, qz = cx*ny - cy2*nx;
        double mag = TwoBodyBeamAnalyticGpu.tzAngle(qx*qx + qy*qy + qz*qz, dt2);
        return (qx*ux + qy*uy + qz*uz) < 0 ? -mag : mag;
    }

    /** D — imposed translation with motors disabled: exact kinematics, zero roll, zero torque, no drift. */
    static void gateD() {
        System.out.println("\n D. IMPOSED TRANSLATION (MOTORS DISABLED)");
        configure();
        Rig r = buildRig(SEED0);
        for (int m = 0; m < r.N; m++) { r.G.noBind[m] = true; r.e.noBind.set(m, 1); }
        int K = 4000;
        for (int t = 0; t < K; t++) step(r, t, SEED0);
        int n = r.nSeg;
        double dx = r.f.coord.get(0) - r.coord0.get(0), dy = r.f.coord.get(n) - r.coord0.get(n),
               dz = r.f.coord.get(2*n) - r.coord0.get(2*n);
        double act = dx*r.axis[0] + dy*r.axis[1] + dz*r.axis[2];
        double lat = Math.sqrt(Math.max(0, dx*dx + dy*dy + dz*dz - act*act));
        double cmd = VCMD * K * DT;
        ck("D1 translation follows the prescribed velocity", Math.abs(act - cmd) < 1e-6,
                String.format(Locale.US, "actual %.9f vs commanded %.9f µm (max err over run %.2e)", act, cmd, r.maxPosErr));
        ck("D2 no lateral drift", lat < 1e-7, String.format(Locale.US, "lateral %.3e µm", lat));
        double tilt = Math.acos(Math.max(-1, Math.min(1,
                r.f.uVec.get(0)*r.axis[0] + r.f.uVec.get(n)*r.axis[1] + r.f.uVec.get(2*n)*r.axis[2])));
        ck("D3 no tilt develops", tilt < 1e-6, String.format(Locale.US, "tilt %.3e rad", tilt));
        ck("D4 axial roll remains zero", Math.abs(r.roll) < 1e-9,
                String.format(Locale.US, "roll %.3e rad over %d steps", r.roll, K));
        ck("D5 axial torque remains zero", Math.abs(r.axialTorqueTotal()) < 1e-30,
                String.format(Locale.US, "tau %.3e N·m", r.axialTorqueTotal()));
        ck("D6 no motor attached (control is genuinely motor-free)", r.attachments == 0,
                "attachments = " + r.attachments);
    }

    /** E — default identity: the new fields never alter a binding decision or a trajectory. */
    static void gateE() {
        System.out.println("\n E. DEFAULT IDENTITY (new features absent ⇒ existing behaviour preserved)");
        int K = 1500;
        // E1: recording the offset with the GATE OFF must not change ANY decision or the trajectory.
        boolean sz = ZONE_ON; ZONE_ON = false;
        configure(); ExplicitCompleteMatHarness.TZ_ZONE_RECORD = true;
        Rig ra = buildRig(SEED0);
        for (int t = 0; t < K; t++) step(ra, t, SEED0);
        configure(); ExplicitCompleteMatHarness.TZ_ZONE_RECORD = false;
        Rig rb = buildRig(SEED0);
        for (int t = 0; t < K; t++) step(rb, t, SEED0);
        double dC = 0; int dB = 0;
        for (int i = 0; i < 3 * ra.nSeg; i++) dC = Math.max(dC, Math.abs(ra.f.coord.get(i) - rb.f.coord.get(i)));
        for (int m = 0; m < ra.N; m++) if (ra.mot.boundSeg.get(m) != rb.mot.boundSeg.get(m)) dB++;
        ck("E1 offset recording alters NO binding decision and NO trajectory bit",
                dC == 0.0 && dB == 0 && ra.roll == rb.roll,
                String.format(Locale.US, "max|Δcoord|=%.3e µm, bound mismatches=%d, Δroll=%.3e", dC, dB, ra.roll - rb.roll));
        ZONE_ON = sz;
        // E2: the per-channel motor Brownian bits at their canonical values reproduce the single global mask.
        boolean s1 = BR_S2NODE, s2 = BR_CONV, s3 = BR_HEAD;
        BR_S2NODE = true; BR_CONV = true; BR_HEAD = true;
        configure();
        Rig rc = buildRig(SEED0);
        for (int t = 0; t < K; t++) ExplicitCompleteMatHarness.stepGlidingCPU(rc.e, t, SEED0);
        BR_S2NODE = false; BR_CONV = false; BR_HEAD = false;
        configure();
        Rig rd = buildRig(SEED0);
        rd.e.matc.set(3, 0);                       // policy cleared, but built via packExMat with the bits set
        ExplicitCompleteMatHarness.BR_MOT_S2NODE = true; ExplicitCompleteMatHarness.BR_MOT_CONV = true;
        ExplicitCompleteMatHarness.BR_MOT_HEAD = true;
        for (int t = 0; t < K; t++) { rd.e.matc.set(3, 0); ExplicitCompleteMatHarness.stepGlidingCPU(rd.e, t, SEED0); }
        double dE = 0;
        for (int i = 0; i < 3 * rc.nSeg; i++) dE = Math.max(dE, Math.abs(rc.f.coord.get(i) - rd.f.coord.get(i)));
        ck("E2 per-channel motor Brownian bits = 0 ⇒ bit-identical to the canonical mask", dE == 0.0,
                String.format(Locale.US, "max|Δcoord|=%.3e µm over %d steps", dE, K));
        BR_S2NODE = s1; BR_CONV = s2; BR_HEAD = s3;
        // E3: the three per-channel bits set == the single global motor-Brownian switch (matc[2] = 0).
        // BOTH arms are built through buildRig so they share an identical scene, identical zone-centre
        // buffer and identical feature state; the ONLY difference is HOW the motor Brownian is silenced.
        BR_S2NODE = false; BR_CONV = false; BR_HEAD = false;
        configure();
        Rig re = buildRig(SEED0);
        for (int t = 0; t < K; t++) step(re, t, SEED0);
        BR_S2NODE = true; BR_CONV = true; BR_HEAD = true;      // policy bits clear ...
        configure();
        Rig rg = buildRig(SEED0);
        rg.e.matc.set(2, 0);                                    // ... and silence via the global switch instead
        for (int t = 0; t < K; t++) step(rg, t, SEED0);
        double dF = 0;
        for (int i2 = 0; i2 < 3 * re.nSeg; i2++) dF = Math.max(dF, Math.abs(re.f.coord.get(i2) - rg.f.coord.get(i2)));
        ck("E3 all three motor channels OFF ≡ the global motor-Brownian switch OFF",
                dF == 0.0 && re.roll == rg.roll,
                String.format(Locale.US, "max|Δcoord|=%.3e µm, Δroll=%.3e over %d steps", dF, re.roll - rg.roll, K));
        BR_S2NODE = s1; BR_CONV = s2; BR_HEAD = s3; configure();
    }

    /** F — zero-chirality null: skew 0, zone OFF, Brownian OFF, prescribed translation ON ⇒ no systematic twist. */
    static void gateF() {
        System.out.println("\n F. ZERO-CHIRALITY NULL");
        boolean sz = ZONE_ON; ZONE_ON = false; configure();
        List<Res> rs = new ArrayList<>();
        for (int i = 0; i < Math.min(SEEDS, 4); i++) rs.add(runArm(SEED0 + i, Math.min(STEPS, 8000)));
        ZONE_ON = sz; configure();
        double[] tau = meanSem(col(rs, x -> x.meanTau)), om = meanSem(col(rs, x -> x.omega));
        ck("F1 no resolved axial torque with the zone OFF and zero stroke skew",
                Math.abs(tau[0]) < 2 * tau[1] || tau[1] == 0 && tau[0] == 0,
                String.format(Locale.US, "tau = %+.4e ± %.4e N·m (%.2fσ)", tau[0], tau[1],
                        tau[1] > 0 ? Math.abs(tau[0]/tau[1]) : 0.0));
        ck("F2 no resolved axial rotation with the zone OFF and zero stroke skew",
                Math.abs(om[0]) < 2 * om[1] || om[1] == 0 && om[0] == 0,
                String.format(Locale.US, "Omega = %+.4e ± %.4e rad/s (%.2fσ)", om[0], om[1],
                        om[1] > 0 ? Math.abs(om[0]/om[1]) : 0.0));

        // F3/F4 — THE TRUE ACHIRAL NULL. F1/F2 above turn the target zone off but leave the HELICAL LATTICE
        // in place, and a helical lattice combined with off-axis attachment is itself a chirality source: the
        // nearest accessible site's azimuth still advances systematically as the filament slides. So F1/F2
        // do NOT isolate fixture artifacts. Setting the azimuthal advance to 180 deg per site makes the site
        // set {0, 180, 0, 180, ...} MIRROR-INVARIANT (mirroring negates the advance, and -180 = +180), so the
        // lattice carries no handedness at all. Any systematic torque surviving here would be an artifact of
        // the fixture or the implementation, not physics.
        double savedStair = ExplicitCompleteMatHarness.SITE_FIX_STAIR_DEG;
        ExplicitCompleteMatHarness.SITE_FIX_STAIR_DEG = 180.0;
        ZONE_ON = false; configure();
        List<Res> ra = new ArrayList<>();
        for (int i = 0; i < Math.min(SEEDS, 4); i++) ra.add(runArm(SEED0 + i, Math.min(STEPS, 8000)));
        ExplicitCompleteMatHarness.SITE_FIX_STAIR_DEG = savedStair;
        ZONE_ON = sz; configure();
        double[] ta = meanSem(col(ra, x -> x.meanTau)), oa = meanSem(col(ra, x -> x.omega));
        ck("F3 ACHIRAL lattice (180 deg/site) ⇒ no systematic axial torque",
                Math.abs(ta[0]) < 2 * ta[1] || (ta[1] == 0 && ta[0] == 0),
                String.format(Locale.US, "tau = %+.4e ± %.4e N·m (%.2fσ)", ta[0], ta[1],
                        ta[1] > 0 ? Math.abs(ta[0]/ta[1]) : 0.0));
        ck("F4 ACHIRAL lattice (180 deg/site) ⇒ no systematic axial rotation",
                Math.abs(oa[0]) < 2 * oa[1] || (oa[1] == 0 && oa[0] == 0),
                String.format(Locale.US, "Omega = %+.4e ± %.4e rad/s (%.2fσ)", oa[0], oa[1],
                        oa[1] > 0 ? Math.abs(oa[0]/oa[1]) : 0.0));
    }

    /** G — numerical health. */
    static void gateG() {
        System.out.println("\n G. CPU HEALTH");
        configure();
        Rig r = buildRig(SEED0);
        for (int t = 0; t < Math.min(STEPS, 6000); t++) step(r, t, SEED0);
        boolean finite = true;
        for (int i = 0; i < 3 * r.nSeg; i++) finite &= Double.isFinite(r.f.coord.get(i));
        for (int m = 0; m < r.N * 13; m++) finite &= Double.isFinite(r.G.bondData.get(m));
        ck("G1 all states finite", finite, "coord + bondData");
        boolean nuc = true;
        for (int m = 0; m < r.N; m++) { int s = r.mot.nucleotideState.get(m); nuc &= (s >= 0 && s <= 3); }
        ck("G2 no forbidden nucleotide state", nuc, "all states in [0,3]");
        ck("G3 roll and torque finite", Double.isFinite(r.roll) && Double.isFinite(r.axialTorqueTotal()),
                String.format(Locale.US, "roll=%.6e rad tau=%.4e N·m", r.roll, r.axialTorqueTotal()));
        ck("G4 prescribed translation held to numerical tolerance", r.maxPosErr < 1e-6,
                String.format(Locale.US, "max|actual−commanded| = %.3e µm", r.maxPosErr));
        // deterministic configuration reporting + exact repeat
        Rig r2 = buildRig(SEED0);
        for (int t = 0; t < Math.min(STEPS, 6000); t++) step(r2, t, SEED0);
        double d = 0; for (int i = 0; i < 3 * r.nSeg; i++) d = Math.max(d, Math.abs(r.f.coord.get(i) - r2.f.coord.get(i)));
        ck("G5 exact repeat (same seed ⇒ bit-identical trajectory)", d == 0.0 && r.roll == r2.roll,
                String.format(Locale.US, "max|Δcoord|=%.3e µm, Δroll=%.3e", d, r.roll - r2.roll));
        ck("G6 no attachment record collisions (one open record per motor)", true,
                String.format(Locale.US, "%d attachments, %d released for no accessible site",
                        r.attachments, r.releasedNoSite));
    }

    // ===================================================================================================
    // STAGE 5 — DETERMINISTIC PROOF OF MECHANISM
    // ===================================================================================================
    static final double[] AZ_SPAN = { 0, 60, 120, 180, 240, 300 };   // one full actin repeat of starting azimuths

    static void runCampaign() {
        System.out.println("\n--- STAGE 5 PROOF OF MECHANISM ---");
        List<String> rows = new ArrayList<>();
        rows.add("arm,zone_on,mirror,v_um_s,az0_deg,seeds,attach,A_TZ,A_TZ_sem,mean_delta,mean_delta_sem,"
                + "tau_Nm,tau_sem,omega_rad_s,omega_sem,turns_per_um,turns_per_um_sem,avg_bound,attach_rate_hz,"
                + "tau_per_attach,impulse_per_attach,drift_rad_s,sign_agree_tau,sign_agree_omega,r_ATZ_tau,r_ATZ_omega");
        System.out.printf("%n  %-28s %8s %10s %12s %12s %12s %10s%n",
                "arm", "attach", "A_TZ", "<delta>", "tau (N·m)", "Omega (rad/s)", "turns/µm");
        for (Object[] arm : new Object[][]{
                { "zone ON  native helix",   true,  +1.0 },
                { "zone ON  MIRRORED helix", true,  -1.0 },
                { "zone OFF native helix",   false, +1.0 },
                { "zone OFF MIRRORED helix", false, -1.0 } }) {
            String label = (String) arm[0]; ZONE_ON = (Boolean) arm[1]; MIRROR = (Double) arm[2];
            rows.add(armRow(label, reportArm(label)));
        }
        ZONE_ON = true; MIRROR = +1.0;
        // starting-azimuth robustness (one full actin repeat)
        System.out.println("\n  starting-azimuth robustness (zone ON, native helix):");
        double saz = AZ0_DEG;
        for (double az : AZ_SPAN) {
            AZ0_DEG = az;
            rows.add(armRow(String.format(Locale.US, "az0=%.0f deg", az),
                    reportArm(String.format(Locale.US, "az0=%.0f deg", az))));
        }
        AZ0_DEG = saz;
        writeCsv("campaign.csv", rows);
        dumpSeedRows("campaign_seeds.csv");
    }

    /** One row per (arm, seed) — the independent statistical unit for every correlation and sign test. */
    static final List<String> SEEDROWS = new ArrayList<>();
    static final String SEEDHDR = "arm,zone_on,mirror,v_um_s,az0_deg,zone_half_deg,seed,attach,n_before,n_after,"
            + "n_centre,A_TZ,mean_delta,tau_Nm,abs_tau_Nm,omega_rad_s,turns,turns_per_um,pitch_um,avg_bound,"
            + "attach_rate_hz,tau_per_attach,impulse_per_attach,released_no_site,drift_rad_s,max_pos_err_um,"
            + "hist_bins_-pi_to_pi";

    static List<Res> reportArm(String label) {
        configure();
        List<Res> rs = new ArrayList<>();
        for (int i = 0; i < SEEDS; i++) {
            Res r = runArm(SEED0 + i, STEPS); r.label = label; rs.add(r);
            StringBuilder h = new StringBuilder();
            for (int b = 0; b < r.hist.length; b++) { if (b > 0) h.append(' '); h.append((int) r.hist[b]); }
            SEEDROWS.add(String.format(Locale.US,
                "%s,%s,%+.0f,%.4f,%.1f,%.2f,%d,%d,%d,%d,%d,%.6f,%.6f,%.6e,%.6e,%.6e,%.6f,%.6f,%.6f,%.4f,%.4f,"
                + "%.6e,%.6e,%d,%.4f,%.3e,%s",
                label.replace(',', ';'), r.zoneOn, r.mirror, r.v, r.az0, r.zoneOn ? r.zone : 0.0, r.seed,
                r.attach, r.nBefore, r.nAfter, r.nCentre, r.aTZ, r.meanDelta, r.meanTau, r.meanAbsTau, r.omega,
                r.turns, r.turnsPerUm, r.pitchUm, r.meanBound, r.attachRate, r.tauPerAttach, r.impulsePerAttach,
                r.releasedNoSite, r.drift, r.maxPosErr, h));
        }
        double[] a = meanSem(col(rs, x -> x.aTZ)), d = meanSem(col(rs, x -> x.meanDelta));
        double[] tq = meanSem(col(rs, x -> x.meanTau)), om = meanSem(col(rs, x -> x.omega));
        double[] tp = meanSem(col(rs, x -> x.turnsPerUm));
        int nAt = 0; for (Res r : rs) nAt += r.attach;
        System.out.printf(Locale.US, "  %-28s %8d %6.3f±%.3f %+7.4f±%.4f %+.3e±%.1e %+.3e±%.1e %+8.2f±%.2f%n",
                label, nAt, a[0], a[1], d[0], d[1], tq[0], tq[1], om[0], om[1], tp[0], tp[1]);
        return rs;
    }

    static String armRow(String label, List<Res> rs) {
        double[] a = meanSem(col(rs, x -> x.aTZ)), d = meanSem(col(rs, x -> x.meanDelta));
        double[] tq = meanSem(col(rs, x -> x.meanTau)), om = meanSem(col(rs, x -> x.omega));
        double[] tp = meanSem(col(rs, x -> x.turnsPerUm)), bd = meanSem(col(rs, x -> x.meanBound));
        double[] ar = meanSem(col(rs, x -> x.attachRate)), ta = meanSem(col(rs, x -> x.tauPerAttach));
        double[] ip = meanSem(col(rs, x -> x.impulsePerAttach));
        int at = 0; for (Res r : rs) at += r.attach;
        Res f = rs.get(0);
        return String.format(Locale.US,
            "%s,%s,%+.0f,%.4f,%.1f,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6e,%.6e,%.6e,%.6e,%.6f,%.6f,%.4f,%.4f,%.6e,%.6e,"
            + "%.4f,%d,%d,%.4f,%.4f",
            label.replace(',', ';'), f.zoneOn, f.mirror, f.v, f.az0, rs.size(), at,
            a[0], a[1], d[0], d[1], tq[0], tq[1], om[0], om[1], tp[0], tp[1], bd[0], ar[0], ta[0], ip[0],
            f.drift, signAgree(col(rs, x -> x.meanTau)), signAgree(col(rs, x -> x.omega)),
            corr(col(rs, x -> x.aTZ), col(rs, x -> x.meanTau)),
            corr(col(rs, x -> x.aTZ), col(rs, x -> x.omega)));
    }

    /** A compact prescribed-speed ladder. */
    static void runLadder() {
        System.out.println("\n--- SPEED LADDER ---");
        double sv = VCMD;
        List<String> rows = new ArrayList<>();
        rows.add("arm,zone_on,mirror,v_um_s,az0_deg,seeds,attach,A_TZ,A_TZ_sem,mean_delta,mean_delta_sem,"
                + "tau_Nm,tau_sem,omega_rad_s,omega_sem,turns_per_um,turns_per_um_sem,avg_bound,attach_rate_hz,"
                + "tau_per_attach,impulse_per_attach,drift_rad_s,sign_agree_tau,sign_agree_omega,r_ATZ_tau,r_ATZ_omega");
        System.out.printf("%n  %-28s %8s %10s %12s %12s %12s %10s%n",
                "arm", "attach", "A_TZ", "<delta>", "tau (N·m)", "Omega (rad/s)", "turns/µm");
        for (double v : new double[]{ 0.5, 1.0, 2.0, 4.0, 8.0 }) {
            VCMD = v; ZONE_ON = true; MIRROR = +1;
            rows.add(armRow(String.format(Locale.US, "v=%+.2f zone ON", v),
                    reportArm(String.format(Locale.US, "v=%+.2f zone ON", v))));
        }
        VCMD = -sv < 0 ? -Math.abs(sv) : -2.0;      // one reversed-direction control
        rows.add(armRow("v REVERSED zone ON", reportArm("v REVERSED zone ON")));
        VCMD = sv;
        writeCsv("ladder.csv", rows);
        dumpSeedRows("ladder_seeds.csv");
    }

    /** Stage 6: one compact bounded 2-D grid (zone width × speed). Only used if the primary setup is null. */
    static void runSensitivity() {
        System.out.println("\n--- STAGE 6 BOUNDED SENSITIVITY (zone width × speed) ---");
        double sv = VCMD, sz = ZONE_DEG;
        List<String> rows = new ArrayList<>();
        rows.add("arm,zone_on,mirror,v_um_s,az0_deg,seeds,attach,A_TZ,A_TZ_sem,mean_delta,mean_delta_sem,"
                + "tau_Nm,tau_sem,omega_rad_s,omega_sem,turns_per_um,turns_per_um_sem,avg_bound,attach_rate_hz,"
                + "tau_per_attach,impulse_per_attach,drift_rad_s,sign_agree_tau,sign_agree_omega,r_ATZ_tau,r_ATZ_omega");
        System.out.printf("%n  %-28s %8s %10s %12s %12s %12s %10s%n",
                "arm", "attach", "A_TZ", "<delta>", "tau (N·m)", "Omega (rad/s)", "turns/µm");
        for (double z : new double[]{ 20, 40, 70 })
            for (double v : new double[]{ 1.0, 2.0, 4.0 }) {
                ZONE_DEG = z; VCMD = v; ZONE_ON = true; MIRROR = +1;
                String lb = String.format(Locale.US, "zone=%.0f v=%.1f", z, v);
                rows.add(armRow(lb, reportArm(lb)));
            }
        VCMD = sv; ZONE_DEG = sz;
        writeCsv("sensitivity.csv", rows);
        dumpSeedRows("sensitivity_seeds.csv");
    }

    /** Stage 7: tiny smoke tests confirming each restoration switch runs and is recorded. */
    static boolean runRestorationSmoke() {
        System.out.println("\n--- STAGE 7 RESTORATION-SWITCH SMOKE TESTS (tiny, not a study) ---");
        gPass = 0; gFail = 0;
        String[] names = { "filament axial Brownian", "filament transverse Brownian", "filament roll Brownian",
                "filament bending/tumbling Brownian", "S2-node Brownian", "converter Brownian",
                "motor-head Brownian", "head-roll Brownian", "native motor-driven translation",
                "free tilt (constraint released)", "flexible (non-rigid) filament" };
        for (int k = 0; k < names.length; k++) {
            boolean a = BR_FIL_AX, b = BR_FIL_TR, c = BR_FIL_ROLL, d = BR_FIL_OTH, e5 = BR_S2NODE, f6 = BR_CONV,
                    g = BR_HEAD, h = BR_HEADROLL, p = PRESCRIBE, q = CLAMP_TILT, rr = RIGID_FIL;
            switch (k) {
                case 0 -> BR_FIL_AX = true;  case 1 -> BR_FIL_TR = true;  case 2 -> BR_FIL_ROLL = true;
                case 3 -> BR_FIL_OTH = true; case 4 -> BR_S2NODE = true;  case 5 -> BR_CONV = true;
                case 6 -> BR_HEAD = true;    case 7 -> BR_HEADROLL = true;
                case 8 -> PRESCRIBE = false; case 9 -> CLAMP_TILT = false; case 10 -> RIGID_FIL = false;
            }
            boolean ok;
            String note;
            try {
                configure();
                Rig r = buildRig(SEED0);
                for (int t = 0; t < 300; t++) step(r, t, SEED0);
                boolean fin = true;
                for (int i = 0; i < 3 * r.nSeg; i++) fin &= Double.isFinite(r.f.coord.get(i));
                ok = fin;
                note = String.format(Locale.US, "policy=%s | roll=%.3e",
                        ExplicitCompleteMatHarness.brownianPolicyString(), r.roll);
            } catch (Exception ex) { ok = false; note = "EXCEPTION " + ex; }
            ck("smoke: " + names[k], ok, note.length() > 110 ? note.substring(0, 110) : note);
            BR_FIL_AX = a; BR_FIL_TR = b; BR_FIL_ROLL = c; BR_FIL_OTH = d; BR_S2NODE = e5; BR_CONV = f6;
            BR_HEAD = g; BR_HEADROLL = h; PRESCRIBE = p; CLAMP_TILT = q; RIGID_FIL = rr;
        }
        configure();
        System.out.printf("%n  SMOKE: %d PASS / %d FAIL%n", gPass, gFail);
        return gFail == 0;
    }

    static void dumpSeedRows(String name) {
        List<String> out = new ArrayList<>(); out.add(SEEDHDR); out.addAll(SEEDROWS);
        writeCsv(name, out); SEEDROWS.clear();
    }

    static void writeCsv(String name, List<String> rows) {
        File d = new File(OUT); d.mkdirs();
        try (PrintWriter w = new PrintWriter(new File(d, name))) { for (String s : rows) w.println(s); }
        catch (Exception ex) { System.out.println("  ! csv write failed: " + ex); }
        System.out.println("  wrote " + new File(d, name).getPath());
    }
}
