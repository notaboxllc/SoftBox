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

**Status: stopped at the pilot checkpoint by direction.** Stage 0 (audit), Stage 1 (19/19 validation gates) and
Stage 2 (duration pilot, 16 arms) are complete; the n = 8 production campaign and the controls were **not**
launched, and the pilot was converted into an analysis-only mechanism study (§5A). All 16 arms are complete,
device-resident, and carry **zero invalid states, zero solver failures, zero rate-cap warnings**.

1. **The ATP condition transfers into the model through one number, with no new biochemistry.** `nucParams[1]`
   (`atpOn`, NONE→ATP) is the sole [ATP]-dependent transition; it is a pseudo-first-order hazard frozen at
   2.0×10⁴ s⁻¹ for saturating ATP, and a linear scaling interface was already declared in-repo. The absolute
   axis is anchored on the repository's own declared saturating condition (2 mM, Rossi et al. 2012), so no rate
   constant is invented or fitted; every record also carries the effective hazard in s⁻¹, so the whole study can
   be relabelled under a different anchor by a pure rescale (§2.5).

2. **Gliding transfers, and it is the solid result.** v_even falls **25.7×** monotonically from the reference to
   5 µM (−4.19 → −0.163 µm/s) with tight seed agreement (1.15–1.32×), placing 5 and 10 µM inside the
   experimentally reported 0.1–0.5 µm/s band **with nothing tuned**. The engagement account is equally clean:
   bound population 4.5 → 34.7, rigor occupancy 0.08 → 0.94, residence 1.1 → 21.1 ms, 100 % ATP-triggered
   detachment, and a pre-stroke lifetime invariant at ~100 µs.

3. **Rotation does not follow, and that is a real torque statement, not an observable artifact.** Ω_odd stays
   between −40 and −71 rad/s across a 400× ATP range, and **measured rotation closes against independently
   accumulated torque at 1.033 ± 0.031 with 8/8 sign agreement** (§5A.4). Turns-per-µm therefore rises from
   −2.53 ± 0.28 at the reference to −58.8 ± 18.9 at 5 µM purely because v_even collapses. In plain terms: **ATP slows translation appropriately, but
   rotation and translation stop scaling together** — interpretation class B.

4. **The plateau is not established at n = 2.** The across-ATP variation in both Ω_odd and τ_odd is *smaller
   than* the seed-to-seed scatter within a single concentration (ratio 1.12 and 0.92); 9/16 arms fail
   window-stability; the ε-even rotational background reaches 1.08× the odd signal; and every n = 2 confidence
   interval includes zero. The direction and order of the pitch shift are supported; its magnitude is not.

5. **Two defects were found by the analysis, and one of them matters for anyone reading the records.** A
   *diagnostic* field (`qOmega`/`omegaPred`) divided whole-filament torque by one-segment drag, understating
   closure by exactly NSEG = 12 — no claim ever used it, and it is fixed. A genuine *analysis* artifact was
   found in the episode-impulse route: right-censoring of bound episodes rises 0.28 % → 16.6 % as ATP falls and
   removes precisely the longest, highest-impulse episodes, which is why that route closes to 2 % at the
   reference and inverts sign at 5 µM. The flux/impulse compensation is therefore coherent but **unproven**.

6. **Recommendation: a targeted extension to n = 4, then stop** (§5A.11). n = 4 is where Ω_odd itself becomes
   resolved at every ATP (95 % half-width 44 rad/s against |Ω_odd| ≈ 55); ~11 h. The *plateau* question needs
   n ≈ 16 (~76 h) and should be reserved for publication work, and only if ATP-independence of chiral torque is
   a claim the paper intends to make. Three cheap instrumentation fixes should precede any extension — two are
   already applied, and the third (eight per-head reduction fields) is the difference between "cancellation and
   puller/dragger not diagnosable" and a decisive mechanism test.

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

**Decision: 200 ms, common to every condition.** Log: `RUN_LOGS/lowatp/stage2_pilot_gpu.txt`; driver log
`RUN_LOGS/lowatp/stage2_driver.txt`.

### 5.1 Design — nested prefixes of ONE trajectory

The pilot runs **one** trajectory per (ATP, ε sign, seed) at the longest pilot duration and reads every shorter
window out of that **same** trajectory as a genuine prefix `[0, W]`, each with the same 25 % equilibration
convention the production estimator uses. So the nested comparison isolates *duration* and nothing else — it
can never be confounded by a different realization — and it costs nothing beyond the longest run. The dense
prefix trace is also written to `<id>.trace.tsv`, so any other sub-window can be re-derived offline without
rerunning anything.

2 matched seeds, both ε signs, at 5 / 10 / 20 µM and the reference; nested windows 20 / 50 / 100 / 200 ms.

### 5.2 A first pass at 100 ms was rejected, on evidence

A 100 ms pilot was run first and **abandoned**: at 5 µM the ε-ODD roll rate was still growing across its
nested windows (−19.2 → −68.6 → −111.1 rad/s), i.e. 100 ms is only ~5 bound lifetimes there. Evidence
retained under `RUN_LOGS/lowatp/prelim_100ms/`. The pilot was relaunched at 200 ms, where its arms double as
production records. The 200 ms run's own 100 ms prefix reproduces the abandoned run to −111.17 vs −111.10
rad/s, confirming trajectory reproducibility across the relaunch.

### 5.3 The binding condition — 5 µM (seed-paired, n = 2)

| window | v_even µm/s | Ω_odd rad/s | turns/µm | \|disp\| µm | Δ vs previous window (v / Ω / turns) | G3 half-vs-full | roll R² |
|---|---|---|---|---|---|---|---|
| 20 ms | −0.1086 | −55.24 | −101.06 | 0.0016 ✗ | — | 22.8 % ✗ | 0.488 |
| 50 ms | −0.0793 | −29.28 | −59.59 | 0.0030 ✗ | 26.9 / 47.0 ✗ / 41.0 % ✗ | 22.8 % ✗ | 0.452 |
| 100 ms | −0.1615 | −58.24 | −73.92 | 0.0121 ✗ | 50.9 / 49.7 ✗ / 19.4 % | 11.0 % ✓ | 0.867 |
| **200 ms** | **−0.1551** | **−57.09** | **−60.65** | **0.0233 ✓** | **4.0 / 2.0 / 18.0 % ✓** | **14.0 % ✓** | **0.830** |

**G1 (≥100 completed stroke episodes per matched ±ε seed pair):** 370 (seed 101) and 476 (seed 102). ✓

At 200 ms v_even and Ω_odd move only **4 %** and **2 %** from the previous window — an order of magnitude
inside the 25 % requirement — while at 100 ms the displacement gate fails outright (0.0121 < 0.02 µm) and
Ω_odd swings 50 %. The roll fit quality rises from R² 0.45 to 0.83–0.87 between 50 and 100 ms and is stable
thereafter, so no startup transient dominates the fitted slope at 200 ms (G5), and the bound population is
stationary from ~50 ms onward (G6).

**Mechanistic cross-check that the ATP mapping is doing what Stage 0 derived:** measured bound residence at
5 µM is **20.8–21.3 ms** across all four arms, against the predicted 1/atpOn = 20 ms rigor wait plus the ~1 ms
remainder of the cycle.

### 5.4 Duration decision

5 µM is the slowest, hardest condition; the higher concentrations converge faster (shorter bound lifetime,
faster gliding), so the shortest duration passing every gate at 5 µM is the shortest **common** duration.
**200 ms is used for every condition**, so no ATP-specific durations and no separate common-window analysis
are needed. Equilibration stays at the preregistered 25 % (= 50 ms ≈ 2.4 bound lifetimes at 5 µM); the pilot
gave no evidence that a longer fixed equilibration is required. No 50 µM bridge condition was triggered — the
20 µM → reference gap is spanned by a monotone, well-resolved ladder (§7).

---

## 5A. Pilot torque-mechanism analysis

**Analysis-only. No parameter was changed and no arm was rerun.** The study was stopped at the pilot
checkpoint by direction; the n=8 production campaign was **not** launched. All numbers below come from the
16 completed pilot arms.

Tooling: `scripts/lowatp_torque_mechanism.py` (this section), `scripts/lowatp_pilot_report.py` (inventory and
reuse), `scripts/lowatp_analysis.py` (primary ladder). Outputs: `RUN_LOGS/lowatp/torque_mechanism_full.txt`,
`RUN_LOGS/lowatp/pilot_checkpoint_report.txt`, `RUN_LOGS/lowatp/pilot_primary_analysis.txt`,
`RUN_LOGS/lowatp/torque_mechanism/*.png`, `RUN_LOGS/lowatp/lowatp_d200ms_{per_seed,summary}.csv`.

### 5A.1 The question

The pilot means suggested that gliding speed changes strongly with ATP while the ε-odd **rotation rate** stays
roughly constant. Is that apparent rotational plateau a real torque plateau, a flux/impulse compensation,
saturation or cancellation among bound motors, axial–rotational decoupling, or an artifact?

### 5A.2 STEP 1 — record inventory and what is analysable

All **16** arms are atomically complete (temp-file + rename, `COMPLETE` sentinel), 4 ATP × 2 ε × 2 seeds, all
device-resident GPU with no fallback, **zero invalid states and zero solver failures**, zero rate-cap
warnings. Configuration is invariant across every arm: η = 0.01, dt = 2.5×10⁻⁷ s, duration 0.2 s,
equilibration 0.25, measSteps 600 000, mirror = +1. Effective hazards: 2000 µM → 2.0×10⁴ /s, 20 → 200,
10 → 100, 5 → 50.

| availability | quantities |
|---|---|
| **Directly stored** | `tau` (mean TOTAL axial torque = time-average of the signed sum over bound heads); `omega`, `omegaFit`, `turns`, `rollR2`, `qOmega`; `glide`, `measSteps`, `dt`; `avgBound`; bound-head and all-head nucleotide occupancy; `nEp`, `nCensored`, `epRate`, `strokeRatePerS`, `bindsPerS`, `detachPerS`; `detachAtp`/`detachRigor`/`detachOther`/`ruptureEvents`/`rateCapWarns`; `jPre`/`jStroke`/`jEarly`/`jLate` (mean per-**episode** phase-resolved axial angular impulse); `preLifeS`, `postLifeS`, `residenceS` |
| **Exactly reconstructable** | `gammaRoll_segment = tau·qOmega/omega` (verified to give **one identical value** across all 16 arms); `gammaRoll_filament = NSEG · gammaRoll_segment`; cumulative unwrapped body-fixed roll vs time (4001-sample `<id>.trace.tsv` per arm); full- and late-window roll slopes and R²; odd impulse per second, per attachment, per stroke; odd torque per bound head; cumulative binds/strokes/detachments/bound-steps vs time |
| **Unavailable without new instrumentation** | signed **per-head** axial torque ⇒ positive/negative torque sums, contributing-head counts, per-head magnitude, torque-by-nucleotide-state; **per-head axial force** ⇒ puller/dragger classification; per-head residence conditioned on torque sign |

The class-stratified fields (`dep*`/`bnd*`/`fpr*`/`fab*`/`tau*`/`bind*`/`str*`) are all **zero** here — they
populate only for a heterogeneous S2 lawn, and this study uses a homogeneous 40 nm lawn.

⇒ **STEP 5 is possible only at the episode-phase level; STEP 6 is not possible at all.** No per-head
decomposition is inferred from aggregates anywhere below.

### 5A.3 STEP 2 — is the rotational plateau real?

Per-seed, ε signs kept separate (rad/s; roll in rad accumulated over the 150 ms measurement window):

| [ATP] | seed | Ω(+ε) | Ω(−ε) | Ω_even | **Ω_odd** | rev/s odd | roll | R²(+) | R²(−) |
|---|---|---|---|---|---|---|---|---|---|
| 2000 | 101 | −146.06 | −33.83 | −89.95 | **−56.12** | −8.93 | 11.86 | 0.977 | 0.699 |
| 2000 | 102 | −150.73 | +22.66 | −64.04 | **−86.69** | −13.80 | 11.62 | 0.972 | 0.461 |
| 20 | 101 | −63.56 | −7.77 | −35.66 | **−27.90** | −4.44 | 5.65 | 0.876 | 0.078 |
| 20 | 102 | −119.11 | +37.96 | −40.57 | **−78.53** | −12.50 | 11.64 | 0.974 | 0.750 |
| 10 | 101 | −22.38 | +11.58 | −5.40 | **−16.98** | −2.70 | 2.07 | 0.363 | 0.195 |
| 10 | 102 | −61.30 | +66.29 | +2.50 | **−63.80** | −10.15 | 9.85 | 0.888 | 0.895 |
| 5 | 101 | −71.20 | +21.58 | −24.81 | **−46.39** | −7.38 | 6.91 | 0.893 | 0.623 |
| 5 | 102 | −78.86 | +57.93 | −10.47 | **−68.40** | −10.89 | 10.39 | 0.968 | 0.866 |

| [ATP] | Ω_odd mean ± SEM | seed spread | sign | Ω_even mean | \|even\|/\|odd\| |
|---|---|---|---|---|---|
| 2000 | −71.41 ± 15.29 | 1.54× | same | −76.99 | **1.08** |
| 20 | −53.22 ± 25.32 | 2.82× | same | −38.12 | 0.72 |
| 10 | −40.39 ± 23.41 | 3.76× | same | −1.45 | 0.04 |
| 5 | −57.40 ± 11.01 | 1.47× | same | −17.64 | 0.31 |

**All 8 seed-pairs are same-signed (negative)** at every concentration, and Ω_odd spans only −40 to −71 rad/s
across a **400×** range in [ATP]. But:

- **9 of 16 arms fail window stability** (late-vs-full slope > 25 % or roll R² < 0.5).
- The **ε-even rotational background is large** — at the reference it *exceeds* the odd signal
  (|even|/|odd| = 1.08). Ω_odd is therefore a difference of two comparable, noisy raw rotations.
- **The across-condition variation is smaller than the within-condition seed scatter**: pooled
  within-condition SD = 27.78 rad/s against an across-ATP spread of only 31.02 rad/s ⇒ **ratio 1.12**.
- At n = 2 the *t*-quantile is 12.706, so every 95 % CI is enormous and **includes zero** at every
  concentration (e.g. 5 µM: [−197, +82]).

⇒ On this evidence the ATP-independence of rotation is **suggestive but underpowered**, not established.

**Gate-evaluator defect — found, fixed, re-verified.** The harness's automated Stage-2 verdict initially
printed "EXTEND the pilot", because gate G4 (Ω and turns/µm within 25 % of the adjacent window) failed at the
*high*-ATP end. The preregistered rule carries the clause *"unless their confidence intervals include zero"*,
which the `reportAtpPilot` evaluator did not implement — and a window-to-window stability gate applied to an
**unresolved** quantity carries no information. With the clause implemented
(`omegaCiIncludesZero`, two-sided 95 % *t*), re-running the evaluator over the **same 16 records** (16 reused,
0 newly run — pure re-analysis, no simulation) gives:

```
[ATP] =    5 µM : shortest window passing all gates = 200 ms
[ATP] =   10 µM : shortest window passing all gates = 200 ms
[ATP] =   20 µM : shortest window passing all gates =  50 ms
[ATP] = 2000 µM : shortest window passing all gates =  20 ms
⇒ SHORTEST COMMON PRODUCTION DURATION = 200 ms
```

confirming the duration decision independently. The lesson generalises: at n = 2 every Ω_odd CI includes zero,
so **G4 cannot discriminate at this sample size at any concentration** — which is itself part of the §5A.9
verdict.

### 5A.4 STEP 3 — torque–rotation closure

`gammaRoll` per segment reconstructs to **2.701612×10⁻²⁵ N·m·s**, with **exactly one distinct value across all
16 arms** — confirming both that the reconstruction is exact and that the configuration is invariant.

> **Bookkeeping defect found — in a DIAGNOSTIC field only.** `ChiralSiteHarness.gammaRollOf()` returns
> `fil.bRotGam[0]`, the roll drag of **one** segment, while `r.tau` is the torque on the **whole 12-segment**
> filament. The stored `omegaPred = tau/gamma_SEGMENT` therefore over-predicts by NSEG and `qOmega`
> under-reports closure by the same factor. Roll drag is additive in length (the harness's own `dragAudit`
> states this), so the correct whole-filament drag is **NSEG · gamma_segment = 3.241935×10⁻²⁴ N·m·s**.
> **No claim in this study or in the viscosity campaign uses `qOmega`/`omegaPred`** — every rotation result
> uses `omegaFit`, the direct LS slope of measured body-fixed roll. Fix recorded in §5A.9.

| [ATP] | seed | τ(+ε) | τ(−ε) | τ_even | **τ_odd** | Ω_odd measured | Ω_odd predicted | meas/pred |
|---|---|---|---|---|---|---|---|---|
| 2000 | 101 | −3.3333e-22 | +3.7309e-23 | −1.4801e-22 | −1.8532e-22 | −56.12 | −57.16 | **0.982** |
| 2000 | 102 | −3.4336e-22 | +1.5971e-22 | −9.1823e-23 | −2.5153e-22 | −86.69 | −77.59 | **1.117** |
| 20 | 101 | −1.1840e-22 | +4.9699e-23 | −3.4351e-23 | −8.4050e-23 | −27.90 | −25.93 | **1.076** |
| 20 | 102 | −3.4997e-22 | +1.5772e-22 | −9.6130e-23 | −2.5384e-22 | −78.53 | −78.30 | **1.003** |
| 10 | 101 | +7.8168e-25 | +9.2644e-23 | +4.6713e-23 | −4.5931e-23 | −16.98 | −14.17 | **1.199** |
| 10 | 102 | −1.8370e-22 | +2.5642e-22 | +3.6358e-23 | −2.2006e-22 | −63.80 | −67.88 | **0.940** |
| 5 | 101 | −1.2796e-22 | +1.8230e-22 | +2.7168e-23 | −1.5513e-22 | −46.39 | −47.85 | **0.970** |
| 5 | 102 | −2.6321e-22 | +1.8944e-22 | −3.6886e-23 | −2.2633e-22 | −68.40 | −69.81 | **0.980** |

**Closure: measured/predicted = 1.033 ± 0.031 (n = 8), range 0.940–1.199, sign agreement 8 of 8.**

This is the single strongest result in the pilot. The measured body-fixed rotation reproduces the
independently accumulated axial torque divided by the filament's own roll drag, to ~3 %, at every ATP and
every seed. **The rotation observable is therefore not an artifact, and the rotational plateau is exactly a
torque plateau — Ω_odd is flat because τ_odd is flat, not because of any observable defect.**

Independent route — cumulative odd angular impulse per second vs mean odd torque:

| [ATP] | seed | J_odd/s | τ_odd | ratio | censoring |
|---|---|---|---|---|---|
| 2000 | 101 | −1.8888e-22 | −1.8532e-22 | **1.019** | 0.40 % |
| 2000 | 102 | −2.4629e-22 | −2.5153e-22 | **0.979** | 0.17 % |
| 20 | 101 | −2.2076e-22 | −8.4050e-23 | 2.627 | 4.37 % |
| 20 | 102 | −3.8928e-22 | −2.5384e-22 | 1.534 | 3.75 % |
| 10 | 101 | +7.4041e-23 | −4.5931e-23 | **−1.612** | 8.91 % |
| 10 | 102 | −7.9653e-22 | −2.2006e-22 | 3.620 | 6.77 % |
| 5 | 101 | −4.2179e-23 | −1.5513e-22 | 0.272 | 15.79 % |
| 5 | 102 | +2.3511e-21 | −2.2633e-22 | **−10.388** | 16.03 % |

The impulse route closes to **within 2 % at the reference** and degrades monotonically with censoring until
it inverts sign at low ATP — see §5A.5.

### 5A.5 STEP 4 — event flux vs angular impulse per event

Directly recorded flux and lifetimes (per-seed means):

| [ATP] | episodes/s | attach/s | strokes/s | ATP-detach/s | rigor-rupture/s | avgBound | residence | **pre-stroke life** | **post-stroke life** |
|---|---|---|---|---|---|---|---|---|---|
| 20 | 3472 | 3635 | 3627 | 3610 | **0** | 21.8 | 6.04 ms | 101.6 µs | 5.75 ms |
| 10 | 2323 | 2535 | 2525 | 2520 | **0** | 29.0 | 11.47 ms | 103.9 µs | 10.53 ms |
| 5 | 1410 | 1690 | 1689 | 1643 | **0** | 34.7 | 21.09 ms | **98.7 µs** | **17.72 ms** |

Nucleotide occupancy of bound heads:

| [ATP] | NONE (rigor) | ATP | ADP·Pi | ADP |
|---|---|---|---|---|
| 20 | 0.8175 | 0.0000 | 0.0169 | 0.1656 |
| 10 | 0.8921 | 0.0000 | 0.0091 | 0.0988 |
| 5 | 0.9404 | 0.0000 | 0.0049 | 0.0546 |

Event flux falls **2.46×** from 20 → 5 µM while residence rises **3.5×**, and the entire increase is in the
**post-stroke limb** (3.1×) — the pre-stroke lifetime is invariant at ~100 µs, reproducing the
viscosity campaign's finding that the pre-stroke dwell is chemistry-limited. **Rigor mechanical rupture
contributes exactly zero detachments at every condition** (it is OFF on this lineage, §2.7), so detachment is
100 % ATP-triggered throughout.

Episode-phase decomposition of the odd impulse (the only cancellation-relevant split available):

| [ATP] | J_pre odd | J_stroke odd | J_early odd | **J_late odd** | J_total odd |
|---|---|---|---|---|---|
| 20 | +3.74e-27 | −4.95e-28 | −3.79e-27 | **−8.61e-26** | −8.67e-26 |
| 10 | +9.76e-27 | −5.87e-28 | −2.80e-27 | **−1.31e-25** | −1.25e-25 |
| 5 | −9.73e-28 | −1.03e-27 | −3.67e-27 | +7.29e-25 | +7.24e-25 |

The odd impulse is carried **almost entirely (≈99 %) in the late post-stroke/rigor limb**, which is exactly
the limb that lengthens as ATP falls. At the one concentration step where the estimator is still usable
(20 → 10 µM) the compensation is close to exact: episode rate ÷1.49 against J_late ×1.52, i.e. flat to ~2 %.

**But this cannot be promoted to a demonstrated mechanism, because the estimator is ATP-dependently biased:**

| [ATP] | mean censored episodes |
|---|---|
| 2000 µM | **0.28 %** |
| 20 µM | 4.23 % |
| 10 µM | 7.97 % |
| 5 µM | **16.56 %** |

Right-censoring rises monotonically (**59×**) as ATP falls, and the censored episodes are precisely the
**longest** ones — the ones carrying the dominant late-phase impulse. The bias therefore removes the dominant
contribution exactly where the compensation claim would be made. Its footprint is visible directly: the 5 µM
+ε seed-102 arm returns `jLate = +1.675e-24`, opposite in sign to every other 5 µM arm and contradicting the
sign of **its own** τ_odd and Ω_odd (both negative), and it alone flips the 5 µM ensemble mean positive.
`tau` and `omegaFit` are per-step accumulations over all bound heads and are **never censored**, which is why
they close at 1.033 while the impulse route inverts.

⇒ The residence-time-compensation picture is **mechanistically coherent and consistent with the data, but
not established by it.**

### 5A.6 STEP 5 — torque cancellation and saturation

**Not diagnosable from this pilot.** Only the **net** axial torque is stored. Positive-torque sum,
negative-torque sum, contributing-head counts, per-head magnitude and torque-by-nucleotide-state are not
recorded and must not be inferred from an aggregate. What can be said: odd torque **per bound head** is
−7.56e-24 (20 µM), −4.13e-24 (10 µM), −5.46e-24 (5 µM) — no monotone trend, and dominated by seed scatter, so
even the load-sharing sub-question is unresolved. The episode-phase split above is the only cancellation-
adjacent decomposition available.

### 5A.7 STEP 6 — axial puller/dragger vs torque sign

**Not possible from this pilot** — per-head axial force and per-head axial torque are not stored. Not rerun,
per instruction. Exact schema additions for a later targeted study (all are per-step reductions over values
the measurement loop **already computes**, so no new physics and no kernel change is required):

```
tauPos, tauNeg        summed positive / negative per-head axial torque
nTauPos, nTauNeg      counts of positive / negative torque heads
tauByState[4]         axial torque summed by nucleotide state
nByState[4]           bound-head counts by nucleotide state
tauPull, tauDrag      axial torque summed over heads with F_ax*v_fil > 0 and < 0
nPull, nDrag          counts of axial pullers / draggers
fAxPull, fAxDrag      summed axial force in each class
residPull, residDrag  residence accumulated in each class
```

Per-head axial torque is already computed at `ChiralSiteHarness` ~line 1930 (`ChiralSiteSystem.axialTorque`)
and per-head axial force just below it (the `bondData` projection); only the signed reductions and these
record fields are missing.

### 5A.8 STEP 7 — artifact checks

| check | result |
|---|---|
| rev/s from direct roll slopes, not via turns-per-distance | **confirmed** — `omegaFit` is the LS slope of transported body-fixed roll; rev/s = Ω/2π |
| 2π conversion correct | **confirmed** — max \|roll_span/2π − stored turns\| = 0.0351 rev across all arms |
| roll-unwrapping / branch cuts | **structurally impossible** — largest observed mean per-step roll increment 2.22×10⁻³ rad vs a π branch cut |
| a few abrupt jumps dominating the slope | **no** — max single-interval \|ΔRoll\| is near-constant at 0.33–0.44 rad on *every* arm (the uniform Brownian roll background). The one case where it exceeds the net excursion (10 µM −ε seed 101, frac 2.98) is an arm whose **net** roll is only 0.13 rad, i.e. a near-null arm, not an anomalous jump |
| lab-frame tumble contaminating body-fixed roll | **excluded by construction** — the estimator transports the material yVec onto the plane ⊥ the current û (rotation-minimizing frame); the legacy lab-referenced readout is computed separately and is never used for a claim |
| startup transient | equilibration 25 % (50 ms ≈ 2.4 bound lifetimes at 5 µM); roll R² rises from ~0.45 to 0.83–0.97 between 50 and 100 ms and is stable after |
| one seed dominating the mean | **yes, for the impulse route** (5 µM +ε seed 102, §5A.5). Not for Ω_odd or τ_odd, where both seeds are same-signed at every ATP |
| ε-even rotational background | **large** — \|even\|/\|odd\| = 1.08 at the reference, 0.72 at 20 µM |
| cancellation from subtracting two noisy large rotations | **a real risk**, quantified by the ratio above; this is the main reason n = 2 cannot settle the plateau |
| full- vs late-window disagreement | **9 of 16 arms** fail (>25 % or R² < 0.5) |
| identical analysis window at every ATP | **confirmed** — duration 0.2 s, equil 0.25, measSteps 600 000 identical on all 16 arms |
| ε sign / seed pairing | **confirmed** — every pair is (p, n) at the same seed and same ATP; unmatched signs are never combined |
| reduced displacement at low ATP compromising the roll fit | **no** — the roll fit does not depend on displacement; R² is reported per arm |
| torque and roll time-aligned | **by construction** — both accumulated in the same per-step loop over the same window |
| reference uses identical estimator definitions | **confirmed** — same code path, only `nucParams[1]` differs |

Plots: `RUN_LOGS/lowatp/torque_mechanism/` — cumulative body-fixed roll for every arm (1), τ_odd vs ATP (2),
Ω_odd vs ATP (3), measured vs predicted Ω_odd (4), flux vs impulse per event (5), bound population and torque
per bound head (6, 6b). Plots 7 and 8 (positive/negative torque components; puller/dragger) are **not
producible** — see `PLOTS_7_8_UNAVAILABLE.txt`.

### 5A.9 STEP 8 — decision

**Primary: E — the apparent plateau is NOT ESTABLISHED (statistically), together with A as the
best-supported physical reading, and explicitly NOT F for the rotation observable.**

- **Not F for rotation.** Torque–rotation closure is 1.033 ± 0.031 with 8/8 sign agreement. Rotation
  faithfully reflects torque; there is no observable defect. *(One genuine bookkeeping defect was found —
  `qOmega`/`omegaPred` divide whole-filament torque by one-segment drag — but it is a diagnostic field that no
  claim uses. A second, real analysis artifact **was** found, in the impulse route only: ATP-dependent
  censoring, §5A.5.)*
- **A is the physical reading the data support.** Because closure holds, the flat Ω_odd *is* a flat τ_odd:
  −2.18, −1.69, −1.33, −1.91 ×10⁻²² N·m across a 400× ATP range, all same-signed on both seeds.
- **E is the honest statistical verdict.** The across-ATP spread is **smaller than the within-condition seed
  scatter** (ratio 1.12 for Ω_odd, 0.92 for τ_odd); 9/16 arms fail window stability; the ε-even background
  reaches 1.08× the odd signal; and every n = 2 CI includes zero. "Flat" and "varying by up to 2×" are not
  distinguishable here.
- **B is coherent but unproven.** Flux falls 2.46×, residence rises 3.5× entirely in the post-stroke limb,
  and ≈99 % of the odd impulse sits in that limb — at 20 → 10 µM the product is flat to ~2 %. But the
  per-episode impulse estimator is ATP-dependently censored (0.28 % → 16.6 %) in exactly the direction that
  removes the dominant term, so this cannot be promoted.
- **C and D are not diagnosable** — net-only torque, no per-head force. Schema additions given in §5A.7.

### 5A.10 Limitations from n = 2

Two seeds give 1 degree of freedom: the 95 % *t*-multiplier is 12.706, so CIs are ~±250–320 rad/s on an
Ω_odd of ~55 rad/s. Seed-to-seed magnitude spread within one concentration is 1.47–3.76×, larger than the
entire across-ATP variation. Nothing in this section is a significance claim; the sign consistency (8/8) and
the closure (8/8) are the only results that survive at this sample size, and both are qualitative-plus-ratio
statements rather than powered tests.

### 5A.11 Recommendation — targeted extension to n = 4, then stop

Power computed from the observed pooled within-condition SD (Ω_odd 27.78 rad/s; τ_odd 9.26×10⁻²³ N·m):

| goal | requirement | n = 2 | n = 4 | n = 8 | n = 16 |
|---|---|---|---|---|---|
| Ω_odd itself resolved ≠ 0 (95 %, *t*) | half-width < ~55 rad/s | 249.6 ✗ | **44.2 ✓** | 23.2 ✓ | — |
| resolve the across-ATP τ_odd spread at 3 SEM | spread/SEM ≥ 3 | 1.30 ✗ | 1.85 ✗ | 2.61 ✗ | **3.69 ✓** |

**Recommendation: option 2 — a targeted extension to n = 4, and no further work now.**

- n = 4 is the point at which **the twirl itself becomes resolved at every ATP** (95 % half-width 44 rad/s
  against |Ω_odd| ≈ 55). That converts the pilot's strongest qualitative result — same sign on every
  seed-pair at every concentration — into a statement with an interval attached. Cost: 2 additional seeds ×
  4 ATP × 2 ε = **16 arms ≈ 10.8 h** on the measured 40.6 min/arm.
- **Do not go to n = 8 for the plateau question** — it still falls short (2.61 < 3). The plateau claim needs
  **n ≈ 16**, i.e. ~112 further arms ≈ 76 h. Reserve that for publication work, and only if the ATP-
  independence of chiral torque is a claim the paper actually intends to make.
- **Before any extension, make three cheap fixes** (all analysis/instrumentation, no physics):
  1. correct `gammaRollOf` to the whole-filament roll drag (or divide `tau` by segment count) so `qOmega` is
     a usable closure diagnostic;
  2. implement the preregistered "unless the CI includes zero" clause in the G4 gate evaluator;
  3. add the eight per-head reduction fields of §5A.7 — they are the difference between "C and D not
     diagnosable" and a decisive mechanism test, and they cost one per-step reduction each.
  With (3) in place, an n = 4 extension would answer the cancellation and puller/dragger questions **that this
  pilot could not touch**, which is worth more than the extra seeds alone.
- Also record for any future campaign: stamp **build identity** (source hash / class mtime) into the
  provenance line, not just `git HEAD` — commits made while runs are in flight left three different `rev=`
  stamps on arms produced by one identical binary (§5A.12).

### 5A.12 Reusability of the 16 pilot arms

All 16 arms share identical [ATP]-independent configuration, duration, equilibration, record schema and seed
definition, and were produced by **one compiled binary** (source mtime 2026-07-27T23:19:02, classes 23:19:49,
first record 2026-07-28T00:00:35, last 08:09, no rebuild in between). The five differing `rev=` stamps
(`f5af18cc` ×7, `f19749a7` ×4, `40843aa2` ×2, `38d1f91b` ×2, `9683598a` ×1) record **HEAD at record-write
time**, not build time, and `git diff` confirms **no `softbox/*.java` changed** across those commits — they
were shell-script, analysis-script and Markdown commits made while runs were in flight.

⇒ **All 16 arms are reusable as-is** at this duration; an n = 4 or n = 8 extension needs only the additional
seeds, provided no Java source changes in the meantime. If the §5A.11 fixes (1) and (3) are applied first,
the existing 16 arms remain valid for Ω/τ/flux analysis (the fixes are additive and do not alter any stored
quantity), but the new per-head fields would exist only in the new arms.

---

## 6. Stage 3 onward — NOT RUN (study stopped at the pilot checkpoint)

The n = 8 production ladder, the ε = 0 null, the low-ATP mirror control and the adaptive seed extension were
**not launched**, by direction: the study was stopped at the pilot checkpoint and converted to an
analysis-only investigation of the 16 completed arms (§5A). No GPU run was started after that point.

The configuration those stages *would* have used is the one the 16 pilot arms already carry, and is recorded
here because it is what any future extension must match exactly:

| item | value | source |
|---|---|---|
| solvent viscosity η | 0.01 Pa·s | viscosity campaign's lowest trustworthy η |
| timestep dt | 2.5×10⁻⁷ s | the mechanically scaled `dt(η) = dt₀·η/η₀` |
| filament | 12 segments, Brownian **ON** (all four channels) | `TwoBodyConverterMotor.G4_NSEG` |
| free S2 | homogeneous 40 nm | canonical L40 geometry |
| motor density | 400 heads/µm² | canonical |
| actin sites | discrete, surface bond ON, native lattice | canonical |
| converter skew | **linear ramp**, ε = +15° and −15° | the confirmed mechanism |
| target-zone / old interface skew / roll spring | OFF | task requirement |
| rigor mechanical rupture | **OFF** (`RUPTURE_MODE = 0`) | §2.7 — as the viscosity campaign had it |
| duration / equilibration | 200 ms / 25 % | Stage 2 decision (§5) |
| runner | GPU device-resident, monitored, **no fallback** | `run_gpu_monitored.sh` |

Controls not run, and what that costs: without the **ε = 0 null** the estimator's own noise floor is not
measured directly (the ε-even background in §5A.3 is the available proxy, and it is large); without the
**low-ATP mirror** the chirality of the low-ATP twirl is inherited from the viscosity campaign's mirror
control at the reference condition rather than demonstrated at low ATP. Neither gap affects any statement
made in §5A, because §5A makes no chirality claim.

## 7. Results — what the pilot establishes

**Gliding transfers cleanly and is the solid result.** Across a 400× ATP range, seed-paired v_even:

| [ATP] µM | atpOn /s | v_even µm/s (n = 2) | seed spread | vs reference |
|---|---|---|---|---|
| 2000 (reference) | 2.0×10⁴ | −4.19 | 1.15× | 1.00 |
| 20 | 200 | −0.650 | 1.23× | 0.155 |
| 10 | 100 | −0.271 | 1.32× | 0.065 |
| 5 | 50 | −0.163 | 1.32× | 0.039 |

Gliding falls **25.7×** from reference to 5 µM, monotonically, with tight seed agreement (1.15–1.32×) — and
5, 10 µM land inside the experimentally reported 0.1–0.5 µm/s band with **nothing tuned**. The engagement
account is equally clean and monotone (§5A.5): bound population 4.5 → 34.7, rigor occupancy 0.08 → 0.94,
residence 1.1 → 21.1 ms, detachment 100 % ATP-triggered at every condition, pre-stroke lifetime invariant at
~100 µs.

**Rotation does not follow.** Ω_odd stays between −40 and −71 rad/s across the same 400× range (§5A.3), and
because torque–rotation closure holds at 1.033 ± 0.031 (§5A.4), that is a flat **torque**, not an observable
artifact. Turns-per-µm therefore rises from −2.53 ± 0.28 at the reference to −58.8 ± 18.9 at 5 µM **entirely
because v_even falls**, not because rotation speeds up.

**Interpretation class: B (gliding transfers but pitch shifts)** is what the point estimates describe — ATP
slows translation appropriately while rotation and translation stop scaling together. But at n = 2 the
across-ATP variation in Ω_odd and τ_odd is smaller than the within-condition seed scatter (§5A.3), so the
*magnitude* of the pitch shift is not established, only its direction and order.

## 8. Controls — not run

See §6. The ε = 0 null and the low-ATP mirror were not launched.

## 9. Numerical and scientific health

Across all 16 arms: **zero invalid states, zero solver failures, zero rate-cap warnings, zero
rigor-rupture events** (the channel is off), 100 % ATP-triggered detachment, no silent CPU fallback (every
arm device-resident, and a lowering failure throws by construction), no record collisions (ids carry ATP,
duration, ε sign, seed and mirror flag; atomic temp-file + rename), exact seed accounting (16 of 16 expected
records, 1 reused + 15 newly run), and identical intrinsic parameters at every ATP — only `nucParams[1]`
differs, verified by the Stage 1 identity gates.

Confirmed **not** altered by lowering ATP (Stage 1 gates C5–C7, and the invariance check in §5A.2): viscosity,
timestep, Brownian amplitudes, motor density, S2 mechanics, stroke angle, binding geometry.

**One machine-level event:** the first 200 ms pilot attempt died at CUDA error 719 cascading into a SIGSEGV in
`libcuda.so`, followed by a host crash and reboot. Evidence archived per CLAUDE.md
(`gpu-crash-case-20260728-001308.tar.gz`; no Xid in the previous-boot kernel log). The atomic-record design
lost nothing — the completed arm survived intact, no partial record was written, and the campaign resumed
from it under `scripts/run_lowatp_campaign.sh`, completing the remaining 15 arms in one attempt with no
retries. A slower run is not a failed run, and this was not a failed run.

## 10. Experimental comparison, limits, work not done, next recommendation

### 10.1 Experimental comparators (POST-HOC — comparisons, never optimization targets)

| quantity | experiment | this study |
|---|---|---|
| [ATP] | ≈5–20 µM | 5, 10, 20 µM (+ the frozen reference) |
| gliding speed | ≈0.1–0.5 µm/s | **−0.163 µm/s (5 µM), −0.271 (10 µM)** — inside the band, untuned |
| mean myosin-II twirling pitch | ≈0.47 ± 0.20 µm | **−0.396 µm at the reference** (per-seed −0.444, −0.357) — *inside the experimental band*; but **−0.017 µm at 5 µM** (per-seed −0.025, −0.013) |
| pitch vs filament velocity | comparatively insensitive | **not reproduced** — pitch tracks 1/v_even because Ω_odd is flat |

**The sharpest comparison in the study, and it is a negative one.** The model reproduces the experimental
pitch *at saturating ATP* (−0.396 µm against 0.47 ± 0.20 µm) — but the experiment was performed at
**5–20 µM**, and at those concentrations the model's pitch collapses to ≈0.017–0.11 µm, a factor of 4–30
below the measurement. So the model matches the reported pitch **at the wrong ATP condition**, and the
condition transfer that would have been the strongest possible outcome does not hold for rotation. It does
hold for gliding. This is reported as found; nothing was adjusted in response to it.

Nothing was changed after seeing output. No ATP, viscosity, skew, density or detachment parameter was
adjusted to improve agreement, and no ATP response curve was fitted back into the motor.

### 10.2 Limits carried by this result

1. **The µM axis inherits the frozen `atpOn`.** The implied second-order constant is ≈1.0×10⁷ M⁻¹s⁻¹ against
   a classic actomyosin ≈1–2×10⁶ M⁻¹s⁻¹ (§2.5). Under the alternative anchor every µM label multiplies by 5
   with identical physics; the effective hazard in s⁻¹ is reported everywhere so the relabelling is trivial.
2. **Rigor mechanical rupture is OFF on this lineage** (§2.7), so these arms are the **pure ATP-limited**
   limit. Stage 1A′ shows the rupture-ON crossover lies inside the experimental window (73 % rupture at
   5 µM), so a rupture-ON ladder would differ materially — that is a separate, declared study, not a defect
   here.
3. **η = 0.01 Pa·s, not water.** The viscosity study established that this lineage's absolute speeds and
   engagement are viscosity-dependent, and that the canonical 0.1 Pa·s suppresses twirling below
   detectability. All statements here are at 0.01 Pa·s.
4. **ε = ±15° is a causal perturbation, not a measured biological skew.** The pitch comparison is an
   order-of-magnitude statement about a *mechanism*, not an estimate of a structural angle.
5. **Single filament per arm.** Seed-to-seed spread is the dominant statistical term; the seed is the
   independent unit throughout.
6. **Temperature stitch.** The cycle is a multi-temperature literature compilation (canonical inventory);
   `atpOn` in particular is a Lymn & Taylor ~20 °C lower bound on a rate "too fast to measure".

### 10.3 Experiments deliberately NOT run

- A **rupture-ON low-ATP ladder** (the natural companion to Stage 1A′) — it changes the frozen motor
  configuration and would break comparability with the viscosity campaign.
- **ATP below 5 µM or above 20 µM** other than the reference, except the 50 µM bridge if the pilot triggered
  it (§5) — outside the experimental window and not needed for the transfer test.
- The **5–15° skew map**, the **Vilfan reconstruction**, **viscosity below 0.01 Pa·s**, **density**, **S2**,
  **catch/slip recalibration**, **force-law redesign**, and any **contractile-network** campaign — all
  explicitly out of scope for this task.
- A **mirror control at the reference [ATP]** is run only if the reference twirl is itself resolved; the
  viscosity campaign already established that at the canonical η it is not, and a mirror test of an
  unresolved quantity reverses nothing.

### 10.4 Next recommendation

**Extend selected conditions to n = 4, then stop** — see §5A.11 for the power calculation this rests on, and
§5A.7 for the instrumentation that would make the extension answer the questions this pilot could not.

Do **not** launch the n = 8 ladder for the plateau question: it still falls short (spread/SEM 2.61 < 3). Either
stay at n = 4 for a resolved-existence statement, or go to n ≈ 16 deliberately for a resolved-plateau statement.
