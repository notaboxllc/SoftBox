# Two-Body Myosin Motor and Blinded Optical-Tweezers Validation
## Integrated synthesis, interpretation, and recommended next steps

**Status:** Experiment 3G complete and unblinded  
**Scope:** Two-body motor mechanics, stereospecific binding, native Pi-release stroke, blinded optical-tweezers data generation, independent analysis, unblinding, and realism assessment

## Executive conclusion

The two-body motor has passed an unusually strong validation sequence.

The corrected model now has:

- a true ellipsoidal motor domain;
- a separate rotating neck–lever;
- a positional converter joint;
- a fixed material-frame power stroke;
- a stereospecific actin-binding basin;
- a naturally captured pre-stroke conformation;
- a predominantly axial, polarity-correct working stroke;
- externally measurable whole-crossbridge stiffness in the skeletal-myosin range.

The blinded optical-tweezers analysis recovered the hidden compliance-free step to within about **1.3%** and recovered the complete attached-crossbridge stiffness within realistic experimental uncertainty. It also recovered the correct pointed-first filament motion and correctly concluded that the very small pre/post stiffness difference was not experimentally resolvable.

This means the model's intended step and stiffness are not merely privileged internal parameters. They are visible through an assay using only bead positions, trap commands, calibrations, and controls.

The experiment was not a complete digital twin of a laboratory three-bead myosin assay. The dumbbell was mechanically simpler, the chemistry was incomplete, the perturbations were not optimally designed, and the analyst had access to paired ideal traces that a real experimentalist would not possess. These limitations matter, but they do not erase the main result.

### Recommended decision

Do **not** build the full publication-grade optical-tweezers digital twin immediately.

Do one modest near-term follow-up:

> Generate a small, sealed **realistic-only holdout** with no ideal twins available to the analyst, using a better stiffness perturbation protocol, and test the frozen analysis strategy once.

Then return to the motor-development sequence: close the minimal biochemical cycle, validate single-motor load-dependent release, and begin sparse ensemble tests. A fully realistic long-record optical-tweezers challenge will be more informative after stochastic detachment, recovery, and duty-ratio behavior exist.

---

# 1. Why the two-body motor was developed

The earlier canonical SoftBox motor could produce plausible ensemble gliding, but single-molecule compliance measurements showed that its externally observable mechanics were much softer than intended.

The project therefore separated two questions:

1. Can a coarse-grained motor produce appropriate actin-attached stiffness and working displacement?
2. Can those properties be inferred from observables available to an experimentalist?

The two-body program addressed the first question through Experiments 3A–3F and the second through Experiment 3G.

---

# 2. Mechanical development of the motor

## 2.1 Experiment 3C: correct topology and stroke handedness

The first two-body prototypes were mechanically closer to pinned cams than to a recognizable head–converter–lever motor. The corrected topology introduced:

- an ellipsoidal motor domain, approximately \(9\times5.5\times4.5\) nm;
- an 8 nm neck–lever;
- one converter joint;
- a fixed-position distal anchor;
- a relative converter coordinate;
- a passive actin-bound head-orientation coordinate;
- fixed material points for F8 and the converter on the motor domain.

The converter-to-F8 separation was encoded by material points on the ellipsoid rather than by a separate swinging rod.

A material-point trajectory audit then revealed that the earlier intrinsic converter ordering was biologically backward. With the original sign, the F8 point moved barbedward and the filament moved barbed-end first. The correction was made by reversing the fixed motor-frame pre/post target ordering, not by consulting `barbedDir` or multiplying forces by \(-1\).

After correction:

- force on actin was pointed-directed;
- an anchor-fixed motor moved the filament pointed-end first;
- polarity reversal and assay rotation behaved covariantly;
- the head remained nearly actin-aligned while the neck–lever carried most of the angular motion.

This established the correct architecture and intrinsic handedness.

## 2.2 Experiment 3D: geometric realignment

The first corrected geometry still converted much of the lever swing into transverse motion:

- axial displacement: about 3.6 nm;
- transverse displacement: about 6 nm.

The key geometric observation was that the entire lever sweep lay on one side of vertical. Re-aiming the pre-stroke lever so that the stroke approximately straddled vertical converted the arc into axial motion.

The selected geometry used:

- pre-stroke neck–lever angle: \(+30^\circ\);
- post-stroke angle: approximately \(-30^\circ\);
- converter target change: \(60^\circ\);
- unchanged motor dimensions and material-point separation.

At the reference operating point, the realigned motor produced:

| Observable | Result |
|---|---:|
| Pointedward external stroke | 6.91 nm |
| Transverse displacement | 0.09 nm |
| Whole-motor stiffness | 0.645 pN/nm |
| Near-stall force | 4.78 pN |
| Preload | approximately zero |
| Neck–lever rotation | about \(-57^\circ\) |
| Head rotation | about \(+0.8^\circ\) |

The model therefore achieved a recognizable lever-arm stroke with minimal transverse motion.

## 2.3 Experiment 3E: stereospecific Brownian binding capture

The validated \(+30^\circ\) pre-stroke geometry was then used as the center of a local actin-frame binding basin.

The binding search considered:

- head-to-actin distance;
- binding-face orientation;
- neck–lever angle;
- converter error;
- F8 preload;
- converter and head-orientation energy;
- steric compatibility;
- a persistent actin material coordinate.

Binding latched the live Brownian pose. It did not teleport or reconstruct the motor into the ideal configuration.

Across 260 independent episodes:

- 252 captured successfully;
- acceptance was 96.9%;
- capture pose clustered around \(\phi=29.0\pm11.2^\circ\);
- head orientation clustered around \(\psi=-1.1\pm8.3^\circ\);
- no accepted capture exceeded the preregistered high-strain threshold.

After passive ADP·Pi relaxation, 140 naturally captured poses all preserved:

- 5–8 nm axial stroke;
- small transverse displacement;
- skeletal-range stiffness;
- pointed-first motion;
- load sensitivity.

Spatial-only binding admitted highly misoriented, high-energy bonds. Reasonable stereospecific windows controlled capture realism but did not strongly tune the final stroke because the passive bound-state basin absorbed moderate capture variation.

This established that the successful pre-stroke conformation can be found through Brownian search rather than imposed after attachment.

## 2.4 Experiment 3F: native Pi-release transition

The next vertical slice added only:

\[
\mathrm{ADP\cdot Pi}\rightarrow\mathrm{ADP}
\]

with the converter target switching from \(-30^\circ\) to \(+30^\circ\) in the fixed motor material frame.

Naturally captured motors underwent:

1. Brownian search;
2. stereospecific capture;
3. passive ADP·Pi relaxation;
4. Pi release;
5. the converter power stroke;
6. a stable ADP dwell.

Across 118 events:

| Observable | Result |
|---|---:|
| Axial pointedward stroke | \(6.90\pm0.04\) nm |
| Transverse displacement | \(0.10\pm0.06\) nm |
| Post-stroke stiffness | \(0.624\pm0.004\) pN/nm |
| Force on actin | pointed-directed |
| Stable post-stroke dwell | 118/118 |
| Load-sensitive stroke | 118/118 |

The preload distinction was also resolved:

- capture preload: about 1.49 pN;
- relaxed internal F8 preload: about 0.10 pN;
- externally observed axial pre-transition trap force: approximately zero.

Thus the optical-trap step began from a nearly force-free axial baseline.

---

# 3. Experiment 3G-A: blinded optical-tweezers data generation

## 3.1 Scientific question

The assay asked:

> Given only realistic laser-tweezers observables, what motor step size and complete attached-crossbridge stiffness would an independent experimentalist infer?

The analyst was not given motor coordinates, chemical-state labels, event times, assigned spring constants, expected step size, or private truth.

## 3.2 Dataset structure

The generator produced a dual-trap actin dumbbell with filament Brownian motion enabled and naturally captured motors undergoing the validated Pi-release sequence.

The blind package contained:

- 165 stroke events;
- 15 bound-no-release traces;
- 21 controls;
- three long calibration traces;
- three trap stiffnesses: 0.02, 0.05, and 0.10 pN/nm per trap;
- assisting, near-zero, and opposing preload conditions;
- perturbations during pre- and post-stroke dwells;
- paired ideal and instrument-realistic traces.

The realistic traces used:

- 20 kHz sampling;
- 3 kHz first-order low-pass filtering;
- 0.8 nm detector noise;
- slow common-mode drift;
- approximately 10% trap-calibration uncertainty.

The private externally measured truth was:

| Quantity | Hidden truth |
|---|---:|
| Compliance-free intrinsic step | 7.85 nm |
| Pre-stroke whole-crossbridge stiffness | 0.643 pN/nm |
| Post-stroke whole-crossbridge stiffness | 0.625 pN/nm |
| Assigned F8 spring | 1.0 pN/nm, explicitly not the comparison target |

The blind and private trees were separated and hashed before analysis.

---

# 4. Independent blind analysis

## 4.1 The analyst reconstructed the assay correctly

The analyst discovered that trap separation remained constant and that preload and perturbations were common-mode translations of the trap pair.

The informative coordinate was therefore:

\[
X=\frac{x_1+x_2}{2}
\]

relative to the common-mode trap command.

The actin dumbbell behaved approximately as a rigid translating body, so the observable mechanics were modeled as:

\[
\gamma \dot X=-2k(X-c)-k_m(X-x_m)+\text{thermal noise}.
\]

This was an important success. Treating the beads independently would have produced incorrect trap and motor stiffness estimates.

## 4.2 Calibration

The analyst independently recovered the per-trap stiffnesses:

| Level | Truth | Blind estimate |
|---|---:|---:|
| A | 0.0200 | 0.0206 pN/nm |
| B | 0.0500 | 0.0504 pN/nm |
| C | 0.1000 | 0.0992 pN/nm |

The errors were approximately +3.0%, +0.8%, and -0.8%.

## 4.3 Step inference

The apparent step decreased as trap stiffness increased, which is the expected signature of compliance division between the motor and the traps.

The analyst recognized severe selection bias at the stiffer trap levels: smaller apparent events were disproportionately missed. The primary zero-load estimate was therefore based on the softest traps, where the detection rate was highest and the compliance correction was smallest.

Blind result:

\[
d_0=7.75\ \mathrm{nm}
\]

compared with hidden truth:

\[
d_{\mathrm{true}}=7.85\ \mathrm{nm}.
\]

The error was approximately **-1.3%**.

The analyst also recovered the correct pointed-first filament motion.

## 4.4 Crossbridge stiffness inference

Blind results:

| Quantity | Hidden truth | Blind estimate |
|---|---:|---:|
| Pre-stroke stiffness | 0.643 | 0.527 [0.406, 0.648] pN/nm |
| Post-stroke stiffness | 0.625 | 0.582 [0.477, 0.687] pN/nm |

The pre-stroke estimate was about 18% low but contained the truth within its confidence interval. The post-stroke estimate was about 7% low and also contained the truth.

The analyst concluded that the difference between pre- and post-stroke stiffness was not identifiable. This was correct: the hidden difference was only 0.018 pN/nm, approximately 2.9%, below the assay's effective systematic resolution.

## 4.5 Event detection and secondary failures

After unblinding:

- 124 of 165 true strokes were detected;
- 41 true strokes were missed;
- no strokes were called in no-motor controls;
- two of 15 bound-no-release traces were falsely called strokes.

The primary mean step remained accurate because the soft-trap condition retained high detection efficiency. However, the event detector was not adequate for kinetics or condition-by-condition event probabilities.

The inferred load dependence of the step was also incorrect. The hidden step was nearly flat over \(\pm1\) pN, whereas the blind analysis inferred a much larger load effect. This likely resulted from selection bias, limited preload range, and degeneracy between post-stroke stiffness and load-dependent displacement.

Therefore:

- **zero-load step:** validated;
- **whole-crossbridge stiffness:** validated within experimental uncertainty;
- **polarity:** validated;
- **pre/post stiffness difference:** correctly judged non-identifiable;
- **step load dependence:** not validated;
- **kinetics and dwell times:** not identifiable.

---

# 5. How realistic was the optical-tweezers challenge?

## 5.1 Features that were close to laboratory practice

The package reproduced several central features of a three-bead myosin assay:

- an actin dumbbell held by two traps;
- a single surface-anchored motor;
- thermal bead fluctuations;
- reduced variance after attachment;
- common translation during a working stroke;
- trap calibration by equilibrium and spectral methods;
- finite sampling and bandwidth;
- detector noise and drift;
- stiffness inference from thermal fluctuations and driven response;
- a step and stiffness in a biologically plausible range.

For the narrow question of whether motor step and attached stiffness were externally measurable, the package was experimentally meaningful.

## 5.2 Important simplifications

The apparatus was simpler than a literal laboratory assay.

### Mechanically simplified dumbbell

The simulated actin–bead assembly behaved nearly as a rigid body. Real experiments may include important compliance from:

- long actin filaments;
- bead–actin attachments;
- motor–pedestal attachments;
- surface or substrate coupling;
- imperfect pretension;
- out-of-plane motion.

In the blind package, the traps were essentially the only important instrument compliance besides the motor.

### Incomplete biochemical cycle

The traces contained capture, relaxation, Pi release, and a stable ADP dwell, but not:

- stochastic ADP release;
- ATP detachment;
- recovery;
- rebinding;
- full duty-cycle variation;
- long continuous event records.

The assay could therefore validate mechanics, but not kinetics.

### Simplified detector model

The realistic arm included noise, filtering, drift, and calibration uncertainty, but not every laboratory artifact, such as:

- QPD voltage nonlinearity;
- cross-talk;
- bead-size variation;
- surface-dependent hydrodynamic drag;
- trap misalignment;
- actin-link slipping;
- multiple accessible motors;
- uncertain filament height.

### Suboptimal perturbation protocol

The small perturbation pulses were short relative to the dumbbell relaxation time, especially in the softest traps. The analyst had to fit explicit dynamics rather than measure steady plateaus.

A better protocol would use either:

- pulses lasting several relaxation times;
- slower triangular ramps;
- or sinusoidal/chirp forcing analyzed through a transfer function.

---

# 6. How much did access to the idealized dataset help the analyst?

A real experimentalist would not possess a noise-free twin for every trace. The blind analyst did.

The exact counterfactual cannot be known without repeating the analysis on a realistic-only holdout, but the report allows a reasoned estimate.

## 6.1 Results likely to survive without ideal traces

### Trap calibration

The trap stiffnesses were recovered independently from the realistic calibration recordings. The ideal twins were not necessary for this.

### Pre-stroke stiffness

The primary pre-stroke estimate came from the realistic preload dependence and attached variance, with agreement across trap levels. It would likely remain near 0.5–0.6 pN/nm without ideal data, although the systematic uncertainty would be larger.

### Zero-load step

The step estimate was derived primarily from level-A realistic traces, where:

- the traps were much softer than the motor;
- the compliance correction was only about 6–8%;
- stroke detection was approximately 95% among attached traces;
- the final realistic estimate already agreed closely with truth.

The central approximately 8 nm conclusion would probably survive.

## 6.2 Results that would become weaker

### False-positive diagnosis

Seven small positive realistic stroke calls had no matching ideal call. The ideal twins made it possible to identify them as likely marginal false positives.

Without ideal traces, these events would still have been retained, but the analyst would have had less evidence that they were spurious. The level-A apparent-step mean would likely remain slightly biased toward smaller magnitude.

### Post-stroke stiffness

The realistic post-stroke estimate was 0.582 pN/nm, whereas the ideal analysis gave about 0.485 pN/nm. The disagreement helped demonstrate that the pre/post difference was not trustworthy.

Without ideal data, the analyst might have placed too much confidence in the realistic indication that post-stroke stiffness exceeded pre-stroke stiffness.

### Instrument-correction validation

The analyst modeled:

- filter-induced variance loss;
- detector-noise contribution;
- finite-window bias.

These corrections could be derived from the realistic data and controls, but the ideal twins provided a direct check that the corrected realistic estimates agreed with a cleaner measurement.

Without ideal traces, the same corrections would be plausible but less strongly validated.

### Selection and detection bias

The ideal twins helped reveal which realistic calls were noise-sensitive and quantified paired step bias. Without them, missed-event and sign-error behavior would be harder to diagnose before unblinding.

## 6.3 Likely realistic-only outcome

The most likely result without ideal traces is:

- zero-load step still identified near 8 nm, with a wider uncertainty;
- pre-stroke stiffness still identified near 0.5–0.6 pN/nm;
- post-stroke stiffness estimated more weakly;
- no defensible claim about a pre/post stiffness change;
- more uncertainty about false strokes and missed events;
- less confidence that instrument corrections removed all stiffness bias.

Thus the ideal data were not necessary for the main step-size conclusion, but they materially strengthened error diagnosis and prevented overinterpretation of secondary stiffness differences.

---

# 7. Is it worth making the assay more realistic now?

## 7.1 Full realism: not yet

A comprehensive laboratory digital twin would require:

- a longer actin dumbbell;
- explicit bead–actin linkage compliance;
- realistic pretension;
- motor–pedestal compliance;
- out-of-plane dynamics;
- raw detector-voltage channels;
- full calibration uncertainty;
- stochastic attachment and detachment;
- long continuous recordings;
- duty-ratio and dwell-time inference;
- multiple-motor contamination controls.

Much of that work is most valuable only after the motor has a closed biochemical cycle. Building it now would validate a more realistic instrument against an incomplete motor that cannot yet generate natural dwell-time and detachment statistics.

It would also risk diverting effort from the central model-development sequence.

## 7.2 A limited follow-up: worthwhile now

One compact follow-up would directly address the largest procedural limitation without expanding scope excessively.

### Recommended realistic-only holdout

Generate a new, smaller sealed package with:

- realistic traces only;
- no ideal twins available to the analyst;
- fresh trace IDs and seeds;
- a frozen analysis plan;
- approximately 50–100 stroke events;
- long enough pre- and post-stroke intervals;
- a better stiffness perturbation protocol;
- sufficient bound-no-release controls.

The analyst should apply the frozen method once, without strategy changes.

This would answer:

> Did access to ideal twins materially enable the successful step and stiffness estimates?

The existing experiment strongly suggests the main answer will survive, but only a realistic-only holdout can establish that directly.

## 7.3 Full realistic challenge: later

After the minimal chemomechanical cycle is closed, a second-generation optical-tweezers challenge should include:

1. stochastic ADP release and ATP detachment;
2. long continuous recordings;
3. realistic event censoring and missed short dwells;
4. finite bead–actin and pedestal compliance;
5. properly designed slow or frequency-domain stiffness forcing;
6. realistic-only development and holdout partitions;
7. raw detector-like channels;
8. possible multiple-motor contamination.

At that point the assay can test not only step and stiffness, but also:

- duty ratio;
- attachment duration;
- load-dependent release;
- event-rate inference;
- kinetic identifiability.

---

# 8. Recommended project sequence

## Immediate

1. Preserve the blind report, scripts, hashes, and unblinding scorecard unchanged.
2. Save this synthesis as the controlling interpretation of Experiment 3G.
3. Optionally run the compact realistic-only holdout.
4. Do not revise the existing blind pipeline after seeing truth and then reuse the same package.

## Next motor-development stage

Close the minimal single-motor cycle:

\[
\text{search}
\rightarrow
\text{bind ADP·Pi}
\rightarrow
\text{Pi-release stroke}
\rightarrow
\text{ADP release}
\rightarrow
\text{ATP detachment}
\rightarrow
\text{recovery}.
\]

Initially use a simple controlled release law, then validate signed-load catch-release in isolated force-clamp assays.

## Early ensemble stage

Begin sparse ensemble tests as soon as the minimal cycle closes. Do not wait for every kinetic detail to be perfected.

Progress through:

1. one cycling motor;
2. several independent motors;
3. sparse one-filament gliding;
4. moderate density;
5. full density sweep;
6. comparison with the earlier fine-\(dt\) canonical gliding curve as a regression benchmark.

## Later optical-tweezers stage

Return to the publication-grade realistic assay once the cycle can generate natural kinetics and detachment.

---

# 9. Final scientific interpretation

The two-body motor has crossed an important threshold.

It now has:

- a mechanically recognizable head–converter–lever topology;
- corrected intrinsic handedness;
- a geometry that converts converter motion into an approximately 7 nm axial working stroke;
- a stereospecific Brownian binding basin;
- a native Pi-release transition;
- externally observable attached stiffness around 0.6 pN/nm.

The blinded analysis recovered:

- a 7.75 nm compliance-free step versus a hidden 7.85 nm truth;
- pre- and post-stroke whole-crossbridge stiffness within experimental uncertainty;
- correct pointed-first polarity;
- the correct non-identifiability of the tiny stiffness-state difference.

The challenge also revealed real experimental limitations:

- incomplete stroke detection;
- nonzero false calls in attached no-release controls;
- strong selection bias in stiff traps;
- poor load-dependence inference;
- a perturbation protocol too fast for simple steady-state stiffness measurement;
- overconfidence risk when ideal twins are available.

The appropriate conclusion is neither “the assay was perfectly realistic” nor “the result was merely synthetic.”

It is:

> The model's central single-molecule mechanics are experimentally visible and independently recoverable in a credible optical-tweezers assay. A modest realistic-only holdout is justified now; a full laboratory digital twin should wait until the motor has a closed stochastic biochemical cycle.

---

# 10. Primary project records

This synthesis draws on:

- `docs/TWOBODY_TOPOLOGY_CORRECTION.md`
- `docs/TWOBODY_AXIAL_GEOMETRY.md`
- `docs/TWOBODY_BINDING_CAPTURE.md`
- `docs/TWOBODY_PI_STROKE.md`
- `docs/TWOBODY_BLIND_TWEEZERS_GENERATION.md`
- the frozen independent `REPORT.md`
- `analyst_results.json`
- `RUN_LOGS/twobody_blind_tweezers/unblind_comparison.txt`

Suggested project filename:

`docs/TWOBODY_BLIND_TWEEZERS_SYNTHESIS.md`
