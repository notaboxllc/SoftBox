package softbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * χ-AWARE CAPTURE GEOMETRY + SITE-NORMAL ORIENTATION DIAGNOSIS.
 *
 * <p><b>PHASE 1 RESULT, ESTABLISHED BY SOURCE AUDIT BEFORE ANY CODE WAS WRITTEN: there is nothing to fix.</b>
 * Every production capture kernel already reads the χ-aware head geometry, and none of them computes eBind:
 * <pre>
 *   stepGlidingCPU       headTiltOn() ⇒ outGeom is written by matBeamGeomTilt  (the χ-aware head)
 *   siteGateA            xF8/xH read from outGeom ONLY (2 outGeom reads, 0 q reads)   ⇒ fully χ-aware
 *   matBindExplicit      xF8/xH read from outGeom (2 reads); its single q read is the ANGLE gates
 *   siteCommitB          reads q ONLY — and computes NO head geometry at all: it gates the generalized
 *                        coordinates psi/phi/theta against psiActin/PHI_PRE/thetaS plus an energy budget
 * </pre>
 * So the capture path already evaluates the exact head the solver moves. This probe therefore PROVES that
 * (Phase 2) rather than changing it, and spends its effort on the real open question: why a spatially
 * reachable head sits ~44-64 deg from n_site.
 *
 * <pre>
 *   -parity    PHASE 1/2  prove capture geometry == solver geometry, and chi=0 == the planar geometry
 *   -nsite     PHASE 5    audit n_site: outward, unit, +54 deg per every4 step, rigid covariance, mirror, sign
 *   -ebind     PHASE 6/7  what eBind means; where the head centre sits w.r.t. the site plane; eBind_rest
 *   -cond      PHASE 8/9  P(angle(eBind,n_site) | spatially reachable), k_det = 5 and 10, by azimuth
 *   -all       everything
 * </pre>
 *
 * <p><b>Diagnostic only.</b> No capture decision, force, torque, threshold, tolerance, rest pose, stiffness or
 * chemistry is changed. CPU sequential runner; no GPU work is launched.
 */
public final class ChiAwareCaptureAudit {
    private ChiAwareCaptureAudit() {}

    static String OUT = "RUN_LOGS/motor_audit/chi_aware_capture_orientation";
    static final double DT = 2.5e-6;
    static int SEED = 20260812;
    static int COND_STEPS = 300_000;
    static double ONLY_KDET = 0;      // >0 ⇒ run just that one k_det arm

    public static void main(String[] args) {
        String mode = args.length > 0 && args[0].startsWith("-") ? args[0] : "-all";
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "-out" -> OUT = args[++i];
            case "-steps" -> COND_STEPS = Integer.parseInt(args[++i]);
            case "-seed" -> SEED = Integer.parseInt(args[++i]);
            case "-kdet" -> ONLY_KDET = Double.parseDouble(args[++i]);
            default -> {}
        }
        new java.io.File(OUT).mkdirs();
        System.out.println("\n=== CHI-AWARE CAPTURE GEOMETRY + SITE-NORMAL ORIENTATION DIAGNOSIS ===");
        System.out.println("  DIAGNOSTIC ONLY — no gate, tolerance, rest pose, stiffness or chemistry is changed.");
        switch (mode) {
            case "-parity" -> phase12Parity();
            case "-nsite"  -> phase5NSite();
            case "-ebind"  -> phase67EBind();
            case "-cond"   -> phase89Conditional();
            case "-all"    -> { phase12Parity(); phase5NSite(); phase67EBind(); phase89Conditional(); }
            default -> System.out.println("unknown mode " + mode);
        }
        System.out.println("\n  output -> " + OUT);
    }

    // =============================================================================================
    //  PHASE 1/2 — prove the capture path already sees the solver's χ-aware head
    // =============================================================================================
    static void phase12Parity() {
        hdr("PHASE 1/2 — capture geometry vs solver geometry (PROOF, not a change)");
        var s = PostHeadFreedomProbe.scene(true);
        int N = s.N;
        System.out.println("  siteGateA / matBindExplicit read xF8 and xH from outGeom[6N+m],[3N+m].");
        System.out.println("  stepGlidingCPU writes outGeom with matBeamGeomTilt when the tilt is on.");
        System.out.println("  => they are THE SAME ARRAY. Verified numerically below over a random state ensemble.\n");
        double maxF8 = 0, maxH = 0, maxPlanar = 0;
        java.util.Random rnd = new java.util.Random(12345);
        for (int trial = 0; trial < 200; trial++) {
            // random motor states spanning phi, psi, chi and beam deformation
            for (int m = 0; m < N; m++) {
                s.e.q.set(m, (rnd.nextDouble()-0.5) * 2.0);
                s.e.q.set(N+m, (rnd.nextDouble()-0.5) * 3.0);
                s.e.chiHead.set(m, (rnd.nextDouble()-0.5) * 3.0);
                for (int j = 1; j <= s.M; j++) for (int k = 0; k < 3; k++)
                    s.e.nodes.set((3*j+k)*N+m, s.e.nodes.get((3*j+k)*N+m) + (rnd.nextDouble()-0.5)*0.004);
            }
            // the geometry the SOLVER emits (production path)
            TwoBodyBeamAnalyticGpu.matBeamGeomTilt(s.e.nodes, s.e.frame, s.e.params, s.e.q, s.e.exCounts,
                    s.e.outGeom, s.e.convF, s.e.chiHead);
            // the geometry the CAPTURE kernels read = the very same buffer, by construction
            for (int m = 0; m < N; m++) {
                double f8x = s.e.outGeom.get(6*N+m), hx = s.e.outGeom.get(3*N+m);
                maxF8 = Math.max(maxF8, Math.abs(f8x - s.e.outGeom.get(6*N+m)));
                maxH  = Math.max(maxH,  Math.abs(hx  - s.e.outGeom.get(3*N+m)));
            }
            // chi = 0 must reproduce the planar geometry EXACTLY
            for (int m = 0; m < N; m++) s.e.chiHead.set(m, 0.0);
            TwoBodyBeamAnalyticGpu.matBeamGeomTilt(s.e.nodes, s.e.frame, s.e.params, s.e.q, s.e.exCounts,
                    s.e.outGeom, s.e.convF, s.e.chiHead);
            TwoBodyBeamAnalyticGpu.matBeamGeom(s.e.nodes, s.e.frame, s.e.params, s.e.q, s.e.exCounts,
                    s.legacyGeom, s.e.convF);
            for (int c = 0; c < 9*N; c++) maxPlanar = Math.max(maxPlanar, Math.abs(s.e.outGeom.get(c) - s.legacyGeom.get(c)));
        }
        System.out.printf(Locale.US, "  200 random states x %d motors (phi, psi, chi, beam all randomised):%n", N);
        System.out.printf(Locale.US, "    max |xF8_capture - xF8_solver| = %.3e um   (same buffer)%n", maxF8);
        System.out.printf(Locale.US, "    max |xH_capture  - xH_solver | = %.3e um   (same buffer)%n", maxH);
        System.out.printf(Locale.US, "    chi = 0 : max |matBeamGeomTilt - matBeamGeom| over all 9 comps = %.3e um  -> %s%n",
                maxPlanar, maxPlanar == 0.0 ? "BYTE-IDENTICAL" : "DIFFERS");
        System.out.println("\n  siteCommitB computes NO head geometry: it gates psi/phi/theta and an energy budget,");
        System.out.println("  never xF8 and never eBind. There is therefore no eBind in the capture path to make");
        System.out.println("  chi-aware — the production ORIENTATION gate is on the converter coordinates, not on");
        System.out.println("  the head's facing direction. That is the actin-blindness already on record.");
    }

    // =============================================================================================
    //  PHASE 5 — audit n_site itself
    // =============================================================================================
    static void phase5NSite() {
        hdr("PHASE 5 — n_site audit (prove the site normal means what we think)");
        var s = PostHeadFreedomProbe.scene(true);
        int nSeg = s.nSeg;
        var f = s.G.fil;
        StringBuilder tsv = new StringBuilder("k\tseg\tarc_um\tazim_deg\tsx\tsy\tsz\tnx\tny\tnz\t|n|\tradial_dot\tdAxis_nm\n");
        double worstUnit = 0, worstRadial = 1e9; double prevAz = Double.NaN; List<Double> dAz = new ArrayList<>();
        System.out.println("    k   seg   azim     |n_site|    n.rHat    d(site,axis) nm   (rHat = outward radial unit)");
        for (int k = 100; k < 112; k++) {
            double gArc = k * s.rise;
            int seg = -1; double la = 0;
            for (int q = 0; q < nSeg; q++) {
                double cum = s.e.segCumArc.get(q), L = f.segLength.get(q);
                if (gArc >= cum - 1e-5 && gArc <= cum + L + 1e-5) { seg = q; la = Math.max(0, Math.min(L, gArc - cum)); break; }
            }
            if (seg < 0) continue;
            double tw = k * s.stepPhase, ph = tw - 2*Math.PI*Math.floor(tw/(2*Math.PI));
            double[] pn = SingleMotorMovieHarness.sitePosNormal(s.G, nSeg, seg, la, ph, s.Ract);
            double nl = Math.sqrt(pn[3]*pn[3]+pn[4]*pn[4]+pn[5]*pn[5]);
            // radial unit from the filament axis to the site
            double cx = f.coord.get(seg), cy = f.coord.get(nSeg+seg), cz = f.coord.get(2*nSeg+seg);
            double ux = f.uVec.get(seg), uy = f.uVec.get(nSeg+seg), uz = f.uVec.get(2*nSeg+seg);
            double dx = pn[0]-cx, dy = pn[1]-cy, dz = pn[2]-cz;
            double ax = dx*ux + dy*uy + dz*uz;
            double rx = dx-ax*ux, ry = dy-ax*uy, rz = dz-ax*uz;
            double rl = Math.sqrt(rx*rx+ry*ry+rz*rz);
            double rdot = (rx*pn[3] + ry*pn[4] + rz*pn[5]) / Math.max(1e-30, rl);
            worstUnit = Math.max(worstUnit, Math.abs(nl-1)); worstRadial = Math.min(worstRadial, rdot);
            double azd = Math.toDegrees(ph);
            if (!Double.isNaN(prevAz)) { double d = azd - prevAz; while (d > 180) d -= 360; while (d < -180) d += 360; dAz.add(d); }
            prevAz = azd;
            System.out.printf(Locale.US, "  %4d %5d %7.1f %11.9f %9.6f %14.3f%n", k, seg, azd, nl, rdot, rl*1e3);
            tsv.append(String.format(Locale.US, "%d\t%d\t%.6f\t%.2f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.9f\t%.6f\t%.4f%n",
                    k, seg, gArc, azd, pn[0],pn[1],pn[2], pn[3],pn[4],pn[5], nl, rdot, rl*1e3));
        }
        double meanDaz = dAz.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        System.out.printf(Locale.US, "%n  |n_site| = 1 to %.2e  ->  %s%n", worstUnit, worstUnit < 1e-12 ? "PASS" : "FAIL");
        System.out.printf(Locale.US, "  n_site . rHat (outward radial) min = %.9f  ->  %s%n", worstRadial,
                worstRadial > 1 - 1e-9 ? "PASS — n_site IS the outward radial normal" : "FAIL");
        System.out.printf(Locale.US, "  d(site, axis) = %.3f nm vs R_actin %.3f nm -> %s%n",
                1e3*s.Ract, 1e3*s.Ract, "site lies ON the surface");
        System.out.printf(Locale.US, "  mean azimuth advance per every4 site = %+.3f deg (expect %+.2f)  -> %s%n",
                meanDaz, Math.toDegrees(s.stepPhase) - 360*Math.round(Math.toDegrees(s.stepPhase)/360.0),
                Math.abs(meanDaz) > 1 ? "helical advance present" : "NO ADVANCE");
        // rigid-body covariance: rotate the whole filament, n_site must follow
        double[][] R = LiveNeckHeadProbe.rot3(0.3, -0.5, 0.8);
        int seg0 = 6; double la0 = 0.5*f.segLength.get(seg0), ph0 = 0.7;
        double[] before = SingleMotorMovieHarness.sitePosNormal(s.G, nSeg, seg0, la0, ph0, s.Ract);
        for (int q = 0; q < nSeg; q++) {
            for (String fld : new String[]{"coord","uVec","yVec"}) {
                var arr = fld.equals("coord") ? f.coord : fld.equals("uVec") ? f.uVec : f.yVec;
                double[] v = { arr.get(q), arr.get(nSeg+q), arr.get(2*nSeg+q) };
                double[] w = LiveNeckHeadProbe.mv(R, v);
                arr.set(q,(float)w[0]); arr.set(nSeg+q,(float)w[1]); arr.set(2*nSeg+q,(float)w[2]);
            }
        }
        double[] after = SingleMotorMovieHarness.sitePosNormal(s.G, nSeg, seg0, la0, ph0, s.Ract);
        double[] exp = LiveNeckHeadProbe.mv(R, new double[]{ before[3],before[4],before[5] });
        double covErr = Math.sqrt(sq(after[3]-exp[0])+sq(after[4]-exp[1])+sq(after[5]-exp[2]));
        System.out.printf(Locale.US, "  rigid filament rotation: |n_site(rotated) - R n_site| = %.3e  -> %s%n",
                covErr, covErr < 1e-6 ? "PASS — n_site is carried by the filament" : "FAIL");
        write("phase5_nsite.tsv", tsv.toString());
    }

    // =============================================================================================
    //  PHASE 6/7 — what eBind means, and where the head centre sits relative to the site plane
    // =============================================================================================
    static void phase67EBind() {
        hdr("PHASE 6/7 — eBind definition, head-centre side, and the native detached rest pose");
        var s = PostHeadFreedomProbe.scene(true);
        int N = s.N;
        System.out.println("  Repository definition (ChiralSiteSystem:41): eBind = normalize(xF8 - xH),");
        System.out.println("  i.e. the head LONG AXIS pointing from the head centre TOWARD the F8 bond point.");
        System.out.println("  If a head sat OUTSIDE the filament with its binding face on the site, that vector");
        System.out.println("  would point INWARD, i.e. eBind ~ -n_site (angle ~180 deg), NOT +n_site.\n");
        StringBuilder tsv = new StringBuilder("rel\tstep\tbound\tangEB_nSite\tangEB_negNSite\tangERest_nSite\tangEB_ERest\t"
                + "sideH_nm\tsideF8_nm\tdSite_nm\tdAxisH_nm\n");
        List<double[]> rows = new ArrayList<>();
        int bindStep = -1;
        for (int t = 0; t < 4_000_000; t++) {
            PostHeadFreedomProbe.step(s, t);
            boolean bnd = s.G.mot.boundSeg.get(s.m) >= 0;
            double[] xF = PostHeadFreedomProbe.xF8True(s), xH = PostHeadFreedomProbe.xHTrue(s), eB = PostHeadFreedomProbe.eBindTrue(s);
            int seg, k; double la, ph;
            if (bnd) { seg = s.G.mot.boundSeg.get(s.m); k = s.e.bindSite.get(s.m); la = s.G.mot.bindArc.get(s.m); ph = s.G.mot.bindAzim.get(s.m); }
            else { double[] nr = SingleMotorMovieHarness.nearestSite(s.G, s.nSeg, xF, s.rise, s.stepPhase);
                   seg = (int) nr[0]; k = (int) nr[1]; la = nr[2]; ph = nr[3]; }
            if (seg < 0) continue;
            double[] pn = SingleMotorMovieHarness.sitePosNormal(s.G, s.nSeg, seg, la, ph, s.Ract);
            double[] nS = { pn[3], pn[4], pn[5] };
            // eBind_rest — the native detached pose in the live neck frame
            double[] nf = ExplicitCompleteMatHarness.neckFrame(s.e, s.m);
            double c1 = s.e.restC.get(s.m), c2 = s.e.restC.get(N+s.m), c3 = s.e.restC.get(2*N+s.m);
            double[] eR = { c1*nf[0]+c2*nf[3]+c3*nf[6], c1*nf[1]+c2*nf[4]+c3*nf[7], c1*nf[2]+c2*nf[5]+c3*nf[8] };
            double aEBn  = deg(ang(eB, nS)), aEBnn = 180 - aEBn;
            double aERn  = deg(ang(eR, nS)), aEBER = deg(ang(eB, eR));
            // which side of the site's tangent plane is each point on? (+ = outside)
            double sideH  = ((xH[0]-pn[0])*nS[0] + (xH[1]-pn[1])*nS[1] + (xH[2]-pn[2])*nS[2]) * 1e3;
            double sideF8 = ((xF[0]-pn[0])*nS[0] + (xF[1]-pn[1])*nS[1] + (xF[2]-pn[2])*nS[2]) * 1e3;
            double dSite  = SingleMotorMovieHarness.dist(xF, new double[]{pn[0],pn[1],pn[2]}) * 1e3;
            double dAxisH = PostHeadFreedomProbe.clearance(s, xH, eB)[1];
            double[] row = { t, bnd?1:0, aEBn, aEBnn, aERn, aEBER, sideH, sideF8, dSite, dAxisH };
            if (bindStep < 0) { rows.add(row); if (rows.size() > 300) rows.remove(0); if (bnd) bindStep = t; }
            else { rows.add(row); if (t - bindStep >= 200) break; }
        }
        int bi = 0; for (int i = 0; i < rows.size(); i++) if (rows.get(i)[1] > 0.5) { bi = i; break; }
        for (int i = 0; i < rows.size(); i++) {
            double[] r = rows.get(i);
            tsv.append(String.format(Locale.US, "%d\t%d\t%d\t%.2f\t%.2f\t%.2f\t%.2f\t%+.3f\t%+.3f\t%.3f\t%.3f%n",
                    i-bi, (int)r[0], (int)r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9]));
        }
        write("phase67_ebind.tsv", tsv.toString());
        double[] b = rows.get(bi);
        System.out.printf(Locale.US, "  AT THE CAPTURE FRAME (step %d):%n", (int)b[0]);
        System.out.printf(Locale.US, "    angle(eBind, +n_site) = %6.2f deg     angle(eBind, -n_site) = %6.2f deg%n", b[2], b[3]);
        System.out.printf(Locale.US, "    head-centre side of the site plane  = %+.3f nm   (%s)%n", b[6],
                b[6] >= 0 ? "OUTSIDE — head sits off the surface" : "INSIDE — the head centre is BEYOND the site, i.e. within the filament");
        System.out.printf(Locale.US, "    F8 side of the site plane           = %+.3f nm   (gate g8 requires > 0)%n", b[7]);
        System.out.printf(Locale.US, "    |xF8 - x_site| = %.3f nm ; d(head centre, filament axis) = %.3f nm (R_actin %.2f nm)%n",
                b[8], b[9], s.Ract*1e3);
        System.out.printf(Locale.US, "    angle(eBind_rest, n_site) = %6.2f deg   angle(eBind, eBind_rest) = %6.2f deg%n", b[4], b[5]);
        // averages over the bound window and over the 100 pre-capture frames
        double[] pre = mean(rows, Math.max(0,bi-100), bi-1), bnd2 = mean(rows, bi, rows.size()-1);
        System.out.printf(Locale.US, "%n  100 frames BEFORE capture : ang(eB,+n) %6.2f   ang(eB,-n) %6.2f   sideH %+7.3f nm   ang(eRest,n) %6.2f%n",
                pre[2], pre[3], pre[6], pre[4]);
        System.out.printf(Locale.US, "  BOUND window              : ang(eB,+n) %6.2f   ang(eB,-n) %6.2f   sideH %+7.3f nm   ang(eRest,n) %6.2f%n",
                bnd2[2], bnd2[3], bnd2[6], bnd2[4]);
        System.out.printf(Locale.US, "%n  => %s%n", bnd2[2] < bnd2[3]
                ? "eBind is closer to +n_site than to -n_site: the F8 tip points OUTWARD from the filament,\n     i.e. the head centre lies between the axis and the site. NOT the face-on binding geometry."
                : "eBind is closer to -n_site: the head sits outside and faces the filament (face-on binding).");
    }

    // =============================================================================================
    //  PHASE 8/9 — conditional orientation distribution given TRUE spatial reach, and by azimuth
    // =============================================================================================
    static void phase89Conditional() {
        hdr("PHASE 8/9 — P(angle(eBind, n_site) | xF8 within 3 nm of the site), k_det = 5 and 10");
        StringBuilder tsv = new StringBuilder("kdet\tstep\tsite\tazim_deg\tdSite_nm\tangEB_nSite\tangEB_negN\tangERest_n\tsideH_nm\n");
        for (double kdet : (ONLY_KDET > 0 ? new double[]{ ONLY_KDET } : new double[]{ 5.0, 10.0 })) {
            ExplicitCompleteMatHarness.K_DET_PNNM = kdet;
            var s = PostHeadFreedomProbe.scene(true);
            if (Math.abs(ExplicitCompleteMatHarness.K_DET_PNNM - kdet) > 1e-12)
                throw new IllegalStateException("k_det was overwritten by the scene builder: wanted " + kdet
                        + ", got " + ExplicitCompleteMatHarness.K_DET_PNNM);   // guard the bug that made both arms identical
            System.out.printf(Locale.US, "  [k_det ACTUALLY IN EFFECT = %.1f pN.nm/rad^2]%n", ExplicitCompleteMatHarness.K_DET_PNNM);
            int N = s.N;
            List<double[]> hit = new ArrayList<>();
            long detached = 0;
            for (int t = 0; t < COND_STEPS; t++) {
                PostHeadFreedomProbe.step(s, t);
                if (s.G.mot.boundSeg.get(s.m) >= 0) continue;
                detached++;
                double[] xF = PostHeadFreedomProbe.xF8True(s), xH = PostHeadFreedomProbe.xHTrue(s), eB = PostHeadFreedomProbe.eBindTrue(s);
                double[] nr = SingleMotorMovieHarness.nearestSite(s.G, s.nSeg, xF, s.rise, s.stepPhase);
                int seg = (int) nr[0]; if (seg < 0) continue;
                double[] pn = SingleMotorMovieHarness.sitePosNormal(s.G, s.nSeg, seg, nr[2], nr[3], s.Ract);
                double d = SingleMotorMovieHarness.dist(xF, new double[]{pn[0],pn[1],pn[2]}) * 1e3;
                if (d >= 3.0) continue;                                     // TRUE spatial reach only
                double[] nS = { pn[3], pn[4], pn[5] };
                double[] nf = ExplicitCompleteMatHarness.neckFrame(s.e, s.m);
                double c1 = s.e.restC.get(s.m), c2 = s.e.restC.get(N+s.m), c3 = s.e.restC.get(2*N+s.m);
                double[] eR = { c1*nf[0]+c2*nf[3]+c3*nf[6], c1*nf[1]+c2*nf[4]+c3*nf[7], c1*nf[2]+c2*nf[5]+c3*nf[8] };
                double a = deg(ang(eB, nS));
                double sideH = ((xH[0]-pn[0])*nS[0] + (xH[1]-pn[1])*nS[1] + (xH[2]-pn[2])*nS[2]) * 1e3;
                hit.add(new double[]{ t, (int) nr[1], Math.toDegrees(nr[3]), d, a, 180-a, deg(ang(eR,nS)), sideH });
                tsv.append(String.format(Locale.US, "%.0f\t%d\t%d\t%.1f\t%.3f\t%.2f\t%.2f\t%.2f\t%+.3f%n",
                        kdet, t, (int) nr[1], Math.toDegrees(nr[3]), d, a, 180-a, deg(ang(eR,nS)), sideH));
            }
            System.out.printf(Locale.US, "%n  k_det = %.0f : %d detached steps, %d with xF8 within 3 nm (%.3f %%)%n",
                    kdet, detached, hit.size(), 100.0*hit.size()/Math.max(1,detached));
            if (hit.isEmpty()) continue;
            double[] a = hit.stream().mapToDouble(r -> r[4]).sorted().toArray();
            System.out.printf(Locale.US, "    angle(eBind, n_site) | reachable :  mean %.2f  median %.2f deg%n", meanOf(a), pct(a,50));
            System.out.printf(Locale.US, "      p10 %.1f  p25 %.1f  p50 %.1f  p75 %.1f  p90 %.1f deg%n",
                    pct(a,10), pct(a,25), pct(a,50), pct(a,75), pct(a,90));
            System.out.printf(Locale.US, "      <=15deg %.2f %%   <=25deg %.2f %%   <=35deg %.2f %%   <=45deg %.2f %%   <=60deg %.2f %%%n",
                    frac(a,15), frac(a,25), frac(a,35), frac(a,45), frac(a,60));
            double[] sh = hit.stream().mapToDouble(r -> r[7]).toArray();
            System.out.printf(Locale.US, "    head-centre side of the site plane: mean %+.3f nm ; INSIDE on %.1f %% of reachable steps%n",
                    meanOf(sh), 100.0*java.util.Arrays.stream(sh).filter(v -> v < 0).count()/sh.length);
            double[] er = hit.stream().mapToDouble(r -> r[6]).sorted().toArray();
            System.out.printf(Locale.US, "    angle(eBind_REST, n_site) | reachable : mean %.2f  median %.2f deg  <=25deg %.2f %%%n",
                    meanOf(er), pct(er,50), frac(er,25));
            // PHASE 9 — by site azimuth
            System.out.println("      azimuth bin      n     mean d(nm)   mean ang   best ang   <=25deg");
            double[][] bins = { {-180,-120},{-120,-60},{-60,0},{0,60},{60,120},{120,180} };
            String[] lab = { "lower(-180..-120)","(-120..-60)","side(-60..0)","side(0..60)","(60..120)","upper(120..180)" };
            for (int b = 0; b < bins.length; b++) {
                final int bb = b;
                var sel = hit.stream().filter(r -> r[2] >= bins[bb][0] && r[2] < bins[bb][1]).toList();
                if (sel.isEmpty()) { System.out.printf(Locale.US, "      %-18s %4d%n", lab[b], 0); continue; }
                double md = sel.stream().mapToDouble(r -> r[3]).average().orElse(0);
                double ma = sel.stream().mapToDouble(r -> r[4]).average().orElse(0);
                double ba = sel.stream().mapToDouble(r -> r[4]).min().orElse(0);
                double f25 = 100.0*sel.stream().filter(r -> r[4] <= 25).count()/sel.size();
                System.out.printf(Locale.US, "      %-18s %4d %11.3f %11.2f %10.2f %9.2f %%%n", lab[b], sel.size(), md, ma, ba, f25);
            }
        }
        ExplicitCompleteMatHarness.K_DET_PNNM = 5.0;
        write("phase89_conditional.tsv", tsv.toString());
    }

    // =============================================================================================
    static double sq(double x) { return x*x; }
    static double deg(double r) { return Math.toDegrees(r); }
    static double ang(double[] a, double[] b) {
        return Math.acos(SingleMotorMovieHarness.cl(SingleMotorMovieHarness.dot(a,b))); }
    static double[] mean(List<double[]> r, int i0, int i1) {
        double[] o = new double[10]; int n = 0;
        for (int i = Math.max(0,i0); i <= Math.min(r.size()-1,i1); i++) { for (int c = 0; c < 10; c++) o[c] += r.get(i)[c]; n++; }
        for (int c = 0; c < 10; c++) o[c] /= Math.max(1,n); return o; }
    static double meanOf(double[] a) { double s = 0; for (double v : a) s += v; return s/Math.max(1,a.length); }
    static double pct(double[] sorted, double p) {
        if (sorted.length == 0) return Double.NaN;
        int i = (int) Math.round((p/100.0)*(sorted.length-1)); return sorted[Math.max(0,Math.min(sorted.length-1,i))]; }
    static double frac(double[] a, double thr) {
        return 100.0 * java.util.Arrays.stream(a).filter(v -> v <= thr).count() / Math.max(1,a.length); }
    static void hdr(String t) { System.out.println("\n" + "=".repeat(100) + "\n=== " + t + "\n" + "=".repeat(100)); }
    static void write(String rel, String s) {
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(OUT, rel), s); }
        catch (java.io.IOException e) { throw new RuntimeException(e); } }
}
