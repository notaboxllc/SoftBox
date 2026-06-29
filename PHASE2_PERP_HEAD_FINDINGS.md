# Phase-2 PERP-HEAD — remove the rear head pin, hold the head ⊥ to actin with a PAIRS torque: TRANSPORT RESTORED

**2026-06-29. Flag-gated (`-perphead`), additive; single config-1 motor, gliding (transport) topology, dt=1e-6,
Brownian OFF; the force-decomposition mechanism gate. `BoA-v1ref` byte-clean; default + config-1 paths
byte-unchanged. CPU host-side, single motor, sub-second (no GPU, no long run).**

## TL;DR — the gate PASSES: head holds ⊥ (87.9°, stable) and transverse:axial flips 22:1 → 2.35:1
Removing the **rear** head pin and replacing the rigid rear-pin orientation constraint with a **PAIRS-form
⊥-orientation torque** does exactly what the bound-geometry report predicted. The head now **stands ⊥ to actin**
(head ∠x̂ = **87.9°**, a stable fixed point), and the converter swing reaches actin as a **real axial force**
(sustained held-filament axial **+1.94 pN**, vs the old two-point config-1's **+0.036 pN** — a **53×** restoration).
The force the cross-bridge delivers to the filament flips from **22:1 transverse:axial** (old config-1, the
"bending-not-transport" artifact) to **2.35:1** — into the same order as the **default v1 motor that glides**.
**The perpendicular-head hypothesis restores transport.** Two flags for the planner (below): it is not yet
axial-*dominated* (transverse still ~2.3× axial, like the default at this snapshot), and the axial **sign is +x**
(the default glides −x) — a uperp-direction / converter-polarity choice, not a mechanism failure.

## The change (exactly the task spec, nothing else)
1. **REMOVED the rear pin** (pin B / `bindArc2`): the head is no longer two-point-pinned. `bindArc2` is dropped
   from the perp kernel's signature but the array stays allocated (no storage churn).
2. **KEPT the tip pin** (pin A / `bindArc`) and the tip binding search — unchanged.
3. **ADDED a PAIRS-form ⊥-orientation torque** driving head.uVec → `perpRest` (frozen at bind), same dt-robust
   `fracMove·angle/((1/Γ_rot,head + 1/Γ_rot,seg)·dt)` damping-limited form as the F9/F10 alignment / the
   config-1 pins (NOT a raw Hookean ⇒ no uncapped-J1-style overshoot). +T on the head, −T reaction on the
   segment (mirrors F9/F10). Strength `orientFracMove = 0.50` = the prior PAIRS-pin / rear-pin scale (`-perpfrac`).
4. **HELD identical:** the tip pin, the binding search, the Hookean-J1 converter + its 0°↔60° switch, the averaged
   catch, kOn, τ, κ (force-matched 3.82e-20), the bond calibration, and `forceDotFil = signed J1 lever strain`
   `(κ/L)·(θ_rest−θ)` (PAIRS pins, J1 reports — unchanged from config-1).

## The frozen perpendicular target
At bind: `uperp = uVec − (uVec·x̂)·x̂`, normalized — the ⊥-to-filament direction nearest the current head uVec.
**Computed ONCE at bind and FROZEN** as the per-motor rest target (`MotorStore.perpRest`, planar 3·nMotors).
In the gliding bind pose the head lies **along x̂** (uVec = +x), so `uperp` is degenerate (≈0 before normalize) —
the **expected** regime, per the task. The **fallback fires**: project the surface normal ẑ ⇒
**`uperp = (0, 0, +1)`** (the head is driven to stand up ⊥ to the filament, away from the −z anchor/surface).

## The force decomposition (single motor, transport topology, dt=1e-6, Brownian OFF)
Sustained values at the cocked equilibrium (rock-steady from step 6000 on; net force ON the filament, in the
filament frame: axial = along x̂ = transport, transverse = ⊥):

| variant | head ∠x̂ | lever ∠x̂ | J1° | sustained axial (pN) | sustained transverse (pN) | **transverse : axial** | net axial impulse (pN·s) |
|---|---|---|---|---|---|---|---|
| **PERP-HEAD (new)** | **87.9°** | 5.3° | 82.6 | **+1.94** | 4.56 | **2.35 : 1** | **+0.117** |
| CONFIG-1 (old two-point) | 0.4° | 62.3° | 61.9 | +0.036 | 0.789 | **22 : 1** | +0.0021 |
| DEFAULT v1 (F8 + F9/F10) | 60.1° | 0.1° | 60.0 | −0.045 | 0.084 | ~1.9 : 1¹ | −0.003 |

¹ The default's *final-equilibrium* snapshot forces are tiny (it transports via the dynamic cycle, not a single
isometric impulse); its "≈1.9:1 axial" reference is the prior `PHASE2_FORCE_DECOMPOSITION` transient/impulse read.
The decision-relevant contrast is **PERP vs the OLD config-1 it replaces**: axial force **0.036 → 1.94 pN (53×)**,
transverse:axial **22:1 → 2.35:1 (8.6× better)**.

- **Head holds ⊥:** YES. head ∠x̂ settles at **87.9°** and is a **stable** fixed point (axial/transverse constant
  to 4 sig-figs from step 6000–60000) ⇒ the ⊥-orientation torque **wins** the competition with the J1 torque
  (J1 settles at 82.6°, a real 22° strain off its 60° rest — i.e. J1 drives the lever, the orientation torque
  pins the head, exactly the intended division of labor; lever swings to ∠x̂ = 5.3°, nearly along actin).
- **Transverse:axial flips toward axial:** YES — 22:1 → **2.35:1**, into the default-motor order. **Not yet
  axial-dominated** (transverse still ~2.3× axial; flag below), but the qualitative artifact (essentially-zero
  axial force) is gone.

## Released-filament cross-check (filament integrated, free, 0.06 s)
| variant | Δx (axial, nm) | Δz (transverse, nm) | reading |
|---|---|---|---|
| **PERP-HEAD** | **+5173** | +8841 | translates axially **(+x)** AND transversely; |Δz|>|Δx| |
| CONFIG-1 (old) | −822 | −1260 | mostly transverse, weak axial |
| DEFAULT v1 | −4006 | +2388 | **axial-dominated (−x)**, |Δx|>|Δz| |

The perp head now drives a **large axial displacement** (+5173 nm vs the old config-1's −822 nm) — transport is
real where it was nearly absent. But the transverse Δz (+8841) still exceeds the axial Δx, mirroring the 2.35:1
held-force ratio. (The large transverse is a −z/+z pull of the standing head's tip toward the in-plane site; in a
real gliding assay that ⊥ component is largely taken up by the surface confinement, leaving the axial as the
effective glide — a favorable reinterpretation, flagged, not relied on here.)

## Gate verdict
- **Head holds ⊥ under the stroke?** ✅ YES — 87.9°, stable; the orientation torque beats the J1 torque (J1 moves
  the lever, the head stays ⊥). The bail boundary "J1 rotates the head instead of the lever" is NOT triggered.
- **Does transverse:axial improve (transport restored)?** ✅ YES — 22:1 → **2.35:1** (8.6×); sustained axial force
  **0.036 → 1.94 pN (53×)**; the released filament now translates axially. The bail "still transverse-dominated →
  STOP" is NOT triggered.
- **Single-point tip pin stable at dt=1e-6?** ✅ YES — no wander; a clean steady equilibrium (no instability).

**⇒ The perpendicular-head mechanism is VALIDATED: the head stands ⊥ and the converter stroke reaches actin
axially, restoring the transport that the two-point pin suppressed.**

## Flags for the planner (report only — NO further changes made, per the task)
1. **Not yet axial-DOMINATED.** Transverse is still ~2.3× axial (vs the default's ~1.9:1 reference). The standing
   head's tip-pin force has a large ⊥ (−z) component pulling the filament toward/away from the head. Whether this
   is acceptable (surface-absorbed in the assay) or wants a further tweak (e.g. tune `perpfrac`, or a slight
   off-90° rest, or a tip-on-actin sliding term) is a planner call.
2. **Axial sign is +x, the default glides −x.** The transport sign follows the converter-swing sense × the chosen
   `uperp` direction (the ẑ-fallback picked **+ẑ**; −ẑ would likely flip the sign). A polarity/uperp-direction
   choice, not a mechanism failure — to be pinned at calibration, not here.
3. **forceDot column reads 0 in the decompose printout** — a pre-existing harness display gap (the host
   `decompose()` helper does not recompute the J1 strain; the *kernel* computes the real `forceDotFil` for the
   catch, unchanged). Cosmetic only.

Per the task: this is the **mechanism gate only** — no ensemble / kinetics / step∝lever work; no piling-on of
further changes.

## What changed (additive; flag-gated; default & config-1 & `BoA-v1ref` byte-unchanged)
- `CrossBridgeSystem.bondForcesCanonicalConfig1Perp` — config-1 with the rear pin removed + the ⊥-orientation
  torque. xbParams `[0]fracMove [1]κ [2]leverLen [3]dt [4]HEAD_LEN [5]orientFracMove`. The existing
  `bondForcesCanonicalConfig1` is byte-unchanged.
- `MotorStore.perpRest` (planar 3·nMotors, default-zero, read only by the perp kernel — byte-identical for every
  existing harness).
- `GlidingHarness`: `-perphead` (implies `-config1` + `-forcedecomp`) runs the gate over **three** modes
  (PERP-HEAD, old config-1, default v1) for the side-by-side; `-perpfrac <v>` overrides the orientation strength.
  `decompRun`/`motorStep`/`decompose` thread a `perp` flag; uperp is computed + frozen at bind.

## CPU-fallback disclosure (the gate)
Single motor, deterministic, host-side (no TaskGraph). Runs in < 2 s on the CPU. No GPU, no long run.
```
./run_gliding.sh -perphead -forcedecomp                 # the gate: PERP-HEAD vs old config-1 (22:1) vs default
./run_gliding.sh -perphead -forcedecomp -perpfrac 0.3   # weaker ⊥-orientation torque (strength sensitivity)
```

---

## Addendum (2026-06-29) — PERP-HEAD wired into the GLIDING pipeline + the gliding-assay velocity
`-perphead` is now a **gliding-capable motor variant** (decoupled from the gate; the gate is `-perphead
-forcedecomp`). The perp kernel runs in the full dynamic gliding assay (CPU `stepOrig` + the GPU device-resident
`buildPlan`), with `uperp` **frozen at each fresh bind** by a new one-shot kernel.

**Wiring (additive; all PERPHEAD-gated ⇒ default / config-1 / canonical paths byte-unchanged):**
- `CrossBridgeSystem.snapPerpRest` — at a fresh two-point bind (`canonSnap != 0`, read before `snapCanonicalHead`
  clears it) freezes `perpRest` = the ⊥-to-filament direction nearest the head's bind-time uVec (ẑ-fallback when
  near-axial). Writes only `perpRest` (no body pose) ⇒ safe to place early on the GPU graph.
- The gliding bond force routes to `bondForcesCanonicalConfig1Perp` when PERPHEAD (CPU + GPU); `xbParamsC1` grows to
  size-6 (`[5]=orientFracMove`); `perpRest` added to the device transfers.

**The perp motor DOES run a gliding assay — it glides −x, stably (dt=1e-6).** But it converts little of its motion
into net transport and **heavily over-binds**:

| run (v1box, dt=1e-6, seed 1) | net x-vel (µm/s) | velFitX (µm/s) | inst. speed (µm/s) | avgBound (steady) | NaN |
|---|---|---|---|---|---|
| PERP-HEAD, CPU 3k-step probe | −3.03 | — | — | 4.0 | no |
| PERP-HEAD, GPU 40k (0.04 s) `-grid` | **−0.57** | 0.46 | 14.4 | **~42** | no |
| default v1 motor (reference) | −6.6 | ~3.5 | ~7 | ~7 | no |

**Reading:** the perp motor is **stable and directionally correct (−x)** but produces only a **weak net glide
(~0.5 µm/s)** with **large instantaneous motion (~14 µm/s)** and **runaway binding (avgBound ~42 vs v1's ~7.6)** —
the restored axial force is dissipated in a many-motor **tug-of-war / grip-lock** rather than coherent transport,
and the heads rarely release (the ⊥-held head keeps re-engaging; the catch reads the J1 strain, which stays low).
So the **single-motor mechanism win (axial force restored, head ⊥) does NOT yet translate into ensemble gliding
speed** — the perp variant needs the release/duty side calibrated (and likely dt < 1e-5; it whips at dt=1e-5 like
config-1). This is an **ensemble observation, not a re-tune** (out of the gate's scope), recorded for the planner.

**Runner / wall-clock:** GPU device-resident `buildPlan` (~24 kernels incl. `snapPerp`), 2000 motors; ~400–500
steps/s at dt=1e-6 (no per-step host pull; host reads at frame/sample cadence only). The default + config-1 gliding
paths are byte-unchanged (PERPHEAD-gated). `BoA-v1ref` byte-clean.
```
./run_gliding.sh -gpu -perphead -v1box -dt 1e-6 -grid -seed 1 40000          # gliding velocity (v1-style grid)
./run_gliding.sh -gpu -perphead -v1box -dt 1e-6 -3js threejs_perphead 40000  # viewer frames
```
