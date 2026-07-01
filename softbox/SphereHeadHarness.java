package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * SPHERE-HEAD motor — THREE-BODY topology (PHASE-2, 2026-06-30 FIX). The earlier build collapsed
 * rod→lever→head (3 bodies, J2+J1) to 2 bodies by making the "sphere" a point at lever.end2 — the
 * head had no spatial extent, J1 collapsed, the neck reached the filament directly (the viewer "T").
 *
 * RESTORED: rod → (J2) → neck/lever → (J1) → HEAD, three distinct rigid bodies, both joints intact —
 * the v1/default articulated body, with the head a SPHERE of real extent (the catalytic domain) instead
 * of a rod-with-a-head-vs-actin-angle (F9 stays deleted: no head axis is used against actin).
 *   • The HEAD carries the actin attachment: the anchor spring (head CENTER ↔ fixed filament site) lives
 *     on the head body (a point contact ⇒ no torque ⇒ the sphere is free to rotate, NO orientation pin).
 *   • J1 (lever↔head) is a REAL articulating joint and its swing IS the powerstroke: the converter drives
 *     the neck-vs-head angle 0°→60° on cocking (MotorJointSystem.joints, the default converter), the head
 *     held on the filament — the BoA Run-2 mechanism (neck swinging vs a head on actin), head now a sphere.
 *   • Rod tail = the v1-faithful COMPLIANT bed anchor (MyosinFixed point-spring; rod orientation free).
 *   • Catch-slip load = the anchor-spring force along the filament axis (on the head). v1's signal.
 *
 * NO rigid pin, NO orientation pin, NO J1 stiffening — if the free head can only work by pinning it,
 * that is the pin-absorption failure returning ⇒ STOP and report (do not stiffen anything).
 *
 * Single-motor gate ONLY (M1 step, M2 impulse-free bind, M3 direction). NOT a glide claim (standing rule:
 * glide requires the full-mat assay, the next increment). New files/flag-gated ⇒ production byte-identical.
 */
public final class SphereHeadHarness {
    static final double ANCHOR_Z = -0.05;
    static final double HEAD_SPHERE_R = 0.5 * MotorStore.HEAD_LEN;   // render the catalytic domain as a sphere (~10 nm)
    static final double MYO_SPRING = 1.0e-9;                         // anchor-spring stiffness (N/µm), v1 myoSpring
    static double DT = 1.0e-5, FIL_GAM = 1.0;
    static String VIZ = null;            // -3js <dir>: dump viewer frames of a single-motor stroke
    static double VIZ_EXAG = 1.0;        // -vizexag: neck-length exaggeration FOR THE VIEWER ONLY (clarity)
    static boolean THERMAL = false;      // -thermal: motor Brownian ON (breaks the J1 collinear degeneracy, the real-motor way)
    static boolean GLIDE = false;        // -glide: multi-motor gliding mat demo
    static int GLIDE_NMOT = 40, GLIDE_STEPS = 60000;
    static boolean NORESET = false;
    static double HEAD_TILT_DEG = -25.0; // initial head tilt to break the J1 collinear degeneracy; SIGN sets the stroke
                                         // polarity → −25° gives step +X / filament −X (the validated glide direction)
    static double PERP_COEFF = 0.4;      // PAIRS-style perp-orientation torque coeff (v1 F9 fracMoveTorq); 0 ⇒ off (-noperp)

    static class Scene {
        FilamentStore fil; MotorStore mot;
        FloatArray bondData, sphereParams, jointParams;
        boolean filFree;
    }

    public static void main(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-dt")) DT = Double.parseDouble(args[++i]);
            else if (args[i].equals("-filgam")) FIL_GAM = Double.parseDouble(args[++i]);
            else if (args[i].equals("-3js")) VIZ = args[++i];
            else if (args[i].equals("-vizexag")) VIZ_EXAG = Double.parseDouble(args[++i]);
            else if (args[i].equals("-thermal")) THERMAL = true;
            else if (args[i].equals("-perpcoeff")) PERP_COEFF = Double.parseDouble(args[++i]);
            else if (args[i].equals("-noperp")) PERP_COEFF = 0.0;   // A/B: disable the perp-orientation torque
            else if (args[i].equals("-headtilt")) HEAD_TILT_DEG = Double.parseDouble(args[++i]);   // degeneracy-breaker sign sets the stroke polarity
            else if (args[i].equals("-glide")) { GLIDE = true; }
            else if (args[i].equals("-noreset")) NORESET = true;                                   // multi-motor gliding mat demo (use with -3js)
            else if (args[i].equals("-nmot")) GLIDE_NMOT = Integer.parseInt(args[++i]);
            else if (args[i].equals("-steps")) GLIDE_STEPS = Integer.parseInt(args[++i]);
        }
        if (GLIDE) { glideDemo(); return; }
        if (VIZ != null) { dumpViewer(); return; }
        System.out.println("=== Soft Box — SPHERE-HEAD motor, THREE-BODY gate (single motor, dt=" + DT + ") ===");
        System.out.println("  topology: rod →(J2)→ neck →(J1 converter = stroke)→ HEAD-sphere(R=" + (HEAD_SPHERE_R * 1e3)
                + " nm) binding actin; compliant tail; F9 deleted.");
        System.out.printf("  J1 converter rest 0°(uncocked) → 60°(cocked); neck=%.1f nm, head=%.1f nm%n%n",
                MotorStore.LEVER_LEN * 1e3, MotorStore.HEAD_LEN * 1e3);
        measureStep();
        measureImpulseFreeBind();
        measureDirection();
        measureThermal();
    }

    /** M4 — the FAIR dynamic test: motor Brownian ON (breaks the collinear J1 degeneracy the real-motor way),
     *  hold cocked, measure net filament Δx vs a stroke-OFF (converter torque=0) thermal-floor control, multi-seed.
     *  If stroke-ON ≈ stroke-OFF ⇒ the free head ABSORBS the J1 converter (no transport ⇒ the bail). */
    static void measureThermal() {
        System.out.println("--- M4: dynamic transport, thermal noise ON (the fair test) — stroke ON vs OFF, n=4 ---");
        boolean save = THERMAL; THERMAL = true;
        int seeds = 4, settle = 8000, hold = 32000;
        double[] net = new double[2];
        for (int on = 1; on >= 0; on--) {
            double sum = 0;
            for (int s = 0; s < seeds; s++) {
                Scene sc = buildScene(true);
                if (on == 0) sc.jointParams.set(3, 0f);                 // converter OFF = thermal floor
                int seed = 0x5B17E + s * 0x1131;
                sc.mot.nucleotideState.init(MotorStore.NUC_ADPPI);
                for (int t = 0; t < settle; t++) { sc.mot.setCounts(t, seed, sc.fil.n); stepBoth(sc); }
                double x0 = filCentroidX(sc);
                sc.mot.nucleotideState.init(MotorStore.NUC_ATP);
                for (int t = settle; t < settle + hold; t++) { sc.mot.setCounts(t, seed, sc.fil.n); stepBoth(sc); }
                sum += (filCentroidX(sc) - x0) * 1e3;
            }
            net[on] = sum / seeds;
            System.out.printf("  stroke %s: mean net filament Δx = %+.3f nm (n=%d)%n", on == 1 ? "ON " : "OFF", net[on], seeds);
        }
        double delta = net[1] - net[0];
        System.out.printf("  ⇒ stroke-attributable Δx (ON−OFF) = %+.3f nm  %s%n", delta,
                Math.abs(delta) < 1.0 ? "*≈0: the free head ABSORBS the J1 converter — no transport (see BAIL)*"
                                      : (delta < 0 ? "−X transport (converter does work)" : "+X (sign)"));
        THERMAL = save;
        System.out.println();
    }

    // ---- M1: geometric step — anchor OFF, filament held; does the converter swing MOVE the head, or just spin it? ----
    static void measureStep() {
        System.out.println("--- M1: per-cycle geometric step (anchor off; head CENTER displacement, uncocked→cocked) ---");
        double[] u = headEquilibrium(MotorStore.NUC_ADPPI);   // uncocked (J1 rest 0°)
        double[] c = headEquilibrium(MotorStore.NUC_ATP);     // cocked   (J1 rest 60°)
        double dxn = (c[0] - u[0]) * 1e3, dyn = (c[1] - u[1]) * 1e3, dzn = (c[2] - u[2]) * 1e3;
        double step = Math.sqrt(dxn * dxn + dyn * dyn + dzn * dzn);
        System.out.printf("  head CENTER Δ (nm): (%+.2f, %+.2f, %+.2f)  ⇒ |step|=%.2f nm,  axial frac |Δx|/|Δ|=%.2f%n",
                dxn, dyn, dzn, step, Math.abs(dxn) / Math.max(1e-9, step));
        boolean axial = Math.abs(dxn) > 0.6 * step;
        boolean skeletal = step > 3.0 && step < 16.0;
        System.out.printf("  axial step Δx=%+.2f nm (−x = pointed-leading)  %s%n", dxn,
                (step < 1.0) ? "*HEAD ABSORBS the converter (spins, no throw) — see bail*"
                             : ((axial && skeletal) ? "PASS (skeletal-range, axial)" : "*CHECK geometry*"));
        System.out.println();
    }

    /** Hold the nucleotide state, anchor OFF, settle; return the HEAD-sphere CENTER (µm). With the anchor off
     *  the head is held only by J1: this reveals whether the converter swing translates the head or just spins it. */
    static double[] headEquilibrium(int state) {
        Scene sc = buildScene(false);
        sc.sphereParams.set(0, 0f);                 // anchor off
        sc.mot.nucleotideState.init(state);
        for (int t = 0; t < 8000; t++) { sc.mot.setCounts(t, 0x5B17E, sc.fil.n); stepMotor(sc); }
        RigidRodBody b = sc.mot.body; int head = 2;
        return new double[]{ b.coordX(head), b.coordY(head), b.coordZ(head) };
    }

    // ---- M2: impulse-free bind (anchor ON, NOT stroking, free filament ⇒ must stay put) ----
    static void measureImpulseFreeBind() {
        System.out.println("--- M2: impulse-free bind control (bound, NOT stroking, free filament) ---");
        Scene sc = buildScene(true);
        sc.jointParams.set(3, 0f);                           // J1 converter torque OFF (hold the pose; only the anchor acts)
        sc.mot.nucleotideState.init(MotorStore.NUC_ADPPI);
        double x0 = filCentroidX(sc);
        for (int t = 0; t < 20000; t++) { sc.mot.setCounts(t, 0x5B17E, sc.fil.n); stepBoth(sc); }
        double drift = (filCentroidX(sc) - x0) * 1e3;
        System.out.printf("  filament centroid Δx over 20k steps = %+.3f nm  %s%n", drift,
                Math.abs(drift) < 1.0 ? "PASS (impulse-free)" : "*BIND IMPULSE*");
        System.out.println();
    }

    // ---- M3: direction + delivered step (anchor ON, bind uncocked → cock; free filament) ----
    static void measureDirection() {
        System.out.println("--- M3: per-cycle filament step + direction (uncocked→cocked, free filament) ---");
        Scene sc = buildScene(true);
        sc.mot.nucleotideState.init(MotorStore.NUC_ADPPI);
        for (int t = 0; t < 8000; t++) { sc.mot.setCounts(t, 0x5B17E, sc.fil.n); stepBoth(sc); }
        double x0 = filCentroidX(sc);
        sc.mot.nucleotideState.init(MotorStore.NUC_ATP);     // COCK
        int[] chk = {100, 500, 2000, 8000, 16000}; int ci = 0;
        System.out.print("  filament Δx after cock (nm) [J1 neck-vs-head θ]:\n   ");
        for (int t = 8000; t < 24000; t++) {
            sc.mot.setCounts(t, 0x5B17E, sc.fil.n); stepBoth(sc);
            if (ci < chk.length && (t - 8000 + 1) == chk[ci]) {
                System.out.printf(" %d:%+.2f(J1 %.0f°,head⊥ %.0f°)", chk[ci], (filCentroidX(sc) - x0) * 1e3, j1AngleDeg(sc), headFilAngleDeg(sc)); ci++;
            }
        }
        double dxn = (filCentroidX(sc) - x0) * 1e3;
        System.out.printf("%n  DIRECTION: %s%n", dxn < -0.5 ? "−X PASS" : (dxn > 0.5 ? "+X" : "≈0 (no transport — head likely absorbing the converter)"));
        System.out.println();
    }

    // ================= scene + step =================
    static Scene buildScene(boolean filFree) {
        Scene sc = new Scene(); sc.filFree = filFree;
        double leverLen = MotorStore.LEVER_LEN * (VIZ != null ? VIZ_EXAG : 1.0);   // neck exaggeration FOR VIEWER ONLY
        // head-sphere CENTER lies on the filament; neck reaches down to the rod; rod down to the bed anchor.
        double zHeadC = ANCHOR_Z + MotorStore.ROD_LEN + leverLen + 0.5 * MotorStore.HEAD_LEN;
        double zFil = zHeadC;

        FilamentStore fil = new FilamentStore(1);
        fil.monomerCount.set(0, Constants.stdSegLength);
        fil.setUVec(0, 1f, 0f, 0f); fil.setYVec(0, 0f, 1f, 0f);
        fil.setCoord(0, 0f, 0f, (float) zFil);
        fil.brownTransScale.set(0, 0f); fil.brownRotScale.set(0, 0f);
        DragTensorSystem.run(fil);
        if (FIL_GAM != 1.0) for (int p = 0; p < fil.bTransGam.getSize(); p++) fil.bTransGam.set(p, (float) (fil.bTransGam.get(p) * FIL_GAM));
        fil.setParams(DT, 0); fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        MotorStore mot = new MotorStore(1);
        RigidRodBody b = mot.body;
        int rod = 0, lever = 1, head = 2;
        double rc = ANCHOR_Z + 0.5 * MotorStore.ROD_LEN;
        double lc = ANCHOR_Z + MotorStore.ROD_LEN + 0.5 * leverLen;
        double hc = zHeadC;
        b.setCoord(rod, 0f, 0f, (float) rc); b.setUVec(rod, 0f, 0f, 1f); b.setYVec(rod, 1f, 0f, 0f);
        b.setCoord(lever, 0f, 0f, (float) lc); b.setUVec(lever, 0f, 0f, 1f); b.setYVec(lever, 1f, 0f, 0f);
        // initial head tilt (x-z plane) breaks the J1 collinear degeneracy so the converter has a defined axis
        double ht = HEAD_TILT_DEG * Math.PI / 180.0;
        b.setCoord(head, 0f, 0f, (float) hc); b.setUVec(head, (float) Math.sin(ht), 0f, (float) Math.cos(ht)); b.setYVec(head, 1f, 0f, 0f);
        mot.setAnchor(0, 0f, 0f, (float) ANCHOR_Z);
        float bs = THERMAL ? 1f : 0f;   // motor Brownian breaks the J1 collinear degeneracy (the real-motor way)
        for (int s = rod; s <= head; s++) { b.brownTransScale.set(s, bs); b.brownRotScale.set(s, bs); }
        DragTensorSystem.run(mot);
        // viewer-only neck exaggeration: override the lever sub-body length + its rod drag (faithful no-op otherwise)
        b.segLength.set(lever, (float) leverLen);
        double[] lg = DragTensorSystem.rodDragSI(leverLen, MotorStore.LEVER_R);
        int lx = b.planeX(lever), ly = b.planeY(lever), lz = b.planeZ(lever);
        b.bTransGam.set(lx, (float) lg[0]); b.bTransGam.set(ly, (float) lg[1]); b.bTransGam.set(lz, (float) lg[2]);
        b.bRotGam.set(lx, (float) lg[3]); b.bRotGam.set(ly, (float) lg[4]); b.bRotGam.set(lz, (float) lg[5]);
        mot.setBodyParams(DT); mot.setJointParams(DT); mot.setKinParams(0.006, -0.4, DT); mot.setNucParams(DT);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);

        // bind the HEAD to the segment at the nearest arc (impulse-free: head center on the site at bind)
        mot.boundSeg.set(0, 0);
        mot.bindArc.set(0, (float) (0.5 * fil.segLength.get(0)));

        // jointParams: DEFAULT full articulated body — J2 connection + J1 converter (the stroke). NOTHING zeroed.
        sc.jointParams = mot.jointParams;
        sc.bondData = new FloatArray(CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        sc.sphereParams = FloatArray.fromElements((float) MYO_SPRING, (float) DT, (float) PERP_COEFF);
        sc.fil = fil; sc.mot = mot;
        return sc;
    }

    /** One motor step (filament held in stepMotor; integrated by stepBoth if free). The J1 converter
     *  (in joints) IS the powerstroke; the anchor (sphereBond, head center ↔ actin) transmits to the filament;
     *  the tail is the v1 compliant point-anchor. NO rigid clamp, NO SphereHeadSystem.swing. */
    static void stepMotor(Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);  // no-op when brownScale=0
        MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum,
                mot.nucleotideState, sc.jointParams, mot.counts);
        TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, sc.jointParams, mot.counts);
        SphereHeadSystem.sphereBond(b.coord, b.uVec, b.segLength, b.bRotGam, f.coord, f.uVec, f.segLength,
                b.forceSum, b.torqueSum, f.forceSum, f.torqueSum, mot.boundSeg, mot.bindArc, sc.bondData, sc.sphereParams, mot.counts);
        // PAIRS-style orientation torque: maintain the head ⊥ to the filament (the binding pose / actin reference)
        SphereHeadSystem.perpTorque(b.coord, b.uVec, b.bRotGam, f.uVec, f.bRotGam, b.torqueSum, f.torqueSum,
                mot.boundSeg, sc.sphereParams, mot.counts);
        RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
    }

    static void stepBoth(Scene sc) {
        stepMotor(sc);
        if (!sc.filFree) return;
        FilamentStore f = sc.fil;
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
    }

    /** J1 converter angle = angle(lever.uVec, head.uVec) in degrees (the neck-vs-head angle, the stroke coordinate). */
    static double j1AngleDeg(Scene sc) {
        RigidRodBody b = sc.mot.body; int lever = 1, head = 2;
        double d = b.uVecX(lever) * b.uVecX(head) + b.uVecY(lever) * b.uVecY(head) + b.uVecZ(lever) * b.uVecZ(head);
        if (d > 1) d = 1; if (d < -1) d = -1;
        return Math.acos(d) * 180.0 / Math.PI;
    }
    /** head-vs-filament angle (deg) — should hold ~90° when the perp torque is on (the binding pose). */
    static double headFilAngleDeg(Scene sc) {
        RigidRodBody b = sc.mot.body; int head = 2, seg = 0;
        double d = b.uVecX(head) * sc.fil.uVec.get(seg) + b.uVecY(head) * sc.fil.uVec.get(sc.fil.n + seg) + b.uVecZ(head) * sc.fil.uVec.get(2 * sc.fil.n + seg);
        if (d > 1) d = 1; if (d < -1) d = -1;
        return Math.acos(d) * 180.0 / Math.PI;
    }
    static double filCentroidX(Scene sc) {
        double s = 0; int n = sc.fil.n;
        for (int i = 0; i < n; i++) s += sc.fil.coord.get(i);
        return s / n;
    }

    // ================= Part 2: viewer frames =================
    static void dumpViewer() {
        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of(VIZ)); } catch (Exception e) {}
        System.out.println("=== SPHERE-HEAD 3-body viewer dump → " + VIZ + "  (dt=" + DT + ", neck×" + VIZ_EXAG + ") ===");
        Scene sc = buildScene(true);
        int frame = 0, t = 0;
        for (int cyc = 0; cyc < 2; cyc++) {
            sc.mot.nucleotideState.init(MotorStore.NUC_ADPPI);   // recovery (J1→0°)
            for (int k = 0; k < 240; k++) { sc.mot.setCounts(t++, 0x5B17E, sc.fil.n); stepBoth(sc); if (k % 3 == 0) writeFrame(frame++, t * DT, sc); }
            sc.mot.nucleotideState.init(MotorStore.NUC_ATP);     // POWER STROKE (J1→60°)
            for (int k = 0; k < 240; k++) { sc.mot.setCounts(t++, 0x5B17E, sc.fil.n); stepBoth(sc); if (k % 3 == 0) writeFrame(frame++, t * DT, sc); }
        }
        System.out.printf("  dumped %d frames (2 cycles); J1 θ→%.0f°, filament centroid x=%+.4f µm%n", frame, j1AngleDeg(sc), filCentroidX(sc));
        System.out.println("  legend: long thin tube = ROD/tail (bed-anchored); SHORT tube = NECK/lever; BIG ball = HEAD/catalytic");
        System.out.println("          domain (its own body, stands the neck OFF the filament, binds actin). Three distinct bodies.");
        System.out.println("  serve: python3 SoftBox/sim_server.py 8000 (from ~/Code) → http://localhost:8000/SoftBox/sim_viewer_boa.html");
    }

    // ================= gliding mat: -3js demo OR velFitX assay (n seeds + nobind floor) =================
    static void glideDemo() {
        if (VIZ == null) { glideAssay(); return; }   // no -3js ⇒ the velFitX gate, not a frame dump
        runGlide(0x5B17E, false, VIZ);
    }

    /** Single-density velFitX gate: stroke-on (n=3) vs the -nobind thermal floor (n=3). velFitX = −slope of the
     *  steady-2nd-half centroid-x fit (positive = −x glide = correct polarity). CPU, REDUCED scale (the full
     *  ~28k-motor GPU bed is a separate large build) — staged here per the task's single-density-gate rule. */
    static void glideAssay() {
        try (java.io.FileWriter w = new java.io.FileWriter(".last_run_status", true)) { w.write("sphere-head glide gate start\n"); } catch (Exception e) {}
        System.out.println("=== SPHERE-HEAD gliding velFitX gate (CPU, " + GLIDE_NMOT + " motors, " + GLIDE_STEPS + " steps, dt=" + DT + ") ===");
        System.out.println("  velFitX>0 ⇒ −x glide (correct); <0 ⇒ +x (wrong polarity). [REDUCED-scale CPU gate; full 28k GPU bed is the next build]");
        int n = 3;
        double[] on = new double[n], fl = new double[n]; double ab = 0;
        for (int s = 0; s < n; s++) { double[] r = runGlide(0x5B17E + s * 0x1131, false, null); on[s] = r[0]; ab += r[1]; }
        for (int s = 0; s < n; s++) { double[] r = runGlide(0x5B17E + s * 0x1131, true,  null); fl[s] = r[0]; }
        System.out.printf("  STROKE-ON  velFitX = %+.3f ± %.3f µm/s (n=%d), avgBound %.2f%n", mean(on), sem(on), n, ab / n);
        System.out.printf("  FLOOR(nobind) velFitX = %+.3f ± %.3f µm/s (n=%d)%n", mean(fl), sem(fl), n);
        double sig = mean(on) - mean(fl);
        System.out.printf("  ⇒ glide vs floor = %+.3f µm/s  %s%n", sig,
                sig > 3 * sem(on) ? (sig > 0 ? "−X glide above floor" : "+X (WRONG polarity) above floor")
                                  : "within floor (no net glide)");
    }
    static double mean(double[] a) { double s = 0; for (double x : a) s += x; return s / a.length; }
    static double sem(double[] a) { double m = mean(a), v = 0; for (double x : a) v += (x - m) * (x - m); return Math.sqrt(v / Math.max(1, a.length - 1)) / Math.sqrt(a.length); }

    static double[] runGlide(int runSeed, boolean nobind, String dir) {
        if (dir != null) { try { java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir)); } catch (Exception e) {} }
        int nMot = GLIDE_NMOT, M = GLIDE_STEPS;
        double leverLen = MotorStore.LEVER_LEN * VIZ_EXAG;
        double ht = HEAD_TILT_DEG * Math.PI / 180.0;
        // --- filament: one long segment along +x (barbed/plus end +x), free + lightly Brownian ---
        double zFil = ANCHOR_Z + MotorStore.ROD_LEN + leverLen + 0.5 * MotorStore.HEAD_LEN;
        int monomers = 150;                                  // ≈0.4 µm segment
        FilamentStore fil = new FilamentStore(1);
        fil.monomerCount.set(0, monomers);
        fil.setUVec(0, 1f, 0f, 0f); fil.setYVec(0, 0f, 1f, 0f);
        fil.setCoord(0, 0.0f, 0f, (float) zFil);             // centred at x=0; glides −x
        fil.brownTransScale.set(0, 1f); fil.brownRotScale.set(0, 0f);
        DragTensorSystem.run(fil); fil.setParams(DT, 0); fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);
        double filLen = fil.segLength.get(0);
        // --- mat: nMot motors anchored in a bed under the filament + a −x runway, all canonical pose ---
        MotorStore mot = new MotorStore(nMot);
        RigidRodBody b = mot.body;
        double bedLo = -0.9, bedHi = 0.5 * filLen;            // runway to −x
        for (int m = 0; m < nMot; m++) {
            double ax = bedLo + (m + 0.5) / nMot * (bedHi - bedLo);
            assembleCanonical(b, m, ax, ht, leverLen);
            mot.setAnchor(m, (float) ax, 0f, (float) ANCHOR_Z);
        }
        DragTensorSystem.run(mot);
        for (int m = 0; m < nMot; m++) {                     // lever drag for the (possibly exaggerated) neck
            int lever = 3 * m + 1; b.segLength.set(lever, (float) leverLen);
            double[] lg = DragTensorSystem.rodDragSI(leverLen, MotorStore.LEVER_R);
            int lx = b.planeX(lever), ly = b.planeY(lever), lz = b.planeZ(lever);
            b.bTransGam.set(lx, (float) lg[0]); b.bTransGam.set(ly, (float) lg[1]); b.bTransGam.set(lz, (float) lg[2]);
            b.bRotGam.set(lx, (float) lg[3]); b.bRotGam.set(ly, (float) lg[4]); b.bRotGam.set(lz, (float) lg[5]);
        }
        mot.setBodyParams(DT); mot.setJointParams(DT); mot.setKinParams(0.006, -0.4, DT); mot.setNucParams(DT);
        for (int s = 0; s < 3 * nMot; s++) { b.brownTransScale.set(s, 0.3f); b.brownRotScale.set(s, 0.3f); }   // mild thermal
        DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
        mot.nucleotideState.init(MotorStore.NUC_NONE);
        mot.boundSeg.init(MotorStore.FREE_BINDABLE);
        // canonical-pose snapshot (re-applied at each bind = stereospecific binding ⇒ consistent +x stroke)
        FloatArray cC = new FloatArray(b.coord.getSize()), cU = new FloatArray(b.uVec.getSize()), cY = new FloatArray(b.yVec.getSize());
        for (int i = 0; i < b.coord.getSize(); i++) { cC.set(i, b.coord.get(i)); cU.set(i, b.uVec.get(i)); cY.set(i, b.yVec.get(i)); }

        Scene sc = new Scene(); sc.fil = fil; sc.mot = mot; sc.filFree = true;
        sc.jointParams = mot.jointParams;
        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        sc.sphereParams = FloatArray.fromElements((float) MYO_SPRING, (float) DT, (float) PERP_COEFF);
        int nB = b.coord.getSize() / 3;
        double reach = mot.kinParams.get(7) + MotorStore.HEAD_LEN;   // generous proximity reach for the tip

        double x0 = fil.coord.get(0);
        int frame = 0, every = Math.max(1, M / 300);
        int nSamp = 200, sEvery = Math.max(1, M / nSamp);
        double[] cxT = new double[nSamp]; int ci = 0;
        double boundAcc = 0; int boundN = 0;
        if (dir != null) System.out.println("=== SPHERE-HEAD gliding mat → " + dir + "  (" + nMot + " motors, " + M + " steps, dt=" + DT + ") ===");
        for (int t = 0; t < M; t++) {
            mot.setCounts(t, runSeed, fil.n);
            MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
            // release (catch-slip on last step's forceDotFil)
            NucleotideCycleSystem.catchSlipRelease(mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.cooldown, mot.stats, mot.capStats, mot.kinParams, mot.counts);
            // proximity bind + canonical-pose reset (stereospecific) for free motors reaching the filament
            if (!nobind) glideBind(sc, cC, cU, cY, reach, nB);
            NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            ChainBendingForceSystem.zeroAccumulators(fil.forceSum, fil.torqueSum, fil.counts);
            BrownianForceSystem.brownianForce(b.randForce, b.randTorque, b.bTransGam, b.bRotGam, b.brownTransScale, b.brownRotScale, mot.bodyParams, mot.counts);
            BrownianForceSystem.brownianForce(fil.randForce, fil.randTorque, fil.bTransGam, fil.bRotGam, fil.brownTransScale, fil.brownRotScale, fil.params, fil.counts);
            MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, sc.jointParams, mot.counts);
            TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, sc.jointParams, mot.counts);
            SphereHeadSystem.sphereBond(b.coord, b.uVec, b.segLength, b.bRotGam, fil.coord, fil.uVec, fil.segLength,
                    b.forceSum, b.torqueSum, fil.forceSum, fil.torqueSum, mot.boundSeg, mot.bindArc, sc.bondData, sc.sphereParams, mot.counts);
            SphereHeadSystem.perpTorque(b.coord, b.uVec, b.bRotGam, fil.uVec, fil.bRotGam, b.torqueSum, fil.torqueSum, mot.boundSeg, sc.sphereParams, mot.counts);
            CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(fil.coord, fil.uVec, fil.yVec, fil.forceSum, fil.torqueSum, fil.randForce, fil.randTorque, fil.bTransGam, fil.bRotGam, fil.params, fil.counts);
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);
            if (dir != null && t % every == 0) writeGlideFrame(dir, frame++, t * DT, sc);
            if (ci < nSamp && t % sEvery == 0) cxT[ci++] = fil.coord.get(0);
            if (t >= M / 2) { int nb = 0; for (int m = 0; m < nMot; m++) if (mot.boundSeg.get(m) >= 0) nb++; boundAcc += nb; boundN++; }
        }
        // velFitX = −slope of the LS fit of cx(t) over the steady 2nd half (positive = −x glide)
        int h = ci / 2; double sx = 0, sy = 0, sxx = 0, sxy = 0; int nn = 0;
        for (int i = h; i < ci; i++) { double tt = i * sEvery * DT; sx += tt; sy += cxT[i]; sxx += tt * tt; sxy += tt * cxT[i]; nn++; }
        double slope = (nn * sxy - sx * sy) / (nn * sxx - sx * sx);
        double velFitX = -slope;   // µm/s; >0 = −x glide (correct)
        double netX = (fil.coord.get(0) - x0) * 1e3;
        double avgBound = boundN > 0 ? boundAcc / boundN : 0;
        if (dir != null) {
            System.out.printf("  dumped %d frames; net Δx %+.1f nm; velFitX %+.3f µm/s (>0=−x); avgBound %.2f  [VISUAL DEMO — gate is glideAssay]%n",
                    frame, netX, velFitX, avgBound);
            System.out.println("  serve: python3 SoftBox/sim_server.py 8000 (from ~/Code) → http://localhost:8000/SoftBox/sim_viewer_boa.html");
        }
        return new double[]{ velFitX, avgBound, netX };
    }

    static void assembleCanonical(RigidRodBody b, int m, double ax, double ht, double leverLen) {
        int rod = 3 * m, lever = 3 * m + 1, head = 3 * m + 2;
        double rc = ANCHOR_Z + 0.5 * MotorStore.ROD_LEN;
        double lc = ANCHOR_Z + MotorStore.ROD_LEN + 0.5 * leverLen;
        double hc = ANCHOR_Z + MotorStore.ROD_LEN + leverLen + 0.5 * MotorStore.HEAD_LEN;
        b.setCoord(rod, (float) ax, 0f, (float) rc); b.setUVec(rod, 0f, 0f, 1f); b.setYVec(rod, 1f, 0f, 0f);
        b.setCoord(lever, (float) ax, 0f, (float) lc); b.setUVec(lever, 0f, 0f, 1f); b.setYVec(lever, 1f, 0f, 0f);
        b.setCoord(head, (float) ax, 0f, (float) hc); b.setUVec(head, (float) Math.sin(ht), 0f, (float) Math.cos(ht)); b.setYVec(head, 1f, 0f, 0f);
    }

    /** Free motors whose head tip is within reach of the filament segment BIND (boundSeg=0, bindArc=closest arc)
     *  and are reset to the canonical bound pose (stereospecific ⇒ consistent +x stroke), nucleotide → uncocked. */
    static void glideBind(Scene sc, FloatArray cC, FloatArray cU, FloatArray cY, double reach, int nB) {
        MotorStore mot = sc.mot; RigidRodBody b = mot.body; FilamentStore f = sc.fil;
        double e1x = f.end1.get(0), e1y = f.end1.get(f.n + 0), e1z = f.end1.get(2 * f.n + 0);
        double e2x = f.end2.get(0), e2y = f.end2.get(f.n + 0), e2z = f.end2.get(2 * f.n + 0);
        double rx = e2x - e1x, ry = e2y - e1y, rz = e2z - e1z; double denom = rx * rx + ry * ry + rz * rz;
        int nMcur = mot.counts.get(0);
        for (int m = 0; m < nMcur; m++) {
            if (mot.boundSeg.get(m) != MotorStore.FREE_BINDABLE) continue;
            double tx = mot.head.get(m), ty = mot.head.get(nMcur + m), tz = mot.head.get(2 * nMcur + m);   // head tip
            double u = ((tx - e1x) * rx + (ty - e1y) * ry + (tz - e1z) * rz) / denom;
            if (u < 0) u = 0; if (u > 1) u = 1;
            double cx = e1x + u * rx, cy = e1y + u * ry, cz = e1z + u * rz;
            double d2 = (tx - cx) * (tx - cx) + (ty - cy) * (ty - cy) + (tz - cz) * (tz - cz);
            if (d2 > reach * reach) continue;
            mot.boundSeg.set(m, 0);
            mot.bindArc.set(m, (float) (u * Math.sqrt(denom)));   // arc from end1
            mot.nucleotideState.set(m, MotorStore.NUC_ADPPI);     // bind uncocked
            if (NORESET) continue;
            for (int sub = 3 * m; sub < 3 * m + 3; sub++) {       // reset to canonical pose (stereospecific)
                b.coord.set(sub, cC.get(sub)); b.coord.set(nB + sub, cC.get(nB + sub)); b.coord.set(2 * nB + sub, cC.get(2 * nB + sub));
                b.uVec.set(sub, cU.get(sub)); b.uVec.set(nB + sub, cU.get(nB + sub)); b.uVec.set(2 * nB + sub, cU.get(2 * nB + sub));
                b.yVec.set(sub, cY.get(sub)); b.yVec.set(nB + sub, cY.get(nB + sub)); b.yVec.set(2 * nB + sub, cY.get(2 * nB + sub));
            }
        }
    }

    static void writeGlideFrame(String dir, int frame, double t, Scene sc) {
        FilamentStore f = sc.fil; RigidRodBody b = sc.mot.body; int nMot = sc.mot.nMotors;
        StringBuilder sb = new StringBuilder(4096);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":1.2,\"yDim\":0.3,\"zDim\":0.3}", frame, t));
        sb.append(",\"segments\":[");
        sb.append(String.format(java.util.Locale.US, "{\"id\":0,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0,\"isBarbedEnd\":true}",
            f.end1.get(0), f.end1.get(f.n), f.end1.get(2 * f.n), f.end2.get(0), f.end2.get(f.n), f.end2.get(2 * f.n), Constants.radius));
        sb.append("],\"myosins\":[");
        for (int m = 0; m < nMot; m++) {
            if (m > 0) sb.append(',');
            int rod = 3 * m, lever = 3 * m + 1, head = 3 * m + 2;
            double hx = b.coordX(head), hy = b.coordY(head), hz = b.coordZ(head);
            boolean bound = sc.mot.boundSeg.get(m) >= 0;
            String state = sc.mot.nucleotideState.get(m) != MotorStore.NUC_ADPPI ? "ATP" : "ADPPi";
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"bound\":%b,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
                + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
                + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
                m, bound, b.end1X(rod), b.end1Y(rod), b.end1Z(rod), b.end2X(rod), b.end2Y(rod), b.end2Z(rod), MotorStore.ROD_R,
                b.end1X(lever), b.end1Y(lever), b.end1Z(lever), b.end2X(lever), b.end2Y(lever), b.end2Z(lever), MotorStore.LEVER_R,
                hx, hy, hz, hx, hy, hz, HEAD_SPHERE_R, state));
        }
        sb.append("]}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US, "frame_%06d.json", frame)), sb.toString()); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static void writeFrame(int frame, double t, Scene sc) {
        FilamentStore f = sc.fil; RigidRodBody b = sc.mot.body;
        StringBuilder sb = new StringBuilder(512);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":0.12,\"yDim\":0.08,\"zDim\":0.2}", frame, t));
        sb.append(",\"segments\":[");
        for (int s = 0; s < f.n; s++) {
            if (s > 0) sb.append(',');
            // uVec=+x ⇒ end2 is the +x (BARBED / plus) end ⇒ mark it so the viewer's "Barbed ends" "+" sprite shows there
            sb.append(String.format(java.util.Locale.US, "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0,\"isBarbedEnd\":true}",
                s, f.end1.get(s), f.end1.get(f.n + s), f.end1.get(2 * f.n + s), f.end2.get(s), f.end2.get(f.n + s), f.end2.get(2 * f.n + s), Constants.radius));
        }
        int rod = 0, lever = 1, head = 2;
        double hx = b.coordX(head), hy = b.coordY(head), hz = b.coordZ(head);   // head-sphere CENTER (its own body)
        boolean bound = sc.mot.boundSeg.get(0) >= 0;
        String state = sc.mot.nucleotideState.get(0) != MotorStore.NUC_ADPPI ? "ATP" : "ADPPi";
        sb.append("],\"myosins\":[");
        sb.append(String.format(java.util.Locale.US,
            "{\"id\":0,\"bound\":%b,\"rod\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"invisible\":false},"
            + "\"lever\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g},"
            + "\"motor\":{\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.4g,\"state\":\"%s\"}}",
            bound, b.end1X(rod), b.end1Y(rod), b.end1Z(rod), b.end2X(rod), b.end2Y(rod), b.end2Z(rod), MotorStore.ROD_R,
            b.end1X(lever), b.end1Y(lever), b.end1Z(lever), b.end2X(lever), b.end2Y(lever), b.end2Z(lever), MotorStore.LEVER_R,
            hx, hy, hz, hx, hy, hz, HEAD_SPHERE_R, state));   // head rendered as a SPHERE of real extent at its center
        sb.append("]}");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(VIZ, String.format(java.util.Locale.US, "frame_%06d.json", frame)), sb.toString()); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
