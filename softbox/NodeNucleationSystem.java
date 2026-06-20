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
 *                   placement: filament barbed end2 ↔ node CENTER, uVec inward). Force + torque on the seed; node is a fixed
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
            // seed pose (barbed=end2): tethered barbed end2 at the node CENTER; coord = node.coord + halfLen·dir
            // (the seed extends OUTWARD along dir), uVec = −dir (INWARD) ⇒ end2 = coord + halfLen·uVec = node.
            float ncx = nodeCoord.get(k), ncy = nodeCoord.get(nN + k), ncz = nodeCoord.get(2 * nN + k);
            // a perp for yVec (any unit ⟂ dir == ⟂ uVec)
            float ex, ey, ez;
            if (dx < 0.9f && dx > -0.9f) { ex = 1f; ey = 0f; ez = 0f; } else { ex = 0f; ey = 1f; ez = 0f; }
            float yx = dy * ez - dz * ey, yy = dz * ex - dx * ez, yz = dx * ey - dy * ex;
            float ym = 1.0f / (float) Math.sqrt(yx * yx + yy * yy + yz * yz); yx *= ym; yy *= ym; yz *= ym;
            reqCoord.set(k, ncx + halfLen * dx); reqCoord.set(RC + k, ncy + halfLen * dy); reqCoord.set(2 * RC + k, ncz + halfLen * dz);
            reqUVec.set(k, -dx); reqUVec.set(RC + k, -dy); reqUVec.set(2 * RC + k, -dz);
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

    /** Per tethered seed: the elastic fracMove spring, node CENTER ↔ seed tethered barbed end2 (barbed=end2
     *  convention). Faithful to v1 addNodeForces (the attach-at-center variant): forceMag = fracMove·1e-6·strain /
     *  (dt·(1/fil.bTransGam.x + 1/node.bTransGam.x)); F toward the node center, applied at end2 (+R×F torque).
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
            double e2x = cx + half * ux, e2y = cy + half * uy, e2z = cz + half * uz;   // tethered (node-attached) barbed end2
            double ncx = nodeCoord.get(k), ncy = nodeCoord.get(nN + k), ncz = nodeCoord.get(2 * nN + k);
            double dx = ncx - e2x, dy = ncy - e2y, dz = ncz - e2z;
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
                // torque from applying F at end2 (barbed, node-attached): R = (end2 − center)·1e-6 = +half·uVec·1e-6
                double Rx = half * ux * 1.0e-6, Ry = half * uy * 1.0e-6, Rz = half * uz * 1.0e-6;
                filTorqueSum.set(s,         (float) (filTorqueSum.get(s)         + (Ry * Fz - Rz * Fy)));
                filTorqueSum.set(C + s,     (float) (filTorqueSum.get(C + s)     + (Rz * Fx - Rx * Fz)));
                filTorqueSum.set(2 * C + s, (float) (filTorqueSum.get(2 * C + s) + (Rx * Fy - Ry * Fx)));
            }
        }
    }

    /** The Newton's-3rd-law NODE reaction for the seed tether (FREE-node SCPR). Stage A omitted it (the node was
     *  a fixed anchor ⇒ the −F reaction did nothing); for a free node it is REAL — without it the node is never
     *  dragged by its own nucleated filament when a partner captures + pulls it, and the filament's barbed end
     *  behaves as if PINNED to the (unreactive) node. Faithful to v1's two-sided formin–node bond. Recomputes the
     *  SAME F as seedTether (KEEP IN LOCK-STEP) and adds −F at the node CENTER (attach-at-center ⇒ no node torque).
     *  PARALLEL OVER NODES: each node accumulates the seed-tether forces acting on it into its own forceSum (one
     *  writer per node ⇒ race-free, no atomics, bit-identical CPU↔GPU) — the standard "a rigid body sums all
     *  forces on it, integrated once per step" pattern, the same accumulation backboneGather does for the node's
     *  myosin tethers. (The many-to-one seeds→node reduction is why it can't be a write from seedTether's
     *  per-filament loop.) */
    public static void seedTetherNodeReact(
            FloatArray filCoord, FloatArray filUVec, FloatArray filSegLength, FloatArray filBTransGam,
            FloatArray nodeForceSum, FloatArray nodeCoord, FloatArray nodeInvTransX,
            IntArray seedNode, FloatArray tetherParams) {
        int C = filCoord.getSize() / 3;
        int nN = nodeCoord.getSize() / 3;
        double fracMove = tetherParams.get(0), dt = tetherParams.get(1);
        for (@Parallel int k = 0; k < nN; k++) {
            double ncx = nodeCoord.get(k), ncy = nodeCoord.get(nN + k), ncz = nodeCoord.get(2 * nN + k);
            double rx = 0.0, ry = 0.0, rz = 0.0;
            for (int s = 0; s < C; s++) {
                if (seedNode.get(s) != k) continue;
                double cx = filCoord.get(s), cy = filCoord.get(C + s), cz = filCoord.get(2 * C + s);
                double ux = filUVec.get(s), uy = filUVec.get(C + s), uz = filUVec.get(2 * C + s);
                double half = 0.5 * filSegLength.get(s);
                double e2x = cx + half * ux, e2y = cy + half * uy, e2z = cz + half * uz;   // tethered barbed end2
                double dx = ncx - e2x, dy = ncy - e2y, dz = ncz - e2z;
                double strain = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (strain > 0.0) {
                    double inv = 1.0 / strain;
                    double denom = dt * (1.0 / filBTransGam.get(s) + nodeInvTransX.get(k));
                    double fm = (denom > 0.0) ? (fracMove * 1.0e-6 * strain / denom) : 0.0;
                    // reaction = −F (seedTether applied +F toward the node center; the node feels the opposite)
                    rx -= fm * dx * inv; ry -= fm * dy * inv; rz -= fm * dz * inv;
                }
            }
            nodeForceSum.set(k,          (float) (nodeForceSum.get(k)          + rx));
            nodeForceSum.set(nN + k,     (float) (nodeForceSum.get(nN + k)     + ry));
            nodeForceSum.set(2 * nN + k, (float) (nodeForceSum.get(2 * nN + k) + rz));
        }
    }

    /** EXPERIMENT — AIM-HOLDING TORQUE (v1 ProteinNode/addNodeForces nodeTorqSpring analog; default OFF).
     *  A restoring torque that holds each aimed-filament segment's axis at its stored aim direction (set at
     *  placement = the node→partner aim). T = aimCoeff·(bRotGam.y/dt)·(uVec × aim) — a fracMove-style rotation
     *  of aimCoeff·sin(angle) toward the aim per step (no acos; dt-stable for aimCoeff<~1). Counters the floppy/
     *  Brownian swing that drops the filament out of the partner's capture cone. Filament-side (the node reaction
     *  torque is small; not applied — flagged as a simplification vs v1's two-sided nodeTorqSpring). Segments with
     *  no aim (|aim|≈0) are skipped ⇒ no-op when AIM_DIR unset. Per-slot self-write ⇒ race-free. */
    public static void seedAimTorque(FloatArray filUVec, FloatArray filBRotGam, FloatArray filTorqueSum,
                                     FloatArray aimDir, FloatArray aimParams) {
        int C = filUVec.getSize() / 3;
        double aimCoeff = aimParams.get(0), dt = aimParams.get(1);
        for (@Parallel int s = 0; s < C; s++) {
            double ax = aimDir.get(s), ay = aimDir.get(C + s), az = aimDir.get(2 * C + s);
            if (ax * ax + ay * ay + az * az < 0.25) continue;   // no aim target for this segment
            double ux = filUVec.get(s), uy = filUVec.get(C + s), uz = filUVec.get(2 * C + s);
            double cx = uy * az - uz * ay, cy = uz * ax - ux * az, cz = ux * ay - uy * ax;   // uVec × aim (= sin·axis)
            double k = aimCoeff * filBRotGam.get(C + s) / dt;    // fracMove-style: rotate aimCoeff·sin(angle)/step
            filTorqueSum.set(s,           (float) (filTorqueSum.get(s)           + k * cx));
            filTorqueSum.set(C + s,       (float) (filTorqueSum.get(C + s)       + k * cy));
            filTorqueSum.set(2 * C + s,   (float) (filTorqueSum.get(2 * C + s)   + k * cz));
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
