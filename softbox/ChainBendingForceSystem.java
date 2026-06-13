package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * System (increment 2a): ChainBendingForceSystem (device kernel) — the PAIRS chain
 * link spring (F3) + bending/torsion (F4) that makes a chain of FilSegments behave
 * like a semiflexible filament.
 *
 * Direct port of v1's DEVICE chain kernel `GPUMoveThing.chainPairForcesKernel`
 * (~/Code/BoA-v1ref/boxOfActin/GPUMoveThing.java:1551-1896) — the residency-shaped
 * reference. NOT the CPU visited-flag version (FilSegment.addLinkForces /
 * addTorsionSpringForces): the device kernel is already a per-segment, self-write,
 * read-only-neighbor, NO-atomic kernel. Ownership: each joint is computed from BOTH
 * segments' perspectives; the "owner" (lower slot index) defines the canonical
 * link direction so the two perspectives are exactly anti-parallel (Newton's 3rd
 * law), and each segment applies +F (if owner) or -F (if not) to ITS OWN slot only.
 *
 * Side decode (the A1 trap — verified against v1 FilSegment.setEnd*Links:2818-2832):
 *   end?NbrSide == 0  -> my end is glued to the neighbor's END1 (tip = ncoord - L/2*nu)
 *   end?NbrSide == 1  -> my end is glued to the neighbor's END2 (tip = ncoord + L/2*nu)
 * Sentinel slot -1 = chain end (that side contributes nothing).
 *
 * Forces are LAB-frame (linkUVec/torsionVec from lab positions/orientations) and are
 * accumulated (+=) into forceSum/torqueSum, which the integration system transforms
 * lab->body. Internals use double (as v1 does) for the strain/angle cancellation;
 * pose read as float, forceSum written as float.
 *
 * chainParams: [0]=dt [1]=fracMove [2]=fracR [3]=fracMoveTorq
 *              [4]=filTorqSpringActive(0/1) [5]=filTorqSpring [6]=actinMonoRadius
 */
public final class ChainBendingForceSystem {
    private ChainBendingForceSystem() {}

    /** Zero the deterministic accumulators before the force systems fill them. */
    public static void zeroAccumulators(FloatArray forceSum, FloatArray torqueSum, IntArray counts) {
        int N = forceSum.getSize() / 3;
        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i, iz = 2 * N + i;
            forceSum.set(i, 0f);  forceSum.set(iy, 0f);  forceSum.set(iz, 0f);
            torqueSum.set(i, 0f); torqueSum.set(iy, 0f); torqueSum.set(iz, 0f);
        }
    }

    // Device-safe acos, VERBATIM v1 GPUMoveThing.accurateAcos:1128-1158.
    private static double accurateAcos(double x) {
        if (x > 1.0)  x = 1.0;
        if (x < -1.0) x = -1.0;
        double y;
        if (x > 0.95) {
            double t = 1.0 - x;
            if (t < 0.0) t = 0.0;
            y = Math.sqrt(2.0 * t);
        } else if (x < -0.95) {
            double t = 1.0 + x;
            if (t < 0.0) t = 0.0;
            y = 3.141592653589793 - Math.sqrt(2.0 * t);
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

    public static void chainForces(
            FloatArray coord,
            FloatArray uVec,
            FloatArray segLength,
            IntArray   end2NbrSlot,
            IntArray   end2NbrSide,
            IntArray   end1NbrSlot,
            IntArray   end1NbrSide,
            FloatArray bTransGam,
            FloatArray bRotGam,
            FloatArray forceSum,
            FloatArray torqueSum,
            FloatArray chainParams,
            IntArray   counts) {

        int N = coord.getSize() / 3;

        double dt                  = (double) chainParams.get(0);
        double fracMove            = (double) chainParams.get(1);
        double fracR               = (double) chainParams.get(2);
        double fracMoveTorq        = (double) chainParams.get(3);
        double filTorqSpringActive = (double) chainParams.get(4);
        double filTorqSpring       = (double) chainParams.get(5);
        double actinMonoRadius     = (double) chainParams.get(6);

        double DEG2RAD = Math.PI / 180.0;
        double RAD2DEG = 180.0 / Math.PI;

        for (@Parallel int i = 0; i < N; i++) {
            int iy = N + i, iz = 2 * N + i;

            double cx = (double) coord.get(i), cy = (double) coord.get(iy), cz = (double) coord.get(iz);
            double ux = (double) uVec.get(i),  uy = (double) uVec.get(iy),  uz = (double) uVec.get(iz);
            double len = (double) segLength.get(i);
            double halfLen_um = 0.5 * len;
            double lSqSelf = 1.0e-12 * len * len;

            double rBTGx = (double) bTransGam.get(i);
            double rBTGy = (double) bTransGam.get(iy);
            double rBRGy = (double) bRotGam.get(iy);

            double fx = 0.0, fy = 0.0, fz = 0.0;
            double tx = 0.0, ty = 0.0, tz = 0.0;

            int e2Slot = end2NbrSlot.get(i);
            int e1Slot = end1NbrSlot.get(i);

            // ---------------- end2 side ----------------
            if (e2Slot >= 0) {
                int e2Side = end2NbrSide.get(i);
                int jx = e2Slot, jy = N + e2Slot, jz = 2 * N + e2Slot;
                double ncx = (double) coord.get(jx), ncy = (double) coord.get(jy), ncz = (double) coord.get(jz);
                double nux = (double) uVec.get(jx),  nuy = (double) uVec.get(jy),  nuz = (double) uVec.get(jz);
                double nlen = (double) segLength.get(e2Slot);
                double nHalfLen_um = 0.5 * nlen;
                double lSqN  = 1.0e-12 * nlen * nlen;
                double nBTGx = (double) bTransGam.get(jx);
                double nBTGy = (double) bTransGam.get(jy);
                double nBRGy = (double) bRotGam.get(jy);

                double e2x = cx + halfLen_um * ux, e2y = cy + halfLen_um * uy, e2z = cz + halfLen_um * uz;
                double selfLpx = e2x - actinMonoRadius * ux;
                double selfLpy = e2y - actinMonoRadius * uy;
                double selfLpz = e2z - actinMonoRadius * uz;
                double nbrTipx, nbrTipy, nbrTipz;
                if (e2Side == 0) {
                    nbrTipx = ncx - nHalfLen_um * nux; nbrTipy = ncy - nHalfLen_um * nuy; nbrTipz = ncz - nHalfLen_um * nuz;
                } else {
                    nbrTipx = ncx + nHalfLen_um * nux; nbrTipy = ncy + nHalfLen_um * nuy; nbrTipz = ncz + nHalfLen_um * nuz;
                }
                double nbrLpx, nbrLpy, nbrLpz;
                if (e2Side == 0) {
                    nbrLpx = nbrTipx + actinMonoRadius * nux; nbrLpy = nbrTipy + actinMonoRadius * nuy; nbrLpz = nbrTipz + actinMonoRadius * nuz;
                } else {
                    nbrLpx = nbrTipx - actinMonoRadius * nux; nbrLpy = nbrTipy - actinMonoRadius * nuy; nbrLpz = nbrTipz - actinMonoRadius * nuz;
                }
                double linkPtX, linkPtY, linkPtZ, ptAtEndX, ptAtEndY, ptAtEndZ;
                if (i < e2Slot) {
                    linkPtX = selfLpx;  linkPtY = selfLpy;  linkPtZ = selfLpz;
                    ptAtEndX = nbrTipx; ptAtEndY = nbrTipy; ptAtEndZ = nbrTipz;
                } else {
                    linkPtX = nbrLpx;   linkPtY = nbrLpy;   linkPtZ = nbrLpz;
                    ptAtEndX = e2x;     ptAtEndY = e2y;     ptAtEndZ = e2z;
                }
                double dx = ptAtEndX - linkPtX, dy = ptAtEndY - linkPtY, dz = ptAtEndZ - linkPtZ;
                double strainDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double invStrain = (strainDist > 0.0) ? (1.0 / strainDist) : 0.0;
                double luX = dx * invStrain, luY = dy * invStrain, luZ = dz * invStrain;

                double cosB1 = ux * luX + uy * luY + uz * luZ;
                if (cosB1 > 1.0) cosB1 = 1.0; if (cosB1 < -1.0) cosB1 = -1.0;
                double cosA1 = Math.sin(accurateAcos(cosB1));
                double cosA1_2 = cosA1 * cosA1;
                double moveC1 = cosB1 * cosB1 / rBTGx + cosA1_2 / rBTGy + lSqSelf * cosA1_2 / (4.0 * rBRGy);

                double cosB2 = nux * luX + nuy * luY + nuz * luZ;
                if (cosB2 > 1.0) cosB2 = 1.0; if (cosB2 < -1.0) cosB2 = -1.0;
                double cosA2 = Math.sin(accurateAcos(cosB2));
                double cosA2_2 = cosA2 * cosA2;
                double moveC2 = cosB2 * cosB2 / nBTGx + cosA2_2 / nBTGy + lSqN * cosA2_2 / (4.0 * nBRGy);

                double denom = dt * (moveC1 + moveC2);
                double forceMag = (denom > 0.0) ? (fracMove * 1.0e-6 * strainDist / denom) : 0.0;
                double fSign = (i < e2Slot) ? 1.0 : -1.0;
                double Fx = fSign * forceMag * luX, Fy = fSign * forceMag * luY, Fz = fSign * forceMag * luZ;
                fx += Fx; fy += Fy; fz += Fz;
                double Rscale = 0.5e-6 * len * fracR;
                double Rx = Rscale * ux, Ry = Rscale * uy, Rz = Rscale * uz;
                tx += Ry * Fz - Rz * Fy; ty += Rz * Fx - Rx * Fz; tz += Rx * Fy - Ry * Fx;

                // F4 torsion at end2: side 0 -> cross(uVec, neighbour.uVec); side 1 -> cross(uVec, -neighbour.uVec)
                double nuxE, nuyE, nuzE;
                if (e2Side == 0) { nuxE = nux; nuyE = nuy; nuzE = nuz; } else { nuxE = -nux; nuyE = -nuy; nuzE = -nuz; }
                double tvx = uy * nuzE - uz * nuyE, tvy = uz * nuxE - ux * nuzE, tvz = ux * nuyE - uy * nuxE;
                double tvMag2 = tvx * tvx + tvy * tvy + tvz * tvz;
                if (tvMag2 > 1.0e-30) {
                    double invMag = 1.0 / Math.sqrt(tvMag2);
                    tvx *= invMag; tvy *= invMag; tvz *= invMag;
                    double dotV = ux * nuxE + uy * nuyE + uz * nuzE;
                    if (dotV > 1.0) dotV = 1.0; if (dotV < -1.0) dotV = -1.0;
                    double angTween = accurateAcos(dotV) * RAD2DEG;
                    double torsionMag;
                    if (filTorqSpringActive > 0.5) {
                        torsionMag = fracMoveTorq * filTorqSpring * angTween;
                    } else {
                        double invBRG = 1.0 / rBRGy + 1.0 / nBRGy;
                        torsionMag = fracMoveTorq * DEG2RAD * angTween / (invBRG * dt);
                    }
                    tx += tvx * torsionMag; ty += tvy * torsionMag; tz += tvz * torsionMag;
                }
            }

            // ---------------- end1 side ----------------
            if (e1Slot >= 0) {
                int e1Side = end1NbrSide.get(i);
                int jx = e1Slot, jy = N + e1Slot, jz = 2 * N + e1Slot;
                double ncx = (double) coord.get(jx), ncy = (double) coord.get(jy), ncz = (double) coord.get(jz);
                double nux = (double) uVec.get(jx),  nuy = (double) uVec.get(jy),  nuz = (double) uVec.get(jz);
                double nlen = (double) segLength.get(e1Slot);
                double nHalfLen_um = 0.5 * nlen;
                double lSqN  = 1.0e-12 * nlen * nlen;
                double nBTGx = (double) bTransGam.get(jx);
                double nBTGy = (double) bTransGam.get(jy);
                double nBRGy = (double) bRotGam.get(jy);

                double e1x = cx - halfLen_um * ux, e1y = cy - halfLen_um * uy, e1z = cz - halfLen_um * uz;
                double selfLpx = e1x + actinMonoRadius * ux;
                double selfLpy = e1y + actinMonoRadius * uy;
                double selfLpz = e1z + actinMonoRadius * uz;
                double nbrTipx, nbrTipy, nbrTipz;
                if (e1Side == 0) {
                    nbrTipx = ncx - nHalfLen_um * nux; nbrTipy = ncy - nHalfLen_um * nuy; nbrTipz = ncz - nHalfLen_um * nuz;
                } else {
                    nbrTipx = ncx + nHalfLen_um * nux; nbrTipy = ncy + nHalfLen_um * nuy; nbrTipz = ncz + nHalfLen_um * nuz;
                }
                double nbrLpx, nbrLpy, nbrLpz;
                if (e1Side == 0) {
                    nbrLpx = nbrTipx + actinMonoRadius * nux; nbrLpy = nbrTipy + actinMonoRadius * nuy; nbrLpz = nbrTipz + actinMonoRadius * nuz;
                } else {
                    nbrLpx = nbrTipx - actinMonoRadius * nux; nbrLpy = nbrTipy - actinMonoRadius * nuy; nbrLpz = nbrTipz - actinMonoRadius * nuz;
                }
                double linkPtX, linkPtY, linkPtZ, ptAtEndX, ptAtEndY, ptAtEndZ;
                if (i < e1Slot) {
                    linkPtX = selfLpx;  linkPtY = selfLpy;  linkPtZ = selfLpz;
                    ptAtEndX = nbrTipx; ptAtEndY = nbrTipy; ptAtEndZ = nbrTipz;
                } else {
                    linkPtX = nbrLpx;   linkPtY = nbrLpy;   linkPtZ = nbrLpz;
                    ptAtEndX = e1x;     ptAtEndY = e1y;     ptAtEndZ = e1z;
                }
                double dx = ptAtEndX - linkPtX, dy = ptAtEndY - linkPtY, dz = ptAtEndZ - linkPtZ;
                double strainDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double invStrain = (strainDist > 0.0) ? (1.0 / strainDist) : 0.0;
                double luX = dx * invStrain, luY = dy * invStrain, luZ = dz * invStrain;

                double cosB1 = ux * luX + uy * luY + uz * luZ;
                if (cosB1 > 1.0) cosB1 = 1.0; if (cosB1 < -1.0) cosB1 = -1.0;
                double cosA1 = Math.sin(accurateAcos(cosB1));
                double cosA1_2 = cosA1 * cosA1;
                double moveC1 = cosB1 * cosB1 / rBTGx + cosA1_2 / rBTGy + lSqSelf * cosA1_2 / (4.0 * rBRGy);

                double cosB2 = nux * luX + nuy * luY + nuz * luZ;
                if (cosB2 > 1.0) cosB2 = 1.0; if (cosB2 < -1.0) cosB2 = -1.0;
                double cosA2 = Math.sin(accurateAcos(cosB2));
                double cosA2_2 = cosA2 * cosA2;
                double moveC2 = cosB2 * cosB2 / nBTGx + cosA2_2 / nBTGy + lSqN * cosA2_2 / (4.0 * nBRGy);

                double denom = dt * (moveC1 + moveC2);
                double forceMag = (denom > 0.0) ? (fracMove * 1.0e-6 * strainDist / denom) : 0.0;
                double fSign = (i < e1Slot) ? 1.0 : -1.0;
                double Fx = fSign * forceMag * luX, Fy = fSign * forceMag * luY, Fz = fSign * forceMag * luZ;
                fx += Fx; fy += Fy; fz += Fz;
                double Rscale = -0.5e-6 * len * fracR;   // lever arm to end1 = -uVec
                double Rx = Rscale * ux, Ry = Rscale * uy, Rz = Rscale * uz;
                tx += Ry * Fz - Rz * Fy; ty += Rz * Fx - Rx * Fz; tz += Rx * Fy - Ry * Fx;

                // F4 torsion at end1: cross(-uVec, +/- neighbour.uVec)
                double nuxE, nuyE, nuzE;
                if (e1Side == 0) { nuxE = nux; nuyE = nuy; nuzE = nuz; } else { nuxE = -nux; nuyE = -nuy; nuzE = -nuz; }
                double mux = -ux, muy = -uy, muz = -uz;
                double tvx = muy * nuzE - muz * nuyE, tvy = muz * nuxE - mux * nuzE, tvz = mux * nuyE - muy * nuxE;
                double tvMag2 = tvx * tvx + tvy * tvy + tvz * tvz;
                if (tvMag2 > 1.0e-30) {
                    double invMag = 1.0 / Math.sqrt(tvMag2);
                    tvx *= invMag; tvy *= invMag; tvz *= invMag;
                    double dotV = mux * nuxE + muy * nuyE + muz * nuzE;
                    if (dotV > 1.0) dotV = 1.0; if (dotV < -1.0) dotV = -1.0;
                    double angTween = accurateAcos(dotV) * RAD2DEG;
                    double torsionMag;
                    if (filTorqSpringActive > 0.5) {
                        torsionMag = fracMoveTorq * filTorqSpring * angTween;
                    } else {
                        double invBRG = 1.0 / rBRGy + 1.0 / nBRGy;
                        torsionMag = fracMoveTorq * DEG2RAD * angTween / (invBRG * dt);
                    }
                    tx += tvx * torsionMag; ty += tvy * torsionMag; tz += tvz * torsionMag;
                }
            }

            // accumulate (+=) into this segment's own lab-frame deterministic sums
            forceSum.set(i,  (float) (forceSum.get(i)  + fx));
            forceSum.set(iy, (float) (forceSum.get(iy) + fy));
            forceSum.set(iz, (float) (forceSum.get(iz) + fz));
            torqueSum.set(i,  (float) (torqueSum.get(i)  + tx));
            torqueSum.set(iy, (float) (torqueSum.get(iy) + ty));
            torqueSum.set(iz, (float) (torqueSum.get(iz) + tz));
        }
    }
}
