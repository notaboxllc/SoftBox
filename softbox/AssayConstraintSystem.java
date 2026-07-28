package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * ASSAY-LEVEL KINEMATIC CONSTRAINTS on a filament — a fixture, never a force law.
 *
 * <p><b>Noncanonical, flag-gated, DEFAULT-OFF.</b> Nothing here is wired unless a harness calls it, and no
 * canonical kernel is modified. These are the idealizing constraints a controlled twirling assay needs:
 * a filament driven along its own axis at a commanded speed, held at a fixed height and a fixed tilt, and
 * left <b>free to roll about its own axis</b>.
 *
 * <h2>Why this is kinematic and not a force</h2>
 * A prescribed translation implemented as an applied force would necessarily act at some point of the body
 * and could therefore exert a moment — including an axial moment, which is precisely the observable under
 * test. Instead this system <b>overwrites the pose</b> after the integrator has run. It adds nothing to
 * {@code forceSum} or {@code torqueSum}, so it cannot contribute an axial torque by construction. Gate D
 * verifies the consequence: with motors disabled the filament translates exactly as commanded and its roll
 * stays identically zero.
 *
 * <h2>What is constrained and what is left free</h2>
 * <ul>
 *   <li><b>position</b> — every segment is placed on its commanded path
 *       {@code coord_s(t) = coord0_s + (v·t)·axis}. This simultaneously prescribes the axial translation,
 *       pins the height, and removes lateral drift.</li>
 *   <li><b>tilt</b> — {@code uVec} is reset to the fixed reference {@code axis}.</li>
 *   <li><b>roll — FREE.</b> {@code yVec} is only Gram–Schmidt-orthogonalized against {@code axis}. That
 *       projection removes the component along the axis and renormalizes; it does <b>not</b> touch the
 *       azimuth of {@code yVec} in the plane perpendicular to {@code axis}, which is the roll coordinate.
 *       The roll the integrator produced from the gathered axial torque therefore survives intact.</li>
 * </ul>
 *
 * <p>Call it AFTER the integrator and re-derive the dependent geometry
 * ({@link DerivedGeometrySystem#derive}) so ends, {@code zVec} and lengths stay consistent.
 *
 * <p>{@code fixP} layout (FloatArray): {@code [0..2]} axis (unit), {@code [3]} commanded axial speed
 * (µm/s), {@code [4]} elapsed time (s), {@code [5]} clampTilt (1 = on), {@code [6]} prescribePos (1 = on).
 */
public final class AssayConstraintSystem {
    private AssayConstraintSystem() {}

    /**
     * Apply the prescribed-translation / height / tilt constraints, leaving axial roll free.
     *
     * @param coord0 the reference pose captured at t = 0 (3·n planar, same layout as {@code coord})
     */
    public static void kinematicFixture(FloatArray coord, FloatArray uVec, FloatArray yVec,
                                        FloatArray coord0, FloatArray fixP, IntArray counts) {
        int n = counts.get(0);
        float ax = fixP.get(0), ay = fixP.get(1), az = fixP.get(2);
        float disp = fixP.get(3) * fixP.get(4);          // commanded axial displacement (µm)
        boolean tilt = fixP.get(5) != 0f, pos = fixP.get(6) != 0f;
        for (@Parallel int s = 0; s < n; s++) {
            if (pos) {
                coord.set(s,         coord0.get(s)         + disp * ax);
                coord.set(n + s,     coord0.get(n + s)     + disp * ay);
                coord.set(2 * n + s, coord0.get(2 * n + s) + disp * az);
            }
            if (tilt) {
                uVec.set(s, ax); uVec.set(n + s, ay); uVec.set(2 * n + s, az);
                // Gram–Schmidt yVec against the FIXED axis: removes only the axial component, so the
                // azimuth of yVec about the axis — the roll coordinate — is preserved exactly.
                float yx = yVec.get(s), yy = yVec.get(n + s), yz = yVec.get(2 * n + s);
                float d = yx * ax + yy * ay + yz * az;
                yx -= d * ax; yy -= d * ay; yz -= d * az;
                float l2 = yx * yx + yy * yy + yz * yz;
                if (l2 > 1e-20f) {
                    float il = (float) (1.0 / Math.sqrt(l2));
                    yVec.set(s, yx * il); yVec.set(n + s, yy * il); yVec.set(2 * n + s, yz * il);
                }
            }
        }
    }
}
