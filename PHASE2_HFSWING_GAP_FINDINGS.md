# Phase-2 sphere-head — is the −2.15 (hfswing+rollsign) vs −3.0 (dirswing) gap real head-noise?

**Date:** 2026-07-01. Diagnostic only — no kinetics/geometry retune. Flag-gated (`-headlock`, `-full`
existing), default byte-identical; `BoA-v1ref` untouched.

**Every speed below is from the full long gliding assay:** LS-centroid drift along the filament axis over the
**on-bed, ≥1 s steady window** (`LONG_ROW` estimator = PROPER_SPEED_ANALYSIS §2; window auto-restricted to
samples with the filament fully over the bed; `-full` 14×2 bed where the `-matbed` runway truncates). The
`LONG_ROW` estimator was cross-checked against the frame-based PROPER_SPEED python: rollsign d1000 −2.11
(LONG_ROW) vs −2.15 (frames); dirswing d1000 −2.96 over a 0.89 s window (SHORT — flagged, rerun on -full).

## Release pathway (stated for every config — the lurking confound)
**All three sphere-head configs (`-dirswing`, `-hfswing`, `-hfswing -rollsign`) use the SAME release: the
default `catchSlipRelease` (force-dependent Guo–Guilford catch-slip) + the standard 4-state
`NucleotideCycleSystem.cycle` (NONE→ATP→ADPPi→ADP, load-gated ADP→NONE).** None set LYMN_TAYLOR / ATP_RECHARGE
/ TAU_AVG / CONFIG1. So the −3.0 / −2.15 spread is a **pure stroke-law difference** at identical release/
kinetics — comparable. (BoA's fixed-head motor is also catch-slip + biochem cycle, §9.6–9.7; its −3.96 /
−2.09 numbers are different geometries/measurements, not a different release. A clean v2→v1 catch-slip-only
map is a later task.)

## TEST 1 — head-lock stiffness sweep (the direct test of the head-noise claim)
`-hfswing -rollsign`, head-lock coeff (xbParams[2], the F9 ⊥ hold + F10 axial/roll lock) scaled ×1/×2/×4/…
via `-headlock`; the stroke coeff (swingParams[0]) is untouched. Full assay, `-full`, density = d500 (mid, avgBound ~10, non-saturated).

| head-lock ×mult (coeff) | v_axial | avg bound | axial frac | note |
|---:|---:|---:|---:|---|
| `-dirswing` reference | −2.43 | 10.4 | 0.99 | the f̂ target |
| ×1 (0.4, current) | −1.56 | 8.7 | 0.99 | |
| ×2 (0.8) | −1.02 | 7.1 | 1.00 | stable, but SLOWER + fewer bound |
| ×4 (1.6) | +0.14 | 1.7 | 0.87 | binding collapsing (whipping) |
| ×8 (3.2) | +0.28 | 0.2 | — | binding gone |

**Verdict: the thermal-head-noise claim is REFUTED.** If the gap were head-orientation jiggle, stiffening
the lock would move `-rollsign` **toward** −2.43. Instead it moves the **wrong way** — monotonically slower
(−1.56 → −1.02 → +0.14), and **avgBound collapses** (8.7 → 7.1 → 1.7 → 0.2). This is *not* the clean
"pin-absorption" failure the task flagged (there avgBound would *hold* while speed fell) — it's worse:
beyond the compliant k≈0.4 the head-lock torque overshoots (explicit-stiffness whipping) and **detaches
heads**. The compliant k=0.4 is already at/near the speed optimum; you **cannot** stiffen toward −3.0. So the
−2.15/−3.0 gap is **not a reducible head-orientation-noise effect.**

**Convergence (the run-length criterion, not a fixed sim time):** refitting the LS-centroid at several
(t₀, t_end) on the on-bed frames, the slope settles by a **~0.7–0.9 s window** — dirswing d1000
−2.86(0.5 s)→−3.0(0.7–0.9 s), rollsign d1000 −1.90(0.5 s)→−2.1(0.8–0.9 s). So the reported numbers are
**statistically settled well under 1 s**; longer runs don't move them. (All speeds here are from the
settled window, not a fixed 1.5 s.)

## TEST 2 — the two laws vs density (is the gap a saturation artifact?)
Full assay, LONG_ROW, n=1 (d250 `-matbed`; the trend + the persistence of the gap are the point):

| density | `-dirswing` | `-hfswing -rollsign` | ratio dir/roll | avgBound | window |
|---:|---:|---:|---:|---:|---|
| 250 | −1.90 | −1.08 | 1.76× | 6.4 | OK 1.3 s |
| 500 | −2.48 | −1.80 | 1.38× | 10.7 | OK 1.0/1.3 s |
| 750 | −2.69 | −1.55 | 1.74× | 12.8 | OK 1.0/1.3 s |
| 1000 | **−3.0** (−2.96 / 0.89 s `-matbed`; −3.0 from the fully-on-bed frame fit) | −2.11 | 1.42× | 14.3 | roll OK; dir → -full/frames |

**Two findings that correct the prior "saturation" reading:**
1. **`-dirswing` rises MONOTONICALLY with density** (−1.90 → −2.48 → −2.69 → −3.0) — it is **not saturated**
   at d1000; higher density is still faster.
2. **The gap PERSISTS at every density — ~1.4–1.8×, including sparse d250 (avgBound 6.4)**, far from any
   tug-of-war. So the −2.15/−3.0 gap at d1000 is **NOT a saturation/density artifact** — it's a genuine
   per-head stroke-law difference across the whole range. (This overturns my earlier "binding-saturated,
   ×1.16" aside from `PHASE2_ROLLSIGN_FINDINGS` — at d250 the gap is if anything *larger*, 1.76×.)

There is no clean velocity peak in 250–1000 (dirswing monotonic); TEST 1 is run at **d500** (mid, avgBound
~10, unambiguously non-saturated, windows valid).

## Resolved reading of −2.15 vs −3.0
Both proposed explanations are **refuted:**
- **NOT saturation** (TEST 2): the gap persists ~1.4–1.8× across d250–1000, including sparse d250
  (avgBound 6.4) far from tug-of-war. This overturns the earlier "binding-saturated, ×1.16" aside.
- **NOT a reducible head-orientation noise** (TEST 1): stiffening the head lock moves `-rollsign` the wrong
  way and collapses binding; the compliant lock is already near-optimal. So −3.0 is **not** recoverable by
  cooling head jiggle — the prior docs' "just head thermal noise, cool it and it converges" inference is
  **wrong** (the Brownian-off single-motor *identity* still holds — the laws are identical without dynamics —
  but the manifest gap under dynamics is not a stiffness/temperature knob).

**What it is:** a genuine, density-independent, **irreducible** per-head difference between the head-frame
stroke law and the f̂ shortcut. It has two components — the head-frame law both **binds fewer heads**
(d500 `-full`: avgBound 8.7 vs dirswing 10.4, ≈1.19×) **and strokes slightly less effectively per head**
(the residual ≈1.31×) — because its converter references, and reacts on, the head's own dynamically-imperfect
orientation, whereas `-dirswing` references an **external, clean, always-correct** filament axis that both
aims every stroke perfectly and keeps the head's reaction aligned on actin. Crucially, this coupling is
**not a tunable inefficiency** — the one knob that could tighten it (head-lock stiffness) *backfires*.

**Verdict on −2.15 vs −3.0:** the gap is a **real cost of biological fidelity**, not an artifact, not
saturation, and not a fixable inefficiency. **−2.15 µm/s (`-hfswing -rollsign`) is the honest glide of the
head-referenced motor;** `-dirswing`'s −3.0 is faster only because the external f̂ reference is a modeling
convenience (a perfect, head-independent stroke axis) the real head-referenced motor structurally lacks and
cannot recover. Release pathway is **identical** (catch-slip + standard cycle) for every config, so this is a
pure stroke-law difference, not a release confound.

**The gap is robust (n=3, d500):** `-dirswing` **−2.46 ± 0.02**, `-hfswing -rollsign` **−1.56 ± 0.10** (SEM),
ratio **1.58×**, separated by >5σ. (rollsign carries more seed scatter — consistent with the head-frame law
being the noisier one, but the mean gap is unambiguous.) d1000: dirswing −3.0 / rollsign −2.11.

## Run
```
GlidingHarness -gpu -full -grid -rollsign -headlock 2 -density <d> 150000   # stiffness sweep (LONG_ROW)
GlidingHarness -gpu -full -grid -dirswing -density <d> 150000               # two-law comparison
```
