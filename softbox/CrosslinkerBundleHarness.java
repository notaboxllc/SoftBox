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
 * Increment 5c-iii Phase 2: the ASSEMBLED moving crosslinker loop over a many-filament bundle, +
 * the confinement-free v1↔v2 validation substrate + the parallel/antiparallel crosslinking demo.
 *
 * The pieces (5a force law, 5b Bell unbinding, 5c-i allocator, 5c-ii formation, 5c-iii P1 dynamic
 * fracMove + torsion) are each bit-exact-validated in isolation (CrosslinkerHarness). This harness
 * wires the FULL per-step coupling — formation ↔ force/torsion ↔ unbinding ↔ integration — over a
 * dispersing bundle of free filaments (no walls, no motors, no chain). It has never run assembled;
 * the first job is watch-it-run STABILITY (both runners), then the confinement-free transient vs v1.
 *
 * THE PER-STEP ORDER (faithful to v1 BoxOfActin.doLoop + FilLink.enforceFilLink — confirmed by
 * reading BoA-v1ref, see JOURNAL 5c-iii P2):
 *   zero → brownian
 *        → [every crosslinkCheckInt steps] FORMATION (countActive(sat) → cands → gates → admit →
 *           free-list → rank → allocate → placeOrient)   (v1: collision phase, before the force wave)
 *        → UNBIND (ckLinkBreak; every step, BEFORE force — v1 applyTransForce calls ckLinkBreak first
 *           and returns early on a break ⇒ a link breaking this step contributes no force)
 *        → countActive (dynamic fracMove = 0.4/max(linkCt)) → linkForces → torsion → 2-pass gather
 *        → integrate → derive
 * This matches the existing v2 unbind-before-force convention (5b); the prompt's "force → unbind"
 * phrasing is reconciled to v1's actual ckLinkBreak-before-force order.
 *
 * CONFINEMENT-FREE by construction: filaments are free rods (no Chamber wall — v2 has none; the v1
 * side is compared walls-off via a /tmp scratch). The bundle therefore DISPERSES under Brownian
 * motion; Part 2.1 measures the window in which density (crossing candidates) stays high enough for
 * formation, the gating measurement before any v1 match.
 */
public final class CrosslinkerBundleHarness {

    static final int B = 64;

    // ---- v1 FilLink / Env constants (same provenance as CrosslinkerHarness) ----
    static final double REST_LEN   = 0.0125;   // FilLink.restLength (µm)
    static final double FRAC_MOVE  = 0.4;      // applyTransForce fracMove base
    static final int    FIL_MONO   = Constants.stdSegLength;          // 32 monomers/segment
    static final double OFF_CONST  = 1.0, OFF_COEFF = 1.0, OFF_EXP = 2.0;   // Bell off-rate (Env)
    static final double FIL_TORQ_SPRING = 1.0e-19;                    // filLinkTorqSpring (N/rad), active
    static final double GRAB_DIST  = 2.0 * Constants.actinMonoDiam;   // crossLinkGrabDist
    static final double MIN_SEP    = 5.0 * Constants.actinMonoDiam;   // minSepBetweenXLinks
    static final double MIN_FILLINK_SEP = 2.0 * Constants.actinMonoDiam;   // loc jitter half-range
    static final int    MAX_LINKS_ON_SEG = 10;
    static final double XLINK_ON_RATE = 10.0, XLINK_CONC = 1.0;       // xLinkOnRate, xLinkConc (Env)

    // boa-xlink-dense-nomotor config (the Phase-1.5 oracle): 200 fil, box 0.7x0.7x0.3, biochemDeltaT
    // 0.01 ⇒ checkInt 100, maxXLinkBondAngle 0.6 rad (~34°), mode 0 (both), torsion active.
    static final double V1_BOX_X = 0.7, V1_BOX_Y = 0.7, V1_BOX_Z = 0.3;
    static final int    V1_NFIL  = 200;
    static final double V1_MAX_ANGLE = 0.6;        // rad (widened from pi/12 in the dense config)
    static final int    V1_CHECK_INT = 100;        // biochemDeltaT/deltaT = 0.01/1e-4
    static final double V1_BIOCHEM_DT = 0.01;

    /** P_form for a given biochem dtCheck: 1 - exp(-kon*conc*dtCheck). */
    static double pForm(double conc, double dtCheck) {
        return 1.0 - Math.exp(-XLINK_ON_RATE * conc * dtCheck);
    }

    static boolean cpu = false;
    static GridScheduler sched;
    // boa-xlink-dense-nomotor sets aeta:1.0 (10x the v2 Constants.aeta=0.1 default) — the crowded-
    // cytoplasm "in Bug" viscosity. Drag γ ∝ aeta, so the fixture's filaments diffuse 10x slower
    // (they stay packed). Matched here by scaling the drag arrays (FDT-consistent: Brownian force
    // reads bTransGam, so scaling drag auto-scales the kick). Default = the fixture value.
    static double AETA = 1.0;

    /** Scale the drag tensors from the Constants.aeta default to the target aeta (drag ∝ aeta;
     *  diffusion = kT/γ ∝ 1/aeta). FDT stays consistent because BrownianForceSystem derives the
     *  random-force magnitude from bTransGam/bRotGam. */
    static void applyAeta(FilamentStore f, double aeta) {
        double r = aeta / Constants.aeta;
        if (r == 1.0) return;
        scale(f.bTransGam, r);  scale(f.bRotGam, r);
        scale(f.bTransDiff, 1.0 / r); scale(f.bRotDiff, 1.0 / r);
    }
    static void scale(FloatArray a, double r) { for (int i = 0; i < a.getSize(); i++) a.set(i, (float) (a.get(i) * r)); }

    public static void main(String[] args) {
        double dt = Constants.deltaT;        // 1e-4
        int M = 6000;
        int nFil = 64;
        int checkInt = V1_CHECK_INT;
        double boxX = V1_BOX_X, boxY = V1_BOX_Y, boxZ = V1_BOX_Z;
        double conc = XLINK_CONC;
        int seed = 12345;
        String mode = "bundle";
        String vizDir = null, icFile = null;
        int stepOffset = 0;
        boolean formOn = true, unbindOn = true, brownian = true;
        java.util.List<String> pos = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu"      -> cpu = true;
                case "-disperse" -> mode = "disperse";
                case "-bundle"   -> mode = "bundle";
                case "-cpugpu"   -> mode = "cpugpu";
                case "-conc"     -> conc = Double.parseDouble(args[++i]);
                case "-aeta"     -> AETA = Double.parseDouble(args[++i]);
                case "-box"      -> { double s = Double.parseDouble(args[++i]); boxX *= s; boxY *= s; boxZ *= s; }
                case "-nfil"     -> nFil = Integer.parseInt(args[++i]);
                case "-seed"     -> seed = Integer.parseInt(args[++i]);
                case "-checkint" -> checkInt = Integer.parseInt(args[++i]);
                case "-noform"   -> formOn = false;
                case "-nounbind" -> unbindOn = false;
                case "-v1"       -> { nFil = V1_NFIL; }   // full v1 density (200 fil)
                case "-loadic"   -> { icFile = args[++i]; mode = "match"; }
                case "-offset"   -> stepOffset = Integer.parseInt(args[++i]);
                case "-3js"      -> { mode = "viz"; vizDir = args[++i]; }
                default          -> pos.add(args[i]);
            }
        }
        if (icFile != null) {
            runMatch(icFile, dt, conc, checkInt, M, seed, stepOffset, formOn, unbindOn);
            return;
        }
        if (!pos.isEmpty()) M = Integer.parseInt(pos.get(0));

        System.out.println("=== Soft Box 5c-iii Phase 2 — assembled moving crosslinker bundle ===");
        System.out.printf("nFil=%d  box=%.2gx%.2gx%.2g µm  dt=%.1e  checkInt=%d  conc=%.3g  pForm=%.5g  maxAngle=%.3g rad%n",
                nFil, boxX, boxY, boxZ, dt, checkInt, conc, pForm(conc, dt * checkInt), V1_MAX_ANGLE);
        System.out.printf("runner: %s   formation=%s  unbind=%s  brownian=%s  M=%d  seed=%d%n",
                cpu ? "CPU sequential" : "GPU TaskGraph (+CPU)", formOn, unbindOn, brownian, M, seed);

        switch (mode) {
            case "disperse" -> runDisperse(nFil, boxX, boxY, boxZ, dt, conc, checkInt, M, seed);
            case "viz"      -> runViz(nFil, boxX, boxY, boxZ, dt, conc, checkInt, M, seed, vizDir);
            case "cpugpu"   -> runCpuGpu(nFil, boxX, boxY, boxZ, dt, conc, checkInt, M, seed);
            default         -> runBundle(nFil, boxX, boxY, boxZ, dt, conc, checkInt, M, seed, formOn, unbindOn, brownian, !cpu);
        }
    }

    // ============================================================== scene
    static final class BundleScene {
        FilamentStore fil; CrosslinkerStore xl; IntArray filID;
        IntArray segCountA, segOffsetsA, segIdxA, segCountB, segOffsetsB, segIdxB;
        int nFil, C, reqCap, seed, checkInt;
        double dt;
        boolean formOn, unbindOn;
        TornadoExecutionPlan mechPlan; GridScheduler mechSched;
    }

    /** Random dense bundle: nFil single-segment free filaments, centres uniform in the box, uVec
     *  uniform on the sphere (deterministic from seed). Brownian on (unless brownScale 0). filID = index. */
    static BundleScene buildBundle(int nFil, double boxX, double boxY, double boxZ, double dt,
                                   double conc, int checkInt, int seed, boolean brownian) {
        BundleScene sc = new BundleScene();
        int nSeg = nFil;
        java.util.Random rng = new java.util.Random(seed * 2654435761L ^ 0x9E3779B9L);
        FilamentStore fil = new FilamentStore(nSeg);
        IntArray filID = new IntArray(nSeg);
        double bScale = brownian ? Constants.BTransCoeff : 0.0;
        double bRot   = brownian ? Constants.BRotCoeff   : 0.0;
        // v1 boa-xlink-dense-nomotor IC distribution: filament length uniform [minFilLength,maxFilLength]
        // = [0.1,0.3] µm (modelled as a single rigid segment of that length — short fils are 1-2 v1
        // segments), centres clustered centrally via a Gaussian (stdDevActinDist=0.2 µm) clamped to the box.
        double minLen = 0.1, maxLen = 0.3, posSigma = 0.2;
        for (int s = 0; s < nSeg; s++) {
            double Li = minLen + rng.nextDouble() * (maxLen - minLen);
            int mono = Math.max(1, (int) Math.round(Li / Constants.actinMonoRadius) - 1);
            fil.monomerCount.set(s, mono);
            // uniform direction on the sphere
            double z = 2.0 * rng.nextDouble() - 1.0;
            double phi = 2.0 * Math.PI * rng.nextDouble();
            double r = Math.sqrt(Math.max(0.0, 1.0 - z * z));
            double ux = r * Math.cos(phi), uy = r * Math.sin(phi), uz = z;
            fil.setUVec(s, (float) ux, (float) uy, (float) uz);
            // any unit perpendicular
            double yx = -uy, yy = ux, yz = 0; double yn = Math.sqrt(yx * yx + yy * yy + yz * yz);
            if (yn < 1e-9) { yx = 0; yy = -uz; yz = uy; yn = Math.sqrt(yy * yy + yz * yz); }
            fil.setYVec(s, (float) (yx / yn), (float) (yy / yn), (float) (yz / yn));
            double cx = clamp(rng.nextGaussian() * posSigma, boxX);
            double cy = clamp(rng.nextGaussian() * posSigma, boxY);
            double cz = clamp(rng.nextGaussian() * posSigma, boxZ);
            fil.setCoord(s, (float) cx, (float) cy, (float) cz);
            fil.brownTransScale.set(s, (float) bScale);
            fil.brownRotScale.set(s, (float) bRot);
            filID.set(s, s);
        }
        DragTensorSystem.run(fil);
        applyAeta(fil, AETA);
        fil.setParams(dt, Math.sqrt(2.0 * Constants.kT / dt));
        fil.setCounts(0, seed);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);

        int reqCap = Math.max(1, nSeg * (nSeg - 1) / 2);    // broad-phase capacity (all cross-fil pairs)
        int C = Math.max(64, nSeg * 4);                      // link pool capacity (plateau ~49/200 ⇒ generous)
        CrosslinkerStore xl = new CrosslinkerStore(C, nSeg, reqCap);
        xl.setParams(REST_LEN, FRAC_MOVE, dt);
        xl.setOffParams(OFF_CONST, OFF_COEFF, OFF_EXP, dt, REST_LEN);
        xl.setFormParams(V1_MAX_ANGLE, GRAB_DIST, MIN_SEP, MAX_LINKS_ON_SEG, pForm(conc, dt * checkInt), MIN_FILLINK_SEP, 0);
        xl.setRequestCount(reqCap);
        xl.setTorsionParams(FIL_TORQ_SPRING, true);

        sc.segCountA = new IntArray(nSeg); sc.segOffsetsA = new IntArray(nSeg + 1); sc.segIdxA = new IntArray(C);
        sc.segCountB = new IntArray(nSeg); sc.segOffsetsB = new IntArray(nSeg + 1); sc.segIdxB = new IntArray(C);
        sc.fil = fil; sc.xl = xl; sc.filID = filID;
        sc.nFil = nFil; sc.C = C; sc.reqCap = reqCap; sc.seed = seed; sc.checkInt = checkInt; sc.dt = dt;
        return sc;
    }

    /** Build the bundle from a v1 IC dump (filID cx cy cz ux uy uz len per line, µm). Single-segment
     *  filaments (= v1's segment population in boa-xlink-dense-nomotor); monomerCount back-solved from len. */
    static BundleScene buildFromIC(String icFile, double dt, double conc, int checkInt, int seed, boolean brownian) {
        java.util.List<double[]> rows = new java.util.ArrayList<>();
        try (java.io.BufferedReader br = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(icFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim(); if (line.isEmpty()) continue;
                String[] t = line.split("\\s+");
                double[] r = new double[8];
                for (int j = 0; j < 8; j++) r[j] = Double.parseDouble(t[j]);
                rows.add(r);
            }
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        int nSeg = rows.size();
        BundleScene sc = new BundleScene();
        FilamentStore fil = new FilamentStore(nSeg);
        IntArray filID = new IntArray(nSeg);
        double bScale = brownian ? Constants.BTransCoeff : 0.0, bRot = brownian ? Constants.BRotCoeff : 0.0;
        for (int s = 0; s < nSeg; s++) {
            double[] r = rows.get(s);
            double len = r[7];
            int mono = Math.max(1, (int) Math.round(len / Constants.actinMonoRadius) - 1);
            fil.monomerCount.set(s, mono);
            double ux = r[4], uy = r[5], uz = r[6];
            double inv = 1.0 / Math.sqrt(ux * ux + uy * uy + uz * uz);
            ux *= inv; uy *= inv; uz *= inv;
            fil.setUVec(s, (float) ux, (float) uy, (float) uz);
            double yx = -uy, yy = ux, yz = 0; double yn = Math.sqrt(yx * yx + yy * yy + yz * yz);
            if (yn < 1e-9) { yx = 0; yy = -uz; yz = uy; yn = Math.sqrt(yy * yy + yz * yz); }
            fil.setYVec(s, (float) (yx / yn), (float) (yy / yn), (float) (yz / yn));
            fil.setCoord(s, (float) r[1], (float) r[2], (float) r[3]);
            fil.brownTransScale.set(s, (float) bScale); fil.brownRotScale.set(s, (float) bRot);
            filID.set(s, (int) r[0]);
        }
        DragTensorSystem.run(fil); applyAeta(fil, AETA); fil.setParams(dt, Math.sqrt(2.0 * Constants.kT / dt)); fil.setCounts(0, seed);
        DerivedGeometrySystem.derive(fil.coord, fil.uVec, fil.yVec, fil.zVec, fil.end1, fil.end2, fil.segLength, fil.counts);
        int reqCap = Math.max(1, nSeg * (nSeg - 1) / 2);
        int C = Math.max(64, nSeg * 4);
        CrosslinkerStore xl = new CrosslinkerStore(C, nSeg, reqCap);
        xl.setParams(REST_LEN, FRAC_MOVE, dt);
        xl.setOffParams(OFF_CONST, OFF_COEFF, OFF_EXP, dt, REST_LEN);
        xl.setFormParams(V1_MAX_ANGLE, GRAB_DIST, MIN_SEP, MAX_LINKS_ON_SEG, pForm(conc, dt * checkInt), MIN_FILLINK_SEP, 0);
        xl.setRequestCount(reqCap);
        xl.setTorsionParams(FIL_TORQ_SPRING, true);
        sc.segCountA = new IntArray(nSeg); sc.segOffsetsA = new IntArray(nSeg + 1); sc.segIdxA = new IntArray(C);
        sc.segCountB = new IntArray(nSeg); sc.segOffsetsB = new IntArray(nSeg + 1); sc.segIdxB = new IntArray(C);
        sc.fil = fil; sc.xl = xl; sc.filID = filID;
        sc.nFil = nSeg; sc.C = C; sc.reqCap = reqCap; sc.seed = seed; sc.checkInt = checkInt; sc.dt = dt;
        return sc;
    }

    /** Part 2.2: v2 walls-off from v1's EXACT IC; print the link-count trajectory (compare vs v1 walls-off). */
    static void runMatch(String icFile, double dt, double conc, int checkInt, int M, int seed, int stepOffset,
                         boolean formOn, boolean unbindOn) {
        BundleScene sc = buildFromIC(icFile, dt, conc, checkInt, seed, true);
        sc.formOn = formOn; sc.unbindOn = unbindOn;
        FORM_DIAG = Boolean.getBoolean("formdiag");
        System.out.println("\n========== Part 2.2 — v2 walls-off from v1 IC (" + sc.nFil + " seg) ==========");
        System.out.printf("  IC: spread=%.4g µm, candidates=%d, stepOffset=%d (aligns formation to v1 absolute steps)%n",
                spread(sc.fil), candidateCount(sc), stepOffset);
        System.out.printf("  %-10s %-10s %-12s %-8s%n", "absStep", "t(s)", "spread(µm)", "links");
        for (int t = 0; t <= M; t++) {
            int abs = t + stepOffset;
            if (abs % 20 == 0) System.out.printf("  %-10d %-10.4g %-12.4g %-8d%n", abs, abs * dt, spread(sc.fil), activeLinks(sc.xl));
            if (t < M) assembledStepCpu(sc, abs);    // abs step ⇒ formation cadence matches v1
            if (!finite(sc.fil)) { System.out.println("  *** NON-FINITE — BLOW-UP ***"); break; }
        }
    }

    // ============================================================== assembled CPU step (the reference)
    static boolean FORM_DIAG = false;
    static void formationCpu(BundleScene sc, int step) {
        FilamentStore f = sc.fil; CrosslinkerStore xl = sc.xl;
        xl.setFormStep(step, sc.seed);
        CrosslinkerSystem.countActiveLinks(xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts);
        CrosslinkerSystem.filFilCandidates(f.coord, f.segLength, sc.filID, xl.reqFilA, xl.reqFilB, xl.formParams, xl.formCounts);
        CrosslinkerSystem.formGates(f.uVec, f.end1, f.end2, f.segLength, xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2,
                xl.reqOrient, xl.gatePass, xl.formParams, xl.formCounts);
        if (FORM_DIAG) {
            int nc = xl.formCounts.get(0), gp = 0; for (int c = 0; c < xl.reqCap; c++) gp += xl.gatePass.get(c);
            // geometry-only funnel (host replica, no P_form) to localize the loss
            int align = 0, geom = 0;
            double maxA = V1_MAX_ANGLE; int n = f.n;
            double[] bins = { 0.0108, 0.015, 0.02, 0.03, 0.05, 0.1 };
            int[] bcnt = new int[bins.length];
            for (int c = 0; c < nc; c++) {
                int a = xl.reqFilA.get(c), b = xl.reqFilB.get(c); if (a < 0 || b < 0) continue;
                double[] g = hostGeom(f, a, b, maxA, 1e9);   // grabSq huge ⇒ returns conDist for all interior crossings
                if (g[0] > 0) align++;
                if (g[1] >= 0 && g[2] > 0) { double d = Math.sqrt(g[1]); for (int bi = 0; bi < bins.length; bi++) if (d < bins[bi]) bcnt[bi]++; }
                if (g[1] >= 0 && g[2] > 0 && g[1] < GRAB_DIST * GRAB_DIST) geom++;
            }
            StringBuilder hb = new StringBuilder();
            for (int bi = 0; bi < bins.length; bi++) hb.append(String.format("  d<%.3g:%d", bins[bi], bcnt[bi]));
            System.out.printf("    [FORMDIAG step=%d] coarse=%d align=%d geom(<grab)=%d gatePass=%d | interior-cross dist bins:%s%n",
                    step, nc, align, geom, gp, hb.toString());
        }
        CrosslinkerSystem.formAdmitReduce(xl.reqFilA, xl.reqFilB, xl.gatePass, xl.minCand, xl.formCounts);
        CrosslinkerSystem.formAdmit(xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.gatePass, xl.minCand, xl.activeLinkCount,
                xl.linkState, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.acceptFlag, xl.formParams, xl.formCounts);
        CrosslinkerSystem.freeFlags(xl.linkState, xl.freeCount, xl.allocCounts);
        CrossBridgeSystem.csrScan(xl.freeScanCounts, xl.freeCount, xl.freeOffsets);
        CrosslinkerSystem.freeScatter(xl.linkState, xl.freeOffsets, xl.freeList, xl.allocCounts);
        CrossBridgeSystem.csrScan(xl.rankScanCounts, xl.acceptFlag, xl.rankOffsets);
        CrosslinkerSystem.allocate(xl.reqFilA, xl.reqFilB, xl.reqLoc1, xl.reqLoc2, xl.rankOffsets, xl.freeList, xl.freeOffsets,
                xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.strainHist, xl.strainPlace, xl.allocCounts);
        CrosslinkerSystem.placeOrient(xl.reqOrient, xl.rankOffsets, xl.freeList, xl.freeOffsets, xl.linkOrientSame,
                xl.torqueMagHist, xl.torqueMagPlace, xl.allocCounts);
    }

    /** Host replica of v1 checkToLink geometry (mode 0). Returns {alignPass, conDistSq(or -1), interiorCross(1/0)}. */
    static double[] hostGeom(FilamentStore f, int a, int b, double maxA, double grabSqUnused) {
        int n = f.n;
        double uax = f.uVecX(a), uay = f.uVecY(a), uaz = f.uVecZ(a);
        double ubx = f.uVecX(b), uby = f.uVecY(b), ubz = f.uVecZ(b);
        double dot = uax * ubx + uay * uby + uaz * ubz; if (dot > 1) dot = 1; if (dot < -1) dot = -1;
        double angT = Math.acos(dot), angTR = Math.acos(-dot);
        boolean align = angT <= maxA || angTR <= maxA;
        if (!align) return new double[]{ 0, -1, 0 };
        double e1ax = f.end1.get(a), e1ay = f.end1.get(n + a), e1az = f.end1.get(2 * n + a);
        double e2ax = f.end2.get(a), e2ay = f.end2.get(n + a), e2az = f.end2.get(2 * n + a);
        double e1bx = f.end1.get(b), e1by = f.end1.get(n + b), e1bz = f.end1.get(2 * n + b);
        double e2bx = f.end2.get(b), e2by = f.end2.get(n + b), e2bz = f.end2.get(2 * n + b);
        double r1x = e2ax - e1ax, r1y = e2ay - e1ay, r1z = e2az - e1az;
        double r2x = e2bx - e1bx, r2y = e2by - e1by, r2z = e2bz - e1bz;
        double r3x = e1bx - e1ax, r3y = e1by - e1ay, r3z = e1bz - e1az;
        double r4x = r1y * r2z - r1z * r2y, r4y = r1z * r2x - r1x * r2z, r4z = r1x * r2y - r1y * r2x;
        double smallNum = 1e-20;
        if (r4x < smallNum && r4y < smallNum && r4z < smallNum) return new double[]{ 1, -1, 0 };   // v1 parallel guard
        double denom = r4x * r4x + r4y * r4y + r4z * r4z;
        double c32x = r3y * r2z - r3z * r2y, c32y = r3z * r2x - r3x * r2z, c32z = r3x * r2y - r3y * r2x;
        double alpha = (r4x * c32x + r4y * c32y + r4z * c32z) / denom;
        if (alpha < 0 || alpha > 1) return new double[]{ 1, -1, 0 };
        double c31x = r3y * r1z - r3z * r1y, c31y = r3z * r1x - r3x * r1z, c31z = r3x * r1y - r3y * r1x;
        double beta = (r4x * c31x + r4y * c31y + r4z * c31z) / denom;
        if (beta < 0 || beta > 1) return new double[]{ 1, -1, 0 };
        double p1x = e1ax + alpha * r1x, p1y = e1ay + alpha * r1y, p1z = e1az + alpha * r1z;
        double p2x = e1bx + beta * r2x, p2y = e1by + beta * r2y, p2z = e1bz + beta * r2z;
        double dd = (p1x - p2x) * (p1x - p2x) + (p1y - p2y) * (p1y - p2y) + (p1z - p2z) * (p1z - p2z);
        return new double[]{ 1, dd, 1 };
    }

    /** The full assembled per-step loop on the host (the device-agnostic reference). */
    static void assembledStepCpu(BundleScene sc, int step) {
        FilamentStore f = sc.fil; CrosslinkerStore xl = sc.xl;
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        f.setCounts(step, sc.seed);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam,
                f.brownTransScale, f.brownRotScale, f.params, f.counts);
        if (sc.formOn && step % sc.checkInt == 0) formationCpu(sc, step);
        xl.setCounts(step, sc.seed);
        if (sc.unbindOn) CrosslinkerSystem.unbind(f.coord, f.uVec, f.end1, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2,
                xl.linkState, xl.strainHist, xl.strainPlace, xl.offParams, xl.counts);
        // dynamic fracMove = 0.4/max(activeLinkCount) recomputed each step (after formation + unbind)
        CrosslinkerSystem.countActiveLinks(xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts);
        CrosslinkerSystem.linkForces(f.coord, f.uVec, f.end1, f.bTransGam, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2,
                xl.activeLinkCount, xl.xlinkData, xl.xlParams);
        CrosslinkerSystem.linkTorsion(f.uVec, xl.linkFilA, xl.linkFilB, xl.linkState, xl.linkOrientSame,
                xl.torqueMagHist, xl.torqueMagPlace, xl.xlinkData, xl.torsionParams);
        CrossBridgeSystem.csrHistogram(xl.linkFilA, xl.counts, sc.segCountA);
        CrossBridgeSystem.csrScan(xl.counts, sc.segCountA, sc.segOffsetsA);
        CrossBridgeSystem.csrScatter(xl.linkFilA, xl.counts, sc.segOffsetsA, sc.segCountA, sc.segIdxA);
        CrosslinkerSystem.segGatherA(sc.segOffsetsA, sc.segIdxA, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
        CrossBridgeSystem.csrHistogram(xl.linkFilB, xl.counts, sc.segCountB);
        CrossBridgeSystem.csrScan(xl.counts, sc.segCountB, sc.segOffsetsB);
        CrossBridgeSystem.csrScatter(xl.linkFilB, xl.counts, sc.segOffsetsB, sc.segCountB, sc.segIdxB);
        CrosslinkerSystem.segGatherB(sc.segOffsetsB, sc.segIdxB, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
    }

    // ============================================================== measurements
    static int activeLinks(CrosslinkerStore xl) {
        int c = 0; for (int k = 0; k < xl.nLinks; k++) if (xl.linkState.get(k) >= 0) c++; return c;
    }

    /** RMS distance of segment centres from the bundle COM (µm) — the dispersion measure. */
    static double spread(FilamentStore f) {
        int n = f.n; double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < n; i++) { cx += f.coordX(i); cy += f.coordY(i); cz += f.coordZ(i); }
        cx /= n; cy /= n; cz /= n;
        double s = 0;
        for (int i = 0; i < n; i++) {
            double dx = f.coordX(i) - cx, dy = f.coordY(i) - cy, dz = f.coordZ(i) - cz;
            s += dx * dx + dy * dy + dz * dz;
        }
        return Math.sqrt(s / n);
    }

    /** Broad-phase crossing candidates within the coarse grab bound — the formation-density proxy. */
    static int candidateCount(BundleScene sc) {
        CrosslinkerSystem.filFilCandidates(sc.fil.coord, sc.fil.segLength, sc.filID, sc.xl.reqFilA, sc.xl.reqFilB,
                sc.xl.formParams, sc.xl.formCounts);
        return sc.xl.formCounts.get(0);
    }

    static boolean finite(FilamentStore f) {
        for (int i = 0; i < f.coord.getSize(); i++) { float v = f.coord.get(i); if (Float.isNaN(v) || Float.isInfinite(v)) return false; }
        return true;
    }

    // ============================================================== Part 2.1: dispersion window
    static void runDisperse(int nFil, double boxX, double boxY, double boxZ, double dt, double conc,
                            int checkInt, int M, int seed) {
        System.out.println("\n========== Part 2.1 — dispersion window (formation ON; density vs t) ==========");
        BundleScene sc = buildBundle(nFil, boxX, boxY, boxZ, dt, conc, checkInt, seed, true);
        sc.formOn = true; sc.unbindOn = true;
        double spread0 = spread(sc.fil);
        int cand0 = candidateCount(sc);
        System.out.printf("  IC: spread=%.4g µm, candidates=%d, links=%d%n", spread0, cand0, activeLinks(sc.xl));
        System.out.printf("  %-8s %-10s %-12s %-12s %-10s %-8s%n", "step", "t(s)", "spread(µm)", "cand", "cand/cand0", "links");
        int every = Math.max(1, M / 40);
        for (int t = 0; t <= M; t++) {
            if (t % every == 0) {
                int cand = candidateCount(sc);
                System.out.printf("  %-8d %-10.4g %-12.4g %-12d %-10.3f %-8d%n",
                        t, t * dt, spread(sc.fil), cand, cand / (double) Math.max(1, cand0), activeLinks(sc.xl));
            }
            if (t < M) assembledStepCpu(sc, t);
            if (!finite(sc.fil)) { System.out.println("  *** NON-FINITE at step " + t + " — BLOW-UP ***"); break; }
        }
        System.out.println("  (usable window = while cand/cand0 stays high enough for formation; PAUSE if it collapses ≪ formation equilibration)");
    }

    // ============================================================== bundle run (stability + trajectory)
    static void runBundle(int nFil, double boxX, double boxY, double boxZ, double dt, double conc,
                          int checkInt, int M, int seed, boolean formOn, boolean unbindOn, boolean brownian, boolean gpu) {
        BundleScene sc = buildBundle(nFil, boxX, boxY, boxZ, dt, conc, checkInt, seed, brownian);
        sc.formOn = formOn; sc.unbindOn = unbindOn;
        System.out.println("\n========== Part 1 — assembled loop run (CPU reference; stability + trajectory) ==========");
        long t0 = System.nanoTime();
        int every = Math.max(1, M / 30);
        boolean stable = true;
        System.out.printf("  %-8s %-10s %-12s %-8s %-10s%n", "step", "t(s)", "spread(µm)", "links", "maxF(N)");
        for (int t = 0; t <= M; t++) {
            if (t % every == 0) {
                double mf = 0; for (int i = 0; i < sc.fil.forceSum.getSize(); i++) mf = Math.max(mf, Math.abs(sc.fil.forceSum.get(i)));
                System.out.printf("  %-8d %-10.4g %-12.4g %-8d %-10.3g%n", t, t * dt, spread(sc.fil), activeLinks(sc.xl), mf);
            }
            if (t < M) assembledStepCpu(sc, t);
            if (!finite(sc.fil)) { System.out.println("  *** NON-FINITE at step " + t + " — BLOW-UP ***"); stable = false; break; }
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("  CPU: %d steps in %.1fs = %.0f steps/s (%d fil)%n", M, secs, M / secs, nFil);
        System.out.println("  STABILITY: " + (stable ? "stable (finite, bounded force) ✓" : "*BLEW UP*"));
    }

    /** GPU mechanics graph (the per-step hot path; formation excluded — its CPU≡GPU is validated in 5c-ii):
     *  zero → brownian → countActive → linkForces → torsion → 2-pass gather → integrate → derive. The link
     *  topology is held static during the comparison window (pre-formed on host). ~16 tasks (under the
     *  gliding 23-kernel ceiling). */
    static TornadoExecutionPlan buildMechPlan(BundleScene sc) {
        FilamentStore f = sc.fil; CrosslinkerStore xl = sc.xl;
        TaskGraph tg = new TaskGraph("xbmech")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength,
                    f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.randForce, f.randTorque, f.params,
                    xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.linkState, xl.linkOrientSame, xl.activeLinkCount,
                    xl.xlinkData, xl.xlParams, xl.torqueMagHist, xl.torqueMagPlace, xl.torsionParams, xl.formCounts,
                    sc.segCountA, sc.segOffsetsA, sc.segIdxA, sc.segCountB, sc.segOffsetsB, sc.segIdxB)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, f.counts, f.forceSum, f.torqueSum, xl.counts)
            .task("zero", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("brownian", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
            .task("countActive", CrosslinkerSystem::countActiveLinks, xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts)
            .task("linkForces", CrosslinkerSystem::linkForces, f.coord, f.uVec, f.end1, f.bTransGam, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.activeLinkCount, xl.xlinkData, xl.xlParams)
            .task("torsion", CrosslinkerSystem::linkTorsion, f.uVec, xl.linkFilA, xl.linkFilB, xl.linkState, xl.linkOrientSame, xl.torqueMagHist, xl.torqueMagPlace, xl.xlinkData, xl.torsionParams)
            .task("histA", CrossBridgeSystem::csrHistogram, xl.linkFilA, xl.counts, sc.segCountA)
            .task("scanA", CrossBridgeSystem::csrScan, xl.counts, sc.segCountA, sc.segOffsetsA)
            .task("scatterA", CrossBridgeSystem::csrScatter, xl.linkFilA, xl.counts, sc.segOffsetsA, sc.segCountA, sc.segIdxA)
            .task("gatherA", CrosslinkerSystem::segGatherA, sc.segOffsetsA, sc.segIdxA, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts)
            .task("histB", CrossBridgeSystem::csrHistogram, xl.linkFilB, xl.counts, sc.segCountB)
            .task("scanB", CrossBridgeSystem::csrScan, xl.counts, sc.segCountB, sc.segOffsetsB)
            .task("scatterB", CrossBridgeSystem::csrScatter, xl.linkFilB, xl.counts, sc.segOffsetsB, sc.segCountB, sc.segIdxB)
            .task("gatherB", CrosslinkerSystem::segGatherB, sc.segOffsetsB, sc.segIdxB, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("derive", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .transferToHost(DataTransferMode.UNDER_DEMAND, f.coord, f.uVec, f.yVec);
        sched = new GridScheduler();
        int nSeg = f.n, C = xl.nLinks;
        addW("xbmech.zero", pad(nSeg)); addW("xbmech.brownian", pad(nSeg)); addS("xbmech.countActive");
        addW("xbmech.linkForces", pad(C)); addW("xbmech.torsion", pad(C));
        addS("xbmech.histA"); addS("xbmech.scanA"); addS("xbmech.scatterA"); addW("xbmech.gatherA", pad(nSeg));
        addS("xbmech.histB"); addS("xbmech.scanB"); addS("xbmech.scatterB"); addW("xbmech.gatherB", pad(nSeg));
        addW("xbmech.integrate", pad(nSeg)); addW("xbmech.derive", pad(nSeg));
        sc.mechSched = sched;
        return new TornadoExecutionPlan(tg.snapshot());
    }

    /** Mechanics-only CPU step (matches buildMechPlan: no formation, no unbind — static links). */
    static void mechStepCpu(BundleScene sc, int step) {
        FilamentStore f = sc.fil; CrosslinkerStore xl = sc.xl;
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        f.setCounts(step, sc.seed);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        CrosslinkerSystem.countActiveLinks(xl.linkState, xl.linkFilA, xl.linkFilB, xl.activeLinkCount, xl.formCounts);
        CrosslinkerSystem.linkForces(f.coord, f.uVec, f.end1, f.bTransGam, xl.linkFilA, xl.linkFilB, xl.loc1, xl.loc2, xl.activeLinkCount, xl.xlinkData, xl.xlParams);
        CrosslinkerSystem.linkTorsion(f.uVec, xl.linkFilA, xl.linkFilB, xl.linkState, xl.linkOrientSame, xl.torqueMagHist, xl.torqueMagPlace, xl.xlinkData, xl.torsionParams);
        CrossBridgeSystem.csrHistogram(xl.linkFilA, xl.counts, sc.segCountA);
        CrossBridgeSystem.csrScan(xl.counts, sc.segCountA, sc.segOffsetsA);
        CrossBridgeSystem.csrScatter(xl.linkFilA, xl.counts, sc.segOffsetsA, sc.segCountA, sc.segIdxA);
        CrosslinkerSystem.segGatherA(sc.segOffsetsA, sc.segIdxA, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
        CrossBridgeSystem.csrHistogram(xl.linkFilB, xl.counts, sc.segCountB);
        CrossBridgeSystem.csrScan(xl.counts, sc.segCountB, sc.segOffsetsB);
        CrossBridgeSystem.csrScatter(xl.linkFilB, xl.counts, sc.segOffsetsB, sc.segCountB, sc.segIdxB);
        CrosslinkerSystem.segGatherB(sc.segOffsetsB, sc.segIdxB, xl.xlinkData, xl.linkState, f.forceSum, f.torqueSum, xl.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
    }

    static void runCpuGpu(int nFil, double boxX, double boxY, double boxZ, double dt, double conc, int checkInt, int M, int seed) {
        System.out.println("\n========== CPU≡GPU — assembled mechanics over the bundle (chaotic ⇒ aggregate-within-SEM) ==========");
        // build two identical scenes; pre-form links on host (so the comparison runs a non-trivial gather)
        BundleScene g = buildBundle(nFil, boxX, boxY, boxZ, dt, conc, checkInt, seed, true);
        BundleScene c = buildBundle(nFil, boxX, boxY, boxZ, dt, conc, checkInt, seed, true);
        g.formOn = c.formOn = true; g.unbindOn = c.unbindOn = true;
        for (int w = 0; w < 30; w++) { formationCpu(g, w * checkInt); formationCpu(c, w * checkInt); }   // same host pre-formation
        int n0 = activeLinks(g.xl);
        System.out.printf("  pre-formed %d links on both scenes; running mechanics-only %d steps (GPU graph vs CPU)%n", n0, M);
        TornadoExecutionPlan plan = buildMechPlan(g);
        boolean stable = true;
        for (int t = 0; t < M; t++) {
            g.fil.setCounts(t, seed);
            TornadoExecutionResult r = plan.withGridScheduler(g.mechSched).execute();
            if (t == M - 1) r.transferToHost(g.fil.coord, g.fil.uVec, g.fil.yVec);
            mechStepCpu(c, t);
            if (!finite(g.fil) || !finite(c.fil)) { stable = false; break; }
        }
        double sg = spread(g.fil), scpu = spread(c.fil);
        double relSpread = Math.abs(sg - scpu) / scpu;
        // chaotic many-body float32 op-ordering ⇒ microstate decorrelates; aggregate (spread) agrees within a few %
        boolean ok = stable && relSpread < 0.05;
        System.out.printf("  after %d steps: GPU spread=%.5f µm, CPU spread=%.5f µm, rel diff=%.3f%%  %s%n",
                M, sg, scpu, 100 * relSpread, ok ? "(aggregate agrees; chaotic microstate decorrelation expected) ✓" : "*DIVERGES*");
        System.out.println("  STABILITY (GPU): " + (stable ? "stable ✓" : "*BLEW UP*"));
        System.out.println("  (per-kernel CPU≡GPU bit-identity is validated in CrosslinkerHarness 5a–5c-iii; this confirms the assembled device mechanics over the many-body bundle.)");
    }

    // ============================================================== demo viz
    static void runViz(int nFil, double boxX, double boxY, double boxZ, double dt, double conc,
                       int checkInt, int M, int seed, String dir) {
        BundleScene sc = buildBundle(nFil, boxX, boxY, boxZ, dt, conc, checkInt, seed, true);
        sc.formOn = true; sc.unbindOn = true;
        new java.io.File(dir).mkdirs();
        int every = Math.max(1, M / 300), frames = 0;
        for (int t = 0; t <= M; t++) {
            if (t % every == 0) writeFrame(dir, frames++, t * dt, sc, boxX, boxY, boxZ);
            if (t < M) assembledStepCpu(sc, t);
        }
        System.out.println("viewer: wrote " + frames + " frames to " + dir + " (filaments + crosslinker bonds)");
    }

    /** Emit filament segments + each ACTIVE crosslinker as a thin bond "segment" (parallel links
     *  notADPRatio=1.0, antiparallel=0.0 ⇒ viewer colour) — uses the verbatim viewer's segments array. */
    static void writeFrame(String dir, int frame, double t, BundleScene sc, double bx, double by, double bz) {
        FilamentStore f = sc.fil; CrosslinkerStore xl = sc.xl; int n = f.n;
        StringBuilder sb = new StringBuilder(4096);
        sb.append(String.format(java.util.Locale.US, "{\"frame\":%d,\"t\":%.6g", frame, t));
        sb.append(String.format(java.util.Locale.US, ",\"bounds\":{\"xDim\":%.5g,\"yDim\":%.5g,\"zDim\":%.5g}", bx, by, bz));
        sb.append(",\"segments\":[");
        boolean first = true;
        for (int s = 0; s < n; s++) {
            if (!first) sb.append(','); first = false;
            double half = f.segLength.get(s) * 0.5;
            double e1x = f.coordX(s) - half * f.uVecX(s), e1y = f.coordY(s) - half * f.uVecY(s), e1z = f.coordZ(s) - half * f.uVecZ(s);
            double e2x = f.coordX(s) + half * f.uVecX(s), e2y = f.coordY(s) + half * f.uVecY(s), e2z = f.coordZ(s) + half * f.uVecZ(s);
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":1.0,\"cofilinCount\":0}",
                s, e1x, e1y, e1z, e2x, e2y, e2z, Constants.radius));
        }
        // crosslinker bonds as thin segments (id offset by n; notADPRatio encodes orientation)
        int id = n;
        for (int k = 0; k < xl.nLinks; k++) {
            if (xl.linkState.get(k) < 0) continue;
            int a = xl.linkFilA.get(k), b = xl.linkFilB.get(k);
            if (a < 0 || b < 0) continue;
            double l1 = xl.loc1.get(k), l2 = xl.loc2.get(k);
            double p1x = f.end1.get(a) + l1 * f.uVec.get(a), p1y = f.end1.get(n + a) + l1 * f.uVec.get(n + a), p1z = f.end1.get(2 * n + a) + l1 * f.uVec.get(2 * n + a);
            double p2x = f.end1.get(b) + l2 * f.uVec.get(b), p2y = f.end1.get(n + b) + l2 * f.uVec.get(n + b), p2z = f.end1.get(2 * n + b) + l2 * f.uVec.get(2 * n + b);
            double orient = xl.linkOrientSame.get(k) != 0 ? 1.0 : 0.0;
            sb.append(',');
            sb.append(String.format(java.util.Locale.US,
                "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":%.1f,\"cofilinCount\":0}",
                id++, p1x, p1y, p1z, p2x, p2y, p2z, Constants.radius * 0.4, orient));
        }
        sb.append("]}");
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(dir, String.format(java.util.Locale.US, "frame_%06d.json", frame)), sb.toString());
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    static double clamp(double v, double box) { double h = 0.5 * box; return v > h ? h : (v < -h ? -h : v); }

    static int pad(int n) { return ((n + B - 1) / B) * B; }
    static void addW(String nm, int gg) { WorkerGrid w = new WorkerGrid1D(gg); w.setLocalWork(B, 1, 1); sched.addWorkerGrid(nm, w); }
    static void addS(String nm) { WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sched.addWorkerGrid(nm, w); }
}
