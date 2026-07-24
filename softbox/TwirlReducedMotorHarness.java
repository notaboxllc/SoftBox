package softbox;

import static softbox.TwirlReducedMotorSystem.*;
import static softbox.TwirlMechanismAtlas.*;

/**
 * ENTRY POINT for the reduced twirling-mechanism atlas. CPU-only, self-contained, noncanonical.
 *
 * <pre>
 *   ./scripts/run_twirl_mechanism_atlas.sh              # -all : accounting → Stage 1 → 2 → 4 → 3 → 5 → ranking
 *   ./scripts/run_twirl_mechanism_atlas.sh -fixtures    # accounting identity + energy closure + symmetry
 *   ./scripts/run_twirl_mechanism_atlas.sh -stage1      # deterministic single-cycle atlas (A0–A12)
 *   ./scripts/run_twirl_mechanism_atlas.sh -stage2 [N]  # stochastic bound-cycle assay (Brownian on)
 *   ./scripts/run_twirl_mechanism_atlas.sh -epssweep    # A2 oblique-stroke ε sweep (odd/even signature)
 *   ./scripts/run_twirl_mechanism_atlas.sh -stage4      # azimuthal summation
 *   ./scripts/run_twirl_mechanism_atlas.sh -stage3      # moving-site passage (top mechanisms)
 *   ./scripts/run_twirl_mechanism_atlas.sh -stage5      # minimal gliding assay (top mechanisms)
 * </pre>
 */
public final class TwirlReducedMotorHarness {
    static final String NM_S = "N·m·s";

    public static void main(String[] args) {
        Params p = new Params();
        String mode = "-all";
        int nStage2 = 8000;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("-") && !a.equals("-all")) mode = a;
            else if (a.matches("\\d+")) nStage2 = Integer.parseInt(a);
        }
        System.out.println("=== REDUCED TWIRLING-MECHANISM ATLAS (CPU, noncanonical) ===");
        System.out.printf("kT=%.4e J  R_actin=%.2e m  dt=%.1e s  γx=γy=%.1e  γω=%.1e%n",
                KT, p.Ractin, p.dt, p.gx, p.gom);
        System.out.printf("scales: stroke d=%.1f nm  Δy=%.1f nm  Δω=%.2f rad  K_xx=%.1e N/m  K_ωω=%.1e N·m/rad%n%n",
                D_STROKE*1e9, DY*1e9, DW, KXX, KWW);

        switch (mode) {
            case "-fixtures": fixtures(p); break;
            case "-stage1":   stage1(p); break;
            case "-stage2":   stage2(assessAll(p, nStage2)); break;
            case "-epssweep": epsSweep(p); break;
            case "-stage4":   stage4(p); break;
            case "-stage3":   stage3(p); break;
            case "-stage5":   stage5(p); break;
            default:
                fixtures(p); stage1(p); epsSweep(p);
                Assess[] as = assessAll(p, nStage2);
                stage2(as); stage4(p); stage3(p); stage5(p); ranking(as);
        }
    }

    // -------------------------------------------------------------------------------------------
    /** One mechanism's full grade: deterministic + 3 stochastic arms (baseline / actin-mirror / chiral-reversed). */
    static final class Assess {
        String id, name; Det d; Stoch s, sMir, sRev; double sig; boolean resolved, flipsActin, flipsParam; String rank;
    }

    static Assess[] assessAll(Params p, int nEp) {
        Assess[] out = new Assess[IDS.length];
        for (int i = 0; i < IDS.length; i++) {
            String id = IDS[i];
            Mechanism m = build(id);
            Assess a = new Assess(); a.id = id; a.name = m.name;
            a.d    = deterministic(m, p);
            a.s    = stochastic(m, p, nEp, 1000);
            a.sMir = stochastic(m.mirrored(), p, nEp, 1000);
            a.sRev = stochastic(m.chiralReversed(), p, nEp, 1000);
            a.sig = (a.s.sem > 0) ? a.s.mean / a.s.sem : 0;
            a.resolved = Math.abs(a.sig) > 3;
            a.flipsActin = a.resolved && a.sMir.sem > 0 && a.sMir.mean * a.s.mean < 0 && Math.abs(a.sMir.mean/a.sMir.sem) > 3;
            a.flipsParam = a.resolved && a.sRev.sem > 0 && a.sRev.mean * a.s.mean < 0 && Math.abs(a.sRev.mean/a.sRev.sem) > 3;
            a.rank = rankOf(id, a);
            out[i] = a;
        }
        return out;
    }

    // -------------------------------------------------------------------------------------------
    static void fixtures(Params p) {
        System.out.println("---- ACCOUNTING FIXTURES (deterministic, Brownian off) ----");
        System.out.println("Identity: J_θ = −R·γ_y·Δy_bound − γ_ω·Δω_bound   (residual should be ≪1)");
        System.out.printf("%-4s %-34s %13s %13s %10s %12s %10s%n",
                "id", "mechanism", "J_θ("+NM_S+")", "identity", "identRes", "cycleRet(m)", "peakτ");
        for (String id : IDS) {
            Mechanism m = build(id);
            Det d = deterministic(m, p);
            double identity = -p.Ractin*p.gy*d.dyBound - p.gom*d.dwBound;
            System.out.printf("%-4s %-34s %+.3e %+.3e %10.1e %12.1e %.3e%n",
                    id, trunc(m.name,34), d.jTheta, identity, d.identRes, d.cycleReturn, d.peakTau);
        }
        System.out.println("\nEnergy closure: the deterministic cycle returns q→start (cycleRet≈0) ⇒ injected free");
        System.out.println("energy = dissipated over the closed loop. Injected free energy per cycle:");
        for (String id : new String[]{"A0","A1","A4","A5"}) {
            Det d = deterministic(build(id), p);
            System.out.printf("   %-4s injected ΔG = %+.3e J  (=%.1f kT)   cycleReturn=%.1e m%n",
                    id, d.injFreeE, d.injFreeE/KT, d.cycleReturn);
        }
        System.out.println("\n---- SYMMETRY SUITE (representative chiral mechanisms) ----");
        for (String id : new String[]{"A1","A2","A4","A5"}) {
            System.out.println(" ["+id+"] "+build(id).name);
            for (String line : symmetry(id, p)) System.out.println("     "+line);
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------------------------
    static void stage1(Params p) {
        System.out.println("---- STAGE 1: DETERMINISTIC SINGLE-CYCLE ATLAS ----");
        System.out.println("J_θ split: J_pre = binding-relaxation impulse, J_stroke = stroke-relaxation impulse.");
        System.out.printf("%-4s %-34s %12s %12s %12s %12s %10s%n",
                "id", "mechanism", "J_θ", "J_pre", "J_stroke", "J_x(axial)", "class*");
        for (String id : IDS) {
            Mechanism m = build(id);
            Det d = deterministic(m, p);
            String cls = detClass(d);
            System.out.printf("%-4s %-34s %+.3e %+.3e %+.3e %+.3e %10s%n",
                    id, trunc(m.name,34), d.jTheta, d.jPre, d.jStroke, d.jX, cls);
        }
        System.out.println("* deterministic pre-class: R0 no torque at all; R1 transient (peakτ≠0 but J_θ→0);");
        System.out.println("  R4? finite cycle impulse (needs Stage 2 to confirm survival). A9/A10 are pure-kinetic ⇒ null here by design.");
        System.out.println();
    }

    static String detClass(Det d) {
        boolean impulse = Math.abs(d.jTheta) > 1e-27;
        boolean torque  = d.peakTau > 1e-24;
        if (impulse) return "R4?";
        if (torque)  return "R1";
        return "R0";
    }

    // -------------------------------------------------------------------------------------------
    static void epsSweep(Params p) {
        System.out.println("---- A2 OBLIQUE-STROKE ε SWEEP (axial even in ε, angular odd in ε) ----");
        System.out.printf("%8s %14s %14s %10s%n", "ε(deg)", "J_θ("+NM_S+")", "J_x(axial)", "J_θ/J_x");
        double[] degs = {-90,-45,-15,-5,-2,-1,0,1,2,5,15,45,90};
        for (double dg : degs) {
            Mechanism m = build("A2", Math.toRadians(dg));
            Det d = deterministic(m, p);
            System.out.printf("%8.0f %+.5e %+.5e %+10.4f%n", dg, d.jTheta, d.jX, d.jX!=0? d.jTheta/d.jX : 0);
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------------------------
    static void stage2(Assess[] as) {
        System.out.println("---- STAGE 2: STOCHASTIC BOUND-CYCLE ASSAY (Brownian ON) N="+as[0].s.n+"+ episodes/arm ----");
        System.out.printf("%-4s %-30s %13s %11s %8s %8s %12s %12s%n",
                "id", "mechanism", "⟨J_θ⟩", "SEM", "signif", "sign+", "actin-mir", "chiral-rev");
        for (Assess a : as) {
            System.out.printf("%-4s %-30s %+.3e %.2e %7.1fσ %7.2f %12s %12s%n",
                    a.id, trunc(a.name,30), a.s.mean, a.s.sem, a.sig, a.s.signFrac,
                    a.resolved ? String.format("%+.2e%s", a.sMir.mean, a.flipsActin?"↺":"") : "—",
                    a.resolved ? String.format("%+.2e%s", a.sRev.mean, a.flipsParam?"↺":"") : "—");
        }
        System.out.println("actin-mir / chiral-rev = ⟨J_θ⟩ of the actin-frame-mirrored and the chiral-parameter-reversed arms;");
        System.out.println("↺ marks a resolved sign flip. Both flip ⇒ ACTIN-borne chirality; only chiral-rev flips ⇒ MOTOR-borne.");
        System.out.println();
    }

    // -------------------------------------------------------------------------------------------
    static void stage4(Params p) {
        System.out.println("---- STAGE 4: AZIMUTHAL SUMMATION (local adds; lab-frame cancels) ----");
        double[] az = {0, Math.PI/2, Math.PI, 3*Math.PI/2};
        System.out.printf("motors at azimuths 0°,90°,180°,270°%n");
        System.out.printf("%-4s %-30s %14s %10s %14s %10s%n",
                "id", "mechanism", "ΣJ_θ local", "cancel", "ΣJ_θ labctl", "cancel");
        for (String id : new String[]{"A1","A2","A3","A4"}) {
            Mechanism m = build(id);
            double[] loc = azimuthalSum(m, p, az, false);
            double[] lab = azimuthalSum(m, p, az, true);
            System.out.printf("%-4s %-30s %+.3e %9.2f %+.3e %10s%n",
                    id, trunc(m.name,30), loc[0], loc[1], lab[0], Double.isInfinite(lab[1])? "∞(cancel)" : String.format("%.2f", lab[1]));
        }
        System.out.println("The reduced model is formulated ENTIRELY in the local (u,n,t) frame, so a local mechanism is");
        System.out.println("azimuth-independent BY CONSTRUCTION (ΣJ_θ = N·single, cancel=N/... =1). A lab-frame stroke");
        System.out.println("(∝cosφ) cancels around the filament. Azimuthal survival therefore hinges on the FULL-motor");
        System.out.println("implementation preserving the local formulation — the exact locus of the askew shared-base artifact.");
        System.out.println();
    }

    // -------------------------------------------------------------------------------------------
    static void stage3(Params p) {
        System.out.println("---- STAGE 3: MOVING-SITE PASSAGE (Brownian on, finite residence) ----");
        System.out.printf("%-4s %-30s %13s %11s %8s %8s%n", "id","mechanism","⟨J_θ⟩/pass","SEM","signif","sign+");
        for (String id : new String[]{"A1","A2","A3","A4"}) {
            Mechanism m = build(id);
            Stoch s = movingSite(m, p, 1.0e-6, 4000, 500);   // v = 1 µm/s axial site drift
            double sig = s.sem>0? s.mean/s.sem : 0;
            System.out.printf("%-4s %-30s %+.3e %.2e %7.1fσ %7.2f%n", id, trunc(m.name,30), s.mean, s.sem, sig, s.signFrac);
        }
        System.out.println("Site translates at v=1 µm/s; the axial extension changes through the passage and residence is");
        System.out.println("finite. A genuinely LOCAL mechanism keeps its sign because its frame moves with the filament.\n");
    }

    // -------------------------------------------------------------------------------------------
    static void stage5(Params p) {
        System.out.println("---- STAGE 5: MINIMAL GLIDING ASSAY (rigid filament: axial X + roll Θ) ----");
        int nMot = 64, steps = 40000, nSeed = 8;
        System.out.printf("(%d motors, %d steps, mean±spread over %d seeds; filament Brownian OFF)%n", nMot, steps, nSeed);
        System.out.printf("%-4s %-28s %13s %16s %8s %14s%n", "id","mechanism","v_glide(m/s)","roll_rate(rad/s)","engage","pitch(rad/µm)");
        String[] ids = {"A0","A1","A2","A4"};
        for (String id : ids) {
            double[] rr = glideSeeds(build(id), p, nMot, steps, false, nSeed);
            double pitch = (Math.abs(rr[0])>1e-30)? rr[2]/rr[0] : 0;
            System.out.printf("%-4s %-28s %+.3e     %+.3e±%.2e %7.3f %+13.3f%n",
                    id, trunc(build(id).name,28), rr[0], rr[2], rr[3], rr[4], pitch*1e-6);
        }
        System.out.println("A0 (achiral) glides with roll_rate consistent with thermal zero; a chiral mechanism glides AND rolls.");
        System.out.println("Robustness with filament Brownian ON (roll noise added; signal survives for the strong arms):");
        for (String id : new String[]{"A1","A4"}) {
            double[] rr = glideSeeds(build(id), p, nMot, steps, true, nSeed);
            System.out.printf("   %-4s v_glide=%+.3e roll_rate=%+.3e±%.2e%n", id, rr[0], rr[2], rr[3]);
        }
        System.out.println();
    }

    /** Mean over seeds → [meanV, semV, meanRoll, semRoll, meanEngage]. */
    static double[] glideSeeds(Mechanism m, Params p, int nMot, int steps, boolean filBrown, int nSeed) {
        double sv=0, sv2=0, sr=0, sr2=0, se=0;
        for (int k=0;k<nSeed;k++){
            Glide g = gliding(m, p, nMot, steps, filBrown, 700 + k*131L);
            sv+=g.vGlide; sv2+=g.vGlide*g.vGlide; sr+=g.rollRate; sr2+=g.rollRate*g.rollRate; se+=g.engage;
        }
        double mv=sv/nSeed, mr=sr/nSeed;
        double semv=Math.sqrt(Math.max(0,sv2/nSeed-mv*mv)/nSeed), semr=Math.sqrt(Math.max(0,sr2/nSeed-mr*mr)/nSeed);
        return new double[]{mv, semv, mr, semr, se/nSeed};
    }

    // -------------------------------------------------------------------------------------------
    static void ranking(Assess[] as) {
        System.out.println("======================= R0–R6 RANKING =======================");
        System.out.printf("%-4s %-34s %6s %11s %8s %14s %8s%n",
                "id","mechanism","det","⟨J_θ⟩","signif","chirality","RANK");
        for (Assess a : as) {
            String chir = !a.resolved ? "—" : (a.flipsActin ? "actin-borne" : (a.flipsParam ? "motor-borne" : "frame-fixed"));
            System.out.printf("%-4s %-34s %6s %+.3e %6.1fσ %14s %8s%n",
                    a.id, trunc(a.name,34), detClass(a.d), a.s.mean, a.sig, chir, a.rank);
        }
        System.out.println("R0 nothing · R1 transient/frozen only · R2 det-ok/stochastic-fragile or kinetic-unresolved ·");
        System.out.println("R3 resolved but sign not chirality-linked · R4 robust chiral (actin- or motor-borne) success.");
        System.out.println("=============================================================");
    }

    static String rankOf(String id, Assess a) {
        boolean detImpulse = Math.abs(a.d.jTheta) > 1e-27;
        boolean detTorque  = a.d.peakTau > 1e-24;
        boolean pureKinetic = id.equals("A9") || id.equals("A10");
        if (a.resolved && (a.flipsActin || a.flipsParam)) return "R4";      // robust chiral (actin- or motor-borne)
        if (a.resolved && !a.flipsActin && !a.flipsParam) return "R3";      // resolved but sign frame-fixed (artifact)
        if (!detImpulse && !detTorque && !pureKinetic) return "R0";        // nothing at all (A0, A8)
        if (!detImpulse && detTorque) return "R1";                          // transient only (A5,A6,A7)
        if (detImpulse && !a.resolved) return "R2";                         // det success, stochastic fragile
        if (pureKinetic) return "R2";                                       // kinetic, unresolved at this N
        return "R1";
    }

    static String trunc(String s, int n) { return s.length() <= n ? s : s.substring(0, n-1) + "…"; }
}
