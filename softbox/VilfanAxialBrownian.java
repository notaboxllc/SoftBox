package softbox;

/**
 * FDT-CONSISTENT AXIAL BROWNIAN MOTION for the Vilfan overdamped reference.
 * <p>
 * Adds exactly one ingredient to the validated deterministic finite-drag system: a thermal force on
 * the filament's axial coordinate X, at fluctuation-dissipation consistency with the SAME whole-
 * filament axial drag {@code gammaX} the drag study audited. Roll stays deterministic.
 * <pre>
 *   gammaX dX/dt = sum_j F_j + sqrt(2 kBT gammaX) xi(t)
 *   <=>  dX = [sum_j F_j / gammaX] dt + sqrt(2 DX) dW ,   DX = kBT / gammaX
 * </pre>
 * For a fixed bound set the drift is linear, {@code sum_j F_j = Nb K (Xeq - X)}, so X is an exact
 * Ornstein-Uhlenbeck process and is propagated with the EXACT finite-time transition — no
 * discretisation error in the marginal law at any step size:
 * <pre>
 *   X(t+dt) = Xeq + [X(t)-Xeq] exp(-dt/tauX) + sqrt[ kBT/(Nb K) (1 - exp(-2 dt/tauX)) ] Z
 *   tauX = gammaX/(Nb K) ,  Var_inf = kBT/(Nb K)
 * </pre>
 * With no bound head (or K = 0) the drift vanishes and X is free Brownian motion,
 * {@code X(t+dt) = X(t) + sqrt(2 DX dt) Z}.
 * <p>
 * <b>Counter-based RNG.</b> Draws are addressed by (seed, stream, index) through a splitmix64 hash
 * rather than by sequence position. That makes Brownian-bridge refinement possible without
 * redrawing independent noise: inserting a midpoint uses its own address, and the surrounding
 * anchor draws are untouched. It also makes the common-noise mirror pairing exact by construction —
 * a mirrored arm addresses the identical stream and therefore realises the identical axial path.
 */
public final class VilfanAxialBrownian {
    private VilfanAxialBrownian() {}

    /* ===================== counter-based Gaussian ===================== */

    public static final int STREAM_AXIAL = 0x41584941;   // "AXIA"
    public static final int STREAM_BRIDGE = 0x42524447;  // "BRDG"

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
    /** splitmix64 of the (seed, stream, index) address; independent for distinct addresses. */
    public static long hash(long seed, long stream, long index) {
        return mix(mix(seed * 0x9E3779B97F4A7C15L + stream) + index * 0xD1342543DE82EF95L);
    }
    private static double u01(long h) {   // (0,1)
        double u = ((h >>> 11) + 0.5) * 0x1.0p-53;
        return u;
    }
    /** standard normal, addressed. Box-Muller from two independent hashes of the same address. */
    public static double gauss(long seed, long stream, long index) {
        double u1 = u01(hash(seed, stream, 2 * index));
        double u2 = u01(hash(seed, stream, 2 * index + 1));
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    /* ===================== exact OU / free-diffusion transitions ===================== */

    /** exact OU finite-time transition. {@code varInf = kBT/(Nb K)}, {@code tau = gammaX/(Nb K)}. */
    public static double ouStep(double xEq, double x0, double tau, double varInf, double dt, double z) {
        if (!(tau > 0) || !Double.isFinite(tau)) return x0;           // no relaxation configured
        double e = Math.exp(-dt / tau);
        return xEq + (x0 - xEq) * e + Math.sqrt(varInf * (1.0 - e * e)) * z;
    }

    /** free axial diffusion (no bound head, or zero axial stiffness). */
    public static double freeStep(double x0, double dX, double dt, double z) {
        return x0 + Math.sqrt(2.0 * dX * dt) * z;
    }

    /**
     * Exact OU bridge midpoint: the law of X(T/2) conditioned on X(0)=xa and X(T)=xb.
     * <p>
     * With {@code a = exp(-T/(2 tau))} the conditional mean is
     * {@code xEq + a (xa + xb - 2 xEq)/(1 + a^2)} and the conditional variance is
     * {@code varInf (1 - a^2)/(1 + a^2)}. Refining a step therefore never redraws the endpoints:
     * the coarse and refined trajectories are the SAME stochastic path.
     */
    public static double ouBridgeMid(double xEq, double xa, double xb, double tau, double varInf,
                                     double T, double z) {
        if (!(tau > 0) || !Double.isFinite(tau)) return 0.5 * (xa + xb);
        double a = Math.exp(-T / (2.0 * tau));
        double mean = xEq + a * ((xa - xEq) + (xb - xEq)) / (1.0 + a * a);
        double var = varInf * (1.0 - a * a) / (1.0 + a * a);
        return mean + Math.sqrt(Math.max(0.0, var)) * z;
    }

    /** free Brownian bridge midpoint: mean (xa+xb)/2, variance DX*T/2. */
    public static double freeBridgeMid(double xa, double xb, double dX, double T, double z) {
        return 0.5 * (xa + xb) + Math.sqrt(Math.max(0.0, dX * T * 0.5)) * z;
    }

    /* ===================== Stage-0 audit ===================== */

    public static double diffusionNm2PerS(double kBT, double gammaX) { return kBT / gammaX; }

    public static String audit(double kBT, double gammaX, double K, double meanInterEventS,
                               double zonePassageS, double kD, double medianNb, double lowerDecileNb) {
        double DX = diffusionNm2PerS(kBT, gammaX);
        StringBuilder b = new StringBuilder();
        b.append(String.format("  gammaX = %.6g pN*s/nm   kBT = %.4g pN*nm%n", gammaX, kBT));
        b.append(String.format("  DX = kBT/gammaX = %.6g nm^2/s = %.6g um^2/s%n%n", DX, DX / 1e6));
        b.append("  FREE-filament diffusion, RMS displacement sqrt(2 DX t):\n");
        double[][] ts = {
            {1e-6, 0}, {1e-5, 0}, {1e-4, 0}, {1e-3, 0},
            {meanInterEventS, 1}, {1.0 / kD, 2}, {zonePassageS, 3}};
        String[] lbl = {"", "mean chemical inter-event interval", "1/kD (bound-head lifetime)", "target-zone passage"};
        for (double[] tt : ts) {
            double t = tt[0];
            b.append(String.format("    t = %-10.4g s   sqrt(2 DX t) = %9.4g nm   %s%n",
                    t, Math.sqrt(2 * DX * t), lbl[(int) tt[1]]));
        }
        b.append("\n  These are FREE-filament values. They are NOT the production fluctuation while\n");
        b.append("  motors are bound: the axial springs confine X to an OU process whose stationary\n");
        b.append("  width is set by the bound-head count, below.\n\n");
        b.append("  MOTOR-CONSTRAINED axial fluctuation, Var(X) = kBT/(Nb K), tauX = gammaX/(Nb K):\n");
        double[] nbs = {1, 2, 5, 10, 25, 50, medianNb, lowerDecileNb};
        String[] nlb = {"", "", "", "", "", "", "  <- production median Nb", "  <- production lower-decile Nb"};
        for (int i = 0; i < nbs.length; i++) {
            double nb = nbs[i];
            if (nb <= 0) continue;
            double var = kBT / (nb * K), tau = gammaX / (nb * K);
            b.append(String.format("    Nb = %-6.0f  sd(X) = %7.4f nm   tauX = %9.4g s%s%n",
                    nb, Math.sqrt(var), tau, nlb[i]));
        }
        return b.toString();
    }
}
