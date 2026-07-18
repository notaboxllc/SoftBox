package softbox;

import softbox.TwoBodyConverterMotor.Glide2D;
import softbox.ExplicitCompleteMatHarness.ExMat;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

/**
 * Binding-search timestep-resolution + angular-gate sensitivity DIAGNOSTIC (CPU-runner, deterministic).
 *
 * <p>Reuses the SHARED explicit free-binding CPU step byte-faithfully (the exact kernel sequence of
 * {@link ExplicitCompleteMatHarness#stepGlidingCPU}) but hooks host-side per-motor diagnostics in AT the
 * pre-bind geometry — the same {@code matBeamGeom} state {@code matBindExplicit} consumes. NO physics /
 * salt / chemistry / gate change: the binding decision is the UNMODIFIED {@code matBindExplicit}; the angular
 * panel only re-parameterizes the already-parameterized {@code bindP} thresholds (phi/psi/theta degrees).
 * Diagnostic-only, one file, touches no shared system. Emits a per-config report + a summary CSV row.
 *
 * <p>Modes: {@code -mode abc} (Parts A/B/C: motion, substep-miss, gate funnel), {@code -mode recruit}
 * (Part D1/E: angular-panel recruitment + admitted-attachment quality), {@code -mode glide} (Part D2:
 * reduced density panel with LS-slope velocity).
 */
public final class ExplicitBindDiagHarness {
    static final double DT = 2.5e-6;
    static final String OUT = "RUN_LOGS/binddiag";

    // ---- running distribution with exact moments + a deterministic reservoir for quantiles ----
    static final class Dist {
        long n = 0; double sum = 0, sumSq = 0, max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY;
        final int cap = 400_000; double[] res = new double[cap]; int filled = 0; final Random rng;
        Dist(long seed) { rng = new Random(seed); }
        void add(double v) {
            n++; sum += v; sumSq += v * v; if (v > max) max = v; if (v < min) min = v;
            if (filled < cap) res[filled++] = v;
            else { long j = (long) (rng.nextDouble() * n); if (j < cap) res[(int) j] = v; }
        }
        double mean() { return n > 0 ? sum / n : 0; }
        double rms() { return n > 0 ? Math.sqrt(sumSq / n) : 0; }
        double[] quantiles(double... qs) {
            double[] out = new double[qs.length];
            if (filled == 0) return out;
            double[] s = Arrays.copyOf(res, filled); Arrays.sort(s);
            for (int i = 0; i < qs.length; i++) { int idx = (int) Math.min(filled - 1, Math.max(0, Math.round(qs[i] * (filled - 1)))); out[i] = s[idx]; }
            return out;
        }
        String line(String name, String unit, double gateW) {
            double[] q = quantiles(0.50, 0.90, 0.95, 0.99);
            String norm = gateW > 0 ? String.format(Locale.US, "  | /gate: rms=%.3f p95=%.3f p99=%.3f", rms() / gateW, q[2] / gateW, q[3] / gateW) : "";
            return String.format(Locale.US, "%-16s [%s] n=%d rms=%.4g med=%.4g p90=%.4g p95=%.4g p99=%.4g max=%.4g%s",
                    name, unit, n, rms(), q[0], q[1], q[2], q[3], max, norm);
        }
    }

    static final int GATES = 8;
    static final String[] GNAME = { "dist", "phi", "psi", "theta", "preload", "energy", "headside", "insegment" };

    // per-motor stored PREVIOUS pre-bind record (for A increments + B substep interpolation)
    static final class Prev {
        boolean have = false;
        double[] xF8, xH, nodeM, node0, q4;   // per motor: [3],[3],[3],[3],[4]
        boolean[] elig, passEp;
        int[] nearSeg;
        // previous filament pose (all segments)
        double[] segCx, segCy, segCz, segUx, segUy, segUz, segHalf;
    }

    public static void main(String[] args) {
        String mode = "abc"; double density = 200; int seed = 101; int dtdiv = 1;
        double equilMs = 8, measMs = 12; double angScale = 1.0; String tag = null;
        double phiDeg = -1, psiDeg = -1, thetaDeg = -1;   // absolute overrides (else 25/25/20 * angScale)
        // NEW STUDY: the three reach/placement thresholds (defaults = production contract)
        double rBind = 3.0, preloadMax = 2.0, segMargin = Double.NaN;   // nm, pN, µm (NaN ⇒ keep canonical ε from packExMat)
        boolean gpu = false;
        // glide panel
        int glEquilSteps = 20000, glMeasSteps = 40000;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-mode": mode = args[++i]; break;
                case "-density": density = Double.parseDouble(args[++i]); break;
                case "-seed": seed = Integer.parseInt(args[++i]); break;
                case "-dtdiv": dtdiv = Integer.parseInt(args[++i]); break;
                case "-equilms": equilMs = Double.parseDouble(args[++i]); break;
                case "-measms": measMs = Double.parseDouble(args[++i]); break;
                case "-angscale": angScale = Double.parseDouble(args[++i]); break;
                case "-phideg": phiDeg = Double.parseDouble(args[++i]); break;
                case "-psideg": psiDeg = Double.parseDouble(args[++i]); break;
                case "-thetadeg": thetaDeg = Double.parseDouble(args[++i]); break;
                case "-glequil": glEquilSteps = Integer.parseInt(args[++i]); break;
                case "-glmeas": glMeasSteps = Integer.parseInt(args[++i]); break;
                case "-rbind": rBind = Double.parseDouble(args[++i]); break;
                case "-preload": preloadMax = Double.parseDouble(args[++i]); break;
                case "-segmargin": segMargin = Double.parseDouble(args[++i]); break;
                case "-legacy": TwoBodyConverterMotor.LEGACY_OWNERSHIP = true; break;   // regression: deprecated first-min + 50 nm
                case "-halfopen": break;   // deprecated no-op (canonical half-open is now the default)
                case "-gpu": gpu = true; break;
                case "-tag": tag = args[++i]; break;
                default: break;
            }
        }
        if (mode.equals("contract")) { contractMode(density, seed, DT / dtdiv); return; }
        if (phiDeg < 0) phiDeg = 25 * angScale;
        if (psiDeg < 0) psiDeg = 25 * angScale;
        if (thetaDeg < 0) thetaDeg = 20 * angScale;
        if (tag == null) tag = String.format(Locale.US, "%s_d%.0f_s%d_dt%d_ph%.0f_ps%.0f_th%.0f_rb%.1f_pl%.1f_sm%.3f", mode, density, seed, dtdiv, phiDeg, psiDeg, thetaDeg, rBind, preloadMax, segMargin);
        try { Files.createDirectories(Path.of(OUT)); } catch (IOException e) { throw new UncheckedIOException(e); }

        double dt = DT / dtdiv;
        System.out.printf(Locale.US, "[binddiag] mode=%s density=%.0f seed=%d dt=%.3e (÷%d) phi/psi/theta=%.1f/%.1f/%.1f rBind=%.2fnm preload=%.2fpN segMargin=%.4fµm tag=%s%n",
                mode, density, seed, dt, dtdiv, phiDeg, psiDeg, thetaDeg, rBind, preloadMax, segMargin, tag);

        R3 r3 = new R3(rBind, preloadMax, segMargin);
        StringBuilder rep = new StringBuilder();
        String summaryLine;
        if (mode.equals("glide")) summaryLine = glideMode(rep, density, seed, dt, phiDeg, psiDeg, thetaDeg, glEquilSteps, glMeasSteps, r3, gpu, tag);
        else if (mode.equals("recruit")) summaryLine = recruitMode(rep, density, seed, dt, dtdiv, equilMs, measMs, phiDeg, psiDeg, thetaDeg, r3, tag);
        else summaryLine = abcMode(rep, density, seed, dt, dtdiv, equilMs, measMs, phiDeg, psiDeg, thetaDeg, r3, tag);

        try {
            Files.writeString(Path.of(OUT, tag + ".md"), rep.toString());
            Files.writeString(Path.of(OUT, "summary_" + mode + ".csv"), summaryLine + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        System.out.println("# report: " + Path.of(OUT, tag + ".md").toAbsolutePath());
        System.out.println("SUMMARY," + summaryLine);
    }

    /** The three reach/placement thresholds (diagnostic overrides; defaults = the production contract). */
    static final class R3 { final double rBind, preloadMax, segMargin; R3(double r, double p, double s) { rBind = r; preloadMax = p; segMargin = s; } }

    // ===================================================================== scene + faithful diagnostic step
    static ExMat buildScene(double density, int seed, double dt, double phiDeg, double psiDeg, double thetaDeg, R3 r3) {
        double slack = TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM;
        Glide2D G = TwoBodyConverterMotor.buildS2Mat(density, dt, 40.0, slack, seed);
        for (int m = 0; m < G.N; m++) G.mot.boundSeg.set(m, -1);
        ExMat e = ExplicitCompleteMatHarness.packExMat(G, 1);   // bindP[10]=canonical ε (or 50nm if -legacy), bindP[12]=mode
        e.bindP.set(1, psiDeg); e.bindP.set(2, phiDeg); e.bindP.set(3, thetaDeg);   // angular thresholds
        e.bindP.set(0, r3.rBind); e.bindP.set(4, r3.preloadMax);                    // reach thresholds
        if (!Double.isNaN(r3.segMargin)) e.bindP.set(10, r3.segMargin);            // explicit margin override (studies)
        return e;
    }

    /** §1 — dump the frozen binding contract (exact values + derived reach limits) from the live default scene. */
    static void contractMode(double density, int seed, double dt) {
        R3 def = new R3(3.0, 2.0, 0.05);
        ExMat e = buildScene(density, seed, dt, 25, 25, 20, def);
        Glide2D G = e.G; double kF8 = e.params.get(5 * e.N + 0), kconv = e.params.get(6 * e.N + 0), kbind = e.params.get(7 * e.N + 0);
        double myoSpring = G.xbParams.get(0); double half = 0.5 * G.fil.segLength.get(0);
        double kinCap = G.mot.kinParams.get(12), breakF = G.mot.kinParams.get(11);
        StringBuilder s = new StringBuilder();
        s.append("# §1 Frozen binding contract — explicit-s2-l40 (dumped from the live default scene)\n\n");
        s.append("Gate arithmetic: `TwoBodyBeamAnalyticGpu.matBindExplicit` L412–461; thresholds `bindP` set in\n");
        s.append("`ExplicitCompleteMatHarness.packExMat` L76–77. Deterministic (NO binding RNG).\n\n```\n");
        s.append(String.format(Locale.US, "distance   bindP[0] = %.4f nm      (surf=(conDist-FIL_R)*1e3 < 3 nm)\n", e.bindP.get(0)));
        s.append(String.format(Locale.US, "psi        bindP[1] = %.1f deg\n", e.bindP.get(1)));
        s.append(String.format(Locale.US, "phi        bindP[2] = %.1f deg\n", e.bindP.get(2)));
        s.append(String.format(Locale.US, "theta      bindP[3] = %.1f deg\n", e.bindP.get(3)));
        s.append(String.format(Locale.US, "preloadMax bindP[4] = %.4f pN      (preload = kF8*conDist*1e12 < 2 pN)\n", e.bindP.get(4)));
        s.append(String.format(Locale.US, "energyMax  bindP[5] = %.1f kT\n", e.bindP.get(5)));
        s.append(String.format(Locale.US, "FIL_R      bindP[6] = %.6f µm   (Constants.radius)\n", e.bindP.get(6)));
        s.append(String.format(Locale.US, "PHI_PRE    bindP[7] = %.6f rad\n", e.bindP.get(7)));
        s.append(String.format(Locale.US, "aSemiZ     bindP[8] = %.6f µm   (headSide < aSemiZ*1e3 = %.3f nm)\n", e.bindP.get(8), e.bindP.get(8) * 1e3));
        s.append(String.format(Locale.US, "kT         bindP[9] = %.4e\n", e.bindP.get(9)));
        s.append(String.format(Locale.US, "segMargin  bindP[10]= %.4f µm     (bindArc in (margin, 2*half-margin))\n", e.bindP.get(10)));
        s.append(String.format(Locale.US, "orientOn   bindP[11]= %.0f\n", e.bindP.get(11)));
        s.append(String.format(Locale.US, "\nsegLength = %.5f µm ⇒ half = %.5f µm (88 nm); in-segment window = [%.1f, %.1f] nm = %.1f%% of segment\n",
                G.fil.segLength.get(0), half, e.bindP.get(10) * 1e3, (2 * half - e.bindP.get(10)) * 1e3, 100 * (2 * half - 2 * e.bindP.get(10)) / (2 * half)));
        s.append(String.format(Locale.US, "\n--- the two DISTINCT spring quantities (task §1) ---\n"));
        s.append(String.format(Locale.US, "gate preload spring  kF8 = params[5N] = %.6g (code)   ⇒ preload gate ⇔ conDist < preloadMax/(kF8*1e12) = %.4f nm\n", kF8, e.bindP.get(4) / (kF8 * 1e12) * 1e3));
        s.append(String.format(Locale.US, "gate distance ⇔ conDist < 3nm + FIL_R = %.4f nm  (surf, surface-to-F8)\n", (e.bindP.get(0) * 1e-3 + e.bindP.get(6)) * 1e3));
        s.append(String.format(Locale.US, "realized bond spring myoSpring = xbParams[0] = %.6g (code)   [%s kF8]\n", myoSpring, Math.abs(myoSpring - kF8) < 1e-9 ? "==" : "!="));
        s.append(String.format(Locale.US, "kconv = params[6N] = %.6g ; kbind = params[7N] = %.6g\n", kconv, kbind));
        s.append(String.format(Locale.US, "\n--- release / detachment ---\n"));
        s.append(String.format(Locale.US, "detach = cycleLymnTaylor catch-slip (Guo–Guilford). 12-pN break cap kinParams[12]=%.1f (%s), breakForce=%.4g N\n",
                kinCap, kinCap > 0.5 ? "ON" : "OFF (gliding default)", breakF));
        s.append("\nbind update (matBindExplicit L461): sets ONLY boundSeg=s, bindArc=bindArcV (beam pose continuous;\n");
        s.append("  onset F8 = myoSpring*(head-tip − bound-site), realized AFTER matPlaceHeadExplicit + matCock ⇒\n");
        s.append("  DISTINCT from the gate preload (different spring const AND geometry: pre-bind conDist vs post-cock tip).\n```\n");
        try { Files.writeString(Path.of(OUT, "CONTRACT.md"), s.toString()); } catch (IOException ex) { throw new UncheckedIOException(ex); }
        System.out.print(s);
        System.out.println("# report: " + Path.of(OUT, "CONTRACT.md").toAbsolutePath());
    }

    /** Gate evaluation result at a given xF8/xH/q against the nearest filament segment. */
    static final class GEval {
        boolean valid; int seg; double[] margin = new double[GATES]; boolean[] pass = new boolean[GATES];
        double surf, preload, eKt, headSide, bindArcV; boolean allPass;
    }
    /** Evaluate the 8 gates EXACTLY as matBindExplicit, but return signed margins (positive = passes). */
    static GEval evalGates(ExMat e, double xF8x, double xF8y, double xF8z, double xHx, double xHy, double xHz,
                           double phi, double psi, double thetaS, double psiActin,
                           FloatArray filCoord, FloatArray filUVec, FloatArray filSegLen, int nSeg, int m) {
        GEval r = new GEval();
        double dBindNm = e.bindP.get(0), psiDeg = e.bindP.get(1), phiDeg = e.bindP.get(2), thetaDeg = e.bindP.get(3);
        double preloadPn = e.bindP.get(4), energyKt = e.bindP.get(5), FIL_R = e.bindP.get(6), PHI_PRE = e.bindP.get(7);
        double aSemiZ = e.bindP.get(8), kT = e.bindP.get(9), margin = e.bindP.get(10);
        boolean halfOpen = e.bindP.get(12) < 0.5; double eps = margin;   // mirror matBindExplicit (bindP[12] ownership mode)
        double DEG = 180.0 / Math.PI;
        double eupx = e.eupP.get(0), eupy = e.eupP.get(1), eupz = e.eupP.get(2);
        int N = e.N;
        // nearest segment: legacy = perp dist, foot within half+0.02; half-open = distance to CLAMPED closest point
        int best = -1; double bd = 1e9;
        for (int s = 0; s < nSeg; s++) {
            double half = 0.5 * filSegLen.get(s);
            double cx = filCoord.get(s), cy = filCoord.get(nSeg + s), cz = filCoord.get(2 * nSeg + s);
            double ux = filUVec.get(s), uy = filUVec.get(nSeg + s), uz = filUVec.get(2 * nSeg + s);
            double dx = xF8x - cx, dy = xF8y - cy, dz = xF8z - cz; double foot = dx * ux + dy * uy + dz * uz;
            double d2;
            if (halfOpen) {
                double footC = foot < -half ? -half : (foot > half ? half : foot);
                double qx = dx - footC * ux, qy = dy - footC * uy, qz = dz - footC * uz; d2 = qx * qx + qy * qy + qz * qz;
            } else {
                if (foot > half + 0.02 || foot < -(half + 0.02)) continue;
                double px = dx - foot * ux, py = dy - foot * uy, pz = dz - foot * uz; d2 = px * px + py * py + pz * pz;
            }
            if (d2 < bd) { bd = d2; best = s; }
        }
        if (best < 0) { r.valid = false; return r; }
        r.valid = true; r.seg = best; int s = best; double half = 0.5 * filSegLen.get(s);
        double cx = filCoord.get(s), cy = filCoord.get(nSeg + s), cz = filCoord.get(2 * nSeg + s);
        double ux = filUVec.get(s), uy = filUVec.get(nSeg + s), uz = filUVec.get(2 * nSeg + s);
        double e1x = cx - half * ux, e1y = cy - half * uy, e1z = cz - half * uz;
        double dx = xF8x - cx, dy = xF8y - cy, dz = xF8z - cz; double foot = dx * ux + dy * uy + dz * uz;
        double footC = halfOpen ? (foot < -half ? -half : (foot > half ? half : foot)) : foot;
        double axx = cx + footC * ux, axy = cy + footC * uy, axz = cz + footC * uz;
        double conDist = Math.sqrt((xF8x - axx) * (xF8x - axx) + (xF8y - axy) * (xF8y - axy) + (xF8z - axz) * (xF8z - axz));
        double bindArcV = halfOpen ? (footC + half) : ((xF8x - e1x) * ux + (xF8y - e1y) * uy + (xF8z - e1z) * uz);
        double surf = (conDist - FIL_R) * 1e3;
        double psiErr = Math.abs(psi - psiActin) * DEG, phiErr = Math.abs(phi - PHI_PRE) * DEG, thetaErr = Math.abs((psi - phi) - thetaS) * DEG;
        double kF8 = e.params.get(5 * N + m), kconv = e.params.get(6 * N + m), kbind = e.params.get(7 * N + m);
        double preload = kF8 * conDist * 1e12;
        double dth = (psi - phi) - thetaS, dpa = psi - psiActin;
        double eKt = (0.5 * kconv * dth * dth + 0.5 * kbind * dpa * dpa) / kT;
        double headSide = ((xHx - cx) * eupx + (xHy - cy) * eupy + (xHz - cz) * eupz) * 1e3;
        r.surf = surf; r.preload = preload; r.eKt = eKt; r.headSide = headSide; r.bindArcV = bindArcV;
        r.margin[0] = dBindNm - surf;                       r.pass[0] = surf < dBindNm;
        r.margin[1] = psiDeg - psiErr;                      r.pass[1] = psiErr < psiDeg;
        r.margin[2] = phiDeg - phiErr;                      r.pass[2] = phiErr < phiDeg;
        r.margin[3] = thetaDeg - thetaErr;                  r.pass[3] = thetaErr < thetaDeg;
        r.margin[4] = preloadPn - preload;                  r.pass[4] = preload < preloadPn;
        r.margin[5] = energyKt - eKt;                       r.pass[5] = eKt < energyKt;
        r.margin[6] = aSemiZ * 1e3 - headSide;              r.pass[6] = headSide < aSemiZ * 1e3;
        double insMargin = Math.min(bindArcV - eps, (2 * half - eps) - bindArcV);
        r.margin[7] = insMargin * 1e3;                      r.pass[7] = bindArcV > eps && bindArcV < 2 * half - eps;
        r.allPass = r.pass[0] && r.pass[1] && r.pass[2] && r.pass[3] && r.pass[4] && r.pass[5] && r.pass[6] && r.pass[7];
        return r;
    }

    // ============================================================ Part A/B/C accumulators
    static final class ABC {
        // Part A distributions
        Dist headDisp = new Dist(1), f8Disp = new Dist(2), beamEndDisp = new Dist(3), node0Disp = new Dist(4);
        Dist dispPar = new Dist(5), dispNorm = new Dist(6);
        Dist dPhi = new Dist(7), dPsi = new Dist(8), dTheta = new Dist(9);
        Dist dNearDist = new Dist(10), filSegDisp = new Dist(11); Dist filAdvX = new Dist(12);
        long nearSegChanges = 0, incrementSamples = 0;
        // Part C funnel (cumulative in gate order dist,phi,psi,theta,preload,energy,headside,insegment)
        long[] cumPass = new long[GATES]; long[] margPass = new long[GATES]; long[] looBind = new long[GATES];
        long eligSteps = 0, allPassSteps = 0, nearMiss = 0; long[] missGate = new long[GATES];
        // Part B substep
        long endpointEpisodes = 0, substepOnlyEpisodes = 0, intervalCount = 0, substepIntervals = 0;
        long[] endpointMissGate = new long[GATES];   // for substep-only intervals, which gate fails at endpoint
        // Part B3 episode durations (in production-dt steps), collected on binds & substep-only encounters
        Dist episodeSteps = new Dist(13);
        long episLt1 = 0, epis1to2 = 0, episGt2 = 0, episTot = 0;
        // per-motor episode tracker: consecutive endpoint all-pass? (rare, binds immediately) — track substep runs
        boolean[] inSubRun; long binds = 0, detaches = 0; double boundSum = 0; long boundSteps = 0;
        long nearIntervals = 0;   // intervals actually substep-probed (near-gate at an endpoint)
        // reusable interpolation scratch (single-threaded ⇒ safe)
        FloatArray sfc, sfu, sfl; int scratchSeg = -1;
        void ensureScratch(int nSeg) { if (scratchSeg != nSeg) { sfc = new FloatArray(3 * nSeg); sfu = new FloatArray(3 * nSeg); sfl = new FloatArray(nSeg); scratchSeg = nSeg; } }
    }
    static final double NEAR_SURF_NM = 25.0;   // substep-probe only when an endpoint F8-surf is within this (a ~2nm/step motor >25nm out cannot reach 3nm mid-interval)

    static String abcMode(StringBuilder rep, double density, int seed, double dt, int dtdiv,
                          double equilMs, double measMs, double phiDeg, double psiDeg, double thetaDeg, R3 r3, String tag) {
        ExMat e = buildScene(density, seed, dt, phiDeg, psiDeg, thetaDeg, r3);
        int N = e.N, nSeg = e.nSeg;
        int equil = (int) Math.round(equilMs * 1e-3 / dt), meas = (int) Math.round(measMs * 1e-3 / dt);
        ABC acc = new ABC(); acc.inSubRun = new boolean[N];
        Prev prev = new Prev();
        prev.xF8 = new double[3 * N]; prev.xH = new double[3 * N]; prev.nodeM = new double[3 * N]; prev.node0 = new double[3 * N];
        prev.q4 = new double[4 * N]; prev.elig = new boolean[N]; prev.passEp = new boolean[N]; prev.nearSeg = new int[N];
        prev.segCx = new double[nSeg]; prev.segCy = new double[nSeg]; prev.segCz = new double[nSeg];
        prev.segUx = new double[nSeg]; prev.segUy = new double[nSeg]; prev.segUz = new double[nSeg]; prev.segHalf = new double[nSeg];

        long t0 = System.nanoTime();
        for (int t = 0; t < equil; t++) stepDiag(e, t, seed, null, null);
        for (int t = equil; t < equil + meas; t++) stepDiag(e, t, seed, acc, prev);
        double wallS = (System.nanoTime() - t0) / 1e9;

        // ---------- assemble report ----------
        rep.append("# Binding-search resolution — Part A/B/C (density ").append((int) density).append(", seed ").append(seed)
           .append(", dt=").append(String.format(Locale.US, "%.3e", dt)).append(" ÷").append(dtdiv).append(")\n\n");
        rep.append(String.format(Locale.US, "N=%d, nSeg=%d, equil=%d meas=%d steps, wall=%.1fs, eligible motor-steps=%d\n\n",
                N, nSeg, equil, meas, wallS, acc.eligSteps));

        rep.append("## Part A — unbound motor-domain per-step increments\n```\n");
        rep.append(acc.f8Disp.line("F8 disp", "nm", 3.0)).append("\n");
        rep.append(acc.headDisp.line("head disp", "nm", 3.0)).append("\n");
        rep.append(acc.beamEndDisp.line("beam-end(P) disp", "nm", 0)).append("\n");
        rep.append(acc.node0Disp.line("node0(clamp) disp", "nm", 0)).append("  (sanity: clamped end ≈0)\n");
        rep.append(acc.dispPar.line("F8 ∥seg", "nm", 3.0)).append("\n");
        rep.append(acc.dispNorm.line("F8 ⊥seg", "nm", 3.0)).append("\n");
        rep.append(acc.dPhi.line("|Δphi|", "deg", 25.0)).append("\n");
        rep.append(acc.dPsi.line("|Δpsi|", "deg", 25.0)).append("\n");
        rep.append(acc.dTheta.line("|Δtheta|", "deg", 20.0)).append("  (theta := psi−phi, the g3 coord)\n");
        rep.append(acc.dNearDist.line("Δ near-seg dist", "nm", 3.0)).append("  (motor+fil relative; gate-relevant)\n");
        rep.append("--- deterministic filament advection (per seg-step, separated) ---\n");
        rep.append(acc.filSegDisp.line("fil seg disp", "nm", 0)).append("\n");
        rep.append(acc.filAdvX.line("fil adv (signed x)", "nm", 0)).append("\n");
        rep.append(String.format(Locale.US, "nearest-seg identity changed: %d / %d increments (%.3f%%)\n",
                acc.nearSegChanges, acc.incrementSamples, 100.0 * acc.nearSegChanges / Math.max(1, acc.incrementSamples)));
        rep.append("```\n\n");

        rep.append("## Part C — gate funnel (per eligible unbound ADP·Pi motor-step)\n```\n");
        rep.append(String.format(Locale.US, "eligible motor-steps: %d   all-8-pass: %d   near-miss(exactly 1 fail, margin<20%%W): %d\n",
                acc.eligSteps, acc.allPassSteps, acc.nearMiss));
        rep.append("cumulative funnel (gate order):\n");
        for (int g = 0; g < GATES; g++)
            rep.append(String.format(Locale.US, "  +%-10s cumPass=%d (%.4f%%)   marginalPass=%d (%.4f%%)\n",
                    GNAME[g], acc.cumPass[g], 100.0 * acc.cumPass[g] / Math.max(1, acc.eligSteps),
                    acc.margPass[g], 100.0 * acc.margPass[g] / Math.max(1, acc.eligSteps)));
        rep.append("leave-one-gate-out (would-bind if ONLY that gate removed = sole-failing-gate count):\n");
        for (int g = 0; g < GATES; g++)
            rep.append(String.format(Locale.US, "  -%-10s bindsIfRemoved=%d\n", GNAME[g], acc.looBind[g]));
        rep.append("which gate causes rejection when it is the sole failure (== leave-one-out above); marginal totals include overlaps.\n");
        rep.append("```\n\n");

        rep.append("## Part B — between-step missed binding opportunities (interpolated coords @ 1/4,1/2,3/4)\n```\n");
        long detected = acc.endpointEpisodes + acc.substepOnlyEpisodes;
        double missedFrac = detected > 0 ? (double) acc.substepOnlyEpisodes / detected : 0;
        rep.append(String.format(Locale.US, "intervals evaluated=%d   near-gate intervals substep-probed=%d   intervals with an interior all-pass=%d\n", acc.intervalCount, acc.nearIntervals, acc.substepIntervals));
        rep.append(String.format(Locale.US, "endpoint-detected episodes (binds)=%d   substep-only episodes=%d\n", acc.endpointEpisodes, acc.substepOnlyEpisodes));
        rep.append(String.format(Locale.US, "missedFraction = %d / %d = %.4f\n", acc.substepOnlyEpisodes, detected, missedFrac));
        rep.append("endpoint-miss gate for substep-only intervals (which gate fails at the bracketing endpoints):\n");
        for (int g = 0; g < GATES; g++) if (acc.endpointMissGate[g] > 0) rep.append(String.format(Locale.US, "  %-10s %d\n", GNAME[g], acc.endpointMissGate[g]));
        rep.append(String.format(Locale.US, "\nepisode durations (production-dt steps of contiguous all-pass eligibility, on binds):\n"));
        double[] eq = acc.episodeSteps.quantiles(0.50, 0.90, 0.99);
        rep.append(String.format(Locale.US, "  n=%d  <1step frac=%.3f  1-2step frac=%.3f  >2step frac=%.3f  med=%.2f p90=%.2f p99=%.2f\n",
                acc.episTot, (double) acc.episLt1 / Math.max(1, acc.episTot), (double) acc.epis1to2 / Math.max(1, acc.episTot),
                (double) acc.episGt2 / Math.max(1, acc.episTot), eq[0], eq[1], eq[2]));
        rep.append(String.format(Locale.US, "binds=%d detaches=%d meanBound=%.3f\n", acc.binds, acc.detaches, acc.boundSum / Math.max(1, acc.boundSteps)));
        rep.append("```\n\n");

        double[] f8q = acc.f8Disp.quantiles(0.50, 0.95);
        // summary CSV: tag,mode,density,seed,dtdiv,f8rms,f8med,f8p95,headrms,dphi_rms,dpsi_rms,dtheta_rms,dneardist_rms,
        //   eligSteps,allPass,nearMiss,missedFrac,substepOnly,endpointEpis,binds,meanBound,cumPass[all],loo(dist,phi,psi,theta,preload,energy,headside,inseg)
        StringBuilder csv = new StringBuilder();
        csv.append(tag).append(",abc,").append((int) density).append(",").append(seed).append(",").append(dtdiv);
        csv.append(String.format(Locale.US, ",%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f",
                acc.f8Disp.rms(), f8q[0], f8q[1], acc.headDisp.rms(), acc.dPhi.rms(), acc.dPsi.rms(), acc.dTheta.rms(), acc.dNearDist.rms()));
        csv.append(String.format(Locale.US, ",%d,%d,%d,%.6f,%d,%d,%d,%.4f",
                acc.eligSteps, acc.allPassSteps, acc.nearMiss, missedFrac, acc.substepOnlyEpisodes, acc.endpointEpisodes, acc.binds, acc.boundSum / Math.max(1, acc.boundSteps)));
        for (int g = 0; g < GATES; g++) csv.append(",").append(acc.looBind[g]);
        for (int g = 0; g < GATES; g++) csv.append(",").append(acc.cumPass[g]);
        return csv.toString();
    }

    /** One faithful free-binding CPU step (EXACT sequence of stepGlidingCPU) with pre-bind diagnostics. */
    static void stepDiag(ExMat e, int t, int seed, ABC acc, Prev prev) {
        Glide2D G = e.G; FilamentStore f = G.fil; MotorStore mot = G.mot; RigidRodBody b = mot.body; int N = e.N, nSeg = e.nSeg;
        e.matc.set(0, t); e.matc.set(1, seed); mot.setCounts(t, seed, e.nSeg); f.counts.set(1, t); f.counts.set(2, seed);
        for (int m = 0; m < N; m++) e.active.set(m, 1);
        TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom);
        // -------- DIAGNOSTIC HOOK: pre-bind geometry (exactly what matBindExplicit sees) --------
        if (acc != null) diag(e, acc, prev, f, mot);
        // -------- the UNMODIFIED binding decision + rest of the step --------
        int[] preBound = null;
        if (acc != null) { preBound = new int[N]; for (int m = 0; m < N; m++) preBound[m] = mot.boundSeg.get(m); }
        TwoBodyBeamAnalyticGpu.matBindExplicit(e.active, e.noBind, mot.boundSeg, mot.nucleotideState, e.outGeom, e.q, f.coord, f.uVec, f.segLength, e.params, e.bindP, e.eupP, mot.bindArc, e.exCounts);
        if (acc != null) { for (int m = 0; m < N; m++) if (preBound[m] < 0 && mot.boundSeg.get(m) >= 0) acc.binds++; }
        NucleotideCycleSystem.cycleLymnTaylor(mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts);
        MatSoaSlice.matCock(mot.nucleotideState, e.q, e.cockP, e.exCounts);
        TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit(e.outGeom, mot.boundSeg, e.eupP, e.exCounts, b.coord, b.uVec, b.yVec);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        CrossBridgeSystem.csrChunkZero(e.csrChunkParams, mot.counts, e.csrMatrix);
        CrossBridgeSystem.csrChunkHistogram(mot.boundSeg, mot.counts, e.csrChunkParams, e.csrMatrix);
        CrossBridgeSystem.csrChunkReduce(mot.counts, e.csrChunkParams, e.csrMatrix, G.segCount);
        CrossBridgeSystem.csrScan(mot.counts, G.segCount, G.segOff);
        CrossBridgeSystem.csrChunkScatter(mot.boundSeg, mot.counts, e.csrChunkParams, G.segOff, G.segMyo, e.csrMatrix);
        CrossBridgeSystem.segGather(G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts);
        if (!G.rigid) ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        MatSoaSlice.matZConfine(f.coord, f.forceSum, e.zP, e.exCounts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.orthogonalizeY(f.uVec, f.yVec, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        TwoBodyBeamAnalyticGpu.matS2SolveStep(e.nodes, e.frame, e.q, G.bondData, mot.boundSeg, e.params, e.sys, e.outGeom, mot.forceDotFil, mot.forceMag, e.matc, e.exCounts);
        MatSoaSlice.matReduceBlocks(mot.boundSeg, e.active, mot.forceDotFil, e.redP, e.exCounts, e.redBlk);
        MatSoaSlice.matReduceFinal(e.redBlk, f.coord, e.redP, e.exCounts, e.redOut);
        if (acc != null) { acc.boundSum += e.redOut.get(0); acc.boundSteps++; }
    }

    /** Host-side pre-bind diagnostics over eligible unbound ADP·Pi motors (Parts A/B/C). */
    static void diag(ExMat e, ABC acc, Prev prev, FilamentStore f, MotorStore mot) {
        int N = e.N, nSeg = e.nSeg;
        // current filament pose snapshot (all segs)
        double[] cCx = new double[nSeg], cCy = new double[nSeg], cCz = new double[nSeg], cUx = new double[nSeg], cUy = new double[nSeg], cUz = new double[nSeg], cHalf = new double[nSeg];
        for (int s = 0; s < nSeg; s++) {
            cCx[s] = f.coord.get(s); cCy[s] = f.coord.get(nSeg + s); cCz[s] = f.coord.get(2 * nSeg + s);
            cUx[s] = f.uVec.get(s); cUy[s] = f.uVec.get(nSeg + s); cUz[s] = f.uVec.get(2 * nSeg + s); cHalf[s] = 0.5 * f.segLength.get(s);
        }
        // filament advection (per seg vs prev)
        if (prev.have) for (int s = 0; s < nSeg; s++) {
            double dx = cCx[s] - prev.segCx[s], dy = cCy[s] - prev.segCy[s], dz = cCz[s] - prev.segCz[s];
            acc.filSegDisp.add(Math.sqrt(dx * dx + dy * dy + dz * dz) * 1e3); acc.filAdvX.add(dx * 1e3);
        }
        for (int m = 0; m < N; m++) {
            boolean elig = e.active.get(m) == 1 && e.noBind.get(m) == 0 && mot.boundSeg.get(m) == -1 && mot.nucleotideState.get(m) == 2;
            double xF8x = e.outGeom.get(6 * N + m), xF8y = e.outGeom.get(7 * N + m), xF8z = e.outGeom.get(8 * N + m);
            double xHx = e.outGeom.get(3 * N + m), xHy = e.outGeom.get(4 * N + m), xHz = e.outGeom.get(5 * N + m);
            double nMx = e.nodes.get((3 * e.M) * N + m), nMy = e.nodes.get((3 * e.M + 1) * N + m), nMz = e.nodes.get((3 * e.M + 2) * N + m);
            double n0x = e.nodes.get(m), n0y = e.nodes.get(N + m), n0z = e.nodes.get(2 * N + m);
            double phi = e.q.get(m), psi = e.q.get(N + m), thetaS = e.q.get(2 * N + m), psiActin = e.q.get(3 * N + m);
            GEval g = elig ? evalGates(e, xF8x, xF8y, xF8z, xHx, xHy, xHz, phi, psi, thetaS, psiActin, f.coord, f.uVec, f.segLength, nSeg, m) : null;

            if (elig && g != null && g.valid) {
                acc.eligSteps++;
                // Part C funnel
                boolean cum = true; int failCount = 0, soleFail = -1;
                for (int gi = 0; gi < GATES; gi++) {
                    if (g.pass[gi]) acc.margPass[gi]++; else { failCount++; soleFail = gi; }
                    cum = cum && g.pass[gi]; if (cum) acc.cumPass[gi]++;
                }
                if (g.allPass) acc.allPassSteps++;
                if (failCount == 1) { acc.looBind[soleFail]++; acc.missGate[soleFail]++;
                    // near-miss: sole failing gate margin within 20% of its width
                    double w = gateWidth(e, soleFail);
                    if (w > 0 && g.margin[soleFail] > -0.2 * w) acc.nearMiss++;
                }
            }

            // Part A increments + Part B interpolation (needs prev record for this motor)
            if (prev.have && elig && prev.elig[m] && g != null && g.valid) {
                double pF8x = prev.xF8[3 * m], pF8y = prev.xF8[3 * m + 1], pF8z = prev.xF8[3 * m + 2];
                double pHx = prev.xH[3 * m], pHy = prev.xH[3 * m + 1], pHz = prev.xH[3 * m + 2];
                double pMx = prev.nodeM[3 * m], pMy = prev.nodeM[3 * m + 1], pMz = prev.nodeM[3 * m + 2];
                double p0x = prev.node0[3 * m], p0y = prev.node0[3 * m + 1], p0z = prev.node0[3 * m + 2];
                double dF8x = xF8x - pF8x, dF8y = xF8y - pF8y, dF8z = xF8z - pF8z;
                double f8d = Math.sqrt(dF8x * dF8x + dF8y * dF8y + dF8z * dF8z) * 1e3;
                acc.f8Disp.add(f8d);
                acc.headDisp.add(Math.sqrt(sq(xHx - pHx) + sq(xHy - pHy) + sq(xHz - pHz)) * 1e3);
                acc.beamEndDisp.add(Math.sqrt(sq(nMx - pMx) + sq(nMy - pMy) + sq(nMz - pMz)) * 1e3);
                acc.node0Disp.add(Math.sqrt(sq(n0x - p0x) + sq(n0y - p0y) + sq(n0z - p0z)) * 1e3);
                // decompose F8 disp along the prev nearest seg axis
                int ps = prev.nearSeg[m];
                if (ps >= 0) {
                    double ux = prev.segUx[ps], uy = prev.segUy[ps], uz = prev.segUz[ps];
                    double par = (dF8x * ux + dF8y * uy + dF8z * uz);
                    double perpx = dF8x - par * ux, perpy = dF8y - par * uy, perpz = dF8z - par * uz;
                    acc.dispPar.add(Math.abs(par) * 1e3);
                    acc.dispNorm.add(Math.sqrt(perpx * perpx + perpy * perpy + perpz * perpz) * 1e3);
                }
                acc.dPhi.add(Math.abs(phi - prev.q4[4 * m]) * 180 / Math.PI);
                acc.dPsi.add(Math.abs(psi - prev.q4[4 * m + 1]) * 180 / Math.PI);
                double thNow = psi - phi, thPrev = prev.q4[4 * m + 1] - prev.q4[4 * m];
                acc.dTheta.add(Math.abs(thNow - thPrev) * 180 / Math.PI);
                acc.incrementSamples++;
                boolean segSwitched = g.seg != prev.nearSeg[m];
                if (segSwitched) acc.nearSegChanges++;
                double pSurf = prevSurf(e, prev, m);
                if (!segSwitched) acc.dNearDist.add(Math.abs(g.surf - pSurf));   // clean: exclude seg-identity-switch jumps

                // -------- Part B substep interpolation over [prev, cur] (near-gate encounters only) --------
                acc.intervalCount++;
                boolean epPrev = prev.passEp[m], epCur = g.allPass;
                boolean interiorPass = false;
                if (!epPrev && !epCur && (g.surf < NEAR_SURF_NM || pSurf < NEAR_SURF_NM)) {   // candidate only if near-gate
                    acc.nearIntervals++;
                    acc.ensureScratch(nSeg);
                    double[] fr = { 0.25, 0.5, 0.75 };
                    for (double a : fr) {
                        // interpolate q, xF8, xH linearly; filament pose linearly (uVec renormalized)
                        double iPhi = lerp(prev.q4[4 * m], phi, a), iPsi = lerp(prev.q4[4 * m + 1], psi, a);
                        double iThS = lerp(prev.q4[4 * m + 2], thetaS, a), iPsiA = lerp(prev.q4[4 * m + 3], psiActin, a);
                        double iF8x = lerp(pF8x, xF8x, a), iF8y = lerp(pF8y, xF8y, a), iF8z = lerp(pF8z, xF8z, a);
                        double iHx = lerp(pHx, xHx, a), iHy = lerp(pHy, xHy, a), iHz = lerp(pHz, xHz, a);
                        GEval gi = evalInterp(e, acc, iF8x, iF8y, iF8z, iHx, iHy, iHz, iPhi, iPsi, iThS, iPsiA,
                                prev, cCx, cCy, cCz, cUx, cUy, cUz, cHalf, a, nSeg, m);
                        if (gi != null && gi.valid && gi.allPass) { interiorPass = true; break; }
                    }
                }
                if (interiorPass) {
                    acc.substepIntervals++;
                    // which gate fails at endpoint (use cur endpoint's sole/first failing gate)
                    for (int gi = 0; gi < GATES; gi++) if (!g.pass[gi]) { acc.endpointMissGate[gi]++; break; }
                    if (!acc.inSubRun[m]) { acc.substepOnlyEpisodes++; acc.inSubRun[m] = true; }
                } else acc.inSubRun[m] = false;

                // episode duration: contiguous endpoint all-pass (leading to a bind). Since a bind removes the motor,
                // an all-pass endpoint IS a bind (endpoint-detected episode). Count and record its 1-step span.
                if (g.allPass) { acc.endpointEpisodes++; acc.episodeSteps.add(1); acc.episTot++; acc.episLt1++ /*<1 = sub-step scale search*/; }
            } else if (elig && g != null && g.valid && g.allPass) {
                // motor newly eligible this step and already all-pass → an endpoint-detected episode
                acc.endpointEpisodes++; acc.episodeSteps.add(1); acc.episTot++; acc.episLt1++;
            }

            // store current as prev
            prev.xF8[3 * m] = xF8x; prev.xF8[3 * m + 1] = xF8y; prev.xF8[3 * m + 2] = xF8z;
            prev.xH[3 * m] = xHx; prev.xH[3 * m + 1] = xHy; prev.xH[3 * m + 2] = xHz;
            prev.nodeM[3 * m] = nMx; prev.nodeM[3 * m + 1] = nMy; prev.nodeM[3 * m + 2] = nMz;
            prev.node0[3 * m] = n0x; prev.node0[3 * m + 1] = n0y; prev.node0[3 * m + 2] = n0z;
            prev.q4[4 * m] = phi; prev.q4[4 * m + 1] = psi; prev.q4[4 * m + 2] = thetaS; prev.q4[4 * m + 3] = psiActin;
            prev.elig[m] = elig; prev.passEp[m] = (g != null && g.valid && g.allPass); prev.nearSeg[m] = (g != null && g.valid) ? g.seg : -1;
        }
        for (int s = 0; s < nSeg; s++) { prev.segCx[s] = cCx[s]; prev.segCy[s] = cCy[s]; prev.segCz[s] = cCz[s]; prev.segUx[s] = cUx[s]; prev.segUy[s] = cUy[s]; prev.segUz[s] = cUz[s]; prev.segHalf[s] = cHalf[s]; }
        prev.have = true;
    }

    // gate width (physical) for near-miss scaling
    static double gateWidth(ExMat e, int g) {
        switch (g) {
            case 0: return e.bindP.get(0);           // dist nm
            case 1: return e.bindP.get(1);           // psi deg
            case 2: return e.bindP.get(2);           // phi deg
            case 3: return e.bindP.get(3);           // theta deg
            case 4: return e.bindP.get(4);           // preload pN
            case 5: return e.bindP.get(5);           // energy kT
            case 6: return e.bindP.get(8) * 1e3;     // headside nm
            default: return 3.0;                     // insegment nm (rough)
        }
    }

    // gate eval on interpolated coords vs an interpolated filament pose
    static GEval evalInterp(ExMat e, ABC acc, double xF8x, double xF8y, double xF8z, double xHx, double xHy, double xHz,
                            double phi, double psi, double thetaS, double psiActin, Prev prev,
                            double[] cCx, double[] cCy, double[] cCz, double[] cUx, double[] cUy, double[] cUz, double[] cHalf,
                            double a, int nSeg, int m) {
        // build interpolated filament pose into reusable scratch (small nSeg)
        FloatArray fc = acc.sfc, fu = acc.sfu, fl = acc.sfl;
        for (int s = 0; s < nSeg; s++) {
            double cx = lerp(prev.segCx[s], cCx[s], a), cy = lerp(prev.segCy[s], cCy[s], a), cz = lerp(prev.segCz[s], cCz[s], a);
            double ux = lerp(prev.segUx[s], cUx[s], a), uy = lerp(prev.segUy[s], cUy[s], a), uz = lerp(prev.segUz[s], cUz[s], a);
            double L = Math.sqrt(ux * ux + uy * uy + uz * uz); if (L > 1e-12) { ux /= L; uy /= L; uz /= L; }
            double half = lerp(prev.segHalf[s], cHalf[s], a);
            fc.set(s, (float) cx); fc.set(nSeg + s, (float) cy); fc.set(2 * nSeg + s, (float) cz);
            fu.set(s, (float) ux); fu.set(nSeg + s, (float) uy); fu.set(2 * nSeg + s, (float) uz);
            fl.set(s, (float) (2 * half));
        }
        return evalGates(e, xF8x, xF8y, xF8z, xHx, xHy, xHz, phi, psi, thetaS, psiActin, fc, fu, fl, nSeg, m);
    }

    static double prevSurf(ExMat e, Prev prev, int m) {
        // recompute prev surf from stored prev xF8 vs prev nearest seg pose (approx Δ near-seg dist)
        int s = prev.nearSeg[m]; if (s < 0) return 0;
        double xF8x = prev.xF8[3 * m], xF8y = prev.xF8[3 * m + 1], xF8z = prev.xF8[3 * m + 2];
        double cx = prev.segCx[s], cy = prev.segCy[s], cz = prev.segCz[s], ux = prev.segUx[s], uy = prev.segUy[s], uz = prev.segUz[s];
        double dx = xF8x - cx, dy = xF8y - cy, dz = xF8z - cz; double foot = dx * ux + dy * uy + dz * uz;
        double axx = cx + foot * ux, axy = cy + foot * uy, axz = cz + foot * uz;
        double conDist = Math.sqrt(sq(xF8x - axx) + sq(xF8y - axy) + sq(xF8z - axz));
        return (conDist - e.bindP.get(6)) * 1e3;
    }

    static double sq(double x) { return x * x; }
    static double lerp(double a, double b, double f) { return a + (b - a) * f; }

    // ============================================================ Part D1 / E — recruitment assay + quality
    static String recruitMode(StringBuilder rep, double density, int seed, double dt, int dtdiv,
                              double equilMs, double measMs, double phiDeg, double psiDeg, double thetaDeg, R3 r3, String tag) {
        ExMat e = buildScene(density, seed, dt, phiDeg, psiDeg, thetaDeg, r3);
        int N = e.N, nSeg = e.nSeg;
        int equil = (int) Math.round(equilMs * 1e-3 / dt), meas = (int) Math.round(measMs * 1e-3 / dt);
        double myoSpring = e.G.xbParams.get(0);   // realized bond spring (for extension = |F8|/myoSpring)

        // quality distributions of admitted binds
        Dist qPhi = new Dist(21), qPsi = new Dist(22), qTheta = new Dist(23), qDist = new Dist(24), qF8 = new Dist(25),
             qEnergy = new Dist(26), qPreload = new Dist(27), qTorq = new Dist(28), qLifetime = new Dist(29),
             qExt = new Dist(30), qEndDist = new Dist(31), qFirstFil = new Dist(32);
        long binds = 0, detaches = 0, rebinds = 0, newlyAdmitted = 0, immediateDetach = 0, invalid = 0;
        double boundSum = 0; long boundSteps = 0, stepsWithBound = 0;
        // gate funnel (over eligible unbound ADP·Pi motor-steps) + leave-one-out (sole-fail per gate)
        long eligSteps = 0, distPass = 0, preloadPass = 0, insegPass = 0, allPass = 0;
        long[] loo = new long[GATES];
        // propulsive/dragging over bound-motor-steps (forceDotFil sign after the step)
        long boundMotorSteps = 0, dragSteps = 0; double fdotSum = 0;

        Glide2D G = e.G; FilamentStore f = G.fil; MotorStore mot = G.mot; RigidRodBody b = mot.body;
        int[] lifeStart = new int[N]; Arrays.fill(lifeStart, -1);
        boolean[] everBound = new boolean[N];
        double[] segCxPre = new double[nSeg];

        for (int t = 0; t < equil; t++) stepFast(e, t, seed);
        for (int t = equil; t < equil + meas; t++) {
            // pre-bind geometry
            e.matc.set(0, t); e.matc.set(1, seed); mot.setCounts(t, seed, e.nSeg); f.counts.set(1, t); f.counts.set(2, seed);
            for (int m = 0; m < N; m++) e.active.set(m, 1);
            for (int s = 0; s < nSeg; s++) segCxPre[s] = f.coord.get(s);   // for first-step fil displacement
            TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom);
            int[] preBound = new int[N]; for (int m = 0; m < N; m++) preBound[m] = mot.boundSeg.get(m);
            GEval[] pre = new GEval[N];
            for (int m = 0; m < N; m++) {
                boolean elig = e.active.get(m) == 1 && e.noBind.get(m) == 0 && mot.boundSeg.get(m) == -1 && mot.nucleotideState.get(m) == 2;
                if (!elig) continue;
                double xF8x = e.outGeom.get(6 * N + m), xF8y = e.outGeom.get(7 * N + m), xF8z = e.outGeom.get(8 * N + m);
                double xHx = e.outGeom.get(3 * N + m), xHy = e.outGeom.get(4 * N + m), xHz = e.outGeom.get(5 * N + m);
                double phi = e.q.get(m), psi = e.q.get(N + m), thetaS = e.q.get(2 * N + m), psiActin = e.q.get(3 * N + m);
                GEval g = evalGates(e, xF8x, xF8y, xF8z, xHx, xHy, xHz, phi, psi, thetaS, psiActin, f.coord, f.uVec, f.segLength, nSeg, m);
                pre[m] = g;
                if (g.valid) {   // funnel + leave-one-out over eligible motor-steps
                    eligSteps++;
                    if (g.pass[0]) distPass++; if (g.pass[4]) preloadPass++; if (g.pass[7]) insegPass++;
                    if (g.allPass) allPass++;
                    int fails = 0, sole = -1; for (int gi = 0; gi < GATES; gi++) if (!g.pass[gi]) { fails++; sole = gi; }
                    if (fails == 1) loo[sole]++;
                }
            }
            TwoBodyBeamAnalyticGpu.matBindExplicit(e.active, e.noBind, mot.boundSeg, mot.nucleotideState, e.outGeom, e.q, f.coord, f.uVec, f.segLength, e.params, e.bindP, e.eupP, mot.bindArc, e.exCounts);
            // detect binds this step; record geometry quality; classify newly-admitted (fails default 25/25/20/3/2/margin)
            for (int m = 0; m < N; m++) {
                if (preBound[m] < 0 && mot.boundSeg.get(m) >= 0) {
                    binds++; if (everBound[m]) rebinds++; everBound[m] = true; lifeStart[m] = t;
                    GEval g = pre[m];
                    if (g != null && g.valid) {
                        double DEG = 180.0 / Math.PI, PHI_PRE = e.bindP.get(7);
                        double phiErr = Math.abs(e.q.get(m) - PHI_PRE) * DEG;
                        double psiErr = Math.abs(e.q.get(N + m) - e.q.get(3 * N + m)) * DEG;
                        double thetaErr = Math.abs((e.q.get(N + m) - e.q.get(m)) - e.q.get(2 * N + m)) * DEG;
                        qPhi.add(phiErr); qPsi.add(psiErr); qTheta.add(thetaErr); qDist.add(g.surf); qEnergy.add(g.eKt); qPreload.add(g.preload);
                        qEndDist.add(Math.min(g.margin[7] < 0 ? 0 : g.margin[7] + e.bindP.get(10) * 1e3, 1e9));   // ~dist to nearest seg end (nm)
                        // newly-admitted = fails the DEFAULT contract (25/25/20 & 3nm & 2pN & 0.05µm) but passes current
                        boolean newly = phiErr >= 25 || psiErr >= 25 || thetaErr >= 20 || g.surf >= 3.0 || g.preload >= 2.0
                                || g.bindArcV <= 0.05 || g.bindArcV >= 2 * (0.5 * f.segLength.get(g.seg)) - 0.05;
                        if (newly) newlyAdmitted++;
                    }
                }
            }
            // bond forces + onset F8/torque/extension captured post-place
            NucleotideCycleSystem.cycleLymnTaylor(mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts);
            MatSoaSlice.matCock(mot.nucleotideState, e.q, e.cockP, e.exCounts);
            TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit(e.outGeom, mot.boundSeg, e.eupP, e.exCounts, b.coord, b.uVec, b.yVec);
            CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams);
            for (int m = 0; m < N; m++) if (lifeStart[m] == t) {
                double fx = G.bondData.get(m * 13), fy = G.bondData.get(m * 13 + 1), fz = G.bondData.get(m * 13 + 2);
                double fpN = Math.sqrt(fx * fx + fy * fy + fz * fz) * 1e12; qF8.add(fpN);
                double tx = G.bondData.get(m * 13 + 3), ty = G.bondData.get(m * 13 + 4), tz = G.bondData.get(m * 13 + 5);
                qTorq.add(Math.sqrt(tx * tx + ty * ty + tz * tz) * 1e12);            // onset head torque (pN·µm)
                qExt.add(myoSpring > 0 ? fpN / (myoSpring * 1e12) * 1e3 : 0);        // realized XB extension (nm)
            }
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            CrossBridgeSystem.csrChunkZero(e.csrChunkParams, mot.counts, e.csrMatrix);
            CrossBridgeSystem.csrChunkHistogram(mot.boundSeg, mot.counts, e.csrChunkParams, e.csrMatrix);
            CrossBridgeSystem.csrChunkReduce(mot.counts, e.csrChunkParams, e.csrMatrix, G.segCount);
            CrossBridgeSystem.csrScan(mot.counts, G.segCount, G.segOff);
            CrossBridgeSystem.csrChunkScatter(mot.boundSeg, mot.counts, e.csrChunkParams, G.segOff, G.segMyo, e.csrMatrix);
            CrossBridgeSystem.segGather(G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts);
            if (!G.rigid) ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
            MatSoaSlice.matZConfine(f.coord, f.forceSum, e.zP, e.exCounts);
            BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
            RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
            DerivedGeometrySystem.orthogonalizeY(f.uVec, f.yVec, f.counts);
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
            TwoBodyBeamAnalyticGpu.matS2SolveStep(e.nodes, e.frame, e.q, G.bondData, mot.boundSeg, e.params, e.sys, e.outGeom, mot.forceDotFil, mot.forceMag, e.matc, e.exCounts);
            MatSoaSlice.matReduceBlocks(mot.boundSeg, e.active, mot.forceDotFil, e.redP, e.exCounts, e.redBlk);
            MatSoaSlice.matReduceFinal(e.redBlk, f.coord, e.redP, e.exCounts, e.redOut);
            // first-step filament displacement for motors that bound this step (bound-seg centroid move, nm)
            for (int m = 0; m < N; m++) if (lifeStart[m] == t) { int s = mot.boundSeg.get(m); if (s >= 0) qFirstFil.add(Math.abs(f.coord.get(s) - segCxPre[s]) * 1e3); }
            // propulsive/dragging over currently-bound motors (forceDotFil sign; glide is −x ⇒ net sign defines drag)
            for (int m = 0; m < N; m++) if (mot.boundSeg.get(m) >= 0) { double fd = mot.forceDotFil.get(m); boundMotorSteps++; fdotSum += fd; if (fd > 0) dragSteps++; }
            // detachment detection (boundSeg → -1 after chemistry) + lifetime capture
            for (int m = 0; m < N; m++) {
                if (preBound[m] >= 0 && mot.boundSeg.get(m) < 0) {
                    detaches++;
                    if (lifeStart[m] >= 0) { int life = t - lifeStart[m]; qLifetime.add(life); if (life <= 1) immediateDetach++; lifeStart[m] = -1; }
                }
            }
            double nb = e.redOut.get(0); boundSum += nb; boundSteps++; if (nb > 0) stepsWithBound++;
            if (Double.isNaN(nb) || Double.isInfinite(nb) || Double.isNaN(e.redOut.get(1))) invalid++;
        }
        double physMs = meas * dt * 1e3;
        double bindRatePerMotorPerS = binds / (double) N / (meas * dt);
        double meanBound = boundSum / Math.max(1, boundSteps);
        double continuity = (double) stepsWithBound / Math.max(1, boundSteps);
        double rebindRate = binds > 0 ? (double) rebinds / binds : 0;
        // dragging fraction = MINORITY (consensus-opposing) fraction of bound-motor-steps by forceDotFil sign
        double meanFdot = boundMotorSteps > 0 ? fdotSum / boundMotorSteps : 0;
        long minoritySteps = Math.min(dragSteps, boundMotorSteps - dragSteps);
        double dragFrac = boundMotorSteps > 0 ? (double) minoritySteps / boundMotorSteps : 0;
        double[] fq = qF8.quantiles(0.5, 0.90, 0.95, 0.99), tq = qTorq.quantiles(0.5, 0.95), xq = qExt.quantiles(0.5, 0.95),
                 eq = qEnergy.quantiles(0.5, 0.95), lq = qLifetime.quantiles(0.5, 0.90), sq = qDist.quantiles(0.5, 0.95);

        rep.append("# Recruitment + quality assay — density ").append((int) density).append(", seed ").append(seed)
           .append(String.format(Locale.US, ", rBind=%.2fnm preload=%.2fpN segMargin=%.4fµm (phi/psi/theta=%.0f/%.0f/%.0f)\n\n", r3.rBind, r3.preloadMax, r3.segMargin, phiDeg, psiDeg, thetaDeg));
        rep.append(String.format(Locale.US, "N=%d meas=%d steps (%.2f ms), binds=%d detaches=%d rebinds=%d meanBound=%.3f bindRate=%.4g/motor/s\n", N, meas, physMs, binds, detaches, rebinds, meanBound, bindRatePerMotorPerS));
        rep.append(String.format(Locale.US, "continuity=%.4f newlyAdmitted=%d immediateDetach=%d dragFrac=%.4f meanFdotFil=%.3e invalid=%d\n\n", continuity, newlyAdmitted, immediateDetach, dragFrac, meanFdot, invalid));
        rep.append("## Gate funnel (eligible unbound ADP·Pi motor-steps) + leave-one-out\n```\n");
        rep.append(String.format(Locale.US, "eligible=%d  distPass=%d(%.3f%%)  preloadPass=%d(%.3f%%)  insegPass=%d(%.3f%%)  allPass=%d\n",
                eligSteps, distPass, 100.0 * distPass / Math.max(1, eligSteps), preloadPass, 100.0 * preloadPass / Math.max(1, eligSteps), insegPass, 100.0 * insegPass / Math.max(1, eligSteps), allPass));
        rep.append("leave-one-out (sole-fail): ");
        for (int gi = 0; gi < GATES; gi++) rep.append(String.format(Locale.US, "%s=%d ", GNAME[gi], loo[gi]));
        rep.append("\n```\n\n## Admitted-attachment quality (all binds)\n```\n");
        rep.append(qDist.line("surf@bind", "nm", 0)).append("\n");
        rep.append(qF8.line("onset F8", "pN", 0)).append("\n");
        rep.append(qTorq.line("onset torque", "pN·µm", 0)).append("\n");
        rep.append(qExt.line("XB extension", "nm", 0)).append("\n");
        rep.append(qEnergy.line("attach E (torsional)", "kT", 0)).append("\n");
        rep.append(qPreload.line("preload@bind", "pN", 0)).append("\n");
        rep.append(qFirstFil.line("1st-step fil disp", "nm", 0)).append("\n");
        rep.append(qLifetime.line("lifetime", "steps", 0)).append("\n");
        rep.append("```\n");

        // CSV schema (NEW STUDY): tag,recruit,density,seed, phi,psi,theta,rBind,preloadMax,segMargin,
        //   binds,detaches,rebinds,meanBound,bindRate,continuity,newlyAdmitted,immediateDetach,invalid,
        //   eligSteps,distPass,preloadPass,insegPass,allPass, loo_dist,loo_preload,loo_inseg,
        //   onsetF8med,onsetF8p95,onsetF8p99, torqMed,extMed,attachEmed,lifetimeMed,dragFrac,meanFdot,surfMed
        StringBuilder csv = new StringBuilder();
        csv.append(tag).append(",recruit,").append((int) density).append(",").append(seed);
        csv.append(String.format(Locale.US, ",%.1f,%.1f,%.1f,%.3f,%.3f,%.4f", phiDeg, psiDeg, thetaDeg, r3.rBind, r3.preloadMax, r3.segMargin));
        csv.append(String.format(Locale.US, ",%d,%d,%d,%.5f,%.6g,%.5f,%d,%d,%d", binds, detaches, rebinds, meanBound, bindRatePerMotorPerS, continuity, newlyAdmitted, immediateDetach, invalid));
        csv.append(String.format(Locale.US, ",%d,%d,%d,%d,%d", eligSteps, distPass, preloadPass, insegPass, allPass));
        csv.append(String.format(Locale.US, ",%d,%d,%d", loo[0], loo[4], loo[7]));
        csv.append(String.format(Locale.US, ",%.4f,%.4f,%.4f", fq[0], fq[2], fq[3]));
        csv.append(String.format(Locale.US, ",%.4f,%.4f,%.4f,%.2f,%.5f,%.4e,%.4f", tq[0], xq[0], eq[0], lq[0], dragFrac, meanFdot, sq[0]));
        return csv.toString();
    }

    // ============================================================ Part D2 — reduced gliding velocity panel
    static String glideMode(StringBuilder rep, double density, int seed, double dt,
                            double phiDeg, double psiDeg, double thetaDeg, int equilSteps, int measSteps, R3 r3, boolean gpu, String tag) {
        ExMat e = buildScene(density, seed, dt, phiDeg, psiDeg, thetaDeg, r3);
        int N = e.N;
        Glide2D G = e.G; MotorStore mot = G.mot;
        if (gpu) return glideGpu(rep, e, density, seed, dt, phiDeg, psiDeg, thetaDeg, equilSteps, measSteps, r3, tag);
        for (int t = 0; t < equilSteps; t++) stepFast(e, t, seed);
        // measure centroid.x slope over meas steps
        double sx = 0, sy = 0, sxx = 0, sxy = 0; int n = 0; double boundSum = 0; long binds = 0, detaches = 0; int[] pre = new int[N];
        double invalid = 0; long stepsWithBound = 0, boundMotorSteps = 0, dragSteps = 0; double fdotSum = 0;
        for (int t = equilSteps; t < equilSteps + measSteps; t++) {
            for (int m = 0; m < N; m++) pre[m] = mot.boundSeg.get(m);
            stepFast(e, t, seed);
            for (int m = 0; m < N; m++) { if (pre[m] < 0 && mot.boundSeg.get(m) >= 0) binds++; if (pre[m] >= 0 && mot.boundSeg.get(m) < 0) detaches++;
                if (mot.boundSeg.get(m) >= 0) { double fd = mot.forceDotFil.get(m); boundMotorSteps++; fdotSum += fd; if (fd > 0) dragSteps++; } }
            double cx = e.redOut.get(1), nb = e.redOut.get(0);
            double time = (t - equilSteps) * dt;
            sx += time; sy += cx; sxx += time * time; sxy += time * cx; n++; boundSum += nb; if (nb > 0) stepsWithBound++;
            if (Double.isNaN(cx) || Double.isInfinite(cx) || Double.isNaN(nb)) invalid++;
        }
        double slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);   // µm/s (centroid x per second)
        double meanBound = boundSum / Math.max(1, n);
        double physS = measSteps * dt;
        double bindRate = binds / (double) N / physS, detachRate = detaches / (double) N / physS;
        double continuity = (double) stepsWithBound / Math.max(1, n);
        double meanFdot = boundMotorSteps > 0 ? fdotSum / boundMotorSteps : 0;
        double dragFrac = boundMotorSteps > 0 ? (double) Math.min(dragSteps, boundMotorSteps - dragSteps) / boundMotorSteps : 0;
        rep.append("# Gliding velocity panel (§8) — density ").append((int) density).append(", seed ").append(seed)
           .append(String.format(Locale.US, ", rBind=%.2f preload=%.2f segMargin=%.4f\n\n", r3.rBind, r3.preloadMax, r3.segMargin));
        rep.append(String.format(Locale.US, "N=%d equil=%d meas=%d (%.2f ms) velocity=%.4f µm/s meanBound=%.3f continuity=%.4f bindRate=%.4g detachRate=%.4g dragFrac=%.4f meanFdot=%.3e binds=%d detaches=%d invalid=%.0f\n",
                N, equilSteps, measSteps, physS * 1e3, slope, meanBound, continuity, bindRate, detachRate, dragFrac, meanFdot, binds, detaches, invalid));
        // CSV: tag,glide,density,seed, phi,psi,theta,rBind,preloadMax,segMargin, slope,meanBound,continuity,bindRate,detachRate,dragFrac,meanFdot,binds,detaches,invalid
        StringBuilder csv = new StringBuilder();
        csv.append(tag).append(",glide,").append((int) density).append(",").append(seed);
        csv.append(String.format(Locale.US, ",%.1f,%.1f,%.1f,%.3f,%.3f,%.4f", phiDeg, psiDeg, thetaDeg, r3.rBind, r3.preloadMax, r3.segMargin));
        csv.append(String.format(Locale.US, ",%.5f,%.4f,%.5f,%.6g,%.6g,%.4f,%.4e,%d,%d,%.0f", slope, meanBound, continuity, bindRate, detachRate, dragFrac, meanFdot, binds, detaches, invalid));
        return csv.toString();
    }

    /** §8 GPU device-resident glide (free-binding graph; bindP uploaded FIRST_EXECUTION ⇒ reach overrides take effect).
     *  Changing gate CONSTANTS does NOT alter kernel structure (no added/removed task, no toggled transcendental) ⇒ the
     *  graph is byte-identical; only uploaded values differ. CPU low-density runs are the arbiter cross-check. */
    static String glideGpu(StringBuilder rep, ExMat e, double density, int seed, double dt,
                           double phiDeg, double psiDeg, double thetaDeg, int equilSteps, int measSteps, R3 r3, String tag) {
        int N = e.N; Glide2D G = e.G; MotorStore mot = G.mot; FilamentStore f = G.fil; int nSeg = e.nSeg;
        TornadoExecutionPlan plan;
        try { plan = ExplicitCompleteMatHarness.buildGlidingGraph(e, true); }
        catch (Throwable ex) { rep.append("GPU graph build FAILED: ").append(ex).append("\n");
            return tag + ",glide,-1,-1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1"; }
        int[] boundPrev = new int[N];
        for (int t = 0; t < equilSteps; t++) { e.matc.set(0, t); e.matc.set(1, seed); mot.setCounts(t, seed, nSeg); f.counts.set(1, t); f.counts.set(2, seed); plan.execute(); }
        double sx = 0, sy = 0, sxx = 0, sxy = 0; int n = 0; double boundSum = 0; long binds = 0, detaches = 0; double invalid = 0; long stepsWithBound = 0;
        for (int m = 0; m < N; m++) boundPrev[m] = mot.boundSeg.get(m);
        for (int t = equilSteps; t < equilSteps + measSteps; t++) {
            e.matc.set(0, t); e.matc.set(1, seed); mot.setCounts(t, seed, nSeg); f.counts.set(1, t); f.counts.set(2, seed);
            plan.execute();
            for (int m = 0; m < N; m++) { int bs = mot.boundSeg.get(m); if (boundPrev[m] < 0 && bs >= 0) binds++; if (boundPrev[m] >= 0 && bs < 0) detaches++; boundPrev[m] = bs; }
            double cx = e.redOut.get(1), nb = e.redOut.get(0); double time = (t - equilSteps) * dt;
            sx += time; sy += cx; sxx += time * time; sxy += time * cx; n++; boundSum += nb; if (nb > 0) stepsWithBound++;
            if (Double.isNaN(cx) || Double.isInfinite(cx) || Double.isNaN(nb)) invalid++;
        }
        double slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
        double meanBound = boundSum / Math.max(1, n), continuity = (double) stepsWithBound / Math.max(1, n);
        double physS = measSteps * dt, bindRate = binds / (double) N / physS, detachRate = detaches / (double) N / physS;
        rep.append("# Gliding velocity panel (§8, GPU device-resident) — density ").append((int) density).append(", seed ").append(seed)
           .append(String.format(Locale.US, ", rBind=%.2f preload=%.2f segMargin=%.4f\n\n", r3.rBind, r3.preloadMax, r3.segMargin));
        rep.append(String.format(Locale.US, "N=%d equil=%d meas=%d (%.2f ms) velocity=%.4f µm/s meanBound=%.3f continuity=%.4f bindRate=%.4g detachRate=%.4g binds=%d detaches=%d invalid=%.0f  [GPU; dragFrac N/A]\n",
                N, equilSteps, measSteps, physS * 1e3, slope, meanBound, continuity, bindRate, detachRate, binds, detaches, invalid));
        StringBuilder csv = new StringBuilder();
        csv.append(tag).append(",glide,").append((int) density).append(",").append(seed);
        csv.append(String.format(Locale.US, ",%.1f,%.1f,%.1f,%.3f,%.3f,%.4f", phiDeg, psiDeg, thetaDeg, r3.rBind, r3.preloadMax, r3.segMargin));
        csv.append(String.format(Locale.US, ",%.5f,%.4f,%.5f,%.6g,%.6g,%.4f,%.4e,%d,%d,%.0f", slope, meanBound, continuity, bindRate, detachRate, -1.0, 0.0, binds, detaches, invalid));
        return csv.toString();
    }

    /** Fast step: the exact free-binding CPU sequence, no diagnostics (for equilibration + glide/recruit background). */
    static void stepFast(ExMat e, int t, int seed) {
        Glide2D G = e.G; FilamentStore f = G.fil; MotorStore mot = G.mot; RigidRodBody b = mot.body; int N = e.N;
        e.matc.set(0, t); e.matc.set(1, seed); mot.setCounts(t, seed, e.nSeg); f.counts.set(1, t); f.counts.set(2, seed);
        for (int m = 0; m < N; m++) e.active.set(m, 1);
        TwoBodyBeamAnalyticGpu.matBeamGeom(e.nodes, e.frame, e.params, e.q, e.exCounts, e.outGeom);
        TwoBodyBeamAnalyticGpu.matBindExplicit(e.active, e.noBind, mot.boundSeg, mot.nucleotideState, e.outGeom, e.q, f.coord, f.uVec, f.segLength, e.params, e.bindP, e.eupP, mot.bindArc, e.exCounts);
        NucleotideCycleSystem.cycleLymnTaylor(mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts);
        MatSoaSlice.matCock(mot.nucleotideState, e.q, e.cockP, e.exCounts);
        TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit(e.outGeom, mot.boundSeg, e.eupP, e.exCounts, b.coord, b.uVec, b.yVec);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        CrossBridgeSystem.csrChunkZero(e.csrChunkParams, mot.counts, e.csrMatrix);
        CrossBridgeSystem.csrChunkHistogram(mot.boundSeg, mot.counts, e.csrChunkParams, e.csrMatrix);
        CrossBridgeSystem.csrChunkReduce(mot.counts, e.csrChunkParams, e.csrMatrix, G.segCount);
        CrossBridgeSystem.csrScan(mot.counts, G.segCount, G.segOff);
        CrossBridgeSystem.csrChunkScatter(mot.boundSeg, mot.counts, e.csrChunkParams, G.segOff, G.segMyo, e.csrMatrix);
        CrossBridgeSystem.segGather(G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts);
        if (!G.rigid) ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        MatSoaSlice.matZConfine(f.coord, f.forceSum, e.zP, e.exCounts);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.orthogonalizeY(f.uVec, f.yVec, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        TwoBodyBeamAnalyticGpu.matS2SolveStep(e.nodes, e.frame, e.q, G.bondData, mot.boundSeg, e.params, e.sys, e.outGeom, mot.forceDotFil, mot.forceMag, e.matc, e.exCounts);
        MatSoaSlice.matReduceBlocks(mot.boundSeg, e.active, mot.forceDotFil, e.redP, e.exCounts, e.redBlk);
        MatSoaSlice.matReduceFinal(e.redBlk, f.coord, e.redP, e.exCounts, e.redOut);
    }
}
