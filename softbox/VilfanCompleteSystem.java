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
        /** absolute safety nets. A zero-velocity control (d = 0) never reaches a travel target, so
         *  every run also carries an event and a simulated-time cap. Hitting one is REPORTED. */
        public long   maxEvents   = 200_000_000L;
        public double maxSimTimeS = Double.POSITIVE_INFINITY;

        /** measure the no-depletion (shadow) attachment landscape alongside the real run. */
        public boolean noDepletionControl = false;

        public double equilTolRad = 1e-13;
        public int    equilMaxIter = 200;

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
    }

    public Config config()   { return c; }
    public int    nSites()   { return nSites; }
    public double targetZonePeriodNm() { return LNm; }
    public double grooveSlopeRadPerNm(){ return grooveSlope; }

    /** azimuth of site i relative to the filament frame: (i*theta0) mod 2pi, EXACT (no drift). */
    public double baseAzim(int i) { return azimTable[Math.floorMod(i, c.latQ)]; }

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

    private int nearestSite(double xMj) {
        int i0 = (int) Math.rint((xMj - X) / a);
        if (i0 < 0) i0 = 0;
        if (i0 > nSites - 1) i0 = nSites - 1;
        return i0;
    }

    /** sum_i kA_i for a detached motor; ignores occupancy if {@code respectOccupancy} is false. */
    private double motorHazard(double xMj, boolean respectOccupancy) {
        int i0 = nearestSite(xMj);
        int lo = Math.max(0, i0 - c.window), hi = Math.min(nSites - 1, i0 + c.window);
        double sum = 0.0;
        for (int i = lo; i <= hi; i++) {
            if (respectOccupancy && c.singleOccupancy && occupied[i]) continue;
            sum += siteHazard(X, Theta, xMj, i);
        }
        return sum;
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
        double shadowW = 0, shadowWX = 0, shadowWTh = 0;
        // least-squares accumulators over the analysed window
        double lsN = 0, lsT = 0, lsT2 = 0, lsX = 0, lsTX = 0, lsTh = 0, lsTTh = 0;

        boolean capHit = false;

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
            if (!(ktotal > 0.0)) {
                R.note = "HALT: ktotal = 0 (no legal transition) at t=" + t + " X=" + X;
                break;
            }

            // ---------- step 3: waiting time ----------
            double dt = expWait(rngTime, ktotal);

            // ---------- time-weighted accumulation over [t, t+dt) ----------
            boolean analysed = (tW >= 0.0);
            if (analysed) {
                for (int j = 0; j < nM; j++) occ[state[j]] += dt;
                boundTime += nb * dt;
                double sF = 0, sM = 0;
                for (int j = 0; j < nM; j++) {
                    if (state[j] == DETACHED) continue;
                    double dj = (state[j] == PRE_PS) ? 0.0 : delta;
                    sF += headForce(X, xM[j], siteOf[j], dj);
                    sM += headTorque(Theta, siteOf[j]);
                }
                sForceT += sF * dt; sTorqT += sM * dt;
                if (nb > 0) { sForcePer += (sF / nb) * dt; sTorqPer += (sM / nb) * dt; }
                wT += dt;
                lsN += dt; lsT += t * dt; lsT2 += t * t * dt;
                lsX += X * dt; lsTX += t * X * dt; lsTh += Theta * dt; lsTTh += t * Theta * dt;

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
            if (kind == 0) {
                if (chosenSite < 0) { R.note = "BLOCKER: no legal site for chosen attachment"; break; }
                // observables are read AT THE INSTANT OF BINDING, before re-equilibration
                double xz  = zoneCoord(X, Theta, xM[chosen]);
                double xiA = strain(X, xM[chosen], chosenSite);
                double thA = wrapPi(Theta + baseAzim(chosenSite));
                state[chosen] = PRE_PS; siteOf[chosen] = chosenSite; occupied[chosenSite] = true;
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

            equilibrate();
            nEv++;

            // ---------- warm-up boundary + step 6: termination ----------
            // progress is measured along the direction the stroke drives (polarity), so the same
            // rule terminates a native run and a polarity-reversed control.
            double prog = c.polarity * X;
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
        R.nEvents = nEv; R.nAttach = nAtt; R.nStroke = nPS; R.nAdpRelease = nADP; R.nDetach = nDet;
        R.meanBound = (dT > 0) ? boundTime / dT : 0.0;
        R.dutyRatio = (nM > 0) ? R.meanBound / countReachable() : 0.0;
        double occTot = occ[0] + occ[1] + occ[2] + occ[3];
        for (int s = 0; s < 4; s++) R.stateOccupancy[s] = (occTot > 0) ? occ[s] / occTot : 0.0;
        if (nXa > 0) {
            R.meanXa = sXa / nXa;  R.sdXa  = sd(sXa, sXa2, nXa);
            R.meanXiA= sXiA / nXa; R.sdXiA = sd(sXiA, sXiA2, nXa);
            R.meanThA= sThA / nXa; R.sdThA = sd(sThA, sThA2, nXa);
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
