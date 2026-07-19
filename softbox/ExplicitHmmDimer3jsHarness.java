package softbox;

import static softbox.TwoBodyConverterMotor.add;
import static softbox.TwoBodyConverterMotor.sub;
import static softbox.TwoBodyConverterMotor.scl;
import static softbox.TwoBodyConverterMotor.dot;
import static softbox.TwoBodyConverterMotor.geomC;

import softbox.ExplicitHmmDimer.Dimer;
import softbox.TwoBodyConverterMotor.Cmot;
import softbox.TwoBodyConverterMotor.Glide2D;
import softbox.TwoBodyConverterMotor.Tol;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dynamic one-dimer `-3js` visualization assay for the explicit HMM dimer ({@link ExplicitHmmDimer}).
 *
 * ONE surface-anchored `explicit-hmm-dimer-s2-l40` + ONE real dynamic multi-segment actin filament.
 * Each of the two heads goes through the PRODUCTION per-head machinery — the exact stages of
 * `TwoBodyConverterMotor.stepGlideS2` — search → canonical half-open bind gate (zero physical margin) →
 * `cycleLymnTaylor` nucleotide chemistry (ADP·Pi→ADP power stroke) → `bondForces` F8h → CSR gather →
 * chain forces → filament Brownian+integrate+derive — with the ONLY substitution being that the two
 * independent `s2SolveM` tail solves are replaced by the coupled forked-tail {@link ExplicitHmmDimer#solve}
 * (the two heads share one S2 beam). NOT a hand-authored animation; NOT the fixed-actin F8 spring.
 *
 * Exports viewer frames (2 `myosins` entries A/B colored by nucleotide state, branches+shared-S2 as
 * `segments`, fork+anchor as `nodes`, the real actin via the actin renderer, F8 bond lines when bound) +
 * a synchronized event CSV. CPU-only.
 *
 *   ./scripts/run_hmm_dimer_3js.sh -scan                         # headless seed/placement scan (find a good trajectory)
 *   ./scripts/run_hmm_dimer_3js.sh -3js <dir> -seed N -steps M   # export the movie for one run
 */
public final class ExplicitHmmDimer3jsHarness {

    static final double DT = 2.5e-6;
    static final double PRE  = TwoBodyConverterMotor.PRESTROKE_THETAS;   // -30° (ADP·Pi)
    static final double FIL_R = Constants.radius;                        // actin radius (µm)
    static final int SETTLE = 800;
    static final double TOL_MOVE = 3e-7;
    // Viewer cleanliness (§13): the translucent grey fork-node sphere obscured the converter/pivot region. It is
    // DEFAULT-OFF (the fork is shown by the three beam cylinders meeting at its vertex); -forknode restores it.
    static boolean SHOW_FORK_SPHERE = false;

    // ---- same-filament bound-site occupancy exclusion (one actin-monomer spacing) ----
    // Once one head of the dimer is bound, the partner may not bind at a material coordinate < exclusion away on
    // the SAME filament (symmetric, no barbed/pointed preference — any forward bias must emerge from mechanics).
    // Per-scene (Scene.exclusionNm); default OFF (0) so every prior harness path is byte-unchanged.
    static final double DEFAULT_EXCLUSION_NM = 5.4;   // task study value (~one actin monomer spacing)
    static final double EXCL_TOL_NM = 1e-3;           // boundary tolerance: reject iff |Δ| < excl − tol ⇒ 5.4 nm is ALLOWED

    /** Continuous material coordinate (µm) of site (seg, arcUm) along the whole filament, increasing toward the
     *  BARBED end (+uVec / end2). Walks the chain from the pointed terminal (end1NbrSlot==SENTINEL) via end2NbrSlot,
     *  so it is correct on the same segment, on neighbouring segments, ACROSS a segment boundary, and anywhere on
     *  the filament. arcUm = bindArc ∈ [0, segLength] measured from e1 (= c − half·u). */
    static double filMatCoordUm(FilamentStore f, int nSeg, int seg, double arcUm) {
        int start = -1;
        for (int k = 0; k < nSeg; k++) if (f.end1NbrSlot.get(k) == FilamentStore.SENTINEL_NO_NBR) { start = k; break; }
        if (start < 0) start = 0;                              // ring/degenerate fallback
        double cum = 0; int cur = start, guard = 0;
        while (cur >= 0 && guard++ <= nSeg) {
            if (cur == seg) return cum + arcUm;
            cum += f.segLength.get(cur);
            int nxt = f.end2NbrSlot.get(cur); cur = (nxt == FilamentStore.SENTINEL_NO_NBR) ? -1 : nxt;
        }
        double c2 = 0; for (int k = 0; k < seg; k++) c2 += f.segLength.get(k); return c2 + arcUm;   // fallback: index order
    }

    /** Occupancy veto: true ⇒ reject the candidate. Symmetric (|Δ| is order-independent); same-filament only
     *  (different filaments never exclude); boundary at exclNm is ALLOWED (reject iff |Δ| < exclNm − tol). */
    static boolean occupancyVeto(int candFil, double candMatUm, int partnerFil, double partnerMatUm, double exclNm) {
        if (exclNm <= 0) return false;                        // disabled (control)
        if (candFil != partnerFil) return false;              // different physical filament
        double dNm = Math.abs(candMatUm - partnerMatUm) * 1e3;
        return dNm < exclNm - EXCL_TOL_NM;
    }

    /** Material coordinate (µm) of the FREE head m: project its F8 tip onto the nearest filament segment (clamped
     *  closest point) and read the continuous coordinate. This is where the free head would propose to bind — its
     *  signed offset from the bound partner's site is the free-head search position (§3/§4). */
    static double freeHeadMatCoordUm(Glide2D G, int nSeg, int m) {
        int s = TwoBodyConverterMotor.nearestSeg2D(G, m); if (s < 0) return Double.NaN;
        FilamentStore f = G.fil; double half = 0.5 * f.segLength.get(s);
        double cx = f.coordX(s), cy = f.coordY(s), cz = f.coordZ(s), ux = f.uVecX(s), uy = f.uVecY(s), uz = f.uVecZ(s);
        double[] x = G.xF8_[m];
        double foot = (x[0] - cx) * ux + (x[1] - cy) * uy + (x[2] - cz) * uz;
        double footC = Math.max(-half, Math.min(half, foot));
        return filMatCoordUm(f, nSeg, s, footC + half);
    }

    // ---- scene ----
    static final class Scene {
        Glide2D G; Dimer d; Tol tol;
        double[] fork0, actin0;    // references for displacement readouts
        double[] tipA0, tipB0;
        double exclusionNm = 0;    // same-filament bound-site exclusion (nm); 0 = OFF (default, byte-preserving)
        int filId = 0;             // this scene has a single filament (id 0); both heads bind it
        boolean noStroke = false;  // §5 Condition 2 control: hold every head at PRE (suppress the power stroke)
        // directional-mechanism survey (dynamic path); default OFF
        int dirMech = 0; double dirAmpDeg = 0, dirComp = 0, dirConvStiff = 1, dirAmp2Deg = 0; int dirTrigger = 1;   // 1 T1 bind / 2 T2 post-stroke / 3 T3 stroke-ramp
        boolean rearVeto = false;  // C3 phenomenological hard rearward veto
        double rearPenalty = 1.0;  // C4 backward on-rate multiplier γ (1 = none)
    }

    public static void main(String[] args) throws IOException {
        boolean scan = has(args, "-scan");
        String jsDir = argStr(args, "-3js", null);
        int seed = (int) argD(args, "-seed", 12345);
        int steps = (int) argD(args, "-steps", 8000);        // 20 ms at dt=2.5e-6
        int stride = (int) argD(args, "-stride", 40);        // 0.1 ms
        double splay = argD(args, "-splay", 16.0);
        double gapNm = argD(args, "-gap", 12.0);
        int nSeg = (int) argD(args, "-nseg", 12);
        double filBrown = argD(args, "-filbrown", 1.0);
        double alpha = argD(args, "-alpha", 0.0), brEI = argD(args, "-branchei", 1.0), brEA = argD(args, "-branchea", 1.0);
        double branchLen = argD(args, "-branchlen", ExplicitHmmDimer.L0_NM);
        SHOW_FORK_SPHERE = has(args, "-forknode");   // §13: default OFF (removes the grey converter/pivot sphere)

        if (scan) { scan(splay, gapNm, nSeg, steps); return; }

        Scene sc = buildScene(splay, gapNm, nSeg, DT, seed, filBrown, alpha, brEI, brEA, branchLen);
        sc.exclusionNm = has(args, "-noexcl") ? 0.0 : argD(args, "-excl", 0.0);   // default OFF (byte-preserving); -excl 5.4 engages
        sc.noStroke = has(args, "-nostroke");   // §5 Condition 2 control: bound head held pre-stroke
        sc.dirMech = (int) argD(args, "-mech", 0); sc.dirAmpDeg = argD(args, "-mechamp", 0); sc.dirComp = argD(args, "-mechcomp", 0);
        sc.dirConvStiff = argD(args, "-mechstiff", 1); sc.dirAmp2Deg = argD(args, "-mechamp2", 0); sc.dirTrigger = (int) argD(args, "-mechtrig", 2);
        sc.rearVeto = has(args, "-rearveto"); sc.rearPenalty = argD(args, "-rearpen", 1.0);
        if (sc.dirMech != 0 || sc.rearVeto || sc.rearPenalty < 1.0)
            System.out.printf(Locale.US, "directional: mech=%d amp=%.0f° comp=%.2f stiff=%.2f amp2=%.0f° trig=T%d | rearVeto=%b rearPenalty=%.2f%n",
                    sc.dirMech, sc.dirAmpDeg, sc.dirComp, sc.dirConvStiff, sc.dirAmp2Deg, sc.dirTrigger, sc.rearVeto, sc.rearPenalty);
        System.out.printf(Locale.US, "fork: alpha=%.0f° (total opening %.0f°), branchEI×%.2f branchEA×%.2f, branchLen=%.1f nm (shared S2 %.1f nm); bound-site exclusion %s%n",
                alpha, 2 * alpha, brEI, brEA, branchLen, ExplicitHmmDimer.TOTAL_NM - branchLen,
                sc.exclusionNm > 0 ? String.format(Locale.US, "%.1f nm", sc.exclusionNm) : "OFF");
        System.out.printf(Locale.US, "=== HMM DIMER dynamic assay: seed=%d steps=%d stride=%d splay=%.0f° gap=%.1f nm nSeg=%d ===%n",
                seed, steps, stride, splay, gapNm, nSeg);
        Run r = run(sc, seed, steps, stride, jsDir);
        r.printStatusBlock(seed, steps, stride, jsDir, splay, gapNm, nSeg);
    }

    // ============================ scene construction ============================
    /** Build one anchored dimer + a positioned real dynamic multi-segment filament + a 2-motor Glide2D. */
    static Scene buildScene(double splay, double gapNm, int nSeg, double dt, int seed) { return buildScene(splay, gapNm, nSeg, dt, seed, 1.0, 0, 1, 1); }
    static Scene buildScene(double splay, double gapNm, int nSeg, double dt, int seed, double filBrown, double alphaDeg, double brEI, double brEA) {
        return buildScene(splay, gapNm, nSeg, dt, seed, filBrown, alphaDeg, brEI, brEA, ExplicitHmmDimer.L0_NM);
    }
    /** filBrown scales the filament's thermal amplitude: a finite rod diffuses faster than the µm+ gliding-assay
     *  filament it represents, so <1 keeps it engaged (it still integrates, bends, and responds to motor load).
     *  alphaDeg = fork rest half-angle; brEI/brEA = proximal-branch compliance multipliers (shared S2 untouched);
     *  branchLenNm = proximal branch contour (shared S2 = 40 − branchLenNm; branchLenNm=10 ⇒ the reference model). */
    static Scene buildScene(double splay, double gapNm, int nSeg, double dt, int seed, double filBrown, double alphaDeg, double brEI, double brEA, double branchLenNm) {
        Dimer d = ExplicitHmmDimer.build(3, 1, 1, splay, dt, alphaDeg, brEI, brEA, branchLenNm);
        // settle unbound to the pre-stroke equilibrium (F8h=0) to read the head-tip positions
        d.hA.thetaS = PRE; d.hB.thetaS = PRE;
        for (int it = 0; it < SETTLE; it++) { double[] snap = flat(d);
            ExplicitHmmDimer.solve(d, it, seed, false, new double[3], new double[3]);
            if (maxMove(snap, d) < TOL_MOVE) break; }
        // translate the whole dimer so the head tips sit just BELOW a filament held at its (y,z)=(0,0)
        // confinement equilibrium — the heads reach UP into it (steady-state surf ≈ gap) for sustained binding.
        double mtz = 0.5 * (d.hA.xF8[2] + d.hB.xF8[2]);
        double zTarget = -(FIL_R + gapNm * 1e-3);
        translate(d, new double[]{ 0, 0, zTarget - mtz });
        double[] tipA = d.hA.xF8.clone(), tipB = d.hB.xF8.clone();
        double cx = 0.5 * (tipA[0] + tipB[0]);
        double zfil = 0.0;                                  // filament at its z-confine equilibrium; heads just below

        Glide2D G = new Glide2D();
        G.dt = dt; G.rigid = false; G.filBrown = true; G.density = 0;
        G.kzCode = 2.0 * TwoBodyConverterMotor.PNNM;        // G4_KZ z-confine toward z=0
        G.matXlo = -5; G.matXhi = 5; G.matYlo = -5; G.matYhi = 5;
        // canonical explicit frame + converter params (same reference build the mat uses)
        Cmot ref = TwoBodyConverterMotor.build3core(dt, 1.0, 128, 512, 0, 0, TwoBodyConverterMotor.IDENT, false,
                TwoBodyConverterMotor.LB_3C, TwoBodyConverterMotor.PHI_PRE_3E, TwoBodyConverterMotor.R_F8, TwoBodyConverterMotor.R_CONV, 0, 0);
        G.kF8Code = ref.kF8Code; G.kconvCode = ref.kconvCode; G.kbindCode = ref.kbindCode; G.lb = ref.lb;
        G.bhat = ref.bhat; G.phat = ref.phat; G.eup = ref.eup; G.econv = ref.econv; G.uvecPhys = ref.uvecPhys;
        G.rF8 = ref.rF8.clone(); G.rConv = ref.rConv.clone(); G.gammaPhi = ref.gammaPhi; G.gammaPsi = ref.gammaPsi;
        G.xbParams = ref.xbParams;

        // --- real dynamic multi-segment actin chain along +x, centered (cx,0,zfil) ---
        double segLen = (64 + 1) * Constants.actinMonoRadius;   // ≈0.176 µm (G4_MONO=64)
        FilamentStore f = new FilamentStore(nSeg); double x0 = cx - 0.5 * (nSeg - 1) * segLen;
        for (int k = 0; k < nSeg; k++) {
            f.monomerCount.set(k, 64); f.setUVec(k, 1f, 0f, 0f); f.setYVec(k, 0f, 1f, 0f);
            f.setCoord(k, (float) (x0 + k * segLen), 0f, (float) zfil);
            f.brownTransScale.set(k, (float) (Constants.BTransCoeff * filBrown));
            boolean interior = (k > 0 && k < nSeg - 1);
            f.brownRotScale.set(k, interior ? 0f : (float) (Constants.BRotCoeff * filBrown));
            if (k < nSeg - 1) { f.end2NbrSlot.set(k, k + 1); f.end2NbrSide.set(k, 0); }
            if (k > 0) { f.end1NbrSlot.set(k, k - 1); f.end1NbrSide.set(k, 1); }
        }
        DragTensorSystem.run(f); f.setParams(dt, Constants.brownianForceMag(dt)); f.setChainParams(dt);
        f.chainParams.set(0, (float) dt); f.setCounts(0, seed);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        G.nSeg = nSeg; G.fil = f;

        // --- 2 motors, one per dimer head ---
        int N = 2; G.N = N; G.mot = new MotorStore(N);
        G.phi = new double[N]; G.psi = new double[N]; G.thetaS = new double[N]; G.psiActin = new double[N];
        G.A = new double[N][]; G.C_ = new double[N][]; G.xH_ = new double[N][]; G.xF8_ = new double[N][];
        G.noBind = new boolean[N]; G.active = new boolean[N]; G.siteX = new double[N]; G.siteY = new double[N];
        for (int m = 0; m < N; m++) G.mot.assembleArticulated(m, 0f, 0f, (float) LaserTrapHarness.MANCHOR_Z, 0f, 0f, 1f, 0f);
        DragTensorSystem.run(G.mot); G.mot.setBodyParams(dt); G.mot.setKinParams(0.006, -0.4, dt); G.mot.setNucParams(dt);
        Cmot[] heads = { d.hA, d.hB }; int[] piv = { d.pA, d.pB };
        for (int m = 0; m < N; m++) {
            G.mot.boundSeg.set(m, MotorStore.FREE_BINDABLE); G.mot.nucleotideState.set(m, MotorStore.NUC_ADPPI);
            G.A[m] = d.nd[piv[m]].clone(); G.phi[m] = heads[m].phi; G.psi[m] = heads[m].psi;
            G.psiActin[m] = heads[m].psiActin; G.thetaS[m] = PRE; G.noBind[m] = false;
        }
        G.bondData = new FloatArray(N * CrossBridgeSystem.STRIDE); G.bondData.init(0f);
        G.segCount = new IntArray(nSeg); G.segOff = new IntArray(nSeg + 1); G.segMyo = new IntArray(N);
        G.cullMode = 2;   // brute (all active) — trivial for N=2
        for (int m = 0; m < N; m++) TwoBodyConverterMotor.geom2D(G, m);

        Scene sc = new Scene(); sc.G = G; sc.d = d; sc.tol = new Tol();
        sc.fork0 = d.nd[d.Ms].clone(); sc.actin0 = new double[]{ f.coordX(nSeg / 2), f.coordY(nSeg / 2), f.coordZ(nSeg / 2) };
        sc.tipA0 = tipA; sc.tipB0 = tipB;
        return sc;
    }

    // ============================ the coupled dynamic step ============================
    /** The production stepGlideS2 stages (bind gate, chemistry, thetaS, place, bondForces, gather, chain,
     *  filament dynamics) with the two per-head s2SolveM replaced by the coupled forked-tail solve. */
    static void step(Scene sc, int t, int seed, Run r) {
        Glide2D G = sc.G; Dimer d = sc.d; MotorStore mot = G.mot; FilamentStore f = G.fil;
        RigidRodBody b = mot.body; int N = G.N; Cmot[] heads = { d.hA, d.hB }; int[] piv = { d.pA, d.pB };
        r.occupancyRejectThisFrame = 0;
        // mirror the authoritative converter/pivot state (held on the Cmot heads) into G for the production stages
        for (int m = 0; m < N; m++) { G.phi[m] = heads[m].phi; G.psi[m] = heads[m].psi; G.A[m] = d.nd[piv[m]].clone(); }

        for (int m = 0; m < N; m++) G.active[m] = true;   // cullMode=2
        // 1. BIND gate (canonical half-open ownership, zero physical margin) — copy of stepGlideS2 L6828-6832,
        //    PLUS the dimer-specific same-filament bound-site occupancy exclusion applied ONLY after a geometrically
        //    valid candidate is found and BEFORE the bind is committed (it can only VETO an otherwise valid site;
        //    it never moves a head or alters coordinates). Head 0 is evaluated (and commits) before head 1 ⇒ a
        //    deterministic lower-head-index tie-break for exact simultaneous same-step conflicts.
        for (int m = 0; m < N; m++) if (!G.noBind[m] && mot.boundSeg.get(m) == MotorStore.FREE_BINDABLE && mot.nucleotideState.get(m) == MotorStore.NUC_ADPPI) {
            G.thetaS[m] = PRE; TwoBodyConverterMotor.geom2D(G, m); int s = TwoBodyConverterMotor.nearestSeg2D(G, m); if (s < 0) continue;
            double[] gm = TwoBodyConverterMotor.gate2D(G, m, s); double half = 0.5 * f.segLength.get(s), margin = TwoBodyConverterMotor.bindMargin();
            boolean g0 = gm[0] < sc.tol.dBindNm, g1 = gm[2] < sc.tol.psiDeg, g2 = gm[3] < sc.tol.phiDeg, g3 = gm[4] < sc.tol.thetaDeg,
                    g4 = gm[5] < sc.tol.preloadPn, g5 = gm[6] < sc.tol.energyKt, g6 = gm[7] < TwoBodyConverterMotor.A_SEMI[2] * 1e3, g7 = gm[1] > margin && gm[1] < 2 * half - margin;
            if (!(g0 && g1 && g2 && g3 && g4 && g5 && g6 && g7)) continue;   // geometric gate: not a valid candidate
            // --- occupancy exclusion (dimer, same filament) ---
            int p = (N == 2) ? (1 - m) : -1;
            boolean partnerBound = p >= 0 && mot.boundSeg.get(p) >= 0;
            double candMat = filMatCoordUm(f, G.nSeg, s, gm[1]);
            if (partnerBound) {
                double partnerMat = filMatCoordUm(f, G.nSeg, mot.boundSeg.get(p), mot.bindArc.get(p));
                double signedNm = (candMat - partnerMat) * 1e3;   // + toward barbed
                boolean veto = occupancyVeto(sc.filId, candMat, sc.filId, partnerMat, sc.exclusionNm);
                r.partnerBoundProposals++;
                r.proposalLog.add(String.format(Locale.US, "%.4f,%d,%b,%d,%d,%.4f,%.4f,%.3f,%.3f,%s,%s,%s,%s,%b",
                        t * DT * 1e3, m, partnerBound, sc.filId, sc.filId, candMat, partnerMat, signedNm, Math.abs(signedNm),
                        veto ? "REJECTED" : "ACCEPTED", veto ? "occupancy<excl" : "-",
                        NUC[mot.nucleotideState.get(m)], NUC[mot.nucleotideState.get(p)], mot.nucleotideState.get(p) == MotorStore.NUC_ADP));
                boolean partnerStroked = mot.nucleotideState.get(p) == MotorStore.NUC_ADP;
                if (veto) { r.occupancyRejects++; r.occupancyRejectThisFrame++;
                    if (signedNm >= 0) r.fwdInside++; else r.bwdInside++; continue; }   // reject: do NOT commit, do NOT move
                // C3 hard rearward veto / C4 graded rearward penalty (phenomenological benchmarks; backward = signedNm<0)
                if (signedNm < 0) {
                    if (sc.rearVeto) { r.rearRejects++; continue; }
                    if (sc.rearPenalty < 1.0) { double u = hash01(((long) t * 2654435761L) ^ ((long) seed * 40503L) ^ (m * 2246822519L));
                        if (u >= sc.rearPenalty) { r.rearRejects++; continue; } }
                }
                // accepted second-head bind
                if (signedNm >= 0) { r.fwdBinds++; r.lastDir = "FWD"; if (partnerStroked) r.fwdAccPost++; else r.fwdAccPre++; }
                else { r.bwdBinds++; r.lastDir = "BWD"; if (partnerStroked) r.bwdAccPost++; else r.bwdAccPre++; }
                r.lastSignedOffsetNm = signedNm; r.acceptedSignedOffsets.add(signedNm);
            }
            mot.boundSeg.set(m, s); mot.bindArc.set(m, (float) gm[1]);
        }
        // 2. CHEMISTRY (cycleLymnTaylor) + 3. thetaS from nucleotide
        mot.setCounts(t, seed, G.nSeg);
        NucleotideCycleSystem.cycleLymnTaylor(mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts);
        for (int m = 0; m < N; m++) G.thetaS[m] = TwoBodyConverterMotor.thetaS4a(mot.nucleotideState.get(m));
        if (sc.noStroke) for (int m = 0; m < N; m++) G.thetaS[m] = PRE;   // §5 Condition 2: suppress the power stroke
        // 4. place heads + bond forces (F8h)
        for (int m = 0; m < N; m++) { TwoBodyConverterMotor.geom2D(G, m); TwoBodyConverterMotor.placeHead2D(G, m); }
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams);
        // 5. CSR gather → chain → z-confine → filament Brownian + integrate + derive (shared filament stages)
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, G.segCount);
        CrossBridgeSystem.csrScan(mot.counts, G.segCount, G.segOff);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, G.segOff, G.segCount, G.segMyo);
        CrossBridgeSystem.segGather(G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts);
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        for (int s = 0; s < G.nSeg; s++) { int iz = 2 * G.nSeg + s; f.forceSum.set(iz, (float) (f.forceSum.get(iz) - G.kzCode * f.coordZ(s)));
            int iy = G.nSeg + s; f.forceSum.set(iy, (float) (f.forceSum.get(iy) - G.kzCode * f.coordY(s))); }   // lateral (y) confinement: the visible piece of a long filament stays in the heads' plane
        f.counts.set(1, t); f.counts.set(2, seed);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.orthogonalizeY(f.uVec, f.yVec, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        // 6. read F8h per head, apply the chemistry-driven thetaS, run the COUPLED forked-tail solve
        int ST = CrossBridgeSystem.STRIDE;
        double[] f8A = { G.bondData.get(0 * ST), G.bondData.get(0 * ST + 1), G.bondData.get(0 * ST + 2) };
        double[] f8B = { G.bondData.get(1 * ST), G.bondData.get(1 * ST + 1), G.bondData.get(1 * ST + 2) };
        d.hA.thetaS = G.thetaS[0]; d.hB.thetaS = G.thetaS[1];
        // directional mechanism activation (state-triggered, smoothly ramped) — acts only with exactly one head bound
        if (sc.dirMech != 0) {
            d.dirMech = sc.dirMech; d.dirAmp = Math.toRadians(sc.dirAmpDeg); d.dirComp = sc.dirComp;
            d.dirConvStiffMult = sc.dirConvStiff; d.dirAmp2 = Math.toRadians(sc.dirAmp2Deg); d.bHat = new double[]{ 1, 0, 0 };
            int ba = mot.boundSeg.get(0), bb = mot.boundSeg.get(1);
            int bound = (ba >= 0 && bb < 0) ? 0 : (bb >= 0 && ba < 0) ? 1 : -1;
            double target = 0;
            if (bound >= 0) target = (sc.dirTrigger == 1) ? 1.0 : (mot.nucleotideState.get(bound) == MotorStore.NUC_ADP ? 1.0 : 0.0);
            d.boundHead = bound;
            double rate = (sc.dirTrigger == 3) ? 0.02 : 0.05;   // T3 = slower stroke-ramp
            d.dirAct += Math.max(-0.05, Math.min(rate, target - d.dirAct)); d.dirAct = Math.max(0, Math.min(1, d.dirAct));
        } else { d.dirMech = 0; d.dirAct = 0; d.boundHead = -1; }
        ExplicitHmmDimer.solve(d, t, seed, true, f8A, f8B);     // Brownian ON (search) — updates beam + converters
        // 7. writeback per-head load for next step's catch-slip chemistry
        for (int m = 0; m < N; m++) { double fd = G.bondData.get(m * ST + 12);
            double fx = G.bondData.get(m * ST), fy = G.bondData.get(m * ST + 1), fz = G.bondData.get(m * ST + 2);
            mot.forceDotFil.set(m, mot.boundSeg.get(m) >= 0 ? (float) fd : 0f);
            mot.forceMag.set(m, mot.boundSeg.get(m) >= 0 ? (float) Math.sqrt(fx * fx + fy * fy + fz * fz) : 0f); }
    }

    // ============================ run + export ============================
    static final class Run {
        boolean detachedSearch, bindA, bindB, twoBound, stroke, coupling, detach;
        double maxBindDisc, peakFA, peakFB, strokeA, strokeB, partnerInduced, maxGap, maxContourDrift, maxStepTip;
        // §1 separation distributions
        double overlapFrac, headSepMean, headSepMed, f8SepMean, openMean, sepDet, sepOne, sepBoth;
        double axAdpMean, axAdpMed, axPiMean, fracOff38, dblFrac, dwellMean; int dblFrames;
        int invalid, frames; String trajFile = "-", csvFile = "-";
        int firstBindStep = -1, firstStrokeStep = -1;
        double lastFA, lastFB;
        // ---- bound-site occupancy exclusion diagnostics (this study) ----
        int partnerBoundProposals;      // second-head proposals (candidate passed the geometric gates while partner bound)
        int occupancyRejects;           // proposals vetoed by the same-filament exclusion
        int fwdBinds, bwdBinds;         // accepted second-head binds toward barbed (+) / pointed (−)
        double minSiteSepNm = Double.NaN;   // minimum |material offset| observed while both heads bound the same filament
        double lastSignedOffsetNm = Double.NaN;   // signed offset (+barbed) of the most recent accepted second-head bind
        String lastDir = "-";
        java.util.List<Double> acceptedSignedOffsets = new java.util.ArrayList<>();
        java.util.List<String> proposalLog = new java.util.ArrayList<>();   // §7 per-proposal records
        int nearSiteDoubleBind;         // INVARIANT check: both bound same fil at |Δ| < excl − tol (must stay 0)
        int occupancyRejectThisFrame;   // per-step reject count (for the -3js event log)
        // ---- forward-accessibility diagnostics (this study; §2/§3/§4/§9) ----
        int fwdInside, bwdInside;       // partner-bound proposals rejected by occupancy, split by sign(deltaS)
        int oneBoundSteps, preStrokeSteps, postStrokeSteps;   // one-head-bound exposure (steps) total / partner-pre / partner-post
        java.util.List<Double> freeSignedPre = new java.util.ArrayList<>();    // free-head signed material coord (nm, +barbed) vs bound site, partner PRE-stroke
        java.util.List<Double> freeSignedPost = new java.util.ArrayList<>();   // ... partner POST-stroke (ADP)
        java.util.List<Double> acceptedTimeSinceStroke = new java.util.ArrayList<>();   // ms since partner stroke at each accepted 2nd-bind
        int fwdAccPost, bwdAccPost, fwdAccPre, bwdAccPre;      // accepted 2nd-binds split by direction AND partner stroke state
        int rearRejects;   // backward proposals rejected by C3 veto / C4 penalty (phenomenological benchmarks)
        void printStatusBlock(int seed, int steps, int stride, String jsDir, double splay, double gap, int nSeg) {
            String cmd = String.format(Locale.US, "./scripts/run_hmm_dimer_3js.sh -3js %s -seed %d -steps %d -stride %d -splay %.0f -gap %.1f -nseg %d",
                    jsDir == null ? "<dir>" : jsDir, seed, steps, stride, splay, gap, nSeg);
            System.out.println("\n================= FINAL STATUS BLOCK =================");
            System.out.println("3JS VIEWER WIRED: " + (jsDir != null ? "YES" : "YES (run with -3js <dir> to export)"));
            System.out.println("REAL DYNAMIC ACTIN: YES");
            System.out.println("PRODUCTION BINDING PATH: YES");
            System.out.println("DETACHED SEARCH OBSERVED: " + yn(detachedSearch));
            System.out.println("HEAD A BINDING OBSERVED: " + yn(bindA));
            System.out.println("HEAD B BINDING OBSERVED: " + yn(bindB));
            System.out.println("TWO-HEAD-BOUND STATE OBSERVED: " + yn(twoBound));
            System.out.println("POWER STROKE OBSERVED: " + yn(stroke));
            System.out.println("PARTNER-HEAD COUPLING VISIBLE: " + yn(coupling));
            System.out.println("DETACHMENT OBSERVED: " + yn(detach));
            System.out.printf(Locale.US, "MAX BINDING DISCONTINUITY: %.4f nm  (finite force-onset within the %.4f nm max normal per-step tip motion; binding writes no coordinates)%n", maxBindDisc, maxStepTip);
            System.out.printf(Locale.US, "PEAK F8 FORCE A/B: %.2f / %.2f pN%n", peakFA, peakFB);
            System.out.printf(Locale.US, "STROKE A/B: %.2f / %.2f nm   (partner-head induced via shared S2: %.3f nm)%n", strokeA, strokeB, partnerInduced);
            System.out.printf(Locale.US, "MAX JOINT GAP: %.3f nm ; MAX CONTOUR DRIFT: %.3f nm%n", maxGap, maxContourDrift);
            System.out.println("INVALID STATES: " + invalid);
            System.out.println("TRAJECTORY FILE: " + trajFile);
            System.out.println("EVENT LOG: " + csvFile);
            System.out.println("EXACT COMMAND: " + cmd);
            boolean ready = jsDir != null && detachedSearch && bindA && bindB && twoBound && stroke && coupling && detach
                    && invalid == 0 && maxBindDisc <= maxStepTip + 1e-9 && maxGap < 10;
            System.out.println("READY FOR USER VISUAL INSPECTION: " + yn(ready));
            System.out.println("=====================================================");
        }
    }

    static Run run(Scene sc, int seed, int steps, int stride, String jsDir) throws IOException {
        Run r = new Run(); Glide2D G = sc.G; Dimer d = sc.d;
        List<String> csv = new ArrayList<>();
        csv.add("frame,t_ms,nucA,nucB,boundA,boundB,segA,arcA_nm,segB,arcB_nm,fA_pN,fB_pN,forkDisp_nm,actinDisp_nm,nBound,maxGap_nm,contourDrift_nm,headSep_nm,f8Sep_nm,pivSep_nm,openAng_deg,axOff_nm,siteSep_nm,signedSecondBindOffset_nm,secondBindDirection,occupancyRejectCount,occupancyRejectThisFrame,firstBoundHead,partnerStroked,freeF8Signed_nm,timeSinceFirstBind_ms,timeSincePartnerStroke_ms");
        double contour0 = ExplicitHmmDimer.contour(d);
        double[] prevTipA = sc.tipA0.clone(), prevTipB = sc.tipB0.clone();
        int frame = 0; int ST = CrossBridgeSystem.STRIDE;
        final int STROKE_WIN = 40;   // 0.1 ms window to read the mechanical stroke output
        int[] prevNuc = { G.mot.nucleotideState.get(0), G.mot.nucleotideState.get(1) };
        int[] flipStep = { -1, -1 }; double[][] tipAtFlip = new double[2][], axisAtFlip = new double[2][], partnerTipAtFlip = new double[2][];
        int[] firstBindStepOf = { -1, -1 }, strokeStepOf = { -1, -1 };   // per-head first-bind + first-stroke step (timeSince metrics)
        FrameOut fo = jsDir != null ? new FrameOut(jsDir) : null;
        // --- §1 head-separation distributions by state ---
        List<Double> hsDet = new ArrayList<>(), hsA = new ArrayList<>(), hsB = new ArrayList<>(), hsBoth = new ArrayList<>(), f8All = new ArrayList<>(), openAll = new ArrayList<>();
        List<Double> axPi = new ArrayList<>(), axMix = new ArrayList<>(), axAdp = new ArrayList<>();
        int overlapN = 0, totN = 0; final double OVL = 2 * TwoBodyConverterMotor.A_SEMI[0] * 1e3;   // 9 nm head long-axis diameter
        List<Double> dwell = new ArrayList<>(); int dwellRun = 0;
        for (int t = 0; t < steps; t++) {
            // detect binding-instant discontinuity: head tip jump on the step boundSeg flips −1→≥0
            int bA0 = G.mot.boundSeg.get(0), bB0 = G.mot.boundSeg.get(1);
            double[] preTipA = d.hA.xF8.clone(), preTipB = d.hB.xF8.clone();
            step(sc, t, seed, r);
            int bA = G.mot.boundSeg.get(0), bB = G.mot.boundSeg.get(1);
            if (bA0 < 0 && bA >= 0) { r.bindA = true; if (r.firstBindStep < 0) r.firstBindStep = t; if (firstBindStepOf[0] < 0) firstBindStepOf[0] = t; r.maxBindDisc = Math.max(r.maxBindDisc, dist(d.hA.xF8, preTipA) * 1e3); }
            if (bB0 < 0 && bB >= 0) { r.bindB = true; if (r.firstBindStep < 0) r.firstBindStep = t; if (firstBindStepOf[1] < 0) firstBindStepOf[1] = t; r.maxBindDisc = Math.max(r.maxBindDisc, dist(d.hB.xF8, preTipB) * 1e3); }
            if (bA < 0) firstBindStepOf[0] = -1; if (bB < 0) firstBindStepOf[1] = -1;   // reset on detach
            if (bA >= 0 && bB >= 0) r.twoBound = true;
            // detached search: an unbound head's tip moves appreciably between steps
            if (bA < 0 && dist(d.hA.xF8, prevTipA) * 1e3 > 0.05) r.detachedSearch = true;
            if (bB < 0 && dist(d.hB.xF8, prevTipB) * 1e3 > 0.05) r.detachedSearch = true;
            prevTipA = d.hA.xF8.clone(); prevTipB = d.hB.xF8.clone();
            // stroke onset (ADP·Pi→ADP while bound): record the pre-stroke tip + filament axis + the partner tip,
            // then STROKE_WIN steps later read the axial head displacement (the mechanical stroke) and the
            // partner-head induced displacement transmitted through the shared S2 tail.
            double[][] tips = { d.hA.xF8, d.hB.xF8 }; int[] nuc = { G.mot.nucleotideState.get(0), G.mot.nucleotideState.get(1) }; int[] bnd = { bA, bB };
            r.maxStepTip = Math.max(r.maxStepTip, Math.max(dist(d.hA.xF8, preTipA), dist(d.hB.xF8, preTipB)) * 1e3);
            for (int m = 0; m < 2; m++) {
                if (prevNuc[m] == MotorStore.NUC_ADPPI && nuc[m] == MotorStore.NUC_ADP && bnd[m] >= 0) {
                    flipStep[m] = t; tipAtFlip[m] = tips[m].clone(); strokeStepOf[m] = t;
                    axisAtFlip[m] = new double[]{ G.fil.uVecX(bnd[m]), G.fil.uVecY(bnd[m]), G.fil.uVecZ(bnd[m]) };
                    partnerTipAtFlip[m] = tips[1 - m].clone();
                    r.stroke = true; if (r.firstStrokeStep < 0) r.firstStrokeStep = t;
                }
                if (bnd[m] < 0) strokeStepOf[m] = -1;   // reset on detach
                if (flipStep[m] >= 0 && t >= flipStep[m] + STROKE_WIN) {
                    if (bnd[m] >= 0) { double dd = Math.abs(axial(tips[m], tipAtFlip[m], axisAtFlip[m]));
                        if (m == 0) r.strokeA = Math.max(r.strokeA, dd); else r.strokeB = Math.max(r.strokeB, dd); }
                    double pd = dist(tips[1 - m], partnerTipAtFlip[m]) * 1e3; r.partnerInduced = Math.max(r.partnerInduced, pd);
                    flipStep[m] = -1;
                }
                prevNuc[m] = nuc[m];
            }
            // detachment: a bound head goes unbound
            if ((bA0 >= 0 && bA < 0) || (bB0 >= 0 && bB < 0)) r.detach = true;
            double fA = fmag(G, 0, ST), fB = fmag(G, 1, ST);
            r.peakFA = Math.max(r.peakFA, fA); r.peakFB = Math.max(r.peakFB, fB);
            // --- §1 geometry diagnostics (exact simulation coords) ---
            double headSep = dist(d.hA.xH, d.hB.xH) * 1e3, f8Sep = dist(d.hA.xF8, d.hB.xF8) * 1e3;
            double[] brA = sub(d.nd[d.pA], d.nd[d.Ms]), brB = sub(d.nd[d.pB], d.nd[d.Ms]);
            double openAng = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot(brA, brB) / (Math.sqrt(dot(brA, brA)) * Math.sqrt(dot(brB, brB)) + 1e-30)))));
            totN++; if (headSep < OVL) overlapN++; f8All.add(f8Sep); openAll.add(openAng);
            if (bA < 0 && bB < 0) hsDet.add(headSep); else if (bA >= 0 && bB < 0) hsA.add(headSep); else if (bA < 0 && bB >= 0) hsB.add(headSep); else hsBoth.add(headSep);
            double axOff = Double.NaN, siteSepNm = Double.NaN;
            if (bA >= 0 && bB >= 0) { axOff = twoHeadAxialOffset(G); dwellRun++;
                // continuous MATERIAL site separation (nm) — the quantity the exclusion controls
                double matA = filMatCoordUm(G.fil, G.nSeg, bA, G.mot.bindArc.get(0));
                double matB = filMatCoordUm(G.fil, G.nSeg, bB, G.mot.bindArc.get(1));
                siteSepNm = Math.abs(matA - matB) * 1e3;
                if (Double.isNaN(r.minSiteSepNm) || siteSepNm < r.minSiteSepNm) r.minSiteSepNm = siteSepNm;
                if (sc.exclusionNm > 0 && siteSepNm < sc.exclusionNm - EXCL_TOL_NM) r.nearSiteDoubleBind++;   // INVARIANT: must stay 0
                int na = G.mot.nucleotideState.get(0), nb = G.mot.nucleotideState.get(1);
                if (na == MotorStore.NUC_ADP && nb == MotorStore.NUC_ADP) axAdp.add(axOff);
                else if (na == MotorStore.NUC_ADPPI && nb == MotorStore.NUC_ADPPI) axPi.add(axOff);
                else axMix.add(axOff);
            } else if (dwellRun > 0) { dwell.add(dwellRun * DT * 1e3); dwellRun = 0; }
            // --- §3/§4 continuous free-head search geometry while EXACTLY ONE head is bound ---
            double freeSigned = Double.NaN; boolean partnerStroked = false; double tSinceBind = Double.NaN, tSinceStroke = Double.NaN; int firstBoundHead = -1;
            if ((bA >= 0) ^ (bB >= 0)) {
                int boundIdx = bA >= 0 ? 0 : 1, free = 1 - boundIdx; firstBoundHead = boundIdx;
                double boundMat = filMatCoordUm(G.fil, G.nSeg, G.mot.boundSeg.get(boundIdx), G.mot.bindArc.get(boundIdx));
                double freeMat = freeHeadMatCoordUm(G, G.nSeg, free);
                if (!Double.isNaN(freeMat)) {
                    freeSigned = (freeMat - boundMat) * 1e3;   // + toward barbed
                    partnerStroked = G.mot.nucleotideState.get(boundIdx) == MotorStore.NUC_ADP;
                    r.oneBoundSteps++;
                    if (partnerStroked) { r.postStrokeSteps++; r.freeSignedPost.add(freeSigned); }
                    else if (G.mot.nucleotideState.get(boundIdx) == MotorStore.NUC_ADPPI) { r.preStrokeSteps++; r.freeSignedPre.add(freeSigned); }
                    if (firstBindStepOf[boundIdx] >= 0) tSinceBind = (t - firstBindStepOf[boundIdx]) * DT * 1e3;
                    if (strokeStepOf[boundIdx] >= 0) tSinceStroke = (t - strokeStepOf[boundIdx]) * DT * 1e3;
                }
            }
            // invalid checks
            double gap = ExplicitHmmDimer.maxJointGap(d); r.maxGap = Math.max(r.maxGap, gap);
            double cd = Math.abs(ExplicitHmmDimer.contour(d) - contour0) * 1e3; r.maxContourDrift = Math.max(r.maxContourDrift, cd);
            if (bad(d) || gap > 50 || cd > 50) r.invalid++;
            if (bA >= 0) { double arc = G.mot.bindArc.get(0); if (arc < -1e-3 || arc > G.fil.segLength.get(bA) + 1e-3) r.invalid++; }
            if (bB >= 0) { double arc = G.mot.bindArc.get(1); if (arc < -1e-3 || arc > G.fil.segLength.get(bB) + 1e-3) r.invalid++; }

            if (t % stride == 0 || t == steps - 1) {
                double forkDisp = dist(d.nd[d.Ms], sc.fork0) * 1e3;
                double actinDisp = dist(new double[]{ G.fil.coordX(G.nSeg / 2), G.fil.coordY(G.nSeg / 2), G.fil.coordZ(G.nSeg / 2) }, sc.actin0) * 1e3;
                int nb = (bA >= 0 ? 1 : 0) + (bB >= 0 ? 1 : 0);
                csv.add(String.format(Locale.US, "%d,%.4f,%s,%s,%d,%d,%d,%.2f,%d,%.2f,%.3f,%.3f,%.3f,%.3f,%d,%.4f,%.4f,%.3f,%.3f,%.3f,%.2f,%s,%s,%s,%s,%d,%d",
                        frame, t * DT * 1e3, NUC[G.mot.nucleotideState.get(0)], NUC[G.mot.nucleotideState.get(1)],
                        bA >= 0 ? 1 : 0, bB >= 0 ? 1 : 0, bA, bA >= 0 ? G.mot.bindArc.get(0) * 1e3 : 0.0, bB, bB >= 0 ? G.mot.bindArc.get(1) * 1e3 : 0.0,
                        fA, fB, forkDisp, actinDisp, nb, gap, cd,
                        headSep, f8Sep, dist(d.nd[d.pA], d.nd[d.pB]) * 1e3, openAng, Double.isNaN(axOff) ? "" : String.format(Locale.US, "%.3f", axOff),
                        Double.isNaN(siteSepNm) ? "" : String.format(Locale.US, "%.3f", siteSepNm),
                        Double.isNaN(r.lastSignedOffsetNm) ? "" : String.format(Locale.US, "%.3f", r.lastSignedOffsetNm), r.lastDir,
                        r.occupancyRejects, r.occupancyRejectThisFrame)
                        + String.format(Locale.US, ",%s,%s,%s,%s,%s",
                        firstBoundHead < 0 ? "" : String.valueOf(firstBoundHead), (bA >= 0) ^ (bB >= 0) ? String.valueOf(partnerStroked) : "",
                        Double.isNaN(freeSigned) ? "" : String.format(Locale.US, "%.3f", freeSigned),
                        Double.isNaN(tSinceBind) ? "" : String.format(Locale.US, "%.4f", tSinceBind),
                        Double.isNaN(tSinceStroke) ? "" : String.format(Locale.US, "%.4f", tSinceStroke)));
                if (fo != null) fo.write(sc, frame, t * DT, fA, fB);
                frame++;
            }
            r.lastFA = fA; r.lastFB = fB;
        }
        r.frames = frame;
        if (dwellRun > 0) dwell.add(dwellRun * DT * 1e3);
        // §1 separation statistics
        List<Double> all = new ArrayList<>(hsDet); all.addAll(hsA); all.addAll(hsB); all.addAll(hsBoth);
        r.overlapFrac = totN > 0 ? (double) overlapN / totN : 0; r.headSepMean = mean(all); r.headSepMed = median(all);
        r.f8SepMean = mean(f8All); r.openMean = mean(openAll);
        r.sepDet = mean(hsDet); List<Double> one = new ArrayList<>(hsA); one.addAll(hsB); r.sepOne = mean(one); r.sepBoth = mean(hsBoth);
        r.axAdpMean = mean(axAdp); r.axAdpMed = median(axAdp); r.axPiMean = mean(axPi);
        int off38 = 0; for (double v : axAdp) if (v >= 3 && v <= 8) off38++; r.fracOff38 = axAdp.isEmpty() ? 0 : (double) off38 / axAdp.size();
        r.dblFrames = hsBoth.size(); r.dblFrac = totN > 0 ? (double) hsBoth.size() / totN : 0; r.dwellMean = mean(dwell);
        // coupling = the partner head's tip measurably responded to the other's stroke through the shared S2 tail
        r.coupling = r.partnerInduced > 0.02;
        // event CSV
        Path csvp = Path.of(jsDir != null ? jsDir : "RUN_LOGS/explicit_hmm_dimer", "dimer_events.csv");
        Files.createDirectories(csvp.getParent()); Files.write(csvp, csv);
        r.csvFile = csvp.toAbsolutePath().toString();
        // §7 second-head binding-proposal log (only meaningful when the exclusion is engaged / partner-bound proposals occur)
        if (!r.proposalLog.isEmpty()) {
            List<String> pl = new ArrayList<>();
            pl.add("t_ms,proposingHead,partnerBound,candFil,partnerFil,candMat_um,partnerMat_um,signedOffset_nm,absOffset_nm,result,rejectReason,nucProposer,nucPartner,partnerPowerStroked");
            pl.addAll(r.proposalLog);
            Path pp = Path.of(jsDir != null ? jsDir : "RUN_LOGS/explicit_hmm_dimer", "dimer_bind_proposals.csv");
            Files.write(pp, pl);
        }
        if (fo != null) r.trajFile = fo.dir.toAbsolutePath().toString();
        System.out.printf(Locale.US, "  frames=%d  bindA=%b bindB=%b twoBound=%b stroke=%b detach=%b  peakF A/B=%.2f/%.2f pN  maxGap=%.3f nm  invalid=%d%n",
                frame, r.bindA, r.bindB, r.twoBound, r.stroke, r.detach, r.peakFA, r.peakFB, r.maxGap, r.invalid);
        if (r.firstBindStep >= 0) System.out.printf(Locale.US, "  firstBind @ %.3f ms, firstStroke @ %s ms%n", r.firstBindStep * DT * 1e3, r.firstStrokeStep >= 0 ? String.format("%.3f", r.firstStrokeStep * DT * 1e3) : "—");
        System.out.printf(Locale.US, "  SEP headMean %.1f/med %.1f nm, F8 %.1f nm, overlap<9nm %.0f%%, open %.1f°; byState det %.1f/1b %.1f/2b %.1f nm; 2-head ADP axOff %.1f/med %.1f nm (frac3-8 %.0f%%) dblFrac %.2f dwell %.3fms%n",
                r.headSepMean, r.headSepMed, r.f8SepMean, r.overlapFrac * 100, r.openMean, r.sepDet, r.sepOne, r.sepBoth, r.axAdpMean, r.axAdpMed, r.fracOff38 * 100, r.dblFrac, r.dwellMean);
        System.out.printf(Locale.US, "  EXCL %s: partnerProps %d, occupancyRejects %d, fwd %d, bwd %d, minSiteSep %s nm, nearSiteDoubleBind %d (must be 0)%n",
                sc.exclusionNm > 0 ? String.format(Locale.US, "%.1fnm", sc.exclusionNm) : "OFF", r.partnerBoundProposals, r.occupancyRejects, r.fwdBinds, r.bwdBinds,
                Double.isNaN(r.minSiteSepNm) ? "n/a" : String.format(Locale.US, "%.2f", r.minSiteSepNm), r.nearSiteDoubleBind);
        return r;
    }

    // ============================ seed / placement scan ============================
    static void scan(double splay, double gapNm, int nSeg, int steps) throws IOException {
        System.out.println("=== HMM DIMER dynamic assay — seed/placement scan ===");
        System.out.printf(Locale.US, "splay=%.0f° nSeg=%d steps=%d ; sweeping seeds × gap%n", splay, nSeg, steps);
        double[] gaps = { 2.0, 4.0, 6.0 };
        int bestSeed = -1; double bestGap = gapNm; int bestScore = -1;
        for (double g : gaps) {
            for (int seed = 1; seed <= 8; seed++) {
                Scene sc = buildScene(splay, g, nSeg, DT, seed);
                Run r = run(sc, seed, steps, 1_000_000, null);   // no frame export in scan
                int score = (r.bindA ? 1 : 0) + (r.bindB ? 1 : 0) + (r.twoBound ? 2 : 0) + (r.stroke ? 2 : 0) + (r.detach ? 1 : 0) + (r.detachedSearch ? 1 : 0) - r.invalid * 10;
                System.out.printf(Locale.US, "  gap=%.1f seed=%d → score=%d [srch=%b bindA=%b bindB=%b 2b=%b stroke=%b detach=%b inv=%d peakF=%.1f/%.1f]%n",
                        g, seed, score, r.detachedSearch, r.bindA, r.bindB, r.twoBound, r.stroke, r.detach, r.invalid, r.peakFA, r.peakFB);
                if (score > bestScore) { bestScore = score; bestSeed = seed; bestGap = g; }
            }
        }
        System.out.printf(Locale.US, "%n>>> BEST: seed=%d gap=%.1f (score=%d). Export with:%n    ./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer -seed %d -gap %.1f -splay %.0f -nseg %d -steps %d%n",
                bestSeed, bestGap, bestScore, bestSeed, bestGap, splay, nSeg, steps);
    }

    // ============================ viewer frame writer ============================
    static final class FrameOut {
        Path dir; int n = 0;
        FrameOut(String d) throws IOException { dir = Path.of(d); Files.createDirectories(dir); }
        void write(Scene sc, int frame, double t, double fA, double fB) throws IOException {
            Glide2D G = sc.G; Dimer d = sc.d; FilamentStore f = G.fil; StringBuilder s = new StringBuilder();
            s.append(String.format(Locale.US, "{\"frame\":%d,\"t\":%.6f,\"bounds\":{\"xDim\":4,\"yDim\":4,\"zDim\":4},", frame, t));
            // --- actin segments ---
            s.append("\"segments\":[");
            for (int k = 0; k < G.nSeg; k++) {
                double half = 0.5 * f.segLength.get(k);
                double[] c = { f.coordX(k), f.coordY(k), f.coordZ(k) }, u = { f.uVecX(k), f.uVecY(k), f.uVecZ(k) };
                double[] e1 = sub(c, scl(u, half)), e2 = add(c, scl(u, half));
                if (k > 0) s.append(',');
                s.append(String.format(Locale.US, "{\"id\":%d,\"end1\":[%.5f,%.5f,%.5f],\"end2\":[%.5f,%.5f,%.5f],\"r\":%.5f,\"notADPRatio\":1.0,\"cofilinCount\":0,\"isBarbedEnd\":%b}",
                        k, e1[0], e1[1], e1[2], e2[0], e2[1], e2[2], FIL_R, f.end2NbrSlot.get(k) < 0));
            }
            // --- shared S2 beam + branches as motorSeg segments (host coords straight from the dimer nodes) ---
            int sid = G.nSeg;
            sid = beamSeg(s, sid, d, 0, d.Ms, 0.2);                          // shared S2: emergence(0)→fork(Ms), neutral (col 0.2)
            sid = beamSeg(s, sid, d, d.Ms, d.pA, 0.0);                        // branch A: fork→pivotA (col 0.0 = red-ish)
            sid = beamSeg(s, sid, d, d.Ms, d.pB, 1.0);                        // branch B: fork→pivotB (col 1.0 = yellow-ish)
            // --- F8 crossbridge bond lines when bound ---
            int ST = CrossBridgeSystem.STRIDE;
            sid = bondSeg(s, sid, d.hA, G, 0);
            sid = bondSeg(s, sid, d.hB, G, 1);
            s.append("],");
            // --- anchor as a node sphere (kept: the common emergence marker) + OPTIONAL fork-node debug sphere ---
            // The FORK is drawn by default ONLY as the junction where the shared-S2 cylinder meets the two branch
            // cylinders (a distinct visible vertex) — the translucent grey fork SPHERE (a viewer `nodes` entry) sat
            // right in the converter/pivot region and obscured the head/lever geometry, so it is DEFAULT-OFF. It is
            // restored with -forknode for debugging. The shared viewer (sim_viewer_boa.html, used by BoA for real
            // protein nodes) is NOT modified — the change is here, in what the harness emits.
            s.append("\"nodes\":[");
            if (SHOW_FORK_SPHERE) s.append(String.format(Locale.US, "{\"center\":[%.5f,%.5f,%.5f],\"r\":0.006},", d.nd[d.Ms][0], d.nd[d.Ms][1], d.nd[d.Ms][2]));
            s.append(String.format(Locale.US, "{\"center\":[%.5f,%.5f,%.5f],\"r\":0.009}],", d.E[0], d.E[1], d.E[2]));
            // --- two myosins entries (head A / head B), colored by nucleotide state ---
            s.append("\"myosins\":[");
            myoEntry(s, d.hA, d, d.pA, G, 0, true);
            s.append(',');
            myoEntry(s, d.hB, d, d.pB, G, 1, false);
            s.append("]}");
            Files.writeString(dir.resolve(String.format("frame_%06d.json", n++)), s.toString());
        }
    }
    static int beamSeg(StringBuilder s, int id, Dimer d, int a, int bnode, double col) {
        double[] p = d.nd[a], q = d.nd[bnode];
        s.append(String.format(Locale.US, ",{\"id\":%d,\"end1\":[%.5f,%.5f,%.5f],\"end2\":[%.5f,%.5f,%.5f],\"r\":0.0022,\"notADPRatio\":%.2f,\"cofilinCount\":0,\"motorSeg\":true}",
                id, p[0], p[1], p[2], q[0], q[1], q[2], col));
        return id + 1;
    }
    static int bondSeg(StringBuilder s, int id, Cmot h, Glide2D G, int m) {
        if (G.mot.boundSeg.get(m) < 0) return id;
        FilamentStore f = G.fil; int seg = G.mot.boundSeg.get(m); double arc = G.mot.bindArc.get(m);
        double half = 0.5 * f.segLength.get(seg);
        double[] c = { f.coordX(seg), f.coordY(seg), f.coordZ(seg) }, u = { f.uVecX(seg), f.uVecY(seg), f.uVecZ(seg) };
        double[] site = add(sub(c, scl(u, half)), scl(u, arc));   // e1 + arc·u = the material attachment point
        s.append(String.format(Locale.US, ",{\"id\":%d,\"end1\":[%.5f,%.5f,%.5f],\"end2\":[%.5f,%.5f,%.5f],\"r\":0.0015,\"notADPRatio\":0.9,\"cofilinCount\":0,\"motorSeg\":true}",
                id, h.xF8[0], h.xF8[1], h.xF8[2], site[0], site[1], site[2]));
        return id + 1;
    }
    /** One myosins entry: lever = converter arm (pivot P→C→head reference), motor = head ellipsoid colored by
     *  nucleotide state. The myosin ROD is emitted INVISIBLE (§14 cleanup): the proximal branch (pivot→fork) is
     *  ALREADY drawn once as the tinted beam `segments` branch — the myosin rod was a coincident duplicate cylinder.
     *  Keeping the beam segment (with its A/B age-ramp tint + continuity into the shared S2) and hiding the rod
     *  removes the overlap without losing any geometry. */
    static void myoEntry(StringBuilder s, Cmot h, Dimer d, int pivot, Glide2D G, int m, boolean first) {
        double[] P = d.nd[pivot], F = d.nd[d.Ms], C = h.C, head = h.xH, tip = h.xF8;
        String state = G.mot.boundSeg.get(m) >= 0 ? NUC[G.mot.nucleotideState.get(m)] : "NONE";
        boolean bound = G.mot.boundSeg.get(m) >= 0;
        s.append(String.format(Locale.US,
                "{\"id\":%d,\"bound\":%b,\"rod\":{\"end1\":[%.5f,%.5f,%.5f],\"end2\":[%.5f,%.5f,%.5f],\"r\":0.0016,\"invisible\":true},"
              + "\"lever\":{\"end1\":[%.5f,%.5f,%.5f],\"end2\":[%.5f,%.5f,%.5f],\"r\":0.0022},"
              + "\"motor\":{\"end1\":[%.5f,%.5f,%.5f],\"end2\":[%.5f,%.5f,%.5f],\"r\":0.0045,\"state\":\"%s\"}}",
                m, bound, P[0], P[1], P[2], F[0], F[1], F[2],
                P[0], P[1], P[2], C[0], C[1], C[2],
                head[0], head[1], head[2], tip[0], tip[1], tip[2], state));
    }

    // ---- helpers ----
    static final String[] NUC = { "NONE", "ATP", "ADPPi", "ADP" };
    /** Deterministic hash → [0,1) for the C4 graded-penalty draw (reproducible across a run). */
    static double hash01(long x) { x ^= (x >>> 33); x *= 0xff51afd7ed558ccdL; x ^= (x >>> 33); x *= 0xc4ceb9fe1a85ec53L; x ^= (x >>> 33);
        return (x >>> 11) * (1.0 / (1L << 53)); }
    static double fmag(Glide2D G, int m, int ST) { double fx = G.bondData.get(m * ST), fy = G.bondData.get(m * ST + 1), fz = G.bondData.get(m * ST + 2);
        return G.mot.boundSeg.get(m) >= 0 ? Math.sqrt(fx * fx + fy * fy + fz * fz) * 1e12 : 0.0; }
    static boolean bad(Dimer d) { for (double[] nd : d.nd) for (double v : nd) if (Double.isNaN(v) || Double.isInfinite(v)) return true;
        return Double.isNaN(d.hA.phi) || Double.isNaN(d.hB.phi); }
    static void translate(Dimer d, double[] sh) { for (int j = 0; j <= d.NF; j++) d.nd[j] = add(d.nd[j], sh);
        d.E = add(d.E, sh); d.floorZ += sh[2];
        ExplicitHmmDimer.pinHead(d.hA, d.nd[d.pA]); ExplicitHmmDimer.pinHead(d.hB, d.nd[d.pB]); }
    static double dist(double[] a, double[] b) { double[] e = sub(a, b); return Math.sqrt(dot(e, e)); }
    static double axial(double[] x, double[] x0, double[] axis) { return dot(sub(x, x0), axis) * 1e3; }   // nm along the filament axis
    /** Axial (along-filament +x) separation of the two heads' actin material attachment points, nm (both bound). */
    static double twoHeadAxialOffset(Glide2D G) { double[] aA = attach(G, 0), aB = attach(G, 1); return Math.abs(aA[0] - aB[0]) * 1e3; }
    static double[] attach(Glide2D G, int m) { FilamentStore f = G.fil; int seg = G.mot.boundSeg.get(m); double arc = G.mot.bindArc.get(m);
        double half = 0.5 * f.segLength.get(seg); double[] c = { f.coordX(seg), f.coordY(seg), f.coordZ(seg) }, u = { f.uVecX(seg), f.uVecY(seg), f.uVecZ(seg) };
        return add(sub(c, scl(u, half)), scl(u, arc)); }
    static double mean(List<Double> v) { if (v.isEmpty()) return Double.NaN; double s = 0; for (double x : v) s += x; return s / v.size(); }
    static double median(List<Double> v) { if (v.isEmpty()) return Double.NaN; List<Double> s = new ArrayList<>(v); java.util.Collections.sort(s); return s.get(s.size() / 2); }
    static double[] flat(Dimer d) { double[] fv = new double[3 * (d.NF + 1) + 4]; int i = 0;
        for (int j = 0; j <= d.NF; j++) for (int k = 0; k < 3; k++) fv[i++] = d.nd[j][k];
        fv[i++] = d.hA.phi; fv[i++] = d.hA.psi; fv[i++] = d.hB.phi; fv[i++] = d.hB.psi; return fv; }
    static double maxMove(double[] prev, Dimer d) { double[] c = flat(d); double mx = 0; for (int i = 0; i < c.length; i++) mx = Math.max(mx, Math.abs(c[i] - prev[i])); return mx; }
    static boolean has(String[] a, String f) { for (String s : a) if (s.equals(f)) return true; return false; }
    static double argD(String[] a, String f, double dv) { for (int i = 0; i < a.length - 1; i++) if (a[i].equals(f)) return Double.parseDouble(a[i + 1]); return dv; }
    static String argStr(String[] a, String f, String dv) { for (int i = 0; i < a.length - 1; i++) if (a[i].equals(f)) return a[i + 1]; return dv; }
    static String yn(boolean b) { return b ? "YES" : "NO"; }
    private ExplicitHmmDimer3jsHarness() {}
}
