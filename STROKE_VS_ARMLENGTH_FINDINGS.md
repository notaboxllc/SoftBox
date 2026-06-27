# Stroke-vs-arm-length: does the working stroke EMERGE ∝ arm length, and which arm carries it?

**MEASUREMENT-ONLY, flag-gated, default byte-identical (2026-06-26).** The lever-arm structure→function test
(`MOTOR_BENCHMARK_TARGETS` §3 hypothesis; `POWERSTROKE_MECHANICS_READOUT` open question §4). The read-out
established the stroke is a **converter rotation** — fixed rest-angle switches (J1 lever↔head 0°↔60°; F9
head↔actin 90°↔120°), NOT a declared displacement — so the linear working stroke should **emerge** from
rigid-body geometry × fixed angle. This task measures that: vary ONLY the arm lengths, read the stroke + force.

## TL;DR
- **Emergence CONFIRMED.** Varying arm length changes the stroke continuously (a prescribed, length-independent
  step cannot do this). The working stroke is geometry, not a constant.
- **Stroke ∝ HEAD_LEN, ~linear** (slope **≈ 0.41 nm stroke / nm head**; dominant on-axis component
  ≈ −0.40 nm/nm). **Stroke is ~flat / non-monotonic in LEVER_LEN** (~5–7 nm; the stroke *vector rotates*
  −x→−z but its magnitude barely moves).
- **Dominant arm = the HEAD reorientation (F9), not the lever swing (J1).** Isolation is decisive: **J1-only
  stroke = 0.00 nm; F9-only stroke = 10.9 nm** (both-together 6.96 nm — J1 partially *opposes* F9). The model's
  effective "lever arm" is **HEAD_LEN**, because F8 attaches at the head tip (`center + ½·HEAD_LEN·uVec`) and F9
  directly reorients the head about the actin contact.
- **Force ∝ arm length (RISING) — the OPPOSITE sign from the real lever-arm trade-off.** With `myoSpring`
  FIXED (1 pN/nm), the clamped stall force `k·stroke` rises ∝ HEAD_LEN (3.0→15.3 pN over head 10→40 nm). Real
  long levers are more *compliant* (force ∝ 1/L). Getting real force∝1/L needs **stiffness-from-geometry**
  (`myoSpring ∝ 1/L²`) — flagged as a scoped future option, NOT done here (it touches the v1-calibrated stiffness
  the dt arc rests on).
- **Literature:** the emergent **linear step ∝ amplifying-arm** law and a slope of the **right order**
  (model 0.41 vs engineered single-headed myosin-V ≈ 0.74 nm step / nm lever) match the engineered-lever data —
  the available-now structure→function claim holds, with the honest nuance that the model's amplifying arm is the
  head, not the neck.

## Method (the in-silico three-bead, unloaded-stroke assay)
A single fixed motor near a single pinned filament, no external load (`MotorStrokeHarness -armsweep`). Built on
the locked benchmark foundation: **explicit Hookean F8 @ dt = 1e-5**, `myoSpring = 1 pN/nm`. Everything but
geometry held fixed: swing angles (J1 0°↔60°, F9 90°↔120°), all nucleotide-cycle rates, `myoSpring`, the binding.

- **Unloaded stroke** = the head-tip displacement (`center + ½·HEAD_LEN·uVec`) between the held-uncocked (ADPPi)
  and held-cocked (ATP) equilibria, F8 OFF (head free to swing under the F9/J1 rest-angle torques) — exactly the
  existing gate-3 measurement, parameterized by arm length.
- **Isometric/stall force** = mean per-motor |F8| with F8 ON, head bound, held cocked, relaxed to equilibrium
  (the equilibrium torque↔spring balance, in pN); `k·stroke` = `myoSpring × unloaded stroke` is the clamped
  stall force (the quantity the §3 force claim references).
- **Runner:** `-cpu` sequential debug runner. Brownian OFF on the motor body ⇒ **deterministic equilibrium**
  (no seed scatter — 12 motors/config give identical results; the determinism *is* the seed cross-check). Cheap
  (seconds). Defaults reproduce gate-3 = **6.96 nm** bit-for-bit (`MotorStore.LEVER_LEN`=8, `HEAD_LEN`=20 nm).

## Results

### Sweep 1 — LEVER_LEN (head fixed 20 nm)
| lever (nm) | stroke (nm) | strokeVec (dx,dy,dz nm) | k·stroke (pN) | isoForce (pN) | nBound |
|---|---|---|---|---|---|
| 4  | 5.18 | (−5.00, 0.00, −1.34) | 5.18 | 0.000 | 12 |
| 8 (default) | 6.96 | (−5.87, 0.00, −3.75) | 6.96 | 0.626 | 12 |
| 16 | 6.19 | (−3.85, 0.00, −4.84) | 6.19 | 0.607 | 12 |
| 24 | 6.25 | (−1.81, 0.00, −5.99) | 6.25 | 0.711 | 12 |
| 32 | 7.18 | ( 0.23, 0.00, −7.17) | 7.18 | 0.825 | 12 |

Stroke **magnitude is ~flat (5–7 nm) and non-monotonic** in lever length; the stroke **vector rotates** from
−x-dominated (short lever) to −z-dominated (long lever). Lengthening the lever does **not** lengthen the stroke —
it re-aims it. (No clean Δstroke/ΔLEVER_LEN slope: ≈ +0.06 nm/nm magnitude, dominated by direction change.)

### Sweep 2 — HEAD_LEN (lever fixed 8 nm)
| head (nm) | stroke (nm) | strokeVec (dx,dy,dz nm) | k·stroke (pN) | isoForce (pN) | nBound |
|---|---|---|---|---|---|
| 10 | 3.05 | (−1.86, 0.00, −2.42) | 3.05 | 0.512 | 12 |
| 20 (default) | 6.96 | (−5.87, 0.00, −3.75) | 6.96 | 0.626 | 12 |
| 30 | 11.12 | (−9.88, 0.00, −5.10) | 11.12 | 0.709 | 12 |
| 40 | 15.34 | (−13.91, 0.00, −6.46) | 15.34 | 9.919 | 12 |

**Clean linear scaling:** stroke = 3.05/6.96/11.12/15.34 nm ⇒ **Δstroke/ΔHEAD_LEN ≈ 0.41 nm/nm** (intercept
≈ −1.2 nm). The dominant on-axis component is even cleaner: dx = −1.86/−5.87/−9.88/−13.91 ⇒ **−0.40 nm/nm**
(R²≈1.000). The head-domain reorientation is the linear amplifier.

### Sweep 3 — rotation isolation at default geometry (lever 8 nm, head 20 nm)
| mode | stroke (nm) | strokeVec (dx,dy,dz nm) |
|---|---|---|
| both (J1 + F9) | 6.96 | (−5.87, 0.00, −3.75) |
| **J1-only (F9 frozen 90°)** | **0.00** | (0.00, 0.00, 0.00) |
| **F9-only (J1 frozen 0°)** | **10.89** | (−10.19, 0.00, −3.84) |

**Decisive attribution.** The lever-swing (J1) alone produces **zero** head-tip displacement; the head
reorientation (F9) alone produces **10.9 nm** — *more* than the two together (6.96 nm), i.e. the J1 swing
**partially cancels** the F9 stroke (they interfere; sum 156%, not additive). The F8-relevant stroke is carried
**entirely by F9 (head reorientation)**.

*Why J1 contributes nothing to the tip:* with F9 holding the head at a fixed angle to the filament, the head
**orientation** (hence the `½·HEAD_LEN·uVec` tip offset) is pinned regardless of J1; the unloaded relaxation
returns the tip to essentially the same place. F9 changing 90°→120° rotates `head.uVec` directly, sweeping the
tip by ≈ the head half-arm chord. This is also why Sweep 1's magnitude is flat (J1's tip contribution ≈ 0 at any
lever length) and Sweep 2 scales (the tip moves ∝ the head arm F9 rotates).

## The scaling law
- `stroke ≈ 0.41 · HEAD_LEN(nm) − 1.2 nm`  (≈ 0 dependence on LEVER_LEN).
- Geometric sanity: F9 rotates the head half-arm `½·HEAD_LEN` by ~30°; chord `2·(½L)·sin15° = 0.26·L`; the
  multi-body relaxation (head center also shifts) lifts the realized slope to ~0.40–0.41 — same magnitude, right
  mechanism. The stroke is geometry × fixed angle, as predicted.

## Force scaling (and the sign caveat)
- **Clamped stall force = `myoSpring × stroke`** ⇒ since `myoSpring` is a FIXED constant, **force ∝ HEAD_LEN**:
  3.0 → 7.0 → 11.1 → 15.3 pN over head 10→40 nm (the `k·stroke` column). The equilibrium *isometric* force
  (torque↔spring balance, the force actually delivered) also rises with head length over 10–30 nm
  (0.51→0.63→0.71 pN); the head-40 row (isoForce 9.9 pN, elevated baseline 1.97 pN) is a **large-deformation
  regime** — a 15 nm stroke is comparable to the head size, so the small-angle bind geometry no longer returns
  cleanly; flagged, not over-interpreted.
- **Opposite sign from the real lever-arm trade-off (explicit flag).** Real long levers are more *compliant*
  (flexural stiffness ∝ 1/L²), so real per-head force is ~flat-to-decreasing with lever length (force ∝ 1/L) —
  longer lever trades force for displacement. The model gives the reverse (force RISES with arm) **because its
  stiffness is a set constant, not a geometry-derived compliance.** The model reproduces **step ∝ L** emergently,
  but to also reproduce **force ∝ 1/L** it would need **stiffness-from-geometry** (`myoSpring ∝ 1/L²`, giving
  `force = k(L)·stroke ∝ (1/L²)·L = 1/L`). Quantified here so the gap is on record.

## Literature comparison
Engineered-lever-length myosins (same head + kinetics, vary only the amplifying arm) establish **step ∝ lever
length**:
- **Purcell, Morris, Spudich, Sweeney 2002 (PNAS)** — single-headed myosin V S1: stroke **7 nm (1IQ) → 16 nm
  (4IQ) → 20 nm (6IQ)**, linear, ≈ **2.6 nm stroke per IQ**. Each IQ ≈ 3.5 nm of lever ⇒ **≈ 0.74 nm stroke per
  nm of lever**, positive intercept ~4 nm (the converter's own contribution).
- **Ruff, Furch, Brenner, Manstein, Meyhöfer 2001 (Nat Struct Biol)** — Dictyostelium myosin II with engineered
  rigid α-actinin "amplifier" repeats: the single-molecule working stroke grows ~linearly with the added lever
  length.
- **Uyeda, Abramson, Spudich 1996 (PNAS)** — Dicty myosin II neck (0/1/2 light-chain repeats): in-vitro sliding
  velocity (∝ stroke) ~linear in neck length, extrapolating low at zero neck.

**Verdict vs literature.** The model lands on the **measured linear law** (emergent step ∝ amplifying-arm
length) with a slope of the **right order**: model **0.41 nm/nm** vs myosin-V **≈ 0.74 nm/nm** (within ~2×; both
a fraction-of-arm-length set by the converter swing angle). The honest nuance: the model's amplifying arm is
**HEAD_LEN (the F9 head reorientation)**, whereas the biological amplifier is the **neck/light-chain (lever)
domain** — a consequence of the model splitting its converter rotation across J1 (60°) + F9 (30°) and attaching
F8 at the head tip. Mapping the biological "lever arm" onto this model means varying **HEAD_LEN**, not LEVER_LEN.

## Verdict
- **Available NOW (no powerstroke change):** *the working stroke emerges from converter-rotation geometry and
  scales ~linearly with the amplifying-arm length (≈ 0.41 nm stroke / nm head, R²≈1), matching engineered-lever
  data to within ~2× of slope — a structure→function prediction a prescribed-step motor cannot make.* The
  dominant arm is the **head reorientation (F9)**, confirmed by isolation (J1-only = 0, F9-only = 10.9 nm).
- **Refinement needed (scoped future, NOT done here):** the full lever-arm chain (**force ∝ 1/L**) requires
  deriving `myoSpring` from the arm's flexural compliance (`∝ 1/L²`). This touches the v1-calibrated cross-bridge
  stiffness the dt-faithful-ceiling and the dt arc rest on — a deliberate scoped deferral.

## What changed (measurement-only, default byte-identical)
- `MotorStrokeHarness.java`: `-armsweep` mode + `-leverlen`/`-headlen`/`-isolate` flags (default = production
  constants ⇒ byte-identical); harness-local `assembleLen`/`overrideMotorGeom` (mirror
  `MotorStore.assembleArticulated`/`DragTensorSystem.run` exactly at default lengths) + `strokeIsometricForce`.
- `MotorJointSystem.java` / `CrossBridgeSystem.java`: one **size-guarded** optional read each (the established
  `satMode`/`dashParams` pattern) — `jointParams[11]` freezes the J1 rest, `xbParams[9]` freezes the F9 rest.
  **Byte-identical for every existing caller** (their arrays are the smaller size ⇒ the guard is false; the
  default `-armsweep` isolation row 0 and the standard `run_stroke.sh` gates re-run unchanged, gate-3 = 6.96 nm).
- `BoA-v1ref` untouched; production untouched. Run: `./run_stroke.sh -armsweep`.
</content>
</invoke>
