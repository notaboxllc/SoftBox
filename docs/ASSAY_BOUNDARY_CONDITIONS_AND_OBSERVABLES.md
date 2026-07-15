# Assay Boundary Conditions and the Meaning of Motor Observables

**Project:** SoftBox two-body myosin development  
**Status:** Controlling interpretation guide for calibration and validation  
**Applies to:** single-molecule mechanics, optical-tweezers assays, gliding assays, sparse multimotor assays, and future native-filament comparisons

---

## Controlling statement

**An experimental measurement is not automatically an assay-independent property of the motor.**

The observed step size, stiffness, force, duty ratio, attachment lifetime, and gliding velocity depend on both:

1. the intrinsic molecular motor; and
2. the mechanical and analytical boundary conditions of the assay.

The project must therefore distinguish:

- **intrinsic motor behavior**;
- **mechanical transmission through the construct and its attachments**; and
- **the observable reported by a particular experimental procedure**.

A target value may be valid for one fixture and inappropriate as a universal requirement for every assay.

This is not permission to disregard experimental data. It is a requirement to reproduce the relevant assay before deciding what the data constrain.

---

## Why this document exists

The two-body program repeatedly exposed a modeling trap:

> A measured displacement or force was treated as though the same value should emerge unchanged from every representation of the motor and every assay geometry.

The fixed-anchor two-body motor can transmit an approximately 7 nm event and maintain substantial external stiffness. Adding a more mobile passive tail increases recruitment but allows pivot recoil, reducing the displacement and stiffness transmitted to the filament. This does not necessarily mean that one of those representations is intrinsically wrong. They may describe the same internal motor operating under different substrate-contact and tail-support conditions.

The central lesson is:

> **One intrinsic conformational stroke can produce different measured displacements in different fixtures.**

---

## Three levels that must remain separate

### 1. Intrinsic conformational stroke

Internal changes of the motor itself, including:

- converter-state change;
- lever-arm rotation;
- head-domain reorientation;
- nucleotide-state sequence;
- intrinsic handedness and polarity;
- internal elastic-energy change.

These quantities should be as assay-independent as the model permits.

### 2. Mechanical transmission

How much of the intrinsic stroke reaches the actin filament or external load after deformation of:

- the neck–lever assembly;
- converter and head compliance;
- F8 or other crossbridge elasticity;
- S2 or tail compliance;
- surface contacts;
- filament bending and stretching;
- bead links, traps, pedestals, and other experimental fixtures;
- simultaneously attached motors.

Mechanical transmission is generally assay-dependent.

### 3. Reported assay observable

The quantity estimated from experimental records, such as:

- bead displacement;
- dumbbell displacement;
- event amplitude after filtering;
- force-clamp working stroke;
- filament center-of-mass displacement;
- velocity from a fitted trajectory;
- stiffness inferred from variance or perturbation response;
- dwell time after event selection;
- apparent duty ratio from ensemble occupancy.

The reported value also depends on bandwidth, noise, filtering, event detection, fitting, selection, and statistical analysis.

---

## “Step size” is not a single universal quantity

The term **step size** must always be qualified. At minimum, future reports should distinguish:

| Quantity | Definition |
|---|---|
| Converter stroke | Change in converter target or angle |
| Lever/head stroke | Displacement of a defined material point in the motor |
| Clean mechanical capacity | Relaxed pre/post displacement with a specified fixture |
| Trap-reported step | Displacement inferred from a specified optical-trap protocol |
| Event-window filament displacement | Filament displacement over a defined post-transition window |
| Displacement to ADP release | Net filament displacement from Pi release to ADP release |
| Displacement to ATP detachment | Net filament displacement from Pi release to detachment |
| Ensemble displacement per cycle | Net gliding displacement divided by completed cycles under overlap |
| Effective gliding step | A model-dependent quantity inferred from velocity, ATPase, duty, or event flux |

No report should use the unqualified phrase “the step size” when more than one of these could be meant.

---

## Assay boundary conditions that can change the observed result

### Optical-tweezers and three-bead assays

Potentially controlling details include:

- myosin construct: S1, HMM, full-length myosin, engineered fragment;
- pedestal or bead surface chemistry;
- whether adsorption occurs at one point or over a distributed region;
- how much tail or S2 is collapsed, embedded, adsorbed, or otherwise supported;
- effective free length between substrate support and lever pivot;
- trap stiffness and dumbbell compliance;
- position clamp, force clamp, length clamp, or passive trapping;
- loading direction and preload;
- temporal bandwidth and filtering;
- event-detection criteria;
- selection for isolated, clean, mechanically stable interactions;
- calibration and correction for series compliance.

A strongly adsorbed or multiply supported tail can make a long molecule behave mechanically like a short, nearly fixed anchor. In such a fixture, a stubby-tail or fixed-pivot model may be an appropriate effective representation even if the molecular construct contains a longer tail.

### Surface gliding assays

Potentially controlling details include:

- myosin or HMM construct;
- surface chemistry and adsorption orientation;
- supported versus free tail length;
- fraction of molecules that are active, accessible, misoriented, or denatured;
- motor density and spatial distribution;
- filament contour length and flexibility;
- surface-normal confinement and steric interaction;
- filament Brownian translation, rotation, and bending;
- load sharing, interference, drag, and simultaneous attachment;
- ATP concentration and recovery kinetics;
- trajectory duration and velocity estimator.

A motor attached mainly through a distal tail region may have a larger capture volume and better recruitment while transmitting less displacement per event than a strongly immobilized motor.

### Native thick-filament or sarcomeric systems

Potentially controlling details include:

- thick-filament backbone support;
- proximal S2 mobility;
- tail packing and neighboring-tail contacts;
- MyBP-C, titin, and other structural constraints;
- two-headed geometry;
- lattice spacing and filament compliance;
- cooperative recruitment and regulatory states;
- load distribution across many motors;
- activation state and filament strain.

These systems should not be treated as equivalent to either a single surface-bound motor or a simplified gliding lawn.

---

## Surface contact should be modeled as a boundary condition

The project should avoid asking for one universally “correct” free-tail length when the experimentally relevant quantity may be:

> **the supported fraction of the tail and the remaining mechanically free proximal length.**

A useful decomposition is:

```text
total molecular tail
    = substrate-supported or backbone-supported portion
    + mechanically free proximal portion
```

Different experimental surfaces may produce different distributions of supported length without changing the motor molecule itself.

Accordingly, fixed-anchor and compliant-tail implementations may represent different assay contact states rather than mutually exclusive molecular hypotheses.

---

## Current project interpretation

### Fixed-anchor two-body motor

Best interpreted as an effective boundary condition with:

- very short mechanically free proximal length;
- strong surface or fixture support;
- little pivot recoil;
- high transmission of the intrinsic stroke;
- relatively high external stiffness.

This can be an appropriate model for a well-supported single-molecule fixture.

### Free passive-tail motor

Best interpreted as a diagnostic boundary condition with:

- increased pivot search volume;
- increased capture and recruitment;
- substantial pivot recoil under load;
- lower transmitted displacement;
- lower external stiffness.

This may be more representative of a loosely supported surface attachment, but it is not automatically a complete biological model.

### Dense-mat gliding motor

Must be judged primarily by ensemble observables:

- recruitment;
- continuity;
- directionality;
- gliding velocity;
- transverse wandering;
- ATP use per distance;
- loaded and unloaded force–velocity behavior;
- fraction of chemically bound motors that actually carry load.

It should not be rejected solely because each event transmits less than the optical-tweezers step.

---

## Calibration policy

### Preserve an assay-independent motor core where possible

The following should remain common across assay models unless evidence requires otherwise:

- nucleotide-state topology;
- converter-state ordering;
- intrinsic stroke handedness;
- head and lever geometry;
- core chemical rates, with stated solution conditions;
- state-dependent actin affinity;
- intrinsic force-generation mechanism.

### Make fixture parameters explicit

Assay-specific configuration should include, where relevant:

- supported tail length;
- free S2 length;
- surface-contact geometry;
- substrate stiffness;
- trap stiffness;
- bead and linker compliance;
- filament length and flexibility;
- surface confinement;
- motor density and active fraction;
- force- or position-control protocol;
- measurement bandwidth and estimator.

### Do not retune the intrinsic motor to compensate for an incorrect fixture

Before changing converter geometry, F8 stiffness, catch-slip kinetics, or nucleotide rates, first test whether the discrepancy is caused by:

- the wrong surface attachment;
- the wrong free-tail length;
- missing trap or bead compliance;
- missing filament flexibility;
- a mismatched load protocol;
- an estimator that does not reproduce the experiment.

### Require assay reproduction before declaring a conflict

A model conflicts with an experimental target only after the relevant construct, fixture, loading protocol, and observable estimator have been represented with adequate fidelity.

---

## Mandatory target-data record

Before adopting any experimental value as a calibration target, record the following.

```markdown
### Target-data record

- Observable:
- Numerical value and uncertainty:
- Original source:
- Myosin isoform:
- Myosin construct:
- Actin construct or labeling:
- Solution conditions:
- ATP concentration:
- Temperature:
- Surface or pedestal chemistry:
- Presumed attachment site:
- Evidence for distributed adsorption or embedding:
- Estimated supported tail/S2 length:
- Estimated mechanically free tail/S2 length:
- Filament geometry and flexibility:
- External compliance:
- Loading or feedback protocol:
- Measurement bandwidth and filtering:
- Event-selection or trajectory-selection method:
- Observable estimator:
- Known corrections:
- Relevant simulation assay:
- Parameters constrained primarily by this target:
- Parameters that this target should not constrain:
- Remaining interpretation uncertainty:
```

If these fields are unknown, mark them as unknown rather than silently assuming a fixed anchor, free tail, rigid filament, or direct observation of the molecular stroke.

---

## Required reporting in future SoftBox experiments

Every mechanical or gliding report should include:

1. **Intrinsic motor observables**
   - converter completion;
   - head or lever material-point displacement;
   - intrinsic polarity.

2. **Transmission observables**
   - pivot recoil;
   - tail deformation;
   - F8 deformation;
   - filament deformation;
   - transmitted filament displacement;
   - substrate or trap reaction.

3. **Assay observables**
   - the exact experimentally analogous estimator;
   - filtering or averaging window;
   - load and fixture settings;
   - confidence intervals across independent episodes.

4. **Boundary-condition statement**
   - what is fixed;
   - what is compliant;
   - what is free to rotate or translate;
   - how the motor is attached to the substrate or backbone.

5. **Interpretation limit**
   - which conclusions are molecular;
   - which are fixture-dependent;
   - which remain underdetermined.

---

## Decision rules

### A smaller gliding event is not automatically a failed stroke

If the converter completes and the event has the correct polarity, reduced filament displacement may reflect compliant transmission rather than failure of the intrinsic motor.

### A large trap step is not automatically the free-motor stroke

A large observed displacement may require a strongly supported tail and stiff fixture. It should not automatically be imposed on a loosely attached gliding motor.

### More chemically bound motors do not necessarily mean more force

Recruitment metrics must be paired with load-bearing metrics. Report both:

- chemically bound motors;
- mechanically load-bearing motors.

### Low stiffness can be acceptable for unloaded motility but not necessarily for force production

The relevant acceptance criterion depends on the assay objective. A compliant motor may glide effectively through high recruitment and event flux while performing poorly in isometric-force or high-load assays.

### Ensemble success can compensate for small event transmission

For gliding, assess the combination of:

- event frequency;
- transmitted displacement per event;
- continuity;
- drag from attached motors;
- overlap and handoff;
- ATP cost;
- load dependence.

Do not judge ensemble motility from single-event displacement alone.

---

## Red flags

Pause interpretation if any of the following appear:

- “the step size” is used without defining the measured coordinate;
- a tweezers value is imposed directly on a gliding fixture;
- a gliding velocity is used to retune single-motor stiffness without checking recruitment;
- a long molecular tail is automatically modeled as a fully free compliant tail;
- an adsorbed construct is modeled as a single-point attachment without justification;
- a fixed anchor is interpreted literally as absence of a tail;
- a compliant attachment is called mechanically inert without testing ensemble motion;
- event filtering or selection is omitted from an assay comparison;
- surface chemistry is treated as irrelevant;
- a discrepancy is assigned to the motor before the fixture is reproduced.

---

## Recommended project structure

Maintain two layers of model configuration.

### Molecular-core configuration

Contains:

- head, converter, and lever geometry;
- intrinsic stroke targets;
- chemistry;
- binding gate;
- core elastic elements.

### Assay-fixture configuration

Contains:

- surface attachment and supported tail fraction;
- tail/S2 free length and compliance;
- substrate or thick-filament support;
- trap and bead mechanics;
- filament length and flexibility;
- motor density and active fraction;
- confinement and boundary conditions;
- observable estimator.

A result should name both configurations.

Example:

```text
Motor core: TWO_BODY_LYMN_TAYLOR_V1
Fixture: TWEEZERS_STRONGLY_SUPPORTED_HMM_V1
```

or:

```text
Motor core: TWO_BODY_LYMN_TAYLOR_V1
Fixture: GLIDING_DISTAL_TAIL_ADSORPTION_V1
```

---

## Implications for upcoming work

Before engineering a nonlinear tail to recover the fixed-anchor step, first ask whether full transmission is actually required for the target gliding assay.

The next decisive comparison should test existing fixed and compliant attachment states under long, GPU-accelerated dense-mat gliding runs, while preserving the same intrinsic motor core.

Primary question:

> Does increased recruitment and event frequency compensate for reduced displacement transmission?

Only after that comparison should the project decide whether a nonlinear load-bearing tail is necessary.

---

## Project-level conclusion

The biologically defensible goal is not one motor that emits the same measured step and stiffness in every context.

The goal is:

> **one coherent molecular motor whose internal cycle is stable, combined with explicit assay-specific mechanical boundary conditions that reproduce the observables measured by each experiment.**

Experimental procedures are part of the model–data mapping. They must remain visible whenever a measurement is converted into a target.
