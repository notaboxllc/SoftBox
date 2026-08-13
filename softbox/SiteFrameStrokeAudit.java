package softbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SITE-FRAME POWER-STROKE POLARITY AUDIT — why did the revised motor show ~50/50 pulling vs resisting?
 *
 * <p><b>AUDIT ONLY. Nothing is tuned and no physics is changed by any path in this file.</b> Head geometry,
 * {@code R_F8}/{@code R_CONV}, the site lattice, {@code n_site}, the 25° gate, the g6/g2 retirement, g3, g5,
 * {@code k_det}, {@code k_bind}, {@code xCatch}, the preload threshold, chemistry, the lever joint, S2
 * mechanics, viscosity, the detached rest pose and sterics are all untouched.
 * Report: {@code docs/motor/SITE_FRAME_POWER_STROKE_AUDIT.md}.
 *
 * <pre>
 *   -sign      PHASE 1   the axial sign convention, traced and verified numerically
 *   -fixture   PHASE 3/4/10/11/12  deterministic canonical bound pose -> real stroke, every azimuth
 *   -natural   PHASE 5/6/7/8/9     natural events, stroke-aligned, decomposed
 *   -all       everything
 * </pre>
 *
 * <p><b>Runner: the CPU sequential runner throughout.</b> No GPU work is launched.
 */
public final class SiteFrameStrokeAudit {
    private SiteFrameStrokeAudit() {}

    static String OUT = "RUN_LOGS/motor_audit/site_frame_power_stroke";
    static final double DT = 2.5e-6;
    static int SEED = 20260812;
    static int RELAX = 4000;          // deterministic relaxation steps per equilibrium
    static int AZIM = 7;              // consecutive every4 sites (7 x 54 deg spans the full circle)
    static int NAT_STEPS = 600_000;

    public static void main(String[] args) {
        String mode = args.length > 0 && args[0].startsWith("-") ? args[0] : "-all";
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "-out" -> OUT = args[++i];
            case "-seed" -> SEED = Integer.parseInt(args[++i]);
            case "-relax" -> RELAX = Integer.parseInt(args[++i]);
            case "-azim" -> AZIM = Integer.parseInt(args[++i]);
            case "-nat-steps" -> NAT_STEPS = Integer.parseInt(args[++i]);
            default -> { }
        }
        new java.io.File(OUT).mkdirs();
        System.out.println("\n=== SITE-FRAME POWER-STROKE POLARITY AUDIT (audit only; nothing is tuned) ===");
        System.out.println("  RUNNER: CPU sequential runner. No GPU work is launched.");
        switch (mode) {
            case "-sign"    -> phase1Sign();
            case "-fixture" -> phase3Fixture();
            case "-natural" -> phase5Natural();
            default         -> { phase1Sign(); phase3Fixture(); phase5Natural(); }
        }
        System.out.println("\n  output -> " + OUT);
    }

    // ==============================================================================================
    //  scene — the canonical site-normal scene with motor Brownian OFF (deterministic fixtures)
    // ==============================================================================================
    static final class S {
        TwoBodyConverterMotor.Glide2D G; ExplicitCompleteMatHarness.ExMat e;
        int m, N, M, nSeg; double rise, stepPhase, Ract;
        float[] fc, fu, fy, fz, f1, f2;
        boolean freeze = true;
    }
    static S scene(int brownOn) {
        ExplicitCompleteMatHarness.resetChiral();
        ExplicitCompleteMatHarness.SITE_MODE = 3;              // every4 sparse long-pitch
        ExplicitCompleteMatHarness.SITE_PHASE_GLOBAL = true;
        ExplicitCompleteMatHarness.SITE_AWARE = true;
        ExplicitCompleteMatHarness.SITE_EXCLUSIVE = true;
        ExplicitCompleteMatHarness.Z_SLAB = true;
        ExplicitCompleteMatHarness.RAND_BASE_AZ = false;
        ExplicitCompleteMatHarness.HEAD_TILT_3D = true;
        ExplicitCompleteMatHarness.HEAD_TILT_AXIS_FIX = true;
        ExplicitCompleteMatHarness.KBIND_BOUND_ONLY = true;
        ExplicitCompleteMatHarness.K_DET_PNNM = 5.0;
        ExplicitCompleteMatHarness.SITE_NORMAL_BIND = true;
        ExplicitCompleteMatHarness.SITE_NORMAL_TOL_DEG = 25.0;
        S s = new S();
        s.G = TwoBodyConverterMotor.buildS2Mat(4.0, DT, 40.0, TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, SEED, false);
        s.e = ExplicitCompleteMatHarness.packExMat(s.G, brownOn);
        s.N = s.e.N; s.M = s.e.M; s.nSeg = s.e.nSeg;
        TwoBodyBeamAnalyticGpu.matBeamGeom(s.e.nodes, s.e.frame, s.e.params, s.e.q, s.e.exCounts, s.e.outGeom, s.e.convF);
        int mSel = -1; double best = 1e9;
        for (int m = 0; m < s.N; m++) {
            double d = SingleMotorMovieHarness.axisDist(s.G, s.nSeg,
                    s.e.outGeom.get(6*s.N+m), s.e.outGeom.get(7*s.N+m), s.e.outGeom.get(8*s.N+m));
            if (d < best) { best = d; mSel = m; }
        }
        s.m = mSel;
        for (int m = 0; m < s.N; m++) { boolean nb = m != mSel; s.G.noBind[m] = nb; s.e.noBind.set(m, nb ? 1 : 0); }
        var f = s.G.fil;
        s.fc = SingleMotorMovieHarness.snap(f.coord); s.fu = SingleMotorMovieHarness.snap(f.uVec);
        s.fy = SingleMotorMovieHarness.snap(f.yVec);  s.fz = SingleMotorMovieHarness.snap(f.zVec);
        s.f1 = SingleMotorMovieHarness.snap(f.end1);  s.f2 = SingleMotorMovieHarness.snap(f.end2);
        s.rise = ExplicitCompleteMatHarness.siteRise(3);
        double twist = ExplicitCompleteMatHarness.TWIST_PER_MON_DEG * Math.PI / 180.0 / Constants.actinMonoRadius;
        s.stepPhase = twist * s.rise;
        s.Ract = ExplicitCompleteMatHarness.R_ACTIN_NM * 1e-3;
        SingleMotorMovieHarness.SEGCUM = new float[s.nSeg];
        for (int q = 0; q < s.nSeg; q++) SingleMotorMovieHarness.SEGCUM[q] = s.e.segCumArc.get(q);
        return s;
    }
    /** One production step; the filament is held fixed when {@code freeze} (an infinitely stiff axial restraint). */
    static void step(S s, int t) {
        ExplicitCompleteMatHarness.stepGlidingCPU(s.e, t, SEED);
        if (!s.freeze) return;
        var f = s.G.fil;
        SingleMotorMovieHarness.restore(f.coord, s.fc); SingleMotorMovieHarness.restore(f.uVec, s.fu);
        SingleMotorMovieHarness.restore(f.yVec, s.fy);  SingleMotorMovieHarness.restore(f.zVec, s.fz);
        SingleMotorMovieHarness.restore(f.end1, s.f1);  SingleMotorMovieHarness.restore(f.end2, s.f2);
    }

    // ==============================================================================================
    //  PHASE 1 — the axial sign convention, traced through the executed code and verified numerically
    // ==============================================================================================
    static void phase1Sign() {
        hdr("PHASE 1 — AXIAL SIGN CONVENTION (traced, then verified numerically; nothing assumed from names)");
        S s = scene(0);
        var f = s.G.fil; int nSeg = s.nSeg;
        double[] u  = { f.uVec.get(0), f.uVec.get(nSeg), f.uVec.get(2*nSeg) };
        double[] bh = s.G.bhat, ph = s.G.phat;
        double[] e1 = { f.end1.get(0), f.end1.get(nSeg), f.end1.get(2*nSeg) };
        double[] e2 = { f.end2.get(0), f.end2.get(nSeg), f.end2.get(2*nSeg) };
        double dEnds = dot(sub(e2, e1), u);
        double dBU = dot(bh, u), dPU = dot(ph, u);

        System.out.printf(Locale.US, "%n  MEASURED on the built scene (segment 0):%n");
        System.out.printf(Locale.US, "    u_fil                = (%+.6f %+.6f %+.6f)%n", u[0], u[1], u[2]);
        System.out.printf(Locale.US, "    b_hat (barbed LABEL) = (%+.6f %+.6f %+.6f)   dot(b_hat, u_fil) = %+.6f%n", bh[0], bh[1], bh[2], dBU);
        System.out.printf(Locale.US, "    p_hat (pointed LABEL)= (%+.6f %+.6f %+.6f)   dot(p_hat, u_fil) = %+.6f%n", ph[0], ph[1], ph[2], dPU);
        System.out.printf(Locale.US, "    dot(end2 - end1, u_fil) = %+.6f um  ⇒ end2 lies at %s u_fil%n",
                dEnds, dEnds > 0 ? "+" : "-");
        // material arc direction: does bindArc increase toward +u ?
        double arcLo = 0.10 * f.segLength.get(0), arcHi = 0.90 * f.segLength.get(0);
        double[] pLo = SingleMotorMovieHarness.sitePosNormal(s.G, nSeg, 0, arcLo, 0.0, s.Ract);
        double[] pHi = SingleMotorMovieHarness.sitePosNormal(s.G, nSeg, 0, arcHi, 0.0, s.Ract);
        double dArc = dot(sub(new double[]{pHi[0],pHi[1],pHi[2]}, new double[]{pLo[0],pLo[1],pLo[2]}), u);
        System.out.printf(Locale.US, "    d(site position)/d(bindArc) . u_fil = %+.6f um  ⇒ bindArc increases toward %s u_fil%n",
                dArc, dArc > 0 ? "+" : "-");

        boolean barbedPlus = dBU > 0 && dEnds > 0;
        System.out.printf(Locale.US, "%n  ================== THE CONVENTION ==================%n");
        System.out.printf(Locale.US, "    +u_fil = %s%n", barbedPlus ? "BARBED   (= end2)" : "POINTED  (= end1)");
        System.out.printf(Locale.US, "    -u_fil = %s%n", barbedPlus ? "POINTED  (= end1)" : "BARBED   (= end2)");
        System.out.println("  ====================================================");
        System.out.println("\n  SOURCE (TwoBodyConverterMotor:676-680, the Exp-3B audited convention, verbatim):");
        System.out.println("    \"barbed = end2 = +uVec; pointed = end1 = -uVec; material coord (bindArc) increases");
        System.out.println("     pointed->barbed; the working stroke sweeps the F8 point toward the POINTED direction");
        System.out.println("     (-b_hat) => force on actin toward pointed, free filament glides pointed-first,");
        System.out.println("     motor reaction toward barbed.\"");

        System.out.printf(Locale.US, "%n  ================== WHAT THAT IMPLIES, SIGN BY SIGN ==================%n");
        System.out.println("    MOTOR force (on the head, bondData[m*13 + 0..2]) during a productive stroke:");
        System.out.printf(Locale.US, "        toward BARBED  ⇒  dot(F_motor, u_fil) > 0%n");
        System.out.println("    FILAMENT reaction (on actin, bondData[m*13 + 6..8] — the segGather channel):");
        System.out.printf(Locale.US, "        toward POINTED ⇒  dot(F_filament, u_fil) < 0   <-- THE POLARITY OBSERVABLE (`fax`)%n");
        System.out.println("    FREE-FILAMENT glide, measured by ChiralSiteHarness as centroidDot(f, b_hat):");
        System.out.printf(Locale.US, "        pointed-first  ⇒  glide < 0%n");
        System.out.println("\n    So: EXPECTED PRODUCTIVE SIGNS are  fax < 0  and  glide < 0.");

        System.out.printf(Locale.US, "%n  WARNING — `nPull`/`nDrag` are NOT a polarity observable.%n");
        System.out.println("    ChiralSiteHarness:2290  `double power = fax * vFil;`  classifies each bound sample by");
        System.out.println("    MECHANICAL POWER against the INSTANTANEOUS FILAMENT VELOCITY, not against the pointed");
        System.out.println("    direction. When vFil is dominated by Brownian motion its sign is ~random, so nPull/nDrag");
        System.out.println("    tends to 50/50 BY CONSTRUCTION whatever the sign of fax. The polarity observable is the");
        System.out.println("    signed mean `r.fax` (ChiralSiteHarness:2388), which the previous smoke test did not print.");

        write("phase1_sign_convention.txt", String.format(Locale.US,
                "u_fil\t%.9f\t%.9f\t%.9f%nbhat_dot_u\t%.9f%nphat_dot_u\t%.9f%nend2_minus_end1_dot_u\t%.9f%n"
                + "arc_increases_dot_u\t%.9f%nplus_u_is\t%s%nminus_u_is\t%s%n"
                + "expected_fax_sign\tNEGATIVE (force on filament toward pointed)%n"
                + "expected_glide_sign\tNEGATIVE (pointed-first)%n"
                + "expected_F_motor_axial_sign\tPOSITIVE (reaction toward barbed)%n",
                u[0], u[1], u[2], dBU, dPU, dEnds, dArc,
                barbedPlus ? "BARBED" : "POINTED", barbedPlus ? "POINTED" : "BARBED"));
    }

    // ==============================================================================================
    //  PHASE 3/4/10/11/12 — deterministic canonical bound pose, real stroke, every helical azimuth
    // ==============================================================================================
    static void phase3Fixture() {
        hdr("PHASE 3/4/10 — DETERMINISTIC CANONICAL STROKE FIXTURE across the helical circle");
        System.out.printf(Locale.US, "  Motor Brownian OFF, %d relaxation steps per equilibrium, real ADP.Pi->ADP transition%n", RELAX);
        System.out.println("  (the nucleotide state is set and matCock + the implicit solver do the rest — F8 is NEVER");
        System.out.println("   translated by hand). Two restraints per azimuth:");
        System.out.println("     ISOMETRIC  filament FROZEN (infinitely stiff axial restraint) -> measures FORCE polarity");
        System.out.println("     FREE       filament mobile, all Brownian off                  -> measures DISPLACEMENT polarity");

        StringBuilder tsv = new StringBuilder("mode\tsite\tazim_deg\tsF8_pre_nm\tsF8_post_nm\tdsF8_nm\t"
                + "fax_pre_pN\tfax_post_pN\tdfax_pN\tphi_pre\tphi_post\ttheta_pre\ttheta_post\ts2_pre_nm\ts2_post_nm\t"
                + "thetabind_pre\tthetabind_post\tdFil_axial_nm\twork_aJ\n");
        for (int mode = 0; mode < 2; mode++) {
            boolean freeze = mode == 0;
            System.out.printf(Locale.US, "%n  ---- %s restraint ----%n", freeze ? "ISOMETRIC (filament frozen)" : "FREE (filament mobile)");
            System.out.printf(Locale.US, "    %5s %9s %11s %11s %11s %11s %11s %11s %10s%n",
                    "site", "azim", "sF8_pre", "sF8_post", "d sF8", "fax_pre", "fax_post", "d fax", "dFil_ax");
            System.out.printf(Locale.US, "    %5s %9s %11s %11s %11s %11s %11s %11s %10s%n",
                    "", "deg", "nm", "nm", "nm", "pN", "pN", "pN", "nm");
            List<double[]> rows = new ArrayList<>();
            for (int j = 0; j < AZIM; j++) {
                S s = scene(0); s.freeze = freeze;
                // PHASE 4 covariance done CORRECTLY: rotate the filament's MATERIAL FRAME about its own axis,
                // which rotates every site normal while leaving the site's AXIAL coordinate untouched. Walking
                // along the every4 lattice instead would advance the site 10.8 nm axially per step and measure
                // an increasingly stretched bond rather than the same configuration at a new azimuth.
                double phiRot = 2*Math.PI*j/AZIM;
                rotateFilamentFrame(s, phiRot);
                int seg = latchNearestCanonical(s);
                if (seg < 0) continue;
                double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, s.nSeg, s.m, s.Ract, 1.0, 0.0);
                if (sn == null) continue;
                double[] pc = SiteNormalBindHarness.poseForTarget(s.e, s.m, new double[]{ -sn[0], -sn[1], -sn[2] });
                if (pc == null) continue;
                s.e.q.set(s.N + s.m, pc[0]); s.e.chiHead.set(s.m, pc[1]);
                // PRE-STROKE: the real ADP.Pi state; matCock sets thetaS = PRESTROKE_THETAS inside the step
                s.G.mot.nucleotideState.set(s.m, MotorStore.NUC_ADPPI);
                s.e.noBind.set(s.m, 1);                     // hold the latched bond; no re-capture during relaxation
                double[] filPre = filCentroid(s);
                for (int t = 0; t < RELAX; t++) { s.G.mot.nucleotideState.set(s.m, MotorStore.NUC_ADPPI); step(s, t); }
                double[] pre = observe(s, sn);
                // POWER STROKE: the production state change, nothing else
                for (int t = 0; t < RELAX; t++) { s.G.mot.nucleotideState.set(s.m, MotorStore.NUC_ADP); step(s, RELAX + t); }
                double[] post = observe(s, sn);
                double[] filPost = filCentroid(s);
                double dFil = dot(sub(filPost, filPre), uOf(s)) * 1e3;
                double dsF8 = post[0] - pre[0];
                double work = -0.5 * (pre[1] + post[1]) * 1e-12 * dFil * 1e-9 * 1e18;   // aJ, F_fil . displacement
                // PHYSICAL azimuth of the (rotated) site normal about the filament axis — NOT the stored
                // bindAzim, which is the lattice phase and does not change when the material frame rotates.
                double az = physAzim(s, sn);
                System.out.printf(Locale.US, "    %5d %9.2f %11.4f %11.4f %11.4f %11.5f %11.5f %11.5f %10.4f%n",
                        s.e.bindSite.get(s.m), az, pre[0], post[0], dsF8, pre[1], post[1], post[1]-pre[1], dFil);
                tsv.append(String.format(Locale.US, "%s\t%d\t%.3f\t%.5f\t%.5f\t%.5f\t%.6f\t%.6f\t%.6f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.5f\t%.5f%n",
                        freeze ? "isometric" : "free", s.e.bindSite.get(s.m), az, pre[0], post[0], dsF8,
                        pre[1], post[1], post[1]-pre[1], pre[2], post[2], pre[3], post[3], pre[4], post[4],
                        pre[5], post[5], dFil, work));
                rows.add(new double[]{ dsF8, pre[1], post[1], post[1]-pre[1], dFil });
            }
            summarise(freeze ? "ISOMETRIC" : "FREE", rows);
        }
        write("phase3_fixture.tsv", tsv.toString());
    }
    static void summarise(String tag, List<double[]> rows) {
        if (rows.isEmpty()) { System.out.println("    (no reachable azimuth)"); return; }
        int n = rows.size();
        double[] ds = new double[n], fp = new double[n], df = new double[n], dfil = new double[n];
        for (int i = 0; i < n; i++) { ds[i] = rows.get(i)[0]; fp[i] = rows.get(i)[2]; df[i] = rows.get(i)[3]; dfil[i] = rows.get(i)[4]; }
        System.out.printf(Locale.US, "%n    PHASE 4 — AZIMUTHAL COVARIANCE (%s), n = %d azimuths:%n", tag, n);
        rep("d sF8 (axial F8 displacement)", ds, "nm");
        rep("fax_post (axial force ON FILAMENT after the stroke)", fp, "pN");
        rep("d fax (force change through the stroke)", df, "pN");
        rep("d filament axial displacement", dfil, "nm");
        int sameDs = signAgree(ds), sameF = signAgree(fp);
        System.out.printf(Locale.US, "    SIGN CONSISTENCY: d sF8 %d/%d share a sign ; fax_post %d/%d share a sign%n",
                sameDs, n, sameF, n);
        System.out.printf(Locale.US, "    ⇒ axial stroke polarity is %s across the helical circle%n",
                sameDs == n ? "INVARIANT" : "NOT invariant");
        System.out.printf(Locale.US, "    ⇒ force on the filament after the stroke is %s (expected NEGATIVE = toward pointed)%n",
                sameF == n ? (fp[0] < 0 ? "consistently NEGATIVE — CORRECT polarity" : "consistently POSITIVE — REVERSED polarity")
                           : "MIXED in sign");
    }
    static void rep(String name, double[] v, String unit) {
        double mn = 1e30, mx = -1e30, s = 0;
        for (double x : v) { mn = Math.min(mn, x); mx = Math.max(mx, x); s += x; }
        System.out.printf(Locale.US, "      %-52s mean %+10.5f  range [%+.5f, %+.5f] %s%n", name, s/v.length, mn, mx, unit);
    }
    static int signAgree(double[] v) {
        int pos = 0, neg = 0;
        for (double x : v) { if (x > 0) pos++; else if (x < 0) neg++; }
        return Math.max(pos, neg);
    }
    /** {sF8_nm, fax_pN, phi_deg, theta_deg, s2ext_nm, thetaBind_deg} at the current state. */
    static double[] observe(S s, double[] sn) {
        int N = s.N, m = s.m;
        double[] u = uOf(s);
        double[] xf = { s.e.outGeom.get(6*N+m), s.e.outGeom.get(7*N+m), s.e.outGeom.get(8*N+m) };
        double sF8 = dot(sub(xf, new double[]{ sn[3], sn[4], sn[5] }), u) * 1e3;
        int d = m*13; int bs = s.G.mot.boundSeg.get(m);
        double fax = 0;
        if (bs >= 0) fax = (s.G.bondData.get(d+6)*s.G.fil.uVec.get(bs)
                          + s.G.bondData.get(d+7)*s.G.fil.uVec.get(s.nSeg+bs)
                          + s.G.bondData.get(d+8)*s.G.fil.uVec.get(2*s.nSeg+bs)) * 1e12;
        double phi = Math.toDegrees(s.e.q.get(m)), psi = Math.toDegrees(s.e.q.get(N+m));
        double s2 = 0;
        for (int j = 0; j < s.M; j++) {
            double dx = s.e.nodes.get((3*(j+1))*N+m) - s.e.nodes.get((3*j)*N+m);
            double dy = s.e.nodes.get((3*(j+1)+1)*N+m) - s.e.nodes.get((3*j+1)*N+m);
            double dz = s.e.nodes.get((3*(j+1)+2)*N+m) - s.e.nodes.get((3*j+2)*N+m);
            s2 += Math.sqrt(dx*dx+dy*dy+dz*dz);
        }
        double[] xh = { s.e.outGeom.get(9*N+m), s.e.outGeom.get(10*N+m), s.e.outGeom.get(11*N+m) };
        double[] snNow = SiteNormalBindSystem.boundSiteNormal(s.G, s.nSeg, m, s.Ract, 1.0, 0.0);
        double tb = snNow != null ? SiteNormalBindSystem.thetaBindDeg(xh, snNow) : Double.NaN;
        return new double[]{ sF8, fax, phi, psi - phi, s2*1e3, tb };
    }
    /** Signed azimuth of n_site about u_fil, referenced to the lab z-axis projected perpendicular to u. */
    static double physAzim(S s, double[] sn) {
        double[] u = uOf(s);
        double[] z = { 0, 0, 1 };
        double du = dot(z, u);
        double[] pr = { z[0]-du*u[0], z[1]-du*u[1], z[2]-du*u[2] };
        double pl = Math.sqrt(dot(pr, pr)); if (pl < 1e-12) return Double.NaN;
        pr[0] /= pl; pr[1] /= pl; pr[2] /= pl;
        double[] q = { u[1]*pr[2]-u[2]*pr[1], u[2]*pr[0]-u[0]*pr[2], u[0]*pr[1]-u[1]*pr[0] };
        return Math.toDegrees(Math.atan2(sn[0]*q[0]+sn[1]*q[1]+sn[2]*q[2], sn[0]*pr[0]+sn[1]*pr[1]+sn[2]*pr[2]));
    }
    static double[] uOf(S s) {
        int bs = Math.max(0, s.G.mot.boundSeg.get(s.m));
        return new double[]{ s.G.fil.uVec.get(bs), s.G.fil.uVec.get(s.nSeg+bs), s.G.fil.uVec.get(2*s.nSeg+bs) };
    }
    static double[] filCentroid(S s) {
        double x = 0, y = 0, z = 0;
        for (int q = 0; q < s.nSeg; q++) { x += s.G.fil.coord.get(q); y += s.G.fil.coord.get(s.nSeg+q); z += s.G.fil.coord.get(2*s.nSeg+q); }
        return new double[]{ x/s.nSeg, y/s.nSeg, z/s.nSeg };
    }
    /**
     * Rotate the filament's rolling material frame (yVec, zVec) about its own axis by {@code phi}. Every site
     * normal rotates with it — {@code n_site = cos(bindAzim) segY + sin(bindAzim) segZ} — while coord, uVec,
     * end1 and end2, hence every site's AXIAL coordinate, are untouched. This is the covariance operation
     * Phase 4 asks for.
     */
    static void rotateFilamentFrame(S s, double phi) {
        var f = s.G.fil; int nSeg = s.nSeg;
        for (int q = 0; q < nSeg; q++) {
            double ux = f.uVec.get(q), uy = f.uVec.get(nSeg+q), uz = f.uVec.get(2*nSeg+q);
            double[] y = { f.yVec.get(q), f.yVec.get(nSeg+q), f.yVec.get(2*nSeg+q) };
            double c = Math.cos(phi), sn2 = Math.sin(phi);
            double kx = uy*y[2]-uz*y[1], ky = uz*y[0]-ux*y[2], kz = ux*y[1]-uy*y[0];
            double d = ux*y[0]+uy*y[1]+uz*y[2];
            double ry = 0;
            double nyx = y[0]*c + kx*sn2 + ux*d*(1-c);
            double nyy = y[1]*c + ky*sn2 + uy*d*(1-c);
            double nyz = y[2]*c + kz*sn2 + uz*d*(1-c);
            f.yVec.set(q, (float) nyx); f.yVec.set(nSeg+q, (float) nyy); f.yVec.set(2*nSeg+q, (float) nyz);
            f.zVec.set(q, (float)(uy*nyz - uz*nyy)); f.zVec.set(nSeg+q, (float)(uz*nyx - ux*nyz));
            f.zVec.set(2*nSeg+q, (float)(ux*nyy - uy*nyx));
        }
        s.fy = SingleMotorMovieHarness.snap(f.yVec); s.fz = SingleMotorMovieHarness.snap(f.zVec);
    }
    /**
     * Latch to the site NEAREST the head's F8 point in the CANONICAL pose — iterated, because setting the
     * canonical orientation moves xF8, which can change which site is nearest. Two passes converge. This
     * keeps the pre-stroke bond at a realistic (few-nm) extension instead of the tens of nm a fixed
     * lattice-walk produces.
     */
    static int latchNearestCanonical(S s) {
        int seg = -1;
        for (int pass = 0; pass < 3; pass++) {
            seg = latch(s, 0);
            if (seg < 0) return -1;
            double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, s.nSeg, s.m, s.Ract, 1.0, 0.0);
            if (sn == null) return -1;
            double[] pc = SiteNormalBindHarness.poseForTarget(s.e, s.m, new double[]{ -sn[0], -sn[1], -sn[2] });
            if (pc == null) return -1;
            s.e.q.set(s.N + s.m, pc[0]); s.e.chiHead.set(s.m, pc[1]);
        }
        return seg;
    }
    /** Latch the motor to the (nearest + j)-th every4 site in the canonical bound configuration. */
    static int latch(S s, int j) {
        TwoBodyBeamAnalyticGpu.matBeamGeomTilt(s.e.nodes, s.e.frame, s.e.params, s.e.q, s.e.exCounts, s.e.outGeom, s.e.convF, s.e.chiHead);
        SiteNormalBindSystem.headAxisStep(s.e.frame, s.e.params, s.e.q, s.e.chiHead, s.e.convF, s.e.outGeom, s.e.exCounts);
        double[] xf = { s.e.outGeom.get(6*s.N+s.m), s.e.outGeom.get(7*s.N+s.m), s.e.outGeom.get(8*s.N+s.m) };
        double[] ns = SingleMotorMovieHarness.nearestSite(s.G, s.nSeg, xf, s.rise, s.stepPhase);
        int seg = (int) ns[0]; if (seg < 0) return -1;
        int k = (int) ns[1] + j;
        double cum = s.e.segCumArc.get(seg), L = s.G.fil.segLength.get(seg);
        double la = k * s.rise - cum;
        if (la < 0 || la > L) return -1;
        double tw = k * s.stepPhase, ph = tw - 2*Math.PI*Math.floor(tw/(2*Math.PI));
        s.G.mot.boundSeg.set(s.m, seg);
        s.G.mot.bindArc.set(s.m, (float) la);
        s.G.mot.bindAzim.set(s.m, (float) ph);
        s.e.bindSite.set(s.m, k);
        s.e.params.set(7*s.N + s.m, s.e.gateP.get(0));
        return seg;
    }

    // ==============================================================================================
    //  PHASE 5-9 — natural bound events, stroke-aligned and decomposed
    // ==============================================================================================
    static void phase5Natural() {
        hdr("PHASE 5/6/7/8/9 — NATURAL BOUND EVENTS: stroke alignment and the pull/resist decomposition");
        S s = scene(1); s.freeze = true;
        int N = s.N, nSeg = s.nSeg;
        for (int m = 0; m < N; m++) { s.G.noBind[m] = false; s.e.noBind.set(m, 0); }   // all motors bindable
        double[] u = { s.G.fil.uVec.get(0), s.G.fil.uVec.get(nSeg), s.G.fil.uVec.get(2*nSeg) };
        StringBuilder log = new StringBuilder("event\tmotor\tstep\trel\tnuc\tphase\tazim_deg\tsF8_nm\tfax_pN\t"
                + "theta_bind\tphi\tpsi\tchi\tanchor_site_nm\n");
        StringBuilder ev = new StringBuilder("event\tmotor\tcapStep\tsite\tazim_deg\tanchor_site_nm\t"
                + "fax_at_capture_pN\tsF8_at_capture_nm\tstroked\tdsF8_nm\tdfax_pN\tfax_mean_pre_pN\tfax_mean_post_pN\t"
                + "lifetime\tclass\n");
        final class Ev { int m, cap, site; double azim, anchor, fax0, s0; boolean stroked;
                         double sPre, sPost, fPre, fPost; int nPre, nPost, strokeAt = -1; int life = -1; }
        List<Ev> live = new ArrayList<>(), done = new ArrayList<>();
        boolean[] was = new boolean[N]; int[] prevNuc = new int[N];
        for (int m = 0; m < N; m++) { was[m] = s.G.mot.boundSeg.get(m) >= 0; prevNuc[m] = s.G.mot.nucleotideState.get(m); }
        for (int t = 0; t < NAT_STEPS; t++) {
            step(s, t);
            for (int m = 0; m < N; m++) {
                boolean now = s.G.mot.boundSeg.get(m) >= 0;
                int nu = s.G.mot.nucleotideState.get(m);
                if (!was[m] && now) {
                    Ev e2 = new Ev(); e2.m = m; e2.cap = t; e2.site = s.e.bindSite.get(m);
                    double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, nSeg, m, s.Ract, 1.0, 0.0);
                    e2.azim = Math.toDegrees(s.G.mot.bindAzim.get(m));
                    e2.anchor = sn != null ? anchorSite(s, m, sn, u) : Double.NaN;
                    e2.fax0 = faxOf(s, m); e2.s0 = sn != null ? sF8Of(s, m, sn, u) : Double.NaN;
                    live.add(e2);
                }
                if (now) for (Ev e2 : live) if (e2.m == m) {
                    double fx = faxOf(s, m);
                    if (prevNuc[m] == MotorStore.NUC_ADPPI && nu == MotorStore.NUC_ADP && !e2.stroked) {
                        e2.stroked = true; e2.strokeAt = t;
                        double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, nSeg, m, s.Ract, 1.0, 0.0);
                        e2.sPre = sn != null ? sF8Of(s, m, sn, u) : Double.NaN;
                    }
                    if (e2.stroked) { e2.fPost += fx; e2.nPost++; } else { e2.fPre += fx; e2.nPre++; }
                    if (e2.stroked && t == e2.strokeAt + 40) {
                        double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, nSeg, m, s.Ract, 1.0, 0.0);
                        e2.sPost = sn != null ? sF8Of(s, m, sn, u) : Double.NaN;
                    }
                    if (done.size() + live.size() <= 30 && (t - e2.cap) % 5 == 0) {
                        double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, nSeg, m, s.Ract, 1.0, 0.0);
                        double[] xh = { s.e.outGeom.get(9*N+m), s.e.outGeom.get(10*N+m), s.e.outGeom.get(11*N+m) };
                        log.append(String.format(Locale.US, "%d\t%d\t%d\t%d\t%s\t%s\t%.2f\t%.4f\t%.6f\t%.3f\t%.3f\t%.3f\t%.3f\t%.4f%n",
                                done.size()+live.indexOf(e2), m, t, t-e2.cap, SingleMotorMovieHarness.nucName(nu),
                                e2.stroked ? "post" : "pre", e2.azim, sn != null ? sF8Of(s,m,sn,u) : Double.NaN, fx,
                                sn != null ? SiteNormalBindSystem.thetaBindDeg(xh, sn) : Double.NaN,
                                Math.toDegrees(s.e.q.get(m)), Math.toDegrees(s.e.q.get(N+m)),
                                Math.toDegrees(s.e.chiHead.get(m)), e2.anchor));
                    }
                }
                if (was[m] && !now) for (java.util.Iterator<Ev> it = live.iterator(); it.hasNext(); ) {
                    Ev e2 = it.next(); if (e2.m != m) continue;
                    e2.life = t - e2.cap; done.add(e2); it.remove();
                }
                was[m] = now; prevNuc[m] = nu;
            }
        }
        for (Ev e2 : live) { e2.life = -1; done.add(e2); }
        // ---- report -----------------------------------------------------------------------------
        int nProd = 0, nResist = 0, nRev = 0, nLoad = 0, nNo = 0;
        int bornPull = 0, bornResist = 0;
        double sAnchorPull = 0, sAnchorResist = 0;
        System.out.printf(Locale.US, "%n  %d natural bound episodes over %d steps x %d motors%n", done.size(), NAT_STEPS, N);
        for (Ev e2 : done) {
            double fPre = e2.nPre > 0 ? e2.fPre/e2.nPre : Double.NaN;
            double fPost = e2.nPost > 0 ? e2.fPost/e2.nPost : Double.NaN;
            double dsF8 = e2.stroked ? (e2.sPost - e2.sPre) : Double.NaN;
            String cls;
            if (!e2.stroked) { cls = "NO-STROKE"; nNo++; }
            else if (fPost < 0 && dsF8 < 0) { cls = "PRODUCTIVE"; nProd++; }
            else if (fPost > 0 && dsF8 < 0) { cls = "LOAD-REVERSED"; nLoad++; }
            else if (dsF8 > 0) { cls = "POLARITY-REVERSED"; nRev++; }
            else { cls = "RESISTING"; nResist++; }
            if (e2.fax0 < 0) { bornPull++; sAnchorPull += e2.anchor; } else { bornResist++; sAnchorResist += e2.anchor; }
            ev.append(String.format(Locale.US, "%d\t%d\t%d\t%d\t%.2f\t%.4f\t%.6f\t%.4f\t%b\t%.4f\t%.6f\t%.6f\t%.6f\t%d\t%s%n",
                    done.indexOf(e2), e2.m, e2.cap, e2.site, e2.azim, e2.anchor, e2.fax0, e2.s0, e2.stroked,
                    dsF8, fPost - fPre, fPre, fPost, e2.life, cls));
        }
        System.out.printf(Locale.US, "%n  PHASE 6 — stroke-aligned classification:%n");
        System.out.printf(Locale.US, "    PRODUCTIVE %d | RESISTING %d | POLARITY-REVERSED %d | LOAD-REVERSED %d | NO-STROKE %d%n",
                nProd, nResist, nRev, nLoad, nNo);
        System.out.printf(Locale.US, "%n  PHASE 9 — CAPTURE PRELOAD SIGN (fax at the capture step):%n");
        System.out.printf(Locale.US, "    born PULLING (fax < 0) %d | born RESISTING (fax > 0) %d  ⇒ %.1f %% born pulling%n",
                bornPull, bornResist, 100.0*bornPull/Math.max(1, bornPull+bornResist));
        System.out.printf(Locale.US, "%n  PHASE 8 — ANCHOR-SITE AXIAL GEOMETRY dot(x_anchor - x_site, u_fil):%n");
        System.out.printf(Locale.US, "    mean for born-PULLING  %+.4f nm (n=%d)%n", bornPull > 0 ? sAnchorPull/bornPull : Double.NaN, bornPull);
        System.out.printf(Locale.US, "    mean for born-RESISTING %+.4f nm (n=%d)%n", bornResist > 0 ? sAnchorResist/bornResist : Double.NaN, bornResist);
        write("phase5_events.tsv", ev.toString());
        write("phase5_boundlog.tsv", log.toString());
    }
    static double faxOf(S s, int m) {
        int bs = s.G.mot.boundSeg.get(m); if (bs < 0) return Double.NaN;
        int d = m*13;
        return (s.G.bondData.get(d+6)*s.G.fil.uVec.get(bs)
              + s.G.bondData.get(d+7)*s.G.fil.uVec.get(s.nSeg+bs)
              + s.G.bondData.get(d+8)*s.G.fil.uVec.get(2*s.nSeg+bs)) * 1e12;
    }
    static double sF8Of(S s, int m, double[] sn, double[] u) {
        int N = s.N;
        double[] xf = { s.e.outGeom.get(6*N+m), s.e.outGeom.get(7*N+m), s.e.outGeom.get(8*N+m) };
        return dot(sub(xf, new double[]{ sn[3], sn[4], sn[5] }), u) * 1e3;
    }
    /** dot(x_anchor - x_site, u_fil) in nm; the anchor is the S2 clamp (beam node 0 = g4E). */
    static double anchorSite(S s, int m, double[] sn, double[] u) {
        int N = s.N;
        double[] a = { s.e.nodes.get(m), s.e.nodes.get(N+m), s.e.nodes.get(2*N+m) };
        return dot(sub(a, new double[]{ sn[3], sn[4], sn[5] }), u) * 1e3;
    }

    // ==============================================================================================
    static double[] sub(double[] a, double[] b) { return new double[]{ a[0]-b[0], a[1]-b[1], a[2]-b[2] }; }
    static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static void hdr(String t) { System.out.println("\n" + "=".repeat(100) + "\n=== " + t + "\n" + "=".repeat(100)); }
    static void write(String rel, String s) {
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(OUT, rel), s); }
        catch (java.io.IOException e) { throw new RuntimeException(e); } }
}
