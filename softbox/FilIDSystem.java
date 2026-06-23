package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Per-segment filament identity (the crosslinker {@code filID}) computed ON-DEVICE by pointer-jumping (pointer
 * doubling) to the chain terminal — the device-agnostic replacement for the host chain-walk in
 * {@code FullSystemDemoHarness.computeFilID}.
 *
 * <p>filID semantics (faithful to v1 / the host walk): two segments share a filID IFF they are on the same actin
 * chain. The canonical label is the chain's {@code end2NbrSlot}-terminal slot index — each segment walks the
 * (linear, ≤1-neighbour-per-direction) backbone toward end2 until the terminal, whose index identifies the whole
 * filament. FREE slots get a distinct negative id ({@code -seg-2}) so they never collide with an active label.
 * The crosslinker formation predicate is {@code filID[i] != filID[j]} (same-filament exclusion + distinct-filament
 * bundling), so a value-identical label (not merely a matching partition) is produced.
 *
 * <p>The actin backbone (F3/F4 PAIRS chain) is a pure chain — each segment has ≤1 end2 neighbour, no branching
 * (crosslinks do NOT define filament identity) — so pointer-doubling to the terminal is exact in
 * {@code ceil(log2(maxChainLength))} rounds, race-free, atomics-free, KernelContext-free ⇒ runs identically on the
 * GPU TaskGraph and the {@code -cpu} runner (the one-physics / device-agnostic-systems rule). Each round is a
 * separate kernel launch over two ping-pong buffers (read prev, write next) so the whole buffer is consistent
 * between rounds.
 */
public final class FilIDSystem {
    private FilIDSystem() {}

    /** ceil(log2(n)) rounded UP to the nearest EVEN count, so an init→ping-pong chain that starts in {@code filID}
     *  ends back in {@code filID} (even # of jumps). A conservative over-estimate is harmless — once a pointer
     *  reaches its terminal it self-loops, so extra rounds are idempotent. */
    public static int rounds(int n) {
        int k = (n <= 1) ? 1 : (32 - Integer.numberOfLeadingZeros(n - 1));   // ceil(log2(n))
        return (k % 2 == 0) ? k : k + 1;
    }

    /** Round 0: {@code ptr[seg]} = the immediate end2-neighbour toward the terminal, or {@code seg} itself if
     *  {@code seg} is already the terminal (self-loop); FREE slots → {@code -seg-2}. counts[0]=n. */
    public static void init(IntArray filState, IntArray end2NbrSlot, IntArray ptr, IntArray counts) {
        int n = counts.get(0);
        for (@Parallel int seg = 0; seg < n; seg++) {
            if (filState.get(seg) < 0) { ptr.set(seg, -seg - 2); continue; }   // FREE ⇒ distinct negative id
            int nx = end2NbrSlot.get(seg);
            // valid next == an active end2 neighbour that isn't self; else the terminal (point at self)
            ptr.set(seg, (nx >= 0 && nx != seg && filState.get(nx) >= 0) ? nx : seg);
        }
    }

    /** One pointer-doubling round: {@code ptrOut[seg] = ptrIn[ptrIn[seg]]} for active segments (each round doubles
     *  the distance covered toward the terminal); FREE slots carry their {@code -seg-2} marker through unchanged.
     *  An active segment's pointer is always an active slot, so {@code ptrIn[ptrIn[seg]]} never indexes a FREE
     *  marker. counts[0]=n. */
    public static void jump(IntArray ptrIn, IntArray ptrOut, IntArray filState, IntArray counts) {
        int n = counts.get(0);
        for (@Parallel int seg = 0; seg < n; seg++) {
            if (filState.get(seg) < 0) { ptrOut.set(seg, ptrIn.get(seg)); continue; }   // keep FREE marker
            int p = ptrIn.get(seg);
            ptrOut.set(seg, ptrIn.get(p));
        }
    }
}
