package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Support kernels for the deflection-benchmark comparison test (mini-2b): seed the
 * deterministic accumulators with a constant external load, and enforce v1's pinned
 * boundary condition. Used only by DiffusionHarness.runDeflection — does not touch
 * the FDT / free-chain paths.
 *
 * Replicates v1 (BoxOfActin.applyBenchmarkPins / the midpoint force):
 *  - force: transForce added to the midpoint segment's forceSum every step.
 *  - pin:   AFTER integration, translate the anchor segments so the pinned endpoint
 *           snaps back to its fixed anchor (incCoord(anchor - endpoint)); segments are
 *           free to rotate -> pinned-pinned. end1 = coord - L/2 uVec, end2 = coord + L/2 uVec.
 */
public final class DeflectionSupport {
    private DeflectionSupport() {}

    /** Seed forceSum from a constant external-force field (the load); zero torqueSum. */
    public static void seedAccumulators(FloatArray forceSum, FloatArray torqueSum,
                                        FloatArray extForce, IntArray counts) {
        int N = forceSum.getSize() / 3;
        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i, iz = 2 * N + i;
            forceSum.set(i, extForce.get(i)); forceSum.set(iy, extForce.get(iy)); forceSum.set(iz, extForce.get(iz));
            torqueSum.set(i, 0f); torqueSum.set(iy, 0f); torqueSum.set(iz, 0f);
        }
    }

    /**
     * Hard endpoint pin (v1 applyBenchmarkPins). For each pin p: translate segment
     * pinSeg[p] so its pinEnd[p] endpoint (1=end1, 2=end2) lands exactly on the anchor
     * pinAnchor[3p..3p+2]. Runs after integration. Orientation untouched (free rotation).
     */
    public static void pinEndpoints(FloatArray coord, FloatArray uVec, FloatArray segLength,
                                    IntArray pinSeg, IntArray pinEnd, FloatArray pinAnchor,
                                    IntArray counts) {
        int N = coord.getSize() / 3;
        int P = pinSeg.getSize();
        for (@Parallel int p = 0; p < P; p++) {
            int s = pinSeg.get(p);
            int sx = s, sy = N + s, sz = 2 * N + s;
            float cx = coord.get(sx), cy = coord.get(sy), cz = coord.get(sz);
            float ux = uVec.get(sx), uy = uVec.get(sy), uz = uVec.get(sz);
            float half = 0.5f * segLength.get(s);
            float ex, ey, ez;
            if (pinEnd.get(p) == 1) { ex = cx - half * ux; ey = cy - half * uy; ez = cz - half * uz; }
            else                    { ex = cx + half * ux; ey = cy + half * uy; ez = cz + half * uz; }
            float dx = pinAnchor.get(3 * p)     - ex;
            float dy = pinAnchor.get(3 * p + 1) - ey;
            float dz = pinAnchor.get(3 * p + 2) - ez;
            coord.set(sx, cx + dx); coord.set(sy, cy + dy); coord.set(sz, cz + dz);
        }
    }
}
