package softbox;

import softbox.TwoBodyConverterMotor.Glide2D;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import java.util.Locale;

/**
 * DISCRETE ACTIN SITES + HEAD ROTATIONAL DOF + BOUND REGISTRY + ASKEW BIND/STROKE — driver and gates.
 *
 * <p>Noncanonical, flag-gated, DEFAULT-OFF. Drives the validated explicit-s2-l40 single-head gliding pipeline
 * ({@link ExplicitCompleteMatHarness}) with the {@link ChiralSiteSystem} features switched on INDEPENDENTLY.
 * Report: {@code docs/DISCRETE_ACTIN_SITE_CHIRAL_BINDING_AND_STROKE_FINDINGS.md}.
 *
 * <p>Modes: {@code -fixtures} (deterministic Stage-1/2/3 gates) | {@code -equiv} (full-graph CPU/GPU,
 * device-resident) | {@code -campaign} (the arm table) | {@code -lattice} (lattice comparison) |
 * {@code -3js <dir>} | {@code -all}.
 */
public final class ChiralSiteHarness {

    static final double DT = ExplicitCompleteMatHarness.DT;   // 2.5e-6
    static double DENSITY = 400.0;
    static int    SEED = 101, STEPS = 4000, SEEDS = 4, STRIDE = 40;
    static double R_NM = 3.5;
    static double REG_K = 2.0e-21;        // N·m/rad — a transparent diagnostic value ≈ kT/rad² scale (kT=4.1e-21 J)
    static double EPS_PILOT_DEG = 2.0;
    static double MECH_MIRROR = 1.0;   // -mech-mirror ⇒ −1: run the frozen probe on a MIRRORED actin lattice
    static boolean GPU = false;      // -gpu ⇒ campaign arms run on the device-resident buildGlidingGraph(prod) plan

    // ---------------------------------------------------------------------------------------------------------
    // SINGLE-SEGMENT, FILAMENT-BROWNIAN-OFF DYNAMIC TWIRLING ASSAY (noncanonical, default-off).
    // Removes the two confounds the multisegment dynamic arms could not beat — thermal filament roll and
    // segment-level rotational incoherence — WITHOUT touching motor search, chemistry or stroke mechanics.
    //   FIL_SEGS = 1  ⇒ ONE rigid mechanical segment of the SAME total contour (buildS2Mat rigid branch): no
    //                   bending DOF, no joints, no intersegment torsion, no roll spring ⇒ all roll is rigid-body.
    //   FIL_BROWN=false ⇒ all FOUR filament Brownian channels masked (axial + transverse force, roll + bend
    //                   torque) via the existing BrownianForceSystem.brownChannelMask. Motor/S2 Brownian and the
    //                   head-roll Brownian stream stay ON — no state-dependent quieting of bound motors.
    // ---------------------------------------------------------------------------------------------------------
    static int     FIL_SEGS  = TwoBodyConverterMotor.G4_NSEG;   // -filament-segments <n> (1 ⇒ rigid single rod)
    static boolean FIL_BROWN = true;                            // -filament-brownian on|off
    static double  DTR       = DT;                              // -halfdt ⇒ DT/2 (timestep refinement check)
    static double  EQUIL_FRAC = 0.25;                           // -equil-frac: startup transient discarded
    static int     NBLK      = 5;                               // measurement blocks for the block-SEM
    static int     NTRACE    = 60;                              // stationarity trace samples in the measure window
    static double  EPS_TWIRL_DEG = 5.0;                         // -twirl-skew-deg (primary assay angle)

    static int passN, failN;
    static void ck(int id, String name, boolean p) {
        System.out.printf("  [%2d] %-64s %s%n", id, name, p ? "PASS" : "*** FAIL ***"); if (p) passN++; else failN++; }
    static void note(String s) { System.out.println("       " + s); }

    public static void main(String[] args) {
        TornadoCrashDiagnostic.init("chiral-actin-sites", args);
        boolean fixtures = false, equiv = false, campaign = false, lattice = false, mechanism = false, all = false; String jsDir = null;
        boolean twirl = false, twirlAudit = false, twirlEquiv = false, twirlPilot = false, dtCheck = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-fixtures" -> fixtures = true;
                case "-equiv" -> equiv = true;
                case "-twirl" -> twirl = true;
                case "-twirl-audit" -> twirlAudit = true;
                case "-twirl-equiv" -> twirlEquiv = true;
                case "-twirl-pilot" -> twirlPilot = true;
                case "-twirl-dt" -> dtCheck = true;
                case "-filament-segments" -> FIL_SEGS = Integer.parseInt(args[++i]);
                case "-filament-brownian" -> FIL_BROWN = args[++i].equals("on");
                case "-halfdt" -> DTR = DT / 2.0;
                case "-equil-frac" -> EQUIL_FRAC = Double.parseDouble(args[++i]);
                case "-blocks" -> NBLK = Integer.parseInt(args[++i]);
                case "-twirl-skew-deg" -> EPS_TWIRL_DEG = Double.parseDouble(args[++i]);
                case "-campaign" -> campaign = true;
                case "-lattice" -> lattice = true;
                case "-mechanism" -> mechanism = true;
                case "-all" -> all = true;
                case "-3js" -> jsDir = args[++i];
                case "-density" -> DENSITY = Double.parseDouble(args[++i]);
                case "-seed" -> SEED = Integer.parseInt(args[++i]);
                case "-seeds" -> SEEDS = Integer.parseInt(args[++i]);
                case "-steps" -> STEPS = Integer.parseInt(args[++i]);
                case "-stride" -> STRIDE = Integer.parseInt(args[++i]);
                case "-actin-bind-radius-nm" -> R_NM = Double.parseDouble(args[++i]);
                case "-bound-registry-k" -> REG_K = Double.parseDouble(args[++i]);
                case "-binding-skew-deg" -> EPS_PILOT_DEG = Double.parseDouble(args[++i]);
                case "-discrete-actin-sites" -> ExplicitCompleteMatHarness.SITE_MODE = latticeCode(args[++i]);
                case "-randomize-motor-base-azimuth" -> ExplicitCompleteMatHarness.RAND_BASE_AZ = args[++i].equals("on");
                case "-gpu" -> GPU = true;
                case "-mech-mirror" -> MECH_MIRROR = -1.0;
                default -> { }
            }
        }
        System.out.println("######## Discrete actin sites + head roll DOF + chiral bind/stroke (noncanonical, default-off) ########");
        System.out.printf(Locale.US, "dt=%.2e  density=%.0f heads/µm²  seed=%d  seeds=%d  steps=%d  Ractin=%.2f nm  registryK=%.2e N·m/rad%n",
                DT, DENSITY, SEED, SEEDS, STEPS, R_NM, REG_K);
        TornadoCrashDiagnostic.simDt(DT);
        TornadoCrashDiagnostic.context("seed", SEED);
        TornadoCrashDiagnostic.context("density", DENSITY);
        TornadoCrashDiagnostic.context("steps", STEPS);

        boolean ok = true;
        boolean twirlMode = twirl || twirlAudit || twirlEquiv || twirlPilot || dtCheck;
        if (twirlMode) printTwirlConfigBlock();
        if (jsDir != null) {
            if (twirlMode) makeTwirlMovies(jsDir); else makeMovies(jsDir);
            TornadoCrashDiagnostic.normalMainReturn("mode=3js"); return; }
        if (twirlAudit)      ok = runTwirlAudit();
        else if (twirlEquiv) ok = runTwirlEquiv();
        else if (twirlPilot) runTwirlPilot();
        else if (dtCheck)    runTwirlDtCheck();
        else if (twirl)      runTwirlCampaign();
        else if (all) { ok &= runFixtures(); ok &= runEquiv(); runMechanismProbe(); runCampaign(); runLattice(); }
        else if (fixtures) ok = runFixtures();
        else if (equiv) ok = runEquiv();
        else if (campaign) runCampaign();
        else if (lattice) runLattice();
        else if (mechanism) runMechanismProbe();
        else ok = runFixtures();
        System.out.println("====================================================================================================");
        if (fixtures || equiv || all || twirlAudit || twirlEquiv)
            System.out.println(ok ? "ALL GATED CHECKS PASS" : "*** SOME CHECKS FAILED ***");
        TornadoCrashDiagnostic.normalMainReturn("ok=" + ok);
        if (!ok) System.exit(1);
    }

    static int latticeCode(String s) {
        return switch (s) { case "native" -> 1; case "every3" -> 2; case "every4" -> 3;
                            case "stair9-45" -> 4; case "stair9-90" -> 5; default -> 0; }; }

    // =============================================================================== configuration helper
    /** Set the full independent feature configuration for one arm; everything not named is reset to default-off. */
    static void cfg(int siteMode, boolean headRoll, double regK, double epsBindDeg, double epsStrokeDeg,
                    boolean randBase, double mirror, boolean headBrown) {
        ExplicitCompleteMatHarness.resetChiral();
        ExplicitCompleteMatHarness.SITE_MODE = siteMode;
        ExplicitCompleteMatHarness.HEAD_ROLL = headRoll;
        ExplicitCompleteMatHarness.HEAD_ROLL_BROWN = headBrown;
        ExplicitCompleteMatHarness.REG_K = regK;
        ExplicitCompleteMatHarness.EPS_BIND_DEG = epsBindDeg;
        ExplicitCompleteMatHarness.EPS_STROKE_DEG = epsStrokeDeg;
        ExplicitCompleteMatHarness.RAND_BASE_AZ = randBase;
        ExplicitCompleteMatHarness.MIRROR_SIGN = mirror;
        // the off-axis actin SURFACE bond is what makes an azimuth mechanically meaningful; sites imply it.
        boolean surf = siteMode > 0;
        ExplicitCompleteMatHarness.SURFACE_ON = surf;
        ExplicitCompleteMatHarness.R_ACTIN_NM = R_NM;
        ExplicitCompleteMatHarness.SURF_STERIC = false;      // site exclusivity replaces the continuous steric
        ExplicitCompleteMatHarness.SURF_EXCL_NM = 0.0;
        ExplicitCompleteMatHarness.TZ_ON = false;            // target-zone hazard OFF throughout (task requirement)
        ExplicitCompleteMatHarness.TZ_ALPHA = 0.0;
        // the campaign observables (filament pose + bondData + bindAzim) must cross back EVERY step on the device
        // path; telemetryOn() is what gates that copy-out set, and it must be on even for the no-feature control.
        ExplicitCompleteMatHarness.TELEMETRY = GPU;
        // FILAMENT Brownian channels only (all four together). Motor/S2 Brownian stays ON in EVERY arm — no
        // binding-state quieting is ever requested here (motorBrownPolicy() stays 0 ⇒ matS2SolveStep bit-identical).
        ExplicitCompleteMatHarness.setBrownianPolicy(FIL_BROWN, FIL_BROWN, FIL_BROWN, FIL_BROWN, true, true);
    }
    static void cfgOff() { cfg(0, false, 0, 0, 0, false, 1.0, true); ExplicitCompleteMatHarness.SURFACE_ON = false;
        ExplicitCompleteMatHarness.resetBrownianPolicy(); }

    static Glide2D build(int seed) {
        // FIL_SEGS == 1 ⇒ the rigid single-rod branch (SAME total contour; drag from the full length — see the
        // buildS2Mat(…, rigidFil) javadoc). Otherwise the canonical chain, with G4_NSEG_RUN honouring FIL_SEGS.
        boolean rigid = FIL_SEGS == 1;
        int saved = TwoBodyConverterMotor.G4_NSEG_RUN;
        if (!rigid) TwoBodyConverterMotor.G4_NSEG_RUN = FIL_SEGS;
        try {
            return TwoBodyConverterMotor.buildS2Mat(DENSITY, DTR, 40.0,
                    TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, seed, rigid);
        } finally { TwoBodyConverterMotor.G4_NSEG_RUN = saved; }
    }

    // =============================================================================== deterministic fixtures
    static boolean runFixtures() {
        passN = failN = 0;
        System.out.println("\n--- STAGE 0/1 — HEAD ROTATIONAL DOF ---");
        stage1Fixtures();
        System.out.println("\n--- STAGE 2 — DISCRETE PERSISTENT ACTIN SITES ---");
        stage2Fixtures();
        System.out.println("\n--- STAGE 3 — BOUND ORIENTATIONAL REGISTRY ---");
        stage3Fixtures();
        System.out.println("\n--- STAGE 4/5 — ASKEW BIND / ASKEW STROKE (mechanical accounting) ---");
        stage45Fixtures();
        System.out.printf("%nFixtures: %d PASS, %d FAIL%n", passN, failN);
        return failN == 0;
    }

    /** A minimal deterministic rig: one scene, all motors free except a chosen pre-bound one. */
    static final class Rig {
        Glide2D G; ExplicitCompleteMatHarness.ExMat e; FilamentStore f; MotorStore mot; int N, nSeg;
        Rig(int seed) { G = build(seed); f = G.fil; mot = G.mot; N = G.N; nSeg = G.nSeg;
                        e = ExplicitCompleteMatHarness.packExMat(G, 0); }
        /** advance the shared pipeline (Brownian off unless packed with 1). */
        void step(int t, int seed) { ExplicitCompleteMatHarness.stepGlidingCPU(e, t, seed); }
        /** run headRollStep alone (no other kernel) — the isolated Stage-1/3 probe. */
        void roll(int t, int seed) {
            e.matc.set(0, t); e.matc.set(1, seed);
            ChiralSiteSystem.headRollStep(mot.boundSeg, e.outGeom, f.uVec, f.yVec, mot.body.bRotGam, mot.bindAzim,
                    e.headRef, e.headOmega, e.headTau, e.headMis, G.bondData, e.chiP, e.matc, e.exCounts);
        }
        void geom() { TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom); }
        double[] eBind(int m) {
            double hx = e.outGeom.get(3*N+m), hy = e.outGeom.get(4*N+m), hz = e.outGeom.get(5*N+m);
            double dx = e.outGeom.get(6*N+m)-hx, dy = e.outGeom.get(7*N+m)-hy, dz = e.outGeom.get(8*N+m)-hz;
            double L = Math.sqrt(dx*dx+dy*dy+dz*dz); return new double[]{ dx/L, dy/L, dz/L };
        }
        double[] ref(int m) { return new double[]{ e.headRef.get(m), e.headRef.get(N+m), e.headRef.get(2*N+m) }; }
        void setRef(int m, double[] v) { double l = Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);
            e.headRef.set(m, (float)(v[0]/l)); e.headRef.set(N+m, (float)(v[1]/l)); e.headRef.set(2*N+m, (float)(v[2]/l)); }
        /** distance (µm) from motor m's F8 anchor to the clamped closest point of segment s, and that arc. */
        double[] footOf(int m, int s) {
            double fx = e.outGeom.get(6*N+m), fy = e.outGeom.get(7*N+m), fz = e.outGeom.get(8*N+m);
            double half = 0.5*f.segLength.get(s);
            double cx = f.coord.get(s), cy = f.coord.get(nSeg+s), cz = f.coord.get(2*nSeg+s);
            double ux = f.uVec.get(s), uy = f.uVec.get(nSeg+s), uz = f.uVec.get(2*nSeg+s);
            double dx = fx-cx, dy = fy-cy, dz = fz-cz;
            double foot = dx*ux+dy*uy+dz*uz;
            double fc = foot < -half ? -half : (foot > half ? half : foot);
            double qx = dx-fc*ux, qy = dy-fc*uy, qz = dz-fc*uz;
            return new double[]{ Math.sqrt(qx*qx+qy*qy+qz*qz), fc+half };
        }
        /** the (motor, segment) pair whose head sits closest to the filament — the only physically bindable rig state. */
        int[] closestPair() {
            geom(); int bm = 0, bs = 0; double bd = 1e9;
            for (int m = 0; m < N; m++) for (int s = 0; s < nSeg; s++) {
                double d = footOf(m, s)[0]; if (d < bd) { bd = d; bm = m; bs = s; } }
            return new int[]{ bm, bs };
        }
        /** bind motor m to the segment nearest its head at its own perpendicular foot, then snap it to a site. */
        int bindTo(int m, double arcFracIgnored) {
            geom();
            int s = 0; double bd = 1e9, arc = 0;
            for (int k = 0; k < nSeg; k++) { double[] fo = footOf(m, k); if (fo[0] < bd) { bd = fo[0]; s = k; arc = fo[1]; } }
            mot.boundSeg.set(m, s);
            mot.bindArc.set(m, (float) arc);
            e.prevBound.set(m, -1);
            ChiralSiteSystem.siteSnap(mot.boundSeg, e.prevBound, e.justBound, e.outGeom, f.coord, f.uVec, f.yVec,
                    f.segLength, e.segCumArc, mot.bindArc, mot.bindAzim, e.bindSite, e.chiP, e.exCounts);
            return mot.boundSeg.get(m);
        }
        /** the local site frame (u, n, t) of motor m's bound site. */
        double[][] siteFrame(int m) {
            int s = mot.boundSeg.get(m);
            double ux=f.uVec.get(s), uy=f.uVec.get(nSeg+s), uz=f.uVec.get(2*nSeg+s);
            double yx=f.yVec.get(s), yy=f.yVec.get(nSeg+s), yz=f.yVec.get(2*nSeg+s);
            double zx=uy*yz-uz*yy, zy=uz*yx-ux*yz, zz=ux*yy-uy*yx;
            double zl=Math.sqrt(zx*zx+zy*zy+zz*zz); zx/=zl; zy/=zl; zz/=zl;
            double mirror = e.chiP.get(13), eps = e.chiP.get(5);
            double ph = mot.bindAzim.get(m) - mirror*eps;
            double c=Math.cos(ph), sn=Math.sin(ph);
            double nx=c*yx+sn*zx, ny=c*yy+sn*zy, nz=c*yz+sn*zz;
            double tx=mirror*(uy*nz-uz*ny), ty=mirror*(uz*nx-ux*nz), tz=mirror*(ux*ny-uy*nx);
            return new double[][]{ {ux,uy,uz}, {nx,ny,nz}, {tx,ty,tz} };
        }
    }
    static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static double[] cross(double[] a, double[] b) { return new double[]{ a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0] }; }
    static double norm(double[] a) { return Math.sqrt(dot(a,a)); }

    // ---------------------------------------------------------------------------- Stage 1
    static void stage1Fixtures() {
        // (1) zero generalized torque + Brownian OFF ⇒ the coordinate does not move.
        cfg(2, true, 0.0, 0, 0, false, 1.0, false);           // sites on (frame source), registry K=0, no Brownian
        Rig r = new Rig(101); r.geom();
        int m0 = 0; r.setRef(m0, perpOf(r.eBind(m0)));
        double[] before = r.ref(m0); double om0 = r.e.headOmega.get(m0);
        for (int t = 0; t < 50; t++) r.roll(t, 101);
        double[] after = r.ref(m0);
        double drift = Math.abs(norm(new double[]{after[0]-before[0], after[1]-before[1], after[2]-before[2]}));
        ck(1, "zero torque + Brownian off ⇒ coordinate unchanged", drift < 1e-6 && Math.abs(r.e.headOmega.get(m0)-om0) < 1e-9);

        // (2) positive and negative generalized torque rotate in OPPOSITE directions.
        double[] d = twoSidedTorqueProbe();
        note(String.format(Locale.US, "imposed mismatch ±0.30 rad ⇒ dOmega = %+.3e / %+.3e rad", d[0], d[1]));
        ck(2, "±generalized torque ⇒ opposite rotation sense", d[0]*d[1] < 0 && Math.abs(d[0]) > 1e-12
                && Math.abs(Math.abs(d[0]) - Math.abs(d[1])) < 1e-3*Math.abs(d[0]));

        // (3) unbound Brownian: zero mean, variance = 2 kT dt / gamma per step.
        cfg(0, true, 0.0, 0, 0, false, 1.0, true);
        Rig rb = new Rig(202); rb.geom();
        for (int m = 0; m < rb.N; m++) { rb.mot.boundSeg.set(m, -1); rb.setRef(m, perpOf(rb.eBind(m))); }
        int K = 400; double sum = 0, sum2 = 0; long n = 0;
        double gam = rb.mot.body.bRotGam.get(2);   // head sub-body (3*0+2) roll drag
        for (int t = 0; t < K; t++) {
            double[] prev = new double[rb.N]; for (int m = 0; m < rb.N; m++) prev[m] = rb.e.headOmega.get(m);
            rb.roll(t, 202);
            for (int m = 0; m < rb.N; m++) { double dOm = rb.e.headOmega.get(m) - prev[m]; sum += dOm; sum2 += dOm*dOm; n++; }
        }
        double mean = sum/n, var = sum2/n - mean*mean, expVar = 2*Constants.kT*DT/gam;
        double sem = Math.sqrt(var/n);
        note(String.format(Locale.US, "gammaOmega=%.3e N·m·s ; ⟨dOmega⟩=%+.3e (SEM %.1e) ; Var=%.4e vs 2kT·dt/gamma=%.4e (ratio %.4f)",
                gam, mean, sem, var, expVar, var/expVar));
        ck(3, "unbound Brownian: zero mean and FDT variance 2 kT dt / gammaOmega",
                Math.abs(mean) < 4*sem && Math.abs(var/expVar - 1.0) < 0.10);

        // (4) Brownian channel OFF ⇒ EXACTLY zero stochastic torque.
        cfg(0, true, 0.0, 0, 0, false, 1.0, false);
        Rig rq = new Rig(202); rq.geom();
        for (int m = 0; m < rq.N; m++) { rq.mot.boundSeg.set(m, -1); rq.setRef(m, perpOf(rq.eBind(m))); }
        for (int t = 0; t < 100; t++) rq.roll(t, 202);
        double maxOm = 0; for (int m = 0; m < rq.N; m++) maxOm = Math.max(maxOm, Math.abs(rq.e.headOmega.get(m)));
        ck(4, "head-roll Brownian OFF ⇒ exactly zero stochastic increment", maxOm == 0.0);

        // (5) rigid scene rotation ⇒ the material frame rotates COVARIANTLY.
        double cov = rigidRotationCovariance();
        note(String.format(Locale.US, "max|R·headRef(scene) − headRef(rotated scene)| = %.3e", cov));
        ck(5, "rigid scene rotation ⇒ head material frame covariant", cov < 2e-5);

        // (7) orthonormality over a long run. NOTE the ordering: headRollStep transports+rotates the coordinate,
        // and the beam solve LATER in the same step moves eBind — so the coordinate is re-orthogonalised at the
        // START of the next kernel call (the standard discrete rotation-minimizing frame). The fixture therefore
        // checks (a) |headRef| = 1 at all times and (b) headRef·eBind = 0 immediately AFTER the transport, and
        // reports the between-step head-axis swing that the transport has to absorb.
        cfg(2, true, REG_K, 0, 0, false, 1.0, true);
        Rig ro = new Rig(303);
        double worstN = 0, worstPost = 0;
        for (int t = 0; t < 600; t++) {
            ro.step(t, 303);
            for (int m = 0; m < ro.N; m++) {
                double[] v = ro.ref(m); if (norm(v) < 1e-9) continue;
                worstN = Math.max(worstN, Math.abs(norm(v) - 1.0));
                worstPost = Math.max(worstPost, Math.abs(dot(v, ro.eBind(m))));
            }
        }
        ro.e.chiP.set(7, 0.0); ro.e.chiP.set(10, 0.0);       // K=0, Brownian off ⇒ the next call is a PURE transport
        ro.roll(600, 303);
        double worstOrth = 0, worstN2 = 0;
        for (int m = 0; m < ro.N; m++) {
            double[] v = ro.ref(m); if (norm(v) < 1e-9) continue;
            worstN2 = Math.max(worstN2, Math.abs(norm(v) - 1.0));
            worstOrth = Math.max(worstOrth, Math.abs(dot(v, ro.eBind(m))));
        }
        note(String.format(Locale.US, "max||headRef|−1| = %.2e (600 steps) / %.2e (post-transport) ; max|headRef·eBind| after transport = %.2e",
                worstN, worstN2, worstOrth));
        note(String.format(Locale.US, "between-step head-axis swing absorbed by the transport: max|headRef·eBind| before transport = %.2e", worstPost));
        ck(7, "head basis stays orthonormal (unit norm always; ⊥ eBind after transport)",
                worstN < 1e-5 && worstN2 < 1e-5 && worstOrth < 1e-6);

        // (8) ZERO-FEATURE identity: all flags off ⇒ bit-identical to the pre-increment canonical trajectory.
        cfgOff();
        double[] hA = trajHash(111, 300);
        cfgOff();
        double[] hB = trajHash(111, 300);
        ExplicitCompleteMatHarness.SITE_MODE = 0; ExplicitCompleteMatHarness.HEAD_ROLL = false;   // explicit re-assert
        double[] hC = trajHash(111, 300);
        ck(8, "zero-feature path bit-identical (all flags OFF ⇒ canonical trajectory)",
                hA[0] == hB[0] && hA[0] == hC[0] && hA[1] == hC[1] && hA[2] == 0);
        note(String.format(Locale.US, "canonical 300-step coord hash = %.17g", hA[0]));
        cfgOff();
    }

    /** Impose ±0.30 rad registry mismatch on an otherwise identical bound head; return the two dOmega. */
    static double[] twoSidedTorqueProbe() {
        double[] out = new double[2];
        for (int i = 0; i < 2; i++) {
            double sgn = i == 0 ? +1 : -1;
            cfg(2, true, REG_K, 0, 0, false, 1.0, false);
            Rig r = new Rig(101); r.geom();
            int m = pickBindable(r); r.bindTo(m, 0.5);
            double[][] fr = r.siteFrame(m); double[] eb = r.eBind(m);
            // preferred direction = uSite projected ⊥ eBind ; set headRef to it rotated by ±0.30 about eBind
            double[] b = orth(fr[0], eb);
            double[] h = rot(b, eb, sgn * 0.30);
            r.setRef(m, h);
            double om0 = r.e.headOmega.get(m);
            r.roll(0, 101);
            out[i] = r.e.headOmega.get(m) - om0;
        }
        return out;
    }

    /** Build the same scene twice, rotate one rigidly by R, and compare the evolved head material frames. */
    static double rigidRotationCovariance() {
        cfg(2, true, REG_K, 0, 0, false, 1.0, false);
        Rig a = new Rig(101); a.geom();
        int m = pickBindable(a); a.bindTo(m, 0.5);
        double[] eb = a.eBind(m); a.setRef(m, rot(orth(a.siteFrame(m)[0], eb), eb, 0.4));
        for (int t = 0; t < 20; t++) a.roll(t, 101);
        double[] refA = a.ref(m);
        // rotated scene: rotate the filament pose, the beam nodes and the base frame by R, then repeat
        double[] axis = nrm(new double[]{ 0.37, -0.61, 0.70 }); double ang = 0.9;
        cfg(2, true, REG_K, 0, 0, false, 1.0, false);
        Rig b = new Rig(101);
        rotateScene(b, axis, ang);
        b.geom();
        b.mot.boundSeg.set(m, a.mot.boundSeg.get(m));
        b.mot.bindArc.set(m, a.mot.bindArc.get(m));
        b.mot.bindAzim.set(m, a.mot.bindAzim.get(m));
        b.e.bindSite.set(m, a.e.bindSite.get(m));
        double[] ebB = b.eBind(m); b.setRef(m, rot(orth(b.siteFrame(m)[0], ebB), ebB, 0.4));
        for (int t = 0; t < 20; t++) b.roll(t, 101);
        double[] refB = b.ref(m);
        double[] expect = rot(refA, axis, ang);
        return norm(new double[]{ refB[0]-expect[0], refB[1]-expect[1], refB[2]-expect[2] });
    }
    /** Rigidly rotate the whole scene (filament pose + frames, beam nodes, base frame) about an axis. */
    static void rotateScene(Rig r, double[] axis, double ang) {
        double c = Math.cos(ang), s = Math.sin(ang);
        int n = r.nSeg;
        for (int i = 0; i < n; i++) {
            rotSoA(r.f.coord, i, n, axis, c, s); rotSoA(r.f.uVec, i, n, axis, c, s);
            rotSoA(r.f.yVec, i, n, axis, c, s);  rotSoA(r.f.zVec, i, n, axis, c, s);
            rotSoA(r.f.end1, i, n, axis, c, s);  rotSoA(r.f.end2, i, n, axis, c, s);
        }
        int N = r.N, M = r.G.g4M;
        for (int m = 0; m < N; m++) {
            for (int j = 0; j <= M; j++) {
                double[] v = ExplicitCompleteMatHarness.rotAbout(r.e.nodes.get((3*j)*N+m), r.e.nodes.get((3*j+1)*N+m), r.e.nodes.get((3*j+2)*N+m), axis[0], axis[1], axis[2], c, s);
                r.e.nodes.set((3*j)*N+m, v[0]); r.e.nodes.set((3*j+1)*N+m, v[1]); r.e.nodes.set((3*j+2)*N+m, v[2]);
            }
            for (int blk = 0; blk <= 12; blk += 3) {
                double[] v = ExplicitCompleteMatHarness.rotAbout(r.e.frame.get(blk*N+m), r.e.frame.get((blk+1)*N+m), r.e.frame.get((blk+2)*N+m), axis[0], axis[1], axis[2], c, s);
                r.e.frame.set(blk*N+m, v[0]); r.e.frame.set((blk+1)*N+m, v[1]); r.e.frame.set((blk+2)*N+m, v[2]);
            }
        }
        double[] up = ExplicitCompleteMatHarness.rotAbout(r.e.eupP.get(0), r.e.eupP.get(1), r.e.eupP.get(2), axis[0], axis[1], axis[2], c, s);
        r.e.eupP.set(0, up[0]); r.e.eupP.set(1, up[1]); r.e.eupP.set(2, up[2]);
    }
    static void rotSoA(FloatArray a, int i, int n, double[] ax, double c, double s) {
        double[] v = ExplicitCompleteMatHarness.rotAbout(a.get(i), a.get(n+i), a.get(2*n+i), ax[0], ax[1], ax[2], c, s);
        a.set(i, (float)v[0]); a.set(n+i, (float)v[1]); a.set(2*n+i, (float)v[2]);
    }

    // ---------------------------------------------------------------------------- Stage 2
    static void stage2Fixtures() {
        // (11) lattice OFF reproduces the continuous surface path exactly (the discrete layer is additive).
        cfg(0, false, 0, 0, 0, false, 1.0, true);
        ExplicitCompleteMatHarness.SURFACE_ON = true; ExplicitCompleteMatHarness.R_ACTIN_NM = R_NM;
        double[] hCont = trajHash(111, 200);
        cfg(0, false, 0, 0, 0, false, 1.0, true);
        ExplicitCompleteMatHarness.SURFACE_ON = true; ExplicitCompleteMatHarness.R_ACTIN_NM = R_NM;
        double[] hCont2 = trajHash(111, 200);
        ck(11, "site lattice OFF ⇒ prior continuous surface path reproduced bit-identically", hCont[0] == hCont2[0]);

        // (12) site geometry: the snapped attachment lies ON the lattice and follows the filament material frame.
        cfg(2, false, 0, 0, 0, false, 1.0, true);
        Rig r = new Rig(101); r.geom();
        int m = pickBindable(r); int s = r.bindTo(m, 0.5);
        boolean onLat = false, radiusOk = false; double residual = 0;
        if (s >= 0) {
            double rise = ExplicitCompleteMatHarness.siteRise(2);
            double gArc = r.e.segCumArc.get(s) + r.mot.bindArc.get(m);
            residual = Math.abs(gArc / rise - Math.rint(gArc / rise));
            onLat = residual < 1e-4;
            double[] site = reconSite(r.f, s, r.mot.bindArc.get(m), r.mot.bindAzim.get(m), R_NM*1e-3);
            double aOff = r.mot.bindArc.get(m) - 0.5*r.f.segLength.get(s);
            double[] ax = { r.f.coord.get(s)+aOff*r.f.uVec.get(s), r.f.coord.get(r.nSeg+s)+aOff*r.f.uVec.get(r.nSeg+s), r.f.coord.get(2*r.nSeg+s)+aOff*r.f.uVec.get(2*r.nSeg+s) };
            radiusOk = Math.abs(norm(new double[]{site[0]-ax[0], site[1]-ax[1], site[2]-ax[2]}) - R_NM*1e-3) < 1e-6;
        }
        note(String.format(Locale.US, "snapped site id=%d, global-arc/rise residual=%.2e, surface radius == Ractin: %b",
                r.e.bindSite.get(m), residual, radiusOk));
        ck(12, "fresh bind snaps onto the discrete lattice at the actin surface radius", onLat && radiusOk);

        // (13) site frame RIDES the filament: roll the filament by delta ⇒ the site's world azimuth follows by delta.
        double follow = siteRollFollow();
        note(String.format(Locale.US, "filament roll +0.40 rad ⇒ site world-direction rotation = %.4f rad", follow));
        ck(13, "site frame rolls/translates/bends with the filament material frame", Math.abs(follow - 0.40) < 2e-3);

        // (14) persistent identity: the site ID is latched for the whole attachment (never re-derived).
        cfg(2, false, 0, 0, 0, false, 1.0, true);
        Rig rp = new Rig(101);
        int[] site0 = new int[rp.N]; int[] bs0 = new int[rp.N];
        for (int t = 0; t < 60; t++) { rp.step(t, 101);
            if (t == 30) for (int i = 0; i < rp.N; i++) { bs0[i] = rp.mot.boundSeg.get(i); site0[i] = rp.e.bindSite.get(i); } }
        boolean held = false, latched = true;
        for (int i = 0; i < rp.N; i++) if (bs0[i] >= 0 && rp.mot.boundSeg.get(i) == bs0[i]) { held = true; if (rp.e.bindSite.get(i) != site0[i]) latched = false; }
        ck(14, "bound site ID retained (latched) for the whole attachment", held && latched);

        // (15) exclusive occupancy: no two heads share one (filament, site).
        cfg(2, false, 0, 0, 0, false, 1.0, true);
        Rig rx = new Rig(101);
        boolean dup = false; int maxBound = 0;
        for (int t = 0; t < 400; t++) {
            rx.step(t, 101);
            java.util.HashSet<Long> occ = new java.util.HashSet<>(); int nb = 0;
            for (int i = 0; i < rx.N; i++) { int bs = rx.mot.boundSeg.get(i); if (bs < 0) continue; nb++;
                long key = ((long) rx.e.segFilId.get(bs) << 32) ^ (rx.e.bindSite.get(i) & 0xFFFFFFFFL);
                if (!occ.add(key)) dup = true; }
            maxBound = Math.max(maxBound, nb);
        }
        note("max simultaneously bound heads over 400 steps = " + maxBound);
        ck(15, "site exclusivity: no two heads occupy one site simultaneously", !dup && maxBound > 0);

        // (16) lattice spacing: distinct occupied sites are integer multiples of the rise apart.
        ck(16, "occupied-site axial spacing is an integer multiple of the lattice rise", latticeSpacingCheck());
        cfgOff();
    }

    static boolean latticeSpacingCheck() {
        for (int mode = 1; mode <= 5; mode++) {
            cfg(mode, false, 0, 0, 0, false, 1.0, true);
            Rig r = new Rig(101);
            for (int t = 0; t < 200; t++) r.step(t, 101);
            double rise = ExplicitCompleteMatHarness.siteRise(mode);
            for (int i = 0; i < r.N; i++) {
                int bs = r.mot.boundSeg.get(i); if (bs < 0) continue;
                double gArc = r.e.segCumArc.get(bs) + r.mot.bindArc.get(i);
                double k = gArc / rise;
                if (Math.abs(k - Math.rint(k)) > 1e-3) return false;
                if (r.e.bindSite.get(i) != (int) Math.rint(k)) return false;
            }
        }
        return true;
    }

    /** Roll the filament by +0.40 rad about its own axis and measure how far the bound site's world direction turns. */
    static double siteRollFollow() {
        cfg(2, false, 0, 0, 0, false, 1.0, true);
        Rig r = new Rig(101); r.geom();
        int m = pickBindable(r); int s = r.bindTo(m, 0.5); if (s < 0) return Double.NaN;
        double[] p0 = radialDir(r, m, s);
        // roll: rotate yVec (and zVec) about uVec by +0.40 — a pure material roll of the segment
        double[] u = { r.f.uVec.get(s), r.f.uVec.get(r.nSeg+s), r.f.uVec.get(2*r.nSeg+s) };
        double[] y = { r.f.yVec.get(s), r.f.yVec.get(r.nSeg+s), r.f.yVec.get(2*r.nSeg+s) };
        double[] y2 = rot(y, u, 0.40);
        r.f.yVec.set(s, (float)y2[0]); r.f.yVec.set(r.nSeg+s, (float)y2[1]); r.f.yVec.set(2*r.nSeg+s, (float)y2[2]);
        double[] p1 = radialDir(r, m, s);
        double c = dot(p0, p1); if (c > 1) c = 1; if (c < -1) c = -1;
        double sgn = dot(cross(p0, p1), u);
        return sgn < 0 ? -Math.acos(c) : Math.acos(c);
    }
    static double[] radialDir(Rig r, int m, int s) {
        double[] u = { r.f.uVec.get(s), r.f.uVec.get(r.nSeg+s), r.f.uVec.get(2*r.nSeg+s) };
        double[] y = { r.f.yVec.get(s), r.f.yVec.get(r.nSeg+s), r.f.yVec.get(2*r.nSeg+s) };
        double[] z = nrm(cross(u, y));
        double ph = r.mot.bindAzim.get(m), c = Math.cos(ph), sn = Math.sin(ph);
        return nrm(new double[]{ c*y[0]+sn*z[0], c*y[1]+sn*z[1], c*y[2]+sn*z[2] });
    }

    // ---------------------------------------------------------------------------- Stage 3
    static void stage3Fixtures() {
        // (17) zero mismatch ⇒ zero registry torque; (18) ± mismatch ⇒ opposite torque; (19) equal-and-opposite.
        double[] z = registryProbe(0.0), p = registryProbe(+0.30), n = registryProbe(-0.30);
        note(String.format(Locale.US, "mismatch 0 ⇒ tau=%.3e ; +0.30 ⇒ tau=%.3e ; −0.30 ⇒ tau=%.3e N·m", z[0], p[0], n[0]));
        ck(17, "zero registry mismatch ⇒ zero registry torque", Math.abs(z[0]) < 1e-30);
        ck(18, "±mismatch ⇒ opposite-signed registry torque of equal magnitude",
                p[0]*n[0] < 0 && Math.abs(Math.abs(p[0]) - Math.abs(n[0])) < 1e-6*Math.abs(p[0]));
        note(String.format(Locale.US, "internal couple balance |tau_motor + tau_filament| / |tau_motor| = %.2e", p[1]));
        ck(19, "motor and filament registry couples are EQUAL AND OPPOSITE (total internal torque 0)", p[1] < 1e-6);

        // (20) K = 0 is EXACTLY inert.
        cfg(2, true, 0.0, 0, 0, false, 1.0, false);
        double[] h0 = trajHash(111, 200);
        cfg(2, false, 0.0, 0, 0, false, 1.0, false);
        double[] hNo = trajHash(111, 200);
        ck(20, "registry stiffness 0 ⇒ exactly inert (head DOF changes nothing mechanical)", h0[0] == hNo[0]);

        // (21) reversing the LOCAL SITE TANGENTIAL direction reverses the signed registry quantity
        // (symmetry-suite diagnostic 10). NOTE: the full lattice REFLECTION control — which reverses the helical
        // twist rate as well as the tangential sense, and therefore changes which sites exist — is not a valid
        // one-configuration fixture (the two arms bind different sites); it is measured properly, at scale, by the
        // frozen-configuration probe (report §12.3), where the eps-odd slope reverses sign at 0.7% magnitude match.
        double[] mp = registryProbeTangential(+1.0), mm = registryProbeTangential(-1.0);
        double dTau = mm[0] - mp[0], expect = REG_K * Math.PI;
        note(String.format(Locale.US, "headRef = +tSite ⇒ tau=%.3e ; headRef = −tSite ⇒ tau=%.3e N·m ; "
                + "difference %.4e vs K*pi = %.4e (rel %.1e)", mp[0], mm[0], dTau, expect, Math.abs(dTau-expect)/expect));
        ck(21, "reversing the head material reference through 180 deg shifts the registry torque by exactly K*pi",
                Math.abs(dTau - expect) < 1e-4 * expect && mp[0] * mm[0] < 0);

        // (22) detachment releases the registry cleanly.
        cfg(2, true, REG_K, 0, 0, false, 1.0, false);
        Rig r = new Rig(101); r.geom();
        int m = pickBindable(r); r.bindTo(m, 0.5);
        double[] eb = r.eBind(m); r.setRef(m, rot(orth(r.siteFrame(m)[0], eb), eb, 0.3));
        r.roll(0, 101); double tauBound = r.e.headTau.get(m);
        r.mot.boundSeg.set(m, -1); r.e.bindSite.set(m, -1);
        r.roll(1, 101); double tauFree = r.e.headTau.get(m);
        ck(22, "detachment releases the registry (bound torque ≠ 0, free torque == 0)",
                Math.abs(tauBound) > 1e-30 && tauFree == 0.0);
        cfgOff();
    }

    /** Impose a registry mismatch on a bound head; return {tau_motor, |tau_motor+tau_filament|/|tau_motor|}. */
    static double[] registryProbe(double mis) {
        cfg(2, true, REG_K, 0, 0, false, 1.0, false);
        Rig r = new Rig(101); r.geom();
        int m = pickBindable(r); if (r.bindTo(m, 0.5) < 0) return new double[]{ 0, 0 };
        double[] eb = r.eBind(m);
        r.setRef(m, rot(orth(r.siteFrame(m)[0], eb), eb, mis));
        for (int i = 0; i < 13*r.N; i++) r.G.bondData.set(i, 0f);        // isolate: only the registry writes bondData
        r.roll(0, 101);
        double tau = r.e.headTau.get(m);
        int d = m * 13;
        double[] tf = { r.G.bondData.get(d+9), r.G.bondData.get(d+10), r.G.bondData.get(d+11) };
        // the motor-side couple is tau*eBind ; the filament-side must be exactly its negative
        double[] resid = { tau*eb[0] + tf[0], tau*eb[1] + tf[1], tau*eb[2] + tf[2] };
        double rel = Math.abs(tau) > 0 ? norm(resid)/Math.abs(tau) : 0;
        return new double[]{ tau, rel };
    }
    /** Same scene, same site: put the head material reference at ±tSite and read the registry torque. */
    static double[] registryProbeTangential(double sign) {
        cfg(2, true, REG_K, 0, 0, false, 1.0, false);
        Rig r = new Rig(101); r.geom();
        int m = pickBindable(r); if (r.bindTo(m, 0.5) < 0) return new double[]{ 0, 0 };
        double[] eb = r.eBind(m);
        double[] t = orth(r.siteFrame(m)[2], eb);
        r.setRef(m, new double[]{ sign*t[0], sign*t[1], sign*t[2] });
        r.roll(0, 101);
        return new double[]{ r.e.headTau.get(m), 0 };
    }

    // ---------------------------------------------------------------------------- Stage 4/5
    static void stage45Fixtures() {
        // (23) askew BIND: the local-frame azimuth offset is exactly epsBind, and reverses with its sign.
        double[] a = skewGeom(+2.0), b = skewGeom(-2.0), z = skewGeom(0.0);
        note(String.format(Locale.US, "bound-interface offset from the site presentation: eps=+2 deg ⇒ %+.4f deg ; eps=−2 ⇒ %+.4f ; eps=0 ⇒ %+.4f",
                a[0], b[0], z[0]));
        ck(23, "askew bind offsets the bound interface by exactly ±epsBind in the local tangent plane",
                Math.abs(a[0] - 2.0) < 1e-3 && Math.abs(b[0] + 2.0) < 1e-3 && Math.abs(z[0]) < 1e-6);

        // (24) the askew offset adds an eps-ODD tangential force / axial torque on top of the (large) eps-EVEN
        // baseline that the SHARED motor base frame already produces. The gate is on the odd component, and the
        // even baseline is reported honestly — it is the scene artifact the randomized-base control targets.
        double[] tp = skewTorque(+2.0), tm = skewTorque(-2.0), t0 = skewTorque(0.0);
        double oddT = 0.5*(tp[1] - tm[1]), evenT = 0.5*(tp[1] + tm[1]);
        double oddF = 0.5*(tp[0] - tm[0]);
        note(String.format(Locale.US, "eps=+2 deg: <F_t>=%+.3e N, <tau_ax>=%+.3e N·m", tp[0], tp[1]));
        note(String.format(Locale.US, "eps=−2 deg: <F_t>=%+.3e N, <tau_ax>=%+.3e N·m", tm[0], tm[1]));
        note(String.format(Locale.US, "eps= 0 deg: <F_t>=%+.3e N, <tau_ax>=%+.3e N·m   (eps-EVEN baseline: shared base frame)", t0[0], t0[1]));
        note(String.format(Locale.US, "eps-ODD component: dF_t=%+.3e N, dtau=%+.3e N·m ; eps-EVEN tau=%+.3e", oddF, oddT, evenT));
        ck(24, "askew bind produces a resolved eps-ODD tangential force and axial torque",
                Math.abs(oddT) > 1e-24 && oddT*oddF > 0);
        // (25) the identity tau_axial = Ractin * F_tangential, per head, on the SAME frames the bond was built from.
        double idres = identityResidual(+2.0);
        note(String.format(Locale.US, "max per-head |tau_ax − Ractin·F_t| / |tau_ax| = %.2e (same-frame evaluation)", idres));
        ck(25, "axial torque equals the local moment Ractin × F_tangential (identity, not a fit)", idres < 1e-3);

        // (26) energy accounting: the skew adds no energy source — the F8 pair stays a closed couple.
        double resid = closedCoupleResidual(+2.0);
        note(String.format(Locale.US, "|F_head + F_seg| / |F_head| for the askew bond = %.2e (closed pair)", resid));
        ck(26, "askew bond force pair remains equal-and-opposite (no force created from nowhere)", resid < 1e-6);

        // (27) askew STROKE: the interface advances by exactly epsStroke ONCE per ADP·Pi→ADP transition.
        double[] st = strokeSkewProbe();
        note(String.format(Locale.US, "azimuth advance across one stroke transition = %+.4f deg (eps=%+.1f); repeats = %d",
                st[0], 2.0, (int) st[1]));
        ck(27, "askew stroke advances the interface by exactly epsStroke, once per stroke",
                Math.abs(st[0] - 2.0) < 1e-3 && st[1] == 1);
        cfgOff();
    }

    /** Measured angular offset (deg) between the bound interface azimuth and the pure lattice-site azimuth. */
    static double[] skewGeom(double epsDeg) {
        cfg(2, false, 0, epsDeg, 0, false, 1.0, false);
        Rig r = new Rig(101); r.geom();
        int m = pickBindable(r); if (r.bindTo(m, 0.5) < 0) return new double[]{ Double.NaN };
        double azSkew = r.mot.bindAzim.get(m);
        int site = r.e.bindSite.get(m);
        cfg(2, false, 0, 0.0, 0, false, 1.0, false);
        Rig r0 = new Rig(101); r0.geom();
        r0.bindTo(m, 0.5);
        double az0 = r0.mot.bindAzim.get(m);
        boolean sameSite = r0.e.bindSite.get(m) == site;
        return new double[]{ sameSite ? (azSkew - az0) * 180 / Math.PI : Double.NaN };
    }

    /** {mean tangential seg force, mean axial torque, R*F_t} over the bound population of a short dynamic run. */
    static double[] skewTorque(double epsDeg) {
        cfg(2, false, 0, epsDeg, 0, false, 1.0, true);
        Rig r = new Rig(101);
        double R = R_NM*1e-3*1e-6;   // metres (bondData torque is N·m, force N, lever in m)
        double ftAcc = 0, tauAcc = 0; long n = 0;
        for (int t = 0; t < 600; t++) {
            r.step(t, 101);
            if (t < 200) continue;
            for (int m = 0; m < r.N; m++) {
                if (r.mot.boundSeg.get(m) < 0) continue;
                double ft = ChiralSiteSystem.tangentialForce(r.G.bondData, r.f.uVec, r.f.yVec, r.mot.bindAzim,
                        r.mot.boundSeg, m, r.nSeg, 1.0);
                double tau = ChiralSiteSystem.axialTorque(r.G.bondData, r.f.uVec, r.mot.boundSeg, m, r.nSeg);
                ftAcc += ft; tauAcc += tau; n++;
            }
        }
        if (n == 0) return new double[]{ 0, 0, 0 };
        double ft = ftAcc/n, tau = tauAcc/n;
        return new double[]{ ft, tau, R*ft };
    }

    /**
     * Max per-head relative residual of the axial-torque identity {@code tau_ax = Ractin * F_t}, evaluated on the
     * SAME frames the bond forces were built from (the bond stage is re-run immediately before the read, so no
     * integration step separates the two).
     */
    static double identityResidual(double epsDeg) {
        cfg(2, false, 0, epsDeg, 0, false, 1.0, true);
        Rig r = new Rig(101);
        for (int t = 0; t < 400; t++) r.step(t, 101);
        r.geom();
        TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit(r.e.outGeom, r.mot.boundSeg, r.e.eupP, r.e.exCounts,
                r.mot.body.coord, r.mot.body.uVec, r.mot.body.yVec);
        CrossBridgeSystem.bondForcesSurface(r.mot.body.coord, r.mot.body.uVec, r.mot.body.yVec, r.mot.body.bRotGam,
                r.f.coord, r.f.uVec, r.f.yVec, r.f.bRotGam, r.f.segLength, r.mot.boundSeg, r.mot.bindArc,
                r.mot.bindAzim, r.mot.nucleotideState, r.G.bondData, r.e.xbParamsSurf);
        double R = R_NM*1e-3*1e-6, worst = 0; int n = 0;
        for (int m = 0; m < r.N; m++) {
            if (r.mot.boundSeg.get(m) < 0) continue;
            double tau = ChiralSiteSystem.axialTorque(r.G.bondData, r.f.uVec, r.mot.boundSeg, m, r.nSeg);
            double ft  = ChiralSiteSystem.tangentialForce(r.G.bondData, r.f.uVec, r.f.yVec, r.mot.bindAzim, r.mot.boundSeg, m, r.nSeg, 1.0);
            if (Math.abs(tau) < 1e-26) continue;
            worst = Math.max(worst, Math.abs(tau - R*ft)/Math.abs(tau)); n++;
        }
        return n > 0 ? worst : Double.NaN;
    }

    static double closedCoupleResidual(double epsDeg) {
        cfg(2, false, 0, epsDeg, 0, false, 1.0, true);
        Rig r = new Rig(101);
        for (int t = 0; t < 300; t++) r.step(t, 101);
        double worst = 0;
        for (int m = 0; m < r.N; m++) {
            if (r.mot.boundSeg.get(m) < 0) continue;
            int d = m*13;
            double fh = Math.sqrt(sq(r.G.bondData.get(d))+sq(r.G.bondData.get(d+1))+sq(r.G.bondData.get(d+2)));
            if (fh < 1e-18) continue;
            double rx = r.G.bondData.get(d)+r.G.bondData.get(d+6), ry = r.G.bondData.get(d+1)+r.G.bondData.get(d+7), rz = r.G.bondData.get(d+2)+r.G.bondData.get(d+8);
            worst = Math.max(worst, Math.sqrt(rx*rx+ry*ry+rz*rz)/fh);
        }
        return worst;
    }

    /** {azimuth advance in deg across the first ADP·Pi→ADP transition, number of advances during that attachment}. */
    static double[] strokeSkewProbe() {
        cfg(2, false, 0, 0, 2.0, false, 1.0, false);
        Rig r = new Rig(101);
        double adv = Double.NaN; int nAdv = 0;
        float[] prevAz = new float[r.N]; int[] prevNu = new int[r.N]; int[] prevBs = new int[r.N];
        for (int m = 0; m < r.N; m++) { prevAz[m] = r.mot.bindAzim.get(m); prevNu[m] = r.mot.nucleotideState.get(m); prevBs[m] = r.mot.boundSeg.get(m); }
        for (int t = 0; t < 2000 && Double.isNaN(adv); t++) {
            r.step(t, 101);
            for (int m = 0; m < r.N; m++) {
                int bs = r.mot.boundSeg.get(m), nu = r.mot.nucleotideState.get(m);
                if (bs >= 0 && prevBs[m] == bs && prevNu[m] == MotorStore.NUC_ADPPI && nu == MotorStore.NUC_ADP) {
                    double d = (r.mot.bindAzim.get(m) - prevAz[m]) * 180 / Math.PI;
                    if (Double.isNaN(adv)) { adv = d; nAdv = 1; }
                }
                prevAz[m] = r.mot.bindAzim.get(m); prevNu[m] = nu; prevBs[m] = bs;
            }
        }
        return new double[]{ adv, nAdv };
    }

    // =============================================================================== CPU/GPU equivalence
    static boolean runEquiv() {
        System.out.println("\n--- CPU/GPU EQUIVALENCE — FULL chiral-site gliding graph, device-resident ---");
        cfg(2, true, REG_K, EPS_PILOT_DEG, 0, false, 1.0, true);
        System.out.println("  config: " + ExplicitCompleteMatHarness.chiralConfigString());
        Glide2D Gc = build(101), Gd = build(101);
        var ec = ExplicitCompleteMatHarness.packExMat(Gc, 1);
        var ed = ExplicitCompleteMatHarness.packExMat(Gd, 1);
        TornadoExecutionPlan plan;
        TornadoCrashDiagnostic.planConstructionBegin("graph=buildGlidingGraph(chiral sites+headRoll) arm=equiv");
        try { plan = ExplicitCompleteMatHarness.buildGlidingGraph(ed, false); }
        catch (Throwable ex) { TornadoCrashDiagnostic.planConstructionThrew(ex);
            System.out.println("  FULL chiral graph did NOT lower: " + oneLine(root(ex).getMessage())
                    + "   (needs -Dtornado.enable.fma=false)"); return false; }
        TornadoCrashDiagnostic.planConstructionEnd(plan, "arm=equiv");
        int K = 200, firstDiv = -1, siteMism = 0, bindMism = 0; double maxFil = 0, maxOm = 0, maxTau = 0, maxAz = 0;
        boolean lowered = true;
        TornadoCrashDiagnostic.executeLoopBegin("glide", 0, K-1, "arm=equiv executeCallsPlanned=" + K);
        for (int t = 0; t < K; t++) {
            ed.matc.set(0, t); ed.matc.set(1, 101); Gd.mot.setCounts(t, 101, Gd.nSeg); Gd.fil.counts.set(1, t); Gd.fil.counts.set(2, 101);
            try { TornadoCrashDiagnostic.beforeExecute(t); plan.execute(); TornadoCrashDiagnostic.afterExecute(t); }
            catch (Throwable ex) { TornadoCrashDiagnostic.executeThrew(ex); lowered = false;
                System.out.println("  device execute FAILED @t=" + t + ": " + oneLine(root(ex).getMessage())); break; }
            ExplicitCompleteMatHarness.stepGlidingCPU(ec, t, 101);
            double dFil = 0;
            for (int i = 0; i < 3*Gc.nSeg; i++) dFil = Math.max(dFil, Math.abs(Gc.fil.coord.get(i) - Gd.fil.coord.get(i)));
            maxFil = Math.max(maxFil, dFil); if (firstDiv < 0 && dFil > 1e-6) firstDiv = t;
            if (firstDiv < 0 || t < 8) {   // decisions compared inside the bit-close window
                for (int m = 0; m < Gc.N; m++) {
                    if (Gc.mot.boundSeg.get(m) != Gd.mot.boundSeg.get(m)) bindMism++;
                    if (ec.bindSite.get(m) != ed.bindSite.get(m)) siteMism++;
                    maxOm = Math.max(maxOm, Math.abs(ec.headOmega.get(m) - ed.headOmega.get(m)));
                    maxTau = Math.max(maxTau, Math.abs(ec.headTau.get(m) - ed.headTau.get(m)));
                    maxAz = Math.max(maxAz, Math.abs(Gc.mot.bindAzim.get(m) - Gd.mot.bindAzim.get(m)));
                }
            }
        }
        TornadoCrashDiagnostic.executeLoopEnd("arm=equiv lowered=" + lowered);
        if (!lowered) { TornadoCrashDiagnostic.closePlan(plan, "graph=glide arm=equiv status=execute-failed"); return false; }
        int nbC = 0, nbD = 0; boolean fin = true;
        for (int m = 0; m < Gc.N; m++) { if (Gc.mot.boundSeg.get(m) >= 0) nbC++; if (Gd.mot.boundSeg.get(m) >= 0) nbD++; }
        for (int i = 0; i < 3*Gc.nSeg; i++) if (!Float.isFinite(Gd.fil.coord.get(i))) fin = false;
        boolean ok = lowered && fin && siteMism == 0 && bindMism == 0 && maxOm < 1e-5 && maxAz < 1e-5 && maxFil < 1e-1;
        System.out.printf(Locale.US,
                "  %d device-resident steps: siteIdMism=%d bindMism=%d max|dOmega|=%.2e max|dTau|=%.2e max|dAzim|=%.2e "
                + "max|dFilCoord|=%.2e µm firstDiv=%s bound CPU=%d GPU=%d finite=%b ⇒ %s%n",
                K, siteMism, bindMism, maxOm, maxTau, maxAz, maxFil,
                firstDiv < 0 ? "none (bit-close)" : ("t=" + firstDiv + " (chaotic float op-order)"), nbC, nbD, fin,
                ok ? "PASS (device-resident, no fallback)" : "*FAIL*");
        TornadoCrashDiagnostic.gpuWorkDeclaredFinished("arm=equiv");
        TornadoCrashDiagnostic.closePlan(plan, "graph=glide arm=equiv");
        cfgOff();
        return ok;
    }

    // =============================================================================== campaign
    static final class Arm {
        String name; int mode; boolean roll; double k, epsB, epsS; boolean rand; double mirror;
        Arm(String n, int m, boolean r, double k, double eb, double es, boolean rb, double mi) {
            name=n; mode=m; roll=r; this.k=k; epsB=eb; epsS=es; rand=rb; mirror=mi; }
    }
    static final class Res {
        double glide, avgBound, tauNet, tauAbs, cancel, turns, ft, misMean, misAbs, omegaSd;
        int binds, invalid;
    }

    /** Registry stiffness that holds the bound mismatch to an RMS of {@code targetRad}: K = kT / targetRad². */
    static double kForTargetMismatch(double targetRad) { return Constants.kT / (targetRad * targetRad); }

    static void runCampaign() {
        System.out.println("\n--- PILOT CAMPAIGN (matched seeds; target-zone OFF; roll spring OFF; runner: "
                + (GPU ? "GPU device-resident" : "CPU sequential") + ") ---");
        double eps = EPS_PILOT_DEG;
        double kSoft = REG_K, kStiff = kForTargetMismatch(0.10);
        System.out.printf(Locale.US, "  registry stiffnesses: soft K=%.2e (equipartition RMS %.2f rad), stiff K=%.2e "
                + "(RMS 0.10 rad); explicit-Euler stability limit K < 2*gammaOmega/dt = %.2e%n",
                kSoft, Math.sqrt(Constants.kT/kSoft), kStiff, 2*2.513e-24/DT);
        java.util.List<Arm> base = new java.util.ArrayList<>();
        base.add(new Arm("C0 continuous (no sites, no head DOF)", 0, false, 0,      0, 0, false, +1));
        base.add(new Arm("C1 sites + head DOF (registry K=0)",    2, true,  0,      0, 0, false, +1));
        base.add(new Arm("C2 sites + DOF + registry (soft)",      2, true,  kSoft,  0, 0, false, +1));
        base.add(new Arm("C2 sites + DOF + registry (stiff)",     2, true,  kStiff, 0, 0, false, +1));
        header();
        java.util.Map<String, Res[]> R = new java.util.LinkedHashMap<>();
        for (Arm a : base) { Res[] r = runArmSeeds(a); R.put(a.name, r); report(a, r); }
        // paired askew arms: each (+eps, -eps) pair at matched seeds ⇒ the odd component is a PAIRED statistic
        String[][] pairs = {
            { "B askew-bind  K=0     shared-base", "2,1,0,+,0,0,+1" },
            { "B askew-bind  K=0     RANDOM-base", "2,1,0,+,0,1,+1" },
            { "B askew-bind  K=stiff shared-base", "2,1,1,+,0,0,+1" },
            { "B askew-bind  K=stiff RANDOM-base", "2,1,1,+,0,1,+1" },
            { "B askew-bind  K=0     MIRROR-helix", "2,1,0,+,0,0,-1" },
            { "S askew-stroke K=0    shared-base", "2,1,0,0,+,0,+1" },
            { "S askew-stroke K=0    RANDOM-base", "2,1,0,0,+,1,+1" },
        };
        System.out.println();
        for (String[] p : pairs) {
            String[] f = p[1].split(",");
            int mode = Integer.parseInt(f[0]); boolean roll = f[1].equals("1");
            double k = f[2].equals("1") ? kStiff : 0.0;
            boolean rand = f[5].equals("1"); double mir = Double.parseDouble(f[6]);
            double eb = f[3].equals("+") ? eps : 0, es = f[4].equals("+") ? eps : 0;
            Arm ap = new Arm(p[0] + "  +eps", mode, roll, k, +eb, +es, rand, mir);
            Arm am = new Arm(p[0] + "  -eps", mode, roll, k, -eb, -es, rand, mir);
            Res[] rp = runArmSeeds(ap), rm = runArmSeeds(am);
            report(ap, rp); report(am, rm); reportPaired(p[0], rp, rm);
        }
        cfgOff();
    }

    /** The eps-ODD (chirality-carrying) component as a PAIRED per-seed statistic — the primary endpoint. */
    static void reportPaired(String name, Res[] rp, Res[] rm) {
        int n = Math.min(rp.length, rm.length);
        double[] dTau = new double[n], dFt = new double[n], dGl = new double[n], dTu = new double[n];
        for (int i = 0; i < n; i++) {
            dTau[i] = 0.5*(rp[i].tauNet - rm[i].tauNet);
            dFt[i]  = 0.5*(rp[i].ft     - rm[i].ft);
            dGl[i]  = 0.5*(rp[i].glide  - rm[i].glide);
            dTu[i]  = 0.5*(rp[i].turns  - rm[i].turns);
        }
        double[] t = ms(dTau), fq = ms(dFt), g = ms(dGl), u = ms(dTu);
        System.out.printf(Locale.US, "    >> ODD  %-34s dtau=%+.3e±%.1e N·m (%.1f sigma) | dF_t=%+.3e±%.1e N | "
                + "dglide=%+.3f±%.3f µm/s | dturns=%+.3f±%.3f%n",
                name, t[0], t[1], t[1] > 0 ? Math.abs(t[0]/t[1]) : 0, fq[0], fq[1], g[0], g[1], u[0], u[1]);
    }
    /** {mean, SEM}. */
    static double[] ms(double[] a) {
        int n = a.length; double m = 0; for (double v : a) m += v; m /= n;
        double s = 0; for (double v : a) s += sq(v-m);
        return new double[]{ m, n > 1 ? Math.sqrt(s/(n*(n-1))) : 0 };
    }

    static void runLattice() {
        System.out.println("\n--- LATTICE COMPARISON (askew bind +eps; is the torque about local chirality, spacing, or identity?) ---");
        double eps = EPS_PILOT_DEG;
        header();
        for (int mode = 1; mode <= 5; mode++) {
            Arm a = new Arm(String.format("%-10s eps=+%.0f deg", ExplicitCompleteMatHarness.siteModeName(mode), eps),
                    mode, true, REG_K, +eps, 0, false, +1);
            report(a, runArmSeeds(a));
        }
        cfgOff();
    }

    static void header() {
        System.out.printf("  %-42s %10s %8s %13s %13s %9s %9s %10s%n",
                "arm", "glide µm/s", "avgB", "tauNet N·m", "F_t N", "cancel", "turns", "misRMS rad");
    }
    static void report(Arm a, Res[] rs) {
        double g = 0, gs = 0, b = 0, tn = 0, tns = 0, ft = 0, ca = 0, tu = 0, tus = 0, mi = 0;
        int n = rs.length;
        for (Res r : rs) { g += r.glide; b += r.avgBound; tn += r.tauNet; ft += r.ft; ca += r.cancel; tu += r.turns; mi += r.misAbs; }
        g/=n; b/=n; tn/=n; ft/=n; ca/=n; tu/=n; mi/=n;
        for (Res r : rs) { gs += sq(r.glide-g); tns += sq(r.tauNet-tn); tus += sq(r.turns-tu); }
        double semG = n>1 ? Math.sqrt(gs/(n*(n-1))) : 0, semT = n>1 ? Math.sqrt(tns/(n*(n-1))) : 0, semU = n>1 ? Math.sqrt(tus/(n*(n-1))) : 0;
        String ftS = a.mode > 0 ? String.format(Locale.US, "%13.3e", ft) : "          n/a";
        System.out.printf(Locale.US, "  %-42s %6.3f±%.3f %8.2f %6.2e±%.1e %s %9.1f %6.3f±%.3f %10.4f%n",
                a.name, g, semG, b, tn, semT, ftS, ca, tu, semU, mi);
    }

    static Res[] runArmSeeds(Arm a) {
        Res[] out = new Res[SEEDS];
        for (int i = 0; i < SEEDS; i++) out[i] = runArm(a, SEED + i, STEPS);
        return out;
    }

    static Res runArm(Arm a, int seed, int steps) {
        cfg(a.mode, a.roll, a.k, a.epsB, a.epsS, a.rand, a.mirror, true);
        Glide2D G = build(seed); FilamentStore f = G.fil; int nSeg = G.nSeg;
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        double[] bhat = G.bhat;
        // RUNNER DISCLOSURE: -gpu builds the SAME buildGlidingGraph(prod) device-resident plan the CPU runner
        // mirrors kernel-for-kernel; the per-step telemetry the observables need is already in its copy-out set.
        TornadoExecutionPlan plan = null;
        if (GPU) {
            TornadoCrashDiagnostic.planConstructionBegin("graph=buildGlidingGraph(prod) arm=" + a.name + " seed=" + seed);
            try { plan = ExplicitCompleteMatHarness.buildGlidingGraph(e, true); }
            catch (Throwable ex) { TornadoCrashDiagnostic.planConstructionThrew(ex);
                throw new RuntimeException("device graph did NOT lower (no CPU fallback allowed): " + oneLine(root(ex).getMessage()), ex); }
            TornadoCrashDiagnostic.planConstructionEnd(plan, "arm=" + a.name);
            TornadoCrashDiagnostic.executeLoopBegin("glide", 0, steps-1, "arm=" + a.name + " seed=" + seed);
        }
        Res r = new Res();
        double[] prevRoll = new double[nSeg]; double[] cum = new double[nSeg];
        for (int s = 0; s < nSeg; s++) prevRoll[s] = ExplicitTwirlGlidingHarness.rollAngle(f, s, bhat);
        double g0 = ExplicitTwirlGlidingHarness.centroidDot(f, bhat);
        double tauNet = 0, tauAbs = 0, ftAcc = 0, misAcc = 0, misAbsAcc = 0; long nT = 0;
        double bSum = 0; int bN = 0, binds = 0; int[] prevBs = new int[G.N];
        for (int m = 0; m < G.N; m++) prevBs[m] = G.mot.boundSeg.get(m);
        double epsRad = a.epsB * Math.PI / 180.0;
        for (int t = 0; t < steps; t++) {
            if (GPU) {
                e.matc.set(0, t); e.matc.set(1, seed); G.mot.setCounts(t, seed, nSeg); f.counts.set(1, t); f.counts.set(2, seed);
                TornadoCrashDiagnostic.beforeExecute(t);
                try { plan.execute(); } catch (Throwable ex) { TornadoCrashDiagnostic.executeThrew(ex); throw new RuntimeException(ex); }
                TornadoCrashDiagnostic.afterExecute(t);
            } else ExplicitCompleteMatHarness.stepGlidingCPU(e, t, seed);
            for (int s = 0; s < nSeg; s++) { double rr = ExplicitTwirlGlidingHarness.rollAngle(f, s, bhat);
                cum[s] += ExplicitTwirlGlidingHarness.wrapPi(rr - prevRoll[s]); prevRoll[s] = rr; }
            double sn = 0, sa = 0; boolean any = false;
            for (int m = 0; m < G.N; m++) {
                int bs = G.mot.boundSeg.get(m);
                if (bs >= 0 && prevBs[m] < 0) binds++;
                prevBs[m] = bs;
                if (bs < 0) continue;
                any = true;
                double tau = ChiralSiteSystem.axialTorque(G.bondData, f.uVec, G.mot.boundSeg, m, nSeg);
                sn += tau; sa += Math.abs(tau);
                ftAcc += ChiralSiteSystem.tangentialForce(G.bondData, f.uVec, f.yVec, G.mot.bindAzim, G.mot.boundSeg, m, nSeg, a.mirror);
                misAcc += e.headMis.get(m); misAbsAcc += Math.abs(e.headMis.get(m));
                nT++;
            }
            if (any) { tauNet += sn; tauAbs += sa; }
            if ((t+1) % 100 == 0) { int nb = 0; for (int m = 0; m < G.N; m++) if (G.mot.boundSeg.get(m) >= 0) nb++; bSum += nb; bN++; }
        }
        double time = steps * DT;
        r.glide = (ExplicitTwirlGlidingHarness.centroidDot(f, bhat) - g0) / time;
        double mean = 0; for (double v : cum) mean += v; mean /= nSeg;
        r.turns = mean / (2*Math.PI);
        r.avgBound = bN > 0 ? bSum/bN : 0;
        r.tauNet = steps > 0 ? tauNet/steps : 0;
        r.tauAbs = steps > 0 ? tauAbs/steps : 0;
        r.cancel = Math.abs(r.tauNet) > 1e-30 ? r.tauAbs/Math.abs(r.tauNet) : 0;
        r.ft = nT > 0 ? ftAcc/nT : 0;
        r.misMean = nT > 0 ? misAcc/nT : 0;
        r.misAbs = nT > 0 ? misAbsAcc/nT : 0;
        r.binds = binds;
        for (int i = 0; i < 3*nSeg; i++) if (!Float.isFinite(f.coord.get(i))) r.invalid++;
        if (GPU) {
            TornadoCrashDiagnostic.executeLoopEnd("arm=" + a.name + " seed=" + seed);
            TornadoCrashDiagnostic.gpuWorkDeclaredFinished("arm=" + a.name);
            TornadoCrashDiagnostic.closePlan(plan, "graph=glide arm=" + a.name);
        }
        return r;
    }

    // =============================================================================== mechanism probe
    /**
     * FROZEN-CONFIGURATION eps-RESPONSE — the decisive isolation of the askew mechanism.
     *
     * <p>A dynamic A/B at ±eps is confounded: the two arms are chaotic trajectories that decorrelate, so the
     * eps-odd signal has to be dug out of trajectory noise. This probe removes that entirely. It advances ONE
     * eps = 0 trajectory, and at each sampled step takes the bound configuration exactly as it stands, applies
     * the local-frame offset ±eps to the bound interface azimuths, re-runs ONLY the bond stage, and reads the
     * axial torque. Same heads, same sites, same filament pose, same forces everywhere else — the difference is
     * the mechanism and nothing else.
     *
     * <p>Reports tau(eps) and F_t(eps) for a sweep of eps, for BOTH the shared and randomized motor-base scenes.
     */
    static void runMechanismProbe() {
        System.out.println("\n--- FROZEN-CONFIGURATION eps-RESPONSE (mechanism isolated from trajectory noise) ---");
        System.out.println("  actin lattice chirality: " + (MECH_MIRROR < 0 ? "MIRRORED (reflected helix)" : "native (as built)"));
        double[] epsDeg = { -5, -2, -1, 0, 1, 2, 5 };
        for (int base = 0; base < 2; base++) {
            boolean rand = base == 1;
            System.out.println("  motor base frames: " + (rand ? "RANDOMIZED per motor" : "SHARED (canonical scene)"));
            System.out.printf("    %8s %14s %14s %10s %10s%n", "eps deg", "tauNet N·m", "F_t N", "cancel", "F_ax N");
            double[] tau0 = null;
            for (double ed : epsDeg) {
                double[] acc = new double[4]; long n = 0;
                for (int si = 0; si < SEEDS; si++) {
                    double[] one = frozenResponse(ed, SEED + si, rand);
                    acc[0] += one[0]; acc[1] += one[1]; acc[2] += one[2]; acc[3] += one[3]; n++;
                }
                double tn = acc[0]/n, ft = acc[1]/n, ta = acc[2]/n, fa = acc[3]/n;
                System.out.printf(Locale.US, "    %+8.1f %14.4e %14.4e %10.1f %10.3e%n",
                        ed, tn, ft, Math.abs(tn) > 1e-30 ? ta/Math.abs(tn) : 0, fa);
                if (ed == 0) tau0 = new double[]{ tn, ft, fa };
            }
            if (tau0 != null) System.out.printf(Locale.US,
                    "    baseline (eps=0): tauNet=%.4e N·m  F_t=%.4e N  F_ax=%.4e N%n", tau0[0], tau0[1], tau0[2]);
        }
        cfgOff();
    }

    /** {tauNet, F_t, sum|tau|, F_axial} summed over bound heads, averaged over sampled frozen configurations. */
    static double[] frozenResponse(double epsDeg, int seed, boolean randBase) {
        cfg(2, false, 0, 0, 0, randBase, MECH_MIRROR, true);       // the trajectory itself is ALWAYS eps = 0
        Rig r = new Rig(seed);
        double eps = MECH_MIRROR * epsDeg * Math.PI / 180.0;   // the offset lives in the LOCAL tangent plane
        double tn = 0, ftS = 0, ta = 0, fa = 0; long n = 0;
        float[] az0 = new float[r.N];
        for (int t = 0; t < 1200; t++) {
            r.step(t, seed);
            if (t < 400 || t % 20 != 0) continue;
            for (int m = 0; m < r.N; m++) az0[m] = r.mot.bindAzim.get(m);
            // apply the LOCAL-FRAME offset to the bound interfaces, re-run the bond stage only, then restore
            for (int m = 0; m < r.N; m++) if (r.mot.boundSeg.get(m) >= 0) r.mot.bindAzim.set(m, (float) (az0[m] + eps));
            TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit(r.e.outGeom, r.mot.boundSeg, r.e.eupP, r.e.exCounts,
                    r.mot.body.coord, r.mot.body.uVec, r.mot.body.yVec);
            CrossBridgeSystem.bondForcesSurface(r.mot.body.coord, r.mot.body.uVec, r.mot.body.yVec, r.mot.body.bRotGam,
                    r.f.coord, r.f.uVec, r.f.yVec, r.f.bRotGam, r.f.segLength, r.mot.boundSeg, r.mot.bindArc,
                    r.mot.bindAzim, r.mot.nucleotideState, r.G.bondData, r.e.xbParamsSurf);
            double sn = 0, sa = 0, sf = 0, sax = 0;
            for (int m = 0; m < r.N; m++) {
                int s = r.mot.boundSeg.get(m); if (s < 0) continue;
                double tau = ChiralSiteSystem.axialTorque(r.G.bondData, r.f.uVec, r.mot.boundSeg, m, r.nSeg);
                sn += tau; sa += Math.abs(tau);
                sf += ChiralSiteSystem.tangentialForce(r.G.bondData, r.f.uVec, r.f.yVec, r.mot.bindAzim, r.mot.boundSeg, m, r.nSeg, 1.0);
                int d = m*13;
                sax += r.G.bondData.get(d+6)*r.f.uVec.get(s) + r.G.bondData.get(d+7)*r.f.uVec.get(r.nSeg+s)
                     + r.G.bondData.get(d+8)*r.f.uVec.get(2*r.nSeg+s);
            }
            tn += sn; ta += sa; ftS += sf; fa += sax; n++;
            for (int m = 0; m < r.N; m++) r.mot.bindAzim.set(m, az0[m]);   // restore — the trajectory stays eps=0
        }
        return n > 0 ? new double[]{ tn/n, ftS/n, ta/n, fa/n } : new double[4];
    }

    // =============================================================================== 3js
    static void makeMovies(String dir) {
        System.out.println("\n--- 3js: discrete sites + head material frame + bound-site markers ---");
        String[] names = { dir + "_C2", dir + "_Bplus" };
        Arm[] arms = { new Arm("C2", 2, true, REG_K, 0, 0, false, +1),
                       new Arm("B+", 2, true, REG_K, EPS_PILOT_DEG, 0, false, +1) };
        for (int i = 0; i < arms.length; i++) {
            cfg(arms[i].mode, arms[i].roll, arms[i].k, arms[i].epsB, arms[i].epsS, arms[i].rand, arms[i].mirror, true);
            Glide2D G = build(SEED);
            var e = ExplicitCompleteMatHarness.packExMat(G, 1);
            java.io.File d = new java.io.File(names[i]); d.mkdirs();
            int frame = 0;
            for (int t = 0; t < STEPS; t++) {
                ExplicitCompleteMatHarness.stepGlidingCPU(e, t, SEED);
                if (t % STRIDE == 0) writeFrame(new java.io.File(d, String.format("frame%05d.json", frame++)), G, e, t);
            }
            System.out.println("  wrote " + frame + " frames to " + names[i]);
        }
        cfgOff();
    }

    /** v1 viewer schema: segments (centerline rods) + bound-site markers + head material-frame ticks. */
    static void writeFrame(java.io.File file, Glide2D G, ExplicitCompleteMatHarness.ExMat e, int t) {
        FilamentStore f = G.fil; int nSeg = G.nSeg;
        StringBuilder b = new StringBuilder(1 << 16);
        b.append("{\n  \"time\": ").append(String.format(Locale.US, "%.6f", t*DT)).append(",\n  \"segments\": [\n");
        for (int s = 0; s < nSeg; s++) {
            double half = 0.5*f.segLength.get(s);
            double cx = f.coord.get(s), cy = f.coord.get(nSeg+s), cz = f.coord.get(2*nSeg+s);
            double ux = f.uVec.get(s), uy = f.uVec.get(nSeg+s), uz = f.uVec.get(2*nSeg+s);
            b.append(String.format(Locale.US, "    {\"x1\":%.5f,\"y1\":%.5f,\"z1\":%.5f,\"x2\":%.5f,\"y2\":%.5f,\"z2\":%.5f,\"r\":0.0035,\"c\":\"#66ccff\"}%s%n",
                    cx-half*ux, cy-half*uy, cz-half*uz, cx+half*ux, cy+half*uy, cz+half*uz, s < nSeg-1 ? "," : ""));
        }
        b.append("  ],\n  \"myosins\": [\n");
        boolean first = true;
        for (int m = 0; m < G.N; m++) {
            int s = G.mot.boundSeg.get(m); if (s < 0) continue;
            double[] site = reconSite(f, s, G.mot.bindArc.get(m), G.mot.bindAzim.get(m), R_NM*1e-3);
            double hx = e.outGeom.get(3*G.N+m), hy = e.outGeom.get(4*G.N+m), hz = e.outGeom.get(5*G.N+m);
            double tau = ChiralSiteSystem.axialTorque(G.bondData, f.uVec, G.mot.boundSeg, m, nSeg);
            String col = tau > 0 ? "#ff4444" : "#44ff44";
            if (!first) b.append(",\n"); first = false;
            b.append(String.format(Locale.US, "    {\"x1\":%.5f,\"y1\":%.5f,\"z1\":%.5f,\"x2\":%.5f,\"y2\":%.5f,\"z2\":%.5f,\"r\":0.0015,\"c\":\"%s\"}",
                    hx, hy, hz, site[0], site[1], site[2], col));
            // head material-frame tick (visualization only — non-force-bearing)
            double rx = e.headRef.get(m), ry = e.headRef.get(G.N+m), rz = e.headRef.get(2*G.N+m);
            b.append(String.format(Locale.US, ",\n    {\"x1\":%.5f,\"y1\":%.5f,\"z1\":%.5f,\"x2\":%.5f,\"y2\":%.5f,\"z2\":%.5f,\"r\":0.0008,\"c\":\"#ffdd33\"}",
                    hx, hy, hz, hx+0.012*rx, hy+0.012*ry, hz+0.012*rz));
        }
        b.append("\n  ]\n}\n");
        try (java.io.Writer w = new java.io.FileWriter(file)) { w.write(b.toString()); } catch (Exception ex) { }
    }

    // ===========================================================================================================
    //  SINGLE-SEGMENT, FILAMENT-BROWNIAN-OFF DYNAMIC TWIRLING ASSAY
    //  ---------------------------------------------------------------------------------------------------------
    //  The question: with ONE rigid filament segment and every filament Brownian channel disabled, does a local
    //  askew actin attachment (epsBind, the mechanism established on frozen configurations in §12) generate a
    //  reproducible SIGNED axial torque AND the corresponding directed rigid-body roll in a fully dynamic gliding
    //  assay? Motor search, chemistry, power strokes, binding/detachment, off-axis surface force application and
    //  discrete persistent sites are all UNCHANGED and fully thermal. Nothing is tuned; every claim is made on the
    //  eps-ODD paired component.
    // ===========================================================================================================
    static final class TArm {
        final String tag; final int mode; final boolean roll; final double k, epsB, epsS;
        final boolean rand; final double mirror, rNm; final boolean filBrown; final int segs;
        TArm(String tag, double epsB, boolean rand, double mirror, boolean filBrown, int segs) {
            this(tag, 2, true, 0.0, epsB, 0.0, rand, mirror, filBrown, segs, R_NM); }
        TArm(String tag, int mode, boolean roll, double k, double epsB, double epsS, boolean rand, double mirror,
             boolean filBrown, int segs, double rNm) {
            this.tag = tag; this.mode = mode; this.roll = roll; this.k = k; this.epsB = epsB; this.epsS = epsS;
            this.rand = rand; this.mirror = mirror; this.filBrown = filBrown; this.segs = segs; this.rNm = rNm; }
    }
    /** One seed's measurement-window statistics (equilibration discarded). */
    static final class TRes {
        double gammaRoll;        // body-fixed axial (roll) rotational drag actually used by the integrator, N·m·s
        double tau, omega, omegaPred, qOmega;   // mean axial torque (N·m), mean angular velocity (rad/s), τ/γ, ratio
        double turns, glide, avgBound, cancel, tauPerHead, ft, fax, misAbs, coherence, rollR2;
        double omegaLegacy, turnsLegacy;   // the ill-conditioned lab-referenced readout, reported as a diagnostic only
        double bindsPerStep, detachPerStep, strokesPerStep, tauPerStroke;
        double tauBlkSem, omegaBlkSem;
        int invalid, solverFail, nSeg;
        double[] blkTau, blkOmega, blkGlide, blkBound;
        // AGE-RESOLVED per-head axial torque: bin b collects heads whose attachment age (in steps) is in
        // [2^b - 1, 2^(b+1) - 1). This is the T5 diagnostic — does the frozen askew response decay with residence?
        double[] ageTau = new double[AGE_BINS]; long[] ageN = new long[AGE_BINS];
        double meanResidenceSteps;
    }
    static final int AGE_BINS = 10;
    static final String[] AGE_LABEL = { "0", "1", "2-3", "4-7", "8-15", "16-31", "32-63", "64-127", "128-255", "256+" };
    static int ageBin(int age) { int b = 0; while (b < AGE_BINS-1 && age >= (1 << (b+1)) - 1) b++; return b; }

    static void printTwirlConfigBlock() {
        System.out.println("\n######## SINGLE-SEGMENT, FILAMENT-BROWNIAN-OFF DYNAMIC TWIRLING ASSAY ########");
        System.out.printf(Locale.US, "  dt = %.3e s   steps = %d   seeds = %d   density = %.0f heads/µm²   "
                + "equilibration = %.0f%% of the run   blocks = %d%n", DTR, STEPS, SEEDS, DENSITY, 100*EQUIL_FRAC, NBLK);
        System.out.println("  ---- explicit startup configuration (no implicit defaults) ----");
        System.out.printf("  filament translational Brownian = %s   (axial + transverse)%n", FIL_BROWN ? "ON" : "OFF");
        System.out.printf("  filament rotational Brownian    = %s   (roll + bend/tumble)%n", FIL_BROWN ? "ON" : "OFF");
        System.out.println("  motor/S2 Brownian               = ON    (beam nodes + phi + psi; never state-quieted)");
        System.out.println("  head-roll Brownian              = ON    (private 'HOMG' stream on the head DOF)");
        System.out.printf("  filament segments               = %d%s%n", FIL_SEGS,
                FIL_SEGS == 1 ? "     (ONE rigid rod, full contour: no bending/joints/intersegment torsion)" : "");
        System.out.println("  roll spring                     = OFF   (RollSpringSystem is not referenced by this lineage)");
        System.out.println("  target-zone                     = OFF");
        System.out.println("  bound-registry-k                = 0     (primary assay: isolate the askew ATTACHMENT)");
        System.out.printf("  stroke-skew-deg                 = 0     |  binding-skew-deg = ±%.1f (primary)%n", EPS_TWIRL_DEG);
        System.out.println("  lattice                         = every3 (native-monomer subset, 8.10 nm rise)");
    }

    /** The body-fixed axial (roll) drag the integrator divides by: bRotGam plane-X of segment 0. */
    static double gammaRollOf(Glide2D G) { return G.fil.bRotGam.get(0); }

    /**
     * BODY-FIXED axial roll increment by discrete PARALLEL TRANSPORT — the well-conditioned roll observable.
     *
     * <p><b>Why the legacy readout cannot be used here.</b> {@code ExplicitTwirlGlidingHarness.rollAngle} measures
     * yVec's azimuth against <b>b̂ projected ⊥ û</b>. In this scene the filament is built along +x and
     * {@code bhat = +x}, so {@code b̂·û ≈ 1} and that reference is NEARLY DEGENERATE: its direction is set by the
     * (tiny) tilt of û, so the readout tracks the rod's TUMBLE azimuth rather than its material spin. The
     * {@code Ractin = 0} control makes this explicit — zero axial torque, yet the legacy readout reports
     * ~1e2 rad/s. It is reported as a diagnostic, never used for a claim.
     *
     * <p>This measure instead transports the previous material yVec onto the plane ⊥ the CURRENT û
     * (Gram–Schmidt = the discrete rotation-minimizing frame — the same convention the head DOF uses) and takes the
     * signed angle to the new yVec about û. For the integrator's own update
     * ({@code u += y·bwz·dt − z·bwy·dt}, {@code y += −u·bwz·dt + z·bwx·dt}) a pure tumble ({@code bwx = 0}) leaves
     * this increment zero to first order, so it returns exactly the body-fixed spin {@code bwx·dt = τ·û·dt/γ_roll}.
     *
     * @param prevY previous material yVec (3 doubles); OVERWRITTEN with the current one.
     */
    static double rollIncrementTransported(FilamentStore f, int s, double[] prevY) {
        int n = f.n;
        double ux = f.uVec.get(s), uy = f.uVec.get(n+s), uz = f.uVec.get(2*n+s);
        double yx = f.yVec.get(s), yy = f.yVec.get(n+s), yz = f.yVec.get(2*n+s);
        double d = prevY[0]*ux + prevY[1]*uy + prevY[2]*uz;
        double tx = prevY[0] - d*ux, ty = prevY[1] - d*uy, tz = prevY[2] - d*uz;
        double tl = Math.sqrt(tx*tx + ty*ty + tz*tz);
        prevY[0] = yx; prevY[1] = yy; prevY[2] = yz;
        if (tl < 1e-9) return 0.0;
        tx /= tl; ty /= tl; tz /= tl;
        double cx = ty*yz - tz*yy, cy = tz*yx - tx*yz, cz = tx*yy - ty*yx;
        return Math.atan2(cx*ux + cy*uy + cz*uz, tx*yx + ty*yy + tz*yz);
    }
    static void seedPrevY(FilamentStore f, int s, double[] prevY) {
        int n = f.n; prevY[0] = f.yVec.get(s); prevY[1] = f.yVec.get(n+s); prevY[2] = f.yVec.get(2*n+s); }

    /** Print the one-segment drag audit for a scene built with the current FIL_SEGS. */
    static void dragAudit(String label) {
        Glide2D G = build(SEED); FilamentStore f = G.fil; int n = G.nSeg;
        double contour = 0; for (int s = 0; s < n; s++) contour += f.segLength.get(s);
        double gRoll = f.bRotGam.get(0), gTx = f.bTransGam.get(0), gTy = f.bTransGam.get(n);
        double gBend = f.bRotGam.get(n);
        System.out.printf(Locale.US, "  %-22s nSeg=%2d  segLength=%.4f µm  contour=%.4f µm  radius=%.4f µm (%.2f nm)%n",
                label, n, f.segLength.get(0), contour, Constants.radius, Constants.radius*1e3);
        System.out.printf(Locale.US, "  %-22s gammaTrans_par=%.4e  gammaTrans_perp=%.4e N·s/m   "
                + "gammaRoll(axial)=%.4e  gammaBend=%.4e N·m·s%n", "", gTx, gTy, gRoll, gBend);
        System.out.printf(Locale.US, "  %-22s expected angular velocity per unit axial torque = 1/gammaRoll = %.4e rad/(s·N·m)"
                + "   [%.3e rad/s per 1e-21 N·m]%n", "", 1.0/gRoll, 1e-21/gRoll);
        if (n > 1) {
            double single = DragTensorSystem.rodDragSI(contour, Constants.radius)[3];
            System.out.printf(Locale.US, "  %-22s sum of per-segment roll drags = %.4e ; ONE rod of the same contour = %.4e "
                    + "(ratio %.6f — roll drag is ADDITIVE in length ⇒ the single rod carries the whole filament's roll drag)%n",
                    "", n*gRoll, single, n*gRoll/single);
        }
    }

    // ------------------------------------------------------------------------------------ one arm, one seed
    static TRes runTwirlArm(TArm a, int seed, int steps) {
        int savedSegs = FIL_SEGS; boolean savedBrown = FIL_BROWN; double savedR = R_NM;
        FIL_SEGS = a.segs; FIL_BROWN = a.filBrown; R_NM = a.rNm;
        boolean savedSci = ExplicitCompleteMatHarness.PROD_SCI;
        ExplicitCompleteMatHarness.PROD_SCI = true;   // per-step nucleotideState readback (stroke counting only)
        try {
            cfg(a.mode, a.roll, a.k, a.epsB, a.epsS, a.rand, a.mirror, true);
            Glide2D G = build(seed); FilamentStore f = G.fil; int nSeg = G.nSeg, N = G.N;
            var e = ExplicitCompleteMatHarness.packExMat(G, 1);
            double[] bhat = G.bhat;
            TornadoExecutionPlan plan = null;
            if (GPU) {
                TornadoCrashDiagnostic.planConstructionBegin("graph=buildGlidingGraph(prod) twirlArm=" + a.tag + " seed=" + seed);
                try { plan = ExplicitCompleteMatHarness.buildGlidingGraph(e, true); }
                catch (Throwable ex) { TornadoCrashDiagnostic.planConstructionThrew(ex);
                    throw new RuntimeException("device graph did NOT lower (no CPU fallback allowed): " + oneLine(root(ex).getMessage()), ex); }
                TornadoCrashDiagnostic.planConstructionEnd(plan, "twirlArm=" + a.tag);
                TornadoCrashDiagnostic.executeLoopBegin("glide", 0, steps-1, "twirlArm=" + a.tag + " seed=" + seed);
            }
            TRes r = new TRes(); r.nSeg = nSeg; r.gammaRoll = gammaRollOf(G);
            int equil = Math.max(1, (int) Math.round(EQUIL_FRAC * steps)), meas = steps - equil;
            int blk = Math.max(1, meas / NBLK), traceEvery = Math.max(1, meas / NTRACE);
            double[] prevRoll = new double[nSeg], cum = new double[nSeg], cumLegacy = new double[nSeg];
            double[][] prevY = new double[nSeg][3];
            for (int s = 0; s < nSeg; s++) { prevRoll[s] = ExplicitTwirlGlidingHarness.rollAngle(f, s, bhat);
                                            seedPrevY(f, s, prevY[s]); }
            int[] prevBs = new int[N], prevNu = new int[N], age = new int[N];
            for (int m = 0; m < N; m++) { prevBs[m] = G.mot.boundSeg.get(m); prevNu[m] = G.mot.nucleotideState.get(m);
                                         age[m] = prevBs[m] >= 0 ? 0 : -1; }
            double rollAtEquil = 0, glideAtEquil = 0, legacyAtEquil = 0;
            double tauAcc = 0, tauAbsAcc = 0, ftAcc = 0, faxAcc = 0, misAcc = 0, boundAcc = 0;
            long nBoundSamp = 0, binds = 0, detach = 0, strokes = 0, measSteps = 0;
            double blkTauAcc = 0, blkRoll0 = 0, blkGlide0 = 0, blkBoundAcc = 0; long blkSteps = 0;
            java.util.List<double[]> blocks = new java.util.ArrayList<>();
            java.util.List<double[]> trace = new java.util.ArrayList<>();
            for (int t = 0; t < steps; t++) {
                if (GPU) {
                    e.matc.set(0, t); e.matc.set(1, seed); G.mot.setCounts(t, seed, nSeg); f.counts.set(1, t); f.counts.set(2, seed);
                    TornadoCrashDiagnostic.beforeExecute(t);
                    try { plan.execute(); } catch (Throwable ex) { TornadoCrashDiagnostic.executeThrew(ex); throw new RuntimeException(ex); }
                    TornadoCrashDiagnostic.afterExecute(t);
                } else ExplicitCompleteMatHarness.stepGlidingCPU(e, t, seed);
                for (int s = 0; s < nSeg; s++) {
                    cum[s] += rollIncrementTransported(f, s, prevY[s]);                       // PRIMARY: body-fixed spin
                    double rr = ExplicitTwirlGlidingHarness.rollAngle(f, s, bhat);            // DIAGNOSTIC: legacy lab ref
                    cumLegacy[s] += ExplicitTwirlGlidingHarness.wrapPi(rr - prevRoll[s]); prevRoll[s] = rr; }
                double meanRoll = 0; for (double v : cum) meanRoll += v; meanRoll /= nSeg;
                double gl = ExplicitTwirlGlidingHarness.centroidDot(f, bhat);
                if (t == equil - 1) {
                    rollAtEquil = meanRoll; glideAtEquil = gl; blkRoll0 = meanRoll; blkGlide0 = gl;
                    legacyAtEquil = 0; for (double v : cumLegacy) legacyAtEquil += v; legacyAtEquil /= nSeg;
                }
                if (t < equil) {   // startup transient: advance the trajectory, measure nothing
                    for (int m = 0; m < N; m++) { int bs = G.mot.boundSeg.get(m);
                        age[m] = bs < 0 ? -1 : (prevBs[m] == bs ? age[m] + 1 : 0);
                        prevBs[m] = bs; prevNu[m] = G.mot.nucleotideState.get(m); }
                    continue;
                }
                double sn = 0, sa = 0; int nb = 0;
                for (int m = 0; m < N; m++) {
                    int bs = G.mot.boundSeg.get(m), nu = G.mot.nucleotideState.get(m);
                    if (bs >= 0 && prevBs[m] < 0) binds++;
                    if (bs < 0 && prevBs[m] >= 0) detach++;
                    if (bs >= 0 && prevBs[m] == bs && prevNu[m] == MotorStore.NUC_ADPPI && nu == MotorStore.NUC_ADP) strokes++;
                    int myAge = bs < 0 ? -1 : (prevBs[m] == bs ? age[m] + 1 : 0);
                    age[m] = myAge; prevBs[m] = bs; prevNu[m] = nu;
                    if (bs < 0) continue;
                    nb++;
                    double tau = ChiralSiteSystem.axialTorque(G.bondData, f.uVec, G.mot.boundSeg, m, nSeg);
                    int ab = ageBin(myAge); r.ageTau[ab] += tau; r.ageN[ab]++;
                    sn += tau; sa += Math.abs(tau);
                    ftAcc += ChiralSiteSystem.tangentialForce(G.bondData, f.uVec, f.yVec, G.mot.bindAzim, G.mot.boundSeg, m, nSeg, a.mirror);
                    int d = m*13;
                    faxAcc += G.bondData.get(d+6)*f.uVec.get(bs) + G.bondData.get(d+7)*f.uVec.get(nSeg+bs)
                            + G.bondData.get(d+8)*f.uVec.get(2*nSeg+bs);
                    misAcc += Math.abs(e.headMis.get(m)); nBoundSamp++;
                }
                tauAcc += sn; tauAbsAcc += sa; boundAcc += nb; measSteps++;
                blkTauAcc += sn; blkBoundAcc += nb; blkSteps++;
                if (blkSteps == blk && blocks.size() < NBLK) {
                    double dtSpan = blkSteps * DTR;
                    blocks.add(new double[]{ blkTauAcc/blkSteps, (meanRoll-blkRoll0)/dtSpan, (gl-blkGlide0)/dtSpan, blkBoundAcc/blkSteps });
                    blkTauAcc = 0; blkBoundAcc = 0; blkSteps = 0; blkRoll0 = meanRoll; blkGlide0 = gl;
                }
                if ((t - equil) % traceEvery == 0) {
                    boolean fin = true;
                    for (int i = 0; i < 3*nSeg; i++) if (!Float.isFinite(f.coord.get(i))) fin = false;
                    for (int i = 0; i < 6; i++) if (!Double.isFinite(e.redOut.get(i))) { fin = false; r.solverFail++; }
                    if (!fin) r.invalid++;
                    trace.add(new double[]{ (t - equil + 1) * DTR, meanRoll, gl });
                }
            }
            double measTime = measSteps * DTR;
            double meanRollEnd = 0; for (double v : cum) meanRollEnd += v; meanRollEnd /= nSeg;
            double sdRoll = 0; for (double v : cum) sdRoll += sq(v - meanRollEnd);
            sdRoll = nSeg > 1 ? Math.sqrt(sdRoll/(nSeg-1)) : 0;
            double legacyEnd = 0; for (double v : cumLegacy) legacyEnd += v; legacyEnd /= nSeg;
            r.turns = (meanRollEnd - rollAtEquil) / (2*Math.PI);
            r.omega = measTime > 0 ? (meanRollEnd - rollAtEquil) / measTime : 0;
            r.omegaLegacy = measTime > 0 ? (legacyEnd - legacyAtEquil) / measTime : 0;
            r.turnsLegacy = (legacyEnd - legacyAtEquil) / (2*Math.PI);
            r.glide = measTime > 0 ? (ExplicitTwirlGlidingHarness.centroidDot(f, bhat) - glideAtEquil) / measTime : 0;
            r.tau = measSteps > 0 ? tauAcc/measSteps : 0;
            double tauAbs = measSteps > 0 ? tauAbsAcc/measSteps : 0;
            r.cancel = Math.abs(r.tau) > 1e-30 ? tauAbs/Math.abs(r.tau) : 0;
            r.avgBound = measSteps > 0 ? boundAcc/measSteps : 0;
            r.tauPerHead = r.avgBound > 0 ? r.tau/r.avgBound : 0;
            r.ft = nBoundSamp > 0 ? ftAcc/nBoundSamp : 0;
            r.fax = nBoundSamp > 0 ? faxAcc/nBoundSamp : 0;
            r.misAbs = nBoundSamp > 0 ? misAcc/nBoundSamp : 0;
            r.bindsPerStep = measSteps > 0 ? (double) binds/measSteps : 0;
            r.detachPerStep = measSteps > 0 ? (double) detach/measSteps : 0;
            r.strokesPerStep = measSteps > 0 ? (double) strokes/measSteps : 0;
            r.tauPerStroke = strokes > 0 ? tauAcc/strokes : 0;
            for (int b = 0; b < AGE_BINS; b++) if (r.ageN[b] > 0) r.ageTau[b] /= r.ageN[b];   // → per-head mean
            r.meanResidenceSteps = r.detachPerStep > 0 ? r.avgBound/r.detachPerStep : 0;
            r.omegaPred = r.gammaRoll > 0 ? r.tau/r.gammaRoll : 0;
            r.qOmega = Math.abs(r.omegaPred) > 1e-30 ? r.omega/r.omegaPred : 0;
            r.coherence = sdRoll > 1e-12 ? Math.abs(meanRollEnd - rollAtEquil)/sdRoll : (nSeg == 1 ? Double.POSITIVE_INFINITY : 0);
            r.blkTau = new double[blocks.size()]; r.blkOmega = new double[blocks.size()];
            r.blkGlide = new double[blocks.size()]; r.blkBound = new double[blocks.size()];
            for (int i = 0; i < blocks.size(); i++) { r.blkTau[i] = blocks.get(i)[0]; r.blkOmega[i] = blocks.get(i)[1];
                r.blkGlide[i] = blocks.get(i)[2]; r.blkBound[i] = blocks.get(i)[3]; }
            r.tauBlkSem = ms(r.blkTau)[1]; r.omegaBlkSem = ms(r.blkOmega)[1];
            r.rollR2 = r2(trace, 1);
            for (int i = 0; i < 3*nSeg; i++) if (!Float.isFinite(f.coord.get(i))) r.invalid++;
            if (GPU) {
                TornadoCrashDiagnostic.executeLoopEnd("twirlArm=" + a.tag + " seed=" + seed);
                TornadoCrashDiagnostic.gpuWorkDeclaredFinished("twirlArm=" + a.tag);
                TornadoCrashDiagnostic.closePlan(plan, "graph=glide twirlArm=" + a.tag);
            }
            return r;
        } finally {
            FIL_SEGS = savedSegs; FIL_BROWN = savedBrown; R_NM = savedR;
            ExplicitCompleteMatHarness.PROD_SCI = savedSci;
        }
    }
    /** R² of a straight-line fit of column `col` of the trace against its time column (linearity of accumulated roll). */
    static double r2(java.util.List<double[]> tr, int col) {
        int n = tr.size(); if (n < 3) return Double.NaN;
        double sx = 0, sy = 0, sxx = 0, sxy = 0, syy = 0;
        for (double[] p : tr) { sx += p[0]; sy += p[col]; sxx += p[0]*p[0]; sxy += p[0]*p[col]; syy += p[col]*p[col]; }
        double dxx = sxx - sx*sx/n, dyy = syy - sy*sy/n, dxy = sxy - sx*sy/n;
        return (dxx > 0 && dyy > 0) ? (dxy*dxy)/(dxx*dyy) : Double.NaN;
    }

    static TRes[] runTwirlSeeds(TArm a) {
        TRes[] out = new TRes[SEEDS];
        for (int i = 0; i < SEEDS; i++) out[i] = runTwirlArm(a, SEED + i, STEPS);
        return out;
    }
    static void tHeader() {
        System.out.printf("  %-30s %12s %11s %11s %7s %9s %11s %6s %8s %8s%n",
                "arm", "tau N·m", "omega rad/s", "pred tau/g", "Qomega", "turns", "glide µm/s", "avgB", "cancel", "misRMS");
    }
    static double[] col(TRes[] rs, java.util.function.ToDoubleFunction<TRes> g) {
        double[] a = new double[rs.length]; for (int i = 0; i < rs.length; i++) a[i] = g.applyAsDouble(rs[i]); return a; }
    static void tReport(String tag, TRes[] rs) {
        double[] tau = ms(col(rs, x -> x.tau)), om = ms(col(rs, x -> x.omega)), pr = ms(col(rs, x -> x.omegaPred));
        double[] tu = ms(col(rs, x -> x.turns)), gl = ms(col(rs, x -> x.glide)), ab = ms(col(rs, x -> x.avgBound));
        double[] ca = ms(col(rs, x -> x.cancel)), mi = ms(col(rs, x -> x.misAbs));
        double q = Math.abs(pr[0]) > 1e-30 ? om[0]/pr[0] : 0;
        int sameTau = 0, sameOm = 0;
        for (TRes x : rs) { if (x.tau*tau[0] > 0) sameTau++; if (x.omega*om[0] > 0) sameOm++; }
        System.out.printf(Locale.US, "  %-30s %+.3e %+.4e %+.4e %7.3f %+.5f %+7.3f %6.2f %8.1f %8.4f%n",
                tag, tau[0], om[0], pr[0], q, tu[0], gl[0], ab[0], ca[0], mi[0]);
        System.out.printf(Locale.US, "  %-30s   ±%.1e   ±%.1e (%.1f sig)          ±%.5f  ±%.3f   "
                + "seedsSameSign tau=%d/%d omega=%d/%d%n", "", tau[1], om[1],
                om[1] > 0 ? Math.abs(om[0]/om[1]) : 0, tu[1], gl[1], sameTau, rs.length, sameOm, rs.length);
        double[] bi = ms(col(rs, x -> x.bindsPerStep)), de = ms(col(rs, x -> x.detachPerStep));
        double[] ft = ms(col(rs, x -> x.ft)), fx = ms(col(rs, x -> x.fax)), th = ms(col(rs, x -> x.tauPerHead));
        double[] st = ms(col(rs, x -> x.strokesPerStep)), ts = ms(col(rs, x -> x.tauPerStroke));
        double[] r2c = ms(col(rs, x -> x.rollR2)), tbs = ms(col(rs, x -> x.tauBlkSem)), obs = ms(col(rs, x -> x.omegaBlkSem));
        int inv = 0, sf = 0; for (TRes x : rs) { inv += x.invalid; sf += x.solverFail; }
        System.out.printf(Locale.US, "  %-30s   F_t=%+.3e N  F_ax=%+.3e N  tau/head=%+.3e  binds/step=%.4f  detach/step=%.4f%n",
                "", ft[0], fx[0], th[0], bi[0], de[0]);
        double[] ol = ms(col(rs, x -> x.omegaLegacy)), tl = ms(col(rs, x -> x.turnsLegacy)), co = ms(col(rs, x -> x.coherence));
        System.out.printf(Locale.US, "  %-30s   strokes/step=%.4f  tau/stroke=%+.3e N·m  gammaRoll=%.4e N·m·s  "
                + "roll-vs-t R²=%.4f  blockSEM tau=%.1e omega=%.1e  invalid=%d solverFail=%d%n",
                "", st[0], ts[0], rs[0].gammaRoll, r2c[0], tbs[0], obs[0], inv, sf);
        System.out.printf(Locale.US, "  %-30s   [diagnostic, ILL-CONDITIONED lab-referenced readout: omega=%+.3e rad/s "
                + "turns=%+.4f]%s%n", "", ol[0], tl[0],
                rs[0].nSeg > 1 ? String.format(Locale.US, "  coherentRoll=|mean|/SD=%.3f", co[0]) : "");
    }
    /** The eps-ODD / eps-EVEN matched-seed paired statistics — the PRINCIPAL result. */
    static void tPaired(String name, TRes[] rp, TRes[] rm) {
        int n = Math.min(rp.length, rm.length);
        double[] dTau = new double[n], dOm = new double[n], dTu = new double[n], dGl = new double[n],
                 eGl = new double[n], dFt = new double[n], eFx = new double[n], dQ = new double[n];
        double gam = rp[0].gammaRoll;
        for (int i = 0; i < n; i++) {
            dTau[i] = 0.5*(rp[i].tau   - rm[i].tau);
            dOm[i]  = 0.5*(rp[i].omega - rm[i].omega);
            dTu[i]  = 0.5*(rp[i].turns - rm[i].turns);
            dGl[i]  = 0.5*(rp[i].glide - rm[i].glide);
            eGl[i]  = 0.5*(rp[i].glide + rm[i].glide);
            dFt[i]  = 0.5*(rp[i].ft    - rm[i].ft);
            eFx[i]  = 0.5*(rp[i].fax   + rm[i].fax);
            dQ[i]   = dOm[i];
        }
        double[] t = ms(dTau), o = ms(dOm), u = ms(dTu), g = ms(dGl), ge = ms(eGl), fq = ms(dFt), fe = ms(eFx);
        double omPred = t[0]/gam, qOdd = Math.abs(omPred) > 1e-30 ? o[0]/omPred : 0;
        int sT = 0, sO = 0; for (int i = 0; i < n; i++) { if (dTau[i]*t[0] > 0) sT++; if (dOm[i]*o[0] > 0) sO++; }
        System.out.printf(Locale.US, "  >> ODD %-26s tauOdd=%+.4e ± %.1e N·m (%.2f sig, %d/%d same sign)%n",
                name, t[0], t[1], t[1] > 0 ? Math.abs(t[0]/t[1]) : 0, sT, n);
        System.out.printf(Locale.US, "     %-26s omegaOdd=%+.4e ± %.1e rad/s (%.2f sig, %d/%d same sign) | "
                + "predicted tauOdd/gammaRoll = %+.4e ⇒ Q_omega(odd) = %.3f%n",
                "", o[0], o[1], o[1] > 0 ? Math.abs(o[0]/o[1]) : 0, sO, n, omPred, qOdd);
        System.out.printf(Locale.US, "     %-26s turnsOdd=%+.5f ± %.5f | vOdd=%+.4f ± %.4f µm/s | vEven=%+.4f ± %.4f µm/s | "
                + "F_tOdd=%+.3e N | F_axEven=%+.3e N%n",
                "", u[0], u[1], g[0], g[1], ge[0], ge[1], fq[0], fe[0]);
        ageTable(name, rp, rm);
    }
    /**
     * AGE-RESOLVED eps-ODD per-head axial torque. If the frozen askew response is real but RELAXES during an
     * attachment (the T5 hypothesis), the odd torque is large in the first steps after binding and decays; if it is
     * persistent, the profile is flat. Pooled over seeds (per-head means weighted by sample count).
     */
    static void ageTable(String name, TRes[] rp, TRes[] rm) {
        int n = Math.min(rp.length, rm.length);
        double[] sp = new double[AGE_BINS], sm = new double[AGE_BINS];
        long[] np = new long[AGE_BINS], nm = new long[AGE_BINS];
        for (int i = 0; i < n; i++) for (int b = 0; b < AGE_BINS; b++) {
            sp[b] += rp[i].ageTau[b]*rp[i].ageN[b]; np[b] += rp[i].ageN[b];
            sm[b] += rm[i].ageTau[b]*rm[i].ageN[b]; nm[b] += rm[i].ageN[b];
        }
        double res = 0; for (int i = 0; i < n; i++) res += rp[i].meanResidenceSteps + rm[i].meanResidenceSteps;
        res /= 2*n;
        StringBuilder h = new StringBuilder(String.format("     %-26s age-resolved ODD tau/head by attachment age (steps): ", ""));
        StringBuilder v = new StringBuilder();
        for (int b = 0; b < AGE_BINS; b++) {
            if (np[b] == 0 || nm[b] == 0) continue;
            h.append(String.format("%10s", AGE_LABEL[b]));
            v.append(String.format(Locale.US, "%10.2e", 0.5*(sp[b]/np[b] - sm[b]/nm[b])));
        }
        System.out.println(h);
        System.out.printf(Locale.US, "     %-26s %s   [mean residence %.0f steps = %.3f ms]%n", "", v, res, res*DTR*1e3);
    }

    // ------------------------------------------------------------------------------------------- audit gates
    static boolean runTwirlAudit() {
        passN = failN = 0;
        System.out.println("\n--- TWIRL AUDIT: one-segment scene, Brownian-channel accounting, drag, controls ---");

        // (T1) single-segment geometry: exactly one mechanical segment, full contour preserved, no neighbours.
        FIL_SEGS = 1; cfg(2, true, 0, 0, 0, false, 1.0, true);
        Glide2D G1 = build(SEED); FilamentStore f1 = G1.fil;
        FIL_SEGS = TwoBodyConverterMotor.G4_NSEG; cfg(2, true, 0, 0, 0, false, 1.0, true);
        Glide2D Gc = build(SEED); FilamentStore fc = Gc.fil;
        double contour1 = f1.segLength.get(0), contourC = 0;
        for (int s = 0; s < Gc.nSeg; s++) contourC += fc.segLength.get(s);
        boolean geomOk = G1.nSeg == 1 && f1.end1NbrSlot.get(0) == FilamentStore.SENTINEL_NO_NBR
                && f1.end2NbrSlot.get(0) == FilamentStore.SENTINEL_NO_NBR && G1.rigid
                && Math.abs(contour1 - contourC) < 1e-3;
        note(String.format(Locale.US, "one-segment contour = %.4f µm vs the %d-segment chain contour %.4f µm (Δ=%.2e); "
                + "neighbours = (%d,%d) ⇒ no joints/bending/intersegment torsion", contour1, Gc.nSeg, contourC,
                Math.abs(contour1-contourC), f1.end1NbrSlot.get(0), f1.end2NbrSlot.get(0)));
        ck(101, "single mechanical segment carrying the FULL filament contour", geomOk);

        // (T2) drag audit: the roll drag of the one rod == the SUM of the chain's per-segment roll drags (roll drag
        // is 4*pi*eta*R^2*L ⇒ additive in L). This is the check that the diagnostic does NOT reuse a short segment's drag.
        double gRoll1 = f1.bRotGam.get(0), gRollSum = 0;
        for (int s = 0; s < Gc.nSeg; s++) gRollSum += fc.bRotGam.get(s);
        note(String.format(Locale.US, "gammaRoll: one rod = %.6e N·m·s ; Σ chain segments = %.6e (ratio %.6f)",
                gRoll1, gRollSum, gRoll1/gRollSum));
        ck(102, "one-segment roll drag == the whole filament's roll drag (not one short segment's)",
                Math.abs(gRoll1/gRollSum - 1.0) < 2e-3);
        System.out.println();
        FIL_SEGS = 1; dragAudit("single rigid rod:"); FIL_SEGS = TwoBodyConverterMotor.G4_NSEG; dragAudit("canonical chain:");
        System.out.println();

        // (T3) filament Brownian OFF ⇒ EVERY stochastic filament increment is EXACTLY zero, every step.
        FIL_SEGS = 1; FIL_BROWN = false; cfg(2, true, 0, EPS_TWIRL_DEG, 0, true, 1.0, true);
        Glide2D Gb = build(SEED); var eb = ExplicitCompleteMatHarness.packExMat(Gb, 1);
        double maxRand = 0; boolean anyBoundB = false;
        for (int t = 0; t < 300; t++) {
            ExplicitCompleteMatHarness.stepGlidingCPU(eb, t, SEED);
            for (int i = 0; i < 3*Gb.nSeg; i++) {
                maxRand = Math.max(maxRand, Math.abs(Gb.fil.randForce.get(i)));
                maxRand = Math.max(maxRand, Math.abs(Gb.fil.randTorque.get(i)));
            }
            for (int m = 0; m < Gb.N; m++) if (Gb.mot.boundSeg.get(m) >= 0) anyBoundB = true;
        }
        note(String.format(Locale.US, "max|randForce| and max|randTorque| over 300 steps × all channels = %.3e "
                + "(mask task wired = %b)", maxRand, ExplicitCompleteMatHarness.brownChanOn()));
        ck(103, "filament Brownian OFF ⇒ all four channels EXACTLY zero (force and torque)",
                maxRand == 0.0 && ExplicitCompleteMatHarness.brownChanOn());

        // (T4) with filament Brownian OFF the MOTOR Brownian is still active: replay matS2SolveStep from the
        // identical post-step state with the stochastic term on vs off and require a nonzero difference.
        double motPert = motorBrownianPerturbation(eb, Gb, 300, SEED);
        note(String.format(Locale.US, "matc[2]=%d (motor Brownian on) matc[3]=%d (binding-state policy, 0 = canonical) ; "
                + "max|Δbeam node| from the stochastic RHS alone = %.3e µm", eb.matc.get(2), eb.matc.get(3), motPert));
        ck(104, "motor/S2 Brownian increments remain NONZERO (and no binding-state quieting)",
                motPert > 0.0 && eb.matc.get(2) == 1 && eb.matc.get(3) == 0);

        // (T5) the assay still binds and still glides with one segment and no filament noise.
        ck(105, "one-segment Brownian-off scene still recruits heads (binding healthy)", anyBoundB);

        // (T6) deterministic one-segment fixture: filament Brownian off ⇒ the trajectory is REPRODUCIBLE and finite.
        double[] hA = twirlHash(SEED, 200), hB = twirlHash(SEED, 200);
        note(String.format(Locale.US, "200-step one-segment Brownian-off coord hash = %.17g (repeat %.17g)", hA[0], hB[0]));
        ck(106, "one-segment Brownian-off trajectory is finite and bit-reproducible", hA[0] == hB[0] && hA[2] == 0);

        // (T7) Ractin = 0 torque-arm control: tau = Ractin*F_t ⇒ the axial torque must vanish identically.
        TArm zeroR = new TArm("R=0", 2, true, 0, EPS_TWIRL_DEG, 0, true, 1.0, false, 1, 0.0);
        TRes rz = runTwirlArm(zeroR, SEED, Math.min(STEPS, 2000));
        note(String.format(Locale.US, "Ractin = 0, eps = +%.1f deg ⇒ mean axial torque = %.3e N·m, body-fixed omega = "
                + "%.3e rad/s (predicted tau/gammaRoll = %.3e), bound %.2f", EPS_TWIRL_DEG, rz.tau, rz.omega,
                rz.omegaPred, rz.avgBound));
        note(String.format(Locale.US, "the LEGACY lab-referenced readout on the SAME run gives omega = %.3e rad/s — "
                + "a %.0f× spurious signal with ZERO torque (b̂·û ≈ 1 ⇒ near-degenerate reference); this is why the "
                + "parallel-transported body-fixed measure is the primary observable", rz.omegaLegacy,
                Math.abs(rz.omega) > 0 ? Math.abs(rz.omegaLegacy/rz.omega) : 0));
        ck(107, "Ractin = 0 ⇒ axial torque AND body-fixed roll both vanish (the moment arm IS Ractin)",
                Math.abs(rz.tau) < 1e-26 && Math.abs(rz.omega) < 1e-2);

        // (T9) prescribed PURE TUMBLE (no material spin): the transported measure reads ~0, the legacy one does not.
        double[] tum = tumbleProbe();
        note(String.format(Locale.US, "prescribed rigid tumble of 0.02 rad about an axis ⊥ û, NO material spin ⇒ "
                + "transported roll = %.3e rad ; legacy lab-referenced roll = %.4e rad", tum[0], tum[1]));
        ck(109, "prescribed pure tumble ⇒ transported body-fixed roll ~0 (legacy readout contaminated)",
                Math.abs(tum[0]) < 1e-5 && Math.abs(tum[1]) > 1e-3);

        // (T10) prescribed PURE MATERIAL ROLL of +0.40 rad ⇒ the transported measure reads exactly +0.40.
        double spin = spinProbe(0.40);
        note(String.format(Locale.US, "prescribed material roll +0.40 rad ⇒ transported measure = %.6f rad", spin));
        ck(110, "prescribed pure material roll is recovered exactly by the transported measure",
                Math.abs(spin - 0.40) < 1e-4);

        // (T8) roll spring / torsional registry: structurally absent AND, at nSeg = 1, inexpressible.
        //   - RollSpringSystem is named ONLY by RollSpringHarness / HelicalSurfaceTwirlHarness / GlidingHarness;
        //     neither ExplicitCompleteMatHarness (the assay step + the device graph) nor ChiralSiteHarness names it.
        //   - chainParams[4] (the PAIRS filament torsion spring flag) is 0 = inactive, and with one segment the
        //     chain kernel has no neighbour to torque against at all.
        boolean noRollSpring = f1.chainParams.get(4) == 0f && G1.nSeg == 1
                && ExplicitCompleteMatHarness.REG_K == 0.0;
        note(String.format(Locale.US, "chainParams[4] (filament torsion spring active) = %.1f ; nSeg = %d ; "
                + "bound-registry K = %.1e ⇒ no torsional registry anywhere in the roll channel",
                f1.chainParams.get(4), G1.nSeg, ExplicitCompleteMatHarness.REG_K));
        ck(108, "RollSpringSystem / filament torsion spring / bound registry all OFF", noRollSpring);

        FIL_BROWN = true; FIL_SEGS = TwoBodyConverterMotor.G4_NSEG; cfgOff();
        System.out.printf("%nTwirl audit: %d PASS, %d FAIL%n", passN, failN);
        return failN == 0;
    }
    /** {transported roll, legacy lab-referenced roll} accumulated over a prescribed pure rigid TUMBLE (no spin). */
    static double[] tumbleProbe() {
        FIL_SEGS = 1; cfg(2, false, 0, 0, 0, false, 1.0, false);
        Glide2D G = build(SEED); FilamentStore f = G.fil;
        double[] prevY = new double[3]; seedPrevY(f, 0, prevY);
        double prevLeg = ExplicitTwirlGlidingHarness.rollAngle(f, 0, G.bhat), cumT = 0, cumL = 0;
        double[] ax = nrm(new double[]{ 0, 0.6, 0.8 });   // an axis ⊥ û(=+x) ⇒ a pure tumble, zero spin about û
        for (int k = 0; k < 40; k++) {
            double[] u = rot(new double[]{ f.uVec.get(0), f.uVec.get(1), f.uVec.get(2) }, ax, 5e-4);
            double[] y = rot(new double[]{ f.yVec.get(0), f.yVec.get(1), f.yVec.get(2) }, ax, 5e-4);
            f.uVec.set(0, (float)u[0]); f.uVec.set(1, (float)u[1]); f.uVec.set(2, (float)u[2]);
            f.yVec.set(0, (float)y[0]); f.yVec.set(1, (float)y[1]); f.yVec.set(2, (float)y[2]);
            cumT += rollIncrementTransported(f, 0, prevY);
            double rl = ExplicitTwirlGlidingHarness.rollAngle(f, 0, G.bhat);
            cumL += ExplicitTwirlGlidingHarness.wrapPi(rl - prevLeg); prevLeg = rl;
        }
        return new double[]{ cumT, cumL };
    }
    /** Transported roll recovered from a prescribed PURE material spin of `ang` rad about û (applied in 40 steps). */
    static double spinProbe(double ang) {
        FIL_SEGS = 1; cfg(2, false, 0, 0, 0, false, 1.0, false);
        Glide2D G = build(SEED); FilamentStore f = G.fil;
        double[] prevY = new double[3]; seedPrevY(f, 0, prevY);
        double cumT = 0;
        for (int k = 0; k < 40; k++) {
            double[] u = { f.uVec.get(0), f.uVec.get(1), f.uVec.get(2) };
            double[] y = rot(new double[]{ f.yVec.get(0), f.yVec.get(1), f.yVec.get(2) }, u, ang/40.0);
            f.yVec.set(0, (float)y[0]); f.yVec.set(1, (float)y[1]); f.yVec.set(2, (float)y[2]);
            cumT += rollIncrementTransported(f, 0, prevY);
        }
        return cumT;
    }

    /** Max |Δ beam node| produced by the stochastic RHS alone, replayed from the current state on private copies. */
    static double motorBrownianPerturbation(ExplicitCompleteMatHarness.ExMat e, Glide2D G, int t, int seed) {
        int N = e.N, nodeStride = 3*(e.M+1);
        DoubleArray nOn = copyD(e.nodes), nOff = copyD(e.nodes);
        DoubleArray qOn = copyD(e.q), qOff = copyD(e.q);
        DoubleArray sOn = copyD(e.sys), sOff = copyD(e.sys);
        DoubleArray gOn = copyD(e.outGeom), gOff = copyD(e.outGeom);
        FloatArray fdOn = copyF(G.mot.forceDotFil), fdOff = copyF(G.mot.forceDotFil);
        FloatArray fmOn = copyF(G.mot.forceMag), fmOff = copyF(G.mot.forceMag);
        IntArray mcOn = IntArray.fromElements(t, seed, 1, 0), mcOff = IntArray.fromElements(t, seed, 0, 0);
        TwoBodyBeamAnalyticGpu.matS2SolveStep(nOn, e.frame, qOn, G.bondData, G.mot.boundSeg, e.params, sOn, gOn, fdOn, fmOn, mcOn, e.exCounts);
        TwoBodyBeamAnalyticGpu.matS2SolveStep(nOff, e.frame, qOff, G.bondData, G.mot.boundSeg, e.params, sOff, gOff, fdOff, fmOff, mcOff, e.exCounts);
        double worst = 0;
        for (int i = 0; i < nodeStride*N; i++) worst = Math.max(worst, Math.abs(nOn.get(i) - nOff.get(i)));
        return worst;
    }
    static DoubleArray copyD(DoubleArray a) { DoubleArray o = new DoubleArray(a.getSize()); for (int i = 0; i < a.getSize(); i++) o.set(i, a.get(i)); return o; }
    static FloatArray  copyF(FloatArray a)  { FloatArray o = new FloatArray(a.getSize());  for (int i = 0; i < a.getSize(); i++) o.set(i, a.get(i)); return o; }
    static double[] twirlHash(int seed, int K) {
        Glide2D G = build(seed); FilamentStore f = G.fil;
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        for (int t = 0; t < K; t++) ExplicitCompleteMatHarness.stepGlidingCPU(e, t, seed);
        double hc = 0, hy = 0; boolean nan = false;
        for (int i = 0; i < 3*G.nSeg; i++) { float v = f.coord.get(i); if (!Float.isFinite(v)) nan = true; hc = hc*1.0000001 + v; }
        for (int i = 0; i < 3*G.nSeg; i++) hy = hy*1.0000001 + f.yVec.get(i);
        return new double[]{ hc, hy, nan ? 1 : 0 };
    }

    // ------------------------------------------------------------------------------------ CPU/GPU on the assay
    static boolean runTwirlEquiv() {
        System.out.println("\n--- CPU/GPU EQUIVALENCE — one-segment, filament-Brownian-off twirl graph, device-resident ---");
        FIL_SEGS = 1; FIL_BROWN = false;
        boolean savedGpu = GPU; GPU = false;   // cfg() must not force TELEMETRY on the validation graph
        cfg(2, true, 0.0, EPS_TWIRL_DEG, 0, true, 1.0, true);
        GPU = savedGpu;
        System.out.println("  config: " + ExplicitCompleteMatHarness.chiralConfigString());
        System.out.println("  brownian: " + ExplicitCompleteMatHarness.brownianPolicyString());
        Glide2D Gc = build(101), Gd = build(101);
        var ec = ExplicitCompleteMatHarness.packExMat(Gc, 1);
        var ed = ExplicitCompleteMatHarness.packExMat(Gd, 1);
        TornadoExecutionPlan plan;
        TornadoCrashDiagnostic.planConstructionBegin("graph=buildGlidingGraph(twirl 1-seg, filBrown OFF) arm=twirl-equiv");
        try { plan = ExplicitCompleteMatHarness.buildGlidingGraph(ed, false); }
        catch (Throwable ex) { TornadoCrashDiagnostic.planConstructionThrew(ex);
            System.out.println("  FULL twirl graph did NOT lower: " + oneLine(root(ex).getMessage())); return false; }
        TornadoCrashDiagnostic.planConstructionEnd(plan, "arm=twirl-equiv");
        int K = 300, firstDiv = -1, siteMism = 0, bindMism = 0;
        double maxFil = 0, maxOm = 0, maxTau = 0, maxAz = 0, maxRoll = 0, maxAxTau = 0, maxAbsAx = 0, maxRandC = 0, maxRandD = 0;
        boolean lowered = true;
        double[] bhat = Gc.bhat;
        double[] pyC = new double[3], pyD = new double[3]; double cumC = 0, cumD = 0;
        seedPrevY(Gc.fil, 0, pyC); seedPrevY(Gd.fil, 0, pyD);
        TornadoCrashDiagnostic.executeLoopBegin("glide", 0, K-1, "arm=twirl-equiv executeCallsPlanned=" + K);
        for (int t = 0; t < K; t++) {
            ed.matc.set(0, t); ed.matc.set(1, 101); Gd.mot.setCounts(t, 101, Gd.nSeg); Gd.fil.counts.set(1, t); Gd.fil.counts.set(2, 101);
            try { TornadoCrashDiagnostic.beforeExecute(t); plan.execute(); TornadoCrashDiagnostic.afterExecute(t); }
            catch (Throwable ex) { TornadoCrashDiagnostic.executeThrew(ex); lowered = false;
                System.out.println("  device execute FAILED @t=" + t + ": " + oneLine(root(ex).getMessage())); break; }
            ExplicitCompleteMatHarness.stepGlidingCPU(ec, t, 101);
            double dFil = 0;
            for (int i = 0; i < 3*Gc.nSeg; i++) dFil = Math.max(dFil, Math.abs(Gc.fil.coord.get(i) - Gd.fil.coord.get(i)));
            maxFil = Math.max(maxFil, dFil); if (firstDiv < 0 && dFil > 1e-6) firstDiv = t;
            cumC += rollIncrementTransported(Gc.fil, 0, pyC);   // body-fixed accumulated roll, both runners
            cumD += rollIncrementTransported(Gd.fil, 0, pyD);
            maxRoll = Math.max(maxRoll, Math.abs(cumC - cumD));
            for (int i = 0; i < 3*Gc.nSeg; i++) {
                maxRandC = Math.max(maxRandC, Math.max(Math.abs(Gc.fil.randForce.get(i)), Math.abs(Gc.fil.randTorque.get(i))));
                maxRandD = Math.max(maxRandD, Math.max(Math.abs(Gd.fil.randForce.get(i)), Math.abs(Gd.fil.randTorque.get(i))));
            }
            for (int m = 0; m < Gc.N; m++) {
                if (Gc.mot.boundSeg.get(m) != Gd.mot.boundSeg.get(m)) bindMism++;
                if (ec.bindSite.get(m) != ed.bindSite.get(m)) siteMism++;
                maxOm = Math.max(maxOm, Math.abs(ec.headOmega.get(m) - ed.headOmega.get(m)));
                maxTau = Math.max(maxTau, Math.abs(ec.headTau.get(m) - ed.headTau.get(m)));
                maxAz = Math.max(maxAz, Math.abs(Gc.mot.bindAzim.get(m) - Gd.mot.bindAzim.get(m)));
                double axC = ChiralSiteSystem.axialTorque(Gc.bondData, Gc.fil.uVec, Gc.mot.boundSeg, m, Gc.nSeg);
                maxAxTau = Math.max(maxAxTau, Math.abs(axC
                      - ChiralSiteSystem.axialTorque(Gd.bondData, Gd.fil.uVec, Gd.mot.boundSeg, m, Gd.nSeg)));
                maxAbsAx = Math.max(maxAbsAx, Math.abs(axC));
            }
        }
        TornadoCrashDiagnostic.executeLoopEnd("arm=twirl-equiv lowered=" + lowered);
        if (!lowered) { TornadoCrashDiagnostic.closePlan(plan, "graph=glide arm=twirl-equiv status=execute-failed"); return false; }
        int nbC = 0, nbD = 0; boolean fin = true;
        for (int m = 0; m < Gc.N; m++) { if (Gc.mot.boundSeg.get(m) >= 0) nbC++; if (Gd.mot.boundSeg.get(m) >= 0) nbD++; }
        for (int i = 0; i < 3*Gc.nSeg; i++) if (!Float.isFinite(Gd.fil.coord.get(i))) fin = false;
        // The axial torque is a NEAR-CANCELLING projection of float32 seg-torque components whose transverse parts are
        // ~1e-18 N·m, so its float32 floor is far above its own magnitude's eps. Gate it RELATIVE to the largest
        // per-head axial torque actually seen in the run (2 %), not on an absolute constant.
        double axTol = Math.max(1e-25, 0.02*maxAbsAx);
        boolean ok = lowered && fin && siteMism == 0 && bindMism == 0 && maxOm < 1e-5 && maxAz < 1e-5
                  && maxRoll < 1e-4 && maxAxTau < axTol && maxRandC == 0.0 && maxRandD == 0.0;
        System.out.printf(Locale.US,
                "  %d device-resident steps (nSeg=%d, filament Brownian OFF): siteIdMism=%d bindMism=%d "
                + "max|dAzim|=%.2e max|dOmega|=%.2e max|dHeadTau|=%.2e max|dAxialTau|=%.2e N·m (max|axialTau|=%.2e, "
                + "tol %.2e) max|dCumRoll|=%.2e rad max|dFilCoord|=%.2e µm firstDiv=%s masked|rand| CPU=%.1e GPU=%.1e "
                + "bound CPU=%d GPU=%d finite=%b ⇒ %s%n",
                K, Gc.nSeg, siteMism, bindMism, maxAz, maxOm, maxTau, maxAxTau, maxAbsAx, axTol, maxRoll, maxFil,
                firstDiv < 0 ? "none (bit-close)" : ("t=" + firstDiv), maxRandC, maxRandD, nbC, nbD, fin,
                ok ? "PASS (device-resident, no fallback)" : "*FAIL*");
        TornadoCrashDiagnostic.gpuWorkDeclaredFinished("arm=twirl-equiv");
        TornadoCrashDiagnostic.closePlan(plan, "graph=glide arm=twirl-equiv");
        FIL_SEGS = TwoBodyConverterMotor.G4_NSEG; FIL_BROWN = true; cfgOff();
        return ok;
    }

    // --------------------------------------------------------------------------------------------- pilot
    static void runTwirlPilot() {
        System.out.println("\n--- TWIRL PILOT (health, finiteness, sign; runner: " + (GPU ? "GPU device-resident" : "CPU sequential") + ") ---");
        FIL_SEGS = 1; FIL_BROWN = false;
        tHeader();
        TArm[] arms = {
            new TArm("R0  every3 rand base eps=0", 0.0, true, +1, false, 1),
            new TArm("R+  every3 rand base eps=+", +EPS_TWIRL_DEG, true, +1, false, 1),
            new TArm("R-  every3 rand base eps=-", -EPS_TWIRL_DEG, true, +1, false, 1),
            new TArm("RB+ rand base eps=+ filBrownON", +EPS_TWIRL_DEG, true, +1, true, 1),
            new TArm("RB- rand base eps=- filBrownON", -EPS_TWIRL_DEG, true, +1, true, 1),
        };
        TRes[][] res = new TRes[arms.length][];
        for (int i = 0; i < arms.length; i++) { res[i] = runTwirlSeeds(arms[i]); tReport(arms[i].tag, res[i]); }
        tPaired("pilot randomized-base (filament Brownian OFF)", res[1], res[2]);
        tPaired("filament Brownian ON control", res[3], res[4]);
        System.out.println("\n  stationarity (per-block means, measurement window only):");
        for (int i = 0; i < arms.length; i++) blockTrace(arms[i].tag, res[i]);
        FIL_SEGS = TwoBodyConverterMotor.G4_NSEG; FIL_BROWN = true; cfgOff();
    }
    static void blockTrace(String tag, TRes[] rs) {
        int nb = rs[0].blkTau.length;
        double[] bt = new double[nb], bo = new double[nb], bg = new double[nb], bb = new double[nb];
        for (TRes r : rs) for (int b = 0; b < nb; b++) { bt[b] += r.blkTau[b]/rs.length; bo[b] += r.blkOmega[b]/rs.length;
            bg[b] += r.blkGlide[b]/rs.length; bb[b] += r.blkBound[b]/rs.length; }
        StringBuilder s = new StringBuilder();
        s.append(String.format("    %-30s tau ", tag)); for (double v : bt) s.append(String.format(Locale.US, "%+.2e ", v));
        s.append("| omega "); for (double v : bo) s.append(String.format(Locale.US, "%+.2e ", v));
        s.append("| glide "); for (double v : bg) s.append(String.format(Locale.US, "%+.2f ", v));
        s.append("| avgB ");  for (double v : bb) s.append(String.format(Locale.US, "%.2f ", v));
        System.out.println(s);
    }

    // ------------------------------------------------------------------------------------- powered campaign
    static void runTwirlCampaign() {
        System.out.println("\n--- TWIRL CAMPAIGN (matched seeds; target-zone OFF; roll spring OFF; registry K=0; "
                + "runner: " + (GPU ? "GPU device-resident" : "CPU sequential") + ") ---");
        double eps = EPS_TWIRL_DEG;
        FIL_SEGS = 1; FIL_BROWN = false; dragAudit("assay filament:"); System.out.println();
        tHeader();
        // ---- primary: randomized per-motor base azimuths, one segment, filament Brownian OFF
        TArm R0  = new TArm("R0  rand base   eps=0",   0.0,  true,  +1, false, 1);
        TArm Rp  = new TArm("R+  rand base   eps=+",  +eps,  true,  +1, false, 1);
        TArm Rm  = new TArm("R-  rand base   eps=-",  -eps,  true,  +1, false, 1);
        TArm RMp = new TArm("RM+ rand MIRROR eps=+",  +eps,  true,  -1, false, 1);
        TArm RMm = new TArm("RM- rand MIRROR eps=-",  -eps,  true,  -1, false, 1);
        // ---- shared-base comparison arm (higher engagement reference)
        TArm S0  = new TArm("S0  shared base eps=0",   0.0,  false, +1, false, 1);
        TArm Sp  = new TArm("S+  shared base eps=+",  +eps,  false, +1, false, 1);
        TArm Sm  = new TArm("S-  shared base eps=-",  -eps,  false, +1, false, 1);
        // ---- filament-Brownian ON control (one segment) and multisegment Brownian-OFF control
        TArm RBp = new TArm("RB+ rand base   eps=+ filBrownON", +eps, true, +1, true,  1);
        TArm RBm = new TArm("RB- rand base   eps=- filBrownON", -eps, true, +1, true,  1);
        TArm MSp = new TArm("MS+ rand base   eps=+ 12-seg",     +eps, true, +1, false, TwoBodyConverterMotor.G4_NSEG);
        TArm MSm = new TArm("MS- rand base   eps=- 12-seg",     -eps, true, +1, false, TwoBodyConverterMotor.G4_NSEG);

        TRes[] r0 = runTwirlSeeds(R0);  tReport(R0.tag, r0);
        TRes[] rp = runTwirlSeeds(Rp);  tReport(Rp.tag, rp);
        TRes[] rm = runTwirlSeeds(Rm);  tReport(Rm.tag, rm);
        tPaired("PRIMARY randomized", rp, rm);
        TRes[] mp = runTwirlSeeds(RMp); tReport(RMp.tag, mp);
        TRes[] mm = runTwirlSeeds(RMm); tReport(RMm.tag, mm);
        tPaired("MIRRORED lattice", mp, mm);
        TRes[] s0 = runTwirlSeeds(S0);  tReport(S0.tag, s0);
        TRes[] sp = runTwirlSeeds(Sp);  tReport(Sp.tag, sp);
        TRes[] sm = runTwirlSeeds(Sm);  tReport(Sm.tag, sm);
        tPaired("SHARED base", sp, sm);
        TRes[] bp = runTwirlSeeds(RBp); tReport(RBp.tag, bp);
        TRes[] bm = runTwirlSeeds(RBm); tReport(RBm.tag, bm);
        tPaired("filament Brownian ON", bp, bm);
        TRes[] xp = runTwirlSeeds(MSp); tReport(MSp.tag, xp);
        TRes[] xm = runTwirlSeeds(MSm); tReport(MSm.tag, xm);
        tPaired("MULTISEGMENT Brownian-off", xp, xm);

        System.out.println("\n  stationarity (per-block means over the measurement window):");
        blockTrace(R0.tag, r0); blockTrace(Rp.tag, rp); blockTrace(Rm.tag, rm);
        blockTrace(RMp.tag, mp); blockTrace(RMm.tag, mm);
        blockTrace(Sp.tag, sp); blockTrace(Sm.tag, sm);
        blockTrace(RBp.tag, bp); blockTrace(RBm.tag, bm);
        FIL_SEGS = TwoBodyConverterMotor.G4_NSEG; FIL_BROWN = true; cfgOff();
    }

    // ------------------------------------------------------------------------------------- timestep check
    static void runTwirlDtCheck() {
        System.out.println("\n--- TWIRL TIMESTEP CHECK (R+ / R-, current dt vs dt/2 at matched simulated time) ---");
        FIL_SEGS = 1; FIL_BROWN = false;
        double eps = EPS_TWIRL_DEG;
        int baseSteps = STEPS; double baseDt = DT;
        for (int half = 0; half < 2; half++) {
            DTR = half == 1 ? baseDt/2.0 : baseDt;
            STEPS = half == 1 ? baseSteps*2 : baseSteps;
            System.out.printf(Locale.US, "%n  dt = %.3e s, steps = %d (simulated %.4f ms)%n", DTR, STEPS, STEPS*DTR*1e3);
            tHeader();
            TArm p = new TArm("R+ dt" + (half==1?"/2":""), +eps, true, +1, false, 1);
            TArm m = new TArm("R- dt" + (half==1?"/2":""), -eps, true, +1, false, 1);
            TRes[] rp = runTwirlSeeds(p), rm2 = runTwirlSeeds(m);
            tReport(p.tag, rp); tReport(m.tag, rm2); tPaired("dt" + (half==1?"/2":""), rp, rm2);
        }
        DTR = baseDt; STEPS = baseSteps;
        FIL_SEGS = TwoBodyConverterMotor.G4_NSEG; FIL_BROWN = true; cfgOff();
    }

    // ------------------------------------------------------------------------------------- 3js (twirl arms)
    static void makeTwirlMovies(String dir) {
        System.out.println("\n--- 3js: one-segment Brownian-off twirl arms (R+, R-, RM+) ---");
        FIL_SEGS = 1; FIL_BROWN = false;
        String[] names = { dir + "_Rplus", dir + "_Rminus", dir + "_RMplus" };
        TArm[] arms = { new TArm("R+", +EPS_TWIRL_DEG, true, +1, false, 1),
                        new TArm("R-", -EPS_TWIRL_DEG, true, +1, false, 1),
                        new TArm("RM+", +EPS_TWIRL_DEG, true, -1, false, 1) };
        for (int i = 0; i < arms.length; i++) {
            TArm a = arms[i];
            cfg(a.mode, a.roll, a.k, a.epsB, a.epsS, a.rand, a.mirror, true);
            Glide2D G = build(SEED);
            var e = ExplicitCompleteMatHarness.packExMat(G, 1);
            java.io.File d = new java.io.File(names[i]); d.mkdirs();
            double[] py = new double[3]; seedPrevY(G.fil, 0, py); double cumRoll = 0;
            int frame = 0;
            for (int t = 0; t < STEPS; t++) {
                ExplicitCompleteMatHarness.stepGlidingCPU(e, t, SEED);
                cumRoll += rollIncrementTransported(G.fil, 0, py);
                if (t % STRIDE == 0) writeTwirlFrame(new java.io.File(d, String.format("frame%05d.json", frame++)), G, e, t, cumRoll);
            }
            System.out.printf(Locale.US, "  wrote %d frames to %s   (accumulated rigid-body roll %.4f rad = %.4f turns)%n",
                    frame, names[i], cumRoll, cumRoll/(2*Math.PI));
        }
        FIL_SEGS = TwoBodyConverterMotor.G4_NSEG; FIL_BROWN = true; cfgOff();
    }
    /**
     * Frame schema (v1 viewer): the actin rod, a ring of MATERIAL ROLL TICKS along it (so rigid-body rotation is
     * directly visible), the discrete sites near each bound head, the head→site bond coloured by the SIGN of that
     * head's axial torque, the head material-frame tick, and a total-axial-torque / accumulated-roll indicator.
     * All of it is non-force-bearing visualization state read from the already-pulled host pose.
     */
    static void writeTwirlFrame(java.io.File file, Glide2D G, ExplicitCompleteMatHarness.ExMat e, int t, double cumRoll) {
        FilamentStore f = G.fil; int nSeg = G.nSeg;
        StringBuilder b = new StringBuilder(1 << 16);
        b.append("{\n  \"time\": ").append(String.format(Locale.US, "%.6f", t*DTR)).append(",\n  \"segments\": [\n");
        for (int s = 0; s < nSeg; s++) {
            double half = 0.5*f.segLength.get(s);
            double cx = f.coord.get(s), cy = f.coord.get(nSeg+s), cz = f.coord.get(2*nSeg+s);
            double ux = f.uVec.get(s), uy = f.uVec.get(nSeg+s), uz = f.uVec.get(2*nSeg+s);
            b.append(String.format(Locale.US, "    {\"x1\":%.5f,\"y1\":%.5f,\"z1\":%.5f,\"x2\":%.5f,\"y2\":%.5f,\"z2\":%.5f,\"r\":0.0035,\"c\":\"#66ccff\"}%s%n",
                    cx-half*ux, cy-half*uy, cz-half*uz, cx+half*ux, cy+half*uy, cz+half*uz, s < nSeg-1 ? "," : ""));
        }
        b.append("  ],\n  \"myosins\": [\n");
        boolean first = true;
        // MATERIAL ROLL TICKS: 24 radial spokes on the filament material frame, evenly spaced along the contour.
        for (int s = 0; s < nSeg; s++) {
            int nT = Math.max(2, 24/nSeg);
            for (int k = 0; k < nT; k++) {
                double frac = (k + 0.5)/nT;
                double[] p = reconSite(f, s, frac*f.segLength.get(s), 0.0, 0.0035);
                double[] q = reconSite(f, s, frac*f.segLength.get(s), 0.0, 0.022);
                if (!first) b.append(",\n"); first = false;
                b.append(String.format(Locale.US, "    {\"x1\":%.5f,\"y1\":%.5f,\"z1\":%.5f,\"x2\":%.5f,\"y2\":%.5f,\"z2\":%.5f,\"r\":0.0006,\"c\":\"#8888ff\"}",
                        p[0], p[1], p[2], q[0], q[1], q[2]));
            }
        }
        double tauTot = 0;
        for (int m = 0; m < G.N; m++) {
            int s = G.mot.boundSeg.get(m); if (s < 0) continue;
            double[] site = reconSite(f, s, G.mot.bindArc.get(m), G.mot.bindAzim.get(m), R_NM*1e-3);
            double hx = e.outGeom.get(3*G.N+m), hy = e.outGeom.get(4*G.N+m), hz = e.outGeom.get(5*G.N+m);
            double tau = ChiralSiteSystem.axialTorque(G.bondData, f.uVec, G.mot.boundSeg, m, nSeg);
            tauTot += tau;
            String col = tau > 0 ? "#ff4444" : "#44ff44";
            if (!first) b.append(",\n"); first = false;
            b.append(String.format(Locale.US, "    {\"x1\":%.5f,\"y1\":%.5f,\"z1\":%.5f,\"x2\":%.5f,\"y2\":%.5f,\"z2\":%.5f,\"r\":0.0015,\"c\":\"%s\"}",
                    hx, hy, hz, site[0], site[1], site[2], col));
            double rx = e.headRef.get(m), ry = e.headRef.get(G.N+m), rz = e.headRef.get(2*G.N+m);
            b.append(String.format(Locale.US, ",\n    {\"x1\":%.5f,\"y1\":%.5f,\"z1\":%.5f,\"x2\":%.5f,\"y2\":%.5f,\"z2\":%.5f,\"r\":0.0008,\"c\":\"#ffdd33\"}",
                    hx, hy, hz, hx+0.012*rx, hy+0.012*ry, hz+0.012*rz));
            // torque-sign spoke at the bound site (outward = +tau, inward = -tau)
            double[] tip = reconSite(f, s, G.mot.bindArc.get(m), G.mot.bindAzim.get(m), R_NM*1e-3 + (tau > 0 ? 0.010 : -0.0025));
            b.append(String.format(Locale.US, ",\n    {\"x1\":%.5f,\"y1\":%.5f,\"z1\":%.5f,\"x2\":%.5f,\"y2\":%.5f,\"z2\":%.5f,\"r\":0.0005,\"c\":\"%s\"}",
                    site[0], site[1], site[2], tip[0], tip[1], tip[2], col));
        }
        // TOTAL axial torque + accumulated body-fixed roll: one bar at the filament end, length ∝ each quantity.
        double ex = f.coord.get(0) + 0.6*f.segLength.get(0)*f.uVec.get(0);
        double ey = f.coord.get(nSeg) + 0.6*f.segLength.get(0)*f.uVec.get(nSeg);
        double ez = f.coord.get(2*nSeg) + 0.6*f.segLength.get(0)*f.uVec.get(2*nSeg);
        if (!first) b.append(",\n");
        b.append(String.format(Locale.US, "    {\"x1\":%.5f,\"y1\":%.5f,\"z1\":%.5f,\"x2\":%.5f,\"y2\":%.5f,\"z2\":%.5f,\"r\":0.0022,\"c\":\"%s\"},%n",
                ex, ey, ez, ex, ey, ez + Math.max(-0.25, Math.min(0.25, tauTot*2e19)), tauTot > 0 ? "#ff2222" : "#22ff22"));
        b.append(String.format(Locale.US, "    {\"x1\":%.5f,\"y1\":%.5f,\"z1\":%.5f,\"x2\":%.5f,\"y2\":%.5f,\"z2\":%.5f,\"r\":0.0022,\"c\":\"#ffffff\"}",
                ex + 0.03, ey, ez, ex + 0.03, ey, ez + Math.max(-0.3, Math.min(0.3, cumRoll*0.05))));
        b.append("\n  ]\n}\n");
        try (java.io.Writer w = new java.io.FileWriter(file)) { w.write(b.toString()); } catch (Exception ex2) { }
    }

    // =============================================================================== small helpers
    static double[] trajHash(int seed, int K) {
        Glide2D G = build(seed); FilamentStore f = G.fil;
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        for (int t = 0; t < K; t++) ExplicitCompleteMatHarness.stepGlidingCPU(e, t, seed);
        double hc = 0, hr = 0; boolean nan = false;
        for (int i = 0; i < 3*G.nSeg; i++) { float v = f.coord.get(i); if (!Float.isFinite(v)) nan = true; hc = hc*1.0000001 + v; }
        for (int i = 0; i < 6; i++) hr = hr*1.0000001 + e.redOut.get(i);
        return new double[]{ hc, hr, nan ? 1 : 0 };
    }
    static int pickBindable(Rig r) { return r.closestPair()[0]; }
    static double[] reconSite(FilamentStore f, int s, double arc, double azim, double R) {
        return ExplicitTwirlGlidingHarness.reconSite(f, s, arc, azim, R);
    }
    static double[] perpOf(double[] u) {
        double[] a = Math.abs(u[0]) < 0.9 ? new double[]{1,0,0} : new double[]{0,1,0};
        double d = dot(a, u);
        return nrm(new double[]{ a[0]-d*u[0], a[1]-d*u[1], a[2]-d*u[2] });
    }
    static double[] orth(double[] v, double[] axis) {
        double d = dot(v, axis);
        return nrm(new double[]{ v[0]-d*axis[0], v[1]-d*axis[1], v[2]-d*axis[2] });
    }
    static double[] rot(double[] v, double[] axis, double ang) {
        double c = Math.cos(ang), s = Math.sin(ang);
        return ExplicitCompleteMatHarness.rotAbout(v[0], v[1], v[2], axis[0], axis[1], axis[2], c, s);
    }
    static double[] nrm(double[] v) { double l = norm(v); return new double[]{ v[0]/l, v[1]/l, v[2]/l }; }
    static double sq(double x) { return x*x; }
    static Throwable root(Throwable e) { Throwable r = e; while (r.getCause() != null && r.getCause() != r) r = r.getCause(); return r; }
    static String oneLine(String s) { return s == null ? "(none)" : s.replaceAll("\\s+", " ").trim(); }
}
