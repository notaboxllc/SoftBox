# CANONICAL FREEZE CLOSURE — SoftBox canonical myosin model v2

**Date 2026-07-22 · `MotorModel.CANON_VERSION = 2`.** The final closure report of the freeze-audit. It records
the four corrections/closures (ADP protocol, rigor-rupture promotion, GPU production governance, S2 length) and
answers the nine closure questions. Companions: `RIGOR_RUPTURE_PROMOTION_REGRESSION.md` (Part B),
`GPU_CANONICAL_PRODUCTION_SIGNOFF.md` (Part C), `S2_LENGTH_FREEZE_DECISION.md` (Part D),
`SM4_ADP_ATPFREE_PROTOCOL_FINDINGS.md` (ADP), `BRANCHEA_CALIBRATION_FINDINGS.md`,
`S2_MD_PROVENANCE_CORRECTION.md`. **No chemistry tuning, no gliding-target fitting, no broad reruns.**

---

## What changed vs the v1.0-draft / audit-rev-2 freeze

1. **ADP arm — corrected (no longer a blocker).** The ATP-free protocol correction (`atpOn = 0` validated as
   the ATP-free condition) restored the biological **ADP > rigor** lifetime ordering: ATP-free ADP is
   **1.17× rigor** (Guo & Guilford ~1.24×), peak lifetime **32.6 ms** (exp 31.7), peak force **6.7 pN**
   (exp 6.4); the planted ADP catch-slip params were recovered stage-resolved with the sequential model;
   **no ADP parameter was retuned.** The prior ATP-present "rigor > ADP" mismatch was a **protocol +
   observation-model artifact**, not a calibration failure. **The stale "ADP calibration required" blocker is
   removed everywhere.** The one honest residual: SoftBox represents the ATP-free ADP-conditioned lifetime as a
   **sequential** ADP→NONE release + rigor rupture, whereas the experiment is more naturally a **direct** rupture
   from an ADP-bound bond; the optional direct-ADP-rupture channel did **not** improve the result (outcompeted
   by the faster ADP→NONE transition) and is a declared future refinement, not required.

2. **Rigor rupture — PROMOTED to canonical default ON (v2).** All promotion checks pass
   (`RIGOR_RUPTURE_PROMOTION_REGRESSION.md`): rigor force-clamp law recovered (xCatch 1.5, peak ~7 pN);
   ATP-free ADP ordering/scale preserved; gliding impact negligible (+0.33 % ± 1.56 %, dimer ≤ 0.06 %);
   legacy-disable byte-identical (`-no-rupture` reproduces the pre-v2 default, SHA match); SM6 unchanged;
   SM4 selftest still PASS; new default health-clean (0 invalid/solver, rateCapWarn 0) CPU + GPU. Default
   `rupture_mode = 1`; `-no-rupture`/`-legacy-disable`/`-rupture-mode 0` restores the legacy default.

3. **GPU — canonical production, per-assay-class gate (no override).** The forked-dimer / explicit-S2 gliding
   assay class runs device-resident as the **normal canonical production path without `-gpu-experimental`**
   (`GPU_CANONICAL_PRODUCTION_SIGNOFF.md`); unvalidated classes (full experimental single-graph timestep,
   un-ported two-body GPU models) **hard-fail**; no silent fallback. The blanket `DEVICE_VALIDATED=false`
   refusal is replaced by `productionValidated(Backend)`.

4. **branchEA = 0.03 — resolved numerical compliance** (calibrated by stability/geometry, not gliding;
   split default eliminated; `BRANCHEA_CALIBRATION_FINDINGS.md`).

5. **S2 — EA + EI frozen (MD-constrained); exposed length conditionally frozen at L40** (Gate A;
   `S2_LENGTH_FREEZE_DECISION.md`).

---

## The nine closure answers

### 1. Is rigor rupture now default ON?
**YES.** Rigor-only mechanical rupture (`rupture_mode = 1`) is the canonical production default in the
single-head (`ExplicitCompleteMatHarness`), HMM-dimer (`ExplicitHmmDimerGlidingHarness`), and dimer GPU
production-cell (`ExplicitHmmDimerGpuValidation.runProductionCell`) paths. Explicit legacy-disable retained
(`-no-rupture`); the SM4 force-clamp apparatus keeps its default OFF but invokes the same pathway explicitly.
The all-strong-bound (mode 2) pathway is **not** promoted.

### 2. Is the ATP-free ADP result preserved?
**YES.** ADP > rigor ordering (1.17×), peak 32.6 ms / 6.7 pN, stage-resolved recovery of the frozen ADP and
rigor params — all preserved with **no ADP retuning** (regression Check 2).

### 3. Is GPU the normal production path without an experimental override?
**YES**, for the validated forked-dimer / explicit-S2 gliding assay classes (device-resident, no CPU fallback,
0 invalid/solver). Unvalidated classes hard-fail. CPU is supplemental.

### 4. Is branchEA fully reconciled?
**YES.** branchEA = 0.03 (12.6 pN/nm), confirmed from run metadata; single source of truth
`ExplicitHmmDimerGpuParams.STANDING_BRANCH_EA`; split default eliminated; classified NUMERICAL COMPLIANCE —
CALIBRATED BY CONSTRAINT. **Not reopened** (the 0.03 calibration evidence stands).

### 5. Are S2 EA and EI frozen?
**YES** — both CONDITIONALLY FROZEN — MD/LITERATURE CONSTRAINED (AMK-2008). Not reopened.

### 6. Is exposed S2 length frozen, conditionally frozen, or still open?
**CONDITIONALLY FROZEN — GEOMETRY:** canonical reference L = 40 nm; bounded 40–60 nm exposed-contour + boundary
uncertainty; declared-sensitivity variation only. The **beam / single-molecule** conclusions are *demonstrated*
L-robust (contour, stiffness scaling, buckling, stroke, tension/compression asymmetry). The **ensemble-gliding**
conclusions were **directly tested by the completed L60 sweep (2026-07-23) — Outcome 1 (quantitative rescaling
only)**: saturation, modest Vmax (+2.6 % single-head), recruitment, and near-hyperbolic response are
**demonstrated** robust; a modest ρ½ +20 % right-shift (mechanical accessibility) is demonstrated; the dimer
slowdown and dimer/single ratio are **supported** (dimer arm noise-limited); the cooperativity interpretation is
robust (`L40_VS_L60_GLIDING_COMPARISON.md`). L60 is a declared alternative boundary-condition sensitivity, **not**
a competing tuned baseline; L40 stays canonical (`S2_LENGTH_FREEZE_DECISION.md`).

### 7. What exact parameters remain open before cooperativity analysis?
A short **geometry / numerical** list — no chemistry:
- **Exposed S2 contour length + surface boundary** (40–60 nm; material frozen; canonical L40; the L60 gliding
  sensitivity is DONE — Outcome 1, robust; a tighter dimer L60 Vmax/ρ½ is an optional follow-up).
- **HMM branch/fork geometry** (branchLen, SPLAY, ALPHA, BREI) — coarse sensitivity confirms dimer conclusions.
- **Anchor / motor compliance** (kconv, kbind; fixed vs compliant) — the §6.2 compliance panel.
- **Surface height / gap** (small gap sweep folded into §6.1).
- **Numerical:** the cross-bridge dt sub-step (≤ 12 % binding under-sampling residual on the explicit path;
  affects absolute Vmax, not the qualitative saturation).
These are the paper's §6 causal-perturbation targets — declared structural studies, not tuning. The chemistry,
force-dependent release (incl. rigor rupture), binding gate, kT, FDT drag, and working stroke are FROZEN.

### 8. Can the final canonical baseline sweeps be treated as immutable?
**YES.** The completed baseline gliding sweeps ran at `rupture_mode = 0`; rigor ON ≡ OFF within the chaotic-seed
envelope (regression Check 3), so the mode-1 default is statistically indistinguishable from the baseline. The
sweeps ran device-resident with 0 invalid/solver at the enforced standing config (branchEA = 0.03). They are
immutable canonical numbers; no re-baseline is required by the v2 default flip.

### 9. Is the model ready for head-resolved cooperativity analysis?
**YES — for the frozen core, now.** The chemistry, the force-dependent release model (rigor rupture included),
the binding gate, the numerics/methodology, the density conventions, the calibrated-surrogate registry, GPU
production governance, and branchEA are frozen and validated at canon v2. The **structural layer is v2-provisional**
on the short open list (§7), which is exactly the paper's §6 perturbation set. The cooperativity analysis can
proceed on the frozen core **provided the no-tuning firewall holds** (`MODEL_FREEZE_DECISION.md`): the §6
perturbations vary only OPEN structural/numerical parameters and never touch the frozen chemistry to chase a
curve.

---

## No-tuning firewall (unchanged, binding)

No `FROZEN` / `CONDITIONALLY FROZEN` parameter may be adjusted to improve gliding speed, ρ½, dimer/single ratio,
cooperativity metrics, or agreement with any target curve. Any change requires new biological evidence, an
explicit reopen (version bump), a dependency-matrix rerun plan, a regenerated manifest, and an explicit
comparison — never a gliding-fit argument. The all-strong-bound rupture stays NONCANONICAL; branchEA stays 0.03
unless the 0.03 evidence is disproven; S2 EA/EI stay MD-constrained.

## Consistency

Code defaults (rupture_mode = 1 default, `-no-rupture` legacy; `productionValidated` gliding class;
`CANON_VERSION = 2`; `STANDING_BRANCH_EA = 0.03`), the production runners, the parameter manifest
(`CANONICAL_PARAMETER_MANIFEST.json`), and the freeze documents now describe the **same canonical model v2 with
no contradictions**.
