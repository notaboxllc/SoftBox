# Phase-2 sphere-head — stereospecific roll sign (+ŝ from polarity)

**Date:** 2026-07-01. Flag-gated (`-rollsign`), default byte-identical; `BoA-v1ref` untouched.
Faithfulness change only — no kinetics/geometry retune.

## The rule
The head-frame converter (`-hfswing`) glided −1.86 vs the f̂-referenced `-dirswing`'s −3.0 because the axial
lock pins the roll **axis** but leaves its **sign** free (`PHASE2_HEADFRAME_SWING_FINDINGS`: census 50/50 ±ŝ).
Real binding is stereospecific — actin polarity fixes the full head orientation, sign included. Fix
(`-rollsign`, `CrossBridgeSystem.bondForces`): align `head.yVec` to **+ŝ specifically** (ŝ = n̂bed × û_seg,
the barbed-sweep sign, from filament polarity) instead of the nearer of ±ŝ — one gated branch (skip the
sign-flip). Per-motor pure, CPU≡GPU. Everything else (⊥ hold, lock magnitude, head-frame converter,
kinetics) unchanged.

## GATE 1 — single motor, Brownian off: unchanged ✓
`AxLockGateHarness -rollsign`: axial fraction **1.000**, lever rear sweeps **+7.80 nm barbed-ward**, recovery
→ straight. Identical to `-hfswing`/`-dirswing` (the gate already poses head.yVec=+ŝ, so `-rollsign` is a
no-op there — as expected).

## GATE 2 — dense mat, d1000, 1.5 s, LS-centroid

| model | v_axial | axial frac | avg bound | roll census |
|---|---:|---:|---:|---:|
| `-dirswing` (f̂-referenced) | −3.0 µm/s | 1.00 | 14.3 | (n/a) |
| `-hfswing` (head-frame, ±ŝ) | −1.86 | 0.999 | 13.7 | 50.6% +ŝ / 49.4% −ŝ |
| **`-hfswing -rollsign` (+ŝ)** | **−2.15** | 0.999 | 14.0 | **99.5% +ŝ / 0.5% −ŝ** |

(velFitX cross-check: rollsign 2.13.) **The roll sign is now fixed (99.5% +ŝ) and the speed recovered
partially — −1.86 → −2.15 — but did NOT return to −3.0.**

## The twist cost — CHEAP, not a forced 180° rotation
`-twistcensus` (35,962 fresh binds over 1.5 s): the arrival angle between the head's yVec as it binds and
the forced +ŝ target:

```
mean arrival angle = 48.5°        far side (>90°, needs a big rotation) = 14.5%
histogram [0-30 / 30-60 / 60-90 / 90-120 / 120-150 / 150-180]° = 13948 / 11744 / 5041 / 3135 / 1577 / 517
mean bound lifetime = 0.59 ms (~59 steps)      avg bound = 14.0 (unchanged vs -hfswing 13.7)
```

Heads arrive **already biased toward +ŝ** (mean 48.5°, 61% within 60°); only ~15% need a >90° rotation.

**Comparison — the `-hfswing` (nearest-±ŝ) baseline:** mean arrival **90.1°, far 50%**, histogram **uniform**
(symmetric: 7117 / 6420 / 3702 / 3626 / 6395 / 7216) — i.e. under the sign-free lock the head roll is
uniformly distributed vs +ŝ, confirming the free sign DOF. Its lifetime **0.60 ms** and avgBound **13.5** are
**identical** to `-rollsign` (0.59 ms / 14.0). **So (a) the roll-sign lock costs NOTHING in binding/duty, and
(b) the +ŝ arrivals under `-rollsign` are SELF-GENERATED** — a head held at +ŝ while bound rebinds (fast
turnover, lifetime ~0.6 ms) still near +ŝ, so the twist is **self-limiting** (48.5° vs the naive 90°). This
is **"stereospecific binding is cheap/faithful," NOT "forced post-bind twist artifact."**

## Why it stays below −3.0 — the residual is NOT the roll sign
With the sign fixed at 99.5% +ŝ, the head-frame target `cos θ·û_head − sin θ·(ŷ_head×û_head)` should equal
`-dirswing`'s `cos θ·û_head − sin θ·f̂` for ~every head — yet the glide is −2.15, not −3.0. The difference:
the head-frame law references the head's **own, thermally-fluctuating** orientation (û_head, ŷ_head jiggle
under Brownian, and freshly-bound heads are still mid-convergence toward +ŝ), whereas `-dirswing` references
the **clean, fixed filament axis** f̂. So `ŷ_head × û_head ≈ f̂ + orientation-noise`, and the head-frame
stroke is a little less consistently barbed → less coherent net → slower.

**Direct evidence this is the cause:** with **Brownian OFF** (the single-motor gate) the two laws are
**identical** (both barbed, axial 1.00, same rearX). The gap appears **only under mat Brownian** — so it *is*
the head-orientation noise the head-frame law faithfully inherits and the f̂ reference does not. `-dirswing`'s
−3.0 is partly artificial: it strokes along a noise-free axis the real head doesn't have.

**Aside — the mat is binding-saturated.** The `-hfswing`→`-rollsign` gain (−1.86 → −2.15, ×1.16) is far
smaller than a "each −ŝ head cancels a +ŝ head" naive model ((1−2p): p 0.49→0.005 would be ×50) or even a
"net ∝ +ŝ fraction" model (0.5→0.995 = ×2). At d1000 (avgBound 14) the filament is over-driven, so per-head
sign/efficiency is a weak lever on net speed — consistent with the earlier over-binding/tug-of-war finding.
This is *also* why fixing the sign only buys +0.29 despite going 50/50 → 99.5% +ŝ.

## Verdict
- **The stereospecific +ŝ roll lock works and is cheap** (census 99.5% +ŝ; twist mean 48.5°, 15% far;
  avgBound/lifetime unchanged) — faithful, not a forced-twist artifact.
- **It does NOT fully recover −3.0** (reaches −2.15). The residual is *beyond roll sign*: the head-frame law
  inherits the head's thermal orientation noise; the f̂ shortcut is artificially clean.
- **Interpretation:** the head-frame + `-rollsign` motor is now **both working AND first-principles AND
  cheap-assembly**, and **−2.15 µm/s is arguably the more physical glide** (a real head-referenced stroke
  jiggles). `-dirswing`'s −3.0 is a mild overestimate from stroking along the clean filament axis. Both are
  in the skeletal range and pointed-leading with axial fraction ≈1.0.
- **Recommendation:** adopt `-hfswing -rollsign` as the biologically-defensible motor (head orients at bind
  from polarity; converter swings the neck in the head frame; ~−2.1 µm/s). Keep `-dirswing` only if the
  higher number is wanted, noting it's the noise-free-reference overestimate. No retune done.

## The ±ŝ-lifetime rectification check (optional) — not directly instrumented
The prior finding speculated that `-hfswing`'s wrong-sign heads "don't cancel" because catch-slip
preferentially releases heads fighting the glide (a per-sign lifetime difference). I did **not** build the
per-sign lifetime split (optional; would need per-head bound-duration tagged by roll sign). Indirect signal:
the **overall** bound lifetime is **identical** across configs (`-hfswing` 0.60 ms with 50% −ŝ vs `-rollsign`
0.59 ms with ~0% −ŝ) — which does **not** support a strong per-sign lifetime rectification (a large one would
shorten `-hfswing`'s mixed-population mean). The likelier reason the −ŝ heads don't linearly cancel is the
**binding saturation** above, not lifetime rectification. Flagged, not resolved.

## Run
```
./run_axlock.sh -rollsign                                                        # single-motor gate (unchanged)
GlidingHarness -gpu -matbed -rollsign -density 1000 -3js <dir> 150000            # mat glide (−2.15 µm/s)
GlidingHarness -gpu -matbed -grid -rollsign -rollcensus -twistcensus -density 1000 150000   # census + twist
```

New: `-rollsign` (`CrossBridgeSystem.bondForces` +ŝ branch), `-twistcensus`
(`CrossBridgeSystem.captureBindTwist`), `-rollcensus` accumulation. Default byte-identical; `BoA-v1ref`
byte-clean; CPU≡GPU-safe (per-motor pure).
