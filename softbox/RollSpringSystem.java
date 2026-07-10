package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Azimuthal-binding Increment 1 — the INTER-SEGMENT TORSIONAL-ROLL SPRING (physical twist), in isolation.
 *
 * v1 has NO roll-coherence spring (AZIMUTHAL_BINDING_BUILD_READ.md Verdict A: the only inter-segment
 * orientation torque is the ⊥-axis bending straightener cross(u_i, u_j); segment roll is a free, uncoupled,
 * random-at-birth DOF). This is genuinely NEW v2 physics. It couples each segment's ROLL — the azimuth of
 * yVec about the shared long axis u — to its chain neighbours, driving the neighbour azimuth difference toward
 * a TWISTED rest angle so the relaxed filament is physically twisted (the segment frames trace the actin
 * helix). The twirl and (later) the azimuthal binding gate both read this coherent frame.
 *
 * FORM — the dt-ROBUST damping-limited (fraction-per-step) family, NOT a raw Hookean k. This is the DELIBERATE
 * choice: the roll DOF has tiny rotational drag (bRotGam_x = 4πη·R²·L ~ 1e-24 N·m·s/rad), so a raw Hookean
 * torque T=k·e has dt_crit ∝ γ_roll/k — explicitly UNSTABLE at production dt=1e-5 for any coherence-grade k
 * (α = k·dt/γ_roll ≫ 2). The codebase's own answer to a stiff DOF at large dt is the fraction-per-step
 * damping-limited spring (the chain torsion's filTorqSpringActive=0 branch, MyoFilLink.alignYVecTorque,
 * the PAIRS moveC) — α = the fraction, UNCONDITIONALLY stable for f<2. This system uses that family:
 *
 *     Tmag = f · e / ((1/bRGx_owner + 1/bRGx_other) · dt)          [N·m ; e = wrapped twist error, rad]
 *     Δroll per step = Tmag/bRGx · dt = f·e/2  (equal drags)       ⇒ fraction-per-step, stable for f<2.
 *
 * -rollhooke (mode 1) switches to the raw Hookean T = k·e specifically to EXHIBIT that instability (the
 * (A)-vs-(B) contrast the prototype reports). Default mode 0 = the stable fraction-per-step form.
 *
 * REST TWIST — actin 13/6 genetic helix. Per-monomer azimuthal advance magnitude ~166.5° (v1
 * helixAngInc = π/13.333 ⇒ advance = π − helixAngInc; AZIMUTHAL_BINDING_BUILD_READ.md Verdict A). Handedness
 * DERIVED FRESH (v1's rendering screw sign is non-authoritative): actin is a LEFT-HANDED genetic helix, so the
 * rest twist is NEGATIVE about the pointed→barbed axis (u). The per-joint rest = monomersBetweenFrames ·
 * twistPerMon, WRAPPED to (−π,π]; the caller passes it already-wrapped in rollParams[2]. NOTE: the segment
 * frame carries the COARSE (net-mod-2π) phase — the sub-segment helix (166.5°/mon over 32 monomers) aliases
 * at the frame scale; the true microscopic handedness lives in the intra-segment analytic interpolation
 * (deferred to the binding increment). This system holds the coarse frame twist coherent.
 *
 * RACE-FREE, chain-neighbour pattern (the ChainBendingForceSystem template; no atomics, no KernelContext).
 * Each joint is computed from BOTH segments' perspectives; the OWNER (lower slot) defines the canonical frame
 * (u_owner, y_owner) and the twist e = wrap(φ_other − rest), where φ_other = roll of the HIGHER-slot segment's
 * yVec about u_owner. Both perspectives read the SAME two segments ⇒ compute the SAME e, Tmag, u_owner ⇒ the
 * owner writes +Tmag·u_owner to itself, the other writes −Tmag·u_owner to itself: exactly equal-and-opposite
 * (roll angular momentum conserved), each segment writes ONLY its own torqueSum slot (+=), bit-identical CPU↔GPU.
 *
 * CONVENTION ASSUMPTION (prototype): a single straight slot-ordered chain — slot 0 = pointed (end1-free),
 * slot n−1 = barbed (end2-free), end2→higher slot. Then owner=lower-slot is always the more-pointed segment and
 * owner→other is always pointed→barbed, so ONE scalar rest (rollParams[2]) is correct for both sides. A general
 * topology would decode the reciprocal end-side for the rest sign — deferred (no motors/branching this increment).
 *
 * SPRINGS-CONTINUUM form (Inc 1b, mode 2 — the CANONICAL object). The fraction-per-step law (mode 0) is
 * dt-DEPENDENT (its effective stiffness k = f·γ_red/dt grows ∝1/dt ⇒ coherence freezes as dt→0 — the one
 * fraction-per-step law smuggled back after the canonical collapse made every constraint a fixed spring). The
 * springs form mirrors the codebase convention (GlidingHarness §PAIRS_SPRINGS / DT_AUDIT_AND_SPRINGS): a FIXED
 * stiffness k_roll = f·γ_roll_red/refDt via springify(f)=f·(dt/refDt) fed into the SAME kernel ⇒ dt CANCELS to
 * refDt. Concretely Tmag uses refDt in the denominator instead of dt:
 *     Tmag = f · e / ((1/bRGx_owner + 1/bRGx_other) · refDt)   ⇒  k_roll = f·γ_roll_red/refDt (dt-independent).
 * At dt==refDt (=1e-5, production) mode 2 ≡ mode 0 BYTE-IDENTICAL (all Inc-1 numbers stand); they diverge only
 * BELOW refDt, where the springs coherence std → the dt-independent equipartition rolldamp·√(kT/k_roll) while
 * the fraction std shrinks ∝√dt. This makes the roll coupling the SAME springs object as the canonical model.
 *
 * rollParams: [0]=dt [1]=f(fraction stiffness) [2]=restRad(wrapped, pointed→barbed) [3]=mode(0 frac/1 hooke/2 springs)
 *             [4]=kHooke(N·m/rad) [5]=rollDamp(thermostat, used by dampRoll) [6]=refDt(springs mode; s).
 */
public final class RollSpringSystem {
    private RollSpringSystem() {}

    // Device-safe acos, VERBATIM ChainBendingForceSystem.accurateAcos (PTX has no Math.acos in this form).
    private static double accurateAcos(double x) {
        if (x > 1.0)  x = 1.0;
        if (x < -1.0) x = -1.0;
        double y;
        if (x > 0.95) { double t = 1.0 - x; if (t < 0.0) t = 0.0; y = Math.sqrt(2.0 * t); }
        else if (x < -0.95) { double t = 1.0 + x; if (t < 0.0) t = 0.0; y = 3.141592653589793 - Math.sqrt(2.0 * t); }
        else {
            double ax = (x < 0.0) ? -x : x;
            double p = (-0.0187293 * ax + 0.0742610) * ax - 0.2121144;
            p = (p * ax + 1.5707963); p = p * Math.sqrt(1.0 - ax);
            y = (x < 0.0) ? (3.141592653589793 - p) : p;
        }
        double s = Math.sin(y);
        if (s > 1.0e-12 || s < -1.0e-12) { y = y + (Math.cos(y) - x) / s; }
        s = Math.sin(y);
        if (s > 1.0e-12 || s < -1.0e-12) { y = y + (Math.cos(y) - x) / s; }
        return y;
    }

    /** Roll of the HIGHER-slot segment's yVec (fy) about the OWNER axis (ou, oy) — signed angle in (−π,π]. */
    private static double rollAngle(double oux, double ouy, double ouz, double oyx, double oyy, double oyz,
                                    double fyx, double fyy, double fyz) {
        // owner z = u × y (normalized; owner frame is orthonormal after DerivedGeometry, but normalize for safety)
        double ozx = ouy * oyz - ouz * oyy, ozy = ouz * oyx - oux * oyz, ozz = oux * oyy - ouy * oyx;
        double zl = ozx * ozx + ozy * ozy + ozz * ozz;
        if (zl > 1.0e-30) { double iz = 1.0 / Math.sqrt(zl); ozx *= iz; ozy *= iz; ozz *= iz; }
        double cosp = fyx * oyx + fyy * oyy + fyz * oyz;   // fy · y_owner
        double sinp = fyx * ozx + fyy * ozy + fyz * ozz;   // fy · z_owner
        if (cosp > 1.0) cosp = 1.0; if (cosp < -1.0) cosp = -1.0;
        double base = accurateAcos(cosp);
        return (sinp < 0.0) ? -base : base;                // (−π, π]
    }

    /**
     * The torsional-roll spring: for each segment, add the roll-restoring torque of its ≤2 chain joints to its
     * own torqueSum (lab frame, about u_owner; the integrator projects onto u ⇒ the bwx roll channel). Reads
     * uVec/yVec (both segments) + bRotGam; no coord needed. Race-free self-write (owner ± equal-opposite).
     */
    public static void rollForces(
            FloatArray uVec, FloatArray yVec,
            IntArray end2NbrSlot, IntArray end1NbrSlot,
            FloatArray bRotGam, FloatArray torqueSum,
            FloatArray rollParams, IntArray counts) {

        int N = uVec.getSize() / 3;
        double dt   = rollParams.get(0);
        double f    = rollParams.get(1);
        double rest = rollParams.get(2);
        int    mode = (int) rollParams.get(3);
        double kH   = rollParams.get(4);
        double refDt = rollParams.get(6);   // springs mode (2): fixed stiffness k = f·γ_red/refDt
        double PI = 3.141592653589793, TWO_PI = 6.283185307179586;

        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i, iz = 2 * N + i;
            double tx = 0.0, ty = 0.0, tz = 0.0;

            // ---- two EXPLICIT joint blocks (NOT a loop with continue — the ChainBendingForceSystem PTX
            //      pattern; a free-end continue in an inner loop mis-lowers on the PTX backend) ----
            int e2Slot = end2NbrSlot.get(i);
            if (e2Slot >= 0) {
                int owner = (i < e2Slot) ? i : e2Slot;
                int other = (i < e2Slot) ? e2Slot : i;
                double oux = uVec.get(owner), ouy = uVec.get(N + owner), ouz = uVec.get(2 * N + owner);
                double oyx = yVec.get(owner), oyy = yVec.get(N + owner), oyz = yVec.get(2 * N + owner);
                double fyx = yVec.get(other), fyy = yVec.get(N + other), fyz = yVec.get(2 * N + other);
                double phi = rollAngle(oux, ouy, ouz, oyx, oyy, oyz, fyx, fyy, fyz);
                double e = phi - rest;
                if (e > PI) e -= TWO_PI; else if (e <= -PI) e += TWO_PI;
                double Tmag;
                if (mode == 1) { Tmag = kH * e; }
                else { double invSum = 1.0 / bRotGam.get(owner) + 1.0 / bRotGam.get(other);
                       double denom = (mode == 2) ? refDt : dt;   // springs (2): dt cancels to refDt ⇒ fixed stiffness
                       Tmag = f * e / (invSum * denom); }
                double sgn = (i == owner) ? 1.0 : -1.0;
                tx += sgn * Tmag * oux; ty += sgn * Tmag * ouy; tz += sgn * Tmag * ouz;
            }

            int e1Slot = end1NbrSlot.get(i);
            if (e1Slot >= 0) {
                int owner = (i < e1Slot) ? i : e1Slot;
                int other = (i < e1Slot) ? e1Slot : i;
                double oux = uVec.get(owner), ouy = uVec.get(N + owner), ouz = uVec.get(2 * N + owner);
                double oyx = yVec.get(owner), oyy = yVec.get(N + owner), oyz = yVec.get(2 * N + owner);
                double fyx = yVec.get(other), fyy = yVec.get(N + other), fyz = yVec.get(2 * N + other);
                double phi = rollAngle(oux, ouy, ouz, oyx, oyy, oyz, fyx, fyy, fyz);
                double e = phi - rest;
                if (e > PI) e -= TWO_PI; else if (e <= -PI) e += TWO_PI;
                double Tmag;
                if (mode == 1) { Tmag = kH * e; }
                else { double invSum = 1.0 / bRotGam.get(owner) + 1.0 / bRotGam.get(other);
                       double denom = (mode == 2) ? refDt : dt;   // springs (2): dt cancels to refDt ⇒ fixed stiffness
                       Tmag = f * e / (invSum * denom); }
                double sgn = (i == owner) ? 1.0 : -1.0;
                tx += sgn * Tmag * oux; ty += sgn * Tmag * ouy; tz += sgn * Tmag * ouz;
            }

            torqueSum.set(i,  (float) (torqueSum.get(i)  + tx));
            torqueSum.set(iy, (float) (torqueSum.get(iy) + ty));
            torqueSum.set(iz, (float) (torqueSum.get(iz) + tz));
        }
    }

    /**
     * Roll thermostat — attenuate the ‖u (roll) component of the Brownian torque kick (randTorque x-plane,
     * indices 0..N−1) by rollParams[5]. Runs AFTER BrownianForceSystem, BEFORE integrate. Takes energy out of
     * the low-drag roll mode (the mode most prone to instability) AND quiets roll so the coherent twist is
     * observable. rollDamp=1.0 ⇒ ×1.0f ⇒ BYTE-IDENTICAL (exact float identity); 0.0 ⇒ roll kick off. The y/z
     * (bending) rotational kicks are untouched.
     */
    public static void dampRoll(FloatArray randTorque, FloatArray rollParams, IntArray counts) {
        int N = randTorque.getSize() / 3;
        float damp = (float) rollParams.get(5);
        for (@Parallel int i = 0; i < N; i++) {
            randTorque.set(i, randTorque.get(i) * damp);
        }
    }
}
