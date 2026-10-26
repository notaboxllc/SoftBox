package softbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * TANAKA ANGLE PROBE — single-motor stroke versus the angle between the S2 and actin (2026-10-19, jba).
 *
 * <p><b>Probe only. Nothing is tuned and no physics changes.</b> Literature target: Tanaka et al. 1998 Biophys J
 * 75:1886 (doi:10.1016/S0006-3495(98)77629-5), single myosins in rod cofilaments held at a set angle to actin:
 * displacement per attachment ~10 nm at 5-10 deg, ~0 at ~90 deg, ~5 nm at 150-170 deg.
 *
 * <p>Scene: SiteFrameStrokeAudit's deterministic single-motor scene (motor Brownian OFF) with the SiteNormal S2
 * defaults (one surface, soft base hinge, swivel, stereospecific dock). The angle is set WITHOUT touching the head's
 * pose relative to actin: the head's stroke frame is rotated about the vertical through the S2 tip, and the frozen
 * filament is rotated by the same angle about the vertical and translated so the F8 point keeps its place relative to
 * the filament. Head-actin geometry is therefore identical at every angle; only the S2 direction relative to actin
 * changes. theta = angle about e_up from the S2 (base -> tip, horizontal) to the actin barbed direction u:
 * theta = 0 is the muscle orientation (the stroke pulls the S2 taut), theta = 180 the reversed one (the stroke
 * pushes on the S2).
 *
 * <p>Measurement: quasi-static force-displacement curves F(d) of the bound motor (axial force ON the filament,
 * + = toward barbed) in the pre-stroke ADP.Pi state and the post-stroke ADP state, filament frozen and stepped
 * along its own axis outward from the latched pose in each direction (fresh scene per direction). A trap of any
 * stiffness k is then solved offline: equilibrium F(d) = k d. Chemistry rates are zeroed and the state is forced
 * every step, so the bond cannot release.
 *
 * <pre>  java ... softbox.TanakaAngleProbe [-angles 0,90,180] [-range nm] [-dstep nm] [-relax steps]
 *                                    [-lift nm] [-steric] [-head-floor] [-out dir]</pre>
 * Runner: CPU sequential. No GPU work.
 */
public final class TanakaAngleProbe {
    private TanakaAngleProbe() {}

    static double RANGE = 20, DSTEP = 1; static int RELAX = 4000; static double LIFT = 0;
    static boolean CAPTURE = false; static int CAPMAX = 400_000;
    static String ARMS = "";    // -arms a,b,...: after ONE capture, restore the captured state and apply each bound-state lock (see lock())
    static String TRAPS = "";
    static boolean TIPDIAG = false;   // -tipdiag: in -walk, also write tip_<tag>.tsv = the motor's points in the actin frame at each stage   // -walk k1,k2,... (pN/nm): natural capture, then quasi-static trap equilibrium pre and post stroke

    public static void main(String[] args) throws Exception {
        String angles = "0,30,60,90,120,150,165,180,270";
        String out = "/home/jba/Code/SoftBox/RUN_LOGS/motor_audit/tanaka_angle";
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "-angles" -> angles = args[++i];
            case "-range" -> RANGE = Double.parseDouble(args[++i]);
            case "-dstep" -> DSTEP = Double.parseDouble(args[++i]);
            case "-relax" -> RELAX = Integer.parseInt(args[++i]);
            case "-lift" -> LIFT = Double.parseDouble(args[++i]);
            case "-steric" -> ExplicitCompleteMatHarness.MOTOR_STERIC = true;
            case "-head-floor" -> ExplicitCompleteMatHarness.HEAD_FLOOR = true;
            case "-out" -> out = args[++i];
            case "-capture" -> CAPTURE = true;            // thermal search + natural capture, then deterministic sweep
            case "-capmax" -> CAPMAX = Integer.parseInt(args[++i]);
            case "-seed" -> SiteFrameStrokeAudit.SEED = Integer.parseInt(args[++i]);
            case "-walk" -> { TRAPS = args[++i]; CAPTURE = true; }
            case "-arms" -> ARMS = args[++i];
            case "-tipdiag" -> TIPDIAG = true;
            case "-stroke-window" -> { String[] w = args[++i].split(",");
                ExplicitCompleteMatHarness.STROKE_WINDOW_PRE_DEG = Double.parseDouble(w[0]);
                ExplicitCompleteMatHarness.STROKE_WINDOW_POST_DEG = Double.parseDouble(w[1]); }
            default -> { }
        }
        Files.createDirectories(Path.of(out));
        ExplicitCompleteMatHarness.S2_ONE_SURFACE = true;
        ExplicitCompleteMatHarness.S2_HINGE_PM = 100;
        ExplicitCompleteMatHarness.S2_LIFT_INIT = false;
        ExplicitCompleteMatHarness.S2_SWIVEL = true;
        ExplicitCompleteMatHarness.SWIVEL_DOCK = true;
        ExplicitCompleteMatHarness.POLARITY_GATE = true;   // the SiteNormal dock default: stroke within the site-normal tolerance
        ExplicitCompleteMatHarness.POLARITY_TOL_DEG = ExplicitCompleteMatHarness.SITE_NORMAL_TOL_DEG;
        String tag = String.format(Locale.US, "lift%.0f%s%s", LIFT, ExplicitCompleteMatHarness.MOTOR_STERIC ? "_steric" : "",
                ExplicitCompleteMatHarness.HEAD_FLOOR ? "_floor" : "")
                + (CAPTURE ? "_cap_s" + SiteFrameStrokeAudit.SEED : "");
        System.out.printf(Locale.US, "%n=== TANAKA ANGLE PROBE (deterministic, %s) range %.0f nm step %.1f nm relax %d ===%n", tag, RANGE, DSTEP, RELAX);
        if (!TRAPS.isEmpty()) { walkMode(angles, out, tag); return; }
        for (String a : angles.split(",")) {
            double th = Double.parseDouble(a.trim());
            StringBuilder tsv = new StringBuilder("theta_set\tstate\tdir\td_nm\tfax_pN\tbound\ttheta_meas\ts2_e2e_nm\ttipZ_nm\thead_z_above_base_nm\ttip_site_u_nm\ttip_site_l_nm\ttip_site_z_nm\ts2_elev_deg\tsn_u\tsn_l\tsn_z\n");
            for (int st = 0; st < 2; st++) for (int dir = -1; dir <= 1; dir += 2) {
                Setup su = setup(th, st == 1);
                if (su == null) { System.out.printf(Locale.US, "  theta %.0f: no %s%n", th, CAPTURE ? "capture within -capmax" : "canonical site reachable"); break; }
                if (CAPTURE && dir < 0) System.out.printf(Locale.US, "  theta %6.1f %-5s %s%n", th, st == 1 ? "ADP" : "ADPPi", su.capInfo);
                int n = (int) Math.round(RANGE / DSTEP);
                for (int k = (dir < 0 ? 0 : 1); k <= n; k++) {   // d = 0 recorded once (on the - sweep)
                    double d = dir * k * DSTEP;
                    shift(su, d);
                    for (int t = 0; t < RELAX; t++) step(su, su.t++);
                    double[] g = obs(su);
                    tsv.append(String.format(Locale.US, "%.1f\t%s\t%d\t%.3f\t%.5f\t%d\t%.2f\t%.3f\t%.3f\t%.3f\t%.4f\t%.4f\t%.4f\t%.2f\t%.4f\t%.4f\t%.4f%n",
                            th, st == 1 ? "ADP" : "ADPPi", dir, d, g[0], (int) g[1], g[2], g[3], g[4], g[5], g[6], g[7], g[8], g[9], g[10], g[11], g[12]));
                    if (k == 0 || k == n) System.out.printf(Locale.US, "  theta %6.1f %-5s d=%+6.1f  F=%+8.3f pN  bound=%d  theta_meas=%7.2f  S2 e2e=%6.2f nm%n",
                            th, st == 1 ? "ADP" : "ADPPi", d, g[0], (int) g[1], g[2], g[3]);
                }
            }
            String fn = String.format(Locale.US, "fd_%s_theta%03.0f.tsv", tag, th);
            Files.writeString(Path.of(out, fn), tsv.toString());
        }
        System.out.println("\n  output -> " + out);
    }


    /**
     * WALK MODE: natural capture, then the trap. The filament (frozen between moves) is stepped along its axis toward
     * the side the net force F - k d points, DSTEP at a time with RELAX steps each, until that net force changes sign
     * (linear interpolation): the overdamped equilibrium in a trap of stiffness k centred on the capture position.
     * Pre-stroke (ADP.Pi) equilibrium d0, then the stroke (ADP) from d0 to d1. Productive step = -(d1 - d0).
     */
    static void walkMode(String angles, String out, String tag) throws Exception {
        java.nio.file.Path fp = Path.of(out, "walk_" + tag + ".tsv");
        if (!Files.exists(fp)) Files.writeString(fp, "theta_set\tseed\tk_pNnm\tarm\tcap_step\ttheta_cap\tsn_u\tsn_l\tsn_z\telev_cap\tF_cap_pN\t"
                + "F_pre0_pN\td0_nm\ttheta_d0\tF_post_at_d0_pN\td1_nm\ttheta_d1\telev_d1\tstep_productive_nm\tbound_end\n");
        for (String a : angles.split(",")) for (String ks : TRAPS.split(",")) {
            double th = Double.parseDouble(a.trim()), k = Double.parseDouble(ks.trim());
            Setup su = setup(th, false);
            String[] arms = ARMS.isEmpty() ? new String[]{ "" } : ARMS.split(",");
            Snap snap = su == null || ARMS.isEmpty() ? null : new Snap(su);
            for (String arm : arms) {
            if (su != null && snap != null) { snap.restore(); lock(su, arm); postCapture(su); }
            if (su == null) { System.out.printf(Locale.US, "  theta %.0f k %.3f: no capture within %d steps%n", th, k, CAPMAX);
                Files.writeString(fp, String.format(Locale.US, "%.1f\t%d\t%.4f\tNaN%n", th, SiteFrameStrokeAudit.SEED, k), java.nio.file.StandardOpenOption.APPEND); break; }
            double[] g0 = obs(su);
            String aTag = arm.isEmpty() ? "none" : arm;
            if (TIPDIAG) tipRow(out, tag, th, aTag, "pre_d0", 0.0, su);
            double fPre0 = g0[0];
            double[] w0 = walkTo(su, k, 0.0);
            double[] gp = obs(su);
            if (TIPDIAG) tipRow(out, tag, th, aTag, "pre_eq", w0[0], su);
            su.adp = true;
            for (int t = 0; t < RELAX; t++) step(su, su.t++);
            double fPostD0 = obs(su)[0];
            if (TIPDIAG) tipRow(out, tag, th, aTag, "iso_post", w0[0], su);
            double[] w1 = walkTo(su, k, w0[0]);
            double[] g1 = obs(su);
            if (TIPDIAG) tipRow(out, tag, th, aTag, "post_eq", w1[0], su);
            double stepP = -(w1[0] - w0[0]);
            String line = String.format(Locale.US, "%.1f\t%d\t%.4f\t%s\t%s\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%d%n",
                    th, SiteFrameStrokeAudit.SEED, k, arm.isEmpty() ? "none" : arm, su.capInfo.replaceAll("^capture step (\\d+).*", "$1"), su.capTheta, su.capSn[0], su.capSn[1], su.capSn[2],
                    su.capElev, su.capF, fPre0, w0[0], gp[2], fPostD0, w1[0], g1[2], g1[9], stepP, (int) g1[1]);
            Files.writeString(fp, line, java.nio.file.StandardOpenOption.APPEND);
            System.out.printf(Locale.US, "  theta %5.0f k %.3f arm %-5s | %s%n      F_pre(0) %+.2f  d0 %+.2f (theta %.0f) | F_post(d0) %+.2f  d1 %+.2f (theta %.0f)  STEP %+.2f nm%s%n",
                    th, k, arm.isEmpty() ? "none" : arm, su.capInfo, fPre0, w0[0], gp[2], fPostD0, w1[0], g1[2], stepP, g1[1] > 0 ? "" : "  DETACHED");
            }
        }
    }
    /**
     * S2-TIP DISPLACEMENT DIAGNOSTIC (2026-10-20). Points of the bound motor relative to the bound site, in the actin
     * frame (u = barbed, l = up x u, up): E = S2 base (node 0), P = S2 tip = lever base (node M), C = converter /
     * neck-head joint, H = head centre, X = F8 bond point (outGeom 0..8). Plus the axial force on the filament and
     * the filament displacement d. Differences between stages say where the stroke goes.
     */
    static void tipRow(String out, String tag, double th, String arm, String stage, double d, Setup su) throws java.io.IOException {
        var s = su.s; var e = s.e; int N = s.N, m = s.m, M = s.M, n = s.nSeg;
        java.nio.file.Path fp = Path.of(out, "tip_" + tag + ".tsv");
        StringBuilder h = new StringBuilder("theta_set\tseed\tarm\tstage\td_nm\tfax_pN\tbound");
        for (String pt : new String[]{ "E", "P", "C", "H", "X" }) for (String c : new String[]{ "u", "l", "z" }) h.append('\t').append(pt).append('_').append(c);
        if (!Files.exists(fp)) Files.writeString(fp, h.append('\n').toString());
        double[] up = { e.frame.get(6*N+m), e.frame.get(7*N+m), e.frame.get(8*N+m) };
        double[] uu = SiteFrameStrokeAudit.uOf(s);
        double[] l = { up[1]*uu[2]-up[2]*uu[1], up[2]*uu[0]-up[0]*uu[2], up[0]*uu[1]-up[1]*uu[0] };
        double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, n, m, s.Ract, 1.0, 0.0);
        StringBuilder r = new StringBuilder(String.format(Locale.US, "%.1f\t%d\t%s\t%s\t%.3f\t%.4f\t%d", th, SiteFrameStrokeAudit.SEED, arm, stage, d,
                S2TetherProbe.fax(s), s.G.mot.boundSeg.get(m) >= 0 ? 1 : 0));
        double[][] pts = {
            { e.nodes.get(m), e.nodes.get(N+m), e.nodes.get(2*N+m) },
            { e.nodes.get((3*M)*N+m), e.nodes.get((3*M+1)*N+m), e.nodes.get((3*M+2)*N+m) },
            { e.outGeom.get(m), e.outGeom.get(N+m), e.outGeom.get(2*N+m) },
            { e.outGeom.get(3*N+m), e.outGeom.get(4*N+m), e.outGeom.get(5*N+m) },
            { e.outGeom.get(6*N+m), e.outGeom.get(7*N+m), e.outGeom.get(8*N+m) } };
        for (double[] q : pts) {
            double[] v = sn == null ? new double[]{ Double.NaN, Double.NaN, Double.NaN } : new double[]{ q[0]-sn[3], q[1]-sn[4], q[2]-sn[5] };
            r.append(String.format(Locale.US, "\t%.3f\t%.3f\t%.3f", dot(v, uu)*1e3, dot(v, l)*1e3, dot(v, up)*1e3));
        }
        Files.writeString(fp, r.append('\n').toString(), java.nio.file.StandardOpenOption.APPEND);
    }

    /** Walk from d to the trap equilibrium F(d) = k d; returns {d_eq, steps used}. NaN if not reached within 60 nm. */
    static double[] walkTo(Setup su, double k, double d) {
        double g = obs(su)[0] - k*d; if (g == 0) return new double[]{ d, 0 };
        double dir = Math.signum(g);
        for (int i = 1; i <= (int) Math.round(60 / DSTEP); i++) {
            double dn = d + dir*DSTEP;
            shift(su, dn);
            for (int t = 0; t < RELAX; t++) step(su, su.t++);
            double gn = obs(su)[0] - k*dn;
            if (su.s.G.mot.boundSeg.get(su.s.m) < 0) return new double[]{ Double.NaN, i };
            if (Math.signum(gn) != dir) return new double[]{ d + (dn - d) * g / (g - gn), i };
            d = dn; g = gn;
        }
        return new double[]{ Double.NaN, -1 };
    }

    static final class Setup { SiteFrameStrokeAudit.S s; float[] fc0, f10, f20; double[] u; int t; boolean adp; String capInfo = ""; double capTheta, capElev, capF; double[] capSn = new double[3]; }

    static Setup setup(double thetaDeg, boolean adp) {
        SiteFrameStrokeAudit.S s = SiteFrameStrokeAudit.scene(CAPTURE ? 1 : 0);
        s.freeze = true;
        int N = s.N, m = s.m, M = s.M, n = s.nSeg; var f = s.G.fil; var e = s.e;
        if (LIFT != 0) {
            for (int q = 0; q < n; q++) { s.fc[2*n+q] += (float)(LIFT*1e-3); s.f1[2*n+q] += (float)(LIFT*1e-3); s.f2[2*n+q] += (float)(LIFT*1e-3); }
            SingleMotorMovieHarness.restore(f.coord, s.fc); SingleMotorMovieHarness.restore(f.end1, s.f1); SingleMotorMovieHarness.restore(f.end2, s.f2);
        }
        geom(s);
        double[] up = { e.frame.get(6*N+m), e.frame.get(7*N+m), e.frame.get(8*N+m) };
        double[] sHat = horiz(new double[]{ e.nodes.get((3*M)*N+m) - e.nodes.get(m), e.nodes.get((3*M+1)*N+m) - e.nodes.get(N+m),
                e.nodes.get((3*M+2)*N+m) - e.nodes.get(2*N+m) }, up);
        double[] u0 = horiz(new double[]{ f.uVec.get(0), f.uVec.get(n), f.uVec.get(2*n) }, up);
        double[] uT = rot(sHat, up, Math.toRadians(thetaDeg));
        double delta = signedAngle(u0, uT, up);
        double[] x0 = { e.outGeom.get(6*N+m), e.outGeom.get(7*N+m), e.outGeom.get(8*N+m) };
        // head stroke frame (bhat, econv) about the vertical through the S2 tip; the stroke-axis copy follows
        for (int r = 0; r < 2; r++) {
            double[] v = rot(new double[]{ e.frame.get((3*r)*N+m), e.frame.get((3*r+1)*N+m), e.frame.get((3*r+2)*N+m) }, up, delta);
            for (int c = 0; c < 3; c++) e.frame.set((3*r+c)*N+m, v[c]);
            if (r == 0) for (int c = 0; c < 3; c++) e.params.set((19+c)*N+m, v[c]);
        }
        geom(s);
        double[] x1 = { e.outGeom.get(6*N+m), e.outGeom.get(7*N+m), e.outGeom.get(8*N+m) };
        // filament: same rotation about the vertical through the old F8 point, then carried to the new one
        for (int q = 0; q < n; q++) {
            for (var a : new uk.ac.manchester.tornado.api.types.arrays.FloatArray[]{ f.coord, f.end1, f.end2 }) {
                double[] p = rot(new double[]{ a.get(q) - x0[0], a.get(n+q) - x0[1], a.get(2*n+q) - x0[2] }, up, delta);
                for (int c = 0; c < 3; c++) a.set(c*n+q, (float)(x1[c] + p[c]));
            }
            for (var a : new uk.ac.manchester.tornado.api.types.arrays.FloatArray[]{ f.uVec, f.yVec, f.zVec }) {
                double[] v = rot(new double[]{ a.get(q), a.get(n+q), a.get(2*n+q) }, up, delta);
                for (int c = 0; c < 3; c++) a.set(c*n+q, (float) v[c]);
            }
        }
        s.fc = SingleMotorMovieHarness.snap(f.coord); s.fu = SingleMotorMovieHarness.snap(f.uVec);
        s.fy = SingleMotorMovieHarness.snap(f.yVec);  s.fz = SingleMotorMovieHarness.snap(f.zVec);
        s.f1 = SingleMotorMovieHarness.snap(f.end1);  s.f2 = SingleMotorMovieHarness.snap(f.end2);
        s.G.mot.nucParams.set(1, 0f); s.G.mot.nucParams.set(4, 0f); s.G.mot.nucParams.set(6, 0f);   // no transitions, no release
        Setup su = new Setup(); su.s = s; su.adp = false;
        if (CAPTURE) {   // thermal search in ADP.Pi until the head binds by the production capture path
            e.noBind.set(m, 0); s.G.noBind[m] = false;
            while (s.G.mot.boundSeg.get(m) < 0 && su.t < CAPMAX) step(su, su.t++);
            if (s.G.mot.boundSeg.get(m) < 0) return null;
            e.matc.set(2, 0);                                     // motor Brownian off from here: deterministic sweep
            double[] g = obs(su);
            su.capTheta = g[2]; su.capElev = g[9]; su.capF = g[0]; su.capSn = new double[]{ g[10], g[11], g[12] };
            su.capInfo = String.format(Locale.US, "capture step %d  theta %.1f  F %+.2f pN  site normal (u,l,z) %+.2f %+.2f %+.2f  S2 elev %.1f",
                    su.t, g[2], g[0], g[10], g[11], g[12], g[9]);
        } else if (SiteFrameStrokeAudit.latchNearestCanonical(s) < 0) return null;
        e.noBind.set(m, 1); s.G.noBind[m] = true;
        su.u = SiteFrameStrokeAudit.uOf(s);
        su.fc0 = s.fc.clone(); su.f10 = s.f1.clone(); su.f20 = s.f2.clone();
        if (!ARMS.isEmpty()) return su;                             // walkMode: snapshot here, then lock + postCapture per arm
        postCapture(su);
        if (adp) { su.adp = true; for (int t = 0; t < RELAX; t++) step(su, su.t++); }   // the real stroke, isometric
        return su;
    }
    static void postCapture(Setup su) { for (int t = 0; t < RELAX; t++) step(su, su.t++); }   // pre-stroke (ADP.Pi) equilibrium at d = 0

    /**
     * BOUND-STATE LOCK ARMS (2026-10-20, jba: "free swivel in search, stiff when bound" as a diagnostic). Applied once,
     * right after natural capture, with the head bound for the rest of the run (no release). All work through the
     * existing base-joint term of the S2 solve (TwoBodyBeamAnalyticGpu, BEND clamped joint 0): rest tangent = frame
     * rows 12-14, stiffness kbend0 = kb * matc[5]/1000, matc[6] = 1 swivel (elevation only, rest = horizontal).
     *   none : unchanged (swivel, elevation hinge 0.1 kb)
     *   az   : azimuth locked at the capture azimuth, same 0.1 kb (rest tangent = horizontal S2 base direction, swivel off)
     *   az1k : as az at the full beam kb
     *   el   : swivel kept, elevation hinge raised to kb (rest horizontal, i.e. the old clamp's elevation term)
     *   both : base fully clamped at the capture direction at kb (rest tangent = the capture base segment, swivel off)
     *   bend : whole S2 bending stiffness x10 (params row 13; cf. the speculative S2_CATCH gate), base unchanged
     * Every arm restores the identical captured state first, so arms are paired on one capture.
     */
    static void lock(Setup su, String arm) {
        var s = su.s; var e = s.e; int N = s.N, m = s.m;
        double[] up = { e.frame.get(6*N+m), e.frame.get(7*N+m), e.frame.get(8*N+m) };
        double[] b0 = { e.nodes.get(3*N+m) - e.nodes.get(m), e.nodes.get(4*N+m) - e.nodes.get(N+m), e.nodes.get(5*N+m) - e.nodes.get(2*N+m) };
        double[] t = null;
        switch (arm) {
            case "", "none" -> { }
            case "az", "az1k" -> { t = horiz(b0, up); e.matc.set(6, 0); if (arm.equals("az1k")) e.matc.set(5, 1000); }
            case "el" -> e.matc.set(5, 1000);
            case "both" -> { double l = norm(b0); t = new double[]{ b0[0]/l, b0[1]/l, b0[2]/l }; e.matc.set(6, 0); e.matc.set(5, 1000); }
            case "bend" -> e.params.set(13*N+m, 10 * e.params.get(13*N+m));
            default -> throw new IllegalArgumentException("unknown arm " + arm);
        }
        if (t != null) for (int c = 0; c < 3; c++) e.frame.set((12+c)*N+m, t[c]);
    }

    /** In-place snapshot of every TornadoVM / primitive array and primitive field reachable from the scene (softbox objects only). */
    static final class Snap {
        final Setup su; final int t; final boolean adp;
        final java.util.List<Runnable> back = new java.util.ArrayList<>();
        final java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Snap(Setup su) { this.su = su; t = su.t; adp = su.adp; grab(su.s); }
        void restore() { for (Runnable r : back) r.run(); su.t = t; su.adp = adp; }
        void grab(Object o) {
            if (o == null || o instanceof Enum<?> || o instanceof Record || !seen.add(o)) return;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                for (var f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try { f.setAccessible(true); } catch (RuntimeException ex) { continue; }
                    try { grabField(o, f); } catch (IllegalAccessException ex) { throw new RuntimeException(ex); }
                }
        }
        void grabField(Object o, java.lang.reflect.Field f) throws IllegalAccessException {
            Class<?> ty = f.getType(); Object v = f.get(o);
            if (ty.isPrimitive()) { if (java.lang.reflect.Modifier.isFinal(f.getModifiers())) return; Object val = v; back.add(() -> { try { f.set(o, val); } catch (IllegalAccessException ex) { throw new RuntimeException(ex); } }); return; }
            if (v == null) return;
            if (v instanceof uk.ac.manchester.tornado.api.types.arrays.FloatArray a) { if (!seen.add(a)) return; float[] c = new float[a.getSize()]; for (int i = 0; i < c.length; i++) c[i] = a.get(i); back.add(() -> { for (int i = 0; i < c.length; i++) a.set(i, c[i]); }); }
            else if (v instanceof uk.ac.manchester.tornado.api.types.arrays.DoubleArray a) { if (!seen.add(a)) return; double[] c = new double[a.getSize()]; for (int i = 0; i < c.length; i++) c[i] = a.get(i); back.add(() -> { for (int i = 0; i < c.length; i++) a.set(i, c[i]); }); }
            else if (v instanceof uk.ac.manchester.tornado.api.types.arrays.IntArray a) { if (!seen.add(a)) return; int[] c = new int[a.getSize()]; for (int i = 0; i < c.length; i++) c[i] = a.get(i); back.add(() -> { for (int i = 0; i < c.length; i++) a.set(i, c[i]); }); }
            else if (v.getClass().isArray()) { if (!seen.add(v)) return; grabArray(v); }
            else if (v.getClass().getName().startsWith("softbox.")) grab(v);
            // the reference itself must not change identity across a run
            back.add(() -> { try { if (f.get(o) != v) throw new IllegalStateException("field " + f + " changed identity"); } catch (IllegalAccessException ex) { throw new RuntimeException(ex); } });
        }
        void grabArray(Object v) {
            Class<?> ct = v.getClass().getComponentType(); int n = java.lang.reflect.Array.getLength(v);
            if (ct.isPrimitive()) { Object c = java.lang.reflect.Array.newInstance(ct, n); System.arraycopy(v, 0, c, 0, n); back.add(() -> System.arraycopy(c, 0, v, 0, n)); return; }
            for (int i = 0; i < n; i++) { Object x = java.lang.reflect.Array.get(v, i); if (x == null) continue;
                if (x.getClass().isArray()) { if (seen.add(x)) grabArray(x); } else if (x.getClass().getName().startsWith("softbox.")) grab(x); }
        }
    }

    static void step(Setup su, int t) {
        su.s.G.mot.nucleotideState.set(su.s.m, su.adp ? MotorStore.NUC_ADP : MotorStore.NUC_ADPPI);
        SiteFrameStrokeAudit.step(su.s, t);
    }
    static void shift(Setup su, double d) {
        var s = su.s; int n = s.nSeg;
        for (int q = 0; q < n; q++) for (int c = 0; c < 3; c++) {
            double sh = d * 1e-3 * su.u[c];
            s.fc[c*n+q] = (float)(su.fc0[c*n+q] + sh); s.f1[c*n+q] = (float)(su.f10[c*n+q] + sh); s.f2[c*n+q] = (float)(su.f20[c*n+q] + sh);
        }
    }
    static void geom(SiteFrameStrokeAudit.S s) {
        var e = s.e;
        TwoBodyBeamAnalyticGpu.matBeamGeomTilt(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom, e.convF, e.chiHead);
        SiteNormalBindSystem.headAxisStep(e.frame, e.params, e.q, e.chiHead, e.convF, e.outGeom, e.exCounts);
    }
    /** {fax pN, bound, theta_meas deg, S2 e2e nm, S2 tip height nm, head-centre height above base nm, tip-site u/l/up nm, S2 elevation deg}. */
    static double[] obs(Setup su) {
        var s = su.s; var e = s.e; int N = s.N, m = s.m, M = s.M, n = s.nSeg;
        double[] up = { e.frame.get(6*N+m), e.frame.get(7*N+m), e.frame.get(8*N+m) };
        double[] d = { e.nodes.get((3*M)*N+m) - e.nodes.get(m), e.nodes.get((3*M+1)*N+m) - e.nodes.get(N+m), e.nodes.get((3*M+2)*N+m) - e.nodes.get(2*N+m) };
        double[] u = horiz(new double[]{ s.G.fil.uVec.get(0), s.G.fil.uVec.get(n), s.G.fil.uVec.get(2*n) }, up);
        double th = Math.toDegrees(signedAngle(horiz(d, up), u, up)); if (th < -1) th += 360;
        double hz = (e.outGeom.get(9*N+m) - e.nodes.get(m))*up[0] + (e.outGeom.get(10*N+m) - e.nodes.get(N+m))*up[1] + (e.outGeom.get(11*N+m) - e.nodes.get(2*N+m))*up[2];
        // S2 tip (= lever end) relative to the bound site, in the actin frame (u barbed, l = up x u, up)
        double[] tp = { 0, 0, 0 }, snf = { 0, 0, 0 };
        double[] sn = SiteNormalBindSystem.boundSiteNormal(s.G, n, m, s.Ract, 1.0, 0.0);
        double[] uu = SiteFrameStrokeAudit.uOf(s);
        if (sn != null) {
            double[] r = { e.nodes.get((3*M)*N+m) - sn[3], e.nodes.get((3*M+1)*N+m) - sn[4], e.nodes.get((3*M+2)*N+m) - sn[5] };
            double[] l = { up[1]*uu[2]-up[2]*uu[1], up[2]*uu[0]-up[0]*uu[2], up[0]*uu[1]-up[1]*uu[0] };
            tp = new double[]{ dot(r, uu)*1e3, dot(r, l)*1e3, dot(r, up)*1e3 };
            double[] nv = { sn[0], sn[1], sn[2] }; snf = new double[]{ dot(nv, uu), dot(nv, l), dot(nv, up) };
        }
        double elev = Math.toDegrees(Math.asin(dot(d, up) / norm(d)));
        return new double[]{ S2TetherProbe.fax(s), s.G.mot.boundSeg.get(m) >= 0 ? 1 : 0, th, norm(d)*1e3, dot(d, up)*1e3, hz*1e3,
                             tp[0], tp[1], tp[2], elev, snf[0], snf[1], snf[2] };
    }

    static double[] horiz(double[] v, double[] up) {
        double a = dot(v, up); double[] h = { v[0]-a*up[0], v[1]-a*up[1], v[2]-a*up[2] }; double l = norm(h);
        return new double[]{ h[0]/l, h[1]/l, h[2]/l };
    }
    static double[] rot(double[] v, double[] k, double a) {   // Rodrigues, unit k
        double c = Math.cos(a), sn = Math.sin(a), kv = dot(k, v);
        return new double[]{ v[0]*c + (k[1]*v[2]-k[2]*v[1])*sn + k[0]*kv*(1-c),
                             v[1]*c + (k[2]*v[0]-k[0]*v[2])*sn + k[1]*kv*(1-c),
                             v[2]*c + (k[0]*v[1]-k[1]*v[0])*sn + k[2]*kv*(1-c) };
    }
    static double signedAngle(double[] a, double[] b, double[] up) {
        double[] x = { a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0] };
        return Math.atan2(dot(x, up), dot(a, b));
    }
    static double dot(double[] a, double[] b) { return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]; }
    static double norm(double[] a) { return Math.sqrt(dot(a, a)); }
}
