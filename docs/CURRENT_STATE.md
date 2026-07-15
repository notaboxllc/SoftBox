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

The project has documented CPU/GPU basin sensitivity in some gliding configurations. Reported GPU curves require CPU-arbiter spot checks, particularly when code paths differ or high-density trajectories become unstable. Do not mix CPU and GPU values silently in one fitted curve.

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
2. **Analyze direct timestep shifts, plateau identifiability, and CPU-arbiter points.**
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

## 10. Load-bearing reports

Read these before revisiting the associated topic:

- `docs/TWOBODY_BLIND_TWEEZERS_SYNTHESIS.md` · `docs/TWOBODY_BIOCHEMICAL_CYCLE.md` · `docs/TWOBODY_SPARSE_MULTIMOTOR.md` · `docs/TWOBODY_LOWDENSITY_GLIDING.md` · `docs/TWOBODY_FLEXIBLE_MAT_GLIDING.md` · `docs/TWOBODY_FULLCOVERAGE_MAT.md` · `docs/TWOBODY_TAIL_RECRUITMENT.md` · `docs/TWOBODY_SUPPORTED_S2_TAIL.md` · `docs/TWOBODY_MD_INFORMED_S2.md` (two-body replacement-motor arc; §9b)
- `docs/FINE_DT_V0_REFERENCE.md`
- `docs/TIMESTEP_SERVO_AUDIT.md`
- `docs/CANONICAL_STROKE_DISAMBIGUATION.md`
- `docs/EPISODE_KERNEL_ACCOUNTING.md`
- `docs/FORCE_BALANCE_CLOSURE.md`
- `docs/VMAX_SENSITIVITY_25C.md`
- `docs/MOTOR_PARAMETER_PROVENANCE_25C.md`
- `docs/GLIDING_TARGET_25C.md`
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
