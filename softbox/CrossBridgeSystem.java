package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 4b-ii/4b-iii: the myosin cross-bridge + the CROSS-ENTITY force+torque gather (motor→segment).
 *
 * Cross-bridge (faithful port of v1 MyoFilLink): for each bound motor, between its head tip and the
 * bound site on the segment —
 *   F8  cross-bridge spring   F = myoSpring·dist toward the bound site (addForces:187), at the head tip
 *       / bound site (each end gets the positional torque R×F, R in metres).
 *   F9  uVec alignment torque toward the motor–actin rest angle — STATE-DEPENDENT (4b-iii): uncocked
 *       (ADPPi) 90°, cocked 120° (alignUVecTorque:239-240). The power stroke emerges from this switch.
 *   F10 yVec alignment torque toward 0° (alignYVecTorque).
 * The head gets +F / −torsion; the segment gets −F / +torsion. forceDotFil = Dot(F, seg.uVec) (the
 * along-filament load; feeds the catch-slip + the ADP→NONE gate).
 *
 * `bondForces` computes the bond ONCE and stores head-side (6) + seg-side (6) + forceDotFil (1) in
 * bondData[m*13..]. `applyHeadForce` does the head self-write (one bond per head, race-free);
 * `registerForceDot` tracks the load; `segGather` sums the seg-side over the CSR-inverse (the
 * cross-entity gather — race-free, no atomics; see below).
 *
 * THE CROSS-ENTITY GATHER. Race-free WITHOUT atomics by a SEGMENT-SIDE gather over a
 * segment→bound-motors CSR-inverse (inc-3 histogram/scan/scatter keyed by boundSeg). The scatter
 * visits motors in index order ⇒ the gather sums in the same order as the brute reference ⇒
 * bit-identical. General infrastructure — crosslinkers / nodes / membrane reuse it.
 *
 * bondData stride 13: [0..2]=head force [3..5]=head torque [6..8]=seg force [9..11]=seg torque
 *   [12]=forceDotFil. xbParams: [0]=myoSpring [1]=(unused, F9 rest is state-dependent) [2]=j1FracMoveTorq
 *   [3]=dt [4]=HEAD_LEN [5]=forcebias. OPTIONAL (size>6, -xbsat diagnostic): [6]=satMode [7]=satFmax(N)
 *   [8]=satOnset(N) — the saturating-F8 measurement (default size-6 ⇒ plain Hookean, byte-identical).
 */
public final class CrossBridgeSystem {
    private CrossBridgeSystem() {}
    public static final int STRIDE = 13;

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

    /** Compute each bound motor's cross-bridge bond ONCE; store head-side + seg-side + forceDotFil. */
    public static void bondForces(
            FloatArray motorCoord, FloatArray motorUVec, FloatArray motorYVec, FloatArray motorBRotGam,
            FloatArray filCoord, FloatArray filUVec, FloatArray filYVec, FloatArray filBRotGam, FloatArray filSegLength,
            IntArray boundSeg, FloatArray bindArc, IntArray nucleotideState,
            FloatArray bondData, FloatArray xbParams) {

        int nB = motorCoord.getSize() / 3;
        int nSeg = filCoord.getSize() / 3;
        double myoSpring = xbParams.get(0), j1FMT = xbParams.get(2);
        double dt = xbParams.get(3), headLen = xbParams.get(4);
        double xbias = xbParams.get(5);   // -forcebias diagnostic: coherent −x seg-side force per bound motor (0 = production)
        double DEG2RAD = Math.PI / 180.0, RAD2DEG = 180.0 / Math.PI;
        int nM = nB / 3;

        // ---- MEASUREMENT-ONLY saturating F8 (SATURATED_CROSSBRIDGE_DIAGNOSTIC). Flag-gated by xbParams SIZE:
        //   size 6 (production + every other harness)  ⇒ satMode=0 ⇒ plain Hookean, BYTE-IDENTICAL.
        //   size 9 (V2OneX/Gliding -xbsat)             ⇒ [6]=mode [7]=Fmax(N) [8]=onset(N).
        // Caps the |F8| spring magnitude above onset to bound the k·dt overshoot's spurious load excursions
        // WITHOUT softening the in-range spring. The force DIRECTION is unchanged ⇒ F, both torques, and
        // forceDotFil all rescale consistently. modes: 1 sym-tanh, 2 sym-hardclip, 3 asym(compression-only)-tanh,
        // 4 asym-hardclip (compression = forceDotFil<0, the side the catch exponential e^(−F·xCatch) detonates on).
        int satMode = 0; double satFmax = 0.0, satOnset = 0.0;
        if (xbParams.getSize() > 6) { satMode = (int) xbParams.get(6); satFmax = xbParams.get(7); satOnset = xbParams.get(8); }
        // MEASUREMENT-ONLY (STROKE_VS_ARMLENGTH isolation cross-check). Flag-gated by xbParams SIZE:
        //   size ≤9 (production + -xbsat) ⇒ f9Frozen=0 ⇒ the F9 rest still switches, BYTE-IDENTICAL.
        //   size 10 (MotorStrokeHarness -isolate 1) ⇒ [9]=1 freezes the F9 rest at 90° (uncocked) so only J1 strokes.
        int f9Frozen = (xbParams.getSize() > 9) ? (int) xbParams.get(9) : 0;
        // AXIAL SWING LOCK (PHASE-2, -axlock; xbParams[10]). Flag-gated by SIZE:
        //   size ≤10 (production + -spherehead) ⇒ axLock=0 ⇒ F10 targets seg.yVec (BYTE-IDENTICAL).
        //   size 11 (Gliding -axlock) ⇒ [10]=1 ⇒ F10 retargets the head yVec to ŝ = normalize(n̂bed×seg.uVec),
        //   n̂bed = lab +Z, HEAD-ONLY (BoA MyoFilLink.alignYVecTorqueAxial). Fixes the neck swing plane to axial.
        int axLock = (xbParams.getSize() > 10) ? (int) xbParams.get(10) : 0;
        // STEREOSPECIFIC ROLL SIGN (PHASE-2, -rollsign; xbParams[11]). size ≤11 ⇒ rollSign=0 ⇒ align head.yVec
        // to the NEARER of ±ŝ (the sign-agnostic axial lock, byte-identical). size 12 with [11]=1 ⇒ align to
        // +ŝ SPECIFICALLY (ŝ = n̂bed×û_seg is the barbed-sweep sign, from polarity) ⇒ the roll SIGN is fixed.
        int rollSign = (xbParams.getSize() > 11) ? (int) xbParams.get(11) : 0;

        for (@Parallel int m = 0; m < nM; m++) {
            int d = m * STRIDE;
            for (int k = 0; k < STRIDE; k++) bondData.set(d + k, 0f);
            int s = boundSeg.get(m);
            if (s < 0) continue;

            int h = 3 * m + 2;
            double hcx = motorCoord.get(h), hcy = motorCoord.get(nB + h), hcz = motorCoord.get(2 * nB + h);
            double hux = motorUVec.get(h), huy = motorUVec.get(nB + h), huz = motorUVec.get(2 * nB + h);
            double hyx = motorYVec.get(h), hyy = motorYVec.get(nB + h), hyz = motorYVec.get(2 * nB + h);
            double hbRGx = motorBRotGam.get(h), hbRGy = motorBRotGam.get(nB + h);
            double htipx = hcx + 0.5 * headLen * hux, htipy = hcy + 0.5 * headLen * huy, htipz = hcz + 0.5 * headLen * huz;

            double scx = filCoord.get(s), scy = filCoord.get(nSeg + s), scz = filCoord.get(2 * nSeg + s);
            double sux = filUVec.get(s), suy = filUVec.get(nSeg + s), suz = filUVec.get(2 * nSeg + s);
            double syx = filYVec.get(s), syy = filYVec.get(nSeg + s), syz = filYVec.get(2 * nSeg + s);
            double sbRGx = filBRotGam.get(s), sbRGy = filBRotGam.get(nSeg + s);
            double slen = filSegLength.get(s);
            double aOff = bindArc.get(m) - 0.5 * slen;
            double apx = scx + aOff * sux, apy = scy + aOff * suy, apz = scz + aOff * suz;

            // F8 spring (toward the bound site)
            double dx = apx - htipx, dy = apy - htipy, dz = apz - htipz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double fmag = myoSpring * dist;
            // saturating-F8 diagnostic (satMode==0 in production ⇒ skipped, byte-identical)
            if (satMode != 0) {
                boolean compressive = (dx * sux + dy * suy + dz * suz) < 0.0;  // forceDotFil<0
                if (satMode < 3 || compressive) {                             // 1,2 symmetric; 3,4 compression-only
                    if (satMode == 2 || satMode == 4) {                       // hard clip: min(fmag, Fmax)
                        if (fmag > satFmax) fmag = satFmax;
                    } else if (fmag > satOnset && satFmax > satOnset) {       // smooth tanh: Hookean below onset, →Fmax asymptote
                        double span = satFmax - satOnset;
                        double z = (fmag - satOnset) / span;                  // >0
                        double e = Math.exp(-2.0 * z);                        // stable for z>0; Math.exp lowers on PTX
                        fmag = satOnset + span * (1.0 - e) / (1.0 + e);
                    }
                }
            }
            double Fx = 0, Fy = 0, Fz = 0;
            if (dist > 0.0) { double inv = fmag / dist; Fx = inv * dx; Fy = inv * dy; Fz = inv * dz; }
            double RHx = (htipx - hcx) * 1e-6, RHy = (htipy - hcy) * 1e-6, RHz = (htipz - hcz) * 1e-6;
            double THx = RHy * Fz - RHz * Fy, THy = RHz * Fx - RHx * Fz, THz = RHx * Fy - RHy * Fx;
            double RSx = (apx - scx) * 1e-6, RSy = (apy - scy) * 1e-6, RSz = (apz - scz) * 1e-6;
            double nFx = -Fx, nFy = -Fy, nFz = -Fz;
            double TSx = RSy * nFz - RSz * nFy, TSy = RSz * nFx - RSx * nFz, TSz = RSx * nFy - RSy * nFx;

            // F9 uVec alignment torque — STATE-DEPENDENT rest angle (the stroke switch)
            double restF9 = (f9Frozen != 0) ? 90.0 : ((nucleotideState.get(m) != MotorStore.NUC_ADPPI) ? 120.0 : 90.0);
            double t9x = suy * huz - suz * huy, t9y = suz * hux - sux * huz, t9z = sux * huy - suy * hux;
            double m9 = t9x * t9x + t9y * t9y + t9z * t9z;
            double T9x = 0, T9y = 0, T9z = 0;
            if (m9 > 1.0e-30) {
                double im = 1.0 / Math.sqrt(m9); t9x *= im; t9y *= im; t9z *= im;
                double dot = sux * hux + suy * huy + suz * huz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
                double angD = accurateAcos(dot) * RAD2DEG - restF9;
                double tm = j1FMT * DEG2RAD * angD / ((1.0 / hbRGy + 1.0 / sbRGy) * dt);
                T9x = tm * t9x; T9y = tm * t9y; T9z = tm * t9z;
            }
            // F10 yVec alignment torque (rest 0): default aligns head.yVec → seg.yVec, two-body.
            double t10x = syy * hyz - syz * hyy, t10y = syz * hyx - syx * hyz, t10z = syx * hyy - syy * hyx;
            double m10 = t10x * t10x + t10y * t10y + t10z * t10z;
            double T10x = 0, T10y = 0, T10z = 0;
            if (m10 > 1.0e-30) {
                double im = 1.0 / Math.sqrt(m10); t10x *= im; t10y *= im; t10z *= im;
                double dot = syx * hyx + syy * hyy + syz * hyz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
                double ang = accurateAcos(dot) * RAD2DEG;
                double tm = j1FMT * DEG2RAD * ang / ((1.0 / hbRGx + 1.0 / sbRGx) * dt);
                T10x = tm * t10x; T10y = tm * t10y; T10z = tm * t10z;
            }
            // head-side / seg-side F10 contribution. Default: head −T10, seg +T10 (byte-identical when axLock=0).
            double hF10x = -T10x, hF10y = -T10y, hF10z = -T10z;
            double sF10x =  T10x, sF10y =  T10y, sF10z =  T10z;
            if (axLock != 0) {
                // AXIAL LOCK: align head.yVec → ŝ = normalize(n̂bed×seg.uVec), n̂bed=(0,0,1) ⇒ ŝ=(−suy,sux,0). Head-only.
                double sx = -suy, sy = sux, sz = 0.0;
                double sm = sx * sx + sy * sy + sz * sz;
                double lx = 0, ly = 0, lz = 0;
                if (sm > 1.0e-18) {
                    double ims = 1.0 / Math.sqrt(sm); sx *= ims; sy *= ims; sz *= ims;
                    double tvx = hyy * sz - hyz * sy, tvy = hyz * sx - hyx * sz, tvz = hyx * sy - hyy * sx;   // head.yVec × ŝ
                    double dotv = hyx * sx + hyy * sy + hyz * sz;
                    if (rollSign == 0 && dotv < 0) { tvx = -tvx; tvy = -tvy; tvz = -tvz; dotv = -dotv; }      // nearer of ±ŝ (default)
                    // rollSign!=0: align to +ŝ SPECIFICALLY (no flip) ⇒ heads at −ŝ get rotated all the way to +ŝ
                    if (dotv > 1) dotv = 1; if (dotv < -1) dotv = -1;
                    double tvm = tvx * tvx + tvy * tvy + tvz * tvz;
                    if (tvm > 1.0e-30) {
                        double imt = 1.0 / Math.sqrt(tvm); tvx *= imt; tvy *= imt; tvz *= imt;
                        double angL = accurateAcos(dotv) * RAD2DEG;
                        double tmL = j1FMT * DEG2RAD * angL / ((1.0 / hbRGx + 1.0 / sbRGx) * dt);
                        lx = tmL * tvx; ly = tmL * tvy; lz = tmL * tvz;                                        // rotate head.yVec toward ŝ
                    }
                }
                hF10x = lx; hF10y = ly; hF10z = lz;      // head only
                sF10x = 0;  sF10y = 0;  sF10z = 0;       // no segment reaction (ŝ is a bed reference)
            }

            // head-side: +F, torque TH - T9 + hF10 (default hF10 = −T10 ⇒ byte-identical)
            bondData.set(d,     (float) Fx);  bondData.set(d + 1, (float) Fy);  bondData.set(d + 2, (float) Fz);
            bondData.set(d + 3, (float) (THx - T9x + hF10x));
            bondData.set(d + 4, (float) (THy - T9y + hF10y));
            bondData.set(d + 5, (float) (THz - T9z + hF10z));
            // seg-side: -F, torque TS + T9 + sF10  (-forcebias subtracts a uniform −x bias on the seg side; 0 in production)
            bondData.set(d + 6, (float) (nFx - xbias)); bondData.set(d + 7, (float) nFy); bondData.set(d + 8, (float) nFz);
            bondData.set(d + 9,  (float) (TSx + T9x + sF10x));
            bondData.set(d + 10, (float) (TSy + T9y + sF10y));
            bondData.set(d + 11, (float) (TSz + T9z + sF10z));
            // forceDotFil = Dot(F, seg.uVec) — the along-filament load (motor-side force)
            bondData.set(d + 12, (float) (Fx * sux + Fy * suy + Fz * suz));
        }
    }

    /**
     * DIRECTED power-stroke converter (sphere-head; PHASE-2, 2026-07-01). Replaces the J1 cross(lever,head)
     * converter — whose torsion axis is DEGENERATE at the collinear/straight recovery pose, so each new power
     * stroke picks its swing azimuth from numerical residue and FLIPS direction between cycles (the ill-defined
     * direction jba observed). Here the swing is driven toward a POLARITY-DEFINED target so the direction is
     * deterministic and always barbed-ward:
     *   target lever uVec  û_L* = normalize( cos θ_rest · û_head − sin θ_rest · f̂ ),   f̂ = bound-seg uVec
     * (pointed→barbed). Tipping the lever toward −f̂ sweeps its REAR (rod/tail-side end) toward the BARBED end.
     * Standard compliant alignment-torque form (k = j1FracMoveTorq); +T on the lever, −T on the head (internal
     * converter couple). Per-motor PURE — motor m writes only its own lever (3m+1) & head (3m+2) torque slots
     * (disjoint) ⇒ race-free, no atomics, CPU≡GPU. Use with the J1 angular converter OFF (jointParams[3]=0)
     * so this is the sole stroke driver; the J1 POSITION spring (lever.end2↔head.end1) stays on.
     * swingParams: [0]=k [1]=dt [2]=θ_uncocked(deg) [3]=θ_cocked(deg). ADDITIVE/flag-gated ⇒ default untouched.
     */
    public static void directedSwing(FloatArray motorUVec, FloatArray motorTorqueSum, FloatArray motorBRotGam,
                                     FloatArray filUVec, IntArray boundSeg, IntArray nucleotideState,
                                     FloatArray swingParams, IntArray counts) {
        int nB = motorUVec.getSize() / 3;
        int nSeg = filUVec.getSize() / 3;
        int nM = nB / 3;
        double k = swingParams.get(0), dt = swingParams.get(1);
        double thetaU = swingParams.get(2), thetaC = swingParams.get(3);
        // -strokerate (STROKE_DT_RATE_DIAGNOSIS): convert the per-STEP swing fraction k into a per-TIME rate so the
        // stroke's sim-time DURATION is dt-independent. Default (size 4) ⇒ k unchanged, byte-identical. When
        // swingParams[4]=refDt>0: k_eff = 1 − (1−k)^(dt/refDt) = 1 − exp((dt/refDt)·log(1−k)); at dt=refDt k_eff==k
        // (preserves the coarse/production-dt physical stroke duration); at finer dt the per-step fraction shrinks so
        // the stroke takes more STEPS but the same SIM-TIME. Math.exp/Math.log lower on the PTX backend.
        if (swingParams.getSize() > 4) {
            double refDt = swingParams.get(4);
            if (refDt > 0.0) k = 1.0 - Math.exp((dt / refDt) * Math.log(1.0 - k));
        }
        double DEG2RAD = Math.PI / 180.0;
        for (@Parallel int m = 0; m < nM; m++) {
            int s = boundSeg.get(m);
            if (s < 0) continue;
            int lev = 3 * m + 1, head = 3 * m + 2;
            double lux = motorUVec.get(lev), luy = motorUVec.get(nB + lev), luz = motorUVec.get(2 * nB + lev);
            double hux = motorUVec.get(head), huy = motorUVec.get(nB + head), huz = motorUVec.get(2 * nB + head);
            double fx = filUVec.get(s), fy = filUVec.get(nSeg + s), fz = filUVec.get(2 * nSeg + s);
            double rest = (nucleotideState.get(m) != MotorStore.NUC_ADPPI) ? thetaC : thetaU;
            double th = rest * DEG2RAD, c = Math.cos(th), sn = Math.sin(th);
            double tx = c * hux - sn * fx, ty = c * huy - sn * fy, tz = c * huz - sn * fz;   // target lever dir (tip toward −f̂)
            double tm2 = tx * tx + ty * ty + tz * tz;
            if (tm2 < 1.0e-30) continue;
            double it = 1.0 / Math.sqrt(tm2); tx *= it; ty *= it; tz *= it;
            // axis = lever.uVec × target ⇒ rotates lever.uVec TOWARD target (b̂×â form)
            double ax = luy * tz - luz * ty, ay = luz * tx - lux * tz, az = lux * ty - luy * tx;
            double am2 = ax * ax + ay * ay + az * az;
            if (am2 < 1.0e-30) continue;                    // already aligned to the target
            double ia = 1.0 / Math.sqrt(am2); ax *= ia; ay *= ia; az *= ia;
            double dot = lux * tx + luy * ty + luz * tz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
            double ang = accurateAcos(dot);                 // radians
            double mag = k * ang / ((1.0 / motorBRotGam.get(nB + lev) + 1.0 / motorBRotGam.get(nB + head)) * dt);
            motorTorqueSum.set(lev,          (float) (motorTorqueSum.get(lev)          + mag * ax));
            motorTorqueSum.set(nB + lev,     (float) (motorTorqueSum.get(nB + lev)     + mag * ay));
            motorTorqueSum.set(2 * nB + lev, (float) (motorTorqueSum.get(2 * nB + lev) + mag * az));
            motorTorqueSum.set(head,          (float) (motorTorqueSum.get(head)          - mag * ax));
            motorTorqueSum.set(nB + head,     (float) (motorTorqueSum.get(nB + head)     - mag * ay));
            motorTorqueSum.set(2 * nB + head, (float) (motorTorqueSum.get(2 * nB + head) - mag * az));
        }
    }

    /**
     * TWIST-COST census (PHASE-2, 2026-07-01, -twistcensus). At each FRESH bind (boundSeg free→bound this
     * step), record the arrival angle between the head's yVec (as it arrives) and the +ŝ roll target
     * (ŝ = n̂bed×seg.uVec) — the assembly twist the stereospecific roll-sign lock must impose. Binned into
     * 6×30° buckets [0,180]° per motor (each motor writes only its own twistHist[6m+bin] + prevBound[m] ⇒
     * race-free). Runs AFTER bind, BEFORE the roll lock (bondForces) acts, so head.yVec is the arrival value.
     * prevBound init −1, twistHist init 0. ADDITIVE/flag-gated ⇒ default untouched. */
    public static void captureBindTwist(FloatArray motorYVec, FloatArray filUVec, IntArray boundSeg,
                                        IntArray prevBound, IntArray twistHist, IntArray counts) {
        int nB = motorYVec.getSize() / 3;
        int nSeg = filUVec.getSize() / 3;
        int nM = nB / 3;
        double RAD2DEG = 180.0 / Math.PI;
        for (@Parallel int m = 0; m < nM; m++) {
            int s = boundSeg.get(m);
            int ps = prevBound.get(m);
            if (s >= 0 && ps < 0) {                          // fresh bind this step
                int h = 3 * m + 2;
                double sux = filUVec.get(s), suy = filUVec.get(nSeg + s);
                double sx = -suy, sy = sux;                  // +ŝ = n̂bed×û_seg (z=0)
                double sm = Math.sqrt(sx * sx + sy * sy);
                if (sm > 1.0e-9) {
                    sx /= sm; sy /= sm;
                    double dot = motorYVec.get(h) * sx + motorYVec.get(nB + h) * sy;   // head.yVec·(+ŝ)
                    if (dot > 1) dot = 1; if (dot < -1) dot = -1;
                    double ang = accurateAcos(dot) * RAD2DEG;                            // 0..180°
                    int bin = (int) (ang / 30.0); if (bin > 5) bin = 5; if (bin < 0) bin = 0;
                    twistHist.set(6 * m + bin, twistHist.get(6 * m + bin) + 1);
                }
            }
            prevBound.set(m, s);
        }
    }

    /**
     * BIND-TIME stereospecific HEAD-AXIS init (PHASE-2, 2026-07-01, -mhatset). At each FRESH bind
     * (boundSeg free→bound this step), orient the bound head to the fully stereospecific pose that fixes
     * the SECOND free sign — the head-axis mhat = head.uVec sign along ±n̂bed. The ⊥ hold (F9) + roll lock
     * (-rollsign) pin mhat to ±n̂bed but leave the sign free (≈50/50, PHASE2_MHAT_BIND_FINDINGS STEP 0);
     * the PRODUCTIVE pole is +n̂bed (+Z) — then p = ŷ_head×û_head = f̂ and the head-frame swing sweeps
     * barbed-ward, matching -dirswing.
     *
     * Sets the full orthonormal head frame consistent with the +ŝ roll: û_head = +n̂bed = (0,0,1);
     * ŷ_head = +ŝ = n̂bed×û_seg = (−suy, sux, 0); ẑ_head recomputed by DerivedGeometrySystem (û×ŷ). This is
     * an INITIALIZATION at the bind event ONLY (prevBound gate) — NOT a per-step torque, NOT a persistent
     * pin. The existing ⊥ hold + roll lock maintain the pose thereafter. Per-motor PURE (motor m writes only
     * its own head slot 3m+2 + prevBound[m]) ⇒ race-free, no atomics, CPU≡GPU.
     *
     * PLACEMENT: run LATE (after DerivedGeometrySystem.derive), mirroring snapCanonicalHead — an early
     * body-pose write breaks the PTX-lowered bind (GPU avgBound→0). Takes effect on the next step's bond
     * ⇒ CPU/GPU timing identical. prevBound init −1. ADDITIVE/flag-gated ⇒ default untouched.
     */
    public static void setBindMhat(FloatArray motorCoord, FloatArray motorUVec, FloatArray motorYVec, FloatArray filUVec,
                                   IntArray boundSeg, IntArray prevBound, IntArray counts) {
        int nB = motorUVec.getSize() / 3;
        int nSeg = filUVec.getSize() / 3;
        int nM = nB / 3;
        double halfHead = 0.5 * MotorStore.HEAD_LEN;
        for (@Parallel int m = 0; m < nM; m++) {
            int s = boundSeg.get(m);
            int ps = prevBound.get(m);
            if (s >= 0 && ps < 0) {                          // fresh bind this step
                int h = 3 * m + 2;
                double sux = filUVec.get(s), suy = filUVec.get(nSeg + s);
                double sx = -suy, sy = sux;                  // +ŝ = n̂bed×û_seg (z=0)
                double sm = Math.sqrt(sx * sx + sy * sy);
                // MINIMAL intervention: reorient ONLY wrong-pole heads (uz<0). Heads already on the productive
                // +ẑ pole (~52%) are left EXACTLY as-arrived (zero disturbance) — the wrong-pole heads (~48%)
                // are the only ones flipped. This is the fairest bind-time init: it touches the smallest set and
                // does not perturb an already-productive attachment.
                if (motorUVec.get(2 * nB + h) >= 0.0) { prevBound.set(m, s); continue; }
                if (sm > 1.0e-9) {
                    sx /= sm; sy /= sm;
                    // IMPULSE-FREE reorientation: rotate the head about its BOUND TIP (= the F8 attach point),
                    // so the F8 spring vector (site − tip) is preserved ⇒ no catch-slip release impulse. Preserve
                    // htip = hc + ½·HEAD_LEN·û_old, then set û_head = +ẑ and hc = htip − ½·HEAD_LEN·û_new.
                    double hcx = motorCoord.get(h), hcy = motorCoord.get(nB + h), hcz = motorCoord.get(2 * nB + h);
                    double uox = motorUVec.get(h), uoy = motorUVec.get(nB + h), uoz = motorUVec.get(2 * nB + h);
                    double htx = hcx + halfHead * uox, hty = hcy + halfHead * uoy, htz = hcz + halfHead * uoz;
                    // û_head = +n̂bed = +ẑ (the productive pole)
                    motorUVec.set(h, 0f); motorUVec.set(nB + h, 0f); motorUVec.set(2 * nB + h, 1f);
                    // ŷ_head = +ŝ (in-plane, ⊥ û_head ⇒ orthonormal; derive recomputes ẑ_head = û×ŷ)
                    motorYVec.set(h, (float) sx); motorYVec.set(nB + h, (float) sy); motorYVec.set(2 * nB + h, 0f);
                    // hc so the tip is preserved (û_new=+ẑ ⇒ hc = htip − ½·HEAD_LEN·ẑ)
                    motorCoord.set(h, (float) htx); motorCoord.set(nB + h, (float) hty); motorCoord.set(2 * nB + h, (float) (htz - halfHead));
                }
            }
            prevBound.set(m, s);
        }
    }

    /**
     * HEAD-FRAME power-stroke converter (sphere-head; PHASE-2, 2026-07-01) — the biologically-defensible
     * recast of `directedSwing`. Same mechanics, but the swing target is derived PURELY from the head's own
     * (orientation-locked) frame — NO filament axis appears in the swing law (note: no filUVec argument).
     *
     * Biology: binding orients the head (the ⊥ hold pins û_head, the axial/roll lock pins ŷ_head→ŝ); the
     * converter then rotates the neck relative to the BOUND HEAD, about the head's hinge axis ŷ_head, by the
     * state-dependent rest angle θ_rest (0°→60° on ADP-Pi→ADP). Actin polarity enters ONLY through the head's
     * bound pose — exactly as in real myosin. Rodrigues (û_head ⊥ ŷ_head):
     *   target û_L* = normalize( cos θ_rest · û_head − sin θ_rest · (ŷ_head × û_head) ).
     * When the head is fully locked (ŷ_head = +ŝ, û_head ⊥ f̂), ŷ_head × û_head = f̂, so this is IDENTICAL to
     * directedSwing's `cos θ·û_head − sin θ·f̂`. The recast is well-defined at EVERY pose (the axial lock
     * removed the straight-pose degeneracy of the old cross(lever,head) converter). Same compliant torque form,
     * +T lever / −T head, per-motor pure ⇒ race-free, CPU≡GPU. Use with J1 angular converter OFF.
     * swingParams: [0]=k [1]=dt [2]=θ_uncocked(deg) [3]=θ_cocked(deg). ADDITIVE/flag-gated ⇒ default untouched.
     */
    public static void directedSwingHeadFrame(FloatArray motorUVec, FloatArray motorYVec, FloatArray motorTorqueSum,
                                              FloatArray motorBRotGam, IntArray boundSeg, IntArray nucleotideState,
                                              FloatArray swingParams, IntArray counts) {
        int nB = motorUVec.getSize() / 3;
        int nM = nB / 3;
        double k = swingParams.get(0), dt = swingParams.get(1);
        double thetaU = swingParams.get(2), thetaC = swingParams.get(3);
        // -strokerate (STROKE_DT_RATE_DIAGNOSIS): convert the per-STEP swing fraction k into a per-TIME rate so the
        // stroke's sim-time DURATION is dt-independent. Default (size 4) ⇒ k unchanged, byte-identical. When
        // swingParams[4]=refDt>0: k_eff = 1 − (1−k)^(dt/refDt) = 1 − exp((dt/refDt)·log(1−k)); at dt=refDt k_eff==k
        // (preserves the coarse/production-dt physical stroke duration); at finer dt the per-step fraction shrinks so
        // the stroke takes more STEPS but the same SIM-TIME. Math.exp/Math.log lower on the PTX backend.
        if (swingParams.getSize() > 4) {
            double refDt = swingParams.get(4);
            if (refDt > 0.0) k = 1.0 - Math.exp((dt / refDt) * Math.log(1.0 - k));
        }
        double DEG2RAD = Math.PI / 180.0;
        for (@Parallel int m = 0; m < nM; m++) {
            int s = boundSeg.get(m);
            if (s < 0) continue;
            int lev = 3 * m + 1, head = 3 * m + 2;
            double lux = motorUVec.get(lev), luy = motorUVec.get(nB + lev), luz = motorUVec.get(2 * nB + lev);
            double hux = motorUVec.get(head), huy = motorUVec.get(nB + head), huz = motorUVec.get(2 * nB + head);
            double hyx = motorYVec.get(head), hyy = motorYVec.get(nB + head), hyz = motorYVec.get(2 * nB + head);
            double rest = (nucleotideState.get(m) != MotorStore.NUC_ADPPI) ? thetaC : thetaU;
            double th = rest * DEG2RAD, c = Math.cos(th), sn = Math.sin(th);
            // power-stroke direction in the head frame = ŷ_head × û_head (= f̂ when the head is fully locked)
            double px = hyy * huz - hyz * huy, py = hyz * hux - hyx * huz, pz = hyx * huy - hyy * hux;
            double tx = c * hux - sn * px, ty = c * huy - sn * py, tz = c * huz - sn * pz;   // target lever dir (head frame)
            double tm2 = tx * tx + ty * ty + tz * tz;
            if (tm2 < 1.0e-30) continue;
            double it = 1.0 / Math.sqrt(tm2); tx *= it; ty *= it; tz *= it;
            double ax = luy * tz - luz * ty, ay = luz * tx - lux * tz, az = lux * ty - luy * tx;   // lever × target
            double am2 = ax * ax + ay * ay + az * az;
            if (am2 < 1.0e-30) continue;
            double ia = 1.0 / Math.sqrt(am2); ax *= ia; ay *= ia; az *= ia;
            double dot = lux * tx + luy * ty + luz * tz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
            double ang = accurateAcos(dot);
            double mag = k * ang / ((1.0 / motorBRotGam.get(nB + lev) + 1.0 / motorBRotGam.get(nB + head)) * dt);
            motorTorqueSum.set(lev,          (float) (motorTorqueSum.get(lev)          + mag * ax));
            motorTorqueSum.set(nB + lev,     (float) (motorTorqueSum.get(nB + lev)     + mag * ay));
            motorTorqueSum.set(2 * nB + lev, (float) (motorTorqueSum.get(2 * nB + lev) + mag * az));
            motorTorqueSum.set(head,          (float) (motorTorqueSum.get(head)          - mag * ax));
            motorTorqueSum.set(nB + head,     (float) (motorTorqueSum.get(nB + head)     - mag * ay));
            motorTorqueSum.set(2 * nB + head, (float) (motorTorqueSum.get(2 * nB + head) - mag * az));
        }
    }

    /**
     * CANONICAL_MOTOR (flag-gated; deliberately DIVERGES from v1 — see CANONICAL_MOTOR_FINDINGS.md).
     * The lever-arm cross-bridge: the head is rigidly anchored to actin at TWO points (the existing F8
     * tip site AND a second spring at the head's J1-pivot end), so the head orientation is pinned by
     * GEOMETRY rather than by the F9 alignment torque. With the head pinned, the J1 converter swing
     * (MotorJointSystem's 0°↔60° rest switch, UNCHANGED) drives the LEVER + tail against the anchored
     * head ⇒ the working stroke is delivered at the tail/load end and scales with the lever length
     * (the canonical lever-arm law), not the head-tip reorientation the default bondForces produces.
     *
     * The three canonical changes vs the default bondForces:
     *   (1) TWO-POINT F8.  F8a: tip (head.end2) → site A (bindArc). F8b: rear (head.end1, the J1 pivot)
     *       → site B (bindArc2). Both are toward FIXED material points on the bound segment, so the two
     *       springs form a couple that pins the head's position AND axis. Both sites are on the SAME bound
     *       segment (the rear-on-a-neighbour case is a gliding-assay concern, out of scope for the
     *       characterization; flagged).
     *   (2) NO F9.  The head-vs-actin alignment torque (the default stroke driver) is REMOVED — the head
     *       no longer reorients against actin. (F10, the roll/yVec alignment toward a CONSTANT 0° rest, is
     *       KEPT: two point-springs do not constrain roll about the head axis, and F10 is not a stroke
     *       driver — its rest never switches with nucleotide state.)
     *   (3) LEVER-STRAIN load.  forceDotFil = Dot(F8a + F8b, seg.uVec) — the NET along-filament load the
     *       two-point cross-bridge transmits = the resistance the converter swing develops, reacted through
     *       the pinned head into actin (the "lever-tail tension projected appropriately"), NOT the single
     *       tip-bond stretch the default reads. Non-degenerate under load; ≈0 unloaded (head freely pinned).
     *
     * Same bondData stride-13 layout / head-self-write / seg-side gather as bondForces (the gather is reused
     * VERBATIM). xbParams: [0]=myoSpring [2]=j1FMT(F10 torque coeff) [3]=dt [4]=HEAD_LEN. Default path
     * untouched ⇒ byte-identical for every non-canonical caller.
     */
    public static void bondForcesCanonical(
            FloatArray motorCoord, FloatArray motorUVec, FloatArray motorYVec, FloatArray motorBRotGam,
            FloatArray filCoord, FloatArray filUVec, FloatArray filYVec, FloatArray filBRotGam, FloatArray filSegLength,
            IntArray boundSeg, FloatArray bindArc, FloatArray bindArc2, IntArray nucleotideState,
            FloatArray bondData, FloatArray xbParams) {

        int nB = motorCoord.getSize() / 3;
        int nSeg = filCoord.getSize() / 3;
        double myoSpring = xbParams.get(0), j1FMT = xbParams.get(2);
        double dt = xbParams.get(3), headLen = xbParams.get(4);
        double DEG2RAD = Math.PI / 180.0, RAD2DEG = 180.0 / Math.PI;
        int nM = nB / 3;

        for (@Parallel int m = 0; m < nM; m++) {
            int d = m * STRIDE;
            for (int k = 0; k < STRIDE; k++) bondData.set(d + k, 0f);
            int s = boundSeg.get(m);
            if (s < 0) continue;

            int h = 3 * m + 2;
            double hcx = motorCoord.get(h), hcy = motorCoord.get(nB + h), hcz = motorCoord.get(2 * nB + h);
            double hux = motorUVec.get(h), huy = motorUVec.get(nB + h), huz = motorUVec.get(2 * nB + h);
            double hyx = motorYVec.get(h), hyy = motorYVec.get(nB + h), hyz = motorYVec.get(2 * nB + h);
            double hbRGx = motorBRotGam.get(h);
            double tipx = hcx + 0.5 * headLen * hux, tipy = hcy + 0.5 * headLen * huy, tipz = hcz + 0.5 * headLen * huz;   // head.end2
            double rearx = hcx - 0.5 * headLen * hux, reary = hcy - 0.5 * headLen * huy, rearz = hcz - 0.5 * headLen * huz; // head.end1 (J1 pivot)

            double scx = filCoord.get(s), scy = filCoord.get(nSeg + s), scz = filCoord.get(2 * nSeg + s);
            double sux = filUVec.get(s), suy = filUVec.get(nSeg + s), suz = filUVec.get(2 * nSeg + s);
            double syx = filYVec.get(s), syy = filYVec.get(nSeg + s), syz = filYVec.get(2 * nSeg + s);
            double sbRGx = filBRotGam.get(s);
            double slen = filSegLength.get(s);

            // ---- F8a: tip (head.end2) → site A (the existing bindArc material point) ----
            double aOffA = bindArc.get(m) - 0.5 * slen;
            double apAx = scx + aOffA * sux, apAy = scy + aOffA * suy, apAz = scz + aOffA * suz;
            double dAx = apAx - tipx, dAy = apAy - tipy, dAz = apAz - tipz;
            double distA = Math.sqrt(dAx * dAx + dAy * dAy + dAz * dAz);
            double FAx = 0, FAy = 0, FAz = 0;
            if (distA > 0.0) { double inv = myoSpring; FAx = inv * dAx; FAy = inv * dAy; FAz = inv * dAz; }   // myoSpring·dist·(unit) = myoSpring·d

            // ---- F8b: rear (head.end1 = J1 pivot) → site B (bindArc2 material point) ----
            double aOffB = bindArc2.get(m) - 0.5 * slen;
            double apBx = scx + aOffB * sux, apBy = scy + aOffB * suy, apBz = scz + aOffB * suz;
            double dBx = apBx - rearx, dBy = apBy - reary, dBz = apBz - rearz;
            double distB = Math.sqrt(dBx * dBx + dBy * dBy + dBz * dBz);
            double FBx = 0, FBy = 0, FBz = 0;
            if (distB > 0.0) { double inv = myoSpring; FBx = inv * dBx; FBy = inv * dBy; FBz = inv * dBz; }

            // ---- head-side positional torques (R in metres) ----
            double RtAx = (tipx - hcx) * 1e-6, RtAy = (tipy - hcy) * 1e-6, RtAz = (tipz - hcz) * 1e-6;
            double THAx = RtAy * FAz - RtAz * FAy, THAy = RtAz * FAx - RtAx * FAz, THAz = RtAx * FAy - RtAy * FAx;
            double RrBx = (rearx - hcx) * 1e-6, RrBy = (reary - hcy) * 1e-6, RrBz = (rearz - hcz) * 1e-6;
            double THBx = RrBy * FBz - RrBz * FBy, THBy = RrBz * FBx - RrBx * FBz, THBz = RrBx * FBy - RrBy * FBx;
            // ---- seg-side positional torques (reaction −F at each site) ----
            double RSAx = (apAx - scx) * 1e-6, RSAy = (apAy - scy) * 1e-6, RSAz = (apAz - scz) * 1e-6;
            double TSAx = RSAy * (-FAz) - RSAz * (-FAy), TSAy = RSAz * (-FAx) - RSAx * (-FAz), TSAz = RSAx * (-FAy) - RSAy * (-FAx);
            double RSBx = (apBx - scx) * 1e-6, RSBy = (apBy - scy) * 1e-6, RSBz = (apBz - scz) * 1e-6;
            double TSBx = RSBy * (-FBz) - RSBz * (-FBy), TSBy = RSBz * (-FBx) - RSBx * (-FBz), TSBz = RSBx * (-FBy) - RSBy * (-FBx);

            // ---- F10 roll/yVec alignment torque toward CONSTANT 0° (NOT a stroke driver; F9 removed) ----
            double t10x = syy * hyz - syz * hyy, t10y = syz * hyx - syx * hyz, t10z = syx * hyy - syy * hyx;
            double m10 = t10x * t10x + t10y * t10y + t10z * t10z;
            double T10x = 0, T10y = 0, T10z = 0;
            if (m10 > 1.0e-30) {
                double im = 1.0 / Math.sqrt(m10); t10x *= im; t10y *= im; t10z *= im;
                double dot = syx * hyx + syy * hyy + syz * hyz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
                double ang = accurateAcos(dot) * RAD2DEG;
                double tm = j1FMT * DEG2RAD * ang / ((1.0 / hbRGx + 1.0 / sbRGx) * dt);
                T10x = tm * t10x; T10y = tm * t10y; T10z = tm * t10z;
            }

            // head-side: +F8a +F8b, torque (THA + THB − T10)
            bondData.set(d,     (float) (FAx + FBx)); bondData.set(d + 1, (float) (FAy + FBy)); bondData.set(d + 2, (float) (FAz + FBz));
            bondData.set(d + 3, (float) (THAx + THBx - T10x));
            bondData.set(d + 4, (float) (THAy + THBy - T10y));
            bondData.set(d + 5, (float) (THAz + THBz - T10z));
            // seg-side: −F8a −F8b, torque (TSA + TSB + T10)
            bondData.set(d + 6, (float) (-(FAx + FBx))); bondData.set(d + 7, (float) (-(FAy + FBy))); bondData.set(d + 8, (float) (-(FAz + FBz)));
            bondData.set(d + 9,  (float) (TSAx + TSBx + T10x));
            bondData.set(d + 10, (float) (TSAy + TSBy + T10y));
            bondData.set(d + 11, (float) (TSAz + TSBz + T10z));
            // forceDotFil = Dot(F8a + F8b, seg.uVec) — the LEVER-STRAIN load (net two-point along-filament load)
            bondData.set(d + 12, (float) ((FAx + FBx) * sux + (FAy + FBy) * suy + (FAz + FBz) * suz));
        }
    }

    /**
     * CANONICAL_MOTOR CONFIG 1 — PERP-HEAD variant (flag-gated; PHASE2_PERP_HEAD_FINDINGS.md). The bound-geometry
     * report found the TWO-POINT head pin mis-delivers the powerstroke: rigidly fixed at tip + rear, the head
     * cannot slide on actin, so the converter swing presents only a transverse reaction couple (bending, not
     * transport). This variant is config-1 with ONE structural change (jba):
     *   (1) REMOVE the rear pin (pin B / bindArc2). The head is no longer two-point-pinned.
     *   (2) KEEP the tip pin (pin A / bindArc) — the head's tip stays stereospecifically bound (single-point
     *       positional PAIRS pin; the strong-bound contact does not slide).
     *   (3) ADD a PAIRS-form orientation torque (the dt-robust fracMove/(moveC·dt) family, same convention as the
     *       config-1 pins / the F9/F10 alignment) that drives the head's uVec toward `perpRest` — the FROZEN-at-bind
     *       ⊥-to-filament direction. This replaces the rigid rear-pin orientation constraint with a soft-but-dt-
     *       robust torque so the head STANDS ⊥ to actin (the converter swing must then move the LEVER, not rotate
     *       the head flat). +T on the head, −T reaction on the segment (mirrors F9/F10; perpRest is frozen, ≈the
     *       fixed filament's ⊥ direction).
     * Everything else is HELD identical to config1: the J1 Hookean converter (0°↔60° switch in MotorJointSystem),
     * the catch load forceDotFil = signed J1 lever strain (κ/L)·(θ_rest−θ), κ, the PAIRS pin form. bindArc2 is
     * UNUSED on this path (dropped from the signature; the array stays allocated).
     *
     * xbParams (config1-perp): [0]=fracMove(PAIRS pin) [1]=κ(N·m/rad) [2]=leverLen(µm) [3]=dt [4]=HEAD_LEN(µm)
     *                          [5]=orientFracMove (the ⊥-orientation torque strength; matches the rear-pin scale).
     * Same bondData stride-13 layout / head-self-write / seg-side gather (reused VERBATIM). Default path untouched.
     */
    public static void bondForcesCanonicalConfig1Perp(
            FloatArray motorCoord, FloatArray motorUVec, FloatArray motorBTransGam, FloatArray motorBRotGam,
            FloatArray filCoord, FloatArray filUVec, FloatArray filSegLength, FloatArray filBTransGam, FloatArray filBRotGam,
            IntArray boundSeg, FloatArray bindArc, FloatArray perpRest, IntArray nucleotideState,
            FloatArray bondData, FloatArray xbParams) {

        int nB = motorCoord.getSize() / 3;
        int nSeg = filCoord.getSize() / 3;
        int nM = nB / 3;
        double fracMove = xbParams.get(0), kappa = xbParams.get(1), leverLenUm = xbParams.get(2);
        double dt = xbParams.get(3), headLen = xbParams.get(4);
        double orientFrac = (xbParams.getSize() > 5) ? xbParams.get(5) : fracMove;
        double DEG2RAD = Math.PI / 180.0, RAD2DEG = 180.0 / Math.PI;

        for (@Parallel int m = 0; m < nM; m++) {
            int d = m * STRIDE;
            for (int k = 0; k < STRIDE; k++) bondData.set(d + k, 0f);
            int s = boundSeg.get(m);
            if (s < 0) continue;

            int h = 3 * m + 2, lv = 3 * m + 1;
            double hcx = motorCoord.get(h), hcy = motorCoord.get(nB + h), hcz = motorCoord.get(2 * nB + h);
            double hux = motorUVec.get(h), huy = motorUVec.get(nB + h), huz = motorUVec.get(2 * nB + h);
            double hbTGx = motorBTransGam.get(h), hbTGy = motorBTransGam.get(nB + h), hbRGy = motorBRotGam.get(nB + h);
            double tipx = hcx + 0.5 * headLen * hux, tipy = hcy + 0.5 * headLen * huy, tipz = hcz + 0.5 * headLen * huz;   // head.end2

            double scx = filCoord.get(s), scy = filCoord.get(nSeg + s), scz = filCoord.get(2 * nSeg + s);
            double sux = filUVec.get(s), suy = filUVec.get(nSeg + s), suz = filUVec.get(2 * nSeg + s);
            double slen = filSegLength.get(s);
            double sbTGx = filBTransGam.get(s), sbTGy = filBTransGam.get(nSeg + s), sbRGy = filBRotGam.get(nSeg + s);

            // ---- PAIRS pin A: tip (head.end2) → site A (bindArc). The ONLY positional pin (rear pin removed). ----
            double aOffA = bindArc.get(m) - 0.5 * slen;
            double apAx = scx + aOffA * sux, apAy = scy + aOffA * suy, apAz = scz + aOffA * suz;
            double dAx = apAx - tipx, dAy = apAy - tipy, dAz = apAz - tipz;
            double distA = Math.sqrt(dAx * dAx + dAy * dAy + dAz * dAz);
            double FAx = 0, FAy = 0, FAz = 0;
            if (distA > 0.0) {
                double lAx = dAx / distA, lAy = dAy / distA, lAz = dAz / distA;
                double mcH = moveC(hux, huy, huz, lAx, lAy, lAz, hbTGx, hbTGy, hbRGy, headLen);
                double mcS = moveC(sux, suy, suz, lAx, lAy, lAz, sbTGx, sbTGy, sbRGy, slen);
                double denom = dt * (mcH + mcS);
                double fmag = (denom > 0.0) ? (fracMove * 1.0e-6 * distA / denom) : 0.0;
                FAx = fmag * lAx; FAy = fmag * lAy; FAz = fmag * lAz;
            }

            // ---- tip-pin positional torque (R in metres) ----
            double RtAx = (tipx - hcx) * 1e-6, RtAy = (tipy - hcy) * 1e-6, RtAz = (tipz - hcz) * 1e-6;
            double THAx = RtAy * FAz - RtAz * FAy, THAy = RtAz * FAx - RtAx * FAz, THAz = RtAx * FAy - RtAy * FAx;
            double RSAx = (apAx - scx) * 1e-6, RSAy = (apAy - scy) * 1e-6, RSAz = (apAz - scz) * 1e-6;
            double TSAx = RSAy * (-FAz) - RSAz * (-FAy), TSAy = RSAz * (-FAx) - RSAx * (-FAz), TSAz = RSAx * (-FAy) - RSAy * (-FAx);

            // ---- ⊥-orientation PAIRS torque: drive head.uVec → perpRest (frozen). +T head, −T seg. ----
            double px = perpRest.get(m), py = perpRest.get(nM + m), pz = perpRest.get(2 * nM + m);
            double Tox = 0, Toy = 0, Toz = 0;
            // axis = head.uVec × perpRest (rotates head.uVec TOWARD perpRest by the right-hand rule)
            double aox = huy * pz - huz * py, aoy = huz * px - hux * pz, aoz = hux * py - huy * px;
            double mo = aox * aox + aoy * aoy + aoz * aoz;
            if (mo > 1.0e-30) {
                double im = 1.0 / Math.sqrt(mo); aox *= im; aoy *= im; aoz *= im;
                double dot = hux * px + huy * py + huz * pz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
                double angRad = accurateAcos(dot);    // angle to rotate head.uVec onto perpRest
                double tm = orientFrac * angRad / ((1.0 / hbRGy + 1.0 / sbRGy) * dt);
                Tox = tm * aox; Toy = tm * aoy; Toz = tm * aoz;
            }

            // ---- J1 lever strain → forceDotFil (the catch load; unchanged from config1) ----
            double lux = motorUVec.get(lv), luy = motorUVec.get(nB + lv), luz = motorUVec.get(2 * nB + lv);
            double dotV = lux * hux + luy * huy + luz * huz; if (dotV > 1) dotV = 1; if (dotV < -1) dotV = -1;
            double angDeg = accurateAcos(dotV) * RAD2DEG;
            double j1Rest = (nucleotideState.get(m) != MotorStore.NUC_ADPPI) ? 60.0 : 0.0;
            double deflRad = (angDeg - j1Rest) * DEG2RAD;
            double leverM = leverLenUm * 1.0e-6;
            double forceDot = (leverM > 0.0) ? (kappa / leverM) * (-deflRad) : 0.0;   // resisting (θ<rest) ⇒ +

            // head-side: +F8a tip pin, torque (THA + T_orient)
            bondData.set(d,     (float) FAx); bondData.set(d + 1, (float) FAy); bondData.set(d + 2, (float) FAz);
            bondData.set(d + 3, (float) (THAx + Tox));
            bondData.set(d + 4, (float) (THAy + Toy));
            bondData.set(d + 5, (float) (THAz + Toz));
            // seg-side: −F8a, torque (TSA − T_orient reaction)
            bondData.set(d + 6, (float) (-FAx)); bondData.set(d + 7, (float) (-FAy)); bondData.set(d + 8, (float) (-FAz));
            bondData.set(d + 9,  (float) (TSAx - Tox));
            bondData.set(d + 10, (float) (TSAy - Toy));
            bondData.set(d + 11, (float) (TSAz - Toz));
            bondData.set(d + 12, (float) forceDot);   // the J1 lever-strain load (NOT a pin tension)
        }
    }

    /**
     * PHASE-2 PERP-HEAD — freeze the per-motor ⊥-orientation rest at FRESH bind (the gliding-pipeline analog of
     * the force-decomp gate's bind-time uperp computation). Runs right after the (two-point) binder, BEFORE
     * snapCanonicalHead clears `canonSnap`: for each motor whose `canonSnap != 0` (a fresh formation this step) it
     * computes `uperp` = the ⊥-to-filament direction nearest the head's current uVec (ẑ-fallback when the head is
     * near-axial — the expected fresh-bind regime) and stores it in `perpRest`. Gating on `canonSnap` makes it a
     * one-shot FREEZE (subsequent steps see canonSnap=0 ⇒ perpRest is held). Does NOT touch the body pose (only
     * writes perpRest) ⇒ safe to place early (unlike snapCanonicalHead). Does NOT clear canonSnap (snapCanonicalHead
     * owns that). ADDITIVE — only the PERP-HEAD gliding path calls it; byte-identical default. */
    public static void snapPerpRest(FloatArray motorUVec, FloatArray filUVec, IntArray boundSeg,
                                    IntArray canonSnap, FloatArray perpRest, IntArray counts, FloatArray headTiltCS) {
        int nB = motorUVec.getSize() / 3;
        int nSeg = filUVec.getSize() / 3;
        int nM = nB / 3;
        double cosT = headTiltCS.get(0), sinT = headTiltCS.get(1), tiltSet = headTiltCS.get(2);
        for (@Parallel int m = 0; m < nM; m++) {
            if (canonSnap.get(m) == 0) continue;          // only freshly-bound motors ⇒ frozen otherwise
            int s = boundSeg.get(m);
            if (s < 0) continue;
            int h = 3 * m + 2;
            double hux = motorUVec.get(h), huy = motorUVec.get(nB + h), huz = motorUVec.get(2 * nB + h);
            double sux = filUVec.get(s), suy = filUVec.get(nSeg + s), suz = filUVec.get(2 * nSeg + s);
            double axc = hux * sux + huy * suy + huz * suz;
            double upx = hux - axc * sux, upy = huy - axc * suy, upz = huz - axc * suz;   // ⊥ component of the head uVec (perpRest, SURFACE-FREE)
            double mag = Math.sqrt(upx * upx + upy * upy + upz * upz);
            if (mag < 0.1) {                              // head near-axial (degenerate) ⇒ fallback: project the surface normal ẑ
                double zc = suz;                          // ẑ·seg
                upx = -zc * sux; upy = -zc * suy; upz = 1.0 - zc * suz;
                mag = Math.sqrt(upx * upx + upy * upy + upz * upz);
            }
            if (mag > 0.0) { upx /= mag; upy /= mag; upz /= mag; }
            // PHASE-2 HEAD-ANGLE SWEEP — when tiltSet, rotate the rest target within the {f,u} plane to angle θ:
            // Target(θ) = cos θ·f_hat + sin θ·perpRest, f_hat = sign(u·f)·f (θ=0 ⇒ head aligned to actin, θ=90 ⇒ ⊥).
            // SURFACE-FREE in the non-degenerate case (uses the filament direction only). θ=90 ⇒ Target≈perpRest.
            if (tiltSet != 0.0) {
                double sgn = (axc >= 0.0) ? 1.0 : -1.0;
                double fhx = sgn * sux, fhy = sgn * suy, fhz = sgn * suz;
                double tx = cosT * fhx + sinT * upx, ty = cosT * fhy + sinT * upy, tz = cosT * fhz + sinT * upz;
                double tm = Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (tm > 0.0) { upx = tx / tm; upy = ty / tm; upz = tz / tm; }
            }
            perpRest.set(m, (float) upx); perpRest.set(nM + m, (float) upy); perpRest.set(2 * nM + m, (float) upz);
        }
    }

    /** v1 moveCoeff (VERBATIM MotorJointSystem.moveC / the PAIRS effective mobility along a link). */
    private static double moveC(double ux, double uy, double uz,
                               double lx, double ly, double lz,
                               double bTGx, double bTGy, double bRGy, double lenUm) {
        double cosB = ux * lx + uy * ly + uz * lz;
        if (cosB > 1.0) cosB = 1.0; if (cosB < -1.0) cosB = -1.0;
        double cosB2 = cosB * cosB;
        double cosA2 = 1.0 - cosB2;
        double lSq = 1.0e-12 * lenUm * lenUm;
        return cosB2 / bTGx + cosA2 / bTGy + lSq * cosA2 / (4.0 * bRGy);
    }

    /**
     * CANONICAL_MOTOR CONFIG 1 (flag-gated; the COMPLETE composed cross-bridge architecture, MOTOR_BENCHMARK_TARGETS
     * §6; PHASE2_CONFIG1_FINDINGS.md). Division of labor that retires the two SOFT translational F8 springs (whose
     * Brownian-head-vs-soft-spring wandering was the phase-2 thermal lever-strain tail):
     *
     *   (1) TIP + REAR ATTACHMENTS → PAIRS form. Each attachment (head.end2→siteA `bindArc`, head.end1→siteB
     *       `bindArc2`) is the dt-robust DAMPING-LIMITED connection `fmag = fracMove·1e-6·strain/(dt·(mcHead+mcSeg))`
     *       (the actin-layer PAIRS magnitude, VERBATIM moveC), applied at the attachment point with the full
     *       positional torque R×F. Two such pins (HEAD_LEN apart on the head axis) hold the head RIGIDLY — position
     *       AND orientation (the couple pins uVec ∥ filament; roll about uVec is a free decoupled DOF, so F9/F10 are
     *       BOTH dropped). PAIRS reports NO load (it only maintains geometry, dt-robustly) ⇒ the gating question
     *       (§6 "can PAIRS expose the load?") is SIDESTEPPED — the load is read from J1, not from these pins.
     *   (2) J1 → Hookean torsional spring (in MotorJointSystem, config1 branch): rest still switches 0°↔60° with
     *       nucleotide state (J1 still DRIVES the stroke) and its deflection under load IS the compliance.
     *   (3) forceDotFil (the catch load) → the SIGNED J1 LEVER STRAIN: `(κ/L)·(θ_rest − θ)`, the lever-tip-
     *       equivalent force of the J1 deflection, signed so resisting (held below the cocked rest) is POSITIVE
     *       (the catch convention; matches the old +0.285 pN isometric sign). PAIRS pins, J1 reports.
     *
     * Same bondData stride-13 layout / head-self-write / seg-side gather (reused VERBATIM). Default path untouched.
     * xbParams (config1): [0]=fracMove(PAIRS) [1]=κ(N·m/rad) [2]=leverLen(µm) [3]=dt [4]=HEAD_LEN(µm).
     */
    public static void bondForcesCanonicalConfig1(
            FloatArray motorCoord, FloatArray motorUVec, FloatArray motorBTransGam, FloatArray motorBRotGam,
            FloatArray filCoord, FloatArray filUVec, FloatArray filSegLength, FloatArray filBTransGam, FloatArray filBRotGam,
            IntArray boundSeg, FloatArray bindArc, FloatArray bindArc2, IntArray nucleotideState,
            FloatArray bondData, FloatArray xbParams) {

        int nB = motorCoord.getSize() / 3;
        int nSeg = filCoord.getSize() / 3;
        int nM = nB / 3;
        double fracMove = xbParams.get(0), kappa = xbParams.get(1), leverLenUm = xbParams.get(2);
        double dt = xbParams.get(3), headLen = xbParams.get(4);
        double DEG2RAD = Math.PI / 180.0, RAD2DEG = 180.0 / Math.PI;

        for (@Parallel int m = 0; m < nM; m++) {
            int d = m * STRIDE;
            for (int k = 0; k < STRIDE; k++) bondData.set(d + k, 0f);
            int s = boundSeg.get(m);
            if (s < 0) continue;

            int h = 3 * m + 2, lv = 3 * m + 1;
            double hcx = motorCoord.get(h), hcy = motorCoord.get(nB + h), hcz = motorCoord.get(2 * nB + h);
            double hux = motorUVec.get(h), huy = motorUVec.get(nB + h), huz = motorUVec.get(2 * nB + h);
            double hbTGx = motorBTransGam.get(h), hbTGy = motorBTransGam.get(nB + h), hbRGy = motorBRotGam.get(nB + h);
            double tipx = hcx + 0.5 * headLen * hux, tipy = hcy + 0.5 * headLen * huy, tipz = hcz + 0.5 * headLen * huz;   // head.end2
            double rearx = hcx - 0.5 * headLen * hux, reary = hcy - 0.5 * headLen * huy, rearz = hcz - 0.5 * headLen * huz; // head.end1 (J1 pivot)

            double scx = filCoord.get(s), scy = filCoord.get(nSeg + s), scz = filCoord.get(2 * nSeg + s);
            double sux = filUVec.get(s), suy = filUVec.get(nSeg + s), suz = filUVec.get(2 * nSeg + s);
            double slen = filSegLength.get(s);
            double sbTGx = filBTransGam.get(s), sbTGy = filBTransGam.get(nSeg + s), sbRGy = filBRotGam.get(nSeg + s);

            // ---- PAIRS pin A: tip (head.end2) → site A (bindArc) ----
            double aOffA = bindArc.get(m) - 0.5 * slen;
            double apAx = scx + aOffA * sux, apAy = scy + aOffA * suy, apAz = scz + aOffA * suz;
            double dAx = apAx - tipx, dAy = apAy - tipy, dAz = apAz - tipz;
            double distA = Math.sqrt(dAx * dAx + dAy * dAy + dAz * dAz);
            double FAx = 0, FAy = 0, FAz = 0;
            if (distA > 0.0) {
                double lAx = dAx / distA, lAy = dAy / distA, lAz = dAz / distA;
                double mcH = moveC(hux, huy, huz, lAx, lAy, lAz, hbTGx, hbTGy, hbRGy, headLen);
                double mcS = moveC(sux, suy, suz, lAx, lAy, lAz, sbTGx, sbTGy, sbRGy, slen);
                double denom = dt * (mcH + mcS);
                double fmag = (denom > 0.0) ? (fracMove * 1.0e-6 * distA / denom) : 0.0;
                FAx = fmag * lAx; FAy = fmag * lAy; FAz = fmag * lAz;
            }
            // ---- PAIRS pin B: rear (head.end1) → site B (bindArc2) ----
            double aOffB = bindArc2.get(m) - 0.5 * slen;
            double apBx = scx + aOffB * sux, apBy = scy + aOffB * suy, apBz = scz + aOffB * suz;
            double dBx = apBx - rearx, dBy = apBy - reary, dBz = apBz - rearz;
            double distB = Math.sqrt(dBx * dBx + dBy * dBy + dBz * dBz);
            double FBx = 0, FBy = 0, FBz = 0;
            if (distB > 0.0) {
                double lBx = dBx / distB, lBy = dBy / distB, lBz = dBz / distB;
                double mcH = moveC(hux, huy, huz, lBx, lBy, lBz, hbTGx, hbTGy, hbRGy, headLen);
                double mcS = moveC(sux, suy, suz, lBx, lBy, lBz, sbTGx, sbTGy, sbRGy, slen);
                double denom = dt * (mcH + mcS);
                double fmag = (denom > 0.0) ? (fracMove * 1.0e-6 * distB / denom) : 0.0;
                FBx = fmag * lBx; FBy = fmag * lBy; FBz = fmag * lBz;
            }

            // ---- positional torques (R in metres) ----
            double RtAx = (tipx - hcx) * 1e-6, RtAy = (tipy - hcy) * 1e-6, RtAz = (tipz - hcz) * 1e-6;
            double THAx = RtAy * FAz - RtAz * FAy, THAy = RtAz * FAx - RtAx * FAz, THAz = RtAx * FAy - RtAy * FAx;
            double RrBx = (rearx - hcx) * 1e-6, RrBy = (reary - hcy) * 1e-6, RrBz = (rearz - hcz) * 1e-6;
            double THBx = RrBy * FBz - RrBz * FBy, THBy = RrBz * FBx - RrBx * FBz, THBz = RrBx * FBy - RrBy * FBx;
            double RSAx = (apAx - scx) * 1e-6, RSAy = (apAy - scy) * 1e-6, RSAz = (apAz - scz) * 1e-6;
            double TSAx = RSAy * (-FAz) - RSAz * (-FAy), TSAy = RSAz * (-FAx) - RSAx * (-FAz), TSAz = RSAx * (-FAy) - RSAy * (-FAx);
            double RSBx = (apBx - scx) * 1e-6, RSBy = (apBy - scy) * 1e-6, RSBz = (apBz - scz) * 1e-6;
            double TSBx = RSBy * (-FBz) - RSBz * (-FBy), TSBy = RSBz * (-FBx) - RSBx * (-FBz), TSBz = RSBx * (-FBy) - RSBy * (-FBx);

            // ---- J1 lever strain → forceDotFil (the catch load; PAIRS pins, J1 reports) ----
            double lux = motorUVec.get(lv), luy = motorUVec.get(nB + lv), luz = motorUVec.get(2 * nB + lv);
            double dotV = lux * hux + luy * huy + luz * huz; if (dotV > 1) dotV = 1; if (dotV < -1) dotV = -1;
            double angDeg = accurateAcos(dotV) * RAD2DEG;
            double j1Rest = (nucleotideState.get(m) != MotorStore.NUC_ADPPI) ? 60.0 : 0.0;
            double deflRad = (angDeg - j1Rest) * DEG2RAD;
            double leverM = leverLenUm * 1.0e-6;
            double forceDot = (leverM > 0.0) ? (kappa / leverM) * (-deflRad) : 0.0;   // resisting (θ<rest) ⇒ +

            bondData.set(d,     (float) (FAx + FBx)); bondData.set(d + 1, (float) (FAy + FBy)); bondData.set(d + 2, (float) (FAz + FBz));
            bondData.set(d + 3, (float) (THAx + THBx));
            bondData.set(d + 4, (float) (THAy + THBy));
            bondData.set(d + 5, (float) (THAz + THBz));
            bondData.set(d + 6, (float) (-(FAx + FBx))); bondData.set(d + 7, (float) (-(FAy + FBy))); bondData.set(d + 8, (float) (-(FAz + FBz)));
            bondData.set(d + 9,  (float) (TSAx + TSBx));
            bondData.set(d + 10, (float) (TSAy + TSBy));
            bondData.set(d + 11, (float) (TSAz + TSBz));
            bondData.set(d + 12, (float) forceDot);   // the J1 lever-strain load (NOT a pin tension)
        }
    }

    /** MEASUREMENT-ONLY parallel DASHPOT on F8 (CROSSBRIDGE_DASHPOT_FINDINGS). Kelvin-Voigt = spring ∥ dashpot:
     *  adds F_dash = γ_xb·(b_n − b_{n-1})/dt to the head-side force, where b = (site − head_tip) is the bond vector
     *  (so F_dash opposes the head's velocity RELATIVE to the site — a stretch-velocity, history-aware drag, NOT a
     *  magnitude law). The stretch-mode effective drag becomes γ_eff = γ_head + γ_xb ⇒ r = k·dt/γ_eff drops without
     *  softening the spring and without slowing the FREE head's diffusion (a free head has no site ⇒ no dashpot).
     *  γ_xb = gammaMult · (head's own bTransGam, SI N·s/m), read per-head ⇒ unit-consistent with the integrator.
     *  EXPLICIT (finite-difference velocity from a per-bond stored previous b); may itself be fragile at coarse dt
     *  (a semi-implicit dashpot is the flagged follow-on). Runs AFTER bondForces, BEFORE applyHeadForce/segGather/
     *  registerForceDot, so the head/seg force, the torque, forceMag (the cap) AND forceDotFil (the catch) all pick
     *  up the dashpot load. dashInit[m]=0 ⇒ seed (fresh bond / unbound, no velocity yet). prevStretch planar 3·nM.
     *  ADDITIVE: only V2OneX/Gliding wire it (when -xbdash set); never called elsewhere ⇒ byte-identical default.
     *  dashParams: [0]=gammaMult [1]=dt [2]=HEAD_LEN [3]=mechOnly. mechOnly=1 (-xbdashmech): the dashpot adds its
     *  MECHANICAL force/torque (head+seg) but does NOT feed forceDotFil (the catch reads the SPRING load only) —
     *  isolates the overshoot-suppression mechanism from the catch-detonation artifact of the explicit dashpot's
     *  thermal-velocity transient (≈γ_xb·√(2D/dt), which dominates at fine dt). */
    public static void dashpotForces(
            FloatArray motorCoord, FloatArray motorUVec, FloatArray motorBTransGam,
            FloatArray filCoord, FloatArray filUVec, FloatArray filSegLength,
            IntArray boundSeg, FloatArray bindArc,
            FloatArray bondData, FloatArray prevStretch, IntArray dashInit, FloatArray dashParams) {
        int nB = motorCoord.getSize() / 3;
        int nSeg = filCoord.getSize() / 3;
        int nM = nB / 3;
        double gammaMult = dashParams.get(0), dt = dashParams.get(1), headLen = dashParams.get(2);
        int mechOnly = (int) dashParams.get(3);   // 1 ⇒ dashpot omitted from forceDotFil (catch reads spring only)

        for (@Parallel int m = 0; m < nM; m++) {
            int s = boundSeg.get(m);
            if (s < 0) { dashInit.set(m, 0); continue; }   // unbound ⇒ tracker stale (mirrors registerForceDot reset)

            int h = 3 * m + 2;
            double hcx = motorCoord.get(h), hcy = motorCoord.get(nB + h), hcz = motorCoord.get(2 * nB + h);
            double hux = motorUVec.get(h), huy = motorUVec.get(nB + h), huz = motorUVec.get(2 * nB + h);
            double htipx = hcx + 0.5 * headLen * hux, htipy = hcy + 0.5 * headLen * huy, htipz = hcz + 0.5 * headLen * huz;

            double scx = filCoord.get(s), scy = filCoord.get(nSeg + s), scz = filCoord.get(2 * nSeg + s);
            double sux = filUVec.get(s), suy = filUVec.get(nSeg + s), suz = filUVec.get(2 * nSeg + s);
            double slen = filSegLength.get(s);
            double aOff = bindArc.get(m) - 0.5 * slen;
            double apx = scx + aOff * sux, apy = scy + aOff * suy, apz = scz + aOff * suz;

            // bond vector b = site − head_tip (same convention as the spring's d), µm
            double bx = apx - htipx, by = apy - htipy, bz = apz - htipz;

            if (dashInit.get(m) == 0) {                    // fresh bond: seed, no velocity this step
                prevStretch.set(m, (float) bx); prevStretch.set(nM + m, (float) by); prevStretch.set(2 * nM + m, (float) bz);
                dashInit.set(m, 1);
                continue;
            }
            double pbx = prevStretch.get(m), pby = prevStretch.get(nM + m), pbz = prevStretch.get(2 * nM + m);
            prevStretch.set(m, (float) bx); prevStretch.set(nM + m, (float) by); prevStretch.set(2 * nM + m, (float) bz);

            // γ_xb = gammaMult · head bTransGam (SI N·s/m); Δb in µm → ·1e-6 m ⇒ F_dash in N
            double gxb = gammaMult * motorBTransGam.get(h);
            double k = gxb * 1.0e-6 / dt;
            double Fdx = k * (bx - pbx), Fdy = k * (by - pby), Fdz = k * (bz - pbz);

            int d = m * STRIDE;
            // head-side += F_dash ; positional torque RH × F_dash (RH = tip−center, ·1e-6 m)
            double RHx = (htipx - hcx) * 1e-6, RHy = (htipy - hcy) * 1e-6, RHz = (htipz - hcz) * 1e-6;
            bondData.set(d,     (float) (bondData.get(d)     + Fdx));
            bondData.set(d + 1, (float) (bondData.get(d + 1) + Fdy));
            bondData.set(d + 2, (float) (bondData.get(d + 2) + Fdz));
            bondData.set(d + 3, (float) (bondData.get(d + 3) + (RHy * Fdz - RHz * Fdy)));
            bondData.set(d + 4, (float) (bondData.get(d + 4) + (RHz * Fdx - RHx * Fdz)));
            bondData.set(d + 5, (float) (bondData.get(d + 5) + (RHx * Fdy - RHy * Fdx)));
            // seg-side += −F_dash ; positional torque RS × (−F_dash)
            double RSx = (apx - scx) * 1e-6, RSy = (apy - scy) * 1e-6, RSz = (apz - scz) * 1e-6;
            bondData.set(d + 6, (float) (bondData.get(d + 6) - Fdx));
            bondData.set(d + 7, (float) (bondData.get(d + 7) - Fdy));
            bondData.set(d + 8, (float) (bondData.get(d + 8) - Fdz));
            bondData.set(d + 9,  (float) (bondData.get(d + 9)  + (RSy * (-Fdz) - RSz * (-Fdy))));
            bondData.set(d + 10, (float) (bondData.get(d + 10) + (RSz * (-Fdx) - RSx * (-Fdz))));
            bondData.set(d + 11, (float) (bondData.get(d + 11) + (RSx * (-Fdy) - RSy * (-Fdx))));
            // forceDotFil += Dot(F_dash, seg.uVec) — the dashpot's along-filament load (feeds the catch); skipped in mechOnly
            if (mechOnly == 0) bondData.set(d + 12, (float) (bondData.get(d + 12) + (Fdx * sux + Fdy * suy + Fdz * suz)));
        }
    }

    /** LOCALLY-IMPLICIT cross-bridge spring — STEP 1: snapshot each motor head's CENTER (pre-integration c_n).
     *  Cheap (one planar write per motor); the correction below reads it. Writes ALL heads; only bound heads
     *  are corrected (the snapshot is overwritten every step, so writing free heads is harmless). headPrev
     *  planar 3·nM. ADDITIVE/flag-gated (IMPLICIT_CROSSBRIDGE_FINDINGS) ⇒ unused in the default path. */
    public static void snapshotHeadCenter(FloatArray bodyCoord, FloatArray headPrev) {
        int nB = bodyCoord.getSize() / 3;
        int nM = nB / 3;
        for (@Parallel int m = 0; m < nM; m++) {
            int h = 3 * m + 2;
            headPrev.set(m,          bodyCoord.get(h));
            headPrev.set(nM + m,     bodyCoord.get(nB + h));
            headPrev.set(2 * nM + m, bodyCoord.get(2 * nB + h));
        }
    }

    /** LOCALLY-IMPLICIT cross-bridge spring — STEP 2: advance the BOUND head's translational stretch IMPLICITLY.
     *  The convergent lever the five force-law/noise failures pointed at: make the stiff cross-bridge SPRING
     *  implicit (evaluate it at the NEW head position) so it is unconditionally stable and never overshoots,
     *  while leaving the noise EXPLICIT and FDT-correct.
     *
     *  Closed form. The head is a Stokes SPHERE ⇒ ISOTROPIC translational drag γ_head (sphereDragSI) ⇒ the
     *  linearly-implicit overdamped step on the head CENTER is the scalar blend
     *        c_imp = (c_exp + r·c_n) / (1 + r),     r = myoSpring·dt·1e6 / γ_head  ( = k·dt/γ, the overshoot factor)
     *  where c_n is the pre-integration center (snapshotHeadCenter) and c_exp is the EXPLICIT-integrator result
     *  (which carries the explicit spring + joints + cross-bridge torque + THERMAL, all at x_n). Derivation: the
     *  linearly-implicit Euler solve of the central spring F=k(site−tip), with `site` and every other coupling
     *  held EXPLICIT (at x_n), gives Δc_imp = Δc_exp/(1+r) about the spring's force-free point; the `site` term
     *  cancels, leaving the c_n↔c_exp blend (no `site`, no orientation needed — isotropy makes r a scalar).
     *
     *  WHY THIS ≠ THE DASHPOT (CROSSBRIDGE_DASHPOT, which failed): no velocity is computed. The (1+r) denominator
     *  is the spring's own resistance to ALL motion this step — the correct implicit behaviour. The thermal force
     *  enters c_exp at its standard explicit √(2kT/dt) amplitude (NOT damped at source, NOT cooled); it is the
     *  spring that resists it, exactly as a stiff bond should — not a spurious anti-thermal finite-difference force.
     *
     *  SCOPE: bound-head TRANSLATION only. Torque/rotation (the R×F8 positional torque + F9/F10 alignment) stay
     *  EXPLICIT (already dt-robust). `site` + the chain + crosslinkers + the segment stay EXPLICIT ⇒ this is the
     *  cheap "head-implicit, site-explicit" operator split (one division per bound head; the O(dt) split error at
     *  the head↔site boundary is what Stage 2 characterizes). Runs AFTER integrate(b), BEFORE derive(b).
     *  xbImplParams: [0]=myoSpring (N/µm) [1]=dt (s). ADDITIVE/flag-gated ⇒ byte-identical default. */
    public static void implicitCorrect(FloatArray bodyCoord, IntArray boundSeg, FloatArray bodyBTransGam,
                                       FloatArray headPrev, FloatArray xbImplParams) {
        int nB = bodyCoord.getSize() / 3;
        int nM = nB / 3;
        double myoSpring = xbImplParams.get(0), dt = xbImplParams.get(1);
        for (@Parallel int m = 0; m < nM; m++) {
            if (boundSeg.get(m) < 0) continue;
            int h = 3 * m + 2;
            double gh = bodyBTransGam.get(h);                 // head sphere drag (SI N·s/m; isotropic ⇒ scalar)
            double r = myoSpring * dt * 1.0e6 / gh;            // = k·dt/γ, the explicit overshoot factor
            double inv = 1.0 / (1.0 + r);
            double cnx = headPrev.get(m), cny = headPrev.get(nM + m), cnz = headPrev.get(2 * nM + m);
            double cex = bodyCoord.get(h), cey = bodyCoord.get(nB + h), cez = bodyCoord.get(2 * nB + h);
            bodyCoord.set(h,          (float) ((cex + r * cnx) * inv));
            bodyCoord.set(nB + h,     (float) ((cey + r * cny) * inv));
            bodyCoord.set(2 * nB + h, (float) ((cez + r * cnz) * inv));
        }
    }

    // ======================= COUPLED head+SITE implicit F8 (-xbimplicit2) ============================
    // The head-only implicitCorrect above leaves the filament SITE explicit; on the gliding assay the stretch
    // variance is site-motion-dominated (IMPLICIT_XB_CONVERGENCE_FINDINGS), so head-only is only PARTIAL. This
    // solves the bound head AND its bound segment TOGETHER implicitly under the F8 spring. F8 is zero-rest-length
    // (F=k·d) ⇒ isotropic stiffness ⇒ the implicit step is a LINEAR solve; F8 never couples two segments ⇒ the
    // system BLOCK-DIAGONALIZES into per-segment STARS (1 segment + its k_s bound heads). Each star has a
    // CLOSED FORM (head is isotropic sphere ⇒ scalar r_h; segment rod ⇒ diagonal drag in its body frame):
    //     head i (lab):   p_i^imp = A_i + B_i·q^imp,  B_i = r_h/(1+r_h),  A_i = (p_i^e − r_h(q^n − p_i^n))/(1+r_h)
    //     seg (body axis a): q^imp_a = [ q^e_a + r_{s,a}(k_s·q^n − Σp_i^n + ΣA_i)_a ] / [ 1 + r_{s,a}·Σ(1−B_i) ]
    // (the bond offset c_i CANCELS — held explicit, zero rest length). r=myoSpring·dt·1e6/γ. Three parity-clean
    // passes: coupleComputeA (per head PURE) → coupleSolveSeg (per SEGMENT, gather over the boundSeg CSR-inverse,
    // writes its own center) → coupleCorrectHead (per head PURE, reads its segment's new center). No atomics, no
    // KernelContext, disjoint writes ⇒ CPU≡GPU bit-identical-capable. Runs at end-of-step (after BOTH integrates);
    // callers re-derive both bodies. ADDITIVE/flag-gated ⇒ byte-identical default. See COUPLED_IMPLICIT_XB_FINDINGS.

    /** COUPLED STEP 1: snapshot each segment's pre-integration CENTER q_n (segPrev planar 3·nSeg). */
    public static void snapshotSegCenter(FloatArray filCoord, FloatArray segPrev) {
        int nSeg = filCoord.getSize() / 3;
        for (@Parallel int s = 0; s < nSeg; s++) {
            segPrev.set(s,            filCoord.get(s));
            segPrev.set(nSeg + s,     filCoord.get(nSeg + s));
            segPrev.set(2 * nSeg + s, filCoord.get(2 * nSeg + s));
        }
    }

    /** COUPLED Phase 1 (per bound head, PURE): A_i = (p_i^e − r_h(q^n − p_i^n))/(1+r_h), B_i = r_h/(1+r_h).
     *  p_i^e = current head center (post integrate), p_i^n = headPrev, q^n = segPrev[boundSeg]. */
    public static void coupleComputeA(FloatArray bodyCoord, IntArray boundSeg, FloatArray bodyBTransGam,
                                      FloatArray headPrev, FloatArray segPrev, FloatArray cplA, FloatArray cplB,
                                      FloatArray xbImplParams) {
        int nB = bodyCoord.getSize() / 3;
        int nM = nB / 3;
        int nSeg = segPrev.getSize() / 3;
        double myoSpring = xbImplParams.get(0), dt = xbImplParams.get(1);
        for (@Parallel int m = 0; m < nM; m++) {
            int s = boundSeg.get(m);
            if (s < 0) continue;
            int h = 3 * m + 2;
            double gh = bodyBTransGam.get(h);
            double r = myoSpring * dt * 1.0e6 / gh;
            double inv = 1.0 / (1.0 + r);
            double B = r * inv;
            double pex = bodyCoord.get(h),          pey = bodyCoord.get(nB + h),      pez = bodyCoord.get(2 * nB + h);
            double pnx = headPrev.get(m),           pny = headPrev.get(nM + m),       pnz = headPrev.get(2 * nM + m);
            double qnx = segPrev.get(s),            qny = segPrev.get(nSeg + s),      qnz = segPrev.get(2 * nSeg + s);
            cplA.set(m,          (float) ((pex - r * (qnx - pnx)) * inv));
            cplA.set(nM + m,     (float) ((pey - r * (qny - pny)) * inv));
            cplA.set(2 * nM + m, (float) ((pez - r * (qnz - pnz)) * inv));
            cplB.set(m, (float) B);
        }
    }

    /** COUPLED Phase 2 (per SEGMENT, GATHER over the boundSeg CSR-inverse): solve the star's central center q^imp
     *  in the segment's body frame (diagonal drag), write filCoord[s]. Same CSR (segMotorOffsets/segMotorMyo) as
     *  segGather; segment writes only itself ⇒ race-free, no atomics. */
    public static void coupleSolveSeg(IntArray segMotorOffsets, IntArray segMotorMyo,
                                      FloatArray headPrev, FloatArray cplA, FloatArray cplB,
                                      FloatArray filCoord, FloatArray filUVec, FloatArray filYVec, FloatArray filBTransGam,
                                      FloatArray segPrev, FloatArray xbImplParams, IntArray counts) {
        int nSeg = counts.get(3);
        int nM = cplB.getSize();
        double myoSpring = xbImplParams.get(0), dt = xbImplParams.get(1);
        double kdt = myoSpring * dt * 1.0e6;   // = k·dt·1e6 ; r_{s,a} = kdt/γ_{s,a}
        for (@Parallel int s = 0; s < nSeg; s++) {
            int start = segMotorOffsets.get(s), end = segMotorOffsets.get(s + 1);
            int ks = end - start;
            if (ks <= 0) continue;
            // gather Σp_i^n (lab), ΣA_i (lab), Σ(1−B_i)
            double spx = 0, spy = 0, spz = 0, sax = 0, say = 0, saz = 0, sInvB = 0;
            for (int k = start; k < end; k++) {
                int m = segMotorMyo.get(k);
                spx += headPrev.get(m);          spy += headPrev.get(nM + m);      spz += headPrev.get(2 * nM + m);
                sax += cplA.get(m);              say += cplA.get(nM + m);          saz += cplA.get(2 * nM + m);
                sInvB += 1.0 - cplB.get(m);
            }
            double qnx = segPrev.get(s),   qny = segPrev.get(nSeg + s),   qnz = segPrev.get(2 * nSeg + s);
            double qex = filCoord.get(s),  qey = filCoord.get(nSeg + s),  qez = filCoord.get(2 * nSeg + s);
            // V = k_s·q^n − Σp^n + ΣA   (lab)
            double Vx = ks * qnx - spx + sax, Vy = ks * qny - spy + say, Vz = ks * qnz - spz + saz;
            // segment body axes (current orientation): u, y, z=u×y
            double ux = filUVec.get(s), uy = filUVec.get(nSeg + s), uz = filUVec.get(2 * nSeg + s);
            double yx = filYVec.get(s), yy = filYVec.get(nSeg + s), yz = filYVec.get(2 * nSeg + s);
            double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
            double zl = 1.0 / Math.sqrt(zx * zx + zy * zy + zz * zz); zx *= zl; zy *= zl; zz *= zl;
            // per body axis a: r_a = kdt/γ_a ; num_a = (q^e·â) + r_a·(V·â) ; den_a = 1 + r_a·Σ(1−B) ; q^imp_a = num/den
            double rU = kdt / filBTransGam.get(s);
            double rY = kdt / filBTransGam.get(nSeg + s);
            double rZ = kdt / filBTransGam.get(2 * nSeg + s);
            double qeU = qex * ux + qey * uy + qez * uz, VU = Vx * ux + Vy * uy + Vz * uz;
            double qeY = qex * yx + qey * yy + qez * yz, VY = Vx * yx + Vy * yy + Vz * yz;
            double qeZ = qex * zx + qey * zy + qez * zz, VZ = Vx * zx + Vy * zy + Vz * zz;
            double qiU = (qeU + rU * VU) / (1.0 + rU * sInvB);
            double qiY = (qeY + rY * VY) / (1.0 + rY * sInvB);
            double qiZ = (qeZ + rZ * VZ) / (1.0 + rZ * sInvB);
            // rotate q^imp back to lab
            filCoord.set(s,            (float) (qiU * ux + qiY * yx + qiZ * zx));
            filCoord.set(nSeg + s,     (float) (qiU * uy + qiY * yy + qiZ * zy));
            filCoord.set(2 * nSeg + s, (float) (qiU * uz + qiY * yz + qiZ * zz));
        }
    }

    /** COUPLED Phase 3 (per bound head, PURE): p_i^imp = A_i + B_i·q^imp, reading its segment's updated center. */
    public static void coupleCorrectHead(FloatArray bodyCoord, IntArray boundSeg, FloatArray cplA, FloatArray cplB,
                                         FloatArray filCoord, IntArray counts) {
        int nB = bodyCoord.getSize() / 3;
        int nM = nB / 3;
        int nSeg = filCoord.getSize() / 3;
        for (@Parallel int m = 0; m < nM; m++) {
            int s = boundSeg.get(m);
            if (s < 0) continue;
            int h = 3 * m + 2;
            double B = cplB.get(m);
            double qx = filCoord.get(s), qy = filCoord.get(nSeg + s), qz = filCoord.get(2 * nSeg + s);
            bodyCoord.set(h,          (float) (cplA.get(m)          + B * qx));
            bodyCoord.set(nB + h,     (float) (cplA.get(nM + m)     + B * qy));
            bodyCoord.set(2 * nB + h, (float) (cplA.get(2 * nM + m) + B * qz));
        }
    }

    // ============= DIAGONAL per-segment IMPLICIT loaded cross-bridge force (-segimplicit) ============
    // EOM_STABILITY_FINDINGS: the explicit-Euler instability at production dt is the COLLECTIVE loaded
    // cross-bridge force a segment sees (dt_crit ∝ γ/(k_ext+k_F8), rigid worst case marginal ~1.4 /
    // blow-up ~3.8 pN/nm at 1e-5). The cure is backward-Euler on that per-segment self-stiffness, with the
    // HEAD held EXPLICIT (rigid) — the DIAGONAL of the coupled operator. This is exactly the proven
    // `-extimplicit` control (ExternalSpringSystem.applyExternalSpringImplicit) applied to the REAL
    // per-segment cross-bridge sum instead of a test spring: a post-integrate scalar (per body axis) divide,
    //     q_imp,a = (q_e,a + rK_a·q_n,a) / (1 + rK_a),   rK_a = K_tot·dt·1e6 / γ_a
    // where q_e = the FULL explicit integrate output (cross-bridge force already gathered in), q_n = the
    // pre-integrate center (segImplPrev snapshot), K_tot = Σ_bond k = k_s·myoSpring (rigid head ⇒ full F8
    // stiffness, NOT the coupled solve's head-softened Σ(1−B)). Derivation: linearize F_xb(q)=F_xb(q_n)−
    // K_tot(q−q_n), backward-Euler F_xb / explicit F_other ⇒ q_imp(1+rK)=q_e+rK·q_n (the c_i/head-tip terms
    // fold into q_e; anchor is the OLD center q_n, as in applyExternalSpringImplicit with eq→q_n).
    // K_tot isotropic (zero-rest-length F8 ⇒ k·I in lab) but γ diagonal in the body frame ⇒ rotate to body
    // axes, divide per axis, rotate back — the SAME body-frame handling as coupleSolveSeg. Per-segment PURE
    // (reads its own CSR count from segMotorOffsets, writes only its own center) ⇒ race-free, no atomics,
    // no KernelContext, CPU≡GPU-capable. DIAGONAL only: off-diagonal (motor-body cross-segment, chain F3/F4)
    // stay EXPLICIT — the harness showed the chain is soft and -extimplicit (diagonal) stabilized every
    // k_ext. Runs at end-of-step (after integrate+derive of the filament); caller re-derives. ADDITIVE /
    // flag-gated ⇒ byte-identical default. See SEG_IMPLICIT_FINDINGS.md.
    public static void segImplicitSolve(IntArray segMotorOffsets,
                                        FloatArray filCoord, FloatArray filUVec, FloatArray filYVec, FloatArray filBTransGam,
                                        FloatArray segPrev, FloatArray xbImplParams, IntArray counts) {
        int nSeg = counts.get(3);
        double myoSpring = xbImplParams.get(0), dt = xbImplParams.get(1);
        double kdt = myoSpring * dt * 1.0e6;   // per-bond k·dt·1e6 ; K_tot·dt·1e6 = k_s·kdt
        for (@Parallel int s = 0; s < nSeg; s++) {
            int ks = segMotorOffsets.get(s + 1) - segMotorOffsets.get(s);   // K_tot = Σ_bond k = ks·myoSpring
            if (ks <= 0) continue;
            double kdtTot = ks * kdt;                                       // = K_tot·dt·1e6 (rigid head)
            double qnx = segPrev.get(s), qny = segPrev.get(nSeg + s), qnz = segPrev.get(2 * nSeg + s);
            double qex = filCoord.get(s), qey = filCoord.get(nSeg + s), qez = filCoord.get(2 * nSeg + s);
            // segment body axes (current orientation): u, y, z=u×y
            double ux = filUVec.get(s), uy = filUVec.get(nSeg + s), uz = filUVec.get(2 * nSeg + s);
            double yx = filYVec.get(s), yy = filYVec.get(nSeg + s), yz = filYVec.get(2 * nSeg + s);
            double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
            double zl = 1.0 / Math.sqrt(zx * zx + zy * zy + zz * zz); zx *= zl; zy *= zl; zz *= zl;
            double rU = kdtTot / filBTransGam.get(s);
            double rY = kdtTot / filBTransGam.get(nSeg + s);
            double rZ = kdtTot / filBTransGam.get(2 * nSeg + s);
            // project q_e and q_n onto body axes; backward-Euler divide toward q_n (the old center) per axis
            double qeU = qex * ux + qey * uy + qez * uz, qnU = qnx * ux + qny * uy + qnz * uz;
            double qeY = qex * yx + qey * yy + qez * yz, qnY = qnx * yx + qny * yy + qnz * yz;
            double qeZ = qex * zx + qey * zy + qez * zz, qnZ = qnx * zx + qny * zy + qnz * zz;
            double qiU = (qeU + rU * qnU) / (1.0 + rU);
            double qiY = (qeY + rY * qnY) / (1.0 + rY);
            double qiZ = (qeZ + rZ * qnZ) / (1.0 + rZ);
            filCoord.set(s,            (float) (qiU * ux + qiY * yx + qiZ * zx));
            filCoord.set(nSeg + s,     (float) (qiU * uy + qiY * yy + qiZ * zy));
            filCoord.set(2 * nSeg + s, (float) (qiU * uz + qiY * yz + qiZ * zz));
        }
    }

    /** Head self-write: apply the head-side force+torque to the head sub-body (3m+2), += (race-free). */
    public static void applyHeadForce(FloatArray bondData, FloatArray bodyForceSum, FloatArray bodyTorqueSum, IntArray counts) {
        int nB = bodyForceSum.getSize() / 3;
        int nM = nB / 3;
        for (@Parallel int m = 0; m < nM; m++) {
            int h = 3 * m + 2, d = m * STRIDE;
            bodyForceSum.set(h,          (float) (bodyForceSum.get(h)          + bondData.get(d)));
            bodyForceSum.set(nB + h,     (float) (bodyForceSum.get(nB + h)     + bondData.get(d + 1)));
            bodyForceSum.set(2 * nB + h, (float) (bodyForceSum.get(2 * nB + h) + bondData.get(d + 2)));
            bodyTorqueSum.set(h,          (float) (bodyTorqueSum.get(h)          + bondData.get(d + 3)));
            bodyTorqueSum.set(nB + h,     (float) (bodyTorqueSum.get(nB + h)     + bondData.get(d + 4)));
            bodyTorqueSum.set(2 * nB + h, (float) (bodyTorqueSum.get(2 * nB + h) + bondData.get(d + 5)));
        }
    }

    /** Track forceDotFil: instantaneous (catch-slip) + a 10-window ring (the ADP→NONE gate average,
     *  v1 ValueTracker(10)). Free motors reset the tracker (v1 release().zero()).
     *  §6.10: also publish forceMag = |F8| (the cross-bridge spring MAGNITUDE, v1 MyoFilLink.forceMag)
     *  — the head-side force bondData[d..d+2] already has magnitude myoSpring·dist, so this is a sqrt
     *  of stored values, NOT a re-derivation of the force law. Kept in lockstep with forceDotFil (same
     *  vintage in stepOrig last-step / stepFresh this-step) so the cap reads the same force the
     *  catch-slip draw does, exactly as v1's single addForces writes both. */
    public static void registerForceDot(FloatArray bondData, IntArray boundSeg,
                                        FloatArray forceDotFil, FloatArray forceMag, FloatArray forceDotHist, IntArray forceDotPlace, IntArray counts) {
        int nM = boundSeg.getSize();
        for (@Parallel int m = 0; m < nM; m++) {
            if (boundSeg.get(m) >= 0) {
                int d = m * STRIDE;
                float fd = bondData.get(d + 12);
                forceDotFil.set(m, fd);
                float fx = bondData.get(d), fy = bondData.get(d + 1), fz = bondData.get(d + 2);
                forceMag.set(m, (float) Math.sqrt(fx * fx + fy * fy + fz * fz));
                int p = forceDotPlace.get(m);
                forceDotHist.set(m * 10 + p, fd);
                forceDotPlace.set(m, (p + 1) % 10);
            } else {
                forceDotFil.set(m, 0f);
                forceMag.set(m, 0f);
                forceDotPlace.set(m, 0);
                int b = m * 10;
                for (int k = 0; k < 10; k++) forceDotHist.set(b + k, 0f);
            }
        }
    }

    // ===================== segment→bound-motors CSR-inverse (inc-3 pattern, no atomics) =============
    public static void csrHistogram(IntArray boundSeg, IntArray counts, IntArray segMotorCount) {
        int nSeg = counts.get(3);
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int nM = counts.get(0);
            for (int s = 0; s < nSeg; s++) segMotorCount.set(s, 0);
            for (int m = 0; m < nM; m++) { int s = boundSeg.get(m); if (s >= 0) segMotorCount.set(s, segMotorCount.get(s) + 1); }
        }
    }
    public static void csrScan(IntArray counts, IntArray segMotorCount, IntArray segMotorOffsets) {
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int nSeg = counts.get(3);
            int acc = 0;
            for (int s = 0; s < nSeg; s++) { segMotorOffsets.set(s, acc); acc += segMotorCount.get(s); segMotorCount.set(s, 0); }
            segMotorOffsets.set(nSeg, acc);
        }
    }
    public static void csrScatter(IntArray boundSeg, IntArray counts, IntArray segMotorOffsets,
                                  IntArray segMotorCount, IntArray segMotorMyo) {
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int nM = counts.get(0);
            for (int m = 0; m < nM; m++) {
                int s = boundSeg.get(m); if (s < 0) continue;
                int pos = segMotorOffsets.get(s) + segMotorCount.get(s);
                segMotorMyo.set(pos, m);
                segMotorCount.set(s, segMotorCount.get(s) + 1);
            }
        }
    }

    // ===================== PARALLEL CSR-inverse (atomic-free counting sort) ==========================
    // Retires the single-threaded csrHistogram + csrScatter above (the O(nMotors) serial passes that
    // dominate the dense-gliding step once the binding bottleneck is parallelized). Same body-chunked
    // counting-sort as SpatialGrid.gridChunk* but keyed by boundSeg over motors (key space = nSeg).
    // Produces a CSR BIT-IDENTICAL to the serial csrHistogram+csrScatter (motors in index order within
    // each segment), so segGather is unaffected. ADDITIVE — the serial csr* are byte-unchanged for the
    // ~10 other harnesses that reuse them VERBATIM; only DenseGliding wires these. The csrScan (over
    // nSeg, single-thread) is REUSED between count and scatter as before.
    //
    // csrChunkParams (int): [0]=motorChunkSize  [1]=numMotorChunks
    // csrMatrix (int):      numMotorChunks × nSeg  (per-chunk private seg-count rows / scatter cursor)

    /** Zero the segmented per-(chunk,seg) count matrix (parallel over all entries). */
    public static void csrChunkZero(IntArray csrChunkParams, IntArray counts, IntArray csrMatrix) {
        int nSeg = counts.get(3);
        int numChunks = csrChunkParams.get(1);
        int total = numChunks * nSeg;
        for (@Parallel int e = 0; e < total; e++) csrMatrix.set(e, 0);
    }

    /** Segmented histogram: each motor-chunk counts its bound motors' boundSeg into its OWN row. */
    public static void csrChunkHistogram(IntArray boundSeg, IntArray counts,
                                         IntArray csrChunkParams, IntArray csrMatrix) {
        int nSeg = counts.get(3);
        int nM   = counts.get(0);
        int chunkSize = csrChunkParams.get(0);
        int numChunks = csrChunkParams.get(1);
        for (@Parallel int mc = 0; mc < numChunks; mc++) {
            int start = mc * chunkSize;
            int end   = start + chunkSize;
            if (end > nM) end = nM;
            int rowBase = mc * nSeg;
            for (int m = start; m < end; m++) {
                int s = boundSeg.get(m);
                if (s >= 0) { int idx = rowBase + s; csrMatrix.set(idx, csrMatrix.get(idx) + 1); }
            }
        }
    }

    /** Per-seg merge: segMotorCount[s] = Σ chunks row[s]; overwrite each row[s] with its exclusive
     *  column-prefix (the chunk's base within seg s). Parallel over segs (disjoint columns). */
    public static void csrChunkReduce(IntArray counts, IntArray csrChunkParams,
                                      IntArray csrMatrix, IntArray segMotorCount) {
        int nSeg = counts.get(3);
        int numChunks = csrChunkParams.get(1);
        for (@Parallel int s = 0; s < nSeg; s++) {
            int acc = 0;
            for (int mc = 0; mc < numChunks; mc++) {
                int idx = mc * nSeg + s;
                int v = csrMatrix.get(idx);
                csrMatrix.set(idx, acc);
                acc += v;
            }
            segMotorCount.set(s, acc);
        }
    }

    /** Counting-sort scatter: each motor-chunk places its motors (index order) at
     *  segMotorOffsets[s] + row[s]++ (private per-(chunk,seg) cursor). Stable ⇒ bit-identical to serial. */
    public static void csrChunkScatter(IntArray boundSeg, IntArray counts, IntArray csrChunkParams,
                                       IntArray segMotorOffsets, IntArray segMotorMyo, IntArray csrMatrix) {
        int nSeg = counts.get(3);
        int nM   = counts.get(0);
        int chunkSize = csrChunkParams.get(0);
        int numChunks = csrChunkParams.get(1);
        for (@Parallel int mc = 0; mc < numChunks; mc++) {
            int start = mc * chunkSize;
            int end   = start + chunkSize;
            if (end > nM) end = nM;
            int rowBase = mc * nSeg;
            for (int m = start; m < end; m++) {
                int s = boundSeg.get(m);
                if (s < 0) continue;
                int idx = rowBase + s;
                int pos = segMotorOffsets.get(s) + csrMatrix.get(idx);
                segMotorMyo.set(pos, m);
                csrMatrix.set(idx, csrMatrix.get(idx) + 1);
            }
        }
    }

    /** Segment-side GATHER: each segment sums its bound motors' seg-side reactions into its own
     *  forceSum/torqueSum (+=). Race-free (segment writes self), no atomics. */
    public static void segGather(IntArray segMotorOffsets, IntArray segMotorMyo, FloatArray bondData,
                                 FloatArray filForceSum, FloatArray filTorqueSum, IntArray counts) {
        int nSeg = counts.get(3);
        for (@Parallel int s = 0; s < nSeg; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            int start = segMotorOffsets.get(s), end = segMotorOffsets.get(s + 1);
            for (int k = start; k < end; k++) {
                int d = segMotorMyo.get(k) * STRIDE;
                fx += bondData.get(d + 6); fy += bondData.get(d + 7); fz += bondData.get(d + 8);
                tx += bondData.get(d + 9); ty += bondData.get(d + 10); tz += bondData.get(d + 11);
            }
            filForceSum.set(s,           (float) (filForceSum.get(s)            + fx));
            filForceSum.set(nSeg + s,    (float) (filForceSum.get(nSeg + s)     + fy));
            filForceSum.set(2 * nSeg + s,(float) (filForceSum.get(2 * nSeg + s) + fz));
            filTorqueSum.set(s,           (float) (filTorqueSum.get(s)            + tx));
            filTorqueSum.set(nSeg + s,    (float) (filTorqueSum.get(nSeg + s)     + ty));
            filTorqueSum.set(2 * nSeg + s,(float) (filTorqueSum.get(2 * nSeg + s) + tz));
        }
    }

    /** O(nMotors·nSeg) brute-force reference: each segment sums over ALL motors with boundSeg==s. */
    public static void bruteGather(IntArray boundSeg, FloatArray bondData,
                                   FloatArray bForceSum, FloatArray bTorqueSum, IntArray counts) {
        int nSeg = counts.get(3), nM = counts.get(0);
        for (@Parallel int s = 0; s < nSeg; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            for (int m = 0; m < nM; m++) {
                if (boundSeg.get(m) != s) continue;
                int d = m * STRIDE;
                fx += bondData.get(d + 6); fy += bondData.get(d + 7); fz += bondData.get(d + 8);
                tx += bondData.get(d + 9); ty += bondData.get(d + 10); tz += bondData.get(d + 11);
            }
            bForceSum.set(s, (float) fx); bForceSum.set(nSeg + s, (float) fy); bForceSum.set(2 * nSeg + s, (float) fz);
            bTorqueSum.set(s, (float) tx); bTorqueSum.set(nSeg + s, (float) ty); bTorqueSum.set(2 * nSeg + s, (float) tz);
        }
    }
}
