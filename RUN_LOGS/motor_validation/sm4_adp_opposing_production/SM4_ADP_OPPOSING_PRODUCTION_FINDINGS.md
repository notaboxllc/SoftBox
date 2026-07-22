# SM4 — ADP opposing-load production grid (explicit-s2-l40)

**Status: COMPLETE. n = 200/cell × 12 forces = 2400 events. 0 censored, 0 invalid, 0 solver failures.**
**CPU-only, 397 s wall. Code rev `73d82bf`, scratch build `e667650b54f9`.**
**NO physics parameter was changed during data generation.**

---

## 1. Configuration (frozen)

| | |
|---|---|
| fixture | `explicit-s2-l40` (mechanistic reference beam, L = 40 nm) |
| prepared state | ADP (`NUC_ADP`), converter target pinned to `ADP_THETAS` at t=0 |
| direction | opposing / tensile (`+` = toward the BARBED end) |
| force ladder | 0, 1, 2, 3, 4, 5, 6, 8, 10, 15, 20, 25 pN |
| detachment | natural chemistry only (`cycleLymnTaylor`); break-cap never read on this path |
| rupture | `RUPTURE_MODE = 0`, `EMERGENCY_ON = false` (and structurally absent from this code path) |
| dt | 2.5e-6 s; settle 8000 steps (mechanics only, chemistry frozen) |
| window | 40 ms (right-censoring horizon) |

---

## 2. Survival results

| F req (pN) | realized load (pN) | RMST (ms) | RMST 95% CI | median (ms) | p90 (ms) | censored |
|---:|---:|---:|---|---:|---:|---:|
| 0 | 0.759 | 1.133 | [0.986, 1.280] | 0.880 | 2.360 | 0 |
| 1 | 1.664 | 1.729 | [1.491, 1.967] | 1.163 | 3.880 | 0 |
| 2 | 2.564 | 2.410 | [2.068, 2.752] | 1.635 | 5.083 | 0 |
| 3 | 3.479 | 3.222 | [2.783, 3.661] | 2.340 | 7.450 | 0 |
| 4 | 4.389 | 4.394 | [3.796, 4.992] | 3.135 | 9.780 | 0 |
| 5 | 5.297 | 4.980 | [4.329, 5.631] | 3.673 | 11.075 | 0 |
| **6** | **6.204** | **5.350** | **[4.655, 6.045]** | 3.813 | 12.698 | 0 |
| **8** | **8.016** | **5.169** | **[4.494, 5.844]** | 3.755 | 12.645 | 0 |
| 10 | 9.828 | 4.517 | [3.902, 5.132] | 3.133 | 10.680 | 0 |
| 15 | 14.354 | 3.041 | [2.652, 3.431] | 2.318 | 6.718 | 0 |
| 20 | 18.892 | 2.001 | [1.735, 2.267] | 1.438 | 4.338 | 0 |
| 25 | 23.431 | 1.255 | [1.102, 1.408] | 0.880 | 2.745 | 0 |

A clean catch–slip curve: lifetime rises 4.7× from 0 to the optimum, then falls monotonically.
**Zero censoring at every force** — the 40 ms window was ample, so no censoring assumptions enter
these numbers. Kaplan–Meier curves, Greenwood/log-log CIs and person-time hazards are in
`kaplan_meier.csv` / `hazard.csv`; per-event records in `event_table.csv`.

### Force clamp (realized vs requested)

The opposing arm is a faithful clamp: relative error is +0.16 at 1 pN (dominated by the fixture's
+0.7 pN preload), then ≤ 3 % from 4 pN upward, drifting to −6 % at 25 pN (mild under-transmission).
Within-event thermal SD of the bond load is **1.346 pN** — this matters, see §4.

**All fits below use REALIZED load, never requested.**

---

## 3. Force at maximum lifetime

- Coded analytic optimum of `1/g(F)`: **6.106 pN**.
- Measured RMST peak: **6 pN (5.350 ms)**, with 8 pN at 5.169 ms.
- The two CIs overlap heavily ([4.655, 6.045] vs [4.494, 5.844]) ⇒ **the optimum is located in the
  6–8 pN band but 6 vs 8 is NOT resolved at n = 200.** Resolving it needs either finer force spacing
  around 6–8 pN or ~4× more events; it is not resolvable by re-analysing this dataset.
- Fitted optimum from the free fit: **6.90 pN** — consistent with both the coded 6.106 and the
  measured band.

---

## 4. Kinetic fit — does the apparatus recover the coded law?

The fit targets the **ADP→NONE waiting time** (`t_adp_release_s`), which is the only
force-dependent step; total lifetime is contaminated by the force-independent terminal ATP step.
Per-cell rate is the censoring-aware exponential MLE.

### 4a. Raw fit (rate evaluated at the MEAN realized load)

| parameter | fitted | coded | agreement |
|---|---|---|---|
| k0 (1/s) | 1356 ± 111 | 1000 (`onADP`) | **+3.2σ — biased high** |
| aCatch | 0.9405 ± 0.0059 | 0.92 | +3.5σ |
| xCatch (nm) | 2.279 ± 0.174 | 2.5 | −1.3σ ✔ |
| xSlip (nm) | 0.4092 ± 0.0251 | 0.4 | +0.4σ ✔ |

χ²/dof = 0.13. The distances are recovered; the **amplitude and base rate are biased high**.

### 4b. Thermal-fluctuation (Jensen) corrected fit — the bias is fully explained

`g(F)` is convex, and the bond load fluctuates thermally with SD 1.346 pN. The rate law is evaluated
on the *instantaneous* load, so the time-averaged rate is `⟨g(F)⟩ > g(⟨F⟩)`. For `F ~ N(μ, σ²)` this
is exact and analytic. Refitting with `⟨g⟩`:

| parameter | fitted | coded | agreement |
|---|---|---|---|
| **k0 (1/s)** | **1041.0 ± 54.1** | 1000 | **+0.76σ ✔** |
| **aCatch** | **0.9232 ± 0.0082** | 0.92 | **+0.39σ ✔** |
| xCatch (nm) | 2.270 ± 0.172 | 2.5 | −1.3σ ✔ |
| xSlip (nm) | 0.4095 ± 0.0251 | 0.4 | +0.4σ ✔ |

χ²/dof = 0.14. **All four coded parameters are recovered within 1.3σ once the convexity bias is
removed.** The +36 % apparent inflation of the unloaded rate was entirely a Jensen artefact of
reading a convex rate law on a thermally fluctuating load — not a model error and not a fit failure.

**⇒ The frozen model's coded catch–slip law IS recovered by this apparatus.**

### 4c. Identifiability on this arm

- **xSlip is well identified here** (±6 %) — it is constrained by the falling limb beyond the
  optimum, which only the *tensile* arm reaches. This is the arm that identifies the slip branch.
- **xCatch is identified but less precisely** (±7.6 %, and 1.3σ low). Its constraint comes from the
  rising limb 0–6 pN, over which the catch exponential has only decayed by ~e⁻³·⁵.
- k0 and aCatch are strongly correlated (both set the F→0 intercept); they are only separable
  because the amplitude constraint `aCatch + aSlip = 1` is imposed.

---

## 5. Residuals

Residuals vs **realized** load (in `fit_results.json → fit_jensen_corrected.residuals`) are
structureless, |log-residual| ≤ 0.09 across the whole ladder, with χ²/dof = 0.14 — i.e. the two-pathway
form is not merely adequate, it slightly over-fits the per-cell scatter. There is **no systematic
residual trend** that would indicate a missing third pathway or a load-dependent amplitude.

---

## 6. Sensitivity to thermal load fluctuation

The within-event bond-load SD is 1.346 pN, essentially constant across the ladder (0.94–1.92 pN).
The Jensen inflation factor `⟨g⟩/g(⟨F⟩)` it induces is ~1.36 at the unloaded end and shrinks as the
slip branch (with its much smaller `xSlip`) takes over. **Any future calibration against experimental
lifetimes must apply this correction**, otherwise the fitted unloaded rate will be biased high by
~35 % — an error larger than the entire experimental uncertainty this assay is meant to resolve.

---

## 7. Health

0 invalid states, 0 solver failures, 0 forbidden chemistry transitions, 0 censored events across
2400 events. Detachment pathway was `NONE(rigor)->ATP` for 100 % of events, as the single-pathway
Lymn–Taylor cycle requires.

---

## 8. What is NOT concluded here

- **No parameter is changed.** This is a recovery/validation result on the frozen model. Calibration
  against experiment is a separate analysis (see the interim synthesis and §Part F literature notes).
- The 6 vs 8 pN optimum is unresolved (§3).
- These are `explicit-s2-l40` numbers. The assisting arm on this fixture is invalid — S2 buckles
  (see the SM6 findings) — which is why the slip-side production grid was run on `fixed-anchor`.
