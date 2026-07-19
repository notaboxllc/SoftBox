# Explicit HMM dimer — directional-mechanism survey (barbed-ward free-head steering)

A long survey testing whether a **polarity-aware, state-dependent conformational mechanism** can naturally
reposition/re-aim the free HMM head toward the next barbed-end actin site when its partner binds and/or
power-strokes — producing a **resolved forward second-head binding bias without a hard rearward veto**, while
preserving the validated motor stroke, force, shared paired-S2 architecture, second-head binding, and numerical
health. Builds on `EXPLICIT_HMM_DIMER_FORWARD_ACCESSIBILITY_FINDINGS.md` (which found the current mechanics gives
only a weak, unresolved forward tendency). Fork geometry α=10°, Lb=10 nm, EI×0.25, exclusion 5.4 nm. Every mechanism
is **default OFF**; the single-head model, `TwoBodyConverterMotor`/`MotorModel`, the shared-S2 material, canonical
half-open ownership, zero physical margin, search radius, and orientation gates are all preserved; gates + exclusion
fixtures re-pass byte-identically with the defaults. CPU-only.

## Headline — NO conformational mechanism resolves the forward bias; only a hard/graded rule does
The decisive geometric fact (from the accessibility study): the symmetric fork splays the two heads **transversely
(in Z)**, so **both pivots sit at the same x** — both heads project to nearly the same filament material coordinate.
A directional mechanism must move the free head's tip to a *different, barbed-ward* x. The deterministic survey shows:
- **M1/M2/M4 (fork/branch skew, stroke-driven fork):** ≲0.05 nm forward shift at healthy amplitudes — **ineffective**
  (canting a 10 nm branch barely changes the pivot's x); and at useful amplitudes they become **dynamically unstable**
  (M1b 15° → joint gap 129 nm, peak 40 pN, invalid states).
- **M3 (free-converter cant):** the only mechanism with a sign-clean, S2-preserving, monotonic forward shift — but it
  tops out at **+1.66 nm only at an extreme 30° cant** (below the +2–6 nm target), and a cant that large **breaks the
  free head's binding orientation gate** (the reposition-vs-bindability tension); dynamically it produced **no** forward
  preference. 
- **M5 (shared-S2 bend):** ~0 nm (the intact paired S2 is rigid — correctly preserved, no directional leverage).
- **M6 (combined):** ≲0.2 nm — modest distributed changes do not beat the single M3 term.
- **Phenomenological benchmarks:** only the **C3 hard rearward veto** produces a clean forward-only result (by
  construction); the **C4 graded penalty** gives a tunable intermediate. _(quantified in §Comparison below.)_

**Conclusion: EMERGENT/CONFORMATIONAL forward bias — NOT achievable in this architecture without a hard/graded rule.
READY TO PROMOTE A DIRECTIONAL MODEL: NO** (no conformational mechanism qualifies; a hard veto works but is
artificial). The architectural root cause (transverse splay ⇒ pivots at equal x) means a genuine forward bias needs a
**different fork/head geometry that offsets the two pivots along the filament axis**, not a local cant of the present
one — that is the recommended next step, or the graded C4 penalty as an explicit phenomenological stand-in.

## 1. Common infrastructure (deliverables 1, 2)
`ExplicitHmmDimer` gains a polarity-aware, state-dependent directional layer (default OFF): `dirMech` (1 M1a / 2 M1b /
3 M2 / 4 M3 / 5 M4 / 6 M5 / 7 M6), amplitude `dirAmp`/`dirAmp2`, compensation `dirComp`, converter stiffness
`dirConvStiffMult`, barbed direction `bHat` (filament polarity — flips for the polarity-reversal control), `boundHead`
(which head is strongly bound), and a smoothly-ramped activation `dirAct∈[0,1]` set by the caller from the trigger.
Mechanisms are pure **energy / rest-geometry / orientation** terms (no coordinate teleportation, no position restraints):
branch cants rotate a fork hinge's preferred direction toward/away from `bHat` (Rodrigues); the converter cant adds a
spring pulling the free head's converter rest toward a canted angle; the shared bend rotates the emergence tangent.
**Smooth activation** (deliverable 2): `dirAct` ramps toward its target (rate 0.05/step T1/T2, 0.02/step T3) and relaxes
to 0 on detach — no instantaneous force jump (verified: default byte-identical; activation transients bounded).

## 2. Baseline controls (deliverable, §2)
- **C0** current coupled dimer (exclusion 5.4 nm, normal stroke): the reference (see the accessibility findings —
  forward fraction 0.667, CI [0.413, 0.780], deterministic shift +0.020 nm).
- **C1** stroke suppressed (`-nostroke`) and **C2** fork-decoupled (`solve(decoupleFork)`): reused from the prior
  study (both show the shared tail transmits ~0 directional drive).
- **C3** hard rearward veto (`-rearveto`): backward second-head binding rejected — labeled upper-bound reference.
- **C4** graded rearward penalty (`-rearpen γ`, γ = 0.5/0.25/0.1): backward on-rate multiplier — phenomenological.

## 3–8. Mechanism deterministic survey (deliverables 3–8, 9, 11)
`./scripts/run_hmm_dimer_forward.sh -survey` — Brownian-off, head A bound at its natural site, head B free; primary
metric `deltaFreeF8Forward` = the free-head B F8 signed-coordinate shift toward barbed when the mechanism activates
(sign-robust: both cant signs tested, the forward one reported); health = max joint gap < 4 nm AND shared-S2 deform
< 1 nm; T1 = bound-pre-stroke, T2 = bound-post-stroke.

| family | best healthy ΔfreeF8Fwd (T2) | best params | note |
|---|---|---|---|
| M1 (fork skew a/b) | **+0.02 nm** | M1b amp 5° comp 1.0 | ineffective; larger amp → wrong sign / unstable |
| M2 (stroke-driven fork) | +0.02 nm | amp 5° comp 0.5 | ineffective (same skew machinery) |
| M3 (free-converter cant) | **+1.66 nm** | amp 30° k×1.0 | best; monotonic; but 30° breaks the bind orientation gate |
| M4 (free-branch fwd) | +0.01 nm | amp 5° | ineffective (branch cant; = M1a machinery) |
| M5 (shared-S2 bend) | +0.00 nm | — | null (intact S2 rigid; correctly preserved) |
| M6 (combined) | +0.21 nm | branch 5°/comp0.5 + conv 5°/k0.5 | no synergy beyond M3 |

M3 scaling (k×1.0): amp 5° → +0.30 nm, 10° → +0.59, 20° → +1.14, 30° → +1.66 nm (fwdAdvantage surf(−5.4)−surf(+5.4):
+0.48 → +0.95 → +1.77 → +2.44 nm). Even the extreme 30° cant does not reach the +2–6 nm free-F8-shift target, and a
30° converter re-aim exceeds the binding orientation tolerance ⇒ the repositioned head cannot bind. Branch/fork skew
(M1/M2/M4) barely moves the pivot's x because both pivots start at equal x (transverse splay). M5 leaves the intact
shared S2 undeformed (deform ≈ 0), so it has no directional leverage — the paired-S2 interpretation is preserved.

## 9. State-trigger survey (deliverable, §9)
T1 (bind-triggered) and T2 (post-stroke) give nearly identical deterministic shifts (the mechanisms act on the free
head, largely independent of the bound head's stroke state); T3 (stroke-ramp) is the smoothed T2. None changes the
conclusion — the shift ceiling is set by geometry, not the trigger. T2 is used for the dynamic screens (activates once
the partner has strongly bound + stroked).

## 10. Smooth activation / hysteresis (deliverable, §10)
Activation ramps over ~20 steps (T1/T2) or ~50 steps (T3) and relaxes on detachment; no discontinuous force jump
(default paths byte-identical; the dynamic runs show no activation force spike beyond the normal binding onset).

## 11–13. Mechanical-health screen (deliverables 9, 11)
Static: every candidate keeps the intrinsic converter stroke **8.00 nm** and head-vs-anchor/actin **7.60 nm** (the
free-head mechanisms don't touch the bound head's stroke; the branch mechanisms don't either — verified), max joint
gap 0.02 nm, shared-S2 deform < 0.01 nm. **Dynamic health, however, rejects the branch mechanisms:** M1b at 15° under
the full Brownian + cross-bridge load blows up (max joint gap 129 nm, peak F8 40 pN, invalid states 4) — the fork
skew is statically fine but dynamically unstable at useful amplitudes. M3 stays numerically healthy dynamically
(gap ~4 nm, invalid 0) but delivers no forward benefit (below).

## 14–15. Natural dynamic screen + comparison (deliverables 12, 13, 14, 15, 16)
Dynamic (seed 13, 16000 steps, exclusion 5.4 nm) confirms the deterministic verdict:
| config | fwd | bwd | note |
|---|---|---|---|
| C0 control | 2 | 1 | reference |
| M3 conv-cant 15° T2 | 0 | 2 | **no forward benefit** (the small deterministic shift is swamped dynamically; the canted head bound backward) |
| M1b fork-skew 15° | — | — | **unstable** (gap 129 nm, peak 40 pN, invalid 4) — rejected |
| C3 hard veto | 3 | 0 | clean forward-only (by construction) |

**§16 model comparison** (`-compare`, 24 seeds × 16000 steps; clustered 95% CI by seed; 146–149 ms one-head-bound
exposure per config):
```
config           | fwd bwd  fwdFrac  clustered95%CI | dblFrac  dwell(ms) | contShift(nm)
C0 baseline      |  15   8   0.652   [0.35, 0.79]   | 0.0079   0.378     |  +1.352
M3 conv 10° k1 T2|  11  10   0.524   [0.24, 0.73]   | 0.0087   0.439     |  +0.636   ← WORSE than baseline
M3 conv 20° k1 T2|   7  17   0.292   [0.12, 0.50]   | 0.0096   0.412     |  +0.123   ← strongly BACKWARD
C4 penalty γ0.5  |  16   7   0.696   [0.38, 0.81]   | 0.0071   0.337     |  +1.343   (CI still includes 0.5)
C4 penalty γ0.25 |  16   3   0.842   [0.54, 1.00]   | 0.0055   0.311     |  +1.700   ← RESOLVED (CI excludes 0.5)
C4 penalty γ0.1  |  16   2   0.889   [0.58, 1.00]   | 0.0051   0.304     |  +1.712   ← RESOLVED
C3 hard veto     |  16   0   1.000   [1.00, 1.00]   | 0.0038   0.247     |  +1.864   ← RESOLVED (by construction)
```
**The decisive comparison:** the best conformational mechanism (M3) does not merely fail to help — it **degrades**
the forward fraction (0.652 → 0.524 at 10°, → 0.292 at 20°: the canted free head increasingly binds *backward*,
because re-aiming the converter toward barbed rotates the head out of the forward-site orientation and into the
backward-site orientation). Only a **phenomenological rule** resolves the bias: the graded rearward penalty at
**γ ≤ 0.25** (clustered CI excludes 0.5) or the hard veto (γ = 0). Rejecting backward binds also modestly raises the
continuous forward search shift (+1.35 → +1.86 nm) and lowers the double-bound fraction/dwell (fewer, cleaner
forward-only co-bindings). Second-head binding is preserved throughout (16 forward binds in every C4/C3 config).

A resolved forward bias requires the clustered CI to **exclude 0.5**. Only the hard veto (C3, forward fraction 1.0 by
construction) and a sufficiently strong graded penalty (C4) achieve it; **no conformational mechanism does.**

## 16–17. Ranking (deliverable 17)
Ranked by (1) deterministic forward shift, (2) +5.4/−5.4 accessibility advantage, (3) resolved natural bias, (4)
preserved binding rate, (5) preserved stroke, (6) low preload, (7) moderate coupling, (8) intact S2, (9) stability,
(10) simplicity: **M3 is the best CONFORMATIONAL mechanism** (only one with a real, S2-preserving, stable forward
shift) but it is **insufficient** (shift ≪ target, no dynamic bias, orientation cost at large amplitude). All
branch/fork mechanisms rank below it (ineffective and/or dynamically unstable). No mechanism qualifies as a resolved
directional model.

## 18–19. `-3js` movies (deliverable 16) + overlays (§19)
Matched (seed 13, 16000 steps, stride 15 → 1068 frames, exclusion 5.4 nm, fork sphere off, rod hidden):
| movie | dir | config | outcome |
|---|---|---|---|
| A control | `threejs_hmm_dimer_dir_ctrl/` | C0 | fwd 2 / bwd 1 |
| B M1 fork-skew (rejected) | `threejs_hmm_dimer_dir_M1/` | M1b 15° comp 1.0 | **unstable** (gap 129 nm) — shown as the rejected case |
| D M3 converter-cant | `threejs_hmm_dimer_dir_M3/` | M3 15° k×1.0 T2 | fwd 0 / bwd 2 (no benefit) |
| F hard rearward veto | `threejs_hmm_dimer_dir_veto/` | C3 | fwd 3 / bwd 0 (clean forward-only) |
(M2/M6 movies omitted — deterministically ~null, visually identical to the control.) The removed translucent fork
sphere is NOT restored by default (§19); debug overlays remain default-off.

## 20. Interpretation (deliverable, §20)
The angles/stiffnesses are effective coarse-grained parameters, not measured values. Distinguished clearly: emergent
shared-tail mechanics (negligible), effective state-dependent conformational mechanics (M1–M6 — insufficient here),
graded phenomenological kinetic bias (C4), and a hard rearward veto (C3). A successful conformational mechanism, had
one worked, would represent an effective leading/trailing head–lever or head–rod-junction rearrangement omitted from
the current explicit model. No native inter-head biochemical gating was introduced (chemistry rates unchanged); the
dimer is not claimed processive; backward binding is not claimed biologically impossible.

## 21. Commands / seeds / timestep / stride (deliverable 17)
- `dt = 2.5e-6 s`; deterministic survey Brownian-off; dynamic comparison 24 seeds × 16000 steps; movies seed 13.
```
./scripts/run_hmm_dimer_forward.sh -survey                       # deterministic mechanism survey (M1–M6 × params × triggers)
./scripts/run_hmm_dimer_forward.sh -compare -seeds 24 -steps 16000   # C0 / M3 / C4(γ) / C3 dynamic comparison + clustered CI
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_dir_ctrl -seed 13 -steps 16000 -stride 15 -splay 16 -gap 3.0 -nseg 12 -alpha 10 -branchei 0.25 -branchlen 10 -excl 5.4                          # Movie A
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_dir_M3   -seed 13 ... -excl 5.4 -mech 4 -mechamp 15 -mechstiff 1.0 -mechtrig 2   # Movie D (M3)
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_dir_M1   -seed 13 ... -excl 5.4 -mech 2 -mechamp 15 -mechcomp 1.0 -mechtrig 2   # Movie B (M1, rejected)
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_dir_veto -seed 13 ... -excl 5.4 -rearveto                                        # Movie F (C3)
```

## 22. Modified-file inventory (deliverable 18)
- `softbox/ExplicitHmmDimer.java` — directional layer: config fields, `hingeBranchSide`, `forkCant`/`rotToward`,
  branch-cant in `bendEnergy` (M1/M2/M4/M6), converter cant in `headBlock` (M3/M6), shared-bend in the emergence term
  (M5). All gated on `dirMech`/`dirAct`; default 0 ⇒ byte-identical.
- `softbox/ExplicitHmmDimer3jsHarness.java` — Scene mechanism config + per-step activation ramp; C3 veto / C4 penalty
  in the bind gate; `-mech/-mechamp/-mechcomp/-mechstiff/-mechamp2/-mechtrig/-rearveto/-rearpen` args; `rearRejects`.
  Default OFF ⇒ prior paths byte-unchanged.
- `softbox/ExplicitHmmDimerForwardHarness.java` — `-survey` (deterministic mechanism screen + per-family scorecard),
  `-compare` (§16 conformational-vs-phenomenological comparison), `detMechShift`/`mechanismSurvey`/`poolCfg`/`compareModels`.
- `docs/matsoa/EXPLICIT_HMM_DIMER_DIRECTIONAL_MECHANISM_SURVEY.md` — this report.
- **Unchanged:** single-head model, `TwoBodyConverterMotor.java`, `MotorModel.java`, `sim_viewer_boa.html`, and the
  prior exclusion/short-branch/fork harnesses' default behaviour.

## 23. Failure log (deliverable 19)
- **M1a/M1b/M4 (fork/branch skew):** deterministically ineffective (≲0.05 nm; pivots at equal x) AND dynamically
  unstable at useful amplitudes (M1b 15° → gap 129 nm). REJECTED.
- **M2 (stroke-driven fork):** same skew machinery ⇒ same ineffectiveness. REJECTED.
- **M5 (shared-S2 bend):** null shift (intact S2 rigid) — correctly preserves the paired coiled coil but has no
  directional leverage. REJECTED (no effect).
- **M6 (combined):** ≲0.2 nm, no synergy beyond M3. REJECTED.
- **M3 (free-converter cant):** best conformational mechanism but INSUFFICIENT — shift ceiling +1.66 nm only at a
  bind-breaking 30° cant; no dynamic forward preference. Not promoted.
- Shared-infrastructure error found + fixed during the survey: the initial "toward-barbed" cant sign was inverted
  (the stroke axis points −x); the survey is sign-robust (tests both signs, reports the forward one).

## 24. FINAL STATUS BLOCK
```
BASELINE DETERMINISTIC FORWARD SHIFT: +0.020 nm (current mechanics; negligible)
MECHANISMS TESTED: M1a, M1b, M2, M3, M4, M5, M6 (conformational) + C0, C1, C2, C3, C4 (controls)
M1 BEST FORWARD SHIFT: +0.02 nm      M1 BEST PARAMETERS: M1b amp 5° comp 1.0  (ineffective; unstable at larger amp)
M2 BEST FORWARD SHIFT: +0.02 nm      M2 BEST PARAMETERS: amp 5° comp 0.5      (ineffective)
M3 BEST FORWARD SHIFT: +1.66 nm      M3 BEST PARAMETERS: amp 30° k×1.0        (below +2–6 nm target; 30° breaks the bind orientation gate)
M4 BEST FORWARD SHIFT: +0.01 nm      M4 BEST PARAMETERS: amp 5°               (ineffective)
M5 BEST FORWARD SHIFT: +0.00 nm      M5 BEST PARAMETERS: —                    (null; intact S2 preserved, no leverage)
BEST COMBINED-MECHANISM SHIFT: +0.21 nm   BEST COMBINED PARAMETERS: M6 branch 5°/comp0.5 + conv 5°/k0.5  (no synergy)
BEST +5.4 VS -5.4 ACCESSIBILITY ADVANTAGE: +2.44 nm (M3 30° k×1.0; but that cant is not bindable)
BEST NATURAL FORWARD FRACTION (conformational): 0.524 (M3 10°) — LOWER than the C0 baseline 0.652 (M3 degrades it)
CLUSTERED 95% CI: [0.24, 0.73] (M3 10°) — includes 0.5 (unresolved / degraded)
FORWARD/BACKWARD RATE RATIO: 1.9 (C0 baseline)
FORWARD BINDING RATE: 0.103 per ms (C0 baseline)
BACKWARD BINDING RATE: 0.055 per ms (C0 baseline)
SECOND-HEAD BINDING RATE VS BASELINE: preserved (M3 ≈ baseline; C4/C3 keep 16 forward binds)
TWO-HEAD-BOUND FRACTION VS BASELINE: 0.0079 (C0) → 0.0087 (M3) → 0.0038 (C3 veto) — veto lowers it (backward co-binds removed)
DOUBLE-BOUND DWELL VS BASELINE: 0.378 ms (C0) → 0.439 (M3) → 0.247 (C3)
INTRINSIC CONVERTER STROKE: 8.00 nm (preserved by every mechanism)
HEAD STROKE RELATIVE TO ANCHOR: 7.60 nm (preserved)
PEAK F8 FORCE: ~9–12 pN (healthy configs); M1b 15° → 40 pN (unstable, rejected)
REST PRELOAD: ~0 pN (detached)
DETERMINISTIC COUPLING RATIO: 0.36 (unchanged; shared-tail transmission negligible)
MAX SHARED-S2 DEFORMATION: < 0.01 nm (intact paired coiled coil preserved in every mechanism)
MAX JOINT GAP: ~4 nm (healthy dynamic configs); M1b 15° → 129 nm (unstable)
INVALID STATES: 0 (C0/M3/C3/C4); M1b 15° → 4 (unstable, rejected)
SOLVER FAILURES: 0
POLARITY REVERSAL: PASS       SPATIAL MIRROR: PASS       A/B RELABEL: PASS       SMOOTH ACTIVATION: PASS
BEST CONFORMATIONAL MECHANISM: M3 free-converter cant (only one with a real, S2-preserving, stable forward shift — but INSUFFICIENT and dynamically counterproductive)
CONFORMATIONAL MECHANISM RESOLVES FORWARD BIAS: NO
GRADED REARWARD PENALTY REQUIRED: YES — γ ≤ 0.25 resolves it (clustered CI [0.54,1.00] excludes 0.5)
HARD REARWARD VETO REQUIRED: NO — the graded penalty (γ0.25) already resolves it; the veto (γ=0, fraction 1.0) is the upper bound, not required
CONTROL 3JS: threejs_hmm_dimer_dir_ctrl/ (1068 frames)
BEST M1 3JS: threejs_hmm_dimer_dir_M1/ (1068 frames — the UNSTABLE case, shown as rejected)
BEST M2 3JS: — (omitted; deterministically null, identical to control)
BEST M3 3JS: threejs_hmm_dimer_dir_M3/ (1068 frames)
BEST COMBINED 3JS: — (omitted; M6 null)
HARD-VETO REFERENCE 3JS: threejs_hmm_dimer_dir_veto/ (1068 frames)
READY TO PROMOTE DIRECTIONAL MODEL: NO — no conformational mechanism qualifies; the current default (C0) stands
RECOMMENDED DEFAULT MODE: keep C0 (no directional rule) as default; if a resolved forward bias is required now, the graded rearward penalty γ≈0.25 (C4) is the least-artificial effective option (an explicit phenomenological kinetic bias, not a hard veto)
NEXT STEP: the architectural root cause is that the symmetric fork splays the heads TRANSVERSELY (both pivots at equal x); a genuine emergent forward bias needs a fork/head geometry that OFFSETS the two pivots along the filament axis (e.g. an axially-staggered / lead-lag fork), tested with the same deterministic forward-shift screen — a local cant of the present geometry cannot deliver it. Failing that, adopt the C4 γ0.25 graded penalty as an explicit stand-in.
```

**Success criterion:** the survey determined that **no** polarity-aware, state-dependent conformational rearrangement
of the present fork/converter can naturally reposition the free head toward the barbed site enough to produce a
resolved forward second-head binding bias (the best, M3, is insufficient and dynamically counterproductive; the
branch mechanisms are ineffective and unstable; the shared-S2 bend is null with the S2 correctly intact). A resolved
forward bias in this architecture requires an explicit phenomenological rule (graded rearward penalty γ≤0.25, or a
hard veto) — or, preferably, a redesigned fork geometry that axially offsets the two pivots. The motor stroke,
shared-tail architecture, second-head binding, and numerical stability are preserved throughout.
