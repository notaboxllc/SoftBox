# Canonical model — velocity–density sweep at coltol = 8 nm (the azimuthally-unaware pre-refinement baseline)

**Date:** 2026-07-09 · **Branch:** dt-convergence-study · **Runner:** GPU (springs = transcendental-free ⇒
GPU-trustworthy) + 1 CPU basin-arbiter point at d4000.
**Status: PRE-REFINEMENT BASELINE (a control, not a biology validation).** This is the *current
azimuthally-unaware* canonical model's own gliding envelope, measured at a tighter capture radius before jba
adds specific (helical/azimuthal) actin binding sites — a model-physics change that will re-baseline gliding.
Read it as "the model's own ceiling," not "does it match experiment."

Companion to `docs/DENSITY_SWEEP.md` (the coltol = 10 nm first stab, d100–d2000). coltol = 8 nm is a **tighter**
capture radius ⇒ a **new, lower-engagement curve**, not an extension of the coltol = 10 curve; the low points
are re-run at 8 nm for a self-consistent curve, and the range is extended to d8000.

---

## Method

- **Canonical invocation (bare — model is the post-collapse default):**
  `scripts/run_gliding.sh -gpu -full -grid -coltol 8 -density <D> -seed <s> 60000`
  — springs (`-pairsprings -alignsprings -structsprings`), Lymn–Taylor (`-lymntaylor`), ADP·Pi-only binding
  (`-adppibind`), and the coupled implicit cross-bridge (`-xbimplicit2`) are **all DEFAULT-ON**; only `density`
  and `coltol` are passed explicitly. No model flags, no code change beyond the coltol/density edit to the
  driver (`scripts/run_canonical_density_sweep_coltol8.sh`).
- **Densities:** 100 / 250 / 500 / 1000 / 2000 / 4000 / 6000 / 8000 µm⁻², **coltol = 8 nm** fixed, **3 seeds**,
  **M = 60000 (0.6 s @ dt = 1e-5)**, velFitX/avgBsteady over the 2nd-half steady window. Cheapest-first.
- **CPU basin arbiter at d4000** (a *high-density* point — the standing GPU-number-trust rule, doubly important
  in the dense collective-load regime).
- Raw: `RUN_LOGS/2026-07-09_canonical_density_sweep_coltol8.txt`.
- **Production-safety:** aorus confirmed free (GPU idle, no concurrent CC/Java) before launch; `BoA-v1ref`
  untouched; single job. All 24 GPU runs completed clean — **no NaN / blow-up / exception at any density.**

---

## The curve — velFitX and avgBsteady reported separately (±SEM, 3-seed)

| density (µm⁻²) | nMot | **velFitX** (µm/s) mean±SEM | **avgBsteady** mean±SEM | per-bound | inst (µm/s) | Δ velFitX vs prev |
|---:|---:|---:|---:|---:|---:|---:|
| 100  | 2 674   | **0.133 ± 0.050** | 0.137 ± 0.028 | 0.97 | 6.42 | — |
| 250  | 6 685   | **0.323 ± 0.082** | 0.349 ± 0.045 | 0.93 | 6.43 | +143 % |
| 500  | 13 370  | **1.011 ± 0.120** | 1.024 ± 0.088 | 0.99 | 6.51 | +213 % |
| 1000 | 26 740  | **2.687 ± 0.053** | 2.905 ± 0.087 | 0.93 | 6.79 | +166 % |
| 2000 | 53 480  | **4.389 ± 0.105** | 5.424 ± 0.124 | 0.81 | 7.47 | +63 % |
| 4000 | 106 960 | **6.439 ± 0.128** | 10.686 ± 0.180 | 0.60 | 8.62 | +47 % |
| 6000 | 160 440 | **7.948 ± 0.200** | 15.802 ± 0.027 | 0.50 | 9.81 | +23 % |
| 8000 | 213 920 | **9.384 ± 0.212** | 20.206 ± 1.114 | 0.46 | 11.49† | +18 % |

(velFitX and avgBound reported **separately** — the arc's recurring trap; per-bound = velFitX/avgB shown as a
derived aid. †d8000 `inst` is inflated by the coverage-violated seed1 — see the coverage caveat below.)

### Plateau / turnover behavior — **no plateau, no turnover; a decelerating climb toward an unreached Vmax**

- **velFitX rises monotonically the entire way, 0.13 → 9.38 µm/s (72×),** with a cleanly **decelerating slope**
  (+143/+213/+166/+63/+47/+23/+18 %). It does **not** flatten and does **not** roll over through d8000. The
  curve is bending toward a plateau it **has not reached** — Vmax lies **above ~9.4 µm/s (beyond d8000)**.
- **The saturation mechanism is a per-head EFFICIENCY collapse, not motor slowdown and not instability.**
  Above d2000, **per-bound falls sharply: 0.81 → 0.60 → 0.50 → 0.46** (vs a flat ~0.95 through d1000). Meanwhile
  **avgBound keeps climbing ~linearly with density** (0.14 → 20.2, tracking `meanReach` 0.2 → 24.6, with ~82–88 %
  of reachable motors bound). So each density doubling keeps recruiting bound heads, but each head converts
  **less** of its motion into net directed glide — the classic **co-bound tug-of-war**: densely co-bound heads
  on the same/adjacent segments fight each other. velFitX saturates because efficiency drops, **while
  engagement (avgBound) does not.** This is the headline finding of the extended range (the coltol = 10 first
  stab stopped at d2000, where per-bound was still ~flat and this collapse had not yet appeared).
- **Kinetics are density-independent** (confirming the saturation is collective mechanics, not a rate change):
  across the whole range, **dwell ≈ 0.60 ms, duty ≈ 0.85, detach ≈ 1600 /s** are all flat (`STATS_STEADY_ROW`).
  The per-head bound lifetime and duty cycle do not change with density; only the *number* co-bound and their
  mutual interference do.
- **`inst` (instantaneous speed-when-moving) is NOT density-independent here.** It is ~flat 6.4 → 7.5 through
  d2000 (the classic gliding-assay signature, matching the coltol = 10 story), but **climbs to 8.6 / 9.8 / 11.5**
  at d4000/6000/8000. Interpret with care: at high density the motion is fast-but-jittery (high `inst`, low
  net per-bound), consistent with tug-of-war; and the d8000 `inst` is partly the coverage-violated seed (below).

### Comparison to the coltol = 10 first stab (overlap region d100–d2000)
| density | velFitX coltol10 | velFitX coltol8 | avgBsteady coltol10 | avgBsteady coltol8 |
|---:|---:|---:|---:|---:|
| 100  | 0.143 | 0.133 | 0.165 | 0.137 |
| 250  | 0.473 | 0.323 | 0.574 | 0.349 |
| 500  | 1.100 | 1.011 | 1.435 | 1.024 |
| 1000 | 2.825 | 2.687 | 3.317 | 2.905 |
| 2000 | 3.821 | 4.389 | 5.342 | 5.424 |

The tighter 8 nm capture radius yields **modestly lower engagement at low–mid density** (avgBound ~15–40 % lower
at d250–d1000) as expected — fewer motors fall within reach. The effect is small and shrinks with density; by
d2000 the two curves converge (the coltol = 10 d2000 mean was dragged down by a low seed; the good seeds agreed).
Seed scatter is **tighter** in this batch at d1000–d6000 than the coltol = 10 first stab showed.

---

## Coverage — the top of the range is a MEASUREMENT-GEOMETRY limit (not a physics limit)

`fullMat` (does the motor bed cover the whole filament through the run) holds `YES` from d100 through d6000 and
on **2 of 3 d8000 seeds**. **At d8000 seed1, `fullMat = VIOLATED` (runMinMargin = −0.204 µm):** the filament's
leading edge ran ~0.2 µm off the +x bed edge. The cause is geometric, not numerical — at ~9.4 µm/s net over a
0.6 s window the filament translates ~5–6 µm, and at extreme density the off-axis wander (that seed showed
netXY 11.2 > velFitX 9.6) pushed the leading edge past the strip end. Trailing margins stay huge; the violation
is on the leading edge only.

**Consequence:** **d8000 is the practical top of the reliably-measurable range with this bed length / 0.6 s
window** — the d8000 numbers are usable (the three seeds agree: velFitX 9.55/9.64/8.96) but the top point is
coverage-marginal. A longer motor bed or a shorter window would be needed to push density higher cleanly.
d4000 and d6000 are fully covered. (This is a real caveat surfaced by the `COV_ROW` instrumentation; the live
monitor keyed on `fullMat=NO` and missed the `fullMat=VIOLATED` label — caught in the post-run log scan.)

---

## Collective-load stability — STABLE throughout; `-segimplicit` NOT needed

The high-density regime (avgBound up to ~21 co-bound heads per filament at d8000) is exactly the
intense-collective-load regime where the explicit remainder of `-xbimplicit2` could hit the EOM instability
(`dt_crit ∝ γ/Σk`). **It did not.** No NaN, no Infinity, no velFitX turnover, no avgBound runaway at any density
through d8000. The default coupled implicit cross-bridge (`-xbimplicit2`, which handles the within-segment
collective load) was **sufficient on its own** — the diagonal collective cure `-segimplicit` (kept opt-in for
the ring) **was not needed and was not fired.** Low-duty gliding, even at d8000, stays below the collective
blow-up threshold (consistent with the `-ktotcensus` finding that gliding is sub-threshold for K_tot blow-up).

---

## CPU≡GPU basin agreement at d4000 (the dense-regime trust check)

**20k-step CPU arbiter (seed0, d4000, coltol = 8) vs GPU:** completed in 54:51 (~6 steps/s), `fullMat = YES`.

| | velFitX | avgBsteady | avgBound (whole-run) |
|---|---:|---:|---:|
| GPU d4000 (3-seed mean) | 6.439 | 10.686 | 10.686 |
| GPU d4000 seed0 (60k) | 6.575 | 11.020 | 10.780 |
| **CPU d4000 seed0 (20k arbiter)** | **6.469** | 9.188 | 9.920 |

**velFitX matches the GPU 3-seed mean to 0.5 %** (6.469 vs 6.439 — landing right on the mean, well inside the
GPU seed spread 6.18–6.58 / SEM 0.128). avgBound is ~7 % lower on the CPU run (9.92 vs 10.69 whole-run; 9.19 vs
10.69 steady) — a **window-length transient**, not a basin flip: the CPU arbiter's steady window is the shorter,
earlier 0.1–0.2 s (2nd half of 20k) vs the GPU's 0.3–0.6 s, and in the dense regime avgBound builds toward
steady over the run. A **flipped LOW basin would roughly halve both channels** (velFitX ~3, avgBound ~5) — the
opposite of what is seen. **Verdict: same HIGH basin, confirmed at the dense d4000 point; the GPU curve is not
reading a flipped basin. The curve is trustworthy.** (The preliminary 2000-step probe agreed: avgBound matched
to 0.2 %.)

---

## Feasibility (12 GB card) — VRAM is a non-issue; wall-time scales with motor count

- **VRAM: ~800 MiB at d8000 (213 920 motors), flat across the run** — the SoA arrays are compact; the 12 GB card
  is nowhere near pressure. No density was VRAM-limited; the sweep was **not capped for memory.**
- **Per-seed GPU wall time** (60000 steps): d100 1.9 min · d250 2.3 · d500 3.0 · d1000 4.5 · d2000 7.4 ·
  d4000 13.2 · d6000 18.9 · d8000 24.8 min. Full 24-run GPU sweep ≈ **3.8 h**.
- **CPU (d4000): ~6 steps/s** (2000 steps = 5:35) ⇒ a full 60k arbiter ≈ 2.7 h; the arbiter was sized to 20k
  (~55 min) — the smallest window that gives a real steady window and resolves the basin.

---

## Verdict

The azimuthally-unaware canonical model, at the tighter coltol = 8 nm capture radius, produces a **clean,
monotonic, decelerating velocity–density curve that neither plateaus nor turns over through d8000** (velFitX
0.13 → 9.38 µm/s at avgBound 0.14 → 20.2). The velocity rise is **engagement-driven at low–mid density**
(per-bound ~flat ~0.95, inst ~flat) and **efficiency-limited at high density** (per-bound collapses 0.95 → 0.46
as co-bound heads crowd and fight — the tug-of-war). Kinetics (dwell/duty/detach) are density-independent
throughout; the collective-load regime is **numerically stable on `-xbimplicit2` alone** (no `-segimplicit`
needed); the top point (d8000) is **coverage-limited by the bed geometry**, not by physics. CPU confirms the
GPU is reading the correct HIGH basin at the dense d4000 point.

**This is banked as the pre-refinement, azimuthally-unaware coltol = 8 baseline.** Adding specific
helical/azimuthal binding sites is expected to change engagement geometry (and thus this curve) — compare the
post-refinement sweep against *this* control, not against the coltol = 10 first stab or experiment.
