# Is the residual per-bound-glide dt-climb a dt-dependent stroke RATE or genuine rotational STIFFNESS?

**Date:** 2026-07-05. Flag-gated (`-strokerate`, new); **default byte-identical**; `BoA-v1ref` untouched.
Follows `COUPLED_IMPLICIT_XB_FINDINGS.md` (the coupled F8 star converged the binding count + detach clock but NOT
the per-bound GLIDE — velFitX/avgBound 0.73 → 1.73 → 2.46 across dt 1e-5 / 2.5e-6 / 1.25e-6, monotone, unbracketed;
attributed to "the still-explicit stroke/rotation/force couplings"). This file decides WHICH: a dt-dependent
stroke **rate** (per-step fraction → cheap same-dt fix) or genuine rotational **stiffness** (needs implicit/sub-step).

---

## PLAIN ANSWER (headline)
**Is the residual glide dt-climb a dt-dependent RATE (cheap same-dt fix) or genuine rotational STIFFNESS (needs
implicit/sub-step)?** → **BOTH — but the cheap rate fix is NOT sufficient, so the answer for "which path" is:
scope the rotational implicit / sub-step.**
- A per-step-fraction rate DOES exist (STEP 1: the directedSwing stroke AND the F9/F10 alignment torques are
  `0.4/step`, dt cancels ⇒ rate ∝ 1/dt by construction). The catch `g(F)` is a proper per-time rate (not implicated).
- **Stroke rate: negligible.** `-strokerate` (stroke → per-time) barely moves the steady glide (within the ~20 %
  single-seed noise) — the glide is set by the sustained F8 pull over the ~600-step dwell, not the ~6-step stroke.
- **Alignment (F9/F10) rate: a REAL, significant component.** `-alignrate` (F9/F10 → per-time) suppresses the
  fine-dt glide runaway substantially — at dt=1.25e-6 velFitX **5.88 vs coupled 10.06 (−42 %, beyond noise)**; the
  suppression GROWS as dt→0 (the rate-artifact fingerprint). So the alignment torque's per-step-fraction IS a
  genuine dt-dependent-rate contributor, and the cheap same-dt conversion materially reduces the runaway.
- **But it does NOT achieve dt-robustness at production dt:** even with ALL rotational rates converted, the glide
  still climbs **2.24×** from 1e-5 (2.46) to 2.5e-6 (5.50). A residual dt-climb persists that no per-step→per-time
  redefinition removes — the **explicit forward-Euler rotational INTEGRATION** overshoot (the head/segment
  orientation that sets the F8-stretch tip geometry), i.e. genuine stiffness. (The F8 *translation* stiffness was
  already removed by the coupled star in COUPLED_IMPLICIT_XB; this residual is the rotational half.)
- **⇒ WHICH PATH:** the cheap rate fix (esp. `-alignrate`) is a **worthwhile real partial** but is **not
  sufficient** for a dt-robust production-dt glide. **Scope the full-motor (rotational) implicit — extend the
  coupled star to the head/segment ROTATION — or the sub-step.** (Single-seed noise ~20 % limits precise
  attribution of the rate-vs-residual split; a multi-seed confirm would sharpen it but does not change the
  "rate-fix-insufficient → rotational implicit/sub-step" conclusion, which rests on the beyond-noise 2.24× residual.)

---

## STEP 1 — code read: per-STEP fraction, or per-TIME rate? (the master torque law)

The integrator applies angular motion as **Δθ = τ·dt/γ_rot** (`RigidRodLangevinIntegrationSystem:79-110`:
`bw = τ/γ_rot`, orientation update `∝ bw·dt`). So a torque `τ` produces a per-step angular step `τ·dt/γ`.

### 1a. The directed power stroke (`directedSwing`) — PER-STEP FRACTION (dt-independent per step). **← the stroke.**
Default motor = SPHEREHEAD + AXLOCK + **DIRSWING** (`GlidingHarness.java:230-232`; the active stroke is
`CrossBridgeSystem.directedSwing`). `swingParams = [0.4, DT, 0, 60]` ⇒ swing coeff **k = 0.4**.
`directedSwing:251`: `mag = k·ang / ((1/γ_lev + 1/γ_head)·dt)`, applied +mag to the lever, −mag to the head.
Per-step angular step of the lever = `mag·dt/γ_lev = k·ang·(1/γ_lev)/((1/γ_lev)+(1/γ_head))` — **the `dt`
CANCELS**. Summed over lever + head, the lever-to-target angle **relaxes by the fixed fraction k = 0.4 PER STEP,
independent of dt** (the master torque law: the `·dt` in the denominator is designed to cancel the integrator's
`·dt`). ⇒ the stroke closes 40 % of the remaining angle per step ⇒ reaches ~95 % in **~6 steps regardless of dt**.

**Sim-time per stroke (0 → 60° neck swing):** ≈6 steps × dt = **6.0e-5 s @ dt=1e-5, 1.5e-5 s @ 2.5e-6, 7.5e-6 s @
1.25e-6** — the sim-time **HALVES with each dt-halving**. So the stroke angular RATE (and the tip-sweep velocity that
pushes the filament) **scales ∝ 1/dt**. This is a dt-dependent stroke rate **BY CONSTRUCTION** — the "converged"
(dt→0) limit is an unphysically instantaneous stroke, not a physical fixed-sim-time stroke.

### 1b. F9 / F10 / axial-lock alignment torques (`bondForces`) — also PER-STEP FRACTIONS.
`bondForces:152/163/185`: `tm = j1FMT·ang / ((1/γ_a + 1/γ_b)·dt)`, `j1FMT = xbParams[2] = 0.4`. Identical
structure ⇒ per-step fraction 0.4, dt cancels ⇒ rate ∝ 1/dt. In the DEFAULT (SPHEREHEAD) path F9 is **frozen at
90°** (`f9Frozen`) and F10 is the axial roll lock — these are head-alignment **CONSTRAINTS** (keep the head ⊥ /
rolled to the filament), not the forward-push stroke, but they carry the same dt-scaling. The forward push is
`directedSwing` (1a).

### 1c. The F8 tip/site rotation — the positional torque `R×F8`, NOT a per-step-fraction coefficient.
`bondForces:137-141`: the F8 spring's torque on head/segment is `R × F8` (R = tip/site offset). Its magnitude
rides on the F8 force, which the coupled solve made **implicit (stable)** in translation; the rotational response
is explicit but bounded by the now-implicit F8. No separate `fracMove` coefficient. Not the per-step-fraction class.

### 1d. The catch `g(F)` release — a PROPER PER-TIME RATE (dt-correct). NOT an artifact.
`catchSlipRelease:193-197`: `rate = kOff·(aCatch·exp(−F·xCatch/kT) + aSlip·exp(F·xSlip/kT))`; release iff
`u < rate·dt`. Probability = rate·dt ⇒ **per-time rate, dt-correct**. Its only dt-dependence was via `F` (the F8
overshoot), which the coupled solve already fixed (the detach clock converged in COUPLED_IMPLICIT_XB). Not a
per-step-fraction bias.

### STEP-1 verdict
The forward-push **stroke** (`directedSwing`, 0.4/step) and the alignment torques (F9/F10, 0.4/step) are **per-step
fractions** whose `·dt` cancels the integrator `·dt` ⇒ they relax a fixed fraction PER STEP ⇒ the stroke/alignment
sim-time DURATION shrinks ∝ dt ⇒ the stroke **rate scales ∝ 1/dt by construction**. The catch is a proper per-time
rate (not implicated). ⇒ **there IS a dt-dependent stroke rate in the code** — the cheap same-dt fix (STEP 3) must
be tried before scoping full-motor-implicit / sub-step. STEP 2 tests whether the observed per-bound-glide climb
matches the ∝1/dt fingerprint.

## STEP 2 — per-bound-drift vs 1/dt fingerprint (coupled, no rate fix; from COUPLED_IMPLICIT_XB)

| dt | 1/dt | velFitX | avgBound | **per-bound = velFitX/avgBound** | climb (per dt-halving) |
|--:|--:|--:|--:|--:|--:|
| 1e-5   | 1×10⁵ | 2.020 | 2.774 | **0.728** | — |
| 2.5e-6 | 4×10⁵ | 6.279 | 3.615 | **1.737** | ×1.55 /halving (×2.39 over 4×) |
| 1.25e-6| 8×10⁵ | 10.064 | 4.083 | **2.465** | ×1.42 /halving |

Per-bound drift ∝ (1/dt)^**~0.55** (4× refine → 2.39× ⇒ p=0.63; 2× refine → 1.42× ⇒ p=0.51). **Sub-linear, no
plateau.** Pure rate-artifact would be p=1 (∝1/dt); pure stiffness-converging would be p→0 (plateau). Observed
p≈0.55 is between ⇒ **NOT pure rate, NOT plateauing** — consistent with STEP 1's finding that a per-step-fraction
stroke rate IS present (so the climb is rate-driven in part), but a second effect (drag/dwell) softens the scaling.
The **decisive test is STEP 3**: if converting the stroke to a per-time rate flattens per-bound drift, the climb
WAS the stroke rate (the sub-linearity was drag/dwell co-moving); if it keeps climbing, there is genuine stiffness.

## STEP 3 — `-strokerate` (stroke as a per-TIME rate): does per-bound glide flatten across dt? — NO (stroke rate negligible)
Fix: `directedSwing` per-step fraction `k=0.4` → `k_eff = 1 − (1−k)^(dt/refDt)`, `refDt=1e-5` (so k_eff==0.4 at
production dt — magnitude/coarse-dt duration preserved; at finer dt the stroke takes more STEPS, same sim-time).
Flag-gated (`-strokerate`), default byte-identical (size-4 swingParams ⇒ code skipped). k_eff: 0.400 @1e-5 (no-op),
0.120 @2.5e-6, 0.062 @1.25e-6.

**Result (GPU, coltol10/d1000/-xbimplicit2, seed 0; velFitX from GRID_ROW, avgBound from STATS_STEADY_ROW):**

| scheme | dt | velFitX | avgBound | per-bound | velFitX climb (per 4× dt) |
|---|--:|--:|--:|--:|--:|
| coupled (no rate) | 1e-5 | 2.020 | 2.774 | 0.728 | — |
| coupled (no rate) | 2.5e-6 | 6.279 | 3.615 | 1.737 | **3.11×** |
| coupled (no rate) | 1.25e-6 | 10.064 | 4.203 | 2.395 | (×1.60/2× halving) |
| **-strokerate** | 1e-5 | 2.459 | 3.044 | 0.808 | — |
| **-strokerate** | 2.5e-6 | 4.701 | 2.900 | 1.621 | **1.91×** |
| **-strokerate** | 1.25e-6 | 9.681 | 4.299 | 2.252 | (×1.39/2× halving) |

**Single-seed noise floor ≈ 20 %** — the dt=1e-5 point is a near-no-op (k_eff=0.4=original) yet `-strokerate`
reads velFitX 2.459 vs coupled 2.020 (+22 %); that gap is pure chaotic decorrelation (k_eff differs from 0.4 only
at float level, which over 120k steps fully decorrelates the microstate). So compare only differences ≫ 20 %.

**`-strokerate` does NOT flatten the per-bound climb.** Against that ~20 % floor, strokerate ≈ coupled at EVERY dt
(per-bound 0.81/1.62/2.25 vs 0.73/1.74/2.40); both climb ~3× across the dt range. The apparent 25 % drop at 2.5e-6
did not survive the 1.25e-6 point (strokerate 9.68 ≈ coupled 10.06, within 4 %). **So the directedSwing per-step-
fraction stroke rate — though real (STEP 1) — is NOT the dominant cause of the residual per-bound-glide climb.**
Mechanistically consistent: the steady glide is dominated by the **sustained F8 pull over the long dwell (~0.6 ms =
~600 steps)**, not the brief ~6-step stroke transient; re-timing the stroke barely moves the steady per-bound push.
⇒ the residual is either the F9/F10/axlock **alignment** (also per-step fractions — the `-alignrate` test) or
**genuine rotational stiffness**.

### STEP 3b — `-alignrate`: also convert F9/F10/axlock to per-time rates (the decisive A/B)
Since the per-step fraction `xbParams[2]=0.4` is fixed per run (DT known at build), F9/F10 convert to a per-time
rate at BUILD time (`alignK = 1−(1−0.4)^(DT/refDt)`), NO kernel change, default byte-identical. `-strokerate
-alignrate` converts ALL rotational per-step fractions.

**velFitX (the glide — the robust observable; per-bound divides by the noisier avgBound):**

| scheme | velFitX @1e-5 | @2.5e-6 | @1.25e-6 | allrate/coupled ratio |
|---|--:|--:|--:|--:|
| coupled (no rate)          | 2.020 | 6.279 | 10.064 | — |
| -strokerate                | 2.459 | 4.701 | 9.681 | — |
| **-strokerate -alignrate** | 2.459 | 5.503 | **5.880** | 1.22 / 0.88 / **0.58** |

**Two robust reads (vs the ~20 % single-seed floor set by the 1e-5 no-op point):**
1. **Alignment (F9/F10) rate is a REAL, significant contributor.** `allrate/coupled` velFitX DROPS with finer dt
   (1.22 → 0.88 → **0.58**) — at 1.25e-6 allrate (5.88) is **42 % below** coupled (10.06), beyond noise, and the
   suppression GROWS as dt→0 (the rate-artifact fingerprint). So converting F9/F10 to a per-time rate materially
   suppresses the fine-dt glide runaway. (Note `-strokerate` alone did NOT — the stroke transient is negligible;
   it's the F9/F10 alignment, which acts on EVERY step of the ~600-step dwell, that carries the rate bias.)
2. **But it does NOT reach dt-robustness at production dt.** Even with all rotational rates converted, allrate
   still climbs **2.24×** from 1e-5 (2.459) to 2.5e-6 (5.503) — a residual dt-climb beyond noise that no
   per-step→per-time redefinition removes.

### BOTTOM LINE — **BOTH a real alignment-rate component AND a residual rotational stiffness; the cheap rate fix is NOT sufficient.**
STEP 1 confirmed the stroke + F9/F10 ARE per-step fractions (rate ∝1/dt). STEP 3 resolves their weights: the
**stroke** rate is negligible for the steady glide (dominated by the sustained F8 pull over the ~600-step dwell,
not the ~6-step stroke), but the **F9/F10 alignment** rate is a genuine dt-dependent contributor that `-alignrate`
suppresses ~40 % at fine dt. What no rate redefinition removes is the residual **explicit forward-Euler rotational
INTEGRATION** overshoot — the head/segment orientation (`R×F8` tip torque + alignment) advanced as `Δθ = τ·dt/γ`
under-resolves the stiff orientation dynamics at coarse dt (the tip geometry that sets the F8 stretch, hence the
glide). That is genuine rotational **stiffness**, converging only as dt→0.

**⇒ WHICH PATH: the cheap same-dt rate fix (esp. `-alignrate`) is a worthwhile REAL PARTIAL — it cuts the fine-dt
glide runaway ~40 % — but it does NOT deliver a dt-robust production-dt glide (residual 2.24× climb). Scope the
full-motor (rotational) implicit** — extend the coupled star (COUPLED_IMPLICIT_XB, which fixed the F8 *translation*)
to the head/segment **ROTATION** — **or the sub-step** (`substep-feasibility-verdict`). Keep `-strokerate` (negligible)
and `-alignrate` (real ~40 % fine-dt partial) as documented, flag-gated, default-byte-identical instruments, NOT
the production fix. **Caveat:** single-seed (~20 % noise) limits the precise rate-vs-stiffness split; a multi-seed
confirm at 2.5e-6/1.25e-6 would sharpen the weights but does not change the actionable conclusion (rate-fix
insufficient → rotational implicit/sub-step), which rests on the beyond-noise 2.24× residual and the 42 % alignrate
suppression.

## Reproduce
```
# stroke rate → per-time (default byte-identical; size-4 swingParams untouched when off):
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -strokerate -dt <DT> -seed 0 <STEPS>
# + alignment (F9/F10/axlock) rate → per-time (build-time xbParams[2]; default alignK=0.4 ⇒ byte-identical):
./run_gliding.sh ... -xbimplicit2 -strokerate -alignrate -dt <DT> -seed 0 <STEPS>
#   dt 1e-5→120k, 2.5e-6→480k, 1.25e-6→960k. refDt override: -strokerefdt <s> (default 1e-5).
# batches: ./run_strokerate_conv.sh  ./run_alignrate_conv.sh
#   logs RUN_LOGS/2026-07-05_strokerate_conv.txt, _alignrate_conv.txt
```
Files: `CrossBridgeSystem.directedSwing/directedSwingHeadFrame` (in-kernel k_eff via swingParams[4]) +
`GlidingHarness` (`STROKE_RATE`/`ALIGN_RATE`/`STROKE_REF_DT`; `swingParams[4]`, build-time `alignK` in `xbParams[2]`).
Default byte-identical (swingParams size 4 & alignK=0.4); `BoA-v1ref` untouched.
