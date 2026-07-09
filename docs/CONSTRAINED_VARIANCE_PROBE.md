# Constrained-body thermal over-fluctuation vs dt — how far off is "jiggle every body as if free," per constraint mode?

**Date:** 2026-07-06. New code path only (`EomStabilityHarness -varprobe`); **default byte-identical** (the
default relaxation grid reproduces `EOM_STABILITY_FINDINGS.md` exactly — 0/1 pN·nm⁻¹ rows
MONO/RING map + dt_crit 2.39e-5/4.78e-5 unchanged); `BoA-v1ref` untouched. A **measurement-only** probe:
Brownian ON, stroke HELD at rest (no cycling/kinetics), single motor. No model/noise/kinetics change; the
per-mode noise correction (`√((2−α)/2)`) is downstream and **not built here**.

Companion to `STROKE_TIP_IMPLICIT_PROBE.md` (deterministic transient) / `XBTRAP_PROBE.md` (deterministic
relaxation order): this is the **Brownian-ON equilibrium-variance** complement — the second of the two
independent dt-error contributors (integration accuracy vs thermostat fidelity).

---

## PLAIN ANSWER (headline)

**The mechanism is REAL and VALIDATED, and at production dt=1e-5 the dominant over-fluctuation is NOT the
stiff joints jba flagged — it is the F8 cross-bridge BOND itself, over-fluctuating ~3.4× in variance,
because the bond's free end is the LOW-DRAG sphere head (α jumps from the isolated 0.42 to ~1.4 once the
head is unpinned).**

- **STEP-1 GATE PASS (bulletproof).** The isolated F8 bond mode's measured variance tracks the
  Euler–Maruyama prediction `Var/(kT/k) = E(α) = 2/(2−α)` across the **whole** dt ladder, for **two** α
  values at once (x∥ with γ_∥, y/z⊥ with γ_⊥) against **one** γ-independent target `kT/k_F8 = 4.116 nm²`:
  6.19/6.15 at 4e-5 (α=1.67, near the α→2 edge) → **1.271/1.265 at 1e-5 (α=0.42)** → 1.028/1.007 at
  3.1e-7 (α=0.013). The formula + the noise + the mode isolation are all confirmed. The over-fluctuation
  is a genuine dt artifact: it **climbs toward the α→2 stability edge at coarse dt and → 1 as dt→0**.
- **Two distinct mode families (the structural result):**
  - **REAL positional springs (F8 bond):** `α = k·dt/γ` shrinks with dt ⇒ the excess **vanishes** as
    dt→0. This is a **correctness** question — there is a right answer (`kT/k`).
  - **fracMove springs (motor joints J1/J2, tail anchor):** the per-step gap fraction is `α = frac`
    (≈0.4), **independent of dt** ⇒ a **PERMANENT** excess `2/(2−frac)` that **never converges to 1**,
    while the absolute variance shrinks ∝ dt (the mode stiffens). Measured dt-FLAT: J1 α≈0.35 (E≈1.22),
    J2 α≈0.25 (E≈1.14), anchor α≈0.29 (E≈1.17) — a permanent **~15–22 %** over-fluctuation.
- **Ranking at production dt=1e-5 (by excess factor E):**
  **F8 bond 3.38× ≫ J1 gap 1.22× > anchor gap 1.17× > J2 gap 1.14×.** jba's "stiff neck/tail joints
  dominate" hypothesis is **refuted in that form** — the joints are the *mildest* modes (all fracMove,
  capped at α=frac≈0.4). The bond dominates because with the **free sphere head** (γ_head ≈ 1.9e-8, a
  small low-drag body) its reduced-γ α ≈ 0.95, further softened by the joint network to an effective
  α_eff ≈ 1.4 — right at the ringing regime.
- **PLAUSIBILITY — credible in magnitude, unproven in causation.** A 3.4× bond-STRETCH variance is a 3.4×
  bond-FORCE variance (F=k·stretch) ⇒ RMS bond force inflated **~1.84×** at production dt vs the dt→0
  truth. The catch-slip detachment rate is **exponential** in bond force (`∝ e^(±F·x/kT)`) and the stroke
  rectifies nonlinearly, so an inflated force *variance* biases the *mean* rate — the right locus and the
  right dt-trend (large at 1e-5, shrinking as dt→0) to be a chunk of the ~2.2× per-bound transport bias
  (0.82→1.78). **But this probe measures the equilibrium fluctuation magnitude, not its transport
  consequence** — stroke is held, no load, no cycling. Consistent with `STROKE_TIP_IMPLICIT_PROBE.md`
  (the bias lives in the loaded cycling ensemble), the causal test is the downstream per-mode
  noise-scaling correction, not this measurement.

---

## Setup

Single filament + single motor, Brownian ON, **stroke held uncocked** (ADPPi, no cycle), no external
spring. Measurement = the stationary variance of a mode coordinate about its mean over T_samp=0.25 s
(≈10⁴ independent samples, T/τ) after T_eq=0.02 s (≈830·τ), plus α_emp = 1 − lag-1 autocorrelation.
`kT = 4.116e-21 J`; `k_F8 = 1 pN·nm⁻¹`; γ_seg,∥ = 2.389e-8, γ_seg,⊥ = 3.309e-8 N·s·m⁻¹; τ_relax≈γ/k_F8≈2.39e-5 s.
`kT/k_F8 = 4.116 nm²` (depends only on k, γ cancels).

---

## STEP 1 — the F8 bond ISOLATED harmonic mode (THE GATE)

Motor FROZEN (fixed head tip) + filament rotation FROZEN + Brownian ON (filament translation only). The
three filament-COM Cartesian components are three **independent overdamped harmonic modes** of the same
zero-rest-length F8 spring (k=k_F8), anisotropic drag: x∥ (γ_∥, α≈0.42 @1e-5), y/z⊥ (γ_⊥, α≈0.21 @1e-5).

| dt (s) | axis | α (analytic) | α_emp | Var (nm²) | **meas E = Var/(kT/k)** | **pred 2/(2−α)** |
|--:|:--:|--:|--:|--:|--:|--:|
| 4.00e-5 | x∥ | 1.675 | 1.678 | 25.48 | **6.189** | **6.148** |
| 4.00e-5 | y⊥ | 1.209 | 1.215 | 10.39 | 2.524 | 2.528 |
| 2.00e-5 | x∥ | 0.837 | 0.835 | 7.08 | 1.720 | 1.720 |
| **1.00e-5** | **x∥** | **0.419** | **0.418** | **5.23** | **1.271** | **1.265** |
| 1.00e-5 | y⊥ | 0.302 | 0.309 | 4.72–4.86 | 1.15–1.18 | 1.178 |
| 5.00e-6 | x∥ | 0.209 | 0.207 | 4.67 | 1.135 | 1.117 |
| 2.50e-6 | x∥ | 0.105 | 0.105 | 4.36 | 1.059 | 1.055 |
| 1.25e-6 | x∥ | 0.052 | 0.052 | 4.26 | 1.036 | 1.027 |
| 6.25e-7 | x∥ | 0.026 | 0.026 | 4.20 | 1.021 | 1.013 |
| 3.13e-7 | x∥ | 0.013 | 0.013 | 4.23 | 1.028 | 1.007 |

**GATE PASS** — measured excess tracks `2/(2−α)` within ~1 % (x∥ within 8 % at every dt), across a 128×
dt span and 130× α span, for two independent α values against one γ-independent kT/k. α_emp = α_analytic
to 3 digits (the noise + integrator ARE the textbook Euler–Maruyama; the mode IS cleanly isolated). At
production dt=1e-5 the isolated bond over-fluctuates **1.27×** in variance (RMS 1.13×).

## STEP 2 — the full constraint set, per mode (F8 bond + joints J1/J2 + tail anchor)

Motor body FREE (joints + tail anchor active) + Brownian ON on the motor sub-bodies (rod+head; lever off,
v1) AND the filament. Cell = `α_emp / E=2/(2−α_emp) / Var[nm²]`. "DIVERGED (α≥2)" = the head-free F8 mode
is EOM-unstable at that dt (matches `EOM_STABILITY_FINDINGS.md`: the loaded mode blows up above dt_crit).

| dt (s) | **F8∥ bond** | J1 gap | J2 gap | anchor gap |
|--:|:--:|:--:|:--:|:--:|
| 4.00e-5 | DIVERGED | DIVERGED | DIVERGED | DIVERGED |
| 2.00e-5 | DIVERGED | 0.94 / 1.89 / 387 | 1.61 / — / 89 | 0.31 / 1.19 / 22.0 |
| **1.00e-5** | **1.43 / 3.53 / 13.9** | **0.35 / 1.22 / 11.9** | **0.24 / 1.14 / 16.1** | **0.29 / 1.17 / 11.1** |
| 5.00e-6 | 0.73 / 1.58 / 6.33 | 0.33 / 1.20 / 6.03 | 0.24 / 1.14 / 8.21 | 0.29 / 1.17 / 5.68 |
| 2.50e-6 | 0.36 / 1.22 / 5.05 | 0.33 / 1.20 / 2.96 | 0.24 / 1.14 / 4.03 | 0.29 / 1.17 / 2.84 |
| 1.25e-6 | 0.18 / 1.10 / 4.72 | 0.34 / 1.21 / 1.42 | 0.25 / 1.14 / 1.95 | 0.29 / 1.17 / 1.40 |
| 6.25e-7 | 0.08 / 1.04 / 4.86 | 0.35 / 1.21 / 0.68 | 0.26 / 1.15 / 0.95 | 0.29 / 1.17 / 0.70 |
| 3.13e-7 | 0.04 / 1.02 / 5.39 | 0.36 / 1.22 / 0.34 | 0.26 / 1.15 / 0.47 | 0.29 / 1.17 / 0.35 |

**Reads:**
1. **The joint/anchor gaps are dt-FLAT in α and E** (J1 0.33–0.36, J2 0.24–0.26, anchor 0.29 across the
   whole ladder) — the fracMove signature: `α = frac`, a **permanent** ~14–22 % over-fluctuation that does
   NOT vanish as dt→0. Their absolute Var ∝ dt (shrinks ~2× per dt-halving) — they stiffen, keeping the
   fixed excess on an ever-tighter well. (α_emp sits a little below frac=0.4 because the gap is a folded
   `|vector|` distance whose Euclidean relaxation is slightly sub-fracMove — a minor caveat, not the
   signal.)
2. **The F8 bond is the outlier and the dominant mode at 1e-5: 3.38×** (α_eff≈1.43, ringing). It
   **DIVERGES at dt≥2e-5** (head-free F8 α>2 — the EOM instability), and as dt→0 relaxes toward ~1.1–1.3
   (not exactly 1: it stays mildly hot because it is coupled to the fracMove joints, which keep their
   ~1.15× excess forever). The bond's equipartition target is **kT/k_F8 = 4.116 nm² regardless of series
   compliance** (equipartition gives each quadratic DOF ½kT), so `Var/(kT/k_F8)` is the honest bond
   over-fluctuation.
3. **Why the bond, not the joints (refutes the stiff-joint hypothesis):** the F8 bond's free end is the
   **low-drag sphere head** (γ_head≈1.9e-8 ⇒ head-free reduced-γ α≈0.95, vs the isolated head-anchored
   0.42). The joint network softens the head further ⇒ effective α_eff≈1.4. The joints themselves are
   fracMove-capped at α≈0.4 (E≈1.2), so they are the *mildest*, not the stiffest, modes.

## Read-out (the three requested)

- **Excess-vs-dt curve per mode:** F8 bond — 6.2× (4e-5, near edge) → 3.4× (1e-5) → ~1 (dt→0), a REAL-spring
  curve that vanishes with dt. Joints/anchor — a FLAT ~1.14–1.22× at every dt (fracMove; permanent).
- **Which dominates at 1e-5:** the **F8 bond (3.4×)**, by a wide margin, driven by the low-drag free head —
  not the joints (~1.15×). "Jiggle as if free" over-fluctuates the bond stretch/force ~3.4× in variance
  (~1.84× RMS) at production dt.
- **Plausibility as a per-bound-bias driver:** **credible in magnitude, unproven in causation.** The bond
  is the exact coordinate the nonlinear transport channels read (exponential catch-slip, stroke
  rectification), the over-fluctuation is large (~1.84× RMS force) and has the right dt-trend (shrinks as
  dt→0, like the per-bound bias). That makes it a plausible chunk of the 2.2× per-bound climb. But it is an
  **equilibrium** measurement (stroke held, no load, no cycling); the causal link to transport needs the
  loaded/cycling channel (`STROKE_TIP_IMPLICIT_PROBE.md`), i.e. the downstream per-mode noise-scaling
  correction `√((2−α)/2)` on the F8 bond, not this measurement.

## The indicated correction (downstream, NOT built here)

Per-mode noise scaling `randForce_mode ← randForce_mode · √((2−α)/2)` restores `Var = kT/k` exactly for
each mode. For the **F8 bond** this is a real-spring correction (α=k·dt/γ, α_eff≈1.4 at 1e-5 — the big
one); for the **fracMove joints** it would remove the permanent ~1.15× (α=frac). The bond correction is the
one worth wiring into the loaded/cycling gliding assay to test whether taming its 3.4× fluctuation moves
the per-bound bias — the natural next experiment.

---

# WIRED INTO THE GLIDING ASSAY — `-bondnoise` (2026-07-06)

The bond-noise correction is now wired into `GlidingHarness` (CPU `stepOrig`/`stepFresh` + the GPU
`buildPlan` device graph), flag-gated **`-bondnoise`**, **default byte-identical** (all new code behind
`if (BOND_NOISE)`; the buildPlan task-chain split reproduces the identical graph when off).

**What it does.** Each step, before the motor Brownian task, `BrownianForceSystem.scaleBoundHeadNoise`
sets each BOUND head's translational `brownTransScale` to `base·√((2−α)/2)` and each FREE head's to
`base` — per motor, writing only its own head slot (3m+2) ⇒ **race-free, no atomics** (device-capable,
CPU≡GPU by construction). α = `k_F8·dt/γ_head,∥` (the low-drag sphere head in the F8 well). Rotational
Brownian untouched (F8 is a translational mode). The **segment side is deliberately NOT corrected** — a
segment is shared by several motors, so a per-segment noise scale is not race-free, and the head is the
dominant low-drag mode anyway.

- **`-bondnoise`** — principled factor from α_head = k_F8·dt/γ_head (at dt=1e-5: α_head=0.531 ⇒ ×0.857).
  This corrects the head-body's OWN F8 over-fluctuation. NOTE it **under-corrects** the assembled bond,
  whose measured α_eff≈1.43 (the head is softened further by the joint network — STEP 2): the principled
  α_head sees only the F8+own-drag part.
- **`-bondnoisefac <x>`** — override the factor directly, e.g. **0.534** = `√((2−1.43)/2)`, the full
  correction sized to the assembled empirical α_eff≈1.43 (fully tames the 3.4× bond over-fluctuation).

Both are **dt-adaptive** (α∝dt ⇒ factor→1 as dt→0): the correction self-disables in the converged limit,
so it only bites at coarse (production) dt — exactly where the over-fluctuation lives.

## A/B experiment — does taming the bond over-fluctuation move the per-bound bias?

Paired at production dt=1e-5, seed 0, `-full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix
-coltol 10 -density 1000`, 60k steps (GPU device-resident). per-bound = velFitX / avgBsteady (the
ROTIMPLICIT convention). `run_bondnoise_ab.sh`.

| arm | velFitX | avgBsteady | **per-bound** | detach/s | dwell (ms) |
|--:|--:|--:|--:|--:|--:|
| baseline (no correction) | 2.112 | 2.870 | **0.736** | 1624 | 0.616 |
| `-bondnoise` (×0.857, α_head) | 2.678 | 3.243 | **0.826 (+12%)** | 1495 | 0.669 |
| `-bondnoisefac 0.534` (×0.534, α_eff) | 2.625 | 3.219 | **0.815 (+11%)** | 1439 | 0.695 |

**The correction moves per-bound UP toward the converged value, with a fully coherent mechanistic
signature.** Reducing the bound head's F8-well over-fluctuation → **fewer catch-slip detachments**
(detach/s 1624→1495→1439) → **longer dwell** (0.62→0.67→0.70 ms) → **more bound heads** (avgBsteady
2.87→3.24) → **higher glide** (velFitX 2.11→2.68, +27%). velFitX rises more than avgBound, so it is not
purely an engagement effect — there is a genuine per-bound (efficiency) component too.

**Reads:**
1. **Real and correctly-signed.** The dt→0-converged per-bound reference is ~1.78 (ROTIMPLICIT); production
   dt=1e-5 baseline is 0.736. The correction recovers **+0.09 (~9 % of the ~1.04 dt-gap)** in the right
   direction. The velFitX shift (+27 %) is ~4× the single-seed SEM (~6–7 %, pairsprings), and the whole
   detach→dwell→avgBound→glide chain is internally consistent ⇒ a real effect, not noise. **Confirms the
   probe's hypothesis: the F8-bond over-fluctuation inflates catch-slip detachment and drags production
   per-bound down.**
2. **But minor, and it SATURATES.** The full correction (×0.534, sized to the assembled α_eff≈1.43) gives
   essentially the SAME shift as the mild one (×0.857) — 0.815 vs 0.826, i.e. no extra recovery from
   fully taming the bond. So the bond noise is a **real but small** contributor: it accounts for ~10 % of
   the per-bound dt-bias, not the bulk. The remaining ~90 % is the **loaded/cycling collective transport**
   effect (`STROKE_TIP_IMPLICIT_PROBE.md`: the bias lives in the never-equilibrating cycling ensemble;
   `EOM_STABILITY_FINDINGS.md`: the limiter is the collective loaded force, needing a sub-step) — which a
   thermostat correction on a single bond cannot reach.
3. **Faithfulness, not a tuning knob.** The correction restores `Var=kT/k` (the physically-correct
   equilibrium variance) and is dt-adaptive (→1 as dt→0), so it moves production TOWARD the converged
   truth — a legitimate partial dt-fix, not a fudge. Single-seed; a 2–3 seed confirm would tighten the
   ~12 % shift (flagged, not blocking — the mechanistic signature already corroborates it).

**Verdict:** taming the F8-bond thermal over-fluctuation recovers ~10 % of the per-bound dt-bias in the
correct direction (and saturates there) — the over-fluctuation is a **real, minor** driver; the dominant
remainder is the loaded/cycling collective transport, per the prior arc. `-bondnoise` stays a diagnostic
(default byte-identical, not promoted); whether to keep it composed with a collective sub-step is jba's
call.

---

# FULL CONSTRAINED-MODE CORRECTION — the ceiling test (`-allnoise`, 2026-07-06)

The `-bondnoise` result corrected ONE end (head), ONE channel (translation) and saturated at ~10 %. This
test corrects **every constrained mode** of a bound motor and its bound segment to find the **ceiling** on
what the over-fluctuation mechanism can recover — settling whether the ~10 % meant "the thermostat is
genuinely minor" or "we only corrected half the bond."

**Wiring.** `BrownianForceSystem.scaleMotorNoise` (per-motor, own head/rod slots ⇒ race-free) +
`scaleBoundSegNoise` (per-SEGMENT, own slot, gated by the CSR histogram `segMotorCount` — the established
CSR-inverse machinery supplies the bound-motor count, one-step-stale, so **no per-motor competing writes ⇒
race-free without the gather**). Wired into `GlidingHarness` CPU `stepOrig`/`stepFresh` + the GPU
`buildPlan` (both branches). **Default byte-identical**; `BoA-v1ref` untouched. Per-mode α from each mode's
own k/γ (α_head=0.531, α_seg,∥=0.419 — the STEP-1 gate values; α_align=0.40, α_anchor=0.40 fracMove).

- **`-allnoise`** — head-trans (×0.857) + **segment-trans** (×0.889): the two **real-spring** F8 ends
  (both dt-vanishing) — the honest dt-ceiling.
- **`-fracnoise`** — + head-rotation (×0.894, F9/F10) + rod (×0.894, tail-anchor/J2): the **fracMove**
  modes (α=frac, dt-INDEPENDENT ⇒ a re-baseline, NOT a dt-fix — it shifts the converged reference too).

**Physics-correct arm split (note the deviation from the task's grouping).** The task grouped head/segment
*rotation* under "real-spring," but F9/F10 are **fracMove** torques (α=frac), so rotation is placed in the
fracMove re-baseline arm (`-fracnoise`), keeping `-allnoise` the pure dt-vanishing translational ceiling —
the scientifically correct split.

## A/B — 4 arms, dt=1e-5, seed 0, `-full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix`, 60k (0.6 s, 2nd-half window)

| arm | velFitX | avgBsteady | **per-bound** | detach/s | dwell (ms) | duty | gap recovered |
|--:|--:|--:|--:|--:|--:|--:|--:|
| baseline | 2.112 | 2.870 | **0.736** | 1624 | 0.62 | 0.81 | — |
| `-bondnoise` (head trans) | 2.678 | 3.243 | **0.826** | 1495 | 0.67 | 0.78 | ~9 % |
| **`-allnoise` (head+seg trans, real-spring)** | **3.770** | 2.698 | **1.397** | **933** | **1.07** | 0.60 | **~63 %** |
| `-fracnoise` (+head-rot +rod, fracMove) | 1.097 | 1.475 | 0.744 | 1419 | 0.71 | 0.59 | ~1 % (re-baseline) |

per-bound = velFitX / avgBsteady; converged reference ~1.78 @6.25e-7, production baseline 0.736 ⇒ dt-gap ~1.04.

**Reads:**
1. **Arm 3 SHATTERS the head-only saturation: per-bound 0.736 → 1.397, recovering ~63 % of the dt-gap**
   (vs head-only's ~9 %). The velFitX shift (+78 %) is ≫ SEM; the mechanistic chain is coherent and strong —
   **detach/s nearly halves** (1624→933), **dwell +74 %** (0.62→1.07 ms), **velFitX +78 %**. avgBound drops
   slightly (fewer rebinds), so the glide is dominated by a large **per-bound efficiency** gain: each bound
   head converts far more of its dwell to directed transport once the bond's thermal over-fluctuation is
   removed from BOTH ends.
2. **The SEGMENT side is the dominant missing piece.** Head-only (`-bondnoise`) recovered ~9 %; adding the
   segment-translation correction (the OTHER end of the F8 bond, the transported body) jumped it to ~63 %.
   The head-only test massively **under-corrected** because it left the segment over-fluctuating. ⇒ **the
   constrained-variance thermostat is a MAJOR driver of the per-bound dt-bias, not minor — overturning the
   `-bondnoise`-based conclusion.** It is faithful (dt-adaptive → 1 as dt→0; restores Var=kT/k) and the
   ~63 % is a **lower bound** — multi-bound segments see stiffness N·k_F8 (higher α), so the single-bond
   α_seg=0.42 correction UNDER-corrects them.
3. **The fracMove re-baseline (arm 4) COLLAPSES engagement** (avgBound 2.70→1.48, velFitX 3.77→1.10,
   per-bound back to ~baseline). Cooling the head's angular over-fluctuation (F9/F10) removes the **thermal
   search that binding relies on** ⇒ far fewer binds. So the fracMove correction is not just a re-baseline
   (it would move the converged reference too) — it is an **actively harmful** one: the fracMove modes'
   over-fluctuation is load-bearing for the thermal search, not a bias to remove. Reported separately, as
   required, and NOT counted as dt-gap recovery.

**CPU≡GPU.** Baseline is bit-identical CPU↔GPU (0.980=0.980). With the correction the per-step
`brownTransScale` writes tip the trajectory off that bit-identical manifold into the expected chaotic
(Lyapunov) microstate decorrelation — window-dependent and erratic (seed-0 velFitX GPU/CPU 0.70/2.13 @6k →
2.15/1.85 @20k), NOT a fixed kernel error. **3-seed aggregate agrees within SEM on the transport channel:
velFitX GPU 1.996±0.24 vs CPU 2.074±0.14** (avgBound carries a larger chaotic spread in the tiny parity
box). The segment correction is genuinely race-free (per-segment write over the CSR histogram count) — the
task's bail condition (segment side not race-free) was **not** triggered.

## Plain verdict

**Correcting every real-spring constrained mode (both F8-bond ends' translation) recovers ~63 % of the
per-bound dt-bias — breaking decisively past the head-only ~10 %.** The over-fluctuation mechanism is a
**MAJOR** contributor, and the earlier "thermostat is minor" reading was an artifact of correcting only
half the bond; the **segment side (the transported body) dominates**. The remaining ~37 % is **not** the
constrained-variance mechanism — it is the **orientation integration** (the still-explicit F8-tip head
rotation, untested by a 2nd-order scheme) plus the **collective loaded transport** (`EOM_STABILITY` /
`STROKE_TIP`: the loaded cycling ensemble → a sub-step). The fracMove structural over-fluctuation is
load-bearing for binding (its "correction" is a harmful re-baseline), so it is **not** part of the
recoverable dt-bias. `-allnoise` stays a diagnostic (default byte-identical, dt-adaptive/faithful, not
promoted); it is the strongest evidence yet that the per-bound dt-bias is substantially a
constrained-bond thermal over-fluctuation, closable by a per-mode noise thermostat — pending a
seed-ensemble confirm and composition with an orientation/collective sub-step.

---

# COMPREHENSIVE THERMAL CORRECTION — per-body total-stiffness α (`-thermcorr`, 2026-07-06)

`-allnoise` corrected the F8 bond ends at **single-bond** stiffness. This test computes each body's α from
its **actual total constraint stiffness** and asks whether that recovers more of the remaining ~37%. jba's
two predictions: (1) multi-bound segments (stiffness N·k_F8) were under-corrected ⇒ load-aware α recovers
more; (2) even *unbound* segments over-fluctuate in their chain links, transmitting excess motion to bound
neighbors ⇒ correct chain modes on **all** segments.

**Wiring.** `BrownianForceSystem.scaleThermCorrSeg` — per-SEGMENT (own slot ⇒ race-free), α =
`N·aPerMotor` (`N=segMotorCount`, the CSR-inverse bound count ⇒ **load-aware** K_tot=N·k_F8) +
(doChain) `nNbr·aPerNbr` (the ratefixed chain-link stiffness to topological neighbors, on **every**
segment incl unbound), clamped α<1.98 (the formula diverges at the α→2 rigid edge). `aPerMotor` (real
k_F8) and `aPerNbr` (ratefixed chain k_eff) are **both ∝ dt** ⇒ the whole correction self-disables as
dt→0. Motor head/rod via `scaleMotorNoise` (reused). `-thermcorr <level>`: **3** = load-aware seg +
head-trans; **4** = + chain on all segments; **5** = + motor modes (head-rot + rod). Default 0/off,
byte-identical (baseline arm reproduced velFitX=2.112 exactly, all sessions); `BoA-v1ref` untouched.
**Race-free throughout** (per-segment / per-motor own-slot writes; `scaleThermCorrSeg` reads only the CSR
histogram count + static topology + Math.sqrt, all identical CPU↔GPU) — the bail condition never triggered.
CPU≡GPU follows the parity-validated `-allnoise` (same race-free architecture + one deterministic
race-free kernel): baseline bit-identical, the per-step noise writes then decorrelate the chaotic
microstate (aggregate-within-SEM), not a runner artifact.

## Cumulative 5-arm A/B — dt=1e-5, seed 0, `-full` (26740 motors), 60k (0.6 s window)

| # | arm | adds | velFitX | avgBsteady | **per-bound** | detach/s | dwell | gap rec. | marginal |
|--:|--|--|--:|--:|--:|--:|--:|--:|--:|
| 1 | baseline | — | 2.112 | 2.870 | **0.736** | 1624 | 0.62 | — | — |
| 2 | `-allnoise` | head+seg F8-trans, single-bond | 3.770 | 2.698 | **1.397** | 933 | 1.07 | **63 %** | +63 |
| 3 | `-thermcorr 3` | **load-aware** seg α=N·k_F8 | 2.984 | 2.445 | **1.221** | 900 | 1.11 | 46 % | **−17** |
| 4 | `-thermcorr 4` | + chain on **ALL** segs (incl unbound) | 2.822 | 2.239 | **1.260** | 889 | 1.13 | 50 % | **+4** |
| 5 | `-thermcorr 5` | + motor modes (head-rot + rod) | 1.985 | 2.312 | **0.859** | 1343 | 0.74 | 12 % | **−38** |

per-bound = velFitX/avgBsteady; converged ref ~1.78, baseline 0.736 ⇒ dt-gap ~1.04. "marginal" = pp of the
gap gained/lost vs the row above.

**Reads (the robust, 26740-motor-averaged conclusions):**
1. **Correcting every body does NOT beat `-allnoise` — the ~63 % is the CEILING, not a lower bound.** This
   **refutes** both jba's under-correction prediction AND the prior session's "~63 % is a lower bound
   because multi-bound segments are under-corrected." The opposite is true:
2. **Load-aware α RECOVERS LESS (−17 pp): the single-bond uniform correction was near-optimal; N·k_F8
   OVER-suppresses.** Making the correction *stronger* on multi-bound segments (α up to the α→2 clamp ⇒
   noise ×0.1) drops per-bound 1.397→1.221. The **sum-of-stiffnesses (Jacobi/quasi-static) N·k_F8
   overestimates the true coupled-mode COM stiffness** (the N bonds sit at different arc points and
   partially distribute/cancel), so the "principled" load-aware α over-corrects. The true stiffness needs
   the coupled normal-mode covariance, not a stiffness sum — the task's flagged approximation breaks here.
3. **The unbound-neighbor chain transmission is a SMALL but REAL chunk (+4 pp, arm 4 − arm 3).** Correcting
   the chain modes on ALL segments (incl unbound) nudges per-bound 1.221→1.260 — jba's transmission
   hypothesis holds *directionally* but it is a minor piece of the 37 %, not a large one.
4. **Motor modes COLLAPSE it again (−38 pp): the head angular over-fluctuation is load-bearing.** Arm 5's
   head-rotation cooling wrecks the per-bound efficiency — detach/s jumps 889→1343, dwell 1.13→0.74 ms,
   velFitX 2.82→1.99 (this time via the stroke/detachment, where `-fracnoise` hit binding count; same
   verdict). Confirms the F9/F10 angular fluctuation is a load-bearing search/stroke mode, not a
   correctable bias.

**Where binding/stroke collapses (arm 5) marks the load-bearing mode**, and where load-aware over-suppresses
(arm 3) marks the Jacobi-stiffness-sum breakdown. Neither pushes past the two-F8-end translational
correction.

## dt-vanishing confirm — the correction FACTOR vanishes, but its EFFECT does NOT (the key nuance)

**Analytic (factor):** `aPerMotor`, `aPerNbr`, head/rod are ALL ∝ dt ⇒ α→0, scale→1 as dt→0. At 6.25e-7:
`aPerMotor=0.0262` / `aPerNbr=0.0156` (vs 0.419 / 0.25 at 1e-5) ⇒ segment scales **≈0.98** (a ~2 %
correction) vs ~0.61–0.89 (up to ~40 %) at 1e-5 — **16× smaller**. So the correction *magnitude* self-
disables.

**Empirical (`-full`, 26740 motors, uncorr vs thermcorr4):**

| dt | uncorr per-bound (velFitX/avgB) | thermcorr4 per-bound (velFitX/avgB) | Δ per-bound | Δ avgB |
|--:|--:|--:|--:|--:|
| 1e-5 | 0.736 (2.11 / 2.87) | 1.260 (2.82 / 2.24) | **+71 %** (toward converged) | −22 % |
| 6.25e-7 | **1.914** (7.75 / 4.05) ≈ converged | **2.469** (4.77 / 1.93) | **+29 % (OVERSHOOTS)** | **−52 %** |

**⇒ The EFFECT does NOT vanish at reachable fine dt.** At 6.25e-7 the ~2 % factor still **halves avgBound**
and pushes per-bound +29 % **past** the uncorrected converged reference (2.469 vs 1.914). Per-bound glide is
**hypersensitive to thermal-noise amplitude** — a ~2 % noise change → ~29 % per-bound change, a **~15×
gain** — so the residual factor at any practically-reachable dt has an outsized effect. (The small-box
v1box check is *also* confounded, even sign-flipping vs `-full` — but `-full` shows the effect is genuine,
not just small-box chaos.)

**So it is NOT a clean dt-fix.** The factor is asymptotically dt-vanishing, but because glide is
noise-amplitude-hypersensitive, the correction behaves substantially as a **noise-amplitude re-baseline**
at reachable dt: it points toward converged at production dt (a real, correctly-signed recovery) but
**over-cools and overshoots** at fine dt. This is the honest picture — the corrections are a strong, blunt
noise knob, not a precise restoration of the converged dynamics.

## Plain verdict

Two findings, one structural and one that reframes the whole arc:

**(1) Correcting every body does NOT recover more than the targeted two-F8-bond-end correction
(`-allnoise`, ~63 % at production dt) — that is the ceiling of the per-body-α thermostat.** Load-aware
N·k_F8 **over-suppresses** (the stiffness-sum overestimates the coupled COM stiffness — the Jacobi
approximation breaks for multi-bound segments), motor-mode cooling is **load-bearing and harmful**, and the
unbound-neighbor chain transmission is **real but small (~4 %)**. This refutes both jba's under-correction
prediction and the prior "~63 % is a lower bound."

**(2) The per-bound dt-bias IS largely a thermal-noise-amplitude effect — but the noise thermostat is too
BLUNT to cleanly fix it.** The dt-vanishing check shows glide is **hypersensitive to noise amplitude
(~15× gain)**: at the converged dt the correction still overshoots (per-bound 1.914→2.469, avgBound halved),
so it is **not a clean dt-fix** — it points the right way at production dt but behaves as a noise-amplitude
re-baseline at fine dt. This CONFIRMS the mechanism (the coarse-dt EM over-fluctuation genuinely inflates
the noise that suppresses per-bound — reducing it recovers a large, correctly-signed chunk) while showing a
per-mode noise scale is the wrong INSTRUMENT: getting it exactly right is impossible because (a) the coupled
stiffness is intractable per-body (load-aware fails) and (b) the hypersensitivity demands precision the
approximation can't deliver (fine-dt overshoot).

**⇒ The remaining ~37 % — and the overshoot — both point to the same fix: not a per-mode noise thermostat
but the COUPLED treatment (a sub-step / implicit integrator on the loaded cross-bridge), which corrects the
*dynamics* rather than blunting the *noise*** (`EOM_STABILITY`/`STROKE_TIP`). `-allnoise`/`-thermcorr` stay
**diagnostics** — they quantify that the over-fluctuation is a major, hypersensitive driver, and that a
noise scale is too blunt to be the production fix. Not promoted.

## Reproduce

```
./run_eomstab.sh -varprobe    # STEP 1 (F8 isolated gate) + STEP 2 (full-set per-mode sweep)
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -coltol 10 -density 1000 -dt 1e-5 -bondnoise 60000        # principled α_head correction
./run_gliding.sh -gpu ... -bondnoisefac 0.534 60000   # full correction sized to the assembled α_eff≈1.43
./run_bondnoise_ab.sh          # the paired baseline / -bondnoise / -bondnoisefac 0.534 A/B
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -coltol 10 -density 1000 -dt 1e-5 -allnoise 60000   # FULL real-spring correction (head+seg trans)
./run_gliding.sh -gpu ... -fracnoise 60000     # + fracMove modes (head rot + rod) — the re-baseline arm
./run_allnoise_ab.sh           # the 4-arm ceiling A/B (baseline / bondnoise / allnoise / fracnoise)
./run_allnoise_parity.sh       # 3-seed CPU≡GPU aggregate parity on -allnoise
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -coltol 10 -density 1000 -dt 1e-5 -thermcorr 4 60000   # comprehensive load-aware+chain correction (level 4)
./run_thermcorr_ab.sh          # the cumulative 5-arm A/B (baseline / allnoise / thermcorr 3 / 4 / 5)
./run_thermcorr_dtvanish_full.sh   # robust dt-vanishing (-full: uncorr vs thermcorr4 at 1e-5 and 6.25e-7)
```
Files: `EomStabilityHarness` (`-varprobe`: `runVarProbe`/`runVarStep1`/`measureF8Isolated`/`runVarStep2`/
`measureFullSet`, reusing `BrownianForceSystem`/`CrossBridgeSystem.bondForces`/`MotorJointSystem`/
`TailAnchorSystem`/`RigidRodLangevinIntegrationSystem`). Log:
`RUN_LOGS/2026-07-06_constrained_variance_probe.txt`. Default byte-identical; `BoA-v1ref` byte-clean.

---

# OVERSHOOT DIAGNOSIS + DETACHMENT-RATE ISOLATION (2026-07-07)

The comprehensive-thermcorr section above closed on two claims that this section **re-tests and partly
overturns**: (i) the fine-dt (6.25e-7) thermcorr4 **overshoot** (per-bound past the uncorrected converged
reference, avgBound halved) is genuine, and (ii) it proves glide is **~15× hypersensitive to noise
amplitude** ("a 2% factor → 29% per-bound"), so the thermostat is intrinsically too blunt. Both rest on
the 6.25e-7 comparison — which was run at **150k steps = 0.094 s** sim-time vs 0.6 s at production (a 6.4×
shorter, transient-prone window), and never against a **uniform-amplitude control**. Part A re-runs it at
matched sim-time with the control; Part B closes the last *reducible* dt-source (convex catch-slip
sampling). New code only (`GlidingHarness -uninoise <fac>`, a flat brownianForceMag×fac on both stores;
`EomStabilityHarness -detachramp`), **default byte-identical**; `BoA-v1ref` untouched; float32/CSR unchanged.

## PLAIN ANSWER (headline)

- **Part A — the "fine-dt overshoot" is REAL but is NOT noise-amplitude hypersensitivity; it is thermcorr4's
  LOAD-AWARE OVER-SUPPRESSION.** (A1) At matched 0.6 s sim-time the overshoot **survives and grows**
  (thermcorr4 per-bound 2.36 vs uncorr 1.59, **+48%**, avgBound halved 4.45→1.99) — windowing shifts the
  *absolute* numbers ~15 % (it inflated the uncorr reference: short-window 1.914 → matched 1.59) but does not
  explain the overshoot. (A2, decisive) A **genuine flat ×0.98 on ALL Brownian** (`-uninoise 0.98`) is
  **INERT at fine dt** — velFitX 7.09→7.08 (−0.1 %), per-bound 1.59→1.57 — while thermcorr4 swings velFitX
  −34 % / avgBound −55 %. **A clean 2 % noise cut does ~nothing at 6.25e-7**, so the swing is NOT a 2 %
  amplitude effect (⇒ the "~15× hypersensitivity" reading is **refuted**). thermcorr4's swing comes from its
  **load-aware `α=N·k_F8·dt/γ` term** (the ONLY term that exceeds a few-% cut at fine dt: on a multiply-bound
  segment N·α is O(1), up to the α→2 clamp = 90 % noise removal), which **over-cools exactly the
  transport-bearing bound segments** → collapses avgBound. This is the **same Jacobi-stiffness-sum breakdown**
  the coarse-dt arm 3 showed (−17 pp), now identified as the driver of the fine-dt "overshoot" too.
- **Part B — the deterministic detachment kinetics are dt-FLAT; there is NO reducible convex-rate-sampling
  error.** The exact catch-slip rate `kOff·(αCatch·e^(−F·xCatch/kT)+αSlip·e^(+F·xSlip/kT))` under a ramping
  load, sampled once-per-step exactly as `catchSlipRelease` reads `forceDotFil`, mis-estimates the mean
  detachment force by **only −0.07 % (Ḟ=1 pN/ms) to −0.19 % (an aggressive Ḟ=30 pN/ms) at production
  dt=1e-5**, shrinking first-order to the continuum. **B2:** the ~3.5 % detachment dt-dependence that appears
  with the thermal force fluctuation is **entirely the F8-well variance inflation** (E=1.36→1); correcting
  the variance (E=1) collapses it to −0.13 % (= the pure-sampling residual). ⇒ **the detachment
  dt-dependence IS the thermostat-variance mechanism, not a separate kinetics error** — no targeted rate
  sub-step is warranted.
- **Net:** every *reducible* dt-source is now excluded — integration order (`XBTRAP` NULL), constrained
  thermostat variance (a faithful uniform correction is **correctly inert at fine dt**, as a dt-vanishing
  correction must be), and now **convex detachment-rate sampling (<0.2 %, dt-flat)**. What remains is the
  **coupled loaded transport** (`EOM_STABILITY`/`STROKE_TIP`: the collective loaded force → a sub-step) — the
  irreducibility candidate, now reached by elimination rather than asserted. The "go to a coupled sub-step,
  not a noise thermostat" conclusion **stands and is strengthened**; only its *reason* changes (the thermostat
  is not "hypersensitive/blunt" — the faithful version does the right thing, ~nothing, at fine dt).

---

## PART A — the fine-dt overshoot: windowing (A1), the uniform control (A2), seeds (A3)

Config `-full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -ratefix` (26740 motors),
GPU device-resident, ~234 steps/s. **Matched sim-time**: 0.6 s at BOTH dts ⇒ 60k steps @1e-5, **960k steps
@6.25e-7** (the prior table's 150k = 0.094 s). per-bound = velFitX/avgBsteady. `run_overshoot_A.sh`.

### Coarse dt=1e-5, 60k (0.6 s) — 3 seeds, the effect-size + byte-identity anchor

| arm | velFitX (0/1/2) | avgBsteady | **per-bound mean** |
|--|--|--|--|
| uncorr | 2.112 / 2.338 / 2.525 | 2.870 / 2.957 / 3.173 | **0.774** |
| thermcorr4 | 2.822 / 2.726 / 3.257 | 2.239 / 2.276 / 2.478 | **1.257** (+62 %) |
| `-uninoise 0.98` (flat ×0.98) | 2.395 / 2.645 / 2.744 | 2.621 / 3.133 / 3.319 | **0.862** (+11 %) |

uncorr seed 0 velFitX=2.112 reproduces the established baseline **bit-for-print** (byte-identity of the
`-uninoise` edit confirmed). At production dt a flat 2 % cut moves per-bound **+11 %** (a real, faithful
amplitude effect — the single-bond α is meaningful at 1e-5).

### Fine dt=6.25e-7, 960k (0.6 s, MATCHED) — 2 seeds, the A1/A2 decider

| arm | velFitX (0/1) | avgBsteady (0/1) | **per-bound mean** | detach/s | dwell ms |
|--|--|--|--|--|--|
| uncorr | 7.304 / 6.868 | 4.528 / 4.371 | **1.592** | ~1303 | 0.77 |
| thermcorr4 | 4.021 / 5.398 | 1.792 / 2.183 | **2.359** (+48 %) | ~839 | 1.19 |
| `-uninoise 0.98` | 7.323 / 6.837 | 4.723 / 4.308 | **1.568** (−1.5 %) | ~1271 | 0.79 |

**A1 (matched sim-time).** The prior short-window (0.094 s) values were uncorr 1.914 / thermcorr4 2.469
(+29 %). At matched 0.6 s: uncorr **1.592** (the reference dropped −17 % — the 0.094 s window was
transient-inflated, exactly the A1 hazard), thermcorr4 **2.359** (−4 %). The **overshoot survives and is
larger** (+29 % → **+48 %**): windowing was slightly *masking* it, not creating it. So the overshoot is a
real matched-time effect, not a short-window artifact.

**A2 (uniform control — DECISIVE).** A genuine flat ×0.98 on ALL Brownian (`-uninoise 0.98`) at 6.25e-7 is
**inert**: velFitX 7.09→7.08 (−0.1 %), avgBound 4.45→4.52 (+1.5 %), per-bound 1.59→1.57 (both seeds:
7.323/6.837 vs 7.304/6.868). thermcorr4, nominally "also ≈0.98", instead **halves avgBound and cuts velFitX
−34 %**. Per the task's A2 criterion this is the **implementation-artifact branch, unambiguously**: a clean
2 % moves glide ~0 % while thermcorr4 moves it hugely ⇒ **thermcorr4 is not applying a clean uniform 2 % at
fine dt.** The differentiator is the **load-aware `N·k_F8` amplification**: the single-bond factors
(`-uninoise`/`-allnoise`) all → ~1 at 6.25e-7 (α_single ≈ 0.03 ⇒ ×0.992) and are correctly inert, but
thermcorr4's `α=N·aPerMotor` on a multiply-bound segment stays O(1) (N bonds; clamp at α→2 ⇒ ×0.1), and its
chain/head-trans terms are both <1 % at this dt — so the N-term is the *only* candidate that can produce a
large effect, and it over-cools the bound (load-bearing) segments (avgBound −55 %), the arm-3/arm-5 harm.

**Sensitivity direction (kills the "fine-dt hypersensitivity" claim).** The flat-2 % effect on per-bound is
**+11 % at 1e-5 but −1.5 % (≈0) at 6.25e-7** — sensitivity to a uniform amplitude cut **decreases** with
finer dt (the system is better-resolved, farther from the α→2 edge), the **opposite** of the claimed
"a 2 % factor → 29 % swing at fine dt." That claim was an artifact of attributing thermcorr4's load-aware
over-suppression swing to its nominal (single-bond) "2 % factor."

**What this corrects in the section above.** The comprehensive-thermcorr "Plain verdict (2)" — *"glide is
hypersensitive to noise amplitude (~15× gain) … a per-mode noise scale is the wrong instrument because the
hypersensitivity demands precision it can't deliver"* — is **overturned in its mechanism**: a faithful
uniform dt-vanishing amplitude correction is **inert at fine dt** (as it must be), so there is no
noise-amplitude hypersensitivity to fight. What stands, and is reinforced: **the load-aware `N·k_F8` term
over-suppresses** (arm 3, now shown to drive the fine-dt overshoot), **cooling load-bearing modes is harmful**
(arm 5 / `-fracnoise`), and **the fix is a coupled sub-step, not a noise thermostat.** The `-allnoise` ~63 %
recovery at *production* dt is unaffected (it is the single-bond correction, whose α is genuinely large at
1e-5) — that number was never the disputed one.

---

## PART B — detachment-rate convex-sampling isolation (`-detachramp`, deterministic, ~seconds)

The last untested reducible source: the catch-slip rate is **convex** in the bond force
(`∝ e^{±F·x/kT}`), evaluated on the **once-per-step-sampled** `forceDotFil` and applied over the whole step
(`P=rate·dt`). Under a load that changes *within* a step, the left-endpoint hazard sum ≠ the true within-step
integral — a dt-error in the *kinetics evaluation*, distinct from integration order and thermostat variance.
Isolated deterministically: a fresh bond (F0=0) under a linear ramp `F=Ḟ·t`, the **exact faithful rate**
(kOff=100/s, αCatch=0.92, αSlip=0.08, xCatch=2.5 nm, xSlip=0.4 nm; MotorStore/v1 Env), sampled left-endpoint
exactly as `catchSlipRelease` does, with **analytic survival accumulation** (no RNG/Brownian ⇒ ONLY the
sampling error) swept over the dt ladder; continuum = the same scheme at dt=1e-8.

### Pure convex-sampling (Brownian OFF) — ⟨F_detach⟩ deviation from the continuum

| Ḟ (pN/ms) | continuum ⟨F_det⟩ | **dev% @1e-5** | @2.5e-6 | @6.25e-7 | order |
|--|--|--|--|--|--|
| 1 | 19.431 pN | **−0.066 %** | −0.017 | −0.004 | 1st (≈4×/halving) |
| 3 | 30.709 pN | **−0.075 %** | −0.019 | −0.005 | 1st |
| 10 | 43.262 pN | **−0.108 %** | −0.027 | −0.007 | 1st |
| 30 (aggressive) | 54.669 pN | **−0.188 %** | −0.047 | −0.012 | 1st |

**The pure convex-sampling error is < 0.2 % at production dt even for an aggressive 30 pN/ms ramp**, and
shrinks first-order to the continuum. Deterministic detachment is **dt-flat** — there is no reducible
convex-rate-sampling error of any consequence. (Physical reason: within one 1e-5 step at Ḟ=3 pN/ms, F moves
~0.03 pN vs the catch e-fold scale kT/xCatch = 1.65 pN ⇒ ~2 % per-step on the integrand, but that is a
*local* error on a *fresh-hazard-weighted* sum ⇒ the accumulated mean shifts only ~0.07 %.)

### B2 — thermal-averaged rate: does a residual sampling dt-trend survive variance correction?

With the F8-well thermal fluctuation, `⟨rate(F_ramp+ξ)⟩ = rate·e^{a²σF²/2}`, σF² = k_F8·kT·E, E = 2/(2−α)
uncorrected / 1 corrected (`-allnoise`), α = k_F8·dt/γ_head (α_head@1e-5 = 0.531 ⇒ E = 1.361; σF(E=1) = 2.03
pN). Ḟ=3 pN/ms, ⟨F_detach⟩ dev% vs the same-treatment continuum:

| dt | UNCORR dev% (E) | **CORR(E=1) dev%** |
|--|--|--|
| 1e-5 | **−3.52 %** (E=1.361) | **−0.13 %** |
| 2.5e-6 | −0.64 % (E=1.071) | −0.03 % |
| 6.25e-7 | −0.15 % (E=1.017) | −0.008 % |

The ~3.5 % detachment dt-dependence at production dt is **entirely the variance inflation** (E: 1.36→1);
correcting the variance (E=1, the `-allnoise` fix) collapses it to the −0.13 % pure-sampling residual. **⇒
the detachment-rate dt-dependence is the thermostat-variance mechanism (Part A / the section above), not a
separate reducible convex-sampling error.** (Representative single-mode σF via γ_head; the exact head/segment
split shifts the magnitude, not the conclusion — both E→1 as dt→0.)

**VERDICT (Part B): dt-flat.** Deterministic detachment is fine (<0.2 %); no targeted detachment-rate
sub-step is warranted. The residual, after variance correction, is the **coupled loaded transport** — the
irreducibility candidate, now reached by *elimination* (integration order + thermostat variance + convex
sampling all excluded), exactly the airtight set the task asked for before the "irreducible" label.

## Reproduce
```
./run_eomstab.sh -detachramp                 # Part B (deterministic, ~seconds): the convex-sampling dt-ladder + B2
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -ratefix \
    -dt 6.25e-7 -seed 0 -uninoise 0.98 960000    # A2 uniform control (flat ×0.98 on ALL Brownian; matched 0.6 s)
./run_overshoot_A.sh                         # Part A: coarse (3 seeds) + fine matched-time 960k (2 seeds) A/B
```
Logs: `RUN_LOGS/2026-07-06_overshoot_A.txt`, `RUN_LOGS/2026-07-06_detachramp.txt`. New code:
`GlidingHarness.UNI_NOISE`/`-uninoise` (flat brownianForceMag×fac, both stores; byte-identical at 1.0);
`EomStabilityHarness.runDetachRampProbe`/`-detachramp` (faithful catch-slip rate, analytic survival).
Default byte-identical; `BoA-v1ref` byte-clean.

---

# SYSTEM-WIDE THERMAL CORRECTION — done correctly (single-constraint α, +free-motor chain) (2026-07-07)

The prior sections corrected the F8-bond ends at single-bond stiffness (`-allnoise`, ~63 %) and showed the
load-aware **N·k** summing OVER-cools (`-thermcorr`, the Jacobi-stiffness-sum breakdown). This section applies
the correction **system-wide but done correctly** — every constrained mode of every body, each at its **own
single-constraint α** (NEVER the summed N·k), plus the **free-standing motor body chain** (head/lever/tail
through their joints), which nothing had corrected. New code only (`GlidingHarness -syswide` / `-syswiderb`;
`BrownianForceSystem.scaleSysWideMotor` per-motor own-slot + `scaleSysWideSeg` per-segment own-slot ⇒
race-free, no atomics — the bail condition never triggered), **default byte-identical** (the `else-if` chains
preserve the exact dispatch when off: uncorr velFitX=2.112 / avgB=2.870 reproduced bit-for-print; `-allnoise`
reproduced 1.397); `BoA-v1ref` byte-clean; float32/CSR unchanged.

## STEP 1 — the wiring
`-syswide` scales each mode's Brownian amplitude by `√((2−α)/2)`, α from that mode's **own single constraint**:
- **Motor (`scaleSysWideMotor`, per-motor, own slots rod=3m/lever=3m+1/head=3m+2):** bound head trans = F8 well
  (single-bond); bound head rot = F9/F10; unbound head + all lever/rod = the J1/J2/tail-anchor fracMove chain
  (the free-motor-chain, `-syswiderb` only). head trans/rot ALWAYS written (no bind/unbind staleness).
- **Segment (`scaleSysWideSeg`, per-segment, own slot):** F8 = **SINGLE-BOND** `k_F8` (α=aF8 iff bound, **NOT
  N·k_F8**) + chain-links `nNbr·aChain` on **ALL** segments (nNbr∈{1,2} genuine independent chain neighbors —
  the noted parallel-springs-on-one-DOF case, distinct from the forbidden co-bound-motor summing).

## STEP 1b — per-mode faithfulness classification (measured α@1e-5 / α@6.25e-7)

| mode | α@1e-5 | α@6.25e-7 | class | why |
|--|--:|--:|--|--|
| F8 head-trans (bound) | 0.531 | 0.033 | **FAITHFUL** (dt-vanishing) | real spring α=k_F8·dt/γ_head ∝ dt |
| F8 seg-trans (bound, single-bond) | 0.419 | 0.026 | **FAITHFUL** | real spring α=k_F8·dt/γ_seg ∝ dt |
| F9/F10 head-rot (bound) | 0.400 | 0.031 | **FAITHFUL** | ratefixed (`-alignrate`) ⇒ ∝ dt |
| chain-link / neighbor (all segs) | 0.250 | 0.016 | **FAITHFUL** | ratefixed (`-filrate`) ⇒ ∝ dt |
| **J1/J2/tail-anchor free-motor chain** | **0.400** | **0.400** | **RE-BASELINE** (dt-FLAT) | fracMove α=frac, **not** in `-ratefix` scope |

The task's flag is confirmed: the **motor joints J1/J2 and the tail anchor are RE-BASELINE** (dt-flat fracMove,
α=0.400 at BOTH dts — corrected but the correction does NOT vanish as dt→0, so it changes the model at every
dt). Everything else (both F8 ends, the ratefixed F9/F10 and chain-links) is **FAITHFUL** (α∝dt, correction→1
as dt→0). This matches the already-measured `-varprobe` STEP-2 table (J1/J2/anchor dt-flat; F8 dt-vanishing).
`-syswide` = FAITHFUL modes only (free-motor chain EXCLUDED); `-syswiderb` = + the RE-BASELINE free-motor chain.

## STEP 2 — does cooling the free myosins move binding? (dt=1e-5, seed 0, 60k, GPU device-resident)

Config `-full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -coltol 10 -density 1000` (26740 motors).
per-bound = velFitX / avgBsteady.

| arm | adds | velFitX | avgBsteady | **per-bound** | detach/s | dwell ms |
|--|--|--:|--:|--:|--:|--:|
| 1 uncorr | — | 2.112 | 2.870 | **0.736** | 1624 | 0.62 |
| 2 `-allnoise` | F8 head+seg trans (single-bond) | 3.770 | 2.698 | **1.397** | 933 | 1.07 |
| 3 `-syswide` | + F9/F10 head-rot + chain-links (faithful) | 2.856 | 3.791 | **0.753** | 1399 | 0.71 |
| 4 `-syswiderb` | + free-motor chain (re-baseline) | 2.515 | 3.302 | **0.762** | 1383 | 0.72 |

**Isolations:**
- **Bond (arm2−arm1):** per-bound 0.736→1.397 (**+63 %** of the ~1.04 dt-gap) — the established `-allnoise` ceiling.
- **F9/F10 head-rot + chain-links (arm3−arm2):** per-bound **CRATERS 1.397→0.753 (−46 %)** while avgBound **rises
  2.70→3.79 (+40 %)**. Cooling the **bound** head's angular over-fluctuation over-retains heads (avgBound up,
  detach 933→1399) but **kills directed efficiency** (velFitX 3.77→2.86). A **faithful-by-classification**
  (dt-vanishing) mode that is nonetheless **load-bearing for directedness** — the same verdict as the prior
  `-fracnoise`/thermcorr-5 head-rotation collapse, now cleanly isolated as the F9/F10 mode. (The chain-links
  piece is the historical ~+4 %, swamped by the head-rot collapse.)
- **Free-motor chain (arm4−arm3 = jba's question):** avgBound **drops 3.79→3.30 (−13 %)**, velFitX −12 %,
  per-bound **flat** (0.753→0.762).

**PLAIN VERDICT — does cooling the free myosins move binding, or is the search not diffusion-limited at coltol
10?** Cooling the free myosins moves binding **modestly (avgBound −13 %) and per-bound-NEUTRAL** — the free-head
angular/positional thermal search (through its J1/J2/anchor joints) is only **weakly** fluctuation-limited at
capture radius 10 nm, **not** the dominant driver. **jba's hypothesis is confirmed:** the earlier strong
"harmful" verdict was **mostly the BOUND head-rotation (F9/F10) cooling collapsing per-bound directedness**
(arm3, per-bound 1.40→0.75) plus the **N·k over-cooling** (`-thermcorr` arm-3/5), **NOT** the free-head cooling
(a mere −13 % binding, efficiency-neutral). The binding-site search at coltol 10 is close to diffusion-INsensitive.

**Terminology (objective axis, replacing "helpful/harmful").** Both corrections are FAITHFUL (restore Var=kT/k,
dt-vanishing) — neither is "wrong." The only objective yardstick for a dt-fix is **distance to the measured dt→0
converged limit** (per-bound ≈1.57, STEP 3), *stated per channel*: a correction "moves production TOWARD" or
"AWAY FROM" that limit. This is not a scalar — the SAME F9/F10 head-rot cooling moves production **avgBound
toward** the converged value (3.00→3.75 vs ≈4.26 — over-retention that happens to match dt→0) while moving the
**per-bound ratio away** (1.40→0.75 vs ≈1.57). So "helpful/harmful" is the wrong axis; the honest statement names
the channel and the reference.

**Consequence for the "system-wide" program:** the full faithful correction does **NOT** beat the targeted
bond-only `-allnoise` on the per-bound channel — production per-bound 0.753 vs 1.397, i.e. it sits **farther from
the ≈1.57 converged limit** — because the extra faithful-by-classification mode (F9/F10 head-rot) is
directedness-load-bearing: cooling it moves per-bound **back away** from the limit (while moving avgBound toward
it). The **bond-only correction remains the ceiling on the per-bound channel**; system-wide coverage reaches into
directedness modes whose per-mode variance correction does not translate monotonically to converged transport.

## STEP 3 — convergence with the faithful `-syswide` on (matched SIM-TIME 0.6 s)

dt-ladder 1e-5→2.5e-6→1.25e-6→6.25e-7 at **matched sim-time** (60k/240k/480k/960k steps), faithful `-syswide`
(free-motor chain EXCLUDED), GPU device-resident. Uncorrected REUSED from `RUN_LOGS/2026-07-06_overshoot_A.txt`
(identical config; byte-identity reconfirmed by STEP-2's uncorr arm). per-bound = velFitX/avgBsteady.

| dt | steps | **`-syswide` per-bound** (velFitX / avgBsteady) | **uncorr per-bound** |
|--|--:|--:|--:|
| 1e-5 | 60k | **0.767** (2.875 / 3.746, 3-seed) | 0.774 (2.325 / 3.000, 3-seed) |
| 2.5e-6 | 240k | 1.261 (4.794 / 3.802) | — |
| 1.25e-6 | 480k | 1.488 (6.279 / 4.221) | — |
| 6.25e-7 | 960k | **1.549** (6.602 / 4.263, 2-seed) | **1.592** (7.086 / 4.450, 2-seed) |

**(a) Faithful — converges to the SAME dt→0 target.** `-syswide`@6.25e-7 per-bound **1.549 ≈ uncorr@6.25e-7
1.592** (−2.7 %, within the 2-seed chaotic envelope; the factors at 6.25e-7 are all ≈0.99 ⇒ near-inert, as a
dt-vanishing correction must be — cf. Part A's flat-×0.98 inertness at fine dt). The correction does **NOT move
the target**. The measured dt→0 per-bound limit for THIS config (`-xbimplicit2 -ratefix`) is **≈1.57**, somewhat
below the 1.78 ROTIMPLICIT reference. (velFitX/avgBsteady at 6.25e-7 sit marginally below uncorr — 6.60 vs 7.09,
4.26 vs 4.45 — consistent with the residual ~1 % cooling on near-inert factors / 2-seed sampling.)

**(b) Gap closed at production dt ≈ 0 %.** `-syswide`@1e-5 per-bound **0.767 ≈ uncorr 0.774** — the full
faithful system-wide correction closes **essentially NONE** of the ~0.80 per-bound gap, and tracks the **same
2.0× per-bound dt-climb** as uncorrected (0.767→1.549 vs 0.774→1.592). The reason is decomposed in STEP 2: the
F8-bond cooling (`-allnoise`, +0.62 ≈ **+78 %** of the gap to the 1.57 limit / +63 % to 1.78 — moves production
per-bound TOWARD the converged limit) is **cancelled** by the F9/F10 head-rot cooling (−0.64, moves it back AWAY
from the limit), which is directedness-load-bearing. `-syswide`
inflates production avgBound (+32 % vs uncorr, head-rot over-retention) and velFitX proportionally, so per-bound
is unchanged. A faithful correction that neither moves the target NOR accelerates convergence — a null at
production dt, by cancellation.

## PLAIN VERDICT (system-wide)

1. **The system-wide correction is genuinely FAITHFUL** (each mode's own single-constraint α; the free-motor
   chain classified RE-BASELINE and kept separate) and **converges to the same dt→0 limit as uncorrected**
   (per-bound ≈1.57). No N·k over-cooling, no moved target.
2. **But the full faithful correction closes ~0 % of the production-dt gap** — because correcting MORE faithful
   modes reaches into the **F9/F10 head-rotation**, which is load-bearing for directedness; cooling it cancels
   the F8-bond recovery. **The targeted bond-only `-allnoise` (~63–78 %) remains the ceiling.**
3. **Cooling the FREE myosins moves binding only modestly** (−13 % avgBound, per-bound-neutral) — the coltol-10
   binding-site search is only WEAKLY diffusion/fluctuation-limited. The earlier "harmful free-myosin cooling"
   reading was mostly the **bound** F9/F10 head-rotation collapse + the N·k over-cooling, not the free-head
   search — **jba's open question resolved: the search is close to diffusion-INsensitive at capture radius 10 nm.**
4. **The fix remains the coupled loaded transport (sub-step), not a per-mode noise thermostat** — the thermostat
   is faithful but self-cancelling system-wide, and reaches its ceiling at the two F8-bond ends. Consistent with
   the whole prior arc (`EOM_STABILITY`/`STROKE_TIP`).

`-syswide`/`-syswiderb` stay **diagnostics** (default byte-identical, dt-adaptive/faithful, not promoted).

## Reproduce
```
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -coltol 10 -density 1000 -dt 1e-5 -syswide 60000     # faithful system-wide (single-constraint α + F9/F10 + chain-links)
./run_gliding.sh -gpu ... -syswiderb 60000     # + free-motor-chain fracMove re-baseline (J1/J2/anchor, incl unbound)
./run_syswide_step2.sh    # 4-arm A/B (uncorr / allnoise / syswide / syswiderb) at 1e-5
./run_syswide_step3.sh    # faithful -syswide matched-sim-time dt-ladder + anchor seeds
```
Logs: `RUN_LOGS/2026-07-07_syswide_step2.txt`, `RUN_LOGS/2026-07-07_syswide_step3.txt`. New code:
`BrownianForceSystem.scaleSysWideMotor`/`scaleSysWideSeg` (per-motor/per-segment own-slot ⇒ race-free);
`GlidingHarness -syswide`/`-syswiderb` (single-constraint α, STEP-1b classification banner). Default
byte-identical; `BoA-v1ref` byte-clean.

---

# UNIFORM EQUILIBRIUM GATE — which corrections are REQUIRED / FORBIDDEN / NEEDS-REFORMULATION, decided by measurement, one test per mode (2026-07-07)

Every constrained mode through the **same** two-axis gate, under its **real operating conditions**, so the
Euler–Maruyama variance correction `noise ← noise·√((2−α)/2)` is applied where equipartition genuinely holds
and withheld where the fluctuation is driven — classified by **measurement**, never by whether the mode moves
the glide. New code only (`EomStabilityHarness -vargate`; `runVarGate`/`angHold`/`angDrive`/`setupAngScene`/
`angStep`/`measureChainModes`), **measurement-only, Brownian ON, single motor, default byte-identical**;
`BoA-v1ref` untouched; float32.

- **Axis 1 (equilibrium vs driven).** Variance of the mode coordinate about its **conditional** (per-nucleotide-
  state, post-settle) mean, vs the passive `kT/k·2/(2−α)`. **Driven-candidate modes run BOTH drive-ON** (the
  nucleotide **rest-angle square-wave** — the head stays BOUND and CYCLING, the powerstroke actually driving,
  not frozen) **AND drive-OFF** (held). The decisive, `kT/k`-free discriminator is the **per-state ratio**
  `ρ = condVar_on[state] / Var_off[state]` at the SAME state: ρ≈1 ⇒ the drive moves the MEAN but not the
  fluctuation ⇒ **equilibrium**; ρ≫1 ⇒ the switch pumps the fluctuation ⇒ **driven**.
- **Axis 2 (dt-vanishing vs dt-flat).** α@1e-5 vs α@6.25e-7 across the ladder. Ratefixed (production) ⇒ α∝dt
  (correction → 1 as dt→0, faithful); raw fracMove ⇒ α≈const (re-baseline).

## HEADLINE

**No mode is FORBIDDEN, and no mode is ILL-DEFINED at biological cycling rates.** The task's *prime FORBIDDEN
candidate* — the F9/F10 powerstroke-**alignment** and the J1 swing — is **REFUTED by measurement**: at the real
cycling regime these are **equilibrium about a moving mean** (the rest-angle switch relocates the conditional
mean; the conditional fluctuation is thermal, ρ≈1.0 at every dt). So imposing `kT/k` on them is **not** a wrong
equilibrium constraint. The correction set is decided purely by Axis 2 after that: real-spring / ratefixed modes
are **REQUIRED**; the dt-flat fracMove structural modes are **NEEDS-REFORMULATION** (make them dt-convergent,
then correct). **Crucially, this is orthogonal to transport:** that cooling F9/F10 moves per-bound *away* from
the converged limit (§SYSTEM-WIDE STEP-2) is a *directedness/transport* consequence, NOT a non-equilibrium
signature — the gate answers "is `kT/k` the wrong target for this mode?" (no), not "does cooling it help glide?".

## [0] Instrument confirmation (F8 isolated — must reproduce the validated STEP-1 gate)

| dt | α_emp | Var(nm²) | meas E=Var/(kT/k) | pred 2/(2−α) |
|--:|--:|--:|--:|--:|
| 1e-5 | 0.418 | 5.233 | **1.271** | 1.264 |
| 6.25e-7 | 0.026 | 4.204 | 1.021 | 1.013 |

Instrument confirmed — the F8 seg-translation excess tracks `2/(2−α)` (the validated calibration). F8
head-translation is the SAME zero-rest F8 well by symmetry (same `kT/k_F8`).

## [1] The driven angular modes — drive-OFF (held) vs drive-ON (nucleotide square-wave), per state

Body FREE + Brownian (rod+head; lever off, v1), filament FROZEN reference, ratefix ON. The head stays BOUND;
ONLY the rest angle switches (F9 90°↔120°, J1 0°↔60° with the ADPPi↔ADP square wave). `condVon[state]` samples
only steps past the settle window of each half-period (T½=4000 steps ≫ τ_mode). ρ = max per-state
`condVon/Var_off`.

| mode | dt | Var_offA / Var_offB | condVonA / condVonB | mean A→B | **ρ per-state** |
|--|--:|--:|--:|--:|--:|
| **F9 head-rot** (θ seg.u∠head.u) | 1e-5 | 179.6 / 166.0 | 179.8 / 164.6 | 89.9→116.2° | **1.001** |
| | 6.25e-7 | 105.2 / 87.4 | 111.0 / 87.7 | 89.7→110.1° | 1.056 |
| **F10 head-roll** (θ seg.y∠head.y) | 1e-5 | 157.2 / 153.8 | 158.1 / 153.1 | 24.5→23.9° | **1.006** |
| | 6.25e-7 | 101.5 / 100.6 | 100.6 / 95.1 | 19.0→18.5° | 0.991 |
| **J1 swing** (θ lever.u∠head.u) | 1e-5 | 50.8 / 123.4 | 50.5 / 123.1 | 14.1→57.9° | **0.997** |
| | 6.25e-7 | 21.6 / 45.0 | 21.4 / 43.4 | 8.9→53.3° | 0.991 |

**All three are EQUILIBRIUM-about-a-moving-mean** (ρ 0.99–1.06 at every dt on the 1e-5→6.25e-7 ladder). The
powerstroke rest-switch relocates the conditional MEAN (F9 90→116°, J1 14→58° — the means track the 90↔120 /
0↔60 rest switch, softened by the joint network + the stall cap), while the conditional VARIANCE is unchanged
from the held (drive-off) value at that state. **Caveat (does not affect the verdict):** the analytic angular
`kT/k` printed is only approximate for J1 — its near-collinear (rest-0°) geometry stiffens the angle
(Var_offA 50.8 ≪ naive kT/k 331 °²), so `Var/(kT/k)` is unreliable there; the **ratio test is `kT/k`-free** and
is what decides Axis 1.

## [2] The gate DOES detect driven excess — a FAST square wave (T½ ≈ τ) control, dt=1e-5

| mode | Var_off | running-Var @T½=4 | @T½=20 | conditional @T½=4000 |
|--|--:|--:|--:|--:|
| F9 head-rot | 179.6 | 210.0 | 260.3 | 172.2 |
| F10 head-roll | 157.2 | 164.8 | 162.6 | 155.6 |
| J1 swing | 50.8 | 221.3 | **448.6** | 86.8 |

When the rest is switched **faster than the mode relaxes** (T½=4–20 steps ≈ a few·τ), the variance about the
**running** mean inflates far above equilibrium (J1 51→449 — dominated by the 14↔58° mean swing); the mode is
continuously driven and the conditional mean is not resolvable. At T½=4000 (slow) it collapses back to the
equilibrium conditional value. **So the classification is regime-dependent, and the gate correctly flags the
driven regime** — it simply is not the one biology occupies (see [3]).

## [3] Regime check — τ_mode vs the biological cycle dwell (why the SLOW column is the real one)

τ_mode (from α_off): F9 ≈ 1.3 steps, F10 ≈ 2.2 steps, J1 ≈ 1 step (α≈1, ringing edge). v1 biological dwells
@1e-5: powerstroke (ADPPi→ADP) ~10 steps, post-stroke (ADP→NONE) ~100, ATP recovery ~1000. **Real dwell (10–1000
steps) ≫ τ_mode (1–2 steps)** ⇒ each nucleotide state fully equilibrates before the next switch ⇒ the
**conditional-mean regime is operative** ⇒ the modes are **equilibrium** under real cycling. (In physical time the
same holds across dt: τ_mode·dt ≈ 1.3e-5 s vs the ~1e-4 s powerstroke ⇒ dwell/τ ≈ 5–8 at both 1e-5 and 6.25e-7.)

## [4] Chain F3 / F4 (passive elastic filament links, 8-seg free Brownian chain, no drive)

| mode | α@1e-5 | Var@1e-5 | α@6.25e-7 | Var@6.25e-7 | Var ratio (16× dt) |
|--|--:|--:|--:|--:|--:|
| F3 link-gap | 0.361 | 1.299e-5 µm² | 0.363 | 8.04e-7 µm² | 16.1 (∝dt) |
| F4 bend-angle | 0.566 | 11.42 °² | 0.570 | 0.717 °² | 15.9 (∝dt) |

Passive (no drive) ⇒ **equilibrium** by construction. Axis 2: measured with the **default (un-ratefixed) chain
coeffs**, α is **dt-FLAT** (0.361≈0.363, 0.566≈0.570) and the absolute Var scales ∝dt — the **fracMove
signature** (α=frac). So the chain links, as SoftBox integrates them by default, are **NEEDS-REFORMULATION**; the
production `-ratefix`/`-filrate` converts their coeffs to ∝dt (dt-vanishing → REQUIRED) — exactly as the
ratefixed F9/F10/J1 modes above demonstrate (α drops ~16× over the 16× dt span).

## MODE-BY-MODE TABLE — the uniformly-measured correction set

| mode | k / γ character | α@1e-5 | α@6.25e-7 | Var/(kT/k) & E | drive ON vs OFF | **Axis 1** | **Axis 2** | **DECISION** |
|--|--|--:|--:|--|--|--|--|--|
| **F8 head-trans** (bound) | real spring k_F8, low-drag sphere head | ~0.53 | ~0.03 | tracks E (STEP-1) | n/a (no rest switch) | equilibrium | dt-vanishing (α∝dt) | **REQUIRED** |
| **F8 seg-trans** (bound) | real spring k_F8, γ_seg | 0.42 | 0.026 | 1.271 vs 1.264 ✓ | n/a | equilibrium | dt-vanishing | **REQUIRED** |
| **F9 head-rot** (bound) | align torque, ratefixed | 0.55 | 0.034 | ~1.26·kT/k | **ρ=1.00** (mean 90→116°) | **equilibrium** | dt-vanishing | **REQUIRED** (ratefixed) |
| **F10 head-roll** (bound) | align torque, **constant 0° rest** | 0.36 | 0.033 | ~0.5·kT/k† | **ρ=1.01** (rest never switches) | **equilibrium** | dt-vanishing | **REQUIRED** (ratefixed) |
| **J1 swing / neck** (bound) | converter torque, ratefixed + stall cap | 1.02‡ | 0.15 | geom-stiffened† | **ρ=1.00** (mean 14→58°) | **equilibrium** | dt-vanishing | **REQUIRED** (ratefixed) |
| **chain F3 link** | fracMove link spring (default) | 0.361 | 0.363 | Var∝dt | n/a (passive) | equilibrium | **dt-FLAT** | **NEEDS-REFORMULATION** → REQUIRED w/ `-filrate` |
| **chain F4 bend** | fracMove bend torque (default) | 0.566 | 0.570 | Var∝dt | n/a (passive) | equilibrium | **dt-FLAT** | **NEEDS-REFORMULATION** → REQUIRED w/ `-filrate` |
| **J2 gap** (structural) | fracMove hinge (α=frac) | 0.24 | 0.25 | E≈1.14 (STEP-2) | n/a (structural) | equilibrium | **dt-FLAT** | **NEEDS-REFORMULATION** |
| **tail anchor** (structural) | fracMove tether (α=frac) | 0.29 | 0.29 | E≈1.17 (STEP-2) | n/a | equilibrium | **dt-FLAT** | **NEEDS-REFORMULATION** |

† analytic angular `kT/k` unreliable (F10/J1 geometry-dependent stiffness); the **drive ON/OFF ratio** (kT/k-free)
decides Axis 1, and it is unambiguous (ρ≈1). ‡ J1 α@1e-5≈1 sits at the ringing edge (τ ill-resolved), but α∝dt
holds (1.02→0.15 over 16× dt) and the equilibrium verdict is robust (ρ≈1 at all four dts).

## THE DEFENSIBLE, UNIFORMLY-MEASURED CORRECTION SET

- **REQUIRED** (equilibrium + dt-vanishing — apply `√((2−α)/2)`; justified *and* faithful): the two F8-bond
  ends (head-trans, seg-trans), and — once ratefixed, as production runs them — the **F9/F10 head alignment and
  the J1 swing**. Their fluctuation-about-conditional-mean is genuine equipartition; the powerstroke only moves
  the mean.
- **NEEDS-REFORMULATION** (equilibrium but dt-FLAT fracMove — the correction would re-baseline the model, not
  vanish as dt→0): the **motor structural joints J2 & tail anchor**, and the **chain F3/F4 links + the align/swing
  coeffs *before* ratefix**. The honest fix is to make the mode dt-convergent (real-spring / `-ratefix`/`-filrate`)
  **first**, then correct. Do NOT silently noise-correct a dt-flat mode.
- **FORBIDDEN** (driven / non-equilibrium — leave the thermostat as-is): **EMPTY at biological cycling rates.**
  The powerstroke-alignment modes were the candidates and they measured **equilibrium** (§[1]). The FORBIDDEN
  category would apply only in the fast-driving regime (T½ ≲ τ, §[2]), where the correct noise treatment of a
  *driven/rectifying* mode is a **separate open modeling problem** — but biology is not in that regime (§[3]), so
  this category leaves **no** mode's driven-noise question open here.
- **ILL-DEFINED:** none — every mode's `k/γ` extracted cleanly and every driven-candidate resolved a clean
  conditional distribution (dwell ≫ τ). The bail conditions (drive/fluctuation inseparable; k/γ unextractable)
  were not triggered.

**No mode is classified by whether it moves the glide.** The F9/F10 modes that §SYSTEM-WIDE showed are
"directedness-load-bearing" (cooling them moves per-bound away from the converged limit) are measured here as
**equilibrium** — so the correction on them is *faithful* (restores the physically-correct equilibrium spread);
its transport consequence is a distinct question (the converged target itself shifts), exactly the "name the
channel, not helpful/harmful" discipline. This settles the correction set on physics: it matches the
**bond-only `-allnoise` ceiling** (both F8 ends REQUIRED), adds F9/F10/J1 as *equally* REQUIRED-when-ratefixed
(not forbidden), and flags every fracMove structural mode for reformulation-before-correction.

## Reproduce
```
./run_eomstab.sh -vargate    # [0] F8 instrument + [1] F9/F10/J1 drive-OFF/ON ladder + [2] fast-drive control + [3] regime + [4] chain F3/F4
```
Log: `RUN_LOGS/2026-07-07_vargate.txt`. New code: `EomStabilityHarness -vargate` (`runVarGate` + `angHold`/
`angDrive` per-state conditional variance + `setupAngScene`/`angStep` + `measureChainModes`), reusing
`BrownianForceSystem`/`CrossBridgeSystem.bondForces`+`applyHeadForce`/`MotorJointSystem`/`TailAnchorSystem`/
`ChainBendingForceSystem.chainForces`/`RigidRodLangevinIntegrationSystem`. **Measurement-only, default
byte-identical; `BoA-v1ref` byte-clean.** (Summary "ρ(on/off)" column = the per-state max; the [1] table shows
both states.)

---

# STRUCTURAL-MODE REFORMULATION — `-structrate`: make the motor SKELETON dt-convergent (2026-07-07)

The UNIFORM EQUILIBRIUM GATE classified the motor's passive structural joints (**J2 hinge + tail anchor**)
as **NEEDS-REFORMULATION**: equilibrium modes but **dt-FLAT** (fracMove α=frac) ⇒ their variance freezes
∝dt toward zero as dt→0 instead of converging to a physical spread. The driven stroke/alignment
(F9/F10/J1-swing) and the chain (F3/F4) were already rate-converted by `-ratefix`; these passive
structural constraints were left out. A defensible model can't have its own skeleton freeze at dt→0, so
this section brings them to dt-convergent form for **consistency of realism** (transport impact expected
~0 — these modes are stiff/near-static — measured to size the promotion cost). New code only
(`GlidingHarness`/`EomStabilityHarness -structrate`), **default byte-identical**; `BoA-v1ref` untouched;
float32/CSR unchanged; race-free (scalar build-time constants fed to UNCHANGED kernels).

## PART 1 — the enumeration (code read; this IS the reformulation spec)

### (1) Current rate-conversion coverage — what `-strokerate`/`-alignrate`/`-filrate` convert

| flag | force term(s) converted | coeff slot (value) | law site |
|--|--|--|--|
| **`-strokerate`** | the directedSwing power stroke (neck sweep toward the barbed-ward target) | `swingParams[0]=0.4` (via `swingParams[4]=refDt` sentinel) | `CrossBridgeSystem.directedSwing`/`directedSwingHeadFrame`: `mag=k·(π/180)·ang/((1/γ_lev+1/γ_head)·dt)` |
| **`-alignrate`** | F9 (⊥ perp-maintainer, frozen-90° sphere-head) + F10 (axial/roll lock → ŝ under `-axlock`) alignment torques | `xbParams[2]=0.4` (build-time `rateFix`) | `CrossBridgeSystem.bondForces`: `tm=xbP2·(π/180)·ang/((1/γ+1/γ)·dt)` |
| **`-filrate`** | F3 link spring + F3 bending torque (rides on fracMove) + F4 torsion | `chainParams[1]=0.5` (fracMove), `chainParams[3]=0.2` (fracMoveTorq); `chainParams[2]=0.1` (fracR) = **geometry, untouched** | `ChainBendingForceSystem.chainForces` |
| **`-ratefix`** | = `-strokerate` + `-alignrate` + `-filrate` (all of the above) | | `GlidingHarness.java:223` |

Conversion form (all): `k_eff = 1 − (1−k)^(dt/refDt)` (`GlidingHarness.rateFix`), refDt=1e-5. At dt=refDt,
k_eff=k ⇒ byte-identical; ∝dt below refDt.

### (2) The J2 hinge + tail anchor — un-converted, fraction-per-step, dt-flat (the gate's α=const modes)

| structural term | law site | expression | coeff (value) | status |
|--|--|--|--|--|
| **J2 connection/position spring** (rod.end2 ↔ lever.end1) | `MotorJointSystem.joints:127-128,143-153` | `forceMag = j2FracMove·1e-6·strain/(dt·(mcRod+mcLever))`, applied at body centres + lever-arm bend torque `R×F`, `R=½·len·j2FracR·û` | `jointParams[5]=0.4` (+ `[6]=j2FracR=0.4` geometry, rides) | **MODEL/frac, un-converted, dt-flat** — varprobe "J2 gap" α≈0.24 flat |
| **tail-anchor spring** (rod.end1 ↔ fixed bed pt) | `TailAnchorSystem.anchor:58-63` | `forceMag = anchorFracMove·1e-6·strain/(dt·mc2)`, force at rod centre, NO torque | `jointParams[9]=0.4` | **MODEL/frac, un-converted, dt-flat** — varprobe "anchor gap" α≈0.29 flat |
| J2 **angular** torsion (rest 96°) | `MotorJointSystem.joints:139` | `torsionMag = j2FracMoveTorq·(π/180)·(ang−96)/(invBRG·dt)` | `jointParams[7]=0.0` | **OFF (zero coeff)** — nothing to convert (`myoJ2FracMoveTorq=0`, v1 free hinge) |

Both active structural forces are exactly the PAIRS `frac·gap/(dt·moveC)` form ⇒ the per-STEP relaxation
`Δ_rel = frac·gap` is dt-independent (physical rate ∝ 1/dt), the fracMove/α=frac signature (unit-checked
in `DT_AUDIT_AND_SPRINGS.md` §PART A). **Cleanly fraction-per-step ⇒ the bail (not fraction-per-step) is
NOT triggered.**

### (3) The J1/J2 taxonomy resolution — swing converter (covered) vs structural springs (the gap)

The earlier audit's "J1/J2 torsions unconverted" flag and the gate's "J1 swing IS ratefixed" are **both
correct, about different force terms**:

- **The J1 SWING CONVERTER** (the powerstroke driver) is `jointParams[3]` (`j1FracMoveTorq`, the neck
  angular converter toward the switching rest 0°↔60°). In the **default gliding path**
  (SPHEREHEAD+AXLOCK+DIRSWING) it is **ZEROED** (`GlidingHarness.java:601 if(DIRSWING) jointParams.set(3,0)`)
  and the stroke is `directedSwing` (`swingParams`) instead — **covered by `-strokerate`**; the F9/F10
  alignment (`xbParams[2]`) is **covered by `-alignrate`**. So "the J1 swing IS rate-converted" (vargate
  **REQUIRED**) — via directedSwing/F9, NOT via `jointParams[3]`. ✓ Not in the reformulation scope.
- **The structural ANGULAR torsion springs** are `jointParams[3]` (J1) and `[7]` (J2). Both are **zero in
  glide** ([3] zeroed by dirswing; [7]=0 always). Nothing to reformulate.
- **The actually-active un-converted structural constraints** are the **CONNECTION/POSITION springs** —
  J1 `[1]`, J2 `[5]` — and the **tail anchor** `[9]`: the rigidity constraints holding the 3-body motor +
  its bed anchor together. **These** are the dt-flat skeleton `-structrate` reforms.

**Other un-converted structural constraint beyond J2/anchor:** the **J1 CONNECTION spring**
(`MotorJointSystem.joints:168-169`, `j1FracMove=jointParams[1]=0.4`, + lever-arm bend torque `j1FracR=[2]=0.4`)
— MODEL/frac, un-converted, dt-flat (varprobe "J1 gap" α≈0.35 flat). Included in the reformulation.

**⇒ Exact named list to reformulate: `jointParams[1]` (J1 connection), `jointParams[5]` (J2 connection),
`jointParams[9]` (tail anchor)** — all fracMove=0.4, all cleanly fraction-per-step. `fracR` ([2]/[6]) is
geometry (untouched); the angular converters ([3]/[7]) are inert in glide (untouched).

## PART 2 — the reformulation (`-structrate`) + gliding transport impact

**Realization = pure build-time coefficient substitution, NO kernel edit** (identical mechanism to
`-alignrate`/`-filrate`): after scene finalization, `jointParams[1]/[5]/[9] ← rateFix(0.4)`
(`GlidingHarness.java:602-611`). The MotorJointSystem/TailAnchorSystem kernels are byte-unchanged; a scalar
constant per run ⇒ **race-free, CPU≡GPU by construction**. `-structrate` is **NOT** folded into `-ratefix`
(keeps the `-ratefix` baseline stable for the A/B). *Alternative form:* a fixed-stiffness Hookean
`k=frac·γ_red/refDt` (the `DT_AUDIT_AND_SPRINGS.md` `springify`) is the equally-valid spring reading; per
the task we default to the **rate-conversion** for consistency with how F9/F10/chain were converted (the
`−ln(1−frac)/frac` continuum-choice between the two is the deferred `-ratefix`-fidelity issue, not opened
here).

**Default byte-identity — CONFIRMED.** At dt=refDt=1e-5, `rateFix(0.4)=0.4000` ⇒ the coefficients are
unchanged, so `-structrate` ON is byte-identical to OFF (= HEAD).

**Gliding transport impact (dt=1e-5, seed 0, `-full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix
-coltol 10 -density 1000`, 60k, GPU device-resident):**

| arm | velFitX | avgBsteady | **per-bound** | detach/s | dwell (ms) | duty |
|--|--:|--:|--:|--:|--:|--:|
| baseline (`-ratefix`) | 2.112 | 2.870 | **0.736** | 1624.4 | 0.6156 | 0.8114 |
| **`-ratefix -structrate`** | **2.112** | **2.870** | **0.736** | **1624.4** | **0.6156** | **0.8114** |

**BYTE-IDENTICAL to every digit** (GRID_ROW + STATS_STEADY_ROW `diff`-clean). **The re-baseline cost of
promoting `-structrate` at production dt is exactly ZERO** — because production dt = refDt, so
`rateFix(0.4)=0.4` and no coefficient moves. The reformulation only bites **below** refDt (the
dt-convergence studies), where it makes the skeleton converge instead of freeze. This is the strongest
possible promotion argument (zero production cost) — and precisely why it is *still* a planner decision,
not auto-promoted (it changes the model at every dt≠refDt, i.e. the dt-ladders).

## PART 3 — the re-gate (`-varprobe -structrate` / `-vargate -structrate`): the flip

**Axis 2 — the flip dt-FLAT → dt-VANISHING (varprobe STEP-2 α_emp; baseline vs `-structrate`):**

| dt | J2 gap base → structrate | anchor gap base → structrate | J1 gap base → structrate |
|--:|--:|--:|--:|
| 1e-5 | 0.24 → **0.242** | 0.29 → **0.294** | 0.35 → **0.354** (byte-identical @refDt) |
| 5e-6 | — → 0.128 | — → 0.170 | — → 0.177 |
| 2.5e-6 | 0.24 → 0.066 | 0.29 → 0.094 | 0.33 → 0.092 |
| 1.25e-6 | 0.25 → 0.033 | 0.29 → 0.049 | 0.34 → 0.046 |
| 6.25e-7 | 0.26 → **0.016** | 0.29 → **0.024** | 0.35 → **0.023** |
| 3.13e-7 | 0.26 → 0.008 | 0.29 → 0.013 | 0.36 → 0.012 |

Baseline α is **dt-FLAT** (const ≈0.24/0.29/0.35 at every dt); `-structrate` α is now **∝dt** (drops ~15×
over the 16× dt span), E=2/(2−α) → 1 (J2 1.138→1.008, anchor 1.172→1.012 at 6.25e-7). **And the absolute
variance now CONVERGES to a fixed physical spread** (anchor Var 11.1→8.5, J2 16.1→13.0, J1 11.9→9.2 nm²)
instead of freezing ∝dt toward zero — the skeleton **stops freezing**.

**Axis 1 — ρ ≈ 1 (still equilibrium):** J2/anchor/J1-gap are PASSIVE structural modes (no rest-angle
drive) ⇒ equilibrium by construction (the vargate already classified them so). `-structrate` restores the
physical equilibrium variance (E→1) rather than disturbing it. **⇒ the decision flips cleanly
NEEDS-REFORMULATION → REQUIRED** (equilibrium + now dt-vanishing).

**J1 ringing-edge re-check (`-vargate -structrate`, the J1 SWING angular converter):** ρ stays **≈1 at
every dt** (0.997 / 1.001 / 1.005 / 0.990 across 1e-5→6.25e-7) — the equilibrium-about-a-moving-mean
verdict **SURVIVES** the reformulation. At production dt=1e-5 the swing α_off=1.0238 (still on the ringing
edge) is **byte-identical** to baseline — `-structrate` is byte-identical at refDt, so it does **not and
cannot** move the production-dt α off the edge. At finer dt the swing IS better-resolved (α_off 1.02→0.13)
and, with the position springs now dt-convergent too, the whole joint network is uniformly dt-convergent —
the swing's coupled Var converges to a fixed spread (50.8→24.7 nm²). **The ringing edge is an intrinsic
coarse-dt property of the swing's own α** (addressable only by finer dt or an implicit swing), NOT
something the structural reformulation touches at production dt.

## Plain statement

- **Is the motor skeleton now uniformly dt-convergent?** **YES.** With `-structrate`, every structural
  fraction-per-step constraint (J1/J2 connection springs + tail anchor) joins the already-ratefixed driven
  (F9/F10/swing) and chain (F3/F4) modes in **α ∝ dt**: the STEP-2 α ladder is dt-vanishing for *all*
  skeleton modes, and their equilibrium spreads converge to fixed physical values instead of freezing
  toward zero. The gate's NEEDS-REFORMULATION category is emptied for the motor.
- **What does it cost the gliding number?** **NOTHING at production dt** — byte-identical (velFitX 2.112,
  per-bound 0.736, both arms, every digit), because production dt = refDt so `rateFix(0.4)=0.4`. The change
  is a re-baseline only for dt≠refDt (the dt-ladders), where it is the *point* (converge, don't freeze).
- **Does J1's equilibrium verdict survive?** **YES** — ρ≈1 at every dt. The J1-swing ringing edge is a
  coarse-dt resolution property of the swing's own α, left untouched at production dt (byte-identical) and
  resolved only by finer dt / an implicit swing — orthogonal to the structural reformulation.

**Promotion to default is a separate planner decision (flagged for jba, NOT done here):** `-structrate` is
faithful (dt-vanishing, restores Var=kT/k), race-free, CPU≡GPU, and costs the production number zero — but
promoting it re-baselines every dt-ladder study, so it stays a diagnostic pending sign-off.

## Reproduce
```
./run_eomstab.sh -varprobe -structrate     # PART 3 Axis-2: J1/J2/anchor gap α flips dt-flat → ∝dt
./run_eomstab.sh -vargate  -structrate     # PART 3 J1-swing ringing-edge re-check (ρ≈1 survives)
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -structrate -coltol 10 -density 1000 -dt 1e-5 -seed 0 60000   # PART 2 transport (byte-identical to -ratefix)
```
Logs: `RUN_LOGS/2026-07-07_structrate_regate.txt`, `_structrate_vargate.txt`,
`_structrate_transport_{baseline,on}.txt`. New code: `GlidingHarness -structrate` (build-time
`jointParams[1]/[5]/[9]` rateFix) + `EomStabilityHarness -structrate` (same, in the measurement scene).
Default byte-identical; `BoA-v1ref` byte-clean.

---

# `-allnoise` DIAGNOSIS — the dt-flat / factor-insensitive offset is a GPU TaskGraph-split ARTIFACT (2026-07-08)

`GLIDING_RECONVERGENCE.md` left an OPEN PUZZLE: with the convergent skeleton, `-allnoise`'s per-bound
offset (arm B ≈1.4 vs arm A ≈0.8) **does NOT vanish as dt→0 even though its factor →1** (×0.993 @5e-7),
and is **insensitive to factor magnitude** (~0.63 at both ×0.987 and ×0.993). A faithful dt-vanishing
correction's *effect* must scale with its factor's departure from 1 — so something `-allnoise` does is
**not tracking its stated `√((2−α)/2)` factor**. This diagnostic finds what. New code only
(`GlidingHarness -allnoisescale <x>` forces the applied head+seg factors to a fixed value with the code
path fully active; `EomStabilityHarness -varmean` = STEP 3/4). **Default byte-identical**
(`ALL_NOISE_SCALE=-1` off; real `-allnoise` unchanged — ALLNOISE seed-0 reproduces the prior table's
velFitX 3.770 / per-bound 1.397 exactly); `BoA-v1ref` untouched; float32/race-free.

## HEADLINE (the resolution)

**The `-allnoise` per-bound "recovery" is a GPU-EXECUTION ARTIFACT of enabling the code path (the
TaskGraph acquires the `motNoise`/`segNoise` tasks → altered kernel scheduling/compilation → a ULP-scale
perturbation), amplified by the metastable/chaotic gliding steady state into a systematic basin shift —
NOT the `√((2−α)/2)` noise physics.** Forcing the applied factor to **exactly 1.0** (`-allnoisescale
1.0`, code path active) reproduces the full `-allnoise` per-bound offset. The genuine noise-factor
contribution is ≈0. This is neither path 1 (mode-drift/OU — ruled out, STEP 3/4) nor path 2 (nonlinear
rotation — the mode is linear); it is an implementation artifact × a bistable steady state.

## STEP 1 — the identity test: does scale-1.0 reproduce OFF? RUNNER-DEPENDENT (the smoking gun)

`-allnoisescale 1.0` keeps the `-allnoise` code path fully active but forces the applied head+seg factors
to exactly 1.0. Since the default `brownTransScale` equals `base=BTransCoeff` for **every** real slot
(heads via `assembleArticulated`, segments via `fil.brownTransScale.set(s,BTransCoeff)`) and the gliding
stores are allocated at exact active count (no FREE/phantom slots), a factor-1.0 write is provably an
identity no-op on a deterministic runner. And the Brownian RNG is keyed on `(slot,step,seed)` ONLY
(`BrownianForceSystem.brownianForce`), independent of `brownTransScale` — so enabling `-allnoise` cannot
shift the RNG stream.

- **CPU (deterministic, sequential, NO TaskGraph): `-allnoisescale 1.0` ≡ OFF BYTE-IDENTICAL** (2000
  steps: velFitX 1.220, avgBsteady 3.636, GRID_ROW+STATS every digit; `diff`-clean). The code path is a
  **true no-op at factor 1.0** — confirming the genuine effect enters ONLY through the factor and must
  vanish as factor→1.
- **GPU (the runner ALL the A/B tables used): `-allnoisescale 1.0` ≠ OFF, and reproduces the `-allnoise`
  offset.** Decisive 60k (0.6 s, the tables' window) × 3 seeds, `-full -grid -lymntaylor -adppibind
  -xbimplicit2 -ratefix -structrate -coltol 10 -density 1000 -dt 1e-5`, per-bound = velFitX/avgBsteady:

| arm | s0 | s1 | s2 | **mean per-bound** | mean avgBsteady |
|--|--:|--:|--:|--:|--:|
| OFF (arm A) | 0.736 | 0.791 | 0.796 | **0.774** | ~3.0 (2.87/2.96/3.17) |
| **`-allnoisescale 1.0`** (factor **1.0**, code path active) | 1.294 | 1.294 | 1.271 | **1.286** | ~2.0 (1.47/2.59/1.86) |
| `-allnoise` (real `√((2−α)/2)` factor) | 1.397 | 1.080 | 1.299 | **1.259** | ~2.6 (2.70/2.41/2.63) |

**`-allnoisescale 1.0` (1.286) ≈ `-allnoise` (1.259) ≫ OFF (0.774)** — the two are statistically
indistinguishable (SCALE1 is if anything slightly HIGHER), both ~+0.5 above OFF, with the SAME systematic
avgBound collapse (~3.0→~2.0). **So the entire `-allnoise` per-bound "recovery" is reproduced at factor
exactly 1.0**, i.e. it is the code-path/execution artifact, and the `√((2−α)/2)` factor adds ≈0.

**Answer to STEP 1's fork:** **YES on CPU (byte-identical ⇒ genuinely inert), NO on GPU (differs
independent of the scale value).** The GPU difference is therefore an **execution artifact** of enabling
the code path — the graph gains two tasks → TornadoVM recompiles/reschedules the whole TaskGraph → a
ULP-level float perturbation in the (unrelated) kernels → the chaotic gliding trajectory decorrelates.
The shift is **systematic** (not scatter): SCALE1 lands consistently in a **low-avgBound / high-per-bound
basin** across all 3 seeds (per-bound 1.29/1.29/1.27, tight) — the **metastable "two glide regimes"** the
RECONVERGENCE doc flagged, here selected by the perturbation. This **ends the diagnosis**: the offset was
never faithfully *applying* the `√((2−α)/2)` correction — it is the graph-split perturbation tipping a
bistable steady state.

## STEP 3/4 — isolated F8 mode: variance-only, mean-unbiased, linear ⇒ OU ≡ `-allnoise` (path 1 RULED OUT)

`EomStabilityHarness -varmean`: the isolated F8 bond mode (motor frozen, filament rotation frozen, single
bound segment, Brownian on the ∥ translation only) — a LINEAR zero-rest-length overdamped harmonic mode
⇒ exactly Ornstein–Uhlenbeck. Measured Var + MEAN-displacement-from-noise-free-equilibrium vs dt, three
modes: OFF (explicit EM ×1) / ON (`-allnoise` ×√((2−α)/2)) / OU (exact one-step propagator
`x_{n+1}=e^{−α}x_n+√((kT/k)(1−e^{−2α}))ξ`):

| dt | α | Var_OFF | Var_ON | Var_OU | meanOFF(nm) | meanON(nm) | meanOU(nm) |
|--:|--:|--:|--:|--:|--:|--:|--:|
| 4e-5 | 1.675 | 25.36 | 4.12 | 4.12 | −0.003 | −0.001 | +0.002 |
| 1e-5 | 0.419 | 5.26 | 4.16 | 4.18 | −0.015 | −0.013 | −0.013 |
| 6.25e-7 | 0.026 | 4.22 | 4.17 | 4.10 | −0.004 | −0.004 | +0.062 |

(kT/k_F8 = 4.116 nm².) **Var_OFF over-fluctuates at coarse dt tracking `(kT/k)·2/(2−α)`; Var_ON and Var_OU
both restore to ≈kT/k at every dt.** The **MEAN displacement is ~0 (sub-0.06 nm scatter, no dt-trend) for
ALL THREE** — so the isolated mode's EM error is **variance-only, with NO mean/drift bias** (as expected:
a linear additive-noise mode has an exact mean update in expectation). ⇒ **OU and `-allnoise` give the
SAME (unbiased) mean AND the same restored variance ⇒ OU offers nothing beyond `-allnoise` for this mode
⇒ path 1 (a correct-EM/OU fix for a mode-drift error) is RULED OUT for the bond mode.** The mode is
linear (clean AR(1); the STEP-1 gate's α_emp=α_analytic), so **path 2 (nonlinear rotation coupling) does
not apply to it either.** At the mode level `-allnoise` is the faithful, complete fix — so the ensemble
per-bound offset is **not** a mode-integration error, consistent with STEP 1.

## STEP 2 — genuine factor-response on the DETERMINISTIC (CPU) runner: threshold/near-bistable, but SMALL

On CPU (where scale-1.0 ≡ OFF byte-identically) the genuine noise-factor effect is uncontaminated by the
graph split. dt=1e-5, seed 0, 10k, `-full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -structrate
-coltol 10 -density 1000`:

| arm | velFitX | avgBsteady | per-bound | Δ avgB vs OFF |
|--|--:|--:|--:|--:|
| OFF (= `-allnoisescale 1.0`, byte-identical) | 1.941 | 3.647 | 0.532 | — |
| `-allnoisescale 0.90` (a genuine 10 % cut) | 1.494 | 2.588 | 0.577 | **−29 %** |
| `-allnoise` (real ×0.857/0.889) | 2.120 | 3.216 | 0.659 | **−12 %** |

**Reads.** (1) The genuine factor is a **real lever** on the deterministic runner — a 10 % cut moves
avgBound −29 %. (2) But the response is **non-monotonic / threshold-like**: `-allnoisescale 0.90` (a
smaller cut) drops avgBound MORE than the real `-allnoise` ×0.857/0.889 (a bigger cut) — a single
deterministic trajectory landing in different basins for nearby factors, the **near-bistable "two glide
regimes"** signature (STEP 2's threshold branch — a model-robustness property, not a proportional-from-zero
faithful response). (3) The genuine per-bound shift is **modest** (0.532→0.659, +24 %) and **dt-vanishing**
(factor →1 as dt→0) — FAR below the GPU factor-1.0 artifact (0.774→1.286, +66 %; the artifact alone
exceeds the whole genuine CPU effect). So the metastable steady state genuinely responds to a
noise-amplitude cut (the factor is not inert), but that response is small, steep/threshold-like, and
dt-vanishing — while the large, dt-flat, factor-insensitive GPU offset is the graph-split artifact
(STEP 1). (Single-seed/short-window on the slow sequential runner; the sign of the qualitative reads —
threshold-like + small vs the GPU artifact — is robust, the exact %'s are noisy.)

## ROUTING VERDICT

**The dt-flat, factor-insensitive `-allnoise` offset is an IMPLEMENTATION ARTIFACT — the GPU TaskGraph
gains the `motNoise`/`segNoise` tasks when the code path is enabled, TornadoVM recompiles/reschedules the
graph, and the resulting ULP-scale float perturbation tips the metastable/chaotic gliding steady state
into a systematically lower-avgBound / higher-per-bound basin — NOT the `√((2−α)/2)` noise physics
(reproduced at factor exactly 1.0), NOT a mode-drift error the correct EM/OU fixes (path 1, ruled out:
the isolated mode is variance-only + mean-unbiased ⇒ OU≡allnoise), NOT a nonlinear rotation coupling
(path 2: the mode is linear).** It is jointly an **execution artifact × a bistable steady state** — the
same object viewed two ways (the artifact provides the perturbation; the bistability provides the
amplification).

**Consequence for the planner (IMPORTANT):** the prior **GPU-measured** `-allnoise`/`-thermcorr`/`-syswide`
A/B numbers (the "~63 % recovery," the fine-dt "overshoot," the RECONVERGENCE arm-B offset) are
**contaminated by this graph-split basin-tip** and conflate the noise factor with a factor-independent
execution artifact. A `-allnoisescale 1.0` **control arm** (or CPU) must be run alongside any future
`-allnoise`-family A/B to subtract the artifact. The genuine noise-factor effect vanishes as factor→1 (as
a faithful correction must) and is measured cleanly only on the deterministic runner (STEP 2).

## Reproduce
```
./run_gliding.sh          -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -structrate -coltol 10 -density 1000 -dt 1e-5 -seed 0 -allnoisescale 1.0 2000   # STEP 1 CPU: ≡ OFF byte-identical
./run_gliding.sh -gpu     -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -structrate -coltol 10 -density 1000 -dt 1e-5 -seed 0 -allnoisescale 1.0 60000  # STEP 1 GPU: reproduces the -allnoise offset
./run_allnoise_ident.sh    # STEP 1 decisive: 3-arm (OFF / scale1.0 / allnoise) × 3-seed 60k GPU
./run_eomstab.sh -varmean  # STEP 3/4: isolated F8 mode Var+mean, OFF vs ON vs OU
./run_allnoise_step2cpu.sh # STEP 2: deterministic CPU factor-response
```
Logs: `RUN_LOGS/2026-07-08_allnoise_diagnosis.txt`. New code: `GlidingHarness -allnoisescale <x>`,
`EomStabilityHarness -varmean`/`measureF8MeanVar`/`runVarStep3`. Default byte-identical; `BoA-v1ref` byte-clean.
