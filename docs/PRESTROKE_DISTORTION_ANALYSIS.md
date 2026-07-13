# PRESTROKE_DISTORTION_ANALYSIS — does a pre-stroke competence metric earn a geometry-gated commitment step?

**Date:** 2026-07-11 · **Branch:** dt-convergence-study · **Runner:** CPU deterministic (thermal off; the
rigid velocity-clamp arbiter). `BoA-v1ref` untouched. **Measurement/analysis only — NO gate built.**
Flag-gated `-prestroke`, default-off **byte-identical** (verified: FVROW char-for-char unchanged with/without).

> **BOTTOM LINE — BAIL (honest negative).** No pre-stroke distortion metric has the mechanistic content a
> geometry-gated commitment step would need. The **sign-only virtual axial work** (the PRIMARY, no-fitting
> metric — and every joint/energy/ΔU variant) is a genuine *within-velocity* competence signal (F9-channel
> virtual work `wf9` predicts episode impulse at fixed v, r ≈ 0.30–0.36, n ≈ 2200/cell) **and** spares a
> large v=0 population — but it is **FLAT with sliding velocity** (Q1 FAILS): `wf9` incompetent-fraction 0.020
> → 0.029 over v = 0 → 20 µm/s. A metric that doesn't worsen with v is a **static filter, not a saturation
> mechanism** — gating on it removes the same ~2 % of motors at every velocity and cannot lower V₀. The
> *only* candidate that grows with v is the **F8 axial strain** (−0.93 → −1.43 nm) — but that is cross-bridge
> **load, not assembly distortion**; its velocity growth and its impulse correlation *are* the force balance
> already captured by Variant-B, and a gate on it merely **duplicates the existing force-dependent catch-slip
> release + 12 pN break-force cap** (an efficiency-reducer, not new mechanism). **The Variant-B reconciliation
> is decisive:** the per-episode impulse ceiling is a **UNIFORM decline dominated by the collapse of the
> productive tail** (p90: 1.73 → 0.51 pN·ms, Δ−1.22) with the resisting tail barely moving (p10: −0.34 →
> −0.59, Δ−0.25) — there is **no emergent incompetent sub-population to excise**, and a pre-stroke gate cannot
> rescue the *productive* episodes that are losing steam. ⇒ **Geometric commitment gating is not the missing
> mechanism.** This re-refutes the pre-stroke-strain-gate idea, consistent with `FORCE_BALANCE_CLOSURE.md`
> PART 2 (impulse crosses zero at V₀, intrinsic to one attachment).

---

## STEP 0 — reanalysis or re-run? → **RE-RUN required** (the pre-stroke variables were NOT logged)

The 104 head-swing clamp runs logged the episode **outcome** (per-episode net axial impulse, but only
**aggregated to a mean** `Iattach` — no per-episode records) and **commanded geometry** (`-swingdiag`: q_HF,
off-axis angle, transverse impulse). **None** of the pre-stroke *mechanical* variables this analysis needs
were captured, and nothing was logged **paired per-episode**, so Q2 (predict-at-fixed-v) was impossible from
existing data:

| variable needed | in existing logs? |
|---|---|
| per-episode net axial impulse (outcome), **individually** | ✘ (only the mean `Iattach` / `fpos_imp`) |
| sign-only virtual axial work at the transition | ✘ |
| F8 axial/transverse strain at the transition | ✘ |
| joint (J1 converter) angular strain / ΔU_stroke | ✘ |
| pairing (pre-stroke metric ↔ this episode's impulse) | ✘ |

**Re-instrumented, cheap re-run of the DIRSWING arm only** (the gate would sit on DIRSWING motors). New flag
`-prestroke` (additive, default-off byte-identical): at each episode's **first** ADP·Pi→ADP power-stroke
transition, capture the pre-stroke metrics from the current body pose and, at detach, emit a per-episode
`PSROW` (metric values ↔ that episode's net axial impulse `imp`). Grid = the head-swing grid subset **v = 0,
2, 4, 8, 12, 16, 20 µm/s × seeds 0–3, 12 000 steps** (warm 4 000; window 8 000), d2000, `-matbox 50`.
**15 586 complete episodes** (≈ 2 200 per velocity). FVROW reproduced the known force–velocity curve
(`fbar_avail` crosses zero at v ≈ 16 = V₀), confirming the arm is the validated DIRSWING baseline.

**Candidate metrics captured (per the proposal).** PRIMARY = sign-only virtual axial work
`w = (τ̂_channel × û_head)·(−x̂) · angle_weight` — the axial glide (−x̂) component of the incipient head-tip
motion the *switched* stroke torque drives; **> 0 ⇒ tip barbed-ward ⇒ productive** (no threshold, no fitting).
Two channels switch at the transition — `directedSwing` (lever, θ 0°→60°) and **F9** (head, 90°→120°); per the
codebase the **F9 head-reorientation is the productive channel** (stroke ∝ HEAD_LEN — memory
`stroke-effective-lever-is-headlen`), so `whead = wsw + wf9`, with `wf9` the productive-channel virtual work.
Secondary (screened, not fitted): **ΔU_stroke** = ang²(θ_post) − ang²(θ_pre) at the fixed pose; **F8 axial
strain** `(site−tip)·x̂` and `|F8|` (nm); **J1 converter misalignment** ∠(û_lever, û_head).

> **Metric-construction note (a trap avoided).** A first cut computed the virtual work of the `directedSwing`
> **lever** channel only; it was uniformly-signed at fixed v (like the ~87° head-frame off-axis artifact of
> `HEADFRAME_SWING_VELOCITY_TEST.md` caution #1) — because the lever channel is *not* the productive one here.
> Adding the **F9 head channel** (the channel that actually moves the F8 tip) is what gives a non-degenerate,
> episode-varying competence metric. The 87° off-axis angle was **not** used as a gating variable.

---

## Per-velocity distributions — candidate metrics vs episode impulse

`imp` = net axial impulse per complete episode (pN·ms); `frac_neg` = fraction of episodes with imp < 0.
Metric means (n ≈ 2200/row):

| v | nEp | imp_mean | imp_sd | frac_neg | whead | wsw | **wf9** | dU | **f8ax** | f8mag | j1° |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0 | 2149 | +0.514 | 1.29 | 0.389 | 0.086 | −0.559 | 0.644 | 0.285 | −0.927 | 4.20 | 40.2 |
| 2 | 2193 | +0.423 | 1.13 | 0.422 | 0.086 | −0.553 | 0.639 | 0.284 | −0.945 | 4.28 | 40.4 |
| 4 | 2208 | +0.362 | 1.04 | 0.446 | 0.092 | −0.545 | 0.636 | 0.257 | −0.997 | 4.26 | 40.6 |
| 8 | 2216 | +0.193 | 0.76 | 0.499 | 0.081 | −0.547 | 0.628 | 0.254 | −1.195 | 4.27 | 40.3 |
| 12 | 2251 | +0.096 | 0.66 | 0.542 | 0.067 | −0.555 | 0.622 | 0.273 | −1.277 | 4.30 | 39.6 |
| 16 | 2291 | −0.003 | 0.58 | 0.608 | 0.054 | −0.564 | 0.619 | 0.293 | −1.425 | 4.28 | 39.1 |
| 20 | 2278 | −0.067 | 0.52 | 0.644 | 0.050 | −0.558 | 0.607 | 0.277 | −1.364 | 4.30 | 39.2 |

Reading the columns: `wf9` (productive channel) is **flat**; `wsw` (lever channel) is flat and negative
(chronically anti-productive — the lever swing sweeps ⊥ glide, the static counterpart of the head-frame
finding); `whead` declines mildly; `dU`, `f8mag`, `j1` flat; **`f8ax` is the one metric that grows with v**
(the sliding filament drags the bound site off the tip → load builds).

---

## The three arbitrating questions (best metric = the PRIMARY virtual work `wf9`; `f8ax` also examined)

### Q1 — does the distortion score worsen with velocity? → **NO for virtual work; only F8 *load* grows.**

Incompetent-side fraction vs v:

| metric | v0 | v4 | v8 | v12 | v16 | v20 | verdict |
|---|---:|---:|---:|---:|---:|---:|---|
| `wf9` incompetent (< 0) | 0.020 | 0.023 | 0.017 | 0.023 | 0.023 | 0.029 | **FLAT** (Q1 fail) |
| `whead` incompetent (< 0) | 0.479 | 0.478 | 0.481 | 0.508 | 0.512 | 0.528 | +5 pp, marginal |
| `f8ax` mean (nm) | −0.93 | −1.00 | −1.20 | −1.28 | −1.43 | −1.36 | **grows** (Q1 pass) |

The virtual-work competence of the productive stroke does **not** degrade with sliding velocity — the geometry
the F9 head-reorientation sweeps is velocity-independent (mirror of the head-frame test's flat off-axis angle).
`whead` worsens only ~5 pp (a mild balance shift). The **only** metric that clearly worsens with v is the F8
axial strain — i.e. cross-bridge **load**, not assembly distortion.

### Q2 — does the metric predict poor episodes AT FIXED velocity? → **YES (weak–moderate); `wf9` strongest.**

Within-velocity Pearson r(metric, episode impulse) — computed *inside* each v, so it is genuine within-velocity
predictive power, **not** a velocity proxy (n ≈ 2200 ⇒ |r| ≳ 0.05 is significant):

| metric | v0 | v4 | v8 | v12 | v16 | v20 |
|---|---:|---:|---:|---:|---:|---:|
| **`wf9`** | +0.295 | +0.325 | +0.359 | +0.378 | +0.350 | +0.362 |
| `f8ax` | +0.188 | +0.270 | +0.273 | +0.282 | +0.297 | +0.287 |
| `dU` | +0.133 | +0.143 | +0.151 | +0.167 | +0.115 | +0.109 |
| `whead` | +0.068 | +0.082 | +0.113 | +0.099 | +0.132 | +0.135 |
| `wsw` | −0.091 | −0.099 | −0.091 | −0.119 | −0.081 | −0.083 |
| `j1`, `f8mag` | ≈ 0 | | | | | |

`wf9` r ≈ 0.35 (≈ 12 % of variance) is real and consistent. Sign-split of `whead` (does whead > 0 give better
episodes?): Δ⟨imp⟩ = +0.04…+0.15 pN·ms — positive but modest. **So a competence signal exists at fixed v.**
This is the one question the virtual-work metric passes.

### Q3 — is there a productive population at v=0? → **YES (metric is not an axis artifact).**

v=0: frac(`whead` > 0) = 0.521, frac(`wf9` productive) = 0.980, frac(imp > 0) = 0.611. A large majority of
motors are competent at v=0 — the metric is **not** describing an axis-convention problem (unlike the 87°
angle, which would have rejected nearly everyone). Q3 passes — but note `wf9` passing "too well" (98 %
productive) means it barely rejects anyone at any v, reinforcing the Q1 static-filter reading.

### Three-question scorecard

| metric | Q1 worsens-with-v | Q2 predicts-at-fixed-v | Q3 spares-v0 | gate justified? |
|---|:--:|:--:|:--:|:--:|
| **`wf9`** (PRIMARY virtual work, productive channel) | ✘ flat | ✔ r≈0.35 | ✔ | **NO** — static filter, not a saturation mechanism |
| `whead` (combined virtual work) | ~ marginal | ~ weak | ✔ | NO — weak on all axes |
| `dU` (ΔU_stroke) | ✘ flat | ~ weak | ✔ | NO |
| `f8ax` (F8 axial strain = **load**) | ✔ grows | ✔ r≈0.28 | ✔ | **NO** — see below (load, not distortion; redundant; wrong tail) |

**`f8ax` numerically meets all three — and is still disqualified**, for three reasons: (a) it is the
cross-bridge **load**, not a pre-stroke assembly *distortion* — its velocity growth is the site sliding off
the tip, the ordinary force build-up; (b) a pre-stroke gate on load merely **duplicates the model's existing
force-dependent catch-slip release and 12 pN break-force cap** — no new mechanism; (c) decisively, it
addresses the **wrong part of the distribution** — see Variant-B.

---

## Variant-B reconciliation — is there a gate-able incompetent sub-population, or a uniform decline?

Per-episode impulse distribution shape vs v (same 15 586 episodes):

| v | imp_mean | frac_neg | p10 | p50 | p90 |
|---:|---:|---:|---:|---:|---:|
| 0 | +0.514 | 0.389 | −0.340 | +0.124 | **+1.730** |
| 4 | +0.362 | 0.446 | −0.396 | +0.058 | +1.544 |
| 8 | +0.193 | 0.499 | −0.436 | +0.002 | +1.109 |
| 12 | +0.096 | 0.542 | −0.493 | −0.039 | +0.821 |
| 16 | −0.003 | 0.608 | −0.548 | −0.096 | +0.624 |
| 20 | −0.067 | 0.644 | −0.586 | −0.117 | **+0.509** |

**The ceiling is a UNIFORM decline dominated by the collapse of the *productive* tail, not a growing
incompetent cohort.** From v=0→20: **p90 falls 1.73 → 0.51 (Δ−1.22)** — the productive episodes lose ~70 % of
their drive; p50 falls +0.12 → −0.12 (Δ−0.24, crossing zero at v ≈ 16 = V₀); **p10 falls only −0.34 → −0.59
(Δ−0.25)** — the resisting tail barely deepens. ~⅘ of the distributional change lives in the upper/productive
half. There is **no emergent bimodal split**, no distinct velocity-growing sub-population of contorted
backward-workers for a commitment gate to excise. This matches — and is the per-episode-distribution view of —
`FORCE_BALANCE_CLOSURE.md` PART 2: the single-attachment impulse crosses zero at V₀, **intrinsic to one
attachment, not recruitment-masked.**

**The clincher:** the actual ceiling mechanism is the **productive episodes weakening** (p90 collapsing), not
incompetent episodes appearing. A *pre-stroke* competence gate — by construction — can only remove episodes
*before* they run; it cannot rescue a productive episode that is losing steam mid-attachment. So even the one
metric that grows with v (`f8ax`) is aimed at the wrong tail: gating out high-load starts throws away weak-but-
still-net-positive episodes along with the resisting ones (r is only ~0.28), reducing occupancy for little net
gain. **The gate has no room to act.** The pre-stroke-strain-gate hypothesis does not survive Variant-B; it is
re-refuted, not merely unrefuted-and-untested.

---

## Plain bottom line

**Does any pre-stroke distortion metric have genuine mechanistic content — rising with v AND predicting bad
episodes at fixed v AND sparing a v=0 population — earning a geometry-gated commitment step?** **No.**
- The **sign-only virtual axial work** (PRIMARY) is a real *static* competence signal (predicts within-v, r ≈
  0.35; spares v=0) but is **velocity-independent** ⇒ a static filter, **not** the saturation mechanism a gate
  needs; gating on it cannot lower V₀.
- The only metric that grows with v is **F8 load**, not distortion; a gate on it **duplicates existing
  load-dependent release** and targets the **wrong tail** (the productive collapse, not a resisting cohort).
- **Variant-B reconciliation:** the impulse ceiling is a **uniform decline via productive-tail collapse**, with
  **no gate-able incompetent sub-population**. The ceiling is intrinsic to a single attachment (re-confirming
  `FORCE_BALANCE_CLOSURE.md` PART 2).

**⇒ Geometric commitment gating is NOT the missing mechanism.** A pre-stroke competence gate here would be an
efficiency-reducer dressed as mechanism (or a redundant load gate) — exactly the outcome jba's honesty
constraint is meant to catch. **No gate is built.** (Consistent with the head-frame test's H1 rejection and
Variant-B: the model's ~1.4× over-Vmax is a *static/geometry* over-drive — the rigid-clamp/flexure residual of
`FORCE_BALANCE_CLOSURE.md` — not a missing velocity-dependent commitment step.)

**Not built (the three-arm follow-up is NOT licensed by this outcome):** since no metric passes all three
questions with mechanistic content, the sketched canonical/gate/head-frame three-arm comparison and the soft
`k_eff = k⁰·exp[−max(0,ΔU)/E*]` competence-rate form are **left unbuilt** (`ΔU_stroke` is flat with v and only
weakly predictive, r ≈ 0.13 — the same static-filter failure). If Vmax calibration is pursued, the evidence
points at the **static over-drive** (stroke size / duty / the rigid-clamp geometry factor), not a
velocity-gated commitment step.

---

## Artifacts

- Code: `-prestroke` (selector) in `GlidingHarness` (per-episode `PSROW` capture at the first ADP·Pi→ADP
  transition; combined-head-torque virtual work `whead`/`wsw`/`wf9`, `dU`, `f8ax`/`f8mag`, `j1`). Default-off
  **byte-identical** (FVROW verified unchanged).
- Runs: `RUN_LOGS/2026-07-11_prestroke_grid.txt` (28 runs, 15 586 episodes). Analysis:
  `RUN_LOGS/prestroke_analyze.py`.
- Constraints honored: analysis-first, **no gate built**; re-run only because the pre-stroke variables were
  unlogged (STEP 0); sign-only virtual work the PRIMARY (no fitting), ΔU/E* screened not fitted; the 87°
  off-axis angle **not** used; CPU deterministic; paired CRN seeds; Variant-B reconciled explicitly (gate not
  assumed unrefuted); `BoA-v1ref` untouched.
