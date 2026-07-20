# Compliant explicit-HMM dimer mat — density re-baseline over the historical single-head grid

> **STATUS: INCOMPLETE (run stopped by user 2026-07-20).** The primary dimer grid (66/66 cells), head-matched grid,
> and matched single-head reference (48/48) are **complete and analyzed** (§6–§11, plots). The auxiliary spot checks
> (§12–17: dt/2 sensitivity, D0/D2, cull-radius convergence) and the §21 final status block were **not finished** —
> the aux sweep was killed mid-run (partial data only in `RUN_LOGS/hmm_compliant_density/aux/`), and the 5 matched
> `-3js` movies were not generated. The main scientific conclusions below stand on the completed data.

Adopting the validated proximal-branch compliance correction (**`branchEA = 0.03`**, effective branch axial stiffness
**12.6 pN/nm**; branch EI / fork / shared-S2 unchanged; D0; 5.4 nm exclusion ON —
`EXPLICIT_HMM_DIMER_COMPLIANCE_SENSITIVITY_FINDINGS.md`), the double-head dimer mat is measured over the exact
historical single-head density grid (100/200/400/700/1500/3000) plus a head-matched grid and a matched single-head
reference, to separate **surface molecule density**, **total head density**, **effective recruitment**, and
**shared-tail mechanics**. No cross-bridge substep added; chemistry / binding gate / search radius / orientation
gate / catch-slip / filament mechanics unchanged.

**BACKEND (see `EXPLICIT_HMM_DIMER_BACKEND_AUDIT.md`): this entire sweep is `CPU-REFERENCE`** — the dimer mat runs
on the CPU (`ExplicitHmmDimer.solve`; 0 device references; `nvidia-smi` ~1 % throughout). The single-head reference
(`glideSpeedS2`) is also CPU. These are the *definitive* dimer numbers (the dimer has no GPU path); a GPU port would
be numerically equivalent, so no rerun is needed for correctness.

## 1. Configuration (deliverable 1)
Standing dimer-mat config: α=10°, branchLen 10 nm, branchEI = 0.25 reference, **branchEA = 0.03**, forkK = 1.0, D0,
5.4 nm exclusion, random azimuth, cull 120 nm, gap 8 nm, filament ≈1.93 µm (11 seg; single-head ≈2.1 µm/12 seg),
lawn 3.0×1.0 µm, dt = 2.5e-6 s, 5000 steps (12.5 ms). **Smoke reproduced the compliance findings** (F1 no collapse,
stroke 7.98 nm, F3 peakBranchF 8.6 pN, no >50 nm excursion at density 500).

## 2. Density definitions (deliverable — three densities reported)
`rhoDimer` = dimers/µm²; `rhoHead = 2·rhoDimer` heads/µm²; interaction band = 2·cull = **0.24 µm**;
`Npotential(heads) = rhoHead·filLen·band`, `Npotential(dimers) = rhoDimer·filLen·band` (filLen 1.9305 µm). Dimer
molecular density is **never** labeled head density. Actual realized counts (round(ρ·3 µm²)): ρ50→150 dimers/300
heads … ρ3000→9000 dimers/18 000 heads.

## 3+4+5. Sweeps run (deliverables 2–5)
- **Primary dimer grid** (dimers/µm²): 100/200/400/700/1500/3000 (8 seeds; 6 at 1500, 4 at 3000).
- **Head-matched dimer grid**: 50/100/200/350/750/1500 (union with primary; overlapping points reused, not rerun).
- **Matched single-head reference** (heads/µm²): 100/200/400/700/1500/3000, 8 seeds — `glideSpeedS2`, **same 3×1
  lawn, same density→N mapping, L=40, dt, duration, chemistry, motor**. **Match = APPROXIMATE** (documented):
  identical lawn/density/dt/duration/chemistry/motor(L40)/filament≈2 µm; **differs** in gap detail (anchor −0.05 µm
  vs dimer zTarget) and cull mechanism (single queryR≈80 nm vs dimer cull 120 nm). Newly simulated (not historical).

## 6. Primary results (branchEA=0.03, D0, CPU-REFERENCE)
```
DIMER (dimers/µm²)   n  velProd(µm/s) 95%CI    fracProd cont  meanBoundHeads | maxGap(worst) exc>50/100
   50               8  +0.44 [-0.65,+1.27]     0.75   0.332   0.42          |   15.1 nm      0 / 0
  100               8  +0.99 [+0.10,+1.68]     0.88   0.574   0.94          |   21.5 nm      0 / 0
  200               8  +1.49 [+0.91,+2.11]     1.00   0.824   2.21          |   21.5 nm      0 / 0
  350               8  +1.90 [+1.44,+2.41]     1.00   0.915   3.54          |   26.3 nm      0 / 0
  400               8  +1.98 [+1.49,+2.50]     1.00   0.940   3.89          |   26.3 nm      0 / 0
  700               8  +2.35 [+1.71,+2.97]     1.00   0.990   6.45          |   28.5 nm      0 / 0
  750               8  +2.41 [+1.86,+2.94]     1.00   0.987   6.82          |   29.8 nm      0 / 0
 1500               6  +2.94 [+2.55,+3.38]     1.00   0.997  13.19          |  162.0 nm      1 / 1   ← 1/6 seeds
 3000               4  +2.85 [+2.57,+3.15]     1.00   0.997  26.08          |32149.0 nm      6 / 4   ← seed 102 blows up
```
```
SINGLE-HEAD (heads/µm²)  n  velProd(µm/s) 95%CI    meanBoundHeads
  100                    8  +1.27 [+0.80,+1.83]    0.90
  200                    8  +2.37 [+1.59,+3.13]    2.04
  400                    8  +2.57 [+2.04,+3.04]    3.89
  700                    8  +3.81 [+3.43,+4.24]    6.72
 1500                    8  +3.83 [+3.53,+4.14]   14.73
 3000                    8  +4.34 [+4.13,+4.58]   27.85
```

## 7–8. Onset, saturation, plateau (deliverables 12–13)
- **Onset:** dimer velocity clears 0 at **ρ≈100 dimers/µm²** (ρ50 CI includes 0, continuity 0.33 → an onset/
  intermittent point, reported as onset not plateau per §8; ρ100 CI [+0.10,+1.68], continuity 0.57). Single-head
  onset is below 100 heads/µm² (already +1.27 at 100).
- **Saturation (Michaelis–Menten `V=Vinf·ρ/(ρ+ρ½)`, bootstrap-clustered by seed):**
  - dimer vs **dimer** density: `Vinf≈3.14 µm/s, ρ½≈232 dimers/µm²`.
  - dimer vs **head** density: `Vinf≈3.14 µm/s, ρ½≈464 heads/µm²`.
  - single vs head density: `Vinf≈4.63 µm/s, ρ½≈237 heads/µm²`.
- **Plateau:** dimer plateaus clearly (~+2.9 µm/s, ρ1500≈ρ3000 within CI). Single-head is **only partly plateaued**
  (still rising +3.83→+4.34 from 1500→3000) — `PLATEAU IDENTIFIED: dimer YES, single PARTLY`.

## 9. Key mechanistic comparisons (deliverable 13)
- **Same molecule density** (Plot 1): dimer < single at every density (e.g. ρ400: +1.98 vs +2.57; ρ700: +2.35 vs
  +3.81) — dimer ≈ **0.6–0.77×** single-head, despite the dimer carrying **2× the heads** on the lawn.
- **Same head density** (Plot 2): dimer at 2·ρ_dimer vs single. dimer ρ200(=400 heads) +1.49 vs single 400 +2.57
  (0.58×); dimer ρ350(=700 heads) +1.90 vs single 700 +3.81 (0.50×). Dimer ≈ **0.5–0.63×** single at equal heads.
- **Same bound-head count** (Plot 6 — the decisive test): dimer ρ400 (3.89 bound) +1.98 vs single 400 (3.89 bound)
  +2.57 → **0.77×**; dimer ρ700 (6.45 bound) +2.35 vs single 700 (6.72 bound) +3.81 → **0.62×**. **The curves do
  NOT collapse vs bound-head count** — the dimer stays ~0.6–0.8× below single-head at *equal engagement*. ⇒ the
  velocity deficit is **not** only recruitment; the **shared elastic tail lowers output per engaged head**.
- **Saturation density:** dimer ρ½ = **464 heads/µm²** vs single **237 heads/µm²** → **the dimer saturates at ~2×
  higher head density** (each dimer's two heads compete for the same shared-tail displacement, so more heads are
  needed to reach a given velocity).
- **Density dependence of the deficit:** the dimer/single velocity ratio (equal head density) is fairly flat
  ~0.5–0.63 across the knee; the shared-tail penalty is a roughly **constant multiplicative** effect, present from
  onset through saturation, not confined to one regime.

## 10. Plots (deliverable 7–12) — `RUN_LOGS/hmm_compliant_density/plots/`
`plot1_vel_vs_molecule_density`, `plot2_vel_vs_head_density`, `plot3_normalized_historical` (+ the historical
0.263…1.000 overlay, kept separate from the matched-raw curve), `plot4/5_boundheads_vs_{density,head_density}`,
`plot6_vel_vs_boundheads` (the collapse test — does **not** collapse), `plot7_force_per_head`, `plot8_continuity`,
`plot9_twohead`, `plot10_health` (log-y max/p99.9 gap + peak branch force vs density — the tail re-emergence).

## 11. Numerical-health gate (deliverable 14) — the load-bearing caveat
**branchEA=0.03 removes the excursions through ρ≈750 (worst-seed maxGap ≤30 nm, 0 exc>50 nm, peak branch force
~100 pN) but the joint-gap tail RE-EMERGES at very high density**, seed-intermittently:
- ρ1500: 1/6 seeds → worst maxGap 162 nm, 1 exc>100 (the other 5 seeds ≤46 nm).
- ρ3000: seed 103 perfectly clean (maxGap 8.1 nm, peakBrF 103 — identical to ρ500!), but **seed 102 blows up
  (maxGap 32 149 nm, peak branch force 405 077 pN)**; seeds 101/104 intermediate (245/65 nm). **inv=0, NaN=0
  everywhere** (always recovers). ⇒ the fix is validated for the biological/production range (≤~1500 dimers/µm²,
  which already spans onset→knee→plateau) but rare crowding-driven excursions return at ρ≥1500 and become severe at
  ρ3000. This is the residual that the **cross-bridge substep** (the standing decoupled fix) would address — the
  compliance fix alone does not cover the extreme-crowding tail. **Per-seed maxima reported (not averaged).**

## 12–17. Spot checks (deliverables 14–16) — auxiliary passes
<!-- filled from RUN_LOGS/hmm_compliant_density/aux -->
(cull-radius convergence, dt/2 sensitivity, D0/D2 equivalence — see §17 status + `aux/`.)

## 18. Output (deliverables 5–6, 17–19)
Per-seed CSV `RUN_LOGS/hmm_compliant_density/per_seed.csv`; aggregate `aggregate.csv`; plots `plots/`; movies
`threejs_hmm_dens_*`. Modified files: `ExplicitHmmDimerGlidingHarness.java` (enriched MATROW + `-shcell` single-head
reference mode calling `TwoBodyConverterMotor.glideSpeedS2` — single-head model NOT modified). `run_hmm_gliding.sh`
unchanged. Orchestrators in scratchpad.

## Commands (deliverable 18)
```
# dimer cell (compliant), density D, D0, 5000 steps:
./scripts/run_hmm_gliding.sh -matsmoke -mdensity <D> -seed <s> -steps 5000 -cullr 0.12 -fillen 2.0 -branchEA 0.03 -dmode 0
# matched single-head reference, density D heads/µm²:
./scripts/run_hmm_gliding.sh -shcell -shdensity <D> -seed <s> -steps 5000
# dt/2 spot: add -dt 1.25e-6 -steps 10000 ; D2 spot: -dmode 2 ; cull spot: -cullr 0.10/0.15/0.20
```
Seeds 101–108 (staged: 8 at ≤750, 6 at 1500, 4 at 3000). dt 2.5e-6 (spot 1.25e-6). Duration 12.5 ms (25 ms at dt/2).
