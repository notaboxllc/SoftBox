package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 7 Aging build (A) — the per-segment nucleotide-composition proxy aging (the ATP→ADP-Pi→ADP cascade).
 * Three device-agnostic kernels over the SoA arrays (GPU TaskGraph and the -cpu runner identical). A NEW v2
 * representation (recon §2 / CLAUDE.md §8): faithful to v1's per-monomer cascade KINETICS in AGGREGATE, NOT a
 * per-monomer bit-match. ADDITIVE — touches no existing kernel: it rides on existing per-cadence outputs
 * (grow's grewFlag, the split allocator's rankOffsets/freeList) and writes only its own nucFrac array.
 *
 * THE CASCADE is a deterministic forward-Euler step of the two-step linear ODE (the composition is a population
 * fraction, not a stochastic monomer):
 *   df_ATP/dt = −kH·f_ATP ;  df_ADPPi/dt = kH·f_ATP − kD·f_ADPPi ;  df_ADP/dt = kD·f_ADPPi
 * discretised per biochem cadence with pH=kH·bcΔt, pD=kD·bcΔt (both ≪1 ⇒ the map tracks the analytic to ~1e-4).
 * Sum is conserved exactly. Pure float mul/add ⇒ CPU↔GPU bit-identical to float32 last-bit (the depoly DECISION
 * it then feeds may decorrelate at that last bit on a chaotic horizon — §8 aggregate-within-SEM there).
 *
 * Race-free: every kernel is a per-slot self-write (age over each active slot; growthAtp over each grown tip;
 * splitInheritNuc per accepted split writes the child slot, distinct tips ⇒ distinct children). No
 * atomics/KernelContext. No-op when fires==0 (every non-cadence step AND when aging is OFF, pH=pD=0).
 *
 * ON DEPOLY (the per-segment-proxy averaging choice, flagged): pointed-end depoly removes the OLDEST (most-ADP)
 * terminal monomer, but the per-segment proxy carries no within-segment gradient (that is the Stage-4
 * per-monomer fidelity), so depoly leaves the segment's composition fraction UNCHANGED. At steady state the
 * pointed segment is ≈100% ADP (transit time ≫ the ~4.3 s aging time), so this is negligible — and it is the
 * consistent intensive-fraction choice for an aggregate proxy.
 */
public final class AgingSystem {
    private AgingSystem() {}

    /**
     * Age every ACTIVE segment one biochem cadence: the ATP→ADP-Pi→ADP cascade (forward-Euler). Reads the three
     * fractions into locals (so the in-place writes are race-free / order-independent), writes back. No-op
     * (writes nothing) when fires==0 or filState<0.
     *   agingParams: [0]=pH  [1]=pD ;  agingCounts: [0]=C  [1]=fires.
     */
    public static void age(IntArray filState, FloatArray nucFrac, FloatArray agingParams, IntArray agingCounts) {
        int C = filState.getSize();
        int fires = agingCounts.get(1);
        float pH = agingParams.get(0), pD = agingParams.get(1);
        for (@Parallel int s = 0; s < C; s++) {
            if (fires == 0) continue;
            if (filState.get(s) < 0) continue;
            float fATP = nucFrac.get(s), fADPPi = nucFrac.get(C + s);
            float nATP   = fATP * (1f - pH);
            float nADPPi = fADPPi * (1f - pD) + fATP * pH;
            // f_ADP is the conserved remainder. Algebraically nADP = fADP + fADPPi·pD (forward-Euler), but
            // computing it as 1 − nATP − nADPPi ANCHORS the per-segment sum to 1 each cadence (the pD terms cancel
            // exactly in real arithmetic; in float32 the two-place computation drifts ~1e-7/step ⇒ ~1e-5 over
            // thousands of cadences — the remainder form removes that accumulation, identical physics).
            float nADP   = 1f - nATP - nADPPi;
            nucFrac.set(s, nATP); nucFrac.set(C + s, nADPPi); nucFrac.set(2 * C + s, nADP);
        }
    }

    /**
     * Re-weight a grown tip's composition toward ATP: barbed growth (GrowthSystem.grow, already ran this cadence,
     * leaving grewFlag=1 + monomerCount already incremented to M) added ONE fresh ATP monomer. Mixing one new ATP
     * monomer into an M-monomer segment: f' = f·(M−1)/M (+ 1/M for ATP). Sum conserved. Per grown-tip self-write,
     * race-free. No-op when grewFlag==0 (so when growth is OFF / a non-cadence step, this writes nothing).
     */
    public static void growthAtp(IntArray grewFlag, IntArray monomerCount, FloatArray nucFrac) {
        int C = grewFlag.getSize();
        for (@Parallel int s = 0; s < C; s++) {
            if (grewFlag.get(s) == 0) continue;
            int M = monomerCount.get(s);
            if (M <= 0) continue;                       // defensive (grew ⇒ M>=1)
            float w = (float) (M - 1) / (float) M;      // old-mass weight
            float inv = 1f / (float) M;                 // the new ATP monomer
            nucFrac.set(s,         nucFrac.get(s)         * w + inv);
            nucFrac.set(C + s,     nucFrac.get(C + s)     * w);
            nucFrac.set(2 * C + s, nucFrac.get(2 * C + s) * w);
        }
    }

    /**
     * Split-inheritance: a freshly-split CHILD inherits its parent's composition (recon §2). Mirrors
     * GrowthSystem.splitWire's iteration EXACTLY (per accepted request r the parent is slot Gs=r, the child is
     * Cs=freeList[rankOffsets[r]] when allocated), reading the SAME rankOffsets/freeList arrays splitWire uses —
     * so no GrowthSystem edit. The parent's nucFrac is unchanged by the split (the fraction is intensive; halving
     * monomerCount does not change it), so copying nucFrac[Gs]→nucFrac[Cs] is the faithful inheritance. Distinct
     * tips ⇒ distinct {Gs,Cs} ⇒ one writer per child, race-free. Run AFTER splitWire (allocate populated the
     * free-list/ranks). allocCounts: [0]=C [1]=K(reqCap).
     */
    public static void splitInheritNuc(IntArray rankOffsets, IntArray freeList, IntArray freeOffsets,
                                       FloatArray nucFrac, IntArray allocCounts) {
        int C = allocCounts.get(0), K = allocCounts.get(1);
        int nFree = freeOffsets.get(C);
        for (@Parallel int r = 0; r < K; r++) {
            int rank = rankOffsets.get(r);
            if (!(rankOffsets.get(r + 1) > rank)) continue;   // not an accepted split
            if (rank >= nFree) continue;                      // no free slot ⇒ no child allocated
            int Gs = r;
            int Cs = freeList.get(rank);
            nucFrac.set(Cs,         nucFrac.get(Gs));
            nucFrac.set(C + Cs,     nucFrac.get(C + Gs));
            nucFrac.set(2 * C + Cs, nucFrac.get(2 * C + Gs));
        }
    }

    /**
     * Increment 7 DEAD-SLOT-REUSE FIX (aging side). A freshly NUCLEATED seed is unhydrolyzed ⇒ pure ATP
     * (f_ATP=1, f_ADPPi=0, f_ADP=0, v1 Monomer:62) — NOT the recycled corpse's aged (mostly-ADP) composition that
     * a dead slot leaves stale in nucFrac. This is the aging-side analog of NodeNucleationSystem.initNewborn,
     * mirroring how splitInheritNuc is the aging-side analog of GrowthSystem.splitWire: it runs the SAME rank→slot
     * iteration over the SAME rankOffsets/freeList arrays as initNewborn (post-allocate, per accepted nucleation
     * request), so NodeNucleationSystem needs no nucFrac coupling (aging stays additive — it touches only nucFrac).
     * One writer per born slot ⇒ race-free, no atomics. (Contrast splitInheritNuc, which COPIES the parent's
     * composition — a split child inherits; a nucleated seed is born fresh.)
     */
    public static void nucleateFreshAtp(IntArray rankOffsets, IntArray freeList, IntArray freeOffsets,
                                        FloatArray nucFrac, IntArray allocCounts) {
        int C = allocCounts.get(0), K = allocCounts.get(1);
        int nFree = freeOffsets.get(C);
        for (@Parallel int r = 0; r < K; r++) {
            int rank = rankOffsets.get(r);
            if (!(rankOffsets.get(r + 1) > rank)) continue;   // not an accepted nucleation
            if (rank >= nFree) continue;                      // over-clamp ⇒ no birth
            int slot = freeList.get(rank);
            nucFrac.set(slot, 1f); nucFrac.set(C + slot, 0f); nucFrac.set(2 * C + slot, 0f);
        }
    }
}
