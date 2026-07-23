# OPEN BIOPHYSICAL PARAMETERS — the short pre-freeze list

**The genuinely debatable structural parameters that remain before the canonical model is frozen for the
mechanical-cooperativity analysis.** Intentionally short. **Already-validated chemistry is NOT here** — those
are FROZEN (`PARAMETER_FREEZE_CLASSIFICATION.md` §A/B) and are not reopened merely because changing them would
alter gliding. Everything below is *structural/mechanical geometry* whose value is weakly constrained and whose
alternatives may legitimately be tested before freeze. Two are also the declared perturbation targets of the
paper's §6.2 (S2 / lever / anchor / branch compliance), so testing them is a planned structural study, not
tuning.

For each: why open · plausible range + basis · observables expected to change · minimum experiment to close ·
completed validations that must rerun if changed.

> **FREEZE-CLOSURE (2026-07-22, canon v2).** Entries now CLOSED and removed from this list: **S2 bending
> stiffness EI** is `CONDITIONALLY FROZEN — MD/LITERATURE CONSTRAINED` (kLatRef=0.01 dead-center in the
> AMK-2008 0.008–0.012 band; `S2_MD_PROVENANCE_CORRECTION.md`); **branchEA** is RESOLVED to 0.03 (NUMERICAL
> COMPLIANCE — CALIBRATED BY CONSTRAINT; `BRANCHEA_CALIBRATION_FINDINGS.md`); the **chemistry + force-dependent
> release** (rigor rupture now the canonical default ON) and the **GPU production governance** are frozen
> (`CANONICAL_FREEZE_CLOSURE_FINDINGS.md`). The S2 **exposed length** is CONDITIONALLY FROZEN at L40 (Gate A);
> §1 below is retained as the flagship structural sensitivity (exposed contour length + boundary condition,
> material frozen — the paper's §6.2 target).

---

## 1. Exposed/free S2 contour length + surface boundary condition — FLAGSHIP (CONDITIONALLY FROZEN — GEOMETRY, L40)

- **Canonical value L40; conditionally frozen — geometry.** The S2 **material is frozen** (EA + EI MD-constrained).
  The bounded uncertainty is **how much of the ~60 nm S2 is exposed above the coverslip** vs adsorbed/supported —
  the free contour length and the emergence boundary condition (40–60 nm). L∈{10,20,40,60} were built
  (`EXP4G_L_NM`); L40 is the canonical partially-supported reference, L60 the exposed-tail limit. Because bending
  compliance grows with L, the effective axial reaction cannot be 1/L-scaled between L40 and L60 (**L40≠L60**),
  so the exposed length is a real boundary-condition choice even though the material is fixed. **Permitted
  variation = declared structural sensitivity only** (never retune L to a gliding target and silently swap the
  baseline).
- **Plausible range + basis.** 40–60 nm exposed (the full S2 is ~60 nm; a surface assay adsorbs some fraction).
  A geometry choice on the same MD-characterized molecule.
- **Observables expected to change.** `k_ax=EA/L` (105 pN/nm at L40 → 70 at L60); Euler buckling F_crit (4.4 →
  2.0 pN); the load-bearing-vs-bending head fraction (explicit ≈67% taut at L40); gliding Vmax (amplitude).
- **Evidence split (L60 sweep COMPLETE 2026-07-23 — Outcome 1).** *Demonstrated across L* (beam/single-molecule):
  stroke L-robust; axial/bending/buckling scale as MD predicts; contour conserved; decoupling holds for L≥40.
  *Ensemble (now tested)*: hyperbolic saturation, modest Vmax (+2.6 % single-head), recruitment beyond
  saturation, near-hyperbolic response = **DEMONSTRATED** robust; ρ½ +20 % right-shift (mechanical accessibility)
  demonstrated; dimer slowdown + dimer/single ratio = **SUPPORTED** (dimer arm noise-limited). L40 stays
  canonical; L60 is a supporting §6.2 sensitivity result (`L40_VS_L60_GLIDING_COMPARISON.md`). Do NOT run a
  material-stiffness study; the only optional follow-up is a tighter dimer L60 (substep / NDOF=25 GPU + seeds).
- **Reruns if the exposed length is changed.** Dimer + single-head gliding density sweeps, the calibrated
  surrogate re-fit at the new L (kAxTension), GPU/CPU spot-check. (Step-size / force-clamp are L-robust in stroke.)

## 2. HMM branch/fork geometry — branchLen 10 nm, SPLAY 16°, ALPHA 10°, BREI 0.25

- **Why open.** Fork-relaxation geometric/compliance choices with **no external citation**; they set two-head
  binding geometry and internal opposition — exactly the dimer-slowdown mechanism the paper (§5.3/§5.4) is built
  to explain.
- **Plausible range + basis.** Structural (branch = the proximal S1–S2 junction); ranges from the HMM crystal
  geometry, but not pinned in-model.
- **Observables expected to change.** One-/two-head-bound fractions, sister-head correlation, dimer Vmax, duty.
- **Minimum experiment.** A coarse geometry sensitivity (branchLen, SPLAY) confirming the dimer conclusions
  (recruitment scale preserved, Vmax lowered) are qualitatively robust.
- **Reruns if changed.** Dimer gliding, dimer diagnostics; single-head unaffected.

## 3. Anchor / motor compliance — fixed-anchor rigid limit vs the compliant S2 pivot; `kconv`/`kbind`

- **Why open.** `kconv=128`, `kbind=512 pN·nm/rad²` have **no in-code provenance** (mid-sweep values); the
  fixed-anchor fixture is an *infinitely stiff* idealization that "over-reports whole-crossbridge stiffness." The
  paper §6.2 explicitly perturbs anchor/lever/branch compliance — so this is a **declared structural study**,
  not tuning.
- **Plausible range + basis.** The sweep grids (`KCONV_3C={32…1000}`, `KBIND_3C`) bracket the choices; the
  fixed-anchor and explicit-S2 fixtures are the two supported compliance regimes.
- **Observables expected to change.** Recruitment, force heterogeneity, productive/resisting fractions, Vmax.
- **Minimum experiment.** The §6.2 compliance panel already planned (S2 stiffness, lever stiffness, anchor
  stiffness); report which observable each compliance moves.
- **Reruns if changed.** Step-size (compliance filters the stroke), gliding, force-clamp on the compliant
  fixture.

## 4. Surface height / gap geometry — MANCHOR_Z −0.05 µm, filament–head gap 2 nm, wall kz 2 pN/nm

- **Why open.** An idealized coverslip geometry; the head reach and the taut/bending balance depend on how high
  the filament floats. Minor relative to §1–§3, listed because §6 of the paper touches "surface height."
- **Plausible range + basis.** Gap of a few nm; wall stiffness a modeling choice.
- **Observables expected to change.** Recruitment (bindP4 reach), possibly ρ½.
- **Minimum experiment.** A small gap sweep folded into the §6.1 binding-gate perturbation.
- **Reruns if changed.** Gliding density sweeps.

---

## Also open — but NUMERICAL, not biophysical (tracked in `PARAMETER_FREEZE_CLASSIFICATION.md` §H)

- **Cross-bridge dt sub-step.** The explicit path (dt=2.5e-6) under-samples binding by ≤12 % (converged by
  dt/2); the lumped path (1e-5) is a hard ceiling. The open lever is a sub-step / implicit cross-bridge
  integrator. Close by implementing the sub-step and confirming dt/2 convergence. Does not change the
  *qualitative* cooperativity conclusions (saturation existence is dt-robust) but affects absolute Vmax.
- **GPU production governance — CLOSED (not open).** GPU is the normal canonical production path via a
  per-assay-class gate (`productionValidated(Backend)`): the validated forked-dimer / explicit-S2 gliding class
  runs device-resident **without the `-gpu-experimental` override**; unvalidated classes hard-fail; no silent
  fallback (`GPU_CANONICAL_PRODUCTION_SIGNOFF.md`). CPU is supplemental; the `runG4d` aggregate-equivalence suite
  is preserved for future kernel changes. Listed here only for completeness — it is not an open parameter.

---

## What is deliberately NOT on this list

The seven Lymn–Taylor rates, the five catch-slip parameters, the rigor-rupture parameters, kT, the FDT drag
fit, the binding gate (closed by the completed sensitivity study), the density conventions, and the velocity
estimator. These are FROZEN. **A gliding mismatch is not grounds to reopen any of them** — that is the no-tuning
firewall (`MODEL_FREEZE_DECISION.md` §Firewall).
