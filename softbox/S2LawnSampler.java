package softbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * S2 LAWN SAMPLER — thermal conformations of UNBOUND explicit-S2 motors (2026-10-18; jba: "estimate the effect of all
 * those little motor parts colliding with the filament"). <b>Probe only; no physics changes.</b>
 *
 * <p>Builds the SiteFrameStrokeAudit scene with the SiteNormal S2 defaults (one surface, soft base hinge, swivel, dock),
 * forbids binding for every motor, holds the filament frozen, and records every ACTIVE (not culled) motor's S2 nodes,
 * lever end and head centre at a fixed cadence. The model has no motor-filament excluded volume, so the filament does
 * not perturb these conformations: they are the free-lawn ensemble the offline steric estimator needs.
 *
 * <pre>  java ... softbox.S2LawnSampler [-relax steps] [-samples n] [-every steps] [-out file]</pre>
 * Runner: CPU sequential. No GPU work.
 */
public final class S2LawnSampler {
    private S2LawnSampler() {}

    public static void main(String[] args) throws Exception {
        int relax = 20000, samples = 200, every = 500; double lift = 0;
        String out = "RUN_LOGS/motor_audit/steric_estimate/lawn_conformations.tsv";
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "-relax" -> relax = Integer.parseInt(args[++i]);
            case "-samples" -> samples = Integer.parseInt(args[++i]);
            case "-every" -> every = Integer.parseInt(args[++i]);
            case "-out" -> out = args[++i];
            case "-steric" -> ExplicitCompleteMatHarness.MOTOR_STERIC = true;
            case "-head-floor" -> ExplicitCompleteMatHarness.HEAD_FLOOR = true;
            case "-lift" -> lift = Double.parseDouble(args[++i]);   // raise the frozen filament (nm)
            default -> { }
        }
        Files.createDirectories(Path.of(out).getParent());
        ExplicitCompleteMatHarness.S2_ONE_SURFACE = true;
        ExplicitCompleteMatHarness.S2_HINGE_PM = 100;
        ExplicitCompleteMatHarness.S2_LIFT_INIT = true;
        ExplicitCompleteMatHarness.S2_SWIVEL = true;
        ExplicitCompleteMatHarness.SWIVEL_DOCK = true;
        SiteFrameStrokeAudit.S s = SiteFrameStrokeAudit.scene(1);
        s.freeze = true;
        int N = s.N, M = s.M;
        if (lift != 0) {
            int n = s.nSeg; var f = s.G.fil;
            for (int q = 0; q < n; q++) { s.fc[2*n+q] += (float)(lift*1e-3); s.f1[2*n+q] += (float)(lift*1e-3); s.f2[2*n+q] += (float)(lift*1e-3); }
            SingleMotorMovieHarness.restore(f.coord, s.fc); SingleMotorMovieHarness.restore(f.end1, s.f1); SingleMotorMovieHarness.restore(f.end2, s.f2);
        }
        boolean st = ExplicitCompleteMatHarness.stericRowsOn();
        double fzSum = 0, fzSq = 0; long fzN = 0;
        for (int m = 0; m < N; m++) { s.G.noBind[m] = true; s.e.noBind.set(m, 1); }
        double ex = s.e.frame.get(6*N), ey = s.e.frame.get(7*N), ez = s.e.frame.get(8*N);
        System.out.printf(Locale.US, "  steric=%s head-floor=%s lift=%.1f nm%n", ExplicitCompleteMatHarness.MOTOR_STERIC, ExplicitCompleteMatHarness.HEAD_FLOOR, lift);
        System.out.printf(Locale.US, "S2 LAWN SAMPLER: N=%d motors, M=%d, dt=%.3g, relax=%d, samples=%d every %d; eup=(%.3f %.3f %.3f)%n",
                N, M, SiteFrameStrokeAudit.DT, relax, samples, every, ex, ey, ez);
        int t = 0;
        for (int k = 0; k < relax; k++) SiteFrameStrokeAudit.step(s, t++);
        StringBuilder b = new StringBuilder("sample\tm");
        for (int j = 0; j <= M; j++) b.append("\tn").append(j).append("x\tn").append(j).append("y\tn").append(j).append("z");
        b.append("\tCx\tCy\tCz\tHx\tHy\tHz\n");
        int rows = 0;
        for (int smp = 0; smp < samples; smp++) {
            for (int k = 0; k < every; k++) {
                SiteFrameStrokeAudit.step(s, t++);
                if (st) {   // vertical steric force ON THE FILAMENT this step (sum of both slots, all motors), pN
                    double fz = 0; for (int m = 0; m < N; m++) fz += s.e.restC.get(32*N+m) + s.e.restC.get(38*N+m);
                    fz *= 1e12; fzSum += fz; fzSq += fz*fz; fzN++;
                }
            }
            for (int m = 0; m < N; m++) {
                if (s.e.active.get(m) != 1) continue;
                b.append(smp).append('\t').append(m);
                for (int j = 0; j <= M; j++) for (int c = 0; c < 3; c++)
                    b.append('\t').append(String.format(Locale.US, "%.6g", s.e.nodes.get((3*j+c)*N+m) * 1e3));
                for (int c = 0; c < 6; c++) b.append('\t').append(String.format(Locale.US, "%.6g", s.e.outGeom.get(c*N+m) * 1e3));
                b.append('\n'); rows++;
            }
            if (smp % 20 == 0) System.out.printf(Locale.US, "  sample %d (step %d), rows %d%n", smp, t, rows);
        }
        Files.writeString(Path.of(out), b.toString());
        if (st && fzN > 0) {
            double mu = fzSum/fzN, sd = Math.sqrt(Math.max(0, fzSq/fzN - mu*mu));
            System.out.printf(Locale.US, "  STERIC Fz on filament: mean %+.4f pN  sd %.4f  (steps %d)%n", mu, sd, fzN);
        }
        { var f = s.G.fil; int n = s.nSeg;   // filament geometry for the offline prediction
          StringBuilder g = new StringBuilder("seg\tcx\tcy\tcz\tux\tuy\tuz\thalf\n");
          for (int q = 0; q < n; q++) g.append(String.format(Locale.US, "%d\t%.6g\t%.6g\t%.6g\t%.6g\t%.6g\t%.6g\t%.6g%n", q,
                  f.coord.get(q)*1e3, f.coord.get(n+q)*1e3, f.coord.get(2*n+q)*1e3, f.uVec.get(q), f.uVec.get(n+q), f.uVec.get(2*n+q), 0.5*f.segLength.get(q)*1e3));
          Files.writeString(Path.of(out + ".fil.tsv"), g.toString()); }
        System.out.println("  output (nm) -> " + out);
    }
}
