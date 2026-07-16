package softbox;

import java.io.PrintStream;
import java.util.Locale;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;

/**
 * Canonical registry→device parameter packer for the two-body canonical motor Step-7 kernels
 * ({@link TwoBodyGpuKernels}), plus the CLOSED GPU gate.
 *
 * <p><b>Single source of device constants.</b> Every packer reads ONLY from a {@link TwoBodyConverterMotor.Cmot}
 * that was built by {@link TwoBodyConverterMotor#buildBoundMotor} — i.e. from the {@link MotorModel} registry
 * (the frozen descriptors). Device buffers therefore inherit the registry constants; there is no independent
 * parameter set. This is the one packer used by both the CPU-runner validation and any future device launch.
 *
 * <p><b>Closed gate.</b> {@link #DEVICE_VALIDATED} is the single enabling flag; it is {@code false} until the
 * device gates (T2/T6/T7 + measurement-output, and the Phase-5 beam microbench for the explicit model) pass.
 * {@link #refuseGpu} prints a clear, model-named refusal; {@link #requireDeviceValidated} is the programmatic
 * guard (always throws while {@code DEVICE_VALIDATED==false}). Neither ever swaps the model or silently falls
 * back to CPU. This is distinct from {@link MotorModel#gpuSupported()} (a capability flag).
 */
public final class MotorGpuParams {
    private MotorGpuParams() {}

    /** The single enabling flag for the device path. Flip to true ONLY when all device gates have passed. */
    public static final boolean DEVICE_VALIDATED = false;

    // ================================================================================================
    // PARAM / BUFFER PACKERS  (planar-SoA, N=1 single-motor; index = comp*N + m, here N=1 ⇒ index = comp)
    // ================================================================================================

    /** Base geometry + stiffness + drag params common to fixedStep and calibratedStep (indices 0..11). */
    private static void putBaseParamsF(FloatArray p, TwoBodyConverterMotor.Cmot cm) {
        p.set(0, (float) cm.lb);
        p.set(1, (float) cm.rF8[0]);   p.set(2, (float) cm.rF8[1]);
        p.set(3, (float) cm.rConv[0]); p.set(4, (float) cm.rConv[1]);
        p.set(5, (float) cm.kF8Code);  p.set(6, (float) cm.kconvCode); p.set(7, (float) cm.kbindCode);
        p.set(8, (float) cm.gammaP);   p.set(9, (float) cm.gammaPhi);  p.set(10, (float) cm.gammaPsi);
        p.set(11, (float) cm.dt);
    }

    /** FIXED_ANCHOR params buffer (12 elems, indices 0..11 — the base block; stepC ignores the sup block). */
    public static FloatArray packFixed(TwoBodyConverterMotor.Cmot cm) {
        FloatArray p = new FloatArray(12);
        putBaseParamsF(p, cm);
        return p;
    }

    /** CALIBRATED_S2_L40 params buffer (26 elems): base block 0..11 + the anisotropic sup-tail scalars 12..25. */
    public static FloatArray packCalibrated(TwoBodyConverterMotor.Cmot cm) {
        FloatArray p = new FloatArray(26);
        putBaseParamsF(p, cm);
        p.set(12, (float) cm.supKsoftAx); p.set(13, (float) cm.supKtautAx); p.set(14, (float) cm.supDelta);
        p.set(15, (float) cm.supKsoftTr); p.set(16, (float) cm.supKfeTr);   p.set(17, (float) cm.supRmax);
        p.set(18, (float) cm.supKfloor);  p.set(19, (float) cm.supSmoothAx); p.set(20, (float) cm.supSmoothTr);
        p.set(21, (float) cm.supCompFrac); p.set(22, (float) cm.supFloorZ);
        p.set(23, (float) cm.supBuckleCrit); p.set(24, (float) cm.supKcompPost); p.set(25, (float) cm.supSmoothBuck);
        return p;
    }

    /** CALIBRATED_S2_L40 movable-pivot geometry: supP0(0..2), supUL(3..5), supUT1(6..8), supUT2(9..11). */
    public static FloatArray packSupGeom(TwoBodyConverterMotor.Cmot cm) {
        FloatArray g = new FloatArray(12);
        setVecF(g, 0, cm.supP0); setVecF(g, 3, cm.supUL); setVecF(g, 6, cm.supUT1); setVecF(g, 9, cm.supUT2);
        return g;
    }

    /** EXPLICIT_S2_L40 params buffer (17 elems): geometry + stiffness + beam (ks,l0,kb) + floor + node drag. */
    public static DoubleArray packExplicit(TwoBodyConverterMotor.Cmot cm) {
        DoubleArray p = new DoubleArray(17);
        p.set(0, cm.lb);
        p.set(1, cm.rF8[0]);   p.set(2, cm.rF8[1]);
        p.set(3, cm.rConv[0]); p.set(4, cm.rConv[1]);
        p.set(5, cm.kF8Code);  p.set(6, cm.kconvCode); p.set(7, cm.kbindCode);
        p.set(8, cm.gammaPhi); p.set(9, cm.gammaPsi);  p.set(10, cm.dt);
        p.set(11, cm.g4ks);    p.set(12, cm.g4l0);     p.set(13, cm.g4kb);
        p.set(14, cm.g4floorZ); p.set(15, cm.g4kfloor); p.set(16, cm.g4gammaNode);
        return p;
    }

    /** FLOAT32 frame buffer (9 elems): bhat(0..2), econv(3..5), eup(6..8). */
    public static FloatArray packFrameFloat(TwoBodyConverterMotor.Cmot cm) {
        FloatArray f = new FloatArray(9);
        setVecF(f, 0, cm.bhat); setVecF(f, 3, cm.econv); setVecF(f, 6, cm.eup);
        return f;
    }

    /** DOUBLE frame buffer for the explicit beam (15 elems): bhat, econv, eup, g4E, g4Tan. */
    public static DoubleArray packFrameExplicit(TwoBodyConverterMotor.Cmot cm) {
        DoubleArray f = new DoubleArray(15);
        setVecD(f, 0, cm.bhat); setVecD(f, 3, cm.econv); setVecD(f, 6, cm.eup); setVecD(f, 9, cm.g4E); setVecD(f, 12, cm.g4Tan);
        return f;
    }

    public static FloatArray  packQFloat(TwoBodyConverterMotor.Cmot cm)  { FloatArray q = new FloatArray(4);  q.set(0,(float)cm.phi); q.set(1,(float)cm.psi); q.set(2,(float)cm.thetaS); q.set(3,(float)cm.psiActin); return q; }
    public static DoubleArray packQDouble(TwoBodyConverterMotor.Cmot cm) { DoubleArray q = new DoubleArray(4); q.set(0,cm.phi); q.set(1,cm.psi); q.set(2,cm.thetaS); q.set(3,cm.psiActin); return q; }
    public static FloatArray  packAFloat(TwoBodyConverterMotor.Cmot cm)  { FloatArray a = new FloatArray(3);  setVecF(a,0,cm.A); return a; }
    public static DoubleArray packNodes(TwoBodyConverterMotor.Cmot cm)   { int M=cm.g4M; DoubleArray n=new DoubleArray(3*(M+1)); for(int j=0;j<=M;j++){ n.set(3*j,cm.g4Node[j][0]); n.set(3*j+1,cm.g4Node[j][1]); n.set(3*j+2,cm.g4Node[j][2]); } return n; }
    /** F8 head force (bondForces output) from cm.bondData[0..2]. */
    public static FloatArray  packF8Float(TwoBodyConverterMotor.Cmot cm)  { FloatArray f=new FloatArray(3);  f.set(0,cm.bondData.get(0)); f.set(1,cm.bondData.get(1)); f.set(2,cm.bondData.get(2)); return f; }
    public static DoubleArray packF8Double(TwoBodyConverterMotor.Cmot cm) { DoubleArray f=new DoubleArray(3); f.set(0,cm.bondData.get(0)); f.set(1,cm.bondData.get(1)); f.set(2,cm.bondData.get(2)); return f; }

    static void setVecF(FloatArray a, int off, double[] v)  { a.set(off,(float)v[0]); a.set(off+1,(float)v[1]); a.set(off+2,(float)v[2]); }
    static void setVecD(DoubleArray a, int off, double[] v) { a.set(off,v[0]); a.set(off+1,v[1]); a.set(off+2,v[2]); }

    // ================================================================================================
    // T0 GATE: registry→device parameter sourcing is consistent, finite, and correctly sized.
    // ================================================================================================
    /** For each of the 3 models: assert the frozen registry↔code params (exact ==), build via the registry,
     *  pack the device buffers, and assert every buffer is the right size and all-finite. Throws on any failure. */
    public static void verifyRegistrySourced(double dt) {
        for (MotorModel m : new MotorModel[]{ MotorModel.FIXED_ANCHOR, MotorModel.CALIBRATED_S2_L40, MotorModel.EXPLICIT_S2_L40 }) {
            TwoBodyConverterMotor.assertFrozenParamsConsistent();   // exact registry↔code gate (package-private static)
            TwoBodyConverterMotor.Cmot cm = TwoBodyConverterMotor.buildBoundMotor(m, dt);
            switch (m) {
                case EXPLICIT_S2_L40 -> {
                    checkD(packExplicit(cm), 17, m, "explicit-params");
                    checkD(packFrameExplicit(cm), 15, m, "explicit-frame");
                    checkD(packNodes(cm), 3 * (cm.g4M + 1), m, "explicit-nodes");
                    checkD(packQDouble(cm), 4, m, "q");
                }
                case CALIBRATED_S2_L40 -> {
                    checkF(packCalibrated(cm), 26, m, "calibrated-params");
                    checkF(packSupGeom(cm), 12, m, "calibrated-supGeom");
                    checkF(packFrameFloat(cm), 9, m, "frame");
                    checkF(packQFloat(cm), 4, m, "q");
                    checkF(packAFloat(cm), 3, m, "A");
                }
                case FIXED_ANCHOR -> {
                    checkF(packFixed(cm), 12, m, "fixed-params");
                    checkF(packFrameFloat(cm), 9, m, "frame");
                    checkF(packQFloat(cm), 4, m, "q");
                    checkF(packAFloat(cm), 3, m, "A");
                }
            }
        }
    }
    private static void checkF(FloatArray a, int n, MotorModel m, String what) {
        if (a.getSize() != n) throw new IllegalStateException("T0 FAIL " + m.id() + " " + what + ": size " + a.getSize() + " != " + n);
        for (int i = 0; i < n; i++) if (!Float.isFinite(a.get(i))) throw new IllegalStateException("T0 FAIL " + m.id() + " " + what + "[" + i + "] not finite: " + a.get(i));
    }
    private static void checkD(DoubleArray a, int n, MotorModel m, String what) {
        if (a.getSize() != n) throw new IllegalStateException("T0 FAIL " + m.id() + " " + what + ": size " + a.getSize() + " != " + n);
        for (int i = 0; i < n; i++) if (!Double.isFinite(a.get(i))) throw new IllegalStateException("T0 FAIL " + m.id() + " " + what + "[" + i + "] not finite: " + a.get(i));
    }

    // ================================================================================================
    // CLOSED GPU GATE
    // ================================================================================================
    /** The model-named refusal message (missing device gates, capability, and the never-swap policy). */
    public static String refusalMessage(MotorModel m) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "REFUSED: -gpu requested for motor model '%s' (%s).%n", m.id(), m.displayName()));
        if (m.gpuSupported()) {
            sb.append("  This model is GPU-CAPABLE but NOT device-validated yet. Missing device gates:\n");
            sb.append("    - T2 CSR-gather identity\n");
            sb.append("    - T6 ensemble-within-SEM + CPU-double arbiter\n");
            sb.append("    - T7 half-dt\n");
            sb.append("    - measurement-output\n");
            sb.append("  Run on CPU (drop -gpu). The GPU path opens only once DEVICE_VALIDATED (all gates pass).\n");
        } else {
            sb.append("  This model has no GPU implementation (CPU-only)");
            if (m == MotorModel.EXPLICIT_S2_L40) sb.append(" — pending Phase-5 beam microbench");
            sb.append(".\n");
            sb.append("  Run on CPU (drop -gpu). If you want the GPU-friendly surrogate, explicitly select\n");
            sb.append("    -motor calibrated-s2-l40  (a deliberate user choice — the model is NEVER auto-swapped).\n");
        }
        sb.append("  The motor model is NEVER silently changed, and never silently falls back to CPU.");
        return sb.toString();
    }

    /** Print the clear, model-named GPU refusal (no swap, no silent CPU fallback). */
    public static void refuseGpu(MotorModel m, PrintStream out) { out.println(refusalMessage(m)); }

    /** Programmatic guard for any future {@code -gpu} wiring: ALWAYS throws while {@link #DEVICE_VALIDATED}==false. */
    public static void requireDeviceValidated(MotorModel m) {
        if (!DEVICE_VALIDATED) throw new IllegalStateException(refusalMessage(m));
    }
}
