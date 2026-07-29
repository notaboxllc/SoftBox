# Low-[ATP] Small-Skew Pilot — ε = ±1° and ±2° at 5 µM

**Controlling report for this pilot.** Branch `feature/lowatp-small-skew-pilot`, worktree
`../softbox-lowatp-small-skew`. Date 2026-07-28/29.

- **Question.** Does experimentally modest converter skew preserve a resolved mirror-odd twirl while bringing
  the angular rate down from the ±15° result toward the experimental pitch scale?
- **Answer, in one line.** **No — at 1° and 2° the chiral signal falls into the cancellation noise before the
  pitch reaches the experimental scale.** Neither magnitude is resolved, and the failure is mechanistic, not
  merely statistical: the per-head torque magnitude and the cancellation residual at 1–2° are indistinguishable
  from a zero-skew control.
- **Controlling reference (not reopened):** `docs/twirling/LOW_ATP_GLIDING_TWIRLING_FINDINGS.md`. Its
  conclusions — the ATP torque plateau, closure, the 0.14–0.7 % cancellation residual, ATP-independent per-head
  torque, same-sign puller/dragger contribution — stand unchanged and are used here only as the reference point.
- **Raw records** `RUN_LOGS/chiral_sites/lowatp/` (this worktree, ε-tagged ids only); **analysis**
  `RUN_LOGS/lowatp_smallskew/analysis.txt`; **gates** `RUN_LOGS/lowatp_smallskew/stage01_skew_fixtures.txt`.
- **No parameter was tuned after seeing any result.** See §12.

---

## 1. Stage 0 — parameter-path audit

| # | item | finding |
|---|---|---|
| 1 | which parameter | the **linear converter-ramp skew** used by the ±15° campaign — `CONV_RAMP_ARM = RAMP_LINEAR`, skew carried in `chiP[16]` and read by `ChiralSiteSystem.convFrameStep` |
| 2 | stored value and units | **CLI is in DEGREES** (`-atp-eps-deg`), converted exactly once to radians at pack time |
| 3 | path | `-atp-eps-deg` → `ATP_EPS_DEG` → `TArm.convSkew` → `EPS_CONV_ARM` → `ExplicitCompleteMatHarness.CONV_SKEW_DEG` → `chiP[16] = deg·π/180` |
| 4 | ±ε geometry | +ε and −ε differ in **exactly one** packed entry (`chiP[16]`), exactly negated (gate C) |
| 5 | ε = 0 | exact achiral limit — `convSkewOn()` false and `chiP[16]` packs exactly 0.0 (gate B) |
| 6 | RNG | skew appears in no salt, no draw count and no random-consuming branch — streams identical by construction |
| 7 | kernel structure | no restructuring, no buffer resize; one `double` in an existing parameter block |
| 8 | CPU/GPU | one shared `chiP` buffer; both runners read the same entry |
| 9 | second skew mechanism | none active — `EPS_BIND_DEG` (actin-side binding skew) and `EPS_STROKE_DEG` (interface step) are separately packed and **both 0** |
| 10 | other buffers | chemistry, catch-slip, drag, Brownian-source and geometry buffers **bit-identical** across skew; `atpOn` = 50 /s at every skew (gates A) |

**As-built values used (gate D, 0 ulp error at every skew, both signs):**

```
  1° -> 1.745329251994329500e-02 rad
  2° -> 3.490658503988659000e-02 rad
 15° -> 2.617993877991494000e-01 rad
  0° -> 0.0                      rad (exact)
```

### 1.1 A blocking defect found by the audit, before any GPU time

`atpId` tagged the physical duration but **not** the ε magnitude — despite its own javadoc claiming both. A
pilot arm at ε = 1°, 5 µM, seed 101, +ε would therefore have produced the id
`atp_u0005.00_d00200000_p_101`, **byte-identical to the stored 15° record**. The resume-safe check would have
found it, skipped the run, and returned **15° data labelled as 1°** — a silent, undetectable corruption of the
entire pilot.

Fixed by tagging the skew (`_e0010_`, deg×10). The untagged records predate the tag, so `atpRead` falls back to
the legacy form **only** when the current skew is the 15° those records were actually run at; no other skew has
a legacy form and none can alias onto them. Gate: 4 skews × 2 signs × 2 seeds → 16 distinct ids.

**Stage 0/1 gates: 9 PASS, 0 FAIL** (`-skew-fixtures`).

---

## 2. Frozen configuration

Copied exactly from the completed 5 µM condition; **only the converter skew changes**.

| item | value |
|---|---|
| [ATP] / effective atpOn | 5 µM / 50 s⁻¹ |
| viscosity / timestep | 0.01 Pa·s / 2.5×10⁻⁷ s |
| duration / equilibration | 200 ms / 0.25 (150 ms measurement) |
| filament | 12 segments, Brownian ON (all channels) |
| motor density / S2 | 400 heads/µm² / homogeneous 40 nm |
| actin sites / surface bond / lattice | discrete / ON / native |
| target-zone, old interface skew, roll spring, rigor rupture | all OFF |
| nucleotide cycle, intrinsic rates, force laws, drag | unchanged |
| runner | GPU device-resident, monitored, no fallback |

---

## 3. Arm inventory

**10 arms, all complete, zero invalid states, zero solver failures, zero rate-cap warnings, no retries.**

| ε | signs | seeds | arms |
|---|---|---|---|
| +1°, −1° | both | 101, 102 | 4 |
| +2°, −2° | both | 101, 102 | 4 |
| 0 (null) | — | 101, 102 | 2 |

The stored ε = 15°, 5 µM, seeds 101–104 arms are used as the reference **by reading the main tree's record
directory read-only**. No stored record was copied, moved, rewritten or re-run.

---

## 4. Paired observables

| ε | seed | v_even | Ω_odd | Ω_even | turns/µm | pitch µm | R²(+) | R²(−) |
|---|---|---|---|---|---|---|---|---|
| 15° | 101 | −0.1848 | −46.39 | −24.81 | −39.94 | −0.0250 | 0.893 | 0.623 |
| 15° | 102 | −0.1401 | −68.40 | −10.47 | −77.72 | −0.0129 | 0.968 | 0.866 |
| 15° | 103 | −0.1061 | −43.44 | −10.77 | −65.19 | −0.0153 | 0.770 | 0.815 |
| 15° | 104 | −0.1313 | −48.53 | −14.20 | −58.83 | −0.0170 | 0.974 | 0.697 |
| **2°** | 101 | −0.1701 | **−0.32** | −21.69 | −0.30 | −3.30 | 0.621 | 0.670 |
| **2°** | 102 | −0.1653 | **+18.15** | −3.31 | +17.48 | +0.057 | 0.800 | 0.644 |
| **1°** | 101 | −0.1733 | −14.14 | −20.31 | −12.98 | −0.0770 | 0.892 | **0.114** |
| **1°** | 102 | −0.1624 | −12.90 | −0.65 | −12.64 | −0.0791 | **0.527** | **0.318** |

| ε | Ω_odd ± SEM | spread | sign | τ_odd ± SEM | sign | turns/µm | pitch µm |
|---|---|---|---|---|---|---|---|
| 15° | −51.69 ± 5.67 | 1.57× | same | −1.879e-22 ± 1.7e-23 | same | −60.42 | −0.0166 |
| 2° | **+8.91 ± 9.24** | 56× | **SPLIT** | **+8.75e-24 ± 1.9e-23** | **SPLIT** | +8.59 | +0.116 |
| 1° | −13.52 ± 0.62 | 1.10× | same | −2.315e-23 ± 8.8e-24 | same | −12.81 | −0.0780 |

**Gliding is untouched by skew**, as required: v_even = −0.168, −0.168, −0.141 µm/s at 1°, 2°, 15°
(R(2/1) = 0.999).

---

## 5. ε = 0 noise floor

| seed | Ω | τ | roll R² | glide |
|---|---|---|---|---|
| 101 | −11.51 | +4.58e-23 | 0.240 | −0.2288 |
| 102 | −13.17 | +2.93e-23 | 0.515 | −0.1776 |

**With no chirality imposed at all, a single arm rolls at ≈12 rad/s.**

The floor must be the *width* of the spurious-odd distribution, not one draw of it: the two null arms happened
to roll the same way, so their pseudo-odd `0.5·(Ω_a − Ω_b) = +0.83 rad/s` is an unrepresentatively small
sample. Since `Ω_odd = 0.5·(Ω(+) − Ω(−))` and the two arms of a pair decorrelate over 200 ms,

```
sigma(spurious Omega_odd) = 0.5 * sqrt(2) * RMS(Omega at eps=0) = 8.74 rad/s
```

| ε | \|Ω_odd\| | vs floor σ | verdict |
|---|---|---|---|
| 15° | 51.69 | **5.91 σ** | clears |
| 2° | 8.91 | 1.02 σ | at the floor |
| 1° | 13.52 | 1.55 σ | marginal |

---

## 6. Torque–rotation closure

γ_segment = 2.701612e-25, **γ_filament = NSEG·γ_segment = 3.241935e-24 N·m·s**.

| ε | meas/pred | per-arm values |
|---|---|---|
| 15° | **0.893 ± 0.052** (n=4) | 0.970, 0.980, 0.865, 0.760 |
| 2° | 1.126 ± 1.020 (n=2) | 0.106, 2.146 |
| 1° | **2.172 ± 0.736** (n=2) | 1.436, 2.908 |

**At 1° the measured rotation is ~2.2× more than the accumulated torque can account for.** The excess is
Brownian roll that the ±ε half-split did not cancel. At 15° closure holds (0.89), reproducing the controlling
study's result at this condition.

---

## 7. Per-head cancellation and puller/dragger decomposition

| ε | Σ τ⁺ | Σ τ⁻ | net | n(τ⁺) | n(τ⁻) | **net/Σ τ⁺** | **\|τ\| per head⁺** |
|---|---|---|---|---|---|---|---|
| 15° | +1.041e-19 | −1.043e-19 | −1.413e-22 | 19.02 | 19.09 | **0.00136** | 5.476e-21 |
| 2° | +9.359e-20 | −9.355e-20 | +3.91e-23 | 17.25 | 17.29 | 0.00042 | 5.426e-21 |
| 1° | +9.733e-20 | −9.729e-20 | +3.07e-23 | 17.74 | 17.76 | 0.00032 | 5.487e-21 |
| **0°** | +9.601e-20 | −9.597e-20 | +3.76e-23 | 17.24 | 17.23 | **0.00039** | **5.570e-21** |

| ε | n pull | n drag | τ/head pull | τ/head drag | same sign |
|---|---|---|---|---|---|
| 15° | 19.69 | 18.42 | −2.625e-24 | −4.868e-24 | **YES** |
| 2° | 17.85 | 16.69 | +2.636e-24 | −4.745e-25 | no |
| 1° | 18.35 | 17.15 | −7.741e-25 | +2.617e-24 | no |
| 0° | 17.80 | 16.66 | +8.463e-25 | +1.347e-24 | YES (by chance) |

**This is the decisive evidence.** Stage 4's central question — does reducing the skew reduce the per-head
torque magnitude, alter the cancellation imbalance, or both? — has the answer **neither, detectably**:

- the per-head torque magnitude is ≈5.4–5.6×10⁻²¹ N·m at **every** skew **including zero**, so it is not set
  by the imposed skew at this scale;
- the cancellation residual at 1° (0.00032) and 2° (0.00042) is **indistinguishable from the zero-skew control
  (0.00039)**; only 15° lifts it (0.00136, 3.5×) above the achiral value;
- the puller/dragger sign test even "passes" at ε = 0, confirming it carries no information at this signal size.

Also note τ_odd at 1° (−2.32e-23) is **smaller in magnitude than the achiral per-arm net torque at ε = 0**
(+3.76e-23) — the 1° odd torque sits below the achiral torque fluctuation.

---

## 8. Nested-window duration check

| ε | 50 ms | 100 ms | 150 ms | 200 ms | roll R² |
|---|---|---|---|---|---|
| 15° | −53.56 | −36.60 | −62.08 | −51.40 | 0.42 → 0.82 |
| 2° | +28.66 | +10.32 | +10.55 | +8.57 | 0.45 → 0.67 |
| 1° | −1.45 | **+7.06** | −10.50 | −13.61 | 0.11 → 0.46 |

**At 1° the Ω_odd estimate changes sign within the same trajectory** (−1.45 → +7.06 → −10.50 → −13.61). It is
not converged at 200 ms in any sense. 2° drifts 3.3× from 50 → 200 ms while keeping a sign that disagrees with
every other condition. Only 15° is stable in sign with a rising R².

---

## 9. Skew scaling (Stage 5)

| quantity | ε=1 | ε=2 | ε=15 | R(2/1) | R(15/1) |
|---|---|---|---|---|---|
| Ω_odd (rad/s) | −13.52 | +8.91 | −51.69 | **−0.659** | 3.823 |
| τ_odd (N·m) | −2.315e-23 | +8.752e-24 | −1.879e-22 | **−0.378** | 8.114 |
| turns per µm | −12.81 | +8.59 | −60.42 | −0.670 | 4.715 |
| v_even (µm/s) | −0.1679 | −0.1677 | −0.1406 | 0.999 | 0.837 |

Linear response would give R(2/1) = 2 and R(15/1) = 15. **Observed R(2/1) is negative** — the ratio changes
sign — which is not a scaling exponent but a signature of an unresolved quantity.

⇒ **Regime C: threshold / noise-floor.** Neither A (linear) nor B (sublinear) applies; both small-skew signals
are at or below the achiral background. No nonlinear curve is fitted, per the task's instruction.

---

## 10. Comparison with experiment (post-hoc only)

| quantity | experiment | this pilot |
|---|---|---|
| pitch | 0.47 ± 0.20 µm | 15°: −0.017 µm · 1°: −0.078 µm (unresolved) · 2°: +0.116 µm (unresolved) |
| implied Ω for 0.47 µm at \|v\| = 0.163 µm/s | ≈ **2.2 rad/s** | ε = 0 alone produces ≈ **12 rad/s** of Brownian roll per arm |

**The decisive obstacle is not the skew value.** The target angular rate (≈2.2 rad/s) is **5–6× below the
achiral Brownian roll of a single arm** at this condition. No choice of small skew can produce a *resolved*
2.2 rad/s signal in a 200 ms window at 5 µM, because the measurement floor is above the target. Reducing skew
moves Ω_odd toward the target and into the noise simultaneously.

**Preregistered scale estimates vs observed** (estimates, never acceptance criteria): linear extrapolation
predicted Ω_odd ≈ −3.4 (1°) and −6.9 (2°) with pitches −0.30 and −0.15 µm. Observed: −13.5 (unresolved) and
+8.9 (unresolved, wrong sign). The model does **not** follow the linear extrapolation.

---

## 11. Pilot classification (Stage 6)

| ε | class | basis |
|---|---|---|
| **1°** | **3 — UNRESOLVED AT THIS SKEW** | Ω_odd only 1.55σ above the floor; closure fails at 2.17×; Ω_odd changes sign *within* the trajectory across nested windows; roll R² 0.11–0.53; cancellation residual and per-head torque identical to ε = 0; τ_odd below the achiral net-torque scale. The same-signed Ω_odd on both seeds is not supported by any torque-level evidence and is consistent with coincidence at n = 2. |
| **2°** | **3 — UNRESOLVED AT THIS SKEW** | Ω_odd and τ_odd both **split in sign** between seeds; 1.02σ above the floor; per-arm closure 0.106 and 2.146; cancellation residual identical to ε = 0. |

Neither is a numerical failure (class 4): zero invalid states, zero solver failures, zero rate-cap warnings,
closure holds at 15° in the same run set, and all Stage 0/1 gates pass. **This is not evidence that the
mechanism is absent** — it is evidence that at 1–2° the signal is below what this assay can measure in 200 ms.

---

## 12. Recommended next action, with cost

**Do not brute-force 1–2°.** From the measured floor (σ ≈ 8.74 rad/s per seed-pair at a 150 ms measurement
window) and σ ∝ 1/√(n·T):

| goal | requirement | cost at 40.6 min/arm |
|---|---|---|
| resolve the τ-predicted 1° signal (≈7.2 rad/s) at 3σ | n·T must rise ≈13.5× | **≈36 h** (27 seeds at 200 ms, or 4 arms at 2.7 s each) |
| resolve a linearly-extrapolated 1° signal (≈3.4 rad/s) at 3σ | n·T must rise ≈60× | **≈150 h** |

Duration and seeds are **equally efficient** here (both enter as n·T), so prefer **seeds**: they also give a
usable variance estimate, which n = 2 does not, and guard against a single pathological trajectory.

**But the better recommendation is not to spend either.** The pilot has already answered the scientific
question: the experimental pitch scale corresponds to ≈2.2 rad/s, which is *below the achiral Brownian roll
floor of this assay*. Buying significance by brute force would resolve a signal that is still ~6× short of the
experimental pitch. The productive next steps are variance reduction or a regime change, not more arms:

1. **Reduce rotational Brownian noise at source** — a longer filament raises roll drag ∝ length and lowers
   D_rot ∝ 1/length, cutting the floor without touching motor physics. This is the single highest-leverage
   change and is a declared configuration study, not a tuning knob.
2. **Re-examine the cancellation** — §7 shows the net is a ~0.0004 residual of ±10⁻¹⁹ N·m populations at all
   small skews. Any mechanism that reduces the cancelling population (lower density, fewer engaged heads)
   raises the signal-to-floor ratio far more cheaply than more seeds.
3. Only then, if a resolved small-skew twirl is still wanted, spend the ≈36 h.

**Not recommended:** an intermediate skew map (3–10°) to "find where the signal disappears" — the disappearance
point is set by the noise floor, not by the motor, so such a map would characterise the measurement rather
than the mechanism.

---

## 13. Scope and honesty statements

- **No parameter was tuned after seeing any result.** ATP, density, viscosity, duration, timestep, S2 length,
  motor mechanics, Brownian amplitudes, detachment, geometry and analysis windows are all exactly the frozen
  5 µM values; only `-atp-eps-deg` changed, and its four values (0, 1, 2 and the stored 15) were fixed before
  the first run.
- **No skew was fitted to experiment**, and no interpolation to a "best-fit" skew is offered.
- **The completed low-ATP study was not reopened.** Its plateau, closure, cancellation and puller/dragger
  conclusions are used as the reference point and are unchanged.
- **Two analysis defects were found and corrected during this pilot**, both mine, both before they affected a
  conclusion: (a) the loader keyed reference records without [ATP], so the whole 4-concentration ladder
  collapsed onto one key and the 15° comparison was initially made against the 2000 µM arms; (b) the noise
  floor was first taken as a single pseudo-odd draw (0.83 rad/s) rather than the width of the spurious-odd
  distribution (8.74 rad/s), which would have overstated significance by ~10×.
- **Stopping boundary respected:** eight primary arms, two ε = 0 arms, nested-window analysis, mechanism
  decomposition and a recommendation. No n = 4 extension, no other ATP, no density sweep, no rigor-rupture or
  viscosity change, no broad skew map.
