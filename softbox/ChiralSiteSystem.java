package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * DISCRETE ACTIN SITES + HEAD ROTATIONAL DOF + BOUND ORIENTATIONAL REGISTRY + LOCAL CHIRAL OFFSETS.
 *
 * <p><b>Noncanonical, flag-gated, DEFAULT-OFF, byte-identical when disabled.</b> Every kernel here is
 * additive: none is wired into a TaskGraph or called by the CPU runner unless its feature flag is on, and
 * no canonical kernel is modified. Report:
 * {@code docs/DISCRETE_ACTIN_SITE_CHIRAL_BINDING_AND_STROKE_FINDINGS.md}.
 *
 * <p>Tests the mechanical-stereospecificity hypothesis: strong binding registers the head to a DISCRETE
 * local actin-site frame; if the bound interface (or the effective stroke) is slightly oblique to the
 * filament axis <i>in that local chiral frame</i>, every stroke acquires the SAME-signed circumferential
 * component regardless of which side of the filament the motor sits on, hence a coherent axial torque.
 *
 * <h2>Local covariance (the governing rule)</h2>
 * Every direction below is built from simulated local material frames — the filament's rolling material
 * frame ({@code filUVec}/{@code filYVec}, maintained by {@link DerivedGeometrySystem}) and the head's own
 * long axis (from the explicit-S2 beam pose). No laboratory axis, no coverslip normal, no shared absolute
 * azimuth, no world-frame torsional anchor, and no torque sign chosen by a parameter appear anywhere.
 *
 * <h2>Site frame</h2>
 * <pre>
 *   uSite = filUVec[s]                           (pointed→barbed material tangent)
 *   nSite = cos(phi)*segY + sin(phi)*segZ        (outward radial material normal; segZ = uSite × segY)
 *   tSite = mirrorSign * (uSite × nSite)         ⇒ (nSite, tSite, uSite) right-handed at mirrorSign=+1
 * </pre>
 * so the frame translates, bends, rotates and ROLLS with the filament.
 *
 * <h2>The new generalized coordinate (Stage 1)</h2>
 * The explicit-S2 motor's only rotational generalized coordinates are {@code phi} and {@code psi}, BOTH
 * carried about the shared base axis {@code eup} in {@code matS2SolveStep}; the head's spin about its own
 * long axis is unrepresented ({@code matPlaceHeadExplicit} synthesises the head {@code yVec} from a
 * LAB-FIXED perpendicular). This class adds exactly that missing coordinate:
 * <pre>
 *   eBind   = normalize(xF8 − xH)                (the head long axis — simulated beam pose, not a lab axis)
 *   headRef = a unit material direction ⊥ eBind  (the head's material reference; the coordinate itself)
 *   omega   = accumulated roll of headRef about eBind (a DERIVED material accumulation, never a lab angle)
 * </pre>
 * {@code headRef} is a 3-vector, not an angle, so it transforms covariantly under any rigid rotation of the
 * scene. Each step it is parallel-transported (Gram–Schmidt against the CURRENT eBind — the standard
 * discrete rotation-minimizing frame) and then rotated about eBind by the overdamped increment
 * {@code dOmega = tau*dt/gammaOmega}.
 *
 * <p><b>Why this is the smallest valid coordinate, and why it does not perturb the beam.</b> A rotation
 * about {@code eBind} moves neither {@code xF8} nor {@code xH} (both lie on that axis), so it is exactly
 * decoupled from the 14×14 beam+angle system: the canonical solve is untouched and the generalized force
 * conjugate to a pure couple about eBind vanishes for {@code phi}/{@code psi} under the
 * rotation-minimizing convention. The coordinate is therefore added WITHOUT redesigning
 * {@code matS2SolveStep}.
 *
 * <h2>Equal-and-opposite mechanics</h2>
 * The registry couple {@code tau} about {@code eBind} is applied to the head's new coordinate AND, with the
 * opposite sign, to the filament — accumulated into the segment-side torque slot of {@code bondData}
 * ({@code d+9..11}) which the byte-unchanged {@link CrossBridgeSystem#segGather} sums into
 * {@code filament.torqueSum}. Nothing is applied to the filament alone. The Brownian torque is a
 * thermostat and carries no reaction, exactly as every other Brownian channel in this project.
 */
public final class ChiralSiteSystem {
    private ChiralSiteSystem() {}

    /** {@code bondData} stride (CrossBridgeSystem.STRIDE). */
    static final int STRIDE = 13;
    /** Private counter-based streams (no existing RNG stream is shifted). */
    static final long OMEGA_SALT = 0x484F4D47L;   // "HOMG" — head-roll Brownian

    // ---------------------------------------------------------------------------------------------------
    // chiP layout (DoubleArray, size 16) — one place, both runners:
    //  [0] latticeMode   0 = OFF (continuous canonical) | 1 native | 2 every3 | 3 every4 | 4 stair9-45 | 5 stair9-90
    //  [1] rise          axial site spacing (µm)
    //  [2] twistRate     analytic native helical rate (rad/µm, signed, LEFT-handed)
    //  [3] stairPhase    azimuthal advance per site (rad); 0 ⇒ use the analytic native twist
    //  [4] Ractin        actin radius (µm) — the moment arm
    //  [5] epsBind       askew BOUND-interface offset in the local site tangent plane (rad)
    //  [6] epsStroke     askew STROKE offset in the local site tangent plane (rad)
    //  [7] kOmega        bound orientational registry stiffness (N·m/rad); 0 ⇒ exactly inert
    //  [8] gammaOmegaFallback  (unused; the head's own bRotGam is used)
    //  [9] dt            (s)
    //  [10] brownOmega   1 ⇒ head-roll Brownian on
    //  [11] rollDofOn    1 ⇒ the head roll coordinate is integrated
    //  [12] siteExclusive 1 ⇒ one head per site
    //  [13] mirrorSign   +1 | −1 (diagnostic: reverses the site tangential direction ⇒ site chirality)
    //  [14] captureUm    3D site-capture radius (µm); a fresh bind with no site inside it is released
    //  [15] searchHalf   site-search half width (number of sites either side of the perpendicular foot)
    // ---------------------------------------------------------------------------------------------------

    /** Stable acos, PTX-safe (reuses the validated device form). */
    static double cacos(double x) { return TwoBodyBeamAnalyticGpu.dacos(x); }

    // ===================================================================================================
    // §25 — PROGRESS-RAMPED CONVERTER SKEW: the calibrated theta normalization (ONE source of truth)
    // ===================================================================================================
    /**
     * Relaxed PRE-stroke mechanical pose in the converter coordinate {@code theta = psi − phi}.
     *
     * <p><b>This is a CALIBRATION, not a derivation</b> (§25.1). The nominal rest constant
     * {@code PRESTROKE_THETAS = −0.523599} is NOT the relaxed pose: during the ADP·Pi dwell the head is docked
     * ({@code psi} pinned near {@code psiActin} ≈ 0) and {@code phi} is held at the binding lean, so the
     * converter torsional spring sits ≈ 0.436 rad OFF its own rest. Normalizing on the rest constants would put
     * {@code qTheta = 0.4166} on every WAITING motor — 42 % of eps as a standing preload (class R5).
     *
     * <p>Measured by {@code ChiralSiteHarness.runRampAudit} on the unloaded relaxed prestroke pose. The LOADED
     * value is −0.07745, i.e. 1.6 % of the full range away, so a loaded waiting motor carries a small but
     * nonzero {@code qTheta}; that residue is measured and reported, never assumed to be zero.
     */
    public static final double THETA_PRE  = -0.08736;
    /** Relaxed POST-stroke pose in {@code theta}. Equals {@code ADP_THETAS} exactly — unlike the pre-stroke
     *  pose, the converter DOES fully relax after the stroke, so this endpoint falls out of the model. */
    public static final double THETA_POST = +0.523599;

    /** Ramp shapes for {@code -converter-skew-progress-ramp}. */
    static final int RAMP_OFF = 0, RAMP_LINEAR = 1, RAMP_SMOOTHSTEP = 2, RAMP_DELAYED = 3;

    /**
     * The ramp factor {@code f(q)} — the HOST twin of the arithmetic inlined in {@link #convFrameStep}.
     *
     * <p>The kernel inlines this rather than calling it (TornadoVM device-side call limits), so the two must be
     * kept identical; fixture 404 gates that they agree to machine precision at every sampled q. Used host-side
     * for telemetry only — it is never part of the mechanical path.
     */
    public static double rampF(double q, int mode, double onset) {
        if (q <= 0.0) return 0.0;
        if (q >= 1.0) return 1.0;
        if (mode == RAMP_LINEAR) return q;
        if (mode == RAMP_SMOOTHSTEP) return q * q * (3.0 - 2.0 * q);
        if (mode == RAMP_DELAYED) {
            if (q <= onset) return 0.0;
            double z = (q - onset) / (1.0 - onset);
            return z * z * (3.0 - 2.0 * z);
        }
        return 1.0;   // RAMP_OFF ⇒ always-active: f ≡ 1
    }
    /** Normalized mechanical stroke progress from the stored converter coordinate. */
    public static double qTheta(double phi, double psi) {
        double q = (psi - phi - THETA_PRE) / (THETA_POST - THETA_PRE);
        return q < 0.0 ? 0.0 : (q > 1.0 ? 1.0 : q);
    }
    /** Effective skew for a BOUND motor. Host twin of the kernel expression (fixture 404). */
    public static double epsEff(double phi, double psi, double epsMax, int mode, double onset) {
        return epsMax * rampF(qTheta(phi, psi), mode, onset);
    }

    /** Counter-based Gaussian, identical construction to the Brownian streams already lowering on PTX. */
    static double gauss(long ep, long t, long salt) {
        long h = ((ep * 2654435761L) ^ (t * 40503L) ^ (salt * 0x9E3779B1L)); h ^= (h >>> 13); h *= 0x9E3779B1L; h ^= (h >>> 16);
        double u1 = ((h & 0xFFFFFF) + 1) / 16777217.0; h ^= (h << 7); double u2 = (((h >>> 8) & 0xFFFFFF) + 1) / 16777217.0;
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    // ===================================================================================================
    // STAGE 2 — DISCRETE PERSISTENT ACTIN SITES
    // ===================================================================================================
    /**
     * Snap a FRESH bind (boundSeg≥0 && prevBound&lt;0) from the canonical CONTINUOUS attachment
     * ({@code bindArc} = the perpendicular foot) onto the nearest DISCRETE lattice site, and latch the
     * site's persistent identity.
     *
     * <p>The lattice is generated analytically from the filament's own material frame, so it bends,
     * translates and rolls with the filament and needs no stored per-site state. A site is identified by
     * its FILAMENT-GLOBAL integer index {@code k = round(globalArc / rise)} where
     * {@code globalArc = segCumArc[s] + localArc} — stable across segment boundaries and across steps.
     * Its material coordinates are
     * <pre>
     *   localArc = k*rise − segCumArc[s]                            (axial, µm, from the segment's end1)
     *   phi      = twistRate*(localArc − half)                      (native lattices: the analytic helix)
     *            = k*stairPhase                                     (idealized staircase lattices)
     *   xSite    = sc + (localArc−half)*uSite + Ractin*nSite
     * </pre>
     *
     * <p><b>Bounded neighbour search, never an all-pairs scan.</b> Only the {@code searchHalf} sites either
     * side of the head's perpendicular foot are examined (a fixed, runtime-bounded loop; the foot index is
     * O(1) arithmetic), and the winner is the site whose 3D surface position is closest to the head's F8
     * anchor. A fresh bind with no site inside {@code captureUm} is RELEASED (site-limited binding — an
     * honest loss, not renormalised).
     *
     * <p>Writes {@code bindArc} (the site's axial coordinate), {@code bindAzim} (the site azimuth PLUS the
     * askew offset {@code epsBind}) and {@code bindSite} (the persistent global site id, −1 when free).
     * Established bonds are NOT re-snapped: the site ID is retained for the whole attachment.
     */
    public static void siteSnap(IntArray boundSeg, IntArray prevBound, IntArray justBound,
            DoubleArray outGeom, FloatArray filCoord, FloatArray filUVec, FloatArray filYVec,
            FloatArray filSegLength, FloatArray segCumArc, FloatArray bindArc, FloatArray bindAzim,
            IntArray bindSite, FloatArray tzOff, DoubleArray chiP, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3);
        int mode = (int) chiP.get(0);
        double rise = chiP.get(1), twistRate = chiP.get(2), stairPhase = chiP.get(3), Ract = chiP.get(4);
        double epsBind = chiP.get(5), mirror = chiP.get(13), capture = chiP.get(14);
        int halfSearch = (int) chiP.get(15);
        // ---- VILFAN TARGET-ZONE ACCESSIBILITY (noncanonical, default-off; chiP[25]/[26]) -------------------
        // tzHalf <= 0 AND tzRec == 0 ⇒ not one extra arithmetic operation is executed and tzOff is never
        // written ⇒ this kernel is byte-identical to its pre-target-zone form (validated by gate E).
        double tzHalf = chiP.get(25);    // accessibility half-width (rad); <= 0 ⇒ every azimuth is accessible
        double tzRec  = chiP.get(26);    // != 0 ⇒ record the signed zone offset of the CHOSEN site into tzOff
        boolean tzAct = (tzHalf > 0.0) || (tzRec != 0.0);
        for (@Parallel int m = 0; m < N; m++) {
            int bs = boundSeg.get(m), pb = prevBound.get(m);
            int jb = 0;
            if (bs >= 0 && pb < 0 && mode > 0) {
                int s = bs;
                double cx = filCoord.get(s), cy = filCoord.get(nSeg + s), cz = filCoord.get(2 * nSeg + s);
                double ux = filUVec.get(s), uy = filUVec.get(nSeg + s), uz = filUVec.get(2 * nSeg + s);
                double yx = filYVec.get(s), yy = filYVec.get(nSeg + s), yz = filYVec.get(2 * nSeg + s);
                double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
                double zl = zx * zx + zy * zy + zz * zz;
                if (zl > 1e-30) { double iz = 1.0 / Math.sqrt(zl); zx *= iz; zy *= iz; zz *= iz; }
                double halfLen = 0.5 * filSegLength.get(s);
                double footArc = bindArc.get(m);                 // canonical continuous attachment arc
                double cum = segCumArc.get(s);
                double fx = outGeom.get(6 * N + m), fy = outGeom.get(7 * N + m), fz = outGeom.get(8 * N + m);   // xF8
                int k0 = (int) ((cum + footArc) / rise + 0.5);
                int bestK = -1; double bestD2 = capture * capture;
                double bestArc = footArc, bestPhi = 0.0, bestDel = 0.0;
                // TARGET-ZONE CENTRE DIRECTION cHat: the radial direction from the filament AXIS toward this
                // head's own F8 anchor, taken at the head's axial station (its perpendicular foot). It is built
                // ONLY from simulated material vectors (u, the segment pose, the beam-solved xF8), so it is
                // covariant under any rigid rotation of the scene and carries no laboratory axis. In a surface
                // assay every motor is tethered below the filament, so cHat is the substrate-facing radial
                // direction — the equivalence is MEASURED, not assumed (gate C4).
                double chx = 0.0, chy = 0.0, chz = 0.0; boolean tzOk = false;
                if (tzAct) {
                    double fo = footArc - halfLen;
                    double rx = fx - (cx + fo * ux), ry = fy - (cy + fo * uy), rz = fz - (cz + fo * uz);
                    double rd = rx * ux + ry * uy + rz * uz;
                    rx -= rd * ux; ry -= rd * uy; rz -= rd * uz;
                    double rl = rx * rx + ry * ry + rz * rz;
                    if (rl > 1e-24) { double ir = 1.0 / Math.sqrt(rl); chx = rx * ir; chy = ry * ir; chz = rz * ir; tzOk = true; }
                }
                for (int j = -halfSearch; j <= halfSearch; j++) {
                    int k = k0 + j;
                    if (k < 0) continue;
                    double la = k * rise - cum;
                    if (la < 0.0 || la > 2.0 * halfLen) continue;                 // site not on THIS segment
                    double ph = (stairPhase != 0.0) ? (k * stairPhase) : (twistRate * (la - halfLen));
                    double cph = Math.cos(ph), sph = Math.sin(ph);
                    double nx = cph * yx + sph * zx, ny = cph * yy + sph * zy, nz = cph * yz + sph * zz;
                    // SIGNED TARGET-ZONE OFFSET delta = angle(cHat -> nSite) about u, in (-pi, pi].
                    // delta = 0 ⇔ the site's outward normal points exactly at the motor (the zone centre).
                    double del = 0.0;
                    if (tzOk) {
                        double dt2 = chx * nx + chy * ny + chz * nz;
                        double qx = chy * nz - chz * ny, qy = chz * nx - chx * nz, qz = chx * ny - chy * nx;
                        double sin2 = qx * qx + qy * qy + qz * qz;
                        double mag = TwoBodyBeamAnalyticGpu.tzAngle(sin2, dt2);
                        del = (qx * ux + qy * uy + qz * uz) < 0.0 ? -mag : mag;
                        // ACCESSIBILITY: a site whose normal lies outside the zone is not a binding target at all.
                        // No renormalisation — a head with no accessible site in reach simply does not attach.
                        if (tzHalf > 0.0 && (del > tzHalf || del < -tzHalf)) continue;
                    }
                    double aOff = la - halfLen;
                    double px = cx + aOff * ux + Ract * nx, py = cy + aOff * uy + Ract * ny, pz = cz + aOff * uz + Ract * nz;
                    double dx = px - fx, dy = py - fy, dz = pz - fz;
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 < bestD2) { bestD2 = d2; bestK = k; bestArc = la; bestPhi = ph; bestDel = del; }
                }
                if (bestK < 0) { boundSeg.set(m, -1); bs = -1; bindSite.set(m, -1); }   // no site in reach ⇒ release
                else {
                    jb = 1;
                    bindSite.set(m, bestK);
                    bindArc.set(m, (float) bestArc);
                    // askew BOUND-interface offset: a rotation in the LOCAL site tangent plane. mirror flips the
                    // site's tangential sense (the chirality control) so a mirrored lattice reverses the offset.
                    bindAzim.set(m, (float) (bestPhi + mirror * epsBind));
                    if (tzAct) tzOff.set(m, (float) bestDel);   // the signed zone offset AT ATTACHMENT
                }
            }
            if (bs < 0) { bindSite.set(m, -1); if (tzAct) tzOff.set(m, Float.NaN); }
            justBound.set(m, jb);
            prevBound.set(m, bs);
        }
    }

    /**
     * EXCLUSIVE discrete-site occupancy (single-thread serial, ascending head id, lowest id wins — the
     * validated {@code matSurfaceStericPrune} / {@code matOccupancyResolve} pattern). A freshly-bound head
     * is released if another bound head already occupies the SAME (filament, site id). Established bonds
     * are untouched; a higher-id fresh head never blocks a lower-id one. Deterministic, no RNG.
     * {@code occStats}: [candidates, rejects, accepted, same-step conflicts].
     */
    public static void siteOccupancyResolve(IntArray boundSeg, IntArray justBound, IntArray prevBound,
            IntArray bindSite, IntArray segFilId, IntArray occStats, DoubleArray chiP, IntArray counts) {
        int N = counts.get(0);
        int on = (int) chiP.get(12);
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int cand = 0, rej = 0, conf = 0;
            if (on != 0) {
                for (int m = 0; m < N; m++) {
                    if (justBound.get(m) != 1) continue;
                    int s = boundSeg.get(m); if (s < 0) continue;
                    cand++;
                    int myFil = segFilId.get(s), mySite = bindSite.get(m);
                    boolean reject = false, byFresh = false;
                    for (int b = 0; b < N; b++) {
                        if (b == m) continue;
                        int bsg = boundSeg.get(b); if (bsg < 0) continue;
                        if (justBound.get(b) == 1 && b > m) continue;
                        if (segFilId.get(bsg) != myFil) continue;
                        if (bindSite.get(b) != mySite) continue;
                        reject = true; if (justBound.get(b) == 1) byFresh = true;
                    }
                    if (reject) { boundSeg.set(m, -1); prevBound.set(m, -1); bindSite.set(m, -1); rej++; if (byFresh) conf++; }
                }
            }
            occStats.set(0, cand); occStats.set(1, rej); occStats.set(2, cand - rej); occStats.set(3, conf);
        }
    }

    // ===================================================================================================
    // STAGE 1 + STAGE 3 — HEAD ROTATIONAL DOF and BOUND ORIENTATIONAL REGISTRY
    // ===================================================================================================
    /**
     * Integrate the head's roll coordinate and (for a bound head) apply the equal-and-opposite registry
     * couple.
     *
     * <p>Per motor:
     * <ol>
     *   <li>{@code eBind = normalize(xF8 − xH)} from the beam pose; if degenerate the coordinate is held.</li>
     *   <li><b>Parallel transport</b>: {@code headRef ← normalize(headRef − (headRef·eBind) eBind)}. If the
     *       stored reference has collapsed onto eBind it is re-seeded from the CURRENT local material data
     *       (the site tangential direction when bound, else the largest-component perpendicular of eBind —
     *       a measure-zero re-seed, flagged in the report, never a standing lab-frame anchor).</li>
     *   <li><b>Registry</b> (bound, {@code kOmega>0}, lattice on): preferred material direction
     *       {@code bHat = cos(epsBind)*uSite + sin(epsBind)*tSite} projected ⊥ eBind; signed mismatch
     *       {@code dOmega = signed angle(bPerp → headRef) about eBind}; couple {@code tau = −kOmega*dOmega}
     *       on the head, {@code −tau} about eBind on the FILAMENT (added into the seg-side torque slot).</li>
     *   <li><b>Brownian</b> (optional, own stream): {@code tauB = sqrt(2 kT gammaOmega/dt) * g} — a
     *       thermostat, no reaction, applied bound AND unbound (a bound head is quieted only by the
     *       registry stiffness itself, never by a state switch).</li>
     *   <li>Rotate {@code headRef} about eBind by {@code dOmega_step = (tau + tauB)*dt/gammaOmega}
     *       (Rodrigues, exact for the perpendicular component), accumulate {@code omega}.</li>
     * </ol>
     *
     * <p>{@code headTau} (per motor, N·m): [m] = the registry couple actually applied this step (0 when
     * unbound / kOmega=0), used by the deterministic fixtures and the torque accounting.
     * {@code headMis} (per motor, rad): the signed registry mismatch.
     */
    public static void headRollStep(IntArray boundSeg, DoubleArray outGeom, FloatArray filUVec, FloatArray filYVec,
            FloatArray motorBRotGam, FloatArray bindAzim, FloatArray headRef, FloatArray headOmega,
            FloatArray headTau, FloatArray headMis, FloatArray bondData, DoubleArray chiP, IntArray matc, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3), nB = 3 * N;
        int mode = (int) chiP.get(0);
        double epsBind = chiP.get(5), kOmega = chiP.get(7), dt = chiP.get(9);
        int brownOn = (int) chiP.get(10), rollOn = (int) chiP.get(11);
        double mirror = chiP.get(13);
        long tt = matc.get(0), seed = matc.get(1);
        for (@Parallel int m = 0; m < N; m++) {
            headTau.set(m, 0f); headMis.set(m, 0f);
            if (rollOn == 0) continue;
            double hx = outGeom.get(3 * N + m), hy = outGeom.get(4 * N + m), hz = outGeom.get(5 * N + m);
            double ex = outGeom.get(6 * N + m) - hx, ey = outGeom.get(7 * N + m) - hy, ez = outGeom.get(8 * N + m) - hz;
            double el = Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (!(el > 1e-20)) continue;                                   // degenerate head ⇒ hold the coordinate
            double ie = 1.0 / el; ex *= ie; ey *= ie; ez *= ie;            // eBind
            int s = boundSeg.get(m);
            // --- site frame (only needed when bound) -------------------------------------------------
            double sux = 0, suy = 0, suz = 0, tnx = 0, tny = 0, tnz = 0;
            boolean haveSite = false;
            if (s >= 0) {
                sux = filUVec.get(s); suy = filUVec.get(nSeg + s); suz = filUVec.get(2 * nSeg + s);
                double yx = filYVec.get(s), yy = filYVec.get(nSeg + s), yz = filYVec.get(2 * nSeg + s);
                double zx = suy * yz - suz * yy, zy = suz * yx - sux * yz, zz = sux * yy - suy * yx;
                double zl = zx * zx + zy * zy + zz * zz;
                if (zl > 1e-30) { double iz = 1.0 / Math.sqrt(zl); zx *= iz; zy *= iz; zz *= iz; }
                double phiSite = bindAzim.get(m) - mirror * epsBind;       // the SITE azimuth (offset removed)
                double cph = Math.cos(phiSite), sph = Math.sin(phiSite);
                double nx = cph * yx + sph * zx, ny = cph * yy + sph * zy, nz = cph * yz + sph * zz;
                // tSite = mirror * (uSite × nSite)
                tnx = mirror * (suy * nz - suz * ny); tny = mirror * (suz * nx - sux * nz); tnz = mirror * (sux * ny - suy * nx);
                haveSite = true;
            }
            // --- parallel transport of the material reference ------------------------------------------
            double rx = headRef.get(m), ry = headRef.get(N + m), rz = headRef.get(2 * N + m);
            double d = rx * ex + ry * ey + rz * ez;
            rx -= d * ex; ry -= d * ey; rz -= d * ez;
            double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (!(rl > 1e-9)) {                                            // collapsed / uninitialised ⇒ re-seed
                if (haveSite) { rx = tnx; ry = tny; rz = tnz; }
                else { double ax = (ex < 0.9 && ex > -0.9) ? 1.0 : 0.0, ay = (ex < 0.9 && ex > -0.9) ? 0.0 : 1.0;
                       double dd = ax * ex + ay * ey; rx = ax - dd * ex; ry = ay - dd * ey; rz = -dd * ez; }
                d = rx * ex + ry * ey + rz * ez; rx -= d * ex; ry -= d * ey; rz -= d * ez;
                rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
                if (!(rl > 1e-12)) continue;
            }
            double ir = 1.0 / rl; rx *= ir; ry *= ir; rz *= ir;
            // --- registry couple ----------------------------------------------------------------------
            double gam = motorBRotGam.get(3 * m + 2);                      // head sub-body roll drag (about its own axis)
            if (!(gam > 0)) gam = 1.0;
            double tau = 0.0, mis = 0.0;
            if (haveSite && kOmega > 0.0 && mode > 0) {
                double bx = Math.cos(epsBind) * sux + Math.sin(epsBind) * tnx;
                double by = Math.cos(epsBind) * suy + Math.sin(epsBind) * tny;
                double bz = Math.cos(epsBind) * suz + Math.sin(epsBind) * tnz;
                double bd = bx * ex + by * ey + bz * ez;
                bx -= bd * ex; by -= bd * ey; bz -= bd * ez;
                double bl = Math.sqrt(bx * bx + by * by + bz * bz);
                if (bl > 1e-9) {
                    double ib = 1.0 / bl; bx *= ib; by *= ib; bz *= ib;
                    double cs = bx * rx + by * ry + bz * rz; if (cs > 1) cs = 1; if (cs < -1) cs = -1;
                    double crx = by * rz - bz * ry, cry = bz * rx - bx * rz, crz = bx * ry - by * rx;
                    double sgn = crx * ex + cry * ey + crz * ez;
                    double mag = cacos(cs);
                    mis = sgn < 0 ? -mag : mag;                            // signed angle bPerp → headRef about eBind
                    tau = -kOmega * mis;                                   // restoring: drives headRef → bPerp
                    // EQUAL AND OPPOSITE: −tau about eBind onto the filament, through the existing seg-torque slot
                    int dB = m * STRIDE;
                    bondData.set(dB + 9,  (float) (bondData.get(dB + 9)  - tau * ex));
                    bondData.set(dB + 10, (float) (bondData.get(dB + 10) - tau * ey));
                    bondData.set(dB + 11, (float) (bondData.get(dB + 11) - tau * ez));
                }
            }
            headTau.set(m, (float) tau); headMis.set(m, (float) mis);
            // --- Brownian thermostat (no reaction) ----------------------------------------------------
            double tauB = 0.0;
            if (brownOn != 0) tauB = Math.sqrt(2.0 * Constants.kT * gam / dt) * gauss(seed, tt, OMEGA_SALT + (long) m * 7919L);
            double dOm = (tau + tauB) * dt / gam;
            // --- rotate headRef about eBind by dOm (Rodrigues; headRef ⊥ eBind ⇒ the cross term is exact)
            double c = Math.cos(dOm), sn = Math.sin(dOm);
            double kx = ey * rz - ez * ry, ky = ez * rx - ex * rz, kz = ex * ry - ey * rx;   // eBind × headRef
            double nrx = c * rx + sn * kx, nry = c * ry + sn * ky, nrz = c * rz + sn * kz;
            double nl = Math.sqrt(nrx * nrx + nry * nry + nrz * nrz);
            if (nl > 1e-12) { double inl = 1.0 / nl; nrx *= inl; nry *= inl; nrz *= inl; }
            headRef.set(m, (float) nrx); headRef.set(N + m, (float) nry); headRef.set(2 * N + m, (float) nrz);
            headOmega.set(m, (float) (headOmega.get(m) + dOm));
        }
    }

    // ===================================================================================================
    // STAGE 5 — ASKEW EFFECTIVE STROKE (a local-frame REST-COORDINATE change, not an applied force)
    // ===================================================================================================
    /**
     * At the power-stroke transition (bound head, nucleotide ADP·Pi → ADP — the cocked→uncocked switch this
     * model already uses as the stroke), advance the BOUND interface by {@code epsStroke} in the LOCAL site
     * tangent plane: {@code bindAzim += mirrorSign*epsStroke}. Because the bond is a spring between the
     * head's F8 anchor and the material site, rotating the bond's rest position about the filament's own
     * axis gives the stroke a circumferential component of exactly {@code Ractin*epsStroke} in the local
     * frame — the mathematically equivalent local-frame rest change for a solver that represents the stroke
     * as a rest-coordinate switch. NO external tangential force is applied: the resulting force and its
     * reaction come entirely from the existing F8 pathway, which is a closed couple by construction.
     *
     * <p>Applied ONCE per stroke (the transition is detected against {@code prevNuc}), and only while the
     * head stays bound; on release the interface state is discarded with the bond.
     */
    public static void strokeSkew(IntArray boundSeg, IntArray nucleotideState, IntArray prevNuc,
            FloatArray bindAzim, DoubleArray chiP, IntArray counts) {
        int N = counts.get(0);
        double eps = chiP.get(6), mirror = chiP.get(13);
        int mode = (int) chiP.get(0);
        for (@Parallel int m = 0; m < N; m++) {
            int nu = nucleotideState.get(m), pv = prevNuc.get(m), bs = boundSeg.get(m);
            if (mode > 0 && eps != 0.0 && bs >= 0 && pv == MotorStore.NUC_ADPPI && nu == MotorStore.NUC_ADP)
                bindAzim.set(m, (float) (bindAzim.get(m) + mirror * eps));
            prevNuc.set(m, nu);
        }
    }

    // ===================================================================================================
    // TRUE LOCAL-FRAME ROTATION OF THE CONVERTER POWER STROKE
    // ===================================================================================================
    /**
     * Build the per-motor CONVERTER FRAME: the base triad {@code (bhat, econv, eup)} rotated by the signed
     * skew {@code epsConv} in the LOCAL actin-site frame of the bound site, plus the gauge offset that puts
     * the rotation centre at the binding interface.
     *
     * <h3>What this is, and what it is NOT</h3>
     * {@code epsBind} / {@code epsStroke} (§7/§8) are ACTIN-SIDE offsets: they move the attachment azimuth,
     * i.e. WHICH material point the zero-rest cross-bridge is tethered to. They do not rotate anything on the
     * motor. THIS kernel changes the motor's own converter kinematics: the plane in which the
     * nucleotide-driven converter swing happens is rotated, so the stroke DISPLACEMENT ITSELF acquires a
     * circumferential component. The actin site is untouched — {@code bindArc} and {@code bindAzim} are not
     * read for anything but the site's radial DIRECTION, and are never written here.
     *
     * <h3>The construction</h3>
     * The whole explicit-S2 converter block is generated from the orthonormal base triad:
     * <pre>
     *   uB   = eup*cos(phi) + bhat*sin(phi)          C   = P + lb*uB
     *   xF8  = C + R_econv(psi) d0                   xH  = C − R_econv(psi) rc
     *   d0   = bhat*(rF8x−rCx) + eup*(rF8y−rCy)      rc  = bhat*rCx + eup*rCy
     * </pre>
     * so the stroke plane is span{bhat, eup} with normal econv, and the arm {@code x̃(phi,psi) = xF8 − P} is an
     * EQUIVARIANT function of the triad: rotating the triad by R rotates the arm, {@code x̃(phi,psi; R·F) =
     * R·x̃(phi,psi; F)}. Hence with the triad rotated the stroke displacement is exactly
     * {@code Δr(eps) = R·Δr(0)} — a pure rotation of the converter-driven motion, not an added force.
     *
     * <p>The rotation is by {@code epsConv} about {@code k = −mirrorSign · nSite}, chosen so that
     * {@code R·uSite = cos(eps)·uSite + sin(eps)·tSite} with {@code tSite = mirrorSign·(uSite × nSite)} — the
     * task's required stroke direction. {@code R(0) = I} exactly, so {@code eps = 0} recovers the canonical
     * motor and the feature is never wired at all when the angle is zero.
     *
     * <h3>Gauge (the rotation CENTRE)</h3>
     * A bare basis rotation pivots the converter about the S2 pivot P, which displaces the F8 anchor by
     * {@code |x̃|·eps} at the instant a head binds — a large STATIC attachment strain that would be
     * indistinguishable from the old actin-side offsets. The default gauge removes it: the offset
     * {@code Δ = x̃_ref − R·x̃_ref}, with {@code x̃_ref} the arm at the model's own REFERENCE BINDING POSE
     * ({@code phi = PHI_PRE}, {@code psi = psiActin} — the pose the 8-gate bind certifies), makes the geometry
     * {@code xF8 = P + x̃_ref + R(x̃ − x̃_ref)}, i.e. the converter plane rotates ABOUT THE BINDING INTERFACE.
     * The physical statement: the head is docked on actin, so the docked interface — not the distant S2
     * pivot — is what the converter swings about. Δ is a rest-GEOMETRY offset, not a force, and it cancels
     * identically out of the stroke increment either way ({@code Δr(eps) = R·Δr(0)} for both gauges).
     * {@code -converter-skew-gauge off} restores the bare pivot rotation as a control.
     *
     * <h3>Frames, covariance, and what is NOT rotated</h3>
     * Every direction here is a simulated material direction (the filament's own {@code uVec}/{@code yVec} and
     * the motor's own base triad); no laboratory axis enters, and the frame is rebuilt from the CURRENT
     * material frame every step, so it rolls, bends and translates with the bound filament (no latched lab
     * anchor). The S2 anchor geometry is NOT rotated: the beam nodes, the clamped base tangent {@code g4Tan},
     * the anchored base point {@code g4E} and the coverslip floor normal all stay in {@code frame} and are
     * read unrotated by the solver. An UNBOUND motor gets flag 0 ⇒ the canonical branch ⇒ it is exactly the
     * canonical motor.
     *
     * <p><b>Ordering.</b> This task runs FIRST in the step, before {@code matBeamGeom}, so geometry, head
     * placement, the bond, the gates and the solve all see ONE converter frame within a step. It therefore
     * reads the binding state as of the start of the step: a head that binds during step t gets its converter
     * frame at step t+1 (a one-step establishment lag, identical on both runners).
     *
     * <h3>STATE-GATED activation (noncanonical, default-off — {@code chiP[19]})</h3>
     * By default the rotation is active for the WHOLE bound episode, including the ADP·Pi pre-stroke dwell. §23
     * measured that the pre-stroke dwell contributes an eps-ODD impulse {@code J_pre} of the OPPOSITE sign to the
     * stroke, growing FASTER than sin(eps) and cancelling 45–77 % of the stroke channel. State gating removes
     * exactly that: while the motor sits in the pre-stroke rest coordinate the converter frame is CANONICAL, and
     * the configured skew switches on at the same chemical transition that switches the converter rest angle.
     *
     * <p><b>The state predicate is {@code thetaS} itself</b> ({@code q[2N+m]}), which {@link MatSoaSlice#matCock}
     * writes as {@code nuc == NUC_ADPPI ? PRESTROKE_THETAS : ADP_THETAS}. Reading it here means the skew and the
     * rest-coordinate switch are driven by ONE quantity from ONE source — no duplicated state semantics, no
     * guessed nucleotide integer, and no new kernel argument. {@code chiP[20]} carries the pre/post discriminant
     * {@code ½(PRESTROKE_THETAS + ADP_THETAS)}, built host-side from the SAME {@code cockP} constants.
     *
     * <p><b>Ordering (load-bearing — see §24.2).</b> This task runs FIRST in the step, i.e. BEFORE {@code chem}
     * and {@code cock}, so at that point {@code thetaS} is still the PREVIOUS step's value. The stroke is
     * physically realised by {@code matS2SolveStep} at the END of the step, using the {@code thetaS} that
     * {@code cock} wrote during THIS step. So when gating is on, the harness invokes this kernel a SECOND time
     * immediately after {@code cock}; that second invocation is what makes the converter frame and the rest-angle
     * switch describe ONE power-stroke event in the solve. Without it the skew would activate one full step late
     * and the first (largest) stroke increment would be taken unrotated. The second invocation is added ONLY when
     * gating is on, so the default path's task list is byte-unchanged.
     *
     * <p>{@code convF} (stride 13, planar c·N+m): [0..2] b*, [3..5] econv*, [6..8] eup*, [9..11] gauge offset
     * (µm), [12] flag (0 ⇒ the canonical branch). chiP[16]=epsConv (rad), [17]=gauge on, [18]=phiRef,
     * [19]=stateGated, [20]=thetaS pre/post discriminant.
     */
    public static void convFrameStep(IntArray boundSeg, FloatArray filUVec, FloatArray filYVec, FloatArray bindAzim,
            DoubleArray frame, DoubleArray params, DoubleArray q, DoubleArray convF,
            DoubleArray chiP, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3);
        int mode = (int) chiP.get(0);
        double eps = chiP.get(16), gauge = chiP.get(17), phiRef = chiP.get(18), mirror = chiP.get(13);
        double gated = chiP.get(19), thetaDisc = chiP.get(20);
        double thPre = chiP.get(21), thPost = chiP.get(22);
        int ramp = (int) chiP.get(23); double onset = chiP.get(24);
        for (@Parallel int m = 0; m < N; m++) {
            int s = boundSeg.get(m);
            if (mode == 0 || eps == 0.0 || s < 0) { convF.set(12 * N + m, 0.0); continue; }
            // STATE GATE (§24): pre-stroke rest coordinate ⇒ canonical converter frame (exactly the eps=0 motor).
            if (gated != 0.0 && q.get(2 * N + m) <= thetaDisc) { convF.set(12 * N + m, 0.0); continue; }
            // ---- §25 PROGRESS RAMP: eps_eff = eps · f(qTheta), qTheta from the STORED converter coordinate ----
            // phi/psi here are the PREVIOUS step's converged solve output (§25.1 audit option B) — a stored state
            // variable, so this is NOT circular. Inlined rather than calling ChiralSiteSystem.rampF (device-side
            // call limits); fixture 404 gates the two against each other.
            double epsE = eps;
            if (ramp != 0) {
                double qq = (q.get(N + m) - q.get(m) - thPre) / (thPost - thPre);
                if (qq < 0.0) qq = 0.0; else if (qq > 1.0) qq = 1.0;
                double fq;
                if (ramp == 1) fq = qq;
                else if (ramp == 2) fq = qq * qq * (3.0 - 2.0 * qq);
                else {
                    if (qq <= onset) fq = 0.0;
                    else { double z = (qq - onset) / (1.0 - onset); fq = z * z * (3.0 - 2.0 * z); }
                }
                epsE = eps * fq;
                if (epsE == 0.0) { convF.set(12 * N + m, 0.0); continue; }   // f = 0 ⇒ exactly the canonical motor
            }
            double bx = frame.get(m),         by = frame.get(N + m),      bz = frame.get(2 * N + m);
            double ex = frame.get(3 * N + m), ey = frame.get(4 * N + m),  ez = frame.get(5 * N + m);   // econv
            double ux = frame.get(6 * N + m), uy = frame.get(7 * N + m),  uz = frame.get(8 * N + m);   // eup
            // ---- local site frame at the ATTACHMENT azimuth (the bond's own moment-arm direction) ----------
            double sux = filUVec.get(s), suy = filUVec.get(nSeg + s), suz = filUVec.get(2 * nSeg + s);
            double yx = filYVec.get(s), yy = filYVec.get(nSeg + s), yz = filYVec.get(2 * nSeg + s);
            double zx = suy * yz - suz * yy, zy = suz * yx - sux * yz, zz = sux * yy - suy * yx;
            double zl = zx * zx + zy * zy + zz * zz;
            if (zl > 1e-30) { double iz = 1.0 / Math.sqrt(zl); zx *= iz; zy *= iz; zz *= iz; }
            double ph = bindAzim.get(m);
            double cph = Math.cos(ph), sph = Math.sin(ph);
            double nx = cph * yx + sph * zx, ny = cph * yy + sph * zy, nz = cph * yz + sph * zz;   // outward radial
            // rotation axis: R_k(eps)·uSite = cos(eps)·uSite + sin(eps)·tSite  with  tSite = mirror·(uSite×nSite)
            double kx = -mirror * nx, ky = -mirror * ny, kz = -mirror * nz;
            double kl = Math.sqrt(kx * kx + ky * ky + kz * kz);
            if (!(kl > 1e-12)) { convF.set(12 * N + m, 0.0); continue; }
            double ik = 1.0 / kl; kx *= ik; ky *= ik; kz *= ik;
            double c = Math.cos(epsE), sn = Math.sin(epsE), omc = 1.0 - c;
            // ---- Rodrigues of the three base vectors (inlined; no device-side helper allocation) -----------
            double dB = kx * bx + ky * by + kz * bz;
            double rbx = bx * c + (ky * bz - kz * by) * sn + kx * dB * omc;
            double rby = by * c + (kz * bx - kx * bz) * sn + ky * dB * omc;
            double rbz = bz * c + (kx * by - ky * bx) * sn + kz * dB * omc;
            double dE = kx * ex + ky * ey + kz * ez;
            double rex = ex * c + (ky * ez - kz * ey) * sn + kx * dE * omc;
            double rey = ey * c + (kz * ex - kx * ez) * sn + ky * dE * omc;
            double rez = ez * c + (kx * ey - ky * ex) * sn + kz * dE * omc;
            double dU = kx * ux + ky * uy + kz * uz;
            double rux = ux * c + (ky * uz - kz * uy) * sn + kx * dU * omc;
            double ruy = uy * c + (kz * ux - kx * uz) * sn + ky * dU * omc;
            double ruz = uz * c + (kx * uy - ky * ux) * sn + kz * dU * omc;
            convF.set(m, rbx);           convF.set(N + m, rby);           convF.set(2 * N + m, rbz);
            convF.set(3 * N + m, rex);   convF.set(4 * N + m, rey);       convF.set(5 * N + m, rez);
            convF.set(6 * N + m, rux);   convF.set(7 * N + m, ruy);       convF.set(8 * N + m, ruz);
            // ---- gauge offset: put the rotation centre at the REFERENCE binding interface -------------------
            double ofx = 0.0, ofy = 0.0, ofz = 0.0;
            if (gauge != 0.0) {
                double lb = params.get(m), rF8x = params.get(N + m), rF8y = params.get(2 * N + m);
                double rCx = params.get(3 * N + m), rCy = params.get(4 * N + m);
                double psiRef = q.get(3 * N + m);                              // psiActin (per motor)
                double cpr = Math.cos(phiRef), spr = Math.sin(phiRef);
                double uBx = ux * cpr + bx * spr, uBy = uy * cpr + by * spr, uBz = uz * cpr + bz * spr;
                double d0x = bx * (rF8x - rCx) + ux * (rF8y - rCy);
                double d0y = by * (rF8x - rCx) + uy * (rF8y - rCy);
                double d0z = bz * (rF8x - rCx) + uz * (rF8y - rCy);
                double cps = Math.cos(psiRef), sps = Math.sin(psiRef);
                double axx = d0x * cps + (ey * d0z - ez * d0y) * sps;          // rotConv(d0, psiRef, econv)
                double ayy = d0y * cps + (ez * d0x - ex * d0z) * sps;
                double azz = d0z * cps + (ex * d0y - ey * d0x) * sps;
                double xrx = lb * uBx + axx, xry = lb * uBy + ayy, xrz = lb * uBz + azz;   // arm at the ref pose
                double dR = kx * xrx + ky * xry + kz * xrz;
                double Rx = xrx * c + (ky * xrz - kz * xry) * sn + kx * dR * omc;
                double Ry = xry * c + (kz * xrx - kx * xrz) * sn + ky * dR * omc;
                double Rz = xrz * c + (kx * xry - ky * xrx) * sn + kz * dR * omc;
                ofx = xrx - Rx; ofy = xry - Ry; ofz = xrz - Rz;
            }
            convF.set(9 * N + m, ofx); convF.set(10 * N + m, ofy); convF.set(11 * N + m, ofz);
            convF.set(12 * N + m, 1.0);
        }
    }

    // ===================================================================================================
    // HOST-SIDE ANALYSIS HELPERS (not device kernels)
    // ===================================================================================================
    /** Axial (roll-driving) component of the bond's segment-side torque for motor m: TS·uSeg (N·m). */
    static double axialTorque(FloatArray bondData, FloatArray filUVec, IntArray boundSeg, int m, int nSeg) {
        int s = boundSeg.get(m); if (s < 0) return 0.0;
        int d = m * STRIDE;
        return bondData.get(d + 9) * filUVec.get(s) + bondData.get(d + 10) * filUVec.get(nSeg + s)
             + bondData.get(d + 11) * filUVec.get(2 * nSeg + s);
    }
    /**
     * Tangential (circumferential) component of the bond's segment-side FORCE, expressed in the local material
     * frame AT THE ATTACHMENT azimuth (N). This is the frame in which the axial torque identity
     * {@code tau_axial = Ractin * F_tangential} is exact, because {@code Ractin*nHat} at that azimuth IS the
     * bond's moment arm.
     */
    static double tangentialForce(FloatArray bondData, FloatArray filUVec, FloatArray filYVec, FloatArray bindAzim,
                                  IntArray boundSeg, int m, int nSeg, double mirror) {
        int s = boundSeg.get(m); if (s < 0) return 0.0;
        double ux = filUVec.get(s), uy = filUVec.get(nSeg + s), uz = filUVec.get(2 * nSeg + s);
        double yx = filYVec.get(s), yy = filYVec.get(nSeg + s), yz = filYVec.get(2 * nSeg + s);
        double zx = uy * yz - uz * yy, zy = uz * yx - ux * yz, zz = ux * yy - uy * yx;
        double zl = Math.sqrt(zx * zx + zy * zy + zz * zz); if (zl > 1e-30) { zx /= zl; zy /= zl; zz /= zl; }
        double ph = bindAzim.get(m);
        double c = Math.cos(ph), sn = Math.sin(ph);
        double nx = c * yx + sn * zx, ny = c * yy + sn * zy, nz = c * yz + sn * zz;
        double tx = mirror * (uy * nz - uz * ny), ty = mirror * (uz * nx - ux * nz), tz = mirror * (ux * ny - uy * nx);
        int d = m * STRIDE;
        return bondData.get(d + 6) * tx + bondData.get(d + 7) * ty + bondData.get(d + 8) * tz;
    }
}
