# Explicit-HMM dimer — DEFINITIVE GPU-only density sweep (density-response results)

**Status: COMPLETE (2026-07-21).** **68 GPU device-path cells** (11 densities × 5000 steps), all
`status=ok`, **0 invalid states, 0 solver failures** across the entire campaign. Run in two batches: the
original 9-density × 4-seed sweep (rev e342b8f), then a statistics top-up (rev f8dffd3 — harness-only commit,
model/kernels byte-unchanged ⇒ physics identical, pooling valid) adding seeds 105–108 at ρ{200,400,500,700,
750,1000} (→ **n=8**) and the new densities **ρ300, ρ600** (n=4). This is the scientific density-response
result on the **device path** (task objective), NOT the CPU↔GPU promotion matrix — `DEVICE_VALIDATED` is
unchanged (`false`); nothing promoted; the model was not modified during the sweep.

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

## 2. Headline density-response (velProd, µm/s, mean±SD; n seeds; seed values in ANALYSIS.md)

| ρ (dim/µm²) | n | velProd mean | SD | SEM | 95% CI | median | boundHeads | twoFrac |
|---|---|---|---|---|---|---|---|---|
| 100 | 4 | **+0.875** | 0.148 | 0.074 | [+0.64,+1.11] | +0.88 | 1.12 | 0.053 |
| 200 | 8 | **+1.903** | 0.944 | 0.334 | [+1.11,+2.69] | +2.04 | 2.79 | 0.062 |
| 300 | 4 | **+1.940** | 1.449 | 0.725 | [−0.37,+4.25] | +1.99 | 4.33 | 0.064 |
| 400 | 8 | **+2.432** | 0.815 | 0.288 | [+1.75,+3.11] | +2.60 | 5.62 | 0.067 |
| 500 | 8 | **+2.715** | 0.653 | 0.231 | [+2.17,+3.26] | +2.70 | 6.52 | 0.065 |
| 600 | 4 | **+2.830** | 0.775 | 0.388 | [+1.60,+4.06] | +2.67 | 8.18 | 0.068 |
| 700 | 8 | **+2.828** | 0.602 | 0.213 | [+2.33,+3.33] | +2.79 | 9.28 | 0.070 |
| 750 | 8 | **+2.964** | 0.577 | 0.204 | [+2.48,+3.45] | +2.80 | 9.48 | 0.073 |
| 1000 | 8 | **+2.964** | 0.655 | 0.232 | [+2.42,+3.51] | +3.08 | 12.33 | 0.070 |
| 1500 | 4 | **+2.629** | 0.653 | 0.327 | [+1.59,+3.67] | +2.50 | 19.63 | 0.064 |
| 3000 | 4 | **+2.843** | 0.462 | 0.231 | [+2.11,+3.58] | +2.83 | 38.00 | 0.065 |

All velProd **positive (productive, pointed-leading −x glide)**; velocity = LS slope of filament centroid.
The n=8 top-up roughly halved the SEMs on the mid-range densities (e.g. ρ500 0.44→0.23, ρ750 0.35→0.20).
Agreement with the CPU reference gate table (`..._GPU_BACKEND_FINDINGS.md §3`) is good in shape and close in
magnitude (ρ100 +0.88 vs CPU +0.99; **ρ3000 +2.84 vs CPU +2.85**); the GPU basin binds somewhat more in the
mid-range (ρ500 boundHeads 6.52 vs prior GPU-biological 6.29 — reproduces it; CPU 4.46), a documented
CPU↔GPU basin difference, not a new effect. Note ρ200/ρ300 are intrinsically high-variance (wide seed range,
SEM 0.33/0.73) — the rising, sub-half-saturation shoulder where a few seeds under-recruit.

## 3. Density-response FITS (§7)

- **Hill wins, and the top-up tightened it.** Hyperbolic `v=vmax·ρ/(ρ½+ρ)`: vmax=3.29±0.20, ρ½=160±42,
  **R²=0.852**. Hill `v=vmax·ρⁿ/(ρ½ⁿ+ρⁿ)`: vmax=**2.93±0.12**, ρ½=**160±17**, **n=1.89±0.37**, **R²=0.936**
  (ΔR²=+0.084 ⇒ the Hill exponent **materially improves** the fit — mild positive cooperativity, n≈1.9; the
  n=8 top-up shrank the parameter errors ~1.5× and raised R² 0.905→0.936).
- **Apparent onset** ~ρ100 (already >10 % vmax); **half-maximal** at ρ½≈**160 dim/µm²**; **90 % of fitted
  max** at ρ≈1440.
- **High-density behavior = PURE SATURATION (no significant suppression) — resolved by the top-up.** velProd
  plateaus at **~2.8–3.0 µm/s for ρ≥600**, peaks +2.96 (ρ750 = ρ1000), and ρ3000 (+2.84) sits **within noise
  of the plateau** (peak→ρ3000 Δ0.12 µm/s ≪ the ~0.2–0.3 SEMs). The earlier n=4 read had a marginal "dip"
  hint (peak ρ750 +3.14 → ρ3000 +2.84, ~1 SEM); with n=8 on the mid-range that washed out entirely — the
  analyzer's automated verdict flipped to **"no significant decline (pure saturation)."** Per §7 we do NOT
  impose a high-density-suppression term the data do not support.

## 4. Binding–density relationships & the efficiency mechanism (§8) — the real finding

**Speed saturation is NOT bound-head saturation — it is declining per-motor efficiency.**

| ρ | boundHeads | vel/boundHead | twoFrac | attachLife (ms) | ATPturn |
|---|---|---|---|---|---|
| 100 | 1.12 | **+0.781** | 0.053 | 0.67 | 22 |
| 500 | 6.52 | +0.416 | 0.065 | 0.69 | 115 |
| 1000 | 12.33 | +0.240 | 0.070 | 0.67 | 222 |
| 3000 | 38.00 | **+0.075** | 0.065 | 0.68 | 676 |

(full 11-density efficiency ladder in ANALYSIS.md §8: vel/boundHead falls monotonically 0.78→0.075 as ρ100→ρ3000)

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

On the explicit-HMM dimer **GPU device path** (68 cells, up to n=8/density), gliding velocity **rises with
motor density, half-saturates at ρ≈160 dim/µm², and reaches a clean plateau at ~2.8–3.0 µm/s above ρ600**
(Hill n≈1.9, R²=0.94). The improved statistics resolve the high-density question: it is **pure saturation —
no significant suppression** (ρ3000 within noise of the plateau; the earlier n=4 "dip" hint washed out). The
plateau is set by **declining per-motor efficiency** — bound heads grow linearly (1.1→38) but net forward
transport per head falls ~10× (0.78→0.075 µm/s/head), driven by growing backward-second-head/co-bound
opposition, not by any failure to bind (attachment lifetime ~0.68 ms and two-head fraction ~0.06 are
density-flat). Mechanical health is clean through ρ1500; at ρ3000 a single seed shows a 69 nm joint-gap
excursion (basin-intermittent, no NaN/solve-failure, R0-recorded). `DEVICE_VALIDATED` stays `false`; this is
a device-path science result, not a promotion.

## 7. Reproduce

```
./scripts/run_hmm_density_sweep.sh -steps 5000     # run/resume the 36-cell campaign (checkpoint per cell)
python3 scripts/hmm_density_analysis.py RUN_LOGS/hmm_density_sweep   # regenerate ANALYSIS.md
./scripts/run_hmm_gpu.sh -production-cell -density 500 -seed 101 -steps 5000 -outdir <dir> -rev <sha>   # one cell
```

Observables NOT host-instrumented (would need kernel/driver hooks; noted in each JSON): min solver pivot,
max residual, per-cell GPU util/mem. Solve health is covered by `solver_failures` + `invalid_states` (both 0
everywhere). Mechanical-health metrics (gap/branch/F8) sampled every `healthStride`=10 steps.
