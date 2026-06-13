package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * System 3: rigidRodLangevinIntegrationSystem (device kernel) — the core.
 *
 * Overdamped (no inertia) explicit-Euler Langevin step. Direct port of v1
 * FilSegment.moveThing() (~/Code/BoA-v1ref/boxOfActin/FilSegment.java:609-685)
 * fused with the device integration in GPUMoveThing.moveThingKernel
 * (GPUMoveThing.java:3087-3230), minus the inlined Brownian (now System 2) and
 * the velMask (identity for free rods).
 *
 * Per segment:
 *   1. zVec = uVec x yVec (normalized).
 *   2. body force  = R^T * forceSum (lab->body) + randForce (already body-frame).
 *      body torque = R^T * torqueSum            + randTorque.
 *      For a free rod forceSum = torqueSum = 0, so only Brownian drives motion.
 *   3. body velocity      bv = 1e6 * bForce / bTransGam     (microns/s; 1e6 = m->micron)
 *      body angular vel   bw =       bTorque / bRotGam      (rad/s)
 *   4. coord += dt * (R * bv)   (lab frame).
 *   5. uVec, yVec rotated by the small-angle body update, then renormalized — exactly
 *      v1's scratch.setVals(...).xToX(this).unitVec() sequence written out in scalars.
 *
 * R = [uVec; yVec; zVec] row-major; R^T maps lab->body, R maps body->lab (the xToX /
 * XTox transforms in Pt3D.java:439/515).
 */
public final class RigidRodLangevinIntegrationSystem {
    private RigidRodLangevinIntegrationSystem() {}

    public static void integrate(
            FloatArray coord,
            FloatArray uVec,
            FloatArray yVec,
            FloatArray forceSum,
            FloatArray torqueSum,
            FloatArray randForce,
            FloatArray randTorque,
            FloatArray bTransGam,
            FloatArray bRotGam,
            FloatArray params,
            IntArray   counts) {

        int N = coord.getSize() / 3;
        float dt = params.get(0);

        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i;
            int iz = 2 * N + i;

            float ux = uVec.get(i), uyc = uVec.get(iy), uzc = uVec.get(iz);
            float yx = yVec.get(i), yyc = yVec.get(iy), yzc = yVec.get(iz);

            // zVec = uVec x yVec, normalized
            float zx = uyc * yzc - uzc * yyc;
            float zy = uzc * yx  - ux  * yzc;
            float zz = ux  * yyc - uyc * yx;
            float zlen = 1.0f / (float) Math.sqrt(zx * zx + zy * zy + zz * zz);
            zx *= zlen; zy *= zlen; zz *= zlen;

            // deterministic lab-frame force/torque (zero for free rod, but ported faithfully)
            float fx = forceSum.get(i), fy = forceSum.get(iy), fz = forceSum.get(iz);
            float tx = torqueSum.get(i), ty = torqueSum.get(iy), tz = torqueSum.get(iz);

            // lab->body (R^T), then add body-frame Brownian
            float bfx = ux * fx + uyc * fy + uzc * fz + randForce.get(i);
            float bfy = yx * fx + yyc * fy + yzc * fz + randForce.get(iy);
            float bfz = zx * fx + zy  * fy + zz  * fz + randForce.get(iz);
            float btx = ux * tx + uyc * ty + uzc * tz + randTorque.get(i);
            float bty = yx * tx + yyc * ty + yzc * tz + randTorque.get(iy);
            float btz = zx * tx + zy  * ty + zz  * tz + randTorque.get(iz);

            // overdamped EOM: v = F/gamma  (1e6: meters/s -> microns/s),  w = T/gamma
            float bvx = 1.0e6f * bfx / bTransGam.get(i);
            float bvy = 1.0e6f * bfy / bTransGam.get(iy);
            float bvz = 1.0e6f * bfz / bTransGam.get(iz);
            float bwx = btx / bRotGam.get(i);
            float bwy = bty / bRotGam.get(iy);
            float bwz = btz / bRotGam.get(iz);

            // body->lab (R) velocity, integrate position
            float vx = ux * bvx + yx * bvy + zx * bvz;
            float vy = uyc * bvx + yyc * bvy + zy * bvz;
            float vz = uzc * bvx + yzc * bvy + zz * bvz;
            coord.set(i,  coord.get(i)  + dt * vx);
            coord.set(iy, coord.get(iy) + dt * vy);
            coord.set(iz, coord.get(iz) + dt * vz);

            // orientation update (v1 FilSegment.moveThing:669-682, written in scalars)
            float uTransInZ = -bwy * dt;
            float uTransInY =  bwz * dt;
            float nuX = ux  + yx  * uTransInY + zx * uTransInZ;
            float nuY = uyc + yyc * uTransInY + zy * uTransInZ;
            float nuZ = uzc + yzc * uTransInY + zz * uTransInZ;
            float nuInv = 1.0f / (float) Math.sqrt(nuX * nuX + nuY * nuY + nuZ * nuZ);
            uVec.set(i,  nuX * nuInv);
            uVec.set(iy, nuY * nuInv);
            uVec.set(iz, nuZ * nuInv);

            float yTransInX = -uTransInY;
            float yTransInZ =  bwx * dt;
            float nyX = ux  * yTransInX + yx  + zx * yTransInZ;
            float nyY = uyc * yTransInX + yyc + zy * yTransInZ;
            float nyZ = uzc * yTransInX + yzc + zz * yTransInZ;
            float nyInv = 1.0f / (float) Math.sqrt(nyX * nyX + nyY * nyY + nyZ * nyZ);
            yVec.set(i,  nyX * nyInv);
            yVec.set(iy, nyY * nyInv);
            yVec.set(iz, nyZ * nyInv);
        }
    }
}
