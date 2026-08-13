# SoftBox — Current Scientific State

**Updated:** 2026-07-13  
**Working branch:** `dt-convergence-study`  
**Purpose:** Controlling handoff for new chats and investigators. This document summarizes the present canonical model, settled findings, retractions, active work, and open questions. It is not a chronological lab notebook.

## Authority and reading order

When sources appear to conflict, use this order:

1. this `CURRENT_STATE.md`;
2. the newest dedicated final report on the subject;
3. executed source code and reproducible run artifacts;
4. `JOURNAL.md`;
5. older exploratory reports.

`JOURNAL.md` preserves the reasoning path, including hypotheses and conclusions that were later corrected. Use it for provenance and chronology, not as an automatically current specification.

Distinguish throughout:

- **canonical model:** what the default code executes;
- **settled result:** supported by a powered or decisive experiment;
- **working interpretation:** the best current mechanism, but not uniquely proven;
- **open question:** unresolved and eligible for further study.

---

## 1. Canonical motor and assay model

The ratified default motor stack is:

`SPHEREHEAD + AXLOCK + DIRSWING + XB_IMPLICIT2 + LYMN_TAYLOR`

The Java boolean named `CANONICAL` selects a separate experimental Config-1/two-point motor and is **not** the ratified default referred to here.

### Mechanical topology

Each motor consists of three overdamped rigid bodies:

- rod/tail;
- lever/neck;
- head.

The bodies respond to accumulated physical forces and torques plus Brownian forcing.

- **J1:** lever–head positional joint. Its native angular spring is disabled. The nucleotide-dependent `DIRSWING` torque supplies the active power-stroke potential.
- **J2:** rod–lever positional joint. Its native angular spring is zero in the canonical model.
- **Tail anchor:** tethers the rod to the substrate.
- **F8:** material-latched compliant head–actin cross-bridge.
- **F9:** fixed 90° head-orientation/perpendicularity constraint; nucleotide-independent.
- **AXLOCK:** maintains the selected assay-plane geometry.
- **DIRSWING:** the sole nucleotide-triggered stroke; its target changes from 0° in ADP·Pi to 60° in ADP.
- **XB_IMPLICIT2:** coupled implicit head/site cross-bridge correction.

The actin-side attachment coordinate is material-latched. It is recorded at binding and subsequently moves with the actin segment; it is not recomputed as a nearest point each timestep.

### Chemistry

The canonical path uses a Lymn–Taylor-like cycle with:

- ADP·Pi-only binding;
- Pi-release-associated stroke commitment;
- ADP release modulated by signed load through the catch–slip law;
- ATP-driven detachment and detached recovery.

### Protected invariants

Do not change casually:

- canonical defaults;
- J1/J2 positional-joint arithmetic;
- F8, F9, AXLOCK, DIRSWING, or anchor ordering;
- binding candidate order;
- stateless RNG keying and draw semantics;
- chemistry/update ordering;
- force-gather ordering;
- `BoA-v1ref`.

Every optional physical change should be default-off, directly testable, and leave the zero-option path unchanged.

---

## 2. Settled mechanistic findings

### 2.1 There is one power stroke, not two

`DIRSWING` is the sole nucleotide-switched conformational stroke. F9 is frozen at 90° in all nucleotide states and acts as a perpendicularity-maintaining constraint.

The older interpretation that F9 switched from 90° to 120° and supplied a second or dominant stroke was caused by a diagnostic using the wrong target. That claim is retracted.

### 2.2 DIRSWING is a finite stroke, not an energy-pumping servo

The deterministic servo/work audit found:

- about 98% of DIRSWING work is delivered in the first 0.1 ms after the ADP·Pi→ADP transition;
- cumulative work plateaus;
- closed displacement cycles give approximately zero or slightly negative net DIRSWING work;
- pose, force, work, and stroke trajectory converge with timestep when compared over equal physical equilibration times.

Per-step rotation shrinks with `dt`; coarse-step angular jumps are a discretization effect, not persistent active pumping.

### 2.3 The velocity ceiling is set by the full episode force waveform

The age-resolved episode-kernel identity is

\[
\langle I(v)\rangle
=
\int S(a|v)\,m_f(a,v)\,da.
\]

The force-zero is not explained by a simple `stroke distance / mean lifetime` relation.

The observed episode waveform consists of:

- a short early stroke transient;
- a sustained bound-ADP force component;
- eventual force reversal under sliding.

Freezing survival at its zero-velocity form while allowing the force waveform to vary preserves the force zero. Freezing the waveform while allowing survival to vary does not produce a zero. This is descriptive attribution within a survivor-conditioned kernel, not proof that mechanics and survival are causally independent.

### 2.4 Nominal stroke angle is weakly connected to effective actin-level displacement

Changing the nominal neck swing from 40° to 80° changed the peak actin-level excursion by only about 2%. The compliant multibody geometry absorbs most of the nominal conformational change.

Typical measured actin-level motion:

- early peak excursion: approximately 6 nm in the episode audit;
- late relaxed/retained displacement: approximately 0.7–1.6 nm in those stochastic measurements.

Exact deterministic values depend on the diagnostic initial pose and should not be substituted for the episode distribution.

### 2.5 xCatch is a strong force-waveform lever

Changing `xCatch` from 2.5 nm to 1.0 nm previously shifted the production-timestep clamp zero from roughly 16 to roughly 7.8 µm/s. The paired change was approximately

\[
\Delta V_0=-8.65\ \mathrm{µm/s},
\]

with a bootstrap interval of about `[-10.14, -7.17]`.

This change did not materially change the effective stroke displacement. It reshaped the survivor-conditioned force history and increased attachment lifetime. Therefore xCatch is not merely a scalar lifetime knob; it changes which strained histories remain represented.

This result establishes **sensitivity**, not biological justification for changing the parameter.

### 2.6 A passive angle-only J2 spring is a mechanical null

A default-off physical J2 potential was implemented and audited:

\[
U_{J2}
=
\frac{1}{2}\kappa_{J2}
(\theta_{J2}-\theta_0)^2,
\]

where the coordinate is the shortest unsigned angle between rod and lever axes and the preregistered ADP-plateau rest angle was 124°.

The native J2 coordinate is broad:

- bound ADP·Pi: roughly 103° ± 39° at zero clamp velocity;
- bound ADP plateau: roughly 122° ± 25°;
- the late mean changes little with sliding velocity;
- attachment age explains more of the progression than load or velocity;
- correlations with impulse, lifetime, and retained displacement are weak.

Weak springs select a markedly different internal pose even while storing far below `kT`, showing that the coordinate is nearly neutral and geometrically underdetermined. Stronger springs eventually create preload and internal-torque concerns.

Across deterministic transmission, powered force–velocity, and valid-density tests, the spring:

- changed J2 pose and early force history;
- reduced some transverse loading;
- did not monotonically improve retained displacement;
- did not increase useful axial force per bound motor;
- did not measurably change force–velocity slope or \(V_0\);
- did not shift the density knee over the valid tested range.

**Canonical decision:** keep the free J2 hinge. Retain the physical spring and diagnostics only as default-off tools. This rules out the tested state-independent angle-only cone potential as a useful canonical addition; it does not rule out every possible full-frame, twist, or dihedral conformation. Such alternatives require independent structural justification.

---

## 3. Current quantitative reference for clamp \(V_0\)

A dedicated eight-seed, equal-physical-duration timestep study measured the rigid-clamp force–velocity zero at:

| timestep | fitted \(V_0\) |
|---|---:|
| \(1.0\times10^{-5}\) s | 16.07 µm/s |
| \(5.0\times10^{-6}\) s | 13.53 µm/s |
| \(2.5\times10^{-6}\) s | 13.01 µm/s |
| \(1.25\times10^{-6}\) s | 13.41 µm/s |

At fixed velocities 12, 14, and 16 µm/s, the production→5 µs force change is significant, while subsequent halvings are unresolved.

The controlling statement is:

> Over the tested fine-timestep range, the canonical rigid-clamp force zero is operationally stable at approximately \(13.3\pm0.5\) µm/s. The routine \(10^{-5}\) s timestep gives approximately 16.1 µm/s, an upward bias of about 2.7 µm/s or 20%.

This is an empirical fine-timestep reference, not a mathematical proof of the `dt→0` limit.

At finer timestep, attachments become more numerous and longer-lived while net forward impulse per episode decreases. The remaining timestep sensitivity is localized to the coupled sustained-force-under-sliding cycle, not the isolated DIRSWING stroke.

Routine simulations may continue at \(dt=10^{-5}\) s for qualitative work, but production \(V_0\approx16\) must be labeled as numerically biased.

---

## 4. Free-gliding ceiling: active work

### Ongoing experiment

A matched fine-timestep free-gliding density sweep is currently running and is expected to occupy most of 2026-07-13.

Its purpose is to directly measure:

- production- versus fine-timestep velocity at fixed density;
- whether a valid operational plateau appears;
- the high-density velocity \(V_\infty\), if identifiable;
- the density knee, if identifiable;
- whether \(dt=5\times10^{-6}\) s is adequate for free-gliding observables;
- whether the unmodified canonical motor already lies near the biological operating range.

### Current resource constraint

The GPU is approximately 89% utilized by this sweep. Until it completes:

- do not launch additional GPU-heavy simulations;
- avoid concurrent jobs that could alter thermal state, timing, memory pressure, or trajectory throughput;
- prefer read-only analysis, literature/provenance work, log reanalysis, and small deterministic CPU diagnostics;
- record CPU load before starting even modest parallel work.

### Interpretation restraint

Do **not** convert clamp \(V_0\) into a free-gliding plateau by dividing by the earlier factor of about 1.4.

That factor arose from a finite-density rigid-clamp/free-glide force-balance discrepancy. It has not been demonstrated to be a multiplicative map between the clamp zero and the asymptotic free-gliding velocity.

The ongoing sweep should provide the direct answer.

### Runner caveat

Governed by `docs/CPU_GPU_VALIDATION_POLICY.md` (2026-07-22). Device-path results are **primary** once
their assay class has passed a CPU/GPU equivalence benchmark at the current revision; deterministic and
stochastic single-motor assays are bit-/event-identical CPU↔GPU. **Chaotic many-body gliding retains
targeted CPU spot checks** — documented basin sensitivity is real in that class: required when arms differ
in hot-kernel structure, for outlier/instability questions, and as one periodic mid-range check per campaign
reporting an absolute number. A CPU check is **not** required per sweep, per parameter point, or for
data-only flags. Always report the runner, and **do not mix CPU and GPU values silently in one fitted curve.**

---

## 5. Open question 1 — can binding-gate changes move saturation to lower nominal density?

### Scientific question

Can increased motor accessibility—through a larger search/capture radius, looser orientation requirements, or another defensible binding gate—move the free-gliding velocity curve leftward so that saturation is reached at lower nominal motor density?

### Current evidence

Previous interventions establish an important distinction:

- capture-radius, azimuthal-gate, graded-orientation, and finite-binding-rate changes strongly alter recruitment and bound count;
- those changes generally scale the engaged motor pool;
- they did not create a missing intrinsic velocity ceiling;
- restrictive gates often reduced velocity and bound count together rather than producing a true plateau;
- the force-zero itself is density-independent in rigid clamp.

Therefore, a binding-gate change may plausibly shift the **mapping from nominal density to effective motor engagement** without changing intrinsic single-motor mechanics.

That would be scientifically useful if stated correctly:

> A gate that moves the density knee left while preserving the high-density plateau is a recruitment-map change, not a new force-generation mechanism.

Increasing search radius or loosening an orientation restriction is expected to increase the number of reachable or bindable motors at a given nominal density. It may therefore move an observed density curve left. It may also increase bound count, internal tug-of-war, and finite-density velocity, so the net result must be measured rather than assumed.

### Clean test

After the fine-dt baseline sweep completes:

1. choose one physically interpretable recruitment change at a time;
2. keep motor mechanics and chemistry fixed;
3. run matched densities and paired seeds;
4. fit the nominal-density curve only if a plateau is actually identified;
5. replot velocity against:
   - mean available motors;
   - mean bound motors;
   - attachment flux;
6. compare \(V_\infty\), \(\rho_{1/2}\), and force per bound motor.

Key diagnostic:

- If curves differ versus nominal density but collapse versus available or bound motor count, the gate has only recalibrated effective density.
- If \(V_\infty\), force per bound head, or clamp \(V_0\) changes, the gate is affecting episode selection or mechanics and is not merely a horizontal density shift.

### Best CPU-safe analysis today

Use existing logs from:

- capture-radius sweeps;
- azimuthal hard-gate and falloff sweeps;
- finite-kOn crossover;
- biological bound-count experiments;
- previous density sweeps.

Perform a no-new-simulation curve-collapse analysis:

\[
V(\rho),\quad
V(N_{\mathrm{available}}),\quad
V(N_{\mathrm{bound}}),\quad
V(J_{\mathrm{attach}}).
\]

This could reveal whether the old interventions already support a horizontal effective-density interpretation. It is well suited to CPU-only analysis while the GPU sweep runs.

### What would justify a model change

A binding-gate change would be defensible if:

- it represents a known omitted geometric or orientational accessibility factor;
- it shifts the nominal-density knee without degrading the fine-dt high-density plateau;
- it preserves single-motor force–velocity behavior;
- the resulting effective bound-head count is biologically plausible;
- the effect is not a chamber, bed-coverage, or denominator artifact.

Do not tune search radius solely to force a desired density knee.

---

## 6. Open question 2 — can changing xCatch be biologically justified?

### Scientific question

Can the catch-path distance `xCatch` be changed from its present value in a way supported by experimental provenance or model coarse-graining, rather than chosen merely to lower gliding velocity?

### Why it matters

xCatch is currently the strongest identified \(V_0\) lever. Reducing it can substantially lower the force zero by changing the age- and velocity-dependent force waveform and force-selective survival.

Because the fine-timestep clamp reference is now about 13.3 µm/s rather than 16.1 µm/s, the amount of xCatch adjustment—if any—needed after direct free-gliding measurement may be smaller than earlier calibration exercises suggested.

### Why it is provenance-expensive

The project provenance audit traces the catch–slip parameters to skeletal HMM measurements under specific experimental conditions. Changing xCatch therefore risks overriding a measured parameter to fit an ensemble assay.

Further, `xCatch` in this coarse-grained model may not map one-to-one onto a single molecular distance if:

- the simulated force projection differs from the experimental reaction coordinate;
- head/lever/anchor compliance coarse-grains multiple structural modes;
- the empirical two-path law absorbs omitted states;
- assay temperature, ionic strength, isoform, nucleotide conditions, or load geometry differ.

A change could be justified only by demonstrating such a mapping mismatch or by adopting a clearly different biological target.

### Required evidence before changing the canonical value

1. **Primary-source provenance audit**
   - exact construct and myosin isoform;
   - temperature and ionic conditions;
   - force direction and loading geometry;
   - fitted form and parameter uncertainty;
   - whether 2.5 nm is directly measured or model-dependent;
   - whether alternative fits or later measurements support a different range.

2. **Reaction-coordinate audit**
   - identify the exact simulated force component entering the catch term;
   - compare its sign and geometry with the experimental loading coordinate;
   - test whether effective compliance means the code-level distance should be renormalized.

3. **Single-molecule validation**
   - reproduce lifetime-versus-force curves for baseline and candidate xCatch values;
   - preserve unloaded lifetime and slip-side behavior;
   - report where the candidate departs from the source data.

4. **Fine-timestep clamp validation**
   - measure the candidate at a timestep where the force waveform is stable;
   - use paired seeds and full episode-kernel attribution;
   - distinguish waveform change from survival change.

5. **Direct free-gliding validation**
   - wait for the current fine-dt baseline sweep;
   - compare plateau velocity, density knee, bound count, and continuity;
   - do not calibrate against clamp \(V_0\) alone.

### Best CPU-safe work today

The most useful non-GPU task is a primary-literature and code-provenance audit of xCatch, followed by reconstruction of the baseline and candidate force-dependent release curves.

A second CPU-only task is to reanalyze existing episode-kernel logs for:

- force distributions actually sampled by bound heads;
- the range over which xCatch materially changes hazard;
- whether the earlier 2.5→1.0 nm intervention acts mainly in a biologically sampled force regime or only in extreme tails;
- whether a smaller candidate change can be interpolated without new simulation.

### Decision rule

Do not change xCatch canonically merely because it produces a more biological gliding speed.

A canonical adjustment requires:

- a defensible mapping from experiment to the simulated reaction coordinate;
- acceptable single-molecule force–lifetime behavior;
- fine-timestep numerical stability;
- improved direct free-gliding agreement;
- no compensating damage to other validated observables.

If the biological justification remains weak, retain the measured value and describe any altered xCatch as an explicit calibrated variant.

---

## 7. Other open scientific questions

### 7.1 What is the directly measured fine-dt free-gliding plateau?

This is the active priority. It determines whether additional kinetic or structural calibration is needed at all.

### 7.2 Which compliance mode absorbs the stroke?

The J2 study argues against one missing angle-only hinge stiffness as the explanation. A future CPU-friendly small-perturbation compliance audit could partition imposed axial displacement among:

- J1 rotation;
- J2 bend;
- F8 extension;
- head orientation;
- F9/AXLOCK rotation;
- tail-anchor extension.

This should measure the existing network before proposing another spring.

### 7.3 Can production timestep recover the fine-dt force waveform through a faithful substep?

The fine-dt reference localizes the error to sustained force under sliding. A future intervention could substep or hazard-integrate the cross-bridge/chemistry coupling while retaining the outer production step. It must reproduce fixed-velocity force and episode kernels, not merely the final zero.

### 7.4 What are the correct assay density and capture parameters?

`density` and `coltol` are experimental parameters, not well-established universal canonical constants. Every gliding result must state them explicitly. Changes in these parameters may alter effective recruitment without changing intrinsic motor behavior.

### 7.5 Is the biological comparison condition-matched?

The working skeletal gliding target is around 4 µm/s under a specific low-ionic-strength, approximately 25°C assay. Ionic strength, myosin construct, surface preparation, temperature, and analysis method can materially change the comparison. Calibration claims must name the intended experimental condition.

---

## 8. Recommended order of work

1. **Finish the ongoing fine-dt free-gliding density sweep.**
2. **Analyze direct timestep shifts, plateau identifiability, and the retained CPU spot-check points** (per `docs/CPU_GPU_VALIDATION_POLICY.md` §3/§5 — chaotic-ensemble outliers and structural A/Bs only).
3. **Perform the CPU-only binding-gate curve-collapse analysis using existing logs.**
4. **Perform the xCatch primary-source and reaction-coordinate audit.**
5. Decide whether either intervention is still motivated after the direct fine-dt gliding result.
6. Only then design a small, paired, one-factor simulation study.

Do not run binding-gate and xCatch changes together initially. Recruitment mapping and force-dependent release affect different parts of the model and must be identified separately before testing interactions.

---

## 9. Current interpretation in one paragraph

The canonical SoftBox motor contains one finite DIRSWING power stroke acting through a compliant three-body constraint network. Its rigid-clamp force zero exists and is operationally stable near \(13.3\pm0.5\) µm/s at fine timestep; the production step overestimates it by about 20%. The ceiling is generated by the full survivor-conditioned attachment force waveform, not by nominal stroke distance divided by lifetime. An angle-only passive J2 spring changes internal pose but not useful output and should remain off. The immediate unresolved issue is the directly measured fine-timestep free-gliding density response. After that result, the two leading intervention questions are whether a defensible binding-accessibility change can shift the nominal-density knee without altering intrinsic mechanics, and whether any xCatch adjustment can be justified from experimental provenance and reaction-coordinate mapping rather than fitted solely to gliding speed.

---

## 9b. Parallel arc — the two-body replacement motor (3A–3G, 4A)

A separate, **non-canonical, default-off** experimental line (`-exp3*` / `-exp4a` in
`softbox/TwoBodyConverterMotor.java`, CPU-only) is developing a topologically faithful **head–converter–lever**
two-body motor as a candidate replacement for the canonical SPHEREHEAD motor, whose externally observable
compliance was much softer than intended. It does **not** touch the canonical motor, production defaults, or
`BoA-v1ref`. Status (settled results):

- **3C/3D:** corrected topology + geometry — a ~6.9 nm pointed-first axial stroke, ~0.1 nm transverse,
  skeletal ~0.64 pN/nm whole-crossbridge stiffness, load-sensitive, ~0 preload, correct handedness (fixed
  material-frame stroke sign, not `barbedDir`).
- **3E/3F:** stereospecific Brownian binding capture (no teleport) + native Pi-release stroke on natural
  captures (118/118 retain the mechanics).
- **3G/3G-A/3G-B:** blinded dual-trap optical-tweezers challenge + realistic-only holdout — an independent
  analyst recovered the compliance-free step (~7–7.85 nm, ≤~11 % error) and whole-crossbridge stiffness
  (~0.6 pN/nm) from realistic observables alone.
- **4A (2026-07-14) — FULL PASS:** the **canonical Lymn–Taylor nucleotide cycle** (`cycleLymnTaylor`) is ported
  onto the two-body motor by **reusing the kernel verbatim** over the motor's own 1-motor `MotorStore` (no second
  chemistry framework, no changed constant). The single-molecule cycle closes with no manual reset (915 cycles,
  0 forbidden transitions); ADP·Pi-only binding, the pointed-first stroke, signed-load catch–slip ADP release
  (sign established from geometry: +barbed/opposing → forceDotFil > 0 → catch), clean ATP detachment, and
  recovery/rebind all reproduce the canonical rules; the 3E/3F mechanics are preserved at baseline. Report:
  `docs/TWOBODY_BIOCHEMICAL_CYCLE.md`.
- **4B (2026-07-14) — OUTCOME A (independent composition):** N∈{1,2,3,4} independent 4A motors share one
  trap-held filament, interacting only through the shared filament mechanics + the canonical CSR force gather (no
  motor–motor coupling, no shared chemistry, no tuning). The per-motor duty ratio is **flat with N**
  (bound fraction ~0.012), occupancy is near the independent binomial (a small filament-mediated positive
  co-binding correlation only), the ADP dwell is **flat with N** (no catch-locking), native strokes stay
  predominantly pointedward (72 %), and load sharing is transient with **exact force balance** (Σ_i F_{i,∥} =
  filament force to ~1e-7 pN). Independence controls pass (inactive neighbors leave the active motor unchanged,
  fixed-seed bit-identical, index-permutation invariant). Report: `docs/TWOBODY_SPARSE_MULTIMOTOR.md`.
- **4C (2026-07-14) — first low-density GLIDING; feasibility DEMONSTRATED, motion recruitment-limited:** a free
  Brownian filament glides over a sparse bed of independent cycling two-body motors (reusing the 4B machinery +
  the canonical gliding density/bed/surface convention; filament integrated once/step). **Recruitment scales with
  density** (reachable 7/16/32 at 250/500/1000 µm⁻²), the filament **glides pointed-end-first (correct polarity)**
  at every density (per-stroke directed displacement ~+3.3 nm, Brownian-free, dt-stable), **strokes complete
  (~1.0)** before release, the filament stays in-plane (RMS drift ~1 nm), 0 forbidden transitions. Motion is
  **intermittent** (continuity 0.05→0.15; longest gap 200→64 ms) — continuous gliding is not reached at ≤1000 µm⁻²,
  limited by the low single-motor duty (~0.013, search+recovery-dominated). Net velocity is Brownian-limited at low
  duty. Report: `docs/TWOBODY_LOWDENSITY_GLIDING.md`; viewer `threejs_twobody4c_{control,low,medium,highlow}`.
  **Recommended next step: a focused recruitment/continuity study (continuity vs reachable-count / duty) to reach
  continuous coverage, then a proper gliding velocity vs the fine-dt canonical curve as a regression — not started.**
- **4D (2026-07-14) — flexible filament over a dense 2D mat; feasibility DEMONSTRATED:** corrects 4C's assay
  limits (rigid rod in a doubly-confined strip) — a flexible chain filament (12 seg, ~2.1 µm, canonical actin
  bending + Brownian, **z-only** surface so x/y/rotation/bending free) glides over a true 2D lawn (1000 µm⁻²,
  ~3000 motors; active-set spatial cull). It **glides pointed-first**, engages **independent motors along
  different sections simultaneously** (up to 7 segments), explores laterally + bends gently without leaving the
  surface, **conserves contour**, and shows no buckling/snagging; engagement (avgBound ~0.38, continuity ~0.30) is
  higher than the 4C strip. Both null controls (no-motor, binding-disabled) are clean; 0 forbidden transitions.
  **At canonical actin stiffness the 2 µm filament is nearly rigid (bend < 1°), so flexible ≈ rigid** in
  recruitment; flexibility slightly softens the per-stroke COM output (compliance). Report:
  `docs/TWOBODY_FLEXIBLE_MAT_GLIDING.md`; viewer `threejs_twobody4d_{flexible_active,flexible_control,flexible_nobind,rigid_active}`.
  **Recommended next step: a continuity/velocity study on the validated 2D-mat assay (velocity vs reachable/duty,
  toward a regression vs the fine-dt canonical curve), or a longer filament (8–15 µm) to isolate flexibility — not started.**
- **4D-ii (2026-07-14) — 2D-mat coverage audit + correction:** the 4D viewer showed articulation concentrated at
  the filament midpoint. Audit found the **simulation** candidate set (whole-chain AABB, all 12 segments) was
  already contour-complete; the defect was a **viewer-only** articulation bug (a `±0.6 µm` window centered on the
  filament midpoint clipped the ends). Fixed with a grid-accelerated **per-segment union cull** (site → any live
  segment, queryR = 30 nm derived) for both the sim cull and the viewer. **Brute-force validated** (4000-motor
  brute: the union cull omits 0 bindings; the freezing-induced trajectory divergence is the chaotic
  statistical-equivalence standard). **Coverage 100 %** of the contour (every segment 9–18 candidates; both ends
  covered). Viewer fix verified (articulation spans the full contour; 253 motors articulated near the ends where
  the old rule gave 0). **4D's numeric results and scientific conclusions are unchanged** (the sim cull is
  byte-identical by default). Report: `docs/TWOBODY_FULLCOVERAGE_MAT.md`; viewer `threejs_twobody4d2_*`.
- **4E (2026-07-14) — passive myosin-TAIL as a recruitment mechanism — OUTCOME: TRADE-OFF (default-off `-exp4e`).**
  Replaces the two-body motor's FIXED calibration anchor with a passive compliant tail (rod from a fixed surface
  attachment to a now-movable pivot; rest length lTail, stretch k_tail, bend κ_tail; 5-DOF implicit solve; reduces
  to the fixed anchor as lTail→0 / stiffness→∞; `stepTail(off)` ≡ `stepC` bit-identical). **Q1:** the current
  effective surface-anchor→lever-pivot distance is **0 nm** (the anchor IS the pivot). **Q2:** a tail strongly
  enlarges the capture volume (transverse reach 0→139 nm at 80 nm; the fixed motor has a zero-width line footprint)
  — but only via the **free swing**. **Q3:** recruitment rises sharply (avgBound 0.23→**1.32, 5.7×** at a ~10 nm
  free tail; continuity 0.20→0.69). **Q4/Q5:** the tail adds large series compliance, dominated by the **bending**
  stiffness (the stroke is axial, the tail transverse); the recruitment-optimal free tail **absorbs the stroke**
  (6.9→1.1 nm, k_ext 0.645→0.010 pN/nm, 98 %). **Q7:** the recruitment gain and the stroke loss share the same
  compliance ⇒ **no passive tail both recruits well and preserves the single-molecule mechanics** — a recruited
  motor is bound-but-inert (more-bound ≠ more-force). Connects to §5: the tail is a leftward recruitment-map shift
  that **violates** the §5 requirement to preserve intrinsic single-motor mechanics. What would work (not built): a
  state-dependent catch-**stiffening** tail. No canonical change. Report: `docs/TWOBODY_TAIL_RECRUITMENT.md`.
- **4F (2026-07-15) — supported two-region tail (search-mobile, load-bearing) — OUTCOME A: CLEAN DECOUPLING
  (default-off `-exp4f`).** The follow-up 4E flagged. 4E failed because its compliance was **isotropic** (the
  softness that enlarged the capture volume also absorbed the stroke). 4F makes the passive tail **anisotropic +
  nonlinear**: the movable pivot P is held by TWO separate elements — a **supported distal tail** giving a
  **slack-to-taut AXIAL** law along the load axis b̂ (soft within a slack δ, TAUT/stiff beyond — the spec tension
  coordinate qL=(P−P0)·b̂) + a **flexible proximal S2 hinge** giving a **soft TRANSVERSE** search spring
  (finite-extension bounded). Passive · load-engaged · **nucleotide-INDEPENDENT** (no state switch). 5-DOF implicit
  solve (the 4E structure with the anisotropic-nonlinear law + its tangent); `supOn=false` ≡ `stepC` bit-identical.
  The ONE §5-licensed refinement is the *supported tail's own* axial stiffness (search is transverse ⇒ unaffected;
  k_ext saturates at the fixed ceiling by k_taut≈20 pN/nm). **Single-motor (decisive, all 12 gates PASS):** at
  no-slack (δ=0) and short-slack (δ=1.5 nm) the motor keeps the full stroke (100 % / 98 %), skeletal k_ext
  (99 % / 93 %), negligible pivot recoil (0.07 / 0.15 nm) **while** gaining a 2-D capture footprint (783 / 1110 nm²
  vs the fixed anchor's zero-width line); excessive slack (δ≥3.5 nm) reproduces the 4E trap (stroke absorbed,
  k_ext collapses) — the documented failure edge. **Dense mat (4000 motors, active-set cull — CPU-tractable, no GPU
  needed):** the low-slack tail recruits several-fold more chemically-bound motors AND — the §11 distinction — those
  are **LOAD-BEARING** (pivot taut): no-slack ≈100 % of the recruited motors are load-bearing, whereas excess-slack
  recruits similarly but stays ~inert (≈17 % taut, ≈ the fixed load-bearing count). dt-invariant; covariant;
  controls (Jacobian, action–reaction, hinge-locked, fixed-seed) pass. **This is the first tail that recruits AND
  preserves the single-molecule mechanics** — a clean recruitment-map shift satisfying the §5 requirement 4E
  violated. **Recommendation: provisionally ADOPT the supported tail (short slack δ=1.5 nm, k_taut=20 pN/nm) as a
  non-canonical candidate; test with a longer filament.** No canonical change. Report:
  `docs/TWOBODY_SUPPORTED_S2_TAIL.md`.
- **4G (2026-07-15) — MD-informed EXPLICIT fixed-contour S2 — OUTCOME A (partial): decoupling EMERGES from geometry
  (default-off `-exp4g`, CPU-only).** Tests whether 4F's decoupling emerges from an **explicit** fixed-contour S2
  coiled coil rather than 4F's two PRESCRIBED Cartesian springs. The free proximal S2 (L∈{10,20,40,60} nm) is a
  discretized extensible-elastica **beam** (M=L/10 segments, stiff stretch ks=420 pN/nm, finite bending kb, Lp≈175
  nm — all from AMK-2008 MD by length scaling: axial ∝1/L, bending ∝1/L³), clamped at a supported emergence point
  (position + tangent, non-rotating), distal node = the validated pivot; passive, nucleotide-INDEPENDENT. Coupled
  (3M+2)-DOF implicit solve with a **full numeric beam tangent**; `g4On=false` ≡ `stepC` bit-identical.
  **DECOUPLED for L≥40 nm** (stroke 105 %, k_ext ~154 %, pivot recoil ≤0.01 nm, capture 576–864 nm²) — 4F's
  search-mobile + load-bearing decoupling reproduced **with NO prescribed spring**, the anisotropy being the MD 1/L
  (stretch, stiff) vs 1/L³ (bending, soft) scaling; **short S2 (L≤20) = a stiff link** (the tweezers/strongly-
  supported boundary condition). **The one honest correction:** a stiff-stretch beam does **NOT hold 4F's rest
  slack** — it straightens and repositions the pivot; the load-engaged nonlinearity relocates to a **compression-
  buckling** asymmetry (kComp≪kTens at L=60). Contour conserved (≤0.01 nm); NO binding-state/nucleotide stiffness
  switch (§17.6); all controls + dt-invariance PASS. Dense mat (CPU, reduced-scale, disclosed): recruits 3.3–4.3×
  more motors, **load-bearing** (100 % taut at short S2, 69 % at L=40) — the opposite of the 4E trap. **Recommend
  retaining BOTH the explicit MD-informed S2 (gliding/exposed-tail, L≥40) and the fixed anchor (tweezers) as
  assay-conditioned variants;** 4F stays valid as a phenomenological fit; the decoupling survives explicit geometry
  (not Outcome E). No canonical change; `BoA-v1ref` byte-clean. Report: `docs/TWOBODY_MD_INFORMED_S2.md`.

This arc is **not** on the canonical path and does not change any canonical finding above; it is a parallel
prototype toward a stiffer, mechanically recognizable motor.

---

## 9c. Low-[ATP] condition transfer (exploratory pilot, 2026-07-28)

**Settled for the plateau and its mechanism; controls still outstanding.** 32 arms at n = 4 seeds (pilot +
executed extension); the n = 8 campaign was deliberately not run. Report:
`docs/twirling/LOW_ATP_GLIDING_TWIRLING_FINDINGS.md`.

The experimental myosin-II twirling assay uses ~5-20 uM ATP to slow translation. Changing ONLY the assay ATP
concentration, through the model's own nucleotide law and with no motor parameter retuned:

- **Gliding transfers.** v_even falls 25.7x monotonically from 2 mM to 5 uM (-4.19 -> -0.163 um/s), seed
  spread 1.15-1.32x, placing 5 and 10 uM inside the reported 0.1-0.5 um/s band untuned. Bound population
  4.5 -> 34.7, rigor occupancy 0.08 -> 0.94, residence 1.1 -> 21.1 ms, detachment 100% ATP-triggered,
  pre-stroke lifetime invariant at ~100 us.
- **Rotation does not.** Omega_odd stays between -40 and -71 rad/s over a 400x ATP range, and measured rotation
  closes against independently accumulated torque at **1.033 +/- 0.031 (8/8 sign agreement)** -- so this is a
  flat TORQUE, not an observable defect. Turns-per-um rises from -2.53 to -58.8 purely because v_even collapses.
- **The pitch match sits at the wrong ATP.** The model reproduces the experimental pitch (0.47 +/- 0.20 um) at
  SATURATING ATP (-0.396 um), but gives -0.017 um at 5 uM, the condition the experiment actually used.
- **The plateau is REAL (confirmed at n = 4).** tau_odd flat to **12%** across the 400x ATP range, every
  condition individually resolved, across-ATP spread down to 0.32 of the within-condition SD, closure
  **1.006 +/- 0.029 (16/16)**.
- **Its mechanism is near-total cancellation.** Net chiral torque is a **0.14-0.7% residual** of two balanced
  +/- populations; the **per-head torque magnitude is invariant to 2.8%** across 400x in [ATP] while
  contributing heads grow **7.9x** with +/- counts matched to <0.4%.
- **Chiral torque is decoupled from axial role:** axial pullers and draggers carry the same torque sign at
  4 of 4 conditions. Torque by nucleotide state and by stroke phase remain UNRESOLVED (4 arms per condition).

**ATP interface (reusable).** `nucParams[1]` (`atpOn`, NONE->ATP) is the sole [ATP]-dependent transition, a
pseudo-first-order hazard frozen at 2.0e4/s for saturating ATP. `-atp-uM` scales it linearly, anchored on the
project's own declared saturating condition (2 mM). Data-only, default-absent, exact no-op when absent, and
CPU/GPU decision-identical (Stage 1: 19/19 gates). Note this lineage runs with **rigor rupture OFF**, so ATP
binding is the sole detachment pathway.

**Caveat for anyone reading older records:** `qOmega`/`omegaPred` divided whole-filament torque by ONE
segment's roll drag until 2026-07-28; records written before the fix carry `qOmega` low by nSeg = 12. No claim
ever used the field.

**Next:** backfill the two original seeds with per-head instrumentation (15 arms, ~10 h) to take the per-head
decomposition from 4 to 8 arms per condition, which is what would resolve torque by nucleotide state and by
stroke phase. The low-ATP mirror control and the eps = 0 null were never run and are the outstanding controls
for any low-ATP chirality claim.

---

## 9d. Canonical actin-attachment architecture (audit, 2026-08-11)

> **SUPERSEDED IN PART by §9f (2026-08-12).** This section's description of Path B — an `every3` lattice
> (8.10 nm rise) reached by centreline acceptance followed by a post-hoc snap — is an accurate record of the
> configuration that produced every campaign result listed here, and stays valid as such. It is **no longer
> the current Path-B model**: the lattice is now sparse long-pitch `every4` (10.8 nm rise, +54°/site,
> filament-global phase) and capture is **site-first**. See §9f.

**Read-only audit; nothing changed.** Full report: `docs/attachment/CANONICAL_ACTIN_ATTACHMENT_AUDIT.md`
(branch `gpu-mat-bottlenecks-explicit-singlehead`, commit `3119000`). It supersedes three headline statements
of `docs/helical_binding/ACTIN_HELICAL_BINDING_AUDIT.md` (2026-07-23) — see that report's §14.4 — while that
document remains authoritative for the azimuth/roll infrastructure inventory and git provenance.

**Two canonical attachment architectures are simultaneously live and must never be conflated.**

- **Path A — frozen production gliding** (`ExplicitCompleteMatHarness -production-cell` /
  `ExplicitHmmDimerGlidingHarness -production-cell`). Actin is a **continuous, azimuth-symmetric centerline
  cylinder** of radius `FIL_R = 3.5 nm`. The attachment coordinate is the single scalar `bindArc` and the bond
  acts **on the axis**. No monomer, no helix, no azimuth, no site identity. Occupancy: **none** for
  single-head; **sister-head-only 5.4 nm axial** for the HMM dimer. This produced every density-saturation,
  L40/L60, rigor-rupture-impact and force-balance result.
- **Path B — chiral-site twirling campaign** (`ChiralSiteHarness`: `-eta-map`, `-atp-map`,
  `-atp-density-map`, `-twirl*`, `-conv-*`). Actin is a **discrete helical lattice of material-frame sites**
  (`every3`, rise 8.10 nm, twist −166.5°/monomer left-handed) on the actin **surface** at `R = 3.5 nm`, with a
  latched filament-global site id and **exclusive one-head-per-site occupancy**. This produced the viscosity
  campaign + mirror control, the low-[ATP] transfer, the density-occupancy screen and all twirling work.

**The load-bearing structural fact:** in BOTH paths the **capture gate is the same azimuth-blind 8-gate
contract** (`matBindExplicit`, a device port of the single-molecule `gateMetrics`/`gatePasses`) — so the motor
validated in the blind tweezers challenge attaches under **identical geometric rules** in the gliding assay.
In Path B the lattice enters only **after** acceptance, as a snap + 12 nm capture veto + occupancy filter.
**Filament roll therefore cannot change whether a head may bind, on either path.**

**Classification: R4** (two materially different actin representations across the current result set),
decomposing into **R2** for Path A (stereospecific motor capture, simplified actin) and **R1** for Path B
(helical off-axis geometry present, accessibility selection incomplete). **Urgency: high-value but not
publication-blocking** — with one **disclosure obligation**: the saturation results and the twirling results
use different actin-side representations, and the manuscript must say so.

**Path-B far-side accessibility — QUANTIFIED (2026-08-11).** Report:
`docs/attachment/PATH_B_SITE_ACCESSIBILITY_TELEMETRY.md`; telemetry `RUN_LOGS/attachment_audit/path_b_accessibility/`.
Default-off, bit-identical-when-off host-side telemetry; 8 seeds × 20 ms at a parameter-exact replica of the
production η = 0.01 ladder cell; 24 arms, 0 invalid / 0 solver. **Verdict A1 — present but mostly inert for
torque, material for occupancy.**
- **Attachment is statistically uniform around the filament circumference** (every class within ~1.4 SEM of
  1/3); **far-side sites hold 32.1 % of bound-head time**; the lawn-facing hemisphere is preferred by only
  **1.12–1.15×**. Occupancy peaks LATERALLY and is depressed at BOTH poles.
- **Cause is scene geometry, not the gate:** each motor's reference-pose F8 point is placed at z = 0, the
  filament **centreline**, so the head approaches at axis height and top/bottom are equidistant.
- **Far-side bonds carry the SAME-signed chiral torque** (local-frame skew, by design), contributing **17.7 %**
  of τ_odd at **0.55×** per-head productivity and ~zero mean axial force. Removing their instantaneous
  contribution retains **82.4 %** of τ_odd — inside the total's own 2.53 σ uncertainty.
- **This REFUTES the earlier H1 guess** that far-side bonds dilute the torque and that τ_odd is a lower bound;
  τ_odd is if anything a mild over-estimate. Twirling sign/mechanism/mirror results are unaffected;
  **occupancy claims (incl. the Vilfan bracket comparison) must state the ~32 % far-side population** — note
  removing it moves N_b further BELOW the bracket, strengthening D1.
- Not a blocker for further twirling mechanism work; fix before any quantitative occupancy claim.

**Filament z BOUNDARY — hard slab implemented, validated, DEFAULT-OFF (2026-08-12).** Report:
`docs/attachment/FILAMENT_Z_SLAB_AND_ACCESSIBILITY_RERUN.md`; data `RUN_LOGS/attachment_audit/z_slab/`.
- **Old (still the DEFAULT):** `MatSoaSlice.matZConfine`, a harmonic well `Fz = −kz·z_com` at kz = 2 pN/nm ⇒
  z = 0 is an energetic minimum pinning the filament to **RMS z = 1.44 nm**, *less than half the actin radius*.
- **New (`-z-slab on`):** `MatSoaSlice.matZSlab` — exactly flat interior (accumulator untouched), one-sided
  walls on the **segment SURFACE** via the exact cylinder z half-extent, pure z force (no torque ⇒ cannot inject
  tangential/angular momentum), fracMove law ⇒ dt/η-consistent, k_eff = 19.6 pN/nm anchored on the existing
  20 pN/nm S2-beam substrate floor. Walls: lawn plane (−9.93 nm) to lawn+80 nm. **PASS** on interior flatness
  (exactly 0 N), unbiased centre-start diffusion, wall signs, tilt-aware surface rule, penetration (p99 1.72 nm
  < 3 thermal steps), dt (penetration halves with dt), and **CPU≡GPU to printed precision on every channel**.
- **EMERGENT HEIGHT (the Part-A result):** with no prescribed height the motors select **z_COM = +11.1 nm**
  (SD 11.4 nm) — vs the old 0 ± 1.4 nm pin and vs +30 nm for a *free* filament in the same slab. Motors pull
  actin DOWN at **−0.33 pN**, balanced by the lower wall; **lower wall acts on 0.33 %, upper wall on 0.016 %**
  of segment-steps ⇒ motor-selected, not wall-imposed. **Z1–Z2, not Z3/Z4.** (+11 nm is still relaxing at
  20 ms ⇒ read as a lower bound.)
- **ACCESSIBILITY RERUN (n = 8, paired seeds, everything else identical):** far-side **bound occupancy 0.321 →
  0.270 (−16 %, 5.26 σ)**, NEAR 0.262 → 0.344 (+31 %, 5.38 σ); lawn-hemisphere preference sharpens **1.14× →
  1.61×** and the old lateral bimodality weakens. **But far-side ATTACHMENT EVENTS do not move at all**
  (0.331 → 0.338, 0.50 σ) — attachment stays statistically uniform around the circumference.
- **τ_odd is NOT materially changed** (−1.64e−17 → −1.27e−17, −22 %, 0.55 σ) but is **better resolved**
  (2.53 σ → **3.85 σ**, 7/8 sign) because the pin's variance is gone. **Engagement IS changed**: avgBound
  −14 %, attachment flux −15 %, glide −21 % — so the slab re-baselines occupancy/velocity observables.
- **VERDICT: the harmonic pin was NOT the primary cause of near/far symmetry** (it bought 5 points of occupancy
  and nothing at capture). **Accessibility stays A1, and an explicit site-level rule is STILL REQUIRED** — the
  8-gate capture test is evaluated against the clamped CENTRELINE and never sees a site, so vertical freedom
  cannot make capture azimuth-aware. Smallest fix (proposal only): move site selection inside the gate + the
  geometry-derived `n̂_site·(x_F8 − x_site) > 0` **at capture**.
- **Path A: NEEDS TEST, default unchanged.** The slab is physically at least as appropriate, but Path A binds
  on the centreline (no accessibility gain) and its frozen density sweep would be re-baselined by the −14/−15/
  −21 % engagement shift. No Path-A sweep was run.

**Other documented hazards (not fixed):** the 3-D surface steric is disabled in campaign arms
in favour of site exclusivity; the single-head lawn has **no orientational disorder** (`RAND_BASE_AZ=false` ⇒
every motor shares the lab triad); the HMM dimer CPU/GPU bind logics are hand-maintained twins (equivalent
only in D0 with `-occupancy-global` off); `ψ_actin` is a per-motor constant and carries no actin information.

---

## 9f. Sparse long-pitch actin site lattice — the Path-B geometry correction (2026-08-12)

**Report: `docs/attachment/SPARSE_LONG_PITCH_ACTIN_SITE_LATTICE.md`.** Actin-side geometry only — no motor
parameter retuned, no chemistry changed, no fitted angular parameter introduced, no new steric law.

**The Path-B effective binding-site lattice is now the sparse long-pitch `every4` geometry, verified
numerically and visually from the exact coordinates the capture kernels use:** one effective site every
**10.800 nm** axially, advancing **+54.000 deg** per site (= the native twist `4 × −166.5°` evaluated four
monomer rises later), one revolution every **72.000 nm**, on the actin surface at `R = 3.500 nm`; 196 sites
on the canonical 2.106 µm filament, **one continuous helix with 0 azimuth discontinuities across all 11
segment boundaries and 0 missing or duplicated sites**. Nearest-neighbour 3-D site separation **11.258 nm**
= **1.61× a nominal 7 nm head diameter**, so a head footprint contains **exactly one** effective site
(max other sites within 7 nm of any site = **0**) ⇒ **no additional steric law is needed and none was added.**

**Two real defects were found in the pre-existing `every4` mode and fixed** (the rise and per-site twist were
already right; the mode was reused, not rebuilt):

1. **The site azimuth was referenced to each segment's own centre**, so the helical phase restarted at every
   segment boundary — a **+22.5°** step, 11 times along the canonical filament. New **filament-global**
   convention `φ(k) = k·(twistRate·rise)`, `ExplicitCompleteMatHarness.SITE_PHASE_GLOBAL` (`-site-phase
   global|segment`), **default false** ⇒ every pre-2026-08-12 configuration is byte-identical by construction.
2. **A site whose global arc landed exactly on a segment junction vanished** — float32 rounding of
   `segCumArc`/`segLength` put it outside *both* neighbours (3 of 195 `every4` sites). Fixed in the
   **site-aware path only** with a float32-robust membership tolerance + clamp (`SITE_SEG_TOL_UM = 1e-5 µm`;
   a numerical tolerance, not physics). The legacy `siteSnap` retains the fragility deliberately.

**Capture is now site-first** (`ChiralSiteSystem.siteGateA/siteCommitB`, from the preceding task, reused
unchanged): enumerate the sparse sites → reject interior approaches (`g8`, pure geometry) → apply `g0`/`g4`
**against the actual site** → select one → bind → latch identity. **No centreline acceptance + post-hoc snap
in this mode.** g0 (3 nm) and g4 (2 pN) keep their values but now measure head-to-surface separation and the
real F8 bond extension; g1/g2/g3/g5/g6 are untouched.

**Verification.** 10 static fixtures PASS (one reachable site — exactly 1 acceptable candidate; between-sites
— 0 of 13 reachable; interior-approach rejection; 90° roll with site id + `bindAzim` retained and the lab
azimuth rotating exactly +90°; one-head-per-site with k±1 separate; whole-filament boundary continuity).
**CPU/GPU exact** on site ids, bind decisions and `bindAzim` (`max|dAzim| = 0`) at 1 and 12 segments, plus a
legacy-path regression guard — all device-resident.

**Bounded `every3`→`every4` compatibility panel** (GPU, 12 arms, 2 seeds, 8000 steps, ε ∈ {0, ±15°}, identical
capture and slab in both arms; `invalid = solverFail = 0`): recruitment **0.82×**, avgBound **0.85×**, glide
**0.84×** (the sparser lattice presents 25 % fewer sites); site occupancy **1.14×**; NEAR/SIDE/FAR bind and
bound fractions and filament mean z all **within SEM**; τ_odd **0.84×** and Ω_odd **0.60×**, same signs.
Nothing changed qualitatively.

**Zero-skew (ε = 0), measured without a prior.** τ, Ω and turns are negative in 4/4 seed-arms. The mirror
control (`-lattice-mirror`, a reflection of the lattice — with ε = 0 the only chirality left in the model):
**τ reverses sign in 2/2 matched seeds for `every4`** (−2.85e−22 → +4.45e−22 N·m), but **Ω does NOT reverse**
(stays negative in the mean, 1/2 seeds) and at ε = 0 |Ω| exceeds the ε-odd half-difference of the skewed arms
⇒ the rotation signal is thermal-dominated at this sample size. **n = 2 is far too small to conclude
anything** (the viscosity campaign needed n = 24 and still found Ω_odd unresolved at canonical η).
**⇒ Realistic binding geometry alone has NOT been shown to generate twirling, and no physics was added to try
to make it.** The τ sign reversal is a lead; the recommended next step is the same ε = 0 native-vs-mirror pair
at **n = 16–24**, gated on per-seed sign reversal of τ.

**Historical-result status (do not rewrite).** Every previous Path-B campaign — viscosity map + mirror
control, low-[ATP] transfer, density-occupancy screen, all twirling/converter-skew arms — ran **`every3`,
segment-relative phase, legacy centreline+snap capture**, and **remains valid for that model**. Quantitative
twirling values must be **regenerated** before being attributed to the sparse geometry; the panel above
establishes compatibility, not replacement values. `-legacy-lattice` reproduces the historical lattice
exactly; `-path-b-candidate` selects the whole new geometry in one switch.

```
./scripts/run_chiral_sites.sh -site-geometry     # numeric table + acceptance criteria + figure data
python3 scripts/plot_sparse_sites.py             # figures, straight from those coordinates
./scripts/run_chiral_sites.sh -site-fixtures     # static capture fixtures A–F
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -lattice-compare -gpu -steps 8000 -seeds 2
```

---

## 9g. Powered zero-skew native-vs-mirror study — the sparse lattice does NOT generate chiral torque (2026-08-12)

**Report: `docs/twirling/ZERO_SKEW_SPARSE_LATTICE_MIRROR_GPU.md`.** Mechanistic screen; no physics changed,
no parameter tuned, no new chirality, no new device kernel. n = 24 matched native/mirror seed pairs (7001–7024),
**eps = 0 in every arm**, GPU device-resident, 24.4 min, `invalid = solverFail = 0`.

**CLASSIFICATION: M0 — no mirror-odd torque.** With the corrected sparse long-pitch geometry frozen (`every4`
10.8 nm / +54° per site / filament-global phase / site-aware capture / one head per site / z slab / free
filament height), reflecting the actin lattice (`MIRROR_SIGN = −1`) leaves the deterministic axial torque
**unchanged within noise**:

- **tau_mirror_odd = −1.602e−23 ± 8.500e−23 N·m · |mean|/SEM = 0.19 · 95 % CI [−1.92e−22, +1.60e−22] includes
  zero · signs exactly 12−/12+ · sign-test p = 1.0000.**
- Secondary rotation also null: Ω_odd −1.79 ± 2.67 rad/s (0.67σ), turns_odd 0.18σ. **Not required to resolve.**
- Mirror-EVEN control tau_even = −9.25e−23 ± 6.10e−23 (1.52σ): what little mean torque the native arm carries
  is predominantly **achiral background**, and is itself unresolved.

**The n = 2 pilot (§9f) is REFUTED, not merely unresolved.** Its apparent effect was −3.65e−22 N·m; the
measured per-seed SD (4.16e−22) gives this design >80 % power against ≈2.4e−22, and the 95 % CI **excludes the
pilot value**. **Standing bound: |tau_mirror_odd| < 1.92e−22 N·m (95 %)** at zero imposed skew — below the
≈2.7e−22 N·m the ±15° converter-skew arms carry.

**MECHANISM (why there is nothing to find):** the episode telemetry (695 native + 700 mirror episodes) shows
**S2 axial extension flat at ≈38.5 nm and the bend proxy flat across every binding-site azimuth bin in both
arms**. The helical geometry changes *where* the head attaches but, at this motor's compliance, **the
attachment azimuth does not measurably bias the strain the motor develops** — so there is no handed strain
channel for the lattice to drive. Age-resolved torque is null in every bin (max 0.65σ) — nothing at capture,
during the stroke, or in post-stroke drag; puller/dragger channels null; NEAR/SIDE/FAR sub-totals (1.0–1.6σ)
cancel. Azimuth occupancy is bimodal (lawn-facing preference, inherited from the z-slab study) and **identical
in the two arms**, as a reflected lattice requires.

**Pre-launch gates all PASS**, notably: mirror is an **EXACT reflection** (max|φ_nat + φ_mir| = 0.00°,
z exactly negated, every non-chiral quantity bit-identical) and the RNG streams are **bit-identical** across
the pair (counter-based, keyed on (entity, step, seed); the mirror changes only `chiP`/`sbP`), so the pair is
matched at the RNG source. Telemetry is **exactly trajectory-inert** (all deltas 0.000e+00).

**ONE SANITY FLAG, investigated, not dismissed:** mean glide differs native (−1.912) vs mirror (−1.605 µm/s),
odd channel 2.10σ **uncorrected**. It fails Bonferroni over the six sanity channels (threshold |t| > 2.81),
sign-test p = 0.15, is **uncorrelated with the torque channel** (r = −0.09) and tracks engagement noise
(r = −0.60 with the avgBound difference, while avgBound and attachment flux themselves match at 0.39σ/0.02σ).
It does not undermine the torque null; it is the one channel a follow-up should re-check.

**MAY NOT be concluded:** that twirling is impossible in this model, that motor skew is necessary in vivo, or
that the helical geometry is unimportant — it remains the physically correct actin representation (§9f) and it
does change recruitment and glide. This says only that it is **not by itself a twirling generator at this
operating point** (η = 0.1 Pa·s, 400 heads/µm², 20 ms, `REG_K` = 0, no lawn orientational disorder).

**NEXT (do NOT just add seeds — the channel is at zero with even signs):** (1) repeat the same zero-skew
native/mirror pair at **η = 0.01 Pa·s**, where the viscosity campaign showed rotation is drag-limited and
Ω_odd rose 22.8× — that is where a geometric torque would become visible; (2) exercise **`REG_K` > 0**, the
registry couple that would convert site azimuth into a head-orientation constraint, i.e. supply the coupling
§9g shows is absent; (3) only if either resolves, the seconds-long accumulation experiment.

```
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -zsm-gates -gpu
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -zsm-campaign -gpu -seeds 24 -steps 8000 -zsm-seed0 7001
./scripts/run_chiral_sites.sh -zsm-report -seeds 24 -steps 8000 -zsm-seed0 7001   # re-report from records
python3 scripts/plot_zero_skew_mirror.py
```

**Statistical hygiene (repo-wide):** the shared `T95_TWO_SIDED` table clamped to the normal approximation
1.960 above df = 20; it was **extended to df = 40** (t_23 = 2.069), so n = 24 confidence intervals are now
slightly wider (conservative). A lookup constant — no conclusion depends on the third digit, but previously
printed n = 24 intervals were marginally narrow.

---

## 9h. Bound-motor helical geometry — visual audit (2026-08-12)

**Report: `docs/attachment/BOUND_MOTOR_HELICAL_GEOMETRY_VISUAL_AUDIT.md`.** Geometry/visualization audit only.
No force law, gate, threshold or default changed; nothing tuned; new files plus one additive read-only harness
mode (`-bound-viz`). `-site-fixtures` **PASS** and the 24-fixture suite **24 PASS / 0 FAIL** re-run green.

**The actin side is what we intended; the motor side is not.** Verified from the coordinates the capture
kernels themselves use, with real bound motors placed by the real
`siteGateA → siteCommitB → siteOccupancyResolve` and posed by the real `matPlaceHeadExplicit`:

- **Sites form ONE sparse long-pitch helical track** — 10.800 nm rise, +54.000°/site, 72.000 nm repeat,
  R = 3.500 nm, filament-global phase, site-aware capture, one head per site. **Multiple bound motors occupy
  it as expected**: 12 heads on 12 consecutive sites marching around the whole circumference; a natural
  3000-step CPU gliding run uses 25 distinct sites over the full length *and* circumference.
- **The bound head does NOT face its own site normal.** In the decisive fixture, 12 heads each docked
  perfectly facing their site (`n̂_site·êBind = +1.0000`, 0.00°) at 12 azimuths spanning 1.8 turns all carry
  the **identical** transverse reference `h_perp = (+1, 0, 0)`. Its azimuth in the filament's own material
  frame is **flat to 0.000°** while `n̂_site` advances +54.000°/site. Same in the natural run (all 5 bound
  heads: `h_perp = (0, 1, 0)`).
- **The reference is a LAB axis.** `TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit` builds the head `yVec` by
  Gram–Schmidt of a hard-coded `x̂` (or `ŷ`) seed against the head long axis. Actin never enters.
- **No angular actin↔motor channel exists on this path at all:** `xbParams[2] = j1FMT = 0` ⇒ **F9 and F10 are
  identically zero**; the only bound angular constraint is `½k_bind(ψ − ψ_actin)²` with `ψ_actin = 0`, a
  per-motor constant about the lab-fixed `ê_conv`; and the orientational registry `headRollStep` is exactly
  inert (`REG_K = 0`, `HEAD_ROLL = false`) — and even when enabled prefers the site **tangential/axial**
  direction, not `n̂_site`. **The actin azimuth reaches the motor only through the position of one point.**
  That is the geometric reason §9g's M0 null was inevitable at this operating point.
- **Viewer-schema hazard surfaced and fixed for this writer:** `sim_viewer_boa.html` requires the **array**
  frame form (`segments[].end1/end2`, `myosins[].rod/lever/motor`). The older **flat** `{x1,y1,z1,x2,…}` form —
  still emitted by the legacy `ChiralSiteHarness.writeFrame` `-3js` movie path — makes `applyFrameData` throw
  on `m.rod.invisible`; `loadFrame`'s `.catch` swallows it and **the HUD hangs on "loading…"** instead of
  erroring. The audit writer emits the array form and is field-validated. The legacy movie path is untouched
  and remains a standing hazard.
- Secondary item surfaced (not hunted, not fixed): **`g6` is evaluated against the segment CENTRE**, so a
  rigidly-posed reference motor is refused at top-of-filament sites purely on head-centre height (3 of 16
  requested sites across fixtures A/B).

**Next code change (focused, default-off, two gated steps):** (1) seed the bound head's Gram–Schmidt with the
site's own material direction instead of `x̂` — provably trajectory-inert today (`j1FMT = 0`, `REG_K = 0`), and
**gate** that inertness rather than assume it; (2) only then exercise `REG_K > 0`, first deciding explicitly
whether the preferred direction should involve `n̂_site` (a change of law, not a parameter) and what `k_Ω` is
based on. Step (2) is exactly follow-up (2) of §9g. **Not recommended:** changing g6/g0/g4, adding a steric
law, or enabling `REG_K` in production before (1) lands.

```
./scripts/run_chiral_sites.sh -bound-viz -bound-viz-steps 3000   # audit + TSV/JSON scenes + -3js frames
python3 scripts/plot_bound_motor_audit.py                        # figures 1-8
cd ~/Code && python3 SoftBox/sim_server.py 8000
#   audit viewer   http://localhost:8000/SoftBox/bound_motor_geometry_viewer.html
#   project viewer http://localhost:8000/SoftBox/sim_viewer_boa.html -> threejs_bound_geometry/<fixture>
```

---

## 9i. Myosin head orientational DOF — lost 2026-07-14, and it explains the 30 % site mask (2026-08-12)

**Report: `docs/motor/MYOSIN_HEAD_ORIENTATION_DOF_HISTORY.md`.** Archaeology only; no physics, default, kernel
or parameter changed. New read-only probe `softbox/HeadOrientationDofProbe.java` + `scripts/plot_head_orientation_dof.py`.

**The older motor genuinely had 3-D head orientational freedom, and it was lost in the deliberate 2026-07-14
two-body topology replacement (`e17b5a4`) — not in the GPU port (three commits downstream, which merely
inherits it) and not in a solver reduction (the implicit solver *gained* 14 DOF, all positional).** What is
undocumented is the consequence: no report states that the replacement collapsed the head's orientational
manifold from a 2-sphere to one lab-fixed great circle.

Measured for ONE fixed motor (`HeadOrientationDofProbe`):

| | historical SPHEREHEAD (still live in the tree) | current explicit-S2 |
|---|---|---|
| head | integrated `RigidRodBody` sub-body | algebraic slave of (φ, ψ) |
| orientation DOF | 3, dynamic, Brownian + Stokes-sphere drag | 1 (ψ), no Brownian, no drag in the EOM |
| eBind reachable set | **2-sphere — 97.8 % of 4π**, covariance rank 3 | **one great circle — 2.9 %**, rank 2, `max\|eBind·êconv\| = 0` exactly |
| every4 site azimuths facable within 25° | **20/20 = 100 %** (worst 2.19°) | **6/20 = 30 %** (worst 90°) |

**⇒ The 30.1 % site-normal mask in §9h's decision brief is an artifact of that topology change, not a property
of myosin or of the helical lattice.** The historical head reaches every site normal to ~2° from a fixed
anchor with no lawn disorder.

**Parameter provenance for the missing DOF EXISTS** — Stokes-sphere rotational drag `8πηR³` (`HEAD_R` = 10 nm),
FDT Brownian torque, `BRotCoeff` = 0.5, and the bound F9 alignment torque `j1FMT` = 0.4 (currently **0** on the
explicit-S2 path). There was **no** detached stereospecific orientational stiffness and **no** actin-normal
restoring torque: F9's reference is the filament **axis**, never a site normal.

**`RAND_BASE_AZ` classified R3 — a workaround for this restriction**, not biological lawn disorder: introduced
2026-07-24 (ten days *after* the loss), its own comment calls it "a SCENE control for the shared-base-frame
artifact, not physics", and it has **never been used in a campaign**. It should not be used to hide the
missing internal DOF.

**Key structural finding (§11):** the explicit S2 beam gives the head genuine 3-D *positional* freedom, but
`matBeamGeom` builds the head from the **static base triad** and takes the beam only as a translation of the
pivot — so the S2 can bend anywhere and `eBind` still cannot leave its plane. That is the coupling the
analytic reduction lost.

**Restoration is a core-motor revalidation, not a local repair.** Recommended: Option B — add ONE dynamic
per-motor coordinate χ rotating the converter plane about `ê_up` (χ = 0 byte-identical to today), drag derived
by the same construction as `γ_φ`/`γ_ψ`, gate FDT first, then stroke → `k_ext` → capture. **Stop if `k_ext`
moves** (the 4E trap). Do not re-open the site-normal law (§9h) until this lands.

```
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.HeadOrientationDofProbe
python3 scripts/plot_head_orientation_dof.py
```

---

## 9j. Neck–head tilt DOF χ — kinematics restored and gated; dynamics NOT yet done (2026-08-12)

**Report: `docs/motor/RESTORED_3D_HEAD_TILT_DOF.md`.** DEFAULT-OFF, χ ≡ 0 **byte-identical**
(`max |matBeamGeomTilt − matBeamGeom| = 0.000e+00`), Path A and Path B unaffected. The original
`matBeamGeom` is NOT modified; `matBeamGeomTilt` is an additive kernel.

**One extra neck–head coordinate fully restores 3-D `eBind` freedom.** χ rotates the head — and only the head —
rigidly about the neck–head joint C about `t̂ = e0 × ê_conv`, giving
`eBind(ψ,χ) = cos χ·e0(ψ) + sin χ·ê_conv`, i.e. (ψ, χ) are spherical coordinates of the head axis with
`ê_conv` as the pole. C, the lever/neck, the converter plane, the S2 beam, the base triad and the anchors are
untouched; `θ = ψ − φ` and the stroke plane are unchanged.

For ONE fixed motor, no base rotation, **no `RAND_BASE_AZ`**, measured through the real kernel:

| | current | **restored (ψ, χ)** | historical sphere-head |
|---|---|---|---|
| eBind manifold | rank 2 (plane), 1-D locus | **rank 3** | rank 3 |
| solid angle | 2.9 % of 4π | **99.8 %** | 97.8 % |
| every4 azimuths facable within 25° | 6/20 = 30 % | **20/20 = 100 %** (worst 0.80°) | 20/20 (worst 2.19°) |

**Rotational drag is DERIVED, not fitted:** χ rotates the same rigid sphere-head about the same pivot with the
same lever arm as ψ, so `γ_χ = γ_ψ = 3.7036e-25 N·m·s/rad` by the identical `build3core` construction
(cross-check vs `8πηR³` = 2.4463e-25; ratio 1.514 = the head-centre translation term). **No new stiffness and
no new tolerance were introduced.**

**Phase-0 blocker RESOLVED (2026-08-12): the strong actin spring is now BINDING-STATE GATED.** New additive
`MatSoaSlice.matKbindGate` writes the already-per-motor stiffness slot `params[7N+m]` from `boundSeg`
(bound → the historical `k_bind` = 512 pN·nm/rad², unchanged; detached → a separate weak `k_det`). **Data-only
— no solver edit, no buffer resize, no TaskGraph change** (the `applyS2Lawn`/`-eta` precedent); flag
`KBIND_BOUND_ONLY`, default off ⇒ byte-identical. **Frame note: `ψ` is measured about `ê_conv` in the motor's
OWN base triad, so `ψ = ψ_actin` was ALREADY a neck-relative rest pose** — it only looked lab-fixed because all
motors share one triad — so no new frame machinery was needed. **k_det ladder** (12 detached motors, real
solver): SD(ψ) = **1043° / 74° / 46.6° / 32.6° / 22.8° / 3.23°** at k_det = **0 / 2 / 5 / 10 / 20 / 512**
pN·nm/rad²; measured SD tracks `sqrt(kT/k_det)` at 0.88–0.90× (`k_conv` also restrains ψ). **k_det = 0 tumbles
without bound** (range 3324°). **Carried forward: k_det ∈ [5, 10] pN·nm/rad², ~100× weaker than k_bind,
DIAGNOSTIC and NOT calibrated** — it has no historical provenance and was chosen from detached search
behaviour alone, with no torque/glide/recruitment quantity consulted. Caveat: ψ is the in-plane angle and χ is
still not dynamic, so this fixes the *stiffness scale*, not yet the 3-D envelope.

**Earlier finding (now fixed, kept for the record) — the DETACHED ψ spring.** The `k_bind(ψ − ψ_actin)` term is **structurally ungated**:
`boundSeg >= 0` gates the F8 *force* only, not the spring or its Jacobian entry. Measured on 12 fully detached
motors with the real solver: **mean ψ = +0.139°, SD(ψ) = 3.229°** (vs `sqrt(kT/k_bind)` = 5.137°; tighter
because `k_conv` also holds ψ). **The detached head wanders ±3.2° along a 360° circle — it does not
orientationally search at all**, which also explains the standing "recruitment is reach-limited, not
angle-limited" result (ψ is pinned inside a 25° gate). **χ alone would not fix this**: χ has no spring, so the
detached manifold would be a narrow band around a meridian — still 1-D. Provenance is *partial* (Exp 3E's head
searched in φ with ψ held as a deliberate stereospecific pre-orientation, calibrated on a single filament at
one azimuth where a lab-fixed `ψ_actin` was equivalent to a site-referenced one), so this is the task's
**CASE B hard stop**. Options B1 retarget the rest orientation to the candidate site (recommended, reuses
`k_bind` unchanged) · B2 make the spring bound-only (closest to the sphere-head oracle, but changes every
campaign's detached mechanics) · B3 accept no orientational search.

**NOT DONE — χ is kinematic only.** It is not integrated, has no Brownian torque, is not wired into the step
loop or the device graph, and none of the core-motor gates were run (stroke, `k_ext`, axial-compliance
decomposition, FDT, CPU/GPU), nor the site-normal capture gate, the 3-D bound potential, the actin reaction,
the viewer scene or the η = 0.01 compatibility run. **The 4E trap — recruitment gain paid for by stroke loss
through a shared compliance — is untested and is the governing risk.** Next: measure τ_χ, wire the overdamped
update + FDT, then regress stroke and `k_ext` and **stop if `k_ext` collapses**.

---

## 9k. Explicit-S2 motor mechanics repair — F8 virtual-work axis + S2→lever moment transfer (2026-08-12)

**Both hard findings raised by the 3-D-head work (§9j) are REPAIRED and DEFAULT-ON. F8 remains a purely
translational spring; the lever joint introduces no fitted stiffness. Report:
`docs/motor/RESTORED_3D_HEAD_TILT_DOF.md` §13–§15.**

**Repair 1 — the F8 generalized-force axis was orthogonal to the true one.** The explicit-S2 solvers projected
the translational F8 spring onto the converter coordinates about `eup`, but the geometry rotates about `econv`
(`uB = R_econv(φ)·eup`, `xF8 − C = R_econv(ψ)·d0`). With the converter geometry planar, `eup × (in-plane)` is
**orthogonal** to the true Jacobian column, so **an in-plane bond force fed exactly ZERO generalized load into
φ and ψ** — and the only load the legacy axis did respond to (out-of-plane) is the one the true geometry
ignores. Present since the explicit-S2 model was introduced (`1b227c0`, 2026-07-15). Fixed on every path
(`matS2SolveStep`, `matS2SolveStepTilt`, `beamRelaxAnalytic`, `s2SolveM`, `s2Solve`, `ExplicitBeamSolver`,
`TwoBodyGpuKernels.explicitBeamStep`, and the `ExplicitBeamGpuHarness` mirror). Gates: FD-vs-column rel
**4e−08**; virtual-work closure rel **3.5e−07**; in-plane load matches the FD ground truth to ~1e−07 where the
legacy axis gave exactly 0; and a fixed-site relaxation is now **stationary under the true potential gradient
(rel 1.0e−08)** where legacy stopped at **1.9e−01** — i.e. it was converging to the fixed point of the wrong
variational problem.

**Repair 2 — the S2 terminated at the lever as an exact zero-moment pin.** The beam's bending energy ends at
node M; the lever entered only as a translation of the attachment point, so **nothing constrained the lever
angle φ** once the head's rest pose became neck-relative (`k_conv` ties ψ to φ and the head potential is
neck-relative — both purely *relative*). **Archaeology found no historical joint to restore**: the direct
structural counterpart in the sphere-head motor, the J2 rod↔lever joint, has **`myoJ2FracMoveTorq = 0.00`** in
the frozen v1 oracle — itself an exact free hinge. **Option A was therefore taken: the lever is the terminal
orientation of the S2 chain, held by the beam's OWN `kbend` (= EI/l₀ = 7.2e−20 N·m/rad², AMK 2008) against an
unstrained angle read off the as-built geometry** — the same kind of build-time geometric constant as the
clamped joint 0's rest tangent `g4Tan`. **No new stiffness, no fit.** Gates: the `(φ,ψ)→(φ+δ,ψ+δ)` zero-energy
mode is gone; rigid-body covariance to 4e−14; bending the distal S2 shifts the lever's rest angle **1:1**
(+5.00° per 5°, +10.00° per 10°); and the joint stays **compliant** (13.7° thermal play, τ = 8.55 µs > dt).

**CPU/GPU parity (the triggered confirmation for a structural hot-kernel change): PASS** — device `TaskGraph`
lowers and executes, 0 NaN, GPU vs CPU-mirror **9.75e−10 µm**, CPU-mirror vs the scalar twin `s2SolveM`
**1.16e−09 µm**. *(Unrelated pre-existing device condition, verified against the pristine pre-repair kernel:
`matS2SolveStep` fails PTX compilation with FMA fusion on — `run_chiral_sites.sh` already carries the
documented `-Dtornado.enable.fma=false` workaround.)*

**Re-baseline scope.** Detached mechanics are unaffected (`F8 = 0`). **Bound explicit-S2 mechanics change on
every path that used `eup`** — the production mat gliding path *and* the `Cmot` `explicit-s2-l40` model, whose
tweezers/stroke fixtures must be re-quoted. **`fixed-anchor` and `calibrated-s2-l40` are untouched** (they
always used `econv`), so those frozen `MOTOR_MODELS.md` calibrations stand. Legacy escapes: `-legacy-f8axis`,
`-legacy-freehinge`, `-legacy-mechanics`.

**Visual gate re-run on the repaired motor (§16).** Both arms bind naturally onto the same site; three §9b
flags are resolved (arm B's 1.06 nm capture-step S2 jump → 0.17 nm; its anomalous head jump 1.68× → 1.01×; the
free lever mode is visibly gone), ∠(eBind, n_site) at capture improves 68° → 61° in both arms, and S2 bending
during detached search is now visible because moment continuity finally carries the head's torque to the beam.
**NEW FLAG:** bond persistence in the recorded window fell in BOTH arms (280 → 78 and 204 → 104 frames). ONE
event per arm, so NOT a lifetime measurement — but the sign is consistent and the mechanism is plausible (load
now reaches the converter). **An ensemble lifetime/duty measurement on the repaired motor is REQUIRED before
bound lifetime, duty ratio or `avgBound` are re-used from gliding/density/viscosity work.** Still unchanged:
~61° actin-blind orientation gate, head/actin steric overlap, diffusive-coarse search at production dt.

**Not done:** the lever joint is implemented in the production explicit-S2 solvers only — the `Cmot` `s2Solve`
path and the beam-replica assemblies keep the free hinge (a stated limitation that preserves every beam-solver
replica gate). Site-normal binding and twirling remain OUT OF SCOPE and unstarted.

---

## 9l. Post-head-freedom geometric validation — lever OK, sterics REAL, 25° gate NOT reachable (2026-08-13)

**Diagnostic only; nothing changed. Report: `docs/motor/POST_HEAD_FREEDOM_VALIDATION.md`. Raw:
`RUN_LOGS/motor_audit/post_head_freedom_validation/`.; viewer runs `threejs_posthead_{lever,sterics,eventC}` at the repo root.**

**(A) The repaired S2→lever junction is mechanically sound.** 200 000 detached steps: mean θ_joint 81.825° vs
rest 81.732° (offset 0.09°), SD 10.75° — *below* its own thermal amplitude 13.70°; **0 of 199 999** single
timesteps exceed 3× that SD; autocorrelation → 0 by lag-100 with no drift; mean |θ−θ₀| = 8.59° vs mean
**interior** beam bend 12.40°, so the terminal joint is **less** strained than the beam's own joints — no
terminal kink, no lever spin.

**(B) Head/actin overlap is REAL, and it is not a projection.** Head ellipsoid (4.5 × 2.75 × 2.25 nm) vs the
3.5 nm actin cylinder, radial clearance: **bound — inside the actin in 99 of 105 frames (94.3 %), mean
−3.09 nm**; detached — through the filament 40.1 % of the time. Deepest **−6.54 nm** with the head centre
**0.21 nm from the filament axis**. Only the F8 *point* is constrained; the head *body* is sterically absent
from actin on this path.

**(C) THE LOAD-BEARING RESULT — the capture path is judging the wrong head, and the 25° site-normal gate is
not reachable as things stand.** Over 134 696 detached candidate evaluations the capture geometry (recomputed
from φ,ψ with χ ignored) differs from the integrated χ-aware head by **mean 4.97 nm / 39.2°** (max 10.7 nm /
89°) — against a 3 nm spatial gate. Ignoring χ flips the spatial decision in 0.72 % of evaluations. Of **20
natural production captures, 0 would pass** a ≤25° site-normal gate: all 20 sit **43.6–64.3° (mean 53.9°)**
from n_site, while their true xF8 is inside 3 nm at every one — **the disagreement is entirely orientational,
not spatial**. Binding-compatible poses (true reach AND ≤25°) occur in **15 of 134 696 steps = 0.011 %**, so
adopting the gate unchanged would cut capture by ~3 orders of magnitude.

**No pre-steering** (trend −0.03 deg/frame, range 75°, 0/20 monotonic) — the head arrives thermally; there is
no candidate-derived torque in the code path. **Snap prediction:** gating at ≤25° cuts the initial bound
strain 4.7× (55.0 → 11.8 kT) but 11.8 kT is still substantial, and τ/dt = 0.29 means the bound orientational
relaxation is **not resolved** at production dt (the standing sub-step issue).

**Before any binding-law change:** close the ~39°/~5 nm gap between the capture geometry and the integrated
head first — the gate should at minimum evaluate the head the solver is actually moving. Whether the residual
mismatch is then a tolerance, rest-orientation or site-normal-definition question is the open decision.
*(Caveat on record: four flaws in the probe itself were found and fixed before these numbers were
trustworthy; earlier "50 % B / 50 % C" and "100 % B" passes were artifacts — see the report §8.)*

---

## 9m. χ-aware capture audit — a RETRACTION, and the orientation mismatch diagnosed as CASE C (2026-08-13)

**Report: `docs/motor/CHI_AWARE_CAPTURE_ORIENTATION_AUDIT.md`. Diagnostic only; nothing changed.**

**RETRACTION (load-bearing).** The claim that the binding gates "recompute the head pose from (φ, ψ) and
ignore χ" — in `RESTORED_3D_HEAD_TILT_DOF.md` §10.5 and repeated as the headline of
`POST_HEAD_FREEDOM_VALIDATION.md` §3/§4 — is **WRONG**. `siteGateA` reads xF8/xH from `outGeom` **only** (0 `q`
reads) and `matBindExplicit` likewise; `stepGlidingCPU` writes `outGeom` with `matBeamGeomTilt`, so **the
capture path already evaluates the exact χ-aware head the solver moves** (verified 0.000e+00 over 200
randomised states). `siteCommitB` computes **no head geometry at all** — it gates ψ/φ/θ and an energy budget —
so **there is no `eBind` in the capture path to make χ-aware**. The earlier "capture is judging a head ~5 nm /
~39° away" compared `outGeom` against a χ=0 recomputation that nothing uses; those numbers are real but
measure **the size of the χ coordinate**, not a capture inconsistency. Both prior reports now carry
retraction notes. **No code change was required or made.**

**DIAGNOSIS — the ~54° site-normal mismatch is CASE C** (native rest orientation), with D and E contributing:
- **CASE A excluded, exactly:** `|n_site|`=1, `n_site·r̂_outward`=1.000000000 at every site, sites exactly on
  the 3.5 nm surface, +54.000°/site, rigid-rotation covariance 3.2e−08.
- **CASE B excluded as a sign error:** at capture ∠(eBind,+n_site)=61.0°, ∠(eBind,−n_site)=119.0° — neither
  convention is the target, because **the rest orientation was never defined against a site normal**.
  `eBind_rest` derives from `ψ_actin`, a base-frame angle predating discrete sites by ten days (`3769ff6`).
- **CASE C, the cause:** conditioned on true spatial reach, **∠(eBind_rest, n_site) = 47.9° mean / 46.0°
  median, within 25° only 0.89 % of the time** — the potential aims ~48° off the site normal whenever the head
  is in reach, and the head sits only ~21° from that rest pose.
- **Conditional distribution given `|xF8−x_site| < 3 nm`** (559 of 116 814 detached steps = 0.479 %): mean
  **82.8°**, median 83.8°, p10/p25/p75/p90 = 49/59/101/119°; **≤25° only 2.68 %** (≤15° 1.07 %, ≤45° 6.44 %).
- **Selection effect worth knowing:** at real captures the mean is 53.9°, not 82.8° — today's `|ψ−ψ_actin|<25°`
  gate incidentally selects poses near the rest orientation and so acts as a weak, accidental proxy for site
  alignment without ever referring to a site.
- **CASE D contributing:** the head centre is *inside* the site's tangent plane on **63 %** of reachable steps
  (the same geometry as the 94 % bound steric overlap), and **only side azimuths (0–120°) are ever reached** —
  lower and upper sites get zero encounters. Azimuth matters strongly: the 0–60° band reaches **10.6 % within
  25° (best 5.07°)** while 60–120° never gets below 33.8°. **Binding-compatible orientations do occur; they are
  rare and confined to one band.**

**Implication for the binding law (reported, not actioned):** the fix is not to loosen 25° but to decide what
the bound orientation should be *relative to the site frame* — retargeting the rest/bound orientation to the
site normal is the only option that addresses the root cause. The model currently has **no stated answer** to
"what angle should eBind make to n_site in a correctly bound head?"; the historical sphere-head stereospecific
pose is the natural oracle and was not consulted.

**k_det CONTROL — the sharpest evidence for CASE C.** Doubling the detached stiffness (k_det 5 → 10) holds the
head closer to its own rest pose, and because that pose is ~48–51° off `n_site` the distribution concentrates
there: ≤60° 26.7 % → **49.3 %**, ≤45° 6.4 % → **14.2 %**, **but ≤25° gets WORSE, 2.68 % → 1.54 %** (mean 82.8°
→ 65.6°; head centre inside the site plane 63 % → 80 %). **Tightening the potential pins the head more firmly
at the wrong angle** — neither the tolerance nor `k_det` can rescue a rest pose that is ~50° off.
*(This comparison initially ran as two identical arms — `scene()`'s `resetChiral()` clobbered `K_DET_PNNM`;
fixed and now asserted, so the arms above are real.)*

---

## 9n. Canonical site-normal head binding — `xHeadHat = -n_site` IMPLEMENTED (2026-08-13)

**Report: `docs/motor/SITE_NORMAL_HEAD_BINDING.md`. Flag-gated, DEFAULT-OFF, byte-identical when off
(`ExplicitCompleteMatHarness.SITE_NORMAL_BIND`). CPU runner only — the device path REFUSES.**

**The bound pose is now SPECIFIED and implemented:** the head-local **+x** axis (its ellipsoid LONG axis)
points antiparallel to the helically informed outward site normal, `xHeadHat = -n_site`, so F8 faces the
filament and the head body extends radially outward.

**PHASE-0 RESULT, load-bearing and previously unstated: `xHeadHat` is NEITHER `eBind` NOR `-eBind`.**
`eBind = normalize(xF8 - xH)` is the direction of the material POINT `r_F8 = (+3.5, +1.5) nm`, which sits a
FIXED **d = atan2(rF8y,rF8x) = 23.19859 deg** off the head's long axis; `dot(xHeadHat, eBind) = cos d =
0.919145` constant to **2.3e-12 deg** across 400 randomised (psi, chi) states, and at `chi = 0` `xHeadHat`
reproduces the head's material `a_hat` axis to 3.3e-16. **Every angle in sections 9l and 9m was measured on
`eBind`, i.e. 23.2 deg off the head axis and referenced to the OUTWARD normal**; both reports now carry
correction banners. Two visualization defects fall out and are fixed: the ellipsoid was drawn along `eBind`
and centred on the midpoint of `xH..xF8` rather than on `xH`.

**What changed (all additive):** `SiteNormalBindSystem.headAxisStep` writes `xHeadHat` into `outGeom` rows
9..11 — ONE axis read by the gate, the solver's target, the steric read-out and the viewer ellipsoid.
`siteGateA` gains `angle(xHeadHat, -n_site[k]) <= 25 deg` against the ACTUAL candidate site, replacing the
actin-blind `|psi - psiActin| < 25 deg`; `g5`'s orientation energy becomes `1/2 k_bind theta_bind^2` (the
energy the bond will actually carry) instead of the superseded base-frame term. `siteCoupleStep` rebuilds
`eTarget = -n_boundSite` each step from the LATCHED site's live material frame and applies the EXACT
equal-and-opposite reaction through the byte-unchanged `bondData[d+9..11]` / `segGather` channel.
`matS2SolveStepTilt` carries the term implicitly with the exact Gauss-Newton Hessian, which now has a
genuine psi-chi CROSS term `k_bind sin(d) cos(d) sin(chi)` (identically 0 at `d = 0`). **`k_bind` = 512
pN.nm/rad^2 unchanged and NOT tuned. The detached rest potential, `k_det`, chemistry and `xCatch` are
untouched. No pre-capture steering: the site exerts zero torque, zero attraction, zero retargeting before
the latch.**

**Gates, all PASS.** A target (`|xHeadHat - (-n_site)| = 5.6e-17`, `Q_psi/Q_chi ~ 1e-35 N.m`); B rigid
covariance (`max |dU| = 2.6e-06 kT` over 9 rotations); C site material frame (`|n(R.fil) - R n| = 1.8e-08`);
D reaction closure (`|T_head + T_fil|/|T_head| = 9.8e-09`, the float32 precision of the shared bond channel;
FD virtual work sums to zero to 4-6e-09 on three axes); E helical azimuths (`dot = 1.000000000000` at every
reachable one); Phase-6 finite differences (3e-11 relative at 15/25 deg); Phase-6b implicit stability
(`tau/dt = 0.289`, bounded, relaxes to the 5.1 deg thermal amplitude); and DETACHED OFF == ON
**bit-identical** over 12 motors x 5000 steps.

**GEOMETRY — the specification is realised.** At the canonical pose the head centre sits
`|r_F8| cos d = +3.500 nm` OUTWARD of the site (exactly `R_actin`), i.e. 7.0 nm from the filament axis, F8 on
the filament-facing end. **The head/actin overlap falls from mean -3.09 nm / worst -6.54 nm (section 9l) to
-0.92 .. -1.00 nm — roughly 3x on the mean and 6.6x on the worst case — with NO steric force added.** The
residual ~1 nm is arithmetic: a 2.75 nm transverse semi-axis at 7.0 nm from the axis still reaches ~1 nm
inside a 3.5 nm cylinder.

**BLOCKING RESULT — ZERO natural captures across three horizons (up to 2.4e6 motor-steps), and it is NOT a
tolerance problem.** Capture funnel over 200 000 steps: `g6` 79.6 %, `g8` 77.8 %, `g0` (< 3 nm) 0.123 %, `g4`
(preload, < 2 nm) 0.0405 % -> **81 candidates pass every spatial gate, and NOT ONE is within 25 deg**:
`angle(xHeadHat, -n_site)` mean **106.8 deg**, BEST **41.4 deg**. 1 motor x 600 000 steps: 0 events. **12
motors x 200 000 steps = 2.4e6 motor-steps: 0 events.** A 180-deg control also yields 0, because the rewritten
`g5` budget then binds at 28.1 deg — the two agree, which is itself a consistency check.
**THE PRECISE FAILURE is an ANTI-CORRELATION, not a rate:** orientation-compatible poses DO occur (2.1-3.6 % of
loose-reach steps, best **2.34 deg**) and spatially-qualified candidates DO occur (0.04 % of steps), but their
intersection is empty in every run — the head poses that bring `xF8` within 2 nm of a real site are precisely
the ones whose long axis is not radial. A longer run does not fix this. **The cause is section 9m's CASE C measured on the correct
vector: the native detached rest orientation and the canonical bound pose are on OPPOSITE SIDES of the
sphere.** Phase 8 of the task forbade retargeting the detached potential, so this is NOT closed here. Options
in the report; **recommended: re-derive `(c1, c2, c3)` — the native head pose in the live neck frame — from the
canonical bound geometry instead of the historical `psi_actin`.** That is a separate task: it re-baselines
every capture statistic on record. **Do not proceed to gliding or twirling.** The only viewer trajectory that
exists is the deterministic canonical-pose fixture `threejs_sitenormal_canonical` (NOT a natural capture).

**NEW STRUCTURAL CONSTRAINT — a 23.2 deg dead cone.** `xHeadHat`'s component along `econv` is
`cos d sin chi`, bounded by `cos d`, so the head's +x axis **cannot point within 23.2 deg of +-econv** and the
canonical pose is geometrically UNREACHABLE at those sites: **2 of 7 consecutive every4 azimuths**. This is a
property of the (psi, chi) parameterisation, not of the new law — `chi` gave `eBind` the full sphere, but
`xHeadHat`, being `d` off it, inherits a polar cap. About `1 - cos d ~ 8 %` of site normals admit no
canonical bound pose at all.

---

## 9o. F8 long-axis geometry correction — the 23.2 deg head-axis offset was a COORDINATE BUG (2026-08-13)

**Report: `docs/motor/SITE_NORMAL_HEAD_BINDING.md` section "F8 LONG-AXIS GEOMETRY CORRECTION".
Raw: `RUN_LOGS/motor_audit/f8_long_axis_correction/`.**

**RETRACTION of section 9n's headline interpretation.** 9n reported that the head-local +x axis is "`eBind`
rotated by a FIXED 23.19859 deg" and called it "a RIGID property of the head body". The *measurement* was
right; the *interpretation* was wrong. The head's F8 material point carried an unintended transverse
component — `R_F8 = (+3.5, +1.5) nm` instead of `(+3.5, 0)` — which put the actin-binding point 23.2 deg off
the ellipsoid's own long axis. **Corrected:**

```
    R_F8 = (+3.5, 0) nm   ON the long axis, 1.0 nm inboard of the +x tip (a = 4.5 nm)
    R_CONV = (-3.5, 0) nm antipodal, also on the long axis
    eBind = normalize(xF8 - xH) = xHeadHat = the ellipsoid long axis    ONE vector, three names
```

**Provenance (archaeology).** Introduced 2026-07-14, commit `e17b5a4`, with the Exp-3C topology; annotated
"opposite **corner**" with `|r_F8 - r_conv| ~ 7.6 nm` named as the design quantity; graded "geometric
construction, **no citation**" in the canonical-freeze inventory, which inventories only the SEPARATION, not
the transverse split; and Exp 3D measured the kinematics to be insensitive to it (its deliberately flatter
`(3.7, 1.0)` candidate scored within noise). **Never intended, never cited, never calibrated.**

**`R_CONV` audited separately and DID have to move.** It does not enter the head axis at all
(`xF8 - xH = R(r_F8)` for any `r_conv`), but it must stay antiparallel to `r_F8` because (a) the chi mobility
derivation in `matS2SolveStepTilt` gets `Gamma_psichi = 0` EXACTLY from `rho = xH - C` being parallel to the
head axis, and (b) `C`, `xH`, `xF8` must remain collinear with `xH` the midpoint — the recorded Exp-3C
topology. **Consequences, stated and NOT tuned back:** converter arm `7.6158 -> 7.0000 nm` (-8.1 %);
`gamma_psi` **-5.3 %**; `tau = gamma_psi/k_bind` `0.7234 -> 0.6853 us`. The opt-in `CONV_ECC_SCALE`
(`-converter-f8-eccentricity-scale`) knob is now **inert** — it scales a component that is identically zero.

**Gates after the correction (CPU runner):**
- **Head-axis identity:** `max |xHeadHat - eBind| = 1.9e-16` over 400 randomised (psi, chi); `chi = 0`
  reproduces the material `a_hat` axis to 0.0e+00. The delta machinery is REMOVED from the solver — one
  orientation convention, no cross term in the Hessian (`H_pc = 0` exactly).
- **`d xF8/dq` through the REAL kernel** (`matBeamGeomTilt`, central differences): worst relative error
  **1.2e-07** over 12 random (phi, psi, chi) x {phi, psi, chi}.
- **`U_bind` generalized forces vs FD**, now swept over 4 tilt AZIMUTHS as well as 4 misalignments so both
  coordinates are loaded: PASS.
- **THE DEAD CONE IS GONE:** the head axis now reaches **100.00 % of 4pi** (2000 equal-area bins), **0**
  bins unreachable beyond the `|chi| <= 89 deg` pole clamp, Jacobian rank 2 everywhere (min singular value
  0.0175 = cos 89 deg at the clamp). The old geometry excluded **8.09 %** of the sphere around `+-econv`.
  **All 7 consecutive every4 site azimuths are now canonically reachable (was 5 of 7).**
- **Gates A/B/C/D/E all PASS**; head centre `+3.500 nm` outward at EVERY azimuth; clearance uniformly
  **-1.000 nm**.
- **PHASE-7 MECHANICS REBASELINE (validated estimator `LiveNeckHeadProbe -reg`, nothing tuned):** stroke
  `-8.381 -> -8.458 nm` (**+0.9 %**), **polarity pointed-first unchanged**, `k_ext` `0.7211 -> 0.7566 pN/nm`
  (**+4.9 %**, slightly AWAY from the `Cmot` reference band 0.60-0.64 — reported, not corrected), theta
  `-24.972 -> -24.853 deg`, S2 extension and contour conservation unaffected (contour within 2e-5 of unity),
  every arm stable, fixed-site relaxation stationary (`rel = 4.1e-09`). The `k_det` 5/10 arms are numerically
  identical, correctly — `k_det` only applies while detached. *(The `MechanicsRepairProbe -reg` estimator
  disagrees at stroke -3.972 / `k_ext` 2.099; that probe prints its own warning that its 1 pN/nm probe spring
  makes the measurement badly conditioned, and the codebase already labels it superseded.)*
- **Monitored CPU/GPU parity on the corrected geometry: PASS** (`ExplicitMatSolveHarness` via
  `scripts/run_gpu_monitored.sh scripts/run_mats2solve_gate.sh`) — port-vs-`s2SolveM` **1.2e-09 um**,
  GPU-vs-mirror **9.7e-10 um**, recorder running, no crash.
- **Viewer acceptance, measured on the emitted frames:** `min dot(normalize(xF8-xH), xHeadHat) =
  0.99999998`, `max |cross| = 6.3e-04 nm` — i.e. exact to the frame file's own print precision. There is no
  longer any visual offset between the ellipsoid and its F8 point.

**GPU note (structural, not a validation gap):** the site-normal law still refuses the device path, and the
reason is a PREREQUISITE, not this feature — the device gliding graph wires `beamGeom` + `s2solve`, the
**non-tilt** kernels. **The chi-dynamic 3-D head has never been on the device graph at all**, so porting it is
a separate piece of work.

**PHASE-9 RETRACTION — section 9n's "anti-correlation" is WITHDRAWN.** 9n concluded from "0 of 81
spatially-qualified candidates within 25 deg" that spatial proximity and correct orientation are
anti-correlated. Measured directly on 5 809 (old) / 7 751 (new) candidate evaluations with `d < 6 nm`, matched
seed, head long axis taken analytically in both arms: **Pearson r(d, theta) = -0.004 (old) and +0.068 (new)**,
i.e. **no correlation in either geometry**, with the conditional mean theta flat across distance bins. The
conjunction `d < 2 nm AND theta <= 25 deg` is **4 in BOTH arms**, against **5.8 / 3.2 expected at
independence**. The earlier "0 of 81" was a small-sample artefact (expected count 1-4). **The F8 correction did
not need to break an anti-correlation, because there was none.**

**BOTTLENECK IDENTIFIED — and section 9n's "CASE C is the cause" is SUPERSEDED.** Evaluating all eight gates
INDEPENDENTLY on the same 1 373 candidates with `d < 3 nm` (the ordered funnel answers "which gate stopped the
survivors", which is a different question from "which gate is incompatible with the law"): marginal pass rates
`g0` 100 % · `g3` 99.4 % · `g8` 46.9 % · `g6` 44.6 % · `g4` 29.9 % · **`g2` phi 13.0 %** · `g5` 3.2 % ·
**`g1'` orientation 2.6 %**. **Of the 10 candidates that are BOTH in reach (`g0` and `g4`) AND correctly
oriented (`g1'`): `g2` (lever angle) rejects 10/10, `g6` (head side) rejects 9/10, `g8` rejects 6/10, and
`g3`/`g5` reject NONE.** **`g6` is STRUCTURALLY incompatible with the canonical pose**: it allows the head
centre `A_SEMI[2] = 2.25 nm` above the segment centre, and the canonical bound pose puts it **6.414 nm**
there (measured at the same sites) — 2.85x the threshold. `g6` was written for a head approaching from the
lawn side; the site-normal law requires a head standing off the surface. **The rewritten `g5` energy budget
rejects none of them, so that rewrite is sound.** ⇒ **The next decision is `g6`/`g2`, NOT recalibrating
`(c1,c2,c3)`** — with those gates as they stand, fixing the detached rest pose alone would still give zero
captures.

**CAPTURE IS STILL ZERO, and the bottleneck is now cleanly stated.** Same assay, detached rest pose untouched:
0 natural captures in 200 000 single-motor steps. Two independent scarcities multiply, neither of them the
orientation law: (i) **spatial reach is rare and got rarer** — `g0` (< 3 nm) `0.123 % -> 0.037 %` and `g4`
(preload, < 2 nm) `0.041 % -> 0.012 %`, because `|r_F8|` shrank 3.808 -> 3.500 nm; (ii) the detached native
rest orientation is still ~100 deg from the canonical target — **section 9m's CASE C, untouched by this
correction and out of scope by instruction**. Conditioned on the full spatial chain the orientation DID improve
(mean `106.8 -> 90.9 deg`, best `41.4 -> 35.0 deg`) and the distribution **split into two identifiable modes**
— "approaching from outside" (12 of 23 in `[30,80) deg`) and "threaded through the actin" (11 of 23 in
`[100,170) deg`, the known steric defect) — where the off-axis geometry had smeared them into one lump at
107 deg.

---

## 9p. Legacy g6/g2 RETIRED for the site-normal motor — it now binds NATURALLY (2026-08-13)

**Report: `docs/motor/SITE_NORMAL_HEAD_BINDING.md` sections 14b-14h. Raw:
`RUN_LOGS/motor_audit/site_normal_gate_retirement/`. CPU sequential runner; one monitored GPU parity gate
(the geometry, not the tilt path).**

**DECISION (jba): `g6` (head side) and `g2` (`|phi - phi_pre| < 25 deg`) are RETIRED — ONLY when
`SITE_NORMAL_BIND = true`.** Explicit branches, never faked as "pass = true": the funnel and the marginal
diagnostic print them as `[RETIRED]`. `SITE_NORMAL_KEEP_G6/G2` re-apply them for the ablation control only.
**The OFF path is BIT-IDENTICAL** — the two guard lines were reverted in a scratch tree, rebuilt, and both
binaries ran the legacy path over 12 motors x 150 000 steps with a fingerprint covering capture decisions,
bound-step count, final `(phi, psi, chi)` and every beam node to 17 digits: `diff -> IDENTICAL`. *(Scope: that
scene yields 0 legacy captures, so the capture-decision part is verified trivially plus by construction; the
trajectory/geometry equality is a full bit-for-bit match.)*

**THE MOTOR NOW BINDS NATURALLY.** Funnel over 200 000 single-motor steps, same seed and scene:

| gate | g6/g2 ACTIVE | **RETIRED** |
|---|---:|---:|
| `g8` accessibility | 66.86 % | 97.53 % |
| `g0` `< 3 nm` | 74 (0.037 %) | **626 (0.317 %)** — 8.5x |
| `g4` preload `< 2 pN` | 23 (0.012 %) | **185 (0.094 %)** — 8.0x |
| `g1'` orientation `<= 25 deg` | 0 | **2** |
| **captures** | **0** | **2** |

**Retiring `g6` did not merely stop one rejection — it multiplied the spatially-qualified candidate supply
~8x**, because `g6` was cutting precisely the outward-standing poses that both reach a site and satisfy the
canonical orientation. **The rate-limiting gate is now `g1'` itself** (2 of 185 spatially-qualified = 1.08 %;
mean 100.43 deg, best 14.34 deg); `g3` and `g5` reject none of the survivors.

**CAPTURES ARE MECHANICALLY CLEAN.** 3 events in 400 000 steps x 12 motors. `theta_bind` at capture
24.2 / 11.4 / 20.3 deg, relaxing to 3.9 / 2.1 / 9.1 deg in ONE step and settling near the 5.1 deg thermal
amplitude. **NO SNAP:** per-step motion at the capture step is **1.08x** (head centre) and **1.16x** (F8) the
ordinary detached thermal step, and the S2 extension change is **0.61x** it; `|d chi| = 13.86 deg` is exactly
the free-head thermal increment. **Azimuth: all 3 "upper"** (normal pointing away from the lawn) — the band has
MOVED to where the canonical geometry says a radially standing head must bind (the legacy path reached only
side azimuths, section 9m), but it is still ONE band, and 3 events cannot distinguish preference from scene.
**Yield is 3, not the requested 20** (~1 per 1.6e6 motor-steps).

**ABLATION (explanatory; neither gate reintroduced), 12 motors x 150 000 steps:** A both live **0** | B g6
retired **0** | C g2 retired **0** | D both retired **1**. **Neither gate alone is "the" blocker — they had to
go together**, exactly as the marginal measurement predicted (`g2` rejected 10/10 of qualified candidates,
`g6` 9/10). Counts are small; the pattern is clear, the ratio is not quantified.

**VIEWER — the acceptance condition holds, measured ON the frames.** `threejs_sitenormal_bound_capture`,
**801 frames, one per timestep**, capture at frame 400 (sim step 35 996). Over its 197 bound frames:
`dot(xHeadHat, -n_site)` **mean +0.9921**, best +1.0000, worst +0.9120 ⇒ **mean theta_bind 7.20 deg**; head
centre **+4.6 .. +8.4 nm outward** throughout. The bound ellipsoid stands radially out with F8 on the
filament-facing end.

**BOND LIFETIME / DUTY (Phase 7), 1.8e6 steps x 12 motors = 2.16e7 motor-steps, 5 captures all resolved:**
mean lifetime **264.4 steps = 661.0 us**, median 89.0, p10 17 / p90 197; **duty 0.00612 %**; **every release in
state NONE** (the ordinary ATP-binding terminus, not mechanical rupture); F8 extension at release 4.316 nm;
**mean orientation error while bound 6.68 deg** against a 5.14 deg thermal amplitude — the site-normal
potential holds the head at its thermal floor and is not fighting the mechanics. **No evidence that the new
orientation mechanics altered bond persistence** (same order as the repaired motor's ~350 us), but **n = 5 is a
sanity check, not a distribution measurement**. **Duty, not the bond, is what keeps `avgBound` low.**

**MECHANICS UNCHANGED (Phase 8).** `LiveNeckHeadProbe -reg` reproduces the axial-F8 baseline digit for digit
(stroke **-8.458 nm** pointed, `k_ext` **0.7566**, theta -24.853, contour 0.999981, stable) — as it must,
since the retirement touches only capture ELIGIBILITY, never a force, torque or stiffness.

**GLIDING COMPATIBILITY — NOT DEMONSTRATED, and the resolvable half says why.** Smoke test (NOT a campaign):
3 seeds x 5000 steps, density 400, 12 segments, filament Brownian on, `eta = 0.01 Pa.s`, nothing tuned.
**glide `+2.08 +- 5.25 um/s` (SEM, n=3), sign FLIPPING across seeds (+1.65 / +11.39 / -6.79) ⇒ NOT RESOLVED.**
That is an underpowered estimator, quantitatively: the filament's own diffusion gives an apparent-velocity
floor `sqrt(2D/T) = 6.41 um/s` for this 9.375 ms window (`D_par = 0.1929 um^2/s`), and the observed seed SD is
9.1 um/s — the same scale. **A 2 um/s glide could not have been detected here even if present**; resolving it
needs **~38 600 steps/seed** (8x longer). *That is a limitation of the smoke test, not of the motor.*
**What IS resolvable — the pull/drag decomposition, a time-average over bound samples rather than a
displacement slope — says there is NO net thrust: 51.2 % pulling, mean axial force pull `+3.8546e-14 N` vs
drag `+3.8452e-14 N`, equal to 3 significant figures.** **What DID work: the motor RECRUITS in a many-motor
scene (`avgBound = 0.72 +- 0.30`, structurally 0 before the retirement) and the solver is CLEAN (0 invalid,
0 solverFail).** Rotation `omegaFit = +9.8 +- 129.7 rad/s`, sign-flipping — **DIAGNOSTIC ONLY, not a twirling
result.** **Phase 10 (density) NOT run** — its precondition ("only if the initial glide test works") failed.
**Phase 11 (ATP control) launched then CANCELLED** — with the powered velocity unresolvable an ATP-depleted arm
cannot resolve a velocity difference either, and at `[ATP]=0` heads cannot detach so its noise floor is not even
matched; ~2 h for an uninterpretable comparison. **The right next experiment is a LONGER WINDOW, not a bigger
scene.**

**GPU — STOPPED AT CPU, deliberately and per instruction.** The device graph has no `beamGeomTilt`, no
`s2solveTilt` and no `kbindGate`: **the entire chi-dynamic 3-D head stack has never been on the device**.
Wiring it is **5 kernels** (`matKbindGate` 4 args, `matBeamGeomTilt` 8, `headAxisStep` 7, `siteCoupleStep` 12,
`matS2SolveStepTilt` **15 — exactly at TornadoVM's task() cap**, replacing the graph's largest kernel) plus 4
new transfers, with an unquantified lowering risk (15x16 Gauss-Jordan vs 14x15, scratch 240 vs 210 doubles, on
a backend where the smaller version already needs `-Dtornado.enable.fma=false`). That is a port, not a wiring
step. `buildGlidingGraph` still throws when `siteNormalOn()`.

---

## 9q. Site-frame power-stroke polarity audit — the 50/50 was MY MEASUREMENT ARTEFACT (2026-08-13)

**Report: `docs/motor/SITE_FRAME_POWER_STROKE_AUDIT.md`. Raw:
`RUN_LOGS/motor_audit/site_frame_power_stroke/`. AUDIT ONLY — nothing tuned. CPU runner; no GPU work.**

**RETRACTION of section 9p's gliding reading.** 9p reported "the bound population is a 50/50 tug-of-war with
no net axial bias ... mean axial force pull `+3.8546e-14` vs drag `+3.8452e-14 N`, equal to 3 significant
figures ... a resolved statement". **Both halves are withdrawn.** (i) `nPull`/`nDrag` classify by
`fax * vFil` (`ChiralSiteHarness:2290`) — **mechanical POWER against the instantaneous filament velocity**, not
polarity. With a 6.41 um/s Brownian noise floor `sign(vFil)` is a coin flip, so the split tends to **50/50 BY
CONSTRUCTION for any force polarity**. (ii) `fAxPull`/`fAxDrag` are summed forces *within* the power-defined
subgroups; their near-equality is arithmetically meaningless as a bias measure. The correct quantity is their
SUM, the net axial force: per seed **-0.068 / +0.497 / -0.198 pN**, mean **+0.077 +- 0.213 pN**,
`|mean|/SEM = 0.36` ⇒ **not distinguishable from zero — unresolved, NOT zero thrust.**

**SIGN CONVENTION PINNED (measured, not assumed):** `dot(b_hat, u_fil) = +1`, `dot(end2-end1, u_fil) > 0`,
`bindArc` increases toward `+u_fil` ⇒ **`+u_fil` = BARBED (end2), `-u_fil` = POINTED (end1)**. Productive signs:
**force on the FILAMENT `fax < 0`** (toward pointed), **motor reaction `> 0`** (toward barbed), **glide `< 0`**
(pointed-first).

**THE STROKE POLARITY IS CORRECT AND AZIMUTH-INVARIANT.** Deterministic fixture: motor Brownian off, real
`ADP.Pi -> ADP` transition (F8 never translated by hand), azimuth swept by **rotating the filament's material
frame** so the site's AXIAL coordinate is held fixed (all 8 arms bind site 155). Under an **isometric** clamp
`fax_post` is **NEGATIVE at 8 of 8 azimuths** (-3.24 to -10.38 pN) and the stroke's own increment is
`d fax = -1.39 pN` mean (6/8 negative) — **force on the filament toward POINTED everywhere on the helical
circle.** Under a **free** filament the residual force relaxes to ~0 with mixed sign, which is the expected
behaviour of a single motor relaxing to a new equilibrium, not a contradiction. *(A first sweep that walked the
every4 lattice was CONFOUNDED — each site is +10.8 nm further along the axis, so it measured an increasingly
stretched bond; discarded and redone.)*

**SITE-NORMAL TORQUE INJECTS NO AXIAL FORCE, by construction:** `siteCoupleStep` writes only `bondData[d+9..11]`
(the segment TORQUE slots) and never `d+6..8` (FORCE) — a pure couple.

**DIAGNOSIS: primary D (telemetry interpreted backwards). A REFUTED** (polarity correct and invariant),
**F REFUTED** (no axial leakage). **B/C/E remain OPEN** as contributors to the underlying force distribution —
the natural-event sample was **n = 3** (7.2e6 motor-steps), far too small for the six-way decomposition, and
all three events share one motor and site so the anchor-geometry comparison is degenerate. **D alone fully
explains the reported 50/50.**

**⇒ A longer Brownian-ON gliding run IS now scientifically justified** — no mechanics correction is indicated
first. Required window (from section 9p): **~40 000 steps/seed** to lift a 2 um/s glide above the diffusive
floor. `-glide-compat` now prints `r.fax` with a significance test.

---

## 10. Load-bearing reports

Read these before revisiting the associated topic:

- `docs/TWOBODY_BLIND_TWEEZERS_SYNTHESIS.md` · `docs/TWOBODY_BIOCHEMICAL_CYCLE.md` · `docs/TWOBODY_SPARSE_MULTIMOTOR.md` · `docs/TWOBODY_LOWDENSITY_GLIDING.md` · `docs/TWOBODY_FLEXIBLE_MAT_GLIDING.md` · `docs/TWOBODY_FULLCOVERAGE_MAT.md` · `docs/TWOBODY_TAIL_RECRUITMENT.md` · `docs/TWOBODY_SUPPORTED_S2_TAIL.md` · `docs/TWOBODY_MD_INFORMED_S2.md` (two-body replacement-motor arc; §9b)
- `docs/attachment/CANONICAL_ACTIN_ATTACHMENT_AUDIT.md` · `docs/attachment/PATH_B_SITE_ACCESSIBILITY_TELEMETRY.md` · `docs/attachment/FILAMENT_Z_SLAB_AND_ACCESSIBILITY_RERUN.md` (attachment architecture, accessibility telemetry, z-boundary; §9d) · `docs/attachment/SPARSE_LONG_PITCH_ACTIN_SITE_LATTICE.md` (**the current Path-B site geometry**; §9f) · `docs/attachment/BOUND_MOTOR_HELICAL_GEOMETRY_VISUAL_AUDIT.md` (**bound-head orientation is lab-referenced, not site-referenced**; §9h) · `docs/motor/MYOSIN_HEAD_ORIENTATION_DOF_HISTORY.md` (**the head's 3-D orientational DOF was lost at the 2026-07-14 two-body replacement; it explains the 30 % site mask**; §9i) · `docs/motor/RESTORED_3D_HEAD_TILT_DOF.md` (**neck–head tilt χ restores it kinematically, 20/20 site normals, no RAND_BASE_AZ; dynamics not yet done**; §9j) · `docs/motor/POST_HEAD_FREEDOM_VALIDATION.md` / `docs/motor/CHI_AWARE_CAPTURE_ORIENTATION_AUDIT.md` (**both carry 2026-08-13 correction banners: their orientation angles were measured on `eBind`, which is 23.2° off the head axis**; §9l/§9m) · `docs/motor/SITE_NORMAL_HEAD_BINDING.md` (**THE CURRENT BINDING LAW: `xHeadHat = −n_site`; `xHeadHat` is neither `eBind` nor `−eBind`; all gates pass but natural capture yield is ZERO — read §14 before any gliding or twirling work**; §9n)
- `docs/FINE_DT_V0_REFERENCE.md`
- `docs/TIMESTEP_SERVO_AUDIT.md`
- `docs/CANONICAL_STROKE_DISAMBIGUATION.md`
- `docs/EPISODE_KERNEL_ACCOUNTING.md`
- `docs/FORCE_BALANCE_CLOSURE.md`
- `docs/VMAX_SENSITIVITY_25C.md`
- `docs/MOTOR_PARAMETER_PROVENANCE_25C.md`
- `docs/GLIDING_TARGET_25C.md`
- `docs/twirling/LOW_ATP_GLIDING_TWIRLING_FINDINGS.md` (low-[ATP] condition transfer; §9c)
- `docs/twirling/ZERO_SKEW_SPARSE_LATTICE_MIRROR_GPU.md` (**M0**: the sparse helical lattice alone generates no chiral torque at eps = 0; §9g)
- `docs/VISCOSITY_SENSITIVITY_FINDINGS.md` (viscosity campaign + mirror control)
- `J2_CONFORMATION_ARCHITECTURE.md`
- `J2_NATIVE_ANGLE_AUDIT.md`
- `J2_SINGLE_MOTOR_TRANSMISSION.md`
- `J2_FORCE_VELOCITY.md`
- `J2_DENSITY_SATURATION.md`
- `J2_CONFORMATION_VERDICT.md`
- `JOURNAL.md`

Before starting new work, record:

- git commit and dirty status;
- runner and hardware;
- timestep and equal physical duration;
- exact density, capture radius, chamber, and coverage criteria;
- seed inventory;
- whether the result is CPU, GPU, or CPU-arbitrated.
