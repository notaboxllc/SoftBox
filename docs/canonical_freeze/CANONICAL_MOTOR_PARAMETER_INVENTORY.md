# CANONICAL MOTOR PARAMETER INVENTORY — SoftBox myosin system

**Purpose.** A complete evidence inventory and freeze audit of every active parameter of the **current
canonical myosin model**, produced as the governance gate before the mechanical-cooperativity analysis
(`docs/MECHANICAL_COOPERATIVITY_PAPER_OUTLINE.md`). It exists to make it **impossible to silently alter a
parameter to improve gliding agreement**: §6 of the cooperativity paper perturbs the binding gate and S2
compliance, and those perturbations are only legitimate structural studies if the frozen core is locked
first.

**Status.** Freeze-CLOSURE audit (canon v2). Chemistry inventory unchanged; the canon-v2 code changes are the
rigor-rupture default promotion + the per-assay-class GPU production gate (see the closure companions).
`BoA-v1ref` byte-clean. Date 2026-07-22. Branch `gpu-mat-bottlenecks-explicit-singlehead`.
Canonical schema version **2** (`MotorModel.CANON_VERSION = 2`).

**Companion deliverables (all in `docs/canonical_freeze/`):**
`PARAMETER_PROVENANCE_TABLE.csv` · `PARAMETER_FREEZE_CLASSIFICATION.md` · `PARAMETER_DEPENDENCY_MATRIX.csv`
· `OPEN_BIOPHYSICAL_PARAMETERS.md` · `MODEL_FREEZE_DECISION.md` · `CANONICAL_PARAMETER_MANIFEST.json` ·
`GPU_PRODUCTION_PATH_RECONCILIATION.md` · `BRANCHEA_CALIBRATION_FINDINGS.md` · `S2_MD_PROVENANCE_CORRECTION.md`
· `S2_LENGTH_EXISTING_EVIDENCE.md`. **Freeze-closure companions (canon v2):**
`CANONICAL_FREEZE_CLOSURE_FINDINGS.md` · `RIGOR_RUPTURE_PROMOTION_REGRESSION.md` ·
`GPU_CANONICAL_PRODUCTION_SIGNOFF.md` · `S2_LENGTH_FREEZE_DECISION.md`.

> **FREEZE-CLOSURE CORRECTIONS (2026-07-22, canon v2) — supersede this doc's earlier (pre-v2) draft where they
> conflict.** **(A) Production path = GPU, the normal canonical path via a per-assay-class gate**
> (`ExplicitHmmDimerGpuParams.productionValidated(Backend)`): the validated forked-dimer / explicit-S2 gliding
> class runs device-resident **WITHOUT the `-gpu-experimental` override**; unvalidated classes hard-fail; no silent
> fallback; CPU supplemental. **(B) branchEA = 0.03** confirmed from run metadata (the "0.3" recollection was a
> rejected candidate); split default eliminated; NUMERICAL COMPLIANCE — CALIBRATED BY CONSTRAINT. **(C) S2 EA and
> EI are BOTH MD/LITERATURE CONSTRAINED** (AMK-2008; EI not open/unknown). **(D)** the S2 exposed length is
> **CONDITIONALLY FROZEN at L40 (Gate A)**, material frozen; optional L60 gliding sweep is a §6.2 structural
> sensitivity, not required. **(E) Rigor-only rupture (mode 1) PROMOTED to the canonical production default ON**
> (canon v2): the ATP-free ADP protocol restored the ADP > rigor ordering (1.17×, peak 32.6 ms / 6.7 pN) with the
> frozen params, so the double-counting guard is satisfied without recalibration; `-no-rupture` is the
> byte-identical legacy-disable. Details in the closure companions + `MODEL_FREEZE_DECISION.md`.

---

## 0. What "the canonical model" is (scope) — read before the tables

There are **two motor lineages** in the tree. This audit is about the FIRST; the second is named only so its
parameters are never mistaken for canonical.

| Lineage | Code | Binding | Chemistry | Runner | In scope? |
|---|---|---|---|---|---|
| **Explicit-S2 two-body + HMM dimer** (the cooperativity model) | `TwoBodyConverterMotor`, `ExplicitHmmDimer`, `MotorModel` registry (`explicit-s2-l40`, `calibrated-s2-l40`, `fixed-anchor`); harnesses `ExplicitCompleteMatHarness`, `ExplicitSingleHeadHarness`, `ExplicitHmmDimerGlidingHarness`, `Sm4ForceLifetimeHarness`, `LaserTrapHarness` | deterministic 8-gate `bindP[]` contract (`gate2D`/`matBindExplicit`); reach = F8 preload < 2 pN | shared Lymn–Taylor core (`NucleotideCycleSystem.cycleLymnTaylor*`) | **GPU canonical (per-assay-class validation; no override for the validated gliding class)**; CPU supplemental — see corrections banner + `GPU_CANONICAL_PRODUCTION_SIGNOFF.md` | **YES** |
| Lumped sphere-head gliding (legacy) | `GlidingHarness`, `DenseGlidingHarness`, `BindingDetectionSystem` | `myoColTol` capture radius (`-coltol` 6/8/10 nm) | same `nucParams` but via `cycleLymnTaylor` from the lumped path | CPU/GPU | reference only |

**Critical shared-vs-distinct facts the tables rest on:**

1. **Chemistry is bit-identical single-head ↔ dimer.** Both call `MotorStore.setNucParams(dt)` +
   `setKinParams(0.006,−0.4,dt)` and dispatch to the *same* `cycleLymnTaylor*` kernels over the *same*
   `nucParams`/`kinParams` arrays. No `MotorModel` alters any rate (`MotorModel.java:19`). ⇒ every chemistry
   parameter is identical in both architectures (Part I, Q6).
2. **On the canonical `-lymntaylor` path the only release is "a bound head ending the step in `NUC_ATP`
   detaches."** The Guo–Guilford catch–slip term is applied **only as a load-modulation `g(F)` on the
   ADP→NONE transition** whose base rate is `onADP = nucParams[6] = 1000/s` — **NOT `kOff`**
   (`NucleotideCycleSystem.java:449-452`). `kOff = kinParams[0] = 100/s` is read only by the *legacy*
   `catchSlipRelease*` kernels, which are **not on the canonical path** ⇒ `kOff` is **dormant**.
3. **The explicit binding gate does NOT read `myoColTol`.** Its "reach" is the `preload < 2 pN` gate. The
   6/8/10 nm coltol sweeps belong to the lumped path only.
4. **GPU is the normal canonical production runner (closed — Part C).** The completed density sweeps ran GPU
   device-resident (PTX-compiled; `device-resident=true`; `no CPU fallback`; 0 invalid/solver). Governance is a
   **per-assay-class gate**: `ExplicitHmmDimerGpuParams.productionValidated(Backend)` marks the forked-dimer /
   explicit-S2 gliding class validated, so it runs device-resident **WITHOUT the `-gpu-experimental` override**;
   the full experimental single-graph timestep + un-ported two-body GPU models **hard-fail**; never a silent
   fallback. Evidence: deterministic fixtures V1–V8 + moving-actin + binding + chemistry bit-identical CPU↔GPU +
   0 invalid/solver across all production cells (aggregate-statistical equivalence, not bit-identical
   trajectories). **CPU is supplemental** (small assays, tests, targeted equivalence; the `runG4d` equivalence
   suite is preserved for future kernel changes). GPU and CPU are parameter-identical by construction (same
   arrays; Wang-hash RNG bit-identical). See `GPU_CANONICAL_PRODUCTION_SIGNOFF.md`.

---

## 1. Provenance classes and freeze statuses (keys used throughout)

**Provenance class (one primary per parameter):**
`A` direct biological measurement (matching species/isoform/construct/condition) · `B` literature-derived
approximation (related isoform/species/T/buffer) · `C` calibrated from an independent SoftBox assay · `D`
canonical modeling convention · `E` numerically chosen · `F` geometric assumption · `G` sensitivity
parameter (non-canonical) · `H` legacy inheritance.

**Freeze status:** `FROZEN` · `CONDITIONALLY FROZEN` · `OPEN — BIOPHYSICAL` · `OPEN — NUMERICAL` ·
`OPTIONAL / NONCANONICAL` · `DEPRECATED` · `UNKNOWN PROVENANCE`.

---

## 2. Motor chemistry — Lymn–Taylor nucleotide cycle (`nucParams`, size 8)

Set in `MotorStore.setNucParams(double dt)` (`MotorStore.java:455-464`); layout comment `:195-196`. Per-step
probability = `rate·dt`. Read by all `cycleLymnTaylor*` kernels. In-code provenance root: v1 `Env.java:836-855`
("Nucleotide on/off-filament rate constants"); literature attribution from
`docs/MOTOR_PARAMETER_PROVENANCE_25C.md` Table 2A (Howard 2001 Table 14-2, a multi-temperature compilation).
**Identical single-head ↔ dimer.**

| slot | transition | value | units | code | 1st-order? | literature source (as documented) | class | freeze |
|---|---|---|---|---|---|---|---|---|
| `nucParams[1]` atpOn | NONE→ATP (ATP-induced detachment; the sole LT release clock) | **2.0×10⁴** | s⁻¹ | `MotorStore.java:457` | pseudo-1st-order (saturating [ATP]) | Lymn & Taylor 1971 (rabbit skel HMM, ~20 °C; "too fast to measure," 2e4 = accepted lower bound) | B | FROZEN |
| `nucParams[2]` onATP | ATP→ADP·Pi hydrolysis, on-fil | **100** | s⁻¹ | `:458` | 1st-order | Lymn & Taylor 1971 / White & Taylor 1976 (~20 °C) | B | FROZEN |
| `nucParams[3]` offATP | ATP→ADP·Pi hydrolysis, off-fil (sets ~10 ms detached recovery) | **100** | s⁻¹ | `:459` | 1st-order | as above | B | FROZEN |
| `nucParams[4]` onPi | ADP·Pi→ADP (Pi release / **power stroke**), on-fil | **1.0×10⁴** | s⁻¹ | `:460` | 1st-order | White & Taylor 1976 / Howard T14-2 (~20 °C assumed) | B | FROZEN |
| `nucParams[5]` offPi | ADP·Pi→ADP, off-fil (disabled ⇒ head stays primed) | **0** | s⁻¹ | `:461` | — | v1 choice ("//0.1", `Env.java:851`); absorbing | D | CONDITIONALLY FROZEN |
| `nucParams[6]` onADP | ADP→NONE (**ADP release; velocity-limiting; catch-slip-modulated**), on-fil | **1.0×10³** | s⁻¹ | `:462` | 1st-order × g(F) | Siemankowski, Wiseman & White 1985 (rabbit skel S1, ~15 °C; ≥500/s lower limit) — already ~25 °C-equivalent | B | FROZEN |
| `nucParams[7]` offADP | ADP→NONE, off-fil | **1.0×10³** | s⁻¹ | `:463` | 1st-order × g(F) | as above | B | FROZEN |

- The **canonical force-dependent lifetime** lives here: ADP→NONE effective rate = `(bound?onADP:offADP)·g(F)`,
  `g(F)=aCatch·e^(−F·xCatch/kT)+aSlip·e^(+F·xSlip/kT)`, base **1000/s** (not kOff).
- **Temperature stitch (documented, accepted):** the `exp(F·x/kT)` denominator and Brownian are explicit
  **25 °C** (`Constants.java:26` `tempK=298.15`), while the cycle is a Howard-Table-14-2 compilation of ~20 °C
  ATP-side + ~15 °C ADP-release rates. `docs/MOTOR_PARAMETER_PROVENANCE_25C.md` establishes there is **no single
  effective temperature to correct FROM**, no clean per-transition Q₁₀, and that the velocity-limiting ADP
  release is already ~25 °C-equivalent ⇒ **temperature is NOT a gliding lever.** These rates are frozen as
  validation inputs, not tuned.

---

## 3. ATP handling

| item | finding | code | class | status |
|---|---|---|---|---|
| ATP-scaling **mechanism** | `atpOn` is pseudo-first-order; `nucParams[1] ← 2e4·atpScale`, `atpScale ∝ [ATP]/[ATP]_default` | `Sm4ForceLifetimeHarness.java:147-151,241` | D (declared interface) | CONDITIONALLY FROZEN |
| zero scaling ≡ ATP-free? | **Yes** — `atpScale=0.0` ≡ `atpFree` (`nucParams.set(1,0)`) removes ALL detachment cleanly (no break-cap/emergency on this path) | `Sm4…java:146,149-150,184` | — | — |
| default ATP **condition** — gliding | **saturating** (`atpScale=1`, `atpFree=false`) | — | (assay condition) | FROZEN (canonical gliding condition) |
| default ATP **condition** — ADP force-clamp | **ATP-free** (`atpScale=0`) — the protocol-matched Guo & Guilford nucleotide-free condition | `docs/SM4_ADP_ATPFREE_PROTOCOL_FINDINGS.md` | (assay condition) | FROZEN (protocol-declared) |
| `atpFactor` | **absent** — the mechanism is `atpScale` only | — | — | n/a |

**Governance note (Part D2):** the ATP-scaling *mechanism* (pseudo-first-order effective rate) is a declared
model approximation → CONDITIONALLY FROZEN; the *default ATP condition* per assay (saturating for gliding,
ATP-free for the ADP force-clamp) is a run condition frozen with its assay, not a motor property. All
production harnesses share the same `atpScale` interface.

---

## 4. Force-dependent release — catch-slip on ADP→NONE (`kinParams`)

Set in `MotorStore.setKinParams(myoColTol, alignTol, dt)` (`MotorStore.java:288-329`); layout `:138-147`.
Law applied to `onADP` at `NucleotideCycleSystem.java:449-452`. **Identical single-head ↔ dimer.**

| slot | name | value | units | code | source (documented) | class | freeze |
|---|---|---|---|---|---|---|---|
| `kinParams[1]` | alphaCatch | **0.92** | — | `:290` | Guo & Guilford 2006 (rat skel HMM, "room temp") | B | FROZEN |
| `kinParams[2]` | alphaSlip | **0.08** | — | `:291` | Guo & Guilford 2006 | B | FROZEN |
| `kinParams[3]` | xCatch | **2.5** (2.5e-9 m) | nm | `:292` | Guo & Guilford 2006 (Veigel d≈2.7 nm cross-check) | B | FROZEN |
| `kinParams[4]` | xSlip | **0.4** (0.4e-9 m) | nm | `:293` | Guo & Guilford 2006 | B | FROZEN |
| `kinParams[5]` | kT | 4.116×10⁻²¹ | J | `:294` | `Constants.kT`, 25 °C explicit | A (defined) | FROZEN (PINNED) |
| `kinParams[0]` | kOff | 100 | s⁻¹ | `:289` | v1 `Env.java:822`; duty anchor | H | **DEPRECATED** (dormant on LT path) |

- `g(0) = aCatch+aSlip = 0.92+0.08 = 1.000000` exactly (design guard;
  `docs/SM4_FORCE_DEPENDENT_LIFETIME_FINDINGS.md`). Emergent catch peak ≈ 6 pN (Guo & Guilford ≈6.4 pN target).
- **Blind validation (Part I, Q2):** `docs/SM4_BLIND_STUDY_FINDINGS.md` — a genuinely blinded analyst
  recovered the rigor catch-slip decisively (ΔAIC +7240 vs Bell), and the ADP catch-slip on the compliant
  fixture recovered **xCatch = 2.50 nm exactly**. No parameter was tuned; production default not flipped.
- **Firewall:** these are the "current canonical ADP force-law parameters" → **FROZEN, not available for
  gliding tuning** (Part D6).

---

## 5. Rigor & ADP mechanical rupture (SM4 campaign) — rigor-only rupture is the canonical default ON (canon v2)

### 5a. Rigor mechanical rupture — `rigorParams` (size 12); `NucleotideCycleSystem.cycleLymnTaylorRigor`
Storage `MotorStore.java:238`; setter `setRigorRupture:342-354`; eligibility = **bound head in NUC_NONE (rigor)
only** (`:546-547`). Canonical production default = **mode 1 ON** in the single-head / HMM-dimer / GPU-production
paths; `-no-rupture` / `-legacy-disable` / `-rupture-mode 0` restores the byte-identical legacy default; the SM4
force-clamp apparatus keeps its default OFF but invokes the same pathway explicitly (`-rigor-rupture`).

| slot | param | value | units | source (verbatim, `Sm4…:134-135`) |
|---|---|---|---|---|
| [0] | enabled | **1 (ON) in production** | flag | canonical default ON (v2); `installRigor` sets it; `-no-rupture` ⇒ 0 (byte-identical legacy) |
| [2] | k0Rigor | 140 | s⁻¹ | Guo & Guilford 2006 **Table-2 rigor** fit (catch k_c0=127/s, slip k_s0=13/s ⇒ k0=140) |
| [3] | aRigorCatch | 0.9071 | — | " |
| [4] | xRigorCatch | 1.5 | nm | " (x_c=1.5 nm) |
| [5] | aRigorSlip | 0.0929 | — | " |
| [6] | xRigorSlip | 0.5 | nm | " (x_s=0.5 nm) |
| [9] | pDtCap | 0.2 | — | rate·dt small-limit guard (flag, never clip) |

**Class B (Guo & Guilford Table-2). The parameters are FROZEN (blind-validated: planted xCatch 1.50 →
recovered 1.51, z=0.70; peak 6.99 → 7.09 pN).** **Governance (closed — Part B, canon v2): rigor-only rupture
(mode 1) is the CANONICAL PRODUCTION DEFAULT ON.** Promoted after the `RIGOR_RUPTURE_PROMOTION_REGRESSION`
passed every check: the rigor force-clamp law recovered (xCatch 1.5, peak ~7 pN); the gliding impact is
**negligible** (+0.33%±1.56%, |t|=0.21, `docs/matsoa/RIGOR_RUPTURE_AND_GLIDING_IMPACT_FINDINGS.md`); the
legacy-disable is byte-identical; SM6 unchanged; 0 invalid/solver. **The old ADP-calibration blocker is
removed:** the ATP-free ADP protocol (`atpOn=0`) restored the correct **ADP > rigor** ordering — ATP-free ADP
is **1.17× rigor** (Guo & Guilford ~1.24×), peak lifetime **32.6 ms** (exp 31.7), peak force **6.7 pN** (exp
6.4), with the planted ADP catch-slip params recovered stage-resolved by the sequential model and **no ADP
parameter retuned** (`SM4_ADP_ATPFREE_PROTOCOL_FINDINGS.md`). The prior ATP-present "rigor > ADP" mismatch was a
protocol + observation-model artifact, **not a calibration failure**; the double-counting guard is **satisfied
without recalibration**. The remaining ADP limitation is stated accurately: SoftBox represents the ATP-free
ADP-conditioned lifetime as a **sequential** ADP→NONE release + rigor rupture, whereas the experiment is more
naturally a **direct** rupture from an ADP-bound bond; the optional direct-ADP-rupture channel did not improve
the result (outcompeted by the faster ADP→NONE transition) and is a declared future refinement, not required.
See `PARAMETER_FREEZE_CLASSIFICATION.md` §C, `RIGOR_RUPTURE_PROMOTION_REGRESSION.md`.

### 5b. Direct ADP rupture — "all-strong-bound" mode 2 — `adpRuptureParams`
Storage `MotorStore.java:240` (`[0]=0 ⇒ OFF`); setter `setAdpRupture:360-372`; kernel
`cycleLymnTaylorRuptureAll`; eligibility = bound head in NUC_ADP only. Flag `-allrupture`/`-rupture-mode 2`.
Values: k0=191, aCatch=0.92147, xCatch=2.5 nm, aSlip=0.07853, xSlip=0.4 nm → **Guo & Guilford 2006 Table-2 ADP
fit** (`Sm4…:141-142`).

**Class B. Status: OPTIONAL / NONCANONICAL (Part D5).** It worsened ADP agreement and changed gliding
velocity (`docs/ALL_STRONG_BOUND_RUPTURE_FINDINGS.md`; paper outline §2.7) — a **rejected structural
alternative**, default OFF, sensitivity-only, must not influence canonical conclusions.

### 5c. HMM-dimer GPU strain-rupture failsafe (R0–R5) — `ExplicitHmmDimerGpuParams`
A **separate mechanical-strain bond-rupture failsafe** on the GPU beam-solve path (NOT the LT chemistry).
`RUPTURE_MODE = 0` (R0 disabled, byte-identical default; `:97`). Thresholds `BOND_RELEASE_NM=20`,
`BRANCH_RELEASE_NM=10`, `BRANCH_RELEASE_STRAIN=1.0`, `RUPTURE_FORCE_PN=40`, `EMERGENCY_GAP_NM=50`,
`EMERGENCY_ON=false` (`:98-103`) — **numerical-stability failsafes, no derivation/citation**. Class E. Status
**OPTIONAL / NONCANONICAL** (default off; threshold promotion pending a compute-heavy sweep). Distinct from the
canonical GPU gliding path: the validated standing config requires the strain failsafe OFF (an active
`RUPTURE_MODE` aborts `validateStandingConfig`), so this failsafe is not part of the production-validated class.

---

## 6. Binding rates, caps, refractory

| item | value | code | class | status |
|---|---|---|---|---|
| bind mechanism (canonical) | **deterministic geometric 8-gate** (no stochastic k_on) | `gate2D` `TwoBodyConverterMotor.java:4551`; applied `stepGlideS2:6831` | D | CONDITIONALLY FROZEN |
| `kinParams[14]` kOn | **0 (unused)** — geometric bind-on-contact | `MotorStore.java:316` | E | DEPRECATED (off) |
| MYO_REBIND_TIME (refractory) | **1.0×10⁻⁵ s** ⇒ `refractorySteps=ceil(1e-5/dt)` (=1 at prod dt, =4 at 2.5e-6) | `MotorStore.java:153,302` | H/E | CONDITIONALLY FROZEN (numerical) |
| refractoryBlockProb | 1.0 (deterministic 1-step); 0.31 with `-faithfulrefractory` | `MotorStore.java:159,310` | E | CONDITIONALLY FROZEN (default) / OPTIONAL (0.31 variant) |
| per-step probability clamp | **none** — Euler `rate·dt`; if `Σp>pDtCap(0.2)` the step is FLAGGED, never clipped | `NucleotideCycleSystem.java:497,555` | E | FROZEN — NUMERICAL |
| `myosinBreakForce` (12 pN cap) | 12 pN, `kinParams[11]`; enable `kinParams[12]=0` (**OFF**) | `MotorStore.java:306,307`; `setFaithfulRelease` | D/E (MURKY physical/numerical) | OPTIONAL (default OFF) |

---

## 7. Motor mechanics — cross-bridge (F8), lever, stroke

| param | value | units | code | provenance | class | freeze |
|---|---|---|---|---|---|---|
| **`myoSpring` (F8 cross-bridge stiffness)** | **1.0** (1.0e-9 N/µm) | pN/nm | `CrossBridgeSystem.java:120`; consumed everywhere; `xbParams[0]`; 3C-core `kF8=1.0` | **v1 gliding-behavior match — "unpublished, internal v1 reference (conference-poster), NOT experimentally validated ground truth"** (`docs/CROSSBRIDGE_STIFFNESS_SWEEP_FINDINGS.md`); literature bracket 0.3–2 pN/nm (Veigel; Kaya & Higuchi ≈1.8) | D (+C via gliding) | **CONDITIONALLY FROZEN — GENERIC BUT ACCEPTED** |
| lever length (neck-lever) | `LB_3C` = 8; point-motor `LEVER_LEN` = 8 | nm | `TwoBodyConverterMotor.java:1185`; `MotorStore.java:45` | v1 geometry (`Env.java:776-778`) | F | CONDITIONALLY FROZEN |
| power-stroke swing | PRESTROKE θ=−30°, ADP θ=+30° ⇒ **+60° converter swing** | deg | `TwoBodyConverterMotor.java:2164,2472` | modeling geometry; validated by Exp 3D/3E | F | CONDITIONALLY FROZEN |
| unloaded working stroke (**emergent**) | ≈7 nm (explicit ≈5–7 nm) | nm | measured, not set | validation vs skeletal ~5–8 nm (Finer 1994; Molloy 1995; Norstrom 2010) | — | **PINNED validation metric** (never a knob) |
| `kconv` (converter torsional stiffness) | 128 | pN·nm/rad² | `:6297` (grid `KCONV_3C={32,64,128,256,576,1000}` `:1190`) | **no in-code provenance — mid-sweep choice** | G/D | CONDITIONALLY FROZEN (eligible for §6.2 compliance study) |
| `kbind` (bound-head orientation stiffness) | 512 | pN·nm/rad² | `:6297` (grid `KBIND_3C` `:1191`) | **no in-code provenance — sweep choice** | G/D | CONDITIONALLY FROZEN (eligible for §6.2) |
| head ellipsoid `A_SEMI` | {4.5, 2.75, 2.25} | nm | `:1184` | motor-domain shape (geometric) | F | CONDITIONALLY FROZEN |
| converter arm \|r_F8−r_conv\| | ≈7.6 nm (`R_F8={3.5,1.5}`, `R_CONV={−3.5,−1.5}`) | nm | `:1186-1187` | geometric construction, no citation | F | CONDITIONALLY FROZEN |
| point-motor geometry (legacy lineage) | ROD 80 / LEVER 8 / HEAD 20 nm | nm | `MotorStore.java:45` | v1 `Env.java:776-778` | F/H | CONDITIONALLY FROZEN (not on explicit tail path) |

**Cross-bridge stiffness sensitivity (completed; `docs/CROSSBRIDGE_STIFFNESS_SWEEP_FINDINGS.md`).** Values 0.5 /
1.0 / 2.0 pN/nm at dt=1e-5: glide velocity **non-monotonic, peaked at 1.0** (2.45 / 3.80 / 0.53 µm/s); avgBound
24.4 / 6.9 / 0.25 (only 1.0 reproduces v1 ≈7.5). Softening halves overshoot but **halves force**, breaking the
joint calibration with the catch-slip. ⇒ 1 pN/nm is **sharply constrained, not a free win**; **any change must
be treated as a structural mechanics study and rerun the step-size and force-clamp single-molecule
validations** (Part D3).

---

## 8. S2 beam — the explicit-S2 tail (`EXPLICIT_S2_L40`)

Single source of truth `MotorModel.ExplicitS2Params.frozenL40()` (`MotorModel.java:312-330`), bit-checked
against live `TwoBodyConverterMotor.EXP4G_*` (`:6263-6272`) by `assertFrozenParamsConsistent()`.

| param | value | units | code | derivation / provenance | class | freeze |
|---|---|---|---|---|---|---|
| **`EA` (axial stretch modulus)** | 4.2×10⁻⁹ | N | `:6268` = 70e-3·60e-9 | **Adamovic, Mijailović & Karplus 2008 + Brizendine 2021** (MD-derived); K_ax(60)=70 pN/nm (lit 60–80) | B | CONDITIONALLY FROZEN |
| **`EI` (bending rigidity)** | 7.2×10⁻²⁸ | N·m² | `:6269` = 0.01e-3·(60e-9)³/3 | **AMK-2008 lateral 0.008–0.012 pN/nm; SoftBox 0.01 dead-center; Lp≈175 nm in-band** (corrected — NOT unknown) | B | **CONDITIONALLY FROZEN — MD/LITERATURE** |
| `kAxRef` | 70 (lit 60–80) | pN/nm | `:6263` | Adamovic 2008 (mid-band) | B | CONDITIONALLY FROZEN — MD/LITERATURE |
| `kLatRef` | 0.01 (lit 0.008–0.012) | pN/nm | `:6264` | Adamovic 2008 (dead-center); sets EI, Lp | B | CONDITIONALLY FROZEN — MD/LITERATURE |
| `Lref` | 60 | nm | `:6265` | MD reference length | B | reference constant |
| `l0` (beam segment) | 10 | nm | `:6266` | discretization | E | FROZEN — NUMERICAL |
| `Rnode` (beam-node drag radius) | 5 | nm | `:6267` | lumps a 10 nm segment; stability | E | FROZEN — NUMERICAL |
| **`L` (explicit/exposed S2 length)** | **40** | nm | `:6270` (built {10,20,40,60}) | free-S2 CONTOUR length; canonical reference geometry (Gate A); a surface **boundary-condition/geometry** choice (material frozen); L-robust mechanics | F | **CONDITIONALLY FROZEN — GEOMETRY** (canonical L40; §E) |
| `M` (segments at L40) | 4 (⇒ 5 nodes) | — | `MotorModel.java:318` | L/l0 | E | derived |
| `k_ax = EA/L` @L40 | 105 | pN/nm | `MotorModel.java:321` | derived | — | inherits L |
| `Lp = EI/kT` | 175 | nm | `MotorModel.java:322` | derived | — | inherits EI |
| per-segment `ks = EA/l0` | 420 | pN/nm | `:6261,6303` | derived | — | — |

**The exact geometric meaning of "40 nm" (Part D4).** L is the **free-S2 contour length** of the exposed S2
coiled-coil modelled as an extensible-elastica beam — the path from the **clamped supported emergence point
E** (node 0, fixed point + fixed +b̂ tangent) to the **distal node = converter pivot P₀**
(`buildS2M:6301-6313`). It is a *fixed-contour* length (stiff stretch, ks=420 pN/nm/seg): the beam absorbs
load by bending, not stretching; contour is conserved and end-to-end = L−slack when slack>0 (pre-bent). So
"40 nm" is **contour / elastic-segment length, not projected end-to-end length**, discretized into M=4
segments. It enters the physics four ways:
- **Tension transmission** — the pivot's effective axial reaction is `k_ax = EA/L = 105 pN/nm at L40`.
- **Buckling** — Euler `F_crit = π²EI/L² ≈ 4.44 pN` at L40; compression buckles soft, tension stays stiff —
  an *emergent* asymmetry (not a coded tension-only rule).
- **Dimer geometry** — L is reused as the HMM dimer's head→anchor contour `TOTAL_NM = 40` (shared 30 nm +
  branch 10 nm/head; `ExplicitHmmDimer.java:54,142`).
- **Force-clamp fixture** — the relaxed-pivot fixtures (`s2RelaxHold`) that CALIBRATE the surrogate depend on
  L40 through k_ax=105 and the buckling geometry; the FXB blind force-clamp fixture is the compliant
  explicit-s2-l40 tail.

**Why the LENGTH is CONDITIONALLY FROZEN at L40 — GEOMETRY (the MATERIAL is frozen; Gate A, Part D):** the S2
EA **and EI** are MD/literature-constrained (Adamovic 2008; kLatRef=0.01 is dead-center in the 0.008–0.012 band
— `S2_MD_PROVENANCE_CORRECTION.md`), so the **material stiffness is not open**. What remains is the **exposed
contour length + surface emergence boundary condition**: L∈{10,20,40,60} were built, and the **L40≠L60 caveat**
(bending compliance softens the effective axial reaction ~2× below `ks/M`) means the choice of exposed length is
a bounded geometry question. **L = 40 nm is the canonical reference geometry** and every main biological
conclusion is L-robust across 40–60 nm (stroke L-robust; MD scaling; `S2_LENGTH_FREEZE_DECISION.md` — Gate A).
**No new run is required;** an L60 gliding density sweep is retained as an **optional §6.2 structural
sensitivity**, not a freeze blocker — no material-stiffness study (`OPEN_BIOPHYSICAL_PARAMETERS.md` §1).

---

## 9. Branch / fork / dimer geometry (the 19-DOF HMM forked-beam)

`ExplicitHmmDimer`; topology E→shared-S2(Ms=3)→fork→two branches(Ma=Mb=1)→two heads ⇒ **19 DOF**
(`ExplicitHmmDimerGpuParams.java:69`).

| param | value | units | code | provenance | class | freeze |
|---|---|---|---|---|---|---|
| TOTAL contour (E→pivot) | 40 | nm | `ExplicitHmmDimer.java:54` | = the L40 S2 length | B/F | inherits L (OPEN) |
| branchLen | 10 | nm | `ExplicitHmmDimerGlidingHarness.java:75,79` | fork-relaxation study; no citation | F | OPEN — BIOPHYSICAL |
| sharedLen | 30 | nm | `ExplicitHmmDimer.java:142` | TOTAL − branchLen | — | derived |
| SPLAY (branch splay half) | 16 | deg | `:75,78` | geometry; no citation | F | OPEN — BIOPHYSICAL |
| ALPHA (fork rest half-angle) | 10 | deg | `:75,77` | energy term; no citation | F | OPEN — BIOPHYSICAL |
| BREI (branch EI multiplier) | 0.25 | × | `:75,80` | compliance study; no citation | F/E | OPEN — BIOPHYSICAL |
| **`branchEA` (branch axial multiplier)** | **0.03 (12.6 pN/nm) — CANONICAL, RESOLVED** | × | single source of truth `ExplicitHmmDimerGpuParams.java:81` (`STANDING_BRANCH_EA=0.03`); harness default now references it (split eliminated) | stability/geometry-calibrated (lowest dt-stable = stiffest that removes the 270→11.8 nm joint-gap instability; velocity flat, not gliding-fit); confirmed used by every production cell | E | **NUMERICAL COMPLIANCE — CALIBRATED BY CONSTRAINT** |
| EXCL (same-fil site exclusion) | 5.4 | nm | `:72,83` | occupancy exclusion | F | CONDITIONALLY FROZEN |
| dirMech (directional mode) | 0 (NONE / Outcome A) | — | `ExplicitHmmDimer.java:101` | glides directionally with no imposed rule | — | CONDITIONALLY FROZEN |

**✓ Resolved (corrected — Part B).** Run metadata confirms every production cell used **branchEA = 0.03**
(`"branchEA":0.0300000`; the "0.3" recollection was a *rejected* candidate — "NOT enough, still 1220 nm"). The
former split default (CPU harness 1.0 vs GPU standing 0.03) is **eliminated**: the harness `CFG_EA` now defaults
to the single source of truth `ExplicitHmmDimerGpuParams.STANDING_BRANCH_EA = 0.03`; `-branchEA 1.0` is the
explicit reference-stiffness diagnostic. branchEA is a NUMERICAL COMPLIANCE — CALIBRATED BY CONSTRAINT
(stability/geometry: lowest dt-stable = stiffest that removes the excursions; velocity flat across 0.01–1.0, so
not gliding-fit). Full calibration + density-resolved health: `BRANCHEA_CALIBRATION_FINDINGS.md`.

---

## 10. Calibrated production surrogate (`CALIBRATED_S2_L40`)

Frozen **fit outputs** of Experiment 4I (fit to `EXPLICIT_S2_L40` relaxed-pivot reaction),
`MotorModel.CalibratedS2Params.frozenL40()` (`:351-366`). Class **C** (calibrated from an independent SoftBox
assay). Status **CONDITIONALLY FROZEN** (changing requires a re-fit to the explicit reference).

| param | value | units | note |
|---|---|---|---|
| kAxTension / kAxCompression | 105 / 105 | pN/nm | = ks/M = EA/L at L40 (exact); symmetric-stiff (Euler buckling **disabled in-range** — an L60 feature) |
| kTr (transverse bending reaction) | 0.026 | pN/nm | ≈3EI/L³; search-observable ambiguity ~1.5× |
| kFeTr / rMaxOnset | 20 / 20.3 | pN/nm / nm | finite-extension limiter (inactive over visited states) |
| smoothAx / smoothTr / smoothBuck | 0.5 / 1.5 / 0.8 | nm | C∞ transition widths |
| kFloor | 20 | pN/nm | one-sided substrate floor |
| refFreeLen | 40 | nm | L40 |

**L40≠L60 caveat (explicit):** F_crit≈4.44 pN buckling is an L60 feature disabled at L40; **not a validated
surrogate for L60 by simple length scaling** (axial 1/L overpredicts ~2×; class C). Cost ≈1.34 µs/motor-step,
GPU-friendly. The surrogate is the *production* motor for cheap large runs; the explicit beam is the
mechanistic reference.

---

## 11. Binding geometry — the `bindP[]` gate contract

Populated `ExplicitCompleteMatHarness.packExMat:83-86`; decoded `ExplicitBindDiagHarness:158-169`; applied
`stepGlideS2:6831`. **Deterministic 8-gate AND — no binding RNG.**

| idx | value | gate | code | class | freeze |
|---|---|---|---|---|---|
| bindP[0] | 3.0 nm | distance (`surf<3` ⇔ conDist<6.5 nm) — a **no-op** (preload always tighter) | `Tol.dBindNm=3.0` `:2167` | F | CONDITIONALLY FROZEN (inert) |
| bindP[1] | 25° | ψ binding-face orientation | " | F | CONDITIONALLY FROZEN |
| bindP[2] | 25° | φ neck-lever vs +30° | " | F | CONDITIONALLY FROZEN |
| bindP[3] | 20° | θ converter-coord | " | F | CONDITIONALLY FROZEN |
| **bindP[4]** | **2.0 pN** | **preload / reach** (`conDist<2.0 nm`) — the recruitment bottleneck, at its empirical optimum | `Tol.preloadPn=2.0` | F | CONDITIONALLY FROZEN |
| bindP[5] | 15 kT | converter+bind attach energy | `Tol.energyKt=15.0` | F | CONDITIONALLY FROZEN |
| bindP[6] | 3.5 nm | FIL_R filament radius | `Constants.radius` | A | FROZEN |
| bindP[7] | +30° | PHI_PRE_3E pre-stroke pose | `:2163` | F | CONDITIONALLY FROZEN |
| bindP[8] | 2.25 nm | head-side steric (`A_SEMI[2]`) | `:1184` | F | CONDITIONALLY FROZEN |
| bindP[9] | kT | Constants.kT | `:27` | A | FROZEN |
| **bindP[10]** | **1×10⁻⁶ µm (machine-ε)** | in-segment arc margin — **canonical half-open ownership** | `bindMargin()`/`BIND_EPS` `:4529,4532` | F | **FROZEN** (segmentation-invariant limit) |
| **bindP[12]** | **0 = canonical half-open** | ownership mode | `LEGACY_OWNERSHIP?1:0` `:4528` | — | FROZEN |

- The gate tolerances are **"named; NOT tuned to a canonical rate"** (`TwoBodyConverterMotor.java:2240`) —
  heuristic provenance (F), but the **completed sensitivity study closes them** (§13 below).
- **Deprecated/regression-only:** `LEGACY_MARGIN = 0.05 µm` (50 nm segment-end exclusion) — **UNKNOWN
  PROVENANCE** ("endpoint-disambiguation intent but no derivation," masked an ownership-handoff gap; commit
  `e17b5a4`), replaced by half-open ownership 2026-07-18. `myoColTol = 6 nm` — a "NEUTRAL PLACEHOLDER"
  (`GlidingHarness.java:133`), **not read by the explicit path**; the 8/10 nm coltol values are swept
  experimental conditions on the lumped path.

**Azimuthal / site-limited binding is NOT part of the canonical model** — the azimuthal-acceptance gate
(default Δ=45°) and azimuthal falloff exist only as flag-gated, **default-OFF** experiments on the lumped
`BindingDetectionSystem` path, both shown insufficient to bend the velocity–density curve
(`docs/AZIMUTHAL_GATE_INCREMENT2.md`, `AZIMUTHAL_FALLOFF_INCREMENT3.md`). Class G, OPTIONAL/NONCANONICAL.

---

## 12. Density, surface, anchor, and actin filament mechanics

| param | value | units | code | class | freeze |
|---|---|---|---|---|---|
| **density convention — single-head** | motors(**heads**)/µm²; `nMot=round(ρ·3.0)` over 3.0×1.0 µm | µm⁻² | `TwoBodyConverterMotor.java:4447`; box `:4413` | (definition) | FROZEN convention |
| **density convention — HMM dimer** | **dimers(molecular)/µm²**; heads = 2·nDim | µm⁻² | `ExplicitHmmDimerGlidingHarness.java:194,738` | (definition) | FROZEN convention |
| anchor distribution | uniform random (Wang-hash) over the lawn | — | `:4491-4492` | E | FROZEN convention |
| motor anchor z (coverslip plane) | −0.05 (`MANCHOR_Z`) | µm | `LaserTrapHarness.java:1014` | v1 fixedMyosinZValue | F | CONDITIONALLY FROZEN |
| surface confinement (filament) | `G4_KZ = 2.0` pN/nm harmonic z-only wall | pN/nm | `:4418,6845` | assay fixture | E | FROZEN (assay) / surface height OPEN (§F) |
| filament–head gap (dimer) | 2.0 nm target | nm | `ExplicitHmmDimerGlidingHarness.java:199` | geometric | F | CONDITIONALLY FROZEN |
| segment length (mat/gliding) | 0.17550 (64 mono/seg) | µm | `:4459`; `G4_MONO=64` `:4415` | discretization | E/F | FROZEN — NUMERICAL |
| # segments — single-head | 12 (contour 2.106 µm) | — | `G4_NSEG=12` `:4416` | assay config | E | FROZEN (assay) |
| # segments — dimer | 11 (contour 1.931 µm) | — | `ExplicitHmmDimerGlidingHarness.java:198` | assay config | E | FROZEN (assay) |
| matched-length control | 11-seg/12-seg velocity ratio 1.034±0.018 | — | `docs/matsoa/SINGLE_HEAD_11SEG_LENGTH_CONTROL_FINDINGS.md` | — | validated |
| persistence length | 15 µm (EI=kT·Lp) | µm | `Constants.java:45-46` | v1 `Env.java:572`; phalloidin actin ~10–18 µm | B/D | CONDITIONALLY FROZEN (note: `G4_NSEG` comment says "≈17 µm" — minor doc mismatch) |
| chain PAIRS (F3/F4) | fracMove 0.5, fracR 0.1, fracMoveTorq 0.265 | — | `FilamentStore.java:345-349` | v1 PAIRS port (dt-robust damping) | H/E | CONDITIONALLY FROZEN |
| rod drag fit | aParallel −0.20, aOrthog 0.84, aTurning −0.662 | — | `Constants.java:49-51` | v1 `FilSegment.java:89-91` (FDT-validated) | B | FROZEN |
| medium viscosity `aeta` | **0.1** (≈100× water) | Pa·s | `Constants.java:33` | v1 `Env.java:404` — a **numerical** choice; absolute glide µm/s is drag-dependent, the faithful comparison is drag-independent (V₀=step×rate) | E | CONDITIONALLY FROZEN — NUMERICAL (caveat) |
| Brownian coeffs | BTransCoeff 1.0, BRotCoeff 0.5; interior brownRotScale 0 | — | `Constants.java:57-58`; `:4472` | v1 Lp-tuning + chain-cohesion | H | CONDITIONALLY FROZEN |
| thermal amplitude | sqrt(2·kT/dt) | — | `Constants.java:65-67` | FDT (v1 GPUMoveThing:6789) | A | FROZEN |
| excluded volume | none (single filament in assay) | — | — | assay config | — | n/a |
| boundary condition | filament FREE (unpinned), z-confined; glide = LS slope of centroid on filament axis | — | `measureS2Mat:6855` | canonical estimator | — | FROZEN (methodology) |

---

## 13. Binding-gate sensitivity study — COMPLETE (Part D7 evidence)

Sources `docs/matsoa/EXPLICIT_BINDING_RESOLUTION_FINDINGS.md` (angular + dt) and
`EXPLICIT_BINDING_REACH_SENSITIVITY_FINDINGS.md` (reach/preload/margin). Diagnostic-only; **canonical defaults
unchanged** in the study; 0 invalid / 0 solverFail across all runs.

- **Angular gates φ/ψ/θ (25/25/20°): non-limiting, in a flat plateau.** Over 2.6×10⁷ eligible motor-steps at
  ρ700, leave-one-out sole-failure counts φ=0, ψ=1, θ=4 (vs preload 4679, in-segment 5464). Moving each 0.6×–2×
  shifts bind rate ≤±7 % (noise) and admits ≈0 new attachments. ⇒ keep 25/25/20 (frozen plateau).
- **Distance (3.0 nm): a pure no-op** (preload always tighter).
- **Preload (2.0 pN): the dominant reach filter, at its optimum.** Sweep 1.0→4.0 pN gives no clean monotonic
  recruitment gain (peaks at default) while degrading onset-force quality (F8 p99 7.11→8.26 pN). Default sits at
  the recruitment peak with best onset quality.
- **In-segment margin: the one clean lever** — 0.05→~0.0125 µm raised recruitment +57–79 % with onset F8
  unchanged; this motivated the rollout to **canonical half-open ownership + machine-ε margin (now default)**,
  which left-shifts the density curve while **preserving the ρ3000 plateau (~4 %)**.
- **dt (2.5e-6):** modestly under-samples binding (+12 % flux, +8 % occupancy from dt→dt/2; converged by dt/2)
  — the known cross-bridge-substep family, an OPEN — NUMERICAL item, not a gate issue.

**Effect on the ensemble curve:** saturation existence is **unchanged by any gate move** — the velocity–density
curve is plain hyperbolic (single-head vmax 4.40 µm/s, ρ½ 352, Hill n=1.09; dimer vmax ≈3.4, ρ½ 363, n≈1.3;
`docs/matsoa/SINGLE_HEAD_GPU_DENSITY_SWEEP_LONG_FINDINGS.md`, `EXPLICIT_HMM_DIMER_GPU_DENSITY_SWEEP_LONG_FINDINGS.md`).
Dimerization is a **pure Vmax effect** (−23 %) that does **not shift ρ½**. ⇒ the binding gate is **sufficiently
closed** for the canonical model (Part I, Q5); the §6.1 "alter the binding gate" perturbation is a declared
structural study, not re-tuning.

---

## 14. Numerics

| param | value | code | class | freeze |
|---|---|---|---|---|
| dt — explicit / beam / HMM / SM4 (**the canonical path**) | **2.5×10⁻⁶ s** (abort-gated) | `MotorModel.java:329`; `ExplicitCompleteMatHarness.java:25,983`; `Sm4…:119,214` | E | FROZEN — NUMERICAL (with ≤12 % binding under-sampling flagged OPEN — NUMERICAL) |
| dt — lumped gliding | 1×10⁻⁵ s (a **hard ceiling, no headroom**; not converged; substep is the open lever) | `GlidingHarness.java:119` | E | OPEN — NUMERICAL |
| `Constants.deltaT` | 1×10⁻⁴ s (fallback; force laws take caller dt) | `Constants.java:30` | H | n/a |
| integrator | overdamped Langevin + linearly-implicit cross-bridge (`(a·I+K)Δq=F`); (3M+2)-DOF implicit beam; 19-DOF HMM Newton | `TwoBodyConverterMotor.java:1342` | E | FROZEN — NUMERICAL |
| RNG | counter-based Wang hash keyed (entity, step, seed); **bit-identical CPU↔GPU by construction** | `BrownianForceSystem.java:35`; `NucleotideCycleSystem.java:25` | E | FROZEN — NUMERICAL |
| GPU precision | float32 (`Precision.FLOAT`) | `ExplicitHmmDimerGpuParams.java:88` | E | FROZEN — NUMERICAL (GPU = normal canonical production; per-assay-class validated gliding class runs without override, §Part C) |
| competing hazards | single-uniform partition (ATP vs rupture vs release) | `NucleotideCycleSystem.java:557` | E | FROZEN — NUMERICAL |
| velocity estimator | LS slope of centroid·b̂, whole-window (equil=0 for matsoa long runs); never `longWindowSpeedXY` | `ExplicitHmmDimerGlidingHarness.java:48`; `ExplicitCompleteMatHarness.java:845` | — | FROZEN (methodology) |
| invalid / solverFail | non-zero **voids the result** (reported every run) | `ExplicitHmmDimerGlidingHarness.java:297,439` | — | governance rule |
| warm-up / burn-in | matsoa long-run equilibration = 0 (whole-window); gliding sweeps warmup 0.20 s | `run_coltol_regime_arbiter.sh:6` | — | FROZEN (methodology) |
| run duration | matsoa 40000 × 2.5e-6 = **0.1 s**; gliding sweep 60000 × 1e-5 = 0.6 s | sweep scripts | — | FROZEN (assay) |

**dt convergence (`docs/DT_CONVERGENCE_FINDINGS.md`).** The lumped gliding dt=1e-5 is an accuracy **ceiling**,
not a converged value (+20 % collapses bound-motor count 2.5×); the cause is explicit stiff-spring cross-bridge
overshoot feeding the slip exponential; the open lever is a **cross-bridge sub-step / implicit integrator** (a
future task). The canonical explicit path runs 4× finer (2.5e-6) and is bending-stability-frozen, with a
documented ≤12 % binding under-sampling residual. ⇒ dt is **frozen by necessity** on the canonical path;
fully closing the residual is an OPEN — NUMERICAL item.

---

## 15. Assay-specific configuration

| assay / harness | fixture params | code |
|---|---|---|
| **Step-size (virtual tweezers)** — `LaserTrapHarness` (CPU-only) | trap stiffness {0.02, 0.05, 0.10} pN/nm, default 0.05; k_eff=kL+kR (two-trap COM); axial pretension 5 nm/side | `LaserTrapHarness.java:48,64,72` |
| **Force-clamp lifetime** — `Sm4ForceLifetimeHarness` (CPU-only) | prepared states {RIGOR=NUC_NONE, ADP=NUC_ADP, CYCLING=NUC_ADPPI}; force ladder default {0,3,6,10} pN, ±dir; censoring horizon maxDwell 20 ms; trap kAx=kTr=0.05; ATP via atpScale (ATP-free for ADP arm) | `Sm4…:119,121,192,135-143` |
| **Blind SM4 study** | 14-force ladder 0–25 pN; n=300 (500 near turnover); FXA=fixed-anchor (rigid, σ_F=0) + FXB=explicit-s2-l40 (compliant); 69,500 events; realized-force clamp + honest right-censoring; arms/fixtures never pooled | `docs/SM4_BLIND_STUDY_FINDINGS.md` |
| **Gliding density sweeps** | ATP saturating (LT cycle on); filament 11-seg (dimer) / 12-seg (single); z-coverslip wall 2.0 pN/nm; free binding, no cull; ρ grids (see manifest/`PARAMETER_DEPENDENCY_MATRIX.csv`); single-head heads/µm², dimer molecular/µm² | matsoa sweep docs |

Density grids (canonical long-run): single-head + HMM-dimer GPU long = {100,150,200,250,300,400,500,600,700,
750,1000,1500,3000}, seeds 101–104, 40000 steps × 2.5e-6 = 0.1 s. Gliding lumped canonical =
{100,250,500,1000,2000(,4000,6000,8000 coltol8)}, seeds 0–2, 60000 × 1e-5.

---

## 16. Cross-references

Freeze rationale per parameter → `PARAMETER_FREEZE_CLASSIFICATION.md`. Machine-readable →
`PARAMETER_PROVENANCE_TABLE.csv`, `CANONICAL_PARAMETER_MANIFEST.json`. Dependency (parameter × completed
result) → `PARAMETER_DEPENDENCY_MATRIX.csv`. The short pre-freeze open list → `OPEN_BIOPHYSICAL_PARAMETERS.md`.
The ten decision-document answers + the no-tuning firewall → `MODEL_FREEZE_DECISION.md`.
