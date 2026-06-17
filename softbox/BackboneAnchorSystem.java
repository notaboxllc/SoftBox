package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 6 (contractile assay): a soft HARMONIC centering of the minifilament backbone COM toward a
 * home point. The v1 contractility assay keeps its FREE, Brownian minifilament from diffusing out of the
 * overlap with a thin confining box (boxYDim 0.3 / boxZDim 0.2 µm); this is the minimal stand-in — a weak
 * restoring force F = −k·(coord − home) added into the backbone's forceSum. The backbone still undergoes
 * full Brownian motion (it wiggles in the well, equilibrium ⟨r²⟩ = kT/k), but stays "positioned in the
 * middle" so the bipolar contraction is symmetric instead of drifting onto one filament.
 *
 * Device-agnostic, one thread per backbone, self-write (no atomics). homeParams: [0..2]=home x,y,z, [3]=k.
 * Force only (no torque) — the backbone is free to reorient. k is small enough that the well is soft
 * (the body diffuses ~nm) but stiff enough to balance the few-pN contractile imbalance within ~the head zone.
 */
public final class BackboneAnchorSystem {
    private BackboneAnchorSystem() {}

    public static void center(FloatArray coord, FloatArray forceSum, FloatArray homeParams, IntArray counts) {
        int N = coord.getSize() / 3;
        int nBb = counts.get(3);
        double hx = homeParams.get(0), hy = homeParams.get(1), hz = homeParams.get(2), k = homeParams.get(3);
        for (@Parallel int i = 0; i < nBb; i++) {
            int iy = N + i, iz = 2 * N + i;
            double fx = -k * (1.0e-6 * (coord.get(i)  - hx));     // µm → m for the spring; F in N
            double fy = -k * (1.0e-6 * (coord.get(iy) - hy));
            double fz = -k * (1.0e-6 * (coord.get(iz) - hz));
            forceSum.set(i,  (float) (forceSum.get(i)  + fx));
            forceSum.set(iy, (float) (forceSum.get(iy) + fy));
            forceSum.set(iz, (float) (forceSum.get(iz) + fz));
        }
    }
}
