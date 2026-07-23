package softbox;

import static softbox.TwoBodyConverterMotor.add;
import static softbox.TwoBodyConverterMotor.sub;
import static softbox.TwoBodyConverterMotor.scl;
import static softbox.TwoBodyConverterMotor.neg;
import static softbox.TwoBodyConverterMotor.dot;
import static softbox.TwoBodyConverterMotor.crs;
import static softbox.TwoBodyConverterMotor.nrm;
import static softbox.TwoBodyConverterMotor.geomC;
import static softbox.TwoBodyConverterMotor.solveLin;
import static softbox.TwoBodyConverterMotor.brownTorque;
import static softbox.TwoBodyConverterMotor.buildS2M;

import softbox.TwoBodyConverterMotor.Cmot;

/**
 * ================== EXPLICIT HMM-LIKE MYOSIN DIMER (explicit-hmm-dimer-s2-l40) ==================
 * A two-headed HMM-like myosin dimer built from the flagship explicit S2 motor (EXPLICIT_S2_L40).
 * NEW, DEFAULT-OFF architecture: the validated single-head explicit model
 * ({@link TwoBodyConverterMotor}) is preserved BYTE-IDENTICAL — this is a new file that only READS
 * its package-private element math (vector helpers, geomC, solveLin, brownTorque, the frozen 4G
 * S2 constants) and its {@link Cmot} converter fixture. Nothing in TwoBodyConverterMotor is edited.
 *
 * TOPOLOGY (a forked "Y" beam — see docs/matsoa/EXPLICIT_HMM_DIMER_DESIGN.md):
 *
 *    head A (converter φ_A,ψ_A; F8 spring → actin site A)
 *        \  proximal branch A (Ma segments)
 *         fork F ── shared paired-S2 beam (Ms segments) ── clamped emergence E (node 0, anchor)
 *        /  proximal branch B (Mb segments)
 *    head B (converter φ_B,ψ_B; F8 spring → actin site B)
 *
 * The shared region reuses the 4G S2 material (EA/EI) AS-IS — the audit (design §2) established that
 * EA=4.2e-9 N, EI=7.2e-28 N·m² already represent the PAIRED coiled coil as one effective element
 * (Adamovic–Mijailović–Karplus 2008 is the S2-subdomain = two-chain unit), so it is NOT doubled.
 * Branch material starts equal to the shared material (task instruction) via a separate field, so a
 * later "make the branches softer" retune is a one-line change.
 *
 * All head↔head coupling flows through the SHARED beam node DOF — no inter-rod springs, no atomics.
 * This mirrors the physical content of an HMM: a load on one head transmits down its branch, through
 * the shared S2 to the anchor, and up the other branch to the partner head.
 *
 * SOLVER: one overdamped linearly-implicit Newton step per timestep over the generalized coordinates
 * q = {nd[1..NF], φ_A, ψ_A, φ_B, ψ_B} (n = 3·NF + 4). Stretch analytic + F8/converter Gauss–Newton
 * blocks (implicit); bending + floor explicit (RHS, stabilised by node drag). The beam TANGENT is the
 * FD oracle (central-difference of the general node-force residual over the free-node DOF) — the same
 * permanent-oracle path the single head uses, generalised from a chain to a graph.
 * =================================================================================================
 */
public final class ExplicitHmmDimer {

    // ------- frozen material (inherited from EXPLICIT_S2_L40 — NOT retuned) -------
    static final double L0_NM   = TwoBodyConverterMotor.EXP4G_L0_NM;      // 10 nm nominal segment length
    // head→anchor contour (E→pivot) = shared + branch. CANONICAL = 40 nm (explicit-hmm-dimer-l40, shared 30 ⇒ Ms=3).
    // Settable ONLY for the declared L60 CPU structural sensitivity (60 ⇒ shared 50 ⇒ Ms=5, NF=7, NDOF=25); default 40
    // keeps every L40 build byte-identical. The GPU forked-dimer kernel is specialized to Ms=3 and correctly aborts
    // (assertStandingConfig) if this drives a non-(3,1,1) topology — L60 dimer is CPU-only.
    static double TOTAL_NM = 40.0;
    static final double EA_SI   = TwoBodyConverterMotor.EXP4G_EA_SI;      // 4.2e-9 N  (paired coiled coil, as-is)
    static final double EI_SI   = TwoBodyConverterMotor.EXP4G_EI_SI;      // 7.2e-28 N·m²
    static final double RNODE_NM = TwoBodyConverterMotor.EXP4G_RNODE_NM;  // 5 nm node drag radius
    // distinct dimer Brownian salts (≠ single-head 0x4711/41/42, ≠ mat 0x4811/41/42)
    static final long SALT_NODE = 0x484D0000L;   // 'HM'
    static final long SALT_ANG  = 0x484E0000L;

    /** The forked-beam dimer state (all world-µm nodes + two converter heads). */
    static final class Dimer {
        int Ms, Ma, Mb, NF;                 // shared / branch-A / branch-B segment counts; NF = free node count
        double[][] nd;                      // nodes 0..NF (µm); nd[0]=E clamped, nd[Ms]=fork, nd[Ms+Ma]=P_A, nd[Ms+Ma+Mb]=P_B
        int[][] seg;                        // stretch segments {lo,hi} (tree; NF segments)
        int[][] hinge;                      // interior/fork/branch bending hinges {iA,iMid,iC} (rest 0); clamped emergence handled separately
        double ks, kb;                      // shared S2 per-element NOMINAL (SI N/m, N·m) — display/back-compat
        double ksBr, kbBr;                  // branch per-element NOMINAL (default = shared) — display/back-compat
        double l0;                          // NOMINAL shared segment rest length (µm) — display/back-compat
        // ---- variable-branch-length generalization (per-element rest lengths + stiffness) ----
        // The proximal branch length may differ from the shared-S2 segment length. Every stretch/bending
        // element carries its OWN rest length + stiffness (ks=EA_eff/l0, kb=EI_eff/lrep), so a shorter branch
        // is a clean per-edge rest-length change, NOT a coordinate distortion at a wrong 10 nm mechanical length.
        double[] segL0;                     // per-segment rest length (µm), parallel to seg[]
        double[] segKs;                     // per-segment stretch stiffness (SI N/m) = EA_eff/segL0
        double[] hingeKb;                   // per-hinge bending stiffness (SI N·m) = EI_eff/lrep (lrep = mean adjacent seg rest len)
        double kbEmg;                       // clamped-emergence bending stiffness (SI N·m) = EI/(first shared seg rest len)
        double branchLenNm = 10, sharedLenNm = 30;   // proximal branch contour / shared-S2 contour (nm), each head E→P = 40 nm
        double gammaNode, kfloor, floorZ;   // node drag (SI), floor
        double[] g4Tan, eup;                // clamped emergence tangent; global up axis
        double[] E;                         // clamped emergence point (world µm) = nd[0]
        double dt;
        int pA, pB;                         // pivot node indices (= Ms+Ma, Ms+Ma+Mb)
        Cmot hA, hB;                        // the two converter heads (g4On=false; A = pivot node)
        double[] actinA, actinB;            // fixed actin sites (= settled pre-stroke xF8), world µm
        double coupleA = 1, coupleB = 1;    // cross-bridge engagement (→0 on detach)
        // per-element ks/kb selector: shared segments use ks/kb, branch segments use ksBr/kbBr
        boolean[] segIsBranch;              // parallel to seg[]
        boolean[] hingeIsBranch;            // parallel to hinge[] (a fork/branch hinge uses branch kb)
        // ---- proximal-fork relaxation (this study) ----
        double alpha;                       // fork rest HALF-angle (rad); 0 = collinear baseline
        double branchEImult = 1, branchEAmult = 1;   // proximal-branch compliance (shared S2 is NOT touched)
        double forkKmult = 1;               // fork-root angular-coupling compliance: scales ONLY the two fork hinges'
                                            // bending stiffness (hingeIsFork), MULTIPLICATIVELY on top of branchEImult.
                                            // 1.0 ⇒ byte-identical (fork hinge kb = EI·branchEImult/lrep as before).
        boolean[] hingeIsFork;              // parallel to hinge[]: the two fork hinges get a DIRECTIONAL rest angle
        double[] hingeRest;                 // parallel to hinge[]: signed rest angle in the splay plane (+α branch A / −α branch B; 0 else)
        int[] hingeBranchSide;              // parallel to hinge[]: 0 = fork-A hinge, 1 = fork-B hinge, −1 = not a fork hinge
        // ---- directional-mechanism survey (state-dependent, polarity-aware; default OFF) ----
        int dirMech = 0;             // 0 NONE | 1 M1a free-branch cant | 2 M1b balanced fork | 3 M2 stroke-driven fork | 4 M3 free-converter cant | 5 M4 free-branch fwd | 6 M5 shared bend | 7 M6 combined
        double dirAmp = 0;           // primary cant amplitude (rad) — free-branch or (M3) free-converter
        double dirAmp2 = 0;          // secondary amplitude (rad) — M6 converter cant added on top of branch cant
        double dirComp = 0;          // M1b/M2 bound-branch compensation ratio (× dirAmp, rearward)
        double dirConvStiffMult = 1; // M3/M6 converter-cant stiffness × kconv
        double[] bHat = { 1, 0, 0 }; // barbed direction (filament polarity) — defines "forward"; flip for polarity reversal
        int boundHead = -1;          // which head is strongly bound (-1 none / 0 A / 1 B) — set by the caller each step
        double dirAct = 0;           // smooth activation 0..1 — set by the caller from the trigger + bound state
    }

    /**
     * Build a symmetric HMM dimer in the canonical frame (b̂=+x, eup=+z, econv=+y): shared beam along
     * +x from emergence E to fork F, two branches splayed ±splayDeg in the x–z plane to the two pivots.
     * Each head is an EXPLICIT converter motor (buildS2M with g4On=false) with A pinned to its pivot
     * node; the fixed actin site is DEFINED as the settled pre-stroke xF8 (rest = current xF8, exactly
     * as the single-head device slice), so no inverse geometry is needed.
     */
    static Dimer build(int Ms, int Ma, int Mb, double splayDeg, double dt) { return build(Ms, Ma, Mb, splayDeg, dt, 0.0, 1.0, 1.0); }
    static Dimer build(int Ms, int Ma, int Mb, double splayDeg, double dt, double alphaDeg, double branchEImult, double branchEAmult) {
        return build(Ms, Ma, Mb, splayDeg, dt, alphaDeg, branchEImult, branchEAmult, Ma * L0_NM);
    }
    static Dimer build(int Ms, int Ma, int Mb, double splayDeg, double dt, double alphaDeg, double branchEImult, double branchEAmult, double branchLenNm) {
        return build(Ms, Ma, Mb, splayDeg, dt, alphaDeg, branchEImult, branchEAmult, branchLenNm, 1.0);
    }
    /**
     * Fork-relaxation + variable-branch-length build. {@code alphaDeg} = the fork rest HALF-angle (branch A
     * prefers the shared-S2 tangent rotated +α, branch B −α, as an energy term — NOT a positional constraint);
     * {@code branchEImult}/{@code branchEAmult} scale ONLY the proximal-branch bending/stretch stiffness (the two
     * fork hinges + the branch segments). {@code branchLenNm} = the proximal-branch contour (nm): shared-S2 length
     * = 40 − branchLenNm, so each head's emergence→pivot path stays 40 nm. The distal shared S2 material (EA/EI)
     * is NEVER touched. alphaDeg=0, mults=1, branchLenNm=Ma·10 ⇒ the collinear zero-rest-angle 10 nm baseline EXACTLY.
     *
     * PER-ELEMENT rest lengths (the clean generalization): shared segments each (40−branchLenNm)/Ms nm, branch
     * segments each branchLenNm/Ma nm; every stretch element gets ks=EA_eff/l0_seg, every bending hinge gets
     * kb=EI_eff/lrep with lrep = mean of the two adjacent segment rest lengths. At branchLenNm=10 all rest lengths
     * are 10 nm and this reduces bit-for-bit to the uniform-l0 model. Node count / indexing / solver dimension are
     * UNCHANGED (only rest spacings + per-element stiffness change); node drag (fixed rNode=5 nm) is unchanged.
     */
    static Dimer build(int Ms, int Ma, int Mb, double splayDeg, double dt, double alphaDeg, double branchEImult, double branchEAmult, double branchLenNm, double forkKmult) {
        Dimer d = new Dimer();
        d.Ms = Ms; d.Ma = Ma; d.Mb = Mb; d.NF = Ms + Ma + Mb; d.dt = dt;
        d.branchLenNm = branchLenNm; d.sharedLenNm = TOTAL_NM - branchLenNm;
        double sharedSegNm = d.sharedLenNm / Ms, branchSegNm = branchLenNm / Ma;    // per-segment rest lengths (nm)
        double lS = sharedSegNm * 1e-3, lB = branchSegNm * 1e-3;                     // µm
        d.l0 = lS;                                            // NOMINAL (display/back-compat) = shared segment rest length
        d.ks = EA_SI / (lS * 1e-6); d.kb = EI_SI / (lS * 1e-6);        // SI (shared S2 — FROZEN material EA/EI)
        d.alpha = Math.toRadians(alphaDeg); d.branchEImult = branchEImult; d.branchEAmult = branchEAmult; d.forkKmult = forkKmult;
        d.ksBr = EA_SI * branchEAmult / (lB * 1e-6); d.kbBr = EI_SI * branchEImult / (lB * 1e-6);   // NOMINAL branch per-element
        d.gammaNode = 6 * Math.PI * Constants.aeta * (RNODE_NM * 1e-9);
        d.eup = new double[]{ 0, 0, 1 };
        d.g4Tan = new double[]{ 1, 0, 0 };                   // clamped emergence tangent = +b̂ (toward the fork)
        d.pA = Ms + Ma; d.pB = Ms + Ma + Mb;

        // --- node placement (per-segment rest spacings) ---
        double Ls = Ms * lS, Lb = Ma * lB;                   // shared / branch contour (µm) (Ma==Mb assumed for symmetry)
        double[] F = new double[]{ 0, 0, 0 };                // fork at origin
        d.E = new double[]{ -Ls, 0, 0 };                     // emergence behind the fork along −x
        double sp = Math.toRadians(alphaDeg > 1e-9 ? alphaDeg : splayDeg);   // start at the fork rest angle when α>0
        double[] dirA = new double[]{ Math.cos(sp), 0,  Math.sin(sp) };
        double[] dirB = new double[]{ Math.cos(sp), 0, -Math.sin(sp) };
        d.nd = new double[d.NF + 1][3];
        // shared: nd[0..Ms] evenly E→F
        for (int j = 0; j <= Ms; j++) { double f = (double) j / Ms; d.nd[j] = add(d.E, scl(sub(F, d.E), f)); }
        // branch A: nd[Ms+1 .. Ms+Ma] from F along dirA
        double lbSeg = Lb / Ma;
        for (int i = 1; i <= Ma; i++) d.nd[Ms + i] = add(F, scl(dirA, lbSeg * i));
        // branch B: nd[Ms+Ma+1 .. Ms+Ma+Mb] from F along dirB
        for (int i = 1; i <= Mb; i++) d.nd[Ms + Ma + i] = add(F, scl(dirB, (Lb / Mb) * i));

        // --- segments (tree; each free node has exactly one parent segment) ---
        int nSeg = d.NF; d.seg = new int[nSeg][2]; d.segIsBranch = new boolean[nSeg]; int s = 0;
        for (int j = 0; j < Ms; j++) { d.seg[s] = new int[]{ j, j + 1 }; d.segIsBranch[s] = false; s++; }        // shared
        d.seg[s] = new int[]{ Ms, Ms + 1 }; d.segIsBranch[s] = true; s++;                                        // fork→A first
        for (int i = 1; i < Ma; i++) { d.seg[s] = new int[]{ Ms + i, Ms + i + 1 }; d.segIsBranch[s] = true; s++; }
        d.seg[s] = new int[]{ Ms, Ms + Ma + 1 }; d.segIsBranch[s] = true; s++;                                   // fork→B first
        for (int i = 1; i < Mb; i++) { d.seg[s] = new int[]{ Ms + Ma + i, Ms + Ma + i + 1 }; d.segIsBranch[s] = true; s++; }

        // --- hinges. Shared-interior + branch-interior + clamped emergence have rest 0; the TWO FORK hinges
        //     get a DIRECTIONAL rest half-angle (+α branch A / −α branch B) in the splay plane. ---
        java.util.List<int[]> hs = new java.util.ArrayList<>(); java.util.List<Boolean> hb = new java.util.ArrayList<>();
        java.util.List<Boolean> hf = new java.util.ArrayList<>(); java.util.List<Double> hr = new java.util.ArrayList<>(); java.util.List<Integer> hbs = new java.util.ArrayList<>();
        for (int m = 1; m < Ms; m++) { hs.add(new int[]{ m - 1, m, m + 1 }); hb.add(false); hf.add(false); hr.add(0.0); hbs.add(-1); }   // shared interior
        hs.add(new int[]{ Ms - 1, Ms, Ms + 1 }); hb.add(true); hf.add(true); hr.add(+d.alpha); hbs.add(0);                               // fork A (+α)
        hs.add(new int[]{ Ms - 1, Ms, Ms + Ma + 1 }); hb.add(true); hf.add(true); hr.add(-d.alpha); hbs.add(1);                          // fork B (−α)
        for (int i = 1; i < Ma; i++) { hs.add(new int[]{ Ms + i - 1, Ms + i, Ms + i + 1 }); hb.add(true); hf.add(false); hr.add(0.0); hbs.add(-1); }   // branch A interior
        for (int i = 1; i < Mb; i++) { hs.add(new int[]{ Ms + Ma + i - 1, Ms + Ma + i, Ms + Ma + i + 1 }); hb.add(true); hf.add(false); hr.add(0.0); hbs.add(-1); } // branch B interior
        d.hinge = hs.toArray(new int[0][]); d.hingeIsBranch = new boolean[hb.size()]; d.hingeIsFork = new boolean[hb.size()]; d.hingeRest = new double[hb.size()]; d.hingeBranchSide = new int[hb.size()];
        for (int i = 0; i < hb.size(); i++) { d.hingeIsBranch[i] = hb.get(i); d.hingeIsFork[i] = hf.get(i); d.hingeRest[i] = hr.get(i); d.hingeBranchSide[i] = hbs.get(i); }

        // --- per-element rest lengths + stiffness (the variable-branch-length generalization) ---
        // Each stretch segment carries its own rest length + ks=EA_eff/l0; each bending hinge carries
        // kb=EI_eff/lrep (lrep = mean of the two adjacent segment rest lengths). At branch=10 nm all rest
        // lengths are equal ⇒ these arrays hold the uniform-l0 values ⇒ bit-for-bit the old model.
        d.segL0 = new double[d.seg.length]; d.segKs = new double[d.seg.length];
        for (int si = 0; si < d.seg.length; si++) {
            d.segL0[si] = d.segIsBranch[si] ? lB : lS;
            double eaEff = d.segIsBranch[si] ? EA_SI * branchEAmult : EA_SI;
            d.segKs[si] = eaEff / (d.segL0[si] * 1e-6);
        }
        d.hingeKb = new double[d.hinge.length];
        for (int hi = 0; hi < d.hinge.length; hi++) {
            double lrep = 0.5 * (segLenOf(d, d.hinge[hi][0], d.hinge[hi][1]) + segLenOf(d, d.hinge[hi][1], d.hinge[hi][2]));  // µm
            double eiEff = d.hingeIsBranch[hi] ? EI_SI * branchEImult : EI_SI;
            if (d.hingeIsFork[hi]) eiEff *= forkKmult;   // fork-root angular coupling: scales ONLY the two fork hinges (default 1 ⇒ byte-identical)
            d.hingeKb[hi] = eiEff / (lrep * 1e-6);
        }
        d.kbEmg = EI_SI / (lS * 1e-6);                        // clamped emergence bends the first shared segment (rest len lS)

        d.floorZ = dot(d.E, d.eup) - 0.05; d.kfloor = 20.0 * 1e-3;   // substrate floor 50 nm below emergence (SI N/m)

        // --- the two converter heads (explicit motor, g4On=false ⇒ plain converter about a pivot) ---
        d.hA = buildS2M(40.0, 4, 0.0, false, dt, 1.0, 1.0);
        d.hB = buildS2M(40.0, 4, 0.0, false, dt, 1.0, 1.0);
        pinHead(d.hA, d.nd[d.pA]); pinHead(d.hB, d.nd[d.pB]);
        d.actinA = d.hA.xF8.clone();                          // rest spring anchor = settled pre-stroke xF8
        d.actinB = d.hB.xF8.clone();
        return d;
    }

    /** Pin a head's converter to a pivot node and refresh its derived geometry (C, xF8, xH). */
    static void pinHead(Cmot h, double[] pivot) { h.A = pivot.clone(); h.P = pivot.clone(); geomC(h); }

    // ---- the stroke target (converter relaxes at binding to θ_s=pre; the Pi-release drives it to ADP) ----
    static final double THETAS_ADP  = TwoBodyConverterMotor.ADP_THETAS;   // 30° post-stroke (ADP) converter target

    // ================= energy / node forces (general graph) =================

    /** Bending energy (SI J): clamped emergence (g4Tan vs bond 0→1) + all interior/fork/branch hinges (rest 0). */
    static double bendEnergy(Dimer d, double[][] nd) {
        double E = 0;
        // clamped emergence
        double[] b0 = sub(nd[1], nd[0]); double l0 = Math.sqrt(dot(b0, b0));
        // M5 (shared-S2 bend): rotate the clamped-emergence preferred tangent toward the barbed direction when bound
        double[] g4 = d.g4Tan;
        if (d.dirMech == 6 && d.dirAct > 0 && d.boundHead >= 0) g4 = rotToward(d.g4Tan, d.bHat, d.dirAmp * d.dirAct);
        if (l0 > 1e-12) { double c = clamp(dot(g4, b0) / l0); double th = Math.acos(c); E += 0.5 * d.kbEmg * th * th; }
        for (int hIdx = 0; hIdx < d.hinge.length; hIdx++) {
            int[] h = d.hinge[hIdx]; double kb = d.hingeKb[hIdx];
            double[] a = sub(nd[h[1]], nd[h[0]]), b = sub(nd[h[2]], nd[h[1]]);
            double la = Math.sqrt(dot(a, a)), lb = Math.sqrt(dot(b, b)); if (la < 1e-12 || lb < 1e-12) continue;
            if (d.hingeIsFork[hIdx]) {
                // DIRECTIONAL fork rest angle: penalise the FULL 3D angle between the branch (b) and a PREFERRED
                // direction = the shared tangent (â) rotated by the rest half-angle (+α branch A / −α branch B) in
                // the splay plane (spanned by â and the eup component ⟂ â). This gives full 3D restoring stiffness
                // (in- AND out-of-plane); at rest=0 the preferred direction is â ⇒ EXACTLY the old unsigned term.
                double[] ah = scl(a, 1.0 / la);
                double eDota = dot(d.eup, ah); double[] sHat = sub(d.eup, scl(ah, eDota)); double ls = Math.sqrt(dot(sHat, sHat));
                double rest = d.hingeRest[hIdx];
                double[] dPref = ls < 1e-9 ? ah : add(scl(ah, Math.cos(rest)), scl(scl(sHat, 1.0 / ls), Math.sin(rest)));
                // directional survey: cant the fork branch's preferred direction toward/away from the barbed end
                double cant = forkCant(d, d.hingeBranchSide[hIdx]);
                if (cant != 0) dPref = rotToward(dPref, d.bHat, cant);
                // AXIAL LEAD-LAG (mech 8): the rest-angle torque uses a tunable stiffness (dirConvStiffMult × branch kb)
                if (d.dirMech == 8 && d.dirAct > 0 && d.hingeIsFork[hIdx]) kb *= d.dirConvStiffMult;
                double th = Math.acos(clamp(dot(b, dPref) / lb)); E += 0.5 * kb * th * th;
            } else if (d.dirMech == 9 && d.dirAct > 0 && d.boundHead >= 0 && h[2] == d.Ms) {
                // AXIAL DISTAL-S2 HINGE (mech 9): the shared hinge immediately below the fork (iC = fork node) gets a
                // state-dependent preferred bend that rotates the last shared segment toward the barbed end, intended
                // to advance the fork junction toward +bHat. Tunable stiffness (dirConvStiffMult × kb).
                double[] ah = scl(a, 1.0 / la);
                double[] dPref = rotToward(ah, d.bHat, d.dirAmp * d.dirAct);   // toward barbed; null if a ∥ bHat
                double th = Math.acos(clamp(dot(b, dPref) / lb)); E += 0.5 * (kb * d.dirConvStiffMult) * th * th;
            } else {
                double c = clamp(dot(a, b) / (la * lb)); double th = Math.acos(c); E += 0.5 * kb * th * th;
            }
        }
        return E;
    }

    /** Internal beam forces (SI N, world) on nodes 0..NF: stretch (analytic) + bending (FD of energy) + floor.
     *  Node 0's force is the emergence reaction (reported, not applied). */
    static double[][] nodeForces(Dimer d, double[][] nd) {
        double[][] F = new double[d.NF + 1][3];
        for (int si = 0; si < d.seg.length; si++) {
            int lo = d.seg[si][0], hi = d.seg[si][1]; double ks = d.segKs[si]; double l0m = d.segL0[si] * 1e-6;
            double[] b = sub(nd[hi], nd[lo]); double len = Math.sqrt(dot(b, b)); if (len < 1e-15) continue;
            double f = ks * (len * 1e-6 - l0m); double[] u = scl(b, 1.0 / len);
            for (int k = 0; k < 3; k++) { F[lo][k] += f * u[k]; F[hi][k] -= f * u[k]; }
        }
        double h = 1e-5;   // µm central-difference of the bending energy per coordinate (the FD-bend residual, as single-head)
        for (int j = 0; j <= d.NF; j++) for (int k = 0; k < 3; k++) {
            double sav = nd[j][k];
            nd[j][k] = sav + h; double Ep = bendEnergy(d, nd); nd[j][k] = sav - h; double Em = bendEnergy(d, nd); nd[j][k] = sav;
            F[j][k] += -((Ep - Em) / (2 * h)) * 1e6;
        }
        for (int j = 0; j <= d.NF; j++) { double z = dot(nd[j], d.eup); if (z < d.floorZ) { double pen = (d.floorZ - z) * 1e-6; double fk = d.kfloor * pen;
            for (int k = 0; k < 3; k++) F[j][k] += fk * d.eup[k]; } }
        return F;
    }

    // ================= the coupled (3·NF + 4) linearly-implicit step =================

    /** One overdamped Newton step. F8h{A,B} are the per-head cross-bridge forces (world N), computed by the
     *  caller from the fixed-actin spring. brownian adds FDT node + angle noise. Returns the solveLin status
     *  (0 = ok, 1 = singular). */
    static int solve(Dimer d, int t, int seed, boolean brownian, double[] F8hA, double[] F8hB) {
        return solve(d, t, seed, brownian, F8hA, F8hB, false);
    }
    /** decoupleFork (a LABELED mechanical control, §5 Condition 4): PIN the fork node (add a rigid diagonal + zero
     *  its RHS) so a load on one branch reacts against the fixed fork instead of propagating through it to the
     *  partner branch — it severs inter-head shared-tail transmission while each head keeps its own branch + F8
     *  mechanics against the fixed fork. decoupleFork=false ⇒ byte-identical to the coupled solve. */
    static int solve(Dimer d, int t, int seed, boolean brownian, double[] F8hA, double[] F8hB, boolean decoupleFork) {
        int NF = d.NF, nF = 3 * NF, n = nF + 4; double[][] nd = d.nd;
        double[][] Msys = new double[n][n]; double[] F = new double[n];

        // --- beam RHS on free nodes 1..NF (SI) ---
        double[][] Fn = nodeForces(d, nd);
        for (int j = 1; j <= NF; j++) for (int k = 0; k < 3; k++) F[3 * (j - 1) + k] = Fn[j][k];
        // --- beam tangent K = −∂F/∂q (FD oracle over the free-node DOF) ---
        double hh = 1e-5;
        for (int jc = 1; jc <= NF; jc++) for (int kc = 0; kc < 3; kc++) {
            int col = 3 * (jc - 1) + kc; double sav = nd[jc][kc];
            nd[jc][kc] = sav + hh; double[][] Fp = nodeForces(d, nd);
            nd[jc][kc] = sav - hh; double[][] Fm = nodeForces(d, nd); nd[jc][kc] = sav;
            for (int jr = 1; jr <= NF; jr++) for (int kr = 0; kr < 3; kr++)
                Msys[3 * (jr - 1) + kr][col] += -((Fp[jr][kr] - Fm[jr][kr]) / (2 * hh)) * 1e6;
        }
        // --- node drag (implicit) + Brownian on free nodes ---
        double aN = d.gammaNode / d.dt;
        for (int j = 1; j <= NF; j++) { int fb = j - 1; for (int k = 0; k < 3; k++) { Msys[3 * fb + k][3 * fb + k] += aN;
            if (brownian) F[3 * fb + k] += brownTorque(d.gammaNode, d.dt, seed, t, SALT_NODE + ((long) j * 131 + k) * 7919L); } }

        // --- two F8/converter/bind blocks (single-head block applied at each pivot) ---
        headBlock(d, d.hA, d.pA, nF,     F8hA, t, seed, brownian, 0, Msys, F);
        headBlock(d, d.hB, d.pB, nF + 2, F8hB, t, seed, brownian, 1, Msys, F);

        // --- decoupling control: pin the fork node (rigid diagonal + zero RHS) so no force crosses it ---
        if (decoupleFork) { int fb = d.Ms - 1; for (int k = 0; k < 3; k++) { F[3 * fb + k] = 0; Msys[3 * fb + k][3 * fb + k] += 1e9; } }

        double[] dq = solveLin(Msys, F, n);
        // increment nodes + angles
        for (int j = 1; j <= NF; j++) { int fb = j - 1; for (int k = 0; k < 3; k++) nd[j][k] += dq[3 * fb + k] * 1e6; }
        d.hA.phi += dq[nF]; d.hA.psi += dq[nF + 1]; d.hB.phi += dq[nF + 2]; d.hB.psi += dq[nF + 3];
        d.nd[0] = d.E.clone();                        // re-pin clamped emergence (defensive)
        pinHead(d.hA, d.nd[d.pA]); pinHead(d.hB, d.nd[d.pB]);
        // crude singular check: solveLin returns NaNs on a zero pivot
        for (double v : dq) if (Double.isNaN(v)) return 1;
        return 0;
    }

    /** Assemble one head's F8 Gauss–Newton + converter + angle-drag block at pivot node `pivot`, angle DOFs
     *  iPhi=angBase, iPsi=angBase+1. Mirrors s2Solve lines 6416-6434 exactly. `hi`∈{0,1} selects the salt. */
    static void headBlock(Dimer d, Cmot h, int pivot, int angBase, double[] F8h, int t, int seed, boolean brownian,
                          int hi, double[][] Msys, double[] F) {
        int pB = 3 * (pivot - 1);           // pivot node free-block
        int iPhi = angBase, iPsi = angBase + 1;
        double[] E = h.eup, C = h.C, xF8 = h.xF8, P = h.P;
        double[] Jphi = crs(E, sub(C, P)), Jpsi = crs(E, sub(xF8, C));   // µm
        double[][] J = { { 1, 0, 0, dot(Jphi, new double[]{ 1, 0, 0 }) * 1e-6, dot(Jpsi, new double[]{ 1, 0, 0 }) * 1e-6 },
                         { 0, 1, 0, dot(Jphi, new double[]{ 0, 1, 0 }) * 1e-6, dot(Jpsi, new double[]{ 0, 1, 0 }) * 1e-6 },
                         { 0, 0, 1, dot(Jphi, new double[]{ 0, 0, 1 }) * 1e-6, dot(Jpsi, new double[]{ 0, 0, 1 }) * 1e-6 } };
        double kfSI = h.kF8Code * 1e6, kc = h.kconvCode, kb = h.kbindCode;
        int[] map = { pB, pB + 1, pB + 2, iPhi, iPsi };
        for (int i = 0; i < 5; i++) for (int jj = 0; jj < 5; jj++) { double kij = kfSI * (J[0][i] * J[0][jj] + J[1][i] * J[1][jj] + J[2][i] * J[2][jj]);
            Msys[map[i]][map[jj]] += kij; }
        Msys[iPhi][iPhi] += kc; Msys[iPhi][iPsi] -= kc; Msys[iPsi][iPhi] -= kc; Msys[iPsi][iPsi] += kc + kb;
        // M3/M6 free-converter cant: shift the FREE head's converter rest toward a state-dependent canted angle,
        // via an extra spring (stiffness kc·dirConvStiffMult) — re-aims the free head's F8 tip along the stroke axis.
        if ((d.dirMech == 4 || d.dirMech == 7) && d.dirAct > 0 && d.boundHead >= 0 && hi == (1 - d.boundHead)) {
            double cant = (d.dirMech == 4 ? d.dirAmp : d.dirAmp2) * d.dirAct;
            double kCant = kc * d.dirConvStiffMult; double thCur = h.psi - h.phi, target = h.thetaS + cant;
            Msys[iPhi][iPhi] += kCant; Msys[iPhi][iPsi] -= kCant; Msys[iPsi][iPhi] -= kCant; Msys[iPsi][iPsi] += kCant;
            F[iPhi] += kCant * (thCur - target); F[iPsi] += -kCant * (thCur - target);
        }
        double aphi = h.gammaPhi / d.dt, apsi = h.gammaPsi / d.dt; Msys[iPhi][iPhi] += aphi; Msys[iPsi][iPsi] += apsi;
        double th = h.psi - h.phi;
        double QphiF8 = dot(E, crs(sub(C, P), F8h)) * 1e-6, QpsiF8 = dot(E, crs(sub(xF8, C), F8h)) * 1e-6;
        F[pB] += F8h[0]; F[pB + 1] += F8h[1]; F[pB + 2] += F8h[2];
        F[iPhi] += QphiF8 + kc * (th - h.thetaS); F[iPsi] += QpsiF8 - kc * (th - h.thetaS) - kb * (h.psi - h.psiActin);
        if (brownian) { F[iPhi] += brownTorque(h.gammaPhi, d.dt, seed, t, SALT_ANG + hi * 2L);
                        F[iPsi] += brownTorque(h.gammaPsi, d.dt, seed, t, SALT_ANG + hi * 2L + 1); }
    }

    // ================= geometry census =================

    /** Max joint gap (nm) over all segments = |actual segment length − l0|, the beam-continuity check. */
    static double maxJointGap(Dimer d) {
        double mx = 0;
        for (int si = 0; si < d.seg.length; si++) { int[] sg = d.seg[si]; double[] b = sub(d.nd[sg[1]], d.nd[sg[0]]);
            double len = Math.sqrt(dot(b, b)); mx = Math.max(mx, Math.abs(len - d.segL0[si])); }
        return mx * 1e3;
    }
    /** Total contour length (µm). */
    static double contour(Dimer d) { double c = 0; for (int[] sg : d.seg) c += Math.sqrt(dot(sub(d.nd[sg[1]], d.nd[sg[0]]), sub(d.nd[sg[1]], d.nd[sg[0]]))); return c; }

    /** Max branch-segment axial spring force (pN) = max over branch stretch elements of |segKs·(len−l0)|. */
    static double maxBranchAxialForcePn(Dimer d) {
        double mx = 0;
        for (int si = 0; si < d.seg.length; si++) if (d.segIsBranch[si]) {
            int[] sg = d.seg[si]; double len = Math.sqrt(dot(sub(d.nd[sg[1]], d.nd[sg[0]]), sub(d.nd[sg[1]], d.nd[sg[0]])));
            double fN = d.segKs[si] * ((len - d.segL0[si]) * 1e-6);
            mx = Math.max(mx, Math.abs(fN) * 1e12);
        }
        return mx;
    }
    /** Max branch-segment extension (nm) = max over branch stretch elements of |len−l0|. */
    static double maxBranchExtNm(Dimer d) {
        double mx = 0;
        for (int si = 0; si < d.seg.length; si++) if (d.segIsBranch[si]) {
            int[] sg = d.seg[si]; double len = Math.sqrt(dot(sub(d.nd[sg[1]], d.nd[sg[0]]), sub(d.nd[sg[1]], d.nd[sg[0]])));
            mx = Math.max(mx, Math.abs(len - d.segL0[si]) * 1e3);
        }
        return mx;
    }
    /** Max branch-segment extension as a FRACTION of the branch rest length (physical-plausibility check). */
    static double maxBranchExtFrac(Dimer d) {
        double mx = 0;
        for (int si = 0; si < d.seg.length; si++) if (d.segIsBranch[si]) {
            int[] sg = d.seg[si]; double len = Math.sqrt(dot(sub(d.nd[sg[1]], d.nd[sg[0]]), sub(d.nd[sg[1]], d.nd[sg[0]])));
            mx = Math.max(mx, Math.abs(len - d.segL0[si]) / d.segL0[si]);
        }
        return mx;
    }
    /** Fork opening angle (deg): the full angle between the two proximal branches at the fork node (rest = 2·α). */
    static double forkAngleDeg(Dimer d) {
        double[] a = sub(d.nd[d.Ms + 1], d.nd[d.Ms]), b = sub(d.nd[d.Ms + d.Ma + 1], d.nd[d.Ms]);
        double la = Math.sqrt(dot(a, a)), lb = Math.sqrt(dot(b, b)); if (la < 1e-12 || lb < 1e-12) return 0;
        return Math.toDegrees(Math.acos(clamp(dot(a, b) / (la * lb))));
    }

    /** Rest length (µm) of the segment connecting nodes a and b (unordered); used to size hinge bending stiffness. */
    static double segLenOf(Dimer d, int a, int b) {
        for (int si = 0; si < d.seg.length; si++) {
            int lo = d.seg[si][0], hi = d.seg[si][1];
            if ((lo == a && hi == b) || (lo == b && hi == a)) return d.segL0[si];
        }
        return d.l0;   // unreachable for a well-formed hinge; fall back to the nominal shared length
    }

    static double clamp(double c) { return Math.max(-1, Math.min(1, c)); }

    /** Signed cant angle (rad) for a fork branch (side 0=A / 1=B) under the active directional mechanism: the FREE
     *  branch rotates toward the barbed end (+), the BOUND branch rotates away (−, M1b/M2 only). Polarity-aware
     *  (the rotation target is d.bHat). Returns 0 when no branch-cant mechanism is active. */
    static double forkCant(Dimer d, int bs) {
        if (d.dirAct <= 0 || d.boundHead < 0 || bs < 0) return 0;
        boolean branchMech = d.dirMech == 1 || d.dirMech == 2 || d.dirMech == 3 || d.dirMech == 5 || d.dirMech == 7 || d.dirMech == 8;
        if (!branchMech) return 0;
        int freeSide = 1 - d.boundHead;
        // Mechanism 8 (AXIAL LEAD-LAG): the free branch rotates TOWARD barbed (+φFree), the bound branch rotates AWAY
        // from barbed toward the pointed end (−φBound = −dirComp·φFree) — this offsets the two pivots along bHat.
        if (bs == freeSide) return d.dirAmp * d.dirAct;
        boolean balanced = d.dirMech == 2 || d.dirMech == 3 || d.dirMech == 8;   // M1b/M2/lead-lag apply a bound-branch counter-rotation
        if (bs == d.boundHead && balanced) return -d.dirComp * d.dirAmp * d.dirAct;
        return 0;
    }
    /** Rotate unit vector v toward unit vector target by angle ang (rad; negative = away), about (v×target). */
    static double[] rotToward(double[] v, double[] target, double ang) {
        double[] axis = crs(v, target); double an = Math.sqrt(dot(axis, axis));
        if (an < 1e-9) return v.clone();
        axis = scl(axis, 1.0 / an);
        double c = Math.cos(ang), s = Math.sin(ang); double[] cross = crs(axis, v);
        return add(add(scl(v, c), scl(cross, s)), scl(axis, dot(axis, v) * (1 - c)));
    }

    private ExplicitHmmDimer() {}
}
