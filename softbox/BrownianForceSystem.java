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

    /**
     * CONSTRAINED-VARIANCE bond-noise correction (CONSTRAINED_VARIANCE_PROBE.md). A BOUND motor's head
     * sub-body is NOT a free body — it sits in the F8 cross-bridge harmonic well (stiffness ≈ k_F8), so
     * explicit Euler–Maruyama over-fluctuates its thermal variance to (kT/k)·2/(2−α), α = k_F8·dt/γ_head.
     * Restore Var → kT/k by scaling the bound head's TRANSLATIONAL Brownian amplitude by √((2−α)/2)
     * (dt-adaptive: α→0 ⇒ factor→1, no correction as dt→0). Per motor, writes ONLY its own head slot
     * (3m+2) ⇒ race-free, no atomics (the segment-side correction is NOT race-free — a segment is shared
     * by several motors — so it is deliberately not done here; the head is the dominant low-drag mode).
     * scaleParams = [baseScale, boundFactor]; free heads keep baseScale. Rotational Brownian untouched
     * (the F8 bond is a translational mode). Default-off ⇒ never called ⇒ byte-identical.
     */
    public static void scaleBoundHeadNoise(
            FloatArray brownTransScale, IntArray boundSeg, FloatArray scaleParams, IntArray counts) {
        int nM = boundSeg.getSize();
        float base = scaleParams.get(0), fac = scaleParams.get(1);
        for (@Parallel int m = 0; m < nM; m++) {
            int head = 3 * m + 2;
            brownTransScale.set(head, boundSeg.get(m) >= 0 ? base * fac : base);
        }
    }

    /**
     * FULL constrained-mode correction (-allnoise, CONSTRAINED_VARIANCE_PROBE.md). Extends the bound-head
     * translational fix to the head ROTATION (F9/F10 orientation well, fracMove) and the anchored ROD
     * (tail-anchor + J2, fracMove structural) — all per-motor OWN-SLOT writes (head 3m+2, rod 3m) ⇒
     * race-free, no atomics. scaleParams=[base, headTransFac, headRotFac, rodTransFac, doHeadRot, doRod].
     * headTrans is the real-spring F8 correction (dt-vanishing); headRot/rod are fracMove (dt-independent).
     */
    public static void scaleMotorNoise(
            FloatArray brownTransScale, FloatArray brownRotScale, IntArray boundSeg, FloatArray scaleParams, IntArray counts) {
        int nM = boundSeg.getSize();
        float base = scaleParams.get(0), htf = scaleParams.get(1), hrf = scaleParams.get(2), rtf = scaleParams.get(3);
        int doRot = (int) scaleParams.get(4), doRod = (int) scaleParams.get(5);
        for (@Parallel int m = 0; m < nM; m++) {
            int head = 3 * m + 2, rod = 3 * m;
            boolean bnd = boundSeg.get(m) >= 0;
            brownTransScale.set(head, bnd ? base * htf : base);          // head translation (F8, real-spring)
            if (doRot == 1) brownRotScale.set(head, bnd ? base * hrf : base);   // head rotation (F9/F10, fracMove)
            if (doRod == 1) brownTransScale.set(rod, base * rtf);        // rod translation (tail-anchor + J2, fracMove; always anchored)
        }
    }

    /**
     * FULL correction — the bound-FILAMENT-SEGMENT translational F8 correction (the other end of the bond).
     * Per-SEGMENT (one thread per segment) using the CSR histogram `segMotorCount` (bound-motor count)
     * ⇒ RACE-FREE by construction: the shared-segment write problem is solved by iterating segments (not
     * motors), and the count comes from the established CSR-inverse machinery (segMotorCount, one-step-stale
     * from the prior step's gather histogram — fine for a slowly-changing bound set). scaleParams=[base,
     * segTransFac]; α_seg = k_F8·dt/γ_seg,∥ (the STEP-1 gate ∥ mode, real-spring/dt-vanishing).
     */
    public static void scaleBoundSegNoise(
            FloatArray brownTransScale, IntArray segMotorCount, FloatArray scaleParams, IntArray counts) {
        int nSeg = brownTransScale.getSize();
        float base = scaleParams.get(0), fac = scaleParams.get(1);
        for (@Parallel int s = 0; s < nSeg; s++)
            brownTransScale.set(s, segMotorCount.get(s) > 0 ? base * fac : base);
    }

    /**
     * COMPREHENSIVE correction (-thermcorr) — the LOAD-AWARE + CHAIN segment thermostat. Each segment's
     * translational F8-well α scales with its ACTUAL total constraint stiffness: α = N·aPerMotor (N =
     * segMotorCount, the CSR-inverse bound count ⇒ K_tot=N·k_F8, load-aware — heavily-bound segments were
     * UNDER-corrected by the single-bond -allnoise) + (doChain) nNbr·aPerNbr (the ratefixed chain-link
     * stiffness to its topological neighbors — applied to ALL segments incl UNBOUND, the neighbor
     * thermal-transmission hypothesis). scale = √((2−α)/2), α clamped < clampA (<2; the formula diverges at
     * the α→2 stability edge — a heavily-loaded segment is nearly rigid ⇒ near-zero thermal noise, physical).
     * aPerMotor/aPerNbr are BOTH dt-vanishing (real k_F8; ratefixed chain k_eff∝dt) ⇒ the whole correction
     * self-disables as dt→0 (a dt-fix, not a re-baseline). Per-SEGMENT own-slot write ⇒ race-free.
     * scaleParams=[base, aPerMotor, aPerNbr, doChain, clampA].
     */
    public static void scaleThermCorrSeg(
            FloatArray brownTransScale, IntArray segMotorCount, IntArray end1NbrSlot, IntArray end2NbrSlot,
            FloatArray scaleParams, IntArray counts) {
        int nSeg = brownTransScale.getSize();
        float base = scaleParams.get(0), aMot = scaleParams.get(1), aNbr = scaleParams.get(2);
        int doChain = (int) scaleParams.get(3);
        float clampA = scaleParams.get(4);
        for (@Parallel int s = 0; s < nSeg; s++) {
            float alpha = segMotorCount.get(s) * aMot;
            if (doChain == 1) {
                int nb = 0;
                if (end1NbrSlot.get(s) >= 0) nb++;
                if (end2NbrSlot.get(s) >= 0) nb++;
                alpha += nb * aNbr;
            }
            if (alpha > clampA) alpha = clampA;
            brownTransScale.set(s, (float) (base * Math.sqrt((2.0 - alpha) / 2.0)));
        }
    }

    /**
     * SYSTEM-WIDE correction (-syswide, CONSTRAINED_VARIANCE_PROBE.md §SYSTEM-WIDE). The MOTOR side, done
     * with the single-constraint discipline: each mode's α comes from that mode's OWN single spring, NEVER
     * summed across co-bound motors. Per-motor OWN-SLOT writes (rod 3m, lever 3m+1, head 3m+2) ⇒ race-free,
     * no atomics/KernelContext.
     *   HEAD translation: bound → the F8 cross-bridge well (real spring, α=k_F8·dt/γ_head, dt-vanishing ⇒
     *     FAITHFUL) via htf; unbound → the J1 fracMove joint (α=frac, dt-FLAT ⇒ RE-BASELINE) via rb (only
     *     when doRB — the free-standing-motor-chain arm).
     *   HEAD rotation: bound → F9/F10 alignment (ratefixed ⇒ dt-vanishing ⇒ FAITHFUL) via hrf; unbound →
     *     J1 torque fracMove (RE-BASELINE) via rb (doRB only).
     *   LEVER + ROD: the joint/anchor fracMove chain that holds the motor body together (J1/J2/tail-anchor,
     *     all fracMove ⇒ RE-BASELINE) via rb (doRB only, ALL motors incl unbound — the new coverage).
     * head trans/rot are ALWAYS written (no staleness across bind/unbind); lever/rod only under doRB (else
     * left at base, never corrupted). scaleParams=[base, htf, hrf, rb, doRB].
     */
    public static void scaleSysWideMotor(
            FloatArray brownTransScale, FloatArray brownRotScale, IntArray boundSeg, FloatArray p, IntArray counts) {
        int nM = boundSeg.getSize();
        float base = p.get(0), htf = p.get(1), hrf = p.get(2), rb = p.get(3);
        int doRB = (int) p.get(4);
        for (@Parallel int m = 0; m < nM; m++) {
            int rod = 3 * m, lever = 3 * m + 1, head = 3 * m + 2;
            boolean bnd = boundSeg.get(m) >= 0;
            brownTransScale.set(head, bnd ? base * htf : (doRB == 1 ? base * rb : base));   // F8 (faithful) | J1 fracMove (re-baseline)
            brownRotScale.set(head,  bnd ? base * hrf : (doRB == 1 ? base * rb : base));    // F9/F10 (faithful) | J1 torque (re-baseline)
            if (doRB == 1) {   // free-standing-motor-chain re-baseline: lever + rod joint/anchor fracMove modes
                brownTransScale.set(lever, base * rb);  brownRotScale.set(lever, base * rb);
                brownTransScale.set(rod,   base * rb);  brownRotScale.set(rod,   base * rb);
            }
        }
    }

    /**
     * SYSTEM-WIDE correction (-syswide) — the FILAMENT-SEGMENT side. Single-constraint α, race-free per-segment
     * own-slot write. Two FAITHFUL (dt-vanishing) translational springs on the segment COM:
     *   F8 cross-bridge: SINGLE-BOND k_F8 (α=aF8 iff bound) — NOT N·k_F8 (that is the deferred correlated-
     *     stiffness / -thermcorr over-cooling; here a bound segment uses exactly one bond's α, as -allnoise did).
     *   Chain links (F3/F4): applied to ALL segments (incl unbound), α = nNbr·aChain (nNbr∈{1,2}). A segment
     *     genuinely sits in up to 2 INDEPENDENT chain constraints (its two topological neighbors) ⇒ this is the
     *     noted "two independent constraints on the same mode" case, physically springs-in-parallel on one DOF —
     *     distinct from the forbidden co-bound-motor summing. Ratefixed (via -filrate) ⇒ dt-vanishing.
     * α clamped < clampA (the √((2−α)/2) formula diverges at the α→2 rigid edge). scaleParams=[base, aF8, aChain, clampA].
     */
    public static void scaleSysWideSeg(
            FloatArray brownTransScale, IntArray segMotorCount, IntArray end1NbrSlot, IntArray end2NbrSlot,
            FloatArray p, IntArray counts) {
        int nSeg = brownTransScale.getSize();
        float base = p.get(0), aF8 = p.get(1), aChain = p.get(2), clampA = p.get(3);
        for (@Parallel int s = 0; s < nSeg; s++) {
            float alpha = (segMotorCount.get(s) > 0) ? aF8 : 0f;   // SINGLE-BOND F8 (not N·k)
            int nb = 0;
            if (end1NbrSlot.get(s) >= 0) nb++;
            if (end2NbrSlot.get(s) >= 0) nb++;
            alpha += nb * aChain;
            if (alpha > clampA) alpha = clampA;
            brownTransScale.set(s, (float) (base * Math.sqrt((2.0 - alpha) / 2.0)));
        }
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
