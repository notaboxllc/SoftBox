package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * EOM-STABILITY DIAGNOSTIC ONLY — an external elastic COM tether on a rigid-rod body's segment centers,
 * standing in for the ensemble load in the numerical-stability probe (EomStabilityHarness). NOT a
 * transport/physics subsystem: no production path references it, and it is only ever wired by the
 * stability harness (default byte-identical elsewhere — this file adds nothing to any existing kernel).
 *
 * The tether is a pure conservative linear spring anchored at each segment's LOADED-EQUILIBRIUM centre
 * (eqCoord): F = -k_ext * (coord - eqCoord), added to forceSum (planar SoA: x-plane i, y-plane N+i,
 * z-plane 2N+i). One additive term per segment, disjoint writes, no atomics / no KernelContext ⇒
 * CPU≡GPU bit-identical-capable and lowers trivially on the PTX backend.
 *
 * springParams: [0] = k_ext (N/µm, same units as myoSpring — a bound F8 is ~1e-9 N/µm = 1 pN/nm).
 *
 * Two forms:
 *  - applyExternalSpring: EXPLICIT — F = -k(x-eq) into forceSum (the integrator advances it forward-Euler,
 *    the conservative worst case the harness probes for the stability boundary).
 *  - applyExternalSpringImplicit: a BACKWARD-EULER operator-split correction applied to coord AFTER the
 *    explicit integrate of all OTHER forces — x <- (x_e + r*eq)/(1+r), r = 1e6*k*dt/gamma (per plane, using
 *    the body-frame drag bTransGam; exact for the axial mode uVec≈x the harness excites). Unconditionally
 *    stable in the spring ⇒ the "cure demonstration" toggle. The external spring is NOT added to forceSum
 *    when this form is used (operator split).
 */
public final class ExternalSpringSystem {
    private ExternalSpringSystem() {}

    /** Explicit: forceSum += -k_ext * (coord - eqCoord), per segment, per plane. */
    public static void applyExternalSpring(FloatArray coord, FloatArray eqCoord, FloatArray forceSum,
                                           FloatArray springParams, IntArray counts) {
        int N = coord.getSize() / 3;
        float k = springParams.get(0);
        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i, iz = 2 * N + i;
            forceSum.set(i,  forceSum.get(i)  - k * (coord.get(i)  - eqCoord.get(i)));
            forceSum.set(iy, forceSum.get(iy) - k * (coord.get(iy) - eqCoord.get(iy)));
            forceSum.set(iz, forceSum.get(iz) - k * (coord.get(iz) - eqCoord.get(iz)));
        }
    }

    /** Zero the torque accumulator only (the clean single-translational-mode probe: freeze filament rotation). */
    public static void zeroTorque(FloatArray torqueSum, IntArray counts) {
        int N = torqueSum.getSize() / 3;
        for (@Parallel int i = 0; i < N; i++) {
            torqueSum.set(i, 0f); torqueSum.set(N + i, 0f); torqueSum.set(2 * N + i, 0f);
        }
    }

    /** Implicit (operator-split backward-Euler on the spring alone, applied post-integrate):
     *  coord <- (coord + r*eq)/(1+r), r = 1e6 * k * dt / gamma (per plane; gamma = bTransGam plane index).
     *  springParams[0]=k_ext, springParams[1]=dt. */
    public static void applyExternalSpringImplicit(FloatArray coord, FloatArray eqCoord, FloatArray bTransGam,
                                                   FloatArray springParams, IntArray counts) {
        int N = coord.getSize() / 3;
        float k = springParams.get(0);
        float dt = springParams.get(1);
        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i, iz = 2 * N + i;
            float rx = 1.0e6f * k * dt / bTransGam.get(i);
            float ry = 1.0e6f * k * dt / bTransGam.get(iy);
            float rz = 1.0e6f * k * dt / bTransGam.get(iz);
            coord.set(i,  (coord.get(i)  + rx * eqCoord.get(i))  / (1.0f + rx));
            coord.set(iy, (coord.get(iy) + ry * eqCoord.get(iy)) / (1.0f + ry));
            coord.set(iz, (coord.get(iz) + rz * eqCoord.get(iz)) / (1.0f + rz));
        }
    }
}
