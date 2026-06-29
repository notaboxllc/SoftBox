# Phase-2 FORCE DECOMPOSITION — where the config-1 powerstroke force goes in the transport topology (why no glide)

**2026-06-28. MEASUREMENT-ONLY (`-forcedecomp`); single motor, deterministic, dt=1e-6, Brownian OFF; NO physics edit,
no fix, no retune, no default change. `BoA-v1ref` byte-clean. CPU host-side, single motor, sub-second (no GPU/long
run).**

## TL;DR — VERDICT: DIAGNOSIS 2 (MISPROJECTED / TRANSVERSE STROKE). NOT a cancelling couple; NOT (robustly) inverted.
The config-1 cross-bridge **does** generate force in the transport topology, but it delivers it ~**22:1 transverse
: axial** — i.e. **overwhelmingly perpendicular to the filament (it bends/lifts it), with only a small fraction along
the filament (transport)**. That is the cause of the SET-A **weak** glide: at converged dt=1e-6 the ensemble does
glide −x but only **~0.5–2 µm/s** (noisy), vs the **default v1 motor's −5.7** and experiment's 2.9 — because most of
the stroke force is spent bending, not translating. The two PAIRS pins do **NOT** form a cancelling couple (diagnosis
1 ruled out): their axial components **ADD** (same sign), they don't oppose. Diagnosis 3 (inverted polarity) is **NOT
robustly supported** — the ensemble (and the released filament) glide −x (correct direction); the single-motor
*isometric* axial residual reads +x but it is tiny (0.036 pN) and confounded (it flips to −x when the filament is
released), so polarity is not the story — **the magnitude misprojection is.** The **default v1 motor**, same setup,
delivers a clean **−x axial** force (transport) with transverse only ~2× the axial — the reference for "what a
transport-delivering stroke looks like." **Reported, not fixed** (per the bail boundary: transverse ≫ axial is a
geometry question for the planner).

## Setup
A SINGLE motor, tail anchored (the gliding substrate attachment), head two-point-pinned to a HELD 1-segment filament
(along +x), fires ONE powerstroke (J1 nucleotide rest **0°→60°**) at dt=1e-6, Brownian OFF. The motor stands roughly
**perpendicular** to the filament (rod+lever hang down to the anchor at z<0; head lies along the filament at z=0) —
**this is the real gliding bound geometry**, not a contrived pose. Force on the filament = the seg-side reaction; the
two pins (tip→site A `bindArc`, rear→site B `bindArc2`) are recomputed host-side (replica of the kernel's PAIRS
formula — no kernel touched) and projected on the filament axis segU. κ = 3.82e-20 (force-matched, stall 5 pN).

## The decomposition (isometric; force the cross-bridge delivers to the filament, pN)
| | axial A (pN) | axial B (pN) | **net axial** | **net transverse** | transverse : axial | net axial impulse |
|---|---|---|---|---|---|---|
| **CONFIG-1** (equilibrium) | **+0.017** | **+0.019** | **+0.036 (+x, tiny & confounded)** | **0.789** | **22 : 1** | +0.0021 pN·s |
| CONFIG-1 (stroke transient t=0) | +0.156 | +0.173 | +0.329 (+x) | 3.726 | 11 : 1 | — |
| **DEFAULT v1** (equilibrium) | −0.045 | (n/a) | **−0.045 (−x, CORRECT)** | 0.084 | 1.9 : 1 | −0.0030 pN·s (−x) |

- **Pins ADD, they do NOT cancel:** axial_A=+0.017, axial_B=+0.019 (same sign) ⇒ `|net|/(|A|+|B|) = 1.000`. There
  is **no cancelling axial couple** (diagnosis 1 is RULED OUT). The opposition between the two pins is in the
  **transverse (z)** channel — a *bending* couple, not an axial one (this is the filament whipping seen in the SET-A
  viewer).
- **Transverse dominates ~22:1** (equilibrium) / ~11:1 (transient): the powerstroke force on the filament is
  **overwhelmingly perpendicular** to the transport axis ⇒ **DIAGNOSIS 2 (misprojected stroke)**.
- **The residual axial is tiny and its sign is confounded** (isometric +x, released −x, ensemble −x). Polarity is
  not the story; the small axial *magnitude* (only 1/22 of the force) is — most of the stroke bends, not transports.
- **Default contrast:** the v1 motor delivers a clean **−x axial** force (−0.045 pN), transverse only ~2× the axial
  — its stroke projects onto the transport axis. config-1 is **~12× more transverse-biased** than the default.

## Released-filament cross-check (free filament; qualitative — confounded by single-motor free sliding)
The filament jumps ONCE to a new equilibrium (by t≈2000 steps) then holds (force → 0):
- **CONFIG-1:** Δx = **−822 nm**, Δz = **−1260 nm** ⇒ **transverse-biased motion** (more ⊥ than along the axis).
- **DEFAULT:** Δx = **−4006 nm**, Δz = +2388 nm ⇒ **axial-biased motion** (−x transport dominates), ~5× more axial
  travel than config-1.
Directionally consistent with the isometric decomposition (config-1 transverse-dominated, default axial/transport-
dominated). NOTE: a single motor on a free 1-segment filament repositions in large one-time jumps and shows a
held-force-vs-released-motion sign subtlety (config-1's tiny +x equilibrium axial vs its −x released jump), because a
transverse-dominated force reconfigures the free filament's geometry — so the cross-check is **qualitative
(direction), not a clean quantitative closure**. The clean measurement is the isometric decomposition above.

## Mechanism — why the stroke misprojects (geometry)
> **REFINED by `PHASE2_BOUND_GEOMETRY_FINDINGS` (2026-06-29):** the swing plane actually **CONTAINS** the filament
> axis (x–z), and the lever load end sweeps axially — the real root is the **two-point head pin** (the head can't
> slide on actin, so the axial swing goes to the anchor and the head transmits only the transverse torque reaction).
> The "perpendicular" phrasing below is the loose first-pass; the bound-geometry report is the precise account.

The J1 converter torque is about `tv = lever.uVec × head.uVec`. In the gliding bound geometry the **lever/rod point
≈ +z (down to the anchor)** and the **head points ≈ +x (along the filament)**, so `tv ≈ +z × +x = +y`. The J1 stroke
therefore **rotates the head about +y**, moving the head tip in **z (perpendicular to the filament's +x axis)** — a
**transverse** displacement of the pinned head ⇒ a **transverse force** on the filament. The converter swing does
**not** project onto the transport (x) axis because the motor stands perpendicular to the filament. The **default**
motor transports by a *different* mechanism — the F9 cross-bridge torque pivots the head ON the actin (90°→120°),
sliding the contact ALONG the filament (the "effective stroke = HEAD_LEN" result) — which DOES project onto +x.

So the config-1 architecture's clean lever-arm converter (the single-molecule thesis win: step∝lever, peak≈stall)
delivers its stroke **perpendicular to the filament in the gliding geometry** ⇒ it **bends** the filament (the
whipping at dt=1e-5) and **barely transports** (only ~1/22 of the force is axial ⇒ the weak ~0.5–2 µm/s glide at
dt=1e-6, vs the default's 5.7) — one mechanism explains BOTH SET-A findings.

## The three diagnoses — adjudicated
1. **CANCELLING COUPLE — RULED OUT.** The two pins' axial forces ADD (+0.017, +0.019), not oppose; `|net|/(|A|+|B|)=1.0`.
2. **MISPROJECTED / TRANSVERSE STROKE — CONFIRMED, DOMINANT.** transverse : axial ≈ 22:1 (equilibrium), 11:1
   (transient); the force is ~95% perpendicular to the transport axis.
3. **INVERTED POLARITY — NOT robustly supported.** The single-motor *isometric* axial residual is +x (wrong sign)
   but negligible (0.036 pN) AND confounded — it flips to −x when the filament is released, and the **dt=1e-6
   ensemble glides −x** (velFitX +0.45 / +2.44 @ density 1000, +1.59 @ density 2000 — weak but correct direction).
   So polarity is not inverted; the **magnitude misprojection (diagnosis 2)** is the cause.

## Bail boundaries — outcome
- Diagnosis 2 (transverse ≫ axial) + a wrong-signed residual (diagnosis 3): **REPORTED, NOT edited** (the fix is a
  geometry/mechanism decision for the planner — e.g. how the converter swing is made to project onto the filament
  axis in the perpendicular gliding geometry, or whether transport must come from a head-pivot-on-actin term like the
  default's F9 rather than the J1 converter swing). Do NOT silently change the geometry.
- "Force is generated but mis-delivered" CONFIRMED (force IS generated — transient net 0.33 pN axial + 3.7 pN
  transverse — but mis-delivered transverse), so the expected premise holds (not the "no force at all" surprise).

## What this means for the planner
The SET-A weak-glide (Thread 2) is now mechanistically located: **the config-1 J1-converter powerstroke, in the
perpendicular gliding geometry, projects ~22:1 onto the filament TRANSVERSE axis (bends), with only ~1/22 axial
(transport)** — so the ensemble glides −x but only weakly (~0.5–2 µm/s vs the default's 5.7).
This is NOT a kinetics issue and NOT a cancelling-couple structural dead-end — it is a **stroke-projection geometry**
question. Candidate directions (planner's call, NOT done here): (a) a transport term that pivots the head ON actin
along the axis (the default's F9 mechanism, which the canonical re-architecture dropped); (b) a converter-swing axis
that lies in the filament plane so the stroke projects axially; (c) revisit the two-point head pin (which fixes the
head orientation to the filament, so the converter swing can only move the head perpendicular). The whipping (Thread
1, numerical) and the no-glide (Thread 2, this geometry) share the same root: a transverse-delivered stroke force.

## What changed (additive; measurement-only; default & `BoA-v1ref` untouched)
- `GlidingHarness.java`: `-forcedecomp` (single-motor transport-topology force decomposition: `forceDecomp` /
  `decompRun` / `motorStep` / `decompose` / `moveCH` — a host replica of the PAIRS pin formula; NO kernel edited).
```
./run_gliding.sh -forcedecomp     # single motor, dt=1e-6, Brownian off: per-pin axial/transverse + default contrast + released cross-check
```

## CPU-fallback disclosure
Single motor, deterministic, host-side (no TaskGraph) — the whole measurement runs in < 2 s on the CPU. No GPU, no
long run.
