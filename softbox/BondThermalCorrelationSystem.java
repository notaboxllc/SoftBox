package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * BOUND_THERMAL_CORRELATION (measurement, flag-gated, default-off): constraint-aware thermal forcing
 * of a bound cross-bridge — correlate the bound head's thermal kicks with its filament contact's.
 *
 * WHY (physical, not a hack). The cross-bridge force F8 = myoSpring·(head_tip − foot) reads the
 * RELATIVE displacement of the head and its actin contact. When the bound head and the filament
 * segment receive INDEPENDENT thermal kicks, the relative separation fluctuates by the SUM of two
 * independent noises and the stiff spring rectifies that into a force spike — fattening the negative
 * tail of the signed load forceDotFil, to which the catch release e^(−F·xCatch) is exponentially
 * sensitive (the explicit integrator inflates exactly this ∝ dt). But a bound cross-bridge is a single
 * mechanically-coupled object: the bond constrains the relative DOF, so by FDT it should NOT be
 * thermally excited as if free. Giving head and filament INDEPENDENT kicks OVER-counts the relative
 * fluctuation; correlating their thermal kicks removes the spurious relative noise at its source — the
 * noise-side analog of treating a stiff bond as a constraint rather than a stiff potential.
 *
 * FORMULATION (variance-preserving α knob). Correlate the UNIT-VARIANCE thermal random draws, then
 * apply each body's own FDT amplitude — so α tunes pure CORRELATION without changing any body's
 * marginal variance (temperature):
 *   η_head = α·η_fil + √(1−α²)·ξ_head        (per Cartesian component, ISOTROPIC)
 * with η_fil ~ N(0,1) the bound segment's RAW Brownian draw this step (NOT its net force — that would
 * re-inject the chain-spring overshoot; the correlation is about NOISE only) and ξ_head ~ N(0,1) the
 * head's own independent draw this step. Var(η_head)=1 (preserved), Corr(η_head,η_fil)=α. α=0 ⇒ the
 * current independent forcing (byte-identical to BrownianForceSystem's output for the head); α=1 ⇒
 * identical unit draws (rigid co-motion of the unit noise, zero relative thermal fluctuation when the
 * two bodies' amplitudes match). The √(1−α²) split moves noise from the RELATIVE coordinate to the
 * COMMON coordinate without changing the head's temperature.
 *
 * IMPLEMENTATION. Each bound head belongs to exactly one motor and reads exactly one filament segment
 * (single pair) ⇒ this is a self-write, NOT a gather: each motor recomputes its head sub-body's raw
 * draw ξ_head and its bound segment's raw draw η_fil (both reproduced bit-for-bit from the SAME
 * wang-hash keying + Box-Muller as BrownianForceSystem, keyed (slot, step, runSeed)), blends, applies
 * the HEAD's own FDT amplitude, and OVERWRITES randForce[head]. Race-free (each motor writes only its
 * own head sub-body slot 3m+2), no atomics/KernelContext, runs on both runners. Runs AFTER the motor
 * Brownian kernel (which it overwrites for bound heads; free heads keep their independent draw).
 *
 * SCOPE: head↔filament-contact ONLY (the bond producing the F8 relative fluctuation); TRANSLATIONAL
 * kicks only (the head tip = center + ½·HEAD_LEN·uVec is dominated by the head-center translation; the
 * rotational/lever wobble is a deferred 2nd-order term — neck/tail also deferred). Isotropic (same α on
 * all 3 components); an axis-projected variant (share only the bond-axial component) is noted as a
 * design option in BOUND_THERMAL_CORRELATION_FINDINGS.md.
 *
 * The marginal variance preservation makes this a constraint-aware THERMOSTAT, not a damping hack:
 * temperature unchanged, only the relative-mode correlation tuned.
 *
 * corrParams (float): [0]=alpha.
 *
 * GENERALIZES to crosslinkers (bound to two filaments — the identical over-counted-relative-noise
 * problem): the same recompute-the-partner's-raw-draw-and-blend mechanism ports, two-ended (correlate
 * to both bound segments). See findings. NOTE (future, not solved here): at higher density MULTIPLE
 * heads bind one filament ⇒ naive pairwise α can over-correlate the shared filament — clean for a
 * single motor per segment, harder at ring scale.
 */
public final class BondThermalCorrelationSystem {
    private BondThermalCorrelationSystem() {}

    // Wang hash — VERBATIM v1 GPUMoveThing.java:1083-1089 (identical to BrownianForceSystem.wangHash).
    private static int wangHash(int seed) {
        seed = (seed ^ 61) ^ (seed >>> 16);
        seed *= 9; seed = seed ^ (seed >>> 4);
        seed *= 0x27d4eb2d; seed = seed ^ (seed >>> 15);
        return seed;
    }

    /**
     * For each bound motor, overwrite its head sub-body's TRANSLATIONAL Brownian force with the
     * α-correlated draw. Bit-identical to the un-correlated head force at α=0.
     *
     * @param boundSeg        nMotors — the bound segment slot (or FREE_*); free heads are skipped (keep their draw)
     * @param randForce       motor body randForce (9*nMotors planar SoA over 3*nMotors sub-bodies) — OVERWRITTEN at head slots
     * @param bTransGam       motor body translational drag (per sub-body, planar SoA)
     * @param brownTransScale motor body translational Brownian scale (per sub-body)
     * @param bodyParams      motor bodyParams: [1]=brownianForceMag = sqrt(2kT/dt)
     * @param motCounts       motor counts: [1]=stepCount [2]=runSeed (head RNG keying — matches the motor Brownian)
     * @param filCounts       filament counts: [1]=stepCount [2]=runSeed (segment RNG keying — matches the filament Brownian)
     * @param corrParams      [0]=alpha
     */
    public static void correlateBoundHead(
            IntArray boundSeg, FloatArray randForce, FloatArray bTransGam, FloatArray brownTransScale,
            FloatArray bodyParams, IntArray motCounts, IntArray filCounts, FloatArray corrParams) {

        int nB = randForce.getSize() / 3;        // number of motor sub-bodies (= 3*nMotors)
        int nM = boundSeg.getSize();             // nMotors
        int motStep = motCounts.get(1), motRun = motCounts.get(2);
        int filStep = filCounts.get(1), filRun = filCounts.get(2);
        float mag = bodyParams.get(1);           // sqrt(2 kT / dt)
        float alpha = corrParams.get(0);
        float beta = (float) Math.sqrt(1.0f - alpha * alpha);   // α=0 ⇒ β=1 ⇒ head draw unchanged (bit-identical)

        for (@Parallel int m = 0; m < nM; m++) {
            int s = boundSeg.get(m);
            if (s < 0) continue;                 // free head: keep BrownianForceSystem's independent draw

            int h = 3 * m + 2;                   // head sub-body flat index
            int iy = nB + h, iz = 2 * nB + h;

            // --- ξ_head: the head's OWN raw N(0,1) force draws (cos terms) — EXACT BrownianForceSystem arithmetic ---
            int bH = (h * 1000003) ^ (motStep * 999983) ^ (motRun * 7919);
            int hH1 = wangHash(bH);
            int hH2 = wangHash(bH ^ 0x9e3779b9);
            int hH3 = wangHash(bH ^ 0x85ebca6b);
            int hH4 = wangHash(bH ^ 0xc2b2ae35);
            int hH5 = wangHash(bH ^ 0x517cc1b7);
            int hH6 = wangHash(bH ^ 0x1f0a7ed5);
            float uH1 = Math.max(1.0e-7f, (hH1 >>> 1) / 2147483647.0f);
            float uH2 = (hH2 >>> 1) / 2147483647.0f;
            float uH3 = Math.max(1.0e-7f, (hH3 >>> 1) / 2147483647.0f);
            float uH4 = (hH4 >>> 1) / 2147483647.0f;
            float uH5 = Math.max(1.0e-7f, (hH5 >>> 1) / 2147483647.0f);
            float uH6 = (hH6 >>> 1) / 2147483647.0f;
            float xHx = (float) Math.sqrt(-2.0f * (float) Math.log(uH1)) * (float) Math.cos(2.0f * 3.14159265f * uH2);
            float xHy = (float) Math.sqrt(-2.0f * (float) Math.log(uH3)) * (float) Math.cos(2.0f * 3.14159265f * uH4);
            float xHz = (float) Math.sqrt(-2.0f * (float) Math.log(uH5)) * (float) Math.cos(2.0f * 3.14159265f * uH6);

            // --- η_fil: the bound segment's RAW N(0,1) force draws (cos terms), same keying with the FILAMENT's slot+counts ---
            int bF = (s * 1000003) ^ (filStep * 999983) ^ (filRun * 7919);
            int hF1 = wangHash(bF);
            int hF2 = wangHash(bF ^ 0x9e3779b9);
            int hF3 = wangHash(bF ^ 0x85ebca6b);
            int hF4 = wangHash(bF ^ 0xc2b2ae35);
            int hF5 = wangHash(bF ^ 0x517cc1b7);
            int hF6 = wangHash(bF ^ 0x1f0a7ed5);
            float uF1 = Math.max(1.0e-7f, (hF1 >>> 1) / 2147483647.0f);
            float uF2 = (hF2 >>> 1) / 2147483647.0f;
            float uF3 = Math.max(1.0e-7f, (hF3 >>> 1) / 2147483647.0f);
            float uF4 = (hF4 >>> 1) / 2147483647.0f;
            float uF5 = Math.max(1.0e-7f, (hF5 >>> 1) / 2147483647.0f);
            float uF6 = (hF6 >>> 1) / 2147483647.0f;
            float eFx = (float) Math.sqrt(-2.0f * (float) Math.log(uF1)) * (float) Math.cos(2.0f * 3.14159265f * uF2);
            float eFy = (float) Math.sqrt(-2.0f * (float) Math.log(uF3)) * (float) Math.cos(2.0f * 3.14159265f * uF4);
            float eFz = (float) Math.sqrt(-2.0f * (float) Math.log(uF5)) * (float) Math.cos(2.0f * 3.14159265f * uF6);

            // --- blend (variance-preserving): η_head = α·η_fil + √(1−α²)·ξ_head ---
            float gx = alpha * eFx + beta * xHx;
            float gy = alpha * eFy + beta * xHy;
            float gz = alpha * eFz + beta * xHz;

            // --- apply the HEAD's own FDT amplitude (same multiplication order as BrownianForceSystem) ---
            float tS = brownTransScale.get(h);
            randForce.set(h,  tS * mag * (float) Math.sqrt(bTransGam.get(h))  * gx);
            randForce.set(iy, tS * mag * (float) Math.sqrt(bTransGam.get(iy)) * gy);
            randForce.set(iz, tS * mag * (float) Math.sqrt(bTransGam.get(iz)) * gz);
        }
    }
}
