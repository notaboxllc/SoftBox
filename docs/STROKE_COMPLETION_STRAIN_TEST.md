# Does load-dependent stroke COMPLETION move V₀'s zero, or only scale amplitude? (measure before build)

**Date:** 2026-07-11 · **Branch:** dt-convergence-study · **Runner:** CPU deterministic (the velocity-clamp
basin arbiter — no GPU float-op-ordering hazard). `BoA-v1ref` untouched. **Measurement/analysis only — NO
canonical build.** Flag-gated, default-off **byte-identical**: `-strokeload` (PART-1 probe) and `-strokecomp`
(the PART-2 minimal modifier) add a dispatch branch + `CrossBridgeSystem.directedSwingComp` + a read-only
completion accumulator in the clamp; the default path never reaches the new code (guarded by `STROKECOMP≥0`,
default −1) and the accumulator writes only local scalars ⇒ FVROW byte-identical (verified structurally).

> **BOTTOM LINE — OUTCOME 2 (the c·f̄ null): load-dependent stroke completion, expressed as the smallest knob
> on the EXISTING stroke, is DOMINANTLY an AMPLITUDE scaler, NOT a V₀ lever — and the only way to get any
> zero-shift is to also WRECK the low-load stroke. Do NOT build it.**
> - **Prerequisite check (PART 1): the existing DIRSWING stroke already drives to FULL completion regardless of
>   load — it has essentially NO load-dependent stalling.** The realized converter rotation θ_J1 = ∠(û_lever,û_head)
>   sits at **≈100–108 % of the 60° target at EVERY sliding velocity** (65.2° at v=0 → 60.0° at v=20; a mild ~8 %
>   decline, and it stays *above* 60°). A quasi-static single-motor probe agrees: at imposed axial offsets from +12
>   to −24 nm the converter holds **~98 % completion** — because the compliant articulated body *absorbs* a static
>   offset (residual F8 axial strain stays |s_ax|<0.08 nm, segFx≈0.05 pN), so the stroke force is **transient**, not
>   a sustained static quantity. ⇒ this is DIRSWING's "perfect axial advantage": there is no existing partial-
>   completion mechanism to duplicate, and none to strengthen without adding one.
> - **The decisive cut (PART 2, multi-seed): the minimal strain-completion modifier does NOT significantly move
>   the zero; it mildly scales f̄ DOWN (including at v=0, where there is no velocity-load).** `-strokecomp E*`
>   reduces the cocked target under resistive F8 axial strain, `θ_eff = θ_u+(θ_c−θ_u)·exp(−max(0,s_res)/E*)`.
>   Over 3 seeds, **V₀ is unchanged within noise**: canonical **15.1**, E*=2 **14.8** (Δ−0.2), E*=1 **13.8**
>   (Δ−1.3, inside the ±1.7 seed SD — the eye-catching single-seed "11.9" was a **seed-0 low draw**, the same seed
>   `FORCE_VELOCITY_TEST.md` flags as sitting low). Meanwhile the **v=0** (zero-velocity, no v-load) force is cut a
>   mild **7 % (E*=2) / 11 % (E*=1)** — an amplitude reduction present at zero velocity. And though the modifier
>   robustly cuts the converter *completion* (θ_J1 65°→48° at E*=1, a parallel down-shift, NOT a steepening), that
>   barely moves f̄ — the lever-completion knob is a **weak, loosely-coupled** force lever. ⇒ **the zero does not
>   move; only the amplitude (mildly) scales** — the c·f̄ null, and the harshest arm's flicker of a shift is a
>   low-seed artifact bundled with low-load degradation, not a real, fidelity-preserving V₀ lever.
> - **Root cause (mechanistic):** the F8 axial strain the modifier keys on is **dominated by the stroke's OWN
>   tip-advance**, not the velocity-load — after the power stroke the tip sits ~one stroke barbed-ward of the
>   material site, so `(tip−site)·f̂ > 0` even at v=0. Throttling completion on this strain is a **negative feedback
>   on the productive stroke itself**, which fires at all v. The velocity-driven increment is small (the pre-stroke
>   `f8ax` grew only −0.93 → −1.43 nm over v=0→20, `PRESTROKE_DISTORTION_ANALYSIS.md`), so the modifier can't
>   separate "resistive load" from "the stroke working." This is the same object `FORCE_VELOCITY_TEST.md`/
>   `FORCE_BALANCE_CLOSURE.md` already identified: **V₀ ≈ 16 is a STATIC/geometry over-drive (stroke size / duty /
>   rigid-clamp), not a missing velocity-dependent completion step.** The fidelity-increasing "load-dependent
>   completion" path does NOT survive the zero-vs-amplitude cut.

---

## Method

Canonical stack throughout: **SPHEREHEAD + AXLOCK + DIRSWING + springs + LYMN_TAYLOR, dt=1e-5**. In this
stack the **power stroke IS `directedSwing`** (the lever swings 0°→60° when the nucleotide switches
ADP·Pi→ADP; F9 is frozen at 90°, the ⊥-maintainer — `stroke-effective-lever-is-headlen` is superseded here).
The realized **stroke completion** is read as the converter angle **θ_J1 = ∠(û_lever,û_head)** (0° = pre-stroke,
→60° = full completion) and, for precision, the **swing residual** ∠(û_lever, θ_cocked-target) (→0 = complete).

**PART 1 — two loadings.**
- *Quasi-static* (`-strokeload`, extends `STROKE_DRAG_PROBE`): one motor deterministically bound to a HELD rigid
  filament, nucleotide forced to ADP, Brownian OFF; the material site is displaced by a ladder of axial offsets Δ
  (Δ<0 = filament slid −x, the glide direction = RESISTIVE), the body equilibrated, and θ_J1 / s_ax=(tip−site)·x̂ /
  |F8| / axial force read.
- *Sustained sliding* (the velocity clamp, `-vclamp v`, the faithful single-molecule load — a bank of independent
  motors vs one rigid filament advancing at −v·dt): the mean θ_J1 of **bound ADP** motors over the steady window,
  vs v. This is the decisive PART-1 read (the quasi-static offset is absorbed; only sustained sliding loads the
  bond).

**PART 2 — the decisive zero-vs-amplitude cut.** Under the same velocity clamp, `f̄_available(v)` (the PRIMARY
force–velocity curve of `FORCE_VELOCITY_TEST.md`; V₀ = its zero) for the canonical stroke vs the minimal
strain-completion modifier at E* = 4, 2, 1 nm. d2000, `-matbox 50`, 10 000 steps/point, warm M/3. **Zero moves ⇒
real lever; curve scales down at the SAME zero ⇒ the c·f̄ null.**

---

## PART 1 — the existing stroke already completes fully under load (no stalling)

**Sustained sliding — canonical converter completion vs v (seed 0, d2000):**

| v (µm/s) | 0 | 4 | 8 | 10 | 12 | 14 | 16 | 18 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **θ_J1 (deg)** | 65.2 | 64.0 | 62.5 | 62.2 | 61.2 | 61.0 | 60.5 | 60.0 |
| **% of 60°** | 108.7 | 106.6 | 104.1 | 103.7 | 102.0 | 101.6 | 100.9 | 100.0 |
| **f̄_available (pN)** | +0.668 | +0.457 | +0.235 | +0.151 | +0.069 | +0.016 | −0.044 | −0.126 |

The converter is at (or slightly above) **full completion at every velocity** — it declines only ~8 % from v=0 to
v=20 and never falls below the 60° target. So the force decline that produces V₀ is **NOT** a stroke that
completes less under load; the stroke completes, and the per-head force falls for the *separate* reason
`FORCE_VELOCITY_TEST.md` documents (the freshly-bound head strokes forward, the sliding then out-runs it, the
material-latched F8 turns resistive — `STROKE_DRAG_PROBE.md`). **The existing stroke drives to full completion
regardless of strain (DIRSWING's "perfect axial advantage").**

**Quasi-static — a static offset is ABSORBED (force transient; completion held):**

| Δ (nm) | +12 | +4 | 0 | −8 | −16 | −24 |
|---:|---:|---:|---:|---:|---:|---:|
| s_ax=(tip−site)·x̂ (nm) | +0.077 | +0.063 | +0.042 | −0.010 | −0.054 | −0.069 |
| |F8| (pN) | 0.31 | 0.44 | 0.47 | 0.48 | 0.42 | 0.30 |
| θ_J1 (deg) | 58.9 | 58.6 | 58.6 | 58.7 | 59.1 | 59.5 |
| completion (% of 60°) | 98.1 | 97.6 | 97.6 | 97.9 | 98.5 | 99.2 |

Displacing the filament ±24 nm barely strains the bond (|s_ax|<0.08 nm) and produces ≈0.05 pN of axial force —
the **compliant articulated body (soft J1/J2/anchor springs) absorbs the offset**, so a *static* load cannot be
imposed; the stroke force is a **transient** that fires on the ADP·Pi→ADP switch and relaxes. Completion holds
at ~98 % throughout. (This is why the decisive load-dependence read must be the *sliding* clamp above, not a
static offset.) **Prerequisite check answered: the existing stroke has no load-dependent partial-completion — a
new completion knob does not duplicate F8/catch-slip/break-cap, but there is also nothing to strengthen.**

---

## PART 2 — the decisive cut: zero MOVES or amplitude SCALES?

`f̄_available(v)` (pN), canonical vs `-strokecomp E*` (seed 0, d2000, 10 k steps):

| v | canonical | E*=4 | E*=2 | E*=1 |
|---:|---:|---:|---:|---:|
| 0 | **+0.668** | +0.547 | +0.537 | +0.528 |
| 4 | +0.457 | +0.390 | +0.376 | +0.371 |
| 8 | +0.235 | +0.187 | +0.213 | +0.222 |
| 10 | +0.151 | +0.124 | +0.146 | +0.107 |
| 12 | +0.069 | +0.091 | +0.041 | −0.008 |
| 14 | +0.016 | +0.037 | +0.014 | +0.012 |
| 15 | −0.005 | −0.023 | −0.034 | −0.027 |
| 16 | −0.044 | −0.055 | −0.058 | −0.051 |
| 18 | −0.126 | −0.130 | −0.155 | −0.120 |

**Multi-seed (3 seeds: 0,1,2) confirmation — the seed-0 shift does not survive:**

| | canonical | E*=2 | E*=1 |
|---|---:|---:|---:|
| **V₀ (µm/s), seed 0** | 14.76 | 14.29 | 11.86 |
| **V₀ per seed** | 14.8, 15.4 | 14.3, 14.6, 15.7 | **11.9, 14.1, 15.2** |
| **V₀ mean ± SD** | **15.06** | **14.83 ± 0.7** | **13.75 ± 1.7** |
| **ΔV₀ vs canonical** | — | −0.2 (n.s.) | −1.3 (< 1 SD, n.s.) |
| **f̄(v=0) ratio /canonical** | 1.00 | **0.93 ± 0.09** | **0.89 ± 0.07** |
| **θ_J1(v=0) (deg)** | 65.2 | 52.2 | 48.4 |

**Reading the cut — this is the amplitude null, not a zero lever:**
1. **V₀ does NOT significantly move.** E*=2 → 14.8 (Δ−0.2, n.s.); E*=1 → 13.8 (Δ−1.3, **within the ±1.7 seed SD**).
   The eye-catching single-seed E*=1 "V₀ = 11.9" is a **seed-0 low draw** (seeds 1,2 give 14.1 and 15.2 — no shift);
   `FORCE_VELOCITY_TEST.md` PART 4 already flags seed 0 as sitting low. So there is **no robust zero movement**.
2. **The v=0 amplitude IS cut (mildly), at ZERO velocity** — E*=2 −7 %, E*=1 −11 % (3-seed). A pure V₀ lever would
   leave v=0 (a fresh, unloaded head) untouched; instead the drive drops from the origin. This is the `c·f̄`
   signature: lower the whole curve, keep the zero.
3. **Completion is cut a lot but force barely moves — a weak, loosely-coupled lever.** θ_J1(v=0) falls 65°→48°
   (E*=1) as a **parallel down-shift** of the canonical curve (same shallow slope, NOT steepened), yet f̄ falls only
   ~11 % and V₀ holds. Cutting the *lever* swing does not proportionally cut the *F8 tip force* (the head is also
   pinned by F9/anchor), so completion is a poor handle on force — reinforcing that this is not the mechanism.
4. **Low-load in-spec check FAILS for the modifier.** The canonical low-load stroke is in spec (full completion,
   the validated ~7 nm working stroke / peak unitary force of `MYOSIN_VALIDATION.md`/`STROKE_VS_ARMLENGTH`); every
   `-strokecomp` arm reduces the v=0 completion and per-bound force — it degrades the unloaded stroke **without**
   buying a robust V₀ shift in return.

**Why (root cause).** The F8 axial strain the completion keys on is dominated by the **stroke's own tip-advance**,
not the resistive velocity-load: post-stroke the tip sits ~one working stroke barbed-ward of the material site, so
`s_res=(tip−site)·f̂ > 0` even at v=0. Reducing completion on `s_res` is therefore a **negative feedback on the
productive stroke**, firing at every velocity — hence the uniform amplitude cut. The genuinely velocity-driven part
of the strain is small (pre-stroke `f8ax` −0.93→−1.43 nm over v=0→20), so the knob cannot tell "resistive load"
from "the stroke doing its job." This is the c·f̄ null: `f̄_new ≈ c·f̄_old` lowers finite-density speed (worsening
the density problem) without moving the zero.

---

## Three outcomes → selected

1. ~~Zero moves, low-load stroke intact ⇒ real fidelity-preserving V₀ lever.~~ **NOT observed** — V₀ is unchanged
   within seed noise, and what v=0 amplitude/completion cut there is only *degrades* the unloaded stroke.
2. **Only amplitude scales, zero ~unchanged ⇒ the c·f̄ null. SELECTED.** Multi-seed: V₀ within noise for both E*
   (15.1 → 14.8 / 13.8, ≤1 SD), the v=0 force mildly cut (−7/−11 %), completion cut as a parallel down-shift.
   ⇒ V₀ ≈ 16 is a **static/geometry+kinetics calibration** (stroke size / duty / the rigid-clamp residual), not a
   missing strain-dependent completion state.
3. ~~Existing stroke already has substantial load-dependent completion.~~ **NOT observed** — the existing stroke
   completes fully at every load (PART 1), so there is nothing to duplicate; but there is also no partial-
   completion to strengthen into a lever.

**⇒ Load-dependent stroke completion does NOT move V₀'s zero (unchanged within seed noise); it is the amplitude
null (mildly worsening the density problem), and it only degrades the unloaded stroke. No build.**
Consistent with `PRESTROKE_DISTORTION_ANALYSIS.md` (the ceiling is a uniform productive-tail collapse, no gate-able
sub-population) and `FORCE_VELOCITY_TEST.md`/`FORCE_BALANCE_CLOSURE.md` (an intrinsic, density-independent V₀ ≈ 16
that is a ~1.8× static over-drive). If Vmax calibration is pursued, the lever is the **static stroke
size / duty / rigid-clamp geometry**, not a velocity-gated completion step. The Option-4 converter re-architecture
is NOT licensed by this outcome (it would be needed only to *test* a lever this measurement shows is the null).

---

## Artifacts
- Code (default-off byte-identical): `-strokeload` (PART-1 quasi-static probe, `runStrokeLoad`), `-strokecomp E*`
  (PART-2 modifier via `CrossBridgeSystem.directedSwingComp`), and the always-on read-only converter-completion
  accumulator + `CMPLROW` in `runForceVelocity` (FVROW unchanged).
- Runs: `RUN_LOGS/2026-07-11_strokecomp_sweep.txt` (canonical + E*=1/2/4, v∈[0,18]),
  `RUN_LOGS/2026-07-11_strokecomp_seeds.txt` (multi-seed V₀ + v=0 amplitude confirmation). Analysis:
  `RUN_LOGS/strokecomp_analyze.py`. Sweep: `scripts/run_strokecomp_sweep.sh` / `run_strokecomp_seeds.sh`.
- Commands:
```
scripts/run_gliding.sh -strokeload -density 5 -seed 0 4000                    # PART 1 quasi-static (completion vs Δ)
scripts/run_gliding.sh -matbox 50 -density 2000 -vclamp 8 -seed 0 10000        # PART 1 sliding completion + PART 2 canonical f̄(8)
scripts/run_gliding.sh -matbox 50 -density 2000 -vclamp 8 -strokecomp 2 -seed 0 10000   # PART 2 modifier point
```
- Constraints honored: measurement-only, **no gate/canonical build**; the modifier is the SMALLEST knob on the
  EXISTING stroke (no new converter frame); **zero-movement distinguished from amplitude-scaling explicitly**
  (v=0 amplitude ratios + V₀ + parallel-vs-steepened completion); existing load-dependence checked FIRST (PART 1);
  low-load unitary/stroke in-spec checked; single-motor CPU deterministic; velocity clamp for the V₀ read;
  `f̄_available` primary; `BoA-v1ref` untouched.
</content>
