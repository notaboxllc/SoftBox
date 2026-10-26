# Independent review: myosin–actin capture model and binding criteria

**Review date:** 2026-10-26  
**Repository:** [notaboxllc/SoftBox](https://github.com/notaboxllc/SoftBox)  
**Reviewed ref:** `motor-binding-review`  
**Scope:** literature and biological plausibility review of the motor construction and actin-capture rules. This is a read-only review; it does not change model parameters or code.

## Executive assessment

The most important modeling concern is the **one-step capture rule**: a motor binds deterministically only when it is already in a near-strong-binding pose and passes several simultaneous hard gates. That conflates initial actin encounter with formation of the stereospecific, force-bearing cross-bridge. Primary biochemical and mechanical work supports a weak, reversible actin-bound state followed by a distinct transition to stronger binding. A two-stage scheme is the best-supported direction for improving biological realism and avoiding artificially low attachment.

The site-normal strong pose, with the myosin head long axis directed toward the site, is a sensible target for the strong state. The restrictive part is demanding that pose at first contact, then charging related orientational strain again through a stiff dock, a finite energy budget, and a hard polarity gate. The current source also selects the nearest geometrical site before applying some motor-pose gates; if that site fails, other candidate sites are not tried.

The dated model note reports 0.13–0.31 mean bound heads at 400 heads/µm². This is low engagement, but the cited literature benchmark is not a precise like-for-like prediction: it combines different assay formats, densities, and single-headed versus two-headed motors. Treat the direction of the discrepancy as useful evidence, not the quoted 4–8× as a calibrated error factor.

## Version and condition notes

The focal [model snapshot](MOTOR_MODEL_AND_BINDING_2026-10-26.md) describes a 400 heads/µm², η = 0.01 Pa·s run. The branch also contains a later [long-glide report](SITE_NORMAL_LONG_GLIDING_ASSAY.md) using 3,000 heads/µm² and η = 0.1 Pa·s. These are different assay conditions and should not be compared as a direct before/after test of binding criteria.

The branch's [long-glide harness](../../softbox/SiteNormalLongGlideHarness.java) enables site-normal binding and retires the legacy head-side and lever-angle gates in that mode. The optional `-weak-bind` path is present in the harness, but the dated baseline command does not enable it. For a rate comparison, state the exact command, density, viscosity, timestep, runner, and binding mode beside each result.

## Findings by model feature

### 1. Encounter and strong attachment are merged

The current baseline has no explicit association rate or weakly attached state. A head that becomes eligible binds immediately if it satisfies all capture gates; a head outside those gates remains detached. In the actomyosin cycle, a weak A-state complex and its isomerization to a more strongly bound R-state are distinct. Weakly attached heads can be orientationally mobile; their attachment/detachment kinetics also depend strongly on ionic strength. This makes a broad, reversible encounter state followed by a separate strong-state transition a better biological representation than a single all-or-nothing event.

The branch's `-weak-bind` option and [design note](WEAK_BINDING_DESIGN.md) are a useful prototype direction. The draft values (including the weak-state off-rate and weak-to-strong rate) should be treated as model assumptions, not universal empirical constants: the relevant measurements vary with ionic strength, species, nucleotide state, and whether measurements are from fibers or purified proteins.

### 2. Distance and preload impose a narrow effective capture shell

Although the geometric distance threshold is 3 nm, the preload condition with `k_F8 = 1 pN/nm` and a 2 pN cap makes the effective extension less than 2 nm. This can be a defensible **contact definition** for a strong bond, but it is restrictive as the sole association mechanism. A more physical approach is to let geometry define a weak encounter region and use a finite transition probability for formation of the strong state.

The F8 spring stiffness itself is within the broad range cited by the project. The issue is the use of that strong-bond preload as an immediate capture gate, not simply the spring constant.

### 3. Orientation is biologically relevant, but the hard cutoff is not established

The model's current site-normal target is a sensible way to define a stereospecific strong pose. The 25° hard cone is less well supported. Steffen et al. found three-monomer target zones on a single actin strand and fit an effective angular target function with α ≈ 3.4, whose angular half-width is about 31°. Their result supports graded angular selectivity. It does **not** measure a universal 31° head-axis tolerance for an S2-tethered motor; the fit includes compliance in the actin, myosin, and assay geometry, and the paper notes that S2 attachment should allow a wider orientation range.

Use that work as support for a soft, condition-dependent orientation weight in the transition to strong binding, not as a literal hard cone. Retain the site-normal geometry as the target.

### 4. Polarity should affect productive conversion or stroke, not necessarily attachment

The 25° barbed-end polarity condition has no clear biological basis as a hard initial attachment requirement for a single-headed surface-tethered motor. Head polarity and actin geometry influence the direction and size of displacement, but a head that is poorly aligned to generate the desired axial stroke need not be incapable of attaching. A graded polarity factor on strong-state conversion or axial stroke contribution is more plausible. If a hard restriction is retained for a particular modeled construct, it needs a specific structural or experimental justification.

### 5. The energy budget can compound the geometric gates

The model uses a 15 kT energy cutoff with a stiff orientation/dock potential (the dated note gives `k_B = 512 pN·nm/rad²`). At 25°, one such harmonic term costs about 11.8 kT; two comparable misalignment terms exceed the budget before converter strain is included. Thus the nominal angular thresholds are not independently available: candidates near both limits are rejected by the combined energy test.

Steffen et al.'s fitted α corresponds to an effective angular energy scale of about 14 pN·nm for the full compliant assay system; their rough estimate for myosin torsional compliance is about 40 pN·nm. These are much softer than 512 pN·nm, but the comparison is not direct: the measured target function folds several compliant elements together, while `k_B` is a model-specific bound-pose stiffness. It does show that 512 lacks a clear empirical basis and merits a sensitivity study.

### 6. Candidate-site commitment is an avoidable numerical rejection

In `ChiralSiteSystem.siteGateA`, the code finds the nearest candidate site by distance and stores that winner. `siteCommitB` subsequently applies converter, energy, and polarity gates to that one site. If the winner fails those later gates, the other candidate sites are not tested for acceptance. That is a computational ordering choice, not a biological rule. Evaluate the full set of candidate sites against all acceptance conditions before selecting or resolving occupancy.

### 7. Chemistry requires a separate condition-matched review

The dated note uses a bound Pi-release/stroke rate of 10,000 s⁻¹. White, Belknap, and Webb measured 75 ± 5 s⁻¹ for the Pi-release phase at 20°C in their rabbit skeletal actomyosin experiment, which used very low ionic strength to increase actin affinity. The conditions differ, so that value should not be substituted blindly. The difference is large enough, however, that the model should state why its rate is chosen and whether the target assay's ionic strength and myosin preparation support it. Keep chemistry calibration separate from capture-geometry changes.

### 8. The site lattice is probably not the first lever

The every-fourth-monomer-per-strand lattice yields roughly 6.7 candidates per 36 nm half-repeat. Steffen et al. identified three-monomer active zones and observed single-strand interactions in their rotation-constrained assay. The lattice comparison is not one-to-one because filament rotation and accessibility differ, but the model's site count is not obviously too sparse. A sensitivity check on site spacing is useful after the capture law and candidate-site commitment are fixed.

## Recommended review sequence

1. Add per-gate and candidate-site telemetry: number of encounters, number passing each gate, first-failing gate, and whether a non-winning candidate would pass the later motor-pose gates.
2. Fix candidate selection so every geometrically viable site can be tested before the motor is rejected.
3. Compare the current strong-only path against a weak-to-strong path under matched conditions. Keep weak-state stiffness and rates explicitly labeled as assumptions, and sweep them over plausible ranges.
4. Replace hard orientation and polarity cuts at strong-state entry with a graded probability or rate. Preserve a well-defined strong bound pose.
5. Reconcile the bound Pi-release rate and other chemistry values against the actual motor species and assay conditions, separately from binding-rule changes.
6. Compare engagement only at matched motor density, viscosity, timestep, runner, and filament conditions; use multiple independent lawns or seeds.

## Primary literature

- Conibear, P. B. & Geeves, M. A. (1998). “Cooperativity between the two heads of rabbit skeletal muscle heavy meromyosin in binding to actin.” *Biophysical Journal* 75:926–937. [doi:10.1016/S0006-3495(98)77581-2](https://doi.org/10.1016/S0006-3495(98)77581-2). Describes weak A-state formation followed by isomerization to the stronger R-state.
- Schoenberg, M. (1988). “The kinetics of weakly- and strongly-binding crossbridges: implications for contraction and relaxation.” *Biophysical Journal* 54:135–148. [doi:10.1016/S0006-3495(88)82938-2](https://doi.org/10.1016/S0006-3495(88)82938-2). Reports ionic-strength effects on weak-state kinetics and binding.
- Steffen, W., Smith, D., Simmons, R. & Sleep, J. (2001). “Mapping the actin filament with myosin.” *PNAS* 98:14949–14954. [doi:10.1073/pnas.261560698](https://doi.org/10.1073/pnas.261560698). Reports three-monomer target zones, single-strand interaction in their constrained assay, and an effective angular target function.
- White, H. D., Belknap, B. & Webb, M. R. (1997). “Kinetics of nucleoside triphosphate cleavage and phosphate release steps by associated rabbit skeletal actomyosin…” *Biochemistry* 36:11828–11836. [doi:10.1021/bi970540h](https://doi.org/10.1021/bi970540h). Measures Pi release under the stated low-ionic-strength conditions.
