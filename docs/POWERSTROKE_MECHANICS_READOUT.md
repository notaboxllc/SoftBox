# Powerstroke mechanics in SoftBox — code read-out (READ-ONLY analysis, 2026-06-26)

A precise description of the myosin powerstroke as actually implemented, traced from the code. The motivating
question: is the powerstroke a fixed **angular** conformational change (a "converter rotation") whose linear
working stroke **emerges** from rigid-body geometry (step ∝ arm length × swing angle) — a structure→function
prediction a prescribed-step model cannot make — or is it a declared linear displacement (the old "5.5 nm
rest-length shift")? **No code was changed; no runs.**

## TL;DR verdict
- **(a) Converter rotation (fixed angle), NOT a declared displacement.** The powerstroke fires by switching two
  **rest angles** as a function of nucleotide state (a 60° swing at the lever↔head converter joint + a 90°→120°
  opening of the head-vs-actin angle), keyed by `isCocked() = !isADPPi`. There is **no rest-length / position
  shift anywhere**; the "5.5 nm shift" is not in this code.
- **(b) Step size already EMERGES from rigid-body geometry** (change the arm lengths → stroke changes; the swing
  angle is a fixed parameter). Honest caveat: the F8-relevant displacement is a *multi-body* relaxation, so it
  emerges from the combined **lever + head** arm geometry, not a single tagged "neck length." A clean
  `step ∝ L_neck` claim needs a *measurement* (vary `LEVER_LEN`/`HEAD_LEN`, read the unloaded stroke) — not a
  code change.
- **(c) Force reaches the filament via F8 spring strain** (the rotation displaces the F8-attached head tip
  relative to the bound site) plus the F9/F10 alignment torques; **`myoSpring` is a fixed constant (1 pN/nm),
  NOT a geometry-derived compliance** ⇒ `force ≈ fixed_stiffness × geometric_stroke`, so the neck/head-length
  dependence lives entirely in the stroke, not the stiffness.

## The bodies and lengths (`MotorStore.java:45`)
```java
public static final double ROD_LEN = 0.080, LEVER_LEN = 0.008, HEAD_LEN = 0.020;   // µm
```
Three rigid sub-bodies per motor: **rod** (tail, 80 nm) —J2— **lever** (neck, **8 nm**) —J1— **head** (motor
domain, 20 nm; its *tip* binds actin via F8). Flat index `3m=rod, 3m+1=lever, 3m+2=head`. The four nucleotide
states and the cocked flag (`MotorStore.java:120,142`):
```java
public static final int NUC_NONE=0, NUC_ATP=1, NUC_ADPPI=2, NUC_ADP=3;
public boolean isCocked(int m){ return nucleotideState.get(m) != NUC_ADPPI; }  // cocked ⇒ J1 60°, head-actin 120°
```

## 1. Fixed ANGLE, not fixed displacement — it is a converter rotation
The powerstroke fires by switching two **rest angles** as a function of nucleotide state. Nothing shifts a rest
length or position.

**J1 (lever↔head, the internal converter joint) — `MotorJointSystem.java:91`:**
```java
double j1Rest = (nucleotideState.get(m) != MotorStore.NUC_ADPPI) ? 60.0 : 0.0;   // uncocked 0° → cocked 60°
```
driven by a bend torque toward that rest angle, **stall-capped** (`MotorJointSystem.java:170-172`):
```java
torsionMag = j1FracMoveTorq * DEG2RAD * (ang - j1Rest) / (invBRG * dt);   // ang = angle(lever.uVec, head.uVec)
double maxMag = stallPN * 0.5 * hlen * 1.0e-18;  if (torsionMag > maxMag) torsionMag = maxMag;
```

**Head↔actin (F9, the cross-bridge alignment) — `CrossBridgeSystem.java:131`:**
```java
double restF9 = (nucleotideState.get(m) != MotorStore.NUC_ADPPI) ? 120.0 : 90.0;  // uncocked 90° → cocked 120°
```
applied as a torque toward `restF9` on the angle between `seg.uVec` and `head.uVec`, −T to the head /
**+T directly to the bound segment**.

So the conformational change is **two coupled rotations** (a 60° lever swing at the converter joint + a 30°
opening of the head-vs-actin angle), keyed entirely by `isCocked() = !isADPPi`. **There is no rest-length shift
anywhere** — F8 is a *zero-rest-length* spring (`CrossBridgeSystem.java:107`, `fmag = myoSpring * dist`, no rest
length subtracted), and `bindArc` is fixed at bind time. The old "5.5 nm rest-length shift" is gone; **this is a
converter rotation.**

## 2. Geometry of the rotation
- **Pivots:** the converter swing at **J1** (`lever.end2 ↔ head.end1`), plus the head-vs-filament reorientation
  (F9) acting at the bound head.
- **Lever arm(s):** the swing is fixed-angle; the resulting *linear* motion scales with the rigid-body arm
  lengths — `LEVER_LEN` (neck, the swung body) **and** `HEAD_LEN` (the head reorients about its own center,
  moving the F8-attached tip). Both are fixed constants in `MotorStore.java:45`.
- **Swing angle is a fixed parameter, independent of length:** `60.0`/`0.0` (J1) and `120.0`/`90.0` (F9) are
  hardcoded, not derived from any length.
- **What displaces:** the head **tip** (`head.center + ½·HEAD_LEN·head.uVec`, where F8 attaches) moves relative
  to the bound site. With the head bound (F8) and the tail anchored, the relaxation toward the new rest angles
  sweeps the tip along the filament — the working stroke (CLAUDE.md records ~7 nm unloaded).

## 3. Relation to F8 / `myoSpring` — how force reaches the filament
Two channels, both downstream of the angle switch:
1. **F8 spring strain (dominant linear-force channel):** the rotation moves the head tip relative to the site →
   strains the spring `F8 = myoSpring·(site − head_tip)` (`CrossBridgeSystem.java:104-123`); head gets `+F`,
   segment gets `−F`. The catch-slip / ADP-gate load is exactly this: `forceDotFil = Dot(F8, seg.uVec)`
   (`CrossBridgeSystem.java:165`).
2. **F9/F10 alignment torques** directly torque the bound segment (`+T9/+T10` seg-side).

**`myoSpring` is a SET CONSTANT, not geometry-derived:** `MYO_SPRING = 1.0e-9` N/µm (1 pN/nm) is a fixed harness
constant (v1 `Env.java:791`), fed in via `xbParams[0]`. It is **not** a flexural compliance computed from neck
length. So `force_per_motor ≈ myoSpring × (geometric stroke)` — the geometry carries the stroke, the stiffness is
an independent fixed constant.

## 4. Step size — emergent, with one honest caveat
**Emergent, not declared.** There is no scalar "step = 5.5 nm" anywhere; the displacement falls out of (fixed
swing angle) × (rigid-body arm geometry). Change `LEVER_LEN`/`HEAD_LEN` with the angle and kinetics held fixed
and **the stroke changes** — and since `force ≈ myoSpring × stroke`, **force per motor scales with arm length too**
(stiffness fixed). This is exactly the structure→function behavior a prescribed-step model cannot produce.

**Caveat for a *clean* "step ∝ neck length":** the F8-relevant displacement is a *multi-body* relaxation — the
head tip moves both because the head reorients (∝ `HEAD_LEN`) and because the neck swings about J1 (∝ `LEVER_LEN`).
So step emerges from the **lever+head arm geometry**, not from a single tagged "neck length." A clean
`step ∝ L_neck` claim needs you to (a) pick which arm dominates and (b) demonstrate it — which is a *measurement*,
not a code change: vary `LEVER_LEN` (and/or `HEAD_LEN`) and read the unloaded stroke from the existing stroke
checkpoint (`run_stroke.sh`). No edit is required to test the prediction.

## 5. Nucleotide coupling and reversibility (`NucleotideCycleSystem.java:43-67`)
Forward-only cycle `NONE→ATP→ADPPi→ADP→NONE`:
- **Recovery stroke** = `ATP→ADPPi` (cocked→uncocked, 60°→0°), runs whether bound or not.
- **Power stroke** = **`ADPPi→ADP`** (uncocked→cocked, 0°→60° and 90°→120°), rate-gated by binding
  (`rate = bound ? onPi : offPi`) — i.e. Pi-release drives the force-generating swing while attached.
- **`ADP→NONE` is load-gated** (`NucleotideCycleSystem.java:57-65`): only fires when the 10-window `forceDotFil`
  average ≤ 0 — a loaded motor stays cocked-and-bound (strained) until it has done its work.

**Reversibility:** the nucleotide state (hence the *target* rest angle) advances **irreversibly** — there are no
backward state transitions. But the *mechanical* angle is enforced by a **finite, stall-capped torque**
(`MotorJointSystem.java:171`), so under load the lever can be held back or driven backward without completing the
swing. The stroke is therefore a biochemically-ratcheted but mechanically-resistible swing toward a state-set rest
angle — load and the cycle are coupled (the stall cap + the load-gated ADP step), the source of the emergent
stiffness→load→detachment coupling the thesis (§10c) contrasts with Cytosim's prescribed detachment.

## Bottom line
- **(a)** It is a **converter rotation** — a fixed-angle conformational change (60° at the lever↔head converter
  joint + a 90°→120° head-vs-actin opening), keyed by `!isADPPi`. Not a declared displacement; the "5.5 nm
  rest-length shift" is not in this code.
- **(b)** Step size **already emerges** from rigid-body geometry × fixed angle (change the arm lengths → stroke
  changes) — but from the combined **lever+head** arm geometry, so a clean "step ∝ neck length alone" still needs
  a measurement (vary `LEVER_LEN`/`HEAD_LEN`, read the stroke) to attribute the dominant arm. No code change
  needed to run that test.
- **(c)** Force reaches the filament mainly through **F8 spring strain** (the rotation displaces the F8-attached
  head tip relative to the site), plus the F9/F10 alignment torques on the segment; **`myoSpring` is a fixed
  constant (1 pN/nm), not a geometry-derived compliance** ⇒ `force ≈ fixed_stiffness × geometric_stroke`, and the
  neck/head-length dependence lives entirely in the stroke, not the stiffness.

## Key code references
| What | File:line |
|---|---|
| sub-body lengths (rod/lever/head) | `MotorStore.java:45` |
| nucleotide states + `isCocked() = !isADPPi` | `MotorStore.java:120,142` |
| J1 lever↔head rest-angle switch (0°↔60°) — the converter rotation | `MotorJointSystem.java:91` |
| J1 bend torque toward rest, stall-capped | `MotorJointSystem.java:170-172` |
| F9 head↔actin rest-angle switch (90°↔120°) | `CrossBridgeSystem.java:131` |
| F8 zero-rest-length spring `myoSpring·dist` (head tip ↔ site) | `CrossBridgeSystem.java:104-123` |
| `forceDotFil = Dot(F8, seg.uVec)` (the load the catch/ADP-gate reads) | `CrossBridgeSystem.java:165` |
| nucleotide cycle; power stroke = `ADPPi→ADP`; load-gated `ADP→NONE` | `NucleotideCycleSystem.java:43-67` |
| `myoSpring = 1.0e-9` N/µm set constant (v1 Env.java:791) | per-harness `MYO_SPRING`, via `xbParams[0]` |
