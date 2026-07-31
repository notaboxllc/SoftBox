package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * NOISE-DECOMPOSED RIGID-BODY AXIAL ROTATION (diagnostic; default-off, read-only, RNG-inert).
 *
 * <h2>Why this exists</h2>
 * The FDT-correct rigid filament carries the full kT reservoir on axial roll, so {@code D_roll = kT/gamma_roll}
 * is large and the realized total angle is a random walk that can hide a real motor-generated drift over any
 * feasible window. This class separates, along the ACTUAL fully thermalized trajectory, the deterministic
 * (motor-driven) angular increment from the independently sampled Brownian increment. Nothing is replayed with
 * noise deleted and no forcing is removed: both increments are read as they are evaluated, step by step.
 *
 * <h2>The exact relation, from the integrator itself</h2>
 * {@link RigidRodLangevinIntegrationSystem#integrate} forms, per body, the BODY-FRAME axial angular velocity
 * <pre>
 *   btx  = (R^T . torqueSum)_x + randTorque_x        [ R = (u, y, z), so (R^T . T)_x = T . u ]
 *   bwx  = btx / bRotGam_x
 * </pre>
 * and applies it to the material frame as {@code yTransInZ = bwx*dt}. So AT THE ANGULAR-VELOCITY LEVEL the
 * split is EXACT and additive:
 * <pre>
 *   dPhiDrive = dt * (torqueSum . u) / gamma_roll     motor-driven
 *   dPhiBrown = dt * randTorque_x    / gamma_roll     Brownian (randTorque is ALREADY body-frame)
 *   dPhiBody  = dPhiDrive + dPhiBrown                 exactly, by construction
 * </pre>
 *
 * <h2>Why a geometric term is REQUIRED, not a fudge</h2>
 * The REALIZED material roll is not {@code dPhiBody}. The orientation update rotates the frame by all three
 * body-frame components at once and then renormalises, and finite rotations do not commute, so the roll
 * actually accumulated on the material frame — the quantity {@code rollIncrementTransported} measures by
 * parallel transport, and the quantity the twirling observable is built from — differs from {@code dPhiBody}
 * by a genuine O(dt^2) noncommutativity + renormalisation term. Forcing a scalar additive identity here would
 * be false. The residual is therefore identified and reported explicitly:
 * <pre>
 *   dPhiGeom = dPhiTotal - dPhiDrive - dPhiBrown
 * </pre>
 * with {@code dPhiTotal} the realized parallel-transported roll. The closure gate validates the relation that
 * IS mathematically true (the body-frame additive split, to float32), and reports {@code dPhiGeom} as a
 * measured physical-integration term rather than hiding it inside a tolerance.
 *
 * <h2>Exactness</h2>
 * The deterministic and Brownian increments are computed from the SAME arrays the integrator consumes
 * ({@code torqueSum}, {@code randTorque}, {@code bRotGam}) and the SAME pre-step material frame, so they are
 * the integrator's own values, not an approximate diagnostic torque. The pre-step frame matters: {@code
 * torqueSum} is a LAB-frame vector and the integrator projects it with the frame as it stood BEFORE the update,
 * so the caller must supply that frame (see {@link #accumulate}).
 *
 * <p>This class only reads. It never writes simulation state, never draws a random number, and never changes a
 * force, a rate or a trajectory.
 */
public final class RigidRollDecomposition {

    /** Sums over the measurement window (radians). */
    public double phiDrive, phiBrown, phiGeom, phiTotal;
    /** Deterministic axial torque, summed over steps (N.m) — divide by nSteps for the mean. */
    public double tauDetSum;
    /** Component decomposition of the deterministic axial torque (N.m, summed over steps). */
    public double tauBondSum, tauOtherSum;
    /** Body-frame closure diagnostics: dPhiBody vs dPhiDrive + dPhiBrown. */
    public double maxBodyResid, sumSqBodyResid, accBodyResid, sumAbsUpdate;
    public long nSteps;

    private final double gammaRoll, dt;

    /**
     * @param gammaRoll the integrator's own axial rotational drag, {@code bRotGam[0]} (N.m.s)
     * @param dt        the stepping dt actually used (s)
     */
    public RigidRollDecomposition(double gammaRoll, double dt) {
        this.gammaRoll = gammaRoll; this.dt = dt;
    }

    /** Axial mobility used by the integrator: {@code M_roll = 1/gamma_roll}. */
    public double mRoll() { return 1.0 / gammaRoll; }

    /**
     * Accumulate ONE step.
     *
     * @param torqueSum   the filament's lab-frame deterministic torque accumulator, exactly as the integrator
     *                    consumed it this step (planar SoA, n = 1 ⇒ indices 0,1,2)
     * @param randTorque  the body-frame Brownian torque, exactly as the integrator consumed it this step
     * @param ux,uy,uz    the material axis BEFORE this step's update (the frame the integrator projected with)
     * @param dPhiTotal   the realized parallel-transported roll increment for this step
     * @param tauBond     the summed per-head bond-moment axial torque for this step (component check), or NaN
     */
    public void accumulate(FloatArray torqueSum, FloatArray randTorque,
                           double ux, double uy, double uz, double dPhiTotal, double tauBond) {
        double tauDet = torqueSum.get(0)*ux + torqueSum.get(1)*uy + torqueSum.get(2)*uz;
        double dDrive = dt * tauDet / gammaRoll;
        double dBrown = dt * randTorque.get(0) / gammaRoll;
        double dBody  = dt * (tauDet + randTorque.get(0)) / gammaRoll;

        // the relation that is mathematically exact: the body-frame axial split
        double resid = dBody - (dDrive + dBrown);
        double a = Math.abs(resid);
        if (a > maxBodyResid) maxBodyResid = a;
        sumSqBodyResid += resid*resid; accBodyResid += resid;
        sumAbsUpdate += Math.abs(dBody);

        phiDrive += dDrive; phiBrown += dBrown; phiTotal += dPhiTotal;
        phiGeom  += dPhiTotal - dDrive - dBrown;     // the honest remainder, not a tolerance
        tauDetSum += tauDet;
        if (!Double.isNaN(tauBond)) { tauBondSum += tauBond; tauOtherSum += tauDet - tauBond; }
        nSteps++;
    }

    public double meanTauDet()  { return nSteps > 0 ? tauDetSum  / nSteps : 0; }
    public double meanTauBond() { return nSteps > 0 ? tauBondSum / nSteps : 0; }
    public double meanTauOther(){ return nSteps > 0 ? tauOtherSum/ nSteps : 0; }
    public double omegaDrive()  { return nSteps > 0 ? phiDrive / (nSteps*dt) : 0; }
    public double omegaBrown()  { return nSteps > 0 ? phiBrown / (nSteps*dt) : 0; }
    public double omegaGeom()   { return nSteps > 0 ? phiGeom  / (nSteps*dt) : 0; }
    public double omegaTotal()  { return nSteps > 0 ? phiTotal / (nSteps*dt) : 0; }
    /** The torque-drift closure target: M_roll * mean(tau_det). Must equal omegaDrive() for constant mobility. */
    public double omegaFromMeanTorque() { return mRoll() * meanTauDet(); }
    public double rmsBodyResid() { return nSteps > 0 ? Math.sqrt(sumSqBodyResid / nSteps) : 0; }
    /** Accumulated body-frame residual relative to the total absolute angular update. */
    public double relBodyResid() { return sumAbsUpdate > 0 ? Math.abs(accBodyResid) / sumAbsUpdate : 0; }
}
