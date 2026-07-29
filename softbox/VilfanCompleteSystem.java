package softbox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * VILFAN-COMPLETE REFERENCE MODEL — a standalone, additive, default-off reconstruction of
 * <p>
 * Andrej Vilfan, "Twirling motion of actin filaments in gliding assays with non-processive
 * myosin motors", Biophys. J. 97(4):1130-1137 (2009); preprint arXiv:0906.0784v1.
 * <p>
 * This class implements Vilfan's COMPLETE model: his lattice, his attachment law (Eqs 1-2), his
 * four-state kinetic cycle with his rates (Table I), his conjugate force and torque law (Eq 4),
 * his quasi-static force/torque equilibrium (Eq 5) and his exact continuous-time (Gillespie)
 * event scheme (Simulation algorithm, steps 1-7).
 * <p>
 * NOTHING here is SoftBox physics. It shares no state, no kernel and no parameter with the
 * canonical SoftBox motor, the explicit-S2 motor, the graded-binding hybrid or the hard-gate
 * comparator. It uses SoftBox only as a host repository (build, scripts, run-log conventions).
 * It is CPU-only, double precision, single threaded, and touches no TornadoVM type.
 * <p>
 * DELIBERATE OMISSIONS (Vilfan's own model assumptions — NOT SoftBox simplifications):
 * no Brownian motion, no drag, no lever/converter, no S2, no strain-dependent rates, no reverse
 * transitions, no transverse or 3-D filament motion. The filament has exactly two mechanical
 * degrees of freedom, X (axial translation, nm) and Theta (axial rotation, rad).
 * <p>
 * UNITS. Lengths nm, forces pN, energies pN*nm, angles rad, rates 1/s, time s.
 */
public final class VilfanCompleteSystem {

    /* ==================== motor chemical states (Fig 1B) ==================== */

    public static final int DETACHED = 0;   // competent to attach at site-specific rate kA_i (Eq 2)
    public static final int PRE_PS   = 1;   // bound, delta = 0     -> power stroke at kPS
    public static final int POST_PS  = 2;   // bound, delta = d     -> ADP release at k_minusADP
    public static final int RIGOR    = 3;   // bound, delta = d     -> ATP-induced detachment at kD
    public static final String[] STATE_NAME = {"detached", "pre-PS", "post-PS", "rigor"};

    static final double TWO_PI = 2.0 * Math.PI;

    /* ==================== configuration ==================== */

    /**
     * Every published value is a field so that a run records exactly what it used. Defaults are
     * Vilfan's Table I, paper-exact lattice. Nothing is tuned.
     */
    public static final class Config implements Cloneable {
        /** azimuthal advance per subunit = latSign * (latP/latQ) * 2*pi, held as an EXACT rational
         *  of a full turn so that i*theta0 mod 2*pi carries no accumulated round-off. Paper: 13/28
         *  (theta0 = -167.142857...deg). SoftBox native: 37/80 (theta0 = -166.5 deg). */
        public int latP = 13, latQ = 28;
        /** -1 = native (left-handed genetic helix, theta0 < 0); +1 = MIRRORED helicity. */
        public int latSign = -1;
        /** axial rise per subunit, nm (Table I a = 2.75; SoftBox native 2.7). */
        public double aNm = 2.75;

        public double lengthUm    = 5.5;    // Table I  l
        public double densityPerUm= 20.0;   // Table I  rho (1-D)

        public double kA          = 50.0;     // Table I  s^-1
        public double kPS         = 10000.0;  // Table I  s^-1
        public double kADP        = 1000.0;   // Table I  k_-ADP, s^-1
        public double kD          = 5.0;      // Table I  kD = (5 uM^-1 s^-1)[ATP]; 5 s^-1 <=> 1 uM ATP
        public double dNm         = 8.0;      // Table I  power stroke size d
        public double K           = 0.5;      // Table I  pN/nm  longitudinal stiffness
        public double alpha       = 4.0;      // Table I  Ktheta = alpha * kBT
        public double kBT         = 4.14;     // Table I  4.14e-21 J = 4.14 pN*nm

        /** +1 = myosin drives the filament toward +X (delta = +d). -1 reverses actin polarity. */
        public int polarity = +1;

        /** candidate half-window in subunits about the motor's nearest site (Sec: candidate
         *  enumeration). The paper places no cutoff; the Gaussian longitudinal factor supplies one.
         *  Convergence is gated in Stage 3-B. */
        public int window = 12;
        /** the paper's Discussion implies the SIMULATION enforces one head per site (it lists
         *  single occupancy among the things the ANALYTICS neglects). Configurable; see report. */
        public boolean singleOccupancy = true;
        /** "randomly along the distance covered ... with an average linear density rho" =>
         *  a homogeneous Poisson process (default). Alternatives kept for sensitivity only. */
        public String placement = "poisson";   // poisson | uniformN | regular

        public long seed = 101L;

        /** analysed travel target, um; extended adaptively until turnsTarget turns accrue. */
        public double travelUm    = 5.0;
        public double warmupUm    = 1.0;
        public double turnsTarget = 8.0;
        public double travelCapUm = 60.0;      // hard cap on adaptive extension
        /** If > 0 the analysis window is defined by TIME, not travel: warm-up ends at warmupTimeS
         *  and the run ends at analysisTimeS after it. A travel-threshold window is biased when the
         *  filament can move backward (it stops preferentially on a forward fluctuation); a
         *  fixed-time window is unbiased by construction. */
        public double warmupTimeS = 0.0, analysisTimeS = 0.0;
        /** absolute safety nets. A zero-velocity control (d = 0) never reaches a travel target, so
         *  every run also carries an event and a simulated-time cap. Hitting one is REPORTED. */
        public long   maxEvents   = 200_000_000L;
        public double maxSimTimeS = Double.POSITIVE_INFINITY;

        /** measure the no-depletion (shadow) attachment landscape alongside the real run. */
        public boolean noDepletionControl = false;

        public double equilTolRad = 1e-13;
        public int    equilMaxIter = 200;

        /* ---------- restored realism: finite filament drag (overdamped mechanics) ----------
         * "quasistatic" = Vilfan Eq (5), the validated reference. "overdamped" replaces it with
         *     gammaX     dX/dt     = sum_j F_j
         *     gammaTheta dTheta/dt = sum_j M_j
         * using the SAME Vilfan force and torque laws. Nothing else changes: no inertia, no
         * Brownian term, no extra degree of freedom, no load dependence of any chemical rate. */
        public String mechanics = "quasistatic";
        /** solvent viscosity, Pa*s. Assay reference 0.01 (CLAUDE.md viscosity study). */
        public double etaPaS = VilfanDrag.ETA_ASSAY;
        /** filament radius for the drag formula, microns (Constants.actinWidth/2 = 0.0035). */
        public double filRadiusUm = VilfanDrag.RADIUS_UM;
        /** VALIDATION ONLY (gate D): multiplies both drag coefficients. dragScale -> 0 must
         *  recover the quasi-static result. Never anything but 1.0 in a physics arm. */
        public double dragScale = 1.0;
        /** relative tolerance on the cumulative-hazard quadrature (gate C convergence axis). */
        public double hazTolRel = 1e-8;
        /** relative tolerance on the located event time. */
        public double rootTolRel = 1e-13;
        /** transient horizon in units of the slowest relaxation time; exp(-40) ~ 4e-18. */
        public double settleFactor = 40.0;

        /** GATE ONLY: re-derive every located event's cumulative hazard by an independent uniform
         *  fine-grid trapezoid along the same analytic trajectory, and record the discrepancy. */
        public boolean hazardCrossCheck = false;
        public int     hazardCrossGrid  = 20000;
        /** axial-Brownian gate: check every Nth substep, refining it to this bridge depth. */
        public long    hazardCheckStride = 997;
        public int     hazardCheckDepth  = 4;

        /* ---- restored realism #2: FDT-consistent AXIAL Brownian motion (roll stays deterministic) ---- */
        public boolean axialBrownian = false;
        /** base anchor step for the axial stochastic grid, seconds. Refinement halves it. */
        public double anchorDtS = 5.0e-6;
        /** refinement level L: the realised step is anchorDtS / 2^L, inserted by OU BRIDGE so the
         *  coarse and refined trajectories are the same stochastic path (Stage 4-D axis). */
        public int refineLevel = 0;
        /** max bridge halvings when locating the terminal event inside an accepted substep. */
        public int bridgeMaxLevel = 14;
        /** hysteresis bands (nm) for scale-aware zone-centre recrossing counting. */
        public double[] recrossBandsNm = {0.0, 0.5, 1.0, 2.7, 5.0};

        public boolean overdamped() { return "overdamped".equals(mechanics); }
        public boolean brownian()   { return overdamped() && axialBrownian; }

        public Config copy() {
            try { return (Config) super.clone(); } catch (CloneNotSupportedException e) { throw new AssertionError(e); }
        }
        public double theta0Rad() { return latSign * ((double) latP / latQ) * TWO_PI; }
        public double theta0Deg() { return latSign * ((double) latP / latQ) * 360.0; }
        public double kTheta()    { return alpha * kBT; }
        public double delta()     { return polarity * dNm; }
    }

    /* ==================== derived lattice geometry ==================== */

    private final Config c;
    private final double[] azimTable;   // azimTable[m] = (m*theta0) mod 2pi, exact rational, m in [0,latQ)
    private final int nSites;
    private final double a, K, kTheta, kBT, kA, delta;
    private final double invKBT;

    /** effective long-pitch ("superhelix") groove slope, rad/nm: the azimuthal advance per unit
     *  axial length along the two-start strand. Used ONLY to define the reporting coordinate x
     *  (Vilfan's Eq 6) and the target-zone period L; it never enters the physics. */
    private final double grooveSlope;
    private final double LNm;

    /** whole-filament drag, working units. Zero unless mechanics = overdamped. */
    private final double gammaX, gammaTheta;

    public VilfanCompleteSystem(Config cfg) {
        this.c = cfg.copy();
        this.a = c.aNm;
        this.K = c.K;
        this.kTheta = c.kTheta();
        this.kBT = c.kBT;
        this.kA = c.kA;
        this.invKBT = 1.0 / c.kBT;
        this.delta = c.delta();
        this.nSites = (int) Math.round(c.lengthUm * 1000.0 / c.aNm);
        this.azimTable = new double[c.latQ];
        for (int m = 0; m < c.latQ; m++) {
            long r = Math.floorMod((long) c.latP * (long) m, (long) c.latQ);
            azimTable[m] = c.latSign * ((double) r / (double) c.latQ) * TWO_PI;
        }
        // effective groove: azimuth advance over 2 subunits (the two-start long-pitch strand)
        double d2 = wrapPi(2.0 * c.theta0Rad());
        this.grooveSlope = (c.aNm == 0.0) ? 0.0 : d2 / (2.0 * c.aNm);
        this.LNm = (grooveSlope == 0.0) ? Double.POSITIVE_INFINITY : Math.PI / Math.abs(grooveSlope);
        if (c.overdamped()) {
            this.gammaX     = c.dragScale * VilfanDrag.gammaXwork(c.etaPaS, c.lengthUm, c.filRadiusUm);
            this.gammaTheta = c.dragScale * VilfanDrag.gammaThetaWork(c.etaPaS, c.lengthUm, c.filRadiusUm);
        } else { this.gammaX = 0.0; this.gammaTheta = 0.0; }
        this.DX = (gammaX > 0) ? c.kBT / gammaX : 0.0;
    }

    public Config config()   { return c; }
    public int    nSites()   { return nSites; }
    public double targetZonePeriodNm() { return LNm; }
    public double grooveSlopeRadPerNm(){ return grooveSlope; }

    /** azimuth of site i relative to the filament frame: (i*theta0) mod 2pi, EXACT (no drift). */
    public double baseAzim(int i) { return azimTable[Math.floorMod(i, c.latQ)]; }

    /* ==================== finite-drag coefficients (overdamped mode only) ==================== */

    /** whole-filament axial drag, pN*s/nm (zero in quasi-static mode — never referenced there). */
    public double gammaX()     { return gammaX; }
    /** whole-filament ROLL drag about the long axis, pN*nm*s/rad. */
    public double gammaTheta() { return gammaTheta; }

    /** wrap to (-pi, pi] — Vilfan's "n chosen such that Theta + i*theta0 + 2*pi*n falls into [-pi,pi]". */
    public static double wrapPi(double x) { return x - TWO_PI * Math.rint(x / TWO_PI); }

    /** algorithm step 3: dt = ktotal^-1 * ln(1/r), r uniform in (0,1]. */
    public static double expWait(SplittableRandom rng, double ktotal) {
        double u = 1.0 - rng.nextDouble();               // (0,1]
        return Math.log(1.0 / u) / ktotal;
    }

    /** the exact conditional site-probability vector for a detached motor (algorithm step 4). */
    public double[] siteProbabilities(double X0, double Theta0, double xMj, int lo, int hi, boolean reversed) {
        double[] p = new double[hi - lo + 1];
        double s = 0.0;
        if (!reversed) for (int i = lo; i <= hi; i++) { p[i - lo] = siteHazard(X0, Theta0, xMj, i); s += p[i - lo]; }
        else           for (int i = hi; i >= lo; i--) { p[i - lo] = siteHazard(X0, Theta0, xMj, i); s += p[i - lo]; }
        if (s > 0) for (int k = 0; k < p.length; k++) p[k] /= s;
        return p;
    }

    /** wrap to [-P/2, P/2). */
    public static double wrapP(double x, double P) { return x - P * Math.floor(x / P + 0.5); }

    /* ---- Eq (1): elastic energy cost of binding head anchored at xM to site i ---- */
    public double bindEnergy(double X, double Theta, double xM, int i) {
        double xi  = X + i * a - xM;                 // longitudinal mismatch, Eq (1) sign
        double th  = wrapPi(Theta + baseAzim(i));    // angular mismatch, Eq (1) sign
        return 0.5 * K * xi * xi + 0.5 * kTheta * th * th;
    }

    /* ---- Eq (2): site-specific attachment rate ---- */
    public double siteHazard(double X, double Theta, double xM, int i) {
        return kA * Math.exp(-bindEnergy(X, Theta, xM, i) * invKBT);
    }

    /* ---- Eq (4): force and torque of a head bound to site i ---- */
    public double headForce(double X, double xM, int i, double deltaJ)  { return K * (xM + deltaJ - X - i * a); }
    public double headTorque(double Theta, int i)                       { return -kTheta * wrapPi(Theta + baseAzim(i)); }

    /** Vilfan's strain (analytical section): xi_i = xM - X - i*a. Note this is the NEGATIVE of the
     *  longitudinal mismatch appearing inside Eq (1); Eq (4) reads F = K*(xi + delta). */
    public double strain(double X, double xM, int i) { return xM - X - i * a; }

    /**
     * Reporting coordinate: motor root position relative to the centre of the nearest target zone.
     * <p>
     * Vilfan Eq (6) is printed as x = xM - X - Theta*(L/pi). That sign is INCONSISTENT with his own
     * xdot = -(v - (L/pi)*omega) stated immediately below it, with Eq (8) (which then reduces exactly
     * to the site azimuth Theta + i*theta0), and with the exact discrete lattice. We implement the
     * self-consistent PLUS form, x = xM - X + Theta/grooveSlope, wrapped into [-L/2, L/2).
     * Positive x = BEFORE the zone centre for a filament advancing in +X.
     */
    public double zoneCoord(double X, double Theta, double xM) {
        if (grooveSlope == 0.0) return 0.0;
        return wrapP(xM - X + Theta / grooveSlope, LNm);
    }

    /* ==================== simulation state ==================== */

    public static final class Result {
        public Config cfg;
        public String armId;
        public int    nMotors, nSites;
        public double lengthNm, fieldLoNm, fieldHiNm, LNm, grooveSlope;

        public double tEnd, xEnd, thetaEnd;
        public double tWarm, xWarm, thetaWarm;
        /** analysed-window steady estimates */
        public double velUmPerS;        // (X_end - X_warm)/(t_end - t_warm), um/s
        public double omegaRadPerS;     // (Theta_end - Theta_warm)/(t_end - t_warm)
        public double turns;            // |dTheta|/2pi over the analysed window
        public double pitchUmPerTurn;   // 2*pi*dX/dTheta, um/turn (signed; negative = left-handed)
        public double invPitchPerUm;    // 1/pitch, um^-1

        /** least-squares slopes over the analysed window (robustness cross-check on the endpoints) */
        public double velLsq, omegaLsq;
        /** stationarity: the analysed window split in two equal halves of SIMULATED TIME */
        public double velFirstHalf, velSecondHalf, omegaFirstHalf, omegaSecondHalf;
        /** mean torque INJECTED AT ATTACHMENT, M_A = -Ktheta * theta_A, pN*nm. The equilibrium
         *  per-head torque is identically zero (that is Eq 5), so this is the signed, mirror-odd
         *  torque that actually drives the rotation. */
        public double meanAttachTorque;

        public long   nEvents, nAttach, nStroke, nAdpRelease, nDetach;
        public double meanBound, dutyRatio;
        public double[] stateOccupancy = new double[4];   // time-weighted fractions

        /** attachment statistics over the analysed window */
        public double meanXa, sdXa;      // <x_A>, nm  (target-zone coordinate at binding)
        public double meanXiA, sdXiA;    // <xi_A>, nm (longitudinal strain at binding)
        public double meanThA, sdThA;    // <theta_A>, rad (angular mismatch at binding)
        public double meanSiteTorque;    // <M^j> over bound heads, time weighted, pN*nm
        public double meanSiteForce;     // <F^j> over bound heads, time weighted, pN
        public double meanTotalTorque;   // <sum_j M^j>, pN*nm  (should be ~0)
        public double meanTotalForce;    // <sum_j F^j>, pN     (should be ~0)

        /** numerical health */
        public double maxForceResid, maxTorqueResid;
        public long   equilIterTotal, equilBranchChanges, equilNonConverged;
        public long   nbZeroEvents;

        /** no-depletion shadow control (flux-weighted, no physical effect) */
        public boolean haveShadow;
        public double  shadowMeanXa, shadowMeanThA;

        /** histogram of x at attachment, over [-L/2, L/2) */
        public int[] xaHist = new int[40];
        /** histogram of the no-depletion flux over x */
        public double[] shadowHist = new double[40];

        public boolean travelCapHit;
        /* ---- finite-drag (overdamped) diagnostics ---- */
        public String  mechanics = "quasistatic";
        public double  etaPaS, gammaX, gammaTheta;      // pN*s/nm, pN*nm*s/rad
        public double  tauXmed, tauThetaMed;            // s, from the MEDIAN bound-head count
        public double  medianNb;
        public double  meanInterEventS;                 // analysed window
        public double  zonePassageS;                    // L / |v - omega*L/pi|
        public double  maxDynResidF, maxDynResidM;      // dynamic closure residuals
        public long    hazEvals, rootIters, branchCrossings, degenerateBranch, transientRootEvents;
        /* ---- axial Brownian (Stage 5) ---- */
        public boolean axialBrownian;
        public double  DXnm2PerS, anchorDtS, sdXconstrainedNm;
        public int     refineLevel;
        public long    nSubsteps, nBridgeDraws, nFreeDiffSteps, zoneCentreCrossRaw;
        public double  pathLenNm, netFwdNm, maxBackNm;
        public long[]  recFwd = new long[0], recBwd = new long[0];
        public double[] recBands = new double[0];
        public long    xcheckN;
        public double  xcheckMaxRel, xcheckMeanRel, xcheckBias, xcheckBiasSem, maxEventJumpX, maxEventJumpTheta;
        public long    nEventsAnalysed;
        public double  wallClockS;
        public String  note = "";
    }

    // live state
    private double X, Theta;
    private double[] xM;
    private int[] state;
    private int[] siteOf;
    private boolean[] occupied;
    private double[] hazCache;      // per-motor total attachment hazard (detached motors only)

    private long equilIterTotal, equilBranchChanges, equilNonConverged, nbZeroEvents;
    private double maxForceResid, maxTorqueResid;

    /* ==================== the motor field (algorithm step 1) ==================== */

    private void placeMotors(double loNm, double hiNm, SplittableRandom rng) {
        List<Double> pos = new ArrayList<>();
        double rhoPerNm = c.densityPerUm / 1000.0;
        switch (c.placement) {
            case "poisson" -> {                    // exact homogeneous Poisson process
                double x = loNm;
                while (true) {
                    double u = 1.0 - rng.nextDouble();          // (0,1]
                    x += -Math.log(u) / rhoPerNm;
                    if (x >= hiNm) break;
                    pos.add(x);
                }
            }
            case "uniformN" -> {                   // exactly N = round(rho*L) uniform positions
                int n = (int) Math.round(rhoPerNm * (hiNm - loNm));
                for (int k = 0; k < n; k++) pos.add(loNm + rng.nextDouble() * (hiNm - loNm));
                pos.sort(Double::compare);
            }
            case "regular" -> {                    // evenly spaced (control only)
                int n = (int) Math.round(rhoPerNm * (hiNm - loNm));
                for (int k = 0; k < n; k++) pos.add(loNm + (k + 0.5) * (hiNm - loNm) / n);
            }
            default -> throw new IllegalArgumentException("placement: " + c.placement);
        }
        xM = new double[pos.size()];
        for (int k = 0; k < xM.length; k++) xM[k] = pos.get(k);
        Arrays.sort(xM);
    }

    /* ==================== Eq (5): quasi-static equilibrium ==================== */

    /**
     * Solve sum_j F^j = 0 and sum_j M^j = 0 for (X, Theta).
     * <p>
     * The two equations DECOUPLE (Eq 4: F depends only on X, M only on Theta), so X is closed-form
     * exact. Theta is closed-form once the branch integers n_j are fixed, but n_j itself depends on
     * Theta; we take the branch continuation from the CURRENT Theta, which is the physically correct
     * quasi-static prescription (follow the local energy minimum the filament is already in). The
     * fixed-point map has zero derivative away from branch boundaries, so it converges in a single
     * iteration unless a head crosses a branch, and in a handful of iterations when one does.
     */
    private int equilibrate() {
        int nb = 0;
        double sx = 0.0;
        for (int j = 0; j < xM.length; j++) {
            if (state[j] == DETACHED) continue;
            double dj = (state[j] == PRE_PS) ? 0.0 : delta;
            sx += xM[j] + dj - siteOf[j] * a;
            nb++;
        }
        if (nb == 0) { nbZeroEvents++; return 0; }     // no bound head => filament is free; freeze

        X = sx / nb;

        int iters = 0;
        if (kTheta > 0.0) {
            double corr;
            do {
                double s = 0.0;
                for (int j = 0; j < xM.length; j++) {
                    if (state[j] == DETACHED) continue;
                    s += wrapPi(Theta + baseAzim(siteOf[j]));
                }
                corr = s / nb;
                Theta -= corr;
                iters++;
            } while (Math.abs(corr) > c.equilTolRad && iters < c.equilMaxIter);
            if (iters > 1) equilBranchChanges++;
            if (iters >= c.equilMaxIter) equilNonConverged++;
            equilIterTotal += iters;
        }

        // residuals
        double fr = 0.0, tr = 0.0;
        for (int j = 0; j < xM.length; j++) {
            if (state[j] == DETACHED) continue;
            double dj = (state[j] == PRE_PS) ? 0.0 : delta;
            fr += headForce(X, xM[j], siteOf[j], dj);
            tr += headTorque(Theta, siteOf[j]);
        }
        if (Math.abs(fr) > maxForceResid)  maxForceResid  = Math.abs(fr);
        if (kTheta > 0.0 && Math.abs(tr) > maxTorqueResid) maxTorqueResid = Math.abs(tr);
        return iters;
    }

    /* ==================== per-motor total attachment hazard ==================== */

    private int nearestSite(double xMj) { return nearestSiteAt(X, xMj); }

    private int nearestSiteAt(double Xv, double xMj) {
        int i0 = (int) Math.rint((xMj - Xv) / a);
        if (i0 < 0) i0 = 0;
        if (i0 > nSites - 1) i0 = nSites - 1;
        return i0;
    }

    /** sum_i kA_i for a detached motor; ignores occupancy if {@code respectOccupancy} is false. */
    private double motorHazard(double xMj, boolean respectOccupancy) {
        return motorHazardAt(X, Theta, xMj, respectOccupancy);
    }

    /** the same sum evaluated at an ARBITRARY filament state — needed because under finite drag
     *  X and Theta evolve continuously between chemical events, so the hazards are time-dependent. */
    private double motorHazardAt(double Xv, double Tv, double xMj, boolean respectOccupancy) {
        int i0 = nearestSiteAt(Xv, xMj);
        int lo = Math.max(0, i0 - c.window), hi = Math.min(nSites - 1, i0 + c.window);
        double sum = 0.0;
        for (int i = lo; i <= hi; i++) {
            if (respectOccupancy && c.singleOccupancy && occupied[i]) continue;
            sum += siteHazard(Xv, Tv, xMj, i);
        }
        return sum;
    }

    /** total transition rate k_total(X, Theta) at an arbitrary filament state. The bound-state part
     *  ({@code boundRateCur}) does not depend on the filament state — no chemical rate in this model
     *  is load-dependent — so only the detached attachment hazards are re-evaluated. */
    public double kTotalAt(double Xv, double Tv) {
        double reachLo = Xv - (c.window + 1) * a, reachHi = Xv + (nSites - 1) * a + (c.window + 1) * a;
        int lo = lowerBound(xM, reachLo), hi = upperBound(xM, reachHi);
        double s = 0.0;
        for (int j = lo; j < hi; j++) if (state[j] == DETACHED) s += motorHazardAt(Xv, Tv, xM[j], true);
        hazEvals++;
        return s + boundRateCur;
    }

    /** pick a site within a detached motor's own cumulative, given a residual in [0, hazard). */
    private int pickSite(double xMj, double residual) {
        int i0 = nearestSite(xMj);
        int lo = Math.max(0, i0 - c.window), hi = Math.min(nSites - 1, i0 + c.window);
        double acc = 0.0; int last = -1;
        for (int i = lo; i <= hi; i++) {
            if (c.singleOccupancy && occupied[i]) continue;
            acc += siteHazard(X, Theta, xMj, i);
            last = i;
            if (acc > residual) return i;
        }
        return last;   // float round-off fallback: the last legal site
    }

    /* ==================== overdamped mechanics (the one restored realism) ====================
     *
     *   gammaX     dX/dt     = sum_j F_j = N_b K     (Xeq - X)
     *   gammaTheta dTheta/dt = sum_j M_j = N_b Ktheta(ThetaEq - Theta)
     *
     * Both are EXACT rearrangements of Vilfan's Eq (4) summed over the bound set, so between
     * chemical events (fixed bound set, fixed angular branches) the motion is exactly exponential
     * relaxation toward the SAME (Xeq, ThetaEq) that Eq (5) would have jumped to instantly:
     *
     *   X(s)     = Xeq     + (X0     - Xeq)     exp(-s/tauX),      tauX     = gammaX     /(N_b K)
     *   Theta(s) = ThetaEq + (Theta0 - ThetaEq) exp(-s/tauTheta),  tauTheta = gammaTheta /(N_b Ktheta)
     *
     * The mechanics is therefore propagated ANALYTICALLY, not by a numerical integrator, and the
     * only numerics are the cumulative-hazard quadrature and the event-time root. Angular branch
     * crossings are located analytically and applied as explicit segment boundaries, so no
     * trajectory ever jumps silently across the +-pi discontinuity.
     */

    /** branch integer n_j per motor, defined while bound: theta_j = Theta + b_j + 2*pi*n_j. */
    private int[] branchN;
    private double boundRateCur;
    private long hazEvals, rootIters, branchCrossings, degenerateBranch, transientRootEvents;
    private double maxDynResidF, maxDynResidM;
    private double DX;                       // kBT/gammaX, nm^2/s
    private long   anchorIdx;                // monotone address for the axial noise stream
    private long   nSubsteps, nBridgeDraws, nFreeDiffSteps;
    // ---- Stage-5 target-zone recrossing diagnostics ----
    private double[] recNetFwd, recNetBwd;   // per hysteresis band
    private long[]   recFwd, recBwd;
    private double[] recRef;                 // per-band reference (last confirmed side)
    private int[]    recSide;
    private double   pathLenNm, maxBackNm, runMaxX;
    private double   prevZone = Double.NaN;
    private long     zoneCentreCrossRaw;
    private long   xcheckN;
    private double xcheckMaxRel, xcheckSum, xcheckSigned, xcheckSq;
    private double maxEventJumpX, maxEventJumpTheta;

    /** independent uniform fine-grid trapezoid of k along the analytic trajectory on [0,s]. */
    private double gridHazard(double Xeq, double Teq, double X0, double T0,
                              double tauX, double tauT, double s, int n) {
        double h = s / n, sum = 0.0;
        double prev = kAt(Xeq, Teq, X0, T0, tauX, tauT, 0.0);
        for (int i = 1; i <= n; i++) {
            double cur = kAt(Xeq, Teq, X0, T0, tauX, tauT, i * h);
            sum += 0.5 * (prev + cur) * h; prev = cur;
        }
        return sum;
    }
    private void crossCheck(double Xeq, double Teq, double X0, double T0,
                            double tauX, double tauT, double s, double target) {
        if (!c.hazardCrossCheck || !(s > 0) || !(target > 0)) return;
        double g = gridHazard(Xeq, Teq, X0, T0, tauX, tauT, s, c.hazardCrossGrid);
        double rel = Math.abs(g - target) / target;
        if (rel > xcheckMaxRel) xcheckMaxRel = rel;
        xcheckN++;
    }

    private int countBound() {
        int nb = 0;
        for (int j = 0; j < xM.length; j++) if (state[j] != DETACHED) nb++;
        return nb;
    }

    /** the quasi-static axial target — identical expression to {@code equilibrate()}'s X. */
    private double xEqNow() {
        double s = 0.0; int nb = 0;
        for (int j = 0; j < xM.length; j++) {
            if (state[j] == DETACHED) continue;
            s += xM[j] + ((state[j] == PRE_PS) ? 0.0 : delta) - siteOf[j] * a;
            nb++;
        }
        return nb > 0 ? s / nb : X;
    }

    /** the angular target implied by the CURRENT branch assignment (no re-wrapping). */
    private double thetaEqNow() {
        double s = 0.0; int nb = 0;
        for (int j = 0; j < xM.length; j++) {
            if (state[j] == DETACHED) continue;
            s += baseAzim(siteOf[j]) + TWO_PI * branchN[j];
            nb++;
        }
        return nb > 0 ? -s / nb : Theta;
    }

    /** set motor j's branch so that its angle lies in (-pi, pi] at the current Theta. */
    private void setBranch(int j) {
        double raw = Theta + baseAzim(siteOf[j]);
        branchN[j] = -(int) Math.rint(raw / TWO_PI);
    }

    /** earliest angular branch crossing on the exponential approach Theta0 -> ThetaEq, or +inf.
     *  Returns {time, motorIndex, direction}; direction -1 means the angle leaves through +pi. */
    private double[] nextBranchCrossing(double T0, double Teq, double tauT) {
        if (kTheta <= 0.0 || !(tauT > 0) || T0 == Teq) return new double[]{Double.POSITIVE_INFINITY, -1, 0};
        boolean up = Teq > T0;
        double best = Double.POSITIVE_INFINITY; int bj = -1; double bd = 0;
        for (int j = 0; j < xM.length; j++) {
            if (state[j] == DETACHED) continue;
            double off = baseAzim(siteOf[j]) + TWO_PI * branchN[j];
            // theta_j(s) = Theta(s) + off ; crosses +pi going up, -pi going down
            double Tc = (up ? Math.PI : -Math.PI) - off;
            if (up ? !(Tc > T0 && Tc < Teq) : !(Tc < T0 && Tc > Teq)) continue;
            double s = -tauT * Math.log((Tc - Teq) / (T0 - Teq));
            if (s > 0 && s < best) { best = s; bj = j; bd = up ? -1 : +1; }
        }
        return new double[]{best, bj, bd};
    }

    /**
     * Cumulative hazard of k_total along the analytic trajectory on [s0, s1].
     * <p>
     * The integrand is a constant settled rate plus a deviation decaying like exp(-s/tau). The
     * constant part is integrated in closed form and only the DEVIATION is quadratured, on panels
     * spaced GEOMETRICALLY in tau so each spans O(1) e-foldings. Integrating k itself on one wide
     * panel forces adaptive Simpson to bisect a 40*tau interval down to the tau scale — hundreds of
     * hazard sweeps per event for no accuracy gain. Accuracy is unchanged (gates C3/C4/C5).
     */
    private double quadK(double Xeq, double Teq, double X0, double T0,
                         double tauX, double tauT, double s0, double s1, double absTol) {
        double kInf = kTotalAt(Xeq, Teq);
        double acc = kInf * (s1 - s0);
        double tX = Double.isFinite(tauX) ? tauX : 0.0, tT = Double.isFinite(tauT) ? tauT : 0.0;
        double tau = Math.max(tX, tT);
        if (!(tau > 0)) return acc;                        // nothing relaxes: the rate is constant
        double p0 = s0, w = 0.5 * tau;
        while (p0 < s1) {
            double p1 = Math.min(s1, p0 + w);
            double g0 = kAt(Xeq, Teq, X0, T0, tauX, tauT, p0) - kInf;
            double gm = kAt(Xeq, Teq, X0, T0, tauX, tauT, 0.5 * (p0 + p1)) - kInf;
            double g1 = kAt(Xeq, Teq, X0, T0, tauX, tauT, p1) - kInf;
            acc += simpsonDev(Xeq, Teq, X0, T0, tauX, tauT, kInf, p0, p1, g0, gm, g1,
                              (p1 - p0) / 6.0 * (g0 + 4 * gm + g1), absTol, 16);
            p0 = p1; w *= 2.0;
        }
        return acc;
    }

    private double simpsonDev(double Xeq, double Teq, double X0, double T0, double tauX, double tauT,
                              double kInf, double s0, double s1, double f0, double fm, double f1,
                              double whole, double absTol, int depth) {
        double sm = 0.5 * (s0 + s1);
        double fl = kAt(Xeq, Teq, X0, T0, tauX, tauT, 0.5 * (s0 + sm)) - kInf;
        double fr = kAt(Xeq, Teq, X0, T0, tauX, tauT, 0.5 * (sm + s1)) - kInf;
        double left  = (sm - s0) / 6.0 * (f0 + 4 * fl + fm);
        double right = (s1 - sm) / 6.0 * (fm + 4 * fr + f1);
        double err = left + right - whole;
        if (depth <= 0 || Math.abs(err) <= 15.0 * absTol) return left + right + err / 15.0;
        return simpsonDev(Xeq, Teq, X0, T0, tauX, tauT, kInf, s0, sm, f0, fl, fm, left, absTol / 2, depth - 1)
             + simpsonDev(Xeq, Teq, X0, T0, tauX, tauT, kInf, sm, s1, fm, fr, f1, right, absTol / 2, depth - 1);
    }

    private double simpsonRec(double Xeq, double Teq, double X0, double T0, double tauX, double tauT,
                              double s0, double s1, double f0, double fm, double f1,
                              double whole, double absTol, int depth) {
        double sm = 0.5 * (s0 + s1);
        double fl = kAt(Xeq, Teq, X0, T0, tauX, tauT, 0.5 * (s0 + sm));
        double fr = kAt(Xeq, Teq, X0, T0, tauX, tauT, 0.5 * (sm + s1));
        double left  = (sm - s0) / 6.0 * (f0 + 4 * fl + fm);
        double right = (s1 - sm) / 6.0 * (fm + 4 * fr + f1);
        double err = left + right - whole;
        if (depth <= 0 || Math.abs(err) <= 15.0 * absTol) return left + right + err / 15.0;
        return simpsonRec(Xeq, Teq, X0, T0, tauX, tauT, s0, sm, f0, fl, fm, left, absTol / 2, depth - 1)
             + simpsonRec(Xeq, Teq, X0, T0, tauX, tauT, sm, s1, fm, fr, f1, right, absTol / 2, depth - 1);
    }

    public static double relaxPublic(double eq, double v0, double tau, double s) {
        return (tau > 0 && Double.isFinite(tau)) ? eq + (v0 - eq) * Math.exp(-s / tau) : eq;
    }

    private double relaxX(double Xeq, double X0, double tauX, double s) {
        return (tauX > 0) ? Xeq + (X0 - Xeq) * Math.exp(-s / tauX) : Xeq;
    }
    private double relaxT(double Teq, double T0, double tauT, double s) {
        if (kTheta <= 0.0) return T0;
        return (tauT > 0 && Double.isFinite(tauT)) ? Teq + (T0 - Teq) * Math.exp(-s / tauT) : Teq;
    }
    private double kAt(double Xeq, double Teq, double X0, double T0, double tauX, double tauT, double s) {
        return kTotalAt(relaxX(Xeq, X0, tauX, s), relaxT(Teq, T0, tauT, s));
    }

    /**
     * Piecewise-deterministic advance to the next chemical event.
     * <p>
     * Draws an exponential CUMULATIVE-HAZARD threshold {@code H* = -ln r}, then evolves the
     * mechanics and {@code dH/dt = k_total[X(t), Theta(t)]} together until {@code H = H*}. The rate
     * is NEVER frozen over the interval. On return X and Theta stand at the event time and are
     * continuous through it; the caller re-evaluates the instantaneous rates there and selects the
     * transition by their rate fractions.
     *
     * @return the elapsed time, or -1 if the total rate vanished (caller halts).
     */
    private double advanceOverdamped(SplittableRandom rngTime) {
        double Hrem = Math.log(1.0 / (1.0 - rngTime.nextDouble()));
        double tAcc = 0.0;

        for (int guard = 0; guard < 1_000_000; guard++) {
            int nb = countBound();
            double kNow = kTotalAt(X, Theta);
            if (!(kNow > 0.0)) return -1;

            if (nb == 0) {                       // no bound head: zero force, X and Theta frozen
                nbZeroEvents++;
                return tAcc + Hrem / kNow;       // hazard is then constant
            }

            double Xeq = xEqNow(), Teq = thetaEqNow();
            // A degree of freedom with zero stiffness feels no force, so it does not relax at all
            // and contributes NO transient: its relaxation time is formally infinite but the
            // trajectory is constant. Counting it in the settle horizon would diverge.
            double tauX = (K > 0.0)      ? gammaX     / (nb * K)      : Double.POSITIVE_INFINITY;
            double tauT = (kTheta > 0.0) ? gammaTheta / (nb * kTheta) : Double.POSITIVE_INFINITY;
            double X0 = X, T0 = Theta;

            double tauMax = Math.max((K > 0.0) ? tauX : 0.0, (kTheta > 0.0) ? tauT : 0.0);
            double tSettle = c.settleFactor * tauMax;
            double[] br = nextBranchCrossing(T0, Teq, tauT);
            double tBranch = br[0];

            // horizon of this analytic segment
            boolean branchFirst = tBranch < tSettle;
            double sEnd = branchFirst ? tBranch : tSettle;

            // cumulative hazard over the transient part of the segment
            double absTol = c.hazTolRel * kNow * Math.max(sEnd, 1e-300);
            double Hseg = (sEnd > 0) ? quadK(Xeq, Teq, X0, T0, tauX, tauT, 0.0, sEnd, absTol) : 0.0;

            if (Hseg >= Hrem) {                  // the event falls inside the transient
                transientRootEvents++;
                double s = rootInSegment(Xeq, Teq, X0, T0, tauX, tauT, 0.0, sEnd, Hrem, absTol);
                if (guard == 0) crossCheck(Xeq, Teq, X0, T0, tauX, tauT, s, Hrem);
                X = relaxX(Xeq, X0, tauX, s); Theta = relaxT(Teq, T0, tauT, s);
                return tAcc + s;
            }
            Hrem -= Hseg; tAcc += sEnd;
            X = relaxX(Xeq, X0, tauX, sEnd); Theta = relaxT(Teq, T0, tauT, sEnd);

            if (branchFirst) {                   // apply the branch crossing and continue
                int j = (int) br[1];
                branchN[j] += (int) br[2];
                branchCrossings++;
                continue;
            }

            // settled: the filament has reached (Xeq, ThetaEq) to exp(-settleFactor), so k_total is
            // constant from here on and no further branch crossing can occur in this segment.
            if (Double.isFinite(tBranch)) degenerateBranch++;
            double kInf = kTotalAt(X, Theta);
            if (!(kInf > 0.0)) return -1;
            double sFull = sEnd + Hrem / kInf;
            if (guard == 0) crossCheck(Xeq, Teq, X0, T0, tauX, tauT, sFull, Hrem + Hseg);
            X = relaxX(Xeq, X0, tauX, sFull); Theta = relaxT(Teq, T0, tauT, sFull);
            return tAcc + Hrem / kInf;
        }
        return -1;
    }

    /* ==================== STOCHASTIC AXIAL PATH + HAZARD COUPLING (Stage 1-2) ====================
     *
     * With axial Brownian motion the filament's axial coordinate is no longer a smooth analytic
     * trajectory, so the deterministic cumulative-hazard solver cannot be reused: the attachment
     * hazards kA_i[X(t), Theta(t)] depend on the REALISED stochastic path. The process is therefore
     * integrated as a coupled stochastic path / cumulative-hazard system:
     *
     *   1. draw H* = -ln r
     *   2. step the realised axial OU path and the deterministic roll path together on an anchor grid
     *   3. accumulate dH/dt = k_total[X(t), Theta(t), states] along that realised path
     *   4. stop at H = H*; 5. evaluate all legal rates there; 6. select by rate fraction;
     *   7. continue from the SAME continuous X and Theta.
     *
     * X uses the EXACT OU transition (no discretisation error in the marginal law); the hazard is
     * integrated by Simpson with the midpoint supplied by the exact OU BRIDGE, so refining a step
     * never redraws independent noise. Theta is propagated exactly as in the deterministic study.
     */

    /** stationary axial variance for the current bound set, kBT/(Nb K). */
    private double varInfX(int nb) { return (nb > 0 && K > 0) ? c.kBT / (nb * K) : Double.POSITIVE_INFINITY; }

    /** one exact axial step: OU when the filament is held, free diffusion when it is not. */
    private double axialStep(double Xeq, double x0, double tauX, int nb, double dt, long addr) {
        double z = VilfanAxialBrownian.gauss(c.seed, VilfanAxialBrownian.STREAM_AXIAL, addr);
        if (nb > 0 && K > 0) return VilfanAxialBrownian.ouStep(Xeq, x0, tauX, varInfX(nb), dt, z);
        nFreeDiffSteps++;
        return VilfanAxialBrownian.freeStep(x0, DX, dt, z);
    }

    /** exact bridge midpoint of the axial path already pinned at (xa, xb) over [0, T]. */
    private double axialBridgeMid(double Xeq, double xa, double xb, double tauX, int nb,
                                  double T, long addr) {
        nBridgeDraws++;
        double z = VilfanAxialBrownian.gauss(c.seed, VilfanAxialBrownian.STREAM_BRIDGE, addr);
        if (nb > 0 && K > 0) return VilfanAxialBrownian.ouBridgeMid(Xeq, xa, xb, tauX, varInfX(nb), T, z);
        return VilfanAxialBrownian.freeBridgeMid(xa, xb, DX, T, z);
    }

    /**
     * Advance to the next chemical event along a realised stochastic axial path.
     * @return elapsed time, or -1 if the total rate vanished.
     */
    private double advanceStochastic(SplittableRandom rngTime) {
        double Hrem = Math.log(1.0 / (1.0 - rngTime.nextDouble()));
        double tAcc = 0.0;
        double dtBase = c.anchorDtS / (1L << c.refineLevel);

        for (int guard = 0; guard < 20_000_000; guard++) {
            int nb = countBound();
            if (nb == 0) nbZeroEvents++;
            double Xeq = xEqNow(), Teq = thetaEqNow();
            double tauX = (nb > 0 && K > 0) ? gammaX / (nb * K) : Double.POSITIVE_INFINITY;
            double tauT = (nb > 0 && kTheta > 0.0) ? gammaTheta / (nb * kTheta) : Double.POSITIVE_INFINITY;

            // a deterministic roll branch crossing truncates the substep so no trajectory ever
            // steps silently across the +-pi discontinuity
            double tBr = (nb > 0) ? nextBranchCrossing(Theta, Teq, tauT)[0] : Double.POSITIVE_INFINITY;
            double[] brInfo = (nb > 0) ? nextBranchCrossing(Theta, Teq, tauT) : new double[]{Double.POSITIVE_INFINITY, -1, 0};
            double dt = Math.min(dtBase, tBr);
            boolean branchEnds = (tBr <= dtBase);

            double X0 = X, T0 = Theta;
            long addr = anchorIdx++;
            double Xn = axialStep(Xeq, X0, tauX, nb, dt, addr);
            double Tn = relaxT(Teq, T0, tauT, dt);
            double Xm = axialBridgeMid(Xeq, X0, Xn, tauX, nb, dt, addr);
            double Tm = relaxT(Teq, T0, tauT, 0.5 * dt);

            double k0 = kTotalAt(X0, T0), km = kTotalAt(Xm, Tm), k1 = kTotalAt(Xn, Tn);
            if (!(k0 > 0) && !(km > 0) && !(k1 > 0)) return -1;
            double Hs = dt / 6.0 * (k0 + 4 * km + k1);
            nSubsteps++;

            if (Hs >= Hrem) {                       // the event falls inside this substep
                double t = locateInSubstep(Xeq, Teq, X0, T0, Xn, Tn, tauX, tauT, nb, dt, Hrem, addr);
                return tAcc + t;
            }
            if (c.hazardCrossCheck && (nSubsteps % c.hazardCheckStride) == 0) {
                double ref = refineH(Xeq, Teq, X0, T0, Xn, Tn, tauX, tauT, nb, dt, addr, c.hazardCheckDepth);
                double sgn = (Hs - ref) / Math.max(1e-300, ref);
                double rel = Math.abs(sgn);
                if (rel > xcheckMaxRel) xcheckMaxRel = rel;
                xcheckSum += rel; xcheckSigned += sgn; xcheckSq += sgn * sgn; xcheckN++;
            }
            Hrem -= Hs; tAcc += dt;
            pathLenNm += Math.abs(Xn - X0);
            X = Xn; Theta = Tn;
            updateRecrossing();
            if (branchEnds && brInfo[1] >= 0) { branchN[(int) brInfo[1]] += (int) brInfo[2]; branchCrossings++; }
        }
        return -1;
    }

    /**
     * Locate the terminal chemical event inside an accepted substep by BROWNIAN-BRIDGE refinement:
     * the substep is halved repeatedly, each midpoint drawn from the exact OU bridge conditioned on
     * the endpoints already realised, so the located time lies on the SAME stochastic path. X and
     * Theta are left standing at the event time.
     */
    private double locateInSubstep(double Xeq, double Teq, double xa, double ta, double xb, double tb,
                                   double tauX, double tauT, int nb, double T, double target, long addr) {
        double t0 = 0.0, x0 = xa, th0 = ta, len = T, acc = 0.0;
        long a = addr;
        for (int lvl = 0; lvl < c.bridgeMaxLevel; lvl++) {
            double half = 0.5 * len;
            a = VilfanAxialBrownian.hash(a, 0x9E3779B9L, lvl);
            double xm = axialBridgeMid(Xeq, x0, xb, tauX, nb, len, a);
            double thm = relaxT(Teq, th0, tauT, half);
            double kL0 = kTotalAt(x0, th0), kLm = kTotalAt(
                    axialBridgeMid(Xeq, x0, xm, tauX, nb, half, a ^ 0x5DEECE66DL),
                    relaxT(Teq, th0, tauT, 0.25 * len)), kLm2 = kTotalAt(xm, thm);
            double HL = half / 6.0 * (kL0 + 4 * kLm + kLm2);
            if (acc + HL >= target) { xb = xm; tb = thm; len = half; }       // event in the first half
            else { acc += HL; t0 += half; x0 = xm; th0 = thm; len = half; } // event in the second half
        }
        X = x0; Theta = th0;
        pathLenNm += Math.abs(x0 - xa);
        updateRecrossing();
        return t0;
    }

    /**
     * NUMERICAL GATE: the cumulative-hazard quadrature error of ONE substep, isolated from the
     * ensemble sampling noise.
     * <p>
     * Refining the whole simulation shifts event times and therefore decorrelates the trajectory, so
     * comparing ensemble statistics across refinement levels measures sampling noise, not
     * discretisation error. Instead we take a substep with its endpoints ALREADY realised and
     * subdivide it by nested Brownian bridges, reusing the same midpoint draws at every level, so the
     * coarse and refined estimates are integrals of literally the same stochastic path. The
     * difference is then purely the quadrature error.
     */
    private double refineH(double Xeq, double Teq, double xa, double tha, double xb, double thb,
                           double tauX, double tauT, int nb, double T, long addr, int depth) {
        double xm = axialBridgeMid(Xeq, xa, xb, tauX, nb, T, addr);
        double thm = relaxT(Teq, tha, tauT, 0.5 * T);
        if (depth <= 0) return T / 6.0 * (kTotalAt(xa, tha) + 4 * kTotalAt(xm, thm) + kTotalAt(xb, thb));
        return refineH(Xeq, Teq, xa, tha, xm, thm, tauX, tauT, nb, 0.5 * T, addr * 2 + 1, depth - 1)
             + refineH(Xeq, Teq, xm, thm, xb, thb, tauX, tauT, nb, 0.5 * T, addr * 2 + 2, depth - 1);
    }

    /** Stage-5 scale-aware zone-centre crossing diagnostics, updated at every accepted substep. */
    private void updateRecrossing() {
        if (recSide == null) return;
        if (X > runMaxX) runMaxX = X;
        double back = runMaxX - X;
        if (back > maxBackNm) maxBackNm = back;
        double z = zoneCoord(X, Theta, 0.0);      // zone coordinate of a reference anchor at 0
        if (!Double.isNaN(prevZone)) {
            // raw sign change of the zone coordinate about the centre (jitter-dominated; reported
            // only to show WHY a hysteresis band is required)
            if (prevZone > 0 != z > 0 && Math.abs(prevZone - z) < 0.5 * LNm) zoneCentreCrossRaw++;
        }
        prevZone = z;
        // hysteretic counting in the AXIAL coordinate: a crossing is confirmed only after the
        // filament has moved band/2 beyond the centre, so sub-nanometre thermal jitter about the
        // centre is not counted as a physical revisit
        for (int b = 0; b < recSide.length; b++) {
            double band = c.recrossBandsNm[b];
            double d = X - recRef[b];
            if (d > 0.5 * LNm) { recRef[b] += LNm; }                 // advanced a whole zone period
            if (Math.abs(d) < band * 0.5) continue;
            int side = d > 0 ? +1 : -1;
            if (recSide[b] == 0) { recSide[b] = side; continue; }
            if (side != recSide[b]) { if (side > 0) recFwd[b]++; else recBwd[b]++; recSide[b] = side; }
        }
    }

    /** locate s in [s0,s1] with integral_{s0}^{s} k = target, by safeguarded Newton (H' = k > 0). */
    private double rootInSegment(double Xeq, double Teq, double X0, double T0, double tauX, double tauT,
                                 double s0, double s1, double target, double absTol) {
        double lo = s0, hi = s1;
        double s = s0 + (s1 - s0) * 0.5;
        for (int it = 0; it < 100; it++) {
            rootIters++;
            double H = quadK(Xeq, Teq, X0, T0, tauX, tauT, s0, s, absTol);
            double f = H - target;
            if (Math.abs(f) <= Math.max(absTol, c.rootTolRel * target)) return s;
            if (f > 0) hi = s; else lo = s;
            double k = kAt(Xeq, Teq, X0, T0, tauX, tauT, s);
            double sn = (k > 0) ? s - f / k : 0.5 * (lo + hi);
            s = (sn > lo && sn < hi) ? sn : 0.5 * (lo + hi);
            if (hi - lo <= c.rootTolRel * Math.max(hi, 1e-300)) return s;
        }
        return s;
    }

    /* ==================== the run (algorithm steps 2-7) ==================== */

    public Result run() {
        long wall0 = System.nanoTime();
        Result R = new Result();
        R.cfg = c.copy();
        R.nSites = nSites;
        R.lengthNm = nSites * a;
        R.LNm = LNm;
        R.grooveSlope = grooveSlope;

        // deterministic, private streams keyed off the declared seed
        SplittableRandom rngPlace = new SplittableRandom(c.seed * 1000003L + 11L);
        SplittableRandom rngTime  = new SplittableRandom(c.seed * 1000003L + 22L);
        SplittableRandom rngPick  = new SplittableRandom(c.seed * 1000003L + 33L);

        double travelTarget = c.warmupUm + c.travelUm;      // um; may extend adaptively
        // The field must cover "the distance covered by the actin filament" (algorithm step 1) in
        // whichever direction the filament actually travels; a polarity-reversed control glides -X.
        double margin = (c.window + 4) * a;
        double reach  = c.polarity * c.travelCapUm * 1000.0;
        double fieldLo = Math.min(0.0, reach) - margin;
        double fieldHi = Math.max(0.0, reach) + nSites * a + margin;
        placeMotors(fieldLo, fieldHi, rngPlace);
        R.fieldLoNm = fieldLo; R.fieldHiNm = fieldHi; R.nMotors = xM.length;

        int nM = xM.length;
        state  = new int[nM];
        siteOf = new int[nM];
        branchN = new int[nM];
        int nBands = c.recrossBandsNm.length;
        recFwd = new long[nBands]; recBwd = new long[nBands];
        recRef = new double[nBands]; recSide = new int[nBands];
        Arrays.fill(siteOf, -1);
        occupied = new boolean[nSites];
        hazCache = new double[nM];

        X = 0.0; Theta = 0.0;                                // algorithm step 1
        double t = 0.0;

        // accumulators
        double tW = -1.0, xW = 0.0, thW = 0.0;               // warm-up boundary snapshot
        long nEv = 0, nAtt = 0, nPS = 0, nADP = 0, nDet = 0;
        double[] occ = new double[4];
        double boundTime = 0.0;
        double sXa = 0, sXa2 = 0, sXiA = 0, sXiA2 = 0, sThA = 0, sThA2 = 0; long nXa = 0;
        double sTorqT = 0, sForceT = 0, sTorqPer = 0, sForcePer = 0, wT = 0;
        int[] xaHist = new int[40];
        double[] shadowHist = new double[40];
        int[] nbHist = new int[0];
        long nEvAnalysed = 0;
        double shadowW = 0, shadowWX = 0, shadowWTh = 0;
        // least-squares accumulators over the analysed window
        double lsN = 0, lsT = 0, lsT2 = 0, lsX = 0, lsTX = 0, lsTh = 0, lsTTh = 0;

        boolean capHit = false;
        // subsampled trajectory over the analysed window, for the stationarity split
        int traceCap = 40000, traceN = 0, traceStride = 1; long traceCount = 0;
        double[] trT = new double[traceCap], trX = new double[traceCap], trTh = new double[traceCap];

        while (true) {
            // ---------- step 2: total transition rate ----------
            double ktotal = 0.0;
            // restrict hazard evaluation to motors that can reach a site (exact: the Gaussian
            // longitudinal factor is < e^-45 beyond the window)
            double reachLo = X - (c.window + 1) * a, reachHi = X + (nSites - 1) * a + (c.window + 1) * a;
            int jLo = lowerBound(xM, reachLo), jHi = upperBound(xM, reachHi);
            for (int j = 0; j < nM; j++) hazCache[j] = 0.0;
            for (int j = jLo; j < jHi; j++) {
                if (state[j] != DETACHED) continue;
                hazCache[j] = motorHazard(xM[j], true);
                ktotal += hazCache[j];
            }
            double boundRate = 0.0;
            int nb = 0;
            for (int j = 0; j < nM; j++) {
                switch (state[j]) {
                    case PRE_PS  -> { boundRate += c.kPS;  nb++; }
                    case POST_PS -> { boundRate += c.kADP; nb++; }
                    case RIGOR   -> { boundRate += c.kD;   nb++; }
                    default -> { }
                }
            }
            ktotal += boundRate;
            boundRateCur = boundRate;
            if (!(ktotal > 0.0)) {
                R.note = "HALT: ktotal = 0 (no legal transition) at t=" + t + " X=" + X;
                break;
            }

            // ---------- step 3: waiting time ----------
            double dt;
            double X0i = X, Th0i = Theta;
            if (c.overdamped()) {
                // piecewise-deterministic: evolve the mechanics and the cumulative hazard together
                // until H reaches an exponential threshold. X and Theta MOVE here, continuously.
                dt = c.brownian() ? advanceStochastic(rngTime) : advanceOverdamped(rngTime);
                if (dt < 0) {
                    R.note = "HALT: ktotal = 0 during overdamped advance at t=" + t + " X=" + X;
                    break;
                }
                // dynamic closure at the event time: the analytic relaxation rate must reproduce the
                // directly summed Vilfan force and torque. The torque arm is a genuine cross-check —
                // it compares the branch-integer bookkeeping against an independent wrapPi sum.
                if (nb > 0) {
                    double sFd = 0, sMd = 0;
                    for (int j = 0; j < nM; j++) {
                        if (state[j] == DETACHED) continue;
                        double dj = (state[j] == PRE_PS) ? 0.0 : delta;
                        sFd += headForce(X, xM[j], siteOf[j], dj);
                        sMd += headTorque(Theta, siteOf[j]);
                    }
                    double xdot = (xEqNow() - X) * (nb * K) / gammaX;
                    double rF = Math.abs(gammaX * xdot - sFd);
                    if (rF > maxDynResidF) maxDynResidF = rF;
                    if (kTheta > 0.0) {
                        double tdot = (thetaEqNow() - Theta) * (nb * kTheta) / gammaTheta;
                        double rM = Math.abs(gammaTheta * tdot - sMd);
                        if (rM > maxDynResidM) maxDynResidM = rM;
                    }
                }
                // rates must be re-read at the state the filament actually reached
                reachLo = X - (c.window + 1) * a; reachHi = X + (nSites - 1) * a + (c.window + 1) * a;
                jLo = lowerBound(xM, reachLo); jHi = upperBound(xM, reachHi);
                ktotal = boundRate;
                for (int j = 0; j < nM; j++) hazCache[j] = 0.0;
                for (int j = jLo; j < jHi; j++) {
                    if (state[j] != DETACHED) continue;
                    hazCache[j] = motorHazard(xM[j], true);
                    ktotal += hazCache[j];
                }
                if (!(ktotal > 0.0)) { R.note = "HALT: ktotal = 0 at located event time"; break; }
            } else {
                dt = expWait(rngTime, ktotal);
            }

            // ---------- time-weighted accumulation over [t, t+dt) ----------
            boolean analysed = (tW >= 0.0);
            if (analysed) {
                if (nbHist.length == 0) nbHist = new int[nM + 1];
                nbHist[nb]++; nEvAnalysed++;
                for (int j = 0; j < nM; j++) occ[state[j]] += dt;
                boundTime += nb * dt;
                double sF = 0, sM = 0;
                for (int j = 0; j < nM; j++) {
                    if (state[j] == DETACHED) continue;
                    double dj = (state[j] == PRE_PS) ? 0.0 : delta;
                    sF += headForce(X, xM[j], siteOf[j], dj);
                    sM += headTorque(Theta, siteOf[j]);
                }
                if (c.overdamped()) {
                    // EXACT time integrals: integral(sum F)dt = gammaX * dX and
                    // integral(sum M)dt = gammaTheta * dTheta, straight from the equations of motion.
                    sForceT += gammaX * (X - X0i);
                    sTorqT  += gammaTheta * (Theta - Th0i);
                } else {
                    sForceT += sF * dt; sTorqT += sM * dt;
                }
                if (nb > 0) { sForcePer += (sF / nb) * dt; sTorqPer += (sM / nb) * dt; }
                wT += dt;
                lsN += dt; lsT += t * dt; lsT2 += t * t * dt;
                lsX += X * dt; lsTX += t * X * dt; lsTh += Theta * dt; lsTTh += t * Theta * dt;

                if (traceCount++ % traceStride == 0) {
                    if (traceN == traceCap) {          // decimate in place, double the stride
                        for (int k = 0; k < traceCap / 2; k++) { trT[k] = trT[2*k]; trX[k] = trX[2*k]; trTh[k] = trTh[2*k]; }
                        traceN = traceCap / 2; traceStride *= 2;
                    }
                    trT[traceN] = t; trX[traceN] = X; trTh[traceN] = Theta; traceN++;
                }

                if (c.noDepletionControl) {
                    // SHADOW measurement: the same moving attachment landscape evaluated for EVERY
                    // motor under the filament as if it were detached. Purely diagnostic — it removes
                    // no motor from the pool and generates no force.
                    for (int j = jLo; j < jHi; j++) {
                        double h = motorHazard(xM[j], false);
                        if (h <= 0) continue;
                        double xz = zoneCoord(X, Theta, xM[j]);
                        shadowW  += h * dt;
                        shadowWX += h * dt * xz;
                        int b = (int) Math.floor((xz / LNm + 0.5) * 40);
                        if (b >= 0 && b < 40) shadowHist[b] += h * dt;
                    }
                }
            }
            t += dt;

            // ---------- step 4: choose the transition ----------
            double r = rngPick.nextDouble() * ktotal;
            int chosen = -1, chosenSite = -1, kind = -1;   // kind: 0 attach 1 PS 2 ADP 3 detach
            double acc = 0.0;
            for (int j = jLo; j < jHi && chosen < 0; j++) {
                if (state[j] != DETACHED || hazCache[j] <= 0) continue;
                if (acc + hazCache[j] > r) { chosen = j; kind = 0; chosenSite = pickSite(xM[j], r - acc); }
                else acc += hazCache[j];
            }
            if (chosen < 0) {
                for (int j = 0; j < nM && chosen < 0; j++) {
                    double rate = switch (state[j]) {
                        case PRE_PS -> c.kPS; case POST_PS -> c.kADP; case RIGOR -> c.kD; default -> 0.0; };
                    if (rate <= 0) continue;
                    if (acc + rate > r) { chosen = j; kind = switch (state[j]) {
                        case PRE_PS -> 1; case POST_PS -> 2; default -> 3; }; }
                    else acc += rate;
                }
            }
            if (chosen < 0) {   // round-off at the very top of the cumulative: take the last bound head
                for (int j = nM - 1; j >= 0; j--) if (state[j] != DETACHED) {
                    chosen = j; kind = switch (state[j]) { case PRE_PS -> 1; case POST_PS -> 2; default -> 3; }; break; }
                if (chosen < 0) { R.note = "BLOCKER: transition selection failed"; break; }
            }

            // ---------- step 5: apply the transition, then re-equilibrate ----------
            double Xpre = X, Tpre = Theta;      // continuity witness (overdamped: must not change)
            if (kind == 0) {
                if (chosenSite < 0) { R.note = "BLOCKER: no legal site for chosen attachment"; break; }
                // observables are read AT THE INSTANT OF BINDING, before re-equilibration
                double xz  = zoneCoord(X, Theta, xM[chosen]);
                double xiA = strain(X, xM[chosen], chosenSite);
                double thA = wrapPi(Theta + baseAzim(chosenSite));
                state[chosen] = PRE_PS; siteOf[chosen] = chosenSite; occupied[chosenSite] = true;
                if (c.overdamped()) setBranch(chosen);   // fix the angular branch at the binding pose
                nAtt++;
                if (analysed) {
                    sXa += xz; sXa2 += xz * xz; sXiA += xiA; sXiA2 += xiA * xiA;
                    sThA += thA; sThA2 += thA * thA; nXa++;
                    int b = (int) Math.floor((xz / LNm + 0.5) * 40);
                    if (b >= 0 && b < 40) xaHist[b]++;
                }
            } else if (kind == 1) { state[chosen] = POST_PS; nPS++; }
            else if (kind == 2)   { state[chosen] = RIGOR;   nADP++; }
            else {                  occupied[siteOf[chosen]] = false; siteOf[chosen] = -1;
                                    state[chosen] = DETACHED; nDet++; }

            // Under finite drag X and Theta are CONTINUOUS through the event: the transition changes
            // the force and torque discontinuously, and the filament then relaxes toward the new
            // target over a finite time. No displacement is applied here.
            if (c.overdamped()) {
                double jX = Math.abs(X - Xpre), jT = Math.abs(Theta - Tpre);
                if (jX > maxEventJumpX) maxEventJumpX = jX;
                if (jT > maxEventJumpTheta) maxEventJumpTheta = jT;
            } else equilibrate();
            nEv++;

            // ---------- warm-up boundary + step 6: termination ----------
            // progress is measured along the direction the stroke drives (polarity), so the same
            // rule terminates a native run and a polarity-reversed control.
            double prog = c.polarity * X;
            if (c.warmupTimeS > 0.0) {                       // preregistered fixed-TIME window
                if (tW < 0.0 && t >= c.warmupTimeS) { tW = t; xW = X; thW = Theta; }
                if (tW >= 0.0 && t - tW >= c.analysisTimeS) break;
                if (nEv >= c.maxEvents) { R.note += " [event cap]"; break; }
                continue;
            }
            if (tW < 0.0 && prog >= c.warmupUm * 1000.0) { tW = t; xW = X; thW = Theta; }
            if (tW >= 0.0) {
                double travelled = c.polarity * (X - xW) / 1000.0;
                double turns = Math.abs(Theta - thW) / TWO_PI;
                if (travelled >= c.travelUm && turns >= c.turnsTarget) break;
            }
            if (prog >= c.travelCapUm * 1000.0) { capHit = true; break; }
            if (nEv >= c.maxEvents)  { R.note += " [event cap]"; break; }
            if (t   >= c.maxSimTimeS){ R.note += " [sim-time cap]"; break; }
        }

        /* ---------------- finalise ---------------- */
        if (tW < 0.0) { tW = 0.0; xW = 0.0; thW = 0.0; R.note += " [warm-up never reached]"; }
        double dT = t - tW, dX = X - xW, dTh = Theta - thW;
        R.tEnd = t; R.xEnd = X; R.thetaEnd = Theta;
        R.tWarm = tW; R.xWarm = xW; R.thetaWarm = thW;
        R.velUmPerS   = (dT > 0) ? (dX / 1000.0) / dT : 0.0;
        R.omegaRadPerS= (dT > 0) ? dTh / dT : 0.0;
        R.turns       = Math.abs(dTh) / TWO_PI;
        R.pitchUmPerTurn = (dTh != 0) ? TWO_PI * (dX / 1000.0) / dTh : Double.NaN;
        R.invPitchPerUm  = (R.pitchUmPerTurn != 0) ? 1.0 / R.pitchUmPerTurn : Double.NaN;
        if (lsN > 0) {
            double mT = lsT / lsN, mX = lsX / lsN, mTh = lsTh / lsN;
            double vTT = lsT2 / lsN - mT * mT;
            R.velLsq   = (vTT > 0) ? ((lsTX / lsN - mT * mX) / vTT) / 1000.0 : 0.0;
            R.omegaLsq = (vTT > 0) ?  (lsTTh / lsN - mT * mTh) / vTT : 0.0;
        }
        // stationarity: split the analysed window at the midpoint of SIMULATED TIME
        if (traceN > 4) {
            double tMid = 0.5 * (trT[0] + trT[traceN - 1]);
            int mid = 0; while (mid < traceN - 1 && trT[mid] < tMid) mid++;
            double d1 = trT[mid] - trT[0], d2 = trT[traceN - 1] - trT[mid];
            if (d1 > 0) { R.velFirstHalf  = (trX[mid] - trX[0]) / 1000.0 / d1; R.omegaFirstHalf  = (trTh[mid] - trTh[0]) / d1; }
            if (d2 > 0) { R.velSecondHalf = (trX[traceN-1] - trX[mid]) / 1000.0 / d2; R.omegaSecondHalf = (trTh[traceN-1] - trTh[mid]) / d2; }
        }
        R.nEvents = nEv; R.nAttach = nAtt; R.nStroke = nPS; R.nAdpRelease = nADP; R.nDetach = nDet;
        R.meanBound = (dT > 0) ? boundTime / dT : 0.0;
        R.dutyRatio = (nM > 0) ? R.meanBound / countReachable() : 0.0;
        double occTot = occ[0] + occ[1] + occ[2] + occ[3];
        for (int s = 0; s < 4; s++) R.stateOccupancy[s] = (occTot > 0) ? occ[s] / occTot : 0.0;
        if (nXa > 0) {
            R.meanXa = sXa / nXa;  R.sdXa  = sd(sXa, sXa2, nXa);
            R.meanXiA= sXiA / nXa; R.sdXiA = sd(sXiA, sXiA2, nXa);
            R.meanThA= sThA / nXa; R.sdThA = sd(sThA, sThA2, nXa);
            R.meanAttachTorque = -kTheta * R.meanThA;      // M_A = -Ktheta * theta_A  (Eq 4)
        }
        if (wT > 0) {
            R.meanTotalForce = sForceT / wT; R.meanTotalTorque = sTorqT / wT;
            R.meanSiteForce  = sForcePer / wT; R.meanSiteTorque = sTorqPer / wT;
        }
        R.maxForceResid = maxForceResid; R.maxTorqueResid = maxTorqueResid;
        R.equilIterTotal = equilIterTotal; R.equilBranchChanges = equilBranchChanges;
        R.equilNonConverged = equilNonConverged; R.nbZeroEvents = nbZeroEvents;
        R.xaHist = xaHist;
        if (c.noDepletionControl && shadowW > 0) {
            R.haveShadow = true;
            R.shadowMeanXa = shadowWX / shadowW;
            R.shadowHist = shadowHist;
        }
        R.travelCapHit = capHit;
        R.mechanics = c.mechanics; R.etaPaS = c.etaPaS;
        R.gammaX = gammaX; R.gammaTheta = gammaTheta;
        R.maxDynResidF = maxDynResidF; R.maxDynResidM = maxDynResidM;
        R.hazEvals = hazEvals; R.rootIters = rootIters; R.branchCrossings = branchCrossings;
        R.degenerateBranch = degenerateBranch; R.transientRootEvents = transientRootEvents;
        R.axialBrownian = c.axialBrownian; R.DXnm2PerS = DX;
        R.anchorDtS = c.anchorDtS; R.refineLevel = c.refineLevel;
        R.nSubsteps = nSubsteps; R.nBridgeDraws = nBridgeDraws; R.nFreeDiffSteps = nFreeDiffSteps;
        R.zoneCentreCrossRaw = zoneCentreCrossRaw;
        R.pathLenNm = pathLenNm; R.netFwdNm = X - xW; R.maxBackNm = maxBackNm;
        R.recFwd = recFwd; R.recBwd = recBwd; R.recBands = c.recrossBandsNm;
        R.xcheckN = xcheckN; R.xcheckMaxRel = xcheckMaxRel;
        R.xcheckMeanRel = (xcheckN > 0) ? xcheckSum / xcheckN : 0.0;
        if (xcheckN > 1) {
            R.xcheckBias = xcheckSigned / xcheckN;
            double var = (xcheckSq - xcheckSigned * xcheckSigned / xcheckN) / (xcheckN - 1);
            R.xcheckBiasSem = Math.sqrt(Math.max(0, var) / xcheckN);
        }
        R.maxEventJumpX = maxEventJumpX; R.maxEventJumpTheta = maxEventJumpTheta;
        R.nEventsAnalysed = nEvAnalysed;
        R.meanInterEventS = (nEvAnalysed > 0) ? dT / nEvAnalysed : 0.0;
        if (nbHist.length > 0 && nEvAnalysed > 0) {          // median bound-head count
            long cum = 0; int med = 0;
            for (int k = 0; k < nbHist.length; k++) { cum += nbHist[k]; if (cum * 2 >= nEvAnalysed) { med = k; break; } }
            R.medianNb = med;
            if (c.overdamped() && med > 0) {
                R.tauXmed     = gammaX / (med * K);
                R.tauThetaMed = (kTheta > 0) ? gammaTheta / (med * kTheta) : Double.POSITIVE_INFINITY;
            }
        }
        if (LNm > 0 && Double.isFinite(LNm)) {               // target-zone passage time, L/|c|
            double cApp = Math.abs(R.velUmPerS * 1000.0 - R.omegaRadPerS * LNm / Math.PI);
            R.zonePassageS = (cApp > 0) ? LNm / cApp : Double.POSITIVE_INFINITY;
        }
        if (R.medianNb > 0 && K > 0) R.sdXconstrainedNm = Math.sqrt(c.kBT / (R.medianNb * K));
        R.wallClockS = (System.nanoTime() - wall0) / 1e9;
        return R;
    }

    private double countReachable() {
        // motors that are under the filament at any instant: length * density
        return c.lengthUm * c.densityPerUm;
    }

    private static double sd(double s, double s2, long n) {
        if (n < 2) return 0.0;
        double v = (s2 - s * s / n) / (n - 1);
        return v > 0 ? Math.sqrt(v) : 0.0;
    }

    private static int lowerBound(double[] arr, double key) {
        int lo = 0, hi = arr.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (arr[mid] < key) lo = mid + 1; else hi = mid; }
        return lo;
    }
    private static int upperBound(double[] arr, double key) {
        int lo = 0, hi = arr.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (arr[mid] <= key) lo = mid + 1; else hi = mid; }
        return lo;
    }

    /* ==================== static-landscape diagnostics (no dynamics) ==================== */

    /** total attachment hazard for a motor at zone coordinate x, filament held at X=Theta=0. */
    public double landscapeHazard(double xMj, boolean respectOcc) {
        double sX = X, sT = Theta; boolean[] so = occupied;
        X = 0; Theta = 0; if (occupied == null) occupied = new boolean[nSites];
        double h = motorHazard(xMj, respectOcc);
        X = sX; Theta = sT; occupied = so;
        return h;
    }
}
