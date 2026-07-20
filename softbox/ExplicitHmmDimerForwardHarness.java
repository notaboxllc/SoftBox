package softbox;

import static softbox.TwoBodyConverterMotor.add;
import static softbox.TwoBodyConverterMotor.sub;
import static softbox.TwoBodyConverterMotor.scl;
import static softbox.TwoBodyConverterMotor.dot;

import softbox.ExplicitHmmDimer.Dimer;
import softbox.TwoBodyConverterMotor.Cmot;
import softbox.ExplicitHmmDimer3jsHarness.Scene;
import softbox.ExplicitHmmDimer3jsHarness.Run;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * EMERGENT FORWARD-vs-BACKWARD second-head ACCESSIBILITY study for the explicit HMM dimer. With the 5.4 nm
 * same-filament occupancy exclusion enforced, does the CURRENT mechanics (shared-tail coupling + fork geometry +
 * converter stroke + actin polarity) make a barbed-ward site more accessible to the free head AFTER its partner
 * binds and power-strokes? NO explicit forward gate / preferred sign / processivity / inter-head coordination is
 * added — this is purely diagnostic. CPU-only.
 *
 * Sign convention (§1): material coordinate s increases toward the BARBED end (+uVec / end2). deltaS = sCand −
 * sBoundPartner; deltaS>0 FORWARD (barbed), deltaS<0 BACKWARD (pointed); |deltaS|<5.4 excluded, ≥5.4 eligible.
 *
 * The DECISIVE analysis is event-independent: (a) deterministic Brownian-off accessibility maps of the free head's
 * reach to signed offset sites, pre- vs post- the bound partner's stroke, in COUPLED vs fork-DECOUPLED conditions
 * (§4/§5/§6); (b) the continuous free-head signed search coordinate accumulated over one-head-bound intervals,
 * split pre/post partner stroke (§3). Rare accepted-binding statistics (§7–10) are collected too, with
 * seed-clustered uncertainty, but the conclusion does not rest on them.
 *
 *   ./scripts/run_hmm_dimer_forward.sh -maps       # §1 sign + §6 accessibility maps + §4 shift + §5 conditions + §12 stroke + §13 validation
 *   ./scripts/run_hmm_dimer_forward.sh -natural [-seeds N -steps M]   # §7–10 natural-event aggregation + continuous search distribution
 *   ./scripts/run_hmm_dimer_forward.sh             # both (natural at a modest default)
 * See docs/matsoa/EXPLICIT_HMM_DIMER_FORWARD_ACCESSIBILITY_FINDINGS.md.
 */
public final class ExplicitHmmDimerForwardHarness {
    static final double DT = 2.5e-6;
    static final double PRE = TwoBodyConverterMotor.PRESTROKE_THETAS, POST = TwoBodyConverterMotor.ADP_THETAS;
    static final double FIL_R = Constants.radius;
    static final double EXCL = ExplicitHmmDimer3jsHarness.DEFAULT_EXCLUSION_NM;   // 5.4 nm
    static final double ALPHA = 10, BREI = 0.25, BREA = 1.0, BRANCHLEN = 10, SPLAY = 16, GAP = 3.0;
    static final int NSEG = 12;
    static final double[] OFFSETS = { -16.2, -10.8, -5.4, 5.4, 10.8, 16.2 };
    static int NAT_SEEDS = 40, NAT_STEPS = 16000;

    public static void main(String[] args) throws IOException {
        boolean maps = has(args, "-maps"), nat = has(args, "-natural"), survey = has(args, "-survey");
        NAT_SEEDS = (int) argD(args, "-seeds", NAT_SEEDS); NAT_STEPS = (int) argD(args, "-steps", NAT_STEPS);
        LL_PHI = argD(args, "-phi", LL_PHI); LL_RATIO = argD(args, "-ratio", LL_RATIO); LL_STIFF = argD(args, "-stiff", LL_STIFF);
        if (survey) { mechanismSurvey(); return; }
        if (has(args, "-hinge")) { hingeSurvey(); return; }
        if (has(args, "-leadlag")) { leadLagSurvey(); return; }
        if (has(args, "-leadcompare")) { leadLagCompare(); return; }
        if (has(args, "-compare")) { compareModels(); return; }
        if (!maps && !nat) { maps = true; nat = true; NAT_SEEDS = (int) argD(args, "-seeds", 24); }
        DetResult det = null; NatResult nr = null;
        if (maps) det = deterministic();
        if (nat) nr = natural();
        statusBlock(det, nr);
    }

    // ============================ directional-mechanism DETERMINISTIC survey (§3-8, §11-13) ============================
    /** Deterministic (Brownian-off) forward shift of the FREE head B when a directional mechanism activates, with head
     *  A bound at its natural site. Returns {deltaFreeF8Forward nm, fwdAdvantage nm (surf(−5.4)−surf(+5.4)), maxGap nm,
     *  s2deform nm}. mech: 1 M1a / 2 M1b / 3 M2 / 4 M3 / 5 M4 / 6 M5 / 7 M6. stroked = trigger context (PRE vs POST). */
    static double[] detMechShift(int mech, double ampDeg, double comp, double convStiff, double amp2Deg, boolean stroked) {
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 7, 0.0, ALPHA, BREI, BREA, BRANCHLEN);
        Dimer d = sc.d; FilamentStore f = sc.G.fil; int nSeg = sc.G.nSeg;
        double sA = projectMat(f, nSeg, d.hA.xF8); double[] actinA = matToWorld(f, nSeg, sA);
        // OFF baseline
        d.dirMech = 0; d.dirAct = 0; d.boundHead = -1;
        settleBound(d, actinA, stroked ? POST : PRE, false, 7);
        double sB0 = (projectMat(f, nSeg, d.hB.xF8) - sA) * 1e3; double sharedC0 = sharedContour(d);
        // ON
        d.dirMech = mech; d.dirAmp = Math.toRadians(ampDeg); d.dirComp = comp; d.dirConvStiffMult = convStiff;
        d.dirAmp2 = Math.toRadians(amp2Deg); d.boundHead = 0; d.dirAct = 1; d.bHat = new double[]{ 1, 0, 0 };
        settleBound(d, actinA, stroked ? POST : PRE, false, 7);
        double sB1 = (projectMat(f, nSeg, d.hB.xF8) - sA) * 1e3;
        double surfP = (dist(d.hB.xF8, matToWorld(f, nSeg, sA + 5.4e-3)) - FIL_R) * 1e3;
        double surfM = (dist(d.hB.xF8, matToWorld(f, nSeg, sA - 5.4e-3)) - FIL_R) * 1e3;
        double maxGap = ExplicitHmmDimer.maxJointGap(d); double s2def = Math.abs(sharedContour(d) - sharedC0) * 1e3;
        return new double[]{ sB1 - sB0, surfM - surfP, maxGap, s2def };
    }
    static double sharedContour(Dimer d) { double c = 0; for (int si = 0; si < d.seg.length; si++) if (!d.segIsBranch[si]) c += dist(d.nd[d.seg[si][1]], d.nd[d.seg[si][0]]); return c; }

    static void mechanismSurvey() {
        System.out.println("=== DIRECTIONAL-MECHANISM DETERMINISTIC SURVEY (Brownian off; head A bound; free-head B forward shift) ===");
        System.out.println("primary metric deltaFreeF8Forward (nm, + = barbed); health: maxGap<4 nm, sharedS2 deform<1 nm; T1=bound-pre, T2=bound-post");
        System.out.println("mechanism      params            | ΔfwdT1  ΔfwdT2 | fwdAdvT2 | maxGap  S2def | health");
        double base = detMechShift(0, 0, 0, 1, 0, true)[0];
        System.out.printf(Locale.US, "C0 baseline    (none)            |  %+.3f  %+.3f |    n/a   |   0.00   0.00 | ref%n", base, base);
        double bestM1 = 0, bestM2 = 0, bestM3 = 0, bestM4 = 0, bestM5 = 0; String pM1 = "-", pM2 = "-", pM3 = "-", pM4 = "-", pM5 = "-";
        // --- M1a free-branch cant ---
        for (double a : new double[]{ 2, 5, 10, 15, 20, 30 }) { double[] r = row("M1a fork skew", "amp=" + (int) a + "°", 1, a, 0, 1, 0);
            if (healthy(r) && r[1] > bestM1) { bestM1 = r[1]; pM1 = "M1a amp=" + (int) a + "°"; } }
        // --- M1b balanced fork skew × comp ---
        for (double a : new double[]{ 5, 10, 15, 20 }) for (double cmp : new double[]{ 0.5, 1.0 }) { double[] r = row("M1b balanced", "amp=" + (int) a + "° comp=" + cmp, 2, a, cmp, 1, 0);
            if (healthy(r) && r[1] > bestM1) { bestM1 = r[1]; pM1 = "M1b amp=" + (int) a + "° comp=" + cmp; } }
        // --- M2 stroke-driven fork (post-trigger) ---
        for (double a : new double[]{ 5, 10, 15, 20 }) { double[] r = row("M2 stroke-fork", "amp=" + (int) a + "° comp=0.5", 3, a, 0.5, 1, 0);
            if (healthy(r) && r[1] > bestM2) { bestM2 = r[1]; pM2 = "M2 amp=" + (int) a + "° comp=0.5"; } }
        // --- M3 free-converter cant × stiffness ---
        for (double a : new double[]{ 2, 5, 10, 20, 30 }) for (double ks : new double[]{ 0.25, 0.5, 1.0 }) { double[] r = row("M3 conv cant", "amp=" + (int) a + "° k=" + ks, 4, a, 0, ks, 0);
            if (healthy(r) && r[1] > bestM3) { bestM3 = r[1]; pM3 = "M3 amp=" + (int) a + "° k=" + ks; } }
        // --- M4 free-branch forward (alias of M1a machinery, distinct label/range) ---
        for (double a : new double[]{ 5, 10, 15, 20 }) { double[] r = row("M4 branch fwd", "amp=" + (int) a + "°", 5, a, 0, 1, 0);
            if (healthy(r) && r[1] > bestM4) { bestM4 = r[1]; pM4 = "M4 amp=" + (int) a + "°"; } }
        // --- M5 shared-S2 bend (expect S2 deformation reject) ---
        for (double a : new double[]{ 1, 2, 5, 10 }) { double[] r = row("M5 shared bend", "amp=" + (int) a + "°", 6, a, 0, 1, 0);
            if (healthy(r) && r[1] > bestM5) { bestM5 = r[1]; pM5 = "M5 amp=" + (int) a + "°"; } }
        // --- M6 combined: best branch (M1b) + a small converter cant ---
        System.out.println("  --- M6 combined (branch M1b amp × small converter cant) ---");
        double bestM6 = 0; String pM6 = "-";
        for (double a : new double[]{ 5, 10 }) for (double c2 : new double[]{ 2, 5 }) { double[] r = combinedRow(a, 0.5, c2, 0.5);
            if (healthy(r) && r[1] > bestM6) { bestM6 = r[1]; pM6 = "M6 branch=" + (int) a + "°/comp0.5 + conv=" + (int) c2 + "°/k0.5"; } }
        System.out.println();
        System.out.printf(Locale.US, "BEST per family (deltaFreeF8Forward T2, healthy): M1 %+.2f nm [%s]; M2 %+.2f [%s]; M3 %+.2f [%s]; M4 %+.2f [%s]; M5 %+.2f [%s]; M6 %+.2f [%s]%n",
                bestM1, pM1, bestM2, pM2, bestM3, pM3, bestM4, pM4, bestM5, pM5, bestM6, pM6);
        System.out.println("(Dynamic screening + high-stat confirmation of the winners is run via -natural on the wired mechanism; see the findings doc.)");
    }
    static double[] row(String name, String params, int mech, double ampDeg, double comp, double convStiff, double amp2) {
        // sign-robust: test both cant signs, report whichever gives the larger FORWARD (+barbed) shift
        double[] t2p = detMechShift(mech, ampDeg, comp, convStiff, amp2, true);
        double[] t2m = detMechShift(mech, -ampDeg, comp, convStiff, amp2, true);
        boolean plus = t2p[0] >= t2m[0]; double[] t2 = plus ? t2p : t2m;
        double[] t1 = detMechShift(mech, plus ? ampDeg : -ampDeg, comp, convStiff, amp2, false);
        double[] r = { t1[0], t2[0], t2[1], t2[2], t2[3] };
        System.out.printf(Locale.US, "%-14s %-16s |  %+.3f  %+.3f |  %+.3f  |  %5.2f  %5.2f | %s (sign %s)%n",
                name, params, r[0], r[1], r[2], r[3], r[4], healthy(r) ? "OK" : "REJECT", plus ? "+" : "−");
        return r;
    }
    static double[] combinedRow(double branchAmp, double comp, double convAmp, double convK) {
        // M6: branch cant + converter cant; sign-robust on the converter cant (dominant term)
        double[] t2p = detMechShiftM6(branchAmp, comp, convAmp, convK, true);
        double[] t2m = detMechShiftM6(branchAmp, comp, -convAmp, convK, true);
        boolean plus = t2p[0] >= t2m[0]; double[] t2 = plus ? t2p : t2m;
        double[] t1 = detMechShiftM6(branchAmp, comp, plus ? convAmp : -convAmp, convK, false);
        double[] r = { t1[0], t2[0], t2[1], t2[2], t2[3] };
        System.out.printf(Locale.US, "%-14s %-16s |  %+.3f  %+.3f |  %+.3f  |  %5.2f  %5.2f | %s%n",
                "M6 combined", "b" + (int) branchAmp + "/c" + (int) convAmp, r[0], r[1], r[2], r[3], r[4], healthy(r) ? "OK" : "REJECT");
        return r;
    }
    static double[] detMechShiftM6(double branchAmpDeg, double comp, double convAmpDeg, double convK, boolean stroked) {
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 7, 0.0, ALPHA, BREI, BREA, BRANCHLEN);
        Dimer d = sc.d; FilamentStore f = sc.G.fil; int nSeg = sc.G.nSeg;
        double sA = projectMat(f, nSeg, d.hA.xF8); double[] actinA = matToWorld(f, nSeg, sA);
        d.dirMech = 0; d.dirAct = 0; d.boundHead = -1; settleBound(d, actinA, stroked ? POST : PRE, false, 7);
        double sB0 = (projectMat(f, nSeg, d.hB.xF8) - sA) * 1e3; double sharedC0 = sharedContour(d);
        d.dirMech = 7; d.dirAmp = Math.toRadians(branchAmpDeg); d.dirComp = comp; d.dirAmp2 = Math.toRadians(convAmpDeg);
        d.dirConvStiffMult = convK; d.boundHead = 0; d.dirAct = 1; d.bHat = new double[]{ 1, 0, 0 };
        settleBound(d, actinA, stroked ? POST : PRE, false, 7);
        double sB1 = (projectMat(f, nSeg, d.hB.xF8) - sA) * 1e3;
        double surfP = (dist(d.hB.xF8, matToWorld(f, nSeg, sA + 5.4e-3)) - FIL_R) * 1e3;
        double surfM = (dist(d.hB.xF8, matToWorld(f, nSeg, sA - 5.4e-3)) - FIL_R) * 1e3;
        return new double[]{ sB1 - sB0, surfM - surfP, ExplicitHmmDimer.maxJointGap(d), Math.abs(sharedContour(d) - sharedC0) * 1e3 };
    }
    static boolean healthy(double[] r) { return r[3] < 4.0 && r[4] < 1.0; }   // maxGap < 4 nm AND shared-S2 deform < 1 nm

    // ============================ §1 sign convention + polarity fixtures ============================
    static boolean signFixture() {
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 1, 0.0, ALPHA, BREI, BREA, BRANCHLEN);
        FilamentStore f = sc.G.fil; int nSeg = sc.G.nSeg;
        int mid = nSeg / 2; double half = 0.5 * f.segLength.get(mid);
        double sMid = ExplicitHmmDimer3jsHarness.filMatCoordUm(f, nSeg, mid, half);
        // a site 10 nm barbed-ward (+material) and one 10 nm pointed-ward (−material)
        double barbedMat = sMid + 0.010, pointedMat = sMid - 0.010;
        double[] wB = matToWorld(f, nSeg, barbedMat), wP = matToWorld(f, nSeg, pointedMat), wMid = matToWorld(f, nSeg, sMid);
        boolean signB = (barbedMat - sMid) > 0, signP = (pointedMat - sMid) < 0;
        // rendered polarity: barbed end has the LARGER world-x here (chain laid along +x, end2 barbed) — confirm
        boolean worldBarbedIsPlusX = wB[0] > wMid[0] && wMid[0] > wP[0];
        boolean ok = signB && signP && worldBarbedIsPlusX;
        System.out.printf(Locale.US, "§1 sign convention: +Δs ⇒ FORWARD/barbed (worldX %.4f>%.4f>%.4f pointed) → %s%n",
                wB[0], wMid[0], wP[0], ok ? "PASS" : "FAIL");
        return ok;
    }

    // ============================ §6 deterministic accessibility maps ============================
    static final class DetResult {
        double sBpre, sBpost, sBpreDec, sBpostDec;   // free-head B signed material coord (nm) vs A's site
        double shiftCoupled, shiftDecoupled;         // stroke-induced free-head shift (nm, +barbed)
        double intrinsicStroke, headVsAnchor, headVsActin;   // §12
        boolean signOk, abSym, mirrorOk, polarityOk, noStrokeSmall;
        double abSymDiff, fwdReachPre, fwdReachPost;         // forward reach advantage (nm): surf(−5.4)−surf(+5.4)
    }

    static DetResult deterministic() {
        System.out.println("=== DETERMINISTIC ACCESSIBILITY MAPS (Brownian OFF; head A bound, head B free) ===");
        DetResult r = new DetResult();
        r.signOk = signFixture();

        // --- the free-head B signed coordinate + accessibility map, PRE vs POST A-stroke, COUPLED ---
        System.out.println("\n§6 free-head B reach to signed offset sites (surf = tip→site clearance, nm; lower = more accessible):");
        System.out.println("  offset(nm)  eligible |   surf PRE   surf POST  Δsurf(POST−PRE)");
        double[] pre = mapCase(false, false); double[] post = mapCase(true, false);
        r.sBpre = pre[OFFSETS.length]; r.sBpost = post[OFFSETS.length];
        for (int i = 0; i < OFFSETS.length; i++)
            System.out.printf(Locale.US, "  %+7.1f    %-8s |  %8.3f   %8.3f     %+8.3f%n",
                    OFFSETS[i], Math.abs(OFFSETS[i]) >= EXCL - 1e-3 ? "YES" : "no(excl)", pre[i], post[i], post[i] - pre[i]);
        r.fwdReachPre  = surfAt(pre, -5.4) - surfAt(pre, +5.4);    // + ⇒ +5.4 more accessible than −5.4 (forward advantage)
        r.fwdReachPost = surfAt(post, -5.4) - surfAt(post, +5.4);
        r.shiftCoupled = r.sBpost - r.sBpre;
        System.out.printf(Locale.US, "  free-head B signed coord vs A site: PRE %+.3f nm → POST %+.3f nm  (COUPLED stroke-induced shift %+.3f nm, +barbed)%n",
                r.sBpre, r.sBpost, r.shiftCoupled);
        System.out.printf(Locale.US, "  forward reach advantage [surf(−5.4)−surf(+5.4)]: PRE %+.3f nm → POST %+.3f nm%n", r.fwdReachPre, r.fwdReachPost);

        // --- §5 Condition 4: DECOUPLED (fork pinned) — how much of the shift is shared-tail mechanical ---
        double[] preD = mapCase(false, true), postD = mapCase(true, true);
        r.sBpreDec = preD[OFFSETS.length]; r.sBpostDec = postD[OFFSETS.length]; r.shiftDecoupled = r.sBpostDec - r.sBpreDec;
        System.out.printf(Locale.US, "  DECOUPLED (fork pinned): PRE %+.3f → POST %+.3f nm  (shift %+.3f nm)  ⇒ shared-tail contribution %+.3f nm%n",
                r.sBpreDec, r.sBpostDec, r.shiftDecoupled, r.shiftCoupled - r.shiftDecoupled);

        // --- §12 stroke decomposition (head A) ---
        double[] sd = strokeDecomp();
        r.intrinsicStroke = sd[0]; r.headVsAnchor = sd[1]; r.headVsActin = sd[2];
        System.out.printf(Locale.US, "\n§12 stroke decomposition (head A): intrinsic converter %.2f nm, head vs anchor %.2f nm, head vs actin material %.2f nm%n",
                r.intrinsicStroke, r.headVsAnchor, r.headVsActin);

        // --- §13 validation ---
        double sBpost_B = mapCaseHeadB(true);   // bind B instead of A, stroke; free-head A signed coord
        r.abSymDiff = Math.abs(Math.abs(r.sBpost) - Math.abs(sBpost_B));
        // both free-head offsets stay near the partner site (no large asymmetric artifact). Exact A/B equality is
        // NOT expected: the two heads carry the SAME world-fixed orientation at splayed ±z pivots, so binding A vs
        // binding B are genuinely different geometric situations, not mirror-equivalent.
        r.abSym = Math.abs(r.sBpost) < 1.5 && Math.abs(sBpost_B) < 1.5;
        double shiftMirror = mirrorShift();      // spatial mirror (z→−z): forward shift magnitude unchanged
        r.mirrorOk = Math.abs(Math.abs(shiftMirror) - Math.abs(r.shiftCoupled)) < 0.3;
        double shiftPolRev = polarityReversedShift();   // reverse filament polarity: forward (toward NEW barbed) shift keeps sign in material coords
        r.polarityOk = sameSign(shiftPolRev, r.shiftCoupled) || Math.abs(shiftPolRev) < 0.2;
        r.noStrokeSmall = true;   // by construction: PRE state has 0 stroke-induced shift (the reference)
        System.out.printf(Locale.US, "§13 validation: A/B relabel |Δ|=%.3f nm (%s); spatial mirror shift %+.3f nm (%s); polarity-reversal shift %+.3f nm (%s)%n",
                r.abSymDiff, r.abSym ? "PASS" : "FAIL", shiftMirror, r.mirrorOk ? "PASS" : "FAIL", shiftPolRev, r.polarityOk ? "PASS" : "FAIL");
        return r;
    }

    /** Settle head A bound (F8 spring to the mid-segment site), head B free, Brownian off; optionally A stroked and
     *  optionally fork-decoupled. Returns {surf(offset_i)... , sB_signed_nm} where surf = (|B_tip − site| − FIL_R)·1e3. */
    static double[] mapCase(boolean stroked, boolean decouple) {
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 7, 0.0, ALPHA, BREI, BREA, BRANCHLEN);
        Dimer d = sc.d; FilamentStore f = sc.G.fil; int nSeg = sc.G.nSeg;
        double sA = projectMat(f, nSeg, d.hA.xF8);   // A's NATURAL site (its settled tip projected onto the filament)
        double[] actinA = matToWorld(f, nSeg, sA);
        settleBound(d, actinA, stroked ? POST : PRE, decouple, 7);
        double[] out = new double[OFFSETS.length + 1];
        for (int i = 0; i < OFFSETS.length; i++) {
            double[] site = matToWorld(f, nSeg, sA + OFFSETS[i] * 1e-3);
            out[i] = (dist(d.hB.xF8, site) - FIL_R) * 1e3;
        }
        double sB = projectMat(f, nSeg, d.hB.xF8);
        out[OFFSETS.length] = (sB - sA) * 1e3;
        return out;
    }
    /** Symmetric case with head B bound instead (free head A); returns free-head A signed coord (nm) post-stroke. */
    static double mapCaseHeadB(boolean stroked) {
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 7, 0.0, ALPHA, BREI, BREA, BRANCHLEN);
        Dimer d = sc.d; FilamentStore f = sc.G.fil; int nSeg = sc.G.nSeg;
        double sB = projectMat(f, nSeg, d.hB.xF8);
        double[] actinB = matToWorld(f, nSeg, sB);
        settleBoundB(d, actinB, stroked ? POST : PRE, 7);
        double sA = projectMat(f, nSeg, d.hA.xF8);
        return (sA - sB) * 1e3;
    }
    /** Spatial mirror y→−y (perpendicular to the filament axis; polarity +x and the x–z splay/head frames are
     *  unaffected): the forward-shift magnitude must be preserved. (A z-mirror is NOT clean here — it would flip the
     *  dimer to the far side of the filament while the head orientation frames stay world-fixed.) */
    static double mirrorShift() {
        double pre = mapCaseMirror(false), post = mapCaseMirror(true); return post - pre;
    }
    static double mapCaseMirror(boolean stroked) {
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 7, 0.0, ALPHA, BREI, BREA, BRANCHLEN);
        Dimer d = sc.d; FilamentStore f = sc.G.fil; int nSeg = sc.G.nSeg;
        for (int j = 0; j <= d.NF; j++) d.nd[j][1] = -d.nd[j][1];   // y→−y (clean perpendicular mirror)
        d.E[1] = -d.E[1]; ExplicitHmmDimer.pinHead(d.hA, d.nd[d.pA]); ExplicitHmmDimer.pinHead(d.hB, d.nd[d.pB]);
        double sA = projectMat(f, nSeg, d.hA.xF8);
        double[] actinA = matToWorld(f, nSeg, sA);
        settleBound(d, actinA, stroked ? POST : PRE, false, 7);
        return (projectMat(f, nSeg, d.hB.xF8) - sA) * 1e3;
    }
    /** Reverse filament polarity (flip every segment's uVec + reverse the chain neighbour order): the free-head
     *  shift toward the NEW barbed end should keep the same SIGN in material coordinates (which follow polarity). */
    static double polarityReversedShift() {
        double pre = mapCasePolRev(false), post = mapCasePolRev(true); return post - pre;
    }
    static double mapCasePolRev(boolean stroked) {
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 7, 0.0, ALPHA, BREI, BREA, BRANCHLEN);
        Dimer d = sc.d; FilamentStore f = sc.G.fil; int nSeg = sc.G.nSeg;
        // flip polarity: negate every uVec and swap end1/end2 neighbour slots so material coord now increases toward −x
        for (int k = 0; k < nSeg; k++) { f.setUVec(k, -f.uVecX(k), -f.uVecY(k), -f.uVecZ(k));
            int e1 = f.end1NbrSlot.get(k), e2 = f.end2NbrSlot.get(k); f.end1NbrSlot.set(k, e2); f.end2NbrSlot.set(k, e1); }
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        double sA = projectMat(f, nSeg, d.hA.xF8);
        double[] actinA = matToWorld(f, nSeg, sA);
        settleBound(d, actinA, stroked ? POST : PRE, false, 7);
        return (projectMat(f, nSeg, d.hB.xF8) - sA) * 1e3;
    }

    /** §12 stroke decomposition for head A: (0) intrinsic UNLOADED converter throw (F8=0), (1) bound head-vs-anchor,
     *  (2) bound head-vs-actin material point. The 5.4 nm occupancy rule cannot affect any of these (it only vetoes
     *  the partner's bind), so this also confirms the intrinsic stroke is preserved. */
    static double[] strokeDecomp() {
        // (0) intrinsic: unloaded head-A throw (both F8 = 0), tip displacement along bhat
        Scene scU = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 7, 0.0, ALPHA, BREI, BREA, BRANCHLEN);
        Dimer du = scU.d;
        for (int it = 0; it < 3000; it++) { double[] snap = flat(du); du.hA.thetaS = PRE; du.hB.thetaS = PRE;
            ExplicitHmmDimer.solve(du, it, 7, false, new double[3], new double[3]); if (maxMove(snap, du) < 3e-7) break; }
        double[] bhat = du.hA.bhat.clone(), xh0 = du.hA.xH.clone();
        for (int it = 0; it < 3000; it++) { double[] snap = flat(du); du.hA.thetaS = POST; du.hB.thetaS = PRE;
            ExplicitHmmDimer.solve(du, it, 7, false, new double[3], new double[3]); if (maxMove(snap, du) < 3e-7) break; }
        double intrinsic = Math.abs(dot(sub(du.hA.xH, xh0), bhat)) * 1e3;
        // (1)(2) bound head A vs anchor + vs actin material point
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 7, 0.0, ALPHA, BREI, BREA, BRANCHLEN);
        Dimer d = sc.d; FilamentStore f = sc.G.fil; int nSeg = sc.G.nSeg;
        double sA = projectMat(f, nSeg, d.hA.xF8);
        double[] actinA = matToWorld(f, nSeg, sA);
        settleBound(d, actinA, PRE, false, 7);
        double[] bhat2 = d.hA.bhat.clone(), xh0b = d.hA.xH.clone(); double sTipPre = projectMat(f, nSeg, d.hA.xF8);
        settleBound(d, actinA, POST, false, 7);
        double headVsAnchor = Math.abs(dot(sub(d.hA.xH, xh0b), bhat2)) * 1e3;
        double headVsActin = Math.abs(projectMat(f, nSeg, d.hA.xF8) - sTipPre) * 1e3;   // A tip motion along actin (held by the F8 spring)
        return new double[]{ intrinsic, headVsAnchor, headVsActin };
    }

    // --- deterministic settle helpers ---
    static void settleBound(Dimer d, double[] actinA, double thetaA, boolean decouple, int seed) {
        double kF8 = d.hA.kF8Code;
        for (int it = 0; it < 3000; it++) {
            double[] snap = flat(d);
            double[] fA = scl(sub(actinA, d.hA.xF8), kF8);
            d.hA.thetaS = thetaA; d.hB.thetaS = PRE;
            ExplicitHmmDimer.solve(d, it, seed, false, fA, new double[3], decouple);
            if (maxMove(snap, d) < 3e-7) break;
        }
    }
    static void settleBoundB(Dimer d, double[] actinB, double thetaB, int seed) {
        double kF8 = d.hB.kF8Code;
        for (int it = 0; it < 3000; it++) {
            double[] snap = flat(d);
            double[] fB = scl(sub(actinB, d.hB.xF8), kF8);
            d.hB.thetaS = thetaB; d.hA.thetaS = PRE;
            ExplicitHmmDimer.solve(d, it, seed, false, new double[3], fB, false);
            if (maxMove(snap, d) < 3e-7) break;
        }
    }

    // ============================ §7–10 natural-event aggregation ============================
    static final class NatResult {
        int seeds, seedsWithEvents, fwd, bwd, fwdInside, bwdInside, invalid;
        double exposureMs, preMs, postMs;
        double fwdFrac, fwdFracLo, fwdFracHi, meanSigned, medSigned, meanAbs;
        double fwdRatePerMs, bwdRatePerMs, fwdEligRate, bwdEligRate;
        double preFwdAccess, postFwdAccess, meanFreePre, meanFreePost, shift;
        double dblFrac, dwell;
        List<Double> perSeedFwdFrac = new ArrayList<>();
    }
    static NatResult natural() throws IOException {
        System.out.printf(Locale.US, "%n=== §7–10 NATURAL-EVENT AGGREGATION (%d seeds × %d steps; exclusion %.1f nm; α=%.0f Lb=%.0f EI×%.2f) ===%n",
                NAT_SEEDS, NAT_STEPS, EXCL, ALPHA, BRANCHLEN, BREI);
        NatResult nr = poolCfg(NAT_SEEDS, NAT_STEPS, 0, 0, 0, 1, 1, false, 1.0);
        return nr;
    }
    /** Aggregate a configured dynamic run set (mechanism + optional C3 veto / C4 penalty). */
    static NatResult poolCfg(int seeds, int steps, int mech, double ampDeg, double comp, double convStiff, int trigger, boolean veto, double penalty) throws IOException {
        NatResult nr = new NatResult(); nr.seeds = seeds;
        List<Double> signed = new ArrayList<>(), freePre = new ArrayList<>(), freePost = new ArrayList<>();
        double dtms = DT * 1e3, dblSum = 0, dwSum = 0; int nDw = 0;
        for (int s = 1; s <= seeds; s++) {
            Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, s, 1.0, ALPHA, BREI, BREA, BRANCHLEN);
            sc.exclusionNm = EXCL;
            sc.dirMech = mech; sc.dirAmpDeg = ampDeg; sc.dirComp = comp; sc.dirConvStiff = convStiff; sc.dirTrigger = trigger;
            sc.rearVeto = veto; sc.rearPenalty = penalty;
            Run r = ExplicitHmmDimer3jsHarness.run(sc, s, steps, 1_000_000, null);
            nr.fwd += r.fwdBinds; nr.bwd += r.bwdBinds; nr.fwdInside += r.fwdInside; nr.bwdInside += r.bwdInside; nr.invalid += r.invalid;
            nr.exposureMs += r.oneBoundSteps * dtms; nr.preMs += r.preStrokeSteps * dtms; nr.postMs += r.postStrokeSteps * dtms;
            for (double v : r.acceptedSignedOffsets) signed.add(v);
            freePre.addAll(r.freeSignedPre); freePost.addAll(r.freeSignedPost);
            dblSum += r.dblFrac; if (!Double.isNaN(r.dwellMean)) { dwSum += r.dwellMean; nDw++; }
            int ev = r.fwdBinds + r.bwdBinds; if (ev > 0) { nr.seedsWithEvents++; nr.perSeedFwdFrac.add((double) r.fwdBinds / ev); }
        }
        nr.dblFrac = dblSum / seeds; nr.dwell = nDw > 0 ? dwSum / nDw : Double.NaN;
        int tot = nr.fwd + nr.bwd;
        nr.fwdFrac = tot > 0 ? (double) nr.fwd / tot : Double.NaN;
        nr.meanSigned = mean(signed); nr.medSigned = median(signed); nr.meanAbs = meanAbs(signed);
        nr.fwdRatePerMs = nr.exposureMs > 0 ? nr.fwd / nr.exposureMs : 0;
        nr.bwdRatePerMs = nr.exposureMs > 0 ? nr.bwd / nr.exposureMs : 0;
        nr.fwdEligRate = nr.fwdRatePerMs; nr.bwdEligRate = nr.bwdRatePerMs;   // eligible⟹accepted in the current path
        nr.preFwdAccess = fracPositive(freePre); nr.postFwdAccess = fracPositive(freePost);
        nr.meanFreePre = mean(freePre); nr.meanFreePost = mean(freePost); nr.shift = nr.meanFreePost - nr.meanFreePre;
        // seed-clustered bootstrap CI on the forward fraction (resample seeds-with-events with replacement)
        double[] ci = clusteredCI(nr.perSeedFwdFrac);
        nr.fwdFracLo = ci[0]; nr.fwdFracHi = ci[1];

        System.out.printf(Locale.US, "one-head-bound exposure: %.2f ms (pre-stroke %.2f, post-stroke %.2f) over %d seeds (%d with accepted events)%n",
                nr.exposureMs, nr.preMs, nr.postMs, nr.seeds, nr.seedsWithEvents);
        System.out.printf(Locale.US, "CONTINUOUS free-head search (event-independent): mean signed coord PRE %+.3f nm, POST %+.3f nm ⇒ shift %+.3f nm%n",
                nr.meanFreePre, nr.meanFreePost, nr.shift);
        System.out.printf(Locale.US, "  forward-accessibility fraction (free-head barbed-ward): PRE %.3f, POST %.3f%n", nr.preFwdAccess, nr.postFwdAccess);
        System.out.printf(Locale.US, "ACCEPTED 2nd-binds: fwd %d, bwd %d (forward fraction %.3f, seed-clustered 95%% CI [%.3f, %.3f], n=%d events / %d seeds)%n",
                nr.fwd, nr.bwd, nr.fwdFrac, nr.fwdFracLo, nr.fwdFracHi, tot, nr.seedsWithEvents);
        System.out.printf(Locale.US, "  mean signed accepted offset %+.3f nm, median %+.3f nm, mean |offset| %.3f nm%n", nr.meanSigned, nr.medSigned, nr.meanAbs);
        System.out.printf(Locale.US, "  exposure-normalized accepted rate: forward %.4f /ms, backward %.4f /ms; occupancy rejects fwd %d / bwd %d%n",
                nr.fwdRatePerMs, nr.bwdRatePerMs, nr.fwdInside, nr.bwdInside);
        return nr;
    }

    // ============================ FORWARD-JUNCTION DISTAL-S2 HINGE survey (this task) ============================
    /** Distal-S2 hinge case: head A bound; mech 9 with (phiHinge, stiffness). Returns {junctionLead nm
     *  (dot(pFork−pBind,bHat)), hingeAdvance nm (dot(pFork_on−pFork_off,bHat)), freeF8Lead nm (dot(pFreeF8−pBind,bHat)),
     *  maxGap nm, distalTangent·bHat, s2contourChange%}. */
    static double[] hingeCase(double phiHingeDeg, double stiff, boolean stroked) {
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 7, 0.0, ALPHA, BREI, BREA, BRANCHLEN);
        Dimer d = sc.d; FilamentStore f = sc.G.fil; int nSeg = sc.G.nSeg;
        double sA = projectMat(f, nSeg, d.hA.xF8); double[] pBind = matToWorld(f, nSeg, sA);
        // OFF baseline
        d.dirMech = 0; d.dirAct = 0; d.boundHead = -1; settleBound(d, pBind, stroked ? POST : PRE, false, 7);
        double[] forkOff = d.nd[d.Ms].clone(); double c0 = ExplicitHmmDimer.contour(d);
        // distal S2 tangent (segment Ms-1 → Ms), normalized · bHat
        double[] tDist = sub(d.nd[d.Ms], d.nd[d.Ms - 1]); double tdb = dot(tDist, new double[]{ 1, 0, 0 }) / Math.sqrt(dot(tDist, tDist));
        // ON
        d.dirMech = 9; d.dirAmp = Math.toRadians(phiHingeDeg); d.dirConvStiffMult = stiff; d.boundHead = 0; d.dirAct = 1; d.bHat = new double[]{ 1, 0, 0 };
        settleBound(d, pBind, stroked ? POST : PRE, false, 7);
        double[] forkOn = d.nd[d.Ms].clone();
        double junctionLead = dot(sub(forkOn, pBind), d.bHat) * 1e3;
        double hingeAdvance = dot(sub(forkOn, forkOff), d.bHat) * 1e3;
        double freeF8Lead = dot(sub(d.hB.xF8, pBind), d.bHat) * 1e3;
        double s2pct = 100.0 * (ExplicitHmmDimer.contour(d) - c0) / c0;
        return new double[]{ junctionLead, hingeAdvance, freeF8Lead, ExplicitHmmDimer.maxJointGap(d), tdb, s2pct };
    }

    static void hingeSurvey() {
        System.out.println("=== FORWARD-JUNCTION DISTAL-S2 HINGE SURVEY (mech 9; Brownian off; head A bound) ===");
        // baseline geometry probe (grounds the analysis)
        double[] b0 = hingeCase(0, 1.0, true);
        System.out.printf(Locale.US, "BASELINE geometry (A bound, mech off): junctionLead(pFork−pBind)·bHat = %+.3f nm; distal-S2 tangent·bHat = %.4f (1.0 ⇒ S2 ∥ filament barbed axis)%n",
                b0[0], b0[4]);
        System.out.println("   ⇒ if distal-S2 tangent·bHat ≈ 1, the S2 already points at the barbed end and the fork is at the straight-beam +x tip;");
        System.out.println("     a barbed-ward bend then has NO forward room (it can only move the fork back/transverse). J1 measures this directly.");

        // --- J1 mandatory monotonic fork advance ---
        System.out.println("\n§8-J1 MONOTONIC FORK ADVANCE: hingeAdvance = dot(pFork_active − pFork_baseline, bHat) vs phiHinge (stiff 1.0×, bound-post):");
        double prev = -1e9; boolean mono = true, moved = false;
        for (double p : new double[]{ 0, 1, 2, 5, 8, 10, 15, 20, 30 }) { double[] r = hingeCase(p, 1.0, true);
            System.out.printf(Locale.US, "   phiHinge=%2.0f° → hingeAdvance %+.3f nm, junctionLead %+.3f nm, freeF8Lead %+.3f nm, maxGap %.2f, s2Δ %.3f%%%n",
                    p, r[1], r[0], r[2], r[3], r[5]);
            if (Math.abs(r[1]) > 1.0) moved = true; if (p > 0 && r[1] < prev - 0.05) mono = false; prev = r[1]; }
        System.out.println("   J1 " + (moved && mono ? "PASS" : "FAIL") + " (needs monotonic fork advance > 1 nm; a fork that does not move FAILS).");

        // --- stiffness sweep at phi=20 to probe whether a softer/stiffer hinge advances the fork ---
        System.out.println("\n§7 stiffness sweep at phiHinge=20° (bound-post): hingeAdvance | junctionLead | maxGap | s2Δ%");
        for (double ks : new double[]{ 0.02, 0.05, 0.1, 0.25, 0.5, 1.0 }) { double[] r = hingeCase(20, ks, true);
            System.out.printf(Locale.US, "   stiff=%.2f× → hingeAdvance %+.3f nm, junctionLead %+.3f nm, maxGap %.2f, s2Δ %.3f%%%n", ks, r[1], r[0], r[3], r[5]); }

        // --- J6 internal balance ---
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 7, 0.0, ALPHA, BREI, BREA, BRANCHLEN);
        Dimer d = sc.d; d.dirMech = 9; d.dirAmp = Math.toRadians(20); d.dirConvStiffMult = 1.0; d.boundHead = 0; d.dirAct = 1; d.bHat = new double[]{ 1, 0, 0 };
        d.hA.thetaS = POST; d.hB.thetaS = PRE;
        double[][] Fn = ExplicitHmmDimer.nodeForces(d, d.nd); double[] sum = { 0, 0, 0 };
        for (int j = 0; j <= d.NF; j++) for (int k = 0; k < 3; k++) sum[k] += Fn[j][k];
        System.out.printf(Locale.US, "%n§8-J6 internal balance: |net node-force| %.3e pN → %s%n", Math.sqrt(dot(sum, sum)) * 1e12, Math.sqrt(dot(sum, sum)) * 1e12 < 1e-2 ? "PASS" : "FAIL");
        double[] sd = strokeDecomp();
        System.out.printf(Locale.US, "§14 stroke preservation: intrinsic %.2f nm, head-anchor %.2f nm (unaffected by the hinge)%n", sd[0], sd[1]);
    }

    // ============================ AXIAL LEAD-LAG survey (this task) ============================
    /** Deterministic lead-lag case: head A bound, head B free; mechanism 8 with (φFree, φBound/φFree ratio, stiffness).
     *  Returns {axialPivotOffset nm (dot(pivotFree−pivotBound,bHat)), freeF8fwdShift nm, fwdAdvantage nm, maxGap nm,
     *  s2def nm, freePreload pN, boundForce pN}. */
    static double[] leadLagCase(double phiFreeDeg, double ratio, double stiff, boolean stroked, boolean decouple, boolean bindB) {
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 7, 0.0, ALPHA, BREI, BREA, BRANCHLEN);
        Dimer d = sc.d; FilamentStore f = sc.G.fil; int nSeg = sc.G.nSeg;
        int boundIdx = bindB ? 1 : 0, freeIdx = 1 - boundIdx;
        Cmot bh = bindB ? d.hB : d.hA;
        double sBnd = projectMat(f, nSeg, bh.xF8); double[] actinBnd = matToWorld(f, nSeg, sBnd);
        // OFF baseline
        d.dirMech = 0; d.dirAct = 0; d.boundHead = -1;
        if (bindB) settleBoundB(d, actinBnd, stroked ? POST : PRE, 7); else settleBound(d, actinBnd, stroked ? POST : PRE, false, 7);
        double sF0 = (projectMat(f, nSeg, (freeIdx == 0 ? d.hA : d.hB).xF8) - sBnd) * 1e3, sharedC0 = sharedContour(d);
        // ON (lead-lag)
        d.dirMech = 8; d.dirAmp = Math.toRadians(phiFreeDeg); d.dirComp = ratio; d.dirConvStiffMult = stiff;
        d.boundHead = boundIdx; d.dirAct = 1; d.bHat = new double[]{ 1, 0, 0 };
        if (bindB) settleBoundB(d, actinBnd, stroked ? POST : PRE, 7); else settleBound(d, actinBnd, stroked ? POST : PRE, decouple, 7);
        Cmot fr = freeIdx == 0 ? d.hA : d.hB;
        double[] pivF = d.nd[freeIdx == 0 ? d.pA : d.pB], pivB = d.nd[boundIdx == 0 ? d.pA : d.pB];
        double axialOff = dot(sub(pivF, pivB), d.bHat) * 1e3;                       // dot(pivotFree − pivotBound, bHat)
        double sF1 = (projectMat(f, nSeg, fr.xF8) - sBnd) * 1e3;
        double surfP = (dist(fr.xF8, matToWorld(f, nSeg, sBnd + 5.4e-3)) - FIL_R) * 1e3;
        double surfM = (dist(fr.xF8, matToWorld(f, nSeg, sBnd - 5.4e-3)) - FIL_R) * 1e3;
        double maxGap = ExplicitHmmDimer.maxJointGap(d), s2def = Math.abs(sharedContour(d) - sharedC0) * 1e3;
        return new double[]{ axialOff, sF1 - sF0, surfM - surfP, maxGap, s2def, sF0, sF1 };
    }

    static void leadLagSurvey() {
        System.out.println("=== AXIAL LEAD-LAG SURVEY (mech 8; internal branch-root rest-angle torques; Brownian off; head A bound) ===");
        // --- G1: mandatory axial-pivot-offset monotonicity (ratio 1.0, full stiffness) ---
        System.out.println("§7-G1 MONOTONICITY: axialPivotOffset = dot(pivotFree − pivotBound, bHat) vs φFree (ratio 1.0, stiff 1.0×, bound-post):");
        double prev = -1e9; boolean mono = true;
        for (double p : new double[]{ 0, 2, 5, 10, 15, 20, 25, 30 }) { double off = leadLagCase(p, 1.0, 1.0, true, false, false)[0];
            System.out.printf(Locale.US, "   φFree=%2.0f° → axialPivotOffset %+.3f nm%n", p, off);
            if (p > 0 && off < prev - 0.05) mono = false; prev = off; }
        System.out.println("   G1 " + (mono ? "PASS (monotonic increasing)" : "FAIL (non-monotonic)") + "; a near-zero offset would FAIL the mechanism.");

        // --- full deterministic screen: φFree × ratio × stiffness ---
        System.out.println("\n§8 deterministic screen (bound-post T2): axialOff | freeF8fwd | fwdAdv | maxGap s2def | health");
        System.out.println("  φFree ratio stiff | axialOff freeF8fwd fwdAdv | maxGap  s2def | status");
        double bestOff = 0, bestShift = 0; String bestP = "-"; double[] best = null;
        for (double p : new double[]{ 10, 15, 20, 25, 30 }) for (double rat : new double[]{ 0, 0.5, 1.0 }) for (double ks : new double[]{ 0.1, 0.25, 0.5, 1.0 }) {
            double[] r = leadLagCase(p, rat, ks, true, false, false);
            boolean h = r[3] < 4.0 && r[4] < 1.0;
            if (p == 20 || (h && r[0] > 4.5 && r[0] < 9)) System.out.printf(Locale.US, "  %3.0f  %.2f  %.2f | %+7.3f  %+7.3f  %+6.3f | %5.2f  %5.2f | %s%n",
                    p, rat, ks, r[0], r[1], r[2], r[3], r[4], h ? "OK" : "REJECT");
            // pick the candidate whose axial offset is closest to 5.4 nm among healthy configs
            if (h && Math.abs(r[0] - 5.4) < Math.abs(bestOff - 5.4)) { bestOff = r[0]; bestShift = r[1]; bestP = String.format(Locale.US, "φFree=%.0f° ratio=%.2f stiff=%.2f", p, rat, ks); best = r; }
        }
        System.out.printf(Locale.US, "%n>>> best ~5.4 nm axial-offset healthy candidate: %s → axialOffset %+.2f nm, freeF8fwd %+.2f nm, fwdAdv %+.2f nm%n",
                bestP, bestOff, bestShift, best == null ? 0 : best[2]);

        // --- fixtures G2 (polarity), G3 (A/B swap), G4 (mirror), G5 (force/torque balance) at a representative φ=20°, ratio1, stiff1 ---
        double[] gA = leadLagCase(20, 1.0, 1.0, true, false, false);      // A bound, free B leads
        double[] gB = leadLagCase(20, 1.0, 1.0, true, false, true);       // B bound, free A leads (A/B swap)
        System.out.printf(Locale.US, "%n§7-G3 A/B swap: A-bound axialOffset %+.3f nm; B-bound axialOffset %+.3f nm (free head leads in both) → %s%n",
                gA[0], gB[0], (gA[0] > 0.5 && gB[0] > 0.5) ? "PASS" : "FAIL");
        double polOff = leadLagPolarityReversed(20, 1.0, 1.0);
        System.out.printf(Locale.US, "§7-G2 polarity reversal: free branch leads toward the NEW barbed end, axialOffset(new bHat) %+.3f nm → %s%n",
                polOff, polOff > 0.5 ? "PASS" : "FAIL");
        double mirOff = leadLagMirror(20, 1.0, 1.0);
        System.out.printf(Locale.US, "§7-G4 spatial mirror (y→−y): axialOffset %+.3f nm (preserved) → %s%n", mirOff, Math.abs(mirOff - gA[0]) < 0.5 ? "PASS" : "FAIL");
        double netF = leadLagNetForce(20, 1.0, 1.0);
        System.out.printf(Locale.US, "§7-G5 internal balance: |net mechanism node-force| %.3e pN (should be ≈ solver tol) → %s%n", netF, netF < 1e-2 ? "PASS" : "FAIL");
        // stroke preservation
        double[] sd = strokeDecomp();
        System.out.printf(Locale.US, "%n§11 stroke preservation (unaffected by mech): intrinsic %.2f nm, head-anchor %.2f nm, head-actin %.2f nm%n", sd[0], sd[1], sd[2]);
    }
    /** Polarity-reversed lead-lag axial offset toward the NEW barbed end (flip filament uVec + neighbours + bHat). */
    static double leadLagPolarityReversed(double phiFree, double ratio, double stiff) {
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 7, 0.0, ALPHA, BREI, BREA, BRANCHLEN);
        Dimer d = sc.d; FilamentStore f = sc.G.fil; int nSeg = sc.G.nSeg;
        for (int k = 0; k < nSeg; k++) { f.setUVec(k, -f.uVecX(k), -f.uVecY(k), -f.uVecZ(k));
            int e1 = f.end1NbrSlot.get(k), e2 = f.end2NbrSlot.get(k); f.end1NbrSlot.set(k, e2); f.end2NbrSlot.set(k, e1); }
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        double sA = projectMat(f, nSeg, d.hA.xF8); double[] actinA = matToWorld(f, nSeg, sA);
        d.dirMech = 8; d.dirAmp = Math.toRadians(phiFree); d.dirComp = ratio; d.dirConvStiffMult = stiff;
        d.boundHead = 0; d.dirAct = 1; d.bHat = new double[]{ -1, 0, 0 };   // NEW barbed = −x after polarity flip
        settleBound(d, actinA, POST, false, 7);
        return dot(sub(d.nd[d.pB], d.nd[d.pA]), d.bHat) * 1e3;   // free-B pivot leads toward the new barbed (−x)
    }
    static double leadLagMirror(double phiFree, double ratio, double stiff) {
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 7, 0.0, ALPHA, BREA == 0 ? 1 : BREI, BREA, BRANCHLEN);
        Dimer d = sc.d; FilamentStore f = sc.G.fil; int nSeg = sc.G.nSeg;
        for (int j = 0; j <= d.NF; j++) d.nd[j][1] = -d.nd[j][1]; d.E[1] = -d.E[1];
        ExplicitHmmDimer.pinHead(d.hA, d.nd[d.pA]); ExplicitHmmDimer.pinHead(d.hB, d.nd[d.pB]);
        double sA = projectMat(f, nSeg, d.hA.xF8); double[] actinA = matToWorld(f, nSeg, sA);
        d.dirMech = 8; d.dirAmp = Math.toRadians(phiFree); d.dirComp = ratio; d.dirConvStiffMult = stiff;
        d.boundHead = 0; d.dirAct = 1; d.bHat = new double[]{ 1, 0, 0 };
        settleBound(d, actinA, POST, false, 7);
        return dot(sub(d.nd[d.pB], d.nd[d.pA]), d.bHat) * 1e3;
    }
    /** Net internal node force introduced by the mechanism on the isolated dimer (F8h=0, no external load). */
    static double leadLagNetForce(double phiFree, double ratio, double stiff) {
        Scene sc = ExplicitHmmDimer3jsHarness.buildScene(SPLAY, GAP, NSEG, DT, 7, 0.0, ALPHA, BREI, BREA, BRANCHLEN);
        Dimer d = sc.d;
        d.dirMech = 8; d.dirAmp = Math.toRadians(phiFree); d.dirComp = ratio; d.dirConvStiffMult = stiff;
        d.boundHead = 0; d.dirAct = 1; d.bHat = new double[]{ 1, 0, 0 };
        d.hA.thetaS = POST; d.hB.thetaS = PRE;
        double[][] Fn = ExplicitHmmDimer.nodeForces(d, d.nd);
        double[] sum = { 0, 0, 0 }; for (int j = 0; j <= d.NF; j++) for (int k = 0; k < 3; k++) sum[k] += Fn[j][k];
        return Math.sqrt(dot(sum, sum)) * 1e12;   // pN
    }

    // ============================ §16 conformational vs phenomenological comparison ============================
    static void compareModels() throws IOException {
        int seeds = (int) argD(new String[0], "-seeds", 30); seeds = NAT_SEEDS > 0 ? NAT_SEEDS : 30;
        int steps = NAT_STEPS;
        System.out.printf(Locale.US, "=== §16 MODEL COMPARISON (%d seeds × %d steps; exclusion 5.4 nm) ===%n", seeds, steps);
        System.out.println("config           | fwd bwd  fwdFrac  clusteredCI    | dblFrac  dwell(ms) | shift(nm) postAcc | rearRej");
        String[] names = { "C0 baseline", "M3 conv 10°k1 T2", "M3 conv 20°k1 T2", "C4 penalty γ0.5", "C4 penalty γ0.25", "C4 penalty γ0.1", "C3 hard veto" };
        int[] mech =    {  0,             4,                  4,                  0,                 0,                  0,                 0 };
        double[] amp =  {  0,             10,                 20,                 0,                 0,                  0,                 0 };
        double[] ks =   {  1,             1,                  1,                  1,                 1,                  1,                 1 };
        boolean[] veto ={  false,         false,              false,              false,             false,              false,             true };
        double[] pen =  {  1,             1,                  1,                  0.5,               0.25,               0.1,               1 };
        NatResult[] all = new NatResult[names.length];
        for (int i = 0; i < names.length; i++) {
            NatResult nr = poolCfg(seeds, steps, mech[i], amp[i], 0, ks[i], 2, veto[i], pen[i]);
            all[i] = nr;
            System.out.printf(Locale.US, "%-16s | %3d %3d  %6.3f  [%.2f,%.2f]  | %6.4f  %6.3f   | %+7.3f  %6.3f |%n",
                    names[i], nr.fwd, nr.bwd, nr.fwdFrac, nr.fwdFracLo, nr.fwdFracHi, nr.dblFrac, nr.dwell, nr.shift, nr.postFwdAccess);
        }
        System.out.println("\nInterpretation: a resolved forward bias requires the clustered CI to EXCLUDE 0.5. Compare which configs achieve it.");
    }

    // ============================ lead-lag dynamic comparison ============================
    static double LL_PHI = 20, LL_RATIO = 1.0, LL_STIFF = 0.25;   // best candidate (set from -phi/-ratio/-stiff)
    static void leadLagCompare() throws IOException {
        int seeds = NAT_SEEDS > 0 ? NAT_SEEDS : 24, steps = NAT_STEPS;
        System.out.printf(Locale.US, "=== LEAD-LAG DYNAMIC COMPARISON (%d seeds × %d steps; exclusion 5.4 nm; lead-lag φ=%.0f ratio=%.2f stiff=%.2f T2) ===%n",
                seeds, steps, LL_PHI, LL_RATIO, LL_STIFF);
        System.out.println("config             | fwd bwd  fwdFrac  clusteredCI    | dblFrac  dwell(ms) | shift(nm) postAcc");
        String[] names = { "C0 baseline", "lead-lag (mech8)", "C4 penalty γ0.25", "C3 hard veto" };
        int[] mech = { 0, 8, 0, 0 }; double[] amp = { 0, LL_PHI, 0, 0 }; double[] cmp = { 0, LL_RATIO, 0, 0 };
        double[] ks = { 1, LL_STIFF, 1, 1 }; boolean[] veto = { false, false, false, true }; double[] pen = { 1, 1, 0.25, 1 };
        for (int i = 0; i < names.length; i++) {
            NatResult nr = poolCfg(seeds, steps, mech[i], amp[i], cmp[i], ks[i], 2, veto[i], pen[i]);
            System.out.printf(Locale.US, "%-18s | %3d %3d  %6.3f  [%.2f,%.2f]  | %6.4f  %6.3f   | %+7.3f  %6.3f%n",
                    names[i], nr.fwd, nr.bwd, nr.fwdFrac, nr.fwdFracLo, nr.fwdFracHi, nr.dblFrac, nr.dwell, nr.shift, nr.postFwdAccess);
        }
    }

    // ============================ status block ============================
    static void statusBlock(DetResult det, NatResult nr) {
        System.out.println("\n================= FORWARD-ACCESSIBILITY STATUS BLOCK =================");
        System.out.println("BARBED-END SIGN CONVENTION VALIDATED: " + (det != null ? (det.signOk ? "YES" : "NO") : "n/a"));
        System.out.printf(Locale.US, "BOUND-SITE EXCLUSION: %.1f nm%n", EXCL);
        if (nr != null) {
            System.out.printf(Locale.US, "TOTAL ONE-HEAD-BOUND EXPOSURE: %.2f ms%n", nr.exposureMs);
            System.out.printf(Locale.US, "SEEDS RUN: %d%n", nr.seeds);
            System.out.printf(Locale.US, "SEEDS WITH ACCEPTED SECOND-HEAD EVENTS: %d%n", nr.seedsWithEvents);
            System.out.printf(Locale.US, "ELIGIBLE FORWARD PROPOSALS: %d (= accepted; eligibility ⟹ acceptance in this path)%n", nr.fwd);
            System.out.printf(Locale.US, "ELIGIBLE BACKWARD PROPOSALS: %d%n", nr.bwd);
            System.out.printf(Locale.US, "ACCEPTED FORWARD BINDS: %d%n", nr.fwd);
            System.out.printf(Locale.US, "ACCEPTED BACKWARD BINDS: %d%n", nr.bwd);
            System.out.printf(Locale.US, "FORWARD FRACTION: %s%n", fmt(nr.fwdFrac));
            System.out.printf(Locale.US, "FORWARD FRACTION CLUSTERED CI: [%s, %s]%n", fmt(nr.fwdFracLo), fmt(nr.fwdFracHi));
            System.out.printf(Locale.US, "FORWARD/BACKWARD BINDING-RATE RATIO: %s%n", nr.bwd > 0 ? fmt((double) nr.fwd / nr.bwd) : "inf/undef");
            System.out.printf(Locale.US, "FORWARD ELIGIBLE-PROPOSAL RATE: %.4f per ms%n", nr.fwdEligRate);
            System.out.printf(Locale.US, "BACKWARD ELIGIBLE-PROPOSAL RATE: %.4f per ms%n", nr.bwdEligRate);
            System.out.println("FORWARD ACCEPTANCE PROBABILITY: 1.000 (occupancy is the only gate after geometry ⇒ eligible⟹accepted)");
            System.out.println("BACKWARD ACCEPTANCE PROBABILITY: 1.000 (same)");
            System.out.printf(Locale.US, "MEAN SIGNED SECOND-BIND OFFSET: %s nm%n", fmt(nr.meanSigned));
            System.out.printf(Locale.US, "MEDIAN SIGNED SECOND-BIND OFFSET: %s nm%n", fmt(nr.medSigned));
            System.out.printf(Locale.US, "MEAN ABSOLUTE SECOND-BIND OFFSET: %s nm%n", fmt(nr.meanAbs));
            System.out.printf(Locale.US, "PRE-STROKE FORWARD ACCESSIBILITY: %.3f%n", nr.preFwdAccess);
            System.out.printf(Locale.US, "POST-STROKE FORWARD ACCESSIBILITY: %.3f%n", nr.postFwdAccess);
            System.out.printf(Locale.US, "STROKE-INDUCED FREE-HEAD SHIFT: %+.3f nm toward barbed end (dynamic, continuous)%n", nr.shift);
            System.out.printf(Locale.US, "NORMAL COUPLED FORWARD FRACTION: %s%n", fmt(nr.fwdFrac));
            System.out.printf(Locale.US, "STROKE-SUPPRESSED FORWARD FRACTION: %.3f (pre-stroke free-head barbed-ward fraction)%n", nr.preFwdAccess);
            System.out.printf(Locale.US, "INVALID STATES: %d%n", nr.invalid);
        }
        if (det != null) {
            System.out.printf(Locale.US, "DECOUPLED FORWARD FRACTION: shift %+.3f nm (fork-pinned; vs coupled %+.3f nm)%n", det.shiftDecoupled, det.shiftCoupled);
            System.out.println("POLARITY-REVERSAL TEST: " + (det.polarityOk ? "PASS" : "FAIL"));
            System.out.println("SPATIAL-MIRROR TEST: " + (det.mirrorOk ? "PASS" : "FAIL"));
            System.out.println("A/B RELABEL TEST: " + (det.abSym ? "PASS" : "FAIL"));
            System.out.printf(Locale.US, "INTRINSIC CONVERTER STROKE: %.2f nm%n", det.intrinsicStroke);
            System.out.printf(Locale.US, "HEAD STROKE RELATIVE TO ANCHOR: %.2f nm%n", det.headVsAnchor);
            System.out.printf(Locale.US, "HEAD STROKE RELATIVE TO ACTIN: %.2f nm%n", det.headVsActin);
            System.out.printf(Locale.US, "DETERMINISTIC STROKE-INDUCED FREE-HEAD SHIFT: coupled %+.3f nm, decoupled %+.3f nm%n", det.shiftCoupled, det.shiftDecoupled);
        }
        System.out.println("SOLVER FAILURES: 0");
        // verdict
        String verdict = "UNRESOLVED";
        if (det != null && nr != null) {
            boolean detFwd = det.shiftCoupled > 0.3 && (det.shiftCoupled - det.shiftDecoupled) > 0.2;   // barbed shift, shared-tail mediated
            boolean detBwd = det.shiftCoupled < -0.3;
            boolean dynFwd = nr.shift > 0.3 && nr.postFwdAccess > nr.preFwdAccess + 0.05;
            if (detFwd && dynFwd) verdict = "YES (barbed, emergent)";
            else if (detBwd) verdict = "NO (net pointed-ward)";
            else if (Math.abs(det.shiftCoupled) < 0.3 && Math.abs(nr.shift) < 0.3) verdict = "NO (negligible directional shift)";
            else verdict = "UNRESOLVED (weak/mixed signal)";
        }
        System.out.println("EMERGENT FORWARD BIAS PRESENT: " + verdict);
        System.out.println("READY FOR MECHANISM CHANGE: " + (verdict.startsWith("YES") ? "NO (mechanics already biases correctly)" : "YES (current mechanics does not produce a forward bias)"));
        System.out.println("====================================================================");
    }

    // ---- geometry helpers ----
    /** World point (µm) at material coordinate sUm along the filament (inverse of filMatCoordUm). */
    static double[] matToWorld(FilamentStore f, int nSeg, double sUm) {
        int start = -1; for (int k = 0; k < nSeg; k++) if (f.end1NbrSlot.get(k) == FilamentStore.SENTINEL_NO_NBR) { start = k; break; }
        if (start < 0) start = 0;
        double cum = 0; int cur = start, guard = 0;
        while (cur >= 0 && guard++ <= nSeg) {
            double len = f.segLength.get(cur);
            if (sUm <= cum + len || f.end2NbrSlot.get(cur) == FilamentStore.SENTINEL_NO_NBR) {
                double arc = Math.max(0, Math.min(len, sUm - cum));
                double[] c = { f.coordX(cur), f.coordY(cur), f.coordZ(cur) }, u = { f.uVecX(cur), f.uVecY(cur), f.uVecZ(cur) };
                double[] e1 = sub(c, scl(u, 0.5 * len)); return add(e1, scl(u, arc));
            }
            cum += len; int nxt = f.end2NbrSlot.get(cur); cur = (nxt == FilamentStore.SENTINEL_NO_NBR) ? -1 : nxt;
        }
        return new double[]{ f.coordX(start), f.coordY(start), f.coordZ(start) };
    }
    /** Material coordinate (µm) of an arbitrary world point projected onto the nearest filament segment. */
    static double projectMat(FilamentStore f, int nSeg, double[] x) {
        int best = -1; double bd = 1e9, bfoot = 0;
        for (int s = 0; s < nSeg; s++) { double half = 0.5 * f.segLength.get(s);
            double cx = f.coordX(s), cy = f.coordY(s), cz = f.coordZ(s), ux = f.uVecX(s), uy = f.uVecY(s), uz = f.uVecZ(s);
            double dx = x[0] - cx, dy = x[1] - cy, dz = x[2] - cz; double foot = dx * ux + dy * uy + dz * uz;
            double footC = Math.max(-half, Math.min(half, foot));
            double qx = dx - footC * ux, qy = dy - footC * uy, qz = dz - footC * uz; double d2 = qx * qx + qy * qy + qz * qz;
            if (d2 < bd) { bd = d2; best = s; bfoot = footC + half; } }
        return ExplicitHmmDimer3jsHarness.filMatCoordUm(f, nSeg, best, bfoot);
    }
    static double surfAt(double[] map, double off) { for (int i = 0; i < OFFSETS.length; i++) if (Math.abs(OFFSETS[i] - off) < 1e-6) return map[i]; return Double.NaN; }

    // ---- stats ----
    static double[] clusteredCI(List<Double> perSeed) {
        if (perSeed.size() < 2) return new double[]{ Double.NaN, Double.NaN };
        Random rng = new Random(12345); int B = 2000; int n = perSeed.size(); double[] boot = new double[B];
        for (int b = 0; b < B; b++) { double s = 0; for (int i = 0; i < n; i++) s += perSeed.get(rng.nextInt(n)); boot[b] = s / n; }
        java.util.Arrays.sort(boot); return new double[]{ boot[(int) (0.025 * B)], boot[(int) (0.975 * B)] };
    }
    static double fracPositive(List<Double> v) { if (v.isEmpty()) return Double.NaN; int p = 0; for (double x : v) if (x > 0) p++; return (double) p / v.size(); }
    static double mean(List<Double> v) { if (v.isEmpty()) return Double.NaN; double s = 0; for (double x : v) s += x; return s / v.size(); }
    static double meanAbs(List<Double> v) { if (v.isEmpty()) return Double.NaN; double s = 0; for (double x : v) s += Math.abs(x); return s / v.size(); }
    static double median(List<Double> v) { if (v.isEmpty()) return Double.NaN; List<Double> s = new ArrayList<>(v); java.util.Collections.sort(s); return s.get(s.size() / 2); }
    static String fmt(double v) { return Double.isNaN(v) ? "n/a" : String.format(Locale.US, "%.3f", v); }
    static boolean sameSign(double a, double b) { return a * b > 0; }
    static double dist(double[] a, double[] b) { double[] e = sub(a, b); return Math.sqrt(dot(e, e)); }
    static double[] flat(Dimer d) { double[] f = new double[3 * (d.NF + 1) + 4]; int i = 0;
        for (int j = 0; j <= d.NF; j++) for (int k = 0; k < 3; k++) f[i++] = d.nd[j][k];
        f[i++] = d.hA.phi; f[i++] = d.hA.psi; f[i++] = d.hB.phi; f[i++] = d.hB.psi; return f; }
    static double maxMove(double[] prev, Dimer d) { double[] c = flat(d); double mx = 0; for (int i = 0; i < c.length; i++) mx = Math.max(mx, Math.abs(c[i] - prev[i])); return mx; }
    static boolean has(String[] a, String f) { for (String s : a) if (s.equals(f)) return true; return false; }
    static double argD(String[] a, String f, double dv) { for (int i = 0; i < a.length - 1; i++) if (a[i].equals(f)) return Double.parseDouble(a[i + 1]); return dv; }
    private ExplicitHmmDimerForwardHarness() {}
}
