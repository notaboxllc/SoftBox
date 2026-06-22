# Increment 6 glide integration (part 1) — dimer-glide: findings

**Status: DONE (2026-06-17). All 4 gates PASS on GPU + CPU.** The dimer is now a *functional* two-head
motor. v2 = SoftBox (`softbox/`); v1 oracle = `BoA-v1ref` (read-only, byte-clean). Reuses the 4b-iii
pinned-filament setup (`MotorStrokeHarness`) with dimers + the binding-gated coupling.

## What this is
A **free dimer** (two motors held by the 6a `DimerCoupling`, NO anchor) whose two heads **bind and walk**
on a **pinned filament** via the existing `CrossBridge` (unchanged), translocating under the collective
head force. The three genuinely-new things vs 6a/6b (which had free heads, the binding gate always-true):
1. **Heads bind/walk** via `CrossBridge` (head self-write + seg gather, 4b-ii — unchanged, additive).
2. **🚩 Binding-dependent coupling gate** — the dimer lever-align is now `boundSeg`-gated:
   **SUPPRESSED when BOTH heads are bound** (v1 `MyosinDimer.java:276`: `!myo1.onFil | !myo2.onFil`),
   else it fires. The rod-couplings are **unconditional** (v1 has no binding gate on them — verified:
   `MyosinDimer.java:275-283` is the *only* binding gate in the dimer coupling). `onFil ⟺ boundSeg ≥ 0`.
3. **The full force-transmission chain** end-to-end: a bound head's cross-bridge reaction → head body →
   J1 → lever → J2 → rod → dimer rod-coupling → the partner rod (only validated piecewise before).

## The one new physics — the binding gate (one implementation, gated)
`DimerCouplingSystem.couple` gained one parameter, `boundSeg`, and one guard:
`if (par && !(boundSeg[mA] ≥ 0 && boundSeg[mB] ≥ 0)) { lever-align }`. **This is bit-identical for 6a/6b**
(their `boundSeg` is all `FREE_BINDABLE = -1` ⇒ `bothBound` always false ⇒ align always fires, exactly the
pre-glide always-on path). 6a (6 gates) and 6b (5 gates) **re-ran bit-identical PASS** after the change —
the one-physics-implementation invariant holds (no forked gated/ungated copy).

## Per step (free dimer, gated coupling)
`[cycle] → zero → joints(J1/J2) → dimerCouple(boundSeg-gated) → bond(CrossBridge) → applyHead → integrate
→ derive → register → fil-gather`. **No anchor** (the dimer coupling holds the structure; the cross-bridge
tethers it to the filament).

## Validation (co-developed small-scale vs `BoA-v1ref`, not fixtures)

| # | gate | result |
|---|---|---|
| **#1** | **force transmission** through bound heads: fil gather==`bruteGather` + momentum + CPU≡GPU | 24/24 heads bound; fil gather==brute **bit-identical (Δ=0.0)**; momentum \|Σ motor force + Σ fil force\| = **2.0e-19 N** (≈0 — the cross-bridge is the only cross-entity force, joints+dimer-coupling internal); **CPU≡GPU** (300 deterministic steps) max\|Δ pose\| = **4.4e-8 µm** (float32 last-bit). |
| **#2** | **binding gate** bit-for-decision vs v1 (both-free / one-bound / both-bound) | align **fires** both-free + one-bound (lever torque 5.408e-20 vs v1 ref, **rel 4.6e-10**); **suppressed** both-bound (lever torque exactly **0.0**). Decision + arithmetic both match v1. |
| **#3** | **two-head translocation** (free dimer, cycling) — emergent | dimer motor-COM **Δx = +9.38 nm** over 20k steps (avgBound 24/24): the free dimer walks **+x toward the actin plus-end** — the Newton reaction to 4b-iii's −x filament force (the −x is the *filament's* glide in a surface assay; the free *motor* walks the opposite way). Emergent, physics cross-checked; v1 informational. |
| **#5** | **all-OFF ≡ HEAD** | dimer coupling off ⇒ the single-motor / 4b-iii path **bit-identical (Δ=0.0)**; control: dimer ON vs OFF differs 2.7e-3 µm (the coupling is real, not a silent no-op). |

(#4 CPU≡GPU is folded into #1's deterministic cross-check.)

**Sign note (worth flagging):** the free dimer walks **+x** (toward the actin plus-end), NOT −x. 4b-iii's
"−x glide" is the force the *anchored* motors pulse into the *filament* (the filament glides minus-end-
leading in a surface assay); by Newton's 3rd law a *free* motor feels the +x reaction and walks toward the
plus-end — the biological myosin-II direction. Both are the same Newton pair; the initial gate had the sign
backwards and was corrected.

## Files
New: `DimerGlideHarness.java`, `run_dimerglide.sh`. Modified (one-impl binding gate): `DimerCouplingSystem`
(+`boundSeg` param + the gate), `MyosinDimerHarness` / `MiniFilamentHarness` (pass `mot.boundSeg` — both
re-validated bit-identical). `CrossBridge` byte-unchanged (reused). Production / `GlidingHarness`
byte-unchanged; `BoA-v1ref` byte-clean. Structures default-off in production.
```
./run_dimerglide.sh              # GPU + CPU cross-check: #1 transmission, #2 gate, #3 walk, #5 all-OFF
./run_dimerglide.sh -cpu         # CPU runner only (triage)
./run_dimerglide.sh -3js threejs_dimerglide   # viewer (free dimers walking on a pinned filament)
```

## Next
- **Minifilament-glide** (part 2): 32 heads + the backbone gather **under load** — the 6b single-ended
  gather now carrying real cross-bridge force from bound heads through the tether to the backbone.
- Then dynamic minifilament assembly / `myoMiniLifetime`, the contractile geometry, and 6c nodes.
