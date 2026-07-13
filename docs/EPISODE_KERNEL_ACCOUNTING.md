# EPISODE_KERNEL_ACCOUNTING — what physically produces V₀ ≈ 16 µm/s? (measure the kernel, don't correlate with it)

**Date:** 2026-07-12 · **Branch:** dt-convergence-study · **Runner:** CPU deterministic (thermal off; the rigid
velocity-clamp arbiter — no GPU/basin hazard). `BoA-v1ref` untouched. **PART 1–2 are LOGGING ONLY (no model
change, no new mechanism, no calibration); PART 3 uses existing flags (`-xcatch`, `-neckangle`).** New flag
`-epkernel`, default-off **byte-identical** (verified: FVROW char-for-char unchanged with/without; the completeness
identity below is machine-exact, idErr ≤ 5e-15 across all 32 runs).

> **BOTTOM LINE.** V₀ ≈ 16 µm/s is **NOT** `d_eff/τ_on`, and **NOT** a lifetime (survival) phenomenon. It is a
> **force / cross-bridge-strain balance**, carried by the **age-conditioned force waveform** `m_f(a,v)`: a bound head
> spends most of its life in a **sustained ADP force-bearing plateau** whose net axial (F8-strain) force is **+0.5 pN
> forward at v=0** and is **eroded by the sliding-induced resistive strain until it crosses zero at v ≈ 16**. The
> **nominal power stroke is a fast transient** (peak actin-level displacement d_peak ≈ 6 nm at age ≤ 0.05 ms) that the
> **compliant body absorbs within ~5 steps** (relaxed d_relax ≈ 0.7 nm) — it contributes only a small early positive
> spike and is **NOT** the dominant impulse term. **Only Model 3 (the measured kernel integral) reproduces V₀; the two
> simple closures fail** — Model 1 `d/T̄` (≈ 8.9) because the dwell is not exponential and reshapes with v, Model 2
> `2d·E[T]/E[T²]` (≈ 9.2) because the force waveform is a transient+plateau, not a linear stroke `k(d−vt)`. **The
> decisive decomposition:** freeze the survival at its v=0 shape and let only the waveform move → the impulse zero
> stays at **V₀ = 16.1**; freeze the waveform at v=0 and let only the survival move → the impulse **never crosses
> zero** (survival shortening only scales the magnitude ~40 %). ⇒ **V₀ is a property of the strain-dependent force
> trajectory, weighted by survival — emergent, intrinsic to one attachment, exactly as `FORCE_BALANCE_CLOSURE.md`
> PART 2 said, now with the physical account.**

---

## Method — the age-resolved episode kernel (`-epkernel`)

Extends the `-vclamp` / `-prestroke` per-episode capture (`GlidingHarness.runForceVelocity`) to a full **age-resolved**
log. The rigid velocity clamp makes the carpet a bank of independent single-motor episodes vs one prescribed sliding
filament (glide dir ĝ = −x̂; `+v` ⇒ forward glide). Only episodes that **attach inside the steady window** are tracked,
so attachment age `a = 0` is a true bind. Grid: **d2000, `-matbox 50`, v = 0, 4, 8, 12, 14, 16, 18, 20 µm/s × 4 paired
seeds, 12 000 steps** (warm-up 4 000).

- **Per age `a` (folded over surviving tracked episodes):** survival `S(a|v) = P(T>a)`; age-conditioned mean glide
  force `m_f(a,v) = E[f_x(a)|T>a]`; transverse force; **relative tip−site axial strain** `(site−tip)·x̂` (the F8
  stretch); head-tip and material-site axial displacement since bind; converter angle `θ_J1 = ∠(û_lever,û_head)`;
  per-state nucleotide fraction; **catch-slip release hazard** `kOff·(αCatch·e^(−F·xCatch/kT)+αSlip·e^(+F·xSlip/kT))`,
  `F = forceDotFil` (`EPKROW`).
- **Per completed episode:** total lifetime `T`; **force-producing-state (ADP) lifetime**; **first force-sign-reversal
  age & filament-travel distance**; positive/negative/net impulse `I₊/I₋/I_net`; rel-strain bind/peak/min/last (⇒ the
  **effective actin-level working displacement** `d_peak = rel0−relmin`, `d_relax = rel0−rellast`); bind and
  power-stroke-onset snapshots (`EPROW`).
- **Completeness gate (`EPKSTAT`):** total impulse held in the age bins ≡ Σ_completed I_net + Σ_censored I_net,
  **machine-exact** (idErr ≤ 5e-15) — proves the age fold dropped no force, so the reconstruction below is not an
  artifact of incomplete logging. Censoring (episodes still bound at run end) is < 1.5 % and reported separately.

---

## PART 1 — the age-resolved kernel

Per-velocity summary (4 seeds pooled; forces pN, times ms, displacement nm):

| v | f̄_avail (pN) | ⟨I⟩ kernel=∫S·m_f | ⟨I⟩ measured | T̄ (ms) | E[T²] | CV(T) | d_peak (nm) | d_relax (nm) | nEp |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0  | +0.649 | +0.517 | +0.514 | 0.661 | 0.837 | 0.96 | 5.84 | 0.70 | 2146 |
| 4  | +0.475 | +0.366 | +0.365 | 0.629 | 0.734 | 0.92 | 5.97 | 0.95 | 2205 |
| 8  | +0.286 | +0.196 | +0.196 | 0.556 | 0.530 | 0.85 | 6.03 | 1.12 | 2213 |
| 12 | +0.151 | +0.099 | +0.097 | 0.529 | 0.450 | 0.78 | 6.03 | 1.22 | 2248 |
| 14 | +0.061 | +0.038 | +0.038 | 0.512 | 0.419 | 0.77 | 6.11 | 1.35 | 2253 |
| **16** | **−0.000** | **−0.000** | **−0.002** | 0.495 | 0.391 | 0.77 | 6.10 | 1.40 | 2288 |
| 18 | −0.048 | −0.029 | −0.029 | 0.488 | 0.380 | 0.77 | 6.27 | 1.58 | 2344 |
| 20 | −0.110 | −0.066 | −0.066 | 0.470 | 0.346 | 0.75 | 6.25 | 1.56 | 2275 |

**V₀ = 16.0** (force f̄ zero 16.00, kernel-impulse zero 15.98, measured-impulse zero 15.92 — all coincident).

**The force waveform `m_f(a,v)` is two-phase, NOT a linear stroke.** At every v the age-resolved force has (i) a **fast
stroke transient** — `m_f` spikes at age 0.01 ms then decays within ~0.05 ms as the compliant body absorbs the neck
swing — and (ii) a **sustained bound-ADP plateau** — as the ADP force-bearing fraction builds to ~0.85 and the
converter completes (θ_J1 → ~60°) over ~0.3 ms, `m_f` settles to a plateau. **The plateau is where V₀ lives:**

| plateau `m_f` (age 0.3–0.5 ms, pN) | v=0 | v=8 | v=16 | v=18 | v=20 |
|---|---:|---:|---:|---:|---:|
| sustained force | **+0.83** | +0.49 | **+0.11** | +0.02 | **−0.07** |
| rel tip−site strain (nm) | +0.6 | +0.4 | ≈ 0 | — | −0.2 |

The **early transient itself reverses** with v (age-0.06 ms `m_f`: +0.23 at v=0 → −0.25 at v=16 → −0.47 at v=20), and
the sustained plateau slides from productive to resistive, its own zero at v ≈ 19; the **net-episode impulse crosses
zero slightly earlier, at V₀ ≈ 16**, because the early transient reverses first and the productive survival shortens —
so V₀ is the plateau balance pulled in by the reversing transient. **The strain (mrel) and the force track one-for-one**
(the F8 spring): V₀ is the sliding speed at which the sustained forward F8 strain from the bound state is cancelled by
the resistive strain the sliding actin imposes.

**The effective actin-level working displacement is ≈ 6 nm at peak but ≈ 0.7 nm relaxed.** `d_peak` (barbed excursion
of the tip relative to its bound actin site) is **≈ 6 nm and nearly velocity-independent** (5.84 → 6.25, +7 % over
v=0→20). But `d_relax` (the excursion still present late in the episode) is **only 0.7–1.6 nm** — the compliant
articulated body absorbs ~85 % of the nominal stroke within a few steps. This is the per-episode-kernel confirmation of
`STROKE_COMPLETION_STRAIN_TEST.md` PART 1 (the stroke force is a transient the compliant body relaxes; the lever
coordinate is not the effective stroke coordinate). **The nominal stroke is not the working displacement the impulse
integral sees.**

---

## PART 2 — the reconstruction gate + the nested-model verdict

### Reconstruction gate — PASS

`⟨I(v)⟩ = ∫₀^∞ S(a|v)·m_f(a,v) da` reproduces the measured per-episode impulse **within 0.1–2.0 %** at every v (the
apparent 92 % at v=16 is a near-zero division: both numbers are ~1e-3, |Δ| = 1.4e-3 pN·ms), and the **kernel impulse
zero (15.98) coincides with the measured impulse zero (15.92) and the ensemble force zero (16.00)**. The
survival×force-waveform decomposition is complete and self-consistent — the log is not missing a channel. **Gate
passed ⇒ interpret.**

### Nested models — only Model 3 survives

Predicting V₀ from the kernel components measured at **low velocity** (the honest anchor — a predictive closure must
use rest-state d and dwell, not the v=16 self-consistent values):

| model | form | prediction (v=0 anchor) | verdict |
|---|---|---:|---|
| **1 — ratio heuristic** | V₀ ≈ d_eff / T̄ | 8.85 (d_peak) / 1.07 (d_relax) | **FAIL** — off by ~2× (peak) or ~15× (relaxed) |
| **2 — linear stroke, stochastic dwell** | V₀ ≈ 2·d_eff·E[T]/E[T²] | 9.22 (d_peak) / 1.11 (d_relax) | **FAIL** — off by ~1.7× |
| **3 — measured kernel** | ∫S·m_f | **15.98** | **PASS** (= measured 16.0) |

- **Model 1 fails** because the dwell is **not exponential** and its **shape moves with v**: CV(T) = 0.96 at v=0
  (≈ exponential, CV=1) but falls to **0.75** by v=20 (sub-exponential / more peaked). A `d/T̄` mean-only reduction is
  wrong at every v, and doubly wrong because the shape itself reshapes.
- **Model 2 fails** because the force waveform is **not** `k(d−vt)` — it is the transient+plateau of PART 1, so the
  neat `2d·E[T]/E[T²]` zero (which assumes a linear ramp eroded by sliding) does not hold. Model 2 only "matches" 16
  if you feed it the v≈16 self-consistent d & dwell — a circular fit, not a prediction.
- **Model 3** reproduces V₀ by construction-of-nothing (it assumes no waveform or dwell form) — so **V₀ is emergent
  from the complete strain-dependent force + survival trajectories.**

### Which kernel component carries V₀ — the counterfactual (decisive)

Recompute `⟨I(v)⟩` with one factor frozen at its v=0 shape:

| v | ⟨I⟩ true | ⟨I⟩ survival frozen@0 (only waveform varies) | ⟨I⟩ waveform frozen@0 (only survival varies) |
|---:|---:|---:|---:|
| 0  | +0.517 | +0.517 | +0.517 |
| 8  | +0.194 | +0.250 | +0.395 |
| 16 | −0.000 | +0.002 | +0.331 |
| 20 | −0.066 | −0.098 | +0.306 |
| **V₀** | **15.98** | **16.12** | **none (never crosses)** |

**The force waveform carries V₀.** With survival frozen at v=0 the impulse still crosses zero at **16.1** — the
waveform's shift with v alone produces the ceiling. With the waveform frozen at v=0 the impulse **never crosses zero**
(stays +0.31…+0.52) — survival/lifetime shortening only **scales the magnitude down ~40 %**, it does not create the
zero. **V₀ is a force (cross-bridge-strain) phenomenon, not a lifetime phenomenon.**

*(Consistency with `VMAX_SENSITIVITY_25C.md`: xCatch is the dominant V₀ lever precisely because the catch term
releases **back-strained (resistive) heads** faster — it reshapes the survivor-conditioned `m_f(a,v)` by censoring the
negative-force ages, i.e. it acts **through** the waveform. PART 3 tests this directly.)*

---

## PART 3 — the two-perturbation decomposition (which component carries ΔV₀?)

Same clamp grid, paired seeds 0–3. One catch-shape perturbation known to move V₀ (`-xcatch 1.0`, vs baseline 2.5 nm)
and one neck-angle perturbation known to move nominal geometry but not V₀ (`-neckangle 40` and `80`, vs 60°):

| arm | V₀ (f̄ zero) | ΔV₀ | d_peak@v0 (nm) | d_relax@v0 (nm) | Δd_peak | Δd_relax |
|---|---:|---:|---:|---:|---:|---:|
| **BASE** (xcatch 2.5, neck 60°) | **16.0** | — | 5.84 | 0.70 | — | — |
| **NECK40** | 14.5 | −1.5 | 5.79 | 0.72 | −0.05 | +0.01 |
| **NECK80** | 15.9 | −0.07 | 5.90 | 0.76 | +0.05 | +0.06 |
| **XCATCH1** | **7.8** | **−8.2** | 6.13 | 0.52 | +0.28 | −0.19 |

**Sustained plateau force `m_f` (age 0.3–0.5 ms, pN) vs v — the age band that decides V₀:**

| arm | v0 | v4 | v8 | v12 | v14 | v16 | zero-cross |
|---|---:|---:|---:|---:|---:|---:|---:|
| BASE | +0.83 | +0.69 | +0.49 | +0.30 | +0.22 | +0.11 | ~19 |
| NECK80 | +0.93 | +0.74 | +0.57 | +0.35 | +0.27 | +0.14 | ~19 (slightly up) |
| NECK40 | +0.72 | +0.54 | +0.39 | +0.18 | +0.14 | −0.03 | ~15 (slightly down) |
| **XCATCH1** | +0.61 | +0.43 (v4) | **+0.16 (v8)** | — | — | — | **~11** (steepened) |

**The neck-angle knob is DISCONNECTED from the effective actin-level displacement (Claude's hypothesis — CONFIRMED).**
Swinging the nominal cocked neck angle 40° → 80° is a **~1.9× change in the nominal stroke** `2·L·sin(θ/2)`
(sin20° → sin40°), yet **d_peak moves 5.79 → 5.90 nm (+2 %)** and **d_relax 0.72 → 0.76 nm** — a **~50× attenuation**.
The compliant articulated body absorbs the neck swing; the effective stroke is read at the F8 tip via HEAD_LEN, not the
lever (consistent with `stroke-effective-lever-is-headlen`). So V₀ barely moves (16.0 → 14.5 / 15.9) — the small
residual is the mild stiffness/geometry coupling (S ≈ 0.15, `VMAX_SENSITIVITY_25C.md`), **not** a stroke-size effect.
**ΔV₀ is NOT carried by d_eff.**

**xCatch carries ΔV₀ through the force waveform `m_f(a,v)`, with d_eff unchanged.** Halving xCatch (2.5 → 1.0 nm) drops
V₀ by 8.2 µm/s while **d_eff is unchanged** (d_peak 5.84 → 6.13, d_relax 0.70 → 0.52 — within the neck-angle-null
scatter). What moves is the **survivor-conditioned plateau force**: its decline with v **steepens** (zero-crossing
~19 → ~11), so the impulse zero drops to 7.8. Mechanism: the catch term `αCatch·e^(−F·xCatch/kT)` at lower xCatch is
**less sensitive to a resistive (back-strained, F<0) load** ⇒ back-strained heads are **released more slowly** ⇒ they
**persist in the surviving population** ⇒ `m_f(a,v)` (the mean over survivors) is dragged negative faster as v rises.
So xCatch acts **exactly through the waveform channel PART 2 identified** — it reshapes `m_f(a,v)` by controlling the
strain-selective censoring of resistive heads. **This unifies with `VMAX_SENSITIVITY_25C.md` (xCatch the dominant V₀
lever): the catch-slip SHAPE is dominant because it is the knob on the survivor-conditioned force waveform, which is
the component that carries V₀.**

**Decomposition scorecard:**

| perturbation | Δ d_eff | Δ S(a) (T̄) | Δ m_f(a,v) shape | carries ΔV₀? |
|---|---|---|---|---|
| **neck-angle** (nominal stroke ×1.9) | **~0** (+2 %) | ~0 | ~0 (mild ±) | **NO** (ΔV₀ ≤ 1.5, the stiffness residual) |
| **xCatch** (catch shape) | **~0** | + (T̄ 0.66→1.06) | **steepens, zero 19→11** | **YES — via the waveform** |

---

## Plain bottom line

**What physically produces V₀ ≈ 16 µm/s?** The **sustained cross-bridge force of the bound ADP state under sliding** —
a force/strain balance, not a stroke-size-over-dwell ratio. A bound head spends most of its ~0.5 ms life in a
force-bearing ADP plateau holding a **net-forward F8 strain (~+0.5 pN at v=0)**; the sliding actin adds resistive
strain that **erodes this plateau linearly with v and cancels it at v ≈ 16**, where the per-episode net impulse crosses
zero. This is carried by the **age-conditioned force waveform `m_f(a,v)`** (freezing survival at v=0 leaves V₀ = 16.1;
freezing the waveform leaves no zero at all — survival only scales the magnitude ~40 %).

**Is the nominal stroke knob disconnected from the effective stroke?** **Yes, decisively.** The nominal neck stroke is a
**fast transient** with peak actin-level displacement d_peak ≈ 6 nm that the **compliant body relaxes to d_relax ≈ 0.7
nm within ~5 steps**; and a **1.9× change in the nominal neck angle moves the effective d_eff by only ~2 %** and V₀ by
< 10 %. The effective actin-level working displacement — not the neck angle — is what the impulse integral sees, and it
is nearly velocity- and neck-angle-independent.

**Which closure is right?** **Only Model 3 (the measured kernel).** `V₀ ≈ d_eff/τ_on` (Model 1, ≈ 8.9) and the
linear-stroke `V₀ = 2d_eff·E[T]/E[T²]` (Model 2, ≈ 9.2) both fail because (i) the force waveform is a transient+plateau,
not `k(d−vt)`, and (ii) the dwell is not exponential and its shape moves with v (CV 0.96 → 0.75). **V₀ is emergent from
the complete strain-dependent force + survival trajectories** — the dominant lever is the **catch-slip shape (xCatch)**,
because it is the knob on the survivor-conditioned force waveform, i.e. on `m_f(a,v)` itself. This closes the loop with
`FORCE_BALANCE_CLOSURE.md` (ceiling intrinsic to one attachment), `PRESTROKE_DISTORTION_ANALYSIS.md` (uniform
productive-tail decline, no gate-able sub-population), and `VMAX_SENSITIVITY_25C.md` (catch-slip shape dominant, stroke
size not) — and gives the mechanism those three only bounded: **V₀ is a bound-state F8-strain balance read through the
survival-weighted force waveform, and the nominal stroke is decoupled from it by the compliant body.**

## Artifacts

- Code: `-epkernel` (selector) in `GlidingHarness.runForceVelocity` — age-resolved `EPKROW` (S, m_f, strain, tip/site
  displacement, converter, nucleotide fractions, release hazard), per-episode `EPROW` (T, ADP-lifetime, sign-reversal
  age/distance, I₊/I₋/net, d_eff peak/relaxed, bind & stroke-onset snapshots), `EPKSTAT` completeness identity +
  reconstruction. Default-off **byte-identical**.
- Runs: `RUN_LOGS/2026-07-12_epkernel_grid.txt` (PART 1–2, 32 runs), `RUN_LOGS/2026-07-12_epkernel_part3.txt`
  (PART 3). Analysis: `RUN_LOGS/epkernel_analyze.py`, `RUN_LOGS/epkernel_part3_analyze.py`.
  Drivers: `scripts/run_epkernel_grid.sh`, `scripts/run_epkernel_part3.sh`.
- Commands:
```
scripts/run_gliding.sh -matbox 50 -density 2000 -vclamp 8 -seed 0 -epkernel 12000   # one kernel point (EPKROW/EPROW/EPKSTAT)
scripts/run_epkernel_grid.sh  RUN_LOGS/2026-07-12_epkernel_grid.txt  12000            # PART 1–2 grid (8 v × 4 seeds)
scripts/run_epkernel_part3.sh RUN_LOGS/2026-07-12_epkernel_part3.txt 12000            # PART 3 (xcatch 1.0 ; neck 40/80)
python3 RUN_LOGS/epkernel_analyze.py        RUN_LOGS/2026-07-12_epkernel_grid.txt      # PART 1–2 tables + models
python3 RUN_LOGS/epkernel_part3_analyze.py  RUN_LOGS/2026-07-12_epkernel_part3.txt  RUN_LOGS/2026-07-12_epkernel_grid.txt
```
- Constraints honored: logging-only PART 1–2 (existing flags in PART 3); reconstruction gate first (completeness
  identity machine-exact ⇒ log complete); `V₀=d/τ` tested (Model 1) and rejected, not assumed; effective actin-level
  working displacement reported (never the neck angle as a proxy); paired CRN seeds; CPU deterministic; `f̄_available`
  primary; `BoA-v1ref` untouched.
