# Motor Validation and Calibration Campaign

## 1. Check-in: what is already established

The model has moved beyond “plausible animation.”

The single-motor program has already shown that a naturally captured motor can produce a polarity-correct axial stroke near 7–8 nm and an externally observable whole-crossbridge stiffness near 0.63 pN/nm. A blinded optical-tweezers analysis recovered the hidden compliance-free step within about 1.3% and recovered stiffness within realistic uncertainty. The important outstanding weaknesses were **load dependence and kinetics**, which the blind assay could not identify reliably.

The sparse ensemble work also showed that several motors can share a trapped filament without illegal chemistry, force double-counting, persistent locking, or collapse of the individual stroke. In that assay, duty ratio remained low and approximately independent across motors, with only a small filament-mediated co-binding correlation.

The dimer GPU work now adds:

- a mechanically explicit two-headed HMM;
- full Brownian binding and chemistry;
- stable ensemble gliding;
- a density-dependent velocity curve with biology-like saturation;
- evidence that added motors become progressively less efficient because of internal opposition rather than binding saturation.

So the next campaign should not begin by tuning ensemble gliding. It should establish a **hierarchy of single-molecule and dimer constraints**, then allow ensemble behavior to remain a prediction.

---

# 2. Campaign principles

## Validation and calibration must be separated

A **validation assay** tests a prediction using the frozen model.

A **calibration assay** may change only parameters that are directly identifiable from that assay.

For example:

- working-stroke geometry may be calibrated against compliance-corrected stroke;
- mechanical spring constants may be calibrated against force–extension curves;
- `xCatch`, `xSlip`, and pathway rates may be calibrated against force-dependent lifetimes;
- binding-gate parameters may be calibrated against capture probability and accepted pose distributions;
- none of those should be changed solely because gliding speed is inconvenient.

The literature values must be treated as **condition-specific distributions**, not universal constants. Single-myosin studies have reported low-load displacements near 11 nm and isometric forces around 3–4 pN, while later work and compliance analyses support working strokes around 8 nm and substantial assay-dependent differences between observed bead displacement and intrinsic motor displacement. ([pubmed.ncbi.nlm.nih.gov](https://pubmed.ncbi.nlm.nih.gov/8139653/))

## Freeze the biological target

The first campaign should remain:

> **Fast skeletal-muscle HMM at the project’s standing temperature, ATP, ionic strength, and actin conditions.**

A nonmuscle myosin-II minifilament is a later biological target. It requires different kinetics and duty ratios, not merely a new tail geometry.

---

# 3. Single-motor laser-tweezers campaign

## SM1 — Realistic-only blinded mechanics holdout

This closes the remaining methodological debt from the earlier blind assay.

### Simulation

Generate a sealed package containing only instrument-realistic traces:

- no noise-free twins;
- no private event times;
- no chemistry labels;
- no motor coordinates;
- three trap stiffnesses;
- longer pre- and post-stroke dwells;
- stiffness perturbations lasting at least several dumbbell relaxation times;
- calibration, no-motor, bound-no-stroke, and stroke controls.

### Readouts

- zero-load compliance-corrected stroke;
- pre- and post-stroke whole-crossbridge stiffness;
- direction of motion;
- event-detection sensitivity and specificity.

### Gate

The approximately 8 nm intrinsic step and approximately 0.63 pN/nm stiffness should remain recoverable without privileged ideal traces. The earlier assay already indicates that they probably will.

This is validation only. No parameter changes unless the realistic-only analysis exposes a systematic mechanical mismatch.

---

## SM2 — Ultrafast constant-load working-stroke map

The highest-value missing mechanical test is load-dependent stroke completion.

Capitanio and colleagues used an ultrafast force clamp to resolve early interactions and found load-dependent branching between weak interactions, full strokes, and mechanically altered stroke outcomes. ([pubmed.ncbi.nlm.nih.gov](https://pubmed.ncbi.nlm.nih.gov/22941363/?utm_source=chatgpt.com))

### Simulation

Apply constant axial loads immediately after actin binding:

- assisting: −4, −2 and −1 pN;
- near-zero: 0 pN;
- opposing: +1, +2, +3, +4, +5, +6 and +8 pN.

Resolve the first 2 ms at high temporal resolution.

### Readouts

- latency from strong binding to movement;
- initial rapid displacement;
- final retained displacement;
- full-, partial- and no-stroke fractions;
- premature detachment fraction;
- mechanical work per event;
- Pi-release timing relative to force production;
- transient versus plateau force.

### Parameters identified

Primarily:

- converter target geometry;
- stroke transition timing;
- stroke reversibility;
- F8 and lever compliance.

Do **not** tune `xCatch` from the step amplitude alone.

### Gate

The model should show a coherent reduction or altered survival of productive strokes under opposing load, without creating an arbitrary hard stall threshold.

---

## SM3 — Isometric force development and force plateau

Early optical-trap experiments reported single-molecule low-load steps near 11 nm and isometric force transients around 3–4 pN. ([pubmed.ncbi.nlm.nih.gov](https://pubmed.ncbi.nlm.nih.gov/8139653/))

### Simulation

Use both:

- a high-stiffness passive trap;
- an active position clamp maintaining fixed actin position.

Begin from naturally captured ADP·Pi states and run complete cycles.

### Readouts

- force rise time;
- peak force;
- stable force plateau;
- force impulse;
- duration of the force-generating state;
- force at detachment;
- work and ATP consumption;
- distribution, not just the mean.

### Parameters identified

- whole-crossbridge stiffness;
- stroke amplitude;
- post-stroke retained strain;
- any load dependence of Pi release.

### Gate

The present near-stall result around 4.8 pN is plausible, but it should emerge under a realistic cycling protocol and remain consistent across trap stiffnesses rather than being a single prepared-state result.

---

## SM4 — Force-dependent attachment lifetime: the `xCatch` assay

This is the primary calibration assay for the catch–slip law.

Guo and Guilford measured skeletal actomyosin lifetimes under step loads from approximately 1.8 to 26 pN. Both rigor and ADP states showed catch–slip behavior, with lifetime maximized near 6 pN; ADP bonds were longer lived than rigor bonds near the optimum. They fitted a two-pathway force-dependent dissociation model—the provenance behind the project’s catch–slip formulation. ([pmc.ncbi.nlm.nih.gov](https://pmc.ncbi.nlm.nih.gov/articles/PMC1502541/))

### Simulation

Prepare separately:

- rigor actomyosin;
- actomyosin–ADP;
- normal cycling ADP attachments.

Apply constant loads:

- 0, 1, 2, 3, 4, 5, 6, 8, 10, 15, 20 and 25 pN.

Run enough events to resolve full survival curves, not merely mean lifetimes.

Also reverse the force direction relative to actin polarity.

### Readouts

- Kaplan–Meier survival curve;
- mean, median, p90 and p99 lifetime;
- empirical hazard versus time;
- critical force of maximal lifetime;
- catch-side and slip-side slopes;
- ADP/rigor lifetime ratio;
- force-direction asymmetry;
- loading-history effects.

### Parameters identified

Directly:

- `xCatch`;
- `xSlip`;
- unloaded rates for both pathways;
- whether a single reaction coordinate is adequate.

### Calibration rule

Fit the single-molecule lifetime data first. Then rerun clamp and gliding predictions.

Do not change `xCatch` to fit gliding and subsequently ask whether the lifetime curve remains acceptable. The current project record correctly identifies this as the strongest but most provenance-expensive velocity lever.

---

## SM5 — Dynamic force spectroscopy

Constant-force lifetimes and ramped rupture forces constrain different aspects of the energy landscape.

Nishizaka and colleagues measured rigor unbinding by pulling actin away from a single myosin interaction, and Guo and Guilford later measured rupture over broad loading-rate ranges in rigor and ADP states. ([pubmed.ncbi.nlm.nih.gov](https://pubmed.ncbi.nlm.nih.gov/7675112/?utm_source=chatgpt.com))

### Simulation

Apply force ramps spanning at least:

- 1;
- 10;
- 100;
- 1,000;
- 10,000 pN/s.

Run both ADP and rigor states.

### Readouts

- rupture-force distributions;
- modal rupture force versus log loading rate;
- apparent inner and outer barriers;
- head-count dependence;
- effect of initial strain and loading direction.

### Why this matters

This assay distinguishes:

- a physical actomyosin bond model;
- a generic lifetime law;
- the separate emergency overstrain safeguard.

The dormant rupture guard should **not** be calibrated from ordinary actomyosin rupture data unless its metric maps to the same reaction coordinate.

---

## SM6 — Nonlinear force–extension and S2 buckling

Kaya and Higuchi reported nonlinear single-myosin elasticity and an approximately 8 nm working stroke after correcting observed displacement for elastic extension. The response was markedly asymmetric between positive and negative strain. ([tohoku.elsevierpure.com](https://tohoku.elsevierpure.com/en/publications/nonlinear-elasticity-and-an-8-nm-working-stroke-of-single-myosin-/?utm_source=chatgpt.com))

### Simulation

Freeze chemistry in defined states and impose quasistatic displacements:

- axial: −20 to +20 nm;
- transverse: −10 to +10 nm;
- multiple pulling angles.

Run:

- pre-stroke;
- post-stroke;
- rigor;
- single-headed HMM;
- intact dimer.

### Readouts

- full force–extension curve;
- tangent stiffness versus strain;
- tensile stiffening;
- compressive softening or buckling;
- hysteresis;
- energy stored in F8, lever, branch and S2;
- recovery after unloading.

### Parameters identified

- F8 stiffness;
- branch EA and EI;
- shared-S2 EA and EI;
- angular constraints;
- anchor compliance.

This assay should determine whether the present approximately 0.63 pN/nm small-signal stiffness conceals an incorrect large-strain response.

---

## SM7 — Nucleotide and concentration dependence

Run the trap protocols across:

- ATP concentration;
- ADP concentration;
- Pi concentration;
- temperature where the code supports condition-specific rates.

### Readouts

- attached lifetime;
- waiting time to binding;
- stroke probability;
- stroke latency;
- detachment pathway;
- ATP consumed per productive event;
- force and work.

This tests whether the chemistry and mechanics remain correctly coupled rather than merely reproducing one standard condition.

---

# 4. Dimer-specific laser-tweezers campaign

The dimer introduces experimentally testable predictions unavailable to the original single-head model.

## DM1 — One-head-bound versus two-head-bound stiffness

Prepare identical dimer poses with:

- head A bound only;
- head B bound only;
- both heads bound;
- both heads bound at several axial separations.

Measure axial and transverse stiffness with small oscillatory perturbations.

### Questions

- Is double-bound stiffness additive, subadditive or dominated by the shared S2?
- Does the fork redistribute load symmetrically?
- Does one branch become mechanically shielded?
- Does stiffness depend on head separation?

This directly identifies fork and branch mechanics.

---

## DM2 — Second-head capture under controlled preload

Hold the first head bound and apply assisting or opposing load while the second head searches.

Measure:

- second-head binding rate;
- forward versus backward target selection;
- accepted separation;
- two-head fraction;
- force and velocity at capture;
- subsequent dwell.

This is the controlled-trap counterpart of the backward-second-head population seen in gliding.

It should determine whether the high-density opposition is an inevitable mechanical consequence of the dimer geometry or a binding-gate artifact.

---

## DM3 — Head-resolved load sharing

Once both heads are bound, impose constant total loads.

Record:

- force carried by each head;
- branch extension;
- shared-S2 deformation;
- head-specific detachment hazard;
- sequence of one-head and two-head states;
- recoil when one head detaches.

### Key prediction

The total load need not divide equally. The model should predict a reproducible distribution based on actin-site separation, fork geometry, and nucleotide state.

---

## DM4 — Dimer survival and partner rescue

Compare force-dependent survival of:

- a single bound head;
- a dimer with one head initially bound and the other free;
- a doubly bound dimer.

Measure whether second-head capture increases effective attachment lifetime and whether the dimer exhibits a “partner rescue” effect without becoming processive in an unrealistic hand-over-hand sense.

This is particularly relevant for mapping single-head catch kinetics into ensemble continuity.

---

## DM5 — Coordinated and conflicting strokes

Trigger Pi release in:

- head A only;
- head B only;
- both simultaneously;
- both with controlled delay;
- one head forward-strained and the other backward-strained.

Measure:

- actin displacement;
- total force;
- head-specific work;
- stroke suppression;
- stroke reversal;
- internal energy;
- detachment order.

This will reveal whether the shared S2 produces productive coordination or merely internal frustration.

---

## DM6 — Physical rupture adjudication

Replay highly strained dimer states in a controlled trap.

Increase load slowly enough to identify:

- which physical metric first separates ordinary load from pathological deformation;
- whether the actomyosin bond should detach before the branch becomes extreme;
- whether bond displacement, branch extension, force, or a combination is the best physical rupture coordinate.

This is the right place to validate the dormant rupture mechanism. It should not be inferred from rare free-gliding trajectories alone.

---

# 5. Transition to minifilaments

## First decide which minifilament is being built

There are two scientifically different targets.

### A. Synthetic skeletal-myosin bipolar filament

This retains the current skeletal motor kinetics and places validated dimers on a bipolar backbone.

This is the natural next model.

Relevant experiments include synthetic skeletal myofilaments that produced coordinated approximately 4 nm displacements at loads above 30 pN, interpreted as coordination among a few motors, and isolated intact skeletal myosin filaments whose force–velocity relation was inverse-hyperbolic. ([nature.com](https://www.nature.com/articles/ncomms16036?utm_source=chatgpt.com))

### B. Nonmuscle myosin-II minifilament

This requires new isoform-specific chemistry.

Purified NM2-B bipolar filaments can move processively, whereas NM2-A filaments are much less processive under the same conditions. In one reconstituted study, approximately 5–10 motor domains per half-filament were needed for processivity; NM2-B filaments had characteristic run lengths near 2 μm and velocities around 43 nm/s at 1 mM ATP. ([elifesciences.org](https://elifesciences.org/articles/32871?utm_source=chatgpt.com))

The current fast skeletal motor should not be relabeled NM2 simply because it is assembled into a 300 nm bipolar object.

---

# 6. Minifilament build and validation sequence

## MF0 — Architecture-only validation

Build a bipolar backbone with configurable:

- half-filament length;
- number of HMM dimers per half;
- axial and azimuthal head spacing;
- bare zone;
- backbone axial, bending and torsional stiffness;
- symmetric or stochastic head placement.

Before chemistry:

- verify force and torque conservation;
- verify polarity symmetry;
- verify permutation invariance;
- verify that inactive motors do not change mechanics;
- verify that a backbone under equal opposing loads does not drift.

---

## MF1 — One half-filament on one actin filament

This is the minimal processivity test.

Sweep:

- 1, 2, 4, 8, 16 and 32 dimers per half-filament;
- ATP concentration;
- load;
- backbone compliance.

Measure:

- probability of continuous attachment;
- run length;
- velocity;
- number of heads bound;
- ATP per distance;
- stall frequency;
- backward slips;
- end detachment.

For the skeletal motor, low processivity is expected unless enough heads are available. For an eventual NM2-B parameterization, the Melli results provide a direct processivity benchmark. ([elifesciences.org](https://elifesciences.org/articles/32871?utm_source=chatgpt.com))

---

## MF2 — Bipolar contraction between antiparallel actin filaments

Trap two actin filaments with opposite polarity and place the minifilament between them.

Measure:

- contraction velocity;
- isometric force;
- symmetry of force between halves;
- internal translation of the minifilament;
- bound-head counts on each side;
- ATP turnover;
- contraction continuity;
- behavior when one side loses contact.

This is the essential minifilament experiment.

---

## MF3 — Minifilament force–velocity relation

Use an active force clamp on the two-filament assay.

Sweep from:

- near-zero load;
- through intermediate shortening loads;
- to isometric;
- then controlled lengthening loads.

Measure:

- force–velocity curve;
- power–load curve;
- ATPase–load curve;
- head recruitment versus load;
- force per bound head;
- strain distribution across the backbone.

Isolated intact skeletal and smooth myosin filaments have shown inverse-hyperbolic force–velocity behavior, making this a direct ensemble benchmark. ([journals.physiology.org](https://journals.physiology.org/doi/10.1152/ajpcell.00339.2019?utm_source=chatgpt.com))

---

## MF4 — Coordinated force generation

At high loads, examine whether force rises smoothly or through coordinated steps.

Measure:

- step-size distribution;
- number of heads changing state per step;
- temporal clustering of Pi release;
- whether apparent steps persist after filtering;
- motor–motor strain correlations;
- load-sharing lifetime.

The synthetic-myofilament experiments reporting approximately 4 nm steps above 30 pN provide a concrete target for asking whether coordination emerges without adding an explicit cooperative rate. ([nature.com](https://www.nature.com/articles/ncomms16036?utm_source=chatgpt.com))

---

## MF5 — Mixed composition and regulation

Only after a single-isoform minifilament passes:

- mix fast and slow skeletal heads;
- later implement NM2-A/NM2-B kinetics;
- vary phosphorylation/active fraction;
- allow filament assembly and disassembly;
- test mixed-filament processivity and tension maintenance.

These are separate biological models and should have separate parameter sets.

---

# 7. Parameter-identification map

| Parameter family | Primary calibration assay | Secondary validation |
|---|---|---|
| Intrinsic stroke geometry | SM1, SM2 | SM3, DM5 |
| F8 / converter stiffness | SM1, SM6 | DM1 |
| Shared-S2 and branch EA/EI | SM6, DM1 | DM3, MF3 |
| `xCatch`, `xSlip`, pathway rates | SM4 | SM5, DM4, gliding |
| Rigor and ADP rupture barriers | SM5 | DM6 |
| Pi-release timing/reversibility | SM2, SM3 | MF4 |
| ATP/ADP/Pi chemistry rates | SM7 | gliding and MF3 |
| Binding search radius/orientation | capture-pose distributions, DM2 | density response |
| Fork geometry and stiffness | DM1–DM3 | dimer gliding |
| Minifilament head number/spacing | MF1–MF2 | MF3–MF4 |
| Backbone compliance | MF2–MF3 | contractile network behavior |

A parameter should not be adjusted unless its primary assay is sensitive to it and the adjustment improves that assay without violating previously passed assays.

---

# 8. Recommended execution order

## Campaign I — calibration-critical single motor

Run first:

1. SM4 force–lifetime curves;
2. SM2 constant-load stroke map;
3. SM6 nonlinear force–extension;
4. SM5 loading-rate rupture;
5. SM1 realistic-only blind holdout;
6. SM7 nucleotide perturbations.

This resolves `xCatch`, large-strain mechanics, and load-dependent stroke—the three most important gaps.

## Campaign II — explicit dimer under controlled load

Then:

1. DM1 stiffness;
2. DM2 second-head capture;
3. DM3 load sharing;
4. DM4 partner rescue;
5. DM5 conflicting strokes;
6. DM6 rupture adjudication.

## Campaign III — synthetic skeletal minifilament

Then:

1. MF0 architecture;
2. MF1 half-filament processivity;
3. MF2 bipolar contraction;
4. MF3 force–velocity;
5. MF4 coordinated high-load events.

## Campaign IV — nonmuscle minifilament

Begin only after adopting an NM2-A or NM2-B biochemical parameter set and validating that motor at the single-molecule level.

---

# 9. Formal promotion gates

The motor becomes **single-molecule validated** when it passes:

- realistic-only stroke and stiffness recovery;
- load-dependent stroke behavior;
- isometric force;
- ADP and rigor lifetime–force curves;
- dynamic rupture versus loading rate;
- nonlinear tension/compression elasticity;
- nucleotide-dependent dwell behavior.

The HMM becomes **dimer validated** when it additionally passes:

- one- versus two-head stiffness;
- controlled second-head capture;
- head-resolved load sharing;
- partner-rescue survival;
- coordinated/conflicting-stroke tests;
- physically justified rupture behavior.

A minifilament becomes **validated for its named isoform and assay** only after:

- architecture conservation tests;
- processivity or nonprocessivity;
- bipolar contractility;
- force–velocity;
- motor-count scaling;
- high-load coordination;
- ATP efficiency.

The immediate highest-value experiment is **SM4: the force-dependent lifetime campaign**. It directly tests the model’s strongest calibration lever, anchors the catch–slip provenance, and determines whether the current gliding speed should be accepted as a prediction or revisited through a defensible single-molecule calibration.
