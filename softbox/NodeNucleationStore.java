package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 6c Stage B2 — the node NUCLEATION-FUNCTION state (seam #1: a separable behavior attached to the
 * node, additive over Stage A). Built from jba's behavioral spec (NOT a v1 code port): the node births a
 * fixed-length actin seed, holds it with an ELASTIC tether (node↔seed-end), dissolves the bond at a rate
 * (→ free filament), and DAMPS the seed's Brownian motion (a dt-compensating stand-in for the formin's stiff
 * hold). v1 (BoA-v1ref) is the clean-specifics reference only: kNodeNuc=10/node·s, actinSeed=3 (≈10.8 nm),
 * nodeTetherDetachRate=0.001/s, the fracMove tether law (Env.fracMove=0.5). FLAGGED v1 drift vs jba's spec:
 *   - v1's nodeTetherDetachRate AND maxNodeTetherStrainDist are INACTIVE by default (Parameter 5th arg false);
 *     jba's spec wants dissolution ON, so B2 enables the rate (configurable). v1's value (0.001/s) is reused.
 *   - v1 also has an optional nodeTorqSpring filament-alignment torque (1e-18 N/rad, active); jba's spec is a
 *     POSITIONAL elastic tether only, so B2 omits the alignment torque (flagged for jba — addable later).
 *   - the recon's "Bell/log-stretch" characterization of the node tether release is imprecise — the actual v1
 *     node-tether detach is a CONSTANT rate (nodeTetherDetachRate·dt), reproduced here.
 *
 * The seed BIRTH reuses B1's lifecycle UNCHANGED: the emitter fills FilamentStore's request arrays
 * (acceptFlag / reqCoord / reqUVec / reqYVec) and the scan-rank allocator (FilamentBirthSystem) rides
 * underneath; B2 provides only the birth SOURCE + the per-slot node-bond (seedNode) + the tether + the
 * dissolution. reqCap = nNodes (a node draws at most one nucleation per step ⇒ request r ↔ node r).
 *
 * LIFECYCLE FIELDS (orthogonal to FilamentStore.filState):
 *   filState[s]  : is the SLOT alive?  (>=0 ACTIVE / <0 FREE — B1)
 *   seedNode[s]  : if alive, tethered to which node?  (>=0 node index / <0 = a free, untethered filament)
 * A dissolved seed sets seedNode[s] = -1 but keeps filState ACTIVE ⇒ it becomes a free filament (it does NOT
 * free the slot; filament death/turnover is deferred — freed seeds persist).
 */
public final class NodeNucleationStore {

    public final int nNodes;
    public final int filCap;          // FilamentStore capacity (= seedNode size)
    public final int actinSeedMon;    // monomers per seed (v1 actinSeed)

    // ---- per-slot node bond + per-node count ----
    public final IntArray seedNode;       // filCap; node tethered to, or -1 (free / not a tethered seed)
    public final IntArray nodeBoundFil;   // nNodes; #seeds tethered to each node (recomputed each step)

    // ---- kernel scalar params ----
    // nucParams: [0]=P_nuc (= kNodeNuc·dt), [1]=seed HALF-length (µm), [2]=forminsPerNode (cap)
    public final FloatArray nucParams;
    // tetherParams: [0]=fracMove, [1]=dt
    public final FloatArray tetherParams;
    // dissolveParams: [0]=P_detach (= nodeTetherDetachRate·dt)
    public final FloatArray dissolveParams;
    // nucCounts: [0]=nNodes [1]=stepCount [2]=runSeed [3]=poolOK (1 if the actin pool can cover one more seed)
    public final IntArray   nucCounts;

    // ---- the actin pool (seam #2; host-side scalar accessor) ----
    public final ActinPool pool;
    // born-seed Brownian damping (jba's spec #4: ~20–50× reduced; the dt-compensating stand-in for the
    // formin's stiff hold). The born seed's brownTransScale/brownRotScale = full · 1/dampingFactor.
    public final double dampingFactor;

    public NodeNucleationStore(int nNodes, int filCap, int actinSeedMon, double pool0, double boxVolUm3,
                               double dampingFactor) {
        this.nNodes = nNodes;
        this.filCap = filCap;
        this.actinSeedMon = actinSeedMon;
        this.dampingFactor = dampingFactor;
        seedNode = new IntArray(filCap);     seedNode.init(-1);
        nodeBoundFil = new IntArray(nNodes); nodeBoundFil.init(0);
        nucParams = new FloatArray(3);
        tetherParams = new FloatArray(2);
        dissolveParams = new FloatArray(1);
        nucCounts = new IntArray(4);
        nucCounts.set(0, nNodes);
        pool = new ActinPool(pool0, boxVolUm3);
    }

    /** seedLenUm = (actinSeed+1)·actinMonoRadius (≈10.8 nm). forminsPerNode = the per-node nucleation cap
     *  (v1 default 0 = OFF; >0 enables — the whole nucleation-function is no-op at forminsPerNode 0). */
    public void setNucParams(double kNodeNuc, double dt, double seedLenUm, int forminsPerNode) {
        nucParams.set(0, (float) (kNodeNuc * dt));
        nucParams.set(1, (float) (0.5 * seedLenUm));
        nucParams.set(2, (float) forminsPerNode);
    }
    public void setTetherParams(double fracMove, double dt) {
        tetherParams.set(0, (float) fracMove);
        tetherParams.set(1, (float) dt);
    }
    public void setDissolveParams(double detachRate, double dt) {
        dissolveParams.set(0, (float) (detachRate * dt));
    }
    public void setCounts(int step, int seed) {
        nucCounts.set(1, step);
        nucCounts.set(2, seed);
    }
    /** Host: refresh the pool-availability gate (seam #2 accessor) before the emit kernel. */
    public void refreshPoolGate() { nucCounts.set(3, pool.available(actinSeedMon) ? 1 : 0); }

    /** Host: count this step's NEW seeds and deplete the pool by that many seeds' worth (seam #2). births =
     *  min(nAccepted, nFree) from the allocator's scan outputs. */
    public int depletePoolForBirths(FilamentStore f) {
        int K = f.allocCounts.get(1);
        int nAccepted = f.rankOffsets.get(K);
        int nFree = f.freeOffsets.get(f.n);
        int births = Math.min(nAccepted, nFree);
        if (births > 0) pool.take(births * actinSeedMon);
        return births;
    }
}
