# Sphere-Head Gliding Motor — Results

**Date:** 2026-07-01. Flag-gated, default byte-identical; `BoA-v1ref` untouched.

## The motor
A myosin gliding motor whose head does **not** rotate during the stroke — it is held **⊥ (90°) to the
filament** as a load-bearing strut (both nucleotide states), and the **power stroke is the neck/lever
swinging 0°→60°** (ADP-Pi→ADP). Built on v2's articulated body (rod →J2→ neck →J1→ head), with the head's
tip F8-anchored to the bound actin site.

Base flag `-spherehead`: freeze the head-vs-actin angle at 90° (F9 becomes a compliant ⊥ maintainer, no head
swing) so the J1 neck-swing is the sole stroke. On the dense mat this glided −x but slowly (~1 µm/s net):
the per-cycle transport was skeletal-fast (path speed ~6 µm/s) but **not directed** — the filament wandered.

## Two changes (this work)

**1. Axial swing-plane lock — `-axlock`** (`CrossBridgeSystem.bondForces`, xbParams[10]).
F10 (the head-roll torque) was aligning the head's yVec to the segment's **incidental** yVec, so the swing
plane followed the filament's Brownian roll. Retargeted it to the swing-plane normal
**ŝ = normalize(n̂bed × û_seg)** (n̂bed = bed normal +Z), head-only — a faithful port of BoA
`MyoFilLink.alignYVecTorqueAxial` (compliant k=0.4, no stiff pin). Fixes the swing **plane**: single-motor
axial fraction holds **1.00 at any filament roll** (vs →0 un-locked). Alone, it gave no mat-glide gain — the
swing **direction** was still uncontrolled.

**2. Deterministic directed power stroke — `-dirswing`** (`CrossBridgeSystem.directedSwing`).
The J1 converter drove the swing about `cross(lever, head)`, which is **degenerate at the straight recovery
pose**, so each power stroke picked its direction from numerical noise and **flipped between cycles**.
Replaced by a torque driving the neck toward a **polarity-defined target**
`û_lever* = normalize(cos θ_rest·û_head − sin θ_rest·f̂)` (f̂ = pointed→barbed filament axis), so the neck's
rear sweeps **barbed-ward every cycle**. The J1 angular converter is turned off (its position spring stays).
Per-motor pure (each motor writes only its own lever/head torque slots) ⇒ race-free, no atomics, CPU≡GPU.

Together: axial lock fixes the swing **plane**, directed swing fixes the swing **azimuth**.

## Result
**Single motor (Brownian off):** the stroke is now reliably barbed-ward — every power stroke sweeps the neck
rear +x (barbed), every recovery returns to straight, purely axial. No direction flipping.

**Dense mat — single 2 µm filament, 1000 motors/µm², 1.5 s, dt=1e-5, GPU.** Speed = LS slope of the filament
centroid vs time, projected on the filament axis, over the fully-on-bed window (per
`~/Code/BoA/PROPER_SPEED_ANALYSIS.md`):

| | value |
|---|---|
| **v_axial** | **≈ −3.0 µm/s** (−2.95…−3.03 across t0), pointed-leading |
| **axial fraction** | **1.00** (net ≈ path — fully directed, no wander) |
| avg bound | ~14 |
| body-lengths | ≈ 1.5 filament-lengths/s |

Skeletal-myosin-II gliding range; same regime as the BoA axial-lock reference (−2.09 µm/s). The
**axial-fraction-1.00** (directed) glide is the payoff vs the earlier ~1 µm/s wander.

*Caveat:* v2's `-matbed` has a short (~2.9 µm) −x runway, so the fast filament reaches the bed edge at
t≈1.06 s; the fully-on-bed window is the valid measurement. A `-full` 14×2 bed (BoA geometry) gives a clean
full-1.5 s window.

## Run
```
./run_axlock.sh                                              # single-motor axial-fraction gate (Brownian off)
GlidingHarness -gpu -matbed -dirswing -density 1000 150000   # the mat glide (add -3js <dir> for frames)
```

New/changed: `CrossBridgeSystem.{bondForces axial-lock branch, directedSwing}`, `GlidingHarness`
(`-axlock`/`-dirswing`), `AxLockGateHarness` (single-motor gate/viewer/trace). Detail:
`PHASE2_SPHEREHEAD_AXLOCK_FINDINGS.md`.
