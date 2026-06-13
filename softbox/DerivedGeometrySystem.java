package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * System 4: derivedGeometrySystem (device kernel).
 *
 * Recomputes the DERIVED fields end1/end2/zVec (and re-orthogonalizes yVec) from the
 * canonical coord/uVec/yVec/segLength. Port of v1 Thing.recomputeDerivedSoA
 * (~/Code/BoA-v1ref/boxOfActin/Thing.java:945-996):
 *
 *     zVec  = uVec x yVec                (then normalized)
 *     yVec' = zVec x uVec                (restores orthonormality after Euler drift)
 *     end1  = coord - (segLength/2) uVec
 *     end2  = coord + (segLength/2) uVec
 *
 * Runs after integration each step so output frames (and, later, chain-force reads)
 * see a consistent, orthonormal body frame. end1/end2 are NOT source of truth — they
 * are recomputed here, never stored as canonical. This increment uses them only for
 * output; increment 2's chain forces will read them.
 */
public final class DerivedGeometrySystem {
    private DerivedGeometrySystem() {}

    public static void derive(
            FloatArray coord,
            FloatArray uVec,
            FloatArray yVec,
            FloatArray zVec,
            FloatArray end1,
            FloatArray end2,
            FloatArray segLength,
            IntArray   counts) {

        int N = coord.getSize() / 3;

        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i;
            int iz = 2 * N + i;

            float ux = uVec.get(i), uyc = uVec.get(iy), uzc = uVec.get(iz);
            float yx = yVec.get(i), yyc = yVec.get(iy), yzc = yVec.get(iz);

            // zVec = uVec x yVec, normalized
            float zx = uyc * yzc - uzc * yyc;
            float zy = uzc * yx  - ux  * yzc;
            float zz = ux  * yyc - uyc * yx;
            float zmag2 = zx * zx + zy * zy + zz * zz;
            if (zmag2 > 0f) {
                float inv = 1.0f / (float) Math.sqrt(zmag2);
                zx *= inv; zy *= inv; zz *= inv;
            }
            zVec.set(i, zx); zVec.set(iy, zy); zVec.set(iz, zz);

            // yVec' = zVec x uVec (re-orthogonalize)
            float nyx = zy * uzc - zz * uyc;
            float nyy = zz * ux  - zx * uzc;
            float nyz = zx * uyc - zy * ux;
            yVec.set(i, nyx); yVec.set(iy, nyy); yVec.set(iz, nyz);

            // end1/end2 = coord -/+ (segLength/2) uVec
            float cx = coord.get(i), cy = coord.get(iy), cz = coord.get(iz);
            float halfLen = segLength.get(i) * 0.5f;
            end1.set(i,  cx - halfLen * ux);
            end1.set(iy, cy - halfLen * uyc);
            end1.set(iz, cz - halfLen * uzc);
            end2.set(i,  cx + halfLen * ux);
            end2.set(iy, cy + halfLen * uyc);
            end2.set(iz, cz + halfLen * uzc);
        }
    }
}
