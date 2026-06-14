package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * System 1: dragTensorSystem.
 *
 * Fills bTransGam/bRotGam/bTransDiff/bRotDiff (and segLength) from monomerCount +
 * viscosity. Direct port of v1 FilSegment.calculateProperties()
 * (~/Code/BoA-v1ref/boxOfActin/FilSegment.java:405-450) and the length derivation
 * in FilSegment.initialize() (FilSegment.java:464). This is the gamma the FDT check
 * rests on, so it is ported line-for-line — see the code-fidelity diff in JOURNAL.md.
 *
 * Runs on the host at init (and would re-run on a length change). For a fixed-length
 * rod it runs once. It is a free function over the component arrays — not a method
 * on a segment object.
 *
 * Units: v1 works in microns for length; converts to meters (1e-6) for the SI drag
 * formulas, so the resulting gamma is SI (translational N·s/m, rotational N·m·s/rad)
 * and the Einstein diffusion is SI (m^2/s, rad^2/s). The integration system carries
 * the 1e6 m->micron factor exactly as v1 moveThing does.
 */
public final class DragTensorSystem {
    private DragTensorSystem() {}

    /**
     * Rod (slender-body) drag — the SHARED formula used by actin segments, the myosin rod,
     * and the myosin lever. VERBATIM FilSegment.calculateProperties():420-435 (== MyoRod /
     * MyoLever.calculateProperties, same expressions, different length/radius). Returns SI
     * {bTGx, bTGy, bTGz, bRGx, bRGy, bRGz}. The caller applies any entity-specific length
     * derivation/clamp BEFORE calling (actin min-length clamp stays in run(FilamentStore)).
     */
    public static double[] rodDragSI(double lengthUm, double radiusUm) {
        double aeta = Constants.aeta;
        double LM = 1.0e-6 * lengthUm;          // meters
        double RM = radiusUm * 1.0e-6;
        double logT = Math.log(LM / (2 * RM));  // dimensionless
        double bTGx = (2 * Math.PI * aeta * LM) / (logT + Constants.aParallel);
        double bTGy = (4 * Math.PI * aeta * LM) / (logT + Constants.aOrthog);
        double bTGz = bTGy;
        double bRGx = 4 * Math.PI * aeta * RM * RM * LM;
        double bRGy = (Math.PI * aeta * (LM * LM * LM)) / (3 * (logT + Constants.aTurning));
        double bRGz = bRGy;
        return new double[] { bTGx, bTGy, bTGz, bRGx, bRGy, bRGz };
    }

    /**
     * Sphere (Stokes) drag — the SECOND drag formula, revealed by the myosin HEAD (the
     * diff between the two body instances). VERBATIM MyoMotor.calculateProperties():144-149:
     * translational 6πηr (isotropic), rotational 8πηr³. Returns SI {bTGx,bTGy,bTGz,bRGx,bRGy,bRGz}.
     */
    public static double[] sphereDragSI(double radiusUm) {
        double aeta = Constants.aeta;
        double RM = radiusUm * 1.0e-6;
        double bTG = 6 * Math.PI * aeta * RM;
        double bRG = 8 * Math.PI * aeta * (RM * RM * RM);
        return new double[] { bTG, bTG, bTG, bRG, bRG, bRG };
    }

    /** Store a SI drag tensor (and its Einstein diffusion) into a body's planar arrays at slot i. */
    private static void storeDrag(FloatArray bTransGam, FloatArray bRotGam,
                                  FloatArray bTransDiff, FloatArray bRotDiff,
                                  int planeX, int planeY, int planeZ, double[] g) {
        double kT = Constants.kT;
        bTransGam.set(planeX, (float) g[0]); bTransGam.set(planeY, (float) g[1]); bTransGam.set(planeZ, (float) g[2]);
        bRotGam.set(planeX,   (float) g[3]); bRotGam.set(planeY,   (float) g[4]); bRotGam.set(planeZ,   (float) g[5]);
        bTransDiff.set(planeX, (float) (kT / g[0])); bTransDiff.set(planeY, (float) (kT / g[1])); bTransDiff.set(planeZ, (float) (kT / g[2]));
        bRotDiff.set(planeX,   (float) (kT / g[3])); bRotDiff.set(planeY,   (float) (kT / g[4])); bRotDiff.set(planeZ,   (float) (kT / g[5]));
    }

    public static void run(FilamentStore s) {
        final double halfmono = Constants.actinMonoRadius;
        final int minMonomerCt = 30;                   // FilSegment.java:411

        for (int i = 0; i < s.n; i++) {
            // length = (monomerCt+1)*actinMonoRadius   (FilSegment.java:464), microns
            int monomerCt = s.monomerCount.get(i);
            double length = (monomerCt + 1) * Constants.actinMonoRadius;
            s.segLength.set(i, (float) length);

            // min-length clamp (FilSegment.java:409-419). A free rod is "at end" on both
            // ends (no neighbors), so the filAtEnd branch applies. This length derivation +
            // clamp is ACTIN-specific and stays here; the drag formula is the shared helper.
            double minLength;
            if (s.filAtEnd1(i) || s.filAtEnd2(i)) {
                minLength = Constants.stdSegLength * halfmono;
            } else {
                minLength = minMonomerCt * halfmono;
            }
            double asIfLength = length;
            if (asIfLength < minLength) { asIfLength = minLength; }

            double[] g = rodDragSI(asIfLength, Constants.radius);
            storeDrag(s.bTransGam, s.bRotGam, s.bTransDiff, s.bRotDiff,
                      s.planeX(i), s.planeY(i), s.planeZ(i), g);
        }
    }

    /**
     * Motor sub-body drag init (increment 4b-i). Per motor m, the three sub-bodies
     * 3m=rod, 3m+1=lever (rod drag), 3m+2=head (sphere drag), with v1's lengths/radii.
     * Host init over the SHARED RigidRodBody arrays — the shared device systems then read
     * the resulting gamma unchanged.
     */
    public static void run(MotorStore mot) {
        RigidRodBody b = mot.body;
        for (int m = 0; m < mot.nMotors; m++) {
            int rod = 3 * m, lever = 3 * m + 1, head = 3 * m + 2;
            b.segLength.set(rod,   (float) MotorStore.ROD_LEN);
            b.segLength.set(lever, (float) MotorStore.LEVER_LEN);
            b.segLength.set(head,  (float) MotorStore.HEAD_LEN);
            storeDrag(b.bTransGam, b.bRotGam, b.bTransDiff, b.bRotDiff,
                      b.planeX(rod), b.planeY(rod), b.planeZ(rod),
                      rodDragSI(MotorStore.ROD_LEN, MotorStore.ROD_R));
            storeDrag(b.bTransGam, b.bRotGam, b.bTransDiff, b.bRotDiff,
                      b.planeX(lever), b.planeY(lever), b.planeZ(lever),
                      rodDragSI(MotorStore.LEVER_LEN, MotorStore.LEVER_R));
            storeDrag(b.bTransGam, b.bRotGam, b.bTransDiff, b.bRotDiff,
                      b.planeX(head), b.planeY(head), b.planeZ(head),
                      sphereDragSI(MotorStore.HEAD_R));
        }
    }

    /**
     * Double-precision reference values for the FDT prediction, computed from the SAME
     * formula the kernel's gamma came from. Returns {D_trans_par, D_trans_perp,
     * D_rot_par, D_rot_perp} in SI (m^2/s and rad^2/s). The harness converts trans to
     * micron^2/s for comparison with the measured MSD slope. Predictions read the gamma
     * stored in the arrays so they are self-consistent with what the integrator used.
     */
    public static double[] fdtPrediction(FilamentStore s, int i) {
        double gTransX = s.bTransGam.get(s.planeX(i));
        double gTransY = s.bTransGam.get(s.planeY(i));
        double gRotX   = s.bRotGam.get(s.planeX(i));
        double gRotY   = s.bRotGam.get(s.planeY(i));
        double kT = Constants.kT;
        return new double[] { kT / gTransX, kT / gTransY, kT / gRotX, kT / gRotY };
    }
}
