package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * The SoA store for myosin MINIFILAMENTS (increment 6b) — a rigid-rod backbone that OWNS N dimers.
 *
 * v1 MyoMiniFilament: a rigid-rod body (length 0.180 µm, radius 0.005 µm) carrying numMyoDimersEachEnd
 * dimers at EACH end (default 8 ⇒ 16 dimers / 32 heads), each dimer's myo1.myoRod tethered to a
 * body-local attachment point near the backbone end (the offset is PURELY AXIAL — y=z=0 in the body
 * frame, makeMyosinDimers:393-424 ⇒ attach = backbone.coord + axial·backbone.uVec).
 *
 * The backbone is the THIRD RigidRodBody instance (FilamentStore #1, MotorStore #2): the shared
 * BrownianForceSystem / RigidRodLangevinIntegrationSystem / DerivedGeometrySystem / DragTensorSystem
 * run over `backbone` UNCHANGED. The dimers live in a separate MotorStore (each dimer = a 6a DimerStore
 * pairing); this store only NAMES them by integer slot.
 *
 * Coupling = SINGLE-ENDED, one pass (recon §2, the central favorable finding): the backbone OWNS its
 * dimers (one consumer, many writers, each dimer keyed to exactly one backbone) — the same shape as
 * motor→segment (boundSeg), NOT the crosslinker double-ended two-pass. Each dimer self-writes the
 * rod-side tether reaction into its own rod slot and stores the backbone-side reaction in miniData;
 * the backbone gathers via the CrossBridge CSR-inverse run ONCE, keyed by headBackboneSlot.
 *
 * THIS INCREMENT (6b): pre-placed, STATIC assembly (fixed N; no dynamic assembly / myoMiniLifetime),
 * heads FREE (no cross-bridge / glide). Couple via integer slots, never class identity.
 */
public final class MiniFilamentStore {

    public final int nBackbones;
    public final int nDimers;       // total dimers (children) across all backbones

    // v1 geometry (MyoMiniFilament.java:23-25; Env.java:371)
    public static final double BACKBONE_LEN = 0.180, BACKBONE_R = 0.005, HEAD_ZONE = 0.05;  // µm
    public static final int    DIMERS_EACH_END = 8;        // numMyoDimersEachEndOfMiniFil
    // v1 PAIRS coeffs (Env.java:167-175)
    public static final double MINIFIL_FRAC_MOVE = 0.07;   // myoMiniFilFracMove (rod↔backbone tether)
    public static final double MINIFIL_ALIGN     = 0.01;   // myoMiniFilAlign     (rod→backbone-axis torque)

    // ---- The backbone (3rd RigidRodBody instance) ----
    public final RigidRodBody backbone;
    public final FloatArray bbBodyParams;   // [0]=dt [1]=sqrt(2kT/dt) (shared Brownian/integration)
    public final IntArray   bbCounts;       // [1]=stepCount [2]=runSeed (distinct seed from the motors)

    // ---- Per-dimer (child) coupling, size nDimers ----
    public final IntArray   headBackboneSlot;  // which backbone this dimer attaches to (the CSR key)
    public final IntArray   motorA;            // the dimer's myo1 motor slot (tethered rod = 3*motorA)
    public final FloatArray attachAxial;       // signed axial offset (µm) along backbone uVec (<0 end1, >0 end2)
    // backbone-side reaction stored per dimer for the gather: [6*d..]=force(3)+torque(3)
    public final FloatArray miniData;          // 6*nDimers
    public static final int MINI_STRIDE = 6;

    // ---- Precomputed backbone perpendicular inverse-drag (for the tether denominators) ----
    // [bb] = 1/bTransGam.y ; [nBb+bb] = 1/bRotGam.y   (static — drag set at init)
    public final FloatArray bbInvDragY;        // 2*nBackbones

    // ---- CSR-inverse scratch (backbone→dimers), reusing CrossBridge.csr* ----
    public final IntArray   bbDimerCount;      // nBackbones
    public final IntArray   bbDimerOffsets;    // nBackbones+1
    public final IntArray   bbDimerList;       // nDimers
    // miniCounts mimics MotorStore.counts for the reused CSR build: [0]=nDimers, [3]=nBackbones.
    public final IntArray   miniCounts;        // 4

    // miniParams (float): [0]=dt [1]=miniFilFracMove [2]=miniFilAlign
    public final FloatArray miniParams;

    public MiniFilamentStore(int nBackbones, int nDimers) {
        this.nBackbones = nBackbones;
        this.nDimers = nDimers;
        backbone = new RigidRodBody(nBackbones);
        bbBodyParams = new FloatArray(2);
        bbCounts = new IntArray(4);
        headBackboneSlot = new IntArray(nDimers);
        motorA = new IntArray(nDimers);
        attachAxial = new FloatArray(nDimers);
        miniData = new FloatArray(MINI_STRIDE * nDimers);
        bbInvDragY = new FloatArray(2 * nBackbones);
        bbDimerCount = new IntArray(nBackbones);
        bbDimerOffsets = new IntArray(nBackbones + 1);
        bbDimerList = new IntArray(nDimers);
        miniCounts = new IntArray(4);
        miniParams = new FloatArray(3);

        headBackboneSlot.init(0); motorA.init(0); attachAxial.init(0f);
        miniData.init(0f); bbInvDragY.init(0f);
        bbDimerCount.init(0); bbDimerOffsets.init(0); bbDimerList.init(0);
        miniCounts.set(0, nDimers); miniCounts.set(3, nBackbones);
    }

    public int bbIdx(int bb) { return bb; }   // backbone sub-body flat index (one rod per backbone)

    /** Register dimer `d` (child): attaches to backbone `bb` at signed axial offset, myo1 motor = mA. */
    public void attach(int d, int bb, int mA, double axialUm) {
        headBackboneSlot.set(d, bb);
        motorA.set(d, mA);
        attachAxial.set(d, (float) axialUm);
    }

    public void setMiniParams(double dt) {
        miniParams.set(0, (float) dt);
        miniParams.set(1, (float) MINIFIL_FRAC_MOVE);
        miniParams.set(2, (float) MINIFIL_ALIGN);
    }

    public void setBackboneParams(double dt) {
        bbBodyParams.set(0, (float) dt);
        bbBodyParams.set(1, (float) Math.sqrt(2.0 * Constants.kT / dt));
    }

    public void setBackboneCounts(int stepCount, int runSeed) {
        bbCounts.set(0, nBackbones);
        bbCounts.set(1, stepCount);
        bbCounts.set(2, runSeed);
        bbCounts.set(3, nBackbones);
    }

    /** Rod (slender-body) drag for every backbone (length 0.180, radius 0.005) — the shared rod-drag
     *  formula (DragTensorSystem.rodDragSI, public), set directly into the backbone arrays + the
     *  precomputed perpendicular inverse-drag for the tether denominators. Existing DragTensorSystem
     *  is left byte-unchanged (this replicates its storeDrag inline for the new body). */
    public void initBackboneDrag() {
        double kT = Constants.kT;
        double[] g = DragTensorSystem.rodDragSI(BACKBONE_LEN, BACKBONE_R);   // {bTGx,bTGy,bTGz,bRGx,bRGy,bRGz}
        for (int bb = 0; bb < nBackbones; bb++) {
            int x = backbone.planeX(bb), y = backbone.planeY(bb), z = backbone.planeZ(bb);
            backbone.segLength.set(bb, (float) BACKBONE_LEN);
            backbone.bTransGam.set(x, (float) g[0]); backbone.bTransGam.set(y, (float) g[1]); backbone.bTransGam.set(z, (float) g[2]);
            backbone.bRotGam.set(x,   (float) g[3]); backbone.bRotGam.set(y,   (float) g[4]); backbone.bRotGam.set(z,   (float) g[5]);
            backbone.bTransDiff.set(x, (float) (kT/g[0])); backbone.bTransDiff.set(y, (float) (kT/g[1])); backbone.bTransDiff.set(z, (float) (kT/g[2]));
            backbone.bRotDiff.set(x,   (float) (kT/g[3])); backbone.bRotDiff.set(y,   (float) (kT/g[4])); backbone.bRotDiff.set(z,   (float) (kT/g[5]));
            bbInvDragY.set(bb,               (float) (1.0 / g[1]));   // 1/bTransGam.y
            bbInvDragY.set(nBackbones + bb,  (float) (1.0 / g[4]));   // 1/bRotGam.y
        }
    }
}
