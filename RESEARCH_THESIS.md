# RESEARCH THESIS & PUBLICATION FRAMING — the north star

> The organizing frame for the project's *scientific* purpose. Individual investigations (dt convergence,
> binding reformulation, the ECS/GPU performance arc) are means; this is the end they serve. Each CC task and
> findings doc should be able to name which layer of this it advances.

## 1. Purpose — manifold, but ordered
- **End goal: large-scale EMERGENT cytoskeletal behavior** — whole-cell contractile-ring formation via protein
  nodes, faithful to fission-yeast SCPR. A coarse model in which ring assembly/constriction *emerges* from the
  constituents, not from scripted choreography.
- **The ECS/SoA/GPU-residency work exists to make that scale reachable.** The speed and host-RAM wins (the
  performance arc) are not the point — they are the cost of admission to running emergence at biological scale.
- **But large-scale emergence is meaningless without small-scale validation.** An emergent ring assembled from
  *unvalidated* motors, crosslinkers, and filaments is pretty pictures, not science — it could emerge for the
  wrong reasons. A defensible emergence claim *requires* that each constituent first reproduce its own
  small-scale biophysics.
- **Therefore the near-term publishable output is small-scale CONSTITUENT VALIDATION** — and it is the
  stepping-stone that makes the eventual large-scale emergence claim defensible, not a detour from it.

## 2. The validation ladder (where each constituent stands)
| layer | status |
|---|---|
| **Actin biophysics** | calibrated (PAIRS) and **PUBLISHED** — the foundation is laid |
| **Motors (myosin)** | the **CURRENT focus** — the methods paper below. (Lineage: the 2015 secondary-author paper; the three-body motor is otherwise unvalidated in its current form) |
| **Crosslinkers** | validation needed along the way (next constituent) |
| **Large-scale emergence** | the end goal — rides on all of the above being validated first |

## 3. The motor methods-paper thesis (the "why" of the three-body motor)
**Claim:** an articulated **three-rigid-body** motor with **physically-grounded search / binding / unbinding**
reproduces experimental motor observables that a **prescribed-kinetics point motor** (Cytosim-style "Hand":
point attachment + force–velocity curve + fitted `k_off(F)`) **cannot — WITHOUT after-the-fact prescription.**

- **The bright line is "without further prescription."** If the three-body model only matches data after bolting
  on fitted force–velocity / load-dependent-rate curves, it has merely rebuilt the prescribed model with extra
  steps. The contribution is **emergence**: behavior that falls *out of the mechanics for free*, that the
  prescribed model must be *told*.
- **What counts as evidence is TRANSFERABILITY, not fit.** Any model with enough knobs fits one dataset. The
  thesis holds only if **one physically-meaningful parameterization matches a RANGE of conditions** (loads,
  filament geometries, motor densities) where the prescribed model needs **re-prescription per condition**.
  Emergence shows up as transferability across conditions from a fixed parameter set — *not* as goodness-of-fit
  to any single experiment.

## 4. Where the thesis lives: emergent MECHANICS under a calibrated, lumped bond (CORRECTED 2026-06-25)
**Correction.** An earlier draft of this section said "unbinding is where the thesis lives or dies" and posited
detachment EMERGING from three-body strain energy as the strong claim. That over-reached, and the
release-force-input study (`RELEASE_FORCE_INPUT_FINDINGS.md`) makes the boundary explicit:

- **Unbinding is NOT claimed to emerge — and that is by design, not concession.** Molecular detachment (a single
  cross-bridge's bond rupture) is **below the resolution of this coarse model**. The release is a **deliberately
  LUMPED, calibrated bond** — a phenomenological catch-slip rate `k_off(F)` tuned to experiment (optical-trap
  load-dependent detachment), not a resolved molecular process. A coarse motor *should* prescribe its detachment;
  pretending it emerges would be the over-claim a methods reviewer flags. The honest framing is the publishable
  one. (Cytosim also prescribes detachment — the difference is **what F the rate reads**, see below, not whether
  the rate is prescribed.)
- **The emergence claim lives in the MECHANICS / force generation, not in unbinding.** What falls out of the
  three-body articulated motor *for free* — and what a prescribed point motor must be *told* — is the **power
  stroke and the load-dependent force itself**: an emergent mechanical force from rod→lever→head + the
  nucleotide-gated rest-angle switch under strain, with the cross-bridge load `F` (the input to the lumped
  k_off) **computed from the body geometry** rather than supplied by a force–velocity curve. The transferability
  test of §3 is about *that* — one parameterization of the body reproducing force/velocity across loads and
  densities — not about an emergent detachment.
- **The goal for unbinding is therefore a dt-ROBUST lumped bond, not emergence.** The open problem the
  release-force-input work addresses is purely numerical: the lumped k_off reads a cross-bridge load `F` that the
  explicit stiff-spring integration over-drives ∝ dt, so the *calibrated* bond reads the wrong force at coarse
  dt. Making the bond dt-robust (a faithful, converged read of `F`) is what protects the calibration — it is a
  correctness requirement for the lumped bond, **distinct from** any (unmade) claim that detachment emerges.

## 5. Why the current dt / binding work is the methods FOUNDATION (not a detour)
A reviewer of a methods paper claiming emergent motor behavior asks two questions immediately:
1. **Is the result converged, or a timestep artifact?** → the **dt-convergence study** answers exactly this. The
   honest finding that *1e-5 is a calibration point matched to v1, not a converged fixed point* is **rigor that
   strengthens the paper**, not a weakness — and it is standard practice in the field (Nédélec advises producing
   exactly this output-vs-timestep curve).
2. **Is binding a physical process or a fitted knob?** → the **binding-search reformulation** (a physical
   per-sim-time encounter rate, not a dt-fragile per-step geometric test) makes binding *physical*, removing a
   prescribed knob. A fitted geometric search is prescription smuggled into the model the paper argues against.

So: dt convergence + binding reformulation + a **dt-robust lumped bond** (a faithful read of the cross-bridge
load the calibrated k_off consumes — §4) are not cleanup — they are what make the emergence claim (in the
MECHANICS, §4) *defensible*.

## 6. The decisive experiment (this comparison IS the paper)
Head-to-head, on the **same experimental datasets** (optical-trap force–velocity; load-dependent detachment;
gliding-assay velocity-vs-density):
- **Three-body motor** (physical search / emergent force / emergent-or-physical unbinding) vs
- **Prescribed-kinetics motor** (the Cytosim-Hand class).

**The observable is which reproduces the data ACROSS conditions from fewer free parameters / less per-condition
prescription** — transferability, per §3. Cleanly done, that comparison is the methods publication.

## 7. Honest stance — the null result is a real outcome
We do **not** yet know the three-body model outperforms prescription. That is the hypothesis, and the
project's standing discipline (*measure, don't infer*) applies to its own thesis. Possible outcomes:
- **(a)** it reaches behavior prescription cannot, without after-the-fact fitting → the strong result;
- **(b)** it recovers known behavior, but more physically / from fewer assumptions → a modest methods result;
- **(c)** it needs prescription anyway to match data → bounds the emergence, still an honest finding.
Run the experiment open to (b)/(c). A motivated fit to (a) would be exactly the self-deception this project has
repeatedly caught in smaller forms.

## 8. The spine — how every thread connects
```
ECS / SoA / GPU-residency (speed, RAM)  ──►  scale  ──►  large-scale EMERGENCE  ┐
                                                          (the end goal)         │ defensible only if ↓
dt-convergence + binding reformulation + unbinding-from-strain  ──►  MOTOR validation (methods paper) ─┤
crosslinker validation  ──►  crosslinker constituent  ───────────────────────────────────────────────┤
actin (PAIRS, published)  ──►  actin constituent (done) ──────────────────────────────────────────────┘
```
The constituents must be validated bottom-up; the emergence claim is the capstone that rests on them. The
performance work buys the *scale*; the constituent-validation work buys the *credibility*. Both are required;
neither suffices alone.

## 9. What this means for sequencing (implication, not commitment)
- The **binding-search reformulation** (physical encounter rate; Cytosim/MEDYAN-style rate+chord capture is
  borrowable here precisely because the *search* is model-class-agnostic — it is upstream of the motor
  mechanics) is on the critical path: a physical search is a *requirement* of the emergence claim, not an
  optimization.
- The **unbinding-from-strain** question is the highest-value scientific item — it is where §3's "prescription
  can't reach this" is won or lost.
- The **cross-bridge dt ceiling** (sub-step / implicit cross-bridge) is enabling engineering: a converged,
  trustworthy motor is a precondition for any emergence claim. NOTE: Cytosim's escape from the same stiff-spring
  ceiling — *prescribe the motor / constrain away the stiff mode* — is **not available to us**, because the
  stiff emergent mechanics are the model's reason to exist. Our levers stay inside the explicit-mechanics
  paradigm.
- Large-scale emergence (the flagship) remains the end goal and resumes once the motor constituent is validated.

## 10. The cross-bridge dt-ceiling: a methods contribution, and the Cytosim comparison it grounds

### 10a. The ceiling characterization is itself a result (five levers, mapped)
The cross-bridge `r = k·dt/γ` ceiling is **intrinsic to explicitly integrating a stiff spring whose load feeds an
exponential (catch) detachment rate**: at `r→1` the explicit step overshoots, the overshoot manufactures spurious
negative-load excursions, and the catch `e^(−F·xCatch)` detonates on them → binding collapse. We have now mapped
**five candidate cheap fixes, and all five fail** — which *cheap fixes don't work, and why* is a publishable
negative-result map for anyone building a mechanically-resolved motor:

| lever | kind | why it fails | report |
|---|---|---|---|
| softer myoSpring | force-law (magnitude) | detunes the motor (binding 100×, velocity non-monotonic) | `CROSSBRIDGE_STIFFNESS_SWEEP` |
| release-force averaging | rate-input | Jensen-collapses the catch, or keeps a pathological distribution | `RELEASE_FORCE_INPUT` |
| constraint-aware thermostat | noise-side | the overshoot is deterministic, not thermal; α worsens it | `BOUND_THERMAL_CORRELATION` |
| static saturation | force-law (magnitude) | overshoot & real force OVERLAP in magnitude; no instantaneous cap separates them | `SATURATED_CROSSBRIDGE_DIAGNOSTIC` |
| **parallel dashpot** | **force-law (velocity)** | **the explicit finite-difference velocity is dominated by the thermal random-walk `√(2D/dt)`; the anti-thermal force collapses binding** | `CROSSBRIDGE_DASHPOT` |

The first four are magnitude-based and fail for the same root reason (the cross-bridge force magnitude is
load-bearing and entangled with the overshoot). The dashpot was the one **velocity-discriminating** lever — not
killed a priori by the magnitude argument — and it fails for a *new* reason: an explicit damper on a Brownian
coordinate exerts a spurious force `≈γ_xb·√(2·kT·γ_head/dt)` (the random-walk velocity diverges as dt→0), which
cools the bound mode and collapses binding at every γ_xb, with no operating point at any dt. **All five converge
on the same conclusion: the headroom is in the INTEGRATOR (implicit / sub-step / semi-implicit dashpot), not in
any reshaping of the instantaneous force law.**

### 10b. This bottom-up re-derives why the field went implicit/constraint (Cytosim) — independent validation
This arc is a from-scratch re-derivation of Cytosim's design choice. The stiff cross-bridge mode has **no cheap
explicit fix** (five tried, five failed); the headroom must come from a better integrator of that sub-system —
which is exactly Cytosim's **implicit integration + stiff-bond-as-constraint**. That we arrived here by exhausting
the explicit-paradigm alternatives is independent confirmation that the regime is understood correctly, and it
sharpens §9's note: our levers stay inside the explicit-mechanics paradigm *by necessity demonstrated*, not by
assumption.

### 10c. The deeper trade — a coupling the prescribed model structurally cannot have
Cytosim's escape has a cost that is the thesis in miniature. **Prescribing detachment (the Cytosim-Hand class)
decouples detachment from the integration** — that is precisely what buys the timestep freedom — but it therefore
**cannot exhibit the emergent stiffness→load→detachment coupling** the mechanically-resolved motor has. The
**stiffness-sensitivity result** is the concrete demonstration: across 0.5–2 pN/nm the resolved motor's binding
spans 100× and its velocity is non-monotonic, *because* the cross-bridge stiffness feeds the load that feeds the
catch detachment (`CROSSBRIDGE_STIFFNESS_SWEEP`). A prescribed-detachment Hand has no cross-bridge stiffness in its
detachment law, so it **structurally cannot** show that sensitivity — a coupling the resolved model has and the
prescribed model lacks.

**CAVEAT (do not overclaim).** This demonstrates the *mechanism* of the difference, **not yet its experimental
superiority**. The honest current state: the resolved model has a real, consequential coupling the prescribed model
lacks; whether that coupling reproduces an experiment the prescribed model cannot is the open, **calibration-gated**
question (§6/§7) — it awaits the deferred calibration to experimental observables. A coupling that exists is not yet
a coupling that wins.

### 10d. Why cross-bridge-LOCAL, not global viscosity
Raising viscosity everywhere (global `aeta`) is a near-wash for the ceiling: it slows the head's overshoot but
equally slows diffusion and relaxation, partially self-cancelling per simulated second, and at unphysical viscosity.
The cross-bridge-local dashpot was the *targeted* version — drag on the stretch mode only, leaving the free head's
search diffusion untouched — which is why it was worth testing even though global viscosity is not. Its failure
(10a) is specific to the explicit finite-difference on a thermal coordinate, and is the reason the surviving form is
a *semi-implicit* local damper (an integrator change), not an explicit local force.
