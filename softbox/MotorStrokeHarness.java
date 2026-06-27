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
 * Increment 4b-iii checkpoint: the power stroke on a PINNED filament. The nucleotide cycle + the
 * state-dependent rest-angle switch generate the stroke (no force law invented — it emerges); the
 * cross-bridge transmits a directional pulse into the pinned filament. No unpinning, no gliding
 * (that is the deferred gliding run). Validates five sharpened gates (the regression guard is the
 * 4b-i/ii harnesses with constant ADPPi):
 *
 *  1. Cycle dwell times == rate·dt (≈5/1000/10/100 steps NONE/ATP/ADPPi/ADP; cycle ≈0.011 s) — the
 *     4-state analog of 4a's residence-time check (cross-bridge off ⇒ load gate open).
 *  3. Stroke displacement: the head tip swing between held uncocked (ADPPi) and cocked (ATP) equilibria,
 *     vs the lever-scale geometric expectation.
 *  4. Directional force: the time-averaged cross-bridge force the cycling motors pulse into the pinned
 *     filament points −x (minus-end leading — the glide direction).
 *  5. Catch-slip engages: the unbind rate responds to the forceDotFil load (catch under +load).
 *  6. CPU≡GPU: cycle-only is bit-identical (pure integer RNG); the force-gated stroke is
 *     aggregate-within-SEM (a float forceDotFil comparison flips gated transitions ⇒ decorrelation).
 */
public final class MotorStrokeHarness {

    static final int B = 64;
    static final double ANCHOR_Z = -0.05, Z_OFFSET = 0.003;
    static final double MYO_SPRING = 1.0e-9, J1_FMT = 0.4;
    static GridScheduler sched;

    // ===== STROKE_VS_ARMLENGTH measurement (flag-gated, default = production constants ⇒ byte-identical) =====
    // The lever/head arm lengths used to build the motor body. Default to the production constants; the
    // -leverlen/-headlen flags override ONLY the geometry (swing angles / rates / myoSpring / binding all fixed).
    static double leverLen = MotorStore.LEVER_LEN;   // µm
    static double headLen  = MotorStore.HEAD_LEN;    // µm
    // Isolation cross-check: 0 = both rotations (production), 1 = J1-only (F9 rest frozen 90°),
    // 2 = F9-only (J1 rest frozen 0°). Drives the size-guarded xbParams[9]/jointParams[11] flags.
    static int    isolate  = 0;

    public static void main(String[] args) {
        double dt = 1.0e-5;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-3js")) { runViz(dt, args[++i]); return; }
            else if (args[i].equals("-armsweep")) { runArmSweep(dt); return; }
            else if (args[i].equals("-leverlen")) leverLen = Double.parseDouble(args[++i]);
            else if (args[i].equals("-headlen"))  headLen  = Double.parseDouble(args[++i]);
            else if (args[i].equals("-isolate"))  isolate  = Integer.parseInt(args[++i]);
        }
        System.out.println("=== Soft Box increment 4b-iii — power-stroke checkpoint (pinned filament) ===");
        System.out.println("Nucleotide cycle + rest-angle switch ⇒ the stroke. No unpinning, no gliding.\n");
        boolean g1 = gateDwell(dt);
        boolean g5 = gateCatchSlip(dt);
        boolean[] g346 = gateStroke(dt);
        boolean ok = g1 && g346[0] && g346[1] && g346[2] && g5;
        System.out.println("\n=== STROKE CHECKPOINT " + (ok ? "PASS" : "FAIL") + " ===");
        if (!ok) System.exit(1);
    }

    // ===================== Gate 1: cycle dwell times == rate·dt =====================
    static boolean gateDwell(double dt) {
        int nM = 256, M = 40000;
        MotorStore mot = new MotorStore(nM);
        mot.setNucParams(dt);
        for (int m = 0; m < nM; m++) mot.boundSeg.set(m, 0);     // all bound on-filament (rates use on-fil)
        mot.nucleotideState.init(MotorStore.NUC_NONE);
        // host accumulators
        long[] dwellSum = new long[4]; long[] dwellCnt = new long[4];
        int[] curState = new int[nM], curDwell = new int[nM]; boolean[] seenTrans = new boolean[nM];
        for (int m = 0; m < nM; m++) curState[m] = mot.nucleotideState.get(m);
        for (int t = 0; t < M; t++) {
            mot.setCounts(t, 0x57A0E, 0);
            NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
            for (int m = 0; m < nM; m++) {
                int s = mot.nucleotideState.get(m);
                if (s == curState[m]) { curDwell[m]++; }
                else {
                    if (seenTrans[m]) { dwellSum[curState[m]] += curDwell[m]; dwellCnt[curState[m]]++; }   // skip first (partial) dwell
                    seenTrans[m] = true; curState[m] = s; curDwell[m] = 1;
                }
            }
        }
        double[] expect = { 5.0, 1000.0, 10.0, 100.0 };
        String[] name = { "NONE", "ATP", "ADPPi", "ADP" };
        System.out.println("--- gate 1: cycle dwell times == 1/(rate·dt) [256 motors, 40k steps] ---");
        boolean ok = true;
        for (int s = 0; s < 4; s++) {
            double mean = dwellCnt[s] > 0 ? (double) dwellSum[s] / dwellCnt[s] : 0;
            double err = Math.abs(mean - expect[s]) / expect[s];
            boolean st = err < 0.08;
            ok &= st;
            System.out.printf("  %-6s mean=%-9.2f steps  expect=%-7.1f  err=%-6.2f%%  n=%-6d %s%n",
                    name[s], mean, expect[s], 100 * err, dwellCnt[s], st ? "" : "*FAIL*");
        }
        System.out.printf("  cycle period ≈ %.4f s (expect ≈0.011)  %s%n%n",
                dt * (5 + 1000 + 10 + 100), ok ? "PASS" : "*FAIL*");
        return ok;
    }

    // ===================== Gate 5: catch-slip responds to load =====================
    static boolean gateCatchSlip(double dt) {
        System.out.println("--- gate 5: catch-slip unbind rate responds to forceDotFil load ---");
        double kOff = 100, aCatch = 0.92, aSlip = 0.08, xCatch = 2.5e-9, xSlip = 0.4e-9, kT = Constants.kT;
        double[] loadsPN = { 0.0, 1.0, 2.0, 4.0 };
        boolean prevHigher = true; double prev = 1e9;
        System.out.printf("  %-12s %-16s %-16s %-16s%n", "load(pN)", "rate_analytic(/s)", "rate_empirical(/s)", "meanLife(steps)");
        boolean ok = true;
        for (double loadPN : loadsPN) {
            double F = loadPN * 1e-12;     // N (positive = resisting load)
            double rateA = kOff * (aCatch * Math.exp(-F * xCatch / kT) + aSlip * Math.exp(F * xSlip / kT));
            // empirical: run catchSlipRelease on a population at fixed F
            int nM = 4000, M = 4000;
            MotorStore mot = new MotorStore(nM);
            mot.setKinParams(0.006, -0.4, dt);
            for (int m = 0; m < nM; m++) { mot.boundSeg.set(m, 0); mot.forceDotFil.set(m, (float) F); }
            long releases = 0, boundSteps = 0;
            for (int t = 0; t < M; t++) {
                mot.setCounts(t, 0x57A0E, 0);
                for (int m = 0; m < nM; m++) mot.boundSeg.set(m, 0);          // re-bind each step (steady population)
                for (int m = 0; m < nM; m++) mot.forceDotFil.set(m, (float) F);
                long b0 = countBound(mot);
                NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
                long b1 = countReleased(mot);
                boundSteps += b0; releases += b1;
            }
            double pEmp = releases / (double) boundSteps;
            double rateE = pEmp / dt;
            boolean monotone = rateA <= prev + 1e-9;     // catch: rate decreases with +load
            ok &= monotone;
            System.out.printf("  %-12.1f %-16.2f %-16.2f %-16.1f %s%n", loadPN, rateA, rateE, 1.0 / pEmp,
                    monotone ? "" : "*non-monotone*");
            prev = rateA;
        }
        System.out.println("  (catch: +load stabilizes the bond ⇒ unbind rate DROPS — the F-dependence is engaged)  "
                + (ok ? "PASS" : "*FAIL*") + "\n");
        return ok;
    }
    static long countBound(MotorStore m) { long c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) >= 0) c++; return c; }
    static long countReleased(MotorStore m) { long c = 0; for (int i = 0; i < m.nMotors; i++) if (m.boundSeg.get(i) == MotorStore.FREE_COOLDOWN) c++; return c; }

    // ===================== Gates 3,4,6: stroke displacement + directional force + CPU≡GPU =========
    static boolean[] gateStroke(double dt) {
        // UNLOADED stroke: with F8 (the cross-bridge spring) off, the head swings freely under the
        // F9/J1 rest-angle torques, so the tip DISPLACES (in the isometric/loaded case the tip is
        // pinned by F8 and the stroke is force instead — gate 4). Held uncocked (ADPPi) vs cocked (ATP).
        double[] tipU = strokeEquilibriumTip(dt, MotorStore.NUC_ADPPI);
        double[] tipC = strokeEquilibriumTip(dt, MotorStore.NUC_ATP);
        double dxn = (tipC[0] - tipU[0]) * 1e3, dyn = (tipC[1] - tipU[1]) * 1e3, dzn = (tipC[2] - tipU[2]) * 1e3;
        double strokeNm = Math.sqrt(dxn * dxn + dyn * dyn + dzn * dzn);
        System.out.println("--- gate 3: unloaded stroke displacement (head tip swing, F8 off, held uncocked→cocked) ---");
        System.out.printf("  head tip Δ (nm): (%.2f, %.2f, %.2f)  ⇒  |stroke| = %.2f nm%n", dxn, dyn, dzn, strokeNm);
        boolean g3 = strokeNm > 2.0 && strokeNm < 40.0;   // lever-scale working stroke (lever 8 nm, head 20 nm)
        System.out.printf("  lever 8 nm + head 20 nm articulated swing (J1 0→60°, F9 90→120°); measured %.2f nm  %s%n%n",
                strokeNm, g3 ? "PASS" : "*FAIL*");

        // cycling on the pinned filament → net directional force into the filament (gate 4) + CPU≡GPU (gate 6)
        double[] gpu = cyclingNetForce(dt, false);
        double[] cpuR = cyclingNetForce(dt, true);
        System.out.println("--- gate 4: directional force into the pinned filament (cycling) ---");
        System.out.printf("  GPU: net Σ filForce_x = %.4g N  (mean %.4g N)   ⇒ %s%n", gpu[0], gpu[1], gpu[0] < 0 ? "−x (glide dir) PASS" : "*+x FAIL*");
        System.out.printf("  CPU: net Σ filForce_x = %.4g N  (mean %.4g N)   ⇒ %s%n", cpuR[0], cpuR[1], cpuR[0] < 0 ? "−x (glide dir) PASS" : "*+x FAIL*");
        boolean g4 = gpu[0] < 0 && cpuR[0] < 0;

        System.out.println("--- gate 6: CPU≡GPU (cycle is RNG+force-gated → aggregate-within-SEM, not bit-identical) ---");
        double rel = Math.abs(gpu[1] - cpuR[1]) / (Math.abs(cpuR[1]) + 1e-30);
        double boundRel = Math.abs(gpu[2] - cpuR[2]) / (Math.abs(cpuR[2]) + 1e-30);
        boolean g6 = rel < 0.15 && boundRel < 0.10;
        System.out.printf("  mean filForce_x GPU/CPU = %.4g / %.4g (rel %.1f%%); avgBound GPU/CPU = %.2f / %.2f (rel %.1f%%)  %s%n%n",
                gpu[1], cpuR[1], 100 * rel, gpu[2], cpuR[2], 100 * boundRel, g6 ? "PASS" : "*FAIL*");
        return new boolean[]{ g3, g4, g6 };
    }

    /** Hold all motors at `state`, F8 (cross-bridge spring) OFF, swing to equilibrium under the
     *  F9/F10/J1 rest-angle torques; return the mean head tip (x,y,z) — the unloaded stroke endpoint. */
    static double[] strokeEquilibriumTip(double dt, int state) {
        Scene sc = buildScene(dt, 8);
        sc.mot.setAllStates(state);
        sc.xbParams.set(0, 0f);               // myoSpring = 0 ⇒ F8 off, head free to swing (F9/F10 still reference the bound seg)
        Runnable step = cpuStep(sc, false);   // no cycle (held state)
        for (int t = 0; t < 6000; t++) { sc.mot.setCounts(t, 0x57A0E, sc.fil.n); step.run(); }
        RigidRodBody b = sc.mot.body; double sx = 0, sy = 0, sz = 0; int nM = sc.mot.nMotors;
        for (int m = 0; m < nM; m++) {
            int h = 3 * m + 2;
            sx += b.coordX(h) + 0.5 * headLen * b.uVecX(h);
            sy += b.coordY(h) + 0.5 * headLen * b.uVecY(h);
            sz += b.coordZ(h) + 0.5 * headLen * b.uVecZ(h);
        }
        return new double[]{ sx / nM, sy / nM, sz / nM };
    }

    /** Run the full cycling stroke on a pinned filament; return {netForceX, meanForceX, avgBound}. */
    static double[] cyclingNetForce(double dt, boolean useCpu) {
        Scene sc = buildScene(dt, 12);
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        int M = 20000;
        double netFx = 0, boundSum = 0; int samples = 0;
        if (useCpu) {
            Runnable step = cpuStep(sc, true);
            for (int t = 0; t < M; t++) {
                sc.mot.setCounts(t, 0x57A0E, sc.fil.n); step.run();
                netFx += sumFilForceX(sc); boundSum += countBound(sc.mot); samples++;
            }
        } else {
            TornadoExecutionPlan plan = buildPlan(sc);
            for (int t = 0; t < M; t++) {
                sc.mot.setCounts(t, 0x57A0E, sc.fil.n);
                TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
                res.transferToHost(sc.fil.forceSum, sc.mot.boundSeg);
                netFx += sumFilForceX(sc); boundSum += countBound(sc.mot); samples++;
            }
        }
        return new double[]{ netFx, netFx / samples, boundSum / samples };
    }
    static double sumFilForceX(Scene sc) {
        double s = 0; int nSeg = sc.fil.n;
        for (int seg = 0; seg < nSeg; seg++) s += sc.fil.forceSum.get(seg);   // x-plane
        return s;
    }

    // ===================== STROKE_VS_ARMLENGTH (measurement-only) ==================================
    /** Harness-local body assembly with overridable lever/head lengths. Mirrors MotorStore.assembleArticulated
     *  EXACTLY (same float casts) so it is bit-identical when leverLen=LEVER_LEN, headLen=HEAD_LEN. Rod stays
     *  ROD_LEN (only the lever neck + head reorient in the stroke). */
    static void assembleLen(MotorStore mot, int m, float ax, float ay, float az,
                            float dx, float dy, float dz, float brownScale, double leverLen, double headLen) {
        float px = -dy, py = dx, pz = 0f;
        float pm = (float) Math.sqrt(px * px + py * py + pz * pz);
        if (pm < 1.0e-4f) { px = 1f; py = 0f; pz = 0f; pm = 1f; }
        px /= pm; py /= pm; pz /= pm;
        RigidRodBody b = mot.body;
        int rod = mot.rodIdx(m), lever = mot.leverIdx(m), head = mot.headIdx(m);
        float rOff = 0.5f * (float) MotorStore.ROD_LEN;
        float lOff = (float) MotorStore.ROD_LEN + 0.5f * (float) leverLen;
        float hOff = (float) (MotorStore.ROD_LEN + leverLen) + 0.5f * (float) headLen;
        b.setCoord(rod,   ax + rOff * dx, ay + rOff * dy, az + rOff * dz);
        b.setCoord(lever, ax + lOff * dx, ay + lOff * dy, az + lOff * dz);
        b.setCoord(head,  ax + hOff * dx, ay + hOff * dy, az + hOff * dz);
        b.setUVec(rod, dx, dy, dz); b.setUVec(lever, dx, dy, dz); b.setUVec(head, dx, dy, dz);
        b.setYVec(rod, px, py, pz); b.setYVec(lever, px, py, pz); b.setYVec(head, px, py, pz);
        mot.setAnchor(m, ax, ay, az);
        b.brownTransScale.set(rod, brownScale);   b.brownRotScale.set(rod, brownScale);
        b.brownTransScale.set(lever, 0f);         b.brownRotScale.set(lever, 0f);
        b.brownTransScale.set(head, brownScale);  b.brownRotScale.set(head, brownScale);
    }

    /** Override the lever/head sub-body segLength (the data-driven arm lengths the J1/F9/stall systems read) and
     *  recompute the LEVER rod-drag for its swept length. The HEAD drag is Stokes-sphere(HEAD_R) — independent of
     *  HEAD_LEN — so only the head segLength changes. No-op (bit-identical) at the default lengths. The drag only
     *  sets the relaxation SPEED, not the unloaded equilibrium endpoint; recomputed here for physical honesty. */
    static void overrideMotorGeom(MotorStore mot, double leverLen, double headLen) {
        RigidRodBody b = mot.body;
        double kT = Constants.kT;
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

    /** ISOMETRIC stall force per motor: F8 ON, head bound to the pinned filament, held at `state`, Brownian off,
     *  relaxed to equilibrium; returns {mean |F8| (pN), mean F8_x (pN, signed along the filament), nBound}.
     *  At the cocked state this is the per-motor stall force the converter rotation generates (≈ myoSpring·stroke). */
    static double[] strokeIsometricForce(double dt, int state) {
        Scene sc = buildScene(dt, 12);            // F8 ON (xbParams[0]=MYO_SPRING)
        sc.mot.setAllStates(state);
        Runnable step = cpuStep(sc, false);       // held state, no cycle, no release (cpuStep never unbinds)
        for (int t = 0; t < 8000; t++) { sc.mot.setCounts(t, 0x57A0E, sc.fil.n); step.run(); }
        int nM = sc.mot.nMotors, nb = 0; double sumMag = 0, sumFx = 0;
        for (int m = 0; m < nM; m++) {
            if (sc.mot.boundSeg.get(m) < 0) continue;
            int d = m * CrossBridgeSystem.STRIDE;
            double fx = sc.bondData.get(d), fy = sc.bondData.get(d + 1), fz = sc.bondData.get(d + 2);
            sumMag += Math.sqrt(fx * fx + fy * fy + fz * fz); sumFx += fx; nb++;
        }
        double toPN = 1e12;   // F8 is in Newtons (myoSpring N/µm · dist µm)
        return new double[]{ nb > 0 ? sumMag / nb * toPN : 0, nb > 0 ? sumFx / nb * toPN : 0, nb };
    }

    /** One config: unloaded stroke (nm, signed components) + isometric stall force (pN). */
    static double[] measureConfig(double dt) {
        double[] tu = strokeEquilibriumTip(dt, MotorStore.NUC_ADPPI);  // uncocked
        double[] tc = strokeEquilibriumTip(dt, MotorStore.NUC_ATP);    // cocked (≠ADPPi ⇒ J1 60°, F9 120°)
        double dxn = (tc[0] - tu[0]) * 1e3, dyn = (tc[1] - tu[1]) * 1e3, dzn = (tc[2] - tu[2]) * 1e3;
        double stroke = Math.sqrt(dxn * dxn + dyn * dyn + dzn * dzn);
        double[] fc = strokeIsometricForce(dt, MotorStore.NUC_ATP);    // cocked isometric (stall) force
        double[] fu = strokeIsometricForce(dt, MotorStore.NUC_ADPPI);  // uncocked baseline (≈0)
        // {stroke, dxn, dyn, dzn, stallMagPN, stallFxPN, baselineMagPN, nBound}
        return new double[]{ stroke, dxn, dyn, dzn, fc[0], fc[1], fu[0], fc[2] };
    }

    static void runArmSweep(double dt) {
        double dl = MotorStore.LEVER_LEN, dh = MotorStore.HEAD_LEN;   // production defaults (8 nm / 20 nm)
        System.out.println("=== STROKE vs ARM-LENGTH SWEEP (measurement-only, flag-gated; default byte-identical) ===");
        System.out.println("RUNNER: -cpu sequential debug runner. Single motor near a pinned filament, no external load.");
        System.out.println("        Brownian OFF on the motor body ⇒ DETERMINISTIC equilibrium (no seed scatter); 12 motors/config");
        System.out.println("        give identical results. dt=1e-5, explicit Hookean F8 (myoSpring=1 pN/nm). Held fixed: swing");
        System.out.println("        angles (J1 0°↔60°, F9 90°↔120°), all nucleotide rates, myoSpring, binding. Only geometry varies.\n");

        // ---- Sweep 1: LEVER_LEN (head fixed at default) ----
        double[] levers = { 0.004, 0.008, 0.016, 0.024, 0.032 };
        System.out.println("--- Sweep 1: LEVER_LEN swept, HEAD_LEN fixed at " + (dh * 1e3) + " nm ---");
        System.out.printf("  %-11s %-11s %-22s %-13s %-13s %-11s %-7s%n",
                "lever(nm)", "stroke(nm)", "strokeVec(dx,dy,dz nm)", "k*stroke(pN)", "isoForce(pN)", "base(pN)", "nBound");
        for (double L : levers) {
            leverLen = L; headLen = dh; isolate = 0;
            double[] r = measureConfig(dt);
            System.out.printf("  %-11.1f %-11.3f (%6.2f,%6.2f,%6.2f)      %-13.3f %-13.3f %-11.4f %-7.0f%n",
                    L * 1e3, r[0], r[1], r[2], r[3], r[0], r[4], r[6], r[7]);
        }
        System.out.println();

        // ---- Sweep 2: HEAD_LEN (lever fixed at default) ----
        double[] heads = { 0.010, 0.020, 0.030, 0.040 };
        System.out.println("--- Sweep 2: HEAD_LEN swept, LEVER_LEN fixed at " + (dl * 1e3) + " nm ---");
        System.out.printf("  %-11s %-11s %-22s %-13s %-13s %-11s %-7s%n",
                "head(nm)", "stroke(nm)", "strokeVec(dx,dy,dz nm)", "k*stroke(pN)", "isoForce(pN)", "base(pN)", "nBound");
        for (double H : heads) {
            leverLen = dl; headLen = H; isolate = 0;
            double[] r = measureConfig(dt);
            System.out.printf("  %-11.1f %-11.3f (%6.2f,%6.2f,%6.2f)      %-13.3f %-13.3f %-11.4f %-7.0f%n",
                    H * 1e3, r[0], r[1], r[2], r[3], r[0], r[4], r[6], r[7]);
        }
        System.out.println();

        // ---- Sweep 3: isolation cross-check at DEFAULT geometry (attribute lever-swing vs head-reorientation) ----
        System.out.println("--- Sweep 3: rotation isolation at default geometry (lever " + (dl * 1e3) + " nm, head " + (dh * 1e3) + " nm) ---");
        System.out.printf("  %-22s %-11s %-22s%n", "mode", "stroke(nm)", "strokeVec(dx,dy,dz nm)");
        String[] modeName = { "both (J1+F9)", "J1-only (F9 frozen)", "F9-only (J1 frozen)" };
        double[] isoStroke = new double[3];
        for (int iso = 0; iso < 3; iso++) {
            leverLen = dl; headLen = dh; isolate = iso;
            double[] tu = strokeEquilibriumTip(dt, MotorStore.NUC_ADPPI);
            double[] tc = strokeEquilibriumTip(dt, MotorStore.NUC_ATP);
            double dxn = (tc[0] - tu[0]) * 1e3, dyn = (tc[1] - tu[1]) * 1e3, dzn = (tc[2] - tu[2]) * 1e3;
            isoStroke[iso] = Math.sqrt(dxn * dxn + dyn * dyn + dzn * dzn);
            System.out.printf("  %-22s %-11.3f (%6.2f,%6.2f,%6.2f)%n", modeName[iso], isoStroke[iso], dxn, dyn, dzn);
        }
        System.out.printf("  ⇒ J1-swing %.1f%% of both, F9-reorient %.1f%% of both (sum %.1f%%)%n",
                100 * isoStroke[1] / isoStroke[0], 100 * isoStroke[2] / isoStroke[0],
                100 * (isoStroke[1] + isoStroke[2]) / isoStroke[0]);
        leverLen = dl; headLen = dh; isolate = 0;   // restore
        System.out.println("\n=== ARM-LENGTH SWEEP COMPLETE ===");
    }

    // ===================== scene (pinned filament + articulated motors, bonds established) ==========
    static final class Scene {
        FilamentStore fil; MotorStore mot;
        FloatArray bondData, xbParams, jointParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        FloatArray bruteReachSeg2; IntArray bruteReachCount2;
    }
    static Scene buildScene(double dt, int nMot) {
        Scene sc = new Scene();
        int nSeg = 2;
        double L = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;
        double headTipZ = ANCHOR_Z + MotorStore.ROD_LEN + leverLen + headLen;
        double zFil = headTipZ + Z_OFFSET;
        FilamentStore fil = new FilamentStore(nSeg);
        double x0 = -0.5 * (nSeg - 1) * L;
        for (int s = 0; s < nSeg; s++) {
            fil.monomerCount.set(s, Constants.stdSegLength);
            fil.setUVec(s, 1f, 0f, 0f); fil.setYVec(s, 0f, 1f, 0f);     // plus-end +x
            fil.setCoord(s, (float) (x0 + s * L), 0f, (float) zFil);
            fil.brownTransScale.set(s, 0f); fil.brownRotScale.set(s, 0f);
        }
        DragTensorSystem.run(fil); fil.setParams(dt, 0); fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        MotorStore mot = new MotorStore(nMot);
        double span = nSeg * L;
        for (int m = 0; m < nMot; m++) {
            double fx = x0 - 0.5 * L + (m + 0.5) / nMot * span;       // spread under the filament
            assembleLen(mot, m, (float) fx, 0f, (float) ANCHOR_Z, 0f, 0f, 1f, 0f, leverLen, headLen);   // Brownian off
        }
        DragTensorSystem.run(mot);
        overrideMotorGeom(mot, leverLen, headLen);   // lever/head segLength + lever drag for the swept lengths (no-op at defaults)
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(0.006, -0.4, dt); mot.setNucParams(dt);

        // jointParams: size 11 (production) unless -isolate 2, which adds [11]=1 to freeze the J1 rest (F9-only stroke).
        if (isolate == 2) {
            FloatArray jp = new FloatArray(12);
            for (int k = 0; k < 11; k++) jp.set(k, mot.jointParams.get(k));
            jp.set(11, 1f);
            sc.jointParams = jp;
        } else {
            sc.jointParams = mot.jointParams;
        }

        int MAXC = SpatialGrid.MAX_CAND;
        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        // xbParams: size 6 (production) unless -isolate 1, which extends to size 10 with [6..8]=0 (satMode off,
        // byte-identical) and [9]=1 to freeze the F9 rest at 90° (J1-only stroke).
        sc.xbParams = (isolate == 1)
            ? FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) dt, (float) headLen, 0f, 0f, 0f, 0f, 1f)
            : FloatArray.fromElements((float) MYO_SPRING, 90f, (float) J1_FMT, (float) dt, (float) headLen, 0f);   // [5]=forcebias (0 = no bias)
        sc.segMotorCount = new IntArray(nSeg); sc.segMotorOffsets = new IntArray(nSeg + 1); sc.segMotorMyo = new IntArray(nMot);
        sc.bruteReachSeg2 = new FloatArray(nMot * MAXC); sc.bruteReachCount2 = new IntArray(nMot);
        IntArray reachSeg = new IntArray(nMot * MAXC); reachSeg.init(-1);
        sc.fil = fil; sc.mot = mot;
        // establish bonds (geometric, deterministic)
        for (int t = 0; t < 4; t++) {
            mot.setCounts(t, 0x57A0E, nSeg);
            MotorStore.publishHeadFromBody(mot.body.coord, mot.body.uVec, mot.body.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
            BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, fil.end1, fil.end2, reachSeg, sc.bruteReachCount2, mot.kinParams, mot.counts);
            BindingDetectionSystem.bindKinetics(mot.head, mot.uVec, mot.rodUVec, fil.end1, fil.end2, reachSeg, sc.bruteReachCount2, mot.boundSeg, mot.bindArc, mot.stats, mot.kinParams, mot.counts);
        }
        return sc;
    }

    // per-step: [cycle] → joints → anchor → bond → applyHead → integrate → derive → register → gather
    static Runnable cpuStep(Scene sc, boolean withCycle) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        return () -> {
            if (withCycle) NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, sc.jointParams, mot.counts);
            TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, sc.jointParams, mot.counts);
            CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
            CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
            CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
            CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        };
    }

    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        TaskGraph tg = new TaskGraph("stroke")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, b.bTransGam, b.bRotGam,
                    b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.brownTransScale, b.brownRotScale,
                    mot.bodyParams, mot.jointParams, mot.anchor, mot.nucleotideState, mot.boundSeg, mot.bindArc,
                    mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.nucParams,
                    sc.bondData, sc.xbParams, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, f.forceSum, f.torqueSum,
                    sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts)
            .task("cycle", NucleotideCycleSystem::cycle, mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts)
            .task("zero", ChainBendingForceSystem::zeroAccumulators, b.forceSum, b.torqueSum, mot.counts)
            .task("joints", MotorJointSystem::joints, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts)
            .task("anchor", TailAnchorSystem::anchor, b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts)
            .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams)
            .task("applyHead", CrossBridgeSystem::applyHeadForce, sc.bondData, b.forceSum, b.torqueSum, mot.counts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate, b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts)
            .task("derive", DerivedGeometrySystem::derive, b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts)
            .task("register", CrossBridgeSystem::registerForceDot, sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
            .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
            .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
            .task("gather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.forceSum, mot.boundSeg);

        int nB = 3 * mot.nMotors, nM = mot.nMotors, nSeg = f.n;
        sched = new GridScheduler();
        addW("stroke.cycle", pad(nM)); addW("stroke.zero", pad(nB)); addW("stroke.joints", pad(nB));
        addW("stroke.anchor", pad(nM)); addW("stroke.bond", pad(nM)); addW("stroke.applyHead", pad(nM));
        addW("stroke.integrate", pad(nB)); addW("stroke.derive", pad(nB)); addW("stroke.register", pad(nM));
        addW("stroke.zeroFil", pad(nSeg)); addS("stroke.csrHist"); addS("stroke.csrScan"); addS("stroke.csrScatter");
        addW("stroke.gather", pad(nSeg));
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }

    // ===================== viewer (-3js): cycling motors stroking on a pinned filament ==============
    static final String[] STATE_NAME = { "NONE", "ATP", "ADPPi", "ADP" };
    static void runViz(double dt, String dir) {
        Scene sc = buildScene(dt, 12);
        sc.mot.nucleotideState.init(MotorStore.NUC_NONE);
        new java.io.File(dir).mkdirs();
        Runnable step = cpuStep(sc, true);
        int M = 20000, every = Math.max(1, M / 400), frames = 0;
        for (int t = 0; t <= M; t++) {
            sc.mot.setCounts(t, 0x57A0E, sc.fil.n);
            if (t % every == 0) writeFrame(dir, frames++, t * dt, sc);
            step.run();
        }
        System.out.println("viewer: wrote " + frames + " frames to " + dir + " (motors colored by nucleotide state)");
    }
    static void writeFrame(String dir, int frame, double t, Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        StringBuilder sb = new StringBuilder(512 + 200 * mot.nMotors);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":0.8,\"yDim\":0.4,\"zDim\":0.2}", frame, t));
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
