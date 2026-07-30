package softbox;

/**
 * Occupancy, zero-bound-gap, PHASE-MEMORY and CONDITIONAL-ATTACHMENT instrumentation for the
 * low-occupancy Vilfan study.
 * <p>
 * <b>Gap boundaries are EXACT EVENT TIMES.</b> The first version detected occupancy transitions by
 * comparing {@code N_b} between accepted substeps, which placed each boundary one substep late and
 * over-estimated gap durations by up to ~2 substeps — enough that at high {@code kD}, where an
 * interval may be one substep long, the summed gap time disagreed with the time-weighted
 * {@code P(N_b = 0)} by 25–75 %. Occupancy can only change at a chemical event, so
 * {@link #transition} is now called AT the event with the exact event time and the exact continuous
 * {@code X}, {@code Theta}; {@link #substep} only accumulates residence time. The two must then agree
 * to floating-point, which gate OCC-1 checks.
 * <p>
 * <b>Conditional categories</b> (mutually exclusive, fixed before any pilot arm):
 * <pre>
 *   A FIRST_POST_GAP  the first attachment after an N_b = 0 interval
 *   B EARLY_POST_GAP  within one bound-head lifetime (1/kD) of that reattachment
 *   C TETHERED_SPARSE continuously N_b &gt;= 1 for &gt;= 1/kD, instantaneous N_b in {1,2}
 *   D TETHERED_MULTI  uninterrupted N_b &gt;= 3
 * </pre>
 * First-post-gap attachments are additionally binned by gap duration at the preregistered memory
 * scales (axial 0.424 ms, angular 2.045 ms): {@code <0.5, 0.5-2, 2-10, >10 ms}.
 * <p>
 * <b>Shadow labels.</b> The matched-path no-depletion shadow accumulates its hazard-weighted
 * attachment-position flux separately per substep-level state label (in-gap / early-post-gap /
 * tethered-sparse / tethered-multi), so a real per-category bias can be compared against the
 * availability-blind expectation for the same part of the same trajectory.
 */
public final class VilfanOccupancy {

    public static final int A_FIRST = 0, B_EARLY = 1, C_SPARSE = 2, D_MULTI = 3;
    public static final String[] CATNAME = {"first-post-gap", "early-post-gap",
                                            "tethered-sparse", "tethered-multihead"};
    /** preregistered gap bins, seconds: <0.5 ms, 0.5-2 ms, 2-10 ms, >10 ms. */
    public static final double[] GAP_EDGES = {0.5e-3, 2.0e-3, 10.0e-3};

    /* ---------------- occupancy distribution ---------------- */
    public final double[] occTime = new double[7];
    public double totalTime, sumNb, sumNb2;
    public final long[] nbVisits = new long[7];

    /* ---------------- transitions ---------------- */
    public long t01, t10, t12, t21, tUp, tDown;

    /* ---------------- travel / roll by occupancy ---------------- */
    public final double[] travelAt = new double[4], rollAt = new double[4], timeAt = new double[4];

    /* ---------------- intervals ---------------- */
    public long nZeroIntervals, nOneIntervals;
    public double zeroTime, oneTime, zeroTimeMax, oneTimeMax;
    /** exact-event-time gap durations, summed; gate OCC-1 compares this with occTime[0]. */
    public double gapTimeSum;
    public final long[] zeroDurHist = new long[32];

    /* ---------------- phase memory ---------------- */
    public long nGaps;
    public double sumCosTh, sumSinTh, sumCosX, sumCosJoint, sumGap, sumGapDX, sumGapDTh;
    public final long[] gapN = new long[32];
    public final double[] gapCosTh = new double[32], gapCosX = new double[32];

    /* ---------------- conditional attachment statistics ---------------- */
    public final long[] catN = new long[4], catBefore = new long[4];
    public final double[] catXa = new double[4], catTh = new double[4], catXi = new double[4];
    public final double[] catXa2 = new double[4];
    /** first-post-gap attachments binned by gap duration (4 bins). */
    public final long[] binN = new long[4], binBefore = new long[4];
    public final double[] binXa = new double[4], binTh = new double[4];
    public final double[] binXa2 = new double[4];
    public final double[] binGapSum = new double[4];

    /* ---------------- per-episode roll (steady vs intermittent) ----------------
     * An EPISODE is a contiguous interval with N_b >= 1, i.e. the stretch between two zero-bound gaps.
     * Roll accumulated inside episodes is the roll the mechanism can be held responsible for; roll
     * during a gap is free rotational diffusion. Storing the per-episode signed roll lets the analysis
     * ask whether Omega is carried steadily by all episodes or intermittently by a few large ones. */
    public int nEp;
    public double[] epDTh = new double[4096], epDur = new double[4096], epT0 = new double[4096];
    private double epTh0, epStart; private boolean inEp;

    /* ---------------- matched-path shadow flux, per state label ---------------- */
    public final double[] shW = new double[4], shWX = new double[4];

    /* ---------------- rates by occupancy ---------------- */
    public final long[] attachAt = new long[4], detachAt = new long[4];

    private final double LzoneNm, mCouple, boundLifetimeS;
    private int nb = -1;
    private double intervalStart, tethStart;
    private boolean inGap;
    private double gapX, gapTh, gapT, lastGapDur, rebindT;
    /* ---- OCC-1: residence is PENDING until the interval closes at an event ----
     * Counting residence as it is accrued orphans the substeps of an interval that never closes --
     * which is exactly what happens when the filament reaches the end of the motor field, the total
     * hazard goes to zero and advanceRollStochastic discards its accumulated tAcc. Those substeps are
     * almost always at N_b = 0 (the filament has run out of motors), so they inflated P(N_b = 0)
     * without inflating any gap: 7224 orphaned 5 us substeps put the duty-axis accounting ratio at
     * 0.912 instead of 1. Residence is therefore buffered per interval and committed only when an
     * event closes it, which makes the two clocks agree by construction. */
    private double resInterval, pendDX, pendDTh; private int pendNb;
    private boolean justEnabled;
    public long nAcctBad; public double acctExcess, acctExcessMax; public final long[] acctBadNb = new long[7];
    private boolean analysed;

    public VilfanOccupancy(double LzoneNm, double mCouple, double boundLifetimeS) {
        this.LzoneNm = LzoneNm; this.mCouple = mCouple; this.boundLifetimeS = boundLifetimeS;
    }

    public void setAnalysed(boolean a) {
        if (a && !analysed) justEnabled = true;      // the interval straddling the warm-up boundary
        this.analysed = a;
    }

    private static int bin7(int n) { return n >= 6 ? 6 : n; }
    private static int q(int n)    { return n >= 3 ? 3 : n; }
    private static int durBin(double t) {
        if (!(t > 0)) return 0;
        int b = (int) Math.floor((Math.log10(t) + 6.0) * 4.0);
        return b < 0 ? 0 : (b > 31 ? 31 : b);
    }
    private static int gapBin(double g) {
        for (int i = 0; i < GAP_EDGES.length; i++) if (g < GAP_EDGES[i]) return i;
        return GAP_EDGES.length;
    }

    /** Pends residence for the current interval. Occupancy cannot change here. */
    public void substep(int nbNow, double dt, double dX, double dTheta) {
        if (!analysed) return;
        pendNb = nbNow; resInterval += dt; pendDX += dX; pendDTh += dTheta;
    }

    /**
     * Called AT a chemical event, after the motor state change, with the exact event time and the
     * exact continuous filament coordinates. This is what makes gap boundaries event-exact.
     */
    public void transition(int nbNew, double tEvent, double X, double Th) {
        if (nb >= 0 && analysed && !justEnabled) {
            double dur = tEvent - intervalStart;
            double ex = resInterval - dur;
            if (Math.abs(ex) > 1e-12 * Math.max(1.0, Math.abs(dur))) {
                nAcctBad++; acctExcess += ex; acctBadNb[bin7(nb)]++;
                if (Math.abs(ex) > Math.abs(acctExcessMax)) acctExcessMax = ex;
            }
            // commit the closed interval's residence
            occTime[bin7(pendNb)] += resInterval; totalTime += resInterval;
            sumNb += pendNb * resInterval; sumNb2 += (double) pendNb * pendNb * resInterval;
            int kq = q(pendNb);
            travelAt[kq] += pendDX; rollAt[kq] += pendDTh; timeAt[kq] += resInterval;
            if (nb == 0) { nZeroIntervals++; zeroTime += dur; zeroDurHist[durBin(dur)]++;
                           if (dur > zeroTimeMax) zeroTimeMax = dur; }
            else if (nb == 1) { nOneIntervals++; oneTime += dur;
                                if (dur > oneTimeMax) oneTimeMax = dur; }
            if (nb == 0 && nbNew == 1) t01++;
            else if (nb == 1 && nbNew == 0) t10++;
            else if (nb == 1 && nbNew == 2) t12++;
            else if (nb == 2 && nbNew == 1) t21++;
            else if (nbNew > nb) tUp++; else tDown++;
        }
        if (analysed) nbVisits[bin7(nbNew)]++;
        intervalStart = tEvent; resInterval = 0.0; pendDX = 0.0; pendDTh = 0.0;
        justEnabled = false;

        // ---- episode bookkeeping (contiguous N_b >= 1) ----
        if (nbNew > 0 && !inEp) { inEp = true; epTh0 = Th; epStart = tEvent; }
        else if (nbNew == 0 && inEp) {
            inEp = false;
            if (analysed) {
                if (nEp == epDTh.length) {
                    epDTh = java.util.Arrays.copyOf(epDTh, nEp * 2);
                    epDur = java.util.Arrays.copyOf(epDur, nEp * 2);
                    epT0  = java.util.Arrays.copyOf(epT0,  nEp * 2);
                }
                epDTh[nEp] = Th - epTh0; epDur[nEp] = tEvent - epStart;
                epT0[nEp] = epStart; nEp++;
            }
        }

        if (nbNew == 0 && !inGap) {                       // released: record exact release phase
            inGap = true; gapX = X; gapTh = Th; gapT = tEvent;
        } else if (nbNew > 0 && inGap) {                  // reattached: exact gap duration and phase
            inGap = false;
            double dur = tEvent - gapT, dX = X - gapX, dTh = Th - gapTh;
            lastGapDur = dur; rebindT = tEvent; tethStart = tEvent;
            if (analysed) {
                gapTimeSum += dur;
                double cTh = Math.cos(dTh), cX = Math.cos(2 * Math.PI * dX / LzoneNm);
                nGaps++; sumCosTh += cTh; sumSinTh += Math.sin(dTh); sumCosX += cX;
                sumCosJoint += Math.cos(2 * Math.PI * dX / LzoneNm + mCouple * dTh);
                sumGap += dur; sumGapDX += dX; sumGapDTh += dTh;
                int db = durBin(dur); gapN[db]++; gapCosTh[db] += cTh; gapCosX[db] += cX;
            }
        }
        if (nbNew == 0) tethStart = Double.NaN;
        nb = nbNew;
    }

    /**
     * Classify an attachment. {@code nbBefore} is the occupancy immediately before it.
     * <p>
     * <b>The gap this attachment ENDS is still open here.</b> The harness calls this inside the
     * transition-application block, before {@link #transition} closes the interval, so
     * {@code lastGapDur} still holds the PREVIOUS gap and {@code awaitingFirst} is stale. Using them
     * binned every first-post-gap attachment by the wrong gap — visible as a ">10 ms" bin whose mean
     * gap was 0.29 ms. The current gap duration is {@code tEvent - gapT}, and {@code inGap} is the
     * exact test for "this attachment ends a zero-bound interval".
     */
    public void attachment(int nbBefore, double tEvent, double xZone, double thetaA, double xiA) {
        if (!analysed) return;
        attachAt[q(nbBefore)]++;
        int cat;
        if (inGap) {
            cat = A_FIRST;
            double thisGap = tEvent - gapT;              // the gap being closed by THIS attachment
            int gb = gapBin(thisGap);
            binN[gb]++; binXa[gb] += xZone; binTh[gb] += thetaA; binGapSum[gb] += thisGap;
            binXa2[gb] += xZone * xZone;
            if (xZone > 0) binBefore[gb]++;
        } else if (!Double.isNaN(rebindT) && tEvent - rebindT < boundLifetimeS) {
            cat = B_EARLY;
        } else if (nbBefore >= 3) {
            cat = D_MULTI;
        } else {
            cat = C_SPARSE;
        }
        catN[cat]++; catXa[cat] += xZone; catTh[cat] += thetaA; catXi[cat] += xiA;
        catXa2[cat] += xZone * xZone;
        if (xZone > 0) catBefore[cat]++;
    }

    public void detachment(int nbBefore) { if (analysed) detachAt[q(nbBefore)]++; }

    /** State label for the matched-path shadow at the current substep. */
    public int shadowLabel(int nbNow, double tNow) {
        if (nbNow == 0) return A_FIRST;                                  // in-gap
        if (!Double.isNaN(rebindT) && tNow - rebindT < boundLifetimeS) return B_EARLY;
        return nbNow >= 3 ? D_MULTI : C_SPARSE;
    }
    public void shadow(int label, double w, double wx) { if (analysed) { shW[label] += w; shWX[label] += wx; } }

    public double meanNb()  { return totalTime > 0 ? sumNb / totalTime : 0; }
    public double varNb()   { double m = meanNb(); return totalTime > 0 ? sumNb2 / totalTime - m * m : 0; }
    public double pZero()   { return totalTime > 0 ? occTime[0] / totalTime : 0; }
    public double meanGapS(){ return nGaps > 0 ? sumGap / nGaps : 0; }
    public double cTheta()  { return nGaps > 0 ? sumCosTh / nGaps : Double.NaN; }
    public double sTheta()  { return nGaps > 0 ? sumSinTh / nGaps : Double.NaN; }
    public double cX()      { return nGaps > 0 ? sumCosX / nGaps : Double.NaN; }
    public double cJoint()  { return nGaps > 0 ? sumCosJoint / nGaps : Double.NaN; }
    /** gate OCC-1: exact-event gap time divided by time-weighted zero-occupancy residence. */
    public double gapAccountRatio() { return occTime[0] > 0 ? gapTimeSum / occTime[0] : Double.NaN; }
    public int medianNb() {
        double half = 0.5 * totalTime, cum = 0;
        for (int i = 0; i < occTime.length; i++) { cum += occTime[i]; if (cum >= half) return i; }
        return 0;
    }
}
