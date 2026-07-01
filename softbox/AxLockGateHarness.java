package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * AXIAL SWING-LOCK single-motor GATE (PHASE-2, 2026-06-30). Validates the §9.4c fix — retargeting F10
 * from the segment's incidental yVec (â=ŷ_seg, the BoA bug) to the swing-plane normal
 * ŝ = normalize(n̂bed × û_seg), head-only — using the SAME production kernels the dense GPU mat runs
 * (MotorJointSystem.joints + TailAnchorSystem.anchor + CrossBridgeSystem.bondForces with F9 frozen at
 * 90° and F10 lock off/on), so the gate tests the actual fix, not a re-implementation.
 *
 * The sphere-head three-body motor: rod →(J2)→ neck/lever →(J1 = the 0°→60° stroke)→ HEAD (held ⊥ to
 * actin by the frozen-F9 90° perp-maintainer). The head TIP is F8-anchored to a FIXED material site on
 * a HELD filament; as J1 cocks, the lever swings about the pinned tip and its rear (rod-side end) sweeps.
 *
 * The SWING PLANE is set by the head's ROLL (F10). Lock OFF: F10 drives head.yVec → seg.yVec, so when
 * the filament is rolled about its axis (as it is under Brownian in the mat) the swing plane tilts
 * transverse (the wander that makes the mat's net glide ≪ its path speed). Lock ON: F10 drives
 * head.yVec → ŝ = normalize(n̂bed×seg.uVec) (depends ONLY on the filament AXIS + bed normal, not the
 * incidental roll), so the swing stays in the axial (û_seg–n̂bed) plane at any filament roll.
 *
 * GATE: over a filament-roll sweep φ, the lever-rear sweep axial fraction |Δx|/√(Δx²+Δy²):
 *   lock OFF degrades with φ (→ ~0.1 at large roll); lock ON stays ≈ 1.0 at every φ. Sweep sense
 *   (rear Δx sign) reports barbed-ward (+x rear sweep ⇒ pointed-leading −x glide). Brownian OFF.
 *
 * New file, flag-gated harness ⇒ production byte-identical; BoA-v1ref untouched.
 */
public final class AxLockGateHarness {
    static final double ANCHOR_Z = -0.05;                 // fixedMyosinZValue (bed plane; n̂bed = +Z)
    static final double MYO_SPRING = 1.0e-9;              // F8 cross-bridge spring (N/µm) = 1 pN/nm
    static double DT = 1.0e-5;
    static int SETTLE = 12000;                            // steps to reach the (Brownian-off) mechanical equilibrium each state
    static double HEAD_SPHERE_R = 0.5 * MotorStore.HEAD_LEN;   // render the catalytic domain as a sphere
    static boolean DIRECTED = true;      // deterministic polarity-directed converter (default). -j1swing ⇒ old degenerate J1.
    static boolean HFSWING = false;      // -hfswing: head-frame converter (directedSwingHeadFrame, no f̂) instead of the f̂-referenced directedSwing.
    static boolean ROLLSIGN = false;     // -rollsign: stereospecific +ŝ roll lock (no-op in this gate — the pose already sets head.yVec=+ŝ).
    static FloatArray SWING;             // [k, dt, θ_uncocked, θ_cocked] for CrossBridgeSystem.directedSwing

    public static void main(String[] args) {
        String viz = null; boolean lock = true; double vphi = 0; boolean filFree = true;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-dt")) DT = Double.parseDouble(args[++i]);
            else if (args[i].equals("-settle")) SETTLE = Integer.parseInt(args[++i]);
            else if (args[i].equals("-3js")) viz = args[++i];               // viewer dump of the single-motor stroke
            else if (args[i].equals("-nolock")) lock = false;               // show the un-locked (F10→seg.yVec) swing
            else if (args[i].equals("-phi")) vphi = Double.parseDouble(args[++i]);   // filament roll (deg)
            else if (args[i].equals("-held")) filFree = false;              // hold the filament (isolate the stroke mechanics)
            else if (args[i].equals("-j1swing")) DIRECTED = false;          // use the old (degenerate, direction-unstable) J1 converter
            else if (args[i].equals("-hfswing")) HFSWING = true;            // head-frame converter recast (no f̂ in the swing law)
            else if (args[i].equals("-rollsign")) { HFSWING = true; ROLLSIGN = true; }   // stereospecific +ŝ roll (no-op here; pose already +ŝ)
        }
        SWING = FloatArray.fromElements(0.4f, (float) DT, 0f, 60f);
        if (viz != null) { dumpViewer(viz, lock, vphi, filFree); return; }
        for (String a : args) if (a.equals("-trace")) { trace(lock, vphi, filFree); return; }
        System.out.println("=== Soft Box — AXIAL SWING-LOCK single-motor gate (Brownian OFF, dt=" + DT + ") ===");
        System.out.println("  sphere-head: rod→(J2)→neck→(J1 0°→60° stroke)→HEAD⊥actin (F9 frozen 90°); head TIP F8-anchored to a HELD filament.");
        System.out.println("  F10 lock OFF ⇒ head.yVec→seg.yVec (â=ŷ_seg, the BoA bug); lock ON ⇒ head.yVec→ŝ=normalize(n̂bed×û_seg) head-only (§9.4c).");
        System.out.println();
        System.out.printf("  %-8s | %-28s | %-28s%n", "roll φ", "LOCK OFF (F10→seg.yVec)", "LOCK ON  (F10→ŝ)");
        System.out.printf("  %-8s | %-9s %-9s %-7s | %-9s %-9s %-7s%n", "(deg)", "axialFrac", "Δx(nm)", "|Δ|(nm)", "axialFrac", "Δx(nm)", "|Δ|(nm)");
        double[] phis = {0, 20, 45, 70, 85};
        double offAtRep = 0, onAtRep = 0; double offDx = 0, onDx = 0;
        for (double phi : phis) {
            double[] off = runStroke(phi, false);
            double[] on  = runStroke(phi, true);
            System.out.printf("  %-8.0f | %-9.3f %-+9.3f %-7.3f | %-9.3f %-+9.3f %-7.3f%n",
                    phi, off[0], off[1], off[2], on[0], on[1], on[2]);
            if (phi == 70) { offAtRep = off[0]; onAtRep = on[0]; offDx = off[1]; onDx = on[1]; }
        }
        System.out.println();
        System.out.println("  --- verdict (representative filament roll φ=70°, mat-typical) ---");
        System.out.printf("  swing axial fraction: LOCK OFF %.3f  →  LOCK ON %.3f%n", offAtRep, onAtRep);
        boolean lockDirects = onAtRep > 0.9 && onAtRep > offAtRep + 0.2;
        System.out.printf("  ⇒ %s%n", lockDirects
                ? "PASS — the axial lock makes the swing axial (≈1.0) where the un-locked swing wanders."
                : "*CHECK — the lock did not restore the axial swing (see geometry)*");
        // sweep direction: the lever rear sweeps toward the barbed (+x) end ⇒ the actin is driven pointed-first (−x glide)
        double dx = onDx;
        System.out.printf("  sweep direction (lock on): lever rear Δx = %+.3f nm ⇒ %s%n", dx,
                dx > 0.05 ? "BARBED-WARD (rear→+x ⇒ actin driven −x, pointed-leading = correct)"
                          : (dx < -0.05 ? "*POINTED-WARD (rear→−x ⇒ actin +x — bind pose/swing sense WRONG, §8)*"
                                        : "≈0 (degenerate — check pose)"));
        System.out.println();
        System.out.println("  NOTE: the glide VERDICT is the dense GPU mat (velFitX), not this kinematic gate. This gate only");
        System.out.println("        certifies the axial-lock effect (axial fraction off→on) + the sweep sense before the mat run.");
    }

    /** Run one uncocked→cocked stroke at filament roll φ; return {axialFrac, Δx_rear(nm), |Δ_rear,inplane|(nm)}
     *  of the lever REAR (rod-side end, lever.end1) between the two mechanical equilibria. */
    static double[] runStroke(double phiDeg, boolean lock) {
        double phi = Math.toRadians(phiDeg);
        // --- filament: HELD, axis +x, rolled about its axis by φ (seg.yVec = (0, cosφ, sinφ)) ---
        double zFil = ANCHOR_Z + MotorStore.ROD_LEN + MotorStore.LEVER_LEN + 0.5 * MotorStore.HEAD_LEN;
        FilamentStore fil = new FilamentStore(1);
        fil.monomerCount.set(0, 40);
        fil.setUVec(0, 1f, 0f, 0f);
        fil.setYVec(0, 0f, (float) Math.cos(phi), (float) Math.sin(phi));
        fil.setCoord(0, 0f, 0f, (float) zFil);
        fil.brownTransScale.set(0, 0f); fil.brownRotScale.set(0, 0f);
        DragTensorSystem.run(fil); fil.setParams(DT, 0); fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        // --- motor: one articulated body, bed-anchored, pointing +z; head TIP bound to the filament mid ---
        MotorStore mot = new MotorStore(1);
        RigidRodBody b = mot.body;
        mot.assembleArticulated(0, 0f, 0f, (float) ANCHOR_Z, 0f, 0f, 1f, 0f);   // Brownian OFF (brownScale 0)
        // AXIAL "vertical head" bind pose (the intended sphere-head geometry): head.uVec=+z (⊥ actin, ‖ n̂bed),
        // head.yVec=(0,1,0)=ŝ, and the LEVER pre-tilted in the axial x–z plane so the J1 swing azimuth starts
        // axial (rear sweeps in x). With lock ON the head roll is held at ŝ so the swing STAYS axial at any
        // filament roll φ; with lock OFF the head roll follows seg.yVec (rolled by φ) ⇒ the swing plane tilts.
        int head = 2, lever = 1;
        b.setUVec(head, 0f, 0f, 1f);
        b.setYVec(head, 0f, 1f, 0f);
        double lt = Math.toRadians(6.0);                                       // lever pre-tilt in x–z (axial swing seed)
        b.setUVec(lever, (float) Math.sin(lt), 0f, (float) Math.cos(lt));
        DragTensorSystem.run(mot);
        mot.setCounts(0, 0, fil.n);
        mot.setBodyParams(DT); mot.setJointParams(DT); mot.setKinParams(0.006, -0.4, DT); mot.setNucParams(DT);
        if (DIRECTED) mot.jointParams.set(3, 0f);   // J1 angular converter OFF ⇒ directedSwing is the sole (deterministic) stroke driver
        for (int sub = 0; sub < 3; sub++) { b.brownTransScale.set(sub, 0f); b.brownRotScale.set(sub, 0f); }
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        // bind head TIP to the filament mid (F8 anchor to a fixed material site)
        mot.boundSeg.set(0, 0);
        mot.bindArc.set(0, (float) (0.5 * fil.segLength.get(0)));

        FloatArray bondData = new FloatArray(CrossBridgeSystem.STRIDE); bondData.init(0f);
        // xbParams: [0]spring [1]- [2]j1FMT [3]dt [4]HEAD_LEN [5]bias [6..8]satOff [9]f9Frozen=1 [10]axLock
        FloatArray xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, 0.4f, (float) DT,
                (float) MotorStore.HEAD_LEN, 0f, 0f, 0f, 0f, 1f, lock ? 1f : 0f, ROLLSIGN ? 1f : 0f);

        // COCK (J1 rest 60°) and settle: the lever swings out ~60° from vertical; measure the rear (lever.end1)
        // horizontal offset from the anchor axis (x=0). Its (x,y) direction = the swing PLANE orientation.
        mot.nucleotideState.init(MotorStore.NUC_ATP);
        for (int t = 0; t < SETTLE; t++) step(fil, mot, bondData, xbParams);
        double dxn = b.end1X(lever) * 1e3, dyn = b.end1Y(lever) * 1e3;    // nm, offset from the anchor axis at (0,0)
        double inPlane = Math.sqrt(dxn * dxn + dyn * dyn);
        double axialFrac = inPlane > 1e-9 ? Math.abs(dxn) / inPlane : 0.0;
        return new double[]{ axialFrac, dxn, inPlane };
    }

    /** One motor step (filament HELD): the production kernels the dense GPU mat runs, Brownian off. */
    static void step(FilamentStore f, MotorStore mot, FloatArray bondData, FloatArray xbParams) {
        RigidRodBody b = mot.body;
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum,
                mot.nucleotideState, mot.jointParams, mot.counts);
        TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, bondData, xbParams);
        CrossBridgeSystem.applyHeadForce(bondData, b.forceSum, b.torqueSum, mot.counts);
        if (DIRECTED && HFSWING) CrossBridgeSystem.directedSwingHeadFrame(b.uVec, b.yVec, b.torqueSum, b.bRotGam, mot.boundSeg, mot.nucleotideState, SWING, mot.counts);
        else if (DIRECTED) CrossBridgeSystem.directedSwing(b.uVec, b.torqueSum, b.bRotGam, f.uVec, mot.boundSeg, mot.nucleotideState, SWING, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum,
                b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
    }

    /** TRACE the J1 neck angle + SIGNED swing direction across power↔recovery, filament HELD, Brownian OFF.
     *  Prints the neck-vs-head unsigned angle θ (deg) and the lever REAR (lever.end1) offset from the anchor
     *  axis in nm (x = axial/along-filament, y = transverse), so we can see whether recovery returns to
     *  STRAIGHT (θ→0, rear→origin) or swings to the opposite side. */
    static void trace(boolean lock, double phiDeg, boolean filFree) {
        double phi = Math.toRadians(phiDeg);
        double zFil = ANCHOR_Z + MotorStore.ROD_LEN + MotorStore.LEVER_LEN + 0.5 * MotorStore.HEAD_LEN;
        FilamentStore fil = new FilamentStore(1);
        fil.monomerCount.set(0, 120);
        fil.setUVec(0, 1f, 0f, 0f); fil.setYVec(0, 0f, (float) Math.cos(phi), (float) Math.sin(phi));
        fil.setCoord(0, 0f, 0f, (float) zFil);
        fil.brownTransScale.set(0, 0f); fil.brownRotScale.set(0, 0f);
        DragTensorSystem.run(fil); fil.setParams(DT, 0); fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);
        MotorStore mot = new MotorStore(1); RigidRodBody b = mot.body; int head = 2, lever = 1;
        mot.assembleArticulated(0, 0f, 0f, (float) ANCHOR_Z, 0f, 0f, 1f, 0f);
        b.setUVec(head, 0f, 0f, 1f); b.setYVec(head, 0f, 1f, 0f);
        double lt = Math.toRadians(6.0); b.setUVec(lever, (float) Math.sin(lt), 0f, (float) Math.cos(lt));
        DragTensorSystem.run(mot); mot.setCounts(0, 0, fil.n);
        mot.setBodyParams(DT); mot.setJointParams(DT); mot.setKinParams(0.006, -0.4, DT); mot.setNucParams(DT);
        if (DIRECTED) mot.jointParams.set(3, 0f);   // J1 angular converter OFF ⇒ directedSwing is the sole (deterministic) stroke driver
        for (int sub = 0; sub < 3; sub++) { b.brownTransScale.set(sub, 0f); b.brownRotScale.set(sub, 0f); }
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        mot.boundSeg.set(0, 0); mot.bindArc.set(0, (float) (0.5 * fil.segLength.get(0)));
        FloatArray bondData = new FloatArray(CrossBridgeSystem.STRIDE); bondData.init(0f);
        FloatArray xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, 0.4f, (float) DT,
                (float) MotorStore.HEAD_LEN, 0f, 0f, 0f, 0f, 1f, lock ? 1f : 0f, ROLLSIGN ? 1f : 0f);
        int nB = b.coord.getSize() / 3;
        System.out.println("=== J1 neck trace (Brownian OFF, lock " + (lock ? "ON" : "OFF") + ", φ=" + phiDeg + "°, filament " + (filFree ? "FREE" : "HELD") + ") ===");
        System.out.println("  θ = unsigned neck-vs-head angle; rearX = axial offset of lever.end1 (nm); rearY = transverse (nm)");
        String[] phase = { "POWER (ATP, rest 60°)", "RECOVERY (ADP-Pi, rest 0°)" };
        int[] states = { MotorStore.NUC_ATP, MotorStore.NUC_ADPPI };
        for (int rep = 0; rep < 2; rep++) {
          for (int ph = 0; ph < 2; ph++) {
            mot.nucleotideState.init(states[ph]);
            System.out.printf("  --- %s ---%n", phase[ph]);
            for (int k = 0; k < 4000; k++) {
                stepV(fil, mot, bondData, xbParams, filFree);
                if (k % 800 == 0 || k == 3999) {
                    double th = angDeg(b.uVec, lever, head, nB);
                    System.out.printf("    step %4d: θ=%5.1f°  rearX=%+7.2f nm  rearY=%+7.2f nm  head.uVec=(%+.2f,%+.2f,%+.2f)%n",
                            k, th, b.end1X(lever) * 1e3, b.end1Y(lever) * 1e3,
                            b.uVec.get(head), b.uVec.get(nB + head), b.uVec.get(2 * nB + head));
                }
            }
          }
        }
    }

    // ================= viewer: single-motor stroke, Brownian OFF =================
    /** Dump viewer frames of ONE sphere-head motor (F9-frozen ⊥ head + J1 neck-swing + the axial F10 lock)
     *  cycling ADP-Pi↔ATP on the production kernels, Brownian OFF. Filament FREE (an assay: it steps) unless
     *  -held. Frames render rod (long tube) + neck (short tube) + head (sphere) + the filament segment. */
    static void dumpViewer(String dir, boolean lock, double phiDeg, boolean filFree) {
        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir)); } catch (Exception e) {}
        double phi = Math.toRadians(phiDeg);
        double zFil = ANCHOR_Z + MotorStore.ROD_LEN + MotorStore.LEVER_LEN + 0.5 * MotorStore.HEAD_LEN;
        FilamentStore fil = new FilamentStore(1);
        fil.monomerCount.set(0, 120);                                     // ~0.33 µm segment
        fil.setUVec(0, 1f, 0f, 0f);
        fil.setYVec(0, 0f, (float) Math.cos(phi), (float) Math.sin(phi));
        fil.setCoord(0, 0f, 0f, (float) zFil);
        fil.brownTransScale.set(0, 0f); fil.brownRotScale.set(0, 0f);    // Brownian OFF
        DragTensorSystem.run(fil); fil.setParams(DT, 0); fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        MotorStore mot = new MotorStore(1);
        RigidRodBody b = mot.body;
        int head = 2, lever = 1;
        mot.assembleArticulated(0, 0f, 0f, (float) ANCHOR_Z, 0f, 0f, 1f, 0f);   // Brownian OFF
        b.setUVec(head, 0f, 0f, 1f); b.setYVec(head, 0f, 1f, 0f);               // vertical head, roll = ŝ
        double lt = Math.toRadians(6.0);
        b.setUVec(lever, (float) Math.sin(lt), 0f, (float) Math.cos(lt));       // axial swing seed
        DragTensorSystem.run(mot); mot.setCounts(0, 0, fil.n);
        mot.setBodyParams(DT); mot.setJointParams(DT); mot.setKinParams(0.006, -0.4, DT); mot.setNucParams(DT);
        if (DIRECTED) mot.jointParams.set(3, 0f);   // J1 angular converter OFF ⇒ directedSwing is the sole (deterministic) stroke driver
        for (int sub = 0; sub < 3; sub++) { b.brownTransScale.set(sub, 0f); b.brownRotScale.set(sub, 0f); }
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        mot.boundSeg.set(0, 0); mot.bindArc.set(0, (float) (0.5 * fil.segLength.get(0)));

        FloatArray bondData = new FloatArray(CrossBridgeSystem.STRIDE); bondData.init(0f);
        FloatArray xbParams = FloatArray.fromElements((float) MYO_SPRING, 90f, 0.4f, (float) DT,
                (float) MotorStore.HEAD_LEN, 0f, 0f, 0f, 0f, 1f, lock ? 1f : 0f, ROLLSIGN ? 1f : 0f);

        System.out.println("=== SPHERE-HEAD + axial lock — single-motor viewer (Brownian OFF, lock " + (lock ? "ON" : "OFF")
                + ", φ=" + phiDeg + "°, filament " + (filFree ? "FREE" : "HELD") + ") → " + dir + " ===");
        double x0 = fil.coord.get(0);
        int frame = 0, t = 0;
        for (int cyc = 0; cyc < 4; cyc++) {
            mot.nucleotideState.init(MotorStore.NUC_ADPPI);                     // recovery (J1 → 0°)
            for (int k = 0; k < 300; k++) { stepV(fil, mot, bondData, xbParams, filFree); if (k % 4 == 0) writeFrame(dir, frame++, (t++) * DT, fil, mot, lock); }
            mot.nucleotideState.init(MotorStore.NUC_ATP);                       // POWER STROKE (J1 → 60°)
            for (int k = 0; k < 300; k++) { stepV(fil, mot, bondData, xbParams, filFree); if (k % 4 == 0) writeFrame(dir, frame++, (t++) * DT, fil, mot, lock); }
        }
        double j1 = angDeg(b.uVec, lever, head, mot.body.coord.getSize() / 3);
        System.out.printf("  dumped %d frames (4 cycles); J1 neck-vs-head θ→%.0f°, filament centroid Δx=%+.3f nm%n",
                frame, j1, (fil.coord.get(0) - x0) * 1e3);
        System.out.println("  legend: long thin tube = ROD/tail (bed-anchored); SHORT tube = NECK/lever; BIG ball = HEAD (⊥ actin);");
        System.out.println("          the neck swings axially (F10 lock → ŝ), the head stays ⊥, on a single filament segment.");
        System.out.println("  serve: python3 SoftBox/sim_server.py 8000  (from ~/Code) → http://localhost:8000/SoftBox/sim_viewer_boa.html");
    }

    static double angDeg(FloatArray uVec, int a, int c, int nB) {
        double d = uVec.get(a) * uVec.get(c) + uVec.get(nB + a) * uVec.get(nB + c) + uVec.get(2 * nB + a) * uVec.get(2 * nB + c);
        if (d > 1) d = 1; if (d < -1) d = -1;
        return Math.acos(d) * 180.0 / Math.PI;
    }

    /** Viewer step: production kernels; if filFree also apply the seg-side bond reaction + integrate the filament. */
    static void stepV(FilamentStore f, MotorStore mot, FloatArray bondData, FloatArray xbParams, boolean filFree) {
        RigidRodBody b = mot.body;
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        if (filFree) ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum,
                mot.nucleotideState, mot.jointParams, mot.counts);
        TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, bondData, xbParams);
        CrossBridgeSystem.applyHeadForce(bondData, b.forceSum, b.torqueSum, mot.counts);
        if (DIRECTED && HFSWING) CrossBridgeSystem.directedSwingHeadFrame(b.uVec, b.yVec, b.torqueSum, b.bRotGam, mot.boundSeg, mot.nucleotideState, SWING, mot.counts);
        else if (DIRECTED) CrossBridgeSystem.directedSwing(b.uVec, b.torqueSum, b.bRotGam, f.uVec, mot.boundSeg, mot.nucleotideState, SWING, mot.counts);
        if (filFree) {                                                    // apply the seg-side bond reaction (one motor → seg 0)
            int nS = f.coord.getSize() / 3;
            f.forceSum.set(0,        (float) (f.forceSum.get(0)        + bondData.get(6)));
            f.forceSum.set(nS,       (float) (f.forceSum.get(nS)       + bondData.get(7)));
            f.forceSum.set(2 * nS,   (float) (f.forceSum.get(2 * nS)   + bondData.get(8)));
            f.torqueSum.set(0,       (float) (f.torqueSum.get(0)       + bondData.get(9)));
            f.torqueSum.set(nS,      (float) (f.torqueSum.get(nS)      + bondData.get(10)));
            f.torqueSum.set(2 * nS,  (float) (f.torqueSum.get(2 * nS)  + bondData.get(11)));
        }
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum,
                b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        if (filFree) {
            RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                    f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        }
    }

    static void writeFrame(String dir, int frame, double t, FilamentStore f, MotorStore mot, boolean lock) {
        RigidRodBody b = mot.body; int rod = 0, lever = 1, head = 2;
        double hx = b.coordX(head), hy = b.coordY(head), hz = b.coordZ(head);
        boolean bound = mot.boundSeg.get(0) >= 0;
        String state = mot.nucleotideState.get(0) != MotorStore.NUC_ADPPI ? "ATP" : "ADPPi";
        StringBuilder sb = new StringBuilder(768);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":0.4,\"yDim\":0.2,\"zDim\":0.25}", frame, t));
        sb.append(",\"segments\":[");
        sb.append(String.format(java.util.Locale.US, "{\"id\":0,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0,\"isBarbedEnd\":true}",
            f.end1.get(0), f.end1.get(f.n), f.end1.get(2 * f.n), f.end2.get(0), f.end2.get(f.n), f.end2.get(2 * f.n), Constants.radius));
        sb.append("],\"myosins\":[");
        sb.append(String.format(java.util.Locale.US,
            "{\"id\":0,\"bound\":%b,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
            + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
            + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
            bound, b.end1X(rod), b.end1Y(rod), b.end1Z(rod), b.end2X(rod), b.end2Y(rod), b.end2Z(rod), MotorStore.ROD_R,
            b.end1X(lever), b.end1Y(lever), b.end1Z(lever), b.end2X(lever), b.end2Y(lever), b.end2Z(lever), MotorStore.LEVER_R,
            hx, hy, hz, hx, hy, hz, HEAD_SPHERE_R, state));
        sb.append("]}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US, "frame_%06d.json", frame)), sb.toString()); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
