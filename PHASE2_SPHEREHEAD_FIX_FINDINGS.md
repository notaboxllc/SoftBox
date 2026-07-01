# Phase-2 — sphere-head FIX: restore the three-body topology, re-measure → BAIL (the stroke lives in the head–actin angle)

**2026-06-30. New files / flag-gated only ⇒ production byte-identical; `BoA-v1ref` byte-clean. Single-motor
gate ONLY — NO glide claim (standing rule: glide requires the full-mat assay; not run here).**

## TL;DR
- **The body-count collapse is FIXED.** The motor is again **rod → (J2) → neck/lever → (J1) → HEAD**, three
  distinct rigid bodies, both joints intact; the head is a **sphere of real extent** (R=10 nm) carrying the
  actin attachment, standing the neck off the filament. The viewer now shows **three distinct bodies** (the
  old two-body "T" is gone).
- **But the restored motor does NOT transport, and this triggers the bail.** With F9 (the head-vs-actin
  angle) deleted per the sphere abstraction, the **J1 converter swing is PERPENDICULAR to the filament** — it
  bends the head in Z (the lever's plane), delivering **~0 axial** motion to actin (M1 axial Δx −0.22 nm of a
  2.25 nm Z-dominated swing; M3 +0.78 nm, +X, ≈0). The step did **not** recover from −4.22 nm — there is no
  directed axial stroke at all.
- **Root cause — the powerstroke's directedness LIVES in F9 (the head–actin angle), the very thing the
  "sphere" abstraction deleted.** v1's F9 drives the head angle **to the filament axis**, so the contact sweeps
  *along* actin (axial). The J1 converter drives the neck angle **to the head**, so it bends *perpendicular* to
  actin. A free, orientation-free sphere head has **no actin reference**, so the neck-swing has no axial
  direction. (BoA Run 2 transported precisely because it **held the head** — supplying that actin reference.)
- **Per the bail rule: STOP and report.** Making the neck-swing transport requires the head oriented relative
  to actin — i.e. F9 (the deleted angle) or **holding the head** (an orientation constraint). Both are the
  head-orientation mechanism the abstraction removed. **The sphere abstraction needs rethinking, not a pin.**
  I did NOT stiffen J1 or pin the head (J1 stayed a free articulating joint, head kept its single compliant
  attachment) — the bail is "the abstraction is wrong," not "pin-absorption from over-constraining."

## The fix (the three-body topology, restored)
- **rod → (J2) → neck → (J1) → HEAD**, the v1/default articulated body (`MotorJointSystem.joints` UNCHANGED:
  J2 connection + J1 converter, rest 0°→60° on cocking; `j1FracMoveTorq=0.4` confirmed active).
- **Head = its own rigid body, a sphere** (rendered R = ½·HEAD_LEN = 10 nm). It keeps a body frame **for J1
  only**; there is **no head-vs-actin angle (F9 stays deleted)**.
- **The head carries the actin attachment:** `SphereHeadSystem.sphereBond` anchors the **head TIP**
  (head.center + ½·HEAD_LEN·head.uVec = v1's F8 contact, OFF-centre) ↔ the fixed filament site, force **+**
  the R×F torque (the off-centre contact couples head rotation to actin — a centre anchor would let a free
  sphere spin in place and absorb). Catch-slip load = anchor force along the filament axis. Reaction on the
  filament.
- **Rod tail = the v1-faithful COMPLIANT bed anchor** (`TailAnchorSystem`, point-spring, rod orientation
  free — Part-1 result). NO rigid clamp.

## Re-measurement (single motor, dt=1e-6)
**Two failure layers surfaced, both pointing to the same root:**
1. **Collinear degeneracy (self-start):** the J1 bend axis is `cross(lever, head)`, which is **zero when the
   neck and head are collinear** — and the uncocked rest is exactly **0° (collinear)**. So from the uncocked
   equilibrium the converter has no torque axis and **cannot deterministically initiate the stroke**: M1 = 0.00
   nm, M3 J1 θ stuck at 0°, filament 0. (v1's F9 has no such degeneracy — it references the filament axis,
   never collinear with the head; that is *why* v1's stroke is F9, not J1.)
2. **Perpendicular swing (directedness):** breaking the degeneracy with a 25° initial head tilt, the converter
   **does** swing (θ→60°) — but the head moves **(−0.22, 0, −2.24) nm**, i.e. **Z-dominated** (axial fraction
   0.10); the free filament moves **+0.78 nm (+X, ≈0)**. The converter bends the head ⊥ to actin ⇒ **no axial
   transport.**
- **M2 impulse-free bind: +0.000 nm** (clean, the head's single compliant attachment is at rest at bind).
- **M4 thermal fair test** (motor Brownian ON to break the degeneracy the real-motor way, n=4, hold cocked):
  stroke-ON net −25.8 nm vs stroke-OFF (converter torque=0) net −36.7 nm ⇒ ON−OFF = **+11 nm (no clean −X
  signal)**. Inconclusive in magnitude (the 1-seg filament's thermal floor is large/noisy at n=4) but
  **consistent with no directed transport** — the converter does not bias the filament −x.

## Re-render (the visual acceptance test) — PASS for the topology
`threejs_spherehead/` (320 frames, 2 cycles, dt=1e-6, neck ×3 for clarity): **three distinct bodies** — long
thin tube = ROD/tail, short tube = NECK, big ball = HEAD/catalytic domain standing the neck OFF the filament
and binding it. The body-count collapse is fixed. The frames also **show the failure**: the head bends in Z
(perpendicular) as the converter fires, and the filament barely moves (centroid Δ +0.9 nm) — the ⊥ swing /
no-axial-transport, visible.

## Verdict / the rethink the bail asks for
- **Topology fix: DONE** (three bodies, J1/J2, head sphere with extent, head carries the attachment, compliant
  tail). J1 stayed free, head not re-collapsed, nothing stiffened.
- **Transport: NO — BAIL.** The "head-to-filament angle is a non-physical artifact" premise is **wrong**: that
  angle (F9) is **where the powerstroke's axial directedness is**. Deleting it — whether by collapsing to two
  bodies (the previous bug) or by a free orientation-free sphere head (this build) — removes the stroke. The
  neck/J1 converter cannot supply axial direction because it is referenced to the head/lever, not actin.
- **What the faithful model needs (for the planner):** the head's **orientation relative to actin** is
  load-bearing. A real catalytic domain binds actin in a *defined pose* and the converter swing is referenced
  to that pose. Options to restore directedness without a rigid pin: (a) keep a **compliant head↔actin angular
  reference** (a soft F9-like restraint toward the actin axis — the directedness, not a rigid clamp); (b) the
  **two-point/footprint head** that fixes orientation by *geometry* on actin (but that was the canonical
  two-point pin that absorbed the stroke — so it must be compliant, not rigid); (c) accept v1's F9 as the
  faithful stroke and drop the "orientation-free sphere" goal. The sphere-with-no-orientation is the part to
  rethink.

## Reproduce
```
java … softbox.SphereHeadHarness -dt 1e-6                      # M1–M4 (degeneracy + perpendicular swing + thermal)
java … softbox.SphereHeadHarness -dt 1e-6 -vizexag 3 -3js threejs_spherehead   # three-body frames
```

## JOURNAL-ready line
`2026-06-30 — sphere-head FIX: restored the three-body topology (rod→J2→neck→J1→HEAD-sphere; head a distinct
body of real extent carrying the actin attachment at the tip = v1's F8 contact; compliant tail; viewer shows
3 distinct bodies, the 2-body "T" is gone). BUT re-measure → BAIL: with F9 (head-vs-actin angle) deleted, the
J1 converter is (1) DEGENERATE at the collinear 0° uncocked rest (cross(lever,head)=0 ⇒ can't self-start; M1=0,
θ stuck) and (2) when started (head tilted 25°) swings PERPENDICULAR to actin (head Δ Z-dominated −2.24 nm,
axial −0.22 nm; free filament +0.78 nm +X ≈0) ⇒ NO axial transport. Root cause: the powerstroke's axial
directedness LIVES in F9 (head-vs-FILAMENT, references the actin axis), the very angle the sphere abstraction
deleted; the J1 converter references the head/lever ⇒ bends ⊥ actin. BoA Run 2 transported only because it
HELD the head (supplied the actin reference). J1 kept free, head not pinned ⇒ the bail is "the orientation-free
sphere abstraction is wrong," not pin-absorption. Faithful model needs the head's actin-orientation (a
compliant F9-like reference, or accept F9). NO glide claim (standing rule). New files only ⇒ production
byte-identical; BoA-v1ref byte-clean. → PHASE2_SPHEREHEAD_FIX_FINDINGS.md.`
