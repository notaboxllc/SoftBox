package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * HEAD-SIDE CONFORMATIONAL ORIGIN OF THE STROKE SKEW (2026-09-07).
 *
 * `epsStroke` injects the chiral asymmetry on the ACTIN side: it rotates the bound site azimuth
 * (bindAzim += mirror*eps) at the power stroke. That reproduces the experimental twirl, but it is awkward to
 * defend biologically -- actin does not move its own binding site.
 *
 * This system expresses the SAME mechanics as a conformational change of the MYOSIN HEAD: at the stroke, the
 * head's actin-binding material point (xF8) shifts TANGENTIALLY on the actin surface by a small displacement
 * delta. Because the F8 bond is a ZERO-REST spring, F = k(x_site - x_F8), displacing the head-side endpoint by
 * -delta is force-identical to displacing the actin-side endpoint by +delta. So this is the same physics with a
 * defensible structural reading.
 *
 * SCALE (the point of the exercise): eps = 1.5 deg at the actin radius 3.5 nm is delta = R*eps = 0.092 nm --
 * a ~0.9 ANGSTROM tangential shift of the actin-binding interface, about one Angstrom, riding on a ~5 nm
 * working stroke. That is the scale of a loop or cleft rearrangement, not an exotic assumption.
 *
 * Direction: the azimuthal tangent at the bound site, that = u_fil x n_site (unit), so the displacement is
 * purely azimuthal about the filament axis -- exactly the coordinate epsStroke rotates.
 *
 * Gating: applied ONLY to bound motors in the POST-stroke nucleotide state, so it is a transition, not a static
 * offset. delta = 0 leaves outGeom untouched (no write) => byte-identical when off.
 *
 * DELIBERATE DECOUPLING (jba, 2026-09-08). The head axis xHeadHat is DEFINED elsewhere as
 * normalize(xF8 - xH), so displacing xF8 would ordinarily reorient the whole head and make the site-normal
 * kbind latch fight the shift. That is NOT the model we want. The intended physics is a CONFORMATIONAL
 * CHANGE: the actin-binding INTERFACE moves within the head while the head BODY keeps its orientation --
 * i.e. the binding point is off the head's roll axis, as it is in the real protein, where the actin
 * interface sits on a FACE (loop 2, the HLH motif, the cardiomyopathy loop) and not at the tip on the
 * symmetry axis.
 *
 * So this system PINS the head axis to its PRE-SHIFT value before displacing xF8. The kbind couple
 * T = lambda (xHeadHat x eTarget) is then untouched: the head is still held to eTarget = -n_site at the
 * same azimuth, exactly as before, and only the bond geometry sees the displacement. After this runs, the
 * head-body uVec (bond side) and xHeadHat (kbind side) differ by delta/|r_F8| ~ 1.5 deg at delta = 0.092 nm.
 * That divergence IS the conformational change and is intentional.
 *
 * Self-asserting: writing xHeadHat here means the decoupling does not depend on this task running after
 * SiteNormalBindSystem.headAxisStep -- a task-ordering accident would otherwise silently change the physics.
 *
 * NOTE vs epsStroke: force-identical, but not torque-identical -- moving xF8 changes its lever arm about the
 * head centre while moving the actin site does not.
 */
public final class HeadConformationSystem {

    /** hcP: [0]=delta (um, signed) [1]=mirror [2]=postStrokeState. */
    public static void f8TangentialShift(IntArray boundSeg, IntArray nucleotideState,
                                         FloatArray filUVec, FloatArray filYVec, FloatArray bindAzim,
                                         DoubleArray outGeom, DoubleArray hcP, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3);
        double delta  = hcP.get(0);
        double mirror = hcP.get(1);
        int postState = (int) hcP.get(2);
        if (delta == 0.0) return;

        for (@Parallel int m = 0; m < N; m++) {
            int s = boundSeg.get(m);
            if (s < 0) continue;
            // postState >= 0: exactly that state. postState < 0 (default): any POST-stroke state, i.e.
            // != NUC_ADPPI -- the same predicate as MotorStore.isCocked(), so the head stays shifted through
            // ADP and rigor exactly as epsStroke's bindAzim offset persists until unbinding.
            int nu = nucleotideState.get(m);
            if (postState >= 0 ? (nu != postState) : (nu == MotorStore.NUC_ADPPI)) continue;

            double ux = filUVec.get(s), uy = filUVec.get(nSeg + s), uz = filUVec.get(2 * nSeg + s);
            double yx = filYVec.get(s), yy = filYVec.get(nSeg + s), yz = filYVec.get(2 * nSeg + s);
            double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
            double zl = zx * zx + zy * zy + zz * zz;
            if (!(zl > 1e-30)) continue;
            double iz = 1.0 / Math.sqrt(zl); zx *= iz; zy *= iz; zz *= iz;

            // site normal at the bound azimuth, then the azimuthal tangent t = u x n
            double ph = bindAzim.get(m);
            double c = Math.cos(ph), sn = Math.sin(ph);
            double nx = c * yx + sn * zx, ny = c * yy + sn * zy, nz = c * yz + sn * zz;
            double tx = uy * nz - uz * ny, ty = uz * nx - ux * nz, tz = ux * ny - uy * nx;
            double tl = tx * tx + ty * ty + tz * tz;
            if (!(tl > 1e-30)) continue;
            double it = 1.0 / Math.sqrt(tl); tx *= it; ty *= it; tz *= it;

            // PIN the head axis to its PRE-SHIFT direction (see the class note): the head BODY orientation,
            // and therefore the kbind "stick straight out" couple, must not follow the interface displacement.
            double ax = outGeom.get(6 * N + m) - outGeom.get(3 * N + m);
            double ay = outGeom.get(7 * N + m) - outGeom.get(4 * N + m);
            double az = outGeom.get(8 * N + m) - outGeom.get(5 * N + m);
            double aL = Math.sqrt(ax * ax + ay * ay + az * az);
            if (aL > 1e-30) {
                double ia = 1.0 / aL;
                outGeom.set(9 * N + m,  ax * ia);
                outGeom.set(10 * N + m, ay * ia);
                outGeom.set(11 * N + m, az * ia);
            }

            double d = mirror * delta;
            outGeom.set(6 * N + m, outGeom.get(6 * N + m) + d * tx);
            outGeom.set(7 * N + m, outGeom.get(7 * N + m) + d * ty);
            outGeom.set(8 * N + m, outGeom.get(8 * N + m) + d * tz);
        }
    }
}
