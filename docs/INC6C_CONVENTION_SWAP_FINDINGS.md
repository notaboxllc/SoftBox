# Increment 6c — convention swap: node/growth/nucleation realigned to barbed=end2 — FINDINGS

**Date:** 2026-06-19. **Status: DONE — committed on green.** v2's barbed-end convention is now **uniformly
end2**, matching v1 and the rest of v2. The node self-grab is **eliminated at the root** — rejected by v2's own
**unmodified** bind gate via the corrected **inward** node-filament polarity (NO gate edit). §A bit-identical,
§B gates re-pass coord-equivalent, Test B′ coalesces cleanly. `BoA-v1ref` byte-clean; production (gliding/
contractile/…) byte-unchanged. Plan: `INC6C_CONVENTION_SWAP_SURVEY.md`; root cause:
`INC6C_BINDING_ORIENTATION_DIAGNOSIS_FINDINGS.md`.

## What changed — the §B node/growth/nucleation subsystem only (atomic, one pass)
Files touched: `GrowthSystem`, `NodeNucleationSystem` (production systems, both gated to node scenes via
`seedNode≥0`), `TestBScprHarness`, `GrowthHarness`, `NodeNucleationHarness` (the node-subsystem harnesses).
**No §A shared system (DerivedGeometry / Binding / CrossBridge / Chain / FrameWriter) and no bind gate
touched.** The node-attached filament's `uVec` now points **INWARD** (toward the node); the barbed end is
**end2** (at the node), the pointed end is **end1** (outward).

| site | change | physical effect |
|---|---|---|
| `NodeNucleationSystem.emit` | `reqUVec = −dir` (was `+dir`); coord unchanged | seed uVec inward; barbed end2 at node (coord-bit-identical) |
| `NodeNucleationSystem.seedTether` | tethered end = `end2 = coord+½L·uVec`; torque arm `R = +½L·uVec` | same physical node-attached point + force (bit-identical) |
| `GrowthSystem.grow` | coord shift `−½·mono·uVec` (was `+`) | end2 (node) fixed, end1 extends outward — **same world growth** (sign cancels uVec flip ⇒ coord-bit-identical) |
| `GrowthSystem.markSplits` | child axial `shift` negated | child (outer half) at the IDENTICAL world position |
| `GrowthSystem.splitWire` | read Mold from `end1Nbr`; parent keeps **end2** fixed (shift negated); rewire `G.end1↔C.end2`, `C.end1→Mold` | the 3-slot rewire mirrored ⇒ valid linear chain, coord-bit-identical |
| `placeAimedChain` / warm-start | `setUVec(−dir)`; chain wiring nodeward=end2 / outward=end1 | aimed filament uVec inward (coords unchanged) |
| `filNodeOf` | walk `end2NbrSlot` toward the node tip | follows the node-side (end2) chain direction |
| harness gates (Growth/NodeNuc/TestB) | end1↔end2 label swaps in assertions + the `[orient]`/`[diag]` logs | gates re-pass under the new convention |

**The fix is upstream, not in the gate.** Once nucleation/growth/placement produce an **inward** node-filament
`uVec`, the existing `rodDotFil ≥ 0` polarity gate computes `rodDotFil < 0` for a node's own outward myosins ⇒
self-binding rejected, exactly as v1. The `seedNode≥0` tip-exclusion (keyed by `seedNode`, not by end) is
untouched and still fires.

## Coord-bit-identity — the key safety property, realized
Every §B coord-moving operation has its sign flipped together with the `uVec` flip, so the two cancel and the
**physical coordinate is unchanged**; only the `uVec` representation (negated) and the end1/end2 labels (swapped)
differ. Verified directly:
- **Growth GPU CPU≡GPU:** split lifecycle **bit-identical**, `max|Δcoord| = 1.40e-09 µm`.
- **GrowthHarness gates re-pass:** drag maxRel 1.1e-7; rate P_emp 0.1738 (0.1%); first-order ratio 2.005;
  contour 0.086→2.50 µm (29×), tip end2 held 4.2e-5 µm from node; **no-op-when-off `max|Δcoord| = 0.0`**.
- **The split@64 3-slot chain rewire (highest risk):** lone-tip AND inserted-between-Mold cases both produce a
  **valid linear chain**, monomers **conserved 64→32+32**, node end fixed, child outward, **CPU≡GPU
  bit-identical lifecycle**. The mirror is correct.
- **Nucleation tether double-ref:** `|F| = 4.698e-12 N` vs v1 ref `4.698e-12` (rel 2.06e-08) — the tether force
  is **physically identical** (same node-attached point); toward-node TRUE; relaxes to 1.9e-10 µm.

## Three-tier regression — all green
1. **§A bit-identical (leak detector).** The non-node assays call **byte-unchanged** code (they don't invoke
   growth/nucleation) ⇒ bit-identical by construction; all re-run **PASS**: gliding (−4.185 µm/s, avgBound
   7.61), contractile, dimer, minifilament, motor, crosslinker (5a–5c-iii), dimer-glide, mini-glide,
   cross-bridge, stroke. **No leak** (git scope: only the 5 node/growth files + JOURNAL changed).
2. **§B node subsystem (gates re-pass, coord-equivalent).** growth ✓, nodenuc ✓ (incl. the seed-damping gate,
   once its wander metric was pointed at end2), filbirth ✓ (bit-identical — convention-neutral), node Stage A ✓
   (bit-identical — its test filament is not node-nucleated). CPU≡GPU bit-identical lifecycle on growth/nodenuc/
   node device paths.
3. **Behavioral target — Test B′ (`./run_testb.sh -aimed`).** Self-grab **GONE**: capture-phase self-capture
   count **0.00**, transmitted force **0.000 pN** (was 12.4 pN; self/cross 1.07 → **0.00**); `[diag]` residual
   self-captures **0**. Cross-capture **survives + stronger/legible** (peak **10**, avg **4.71**; was 2.92).
   Nodes **approach beyond noise**: 0.600 → **0.483 µm** (Δ 0.117 µm ≈ 27× the 0.0043 µm Brownian rms) ⇒
   `STAGE 1 demonstrates SCPR capture-and-pull`. CPU≡GPU agree. The `[orient]` log: **SELF n=0** (no self-grab);
   conventions now agree so v1-would-admit ≡ v2-would-admit. This reproduces v1's clean-coalescing twoNodeFormin
   (no self-grab) — **with no gate edit.**

## Notes / flags
- **`[orient]` CROSS "would-admit 1/6" is a stale-pose artifact, not a disagreement.** The log recomputes the
  gate at the FINAL-step pose; binding gates the INITIAL bind, and bonds persist as geometry drifts past the
  align threshold. With conventions now identical, v1 and v2 compute the SAME value — the "1/6" is simply how
  many currently-bound bonds still satisfy a fresh gate at the sampled step.
- **Post-min overrun** (nodes drift apart after the closest approach) is the **unchanged, OUT-OF-SCOPE**
  monotonic-growth / no-depolymerization artifact (Test B′ is the initial-approach test; sustained contraction
  needs turnover). Not introduced by the swap.
- **Cross-capture geometry is now v1-like:** the partner's near-facing heads capture the incoming filament
  (uVec inward toward its node); the captor's myosin walks toward the foreign filament's barbed end (at the
  foreign node) ⇒ contraction. (Pre-swap it required far-side overshoot — itself a symptom of the flip.)

## Bottom line
v2 is now **uniformly barbed = end2** (matching v1 — ending the v1↔v2 confusion). The node self-grab is
**root-caused** (the inc-6c polymerization barbed=end1 divergence) and **fixed at the root** by realigning the
node subsystem's filament polarity to inward — rejected by v2's own **unmodified** gate. §A bit-identical, §B
coord-equivalent with gates re-passing (incl. the split rewire), Test B′ coalesces cleanly like v1's
twoNodeFormin. The convention is settled before the contractile ring builds on it.
</content>
