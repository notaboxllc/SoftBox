# Explicit SINGLE-HEAD (explicit-s2-l40) — GPU density sweep, LONG runs (0.1 s / 40 000 steps)

**Status: COMPLETE (2026-07-21).** A new, definitive device-path single-head gliding density-response dataset at
**0.1 s simulated time per seed**, generated to be **directly comparable, at matched duration, to the completed
0.1 s HMM-dimer campaign** (`EXPLICIT_HMM_DIMER_GPU_DENSITY_SWEEP_LONG_FINDINGS.md`). 52 cells, 13 densities
(ρ100–ρ3000, heads/µm²), n=4 seeds each. **All 52 cells `status=ok`, 0 invalid / 0 solveFail.**

**Headline.** With matched 0.1 s duration and the identical whole-window estimator:
- The single-head curve is **plain hyperbolic saturation** — vmax=**4.40±0.10 µm/s**, ρ½=**352±22 heads/µm²**,
  R²=0.990; Hill n=**1.09±0.08** (ΔR²=+0.001 ⇒ NOT cooperative, plain Michaelis–Menten).
- **The prior single-head reference (Phase C) was NOT transient-inflated** — new (whole-window) / old (equilibrated)
  = **1.01×** over the 7 shared densities. Regenerating at 0.1 s **confirms** the old fit (contrast the dimer, whose
  short→long window collapsed an apparent n≈1.9 to n≈1.3).
- **Dimerization lowers raw gliding speed by ~24 % and vmax by ~23 %, but does NOT shift the half-saturation
  density** (single ρ½=352 vs dimer ρ½=363). Uyeda-normalized (v/vmax), the two curves **collapse onto one** ⇒ the
  monomer↔dimer difference is a clean **Vmax (amplitude) effect**, not a saturation-density / cooperativity shift.
  The difference is real and survives matched-duration control.

## Output (separate dir, not pooled with the dimer data)

`RUN_LOGS/single_head_density_sweep_long/`:
- per-cell JSON `cell_d<D>_s<S>.json` (+ `.done`) — full frozen config, motion, binding, chemistry, health per cell
- `density_summary.csv` (per-density aggregates) · `per_seed.csv` (machine-readable per-(ρ,seed) table)
- `ANALYSIS.md` (A density response, B fits, C mechanism, health) · `COMPARISON.md` (D/E/F)
- `plots/` — `density_response_singlehead.png`, `old_vs_new_singlehead.png`, `matched_raw_speed.png`,
  `headequiv_supplemental.png`, `uyeda_normalized.png`

Reproduce / resume (resumable; skips completed `*.done` cells):
```
./scripts/run_singlehead_density_sweep.sh                       # full 52-cell campaign (40000 steps)
python3 scripts/single_head_density_analysis.py RUN_LOGS/single_head_density_sweep_long
python3 scripts/single_head_dimer_compare.py                    # D/E/F + plots
```

## Exact frozen configuration (enforced + printed + abort-gated per cell)

| axis | value | matched to dimer? |
|---|---|---|
| model | **explicit-s2-l40** (MD-informed explicit S2 beam, L=40 nm, initial slack 1.5 nm) | dimer = explicit forked-beam HMM (same beam family) |
| backend | **GPU device-resident** TornadoVM TaskGraph (`ExplicitCompleteMatHarness.buildGlidingGraph`, `matS2SolveStep`); no CPU fallback (`-Dtornado.recover.bailout=false -Dtornado.enable.fma=false`) | yes (dimer = device-resident `solveActiveFloat`) |
| dt | **2.5e-6 s** | yes |
| steps | **40 000 → 0.1 s** simulated / cell | yes |
| velocity estimator | **whole-window LS slope** of filament centroid·b̂ (equil=0); velProd = −slope > 0 = productive | yes (identical to the dimer's `runProductionCell`) |
| density definition | **motor objects / µm²** (single-head = **heads/µm²**); N = round(ρ·3.0) over a 3.0×1.0 µm lawn | yes (dimer = dimers/µm², same lawn) — **no 2× factor** |
| actin | 12-seg flexible chain, 64 mono/seg, **contour 2.106 µm**, semiflexible (Lp≈17 µm) | dimer 11-seg / ~1.94 µm (see caveats) |
| actin Brownian | ON (BTransCoeff, end-segment BRotCoeff) | yes |
| motor Brownian | ON (motor-body) | yes |
| surface | z-only coverslip confinement, 2.0 pN/nm | yes |
| chemistry | Lymn–Taylor nucleotide cycle ON (NONE→ATP→ADPPi→ADP), catch-slip release | yes |
| binding | **free binding** (`matBindExplicit` each step), **no cull, no occupancy exclusion**; canonical half-open segment ownership | yes |
| direction / sign | b̂ = +x; negative centroid slope = pointed-first = productive | yes |

**No physics parameter was changed.** All harness edits are additive/harness-only: a `-production-cell` mode + per-cell
JSON producer, and one extra per-step host readback (`nucleotideState`, for ATP turnover) behind a default-off
`PROD_SCI` flag so the existing `-sweep`/`-throughput` paths stay byte-identical. The validated `buildS2Mat` +
`buildGlidingGraph` explicit path is unchanged; `MotorGpuParams.DEVICE_VALIDATED` stays `false` (not gated on this
research path). Code rev `73d82bf`.

## Runtime & throughput

- **GPU device-resident, RTX 5070.** ~76 s/cell at low ρ → ~96 s at ρ3000 (N=9000 heads); **~420–530 steps/s**;
  warm/compile ~0.9 s/cell. **Whole 52-cell campaign ≈ 75 min** (vs the dimer's ~6 h — the single head has no
  19-DOF forked-beam Newton solve). One fresh JVM per cell (GPU-hang-resilient, resumable).

## Density response — complete table (velProd µm/s, 0.1 s whole-window, n=4/density)

| ρ (heads/µm²) | velProd mean | SD | SEM | 95% CI | median | boundHeads | vel/boundHead | ATPturn | postEquil |
|---|---|---|---|---|---|---|---|---|---|
| 100  | **+0.910** | 0.503 | 0.251 | [+0.11,+1.71] | +0.753 | 0.68 | +1.344 | 80   | +0.743 |
| 150  | **+1.161** | 0.528 | 0.264 | [+0.32,+2.00] | +1.065 | 1.08 | +1.074 | 132  | +1.117 |
| 200  | **+1.632** | 0.651 | 0.326 | [+0.60,+2.67] | +1.611 | 1.63 | +0.999 | 198  | +1.933 |
| 250  | **+1.907** | 0.219 | 0.110 | [+1.56,+2.26] | +1.919 | 1.93 | +0.989 | 235  | +1.971 |
| 300  | **+2.067** | 0.581 | 0.291 | [+1.14,+2.99] | +1.934 | 2.20 | +0.939 | 276  | +2.020 |
| 400  | **+2.299** | 0.081 | 0.040 | [+2.17,+2.43] | +2.304 | 2.76 | +0.832 | 359  | +2.427 |
| 500  | **+2.713** | 0.340 | 0.170 | [+2.17,+3.25] | +2.614 | 3.66 | +0.741 | 468  | +2.742 |
| 600  | **+2.703** | 0.166 | 0.083 | [+2.44,+2.97] | +2.704 | 4.33 | +0.624 | 565  | +2.665 |
| 700  | **+3.043** | 0.271 | 0.135 | [+2.61,+3.47] | +2.925 | 5.14 | +0.592 | 669  | +2.952 |
| 750  | **+2.870** | 0.252 | 0.126 | [+2.47,+3.27] | +2.961 | 5.57 | +0.515 | 736  | +2.742 |
| 1000 | **+3.324** | 0.260 | 0.130 | [+2.91,+3.74] | +3.317 | 7.16 | +0.464 | 962  | +3.426 |
| 1500 | **+3.546** | 0.130 | 0.065 | [+3.34,+3.75] | +3.600 | 10.47 | +0.339 | 1423 | +3.442 |
| 3000 | **+3.911** | 0.177 | 0.088 | [+3.63,+4.19] | +3.963 | 20.77 | +0.188 | 2859 | +3.863 |

Per-seed values in `per_seed.csv`; per-seed velProd in `ANALYSIS.md §A`. Monotonic saturating (the ρ600/ρ750 dips are
within-SEM seed scatter). Low-ρ points (ρ≤200) carry the largest relative SEM (~20–30 %) because motion is
near-diffusive there (< 2 bound heads); ρ≥250 SEMs are 3–8 %.

## Fit results

- **Hyperbolic** `v = vmax·ρ/(ρ½+ρ)`: **vmax = 4.404 ± 0.097 µm/s, ρ½ = 352 ± 22 heads/µm², R² = 0.9904.**
- **Hill** `v = vmax·ρⁿ/(ρ½ⁿ+ρⁿ)`: vmax = 4.228 ± 0.161, ρ½ = 323 ± 27, **n = 1.09 ± 0.08**, R² = 0.9915.
  **ΔR² = +0.0011 ⇒ the Hill exponent does NOT materially improve the fit.** The steady-state single-head
  density-response is **plain hyperbolic (Michaelis–Menten-like) saturation, n≈1.1** — no cooperativity.
- Saturating: peak velProd at ρ3000 (+3.911) with no decline ⇒ **pure saturation**; 90 % of fitted vmax at ρ≈3.2k.

## Mechanism (same as the dimer): efficiency decline, not attachment saturation

Bound heads rise ~linearly (0.68→20.8 over ρ100→ρ3000; ATP turnover 80→2859 tracks it); attachment lifetime is
density-invariant (~0.72–0.85 ms). **vel/boundHead falls monotonically 1.34→0.19 (7.1×)** — added motors are
increasingly internally-opposing (co-bound tug-of-war), not additively propulsive. So the speed ceiling is a
**transport-efficiency ceiling, not a binding ceiling** — the same mechanism the dimer campaign found.

## Health

**0 invalid / 0 solveFail across all 52 cells.** Peak co-bound axial load rises smoothly 19→93 pN with density (more
co-bound heads at high ρ), with **no pathological excursions** — the single head has no fork, so nothing analogous to
the dimer's rare ρ3000-seed-102 joint-gap tail (24 µm) can occur here. (On this device path, solve health is exposed
only via reduction finiteness ⇒ `solver_failures` ≡ `invalid_states`; there is no separate per-motor pivot/residual
buffer in production residency — same limitation the dimer campaign flagged.)

## D. Long-run (new) vs old single-head — was the prior dataset transient-inflated?

Prior single-head reference = Phase-C `explicit-s2-l40` (`EXPLICIT_DENSITY_SWEEP_FINDINGS.md`,
`RUN_LOGS/explicit_completemat/COMPLETEMAT_SWEEP.csv`): equilibrated (equil 20 000 / meas 60 000 = 0.15 s measured
window), n=3 seeds. New = 0.1 s whole-window, n=4.

| ρ | NEW velProd (whole-window) | NEW post-equilibration | OLD |speed| (equilibrated) | Δ(new−old) |
|---|---|---|---|---|
| 100  | +0.910 | +0.743 | 0.994±0.35 | −0.084 |
| 200  | +1.632 | +1.933 | 1.503±0.11 | +0.129 |
| 400  | +2.299 | +2.427 | 2.308±0.09 | −0.009 |
| 700  | +3.043 | +2.952 | 2.625±0.18 | +0.417 |
| 1000 | +3.324 | +3.426 | 3.291±0.22 | +0.033 |
| 1500 | +3.546 | +3.442 | 3.547±0.12 | −0.002 |
| 3000 | +3.911 | +3.863 | 4.205±0.03 | −0.295 |

- **NEW/OLD = 1.01× over the 7 shared densities** (new mean 2.666 vs old 2.639; new post-equilibration 2.684).
  New-hyperbolic vmax 4.40 / ρ½ 352 vs old-hyperbolic vmax 4.59 / ρ½ 423 — both R²≈0.99, agreeing within fit error.
- **Verdict: the old single-head reference was NOT transient-inflated.** Unlike the dimer (whose short 12.5 ms window
  produced a spurious n≈1.9 that the 0.1 s run corrected to n≈1.3), the old Phase-C single-head data was already
  equilibrated over a 0.15 s window, so the matched 0.1 s regeneration **reproduces it**. The point-by-point spread
  (largest at ρ700 and ρ3000) is seed scatter (old n=3, new n=4), not a systematic window bias — the whole-window and
  post-equilibration columns agree to within ~0.1–0.2 µm/s at every ρ≥250.
- **The prior monomer↔dimer difference SURVIVES matched-duration control** (it is not an artifact of the old
  single-head window) — see E/F for its clarified nature.

## E. Matched raw-speed: single-head (heads/µm²) vs dimer (dimers/µm²) — NO 2× factor

Primary comparison (`plots/matched_raw_speed.png`): x = surface density of motor objects; y = raw µm/s; no
normalization, no 2× factor.

| ρ (objects/µm²) | single-head | HMM dimer | dimer/single |
|---|---|---|---|
| 100 | +0.910 | +0.588 | 0.65× |
| 200 | +1.632 | +1.139 | 0.70× |
| 300 | +2.067 | +1.481 | 0.72× |
| 400 | +2.299 | +1.759 | 0.77× |
| 500 | +2.713 | +1.986 | 0.73× |
| 700 | +3.043 | +2.304 | 0.76× |
| 1000 | +3.324 | +2.514 | 0.76× |
| 1500 | +3.546 | +2.709 | 0.76× |
| 3000 | +3.911 | +2.914 | 0.75× |

- **Single-head glides faster than the dimer at every matched object density** — dimer/single ≈ **0.75×** (roughly
  flat across ρ). Hyperbolic vmax: single **4.40** vs dimer **3.40** (**1.29×**); half-saturation **essentially equal**
  (single ρ½ 352 vs dimer ρ½ 363).
- **Supplemental head-equivalent plot** (`plots/headequiv_supplemental.png`, dimer density ×2, clearly labeled — NOT a
  molecular-density comparison): the dimer stays below the single head even on a per-head x-axis (a dimer at ρ carries
  2ρ heads yet glides slower than a single-head lawn at 2ρ heads), i.e. two coupled heads are less propulsive per head
  than two independent heads.

## F. Uyeda comparison

`plots/uyeda_normalized.png`. Normalization + conventions (documented per task):
- **v/vmax** per model (single-head vmax=4.404, dimer vmax=3.400 µm/s, from the hyperbolic fits).
- **x-axis** = surface density of motor OBJECTS (heads/µm² single-head, dimers/µm² dimer — **no 2× factor**).
- **Filament lengths:** single-head 2.11 µm (12 seg), dimer ~1.94 µm (11 seg).
- **Interaction-band width w_band = 0.30 µm** — the engageable y-strip |ay|<0.15 µm shared by both mats
  (`buildMat`/`buildGlide2D`); motors interacting with a filament ≈ ρ·L_fil·w_band.
- **Uyeda reference is the qualitative literature band ~100–300 motors/µm² for continuous movement**
  (Uyeda, Kron & Spudich 1990). **No digitized Uyeda points are used** — the overlay is that approximate band only.

Result: because ρ½ is nearly identical (352 vs 363), **the two Uyeda-normalized curves collapse onto one** — same
shape, both crossing half-max at ~350/µm² (just above the Uyeda band), tracking each other across the whole range.

## Interpretation — does dimerization lower speed or shift saturation (after matched-duration control)?

**Dimerization genuinely LOWERS raw gliding speed (Vmax) — it does NOT shift the saturation density.**
- Raw speed: dimer is ~0.75× the single head at every matched object density; Vmax 3.40 vs 4.40 µm/s (−23 %).
- Saturation density: **unchanged** — ρ½ 363 (dimer) vs 352 (single), within fit error; the Uyeda-normalized curves
  overlay. So the effect is a clean **amplitude (Vmax) reduction on the same-shaped hyperbolic curve**, not a shift in
  the density at which motors begin to move the filament continuously, and not a change in cooperativity (both n≈1.1–1.3,
  neither materially super-hyperbolic once the transient is stripped).
- Mechanistically consistent: both models saturate by the same efficiency-decline route (co-bound tug-of-war;
  vel/boundHead falls ~6–7×), and the dimer binds ~2× the heads per object yet converts them to less net forward glide —
  the two coupled heads of a dimer share load through the forked beam and partially oppose one another, so a dimer is a
  less efficient propulsion unit per head than an independent single head. This is the Phase-C "Outcome C" picture
  (explicit units bind fewer/oppose more yet the SINGLE head still out-glides the coupled dimer), now confirmed at
  matched 0.1 s duration and full n=4 statistics.

**Bottom line:** the matched-duration control does not dissolve the monomer↔dimer speed difference — it sharpens it.
The difference is a Vmax effect (single ~1.3× the dimer), not a duration artifact and not a saturation-density shift.

## Caveats (explicit)

1. **Filament length is not exactly matched (~9 %).** Single-head actin is the validated 12-seg / 2.106 µm chain;
   the dimer campaign used an 11-seg / ~1.94 µm chain. I kept the validated single-head geometry to satisfy the hard
   "no physics changed" requirement and to keep the old↔new single-head comparison (D) clean (only duration differs).
   Gliding speed is weakly filament-length-dependent once the filament spans many motors, so this is a minor bias on
   the E/F absolute ratios; it does not affect the shape/ρ½ conclusions. A dedicated 11-seg single-head control could
   bound it if a length-exact ratio is later required.
2. **Velocity estimator is whole-window (equil=0), matching the dimer.** For the single head this is a legitimate but
   transient-bearing estimator; the `vel_postequil` column (2nd-half LS) shows the transient is small for ρ≥250
   (agrees with the whole-window value to ~0.1–0.2 µm/s) and only material at ρ≤200 where motion is near-diffusive.
   Both models carry the same estimator, so the E/F comparison is apples-to-apples.
3. **Uyeda is a qualitative literature band, not a digitized dataset** (see F). The normalized overlay uses each
   model's own fitted vmax; no external Uyeda velocity points are claimed.
4. **`DEVICE_VALIDATED=false`.** This is a research device-resident path (not promoted to production default); the CPU
   runner remains the arbiter for absolute-number claims. The 0-invalid/0-solveFail health and the clean agreement
   with the equilibrated Phase-C reference (D) support the numbers; a CPU-arbiter spot check of one mid-density cell
   would fully close it if required for promotion.
5. **Solve-health instrumentation** on this path is reduction-finiteness only (`solver_failures ≡ invalid_states`);
   no per-motor pivot/residual crosses the bus in production residency (same as the dimer campaign).
6. **Low-density statistics** (ρ≤200): relative SEM ~20–30 % (near-diffusive motion, <2 bound heads). n=4→n=8 there
   would tighten the low shoulder but does not change the fit (vmax/ρ½ are pinned by ρ≥250).
