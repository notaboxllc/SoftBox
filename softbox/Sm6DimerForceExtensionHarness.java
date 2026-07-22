package softbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SM6 (dimer arm) — QUASISTATIC FORCE-EXTENSION of the EXPLICIT HMM DIMER (CPU-only, deterministic).
 *
 * <p>Uses the fixed-actin-spring channel: each bound head is held by its cross-bridge spring to a
 * world actin anchor. Imposing a displacement means MOVING THE ANCHOR and re-relaxing the 19-DOF
 * forked beam to mechanical equilibrium with {@code brownian=false}; the transmitted force is read
 * back from the spring. This is the dimer analogue of the single-head clamp sweep.
 *
 * <p><b>Series compliance is reported, not removed.</b> The measured coordinate is the ACTIN-ANCHOR
 * displacement, so the curve is the dimer in series with the F8 spring (1 pN/nm). That is the
 * physically meaningful quantity — it is what the actin site experiences — but it means the reported
 * stiffness is a SERIES stiffness, and the bare dimer stiffness is stiffer. No stiffness is retuned
 * to work around this.
 *
 * <h2>Frozen configuration</h2>
 * The STANDING dimer config is used: {@code build(Ms=3, Ma=1, Mb=1, splay=16 deg, dt, alpha=10 deg,
 * branchEI=0.25, branchEA=0.03, branchLen=10 nm, forkK=1.0)}. The {@code branchEA=0.03} value is the
 * frozen standing value ({@code ExplicitHmmDimerGpuParams.STANDING_BRANCH_EA}), NOT the bare
 * {@code build} default of 1.0 — using the default would characterise a model the project does not run.
 *
 * <h2>Chemistry</h2>
 * Frozen by construction: the chemistry kernel is never called, and each head's converter target
 * {@code thetaS} is pinned to the prepared state for the whole sweep.
 *
 * <h2>Rupture</h2>
 * Structurally absent from the CPU object solve ({@code ExplicitHmmDimer.solve} contains no
 * reference to {@code ExplicitHmmDimerGpuParams}); no head is ever auto-released. The harness still
 * aborts if the globals are non-default.
 */
public final class Sm6DimerForceExtensionHarness {

    static final double DT = 2.5e-6;
    // ---- FROZEN standing dimer configuration ----
    static final int MS = 3, MA = 1, MB = 1;
    static final double SPLAY_DEG = 16.0, ALPHA_DEG = 10.0;
    static final double BRANCH_EI = 0.25;            // ExplicitHmmDimerGlidingHarness.BREI
    static final double BRANCH_EA = 0.03;            // STANDING value (not the build() default of 1.0)
    static final double BRANCH_LEN_NM = 10.0, FORK_K = 1.0;

    static final int SETTLE_MAX = 20000;
    static final double SETTLE_TOL = 3e-7;           // µm max-move, matching the existing harnesses

    static final class Pt {
        String binding, state, axis, mode; double sepNm, dNm;
        double forceTotPn, forceAPn, forceBPn, forceAxialPn;
        double uBend, uStretchShared, uStretchBranch, uTotal;
        double forkDeg, jointGapNm, contourUm, branchExtNm;
        int solverFail, iters; double residUm;
    }

    public static void main(String[] args) {
        String outDir = "RUN_LOGS/motor_validation/sm6_force_extension";
        String rev = "unknown", buildId = "unknown";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-outdir" -> outDir = args[++i];
                case "-rev" -> rev = args[++i];
                case "-buildid" -> buildId = args[++i];
                default -> { }
            }
        }
        System.out.println("=== SoftBox — SM6 (dimer): quasistatic force-extension of the explicit HMM dimer ===");
        if (ExplicitHmmDimerGpuParams.RUPTURE_MODE != 0 || ExplicitHmmDimerGpuParams.EMERGENCY_ON) {
            System.out.println("  *** ABORT: rupture failsafe active ***"); System.exit(3);
        }
        System.out.printf(Locale.US, "# FROZEN: Ms=%d Ma=%d Mb=%d splay=%.1f alpha=%.1f branchEI=%.3f branchEA=%.3f "
                + "branchLen=%.1fnm forkK=%.2f  dt=%.2e%n",
                MS, MA, MB, SPLAY_DEG, ALPHA_DEG, BRANCH_EI, BRANCH_EA, BRANCH_LEN_NM, FORK_K, DT);
        System.out.printf(Locale.US, "# rupture: RUPTURE_MODE=%d EMERGENCY_ON=%s (structurally absent from the CPU object solve)%n",
                ExplicitHmmDimerGpuParams.RUPTURE_MODE, ExplicitHmmDimerGpuParams.EMERGENCY_ON);
        System.out.println("# chemistry FROZEN (cycle kernel never called; thetaS pinned). Brownian OFF.");
        System.out.println("# displacement is imposed on the ACTIN ANCHOR => curves include the F8 spring in series.");

        List<Pt> all = new ArrayList<>();
        double[] axLad = ladder(-20, 20, 0.5);
        double[] trLad = ladder(-10, 10, 0.5);
        double[] seps = { 0.0, 2.75, 5.5, 8.25, 11.0 };      // head-to-head axial separations (nm)

        // --- one-head-bound: axial + transverse, both prepared states ---
        for (String st : new String[]{ "adp", "adppi" }) {
            all.addAll(sweep("one-head", st, "axial", 0.0, axLad, "sym"));
            all.addAll(sweep("one-head", st, "transverse", 0.0, trLad, "sym"));
        }
        // --- two-head-bound at several separations: symmetric and asymmetric loading ---
        for (double sep : seps) {
            all.addAll(sweep("two-head", "adp", "axial", sep, axLad, "sym"));     // both anchors move together
            all.addAll(sweep("two-head", "adp", "axial", sep, axLad, "asym"));    // only head A's anchor moves
        }
        all.addAll(sweep("two-head", "adp", "transverse", 5.5, trLad, "sym"));
        all.addAll(sweep("two-head", "adp", "transverse", 5.5, trLad, "anti"));   // antisymmetric transverse

        // --- head-label exchange symmetry control ---
        List<Pt> swapped = sweep("two-head-swapped", "adp", "axial", 5.5, axLad, "asymB");
        all.addAll(swapped);

        write(all, outDir, rev, buildId);
        System.out.printf(Locale.US, "%n# SM6 dimer done: %d points → %s/force_extension_dimer.csv%n", all.size(), outDir);
    }

    static double[] ladder(double lo, double hi, double step) {
        List<Double> v = new ArrayList<>();
        for (double d = lo; d <= hi + 1e-9; d += step) v.add(Math.round(d * 1e6) / 1e6);
        return v.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Build the standing dimer with both heads' chemistry pinned, relaxed detached. */
    static ExplicitHmmDimer.Dimer build(String state) {
        ExplicitHmmDimer.Dimer d = ExplicitHmmDimer.build(MS, MA, MB, SPLAY_DEG, DT,
                ALPHA_DEG, BRANCH_EI, BRANCH_EA, BRANCH_LEN_NM, FORK_K);
        double ts = state.equals("adppi") ? TwoBodyConverterMotor.PRESTROKE_THETAS
                                          : TwoBodyConverterMotor.ADP_THETAS;
        d.hA.thetaS = ts; d.hB.thetaS = ts;         // FROZEN converter targets
        settle(d, new double[3], new double[3]);
        return d;
    }

    /** Deterministic relaxation to mechanical equilibrium. Returns {iters, residUm, solverFails}. */
    static double[] settle(ExplicitHmmDimer.Dimer d, double[] fA, double[] fB) {
        int fails = 0; double resid = Double.NaN; int it = 0;
        for (; it < SETTLE_MAX; it++) {
            double[] snap = flat(d);
            int st = ExplicitHmmDimer.solve(d, it, 7, false, fA, fB);
            if (st != 0) fails++;
            resid = maxMove(snap, d);
            if (resid < SETTLE_TOL) break;
        }
        return new double[]{ it, resid, fails };
    }

    static double[] flat(ExplicitHmmDimer.Dimer d) {
        double[] q = new double[3 * (d.NF + 1) + 4];
        int k = 0;
        for (int j = 0; j <= d.NF; j++) for (int c = 0; c < 3; c++) q[k++] = d.nd[j][c];
        q[k++] = d.hA.phi; q[k++] = d.hA.psi; q[k++] = d.hB.phi; q[k] = d.hB.psi;
        return q;
    }

    static double maxMove(double[] snap, ExplicitHmmDimer.Dimer d) {
        double[] now = flat(d); double m = 0;
        for (int i = 0; i < now.length; i++) m = Math.max(m, Math.abs(now[i] - snap[i]));
        return m;
    }

    /** Sweep the actin anchor(s) along `axis` and record the equilibrium response.
     *  mode: sym = both anchors displaced; asym = only A; asymB = only B (label-exchange control);
     *        anti = A and B displaced oppositely. */
    static List<Pt> sweep(String binding, String state, String axis, double sepNm, double[] lad, String mode) {
        List<Pt> out = new ArrayList<>();
        boolean two = binding.startsWith("two");
        for (double dNm : lad) {
            ExplicitHmmDimer.Dimer d = build(state);
            double kF8 = d.hA.kF8Code;
            double baseX = 0.5 * (d.hA.xF8[0] + d.hB.xF8[0]);
            double[] aA = { baseX + 0.5 * sepNm * 1e-3, d.hA.xF8[1], d.hA.xF8[2] };
            double[] aB = { baseX - 0.5 * sepNm * 1e-3, d.hB.xF8[1], d.hB.xF8[2] };
            if (!two) { aA = d.hA.xF8.clone(); }             // one-head-bound: only A is anchored

            double[] u = axis.equals("axial") ? new double[]{ 1, 0, 0 } : new double[]{ 0, 0, 1 };
            double sA = 1, sB = 1;
            switch (mode) {
                case "asym" -> { sA = 1; sB = 0; }
                case "asymB" -> { sA = 0; sB = 1; }
                case "anti" -> { sA = 1; sB = -1; }
                default -> { }
            }
            for (int c = 0; c < 3; c++) aA[c] += sA * dNm * 1e-3 * u[c];
            if (two) for (int c = 0; c < 3; c++) aB[c] += sB * dNm * 1e-3 * u[c];

            final double[] fAa = aA, fBb = aB; final boolean twoF = two;
            double[] st = settleWithSprings(d, fAa, fBb, twoF, kF8);

            Pt p = new Pt();
            p.binding = binding; p.state = state; p.axis = axis; p.mode = mode; p.sepNm = sepNm; p.dNm = dNm;
            double[] fA = spring(aA, d.hA.xF8, kF8);
            double[] fB = twoF ? spring(aB, d.hB.xF8, kF8) : new double[3];
            p.forceAPn = mag(fA) * 1e12; p.forceBPn = mag(fB) * 1e12;
            double[] tot = { fA[0] + fB[0], fA[1] + fB[1], fA[2] + fB[2] };
            p.forceTotPn = mag(tot) * 1e12;
            p.forceAxialPn = TwoBodyConverterMotor.dot(tot, u) * 1e12;
            p.uBend = ExplicitHmmDimer.bendEnergy(d, d.nd);
            double[] us = stretchSplit(d);
            p.uStretchShared = us[0]; p.uStretchBranch = us[1];
            p.uTotal = p.uBend + us[0] + us[1];
            p.forkDeg = ExplicitHmmDimer.forkAngleDeg(d);
            p.jointGapNm = ExplicitHmmDimer.maxJointGap(d);
            p.contourUm = ExplicitHmmDimer.contour(d);
            p.branchExtNm = ExplicitHmmDimer.maxBranchExtNm(d);
            p.iters = (int) st[0]; p.residUm = st[1]; p.solverFail = (int) st[2];
            out.add(p);
        }
        System.out.printf(Locale.US, "#   %-17s %-6s %-11s sep=%5.2f nm mode=%-5s : %3d pts%n",
                binding, state, axis, sepNm, mode, out.size());
        return out;
    }

    /** Relax with the spring force RECOMPUTED each iteration (the anchors are fixed, the heads move). */
    static double[] settleWithSprings(ExplicitHmmDimer.Dimer d, double[] aA, double[] aB, boolean two, double kF8) {
        int fails = 0; double resid = Double.NaN; int it = 0;
        for (; it < SETTLE_MAX; it++) {
            double[] snap = flat(d);
            double[] fA = spring(aA, d.hA.xF8, kF8);
            double[] fB = two ? spring(aB, d.hB.xF8, kF8) : new double[3];
            int st = ExplicitHmmDimer.solve(d, it, 7, false, fA, fB);
            if (st != 0) fails++;
            resid = maxMove(snap, d);
            if (resid < SETTLE_TOL) break;
        }
        return new double[]{ it, resid, fails };
    }

    static double[] spring(double[] actin, double[] xF8, double kF8) {
        return new double[]{ (actin[0] - xF8[0]) * kF8, (actin[1] - xF8[1]) * kF8, (actin[2] - xF8[2]) * kF8 };
    }
    static double mag(double[] v) { return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); }

    /** Stretch energy split into SHARED-S2 and BRANCH contributions (SI J). */
    static double[] stretchSplit(ExplicitHmmDimer.Dimer d) {
        double shared = 0, branch = 0;
        for (int si = 0; si < d.seg.length; si++) {
            double[] a = d.nd[d.seg[si][0]], b = d.nd[d.seg[si][1]];
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            double len_m = Math.sqrt(dx * dx + dy * dy + dz * dz) * 1e-6;
            double l0_m = d.segL0[si] * 1e-6;
            double e = 0.5 * d.segKs[si] * (len_m - l0_m) * (len_m - l0_m);
            if (d.segIsBranch[si]) branch += e; else shared += e;
        }
        return new double[]{ shared, branch };
    }

    static void write(List<Pt> all, String outDir, String rev, String buildId) {
        StringBuilder s = new StringBuilder(
            "binding,state,axis,mode,sep_nm,disp_nm,force_total_pn,force_A_pn,force_B_pn,force_axial_pn,"
          + "u_bend_J,u_stretch_shared_J,u_stretch_branch_J,u_total_J,u_total_kT,"
          + "fork_deg,joint_gap_nm,contour_um,branch_ext_nm,settle_iters,resid_um,solver_failures,code_rev,build_id\n");
        double kT = 4.141947e-21;
        for (Pt p : all)
            s.append(String.format(Locale.US,
                "%s,%s,%s,%s,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%d,%.3g,%d,%s,%s%n",
                p.binding, p.state, p.axis, p.mode, p.sepNm, p.dNm, p.forceTotPn, p.forceAPn, p.forceBPn,
                p.forceAxialPn, p.uBend, p.uStretchShared, p.uStretchBranch, p.uTotal, p.uTotal / kT,
                p.forkDeg, p.jointGapNm, p.contourUm, p.branchExtNm, p.iters, p.residUm, p.solverFail,
                rev, buildId));
        Path pth = Path.of(outDir, "force_extension_dimer.csv");
        try { Files.createDirectories(pth.getParent()); Files.writeString(pth, s.toString()); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private Sm6DimerForceExtensionHarness() { }
}
