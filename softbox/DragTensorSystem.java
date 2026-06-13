package softbox;

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

    public static void run(FilamentStore s) {
        final double aeta   = Constants.aeta;
        final double radius = Constants.radius;        // microns
        final double halfmono = Constants.actinMonoRadius;
        final double kT = Constants.kT;
        final int minMonomerCt = 30;                   // FilSegment.java:411

        for (int i = 0; i < s.n; i++) {
            // length = (monomerCt+1)*actinMonoRadius   (FilSegment.java:464), microns
            int monomerCt = s.monomerCount.get(i);
            double length = (monomerCt + 1) * Constants.actinMonoRadius;
            s.segLength.set(i, (float) length);

            // min-length clamp (FilSegment.java:409-419). A free rod is "at end" on both
            // ends (no neighbors), so the filAtEnd branch applies.
            double minLength;
            if (s.filAtEnd1(i) || s.filAtEnd2(i)) {
                minLength = Constants.stdSegLength * halfmono;
            } else {
                minLength = minMonomerCt * halfmono;
            }
            double asIfLength = length;
            if (asIfLength < minLength) { asIfLength = minLength; }

            // --- VERBATIM from FilSegment.calculateProperties():420-441 ---
            double asIfLengthM = 1.0e-6 * asIfLength;          // meters
            double radiusM = radius * 1.0e-6;
            double denomLogTerm = Math.log(asIfLengthM / (2 * radiusM));  // dimensionless

            double bTransGamX = (2 * Math.PI * aeta * asIfLengthM) / (denomLogTerm + Constants.aParallel);
            double bTransGamY = (4 * Math.PI * aeta * asIfLengthM) / (denomLogTerm + Constants.aOrthog);
            double bTransGamZ = bTransGamY;

            double bRotGamX = 4 * Math.PI * aeta * radiusM * radiusM * asIfLengthM;
            double bRotGamY = (Math.PI * aeta * (asIfLengthM * asIfLengthM * asIfLengthM))
                              / (3 * (denomLogTerm + Constants.aTurning));
            double bRotGamZ = bRotGamY;

            // Einstein's relation D = kT/gamma (FilSegment.java:440-441)
            double bTransDiffX = kT / bTransGamX;
            double bTransDiffY = kT / bTransGamY;
            double bTransDiffZ = kT / bTransGamZ;
            double bRotDiffX = kT / bRotGamX;
            double bRotDiffY = kT / bRotGamY;
            double bRotDiffZ = kT / bRotGamZ;

            // store planar (float32 to match v1's GPU marshalling)
            s.bTransGam.set(s.planeX(i), (float) bTransGamX);
            s.bTransGam.set(s.planeY(i), (float) bTransGamY);
            s.bTransGam.set(s.planeZ(i), (float) bTransGamZ);
            s.bRotGam.set(s.planeX(i), (float) bRotGamX);
            s.bRotGam.set(s.planeY(i), (float) bRotGamY);
            s.bRotGam.set(s.planeZ(i), (float) bRotGamZ);

            s.bTransDiff.set(s.planeX(i), (float) bTransDiffX);
            s.bTransDiff.set(s.planeY(i), (float) bTransDiffY);
            s.bTransDiff.set(s.planeZ(i), (float) bTransDiffZ);
            s.bRotDiff.set(s.planeX(i), (float) bRotDiffX);
            s.bRotDiff.set(s.planeY(i), (float) bRotDiffY);
            s.bRotDiff.set(s.planeZ(i), (float) bRotDiffZ);
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
