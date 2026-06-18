package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * The GENERAL in-vitro-chamber containment box (the simulation-domain boundary) — an
 * entity-agnostic system that confines any RigidRodBody inside a configurable rectangular box
 * centered at the origin. This is the stand-in for the in vitro experimental chamber
 * (coverslip / flow-cell) that bounds every in vitro assay; it is NOT the membrane subsystem
 * (the deferred dynamic cell cortex is a distinct, later thing — this is the simple static box).
 *
 * It is the SHARED-infrastructure counterpart of the integrator / Brownian / derive systems: it
 * runs over a body's pose + drag + accumulator arrays UNCHANGED, so the same kernel confines
 * filament segments, motor sub-bodies, minifilament backbones, and (future) nodes — confining
 * POSITIONS, not class identities. Invoke it once per RigidRodBody store, exactly as the
 * integrator and Brownian systems are.
 *
 * Faithful port of v1's free-body box law — the shared detection plus the node/minifilament
 * force. Two v1 pieces, both in BoA-v1ref/boxOfActin:
 *   1. detection  = Chamber.amICollidingOuter (Chamber.java:125-138): per endpoint d (box at
 *      origin), forceUVec_i = sign(d_i)·(halfDim_i − R) − d_i, then ZERO any axis whose push
 *      still points inward (sign(forceUVec_i) == sign(d_i)) — so an endpoint safely inside the
 *      inset wall on an axis contributes nothing on that axis; delta = |forceUVec|, then unit.
 *   2. force      = MyoMiniFilament.checkOuterBugCollision (MyoMiniFilament.java:546-560):
 *      per penetrating endpoint, mag = nodeFracMove·1e-6·delta·bTransGam.x / collisionDeltaT,
 *      F = mag·forceUVec (Newtons), applied AT the endpoint via incForceSum(F, endpoint) ⇒
 *      forceSum += F and torqueSum += r×F with r = (endpoint − center)·1e-6 (µm→m).
 * v1 fires this only every collisionCheckInt = collisionDeltaT/deltaT steps (a collision cadence),
 * reproduced here by the step-gate on counts[1] so the GPU TaskGraph stays a fixed graph and the
 * CPU runner matches bit-for-bit (the kernel is simply a no-op on non-check steps).
 *
 * NOT ported (flagged): v1's SEPARATE FilSegment/ProteinNode box force (FilSegment.bugForcesFrom
 * Inside: 0.1·min(fturn,ftrans), an extra torque-drag clamp). It is never exercised by any current
 * v2 assay — the contractile filaments are pinned + inset by a margin, so a free filament never
 * reaches a box wall. Per "abstract from the second instance", that variant is left for whenever a
 * future assay actually drives a free filament/node into a wall. This system uses v1's free-body
 * law (the one the drifting minifilament/node actually feels).
 *
 * THE SAFETY PROPERTY — no-op when not binding: a body fully inside the inset box yields delta == 0
 * on every endpoint/axis ⇒ NO write to forceSum/torqueSum at all (the accumulators are touched only
 * when there is real penetration). So adding this system to any harness whose bodies stay in-bounds
 * is BIT-IDENTICAL to not having it — the regression guard, by construction.
 *
 * Device-agnostic: one thread per body, each writing only its own slot (+=, race-free, no atomics,
 * no KernelContext); runs identically on the GPU TaskGraph and the sequential -cpu runner. Planar
 * SoA (plane stride N): body i is (i, N+i, 2N+i); segLength/brownTransScale are size-N scalars.
 *
 * boxParams (float): [0]=tau (collisionDeltaT, the force-denominator time, s)
 *                    [1]=boxXDim [2]=boxYDim [3]=boxZDim (µm, full extents; box centered at origin)
 *                    [4]=R (body-radius inset, µm)  [5]=coeff (nodeFracMove)  [6]=checkInt (cadence)
 * counts[1] = stepCount (the collision-cadence gate).
 */
public final class ContainmentSystem {
    private ContainmentSystem() {}

    public static void confine(
            FloatArray coord, FloatArray uVec, FloatArray segLength,
            FloatArray bTransGam, FloatArray forceSum, FloatArray torqueSum,
            FloatArray boxParams, IntArray counts) {

        int N = coord.getSize() / 3;
        int step    = counts.get(1);
        int checkInt = (int) boxParams.get(6);
        // collision cadence: v1 fires at simTime==0 and every collisionCheckInt steps ⇒ step % checkInt == 0
        if (checkInt > 1 && (step % checkInt) != 0) { return; }

        double tau   = boxParams.get(0);
        double halfX = 0.5 * boxParams.get(1) - boxParams.get(4);
        double halfY = 0.5 * boxParams.get(2) - boxParams.get(4);
        double halfZ = 0.5 * boxParams.get(3) - boxParams.get(4);
        double coeff = boxParams.get(5);

        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i, iz = 2 * N + i;
            double cx = coord.get(i), cy = coord.get(iy), cz = coord.get(iz);
            double ux = uVec.get(i),  uy = uVec.get(iy),  uz = uVec.get(iz);
            double half = 0.5 * (double) segLength.get(i);
            double bTGx = bTransGam.get(i);

            double fx = 0.0, fy = 0.0, fz = 0.0, tx = 0.0, ty = 0.0, tz = 0.0;
            boolean touched = false;

            // both endpoints: end1 (sgn=-1) and end2 (sgn=+1)
            for (int e = 0; e < 2; e++) {
                double sgn = (e == 0) ? -1.0 : 1.0;
                // endpoint relative to box center (origin) == v1's d = (ctr - chamberCenter)
                double dx = cx + sgn * half * ux;
                double dy = cy + sgn * half * uy;
                double dz = cz + sgn * half * uz;

                // amICollidingOuter: per-axis push, zeroed where the endpoint is still inside that wall
                double fux = wallPush(dx, halfX);
                double fuy = wallPush(dy, halfY);
                double fuz = wallPush(dz, halfZ);
                double delta2 = fux * fux + fuy * fuy + fuz * fuz;
                if (delta2 > 0.0) {
                    double delta = Math.sqrt(delta2);
                    double inv = 1.0 / delta;
                    double lux = fux * inv, luy = fuy * inv, luz = fuz * inv;   // unit forceUVec
                    double mag = coeff * 1.0e-6 * delta * bTGx / tau;            // Newtons
                    double Fx = mag * lux, Fy = mag * luy, Fz = mag * luz;
                    // r = (endpoint − center)·1e-6 (µm→m); torque = r × F
                    double rx = sgn * half * ux * 1.0e-6;
                    double ry = sgn * half * uy * 1.0e-6;
                    double rz = sgn * half * uz * 1.0e-6;
                    fx += Fx; fy += Fy; fz += Fz;
                    tx += ry * Fz - rz * Fy;
                    ty += rz * Fx - rx * Fz;
                    tz += rx * Fy - ry * Fx;
                    touched = true;
                }
            }

            // no-op when not binding: touch the accumulators ONLY on real penetration ⇒ bit-identical inside
            if (touched) {
                forceSum.set(i,  (float) ((double) forceSum.get(i)  + fx));
                forceSum.set(iy, (float) ((double) forceSum.get(iy) + fy));
                forceSum.set(iz, (float) ((double) forceSum.get(iz) + fz));
                torqueSum.set(i,  (float) ((double) torqueSum.get(i)  + tx));
                torqueSum.set(iy, (float) ((double) torqueSum.get(iy) + ty));
                torqueSum.set(iz, (float) ((double) torqueSum.get(iz) + tz));
            }
        }
    }

    /** One axis of v1's amICollidingOuter: push = sign(d)·halfDim − d, zeroed if it still points
     *  inward (sign(push) == sign(d)) — i.e. the endpoint is safely inside the inset wall on this axis. */
    private static double wallPush(double d, double halfDim) {
        double s = (d > 0.0) ? 1.0 : ((d < 0.0) ? -1.0 : 0.0);
        double push = s * halfDim - d;
        double ps = (push > 0.0) ? 1.0 : ((push < 0.0) ? -1.0 : 0.0);
        if (ps == s) { return 0.0; }
        return push;
    }
}
