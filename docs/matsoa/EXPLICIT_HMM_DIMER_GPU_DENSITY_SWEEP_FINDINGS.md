# Explicit-HMM dimer — DEFINITIVE GPU-only density sweep (density-response results)

**Status: COMPLETE (2026-07-21).** 36 GPU device-path cells (9 densities × 4 seeds × 5000 steps), all
`status=ok`, **0 invalid states, 0 solver failures** across the entire campaign. This is the scientific
density-response result on the **device path** (task objective), NOT the CPU↔GPU promotion matrix —
`DEVICE_VALIDATED` is unchanged (`false`); nothing promoted; the model was not modified during the sweep.

Raw per-cell JSON: `RUN_LOGS/hmm_density_sweep/cell_d<ρ>_s<seed>.json`; auto-analysis
`RUN_LOGS/hmm_density_sweep/ANALYSIS.md`; per-cell stdout `RUN_LOGS/hmm_density_sweep/logs/`.

## 1. Frozen configuration (enforced + printed + abort-gated per cell, §1)

Full-device experimental GPU path (`hmmProd` TaskGraph): Ms=3/Ma=1/Mb=1 (19 DOF), **branchEA=0.03**
(eff. proximal-branch axial ≈12.6 pN/nm), standing branch EI, forkK=1.0, **D0** (dirMech=0), 5.4 nm
occupancy exclusion ON, **dt=2.5e-6 s**, production chemistry + catch-slip + binding gate, actin Brownian
ON, dimer-mechanics Brownian ON, **GPU `cullDimers` + `solveGuarded` active-only mechanics** (the §1
mandate — NOT the prior rupture engine's dense `solveBatchFloat`; dense≡active per G5 A1–A6), rupture **R0**
(emergency OFF), scaled-float mechanics, persistent dimer-ID RNG, no CPU fallback. Each cell verifies the
config and **aborts** on any violation (branchEA≠0.03, non-device backend, rupture active, emergency on).
Driver: `ExplicitHmmDimerGpuValidation.runProductionCell` / `buildProductionGpu`; orchestrator
`scripts/run_hmm_density_sweep.sh`; analysis `scripts/hmm_density_analysis.py`.

Device throughput: 84 steps/s @ρ100 → 58 steps/s @ρ3000 (~15.5 % active, launch-bound); ~60–86 s wall/cell.

## 2. Headline density-response (velProd, µm/s, mean±SD across 4 seeds; seed values in ANALYSIS.md)

| ρ (dim/µm²) | velProd mean | SD | SEM | 95% CI | median | boundHeads | twoFrac | fwd/bwd 2nd (Σ) |
|---|---|---|---|---|---|---|---|---|
| 100 | **+0.875** | 0.148 | 0.074 | [+0.64,+1.11] | +0.88 | 1.12 | 0.053 | 5/6 |
| 200 | **+1.777** | 1.060 | 0.530 | [+0.09,+3.46] | +1.93 | 2.66 | 0.050 | 8/14 |
| 400 | **+2.190** | 1.121 | 0.560 | [+0.41,+3.97] | +2.19 | 5.53 | 0.064 | 19/42 |
| 500 | **+2.586** | 0.881 | 0.441 | [+1.18,+3.99] | +2.48 | 6.47 | 0.064 | 20/49 |
| 700 | **+3.100** | 0.577 | 0.289 | [+2.18,+4.02] | +2.93 | 8.98 | 0.071 | 32/67 |
| 750 | **+3.138** | 0.696 | 0.348 | [+2.03,+4.25] | +3.03 | 9.32 | 0.073 | 36/68 |
| 1000 | **+2.857** | 0.881 | 0.441 | [+1.46,+4.26] | +2.93 | 12.23 | 0.067 | 41/88 |
| 1500 | **+2.629** | 0.653 | 0.327 | [+1.59,+3.67] | +2.50 | 19.63 | 0.064 | 68/139 |
| 3000 | **+2.843** | 0.462 | 0.231 | [+2.11,+3.58] | +2.83 | 38.00 | 0.065 | 156/263 |

All velProd **positive (productive, pointed-leading −x glide)**; velocity = LS slope of filament centroid.
Agreement with the CPU reference gate table (`..._GPU_BACKEND_FINDINGS.md §3`) is good in shape and close in
magnitude (ρ100 +0.88 vs CPU +0.99; **ρ3000 +2.84 vs CPU +2.85**); the GPU basin binds somewhat more in the
mid-range (ρ500 boundHeads 6.47 vs prior GPU-biological 6.29 — reproduces it; CPU 4.46), a documented
CPU↔GPU basin difference, not a new effect.

## 3. Density-response FITS (§7)

- **Hill wins.** Hyperbolic `v=vmax·ρ/(ρ½+ρ)`: vmax=3.31±0.26, ρ½=170±59, **R²=0.826**. Hill
  `v=vmax·ρⁿ/(ρ½ⁿ+ρⁿ)`: vmax=**2.95±0.18**, ρ½=**163±26**, **n=1.89±0.57**, **R²=0.905** (ΔR²=+0.079 ⇒
  the Hill exponent **materially improves** the fit — mild positive cooperativity, n≈1.9).
- **Apparent onset** ~ρ100 (already >10 % vmax); **half-maximal** at ρ½≈**165 dim/µm²**; **90 % of fitted
  max** at ρ≈1500.
- **High-density behavior = SATURATION-to-PLATEAU, not robust suppression.** velProd peaks +3.14 @ρ750,
  sits on a **plateau ~2.6–3.1 µm/s for ρ≥700**. The peak(ρ750)→ρ3000 drop (0.30 µm/s) is **~1 combined SEM**
  — a marginal, non-monotonic dip (lowest at ρ1500 +2.63) **within seed noise**. Per §7 we do NOT force a
  "high-density suppression" narrative: the honest description is **saturation to a plateau**; any suppression
  is at most weak and not resolved at n=4.

## 4. Binding–density relationships & the efficiency mechanism (§8) — the real finding

**Speed saturation is NOT bound-head saturation — it is declining per-motor efficiency.**

| ρ | boundHeads | vel/boundHead | twoFrac | attachLife (ms) | ATPturn |
|---|---|---|---|---|---|
| 100 | 1.12 | **+0.781** | 0.053 | 0.67 | 22 |
| 500 | 6.47 | +0.400 | 0.064 | 0.68 | 114 |
| 1000 | 12.23 | +0.234 | 0.067 | 0.68 | 217 |
| 3000 | 38.00 | **+0.075** | 0.065 | 0.68 | 676 |

- **Bound heads rise ~LINEARLY with density** (1.1→38, ∝ρ; no saturation — Δ(boundHeads)/Δρ still 0.012 at
  the top). ATP turnover tracks bound heads linearly (22→676). Attachment lifetime is **density-invariant**
  (~0.68 ms) ⇒ the per-attachment kinetics do not change with crowding; only the *number* of attachments does.
- **Velocity-per-bound-head collapses ~10×** (0.78→0.075 µm/s per head) as density rises. So each added motor
  contributes progressively less *net forward* transport: the extra heads are increasingly **internally
  opposing** rather than additively propulsive.
- **Two-head fraction stays flat (~0.06)** and the **backward second-head binds grow to dominate**
  (fwd:bwd ~1:1 @ρ100 → ~1:1.7 @ρ3000; Σ 156/263). This is the documented co-bound tug-of-war / backward-bind
  population: at high density more second heads land on the drag (backward) side, and the resulting opposing
  load — not a lack of binding — caps the ensemble speed. ⇒ **the glide is transport-efficiency (co-bound
  opposition) limited, not attachment-limited.**

## 5. Mechanical health & the high-density tail (§9)

- **Biological range (ρ≤1500): pristine.** All seeds maxGap <5.2 nm, peak branch force ≤66 pN, **0** events
  >50 nm, 0 invalid, 0 solveFail. ρ1500 (all 4 seeds) is clean — no >50 nm event.
- **ρ3000: the seed-intermittent tail reappears on ONE seed.** s101 → **maxGap 69.4 nm, peakBrF 825 pN, 1
  event >50 nm** (0 >100 nm, 0 invalid, 0 solveFail); s102/s103/s104 all clean (4.3/5.4/4.3 nm). Recorded, **NOT
  auto-ruptured** (R0 retained per campaign spec); the trajectory did not go non-finite (invalid 0) and the
  solve never failed.
- **The rare gap event is BASIN-sensitive (GPU-number-trust rule).** This campaign's active-only graph puts
  the >50 nm event at **ρ3000 s101** (69 nm), while the prior dense-mechanics rupture spot-check saw ρ3000
  **s102** →14.3 nm (clean here: 4.3 nm). Dense≡active in aggregate (G5), but the last-bit graph-structure
  difference tips *which* chaotic high-density seed lands in the high-gap basin — exactly the documented
  gliding chaotic-bistability / graph-split hazard. **The aggregate density-response (velocity, binding,
  directionality) is basin-robust; only the rare high-density gap excursion is basin/seed-intermittent.** The
  CPU runner remains the arbiter for the specific "does seed X blow up" question; that is a rupture-threshold
  concern (deferred, R0), not a density-response result.

## 6. Bottom line

On the explicit-HMM dimer **GPU device path**, gliding velocity **rises with motor density, half-saturates at
ρ≈165 dim/µm², and plateaus at ~2.6–3.1 µm/s above ρ700** (Hill n≈1.9). The plateau is set by **declining
per-motor efficiency** — bound heads grow linearly but net forward transport per head falls ~10×, driven by
growing backward-second-head/co-bound opposition, not by any failure to bind (attachment lifetime and
two-head fraction are density-flat). Mechanical health is clean through ρ1500; at ρ3000 a single seed shows a
69 nm joint-gap excursion (basin-intermittent, no NaN/solve-failure, R0-recorded). `DEVICE_VALIDATED` stays
`false`; this is a device-path science result, not a promotion.

## 7. Reproduce

```
./scripts/run_hmm_density_sweep.sh -steps 5000     # run/resume the 36-cell campaign (checkpoint per cell)
python3 scripts/hmm_density_analysis.py RUN_LOGS/hmm_density_sweep   # regenerate ANALYSIS.md
./scripts/run_hmm_gpu.sh -production-cell -density 500 -seed 101 -steps 5000 -outdir <dir> -rev <sha>   # one cell
```

Observables NOT host-instrumented (would need kernel/driver hooks; noted in each JSON): min solver pivot,
max residual, per-cell GPU util/mem. Solve health is covered by `solver_failures` + `invalid_states` (both 0
everywhere). Mechanical-health metrics (gap/branch/F8) sampled every `healthStride`=10 steps.
