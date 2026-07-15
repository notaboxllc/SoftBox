package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Experiment-0 DIAGNOSTIC ONLY — virtual harmonic optical traps acting on a rigid-rod body's
 * ENDPOINTS, standing in for the two beads of an optical-trap dumbbell assay. NOT a transport /
 * production subsystem: no canonical path references it; it is only ever wired by LaserTrapHarness
 * (default byte-identical to the rest of the tree — this file is new and referenced nowhere else).
 *
 * PHYSICS (the assignment's axial-projected trap force):
 *
 *     F_trap,i = -k_i * [ (x_i - x0_i) . f̂ ] f̂                    (Newtons)
 *
 * where x_i is a filament endpoint (end1 for the LEFT trap, end2 for the RIGHT trap), x0_i is the
 * corresponding trap CENTER, k_i the trap stiffness, f̂ the assay axis (unit). Only the projection
 * of the endpoint displacement ONTO the assay axis is restored — a real 3D bead trap would also
 * confine the transverse and orientational modes, but this experiment deliberately uses the
 * axial-projected form (see the harness header for why, and for the measured/reported angular
 * excursion). A weak orientational preparation, if ever needed, is a SEPARATE clearly-labeled term
 * (LaserTrapHarness), never folded into this trap force.
 *
 * SIGN CONVENTION (documented, per the assignment):
 *   ext_L = (end1 - x0L) . f̂   is the LEFT-trap axial extension of the endpoint past its center.
 *   The force -k*ext restores the endpoint toward the trap center. If the endpoint sits to the +f̂
 *   side of its center (ext>0) the force is along -f̂ (pulls it back); to the -f̂ side (ext<0) the
 *   force is along +f̂. Same for the RIGHT trap on end2. Equal-and-opposite accumulation: the force
 *   is added to the segment's forceSum (translational) and the torque r×F (r = endpoint-center,
 *   converted µm→m) is added to torqueSum, EXACTLY as ContainmentSystem accumulates an endpoint
 *   force+torque (r in metres, F in Newtons ⇒ torque in N·m, matching the SI rotational drag). A
 *   perfectly axial rod has r ∥ F ⇒ r×F = 0 ⇒ zero trap torque (verified in Phase A1).
 *
 * A deterministic axial EXTERNAL force (trapParams[5], Newtons along f̂) is applied at the segment
 * CENTER (no torque) for the constant-force response test (Phase A5). Trap centers are per-rod
 * planar arrays (x0L/x0R, stride N) so common-mode / differential / stepped protocols are just
 * writes to those arrays; stiffness and axis are scalars in trapParams (one calibration rod).
 *
 * trapParams (float): [0]=kL (N/µm)  [1]=kR (N/µm)  [2..4]=f̂ (unit assay axis)  [5]=extForce (N, axial @COM)
 *
 * Device-agnostic: one thread per rod, each writing only its own slot (+=, race-free, no atomics,
 * no KernelContext) ⇒ runs identically on a GPU TaskGraph and the sequential -cpu runner. Planar
 * SoA (plane stride N): rod i is (i, N+i, 2N+i); segLength is a size-N scalar.
 */
public final class LaserTrapSystem {
    private LaserTrapSystem() {}

    public static void applyTraps(
            FloatArray coord, FloatArray uVec, FloatArray segLength,
            FloatArray x0L, FloatArray x0R,
            FloatArray forceSum, FloatArray torqueSum,
            FloatArray trapParams, IntArray counts) {

        int N = coord.getSize() / 3;
        float kL = trapParams.get(0);
        float kR = trapParams.get(1);
        float fhx = trapParams.get(2), fhy = trapParams.get(3), fhz = trapParams.get(4);
        float extF = trapParams.get(5);   // deterministic axial external force (N), at COM, no torque

        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i, iz = 2 * N + i;
            float cx = coord.get(i), cy = coord.get(iy), cz = coord.get(iz);
            float ux = uVec.get(i), uy = uVec.get(iy), uz = uVec.get(iz);
            float half = 0.5f * segLength.get(i);

            // derived endpoints (same formula as DerivedGeometrySystem)
            float e1x = cx - half * ux, e1y = cy - half * uy, e1z = cz - half * uz;   // LEFT trap acts here
            float e2x = cx + half * ux, e2y = cy + half * uy, e2z = cz + half * uz;   // RIGHT trap acts here

            // LEFT trap on end1: axial-projected restoring force
            float extL = (e1x - x0L.get(i)) * fhx + (e1y - x0L.get(iy)) * fhy + (e1z - x0L.get(iz)) * fhz;
            float FLx = -kL * extL * fhx, FLy = -kL * extL * fhy, FLz = -kL * extL * fhz;

            // RIGHT trap on end2
            float extR = (e2x - x0R.get(i)) * fhx + (e2y - x0R.get(iy)) * fhy + (e2z - x0R.get(iz)) * fhz;
            float FRx = -kR * extR * fhx, FRy = -kR * extR * fhy, FRz = -kR * extR * fhz;

            // external axial force at the segment centre (no torque)
            float FEx = extF * fhx, FEy = extF * fhy, FEz = extF * fhz;

            // ---- accumulate force ----
            forceSum.set(i,  forceSum.get(i)  + FLx + FRx + FEx);
            forceSum.set(iy, forceSum.get(iy) + FLy + FRy + FEy);
            forceSum.set(iz, forceSum.get(iz) + FLz + FRz + FEz);

            // ---- accumulate torque r×F, r = (endpoint - centre)·1e-6 (µm→m), F in N ⇒ N·m ----
            float r1x = (e1x - cx) * 1.0e-6f, r1y = (e1y - cy) * 1.0e-6f, r1z = (e1z - cz) * 1.0e-6f;
            float r2x = (e2x - cx) * 1.0e-6f, r2y = (e2y - cy) * 1.0e-6f, r2z = (e2z - cz) * 1.0e-6f;
            float tx = (r1y * FLz - r1z * FLy) + (r2y * FRz - r2z * FRy);
            float ty = (r1z * FLx - r1x * FLz) + (r2z * FRx - r2x * FRz);
            float tz = (r1x * FLy - r1y * FLx) + (r2x * FRy - r2y * FRx);
            torqueSum.set(i,  torqueSum.get(i)  + tx);
            torqueSum.set(iy, torqueSum.get(iy) + ty);
            torqueSum.set(iz, torqueSum.get(iz) + tz);
        }
    }

    /**
     * FULL 3D VECTOR trap (Experiment 0b / 1): F_i = −K_i·(x_i − x0_i) with K a DIAGONAL stiffness
     * tensor in the (axial f̂, transverse, transverse) frame — kAx along f̂, kTr on the two perpendicular
     * axes. This is the real-bead-trap generalization of the axial-projected applyTraps: it confines
     * transverse position and (via the endpoint lever arm) filament orientation, whereas applyTraps
     * (axial-only) left them free. Setting kTr=0 recovers applyTraps exactly (the axial-only control).
     * Diagonal, NOT introduced silently: kAx and kTr are separate documented params; isotropic ⇔ kAx=kTr.
     *
     * Accumulation identical to applyTraps: force into forceSum, torque r×F (r=(endpoint−center)·1e-6,
     * µm→m) into torqueSum. LEFT trap on end1, RIGHT trap on end2. A deterministic axial external force
     * (trapParams[5], N along f̂) is applied at the COM (no torque) for the constant-force test.
     *
     * trapParams (float): [0]=kAx (N/µm) [1]=kTr (N/µm) [2..4]=f̂ (unit) [5]=extForce (N, axial @COM)
     */
    public static void applyTraps3D(
            FloatArray coord, FloatArray uVec, FloatArray segLength,
            FloatArray x0L, FloatArray x0R,
            FloatArray forceSum, FloatArray torqueSum,
            FloatArray trapParams, IntArray counts) {

        int N = coord.getSize() / 3;
        float kAx = trapParams.get(0);
        float kTr = trapParams.get(1);
        float fhx = trapParams.get(2), fhy = trapParams.get(3), fhz = trapParams.get(4);
        float extF = trapParams.get(5);

        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i, iz = 2 * N + i;
            float cx = coord.get(i), cy = coord.get(iy), cz = coord.get(iz);
            float ux = uVec.get(i), uy = uVec.get(iy), uz = uVec.get(iz);
            float half = 0.5f * segLength.get(i);
            float e1x = cx - half * ux, e1y = cy - half * uy, e1z = cz - half * uz;   // LEFT (end1)
            float e2x = cx + half * ux, e2y = cy + half * uy, e2z = cz + half * uz;   // RIGHT (end2)

            // LEFT trap: F = -kAx*(Δ·f̂)f̂ - kTr*(Δ - (Δ·f̂)f̂)
            float dLx = e1x - x0L.get(i), dLy = e1y - x0L.get(iy), dLz = e1z - x0L.get(iz);
            float aL = dLx * fhx + dLy * fhy + dLz * fhz;
            float FLx = -kAx * aL * fhx - kTr * (dLx - aL * fhx);
            float FLy = -kAx * aL * fhy - kTr * (dLy - aL * fhy);
            float FLz = -kAx * aL * fhz - kTr * (dLz - aL * fhz);

            // RIGHT trap
            float dRx = e2x - x0R.get(i), dRy = e2y - x0R.get(iy), dRz = e2z - x0R.get(iz);
            float aR = dRx * fhx + dRy * fhy + dRz * fhz;
            float FRx = -kAx * aR * fhx - kTr * (dRx - aR * fhx);
            float FRy = -kAx * aR * fhy - kTr * (dRy - aR * fhy);
            float FRz = -kAx * aR * fhz - kTr * (dRz - aR * fhz);

            // external axial force at COM (no torque)
            float FEx = extF * fhx, FEy = extF * fhy, FEz = extF * fhz;

            forceSum.set(i,  forceSum.get(i)  + FLx + FRx + FEx);
            forceSum.set(iy, forceSum.get(iy) + FLy + FRy + FEy);
            forceSum.set(iz, forceSum.get(iz) + FLz + FRz + FEz);

            float r1x = (e1x - cx) * 1.0e-6f, r1y = (e1y - cy) * 1.0e-6f, r1z = (e1z - cz) * 1.0e-6f;
            float r2x = (e2x - cx) * 1.0e-6f, r2y = (e2y - cy) * 1.0e-6f, r2z = (e2z - cz) * 1.0e-6f;
            float tx = (r1y * FLz - r1z * FLy) + (r2y * FRz - r2z * FRy);
            float ty = (r1z * FLx - r1x * FLz) + (r2z * FRx - r2x * FRz);
            float tz = (r1x * FLy - r1y * FLx) + (r2x * FRy - r2y * FRx);
            torqueSum.set(i,  torqueSum.get(i)  + tx);
            torqueSum.set(iy, torqueSum.get(iy) + ty);
            torqueSum.set(iz, torqueSum.get(iz) + tz);
        }
    }

    /**
     * OPTIONAL, SEPARATELY-LABELED orientational preparation — NOT trap physics. A weak angular
     * spring that adds a restoring torque driving uVec toward the assay axis f̂ (the omitted
     * transverse/orientational stiffness of a real 3D bead trap). Default OFF (kPrep=0 ⇒ this method
     * is never called). Its stiffness is reported whenever used; it acts only on orientation
     * (orthogonal to the axial COM mode the gates measure), so it cannot bias ⟨δx²⟩ or k_eff.
     *
     * torque = -kPrep * (f̂ × uVec)   (drives uVec → f̂; magnitude ∝ sin(angle)); units chosen so
     * kPrep is a torque scale in N·m. This is a preparation aid used only if a long thermal run
     * shows the rod tumbling out of the assay geometry; the primary axial measurements do not use it.
     */
    public static void applyAxisPrep(
            FloatArray uVec, FloatArray torqueSum, FloatArray prepParams, IntArray counts) {
        int N = uVec.getSize() / 3;
        float fhx = prepParams.get(0), fhy = prepParams.get(1), fhz = prepParams.get(2);
        float kPrep = prepParams.get(3);
        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i, iz = 2 * N + i;
            float ux = uVec.get(i), uy = uVec.get(iy), uz = uVec.get(iz);
            // f̂ × uVec  (zero when uVec ∥ f̂); torque -kPrep*(f̂×u) rotates u toward f̂
            float cxp = fhy * uz - fhz * uy;
            float cyp = fhz * ux - fhx * uz;
            float czp = fhx * uy - fhy * ux;
            torqueSum.set(i,  torqueSum.get(i)  - kPrep * cxp);
            torqueSum.set(iy, torqueSum.get(iy) - kPrep * cyp);
            torqueSum.set(iz, torqueSum.get(iz) - kPrep * czp);
        }
    }
}
