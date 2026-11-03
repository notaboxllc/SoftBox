package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * MOTOR–ACTIN EXCLUDED VOLUME + HEAD/LEVER FLOOR (2026-10-18, jba: "build the sterics and the head floor").
 *
 * <p><b>Why.</b> Before this, the only motor–filament forces were the bound head's bond and stroke; unbound heads,
 * levers and S2s passed straight through actin, and the glass floor acted on the S2 nodes only (~11 % of unbound heads
 * sat below the glass). An offline estimate from the model's own unbound conformations
 * (RUN_LOGS/motor_audit/steric_estimate/) put the excluded-volume cost of the lawn at ~10 kT (d400) / ~40 kT (d1600)
 * at the pilots' emergent filament height, i.e. a missing ~2.6 / ~10 pN upward push.
 *
 * <p><b>Bodies (radii are structural, not fitted):</b> actin = a capsule of radius {@code rA} along each filament
 * segment; S2 elements = capsules of radius {@code rS} between beam nodes; lever = a capsule of radius {@code rL} from
 * the S2 tip P to the converter point C; head = a sphere of radius {@code rH} at the head centre. Contact law
 * U = 1/2 k delta^2 on overlap (delta = rSum - distance), the SAME one-sided harmonic the S2 floor already uses, and
 * like it, it is assembled INSIDE the implicit S2 solve with its Gauss–Newton Hessian (an explicit contact is not
 * stable at production dt: the S2 node drag is ~1e-3 pN/nm per step). Frictionless: the force is along the normal
 * between the closest points, so it puts no roll torque on the filament axis.
 *
 * <p><b>Data path (restC rows; restC is a planar per-motor DoubleArray that the solver already reads):</b>
 * <pre>
 *   9..15  slot 0 actin segment: centre (3, um), axis (3), half-length (um; 0 = no slot)
 *   16..22 slot 1 (the second-nearest segment, so joints are covered)
 *   23     slot whose segment == boundSeg (head contact skipped there: the dock already sets that geometry), else -1
 *   24..27 rA+rH, rA+rL, rA+rS (um), k (N/m)          — 0 k => no motor–actin sterics
 *   28, 29 head floor radius, lever floor radius (um) — 0 => that floor is off
 *   30..41 OUTPUT reaction on the filament: slot0 F(3,N) T(3,N m), slot1 F T   (written by the solve)
 *   42     OUTPUT contact energy (J)
 *   43, 44 OUTPUT torque on the head azimuth about eup through P (N m) and its stiffness (N m / rad), consumed by
 *          {@link TwoBodyBeamAnalyticGpu#matHeadDock} on the next step (the junction azimuth is not a solver DOF)
 * </pre>
 * The filament reaction is applied on the NEXT step's force phase (the solve runs last in the step; the same
 * one-step staleness every other motor reaction has). The reaction is the exact negative of the force the solve
 * applied, at the actin closest point — every contact force is applied once to each side.
 *
 * <p>Default OFF: restC keeps its 9-row size and the solve's steric block is never entered.
 */
public final class MotorStericSystem {
    private MotorStericSystem() {}

    public static final int ROWS = 45;
    /** restC rows with sterics on: 0..44 as above, 45 solve mode, 46 forced mask, 47 start mask, 48.. trial deltas. */
    public static int rows(int M) { return 51 + 3 * M; }

    /**
     * Per motor: find the two filament segments nearest the motor (capsule distance from the midpoint of S2 base and
     * head centre) within {@code cutoff}, and write their geometry + the contact constants into restC; zero the
     * output rows. stericP = {rA, rH, rL, rS, k(N/m), headFloorR, leverFloorR, cutoff} (um).
     */
    public static void stericPrep(DoubleArray nodes, DoubleArray outGeom, IntArray boundSeg, IntArray active,
                                  FloatArray fCoord, FloatArray fUVec, FloatArray fSegLen, DoubleArray restC,
                                  DoubleArray stericP, IntArray stericKey, IntArray counts) {
        int nM = counts.get(0), M = counts.get(2), nSeg = counts.get(3);
        double rA = stericP.get(0), rH = stericP.get(1), rL = stericP.get(2), rS = stericP.get(3), k = stericP.get(4);
        double hfR = stericP.get(5), lfR = stericP.get(6), cut = stericP.get(7);
        for (@Parallel int m = 0; m < nM; m++) {
            int nRows = restC.getSize() / nM;
            for (int r = 9; r < nRows; r++) restC.set(r * nM + m, 0.0);
            restC.set(23 * nM + m, -1.0);
            restC.set(24 * nM + m, rA + rH); restC.set(25 * nM + m, rA + rL); restC.set(26 * nM + m, rA + rS);
            restC.set(27 * nM + m, k); restC.set(28 * nM + m, hfR); restC.set(29 * nM + m, lfR);
            stericKey.set(m, -1); stericKey.set(nM + m, -1);
            if (active.get(m) != 1 || k <= 0.0) continue;
            double px = 0.5 * (nodes.get(m) + outGeom.get(3 * nM + m));
            double py = 0.5 * (nodes.get(nM + m) + outGeom.get(4 * nM + m));
            double pz = 0.5 * (nodes.get(2 * nM + m) + outGeom.get(5 * nM + m));
            int s0 = -1, s1 = -1; double d0 = 1e30, d1 = 1e30;
            for (int s = 0; s < nSeg; s++) {
                double cx = fCoord.get(s), cy = fCoord.get(nSeg + s), cz = fCoord.get(2 * nSeg + s);
                double ux = fUVec.get(s), uy = fUVec.get(nSeg + s), uz = fUVec.get(2 * nSeg + s);
                double h = 0.5 * fSegLen.get(s);
                double t = (px - cx) * ux + (py - cy) * uy + (pz - cz) * uz;
                if (t > h) t = h; if (t < -h) t = -h;
                double dx = px - cx - t * ux, dy = py - cy - t * uy, dz = pz - cz - t * uz;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < d0) { d1 = d0; s1 = s0; d0 = d; s0 = s; }
                else if (d < d1) { d1 = d; s1 = s; }
            }
            double c2 = cut * cut;
            int bs = boundSeg.get(m);
            for (int slot = 0; slot < 2; slot++) {
                int s = slot == 0 ? s0 : s1; double d = slot == 0 ? d0 : d1;
                if (s < 0 || d > c2) continue;
                int r0 = 9 + 7 * slot;
                restC.set(r0 * nM + m, fCoord.get(s)); restC.set((r0 + 1) * nM + m, fCoord.get(nSeg + s));
                restC.set((r0 + 2) * nM + m, fCoord.get(2 * nSeg + s));
                restC.set((r0 + 3) * nM + m, fUVec.get(s)); restC.set((r0 + 4) * nM + m, fUVec.get(nSeg + s));
                restC.set((r0 + 5) * nM + m, fUVec.get(2 * nSeg + s));
                restC.set((r0 + 6) * nM + m, 0.5 * fSegLen.get(s));
                stericKey.set(slot * nM + m, s);
                if (s == bs) restC.set(23 * nM + m, slot);
            }
        }
    }

    /** Segment-side sum of the per-motor steric reactions (both slots). O(nSeg x nM); fine at gliding scale. */
    public static void stericGather(IntArray stericKey, DoubleArray restC, FloatArray fForceSum, FloatArray fTorqueSum,
                                    IntArray counts) {
        int nM = counts.get(0), nSeg = counts.get(3);
        for (@Parallel int s = 0; s < nSeg; s++) {
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            for (int m = 0; m < nM; m++) {
                for (int slot = 0; slot < 2; slot++) {
                    if (stericKey.get(slot * nM + m) != s) continue;
                    int r0 = 30 + 6 * slot;
                    fx += restC.get(r0 * nM + m);       fy += restC.get((r0 + 1) * nM + m); fz += restC.get((r0 + 2) * nM + m);
                    tx += restC.get((r0 + 3) * nM + m); ty += restC.get((r0 + 4) * nM + m); tz += restC.get((r0 + 5) * nM + m);
                }
            }
            fForceSum.set(s,            (float) (fForceSum.get(s) + fx));
            fForceSum.set(nSeg + s,     (float) (fForceSum.get(nSeg + s) + fy));
            fForceSum.set(2 * nSeg + s, (float) (fForceSum.get(2 * nSeg + s) + fz));
            fTorqueSum.set(s,            (float) (fTorqueSum.get(s) + tx));
            fTorqueSum.set(nSeg + s,     (float) (fTorqueSum.get(nSeg + s) + ty));
            fTorqueSum.set(2 * nSeg + s, (float) (fTorqueSum.get(2 * nSeg + s) + tz));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    //  The contact geometry of one motor at its CURRENT pose (nodes + outGeom), shared by check and eval.
    //  bit = slot*(M+2) + body (body 0..M-1 S2 elements, M lever, M+1 head); floors = 2(M+2) + {0 head, 1 lever}.
    // ------------------------------------------------------------------------------------------------------------

    /**
     * After the TRIAL solve: if a contact penetrates now that was not present at the start of the step, undo this
     * motor's trial step exactly (the solve recorded it) and mark it for the redo (mode 2, forced = the new contacts);
     * otherwise mark it done (mode 1).
     */
    public static void stericCheck(DoubleArray nodes, DoubleArray outGeom, DoubleArray q, DoubleArray chiHead,
                                   DoubleArray frame, DoubleArray params, DoubleArray restC, IntArray counts) {
        int nM = counts.get(0), M = counts.get(2);
        for (@Parallel int m = 0; m < nM; m++) {
            if (restC.get(45 * nM + m) != 0.0) continue;
            if (restC.get(8 * nM + m) < 0.0) { restC.set(45 * nM + m, 1.0); continue; }   // culled: not solved
            int startMask = (int) restC.get(47 * nM + m), newMask = 0;
            double kSt = restC.get(27 * nM + m), rSH = restC.get(24 * nM + m), rSL = restC.get(25 * nM + m), rSS = restC.get(26 * nM + m);
            int skipSlot = (int) restC.get(23 * nM + m);
            double Px = nodes.get((3 * M) * nM + m), Py = nodes.get((3 * M + 1) * nM + m), Pz = nodes.get((3 * M + 2) * nM + m);
            double Cx = outGeom.get(m), Cy = outGeom.get(nM + m), Cz = outGeom.get(2 * nM + m);
            double Hx = outGeom.get(3 * nM + m), Hy = outGeom.get(4 * nM + m), Hz = outGeom.get(5 * nM + m);
            for (int slot = 0; slot < 2; slot++) {
                int r0 = 9 + 7 * slot;
                double hA = restC.get((r0 + 6) * nM + m);
                if (!(hA > 0.0) || !(kSt > 0.0)) continue;
                double ux = restC.get((r0 + 3) * nM + m), uy = restC.get((r0 + 4) * nM + m), uz = restC.get((r0 + 5) * nM + m);
                double a0x = restC.get(r0 * nM + m) - hA * ux, a0y = restC.get((r0 + 1) * nM + m) - hA * uy, a0z = restC.get((r0 + 2) * nM + m) - hA * uz;
                double d2x = 2.0 * hA * ux, d2y = 2.0 * hA * uy, d2z = 2.0 * hA * uz, eA = d2x * d2x + d2y * d2y + d2z * d2z;
                for (int bI = 0; bI <= M + 1; bI++) {
                    if (bI == M + 1 && slot == skipSlot) continue;
                    double p0x, p0y, p0z, p1x, p1y, p1z, rSum;
                    if (bI < M) {
                        p0x = nodes.get((3 * bI) * nM + m); p0y = nodes.get((3 * bI + 1) * nM + m); p0z = nodes.get((3 * bI + 2) * nM + m);
                        p1x = nodes.get((3 * (bI + 1)) * nM + m); p1y = nodes.get((3 * (bI + 1) + 1) * nM + m); p1z = nodes.get((3 * (bI + 1) + 2) * nM + m);
                        rSum = rSS;
                    } else if (bI == M) { p0x = Px; p0y = Py; p0z = Pz; p1x = Cx; p1y = Cy; p1z = Cz; rSum = rSL; }
                    else { p0x = Hx; p0y = Hy; p0z = Hz; p1x = Hx; p1y = Hy; p1z = Hz; rSum = rSH; }
                    double d1x = p1x - p0x, d1y = p1y - p0y, d1z = p1z - p0z;
                    double wx = p0x - a0x, wy = p0y - a0y, wz = p0z - a0z;
                    double aa = d1x * d1x + d1y * d1y + d1z * d1z, bb = d1x * d2x + d1y * d2y + d1z * d2z;
                    double cc = d1x * wx + d1y * wy + d1z * wz, ff = d2x * wx + d2y * wy + d2z * wz;
                    double sb = 0.0, ta;
                    if (aa > 1e-24) {
                        double den = aa * eA - bb * bb;
                        if (den > 1e-30) { sb = (bb * ff - cc * eA) / den; if (sb < 0) sb = 0; if (sb > 1) sb = 1; }
                        ta = (bb * sb + ff) / eA;
                        if (ta < 0) { ta = 0; sb = -cc / aa; if (sb < 0) sb = 0; if (sb > 1) sb = 1; }
                        else if (ta > 1) { ta = 1; sb = (bb - cc) / aa; if (sb < 0) sb = 0; if (sb > 1) sb = 1; }
                    } else { ta = ff / eA; if (ta < 0) ta = 0; if (ta > 1) ta = 1; }
                    double nx = p0x + sb * d1x - a0x - ta * d2x, ny = p0y + sb * d1y - a0y - ta * d2y, nz = p0z + sb * d1z - a0z - ta * d2z;
                    double dist = Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (dist < 1e-12) continue;
                    int cbit = 1 << (slot * (M + 2) + bI);
                    if (rSum - dist > 0.0 && (startMask & cbit) == 0) newMask |= cbit;
                }
            }
            double ex = frame.get(6 * nM + m), ey = frame.get(7 * nM + m), ez = frame.get(8 * nM + m), floorZ = params.get(14 * nM + m);
            for (int fI = 0; fI < 2; fI++) {
                double rF = restC.get((28 + fI) * nM + m);
                if (!(rF > 0.0)) continue;
                double zz = (fI == 0) ? (Hx * ex + Hy * ey + Hz * ez) : (Cx * ex + Cy * ey + Cz * ez);
                int cbit = 1 << (2 * (M + 2) + fI);
                if (floorZ - (zz - rF) > 0.0 && (startMask & cbit) == 0) newMask |= cbit;
            }
            if (newMask != 0) {
                for (int j = 1; j <= M; j++) for (int k = 0; k < 3; k++)
                    nodes.set((3 * j + k) * nM + m, nodes.get((3 * j + k) * nM + m) - restC.get((48 + 3 * (j - 1) + k) * nM + m));
                q.set(m, q.get(m) - restC.get((48 + 3 * M) * nM + m));
                q.set(nM + m, q.get(nM + m) - restC.get((49 + 3 * M) * nM + m));
                chiHead.set(m, chiHead.get(m) - restC.get((50 + 3 * M) * nM + m));
                restC.set(45 * nM + m, 2.0); restC.set(46 * nM + m, (double) newMask);
            } else restC.set(45 * nM + m, 1.0);
        }
    }

    /**
     * After the final solve: the contact forces at the END-of-step pose. Writes the reaction on each actin slot (applied
     * to the filament on the next step's force phase), the contact energy, and the torque on the junction azimuth about
     * eup through the S2 tip P (consumed by matHeadDock next step).
     */
    public static void stericEval(DoubleArray nodes, DoubleArray outGeom, DoubleArray frame, DoubleArray restC, IntArray counts) {
        int nM = counts.get(0), M = counts.get(2);
        for (@Parallel int m = 0; m < nM; m++) {
            for (int r = 30; r < 45; r++) restC.set(r * nM + m, 0.0);
            if (restC.get(8 * nM + m) < 0.0) continue;
            double kSt = restC.get(27 * nM + m), rSH = restC.get(24 * nM + m), rSL = restC.get(25 * nM + m), rSS = restC.get(26 * nM + m);
            int skipSlot = (int) restC.get(23 * nM + m);
            double ex = frame.get(6 * nM + m), ey = frame.get(7 * nM + m), ez = frame.get(8 * nM + m);
            double Px = nodes.get((3 * M) * nM + m), Py = nodes.get((3 * M + 1) * nM + m), Pz = nodes.get((3 * M + 2) * nM + m);
            double Cx = outGeom.get(m), Cy = outGeom.get(nM + m), Cz = outGeom.get(2 * nM + m);
            double Hx = outGeom.get(3 * nM + m), Hy = outGeom.get(4 * nM + m), Hz = outGeom.get(5 * nM + m);
            double eSt = 0.0, tAz = 0.0, kAz = 0.0;
            for (int slot = 0; slot < 2; slot++) {
                int r0 = 9 + 7 * slot;
                double hA = restC.get((r0 + 6) * nM + m);
                if (!(hA > 0.0) || !(kSt > 0.0)) continue;
                double acx = restC.get(r0 * nM + m), acy = restC.get((r0 + 1) * nM + m), acz = restC.get((r0 + 2) * nM + m);
                double ux = restC.get((r0 + 3) * nM + m), uy = restC.get((r0 + 4) * nM + m), uz = restC.get((r0 + 5) * nM + m);
                double a0x = acx - hA * ux, a0y = acy - hA * uy, a0z = acz - hA * uz;
                double d2x = 2.0 * hA * ux, d2y = 2.0 * hA * uy, d2z = 2.0 * hA * uz, eA = d2x * d2x + d2y * d2y + d2z * d2z;
                double sFx = 0, sFy = 0, sFz = 0, sTx = 0, sTy = 0, sTz = 0;
                for (int bI = 0; bI <= M + 1; bI++) {
                    if (bI == M + 1 && slot == skipSlot) continue;
                    double p0x, p0y, p0z, p1x, p1y, p1z, rSum;
                    if (bI < M) {
                        p0x = nodes.get((3 * bI) * nM + m); p0y = nodes.get((3 * bI + 1) * nM + m); p0z = nodes.get((3 * bI + 2) * nM + m);
                        p1x = nodes.get((3 * (bI + 1)) * nM + m); p1y = nodes.get((3 * (bI + 1) + 1) * nM + m); p1z = nodes.get((3 * (bI + 1) + 2) * nM + m);
                        rSum = rSS;
                    } else if (bI == M) { p0x = Px; p0y = Py; p0z = Pz; p1x = Cx; p1y = Cy; p1z = Cz; rSum = rSL; }
                    else { p0x = Hx; p0y = Hy; p0z = Hz; p1x = Hx; p1y = Hy; p1z = Hz; rSum = rSH; }
                    double d1x = p1x - p0x, d1y = p1y - p0y, d1z = p1z - p0z;
                    double wx = p0x - a0x, wy = p0y - a0y, wz = p0z - a0z;
                    double aa = d1x * d1x + d1y * d1y + d1z * d1z, bb = d1x * d2x + d1y * d2y + d1z * d2z;
                    double cc = d1x * wx + d1y * wy + d1z * wz, ff = d2x * wx + d2y * wy + d2z * wz;
                    double sb = 0.0, ta;
                    if (aa > 1e-24) {
                        double den = aa * eA - bb * bb;
                        if (den > 1e-30) { sb = (bb * ff - cc * eA) / den; if (sb < 0) sb = 0; if (sb > 1) sb = 1; }
                        ta = (bb * sb + ff) / eA;
                        if (ta < 0) { ta = 0; sb = -cc / aa; if (sb < 0) sb = 0; if (sb > 1) sb = 1; }
                        else if (ta > 1) { ta = 1; sb = (bb - cc) / aa; if (sb < 0) sb = 0; if (sb > 1) sb = 1; }
                    } else { ta = ff / eA; if (ta < 0) ta = 0; if (ta > 1) ta = 1; }
                    double xbx = p0x + sb * d1x, xby = p0y + sb * d1y, xbz = p0z + sb * d1z;
                    double xax = a0x + ta * d2x, xay = a0y + ta * d2y, xaz = a0z + ta * d2z;
                    double nx = xbx - xax, ny = xby - xay, nz = xbz - xaz;
                    double dist = Math.sqrt(nx * nx + ny * ny + nz * nz);
                    double del = rSum - dist;
                    if (!(del > 0.0) || dist < 1e-12) continue;
                    double fm = kSt * del * 1e-6 / dist, Fx = fm * nx, Fy = fm * ny, Fz = fm * nz;   // force ON THE MOTOR
                    eSt += 0.5 * kSt * del * del * 1e-12;
                    if (bI >= M) {
                        double rx = xbx - Px, ry = xby - Py, rz = xbz - Pz;
                        tAz += (ex * (ry * Fz - rz * Fy) + ey * (rz * Fx - rx * Fz) + ez * (rx * Fy - ry * Fx)) * 1e-6;
                        double cxp = ey * rz - ez * ry, cyp = ez * rx - ex * rz, czp = ex * ry - ey * rx;
                        kAz += kSt * (cxp * cxp + cyp * cyp + czp * czp) * 1e-12;
                    }
                    double qx = xax - acx, qy = xay - acy, qz = xaz - acz;
                    sFx -= Fx; sFy -= Fy; sFz -= Fz;
                    sTx -= (qy * Fz - qz * Fy) * 1e-6; sTy -= (qz * Fx - qx * Fz) * 1e-6; sTz -= (qx * Fy - qy * Fx) * 1e-6;
                }
                int o0 = 30 + 6 * slot;
                restC.set(o0 * nM + m, sFx); restC.set((o0 + 1) * nM + m, sFy); restC.set((o0 + 2) * nM + m, sFz);
                restC.set((o0 + 3) * nM + m, sTx); restC.set((o0 + 4) * nM + m, sTy); restC.set((o0 + 5) * nM + m, sTz);
            }
            restC.set(42 * nM + m, eSt); restC.set(43 * nM + m, tAz); restC.set(44 * nM + m, kAz);
        }
    }
}
