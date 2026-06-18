package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 6c Stage B1: the FilamentStore runtime-birth allocator — the FIRST dynamic filament creation in
 * SoftBox (FilamentStore was fully static through inc 6). This is the recon §2 risk: a lifecycle layer on the
 * foundational entity everything binds to.
 *
 * It REUSES the inc-5 crosslinker scan-rank free-list allocator (Design A — no compaction, slot-stable),
 * one level up: the allocated thing is a whole filament slot, not a crosslink. The TWO prefix sums run
 * CrossBridgeSystem.csrScan VERBATIM (the validated single-threaded exclusive scan); the two thin companions
 * here (a flag kernel + a stream-compaction scatter) are the standard prefix-sum-compaction idiom, identical
 * in shape to CrosslinkerSystem.freeFlags/freeScatter. Per allocation phase:
 *
 *   freeFlags    : freeCount[s] = (filState[s] FREE) ? 1 : 0                              (s in [0,C))
 *   csrScan      : freeOffsets = exclusive prefix of freeCount; freeOffsets[C] = nFree    (REUSED)
 *   freeScatter  : freeList[freeOffsets[s]] = s for each FREE s (index order)
 *   csrScan      : rankOffsets = exclusive prefix of acceptFlag; rankOffsets[K] = nAccepted (REUSED)
 *   allocate     : request r (accepted ⇒ rankOffsets[r+1] > rankOffsets[r]) with dense rank
 *                  rank = rankOffsets[r] claims freeList[rank] when rank < nFree (the overflow clamp), writes
 *                  the seed pose, turns ON the Brownian scale, and flips filState FREE→ACTIVE. Distinct accepted
 *                  requests → distinct ranks → distinct freeList entries → distinct slots ⇒ one writer per slot
 *                  (race-free, no atomics, no KernelContext). Existing ACTIVE slots never move (Design A).
 *
 * The seed is FIXED-LENGTH (≈10.8 nm, v1 actinSeed=3 monomers; growth deferred): segLength / monomerCount /
 * drag are pre-initialised identically in EVERY slot at scene build, so a FREE slot already carries the seed's
 * geometry+drag and allocate need only write the pose, the Brownian scale (FREE slots hold 0 ⇒ inert; the born
 * slot gets birthParams), and the lifecycle flag. Chain neighbors stay sentinel -1 — a born seed is a free rod
 * (v1 nucleates a single FilSegment, born bonded to the NODE, not to another segment).
 *
 * Device-agnostic: every kernel runs on the GPU TaskGraph and the sequential -cpu runner with identical integer
 * arithmetic ⇒ bit-identical CPU↔GPU by construction (the inc-5 allocator standard).
 */
public final class FilamentBirthSystem {
    private FilamentBirthSystem() {}

    /** freeCount[s] = 1 if slot s is FREE (filState < 0), else 0. Input to the free-list csrScan. */
    public static void freeFlags(IntArray filState, IntArray freeCount, IntArray allocCounts) {
        int C = allocCounts.get(0);
        for (@Parallel int s = 0; s < C; s++) {
            freeCount.set(s, filState.get(s) < 0 ? 1 : 0);
        }
    }

    /** Stream-compaction scatter: write each FREE slot index into freeList at its prefix-sum position (index
     *  order). Single-threaded (like csrScatter) ⇒ deterministic, bit-identical CPU↔GPU. Reads filState
     *  directly (csrScan has zeroed freeCount as its cursor side-effect). */
    public static void freeScatter(IntArray filState, IntArray freeOffsets, IntArray freeList, IntArray allocCounts) {
        int C = allocCounts.get(0);
        for (@Parallel int gid = 0; gid < 1; gid++) {
            for (int s = 0; s < C; s++) {
                if (filState.get(s) < 0) freeList.set(freeOffsets.get(s), s);
            }
        }
    }

    /** Allocate: accepted request r (dense rank = rankOffsets[r]) claims freeList[rank] when rank < nFree (the
     *  overflow clamp), writes the seed pose into that slot's planar coord/uVec/yVec, turns ON the Brownian
     *  scale (birthParams), and flips filState FREE→ACTIVE. One writer per slot ⇒ race-free. Over-clamp
     *  requests (rank >= nFree) form nothing. */
    public static void allocate(
            FloatArray reqCoord, FloatArray reqUVec, FloatArray reqYVec,
            IntArray rankOffsets, IntArray freeList, IntArray freeOffsets,
            FloatArray coord, FloatArray uVec, FloatArray yVec,
            FloatArray brownTransScale, FloatArray brownRotScale,
            IntArray filState, FloatArray birthParams, IntArray allocCounts) {
        int C = allocCounts.get(0), K = allocCounts.get(1);
        int RC = reqCoord.getSize() / 3;       // request planar stride (= reqCap)
        int nFree = freeOffsets.get(C);
        float bt = birthParams.get(0), br = birthParams.get(1);
        for (@Parallel int r = 0; r < K; r++) {
            int rank = rankOffsets.get(r);
            boolean accepted = rankOffsets.get(r + 1) > rank;   // accept-flag recovered from the rank scan
            if (!accepted) continue;
            if (rank >= nFree) continue;                        // overflow clamp: lowest nFree ranks form
            int slot = freeList.get(rank);
            coord.set(slot, reqCoord.get(r)); coord.set(C + slot, reqCoord.get(RC + r)); coord.set(2 * C + slot, reqCoord.get(2 * RC + r));
            uVec.set(slot, reqUVec.get(r));   uVec.set(C + slot, reqUVec.get(RC + r));   uVec.set(2 * C + slot, reqUVec.get(2 * RC + r));
            yVec.set(slot, reqYVec.get(r));   yVec.set(C + slot, reqYVec.get(RC + r));   yVec.set(2 * C + slot, reqYVec.get(2 * RC + r));
            brownTransScale.set(slot, bt);
            brownRotScale.set(slot, br);
            filState.set(slot, FilamentStore.FIL_ACTIVE);       // FREE → ACTIVE (self-write into the claimed slot)
        }
    }
}
