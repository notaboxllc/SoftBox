package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Dimer-SPECIFIC system (increment 6a): the inter-motor coupling that holds the two motors of
 * a myosin dimer together. Faithful port of v1 MyosinDimer.enforceParallel/enforceAntiParallel
 * (MyosinDimer.java:163-288):
 *   parallel     : applyRodCouplingEnd1 + applyRodCouplingEnd2 + alignUVecLeversTorque (160°)
 *   antiparallel : applyRodCouplingEnd1End2 + applyRodCouplingEnd2End1
 *
 * Each rod coupling is the SAME damping-limited PAIRS spring as the intra-motor J1/J2 joints
 * (F = fracMove·1e-6·strain/(dt·(moveCa+moveCb)), the moveCoeff anisotropic mobility), applied
 * between motorA's and motorB's RODS instead of within one motor; v1's lever-arm is the FULL
 * half-rod-length 0.5·rodLen·(±uVec) (NO fracR — unlike the joints). The lever coupling is the
 * existing alignment-torque pattern restoring the 160° lever-to-lever angle.
 *
 * NO GATHER (recon §2). One thread per DIMER; each dimer writes ONLY its two motors' rod/lever
 * sub-body slots (forceSum/torqueSum, +=). With the disjoint pairing motorA(d)=2d, motorB(d)=2d+1
 * (DimerStore.pair), no two dimer threads touch the same sub-body ⇒ race-free, no atomics, no
 * KernelContext — the simplest of the myosin-structure couplings. The two rod-coupling calls for
 * the same dimer run SEQUENTIALLY within the one thread, so their get/+= /set on a shared rod
 * slot accumulate correctly.
 *
 * Heads are FREE this increment ⇒ v1's "align only if a head is off-filament" gate is always
 * true, so the lever-align always fires in parallel mode (the gliding gate is a later increment).
 */
public final class DimerCouplingSystem {
    private DimerCouplingSystem() {}

    // Device-safe acos, VERBATIM MotorJointSystem.accurateAcos (v1 GPUMoveThing). v1 uses
    // Pt3D.fastAcos on the CPU + this poly on the device; reuse the device-safe form on both
    // runners so CPU≡GPU is bit-identical (the §5c-ii flag: Math.acos does NOT lower on PTX).
    private static double accurateAcos(double x) {
        if (x > 1.0)  x = 1.0;
        if (x < -1.0) x = -1.0;
        double y;
        if (x > 0.95) {
            double t = 1.0 - x; if (t < 0.0) t = 0.0; y = Math.sqrt(2.0 * t);
        } else if (x < -0.95) {
            double t = 1.0 + x; if (t < 0.0) t = 0.0; y = 3.141592653589793 - Math.sqrt(2.0 * t);
        } else {
            double ax = (x < 0.0) ? -x : x;
            double p = (-0.0187293 * ax + 0.0742610) * ax - 0.2121144;
            p = (p * ax + 1.5707963);
            p = p * Math.sqrt(1.0 - ax);
            y = (x < 0.0) ? (3.141592653589793 - p) : p;
        }
        double s = Math.sin(y);
        if (s > 1.0e-12 || s < -1.0e-12) { y = y + (Math.cos(y) - x) / s; }
        s = Math.sin(y);
        if (s > 1.0e-12 || s < -1.0e-12) { y = y + (Math.cos(y) - x) / s; }
        return y;
    }

    /** v1 moveCoeff (MyoRod.moveCoeff:143-160): effective mobility along the link. VERBATIM
     *  MotorJointSystem.moveC — sign-independent in cosB (squared), so v1's end argument (uVec
     *  vs uVecR) is immaterial; cosA² = 1 - cosB² avoids acos (bit-clean, the inc-2 form). */
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
     * The dimer coupling kernel — one thread per dimer, direct two-slot self-write, no gather.
     * Runs over MotorStore.body arrays (the rod/lever sub-bodies) after the per-motor joints and
     * before the integrator (force-accumulation order is commutative — it only += into the
     * frozen-pose accumulators).
     *
     * The two rod↔rod couplings are an inner 2-iteration loop INLINED in this top-level kernel
     * (not a helper) — TornadoVM caps an inlined CALLEE at 600 nodes, but the @Parallel kernel
     * itself has no such cap; only the small moveC/accurateAcos helpers are inlined. endSel pairs:
     * parallel {(-1,-1),(+1,+1)} (End1,End2); antiparallel {(-1,+1),(+1,-1)} (End1End2,End2End1).
     */
    public static void couple(
            FloatArray coord, FloatArray uVec, FloatArray segLength,
            FloatArray bTransGam, FloatArray bRotGam,
            FloatArray forceSum, FloatArray torqueSum,
            IntArray motorA, IntArray motorB, IntArray parallel,
            FloatArray dimerParams, IntArray boundSeg) {

        int nB = coord.getSize() / 3;
        int nD = motorA.getSize();
        double dt        = dimerParams.get(0);
        double fracMove  = dimerParams.get(1);
        double leverTorq = dimerParams.get(2);
        double leverDeg  = dimerParams.get(3);
        double rodLen    = dimerParams.get(4);

        for (@Parallel int d = 0; d < nD; d++) {
            int mA = motorA.get(d), mB = motorB.get(d);
            int rodA = 3 * mA, rodB = 3 * mB;
            boolean par = parallel.get(d) != 0;

            // ---- two rod↔rod PAIRS springs (inlined; v1 applyRodCouplingEnd1/2 or End1End2/End2End1) ----
            for (int variant = 0; variant < 2; variant++) {
                double endSelA = (variant == 0) ? -1.0 : 1.0;       // end1 then end2 of rodA
                double endSelB = par ? endSelA : -endSelA;          // parallel: same end; antiparallel: opposite

                double aCx = coord.get(rodA), aCy = coord.get(nB + rodA), aCz = coord.get(2 * nB + rodA);
                double aUx = uVec.get(rodA),  aUy = uVec.get(nB + rodA),  aUz = uVec.get(2 * nB + rodA);
                double bCx = coord.get(rodB), bCy = coord.get(nB + rodB), bCz = coord.get(2 * nB + rodB);
                double bUx = uVec.get(rodB),  bUy = uVec.get(nB + rodB),  bUz = uVec.get(2 * nB + rodB);
                double aLen = segLength.get(rodA), bLen = segLength.get(rodB);

                // chosen end points: end = center + endSel·½·len·uVec  (v1 freshEnd1/2AsPt3D)
                double pAx = aCx + endSelA * 0.5 * aLen * aUx, pAy = aCy + endSelA * 0.5 * aLen * aUy, pAz = aCz + endSelA * 0.5 * aLen * aUz;
                double pBx = bCx + endSelB * 0.5 * bLen * bUx, pBy = bCy + endSelB * 0.5 * bLen * bUy, pBz = bCz + endSelB * 0.5 * bLen * bUz;

                // link unit vector A→B (v1 linkUVec1 = unitVec(pB, pA) = (pB - pA)/dist)
                double dx = pBx - pAx, dy = pBy - pAy, dz = pBz - pAz;
                double strain = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (strain > 0.0) {                                  // coincident ⇒ zero spring (forceMag ∝ strain)
                    double inv = 1.0 / strain;
                    double lx = dx * inv, ly = dy * inv, lz = dz * inv;
                    double mcA = moveC(aUx, aUy, aUz, lx, ly, lz, bTransGam.get(rodA), bTransGam.get(nB + rodA), bRotGam.get(nB + rodA), aLen);
                    double mcB = moveC(bUx, bUy, bUz, lx, ly, lz, bTransGam.get(rodB), bTransGam.get(nB + rodB), bRotGam.get(nB + rodB), bLen);
                    double denom = dt * (mcA + mcB);
                    double forceMag = (denom > 0.0) ? (fracMove * 1.0e-6 * strain / denom) : 0.0;

                    // rodA: F = +forceMag·l ; R_A = 0.5e-6·rodLen·endSelA·uVecA ; T_A = R_A × F_A
                    double FAx = forceMag * lx, FAy = forceMag * ly, FAz = forceMag * lz;
                    double RsA = 0.5e-6 * rodLen * endSelA;
                    double TAx = (RsA * aUy) * FAz - (RsA * aUz) * FAy;
                    double TAy = (RsA * aUz) * FAx - (RsA * aUx) * FAz;
                    double TAz = (RsA * aUx) * FAy - (RsA * aUy) * FAx;
                    forceSum.set(rodA,           (float) (forceSum.get(rodA)           + FAx));
                    forceSum.set(nB + rodA,      (float) (forceSum.get(nB + rodA)      + FAy));
                    forceSum.set(2 * nB + rodA,  (float) (forceSum.get(2 * nB + rodA)  + FAz));
                    torqueSum.set(rodA,          (float) (torqueSum.get(rodA)          + TAx));
                    torqueSum.set(nB + rodA,     (float) (torqueSum.get(nB + rodA)     + TAy));
                    torqueSum.set(2 * nB + rodA, (float) (torqueSum.get(2 * nB + rodA) + TAz));

                    // rodB: F = -forceMag·l ; R_B = 0.5e-6·rodLen·endSelB·uVecB ; T_B = R_B × F_B
                    double RsB = 0.5e-6 * rodLen * endSelB;
                    double TBx = (RsB * bUy) * (-FAz) - (RsB * bUz) * (-FAy);
                    double TBy = (RsB * bUz) * (-FAx) - (RsB * bUx) * (-FAz);
                    double TBz = (RsB * bUx) * (-FAy) - (RsB * bUy) * (-FAx);
                    forceSum.set(rodB,           (float) (forceSum.get(rodB)           - FAx));
                    forceSum.set(nB + rodB,      (float) (forceSum.get(nB + rodB)      - FAy));
                    forceSum.set(2 * nB + rodB,  (float) (forceSum.get(2 * nB + rodB)  - FAz));
                    torqueSum.set(rodB,          (float) (torqueSum.get(rodB)          + TBx));
                    torqueSum.set(nB + rodB,     (float) (torqueSum.get(nB + rodB)     + TBy));
                    torqueSum.set(2 * nB + rodB, (float) (torqueSum.get(2 * nB + rodB) + TBz));
                }
            }

            // ---- lever-alignment torque (parallel only; v1 alignUVecLeversTorque, restore 160°) ----
            // BINDING GATE (v1 MyosinDimer.java:276 — enforceParallel): the lever-align fires only if
            // at least one head is OFF filament (!myo1.onFil | !myo2.onFil); when BOTH heads are bound
            // it is SUPPRESSED. onFil ⟺ boundSeg >= 0. (Free-head scenes have boundSeg all FREE_BINDABLE
            // ⇒ bothBound=false ⇒ align always fires, bit-identical to the pre-glide always-on path.)
            boolean bothBound = boundSeg.get(mA) >= 0 && boundSeg.get(mB) >= 0;
            if (par && !bothBound) {
                int leverA = 3 * mA + 1, leverB = 3 * mB + 1;
                double u1x = uVec.get(leverA), u1y = uVec.get(nB + leverA), u1z = uVec.get(2 * nB + leverA);
                double u2x = uVec.get(leverB), u2y = uVec.get(nB + leverB), u2z = uVec.get(2 * nB + leverB);
                double tvx = u1y * u2z - u1z * u2y, tvy = u1z * u2x - u1x * u2z, tvz = u1x * u2y - u1y * u2x;
                double tvm2 = tvx * tvx + tvy * tvy + tvz * tvz;
                if (tvm2 > 1.0e-30) {                                // skip degenerate axis (v1 checkPt3D guard)
                    double im = 1.0 / Math.sqrt(tvm2); tvx *= im; tvy *= im; tvz *= im;
                    double dot = u1x * u2x + u1y * u2y + u1z * u2z;
                    if (dot > 1.0) dot = 1.0; if (dot < -1.0) dot = -1.0;
                    double ang = accurateAcos(dot) * 180.0 / Math.PI;
                    double invBRG = 1.0 / bRotGam.get(nB + leverA) + 1.0 / bRotGam.get(nB + leverB);
                    double torsionMag = leverTorq * (Math.PI / 180.0) * (ang - leverDeg) / (invBRG * dt);
                    torqueSum.set(leverA,          (float) (torqueSum.get(leverA)          + tvx * torsionMag));
                    torqueSum.set(nB + leverA,     (float) (torqueSum.get(nB + leverA)     + tvy * torsionMag));
                    torqueSum.set(2 * nB + leverA, (float) (torqueSum.get(2 * nB + leverA) + tvz * torsionMag));
                    torqueSum.set(leverB,          (float) (torqueSum.get(leverB)          - tvx * torsionMag));
                    torqueSum.set(nB + leverB,     (float) (torqueSum.get(nB + leverB)     - tvy * torsionMag));
                    torqueSum.set(2 * nB + leverB, (float) (torqueSum.get(2 * nB + leverB) - tvz * torsionMag));
                }
            }
        }
    }
}
