package softbox;

/**
 * Scale-aware angular level-crossing counter (Schmitt trigger) for unwrapped filament roll.
 * <p>
 * Replaces the defective first implementation, which advanced its reference at every accepted
 * substep and therefore counted diffusive DIRECTION REVERSALS of an OU path — a quantity that grows
 * without bound as the anchor step shrinks and carries no mechanistic meaning.
 * <p>
 * Here the reference level is FIXED between confirmations. A crossing is confirmed only once the
 * unwrapped angle has moved at least half a band beyond it; on confirmation the reference advances
 * by exactly one band in the direction of travel. Consequences, all of which are gated as fixtures:
 * a sub-band oscillation counts nothing; a monotone drift of D counts floor(D/band) crossings; a
 * reversal deeper than half a band counts one backward crossing; and the total is independent of
 * the stochastic step size.
 * <p>
 * Band 0 is a raw direction-reversal count, retained ONLY to exhibit the jitter it is contaminated
 * by; it is never used mechanistically.
 */
public final class VilfanRollBands {
    public final double[] bands;
    public final long[] fwd, bwd;
    private final double[] ref;
    private final int[] side;
    private boolean started = false;

    public VilfanRollBands(double[] bands) {
        this.bands = bands.clone();
        this.fwd = new long[bands.length]; this.bwd = new long[bands.length];
        this.ref = new double[bands.length]; this.side = new int[bands.length];
    }

    /** seed every reference at the initial angle so counting starts from a defined state. */
    public void start(double theta0) {
        for (int b = 0; b < bands.length; b++) { ref[b] = theta0; side[b] = 0; }
        started = true;
    }

    public void update(double theta) {
        if (!started) start(theta);
        for (int b = 0; b < bands.length; b++) {
            double band = bands[b];
            if (band <= 0.0) {                                   // raw jitter row
                double d = theta - ref[b];
                int s = d > 0 ? +1 : (d < 0 ? -1 : 0);
                if (s != 0) {
                    if (side[b] != 0 && s != side[b]) { if (s > 0) fwd[b]++; else bwd[b]++; }
                    side[b] = s;
                }
                ref[b] = theta;
                continue;
            }
            // FULL-BAND quantiser, in closed form.
            //
            // Two earlier attempts were wrong and both were caught by fixture A7:
            //   (i) a `while (|theta-ref| >= band/2) ref += sign*band;` loop does not terminate at
            //       |theta-ref| == band/2 exactly (it oscillates across the dead-zone edge forever);
            //   (ii) a half-band threshold (confirm at band/2, advance by band) OVER-counts and is
            //       resolution-DEPENDENT: after advancing, the residual can sit just inside the dead
            //       zone, so an arbitrarily small further move re-triggers. Measured counts then grew
            //       as sqrt(1/dt) and sat ~22x above the continuum value 2*D*T/band^2.
            //
            // The resolution-independent statistic is the number of FULL-band net displacements from
            // the last confirmed level. Confirmation therefore requires |theta-ref| >= band, and the
            // reference advances by exactly the whole number of bands traversed.
            double d = theta - ref[b];
            long k = (long) Math.floor(Math.abs(d) / band);
            if (k == 0) continue;                                // less than one full band: nothing
            int s = d > 0 ? +1 : -1;
            if (s > 0) fwd[b] += k; else bwd[b] += k;
            side[b] = s;
            ref[b] += s * k * band;
            // residual now lies in [0, band), so a further full band is required to re-trigger
        }
    }
    public long total(int b) { return fwd[b] + bwd[b]; }
}
