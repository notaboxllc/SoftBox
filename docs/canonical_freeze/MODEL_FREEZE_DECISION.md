# MODEL FREEZE DECISION — SoftBox canonical myosin model v2

**The decision document.** Answers the ten governance questions, states the no-tuning firewall, and records
that the model is declared version 2 canonical. Built from the evidence in
`CANONICAL_MOTOR_PARAMETER_INVENTORY.md` + `PARAMETER_FREEZE_CLASSIFICATION.md`, closed by
`CANONICAL_FREEZE_CLOSURE_FINDINGS.md`. Canonical schema version **2** (`MotorModel.CANON_VERSION`). Date
2026-07-22. No chemistry parameter changed, no gliding-target fitting; the canon-v2 code edits are the
rigor-rupture default promotion (Part B closure) + the per-assay-class GPU production gate (Part C closure) +
the earlier single-source-of-truth branchEA default.

---

## FREEZE-CLOSURE CORRECTIONS (2026-07-22, canon v2) — supersede the audit-rev-2 draft where they conflict

Four closures revise the audit-rev-2 draft. Full evidence in the closure companions
(`CANONICAL_FREEZE_CLOSURE_FINDINGS.md`, `RIGOR_RUPTURE_PROMOTION_REGRESSION.md`,
`GPU_CANONICAL_PRODUCTION_SIGNOFF.md`, `S2_LENGTH_FREEZE_DECISION.md`, `SM4_ADP_ATPFREE_PROTOCOL_FINDINGS.md`).

1. **Production path = GPU, normal canonical path (Part C).** The completed single-head, HMM-dimer, and
   rupture-sensitivity sweeps ran **GPU device-resident** (PTX-compiled, `device-resident=true`, `no CPU
   fallback`, 0 invalid/0 solver). Governance is **GPU-canonical / CPU-supplemental**, now realized as a
   **per-assay-class gate**: `ExplicitHmmDimerGpuParams.productionValidated(Backend)` marks the forked-dimer /
   explicit-S2 gliding class validated, so it runs device-resident **WITHOUT the `-gpu-experimental` override**;
   unvalidated classes (the full experimental single-graph timestep, un-ported two-body GPU models) **hard-fail**;
   never a silent fallback. The blanket `DEVICE_VALIDATED=false` refusal is superseded for the validated class
   (`GPU_CANONICAL_PRODUCTION_SIGNOFF.md`).
2. **branchEA = 0.03, confirmed + split eliminated (Parts B/C).** Run metadata (`"branchEA":0.0300000` in every
   production cell) confirms the sweeps used **0.03** (12.6 pN/nm); the "0.3" recollection was a *rejected*
   candidate. The CPU/GPU split default is removed — the harness now defaults to the single source of truth
   `STANDING_BRANCH_EA=0.03`. branchEA is reclassified **NUMERICAL COMPLIANCE — CALIBRATED BY CONSTRAINT**
   (stability/geometry, not gliding). No longer an open item (`BRANCHEA_CALIBRATION_FINDINGS.md`).
3. **S2 EI is MD-constrained, not open (Part D).** `kLatRef=0.01 pN/nm` is dead-center in the Adamovic–
   Mijailovich–Karplus 2008 band (0.008–0.012); Lp≈175 nm is in the MD-implied 140–210 nm band. **S2 EA and EI
   are both `CONDITIONALLY FROZEN — MD/LITERATURE CONSTRAINED` frozen material constants** — do not call EI
   unknown (`S2_MD_PROVENANCE_CORRECTION.md`).
4. **S2 length — Gate A, conditionally frozen at L40 (Part D).** L∈{10,20,40,60} were built; stroke is L-robust,
   axial/bending/buckling scale as MD predicts, and every main biological conclusion is L-robust across 40–60 nm.
   **L = 40 nm is the canonical reference geometry; the exposed contour length is CONDITIONALLY FROZEN;** L60 is
   retained as an optional §6.2 structural sensitivity — no new run required (`S2_LENGTH_FREEZE_DECISION.md`).
5. **ADP corrected + rigor rupture promoted (Part B).** The ATP-free ADP protocol (`atpOn=0`) restored the
   **ADP > rigor** ordering (1.17× rigor; peak 32.6 ms / 6.7 pN) with the frozen params — the prior mismatch was a
   protocol/observation-model artifact, **no ADP retuning**. With the guard thus satisfied, **rigor-only rupture
   (mode 1) was promoted to the canonical production default ON (canon v2)**; `-no-rupture` restores the
   byte-identical legacy default (`RIGOR_RUPTURE_PROMOTION_REGRESSION.md`, `SM4_ADP_ATPFREE_PROTOCOL_FINDINGS.md`).

**Four-way final status distinction (the audit's required output shape):**
- **Frozen material constants:** chemistry (7 LT rates + 5 catch-slip + kT), rigor-rupture params, S2 EA + EI,
  FDT drag, working stroke (PINNED).
- **Bounded geometry / boundary conditions (CONDITIONALLY FROZEN — GEOMETRY; declared-sensitivity variation
  only):** exposed S2 contour length (canonical L40; bounded 40–60 nm), HMM branch/fork geometry, anchor
  compliance, surface height. These are the paper's §6 declared perturbation targets, not open freeze holes.
- **Numerical compliance chosen by constraint:** branchEA = 0.03 (inextensible fork branch; stability/geometry),
  the cross-bridge dt sub-step (OPEN — NUMERICAL), integrator/RNG/precision.
- **Production-path governance:** GPU is the normal canonical path via a per-assay-class gate
  (`productionValidated`); the validated forked-dimer/explicit-S2 gliding class runs without the
  `-gpu-experimental` override; unvalidated classes hard-fail; no silent fallback; CPU supplemental.
- **Force-dependent release default:** rigor-only mechanical rupture (mode 1) is the canonical production default
  ON (canon v2); `-no-rupture` = byte-identical legacy; all-strong-bound (mode 2) OPTIONAL/NONCANONICAL.

---

## The no-tuning firewall (Part G) — binding governance rule

> **Parameters classified `FROZEN` or `CONDITIONALLY FROZEN` may not be adjusted to improve gliding speed,
> half-saturation density, dimer/single-head ratio, cooperativity metrics, or agreement with any target
> curve.**

This closes the failure mode the whole audit exists to prevent: reaching a target gliding number by quietly
editing a rate, a stiffness, or a gate. It matters specifically because the cooperativity paper's §6
perturbations (binding gate, S2 compliance) look like parameter changes — they are legitimate **only** because
they operate on the declared `OPEN` structural parameters, with the frozen core untouched.

**Any proposed change to a `FROZEN` or `CONDITIONALLY FROZEN` parameter MUST include, before it is applied:**
1. **New biological evidence** (a measurement or citation) — never a gliding-fit argument.
2. **An explicit declaration that the canonical model is being reopened** (this is a version bump, not an edit).
3. **A rerun plan for every dependent validation assay** — read the parameter's row in
   `PARAMETER_DEPENDENCY_MATRIX.csv`; every `D` (and material `I`) cell must be re-run and re-reported.
4. **A versioned parameter manifest** — bump `MotorModel.CANON_VERSION`, regenerate
   `CANONICAL_PARAMETER_MANIFEST.json`, and record the diff.
5. **A comparison against the previous canonical model** — the change reported explicitly, never absorbed into
   another parameter (the SM4 double-counting hazard: `docs/SM4_RIGOR_PATHWAY_DECISION_RECORD.md` §6).

**Corollary — the double-counting guard (satisfied at v2, kept as a standing rule).** Adding a second
force-dependent channel and re-fitting `xCatch`/`xSlip` against total lifetime would count load dependence
twice. The v2 rigor-rupture promotion **satisfied this guard without any refit**: the ADP branch was held at its
frozen values, the ATP-free protocol independently restored the correct ADP>rigor ordering, and rigor was
validated on its own arm — so no ADP parameter absorbed the new channel's force sensitivity
(`RIGOR_RUPTURE_PROMOTION_REGRESSION.md`). Any *future* second channel must likewise be fitted to the rigor and
ADP arms **jointly, with the ADP branch held at its validated values**, and reported explicitly.

**The `OPEN — BIOPHYSICAL` and `OPEN — NUMERICAL` parameters are the ONLY ones free to vary before freeze**, and
even they vary as *declared structural/convergence studies* (`OPEN_BIOPHYSICAL_PARAMETERS.md`), not as
convenience tuning.

---

## Part I — the ten answers

### 1. Is the chemistry frozen?
**YES.** The seven Lymn–Taylor rates (`nucParams[1..7]`) and the five catch-slip parameters (`kinParams[1..5]`)
plus `kT` are **FROZEN** — literature-transferred (Lymn & Taylor 1971; White & Taylor 1976; Siemankowski et al
1985; Howard 2001 T14-2; Guo & Guilford 2006), used unchanged across every assay, and the load-carrying ADP arm
was **independently validated by the SM4 blind force-clamp** (xCatch recovered blind). **Not available for
gliding tuning.** Documented caveats that do not reopen them: the rate set is a multi-temperature stitch (kT
25 °C, cycle ~15–20 °C — accepted, temperature is not the lever); `atpOn=2e4` and `offPi=0` are order-of-
magnitude/assumed but still frozen validation inputs.

### 2. Is the force-dependent release model frozen? *(rigor rupture PROMOTED — canon v2, Part B closure)*
**YES.** The canonical channel — the ADP→NONE catch-slip `g(F)` on base 1000/s — is **FROZEN** and blind-validated.
The **rigor mechanical-rupture parameters are FROZEN** (Guo & Guilford Table-2, blind-recovered STRONG: xCatch
1.50→1.51, peak 6.99→7.09 pN). **Rigor-only mechanical rupture (mode 1) is now the CANONICAL PRODUCTION DEFAULT
(ON)** — promoted at canon v2 after the `RIGOR_RUPTURE_PROMOTION_REGRESSION` passed every check. An explicit
legacy-disable is retained (`-no-rupture` / `-legacy-disable` / `-rupture-mode 0`, byte-identical to the pre-v2
default). The old "blocked on the ADP-arm calibration / double-counting guard" gate is **removed**: the ATP-free
ADP protocol (`atpOn=0` validated as the ATP-free condition; `SM4_ADP_ATPFREE_PROTOCOL_FINDINGS.md`) restored the
correct **ADP > rigor** lifetime ordering — ATP-free ADP is **1.17× rigor** (Guo & Guilford ~1.24×), peak
lifetime **32.6 ms** (exp 31.7), peak force **6.7 pN** (exp 6.4), with the planted ADP catch-slip params recovered
stage-resolved by the sequential model and **no ADP parameter retuned**. The prior ATP-present "rigor > ADP"
mismatch was a **protocol + observation-model artifact**, not a calibration failure. So the double-counting guard
is **satisfied without recalibration**: gliding impact is **negligible** (+0.33%±1.56%, |t|=0.21) and the ADP arm
keeps its frozen values. The remaining ADP limitation is stated accurately: SoftBox represents the ATP-free
ADP-conditioned lifetime as a **sequential** ADP→NONE release + rigor rupture, whereas the experiment is more
naturally a **direct** rupture from an ADP-bound bond; the optional direct-ADP-rupture channel did **not** improve
the result (outcompeted by the faster ADP→NONE transition) and is a declared future refinement, not required.
**All-strong-bound (direct ADP, mode 2) rupture stays OPTIONAL/NONCANONICAL** (rejected; worsened ADP, changed
gliding). The HMM strain-rupture failsafe is a default-OFF numerical failsafe.

### 3. Is the crossbridge stiffness frozen, conditionally frozen, or open?
**CONDITIONALLY FROZEN — GENERIC BUT ACCEPTED.** `myoSpring = 1 pN/nm` is **not** a direct skeletal measurement
— it is a v1 gliding-behavior match (unpublished conference-poster reference) that happens to sit inside the
literature bracket 0.3–2 pN/nm (Veigel; Kaya & Higuchi ≈1.8). The sensitivity sweep shows it is **sharply
constrained by emergent gliding** (velocity non-monotonic, peaked at 1.0; avgBound correct only at 1.0), so
softening/stiffening it to hit a gliding number is forbidden. **Any change is a structural mechanics study and
must rerun the step-size and force-clamp single-molecule validations.**

### 4. Is the 40 nm S2 region justified or still open? *(CONDITIONALLY FROZEN at L40; L60 tested — Outcome 1)*
**The S2 MATERIAL is frozen (MD-anchored); the EXPOSED LENGTH is CONDITIONALLY FROZEN at L40.** EA **and EI**
are both `CONDITIONALLY FROZEN — MD/LITERATURE CONSTRAINED` (Adamovic–Mijailovich–Karplus 2008: axial 70 pN/nm
mid-band, lateral 0.01 pN/nm dead-center, Lp≈175 nm in-band) — **EI is not unknown**
(`S2_MD_PROVENANCE_CORRECTION.md`). The length L∈{10,20,40,60} was built and characterized; **stroke is L-robust,
axial/bending/buckling scale exactly as MD predicts** at the beam/single-molecule level. **L = 40 nm is the
canonical reference geometry** (a partially-supported gliding surface); the free/exposed contour length + surface
emergence boundary (40–60 nm) is a bounded **geometry** question, conditionally frozen, declared-sensitivity
variation only. **The L60 ensemble gliding sensitivity is COMPLETE (2026-07-23; single-head GPU full grid + dimer
reduced CPU) — Outcome 1 (quantitative rescaling only): the gliding phenotype is robust to exposed S2 length**
(modest Vmax +2.6 %, ρ½ +20 % via mechanical accessibility, saturation + dimer-slowdown preserved;
`L40_VS_L60_GLIDING_COMPARISON.md`). No material-stiffness study is needed; L40 stays canonical.

### 5. Is the binding gate sufficiently closed?
**YES.** The completed sensitivity study (`docs/matsoa/EXPLICIT_BINDING_RESOLUTION_FINDINGS.md`,
`EXPLICIT_BINDING_REACH_SENSITIVITY_FINDINGS.md`) shows the angular gates (25/25/20°) sit in a **±7 % flat
plateau** (leave-one-out sole-failure counts 0/1/4 vs preload 4679, margin 5464), distance is a **no-op**,
preload (2 pN) is at its **recruitment optimum with best onset quality**, and the in-segment margin was resolved
to the **segmentation-invariant machine-ε canonical limit**. No gate move changes the existence of hyperbolic
saturation. The gate is **CONDITIONALLY FROZEN / sufficiently closed**; the §6.1 "alter the binding gate"
perturbation is a declared structural study, not re-tuning. **No new sweep is warranted.**

### 6. Are single-head and dimer harnesses using the same canonical chemistry?
**YES — bit-identically.** Both call `MotorStore.setNucParams(dt)` + `setKinParams(0.006,−0.4,dt)` and dispatch
to the *same* `cycleLymnTaylor*` kernels over the *same* `nucParams`/`kinParams` arrays; no `MotorModel`
(fixed-anchor / explicit-s2-l40 / calibrated-s2-l40) alters any rate. The chemistry is shared code.

### 7. Are GPU and CPU production paths parameter-identical? *(closed — Part C)*
**YES by construction; GPU is the normal canonical production path.** Same parameter arrays; the counter-based
Wang-hash RNG is bit-identical CPU↔GPU. The completed production sweeps ran **GPU device-resident** (proven: PTX
warm-compile, `device-resident=true`, `no CPU fallback`, 0 invalid/0 solver) at the enforced standing config.
Governance is realized as a **per-assay-class gate**: `productionValidated(Backend)` marks the forked-dimer /
explicit-S2 gliding class validated, so it runs device-resident as the **normal production path WITHOUT the
`-gpu-experimental` override**; the full experimental single-graph timestep + un-ported two-body GPU models
**hard-fail**; no silent fallback. The evidence base is the deterministic fixtures V1–V8, moving-actin, binding +
chemistry CPU↔GPU bit-identity, and 0 invalid/solver across all production cells (aggregate-statistical
equivalence, not bit-identical trajectories — `GPU_CANONICAL_PRODUCTION_SIGNOFF.md`). **The `branchEA` split
default is RESOLVED** (single source of truth `STANDING_BRANCH_EA=0.03`). No parameter inconsistency remains.

### 8. Which exact parameters remain open before cooperativity analysis? *(shortened — closure)*
A short **geometry/numerical** list (`OPEN_BIOPHYSICAL_PARAMETERS.md`), after closure resolved the chemistry,
the release model (rigor rupture promoted), EI (MD-constrained), branchEA (0.03), the S2 length (conditionally
frozen at L40), and the GPU production gate: **(1)** exposed S2 contour length + surface boundary condition
(40–60 nm geometry, material frozen); **(2)** HMM branch/fork geometry (branchLen, SPLAY, ALPHA, BREI);
**(3)** anchor/motor compliance (kconv/kbind; fixed vs compliant); **(4)** surface height/gap. Plus one numerical
item: the **cross-bridge dt sub-step** (≤12% binding under-sampling residual; affects absolute Vmax, not the
qualitative saturation). **No chemistry, no release-model, and no GPU-governance items remain open.**

### 9. What remaining paper-stage studies are left?
These are **causal / robustness studies for the cooperativity paper's §6, NOT prerequisites for the frozen
chemical core** — the chemistry, force-dependent release (rigor rupture default ON), binding gate, crossbridge
stiffness, branchEA, EA/EI, and GPU governance are all closed at canon v2. What remains varies only OPEN
structural/numerical parameters:
1. **L60 exposed-S2 gliding sensitivity — DONE (2026-07-23; Outcome 1).** The declared sensitivity vs the frozen
   L40 baseline (single-head GPU full grid + dimer reduced CPU) found the gliding phenotype **robust to exposed S2
   length** — modest Vmax (+2.6 % single-head), ρ½ +20 % (mechanical accessibility), saturation + dimer-slowdown
   preserved (`L40_VS_L60_GLIDING_COMPARISON.md`). L40 stays canonical; L60 is a supporting §6.2 sensitivity
   result. (A tighter dimer L60 Vmax/ρ½ — substep / NDOF=25 GPU kernel + more seeds — is an optional follow-up.)
2. **HMM branch/fork geometry sensitivity** — a coarse branchLen/SPLAY sweep confirming the dimer conclusions.
3. **Converter / binding / anchor compliance panel** — the §6.2 compliance perturbations (S2 / lever / anchor
   stiffness).
4. **Surface height / gap bracket** — a small gap sweep folded into §6.1.
5. **Cross-bridge substep convergence** — implement the sub-step and confirm dt/2 convergence (the one open
   numerical item; affects absolute Vmax, not the qualitative saturation).

None of these reopens the frozen core; each is a declared perturbation of an OPEN structural/numerical parameter.
(EI, branchEA, GPU promotion, and rigor-rupture promotion are all **resolved** at canon v2 — not listed here.)

### 10. Can the current model be declared version 2 canonical?
**YES — for the frozen core, now.** The chemistry, the force-dependent release model (rigor rupture now the
canonical default ON), the binding gate, the numerics/methodology, the density conventions, the
calibrated-surrogate registry, the GPU production governance, and branchEA (`MotorModel.CANON_VERSION=2`) are
frozen and validated. **Declare CANONICAL v2** for this core. The **structural layer carries a short list of
declared §6 sensitivity studies** (the exposed S2 length — conditionally frozen at L40, branch/anchor geometry,
surface gap, the dt-substep) — all structural/numerical, none chemistry. Those are exactly the cooperativity
paper's §6 causal-perturbation targets:
the paper turns the open list into a result, not a liability. The cooperativity analysis can proceed on the
frozen core **provided the firewall holds**: the §6 perturbations vary only OPEN structural/numerical parameters
and never touch the frozen chemistry to chase a curve.

---

## Summary verdict

| Question | Verdict |
|---|---|
| Chemistry | **FROZEN** (validated; not a gliding knob) |
| Force-dependent release | **FROZEN**; rigor-rupture params frozen, pathway **PROMOTED to canonical default ON (canon v2)**; `-no-rupture` = byte-identical legacy; all-strong-bound OPTIONAL |
| Cross-bridge stiffness | **CONDITIONALLY FROZEN — generic but accepted** |
| S2 material (EA + EI) | **CONDITIONALLY FROZEN — MD/LITERATURE CONSTRAINED** (AMK-2008; EI not unknown) |
| S2 exposed length / boundary | **CONDITIONALLY FROZEN — GEOMETRY** (canonical value L40; bounded exposed-contour + surface-boundary uncertainty 40–60 nm; declared-sensitivity variation only; L60 is a declared alternative boundary-condition sensitivity, not a competing tuned baseline) |
| branchEA | **NUMERICAL COMPLIANCE — CALIBRATED BY CONSTRAINT = 0.03** (confirmed from run metadata; split eliminated) |
| Binding gate | **Sufficiently closed** (conditionally frozen) |
| Single-head ≡ dimer chemistry | **YES** (bit-identical) |
| Production path | **GPU normal canonical path via per-assay-class validation** (no override for the validated gliding class; unvalidated classes hard-fail; no silent fallback); CPU supplemental |
| v2 canonical? | **YES for the frozen core** (canon v2); remaining §6 sensitivity studies = S2 exposed-length (conditionally frozen at L40) + branch/anchor geometry + dt-substep (all structural/numerical) |

**Desired outcome achieved:** freeze what is genuinely constrained (chemistry, release model, binding gate,
numerics), isolate a very small set of defensible structural questions (chiefly the explicit S2 length), and
install a firewall so no frozen parameter is silently tuned against gliding.
