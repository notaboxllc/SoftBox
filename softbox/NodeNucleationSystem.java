package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 6c Stage B2 — the node NUCLEATION-FUNCTION (seam #1; build-from-behavior, jba's spec). The first
 * dynamic actin CREATION in SoftBox. Per step, over a node + a FilamentStore + an ActinPool:
 *
 *   countBoundFil : nodeBoundFil[k] = #seeds tethered to node k (from seedNode; single-thread histogram).
 *   emit          : per node, if nodeBoundFil[k] < forminsPerNode AND the pool can cover a seed AND a
 *                   wang-hash draw < kNodeNuc·dt → stage a birth request (a random radial seed pose) into
 *                   FilamentStore's request arrays. [actin]-INDEPENDENT fixed per-node rate (recon §2a).
 *   [FilamentBirthSystem allocator — B1, UNCHANGED: births the seed into a free slot.]
 *   tagSeeds      : per accepted request r, seedNode[freeList[rank]] = r (request r ↔ node r) — the node bond.
 *   seedTether    : per tethered seed, the ELASTIC fracMove spring node-center ↔ seed tethered-end (the SAME
 *                   damping-limited law as NodeSystem.tether / the minifilament tether — node-specific
 *                   placement: filament end1 ↔ node CENTER). Force + torque on the seed; node is a fixed
 *                   anchor (Stage A) ⇒ no node reaction applied.
 *   dissolve      : per tethered seed, a CONSTANT-rate wang-hash detach (nodeTetherDetachRate·dt) → seedNode=-1
 *                   ⇒ the seed becomes a FREE filament (filState stays ACTIVE; B1 lifecycle — bindable, drifts).
 *
 * Brownian damping (jba's spec #4): the born seed's Brownian scale is set damped at BIRTH via
 * FilamentStore.birthParams (B1's allocator already writes brownTransScale/brownRotScale). No field added here;
 * a deliberate, non-FDT dt-compensating stand-in for the formin's stiff hold — applied to the SEED ONLY.
 *
 * RNG = the reused v1 wang-hash keyed (node|slot, step, seed) + distinct salts ("NUC1"/"NUCD") ⇒ the
 * stochastic decisions are bit-identical CPU↔GPU; no atomics/KernelContext; every kernel runs on both runners.
 */
public final class NodeNucleationSystem {
    private NodeNucleationSystem() {}

    private static int wangHash(int seed) {
        seed = (seed ^ 61) ^ (seed >>> 16);
        seed *= 9;
        seed = seed ^ (seed >>> 4);
        seed *= 0x27d4eb2d;
        seed = seed ^ (seed >>> 15);
        return seed;
    }

    /** nodeBoundFil[k] = #seeds with seedNode==k. Single-thread (deterministic, bit-identical CPU↔GPU). */
    public static void countBoundFil(IntArray seedNode, IntArray nodeBoundFil, IntArray nucCounts) {
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int nN = nucCounts.get(0);
            int C = seedNode.getSize();
            for (int k = 0; k < nN; k++) nodeBoundFil.set(k, 0);
            for (int s = 0; s < C; s++) { int k = seedNode.get(s); if (k >= 0) nodeBoundFil.set(k, nodeBoundFil.get(k) + 1); }
        }
    }

    /** Per node: stochastic nucleation request. Writes ALL nNodes acceptFlag entries (fresh each step, since
     *  the allocator's rank scan consumes acceptFlag). reqCap == nNodes ⇒ request k ↔ node k. */
    public static void emit(
            FloatArray nodeCoord, IntArray nodeBoundFil,
            IntArray acceptFlag, FloatArray reqCoord, FloatArray reqUVec, FloatArray reqYVec,
            FloatArray nucParams, IntArray nucCounts) {
        int nN = nucCounts.get(0);
        int step = nucCounts.get(1), seed = nucCounts.get(2), poolOK = nucCounts.get(3);
        int RC = reqCoord.getSize() / 3;     // reqCap (= nNodes)
        float pNuc = nucParams.get(0), halfLen = nucParams.get(1);
        int formins = (int) nucParams.get(2);
        for (@Parallel int k = 0; k < nN; k++) {
            acceptFlag.set(k, 0);
            if (nodeBoundFil.get(k) >= formins) continue;
            if (poolOK == 0) continue;
            int base = (k * 1000003) ^ (step * 999983) ^ (seed * 7919) ^ 0x4E554331; // "NUC1"
            float u = (wangHash(base) >>> 1) / 2147483647.0f;
            if (u >= pNuc) continue;
            // random unit direction from 3 more hashes
            float dx = ((wangHash(base ^ 0x9e3779b9) >>> 1) / 2147483647.0f) * 2f - 1f;
            float dy = ((wangHash(base ^ 0x85ebca6b) >>> 1) / 2147483647.0f) * 2f - 1f;
            float dz = ((wangHash(base ^ 0xc2b2ae35) >>> 1) / 2147483647.0f) * 2f - 1f;
            float m2 = dx * dx + dy * dy + dz * dz;
            if (m2 < 1.0e-12f) { dx = 1f; dy = 0f; dz = 0f; m2 = 1f; }
            float inv = 1.0f / (float) Math.sqrt(m2); dx *= inv; dy *= inv; dz *= inv;
            // seed pose: tethered end (end1) at the node CENTER; coord = node.coord + halfLen·dir, uVec = dir
            float ncx = nodeCoord.get(k), ncy = nodeCoord.get(nN + k), ncz = nodeCoord.get(2 * nN + k);
            // a perp for yVec (any unit ⟂ dir)
            float ex, ey, ez;
            if (dx < 0.9f && dx > -0.9f) { ex = 1f; ey = 0f; ez = 0f; } else { ex = 0f; ey = 1f; ez = 0f; }
            float yx = dy * ez - dz * ey, yy = dz * ex - dx * ez, yz = dx * ey - dy * ex;
            float ym = 1.0f / (float) Math.sqrt(yx * yx + yy * yy + yz * yz); yx *= ym; yy *= ym; yz *= ym;
            reqCoord.set(k, ncx + halfLen * dx); reqCoord.set(RC + k, ncy + halfLen * dy); reqCoord.set(2 * RC + k, ncz + halfLen * dz);
            reqUVec.set(k, dx); reqUVec.set(RC + k, dy); reqUVec.set(2 * RC + k, dz);
            reqYVec.set(k, yx); reqYVec.set(RC + k, yy); reqYVec.set(2 * RC + k, yz);
            acceptFlag.set(k, 1);
        }
    }

    /** Post-allocate: tag each accepted request's born slot with its node (request r ↔ node r). Mirrors the
     *  allocator's rank→freeList mapping exactly (one writer per slot ⇒ race-free). */
    public static void tagSeeds(
            IntArray rankOffsets, IntArray freeList, IntArray freeOffsets,
            IntArray seedNode, IntArray allocCounts) {
        int C = allocCounts.get(0), K = allocCounts.get(1);
        int nFree = freeOffsets.get(C);
        for (@Parallel int r = 0; r < K; r++) {
            int rank = rankOffsets.get(r);
            if (!(rankOffsets.get(r + 1) > rank)) continue;
            if (rank >= nFree) continue;
            seedNode.set(freeList.get(rank), r);
        }
    }

    /** Per tethered seed: the elastic fracMove spring, node CENTER ↔ seed tethered-end (end1). Faithful to v1
     *  addNodeForces (the attach-at-center variant): forceMag = fracMove·1e-6·strain /
     *  (dt·(1/fil.bTransGam.x + 1/node.bTransGam.x)); F toward the node center, applied at end1 (+R×F torque).
     *  Node is a fixed anchor (Stage A) ⇒ the −F node reaction is not applied. */
    public static void seedTether(
            FloatArray filCoord, FloatArray filUVec, FloatArray filSegLength,
            FloatArray filBTransGam, FloatArray filForceSum, FloatArray filTorqueSum,
            FloatArray nodeCoord, FloatArray nodeInvTransX, IntArray seedNode, FloatArray tetherParams) {
        int C = filCoord.getSize() / 3;
        int nN = nodeCoord.getSize() / 3;
        double fracMove = tetherParams.get(0), dt = tetherParams.get(1);
        for (@Parallel int s = 0; s < C; s++) {
            int k = seedNode.get(s);
            if (k < 0) continue;
            double cx = filCoord.get(s), cy = filCoord.get(C + s), cz = filCoord.get(2 * C + s);
            double ux = filUVec.get(s), uy = filUVec.get(C + s), uz = filUVec.get(2 * C + s);
            double half = 0.5 * filSegLength.get(s);
            double e1x = cx - half * ux, e1y = cy - half * uy, e1z = cz - half * uz;   // tethered (node-attached) end
            double ncx = nodeCoord.get(k), ncy = nodeCoord.get(nN + k), ncz = nodeCoord.get(2 * nN + k);
            double dx = ncx - e1x, dy = ncy - e1y, dz = ncz - e1z;
            double strain = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (strain > 0.0) {
                double inv = 1.0 / strain;
                double lx = dx * inv, ly = dy * inv, lz = dz * inv;
                double denom = dt * (1.0 / filBTransGam.get(s) + nodeInvTransX.get(k));   // v1 uses bTransGam.x
                double fm = (denom > 0.0) ? (fracMove * 1.0e-6 * strain / denom) : 0.0;
                double Fx = fm * lx, Fy = fm * ly, Fz = fm * lz;
                filForceSum.set(s,         (float) (filForceSum.get(s)         + Fx));
                filForceSum.set(C + s,     (float) (filForceSum.get(C + s)     + Fy));
                filForceSum.set(2 * C + s, (float) (filForceSum.get(2 * C + s) + Fz));
                // torque from applying F at end1: R = (end1 − center)·1e-6 = −half·uVec·1e-6
                double Rx = -half * ux * 1.0e-6, Ry = -half * uy * 1.0e-6, Rz = -half * uz * 1.0e-6;
                filTorqueSum.set(s,         (float) (filTorqueSum.get(s)         + (Ry * Fz - Rz * Fy)));
                filTorqueSum.set(C + s,     (float) (filTorqueSum.get(C + s)     + (Rz * Fx - Rx * Fz)));
                filTorqueSum.set(2 * C + s, (float) (filTorqueSum.get(2 * C + s) + (Rx * Fy - Ry * Fx)));
            }
        }
    }

    /** Per tethered seed: a CONSTANT-rate wang-hash detach → seedNode = -1 (bond dissolves; the seed stays an
     *  ACTIVE free filament). Per-slot self-write ⇒ race-free, bit-identical CPU↔GPU. */
    public static void dissolve(IntArray seedNode, FloatArray dissolveParams, IntArray nucCounts) {
        int C = seedNode.getSize();
        int step = nucCounts.get(1), seed = nucCounts.get(2);
        float pDetach = dissolveParams.get(0);
        for (@Parallel int s = 0; s < C; s++) {
            if (seedNode.get(s) < 0) continue;
            int base = (s * 1000003) ^ (step * 999983) ^ (seed * 7919) ^ 0x4E554344; // "NUCD"
            float u = (wangHash(base) >>> 1) / 2147483647.0f;
            if (u < pDetach) seedNode.set(s, -1);
        }
    }
}
