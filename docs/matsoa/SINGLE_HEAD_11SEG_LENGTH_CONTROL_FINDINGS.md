# Single-head explicit gliding — matched-filament-length CONTROL (11-seg) — findings

**Status: COMPLETE (2026-07-21).** A control campaign that re-runs the explicit single-head long-run gliding density
sweep with the **HMM-dimer's 11-segment actin geometry** (contour 1.931 µm) instead of the validated single-head
default (12 segments, 2.106 µm), to quantify the ~9 % filament-length mismatch that was the leading caveat of
`SINGLE_HEAD_GPU_DENSITY_SWEEP_LONG_FINDINGS.md`. 20 cells (5 densities × 4 seeds), **0 invalid / 0 solveFail**.

**Headline — the ~9 % length mismatch is a small, uniform velocity rescaling that does NOT change the inferred
dimerization effect.**
- **11-seg / 12-seg single-head velocity ratio = 1.034 ± 0.018** (mean ± SD over 5 shared densities) — the shorter,
  dimer-matched filament glides ~**3.4 % faster**, uniformly across density (per-density 1.013–1.058, no ρ trend).
- The correction **slightly sharpens** the dimerization effect rather than weakening it: dimer/single =
  **0.725** at matched length (11-seg) vs **0.750** against the mismatched 12-seg reference (Δ = −0.025).
- Half-saturation and curve shape are unchanged; the conclusion "dimerization is a Vmax (amplitude) effect, not a
  ρ½ shift" is **robust to the length mismatch**.

## Change made (only the actin segment count)

The single-head `buildS2Mat` → `buildGlide2D` filament length is set by the existing runtime override
`TwoBodyConverterMotor.G4_NSEG_RUN` (default `G4_NSEG=12`). A harness-only `-production-cell -nseg 11` sets **only**
that override, giving 11 segments × the same 0.1755 µm segLen = **1.931 µm** — identical to the dimer campaign's
`buildMat(filLenUm=2.0)` → `round(2.0/SEGLEN)=11` segments (same 64 mono/seg, same segLen). **Verified per cell**:
`nSeg==11`, `fil_contour_um==1.931`, and an abort gate fires if the built geometry ≠ requested.

**No motor physics retuned.** Density N = round(ρ·3.0) is nSeg-independent (unchanged), and motor
chemistry/mechanics/binding gates/Brownian/dt (2.5e-6)/whole-window LS estimator/GPU device path/no-CPU-fallback are
all identical to the 12-seg sweep. `G4_NSEG_RUN` defaults to 12, so the completed 12-seg sweep outputs are untouched
(`PROD_SCI` and `-nseg` both default-off). The only per-segment consequence is one fewer actin segment (11 vs 12) —
apparatus geometry, exactly the dimer's.

## Campaign

- Densities {250, 400, 700, 1500, 3000} heads/µm² × seeds 101–104 = 20 cells; dt 2.5e-6; 40 000 steps = 0.1 s;
  whole-window LS centroid·b̂; GPU device-resident (`ExplicitCompleteMatHarness -production-cell -nseg 11`); no 2×.
- Output: `RUN_LOGS/single_head_density_sweep_long_11seg/` — per-cell JSON + `.done` + logs, `per_seed.csv`,
  `density_summary.csv`, `ANALYSIS.md`, `COMPARISON_11SEG.md`, `plots/`.
- Throughput ~230–420 steps/s; all 20 cells `status=ok`, 0 invalid / 0 solveFail. Code rev `73d82bf` (working-tree
  harness edits uncommitted, as with the 12-seg sweep).

Reproduce:
```
./scripts/run_singlehead_density_sweep.sh -nseg 11 -outdir RUN_LOGS/single_head_density_sweep_long_11seg \
    -cells "250:101 250:102 ... 3000:104"
python3 scripts/single_head_density_analysis.py RUN_LOGS/single_head_density_sweep_long_11seg
python3 scripts/single_head_11seg_compare.py                 # 3-way table + plots + decision
```

## Three-way comparison (11-seg single / 12-seg single / 11-seg dimer)

Raw velProd µm/s, whole-window, n=4/density, no 2× factor. SD/SEM/95%CI for the 11-seg arm:

| ρ | 11-seg single-head (SD, SEM, 95% CI) | 12-seg single-head | 11-seg dimer | **11/12 ratio** | dimer/11-seg ratio |
|---|---|---|---|---|---|
| 250  | **+1.975** (0.219, 0.110, [+1.63,+2.32]) | +1.907 | +1.370 | **1.036×** | 0.694× |
| 400  | **+2.433** (0.341, 0.171, [+1.89,+2.98]) | +2.299 | +1.759 | **1.058×** | 0.723× |
| 700  | **+3.083** (0.272, 0.136, [+2.65,+3.52]) | +3.043 | +2.304 | **1.013×** | 0.747× |
| 1500 | **+3.703** (0.136, 0.068, [+3.49,+3.92]) | +3.546 | +2.709 | **1.044×** | 0.732× |
| 3000 | **+3.988** (0.137, 0.068, [+3.77,+4.21]) | +3.911 | +2.914 | **1.020×** | 0.731× |

Mechanism channels (11-seg single-head) — same picture as the 12-seg sweep (efficiency-decline ceiling, not attachment):

| ρ | boundHeads | vel/boundHead | ATP turnover | attach lifetime (ms) | Σinvalid | ΣsolveFail |
|---|---|---|---|---|---|---|
| 250  | 1.78  | +1.109 | 217  | 0.815 | 0 | 0 |
| 400  | 2.72  | +0.893 | 341  | 0.797 | 0 | 0 |
| 700  | 4.58  | +0.673 | 602  | 0.755 | 0 | 0 |
| 1500 | 9.89  | +0.374 | 1339 | 0.735 | 0 | 0 |
| 3000 | 18.94 | +0.211 | 2605 | 0.723 | 0 | 0 |

(Bound heads at matched length are marginally lower than the 12-seg sweep — e.g. ρ3000 18.94 vs 20.77 — consistent with
the ~9 % shorter filament presenting proportionally fewer binding sites; vel/boundHead is correspondingly slightly
higher, and the two effects give the net ~+3 % velocity. Lifetime and ATP-turnover-per-bound are unchanged.)

Plots (`plots/`): `raw_11seg_vs_12seg_singlehead.png`, `raw_11seg_single_vs_dimer.png`, `ratio_vs_density.png`.

## Decision (per the stated rule)

**MODEST RESCALING (near-negligible).** The ~9 % filament-length mismatch rescales single-head velocity by
**+3.4 % ± 1.8 %**, a **uniform, density-independent amplitude shift** (11/12 ratio flat at ~1.03, no ρ trend). This is
just above the 3 % "negligible" threshold and well below the 10 % "material" threshold.

**It does NOT materially change the inferred dimerization effect:**
- The dimer/single-head raw-speed ratio is **0.725** at fully matched length (11-seg vs 11-seg) vs **0.750** against the
  original mismatched 12-seg reference — i.e. correcting the mismatch **slightly increases** the inferred dimerization
  slowdown (from −25.0 % to −27.5 %), it does not erode it. Dimerization lowers raw glide speed by ~27–28 % at matched
  geometry.
- Half-saturation and curve shape are unchanged (the 11-seg curve is the 12-seg curve scaled by ~1.03; both still
  plain-hyperbolic, both still overlay the dimer when Uyeda-normalized). The "Vmax effect, not a ρ½ shift" conclusion
  of the main findings is **robust**.

**Net:** the main single-head↔dimer comparison stands as reported; the ~9 % length caveat accounts for ≈3 percentage
points of the raw ratio, in the direction that makes the dimerization effect marginally larger, not smaller.

## Caveats

1. **The 11-seg run is a control, not a replacement.** The validated single-head production model remains 12-seg; this
   campaign exists only to bound the length caveat. The completed 12-seg sweep outputs are unchanged.
2. **Velocity estimator / duration / density / physics** are all identical to the 12-seg sweep and the dimer campaign
   (whole-window LS, 0.1 s, motor objects per µm², explicit-s2-l40 unchanged) — the only variable is the actin segment
   count (11 vs 12).
3. **`DEVICE_VALIDATED=false`** (research device path; CPU runner is the arbiter for absolute-number promotion — same
   posture as the main sweep). 0-invalid/0-solveFail across all 20 cells.
4. **Provenance:** cells record `code_rev=73d82bf`; the `-nseg` harness edit is an uncommitted working-tree change (as
   were the `-production-cell` edits for the 12-seg sweep).
