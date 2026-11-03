package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * SINGLE-STAGE RATE CAPTURE, Duke style (2026-10-26, jba). Noncanonical, default OFF; replaces the site-aware
 * deterministic gate pair siteGateA + siteCommitB when ON. One implementation for both runners.
 *
 * <p>Motivation. Duke 1999 (PNAS 96:2770) binds M.ADP.Pi at a constant rate k_bind (40 /s) times the Boltzmann
 * factor of the strain the bond is born with, exp(-K x0^2 / 2kT); Huxley's f(x) is the same idea. The weak
 * collision complex is in rapid equilibrium relative to the Pi-limited strong attachment (Geeves A -> R;
 * Schoenberg / Brenner fibre data), so a two-stage scheme reduces exactly to one stage with a renormalised rate.
 * The prefactor k0 therefore LUMPS weak binding, isomerisation and Pi gating; it has no single biochemical
 * analogue and is calibrated against an attachment benchmark, not against gliding or twirl.
 *
 * <p>Rule. A free, eligible ADP.Pi head attaches to lattice site i at rate
 * <pre>   k_i = k0 exp(-E_i / kT),   E_i = 1/2 kF8 d_i^2 + 1/2 k_ori theta_i^2 + 1/2 kconv dtheta^2 + 1/2 k_ori mis^2</pre>
 * i.e. the elastic energy the bound state carries at birth (no uncharged pre-stroke swing: the -headtilt lesson). The
 * orientation and dock terms use k_ori (see KORI_PN_NM): the dock in series with the pliant hinge, as the head relaxes. d_i = F8 to site; theta_i = head axis vs the (tilted) site target;
 * dtheta = converter error; mis = stroke axis vs barbed (charged only when the stereospecific dock is on).
 * Every site within the window on every segment counts (not only the nearest one); the site is drawn with
 * probability k_i / sum k. Retired relative to the deterministic gate: the hard 2 nm preload (g4), the
 * 25 deg orientation cone (g1'), the converter window (g3), the 15 kT budget (g5) and, when the dock is on, the
 * polarity gate (g9; the dock energy already charges misalignment). Kept: g8 accessibility (approach from
 * outside actin), head axis on the outer side of the site (theta < 90 deg), the rebind block, occupancy.
 * The distance window is computational only (weight exp(-7.8) at 8 nm with kF8 = 1 pN/nm).
 *
 * <p>sbP rows 42 enable | 43 k0 (1/s per site) | 44 window (nm) | 45 dt (s) | 46 capture orientation stiffness (N.m/rad^2) |
 * 47 swing-plane tilt psi (rad, mirror-coupled; = HeadChainSystem hp[3]).
 * rbC rows (x N): 0 arc | 1 sum_i exp(-E_i) | 2 azim | 3 expected attachments sum k dt (Rao-Blackwell) |
 * 4 E of the drawn site (kT) | 5 E_common (kT) | 6 F8 part of row 4 | 7 dock part of row 5 |
 * 8-10 stroke-axis target of the drawn site: the barbed axis u turned by psi about the site's head-axis target
 * (CANON V3 capture fix, 2026-11-03: the dock term charged mis against u while the bound dock targets the
 * psi-turned axis, so every head was born psi off its target; identical to u, bit for bit, at psi = 0).
 */
final class RateBindSystem {
    static boolean ON = false;
    static double K0 = 1.0e4;          // 1/s per site, LUMPED, calibrate (no single biochemical analogue)
    static double WINDOW_NM = 8.0;     // computational distance window
    // ORIENTATION STIFFNESS CHARGED AT CAPTURE (pN.nm/rad^2; <= 0 = derived). The bound dock (k_bind = 512) sits in series
    // with the head chain's pliant hinge (Billington 2014, 23.4), and a misdocked head relaxes into the hinge within
    // microseconds, so the elastic energy the bond is born with is the SERIES one, (1/k_bind + 1/kappa_P)^-1 = 22.4. That is
    // also the quantity Steffen 2001 measured (target-function alpha = 3.7 kT/rad^2, i.e. ~15 pN.nm/rad^2). -rate-kori 512
    // charges the unrelaxed interface (the deterministic gate's g5 convention).
    static double KORI_PN_NM = 0.0;
    static final int SB0 = 42, SB_SIZE = 48, ROWS = 11;
    static final long SALT_SITE = 0x52415445L, SALT_ATT = 0x41545443L;

    static void alloc(ExplicitCompleteMatHarness.ExMat e) {
        if (e.sbP.getSize() < SB_SIZE) throw new IllegalStateException("-rate-bind: sbP too small");
        e.sbP.set(SB0, 1.0); e.sbP.set(SB0 + 1, K0); e.sbP.set(SB0 + 2, WINDOW_NM); e.sbP.set(SB0 + 3, e.params.get(10 * e.N));
        double kb = e.sbP.get(29), kp = HeadChainSystem.KAPPA_PN_NM * 1e-21;
        e.sbP.set(SB0 + 4, KORI_PN_NM > 0 ? KORI_PN_NM * 1e-21 : (HeadChainSystem.ON ? 1.0 / (1.0 / kb + 1.0 / kp) : kb));
        e.sbP.set(SB0 + 5, HeadChainSystem.ON ? ExplicitCompleteMatHarness.MIRROR_SIGN * ExplicitCompleteMatHarness.SWING_TILT_DEG * Math.PI / 180.0 : 0.0);
        e.rbC = new DoubleArray(ROWS * e.N); e.rbC.init(0.0);
    }
    static String describe(ExplicitCompleteMatHarness.ExMat e) {
        boolean dock = e.sbP.get(40) > 0.5;
        return String.format(java.util.Locale.US, "k0 %.3g /s per site, window %.1f nm | E = F8 strain + orientation%s at k_ori %.1f pN.nm/rad^2 (%s; bound dock %.0f) + converter | polarity gate %s",
                K0, WINDOW_NM, dock ? " + dock" : "", e.sbP.get(SB0 + 4) * 1e21, KORI_PN_NM > 0 ? "set" : (HeadChainSystem.ON ? "dock in series with the pliant hinge" : "= bound dock"), e.sbP.get(29) * 1e21, dock ? "OFF (dock energy)" : (e.sbP.get(36) > 0.5 ? "ON (no dock)" : "off"));
    }

    /**
     * Actin side (15 args): for each eligible free ADP.Pi head, sum exp(-E_site) over every lattice site within the
     * window on every segment, and draw one site with probability proportional to its weight (single-pass weighted
     * reservoir, one uniform per site). Sites are owned half-open per segment, so a junction site counts once.
     */
    public static void rateGate(IntArray active, IntArray noBind, IntArray boundSeg, IntArray nuc,
            DoubleArray outGeom, FloatArray filCoord, FloatArray filUVec, FloatArray filYVec,
            FloatArray filSegLength, FloatArray segCumArc, DoubleArray sbP, IntArray matc,
            IntArray candInt, DoubleArray rbC, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3);
        long tt = matc.get(0), seed = matc.get(1);
        double rise = sbP.get(16), twistRate = sbP.get(17), stairPhase = sbP.get(18), Ract = sbP.get(19);
        double epsBind = sbP.get(20), mirror = sbP.get(21), accTol = sbP.get(23), kF8 = sbP.get(24);
        int halfSearch = (int) sbP.get(22);
        double phaseGlobal = sbP.get(25), stepPhase = (stairPhase != 0.0) ? stairPhase : (twistRate * rise);
        double partAx = sbP.get(32), partPh = sbP.get(33);
        int nPart = (partAx != 0.0) ? 1 : 0;
        double segTol = sbP.get(26), ctlG = sbP.get(34), stlG = sbP.get(35);
        double kBindP = sbP.get(SB0 + 4), kT = sbP.get(9), psiSw = sbP.get(SB0 + 5);
        double win = sbP.get(SB0 + 2) * 1e-3;   // um
        double reach = win + Ract;
        for (@Parallel int m = 0; m < N; m++) {
            candInt.set(m, -1); candInt.set(N + m, -1); rbC.set(N + m, 0.0);
            if (active.get(m) != 1 || noBind.get(m) == 1 || boundSeg.get(m) != -1 || nuc.get(m) != 2) continue;
            double fx = outGeom.get(6 * N + m), fy = outGeom.get(7 * N + m), fz = outGeom.get(8 * N + m);   // xF8
            double xhx = outGeom.get(9 * N + m), xhy = outGeom.get(10 * N + m), xhz = outGeom.get(11 * N + m);
            double W = 0.0, bArc = 0.0, bAz = 0.0, bE = 0.0, bF = 0.0, bTx = 0.0, bTy = 0.0, bTz = 0.0;
            int bSeg = -1, bK = -1, idx = 0;
            for (int s = 0; s < nSeg; s++) {
                double half = 0.5 * filSegLength.get(s);
                double cx = filCoord.get(s), cy = filCoord.get(nSeg + s), cz = filCoord.get(2 * nSeg + s);
                double ux = filUVec.get(s), uy = filUVec.get(nSeg + s), uz = filUVec.get(2 * nSeg + s);
                double dx = fx - cx, dy = fy - cy, dz = fz - cz;
                double foot = dx * ux + dy * uy + dz * uz;
                double footC = foot < -half ? -half : (foot > half ? half : foot);
                double qx = dx - footC * ux, qy = dy - footC * uy, qz = dz - footC * uz;
                if (qx * qx + qy * qy + qz * qz > reach * reach) continue;
                double yx = filYVec.get(s), yy = filYVec.get(nSeg + s), yz = filYVec.get(2 * nSeg + s);
                double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
                double zl = zx * zx + zy * zy + zz * zz;
                if (zl > 1e-30) { double iz = 1.0 / Math.sqrt(zl); zx *= iz; zy *= iz; zz *= iz; }
                double cum = segCumArc.get(s), segL = 2.0 * half;
                int k0 = (int) ((cum + footC + half) / rise + 0.5);
                for (int j = -halfSearch; j <= halfSearch; j++) {
                    for (int p = 0; p <= nPart; p++) {
                        int k = k0 + j;
                        if (k < 0) continue;
                        double laRaw = k * rise + p * partAx - cum;
                        if (laRaw < -segTol || !(laRaw < segL - segTol)) continue;   // half-open: a junction site counts once
                        double la = laRaw < 0.0 ? 0.0 : laRaw;
                        double ph;
                        if (phaseGlobal != 0.0) { double tw = k * stepPhase + p * partPh; ph = tw - 6.283185307179586 * (double) ((long) (tw / 6.283185307179586)); }
                        else ph = ((stairPhase != 0.0) ? (k * stairPhase) : (twistRate * (la - half))) + p * partPh;
                        double cph = HeadChainSystem.dcos(ph), sph = HeadChainSystem.dsin(ph);
                        double nx = cph * yx + sph * zx, ny = cph * yy + sph * zy, nz = cph * yz + sph * zz;
                        double aOff = la - half;
                        double px = cx + aOff * ux + Ract * nx, py = cy + aOff * uy + Ract * ny, pz = cz + aOff * uz + Ract * nz;
                        double vx = fx - px, vy = fy - py, vz = fz - pz;
                        if (!(vx * nx + vy * ny + vz * nz > -accTol)) continue;      // g8 accessibility
                        double d2 = vx * vx + vy * vy + vz * vz;
                        if (!(d2 < win * win)) continue;                              // computational window
                        double dh = xhx * (-nx * ctlG + ux * stlG) + xhy * (-ny * ctlG + uy * stlG) + xhz * (-nz * ctlG + uz * stlG);
                        if (!(dh > 0.0)) continue;                                    // head on the outer side
                        if (dh > 1.0) dh = 1.0;
                        double th = ChiralSiteSystem.cacos(dh);
                        double E = (0.5 * kF8 * d2 * 1e-6 + 0.5 * kBindP * th * th) / kT;   // kF8 in N/um, d2 in um^2: x1e-6 -> J
                        double w = WeakBindSystem.dexp(-E);
                        W += w;
                        if (WeakBindSystem.u01(seed, tt, m, SALT_SITE + idx) * W < w) {
                            bSeg = s; bK = 2 * k + p; bArc = la; bAz = ph + mirror * epsBind; bE = E; bF = 0.5 * kF8 * d2 * 1e-6 / kT;
                            bTx = ux; bTy = uy; bTz = uz;
                            if (psiSw != 0.0) {   // the bound dock's stroke-axis target: u turned by psi about the head-axis target
                                double wx = psiSw * (-nx * ctlG + ux * stlG), wy = psiSw * (-ny * ctlG + uy * stlG), wz = psiSw * (-nz * ctlG + uz * stlG);
                                bTx = HeadChainSystem.rotC(ux, uy, uz, wx, wy, wz, 0); bTy = HeadChainSystem.rotC(ux, uy, uz, wx, wy, wz, 1);
                                bTz = HeadChainSystem.rotC(ux, uy, uz, wx, wy, wz, 2);
                            }
                        }
                        idx++;
                    }
                }
            }
            if (bSeg < 0) continue;
            candInt.set(m, bSeg); candInt.set(N + m, bK);
            rbC.set(m, bArc); rbC.set(N + m, W); rbC.set(2 * N + m, bAz); rbC.set(4 * N + m, bE); rbC.set(6 * N + m, bF);
            rbC.set(8 * N + m, bTx); rbC.set(9 * N + m, bTy); rbC.set(10 * N + m, bTz);
        }
    }

    /**
     * Motor side (15 args): the energy terms common to all sites of this motor (converter; dock on the drawn
     * segment), the attachment event at k0 * sum_i exp(-E_i) * exp(-E_common), and the siteCommitB bookkeeping.
     */
    /**
     * DIAGNOSTIC (-s2dir-gate-deg, default off): refresh params rows 22..24 with the live horizontal S2 chord, base node 0
     * -> pivot node M, the same measure as the episode table's cos_S2. Under -swivel-dock / -head-chain nothing else
     * updates those rows after build, so the g10 gate would otherwise read the initial S2 direction.
     */
    public static void s2DirRefresh(DoubleArray nodes, DoubleArray params, IntArray counts) {
        int N = counts.get(0), M = counts.get(2);
        for (@Parallel int m = 0; m < N; m++) {
            double cx = nodes.get((3 * M) * N + m) - nodes.get(m), cy = nodes.get((3 * M + 1) * N + m) - nodes.get(N + m);
            double cl = Math.sqrt(cx * cx + cy * cy);
            if (cl > 1e-12) { params.set(22 * N + m, cx / cl); params.set(23 * N + m, cy / cl); params.set(24 * N + m, 0.0); }
        }
    }

    public static void rateCommit(IntArray boundSeg, IntArray noBind, DoubleArray q, DoubleArray params, DoubleArray sbP,
            IntArray matc, IntArray candInt, DoubleArray rbC, FloatArray bindArc, FloatArray bindAzim, IntArray bindSite,
            IntArray prevBound, IntArray justBound, IntArray counts, FloatArray filUVec) {
        int N = counts.get(0), nSeg = counts.get(3);
        long tt = matc.get(0), seed = matc.get(1);
        double kT = sbP.get(9), k0 = sbP.get(SB0 + 1), dt = sbP.get(SB0 + 3);
        boolean dock = sbP.get(40) > 0.5, pol = sbP.get(36) > 0.5;
        double cosPol = sbP.get(37), kDock = sbP.get(SB0 + 4);
        for (@Parallel int m = 0; m < N; m++) {
            int bs = boundSeg.get(m);
            int jb = 0;
            int cs = candInt.get(m);
            if (bs < 0 && cs >= 0 && noBind.get(m) == 0) {
                double dth = (q.get(N + m) - q.get(m)) - q.get(2 * N + m);
                double Ec = 0.5 * params.get(6 * N + m) * dth * dth / kT;
                double pd = params.get(19 * N + m) * rbC.get(8 * N + m) + params.get(20 * N + m) * rbC.get(9 * N + m)
                          + params.get(21 * N + m) * rbC.get(10 * N + m);   // stroke axis vs the dock's target (u at psi = 0)
                boolean ok = true;
                if (dock) {
                    double pc = pd > 1.0 ? 1.0 : (pd < -1.0 ? -1.0 : pd);
                    double mi = ChiralSiteSystem.cacos(pc);
                    double ed = 0.5 * kDock * mi * mi / kT; Ec += ed; rbC.set(7 * N + m, ed);
                } else if (pol) ok = pd >= cosPol;
                if (sbP.get(38) > 0.5) {   // g10 S2-direction gate (DIAGNOSTIC, -s2dir-gate-deg): as ChiralSiteSystem.siteCommitB
                    double sd = params.get(22 * N + m) * filUVec.get(cs) + params.get(23 * N + m) * filUVec.get(nSeg + cs)
                              + params.get(24 * N + m) * filUVec.get(2 * nSeg + cs);
                    if (!(sd >= sbP.get(39))) ok = false;
                }
                rbC.set(5 * N + m, Ec);
                if (ok) {
                    double k = k0 * rbC.get(N + m) * WeakBindSystem.dexp(-Ec);
                    rbC.set(3 * N + m, rbC.get(3 * N + m) + k * dt);
                    if (WeakBindSystem.u01(seed, tt, m, SALT_ATT) < WeakBindSystem.pEvent(k * dt)) {
                        bs = cs;
                        boundSeg.set(m, bs);
                        bindArc.set(m, (float) rbC.get(m));
                        bindAzim.set(m, (float) rbC.get(2 * N + m));
                        bindSite.set(m, candInt.get(N + m));
                        jb = 1;
                    }
                }
            }
            if (bs < 0) bindSite.set(m, -1);
            justBound.set(m, jb);
            prevBound.set(m, bs);
        }
    }

    static int DEBUG = Integer.getInteger("softbox.rbdebug", 0);   // CPU runner only: print candidate statistics every DEBUG steps
    static final StringBuilder DBG = new StringBuilder();   // per-candidate F8 | orientation | dock | converter (kT)
    static String debug(ExplicitCompleteMatHarness.ExMat e) {
        int N = e.N, nc = 0, elig = 0; double maxW = 0, minE = 1e9, minEc = 1e9, maxK = 0;
        for (int m = 0; m < N; m++) {
            if (e.active.get(m) == 1 && e.G.mot.boundSeg.get(m) < 0 && e.G.mot.nucleotideState.get(m) == 2) elig++;
            if (e.candInt.get(m) < 0) continue;
            nc++; double W = e.rbC.get(N + m), Ec = e.rbC.get(5 * N + m);
            maxW = Math.max(maxW, W); minE = Math.min(minE, e.rbC.get(4 * N + m)); minEc = Math.min(minEc, Ec);
            maxK = Math.max(maxK, K0 * W * Math.exp(-Ec));
            double Ef = e.rbC.get(6 * N + m), Es = e.rbC.get(4 * N + m), Ed = e.rbC.get(7 * N + m);
            DBG.append(String.format(java.util.Locale.US, "%.2f\t%.2f\t%.2f\t%.2f%n", Ef, Es - Ef, Ed, Ec - Ed));
        }
        return String.format(java.util.Locale.US, "eligible %d candidates %d maxW %.3g minE_site %.2f kT minE_common %.2f kT max k %.3g /s expected %.4f", elig, nc, maxW, minE, minEc, maxK, sumRow(e, 3));
    }
    static double sumRow(ExplicitCompleteMatHarness.ExMat e, int row) { double s = 0; for (int m = 0; m < e.N; m++) s += e.rbC.get(row * e.N + m); return s; }
    static String stats(ExplicitCompleteMatHarness.ExMat e) {
        return String.format(java.util.Locale.US, "expected attachments (sum k dt) %.3f", sumRow(e, 3));
    }
}
