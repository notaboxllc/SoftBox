package softbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * S2 TETHER PROBE — does the explicit S2, in the CURRENT site-normal geometry, carry compression (a strut) or yield
 * (a rope)? (2026-10-16, jba: "run the buckling test".)
 *
 * <p><b>Probe only. Nothing is tuned and no physics changes.</b> One head is latched in rigor at a canonical site
 * (SiteFrameStrokeAudit's deterministic scene), with the SiteNormal S2 defaults switched on: one surface, soft base
 * hinge (0.1 x kb), base swivel, the stereospecific dock. The filament is FROZEN and stepped quasi-statically along
 * its own axis, d = 0 -> +D -> -D -> 0; at each d the motor relaxes and we record the axial force ON THE FILAMENT
 * and the S2's shape. Chemistry is frozen in rigor (ATP binding rate 0), so the bond cannot release.
 *
 * <p>Literature target (Kaya &amp; Higuchi 2010 Science; their ISB 2009 abstract): ~2.8 pN/nm in stretch, ~0.02
 * pN/nm under compression over ~80 nm, i.e. the S2 is a rope, not a strut. Old SM6 (clamped horizontal base, no
 * swivel): ~1 pN/nm in tension, buckling only at -7.5 nm with a ~7 pN compressive plateau.
 *
 * <pre>
 *   java ... softbox.S2TetherProbe [-brown] [-range nm] [-dstep nm] [-relax steps] [-avg steps] [-out dir]
 *     -brown : thermal (motor Brownian ON), force time-averaged over -avg steps at each d
 * </pre>
 * Runner: CPU sequential. No GPU work.
 */
public final class S2TetherProbe {
    private S2TetherProbe() {}

    public static void main(String[] args) throws Exception {
        boolean brown = false; double range = 30, dstep = 2.5; int relax = 4000, avg = 4000;
        String out = "RUN_LOGS/motor_audit/s2_tether_probe";
        boolean legacyS2 = false; double lift = 0;
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "-brown" -> brown = true;
            case "-range" -> range = Double.parseDouble(args[++i]);
            case "-dstep" -> dstep = Double.parseDouble(args[++i]);
            case "-relax" -> relax = Integer.parseInt(args[++i]);
            case "-avg" -> avg = Integer.parseInt(args[++i]);
            case "-out" -> out = args[++i];
            case "-legacy-s2" -> legacyS2 = true;
            case "-steric" -> ExplicitCompleteMatHarness.MOTOR_STERIC = true;
            case "-head-floor" -> ExplicitCompleteMatHarness.HEAD_FLOOR = true;
            case "-lift" -> lift = Double.parseDouble(args[++i]);   // DIAGNOSTIC: raise the frozen filament (nm) before latching
            case "-s2-segs" -> TwoBodyConverterMotor.S2_SEGS_OVERRIDE = Integer.parseInt(args[++i]);   // discretization convergence   // clamped base, stiff hinge, no swivel/dock (the SM6-like geometry)
            default -> { }
        }
        Files.createDirectories(Path.of(out));
        // SiteNormal S2 defaults (2026-10-08 one surface + soft hinge; swivel; 2026-10-15 dock)
        ExplicitCompleteMatHarness.S2_ONE_SURFACE = !legacyS2;
        ExplicitCompleteMatHarness.S2_HINGE_PM = legacyS2 ? 1000 : 100;
        ExplicitCompleteMatHarness.S2_LIFT_INIT = false;   // the single probed motor is latched by hand
        ExplicitCompleteMatHarness.S2_SWIVEL = !legacyS2;
        ExplicitCompleteMatHarness.SWIVEL_DOCK = !legacyS2;
        SiteFrameStrokeAudit.S s = SiteFrameStrokeAudit.scene(brown ? 1 : 0);
        s.freeze = true;
        if (lift != 0) {   // diagnostic only: steeper S2 take-off; filament height is NOT set this way in any assay
            int n = s.nSeg; var f = s.G.fil;
            for (int q = 0; q < n; q++) { s.fc[2*n+q] += (float)(lift*1e-3); s.f1[2*n+q] += (float)(lift*1e-3); s.f2[2*n+q] += (float)(lift*1e-3); }
            SingleMotorMovieHarness.restore(f.coord, s.fc); SingleMotorMovieHarness.restore(f.end1, s.f1); SingleMotorMovieHarness.restore(f.end2, s.f2);
        }
        int seg = SiteFrameStrokeAudit.latchNearestCanonical(s);
        if (seg < 0) { System.out.println("  no canonical site reachable — abort"); return; }
        int m = s.m, N = s.N, M = s.M;
        s.e.noBind.set(m, 1);
        s.G.mot.nucParams.set(1, 0f);                                  // atpOn = 0 => rigor never releases
        s.G.mot.nucleotideState.set(m, MotorStore.NUC_NONE);
        double[] u = SiteFrameStrokeAudit.uOf(s);
        System.out.printf(Locale.US, "%n=== S2 TETHER PROBE (%s, %s geometry) ===%n", brown ? "THERMAL" : "DETERMINISTIC",
                legacyS2 ? "LEGACY clamped-base" : "SITE-NORMAL (one surface, hinge 0.1, swivel, dock)");
        System.out.printf(Locale.US, "  filament lift = %+.1f nm (diagnostic)%n", lift);
        System.out.printf(Locale.US, "  S2 segments M = %d (l0 = %.1f nm)%n", M, s.G.g4l0 * 1e3);
        System.out.printf(Locale.US, "  motor %d latched to seg %d ; u_fil = (%+.3f %+.3f %+.3f) ; +d = filament toward BARBED%n", m, seg, u[0], u[1], u[2]);
        float[] fc0 = s.fc.clone(), f10 = s.f1.clone(), f20 = s.f2.clone();
        int t = 0;
        for (int k = 0; k < relax; k++) step(s, t++);                // settle at d = 0
        double[] s2dir0 = s2Dir(s);
        System.out.printf(Locale.US, "  at d=0: S2 horizontal direction . u = %+.3f  (+ = S2 points toward barbed, i.e. +d STRETCHES it)%n",
                s2dir0[0]*u[0] + s2dir0[1]*u[1] + s2dir0[2]*u[2]);
        StringBuilder tsv = new StringBuilder("leg\td_nm\tfax_pN\tfax_sd_pN\tbound\ts2_e2e_nm\ts2_contour_nm\ts2_bend_deg\ts2_dir_dot_u\ttip_z_nm\tf8_ext_nm\n");
        System.out.printf(Locale.US, "  %5s %8s %9s %8s %9s %9s %9s %9s %8s%n", "leg", "d nm", "fax pN", "sd", "e2e nm", "bend deg", "S2.u", "tipZ nm", "F8 nm");
        double[] path = buildPath(range, dstep);
        for (int ip = 0; ip < path.length; ip++) {
            double d = path[ip];
            String leg = ip == 0 ? "0" : (path[ip] > path[ip-1] ? "up" : "down");
            for (int q = 0; q < s.nSeg; q++) for (int c = 0; c < 3; c++) {
                double sh = d * 1e-3 * u[c];
                s.fc[c*s.nSeg+q] = (float) (fc0[c*s.nSeg+q] + sh);
                s.f1[c*s.nSeg+q] = (float) (f10[c*s.nSeg+q] + sh);
                s.f2[c*s.nSeg+q] = (float) (f20[c*s.nSeg+q] + sh);
            }
            for (int k = 0; k < relax; k++) step(s, t++);
            double sf = 0, sf2 = 0; int n = brown ? avg : 1;
            for (int k = 0; k < n; k++) {
                if (brown) step(s, t++);
                double f = fax(s); sf += f; sf2 += f*f;
            }
            double fm = sf/n, fsd = Math.sqrt(Math.max(0, sf2/n - fm*fm));
            double[] g = shape(s);
            int bound = s.G.mot.boundSeg.get(m) >= 0 ? 1 : 0;
            System.out.printf(Locale.US, "  %5s %+8.2f %+9.3f %8.3f %9.2f %9.1f %+9.3f %9.2f %8.2f%s%n", leg, d, fm, fsd, g[0], g[2], g[3], g[4], g[5], bound == 1 ? "" : "  DETACHED");
            tsv.append(String.format(Locale.US, "%s\t%.3f\t%.5f\t%.5f\t%d\t%.4f\t%.4f\t%.3f\t%.4f\t%.4f\t%.4f%n", leg, d, fm, fsd, bound, g[0], g[1], g[2], g[3], g[4], g[5]));
        }
        String fn = (legacyS2 ? "legacy_" : "sitenormal_") + (brown ? "thermal" : "deterministic")
                + (TwoBodyConverterMotor.S2_SEGS_OVERRIDE > 0 ? "_M" + TwoBodyConverterMotor.S2_SEGS_OVERRIDE : "")
                + (lift != 0 ? String.format(Locale.US, "_lift%.0f", lift) : "") + ".tsv";
        Files.writeString(Path.of(out, fn), tsv.toString());
        System.out.println("\n  output -> " + out + "/" + fn);
    }

    static void step(SiteFrameStrokeAudit.S s, int t) {
        s.G.mot.nucleotideState.set(s.m, MotorStore.NUC_NONE);
        SiteFrameStrokeAudit.step(s, t);
    }
    static double[] buildPath(double range, double dstep) {
        int n = (int) Math.round(range / dstep);
        double[] p = new double[4*n + 1]; int i = 0;
        p[i++] = 0;
        for (int k = 1; k <= n; k++) p[i++] = k*dstep;
        for (int k = n-1; k >= -n; k--) p[i++] = k*dstep;
        for (int k = -n+1; k <= 0; k++) p[i++] = k*dstep;
        return p;
    }
    /** Axial force ON THE FILAMENT (pN); + = toward barbed. */
    static double fax(SiteFrameStrokeAudit.S s) {
        int m = s.m, bs = s.G.mot.boundSeg.get(m); if (bs < 0) return 0;
        int d = m*13;
        return (s.G.bondData.get(d+6)*s.G.fil.uVec.get(bs) + s.G.bondData.get(d+7)*s.G.fil.uVec.get(s.nSeg+bs)
              + s.G.bondData.get(d+8)*s.G.fil.uVec.get(2*s.nSeg+bs)) * 1e12;
    }
    /** Horizontal unit direction of the S2 from base node 0 to tip node M. */
    static double[] s2Dir(SiteFrameStrokeAudit.S s) {
        int N = s.N, m = s.m, M = s.M;
        double ex = s.e.frame.get(6*N+m), ey = s.e.frame.get(7*N+m), ez = s.e.frame.get(8*N+m);
        double dx = s.e.nodes.get((3*M)*N+m) - s.e.nodes.get(m), dy = s.e.nodes.get((3*M+1)*N+m) - s.e.nodes.get(N+m),
               dz = s.e.nodes.get((3*M+2)*N+m) - s.e.nodes.get(2*N+m);
        double de = dx*ex + dy*ey + dz*ez; dx -= de*ex; dy -= de*ey; dz -= de*ez;
        double l = Math.sqrt(dx*dx + dy*dy + dz*dz); if (!(l > 1e-15)) return new double[]{0,0,0};
        return new double[]{ dx/l, dy/l, dz/l };
    }
    /** {e2e nm, contour nm, max inter-segment bend deg, S2dir.u, tip height above base nm, F8 extension nm}. */
    static double[] shape(SiteFrameStrokeAudit.S s) {
        int N = s.N, m = s.m, M = s.M;
        double[][] p = new double[M+1][3];
        for (int j = 0; j <= M; j++) for (int c = 0; c < 3; c++) p[j][c] = s.e.nodes.get((3*j+c)*N+m);
        double cont = 0, bend = 0;
        for (int j = 0; j < M; j++) {
            double[] a = sub(p[j+1], p[j]); cont += norm(a);
            if (j > 0) { double[] b = sub(p[j], p[j-1]);
                double c = dot(a,b)/(norm(a)*norm(b)); bend = Math.max(bend, Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))))); }
        }
        double e2e = norm(sub(p[M], p[0]));
        double[] u = SiteFrameStrokeAudit.uOf(s), sd = s2Dir(s);
        double ex = s.e.frame.get(6*N+m), ey = s.e.frame.get(7*N+m), ez = s.e.frame.get(8*N+m);
        double tipZ = dot(sub(p[M], p[0]), new double[]{ex, ey, ez});
        int bs = s.G.mot.boundSeg.get(m); double f8 = 0;
        if (bs >= 0) { int d = m*13; double fx = s.G.bondData.get(d), fy = s.G.bondData.get(d+1), fz = s.G.bondData.get(d+2);
            f8 = Math.sqrt(fx*fx+fy*fy+fz*fz) / s.e.params.get(5*N+m); }   // |F| / kF8 (N / (N/um)) -> um
        return new double[]{ e2e*1e3, cont*1e3, bend, dot(sd, u), tipZ*1e3, f8*1e3 };
    }
    static double[] sub(double[] a, double[] b) { return new double[]{ a[0]-b[0], a[1]-b[1], a[2]-b[2] }; }
    static double dot(double[] a, double[] b) { return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]; }
    static double norm(double[] a) { return Math.sqrt(dot(a, a)); }
}
