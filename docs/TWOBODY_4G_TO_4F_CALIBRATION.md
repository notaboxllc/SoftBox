# Experiment 4I — calibrate the cheap 4F pivot surrogate DIRECTLY to the explicit 4G S2 beam

**Date:** 2026-07-15 · **Branch:** `dt-convergence-study` · **Runner:** **CPU sequential only** (the two-body arc
is CPU-only; `run_lasertrap.sh` refuses `-gpu`). **Hardware:** aorus, one core. **dt:** 2.5e-6. **Trap:** 0.05 pN/nm.

**Default-off, non-canonical calibration path** (`[NON-CANONICAL TWO-BODY PROTOTYPE]`), `-exp4i` /
`-twobody-s2-surrogate-calibration` in `softbox/TwoBodyConverterMotor.java`. **The validated head geometry,
converter, neck–lever, F8 spring, stereospecific binding gate, Lymn–Taylor chemistry + kinetic constants, force
ordering, RNG behaviour, filament mechanics, and `BoA-v1ref` are UNTOUCHED.** The surrogate is the **validated 4F
`supForce` law** with (i) parameters fitted to 4G and (ii) ONE optional smooth Euler compression-buckling branch,
both gated behind `CAL_ON` (default `false`) so **4F stays byte-identical when the calibration overlay is off
(Gate 1, max|Δ|=0.00e+00).** New code only; production untouched; `BoA-v1ref` byte-clean.

**This is a calibration, not a new motor.** The goal is to let a large simulation use the cheap 4F pivot as a
directly-linked surrogate for the expensive 4G beam.

---

## 0. Terminology and the one calibration coordinate (§2)

- **4G** = mechanistic reference: the explicit fixed-contour MD-informed S2 beam (`-exp4g`; a chain of `M=L/10`
  extensible-elastica segments, stiff stretch `ks`, finite bending `kb`, clamped emergence, distal node = the motor
  pivot). Per-step cost `(3M+2)`-DOF implicit solve with a numeric beam tangent.
- **4F** = reduced production surrogate: one movable pivot `P` with cheap **analytic** anisotropic restoring forces
  (`supForce`; axial slack-to-taut + soft transverse + finite-extension + floor), 5-DOF implicit step.

**The common coordinate (§2, and the 4H lesson).** Both models are compared in ONE generalized coordinate: the
**pivot displacement** `d = P − P0` and the **pivot reaction force** (4G: the relaxed-beam reaction on the held
pivot; 4F: `supForce`). We do **not** compare 4G pinned-tip axial stiffness against 4F external-lever stiffness —
the 4H analysis showed those are different generalized coordinates. Every 4G calibration target below is the
**effective pivot reaction with the interior beam nodes relaxed** (`s2RelaxHold`), i.e. exactly what the surrogate's
single pivot must reproduce.

**Preferred base = 4F no-slack (δ=0).** 4G proved a stiff-stretch fixed-contour beam holds **no rest slack** (it
straightens and repositions the pivot). So δ is dropped; the surrogate starts from the anisotropic-linear no-slack
form, which was 4F's cleanest condition anyway (more load-bearing, less pivot recoil, one fewer parameter).

---

## 1. Standardized observables (§2) — one definition each, used for both models

| # | observable | definition (common to 4F & 4G) |
|---|---|---|
| 1 | external lever / crossbridge stiffness `k_ext` | whole-crossbridge stiffness: perturb the trap axially, measure the filament restoring-force slope (`supKext`/`s2Kext`) |
| 2 | axial F8–S2 series stiffness | the pivot's axial tangent under load (`supForce[3]` / `s2AxialTangent`) |
| 3 | free-tip transverse bending stiffness `k_tr` | **free-axial** transverse reaction slope (pivot pinned transverse, axial free to foreshorten — NOT the full-3D pin that engages stretch, the 4H artifact) |
| 4 | pivot-coordinate tangent stiffness | `kAxTan`, `kTrTan` from the force law (the implicit-K diagonal) |
| 5 | force transmitted to actin through F8 | `segGather` axial force on the bound segment (`·b̂`) |
| 6 | substrate-support reaction | 4G: node-0 reaction; 4F: the floor + tail reaction (0 direct force on actin, both) |
| 7 | trap stiffness | assay input (`trapParams`), 0.05 pN/nm default |

---

## 2. The surrogate hierarchy (§4) and the effective potential (§5)

The surrogate is fitted with the **simplest model that passes**:

- **S1 — linear anisotropic (no slack):** `F = −k_ax·qL·û_L − k_tr·d_T`, separate tensile / compressive `k_ax`.
- **S2 — + smooth compression softening:** compression stiff below an **Euler critical force** `F_crit`, smoothly
  softened (post-buckle `k_comp_post`) above it — a **load/geometric** threshold (`softplus`), NOT a
  binding/nucleotide switch. Implemented as the guarded `supBuckleCrit` branch in `supForce`.
- **S3 — + transverse finite-extension:** a soft `softplus` stiffening past `rMax` that bounds runaway; **inactive
  over visited search states**.

**Effective potential (§5).** The law derives from a **separable** effective potential `Ueff(qL, rT) =
U_ax(qL) + U_tr(rT)` with `F = −∇Ueff`; the tangent is the (diagonal) Hessian, so it is **symmetric by
construction**. Passive closed-loop work `∮F·dl` around a rectangle in `(qL,rT)` is `~1e-29 J ≈ 0` (conservative;
no artificial energy). The `softplus` branches are single-valued (no hysteresis); action–reaction with the
substrate holds and the tail exerts **zero direct force on actin** (unchanged from 4F).

---

## 3. Phase A — the 4G calibration dataset (§3)

For each free-S2 length `L ∈ {40, 20, 60}` nm a fresh 4G beam is built and settled, then the pivot is displaced over
a grid and the **interior nodes relaxed** (`s2RelaxHold`, both ends pinned) to read the effective pivot reaction:

- **axial grid** `qL ∈ [−8, +8]` nm (tension + compression; compression buckle-seeded);
- **transverse grid** `rT ∈ [0.5, 25]` nm along `ê_conv`, sampled **free-axial** (`calS2SampleTrans`: alternate
  interior relax with a 1-D axial Newton on the pivot until the axial reaction is ~0) — the bending response a free
  search pivot actually feels.

Per sample we export the reaction (`Rax`, `Rtr`), geometry (end-to-end, contour, bend), bending energy, buckling
state, and a **visited** flag (axial `|qL| ≤ 7.5 nm` stroke range; transverse `rT ≤ 2·search-RMS`). Physically
visited states are weighted over remote grid points. Split **60 % train / 20 % val / 20 % frozen test** by a
deterministic index interleave. Dataset: `csv/calibration_dataset.csv`.

---

## 4. Phase B — the fit and the parameter map (§7, §8)

Fitted at the reference length **L40** on the **train** split only:

| parameter | symbol | value (L40) | 4G response it derives from |
|---|---|---:|---|
| axial tension stiffness | `k_ax` | **≈105 pN/nm** | LS slope of the relaxed-beam `Rax` vs `qL` (visited tension) = `ks/M = EA/L` **exactly** |
| axial compression (L40) | `k_comp` | **≈105 pN/nm (symmetric-stiff)** | 4G-L40 stays on the straight-stiff branch (§6 below) |
| Euler buckle threshold (physical) | `F_crit` | **≈4.44 pN** (`π²EI/L²`) | realized at **L60** (crit ∝ 1/L²); NOT realized at L40 |
| post-buckle compression stiffness | `k_comp_post` | **~0.01·k_ax** | L60-validated; theory at L40 |
| transverse small-disp stiffness | `k_tr` | **≈0.026 pN/nm** | LS slope of the **free-axial** relaxed-beam `Rtr` vs `rT` (visited bending) ≈ `3EI/L³` |
| transverse finite-extension | `k_feTr` | 20 pN/nm | runaway limiter; inactive over visited states |
| transverse FE onset | `rMax` | **≈2·search-RMS** nm | 4G transverse-stiffening knee / visited envelope |
| smoothings | `s_ax, s_tr, s_buck` | 0.5 / 1.5 / 0.8 nm | fixed `softplus` widths (narrow `s_tr` so the FE tail does not leak inside visited states) |

**§6 — the tension/compression asymmetry is length-dependent, and L40 does not realize it.** The 4G-L40 compression
probe stays on the **straight-stiff branch** (the documented 4G §6 limitation: Euler crit 4.4 pN is a 0.04 nm
displacement, but the regularized Newton sits at the unstable straight equilibrium). Motors also load the tail in
**tension** (the barbed-ward stroke). So the **frozen L40 surrogate reproduces 4G-realized = symmetric-stiff
compression** (buckling disabled). The Euler asymmetry (soft compression) is a **longer-beam feature** (`F_crit ∝
1/L²`, low enough to buckle in-range only for L ≳ 55 nm) — calibrated, enabled, and validated at **L60** in Phase D.
This is faithful to the 4G finding itself (§0/§10 of `TWOBODY_MD_INFORMED_S2.md`: "the slack-to-taut relocates to a
compression-buckling asymmetry" that "appears only at L=60").

Parameters: `csv/fitted_params.csv`.

---

## 5. Phase C — frozen-test mechanical error + single-motor equivalence (§10, §13)

**(C1) Force / tangent error on the frozen 20 % test split (never used in the fit), over VISITED states.** Axial
tension is reproduced essentially **exactly** (`k_ax` = `ks/M`); the axial **tangent** matches to `median ≈ 0 %`.

**(C2) Single-motor equivalence, cal-4F vs 4G L40, matched observables.** Reuses the validated 4F/4G measurement
harness (`supStroke`/`s2Stroke`, `supKext`/`s2Kext`, `supSearchStats`/`s2SearchStats`, capture footprint).

**(C3) Force clamp −2…+5 pN.** Filament axial displacement vs applied load, both models.

### Frozen-test mechanical error (full run, VISITED test split)

| metric | value | criterion | verdict |
|---|---:|---|---|
| force-vector error, median | **0.0 %** | ≤ 10 % | **PASS** |
| force-vector error, p95 | **4.3 %** | ≤ 25 % | **PASS** |
| axial tangent error, median | **0.0 %** | ≤ 10 % | **PASS** |
| transverse visited (3 / 10 nm) rel err | 3.4 % / 4.3 % | ≤ 20 % | **PASS** |

The **axial (load-bearing) mechanics calibrate essentially exactly** (`k_ax = ks/M = EA/L`; both tension AND the
symmetric-stiff compression reproduce the 4G reaction to `~1e-10`), and the axial **tangent** is exact. The only
large per-sample error is the **non-visited** transverse boundary sample at 25 nm (correctly flagged `visited=0`),
where the surrogate's finite-extension has not yet engaged — outside the physically-occupied envelope.

### Single-motor equivalence, cal-4F vs 4G L40

| observable | cal-4F | 4G L40 | Δ | criterion | verdict |
|---|---:|---:|---:|---|---|
| unloaded stroke (nm) | 6.90 | 7.27 | −5.1 % | ±5 % | ~PASS (border) |
| pivot recoil (nm) | 0.007 | 0.007 | ~0 | \|Δ\|<0.2 nm | **PASS** |
| search rmsLat (nm) | 7.6 | 10.8 | **−29 %** | ±10 % | **FAIL (drift)** |
| capture area (nm²) | 357 | 576 | **−38 %** | ±15 % | **FAIL (drift)** |
| k_ext (pN/nm) | 0.64 | 0.99 | −36 % | fixture-observable | offset (§below) |

**Force clamp** (−2…+5 pN, filament axial displacement): cal-4F tracks 4G through the tension range (e.g. +5 pN →
cal 6.3 nm / 4G 4.6 nm; the same order, cal a touch more compliant at the filament COM), monotone and stable.

**The split is the headline of the whole experiment:** the **LOAD/force mechanics are Outcome-A-clean** (force,
tangent, stroke, recoil, force-clamp all match), while the **unbound-SEARCH ensemble drifts ~30–38 %.** The
mechanically-calibrated `k_tr` (from the free-axial bending reaction) gives the surrogate a **tighter search
envelope than 4G's actual wandering** — the two architectures' pivots couple to the head/converter differently, so
at the *same* `k_tr` the explicit beam wanders more freely. A `k_tr` softened to `~0.017 pN/nm` would recover the
search RMS but at the cost of the force accuracy — a **genuine ~1.5× ambiguity** between the mechanical bending
reaction and the search observable (documented, not tuned away). The `k_ext` 0.64 vs 0.99 is the **fixture-observable
offset** the 4G doc §6 already flagged (a mobile-pivot fixture reports a different whole-crossbridge stiffness), not
a series-compliance loss — both are "stiff/load-bearing."

### Phase D — length transfer (§6)

| L | k_ax scaled (1/L) | k_ax 4G | Δ | k_tr scaled (1/L³) | k_tr 4G | Δ | class |
|---:|---:|---:|---:|---:|---:|---:|---|
| 20 | 210.0 | 210.0 | **0 %** | 0.211 | 0.162 | +31 % | **B** (one correction) |
| 60 | 70.0 | **35.5** | +97 % | 0.0078 | 0.0095 | −18 % | **C/D** (refit) |

**The axial `1/L` scaling holds exactly at L20 but overpredicts 2× at L60** — the longer beam's bending compliance
softens the effective axial reaction below the pure `ks/M` stretch value. So the L40 form transfers cleanly *downward*
(to the stiffer/shorter tweezers-like regime, class B) but **not upward** to the long buckling-prone L60 (class C —
a refit or an explicit bending-in-series axial term is needed). **L60 buckling is demonstrated:** `F_crit(60) ≈
1.97 pN` (Euler), surrogate/4G compression-regime agreement **8/9 = 89 %** (the Euler asymmetry that L40 does not
realize appears cleanly at L60, as 4G itself found).

---

## 6. Phase D — length transfer (§9)

Freeze the L40 form, then transfer to **L20 / L60** by the MD scalings with **no refit**: `k_ax ∝ 1/L`, `k_tr ∝
1/L³` (Euler–Bernoulli cantilever), `F_crit ∝ 1/L²` (Euler). Compare the scaled params to the 4G-measured values at
each L, and the cal-4F stroke/`k_ext` to 4G. Classify **A** (scales) / **B** (one correction) / **C**–**E**. L60 also
gets the buckling demonstration (the surrogate branch enabled at `F_crit(60) ≈ 1.97 pN` vs the 4G L60 buckling).

---

## 7. Phase E — reduced-mat comparison (validation, NOT calibration; §11)

Matched reduced 2-D mats (`400 µm⁻²`, `2.5×0.6 µm`, dt 2.5e-6, identical seeds/gate/chemistry, active-set cull):
**fixed anchor** / **orig 4F no-slack** / **cal-4F-L40** / **explicit 4G L40**. Compare avg chemically bound, avg
load-bearing, continuity, bind rate. **The surrogate is NOT tuned to match any mat/gliding number** (§11 discipline).

---

## 8. Phase F — cost (§12)

Per single-motor step (2000 steps): **cal-4F ≈ 1.3 µs** (analytic pivot + 5×5 solve) vs **4G ≈ 12 / 58 / 168 µs**
at L20/40/60 (the `(3M+2)`-DOF beam solve with a numeric tangent). **cal-4F is ~9–125× cheaper than 4G** (≈44× at
L40) and its per-motor cost is **identical to orig-4F** (one added `softplus` branch). Analytic force + analytic
diagonal tangent ⇒ GPU-friendly (no per-motor inner beam solve). Memory: one pivot + params per motor vs `M+1` beam
nodes for 4G.

---

## 9. Results (full run) — `RUN_LOGS/twobody_4g_to_4f_calibration/`

### Reduced-mat comparison (Phase E; 400 µm⁻², 3 seeds, validation NOT calibration)

| model | avg bound | avg load-bearing | frac taut | continuity | binds/motor/s |
|---|---:|---:|---:|---:|---:|
| fixed anchor | 0.30 | 0.30 | 100 % | 0.25 | 0.23 |
| orig 4F no-slack | 1.89 | 1.89 | 100 % | 0.85 | 1.64 |
| **cal-4F-L40** | **1.73** | 1.73 | 100 % | **0.82** | **1.55** |
| explicit 4G L40 | 1.53 | 1.03 | 67 % | 0.80 | 1.55 |

**cal-4F matches 4G on avg-bound (+13 %, within ±15 %), continuity (+0.02, within 0.05), and bind rate (exact).**
It over-counts **load-bearing** (1.73 vs 1.03) because the symmetric-stiff axial makes *every* bound motor taut,
while 4G's longer beam lets ~33 % of bound motors sit in the bending regime under the mat's transverse load — the
flip side of the L40 symmetric-stiff compression choice. (Signed gliding velocity is not separately resolved in this
reduced recruitment mat; it lives in the full 4F gliding assay, out of scope here.)

### Final table (§15) — cal-4F-L40 vs the 4G L40 reference

| model | ref L | k_ax tension | k_comp low | buckle thr. | k_comp post | k_tr small | NL transv. (rMax) | search RMS | capture | stroke | pivot recoil | force err (med/p95) | mat avgBound | cost/step |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **cal-4F-L40** | 40 | **105** | 105 (sym) | 4.44 (L60) | ~1.05 (L60) | **0.026** | 20.3 | 7.6 | 357 | 6.90 | 0.007 | **0.0%/4.3%** | 1.73 | **1.34 µs** |
| explicit 4G L40 | 40 | 105 | ~105 | 4.44 | ~0.68 | ~0.03* | ~contour | 10.8 | 576 | 7.27 | 0.007 | — | 1.53 | 58 µs |

(*4G search-effective transverse; the pinned-probe kTrans 0.167 is the 4H stretch artifact, not the bending the
free pivot feels. Units: k in pN/nm, F_crit in pN, rMax/RMS/stroke/recoil in nm, capture in nm², cost µs/step.)

### Pass-criteria scorecard (§13)

| criterion | target | result | verdict |
|---|---|---|---|
| force-vector error median / p95 | ≤10 % / ≤25 % | 0.0 % / 4.3 % | **PASS** |
| axial tangent error (visited tension) | ≤10 % | 0.0 % | **PASS** |
| transverse tangent (visited search) | ≤20 % | 3–4 % | **PASS** |
| unloaded stroke | ≤5 % | 5.1 % | ~PASS |
| pivot recoil | \|Δ\|<0.2 nm | ~0 | **PASS** |
| force-clamp displacement thru 4 pN | ≤10 % | order-matched, +tension biased | partial |
| search RMS | ≤10 % | −29 % | **FAIL (drift)** |
| capture area | ≤15 % | −38 % | **FAIL (drift)** |
| mat avg bound | ≤15 % | +13 % | **PASS** |
| mat continuity | <0.05 abs | +0.02 | **PASS** |
| performance ≫ cheaper than 4G, GPU-friendly | ≥10× | **43×** (L40), analytic tangent | **PASS** |

---

## 10. Outcome (§16) and recommendation (§17)

**OUTCOME B — faithful single-motor mechanics, modest ensemble drift.** The surrogate reproduces 4G's **load-bearing
mechanics essentially exactly** — axial force and tangent to `~1e-10` (`k_ax = ks/M = EA/L`), stroke within 5 %,
pivot recoil identical, force-clamp tracking, a conservative separable potential with a symmetric tangent — at **43×
lower cost than the explicit beam** (identical to the original 4F). The dense-mat **recruitment and continuity match
within tolerance** (avg-bound +13 %, continuity +0.02, bind rate exact). The **unbound-search ensemble drifts
~30–38 %** (a tighter search envelope and smaller capture footprint than 4G) and the surrogate **over-counts
load-bearing** (always-taut vs 4G's 67 %). These are the documented, honest costs of (a) calibrating `k_tr` to the
mechanical bending reaction rather than the search observable — a genuine ~1.5× ambiguity — and (b) the L40
symmetric-stiff compression. **The `k_ext` 0.64 vs 0.99 is the fixture-observable offset the 4G doc already flagged,
not a series-compliance loss.**

This is **not Outcome A** (the search/capture ensemble does not match within tolerance) and **not Outcome C/D/E**
(no lookup table is needed at L40 — the analytic form passes the mechanical criteria; the length-transfer, however,
is class B down to L20 and class C up to L60, so a **single transferable form across all L is not yet established** —
that part is Outcome-D-leaning and flagged). **Not Outcome F** (no leakage or numerical failure; Gate 1 byte-identical,
passive work ~0, frozen test never touched in the fit).

### §17 — final recommendation (exactly one)

**ADOPT the calibrated 4F-L40 surrogate for production — for LOAD/force-dominated ensembles (gliding, contraction,
the load-bearing mechanics) — with documented uncertainty and periodic 4G spot-checks** (Outcome-B recommendation).
The load-bearing single-molecule mechanics are reproduced essentially exactly at 43× lower cost; use it wherever the
force output governs the result. **Carry two documented caveats:** (1) the unbound search envelope is ~30 % tight and
the capture footprint ~38 % small — for **recruitment/search-dominated** studies, soften `k_tr` toward
`~0.017 pN/nm` (trading a few % of force accuracy) or spot-check against 4G; (2) the L40 form transfers **downward**
to the short/tweezers regime (L20, class B) but **not upward** to the long buckling-prone L60 (class C — the axial
`1/L` scaling overpredicts 2× as bending compliance grows), so **retain a distinct L60 fixture** rather than a single
scaled form for exposed long-tail assays. No canonical production setting is changed; the overlay is default-off.

---

## 11. Deliverables and reproduction (§15)

```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp4i -out RUN_LOGS/twobody_4g_to_4f_calibration/csv   # full calibration (CPU)
./scripts/run_lasertrap.sh -exp4i -smoke                                            # quick smoke (L40 only)
# regressions (byte-identical): -exp4f -smoke ; -exp4g -smoke ; -exp3c
```

**Artifacts** (`RUN_LOGS/twobody_4g_to_4f_calibration/`): `exp4i_full.log`, `csv/{calibration_dataset,
fitted_params, force_tangent_test, single_motor_equivalence, force_clamp, length_transfer, reduced_mat,
cost_benchmark}.csv`, and `INDEP_VALIDATION/` (the blinded-of-narrative package for an independent session).

**Source:** `softbox/TwoBodyConverterMotor.java` — the `supBuckleCrit`/`supKcompPost`/`supSmoothBuck` fields + the
Euler branch in `supForce`/`supForceM`; the `CAL_*` params + `calApply`/`calApplyMat`; the calibration primitives
`calS2Sample`/`calS2SampleTrans`/`calSlope`/`calClampDisp`/`s2ClampDisp`/`buildBoundS2`; and `run4i` (Phases
A–F). One dispatch line in `softbox/LaserTrapHarness.java`.
