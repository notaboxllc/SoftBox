package softbox;

/**
 * REDUCED LOCAL-FRAME MECHANOCHEMICAL MOTOR — the twirling-mechanism atlas core.
 *
 * <p><b>Noncanonical, self-contained, CPU-only, independent of the production motor.</b> This class does NOT
 * import or depend on {@code TwoBodyBeamAnalyticGpu}, {@code TwoBodyConverterMotor}, the explicit-S2 14-DOF
 * solve, {@code CrossBridgeSystem}, or any {@code bindAzim} offset. It reuses only generic project constants
 * ({@link Constants#kT}) and a counter-based wang-hash Gaussian identical in construction to the project's
 * Brownian streams (so a future GPU port is bit-identical by construction). Report:
 * {@code docs/TWIRLING_MECHANISM_ATLAS_FINDINGS.md}.
 *
 * <h2>Purpose</h2>
 * Answer the inverse question: what is the smallest physically consistent asymmetry or coupling that produces a
 * persistent, same-sign <i>cycle-integrated angular impulse</i> about the actin filament axis under Brownian
 * search, stochastic attachment turnover, and a full mechanochemical cycle? The model is deliberately minimal
 * so mechanism space can be swept quickly, but mechanically complete enough to reject artifacts — in
 * particular the "large frozen torque, zero cycle impulse" failure of the askew-attachment work.
 *
 * <h2>Local site frame (the governing convention, matching {@link ChiralSiteSystem})</h2>
 * <pre>
 *   u = pointed→barbed filament tangent      (axial; the glide / roll axis)
 *   n = outward radial direction             (the moment arm points along n at radius R)
 *   t = u × n                                (local circumferential direction)
 * </pre>
 * Nothing here is a laboratory axis. Every mechanism is defined purely in (u, n, t), so it is azimuth- and
 * rotation-covariant by construction.
 *
 * <h2>Generalized state and energy</h2>
 * Per motor the bond coordinate is {@code q = [x, y, ω]}:
 * <ul>
 *   <li>{@code x} — motor extension/displacement along u (axial; the propulsive coordinate),</li>
 *   <li>{@code y} — motor displacement along t (local circumferential coordinate),</li>
 *   <li>{@code ω} — bound orientational registry about the filament axis u (radians).</li>
 * </ul>
 * For chemical state s the energy is a general quadratic well
 * {@code U_s(q) = ½ (q − q_s)ᵀ K_s (q − q_s)} with {@code q_s} the state rest coordinates and {@code K_s} a
 * symmetric (≽ 0) 3×3 stiffness. Off-diagonals are the chiral couplings:
 * {@code K_xy} (axial–tangential), {@code K_xω} (axial–registry), {@code K_yω} (tangential–registry).
 *
 * <h2>Force / torque / accounting</h2>
 * The strain-force is {@code g = K_s (q − q_s)} — this is the force the motor exerts ON the actin site (the
 * equal-and-opposite of the force on the motor, {@code −g}). The axial (propulsive) force on the filament is
 * {@code F_x = g_x}. The axial torque about u is
 * <pre>
 *   τ_u = R · g_y  +  g_ω                     (moment of the tangential force at radius R, plus the registry couple)
 * </pre>
 * exactly the {@code τ = R_actin · F_tangential} identity of the discrete-site work, extended by an explicit
 * axial registry couple. Both are transmitted to the filament ONLY while the motor is bound.
 *
 * <p><b>The master accounting identity (verified by the fixtures).</b> With the site fixed and the overdamped
 * update {@code γ_i q̇_i = −g_i + ξ_i}, one has {@code g_i = −γ_i q̇_i + ξ_i}, so integrated over a bound
 * episode (noise mean zero):
 * <pre>
 *   J_θ = ∫ τ_u dt = −R γ_y · Δy_bound  −  γ_ω · Δω_bound
 * </pre>
 * The cycle-integrated angular impulse is set ENTIRELY by the NET displacement of the chiral coordinates
 * accrued WHILE BOUND. Rectification therefore requires a chiral coordinate that moves net-nonzero while
 * bound and is reset while unbound. A completed conservative relaxation to an unshifted chiral equilibrium
 * gives {@code Δ_bound = 0} — this is precisely why a zero-rest point spring (askew binding) produced a large
 * frozen torque but a null cycle impulse.
 */
public final class TwirlReducedMotorSystem {
    private TwirlReducedMotorSystem() {}

    // Chemical states.
    public static final int U = 0;   // unbound (search / recovery)
    public static final int W = 1;   // weakly bound
    public static final int P = 2;   // strongly bound, pre power stroke
    public static final int S = 3;   // strongly bound, post power stroke
    public static final int D = 4;   // detached / reset
    public static final int NST = 5;
    public static final String[] STATE_NAME = {"U", "W", "P", "S", "D"};

    public static final double KT = Constants.kT;   // 4.116e-21 J
    public static final double R_ACTIN = 3.5e-9;    // m — actin radius, the moment arm (= Constants.radius in SI)

    // Private counter-based RNG salts (distinct per DOF; no existing project stream is shifted).
    static final long SALT_X  = 0x54574C58L;   // "TWLX"
    static final long SALT_Y  = 0x54574C59L;   // "TWLY"
    static final long SALT_W  = 0x54574C57L;   // "TWLW" (ω)
    static final long SALT_KIN = 0x54574C4BL;  // "TWLK" (kinetics)

    /** Counter-based standard-normal, identical construction to the project's PTX-lowering Brownian streams. */
    public static double gauss(long ep, long t, long salt) {
        long h = ((ep * 2654435761L) ^ (t * 40503L) ^ (salt * 0x9E3779B1L));
        h ^= (h >>> 13); h *= 0x9E3779B1L; h ^= (h >>> 16);
        double u1 = ((h & 0xFFFFFF) + 1) / 16777217.0;
        h ^= (h << 7);
        double u2 = (((h >>> 8) & 0xFFFFFF) + 1) / 16777217.0;
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    /** Counter-based uniform in (0,1), same hash family, for kinetic Poisson draws. */
    public static double uniform(long ep, long t, long salt) {
        long h = ((ep * 2654435761L) ^ (t * 40503L) ^ (salt * 0x9E3779B1L));
        h ^= (h >>> 13); h *= 0x9E3779B1L; h ^= (h >>> 16);
        return ((h & 0xFFFFFF) + 1) / 16777217.0;
    }

    // ===================================================================================================
    // PHYSICAL PARAMETERS (SI: m, N, J, s, rad, N·m·s)
    // ===================================================================================================
    public static final class Params {
        public double gx  = 1.0e-8;    // axial translational drag (N·s/m)   τ_relax,x = gx/Kxx ≈ 10 µs
        public double gy  = 1.0e-8;    // tangential translational drag (N·s/m)
        public double gom = 2.5e-24;   // registry rotational drag (N·m·s)   (head roll drag scale)
        public double dt  = 5.0e-7;    // s (atlas timescale; NOT the production 1e-4)
        public double Ractin = R_ACTIN;
        public double mirror = +1.0;   // actin-frame chirality control (+1 native, −1 mirrored)
        public double polarity = +1.0; // filament polarity (u → polarity·u)
        public double gFilRoll = 1.0e-23; // filament roll drag (N·m·s) — report-only, for the angular-displacement readout

        public Params copy() {
            Params p = new Params();
            p.gx=gx; p.gy=gy; p.gom=gom; p.dt=dt; p.Ractin=Ractin; p.mirror=mirror; p.polarity=polarity; p.gFilRoll=gFilRoll;
            return p;
        }
    }

    // ===================================================================================================
    // MECHANISM SPECIFICATION (per-state rest coords + stiffness + kinetics)
    // ===================================================================================================
    public static final class Mechanism {
        public String id, name, desc;
        public final double[][] qs = new double[NST][3];       // rest coords [x,y,ω] per state
        public final double[][][] Ks = new double[NST][3][3];  // symmetric 3×3 stiffness per state
        public final boolean[] boundState = new boolean[NST];  // transmits force/torque to filament?

        // Kinetics (Stage 2+). Base transition rates (1/s); 0 = no transition.
        public final double[][] rate = new double[NST][NST];
        public int strokeFrom = P, strokeTo = S;               // the power-stroke transition
        // Strain-dependent kinetic modifiers (documented, thermodynamically explicit):
        public double detachAlphaTau = 0.0;    // k_off *= exp(alphaTau * τ_u)      (Family H / A9)
        public double detachAlphaFy  = 0.0;    // k_off *= exp(alphaFy  * g_y)      (Family H)
        public double strokeBeta     = 0.0;    // P→S rate *= exp(strokeBeta * strain)  (Family I / A10)
        public int    strokeStrainDof = 1;     // which strain drives strokeBeta (0=x,1=y,2=ω)

        public Mechanism(String id, String name, String desc) { this.id=id; this.name=name; this.desc=desc; }

        /** Set a symmetric off-diagonal (and its transpose) in every bound state's stiffness. */
        public void setCoupling(int i, int j, double v) {
            for (int s = 0; s < NST; s++) if (boundState[s]) { Ks[s][i][j]=v; Ks[s][j][i]=v; }
        }

        /**
         * A chiral-parameter-reversed copy: flips EVERY chiral knob including the kinetic ones
         * ({@code detachAlphaTau}, {@code detachAlphaFy}, and {@code strokeBeta} when it gates a chiral
         * strain). This is the task's symmetry test #1 ("chiral parameter sign reversal"), distinct from the
         * actin-frame mirror #2: a MOTOR-borne chirality (e.g. torque-dependent detachment) flips under this
         * control but NOT under the actin-frame mirror, whereas an ACTIN-borne chirality flips under both.
         */
        public Mechanism chiralReversed() {
            Mechanism m = mirrored();
            m.id = id + "·rev"; m.name = name + " (chiral-reversed)";
            m.detachAlphaTau = -detachAlphaTau;
            m.detachAlphaFy  = -detachAlphaFy;
            if (strokeStrainDof == 1 || strokeStrainDof == 2) m.strokeBeta = -strokeBeta;
            return m;
        }

        /** A mirror-reflected copy of this mechanism (actin-frame reflection t→−t, ω→−ω about u). */
        public Mechanism mirrored() {
            Mechanism m = new Mechanism(id+"·mir", name+" (mirrored)", desc);
            for (int s=0;s<NST;s++){
                m.boundState[s]=boundState[s];
                m.qs[s][0]= qs[s][0]; m.qs[s][1]= -qs[s][1]; m.qs[s][2]= -qs[s][2];
                for(int a=0;a<3;a++) System.arraycopy(Ks[s][a],0,m.Ks[s][a],0,3);
                // chiral off-diagonals flip: K_xy, K_xω (one chiral factor); K_yω keeps sign (two chiral factors)
                m.Ks[s][0][1]=-Ks[s][0][1]; m.Ks[s][1][0]=-Ks[s][1][0];
                m.Ks[s][0][2]=-Ks[s][0][2]; m.Ks[s][2][0]=-Ks[s][2][0];
            }
            for(int a=0;a<NST;a++) System.arraycopy(rate[a],0,m.rate[a],0,NST);
            m.strokeFrom=strokeFrom; m.strokeTo=strokeTo; m.detachAlphaTau=detachAlphaTau; m.detachAlphaFy=detachAlphaFy;
            m.strokeBeta=strokeBeta; m.strokeStrainDof=strokeStrainDof;
            return m;
        }
    }

    // ===================================================================================================
    // PHYSICS
    // ===================================================================================================
    /** Strain-force g = K_s (q − q_s) (the force the motor exerts on the actin site). Fills g[0..2]. */
    public static void strainForce(double[] q, double[] qs, double[][] K, double[] g) {
        double dx=q[0]-qs[0], dy=q[1]-qs[1], dw=q[2]-qs[2];
        g[0] = K[0][0]*dx + K[0][1]*dy + K[0][2]*dw;
        g[1] = K[1][0]*dx + K[1][1]*dy + K[1][2]*dw;
        g[2] = K[2][0]*dx + K[2][1]*dy + K[2][2]*dw;
    }

    /** Axial torque about u for strain-force g: τ_u = R·g_y + g_ω. */
    public static double torqueU(double[] g, Params p) { return p.Ractin*g[1] + g[2]; }

    /** Potential energy U_s(q) = ½ (q−q_s)ᵀ K_s (q−q_s). */
    public static double energy(double[] q, double[] qs, double[][] K) {
        double[] g = new double[3]; strainForce(q, qs, K, g);
        double dx=q[0]-qs[0], dy=q[1]-qs[1], dw=q[2]-qs[2];
        return 0.5*(g[0]*dx + g[1]*dy + g[2]*dw);
    }

    /**
     * One overdamped Langevin step in state {@code state}. Advances {@code q}; returns the strain-force
     * {@code g} (force on the actin site) in {@code gOut} evaluated at the PRE-step configuration (so the
     * impulse accounting ∫g dt uses the same g the step used). Brownian kicks satisfy FDT with amplitude
     * {@code sqrt(2 γ_i kT / dt)}.
     */
    public static void step(double[] q, Mechanism mech, int state, Params p, long ep, long t,
                            boolean brownian, double[] gOut) {
        strainForce(q, mech.qs[state], mech.Ks[state], gOut);
        double bx=0, by=0, bo=0;
        if (brownian) {
            bx = Math.sqrt(2*p.gx *KT/p.dt)*gauss(ep, t, SALT_X);
            by = Math.sqrt(2*p.gy *KT/p.dt)*gauss(ep, t, SALT_Y);
            bo = Math.sqrt(2*p.gom*KT/p.dt)*gauss(ep, t, SALT_W);
        }
        q[0] += p.dt/p.gx *(-gOut[0] + bx);
        q[1] += p.dt/p.gy *(-gOut[1] + by);
        q[2] += p.dt/p.gom*(-gOut[2] + bo);
    }
}
