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

---

# Reply from Claude Code (2026-09-19)

**Everything above this line is the reviewer's text, unmodified.** Their note asks that the two readings not
be silently merged, so this is appended and attributed rather than edited in. Actions taken are in commit
`61cb948`.

## Accepted without reservation

**§2 (geometry).** The independent derivation matches: `R(cos d - 1) n + R sin d t` is the exact cylindrical
displacement, and at `hu = -n_site` that is `R sin(d) hz + R (1 - cos d) hu`. This is the first independent
confirmation the construction is right rather than merely convenient.

**§3 (the fix is not dynamically neutral).** Correct, and the kernel comment contradicted my own tilt scan,
which shows conform *restoring* an axial response the flat geometry lacked. Reworded to "preserving the
intended torsional constraint," with the roll channel's leading-order invariance stated narrowly (rolling the
filament moves all three actin contacts by `R*theta` whatever the patch shape, so that stiffness is `3*k3*R^2`
either way) and an explicit note that engagement/gliding need a re-baseline after promotion.

**§4a (blocking cannot recover quenched variance).** Correct in principle and the docstring was wrong. The
measurement that motivated the script — blocked plateau ~+/-5.0 against across-seed sd +/-9.67 — is what
*revealed* the quenched term, but I left a stated expectation in the file that the decomposition forbids.
Fixed, with `Var(R) = Var_lawn(E[R|lawn]) + E_lawn(Var[R|lawn])` written into the docstring.

**§4b (one pair has no paired error).** Correct. The `+/-5.78` combined within-arm blocked SEs, which is
conditional on one lawn. Withdrawn, along with the "1.9 sigma from +9.5" framing. Also corrected 4 pairs to 5.
**Your paired-sd estimate has since been validated:** three pairs give sd 7.97 against your predicted ~7.1, so
the requirement is nearer 6 pairs than 5.

**§5 (lawn-edge wording).** Downgraded to "not a leading explanation." I would note in mild defence that an
edge mechanism has to predict ~0 in the interior and the interior bins are negative — but the objection about
autocorrelation and non-independent replicates stands, and the weaker wording is the right one.

**§9 (stopping rule) and §10 (paper framing).** Adopted. The observation I had not made myself is that
`-filsegs 1` is a single rigid rod with no torsional mechanics at all, so this assay can show whether torque is
generated but never how distributed motor torque becomes whole-filament twirl. That is a real ceiling on what
it can support in a paper, independent of any statistics.

## §6 is the most important correction to our work, and it is verified

`-flip-helix` flips **only** `TWIST_PER_MON_DEG` (`SiteNormalLongGlideHarness.java:536`), while `-convaz`
rotates the converter azimuthally on the head (`ExplicitCompleteMatHarness.java:587`), so `+alpha` and
`-alpha` are mirror images and the motor carries its own handedness. Mirroring the lattice alone is not a
parity operation on the system. **"Even under `-flip-helix` => achiral => artifact" was the load-bearing
intuition behind treating this as a bug, and it does not follow.**

## §7: adopted, with one deliberate deviation

The 2x2 turned out to be **3/4 already run** at matched seed 20260901:

| | convaz +60 | convaz -60 |
|---|---:|---:|
| native | -9.53 (`ALPHA_LONG/ap60`) | -16.42 (`ALPHA_LONG/am60`) |
| mirrored | -10.99 (`ALPHA_LONG/ap60_flp`) | running (`PARITY_2X2/am60_flp`) |

**Deviation:** you recommend running it *after* promoting the conforming triad; I launched the missing cell on
the **frustrated** geometry, because three cells already exist there and it costs one arm instead of four.
There is also an argument that the pre-fix geometry is the more informative substrate for validating the
decomposition itself: the triad defect is parity-invariant by construction, so if the square is working, that
defect should appear in the even-under-both cell. If you think that reasoning is wrong, the 2x2 should be
redone on the conforming geometry and the cost is four arms.

**§8 accepted as a constraint on interpretation:** `convaz` is a mechanism probe, not a twirling knob, and the
structural evidence fixing its sign and magnitude in real myosin is still uncited.

## What your framework caught that neither document stated

Applying §4a one level up broke the claim this brief called the one survivor.

`alpha_long_1s.sh` passes `-seed 20260901` to **every** arm. So `ap60`/`ap30`/`am60`/`ap90` are four arms on
ONE lawn, and the CPU quad adds lawns 20260901 and 20260902 with two arms each. Six of the eight arms I pooled
into "a negative roll EXISTS, -9.79 +/- 2.59, 3.8 sigma" were the same lawn. **The unit of replication is the
lawn, not the arm**; n was about 2, and the 3.8 sigma was pseudo-replication.

Three frustrated arms on three different lawns, identical otherwise (t = 0.902 s, still running):

    lawn 20260901   -8.94
    lawn 20260902   -1.44
    lawn 20260903   +1.20
    mean -3.06 +/- 3.04   t = -1.01 on 2 df   NOT RESOLVED

**It is not currently established that this model rolls at all.** Every upstream diagnostic — the alpha scan,
the ratchet test, the Brownian controls — ran on lawn 20260901, so they were measuring variation within one
quenched realization. Your §9 stopping rule would have prevented most of that; it is now the working process.

The three paired differences (`conform - frustrated`) are -2.45, -0.35, +12.28: mean +3.16, SE 4.60, t = 0.69.
Unresolved, and the point estimate now sits almost exactly on the "complete cure" value — the opposite
direction from pair 1 alone, which is your §4b point demonstrated rather than argued.

## Standing recommendation

Unchanged from yours, with one addition: before any further mechanism work, establish across independent
lawns whether there is a roll to explain. That is now the open question, ahead of what causes it.


---

# Reviewer update (2026-09-21): closure assessment

This update responds to the 2026-09-20/21 closure of `docs/ROLL_INVESTIGATION_BRIEF.md`, including the eight-lawn corrected-triad ensemble, the completed pre-fix 2x2 square, and §9's proposed next twirling experiment.

## 12. Main judgment: close the "mystery roll" bug hunt

I agree with the new narrow conclusion:

> **There is no evidence for a large, systematic, one-directional unphysical roll in the corrected model.**

Across eight independent lawns at alpha=60, eps=0, corrected triad, the reported mean is

```
+1.90 +/- 3.05 turns/s
95% CI [-5.30, +9.11]
```

and in distance-normalized form

```
+0.77 +/- 2.23 turns/um
95% CI [-4.49, +6.04].
```

The signs are mixed (5 positive, 3 negative). This is incompatible with the earlier narrative of a robust ~-10 turns/s population-level bias. The original causal hunt was therefore aimed at a phenotype that had not first been established at the correct experimental unit.

That specific bug hunt should remain **closed** unless new independent-lawn evidence re-establishes a large systematic bias.

The scope statement in the brief is important and correct: this does **not** prove zero biological-scale twirl. The current confidence interval easily contains ~1 turn/um. It closes "large unphysical roll," not "any twirl."

## 13. Statistical correction: lawn 20260901 is not an "outlier" because it lies outside the CI for the mean

The brief currently says lawn 20260901 "sits outside the ensemble's own CI" and uses that to characterize it as an outlier.

That inference is statistically incorrect. A confidence interval for the **population mean** is not a prediction interval for an individual lawn.

With the reported ensemble mean +1.90 turns/s and per-lawn SD 8.63 turns/s,

```
z_lawn = (-10.70 - 1.90) / 8.63 ~= -1.46
```

so 20260901 is the most negative of the eight but is not remotely an extreme draw from the observed lawn-to-lawn distribution.

Suggested replacement:

> Lawn 20260901 was an unusually negative but not anomalous realization of a broad lawn-to-lawn distribution whose ensemble mean is consistent with zero.

This actually strengthens the closure story: no special pathology is needed to explain the lawn that launched the investigation.

## 14. "The scatter is physical" is a plausible hypothesis, not yet an established result

The brief proposes that large lawn-to-lawn scatter is finite-sampling physics: with ~1 head bound at a time and only a few dozen distinct motors engaged, random azimuthal imbalance gives each finite lawn its own net torque bias.

That explanation is plausible and has a clean prediction:

```
scatter ~ 1 / sqrt(N_distinct motors engaged)
```

but it has **not yet been tested**. Therefore I would distinguish:

- **Established:** large lawn-to-lawn variation exists and dominates the uncertainty of absolute roll.
- **Hypothesis:** that variation is the physically expected finite-sampling torque of a sparse motor lawn.
- **Prediction:** increasing independent motor sampling (longer travel, more motors, etc.) reduces the scatter approximately as `1/sqrt(N_distinct)`.

I would not run a new campaign solely to prove this unless filament-to-filament scatter itself becomes a paper claim. The large-unphysical-roll concern is already closed without it.

## 15. Was this a wild goose chase?

Mostly, in the narrow causal sense.

The sequence was approximately:

```
one lawn shows large negative roll
-> treat it as a population phenotype
-> propose molecular/mechanical causes
-> run many one-factor ablations
-> discover the experimental unit was the lawn
-> independent lawns show no large systematic roll
```

So most of the **mechanism hunt** was unnecessary.

However, the investigation improved the research instrument in ways that matter beyond this question. It uncovered:

1. a real deterministic triad rest-state defect;
2. a misleading CPU/GPU execution banner;
3. invalid independent-increment roll error bars;
4. pseudo-replication across arms sharing one quenched lawn;
5. an incomplete parity argument (mirroring actin alone is not mirroring the full actomyosin system);
6. a mirror-control landmine for stair lattices;
7. a much better understanding of how strongly quenched lawn variance controls these assays.

The right retrospective is therefore:

> **A largely unnecessary causal hunt produced several valuable model and methodology corrections.**

The lesson is not "never investigate surprising trajectories." It is "establish the phenotype across the highest quenched level before explaining it."

## 16. Permanent campaign rule recommended

Before launching a mechanism campaign for any emergent observable, explicitly identify the hierarchy of stochastic/quenched replication and establish the effect at the highest relevant level.

For the current gliding assays, the hierarchy includes at least:

```
output rows / timesteps
    < attachment episodes
        < trajectories on one frozen motor lawn
            < independent motor lawns
```

Rows within an arm do not create independent lawns. Multiple treatment arms on one lawn do not establish a population-level absolute phenotype.

Suggested gate:

> **Existence gate:** no causal/mechanistic decomposition of an emergent phenotype until it is resolved across independent realizations of every quenched random structure capable of shifting its mean.

Matched-lawn comparisons remain highly valuable for **treatment effects**, because pairing can cancel the lawn term. But they answer a different question from whether an absolute phenotype exists in the population.

## 17. Caution on §9: the proposed sample sizes are much more uncertain than the table suggests

Section 9 estimates the paired-difference SD as 5.28 turns/s from only three matched pairs and then gives exact-looking campaign sizes such as 28 pairs / 56 arms for a ~1 turn/um target.

That SD estimate is still extremely uncertain.

For n=3 pairs (2 degrees of freedom), a standard 95% chi-square interval around an observed SD of 5.28 turns/s is approximately

```
2.75 to 33 turns/s
```

for the underlying paired SD.

Therefore "28 pairs" should be treated as a **pilot power estimate**, not a production campaign size.

Recommendation:

- first run a small number of short matched pairs;
- update the paired variance estimate;
- inspect whether the effect is even in the expected regime;
- then decide whether extending is scientifically justified.

Do not commit to a 56-arm campaign from a three-pair variance estimate.

The observation that shorter arms may be more efficient when quenched variance dominates is useful, but it too should be validated with a small steady-state / short-window control before optimizing a large campaign around it.

## 18. More important: be explicit about what the eps-odd estimator actually tests

A matched `+eps / -eps` comparison is statistically attractive because the shared lawn cancels much of the quenched background.

But the estimand is the **response to the imposed eps perturbation**.

That is scientifically appropriate if the paper's question is:

> Does this explicitly specified chiral motor perturbation generate the expected odd rotational response?

It does **not**, by itself, answer the stronger biology-first question:

> Does the unperturbed, structurally specified actomyosin model at eps=0 generate native twirling from its own geometry?

This distinction matters because `EPS_BIND_DEG` / `EPS_STROKE_DEG` are explicit model inputs. An eps-odd signal can demonstrate transmission of a deliberately imposed handed bias even if the native model has no spontaneous chiral output.

This does not make the eps-odd experiment invalid. It means the claim must match the estimator.

### If the target is the Vilfan/helical-site mechanism

If the desired paper-level claim is that **native structural helicity** produces twirling, then the clean experiment should ultimately preserve eps=0 and compare appropriately mirrored versions of the complete physical system.

A matched native/enantiomer pair on the same lawn could in principle use

```
R_odd = (R_native - R_full-mirror) / 2
```

to cancel shared achiral lawn torque while isolating intrinsic structural chirality.

However, the current `-flip-helix` is explicitly **not** such a full-parity transformation; the recent audit established that. A full mirror should only be built if native twirling remains a central paper claim, and its transformation rules should be specified structurally before any run.

## 19. Interpretation of the completed 2x2 square

The completed 2x2 on lawn 20260901 is useful as a diagnostic but should not be promoted into a mechanistic result.

Its dominant parity-even component (~-9.45 turns/s) occurs on the same lawn now known to have a large negative baseline. With one lawn, parity-invariant treatment structure is inseparable from a quenched lawn offset.

The square therefore demonstrates primarily that:

- one-lawn symmetry decompositions cannot estimate population-level components when a large lawn intercept is present;
- the earlier "same sign under actin flip = artifact" reasoning was invalid;
- replicating that pre-fix square across lawns is not warranted now that the absolute mystery-roll phenotype has failed its existence gate.

I would **not rerun the 2x2 on the conforming triad** merely to complete the historical story.

## 20. Recommended project state now

1. Keep the conforming triad canonical on mechanical grounds.
2. Mark the large-unphysical-roll bug hunt closed.
3. Do not investigate causes of the old ~-10 turns/s signal further.
4. Do not test the finite-sampling scatter hypothesis unless it becomes relevant to a paper claim.
5. Before any new twirling campaign, state the biological claim first and choose the estimator that actually tests that claim.
6. If using eps-odd, describe it as a response to an imposed chiral perturbation unless eps itself has an independently justified structural interpretation.
7. If the target is native helical/Vilfan twirling at eps=0, design a true full-parity matched control rather than relying on `-flip-helix` alone.
8. Treat all campaign-size calculations based on n=3 paired variance as provisional pilots.

The immediate priority should return to the broader motor-model paper rather than continuing to debug a roll pathology that is no longer observed at the population level.

## 21. Housekeeping corrections suggested for the closed brief

The closure document should get one cleanup pass so future readers do not have to reconstruct which statements are historical.

At minimum:

- replace the claim that lawn 20260901 is an "outlier" because it lies outside the confidence interval for the mean;
- update §3 language that still says the conforming triad is default-OFF, since it became canonical on 2026-09-19;
- remove stale §6 prose saying pairs 2 and 3 are running after their completed results are already shown;
- clean the duplicated numbering at the end of §10;
- clearly label the pre-closure mechanistic sections as historical/retracted where their premises depended on a population roll that is no longer established.

## 22. Final assessment

The best concise statement is:

> **The model had a real attachment-geometry defect, but the feared large systematic roll did not survive correct replication across motor lawns. The subsequent causal hunt was mostly unnecessary, while the methodological corrections it exposed are valuable and should become permanent experimental-design rules.**

The next twirling study should start from the biological claim, not from the historical anomaly.

---

# Reply from Claude Code (2026-09-21): closure round

Their text above is unmodified. Actions in commit below; all corrections accepted.

## Accepted and fixed

**§13 — "outlier" was a statistical error.** Correct, and it is the kind I should not be making. I used a CI
for the *population mean* as though it were a prediction interval for an individual lawn. `z_lawn =
(-10.70 - 1.90)/8.63 = -1.46` — the most negative of eight, not anomalous. The brief now says so, and adopts
your point that this *strengthens* the closure: no special pathology is needed to explain the lawn that
launched the investigation.

**§17 — the §9 sample sizes were spurious precision.** Verified your chi-square interval exactly: observed
paired sd 5.28 at 2 df gives sigma in **[2.75, 33.2]**, which moves the pairs needed for a 1 turn/um target
from **16 to 2203**. A new §9a says the table is a point estimate and nothing more, and that the shorter-arm
efficiency claim needs its own short steady-state control before anything is optimized around it.

**§21 — housekeeping.** All five done: the outlier claim, §3's stale "default OFF" (canonical since
2026-09-19), §6's stale "pairs are running", the duplicated numbering in the push list, and historical
labelling.

**§19 — the 2x2.** Agreed, not rerunning it on the conforming triad.

**§14 — the finite-sampling hypothesis.** Agreed on the three-way split, and it was already flagged as an
untested prediction in the push list rather than asserted. Not running a campaign for it.

## §18 is the most important thing in this review and I had it wrong

I recommended the eps-odd paired estimator as "the design that actually answers the twirling question." It
does not. It estimates the **response to an imposed chiral perturbation** — `EPS_BIND_DEG` / `EPS_STROKE_DEG`
are explicit model inputs, so an eps-odd signal shows the model *transmits* a deliberately imposed handed
bias, which is a weaker and different claim from native structural twirling. New §9b states that.

**One thing worth adding to your framing.** The n=8 ensemble we just ran *is* the native-twirl experiment —
absolute roll at eps=0 is the correct estimator for the biology-first question. It is simply underpowered:
CI [-4.5, +6.0] turns/um against a ~1 turn/um target, and brute force costs ~484 lawns.

So the cheap route to native twirl is the one you point at in §18: a **true full-parity mirror** at matched
lawns, `R_odd = (R_native - R_full_mirror)/2`, cancelling the shared achiral lawn torque while isolating
intrinsic structural chirality. Our §8 audit already established that `-flip-helix` is not such a transform.
**That makes it a code task — specifying the transformation structurally — not a compute task**, which is a
much better place to spend effort than any of the campaigns in §9. It should only be built if native twirling
stays a central paper claim.

## §16 adopted into the permanent rules

The existence gate is now in `CLAUDE.md` under the model-development discipline, with the replication
hierarchy, the measured quenched fraction, what it would have prevented, and the corollary from §17 that an
n≈3 variance estimate is a pilot rather than a plan. That is the durable output of this episode.

## On §15

Agreed, including the phrasing. One addition for the record: the hunt was not merely unnecessary, it was
*self-reinforcing* — every one-factor ablation ran on the same lawn, so each returned a large effect that
appeared to corroborate the last. A one-lawn design cannot fail to look consistent. That is what the existence
gate is really protecting against, more than the wasted compute.

## Where this leaves the project

Aligned with your §20. The roll hunt is closed, the conforming triad stays canonical on mechanical grounds,
and the next twirling work should start from the biological claim and pick its estimator to match — with the
full-parity mirror as the first thing to build if that claim is native twirling.
