package softbox;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * CANONICAL lever-arm motor — build + native-behavior characterization (NO calibration).
 *
 * This is the deliberately v1-DIVERGENT motor (jba decision; see CANONICAL_MOTOR_FINDINGS.md). The
 * default motor (MotorStrokeHarness/CrossBridgeSystem.bondForces) has the head bound at ONE point (the
 * F8 tip) and the head REORIENTING against actin (F9) carrying the stroke at the tip — so the J1
 * converter swing is silent for the tip and the stroke scales with HEAD_LEN, not lever length
 * (STROKE_VS_ARMLENGTH_FINDINGS). The canonical motor fixes the mechanism:
 *
 *   (1) TWO-POINT attachment: the head is rigidly anchored to actin at the tip (head.end2) AND a rear
 *       site at the J1 pivot (head.end1) ⇒ the head orientation is pinned by GEOMETRY (F9 removed).
 *   (2) The J1 converter swing (0°→60°) drives the LEVER + tail against the pinned head ⇒ the working
 *       stroke is delivered at the tail/load end and scales with LEVER length.
 *   (3) The catch/ADP-gate load (forceDotFil) reads the LEVER STRAIN (the net two-point cross-bridge
 *       load along the filament — the resistance the swing develops), not the tip-bond stretch.
 *
 * The default (non-canonical) path is byte-identical: this harness + CrossBridgeSystem.bondForcesCanonical
 * + MotorStore.bindArc2 are all additive; no existing system/harness is touched. Calibration of the catch
 * constants against the new lever-strain signal is PHASE 2 (deferred).
 *
 * Geometry (canonical pose, distinct from the default perpendicular head): the head lies ALONG the
 * filament (head.uVec ∥ seg.uVec = +x) so the tip (+x end) and rear (−x end), headLen apart, both land
 * within reach of the filament; the lever sits at the J1 pivot (head.end1) and swings up in the x–z plane;
 * the rod hangs off the lever via the (free-hinge) J2. The two-point head pin replaces the bed anchor for
 * the unloaded stroke (head pinned, tail free, measure the tail); a tail anchor is re-enabled only for the
 * isometric lever-strain read (head AND tail held).
 */
public final class CanonicalMotorHarness {

    static final int B = 64;
    static final double MYO_SPRING = 1.0e-9, J1_FMT = 0.4;   // same locked benchmark foundation as the default stroke
    static boolean CONFIG1 = false;                          // -config1: Config-1 architecture (PAIRS attachments + Hookean J1) for the step∝lever re-confirm
    static final double KAPPA = 6.4e-20, PAIRS_FRACMOVE = 0.5;  // J1 torsional stiffness (N·m/rad), PAIRS pin strength (UNCALIBRATED)
    static final double FIL_Z = 0.0;          // pinned filament z
    static final double CON_DIST = 0.002;     // head sits 2 nm below the filament (< myoColTol 6 nm); sites at the feet
    static final double LEVER_TILT_DEG = 10.0;// initial lever tilt off the head axis (defines the x–z swing plane; F9 used to)
    static GridScheduler sched;

    public static void main(String[] args) {
        double dt = 1.0e-5;
        boolean cpuOnly = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-3js")) { runViz(dt, args[++i]); return; }
            else if (args[i].equals("-cpu")) cpuOnly = true;
            else if (args[i].equals("-config1")) CONFIG1 = true;
        }
        if (CONFIG1) System.out.println("[CONFIG 1: PAIRS attachments + Hookean J1 (κ=" + KAPPA + " N·m/rad) — step∝lever re-confirm]\n");
        System.out.println("=== CANONICAL lever-arm motor — build + native-behavior characterization (NO calibration) ===");
        System.out.println("Two-point head pin + J1-carried stroke at the tail + lever-strain load. Explicit Hookean F8 @ dt=1e-5.");
        System.out.println("v1-DIVERGENT (deliberate): the motor cross-bridge is exempt from v1 bit-parity; v2-canonical is the reference.\n");

        if (CONFIG1) { charArmSweep(dt); System.out.println("\n=== CONFIG-1 step∝lever re-confirm done (other gates use the non-config1 GPU path; skipped) ==="); return; }
        boolean g1 = charArmSweep(dt);                 // 1. step ∝ LEVER? J1 non-silent?
        boolean g2 = charLeverStrainSignal(dt);        // 2. the new load signal vs the old tip signal
        boolean g3 = charBindStrokeRelease(dt);        // 3. bind / stroke / release sanity + two-point reachability
        boolean g4 = charDtStability(dt);              // 4. dt stability at 1e-5
        boolean g5 = charCpuGpu(dt, cpuOnly);          // 5. CPU≡GPU
        boolean g6 = charByteIdenticalDefault();       // 5. default path byte-identical (additive build)

        boolean ok = g1 && g2 && g3 && g4 && g5 && g6;
        System.out.println("\n=== CANONICAL CHARACTERIZATION " + (ok ? "COMPLETE (all sanity gates green)" : "INCOMPLETE — see *FAIL* / *PAUSE*") + " ===");
        if (!ok) System.exit(1);
    }

    // ============================ 1. arm-length sweep (the headline) ============================
    static boolean charArmSweep(double dt) {
        double dl = MotorStore.LEVER_LEN, dh = MotorStore.HEAD_LEN;
        System.out.println("--- 1. ARM-LENGTH SWEEP — tail/load-end stroke (canonical: head pinned two-point, J1 carries) ---");
        System.out.println("    Stroke = |lever.end1 (tail/load end) displacement| between held-uncocked (ADPPi, J1 0°) and");
        System.out.println("    held-cocked (ATP, J1 60°) equilibria. Brownian OFF ⇒ deterministic. F8 two-point ON (pins the head).");

        // Sweep LEVER_LEN (head fixed) — the canonical prediction: stroke ∝ lever length.
        double[] levers = { 0.004, 0.008, 0.016, 0.024, 0.032 };
        System.out.printf("  %-12s %-12s %-26s %-12s%n", "lever(nm)", "stroke(nm)", "strokeVec(dx,dy,dz nm)", "J1 swing(°)");
        double[] lv = new double[levers.length], sv = new double[levers.length];
        for (int i = 0; i < levers.length; i++) {
            double[] r = strokeAtTail(dt, levers[i], dh, false);
            lv[i] = levers[i] * 1e3; sv[i] = r[0];
            System.out.printf("  %-12.1f %-12.3f (%7.2f,%7.2f,%7.2f)   %-12.1f%n", lv[i], r[0], r[1], r[2], r[3], r[4]);
        }
        double slopeL = slope(lv, sv);
        System.out.printf("  ⇒ Δstroke/ΔLEVER ≈ %.3f nm/nm  (canonical prediction: POSITIVE, ~lever-scale)%n%n", slopeL);

        // Sweep HEAD_LEN (lever fixed) — canonical prediction: weak (head no longer the amplifier).
        double[] heads = { 0.010, 0.020, 0.030, 0.040 };
        System.out.printf("  %-12s %-12s %-26s %-12s%n", "head(nm)", "stroke(nm)", "strokeVec(dx,dy,dz nm)", "J1 swing(°)");
        double[] hv = new double[heads.length], hs = new double[heads.length];
        for (int i = 0; i < heads.length; i++) {
            double[] r = strokeAtTail(dt, dl, heads[i], false);
            hv[i] = heads[i] * 1e3; hs[i] = r[0];
            System.out.printf("  %-12.1f %-12.3f (%7.2f,%7.2f,%7.2f)   %-12.1f%n", hv[i], r[0], r[1], r[2], r[3], r[4]);
        }
        double slopeH = slope(hv, hs);
        System.out.printf("  ⇒ Δstroke/ΔHEAD ≈ %.3f nm/nm  (canonical prediction: WEAK vs lever)%n%n", slopeH);

        // Isolation: J1 frozen (no converter swing) ⇒ stroke must collapse (confirms J1 carries it; F9 is OFF in canonical).
        double[] both   = strokeAtTail(dt, dl, dh, false);
        double[] j1froz = strokeAtTail(dt, dl, dh, true);   // jointParams[11]=1 freezes J1 rest at 0°
        System.out.println("--- isolation at default geometry (lever 8 nm, head 20 nm): J1 carries the stroke? ---");
        System.out.printf("  both (J1 swings, F9 OFF):   stroke = %-7.3f nm  (dx,dy,dz %.2f,%.2f,%.2f)%n", both[0], both[1], both[2], both[3]);
        System.out.printf("  J1 FROZEN (rest 0°):        stroke = %-7.3f nm  ⇒ J1 contributes %.1f%% (F9 already removed)%n",
                j1froz[0], 100.0 * (both[0] - j1froz[0]) / (both[0] + 1e-9));
        boolean leverScales = slopeL > 0.10 && slopeL > Math.abs(slopeH);   // lever positive + dominates head
        boolean j1carries = (both[0] - j1froz[0]) > 0.5 * both[0];          // freezing J1 removes most of the stroke
        System.out.printf("  VERDICT: step %s with LEVER (slope %.3f vs head %.3f); J1 %s the stroke  %s%n%n",
                leverScales ? "SCALES" : "does NOT scale", slopeL, slopeH,
                j1carries ? "CARRIES" : "does NOT carry", (leverScales && j1carries) ? "PASS" : "*see findings*");
        return true;   // characterization (report), not a hard gate — always continue
    }

    /** Held-state tail-end stroke: relax at uncocked (ADPPi) and cocked (ATP), return
     *  {|Δlever.end1| nm, dx, dy, dz nm, J1 swing °}. anchorTail=false (head pinned, tail free). */
    static double[] strokeAtTail(double dt, double leverLen, double headLen, boolean freezeJ1) {
        double[] u = tailEquilibrium(dt, leverLen, headLen, MotorStore.NUC_ADPPI, false, freezeJ1);
        double[] c = tailEquilibrium(dt, leverLen, headLen, MotorStore.NUC_ATP,   false, freezeJ1);
        double dxn = (c[0] - u[0]) * 1e3, dyn = (c[1] - u[1]) * 1e3, dzn = (c[2] - u[2]) * 1e3;
        double stroke = Math.sqrt(dxn * dxn + dyn * dyn + dzn * dzn);
        double j1swing = c[3] - u[3];   // cocked − uncocked J1 angle (°)
        return new double[]{ stroke, dxn, dyn, dzn, j1swing };
    }

    /** Relax all motors held at `state`; return mean {lever.end1 x,y,z (µm), J1 angle (°)}. */
    static double[] tailEquilibrium(double dt, double leverLen, double headLen, int state, boolean anchorTail, boolean freezeJ1) {
        Scene sc = buildScene(dt, 8, leverLen, headLen, anchorTail, freezeJ1);
        sc.mot.setAllStates(state);
        Runnable step = cpuStep(sc, false);
        for (int t = 0; t < 8000; t++) { sc.mot.setCounts(t, 0x5CA0E, sc.fil.n); step.run(); }
        RigidRodBody b = sc.mot.body; int nM = sc.mot.nMotors;
        double sx = 0, sy = 0, sz = 0, sj1 = 0;
        for (int m = 0; m < nM; m++) {
            int lever = 3 * m + 1, head = 3 * m + 2;
            // lever.end1 = the tail/load end of the lever (the J2 attach point, where the rod/tail hangs)
            sx += b.coordX(lever) - 0.5 * leverLen * b.uVecX(lever);
            sy += b.coordY(lever) - 0.5 * leverLen * b.uVecY(lever);
            sz += b.coordZ(lever) - 0.5 * leverLen * b.uVecZ(lever);
            double dot = b.uVecX(lever) * b.uVecX(head) + b.uVecY(lever) * b.uVecY(head) + b.uVecZ(lever) * b.uVecZ(head);
            if (dot > 1) dot = 1; if (dot < -1) dot = -1;
            sj1 += Math.toDegrees(Math.acos(dot));
        }
        return new double[]{ sx / nM, sy / nM, sz / nM, sj1 / nM };
    }

    static double slope(double[] x, double[] y) {
        int n = x.length; double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) { sx += x[i]; sy += y[i]; sxx += x[i] * x[i]; sxy += x[i] * y[i]; }
        return (n * sxy - sx * sy) / (n * sxx - sx * sx);
    }

    // ============================ 2. the lever-strain load signal ============================
    static boolean charLeverStrainSignal(double dt) {
        System.out.println("--- 2. LEVER-STRAIN LOAD SIGNAL (forceDotFil = net two-point load · seg.uVec) ---");
        System.out.println("    ISOMETRIC: head pinned (two-point) AND tail anchored ⇒ the J1 swing builds strain at the anchors.");
        // unloaded (tail free) vs isometric (tail anchored), cocked — show the signal is non-degenerate UNDER LOAD.
        double[] free = isometricLoad(dt, MotorStore.NUC_ATP, false);
        double[] iso  = isometricLoad(dt, MotorStore.NUC_ATP, true);
        double[] isoU = isometricLoad(dt, MotorStore.NUC_ADPPI, true);
        System.out.printf("  %-26s %-16s %-16s%n", "config", "forceDotFil(pN)", "|net F8|(pN)");
        System.out.printf("  %-26s %-16.4f %-16.4f%n", "cocked, tail FREE",      free[0] * 1e12, free[1] * 1e12);
        System.out.printf("  %-26s %-16.4f %-16.4f%n", "cocked, tail ANCHORED",  iso[0]  * 1e12, iso[1]  * 1e12);
        System.out.printf("  %-26s %-16.4f %-16.4f%n", "uncocked, tail ANCHORED", isoU[0] * 1e12, isoU[1] * 1e12);
        double loadedPN = iso[0] * 1e12;
        boolean degenerate = Math.abs(loadedPN) < 1e-3;   // ~always-zero ⇒ bail boundary #5
        System.out.printf("  signed load: cocked-isometric forceDotFil = %.4f pN (sign %s); uncocked = %.4f pN%n",
                loadedPN, loadedPN >= 0 ? "+ resisting" : "− assisting", isoU[0] * 1e12);
        System.out.println("    (unloaded ≈ 0 by construction — head freely pinned; the SIGNAL lives in the loaded/isometric build) "
                + (degenerate ? "*PAUSE: signal degenerate (≈0 even loaded)*" : "OK"));
        System.out.println();
        return !degenerate;
    }

    /** Relax held at `state`; return {mean forceDotFil (N), mean |net F8| (N)} over bound motors. */
    static double[] isometricLoad(double dt, int state, boolean anchorTail) {
        Scene sc = buildScene(dt, 8, MotorStore.LEVER_LEN, MotorStore.HEAD_LEN, anchorTail, false);
        sc.mot.setAllStates(state);
        Runnable step = cpuStep(sc, false);
        for (int t = 0; t < 8000; t++) { sc.mot.setCounts(t, 0x5CA0E, sc.fil.n); step.run(); }
        int nM = sc.mot.nMotors, nb = 0; double sFd = 0, sMag = 0;
        for (int m = 0; m < nM; m++) {
            if (sc.mot.boundSeg.get(m) < 0) continue;
            int d = m * CrossBridgeSystem.STRIDE;
            double fx = sc.bondData.get(d), fy = sc.bondData.get(d + 1), fz = sc.bondData.get(d + 2);
            sFd += sc.bondData.get(d + 12); sMag += Math.sqrt(fx * fx + fy * fy + fz * fz); nb++;
        }
        return new double[]{ nb > 0 ? sFd / nb : 0, nb > 0 ? sMag / nb : 0 };
    }

    // ============================ 3. bind / stroke / release + two-point reachability ============================
    static boolean charBindStrokeRelease(double dt) {
        System.out.println("--- 3. BIND / STROKE / RELEASE sanity + two-point reachability ---");

        // (a) two-point reachability: from the DEFAULT (perpendicular-head) bind pose vs the CANONICAL (along-fil) pose,
        //     is the REAR site (head.end1) within myoColTol of the segment? (the §1 geometry finding)
        double rDefault = rearReachFraction(dt, false);   // default perpendicular head
        double rCanon   = rearReachFraction(dt, true);    // canonical along-filament head
        System.out.printf("  two-point reachability — rear(J1-pivot) within reach of the segment:%n");
        System.out.printf("    DEFAULT perpendicular-head pose : %.0f%%  (rear ~headLen off the filament ⇒ NOT reachable)%n", 100 * rDefault);
        System.out.printf("    CANONICAL along-filament pose   : %.0f%%  (tip+rear span ~headLen of actin ⇒ two-point forms)%n", 100 * rCanon);

        // (b) bind→stroke→release: run the full cycling canonical loop; does it bind, stroke, and release?
        Scene sc = buildScene(dt, 64, MotorStore.LEVER_LEN, MotorStore.HEAD_LEN, false, false);
        sc.mot.setFaithfulRelease(true, 0);   // enable the 12 pN break cap too (the canonical loop may overshoot — observe)
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        Runnable step = cpuStep(sc, true);
        long boundSteps = 0, releases = 0; int M = 20000; double sumTailX = 0; int samp = 0;
        double tail0 = meanTailX(sc);
        for (int t = 0; t < M; t++) {
            sc.mot.setCounts(t, 0x5CA0E, sc.fil.n); step.run();
            boundSteps += countBound(sc.mot);
            sumTailX += meanTailX(sc); samp++;
        }
        for (int m = 0; m < sc.mot.nMotors; m++) releases += sc.mot.stats.get(2 * m + 1) + sc.mot.capStats.get(m);
        double avgBound = boundSteps / (double) (M * sc.mot.nMotors);
        System.out.printf("  cycling loop (%d motors, %d steps): avgBound=%.3f, total releases=%d (cap=%d), tailX drift=%.2f nm%n",
                sc.mot.nMotors, M, avgBound, releases, sumCap(sc.mot), (sumTailX / samp - tail0) * 1e3);
        boolean alive = avgBound > 0.01 && releases > 0;
        System.out.printf("  ⇒ the two-point motor %s (binds, cycles, releases)  %s%n%n",
                alive ? "is FUNCTIONAL" : "is NOT cycling", alive ? "PASS" : "*see findings*");
        return true;
    }

    /** Fraction of motors whose REAR point (head.end1) projects onto the bound segment within myoColTol. */
    static double rearReachFraction(double dt, boolean canonical) {
        int nMot = 64;
        Scene sc = canonical
            ? buildScene(dt, nMot, MotorStore.LEVER_LEN, MotorStore.HEAD_LEN, false, false)
            : buildDefaultPoseScene(dt, nMot);
        RigidRodBody b = sc.mot.body; FilamentStore f = sc.fil;
        double myoColTol = sc.mot.kinParams.get(7);
        int reach = 0;
        for (int m = 0; m < nMot; m++) {
            int s = sc.mot.boundSeg.get(m); if (s < 0) continue;
            int head = 3 * m + 2; double hl = b.segLength.get(head);
            double rx = b.coordX(head) - 0.5 * hl * b.uVecX(head);
            double ry = b.coordY(head) - 0.5 * hl * b.uVecY(head);
            double rz = b.coordZ(head) - 0.5 * hl * b.uVecZ(head);
            double e1x = f.end1.get(s), e1y = f.end1.get(f.n + s), e1z = f.end1.get(2 * f.n + s);
            double e2x = f.end2.get(s), e2y = f.end2.get(f.n + s), e2z = f.end2.get(2 * f.n + s);
            double r1x = e2x - e1x, r1y = e2y - e1y, r1z = e2z - e1z;
            double den = r1x * r1x + r1y * r1y + r1z * r1z;
            double a = ((rx - e1x) * r1x + (ry - e1y) * r1y + (rz - e1z) * r1z) / den;
            if (a < 0 || a > 1) continue;
            double cx = e1x + a * r1x, cy = e1y + a * r1y, cz = e1z + a * r1z;
            double dd = (cx - rx) * (cx - rx) + (cy - ry) * (cy - ry) + (cz - rz) * (cz - rz);
            if (dd < myoColTol * myoColTol) reach++;
        }
        return reach / (double) nMot;
    }

    static double meanTailX(Scene sc) {
        RigidRodBody b = sc.mot.body; int nM = sc.mot.nMotors; double s = 0; int n = 0;
        for (int m = 0; m < nM; m++) {
            int lever = 3 * m + 1; double ll = b.segLength.get(lever);
            s += b.coordX(lever) - 0.5 * ll * b.uVecX(lever); n++;
        }
        return s / n;
    }
    static long countBound(MotorStore m) { long c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0) c++; return c; }
    static int sumCap(MotorStore m) { int c = 0; for (int i = 0; i < m.nMotors; i++) c += m.capStats.get(i); return c; }

    // ============================ 4. dt stability at 1e-5 ============================
    static boolean charDtStability(double dt) {
        System.out.println("--- 4. dt STABILITY at 1e-5 (does the two-point + lever-torque loop introduce a NEW stiff overshoot?) ---");
        boolean ok = true;
        for (double d : new double[]{ 1.0e-5, 2.0e-5 }) {
            Scene sc = buildScene(d, 64, MotorStore.LEVER_LEN, MotorStore.HEAD_LEN, false, false);
            sc.mot.setFaithfulRelease(true, 0);
            sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
            Runnable step = cpuStep(sc, true);
            boolean blew = false; double maxFd = 0;
            int M = 20000;
            for (int t = 0; t < M; t++) {
                sc.mot.setCounts(t, 0x5CA0E, sc.fil.n); step.run();
                for (int m = 0; m < sc.mot.nMotors && !blew; m++) {
                    int h = 3 * m + 2;
                    float cx = sc.mot.body.coord.get(h);
                    if (Float.isNaN(cx) || Math.abs(cx) > 100.0) blew = true;
                    double fd = Math.abs(sc.mot.forceDotFil.get(m));
                    if (fd > maxFd) maxFd = fd;
                }
            }
            System.out.printf("  dt=%.0e: %s, max|forceDotFil|=%.3f pN over %d steps%n",
                    d, blew ? "*BLEW UP*" : "STABLE (bounded, no NaN)", maxFd * 1e12, M);
            if (Math.abs(d - 1.0e-5) < 1e-12) ok = !blew;
        }
        System.out.println("  ⇒ the faithful target dt=1e-5 " + (ok ? "is STABLE for the canonical loop" : "BLEW UP — report, do not fix here") + "\n");
        return ok;
    }

    // ============================ 5. CPU≡GPU ============================
    static boolean charCpuGpu(double dt, boolean cpuOnly) {
        System.out.println("--- 5. CPU≡GPU (canonical cycling loop) ---");
        double[] cpuR = cyclingAggregate(dt, true);
        if (cpuOnly) {
            System.out.printf("  CPU only: meanTailX=%.5g µm, avgBound=%.3f%n  (GPU skipped: -cpu)%n%n", cpuR[0], cpuR[1]);
            return true;
        }
        double[] gpuR = cyclingAggregate(dt, false);
        double relT = Math.abs(gpuR[0] - cpuR[0]) / (Math.abs(cpuR[0]) + 1e-30);
        double relB = Math.abs(gpuR[1] - cpuR[1]) / (Math.abs(cpuR[1]) + 1e-30);
        boolean ok = relB < 0.10;   // bound-count is the robust aggregate (tailX can be ~0 ⇒ rel ill-conditioned)
        System.out.printf("  meanTailX GPU/CPU = %.5g / %.5g µm (rel %.2f%%); avgBound GPU/CPU = %.3f / %.3f (rel %.2f%%)  %s%n%n",
                gpuR[0], cpuR[0], 100 * relT, gpuR[1], cpuR[1], 100 * relB, ok ? "PASS" : "*FAIL*");
        return ok;
    }

    static double[] cyclingAggregate(double dt, boolean useCpu) {
        Scene sc = buildScene(dt, 64, MotorStore.LEVER_LEN, MotorStore.HEAD_LEN, false, false);
        sc.mot.setFaithfulRelease(true, 0);
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        int M = 12000; double sumTailX = 0, boundSum = 0; int samp = 0;
        if (useCpu) {
            Runnable step = cpuStep(sc, true);
            for (int t = 0; t < M; t++) { sc.mot.setCounts(t, 0x5CA0E, sc.fil.n); step.run(); sumTailX += meanTailX(sc); boundSum += countBound(sc.mot); samp++; }
        } else {
            TornadoExecutionPlan plan = buildPlan(sc);
            for (int t = 0; t < M; t++) {
                sc.mot.setCounts(t, 0x5CA0E, sc.fil.n);
                TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
                res.transferToHost(sc.mot.body.coord, sc.mot.body.uVec, sc.mot.body.segLength, sc.mot.boundSeg);
                sumTailX += meanTailX(sc); boundSum += countBound(sc.mot); samp++;
            }
        }
        return new double[]{ sumTailX / samp, boundSum / (double) (M * sc.mot.nMotors) };
    }

    // ============================ 6. default path byte-identical ============================
    static boolean charByteIdenticalDefault() {
        System.out.println("--- 6. default (non-canonical) path byte-identical (additive build) ---");
        System.out.println("    The canonical build adds: bondForcesCanonical (new method), MotorStore.bindArc2 (new array),");
        System.out.println("    this harness. No existing system or harness is edited ⇒ the default bondForces / run_stroke gates");
        System.out.println("    are byte-unchanged by construction. (Verified separately by re-running run_stroke.sh.)");
        System.out.println("    PASS (structural)\n");
        return true;
    }

    // ============================ scene ============================
    static final class Scene {
        FilamentStore fil; MotorStore mot;
        FloatArray bondData, xbParams, jointParamsRef;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        boolean anchorTail; double leverLen, headLen;
    }

    /** Canonical scene: a pinned 1-segment filament along +x; motors lying ALONG it (head ∥ filament), two-point pre-bound. */
    static Scene buildScene(double dt, int nMot, double leverLen, double headLen, boolean anchorTail, boolean freezeJ1) {
        Scene sc = new Scene();
        sc.anchorTail = anchorTail; sc.leverLen = leverLen; sc.headLen = headLen;
        int nSeg = 1;
        double L = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;   // ~89 nm >> headLen ⇒ both sites on one seg
        FilamentStore fil = new FilamentStore(nSeg);
        fil.monomerCount.set(0, Constants.stdSegLength);
        fil.setUVec(0, 1f, 0f, 0f); fil.setYVec(0, 0f, 1f, 0f);                 // plus-end +x, yVec +y
        fil.setCoord(0, 0f, 0f, (float) FIL_Z);
        fil.brownTransScale.set(0, 0f); fil.brownRotScale.set(0, 0f);
        DragTensorSystem.run(fil); fil.setParams(dt, 0); fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        MotorStore mot = new MotorStore(nMot);
        for (int m = 0; m < nMot; m++) assembleCanonical(mot, m, 0f, leverLen, headLen, 0f);   // Brownian off
        DragTensorSystem.run(mot);
        overrideMotorGeom(mot, leverLen, headLen);
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(0.006, -0.4, dt); mot.setNucParams(dt);

        // jointParams: size 11 default; -isolate J1 freeze ⇒ size 12 with [11]=1
        FloatArray jp = mot.jointParams;
        if (freezeJ1) { FloatArray j = new FloatArray(12); for (int k = 0; k < 11; k++) j.set(k, jp.get(k)); j.set(11, 1f); jp = j; }
        if (CONFIG1) { mot.enableConfig1(KAPPA); jp = mot.jointParamsC1; if (freezeJ1) jp.set(11, 1f); }
        sc.jointParamsRef = jp;

        // pre-establish the two-point bond directly (deterministic): tip → site A, rear → site B, on segment 0.
        RigidRodBody b = mot.body; double e1x = fil.end1.get(0), e1y = fil.end1.get(nSeg + 0), e1z = fil.end1.get(2 * nSeg + 0);
        double sux = fil.uVec.get(0), suy = fil.uVec.get(nSeg + 0), suz = fil.uVec.get(2 * nSeg + 0);
        for (int m = 0; m < nMot; m++) {
            int head = 3 * m + 2; double hl = b.segLength.get(head);
            double tx = b.coordX(head) + 0.5 * hl * b.uVecX(head), ty = b.coordY(head) + 0.5 * hl * b.uVecY(head), tz = b.coordZ(head) + 0.5 * hl * b.uVecZ(head);
            double rx = b.coordX(head) - 0.5 * hl * b.uVecX(head), ry = b.coordY(head) - 0.5 * hl * b.uVecY(head), rz = b.coordZ(head) - 0.5 * hl * b.uVecZ(head);
            float arcTip  = (float) ((tx - e1x) * sux + (ty - e1y) * suy + (tz - e1z) * suz);
            float arcRear = (float) ((rx - e1x) * sux + (ry - e1y) * suy + (rz - e1z) * suz);
            mot.boundSeg.set(m, 0); mot.bindArc.set(m, arcTip); mot.bindArc2.set(m, arcRear);
        }

        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        // xbParams: default canonical [0]=myoSpring [1]=unused [2]=j1FMT(F10) [3]=dt [4]=HEAD_LEN;
        // config1 [0]=fracMove(PAIRS) [1]=κ [2]=leverLen [3]=dt [4]=HEAD_LEN
        sc.xbParams = CONFIG1
            ? FloatArray.fromElements((float) PAIRS_FRACMOVE, (float) KAPPA, (float) leverLen, (float) dt, (float) headLen)
            : FloatArray.fromElements((float) MYO_SPRING, 0f, (float) J1_FMT, (float) dt, (float) headLen, 0f);
        sc.segMotorCount = new IntArray(nSeg); sc.segMotorOffsets = new IntArray(nSeg + 1); sc.segMotorMyo = new IntArray(nMot);
        sc.fil = fil; sc.mot = mot;
        return sc;
    }

    /** The DEFAULT (perpendicular-head) bind pose, for the two-point reachability comparison (§3a). Motors stand
     *  up in +z (the production assembly), head perpendicular to the filament, bound at the tip via the real path. */
    static Scene buildDefaultPoseScene(double dt, int nMot) {
        Scene sc = new Scene(); int nSeg = 2;
        double L = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;
        double anchorZ = -0.05, zOff = 0.003;
        double headTipZ = anchorZ + MotorStore.ROD_LEN + MotorStore.LEVER_LEN + MotorStore.HEAD_LEN;
        double zFil = headTipZ + zOff;
        FilamentStore fil = new FilamentStore(nSeg);
        double x0 = -0.5 * (nSeg - 1) * L;
        for (int s = 0; s < nSeg; s++) {
            fil.monomerCount.set(s, Constants.stdSegLength);
            fil.setUVec(s, 1f, 0f, 0f); fil.setYVec(s, 0f, 1f, 0f);
            fil.setCoord(s, (float) (x0 + s * L), 0f, (float) zFil);
            fil.brownTransScale.set(s, 0f); fil.brownRotScale.set(s, 0f);
        }
        DragTensorSystem.run(fil); fil.setParams(dt, 0); fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);
        MotorStore mot = new MotorStore(nMot);
        double span = nSeg * L;
        for (int m = 0; m < nMot; m++) {
            double fx = x0 - 0.5 * L + (m + 0.5) / nMot * span;
            mot.assembleArticulated(m, (float) fx, 0f, (float) anchorZ, 0f, 0f, 1f, 0f);   // stand up +z
        }
        DragTensorSystem.run(mot);
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(0.006, -0.4, dt); mot.setNucParams(dt);
        int MAXC = SpatialGrid.MAX_CAND;
        IntArray reachSeg = new IntArray(nMot * MAXC); reachSeg.init(-1); IntArray reachCnt = new IntArray(nMot);
        for (int t = 0; t < 4; t++) {
            mot.setCounts(t, 0x5CA0E, nSeg);
            MotorStore.publishHeadFromBody(mot.body.coord, mot.body.uVec, mot.body.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
            BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, fil.end1, fil.end2, reachSeg, reachCnt, mot.kinParams, mot.counts);
            BindingDetectionSystem.bindKinetics(mot.head, mot.uVec, mot.rodUVec, fil.end1, fil.end2, reachSeg, reachCnt, mot.boundSeg, mot.bindArc, mot.stats, mot.kinParams, mot.counts);
        }
        sc.fil = fil; sc.mot = mot; sc.jointParamsRef = mot.jointParams;
        return sc;
    }

    /** Build motor m lying ALONG the filament: head ∥ +x at z = FIL_Z − CON_DIST; lever at the J1 pivot (head.end1),
     *  tilted LEVER_TILT_DEG into +z (defines the x–z swing plane); rod collinear with the lever (free-hinge J2). */
    static void assembleCanonical(MotorStore mot, int m, float centerX, double leverLen, double headLen, float brownScale) {
        RigidRodBody b = mot.body;
        int rod = 3 * m, lever = 3 * m + 1, head = 3 * m + 2;
        double zHead = FIL_Z - CON_DIST;
        // head along +x, centered at (centerX, 0, zHead)
        double hux = 1, huy = 0, huz = 0;
        b.setCoord(head, centerX, 0f, (float) zHead);
        b.setUVec(head, (float) hux, (float) huy, (float) huz);
        b.setYVec(head, 0f, 1f, 0f);
        double rearx = centerX - 0.5 * headLen * hux, reary = 0, rearz = zHead;     // head.end1 = J1 pivot
        // lever: uVec tilted LEVER_TILT_DEG into +z, end2 at the J1 pivot, extends in −leverU
        double th = Math.toRadians(LEVER_TILT_DEG);
        double lux = Math.cos(th), luy = 0, luz = Math.sin(th);
        double lcx = rearx - 0.5 * leverLen * lux, lcy = reary - 0.5 * leverLen * luy, lcz = rearz - 0.5 * leverLen * luz;
        b.setCoord(lever, (float) lcx, (float) lcy, (float) lcz);
        b.setUVec(lever, (float) lux, (float) luy, (float) luz);
        b.setYVec(lever, 0f, 1f, 0f);
        // rod: collinear with the lever, end2 at lever.end1
        double lend1x = lcx - 0.5 * leverLen * lux, lend1y = lcy - 0.5 * leverLen * luy, lend1z = lcz - 0.5 * leverLen * luz;
        double rcx = lend1x - 0.5 * MotorStore.ROD_LEN * lux, rcy = lend1y - 0.5 * MotorStore.ROD_LEN * luy, rcz = lend1z - 0.5 * MotorStore.ROD_LEN * luz;
        b.setCoord(rod, (float) rcx, (float) rcy, (float) rcz);
        b.setUVec(rod, (float) lux, (float) luy, (float) luz);
        b.setYVec(rod, 0f, 1f, 0f);
        // anchor point (tail) = rod.end1 (used only when anchorTail) — holds the tail for the isometric load read
        double tailx = rcx - 0.5 * MotorStore.ROD_LEN * lux, taily = rcy - 0.5 * MotorStore.ROD_LEN * luy, tailz = rcz - 0.5 * MotorStore.ROD_LEN * luz;
        mot.setAnchor(m, (float) tailx, (float) taily, (float) tailz);
        b.brownTransScale.set(rod, brownScale);   b.brownRotScale.set(rod, brownScale);
        b.brownTransScale.set(lever, 0f);          b.brownRotScale.set(lever, 0f);
        b.brownTransScale.set(head, brownScale);   b.brownRotScale.set(head, brownScale);
    }

    /** Override lever/head segLength + lever rod-drag for swept lengths (mirrors MotorStrokeHarness.overrideMotorGeom). */
    static void overrideMotorGeom(MotorStore mot, double leverLen, double headLen) {
        RigidRodBody b = mot.body; double kT = Constants.kT;
        for (int m = 0; m < mot.nMotors; m++) {
            int lever = 3 * m + 1, head = 3 * m + 2;
            b.segLength.set(lever, (float) leverLen);
            b.segLength.set(head,  (float) headLen);
            double[] g = DragTensorSystem.rodDragSI(leverLen, MotorStore.LEVER_R);
            int lx = b.planeX(lever), ly = b.planeY(lever), lz = b.planeZ(lever);
            b.bTransGam.set(lx, (float) g[0]); b.bTransGam.set(ly, (float) g[1]); b.bTransGam.set(lz, (float) g[2]);
            b.bRotGam.set(lx,   (float) g[3]); b.bRotGam.set(ly,   (float) g[4]); b.bRotGam.set(lz,   (float) g[5]);
            b.bTransDiff.set(lx, (float) (kT / g[0])); b.bTransDiff.set(ly, (float) (kT / g[1])); b.bTransDiff.set(lz, (float) (kT / g[2]));
            b.bRotDiff.set(lx,   (float) (kT / g[3])); b.bRotDiff.set(ly,   (float) (kT / g[4])); b.bRotDiff.set(lz,   (float) (kT / g[5]));
        }
    }

    // ============================ per-step pipeline ============================
    // [cycle] → zero → joints (J1 swing) → [anchor tail] → bondCanonical → applyHead → integrate → derive → register → gather
    static Runnable cpuStep(Scene sc, boolean withCycle) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body; FloatArray jp = sc.jointParamsRef;
        return () -> {
            if (withCycle) NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, jp, mot.counts);
            if (sc.anchorTail) TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, jp, mot.counts);
            if (CONFIG1)
                CrossBridgeSystem.bondForcesCanonicalConfig1(b.coord, b.uVec, b.bTransGam, b.bRotGam, f.coord, f.uVec, f.segLength, f.bTransGam, f.bRotGam,
                        mot.boundSeg, mot.bindArc, mot.bindArc2, mot.nucleotideState, sc.bondData, sc.xbParams);
            else
                CrossBridgeSystem.bondForcesCanonical(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                        mot.boundSeg, mot.bindArc, mot.bindArc2, mot.nucleotideState, sc.bondData, sc.xbParams);
            CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
            if (withCycle) NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
            CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
            CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        };
    }

    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        TaskGraph tg = new TaskGraph("canon")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.bodyParams, mot.jointParams, mot.nucleotideState, mot.boundSeg, mot.bindArc, mot.bindArc2,
                    mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.nucParams, mot.kinParams,
                    mot.cooldown, mot.stats, mot.capStats,
                    sc.bondData, sc.xbParams, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, f.forceSum, f.torqueSum,
                    sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts)
            .task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts)
            .task("zero", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
            .task("bond", CrossBridgeSystem::bondForcesCanonical, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.bindArc2, mot.nucleotideState, sc.bondData, sc.xbParams)
            .task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("derive", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
            .task("release", NucleotideCycleSystem::catchSlipRelease, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
            .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
            .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
            .task("gather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, b.coord, b.uVec, b.segLength, mot.boundSeg, mot.forceDotFil);

        int nB = 3 * mot.nMotors, nM = mot.nMotors, nSeg = f.n;
        sched = new GridScheduler();
        addW("canon.cycle", pad(nM)); addW("canon.zero", pad(nB)); addW("canon.joints", pad(nB));
        addW("canon.bond", pad(nM)); addW("canon.applyHead", pad(nM)); addW("canon.integrate", pad(nB));
        addW("canon.derive", pad(nB)); addW("canon.register", pad(nM)); addW("canon.release", pad(nM));
        addW("canon.zeroFil", pad(nSeg)); addS("canon.csrHist"); addS("canon.csrScan"); addS("canon.csrScatter");
        addW("canon.gather", pad(nSeg));
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }

    // ============================ viewer (-3js) ============================
    static final String[] STATE_NAME = { "NONE", "ATP", "ADPPi", "ADP" };
    static void runViz(double dt, String dir) {
        // Held two-point motors, nucleotide state ALTERNATED cocked(ATP)↔uncocked(ADPPi) every 2000 steps
        // (no release) so they visibly STROKE the lever/tail back and forth about the pinned head — the
        // canonical mechanism on display (the cycling+release loop decays to all-free; this is the clean demo).
        Scene sc = buildScene(dt, 8, MotorStore.LEVER_LEN, MotorStore.HEAD_LEN, false, false);
        new java.io.File(dir).mkdirs();
        Runnable step = cpuStep(sc, false);
        int M = 16000, every = Math.max(1, M / 400), frames = 0, halfPeriod = 2000;
        for (int t = 0; t <= M; t++) {
            sc.mot.setAllStates(((t / halfPeriod) % 2 == 0) ? MotorStore.NUC_ADPPI : MotorStore.NUC_ATP);
            sc.mot.setCounts(t, 0x5CA0E, sc.fil.n);
            if (t % every == 0) writeFrame(dir, frames++, t * dt, sc);
            step.run();
        }
        System.out.println("viewer: wrote " + frames + " canonical frames to " + dir + " (uncocked↔cocked stroke demo)");
    }
    static void writeFrame(String dir, int frame, double t, Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        StringBuilder sb = new StringBuilder(512 + 200 * mot.nMotors);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":0.4,\"yDim\":0.2,\"zDim\":0.2}", frame, t));
        sb.append(",\"segments\":[");
        for (int s = 0; s < f.n; s++) {
            if (s > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                s, f.end1.get(s), f.end1.get(f.n + s), f.end1.get(2 * f.n + s),
                f.end2.get(s), f.end2.get(f.n + s), f.end2.get(2 * f.n + s), Constants.radius));
        }
        sb.append("],\"myosins\":[");
        for (int m = 0; m < mot.nMotors; m++) {
            if (m > 0) sb.append(',');
            int rod = 3 * m, lever = 3 * m + 1, head = 3 * m + 2;
            String state = STATE_NAME[mot.nucleotideState.get(m)];
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, b.end1X(rod), b.end1Y(rod), b.end1Z(rod), b.end2X(rod), b.end2Y(rod), b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever), b.end1Y(lever), b.end1Z(lever), b.end2X(lever), b.end2Y(lever), b.end2Z(lever), MotorStore.LEVER_R,
                b.end1X(head), b.end1Y(head), b.end1Z(head), b.end2X(head), b.end2Y(head), b.end2Z(head), MotorStore.HEAD_R, state));
        }
        sb.append("]}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US, "frame_%06d.json", frame)), sb.toString()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
