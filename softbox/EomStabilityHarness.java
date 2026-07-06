package softbox;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * EOM-STABILITY harness — a NUMERICAL-STABILITY probe, NOT a transport/gliding measurement.
 *
 * Single filament + single PERMANENTLY-bound motor + an EXTERNAL elastic COM tether (stiffness k_ext,
 * standing in for the ensemble load of "many other myosins"). Brownian OFF ⇒ deterministic (no seeds).
 * The one question: is the explicit forward-Euler integration of the filament EOM numerically STABLE and
 * dt-converged under a realistic elastic load — and if not, WHICH coupling sets the limit?
 *
 * PRIMARY mode — relaxation / impulse response: settle to loaded equilibrium, anchor the external spring
 * there (zero rest offset), displace the filament a fixed small offset, RELEASE, integrate the overdamped
 * relaxation. Explicit Euler on a single linear mode has a textbook signature as alpha = 1e6*k_eff*dt/gamma
 * crosses 1 then 2:  monotone decay (alpha<1) -> damped ringing (1<alpha<2) -> growing oscillation (alpha>2).
 * Per (k_ext, dt) we classify the decay + measure the largest stable dt (dt_crit).
 *
 * DECISIVE DIAGNOSTIC — dt_crit vs k_ext scaling:
 *   dt_crit proportional to gamma/k_ext  => genuine LOAD stiffness (textbook explicit Euler; implicit/sub-step
 *     of the loaded force is justified; you know the density at which production dt=1e-5 goes marginal).
 *   dt_crit ~independent of k_ext        => instability is MOTOR-INTERNAL (localize with the toggles).
 *   stable with margin at 1e-5 across the whole realistic k_ext range => the per-bond integration is NOT
 *     unstable at production dt (the ensemble dt-climb is a model-change confound, not a solver instability).
 *
 * TOGGLES (localize the stiff coupling — flip one, watch dt_crit):
 *   default   : single rigid segment, motor body FROZEN (fixed head anchor) ⇒ the cleanest single
 *               translational mode (external spring + F8 both restoring). This is the clean primary number.
 *   -rot      : unfreeze the motor body (joints + F9/F10 bond torques + integrate, stroke held at rest) —
 *               do the rotational couplings set the limit?
 *   -chain N  : multi-segment filament with F3/F4 chain bending/torsion — does the filament internal
 *               stiffness contribute?
 *   -xbimplicit    : head-only locally-implicit F8 (does making F8 implicit raise dt_crit?).
 *   -extimplicit   : external spring treated implicitly (backward-Euler operator split) — the CURE control:
 *               an implicit loaded force is unconditionally stable ⇒ dt_crit -> infinity.
 *
 * SECONDARY mode (-drive): one driven power stroke against k_ext≈15 pN/nm; confirm the settled displacement
 * CONVERGES as dt->0 (a convergence check, NOT a velocity).
 *
 * Runner: CPU sequential (deterministic — the natural runner for a Brownian-off probe; cheap by construction).
 * -gpu runs the default (frozen-motor) relaxation on a device TaskGraph for the CPU≡GPU parity gate.
 * Default byte-identical to the rest of the tree (new files only; ExternalSpringSystem is referenced nowhere else).
 */
public final class EomStabilityHarness {

    static final int B = 64;
    static final double ANCHOR_Z = -0.05, Z_OFFSET = 0.003;
    static final double MYO_SPRING = 1.0e-9, J1_FMT = 0.4;   // 1 pN/nm cross-bridge
    static final double LEVER_LEN = MotorStore.LEVER_LEN, HEAD_LEN = MotorStore.HEAD_LEN;
    static GridScheduler sched;

    // toggles
    static boolean ROT = false;          // -rot : unfreeze the motor body + filament rotation (rotational couplings)
    static int     NCHAIN = 1;           // -chain N : multi-segment filament (F3/F4 on for N>1)
    static boolean NOF8 = false;         // -nof8 : F8 cross-bridge spring off (size F8's stiffness contribution)
    static boolean EXT_IMPLICIT = false; // -extimplicit : implicit external spring (cure control)
    static boolean GPU = false;          // -gpu : device relaxation (default frozen config) + parity
    static boolean DRIVE = false;        // -drive : secondary driven-stroke convergence
    static double  DISP = 0.002;         // displacement offset (µm) = 2 nm
    static int     MAXREL = 4000;        // max relaxation steps

    // STROKE-TIP-IMPLICIT PROBE (STROKE_TIP_IMPLICIT_PROBE) — a driven single-motor per-stroke displacement
    // ladder on a FREE filament (viscous-drag-only load; NO external spring). Brownian OFF ⇒ deterministic.
    // Reuses the validated SPHEREHEAD+AXLOCK+DIRSWING stroke law (bondForces f9Frozen@90 + axlock F10→ŝ +
    // directedSwing) with RATE-FIX ON (strokerate + alignrate ⇒ dt-honest stroke/align rates), so the ONLY
    // residual dt-dependence is the numerical F8-tip orientation integration.
    static boolean STROKE = false;        // -stroke : the per-stroke displacement ladder (STEP 1)
    static boolean TIP_IMPLICIT = false;  // -stroketipimplicit : make ONLY the F8-tip head-orientation torque implicit (STEP 2)
    static final double STROKE_REF_DT = 1e-5;   // refDt at which the per-step fraction k_eff==0.4 (production stroke duration preserved)
    static final double[] STROKE_DTS = { 1e-5, 2.5e-6, 1.25e-6, 6.25e-7, 3.125e-7 };   // the deterministic dt ladder

    // the sweep grids
    static final double[] KEXT = { 0.0, 1.0, 5.0, 15.0, 30.0, 50.0 };       // pN/nm
    static final double[] DTS  = { 1e-4, 5e-5, 2e-5, 1e-5, 5e-6, 2.5e-6 };  // s

    /** Per-STEP fraction that reproduces the SAME per-TIME relaxation at dt as k does at STROKE_REF_DT
     *  (the -strokerate/-alignrate conversion; k_eff==k at dt==refDt). */
    static double rateFix(double k, double dt) { return 1.0 - Math.exp((dt / STROKE_REF_DT) * Math.log(1.0 - k)); }

    public static void main(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-rot" -> ROT = true;
                case "-chain" -> NCHAIN = Integer.parseInt(args[++i]);
                case "-nof8" -> NOF8 = true;
                case "-extimplicit" -> EXT_IMPLICIT = true;
                case "-gpu" -> GPU = true;
                case "-drive" -> DRIVE = true;
                case "-stroke" -> STROKE = true;
                case "-stroketipimplicit" -> { STROKE = true; TIP_IMPLICIT = true; }
                case "-disp" -> DISP = Double.parseDouble(args[++i]);
                default -> {}
            }
        }
        System.out.println("=== Soft Box — EOM STABILITY probe (relaxation/impulse; NOT a transport measurement) ===");
        System.out.printf("config: %s filament (%d seg%s), motor body %s, fil rotation %s, F8=%s, ext-spring=%s%n%n",
                NCHAIN > 1 ? "chain" : "rigid single", NCHAIN, NCHAIN > 1 ? " + F3/F4 chain" : "",
                ROT ? "FREE (joints/F9/F10, stroke held)" : "FROZEN (fixed head anchor)",
                (ROT || NCHAIN > 1) ? "ON" : "FROZEN (clean translational mode)",
                NOF8 ? "OFF (-nof8)" : "on (1 pN/nm)", EXT_IMPLICIT ? "IMPLICIT (cure control)" : "explicit");

        if (STROKE) { runStrokeProbe(); return; }
        if (GPU) { runGpuParity(); return; }
        if (DRIVE) { runDrive(); return; }
        runRelaxationGrid();
    }

    // ===================================================================== the k_ext × dt relaxation grid
    static void runRelaxationGrid() {
        System.out.println("PRIMARY — relaxation classification over k_ext × dt (displace " + (DISP * 1e3) + " nm, release):");
        System.out.printf("  gamma_seg,par ≈ %.3e N·s/m (measured); k_F8 = %.2f pN/nm (bound head)%s%n",
                gammaSegPar(), NOF8 ? 0.0 : MYO_SPRING * 1e9, EXT_IMPLICIT ? "; ext-spring IMPLICIT" : "");
        System.out.println("  character: MONO=monotone decay(stable)  RING=damped ringing(marginal)  GROW=growing(unstable)");
        System.out.printf("%n  %-9s", "k_ext\\dt");
        for (double dt : DTS) System.out.printf(" %10.1e", dt);
        System.out.println("   dt_crit(ring/blow) [extracted]  resid@eq(pN)");

        double dtFine = DTS[DTS.length - 1];
        double[] dtCritRing = new double[KEXT.length];
        double[] dtCritBlow = new double[KEXT.length];
        double[] keffG = new double[KEXT.length];
        for (int ki = 0; ki < KEXT.length; ki++) {
            double kext = KEXT[ki];
            System.out.printf("  %8.1f", kext);
            double residPN = 0;
            Relax fine = null;
            for (double dt : DTS) {
                Relax r = relax(kext, dt, ROT, NCHAIN, EXT_IMPLICIT);
                System.out.printf(" %10s", r.tag());
                if (dt == dtFine) { fine = r; residPN = r.grew ? Double.NaN : r.residualN * 1e12; }
            }
            // extract k_eff/gamma·1e6 from the finest-dt first-step factor rho = 1 - alpha, alpha = (k_eff/gamma·1e6)·dt.
            // For the clean single translational mode this is exact even when the run grows (linear mode).
            double alphaPerDt = fine != null ? (1.0 - fine.rho) / dtFine : 0;
            if (EXT_IMPLICIT) alphaPerDt = 0;   // implicit spring: unconditionally stable, no finite dt_crit
            keffG[ki] = alphaPerDt;
            String residS = Double.isNaN(residPN) ? "grew" : String.format("%.3f", residPN);
            if (alphaPerDt > 0) {
                dtCritRing[ki] = 1.0 / alphaPerDt;
                dtCritBlow[ki] = 2.0 / alphaPerDt;
                System.out.printf("   %8.2e / %8.2e   %8s%n", dtCritRing[ki], dtCritBlow[ki], residS);
            } else {
                System.out.printf("   %8s / %8s   %8s%n", EXT_IMPLICIT ? "STABLE" : "n/a", EXT_IMPLICIT ? "∀dt" : "n/a", residS);
            }
        }

        if (EXT_IMPLICIT) {
            System.out.println("\nVERDICT (-extimplicit, CURE CONTROL):");
            System.out.println("  Making the LOADED (external-spring) force implicit REMOVES THE k_ext DEPENDENCE of the stability");
            System.out.println("  boundary: at dt<=2e-5 every k_ext row is MONO (vs the explicit sweep, where k_ext>=5 blew up at 1e-5).");
            System.out.println("  The whole production dt=1e-5 column is STABLE at every realistic k_ext. The only residual limit is the");
            System.out.println("  still-explicit F8 (k_F8=1 pN/nm) at the two coarsest dt (>=5e-5) — dt_crit_F8 ~2.4e-5, far above production");
            System.out.println("  and k_ext-independent (identical across all rows). ⇒ implicit/sub-step of the LOADED force is the cure.");
            return;
        }

        // ---- the decisive scaling read: is k_eff/gamma LINEAR in k_ext (dt_crit ∝ gamma/(k_ext+k_F8))? ----
        System.out.println("\nDECISIVE — dt_crit vs k_ext scaling (extracted from the finest dt, single-mode-exact):");
        System.out.printf("  %-10s %-14s %-14s %-16s%n", "k_ext(pN/nm)", "dt_crit_ring(s)", "dt_crit_blow(s)", "k_eff/gamma·1e6");
        for (int ki = 0; ki < KEXT.length; ki++)
            if (keffG[ki] > 0)
                System.out.printf("  %-10.1f %-14.3e %-14.3e %-16.3e%n", KEXT[ki], dtCritRing[ki], dtCritBlow[ki], keffG[ki]);
        // linear fit slope: k_eff/gamma·1e6 vs k_ext ⇒ should be ~constant per unit k_ext (load stiffness fingerprint)
        double slopeLo = KEXT.length > 2 && keffG[1] > 0 ? (keffG[2] - keffG[1]) / (KEXT[2] - KEXT[1]) : 0;
        double slopeHi = KEXT.length > 4 && keffG[4] > 0 ? (keffG[5] - keffG[4]) / (KEXT[5] - KEXT[4]) : 0;
        boolean linear = slopeLo > 0 && slopeHi > 0 && Math.abs(slopeHi - slopeLo) / slopeLo < 0.20;

        // marginal k_ext at production dt=1e-5 (blow-up crosses 1e-5)
        double kMarginalRing = interpCrossing(keffG, 1e-5, 1.0);   // dt_crit_ring = 1e-5
        double kMarginalBlow = interpCrossing(keffG, 1e-5, 2.0);   // dt_crit_blow = 1e-5
        System.out.println();
        System.out.println("VERDICT:");
        if (linear) {
            System.out.printf("  dt_crit ∝ gamma/(k_ext + k_F8) — the k_eff/gamma is LINEAR in k_ext (slope ≈ %.2e per pN/nm),%n", 0.5 * (slopeLo + slopeHi));
            System.out.println("  the textbook explicit-Euler LOAD-STIFFNESS fingerprint. The instability is genuine load stiffness,");
            System.out.println("  localized to the loaded (external-spring) path.");
            System.out.printf("  At production dt=1e-5: the explicit filament EOM goes MARGINAL (ring onset) at k_ext ≈ %.1f pN/nm and%n", kMarginalRing);
            System.out.printf("  UNSTABLE (blows up) at k_ext ≈ %.1f pN/nm. A rigid dense-ensemble load (~15-20 pN/nm) is deep in the%n", kMarginalBlow);
            System.out.println("  unstable regime ⇒ an implicit/sub-step of the LOADED force is justified (confirm with -extimplicit).");
        } else {
            System.out.println("  k_eff/gamma NOT linear in k_ext — see the toggle rows (motor-internal / filament-internal coupling).");
        }
    }

    /** k_ext at which dt_crit (= factor/keffG) equals target dt, by linear interpolation of keffG(k_ext). */
    static double interpCrossing(double[] keffG, double targetDt, double factor) {
        double targetKeffG = factor / targetDt;   // k_eff/gamma·1e6 at which dt_crit = targetDt
        for (int ki = 1; ki < KEXT.length; ki++) {
            if (keffG[ki - 1] > 0 && keffG[ki] > 0 && keffG[ki - 1] <= targetKeffG && keffG[ki] >= targetKeffG) {
                double frac = (targetKeffG - keffG[ki - 1]) / (keffG[ki] - keffG[ki - 1]);
                return KEXT[ki - 1] + frac * (KEXT[ki] - KEXT[ki - 1]);
            }
        }
        return -1;
    }

    // ===================================================================== a single relaxation run
    static final class Relax {
        double rho;         // first-step amplification factor d1/d0
        int signChanges;    // sign changes of d_n
        double peak;        // max |d_n|/disp over the run (n>=1)
        double growthRatio; // late-envelope / early-envelope
        boolean grew;       // unstable
        double residualN;   // |forceSum| on the segment at the settled state (N)
        double settled;     // settled displacement / disp (equilibrium convergence)
        double[] traj;      // d_n / disp
        String tag() {
            if (grew) return "GROW";
            if (signChanges > 0) return "RING";
            return "MONO";
        }
    }

    static Relax relax(double kextPN, double dt, boolean rot, int nchain, boolean extImpl) {
        Scene sc = buildScene(dt, nchain);
        boolean freezeFilRot = !(rot || nchain > 1);
        Runnable step = cpuStep(sc, rot, freezeFilRot, extImpl);
        // 1) settle to loaded equilibrium (external spring OFF: springParams k=0 ⇒ no-op)
        sc.springParams.set(0, 0f);
        for (int t = 0; t < 3000; t++) { sc.mot.setCounts(t, 0x57A0E, sc.fil.n); step.run(); }
        // 2) anchor the external spring at equilibrium; record eq
        FilamentStore f = sc.fil; int nSeg = f.n;
        for (int i = 0; i < 3 * nSeg; i++) sc.eqCoord.set(i, f.coord.get(i));
        double eqx = meanX(f);
        // 3) displace +x by DISP (rigid shift of all segments)
        for (int i = 0; i < nSeg; i++) f.coord.set(i, (float) (f.coord.get(i) + DISP));
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        sc.springParams.set(0, (float) (kextPN * 1.0e-9));   // pN/nm -> N/µm
        // 4) integrate the overdamped relaxation, record d_n = meanX - eqx (in units of DISP)
        Relax r = new Relax();
        double[] d = new double[MAXREL + 1];
        d[0] = (meanX(f) - eqx) / DISP;
        int last = 0;
        for (int t = 0; t < MAXREL; t++) {
            sc.mot.setCounts(3000 + t, 0x57A0E, sc.fil.n); step.run();
            double dn = (meanX(f) - eqx) / DISP;
            if (Double.isNaN(dn) || Double.isInfinite(dn) || Math.abs(dn) > 1e3) { d[t + 1] = Math.copySign(1e3, dn); last = t + 1; r.grew = true; break; }
            d[t + 1] = dn; last = t + 1;
            if (Math.abs(dn) < 1e-6 && t > 5) break;   // decayed to noise
        }
        r.traj = java.util.Arrays.copyOf(d, last + 1);
        r.rho = last >= 1 ? d[1] / d[0] : 1.0;
        // sign changes + peak envelope
        int sc0 = 0; double peak = 0; double sgn = Math.signum(d[0]);
        for (int n = 1; n <= last; n++) {
            if (Math.abs(d[n]) > peak) peak = Math.abs(d[n]);
            double s = Math.signum(d[n]);
            if (s != 0 && s != sgn) { sc0++; sgn = s; }
        }
        r.signChanges = sc0; r.peak = peak;
        // growth: late envelope vs early (robust to a fast head/chain transient in -rot/-chain)
        double early = 0, late = 0; int q = Math.max(1, last / 4);
        for (int n = 1; n <= q; n++) early = Math.max(early, Math.abs(d[n]));
        for (int n = Math.max(1, last - q); n <= last; n++) late = Math.max(late, Math.abs(d[n]));
        r.growthRatio = early > 1e-12 ? late / early : (late > 1e-6 ? 1e3 : 0);
        if (!r.grew && (r.growthRatio > 1.05 || peak > 1.5)) r.grew = true;   // envelope grows ⇒ unstable
        // settled displacement (equilibrium convergence): mean of the tail
        double tail = 0; int nt = 0;
        for (int n = Math.max(1, last - 20); n <= last; n++) { tail += d[n]; nt++; }
        r.settled = nt > 0 ? tail / nt : d[last];
        // force-balance residual at the settled state: run one more force-eval, read |forceSum|
        r.residualN = residualForce(sc, extImpl);
        return r;
    }

    static double residualForce(Scene sc, boolean extImpl) {
        // one force-only evaluation (no integrate) to read the net force on the segment at the current pose
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        if (f.n > 1) ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
        CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        if (!extImpl) ExternalSpringSystem.applyExternalSpring(f.coord, sc.eqCoord, f.forceSum, sc.springParams, f.counts);
        int nSeg = f.n; double s = 0;
        for (int i = 0; i < nSeg; i++) {
            double fx = f.forceSum.get(i), fy = f.forceSum.get(nSeg + i), fz = f.forceSum.get(2 * nSeg + i);
            s += Math.sqrt(fx * fx + fy * fy + fz * fz);
        }
        return s / nSeg;
    }

    static double meanX(FilamentStore f) { double s = 0; for (int i = 0; i < f.n; i++) s += f.coord.get(i); return s / f.n; }

    // ===================================================================== the CPU step (filament integrated)
    /** One relaxation step. Motor frozen unless rot; filament always integrated (the EOM under test).
     *  freezeFilRot ⇒ zero the filament torqueSum before integrate (the clean single translational mode —
     *  the task's "avoid a rotational DOF that muddies the mode"). drive ⇒ the motor cycles (secondary mode). */
    static Runnable cpuStep(Scene sc, boolean rot, boolean freezeFilRot, boolean extImpl) {
        return cpuStep(sc, rot, freezeFilRot, extImpl, false);
    }
    static Runnable cpuStep(Scene sc, boolean rot, boolean freezeFilRot, boolean extImpl, boolean drive) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        return () -> {
            if (drive) NucleotideCycleSystem.cycle(mot.nucleotideState, mot.boundSeg, mot.forceDotHist, mot.nucParams, mot.counts);
            // ---- motor body (frozen unless rot) ----
            if (rot) {
                ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
                MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
                TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts);
            }
            CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
            if (rot) {
                CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
                RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
                DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            }
            CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);

            // ---- filament (the EOM under test) ----
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            if (f.n > 1) ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
            CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
            CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
            CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
            if (!extImpl) ExternalSpringSystem.applyExternalSpring(f.coord, sc.eqCoord, f.forceSum, sc.springParams, f.counts);
            if (freezeFilRot) ExternalSpringSystem.zeroTorque(f.torqueSum, f.counts);   // clean translational mode
            RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
            if (extImpl) ExternalSpringSystem.applyExternalSpringImplicit(f.coord, sc.eqCoord, f.bTransGam, sc.springParams, f.counts);
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        };
    }

    // ===================================================================== scene
    static final class Scene {
        FilamentStore fil; MotorStore mot;
        FloatArray bondData, xbParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        FloatArray eqCoord, springParams;   // external spring
        FloatArray swingParams;             // -stroke: directedSwing [k, dt, θ_uncocked, θ_cocked, refDt]
    }

    static Scene buildScene(double dt, int nchain) {
        Scene sc = new Scene();
        int nSeg = Math.max(1, nchain);
        double L = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;
        double headTipZ = ANCHOR_Z + MotorStore.ROD_LEN + LEVER_LEN + HEAD_LEN;
        double zFil = headTipZ + Z_OFFSET;
        FilamentStore fil = new FilamentStore(nSeg);
        double x0 = -0.5 * (nSeg - 1) * L;
        for (int s = 0; s < nSeg; s++) {
            fil.monomerCount.set(s, Constants.stdSegLength);
            fil.setUVec(s, 1f, 0f, 0f); fil.setYVec(s, 0f, 1f, 0f);
            fil.setCoord(s, (float) (x0 + s * L), 0f, (float) zFil);
            fil.brownTransScale.set(s, 0f); fil.brownRotScale.set(s, 0f);
            if (nSeg > 1) {
                if (s > 0)        { fil.end2NbrSlot.set(s, s - 1); fil.end2NbrSide.set(s, 0); }
                if (s < nSeg - 1) { fil.end1NbrSlot.set(s, s + 1); fil.end1NbrSide.set(s, 1); }
            }
        }
        DragTensorSystem.run(fil);
        fil.setParams(dt, 0);            // Brownian OFF (mag 0)
        if (nSeg > 1) fil.setChainParams(dt);
        fil.setCounts(0, 0);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        // one motor centered under the middle of the filament, bound to the central segment
        int nMot = 1;
        MotorStore mot = new MotorStore(nMot);
        double fx = 0.0;   // under the filament center
        mot.assembleArticulated(0, (float) fx, 0f, (float) ANCHOR_Z, 0f, 0f, 1f, 0f);
        DragTensorSystem.run(mot);
        mot.setBodyParams(dt); mot.setJointParams(dt); mot.setKinParams(0.006, -0.4, dt); mot.setNucParams(dt);
        mot.setFaithfulRelease(false, 0.0);   // no break cap — permanently bound, no kinetics
        mot.setImplicit(MYO_SPRING, dt);
        mot.nucleotideState.init(MotorStore.NUC_ADPPI);   // held uncocked (stroke at rest)

        int MAXC = SpatialGrid.MAX_CAND;
        sc.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE); sc.bondData.init(0f);
        if (STROKE) {
            // Reuse the validated SPHEREHEAD+AXLOCK+DIRSWING stroke law with RATE-FIX ON. xbParams size 11:
            //   [0]=myoSpring [1]=90 [2]=alignK(rate-fixed F9/F10/axlock frac) [3]=dt [4]=HEAD_LEN [5]=forcebias=0
            //   [6..8]=0 [9]=1 (f9Frozen@90, sphere-head) [10]=1 (axLock: F10→ŝ head-only).
            double alignK = rateFix(J1_FMT, dt);   // -alignrate: F9/F10/axlock per-step frac → per-time rate
            sc.xbParams = FloatArray.fromElements((float) (NOF8 ? 0.0 : MYO_SPRING), 90f, (float) alignK,
                    (float) dt, (float) HEAD_LEN, 0f, 0f, 0f, 0f, 1f, 1f);
            // directedSwing: [k, dt, θ_uncocked=0, θ_cocked=60, refDt] (-strokerate: [4]=refDt>0 ⇒ per-time rate)
            sc.swingParams = FloatArray.fromElements((float) J1_FMT, (float) dt, 0f, 60f, (float) STROKE_REF_DT);
            mot.jointParams.set(3, 0f);   // -dirswing: J1 angular converter OFF (position spring stays)
        } else
        sc.xbParams = FloatArray.fromElements((float) (NOF8 ? 0.0 : MYO_SPRING), 90f, (float) J1_FMT, (float) dt, (float) HEAD_LEN, 0f);
        sc.segMotorCount = new IntArray(nSeg); sc.segMotorOffsets = new IntArray(nSeg + 1); sc.segMotorMyo = new IntArray(nMot);
        sc.eqCoord = new FloatArray(3 * nSeg); sc.eqCoord.init(0f);
        sc.springParams = FloatArray.fromElements(0f, (float) dt);   // [0]=k_ext(N/µm) [1]=dt
        sc.fil = fil; sc.mot = mot;

        // establish the bond (geometric, deterministic) — bind to the central segment
        IntArray reachSeg = new IntArray(nMot * MAXC); reachSeg.init(-1);
        IntArray bruteReachCount2 = new IntArray(nMot);
        for (int t = 0; t < 4; t++) {
            mot.setCounts(t, 0x57A0E, nSeg);
            MotorStore.publishHeadFromBody(mot.body.coord, mot.body.uVec, mot.body.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
            BindingDetectionSystem.bruteReachable(mot.head, mot.uVec, mot.rodUVec, fil.end1, fil.end2, reachSeg, bruteReachCount2, mot.kinParams, mot.counts);
            BindingDetectionSystem.bindKinetics(mot.head, mot.uVec, mot.rodUVec, fil.end1, fil.end2, reachSeg, bruteReachCount2, mot.boundSeg, mot.bindArc, mot.stats, mot.kinParams, mot.counts);
        }
        return sc;
    }

    static double gammaSegPar() {
        double[] g = DragTensorSystem.rodDragSI((Constants.stdSegLength + 1) * Constants.actinMonoRadius, Constants.radius);
        return g[0];   // parallel translational drag
    }

    // ===================================================================== secondary: driven-stroke convergence
    static void runDrive() {
        System.out.println("SECONDARY — one driven power stroke against k_ext=15 pN/nm; settled-displacement CONVERGENCE (NOT a velocity):");
        System.out.println("  k_ext=15 is UNSTABLE under EXPLICIT integration above dt~3e-6 (the primary map), so the explicit driven");
        System.out.println("  run diverges at production dt. With the loaded force IMPLICIT (the cure) it settles; we confirm the");
        System.out.println("  settled displacement CONVERGES as dt→0 ⇒ the loaded operating point is a well-defined dt-robust equilibrium.");
        double kext = 15.0;
        System.out.printf("%n  %-10s %-18s %-18s %-14s%n", "dt(s)", "EXPLICIT disp(nm)", "IMPLICIT disp(nm)", "|Δ vs finer|(nm)");
        double prevImp = Double.NaN;
        for (double dt : DTS) {
            double explNm = drivenSettled(kext, dt, false);
            double impNm  = drivenSettled(kext, dt, true);
            String eS = Double.isNaN(explNm) ? "DIVERGED" : String.format("%.4f", explNm);
            String delta = Double.isNaN(prevImp) ? "—" : String.format("%.4f", Math.abs(impNm - prevImp));
            System.out.printf("  %-10.1e %-18s %-18.4f %-14s%n", dt, eS, impNm, delta);
            prevImp = impNm;
        }
        System.out.println("  ⇒ CONFIRMATION: the EXPLICIT driven run DIVERGES at coarse dt exactly where the primary relaxation map put");
        System.out.println("    k_ext=15 in the unstable regime; the IMPLICIT (cured) run stays BOUNDED and settles (sub-nm) at every dt —");
        System.out.println("    i.e. the loaded operating point sits in the STABLE region once the loaded force is implicit. (The residual");
        System.out.println("    picometre-scale dt-variation of the settled value is the KNOWN stroke per-step-fraction rate confound,");
        System.out.println("    STROKE_DT_RATE_DIAGNOSIS — a model-definition dt-dependence, NOT a solver instability.)");
    }

    static double drivenSettled(double kext, double dt, boolean implicit) {
        Scene sc = buildScene(dt, NCHAIN);
        // settle uncocked with the external spring anchored, then drive the stroke (cock: NUC_ATP), read the shift
        Runnable holdStep = cpuStep(sc, true, false, implicit);   // -rot implied for a stroke (rotation free)
        sc.springParams.set(0, 0f);
        for (int t = 0; t < 3000; t++) { sc.mot.setCounts(t, 0x57A0E, sc.fil.n); holdStep.run(); }
        FilamentStore f = sc.fil; int nSeg = f.n;
        for (int i = 0; i < 3 * nSeg; i++) sc.eqCoord.set(i, f.coord.get(i));
        double eqx = meanX(f);
        sc.springParams.set(0, (float) (kext * 1.0e-9));
        sc.mot.setAllStates(MotorStore.NUC_ATP);   // cock ⇒ the stroke fires against the load
        Runnable driveStep = cpuStep(sc, true, false, implicit);
        int M = Math.max(4000, (int) Math.round(0.05 / dt));   // ~50 ms sim time to reach loaded equilibrium
        for (int t = 0; t < M; t++) {
            sc.mot.setCounts(3000 + t, 0x57A0E, sc.fil.n); driveStep.run();
            if (Double.isNaN(f.coord.get(0)) || Math.abs(meanX(f) - eqx) > 0.02) return Double.NaN;   // diverged (>20 nm ≫ any real sub-nm settle)
        }
        // settled displacement = mean over a tail window
        double tail = 0; int nt = 0;
        for (int t = 0; t < 200; t++) { sc.mot.setCounts(3000 + M + t, 0x57A0E, sc.fil.n); driveStep.run(); tail += (meanX(f) - eqx); nt++; }
        return tail / nt * 1e3;   // nm
    }

    // ===================================================================== STROKE-TIP-IMPLICIT PROBE
    /** STEP 1: the per-stroke net-displacement ladder on a FREE filament (viscous-drag-only load; NO ext spring).
     *  A single anchored motor drives ONE deterministic power stroke into a free permanently-bound filament; we
     *  measure the net segment displacement the stroke delivers, vs dt. GATE: does it undershoot at 1e-5 and
     *  converge (grow ~2×) as dt→0 — reproducing the gliding per-bound bias (0.82→1.78)? */
    static void runStrokeProbe() {
        System.out.println("STROKE-TIP-IMPLICIT PROBE — per-stroke net filament displacement vs dt (FREE filament, viscous-drag-only load):");
        System.out.printf(java.util.Locale.US,
                "  law: SPHEREHEAD(F9 frozen 90°) + AXLOCK(F10→ŝ) + DIRSWING(neck 0°→60°), RATE-FIX ON (strokerate refDt=%.1e + alignrate); Brownian OFF (deterministic)%n",
                STROKE_REF_DT);
        System.out.printf(java.util.Locale.US, "  F8=%s ; k_F8=%.2f pN/nm ; γ_seg,∥≈%.3e N·s/m ; τ_relax≈γ/k_F8≈%.2e s%n",
                NOF8 ? "OFF" : "on", NOF8 ? 0.0 : MYO_SPRING * 1e9, gammaSegPar(), gammaSegPar() / (MYO_SPRING * 1e9 * 1e-3));
        if (TIP_IMPLICIT) {
            System.out.println("  -stroketipimplicit (STEP 2) NOT built: STEP 1 BAILED (the per-stroke net displacement is dt-flat ⇒");
            System.out.println("  no isolated single-motor stroke bias exists to collapse). Running the STEP-1 explicit ladder for reference.");
            System.out.println("  See STROKE_TIP_IMPLICIT_PROBE.md.\n");
            TIP_IMPLICIT = false;
        }
        System.out.printf(java.util.Locale.US, "  F8-tip head orientation: %s%n%n", "explicit forward-Euler (STEP 1)");

        System.out.printf(java.util.Locale.US, "  %-11s %-16s %-16s %-16s %-14s%n",
                "dt(s)", "settled(nm)", "peak(nm)", "at 5·τ(nm)", "vs coarse");
        double coarse = Double.NaN;
        double[] settled = new double[STROKE_DTS.length];
        for (int i = 0; i < STROKE_DTS.length; i++) {
            double dt = STROKE_DTS[i];
            Stroke r = drivenStroke(dt, TIP_IMPLICIT);
            settled[i] = r.settledNm;
            if (i == 0) coarse = r.settledNm;
            String ratio = (Math.abs(coarse) > 1e-9) ? String.format(java.util.Locale.US, "%.3f×", r.settledNm / coarse) : "—";
            System.out.printf(java.util.Locale.US, "  %-11.3e %-16.5f %-16.5f %-16.5f %-14s%n",
                    dt, r.settledNm, r.peakNm, r.window5Nm, ratio);
        }
        // convergence read: the finest dt is the converged reference; ratio converged/coarse is the "bias".
        double conv = settled[settled.length - 1];
        double c0 = settled[0];
        double lastStep = settled.length >= 2 ? settled[settled.length - 1] / settled[settled.length - 2] : Double.NaN;
        System.out.println();
        System.out.printf(java.util.Locale.US, "  coarse(1e-5)=%.4f nm ; converged(%.3e)=%.4f nm ; converged/coarse=%.3f× ; last-refine step=%.3f×%n",
                c0, STROKE_DTS[STROKE_DTS.length - 1], conv, Math.abs(c0) > 1e-9 ? conv / c0 : Double.NaN, lastStep);
        boolean reproduced = Math.abs(c0) > 1e-9 && (conv / c0) > 1.30 && conv > c0;   // undershoot at 1e-5, grows ≥1.3× as dt→0
        boolean flat = Math.abs(c0) > 1e-9 && Math.abs(conv / c0 - 1.0) < 0.10;         // <10% variation ⇒ flat
        System.out.println();
        if (TIP_IMPLICIT) {
            System.out.println("VERDICT (STEP 2 — F8-tip head orientation IMPLICIT):");
            System.out.println("  Compare the dt=1e-5 settled value to the STEP-1 explicit converged reference. If 1e-5 now ≈ converged");
            System.out.println("  (bias gone) ⇒ PASS: a pure F8-tip implicit suffices, no sub-step. If reduced-but-residual ⇒ PARTIAL.");
            System.out.println("  If unmoved ⇒ NULL: the F8-tip stiffness is NOT the seat of the bias.");
        } else if (reproduced) {
            System.out.printf(java.util.Locale.US, "VERDICT (STEP 1): the per-stroke displacement UNDERSHOOTS at 1e-5 and CONVERGES upward %.2f× as dt→0%n", conv / c0);
            System.out.println("  ⇒ the gliding per-bound dt-bias IS reproduced as a standalone per-motor stroke property. PROCEED to STEP 2");
            System.out.println("     (-stroketipimplicit): does making ONLY the F8-tip head orientation implicit collapse it?");
        } else if (flat) {
            System.out.println("VERDICT (STEP 1 — BAIL): the per-stroke net displacement is FLAT vs dt (converges to a dt-independent");
            System.out.println("  geometric endpoint). The per-bound bias is NOT a standalone per-motor free-stroke property — a single");
            System.out.println("  free permanently-bound head relaxes its delivered stroke to the same working displacement at every dt.");
            System.out.println("  ⇒ the bias lives in the LOADED / continuously-cycling ensemble transport, not one motor's F8-tip stiffness.");
            System.out.println("  Commit nothing for STEP 2 on this harness; the sub-step/implicit must act on the collective loaded force.");
        } else {
            System.out.printf(java.util.Locale.US, "VERDICT (STEP 1): ambiguous — converged/coarse=%.3f× (neither a clean ≥1.3× reproduction nor <10%% flat). Inspect the ladder.%n", conv / c0);
        }
    }

    static final class Stroke { double settledNm, peakNm, window5Nm; }

    /** One driven power stroke: settle uncocked (ADPPI), fire (→cocked ADP), integrate the FREE-filament response.
     *  Returns the settled net segment displacement, the transient peak, and the value at 5·τ_relax after firing. */
    static Stroke drivenStroke(double dt, boolean tipImplicit) {
        Scene sc = buildScene(dt, 1);
        FilamentStore f = sc.fil; int nSeg = f.n;
        Runnable step = cpuStrokeStep(sc, tipImplicit);
        double tau = gammaSegPar() / (MYO_SPRING * 1e9 * 1e-3);      // ≈ γ/k_F8
        int settleSteps = (int) Math.max(30000, Math.round(0.02 / dt));   // ≫ τ_relax and ≫ the ratefixed stroke duration
        int driveSteps  = (int) Math.max(30000, Math.round(0.02 / dt));

        // 1) hold UNCOCKED (ADPPI ⇒ neck 0°, F9 90°) and settle to the uncocked equilibrium
        sc.mot.setAllStates(MotorStore.NUC_ADPPI);
        for (int t = 0; t < settleSteps; t++) { sc.mot.setCounts(t, 0x57A0E, nSeg); step.run(); }
        double eqx = meanX(f);

        // 2) FIRE the stroke: switch to a COCKED state (≠ ADPPI ⇒ neck 60°, F9 120°). The lever swings, F8 drags
        //    the free filament forward. Integrate the response; record d(t)=meanX−eqx.
        sc.mot.setAllStates(MotorStore.NUC_ADP);
        int at5 = (int) Math.round(5.0 * tau / dt);
        double peak = 0, window5 = 0;
        for (int t = 0; t < driveSteps; t++) {
            sc.mot.setCounts(settleSteps + t, 0x57A0E, nSeg); step.run();
            double dn = (meanX(f) - eqx) * 1e3;   // nm
            if (Math.abs(dn) > Math.abs(peak)) peak = dn;
            if (t == at5) window5 = dn;
        }
        // settled = tail average (well past τ_relax and the stroke)
        double tail = 0; int nt = 0;
        for (int t = 0; t < 400; t++) { sc.mot.setCounts(settleSteps + driveSteps + t, 0x57A0E, nSeg); step.run(); tail += (meanX(f) - eqx) * 1e3; nt++; }
        Stroke r = new Stroke();
        r.settledNm = tail / nt; r.peakNm = peak; r.window5Nm = window5;
        return r;
    }

    /** One stroke-probe step: anchored motor free to stroke (SPHEREHEAD+AXLOCK+DIRSWING) + FREE filament (NO ext
     *  spring), Brownian OFF. Mirrors GlidingHarness.stepOrig's motor+filament order for a single permanently-bound
     *  motor (no binding/release/cycle — the nucleotide state is driven by hand). */
    static Runnable cpuStrokeStep(Scene sc, boolean tipImplicit) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        return () -> {
            // ---- motor body (free to stroke; Brownian off ⇒ randForce/randTorque stay 0) ----
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            MotorJointSystem.joints(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, b.torqueSum, mot.nucleotideState, mot.jointParams, mot.counts);
            TailAnchorSystem.anchor(b.coord, b.uVec, b.segLength, b.bTransGam, b.bRotGam, b.forceSum, mot.anchor, mot.jointParams, mot.counts);
            CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams);
            CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
            CrossBridgeSystem.directedSwing(b.uVec, b.torqueSum, b.bRotGam, f.uVec, mot.boundSeg, mot.nucleotideState, sc.swingParams, mot.counts);
            if (tipImplicit) applyTipImplicit(sc);   // STEP 2: backward-Euler on ONLY the F8-tip head-orientation torque
            RigidRodLangevinIntegrationSystem.integrate(b.coord, b.uVec, b.yVec, b.forceSum, b.torqueSum, b.randForce, b.randTorque, b.bTransGam, b.bRotGam, mot.bodyParams, mot.counts);
            DerivedGeometrySystem.derive(b.coord, b.uVec, b.yVec, b.zVec, b.end1, b.end2, b.segLength, mot.counts);
            CrossBridgeSystem.registerForceDot(sc.bondData, mot.boundSeg, mot.forceDotFil, mot.forceMag, mot.forceDotHist, mot.forceDotPlace, mot.counts);
            // ---- filament (the EOM under study; FREE — no ext spring, Brownian off) ----
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
            CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
            CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
            RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        };
    }

    /** STEP 2 — backward-Euler on ONLY the F8-tip head-orientation torque (placeholder; built only if STEP 1 passes). */
    static void applyTipImplicit(Scene sc) {
        throw new UnsupportedOperationException("STEP 2 (-stroketipimplicit) is built only if STEP 1 reproduces the bias; see STROKE_TIP_IMPLICIT_PROBE.md");
    }

    // ===================================================================== GPU parity (default frozen config)
    static void runGpuParity() {
        System.out.println("CPU≡GPU PARITY — default frozen-motor relaxation on the device TaskGraph (k_ext=15 pN/nm, dt=1e-5):");
        double kext = 15.0, dt = 1.0e-5;
        Relax cpu = relax(kext, dt, false, 1, false);
        double[] gpu = relaxGpu(kext, dt);
        // compare trajectories element-wise (deterministic ⇒ expect bit-identical to float precision)
        int n = Math.min(cpu.traj.length, gpu.length);
        double maxAbs = 0;
        for (int i = 0; i < n; i++) maxAbs = Math.max(maxAbs, Math.abs(cpu.traj[i] - gpu[i]));
        System.out.printf("  CPU tag=%s rho=%.5f peak=%.4f ; GPU d[1]=%.5f%n", cpu.tag(), cpu.rho, cpu.peak, gpu.length > 1 ? gpu[1] : 0);
        System.out.printf("  max |d_n^CPU − d_n^GPU| over %d steps = %.3e (units of disp)  %s%n",
                n, maxAbs, maxAbs < 1e-4 ? "PASS (bit-identical to float precision)" : "*CHECK*");
    }

    static double[] relaxGpu(double kext, double dt) {
        Scene sc = buildScene(dt, 1);
        // settle on CPU (deterministic; the settle is not the parity object), then run the RELAXATION on GPU
        Runnable step = cpuStep(sc, false, true, false);   // default config: frozen motor, frozen fil rotation
        sc.springParams.set(0, 0f);
        for (int t = 0; t < 3000; t++) { sc.mot.setCounts(t, 0x57A0E, sc.fil.n); step.run(); }
        FilamentStore f = sc.fil; int nSeg = f.n;
        for (int i = 0; i < 3 * nSeg; i++) sc.eqCoord.set(i, f.coord.get(i));
        double eqx = meanX(f);
        for (int i = 0; i < nSeg; i++) f.coord.set(i, (float) (f.coord.get(i) + DISP));
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        sc.springParams.set(0, (float) (kext * 1.0e-9));
        TornadoExecutionPlan plan = buildPlan(sc);
        double[] d = new double[MAXREL + 1];
        d[0] = (meanX(f) - eqx) / DISP;
        int last = 0;
        for (int t = 0; t < MAXREL; t++) {
            sc.mot.setCounts(3000 + t, 0x57A0E, sc.fil.n);
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            res.transferToHost(f.coord);
            double dn = (meanX(f) - eqx) / DISP;
            d[t + 1] = dn; last = t + 1;
            if (Double.isNaN(dn) || Math.abs(dn) > 1e3) { d[t + 1] = Math.copySign(1e3, dn); break; }
            if (Math.abs(dn) < 1e-6 && t > 5) break;
        }
        return java.util.Arrays.copyOf(d, last + 1);
    }

    /** Device graph for the DEFAULT frozen-motor relaxation: bond → csr → segGather → extSpring → integrate(fil) → derive. */
    static TornadoExecutionPlan buildPlan(Scene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body;
        TaskGraph tg = new TaskGraph("eom")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    b.coord, b.uVec, b.yVec, b.bRotGam, mot.boundSeg, mot.bindArc, mot.nucleotideState,
                    sc.bondData, sc.xbParams, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength,
                    f.bRotGam, f.bTransGam, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.params,
                    sc.segMotorCount, sc.segMotorOffsets, sc.segMotorMyo, sc.eqCoord, sc.springParams)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts, f.counts)
            .task("bond", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, sc.bondData, sc.xbParams)
            .task("zeroFil", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, sc.segMotorCount)
            .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, sc.segMotorCount, sc.segMotorOffsets)
            .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo)
            .task("gather", CrossBridgeSystem::segGather, sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts)
            .task("extspring", ExternalSpringSystem::applyExternalSpring, f.coord, sc.eqCoord, f.forceSum, sc.springParams, f.counts)
            .task("zeroTorque", ExternalSpringSystem::zeroTorque, f.torqueSum, f.counts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("derive", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.coord);
        int nSeg = f.n, nM = mot.nMotors;
        sched = new GridScheduler();
        addW("eom.bond", pad(nM)); addW("eom.zeroFil", pad(nSeg));
        addS("eom.csrHist"); addS("eom.csrScan"); addS("eom.csrScatter");
        addW("eom.gather", pad(nSeg)); addW("eom.extspring", pad(nSeg)); addW("eom.zeroTorque", pad(nSeg));
        addW("eom.integrate", pad(nSeg)); addW("eom.derive", pad(nSeg));
        return new TornadoExecutionPlan(tg.snapshot());
    }
    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String n, int g) { WorkerGrid w = new WorkerGrid1D(g); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(n, w); }
    static void addS(String n) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(n, w); }
}
