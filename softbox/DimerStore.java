package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * The SoA store for myosin DIMERS (increment 6a) — the FIRST myosin-structure assembly.
 *
 * A dimer is a 1:1 STRUCTURAL pairing of two articulated motors (v1 MyosinDimer: myo1, myo2
 * — two full rod→lever→head Myosins joined by inter-rod coupling springs + a lever-alignment
 * torque). It owns NO body arrays of its own; its two motors live in a MotorStore and the
 * dimer just names them by integer slot (motorA, motorB) + a parallel/antiparallel flag.
 *
 * The coupling is the SIMPLEST of the three myosin-structure couplings (recon §2): because a
 * motor (hence each of its rod/lever sub-bodies) belongs to EXACTLY ONE dimer, the dimer
 * computes its reaction once and SELF-WRITES both sides directly into its two uniquely-owned
 * rod/lever sub-body slots — no CSR gather (contrast the motor→segment single-ended gather and
 * the crosslinker two-pass). Race-free with no atomics PROVIDED the pairing is disjoint
 * (motorA(d)=2d, motorB(d)=2d+1 ⇒ each sub-body has one writer). DimerCouplingSystem enforces
 * this only by construction; the harness builds the disjoint pairing.
 *
 * THIS INCREMENT (6a): pre-placed, STATIC assembly (no runtime formation/dissolution), heads
 * FREE (no filament / cross-bridge / glide). The shared rigid-rod systems + the per-motor J1/J2
 * joints run over MotorStore.body UNCHANGED; only DimerCouplingSystem is dimer-specific.
 */
public final class DimerStore {

    public final int nDimers;

    // ---- The 1:1 motor pairing (integer slots into a MotorStore) ----
    public final IntArray motorA;     // nDimers
    public final IntArray motorB;     // nDimers
    public final IntArray parallel;   // nDimers; 1 = parallel (End1+End2 + lever-align), 0 = antiparallel (End1End2+End2End1)

    // dimerParams (float): [0]=dt [1]=rodFracMove (v1 myoDimerFracMove) [2]=leverFracMoveTorq
    //   (v1 myoDimerLeverFracMoveTorq) [3]=leverAngleDeg (v1 leverAngle) [4]=rodLenUm (v1 myoRodLength).
    public final FloatArray dimerParams;

    // ---- v1 defaults (Env.java / MyosinDimer.java; verified BoA-v1ref 2026-06-17) ----
    public static final double ROD_FRAC_MOVE        = 0.2;    // Env.java:165 myoDimerFracMove
    public static final double LEVER_FRAC_MOVE_TORQ = 0.4;    // Env.java:161 myoDimerLeverFracMoveTorq
    public static final double LEVER_ANGLE_DEG      = 160.0;  // MyosinDimer.java:9 leverAngle
    public static final double ROD_LEN_UM           = MotorStore.ROD_LEN;  // 0.080 (Env.java:776)

    public DimerStore(int nDimers) {
        this.nDimers = nDimers;
        motorA   = new IntArray(nDimers);
        motorB   = new IntArray(nDimers);
        parallel = new IntArray(nDimers);
        dimerParams = new FloatArray(5);
        motorA.init(0); motorB.init(0); parallel.init(1);
    }

    /** Disjoint 1:1 pairing: dimer d couples motors 2d and 2d+1 (so each sub-body has a single
     *  writer ⇒ the direct two-slot self-write is race-free). par=true ⇒ parallel mode. */
    public void pair(int d, int mA, int mB, boolean par) {
        motorA.set(d, mA); motorB.set(d, mB); parallel.set(d, par ? 1 : 0);
    }

    public void setDimerParams(double dt) {
        dimerParams.set(0, (float) dt);
        dimerParams.set(1, (float) ROD_FRAC_MOVE);
        dimerParams.set(2, (float) LEVER_FRAC_MOVE_TORQ);
        dimerParams.set(3, (float) LEVER_ANGLE_DEG);
        dimerParams.set(4, (float) ROD_LEN_UM);
    }
}
