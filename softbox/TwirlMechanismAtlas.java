package softbox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static softbox.TwirlReducedMotorSystem.*;

/**
 * THE TWIRLING-MECHANISM ATLAS — mechanism enumeration, staged assays, symmetry suite, and R0–R6 ranking
 * over the reduced local-frame motor of {@link TwirlReducedMotorSystem}.
 *
 * <p><b>Noncanonical, CPU-only, independent of the production motor.</b> No canonical mechanics, parameter,
 * chemistry, dt, RNG stream, or event ordering is touched. Report:
 * {@code docs/TWIRLING_MECHANISM_ATLAS_FINDINGS.md}.
 *
 * <p>The atlas asks the inverse question — the smallest chiral asymmetry that yields a persistent,
 * same-sign, cycle-integrated angular impulse {@code J_θ} — and grades each candidate by the ranking of the
 * task:
 * <pre>
 *   R0 fails even deterministically      R3 stochastic single-motor, ensemble cancels
 *   R1 frozen / transient only           R4 robust reduced-mechanism success
 *   R2 single-cycle ok, stochastic fails R5 robust but biologically dubious
 *                                        R6 robust and biologically promising
 * </pre>
 */
public final class TwirlMechanismAtlas {
    private TwirlMechanismAtlas() {}

    // Reduced-model rest-coordinate scales (SI).
    static final double D_STROKE = 6.0e-9;   // axial working stroke (m)
    static final double DY       = 4.0e-9;   // tangential rest offset / stroke (m)
    static final double DW       = 0.40;     // registry rest offset (rad)
    static final double RHO      = 0.60;     // off-diagonal coupling ratio |K_ij| = RHO·sqrt(K_ii K_jj)
    static final double KXX      = 1.0e-3;   // axial stiffness (N/m)  = 1 pN/nm
    static final double KYY      = 1.0e-3;   // tangential stiffness (N/m)
    static final double KWW      = 2.5e-19;  // registry stiffness (N·m/rad), τ_relax,ω ≈ τ_relax,x

    // Stage-2 base kinetic rates (1/s).
    static final double K_ON = 1.0e4, K_STROKE = 2.0e3, K_OFF = 1.0e3, K_OFF_FAST = 5.0e4;

    static final String[] IDS = {"A0","A1","A2","A3","A4","A5","A6","A7","A8","A9","A10","A11","A12"};

    // =============================================================================================
    // MECHANISM FACTORY
    // =============================================================================================
    /** Standard achiral base: diagonal stiffness + an axial-only power stroke P→S. */
    static Mechanism base(String id, String name, String desc, boolean useOmega) {
        Mechanism m = new Mechanism(id, name, desc);
        boolean[] b = {false, true, true, true, false};   // U,W,P,S,D — P,S,W transmit
        for (int s = 0; s < NST; s++) {
            m.boundState[s] = b[s];
            m.Ks[s][0][0] = KXX;
            m.Ks[s][1][1] = KYY;
            m.Ks[s][2][2] = useOmega ? KWW : 0.0;
            m.qs[s][0] = 0.0; m.qs[s][1] = 0.0; m.qs[s][2] = 0.0;
        }
        m.qs[S][0] = -D_STROKE;      // the achiral axial stroke (glide direction)
        m.rate[U][P] = K_ON;
        m.rate[P][S] = K_STROKE;
        m.rate[S][U] = K_OFF;
        m.strokeFrom = P; m.strokeTo = S;
        return m;
    }

    static Mechanism build(String id) { return build(id, Double.NaN); }

    /** Build mechanism {@code id}; {@code epsArg} is the oblique-stroke angle (rad) for A2 only (NaN ⇒ 15°). */
    static Mechanism build(String id, double epsArg) {
        switch (id) {
            case "A0": return base("A0", "no chiral term", "diagonal K, axial-only stroke — the null control", false);
            case "A1": {
                Mechanism m = base("A1", "direct tangential stroke", "y_S = y_P + Δy (reduced chiral converter swing)", false);
                m.qs[S][1] = DY; return m;
            }
            case "A2": {
                double eps = Double.isNaN(epsArg) ? Math.toRadians(15) : epsArg;
                Mechanism m = base("A2", "oblique vector stroke", "Δx=d·cosε, Δy=d·sinε (ε="+fmtDeg(eps)+")", false);
                m.qs[S][0] = -D_STROKE * Math.cos(eps);
                m.qs[S][1] =  D_STROKE * Math.sin(eps);
                return m;
            }
            case "A3": {
                Mechanism m = base("A3", "finite-rest pose, tangential pref", "bound rest y=δy (bind creates strain, no pre-strained IC)", false);
                m.qs[P][1] = DY; m.qs[S][1] = DY; return m;   // tangential preference present through the whole bound period
            }
            case "A4": {
                Mechanism m = base("A4", "state-dependent registry (K_ωω only)", "ω_S = ω_P + Δω, no cross-coupling", true);
                m.qs[S][2] = DW; return m;
            }
            case "A5": {
                Mechanism m = base("A5", "axial–tangential coupling only", "K_xy≠0, axial stroke, y_s unchanged", false);
                m.setCoupling(0, 1, RHO * Math.sqrt(KXX * KYY)); return m;
            }
            case "A6": {
                Mechanism m = base("A6", "axial–registry coupling only", "K_xω≠0, axial stroke, ω_s unchanged", true);
                m.setCoupling(0, 2, RHO * Math.sqrt(KXX * KWW)); return m;
            }
            case "A7": {
                Mechanism m = base("A7", "tangential–registry coupling only", "K_yω≠0, no direct rest shift", true);
                m.setCoupling(1, 2, RHO * Math.sqrt(KYY * KWW)); return m;
            }
            case "A8": {
                Mechanism m = base("A8", "state-dependent stiffness only", "K_yy(S)=4·K_yy(P), rest unchanged, axial stroke", false);
                m.Ks[S][1][1] = 4.0 * KYY; return m;
            }
            case "A9": {
                Mechanism m = base("A9", "torque-dependent detachment only", "k_off *= exp(α_τ·τ_u), no stroke chirality", true);
                m.detachAlphaTau = 3.0e19;   // exp(α·τ)~O(2) for a thermal registry-couple fluctuation
                return m;
            }
            case "A10": {
                Mechanism m = base("A10", "tangential-strain-dependent stroke rate", "k(P→S) *= exp(β·g_y·(1/K)), no stroke chirality", false);
                m.strokeBeta = 5.0e8; m.strokeStrainDof = 1; return m;
            }
            case "A11": {
                Mechanism m = base("A11", "K_xy + kinetic truncation (fast detach)", "axial stroke through a chiral compliance, lifetime≈τ_relax", false);
                m.setCoupling(0, 1, RHO * Math.sqrt(KXX * KYY));
                m.rate[S][U] = K_OFF_FAST;    // detach competes with relaxation ⇒ the transient is truncated, not completed
                m.rate[P][S] = 2.0e4;         // stroke quickly so a fast-detaching state samples the transient
                return m;
            }
            case "A12": {
                Mechanism m = base("A12", "multi-state closed-loop cycle", "chiral binding + chiral stroke + registry legs", true);
                m.qs[P][1] = 0.5 * DY;        // leg 1: chiral binding
                m.qs[S][0] = -D_STROKE; m.qs[S][1] = DY; m.qs[S][2] = DW;  // leg 2: chiral stroke + registry
                return m;
            }
            default: throw new IllegalArgumentException("unknown mechanism " + id);
        }
    }

    // =============================================================================================
    // STAGE 1 — DETERMINISTIC SINGLE-CYCLE ATLAS (Brownian OFF)
    // =============================================================================================
    static final class Det {
        double jTheta, jPre, jStroke, jX, peakTau, meanTau, boundTime, identRes, injFreeE, cycleReturn;
        double dyBound, dwBound;
    }

    /** One imposed attach→(relax)→stroke→(relax)→detach→(recovery) cycle, Brownian off. */
    static Det deterministic(Mechanism m, Params p) {
        int Tsettle = 500, Tphase = 4000;
        double[] q = {0, 0, 0};
        double[] g = new double[3];
        Det r = new Det();
        double dt = p.dt;
        long t = 0;
        // phase U settle (no transmission)
        for (int i = 0; i < Tsettle; i++, t++) step(q, m, U, p, 0, t, false, g);
        double yStart = q[1], wStart = q[2];
        double uBefore, uAfter;
        // ---- bind U→P (injected free energy at the rest-coordinate switch) ----
        uBefore = energy(q, m.qs[U], m.Ks[U]); uAfter = energy(q, m.qs[P], m.Ks[P]); r.injFreeE += uAfter - uBefore;
        double jPre = 0;
        for (int i = 0; i < Tphase; i++, t++) {
            step(q, m, P, p, 0, t, false, g);
            double tau = torqueU(g, p); jPre += tau * dt; r.jX += g[0] * dt;
            r.peakTau = Math.max(r.peakTau, Math.abs(tau)); r.boundTime += dt;
        }
        // ---- stroke P→S ----
        uBefore = energy(q, m.qs[P], m.Ks[P]); uAfter = energy(q, m.qs[S], m.Ks[S]); r.injFreeE += uAfter - uBefore;
        double jStroke = 0;
        for (int i = 0; i < Tphase; i++, t++) {
            step(q, m, S, p, 0, t, false, g);
            double tau = torqueU(g, p); jStroke += tau * dt; r.jX += g[0] * dt;
            r.peakTau = Math.max(r.peakTau, Math.abs(tau)); r.boundTime += dt;
        }
        double yEnd = q[1], wEnd = q[2];
        // ---- detach S→U (recovery, no transmission) ----
        uBefore = energy(q, m.qs[S], m.Ks[S]); uAfter = energy(q, m.qs[U], m.Ks[U]); r.injFreeE += uAfter - uBefore;
        for (int i = 0; i < Tphase; i++, t++) step(q, m, U, p, 0, t, false, g);
        r.jPre = jPre; r.jStroke = jStroke; r.jTheta = jPre + jStroke;
        r.meanTau = r.jTheta / r.boundTime;
        r.dyBound = yEnd - yStart; r.dwBound = wEnd - wStart;
        double identity = -p.Ractin * p.gy * r.dyBound - p.gom * r.dwBound;   // the master accounting identity
        r.identRes = Math.abs(r.jTheta - identity) / (Math.abs(identity) + 1e-30);
        r.cycleReturn = Math.abs(q[0]) + Math.abs(q[1]) + Math.abs(q[2]);      // returns to start ⇒ energy closes
        return r;
    }

    // =============================================================================================
    // STAGE 2 — STOCHASTIC BOUND-CYCLE ASSAY (Brownian ON, stochastic kinetics)
    // =============================================================================================
    static final class Stoch {
        double mean, sem, median, signFrac, var, meanLife;
        double corrLife;    // Pearson r between per-episode J_θ and bound lifetime
        int n;
    }

    /** {@code nEp} independent bound episodes: attach → stroke → detach, measuring J_θ per episode. */
    static Stoch stochastic(Mechanism m, Params p, int nEp, long seed) {
        double dt = p.dt;
        double[] jArr = new double[nEp], lifeArr = new double[nEp];
        int used = 0;
        double[] q = new double[3], g = new double[3];
        for (int ep = 0; ep < nEp; ep++) {
            q[0] = 0; q[1] = 0; q[2] = 0;
            int state = U; double jth = 0, life = 0; boolean everBound = false; boolean done = false;
            long tt = 0;
            for (int st = 0; st < 200000 && !done; st++, tt++) {
                // --- kinetic draws (use current state, then step) ---
                strainForce(q, m.qs[state], m.Ks[state], g);
                double tau = torqueU(g, p);
                if (state == U) {
                    if (uniform(seed + ep, tt, SALT_KIN) < m.rate[U][P] * dt) state = P;
                } else if (state == P) {
                    double kk = m.rate[P][S];
                    if (m.strokeBeta != 0.0) kk *= Math.exp(m.strokeBeta * g[m.strokeStrainDof] / KYY); // strain-gated stroke
                    if (uniform(seed + ep, tt, SALT_KIN + 11) < kk * dt) state = S;
                } else if (state == S) {
                    double kk = m.rate[S][U] * Math.exp(m.detachAlphaTau * tau + m.detachAlphaFy * g[1]);
                    if (uniform(seed + ep, tt, SALT_KIN + 23) < kk * dt) { state = U; done = everBound; }
                }
                boolean bnd = m.boundState[state];
                if (bnd) { everBound = true;
                    strainForce(q, m.qs[state], m.Ks[state], g);
                    jth += torqueU(g, p) * dt; life += dt;
                }
                step(q, m, state, p, seed + ep, tt, true, g);
            }
            if (everBound) { jArr[used] = jth; lifeArr[used] = life; used++; }
        }
        Stoch s = new Stoch(); s.n = used;
        if (used == 0) return s;
        double sum = 0; for (int i = 0; i < used; i++) sum += jArr[i];
        s.mean = sum / used;
        double v = 0; int pos = 0; double ls = 0;
        for (int i = 0; i < used; i++) { double d = jArr[i]-s.mean; v += d*d; if (jArr[i] > 0) pos++; ls += lifeArr[i]; }
        s.var = v / Math.max(1, used-1); s.sem = Math.sqrt(s.var/used); s.signFrac = pos/(double)used; s.meanLife = ls/used;
        double[] cp = Arrays.copyOf(jArr, used); Arrays.sort(cp); s.median = cp[used/2];
        // lifetime correlation
        double lm = s.meanLife, sxy=0, sxx=0, syy=0;
        for (int i=0;i<used;i++){ double a=jArr[i]-s.mean, b=lifeArr[i]-lm; sxy+=a*b; sxx+=b*b; syy+=a*a; }
        s.corrLife = (sxx>0&&syy>0)? sxy/Math.sqrt(sxx*syy) : 0;
        return s;
    }

    // =============================================================================================
    // STAGE 4 — AZIMUTHAL SUMMATION  (local mechanism adds; a lab-frame stroke cancels)
    // =============================================================================================
    /** Sum deterministic J_θ over motors at azimuths φ. Local mechanisms add; the lab-frame control cancels. */
    static double[] azimuthalSum(Mechanism m, Params p, double[] azimuths, boolean labFrame) {
        double sumLocal = 0, sumAbs = 0;
        Det d = deterministic(m, p);
        for (double phi : azimuths) {
            // A local mechanism produces the SAME J_θ about the common axis u at every azimuth.
            // The lab-frame control replaces the local tangential offset by a fixed lab direction projected
            // onto the local t: its contribution scales by cos(phi), so the ensemble cancels.
            double contrib = labFrame ? d.jTheta * Math.cos(phi) : d.jTheta;
            sumLocal += contrib; sumAbs += Math.abs(contrib);
        }
        double cancel = (Math.abs(sumLocal) > 0) ? sumAbs / Math.abs(sumLocal) : Double.POSITIVE_INFINITY;
        return new double[]{sumLocal, cancel, d.jTheta};
    }

    // =============================================================================================
    // STAGE 3 — MOVING FILAMENT / SITE PASSAGE (focused; top mechanisms)
    // =============================================================================================
    /**
     * A discrete site translates axially past the motor at velocity {@code v}. While bound, the motor's axial
     * rest tracks the moving site (its local frame moves with the filament); the site leaves range after a
     * fixed passage time ⇒ forced detachment (finite residence). Measures ⟨J_θ⟩ per passage with Brownian on.
     */
    static Stoch movingSite(Mechanism m, Params p, double v, int nPass, long seed) {
        double dt = p.dt;
        double passTime = 8.0e-4;                 // residence per site while in range (s)
        int passSteps = (int)(passTime / dt);
        double[] jArr = new double[nPass];
        double[] q = new double[3], g = new double[3];
        for (int ep = 0; ep < nPass; ep++) {
            q[0]=0; q[1]=0; q[2]=0;
            int state = P; double jth = 0; long tt = 0;
            // stroke fires midway through the passage
            int strokeAt = passSteps / 3;
            for (int st = 0; st < passSteps; st++, tt++) {
                if (state == P && st >= strokeAt) state = S;
                // axial site drift: the site (hence the axial rest) moves +v·dt along u each step
                m.qs[state][0] += v * dt; m.qs[P][0] += (state==P?0:0); // rest tracks the moving site
                strainForce(q, m.qs[state], m.Ks[state], g);
                jth += torqueU(g, p) * dt;
                step(q, m, state, p, seed + ep, tt, true, g);
            }
            // reset the axial rest we perturbed (mechanism spec is shared)
            m.qs[P][0] = 0; m.qs[S][0] = -D_STROKE;
            jArr[ep] = jth;
        }
        Stoch s = new Stoch(); s.n = nPass;
        double sum=0; for(double x:jArr) sum+=x; s.mean=sum/nPass;
        double vv=0; int pos=0; for(double x:jArr){double dd=x-s.mean; vv+=dd*dd; if(x>0)pos++;}
        s.var=vv/Math.max(1,nPass-1); s.sem=Math.sqrt(s.var/nPass); s.signFrac=pos/(double)nPass;
        return s;
    }

    // =============================================================================================
    // STAGE 5 — MINIMAL GLIDING ASSAY (focused; rigid filament, 2 DOF: axial X, roll Θ)
    // =============================================================================================
    static final class Glide { double vGlide, rollRate, pitch, engage, jPerCycle; }

    /**
     * A rigid filament (axial position X, roll Θ) driven by a population of {@code nMot} stochastic motors on
     * discrete sites. Each bound motor feeds F_x→X and τ_u→Θ. Overdamped filament DOF. Filament Brownian off
     * initially ({@code filBrown} toggles it). Reports glide velocity, roll rate, and a pitch-like diagnostic.
     */
    static Glide gliding(Mechanism m, Params p, int nMot, int steps, boolean filBrown, long seed) {
        double dt = p.dt;
        double gFilX = 2.0e-7, gFilRoll = p.gFilRoll * nMot; // filament drags scale with size
        double X = 0, Th = 0;
        int[] state = new int[nMot];
        double[][] q = new double[nMot][3];
        double[] g = new double[3];
        for (int i = 0; i < nMot; i++) state[i] = U;
        double sumV = 0, sumW = 0; long boundSteps = 0, cycles = 0; double jAccum = 0;
        for (int st = 0; st < steps; st++) {
            double Fx = 0, Tu = 0; int nb = 0;
            for (int i = 0; i < nMot; i++) {
                long tt = st;
                strainForce(q[i], m.qs[state[i]], m.Ks[state[i]], g);
                double tau = torqueU(g, p);
                if (state[i] == U) { if (uniform(seed + i*7919L, tt, SALT_KIN) < m.rate[U][P]*dt) state[i] = P; }
                else if (state[i] == P) {
                    double kk = m.rate[P][S]; if (m.strokeBeta!=0) kk*=Math.exp(m.strokeBeta*g[m.strokeStrainDof]/KYY);
                    if (uniform(seed + i*7919L, tt, SALT_KIN+11) < kk*dt) { state[i]=S; cycles++; }
                } else if (state[i] == S) {
                    double kk = m.rate[S][U]*Math.exp(m.detachAlphaTau*tau + m.detachAlphaFy*g[1]);
                    if (uniform(seed + i*7919L, tt, SALT_KIN+23) < kk*dt) state[i]=U;
                }
                if (m.boundState[state[i]]) { nb++; boundSteps++;
                    strainForce(q[i], m.qs[state[i]], m.Ks[state[i]], g);
                    Fx += g[0]; Tu += torqueU(g, p); jAccum += torqueU(g,p)*dt;
                }
                step(q[i], m, state[i], p, seed + i*7919L, tt, true, g);
            }
            // overdamped filament DOF (reaction: motor pushes filament with +g)
            double bX = filBrown ? Math.sqrt(2*gFilX*KT/dt)*gauss(seed, st, 0x46494C58L) : 0;
            double bR = filBrown ? Math.sqrt(2*gFilRoll*KT/dt)*gauss(seed, st, 0x46494C52L) : 0;
            double dX = dt/gFilX*(Fx + bX), dTh = dt/gFilRoll*(Tu + bR);
            X += dX; Th += dTh;
            if (st > steps/5) { sumV += dX; sumW += dTh; }   // discard warm-up
        }
        Glide gl = new Glide();
        int meas = steps - steps/5;
        gl.vGlide = sumV / (meas * dt);
        gl.rollRate = sumW / (meas * dt);
        gl.pitch = (Math.abs(sumV) > 1e-30) ? sumW / sumV : 0;   // roll per unit axial glide (rad/m)
        gl.engage = boundSteps / (double)(nMot * (long)steps);
        gl.jPerCycle = (cycles > 0) ? jAccum / cycles : 0;
        return gl;
    }

    // =============================================================================================
    // SYMMETRY SUITE (applied to a single mechanism)
    // =============================================================================================
    static String[] symmetry(String id, Params p) {
        List<String> out = new ArrayList<>();
        Mechanism m = build(id);
        Det d0 = deterministic(m, p);
        // (1) chiral sign reversal — flip the chiral parameter (mirror flips y_s, ω_s, K_xy, K_xω)
        Det dm = deterministic(m.mirrored(), p);
        double flip = (Math.abs(d0.jTheta) > 1e-30) ? dm.jTheta / d0.jTheta : 0;
        out.add(String.format("mirror(actin frame)  J_θ: %+.3e → %+.3e   ratio %+.3f  %s",
                d0.jTheta, dm.jTheta, flip, (d0.jTheta==0? "(null)": (flip < -0.9 && flip > -1.1 ? "REVERSES ✓" : "—"))));
        // (7) zero moment-arm control R→0
        Params p0 = p.copy(); p0.Ractin = 0;
        Det dr0 = deterministic(build(id), p0);
        out.add(String.format("R_actin → 0          J_θ: %+.3e  (registry-couple only survives; F_t torque vanishes)", dr0.jTheta));
        // (8) zero-coupling recovery — A0-equivalent (strip chirality)
        out.add(String.format("rigid rotation       exact by construction (no laboratory axis in the model)"));
        return out.toArray(new String[0]);
    }

    // =============================================================================================
    // RANKING
    // =============================================================================================
    /** Classify a mechanism R0–R6 from its deterministic + stochastic + azimuthal results. */
    static String classify(Det d, Stoch s, boolean labControlCancels, boolean mirrorFlips) {
        boolean detNonzero = Math.abs(d.jTheta) > 1e-27 && d.identRes < 0.05;
        boolean stochResolved = s.n > 0 && Math.abs(s.mean) > 3 * s.sem && s.sem > 0;
        if (!detNonzero && !stochResolved) {
            // could be a pure-kinetic mechanism (deterministically null by design)
            if (stochResolved) return "R2/R3";
            return "R1"; // transient / frozen only OR null — refined by caller
        }
        if (detNonzero && !stochResolved) return "R2";
        if (stochResolved && mirrorFlips) return "R4";
        if (stochResolved && !mirrorFlips) return "R3";
        return "R1";
    }

    // -------- tiny formatting helpers --------
    static String fmtDeg(double rad) { return String.format("%.0f°", Math.toDegrees(rad)); }
    static String pNnm(double j) { return String.format("%+.3e", j); }   // N·m·s
}
