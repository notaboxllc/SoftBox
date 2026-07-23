# PARAMETER FREEZE CLASSIFICATION — SoftBox canonical myosin model

**Companion to `CANONICAL_MOTOR_PARAMETER_INVENTORY.md`.** Every active parameter, its freeze status, and an
explicit rationale. Canonical schema version **2** (freeze-closure).

**Status keys:** `FROZEN` (independently constrained / validated; changing it reopens the relevant validation
section) · `CONDITIONALLY FROZEN` (retained as canonical; may change only on stronger evidence; not a
convenience knob) · `MD/LITERATURE CONSTRAINED` (a CONDITIONALLY-FROZEN material constant with MD/literature
provenance) · `NUMERICAL COMPLIANCE — CALIBRATED BY CONSTRAINT` (a numerical compliance selected by
stability/geometry criteria, not by data fitting) · `OPEN — BIOPHYSICAL` / `OPEN — GEOMETRY` (weakly-constrained
structure/boundary; alternatives may be tested before freeze) · `OPEN — NUMERICAL` (needs convergence/
implementation validation) · `OPTIONAL / NONCANONICAL` (sensitivity-only, default OFF) · `DEPRECATED` · `UNKNOWN
PROVENANCE`.

> **FREEZE-CLOSURE CORRECTIONS (2026-07-22, canon v2), superseding the pre-v2 draft:** **S2 EI** →
> `CONDITIONALLY FROZEN — MD/LITERATURE CONSTRAINED` (AMK-2008, not open/unknown; §F). **branchEA** →
> `NUMERICAL COMPLIANCE — CALIBRATED BY CONSTRAINT` = 0.03, confirmed from run metadata, split default eliminated
> (§G→resolved). **Rigor-only rupture (mode 1)** → **PROMOTED to the CANONICAL PRODUCTION DEFAULT ON** (canon v2;
> `-no-rupture` = byte-identical legacy) (§C). **Production governance** → GPU is the normal canonical production
> path via a per-assay-class gate (no `-gpu-experimental` override for the validated gliding class; unvalidated
> classes hard-fail; no silent fallback) (§H). **S2 exposed length** → CONDITIONALLY FROZEN at L40; L60 sensitivity COMPLETED (Outcome 1).

---

## A. FROZEN — chemistry (not available for gliding tuning)

**The seven Lymn–Taylor rates** (`nucParams[1..7]`: atpOn 2e4, on/offATP 100, onPi 1e4, on/offADP 1e3; offPi 0)
and the **five catch-slip parameters** (`kinParams[1..5]`: aCatch 0.92, aSlip 0.08, xCatch 2.5 nm, xSlip 0.4 nm,
kT). Provenance class **B** (literature-derived/transferred: Lymn & Taylor 1971; White & Taylor 1976;
Siemankowski, Wiseman & White 1985; Howard 2001 Table 14-2; Guo & Guilford 2006). `kT` is class A/PINNED
(25 °C explicit).

**Rationale.** These are imported from the biophysical literature and used **unchanged** across the step-size,
force-clamp, single-head gliding, and dimer gliding assays — no gliding-specific calibration was introduced
(the paper's central methods claim). The ADP arm carries the model's entire load dependence and was
**independently validated by the SM4 blind force-clamp** (planted xCatch recovered within z=0.70 on the rigor
arm; xCatch 2.50 nm exactly on the compliant ADP fixture); no parameter was tuned. **Firewall statement: a
gliding mismatch CANNOT be corrected by altering any of these rates after the fact.** Any change requires new
biological evidence and reruns the single-molecule validations (Part D1/D6 of the task).

**Two honest sub-notes carried inside the FROZEN set (do not use as excuses to tune):**
- `offPi = 0` (off-fil Pi release disabled) and the round `atpOn = 2e4` are ASSUMED/order-of-magnitude
  (class D / weakly-sourced) — still frozen as validation inputs, not knobs.
- The rate set is a documented **multi-temperature stitch** (kT 25 °C, cycle ~15–20 °C). Per
  `docs/MOTOR_PARAMETER_PROVENANCE_25C.md` this is accepted as within model coarseness; temperature is **not**
  a lever (the velocity-limiting ADP release is already ~25 °C-equivalent). A coherent-25 °C re-derivation is
  possible only per-transition from primary Q₁₀ sources — a documentation chore, not a freeze blocker.

## B. FROZEN — force-dependent release model (the ADP catch-slip g(F))

The ADP→NONE load-modulation `g(F)=aCatch·e^(−F·xCatch/kT)+aSlip·e^(+F·xSlip/kT)` on base `onADP=1000/s` **is**
the canonical force-dependent release. FROZEN per Part D6; blind-validated (Part I, Q2). It governs ADP
**release**, not a direct bond rupture (a documented structural limitation — the ADP-conditioned detachment is
a sequential ADP-release→rigor process, not a single direct ADP-bond rupture; paper outline §3.3).

## C. FROZEN — rigor mechanical-rupture PARAMETERS (pathway PROMOTED to canonical default ON, canon v2)

`rigorParams`: k0=140/s, aCatch=0.9071, xCatch=1.5 nm, aSlip=0.0929, xSlip=0.5 nm → **Guo & Guilford 2006
Table-2 rigor** (class B). **The parameters are FROZEN** (validated blind: planted xCatch 1.50 → 1.51,
peak 6.99 → 7.09 pN; ΔAIC +7240 vs Bell) — they are **not available for gliding tuning** (Part D5).

**Rigor governance CLOSED (Part B, canon v2).** Rigor-only mechanical rupture (`rupture_mode=1`) is the
**CANONICAL PRODUCTION DEFAULT ON** in the single-head / HMM-dimer / GPU-production paths; an explicit
legacy-disable is retained (`-no-rupture` / `-legacy-disable` / `-rupture-mode 0`, byte-identical to the pre-v2
default); the SM4 force-clamp apparatus keeps its default OFF but invokes the same `cycleLymnTaylorRigor`
pathway explicitly. Promoted after the `RIGOR_RUPTURE_PROMOTION_REGRESSION` passed every check: rigor force-clamp
law recovered (xCatch 1.5, peak ~7 pN); ATP-free ADP ordering preserved; gliding impact **negligible**
(+0.33%±1.56%, |t|=0.21, `docs/matsoa/RIGOR_RUPTURE_AND_GLIDING_IMPACT_FINDINGS.md`); legacy-disable
byte-identical; SM6 unchanged; 0 invalid/solver. **The old ADP-calibration blocker is removed:** the ATP-free
ADP protocol (`atpOn=0`) restored the correct **ADP > rigor** ordering — ATP-free ADP is 1.17× rigor (Guo &
Guilford ~1.24×), peak 32.6 ms (exp 31.7) / 6.7 pN (exp 6.4), with the planted ADP catch-slip recovered
stage-resolved by the sequential model and **no ADP parameter retuned**; the prior ATP-present mismatch was a
protocol + observation-model artifact, **not a calibration failure**, so the double-counting guard is
**satisfied without recalibration**. The remaining ADP limitation is that SoftBox reaches ATP-free ADP
detachment via a **sequential** ADP→NONE release + rigor rupture, whereas the experiment is more naturally a
**direct** ADP-bond rupture; the optional direct-ADP-rupture channel did not improve the result (outcompeted by
the faster ADP→NONE transition) and is a future refinement, not required
(`RIGOR_RUPTURE_PROMOTION_REGRESSION.md`, `SM4_ADP_ATPFREE_PROTOCOL_FINDINGS.md`).

## D. FROZEN — numerics / methodology

- RNG (counter-based Wang hash, bit-identical CPU↔GPU), competing-hazard single-uniform partition, per-step
  `pDtCap=0.2` flag (never clip), GPU float32, the linearly-implicit cross-bridge / implicit beam / HMM Newton
  integrator, the whole-window LS-slope velocity estimator, warm-up conventions, invalid/solverFail voiding.
  Class E. **FROZEN — NUMERICAL:** revisit only if the integrator, force law, or spatial resolution changes
  (Part D8). The rod-drag FDT fit (aParallel/aOrthog/aTurning) and the Brownian sqrt(2kT/dt) amplitude are
  FDT-validated (class A/B) — FROZEN.
- Filament radius `FIL_R`, `kT`: FROZEN (defined constants).
- **Explicit-path dt = 2.5e-6** is FROZEN — NUMERICAL (bending-stability-gated, abort-checked), with a
  documented ≤12 % binding under-sampling residual carried as OPEN — NUMERICAL (§F).

## E. FROZEN — binding ownership / discretization-invariant choices

`bindP[10]` (in-segment margin at machine-ε) and `bindP[12]=0` (canonical half-open ownership) — FROZEN as the
**segmentation-invariant** choice (the completed reach study drove the rollout, 2026-07-18). Density convention
(heads/µm² single-head, dimers/µm² for the HMM — heads=2×), anchor uniform-random distribution, filament-free
z-confined boundary condition, whole-window estimator: FROZEN conventions/methodology.

## F. CONDITIONALLY FROZEN — generic-but-accepted, or well-constrained without a direct measurement

| parameter | value | why conditionally frozen |
|---|---|---|
| **`myoSpring` F8 cross-bridge stiffness** | 1 pN/nm | Not a direct skeletal measurement — a v1 gliding-behavior match (unpublished conference-poster reference), within the literature bracket 0.3–2 pN/nm (Veigel; Kaya & Higuchi ≈1.8). Sharply constrained by emergent gliding (velocity peaked at 1.0; avgBound only right at 1.0). **CONDITIONALLY FROZEN — GENERIC BUT ACCEPTED (Part D3): not a gliding knob; any change = a structural mechanics study + rerun step-size and force-clamp validations.** |
| **S2 `EA` (axial) / `kAxRef`** — *MD/LITERATURE CONSTRAINED* | 4.2e-9 N / 70 pN/nm | AMK-2008 axial 60–80 pN/nm; SoftBox 70 mid-band. A frozen material constant; change only on stronger MD/experimental evidence, never to tune gliding. |
| **S2 `EI` (bending) / `kLatRef`** — *MD/LITERATURE CONSTRAINED* (corrected; was OPEN in the pre-v2 draft) | 7.2e-28 N·m² / 0.01 pN/nm | AMK-2008 lateral **0.008–0.012 pN/nm; SoftBox 0.01 dead-center**; Lp≈175 nm in the MD-implied 140–210 nm band. **Not unknown/unsupported** (`S2_MD_PROVENANCE_CORRECTION.md`). A §6.2 EI perturbation is a sensitivity study *within the MD band*, not a provenance reopening. |
| **`branchEA` = 0.03 (12.6 pN/nm)** — *NUMERICAL COMPLIANCE — CALIBRATED BY CONSTRAINT* | 0.03 × 420 pN/nm | Confirmed from run metadata (every production cell `"branchEA":0.03`); split default eliminated (single source of truth `STANDING_BRANCH_EA`). Represents an ~inextensible fork branch; selected by stability/geometry (lowest dt-stable = stiffest that removes excursions), **not** gliding (velocity flat across 0.01–1.0). `BRANCHEA_CALIBRATION_FINDINGS.md`. |
| calibrated surrogate params (kAxTension 105, kTr 0.026, kFeTr, rMaxOnset, smoothings, kFloor) | — | Class C fit outputs of Exp 4I; changing requires a re-fit to the explicit reference, never a direct edit. L40≠L60 caveat attached. |
| lever length 8 nm, head ellipsoid, converter arm 7.6 nm, PHI_PRE 30°, ±30° swing | — | geometric (class F); the *emergent* stroke is a PINNED validation metric, so the geometry is held unless the step-size validation is reopened. |
| `kconv` 128, `kbind` 512 pN·nm/rad² | — | no in-code provenance (mid-sweep); eligible for the declared §6.2 compliance perturbation (a structural study), not for gliding tuning. |
| persistence length 15 µm, chain PAIRS (0.5/0.1/0.265), BTrans/BRotCoeff, MANCHOR_Z, gap 2 nm, EXCL 5.4 nm, refractory 1e-5 s / block-prob 1.0 | — | v1-inherited / dt-robust damping / geometric; held as canonical, not convenience knobs. |
| **`aeta` = 0.1 Pa·s** | ≈100× water | a **numerical** medium choice; absolute glide µm/s is drag-dependent (the faithful comparison is the drag-independent V₀=step×rate). CONDITIONALLY FROZEN — NUMERICAL; carry the ~100× caveat in any absolute-velocity statement. |
| ATP-scaling mechanism (pseudo-first-order) | — | a declared model interface; the default ATP condition per assay is frozen with its assay. |
| deterministic geometric bind gate; bindP[1..8] angular/preload/energy/steric | 25/25/20°, 2 pN, 15 kT, 3 nm, 2.25 nm | heuristics ("NOT tuned to a canonical rate"), but the **completed sensitivity study** shows the angular gates in a ±7 % flat plateau, distance a no-op, preload at its recruitment optimum. CONDITIONALLY FROZEN — sufficiently closed; the §6.1 perturbation is a declared study. |

## G. BOUNDED GEOMETRY / BOUNDARY — CONDITIONALLY FROZEN — GEOMETRY (declared-sensitivity variation only; see `OPEN_BIOPHYSICAL_PARAMETERS.md`)

*(EI and branchEA were removed from this list in audit rev 2 — EI is MD-constrained/frozen (§F); branchEA is
resolved to 0.03 (§F). S2 is the exposed-length/boundary question, not a material question. These are bounded
geometry uncertainties, conditionally frozen at their canonical values and varied only as declared §6
sensitivity studies — not open freeze holes.)*

- **Exposed/free S2 contour length + surface boundary condition — CONDITIONALLY FROZEN — GEOMETRY, canonical
  value L40.** The S2 *material* (EA+EI) is frozen; the bounded uncertainty is how much S2 is exposed above the
  coverslip (L40 partially-supported vs L60 exposed, 40–60 nm). **L = 40 nm is the canonical reference geometry;**
  permitted variation is **declared structural sensitivity only** (changing L to improve gliding agreement and
  then silently replacing the canonical baseline is NOT permitted). **Demonstrated L-robust across L**
  (beam/single-molecule): contour conservation, numerical stability, axial/bending/buckling scaling, working
  stroke, tension/compression asymmetry. **Ensemble tested by the completed L60 sweep (2026-07-23) — Outcome 1
  (quantitative rescaling only):** hyperbolic saturation, modest Vmax (+2.6 % single-head), recruitment beyond
  saturation, near-hyperbolic (n≈1) = **DEMONSTRATED** robust; ρ½ +20 % right-shift (mechanical accessibility)
  demonstrated; dimer slowdown + dimer/single ratio = **QUALITATIVELY SUPPORTED** (the reduced-CPU dimer arm is a
  qualitative stress test — dimer Vmax/ρ½ UNRESOLVED / not freeze-grade, with physically-inadmissible L60 branch
  excursions; fitted ratios are artifacts) (`L40_VS_L60_GLIDING_COMPARISON.md`). L40 stays canonical; L60 is a
  supporting §6.2 sensitivity. A geometry question, not a stiffness question.
- **HMM branch/fork geometry** (branchLen 10, SPLAY 16°, ALPHA 10°, BREI 0.25) — fork-relaxation choices, no
  citation.
- **Anchor / motor compliance** — fixed-anchor rigid limit vs the compliant S2 pivot; `kconv`/`kbind` (also
  conditionally frozen) are the §6.2 compliance perturbation targets.
- **Surface height / gap geometry** (MANCHOR_Z, 2 nm gap, kz=2 pN/nm) — idealized coverslip; minor open.

## H. OPEN — NUMERICAL + production-path governance

- **Cross-bridge dt sub-step.** Explicit-path dt=2.5e-6 leaves a ≤12 % binding under-sampling residual
  (converged by dt/2); the lumped gliding dt=1e-5 is a hard ceiling. The open lever is a sub-step / implicit
  cross-bridge integrator (a future task).
- **Production path = GPU (governance, closed — CLOSURE, not open).** GPU is the **normal canonical production
  path** via a per-assay-class gate (`ExplicitHmmDimerGpuParams.productionValidated(Backend)`): the validated
  forked-dimer / explicit-S2 gliding class runs device-resident **WITHOUT the `-gpu-experimental` override**; the
  full experimental single-graph timestep + un-ported two-body GPU models **hard-fail**; no silent fallback. CPU
  is **supplemental** (small assays, unit tests, sanity checks, targeted equivalence; the `runG4d` equivalence
  suite is preserved for future kernel changes). The completed sweeps ran GPU device-resident (PTX-compiled,
  `no CPU fallback`, 0 invalid/solver); GPU and CPU are parameter-identical by construction (same arrays; RNG
  bit-identical). Evidence: V1–V8 fixtures + moving-actin + binding + chemistry bit-identical CPU↔GPU
  (aggregate-statistical equivalence, not bit-identical trajectories) — `GPU_CANONICAL_PRODUCTION_SIGNOFF.md`.
  Listed under §H only because it is not a physics parameter; it is a closed governance item, not open.

## I. OPTIONAL / NONCANONICAL (default OFF; must not influence canonical conclusions)

- **All-strong-bound (direct ADP) rupture, mode 2** (`adpRuptureParams`, k0=191, G&G Table-2 ADP). A rejected
  structural alternative — worsened ADP agreement, changed gliding velocity. Sensitivity/supplement only.
- **HMM-dimer strain-rupture failsafe R1–R5** (`RUPTURE_MODE≠0`, thresholds 20/10/1.0/40/50). Numerical-stability
  failsafe; threshold promotion pending a compute-heavy sweep.
- **Azimuthal binding gate + azimuthal falloff** (lumped path, default OFF) — both insufficient to bend the
  velocity–density curve; experimental.
- **12 pN break-force cap** (`-faithfulrelease`, default OFF on the LT path), `-faithfulrefractory` (block-prob
  0.31), `-atprecharge`/`-tauavg` and the entire legacy sphere-head noise-correction family
  (`docs/CANONICAL_MANIFEST.md` §4) — diagnostics / superseded.

## J. DEPRECATED

- **`kOff` = 100/s** (`kinParams[0]`) — dormant on the canonical LT path (only the legacy `catchSlipRelease*`
  kernels read it); the LT release base is `onADP=1000/s`. Kept for the legacy path; not canonical.
- **`LEGACY_MARGIN` = 50 nm** segment-end exclusion — regression-only; **UNKNOWN PROVENANCE** ("no derivation,"
  masked an ownership-handoff gap), superseded by half-open ownership.
- **`myoColTol` = 6 nm** in the explicit scope — written but not read by the explicit gate ("NEUTRAL
  PLACEHOLDER"); a live parameter only on the reference lumped path.

## K. UNKNOWN PROVENANCE (active or referenced parameters whose origin is not established in code/docs)

These do not block the *frozen* set but are recorded so "legacy" is never accepted as sufficient provenance:
1. **`kconv=128`, `kbind=512`** — chosen as mid-sweep values; no measurement or derivation cited.
2. ~~`kLatRef=0.01 pN/nm` / S2 `EI`~~ — **RESOLVED (audit rev2): MD/literature-constrained** (AMK-2008 lateral
   0.008–0.012 pN/nm; SoftBox 0.01 dead-center). No longer UNKNOWN — see §F and `S2_MD_PROVENANCE_CORRECTION.md`.
3. **HMM `ALPHA/SPLAY/branchLen/BREI`** — geometry/stability choices, no external citation. (**`branchEA` is NOT
   here — RESOLVED** to 0.03, NUMERICAL COMPLIANCE — CALIBRATED BY CONSTRAINT by geometry/stability, §F /
   `BRANCHEA_CALIBRATION_FINDINGS.md`; not an unknown-provenance item.)
4. **bind-gate tolerances (25/25/20°, 2 pN, 15 kT, 3 nm)** — "named; NOT tuned to a canonical rate"; heuristic
   (the sensitivity study shows the model is insensitive to the angular ones and preload sits at an optimum,
   but none has a first-principles derivation).
5. **HMM R-mode thresholds (20/10/1.0/40/50)** and **pDtCap 0.2 / faithful block-prob 0.31** — internal
   guard/stability constants.
6. **`atpOn=2e4`, cycle rates 100/1e4/1e3, offPi=0** — cited only to v1 `Env.java:836-855` (⇒ Howard 2001
   Table-2); the specific per-rate literature mapping beyond the textbook compilation is not shown in code.

**None of the UNKNOWN-PROVENANCE items is a gliding knob.** They are either frozen validation inputs (6),
numerical guards (5), sensitivity/compliance parameters slated for the declared §6.2 study (1,3), or the
OPEN — BIOPHYSICAL structural questions (2,3). Establishing their provenance (or bracketing them in the S2 /
compliance study) is a prerequisite for the full v2 freeze of the *structural* layer, not of the chemistry
(which, with the force-dependent release model, is frozen at canon v2).
