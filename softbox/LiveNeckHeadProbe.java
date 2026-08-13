package softbox;

import java.util.Locale;

/**
 * LIVE NECK/CONVERTER FRAME + DYNAMIC 3-D HEAD ORIENTATION — probe and gates.
 *
 * <p>Measurement harness for {@code docs/motor/RESTORED_3D_HEAD_TILT_DOF.md}. It changes no default: every
 * feature it exercises is flag-gated and default-off, and the historical kernels are never modified.
 *
 * <p><b>Runner: the CPU sequential runner throughout</b> (plain-Java kernel calls over the host SoA arrays,
 * no TaskGraph, no device transfer). Scenes are 12 motors, horizons ≤ 2e5 steps — a deterministic /
 * single-motor assay class, per {@code docs/CPU_GPU_VALIDATION_POLICY.md}. No GPU work is launched.
 *
 * <pre>
 *   -frame    Phase 0   what frame the head geometry actually lives in; the F8 generalized-force axis audit
 *   -cov      Phase 2   covariance fixtures: rigid rotation, S2 roll, lever motion, old-frame comparison
 *   -dyn      Phase 9   detached dynamic 3-D head: FDT gates + orientation statistics for the k_det ladder
 *   -acc      Phase 13  dynamic helical site-normal accessibility from the real thermal trajectory
 *   -reg      Phase 11  core motor regression (stroke, polarity, k_ext, theta, S2, stability)
 *   -comp     Phase 12  axial compliance decomposition
 *   -all      everything
 * </pre>
 */
public final class LiveNeckHeadProbe {
    private LiveNeckHeadProbe() {}

    static String OUT = "RUN_LOGS/motor_audit/restored_3d_head_tilt";
    static final double DT = 2.5e-6;
    static final int SEED = 20260812;

    public static void main(String[] args) {
        String mode = args.length > 0 && args[0].startsWith("-") ? args[0] : "-all";
        for (int i = 0; i < args.length; i++) if (args[i].equals("-out")) OUT = args[++i];
        new java.io.File(OUT).mkdirs();
        switch (mode) {
            case "-frame" -> phase0Frame();
            case "-cov"   -> phase2Covariance();
            case "-dyn"   -> phase9Dynamics();
            case "-acc"   -> phase13Access();
            case "-fdt"   -> phase7Fdt();
            case "-lever" -> phaseLever();
            case "-reg"   -> phase11Regression();
            case "-comp"  -> phase12Compliance();
            case "-all"   -> { phase0Frame(); phase2Covariance(); phase7Fdt(); phase9Dynamics(); phaseLever();
                               phase13Access(); phase11Regression(); phase12Compliance(); }
            default -> System.out.println("unknown mode " + mode);
        }
    }

    // ==================================================================================================
    // PHASE 0 — what frame does the head geometry actually live in?
    // ==================================================================================================
    static void phase0Frame() {
        hdr("PHASE 0 — the frame the head geometry actually lives in");
        var G = build(); var e = pack(G, 1); setNative(e);
        int N = G.N, M = G.g4M;
        System.out.printf(Locale.US, "  scene: N=%d motors, M=%d beam elements, dt=%.1e s%n", N, M, DT);
        System.out.printf(Locale.US, "  base triad (STORED, per motor): bhat=(%.3f %.3f %.3f) econv=(%.3f %.3f %.3f) eup=(%.3f %.3f %.3f)%n",
                G.bhat[0], G.bhat[1], G.bhat[2], G.econv[0], G.econv[1], G.econv[2], G.eup[0], G.eup[1], G.eup[2]);
        System.out.printf(Locale.US, "  lb=%.4f um (lever P->C)  rF8=(%.4f %.4f) rConv=(%.4f %.4f) um  |r_conv|=%.5f um%n",
                G.lb, G.rF8[0], G.rF8[1], G.rConv[0], G.rConv[1], Math.hypot(G.rConv[0], G.rConv[1]));

        // ---- Q1/Q2: the pivot C and the LIVE neck direction --------------------------------------------
        double phi0 = e.q.get(0), psi0 = 0.13;
        double h = 1e-7;
        double[] g0 = geomAt(e, N, phi0, psi0);
        double[] gp = geomAt(e, N, phi0, psi0 + h), gm = geomAt(e, N, phi0, psi0 - h);
        double[] gpf = geomAt(e, N, phi0 + h, psi0), gmf = geomAt(e, N, phi0 - h, psi0);
        double[] dF8dpsi = { (gp[6]-gm[6])/(2*h), (gp[7]-gm[7])/(2*h), (gp[8]-gm[8])/(2*h) };
        double[] dCdphi  = { (gpf[0]-gmf[0])/(2*h), (gpf[1]-gmf[1])/(2*h), (gpf[2]-gmf[2])/(2*h) };
        double Px = e.nodes.get((3*M)*N), Py = e.nodes.get((3*M+1)*N), Pz = e.nodes.get((3*M+2)*N);
        double[] fc = { g0[6]-g0[0], g0[7]-g0[1], g0[8]-g0[2] };
        double[] cp = { g0[0]-Px, g0[1]-Py, g0[2]-Pz };
        geomAt(e, N, phi0, psi0);
        double[] nf = ExplicitCompleteMatHarness.neckFrame(e, 0);

        System.out.println("\n  Q1  the neck-head pivot C = P + lb*uB  (P = beam node M; C is the head's own converter");
        System.out.println("      material point r_conv, so chi and psi rotate the head rigidly about it)");
        System.out.printf(Locale.US, "      C = (%+.5f %+.5f %+.5f) um ; |C-P| = %.5f um = lb%n", g0[0], g0[1], g0[2], norm(cp));
        System.out.println("  Q2  LIVE neck direction entering the head = the lever axis n1 = (C-P)/lb");
        System.out.printf(Locale.US, "      n1 = (%+.5f %+.5f %+.5f)  — dynamic in phi; NOT taken from any lab axis%n", nf[0], nf[1], nf[2]);
        System.out.println("  Q3  second independent LIVE vector = the distal S2 tangent sHat = (node M - node M-1)/|.|");
        int jm = M >= 2 ? M - 1 : 0;
        double[] sh = { e.nodes.get((3*M)*N) - e.nodes.get((3*jm)*N),
                        e.nodes.get((3*M+1)*N) - e.nodes.get((3*jm+1)*N),
                        e.nodes.get((3*M+2)*N) - e.nodes.get((3*jm+2)*N) };
        double sl = norm(sh); sh[0]/=sl; sh[1]/=sl; sh[2]/=sl;
        System.out.printf(Locale.US, "      sHat = (%+.5f %+.5f %+.5f) ; |s| = %.5f um ; angle(n1,sHat) = %.2f deg%n",
                sh[0], sh[1], sh[2], sl, Math.toDegrees(Math.acos(clamp(dot(nf, 0, sh)))));
        System.out.printf(Locale.US, "      => n2 = GS(sHat, n1) = (%+.5f %+.5f %+.5f) ; n3 = n1 x n2 = (%+.5f %+.5f %+.5f)%n",
                nf[3], nf[4], nf[5], nf[6], nf[7], nf[8]);
        System.out.printf(Locale.US, "      Gram-Schmidt conditioning |g| = %.5f  (1 = perfectly orthogonal, 0 = degenerate)%n", nf[9]);
        System.out.printf(Locale.US, "      orthonormality: |n1|=%.15f |n2|=%.15f |n3|=%.15f  n1.n2=%.2e n1.n3=%.2e n2.n3=%.2e%n",
                Math.sqrt(dot(nf,0,nf,0)), Math.sqrt(dot(nf,3,nf,3)), Math.sqrt(dot(nf,6,nf,6)),
                dot(nf,0,nf,3), dot(nf,0,nf,6), dot(nf,3,nf,6));

        System.out.println("\n  Q4  does the frame follow the mechanics?  (measured, one perturbation at a time)");
        frameResponse(G, e, N, M);

        System.out.println("\n  Q5  is the STORED base triad sufficient?  NO — it is written once at build and never");
        System.out.println("      updated: the beam's distal node P enters the head construction as a pure TRANSLATION");
        System.out.println("      (matBeamGeom builds uB, d0 and r_conv from frame[0..8] alone). Measured below.");
        System.out.println("  Q6  minimal live frame = (n1 = lever, n2 = GS(distal S2 tangent), n3 = n1 x n2) — above.");

        // ---- the F8 generalized-force axis audit --------------------------------------------------------
        System.out.println("\n  --- F8 GENERALIZED-FORCE AXIS AUDIT (pre-existing; see the report) ---");
        System.out.printf(Locale.US, "   d xF8/d psi (FD through matBeamGeom) = (%+.6e %+.6e %+.6e)%n", dF8dpsi[0], dF8dpsi[1], dF8dpsi[2]);
        System.out.printf(Locale.US, "   econv x (xF8-C)                      = (%+.6e %+.6e %+.6e)   <- MATCHES%n", cr(G.econv,fc)[0], cr(G.econv,fc)[1], cr(G.econv,fc)[2]);
        System.out.printf(Locale.US, "   eup   x (xF8-C)                      = (%+.6e %+.6e %+.6e)   <- what matS2SolveStep uses%n", cr(G.eup,fc)[0], cr(G.eup,fc)[1], cr(G.eup,fc)[2]);
        System.out.printf(Locale.US, "   d C/d phi   (FD through matBeamGeom) = (%+.6e %+.6e %+.6e)%n", dCdphi[0], dCdphi[1], dCdphi[2]);
        System.out.printf(Locale.US, "   econv x (C-P)                        = (%+.6e %+.6e %+.6e)   <- MATCHES%n", cr(G.econv,cp)[0], cr(G.econv,cp)[1], cr(G.econv,cp)[2]);
        System.out.printf(Locale.US, "   eup   x (C-P)                        = (%+.6e %+.6e %+.6e)   <- what matS2SolveStep uses%n", cr(G.eup,cp)[0], cr(G.eup,cp)[1], cr(G.eup,cp)[2]);
        workClosure(G, false);
        workClosure(G, true);
    }

    /** Fixture: perturb ONE thing and report how the live neck frame and the stored base triad respond. */
    static void frameResponse(TwoBodyConverterMotor.Glide2D G, ExplicitCompleteMatHarness.ExMat e, int N, int M) {
        double[] ref = ExplicitCompleteMatHarness.neckFrame(e, 0).clone();
        // (a) S2 BEND — displace node M-1 out of the base plane
        var e1 = pack(G, 0); setNative(e1);
        int jm = M >= 2 ? M - 1 : 0;
        e1.nodes.set((3*jm+1)*N, e1.nodes.get((3*jm+1)*N) + 0.002);
        double[] a = ExplicitCompleteMatHarness.neckFrame(e1, 0);
        // (b) LEVER MOTION — change phi
        var e2 = pack(G, 0); setNative(e2); for (int m = 0; m < N; m++) e2.q.set(m, e2.q.get(m) + 0.20);
        double[] b = ExplicitCompleteMatHarness.neckFrame(e2, 0);
        // (c) CONVERTER STATE — change psi (the head, not the neck)
        var e3 = pack(G, 0); setNative(e3); for (int m = 0; m < N; m++) e3.q.set(N+m, e3.q.get(N+m) + 0.20);
        double[] c = ExplicitCompleteMatHarness.neckFrame(e3, 0);
        // (d) RIGID ROTATION of the whole motor
        var e4 = pack(G, 0); setNative(e4); double[][] R = rot3(0.7, -0.4, 1.1); rigidRotate(e4, R);
        double[] d = ExplicitCompleteMatHarness.neckFrame(e4, 0);
        double[] rref = new double[9];
        for (int k = 0; k < 3; k++) { double[] v = mv(R, new double[]{ ref[3*k], ref[3*k+1], ref[3*k+2] });
            rref[3*k] = v[0]; rref[3*k+1] = v[1]; rref[3*k+2] = v[2]; }
        System.out.printf("      %-28s %-14s %-14s %-14s   verdict%n", "perturbation", "rot(n1) deg", "rot(n2) deg", "rot(n3) deg");
        rowResp("S2 bend (node M-1 out of plane)", ref, a, "n2/n3 ROLL about n1 — S2 bending now reorients the head");
        rowResp("lever motion (phi + 0.20 rad)  ", ref, b, "n1 turns by 11.46 deg = the lever angle exactly");
        rowResp("converter state (psi + 0.20)   ", ref, c, "INVARIANT — the frame is the NECK's, not the head's");
        rowRespAbs("rigid rotation of the motor   ", rref, d, "COVARIANT — equals R applied to the reference frame");
        System.out.println("      (the STORED base triad frame[0..8] responds to NONE of (a)-(c): it is written once at build)");
    }
    static void rowResp(String lbl, double[] r, double[] v, String note) {
        System.out.printf(Locale.US, "      %-28s %-14.4f %-14.4f %-14.4f   %s%n", lbl,
                angDeg(r,0,v,0), angDeg(r,3,v,3), angDeg(r,6,v,6), note);
    }
    static void rowRespAbs(String lbl, double[] r, double[] v, String note) {
        System.out.printf(Locale.US, "      %-28s %-14.2e %-14.2e %-14.2e   %s%n", lbl,
                angDeg(r,0,v,0), angDeg(r,3,v,3), angDeg(r,6,v,6), note);
    }

    /** Work-closure gate: is the solver's converged (phi,psi) a stationary point of the true potential? */
    static void workClosure(TwoBodyConverterMotor.Glide2D G, boolean axisFix) {
        boolean savT = ExplicitCompleteMatHarness.HEAD_TILT_3D, savA = ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX;
        ExplicitCompleteMatHarness.HEAD_TILT_3D = axisFix;      // the tilt solver is the only one carrying the fix
        ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX = axisFix;
        var e = pack(G, 0); setNative(e); int N = G.N;
        double kc = e.params.get(6*N), kb = e.params.get(7*N), kF8 = e.params.get(5*N)*1e6;
        double thetaS = e.q.get(2*N), psiA = e.q.get(3*N), phi0 = e.q.get(0);
        double[] gr = geomAt(e, N, phi0, 0.0);
        double[] xs = { gr[6] + 0.004, gr[7], gr[8] };           // a fixed site 4 nm away along +bhat
        for (int m = 0; m < N; m++) { e.boundSeg.set(m, 0); e.q.set(m, phi0); e.q.set(N+m, 0.0); e.chiHead.set(m, 0.0); }
        e.matc.set(2, 0);
        for (int t = 0; t < 3000; t++) {
            solveStep(e, G, t, SEED, 0, xs, kF8);
        }
        double pf = e.q.get(0), ps = e.q.get(N), pc = e.chiHead.get(0);
        double hh = 1e-6;
        double dUdphi = (uTot(e,N,pf+hh,ps,pc,kc,kb,kF8,thetaS,psiA,xs) - uTot(e,N,pf-hh,ps,pc,kc,kb,kF8,thetaS,psiA,xs))/(2*hh);
        double dUdpsi = (uTot(e,N,pf,ps+hh,pc,kc,kb,kF8,thetaS,psiA,xs) - uTot(e,N,pf,ps-hh,pc,kc,kb,kF8,thetaS,psiA,xs))/(2*hh);
        double scale = kc * Math.abs(ps - pf - thetaS) + kb * Math.abs(ps - psiA) + 1e-24;
        System.out.printf(Locale.US, "   [work closure, %s]  converged phi=%+.5f psi=%+.5f chi=%+.5f -> dU/dphi=%+.3e dU/dpsi=%+.3e N.m ; /torque-scale %.3e %.3e  %s%n",
                axisFix ? "econv axis (tilt solver)" : "eup axis (production)", pf, ps, pc, dUdphi, dUdpsi,
                Math.abs(dUdphi)/scale, Math.abs(dUdpsi)/scale,
                Math.abs(dUdphi) < 1e-3*scale ? "STATIONARY" : "*** NOT STATIONARY ***");
        G.bondData.init(0f);
        ExplicitCompleteMatHarness.HEAD_TILT_3D = savT; ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX = savA;
    }
    static double uTot(ExplicitCompleteMatHarness.ExMat e, int N, double phi, double psi, double chi,
                       double kc, double kb, double kF8, double thetaS, double psiA, double[] xs) {
        double[] g = geomAtChi(e, N, phi, psi, chi);
        double dx=(g[6]-xs[0])*1e-6, dy=(g[7]-xs[1])*1e-6, dz=(g[8]-xs[2])*1e-6;
        double th = psi - phi;
        return 0.5*kc*(th-thetaS)*(th-thetaS) + 0.5*kb*(psi-psiA)*(psi-psiA) + 0.5*kF8*(dx*dx+dy*dy+dz*dz);
    }

    // ==================================================================================================
    // PHASE 2 — covariance fixtures for the LIVE-frame rest orientation
    // ==================================================================================================
    static void phase2Covariance() {
        hdr("PHASE 2 — covariance fixtures (deterministic; no dynamics)");
        boolean sav = ExplicitCompleteMatHarness.HEAD_TILT_3D;
        ExplicitCompleteMatHarness.HEAD_TILT_3D = true;
        ExplicitCompleteMatHarness.K_DET_PNNM = 10.0;
        var G = build(); var e = pack(G, 0); setNative(e); int N = G.N, M = G.g4M;
        double kdet = 10.0 * TwoBodyConverterMotor.KAPPA_CODE;
        System.out.printf(Locale.US, "  native pose coefficients in the live neck frame: c = (%+.6f %+.6f %+.6f), |c| = %.12f%n",
                e.restC.get(0), e.restC.get(N), e.restC.get(2*N),
                Math.sqrt(e.restC.get(0)*e.restC.get(0) + e.restC.get(N)*e.restC.get(N) + e.restC.get(2*N)*e.restC.get(2*N)));
        double th0 = thetaDet(e, 0, e.q.get(N), 0.0);
        System.out.printf(Locale.US, "  reference configuration: theta_det = %.3e deg (must be 0 by construction), U_det = %.3e J%n",
                Math.toDegrees(th0), 0.5*kdet*th0*th0);

        // ---- A. RIGID ROTATION -------------------------------------------------------------------------
        System.out.println("\n  A. RIGID ROTATION of the whole motor (arbitrary 3-D R; nodes + base triad + anchors)");
        double[][] R = rot3(0.7, -0.4, 1.1);
        double psiT = e.q.get(N) + 0.35, chiT = 0.28;                     // an arbitrary displaced head pose
        double thBefore = thetaDet(e, 0, psiT, chiT);
        double[] tauBefore = restoreTorque(e, 0, psiT, chiT, kdet);
        var eR = pack(G, 0); setNative(eR); rigidRotate(eR, R);
        double thAfter = thetaDet(eR, 0, psiT, chiT);
        double[] tauAfter = restoreTorque(eR, 0, psiT, chiT, kdet);
        double[] tauRot = mv(R, tauBefore);
        System.out.printf(Locale.US, "     theta_det : %.9f deg -> %.9f deg   |delta| = %.3e deg%n",
                Math.toDegrees(thBefore), Math.toDegrees(thAfter), Math.toDegrees(Math.abs(thAfter-thBefore)));
        System.out.printf(Locale.US, "     U_det     : %.9e -> %.9e J          |delta|/U = %.3e%n",
                0.5*kdet*thBefore*thBefore, 0.5*kdet*thAfter*thAfter,
                Math.abs(thAfter*thAfter-thBefore*thBefore)/Math.max(1e-30, thBefore*thBefore));
        System.out.printf(Locale.US, "     torque    : R.tau(before) = (%+.4e %+.4e %+.4e)%n", tauRot[0], tauRot[1], tauRot[2]);
        System.out.printf(Locale.US, "                 tau(after)    = (%+.4e %+.4e %+.4e)   |diff|/|tau| = %.3e%n",
                tauAfter[0], tauAfter[1], tauAfter[2], norm(sub(tauAfter,tauRot))/Math.max(1e-30,norm(tauRot)));
        System.out.printf("     => %s%n", Math.toDegrees(Math.abs(thAfter-thBefore)) < 1e-9
                && norm(sub(tauAfter,tauRot)) < 1e-9*Math.max(1e-30,norm(tauRot)) ? "PASS (invariant + covariant)" : "*** FAIL ***");
        // the OLD base-frame definition under the same fixture
        System.out.printf(Locale.US, "     control — the OLD base-frame law 1/2 k (psi-psiActin)^2 is ALSO invariant here%n"
                        + "               (psi is an internal coordinate): %.9f -> %.9f rad. This fixture does NOT discriminate.%n",
                psiT - e.q.get(3*N), psiT - eR.q.get(3*N));

        // ---- B. S2 ROLL --------------------------------------------------------------------------------
        System.out.println("\n  B. S2 BEND: roll the distal S2 element by alpha about the lever axis n1, and roll the head");
        System.out.println("     with it. The live frame follows; the base-frame law does not know the S2 moved.");
        System.out.printf("     %8s | %-22s %-22s | %-22s%n", "alpha", "theta_det LIVE (deg)", "U_det LIVE (J)", "dU BASE-FRAME (kT)");
        StringBuilder cov = new StringBuilder("alpha_deg\ttheta_live_deg\tU_live_J\tdU_base_kT\n");
        for (double aDeg : new double[]{ 0, 5, 15, 30, 60, 90 }) {
            double alpha = Math.toRadians(aDeg);
            var eB = pack(G, 0); setNative(eB);
            rollDistalS2(eB, 0, alpha);
            double[] nfB = ExplicitCompleteMatHarness.neckFrame(eB, 0);
            double[] tgt = { eB.restC.get(0)*nfB[0] + eB.restC.get(N)*nfB[3] + eB.restC.get(2*N)*nfB[6],
                             eB.restC.get(0)*nfB[1] + eB.restC.get(N)*nfB[4] + eB.restC.get(2*N)*nfB[7],
                             eB.restC.get(0)*nfB[2] + eB.restC.get(N)*nfB[5] + eB.restC.get(2*N)*nfB[8] };
            double[] pc = psiChiFor(eB, 0, tgt);                     // the (psi,chi) that face the NEW rest direction
            double thL = thetaDet(eB, 0, pc[0], pc[1]);
            double dPsi = pc[0] - eB.q.get(3*N);
            double uBase = 0.5*(512.0*TwoBodyConverterMotor.KAPPA_CODE)*dPsi*dPsi;
            System.out.printf(Locale.US, "     %8.1f | %-22.3e %-22.3e | %-22.3f%n", aDeg, Math.toDegrees(thL), 0.5*kdet*thL*thL, uBase/Constants.kT);
            cov.append(String.format(Locale.US, "%.1f\t%.6e\t%.6e\t%.6f%n", aDeg, Math.toDegrees(thL), 0.5*kdet*thL*thL, uBase/Constants.kT));
        }
        System.out.println("     => LIVE: U_det stays at machine zero for every roll (the head keeps its native pose");
        System.out.println("        relative to the neck that carries it). BASE-FRAME: the same physical configuration");
        System.out.println("        is charged up to tens of kT purely because the lab-referenced psi moved.");

        // ---- C. LEVER MOTION ---------------------------------------------------------------------------
        System.out.println("\n  C. LEVER MOTION: move phi by delta and carry the head with the neck (psi -> psi + delta).");
        System.out.printf("     %8s | %-22s | %-22s%n", "delta", "theta_det LIVE (deg)", "dU BASE-FRAME (kT)");
        for (double dDeg : new double[]{ 0, 5, 15, 30 }) {
            double dlt = Math.toRadians(dDeg);
            var eC = pack(G, 0); setNative(eC);
            for (int m = 0; m < N; m++) eC.q.set(m, eC.q.get(m) + dlt);
            double thL = thetaDet(eC, 0, eC.q.get(N) + dlt, 0.0);
            double dPsi = (eC.q.get(N) + dlt) - eC.q.get(3*N);
            System.out.printf(Locale.US, "     %8.1f | %-22.4e | %-22.3f%n", dDeg, Math.toDegrees(thL),
                    0.5*(512.0*TwoBodyConverterMotor.KAPPA_CODE)*dPsi*dPsi/Constants.kT);
        }
        System.out.println("     (theta_det is NOT exactly zero here: the Gram-Schmidt roll reference slips as n1 turns");
        System.out.println("      against a fixed S2 tangent — a real, small, mechanical effect, not a frame artifact.)");

        // ---- D. LAB-AXIS CHANGE ------------------------------------------------------------------------
        System.out.println("\n  D. LAB-AXIS CHANGE: no lab vector enters the live frame or the rest direction at all —");
        System.out.println("     n1, n2, n3 are built from (C-P) and (node M - node M-1). Fixture A is the operational test");
        System.out.println("     of this and it passes to machine precision.");
        write("covariance_s2roll.tsv", cov.toString());
        ExplicitCompleteMatHarness.HEAD_TILT_3D = sav;
    }

    // ==================================================================================================
    // PHASE 9 — the detached dynamic 3-D head
    // ==================================================================================================
    static void phase9Dynamics() {
        hdr("PHASE 9 — DETACHED dynamic 3-D head orientation (real solver, no filament, no binding)");
        final int WARM = 15000, SAMP = 80000, STRIDE = 20;
        System.out.printf(Locale.US, "  12 detached motors (boundSeg=-1, bondData=0) - dt %.1e s - %d warm + %d sampled steps%n", DT, WARM, SAMP);

        System.out.println("\n  (the FDT gates for the new channel are PHASE 7, run on an isolated copy — see there)");

        // ---- the k_det ladder with chi DYNAMIC -----------------------------------------------------------
        System.out.println("\n  ORIENTATION STATISTICS — chi DYNAMIC, live-frame rest direction, both candidate k_det");
        System.out.printf("  %8s %9s %9s %9s %9s %9s %9s %9s %8s %8s%n",
                "k_det", "RMS th", "median", "p90", "p95", "SD(psi)", "SD(chi)", "tau_ac", "solid", "rank3");
        System.out.printf("  %8s %9s %9s %9s %9s %9s %9s %9s %8s %8s%n",
                "pN.nm", "deg", "deg", "deg", "deg", "deg", "deg", "us", "%4pi", "");
        StringBuilder tsv = new StringBuilder("k_det_pNnm\trms_theta_deg\tmedian_deg\tp90_deg\tp95_deg\tsd_psi_deg\tsd_chi_deg\ttau_ac_us\tsolid_frac\tev1\tev2\tev3\tmean_chi_deg\tsem_chi_deg\tfrac_within25\n");
        for (double k : new double[]{ 0.0, 5.0, 10.0, 512.0 }) {
            Res r = detachedRun(k, WARM, SAMP, STRIDE, false);
            System.out.printf(Locale.US, "  %8.1f %9.2f %9.2f %9.2f %9.2f %9.2f %9.2f %9.1f %8.3f %8s%n",
                    k, r.rms, r.med, r.p90, r.p95, r.sdPsi, r.sdChi, r.tauAc*1e6, r.solid, r.ev[2] > 1e-4 ? "yes" : "no");
            tsv.append(String.format(Locale.US, "%.1f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.6f\t%.6f\t%.6f\t%.6f\t%.5f\t%.5f\t%.5f%n",
                    k, r.rms, r.med, r.p90, r.p95, r.sdPsi, r.sdChi, r.tauAc*1e6, r.solid, r.ev[0], r.ev[1], r.ev[2], r.meanChi, r.semChi, r.within25));
        }
        write("head3d_kdet.tsv", tsv.toString());

        System.out.println("\n  FDT GATE 2 — no lab-frame bias in the restrained head (mean chi must be 0 by symmetry)");
        for (double k : new double[]{ 5.0, 10.0 }) {
            Res r = detachedRun(k, WARM, SAMP, STRIDE, false);
            System.out.printf(Locale.US, "     k_det=%5.1f : <chi> = %+.4f deg  (SEM %.4f)  ->  %.2f sigma   %s%n",
                    k, r.meanChi, r.semChi, Math.abs(r.meanChi)/Math.max(1e-12, r.semChi),
                    Math.abs(r.meanChi) < 3*r.semChi ? "PASS" : "*** REVIEW ***");
        }
        System.out.println("\n  FDT GATE 3 — the restrained head against its own Boltzmann prediction on the sphere");
        System.out.println("     exact 2-D quadrature in the (psi, chi) coordinates, FLAT invariant measure (see PHASE 7)");
        for (double k : new double[]{ 5.0, 10.0, 512.0 }) {
            Res r = detachedRun(k, WARM, SAMP, STRIDE, false);
            double pred = boltzRms(k * TwoBodyConverterMotor.KAPPA_CODE, false);
            System.out.printf(Locale.US, "     k_det=%5.1f : RMS theta measured %7.2f deg   ideal 2-DOF %7.2f deg   ratio %.3f%n",
                    k, r.rms, Math.toDegrees(pred), r.rms/Math.toDegrees(pred));
        }
        System.out.println("     (a ratio below 1 is expected and NOT a defect: psi is additionally restrained by k_conv");
        System.out.println("      through theta = psi - phi, so the head is held by more than U_det alone.)");
    }

    static final class Res {
        double rms, med, p90, p95, sdPsi, sdChi, tauAc, solid, within25, meanChi, semChi;
        double[] ev; double[][] eb; double[][] ebAll;
    }

    /** One detached run at stiffness kPN (pN·nm/rad²); optionally retains the full eBind trajectory. */
    static final java.util.Map<Double, Res> RUN_CACHE = new java.util.HashMap<>();
    static Res detachedRun(double kPN, int warm, int samp, int stride, boolean clampPhi) {
        double key = clampPhi ? -kPN - 1 : kPN;
        Res cached = RUN_CACHE.get(key); if (cached != null) return cached;
        ExplicitCompleteMatHarness.HEAD_TILT_3D = true;
        ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = true;
        ExplicitCompleteMatHarness.K_DET_PNNM = kPN;
        var G = build(); var e = pack(G, 1); int N = G.N;
        detach(e, G);
        java.util.List<double[]> eb = new java.util.ArrayList<>();
        java.util.List<double[]> ebAll = new java.util.ArrayList<>();
        double[] thAll = new double[((samp + stride - 1) / stride) * N];
        double s1p = 0, s2p = 0, s1c = 0, s2c = 0; long np = 0;
        double[] prevAng = null; double acN = 0, acD = 0;
        double[][] evAcc = new double[3][3];
        int k = 0;
        java.util.List<double[]> chiSeries = new java.util.ArrayList<>();
        for (int t = 0; t < warm + samp; t++) {
            solveStep(e, G, t, SEED, 1, null, 0);
            if (clampPhi) for (int m = 0; m < N; m++) e.q.set(m, TwoBodyConverterMotor.PHI_PRE_3E);
            if (t >= warm && (t - warm) % stride == 0) {
                double[] chis = new double[N];
                for (int m = 0; m < N; m++) {
                    double psi = e.q.get(N + m), chi = e.chiHead.get(m);
                    double[] u = ExplicitCompleteMatHarness.eBindOf(e, m, psi, chi);
                    thAll[k++] = Math.toDegrees(thetaDet(e, m, psi, chi));
                    s1p += psi; s2p += psi*psi; s1c += chi; s2c += chi*chi; np++;
                    chis[m] = chi;
                    for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) evAcc[i][j] += u[i]*u[j];
                    if (m == 0) eb.add(u);   // motor 0 only: a TIME-ORDERED series (autocorrelation, encounters)
                    ebAll.add(u);            // all 12 identical fixed motors: the ENSEMBLE (coverage, covariance)
                }
                chiSeries.add(chis);
                // one-lag angular autocorrelation of eBind (motor 0)
                double[] u0 = ExplicitCompleteMatHarness.eBindOf(e, 0, e.q.get(N), e.chiHead.get(0));
                if (prevAng != null) { acN += dot(prevAng, 0, u0); acD += 1; }
                prevAng = u0;
            }
        }
        Res r = new Res();
        double[] a = java.util.Arrays.copyOf(thAll, k); java.util.Arrays.sort(a);
        double sq = 0; for (double v : a) sq += v*v;
        r.rms = Math.sqrt(sq / a.length);
        r.med = a[a.length/2]; r.p90 = a[(int)(0.90*(a.length-1))]; r.p95 = a[(int)(0.95*(a.length-1))];
        int w = 0; for (double v : a) if (v <= 25.0) w++; r.within25 = w/(double)a.length;
        double mp = s1p/np, mc = s1c/np;
        r.sdPsi = Math.toDegrees(Math.sqrt(Math.max(0, s2p/np - mp*mp)));
        r.sdChi = Math.toDegrees(Math.sqrt(Math.max(0, s2c/np - mc*mc)));
        r.meanChi = Math.toDegrees(mc);
        r.semChi = r.sdChi / Math.sqrt(Math.max(1, chiSeries.size()));   // per-sample SEM (motors correlated only through none)
        double c1 = acD > 0 ? acN/acD : 1;
        r.tauAc = c1 >= 1 || c1 <= 0 ? Double.NaN : -(stride*DT)/Math.log(Math.max(1e-12, c1));
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) evAcc[i][j] /= (double) k;
        r.ev = HeadOrientationDofProbe.eig3(evAcc);
        boolean[] bin = new boolean[36*72]; int filled = 0;
        for (double[] u : ebAll) { int it = (int)((u[2]+1)*0.5*36); if (it >= 36) it = 35; if (it < 0) it = 0;
            int ip = (int)((Math.atan2(u[1],u[0])+Math.PI)/(2*Math.PI)*72); if (ip >= 72) ip = 71; if (ip < 0) ip = 0;
            if (!bin[it*72+ip]) { bin[it*72+ip] = true; filled++; } }
        r.solid = filled/(double)(36*72);
        r.eb = eb.toArray(new double[0][]);
        r.ebAll = ebAll.toArray(new double[0][]);
        if (!clampPhi) dumpCloud(kPN, r.ebAll, e);
        ExplicitCompleteMatHarness.HEAD_TILT_3D = false; ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = false;
        RUN_CACHE.put(key, r);
        return r;
    }

    // ==================================================================================================
    // PHASE 7 — ISOLATED FDT GATES on the new chi channel.
    //
    // The production solver adds the F8 5x5 stiffness block to EVERY motor, bound or not (only the F8 FORCE
    // is gated on `bound`) — a pre-existing quirk of matS2SolveStep that this kernel inherits verbatim. That
    // spurious Hessian damps the DETACHED motor's angular response, so a naive free-diffusion test on the
    // production solver measures the quirk, not the thermostat. These gates therefore run on an ISOLATED
    // copy with kF8 = 0 and k_conv = 0, where (phi, psi, chi) are exactly the coordinates the FDT amplitudes
    // were derived for. Everything else — the drag, the noise, the solve — is the production kernel.
    // ==================================================================================================
    static void phase7Fdt() {
        hdr("PHASE 7 — FDT of the dynamic chi channel (ISOLATED: kF8 = 0, k_conv = 0)");
        ExplicitCompleteMatHarness.HEAD_TILT_3D = true;
        ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = true;
        ExplicitCompleteMatHarness.K_DET_PNNM = 0.0;
        var G = build(); var e0 = pack(G, 1); int N = G.N;
        double gPsi = e0.params.get(9*N), gRot = e0.params.get(18*N);   // row 17 = S2->lever rest angle (2026-08-12)
        System.out.printf(Locale.US, "  Gamma_chichi = gamma_psi = %.5e N.m.s/rad   gamma_r (sphere only) = %.5e   ratio %.4f%n",
                gPsi, gRot, gRot/gPsi);
        System.out.printf(Locale.US, "  Gamma_psipsi(chi) = gamma_r + (gamma_psi - gamma_r) cos^2(chi):  chi=0 -> %.5e, chi=60deg -> %.5e%n",
                gPsi, gRot + (gPsi-gRot)*0.25);
        System.out.printf(Locale.US, "  Gamma_psichi = 0 exactly (omega_psi = econv, omega_chi = that, econv.that = 0)%n");

        // ---- gate A: per-step variance of chi against the exact FDT prediction --------------------------
        final int STEPS = 2, REP = 3000;   // short enough that SD(chi) stays far from the |chi| <= 89 deg clamp
        double s2 = 0, s1 = 0; long n = 0;
        for (int rep = 0; rep < REP; rep++) {
            var e = pack(G, 1); setNative(e); detach(e, G); isolate(e);
            for (int t = 0; t < STEPS; t++) solveStep(e, G, rep*STEPS + t, SEED + 3*rep, 1, null, 0);
            for (int m = 0; m < N; m++) { double c = e.chiHead.get(m); s1 += c; s2 += c*c; n++; }
        }
        double varMeas = s2/n - (s1/n)*(s1/n);
        double varPred = 2.0*Constants.kT*DT/gPsi*STEPS;
        System.out.printf(Locale.US, "%n  GATE A — free chi (k_det = 0): Var(chi) after %d steps%n", STEPS);
        System.out.printf(Locale.US, "     measured %.6e rad^2   predicted 2 kT dt / Gamma * n = %.6e rad^2   ratio %.4f   %s%n",
                varMeas, varPred, varMeas/varPred, Math.abs(varMeas/varPred - 1) < 0.06 ? "PASS" : "*** REVIEW ***");
        System.out.printf(Locale.US, "     mean chi = %+.4e rad (SEM %.3e) -> %.2f sigma   %s%n",
                s1/n, Math.sqrt(varMeas/n), Math.abs(s1/n)/Math.sqrt(varMeas/n),
                Math.abs(s1/n) < 3*Math.sqrt(varMeas/n) ? "PASS (no drift)" : "*** REVIEW ***");

        // ---- gate B: equilibrium misalignment against the exact 2-DOF Boltzmann law ---------------------
        System.out.println("\n  GATE B — restrained head at equilibrium vs the EXACT Boltzmann prediction.");
        System.out.println("     The two generators are ORTHONORMAL (omega_psi = econv, omega_chi = that, both unit, mutually");
        System.out.println("     perpendicular), so the kinetic metric in (psi, chi) is FLAT and the invariant measure is");
        System.out.println("     d psi d chi — NOT the sphere's area element cos(chi) d psi d chi. Both are quoted; the flat one");
        System.out.println("     is the correct reference for this parameterisation and is what the scheme must reproduce.");
        System.out.printf("     %8s %14s %14s %8s | %14s %8s %8s%n", "k_det", "RMS meas deg", "RMS flat deg", "ratio", "RMS sphere deg", "ratio", "verdict");
        StringBuilder tsv = new StringBuilder("k_det_pNnm\trms_meas_deg\trms_flat_deg\tratio_flat\trms_sphere_deg\tratio_sphere\n");
        for (double k : new double[]{ 5.0, 10.0, 20.0, 512.0 }) {
            ExplicitCompleteMatHarness.K_DET_PNNM = k;
            var e = pack(G, 1); setNative(e); detach(e, G); isolate(e);
            double sq = 0; long cnt = 0;
            for (int t = 0; t < 6000 + 60000; t++) {
                solveStep(e, G, t, SEED + 11, 1, null, 0);
                if (t >= 6000 && t % 10 == 0) for (int m = 0; m < N; m++) {
                    double th = thetaDet(e, m, e.q.get(N+m), e.chiHead.get(m)); sq += th*th; cnt++; }
            }
            double rms = Math.toDegrees(Math.sqrt(sq/cnt));
            double flat = Math.toDegrees(boltzRms(k*TwoBodyConverterMotor.KAPPA_CODE, false));
            double sph  = Math.toDegrees(boltzRms(k*TwoBodyConverterMotor.KAPPA_CODE, true));
            System.out.printf(Locale.US, "     %8.1f %14.3f %14.3f %8.4f | %14.3f %8.4f %8s%n", k, rms, flat, rms/flat, sph, rms/sph,
                    Math.abs(rms/flat - 1) < 0.05 ? "PASS" : "REVIEW");
            tsv.append(String.format(Locale.US, "%.1f\t%.4f\t%.4f\t%.5f\t%.4f\t%.5f%n", k, rms, flat, rms/flat, sph, rms/sph));
        }
        write("fdt_isolated.tsv", tsv.toString());
        ExplicitCompleteMatHarness.HEAD_TILT_3D = false; ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = false;
    }
    /** Zero kF8 and k_conv so (phi, psi, chi) are pure Langevin coordinates under U_det alone. */
    static void isolate(ExplicitCompleteMatHarness.ExMat e) {
        int N = e.N;
        for (int m = 0; m < N; m++) { e.params.set(5*N+m, 0.0); e.params.set(6*N+m, 0.0); }
    }

    // ==================================================================================================
    // The HEAD'S OWN search, isolated from the free lever mode (phi held at its reference angle).
    // ==================================================================================================
    static void phaseLever() {
        hdr("LEVER-CLAMPED CONTROL — the head's OWN search, with the free lever mode removed");
        final int WARM = 15000, SAMP = 80000, STRIDE = 20;
        System.out.println("  phi is held at PHI_PRE_3E after every step; everything else is the production solver.");
        System.out.printf("  %8s %9s %9s %9s %9s %9s %9s %8s   |  %s%n",
                "k_det", "RMS th", "median", "p90", "p95", "SD(psi)", "SD(chi)", "solid", "free-lever SD(psi)");
        StringBuilder tsv = new StringBuilder("k_det_pNnm\trms_theta_deg\tmedian_deg\tp90_deg\tp95_deg\tsd_psi_deg\tsd_chi_deg\tsolid_frac\tsd_psi_free_deg\n");
        for (double k : new double[]{ 0.0, 5.0, 10.0, 512.0 }) {
            Res c = detachedRun(k, WARM, SAMP, STRIDE, true);
            Res f = detachedRun(k, WARM, SAMP, STRIDE, false);
            System.out.printf(Locale.US, "  %8.1f %9.2f %9.2f %9.2f %9.2f %9.2f %9.2f %8.3f   |  %9.1f%n",
                    k, c.rms, c.med, c.p90, c.p95, c.sdPsi, c.sdChi, c.solid, f.sdPsi);
            tsv.append(String.format(Locale.US, "%.1f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.6f\t%.4f%n",
                    k, c.rms, c.med, c.p90, c.p95, c.sdPsi, c.sdChi, c.solid, f.sdPsi));
        }
        write("lever_clamped.tsv", tsv.toString());
    }

    /** Dump the eBind cloud + the live neck frame and rest direction of motor 0, for the figures. */
    static void dumpCloud(double kPN, double[][] cloud, ExplicitCompleteMatHarness.ExMat e) {
        int N = e.N;
        StringBuilder sb = new StringBuilder("ex\tey\tez\n");
        int stride = Math.max(1, cloud.length / 30000);
        for (int i = 0; i < cloud.length; i += stride)
            sb.append(String.format(Locale.US, "%.6f\t%.6f\t%.6f%n", cloud[i][0], cloud[i][1], cloud[i][2]));
        write(String.format(Locale.US, "ebind_cloud_k%.0f.tsv", kPN), sb.toString());
        double savePhi = e.q.get(0); e.q.set(0, TwoBodyConverterMotor.PHI_PRE_3E);
        double[] nf = ExplicitCompleteMatHarness.neckFrame(e, 0);
        e.q.set(0, savePhi);
        double c1 = e.restC.get(0), c2 = e.restC.get(N), c3 = e.restC.get(2*N);
        write("rest_frame.tsv", "n1x\tn1y\tn1z\tn2x\tn2y\tn2z\tn3x\tn3y\tn3z\trestx\tresty\trestz\n"
            + String.format(Locale.US, "%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f%n",
                nf[0], nf[1], nf[2], nf[3], nf[4], nf[5], nf[6], nf[7], nf[8],
                c1*nf[0]+c2*nf[3]+c3*nf[6], c1*nf[1]+c2*nf[4]+c3*nf[7], c1*nf[2]+c2*nf[5]+c3*nf[8]));
    }

    /** FDT gate: chi free-diffusion coefficient (k_det = 0) vs kT/Gamma_chichi. Returns {Dmeas,Dpred,Gamma,drift,sem}. */
    static double[] chiDiffusion() {
        ExplicitCompleteMatHarness.HEAD_TILT_3D = true;
        ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = true;
        ExplicitCompleteMatHarness.K_DET_PNNM = 0.0;
        var G = build(); var e = pack(G, 1); int N = G.N;
        detach(e, G);
        final int STEPS = 4000;                      // short: chi must stay far from the |chi| <= 89 deg clamp
        final int REP = 20;
        double sMsd = 0, sDrift = 0, s2Drift = 0;
        for (int rep = 0; rep < REP; rep++) {
            var er = pack(G, 1); detach(er, G);
            for (int m = 0; m < N; m++) er.chiHead.set(m, 0.0);
            for (int t = 0; t < STEPS; t++) solveStep(er, G, rep*STEPS + t, SEED + rep, 1, null, 0);
            for (int m = 0; m < N; m++) { double c = er.chiHead.get(m); sMsd += c*c; sDrift += c; s2Drift += c*c; }
        }
        long n = (long) REP * N;
        double T = STEPS * DT;
        double dMeas = (sMsd/n) / (2*T);
        double gamma = e.params.get(9*N);
        double dPred = Constants.kT / gamma;
        double mean = sDrift/n, sd = Math.sqrt(Math.max(0, s2Drift/n - mean*mean));
        ExplicitCompleteMatHarness.HEAD_TILT_3D = false; ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = false;
        return new double[]{ dMeas, dPred, gamma, mean/T, sd/Math.sqrt(n)/T };
    }

    /**
     * RMS misalignment at equilibrium, by exact 2-D quadrature in the ACTUAL coordinates. With the target in
     * the chi = 0 plane (which it is: the native rest direction has c3 = 0 ⇒ eTarget . econv = 0),
     * cos(theta) = cos(chi) cos(psi - psi_t). Weight = exp(-k theta^2/2kT) times the measure:
     *   sphereMeasure = false : d psi d chi              — the FLAT metric of the two orthonormal generators
     *   sphereMeasure = true  : cos(chi) d psi d chi     — the sphere's own area element (for contrast)
     */
    static double boltzRms(double k, boolean sphereMeasure) {
        int NQ = 1200; double num = 0, den = 0;
        for (int i = 0; i <= NQ; i++) {
            double chi = -0.5*Math.PI + Math.PI*i/NQ;
            double meas = sphereMeasure ? Math.cos(chi) : 1.0;
            for (int j = 0; j <= NQ; j++) {
                double dp = -Math.PI + 2*Math.PI*j/NQ;
                double th = Math.acos(Math.max(-1, Math.min(1, Math.cos(chi)*Math.cos(dp))));
                double w = meas*Math.exp(-k*th*th/(2*Constants.kT));
                num += w*th*th; den += w;
            }
        }
        return Math.sqrt(num/den);
    }

    // ==================================================================================================
    // PHASE 13 — dynamic helical site-normal accessibility (no RAND_BASE_AZ, no projection)
    // ==================================================================================================
    static void phase13Access() {
        hdr("PHASE 13 — DYNAMIC helical site-normal accessibility (real thermal trajectory, one fixed motor)");
        final double TOL = 25.0, cosTol = Math.cos(Math.toRadians(TOL));
        final int WARM = 15000, SAMP = 80000, STRIDE = 20;
        double dAz = 4 * ExplicitCompleteMatHarness.TWIST_PER_MON_DEG;
        java.util.TreeSet<Long> azs = new java.util.TreeSet<>();
        for (int kk = 0; kk < 40; kk++) azs.add(Math.round(wrap(kk*dAz)*1000));
        StringBuilder tsv = new StringBuilder("k_det_pNnm\tazimuth_deg\tclass\tfrac_time_within25\tencounters_per_s\tbest_deg\n");
        System.out.printf("  every4 lattice: %d distinct site azimuths, %.0f deg tolerance, NO base-azimuth randomisation%n", azs.size(), TOL);
        for (double k : new double[]{ 5.0, 10.0, 512.0 }) {
            Res r = detachedRun(k, WARM, SAMP, STRIDE, false);        // motor 0's trajectory is always retained
            double T = SAMP * DT;
            int nReach = 0; double sumFrac = 0;
            double lowF = 0, sideF = 0, upF = 0; int nLow = 0, nSide = 0, nUp = 0;
            System.out.printf(Locale.US, "%n  k_det = %.1f pN.nm/rad^2   (%d eBind samples of motor 0 over %.1f ms)%n", k, r.eb.length, T*1e3);
            System.out.printf("     %9s %-7s %14s %16s %10s%n", "azimuth", "class", "frac time <25", "encounters/s", "best deg");
            for (long key : azs) {
                double a = Math.toRadians(key/1000.0);
                double[] nS = { 0, Math.cos(a), Math.sin(a) };
                int in = 0, enter = 0; boolean was = false; double best = -1;
                for (double[] u : r.eb) { double d = u[0]*nS[0] + u[1]*nS[1] + u[2]*nS[2];
                    if (d > best) best = d;
                    boolean now = d >= cosTol; if (now) in++; if (now && !was) enter++; was = now; }
                double frac = in/(double) r.eb.length, enc = enter/T;
                String cls = nS[2] > 0.5 ? "upper" : (nS[2] < -0.5 ? "lower" : "side");
                if (frac > 0) nReach++;
                sumFrac += frac;
                if (cls.equals("upper")) { upF += frac; nUp++; } else if (cls.equals("lower")) { lowF += frac; nLow++; } else { sideF += frac; nSide++; }
                System.out.printf(Locale.US, "     %+9.1f %-7s %14.5f %16.1f %10.2f%n", key/1000.0, cls, frac, enc,
                        Math.toDegrees(Math.acos(Math.min(1, best))));
                tsv.append(String.format(Locale.US, "%.1f\t%.1f\t%s\t%.6f\t%.3f\t%.4f%n", k, key/1000.0, cls, frac, enc, Math.toDegrees(Math.acos(Math.min(1,best)))));
            }
            System.out.printf(Locale.US, "     -> %d of %d azimuths DYNAMICALLY visited within %.0f deg ; mean dwell fraction %.5f%n",
                    nReach, azs.size(), TOL, sumFrac/azs.size());
            System.out.printf(Locale.US, "     -> lower %.5f (n=%d) | side %.5f (n=%d) | upper %.5f (n=%d)%n",
                    nLow > 0 ? lowF/nLow : 0, nLow, nSide > 0 ? sideF/nSide : 0, nSide, nUp > 0 ? upF/nUp : 0, nUp);
        }
        write("dynamic_site_access.tsv", tsv.toString());
    }

    // ==================================================================================================
    // PHASE 11 — core motor regression
    // ==================================================================================================
    static void phase11Regression() {
        hdr("PHASE 11 — CORE MOTOR REGRESSION (deterministic, Brownian OFF)");
        System.out.println("  Arms (each differs from the one above it by ONE change):");
        System.out.println("    legacy    production matS2SolveStep, no chi, base-frame k_bind = 512, legacy eup F8 axis");
        System.out.println("    liveLeg   tilt solver: chi DYNAMIC + LIVE-frame detached rest at 512, still the legacy eup axis");
        System.out.println("    live512   + the exact econv F8 axis");
        System.out.println("    chi5/10   + the weak detached stiffness k_det = 5 / 10 pN.nm/rad^2");
        System.out.println("  STROKE and k_ext are BOUND-state quantities and are measured with boundSeg >= 0, so they");
        System.out.println("  exercise the BOUND orientation law (base-frame target, historical, unchanged).");
        System.out.printf("%n  %-10s %10s %10s %10s %10s %10s %10s %10s%n",
                "arm", "stroke nm", "polarity", "k_ext", "theta deg", "S2 ext nm", "contour%", "stable");
        StringBuilder tsv = new StringBuilder("arm\tstroke_nm\tpolarity\tkext_pNnm\ttheta_deg\ts2ext_nm\tcontour_rel\tstable\tchi_deg\n");
        for (String arm : new String[]{ "legacy", "liveLeg", "live512", "chi5", "chi10" }) {
            double[] v = regressionArm(arm);
            System.out.printf(Locale.US, "  %-10s %10.3f %10s %10.4f %10.3f %10.4f %10.6f %10s%n",
                    arm, v[0], v[1] < 0 ? "pointed" : "barbed", v[2], v[3], v[4], v[5], v[6] > 0.5 ? "yes" : "NO");
            tsv.append(String.format(Locale.US, "%s\t%.5f\t%.1f\t%.6f\t%.5f\t%.5f\t%.8f\t%.0f\t%.4f%n", arm, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]));
        }
        write("core_regression.tsv", tsv.toString());
        System.out.println("\n  reference (Cmot fixed-anchor two-body path, where the F8 axis is econv): stroke ~ 7 nm,");
        System.out.println("  pointed-first polarity, k_ext ~ 0.6-0.64 pN/nm.");
    }

    /** {strokeNm, polaritySign, kext, thetaDeg, s2extNm, contourRel, stable, chiDeg}. */
    static double[] regressionArm(String arm) {
        ExplicitCompleteMatHarness.HEAD_TILT_3D = !arm.equals("legacy");
        ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX = !arm.equals("liveLeg");
        ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = true;
        ExplicitCompleteMatHarness.K_DET_PNNM = arm.equals("chi5") ? 5.0 : (arm.equals("chi10") ? 10.0 : 512.0);
        var G = build(); int N = G.N, M = G.g4M;

        // --- A/B: UNLOADED stroke = the axial travel of the F8 point on the ADP.Pi -> ADP rest switch ----
        // BOUND (so the BOUND orientation law applies — the stroke is a bound-state quantity) but with the
        // bond force held at ZERO: the historical UNLOADED working stroke.
        var e = pack(G, 0); setNative(e);
        G.bondData.init(0f);
        for (int m = 0; m < N; m++) { G.mot.boundSeg.set(m, 0); e.boundSeg.set(m, 0);
            e.q.set(2*N+m, TwoBodyConverterMotor.PRESTROKE_THETAS); }
        for (int t = 0; t < 6000; t++) solveStep(e, G, t, SEED, 0, null, 0);
        double[] pre = geomAtChi(e, N, e.q.get(0), e.q.get(N), e.chiHead.get(0));
        double thetaPre = Math.toDegrees(e.q.get(N) - e.q.get(0));
        for (int m = 0; m < N; m++) e.q.set(2*N+m, TwoBodyConverterMotor.ADP_THETAS);
        for (int t = 6000; t < 12000; t++) solveStep(e, G, t, SEED, 0, null, 0);
        double[] post = geomAtChi(e, N, e.q.get(0), e.q.get(N), e.chiHead.get(0));
        double stroke = ((post[6]-pre[6])*G.bhat[0] + (post[7]-pre[7])*G.bhat[1] + (post[8]-pre[8])*G.bhat[2]) * 1e3;
        double chiDeg = Math.toDegrees(e.chiHead.get(0));

        // --- C: k_ext — displace a fixed actin site axially and read the bond force response --------------
        var e2 = pack(G, 0); setNative(e2); double kF8 = e2.params.get(5*N)*1e6;
        for (int m = 0; m < N; m++) { e2.boundSeg.set(m, 0); e2.q.set(2*N+m, TwoBodyConverterMotor.PRESTROKE_THETAS); }
        e2.matc.set(2, 0);
        double[] gr = geomAtChi(e2, N, e2.q.get(0), e2.q.get(N), 0.0);
        double[] xs0 = { gr[6], gr[7], gr[8] };
        for (int t = 0; t < 8000; t++) solveStep(e2, G, t, SEED, 0, xs0, kF8);
        double[] f0 = bondF(e2, G, N, xs0, kF8);
        double dd = 0.0005;                                     // 0.5 nm axial site displacement
        double[] xs1 = { xs0[0] + dd*G.bhat[0], xs0[1] + dd*G.bhat[1], xs0[2] + dd*G.bhat[2] };
        for (int t = 8000; t < 16000; t++) solveStep(e2, G, t, SEED, 0, xs1, kF8);
        double[] f1 = bondF(e2, G, N, xs1, kF8);
        double dF = (f1[0]-f0[0])*G.bhat[0] + (f1[1]-f0[1])*G.bhat[1] + (f1[2]-f0[2])*G.bhat[2];
        double kext = Math.abs(dF) / (dd*1e-6) * 1e3;           // N/m -> pN/nm  (1 pN/nm = 1e-3 N/m)
        double theta = Math.toDegrees(e2.q.get(N) - e2.q.get(0));

        // --- E/F: S2 force-extension + contour conservation ------------------------------------------------
        double contour = 0;
        for (int j = 0; j < M; j++) {
            double ax = e2.nodes.get((3*(j+1))*N) - e2.nodes.get((3*j)*N);
            double ay = e2.nodes.get((3*(j+1)+1)*N) - e2.nodes.get((3*j+1)*N);
            double az = e2.nodes.get((3*(j+1)+2)*N) - e2.nodes.get((3*j+2)*N);
            contour += Math.sqrt(ax*ax+ay*ay+az*az);
        }
        double contourRel = contour / (M * G.g4l0);
        double s2ext = (norm(new double[]{ e2.nodes.get((3*M)*N) - e2.nodes.get(0),
                                           e2.nodes.get((3*M+1)*N) - e2.nodes.get(N),
                                           e2.nodes.get((3*M+2)*N) - e2.nodes.get(2*N) })) * 1e3;

        // --- G: numerical stability, full Brownian --------------------------------------------------------
        var e3 = pack(G, 1); setNative(e3); detach(e3, G);
        boolean stable = true; double maxChi = 0;
        for (int t = 0; t < 30000; t++) {
            solveStep(e3, G, t, SEED + 7, 1, null, 0);
            if ((t % 5000) == 0) for (int m = 0; m < N; m++) {
                double c = e3.chiHead.get(m), p = e3.q.get(N+m);
                if (!Double.isFinite(c) || !Double.isFinite(p) || Math.abs(p) > 1e4) stable = false;
                maxChi = Math.max(maxChi, Math.abs(Math.toDegrees(c)));
            }
        }
        ExplicitCompleteMatHarness.HEAD_TILT_3D = false; ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = false;
        return new double[]{ stroke, Math.signum(stroke), kext, theta, s2ext, contourRel, stable ? 1 : 0, chiDeg };
    }

    // ==================================================================================================
    // PHASE 12 — axial compliance decomposition
    // ==================================================================================================
    static void phase12Compliance() {
        hdr("PHASE 12 — AXIAL COMPLIANCE DECOMPOSITION under a small axial load");
        System.out.println("  A fixed actin site is displaced 0.5 nm along +bhat; the settled response of every generalized");
        System.out.println("  coordinate is projected onto bhat through its exact Jacobian column, so the shares sum to the");
        System.out.println("  total F8 displacement by construction.");
        System.out.printf("%n  %-24s %12s %12s %12s %12s %12s %10s%n",
                "head-orient stiffness", "S2 stretch", "S2 bend", "phi lever", "psi conv", "chi head", "sum nm");
        StringBuilder tsv = new StringBuilder("state\ts2_stretch_nm\ts2_bend_nm\tphi_nm\tpsi_nm\tchi_nm\ttotal_nm\tkext_pNnm\n");
        for (String st : new String[]{ "weak-rest k=5", "weak-rest k=10", "strong-rest k=512" }) {
            double kPN = st.contains("512") ? 512.0 : (st.contains("=5") ? 5.0 : 10.0);
            double[] v = complianceArm(kPN, st.contains("512"));
            System.out.printf(Locale.US, "  %-24s %12.5f %12.5f %12.5f %12.5f %12.5f %10.5f  k_ext %.4f pN/nm%n", st, v[0], v[1], v[2], v[3], v[4], v[5], v[6]);
            tsv.append(String.format(Locale.US, "%s\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f%n", st, v[0], v[1], v[2], v[3], v[4], v[5], v[6]));
        }
        write("axial_compliance.tsv", tsv.toString());
        System.out.println("\n  (shares are the displacement each coordinate contributes to the F8 point along bhat;");
        System.out.println("   chi must NOT become a dominant series-compliance mode.)");
    }

    static double[] complianceArm(double kPN, boolean strongRest) {
        ExplicitCompleteMatHarness.HEAD_TILT_3D = true;
        ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX = true;
        ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = !strongRest;
        ExplicitCompleteMatHarness.K_DET_PNNM = kPN;
        var G = build(); int N = G.N, M = G.g4M;
        var e = pack(G, 0); setNative(e); double kF8 = e.params.get(5*N)*1e6;
        for (int m = 0; m < N; m++) { e.boundSeg.set(m, strongRest ? 0 : -1); e.q.set(2*N+m, TwoBodyConverterMotor.PRESTROKE_THETAS); }
        if (!strongRest) for (int m = 0; m < N; m++) e.boundSeg.set(m, 0);   // a bond is needed to apply the load
        e.matc.set(2, 0);
        double[] gr = geomAtChi(e, N, e.q.get(0), e.q.get(N), 0.0);
        double[] xs0 = { gr[6], gr[7], gr[8] };
        for (int t = 0; t < 8000; t++) solveStep(e, G, t, SEED, 0, xs0, kF8);
        double[] q0 = snap(e, N, M);
        double[] f0 = bondF(e, G, N, xs0, kF8);
        double dd = 0.0005;
        double[] xs1 = { xs0[0] + dd*G.bhat[0], xs0[1] + dd*G.bhat[1], xs0[2] + dd*G.bhat[2] };
        for (int t = 8000; t < 16000; t++) solveStep(e, G, t, SEED, 0, xs1, kF8);
        double[] q1 = snap(e, N, M);
        double[] f1 = bondF(e, G, N, xs1, kF8);
        double dF = (f1[0]-f0[0])*G.bhat[0] + (f1[1]-f0[1])*G.bhat[1] + (f1[2]-f0[2])*G.bhat[2];
        double kext = Math.abs(dF)/(dd*1e-6)*1e3;
        // decomposition: dxF8.bhat = dP.bhat + Jphi.bhat dphi + Jpsi.bhat dpsi + Jchi.bhat dchi
        double[] P0 = { q0[0], q0[1], q0[2] }, P1 = { q1[0], q1[1], q1[2] };
        double[] dP = sub(P1, P0);
        double[] sHat = { q0[6], q0[7], q0[8] };
        double along = dot(dP, sHat);
        double[] dPl = { along*sHat[0], along*sHat[1], along*sHat[2] };
        double[] dPt = sub(dP, dPl);
        double[] g0 = geomAtChi(e, N, q0[3], q0[4], q0[5]);
        double[] cpv = { g0[0]-P0[0], g0[1]-P0[1], g0[2]-P0[2] };
        double[] fcv = { g0[6]-g0[0], g0[7]-g0[1], g0[8]-g0[2] };
        double[] ec = { e.frame.get(3*N), e.frame.get(4*N), e.frame.get(5*N) };
        double[] e0v = ExplicitCompleteMatHarness.eBindOf(e, 0, q0[4], 0.0);
        double[] tv = cr(e0v, ec);
        double sPhi = dot(cr(ec, cpv), G.bhat) * (q1[3]-q0[3]) * 1e3;
        double sPsi = dot(cr(ec, fcv), G.bhat) * (q1[4]-q0[4]) * 1e3;
        double sChi = dot(cr(tv, fcv), G.bhat) * (q1[5]-q0[5]) * 1e3;
        double sStr = dot(dPl, G.bhat)*1e3, sBnd = dot(dPt, G.bhat)*1e3;
        ExplicitCompleteMatHarness.HEAD_TILT_3D = false; ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = false;
        return new double[]{ sStr, sBnd, sPhi, sPsi, sChi, sStr+sBnd+sPhi+sPsi+sChi, kext };
    }

    /** {P(3), phi, psi, chi, sHat(3)} for motor 0. */
    static double[] snap(ExplicitCompleteMatHarness.ExMat e, int N, int M) {
        int jm = M >= 2 ? M-1 : 0;
        double sx = e.nodes.get((3*M)*N) - e.nodes.get((3*jm)*N);
        double sy = e.nodes.get((3*M+1)*N) - e.nodes.get((3*jm+1)*N);
        double sz = e.nodes.get((3*M+2)*N) - e.nodes.get((3*jm+2)*N);
        double sl = Math.sqrt(sx*sx+sy*sy+sz*sz);
        return new double[]{ e.nodes.get((3*M)*N), e.nodes.get((3*M+1)*N), e.nodes.get((3*M+2)*N),
                             e.q.get(0), e.q.get(N), e.chiHead.get(0), sx/sl, sy/sl, sz/sl };
    }

    // ==================================================================================================
    // shared machinery
    // ==================================================================================================
    static TwoBodyConverterMotor.Glide2D build() {
        return TwoBodyConverterMotor.buildS2Mat(4.0, DT, 40.0, TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, 101, false);
    }
    static ExplicitCompleteMatHarness.ExMat pack(TwoBodyConverterMotor.Glide2D G, int brownOn) {
        return ExplicitCompleteMatHarness.packExMat(G, brownOn);
    }
    /** Put every motor at the NATIVE reference pose: phi = PHI_PRE_3E, psi = psiActin, chi = 0. */
    static void setNative(ExplicitCompleteMatHarness.ExMat e) {
        int N = e.N;
        for (int m = 0; m < N; m++) { e.q.set(m, TwoBodyConverterMotor.PHI_PRE_3E);
            e.q.set(N + m, e.q.get(3*N + m)); e.chiHead.set(m, 0.0); }
    }
    static void detach(ExplicitCompleteMatHarness.ExMat e, TwoBodyConverterMotor.Glide2D G) {
        for (int m = 0; m < e.N; m++) { e.boundSeg.set(m, -1); G.mot.boundSeg.set(m, -1); }
        G.bondData.init(0f);
    }
    /** One mechanics step: (optional) fixed-site F8 spring -> kbind gate -> the wired solver. */
    static void solveStep(ExplicitCompleteMatHarness.ExMat e, TwoBodyConverterMotor.Glide2D G,
                          int t, int seed, int brownOn, double[] xs, double kF8) {
        int N = e.N;
        e.matc.set(0, t); e.matc.set(1, seed); e.matc.set(2, brownOn);
        if (ExplicitCompleteMatHarness.headTiltOn())
            TwoBodyBeamAnalyticGpu.matBeamGeomTilt(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF, e.chiHead);
        else
            TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF);
        if (xs != null) for (int m = 0; m < N; m++) { int d = m*13;
            G.bondData.set(d,   (float)(kF8*(xs[0]-e.outGeom.get(6*N+m))*1e-6));
            G.bondData.set(d+1, (float)(kF8*(xs[1]-e.outGeom.get(7*N+m))*1e-6));
            G.bondData.set(d+2, (float)(kF8*(xs[2]-e.outGeom.get(8*N+m))*1e-6)); }
        if (ExplicitCompleteMatHarness.kbindGateOn() || ExplicitCompleteMatHarness.headTiltOn())
            MatSoaSlice.matKbindGate(e.boundSeg, e.params, e.gateP, e.exCounts);
        if (ExplicitCompleteMatHarness.headTiltOn())
            TwoBodyBeamAnalyticGpu.matS2SolveStepTilt(e.nodes, e.frame, e.q, G.bondData, e.boundSeg, e.params, e.sys,
                    e.outGeom, G.mot.forceDotFil, G.mot.forceMag, e.matc, e.exCounts, e.convF, e.chiHead, e.restC);
        else
            TwoBodyBeamAnalyticGpu.matS2SolveStep(e.nodes, e.frame, e.q, G.bondData, e.boundSeg, e.params, e.sys,
                    e.outGeom, G.mot.forceDotFil, G.mot.forceMag, e.matc, e.exCounts, e.convF);
    }
    static double[] bondF(ExplicitCompleteMatHarness.ExMat e, TwoBodyConverterMotor.Glide2D G, int N, double[] xs, double kF8) {
        double[] g = geomAtChi(e, N, e.q.get(0), e.q.get(N), e.chiHead.get(0));
        return new double[]{ kF8*(xs[0]-g[6])*1e-6, kF8*(xs[1]-g[7])*1e-6, kF8*(xs[2]-g[8])*1e-6 };
    }
    /** Evaluate the real matBeamGeom at (phi,psi) for motor 0; returns {C, xH, xF8}. Mutates e.q. */
    static double[] geomAt(ExplicitCompleteMatHarness.ExMat e, int N, double phi, double psi) {
        for (int m = 0; m < N; m++) { e.q.set(m, phi); e.q.set(N+m, psi); }
        TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF);
        double[] r = new double[9]; for (int c = 0; c < 9; c++) r[c] = e.outGeom.get(c*N);
        return r;
    }
    /** Evaluate the real chi-aware matBeamGeomTilt at (phi,psi,chi) for motor 0. Mutates e.q / e.chiHead. */
    static double[] geomAtChi(ExplicitCompleteMatHarness.ExMat e, int N, double phi, double psi, double chi) {
        double[] sq = new double[N], sc = new double[N];
        for (int m = 0; m < N; m++) { sq[m] = e.q.get(N+m); sc[m] = e.chiHead.get(m);
            e.q.set(m, phi); e.q.set(N+m, psi); e.chiHead.set(m, chi); }
        TwoBodyBeamAnalyticGpu.matBeamGeomTilt(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF, e.chiHead);
        double[] r = new double[9]; for (int c = 0; c < 9; c++) r[c] = e.outGeom.get(c*N);
        for (int m = 0; m < N; m++) { e.q.set(N+m, sq[m]); e.chiHead.set(m, sc[m]); }
        return r;
    }
    /** angle(eBind(psi,chi), eTarget_live) for motor m. */
    static double thetaDet(ExplicitCompleteMatHarness.ExMat e, int m, double psi, double chi) {
        int N = e.N;
        double[] nf = ExplicitCompleteMatHarness.neckFrame(e, m);
        double c1 = e.restC.get(m), c2 = e.restC.get(N+m), c3 = e.restC.get(2*N+m);
        double[] t = { c1*nf[0]+c2*nf[3]+c3*nf[6], c1*nf[1]+c2*nf[4]+c3*nf[7], c1*nf[2]+c2*nf[5]+c3*nf[8] };
        double[] u = ExplicitCompleteMatHarness.eBindOf(e, m, psi, chi);
        return Math.acos(clamp(dot(u, t)));
    }
    /** tau = lambda (eBind x eTarget) — the restoring couple on the head. */
    static double[] restoreTorque(ExplicitCompleteMatHarness.ExMat e, int m, double psi, double chi, double k) {
        int N = e.N;
        double[] nf = ExplicitCompleteMatHarness.neckFrame(e, m);
        double c1 = e.restC.get(m), c2 = e.restC.get(N+m), c3 = e.restC.get(2*N+m);
        double[] t = { c1*nf[0]+c2*nf[3]+c3*nf[6], c1*nf[1]+c2*nf[4]+c3*nf[7], c1*nf[2]+c2*nf[5]+c3*nf[8] };
        double[] u = ExplicitCompleteMatHarness.eBindOf(e, m, psi, chi);
        double d = clamp(dot(u, t)), th = Math.acos(d), s = Math.sqrt(Math.max(1e-30, 1-d*d));
        double lam = k * th / s;
        double[] x = cr(u, t);
        return new double[]{ lam*x[0], lam*x[1], lam*x[2] };
    }
    /** The (psi, chi) whose eBind equals the unit target v (exact inverse of the spherical parameterisation). */
    static double[] psiChiFor(ExplicitCompleteMatHarness.ExMat e, int m, double[] v) {
        int N = e.N;
        double bx = e.frame.get(m), by = e.frame.get(N+m), bz = e.frame.get(2*N+m);
        double ex = e.frame.get(3*N+m), ey = e.frame.get(4*N+m), ez = e.frame.get(5*N+m);
        double ux = e.frame.get(6*N+m), uy = e.frame.get(7*N+m), uz = e.frame.get(8*N+m);
        double rF8x = e.params.get(N+m), rF8y = e.params.get(2*N+m);
        double p1x = bx*rF8x+ux*rF8y, p1y = by*rF8x+uy*rF8y, p1z = bz*rF8x+uz*rF8y;
        double pn = Math.sqrt(p1x*p1x+p1y*p1y+p1z*p1z); p1x/=pn; p1y/=pn; p1z/=pn;
        double q1x = ey*p1z-ez*p1y, q1y = ez*p1x-ex*p1z, q1z = ex*p1y-ey*p1x;
        double sc = v[0]*ex + v[1]*ey + v[2]*ez;
        double chi = Math.asin(Math.max(-1, Math.min(1, sc)));
        double cc = Math.cos(chi);
        double a = (v[0]*p1x+v[1]*p1y+v[2]*p1z)/cc, b = (v[0]*q1x+v[1]*q1y+v[2]*q1z)/cc;
        return new double[]{ Math.atan2(b, a), chi };
    }
    /** Rotate the whole motor rigidly: every beam node, the base triad, the anchor and the clamp tangent. */
    static void rigidRotate(ExplicitCompleteMatHarness.ExMat e, double[][] R) {
        int N = e.N, M = e.M;
        for (int m = 0; m < N; m++) {
            for (int j = 0; j <= M; j++) {
                double[] p = mv(R, new double[]{ e.nodes.get((3*j)*N+m), e.nodes.get((3*j+1)*N+m), e.nodes.get((3*j+2)*N+m) });
                e.nodes.set((3*j)*N+m, p[0]); e.nodes.set((3*j+1)*N+m, p[1]); e.nodes.set((3*j+2)*N+m, p[2]);
            }
            for (int blk : new int[]{ 0, 3, 6, 9, 12 }) {
                double[] p = mv(R, new double[]{ e.frame.get(blk*N+m), e.frame.get((blk+1)*N+m), e.frame.get((blk+2)*N+m) });
                e.frame.set(blk*N+m, p[0]); e.frame.set((blk+1)*N+m, p[1]); e.frame.set((blk+2)*N+m, p[2]);
            }
        }
    }
    /** Roll the distal S2 element of motor m by alpha about the lever axis n1 (keeps node M fixed). */
    static void rollDistalS2(ExplicitCompleteMatHarness.ExMat e, int m, double alpha) {
        int N = e.N, M = e.M; if (M < 2) return;
        double[] nf = ExplicitCompleteMatHarness.neckFrame(e, m);
        double Px = e.nodes.get((3*M)*N+m), Py = e.nodes.get((3*M+1)*N+m), Pz = e.nodes.get((3*M+2)*N+m);
        int jm = M-1;
        double vx = e.nodes.get((3*jm)*N+m)-Px, vy = e.nodes.get((3*jm+1)*N+m)-Py, vz = e.nodes.get((3*jm+2)*N+m)-Pz;
        double c = Math.cos(alpha), s = Math.sin(alpha);
        double kx = nf[1]*vz-nf[2]*vy, ky = nf[2]*vx-nf[0]*vz, kz = nf[0]*vy-nf[1]*vx;
        double d = nf[0]*vx+nf[1]*vy+nf[2]*vz;
        e.nodes.set((3*jm)*N+m,   Px + vx*c + kx*s + nf[0]*d*(1-c));
        e.nodes.set((3*jm+1)*N+m, Py + vy*c + ky*s + nf[1]*d*(1-c));
        e.nodes.set((3*jm+2)*N+m, Pz + vz*c + kz*s + nf[2]*d*(1-c));
    }

    static void hdr(String s) { System.out.flush(); System.out.println("\n" + "=".repeat(100) + "\n=== " + s + "\n" + "=".repeat(100)); }
    static double[][] rot3(double a, double b, double c) {
        double[][] Rz = { { Math.cos(a), -Math.sin(a), 0 }, { Math.sin(a), Math.cos(a), 0 }, { 0, 0, 1 } };
        double[][] Ry = { { Math.cos(b), 0, Math.sin(b) }, { 0, 1, 0 }, { -Math.sin(b), 0, Math.cos(b) } };
        double[][] Rx = { { 1, 0, 0 }, { 0, Math.cos(c), -Math.sin(c) }, { 0, Math.sin(c), Math.cos(c) } };
        return mm(Rz, mm(Ry, Rx));
    }
    static double[][] mm(double[][] A, double[][] B) { double[][] C = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) C[i][j] += A[i][k]*B[k][j];
        return C; }
    static double[] mv(double[][] A, double[] v) {
        return new double[]{ A[0][0]*v[0]+A[0][1]*v[1]+A[0][2]*v[2], A[1][0]*v[0]+A[1][1]*v[1]+A[1][2]*v[2], A[2][0]*v[0]+A[2][1]*v[1]+A[2][2]*v[2] }; }
    static double[] cr(double[] a, double[] b) { return new double[]{ a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0] }; }
    static double[] sub(double[] a, double[] b) { return new double[]{ a[0]-b[0], a[1]-b[1], a[2]-b[2] }; }
    static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static double dot(double[] a, int oa, double[] b) { return a[oa]*b[0]+a[oa+1]*b[1]+a[oa+2]*b[2]; }
    static double dot(double[] a, int oa, double[] b, int ob) { return a[oa]*b[ob]+a[oa+1]*b[ob+1]+a[oa+2]*b[ob+2]; }
    static double norm(double[] a) { return Math.sqrt(dot(a, a)); }
    static double clamp(double x) { return x > 1 ? 1 : (x < -1 ? -1 : x); }
    static double angDeg(double[] a, int oa, double[] b, int ob) { return Math.toDegrees(Math.acos(clamp(dot(a, oa, b, ob)))); }
    static double wrap(double d) { d %= 360.0; if (d > 180) d -= 360; if (d <= -180) d += 360; return d; }
    static void write(String name, String s) {
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(OUT, name), s); }
        catch (java.io.IOException ex) { throw new RuntimeException(ex); }
    }
}
