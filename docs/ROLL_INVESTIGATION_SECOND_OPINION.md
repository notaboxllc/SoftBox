# Independent reviewer note: unexplained filament roll

**Date:** 2026-09-18  
**Branch reviewed:** `gpu-mat-bottlenecks-explicit-singlehead`  
**Purpose:** independent second opinion after reading `docs/ROLL_INVESTIGATION_BRIEF.md`, `JOURNAL.md`, the site-normal motor documentation, the triad kernel, the roll estimator, and the current paired-run design.

This note intentionally does **not** replace `ROLL_INVESTIGATION_BRIEF.md`. It records an independent reading so that Claude Code and jba can compare interpretations without silently merging them.

## 1. Main judgment

The project should treat two questions as separate:

1. **Is the surface-triad attachment mechanically well posed?**
2. **What causes the residual filament roll?**

The answer to (1) is clear: the legacy flat head-side triad is mechanically inconsistent with the curved actin-side contact geometry. Three zero-rest springs intended to represent a relaxed attachment interface should possess a reachable zero-energy state. The current `-triad-conform` construction fixes that defect and is geometrically sound.

The answer to (2) is still open. The first matched pair now points in the direction that the triad defect is **not** the principal cause of the macroscopic roll. That is entirely compatible with adopting the conforming triad anyway. The justification for promotion should be **mechanical consistency**, not "it fixes twirling."

## 2. Independent check of the conforming-triad geometry

The actin-side tangential vertex offset is represented as motion along a cylinder of radius `R`. For a tangential arc displacement `offT`, define `d = offT/R`. Relative to the central surface site, the exact cylindrical displacement is

```
R (cos d - 1) n + R sin d t
```

where `n` is the outward radial normal and `t` is the local azimuthal tangent.

At the canonical bound pose the head axis satisfies `hu = -n_site`, so

```
R sin(d) * hz + R (1 - cos d) * hu
```

is exactly the same displacement. Therefore the implementation in `CrossBridgeSystem.bondForcesSurfaceTriad` is not just numerically convenient: it is the correct cylindrical counterpart of the actin-side arc geometry.

The old flat anchor `offT*hz` was not congruent to the actin-side triangle, hence the observed non-zero extension at the nominal relaxed pose is a genuine construction defect.

**Recommendation:** make the conforming geometry canonical once its basic regression/gliding compatibility check is complete, regardless of what happens to the unexplained roll.

## 3. Important wording correction: the fix is not dynamically neutral

The current kernel comments say that conforming the patch makes the rest state correct while "leaving the spring's response to relative motion untouched." That is too strong.

The correction preserves the **intended torsional constraint** and the small-angle roll channel can retain the same leading stiffness. But the off-equilibrium force/torque landscape is changed. The existing tilt scan already demonstrates this: the conforming geometry restores an axial restoring response that the flat geometry structurally lacked.

Suggested wording:

> The conforming construction corrects the attachment rest geometry while preserving the intended torsional constraint.

Do **not** describe it as dynamically or arithmetically neutral.

A small re-baseline of engagement and gliding after promotion is warranted. This does not justify another density campaign.

## 4. Statistical interpretation: paired seeds are the right design, but two claims need tightening

The central variance insight is sound: different seeds create different motor lawns, so substantial variance is quenched at the lawn level. Longer trajectories cannot average away variability between lawns. Matched-seed A/B comparisons are therefore much more efficient than extending single trajectories.

Conceptually,

```
Var(R) =
    Var_lawn( E[R | lawn] )
  + E_lawn( Var[R | lawn] )
```

Batch-means/blocking estimates the second term: stochastic uncertainty **conditional on a given lawn**. Independent seed/lawn replicates reveal the first term as well.

Two consequences:

### 4a. A single-arm blocked SE cannot recover quenched lawn variance

`scripts/roll_estimator.py` says that the blocked plateau from one arm should reproduce the across-seed scatter. If the lawn-dependent component is truly quenched, that cannot generally be true: a trajectory on one fixed lawn never samples the population of lawns.

Blocking is still useful for within-run uncertainty. It should not be presented as the full population-level uncertainty of an absolute roll rate.

### 4b. One matched pair has no population paired-error estimate

The updated brief reports Pair 1 as

```
frustrated  -9.53 turns/s
conform    -10.70 turns/s
difference -1.17 +/- 5.78 turns/s
```

The `+/-5.78` appears to combine within-arm blocked uncertainties. That answers a conditional question about stochastic noise within these two trajectories. It is **not** the uncertainty of the paired treatment effect across motor lawns.

With one pair there is no empirical paired-lawn variance. Therefore Pair 1 is useful directionally, but statements such as "1.9 sigma from the predicted +9.5" should not be treated as the decisive paired inference.

Wait for multiple matched lawns and compute the distribution of

```
Delta_i = R_conform,i - R_frustrated,i
```

across seeds.

Given the preliminary paired SD estimate of about 7.1 turns/s, an effect of 10 turns/s requires roughly

```
n >= (3*7.1/10)^2 = 4.54
```

under a normal approximation: **5 pairs, not 4**. With such small n, a paired-t interval is preferable to a z score.

This still implies a small experiment, not a campaign.

## 5. The updated brief overstates the lawn-edge conclusion

The new brief says "lawn-edge asymmetry tested and EXCLUDED as the roll mechanism" based on pooled per-row increments binned by `yMargin`.

That is suggestive evidence, especially because the negative sign appears in the interior. But it is not yet an exclusion test because:

- increments within one trajectory are autocorrelated;
- rows from the same lawn are not independent replicates;
- pooling rows across arms weights long trajectories/configurations rather than independent lawns;
- the same section acknowledges that the per-row error model is naive.

I would rewrite the conclusion as:

> Existing data do not show an obvious monotonic relationship between roll and lawn-edge proximity; edge asymmetry is therefore not a leading explanation.

That is strong enough for the evidence and avoids closing a mechanism prematurely.

## 6. The mirror experiment is not yet a complete parity test

This is the most important conceptual addition to the current brief.

The current roll configuration uses `-convaz 60`. In the harness, `convaz` is explicitly a **converter azimuth on the head**. Meanwhile `-flip-helix` mirrors the **actin lattice handedness**.

Therefore, if converter azimuth introduces another handed structural element, flipping only the actin lattice is not equivalent to parity-transforming the entire actomyosin system.

An even-under-`flip-helix` roll could arise from:

- an achiral numerical/model artifact;
- motor-side handedness encoded by converter geometry;
- an interaction between motor handedness and another unflipped structural element.

So "same sign under mirrored actin lattice = artifact" is too strong until the motor-side handedness is also tested.

## 7. Highest-value next experiment: a 2x2 handedness decomposition

After the conforming triad becomes the working attachment, run a matched-seed 2x2 design:

| | converter azimuth +60 deg | converter azimuth -60 deg |
|---|---:|---:|
| native actin helix | R++ | R+- |
| mirrored actin helix | R-+ | R-- |

Use identical lawns within each matched set.

This permits a symmetry decomposition into components that are:

- even/odd under actin handedness;
- even/odd under converter handedness;
- dependent on their interaction;
- invariant under both.

That localizes the source of the roll **before** proposing a microscopic mechanism.

Interpretation:

- If the residual changes sign with actin mirroring, it belongs to the lattice-chiral channel.
- If it changes sign with converter-azimuth reversal, motor geometry is supplying the handedness.
- If it depends on the product of the two signs, the effect is an actin-motor chiral coupling.
- If it survives reversal of both, then a parity-invariant artifact/rest-state defect becomes a much stronger suspicion.

This is more informative than another sequence of one-factor ablations such as Brownian off, stroke off, patch-radius changes, etc.

## 8. Biology-first guardrail on `convaz`

Before treating the +/-60 deg converter experiment as more than a mechanism probe, establish what structural evidence fixes the **sign and approximate magnitude** of converter azimuth in real myosin.

The project's own development discipline is correct here: structure should specify the geometry first; the simulation should then reveal the emergent behavior. Converter azimuth should not become an adjustable twirling knob selected because it gives a preferred roll.

The 2x2 experiment is still useful immediately as a **symmetry diagnostic**, even if +/-60 deg are not yet biologically canonical.

## 9. Recommended stopping rule for the current investigation

Do not continue open-ended mechanism hunting.

Use this sequence:

1. Finish the currently running frustrated-vs-conforming matched pairs.
2. Promote the strain-free triad because its rest geometry is correct, not because of its effect on roll.
3. Do a minimal post-promotion gliding/engagement check to ensure the established few-head glide survives.
4. Run the matched-seed 2x2 helix-sign x converter-sign decomposition.
5. Only then inspect the force/torque pathway corresponding to the symmetry component that is actually non-zero.
6. If a substantial component survives reversal of both handed elements, audit the remaining bond/coupling terms for reachable rest states before launching stochastic campaigns.

This explicitly prevents the recurring pattern documented in `JOURNAL.md`:

```
unexpected behavior
 -> mechanism story
 -> one noisy ablation
 -> apparent confirmation
 -> later structural check
 -> retraction
```

The replacement workflow is:

```
deterministic consistency
 -> symmetry classification
 -> matched-seed effect
 -> mechanism
```

## 10. Paper-level interpretation

Twirling should not currently be the load-bearing claim of the motor paper.

The site-normal model already has a valuable emergent-behavior story: explicit geometry + chemistry can generate sustained pointed-leading gliding in an intermittent few-head regime, with roughly 1-1.4 heads bound on average in the long assay, substantial periods with zero bound heads, physiological-scale forces, and no phenomenological prescription of translation.

Twirling can be framed as a stringent emergent test:

> Can structural handedness already present in actomyosin generate rotational filament motion without an imposed phenomenological twisting torque?

That becomes compelling only when:

- attachment geometry has a legitimate rest state;
- the sign of handed structural inputs comes from biology/structure;
- occupancy is in the experimentally relevant regime;
- the filament's torsional mechanics are physically represented;
- the resulting roll survives proper matched-seed and symmetry analysis.

The rigid single-segment filament is an excellent **debugging assay for torque generation**. It should not by itself supply the eventual quantitative biological twirling pitch, because it bypasses the torsional elasticity that maps distributed motor torque into whole-filament rotation.

## 11. Immediate recommendation

The updated first conform pair makes the triad-frustration hypothesis less attractive as the explanation of the roll, but it does not change the main recommendation.

**Finish the conform pairs, promote the mechanically valid triad, then perform the 2x2 handedness decomposition before any further causal ablation campaign.**

That is the shortest path to determining whether there is still a bug to hunt at all.
