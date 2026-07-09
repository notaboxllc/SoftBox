# CANONICAL MANIFEST — the complete model-fork enumeration for the gliding code (read-only; for jba's sign-off)

**Date:** 2026-07-08. **Status: ENUMERATION ONLY — no collapse this session.** This document lists *every*
physics/model fork by which the assembled gliding model can differ, each with its current default, the code
sites that read it, the alternatives, the physical effect, and a ratification category. It exists to kill the
failure mode where a future instance reconstructs "the canonical model" by reading which flags happen to be
true — the way the `-ratefix` LOW basin masqueraded as the baseline for a dozen tables. **Nothing was edited,
deleted, or hardcoded. `BoA-v1ref` untouched.** All sites are `GlidingHarness.java` (`GH`) unless noted.

---

## 0. READ THIS FIRST — the assembled default ≠ "the canonical config", and that gap is the whole point

There are **two different things** a future self could call "canonical", and they are NOT the same:

**(A) The bare code default** — what `./run_gliding.sh -full -grid` runs with no model flags:
- **Motor geometry:** sphere-head neck-powerstroke — `SPHEREHEAD + AXLOCK + DIRSWING`, set **post-arg-parse**
  at `GH:336–340` (NOT via the field defaults, which are all `false`). This is a **hidden promotion**: the
  three fields declare `false` (`GH:86–88`) and are flipped to `true` after parsing unless `-legacymotor` or an
  alternative motor (`-canonical/-config1/-perphead`) is selected.
- **Constraint formulation:** springs — `PAIRS_SPRINGS/ALIGN_SPRINGS/STRUCT_SPRINGS` default `true` (`GH:155–157`).
- **Kinetics:** `LYMN_TAYLOR=false`, `ADPPI_BIND=false` (`GH:130,131`) ⇒ the **legacy nucleotide cycle**
  (`NucleotideCycleSystem.cycle`, `GH:844` else-branch) with **catch-slip release** — NOT Lymn-Taylor.
- **Cross-bridge solve:** `XB_IMPLICIT2=false` (`GH:54`) ⇒ **explicit** Hookean F8 (`bondForces`, `GH:862`
  else-branch).
- **Params:** `DENSITY=500` (`GH:136`), `COL_TOL=6 nm` (`GH:160`), `DT=1e-5` (`GH:33`).

**(B) The config the recent arc treats as canonical** — the SPRINGS_PROMOTION GATE-2 reference config and the
active `dt-convergence-study` runs:
```
-full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000 -dt 1e-5   (+ springs default-on)
```

**These differ in FOUR model forks:** kinetics (Lymn-Taylor vs legacy cycle), the ADP·Pi bind gate, the
cross-bridge integrator (implicit vs explicit), and two parameters (coltol, density). **The manifest task
description itself lists "Lymn-Taylor + ATP-binding detachment + catch-slip-as-ADP-release" as
SETTLED-CANONICAL category 1 — but that kinetics is default-OFF in the code.** So the single most important
finding of this enumeration is:

> **The bare code default is NOT the model jba considers canonical.** A future self running `run_gliding.sh`
> with no flags gets the legacy catch-slip cycle + explicit cross-bridge, not Lymn-Taylor + `-adppibind` +
> `-xbimplicit2`. Before any collapse, jba must decide which of A/B is the sole path — and whichever it is, the
> flags currently required to reach it must be baked into the default or the default must be renamed
> "legacy/diagnostic".

This is exactly the silent-mis-mapping risk the manifest is meant to prevent. It is flagged again in §5
(entanglements) and §6 (decisions).

---

## 1. Fork table — by category

Category key: **1 SETTLED-CANONICAL** (arc validated; propose as sole path) · **2 CHOICE-NOT-PROOF** (defensible
choice, alternative not disproven; jba chooses — see §3) · **3 PARAMETER-NOT-FORK** (tunable input; stays
tunable, must NOT be hardcoded) · **4 DEAD/ARTIFACT** (inert/wrong/superseded; removal also shrinks the GPU
hazard surface) · **5 DIAGNOSTIC** (probe/instrument; stays per jba).

### 1a. Constraint formulation (springs vs rate vs raw)

| fork / flag | site(s) | default | alternatives | physical effect | cat |
|---|---|---|---|---|---|
| `PAIRS_SPRINGS` | decl `GH:155`; bake `GH:399`, chainParams via `springify`; read in `ChainBendingForceSystem` via `chainParams[1]/[3]` | **true** | `-nosprings`→raw 0.5/0.2 fraction/step; `-filrate`→per-time rate | filament F3 link+bend / F4 torsion as fixed stiffness vs fraction-per-step vs dt-convergent rate | **1** |
| `ALIGN_SPRINGS` | decl `GH:156`; bake `GH:661` (`alignK`), `GH:696–702` (`swingParams[4]=−refDt`); in-kernel multiply branch `CrossBridgeSystem.java:241` | **true** | `-nosprings`→raw 0.4/step; `-alignrate`/`-strokerate`→rate (in-kernel `exp/log`, `CrossBridgeSystem:237`) | motor F9/F10/axlock + directed-swing as fixed spring (**transcendental-free multiply**) vs fraction vs the exp/log rate. **This is THE deterministic-vs-basin-flip fix.** | **1** |
| `STRUCT_SPRINGS` | decl `GH:157`; bake `GH:706–712` (`jointParams[1]/[5]/[9]` via `springify`) | **true** | `-nosprings`→raw; `-structrate`→rate | J1/J2 connection + tail-anchor position springs as fixed stiffness (dt-convergent skeleton) | **1** |
| `-nosprings` | `GH:256` | (off) | clears all three springs booleans | opt-out to the raw fraction-per-step default (`-nosprings -ratefix -structrate` = the old rate path) | 1 (opt-out kept) |
| `-ratefix` (= `-strokerate`+`-alignrate`+`-filrate`) | `GH:251`; disclosures `GH:390–398` | off | — | converts ALL glide per-step fractions → per-time rates; **its swing `exp/log` is the GPU basin-flip hazard** | **4** |
| `-strokerate`/`-alignrate`/`-filrate`/`-structrate` | `GH:248–252` decls `GH:138–142` | off | — | individual per-time-rate reformulations; **byte-identical at production dt=1e-5** (`DT/refDt=1`); superseded by springs for the shared slots (springs take precedence, disclosure note `GH:403`) | **4** (rate machinery) / see §3 for `-structrate`'s residual role |

**Note:** at production `dt=refDt=1e-5`, springs ≡ rate ≡ raw are **bit-identical floats** (0.5/0.2/0.4 →
0x3f000000/0x3e4ccccd/0x3ecccccd). They diverge only below refDt (PURE_SPRINGS §STEP-4). So this whole family is
a **fine-dt-only** fork at present.

### 1b. Cross-bridge translation solve (integrator)

| fork / flag | site(s) | default | physical effect | cat |
|---|---|---|---|---|
| explicit Hookean F8 | `bondForces` `CrossBridgeSystem.java:59`; called `GH:862` (default else-branch) | **DEFAULT** | raw explicit stiff spring; the RESEARCH_THESIS §11 **operating decision** for validation | **1** (per thesis) |
| `-xbimplicit2` | `GH:54,245`; tasks `GH:918–921,1006,1148`; disclosure `GH:372–382` | off | COUPLED head+SITE implicit F8 (per-segment star over the boundSeg CSR-inverse); backward-Euler | **2** (see §3 — used in GATE-2 config but thesis banks it) |
| `-xbimplicit` | `GH:53,244`; `GH:366,885,979,1057–1059` | off | locally-implicit bound-head spring only (head, not site); stable-but-unfaithful, ~2× dt (IMPLICIT_CROSSBRIDGE) | **4** (superseded by xbimplicit2) |
| `-xbtrap` | `GH:55,246`; `GH:375–381` | off | trapezoidal/Crank–Nicolson re-timing of the xbimplicit2 star (r→r/2); implies xbimplicit2 | **5** (probe, XBTRAP_PROBE) |
| `-segimplicit` | `GH:56,247`; `GH:384,919,1007,1141` | off | diagonal per-segment implicit COLLECTIVE loaded cross-bridge (rigid head); **dense/ring-regime cure, NOT the gliding fix** (SEG_IMPLICIT) | **4** for gliding / banked for dense |
| `-xbdash`/`-xbdashmech` | `GH:49,51,242–243`; `GH:358–363` | off | parallel Kelvin-Voigt dashpot on F8; **fails** (thermal-velocity catastrophe, THESIS §10a) | **4** |
| `-xbsat` | `GH:241` | off | static saturating F8; **fails** (magnitude overlap, SATURATED_CROSSBRIDGE) | **4** |
| `-substep` | `GH:309` | off | SUBSTEP_FEASIBILITY read-out; banked speed play (THESIS §11 option B) | **5** |

### 1c. Motor geometry / powerstroke

| fork / flag | site(s) | default | physical effect | cat |
|---|---|---|---|---|
| sphere-head neck-powerstroke = `SPHEREHEAD+AXLOCK+DIRSWING` | promoted `GH:336–340`; xbParams `GH:662–668`; `jointParams[3]=0` `GH:705`; `directedSwing` `GH:880,959,1040,1113`; swingParams `GH:696–702` | **DEFAULT** (post-parse promotion) | freeze F9 at 90° (perp-maintainer) + axial swing-plane lock F10→ŝ + deterministic polarity-directed neck stroke; J1 angular converter OFF | **1** |
| `-legacymotor` | `GH:85,296,336–337` | off | restores OLD default (v1-port F9 head-swing 90°↔120°, sphere-head stack OFF); kept as regression/oracle | **5** (regression) |
| `-hfswing` | `GH:89,289`; `directedSwingHeadFrame` `GH:879,958,1039,1112` | off | head-frame converter recast (no f̂ in swing law); documented negative — reproduces `-dirswing` | **4/5** |
| `-rollsign` / `-mhatset` | `GH:91,95,291,295`; `GH:892,1126` | off | stereospecific +ŝ roll / bind-time head-axis init; documented negatives (PHASE2_ROLLSIGN) | **4/5** |
| `-canonical` | `GH:57,260`; `bondForcesCanonical` `CrossBridgeSystem.java:461`; `GH:832,868,889,1008,1076,1106,1125` | off | PHASE-2 Version-B two-point canonical lever-arm motor (head pinned at 2 points, F9 removed, J1 drives lever, catch reads lever strain). **The motor is EXEMPT from v1 bit-parity; v2-canonical is the reference (CANONICAL_MOTOR)** | **2** (banked phase-2; deliberate v1 divergence, calibration deferred) |
| `-config1` | `GH:59,262`; `GH:678–705,865,1009,1104` | off | Config-1 composed architecture (PAIRS attachments + Hookean J1 load); implies `-canonical` | **2** (phase-2, banked) |
| `-perphead` | `GH:75,278`; `snapPerpRest` `CrossBridgeSystem.java:668`; `GH:681–705,839,862,1082,1102` | off | perp-head variant (rear pin removed, ⊥-orientation torque); implies `-config1`; gliding-capable mechanism gate | **2/5** (phase-2 variant) |
| `-headtilt`/`-perpfrac`/`-headlock`/`-neckangle` | `GH:279,280,297,310` | (params) | head↔filament rest angle / ⊥-torque strength / F9-hold stiffness / cocked neck angle | **3** (tunable) / diagnostic overrides |
| `-spherehead`/`-axlock`/`-dirswing` (as explicit flags) | `GH:286–288` | (imply-chain) | manual selection of the now-default stack; kept for explicitness | 1 (= default) |

### 1d. Kinetics (nucleotide cycle / binding / release)

| fork / flag | site(s) | default | physical effect | cat |
|---|---|---|---|---|
| legacy nucleotide cycle + catch-slip release | `NucleotideCycleSystem.cycle` `:33`; `GH:844` else, `GH:1085` else; default `release` task | **DEFAULT** | 4-state NONE→ATP→ADPPi→ADP cycle + force-dependent catch-slip **release** pathway | see §3/§5 — default, but jba's stated canonical is Lymn-Taylor |
| `-lymntaylor`/`-lt` | `GH:130,284`; `cycleLymnTaylor` `NucleotideCycleSystem.java:408`; `GH:819,844,1067,1085`; upload `GH:1014` | **off** | THE validated canonical Lymn-Taylor cycle: ONE release pathway (NONE→ATP = detachment, nucleotide-driven); the catch MODULATES ADP→NONE (not a release). **Removes** the `release` task. dt-robust detach (NUCLEOTIDE_DETACH) | **1 (jba's canonical) but default-OFF — the key mapping gap** |
| `-adppibind` | `GH:131,285,630` (`kinParams[20]=1`) | off | ADP·Pi strong-bind gate — head binds actin ONLY in pre-stroke ADP·Pi state; kills bind-in-ATP ejection churn (NUCDETACH_DUTY) | **1** (companion to Lymn-Taylor) — see §3 (parameter-vs-fork ambiguity) |
| `ATP_RELEASE` / `-noatprelease` | `GH:82,283`; `GH:848,1089` | true (CONFIG1 only) | ATP-binding = detachment coupling; **only seen on CONFIG1 paths**; A/B control disables it | **4/5** (CONFIG1-scoped; superseded by Lymn-Taylor framing) |
| `-atprecharge` | `GH:133,307`; `GH:823,846,1068,1087` | off | catch-slip-ONLY release + ATP recharge; bound head locked out of ATP uptake. **Superseded by `-lymntaylor`** (per decl comment) | **4** |
| `-tauavg` | `GH:269,634,827,1072`; `setReleaseForceAvg` | off (0) | time-averaged catch force input (EMA window τ); **not a lever** (RELEASE_FORCE_AVERAGING) | **4** |
| `-faithfulrelease` | `GH:39,238,638`; `setFaithfulRelease` | off | v1's 12 pN force-cap release branch; §6.10 — **pending promotion decision** (re-baselines avgBound 7.6→6.5) | **2** (pending, own-task) |
| `-faithfulrefractory`/`-norefractory`/`-rebindtime` | `GH:38,41,216,237,239` | off/default | post-release rebind refractory variants; §6.11 refractory not a directedness lever | **4/5** |
| `-kon`/`-kappa`/`-xcatch`/`-ratescale` | `GH:264,265,272,311` | (params) | reaction-limited attach rate / J1 torsional stiffness / catch distance d / cycle-rate scale (phase-2 calibration knobs) | **3** |
| `-nobind` | `GH:63,266` (`kinParams[19]=1`) | off | thermal-floor control, motors never bind | **5** |

### 1e. Thermal / noise-correction family (all understood inert / artifact)

| fork / flag | site(s) | default | physical effect | cat |
|---|---|---|---|---|
| `-bondnoise`/`-bondnoisefac` | `GH:100,298–299`; `scaleBoundHeadNoise` | off | bound-head √((2−α)/2) Brownian correction; genuine effect ≈0 (allnoise diagnosis) | **4** |
| `-allnoise`/`-allnoisescale`/`-fracnoise` | `GH:105,106,300–302` | off | head+segment F8 noise correction; **proven a GPU graph-split artifact** (scale-1.0 reproduces its full "recovery" on GPU, byte-id OFF on CPU) | **4** |
| `-thermcorr` | `GH:117,303` | 0 | comprehensive per-body-α correction level 3/4/5; adds sqrt task (hazard); self-cancelling (SYSWIDE) | **4** |
| `-syswide`/`-syswiderb` | `GH:128,129,305–306` | off | faithful system-wide correction; **self-cancelling, closes ~0%** (SYSWIDE_THERMAL) | **4** |
| `-uninoise` | `GH:123,304,351–354` | 1.0 | flat uniform Brownian-amplitude scale (A2 control) | **5** |
| Brownian on/off | `BrownianForceSystem`; always-on in gliding | on | FDT thermal forcing | **1** (not a fork in gliding — always on) |

### 1f. Numerics / plumbing that changes the assembled model or graph

| fork / flag | site(s) | default | physical effect | cat |
|---|---|---|---|---|
| `-freshread` | `GH:35,236,348,1022` | off | catch-slip + cycle read THIS step's forceDotFil (reorders the whole graph); **does not close the capture split, degrades glide** (FRESHREAD_AB) — do NOT promote | **4** |
| `-box`/`-boxall` | `GH:176,177,231–232`; `GH:1114,1140` | off | ContainmentSystem chamber over the gliding filament / every motor sub-body (v1-faithful) | **3/5** (scene option) |
| `-full`/`-v1box`/`-matbed`/`-densemat` | `GH:224–230` | (`-full` typical) | box geometry / motor count presets (scene construction) | **3** (scene) |
| `-matband`/`-earlystop` + `-es*` | `GH:198,317–323` | off | measurement-window levers (GLIDING_RADIUS_SWEEP) | **5** |
| `-swingkbits`/`-swingkprobe` | `GH:257,258,404,405,474` | -1/off | BISTABILITY_ORIGIN swing-coeff probes | **5** |
| `-diag`/`-cycldiag`/`-brakediag`/`-grid`/`-ztrace`/`-assistlog`/`-stretchcensus`/`-ktotcensus`/ various `*census` | `GH:217–222,290–294,308,314–315` | off | read-only diagnostics / measurement modes | **5** |

### 1g. Parameters (category 3 — tunable inputs, MUST NOT be hardcoded at collapse)

`-dt` (`GH:33,234`, default 1e-5) · `-seed` (`GH:233`) · `-density` (`GH:136`, default 500) · `-coltol`
(`GH:160`, default 6 nm — "the engagement/duty master knob", PHYSICAL) · `-aeta` (`GH:166`, default 0.1 Pa·s,
PHYSICAL) · `-myospring` (`GH:34`, default 1 pN/nm — the sharp tuning knob, THESIS §11) · `-kon` · `-kappa` ·
`-xcatch` · `-neckangle` · `-headtilt` · `-perpfrac` · `-fext` · `-ratescale`. These are inputs to whatever
model is assembled; they parameterize it, they do not branch it.

---

## 2. Diagnostic modes that BUILD THEIR OWN SCENE — collapse-time divergence risks

These functions do NOT go through `buildScene`; they set `chainParams` (and other params) directly, so they
**bypass the springs freeze and any future canonical-path change** — they will silently keep the raw
fraction-per-step law after collapse. Flagged for the later prompt (not resolved here):

| function | site | how it diverges |
|---|---|---|
| `headTiltSweep` | `GH:1770`; raw `chainParams` `GH:1815–1817` (`0.5f/0.2f`, NOT springified) | Stage-1 θ sweep builds its own single-motor scene; dt=1e-6, raw fractions |
| `boundGeom` | `GH:2053`; raw `chainParams` `GH:2064–2065` | bound-state geometry single-motor scene |
| `forceDecomp` | `GH:1732` (dispatched `GH:345`) | single-motor transport scene (shares the raw decomp chainParams block) |
| `stiffnessAngleSweep` | `GH:1632` (dispatched `GH:343`) | builds its own minimal one-shot scenes |
| `dCalib` | `GH:1554` | catch force-sensitivity calibration scene |
| `catchSlipRecal` | `GH:2162` | step-4c recalibration scene |
| `singleMolecule` | `GH:1458` | single-molecule duty assay (Config-1) |
| `swingKProbe` | `GH:474` (dispatched `GH:405`) | bespoke one-kernel probe |

SPRINGS_PROMOTION §Blast-radius confirms these fine-dt `decompRun`/`headTiltSweep` modes are "UNAFFECTED" by
the springs default — which is the point: **they will diverge from the canonical path the instant the canonical
path stops being byte-identical to raw (i.e. below refDt, or after any retune).**

---

## 3. CHOICE-NOT-PROOF forks — the decisions jba must make with eyes open

These are where CC hardcoding the current default would itself be the silent-mis-mapping we are trying to kill.

**(i) Lymn-Taylor kinetics (`-lymntaylor` + `-adppibind`) — canonical by jba's framing, but DEFAULT-OFF.**
What the arc established: Lymn-Taylor passes the dt-convergence non-saturation gate (avgBound stays O(1) as
dt→0) and `-adppibind` fixes the bind-in-ATP churn dwell (NUCLEOTIDE_DETACH / NUCDETACH_DUTY). jba named it
"the VALIDATED canonical cycle" in the code comment (`GH:130`). **But the bare default is still the legacy
catch-slip cycle.** Canonizing Lymn-Taylor means baking `LYMN_TAYLOR=true` (and deciding `-adppibind`) into the
default and demoting the legacy cycle to `-legacycycle`. **Decision:** is Lymn-Taylor the sole kinetics, and is
`-adppibind` part of it (fork) or a separately-tunable gate (parameter)? — currently ambiguous.

**(ii) `-xbimplicit2` (coupled implicit cross-bridge) — used in the GATE-2 "reference" config, but the thesis
banks explicit.** RESEARCH_THESIS §11 **operating decision: benchmark and validate on the EXPLICIT Hookean
cross-bridge at dt=1e-5** — implicit is "banked, deferred to calibration time" because adopting it
re-baselines the v1 parity oracle. Yet the SPRINGS_PROMOTION GATE-2 config and the active dt-study runs pass
`-xbimplicit2`. **These two are in direct tension.** Canonizing implicit freezes in a non-oracle-parity
integrator mid-validation-ladder; canonizing explicit means the recent gliding numbers (run with `-xbimplicit2`)
are off the canonical path. **Decision required — and note the arc found xbimplicit2's *gliding* effect
marginal at production dt with the convergent skeleton (COUPLED_IMPLICIT_XB), so "implicit is canonical" is a
choice, not a proof.**

**(iii) Springs-continuum calibration — canonizing springs freezes an un-retuned fine-dt limit.**
SPRINGS_PROMOTION §DEFERRED is explicit: springs reinterpret the fraction-per-step coefficients as fixed
stiffnesses that are **numerically identical to current only at production dt**. Below refDt (or if the
stiffnesses are given physical meaning — Lp/bending-modulus calibration) they diverge, and **no retuning has
been done**. Canonizing springs is correct for the *production-dt* path but banks an unresolved fine-dt
reference and an un-calibrated stiffness. Cost to keep the alternative: retain `-nosprings` (already there).

**(iv) The canonical motor (`-canonical`/`-config1`/`-perphead`) vs the sphere-head default.** The default is
the sphere-head neck-powerstroke (v1-port lineage, promoted 2026-07-01). The canonical two-point lever-arm
motor is a **deliberate divergence from v1**, banked as phase-2 (CANONICAL_MOTOR / THESIS §12), calibration
deferred. Canonizing the sphere-head default means the phase-2 canonical motor stays opt-in until phase-2
resumes — a defensible sequencing choice, but jba should confirm the sphere-head (not the two-point canonical)
is the sole path *for now*.

**(v) `-faithfulrelease` (12 pN force-cap release) — pending promotion, its own task.** CLAUDE.md §4b-iv
flags this as decided-but-not-executed because flipping it re-baselines avgBound (7.6→6.5). Not a collapse-time
default flip to do as a side-effect; listed so it is not forgotten.

---

## 4. DEAD / ARTIFACT paths (safe to remove at collapse; removal shrinks the GPU hazard surface)

- **Noise-correction family:** `-bondnoise`, `-bondnoisefac`, `-allnoise`, `-allnoisescale`, `-fracnoise`,
  `-thermcorr`, `-syswide`, `-syswiderb`, `-uninoise` — all proven inert or GPU graph-split artifacts (the
  `-allnoise` diagnosis; SYSWIDE self-cancelling). Each adds a `sqrt` task = a GPU basin-flip hazard.
- **Superseded integrator variants:** `-xbimplicit` (superseded by xbimplicit2), `-xbdash`/`-xbdashmech`
  (dashpot fails), `-xbsat` (saturation fails). `-segimplicit` is dead *for gliding* but banked for dense/ring.
- **Superseded kinetics:** `-atprecharge` (superseded by `-lymntaylor` per its own comment), `-tauavg`
  (averaging not a lever), the CONFIG1-scoped `ATP_RELEASE`/`-noatprelease` A/B.
- **The rate machinery:** `-ratefix` (its swing `exp/log` is THE basin-flip artifact) and its constituents
  `-strokerate`/`-alignrate`/`-filrate` — superseded by springs; byte-identical at production dt so removal is
  free there. (`-structrate` — see §3(iii)/keep as the rate-path comparison via `-nosprings -ratefix
  -structrate`.)
- **`-freshread`** — degrades glide, does not close the capture split; do not promote.
- **Documented-negative motor recasts:** `-hfswing`, `-rollsign`, `-mhatset` (reproduce `-dirswing`; kept as
  documented negatives — demote to diagnostics or remove).

---

## 5. Entanglements (the structure that makes silent mis-mapping possible)

1. **The default motor is set by a POST-PARSE promotion block (`GH:336–340`), not by field defaults.** Reading
   the `SPHEREHEAD/AXLOCK/DIRSWING` field declarations (`false`) gives the WRONG answer; you must read the
   promotion block. This is the single most reconstruction-hostile piece of the code.
2. **"Canonical config" (B) ≠ "code default" (A)** in four forks (kinetics, adppibind, xbimplicit2, params) —
   §0. The recent gliding numbers were produced with (B); the bare default is (A).
3. **Implied-flag chains:** `-config1`⇒`-canonical`; `-perphead`⇒`-config1`⇒`-canonical`;
   `-dirswing`⇒`-axlock`⇒`-spherehead`; `-hfswing`⇒`-dirswing`; `-rollsign`⇒`-hfswing`; `-mhatset`⇒`-rollsign`;
   `-ratefix`⇒`-strokerate`+`-alignrate`+`-filrate`. A single flag silently sets several booleans.
4. **Springs take PRECEDENCE over the matching rate flag** (`GH:399–403` note): setting `-ratefix` with springs
   default-on is a silent no-op for the shared slots at production dt. A future A/B could set `-ratefix` and see
   no change and draw the wrong conclusion.
5. **`ATP_RELEASE` is default-TRUE but only consumed on CONFIG1 paths** (`GH:848,1089`) — a default-true field
   that does nothing on the default (non-CONFIG1) path. Reading its default `true` is misleading.
6. **The thesis operating decision (explicit @1e-5) contradicts the GATE-2 config (`-xbimplicit2`)** — §3(ii).
   Two governing documents point opposite ways.

---

## 6. SUMMARY — for jba's ratification

**Proposed sole-path canonical set (category 1 + the ratified §3 choices):**
- Constraint formulation: **springs** (`PAIRS/ALIGN/STRUCT_SPRINGS`) — the transcendental-free deterministic path.
- Motor geometry: **sphere-head neck-powerstroke** (`SPHEREHEAD+AXLOCK+DIRSWING`) — but make it an explicit
  default, not a post-parse promotion.
- Kinetics: **Lymn-Taylor** (`-lymntaylor`) + **catch-as-ADP-release** — *pending jba baking it into the default
  (currently OFF)*; decide whether `-adppibind` is part of it.
- Cross-bridge solve: **explicit @dt=1e-5** per THESIS §11 — *OR* xbimplicit2 if jba overrides the thesis
  (§3(ii) — must be decided, they conflict).

**Parameters that stay tunable (never hardcode):** `dt`, `seed`, `density`, `coltol`, `aeta`, `myospring`,
`kon`, `kappa`, `xcatch`, `neckangle`, `headtilt`, `perpfrac`, `fext`, `ratescale`, box/scene presets.

**Dead paths to remove (also shrinks the GPU hazard surface):** the entire noise-correction family
(`-bondnoise/-allnoise/-thermcorr/-syswide/-uninoise` + variants); `-xbimplicit`, `-xbdash*`, `-xbsat`;
`-atprecharge`, `-tauavg`, CONFIG1 `ATP_RELEASE`; the `-ratefix` rate machinery (`-strokerate/-alignrate/-filrate`);
`-freshread`; the documented-negative recasts `-hfswing/-rollsign/-mhatset`.

**Diagnostic-with-own-scene builders (will silently diverge post-collapse — §2):** `headTiltSweep`,
`boundGeom`, `forceDecomp`, `stiffnessAngleSweep`, `dCalib`, `catchSlipRecal`, `singleMolecule`, `swingKProbe`.
Route them through the canonical scene builder, or explicitly mark them as raw-law diagnostics, before collapse.

**The explicit decisions jba must make before collapse:**
1. **Is the canonical model (A) the bare default or (B) the `-lymntaylor -adppibind -xbimplicit2 -coltol 10
   -density 1000` config?** — the four-fork gap in §0. This is the top decision; everything else hangs on it.
2. **Lymn-Taylor as sole kinetics?** Bake `LYMN_TAYLOR=true`, demote legacy cycle to `-legacycycle`. And:
   `-adppibind` = part of the canonical cycle (fork) or a tunable gate (parameter)?
3. **Explicit vs `-xbimplicit2` cross-bridge** — resolve the THESIS §11 ↔ GATE-2 contradiction (§3(ii)).
4. **Ratify springs** as the sole formulation, accepting the deferred fine-dt/stiffness re-calibration (§3(iii)).
5. **Confirm the sphere-head motor is the sole path for now** (canonical two-point stays phase-2 opt-in — §3(iv)),
   and **make it an explicit default rather than a post-parse promotion** (§5.1).
6. **`-faithfulrelease`** — schedule its own promotion task (re-baselines avgBound); do not fold into collapse.
7. **Sign off the dead-path removal list** and the diagnostic-scene re-routing (§2/§4).

**No collapse performed this session. Manifest is for ratify/amend fork-by-fork.**
