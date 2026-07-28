# Low-[ATP] Gliding and Twirling — Condition-Transfer Study

**The single controlling report for this task.** Audit, implementation, validation, duration pilot, production
ladder, analysis, controls, health and closeout all live here; there is no separate audit/pilot/results file.

- **Date:** 2026-07-27/28 · branch `gpu-mat-bottlenecks-explicit-singlehead`
- **Raw records, CSVs, plots, scripts, manifests:** `RUN_LOGS/lowatp/` (run logs) and
  `RUN_LOGS/chiral_sites/lowatp/` (atomic per-arm records + tidy CSV)
- **Related, deliberately NOT reopened:** `docs/VISCOSITY_SENSITIVITY_FINDINGS.md` (the completed viscosity
  campaign, whose operating point this study inherits verbatim). Its conclusions stand unchanged; this report
  only cross-references it.

---

## 0. Executive conclusion

*(filled in after the production analysis is frozen — see §7)*

---

## 1. Scientific question

The experimental myosin-II twirling assay slowed filament translation for optical tracking by working at
**≈5–20 µM ATP**. This study asks whether the **frozen** SoftBox motor — the one that already produces ordinary
gliding and mirror-controlled chiral twirling at the canonical operating point — also produces appropriate
emergent behaviour when **only that experimentally controlled assay variable** is changed.

Nothing about the motor is retuned and no filament velocity is prescribed. The ATP condition enters through the
model's **own** nucleotide-cycle law.

The strongest available outcome is *not* a fitted match. It is: **one intrinsic parameter set** producing
ordinary high-ATP gliding, slower gliding at low ATP, mirror-controlled twirling, and a twirling pitch of the
experimental order, when only [ATP] changes.

---

## 2. Stage 0 — ATP source and provenance audit

### 2.1 The active chemistry kernel on this assay

The explicit-S2 / discrete-site / linear-converter-ramp gliding assay used by the viscosity campaign is driven by
`ChiralSiteHarness` → `ExplicitCompleteMatHarness.stepGlidingCPU` / `buildGlidingGraph`. Its chemistry task is a
single kernel, identical on both runners:

| runner | call site |
|---|---|
| CPU | `ExplicitCompleteMatHarness.java:735` — `NucleotideCycleSystem.cycleLymnTaylor(...)` |
| GPU | `ExplicitCompleteMatHarness.java:820` — `tg.task("chem", NucleotideCycleSystem::cycleLymnTaylor, ...)` |

Both consume the **same** `mot.nucParams` `FloatArray`. There is no second ATP path, no host-side ATP branch and
no per-runner ATP special case.

**Rupture-mode audit (load-bearing, see §2.7).** `ExplicitCompleteMatHarness` selects among
`cycleLymnTaylor` / `cycleLymnTaylorRigor` / `cycleLymnTaylorRuptureAll` from `RUPTURE_MODE`, whose *canonical
default 1* is applied **only in that class's own `main` argument parsing** (`:1554-1556`). `ChiralSiteHarness`
has its own `main` and never sets `RUPTURE_MODE`, so this whole lineage — including the completed viscosity
campaign — runs at the field defaults `RUPTURE_MODE = 0`, `RIGOR_ON = false`, i.e. **plain `cycleLymnTaylor`
with rigor mechanical rupture OFF**. This was confirmed from source and is echoed in every run banner.

### 2.2 States and transitions in the active cycle

`NucleotideCycleSystem.cycleLymnTaylor` (`:406-473`), one uniform draw `u` per motor per step:

| # | transition | rate slot | value | [ATP]-dependent? |
|---|---|---|---|---|
| 1 | `NONE → ATP` (**detachment** when bound; ATP binding releases the rigor head) | `nucParams[1]` `atpOn` | 2.0×10⁴ s⁻¹ | **YES — the only one** |
| 2 | `ATP → ADP·Pi` hydrolysis, on-filament | `nucParams[2]` `onATP` | 100 s⁻¹ | no (first-order) |
| 3 | `ATP → ADP·Pi` hydrolysis, off-filament (the ~10 ms detached recovery) | `nucParams[3]` `offATP` | 100 s⁻¹ | no (first-order) |
| 4 | `ADP·Pi → ADP` Pi release / **power stroke**, on-filament | `nucParams[4]` `onPi` | 1.0×10⁴ s⁻¹ | no |
| 5 | `ADP·Pi → ADP`, off-filament (disabled ⇒ detached head stays primed) | `nucParams[5]` `offPi` | 0 | no |
| 6 | `ADP → NONE` ADP release, load-modulated by the Guo–Guilford catch–slip `g(F)` | `nucParams[6]` `onADP` | 1.0×10³ s⁻¹ × g(F) | no |
| 7 | `ADP → NONE`, off-filament | `nucParams[7]` `offADP` | 1.0×10³ s⁻¹ × g(F) | no |

Release logic: a **bound** head that ends the step in `NUC_ATP` detaches that step (`:459-466`). On this lineage
that is the **sole** detachment pathway — there is no break-force cap, no emergency release and (rupture being
off) no mechanical channel.

**This was traced through the actual state and probability flow, not inferred from parameter names.** In
particular `nucParams[2]`/`[3]` are named "ATP" but are the *hydrolysis of already-bound ATP* and are
first-order/intrinsic; they are **not** concentration-dependent.

### 2.3 What kind of parameter `atpOn` is

`atpOn` is a **pseudo-first-order hazard**, not a second-order association constant and not an assay scaling
factor. Provenance, all in-repo:

- v1 `Env.java:836` `atpOnMyo_init = 2e4; // s^-1`, in the block commented *"State-change rates (shaded path from
  Howard 2001, Table 14-2)"*.
- `docs/canonical_freeze/CANONICAL_MOTOR_PARAMETER_INVENTORY.md:97` — `nucParams[1] atpOn`, 2.0×10⁴ s⁻¹,
  *"pseudo-1st-order (saturating [ATP])"*, Lymn & Taylor 1971 (rabbit skeletal HMM, ~20 °C; "too fast to
  measure", 2e4 = accepted lower bound), class **B, FROZEN**.
- `docs/MOTOR_PARAM_PROVENANCE.md:208` states the low-[ATP] mechanism verbatim:
  `atpOn_s 2.0e4 # NONE→ATP (saturating [ATP]; reduce for low-[ATP])`.

### 2.4 The concentration the current default represents, and the existing interface

The frozen default is documented as **saturating ATP**. A **linear [ATP] scaling interface already exists** and
is reused rather than reinvented:

- `Sm4ForceLifetimeHarness.java:147-151, 238-241` — `-atpscale`, documented *"EXPLICIT [ATP] SCALING
  (pseudo-first-order): effective atpOn ← atpOn · atpScale ([ATP]/[ATP]_default). Default 1.0 ⇒ no-op
  (byte-identical)"*, with `-atpfree` ≡ `atpScale = 0`.
- `CANONICAL_MOTOR_PARAMETER_INVENTORY.md:120` classifies the *mechanism* as class **D (declared interface),
  CONDITIONALLY FROZEN**, with the governance note that *"the default ATP condition per assay is frozen with its
  assay"*.
- `docs/SM4_ADP_ATPFREE_PROTOCOL_FINDINGS.md` establishes that `atpOn = 0` ≡ `[ATP] = 0` cleanly removes
  ATP-triggered detachment on exactly this `cycleLymnTaylor` path.

**No numeric `[ATP]_default` is recorded anywhere in the repository, and no second-order ATP association
constant exists in code or docs.** That is the one gap this study must close to put an absolute µM axis on the
ladder.

### 2.5 The reference-concentration choice (conservative, recorded, not fitted)

Blocker test #2 asks whether an absolute concentration would require *inventing or fitting a new biochemical
rate constant*. It does not, provided the anchor is taken from a concentration the repository already declares
rather than from a new rate constant:

> **`ATP_REF_UM = 2000 µM (2 mM)`** — the saturating-ATP condition of **Rossi et al. 2012**, the measurement
> `docs/GLIDING_TARGET_25C.md` adopts as *the* canonical gliding comparison ("4.20 ± 0.07 µm/s … 25 °C, 2 mM
> ATP"). The frozen `atpOn = 2.0×10⁴ s⁻¹` and the frozen linear mechanism are used unchanged; the anchor only
> labels the axis.

Mapping actually implemented:

```
atpOn([ATP])  =  2.0e4 s^-1  ×  ( [ATP] / 2000 µM )          (pseudo-first-order, linear, no fitted curve)

  [ATP] =    5 µM  ->  atpOn =    50 /s     (mean rigor wait 20 ms)
  [ATP] =   10 µM  ->  atpOn =   100 /s     (10 ms)
  [ATP] =   20 µM  ->  atpOn =   200 /s     ( 5 ms)
  [ATP] = 2000 µM  ->  atpOn = 2.0e4 /s     (50 µs) — the frozen reference, exact identity
  [ATP] =    0 µM  ->  atpOn =     0 /s     — the established ATP-free path
```

**Stated limitation (not tuned away).** The implied second-order constant is
`k_ATP = 2e4 s⁻¹ / 2 mM = 1.0×10⁷ M⁻¹s⁻¹`, roughly 5–10× the classic actomyosin `K₁k₊₂ ≈ 1–2×10⁶ M⁻¹s⁻¹`. That
is a property of the **frozen** rate constant, inherited, not introduced here. Consequence: the µM labels on
this ladder are conservative in the sense that a given µM label corresponds to a **faster** ATP hazard than the
classic constant would give; under the alternative anchor (`k_ATP = 2×10⁶ M⁻¹s⁻¹` ⇒ `[ATP]_ref = 10 mM`) every
µM label would multiply by 5 (5 µM → 25 µM, 20 µM → 100 µM) with **identical physics**. Every record and every
table therefore carries the **effective hazard `atpOn` in s⁻¹** beside the µM label, so the whole study can be
re-read under any other anchor by a pure relabelling.

### 2.6 CPU and GPU consumers of the ATP-dependent parameter

`mot.nucParams` is a single `FloatArray` passed by reference to the `chem` kernel on both runners
(§2.1). On the device it is uploaded once (`FIRST_EXECUTION`) with the rest of the chemistry parameter block
and read identically by the PTX kernel. Setting `nucParams[1]` **after scene construction and before
`packExMat` / graph construction** therefore reaches both runners through one path, with **no kernel edit, no
buffer resize and no TaskGraph change** — the `applyEta` / `applyS2Lawn` data-only precedent.

### 2.7 Interaction with canonical rigor rupture

`cycleLymnTaylorRigor` (`:502-594`) resolves ATP binding and mechanical rigor rupture as **competing hazards by
a single-uniform band partition** of the *same* draw: `u ∈ [0, pAtp)` → ATP, `u ∈ [pAtp, pAtp+pRig)` → rupture.
The ATP band keeps its exact flag-off threshold, so the partition degrades continuously to the single ATP
pathway as `k_rigor → 0`, and scaling `atpOn` narrows the ATP band while leaving the rupture band's *rate* law
untouched. The competing-hazard treatment is therefore **preserved by construction** under any `atpOn`.

**On this lineage rupture is OFF (§2.1).** Two consequences, both reported rather than engineered away:

1. Production runs here keep the *exact* configuration of the completed viscosity campaign, so the reference-ATP
   arm is directly comparable to it. Turning rupture on would have changed the frozen motor configuration
   mid-study and broken that comparability — the governance list explicitly forbids changing rigor-rupture
   parameters. **Conservative choice: leave it exactly as the controlling starting point had it.**
2. With rupture off, ATP binding is the *only* way out of the bound state, so low [ATP] necessarily lengthens
   bound lifetime with no mechanical escape. That is a real prediction of the frozen configuration, and it is
   what makes the rigor-arrest interpretation class (§7, class D) a live possibility. Stage 1A therefore
   additionally reports a **fixture-only diagnostic** of the same ATP ladder with canonical rigor rupture
   *available*, so the competing channel's size is quantified without changing production.

### 2.8 Does ATP affect off-actin recovery?

Structurally the `NONE → ATP` block in `cycleLymnTaylor` is **not** gated on `bound`, so a *free* head sitting in
`NUC_NONE` would also wait for ATP. In this cycle's steady state that state is essentially unreachable: a head
leaves the filament by entering `NUC_ATP`, recovers `ATP → ADP·Pi` at the [ATP]-independent `offATP = 100 s⁻¹`,
and then **stays primed** in `ADP·Pi` because `offPi = 0`. So the detached recovery limb is
**[ATP]-independent**, and the only [ATP]-sensitive dwell is the bound rigor wait. Gate 1B measures this
directly.

### 2.9 Legacy runners that assume a fixed ATP rate

One found: `Sm4ForceLifetimeHarness.java:549` emits the effective rate into its JSON as a **hardcoded**
`2.0e4 * c.atpScale` rather than reading `nucParams[1]`. Harmless there (its base *is* 2e4) but it is a
duplicate source of truth. The implementation added by this study deliberately does **not** copy that pattern:
`applyAtp` reads the as-built `nucParams[1]` and scales it, so there is no second copy of the base rate.

### 2.10 RNG draw count and event ordering

**Unchanged.** `cycleLymnTaylor` draws exactly **one** wang-hash uniform per motor per step
(`h = wangHash((m*1000003) ^ (step*999983) ^ (seed*7919) ^ 0x4E55)`), unconditionally and independently of state
or of any rate. `atpOn` enters only as the comparison **threshold** `u < atpOn·dt`. Therefore changing [ATP]:

- changes **no** RNG draw count, **no** salt, **no** stream ordering;
- changes **no** kernel structure (identical PTX; the same `chem` task with the same arguments);
- leaves the counter-based RNG bit-identical CPU↔GPU by construction.

### 2.11 Audit verdict

A defensible mapping exists in source, provenance and an already-declared interface; no new biochemical rate
constant is invented or fitted; and the change is an assay-level concentration input, not a change to intrinsic
motor chemistry. **None of the blocker conditions is met — proceed.**

---

## 3. Implementation

Additive, data-only, default-absent. **One** number is written after scene construction.

```java
// ChiralSiteHarness.applyAtp(Glide2D G)   — called from build(), immediately after applyEta(G)
ATP_ON_EFF = G.mot.nucParams.get(1);       // as built = the frozen saturating-ATP rate
if (ATP_UM < 0) return;                    // feature ABSENT  -> exact early return, byte-identical
double s = ATP_UM / ATP_REF_UM;
if (s == 1.0) return;                      // explicit reference -> exact identity
G.mot.nucParams.set(1, (float)(G.mot.nucParams.get(1) * s));
```

Properties, each gated in Stage 1:

- scales **only** the physically ATP-dependent hazard `nucParams[1]`;
- preserves every other kinetic rate, every stiffness, every drag/Brownian buffer and all geometry;
- preserves rigor rupture and its competing-hazard band partition (untouched code path);
- preserves RNG ordering exactly (§2.10);
- **feature absent ⇒ byte-identical** to every existing path (exact early return);
- **explicit reference ATP ⇒ exact identity**; **[ATP] = 0 ⇒ the established ATP-free behaviour**;
- identical through CPU and GPU production paths (one shared buffer, §2.6);
- **no fitted ATP response curve** anywhere.

CLI (all default-off):

```
-atp-uM <c>            assay [ATP] in µM (absent ⇒ exact no-op)
-atp-fixtures          Stage 1 validation gates (CPU)
-atp-pilot             Stage 2 nested-window duration pilot
-atp-map               Stage 3 production ladder     (-atp-mirror ⇒ mirrored lattice)
-atp-null              Stage 5A ε = 0 achiral null
-atp-report / -atp-mirror-report      re-report from stored records
-atp-points / -atp-duration-ms / -atp-nested-ms / -atp-eps-deg
```

Records: `RUN_LOGS/chiral_sites/lowatp/atp_u<µM>_d<duration_µs>_[m_]{p,n,z}_<seed>.tsv`, atomic
(temp-file + rename), one per (ATP × ε × seed), with a **separate schema and directory** from the viscosity
campaign so no completed viscosity record is read, rewritten or invalidated.

---

## 4. Stage 1 — ATP interface validation

**19 gates, 19 PASS, 0 FAIL.** Logs: `RUN_LOGS/lowatp/stage1_fixtures_cpu.txt` (A/B/C, CPU) and
`RUN_LOGS/lowatp/stage1D_equiv_gpu.txt` (D, monitored GPU).

### 4.1 A — bound-rigor ATP-detachment latency (production lineage, rigor rupture OFF)

A population of bound heads held in `NUC_NONE` at zero load; the only exit is ATP binding.

| [ATP] µM | atpOn /s | mean latency (ms) | 1/atpOn (ms) | rel err | f(ATP) | f(censored) |
|---|---|---|---|---|---|---|
| 0 | 0 | — | ∞ | — | — | **1.0000** |
| 5 | 50.0 | 19.482 | 20.000 | 0.026 | 1.0000 | 0.0051 |
| 10 | 100.0 | 9.851 | 10.000 | 0.015 | 1.0000 | 0.0005 |
| 20 | 200.0 | 4.960 | 5.000 | 0.008 | 1.0000 | 0.0007 |
| 2000 (ref) | 2.00×10⁴ | 0.0500 | 0.0500 | 0.0000 | 1.0000 | 0.0000 |

Latency follows the concentration law established in Stage 0 across **400×** in rate (residual error is the
horizon truncation of the sampled exponential, largest where the horizon is tightest). `[ATP] = 0` gives
**100 % censoring** — the established ATP-free behaviour. **PASS.**

### 4.2 A′ — the same ladder with canonical rigor rupture AVAILABLE (fixture-only diagnostic)

Not the production configuration (§2.7) — this quantifies the competing channel that the canonical
rupture-ON configuration *would* introduce, so the production result can be read in context.

| [ATP] µM | mean latency (ms) | f(ATP-triggered) | f(rigor rupture) |
|---|---|---|---|
| 0 | 7.118 | 0.0000 | **1.0000** |
| 5 | 5.243 | 0.2717 | **0.7283** |
| 10 | 4.178 | 0.4285 | **0.5715** |
| 20 | 2.983 | 0.5938 | **0.4063** |
| 2000 (ref) | 0.0497 | 0.9937 | 0.0063 |

Mechanically meaningful: the unloaded rigor-rupture rate is `k₀·(αc+αs) = 140 s⁻¹` ⇒ 7.14 ms, matching the
ATP-free row to 0.3 %. **The crossover sits inside the experimental window** — at 5 µM a rupture-ON motor
would detach mechanically **73 %** of the time, at 20 µM **41 %**. This is the competing-hazard prediction the
task anticipated; on the frozen production lineage the channel is absent, so the production ladder below is
the **pure ATP-limited** limit and the rupture-ON case is quantified here rather than guessed at.

### 4.3 B — off-actin cycle completion (recovery limb must be [ATP]-independent)

| [ATP] µM | ATP→ADP·Pi latency (ms) | 1/offATP (ms) | forbidden transitions | end primed in ADP·Pi | analytic |
|---|---|---|---|---|---|
| 0 / 5 / 10 / 20 / 2000 | 9.866 (all identical) | 10.000 | **0** | 0.9940 | 0.9933 |

The detached recovery limb is **exactly [ATP]-independent** (identical to all printed digits across a 400×
rate range), **zero** forbidden transitions, and the primed fraction matches the analytic
`1 − exp(−T·offATP)` rather than a hand-set threshold. No chemical stall other than the expected rigor ATP
wait. **PASS.**

### 4.4 C — identity gates

| # | gate | result |
|---|---|---|
| C1 | explicit reference [ATP] == feature ABSENT (atpOn identical) | PASS |
| C2 | [ATP] = 0 reproduces the established ATP-free path (atpOn == 0) | PASS |
| C3 | 5 µM ⇒ atpOn = 50 /s exactly (2e4 × 5/2000) | PASS |
| C4 | every OTHER nucleotide rate (and dt) bit-identical at 5 µM | PASS |
| C5 | catch-slip / binding `kinParams` bit-identical at 5 µM | PASS |
| C6 | every drag (= viscosity) and Brownian-amplitude source buffer bit-identical at 5 µM | PASS |
| C7 | filament + motor geometry bit-identical at 5 µM | PASS |
| C8 | 300-step CPU trajectory at explicit reference [ATP] == feature ABSENT, **bit-identical** | PASS |
| C9 | the production chemistry kernel **diverges** between reference and 5 µM on bound rigor heads | PASS |
| C10 | **no head reaches `NUC_NONE` within 300 steps (75 µs)** ⇒ C8's bit-identity is expected, not a masked no-op | PASS |

C10 is worth stating explicitly: the first drafts of C9 tested divergence on a 300-step *full-scene*
trajectory and found bit-identity. That is not a defect — `atpOn` is read **only** in `NUC_NONE`, and the path
bind → stroke → ADP release takes ~1 ms, so in 75 µs the rigor state is never occupied (measured peak: **0**
bound rigor heads). C9 was therefore moved to where the parameter can act, and C10 records the reason. Neither
gate was relaxed.

### 4.5 D — CPU/GPU equivalence

Two parts, because exactness is attainable on different horizons (see the `runAtpEquiv` javadoc):

**D1 — full production graph, device-resident, this study's exact configuration**
(12-segment filament, filament Brownian ON, linear converter ramp, ε = 15°, η = 0.01, dt = 2.5×10⁻⁷ s),
4000 device steps, no fallback:

| [ATP] µM | bindMism | siteMism | nucStateMism | max abs dAzim | max abs dFilCoord (µm) | bit-close until | bound C/G | rigor C/G |
|---|---|---|---|---|---|---|---|---|
| 2000 (ref) | **0** | **0** | **0** | 0.00e+00 | 2.64e-07 | end of run | 9 / 9 | 2 / 2 |
| 5 | **0** | **0** | **0** | 0.00e+00 | 5.83e-07 | end of run | 22 / 22 | **15 / 15** |

Exact agreement in binding, site selection, discrete nucleotide state and azimuth, on both runners, at both
ATP conditions, over the entire window — and the trajectory never left the bit-close band.

**D2 — the ATP decision itself, device vs host, 200 000 steps each:**

| [ATP] µM | atpOn /s | state + boundSeg mismatches | ATP-detached host / device |
|---|---|---|---|
| 2000 (ref) | 2.00×10⁴ | **0** | 25 / 25 |
| 20 | 200.0 | **0** | 51 / 51 |
| 10 | 100.0 | **0** | 160 / 160 |
| 5 | 50.0 | **0** | 313 / 313 |
| 0 | 0 | **0** | 0 / 0 |

Zero mismatches in discrete state, bound state and transition cause at every step of every condition. As
expected from §2.10 — the counter-based RNG is bit-identical by construction and [ATP] moves only a
comparison threshold, so the ATP pathway carries **no** new CPU/GPU divergence channel. **PASS.**

**D1's 5 µM row is also the first physics signal:** after 1 ms, 5 µM already carries **22 bound heads of which
15 are in rigor**, against **9 bound / 2 rigor** at the reference. Low ATP is loading the bound population with
rigor heads, exactly as the mechanism predicts.

---

## 5. Stage 2 — duration pilot

*(filled in from `RUN_LOGS/lowatp/stage2_pilot_gpu.txt`)*

---

## 6. Stage 3 — production configuration and results

*(filled in from `RUN_LOGS/lowatp/stage3_*.txt`)*

---

## 7. Stage 4 — primary analysis and interpretation

*(filled in)*

---

## 8. Stage 5 — controls

*(filled in)*

---

## 9. Stage 6 — numerical and scientific health

*(filled in)*

---

## 10. Experimental comparison, limits, work not done, next recommendation

*(filled in)*
