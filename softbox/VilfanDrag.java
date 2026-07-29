package softbox;

/**
 * WHOLE-FILAMENT hydrodynamic drag for the Vilfan overdamped reference (Stage 0 audit).
 * <p>
 * This is the ONE element SoftBox supplies to this study. It is a re-expression, with viscosity as
 * an explicit argument, of the slender-body rod drag in
 * {@code DragTensorSystem.rodDragSI(lengthUm, radiusUm)} — itself a line-for-line port of BoA-v1ref
 * {@code FilSegment.calculateProperties():420-435}:
 * <pre>
 *   bTGx = (2 pi aeta LM) / (log(LM/(2 RM)) + aParallel)      axial   translation  [N s / m]
 *   bTGy = (4 pi aeta LM) / (log(LM/(2 RM)) + aOrthog)        transverse translation
 *   bRGx =  4 pi aeta RM^2 LM                                 ROLL about the long axis [N m s / rad]
 *   bRGy = (pi aeta LM^3) / (3 (log(LM/(2 RM)) + aTurning))   tumbling about a transverse axis
 * </pre>
 * with {@code aParallel = -0.20}, {@code aOrthog = 0.84}, {@code aTurning = -0.662}
 * ({@code Constants.java:49-51}, sourced to {@code FilSegment.java:89,90,91}).
 * <p>
 * <b>Why the formula is re-expressed here rather than called.</b> Two reasons, both load-bearing:
 * <ol>
 *   <li>{@code DragTensorSystem} imports {@code uk.ac.manchester.tornado.api.types.arrays.FloatArray}.
 *       This study is CPU-only and links no TornadoVM type, so it cannot reference that class.</li>
 *   <li>{@code rodDragSI} reads {@code Constants.aeta} directly, and {@code Constants.aeta} is a
 *       compile-time {@code static final} that javac INLINES — so viscosity cannot be varied at
 *       runtime through it (the standing finding recorded in CLAUDE.md's viscosity section). This
 *       study needs eta as a swept parameter, so eta is an explicit argument here.</li>
 * </ol>
 * Gate DRAG-1 checks this re-expression against a literal transcription of {@code rodDragSI}'s
 * arithmetic at {@code eta = Constants.aeta}, requiring exact agreement.
 * <p>
 * <b>The two coefficients this study uses, and the four it must not.</b> Vilfan's filament has
 * exactly two degrees of freedom, axial translation X and roll Theta, so only
 * <pre>
 *   gammaX     = bTGx   axial translation parallel to the filament
 *   gammaTheta = bRGx   ROLL about the filament's own long axis
 * </pre>
 * are used. {@code bTGy/bTGz} (transverse translation) and {@code bRGy/bRGz} (tumbling about a
 * transverse axis) are deliberately NOT used — there is no transverse or tilt degree of freedom.
 * The sphere drag {@code DragTensorSystem.sphereDragSI} (the myosin head, {@code 6 pi eta r} /
 * {@code 8 pi eta r^3}) is NOT used. Nothing here is multiplied by a segment count: both are
 * evaluated ONCE with the WHOLE filament length {@code l = 5.5 um}, not per segment.
 * <p>
 * <b>Additivity note.</b> {@code bRGx = 4 pi eta R^2 L} is linear in L, so whole-filament roll drag
 * equals the sum over segments — the whole-filament value is unambiguous. {@code bTGx} is NOT
 * additive (the logarithm is a whole-body slender-rod correction), so the whole-filament value at
 * L = 5.5 um is the correct one and differs from any per-segment sum. Using a per-segment
 * coefficient here would be an error of order {@code ln(L_seg/2R)/ln(L/2R)}; this class makes that
 * impossible by taking only a whole-filament length.
 * <p>
 * <b>Units.</b> SI internally (metres, N s/m, N m s/rad), converted once on the way out:
 * <pre>
 *   gammaX     : N s / m      x 1e3   -> pN s / nm
 *   gammaTheta : N m s / rad  x 1e21  -> pN nm s / rad
 * </pre>
 * (1 N = 1e12 pN; 1 m = 1e9 nm, so 1 N s/m = 1e12/1e9 = 1e3 pN s/nm and 1 N m = 1e12*1e9 = 1e21 pN nm.)
 */
public final class VilfanDrag {
    private VilfanDrag() {}

    /** FilSegment.java:89 via Constants.java:49 — the axial slender-body end correction. */
    public static final double A_PARALLEL = -0.20;
    /** Constants.java:38-39 — actin filament radius, microns (actinWidth 0.007 um / 2). */
    public static final double RADIUS_UM = 0.0035;
    /** Constants.aeta, Pa*s — SoftBox's compile-time default, retained only for the audit gate. */
    public static final double AETA_SOFTBOX = 0.1;
    /** The validated gliding/twirling assay reference viscosity, Pa*s (CLAUDE.md viscosity study). */
    public static final double ETA_ASSAY = 0.01;

    /** Axial (parallel-to-axis) translational drag of the WHOLE filament, SI N*s/m. */
    public static double gammaXSI(double etaPaS, double lengthUm, double radiusUm) {
        double LM = lengthUm * 1.0e-6, RM = radiusUm * 1.0e-6;
        return (2.0 * Math.PI * etaPaS * LM) / (Math.log(LM / (2.0 * RM)) + A_PARALLEL);
    }

    /** ROLL drag about the filament's own long axis, WHOLE filament, SI N*m*s/rad. */
    public static double gammaThetaSI(double etaPaS, double lengthUm, double radiusUm) {
        double LM = lengthUm * 1.0e-6, RM = radiusUm * 1.0e-6;
        return 4.0 * Math.PI * etaPaS * RM * RM * LM;
    }

    /** Axial drag in the study's working units, pN*s/nm. */
    public static double gammaXwork(double etaPaS, double lengthUm, double radiusUm) {
        return gammaXSI(etaPaS, lengthUm, radiusUm) * 1.0e3;
    }

    /** Roll drag in the study's working units, pN*nm*s/rad. */
    public static double gammaThetaWork(double etaPaS, double lengthUm, double radiusUm) {
        return gammaThetaSI(etaPaS, lengthUm, radiusUm) * 1.0e21;
    }

    /** The Stage-0 audit block, printed by {@code -drag-audit} and at the head of every run. */
    public static String audit(double etaPaS, double lengthUm, double radiusUm) {
        double LM = lengthUm * 1e-6, RM = radiusUm * 1e-6;
        double logT = Math.log(LM / (2 * RM));
        StringBuilder b = new StringBuilder();
        b.append(String.format("  filament length  l = %.4f um  = %.6g m%n", lengthUm, LM));
        b.append(String.format("  filament radius  R = %.4f um  = %.6g m   (Constants.actinWidth/2)%n", radiusUm, RM));
        b.append(String.format("  viscosity      eta = %.6g Pa*s%n", etaPaS));
        b.append(String.format("  slender-body   ln(L/2R) = %.6f ; + aParallel(%.2f) = %.6f%n",
                logT, A_PARALLEL, logT + A_PARALLEL));
        b.append(String.format("  gammaX     = 2*pi*eta*L / (ln(L/2R)+aParallel) = %.6g N*s/m   = %.6g pN*s/nm%n",
                gammaXSI(etaPaS, lengthUm, radiusUm), gammaXwork(etaPaS, lengthUm, radiusUm)));
        b.append(String.format("  gammaTheta = 4*pi*eta*R^2*L                    = %.6g N*m*s/rad = %.6g pN*nm*s/rad%n",
                gammaThetaSI(etaPaS, lengthUm, radiusUm), gammaThetaWork(etaPaS, lengthUm, radiusUm)));
        b.append("  NOT used: bTGy/bTGz (transverse translation), bRGy/bRGz (tumbling about a\n");
        b.append("            transverse axis), sphereDragSI (myosin head). No segment-count factor.\n");
        return b.toString();
    }
}
