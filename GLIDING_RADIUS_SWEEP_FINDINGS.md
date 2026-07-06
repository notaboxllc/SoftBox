# Converged-dt (5e-7) glide speed vs capture radius — early-stopped, shrunk-mat sweep

**Date:** 2026-07-06. **Measurement + light-optimization only** — no kinetics/stroke/rate/geometry-density
change; both levers opt-in and **default byte-identical**; `BoA-v1ref` untouched. Config held fixed
(only `-coltol` varies): `-full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix`, **dt=5e-7**,
**density 1000**, **seed 0** (paired). Builds on the coltol10/d1000 converged reference
(`ROTIMPLICIT_FEASIBILITY.md` Part B: per-bound ≈1.78 / velFitX ≈7–8 µm/s @6.25e-7).

---

## STEP 1 — the two levers (default byte-identical)

**(a) Early-stop monitor (`-earlystop`, host-side; `esMonitor`).** Every `ES_INTERVAL` (default 0.005 s)
recompute the steady-window glide speed and its uncertainty; stop when precise.
- **Value** = `velFitX` = −LS slope of the centroid `cx(t)` over the **steady 2nd half** `[n/2, n)`
  (exactly the metric `measureGrid` already reports; the binding ramp-in first half is discarded).
- **Uncertainty** = **batch means** (not OLS-slope SEM — the trajectory is autocorrelated): split the
  steady window into `nb ≥ 5` non-overlapping batches each ≥ `ES_BATCH` (0.005 s) span, per-batch chord
  velocity `v_b = −Δcx/Δt`, `SEM = std(v_b)/√nb`, `relSEM = SEM/|velFitX|`.
- **Stop** when `relSEM < ES_THRESH` (0.05) AND steady window ≥ `ES_MINWIN` (0.03 s) AND `nb ≥ 5`.
- **Hard cap** `ES_CAP` (0.15 s); if 5% unmet → `NOT-CONVERGED@cap` with the achieved relSEM.
- Thresholds all args (`-esthresh/-esminwin/-escap/-esbatch/-esinterval`). The stop metric IS the
  reported metric (recomputed on the final samples ⇒ identical).

**(b) Mat-shrink (`-matband <excursionBudgetµm>`, density-preserving, subset).** Trim the never-visited
−x motor tail. Seed the **full** `-full` bed RNG exactly, but **drop** motors with anchor `x < bandXlo`,
`bandXlo = x0 − filEndToEnd − excursionBudget − 0.15 µm`. Kept motors get the **same RNG draws** ⇒ the
shrunk mat is a **strict subset** of the full mat (identical placement, same areal density); dropped
motors sit a margin (> motor x-reach + capture) below the filament's −x-most swept point ⇒ **provably
never in capture range** ⇒ binding unchanged. `bXhi`/`bYhalf` (y-width, +x margin) kept full so the
ends-over-motors physics is untouched. Runtime **edge-guard**: if any segment center comes within
`(capture + 0.10 µm)` of `bandXlo` → `*TRIPPED-band-undersized*` (no silent truncation).

**Default byte-identical:** all new code is `-earlystop`/`-matband`-gated; with neither flag the motor
seeding loop, the measurement loop, and the warmup are the original path.

---

## STEP 2 — the gate (coltol=4 nm, seed 0, dt=5e-7) — **PASS (proceed)**

| run | mat | nMot | velFitX ± SEM | relSEM | avgBsteady | throughput |
|--|--|--:|--:|--:|--:|--:|
| A shrunk + early-stop | band | 7532 | 5.578 ± 0.543 | 0.097 | 2.863 | ~394 st/s |
| B shrunk, no early-stop, full 0.15 s | band | 7532 | 5.578 ± 0.543 | 0.097 | 2.863 | ~406 st/s |
| C full-mat + early-stop (control) | full | 26740 | 5.678 ± 0.679 | 0.120 | 2.865 | ~230 st/s |

- **Mat-shrink parity — PASS.** Shrunk vs full-mat: velFitX **Δ=0.10 µm/s ≪ SEM** (0.54/0.68);
  avgBsteady **2.863 vs 2.865** (Δ0.002); duty/dwell within noise (STATS_STEADY_ROW). The `-matband`
  subset (identical placement; dropped motors provably out of capture range — edge-guard clear,
  minSegX 3.273 ≫ bandXlo+guard 2.694) does **not** change the physics. (The residual 0.10 µm/s is the
  motor **re-indexing** decorrelating the per-motor wang-hash RNG — same placement, different stochastic
  realization — chaotic within-SEM, exactly the CPU≡GPU-class standard.) **1.7× faster** (394 vs 230 st/s).
- **Early-stop sanity — monitor sound; 5% not reachable within the 0.15 s cap at coltol4.** A (early-stop)
  ≡ B (no early-stop) because both ran to cap: relSEM floors at **9.7 %** (shrunk) / 12 % (full) — the
  glide is genuinely noisy at avgBound≈2.9 (sparse binding). The monitor correctly returns
  `NOT-CONVERGED@cap` (no premature stop) and its stop metric equals the reported velFitX exactly
  (recompute-identical). ⇒ at these bind counts the sweep points **cap at ~8–15 % single-seed relSEM**
  (accepted per task: "let them cap"); reaching 5 % single-seed would need ~4× the sim time (≈0.6 s) or a
  multi-seed ensemble (out of scope). The **mat-shrink is the effective wall-time lever**; early-stop is
  the honest-error-bar monitor.

No bail conditions tripped (parity held, edge-guard clear, stop metric matches). **Proceed to STEP 3.**

---

## STEP 3 — the sweep (coltol ∈ {2,3,4,5,6} nm, seed 0, shrunk mat, early-stopped, dt=5e-7)

All points ran the full 0.15 s cap (0.075 s steady window, 15 batches) — none reached 5 % single-seed
relSEM (the accepted "let them cap" outcome; the glide is noisy at these bind counts). Edge-guard **clear**
at every radius (minSegX ≥ 3.07 vs bandXlo+guard ≈ 2.69, ≥ 0.4 µm headroom); no `TRIPPED`.

| coltol (nm) | velFitX ± SEM (µm/s) | relSEM | avgBsteady | **per-bound** = velFitX/avgB | stop window | 5% met? | meanK_tot(eng) / heads-per-seg (avgBound) |
|--:|--:|--:|--:|--:|--:|:--|--:|
| 2 | **5.36 ± 0.66** | 12.4 % | 2.61 | 2.05 | 0.075 s @ cap | NOT-CONVERGED@cap | 1.11 pN/nm / 2.61 |
| 3 | **6.11 ± 0.52** | 8.5 % | 2.79 | 2.19 | 0.075 s @ cap | NOT-CONVERGED@cap | 1.11 / 2.79 |
| 4 | **5.58 ± 0.54** | 9.7 % | 2.86 | 1.95 | 0.075 s @ cap | NOT-CONVERGED@cap | 1.14 / 2.86 |
| 5 | **6.59 ± 0.70** | 10.6 % | 3.28 | 2.01 | 0.075 s @ cap | NOT-CONVERGED@cap | 1.14 / 3.28 |
| 6 | **7.15 ± 0.52** | 7.2 % | 3.98 | 1.80 | 0.075 s @ cap | NOT-CONVERGED@cap | 1.17 / 3.98 |

- **velFitX rises weakly-monotonically** with capture radius, ~5.4 → ~7.2 µm/s (the point-to-point wiggle
  — coltol4 dipping below coltol3 — is within the ~10 % single-seed bars; the robust read is the well-
  separated **endpoints** 5.4 → 7.2 and the monotone avgBound).
- **The rise is entirely engagement (avgBound 2.61 → 3.98); per-bound drift is radius-INVARIANT at ≈2.0**
  (1.80–2.19, no trend). So capture radius sets **how many heads bind**, not per-bound glide efficiency.
- **K_tot census — the gliding regime is SUB-THRESHOLD at every radius:** meanK_tot(engaged) ≈ 1.1 pN/nm
  (k_s≈1-dominated), maxK_tot 3–4 pN/nm, fracEng ≥ 3.8 pN/nm ≈ **0** (≤0.001). The collective-load
  instability (EOM ~3.8 threshold) is never triggered ⇒ explicit Euler is comfortably stable at dt=5e-7
  here, and the residual dt-sensitivity is the **per-bound stroke/rotational stiffness**, not the load
  (consistent with SEG_IMPLICIT_FINDINGS: low-duty gliding is sub-threshold).
- **coltol6 reproduces the coltol10 reference:** velFitX 7.15 ≈ ref 7.3–8, per-bound 1.80 ≈ ref 1.78,
  avgBound 3.98 ≈ ref 4.1–4.3 — the sweep joins smoothly onto ROTIMPLICIT Part B at its wide edge.

---

## Plain statement

**Over 2–6 nm capture radius, converged-dt (5e-7) glide speed rises weakly and monotonically — velFitX
≈ 5.4 → 7.2 µm/s — driven almost entirely by engagement (avgBound 2.6 → 4.0), while the per-bound drift
is essentially radius-invariant at ≈ 2.0 µm/s per bound head.** Capture radius is an **engagement knob,
not a per-bound-efficiency knob**. Relative to the skeletal band (~1.5–4 µm/s, Vmax ≈ 2.9), the
converged-dt glide sits **above Vmax at every radius** — ~1.85× at coltol2 up to ~2.5× at coltol6 — so the
**~2–3× overshoot seen at coltol10 is PRESENT across the whole 2–6 nm range: tightening the radius REDUCES
the absolute overshoot (via fewer bound heads) but does NOT eliminate it** (the ~2.0-µm/s-per-bound
overshoot is intrinsic and radius-independent). The overshoot is therefore a **per-bound** property (the
still-explicit rotational-stroke stiffness that the converged dt exposes), not an artifact of over-wide
capture — narrowing the search radius will not bring the converged-dt glide down into the skeletal band.

**Method note:** single seed, 0.15 s cap ⇒ ~7–12 % relSEM per point (the batch-means SEM is honest; 5 %
single-seed would need ~0.6 s or a multi-seed ensemble — deliberately out of scope). The **mat-shrink is
the load-bearing wall-time lever** here (1.7×, parity-clean); the early-stop monitor did not fire (the
physics is too noisy to hit 5 % within 0.15 s) but correctly reports each point's achieved precision.

---

## Levers — reproduce

```
# STEP-2 gate (parity + early-stop sanity) and STEP-3 sweep:
./run_radiussweep.sh step2      # A shrunk+ES, B shrunk full-cap, C full-mat+ES (coltol4, seed0, dt5e-7)
./run_radiussweep.sh step3      # coltol ∈ {2,3,4,5,6}, shrunk mat, early-stopped, +ktotcensus
# one point directly:
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix \
    -density 1000 -dt 5e-7 -seed 0 -coltol 4 -matband 1.2 -earlystop 300000
```
`-matband <µm>` = density-preserving −x band subset shrink (edge-guarded); `-earlystop` (+`-esthresh/
-esminwin/-escap/-esbatch/-esinterval`) = batch-means-SEM stop monitor. Both **default byte-identical**.
Raw: `RUN_LOGS/2026-07-06_radiussweep.txt`.
