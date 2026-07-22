package softbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SM6 — QUASISTATIC FORCE-EXTENSION characterization of the explicit motor (CPU-only, deterministic).
 *
 * <p>Imposes prescribed displacements on the BOUND filament with the chemistry FROZEN, relaxes the
 * motor to mechanical equilibrium at each displacement, and reads the reaction force, tangent
 * stiffness, and the per-component elastic energy budget. This is the assay that explains WHY the
 * SM4 assisting arm saturates on {@code explicit-s2-l40}.
 *
 * <h2>Method</h2>
 * <ol>
 *   <li>Build a bound motor via the centralized {@link TwoBodyConverterMotor#buildBoundMotor}.</li>
 *   <li>Install canonical rates ({@code initChem4a}), set the prepared nucleotide state and its
 *       converter target {@code thetaS}, then <b>never call the chemistry kernel again</b> —
 *       chemistry is frozen for the whole sweep, and {@code thetaS} is pinned.</li>
 *   <li>Settle deterministically (Brownian OFF) at zero displacement.</li>
 *   <li>For each displacement d: pin the filament with the existing {@code filFullClamp}
 *       (position AND orientation) at {@code c0 + d*axis}, relax, then record.</li>
 * </ol>
 *
 * <h2>Sign convention (same as SM4)</h2>
 * {@code +d} displaces the filament toward the BARBED end ({@code +bhat}) — this TENSIONS the
 * cross-bridge (the SM4 "opposing" sense). {@code -d} is the COMPRESSIVE / assisting sense, where
 * the explicit S2 beam is expected to buckle. Reaction force is reported as the axial component of
 * the F8 force the motor exerts ON the filament ({@code bondData[6..8]}), projected on the sweep
 * axis, in pN.
 *
 * <h2>Energy accounting</h2>
 * F8 is a zero-rest linear spring, so {@code U = 1/2 |F| |x|} exactly — computed from the force and
 * extension directly, which is unit-safe. Converter and binding torsional energies use the code
 * constants ({@code kconvCode}, {@code kbindCode}, both SI J/rad^2). Beam stretch uses
 * {@code 1/2 g4ks dl^2} (g4ks is SI N/m) and beam bending reuses
 * {@link TwoBodyConverterMotor#s2BendEnergy} verbatim. No mechanics parameter is retuned.
 *
 * <pre>
 *   ./scripts/run_sm6.sh -singlehead        # axial + transverse sweeps, all fixtures/states
 *   ./scripts/run_sm6.sh -hysteresis        # loading/unloading cycle + recovery
 * </pre>
 */
public final class Sm6ForceExtensionHarness {

    static final double DT = 2.5e-6;

    // ---------------------------------------------------------------- one measured point
    static final class Pt {
        String fixture, state, axis, mode;
        double dNm;                       // imposed displacement (nm)
        double forcePn;                   // reaction force on the filament along the axis (pN)
        double f8MagPn, f8ExtNm;          // |F8| and its extension
        double forceDotFilPn;             // the SM4 load channel, for cross-referencing
        double uF8, uConv, uBind, uStretch, uBend, uTotal;   // SI J
        double contourNm, e2eNm, axStrain, bendDeg, maxCurv, compressionNm;
        double phiDeg, psiDeg, thetaDeg;
        int solverFailures, invalid;
        int sweepIndex;
    }

    public static void main(String[] args) {
        String outDir = "RUN_LOGS/motor_validation/sm6_force_extension";
        String rev = "unknown", buildId = "unknown";
        boolean doSingle = false, doHyst = false;
        int relax = 4000;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-singlehead" -> doSingle = true;
                case "-hysteresis" -> doHyst = true;
                case "-outdir" -> outDir = args[++i];
                case "-rev" -> rev = args[++i];
                case "-buildid" -> buildId = args[++i];
                case "-relax" -> relax = Integer.parseInt(args[++i]);
                default -> { }
            }
        }
        if (!doSingle && !doHyst) { doSingle = true; doHyst = true; }

        System.out.println("=== SoftBox — SM6: quasistatic force-extension (CPU-only, deterministic) ===");
        if (ExplicitHmmDimerGpuParams.RUPTURE_MODE != 0 || ExplicitHmmDimerGpuParams.EMERGENCY_ON) {
            System.out.println("  *** ABORT: rupture failsafe active ***"); System.exit(3);
        }
        System.out.printf(Locale.US, "# dt=%.3e  relax=%d steps/point  Brownian=OFF  chemistry=FROZEN%n", DT, relax);
        System.out.printf(Locale.US, "# rev=%s buildId=%s%n", rev, buildId);
        System.out.println("# sign: +d = toward BARBED (+bhat) = TENSION (SM4 'opposing');  -d = COMPRESSION (SM4 'assisting')");

        try { Files.createDirectories(Path.of(outDir, "plots")); }
        catch (IOException e) { throw new UncheckedIOException(e); }

        List<Pt> all = new ArrayList<>();
        MotorModel[] models = { MotorModel.EXPLICIT_S2_L40, MotorModel.FIXED_ANCHOR, MotorModel.CALIBRATED_S2_L40 };
        Sm4ForceLifetimeHarness.Prep[] states = {
            Sm4ForceLifetimeHarness.Prep.RIGOR, Sm4ForceLifetimeHarness.Prep.ADP, Sm4ForceLifetimeHarness.Prep.CYCLING };

        if (doSingle) {
            double[] lad = axialLadder();
            for (MotorModel m : models)
                for (Sm4ForceLifetimeHarness.Prep p : states)
                    all.addAll(sweep(m, p, "axial", lad, relax, "fresh"));
            // transverse: two orthogonal directions, ADP only, explicit vs fixed-anchor
            double[] tl = transverseLadder();
            for (MotorModel m : new MotorModel[]{ MotorModel.EXPLICIT_S2_L40, MotorModel.FIXED_ANCHOR }) {
                all.addAll(sweep(m, Sm4ForceLifetimeHarness.Prep.ADP, "transverse_econv", tl, relax, "fresh"));
                all.addAll(sweep(m, Sm4ForceLifetimeHarness.Prep.ADP, "transverse_eup", tl, relax, "fresh"));
            }
        }
        if (doHyst) {
            double[] cyc = hysteresisLadder();
            for (MotorModel m : new MotorModel[]{ MotorModel.EXPLICIT_S2_L40, MotorModel.FIXED_ANCHOR })
                all.addAll(sweep(m, Sm4ForceLifetimeHarness.Prep.ADP, "axial", cyc, relax, "cycle"));
        }

        writeCsvs(all, outDir, rev, buildId);
        System.out.printf(Locale.US, "%n# SM6 done: %d points → %s%n", all.size(), outDir);
        System.out.println("# → python3 scripts/sm6_analysis.py " + outDir);
    }

    // ---------------------------------------------------------------- ladders
    /** Dense near 0 and throughout compression (where buckling is expected); coarser in far tension. */
    static double[] axialLadder() {
        List<Double> v = new ArrayList<>();
        for (double d = -20.0; d < -6.0; d += 0.5) v.add(d);
        for (double d = -6.0; d < -1.0; d += 0.25) v.add(d);
        for (double d = -1.0; d <= 1.0; d += 0.125) v.add(d);
        for (double d = 1.25; d <= 6.0; d += 0.25) v.add(d);
        for (double d = 6.5; d <= 20.0; d += 0.5) v.add(d);
        return v.stream().mapToDouble(Double::doubleValue).toArray();
    }

    static double[] transverseLadder() {
        List<Double> v = new ArrayList<>();
        for (double d = -10.0; d <= 10.0; d += 0.25) v.add(d);
        return v.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** 0 → +20 → 0 → −20 → 0, for hysteresis and recovery. */
    static double[] hysteresisLadder() {
        List<Double> v = new ArrayList<>();
        for (double d = 0; d <= 20.0; d += 0.5) v.add(d);
        for (double d = 19.5; d >= -20.0; d -= 0.5) v.add(d);
        for (double d = -19.5; d <= 0.0; d += 0.5) v.add(d);
        return v.stream().mapToDouble(Double::doubleValue).toArray();
    }

    // ---------------------------------------------------------------- the sweep
    /** mode "fresh": an independent equilibrium at each displacement (path-independent curve).
     *  mode "cycle": one continuous history (previous equilibrium is the next starting point) ⇒ hysteresis. */
    static List<Pt> sweep(MotorModel model, Sm4ForceLifetimeHarness.Prep prep, String axisName,
                          double[] ladder, int relax, String mode) {
        List<Pt> out = new ArrayList<>();
        TwoBodyConverterMotor.Cmot shared = mode.equals("cycle") ? prepared(model, prep, relax) : null;
        double[] c0 = null, axis = null, u0 = null, y0 = null;
        if (shared != null) { c0 = coord(shared); axis = axisOf(shared, axisName);
                              u0 = uvec(shared); y0 = yvec(shared); }

        for (int i = 0; i < ladder.length; i++) {
            double d = ladder[i];
            TwoBodyConverterMotor.Cmot cm;
            if (shared != null) { cm = shared; }
            else {
                cm = prepared(model, prep, relax);
                c0 = coord(cm); axis = axisOf(cm, axisName); u0 = uvec(cm); y0 = yvec(cm);
            }
            // impose the displacement with the existing full position+orientation clamp
            cm.filFullClamp = true;
            cm.clampCoord = new double[]{ c0[0] + d * 1e-3 * axis[0],
                                          c0[1] + d * 1e-3 * axis[1],
                                          c0[2] + d * 1e-3 * axis[2] };
            cm.clampU = u0.clone(); cm.clampY = y0.clone();
            int fails = relaxDeterministic(cm, model, prep, relax);
            Pt p = measure(cm, model, prep, axisName, mode, d, axis);
            p.solverFailures = fails; p.sweepIndex = i;
            out.add(p);
        }
        System.out.printf(Locale.US, "#   %-18s %-8s %-17s %-6s : %3d pts%n",
                model.id(), prep.id, axisName, mode, out.size());
        return out;
    }

    /** Build + prepare + settle, chemistry FROZEN throughout (the cycle kernel is never called). */
    static TwoBodyConverterMotor.Cmot prepared(MotorModel model, Sm4ForceLifetimeHarness.Prep prep, int relax) {
        TwoBodyConverterMotor.Cmot cm = TwoBodyConverterMotor.buildBoundMotor(model, DT);
        TwoBodyConverterMotor.initChem4a(cm, DT, true);
        cm.mot.nucleotideState.set(0, prep.nuc);
        cm.thetaS = prep.thetaS();
        cm.mot.boundSeg.set(0, 0);
        relaxDeterministic(cm, model, prep, relax);
        return cm;
    }

    /** Deterministic (Brownian OFF) relaxation. thetaS is re-pinned every step so the frozen chemical
     *  state cannot drift. Returns the count of non-finite (solver-failure) observations. */
    static int relaxDeterministic(TwoBodyConverterMotor.Cmot cm, MotorModel model,
                                  Sm4ForceLifetimeHarness.Prep prep, int steps) {
        int fails = 0;
        for (int t = 0; t < steps; t++) {
            cm.thetaS = prep.thetaS();                       // frozen chemistry ⇒ frozen converter target
            switch (model) {
                case EXPLICIT_S2_L40 -> TwoBodyConverterMotor.stepS2(cm, t, 0, false);
                case CALIBRATED_S2_L40 -> TwoBodyConverterMotor.stepSup(cm, t, 0, false);
                default -> TwoBodyConverterMotor.stepC(cm, t, 0);
            }
            cm.mot.forceDotFil.set(0, cm.bondData.get(12));
            if (!Double.isFinite(cm.bondData.get(0)) || !Double.isFinite(cm.phi) || !Double.isFinite(cm.psi)) fails++;
        }
        return fails;
    }

    // ---------------------------------------------------------------- observables
    static Pt measure(TwoBodyConverterMotor.Cmot cm, MotorModel model, Sm4ForceLifetimeHarness.Prep prep,
                      String axisName, String mode, double dNm, double[] axis) {
        Pt p = new Pt();
        p.fixture = model.id(); p.state = prep.id; p.axis = axisName; p.mode = mode; p.dNm = dNm;

        // reaction force the motor exerts ON the filament (bondData[6..8]), projected on the sweep axis
        double[] fSeg = { cm.bondData.get(6), cm.bondData.get(7), cm.bondData.get(8) };
        p.forcePn = TwoBodyConverterMotor.dot(fSeg, axis) * 1e12;
        p.forceDotFilPn = cm.bondData.get(12) * 1e12;

        double[] fHead = { cm.bondData.get(0), cm.bondData.get(1), cm.bondData.get(2) };
        double fMag = Math.sqrt(TwoBodyConverterMotor.dot(fHead, fHead));
        p.f8MagPn = fMag * 1e12;
        // F8 is a ZERO-REST linear spring: |F| = k|x| ⇒ x = |F|/k, U = 1/2 |F| x  (unit-safe)
        double ext_m = (cm.kF8Code > 0) ? (fMag / (cm.kF8Code * 1e6)) : 0.0;   // kF8Code is N per µm ⇒ N/m = *1e6
        p.f8ExtNm = ext_m * 1e9;
        p.uF8 = 0.5 * fMag * ext_m;

        double theta = cm.psi - cm.phi;
        p.phiDeg = Math.toDegrees(cm.phi); p.psiDeg = Math.toDegrees(cm.psi); p.thetaDeg = Math.toDegrees(theta);
        p.uConv = 0.5 * cm.kconvCode * (theta - cm.thetaS) * (theta - cm.thetaS);
        p.uBind = 0.5 * cm.kbindCode * (cm.psi - cm.psiActin) * (cm.psi - cm.psiActin);

        if (cm.g4On && cm.g4Node != null) {
            double ks = cm.g4ks, l0m = cm.g4l0 * 1e-6, us = 0;
            for (int i = 0; i < cm.g4M; i++) {
                double[] b = TwoBodyConverterMotor.sub(cm.g4Node[i + 1], cm.g4Node[i]);
                double len_m = Math.sqrt(TwoBodyConverterMotor.dot(b, b)) * 1e-6;
                us += 0.5 * ks * (len_m - l0m) * (len_m - l0m);
            }
            p.uStretch = us;
            p.uBend = TwoBodyConverterMotor.s2BendEnergy(cm, cm.g4Node);
            double[] g = TwoBodyConverterMotor.s2Geom(cm);
            p.contourNm = g[0]; p.e2eNm = g[1]; p.axStrain = g[2];
            p.bendDeg = g[3]; p.maxCurv = g[4]; p.compressionNm = g[5];
        }
        p.uTotal = p.uF8 + p.uConv + p.uBind + p.uStretch + p.uBend;
        if (!Double.isFinite(p.forcePn) || !Double.isFinite(p.uTotal)) p.invalid = 1;
        return p;
    }

    static double[] coord(TwoBodyConverterMotor.Cmot cm) {
        return new double[]{ cm.fil.coordX(0), cm.fil.coordY(0), cm.fil.coordZ(0) };
    }
    static double[] uvec(TwoBodyConverterMotor.Cmot cm) {
        return new double[]{ cm.fil.uVecX(0), cm.fil.uVecY(0), cm.fil.uVecZ(0) };
    }
    static double[] yvec(TwoBodyConverterMotor.Cmot cm) {
        return new double[]{ cm.fil.yVec.get(0), cm.fil.yVec.get(1), cm.fil.yVec.get(2) };
    }
    static double[] axisOf(TwoBodyConverterMotor.Cmot cm, String name) {
        return switch (name) {
            case "axial" -> cm.bhat.clone();
            case "transverse_econv" -> cm.econv.clone();
            case "transverse_eup" -> cm.eup.clone();
            default -> throw new IllegalArgumentException("axis " + name);
        };
    }

    // ---------------------------------------------------------------- output
    static void writeCsvs(List<Pt> all, String outDir, String rev, String buildId) {
        StringBuilder fe = new StringBuilder(
            "fixture,state,axis,mode,sweep_index,disp_nm,force_pn,force_dot_fil_pn,f8_mag_pn,f8_ext_nm,"
          + "phi_deg,psi_deg,theta_deg,contour_nm,e2e_nm,axial_strain,bend_deg,max_curv_per_um,compression_nm,"
          + "solver_failures,invalid,code_rev,build_id\n");
        StringBuilder en = new StringBuilder(
            "fixture,state,axis,mode,disp_nm,u_f8_J,u_conv_J,u_bind_J,u_stretch_J,u_bend_J,u_total_J,u_total_kT\n");
        double kT = 4.141947e-21;
        for (Pt p : all) {
            fe.append(String.format(Locale.US,
                "%s,%s,%s,%s,%d,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%d,%d,%s,%s%n",
                p.fixture, p.state, p.axis, p.mode, p.sweepIndex, p.dNm, p.forcePn, p.forceDotFilPn,
                p.f8MagPn, p.f8ExtNm, p.phiDeg, p.psiDeg, p.thetaDeg, p.contourNm, p.e2eNm, p.axStrain,
                p.bendDeg, p.maxCurv, p.compressionNm, p.solverFailures, p.invalid, rev, buildId));
            en.append(String.format(Locale.US, "%s,%s,%s,%s,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g%n",
                p.fixture, p.state, p.axis, p.mode, p.dNm, p.uF8, p.uConv, p.uBind, p.uStretch, p.uBend,
                p.uTotal, p.uTotal / kT));
        }
        write(Path.of(outDir, "force_extension_singlehead.csv"), fe.toString());
        write(Path.of(outDir, "component_energy.csv"), en.toString());
    }

    static void write(Path p, String s) {
        try { Files.createDirectories(p.getParent()); Files.writeString(p, s); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private Sm6ForceExtensionHarness() { }
}
