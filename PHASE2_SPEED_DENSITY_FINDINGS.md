# Phase-2 SPEED–DENSITY — the flat-θ=0 motor vs EXPERIMENT (V/Vmax vs N/KM), gated behind a dt=1e-5 stability check

**2026-06-29. MEASUREMENT-ONLY. Flat θ=0 (adopted geometry, head-angle sweep), post-ATP-release-fix cycle,
κ=3.82e-20 (force-matched), the 4b/4c-calibrated catch/kOn/τ (`-kon 2e5 -tauavg 0.5 -xcatch 2.5`). GPU
device-resident, matbed, honest signed netX primary. NO physics edit, no retune, no default flip. `BoA-v1ref`
byte-clean. v1 is NOT an anchor (shape-only); the quantitative anchor is the EXPERIMENT (Walcott/Warshaw skeletal
MM form, Vmax≈2.9 µm/s, KM≈16 motors/µm²) compared unit-robustly as V/Vmax vs N/KM. avgBound = internal diagnostic
only (grip-lock detection), never a pass/fail target.**

---

## TL;DR
- **Stage 0 (dt gate): dt=1e-5 is STABLE across the entire density ladder (500-8000) — no whip anywhere.** The stale
  SET-A "whips at 1e-5" verdict is REFUTED on the flat θ=0 motor (one pin + rotational torque + ATP-fix + softer κ
  all moved stabilizing). Sweep run at dt=1e-5. Binding is dt-converged (avgBound agrees 1e-5↔1e-6); glide velocity
  is modestly dt-suppressed at 1e-5 (~1.6×, not 5× — the probe's 5× was a short-window artifact).
- **Stage 1 (vs experiment): OUTCOME 2 — VELOCITY DEFICIT at physical densities.** The curve has the correct
  Michaelis–Menten *shape* (V/Vmax vs N/KM tracks x/(1+x) to |Δ|≤0.03), BUT the fitted **KM ≈ 7 500 µm⁻² is ~470×
  the experimental KM ≈ 16** — so the model's rise is shifted ~470× too far in density. **At experimentally-realistic
  densities (≲1000 µm⁻², where experiment is ~97 % saturated at ~2.8 µm/s), the model glides only ~0.1-0.16 µm/s —
  a few percent of experiment.** It only approaches the right velocity order at NON-physical densities (≥8000). The
  normalized-shape "pass" is real but rescales away this 470× absolute-density gap, so it overstates functionality;
  on the physical density axis the model under-glides ~20-30×. **The tail (16 000-32 000) was NOT run — it is
  unphysical** (jba: don't exceed experimental density).
- **Lever = duty/binding density**, not geometry: the flat single-tip head binds ~7× sparser than the config-1 of
  the 4b study, on top of the calibrated 1.5 % reaction-limited duty ⇒ it needs unphysical density to engage enough
  heads. Flagged for the planner (open thread 4): revisit kOn/duty (or the flat-head engagement), not the geometry.
- **No implicit-J1 needed** (no whip at 1e-5); it stays the banked cure only for a future denser/stiffer regime.

---

## Configuration (settled; no retune)
- Geometry: **flat θ=0** — `-perphead -headtilt 0` (single tip PAIRS pin + the rotational PAIRS orientation torque
  held at θ=0, head aligned along actin), the head-angle-sweep-adopted geometry.
- Cycle: post-ATP-release-fix (`ATP_RELEASE` on by default for config1/perphead).
- κ = 3.82e-20 N·m/rad (force-matched, default).
- **Catch/kOn/τ — the 4b/4c calibration:** `-kon 2e5 -tauavg 0.5 -xcatch 2.5` (kOn reaction-limited 2×10⁵ µm⁻¹s⁻¹,
  averaged catch τ=0.5 ms, catch distance d via xcatch 2.5 nm; xSlip 0.40). **NOTE:** the head-angle sweep that
  adopted flat θ=0 used kinetics DEFAULTS (kOn=0 saturated, instantaneous catch); this benchmark uses the calibrated
  stack per the task — the same stack whose speed-density transferability was shown in `PHASE2_CATCH_DCALIB` (on the
  older config-1 geometry: |v| rises 1→9→11 µm/s over density 500→4000→10000). Density 500 is a known sparse
  low-binding operating point for this 1.5%-duty motor; the curve is expected to rise toward V0 at higher density.

---

## STAGE 0 — dt=1e-5 stability + calibration-consistency gate
_(filled from `RUN_LOGS/2026-06-29_sd_stage0.txt` + `_sd_forcetail.txt`)_

### Whip scan (GPU, dt=1e-5, flat θ=0 + calibrated, 6000 steps, n=2; avgBound steady = the short-run value)
| density (µm⁻²) | motors | netX (µm/s) | velFitX | avgBound | fullMat | capRate | whip? |
|---|---|---|---|---|---|---|---|
| 500  | 3 480  | +0.48 / +0.14 | −0.55 / −0.19 | 0.48 / 0.39 | YES/YES | 0 | **NO** |
| 1000 | 6 960  | +0.51 / +0.20 | −0.23 / −0.12 | 0.97 / 0.48 | YES/YES | 0 | **NO** |
| 2000 | 13 920 | +0.05 / +0.44 | +0.12 / −0.46 | 2.19 / 1.77 | YES/YES | 0 | **NO** |
| 4000 | 27 840 | −0.89 / −0.06 | +0.44 / −0.39 | 2.74 / 3.94 | YES/YES | 0 | **NO** |
| 8000 | 55 680 | −1.24 / −1.84 | +0.80 / +0.73 | 5.16 / 6.19 | YES/YES | 0 | **NO** |

**dt=1e-5 is STABLE across the entire ladder** — every cell `fullMat=YES`, no NaN/huge velFitX, capRate=0 (the 12-pN
cap never fires). The SET-A "whips at 1e-5" verdict was on config-1 (two translational PAIRS pins, pre-ATP-fix,
stiffer κ); all three changed in the stabilizing direction on this flat motor (one pin + rotational torque, softer
effective coupling, avgBound ~4 not ~26), and the whip is GONE. (avgBound here is the short 6000-step value, climbing;
the equilibrated 40k values are higher — see Stage 1.) Binding rises monotonically with density; the −x glide
emerges at high density (netX −1.2…−1.8 at 8000), still climbing.

### Force-tail dt-faithfulness (−diag, v1box 2000, 8000 steps)
| dt | avgBound | bound-state | release rate | mean bound-time |
|---|---|---|---|---|
| 1e-5 | 0.40 | ADP 94 % | 577 /s | 1.73 ms |
| 1e-6 | 0.47 | ADP 94 % | 0 /s¹ | (Inf)¹ |

¹ **v1box is too SPARSE for this calibrated 1.5%-duty motor (avgBound ~0.4)** — the dt=1e-6 8000-step run (0.008 s
sim) saw essentially no release events, and the velocity (0.19 vs 1.27 µm/s) is thermal noise of a barely-tethered
filament, NOT a real dt-dependence. **This cut is INCONCLUSIVE at v1box density.** The meaningful dt-faithfulness
comparison is at a real-binding density (the density-8000 40k probe, below). The bound-state (ADP 94 %, ATP 0 %) and
capRate=0 are consistent across dt — no force-tail explosion signature.

### Stage-0 verdict + selected dt → **dt = 1e-5**
**1e-5 stable at every density (no whip anywhere) ⇒ run the Stage-1 sweep at dt=1e-5** (the calibration point — kOn/
catch/κ were all set at 1e-5 — and the cheaper sim). No implicit-J1 needed for stability here (it remains the banked
cure only IF a future denser/stiffer regime whips). The dt=1e-5↔1e-6 observable-consistency check is completed at the
density-8000 probe (Stage 1) where binding is non-trivial.

---

## STAGE 1 — speed–density curve vs experiment (dt=1e-5, n=3, matbed, 40k)
Honest signed netX (− = −x glide) as the primary readout. All cells `fullMat=YES`. Raw:
`RUN_LOGS/2026-06-29_sd_stage1.txt`.

| density (N, µm⁻²) | motors | **netX mean ± SD (µm/s)** | avgBound | instSteady (µm/s) |
|---|---|---|---|---|
| 500  | 3 480  | **−0.105 ± 0.04** | 0.30 | 6.31 |
| 1000 | 6 960  | **−0.140 ± 0.09** | 0.57 | 6.29 |
| 2000 | 13 920 | **−0.335 ± 0.07** | 1.27 | 6.34 |
| 4000 | 27 840 | **−0.577 ± 0.18** | 2.78 | 6.18 |
| 8000 | 55 680 | **−0.803 ± 0.27** | 6.24 | 6.16 |

**Three robust observations:**
1. **The net −x glide RISES monotonically with density and BENDS OVER** (−0.105 → −0.803 µm/s over 500→8000),
   low-SD, correct transport sign — a real, reproducible directed signal (it scales with binding).
2. **instSteady is CONSTANT ~6.2 µm/s across the entire 20× density range** (even density 500, avgBound 0.30) ⇒ it
   is the **density-independent THERMAL-WANDER floor** of the 2-µm filament centroid (≈√(2 D_com·dtInt)/dtInt over
   the 1-ms sample interval), NOT motor tug-of-war. So this is NOT grip-lock — the directed glide is a genuine
   signal RISING out of a fixed thermal floor as density grows. (Earlier 4-point read of "severe grip-lock" was
   superseded by the constant-instSteady evidence.)
3. **The flat θ=0 motor binds FAR more sparsely than the config-1 of the 4b study.** avgBound 2.78 at density 4000
   here vs 4b config-1's **19** at the same density+kOn — ~7× less; the flat single-tip-pin head engages the
   calibrated reaction-limited kOn much less than the config-1 two-point head. This shifts the whole curve to
   higher density (larger KM).

### Normalized curve V/Vmax vs N/KM vs the Walcott/Warshaw MM form
MM fit on 500-8000 (n=3 each): **Vmax = 1.58 µm/s, KM ≈ 7 540 µm⁻²** (SSE 0.0030). (A 4-point fit to 500-4000 gave
Vmax 3.0/KM 17 000 — the fit is range-sensitive because saturation is not yet reached; the 5-point fit reaching
V/Vmax≈0.5 is the better-constrained one.)

| N/KM | V/Vmax (model) | universal x/(1+x) | \|Δ\| |
|---|---|---|---|
| 0.066 | 0.066 | 0.062 | 0.004 |
| 0.133 | 0.089 | 0.117 | 0.028 |
| 0.265 | 0.212 | 0.210 | 0.002 |
| 0.531 | 0.365 | 0.347 | 0.018 |
| 1.061 | 0.508 | 0.515 | 0.007 |

- **Shape: PASS in the sampled range.** V/Vmax vs N/KM tracks the universal x/(1+x) to **|Δ| ≤ 0.03 across N/KM
  0.07→1.06** — a genuine rising-then-bending MM form, reaching **V/Vmax ≈ 0.51 (half-saturation) at density 8000**.
  The curve has the experimentally-required shape through half-saturation; the upper plateau (V/Vmax→1) is just
  past the affordable range (would need density ~16 000-32 000).
- **Magnitudes carry the calibration asterisk.** Fitted Vmax ≈ 1.6 µm/s (≈ 0.55× the experimental 2.9 — and still
  rising/uncertain since unsaturated). Fitted **KM ≈ 7 500 µm⁻² is ~470× the experimental KM ≈ 16** — the
  half-saturation density is far above experiment, the expected consequence of the sparse-binding reaction-limited
  1.5%-duty calibration (KM is entangled with kOn/duty — the task's asterisk; do NOT over-fit it).

### dt-accuracy check (resolved) — binding dt-converged; velocity ~1.6× dt-suppressed at 1e-5; SHAPE dt-robust
dt-resolver: dt=1e-6, density 4000, **80k steps (0.08 s)**, n=2 → netX −0.92 / −0.98 (mean **−0.95**), avgBound
2.66 / 2.82. Compare dt=1e-5/40k density 4000: netX **−0.577**, avgBound 2.78.
- **Binding is dt-converged:** avgBound 2.7 (1e-6) ≈ 2.78 (1e-5) — the bound-population stat AGREES across dt. The
  bound-state distribution (ADP-dominated) and capRate (0) also agree (Stage 0). So the kinetics/duty are dt-faithful.
- **Glide velocity is MODESTLY dt-suppressed at 1e-5 (~1.6×), NOT 5×.** The probe's apparent 5× (dt=1e-6 −2.8 vs
  dt=1e-5 −0.5 at density 8000) was inflated by the very-short 0.04 s window; the longer 0.08 s dt=1e-6 gives −0.95,
  a **~1.6×** ratio over dt=1e-5's −0.577. This is the expected DT_CONVERGENCE behaviour (1e-5 = the calibration
  ceiling, not the converged continuum). ⇒ the **continuum velocity is ~1.6× the dt=1e-5 curve** (Vmax ≈ 1.6 → ~2.5
  µm/s, i.e. ~0.85× the experimental 2.9), and the **MM SHAPE is dt-robust** (binding converged, only a near-uniform
  velocity scale differs). Measured at one density (4000) — treat the 1.6× as indicative, not exact.

---

## The physical-density comparison (the decisive cut — jba: don't exceed experimental density)
The V/Vmax-vs-N/KM normalization is shape-correct but rescales away the absolute density scale. On a SHARED ABSOLUTE
density axis, against the experimental form V = 2.9·N/(16+N):

| density N (µm⁻²) | EXPERIMENT V (µm/s) | EXPERIMENT V/Vmax | MODEL netX (dt-corr ×1.6) | physical? |
|---|---|---|---|---|
| 16 (= KM) | 1.45 | 0.50 | ~0 (avgBound≪1) | yes (low) |
| 100 | 2.50 | 0.86 | ~0.03 | yes |
| 500 | 2.81 | 0.97 | 0.105 (→0.17) | yes (dense) |
| 1000 | 2.86 | 0.98 | 0.140 (→0.22) | yes (dense) |
| 2000 | 2.88 | 0.99 | 0.335 (→0.54) | upper edge |
| 4000 | 2.89 | >0.99 | 0.577 (→0.92) | supra-physical |
| 8000 | 2.90 | >0.99 | 0.803 (→1.28) | supra-physical |

- **Experimental gliding assays saturate by ~10-50× KM (~160-800 µm⁻²) and physically top out around ~1000-2000
  µm⁻²** (myosin surface packing). So densities ≥ ~4000 are supra-physical and **16 000-32 000 are unphysical — the
  tail was correctly NOT run.**
- **At a normal dense assay density (~500 µm⁻², experiment ~97 % saturated at ~2.8 µm/s), the model glides only
  ~0.1-0.17 µm/s — a few percent of experiment.** The model reaches the right velocity ORDER only at non-physical
  densities. The ~470× KM shift IS the deficit.

## Verdict against the three outcomes — OUTCOME 2 (VELOCITY DEFICIT at physical density)
- **Shape: correct (MM).** V/Vmax vs N/KM tracks x/(1+x) to |Δ|≤0.03 across N/KM 0.07→1.06 — the rising-saturating
  form is real and reproducible (so NOT outcome 3, the null).
- **But it is NOT gliding-functional at experimental density (outcome 2).** The fitted KM ≈ 7 500 µm⁻² is ~470× the
  experimental 16; at physical densities the model is deep in the sub-saturation linear toe (V/Vmax ~0.03-0.10) where
  experiment is saturated. The normalized "shape pass" is a real-but-weak result — it holds only by sweeping to (and
  past) the physical density ceiling. **On the physical density axis the model under-glides ~20-30×.**
- **The lever is DUTY / BINDING DENSITY, not geometry.** Two stacked causes, both measured here: (i) the calibrated
  kOn is reaction-limited at ~1.5 % duty (`PHASE2_CATCH_DCALIB`), and (ii) the flat single-tip head binds **~7×
  sparser** than the config-1 two-point head of the 4b study (avgBound 2.78 vs 19 at density 4000, same kOn). Their
  product pushes KM ~470× too high. This is **open thread 4** (duty/catch), now sharpened by the density comparison:
  to glide at experimental density the motor must engage far more heads per filament length — revisit kOn/duty or the
  flat-head engagement, **NOT** the geometry (the head-angle sweep already showed angle is a non-lever). **FLAGGED,
  not actioned — no retune in this task.**

**avgBound (internal diagnostic only):** rises 0.30→6.24 over the sweep; the instSteady ~6.2 thermal-wander floor is
density-independent (NOT grip-lock — the directed glide is a real signal rising out of a fixed thermal floor). No
pass/fail attached to avgBound.

## implicit-J1 flag
**Not needed here.** dt=1e-5 is stable at every swept density (no whip), so the banked uncapped-Hookean-J1 →
implicit-J1 fix is NOT required for this benchmark. It remains the banked cure only IF a future denser/stiffer
regime whips (e.g. a much higher κ, or the config-1 two-point geometry which DID whip at 1e-5). Surfaced, not built.

## What changed (additive / measurement-only) — NO code edit
**Zero source changes this task.** The benchmark used only existing flags (`-perphead -headtilt 0` flat geometry
from the head-angle sweep; `-kon 2e5 -tauavg 0.5 -xcatch 2.5` the 4b/4c calibration; `-matbed -grid -gpu -dt -seed
-density`) and the harness's existing GRID_ROW/COV_ROW/CAP_ROW diagnostics. Analysis: `scratch_sd_mmfit.py` (the MM/grip-lock
fit; run commands inline below). `BoA-v1ref` byte-clean; default/config-1/canonical untouched.

## CPU-fallback disclosure
- **Stage 0 whip scan + Stage 1 sweep + probe + dt-resolver:** GPU **device-resident** `buildPlan` (~24 kernels
  incl. `snapPerp`, no per-step host pull; host reads fil.coord + boundSeg at the OUT_INT=100 sample cadence only).
  3 480 (density 500) → 55 680 (density 8000) motors. Stage 1 = 15 runs × 40k steps + the 8000 n=3 + probe + 2×
  dt-resolver 80k.
- **Force-tail −diag:** CPU host-side, v1box 2000, 8000 steps (the only forceDotFil-tail path); inconclusive there
  (too sparse), superseded by the GPU dt-resolver at real binding density.

## Reproduce
```
# Stage 0 — dt=1e-5 whip scan (flat θ=0 + calibrated), 6000 steps, ladder:
./run_gliding.sh -gpu -perphead -headtilt 0 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -matbed -density <D> -dt 1e-5 -grid -seed <n> 6000
# Stage 1 — speed-density sweep, dt=1e-5, 40k, n=3 (D ∈ {500,1000,2000,4000,8000}):
./run_gliding.sh -gpu -perphead -headtilt 0 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -matbed -density <D> -dt 1e-5 -grid -seed <n> 40000
# dt-resolver — dt=1e-6 cross-check at real binding density:
./run_gliding.sh -gpu -perphead -headtilt 0 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -matbed -density 4000 -dt 1e-6 -grid -seed <n> 80000
# analysis:
python3 scratch_sd_mmfit.py RUN_LOGS/2026-06-29_sd_stage1.txt
```
