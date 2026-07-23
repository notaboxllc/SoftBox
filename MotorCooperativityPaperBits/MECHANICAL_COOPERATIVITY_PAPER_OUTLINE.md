# Paper Outline: Mechanical Cooperativity and the SoftBox Myosin Motor Model

## Working paper identity

This paper should be framed as a **mechanistic biology paper with a substantial model-development and validation component**, rather than as a software paper with a gliding example.

Its central question is:

> How do individually independent myosin-II motors become mechanically cooperative during actin gliding, and why does increasing motor recruitment eventually reduce transport efficiency?

The methods contribution is that SoftBox provides a single explicit motor representation that survives single-molecule validation and produces ensemble behavior without being reparameterized between assays.

---

## Candidate titles

### Preferred

**From Single-Motor Mechanics to Collective Motility: An Explicit Mechanochemical Model of Myosin-II Mechanical Cooperativity**

### Alternatives

- **Mechanical Cooperativity among Myosin-II Motors Emerges from Load Sharing and Internal Opposition**
- **A Multiscale Mechanochemical Model Links Myosin Step Size and Force-Dependent Detachment to Collective Actin Gliding**
- **Independent Myosin Motors Become Mechanically Cooperative through Shared Filament Load**
- **Explicit Motor Mechanics Reveal Productive Recruitment and Interference in Myosin-Driven Actin Gliding**

---

## Central thesis

> A motor model constrained at the single-molecule level predicts that ensemble gliding passes from sparse attachment, through productive recruitment, into an interference-dominated regime in which additional attached motors increase mechanical coupling but contribute progressively less net propulsion.

A secondary architectural conclusion is:

> Dimerization reduces translational efficiency without substantially changing the motor-density scale of saturation, indicating that recruitment and propulsion efficiency are separable collective properties.

The paper should avoid claiming direct biochemical cooperativity. The motors may retain independent intrinsic state-transition rules while becoming cooperative through shared filament mechanics and load-dependent kinetics.

---

# Abstract

The abstract should follow a problem–method–validation–discovery structure.

## Background

- Myosin ensemble behavior is often inferred from simplified load-sharing models.
- It remains difficult to connect single-molecule mechanics to collective motility using the same motor representation.
- Cooperativity may arise mechanically even when motors do not directly alter one another’s intrinsic chemistry.

## Method

- Introduce an explicit spatial mechanochemical myosin model.
- Include nucleotide cycling, lever-arm mechanics, S2 compliance, flexible actin, stochastic binding, and force-dependent release.
- State that the same model is tested in virtual optical-tweezers and gliding assays.

## Validation

- The step-size assay recovers the expected displacement.
- The force-clamp assay recovers force-dependent rigor and ADP lifetimes under protocol-matched conditions.
- No separate gliding-specific kinetic calibration is introduced.

## Main ensemble result

- Gliding speed saturates with motor density while bound-head number continues increasing.
- Velocity per bound head declines.
- Force distributions reveal increasing coexistence of productive and resisting motors.
- Dimerization lowers maximum speed but leaves the density scale of saturation nearly unchanged.

## Conclusion

- Collective motility reflects a transition from productive recruitment to mechanical interference.
- Detailed motor mechanics bridge single-molecule experiments and ensemble function.

---

# 1. Introduction

## 1.1 The unresolved scale-bridging problem

Contrast two levels of understanding:

- Single-molecule assays reveal step size, attachment lifetime, force dependence, and nucleotide kinetics.
- Ensemble assays reveal velocity, force, persistence, density dependence, and collective organization.

The central difficulty is explaining how the first level generates the second.

A useful opening statement is:

> The behavior of an ensemble cannot generally be reconstructed by multiplying the behavior of an isolated motor by the number of motors present.

Motors sharing a filament experience:

- unequal loads;
- assisting and resisting forces;
- asynchronous strokes;
- state-dependent compliance;
- competition between propulsion and drag;
- force-dependent attachment lifetimes.

## 1.2 Clarifying “cooperativity”

Define the paper’s usage early.

Mechanical cooperativity means that one motor changes the realized behavior of another through shared mechanical variables, even when intrinsic kinetic parameters remain unchanged.

Distinguish it from:

- biochemical or allosteric cooperativity;
- thin-filament regulatory cooperativity;
- generic active-network organization.

A falling velocity per head can coexist with strong collective coupling.

## 1.3 Limits of existing models

Organize previous work into two broad groups:

- mechanochemically detailed but spatially reduced cross-bridge and cluster models;
- spatially detailed but internally reduced active-network models.

State the gap cautiously:

> Few models retain a common, experimentally testable motor representation from single-molecule displacement and force-clamp assays through many-motor gliding.

## 1.4 Questions addressed

1. Can one motor representation reproduce single-molecule displacement and force-dependent attachment?
2. Does collective gliding emerge without assay-specific retuning?
3. What microscopic process causes velocity to saturate with motor density?
4. Do additional motors remain productive, become mechanically neutral, or resist motion?
5. How does HMM dimer architecture change recruitment and transport efficiency?

---

# 2. Model and Computational Methods

This section is the methods-paper core, but the main text should explain the model conceptually rather than documenting every implementation detail.

## 2.1 Overview of the multiscale model

Introduce:

- flexible actin;
- substrate-anchored motor objects;
- single-head and HMM dimer architectures;
- lever arm and S2;
- stochastic nucleotide cycle;
- spatial binding gate;
- overdamped mechanical integration.

A central schematic should show:

```text
motor model
├── step-size assay
├── force-clamp assay
└── gliding assay
```

This should be Figure 1.

## 2.2 Actin representation

Describe:

- number and length of segments;
- bending elasticity;
- axial or constraint mechanics;
- excluded volume, if used;
- thermal forcing;
- drag or hydrodynamic approximation;
- boundary conditions.

State clearly which features are explicit and which are coarse-grained.

## 2.3 Motor architecture

### Single-head model

Describe:

- anchor;
- S2 or elastic connection;
- lever geometry;
- binding site;
- power-stroke conformations.

### HMM dimer model

Describe:

- common tail or anchor;
- shared S2;
- branch point;
- two heads;
- mechanically linked but chemically distinct head states;
- one-head-bound and two-head-bound configurations.

State explicitly that dimer density is molecular density, not doubled head density.

## 2.4 Nucleotide cycle

Present the Lymn–Taylor-inspired state graph.

For every transition, document:

- direction;
- kinetic rate;
- concentration dependence;
- force dependence;
- mechanical consequence;
- binding or detachment consequence.

Incorporate the ATP audit:

- ATP binding is represented as a pseudo-first-order rate;
- the assay interface scales that effective rate with ATP condition;
- zero scaling represents ATP-free conditions in the current implementation.

Include a full state and transition table.

## 2.5 Spatial binding gate

Explain:

- search radius;
- orientation restrictions;
- eligible actin sites;
- competition among candidate sites;
- binding probability;
- separation of geometry from kinetics.

This section is important because binding access may influence half-saturation density.

## 2.6 Mechanical force generation

Describe:

- power-stroke displacement or conformational change;
- lever stiffness;
- S2 mechanics;
- motor-anchor compliance;
- actin reaction force;
- axial force convention;
- assisting and resisting loads.

Highlight tension–compression asymmetry where relevant:

- S2 transmits tension;
- S2 can buckle or transmit compression differently;
- this may create mechanical rectification.

## 2.7 Force-dependent detachment

Separate:

- ADP-state force-dependent chemical release;
- ATP-triggered detachment after rigor;
- mechanical rigor rupture;
- optional experimental pathways.

State that rigor rupture is distinct from ATP binding and was independently validated.

The all-strong-bound rupture variant belongs in a sensitivity subsection or supplement. It worsened ADP agreement and changed gliding velocity, so it should be presented as a rejected structural alternative rather than as canonical physics.

## 2.8 Numerical integration and stochastic events

Document:

- timestep;
- integrator;
- stochastic transition algorithm;
- competing hazards;
- random-number handling;
- GPU implementation;
- CPU/GPU equivalence policy;
- failure and rate-cap monitoring.

Avoid “CPU arbiter” language. Production GPU paths should be used after assay-class validation, with targeted CPU comparisons when kernels or numerical methods change.

## 2.9 Assay implementations

### Virtual step-size assay

- trap geometry;
- prepared motor state;
- measured observable;
- event alignment;
- blind analysis.

### Virtual force-clamp assay

- fixed-anchor and explicit-S2 fixtures;
- force protocol;
- ATP-free and ATP-present conditions;
- event endpoint;
- survival analysis;
- realized rather than nominal force.

### Gliding assay

- motor surface distribution;
- density definition;
- filament length;
- ATP condition;
- run duration;
- velocity estimator;
- bound-head and state metrics.

## 2.10 Statistical analysis

State:

- seed structure;
- confidence intervals;
- nonlinear curve fitting;
- survival models;
- hyperbolic versus Hill comparison;
- paired architecture comparisons;
- censoring treatment;
- handling of unresolved cells;
- no selective retuning after biological comparison.

---

# 3. Single-Molecule Validation

## 3.1 Virtual optical-tweezers step-size experiment

### Question

Does the model produce the correct experimentally observable step size?

### Results

Show:

- synthetic trace;
- event alignment;
- displacement distribution;
- recovered step;
- comparison with geometric stroke;
- sensitivity to trap compliance and analysis.

### Interpretation

The encoded stroke must survive mechanical filtering and experimental analysis.

## 3.2 Rigor force-clamp experiment

### Question

Does the bound motor display the correct force-dependent mechanical lifetime?

### Results

Show:

- survival curves across force;
- catch–slip lifetime curve;
- blind parameter recovery;
- peak force;
- characteristic distances;
- negative controls;
- legacy force-independent model failure.

This is the strongest force-lifetime validation.

## 3.3 ADP force-clamp experiment

### Initial mismatch

- ATP-present simulation produced ADP lifetimes shorter than rigor.
- A single-barrier fit was biased because the observable contained sequential chemical and detachment stages.

### Protocol audit

- The biological assay measured physical bond rupture under ATP-free ADP conditions.
- Setting the effective ATP rate to zero removed ATP-triggered termination.

### Corrected result

- ATP-free ADP lifetime exceeded rigor lifetime.
- The SoftBox ADP/rigor ratio was close to experiment.
- Peak lifetime and peak force were close to the experimental values.
- No ADP kinetic parameter was retuned.

### Structural limitation

SoftBox currently reaches ADP-conditioned detachment through a sequential ADP-release and rigor-rupture process rather than a single direct ADP-bond rupture.

The optional direct-ADP rupture experiment did not improve the result because the faster ADP-to-rigor transition outcompeted direct rupture.

## 3.4 Validation summary

> The model reproduces displacement and force-dependent attachment observables using one parameterized motor representation, providing the basis for interpreting collective gliding as an emergent prediction rather than an independently fitted behavior.

---

# 4. Emergent Gliding Motility

## 4.1 Gliding emerges across motor density

Show velocity versus density for:

- single-head motors;
- HMM dimers.

Use long-duration, timestep-converged runs.

Key existing results:

- both curves show simple saturation;
- single-head maximum speed is higher;
- half-saturation density is similar;
- the Hill exponent adds little over a hyperbola.

The ensemble can be mechanically cooperative without a strongly sigmoidal density curve.

## 4.2 Distinct dynamical regimes

Propose four regimes:

1. sparse or intermittent attachment;
2. productive recruitment;
3. velocity saturation;
4. high-density interference.

Show:

- velocity;
- continuous-motion probability;
- bound-head number;
- pause fraction;
- fluctuations;
- displacement autocorrelation.

## 4.3 Bound-head recruitment continues after velocity saturates

Plot versus density:

- velocity;
- mean bound heads;
- attachment continuity;
- ATP turnover;
- velocity per bound head.

Central observation:

> Motor occupancy continues to increase after velocity approaches its plateau.

Saturation is therefore not simply an attachment ceiling.

## 4.4 Propulsive efficiency per bound motor declines

Define one or more efficiency metrics, such as:

\[
\eta_v = \frac{v}{\langle N_{\mathrm{bound}}\rangle}
\]

and a productive-power fraction based on motor force and filament velocity.

Show that increasing density produces:

- less displacement per bound head;
- more neutral or resisting heads;
- greater internal force cancellation.

This is the heart of the mechanical-cooperativity argument.

## 4.5 Productive, resisting, and neutral motor populations

Classify bound heads using instantaneous power or force sign relative to filament motion:

- productive;
- resisting;
- mechanically neutral.

Analyze by:

- density;
- nucleotide state;
- attachment age;
- initial binding offset;
- architecture;
- force magnitude;
- lifetime.

This is likely the most novel analysis.

## 4.6 Load sharing is heterogeneous

Test equal-load-sharing assumptions.

Show:

- force distributions;
- fraction of total load carried by the most loaded heads;
- coefficient of variation;
- productive/resisting asymmetry;
- correlations among simultaneously bound heads;
- spatial force distribution along actin.

A strong possible result is:

> Collective force is borne by a minority of strongly loaded heads rather than being shared equally among all attached motors.

## 4.7 Time-resolved cooperation

Use event-triggered analyses around:

- attachment;
- power stroke;
- ADP release;
- detachment;
- second-head binding;
- loss of a resisting head.

Questions:

- Does a newly attached motor initially propel or resist?
- How does its force evolve?
- Does it become resisting as the filament advances?
- Does detachment of a resisting motor cause acceleration?
- Do velocity bursts align with coordinated transitions?

---

# 5. Dimer Architecture Changes Collective Efficiency

## 5.1 Matched-length single-head versus HMM comparison

Show that the filament-length mismatch contributes little, while the matched HMM dimer remains substantially slower.

This establishes that the slowdown is architectural.

## 5.2 Recruitment scale is preserved

Emphasize that single-head and dimer curves have similar half-saturation densities.

Interpretation:

- effective recruitment occurs at similar motor-object density;
- dimerization changes conversion of occupancy into movement;
- saturation density and maximum velocity are separable ensemble properties.

## 5.3 Candidate mechanisms for dimer slowdown

Analyze:

- zero-, one-, and two-head-bound fractions;
- sister-head force correlation;
- internal opposition within dimers;
- shared-S2 strain;
- second-head binding geometry;
- duty ratio;
- ATP use per displacement;
- productive power per dimer;
- detachment synchronization.

## 5.4 S2 as a mechanical coupling element

Test whether S2 compliance and buckling:

- isolate one head from compression;
- concentrate tensile load;
- alter sister-head correlations;
- change internal opposition;
- control which head dominates dimer detachment.

---

# 6. Perturbation Tests of Mechanical Cooperativity

## 6.1 Alter the binding gate

Vary:

- search radius;
- orientation tolerance;
- actin-site accessibility.

Ask whether half-saturation density shifts independently of maximum velocity.

Predicted separation:

- binding access primarily affects recruitment density;
- post-binding mechanics primarily affects velocity plateau and efficiency.

## 6.2 Alter motor compliance

Vary:

- S2 stiffness;
- lever stiffness;
- anchor stiffness;
- branch compliance.

Measure:

- velocity;
- bound heads;
- force heterogeneity;
- productive and resisting fractions;
- saturation density.

## 6.3 Remove load-dependent kinetics

Run counterfactuals with:

- force-independent ADP release;
- force-independent rigor rupture;
- altered catch/slip distances.

Use these to determine which feedbacks create ensemble coupling.

## 6.4 ATP titration

Analyze whether lowering ATP:

- increases occupancy;
- increases resisting attachments;
- shifts half-saturation density;
- changes maximum speed;
- strengthens force heterogeneity.

## 6.5 Filament-length dependence

Vary actin length at fixed density.

Distinguish:

- attachment continuity;
- number of available motors;
- force cancellation;
- velocity plateau;
- fluctuations.

This connects directly to classic gliding experiments.

---

# 7. Discussion

## 7.1 Mechanical cooperativity without biochemical communication

> Motors need not alter one another’s intrinsic kinetics to behave cooperatively. Shared filament motion changes each head’s realized load history, which changes lifetime and mechanical contribution.

## 7.2 Productive recruitment becomes interference

- At low density, added motors stabilize and accelerate motion.
- At intermediate density, recruitment remains productive.
- At high density, added motors increasingly oppose existing movement.
- Attachment continues rising while transport efficiency falls.

This reconciles increasing force capacity with saturating velocity.

## 7.3 A weak Hill coefficient does not imply weak cooperativity

The density curve can remain close to hyperbolic even when microscopic mechanical coupling is strong.

Macroscopic sigmoidality is not required for mechanical cooperativity.

## 7.4 Comparison with reduced ensemble models

Discuss:

- equal-load-sharing models;
- cluster models;
- rigid-filament motility models;
- active-network models.

SoftBox can reveal which assumptions hold and which fail.

## 7.5 Dimerization separates recruitment from efficiency

> Dimer architecture does not markedly change the density required to establish persistent collective engagement, but it changes how efficiently that engagement is converted into filament translation.

## 7.6 Relationship to experiments

Predictions include:

- bound motor number continues increasing after velocity saturates;
- high-density assays show greater internal stress;
- ATP reduction strengthens negative interference;
- HMM and S1 share recruitment density but differ in maximum speed;
- altered S2 compliance changes velocity more than density threshold;
- fragmentation or force fluctuations increase at high density.

## 7.7 Limitations

State clearly:

- coarse-grained actin;
- simplified hydrodynamics;
- idealized surface geometry;
- ATP represented through an effective pseudo-first-order interface;
- ADP force-clamp behavior produced through a sequential pathway rather than a direct bond-state model;
- no regulated thin filament;
- no thick filament or sarcomeric lattice;
- incomplete propagation of parameter uncertainty;
- possible differences from specific experimental preparations.

## 7.8 Broader implication

> A motor validated against single-molecule observables can generate nontrivial ensemble behavior without introducing a separate phenomenological cooperativity rule.

---

# 8. Conclusion

1. One explicit motor model reproduces step-size and force-dependent lifetime assays.
2. The same motor representation generates density-dependent gliding without ensemble-specific kinetic fitting.
3. Velocity saturation occurs while attachment continues increasing, revealing a transition from productive recruitment to internal mechanical opposition.
4. HMM dimerization lowers transport efficiency while preserving the recruitment density scale.

Possible final sentence:

> Mechanical cooperativity is therefore not a separate biochemical property added to the motor, but an emergent consequence of many validated motors sharing a deformable filament and a load-dependent cycle.

---

# Proposed main figures

## Figure 1 — Model and validation ladder

- single-head and HMM architectures;
- nucleotide cycle;
- flexible actin and surface geometry;
- three virtual assays;
- shared parameter flow.

## Figure 2 — Single-molecule displacement validation

- virtual trap;
- representative trace;
- aligned displacement events;
- recovered step distribution;
- biological comparison.

## Figure 3 — Force-dependent lifetime validation

- rigor survival curves;
- rigor lifetime versus force;
- ATP-free ADP protocol;
- ADP versus rigor lifetime;
- blind parameter recovery;
- controls.

## Figure 4 — Density-dependent gliding

- single-head velocity versus density;
- dimer velocity versus density;
- saturation fits;
- bound heads versus density;
- continuous-motion fraction.

## Figure 5 — Emergence of mechanical interference

- velocity per bound head;
- productive/resisting/neutral fractions;
- force distributions;
- power balance;
- representative low- and high-density states.

This should likely be the central figure.

## Figure 6 — Heterogeneous load sharing

- head-resolved force distribution;
- cumulative load carried by top-ranked heads;
- force by nucleotide state;
- attachment-age dependence;
- spatial distribution along actin.

## Figure 7 — Dimer architecture

- matched single-head versus dimer;
- one- and two-head occupancy;
- sister-head force correlations;
- shared-S2 mechanics;
- ATP consumed per displacement or power efficiency.

## Figure 8 — Causal perturbations

Possible panels:

- binding-gate perturbation;
- S2 stiffness perturbation;
- ATP titration;
- filament-length dependence;
- phase diagram of discontinuous, productive, and interference-dominated regimes.

---

# Supplementary structure

## Supplementary Methods

- full equations;
- parameter tables;
- state transitions;
- concentration scaling;
- stochastic event algorithm;
- GPU implementation;
- convergence tests;
- force-sign convention;
- analysis code;
- reproducibility details.

## Supplementary Validation

- timestep sweeps;
- CPU/GPU equivalence;
- fixture comparisons;
- additional survival fits;
- blind-analysis controls;
- failed structural alternatives;
- all-strong-bound rupture sensitivity.

## Supplementary Gliding Analyses

- all seeds;
- full density tables;
- hyperbolic and Hill residuals;
- alternate velocity estimators;
- filament-length controls;
- force distributions by state;
- event-triggered averages;
- dimer diagnostics.

---

# Tight Results narrative

The Results should form a chain of inference:

1. The model produces the correct molecular displacement.
2. The model produces the correct force-dependent bound lifetime.
3. Its ensemble behavior can therefore be interpreted mechanistically.
4. Gliding velocity saturates with density.
5. Attachment does not saturate at the same point.
6. The marginal motor becomes progressively less productive.
7. Force-resolved analysis shows growing internal opposition.
8. Dimerization changes efficiency, not recruitment scale.
9. Perturbations separate binding access from post-binding interference.

This gives the methods validation a clear purpose rather than making it a detached preamble.

---

# Current readiness

## Strongly developed

- long single-head and dimer density curves;
- matched-length architecture control;
- virtual step-size experiment;
- force-clamp rigor validation;
- ATP-free ADP protocol correction;
- canonical versus optional rupture-pathway decision;
- GPU production path;
- parameter and model provenance.

## Most important missing analyses

The highest-value missing work is head-resolved analysis of existing gliding trajectories:

- productive versus resisting classification;
- force and power distributions;
- load-sharing heterogeneity;
- state and attachment-age dependence;
- event-triggered acceleration after attachment or detachment;
- sister-head correlations;
- ATP cost per displacement.

One or two causal perturbations—probably binding gate and S2 compliance—would then make the mechanical argument substantially stronger.
