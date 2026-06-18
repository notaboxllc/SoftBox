package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 6c — actin POLYMERIZATION: barbed-end elongation (lengthen + split, growth-only). The FIRST
 * dynamic actin GROWTH in SoftBox (filaments were static-length through inc 6). Built from the v1 BEHAVIOR
 * (FilSegment.addMonomerSim:2764 / splitSegment:863) — NOT a class port. The granularity fork is resolved
 * favorably (recon INC6C_POLYMERIZATION_RECON.md): a SoftBox segment is already a length-mutable rod carrying
 * monomerCount, with segLength = (monomerCount+1)·actinMonoRadius and the drag-from-monomerCount recompute
 * (DragTensorSystem) present — so growth is "lengthen the terminal segment, then split," turning on a dormant,
 * shape-compatible path.
 *
 * GROWING-END CONVENTION (recon flag a, resolved). The growing/barbed/formin end = end1, consistent with B2's
 * seedTether (which tethers end1 to the node). A growing TIP = a node-bonded segment (seedNode[s] >= 0). Growth
 * keeps end1 anchored at the node and extends end2 OUTWARD: coord += ½·actinMonoRadius·uVec per monomer (end1
 * fixed, end2 +actinMonoRadius). v1 does the same via incCoord(halfmono/2, uVec) (FilSegment.end2BiochemSim).
 * This perturbs only the soft node tether / the chain link at end2 (absorbed by the chain spring) — the
 * dt-stable choice. The success criterion ("grows at its node-side barbed end, extends outward") is met.
 *
 * SPLIT @ 64 (the correctness headline). When a tip reaches monomerCount >= 2·stdSegLength (64), it splits into
 * two stdSegLength (32) halves: the node-side half stays on the existing slot (KEEPS the node bond, keeps
 * growing — v1 transfers the formin to the child; we keep it on the stable tip slot, a behavior-faithful
 * divergence flagged below), the OUTER half is born into a fresh slot via B1's scan-rank allocator (REUSED
 * VERBATIM), and the chain neighbors of three slots {parent G, child C, G's old end2-neighbor Mold} are rewired
 * so the chain stays linear: node—G(32, tip)—C(32)—Mold—…  Distinct tips ⇒ distinct {G,C,Mold} ⇒ race-free,
 * no atomics/KernelContext.
 *
 * POOL COUPLING (seam #2). The growth rate is FIRST-ORDER in [actin]: P = onRate·conc·biochemDeltaT (v1
 * addMonomerSim), with onRate = kATPOn2WithFormin. The host refreshes P each cadence from ActinPool.conc()
 * (GrowthStore.refreshRate) and depletes the pool by the number of monomers added (GrowthStore.depletePoolForGrows,
 * counted via the reused csrScan over grewFlag). B2's nucleation was deliberately [actin]-INDEPENDENT; growth
 * adds the pool READ.
 *
 * CADENCE / no-op. Growth fires every biochemCheckInt steps (growCounts[3]=fires); the GPU TaskGraph stays
 * fixed (a step-gate, like ContainmentSystem). When fires==0 (every non-cadence step, AND every step when growth
 * is OFF) grow/markSplits/recomputeDrag early-return touching only scratch (grewFlag/acceptFlag) — and the
 * allocator + splitWire are no-ops with acceptFlag all 0 — so a growth-OFF run is bit-identical to a static run.
 *
 * Device-agnostic: every kernel is a plain method over the SoA arrays (runs on the GPU TaskGraph and the -cpu
 * runner identically). The lifecycle decisions (monomerCount, split, chain rewire) are integer/wang-hash ⇒
 * bit-identical CPU↔GPU; the pose / drag carry only float32 last-bit differences. Math.log lowers on PTX
 * (BrownianForceSystem proves it), so recomputeDrag is a real device per-slot kernel (recon flag d resolved).
 *
 * FLAGGED v1 divergences (behavior-faithful, not class-faithful):
 *   - v1 transfers the formin/node bond to the CHILD on split (transferEnd2Plasmid); we KEEP it on the stable
 *     tip slot G. A labeling choice — G is always the node-side growing segment — that avoids reshuffling the
 *     seedNode/tether every split. Topologically equivalent (node—G—C—…).
 *   - depolymerization / treadmilling (pointed-end shrink) is DEFERRED (the next layer, tied to filament
 *     turnover); growth-only / monotonic is what Test B bridging needs.
 */
public final class GrowthSystem {
    private GrowthSystem() {}

    private static int wangHash(int seed) {
        seed = (seed ^ 61) ^ (seed >>> 16);
        seed *= 9;
        seed = seed ^ (seed >>> 4);
        seed *= 0x27d4eb2d;
        seed = seed ^ (seed >>> 15);
        return seed;
    }

    /**
     * Lengthen: per growing TIP (seedNode>=0, monomerCount<maxMon), draw a wang-hash and grow by one monomer at
     * P = onRate·conc·biochemDeltaT. On growth: monomerCount++, coord += ½·actinMonoRadius·uVec (end1/node fixed,
     * end2 extends outward), grewFlag=1 (the pool-depletion counter input). Per-slot self-write ⇒ race-free,
     * bit-identical CPU↔GPU on the integer decision. No-op (only zeros grewFlag) when fires==0.
     */
    public static void grow(IntArray seedNode, IntArray monomerCount, FloatArray coord, FloatArray uVec,
                            IntArray grewFlag, FloatArray growParams, IntArray growCounts) {
        int C = seedNode.getSize();
        int fires = growCounts.get(3), step = growCounts.get(1), seed = growCounts.get(2);
        float P = growParams.get(0), halfmono = growParams.get(1);
        int maxMon = (int) growParams.get(2);
        for (@Parallel int s = 0; s < C; s++) {
            grewFlag.set(s, 0);
            if (fires == 0) continue;
            if (seedNode.get(s) < 0) continue;             // only node-bonded tips grow
            if (monomerCount.get(s) >= maxMon) continue;   // at the split threshold ⇒ wait for the split
            int base = (s * 1000003) ^ (step * 999983) ^ (seed * 7919) ^ 0x47524F57; // "GROW"
            float u = (wangHash(base) >>> 1) / 2147483647.0f;
            if (u >= P) continue;
            monomerCount.set(s, monomerCount.get(s) + 1);
            float ux = uVec.get(s), uy = uVec.get(C + s), uz = uVec.get(2 * C + s);
            coord.set(s,         coord.get(s)         + 0.5f * halfmono * ux);
            coord.set(C + s,     coord.get(C + s)     + 0.5f * halfmono * uy);
            coord.set(2 * C + s, coord.get(2 * C + s) + 0.5f * halfmono * uz);
            grewFlag.set(s, 1);
        }
    }

    /**
     * Stage a split: per tip at monomerCount >= splitThresh (64), set acceptFlag[s]=1 and write the CHILD birth
     * request (the outer-half pose) into the reused FilamentStore request arrays (reqCap == capacity ⇒ request
     * index r == parent slot). The child is the outer half: coord_C = end2_G' + (½·L_child − actinMonoRadius)·uVec
     * where end2_G' is the parent's NEW end2 after the shrink (end1 fixed). v1 splitSegment geometry. No-op
     * (only zeros acceptFlag) when fires==0.
     */
    public static void markSplits(IntArray seedNode, IntArray monomerCount, FloatArray coord, FloatArray uVec, FloatArray yVec,
                                  IntArray acceptFlag, FloatArray reqCoord, FloatArray reqUVec, FloatArray reqYVec,
                                  FloatArray splitParams, IntArray growCounts) {
        int C = seedNode.getSize();
        int fires = growCounts.get(3);
        int RC = reqCoord.getSize() / 3;               // reqCap (= C)
        double halfmono = splitParams.get(0);
        int splitThresh = (int) splitParams.get(1);    // 2·stdSegLength
        int childMon = (int) splitParams.get(2);       // stdSegLength
        for (@Parallel int s = 0; s < C; s++) {
            acceptFlag.set(s, 0);
            if (fires == 0) continue;
            if (seedNode.get(s) < 0) continue;
            int M = monomerCount.get(s);
            if (M < splitThresh) continue;
            int parentNewMon = M - childMon;
            double Lold = (M + 1) * halfmono;
            double Lpar = (parentNewMon + 1) * halfmono;
            double Lchild = (childMon + 1) * halfmono;
            double ux = uVec.get(s), uy = uVec.get(C + s), uz = uVec.get(2 * C + s);
            // coord_C = coord_G + (−½·Lold + Lpar + ½·Lchild − actinMonoRadius)·uVec  (outer-half center)
            double shift = -0.5 * Lold + Lpar + 0.5 * Lchild - halfmono;
            reqCoord.set(s,           (float) (coord.get(s)         + shift * ux));
            reqCoord.set(RC + s,      (float) (coord.get(C + s)     + shift * uy));
            reqCoord.set(2 * RC + s,  (float) (coord.get(2 * C + s) + shift * uz));
            reqUVec.set(s, (float) ux); reqUVec.set(RC + s, (float) uy); reqUVec.set(2 * RC + s, (float) uz);
            reqYVec.set(s, yVec.get(s)); reqYVec.set(RC + s, yVec.get(C + s)); reqYVec.set(2 * RC + s, yVec.get(2 * C + s));
            acceptFlag.set(s, 1);
        }
    }

    /**
     * Wire the split (post-allocate): per accepted request r (= parent slot G) whose child slot Cs = freeList[rank]
     * was allocated, (1) shrink the parent — monomerCount −= childMon, recompute coord keeping end1 (node) FIXED,
     * (2) finish the child — monomerCount = childMon, seedNode = −1 (not a tip; pose already written by allocate),
     * (3) the 3-slot chain rewire: G.end2 ↔ C.end1, C.end2 → Mold (G's old end2-neighbor), repoint Mold→C. Read
     * G's old end2-neighbor BEFORE overwriting. Distinct tips → distinct {G,C,Mold} ⇒ one writer per slot,
     * race-free. Overflow (rank>=nFree, no child) ⇒ the tip does NOT split (stays at the threshold; grow's maxMon
     * guard keeps it from overshooting). Mirrors NodeNucleationSystem.tagSeeds' rank→slot recovery.
     */
    public static void splitWire(IntArray rankOffsets, IntArray freeList, IntArray freeOffsets,
                                 IntArray monomerCount, FloatArray coord, FloatArray uVec,
                                 IntArray end1NbrSlot, IntArray end1NbrSide, IntArray end2NbrSlot, IntArray end2NbrSide,
                                 IntArray seedNode, FloatArray splitParams, IntArray allocCounts) {
        int C = allocCounts.get(0), K = allocCounts.get(1);
        int nFree = freeOffsets.get(C);
        double halfmono = splitParams.get(0);
        int childMon = (int) splitParams.get(2);
        for (@Parallel int r = 0; r < K; r++) {
            int rank = rankOffsets.get(r);
            if (!(rankOffsets.get(r + 1) > rank)) continue;   // not an accepted split
            if (rank >= nFree) continue;                      // no free slot ⇒ do not split this tip
            int Gs = r;                                       // parent slot (request index == slot)
            int Cs = freeList.get(rank);                      // child slot (allocate wrote its pose, filState ACTIVE)
            int M = monomerCount.get(Gs);
            int parentNewMon = M - childMon;
            // read G's OLD end2 neighbor (Mold) before overwriting
            int Mold = end2NbrSlot.get(Gs), MoldSide = end2NbrSide.get(Gs);
            // parent shrink: keep end1 fixed (end1_G = coord_G − ½·Lold·uVec; coord_G' = end1_G + ½·Lpar·uVec)
            double Lold = (M + 1) * halfmono, Lpar = (parentNewMon + 1) * halfmono;
            double ux = uVec.get(Gs), uy = uVec.get(C + Gs), uz = uVec.get(2 * C + Gs);
            double shift = -0.5 * Lold + 0.5 * Lpar;          // coord_G' − coord_G
            coord.set(Gs,         (float) (coord.get(Gs)         + shift * ux));
            coord.set(C + Gs,     (float) (coord.get(C + Gs)     + shift * uy));
            coord.set(2 * C + Gs, (float) (coord.get(2 * C + Gs) + shift * uz));
            monomerCount.set(Gs, parentNewMon);
            // child: count + not a tip (pose from allocate)
            monomerCount.set(Cs, childMon);
            seedNode.set(Cs, -1);
            // rewire: G.end2 ↔ C.end1
            end2NbrSlot.set(Gs, Cs); end2NbrSide.set(Gs, 0);   // G.end2 glued to C.end1
            end1NbrSlot.set(Cs, Gs); end1NbrSide.set(Cs, 1);   // C.end1 glued to G.end2
            // C.end2 → Mold (G's old outward neighbor); repoint Mold's pointer G→C (its side stays — still glued to an end2)
            end2NbrSlot.set(Cs, Mold);
            end2NbrSide.set(Cs, Mold >= 0 ? MoldSide : -1);
            if (Mold >= 0) {
                if (MoldSide == 0) { end1NbrSlot.set(Mold, Cs); }   // Mold.end1 pointed to G ⇒ now to C
                else               { end2NbrSlot.set(Mold, Cs); }   // Mold.end2 pointed to G ⇒ now to C
            }
        }
    }

    /**
     * Recompute segLength + drag from monomerCount (device per-slot port of DragTensorSystem.run): for each slot,
     * segLength = (monomerCount+1)·actinMonoRadius; the min-length clamp (filAtEnd ⇒ stdSegLength·mono, interior
     * ⇒ minMonomerCt·mono — actin-specific, FilSegment.java:409-419) then the SHARED rod-drag formula
     * (rodDragSI, Math.log lowers on PTX). Stores bTransGam/bRotGam/bTransDiff/bRotDiff. Runs over ALL slots on
     * the cadence (cheap; a FREE/free-rod slot reproduces its init drag exactly ⇒ no perturbation). No-op when
     * fires==0 (drag stays consistent with monomerCount between cadences).
     */
    public static void recomputeDrag(IntArray monomerCount, FloatArray segLength,
                                     IntArray end1NbrSlot, IntArray end2NbrSlot,
                                     FloatArray bTransGam, FloatArray bRotGam, FloatArray bTransDiff, FloatArray bRotDiff,
                                     FloatArray dragParams, IntArray growCounts) {
        int C = monomerCount.getSize();
        int fires = growCounts.get(3);
        double halfmono = dragParams.get(0), aeta = dragParams.get(1), radius = dragParams.get(2), kT = dragParams.get(3);
        double aPar = dragParams.get(4), aOrth = dragParams.get(5), aTurn = dragParams.get(6);
        int stdSeg = (int) dragParams.get(7), minMon = (int) dragParams.get(8);
        for (@Parallel int i = 0; i < C; i++) {
            if (fires == 0) continue;
            int mc = monomerCount.get(i);
            double length = (mc + 1) * halfmono;
            segLength.set(i, (float) length);
            boolean atEnd = (end1NbrSlot.get(i) < 0) || (end2NbrSlot.get(i) < 0);
            double minLength = (atEnd ? stdSeg : minMon) * halfmono;
            double asIf = length < minLength ? minLength : length;
            double LM = 1.0e-6 * asIf, RM = radius * 1.0e-6;
            double logT = Math.log(LM / (2.0 * RM));
            double bTGx = (2.0 * Math.PI * aeta * LM) / (logT + aPar);
            double bTGy = (4.0 * Math.PI * aeta * LM) / (logT + aOrth);
            double bRGx = 4.0 * Math.PI * aeta * RM * RM * LM;
            double bRGy = (Math.PI * aeta * (LM * LM * LM)) / (3.0 * (logT + aTurn));
            int iy = C + i, iz = 2 * C + i;
            bTransGam.set(i, (float) bTGx); bTransGam.set(iy, (float) bTGy); bTransGam.set(iz, (float) bTGy);
            bRotGam.set(i, (float) bRGx);   bRotGam.set(iy, (float) bRGy);   bRotGam.set(iz, (float) bRGy);
            bTransDiff.set(i, (float) (kT / bTGx)); bTransDiff.set(iy, (float) (kT / bTGy)); bTransDiff.set(iz, (float) (kT / bTGy));
            bRotDiff.set(i, (float) (kT / bRGx));   bRotDiff.set(iy, (float) (kT / bRGy));   bRotDiff.set(iz, (float) (kT / bRGy));
        }
    }
}
