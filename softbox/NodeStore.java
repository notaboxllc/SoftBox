package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * The SoA store for PROTEIN NODES (increment 6c, Stage A — the node ENTITY as a motor-bundle).
 *
 * A node is a rigid SPHERE-with-orientation (v1 ProteinNode extends Thing) that OWNS singlet myosins
 * AND myosin dimers tethered RADIALLY to its surface. Mechanically the node is the 4th RigidRodBody
 * instance (FilamentStore #1, MotorStore #2, MiniFilament backbone #3) — a body with isotropic SPHERE
 * drag and zero length. The shared Brownian / integration / derive / containment systems run over
 * `node` UNCHANGED; only the radial surface tether is node-specific (NodeSystem).
 *
 * The recon (INC6_NODE_RECON.md §1a) confirmed: the node mechanism IS the minifilament's — a rigid
 * body owning motor-children via a fracMove damping-limited tether + a backbone-side SINGLE-ENDED
 * gather. The ONLY differences are GEOMETRY (radial sphere-surface splay vs the minifilament's two
 * axial end-clusters) and that the node ALSO carries singlet myosins. Both are *placement*: this store
 * names the children by integer slot + a body-frame radial offset; the gather reuses the CrossBridge
 * CSR-inverse + MiniFilamentSystem.backboneGather VERBATIM (keyed by attachNode).
 *
 * v1 node tether (ProteinNode.keepMyosinsOnSurface / keepMyosinDimersOnSurface, BoA-v1ref):
 *   F = coeff·1e-6·strain / (dt·(1/myoRod.bTransGam.y + 1/node.bTransGam.y)), toward the surface point,
 *   measured from the myoRod END1. Two faithful node-specific differences from the minifilament tether:
 *     - SINGLET: coeff = attnForce/numNodeMyos (0.4/nSing); force applied at the rod CENTER (NO torque),
 *       node reaction at the node CENTER (no node torque).
 *     - DIMER (myo1's rod): coeff = attnForce·myoDimerFracMove (0.4·0.2); force applied at the rod END1
 *       (with R×F torque), node reaction at the surface point (with node torque).
 *   NO axis-alignment torque (unlike the minifilament's miniFilAlign) — the node tether is purely a
 *   positional surface spring.
 *
 * THIS STAGE (6c-A): the node entity as a motor-bundle — FIXED ANCHOR (the node body is NOT integrated,
 * the v1 AnchorNode immobilization), static radial assembly, heads free to bind/stroke a test filament.
 * NO nucleation / runtime filament birth (Stage B; seam #1 kept open — the motor-function is a
 * free-standing system over these child arrays, separable from the future nucleation-function).
 */
public final class NodeStore {

    public final int nNodes;
    public final int nAttach;     // total surface attachments (singlets + dimer myo1 rods) across all nodes

    // v1 geometry / coeffs (Env.java; ProteinNode/AnchorNode; verified BoA-v1ref 2026-06-18)
    public static final double NODE_RADIUS  = 0.05;   // Env.nodeRadius (µm) — the surface-splay radius
    public static final double ATTN_FORCE   = 0.4;    // ProteinNode.keepMyosins/DimersOnSurface attnForce
    public static final double DIMER_FRACMOVE = DimerStore.ROD_FRAC_MOVE;  // myoDimerFracMove = 0.2

    // ---- The node body (4th RigidRodBody instance; isotropic sphere drag, zero length, fixed anchor) ----
    public final RigidRodBody node;
    public final FloatArray nodeBodyParams;   // [0]=dt [1]=sqrt(2kT/dt) (shared Brownian/integration — unused while fixed)
    public final IntArray   nodeBodyCounts;   // [0]=nNodes [1]=stepCount [2]=runSeed [3]=nNodes

    // ---- Per-attachment (child) coupling, size nAttach ----
    // HOST-side readable arrays (gates + the CSR key); the kernel reads the packed arrays below.
    public final IntArray   attachNode;    // which node this child tethers to (the CSR key)
    public final IntArray   attachMotor;   // the child's tethered motor slot (rod sub-body = 3*attachMotor)
    public final IntArray   attachAtEnd1;  // 1 = apply at rod end1 + torque (dimer); 0 = at rod center, no torque (singlet)
    public final FloatArray attachCoeff;   // fracMove-style coeff magnitude (singlet 0.4/nSing, dimer 0.4·0.2)
    // KERNEL-packed arrays (TornadoVM task() caps at 15 args, so per-attachment vector data is planar-packed):
    public final IntArray   attachKey;     // 2*nAttach: [a]=node, [nAttach+a]=motor (packed node|motor)
    public final FloatArray radial;        // 3*nAttach planar (X|Y|Z): body-frame radial offset (µm; |r|=radius)
    public final FloatArray attachCoeffK;  // nAttach SIGNED coeff: >0 ⇒ dimer (end1+torque), <0 ⇒ singlet (center, no torque)
    // node-side reaction stored per attachment for the gather: [6*a..]=force(3)+torque(3) (stride 6, MINI layout)
    public final FloatArray nodeData;      // 6*nAttach
    public static final int NODE_STRIDE = MiniFilamentStore.MINI_STRIDE;   // 6 (reuse the backboneGather layout)

    // ---- Precomputed node inverse translational drag (for the tether denominators) ----
    public final FloatArray nodeInvTransY;  // nNodes; 1/bTransGam.y (isotropic sphere ⇒ .y == .x)

    // ---- CSR-inverse scratch (node→attachments), reusing CrossBridge.csr* keyed by attachNode ----
    public final IntArray   nodeAttachCount;    // nNodes
    public final IntArray   nodeAttachOffsets;  // nNodes+1
    public final IntArray   nodeAttachList;     // nAttach
    // nodeCounts4 mimics MotorStore.counts for the reused CSR build: [0]=nAttach (#items), [3]=nNodes (#buckets).
    public final IntArray   nodeCounts4;        // 4

    // nodeParams (float): [0]=dt
    public final FloatArray nodeParams;

    public NodeStore(int nNodes, int nAttach) {
        this.nNodes = nNodes;
        this.nAttach = nAttach;
        node = new RigidRodBody(nNodes);
        nodeBodyParams = new FloatArray(2);
        nodeBodyCounts = new IntArray(4);
        attachNode   = new IntArray(nAttach);
        attachMotor  = new IntArray(nAttach);
        attachAtEnd1 = new IntArray(nAttach);
        attachCoeff  = new FloatArray(nAttach);
        attachKey    = new IntArray(2 * nAttach);
        radial       = new FloatArray(3 * nAttach);
        attachCoeffK = new FloatArray(nAttach);
        nodeData = new FloatArray(NODE_STRIDE * nAttach);
        nodeInvTransY = new FloatArray(nNodes);
        nodeAttachCount   = new IntArray(nNodes);
        nodeAttachOffsets = new IntArray(nNodes + 1);
        nodeAttachList    = new IntArray(nAttach);
        nodeCounts4 = new IntArray(4);
        nodeParams = new FloatArray(1);

        attachNode.init(0); attachMotor.init(0); attachAtEnd1.init(0); attachCoeff.init(0f);
        attachKey.init(0); radial.init(0f); attachCoeffK.init(0f);
        nodeData.init(0f); nodeInvTransY.init(0f);
        nodeAttachCount.init(0); nodeAttachOffsets.init(0); nodeAttachList.init(0);
        nodeCounts4.set(0, nAttach); nodeCounts4.set(3, nNodes);
    }

    public int nodeIdx(int k) { return k; }   // node sub-body flat index (one sphere per node)

    /** Register attachment `a`: tethers motor `mSlot` to node `k` at body-frame radial offset (ru,ry,rz)
     *  µm; coeff = the fracMove-style coefficient; atEnd1 = true ⇒ dimer (apply at rod end1 + torque). */
    public void attach(int a, int k, int mSlot, double ru, double ry, double rz, double coeff, boolean atEnd1) {
        attachNode.set(a, k);
        attachMotor.set(a, mSlot);
        attachAtEnd1.set(a, atEnd1 ? 1 : 0);
        attachCoeff.set(a, (float) coeff);
        // packed kernel arrays
        attachKey.set(a, k); attachKey.set(nAttach + a, mSlot);
        radial.set(a, (float) ru); radial.set(nAttach + a, (float) ry); radial.set(2 * nAttach + a, (float) rz);
        attachCoeffK.set(a, (float) (atEnd1 ? coeff : -coeff));   // sign carries atEnd1 (coeff always > 0)
    }

    public void setNodeParams(double dt) { nodeParams.set(0, (float) dt); }

    public void setNodeBodyParams(double dt) {
        nodeBodyParams.set(0, (float) dt);
        nodeBodyParams.set(1, (float) Math.sqrt(2.0 * Constants.kT / dt));
    }

    public void setNodeBodyCounts(int stepCount, int runSeed) {
        nodeBodyCounts.set(0, nNodes);
        nodeBodyCounts.set(1, stepCount);
        nodeBodyCounts.set(2, runSeed);
        nodeBodyCounts.set(3, nNodes);
    }

    /** Isotropic SPHERE drag for every node (radius NODE_RADIUS) — the shared sphere-drag formula
     *  (DragTensorSystem.sphereDragSI, public). Zero length (v1 ProteinNode has no length). Sets the
     *  body drag + the precomputed inverse translational drag for the tether denominators. Existing
     *  DragTensorSystem is left byte-unchanged. */
    public void initNodeDrag() {
        double kT = Constants.kT;
        double[] g = DragTensorSystem.sphereDragSI(NODE_RADIUS);   // {bTGx,bTGy,bTGz,bRGx,bRGy,bRGz}, isotropic
        for (int k = 0; k < nNodes; k++) {
            int x = node.planeX(k), y = node.planeY(k), z = node.planeZ(k);
            node.segLength.set(k, 0f);   // sphere: zero length (end1==end2==center)
            node.bTransGam.set(x, (float) g[0]); node.bTransGam.set(y, (float) g[1]); node.bTransGam.set(z, (float) g[2]);
            node.bRotGam.set(x,   (float) g[3]); node.bRotGam.set(y,   (float) g[4]); node.bRotGam.set(z,   (float) g[5]);
            node.bTransDiff.set(x, (float) (kT/g[0])); node.bTransDiff.set(y, (float) (kT/g[1])); node.bTransDiff.set(z, (float) (kT/g[2]));
            node.bRotDiff.set(x,   (float) (kT/g[3])); node.bRotDiff.set(y,   (float) (kT/g[4])); node.bRotDiff.set(z,   (float) (kT/g[5]));
            nodeInvTransY.set(k, (float) (1.0 / g[1]));   // 1/bTransGam.y (isotropic)
        }
    }
}
