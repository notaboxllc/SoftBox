# V1_MOTOR_MECHANISM.md — what the v1 (BoA-v1ref) myosin motor *actually* does

**Date:** 2026-06-29. **Source:** `~/Code/BoA-v1ref/` (frozen, byte-clean oracle — READ-ONLY; nothing
edited or run). Characterization from the code itself, not from memory or journal paraphrase.

**Primary files:** `boxOfActin/MyoFilLink.java` (the cross-bridge: attachment, F8 spring, F9/F10 torques,
release), `boxOfActin/Myosin.java` (the articulated rod→lever→motor body + J1/J2 joints + the cocked-angle
constants), `boxOfActin/MyoMotor.java` (the head domain + binding event / site selection).

---

## The seven answers

### 1. Attachment — a FIXED material site; never re-anchored while bound
At bind, `MyoMotor.checkFilSegCollision` drops a perpendicular from the head's `bindTip` onto the segment
line and computes the arclength of that closest point: `arcOnFil = alpha * sqrt(denom)`
(`MyoMotor.java:421`), then fires `ontoFilament(seg, arcOnFil)` → `tipLink.setAttachment(seg, arcOnSeg)`
(`MyoMotor.java:422,456`). `setAttachment` stores `posOnSeg = pos` **once** (`MyoFilLink.java:85-91`).

Each step `updatePos()` rebuilds the attachment point as
```
attachPt = mySeg.freshEnd1AsPt3D() + posOnSeg · mySeg.uVecAsPt3D()      // MyoFilLink.java:288
```
`posOnSeg` is assigned **only** at `setAttachment` (`:87`) and zeroed at `release` (`:309`) — grep over
`boxOfActin/` confirms there is **no other writer**. So `attachPt` is a **fixed material point** at constant
arclength `posOnSeg` from end1. It moves rigidly *with* the filament (so it tracks the segment's translation
and rotation), but it does **not** move *relative to the filament material*. **The contact does not slide
along actin.**

The F8 spring (`addForces`, `MyoFilLink.java:181-190`) is a Hookean spring between the **head tip** —
`freshMotorTip = motor.coord + 0.5·myoMotorLength·motor.uVec`, the distal end of the head rod — and that
fixed `attachPt`:
```
dist     = ptDist(freshMotorTip, attachPt);
forceMag = dist * myoSpring;            // myoSpring = 1.0e-9 N/µm (Env.java:791)
F        = unit(attachPt → freshMotorTip) · forceMag;
motor.incForceSum(F, freshMotorTip);    // +F on the head at its tip
F.scale(-1); mySeg.incForceSum(F, attachPt);   // −F on the filament at the fixed site
```

### 2. F9 — `alignUVecTorque`: a pure REORIENTING torque, no contact motion
`MyoFilLink.alignUVecTorque` (`:230-256`) drives the **angle between the head axis (`motor.uVec`) and the
filament axis (`seg.uVec`)** toward a rest angle:
```
angTween  = acos( Dot(seg.uVec, motor.uVec) ) in degrees
angRelaxed = 90° (uncocked) | 120° (cocked)        // Myosin.uncocked/cockedMotor_ActinAngle, Myosin.java:18-19
angD       = angTween − angRelaxed
torsionVec = unit( seg.uVec × motor.uVec )          // rotation axis ⟂ both
torsionMag = myoJ1FracMoveTorq·(π/180)·angD / ((1/motor.bRotGam.y + 1/seg.bRotGam.y)·dt)
seg.incTorqueSum(+torsionVec·mag);  motor.incTorqueSum(−torsionVec·mag);
```
This is a **torque only** — it rotates `motor.uVec` (and an equal-opposite reaction on the segment) about the
axis perpendicular to both axes. It **reorients** the head relative to the filament; it writes **no force** and
**does not touch `attachPt`/`posOnSeg`**. So F9 rotates the head about (effectively) its own center; the
contact stays put. (`F10` = `alignYVecTorque`, `:258-280`, is the analogous torque keeping the head's `yVec`
aligned to the segment's — anti-twist about the long axis. Also torque-only.)

### 3. J1 / J2 and the lever — J1 is the converter swing (0°→60°); the tip stroke is carried by F9
The body is three rigid rods rod→lever→motor(head) joined by two joints (`Myosin.jointConstraints`,
`:363-394`):
- **J1 = lever–motor joint** (`applyLeverMotorJointForce`/`applyLeverMotorJointTorque`, `:185-256`). The
  torque drives the lever↔motor angle to `uncockedLever_MotorAngle = 0°` → `cockedLever_MotorAngle = 60°`
  (`Myosin.java:16-17`). **This is the converter / lever swing.**
- **J2 = rod–lever joint** (`:258-318`), rest angle 96° (`:300`), state-independent — the structural elbow.

Both J1's angle (0→60°) **and** F9's head-actin angle (90→120°) switch together on `isCocked()`.
**Who carries the stroke read at the F8 tip:** the tip is `motor.coord + 0.5·myoMotorLength·motor.uVec`, so
the tip displacement is governed by the rotation of `motor.uVec`. F9 rotates `motor.uVec` directly against
the filament ⇒ moves the tip ∝ `myoMotorLength` (HEAD_LEN). J1 rotates the **lever vs the motor**; with the
head's orientation pinned toward the actin angle by F9, the J1 swing mostly repositions the rod/tail and
contributes ≈0 to the *tip*. This is structurally identical to what v2's `STROKE_VS_ARMLENGTH` measured on
the port (stroke ∝ HEAD_LEN, J1 silent for the tip) — v1 has the same geometry, so the same holds. **The
working stroke is read at the head tip (∝ HEAD_LEN), carried by F9's head reorientation, not the J1 swing.**

### 4. How filament motion is produced — a PIVOT (lever), not a slide
Trace: head bound at a **fixed material site** via the F8 tip spring → on cocking, **F9 reorients the head**
(and J1 swings the lever) → the head tip sweeps an arc → the F8 spring, anchored at the stationary `attachPt`,
converts that tip displacement into a spring force whose **along-axis component shoves the whole filament**
(`mySeg.incForceSum(−F, attachPt)`, `:210`). The filament translates as a rigid body; the contact material
point is unchanged. Net transport over time is **bind → cock/pivot/pull → release → rebind at a fresh nearest
site** (`posOnSeg` only changes at a *new* `setAttachment`). **The transport is a pivot/lever about a fixed
anchor with the filament translating — the contact itself never translates along the filament.** Re-anchoring
is discrete (at unbind/rebind), not a continuous slide.

### 5. Force direction & magnitude
The head delivers the **F8 Hookean spring force** `F = strain · myoSpring` (`myoSpring = 1e-9 N/µm`), directed
along the head-tip → attachPt line. Its **along-filament component** is `forceDotFil = Dot(F, seg.uVec)`
(`:194`) — this is what drives glide; the transverse component is reacted by the binding/alignment geometry.
Magnitude is strain-set, capped by the 12 pN break force (`myosinBreakForce = 12 pN`, `Env.java:799`); the
catch-slip calibration puts the working load in the few-pN range (peak ~6 pN, per the v2 Guo&Guilford recal).
**Glide polarity (−x)** is set by the cocked rest-angle geometry (90→120° head, 0→60° lever) combined with the
binding **orientation gates** — bind requires `motDotFil ≥ myoMotorAlignWithFilTolerance` (−0.4) and
`rodDotFil ≥ 0` (`MyoMotor.java:387-390`) — so a head that cocks pulls the filament consistently toward one
pole (filament −x; the free motor would walk +x toward the barbed end).

### 6. Catch-slip load input — the F8 TIP-attachment spring force, projected on the filament axis
`ckRelease` (`MyoFilLink.java:318-361`) reads two things, both derived from the **F8 tip spring**:
- break-force cap: `if (forceMag > myosinBreakForce·1e-12) release()` — `forceMag = dist·myoSpring` (the F8
  spring magnitude).
- Guo&Guilford catch-slip: `guoCatchSlipProb = kOff·(alphaCatch·exp(−forceDotFil·xCatch/kT) +
  alphaSlip·exp(+forceDotFil·xSlip/kT))` with `forceDotFil = Dot(F8 force, seg.uVec)` (`:347-349`).

**Confirmed: the load that feeds catch-slip is the F8 tip-attachment spring force (its along-filament
component), set in `addForces` at `:194`.** (This is exactly the input v2's canonical motor deliberately
moved to J1/lever strain — `canonical-motor-divergence`.)

### 7. Head freedom — ONE attachment point, orientation free but restored by torque
The head binds actin through a **single** F8 tip↔attachPt spring (one contact point). Its orientation is
**not pinned by geometry** — it is free to reorient/flop, restored only by F9 (`alignUVecTorque`, toward
90/120°), F10 (`alignYVecTorque`, anti-twist), the J1 joint to the lever, and thermal noise. This is the
single-point, torque-restored head that v2's *default* motor ports — and precisely what v2's **canonical**
motor diverges from (canonical pins the head at **two** points so orientation is fixed by geometry).

---

## Corrected vocabulary (for the journal entry and the free-head probe)

In v1, a bound myosin head is anchored to a **fixed material site** on the filament (constant arclength
`posOnSeg`; the F8 spring runs head-tip ↔ that site, and the catch-slip load is that spring's along-axis
component). The power stroke is the head **reorienting** (F9 pivots the head toward the cocked 90°→120°
actin angle; J1 swings the lever 0°→60°), which strains the F8 spring and **translates the whole filament**
as a rigid body — the contact point does **not** move relative to the actin. **"Sliding the head's contact
along actin" is WRONG**; the accurate word is **pivot / lever about a fixed anchor** (with the *filament*
translating). Any along-actin re-anchoring is **discrete**, happening only at unbind→rebind to a new nearest
site — never as a continuous slide of a bound head.

**Which reconstruction the code supports:** jba's memory ("no axial sliding / reorientation about a fixed
anchor") is **CORRECT**. The journal's "the swing slides the head's contact ALONG actin" (06-28/06-29) is
**WRONG** and should not be enshrined.

---

**JOURNAL.md-ready line:**
`2026-06-29 — v1 motor mechanism characterized from BoA-v1ref (READ-ONLY): bound head anchored at a FIXED material site (posOnSeg constant; F8 tip↔site Hookean spring, catch-slip reads its along-axis component), stroke = head REORIENTATION (F9 90→120° + J1 lever 0→60°) straining F8 ⇒ the FILAMENT translates. It's a PIVOT/lever about a fixed anchor, NOT a slide; re-anchoring is discrete at unbind→rebind. jba's "no axial sliding" memory CONFIRMED; journal's "slides along actin" wording is WRONG. → V1_MOTOR_MECHANISM.md.`
