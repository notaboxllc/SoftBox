package softbox;

import static softbox.TwoBodyConverterMotor.add;
import static softbox.TwoBodyConverterMotor.sub;
import static softbox.TwoBodyConverterMotor.scl;
import static softbox.TwoBodyConverterMotor.dot;

import softbox.ExplicitHmmDimer.Dimer;
import softbox.TwoBodyConverterMotor.Cmot;
import softbox.TwoBodyConverterMotor.Glide2D;
import softbox.TwoBodyConverterMotor.Tol;
import softbox.ExplicitHmmDimer3jsHarness.Scene;   // reused helper class only for static utilities

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * =============== EXPLICIT-HMM-DIMER ENSEMBLE GLIDING ASSAYS (this task) ===============
 * Two mechanically-reciprocal gliding assays built from an ENSEMBLE of explicit HMM dimers
 * ({@link ExplicitHmmDimer}), each dimer running the exact PRODUCTION per-head machinery already
 * validated in {@link ExplicitHmmDimer3jsHarness#step} (search → canonical half-open bind gate →
 * cycleLymnTaylor chemistry → power stroke → bondForces F8 → CSR gather → coupled forked-tail solve),
 * generalized from ONE dimer to nDim dimers sharing ONE actin filament.
 *
 *   Assay A — MOBILE actin over nDim anchored dimers (the classic gliding assay: filament free to
 *             translate/rotate; each dimer's emergence is pinned to the substrate).
 *   Assay B — FIXED actin, MOBILE common assembly: the nDim dimer emergences ride ONE shared axial
 *             slider (a scaffold/minifilament stand-in) that translates under the net cross-bridge
 *             reaction with drag = the actin's own total axial drag (exact reciprocal mobility).
 *
 * Directional modes (the 5.4 nm same-filament bound-site occupancy exclusion is ON in EVERY mode):
 *   D0  unbiased            — forward & backward eligible second-head binds both allowed (no rule)
 *   D1  graded rearward     — a backward eligible second-head bind is accepted with probability γ
 *   D2  hard rearward veto   — a backward eligible second-head bind is rejected outright
 * The "second head" is the dimer's OWN partner head; the mode acts ONLY on the second head of a dimer
 * whose first head is already bound (exactly the D0/D1/D2 definition; first-head binding is untouched).
 *
 * NO new conformational mechanism is added (the isolated-dimer survey found none steers the free head;
 * dirMech stays 0). The scientific question is whether ENSEMBLE motion supplies the directional bias.
 * Velocity is the LS slope of the mobile component's axial centroid vs time (never longWindowSpeedXY).
 * CPU-only. All shared systems / TwoBodyConverterMotor / MotorModel / shared-S2 material unchanged.
 * =====================================================================================================
 */
public final class ExplicitHmmDimerGlidingHarness {

    static double DT = 2.5e-6;   // global timestep (settable via -dt for the mat timestep-sensitivity study; default 2.5e-6)
    static final double PRE  = TwoBodyConverterMotor.PRESTROKE_THETAS;
    static final double FIL_R = Constants.radius;
    static final int SETTLE = 800;
    static final double TOL_MOVE = 3e-7;
    static final double EXCL = ExplicitHmmDimer3jsHarness.DEFAULT_EXCLUSION_NM;    // 5.4 nm
    static final double EXCL_TOL_NM = 1e-3;
    // fork geometry inherited from the fork-relaxation studies (the reference dimer)
    static final double ALPHA = 10, BREI = 0.25, BREA = 1.0, BRANCHLEN = 10, SPLAY = 16;
    // ---- dimer-only compliance multipliers (this study), RELATIVE to the reference dimer above ----
    //  -branchEA m ⇒ branch axial/stretch stiffness × m (reference = BREA·420 pN/nm)
    //  -branchEI m ⇒ branch bending stiffness × m (reference = BREI·EI = 0.25·EI, i.e. m relative to the CURRENT 0.25)
    //  -forkK   m ⇒ the two fork-root angular-coupling hinges × m (on top of branchEI; new selectable term)
    // All default 1.0 ⇒ the build call is byte-identical to the reference dimer.
    static double CFG_EA = 1.0, CFG_EI = 1.0, CFG_FORK = 1.0;
    static Dimer buildRefDimer() {
        return ExplicitHmmDimer.build(3, 1, 1, SPLAY, DT, ALPHA, BREI * CFG_EI, BREA * CFG_EA, BRANCHLEN, CFG_FORK);
    }
    static final double SEGLEN = (64 + 1) * Constants.actinMonoRadius;             // ≈0.176 µm per actin segment
    static final String[] NUC = { "NONE", "ATP", "ADPPi", "ADP" };

    // ============================ scene ============================
    static final class EScene {
        Glide2D G; Dimer[] dim; Tol tol; int nDim, nSeg;
        int[] pivOf; Cmot[] headOf;                 // per-motor pivot node index + Cmot head
        double[] emg0X;                             // per-dimer emergence x at build (Assay B slider origin)
        double exclusionNm = EXCL;
        boolean rearVeto = false; double rearPenalty = 1.0;
        int assay = 0;                              // 0 = A mobile actin ; 1 = B mobile assembly
        double asmX = 0, asmGamma = 0;              // Assay B: shared axial slider displacement (µm) + total axial drag
        double loadPn = 0;                          // external opposing axial load on the mobile component (pN, +opposes forward)
        double prescribedV = Double.NaN;            // E3: kinematic actin axial velocity (µm/s); NaN = free/dynamic
        double bhatX = 1;                           // barbed = +x (filament polarity); -1 flips (polarity fixture)
        double gapNm, spacingNm; double filBrown = 1.0;
        // ---- 2D double-head MAT mode (mirror the single-head buildS2Mat: 3×1 µm lawn, one gliding filament) ----
        boolean matMode = false; double cullR = 0.03; boolean[] dimActive; double densityUm2 = 0;
        double[][] dimerBhat, dimerEconv;   // per-dimer base frame (random azimuth about vertical) for the mat bind path
    }

    // ============================ build ============================
    static EScene build(int nDim, double spacingNm, double gapNm, int seed, double filBrown, int assay) {
        EScene sc = new EScene();
        sc.nDim = nDim; sc.assay = assay; sc.gapNm = gapNm; sc.spacingNm = spacingNm; sc.filBrown = filBrown;
        double spanUm = (nDim - 1) * spacingNm * 1e-3;
        double filLenUm = spanUm + 0.8;                                            // 0.4 µm margin each side
        int nSeg = Math.max(6, (int) Math.ceil(filLenUm / SEGLEN));
        sc.nSeg = nSeg;
        double zTarget = -(FIL_R + gapNm * 1e-3);

        Glide2D G = new Glide2D();
        G.dt = DT; G.rigid = false; G.filBrown = true; G.density = 0;
        G.kzCode = 2.0 * TwoBodyConverterMotor.PNNM;
        G.matXlo = -50; G.matXhi = 50; G.matYlo = -5; G.matYhi = 5;
        Cmot ref = TwoBodyConverterMotor.build3core(DT, 1.0, 128, 512, 0, 0, TwoBodyConverterMotor.IDENT, false,
                TwoBodyConverterMotor.LB_3C, TwoBodyConverterMotor.PHI_PRE_3E, TwoBodyConverterMotor.R_F8, TwoBodyConverterMotor.R_CONV, 0, 0);
        G.kF8Code = ref.kF8Code; G.kconvCode = ref.kconvCode; G.kbindCode = ref.kbindCode; G.lb = ref.lb;
        G.bhat = ref.bhat; G.phat = ref.phat; G.eup = ref.eup; G.econv = ref.econv; G.uvecPhys = ref.uvecPhys;
        G.rF8 = ref.rF8.clone(); G.rConv = ref.rConv.clone(); G.gammaPhi = ref.gammaPhi; G.gammaPsi = ref.gammaPsi;
        G.xbParams = ref.xbParams;

        // filament centered at x=0, along +x
        FilamentStore f = new FilamentStore(nSeg); double x0 = -0.5 * (nSeg - 1) * SEGLEN;
        for (int k = 0; k < nSeg; k++) {
            f.monomerCount.set(k, 64); f.setUVec(k, 1f, 0f, 0f); f.setYVec(k, 0f, 1f, 0f);
            f.setCoord(k, (float) (x0 + k * SEGLEN), 0f, 0f);
            f.brownTransScale.set(k, (float) (Constants.BTransCoeff * filBrown));
            boolean interior = (k > 0 && k < nSeg - 1);
            f.brownRotScale.set(k, interior ? 0f : (float) (Constants.BRotCoeff * filBrown));
            if (k < nSeg - 1) { f.end2NbrSlot.set(k, k + 1); f.end2NbrSide.set(k, 0); }
            if (k > 0) { f.end1NbrSlot.set(k, k - 1); f.end1NbrSide.set(k, 1); }
        }
        DragTensorSystem.run(f); f.setParams(DT, Constants.brownianForceMag(DT)); f.setChainParams(DT);
        f.chainParams.set(0, (float) DT); f.setCounts(0, seed);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        G.nSeg = nSeg; G.fil = f;
        // total axial drag (Assay B reciprocal mobility)
        double gAx = 0; for (int s = 0; s < nSeg; s++) gAx += f.bTransGam.get(s); sc.asmGamma = gAx;

        int N = 2 * nDim; G.N = N; G.mot = new MotorStore(N);
        G.phi = new double[N]; G.psi = new double[N]; G.thetaS = new double[N]; G.psiActin = new double[N];
        G.A = new double[N][]; G.C_ = new double[N][]; G.xH_ = new double[N][]; G.xF8_ = new double[N][];
        G.noBind = new boolean[N]; G.active = new boolean[N]; G.siteX = new double[N]; G.siteY = new double[N];
        for (int m = 0; m < N; m++) G.mot.assembleArticulated(m, 0f, 0f, (float) LaserTrapHarness.MANCHOR_Z, 0f, 0f, 1f, 0f);
        DragTensorSystem.run(G.mot); G.mot.setBodyParams(DT); G.mot.setKinParams(0.006, -0.4, DT); G.mot.setNucParams(DT);
        G.bondData = new FloatArray(N * CrossBridgeSystem.STRIDE); G.bondData.init(0f);
        G.segCount = new IntArray(nSeg); G.segOff = new IntArray(nSeg + 1); G.segMyo = new IntArray(N);
        G.cullMode = 2;

        sc.dim = new Dimer[nDim]; sc.pivOf = new int[N]; sc.headOf = new Cmot[N]; sc.emg0X = new double[nDim];
        double xC = -0.5 * spanUm;
        for (int i = 0; i < nDim; i++) {
            Dimer d = buildRefDimer();
            d.hA.thetaS = PRE; d.hB.thetaS = PRE;
            for (int it = 0; it < SETTLE; it++) { double[] snap = flat(d);
                ExplicitHmmDimer.solve(d, it, seed + i * 101, false, new double[3], new double[3]);
                if (maxMove(snap, d) < TOL_MOVE) break; }
            double mtx = 0.5 * (d.hA.xF8[0] + d.hB.xF8[0]), mtz = 0.5 * (d.hA.xF8[2] + d.hB.xF8[2]);
            double xi = xC + i * spacingNm * 1e-3;
            translate(d, new double[]{ xi - mtx, 0, zTarget - mtz });
            sc.dim[i] = d; sc.emg0X[i] = d.E[0];
            int mA = 2 * i, mB = 2 * i + 1;
            sc.headOf[mA] = d.hA; sc.headOf[mB] = d.hB; sc.pivOf[mA] = d.pA; sc.pivOf[mB] = d.pB;
            for (int mm : new int[]{ mA, mB }) {
                Cmot h = sc.headOf[mm];
                G.mot.boundSeg.set(mm, MotorStore.FREE_BINDABLE); G.mot.nucleotideState.set(mm, MotorStore.NUC_ADPPI);
                G.A[mm] = d.nd[sc.pivOf[mm]].clone(); G.phi[mm] = h.phi; G.psi[mm] = h.psi;
                G.psiActin[mm] = h.psiActin; G.thetaS[mm] = PRE; G.noBind[mm] = false;
            }
        }
        for (int m = 0; m < N; m++) TwoBodyConverterMotor.geom2D(G, m);
        sc.G = G; sc.tol = new Tol();
        return sc;
    }

    /**
     * Build the DOUBLE-HEAD MAT (mirror single-head buildS2Mat): a {@code G4_MATX}×{@code G4_MATY} µm lawn of HMM
     * dimers (density in DIMERS/µm² ⇒ nDim = round(density·area); heads = 2·nDim) under ONE free gliding 2.1 µm
     * filament centered on the lawn. Each dimer is placed at a random lawn (x,y) with its head-pair reaching the
     * z=0 filament plane at the requested gap. Only dimers whose heads are near the filament run the (expensive)
     * coupled forked-tail solve + bind gate each step (active cull) — the rest are frozen (far from the filament,
     * zero contribution), the standard mat broad-phase economy. Assay A (mobile actin) semantics.
     */
    static EScene buildMat(double density, double gapNm, int seed, double filBrown) { return buildMat(density, gapNm, seed, filBrown, 0.12, 2.0); }
    static EScene buildMat(double density, double gapNm, int seed, double filBrown, double cullR, double filLenUm) {
        double MX = TwoBodyConverterMotor.G4_MATX, MY = TwoBodyConverterMotor.G4_MATY;
        int nDim = Math.max(1, (int) Math.round(density * MX * MY));
        EScene sc = new EScene();
        sc.nDim = nDim; sc.assay = 0; sc.matMode = true; sc.densityUm2 = density; sc.gapNm = gapNm; sc.filBrown = filBrown;
        sc.cullR = cullR;   // head within cullR (perp) of the filament axis ⇒ active (searches + can bind)
        int nSeg = Math.max(4, (int) Math.round(filLenUm / SEGLEN)); sc.nSeg = nSeg;   // ≈ filLenUm µm filament
        double zTarget = -(FIL_R + gapNm * 1e-3);

        Glide2D G = new Glide2D();
        G.dt = DT; G.rigid = false; G.filBrown = true; G.density = density;
        G.kzCode = 2.0 * TwoBodyConverterMotor.PNNM;
        G.matXlo = -0.5 * MX; G.matXhi = 0.5 * MX; G.matYlo = -0.5 * MY; G.matYhi = 0.5 * MY;
        Cmot ref = TwoBodyConverterMotor.build3core(DT, 1.0, 128, 512, 0, 0, TwoBodyConverterMotor.IDENT, false,
                TwoBodyConverterMotor.LB_3C, TwoBodyConverterMotor.PHI_PRE_3E, TwoBodyConverterMotor.R_F8, TwoBodyConverterMotor.R_CONV, 0, 0);
        G.kF8Code = ref.kF8Code; G.kconvCode = ref.kconvCode; G.kbindCode = ref.kbindCode; G.lb = ref.lb;
        G.bhat = ref.bhat; G.phat = ref.phat; G.eup = ref.eup; G.econv = ref.econv; G.uvecPhys = ref.uvecPhys;
        G.rF8 = ref.rF8.clone(); G.rConv = ref.rConv.clone(); G.gammaPhi = ref.gammaPhi; G.gammaPsi = ref.gammaPsi; G.xbParams = ref.xbParams;

        FilamentStore f = new FilamentStore(nSeg); double x0 = -0.5 * (nSeg - 1) * SEGLEN;
        for (int k = 0; k < nSeg; k++) { f.monomerCount.set(k, 64); f.setUVec(k, 1f, 0f, 0f); f.setYVec(k, 0f, 1f, 0f);
            f.setCoord(k, (float) (x0 + k * SEGLEN), 0f, 0f);
            f.brownTransScale.set(k, (float) (Constants.BTransCoeff * filBrown));
            boolean interior = (k > 0 && k < nSeg - 1); f.brownRotScale.set(k, interior ? 0f : (float) (Constants.BRotCoeff * filBrown));
            if (k < nSeg - 1) { f.end2NbrSlot.set(k, k + 1); f.end2NbrSide.set(k, 0); }
            if (k > 0) { f.end1NbrSlot.set(k, k - 1); f.end1NbrSide.set(k, 1); } }
        DragTensorSystem.run(f); f.setParams(DT, Constants.brownianForceMag(DT)); f.setChainParams(DT); f.chainParams.set(0, (float) DT); f.setCounts(0, seed);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        G.nSeg = nSeg; G.fil = f;
        double gAx = 0; for (int s = 0; s < nSeg; s++) gAx += f.bTransGam.get(s); sc.asmGamma = gAx;

        int N = 2 * nDim; G.N = N; G.mot = new MotorStore(N);
        G.phi = new double[N]; G.psi = new double[N]; G.thetaS = new double[N]; G.psiActin = new double[N];
        G.A = new double[N][]; G.C_ = new double[N][]; G.xH_ = new double[N][]; G.xF8_ = new double[N][];
        G.noBind = new boolean[N]; G.active = new boolean[N]; G.siteX = new double[N]; G.siteY = new double[N];
        for (int m = 0; m < N; m++) G.mot.assembleArticulated(m, 0f, 0f, (float) LaserTrapHarness.MANCHOR_Z, 0f, 0f, 1f, 0f);
        DragTensorSystem.run(G.mot); G.mot.setBodyParams(DT); G.mot.setKinParams(0.006, -0.4, DT); G.mot.setNucParams(DT);
        G.bondData = new FloatArray(N * CrossBridgeSystem.STRIDE); G.bondData.init(0f);
        G.segCount = new IntArray(nSeg); G.segOff = new IntArray(nSeg + 1); G.segMyo = new IntArray(N);
        G.cullMode = 2;

        sc.dim = new Dimer[nDim]; sc.pivOf = new int[N]; sc.headOf = new Cmot[N]; sc.emg0X = new double[nDim]; sc.dimActive = new boolean[nDim];
        sc.dimerBhat = new double[nDim][]; sc.dimerEconv = new double[nDim][];
        for (int i = 0; i < nDim; i++) {
            double ax = G.matXlo + hashU(seed, i, 11) * (G.matXhi - G.matXlo);
            double ay = G.matYlo + hashU(seed, i, 12) * (G.matYhi - G.matYlo);
            Dimer d = buildRefDimer();
            d.hA.thetaS = PRE; d.hB.thetaS = PRE;
            // settle ONLY dimers in the engageable y-strip (near the filament); far dimers stay frozen at their
            // built pose (they never become active ⇒ their exact pose is irrelevant) — the mat build economy.
            if (Math.abs(ay) < 0.15)
                for (int it = 0; it < SETTLE; it++) { double[] snap = flat(d);
                    ExplicitHmmDimer.solve(d, it, seed + i * 101, false, new double[3], new double[3]); if (maxMove(snap, d) < TOL_MOVE) break; }
            // RANDOM azimuthal orientation about the vertical (anchored motors point every horizontal direction) —
            // rotates the whole dimer (beam + both head frames + emergence tangent) so the shared S2 / head splay is
            // randomly aimed in the xy-plane; the coupled solve uses the per-head frame, the bind path the stored frame.
            double az = hashU(seed, i, 21) * 2 * Math.PI;
            rotateDimerZ(d, az);
            double mtx = 0.5 * (d.hA.xF8[0] + d.hB.xF8[0]), mty = 0.5 * (d.hA.xF8[1] + d.hB.xF8[1]), mtz = 0.5 * (d.hA.xF8[2] + d.hB.xF8[2]);
            translate(d, new double[]{ ax - mtx, ay - mty, zTarget - mtz });
            sc.dim[i] = d; sc.emg0X[i] = d.E[0];
            sc.dimerBhat[i] = d.hA.bhat.clone(); sc.dimerEconv[i] = d.hA.econv.clone();
            int mA = 2 * i, mB = 2 * i + 1; sc.headOf[mA] = d.hA; sc.headOf[mB] = d.hB; sc.pivOf[mA] = d.pA; sc.pivOf[mB] = d.pB;
            for (int mm : new int[]{ mA, mB }) { Cmot h = sc.headOf[mm];
                G.mot.boundSeg.set(mm, MotorStore.FREE_BINDABLE); G.mot.nucleotideState.set(mm, MotorStore.NUC_ADPPI);
                G.A[mm] = d.nd[sc.pivOf[mm]].clone(); G.phi[mm] = h.phi; G.psi[mm] = h.psi; G.psiActin[mm] = h.psiActin; G.thetaS[mm] = PRE; G.noBind[mm] = false; }
        }
        for (int m = 0; m < N; m++) TwoBodyConverterMotor.geom2D(G, m);
        sc.G = G; sc.tol = new Tol();
        return sc;
    }
    /** Deterministic uniform [0,1) hash for lawn placement (seed, dimer index, salt). */
    static double hashU(int seed, int i, int salt) {
        long x = ((long) seed * 100003L + i) * 2654435761L ^ ((long) salt * 40503L);
        x ^= (x >>> 33); x *= 0xff51afd7ed558ccdL; x ^= (x >>> 33); x *= 0xc4ceb9fe1a85ec53L; x ^= (x >>> 33);
        return (x >>> 11) * (1.0 / (1L << 53));
    }
    /** Mat active cull: a dimer is active (runs bind + coupled solve) iff either head tip is within cullR
     *  PERPENDICULAR distance of a filament segment axis (foot clamped to the segment). This marks exactly the
     *  dimers whose anchored heads are within binding reach of the filament line, regardless of where along it;
     *  far dimers are frozen (zero contribution) — the standard mat broad-phase economy. */
    static void updateActive(EScene sc) {
        FilamentStore f = sc.G.fil; double cr2 = sc.cullR * sc.cullR;
        for (int i = 0; i < sc.nDim; i++) {
            boolean act = false;
            for (int hh = 0; hh < 2 && !act; hh++) { double[] tip = sc.headOf[2 * i + hh].xF8;
                for (int s = 0; s < sc.nSeg; s++) {
                    double half = 0.5 * f.segLength.get(s);
                    double dx = tip[0] - f.coordX(s), dy = tip[1] - f.coordY(s), dz = tip[2] - f.coordZ(s);
                    double ux = f.uVecX(s), uy = f.uVecY(s), uz = f.uVecZ(s);
                    double foot = dx * ux + dy * uy + dz * uz; double fc = Math.max(-half, Math.min(half, foot));
                    double px = dx - fc * ux, py = dy - fc * uy, pz = dz - fc * uz;
                    if (px * px + py * py + pz * pz < cr2) { act = true; break; } } }
            sc.dimActive[i] = act;
        }
    }

    // ============================ observables ============================
    static final class Obs {
        // trajectory samples (mobile-component axial coordinate vs time, µm / s)
        List<Double> ts = new ArrayList<>(), xs = new ArrayList<>();
        // aggregate
        long boundHeadSum = 0, boundDimSum = 0, oneHeadSum = 0, twoHeadSum = 0, steps = 0, stepsWithBound = 0, activeSum = 0;
        double netAxSum = 0, forcePerHeadSum = 0; long forcePerHeadN = 0;
        int fwdBinds = 0, bwdBinds = 0, fwdInside = 0, bwdInside = 0, occupancyRejects = 0, rearRejects = 0, partnerProps = 0;
        int invalid = 0, solverFail = 0, nearSiteDoubleBind = 0;
        double maxGap = 0, peakF = 0;
        // ---- gap-distribution + branch-force diagnostics (compliance study) ----
        List<Double> gapSamples = new ArrayList<>();   // per-step max joint gap over active dimers (nm)
        int exc4 = 0, exc10 = 0, exc50 = 0, exc100 = 0;   // per-dimer rising-edge excursion counts
        double peakBranchF = 0, maxBranchExt = 0, branchExtSum = 0; long branchExtN = 0;
        // >10 nm excursion trigger classification (per-dimer, same-step attribution)
        int excSecondBind = 0, excStroke = 0, excDetach = 0, excFilMotion = 0, excOther = 0;
        // double-bound dwell (per-dimer both-heads-bound runs, ms)
        List<Double> dblDwell = new ArrayList<>();
        // total attachment lifetime per head (ms)
        List<Double> attachLife = new ArrayList<>();
        // event-conditioned second-bind analysis
        List<Double> fwdSecondDwell = new ArrayList<>(), bwdSecondDwell = new ArrayList<>();
        List<Double> fwdSecondForce = new ArrayList<>(), bwdSecondForce = new ArrayList<>();
        List<Double> fwdVelAtBind = new ArrayList<>(), bwdVelAtBind = new ArrayList<>();
        // rotational drift
        double rotDriftDeg = 0;
        // motion-conditioned proposal bias (E3 + dynamic): counts of eligible fwd/bwd proposals split by motion sign
        long propFwd_moveFwd = 0, propBwd_moveFwd = 0, propFwd_moveBwd = 0, propBwd_moveBwd = 0, propFwd_still = 0, propBwd_still = 0;
    }

    // ============================ one step ============================
    static void step(EScene sc, int t, int seed, Obs o, double windowVelForward) {
        Glide2D G = sc.G; MotorStore mot = G.mot; FilamentStore f = G.fil; int N = G.N, nDim = sc.nDim;
        RigidRodBody b = mot.body;
        for (int m = 0; m < N; m++) { Cmot h = sc.headOf[m]; G.phi[m] = h.phi; G.psi[m] = h.psi;
            G.A[m] = sc.dim[m / 2].nd[sc.pivOf[m]].clone(); G.active[m] = true; }
        if (sc.matMode) updateActive(sc);   // broad-phase: mark near-filament dimers active (solve only those)

        // motion class for proposal-bias conditioning: +1 forward, -1 backward, 0 ~still (thresh 0.2 µm/s)
        int moveClass = Double.isNaN(windowVelForward) ? 0 : (windowVelForward > 0.2 ? 1 : windowVelForward < -0.2 ? -1 : 0);

        // 1. BIND gate per motor + per-dimer occupancy exclusion + D1/D2
        for (int m = 0; m < N; m++) if (!G.noBind[m] && mot.boundSeg.get(m) == MotorStore.FREE_BINDABLE && mot.nucleotideState.get(m) == MotorStore.NUC_ADPPI) {
            if (sc.matMode && !sc.dimActive[m / 2]) continue;   // far-from-filament dimer: skip bind
            if (sc.matMode) { G.bhat = sc.dimerBhat[m / 2]; G.econv = sc.dimerEconv[m / 2]; }   // this dimer's random azimuth
            G.thetaS[m] = PRE; TwoBodyConverterMotor.geom2D(G, m); int s = TwoBodyConverterMotor.nearestSeg2D(G, m); if (s < 0) continue;
            double[] gm = TwoBodyConverterMotor.gate2D(G, m, s); double half = 0.5 * f.segLength.get(s), margin = TwoBodyConverterMotor.bindMargin();
            boolean g0 = gm[0] < sc.tol.dBindNm, g1 = gm[2] < sc.tol.psiDeg, g2 = gm[3] < sc.tol.phiDeg, g3 = gm[4] < sc.tol.thetaDeg,
                    g4 = gm[5] < sc.tol.preloadPn, g5 = gm[6] < sc.tol.energyKt, g6 = gm[7] < TwoBodyConverterMotor.A_SEMI[2] * 1e3, g7 = gm[1] > margin && gm[1] < 2 * half - margin;
            if (!(g0 && g1 && g2 && g3 && g4 && g5 && g6 && g7)) continue;
            int p = m ^ 1;                                   // dimer-partner head
            boolean partnerBound = mot.boundSeg.get(p) >= 0;
            double candMat = ExplicitHmmDimer3jsHarness.filMatCoordUm(f, G.nSeg, s, gm[1]);
            if (partnerBound) {
                double partnerMat = ExplicitHmmDimer3jsHarness.filMatCoordUm(f, G.nSeg, mot.boundSeg.get(p), mot.bindArc.get(p));
                double signedNm = (candMat - partnerMat) * 1e3 * sc.bhatX;   // + toward barbed
                o.partnerProps++;
                // motion-conditioned proposal tally
                if (signedNm >= 0) { if (moveClass > 0) o.propFwd_moveFwd++; else if (moveClass < 0) o.propFwd_moveBwd++; else o.propFwd_still++; }
                else               { if (moveClass > 0) o.propBwd_moveFwd++; else if (moveClass < 0) o.propBwd_moveBwd++; else o.propBwd_still++; }
                boolean veto = Math.abs(signedNm) < sc.exclusionNm - EXCL_TOL_NM;
                if (veto) { o.occupancyRejects++; if (signedNm >= 0) o.fwdInside++; else o.bwdInside++; continue; }
                if (signedNm < 0) {
                    if (sc.rearVeto) { o.rearRejects++; continue; }
                    if (sc.rearPenalty < 1.0) { double u = ExplicitHmmDimer3jsHarness.hash01(((long) t * 2654435761L) ^ ((long) seed * 40503L) ^ (m * 2246822519L));
                        if (u >= sc.rearPenalty) { o.rearRejects++; continue; } }
                }
                if (signedNm >= 0) { o.fwdBinds++; tagSecondBind(sc, o, m, +1, t, windowVelForward); }
                else               { o.bwdBinds++; tagSecondBind(sc, o, m, -1, t, windowVelForward); }
            }
            mot.boundSeg.set(m, s); mot.bindArc.set(m, (float) gm[1]);
            if (bindStep[m] < 0) bindStep[m] = t;
        }
        // 2. chemistry + 3. thetaS
        mot.setCounts(t, seed, G.nSeg);
        NucleotideCycleSystem.cycleLymnTaylor(mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts);
        for (int m = 0; m < N; m++) G.thetaS[m] = TwoBodyConverterMotor.thetaS4a(mot.nucleotideState.get(m));
        // 4. place + bond forces
        for (int m = 0; m < N; m++) { if (sc.matMode) { G.bhat = sc.dimerBhat[m / 2]; G.econv = sc.dimerEconv[m / 2]; }
            TwoBodyConverterMotor.geom2D(G, m); TwoBodyConverterMotor.placeHead2D(G, m); }
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams);
        // 5. gather → chain → confine → filament dynamics
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, G.segCount);
        CrossBridgeSystem.csrScan(mot.counts, G.segCount, G.segOff);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, G.segOff, G.segCount, G.segMyo);
        CrossBridgeSystem.segGather(G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts);
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        // net axial cross-bridge force + per-head force stats (read the head-side F8 forces)
        int ST = CrossBridgeSystem.STRIDE; double netHeadX = 0;
        for (int m = 0; m < N; m++) if (mot.boundSeg.get(m) >= 0) {
            double fx = G.bondData.get(m * ST), fy = G.bondData.get(m * ST + 1), fz = G.bondData.get(m * ST + 2);
            netHeadX += fx; o.forcePerHeadSum += Math.sqrt(fx * fx + fy * fy + fz * fz) * 1e12; o.forcePerHeadN++;
            o.peakF = Math.max(o.peakF, Math.sqrt(fx * fx + fy * fy + fz * fz) * 1e12);
        }
        // net FORWARD (productive) axial force, signed so + = drives the mobile component in the direction it
        // productively moves. netHeadX = Σ head-side F8 x-force; during productive gliding netHeadX·bhat > 0 for
        // BOTH assays (Assay A: actin reaction −bhat while it glides −bhat ⇒ productive projection +netHeadX·bhat;
        // Assay B: assembly driven +bhat ⇒ +netHeadX·bhat).
        double netFwdForcePn = netHeadX * sc.bhatX * 1e12;
        o.netAxSum += netFwdForcePn;

        if (!Double.isNaN(sc.prescribedV)) {
            // E3: kinematic actin — rigid-translate at prescribedV (µm/s) along +x; no force integration, no filament Brownian
            double dx = sc.prescribedV * DT;
            for (int s = 0; s < G.nSeg; s++) f.setCoord(s, (float) (f.coordX(s) + dx), f.coordY(s), f.coordZ(s));
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        } else if (sc.assay == 0) {
            // Assay A: y/z confinement + optional opposing axial load + Brownian + integrate
            for (int s = 0; s < G.nSeg; s++) { int iz = 2 * G.nSeg + s, iy = G.nSeg + s;
                f.forceSum.set(iz, (float) (f.forceSum.get(iz) - G.kzCode * f.coordZ(s)));
                f.forceSum.set(iy, (float) (f.forceSum.get(iy) - G.kzCode * f.coordY(s))); }
            if (sc.loadPn != 0) { // opposing load: push actin toward +bhat (against the −bhat glide), spread over segments
                double perSeg = (sc.loadPn * 1e-12) * sc.bhatX / G.nSeg;
                for (int s = 0; s < G.nSeg; s++) f.forceSum.set(s, (float) (f.forceSum.get(s) + perSeg)); }
            f.counts.set(1, t); f.counts.set(2, seed);
            BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
            RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
            DerivedGeometrySystem.orthogonalizeY(f.uVec, f.yVec, f.counts);
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        }
        // else Assay B: filament held FIXED (no integration) — the assembly moves instead (below)

        // 6. per-dimer coupled forked-tail solve (Brownian ON for search)
        for (int i = 0; i < nDim; i++) {
            if (sc.matMode && !sc.dimActive[i]) continue;   // frozen far dimer: skip the expensive coupled solve
            Dimer d = sc.dim[i]; int mA = 2 * i, mB = 2 * i + 1;
            double[] f8A = { G.bondData.get(mA * ST), G.bondData.get(mA * ST + 1), G.bondData.get(mA * ST + 2) };
            double[] f8B = { G.bondData.get(mB * ST), G.bondData.get(mB * ST + 1), G.bondData.get(mB * ST + 2) };
            d.hA.thetaS = G.thetaS[mA]; d.hB.thetaS = G.thetaS[mB];
            d.dirMech = 0; d.dirAct = 0; d.boundHead = -1;    // NO conformational directional mechanism
            int st = ExplicitHmmDimer.solve(d, t, seed + i * 101, true, f8A, f8B);
            if (st != 0) o.solverFail++;
        }

        // Assay B: advance the shared assembly slider under the net cross-bridge reaction (+ optional load), translate all dimers
        if (sc.assay == 1 && Double.isNaN(sc.prescribedV)) {
            double asmForcePn = netHeadX * sc.bhatX * 1e12;         // reaction drives assembly toward +bhat when heads pull toward barbed
            if (sc.loadPn != 0) asmForcePn -= sc.loadPn;            // opposing load
            double vAsm = (asmForcePn * 1e-12) / sc.asmGamma * 1e6; // µm/s (1e6 = m→µm)
            double dx = vAsm * DT;
            sc.asmX += dx;
            for (int i = 0; i < nDim; i++) translate(sc.dim[i], new double[]{ dx, 0, 0 });
        }

        // 7. writeback per-head load for next step's catch-slip chemistry
        for (int m = 0; m < N; m++) { double fd = G.bondData.get(m * ST + 12);
            double fx = G.bondData.get(m * ST), fy = G.bondData.get(m * ST + 1), fz = G.bondData.get(m * ST + 2);
            mot.forceDotFil.set(m, mot.boundSeg.get(m) >= 0 ? (float) fd : 0f);
            mot.forceMag.set(m, mot.boundSeg.get(m) >= 0 ? (float) Math.sqrt(fx * fx + fy * fy + fz * fz) : 0f); }
    }

    // per-head bind bookkeeping (module-static, reset per run)
    static int[] bindStep, secondBindStep, secondBindDir;
    static double[] secondForceAccum; static int[] secondForceN;
    static int[] dblRunStart;   // per-dimer both-bound run start step

    static void tagSecondBind(EScene sc, Obs o, int m, int dir, int t, double vel) {
        secondBindStep[m] = t; secondBindDir[m] = dir; secondForceAccum[m] = 0; secondForceN[m] = 0;
        if (dir > 0) o.fwdVelAtBind.add(vel); else o.bwdVelAtBind.add(vel);
    }

    // ============================ run ============================
    static Obs run(EScene sc, int seed, int steps, int velStride, String jsDir, int frameStride) throws IOException {
        Obs o = new Obs(); Glide2D G = sc.G; MotorStore mot = G.mot; int N = G.N;
        bindStep = new int[N]; secondBindStep = new int[N]; secondBindDir = new int[N];
        secondForceAccum = new double[N]; secondForceN = new int[N]; dblRunStart = new int[sc.nDim];
        java.util.Arrays.fill(bindStep, -1); java.util.Arrays.fill(secondBindStep, -1); java.util.Arrays.fill(dblRunStart, -1);
        int ST = CrossBridgeSystem.STRIDE;
        double meanUx0 = meanUx(G);
        FrameOut fo = jsDir != null ? new FrameOut(jsDir) : null;
        double windowVelForward = Double.NaN;       // running short-window forward velocity for motion-conditioning
        double prevSampleX = mobileAxial(sc); double prevSampleT = 0;
        double[] prevDimGap = new double[sc.nDim];   // per-dimer prior gap (excursion rising-edge detection)

        for (int t = 0; t < steps; t++) {
            int[] wasBound = new int[N]; int[] wasNuc = new int[N];
            for (int m = 0; m < N; m++) { wasBound[m] = mot.boundSeg.get(m); wasNuc[m] = mot.nucleotideState.get(m); }
            step(sc, t, seed, o, windowVelForward);
            o.steps++;
            // bound-state census
            int bh = 0, bd = 0, one = 0, two = 0;
            for (int i = 0; i < sc.nDim; i++) { boolean a = mot.boundSeg.get(2 * i) >= 0, bb = mot.boundSeg.get(2 * i + 1) >= 0;
                if (a) bh++; if (bb) bh++; if (a || bb) bd++; if (a ^ bb) one++; if (a && bb) two++;
                // both-bound dwell + occupancy invariant
                if (a && bb) { if (dblRunStart[i] < 0) dblRunStart[i] = t;
                    double mA = ExplicitHmmDimer3jsHarness.filMatCoordUm(G.fil, G.nSeg, mot.boundSeg.get(2 * i), mot.bindArc.get(2 * i));
                    double mB = ExplicitHmmDimer3jsHarness.filMatCoordUm(G.fil, G.nSeg, mot.boundSeg.get(2 * i + 1), mot.bindArc.get(2 * i + 1));
                    if (Math.abs(mA - mB) * 1e3 < sc.exclusionNm - EXCL_TOL_NM) o.nearSiteDoubleBind++;
                } else if (dblRunStart[i] >= 0) { o.dblDwell.add((t - dblRunStart[i]) * DT * 1e3); dblRunStart[i] = -1; }
            }
            o.boundHeadSum += bh; o.boundDimSum += bd; o.oneHeadSum += one; o.twoHeadSum += two; if (bh > 0) o.stepsWithBound++;
            if (sc.matMode) { int na = 0; for (boolean a : sc.dimActive) if (a) na++; o.activeSum += na; }
            // accumulate per-bound-head axial force for second-bind events
            for (int m = 0; m < N; m++) if (mot.boundSeg.get(m) >= 0 && secondBindStep[m] >= 0) {
                secondForceAccum[m] += G.bondData.get(m * ST) * sc.bhatX * 1e12; secondForceN[m]++;   // + = productive axial
            }
            // detachment bookkeeping (attachment lifetime + second-bind dwell/force close-out)
            for (int m = 0; m < N; m++) {
                if (wasBound[m] >= 0 && mot.boundSeg.get(m) < 0) {
                    if (bindStep[m] >= 0) { o.attachLife.add((t - bindStep[m]) * DT * 1e3); bindStep[m] = -1; }
                    if (secondBindStep[m] >= 0) {
                        double dwell = (t - secondBindStep[m]) * DT * 1e3;
                        double fmean = secondForceN[m] > 0 ? secondForceAccum[m] / secondForceN[m] : 0;
                        if (secondBindDir[m] > 0) { o.fwdSecondDwell.add(dwell); o.fwdSecondForce.add(fmean); }
                        else { o.bwdSecondDwell.add(dwell); o.bwdSecondForce.add(fmean); }
                        secondBindStep[m] = -1;
                    }
                }
            }
            // ---- gap distribution + branch mechanics + per-dimer excursion-trigger classification ----
            double stepMaxGap = 0;
            for (int i = 0; i < sc.nDim; i++) {
                if (sc.matMode && !sc.dimActive[i]) continue;
                Dimer d = sc.dim[i];
                double g = ExplicitHmmDimer.maxJointGap(d); stepMaxGap = Math.max(stepMaxGap, g);
                o.peakBranchF = Math.max(o.peakBranchF, ExplicitHmmDimer.maxBranchAxialForcePn(d));
                double ext = ExplicitHmmDimer.maxBranchExtNm(d); o.maxBranchExt = Math.max(o.maxBranchExt, ext);
                o.branchExtSum += ext; o.branchExtN++;
                int mA = 2 * i, mB = 2 * i + 1; double pg = prevDimGap[i];
                if (g > 4   && pg <= 4)   o.exc4++;
                if (g > 50  && pg <= 50)  o.exc50++;
                if (g > 100 && pg <= 100) o.exc100++;
                if (g > 10  && pg <= 10) { o.exc10++;
                    boolean secondBind = false, detach = false, stroke = false;
                    for (int m : new int[]{ mA, mB }) { int p = m ^ 1;
                        boolean wb = wasBound[m] >= 0, nb = mot.boundSeg.get(m) >= 0;
                        if (!wb && nb && wasBound[p] >= 0) secondBind = true;                       // 2nd-head bind (partner already bound)
                        if (wb && !nb) detach = true;                                               // a head detached
                        if (nb && wasNuc[m] == MotorStore.NUC_ADPPI && mot.nucleotideState.get(m) == MotorStore.NUC_ADP) stroke = true; }  // power stroke
                    if (secondBind) o.excSecondBind++; else if (detach) o.excDetach++; else if (stroke) o.excStroke++; else o.excFilMotion++;
                }
                prevDimGap[i] = g;
            }
            o.gapSamples.add(stepMaxGap); o.maxGap = Math.max(o.maxGap, stepMaxGap);
            if (invalidState(sc)) o.invalid++;
            // trajectory sample + short-window velocity
            if (t % velStride == 0) {
                double x = mobileAxial(sc), tt = t * DT;
                o.ts.add(tt); o.xs.add(x);
                if (tt > prevSampleT) { double vworld = (x - prevSampleX) / (tt - prevSampleT);
                    windowVelForward = (sc.assay == 0 ? -1 : +1) * vworld * sc.bhatX; }
                prevSampleX = x; prevSampleT = tt;
            }
            if (fo != null && t % frameStride == 0) fo.write(sc, t * DT);
        }
        // close open double-bound runs + attachments
        for (int i = 0; i < sc.nDim; i++) if (dblRunStart[i] >= 0) o.dblDwell.add((steps - dblRunStart[i]) * DT * 1e3);
        for (int m = 0; m < N; m++) if (bindStep[m] >= 0) o.attachLife.add((steps - bindStep[m]) * DT * 1e3);
        o.rotDriftDeg = Math.toDegrees(Math.abs(Math.acos(Math.max(-1, Math.min(1, meanUx(G) * meanUx0 + 0)))));   // |Δangle| of mean axis x-cos (rough)
        return o;
    }

    // ============================ velocity + summary from Obs ============================
    static final class Summary {
        double velFwd, velMed, velSd, fracFwdTime, reversalRateHz, startupMs;
        double meanBoundHeads, meanBoundDim, oneFrac, twoFrac, dblDwell, attachLife, continuity, meanActive;
        double netFwdForce, forcePerHead;
        int fwd, bwd; double fwdDwell, bwdDwell, fwdForce, bwdForce;
        int invalid, solverFail, nearSiteDoubleBind; double maxGap, peakF, rotDrift;
        double propBiasMoveFwd, propBiasStill, propBiasMoveBwd;   // fwd fraction of eligible proposals by motion class
        // gap distribution + branch mechanics
        double gapP50, gapP99, gapP999; int exc4, exc10, exc50, exc100;
        double peakBranchF, maxBranchExt, meanBranchExt;
        int excSecondBind, excStroke, excDetach, excFilMotion, excOther;
    }
    static Summary summarize(EScene sc, Obs o, int steps) {
        Summary s = new Summary();
        // steady velocity: LS slope over the last 75% of the trajectory
        int n = o.ts.size(), i0 = n / 4;
        double slope = lsSlope(o.ts, o.xs, i0);   // µm/s world axial
        double dirSign = (sc.assay == 0 ? -1 : +1) * sc.bhatX;
        s.velFwd = slope * dirSign;               // + = productive (forward) glide, polarity-normalized
        // windowed velocities for median/sd/reversals/fraction-forward
        List<Double> wv = new ArrayList<>();
        for (int i = i0 + 1; i < n; i++) { double dt = o.ts.get(i) - o.ts.get(i - 1); if (dt <= 0) continue;
            wv.add((o.xs.get(i) - o.xs.get(i - 1)) / dt * dirSign); }
        s.velMed = median(wv); s.velSd = sd(wv);
        int fwdT = 0, rev = 0; double prevSign = 0;
        for (double v : wv) { if (v > 0) fwdT++; double sg = Math.signum(v); if (prevSign != 0 && sg != 0 && sg != prevSign) rev++; if (sg != 0) prevSign = sg; }
        s.fracFwdTime = wv.isEmpty() ? Double.NaN : (double) fwdT / wv.size();
        double windowSpanS = n > i0 ? (o.ts.get(n - 1) - o.ts.get(i0)) : 0;
        s.reversalRateHz = windowSpanS > 0 ? rev / windowSpanS : 0;
        // startup latency: first time the productive cumulative displacement passes 5 nm and stays (approx: first sample > 5nm)
        double x0 = o.xs.isEmpty() ? 0 : o.xs.get(0); s.startupMs = Double.NaN;
        for (int i = 0; i < n; i++) { double disp = (o.xs.get(i) - x0) * dirSign * 1e3; if (disp > 5) { s.startupMs = o.ts.get(i) * 1e3; break; } }
        double st = o.steps;
        s.meanBoundHeads = o.boundHeadSum / st; s.meanBoundDim = o.boundDimSum / st;
        s.oneFrac = o.oneHeadSum / (double) (sc.nDim) / st; s.twoFrac = o.twoHeadSum / (double) (sc.nDim) / st;
        s.dblDwell = mean(o.dblDwell); s.attachLife = mean(o.attachLife);
        s.continuity = o.stepsWithBound / st; s.meanActive = o.activeSum / st;
        s.netFwdForce = o.netAxSum / st; s.forcePerHead = o.forcePerHeadN > 0 ? o.forcePerHeadSum / o.forcePerHeadN : 0;
        s.fwd = o.fwdBinds; s.bwd = o.bwdBinds;
        s.fwdDwell = mean(o.fwdSecondDwell); s.bwdDwell = mean(o.bwdSecondDwell);
        s.fwdForce = mean(o.fwdSecondForce); s.bwdForce = mean(o.bwdSecondForce);
        s.invalid = o.invalid; s.solverFail = o.solverFail; s.nearSiteDoubleBind = o.nearSiteDoubleBind;
        s.maxGap = o.maxGap; s.peakF = o.peakF; s.rotDrift = o.rotDriftDeg;
        // gap distribution (percentiles of per-step max gap) + branch mechanics + excursion classification
        s.gapP50 = pctl(o.gapSamples, 0.50); s.gapP99 = pctl(o.gapSamples, 0.99); s.gapP999 = pctl(o.gapSamples, 0.999);
        s.exc4 = o.exc4; s.exc10 = o.exc10; s.exc50 = o.exc50; s.exc100 = o.exc100;
        s.peakBranchF = o.peakBranchF; s.maxBranchExt = o.maxBranchExt; s.meanBranchExt = o.branchExtN > 0 ? o.branchExtSum / o.branchExtN : 0;
        s.excSecondBind = o.excSecondBind; s.excStroke = o.excStroke; s.excDetach = o.excDetach; s.excFilMotion = o.excFilMotion; s.excOther = o.excOther;
        s.propBiasMoveFwd = frac(o.propFwd_moveFwd, o.propBwd_moveFwd);
        s.propBiasStill = frac(o.propFwd_still, o.propBwd_still);
        s.propBiasMoveBwd = frac(o.propFwd_moveBwd, o.propBwd_moveBwd);
        return s;
    }

    // ============================ main ============================
    public static void main(String[] args) throws IOException {
        String mode = args.length > 0 && !args[0].startsWith("-") ? args[0] : "-smoke";
        for (String a : args) if (a.equals("-smoke") || a.equals("-density") || a.equals("-e3") || a.equals("-load") || a.equals("-dtconv") || a.equals("-3js") || a.equals("-events") || a.equals("-mat") || a.equals("-matsmoke")) mode = a;
        if (has(args, "-3js")) mode = "-3js";   // -3js is the highest-priority mode; -mat is a sub-flag selecting the mat scene
        int seeds = (int) argD(args, "-seeds", 8);
        int steps = (int) argD(args, "-steps", 12000);
        int nDim  = (int) argD(args, "-ndim", 8);
        double spacing = argD(args, "-spacing", 40);
        double gap = argD(args, "-gap", 8);
        int assay = has(args, "-assayB") ? 1 : 0;
        // dimer-only compliance multipliers (this study; default 1.0 ⇒ reference dimer byte-identical)
        CFG_EA = argD(args, "-branchEA", 1.0); CFG_EI = argD(args, "-branchEI", 1.0); CFG_FORK = argD(args, "-forkK", 1.0);
        DT = argD(args, "-dt", 2.5e-6);   // timestep override (mat sensitivity study); default byte-identical
        if (has(args, "-fixtures")) { fixtures(args); return; }
        if (has(args, "-shcell")) { shCell(args); return; }

        switch (mode) {
            case "-smoke":  smoke(seeds, steps, nDim, spacing, gap); break;
            case "-density": density(seeds, steps, gap, assay); break;
            case "-e3":     e3(seeds, steps, nDim, spacing, gap); break;
            case "-load":   load(seeds, steps, nDim, spacing, gap, assay); break;
            case "-dtconv": dtconv(nDim, spacing, gap, assay); break;
            case "-events": events(seeds, steps, nDim, spacing, gap, assay); break;
            case "-matsmoke": matSmoke(argD(args, "-mdensity", 500), (int) argD(args, "-seed", 101), steps, gap, (int) argD(args, "-dmode", 0),
                                       argD(args, "-cullr", 0.12), argD(args, "-fillen", 2.0)); break;
            case "-mat":    mat(seeds, steps, gap, args); break;
            case "-3js": {
                String dir = argStr(args, "-3js", "threejs_hmm_gliding");
                int modeD = (int) argD(args, "-dmode", 0); int seed = (int) argD(args, "-seed", 7);
                boolean matViz = has(args, "-mat");
                EScene sc = matViz ? buildMat(argD(args, "-mdensity", 300), gap, seed, argD(args, "-filbrown", 1.0), argD(args, "-cullr", 0.12), argD(args, "-fillen", 2.0))
                                   : build(nDim, spacing, gap, seed, argD(args, "-filbrown", 1.0), assay);
                applyMode(sc, modeD);
                System.out.printf(Locale.US, "3js: %s D%d %s seed=%d steps=%d → %s%n",
                        matViz ? "MAT " + (int) argD(args, "-mdensity", 300) + "/µm² (nDim=" + sc.nDim + ")" : "assay " + (assay == 0 ? "A" : "B") + " nDim=" + nDim,
                        modeD, matViz ? "" : "spacing=" + spacing + "nm", seed, steps, dir);
                Obs o = run(sc, seed, steps, 40, dir, (int) argD(args, "-fstride", 40));
                Summary s = summarize(sc, o, steps);
                System.out.printf(Locale.US, "  velFwd=%.3f µm/s  meanBound=%.2f  twoFrac=%.4f  fwd/bwd=%d/%d  invalid=%d maxGap=%.2fnm%n",
                        s.velFwd, s.meanBoundHeads, s.twoFrac, s.fwd, s.bwd, s.invalid, s.maxGap);
                break;
            }
            default: smoke(seeds, steps, nDim, spacing, gap);
        }
    }

    // ---- DOUBLE-HEAD MAT: single cell (one density × mode × seed) — emits a MATROW CSV line for parallel drives ----
    static void matSmoke(double density, int seed, int steps, double gap, int dmode, double cullR, double filLen) throws IOException {
        long t0 = System.currentTimeMillis();
        EScene sc = buildMat(density, gap, seed, 1.0, cullR, filLen); applyMode(sc, dmode);
        System.out.printf(Locale.US, "=== DOUBLE-HEAD MAT CELL: density=%.0f dimers/µm² → nDim=%d (heads=%d) on %.1f×%.1f µm lawn, D%d, seed=%d, %d steps; fil=%.2fµm (%dseg) cullR=%.0fnm | branchEA×%.4g branchEI×%.4g forkK×%.4g ===%n",
                density, sc.nDim, 2 * sc.nDim, TwoBodyConverterMotor.G4_MATX, TwoBodyConverterMotor.G4_MATY, dmode, seed, steps, sc.nSeg * SEGLEN, sc.nSeg, cullR * 1e3, CFG_EA, CFG_EI, CFG_FORK);
        Obs o = run(sc, seed, steps, 40, null, 0); Summary s = summarize(sc, o, steps);
        double wall = (System.currentTimeMillis() - t0) / 1e3;
        System.out.printf(Locale.US, "  velRaw=%+.3f µm/s (productive %+.3f)  meanBound=%.2f  cont=%.3f  activeDim=%.1f  twoFrac=%.4f  netF=%+.3f pN  F/head=%.3f  fwd/bwd=%d/%d  wall=%.1fs%n",
                -s.velFwd, s.velFwd, s.meanBoundHeads, s.continuity, s.meanActive, s.twoFrac, s.netFwdForce, s.forcePerHead, s.fwd, s.bwd, wall);
        System.out.printf(Locale.US, "  GAP maxGap=%.2f p999=%.2f p99=%.2f p50=%.3f nm | exc>4/10/50/100=%d/%d/%d/%d | peakBrF=%.1f pN maxBrExt=%.2f nm meanBrExt=%.3f nm | trig 2nd/stroke/detach/filmo=%d/%d/%d/%d | inv=%d sf=%d%n",
                s.maxGap, s.gapP999, s.gapP99, s.gapP50, s.exc4, s.exc10, s.exc50, s.exc100, s.peakBranchF, s.maxBranchExt, s.meanBranchExt, s.excSecondBind, s.excStroke, s.excDetach, s.excFilMotion, s.invalid, s.solverFail);
        // MATROW,dmode,density,nDim,heads,seed,velRaw,velProd,meanBound,cont,activeDim,twoFrac,netF,Fhead,fwd,bwd,reversalHz,fracFwdT,maxGap,invalid,sf,   (f1..f21)
        //        branchEA,branchEI,forkK,gapP999,gapP99,gapP50,exc4,exc10,exc50,exc100,peakBrF,maxBrExt,meanBrExt,exc2nd,excStroke,excDetach,excFilmo,peakF, (f22..f39)
        //        startupMs,meanBoundDim,oneFrac,dblDwell,attachLife,fracFwdT2,velMed,velSd,cullR_nm,filLen_um  (f40..f49)
        System.out.printf(Locale.US, "MATROW,%d,%.0f,%d,%d,%d,%.5f,%.5f,%.4f,%.5f,%.2f,%.5f,%.4f,%.4f,%d,%d,%.1f,%.4f,%.3f,%d,%d,%.5g,%.5g,%.5g,%.3f,%.3f,%.4f,%d,%d,%d,%d,%.2f,%.3f,%.4f,%d,%d,%d,%d,%.3f,%.4f,%.4f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.4f,%.4f%n",
                dmode, density, sc.nDim, 2 * sc.nDim, seed, -s.velFwd, s.velFwd, s.meanBoundHeads, s.continuity, s.meanActive, s.twoFrac, s.netFwdForce, s.forcePerHead,
                s.fwd, s.bwd, s.reversalRateHz, s.fracFwdTime, s.maxGap, s.invalid, s.solverFail,
                CFG_EA, CFG_EI, CFG_FORK, s.gapP999, s.gapP99, s.gapP50, s.exc4, s.exc10, s.exc50, s.exc100, s.peakBranchF, s.maxBranchExt, s.meanBranchExt,
                s.excSecondBind, s.excStroke, s.excDetach, s.excFilMotion, s.peakF,
                s.startupMs, s.meanBoundDim, s.oneFrac, s.dblDwell, s.attachLife, s.fracFwdTime, s.velMed, s.velSd, sc.cullR * 1e3, sc.nSeg * SEGLEN);
    }

    // ---- DOUBLE-HEAD MAT density sweep × D0/D1/D2 (the user-requested full mat assay) ----
    static void mat(int seeds, int steps, double gap, String[] args) throws IOException {
        double[] dens = { 500, 750, 1000 };
        if (has(args, "-mdlist")) { String[] p = argStr(args, "-mdlist", "500,750,1000").split(","); dens = new double[p.length]; for (int i = 0; i < p.length; i++) dens[i] = Double.parseDouble(p[i]); }
        double cullR = argD(args, "-cullr", 0.12), filLen = argD(args, "-fillen", 2.0);
        System.out.printf(Locale.US, "=== DOUBLE-HEAD MAT GLIDING DENSITY SWEEP (Assay A, dimers/µm², %.1f×%.1f µm lawn, fil≈%.1fµm, cullR=%.0fnm, %d seeds × %d steps, gap=%.0fnm) ===%n",
                TwoBodyConverterMotor.G4_MATX, TwoBodyConverterMotor.G4_MATY, filLen, cullR * 1e3, seeds, steps, gap);
        System.out.println("velRaw = centroid-x LS slope (µm/s; NEGATIVE = pointed-first = productive glide, matching the single-head mat).");
        System.out.println("mode | dens(µm⁻²) nDim heads | velRaw(µm/s) 95%CI          | meanBound continuity twoFrac | netF(pN) F/head | fwd/bwd  activeDim maxGap invalid");
        for (int dm = 0; dm < 3; dm++) {
            for (double d : dens) {
                List<Double> raws = new ArrayList<>(); double mb = 0, cont = 0, tf = 0, nf = 0, fh = 0, ad = 0, mg = 0; long fwd = 0, bwd = 0, inv = 0, sf = 0; int nDim = 0, heads = 0;
                for (int s = 1; s <= seeds; s++) {
                    EScene sc = buildMat(d, gap, 100 + s, 1.0, cullR, filLen); applyMode(sc, dm); nDim = sc.nDim; heads = 2 * nDim;
                    Obs o = run(sc, 100 + s, steps, 40, null, 0); Summary sm = summarize(sc, o, steps);
                    raws.add(-sm.velFwd); mb += sm.meanBoundHeads; cont += sm.continuity; tf += sm.twoFrac; nf += sm.netFwdForce; fh += sm.forcePerHead;
                    ad += sm.meanActive; mg = Math.max(mg, sm.maxGap); fwd += sm.fwd; bwd += sm.bwd; inv += sm.invalid; sf += sm.solverFail;
                }
                double[] ci = bootCI(raws);
                System.out.printf(Locale.US, "D%d   | %6.0f  %5d %5d | %+6.3f [%+.3f,%+.3f] | %7.3f  %7.3f  %.4f | %+7.3f %6.3f | %4d/%-4d %6.1f  %5.1f  %3d%s%n",
                        dm, d, nDim, heads, mean(raws), ci[0], ci[1], mb / seeds, cont / seeds, tf / seeds, nf / seeds, fh / seeds, fwd, bwd, ad / seeds, mg, inv, sf > 0 ? " sf=" + sf : "");
            }
            System.out.println();
        }
    }

    static void applyMode(EScene sc, int dmode) {
        sc.exclusionNm = EXCL;
        if (dmode == 0) { sc.rearVeto = false; sc.rearPenalty = 1.0; }
        else if (dmode == 1) { sc.rearVeto = false; sc.rearPenalty = 0.25; }
        else { sc.rearVeto = true; sc.rearPenalty = 1.0; }
    }
    static String dName(int d) { return d == 0 ? "D0 unbiased" : d == 1 ? "D1 γ0.25" : "D2 hard veto"; }

    // ---- smoke: sanity that the ensemble glides + all three modes run, one density ----
    static void smoke(int seeds, int steps, int nDim, double spacing, double gap) throws IOException {
        System.out.printf(Locale.US, "=== ENSEMBLE GLIDING SMOKE (Assay A, nDim=%d spacing=%.0fnm gap=%.0fnm, %d seeds × %d steps) ===%n", nDim, spacing, gap, seeds, steps);
        System.out.println("mode          | velFwd(µm/s)  fracFwdT  meanBound  twoFrac  fwd/bwd  occlRej  rearRej | netF(pN) F/head  maxGap invalid solverFail");
        for (int dm = 0; dm < 3; dm++) {
            List<Double> vels = new ArrayList<>(); Summary agg = null; long fwd = 0, bwd = 0, occl = 0, rear = 0, inv = 0, sf = 0; double mb = 0, tf = 0, ff = 0, nf = 0, fh = 0, mg = 0;
            for (int s = 1; s <= seeds; s++) {
                EScene sc = build(nDim, spacing, gap, s, 1.0, 0); applyMode(sc, dm);
                Obs o = run(sc, s, steps, 40, null, 0); Summary sm = summarize(sc, o, steps);
                vels.add(sm.velFwd); fwd += sm.fwd; bwd += sm.bwd; occl += o.occupancyRejects; rear += o.rearRejects; inv += sm.invalid; sf += sm.solverFail;
                mb += sm.meanBoundHeads; tf += sm.twoFrac; ff += sm.fracFwdTime; nf += sm.netFwdForce; fh += sm.forcePerHead; mg = Math.max(mg, sm.maxGap);
            }
            double vm = mean(vels), vsd = sd(vels);
            System.out.printf(Locale.US, "%-13s | %+6.3f±%.3f   %.3f    %.3f    %.4f  %3d/%-3d  %5d   %5d | %+7.3f %6.3f  %5.2f  %3d   %3d%n",
                    dName(dm), vm, vsd / Math.sqrt(Math.max(1, seeds)), ff / seeds, mb / seeds, tf / seeds, fwd, bwd, occl, rear, nf / seeds, fh / seeds, mg, inv, sf);
        }
    }

    // ---- density sweep × D0/D1/D2 ----
    static void density(int seeds, int steps, double gap, int assay) throws IOException {
        int[] nDims = { 2, 4, 8, 12, 16 };     // low → high motor density (fixed filament span)
        System.out.printf(Locale.US, "=== DENSITY SWEEP (Assay %s, %d seeds × %d steps, gap=%.0fnm, spacing 40nm) ===%n", assay == 0 ? "A" : "B", seeds, steps, gap);
        System.out.println("mode | nDim  ρ(/µm) | velFwd(µm/s) 95%CI      | meanBound twoFrac | netF(pN) F/head | reversal(Hz) fracFwdT | fwd/bwd invalid");
        for (int dm = 0; dm < 3; dm++) {
            for (int nd : nDims) {
                List<Double> vels = new ArrayList<>(); double mb = 0, tf = 0, nf = 0, fh = 0, rr = 0, ff = 0; long fwd = 0, bwd = 0, inv = 0; double rho = 0;
                for (int s = 1; s <= seeds; s++) {
                    EScene sc = build(nd, 40, gap, s, 1.0, assay); applyMode(sc, dm);
                    rho = nd / ((sc.nSeg - 2) * SEGLEN);
                    Obs o = run(sc, s, steps, 40, null, 0); Summary sm = summarize(sc, o, steps);
                    vels.add(sm.velFwd); mb += sm.meanBoundHeads; tf += sm.twoFrac; nf += sm.netFwdForce; fh += sm.forcePerHead;
                    rr += sm.reversalRateHz; ff += sm.fracFwdTime; fwd += sm.fwd; bwd += sm.bwd; inv += sm.invalid;
                }
                double[] ci = bootCI(vels);
                System.out.printf(Locale.US, "D%d   | %3d   %5.2f  | %+6.3f [%+.3f,%+.3f] | %6.3f  %.4f | %+7.3f %6.3f | %8.1f   %.3f | %3d/%-3d %3d%n",
                        dm, nd, rho, mean(vels), ci[0], ci[1], mb / seeds, tf / seeds, nf / seeds, fh / seeds, rr / seeds, ff / seeds, fwd, bwd, inv);
            }
            System.out.println();
        }
    }

    // ---- E3: prescribed relative motion — proposal bias vs actin speed (the clean mechanistic control) ----
    static void e3(int seeds, int steps, int nDim, double spacing, double gap) throws IOException {
        double[] vpres = { 0, -0.5, -2.0, -5.0, +2.0 };   // µm/s ; negative = forward glide direction (actin toward pointed)
        System.out.printf(Locale.US, "=== E3 PRESCRIBED-MOTION PROPOSAL BIAS (Assay A geom, kinematic actin, %d seeds × %d steps, nDim=%d) ===%n", seeds, steps, nDim);
        System.out.println("Second-head eligible proposals split fwd/bwd while actin is dragged at a controlled speed past anchored dimers (D0, exclusion on).");
        System.out.println(" vActin(µm/s)  motionClass | eligFwdProp  eligBwdProp  fwdFrac | fwdBind  bwdBind");
        for (double v : vpres) {
            long pf = 0, pb = 0; long fb = 0, bb = 0;
            for (int s = 1; s <= seeds; s++) {
                EScene sc = build(nDim, spacing, gap, s, 0.0, 0); applyMode(sc, 0); sc.prescribedV = (v == 0 ? Double.NaN : v);
                Obs o = new Obs();
                bindStep = new int[sc.G.N]; secondBindStep = new int[sc.G.N]; secondBindDir = new int[sc.G.N];
                secondForceAccum = new double[sc.G.N]; secondForceN = new int[sc.G.N]; dblRunStart = new int[sc.nDim];
                java.util.Arrays.fill(bindStep, -1); java.util.Arrays.fill(secondBindStep, -1); java.util.Arrays.fill(dblRunStart, -1);
                double wv = (v == 0 ? 0 : -v * sc.bhatX);   // forward window vel = productive direction
                for (int t = 0; t < steps; t++) step(sc, t, s, o, wv);
                // proposals conditioned on this fixed motion class
                if (v > 0.01) { pf += o.propFwd_moveFwd; pb += o.propBwd_moveFwd; }        // actin +x is BACKWARD (non-productive)
                else if (v < -0.01) { pf += o.propFwd_moveFwd; pb += o.propBwd_moveFwd; }  // forward class holds all here
                pf += o.propFwd_still + o.propFwd_moveFwd + o.propFwd_moveBwd; pb += o.propBwd_still + o.propBwd_moveFwd + o.propBwd_moveBwd;
                fb += o.fwdBinds; bb += o.bwdBinds;
            }
            double ff = (pf + pb) > 0 ? (double) pf / (pf + pb) : Double.NaN;
            String cls = Math.abs(v) < 0.01 ? "still" : (v < 0 ? "forward" : "reverse");
            System.out.printf(Locale.US, " %+6.2f       %-8s | %8d   %8d   %.3f | %6d  %6d%n", v, cls, pf, pb, ff, fb, bb);
        }
        System.out.println("(Eligible = passed the geometric bind gate AND the partner is bound. fwdFrac>0.5 ⇒ motion exposes barbed sites preferentially.)");
    }

    // ---- load–response ----
    static void load(int seeds, int steps, int nDim, double spacing, double gap, int assay) throws IOException {
        double[] loads = { 0, 1, 3, 6, 10 };
        System.out.printf(Locale.US, "=== LOAD RESPONSE (Assay %s, nDim=%d, %d seeds × %d steps) ===%n", assay == 0 ? "A" : "B", nDim, seeds, steps);
        for (int dm = 0; dm < 3; dm++) {
            System.out.printf(Locale.US, "-- %s --%n load(pN) | velFwd(µm/s)   meanBound twoFrac | detachLife(ms) fwd/bwd%n", dName(dm));
            for (double L : loads) {
                List<Double> vels = new ArrayList<>(); double mb = 0, tf = 0, al = 0; long fwd = 0, bwd = 0;
                for (int s = 1; s <= seeds; s++) {
                    EScene sc = build(nDim, spacing, gap, s, 1.0, assay); applyMode(sc, dm); sc.loadPn = L;
                    Obs o = run(sc, s, steps, 40, null, 0); Summary sm = summarize(sc, o, steps);
                    vels.add(sm.velFwd); mb += sm.meanBoundHeads; tf += sm.twoFrac; al += sm.attachLife; fwd += sm.fwd; bwd += sm.bwd;
                }
                System.out.printf(Locale.US, " %5.1f    | %+6.3f±%.3f   %6.3f  %.4f | %8.3f     %3d/%-3d%n",
                        L, mean(vels), sd(vels) / Math.sqrt(Math.max(1, seeds)), mb / seeds, tf / seeds, al / seeds, fwd, bwd);
            }
            System.out.println();
        }
    }

    // ---- timestep convergence ----
    static void dtconv(int nDim, double spacing, double gap, int assay) throws IOException {
        System.out.printf(Locale.US, "=== TIMESTEP CONVERGENCE (Assay %s, nDim=%d, D0, 8 seeds) ===%n", assay == 0 ? "A" : "B", nDim);
        System.out.println("dt(s)   steps | velFwd(µm/s)  meanBound  twoFrac  maxGap invalid");
        // dt and dt/2 for the SAME physical duration
        int[] dims = { nDim };
        double dur = 12000 * DT;
        for (double[] cfg : new double[][]{ { DT, 12000 }, { DT / 2, 24000 } }) {
            double dt = cfg[0]; int steps = (int) cfg[1];
            List<Double> vels = new ArrayList<>(); double mb = 0, tf = 0, mg = 0; long inv = 0;
            for (int s = 1; s <= 8; s++) {
                EScene sc = buildDt(nDim, spacing, gap, s, assay, dt); applyMode(sc, 0);
                Obs o = run(sc, s, steps, (int) (40 * (dt == DT ? 1 : 2)), null, 0); Summary sm = summarize(sc, o, steps);
                vels.add(sm.velFwd); mb += sm.meanBoundHeads; tf += sm.twoFrac; mg = Math.max(mg, sm.maxGap); inv += sm.invalid;
            }
            System.out.printf(Locale.US, "%.2e %5d | %+6.3f      %6.3f    %.4f  %5.2f  %3d%n", dt, steps, mean(vels), mb / 8, tf / 8, mg, inv);
        }
    }

    // ---- events: event-conditioned backward-bind analysis + motion-conditioned proposal bias (Assay A dynamic) ----
    static void events(int seeds, int steps, int nDim, double spacing, double gap, int assay) throws IOException {
        System.out.printf(Locale.US, "=== EVENT-CONDITIONED SECOND-BIND ANALYSIS (Assay %s D0, %d seeds × %d steps, nDim=%d) ===%n", assay == 0 ? "A" : "B", seeds, steps, nDim);
        Obs agg = new Obs();
        for (int s = 1; s <= seeds; s++) {
            EScene sc = build(nDim, spacing, gap, s, 1.0, assay); applyMode(sc, 0);
            Obs o = run(sc, s, steps, 40, null, 0);
            agg.fwdSecondDwell.addAll(o.fwdSecondDwell); agg.bwdSecondDwell.addAll(o.bwdSecondDwell);
            agg.fwdSecondForce.addAll(o.fwdSecondForce); agg.bwdSecondForce.addAll(o.bwdSecondForce);
            agg.fwdVelAtBind.addAll(o.fwdVelAtBind); agg.bwdVelAtBind.addAll(o.bwdVelAtBind);
            agg.fwdBinds += o.fwdBinds; agg.bwdBinds += o.bwdBinds;
            agg.propFwd_moveFwd += o.propFwd_moveFwd; agg.propBwd_moveFwd += o.propBwd_moveFwd;
            agg.propFwd_still += o.propFwd_still; agg.propBwd_still += o.propBwd_still;
            agg.propFwd_moveBwd += o.propFwd_moveBwd; agg.propBwd_moveBwd += o.propBwd_moveBwd;
        }
        System.out.printf(Locale.US, "second binds: forward %d, backward %d (forward fraction %.3f)%n", agg.fwdBinds, agg.bwdBinds,
                (agg.fwdBinds + agg.bwdBinds) > 0 ? (double) agg.fwdBinds / (agg.fwdBinds + agg.bwdBinds) : Double.NaN);
        System.out.printf(Locale.US, "FORWARD  second binds: mean dwell %.4f ms, mean signed axial force %+.3f pN (n=%d)%n", mean(agg.fwdSecondDwell), mean(agg.fwdSecondForce), agg.fwdSecondDwell.size());
        System.out.printf(Locale.US, "BACKWARD second binds: mean dwell %.4f ms, mean signed axial force %+.3f pN (n=%d)%n", mean(agg.bwdSecondDwell), mean(agg.bwdSecondForce), agg.bwdSecondDwell.size());
        System.out.printf(Locale.US, "velocity at bind: forward-bind %.3f µm/s, backward-bind %.3f µm/s%n", mean(agg.fwdVelAtBind), mean(agg.bwdVelAtBind));
        System.out.println("motion-conditioned eligible-proposal forward fraction:");
        System.out.printf(Locale.US, "  while moving FORWARD: %.3f (fwd %d / bwd %d)%n", frac(agg.propFwd_moveFwd, agg.propBwd_moveFwd), agg.propFwd_moveFwd, agg.propBwd_moveFwd);
        System.out.printf(Locale.US, "  while ~STILL:         %.3f (fwd %d / bwd %d)%n", frac(agg.propFwd_still, agg.propBwd_still), agg.propFwd_still, agg.propBwd_still);
        System.out.printf(Locale.US, "  while moving BACKWARD:%.3f (fwd %d / bwd %d)%n", frac(agg.propFwd_moveBwd, agg.propBwd_moveBwd), agg.propFwd_moveBwd, agg.propBwd_moveBwd);
    }

    // ============================ single-head reference cell (matched lawn, for the density re-baseline) ============================
    /** Matched single-head explicit-S2 mat reference on the SAME 3.0×1.0 µm lawn (G4_MATX/MATY defaults, same
     *  density→N mapping g4NMot), L=40 nm core, same dt/duration. Calls the package-private single-head mat velocity
     *  (`TwoBodyConverterMotor.glideSpeedS2`) — the single-head MODEL is NOT modified (measurement reuse only).
     *  MATCH is APPROXIMATE: shares lawn/density/dt/duration/chemistry/motor(L40)/filament≈2µm; differs in gap detail
     *  (anchor −0.05 µm vs dimer zTarget) and cull mechanism (queryR≈80 nm vs dimer cullR 120 nm). vel<0 = productive. */
    static void shCell(String[] args) {
        double density = argD(args, "-shdensity", argD(args, "-mdensity", 400));
        int seed = (int) argD(args, "-seed", 101);
        int steps = (int) argD(args, "-steps", 5000);
        double durS = steps * DT;
        long t0 = System.currentTimeMillis();
        double[] su = TwoBodyConverterMotor.glideSpeedS2(density, DT, 40.0, TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, durS, seed, null);
        double wall = (System.currentTimeMillis() - t0) / 1e3;
        int N = (int) Math.round(density * TwoBodyConverterMotor.G4_MATX * TwoBodyConverterMotor.G4_MATY);
        System.out.printf(Locale.US, "SHCELL density=%.0f heads/µm² N=%d seed=%d steps=%d dt=%.2e: vel=%+.4f µm/s (productive %+.4f) avgBound=%.3f net=%.4fµm wall=%.1fs%n",
                density, N, seed, steps, DT, su[0], -su[0], su[2], su[1] / 1e3, wall);
        System.out.printf(Locale.US, "SHROW,%.0f,%d,%d,%d,%.6e,%.5f,%.5f,%.5f%n", density, N, seed, steps, DT, su[0], su[2], su[1] / 1e3);
    }

    // ============================ F1–F5 controlled single-dimer fixtures (compliance study) ============================
    /** Deterministic (Brownian-OFF, actin FIXED) single-dimer fixtures isolating the dimer-internal transient.
     *  Reports for the CURRENT -branchEA/-branchEI/-forkK setting. Returns nothing; prints a compact per-fixture trace. */
    static void fixtures(String[] args) {
        double gap = argD(args, "-gap", 8);
        System.out.printf(Locale.US, "=== F1–F5 CONTROLLED SINGLE-DIMER FIXTURES | branchEA×%.4g branchEI×%.4g forkK×%.4g | gap=%.0fnm dt=%.2e (Brownian OFF, actin fixed) ===%n",
                CFG_EA, CFG_EI, CFG_FORK, gap, DT);
        EScene sc = build(1, 40, gap, 7, 1.0, 0); Glide2D G = sc.G; MotorStore mot = G.mot; Dimer d = sc.dim[0];
        int mA = 0, mB = 1;
        // free both heads
        mot.boundSeg.set(mA, MotorStore.FREE_BINDABLE); mot.boundSeg.set(mB, MotorStore.FREE_BINDABLE);
        mot.nucleotideState.set(mA, MotorStore.NUC_ADPPI); mot.nucleotideState.set(mB, MotorStore.NUC_ADPPI);

        // ---- F1: detached relaxation ----
        for (int t = 0; t < 400; t++) if (dimerStep(sc, t) < 1e-9) break;
        System.out.printf(Locale.US, "F1 detached: forkAngle=%.2f° (rest %.1f°) brExtMax=%.3fnm jointGap=%.3fnm sharedGap=%.3fnm brF=%.2fpN%n",
                ExplicitHmmDimer.forkAngleDeg(d), 2 * ALPHA, ExplicitHmmDimer.maxBranchExtNm(d), ExplicitHmmDimer.maxJointGap(d), sharedGapNm(d), ExplicitHmmDimer.maxBranchAxialForcePn(d));
        boolean collapse = ExplicitHmmDimer.forkAngleDeg(d) < 1.0 || ExplicitHmmDimer.maxBranchExtNm(d) > 5.0;
        System.out.println("   detached-collapse: " + (collapse ? "YES (WARN)" : "no"));

        // ---- F2: one head bound, relax ----
        bindHeadGeom(sc, mA); mot.nucleotideState.set(mA, MotorStore.NUC_ADPPI);
        double[] freeTip0 = d.hB.xF8.clone();
        for (int t = 0; t < 400; t++) if (dimerStep(sc, t) < 1e-9) break;
        double f8A = f8mag(G, mA); double freeMove = dist(d.hB.xF8, freeTip0) * 1e3;
        System.out.printf(Locale.US, "F2 one-bound: boundF8=%.2fpN forkAngle=%.2f° brExtMax=%.3fnm jointGap=%.3fnm freeHeadMove=%.3fnm (coupling)%n",
                f8A, ExplicitHmmDimer.forkAngleDeg(d), ExplicitHmmDimer.maxBranchExtNm(d), ExplicitHmmDimer.maxJointGap(d), freeMove);

        // ---- F3: second-head binding transient (PRIMARY trigger fixture) ----
        // bind B on A's segment, ~8 nm barbed of A (guarantees ≥5.4 nm separation)
        int sA = mot.boundSeg.get(mA); double arcA = mot.bindArc.get(mA);
        double half = 0.5 * G.fil.segLength.get(sA), margin = TwoBodyConverterMotor.bindMargin();
        double off = (arcA + 0.008 <= 2 * half - margin) ? 0.008 : -0.008;   // guarantee an 8 nm barbed/pointed offset (≥5.4 nm)
        double arcB = arcA + off;
        mot.boundSeg.set(mB, sA); mot.bindArc.set(mB, (float) arcB); mot.nucleotideState.set(mB, MotorStore.NUC_ADPPI);
        double sepNm = Math.abs(ExplicitHmmDimer3jsHarness.filMatCoordUm(G.fil, G.nSeg, sA, arcA) - ExplicitHmmDimer3jsHarness.filMatCoordUm(G.fil, G.nSeg, sA, arcB)) * 1e3;
        System.out.printf(Locale.US, "F3 second-bind transient (sep=%.2fnm): step | brA/brB ext(nm) brA/brB F(pN) | fork(nm) F8A/F8B(pN) jointGap(nm) phiA/phiB(°) nodeMove(nm) st%n", sepNm);
        double[] forkRest = d.nd[d.Ms].clone(); double f3maxGap = 0, f3peakBrF = 0;
        for (int t = 0; t < 50; t++) {
            double mv = dimerStep(sc, t);
            double gapNow = ExplicitHmmDimer.maxJointGap(d); f3maxGap = Math.max(f3maxGap, gapNow); f3peakBrF = Math.max(f3peakBrF, ExplicitHmmDimer.maxBranchAxialForcePn(d));
            if (t < 12 || t % 8 == 0)
                System.out.printf(Locale.US, "   %3d | %6.3f/%6.3f  %6.2f/%6.2f | %6.3f  %5.2f/%5.2f  %7.3f  %6.1f/%6.1f  %7.4f  %d%n",
                        t, brExtNm(d, 0), brExtNm(d, 1), brF(d, 0), brF(d, 1), dist(d.nd[d.Ms], forkRest) * 1e3, f8mag(G, mA), f8mag(G, mB),
                        gapNow, Math.toDegrees(d.hA.phi), Math.toDegrees(d.hB.phi), mv * 1e3, mot.boundSeg.get(mA) >= 0 ? 1 : 0);
        }
        System.out.printf(Locale.US, "   F3 SUMMARY: maxJointGap=%.3fnm peakBranchF=%.2fpN%n", f3maxGap, f3peakBrF);

        // ---- F4: double-bound power stroke ----
        for (int t = 0; t < 200; t++) if (dimerStep(sc, t) < 1e-9) break;   // settle doubly bound
        double[] tipA0 = d.hA.xF8.clone();
        mot.nucleotideState.set(mA, MotorStore.NUC_ADP); mot.nucleotideState.set(mB, MotorStore.NUC_ADP);   // trigger stroke on both
        double f4maxGap = 0, f4peakBrF = 0;
        for (int t = 0; t < 50; t++) { double mv = dimerStep(sc, t);
            f4maxGap = Math.max(f4maxGap, ExplicitHmmDimer.maxJointGap(d)); f4peakBrF = Math.max(f4peakBrF, ExplicitHmmDimer.maxBranchAxialForcePn(d)); }
        System.out.printf(Locale.US, "F4 double-stroke: tipA moved %.3fnm  maxJointGap=%.3fnm peakBranchF=%.2fpN brExtMax=%.3fnm%n",
                dist(d.hA.xF8, tipA0) * 1e3, f4maxGap, f4peakBrF, ExplicitHmmDimer.maxBranchExtNm(d));

        // ---- F5: double-bound detachment recoil ----
        double f5maxGap = 0, f5peakBrF = 0;
        mot.boundSeg.set(mB, MotorStore.FREE_BINDABLE); d.coupleB = 0;   // detach head B from the strained double-bound state
        for (int t = 0; t < 50; t++) { double mv = dimerStep(sc, t);
            f5maxGap = Math.max(f5maxGap, ExplicitHmmDimer.maxJointGap(d)); f5peakBrF = Math.max(f5peakBrF, ExplicitHmmDimer.maxBranchAxialForcePn(d)); }
        System.out.printf(Locale.US, "F5 detach recoil: maxJointGap=%.3fnm peakBranchF=%.2fpN finalGap=%.3fnm brExtMax=%.3fnm%n",
                f5maxGap, f5peakBrF, ExplicitHmmDimer.maxJointGap(d), ExplicitHmmDimer.maxBranchExtNm(d));

        // ---- static head stroke (should be branch-independent) ----
        System.out.printf(Locale.US, "STROKE: static-head stroke=%.2fnm (intrinsic converter target swing 60° = ADP−ADPPi)%n", staticHeadStroke(sc));
    }

    /** One deterministic dimer step (Brownian OFF, actin FIXED): place heads, bond forces, coupled solve. Returns max node/angle move. */
    static double dimerStep(EScene sc, int t) {
        Glide2D G = sc.G; MotorStore mot = G.mot; int N = G.N; RigidRodBody b = mot.body;
        for (int m = 0; m < N; m++) { Cmot h = sc.headOf[m]; G.phi[m] = h.phi; G.psi[m] = h.psi; G.A[m] = sc.dim[m / 2].nd[sc.pivOf[m]].clone();
            G.thetaS[m] = TwoBodyConverterMotor.thetaS4a(mot.nucleotideState.get(m));
            TwoBodyConverterMotor.geom2D(G, m); TwoBodyConverterMotor.placeHead2D(G, m); }
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, G.fil.coord, G.fil.uVec, G.fil.yVec, G.fil.bRotGam, G.fil.segLength,
                mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams);
        int ST = CrossBridgeSystem.STRIDE; Dimer d = sc.dim[0];
        double[] f8A = { G.bondData.get(0), G.bondData.get(1), G.bondData.get(2) };
        double[] f8B = { G.bondData.get(ST), G.bondData.get(ST + 1), G.bondData.get(ST + 2) };
        d.hA.thetaS = G.thetaS[0]; d.hB.thetaS = G.thetaS[1];
        double[] snap = flat(d); ExplicitHmmDimer.solve(d, t, 12345, false, f8A, f8B); return maxMove(snap, d);
    }
    /** Bind a head to its geometric nearest segment (bypassing the acceptance gate) at the clamped foot arc. */
    static boolean bindHeadGeom(EScene sc, int m) {
        Glide2D G = sc.G; TwoBodyConverterMotor.geom2D(G, m); int s = TwoBodyConverterMotor.nearestSeg2D(G, m); if (s < 0) return false;
        double[] gm = TwoBodyConverterMotor.gate2D(G, m, s); G.mot.boundSeg.set(m, s); G.mot.bindArc.set(m, (float) gm[1]); return true;
    }
    /** Free-head stroke (nm): with head A UNBOUND (no F8 load) on the dimer beam, toggle the converter target
     *  ADPPi→ADP (the 60° swing) and measure the F8 tip displacement — the unloaded working stroke as the beam
     *  sees it. The intrinsic converter geometry (kF8/kconv/lever) is untouched by branchEA/EI/forkK, so any
     *  change here is beam absorption, not a stroke-mechanism change. */
    static double staticHeadStroke(EScene sc) {
        Glide2D G = sc.G; MotorStore mot = G.mot; Dimer d = sc.dim[0];
        mot.boundSeg.set(1, MotorStore.FREE_BINDABLE); d.coupleB = 0; mot.nucleotideState.set(1, MotorStore.NUC_ADPPI);   // park B free
        mot.boundSeg.set(0, MotorStore.FREE_BINDABLE); mot.nucleotideState.set(0, MotorStore.NUC_ADPPI);                  // head A free, pre-stroke
        for (int t = 0; t < 400; t++) if (dimerStep(sc, t) < 1e-9) break;
        double[] tip0 = d.hA.xF8.clone(); mot.nucleotideState.set(0, MotorStore.NUC_ADP);
        for (int t = 0; t < 400; t++) if (dimerStep(sc, t) < 1e-9) break;
        return dist(d.hA.xF8, tip0) * 1e3;
    }
    static double sharedGapNm(Dimer d) { double mx = 0; for (int si = 0; si < d.seg.length; si++) if (!d.segIsBranch[si]) {
        double len = Math.sqrt(dot(sub(d.nd[d.seg[si][1]], d.nd[d.seg[si][0]]), sub(d.nd[d.seg[si][1]], d.nd[d.seg[si][0]]))); mx = Math.max(mx, Math.abs(len - d.segL0[si])); } return mx * 1e3; }
    static double brExtNm(Dimer d, int side) { int si = branchSeg(d, side); double len = Math.sqrt(dot(sub(d.nd[d.seg[si][1]], d.nd[d.seg[si][0]]), sub(d.nd[d.seg[si][1]], d.nd[d.seg[si][0]]))); return (len - d.segL0[si]) * 1e3; }
    static double brF(Dimer d, int side) { int si = branchSeg(d, side); double len = Math.sqrt(dot(sub(d.nd[d.seg[si][1]], d.nd[d.seg[si][0]]), sub(d.nd[d.seg[si][1]], d.nd[d.seg[si][0]]))); return d.segKs[si] * ((len - d.segL0[si]) * 1e-6) * 1e12; }
    static int branchSeg(Dimer d, int side) { int want = side == 0 ? d.Ms + 1 : d.Ms + d.Ma + 1;  // first branch node on side A/B
        for (int si = 0; si < d.seg.length; si++) if (d.seg[si][1] == want && d.segIsBranch[si]) return si; return d.Ms; }
    static double f8mag(Glide2D G, int m) { int ST = CrossBridgeSystem.STRIDE; double fx = G.bondData.get(m * ST), fy = G.bondData.get(m * ST + 1), fz = G.bondData.get(m * ST + 2); return Math.sqrt(fx * fx + fy * fy + fz * fz) * 1e12; }
    static double dist(double[] a, double[] b) { return Math.sqrt(dot(sub(a, b), sub(a, b))); }

    static EScene buildDt(int nDim, double spacing, double gap, int seed, int assay, double dt) {
        // dt-variant build: identical geometry, different integration dt (rebuild with a scaled Glide dt)
        EScene sc = build(nDim, spacing, gap, seed, 1.0, assay);
        sc.G.dt = dt; sc.G.fil.setParams(dt, Constants.brownianForceMag(dt)); sc.G.fil.setChainParams(dt); sc.G.fil.chainParams.set(0, (float) dt);
        for (int i = 0; i < nDim; i++) sc.dim[i].dt = dt;
        return sc;
    }

    // ============================ geometry / stat helpers ============================
    static double mobileAxial(EScene sc) {
        if (sc.assay == 1 && Double.isNaN(sc.prescribedV)) return sc.asmX;   // Assay B: assembly slider
        double sx = 0; for (int s = 0; s < sc.nSeg; s++) sx += sc.G.fil.coordX(s); return sx / sc.nSeg;   // Assay A: actin centroid x
    }
    static double meanUx(Glide2D G) { double u = 0; for (int s = 0; s < G.nSeg; s++) u += G.fil.uVecX(s); return u / G.nSeg; }
    static double maxGapAll(EScene sc) { double mx = 0; for (int i = 0; i < sc.nDim; i++) { if (sc.matMode && !sc.dimActive[i]) continue;
        mx = Math.max(mx, ExplicitHmmDimer.maxJointGap(sc.dim[i])); } return mx; }
    static boolean invalidState(EScene sc) {
        for (int i = 0; i < sc.nDim; i++) { if (sc.matMode && !sc.dimActive[i]) continue; Dimer d = sc.dim[i];
            for (double[] nd : d.nd) for (double v : nd) if (Double.isNaN(v) || Double.isInfinite(v)) return true;
            if (Double.isNaN(d.hA.phi) || Double.isNaN(d.hB.phi)) return true; }
        for (int s = 0; s < sc.nSeg; s++) if (Double.isNaN(sc.G.fil.coordX(s))) return true;
        return false;
    }
    static void translate(Dimer d, double[] sh) { for (int j = 0; j <= d.NF; j++) d.nd[j] = add(d.nd[j], sh);
        d.E = add(d.E, sh); d.floorZ += sh[2];
        ExplicitHmmDimer.pinHead(d.hA, d.nd[d.pA]); ExplicitHmmDimer.pinHead(d.hB, d.nd[d.pB]); }
    /** Rotate a whole dimer by angle {@code th} about the vertical (z) axis through its head-midpoint: all beam nodes +
     *  emergence + the clamped-emergence tangent, and both head Cmot frames (bhat/phat/econv/uvecPhys; eup=z unchanged).
     *  geomC is equivariant under this rotation, so re-pinning reproduces the rigidly-rotated head geometry. */
    static void rotateDimerZ(Dimer d, double th) {
        double cx = 0.5 * (d.hA.xF8[0] + d.hB.xF8[0]), cy = 0.5 * (d.hA.xF8[1] + d.hB.xF8[1]);
        double c = Math.cos(th), s = Math.sin(th);
        for (int j = 0; j <= d.NF; j++) rotZpt(d.nd[j], cx, cy, c, s);
        rotZpt(d.E, cx, cy, c, s); rotZvec(d.g4Tan, c, s);
        for (Cmot h : new Cmot[]{ d.hA, d.hB }) { rotZvec(h.bhat, c, s); rotZvec(h.phat, c, s); rotZvec(h.econv, c, s); rotZvec(h.uvecPhys, c, s); }
        ExplicitHmmDimer.pinHead(d.hA, d.nd[d.pA]); ExplicitHmmDimer.pinHead(d.hB, d.nd[d.pB]);
    }
    static void rotZpt(double[] p, double cx, double cy, double c, double s) { double x = p[0] - cx, y = p[1] - cy; p[0] = cx + c * x - s * y; p[1] = cy + s * x + c * y; }
    static void rotZvec(double[] v, double c, double s) { double x = v[0], y = v[1]; v[0] = c * x - s * y; v[1] = s * x + c * y; }
    static double[] flat(Dimer d) { double[] fv = new double[3 * (d.NF + 1) + 4]; int i = 0;
        for (int j = 0; j <= d.NF; j++) for (int k = 0; k < 3; k++) fv[i++] = d.nd[j][k];
        fv[i++] = d.hA.phi; fv[i++] = d.hA.psi; fv[i++] = d.hB.phi; fv[i++] = d.hB.psi; return fv; }
    static double maxMove(double[] prev, Dimer d) { double[] c = flat(d); double mx = 0; for (int i = 0; i < c.length; i++) mx = Math.max(mx, Math.abs(c[i] - prev[i])); return mx; }
    static double lsSlope(List<Double> xsT, List<Double> ysX, int i0) {
        int n = xsT.size(); if (n - i0 < 3) return Double.NaN; double sx = 0, sy = 0, sxx = 0, sxy = 0; int m = 0;
        for (int i = i0; i < n; i++) { double x = xsT.get(i), y = ysX.get(i); sx += x; sy += y; sxx += x * x; sxy += x * y; m++; }
        double den = m * sxx - sx * sx; return Math.abs(den) < 1e-30 ? Double.NaN : (m * sxy - sx * sy) / den;
    }
    static double[] bootCI(List<Double> v) {
        if (v.size() < 2) return new double[]{ Double.NaN, Double.NaN };
        Random rng = new Random(12345); int B = 2000, n = v.size(); double[] bt = new double[B];
        for (int b = 0; b < B; b++) { double s = 0; for (int i = 0; i < n; i++) s += v.get(rng.nextInt(n)); bt[b] = s / n; }
        java.util.Arrays.sort(bt); return new double[]{ bt[(int) (0.025 * B)], bt[(int) (0.975 * B)] };
    }
    static double frac(long a, long b) { return (a + b) > 0 ? (double) a / (a + b) : Double.NaN; }
    static double mean(List<Double> v) { if (v.isEmpty()) return Double.NaN; double s = 0; for (double x : v) s += x; return s / v.size(); }
    static double median(List<Double> v) { if (v.isEmpty()) return Double.NaN; List<Double> s = new ArrayList<>(v); java.util.Collections.sort(s); return s.get(s.size() / 2); }
    static double pctl(List<Double> v, double q) { if (v.isEmpty()) return Double.NaN; List<Double> s = new ArrayList<>(v); java.util.Collections.sort(s);
        int idx = (int) Math.floor(q * (s.size() - 1)); return s.get(Math.max(0, Math.min(s.size() - 1, idx))); }
    static double sd(List<Double> v) { if (v.size() < 2) return Double.NaN; double m = mean(v), s = 0; for (double x : v) s += (x - m) * (x - m); return Math.sqrt(s / (v.size() - 1)); }

    // ============================ viewer frames ============================
    static final class FrameOut {
        Path dir; int n = 0;
        FrameOut(String d) throws IOException { dir = Path.of(d); Files.createDirectories(dir); }
        void write(EScene sc, double t) throws IOException {
            Glide2D G = sc.G; FilamentStore f = G.fil; StringBuilder s = new StringBuilder();
            s.append(String.format(Locale.US, "{\"frame\":%d,\"t\":%.6f,\"bounds\":{\"xDim\":4,\"yDim\":4,\"zDim\":4},", n, t));
            s.append("\"segments\":[");
            for (int k = 0; k < G.nSeg; k++) { double half = 0.5 * f.segLength.get(k);
                double[] c = { f.coordX(k), f.coordY(k), f.coordZ(k) }, u = { f.uVecX(k), f.uVecY(k), f.uVecZ(k) };
                double[] e1 = sub(c, scl(u, half)), e2 = add(c, scl(u, half)); if (k > 0) s.append(',');
                s.append(String.format(Locale.US, "{\"id\":%d,\"end1\":[%.5f,%.5f,%.5f],\"end2\":[%.5f,%.5f,%.5f],\"r\":%.5f,\"notADPRatio\":1.0,\"cofilinCount\":0,\"isBarbedEnd\":%b}",
                        k, e1[0], e1[1], e1[2], e2[0], e2[1], e2[2], FIL_R, f.end2NbrSlot.get(k) < 0)); }
            int sid = G.nSeg;
            for (int i = 0; i < sc.nDim; i++) { Dimer d = sc.dim[i];
                sid = beamSeg(s, sid, d.nd[0], d.nd[d.Ms], 0.2);
                sid = beamSeg(s, sid, d.nd[d.Ms], d.nd[d.pA], 0.0);
                sid = beamSeg(s, sid, d.nd[d.Ms], d.nd[d.pB], 1.0);
            }
            s.append("],\"nodes\":[");
            for (int i = 0; i < sc.nDim; i++) { if (i > 0) s.append(',');
                s.append(String.format(Locale.US, "{\"center\":[%.5f,%.5f,%.5f],\"r\":0.007}", sc.dim[i].E[0], sc.dim[i].E[1], sc.dim[i].E[2])); }
            s.append("],\"myosins\":[");
            for (int i = 0; i < sc.nDim; i++) { Dimer d = sc.dim[i];
                if (i > 0) s.append(',');
                myoEntry(s, d.hA, d, d.pA, G, 2 * i); s.append(','); myoEntry(s, d.hB, d, d.pB, G, 2 * i + 1);
            }
            s.append("]}");
            Files.writeString(dir.resolve(String.format("frame_%06d.json", n++)), s.toString());
        }
    }
    static int beamSeg(StringBuilder s, int id, double[] p, double[] q, double col) {
        s.append(String.format(Locale.US, ",{\"id\":%d,\"end1\":[%.5f,%.5f,%.5f],\"end2\":[%.5f,%.5f,%.5f],\"r\":0.0022,\"notADPRatio\":%.2f,\"cofilinCount\":0,\"motorSeg\":true}",
                id, p[0], p[1], p[2], q[0], q[1], q[2], col));
        return id + 1;
    }
    static void myoEntry(StringBuilder s, Cmot h, Dimer d, int pivot, Glide2D G, int m) {
        double[] P = d.nd[pivot], C = h.C, head = h.xH, tip = h.xF8;
        String state = G.mot.boundSeg.get(m) >= 0 ? NUC[G.mot.nucleotideState.get(m)] : "NONE";
        boolean bound = G.mot.boundSeg.get(m) >= 0;
        s.append(String.format(Locale.US,
                "{\"id\":%d,\"bound\":%b,\"rod\":{\"end1\":[%.5f,%.5f,%.5f],\"end2\":[%.5f,%.5f,%.5f],\"r\":0.0016,\"invisible\":true},"
              + "\"lever\":{\"end1\":[%.5f,%.5f,%.5f],\"end2\":[%.5f,%.5f,%.5f],\"r\":0.0022},"
              + "\"motor\":{\"end1\":[%.5f,%.5f,%.5f],\"end2\":[%.5f,%.5f,%.5f],\"r\":0.0045,\"state\":\"%s\"}}",
                m, bound, P[0], P[1], P[2], d.nd[d.Ms][0], d.nd[d.Ms][1], d.nd[d.Ms][2],
                P[0], P[1], P[2], C[0], C[1], C[2], head[0], head[1], head[2], tip[0], tip[1], tip[2], state));
    }

    static boolean has(String[] a, String f) { for (String s : a) if (s.equals(f)) return true; return false; }
    static double argD(String[] a, String f, double dv) { for (int i = 0; i < a.length - 1; i++) if (a[i].equals(f)) return Double.parseDouble(a[i + 1]); return dv; }
    static String argStr(String[] a, String f, String dv) { for (int i = 0; i < a.length - 1; i++) if (a[i].equals(f)) return a[i + 1]; return dv; }
    private ExplicitHmmDimerGlidingHarness() {}
}
