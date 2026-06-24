package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * MEGAKERNEL PROBE (MEASUREMENT-ONLY) — device-agnostic FUSED hot-mechanics kernels.
 *
 * Recomposes the every-step, per-body-LOCAL mechanics that run over EVERY RigidRodBody store regardless of which
 * biology is loaded, collapsing many tiny kernel launches into a few larger ones WITHOUT changing the arithmetic or
 * the float op-order (the maximal device path is launch-COUNT-bound, PROFILE_FULLDEMO §"~8000-launch/s ceiling").
 * Each method is the EXACT inlined concatenation of the validated separate systems, in the SAME order, so it is
 * BIT-IDENTICAL to running them sequentially — and is called BOTH by the GPU TaskGraph (one task = one launch) and by
 * the -cpu runner (one method over the store's bodies), preserving the one-physics / CPU≡GPU invariant. No atomics, no
 * KernelContext (each thread writes only its own slot), so it lowers on the PTX backend and runs on -cpu identically.
 *
 * Two megakernels bracket the cross-entity gathers (which keep += into forceSum as separate kernels):
 *   (1) forceBuild           = zeroAccumulators + brownianForce  (writes forceSum=0 + randForce/randTorque)
 *       ... the cross-entity gathers (chain, seg-gather, tether, crosslinker) += into forceSum as separate kernels ...
 *   (2) integDerive[Confined]= [confine +] integrate + derive     (reads the fully-accumulated forceSum)
 *
 * The filament CHAIN force (ChainBendingForceSystem.chainForces, the ONE per-body-local force that reads neighbours)
 * is NOT folded into forceBuild: its 4 neighbour-topology IntArrays push the fused signature past TornadoVM's 15-arg
 * task() ceiling, and it is a SINGLE launch of ~74, so folding it would save 1 launch for a store-wide neighbour
 * repack. It stays its own kernel; forceBuild only zeroes+Brownian, exactly the pre-gather state the chain += onto
 * (chain is the FIRST forceSum accumulation in both paths ⇒ order preserved bit-exactly).
 *
 * Param layout (uniform across stores): params[0]=dt, params[1]=brownianForceMag (= sqrt(2kT/dt)).
 * boxParams: [0]=tau [1..3]=box dims [4]=R inset [5]=coeff [6]=checkInt [7]=dt  (dt added at [7] for the confined
 * megakernel so it stays ≤15 args without a separate per-store integrate-params slot).
 */
public final class MechanicsFusion {
    private MechanicsFusion() {}

    // Wang hash — VERBATIM v1 GPUMoveThing.java:1083-1089 (identical to BrownianForceSystem.wangHash).
    private static int wangHash(int seed) {
        seed = (seed ^ 61) ^ (seed >>> 16);
        seed *= 9;
        seed = seed ^ (seed >>> 4);
        seed *= 0x27d4eb2d;
        seed = seed ^ (seed >>> 15);
        return seed;
    }

    /**
     * MEGAKERNEL 1 — fused zeroAccumulators + brownianForce (per body, all stores).
     * forceSum/torqueSum set to 0; randForce/randTorque filled from the FDT amplitude + the v1 RNG.
     * EXACT concatenation of ChainBendingForceSystem.zeroAccumulators and BrownianForceSystem.brownianForce
     * (same arithmetic, same per-i order) ⇒ bit-identical to running them as two kernels.
     */
    public static void forceBuild(
            FloatArray forceSum, FloatArray torqueSum,
            FloatArray randForce, FloatArray randTorque,
            FloatArray bTransGam, FloatArray bRotGam,
            FloatArray brownTransScale, FloatArray brownRotScale,
            FloatArray params, IntArray counts) {

        int N = forceSum.getSize() / 3;
        int stepCount = counts.get(1);
        int runSeed   = counts.get(2);
        float brownianForceMag = params.get(1);   // sqrt(2 kT / dt)

        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i;
            int iz = 2 * N + i;

            // --- zeroAccumulators ---
            forceSum.set(i, 0f);  forceSum.set(iy, 0f);  forceSum.set(iz, 0f);
            torqueSum.set(i, 0f); torqueSum.set(iy, 0f); torqueSum.set(iz, 0f);

            // --- brownianForce (v1 keying, three Box-Muller pairs: cos->force, sin->torque) ---
            int base = (i * 1000003) ^ (stepCount * 999983) ^ (runSeed * 7919);
            int h1 = wangHash(base);
            int h2 = wangHash(base ^ 0x9e3779b9);
            int h3 = wangHash(base ^ 0x85ebca6b);
            int h4 = wangHash(base ^ 0xc2b2ae35);
            int h5 = wangHash(base ^ 0x517cc1b7);
            int h6 = wangHash(base ^ 0x1f0a7ed5);

            float u1 = Math.max(1.0e-7f, (h1 >>> 1) / 2147483647.0f);
            float u2 = (h2 >>> 1) / 2147483647.0f;
            float u3 = Math.max(1.0e-7f, (h3 >>> 1) / 2147483647.0f);
            float u4 = (h4 >>> 1) / 2147483647.0f;
            float u5 = Math.max(1.0e-7f, (h5 >>> 1) / 2147483647.0f);
            float u6 = (h6 >>> 1) / 2147483647.0f;

            float r1 = (float) Math.sqrt(-2.0f * (float) Math.log(u1));
            float th1 = 2.0f * 3.14159265f * u2;
            float gfx = r1 * (float) Math.cos(th1);
            float gtx = r1 * (float) Math.sin(th1);

            float r2 = (float) Math.sqrt(-2.0f * (float) Math.log(u3));
            float th2 = 2.0f * 3.14159265f * u4;
            float gfy = r2 * (float) Math.cos(th2);
            float gty = r2 * (float) Math.sin(th2);

            float r3 = (float) Math.sqrt(-2.0f * (float) Math.log(u5));
            float th3 = 2.0f * 3.14159265f * u6;
            float gfz = r3 * (float) Math.cos(th3);
            float gtz = r3 * (float) Math.sin(th3);

            float tS = brownTransScale.get(i);
            float rS = brownRotScale.get(i);

            randForce.set(i,  tS * brownianForceMag * (float) Math.sqrt(bTransGam.get(i))  * gfx);
            randForce.set(iy, tS * brownianForceMag * (float) Math.sqrt(bTransGam.get(iy)) * gfy);
            randForce.set(iz, tS * brownianForceMag * (float) Math.sqrt(bTransGam.get(iz)) * gfz);

            randTorque.set(i,  rS * brownianForceMag * (float) Math.sqrt(bRotGam.get(i))  * gtx);
            randTorque.set(iy, rS * brownianForceMag * (float) Math.sqrt(bRotGam.get(iy)) * gty);
            randTorque.set(iz, rS * brownianForceMag * (float) Math.sqrt(bRotGam.get(iz)) * gtz);
        }
    }

    /**
     * MEGAKERNEL 2 (unconfined: motor sub-bodies) — fused integrate + derive (per body).
     * EXACT concatenation of RigidRodLangevinIntegrationSystem.integrate and DerivedGeometrySystem.derive.
     * dt = params[0]. No confine (motors are not boxed, matching cpuStep / blkInteg).
     */
    public static void integDerive(
            FloatArray coord, FloatArray uVec, FloatArray yVec, FloatArray zVec, FloatArray segLength,
            FloatArray end1, FloatArray end2,
            FloatArray forceSum, FloatArray torqueSum, FloatArray randForce, FloatArray randTorque,
            FloatArray bTransGam, FloatArray bRotGam, FloatArray params) {

        int N = coord.getSize() / 3;
        float dt = params.get(0);
        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i;
            int iz = 2 * N + i;

            // ===== integrate (RigidRodLangevinIntegrationSystem.integrate, verbatim) =====
            float ux = uVec.get(i), uyc = uVec.get(iy), uzc = uVec.get(iz);
            float yx = yVec.get(i), yyc = yVec.get(iy), yzc = yVec.get(iz);
            float zx = uyc * yzc - uzc * yyc;
            float zy = uzc * yx  - ux  * yzc;
            float zz = ux  * yyc - uyc * yx;
            float zlen = 1.0f / (float) Math.sqrt(zx * zx + zy * zy + zz * zz);
            zx *= zlen; zy *= zlen; zz *= zlen;
            float fx = forceSum.get(i), fy = forceSum.get(iy), fz = forceSum.get(iz);
            float tx = torqueSum.get(i), ty = torqueSum.get(iy), tz = torqueSum.get(iz);
            float bfx = ux * fx + uyc * fy + uzc * fz + randForce.get(i);
            float bfy = yx * fx + yyc * fy + yzc * fz + randForce.get(iy);
            float bfz = zx * fx + zy  * fy + zz  * fz + randForce.get(iz);
            float btx = ux * tx + uyc * ty + uzc * tz + randTorque.get(i);
            float bty = yx * tx + yyc * ty + yzc * tz + randTorque.get(iy);
            float btz = zx * tx + zy  * ty + zz  * tz + randTorque.get(iz);
            float bvx = 1.0e6f * bfx / bTransGam.get(i);
            float bvy = 1.0e6f * bfy / bTransGam.get(iy);
            float bvz = 1.0e6f * bfz / bTransGam.get(iz);
            float bwx = btx / bRotGam.get(i);
            float bwy = bty / bRotGam.get(iy);
            float bwz = btz / bRotGam.get(iz);
            float vx = ux * bvx + yx * bvy + zx * bvz;
            float vy = uyc * bvx + yyc * bvy + zy * bvz;
            float vz = uzc * bvx + yzc * bvy + zz * bvz;
            coord.set(i,  coord.get(i)  + dt * vx);
            coord.set(iy, coord.get(iy) + dt * vy);
            coord.set(iz, coord.get(iz) + dt * vz);
            float uTransInZ = -bwy * dt;
            float uTransInY =  bwz * dt;
            float nuX = ux  + yx  * uTransInY + zx * uTransInZ;
            float nuY = uyc + yyc * uTransInY + zy * uTransInZ;
            float nuZ = uzc + yzc * uTransInY + zz * uTransInZ;
            float nuInv = 1.0f / (float) Math.sqrt(nuX * nuX + nuY * nuY + nuZ * nuZ);
            float nux = nuX * nuInv, nuy = nuY * nuInv, nuz = nuZ * nuInv;
            uVec.set(i, nux); uVec.set(iy, nuy); uVec.set(iz, nuz);
            float yTransInX = -uTransInY;
            float yTransInZ =  bwx * dt;
            float nyX = ux  * yTransInX + yx  + zx * yTransInZ;   // ORIGINAL ux (matches the source kernel)
            float nyY = uyc * yTransInX + yyc + zy * yTransInZ;
            float nyZ = uzc * yTransInX + yzc + zz * yTransInZ;
            float nyInv = 1.0f / (float) Math.sqrt(nyX * nyX + nyY * nyY + nyZ * nyZ);
            float ny0 = nyX * nyInv, ny1 = nyY * nyInv, ny2 = nyZ * nyInv;
            yVec.set(i, ny0); yVec.set(iy, ny1); yVec.set(iz, ny2);

            // ===== derive (DerivedGeometrySystem.derive, verbatim — UPDATED uVec/yVec) =====
            float dzx = nuy * ny2 - nuz * ny1;
            float dzy = nuz * ny0 - nux * ny2;
            float dzz = nux * ny1 - nuy * ny0;
            float zmag2 = dzx * dzx + dzy * dzy + dzz * dzz;
            if (zmag2 > 0f) {
                float inv = 1.0f / (float) Math.sqrt(zmag2);
                dzx *= inv; dzy *= inv; dzz *= inv;
            }
            zVec.set(i, dzx); zVec.set(iy, dzy); zVec.set(iz, dzz);
            float nyx = dzy * nuz - dzz * nuy;
            float nyy = dzz * nux - dzx * nuz;
            float nyz = dzx * nuy - dzy * nux;
            yVec.set(i, nyx); yVec.set(iy, nyy); yVec.set(iz, nyz);
            float cx = coord.get(i), cy = coord.get(iy), cz = coord.get(iz);
            float halfLen = segLength.get(i) * 0.5f;
            end1.set(i,  cx - halfLen * nux);
            end1.set(iy, cy - halfLen * nuy);
            end1.set(iz, cz - halfLen * nuz);
            end2.set(i,  cx + halfLen * nux);
            end2.set(iy, cy + halfLen * nuy);
            end2.set(iz, cz + halfLen * nuz);
        }
    }

    /**
     * MEGAKERNEL 2 (confined: node / backbone / filament stores) — fused confine + integrate + derive.
     * confine = the v1 free-body box law (double precision, cadence-gated by counts[1] % boxParams[6]); += forceSum.
     * Then integrate (dt = boxParams[7]) + derive. On a non-cadence step the confine block is skipped (forceSum read
     * as-is) — bit-identical to ContainmentSystem.confine's whole-kernel early-return then a separate integrate.
     */
    public static void integDeriveConfined(
            FloatArray coord, FloatArray uVec, FloatArray yVec, FloatArray zVec, FloatArray segLength,
            FloatArray end1, FloatArray end2,
            FloatArray forceSum, FloatArray torqueSum, FloatArray randForce, FloatArray randTorque,
            FloatArray bTransGam, FloatArray bRotGam, FloatArray boxParams, IntArray counts) {

        int N = coord.getSize() / 3;
        int step    = counts.get(1);
        int checkInt = (int) boxParams.get(6);
        boolean doConfine = !(checkInt > 1 && (step % checkInt) != 0);
        double tau   = boxParams.get(0);
        double halfX = 0.5 * boxParams.get(1) - boxParams.get(4);
        double halfY = 0.5 * boxParams.get(2) - boxParams.get(4);
        double halfZ = 0.5 * boxParams.get(3) - boxParams.get(4);
        double coeff = boxParams.get(5);
        float dt = boxParams.get(7);

        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i, iz = 2 * N + i;

            // ---- confine (ContainmentSystem.confine, verbatim; no-op off-cadence / fully inside) ----
            if (doConfine) {
                double cx = coord.get(i), cy = coord.get(iy), cz = coord.get(iz);
                double ux = uVec.get(i),  uy = uVec.get(iy),  uz = uVec.get(iz);
                double half = 0.5 * (double) segLength.get(i);
                double bTGx = bTransGam.get(i);
                double fx = 0.0, fy = 0.0, fz = 0.0, tx = 0.0, ty = 0.0, tz = 0.0;
                boolean touched = false;
                for (int e = 0; e < 2; e++) {
                    double sgn = (e == 0) ? -1.0 : 1.0;
                    double dx = cx + sgn * half * ux;
                    double dy = cy + sgn * half * uy;
                    double dz = cz + sgn * half * uz;
                    double fux = wallPush(dx, halfX);
                    double fuy = wallPush(dy, halfY);
                    double fuz = wallPush(dz, halfZ);
                    double delta2 = fux * fux + fuy * fuy + fuz * fuz;
                    if (delta2 > 0.0) {
                        double delta = Math.sqrt(delta2);
                        double inv = 1.0 / delta;
                        double lux = fux * inv, luy = fuy * inv, luz = fuz * inv;
                        double mag = coeff * 1.0e-6 * delta * bTGx / tau;
                        double Fx = mag * lux, Fy = mag * luy, Fz = mag * luz;
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
                if (touched) {
                    forceSum.set(i,  (float) ((double) forceSum.get(i)  + fx));
                    forceSum.set(iy, (float) ((double) forceSum.get(iy) + fy));
                    forceSum.set(iz, (float) ((double) forceSum.get(iz) + fz));
                    torqueSum.set(i,  (float) ((double) torqueSum.get(i)  + tx));
                    torqueSum.set(iy, (float) ((double) torqueSum.get(iy) + ty));
                    torqueSum.set(iz, (float) ((double) torqueSum.get(iz) + tz));
                }
            }

            // ---- integrate + derive (inlined; helper would exceed the PTX 600-node inlined-callee cap) ----
            float ux = uVec.get(i), uyc = uVec.get(iy), uzc = uVec.get(iz);
            float yx = yVec.get(i), yyc = yVec.get(iy), yzc = yVec.get(iz);
            float zx = uyc * yzc - uzc * yyc;
            float zy = uzc * yx  - ux  * yzc;
            float zz = ux  * yyc - uyc * yx;
            float zlen = 1.0f / (float) Math.sqrt(zx * zx + zy * zy + zz * zz);
            zx *= zlen; zy *= zlen; zz *= zlen;
            float fxs = forceSum.get(i), fys = forceSum.get(iy), fzs = forceSum.get(iz);
            float txs = torqueSum.get(i), tys = torqueSum.get(iy), tzs = torqueSum.get(iz);
            float bfx = ux * fxs + uyc * fys + uzc * fzs + randForce.get(i);
            float bfy = yx * fxs + yyc * fys + yzc * fzs + randForce.get(iy);
            float bfz = zx * fxs + zy  * fys + zz  * fzs + randForce.get(iz);
            float btx = ux * txs + uyc * tys + uzc * tzs + randTorque.get(i);
            float bty = yx * txs + yyc * tys + yzc * tzs + randTorque.get(iy);
            float btz = zx * txs + zy  * tys + zz  * tzs + randTorque.get(iz);
            float bvx = 1.0e6f * bfx / bTransGam.get(i);
            float bvy = 1.0e6f * bfy / bTransGam.get(iy);
            float bvz = 1.0e6f * bfz / bTransGam.get(iz);
            float bwx = btx / bRotGam.get(i);
            float bwy = bty / bRotGam.get(iy);
            float bwz = btz / bRotGam.get(iz);
            float vx = ux * bvx + yx * bvy + zx * bvz;
            float vy = uyc * bvx + yyc * bvy + zy * bvz;
            float vz = uzc * bvx + yzc * bvy + zz * bvz;
            coord.set(i,  coord.get(i)  + dt * vx);
            coord.set(iy, coord.get(iy) + dt * vy);
            coord.set(iz, coord.get(iz) + dt * vz);
            float uTransInZ = -bwy * dt;
            float uTransInY =  bwz * dt;
            float nuX = ux  + yx  * uTransInY + zx * uTransInZ;
            float nuY = uyc + yyc * uTransInY + zy * uTransInZ;
            float nuZ = uzc + yzc * uTransInY + zz * uTransInZ;
            float nuInv = 1.0f / (float) Math.sqrt(nuX * nuX + nuY * nuY + nuZ * nuZ);
            float nux = nuX * nuInv, nuy = nuY * nuInv, nuz = nuZ * nuInv;
            uVec.set(i, nux); uVec.set(iy, nuy); uVec.set(iz, nuz);
            float yTransInX = -uTransInY;
            float yTransInZ =  bwx * dt;
            float nyX = ux  * yTransInX + yx  + zx * yTransInZ;   // ORIGINAL ux (matches the source kernel)
            float nyY = uyc * yTransInX + yyc + zy * yTransInZ;
            float nyZ = uzc * yTransInX + yzc + zz * yTransInZ;
            float nyInv = 1.0f / (float) Math.sqrt(nyX * nyX + nyY * nyY + nyZ * nyZ);
            float ny0 = nyX * nyInv, ny1 = nyY * nyInv, ny2 = nyZ * nyInv;
            yVec.set(i, ny0); yVec.set(iy, ny1); yVec.set(iz, ny2);
            float dzx = nuy * ny2 - nuz * ny1;
            float dzy = nuz * ny0 - nux * ny2;
            float dzz = nux * ny1 - nuy * ny0;
            float zmag2 = dzx * dzx + dzy * dzy + dzz * dzz;
            if (zmag2 > 0f) {
                float inv = 1.0f / (float) Math.sqrt(zmag2);
                dzx *= inv; dzy *= inv; dzz *= inv;
            }
            zVec.set(i, dzx); zVec.set(iy, dzy); zVec.set(iz, dzz);
            float nyx = dzy * nuz - dzz * nuy;
            float nyy = dzz * nux - dzx * nuz;
            float nyz = dzx * nuy - dzy * nux;
            yVec.set(i, nyx); yVec.set(iy, nyy); yVec.set(iz, nyz);
            float cx = coord.get(i), cy = coord.get(iy), cz = coord.get(iz);
            float halfLen = segLength.get(i) * 0.5f;
            end1.set(i,  cx - halfLen * nux);
            end1.set(iy, cy - halfLen * nuy);
            end1.set(iz, cz - halfLen * nuz);
            end2.set(i,  cx + halfLen * nux);
            end2.set(iy, cy + halfLen * nuy);
            end2.set(iz, cz + halfLen * nuz);
        }
    }

    /** One axis of v1's amICollidingOuter (ContainmentSystem.wallPush, verbatim). */
    private static double wallPush(double d, double halfDim) {
        double s = (d > 0.0) ? 1.0 : ((d < 0.0) ? -1.0 : 0.0);
        double push = s * halfDim - d;
        double ps = (push > 0.0) ? 1.0 : ((push < 0.0) ? -1.0 : 0.0);
        if (ps == s) { return 0.0; }
        return push;
    }
}
