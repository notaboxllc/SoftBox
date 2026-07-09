package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * System 2: brownianForceSystem (device kernel).
 *
 * Fills randForce / randTorque (body-frame, planar SoA) from the diffusion/drag
 * tensors and the REUSED v1 device RNG. RNG scheme and Box-Muller transform are
 * ported verbatim from v1 GPUMoveThing.moveThingKernel / wangHash
 * (~/Code/BoA-v1ref/boxOfActin/GPUMoveThing.java:3087-3230, 1083-1089), per
 * RESIDENCY_INVENTORY.md §3 ("reuse the Wang-hash RNG; no greenfield device-RNG
 * work"). Determinism is keyed on (slot, stepCount, runSeed) exactly as v1.
 *
 * Amplitude (matches both the v1 device kernel and the scalar CPU form in
 * PT3D_SOA_MIGRATION.md §"scalarization of Thing.calcRandomForces"):
 *
 *     randForce_i  = tScale * sqrt(2 kT / dt) * sqrt(bTransGam_i) * g_i
 *     randTorque_i = rScale * sqrt(2 kT / dt) * sqrt(bRotGam_i)  * g'_i
 *
 * which equals sqrt(2 kT gamma_i / dt) * g — the FDT-consistent amplitude, so the
 * displacement variance is 2 (kT/gamma_i) dt = 2 D_i dt. brownianForceMag =
 * sqrt(2kT/dt) arrives in params[1]. The body->frame split (force=cos, torque=sin
 * of the same three Box-Muller pairs) is exactly v1's.
 *
 * This system is the ONLY place Brownian forcing is applied (force-coverage audit:
 * Brownian applied exactly once; the integration system merely reads these arrays).
 */
public final class BrownianForceSystem {
    private BrownianForceSystem() {}

    // Wang hash — 32-bit integer mixer. VERBATIM v1 GPUMoveThing.java:1083-1089.
    private static int wangHash(int seed) {
        seed = (seed ^ 61) ^ (seed >>> 16);
        seed *= 9;
        seed = seed ^ (seed >>> 4);
        seed *= 0x27d4eb2d;
        seed = seed ^ (seed >>> 15);
        return seed;
    }

    public static void brownianForce(
            FloatArray randForce,
            FloatArray randTorque,
            FloatArray bTransGam,
            FloatArray bRotGam,
            FloatArray brownTransScale,
            FloatArray brownRotScale,
            FloatArray params,
            IntArray   counts) {

        int N = randForce.getSize() / 3;
        int stepCount = counts.get(1);
        int runSeed   = counts.get(2);
        float brownianForceMag = params.get(1);   // sqrt(2 kT / dt)

        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i;
            int iz = 2 * N + i;

            // --- deterministic per-(slot,step,run) RNG seed (v1 keying) ---
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

            // --- three Box-Muller pairs: cos -> force, sin -> torque (v1) ---
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
}
