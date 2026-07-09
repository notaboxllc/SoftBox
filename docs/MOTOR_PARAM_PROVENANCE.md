# Motor-parameter isoform-provenance AUDIT + physics/numerical classification + isoform-file schema

**2026-06-29. READ-ONLY / DESIGN-ONLY. No code edits, no file created or wired, no default flipped, nothing
committed (this doc is the deliverable). `BoA-v1ref` byte-clean (read-only). Locates where skeletal drifted slow and
specifies the vetted `Skeletal_Myosin` contents + schema; the wiring + the turnover fix are a SEPARATE follow-up.**

Scope: the active gliding motor = **config-1 / perp-head, flat θ=0**, calibrated stack `-perphead -headtilt 0 -kon
2e5 -tauavg 0.5 -xcatch 2.5 -atprecharge`, dt=1e-5. jba's standing decision: **keep the motor SKELETAL**; generalize
across isoforms by swapping **measured constants** in an isoform file, not by editing mechanism.

---

## TL;DR
- **The catch-slip is genuinely SKELETAL** (Guo & Guilford 2006 + Stam 2015: xCatch 2.5 nm, xSlip 0.40 nm, αCatch/αSlip
  0.92/0.08 = 11.5, ~6 pN peak); the **stall** (5 pN, Finer 1994) and **step** (~6.96 nm) are skeletal-scale. The
  **mechanics are faithful-skeletal.**
- **The turnover is NOT skeletal — it's the Myo2 regime, and it's a MECHANISM choice, not a drifted constant.** The
  Howard-2001 cascade carries a **fast biochemical detachment, ADP→NONE = 1e3/s (≈1 ms, skeletal-compatible)** — but
  the `-atprecharge` model (the slip-direction-task correctness fix) **deletes it** and makes the force-based
  catch-slip bond the **sole** release. The catch-slip base kOff = 100/s (realized unloaded floor ~141/s ⇒ ~7 ms
  bound-time) then plays the role of the cycling detachment. **V₀ = step × unloaded-rate = 6.96 nm × 143/s ≈ 1.0
  µm/s = the Myo2 regime**, ~5–8× below skeletal (5–8 µm/s). Step and κ are skeletal; **the entire gap is the
  detachment rate / bound-time.**
- **The mechanical-bond-vs-biochemical-cycling-rate conflation is REAL and locatable:** using the catch-slip bond
  unbinding (a Guo & Guilford single-molecule *mechanical* lifetime regime, base 100/s) as the *sole biochemical
  cycling detachment* — discarding the Howard biochemical ADP→NONE (1e3/s) — is the root of the slow turnover. It
  also explains the slip-direction finding (the catch sits at its unloaded floor for every head: the realized rate is
  kOff-base-dominated because the loads are sub-pN). **Located, not fixed (the fix is the follow-up).**
- **The headline INTERNAL INCONSISTENCY:** a **skeletal** catch peak (6 pN) sitting on a **Myo2-slow** turnover (7 ms).
- **Thesis-relevant mechanism flags (cannot be made isoform-swappable by a pure parameter swap — they live in CODE):**
  (1) the **detachment PATHWAY** (biochemical cycle ADP→NONE vs catch-slip-only) is a code/flag branch
  (`-atprecharge`/`ATP_RELEASE` / which release kernel), not a parameter; (2) the **J1-strain-as-load** choice
  (`forceDotFil` = lever strain, not the actual axial pin force) is a modeling decision in `CrossBridgeSystem`. Both
  are called out below.

---

## PART A — isoform-provenance table (read-only)

Values are the **current vetted skeletal** stack. "Set in code" gives file:line. Provenance is the literature/source
anchor (v1 `Env.java` comments + the phase-2 calibration docs). `Env.java` = `~/Code/BoA-v1ref/boxOfActin/Env.java`.

### Kinetics
| constant | value | set in code | isoform / source | faithful-skeletal? |
|---|---|---|---|---|
| **kOff** (catch-slip base) | **100 /s** | `MotorStore.java:249` (kinParams[0]); `Env.java:822` | catch-slip **base rate** — NOT a separately-measured detachment; v1 default 100, "duty-calibrated dwell" (4c doc) | **DRIFTED-SLOW (prime suspect)** — realized floor ~141/s ⇒ ~7 ms; skeletal cycling needs ~720–1150/s (~1 ms) |
| αCatch | 0.92 | `MotorStore.java:250` (kinParams[1]); `Env.java:806` | **Guo & Guilford 2006 (PNAS 103:9844); Stam et al 2015** (skeletal) | ✔ skeletal |
| αSlip | 0.08 | `MotorStore.java:251` (kinParams[2]); `Env.java:810` | **Guo & Guilford 2006; Stam 2015** | ✔ skeletal |
| xCatch | 2.5 nm | `MotorStore.java:252`/`setXCatch:294`; `Env.java:814` | **Guo & Guilford 2006** x_c 2.5 nm | ✔ skeletal |
| xSlip | 0.40 nm | `MotorStore.java:253` (kinParams[4]); `Env.java:818` | **Guo & Guilford 2006** x_s 0.40 nm | ✔ skeletal |
| αCatch/αSlip ratio | 11.5 | derived | GG k_c/k_s ≈ 11.7 | ✔ skeletal |
| catch-slip **peak** | ~6.0 pN @ 56.8 ms | emergent (`-csrecal`) | GG ~6.4 pN behavioral target | ✔ skeletal (4c) |
| **kOn** | **2×10⁵ µm⁻¹s⁻¹** | `GlidingHarness.java:58/148`→`setSearchParams:329` (kinParams[14]) | reaction-limited attachment; tuned to 1.5 % single-molecule duty (4c) | **STALE-FLAG** — tuned on config-1 + plain `cycle`; benchmark runs perphead + recharge (BINDING-SURVEY) |
| **atpOn** (NONE→ATP) | 2×10⁴ /s | `MotorStore.java:375` (nucParams[1]); `Env.java` atpOnMyo | **Howard 2001, Table 14-2** (saturating [ATP]) | ✔ generic/skeletal |
| **ATP→ADPPi** (hydrolysis) | 100 /s (~10 ms) | `MotorStore.java:376-377` (nucParams[2,3]); `Env.java` | **Howard 2001, T14-2** | ✔ generic/skeletal |
| **ADPPi→ADP** (powerstroke) | 1×10⁴ /s (~0.1 ms) | `MotorStore.java:378` (nucParams[4]); `Env.java` | **Howard 2001, T14-2** | ✔ generic/skeletal |
| **ADP→NONE** (biochemical detach, load-gated) | **1×10³ /s (~1 ms)** | `MotorStore.java:380-381` (nucParams[6,7]); `Env.java` | **Howard 2001, T14-2** — the fast skeletal-compatible detachment | ✔ skeletal — **BUT DELETED by `-atprecharge` (see Part B)** |
| offFil ADPPi→ADP | 0 | `MotorStore.java:379` (nucParams[5]); `Env.java` | v1 (head re-primes off-fil only) | ✔ |
| **tauAvg** (catch EMA) | 0.5 ms | `GlidingHarness.java:61/151`→`setReleaseForceAvg` (kinParams[16,17]) | Jensen-fix averaging window (4a) | **MURKY** (numerical smoothing vs physical force-integration time) |
| MYO_REBIND_TIME (refractory) | 1×10⁻⁵ s (=1 step) | `MotorStore.java:127` (kinParams[10]) | numerical refractory | numerical |

### Catch/slip auxiliaries & gates
| constant | value | set in code | source | classification preview |
|---|---|---|---|---|
| **myoColTol** (capture radius / reach) | 6 nm | `GlidingHarness.java:291`→`setKinParams` (kinParams[7]); `Env.java:755` | v1 myoColTol; partly weak-binding reach, partly search tolerance | **MURKY** |
| alignTol (motDotFil gate) | −0.4 | `setKinParams` (kinParams[8]); `Env.java:149` | v1 myoMotorAlignWithFilTolerance | geometry gate (physics) |
| **myosinBreakForce** (stiffness cap) | 12 pN (default **OFF** in v2) | `MotorStore.java:266` (kinParams[11,12]); `Env.java:799` | v1 "stiffness safety valve, independent of catch-slip" | **MURKY** (physical break vs numerical valve) |
| candReach (kOn search radius) | 0 (≤ myoColTol ⇒ tight) | `setSearchParams` (kinParams[15]) | weak-binding capture reach (search reformulation) | **MURKY** |

### Mechanics
| constant | value | set in code | isoform / source | faithful-skeletal? |
|---|---|---|---|---|
| **unitary step** | ~6.96 nm | emergent (`MotorStrokeHarness`; gate 2–40 nm) | skeletal working stroke (Finer 1994 ~11 nm; modern ~5–8 nm) | ✔ skeletal-scale |
| **stall / unitary force** | 5.0 pN | `motorStallPN()` = κ·60°/L (`GlidingHarness.java:989`) | **Finer et al 1994** (skeletal ~5 pN) | ✔ skeletal |
| **κ** (J1 torsional) | 3.82×10⁻²⁰ N·m/rad | `GlidingHarness.java:56` | **derived** = F·L/θ with F=5 pN (Finer'94), L=8 nm, θ=60° | ✔ skeletal (force-matched) |
| powerstroke rest angles | 0° (ADPPi) ↔ 60° (cocked) | `CrossBridgeSystem.java:380/551`, `MotorJointSystem` | lever-swing geometry | ✔ (skeletal-scale swing) |
| HEAD_LEN | 20 nm | `MotorStore.java:45` | v1 myosin geometry (sets the step — memory: step ∝ HEAD_LEN) | ✔ skeletal-scale |
| LEVER_LEN | 8 nm | `MotorStore.java:45` | v1 lever geometry | ✔ |
| ROD_LEN | 80 nm | `MotorStore.java:45` | v1 geometry | ✔ |
| PAIRS_FRACMOVE / PERP_FRACMOVE | 0.5 / 0.5 | `GlidingHarness.java:57/71` | dt-robust pin/orientation damping (UNCALIBRATED) | numerical |

### Internal-inconsistency flags
1. **HEADLINE: skeletal catch peak (6 pN) on a Myo2-slow turnover (7 ms).** The release SHAPE/force-sensitivity is
   skeletal (GG); the release RATE is Myo2. Mixing a skeletal mechanical bond with a non-skeletal cycling rate.
2. **Stall anchor split:** v2 mechanical stall = **5 pN** (Finer'94, via κ) while v1 `Env.myosinStallForce` = **6 pN**
   and the calibrated catch **peak** = 6 pN ⇒ peak/stall = 1.2 (4c). Minor, but the stall value is sourced two ways
   (5 vs 6 pN); the isoform file must pick one and cite it.
3. **The fast biochemical detachment is present in the cascade but unused:** ADP→NONE = 1e3/s (Howard, ~1 ms,
   skeletal-compatible) exists in `nucParams[6,7]` but `-atprecharge` removes the cycle's ADP→NONE
   (`NucleotideCycleSystem.cycleNoBoundAtp`) ⇒ the model detaches at the catch-slip rate (~141/s), not 1000/s.
4. **kOn is stale** (BINDING-SURVEY): tuned on config-1 + plain `cycle`; the benchmark runs perphead + recharge ⇒ the
   realized duty ≠ the calibrated 1.5 %.
5. **Velocity is measured at ~100× experimental drag** (aeta = 0.1 Pa·s, MYOSIN_VALIDATION) ⇒ absolute glide speed is
   a numerical artifact; Part B's V₀ = step × rate (drag-independent) is the faithful comparison, not measured µm/s.

---

## PART B — the skeletal turnover diagnosis ("where it drifted slow")

### V₀ arithmetic (drag-independent — the faithful comparison)
The unloaded gliding ceiling V₀ ≈ step / t_on = step × (unloaded detachment rate):
- **Model:** step **6.96 nm**, unloaded detachment **~143 /s** (realized floor; `-csrecal` F=0 → 142.8/s ⇒ 7.0 ms;
  ATP-RECHARGE bound-time 7.1 ms). **V₀ = 6.96 nm × 143/s ≈ 1.0 µm/s.**
- **Skeletal target:** V₀ 5–8 µm/s ⇒ rate = V₀/step = **720–1150 /s** ⇒ bound-time **0.87–1.4 ms**.
- **Myo2:** ~0.7 µm/s.

⇒ **the model sits in the Myo2 regime (~1 µm/s), ~5–8× below skeletal.** The dominant gap is **bound-time / unloaded
detachment** (7.1 ms vs ~1.25 ms, ~5.7×). **NOT the step** (6.96 nm ≈ skeletal) and **NOT κ/stall** (5 pN, Finer'94).
Confirmed: step and force are skeletal; only the detachment rate is wrong.

### Duty
Single-molecule duty (4c) **1.69 % at kOn 2e5**, vs skeletal duty ratio **~5 %** (~3× low). Note the interplay:
raising the detachment rate (the turnover fix) **shortens** t_on and **lowers** duty further unless kOn is co-raised —
so the turnover fix MUST co-adjust kOn (the BINDING-SURVEY stale-calibration flag). The velocity gap is dominated by
t_on (V₀ = step/t_on), but duty is also sub-skeletal.

### The mechanical-vs-biochemical rate verdict
- `kOff = 100/s` is the **catch-slip base rate** (Guo & Guilford / Stam single-molecule **mechanical** unbinding
  regime). GG's zero-load *mechanical* lifetime extrapolates to ~2.7 s (the authors flag it non-physical; the 4c doc
  rejects it). v2 does **not** use 2.7 s; it uses a **duty-fitted 100/s (~10 ms)** — so `kOff` is a **free/duty
  anchor**, not GG-measured and not a measured cycling rate.
- **The conflation is structural, not in the literal kOff value:** with `-atprecharge` the **force-based catch-slip
  bond unbinding is the ONLY detachment** (`cycleNoBoundAtp` drops ADP→NONE; `catchSlipReleaseAvgRecharge` is the sole
  exit). So a **mechanical-bond-lifetime-flavored rate (kOff, ~141/s realized) is doing the job of the biochemical
  cycling detachment.** For skeletal gliding the cycling detachment is the **fast, ~1 ms, ATP-induced, largely
  load-independent** post-ADP-release step — exactly the Howard ADP→NONE = 1e3/s the cascade already has and the
  recharge model discarded.
- **This is the root of the slow turnover AND of the slip-direction finding:** because kOff-base dominates and the
  real loads are sub-pN, the catch-slip runs near its unloaded floor (~141/s) for every head — load-insensitive — so
  it neither selects nor varies with the per-head force (PHASE2_SLIP_DIRECTION_FINDINGS).
- **Fix direction (NOT done here, follow-up):** restore a fast ATP-induced biochemical detachment (~720–1150/s) as the
  cycling exit and demote the catch-slip to the **load-modulation on top** (not the sole exit); co-retune kOn for duty.

---

## PART C — physics / numerical / murky classification + isoform-file schema (design only)

### Classification
**MEASURED-PHYSICS → isoform file** (varies by isoform; each carries a citation):
- mechanics: unitary **step**, **stall/unitary force**, **κ**, powerstroke **rest angles**, **HEAD_LEN / LEVER_LEN /
  ROD_LEN** lever geometry.
- kinetics: **kOn**, **kOff** (catch-slip base), catch **xCatch / xSlip / αCatch / αSlip / peak**, the nucleotide
  cascade **atpOn / ATP→ADPPi (hydrolysis) / ADPPi→ADP (powerstroke) / ADP→NONE (biochemical detach)**, **duty**
  (derived target).

**NUMERICAL / SIM → stays in code/run config** (must NOT vary by isoform):
- **dt**, **PAIRS_FRACMOVE / PERP_FRACMOVE** (dt-robust damping), grid sizes, **OUT_INT**, box geometry
  (matbed/v1box), **DENSITY** (a run condition, not a motor property), **aeta** (already flagged ~100× experimental),
  **MYO_REBIND_TIME** (1-step refractory).

**MURKY → flag for explicit planner classification (NOT silently bucketed):**
- **myoColTol (6 nm capture radius).** *Physics:* the weak-binding capture reach — how close a head's search point must
  come to an actin site to form a bond — is a real biophysical length (~few nm), and would legitimately differ by
  isoform. *Numerical:* it is also the discrete-search tolerance around a single tip point, entangled with the
  geometric search resolution and the segment discretization. **Recommend: isoform file IF interpreted as
  weak-binding reach; code IF interpreted as search tolerance — planner to rule.**
- **The J1-strain-as-load mechanical gain** (`forceDotFil` = signed J1 lever strain, `CrossBridgeSystem.java:383/554`,
  NOT the actual axial pin force). *Modeling choice:* what mechanical quantity the catch reads. The slip-direction
  audit showed it is decoupled from the real axial force and ~0.1 pN (far below the ±1–6 pN catch scale). This is **not
  a swappable scalar** — it is *which quantity* feeds the rate. **Flag as MECHANISM (see below), not a file field.**
- **tauAvg (0.5 ms catch EMA window).** *Physics:* a bond force-sensing integration time (could be isoform-specific).
  *Numerical:* a Jensen-fix smoothing of the discrete-dt thermal `forceDotFil`. **Planner to rule; lean numerical
  unless given a physical force-integration-time source.**
- **myosinBreakForce (12 pN cap, default OFF).** *Physics:* a real rupture force. *Numerical:* v1 describes it as a
  "stiffness safety valve." Default-off in v2 ⇒ currently inert. **Flag.**
- **weak-binding capture reach (candReach, kОn search radius).** Same tension as myoColTol; the search reformulation
  separated the encounter rate (kOn) from the chord physics (myoColTol). **Flag with myoColTol.**

### Isoform-specific-MECHANISM flags (cannot be a pure parameter swap — they live in CODE) — THESIS-RELEVANT
1. **The detachment PATHWAY is a code/flag branch, not a parameter.** Whether detachment is the biochemical cycle
   (`cycle` ADP→NONE, 1e3/s) or the catch-slip-only (`-atprecharge` / `ATP_RELEASE`, which release kernel runs) is
   selected in `GlidingHarness.stepOrig`/`buildPlan`, not by an isoform constant. **This is exactly the
   falsifiability boundary (§6):** if skeletal wants fast biochemical turnover and NMII wants a held mechanical bond,
   that difference is currently *mechanism in code*, not a swappable rate. **The follow-up turnover fix should make the
   cycling-detachment RATE a parameter so the pathway choice collapses into the isoform file.** Flag prominently.
2. **The J1-strain-as-load choice** (`CrossBridgeSystem`): which mechanical quantity the catch reads (lever strain vs
   axial pin force) is mechanism. If an isoform needs a different load-sensing, that is a code change. Flag.

(Everything else — step, force, κ, angles, geometry, all rates, catch params — is a clean parameter swap, so the
generalization claim holds for the *mechanics + catch + cascade*; the open mechanism items are the detachment pathway
and the load-sensing quantity.)

### Proposed schema (DESIGN ONLY — no file created, no read wired)

**`params/Skeletal_Myosin`** — measured biophysical constants ONLY, each with inline provenance. The file IS the
calibration record. Proposed fields = the current vetted skeletal values (⚠ = drifted-slow / stale, the follow-up
fix corrects these; the *current* value is listed so the file is byte-identical on day one):
```
# Skeletal_Myosin — fast skeletal muscle myosin II (the gliding-assay target)
# MECHANICS
step_nm            6.96      # emergent working stroke; skeletal ~5–11 nm (Finer et al 1994, Nature 368:113)
stallForce_pN      5.0       # single-head stall (Finer et al 1994)
kappa_Nmrad        3.82e-20  # DERIVED = stallForce·L/θ  (L=leverLen, θ=60°); keep consistent with stallForce
j1RestUncocked_deg 0.0       # ADP·Pi (pre-stroke)
j1RestCocked_deg   60.0      # post-stroke (cocked); sets the swing
headLen_nm         20.0      # lever/head geometry (v1 myosin)
leverLen_nm        8.0
rodLen_nm          80.0
# CATCH-SLIP BOND (Guo & Guilford 2006, PNAS 103:9844; Stam et al 2015)
xCatch_nm          2.5
xSlip_nm           0.40
alphaCatch         0.92
alphaSlip          0.08
kOff_s             100.0     # ⚠ DRIFTED-SLOW: catch-slip base; realized floor ~141/s ⇒ ~7 ms (Myo2 regime).
                             #   Skeletal cycling detachment ~720–1150/s (~1 ms). Follow-up fix raises effective turnover.
catchPeak_pN       6.0       # emergent (informational); GG behavioral target ~6.4 pN
# NUCLEOTIDE CASCADE (Howard 2001, Mechanics of Motor Proteins, Table 14-2)
atpOn_s            2.0e4     # NONE→ATP (saturating [ATP]; reduce for low-[ATP])
hydrolysis_ATP_ADPPi_s   100.0    # ATP→ADP·Pi (~10 ms recovery)
powerstroke_ADPPi_ADP_s  1.0e4    # ADP·Pi→ADP (~0.1 ms)
biochem_detach_ADP_None_s 1.0e3   # ⚠ ADP→NONE (~1 ms, skeletal-fast) — currently BYPASSED by -atprecharge; the
                                  #   follow-up turnover fix re-enables this as the cycling detachment.
# ATTACHMENT
kOn_umps           2.0e5     # ⚠ STALE: tuned on config-1+plain cycle; re-calibrate on the current cycle (BINDING-SURVEY)
duty_target        0.05      # skeletal duty ratio (derived check, not set directly)
```

**`params/Actin_Filament`** (SEPARATE — actin is not a myosin property; must vary independently):
```
# Actin_Filament — phalloidin-stabilized F-actin (the gliding-assay filament)
persistenceLength_um   ~10–18    # phalloidin-stabilized Lp (cite the measurement used: fixtures/filament_characterization_v1.md)
segLength_um           <current>  # rigid-rod discretization length
monomerRadius_nm       <current>  # actinMonoRadius
bendingKappa / BRotCoeff  <current>  # the F4 bending stiffness (semiflexible)
chainPairs_fracMove    <current>  # PAIRS link/bend coefficients (actin layer)
```
(aeta is NOT here — it is numerical/run config.)

**`-isoform <name>` flag — design + default-byte-identical guarantee:**
- `-isoform <name>` reads `params/<name>_Myosin` and writes its values into the existing `kinParams` / `nucParams` /
  `KAPPA` / geometry slots, in the SAME slot order and SAME float precision as the current hardcoded
  `setKinParams`/`setNucParams`/`enableConfig1`.
- **Default (no `-isoform`): no file read — the existing hardcoded path runs unchanged ⇒ byte-identical by
  construction.** Introducing the loader changes nothing until `-isoform X` is passed.
- **Equivalence proof obligation (for the follow-up that wires it):** `params/Skeletal_Myosin` is authored to the
  exact current values; a one-time test asserts `-isoform Skeletal_Myosin` produces **bit-identical** kinParams /
  nucParams / KAPPA / geometry arrays to the no-flag default (a pure-readback diff). Float fields must be written and
  parsed at full precision; the loader must not reorder slots. Only `-isoform <non-skeletal>` changes physics.
- **Actin file** read by an analogous `-actin <name>` (or a single `-isoform` selecting a {myosin, actin} pair) — kept
  separate so the actin condition varies independently of the motor isoform.

### Thesis framing (explicit)
The file boundary is the **falsifiability boundary** (RESEARCH_THESIS §6): if **Skeletal → NMII** is a pure parameter
swap, the model reproduces both from measured constants and the cross-condition claim holds. **This audit finds the
mechanics + catch + cascade are clean parameter swaps**, but **two items are still mechanism in code** — the
**detachment pathway** (biochemical-cycle vs catch-slip-only) and the **load-sensing quantity** (J1 strain vs axial
force). Those are where isoform-specificity is currently hidden in code; the turnover follow-up should convert the
detachment pathway into a parameterized rate so the boundary becomes clean.

---

## JOURNAL-ready summary
```
## 2026-06-29 — MOTOR-PARAM PROVENANCE AUDIT (read-only/design-only): skeletal-faithful mechanics, Myo2-slow turnover; the slow turnover is a MECHANISM choice (-atprecharge deletes the fast biochemical detachment), not a drifted constant
Audited every motor constant for isoform provenance + classified physics/numerical/murky + designed the Skeletal_Myosin
file schema. (A) Catch-slip is genuinely SKELETAL (Guo & Guilford 2006 + Stam 2015: xCatch 2.5/xSlip 0.40/αC/αS
0.92/0.08/peak ~6 pN), stall 5 pN (Finer 1994), step ~6.96 nm, cascade rates Howard 2001 T14-2 — mechanics
faithful-skeletal. (B) Turnover is Myo2-regime: V₀ = step×unloaded-rate = 6.96 nm × 143/s ≈ 1.0 µm/s vs skeletal 5–8;
the entire gap is bound-time (7.1 ms vs ~1.25 ms, ~5.7×), NOT step or κ. ROOT = the -atprecharge model makes the
catch-slip bond (kOff 100/s, GG mechanical regime, realized ~141/s) the SOLE detachment, DELETING the Howard
biochemical ADP→NONE (1e3/s, ~1 ms, skeletal-fast) that the cascade already has — the mechanical-bond-as-cycling-rate
conflation, confirmed; it also explains the slip-direction "catch at unloaded floor for every head". Duty 1.69 % vs
skeletal ~5 %; the turnover fix must co-retune the (already-stale) kOn. (C) Designed params/Skeletal_Myosin (measured
constants + inline citations; ⚠ kOff/kOn flagged), a SEPARATE params/Actin_Filament (phalloidin Lp), and -isoform
<name> reading params/<name>_Myosin with default = no read ⇒ BYTE-IDENTICAL until a non-default isoform is selected.
THESIS FLAG: mechanics+catch+cascade are clean parameter swaps, but TWO items are still mechanism in CODE — the
detachment PATHWAY (biochemical-cycle vs catch-slip-only, a flag/kernel branch) and the J1-strain-as-load choice;
the turnover follow-up should parameterize the detachment rate to make the boundary clean. Internal inconsistency:
skeletal catch peak (6 pN) on Myo2-slow turnover (7 ms). NO code edit, no file wired, nothing flipped; BoA-v1ref
byte-clean. Deliverable: MOTOR_PARAM_PROVENANCE.md. Follow-up (separate): wire the file + the turnover fix.
```
