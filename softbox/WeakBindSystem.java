package softbox;

import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * TWO-STAGE ACTIN BINDING: weak collision complex -> stereospecific strong bond (2026-10-25, jba).
 * Design + literature: docs/motor/WEAK_BINDING_DESIGN.md. Noncanonical, default OFF, CPU runner only.
 *
 * <p>States per motor: FREE (boundSeg = -1, weakSeg = -1), WEAK (boundSeg = -1, weakSeg >= 0), STRONG
 * (boundSeg >= 0, the existing bound state, unchanged). A WEAK head is UNBOUND for every other system: its
 * orientation target, Brownian policy, kbind gate and chemistry are the unbound ones. The only thing it gains is
 * an isotropic zero-rest spring between its F8 point and the site: +F on the head through bondData[0..2] (which
 * the tilt solver reads when the weak flag is set) and the EXACT reaction -F on the segment, added to the
 * filament accumulators by {@link #gatherWeak} after the strong-bond gather.
 *
 * <ul>
 *   <li>FREE -> WEAK on contact: F8 within d_w of a lattice site, approached from outside (siteGateA with the
 *       orientation and preload conditions relaxed). Non-stereospecific (Thomas-lab EPR; Geeves A-state).</li>
 *   <li>WEAK -> FREE at k_off,w(F) = k0 exp(F x / kT) (Bell; k0 = 4e3 /s, Schoenberg 1988 at physiological ionic
 *       strength; x = 0.75 nm, the Debye length, ASSUMED), immediately if |F8 - site| > r_break (range cutoff,
 *       ASSUMED 2 d_w), or if the head leaves ADP.Pi.</li>
 *   <li>WEAK -> STRONG at k_ws exp(-alpha theta^2/2) exp(-alpha mu^2/2) (alpha = 3.7, Steffen 2001; reused for the
 *       polarity angle mu as an ASSUMPTION; k_ws unpinned), with the existing g4 preload and g3 converter
 *       conditions. theta = head axis vs -n_site; mu = the matHeadDock misalignment (stroke axis vs barbed, in the
 *       floor plane), i.e. exactly the rotation the dock will then relax.</li>
 * </ul>
 */
final class WeakBindSystem {
    static boolean ON = false;
    static double K_W_PN_NM = 2.0;        // weak spring (no measurement): sqrt(kT/k) = 1.4 nm per axis, i.e. held within
                                          // ~1-2 nm (electrostatic contact range); implicit in the solver, so stable
    static double CONTACT_COS = 0.0;      // contact needs head axis within acos(CONTACT_COS) = 90 deg of -n (head body outside actin)
    static double K_OFF0 = 4.0e3;         // 1/s, Schoenberg 1988 (physiological ionic strength)
    static double X_BELL_NM = 0.75;       // Debye length at ~0.17 M (ASSUMED force sensitivity)
    static double D_W_NM = 3.0;           // contact distance = the existing g0
    static double R_BREAK_NM = 6.0;       // range cutoff (ASSUMED 2 d_w)
    static double K_WS = 300.0;           // 1/s weak -> strong attempt rate (unpinned)
    static double ALPHA = 3.7;            // Steffen 2001 angular selectivity, kT/rad^2
    // LOOP-2 TETHER (2026-10-25, jba). The weak complex is loop 2 (K629/K632) on the actin N-terminus / D24-D25
    // (Klebl et al. 2025 Nature 642; PDB 8R9V primed actomyosin-5). Measured in 8R9V relative to the strong
    // contact centroid (= F8 / the site), in the docked frame: the head-side loop-2 point is 0.63 nm out (toward
    // the head centre) and 1.65 nm toward the pointed end; the actin-side partner (D24-D25 of the pointed-side
    // subunit) is 1.70 nm toward the pointed end. So in the docked pose the two anchors are ~0.8 nm apart, i.e.
    // the tether is relaxed exactly when the stereospecific interface is formed. The tether itself is the
    // ~30-residue disordered part of loop 2 plus the actin N-terminus, modelled as an ideal-chain (entropic,
    // zero-rest) spring: k_w = 3 kT / <R^2>, so k_w = 2 pN/nm <=> RMS reach 2.5 nm (ASSUMED, no measurement).
    static boolean LOOP2 = true;          // false (-weak-f8) = the earlier F8-to-site tether
    static double L2_OUT_NM = 0.63, L2_AX_NM = 1.65, A_AX_NM = 1.70;
    static double L2_SIGN = +1.0;         // sign of the body axis th = e0 x econv that points pointed-ward when docked (checked by DOCK diagnostic)
    static int WEIGHT = 0;                // weak->strong angular weight: 0 none (geometry only: g3, g4, sterics), 1 Steffen facing, 2 facing+theta+mu, 3 theta+mu (docking orientation; facing left emergent)
    static double dockN, dockThU, dockGap;   // diagnostic over strongly bound motor-steps: th . u_barbed, loop-2 anchor gap (nm)

    // per-motor state (host)
    static int[] weakSeg, weakSiteK; static double[] weakArc, weakAzim, weakPh;
    static StringBuilder ANG = null;   // per weak step: t m r_nm facing_deg theta_deg mu_deg g3 g4
    static int DEBUG = 0, DEBUG2 = 0;   // diagnostic prints (entry distance, first weak steps); 0 = silent
    static long nEnter, nBreakRate, nBreakRange, nBreakNuc, nStrong, weakSteps;
    static double[] fSeg;   // per motor: reaction force (N) on the segment + site point (um), for the gather

    static void init(int N) {
        weakSeg = new int[N]; java.util.Arrays.fill(weakSeg, -1);
        weakSiteK = new int[N]; weakArc = new double[N]; weakAzim = new double[N]; weakPh = new double[N];
        fSeg = new double[6 * N];
        nEnter = nBreakRate = nBreakRange = nBreakNuc = nStrong = weakSteps = 0; dockN = dockThU = dockGap = 0;
    }
    static boolean isWeak(int m) { return ON && weakSeg != null && weakSeg[m] >= 0; }

    /** Uniform [0,1) from (seed, t, m, salt): splitmix64, host-only. */
    static double u01(long seed, long t, int m, long salt) {
        long z = seed * 0x9E3779B97F4A7C15L + t * 0xBF58476D1CE4E5B9L + (long) m * 0x94D049BB133111EBL + salt;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L; z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL; z ^= (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }

    /** Site position (um) and outward normal for motor m's weak site, from the CURRENT segment pose. */
    static double[] site(ExplicitCompleteMatHarness.ExMat e, int m) {
        FilamentStore f = e.G.fil; int nSeg = e.nSeg, s = weakSeg[m];
        double half = 0.5 * f.segLength.get(s);
        double ux = f.uVec.get(s), uy = f.uVec.get(nSeg + s), uz = f.uVec.get(2 * nSeg + s);
        double yx = f.yVec.get(s), yy = f.yVec.get(nSeg + s), yz = f.yVec.get(2 * nSeg + s);
        double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
        double zl = Math.sqrt(zx * zx + zy * zy + zz * zz); if (zl > 1e-30) { zx /= zl; zy /= zl; zz /= zl; }
        double c = Math.cos(weakPh[m]), sn = Math.sin(weakPh[m]);
        double nx = c * yx + sn * zx, ny = c * yy + sn * zy, nz = c * yz + sn * zz;
        double a = weakArc[m] - half, R = e.sbP.get(19);
        return new double[]{ f.coord.get(s) + a * ux + R * nx, f.coord.get(nSeg + s) + a * uy + R * ny,
                             f.coord.get(2 * nSeg + s) + a * uz + R * nz, nx, ny, nz, ux, uy, uz };
    }

    /** Loop-2 offset o (um, world) from F8: -L2_OUT along the head axis + L2_SIGN*L2_AX along th = e0 x econv. */
    static double[] loop2Offset(ExplicitCompleteMatHarness.ExMat e, int m) {
        if (!LOOP2) return new double[]{ 0, 0, 0, 0, 0, 0 };
        int N = e.N; DoubleArray fr = e.convF.get(12 * N + m) != 0.0 ? e.convF : e.frame;
        double cx = fr.get(3 * N + m), cy = fr.get(4 * N + m), cz = fr.get(5 * N + m);   // econv
        double bx = e.outGeom.get(6 * N + m) - e.outGeom.get(m), by = e.outGeom.get(7 * N + m) - e.outGeom.get(N + m),
               bz = e.outGeom.get(8 * N + m) - e.outGeom.get(2 * N + m);              // converter -> F8 = head axis
        double bl = Math.sqrt(bx * bx + by * by + bz * bz); bx /= bl; by /= bl; bz /= bl;
        double be = bx * cx + by * cy + bz * cz;
        double e0x = bx - be * cx, e0y = by - be * cy, e0z = bz - be * cz, el = Math.sqrt(e0x * e0x + e0y * e0y + e0z * e0z);
        e0x /= el; e0y /= el; e0z /= el;
        double tx = e0y * cz - e0z * cy, ty = e0z * cx - e0x * cz, tz = e0x * cy - e0y * cx;   // th = e0 x econv
        double a = -L2_OUT_NM * 1e-3, c = L2_SIGN * L2_AX_NM * 1e-3;
        return new double[]{ a * bx + c * tx, a * by + c * ty, a * bz + c * tz, tx, ty, tz };
    }
    /** Head-side tether point (um): F8 + o. */
    static double[] headPoint(ExplicitCompleteMatHarness.ExMat e, int m, double[] o) {
        int N = e.N; return new double[]{ e.outGeom.get(6 * N + m) + o[0], e.outGeom.get(7 * N + m) + o[1], e.outGeom.get(8 * N + m) + o[2] };
    }
    /** Actin-side tether point (um): the site shifted A_AX toward the pointed end (-u; barbed = +u). */
    static double[] anchor(double[] st) {
        double a = LOOP2 ? A_AX_NM * 1e-3 : 0.0;
        return new double[]{ st[0] - a * st[6], st[1] - a * st[7], st[2] - a * st[8] };
    }

    /**
     * Replaces siteGateA + siteCommitB when ON. Same bookkeeping contract as siteCommitB: boundSeg / bindArc /
     * bindAzim / bindSite on a STRONG entry, justBound, prevBound for every motor.
     */
    static void transitions(ExplicitCompleteMatHarness.ExMat e, int t, long seed) {
        var G = e.G; var mot = G.mot; int N = e.N;
        // (1) contact candidates for FREE heads: the real site gate with orientation + preload relaxed
        DoubleArray sb = new DoubleArray(e.sbP.getSize());
        for (int i = 0; i < sb.getSize(); i++) sb.set(i, e.sbP.get(i));
        sb.set(28, CONTACT_COS); sb.set(4, 1e9); sb.set(0, D_W_NM + (LOOP2 ? L2_AX_NM + A_AX_NM : 0.0));   // loop 2: tether gap checked below   // contact: head body outside actin (axis within 90 deg of -n)
        FilamentStore f = G.fil;
        ChiralSiteSystem.siteGateA(e.active, e.noBind, mot.boundSeg, mot.nucleotideState, e.outGeom,
                f.coord, f.uVec, f.yVec, f.segLength, e.segCumArc, sb, e.candInt, e.candArc, e.candAzim, e.exCounts);
        double kT = e.sbP.get(9), kW = K_W_PN_NM * 1e-9, kF8 = e.sbP.get(24);   // N/um
        double eps = e.sbP.get(20) * e.sbP.get(21);
        double dt = e.params.get(10 * N);
        double cos3 = e.sbP.get(3);   // g3 converter tolerance, deg
        for (int m = 0; m < N; m++) {
            int jb = 0, bs = mot.boundSeg.get(m);
            if (bs < 0 && e.active.get(m) == 1 && e.noBind.get(m) == 0) {
                if (weakSeg[m] < 0) {
                    if (e.candInt.get(m) >= 0 && mot.nucleotideState.get(m) == MotorStore.NUC_ADPPI) {
                        weakSeg[m] = e.candInt.get(m); weakSiteK[m] = e.candInt.get(N + m);
                        weakArc[m] = e.candArc.get(m); weakAzim[m] = e.candAzim.get(m); weakPh[m] = e.candAzim.get(m) - eps;
                        if (LOOP2) {   // contact = the loop-2 point within d_w of its actin partner
                            double[] st0 = site(e, m), an = anchor(st0), hp = headPoint(e, m, loop2Offset(e, m));
                            double gx = hp[0] - an[0], gy = hp[1] - an[1], gz = hp[2] - an[2];
                            if (Math.sqrt(gx * gx + gy * gy + gz * gz) * 1e3 > D_W_NM) weakSeg[m] = -1;
                        }
                        if (weakSeg[m] >= 0) nEnter++;
                        if (DEBUG > 0 && weakSeg[m] >= 0) { DEBUG--; double[] st0 = site(e, m);
                            double dx = e.outGeom.get(6 * N + m) - st0[0], dy = e.outGeom.get(7 * N + m) - st0[1], dz = e.outGeom.get(8 * N + m) - st0[2];
                            System.out.printf(java.util.Locale.US, "  [weak debug] t %d enter: F8-site %.2f nm (seg %d arc %.4f ph %.3f azim %.3f eps %.3f)%n", t, Math.sqrt(dx*dx+dy*dy+dz*dz)*1e3, weakSeg[m], weakArc[m], weakPh[m], weakAzim[m], eps); }
                    }
                } else {
                    weakSteps++;
                    double[] st = site(e, m), an = anchor(st), hp = headPoint(e, m, loop2Offset(e, m));
                    double rx = hp[0] - an[0], ry = hp[1] - an[1], rz = hp[2] - an[2];
                    double r = Math.sqrt(rx * rx + ry * ry + rz * rz);   // tether length
                    double qx = e.outGeom.get(6 * N + m) - st[0], qy = e.outGeom.get(7 * N + m) - st[1], qz = e.outGeom.get(8 * N + m) - st[2];
                    double r8 = Math.sqrt(qx * qx + qy * qy + qz * qz);  // F8 - site (the stereospecific interface)
                    double F = kW * r;   // N
                    boolean brk = false;
                    if (mot.nucleotideState.get(m) != MotorStore.NUC_ADPPI) { brk = true; nBreakNuc++; }
                    else if (r * 1e3 > R_BREAK_NM) { brk = true; nBreakRange++; }
                    else {
                        double k = K_OFF0 * Math.exp(F * X_BELL_NM * 1e-9 / kT);
                        if (u01(seed, t, m, 0x57454B4FL) < -Math.expm1(-k * dt)) { brk = true; nBreakRate++; }
                    }
                    if (DEBUG2 > 0) { DEBUG2--; System.out.printf(java.util.Locale.US, "  [weak debug] t %d weak r %.2f nm F %.2f pN%s%n", t, r*1e3, F*1e12, brk ? " BREAK" : ""); }
                    if (brk) weakSeg[m] = -1;
                    else {
                        // weak -> strong: Steffen-weighted attempt, with the existing g4 preload + g3 converter conditions
                        double hx = e.outGeom.get(9 * N + m), hy = e.outGeom.get(10 * N + m), hz = e.outGeom.get(11 * N + m);
                        double dh = -(hx * st[3] + hy * st[4] + hz * st[5]);
                        double th = Math.acos(Math.max(-1, Math.min(1, dh)));
                        double mu = dockMis(e, m, st[6], st[7], st[8]);
                        double phi = e.q.get(m), psi = e.q.get(N + m), thS = e.q.get(2 * N + m);
                        boolean g3 = Math.abs(Math.toDegrees((psi - phi) - thS)) < cos3;
                        boolean g4 = kF8 * r8 * 1e12 < e.sbP.get(4);   // docking: the F8 interface itself must be formed
                        double fa = siteFacing(e, m, st);   // Steffen's angle: site azimuth vs the direction to the motor
                        double ang2 = WEIGHT == 0 ? 0.0 : WEIGHT == 1 ? fa * fa : WEIGHT == 3 ? th * th + mu * mu : fa * fa + th * th + mu * mu;
                        double k = K_WS * Math.exp(-0.5 * ALPHA * ang2);
                        if (ANG != null) ANG.append(String.format(java.util.Locale.US, "%d\t%d\t%.3f\t%.3f\t%.2f\t%.2f\t%.2f\t%d\t%d%n", t, m, r * 1e3, r8 * 1e3,
                                Math.toDegrees(fa), Math.toDegrees(th), Math.toDegrees(mu), g3 ? 1 : 0, g4 ? 1 : 0));
                        if (g3 && g4 && u01(seed, t, m, 0x5354524EL) < -Math.expm1(-k * dt)) {
                            bs = weakSeg[m];
                            mot.boundSeg.set(m, bs); mot.bindArc.set(m, (float) weakArc[m]); mot.bindAzim.set(m, (float) weakAzim[m]);
                            e.bindSite.set(m, weakSiteK[m]); jb = 1; nStrong++;
                            weakSeg[m] = -1;
                        }
                    }
                }
            } else if (bs >= 0) {
                weakSeg[m] = -1;
                if (LOOP2) {   // DOCK diagnostic: in the strong pose the loop-2 anchors should nearly meet (8R9V: ~0.8 nm)
                    int nSeg = e.nSeg;
                    double ux = f.uVec.get(bs), uy = f.uVec.get(nSeg + bs), uz = f.uVec.get(2 * nSeg + bs);
                    double[] o = loop2Offset(e, m), hp = headPoint(e, m, o);
                    double ax = A_AX_NM * 1e-3;   // anchor relative to the bound F8 (on the site when docked)
                    double gx = hp[0] - (e.outGeom.get(6 * N + m) - ax * ux), gy = hp[1] - (e.outGeom.get(7 * N + m) - ax * uy),
                           gz = hp[2] - (e.outGeom.get(8 * N + m) - ax * uz);
                    dockN++; dockThU += L2_SIGN * (o[3] * ux + o[4] * uy + o[5] * uz); dockGap += Math.sqrt(gx * gx + gy * gy + gz * gz) * 1e3;
                }
            }
            if (bs < 0) e.bindSite.set(m, -1);
            e.justBound.set(m, jb);
            e.prevBound.set(m, bs);
        }
    }

    /** Steffen 2001's azimuth: angle between the site normal and the direction from the site to the motor's S2
     *  base, both projected perpendicular to the filament axis (0 = the site faces the motor). */
    static double siteFacing(ExplicitCompleteMatHarness.ExMat e, int m, double[] st) {
        int N = e.N; double ux = st[6], uy = st[7], uz = st[8];
        double dx = e.nodes.get(m) - st[0], dy = e.nodes.get(N + m) - st[1], dz = e.nodes.get(2 * N + m) - st[2];
        double du = dx * ux + dy * uy + dz * uz; dx -= du * ux; dy -= du * uy; dz -= du * uz;
        double nx = st[3], ny = st[4], nz = st[5], nu = nx * ux + ny * uy + nz * uz; nx -= nu * ux; ny -= nu * uy; nz -= nu * uz;
        double a = Math.sqrt(dx * dx + dy * dy + dz * dz), b = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (a < 1e-12 || b < 1e-12) return 0.0;
        return Math.acos(Math.max(-1, Math.min(1, (dx * nx + dy * ny + dz * nz) / (a * b))));
    }
    /** matHeadDock's misalignment: stroke axis vs barbed, both projected into the plane normal to eup. */
    static double dockMis(ExplicitCompleteMatHarness.ExMat e, int m, double ux, double uy, double uz) {
        int N = e.N; var fr = e.frame;
        double ex = fr.get(6 * N + m), ey = fr.get(7 * N + m), ez = fr.get(8 * N + m);
        double vx = fr.get(m), vy = fr.get(N + m), vz = fr.get(2 * N + m);
        double ev = ex * vx + ey * vy + ez * vz, eu = ex * ux + ey * uy + ez * uz;
        double bx = vx - ev * ex, by = vy - ev * ey, bz = vz - ev * ez, hx = ux - eu * ex, hy = uy - eu * ey, hz = uz - eu * ez;
        double bn = Math.sqrt(bx * bx + by * by + bz * bz), hn = Math.sqrt(hx * hx + hy * hy + hz * hz);
        if (bn < 1e-12 || hn < 1e-12) return 0.0;
        double c = (bx * hx + by * hy + bz * hz) / (bn * hn);
        return Math.acos(Math.max(-1, Math.min(1, c)));
    }

    /** Weak spring: +F on the head (bondData[0..2], read by the tilt solver when the weak flag is set). */
    static void forces(ExplicitCompleteMatHarness.ExMat e) {
        int N = e.N; FloatArray bd = e.G.bondData; double kW = K_W_PN_NM * 1e-9;
        int wRow = e.restC.getSize() / N - 1;
        for (int m = 0; m < N; m++) {
            e.restC.set(wRow * N + m, 0.0);
            e.restC.set((wRow - 3) * N + m, 0.0); e.restC.set((wRow - 2) * N + m, 0.0); e.restC.set((wRow - 1) * N + m, 0.0);
            if (weakSeg[m] < 0 || e.G.mot.boundSeg.get(m) >= 0) continue;
            double[] st = site(e, m), an = anchor(st), o = loop2Offset(e, m), hp = headPoint(e, m, o);
            double rx = hp[0] - an[0], ry = hp[1] - an[1], rz = hp[2] - an[2];
            double Fx = -kW * rx, Fy = -kW * ry, Fz = -kW * rz;   // on the head at F8 + o, N (central: collinear with r, so no net couple)
            e.restC.set((wRow - 3) * N + m, o[0]); e.restC.set((wRow - 2) * N + m, o[1]); e.restC.set((wRow - 1) * N + m, o[2]);
            int d = m * CrossBridgeSystem.STRIDE;
            for (int k = 0; k < CrossBridgeSystem.STRIDE; k++) bd.set(d + k, 0f);
            bd.set(d, (float) Fx); bd.set(d + 1, (float) Fy); bd.set(d + 2, (float) Fz);
            e.restC.set(wRow * N + m, kW);   // nonzero = weak; the value is the spring stiffness for the solver Hessian
            fSeg[6 * m] = -Fx; fSeg[6 * m + 1] = -Fy; fSeg[6 * m + 2] = -Fz;
            fSeg[6 * m + 3] = an[0]; fSeg[6 * m + 4] = an[1]; fSeg[6 * m + 5] = an[2];
        }
    }

    /** The exact reaction on the weak segment: force at the site point, torque about the segment centre. */
    static void gatherWeak(ExplicitCompleteMatHarness.ExMat e) {
        int N = e.N, nSeg = e.nSeg; FilamentStore f = e.G.fil;
        int wRow = e.restC.getSize() / N - 1;
        for (int m = 0; m < N; m++) {
            if (e.restC.get(wRow * N + m) == 0.0) continue;
            int s = weakSeg[m];
            double Fx = fSeg[6 * m], Fy = fSeg[6 * m + 1], Fz = fSeg[6 * m + 2];
            double ax = (fSeg[6 * m + 3] - f.coord.get(s)) * 1e-6, ay = (fSeg[6 * m + 4] - f.coord.get(nSeg + s)) * 1e-6,
                   az = (fSeg[6 * m + 5] - f.coord.get(2 * nSeg + s)) * 1e-6;
            f.forceSum.set(s, (float) (f.forceSum.get(s) + Fx));
            f.forceSum.set(nSeg + s, (float) (f.forceSum.get(nSeg + s) + Fy));
            f.forceSum.set(2 * nSeg + s, (float) (f.forceSum.get(2 * nSeg + s) + Fz));
            f.torqueSum.set(s, (float) (f.torqueSum.get(s) + ay * Fz - az * Fy));
            f.torqueSum.set(nSeg + s, (float) (f.torqueSum.get(nSeg + s) + az * Fx - ax * Fz));
            f.torqueSum.set(2 * nSeg + s, (float) (f.torqueSum.get(2 * nSeg + s) + ax * Fy - ay * Fx));
        }
    }

    static String stats() {
        return String.format(java.util.Locale.US, "weak entries %d | breaks: rate %d, range %d, nucleotide %d | weak->strong %d | weak motor-steps %d",
                nEnter, nBreakRate, nBreakRange, nBreakNuc, nStrong, weakSteps)
            + (LOOP2 && dockN > 0 ? String.format(java.util.Locale.US, " | DOCK (strong steps %d): mean signed th.u %.2f (want < 0 = pointed-ward), loop-2 anchor gap %.2f nm (8R9V ~0.8)", (long) dockN, dockThU / dockN, dockGap / dockN) : "");
    }
}
