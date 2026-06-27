# BOUND THERMAL CORRELATION — constraint-aware thermal forcing of the bound cross-bridge; can correlating the head's thermal kick to its filament's tame the coarse-dt signed-load tail?

**MEASUREMENT + ADDITIVE (flag-gated, default-off), no physics edit.** Changes ONLY the thermal-noise
correlation on bound heads (`BondThermalCorrelationSystem.correlateBoundHead`, invoked only with
`-bondcorr <α>`). The binding search, the cross-bridge spring force law, and the catch-slip rate FORMULA
are untouched. `BoA-v1ref` untouched. Sole occupant of aorus, v2-GPU device-resident. Same 0.5× dt-study
scene (box 5.0, 200 nodes × 24 singlet myosins, 500 fil × 10 seg, crosslinkers on, aeta 0.1). New
readout `SLHIST` (the SIGNED bound-head load distribution, negative tail resolved) + `RELROW`/`DTHIST`
(reused). Driver `run_bondcorr.sh`; raw `RUN_LOGS/2026-06-25_bondcorr.txt`, `_work15.txt`. Companions:
`RELEASE_FORCE_INPUT_FINDINGS.md` (the force-averaging fork), `DT_CONVERGENCE_FINDINGS.md` (the dt
ceiling), `MYOSIN_BINDING_RATE_FORMULATION.md` (the two independent wings). Serves `RESEARCH_THESIS.md`
§9 (the cross-bridge dt ceiling toward the 1e-4 large-scale-emergence dream).

## VERDICT — the FORK opens, decisively: correlation is NOT the release-wing dt lever
**No α reproduces the fine-dt negative tail at any coarse dt, and at the working dt it makes the tail
WORSE.** The bound-head↔filament-contact thermal-noise correlation, as formulated (variance-preserving,
translational, isotropic, correlate-to-the-filament's-raw-draw — all per the task spec), does the thing
it is designed to do (preserves temperature, attenuates the relative-coordinate thermal variance) yet
does NOT tame the signed-load distribution. The reason is the FORK the task anticipated: **the negative
excursions of forceDotFil that the catch term e^(−F·xCatch) rectifies are dominated by the DETERMINISTIC
explicit stiff-spring overshoot, NOT by the independent thermal relative noise.** Removing the thermal
relative noise therefore cannot fix them — and the modest noise restructuring slightly worsens them.
**⇒ the indicated lever is sub-stepping the stiff cross-bridge (the deterministic fix), exactly as
`RELEASE_FORCE_INPUT` concluded; the relative-coordinate noise is not the whole story.** This is a real
result (the task's "fork" outcome), not a least-bad tuning.

## The idea (physical, not a hack) — and why it nonetheless fails here
F8 = myoSpring·(head_tip − foot) reads the RELATIVE displacement. Independent thermal kicks to the head
and the segment fluctuate the relative separation by the SUM of two independent noises; the stiff spring
rectifies that into a force spike, fattening forceDotFil's negative tail (the explicit integrator
inflates it ∝ dt). A bound cross-bridge is one mechanically-coupled object — the bond constrains the
relative DOF, so by FDT it should not be thermally excited as if free; correlating the two bodies'
thermal kicks removes the spurious relative noise at its source (the noise-side analog of treating a
stiff bond as a constraint, not a stiff potential). **The premise is sound but its WEIGHT is wrong here:
at the cross-bridge dt ceiling the relative excursion is set by the spring's deterministic one-step-stale
response, of which the thermal kick is a minority.**

## The formulation (variance-preserving α knob) — as implemented
Correlate the UNIT-VARIANCE draws, then apply each body's own FDT amplitude (so α tunes pure
correlation, not temperature):
- `η_fil ~ N(0,1)` = the bound segment's RAW Brownian draw this step (NOT its net force — the net force
  carries the chain-spring overshoot; correlating to it would re-inject the artifact. Care-point #1 honored).
- `η_head = α·η_fil + √(1−α²)·ξ_head`, `ξ_head ~ N(0,1)` independent ⇒ Var(η_head)=1 (preserved),
  Corr=α. Apply the head's own `brownianForceMag·√bTransGam`.
- α=0 ⇒ current independent forcing; α=1 ⇒ identical unit draws.
- **ISOTROPIC** (same α on all 3 Cartesian components — care-point #2: started isotropic) and
  **TRANSLATIONAL** (head-center kicks; the head tip = center + ½·HEAD_LEN·uVec is translation-dominated;
  rotational/lever wobble deferred). Head↔filament-contact only (neck/tail deferred).
- **Implementation = self-write, NOT a gather:** each bound head belongs to one motor and reads one
  segment, so each motor recomputes its head sub-body's `ξ_head` and its bound segment's `η_fil`
  (bit-for-bit the SAME wang-hash + Box-Muller as `BrownianForceSystem`, keyed (slot, step, runSeed)),
  blends, and overwrites `randForce[head]`. Race-free (each motor writes only slot 3m+2), no
  atomics/KernelContext, runs on both runners; placed after the motor+filament Brownian kernels.

**α=0 byte-identity GATE PASSED** (`run_bondcorr.sh verify`): `-bondcorr 0` ≡ no-flag, **bit-identical**
DTROW + SLHIST on BOTH runners (CPU and GPU) — the kernel reproduces `BrownianForceSystem`'s head draw
exactly when α=0. Default-off (no flag) ⇒ the bondCorr task is never built ⇒ production byte-unchanged.

## STAGE 1 — the fine-dt signed-load reference (instantaneous, no correlation; the target)
Steady state (simT 0.10), `-dtconv` SLHIST/RELROW. The target is the SHAPE of the signed-load
distribution, especially its negative tail (what the catch exponential reads):

| dt | bound | off-rate /s | fAtRelease pN | p10 pN | fracNeg | release-weighted load |
|---|---|---|---|---|---|---|
| 1e-5 (calib., overshot) | 400 | 427 | −4.5 | −4.2 | 0.50 | **deep tail: 18–65 % in the <−8 pN bin** |
| **2e-6 (converged ref)** | **1093** | **175** | **−1.84** | **−2.20** | 0.45 | spread −6..0, peaked −1; **0 % below −8 pN** |

2e-6 popBins[−8,−6,−4,−3,−2,−1,0,1,2,3,4,6,8] = `0,1,13,41,81,165,193,224,179,110,52,33,1,0`;
release-weighted = `0,.02,.11,.165,.185,.213,.139,...` (bounded at ~−6 pN, peaked near −1). The
converged off-rate (175/s, matching `RELEASE_FORCE_INPUT` Stage 1) is carried by a TIGHT negative tail.
The 1e-5 overshoot's off-rate (427/s) is carried by a DEEP <−8 pN tail of over-stretched bonds — the
∝dt explicit overshoot. **This deep-tail-vs-tight-tail contrast is exactly what an α at coarse dt would
have to close.**

## STAGE 2 — α-sweep at dt=1e-4 (the target dt), cap OFF: TOTAL COLLAPSE at every α
| α | bound | meanF pN | p10 pN | verdict |
|---|---|---|---|---|
| 0.00 | 9 | +216 | −65 | catastrophic |
| 0.30 | 7 | −35 | −162 | catastrophic |
| 0.50 | 5 | −59 | −313 | catastrophic |
| 0.70 | 10 | +16 | −139 | catastrophic |
| 0.85 (≈anchor) | 8 | −26 | −285 | catastrophic |
| 0.95 | 7 | −54 | −382 | catastrophic |
| 0.99 | 8 | +299 | −4 | catastrophic |

(ref: bound 1093, loads in ±6 pN.) **dt=1e-4 is unreachable at any α.** This is the predicted
**deterministic spring-stability ceiling:** the explicit overdamped cross-bridge step has multiplier
(1 − k_bond·dt/γ_head); at 1e-4, `k_bond·dt/γ_head = (1e-3·1e-4)/1.885e-8 = 5.3 ≫ 2`, so the spring is
UNCONDITIONALLY unstable — it diverges deterministically, and noise correlation (which only touches the
stochastic excitation) cannot fix a deterministic divergence. **Cap ON** (`stage2cap`) bounds the NaN
(fmgMax ~57 pN, no NaN) but does NOT rescue the count (bound 10–16) — confirming the collapse is the
spring instability, not a force-distribution detail the cap can clip.

## CEILING — α-rescue vs the stability ceiling (2e-5…5e-5, cap OFF): no rescue
The deterministic ceiling is `dt < 2γ_head/k_bond = 3.8e-5`. Below it the spring is stable, but binding
has **already collapsed for a SECOND reason** (the catch explosion, `DT_CONVERGENCE`: 2e-5 → bound ~18,
off-rate ~6900/s). α does not rescue this either:

| dt | α | bound | off-rate /s | p10 pN |
|---|---|---|---|---|
| 2e-5 | 0.00 | 21 | 6902 | −5.5 |
| 2e-5 | 0.51 (anchor) | 21 | 7031 | **−8.3 (worse)** |
| 3e-5 | 0.00 | 18 | 6944 | −80 |
| 3e-5 | 0.61 | 5 | 13889 | −18 |
| 5e-5 | 0.00 | 16 | 5904 | −24 |
| 5e-5 | 0.73 | 12 | 5952 | −82 |

Collapsed at every dt≥2e-5 regardless of α (bound 5–21 vs 1093); α never lifts the off-rate toward 175
and often worsens the tail. **Correlation does not extend the cross-bridge dt ceiling.**

## WORK15 — the decisive test: α-sweep at the WORKING dt=1e-5 (where there is a real population to reshape)
At dt=1e-5 the system functions (bound ~440, overshot). Does α tighten it toward the 2e-6 converged
reference (off-rate→175, p10→−2.2)? **NO — α MONOTONICALLY WORSENS it:**

| α | bound | off-rate /s | p10 pN | fAtRelease pN |
|---|---|---|---|---|
| 0.00 | 440 | 432 | −3.75 | −5.4 |
| 0.20 | 373 | 493 | −3.47 | −10.6 |
| 0.35 (≈anchor) | 373 | 528 | −4.46 | −5.7 |
| 0.50 | 331 | 563 | −3.60 | −5.8 |
| 0.70 | 348 | 635 | −4.26 | −11.6 |
| 0.90 | 273 | 672 | −3.91 | −5.1 |
| **2e-6 ref** | **1093** | **175** | **−2.20** | **−1.84** |

The off-rate rises 432 → 672 (away from 175); bound falls 440 → 273 (away from 1093). The marginal width
(meanF ~0.2–0.3 pN, p90 ~4 pN) is PRESERVED across α — so the variance-preserving formulation works as
designed (temperature unchanged; the relative-mode variance IS attenuated, per `Var(Δr) = 2dt[D_h+D_f −
2α√(D_hD_f)]`, linear in α). **But attenuating the thermal relative variance does not attenuate the
deterministic negative excursions** — and the noise restructuring slightly worsens the catch-rectified
tail. Decisive: the relative thermal noise is a MINORITY of the relative excursion at the cross-bridge dt
ceiling.

## (b) STIFFNESS-RATIO ANCHOR — the premise the data refutes
`α_pred = k_bond/(k_bond + k_eff)` with `k_eff = γ_head/dt` (the drag-per-step the explicit step fights;
the per-step numerical stiffness): `α_pred = k_bond·dt/(k_bond·dt + γ_head)` = **0.35 @1e-5, 0.73 @5e-5,
0.84 @1e-4** (k_bond = myoSpring = 1e-3 N/m; γ_head = 6π·aeta·R_head = 1.885e-8 N·s/m). This is a clean,
physically-meaningful prediction — but it is the predicted correlation **IF the relative thermal noise
were the channel.** Empirically the best α is **0 at every dt** (any α>0 worsens or fails to rescue), so
the anchor's premise does not hold: there is no positive α to match it to. The anchor's true content is
the same algebra read as the **stability** criterion `k_bond·dt/γ_head < 2 ⇒ dt < 3.8e-5` — and THAT is
borne out exactly (1e-4 unconditionally collapses; the operative ceiling is deterministic, not thermal).

## (c) dt-WEAKNESS — vacuously "constant" (best-α = 0 at all dt)
Because no α improves the distribution at any dt, best-α = 0 across 1e-5 / 5e-5 / 1e-4 — trivially
dt-independent but meaningless (the lever doesn't engage). The task's hypothesis (best-α a weakly
dt-dependent stiffness ratio) is not testable because the mechanism it presupposes is absent.

## Why it fails (the mechanism, stated)
1. **The dominant negative excursions are deterministic, not thermal.** The catch off-rate is set by the
   stiff spring's one-step-stale response to the geometric/stroke-driven relative offset over a coarse
   step — present even with zero thermal noise. Correlating the thermal kicks leaves this untouched.
2. **The correlation reaches only one of several relative-coordinate channels.** It correlates the head's
   TRANSLATION to the segment's RAW THERMAL draw. The relative coordinate also moves via (a) the head's
   and segment's ROTATION (uVec wobble — uncorrelated), and (b) the segment's NON-thermal motion (chain
   F3/F4 + the cross-bridge reactions of all OTHER bound motors on that segment — uncorrelated with its
   own thermal draw by construction, care-point #1). At coarse dt these dominate the segment's actual
   displacement, so the head's correlated kick tracks a minority of the foot's real motion.
3. **Net effect:** temperature preserved, relative thermal variance reduced, but the catch-rectified
   negative tail unimproved (cap-OFF) or mildly worsened — the modest noise restructuring is not aligned
   with the deterministic excursion it would need to cancel.

## CPU≡GPU + stability
- **CPU≡GPU (the new kernel adds no divergence).** `-cmp` (bondcorr 0.35, cap off): step-0 bound-set
  Δ=0, max|Δcoord| = 7.4e-5 µm (float32 last-bit); thereafter the chaotic, release-bearing trajectory
  decorrelates by Lyapunov divergence (max|Δcoord| 0.59 µm by step 300, aggregate bound CPU 217 / GPU
  201) — the §8 / CLAUDE CPU≡GPU standard for this scene (identical to the `-tauavg` path's behavior;
  the EMA/correlation is computed identically on both runners, the divergence is the pre-existing
  force-threshold stochastic-release decorrelation). The α=0 verify is bit-identical on each runner.
- **Stability.** No NaN at dt≤1e-5 (any α). dt≥2e-5 collapses (binding ~18) and dt=1e-4 diverges
  (loads to ±380 pN cap-off; cap-on bounds to ~57 pN but the count still collapses) — INDEPENDENT of α.
  Correlation neither adds nor removes a stability mode.

## Generalizes to crosslinkers (the principle + the port) — with the same caveat
The crosslinker (bound to TWO filaments) has the identical over-counted-relative-noise structure, and the
mechanism ports cleanly: a `correlateBoundLink` would recompute BOTH bound segments' raw draws and blend
the link-endpoint noise toward them (two-ended; or correlate each filament's draw toward the other's).
The infrastructure is trivial (the same recompute-the-partner's-raw-draw self-write, no gather). **But
this study's verdict transfers as a caution, not a recipe:** if a stiff crosslinker's coarse-dt force
tail is likewise dominated by the deterministic spring overshoot rather than thermal relative noise (the
crosslinker spring is damping-limited / dt-independent in relaxation — §5a — so it may be MORE thermal
and LESS overshoot-dominated than the myosin cross-bridge; that is the open question), correlation would
again be a temperature-preserving no-op-or-worse. **Test the channel decomposition first** (is the tail
deterministic-overshoot or thermal-relative?) before building the crosslinker version. **Noted future
complication (unsolved):** at higher density MULTIPLE heads/links bind one filament ⇒ naive pairwise α
over-correlates the shared filament (each pair pulls the filament's noise toward a different partner) —
clean for the single-motor-per-segment case validated here, harder at ring scale.

## Axis-projected variant (the noted design option) — not built; would not change the verdict
An axis-projected version (share only the bond-AXIAL component, since F8 reads (head−foot) along the
bond) was noted as cleaner if isotropic over-correlated the transverse modes. It was not built: the
isotropic result fails not by transverse over-correlation (the marginal width is preserved at every α —
no over-correlation signature) but because the dominant excursion is deterministic. Projecting onto the
axis would reduce the (already minority) thermal channel further — it cannot reach the deterministic
overshoot that sets the tail. Flagged for completeness; not the lever.

## Reproduce
```
./run_bondcorr.sh verify      # α=0 byte-identity (no-flag ≡ -bondcorr 0) — the regression guard (PASS)
./run_bondcorr.sh stage1      # fine-dt SLHIST reference (1e-5 overshot + 2e-6 converged)
./run_bondcorr.sh stage2      # α-sweep @ dt=1e-4, cap OFF (total collapse at all α)
./run_bondcorr.sh ceiling     # α vs the stability ceiling (2e-5..1e-4; no rescue)
./run_bondcorr.sh stage2cap   # α-sweep @ dt=1e-4, cap ON (cap bounds NaN, count still collapses)
# the decisive working-dt test (1e-5 α-sweep vs the 2e-6 reference) + CPU≡GPU: RUN_LOGS/bondcorr_work15.txt
```
Instrument: `V2OneXHarness -bondcorr <α>` + `SLHIST` (both flag-gated, default-off; with neither set the
harness is byte-identical to the committed benchmark). New file: `BondThermalCorrelationSystem.java`;
additive `MotorStore.corrParams`, the `bondCorr` task in `blkStruct` + `cpuStep`, and the `slHist`
readout. Raw: `RUN_LOGS/2026-06-25_bondcorr.txt`, `_work15.txt`.

## Bottom line for the thesis (§9)
The release-wing dt ceiling is **not** rescuable by a noise-side constraint-aware thermostat: the
coarse-dt negative-load tail is a DETERMINISTIC explicit-stiff-spring artifact, and the only lever that
reaches it is **sub-stepping the stiff cross-bridge** (integrate F8 + the catch-slip release at a small
inner dt under a larger outer dt), confirming the `RELEASE_FORCE_INPUT` / `DT_CONVERGENCE` conclusion
from a second, independent direction. Two attacks on the release wing (force-averaging, noise-correlation)
have now both failed for the same root reason — the instantaneous force is the correct physical input and
the defect is purely that the explicit integrator computes it wrong — which sharpens the case that
sub-stepping is the indicated and remaining lever.
