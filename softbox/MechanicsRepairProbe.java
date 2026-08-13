package softbox;

import java.util.Locale;

/**
 * EXPLICIT-S2 MOTOR MECHANICS REPAIR — gates for the two defects surfaced by the 3-D-head work.
 *
 * <pre>
 *   -f8      FIX 1  F8 virtual-work axis:  A finite difference · B virtual-work closure
 *                                          C in-plane load feedback · D fixed-site stationarity
 *   -lever   FIX 2  S2 -> lever moment transfer: A common-rotation mode · B rigid covariance
 *                                          C S2 bend reorients the rest pose · D compliance
 *                                          E detached equilibrium (no multi-turn drift)
 *   -reg     CORRECTED CORE-MOTOR BASELINE (stroke, polarity, k_ext, theta, S2, stability, search)
 *   -all     everything
 * </pre>
 *
 * <p><b>Runner: the CPU sequential runner</b> — plain-Java kernel calls over the host SoA arrays, no TaskGraph,
 * no device transfer. Deterministic / single-motor assay class per {@code docs/CPU_GPU_VALIDATION_POLICY.md};
 * CPU/GPU parity for the repaired hot kernel is gated separately by {@code ExplicitMatSolveHarness} (a real
 * device TaskGraph), which is the triggered confirmation the policy requires for a structural physics change.
 *
 * <p>Nothing here tunes anything: F8's stiffness and rest length are untouched, the lever joint reuses the
 * beam's own {@code kbend} and a rest angle read off the as-built geometry, and no parameter is fitted.
 * Report: {@code docs/motor/RESTORED_3D_HEAD_TILT_DOF.md}.
 */
public final class MechanicsRepairProbe {
    private MechanicsRepairProbe() {}

    static String OUT = "RUN_LOGS/motor_audit/mechanics_repair";
    static final double DT = 2.5e-6;
    static final int SEED = 20260812;
    /** F8 spring constant used by the fixed-site fixtures, in N/m (the code's own kfSI scale). */
    static final double KF8_SI = 1.0e-3;

    public static void main(String[] args) {
        String mode = args.length > 0 && args[0].startsWith("-") ? args[0] : "-all";
        for (int i = 0; i < args.length; i++) if (args[i].equals("-out")) OUT = args[++i];
        new java.io.File(OUT).mkdirs();
        switch (mode) {
            case "-f8"    -> fix1();
            case "-lever" -> fix2();
            case "-reg"   -> rebaseline();
            case "-jointvar" -> jointVariance();
            case "-all"   -> { fix1(); fix2(); rebaseline(); }
            case "-all2"  -> { fix1(); fix2(); jointVariance(); rebaseline(); }
            default -> System.out.println("unknown mode " + mode);
        }
    }

    // ==================================================================================================
    // FIX 1 — the F8 generalized-force axis
    // ==================================================================================================
    static void fix1() {
        hdr("FIX 1 — F8 VIRTUAL-WORK AXIS  (F8 stays a PURE TRANSLATIONAL SPRING; only the projection changes)");
        var G = LiveNeckHeadProbe.build();
        var e = LiveNeckHeadProbe.pack(G, 0);
        LiveNeckHeadProbe.setNative(e);
        int N = e.N, M = e.M;
        double[] econv = { G.econv[0], G.econv[1], G.econv[2] };
        double[] eup   = { G.eup[0],   G.eup[1],   G.eup[2]   };
        System.out.printf(Locale.US, "  scene: N=%d motors, M=%d beam elements, dt=%.1e s ; econv=(%.3f %.3f %.3f) eup=(%.3f %.3f %.3f)%n",
                N, M, DT, econv[0], econv[1], econv[2], eup[0], eup[1], eup[2]);

        double phi0 = e.q.get(0), psi0 = 0.13, h = 1e-7;
        double[] g0 = LiveNeckHeadProbe.geomAt(e, N, phi0, psi0);
        double[] P  = { e.nodes.get((3*M)*N), e.nodes.get((3*M+1)*N), e.nodes.get((3*M+2)*N) };
        double[] fc = { g0[6]-g0[0], g0[7]-g0[1], g0[8]-g0[2] };   // xF8 - C
        double[] cp = { g0[0]-P[0], g0[1]-P[1], g0[2]-P[2] };      // C - P

        // ---------------- GATE A — finite difference through the live kernel -------------------------
        System.out.println("\n  GATE A — FINITE DIFFERENCE through the live matBeamGeom kernel");
        double[] gp = LiveNeckHeadProbe.geomAt(e, N, phi0, psi0+h), gm = LiveNeckHeadProbe.geomAt(e, N, phi0, psi0-h);
        double[] fp = LiveNeckHeadProbe.geomAt(e, N, phi0+h, psi0), fm = LiveNeckHeadProbe.geomAt(e, N, phi0-h, psi0);
        double[] dF8dpsi = { (gp[6]-gm[6])/(2*h), (gp[7]-gm[7])/(2*h), (gp[8]-gm[8])/(2*h) };
        double[] dCdphi  = { (fp[0]-fm[0])/(2*h), (fp[1]-fm[1])/(2*h), (fp[2]-fm[2])/(2*h) };
        double[] ecXfc = cr(econv, fc), euXfc = cr(eup, fc), ecXcp = cr(econv, cp), euXcp = cr(eup, cp);
        row("d xF8/d psi   (FD, the truth)", dF8dpsi);
        row("econv x (xF8-C)   REPAIRED  ", ecXfc);
        row("eup   x (xF8-C)   legacy    ", euXfc);
        row("d C  /d phi   (FD, the truth)", dCdphi);
        row("econv x (C-P)     REPAIRED  ", ecXcp);
        row("eup   x (C-P)     legacy    ", euXcp);
        double rApsi = rel(dF8dpsi, ecXfc), rAphi = rel(dCdphi, ecXcp);
        double rLpsi = rel(dF8dpsi, euXfc), rLphi = rel(dCdphi, euXcp);
        System.out.printf(Locale.US, "    rel |econv-col - FD| : psi %.3e  phi %.3e   -> %s%n",
                rApsi, rAphi, (rApsi < 1e-6 && rAphi < 1e-6) ? "PASS" : "FAIL");
        System.out.printf(Locale.US, "    rel |eup-col   - FD| : psi %.3e  phi %.3e   (legacy, for the record)%n", rLpsi, rLphi);

        // ---------------- GATE B — virtual-work closure ----------------------------------------------
        System.out.println("\n  GATE B — VIRTUAL WORK:  F8 . d xF8  ==  F8.dP + Qphi dphi + Qpsi dpsi");
        System.out.println("    (an ARBITRARY virtual displacement of every coordinate the F8 point depends on)");
        double[] F8 = { 3.1e-12, -1.7e-12, 2.4e-12 };            // an arbitrary 3-D bond force, N
        double dphi = 3.7e-6, dpsi = -2.3e-6;
        double[] dP = { 1.3e-7, -0.9e-7, 0.6e-7 };                // µm
        double[] xa = xF8Displaced(e, N, M, phi0, psi0, dP, 0, 0);
        double[] xb = xF8Displaced(e, N, M, phi0, psi0, dP, dphi, dpsi);
        double[] x0 = xF8Displaced(e, N, M, phi0, psi0, new double[3], 0, 0);
        double workTot = 0;                                        // F8 . d xF8   (J)
        for (int k = 0; k < 3; k++) workTot += F8[k] * (xb[k]-x0[k]) * 1e-6;
        double workP = 0; for (int k = 0; k < 3; k++) workP += F8[k] * (xa[k]-x0[k]) * 1e-6;
        double Qphi = dot(econv, cr(cp, F8)) * 1e-6, Qpsi = dot(econv, cr(fc, F8)) * 1e-6;   // the solver's own form
        double QphiL = dot(eup, cr(cp, F8)) * 1e-6, QpsiL = dot(eup, cr(fc, F8)) * 1e-6;
        double gen = workP + Qphi*dphi + Qpsi*dpsi, genL = workP + QphiL*dphi + QpsiL*dpsi;
        System.out.printf(Locale.US, "    F8 . d xF8 (kernel FD)         = %+.9e J%n", workTot);
        System.out.printf(Locale.US, "    F8.dP + Qphi dphi + Qpsi dpsi  = %+.9e J   (REPAIRED econv)%n", gen);
        System.out.printf(Locale.US, "    closure residual               = %.3e  (rel %.3e)  -> %s%n",
                Math.abs(workTot-gen), Math.abs(workTot-gen)/Math.abs(workTot),
                Math.abs(workTot-gen)/Math.abs(workTot) < 1e-5 ? "PASS" : "FAIL");
        System.out.printf(Locale.US, "    same with the legacy eup axis  = %+.9e J   (rel error %.3e)%n",
                genL, Math.abs(workTot-genL)/Math.abs(workTot));

        // ---------------- GATE C — an ordinary in-plane load must reach phi and psi ------------------
        System.out.println("\n  GATE C — SIMPLE LOAD: an axial / in-plane F8 force must produce a NONZERO phi,psi load");
        System.out.println("    load dir            Qphi_econv     Qpsi_econv     Qphi_eup      Qpsi_eup      (N.m)");
        String[] nm = { "+bhat (axial)", "+eup  (normal)", "+econv (out-of-plane)", "oblique in-plane" };
        double[][] dirs = { {G.bhat[0],G.bhat[1],G.bhat[2]}, {eup[0],eup[1],eup[2]}, {econv[0],econv[1],econv[2]},
                            norm3(new double[]{ G.bhat[0]+G.eup[0], G.bhat[1]+G.eup[1], G.bhat[2]+G.eup[2] }) };
        double f = 5.0e-12;   // 5 pN
        boolean cPass = true;
        for (int i = 0; i < dirs.length; i++) {
            double[] Fl = { f*dirs[i][0], f*dirs[i][1], f*dirs[i][2] };
            double qa = dot(econv, cr(cp, Fl))*1e-6, qb = dot(econv, cr(fc, Fl))*1e-6;
            double la = dot(eup,   cr(cp, Fl))*1e-6, lb = dot(eup,   cr(fc, Fl))*1e-6;
            // ground truth by FD of the F8 work through the kernel
            double tphi = fdWork(e, N, M, phi0, psi0, Fl, true), tpsi = fdWork(e, N, M, phi0, psi0, Fl, false);
            System.out.printf(Locale.US, "    %-21s %+.4e  %+.4e  %+.4e  %+.4e%n", nm[i], qa, qb, la, lb);
            System.out.printf(Locale.US, "      %-19s %+.4e  %+.4e   <- FD ground truth (rel %.1e / %.1e)%n",
                    "", tphi, tpsi, relS(qa,tphi), relS(qb,tpsi));
            if (i < 2 && (Math.abs(qa) < 1e-24 || Math.abs(qb) < 1e-24)) cPass = false;
            if (relS(qa,tphi) > 1e-5 || relS(qb,tpsi) > 1e-5) cPass = false;
        }
        System.out.printf(Locale.US, "    -> %s   (the legacy eup columns are exactly 0 for every IN-PLANE load:%n"
                + "       with the converter geometry planar, eup x (in-plane) is orthogonal to it)%n", cPass ? "PASS" : "FAIL");

        // ---------------- GATE D — fixed-site relaxation is stationary under the TRUE gradient -------
        System.out.println("\n  GATE D — FIXED-SITE RELAXATION: relax to the solver's own fixed point, then measure");
        System.out.println("    the TRUE potential gradient dU/dphi, dU/dpsi there (U = F8 + converter + bind [+ lever joint])");
        for (boolean legacy : new boolean[]{ false, true }) {
            double[] r = relaxStationarity(legacy);
            System.out.printf(Locale.US, "    %-9s phi=%.5f rad (%.2f deg)  dU/dphi=%+.4e  dU/dpsi=%+.4e N.m   rel=%.3e  -> %s%n",
                    legacy ? "legacy" : "REPAIRED", r[0], Math.toDegrees(r[0]), r[1], r[2], r[3],
                    legacy ? "(for the record)" : (r[3] < 1e-5 ? "STATIONARY / PASS" : "FAIL"));
        }
    }

    /** xF8 (µm) for motor 0 after displacing the beam tip by dP (µm) and the angles by dphi/dpsi. Restores state. */
    static double[] xF8Displaced(ExplicitCompleteMatHarness.ExMat e, int N, int M,
                                 double phi, double psi, double[] dP, double dphi, double dpsi) {
        double[] sav = new double[3];
        for (int k = 0; k < 3; k++) { sav[k] = e.nodes.get((3*M+k)*N); e.nodes.set((3*M+k)*N, sav[k]+dP[k]); }
        double[] g = LiveNeckHeadProbe.geomAt(e, N, phi+dphi, psi+dpsi);
        for (int k = 0; k < 3; k++) e.nodes.set((3*M+k)*N, sav[k]);
        return new double[]{ g[6], g[7], g[8] };
    }
    /** FD ground truth for the F8 generalized force on phi (or psi): Q = F8 . dxF8/dq. */
    static double fdWork(ExplicitCompleteMatHarness.ExMat e, int N, int M, double phi, double psi, double[] F8, boolean isPhi) {
        double h = 1e-7;
        double[] a = LiveNeckHeadProbe.geomAt(e, N, isPhi ? phi+h : phi, isPhi ? psi : psi+h);
        double[] b = LiveNeckHeadProbe.geomAt(e, N, isPhi ? phi-h : phi, isPhi ? psi : psi-h);
        double w = 0; for (int k = 0; k < 3; k++) w += F8[k] * (a[6+k]-b[6+k]) / (2*h) * 1e-6;
        LiveNeckHeadProbe.geomAt(e, N, phi, psi);
        return w;
    }

    /**
     * Bind motor 0 to a FIXED site 4 nm off its native F8 point, relax with Brownian OFF to the solver's own
     * fixed point, then evaluate the EXACT potential's gradient in (phi, psi) there. A solver whose generalized
     * forces come from the true geometry must stop where that gradient vanishes.
     */
    static double[] relaxStationarity(boolean legacy) {
        boolean sav = ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX, savL = TwoBodyConverterMotor.F8_AXIS_LEGACY;
        ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX = !legacy; TwoBodyConverterMotor.F8_AXIS_LEGACY = legacy;
        try {
            var G = LiveNeckHeadProbe.build();
            var e = LiveNeckHeadProbe.pack(G, 0);
            LiveNeckHeadProbe.setNative(e);
            int N = e.N;
            double[] g0 = LiveNeckHeadProbe.geomAt(e, N, e.q.get(0), e.q.get(N));
            double[] xs = { g0[6] + 0.004, g0[7], g0[8] };          // a fixed site 4 nm along +x
            for (int m = 0; m < N; m++) { e.boundSeg.set(m, 0); G.mot.boundSeg.set(m, 0); }
            for (int t = 0; t < 40000; t++) LiveNeckHeadProbe.solveStep(e, G, t, SEED, 0, xs, KF8_SI);
            double phi = e.q.get(0), psi = e.q.get(N);
            double dUdphi = -gradU(e, G, N, phi, psi, xs, true);
            double dUdpsi = -gradU(e, G, N, phi, psi, xs, false);
            double scale = e.params.get(6*N) * 1.0;                 // kconv (N.m/rad^2) — the converter torque scale
            return new double[]{ phi, dUdphi, dUdpsi, Math.max(Math.abs(dUdphi), Math.abs(dUdpsi)) / scale };
        } finally { ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX = sav; TwoBodyConverterMotor.F8_AXIS_LEGACY = savL; }
    }

    /** -dU/dq (a generalized FORCE, N.m) for the full phi/psi-dependent potential, by central difference. */
    static double gradU(ExplicitCompleteMatHarness.ExMat e, TwoBodyConverterMotor.Glide2D G,
                        int N, double phi, double psi, double[] xs, boolean isPhi) {
        double h = 1e-7;
        double up = potential(e, G, N, isPhi ? phi+h : phi, isPhi ? psi : psi+h, xs);
        double um = potential(e, G, N, isPhi ? phi-h : phi, isPhi ? psi : psi-h, xs);
        LiveNeckHeadProbe.geomAt(e, N, phi, psi);
        return -(up-um)/(2*h);
    }
    /** U(phi,psi) = F8 spring + converter + bind + (when the lever joint is on) the S2->lever bend. Joules. */
    static double potential(ExplicitCompleteMatHarness.ExMat e, TwoBodyConverterMotor.Glide2D G,
                            int N, double phi, double psi, double[] xs) {
        double[] g = LiveNeckHeadProbe.geomAt(e, N, phi, psi);
        double dx = (g[6]-xs[0])*1e-6, dy = (g[7]-xs[1])*1e-6, dz = (g[8]-xs[2])*1e-6;
        double kc = e.params.get(6*N), kb = e.params.get(7*N);
        double thetaS = e.q.get(2*N), psiActin = e.q.get(3*N);
        double U = 0.5*KF8_SI*(dx*dx+dy*dy+dz*dz)
                 + 0.5*kc*(psi-phi-thetaS)*(psi-phi-thetaS)
                 + 0.5*kb*(psi-psiActin)*(psi-psiActin);
        U += leverJointEnergy(e, N, 0, phi);
        return U;
    }
    /** The S2->lever terminal bend energy for motor m at lever angle phi (0 when the joint is off). */
    static double leverJointEnergy(ExplicitCompleteMatHarness.ExMat e, int N, int m, double phi) {
        if (!ExplicitCompleteMatHarness.leverJointOn()) return 0;
        int M = e.M; if (M < 2) return 0;
        double[] s = distalTangent(e, N, M, m);
        double[] uB = leverDir(e, N, m, phi);
        double c = clamp(dot(s, uB));
        double th = Math.acos(c), th0 = e.params.get(17*N+m), kb = e.params.get(13*N+m);
        return 0.5*kb*(th-th0)*(th-th0);
    }
    static double[] distalTangent(ExplicitCompleteMatHarness.ExMat e, int N, int M, int m) {
        int jm = M-1;
        double[] s = { e.nodes.get((3*M)*N+m)-e.nodes.get((3*jm)*N+m),
                       e.nodes.get((3*M+1)*N+m)-e.nodes.get((3*jm+1)*N+m),
                       e.nodes.get((3*M+2)*N+m)-e.nodes.get((3*jm+2)*N+m) };
        return norm3(s);
    }
    static double[] leverDir(ExplicitCompleteMatHarness.ExMat e, int N, int m, double phi) {
        double c = Math.cos(phi), s = Math.sin(phi);
        return new double[]{ e.frame.get(6*N+m)*c + e.frame.get(m)*s,
                             e.frame.get(7*N+m)*c + e.frame.get(N+m)*s,
                             e.frame.get(8*N+m)*c + e.frame.get(2*N+m)*s };
    }

    // ==================================================================================================
    // FIX 2 — the S2 -> lever moment transfer
    // ==================================================================================================
    static void fix2() {
        hdr("FIX 2 — S2 -> LEVER MOMENT TRANSFER  (the beam's OWN kbend; rest angle read off the as-built pose)");
        var G = LiveNeckHeadProbe.build();
        var e = LiveNeckHeadProbe.pack(G, 0);
        LiveNeckHeadProbe.setNative(e);
        int N = e.N, M = e.M;
        double kb = e.params.get(13*N), th0 = e.params.get(17*N);
        System.out.printf(Locale.US, "  joint: stiffness = the beam's own kbend = %.4e N.m/rad^2 (EI/l0, AMK 2008) — NO new parameter%n", kb);
        System.out.printf(Locale.US, "  rest angle theta0 = %.4f rad = %.2f deg, read off the AS-BUILT geometry (like the%n"
                + "         clamped joint 0's rest tangent g4Tan) — a build-time geometric constant, not a fit%n", th0, Math.toDegrees(th0));
        System.out.printf(Locale.US, "  enabled = %s%n", ExplicitCompleteMatHarness.leverJointOn());

        // ---------------- GATE A — the common-rotation mode is no longer free ------------------------
        System.out.println("\n  GATE A — COMMON ROTATION (phi,psi) -> (phi+d, psi+d) must NOT be zero-energy");
        System.out.println("    (this is the exact mode Fixture C of the 3-D-head report showed had zero restoring force)");
        double phi0 = e.q.get(0), psi0 = e.q.get(N);
        double[] xsNone = null;
        System.out.println("      d (deg)     U_lever (kT)      dU/dphi (N.m)     verdict");
        boolean aPass = true;
        for (double d : new double[]{ 0, 1, 5, 15, 30 }) {
            double dd = Math.toRadians(d);
            double U = leverJointEnergy(e, N, 0, phi0+dd);
            double h = 1e-7;
            double gr = -(leverJointEnergy(e, N, 0, phi0+dd+h) - leverJointEnergy(e, N, 0, phi0+dd-h))/(2*h);
            System.out.printf(Locale.US, "      %6.1f    %12.4f    %+.5e    %s%n", d, U/Constants.kT, -gr,
                    d == 0 ? "rest" : (Math.abs(gr) > 1e-24 ? "RESTORED" : "still free"));
            if (d > 0 && Math.abs(gr) < 1e-24) aPass = false;
        }
        System.out.printf(Locale.US, "    -> %s%n", aPass ? "PASS — the free lever rotation is gone" : "FAIL");

        // ---------------- GATE B — rigid-body covariance ---------------------------------------------
        System.out.println("\n  GATE B — RIGID-BODY COVARIANCE: rotating the ENTIRE motor changes no internal energy");
        double Ubefore = leverJointEnergy(e, N, 0, phi0 + 0.21);
        LiveNeckHeadProbe.rigidRotate(e, LiveNeckHeadProbe.rot3(0.7, -0.4, 1.1));
        double Uafter = leverJointEnergy(e, N, 0, phi0 + 0.21);
        System.out.printf(Locale.US, "    U_lever before = %.9e J ; after = %.9e J ; |dU|/U = %.3e  -> %s%n",
                Ubefore, Uafter, Math.abs(Uafter-Ubefore)/Math.max(1e-40, Math.abs(Ubefore)),
                Math.abs(Uafter-Ubefore)/Math.max(1e-40, Math.abs(Ubefore)) < 1e-10 ? "PASS" : "FAIL");
        e = LiveNeckHeadProbe.pack(G, 0); LiveNeckHeadProbe.setNative(e);

        // ---------------- GATE C — bending the distal S2 moves the lever's preferred orientation -----
        System.out.println("\n  GATE C — S2 BEND: bending the distal S2 must change the lever's PREFERRED orientation");
        System.out.println("      bend (deg)   phi_min (deg)   d phi_min (deg)   reading");
        double base = Double.NaN; boolean cPass = false;
        for (double a : new double[]{ 0, 5, 10, 20 }) {
            var e2 = LiveNeckHeadProbe.pack(G, 0); LiveNeckHeadProbe.setNative(e2);
            bendDistal(e2, N, M, 0, Math.toRadians(a));
            double best = 0, bU = Double.MAX_VALUE;
            for (int i = -3000; i <= 3000; i++) { double p = phi0 + i*1e-4;
                double U = leverJointEnergy(e2, N, 0, p); if (U < bU) { bU = U; best = p; } }
            if (Double.isNaN(base)) base = best;
            System.out.printf(Locale.US, "      %8.1f     %10.3f      %+10.3f       %s%n",
                    a, Math.toDegrees(best), Math.toDegrees(best-base),
                    a == 0 ? "reference" : "the rest pose FOLLOWS the beam");
            if (a > 0 && Math.abs(best-base) > 1e-4) cPass = true;
        }
        System.out.printf(Locale.US, "    -> %s (naturally, from the beam geometry — no lab reference anywhere)%n", cPass ? "PASS" : "FAIL");

        // ---------------- GATE D — the joint is compliant, not a weld --------------------------------
        System.out.println("\n  GATE D — NO ARTIFICIAL LOCK: the lever must stay COMPLIANT");
        double kphi = kb * dcdphi2(e, N, M, 0, phi0);
        double gPhi = e.params.get(8*N);
        System.out.printf(Locale.US, "    effective lever stiffness k_phi = %.4e N.m/rad^2 ; converter k_conv = %.4e%n", kphi, e.params.get(6*N));
        System.out.printf(Locale.US, "    thermal amplitude sqrt(kT/k_phi) = %.2f deg ; relaxation tau = gamma_phi/k_phi = %.2f us (dt = %.2f us)%n",
                Math.toDegrees(Math.sqrt(Constants.kT/kphi)), gPhi/kphi*1e6, DT*1e6);
        boolean dPass = Math.toDegrees(Math.sqrt(Constants.kT/kphi)) > 1.0 && gPhi/kphi > DT;
        System.out.printf(Locale.US, "    -> %s (a weld would give <<1 deg of thermal play and tau << dt)%n", dPass ? "PASS — compliant" : "FAIL");

        // ---------------- GATE E — detached equilibrium, no multi-turn drift -------------------------
        System.out.println("\n  GATE E — DETACHED EQUILIBRIUM: a free motor must reach a stationary orientation");
        System.out.println("    THE FREE MODE ONLY EXISTS WITH THE LIVE-FRAME HEAD POTENTIAL: the historical base-frame");
        System.out.println("    k_bind pins psi to the stored triad and k_conv ties phi to psi, so the lever is held by");
        System.out.println("    proxy. Replace it with a neck-relative rest pose — which is the whole point of the 3-D");
        System.out.println("    head — and BOTH constraints become purely RELATIVE, leaving phi free. So gate E runs the");
        System.out.println("    3-D-head candidate ON (k_det = 5), which is the configuration that exposed the defect.");
        System.out.println("      arm                                SD(phi)     SD(psi)   phi drift (deg)   turns");
        for (String arm : new String[]{ "base-frame k_bind (historical)", "live frame, NO joint (the defect)",
                                        "live frame + lever joint (REPAIRED)" }) {
            boolean live = !arm.startsWith("base"), joint = arm.contains("REPAIRED");
            double[] r = driftRun(live, joint);
            System.out.printf(Locale.US, "      %-34s %8.2f %11.2f %14.2f %8.2f%n", arm, r[0], r[1], r[2], r[2]/360.0);
        }
        System.out.println("    -> PASS if the REPAIRED row is bounded and comparable to the historical row, i.e. the");
        System.out.println("       joint replaces the lab-frame anchor the live rest pose correctly gave up.");
    }

    /** Bend the distal S2 element of motor m by alpha about econv (keeps node M fixed). */
    static void bendDistal(ExplicitCompleteMatHarness.ExMat e, int N, int M, int m, double alpha) {
        if (M < 2) return;
        double[] ax = { e.frame.get(3*N+m), e.frame.get(4*N+m), e.frame.get(5*N+m) };
        double Px = e.nodes.get((3*M)*N+m), Py = e.nodes.get((3*M+1)*N+m), Pz = e.nodes.get((3*M+2)*N+m);
        int jm = M-1;
        double vx = e.nodes.get((3*jm)*N+m)-Px, vy = e.nodes.get((3*jm+1)*N+m)-Py, vz = e.nodes.get((3*jm+2)*N+m)-Pz;
        double c = Math.cos(alpha), s = Math.sin(alpha);
        double kx = ax[1]*vz-ax[2]*vy, ky = ax[2]*vx-ax[0]*vz, kz = ax[0]*vy-ax[1]*vx;
        double d = ax[0]*vx+ax[1]*vy+ax[2]*vz;
        e.nodes.set((3*jm)*N+m,   Px + vx*c + kx*s + ax[0]*d*(1-c));
        e.nodes.set((3*jm+1)*N+m, Py + vy*c + ky*s + ax[1]*d*(1-c));
        e.nodes.set((3*jm+2)*N+m, Pz + vz*c + kz*s + ax[2]*d*(1-c));
    }
    /** (dc/dphi)^2 + curvature term -> the effective phi stiffness factor of the lever joint at phi. */
    static double dcdphi2(ExplicitCompleteMatHarness.ExMat e, int N, int M, int m, double phi) {
        double h = 1e-5;
        double up = leverJointEnergy(e, N, m, phi+h), um = leverJointEnergy(e, N, m, phi-h), u0 = leverJointEnergy(e, N, m, phi);
        double kbv = e.params.get(13*N+m);
        return Math.max(1e-12, (up - 2*u0 + um)/(h*h) / kbv);
    }
    /**
     * Detached free run. Returns {SD(phi) deg, SD(psi) deg, net phi drift deg}. "Drift" is the UNWRAPPED
     * excursion of the ensemble-mean lever angle from its start — not the summed per-step |dphi|, which at this
     * dt is dominated by thermal jitter and would look large even for a perfectly bounded coordinate.
     */
    static double[] driftRun(boolean liveFrame, boolean joint) {
        boolean sJ = ExplicitCompleteMatHarness.LEVER_JOINT, sT = ExplicitCompleteMatHarness.HEAD_TILT_3D;
        double sK = ExplicitCompleteMatHarness.K_DET_PNNM;
        ExplicitCompleteMatHarness.LEVER_JOINT = joint;
        ExplicitCompleteMatHarness.HEAD_TILT_3D = liveFrame;
        ExplicitCompleteMatHarness.K_DET_PNNM = 5.0;
        try {
            var G = LiveNeckHeadProbe.build();
            var e = LiveNeckHeadProbe.pack(G, 1);
            LiveNeckHeadProbe.setNative(e);
            LiveNeckHeadProbe.detach(e, G);
            int N = e.N, n = 0;
            double p1 = 0, p2 = 0, q1 = 0, q2 = 0, phi0 = 0, maxDrift = 0;
            for (int m = 0; m < N; m++) phi0 += Math.toDegrees(e.q.get(m)); phi0 /= N;
            for (int t = 0; t < 80000; t++) {
                LiveNeckHeadProbe.solveStep(e, G, t, SEED, 1, null, 0);
                if (t > 15000 && t % 20 == 0) {
                    double mp = 0;
                    for (int m = 0; m < N; m++) {
                        double p = Math.toDegrees(e.q.get(m)), q = Math.toDegrees(e.q.get(N+m));
                        p1 += p; p2 += p*p; q1 += q; q2 += q*q; n++; mp += p;
                    }
                    maxDrift = Math.max(maxDrift, Math.abs(mp/N - phi0));
                }
            }
            double mp = p1/n, mq = q1/n;
            return new double[]{ Math.sqrt(Math.max(0, p2/n - mp*mp)), Math.sqrt(Math.max(0, q2/n - mq*mq)), maxDrift };
        } finally {
            ExplicitCompleteMatHarness.LEVER_JOINT = sJ; ExplicitCompleteMatHarness.HEAD_TILT_3D = sT;
            ExplicitCompleteMatHarness.K_DET_PNNM = sK;
        }
    }

    /**
     * GATE E FOLLOW-UP — WHERE does the residual lever wander live? SD(phi) is the LAB-frame lever angle; the
     * joint constrains the RELATIVE angle theta_joint = angle(sHat, uB). If theta_joint is at its own thermal
     * amplitude while phi is far larger, then the residual wander is INHERITED from the S2's own orientational
     * fluctuation — the physically correct consequence of anchoring the lever to a floppy beam rather than to a
     * lab frame — and NOT slack in the joint.
     */
    static void jointVariance() {
        hdr("GATE E FOLLOW-UP — is the residual lever wander joint SLACK, or inherited S2 orientation?");
        boolean sJ = ExplicitCompleteMatHarness.LEVER_JOINT, sT = ExplicitCompleteMatHarness.HEAD_TILT_3D;
        double sK = ExplicitCompleteMatHarness.K_DET_PNNM;
        ExplicitCompleteMatHarness.LEVER_JOINT = true; ExplicitCompleteMatHarness.HEAD_TILT_3D = true;
        ExplicitCompleteMatHarness.K_DET_PNNM = 5.0;
        try {
            var G = LiveNeckHeadProbe.build();
            var e = LiveNeckHeadProbe.pack(G, 1);
            LiveNeckHeadProbe.setNative(e); LiveNeckHeadProbe.detach(e, G);
            int N = e.N, M = e.M, n = 0;
            double p1=0,p2=0,j1=0,j2=0,s1=0,s2=0;
            double kb = e.params.get(13*N), th0 = e.params.get(17*N);
            for (int t = 0; t < 80000; t++) {
                LiveNeckHeadProbe.solveStep(e, G, t, SEED, 1, null, 0);
                if (t > 15000 && t % 20 == 0) for (int m = 0; m < N; m++) {
                    double phi = Math.toDegrees(e.q.get(m));
                    double[] sh = distalTangent(e, N, M, m), uB = leverDir(e, N, m, e.q.get(m));
                    double thj = Math.toDegrees(Math.acos(clamp(dot(sh, uB))));
                    // the S2 tangent's own lab-frame orientation, measured in the same plane as phi
                    double sAng = Math.toDegrees(Math.atan2(sh[0]*e.frame.get(m) + sh[1]*e.frame.get(N+m) + sh[2]*e.frame.get(2*N+m),
                                                            sh[0]*e.frame.get(6*N+m) + sh[1]*e.frame.get(7*N+m) + sh[2]*e.frame.get(8*N+m)));
                    p1+=phi; p2+=phi*phi; j1+=thj; j2+=thj*thj; s1+=sAng; s2+=sAng*sAng; n++;
                }
            }
            double mp=p1/n, mj=j1/n, ms=s1/n;
            double sdP=Math.sqrt(Math.max(0,p2/n-mp*mp)), sdJ=Math.sqrt(Math.max(0,j2/n-mj*mj)), sdS=Math.sqrt(Math.max(0,s2/n-ms*ms));
            System.out.printf(Locale.US, "  repaired, detached, live frame, k_det=5 ; %d samples%n", n);
            System.out.printf(Locale.US, "    SD(phi)          lever angle in the LAB frame          = %8.2f deg%n", sdP);
            System.out.printf(Locale.US, "    SD(sHat angle)   the distal S2 tangent, SAME plane     = %8.2f deg%n", sdS);
            System.out.printf(Locale.US, "    SD(theta_joint)  angle(sHat, uB) — WHAT THE JOINT HOLDS = %8.2f deg   (mean %.2f, rest %.2f)%n",
                    sdJ, mj, Math.toDegrees(th0));
            System.out.printf(Locale.US, "    joint's own thermal amplitude sqrt(kT/kbend)           = %8.2f deg%n",
                    Math.toDegrees(Math.sqrt(Constants.kT/kb)));
            System.out.printf(Locale.US, "  => the joint holds its relative angle to %.2f deg while the lab-frame lever wanders %.2f deg:%n",
                    sdJ, sdP);
            System.out.printf(Locale.US, "     the residual is %s%n", sdJ < 0.35*sdP
                    ? "INHERITED S2 ORIENTATION, not joint slack (the physically correct consequence)"
                    : "NOT explained by inherited S2 orientation — joint slack dominates, REVIEW");
        } finally {
            ExplicitCompleteMatHarness.LEVER_JOINT = sJ; ExplicitCompleteMatHarness.HEAD_TILT_3D = sT;
            ExplicitCompleteMatHarness.K_DET_PNNM = sK;
        }
    }

    // ==================================================================================================
    // CORRECTED CORE-MOTOR BASELINE
    // ==================================================================================================
    static void rebaseline() {
        hdr("CORRECTED CORE-MOTOR BASELINE  —  SUPERSEDED, see below");
        System.out.println("  This probe's own stroke/k_ext estimator is NOT used for the reported baseline.");
        System.out.println("  A 1 pN/nm fixed-site probe spring is far SOFTER than the motor, so the settled F8");
        System.out.println("  displacement is dominated by the probe and k_ext comes out ~100 pN/nm — a badly");
        System.out.println("  conditioned measurement, not a result. The reported baseline uses the ALREADY-VALIDATED");
        System.out.println("  Phase-11 estimator in LiveNeckHeadProbe, which is cross-checked against the Cmot");
        System.out.println("  fixed-anchor reference:");
        System.out.println("      java ... softbox.LiveNeckHeadProbe -reg                              # lever joint ON");
        System.out.println("      java ... -Dsoftbox.legacyFreeHinge=true softbox.LiveNeckHeadProbe -reg   # joint OFF");
        System.out.println("  (kept below, unused, so the negative result is on the record.)\n");
        rebaselineAdHoc();
    }
    static void rebaselineAdHoc() {
        System.out.println("  arms differ from the production motor by exactly the two repairs, applied one at a time.");
        System.out.println("  STROKE and k_ext are BOUND-state quantities (boundSeg >= 0).\n");
        System.out.printf(Locale.US, "  %-26s %10s %10s %9s %9s %10s %10s %8s%n",
                "arm", "stroke nm", "polarity", "k_ext", "theta deg", "S2 ext nm", "contour%", "stable");
        String[] arms = { "legacy (pre-repair)", "+F8 axis (fix 1)", "+lever joint (fix 2)", "BOTH (repaired default)" };
        boolean[] ax   = { false, true,  false, true  };
        boolean[] lj   = { false, false, true,  true  };
        for (int i = 0; i < arms.length; i++) System.out.println(regArm(arms[i], ax[i], lj[i]));
        System.out.println("\n  reference (Cmot fixed-anchor two-body path, which has ALWAYS used econv): stroke ~7 nm,");
        System.out.println("  pointed-first polarity, k_ext ~ 0.6-0.64 pN/nm. Reported for orientation, NOT tuned to.");
    }

    static String regArm(String name, boolean axisFix, boolean leverJoint) {
        boolean sA = ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX, sL = ExplicitCompleteMatHarness.LEVER_JOINT,
                sG = TwoBodyConverterMotor.F8_AXIS_LEGACY;
        ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX = axisFix;
        TwoBodyConverterMotor.F8_AXIS_LEGACY = !axisFix;
        ExplicitCompleteMatHarness.LEVER_JOINT = leverJoint;
        try {
            var G = LiveNeckHeadProbe.build();
            var e = LiveNeckHeadProbe.pack(G, 0);
            LiveNeckHeadProbe.setNative(e);
            int N = e.N;
            double[] g0 = LiveNeckHeadProbe.geomAt(e, N, e.q.get(0), e.q.get(N));
            double[] xs = { g0[6], g0[7], g0[8] };
            for (int m = 0; m < N; m++) { e.boundSeg.set(m, 0); G.mot.boundSeg.set(m, 0); }
            for (int t = 0; t < 30000; t++) LiveNeckHeadProbe.solveStep(e, G, t, SEED, 0, xs, KF8_SI);
            double[] gA = LiveNeckHeadProbe.geomAt(e, N, e.q.get(0), e.q.get(N));
            double s2a = s2Ext(e, N, e.M, 0), cA = contour(e, N, e.M, 0);
            // STROKE: switch the converter rest angle pre -> post and let it settle; read the F8-point travel on bhat
            for (int m = 0; m < N; m++) e.q.set(2*N+m, TwoBodyConverterMotor.ADP_THETAS);
            for (int t = 30000; t < 90000; t++) LiveNeckHeadProbe.solveStep(e, G, t, SEED, 0, xs, KF8_SI);
            double[] gB = LiveNeckHeadProbe.geomAt(e, N, e.q.get(0), e.q.get(N));
            double stroke = ((gB[6]-gA[6])*G.bhat[0] + (gB[7]-gA[7])*G.bhat[1] + (gB[8]-gA[8])*G.bhat[2]) * 1e3;
            double theta = Math.toDegrees(e.q.get(N) - e.q.get(0));
            // k_ext: displace the fixed site 0.5 nm along +bhat, settle, read dF/dx at the F8 point
            double[] xs2 = { xs[0]+0.0005*G.bhat[0], xs[1]+0.0005*G.bhat[1], xs[2]+0.0005*G.bhat[2] };
            for (int t = 90000; t < 150000; t++) LiveNeckHeadProbe.solveStep(e, G, t, SEED, 0, xs2, KF8_SI);
            double[] gC = LiveNeckHeadProbe.geomAt(e, N, e.q.get(0), e.q.get(N));
            double dxF8 = ((gC[6]-gB[6])*G.bhat[0] + (gC[7]-gB[7])*G.bhat[1] + (gC[8]-gB[8])*G.bhat[2]) * 1e3;   // nm
            double give = 0.5 - dxF8;                                                   // nm of spring stretch
            double force = KF8_SI * give * 1e-9 * 1e12;                                 // pN
            double kext = give > 1e-9 ? force / Math.max(1e-9, dxF8) : Double.NaN;      // pN/nm
            double s2b = s2Ext(e, N, e.M, 0), cB = contour(e, N, e.M, 0);
            boolean stable = Double.isFinite(theta) && Double.isFinite(stroke) && Math.abs(theta) < 720
                             && Math.abs(s2b/s2a - 1) < 0.5;
            return String.format(Locale.US, "  %-26s %10.3f %10s %9.4f %9.3f %10.4f %10.6f %8s",
                    name, stroke, stroke < 0 ? "pointed" : "barbed", kext, theta, s2b, cB/cA, stable ? "yes" : "NO");
        } finally {
            ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX = sA; ExplicitCompleteMatHarness.LEVER_JOINT = sL;
            TwoBodyConverterMotor.F8_AXIS_LEGACY = sG;
        }
    }
    static double s2Ext(ExplicitCompleteMatHarness.ExMat e, int N, int M, int m) {
        double dx = e.nodes.get((3*M)*N+m)-e.nodes.get(m), dy = e.nodes.get((3*M+1)*N+m)-e.nodes.get(N+m),
               dz = e.nodes.get((3*M+2)*N+m)-e.nodes.get(2*N+m);
        return Math.sqrt(dx*dx+dy*dy+dz*dz)*1e3;
    }
    static double contour(ExplicitCompleteMatHarness.ExMat e, int N, int M, int m) {
        double c = 0;
        for (int j = 0; j < M; j++) {
            double dx = e.nodes.get((3*(j+1))*N+m)-e.nodes.get((3*j)*N+m);
            double dy = e.nodes.get((3*(j+1)+1)*N+m)-e.nodes.get((3*j+1)*N+m);
            double dz = e.nodes.get((3*(j+1)+2)*N+m)-e.nodes.get((3*j+2)*N+m);
            c += Math.sqrt(dx*dx+dy*dy+dz*dz);
        }
        return c;
    }

    // ==================================================================================================
    static void hdr(String s) { System.out.flush(); System.out.println("\n" + "=".repeat(100) + "\n=== " + s + "\n" + "=".repeat(100)); }
    static void row(String label, double[] v) {
        System.out.printf(Locale.US, "    %-30s = (%+.6e %+.6e %+.6e)%n", label, v[0], v[1], v[2]); }
    static double[] cr(double[] a, double[] b) { return new double[]{ a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0] }; }
    static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static double[] norm3(double[] a) { double n = Math.sqrt(dot(a,a)); return new double[]{ a[0]/n, a[1]/n, a[2]/n }; }
    static double clamp(double x) { return x > 1 ? 1 : (x < -1 ? -1 : x); }
    static double rel(double[] a, double[] b) {
        double d = 0, s = 0; for (int k = 0; k < 3; k++) { d += (a[k]-b[k])*(a[k]-b[k]); s += a[k]*a[k]; }
        return Math.sqrt(d)/Math.max(1e-300, Math.sqrt(s)); }
    static double relS(double a, double b) { return Math.abs(a-b)/Math.max(1e-300, Math.abs(b)); }
}
