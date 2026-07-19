package softbox;

import static softbox.TwoBodyConverterMotor.sub;
import static softbox.TwoBodyConverterMotor.dot;
import static softbox.TwoBodyConverterMotor.scl;
import static softbox.TwoBodyConverterMotor.geomC;

import softbox.ExplicitHmmDimer.Dimer;
import softbox.TwoBodyConverterMotor.Cmot;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Characterisation of the explicit HMM-like dimer ({@link ExplicitHmmDimer}) — BEFORE any retuning.
 * CPU-only (the explicit two-body arc is CPU-only, like EXPLICIT_S2_L40). Default-off architecture;
 * TwoBodyConverterMotor / MotorModel are untouched (verified: this + ExplicitHmmDimer are new files).
 *
 * Gates (see docs/matsoa/EXPLICIT_HMM_DIMER_DESIGN.md §"Characterisation gates"):
 *   1  isometric rest hold      — forked beam holds shape (bounded joint gap, near-zero head force at rest)
 *   2  stroke / force gen        — both heads stroke; per-head working stroke + peak force (single-head ballpark)
 *   3  single-head equivalence   — a dimer head vs the isolated single-head slice, same machinery/schedule
 *   4  shared-tail coupling       — stroke ONLY head A; head B responds through the shared beam (vs a stiff control)
 *   5  force balance + determinism — node-force+reaction sum ≈0 at rest; CPU bit-reproducible
 *
 *   ./scripts/run_hmm_dimer.sh                 # all gates
 */
public final class ExplicitHmmDimerHarness {

    static final double DT = 2.5e-6;
    static final int    SETTLE = 800;          // relaxation steps (matches the single-head MAXIT)
    static final double TOL = 3e-7;            // µm max-move convergence
    static final int    T = 400, t1 = 80, t2 = 160, t3 = 240, t4 = 320;   // relax→stroke→hold→detach→recoil
    static final double PRE  = TwoBodyConverterMotor.PRESTROKE_THETAS;
    static final double POST = TwoBodyConverterMotor.ADP_THETAS;
    static final String OUT = "RUN_LOGS/explicit_hmm_dimer";

    public static void main(String[] args) throws IOException {
        int Ms = 3, Ma = 1, Mb = 1; double splay = 25.0;
        System.out.println("=== EXPLICIT HMM-LIKE DIMER — characterisation (CPU) ===");
        System.out.printf(Locale.US, "topology: shared Ms=%d (%.0f nm) + branches Ma=Mb=%d (%.0f nm) ⇒ per-head path %.0f nm; splay %.0f°%n",
                Ms, Ms * ExplicitHmmDimer.L0_NM, Ma, Ma * ExplicitHmmDimer.L0_NM, (Ms + Ma) * ExplicitHmmDimer.L0_NM, splay);
        System.out.printf(Locale.US, "material (shared=branch, NOT doubled): EA=%.3e N, EI=%.3e N·m², ks=%.4f N/m, kb=%.3e N·m, dt=%.1e%n",
                ExplicitHmmDimer.EA_SI, ExplicitHmmDimer.EI_SI, EA_over_l0(Ms, Ma, Mb, splay).ks, EA_over_l0(Ms, Ma, Mb, splay).kb, DT);

        StringBuilder log = new StringBuilder("# Explicit HMM-like dimer — characterisation findings\n\n");
        boolean ok = true;
        ok &= gate1_restHold(Ms, Ma, Mb, splay, log);
        ok &= gate2_stroke(Ms, Ma, Mb, splay, log);
        ok &= gate3_singleHeadEquiv(Ms, Ma, Mb, splay, log);
        ok &= gate4_coupling(Ms, Ma, Mb, splay, log);
        ok &= gate5_balanceDeterminism(Ms, Ma, Mb, splay, log);

        Files.createDirectories(Path.of(OUT));
        Path rp = Path.of(OUT, "EXPLICIT_HMM_DIMER.md");
        Files.writeString(rp, log.toString());
        System.out.println("\n# report: " + rp.toAbsolutePath());
        System.out.println(ok ? "=== EXPLICIT HMM DIMER: PASS ===" : "=== EXPLICIT HMM DIMER: REVIEW ===");
    }

    static Dimer EA_over_l0(int Ms, int Ma, int Mb, double splay) { return ExplicitHmmDimer.build(Ms, Ma, Mb, splay, DT); }

    // ---- schedules (identical to the single-head slice) ----
    static double thetaSched(int t) { if (t < t1) return PRE; if (t >= t2) return POST; return PRE + (POST - PRE) * (t - t1) / (double) (t2 - t1); }
    static double coupleSched(int t) { if (t < t3) return 1.0; if (t >= t4) return 0.0; return 1.0 - (t - t3) / (double) (t4 - t3); }

    /** Relax the dimer to its UNLOADED pre-stroke equilibrium (θ_s = PRE, F8h = 0 — exactly as the single-head
     *  slice), then define each fixed actin site = the free-equilibrium xF8 so the cross-bridge spring is
     *  identically zero at rest (zero rest force; the stroke then stretches it). */
    static void settle(Dimer d, PrintStream logNodes) {
        d.hA.thetaS = PRE; d.hB.thetaS = PRE;
        for (int it = 0; it < SETTLE; it++) {
            double[] snap = flat(d);
            ExplicitHmmDimer.solve(d, it, 101, false, new double[3], new double[3]);   // unloaded
            if (maxMove(snap, d) < TOL) break;
        }
        d.actinA = d.hA.xF8.clone(); d.actinB = d.hB.xF8.clone();   // spring rest = free-equilibrium xF8 ⇒ zero rest force
    }

    static double[] springForce(Cmot h, double[] actin, double coupl) { return scl(sub(actin, h.xF8), coupl * h.kF8Code); }

    // ================= Gate 1: isometric rest hold =================
    static boolean gate1_restHold(int Ms, int Ma, int Mb, double splay, StringBuilder log) {
        Dimer d = ExplicitHmmDimer.build(Ms, Ma, Mb, splay, DT);
        settle(d, null);
        double gap0 = ExplicitHmmDimer.maxJointGap(d), contour0 = ExplicitHmmDimer.contour(d);
        double fA0 = fmag(springForce(d.hA, d.actinA, 1)), fB0 = fmag(springForce(d.hB, d.actinB, 1));
        // hold isometrically another SETTLE steps, watch the joint gap + head forces
        double gapMax = gap0;
        for (int it = 0; it < SETTLE; it++) {
            ExplicitHmmDimer.solve(d, it, 101, false, springForce(d.hA, d.actinA, 1), springForce(d.hB, d.actinB, 1));
            gapMax = Math.max(gapMax, ExplicitHmmDimer.maxJointGap(d));
        }
        double gap1 = ExplicitHmmDimer.maxJointGap(d), contour1 = ExplicitHmmDimer.contour(d);
        double fA1 = fmag(springForce(d.hA, d.actinA, 1)), fB1 = fmag(springForce(d.hB, d.actinB, 1));
        boolean bounded = gapMax < 2.0 && Math.abs(gap1 - gap0) < 0.05;                 // < 2 nm, non-growing
        boolean quiet = fA1 < 0.5 && fB1 < 0.5;                                          // relaxed ⇒ < 0.5 pN head force
        boolean contourHeld = Math.abs(contour1 - contour0) * 1e3 < 0.5;                 // < 0.5 nm contour drift
        boolean pass = bounded && quiet && contourHeld;
        log.append(String.format(Locale.US,
                "## Gate 1 — isometric rest hold: %s\n- max joint gap %.4f nm (start %.4f, hold %.4f), Δ %.4f nm\n"
              + "- head force at rest: A %.4f pN, B %.4f pN (relaxed)\n- contour drift %.4f nm\n\n",
                pass ? "PASS" : "FAIL", gapMax, gap0, gap1, gap1 - gap0, fA1, fB1, (contour1 - contour0) * 1e3));
        System.out.println("  gate1 rest-hold " + (pass ? "PASS" : "FAIL") + String.format(Locale.US, " (gap %.3f nm, fRest A=%.3f B=%.3f pN)", gapMax, fA1, fB1));
        return pass;
    }

    // ================= Gate 2: stroke / force generation =================
    static boolean gate2_stroke(int Ms, int Ma, int Mb, double splay, StringBuilder log) {
        Dimer d = ExplicitHmmDimer.build(Ms, Ma, Mb, splay, DT);
        settle(d, null);
        double[] xH0A = d.hA.xH.clone(), xH0B = d.hB.xH.clone(); double[] bhat = d.hA.bhat.clone();
        double peakFA = 0, peakFB = 0, strokeA = 0, strokeB = 0; double forkDx = 0; double[] fork0 = d.nd[d.Ms].clone();
        for (int t = 0; t < T; t++) {
            double th = thetaSched(t), coupl = coupleSched(t);
            d.hA.thetaS = th; d.hB.thetaS = th;
            double[] f8A = springForce(d.hA, d.actinA, coupl), f8B = springForce(d.hB, d.actinB, coupl);
            ExplicitHmmDimer.solve(d, t, 101, false, f8A, f8B);
            double fa = fmag(f8A), fb = fmag(f8B);
            if (t >= t1 && t <= t3) { peakFA = Math.max(peakFA, fa); peakFB = Math.max(peakFB, fb); }
            if (t == t2) { strokeA = axial(d.hA.xH, xH0A, bhat); strokeB = axial(d.hB.xH, xH0B, bhat); forkDx = axial(d.nd[d.Ms], fork0, bhat); }
        }
        // realistic myosin working stroke ~5–10 nm, peak force a few pN (single-head slice: ~−7.7 nm, ~7.9 pN)
        boolean strokeOk = Math.abs(strokeA) > 2.0 && Math.abs(strokeA) < 20.0 && sameSign(strokeA, strokeB) && Math.abs(strokeB) > 2.0;
        boolean forceOk = peakFA > 1.0 && peakFA < 20.0 && peakFB > 1.0;
        boolean symmetric = rel(Math.abs(strokeA), Math.abs(strokeB)) < 0.15 && rel(peakFA, peakFB) < 0.15;
        boolean pass = strokeOk && forceOk && symmetric;
        log.append(String.format(Locale.US,
                "## Gate 2 — stroke / force generation: %s\n- head A stroke %.2f nm, peak force %.2f pN\n"
              + "- head B stroke %.2f nm, peak force %.2f pN\n- shared fork axial motion %.2f nm (both heads pull the shared tail)\n"
              + "- symmetry: |strokeA−strokeB| rel %.3f, |peakA−peakB| rel %.3f\n\n",
                pass ? "PASS" : "FAIL", strokeA, peakFA, strokeB, peakFB, forkDx, rel(Math.abs(strokeA), Math.abs(strokeB)), rel(peakFA, peakFB)));
        System.out.println("  gate2 stroke " + (pass ? "PASS" : "FAIL") + String.format(Locale.US, " (A: %.2f nm / %.2f pN, B: %.2f nm / %.2f pN, fork %.2f nm)", strokeA, peakFA, strokeB, peakFB, forkDx));
        return pass;
    }

    // ================= Gate 3: single-head equivalence =================
    static boolean gate3_singleHeadEquiv(int Ms, int Ma, int Mb, double splay, StringBuilder log) {
        // dimer head A stroke
        Dimer d = ExplicitHmmDimer.build(Ms, Ma, Mb, splay, DT); settle(d, null);
        double[] xH0 = d.hA.xH.clone(); double[] bhat = d.hA.bhat.clone(); double dPeak = 0, dStroke = 0;
        for (int t = 0; t < T; t++) { double th = thetaSched(t), c = coupleSched(t); d.hA.thetaS = th; d.hB.thetaS = th;
            double[] f8A = springForce(d.hA, d.actinA, c); ExplicitHmmDimer.solve(d, t, 101, false, f8A, springForce(d.hB, d.actinB, c));
            if (t >= t1 && t <= t3) dPeak = Math.max(dPeak, fmag(f8A)); if (t == t2) dStroke = axial(d.hA.xH, xH0, bhat); }
        // isolated single head, same schedule/machinery
        double[] sh = singleHeadStroke();
        double sStroke = sh[0], sPeak = sh[1];
        boolean strokeClose = rel(Math.abs(dStroke), Math.abs(sStroke)) < 0.30;   // ballpark (shared tail is stiffer at the fork)
        boolean forceClose = rel(dPeak, sPeak) < 0.30;
        boolean pass = strokeClose && forceClose && sameSign(dStroke, sStroke);
        log.append(String.format(Locale.US,
                "## Gate 3 — single-head equivalence: %s\n- dimer head A: stroke %.2f nm, peak %.2f pN\n"
              + "- isolated single head: stroke %.2f nm, peak %.2f pN\n- rel diff: stroke %.3f, force %.3f (shared tail is stiffer at the fork ⇒ modest offset expected)\n\n",
                pass ? "PASS" : "FAIL", dStroke, dPeak, sStroke, sPeak, rel(Math.abs(dStroke), Math.abs(sStroke)), rel(dPeak, sPeak)));
        System.out.println("  gate3 single-head-equiv " + (pass ? "PASS" : "FAIL") + String.format(Locale.US, " (dimer %.2f nm/%.2f pN vs single %.2f nm/%.2f pN)", dStroke, dPeak, sStroke, sPeak));
        return pass;
    }

    /** Isolated single-head explicit-S2 stroke via the production s2Solve (same schedule + fixed-actin spring). */
    static double[] singleHeadStroke() {
        Cmot cm = TwoBodyConverterMotor.buildS2(40.0, 0.0, true, DT, 1.0, 1.0);
        cm.thetaS = PRE;
        for (int it = 0; it < SETTLE; it++) { cm.A = cm.g4Node[cm.g4M].clone(); cm.P = cm.A; geomC(cm);
            TwoBodyConverterMotor.s2Solve(cm, it, 101, false, new double[3]); }
        cm.A = cm.g4Node[cm.g4M].clone(); cm.P = cm.A; geomC(cm);
        double[] xActin = cm.xF8.clone(), xH0 = cm.xH.clone(), bhat = cm.bhat.clone(); double kF8 = cm.kF8Code;
        double peak = 0, stroke = 0;
        for (int t = 0; t < T; t++) {
            double th = thetaSched(t), c = coupleSched(t);
            cm.A = cm.g4Node[cm.g4M].clone(); cm.P = cm.A; geomC(cm);
            double[] f8 = scl(sub(xActin, cm.xF8), c * kF8); cm.thetaS = th;
            TwoBodyConverterMotor.s2Solve(cm, t, 101, false, f8);
            if (t >= t1 && t <= t3) peak = Math.max(peak, fmag(f8)); if (t == t2) stroke = axial(cm.xH, xH0, bhat);
        }
        return new double[]{ stroke, peak };
    }

    // ================= Gate 4: shared-tail coupling =================
    static boolean gate4_coupling(int Ms, int Ma, int Mb, double splay, StringBuilder log) {
        // Baseline: NOBODY strokes (both converters held at PRE) — head B force + pivot should stay ~0 (rest).
        double[] base = strokeAonly(Ms, Ma, Mb, splay, false);   // {indForceB(pN), pBdisp(nm), forkDisp(nm)}
        // Signal: ONLY head A strokes — B's response is transmitted purely through the shared S2 beam.
        double[] sig = strokeAonly(Ms, Ma, Mb, splay, true);
        double dForceB = sig[0] - base[0], dPivotB = sig[1] - base[1], dFork = sig[2] - base[2];
        boolean transmits = dForceB > 0.05 || dPivotB > 0.01;    // B measurably responds to A's stroke through the shared tail
        boolean beamMediated = dFork > 1e-4;                     // the shared fork actually moves under A's load (the transmission path)
        boolean pass = transmits && beamMediated;
        log.append(String.format(Locale.US,
                "## Gate 4 — shared-tail coupling: %s\n- stroke ONLY head A ⇒ head B induced force %.4f pN (Δ vs no-stroke baseline), pivot Δ %.4f nm\n"
              + "- shared fork axial motion under A's load %.4f nm — the transmission path (A's stroke → branch A → fork → shared S2 → branch B → head B)\n"
              + "- (no-stroke baseline: B force %.4f pN, pivot %.4f nm)\n\n",
                pass ? "PASS" : "FAIL", dForceB, dPivotB, dFork, base[0], base[1]));
        System.out.println("  gate4 coupling " + (pass ? "PASS" : "FAIL") + String.format(Locale.US, " (B induced Δ%.4f pN, pivot Δ%.4f nm, fork %.4f nm)", dForceB, dPivotB, dFork));
        return pass;
    }
    /** Run the schedule with head A stroking (aStroke) or held at PRE; head B always held at PRE. Returns
     *  {head-B induced force pN, head-B pivot displacement nm, shared-fork displacement nm} at end of stroke. */
    static double[] strokeAonly(int Ms, int Ma, int Mb, double splay, boolean aStroke) {
        Dimer d = ExplicitHmmDimer.build(Ms, Ma, Mb, splay, DT); settle(d, null);
        double[] pB0 = d.nd[d.pB].clone(), fork0 = d.nd[d.Ms].clone(); double[] bhat = d.hA.bhat.clone();
        double fB = 0, pBd = 0, fkd = 0;
        for (int t = 0; t < T; t++) {
            double th = thetaSched(t), c = coupleSched(t);
            d.hA.thetaS = aStroke ? th : PRE; d.hB.thetaS = PRE;
            ExplicitHmmDimer.solve(d, t, 101, false, springForce(d.hA, d.actinA, c), springForce(d.hB, d.actinB, c));
            if (t == t2) { fB = fmag(springForce(d.hB, d.actinB, c)); pBd = dist(d.nd[d.pB], pB0) * 1e3; fkd = Math.abs(axial(d.nd[d.Ms], fork0, bhat)); }
        }
        return new double[]{ fB, pBd, fkd };
    }

    // ================= Gate 5: force balance + determinism =================
    static boolean gate5_balanceDeterminism(int Ms, int Ma, int Mb, double splay, StringBuilder log) {
        Dimer d = ExplicitHmmDimer.build(Ms, Ma, Mb, splay, DT); settle(d, null);
        // at rest with relaxed converters, the sum of all node internal forces + the two head reactions ≈ the
        // emergence reaction (Newton's 3rd law): |Σ node forces| is the emergence reaction magnitude.
        double[][] Fn = ExplicitHmmDimer.nodeForces(d, d.nd);
        double[] sum = { 0, 0, 0 }; for (int j = 0; j <= d.NF; j++) for (int k = 0; k < 3; k++) sum[k] += Fn[j][k];
        double net = Math.sqrt(dot(sum, sum)) * 1e12;   // pN — internal forces are self-balancing ⇒ ≈0
        // determinism: two independent settle+stroke runs must be bit-identical (no RNG in this path)
        double s1 = detRun(Ms, Ma, Mb, splay), s2 = detRun(Ms, Ma, Mb, splay);
        boolean balanced = net < 1e-3;               // internal stretch/bend/floor forces sum to ~0 (pN)
        boolean deterministic = s1 == s2;
        boolean pass = balanced && deterministic;
        log.append(String.format(Locale.US,
                "## Gate 5 — force balance + determinism: %s\n- Σ internal node forces (self-balance) = %.3e pN\n- CPU determinism: run1 %.12f nm == run2 %.12f nm ⇒ %s\n\n",
                pass ? "PASS" : "FAIL", net, s1, s2, deterministic ? "bit-identical" : "DIVERGED"));
        System.out.println("  gate5 balance+determinism " + (pass ? "PASS" : "FAIL") + String.format(Locale.US, " (ΣF=%.2e pN, det=%b)", net, deterministic));
        return pass;
    }
    static double detRun(int Ms, int Ma, int Mb, double splay) {
        Dimer d = ExplicitHmmDimer.build(Ms, Ma, Mb, splay, DT); settle(d, null);
        double[] xH0 = d.hA.xH.clone(); double[] bhat = d.hA.bhat.clone(); double stroke = 0;
        for (int t = 0; t < T; t++) { double th = thetaSched(t), c = coupleSched(t); d.hA.thetaS = th; d.hB.thetaS = th;
            ExplicitHmmDimer.solve(d, t, 101, false, springForce(d.hA, d.actinA, c), springForce(d.hB, d.actinB, c));
            if (t == t2) stroke = axial(d.hA.xH, xH0, bhat); }
        return stroke;
    }

    // ---- small helpers ----
    static double fmag(double[] f) { return Math.sqrt(dot(f, f)) * 1e12; }                    // N → pN
    static double axial(double[] x, double[] x0, double[] bhat) { return dot(sub(x, x0), bhat) * 1e3; }   // nm
    static double dist(double[] a, double[] b) { double[] d = sub(a, b); return Math.sqrt(dot(d, d)); }
    static double rel(double a, double b) { return Math.abs(a - b) / (Math.max(Math.abs(a), Math.abs(b)) + 1e-30); }
    static boolean sameSign(double a, double b) { return a * b >= 0; }
    static double[] flat(Dimer d) { double[] f = new double[3 * (d.NF + 1) + 4]; int i = 0;
        for (int j = 0; j <= d.NF; j++) for (int k = 0; k < 3; k++) f[i++] = d.nd[j][k];
        f[i++] = d.hA.phi; f[i++] = d.hA.psi; f[i++] = d.hB.phi; f[i++] = d.hB.psi; return f; }
    static double maxMove(double[] prev, Dimer d) { double[] cur = flat(d); double mx = 0; for (int i = 0; i < cur.length; i++) mx = Math.max(mx, Math.abs(cur[i] - prev[i])); return mx; }
    private ExplicitHmmDimerHarness() {}
}
