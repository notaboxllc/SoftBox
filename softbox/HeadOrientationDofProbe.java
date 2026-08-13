package softbox;

import java.util.Locale;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * MYOSIN HEAD ORIENTATIONAL DOF ARCHAEOLOGY — read-only numeric probe.
 *
 * <p><b>Changes no physics, no default, no kernel.</b> It builds two motors that BOTH already exist in the
 * tree and measures, for ONE motor with a FIXED anchor and base, the set of head long-axis directions
 * {@code eBind} its own dynamic internal coordinates can reach:
 *
 * <ol>
 *   <li><b>CURRENT</b> — the explicit-S2 / two-body motor used by Path A and Path B. Its head pose is an
 *       ALGEBRAIC function of two generalized coordinates (phi, psi) written by
 *       {@code TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit}; the head sub-body is never integrated.
 *       {@code eBind = R_econv(psi) . p1hat}, so the (phi, psi) sweep is a ONE-parameter locus.</li>
 *   <li><b>HISTORICAL</b> — the canonical SPHEREHEAD articulated motor (rod -> lever -> head, inc 4b-i).
 *       Its head is a genuine {@link RigidRodBody} sub-body integrated by the SHARED
 *       {@link RigidRodLangevinIntegrationSystem} with body-frame Brownian torque
 *       ({@link BrownianForceSystem}) and rotational drag ({@link DragTensorSystem}), so its {@code uVec}
 *       — which IS {@code eBind} for the cross-bridge, see {@code CrossBridgeSystem.bondForces} — is a free
 *       unit vector on the 2-sphere.</li>
 * </ol>
 *
 * <p>Reported for each: the reachable set, its dimension (via the covariance spectrum of the sampled
 * directions), the covered solid angle by equal-area binning, and — the load-bearing number — the fraction
 * of the sparse {@code every4} helical-site normals that ONE fixed motor can face within the historical
 * stereospecific tolerance of 25 deg.
 */
public final class HeadOrientationDofProbe {
    private HeadOrientationDofProbe() {}

    static final int NBIN_THETA = 36, NBIN_PHI = 72;      // equal-area-ish sphere binning for coverage
    static String OUT = "RUN_LOGS/motor_audit/head_orientation_dof_history";

    public static void main(String[] args) {
        for (int i = 0; i < args.length; i++) if (args[i].equals("-out")) OUT = args[++i];
        new java.io.File(OUT).mkdirs();
        System.out.println("\n=== MYOSIN HEAD ORIENTATIONAL DOF — reachable eBind directions for ONE fixed motor ===");
        System.out.println("    read-only probe; no physics, default or kernel is modified\n");

        if (args.length > 0 && args[0].equals("-detached-psi")) { detachedPsiAudit(); return; }
        if (args.length > 0 && args[0].equals("-kdet-ladder")) { kdetLadder(); return; }
        double[][] cur = currentMotorSweep();
        double[][] his = historicalMotorSweep();
        double[][] tilt = tiltMotorSweep();

        report("CURRENT    explicit-S2 two-body motor (phi, psi algebraic head)", cur, "current");
        report("HISTORICAL canonical SPHEREHEAD articulated motor (rigid-body head)", his, "historical");
        report("RESTORED   explicit-S2 + neck-head tilt chi (psi, chi), via matBeamGeomTilt", tilt, "tilt");

        siteReachability(cur, his, tilt);
        System.out.println("\n  data -> " + OUT + "/{ebind_current,ebind_historical,ebind_tilt,site_reach}.tsv");
    }

    // ---------------------------------------------------------------------------------------------------
    // CURRENT motor: sweep the two generalized coordinates over their full physical range.
    // ---------------------------------------------------------------------------------------------------
    static double[][] currentMotorSweep() {
        // Base triad exactly as buildGlide2D assembles it for every lawn motor.
        double[] bh = { 1, 0, 0 }, up = { 0, 0, 1 };
        double[] ec = { up[1] * bh[2] - up[2] * bh[1], up[2] * bh[0] - up[0] * bh[2], up[0] * bh[1] - up[1] * bh[0] };
        double rF8x = TwoBodyConverterMotor.R_F8[0], rF8y = TwoBodyConverterMotor.R_F8[1];
        double[] p1 = { bh[0] * rF8x + up[0] * rF8y, bh[1] * rF8x + up[1] * rF8y, bh[2] * rF8x + up[2] * rF8y };
        double n = Math.sqrt(p1[0] * p1[0] + p1[1] * p1[1] + p1[2] * p1[2]);
        p1[0] /= n; p1[1] /= n; p1[2] /= n;
        double[] p2 = { ec[1] * p1[2] - ec[2] * p1[1], ec[2] * p1[0] - ec[0] * p1[2], ec[0] * p1[1] - ec[1] * p1[0] };
        // phi and psi BOTH swept over a full turn — deliberately far beyond anything the mechanics allow, so
        // the result is an UPPER BOUND on the reachable set, not a sample of the visited set.
        int NP = 181, NQ = 721;
        double[][] out = new double[NP * NQ][3];
        int k = 0;
        for (int a = 0; a < NP; a++) {
            for (int b = 0; b < NQ; b++) {
                double psi = -Math.PI + 2 * Math.PI * b / (NQ - 1.0);
                double c = Math.cos(psi), s = Math.sin(psi);
                out[k][0] = c * p1[0] + s * p2[0];
                out[k][1] = c * p1[1] + s * p2[1];
                out[k][2] = c * p1[2] + s * p2[2];
                k++;
            }
        }
        return out;
    }

    // ===================================================================================================
    // k_det LADDER — how weak may the detached internal rest elasticity be before the head tumbles, and how
    // strong before it stops searching? Detached motors only; the strong bound spring never activates here.
    // ===================================================================================================
    static void kdetLadder() {
        final double dt = 2.5e-6;
        final int WARM = 15000, SAMP = 45000, STRIDE = 10;
        double[] LAD = { 0.0, 2.0, 5.0, 10.0, 20.0, 512.0 };   // pN.nm/rad^2; 512 = today's value, for reference
        System.out.println("\n=== k_det LADDER — DETACHED head internal rest elasticity ===\n");
        System.out.printf(Locale.US, "  detached motors only (boundSeg = -1, bondData zeroed) · dt %.1e s · %d warm + %d sampled steps%n", dt, WARM, SAMP);
        System.out.printf(Locale.US, "  k_bind (bound, unchanged) = 512.0 pN.nm/rad^2 ; kT = %.3e J%n%n", Constants.kT);
        System.out.printf("  %10s %12s %10s %10s %10s %10s %12s%n",
                "k_det", "sqrt(kT/k)", "SD(psi)", "RMS |psi|", "p90 |psi|", "p95 |psi|", "range(psi)");
        System.out.printf("  %10s %12s %10s %10s %10s %10s %12s%n",
                "pN.nm/rad2", "deg", "deg", "deg", "deg", "deg", "deg");
        StringBuilder tsv = new StringBuilder("k_det_pNnm\tpred_sd_deg\tsd_deg\trms_deg\tp90_deg\tp95_deg\trange_deg\tfrac_within_25deg_of_0\n");
        for (double kdetPN : LAD) {
            ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = true;
            ExplicitCompleteMatHarness.K_DET_PNNM = kdetPN;
            TwoBodyConverterMotor.Glide2D G = TwoBodyConverterMotor.buildS2Mat(4.0, dt, 40.0,
                    TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, 101, false);
            var e = ExplicitCompleteMatHarness.packExMat(G, 1);
            int N = G.N;
            for (int m = 0; m < N; m++) { e.boundSeg.set(m, -1); G.mot.boundSeg.set(m, -1); }
            G.bondData.init(0f);
            java.util.List<Double> vals = new java.util.ArrayList<>();
            double s1 = 0, s2 = 0; long n = 0;
            for (int t = 0; t < WARM + SAMP; t++) {
                e.matc.set(0, t); e.matc.set(1, 101);
                MatSoaSlice.matKbindGate(e.boundSeg, e.params, e.gateP, e.exCounts);
                TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF);
                TwoBodyBeamAnalyticGpu.matS2SolveStep(e.nodes, e.frame, e.q, G.bondData, e.boundSeg, e.params, e.sys,
                        e.outGeom, G.mot.forceDotFil, G.mot.forceMag, e.matc, e.exCounts, e.convF);
                if (t >= WARM && (t - WARM) % STRIDE == 0)
                    for (int m = 0; m < N; m++) { double v = e.q.get(N + m); s1 += v; s2 += v * v; n++; vals.add(v); }
            }
            double mean = s1 / n, sd = Math.sqrt(Math.max(0, s2 / n - mean * mean));
            double[] a = new double[vals.size()];
            for (int i = 0; i < a.length; i++) a[i] = Math.abs(Math.toDegrees(vals.get(i)));
            java.util.Arrays.sort(a);
            double rms = 0; for (double v : a) rms += v * v; rms = Math.sqrt(rms / a.length);
            double p90 = a[(int) (0.90 * (a.length - 1))], p95 = a[(int) (0.95 * (a.length - 1))];
            double range = a[a.length - 1];
            int within = 0; for (double v : a) if (v <= 25.0) within++;
            double pred = kdetPN > 0 ? Math.toDegrees(Math.sqrt(Constants.kT / (kdetPN * TwoBodyConverterMotor.KAPPA_CODE))) : Double.NaN;
            System.out.printf(Locale.US, "  %10.1f %12s %10.2f %10.2f %10.2f %10.2f %12.1f%n",
                    kdetPN, kdetPN > 0 ? String.format(Locale.US, "%.2f", pred) : "free",
                    Math.toDegrees(sd), rms, p90, p95, range);
            tsv.append(String.format(Locale.US, "%.1f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f%n",
                    kdetPN, pred, Math.toDegrees(sd), rms, p90, p95, range, within / (double) a.length));
        }
        ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = false; ExplicitCompleteMatHarness.K_DET_PNNM = 5.0;
        write("kdet_ladder.tsv", tsv.toString());
        System.out.println("\n  NOTE: psi is the IN-PLANE head angle. chi (out-of-plane) is not yet dynamic, so this");
        System.out.println("        ladder characterises the STIFFNESS SCALE, not the full 3-D search envelope.");
        System.out.println("\n  data -> " + OUT + "/kdet_ladder.tsv");
    }

    // ===================================================================================================
    // PHASE 0 — DETACHED psi AUDIT. Is the k_bind(psi - psiActin) spring active BEFORE the head binds?
    // Runs the REAL production solver on a lawn of DETACHED motors (boundSeg = -1, no filament force) and
    // measures the equilibrium distribution of the generalized coordinates.
    // ===================================================================================================
    static void detachedPsiAudit() {
        final double dt = 2.5e-6;
        final int WARM = 20000, SAMP = 60000, STRIDE = 10;
        TwoBodyConverterMotor.Glide2D G = TwoBodyConverterMotor.buildS2Mat(4.0, dt, 40.0,
                TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, 101, false);
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        int N = G.N;
        for (int m = 0; m < N; m++) { e.boundSeg.set(m, -1); G.mot.boundSeg.set(m, -1); }
        G.bondData.init(0f);                                   // no cross-bridge force anywhere
        double kb = e.params.get(7 * N), kc = e.params.get(6 * N);
        System.out.println("\n=== PHASE 0 — DETACHED psi AUDIT (real production solver, boundSeg = -1) ===\n");
        System.out.printf(Locale.US, "  motors %d · dt %.2e s · warm %d · sample %d steps%n", N, dt, WARM, SAMP);
        System.out.printf(Locale.US, "  k_bind = %.4e N.m/rad^2 (%.1f pN.nm/rad^2)   k_conv = %.4e (%.1f pN.nm/rad^2)%n",
                kb, kb / TwoBodyConverterMotor.KAPPA_CODE, kc, kc / TwoBodyConverterMotor.KAPPA_CODE);
        double predSD = Math.sqrt(Constants.kT / kb);
        System.out.printf(Locale.US, "  PREDICTION if the spring is ACTIVE while detached: SD(psi) = sqrt(kT/k_bind) = %.5f rad = %.3f deg%n",
                predSD, Math.toDegrees(predSD));
        System.out.printf(Locale.US, "  PREDICTION if the spring is BOUND-ONLY            : psi diffuses freely (SD grows without bound)%n%n");
        // is the residual term structurally gated? read it straight out of the source of truth
        System.out.println("  SOURCE TRACE (MatSoaSlice.matS2SolveStep / TwoBodyBeamAnalyticGpu.matS2SolveStep):");
        System.out.println("      boolean bound = boundSeg.get(m) >= 0;");
        System.out.println("      if (bound) { f8x..f8z = bondData[..] }        <- the F8 FORCE is gated on bound");
        System.out.println("      a45 = QpsiF8 - kc*(th - thetaS) - kb*(psi - psiActin);   <- the kb SPRING is NOT gated");
        System.out.println("      a44 = (K44 + (kc + kb)) + apsi;                          <- nor is its Jacobian entry\n");

        double sPsi = 0, s2Psi = 0, sPhi = 0, s2Phi = 0, sChi = 0; long n = 0;
        double[] hist = new double[73];                        // psi histogram, -180..180 in 5 deg bins
        java.util.List<double[]> traj = new java.util.ArrayList<>();
        for (int t = 0; t < WARM + SAMP; t++) {
            e.matc.set(0, t); e.matc.set(1, 101);
            TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF);
            TwoBodyBeamAnalyticGpu.matS2SolveStep(e.nodes, e.frame, e.q, G.bondData, e.boundSeg, e.params, e.sys,
                    e.outGeom, G.mot.forceDotFil, G.mot.forceMag, e.matc, e.exCounts, e.convF);
            if (t >= WARM && (t - WARM) % STRIDE == 0) {
                for (int m = 0; m < N; m++) {
                    double psi = e.q.get(N + m), phi = e.q.get(m);
                    sPsi += psi; s2Psi += psi * psi; sPhi += phi; s2Phi += phi * phi; n++;
                    int b = (int) ((Math.toDegrees(psi) + 180.0) / 5.0);
                    if (b >= 0 && b < hist.length) hist[b]++;
                }
                if (traj.size() < 20000) traj.add(new double[]{ e.q.get(N), e.q.get(0) });
            }
        }
        double mPsi = sPsi / n, sdPsi = Math.sqrt(Math.max(0, s2Psi / n - mPsi * mPsi));
        double mPhi = sPhi / n, sdPhi = Math.sqrt(Math.max(0, s2Phi / n - mPhi * mPhi));
        System.out.printf(Locale.US, "  MEASURED  psi : mean %+8.5f rad (%+7.3f deg)   SD %8.5f rad (%6.3f deg)%n",
                mPsi, Math.toDegrees(mPsi), sdPsi, Math.toDegrees(sdPsi));
        System.out.printf(Locale.US, "  MEASURED  phi : mean %+8.5f rad (%+7.3f deg)   SD %8.5f rad (%6.3f deg)   [PHI_PRE = %+.4f rad]%n",
                mPhi, Math.toDegrees(mPhi), sdPhi, Math.toDegrees(sdPhi), TwoBodyConverterMotor.PHI_PRE_3E);
        System.out.printf(Locale.US, "%n  SD(psi) measured / sqrt(kT/k_bind) predicted = %.4f%n", sdPsi / predSD);
        System.out.printf(Locale.US, "  VERDICT: the detached psi spring is %s%n",
                sdPsi < 3.0 * predSD ? "*** ACTIVE *** — the DETACHED head is held by a LAB-FIXED angular spring"
                                     : "not confining (psi explores freely)");
        System.out.printf(Locale.US, "  => detached eBind wanders only ~+-%.1f deg along its great circle (of 360 deg available)%n",
                Math.toDegrees(sdPsi));
        StringBuilder sb = new StringBuilder("psi_deg_bin_centre\tcount\n");
        for (int i = 0; i < hist.length; i++) sb.append(String.format(Locale.US, "%.1f\t%.0f%n", -180.0 + 5.0 * i + 2.5, hist[i]));
        write("detached_psi_hist.tsv", sb.toString());
        StringBuilder tb = new StringBuilder("psi_rad\tphi_rad\n");
        for (double[] r : traj) tb.append(String.format(Locale.US, "%.8f\t%.8f%n", r[0], r[1]));
        write("detached_psi_traj.tsv", tb.toString());
        System.out.println("\n  data -> " + OUT + "/{detached_psi_hist,detached_psi_traj}.tsv");
    }

    // ---------------------------------------------------------------------------------------------------
    // RESTORED motor: sweep (psi, chi) through the REAL matBeamGeomTilt kernel on a real built scene.
    // Also gates that chi == 0 reproduces matBeamGeom bit-for-bit.
    // ---------------------------------------------------------------------------------------------------
    static double[][] tiltMotorSweep() {
        TwoBodyConverterMotor.Glide2D G = TwoBodyConverterMotor.buildS2Mat(40.0, 2.5e-6, 40.0,
                TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, 101, false);
        var e = ExplicitCompleteMatHarness.packExMat(G, 1);
        int N = G.N;
        System.out.printf(Locale.US, "  [probe scene] N = %d motors; gamma_psi = %.4e N.m.s/rad ; gamma_chi (derived, = gamma_psi) = %.4e%n",
                N, G.gammaPsi, e.gammaChi);
        double R = TwoBodyConverterMotor.RHEAD_3C * 1e-6;
        double sphere = 8 * Math.PI * Constants.aeta * R * R * R;
        System.out.printf(Locale.US, "  [drag cross-check] historical Stokes sphere 8*pi*eta*R^3 at R_head = %.2f nm : %.4e N.m.s/rad  (ratio gamma_chi/sphere = %.3f)%n",
                TwoBodyConverterMotor.RHEAD_3C * 1e3, sphere, e.gammaChi / sphere);

        // ---- chi == 0 IDENTITY GATE against the untouched matBeamGeom ----------------------------------
        double worst = 0;
        for (double psi : new double[]{ 0.0, 0.37, -0.61, 1.9 }) {
            for (int m = 0; m < N; m++) { e.q.set(m, TwoBodyConverterMotor.PHI_PRE_3E); e.q.set(N + m, psi); }
            TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF);
            double[] ref = new double[9 * N];
            for (int i = 0; i < 9 * N; i++) ref[i] = e.outGeom.get(i);
            TwoBodyBeamAnalyticGpu.matBeamGeomTilt(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF, e.chiHead);
            for (int i = 0; i < 9 * N; i++) worst = Math.max(worst, Math.abs(ref[i] - e.outGeom.get(i)));
        }
        System.out.printf(Locale.US, "  [chi=0 IDENTITY GATE] max |matBeamGeomTilt - matBeamGeom| over 4 psi x %d motors x 9 comps = %.3e  =>  %s%n",
                N, worst, worst == 0.0 ? "BYTE-IDENTICAL" : "*** NOT IDENTICAL ***");

        // ---- (psi, chi) sweep -------------------------------------------------------------------------
        int NPSI = 181, NCHI = 91;
        double[][] out = new double[NPSI * NCHI][3];
        int k = 0;
        for (int a = 0; a < NPSI; a++) {
            double psi = -Math.PI + 2 * Math.PI * a / (NPSI - 1.0);
            for (int b = 0; b < NCHI; b++) {
                double chi = -0.5 * Math.PI + Math.PI * b / (NCHI - 1.0);
                for (int m = 0; m < N; m++) { e.q.set(m, TwoBodyConverterMotor.PHI_PRE_3E); e.q.set(N + m, psi); e.chiHead.set(m, chi); }
                TwoBodyBeamAnalyticGpu.matBeamGeomTilt(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF, e.chiHead);
                double dx = e.outGeom.get(6 * N) - e.outGeom.get(3 * N);
                double dy = e.outGeom.get(7 * N) - e.outGeom.get(4 * N);
                double dz = e.outGeom.get(8 * N) - e.outGeom.get(5 * N);
                double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
                out[k][0] = dx / n; out[k][1] = dy / n; out[k][2] = dz / n; k++;
            }
        }
        for (int m = 0; m < N; m++) e.chiHead.set(m, 0.0);
        return out;
    }

    // ---------------------------------------------------------------------------------------------------
    // HISTORICAL motor: one anchored articulated motor, detached, full Brownian, shared systems only.
    // ---------------------------------------------------------------------------------------------------
    static double[][] historicalMotorSweep() {
        final double dt = 1.0e-5;
        final int STEPS = 400000, STRIDE = 40;
        MotorStore mot = new MotorStore(1);
        mot.assembleArticulated(0, 0f, 0f, 0f, 0f, 0f, 1f, (float) Constants.BRotCoeff);
        DragTensorSystem.run(mot);
        mot.setBodyParams(dt);
        mot.nucleotideState.set(0, MotorStore.NUC_ADPPI);
        mot.boundSeg.set(0, -1);                       // DETACHED — no filament, no cross-bridge
        RigidRodBody b = mot.body;
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        int nB = b.coord.getSize() / 3, h = 3 * 0 + 2;
        double[][] out = new double[STEPS / STRIDE][3];
        int k = 0;
        for (int t = 0; t < STEPS; t++) {
            mot.setCounts(t, 20260812, 1);
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam,
                    b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
            MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
            TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, mot.anchor, mot.jointParams, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum,
                    b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            if (t % STRIDE == 0 && k < out.length) {
                out[k][0] = b.uVec.get(h); out[k][1] = b.uVec.get(nB + h); out[k][2] = b.uVec.get(2 * nB + h);
                k++;
            }
        }
        double[][] trim = new double[k][]; System.arraycopy(out, 0, trim, 0, k);
        return trim;
    }

    // ---------------------------------------------------------------------------------------------------
    static void report(String label, double[][] v, String tag) {
        // covariance spectrum -> dimension of the reachable set
        double[][] C = new double[3][3];
        for (double[] u : v) for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) C[i][j] += u[i] * u[j];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) C[i][j] /= v.length;
        double[] ev = eig3(C);
        // equal-area coverage
        boolean[] bin = new boolean[NBIN_THETA * NBIN_PHI];
        int filled = 0;
        for (double[] u : v) {
            int it = (int) ((u[2] + 1.0) * 0.5 * NBIN_THETA); if (it >= NBIN_THETA) it = NBIN_THETA - 1; if (it < 0) it = 0;
            double az = Math.atan2(u[1], u[0]);
            int ip = (int) ((az + Math.PI) / (2 * Math.PI) * NBIN_PHI); if (ip >= NBIN_PHI) ip = NBIN_PHI - 1; if (ip < 0) ip = 0;
            int id = it * NBIN_PHI + ip;
            if (!bin[id]) { bin[id] = true; filled++; }
        }
        double solid = 4.0 * Math.PI * filled / (double) (NBIN_THETA * NBIN_PHI);
        System.out.printf("  %s%n", label);
        System.out.printf(Locale.US, "      samples                       : %d%n", v.length);
        System.out.printf(Locale.US, "      covariance eigenvalues        : %.6f  %.6f  %.6f%n", ev[0], ev[1], ev[2]);
        int dim = 0; for (double e : ev) if (e > 1e-9) dim++;
        System.out.printf(Locale.US, "      => eBind spans a %d-DIMENSIONAL linear subspace (smallest eigenvalue %.3e)%n", dim, ev[2]);
        System.out.printf(Locale.US, "      equal-area bins occupied      : %d of %d  =>  solid angle ~ %.3f sr (%.1f %% of 4pi)%n",
                filled, NBIN_THETA * NBIN_PHI, solid, 100.0 * filled / (NBIN_THETA * NBIN_PHI));
        StringBuilder sb = new StringBuilder("ex\tey\tez\n");
        int stride = Math.max(1, v.length / 20000);
        for (int i = 0; i < v.length; i += stride)
            sb.append(String.format(Locale.US, "%.6f\t%.6f\t%.6f%n", v[i][0], v[i][1], v[i][2]));
        write("ebind_" + tag + ".tsv", sb.toString());
    }

    /** Fraction of the sparse every4 site normals ONE fixed motor can face within the historical 25 deg. */
    static void siteReachability(double[][] cur, double[][] his, double[][] tlt) {
        final double TOL = 25.0, cosTol = Math.cos(Math.toRadians(TOL));
        final double dAz = 4 * ExplicitCompleteMatHarness.TWIST_PER_MON_DEG;   // -666 deg == +54 deg
        System.out.printf("%n  SITE-NORMAL REACHABILITY for ONE FIXED MOTOR (every4 lattice, %.0f deg tolerance)%n", TOL);
        System.out.printf("      filament along +x, material y/z; n_site(phi) = (0, cos phi, sin phi)%n");
        System.out.printf("%n      %9s  %-22s %-22s %-22s%n", "azimuth", "CURRENT", "RESTORED (psi,chi)", "HISTORICAL");
        int okC = 0, okH = 0, okT = 0, nAz = 0;
        StringBuilder sb = new StringBuilder("azimuth_deg\tbest_current_deg\tpass_current\tbest_historical_deg\tpass_historical\tbest_tilt_deg\tpass_tilt\n");
        java.util.TreeSet<Long> az = new java.util.TreeSet<>();
        for (int k = 0; k < 40; k++) az.add(Math.round(wrap(k * dAz) * 1000));
        for (long key : az) {
            double a = Math.toRadians(key / 1000.0);
            double[] nS = { 0, Math.cos(a), Math.sin(a) };
            double bc = -1, bh = -1, bt = -1;
            for (double[] u : cur) bc = Math.max(bc, u[0] * nS[0] + u[1] * nS[1] + u[2] * nS[2]);
            for (double[] u : his) bh = Math.max(bh, u[0] * nS[0] + u[1] * nS[1] + u[2] * nS[2]);
            for (double[] u : tlt) bt = Math.max(bt, u[0] * nS[0] + u[1] * nS[1] + u[2] * nS[2]);
            boolean pc = bc >= cosTol, ph = bh >= cosTol, pt = bt >= cosTol;
            if (pc) okC++; if (ph) okH++; if (pt) okT++; nAz++;
            double ac = Math.toDegrees(Math.acos(Math.min(1, bc))), ah = Math.toDegrees(Math.acos(Math.min(1, bh)));
            double at = Math.toDegrees(Math.acos(Math.min(1, bt)));
            System.out.printf(Locale.US, "      %+9.1f  %7.2f deg %-12s %7.2f deg %-12s %7.2f deg %-12s%n",
                    key / 1000.0, ac, pc ? "PASS" : "unreachable", at, pt ? "PASS" : "unreachable", ah, ph ? "PASS" : "unreachable");
            sb.append(String.format(Locale.US, "%.3f\t%.4f\t%d\t%.4f\t%d\t%.4f\t%d%n", key / 1000.0, ac, pc ? 1 : 0, ah, ph ? 1 : 0, at, pt ? 1 : 0));
        }
        write("site_reach.tsv", sb.toString());
        System.out.printf(Locale.US, "%n      CURRENT    : %d of %d azimuths reachable = %.1f %%%n", okC, nAz, 100.0 * okC / nAz);
        System.out.printf(Locale.US, "      RESTORED   : %d of %d azimuths reachable = %.1f %%%n", okT, nAz, 100.0 * okT / nAz);
        System.out.printf(Locale.US, "      HISTORICAL : %d of %d azimuths reachable = %.1f %%%n", okH, nAz, 100.0 * okH / nAz);
    }

    // ---------------------------------------------------------------------------------------------------
    static double wrap(double d) { d = d % 360.0; if (d > 180) d -= 360; if (d <= -180) d += 360; return d; }
    static void write(String name, String s) {
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(OUT, name), s); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
    }
    /** Symmetric 3x3 eigenvalues, descending — closed form (Smith 1961). Analysis code, not a kernel. */
    static double[] eig3(double[][] A) {
        double p1 = A[0][1] * A[0][1] + A[0][2] * A[0][2] + A[1][2] * A[1][2];
        double q = (A[0][0] + A[1][1] + A[2][2]) / 3.0;
        if (p1 < 1e-300) { double[] d = { A[0][0], A[1][1], A[2][2] }; java.util.Arrays.sort(d); return new double[]{ d[2], d[1], d[0] }; }
        double p2 = (A[0][0] - q) * (A[0][0] - q) + (A[1][1] - q) * (A[1][1] - q) + (A[2][2] - q) * (A[2][2] - q) + 2 * p1;
        double p = Math.sqrt(p2 / 6.0);
        double[][] B = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) B[i][j] = (A[i][j] - (i == j ? q : 0)) / p;
        double det = B[0][0] * (B[1][1] * B[2][2] - B[1][2] * B[2][1])
                   - B[0][1] * (B[1][0] * B[2][2] - B[1][2] * B[2][0])
                   + B[0][2] * (B[1][0] * B[2][1] - B[1][1] * B[2][0]);
        double r = det / 2.0; if (r > 1) r = 1; if (r < -1) r = -1;
        double phi = Math.acos(r) / 3.0;
        double e1 = q + 2 * p * Math.cos(phi);
        double e3 = q + 2 * p * Math.cos(phi + 2.0 * Math.PI / 3.0);
        double e2 = 3 * q - e1 - e3;
        double[] ev = { e1, e2, e3 }; java.util.Arrays.sort(ev);
        return new double[]{ ev[2], ev[1], ev[0] };
    }
}
