# Explicit-HMM dimer — GPU density sweep, LONG runs (0.1 s / 40 000 steps) — density-response, tightened

**Status: COMPLETE (2026-07-21).** A **new, independent** device-path density-response dataset at **8×
longer simulated time per cell** than the original sweep, to reduce per-seed variance. Each cell integrates
**40 000 steps × dt 2.5e-6 s = 0.1 s** of simulated time (vs 0.0125 s in
`EXPLICIT_HMM_DIMER_GPU_DENSITY_SWEEP_FINDINGS.md`). 52 cells, 13 densities (ρ100–ρ3000), n=4 seeds each.
**Result: the transient-free curve is plain hyperbolic saturation (n≈1.3, ρ½≈360, vmax≈3.4 µm/s, R²=0.985)**
— the short sweep's apparent n≈1.9 "cooperativity" was a finite-window artifact.

- **Output (separate dir, not pooled with the 12.5 ms data):** `RUN_LOGS/hmm_density_sweep_long/`
  (per-cell JSON + auto `ANALYSIS.md` + per-cell logs).
- **Frozen config: IDENTICAL to the short sweep** (§1: branchEA=0.03, D0, dt=2.5e-6, Brownian ON, active-only
  `cullDimers`+`solveGuarded`, rupture R0, scaled-float, persistent-id RNG) — enforced/printed/abort-gated per
  cell. The ONLY change is `-steps 40000`. `DEVICE_VALIDATED` unchanged (`false`); model unmodified.
- **Density ladder:** {100,200,300,400,500,600,700,750,1000,1500,3000} × seeds 101–104 (n=4), low→high order.
- **Cost:** ~8 min/cell at low ρ → ~11–12 min at ρ3000 (≈ the short sweep's per-cell time × 8); ~6 h for all 44.

Rationale: velProd is the LS slope of the filament centroid over the whole window. An 8× longer window (a)
dilutes the startup transient's contribution to the slope and (b) samples ~8× more binding/unbinding cycles
per seed, so the per-seed velProd estimator variance should fall substantially — directly addressing the
low-density seed spread seen at 12.5 ms.

## Results — COMPLETE (2026-07-21; 52 cells, 13 densities ρ100–ρ3000, n=4/density, 0.1 s each)

Plottable CSV: **`RUN_LOGS/hmm_density_sweep_long/density_summary.csv`** (per-density aggregates, one row/density).
Live auto-analysis: `RUN_LOGS/hmm_density_sweep_long/ANALYSIS.md` (seed values, fits, relationships, health).
Densities: {100,150,200,250,300,400,500,600,700,750,1000,1500,3000}; ρ150/ρ250 were added to sharpen the
steep low shoulder. All 52 cells `status=ok`, **0 invalid / 0 solveFail**.

### Density-response (velProd, µm/s; 0.1 s window; n=4 seeds/density)

| ρ (dim/µm²) | velProd mean | SD | SEM | 95% CI | boundHeads | vel/boundHead | worst maxGap |
|---|---|---|---|---|---|---|---|
| 100 | **+0.589** | 0.156 | 0.078 | [+0.34,+0.84] | 0.86 | +0.686 | 7.5 nm |
| 150 | **+0.988** | 0.209 | 0.104 | [+0.66,+1.32] | 1.44 | +0.688 | 9.7 nm |
| 200 | **+1.139** | 0.169 | 0.085 | [+0.87,+1.41] | 1.83 | +0.622 | 9.7 nm |
| 250 | **+1.370** | 0.216 | 0.108 | [+1.03,+1.71] | 2.21 | +0.620 | 9.7 nm |
| 300 | **+1.481** | 0.208 | 0.104 | [+1.15,+1.81] | 2.77 | +0.534 | 9.7 nm |
| 400 | **+1.759** | 0.166 | 0.083 | [+1.50,+2.02] | 3.70 | +0.476 | 9.7 nm |
| 500 | **+1.986** | 0.116 | 0.058 | [+1.80,+2.17] | 4.52 | +0.439 | **73.3 nm** |
| 600 | **+2.305** | 0.144 | 0.072 | [+2.08,+2.53] | 5.85 | +0.394 | 29.0 nm |
| 700 | **+2.304** | 0.084 | 0.042 | [+2.17,+2.44] | 6.34 | +0.364 | 32.2 nm |
| 750 | **+2.368** | 0.177 | 0.089 | [+2.09,+2.65] | 6.66 | +0.355 | 29.0 nm |
| 1000 | **+2.514** | 0.052 | 0.026 | [+2.43,+2.60] | 8.88 | +0.283 | 29.0 nm |
| 1500 | **+2.709** | 0.159 | 0.079 | [+2.46,+2.96] | 13.53 | +0.200 | 29.0 nm |
| 3000 | **+2.914** | 0.122 | 0.061 | [+2.72,+3.11] | 26.47 | +0.110 | **23 978 nm** (s102) |

### Fits (all 13 densities)

- **Hyperbolic `v=vmax·ρ/(ρ½+ρ)`: vmax=3.40±0.10, ρ½=363±28, R²=0.985.**
- **Hill: vmax=3.08±0.08, ρ½=299±15, n=1.27±0.07, R²=0.995 — ΔR²=+0.009 over hyperbolic ⇒ the Hill exponent
  does NOT materially improve the fit.**
- **Headline vs the short (12.5 ms) sweep:** with the startup transient stripped out, the steady-state
  density-response is essentially **plain hyperbolic (Michaelis–Menten-like) saturation, n≈1.3** — NOT the
  n≈1.9 "cooperativity" the short sweep reported (that was a finite-window artifact from uneven transient
  inflation of the low-ρ points). ρ½ moved up (~160 → ~300–360) for the same reason.
- **Saturating:** peak velProd is at ρ3000 (+2.914) with no decline ⇒ **pure saturation**; 90 % of fitted vmax
  at ρ≈3.3k, so ρ3000 sits right at the top of the working curve. vmax ≈ 3.1–3.4 µm/s.

### Mechanism (unchanged, cleaner): efficiency decline, not attachment saturation

Bound heads rise ~linearly (0.86→26.5 over ρ100→ρ3000; ATP turnover 115→3850 tracks it); attachment lifetime
density-invariant (~0.70–0.76 ms); two-head fraction flat (~0.03–0.05). **vel/boundHead falls monotonically
0.69→0.11 (6.3×)** — added motors are increasingly internally-opposing (co-bound tug-of-war), not additively
propulsive. So saturation is a transport-efficiency ceiling, not a binding ceiling.

### Health (all cells 0 invalid / 0 solveFail) — the seed-intermittent tail

The 8× longer window surfaces the rare high-gap tail more, and at **lower** density than the short sweep:
- **ρ500 s103 → 73.3 nm / 483 pN** (1 event >50 nm); ρ600/700/750/1000/1500 show isolated 22–32 nm excursions.
- **ρ3000 s102 → 23 978 nm (24 µm) / 78 018 pN** — a large excursion that **reproduces the documented
  ρ3000-seed-102 pathology** (the CLAUDE.md gate table lists CPU ~32 149 nm at exactly this seed). It stayed
  **finite (0 invalid/0 solveFail)** and its velProd (+3.043) sits within the other seeds' spread; excluding
  s102 shifts the ρ3000 mean only +2.914→+2.871 (<0.05, within noise) ⇒ **the velocity curve is robust to it.**
  The excursion is internal to one dimer's fork geometry (a joint gap), basin/seed-intermittent
  (chaotic-bistability), R0-recorded not ruptured; it does not perturb the aggregate.

### Do more runs help?

- **Variance is well-resolved at n=4:** SEMs 0.03–0.11 µm/s (≈3–8 % of the mean for ρ≥300; largest *relative*
  SEM is ρ100/ρ150 at ~11–13 %). The 8× window did the heavy lifting; n=4→n=8 would cut SEM only ~30 %.
- **Curve is complete and saturating** — vmax and ρ½ are now well-pinned (errors ±3 % / ±8 %). The only
  optional extension is n=8 at ρ100–250 to push the low-shoulder *relative* SEM under ~5 %, or ρ>3000 to
  confirm the plateau extends — neither is needed for a clean, publishable density-response curve.

Reproduce / resume:
```
./scripts/run_hmm_density_sweep.sh -steps 40000 -outdir RUN_LOGS/hmm_density_sweep_long \
    -cells "100:101 100:102 ... 3000:104"        # resumable; skips completed *.done cells
python3 scripts/hmm_density_analysis.py RUN_LOGS/hmm_density_sweep_long
```
