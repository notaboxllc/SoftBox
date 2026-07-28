# Paper Section Brief — From Target-Zone Bias to a Minimal Handed Power Stroke

**Working purpose:** Organize the paper section that revisits the Vilfan target-zone mechanism, tests it fairly under its own assumptions, identifies why it fails after thermal realism is introduced, and presents a small local-frame azimuthal component of the power stroke as a minimal alternative torque source.

**Status:** Section concept and experimental/computational plan. This is not yet final manuscript prose.

---

## 1. Central narrative

The section should follow a strict logical progression:

> **Reproduce → stress-test → explain failure → propose minimal alternative → demonstrate robust sufficiency → state limits.**

The main scientific claim should be restrained:

> A helical target-zone mechanism can generate directed actin rotation under the assumptions of the original model, but its attachment asymmetry becomes dynamically fragile when translational and rotational Brownian phase diffusion are introduced into an explicit mechanical gliding assay. A small, consistently handed azimuthal component of the working-stroke trajectory provides an alternative torque source that remains effective under those conditions.

The alternative mechanism should be presented as a **causal perturbation**, not as an inferred biological power-stroke angle.

---

## 2. Why revisit the Vilfan mechanism

The target-zone model is the natural theoretical comparator because it explains twirling without requiring an explicitly lateral power stroke. In broad terms, helical actin geometry and surface accessibility create an imbalance between attachments made before and after the center of a target zone. Axial working strokes from those asymmetric attachments then generate a net rotational bias.

The paper should not begin by stating that the Vilfan model “does not work in SoftBox.” That would be vulnerable to the criticism that the model was implemented incorrectly or under incompatible assumptions.

The required first result is:

> **Under Vilfan-like assumptions, the SoftBox implementation reproduces target-zone-induced twirling.**

Only after establishing that positive control should realism be added.

---

## 3. Model ladder for the target-zone mechanism

### Stage V0 — Reduced reproduction of the original mechanism

Build a deliberately reduced target-zone harness that preserves the assumptions responsible for the published effect:

- helical actin binding sites;
- surface-facing target zones;
- accessibility controlled by site azimuth;
- nonprocessive motors;
- axial power stroke with zero imposed azimuthal skew;
- prescribed or tightly controlled axial filament translation;
- fixed filament height;
- filament bending disabled;
- axial Brownian translation disabled;
- axial-roll Brownian diffusion disabled;
- simplified reach and attachment geometry where necessary;
- target-zone parameters mapped as closely as possible to the original model.

**Positive-control outputs:**

- nonzero attachment asymmetry across the target-zone center;
- directed rotation with the expected handedness;
- pitch or rotation-versus-velocity behavior comparable in form to the published target-zone prediction;
- zero rotation when the target-zone asymmetry is removed.

### Stage V1 — SoftBox geometry without thermal realism

Embed the same target-zone law into the current discrete actin lattice and motor geometry while still suppressing the Brownian channels that could erase helical phase.

This stage should establish that the mechanism survives translation from the reduced analytical-style harness into the SoftBox geometry.

### Stage V2 — Add realism one channel at a time

Recommended sequence:

1. explicit attachment and detachment kinetics;
2. explicit S2 compliance and motor reach;
3. axial filament Brownian translation only;
4. axial-roll Brownian diffusion only;
5. filament bending and local orientation fluctuations;
6. motor/S2 Brownian coordinates;
7. full gliding model at assay-relevant viscosity.

At each stage, retain identical target-zone parameters and measure the same causal intermediates.

---

## 4. Causal observables for target-zone failure

The key target-zone observable should be the signed attachment imbalance

\[
A_{\mathrm{TZ}}
=
\frac{N_{\mathrm{before}}-N_{\mathrm{after}}}
     {N_{\mathrm{before}}+N_{\mathrm{after}}},
\]

where “before” and “after” are defined relative to the target-zone center in helical phase.

Report through the model ladder:

- \(A_{\mathrm{TZ}}\);
- signed axial torque;
- body-fixed angular velocity;
- turns per distance;
- target-zone phase at attachment;
- distribution of pre- versus post-center attachments;
- attachment acceptance rate;
- time from target-zone entry to binding;
- target-zone residence time;
- phase-crossing count before attachment.

The persuasive result is not merely that rotation declines. It is that:

1. target-zone attachment asymmetry is initially nonzero;
2. torque and rotation track that asymmetry;
3. thermal phase diffusion collapses the asymmetry;
4. target-zone twirling disappears with it.

---

## 5. Helical-phase diffusion framework

Define a helical phase coordinate such as

\[
\chi(t)=q\,x(t)+\theta_{\mathrm{roll}}(t),
\]

where \(q\) maps axial translation to actin helical phase.

An effective phase-diffusion coefficient can be written approximately as

\[
D_{\chi}
=
q^2D_x+D_{\mathrm{roll}}
+2q\,\mathrm{Cov}(x,\theta_{\mathrm{roll}}).
\]

For a target-zone half-width \(\Delta\chi\), compare

\[
t_{\mathrm{drift}}=
\frac{\Delta\chi}{|\dot{\chi}|}
\]

with

\[
t_{\mathrm{diff}}=
\frac{\Delta\chi^2}{2D_{\chi}}.
\]

A useful target-zone Péclet-like number is

\[
\mathrm{Pe}_{\mathrm{TZ}}
=
\frac{|\dot{\chi}|\,\Delta\chi}{2D_{\chi}}.
\]

Interpretation:

- \(\mathrm{Pe}_{\mathrm{TZ}}\gg1\): directed passage dominates; the before/after attachment bias can survive;
- \(\mathrm{Pe}_{\mathrm{TZ}}\ll1\): repeated diffusive crossing erases the bias before attachment.

This allows the negative result to be stated positively:

> The target-zone mechanism is geometrically possible but dynamically fragile to helical-phase diffusion.

---

## 6. Viscosity as a discriminating perturbation

The current coherent viscosity study is especially useful because lowering viscosity affects the two candidate mechanisms differently.

For the current explicit-S2/discrete-site/linear-ramp motor, reducing viscosity from 0.1 to 0.01 Pa·s:

- increased gliding speed only about 1.6-fold;
- increased engagement about 2.3-fold;
- increased attachment and stroke flux about 2.8-fold;
- increased observable odd angular velocity about 23-fold;
- increased turns per distance about 14-fold;
- did not produce a clean monotonic increase in odd torque or full-cycle odd angular impulse.

This supports the interpretation that the handed-stroke mechanism is limited mainly by rotational mobility and recruitment, not by loss of its microscopic chiral impulse.

For the target-zone mechanism, lower viscosity should increase translational and rotational phase diffusion much more strongly than directed gliding. The target-zone drift-to-diffusion ratio may therefore decrease as the assay approaches aqueous conditions.

This creates a strong comparative prediction:

- **target-zone mechanism:** becomes less robust as preattachment helical phase is randomized;
- **handed-stroke mechanism:** becomes more observable because torque is injected after attachment and rotational drag falls.

This contrast must be tested directly rather than inferred only from scaling.

---

## 7. Literature bridge to an azimuthal stroke component

The literature review should support only a modest structural premise:

> Actomyosin interactions can contain lateral forces, torques, azimuthally varied attached conformations, or small azimuthal components of lever/converter motion.

It should not claim that any experiment directly measures the model parameter \(\epsilon\), nor that fast skeletal myosin II has a known 5°, 10°, 15°, or 20° azimuthal power stroke.

Suggested literature categories:

- experimental observations of predominantly left-handed actin twirling in myosin-II motility assays;
- structural studies showing azimuthally distributed or skewed attached lever-arm conformations;
- structural or time-resolved studies reporting nonzero azimuthal motion in actomyosin or other myosin classes;
- evidence for lateral force or torque generation by myosin;
- the original Vilfan target-zone theory.

**Citation caution:** structural angles from insect-flight-muscle or non-myosin-II systems are evidence for plausibility, not calibration of \(\epsilon\).

---

## 8. Minimal handed power-stroke mechanism

Define a small azimuthal component in the local actin frame, developed continuously with converter progression.

Preferred implementation:

- linear progress ramp;
- approximately zero waiting-state skew;
- unchanged total working displacement;
- unchanged chemistry;
- unchanged binding law;
- unchanged S2 mechanics;
- no imposed external torque;
- chirality defined in the local actin frame;
- sign reversal under \(\epsilon\rightarrow-\epsilon\);
- sign reversal under actin-helicity mirror.

The linear ramp is preferred over always-active skew because it avoids a fully developed chiral preload in the waiting state. It is not claimed to represent measured structural kinetics.

---

## 9. Skew-angle sufficiency study

Use a small causal perturbation map:

\[
\epsilon=0^\circ,\ 5^\circ,\ 10^\circ,\ 15^\circ.
\]

Run at the selected assay-relevant viscosity, with 0.01 and 0.02 Pa·s as the currently validated working points. Use both signs of \(\epsilon\), matched seeds, and at least one actin-helicity mirror at a resolved nonzero angle.

### Primary mechanistic outputs

- odd axial torque;
- body-fixed odd angular velocity;
- accumulated odd roll;
- odd angular impulse by cycle phase;
- turns per distance;
- mirror and sign reversal;
- gliding velocity and engagement;
- total and axial working displacement.

### Phenomenological outputs

- pitch distribution;
- fraction of trajectories classified as clear twirlers;
- handedness among clear twirlers;
- irregular/non-twirling fraction;
- roll-versus-time linearity;
- minimum accumulated rotation over the observation window.

### Desired conclusion

The desired result is not a best-fit angle. It is a robust band:

> Small local-frame skews of approximately 5–15° generate mirror-controlled actin twirling over the experimentally observed order of pitch while preserving axial gliding and an approximately 8 nm total working displacement.

The angle is a mechanistic perturbation, not a biological parameter estimate.

---

## 10. Experimental-style classifier

The paired odd estimator is the strongest causal diagnostic, but it is not the same statistic used experimentally.

Apply a second analysis to independent single-chirality trajectories using experimental-style criteria such as:

- minimum physical observation time;
- minimum accumulated rotation, for example at least half a turn;
- stable handedness;
- adequate roll-versus-time linearity;
- rotational drift distinguishable from diffusion;
- no use of hidden chemistry or paired-sign information.

Report separately:

- clear-twirler fraction;
- pitch distribution among clear twirlers;
- handedness fraction among clear twirlers;
- irregularly rotating fraction;
- non-twirling fraction.

Do not tune classifier thresholds after examining angle-specific outcomes. Where exact experimental thresholds cannot be reproduced because simulations are shorter, state the approximation and test threshold sensitivity.

---

## 11. Proposed figure sequence

### Figure 1 — Reproduction of the target-zone mechanism

Panels:

- actin helix and surface-facing target zones;
- definition of before/after target-zone attachments;
- attachment asymmetry under reduced assumptions;
- reproduced rotation or pitch-versus-velocity relationship;
- zero-asymmetry control.

**Message:** SoftBox can reproduce target-zone twirling under the assumptions that generate it.

### Figure 2 — Progressive loss under thermal realism

Panels:

- \(A_{\mathrm{TZ}}\) through the model ladder;
- signed torque and rotation through the same stages;
- example helical-phase trajectories;
- \(\mathrm{Pe}_{\mathrm{TZ}}\) or drift-time/diffusion-time ratio;
- viscosity dependence of phase randomization.

**Message:** Brownian helical-phase diffusion destroys the attachment asymmetry that carries the target-zone mechanism.

### Figure 3 — Minimal handed working stroke

Panels:

- local-actin-frame geometry;
- converter progression and linear skew ramp;
- zero waiting-state skew and developed poststroke skew;
- sign reversal for \(\epsilon\rightarrow-\epsilon\);
- actin-helicity mirror reversal;
- torque-to-roll closure.

**Message:** A small postattachment handed stroke supplies a direct, mechanically closed torque source.

### Figure 4 — Robustness across modest skews

Panels:

- turns per distance versus \(\epsilon\);
- pitch range versus experimental band;
- gliding velocity versus \(\epsilon\);
- total and axial step versus \(\epsilon\);
- clear-twirler fraction and handedness;
- viscosity comparison at 0.01 and 0.02 Pa·s.

**Message:** Twirling does not require a finely tuned or large angle; a modest 5–15° range produces the correct order of behavior without disrupting gliding.

---

## 12. Claims the section may support

### Strong claims

- The reduced implementation reproduces target-zone-induced twirling under Vilfan-like assumptions.
- Translational and rotational Brownian phase diffusion reduce the before/after target-zone attachment asymmetry.
- Collapse of the attachment asymmetry is accompanied by collapse of target-zone torque and rotation.
- A small local-frame handed component of the working stroke produces signed, mirror-reversing twirling.
- The alternative mechanism preserves ordinary gliding and the approximate total working displacement.
- A range of modest skews can produce the experimental order of pitch.

### Qualified claims

- The target-zone mechanism may be dynamically fragile in thermally mobile gliding assays.
- A handed working stroke is a plausible alternative source of torque.
- Lower solvent viscosity enhances observability of the handed-stroke phenotype mainly through mobility and recruitment.

### Claims to avoid

- The Vilfan mechanism is universally wrong.
- Brownian motion eliminates all target-zone effects in every assay.
- The biological power stroke has a measured skew of 5–15°.
- The model identifies the unique molecular source of myosin-II twirling.
- Agreement with one pitch value calibrates \(\epsilon\).
- A structural angle measured in another myosin or muscle system is equivalent to the model’s \(\epsilon\).

---

## 13. Required controls

### Target-zone arm

- no target-zone asymmetry;
- reversed actin helicity;
- reversed imposed translation where appropriate;
- Brownian channels added individually;
- matched target-zone parameters through the ladder;
- attachment phase histograms;
- prescribed-motion positive control.

### Handed-stroke arm

- \(\epsilon=0\);
- \(+\epsilon\) and \(-\epsilon\);
- actin-helicity mirror;
- torque-arm removal or equivalent causal ablation;
- total-stroke conservation;
- axial-step readout;
- gliding preservation;
- timestep refinement at the selected viscosity;
- no old binding skew, interface-step skew, target-zone mechanism, or roll spring.

---

## 13b. Low-ATP condition transfer (exploratory pilot, 2026-07-28)

The experimental twirling assay ran at approximately 5-20 uM ATP to slow translation for tracking. A bounded
pilot asked whether the frozen motor reproduces that assay manipulation when only ATP concentration is changed.
Full report: `docs/twirling/LOW_ATP_GLIDING_TWIRLING_FINDINGS.md`.

ATP enters through the model's own nucleotide law: `nucParams[1]` (`atpOn`, NONE->ATP) is the sole
concentration-dependent transition, a pseudo-first-order hazard frozen at 2.0e4/s for saturating ATP, scaled
linearly and anchored on the project's own declared saturating condition (2 mM). No rate constant was invented
or fitted, and no motor parameter was retuned. 16 arms, 4 ATP x 2 eps x 2 seeds, 200 ms, eta = 0.01 Pa.s.

What the section may use:

- **Gliding transfers cleanly.** v_even falls 25.7x monotonically from 2 mM to 5 uM (-4.19 -> -0.163 um/s) with
  tight seed agreement, placing 5 and 10 uM inside the reported 0.1-0.5 um/s band with nothing tuned. Bound
  population rises 4.5 -> 34.7 and rigor occupancy 0.08 -> 0.94; detachment stays 100% ATP-triggered; the
  pre-stroke lifetime is invariant at ~100 us.
- **Rotation does not follow.** Omega_odd stays between -40 and -71 rad/s across a 400x ATP range, and measured
  rotation closes against independently accumulated torque at 1.033 +/- 0.031 (8/8 sign agreement) -- so the flat
  rotation is a flat TORQUE, not an observable artifact. Turns-per-um therefore rises from -2.53 to -58.8 purely
  because v_even collapses.
- **The pitch comparison is negative, and should be stated as such.** The model reproduces the experimental
  pitch (0.47 +/- 0.20 um) at SATURATING ATP (-0.396 um) but not at the concentration the experiment used: at
  5 uM the model gives -0.017 um. The reported insensitivity of pitch to filament velocity is not reproduced.
- **Powering caveat that must travel with any of the above rotation numbers.** At n=2 the across-ATP variation
  in Omega_odd and tau_odd is SMALLER than the seed-to-seed scatter within one concentration, 9 of 16 arms fail
  window stability, the eps-even rotational background reaches 1.08x the odd signal, and every confidence
  interval includes zero. The direction and order of the pitch shift are supported; the magnitude is not.

Not run and therefore not claimable: eps = 0 null at low ATP, low-ATP mirror control, any n >= 4 statistics.

## 14. Open computational work

1. Reconstruct the original Vilfan assumptions and target-zone parameters from the paper and supplements.
2. Build the reduced positive-control harness.
3. Establish reproduction gates before adding SoftBox realism.
4. Implement and validate target-zone phase telemetry.
5. Quantify drift, diffusion, and \(\mathrm{Pe}_{\mathrm{TZ}}\).
6. Complete the channel-by-channel Brownian ladder.
7. ~~Extend low-viscosity twirling statistics and required mirror control.~~ **DONE** - the viscosity campaign
   and its mirror control are complete (`docs/VISCOSITY_SENSITIVITY_FINDINGS.md`).
8. Run the 0°, 5°, 10°, 15° angle map at selected viscosity.
9. Develop a preregistered experimental-style twirling classifier.
10. Validate the axial step for the final illustrative skew using the blinded tweezers assay.
11. **Low-ATP follow-up (new, from §13b).** Extend selected low-ATP conditions to n = 4 (~11 h) so Omega_odd
    itself is resolved at every ATP; do NOT use n = 8 for the plateau question (it still falls short at
    spread/SEM 2.61), and reserve n ~ 16 (~76 h) for publication work only if ATP-independence of chiral torque
    becomes a claim. Before extending, add the eight per-head reduction fields (signed torque split by sign and
    by nucleotide state; axial puller/dragger classification) -- the pilot could not diagnose torque cancellation
    or axial-rotational decoupling at all without them.

---

## 15. Candidate section title

Preferred:

> **Thermal phase diffusion suppresses target-zone twirling, whereas a small handed working stroke provides a robust torque source**

Alternatives:

- **From helical target-zone bias to a direct chiral working stroke**
- **Two routes to actin twirling: phase-sensitive attachment bias and a phase-robust handed stroke**
- **A target-zone mechanism is thermally fragile in an explicit actomyosin gliding model**

---

## 16. Candidate manuscript transition

> The helical actin lattice provides an appealing route by which a nominally axial power stroke could generate rotation. We therefore first asked whether our model could reproduce the target-zone mechanism under the assumptions for which it was proposed. After establishing this positive control, we introduced explicit compliance and thermal motion one component at a time. These additions progressively randomized the helical phase before attachment and removed the attachment asymmetry responsible for the predicted torque. We next tested whether a small handed component of the working-stroke trajectory could provide a torque source that did not depend on preserving preattachment phase.

---

## 17. Candidate concluding paragraph

> We first reproduced target-zone-induced twirling under the assumptions of the original model. In the explicit mechanical assay, however, translational and rotational fluctuations rapidly randomized the helical phase and eliminated the attachment asymmetry responsible for rotation. We therefore tested a distinct minimal mechanism motivated by evidence that actomyosin interactions can contain lateral or azimuthal components. Adding a small handed component to the working-stroke trajectory generated robust, mirror-reversing twirling while preserving axial gliding. Skews of 5–15° spanned the experimentally observed order of pitch, but were treated as causal perturbations rather than estimates of the biological power-stroke geometry.

---

## 18. Project-result context to preserve

- The linear progress ramp is mechanistically cleaner than the always-active skew because the waiting state is essentially unskewed.
- The powered comparison did not prove that the linear ramp is stronger than the always-active mechanism.
- Mechanically free S2 mean length affects gliding, but the tested 30/40 nm quenched mixture behaves like a homogeneous lawn with the same mean.
- The tested S2 heterogeneity does not materially change population twirling.
- The legacy 0.1 Pa·s viscosity suppresses recruitment and observable twirling.
- At 0.02 Pa·s, the 15° skew produced approximately 1.94 turns/µm; at 0.01 Pa·s, approximately 3.12 turns/µm.
- These rates should be used to motivate a modest-angle map, not to fit a unique biological skew.
- The canonical motor should remain zero-skew unless and until a structural calibration exists; nonzero skew is an explicit twirl-capable variant.

---

## 19. Editorial posture

The section should read as a fair comparison between mechanisms, not a contest constructed to favor the new model.

The target-zone mechanism should receive:

- a successful reproduction;
- a causal intermediate;
- a transparent realism ladder;
- and a mechanistic explanation for failure.

The handed-stroke mechanism should receive:

- minimal perturbation;
- rigorous chirality controls;
- no fitted angle;
- and explicit limits on structural interpretation.

The scientific contribution is the distinction between a **phase-sensitive preattachment mechanism** and a **phase-robust postattachment torque source**.
