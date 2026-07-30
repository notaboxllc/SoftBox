package softbox;

/**
 * Occupancy, zero-bound-gap and PHASE-MEMORY instrumentation for the low-occupancy Vilfan study.
 * <p>
 * The parent roll-Brownian result was obtained at a median of ~82 bound heads, where collective
 * stiffness confines axial motion to 0.32 nm and roll to 0.056 rad. This class measures the
 * quantities that decide whether that robustness is intrinsic to target-zone depletion or merely a
 * consequence of collective clamping:
 * <ul>
 *   <li>the FULL occupancy distribution P(N_b = n), time-weighted, not just its mean or median;</li>
 *   <li>zero-bound and one-bound interval statistics, and the occupancy transition counts;</li>
 *   <li>how much travel and how much net roll is accumulated at each occupancy;</li>
 *   <li>and the load-bearing new measures: AXIAL and ANGULAR phase memory across zero-bound gaps.</li>
 * </ul>
 * <p>
 * <b>Phase-memory definitions</b> (preregistered; the helical coupling {@code m} is fixed from the
 * lattice geometry and is never fitted):
 * <pre>
 *   C_Theta = &lt; cos(Theta_rebind - Theta_release) &gt;
 *   C_X     = &lt; cos(2 pi (X_rebind - X_release) / L_zone) &gt;
 *   C_joint = &lt; cos(2 pi dX / L_zone + m dTheta) &gt;
 * </pre>
 * Free-diffusion expectations, used as the null: during an unbound gap of duration {@code t},
 * {@code C_Theta = exp(-D_Theta t)} and {@code C_X = exp(-(2 pi / L)^2 D_X t)}, giving 1/e memory
 * times of 2.045 ms and 0.424 ms respectively at the assay viscosity. Axial phase is therefore lost
 * ~4.8x faster than angular phase, which is why both are tracked separately.
 */
public final class VilfanOccupancy {

    /* ---------------- occupancy distribution ---------------- */
    /** time-weighted occupancy histogram, bins 0..5 plus a >=6 tail. */
    public final double[] occTime = new double[7];
    public double totalTime;
    public double sumNb, sumNb2;                 // time-weighted moments
    public final long[] nbVisits = new long[7];  // entries into each occupancy bin

    /* ---------------- transitions ---------------- */
    public long t01, t10, t12, t21, tUp, tDown;

    /* ---------------- travel / roll partitioned by occupancy ---------------- */
    public final double[] travelAt = new double[4];   // Nb = 0, 1, 2, >=3   (nm, signed)
    public final double[] rollAt   = new double[4];   // rad, signed

    /* ---------------- zero-bound and one-bound intervals ---------------- */
    public long nZeroIntervals, nOneIntervals;
    public double zeroTime, oneTime, zeroTimeMax, oneTimeMax;
    /** log-spaced duration histogram, 1 us .. 10 s, 8 decades x 4 bins. */
    public final long[] zeroDurHist = new long[32];
    public final long[] oneDurHist  = new long[32];

    /* ---------------- rate bookkeeping conditional on occupancy ---------------- */
    public final long[] attachAt = new long[4], detachAt = new long[4];
    public final double[] timeAt = new double[4];

    /* ---------------- phase memory across zero-bound gaps ---------------- */
    public long nGaps;
    public double sumCosTh, sumSinTh, sumCosX, sumCosJoint;
    public double sumGap, sumGapDX, sumGapDTh;
    /** gap-duration-binned memory: same log bins as zeroDurHist. */
    public final long[] gapN = new long[32];
    public final double[] gapCosTh = new double[32], gapCosX = new double[32];
    /** first post-gap attachment: zone coordinate and whether it was before the centre. */
    public long nFirstPostGap, nFirstPostGapBefore;
    public double sumFirstPostGapXa;
    /** attachments on continuously tethered intervals (no gap since the last attachment). */
    public long nTethered, nTetheredBefore;
    public double sumTetheredXa;

    private final double LzoneNm, mCouple;
    private int lastNb = -1;
    private double intervalStart;
    private boolean inGap;
    private double gapX, gapTh, gapT;
    private boolean pendingFirstPostGap;

    /** @param mCouple helical coupling, fixed from the lattice: dTheta per zone period is pi. */
    public VilfanOccupancy(double LzoneNm, double mCouple) {
        this.LzoneNm = LzoneNm; this.mCouple = mCouple;
    }

    private static int bin(int nb) { return nb >= 6 ? 6 : nb; }
    private static int q(int nb)   { return nb >= 3 ? 3 : nb; }
    private static int durBin(double t) {
        if (!(t > 0)) return 0;
        double d = Math.log10(t) + 6.0;            // 1 us -> 0
        int b = (int) Math.floor(d * 4.0);
        return b < 0 ? 0 : (b > 31 ? 31 : b);
    }

    /**
     * Accumulate one substep of duration {@code dt} spent at occupancy {@code nb}, over which the
     * filament moved {@code dX} nm and rolled {@code dTheta} rad. Called once per accepted substep.
     */
    public void substep(int nb, double dt, double dX, double dTheta, double tNow, double X, double Th) {
        int b = bin(nb), k = q(nb);
        occTime[b] += dt; totalTime += dt;
        sumNb += nb * dt; sumNb2 += (double) nb * nb * dt;
        travelAt[k] += dX; rollAt[k] += dTheta; timeAt[k] += dt;

        if (nb != lastNb) {
            if (lastNb >= 0) {
                double dur = tNow - intervalStart;
                if (lastNb == 0) {
                    nZeroIntervals++; zeroTime += dur; zeroDurHist[durBin(dur)]++;
                    if (dur > zeroTimeMax) zeroTimeMax = dur;
                } else if (lastNb == 1) {
                    nOneIntervals++; oneTime += dur; oneDurHist[durBin(dur)]++;
                    if (dur > oneTimeMax) oneTimeMax = dur;
                }
                if (lastNb == 0 && nb == 1) t01++;
                else if (lastNb == 1 && nb == 0) t10++;
                else if (lastNb == 1 && nb == 2) t12++;
                else if (lastNb == 2 && nb == 1) t21++;
                else if (nb > lastNb) tUp++;
                else tDown++;
            }
            nbVisits[b]++;
            intervalStart = tNow;
            // gap bookkeeping: entering / leaving the unbound state
            if (nb == 0 && !inGap) { inGap = true; gapX = X; gapTh = Th; gapT = tNow; }
            else if (nb > 0 && inGap) {
                inGap = false;
                double dur = tNow - gapT, ddX = X - gapX, ddTh = Th - gapTh;
                double cTh = Math.cos(ddTh), sTh = Math.sin(ddTh);
                double cX = Math.cos(2 * Math.PI * ddX / LzoneNm);
                double cJ = Math.cos(2 * Math.PI * ddX / LzoneNm + mCouple * ddTh);
                nGaps++; sumCosTh += cTh; sumSinTh += sTh; sumCosX += cX; sumCosJoint += cJ;
                sumGap += dur; sumGapDX += ddX; sumGapDTh += ddTh;
                int db = durBin(dur);
                gapN[db]++; gapCosTh[db] += cTh; gapCosX[db] += cX;
                pendingFirstPostGap = true;
            }
            lastNb = nb;
        }
    }

    /** Classify an attachment as the first after a zero-bound gap, or as continuously tethered. */
    public void attachment(int nbBefore, double xZone) {
        attachAt[q(nbBefore)]++;
        if (pendingFirstPostGap || nbBefore == 0) {
            nFirstPostGap++; sumFirstPostGapXa += xZone;
            if (xZone > 0) nFirstPostGapBefore++;
            pendingFirstPostGap = false;
        } else {
            nTethered++; sumTetheredXa += xZone;
            if (xZone > 0) nTetheredBefore++;
        }
    }

    public void detachment(int nbBefore) { detachAt[q(nbBefore)]++; }

    public double meanNb()      { return totalTime > 0 ? sumNb / totalTime : 0; }
    public double varNb()       { double m = meanNb(); return totalTime > 0 ? sumNb2 / totalTime - m * m : 0; }
    public double pZero()       { return totalTime > 0 ? occTime[0] / totalTime : 0; }
    public double meanGapS()    { return nGaps > 0 ? sumGap / nGaps : 0; }
    public double cTheta()      { return nGaps > 0 ? sumCosTh / nGaps : Double.NaN; }
    public double sTheta()      { return nGaps > 0 ? sumSinTh / nGaps : Double.NaN; }
    public double cX()          { return nGaps > 0 ? sumCosX / nGaps : Double.NaN; }
    public double cJoint()      { return nGaps > 0 ? sumCosJoint / nGaps : Double.NaN; }
    /** median occupancy from the time-weighted histogram. */
    public int medianNb() {
        double half = 0.5 * totalTime, cum = 0;
        for (int i = 0; i < occTime.length; i++) { cum += occTime[i]; if (cum >= half) return i; }
        return 0;
    }
}
