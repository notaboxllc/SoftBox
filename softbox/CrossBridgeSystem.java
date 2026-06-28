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
            // F10 yVec alignment torque (rest 0)
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

            // head-side: +F, torque TH - T9 - T10
            bondData.set(d,     (float) Fx);  bondData.set(d + 1, (float) Fy);  bondData.set(d + 2, (float) Fz);
            bondData.set(d + 3, (float) (THx - T9x - T10x));
            bondData.set(d + 4, (float) (THy - T9y - T10y));
            bondData.set(d + 5, (float) (THz - T9z - T10z));
            // seg-side: -F, torque TS + T9 + T10  (-forcebias subtracts a uniform −x bias on the seg side; 0 in production)
            bondData.set(d + 6, (float) (nFx - xbias)); bondData.set(d + 7, (float) nFy); bondData.set(d + 8, (float) nFz);
            bondData.set(d + 9,  (float) (TSx + T9x + T10x));
            bondData.set(d + 10, (float) (TSy + T9y + T10y));
            bondData.set(d + 11, (float) (TSz + T9z + T10z));
            // forceDotFil = Dot(F, seg.uVec) — the along-filament load (motor-side force)
            bondData.set(d + 12, (float) (Fx * sux + Fy * suy + Fz * suz));
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
