package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 7 Stage 1 — actin TURNOVER: pointed-end (end1) depolymerization + filament DEATH. The reverse of
 * growth (GrowthSystem). Built from the v1 BEHAVIOR (recon INC7_TURNOVER_RECON.md §1d/§1f/§3c/§3d; v1
 * FilSegment.removeMonomerSim:2786 + end1BiochemSim:1041 + cleanup:2861) — NOT a class port. Default-OFF.
 *
 * CONVENTION (barbed=end2 settled, 2026-06-19). The pointed end is END1 (outward, free); the barbed end is END2
 * (node-side, held by the formin/tether). Depoly removes a monomer from the POINTED tip (end1), so end1 retracts
 * INWARD while end2 stays fixed — the EXACT reverse of growth's lengthen (growth fixes end2 and extends end1
 * outward with coord −= ½·mono·uVec; depoly fixes end2 and retracts end1 inward with coord += ½·mono·uVec).
 * uVec points INWARD (toward the node); the +sign with the inward uVec moves the center toward the node as the
 * segment shortens. The segLength update is the REUSED GrowthSystem.recomputeDrag (monomerCount → segLength +
 * the shared rod-drag); depoly here only mutates monomerCount + the coord shift (mirror of grow).
 *
 * THE POINTED-TIP GATE. Only a segment whose end1 has NO chain neighbor (end1NbrSlot < 0) is a pointed tip and
 * depolymerizes; an interior segment (end1 bonded) does not. In a chain node—G(barbed tip)—…—P(pointed tip),
 * only the OUTERMOST segment P shrinks; as P dies, its node-ward neighbor becomes the new pointed tip next
 * cadence ⇒ the filament is consumed pointed-end-first, ONE segment per filament per cadence (so distinct dying
 * tips ⇒ distinct {dying slot, its node-ward neighbor} ⇒ the neighbor link-break is a scatter to disjoint slots,
 * race-free, no atomics/KernelContext).
 *
 * DEATH (monomerCount < actinSeed=3, v1's floor). v1 attempts depoly while monomerCount >= actinSeed, else
 * cleanup() — the segment dies, returning ALL its monomers to the pool en masse. Stage 1 mirrors: depoly marks
 * deathFlag when M < actinSeed (returnedMon = M, the en-masse conservation point); applyDeath then markFrees the
 * slot (filState FREE + Brownian scale 0 + monomerCount 0), breaks BOTH neighbor links (sets the neighbors'
 * back-pointers to −1, fragmenting the chain into valid sub-chains), and clears the node tether (seedNode = −1 —
 * the node's bound-filament count is RECOMPUTED each cadence by NodeNucleationSystem.countBoundFil, so clearing
 * seedNode suffices: NO atomic decrement, recon §3c realized cleaner than v1's filamentOff).
 *
 * SLOT RECYCLE (Design A — slot-stable, NO swap-compaction). Death = markFree (a self-write of the FREE
 * sentinel), exactly the inverse of B1's allocate. A freed slot re-enters the scan-rank free-list the SAME
 * cadence (death runs BEFORE freeFlags rebuilds the list ⇒ the dead slot is reclaimable same-step by growth's
 * split or B2 nucleation — the proven 5c-i death→free-list→allocate order). This structurally avoids v1's
 * removeFilSegment swap-compaction (the packRange ClassCast desync) — a deliberate v2 improvement.
 *
 * CADENCE / no-op. Depoly fires every biochemCheckInt steps (depolyCounts[3]=fires); the GPU TaskGraph stays
 * fixed (a step-gate, like growth). When fires==0 (every non-cadence step, AND every step when depoly is OFF)
 * depoly early-returns touching ONLY scratch (returnedMon/deathFlag, both zeroed) and applyDeath sees deathFlag
 * all 0 ⇒ no writes ⇒ a depoly-OFF run is bit-identical to a static/growth baseline.
 *
 * Device-agnostic: every kernel is a plain method over the SoA arrays (GPU TaskGraph and -cpu runner identical).
 * The lifecycle decisions (monomerCount, death, link-break) are integer/wang-hash ⇒ bit-identical CPU↔GPU; the
 * pose / drag carry only float32 last-bit differences.
 */
public final class DepolySystem {
    private DepolySystem() {}

    private static int wangHash(int seed) {
        seed = (seed ^ 61) ^ (seed >>> 16);
        seed *= 9;
        seed = seed ^ (seed >>> 4);
        seed *= 0x27d4eb2d;
        seed = seed ^ (seed >>> 15);
        return seed;
    }

    /**
     * Pointed-end (end1) depoly OR mark-death, per ACTIVE pointed-tip segment (end1NbrSlot < 0). At the fixed
     * rate P = offRate·biochemDeltaT (Stage 1): if monomerCount >= actinSeed, draw a wang-hash and on fire
     * monomerCount−−, coord += ½·mono·uVec (reverse of growth — end2 fixed, end1 retracts), returnedMon=1 (one
     * monomer back to the pool). Else (monomerCount < actinSeed) deathFlag=1, returnedMon=monomerCount (the
     * en-masse return). Per-slot self-write ⇒ race-free, bit-identical CPU↔GPU on the integer decision. No-op
     * (only zeros returnedMon/deathFlag) when fires==0.
     */
    public static void depoly(IntArray filState, IntArray monomerCount, FloatArray coord, FloatArray uVec,
                              IntArray end1NbrSlot, IntArray returnedMon, IntArray deathFlag,
                              FloatArray depolyParams, IntArray depolyCounts) {
        int C = filState.getSize();
        int fires = depolyCounts.get(3), step = depolyCounts.get(1), seed = depolyCounts.get(2);
        float P = depolyParams.get(0), halfmono = depolyParams.get(1);
        int floor = (int) depolyParams.get(2);   // actinSeed
        for (@Parallel int s = 0; s < C; s++) {
            returnedMon.set(s, 0);
            deathFlag.set(s, 0);
            if (fires == 0) continue;
            if (filState.get(s) < 0) continue;            // FREE slot
            if (end1NbrSlot.get(s) >= 0) continue;        // not a pointed tip (end1 bonded) ⇒ no end1 depoly
            int M = monomerCount.get(s);
            if (M >= floor) {
                int base = (s * 1000003) ^ (step * 999983) ^ (seed * 7919) ^ 0x4445504F; // "DEPO"
                float u = (wangHash(base) >>> 1) / 2147483647.0f;
                if (u >= P) continue;
                monomerCount.set(s, M - 1);
                float ux = uVec.get(s), uy = uVec.get(C + s), uz = uVec.get(2 * C + s);
                // reverse of growth: end2 (node/barbed) fixed, end1 (pointed) retracts inward. uVec is INWARD ⇒
                // coord += ½·mono·uVec moves the center toward the node as the segment shortens.
                coord.set(s,         coord.get(s)         + 0.5f * halfmono * ux);
                coord.set(C + s,     coord.get(C + s)     + 0.5f * halfmono * uy);
                coord.set(2 * C + s, coord.get(2 * C + s) + 0.5f * halfmono * uz);
                returnedMon.set(s, 1);
            } else {
                // below the floor ⇒ the segment dies; ALL remaining monomers return to the pool en masse
                deathFlag.set(s, 1);
                returnedMon.set(s, M);
            }
        }
    }

    /**
     * Apply death (post-depoly): per slot flagged dead, markFree (filState FREE + Brownian scale 0 + monomerCount
     * 0), break BOTH neighbor links (set each neighbor's back-pointer to −1, leaving valid sub-chains), and clear
     * the node tether (seedNode −1). The dying slot is a self-write; the neighbor back-pointer writes are a
     * scatter to DISJOINT slots (a pointed tip's node-ward neighbor is interior ⇒ not itself dying; distinct
     * dying tips ⇒ distinct neighbors) ⇒ race-free, no atomics. No-op when deathFlag all 0 (every step depoly is
     * OFF / a non-cadence step) ⇒ bit-identical to a no-death baseline.
     */
    public static void applyDeath(IntArray filState, IntArray monomerCount, IntArray seedNode,
                                  IntArray end1NbrSlot, IntArray end1NbrSide, IntArray end2NbrSlot, IntArray end2NbrSide,
                                  FloatArray brownTransScale, FloatArray brownRotScale,
                                  IntArray deathFlag, IntArray depolyCounts) {
        int C = filState.getSize();
        for (@Parallel int s = 0; s < C; s++) {
            if (deathFlag.get(s) == 0) continue;
            // markFree: the inverse of B1 allocate (a self-write of the FREE sentinel)
            filState.set(s, FilamentStore.FIL_FREE);
            brownTransScale.set(s, 0f);
            brownRotScale.set(s, 0f);
            monomerCount.set(s, 0);
            // break the end2 (node-ward) neighbor link: clear the neighbor's back-pointer, then my own
            int n2 = end2NbrSlot.get(s), n2side = end2NbrSide.get(s);
            if (n2 >= 0) {
                if (n2side == 0) { end1NbrSlot.set(n2, -1); end1NbrSide.set(n2, -1); }
                else             { end2NbrSlot.set(n2, -1); end2NbrSide.set(n2, -1); }
                end2NbrSlot.set(s, -1); end2NbrSide.set(s, -1);
            }
            // break the end1 (pointed) neighbor link (defensive: a pointed tip has end1NbrSlot<0, but a non-tip
            // death path or a lone seed is covered uniformly)
            int n1 = end1NbrSlot.get(s), n1side = end1NbrSide.get(s);
            if (n1 >= 0) {
                if (n1side == 0) { end1NbrSlot.set(n1, -1); end1NbrSide.set(n1, -1); }
                else             { end2NbrSlot.set(n1, -1); end2NbrSide.set(n1, -1); }
                end1NbrSlot.set(s, -1); end1NbrSide.set(s, -1);
            }
            // clear the node tether (the node's bound count is recomputed by countBoundFil ⇒ no atomic decrement)
            if (seedNode.get(s) >= 0) seedNode.set(s, -1);
        }
    }
}
