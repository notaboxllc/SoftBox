package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * MEASUREMENT-ONLY (SUBSTEP_FEASIBILITY_FINDINGS) — host-side tracker of per-bound-motor cross-bridge SITE
 * motion over fixed sim-time WINDOWS (the candidate sub-stepping OUTER dt, default 1e-4 s).
 *
 * The bound cross-bridge force reads F = k·(site − head_tip), where the bound SITE on the filament segment is
 *     site = segCoord + (bindArc − ½·segLen)·segUVec
 * (the exact expression CrossBridgeSystem.bondForces computes inline). A cross-bridge+release SUB-STEP inner
 * loop advances the HEAD at a fine inner dt while the SITE belongs to the big system at the OUTER dt — so the
 * inner loop must treat the site as frozen / interpolated / co-stepped. WHICH one is faithful depends on how
 * far the site moves in one outer dt relative to the cross-bridge operating length. This tracker measures that
 * motion in a converged (fine-dt) run.
 *
 * For each WINDOW (W = round(outerDt/dt) steps) in which a motor stays CONTINUOUSLY bound to the SAME segment,
 * it records:
 *   - d_net   = |site(end) − site(start)|                         the net per-outer-dt site displacement
 *   - drift   = |LS-slope|·(W−1)                                  the DIRECTED (interpolable) component
 *               (least-squares straight-line fit of site(t) over the window — a smooth gliding site is all drift)
 *   - jitter  = RMS deviation of site(t) about that LS line       the DIFFUSIVE (un-interpolable) component
 * and aggregates the distribution (mean / p90 / max + a histogram) across all motor-windows, plus the
 * directed-vs-diffusive split (mean drift, mean jitter, fraction of windows with drift > jitter).
 *
 * NOT a system/kernel — plain host Java over the already-current host SoA arrays; no device work, no atomics,
 * no production-path change. Only instantiated when a harness is run with -substep (CPU runner).
 */
public final class SiteMotionTracker {

    final int W;            // window length in steps (= round(outerDt/dt)); needs W >= 4 for a meaningful LS fit
    final double dt;
    final double outerDt;
    final int nMotors;

    // ---- per-motor window state ----
    final int[]     seg0;   // boundSeg at window start; -1 ⇒ not tracking this window
    final boolean[] live;   // still continuously bound to seg0 through every step so far
    final double[]  x0, y0, z0;     // site at k=0 (µm)
    final double[]  xL, yL, zL;     // site at the most recent recorded sample (µm)
    // streaming least-squares sums per motor per coord (over samples k=0..W-1; the t-grid is shared)
    final double[]  sX, sY, sZ;     // Σ x
    final double[]  sXX, sYY, sZZ;  // Σ x²
    final double[]  sTX, sTY, sTZ;  // Σ k·x
    // shared t-grid sums (samples k = 0..W-1, identical for every motor finalized in the window)
    final double sumT, sumT2;
    final int    nSamp;

    // ---- aggregate distribution (nm) ----
    static final double BIN_NM = 1.0;      // 1 nm bins
    static final int    NBIN   = 600;      // 0..600 nm + overflow
    final long[] hNet  = new long[NBIN + 1];
    final long[] hDrift= new long[NBIN + 1];
    final long[] hJit  = new long[NBIN + 1];
    long   nWin;                            // # of finalized motor-windows
    double sumNet, sumNet2, maxNet;
    double sumDrift, sumDrift2, maxDrift;
    double sumJit, sumJit2, maxJit;
    long   directedWins;                    // # windows with drift > jitter

    public SiteMotionTracker(int nMotors, double dt, double outerDt) {
        this.nMotors = nMotors; this.dt = dt; this.outerDt = outerDt;
        this.W = (int) Math.max(1, Math.round(outerDt / dt));
        this.nSamp = W;
        // closed-form t-grid sums for k = 0..W-1
        this.sumT  = 0.5 * (W - 1.0) * W;
        this.sumT2 = (W - 1.0) * W * (2.0 * W - 1.0) / 6.0;
        seg0 = new int[nMotors]; live = new boolean[nMotors];
        x0 = new double[nMotors]; y0 = new double[nMotors]; z0 = new double[nMotors];
        xL = new double[nMotors]; yL = new double[nMotors]; zL = new double[nMotors];
        sX = new double[nMotors]; sY = new double[nMotors]; sZ = new double[nMotors];
        sXX = new double[nMotors]; sYY = new double[nMotors]; sZZ = new double[nMotors];
        sTX = new double[nMotors]; sTY = new double[nMotors]; sTZ = new double[nMotors];
        java.util.Arrays.fill(seg0, -1);
    }

    public boolean usable() { return W >= 4; }

    /** Call once per step (CPU runner, host arrays current). t = absolute step index. */
    public void observe(int t, IntArray boundSeg, FloatArray bindArc,
                        FloatArray filCoord, FloatArray filUVec, FloatArray filSegLength) {
        if (W < 4) return;
        int nSeg = filSegLength.getSize();
        int k = t % W;
        if (k == 0) {
            for (int m = 0; m < nMotors; m++) {
                int s = boundSeg.get(m);
                if (s >= 0) {
                    seg0[m] = s; live[m] = true;
                    double[] p = site(m, s, nSeg, bindArc, filCoord, filUVec, filSegLength);
                    x0[m] = p[0]; y0[m] = p[1]; z0[m] = p[2];
                    xL[m] = p[0]; yL[m] = p[1]; zL[m] = p[2];
                    sX[m] = p[0]; sY[m] = p[1]; sZ[m] = p[2];
                    sXX[m] = p[0]*p[0]; sYY[m] = p[1]*p[1]; sZZ[m] = p[2]*p[2];
                    sTX[m] = 0; sTY[m] = 0; sTZ[m] = 0;   // k=0 contributes 0 to Σk·x
                } else {
                    seg0[m] = -1; live[m] = false;
                }
            }
        } else {
            for (int m = 0; m < nMotors; m++) {
                if (!live[m]) continue;
                int s = boundSeg.get(m);
                if (s != seg0[m]) { live[m] = false; continue; }   // unbound or rebound elsewhere ⇒ drop this window
                double[] p = site(m, s, nSeg, bindArc, filCoord, filUVec, filSegLength);
                xL[m] = p[0]; yL[m] = p[1]; zL[m] = p[2];
                sX[m] += p[0]; sY[m] += p[1]; sZ[m] += p[2];
                sXX[m] += p[0]*p[0]; sYY[m] += p[1]*p[1]; sZZ[m] += p[2]*p[2];
                sTX[m] += k*p[0]; sTY[m] += k*p[1]; sTZ[m] += k*p[2];
            }
            if (k == W - 1) finalizeWindow();
        }
    }

    private double[] site(int m, int s, int nSeg, FloatArray bindArc, FloatArray filCoord,
                          FloatArray filUVec, FloatArray filSegLength) {
        double slen = filSegLength.get(s);
        double aOff = bindArc.get(m) - 0.5 * slen;
        return new double[]{
            filCoord.get(s)            + aOff * filUVec.get(s),
            filCoord.get(nSeg + s)     + aOff * filUVec.get(nSeg + s),
            filCoord.get(2 * nSeg + s) + aOff * filUVec.get(2 * nSeg + s)
        };
    }

    private void finalizeWindow() {
        double n = nSamp, denom = sumT2 - sumT * sumT / n;
        if (denom <= 0) return;
        for (int m = 0; m < nMotors; m++) {
            if (!live[m]) continue;
            // net displacement (µm)
            double dnx = xL[m] - x0[m], dny = yL[m] - y0[m], dnz = zL[m] - z0[m];
            double dNet = Math.sqrt(dnx*dnx + dny*dny + dnz*dnz) * 1.0e3;   // → nm
            // least-squares slope b per coord; drift = |b|·(W-1); residual variance about the line
            double bx = (sTX[m] - sumT * sX[m] / n) / denom;
            double by = (sTY[m] - sumT * sY[m] / n) / denom;
            double bz = (sTZ[m] - sumT * sZ[m] / n) / denom;
            double ax = (sX[m] - bx * sumT) / n, ay = (sY[m] - by * sumT) / n, az = (sZ[m] - bz * sumT) / n;
            double drift = Math.sqrt(bx*bx + by*by + bz*bz) * (W - 1.0) * 1.0e3;   // → nm
            double rvX = (sXX[m] - ax*sX[m] - bx*sTX[m]) / n;
            double rvY = (sYY[m] - ay*sY[m] - by*sTY[m]) / n;
            double rvZ = (sZZ[m] - az*sZ[m] - bz*sTZ[m]) / n;
            double jit = Math.sqrt(Math.max(0.0, rvX + rvY + rvZ)) * 1.0e3;        // → nm (3D RMS)
            accum(dNet, drift, jit);
        }
    }

    private void accum(double dNet, double drift, double jit) {
        nWin++;
        sumNet += dNet; sumNet2 += dNet*dNet; if (dNet > maxNet) maxNet = dNet;
        sumDrift += drift; sumDrift2 += drift*drift; if (drift > maxDrift) maxDrift = drift;
        sumJit += jit; sumJit2 += jit*jit; if (jit > maxJit) maxJit = jit;
        if (drift > jit) directedWins++;
        hNet[bin(dNet)]++; hDrift[bin(drift)]++; hJit[bin(jit)]++;
    }

    private static int bin(double nm) { int b = (int) (nm / BIN_NM); return b >= NBIN ? NBIN : b; }

    private double pct(long[] h, double p) {
        if (nWin == 0) return 0;
        long target = (long) Math.ceil(p * nWin); long acc = 0;
        for (int b = 0; b <= NBIN; b++) { acc += h[b]; if (acc >= target) return b * BIN_NM; }
        return NBIN * BIN_NM;
    }

    public long windows() { return nWin; }
    public double meanNet()  { return nWin == 0 ? 0 : sumNet / nWin; }
    public double p90Net()   { return pct(hNet, 0.90); }
    public double maxNet()   { return maxNet; }
    public double meanDrift(){ return nWin == 0 ? 0 : sumDrift / nWin; }
    public double meanJit()  { return nWin == 0 ? 0 : sumJit / nWin; }
    public double directedFrac() { return nWin == 0 ? 0 : (double) directedWins / nWin; }

    /** Formatted multi-line report block (nm units). meanStretchNm + tolNm are passed by the harness for the
     *  comparison to the cross-bridge operating length and myoColTol. */
    public String summary(double meanStretchNm, double tolNm) {
        if (nWin == 0) return String.format(java.util.Locale.US,
            "  SITE-MOTION: no completed bound windows (W=%d steps = %.0e s outer dt) — too few continuously-bound motors%n", W, outerDt);
        double mn = meanNet(), p90 = p90Net(), mx = maxNet(), md = meanDrift(), mj = meanJit();
        String tier;
        double ratioToStretch = mn / Math.max(1e-9, meanStretchNm);
        boolean directed = md > mj;
        if (ratioToStretch < 0.15) tier = "FROZEN-SITE faithful (site motion ≪ stretch ⇒ freezing barely changes the force)";
        else if (directed)         tier = "INTERPOLATED-SITE (site motion comparable but DIRECTED ⇒ a linear predictor captures it)";
        else                       tier = "CO-STEPPED neighbourhood (site motion large and DIFFUSIVE ⇒ approaches the coupled solve)";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.US,
            "  SITE-MOTION over %d bound motor-windows (W=%d steps = %.0e s outer dt):%n", nWin, W, outerDt));
        sb.append(String.format(java.util.Locale.US,
            "    net |Δsite|/outer-dt:  mean=%.3f nm  p90=%.3f nm  max=%.3f nm%n", mn, p90, mx));
        sb.append(String.format(java.util.Locale.US,
            "    decomposition:         drift(directed)=%.3f nm   jitter(diffusive RMS)=%.3f nm   drift/jitter=%.2f   directedWinFrac=%.3f%n",
            md, mj, mj > 0 ? md / mj : 0, directedFrac()));
        sb.append(String.format(java.util.Locale.US,
            "    vs operating length:   mean|stretch|=%.3f nm  (net/stretch=%.3f)   myoColTol=%.1f nm  (net/tol=%.3f)%n",
            meanStretchNm, ratioToStretch, tolNm, mn / Math.max(1e-9, tolNm)));
        sb.append(String.format(java.util.Locale.US,
            "    VERDICT (site-handling tier): %s%n", tier));
        return sb.toString();
    }
}
