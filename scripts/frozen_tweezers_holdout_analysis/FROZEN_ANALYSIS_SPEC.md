# FROZEN ANALYSIS SPECIFICATION — Experiment 3G-B (realistic-only tweezers holdout)

**Status:** FROZEN before any 3G-B holdout trace was generated or inspected.
**Provenance:** derived verbatim from the frozen 3G-A independent-analyst pipeline
(`/home/jba/Desktop/twobody_blind_review_3g/analysis/`, hashed in that session's
`FROZEN_ANALYST_SHA256.txt`). The ONLY changes made are the removal of code paths that
require noise-free ideal twins. **No estimator, threshold, filter, exclusion, compliance
equation, or calibration method was changed, retuned, or added.** This pipeline was NOT
optimized against the holdout and must not be edited after the holdout is seen.

The purpose of the 3G-B holdout is to test whether this frozen strategy still recovers the
compliance-free step, the pre- and post-stroke whole-crossbridge stiffness, and the stroke
polarity **without** access to ideal twins.

---

## 1. What the analysis assumes (unchanged from 3G-A)

The dual-trap actin dumbbell has a constant trap SEPARATION; preload and perturbations are
**common-mode translations of the trap pair**. The informative coordinate is the common-mode
bead position `X = (b1+b2)/2` referred to the common-mode trap command `c = (cL+cR)/2`. With a
bound motor of stiffness `km` and rest position `xm`,

```
gamma dX/dt = -2k (X - c(t)) - km (X - xm) + thermal noise
```

giving the estimators (all preserved verbatim):

| observable | expression |
|---|---|
| detached variance | `var(X-c) = kT/(2k)` |
| attached variance | `var(X-c) = kT/(2k+km)` |
| attached mean shift | `<X-c> = -c·km/(2k+km)` (+ step term post-stroke) |
| perturbation gain | `dX/dc = 2k/(2k+km)`, relaxing with `tau = gamma/(2k+km)` |
| apparent step | `X_post - X_pre = d·k_post/(2k+k_post) + 2k(c-xm)[1/(2k+k_post) - 1/(2k+k_pre)]` |
| compliance-corrected step | `d = [apparent - 2k(c-xm)(1/(2k+k_post) - 1/(2k+k_pre))]·(2k+k_post)/k_post` |
| load (motor force) | `F_pre = 2k(c - X_pre)` |

## 2. Preserved thresholds, filters, exclusions (verbatim from 3G-A)

- **Calibration** (`01`): equipartition on the common-mode coordinate `k = kT/(2·var(X-c))`
  with perturbation excursions masked (drop pulse + 3 ms tail), cross-checked by a Lorentzian
  PSD fit (20 Hz–10 kHz); common-mode drag `gamma = kT/D`; actin-link stiffness from `var(u)`.
- **Attachment** (`03`): settled-sample variance of `y=X-c` in a centred 6 ms window; attached
  where the statistic stays below `f_att = 0.30 ×` the no-motor-control median for `min_dwell =
  8 ms`; close short gaps ≤ 1.5 ms; merge qualifying runs ≤ 6 ms apart. Threshold = the largest
  `f_att` giving **zero false positives on all no-motor control traces** (fixed on controls).
- **Stroke** (`03`): two-window step statistic on settled samples, `NSIDE = 50` (floor 24)
  samples per side; significance from a per-trace Ornstein–Uhlenbeck bootstrap (`nsim = 400`,
  the trace's own attached variance/correlation time/sample mask); called at `p < 0.01`.
- **Stiffness** (`04`/`04b`): E1 attached variance inverted through the forward model
  `var_meas(K) = kT/K · g_OU(wK/gamma) · F_lowpass(K) [+ sigma_det^2]` (3 kHz 1st-order low-pass,
  measured white detector-noise floor, 1.5 ms chunk windowing); E2 deterministic linear-response
  ODE fit over each dwell; E3 mean-shift vs preload. Primary `k_pre` = mean-shift pooled over
  levels; primary `k_post` = attached-variance at **level A only**.
- **Compliance floor** (`04`/`04b`): the same estimators on the no-motor perturbation controls
  give the instrument added stiffness (≈0); its magnitude is adopted as the systematic floor
  `SYS` and folded into every km CI.
- **Zero-load step** (`06`/`07`): compliance-corrected `d` regressed on the measured force
  `F_pre`, extrapolated to `F=0`, **level A only** (95%-class stroke detection; B/C selection-
  biased). Bootstrap over traces (4000).
- **Exclusions** (verbatim): (1) no-attachment traces dropped; (2) too-short dwells reported as
  `stroke = null` (undetermined), NOT no-stroke; (3) levels B/C excluded from the step and
  post-stroke fits; (4) samples during every perturbation step + a short settle excluded from all
  statistics; (5) **no post-hoc exclusion on step value.**

## 3. The ONLY changes: ideal-twin removal

Every change is the deletion of a noise-free-ideal-trace code path; nothing else moved.

1. `lib.py` — `trace_path`/`index`/`load_trace` are realistic-only (the `ideal_observable_only`
   subtree is never referenced); a single `DATASETS = ["realistic"]`. Supplied calibration
   stiffnesses + calibration filenames are read from the package's own
   `calibration/calibration_summary.json` (data-driven; **no** hardcoded 3G-A numbers).
2. `02`/`03`/`04`/`04b`/`06`/`07`/`05` — every `for ds in ["realistic","ideal"]` loop is now
   `["realistic"]`; `index("ideal")` → `index("realistic")`; an unused ideal-control load in the
   detector-noise estimator is removed.
3. `07` — the **paired ideal-vs-realistic instrument-bias block is removed**. Instrument bias on
   the stiffness is still handled by the modelled corrections (unchanged); instrument bias on the
   step is reported as *not twin-assessable*.
4. `08` — the zero-load-step CI no longer includes the ideal-bootstrap term (it spans the level-A
   realistic bootstrap + the `k_post ∈ [0.35,1.2]` sensitivity only). All narrative numbers are
   recomputed from the holdout's own data (polarity counts, detection rates, exclusion counts,
   control false positives); no 3G-A number or ideal reference is emitted as a result.

## 4. Frozen decision rule that lost its ideal cross-check: pre/post stiffness difference

In 3G-A the "pre/post stiffness difference NOT identifiable" verdict was supported partly by the
ideal-vs-realistic sign disagreement, which is unavailable here. The frozen realistic-only rule
uses the analyst's **other, ideal-free** stated criterion (`04b`): the variance inversion is
ill-conditioned (~12% change in attached variance ⇒ ~35% change in km, ≈0.19 pN/nm on km≈0.55),
so a pre/post difference is declared identifiable **only if** (i) the paired bootstrap CI
excludes zero **AND** (ii) `|Δ|` exceeds the frozen systematic resolution `KM_DIFF_SYS = 0.15`
pN/nm. `KM_DIFF_SYS` is a property of the estimator (transcribed from the 3G-A report), not a
truth value; it is part of the method, like `p_stroke`.

## 5. Outputs

`analysis/out/*` (intermediate JSON + figures) and `analyst_results_holdout.json` at the package
root (the same schema as the 3G-A `analyst_results.json`). Run with `./run_frozen.sh` from this
directory.

## 6. Honesty constraints for the analyst session

The independent session must: verify the package hashes; run this pipeline **unchanged**; report
failures and non-identifiability honestly; and make **no** methodological revision after seeing
the holdout. If the frozen pipeline fails or is non-identifiable, that is reported directly — it
is not repaired.
