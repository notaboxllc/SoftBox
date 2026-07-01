# Phase-2 sphere-head — HEAD-FRAME converter recast (biologically-defensible power stroke)

**Date:** 2026-07-01. Flag-gated (`-hfswing`), default byte-identical; `BoA-v1ref` untouched. Refactor/
measurement only — no kinetics or geometry retune.

## The head-frame converter law
`-dirswing` drives the neck toward `û_lever* = cos θ·û_head − sin θ·f̂` — a target referencing the filament
axis f̂ directly. The recast (`CrossBridgeSystem.directedSwingHeadFrame`) derives the target from the head's
**own frame only** — no f̂ in the swing law:

```
target û_lever* = normalize( cos θ_rest · û_head − sin θ_rest · (ŷ_head × û_head) )
```

driven by the same compliant converter torque (+T lever / −T head), J1 angular converter off, per-motor
pure (race-free, CPU≡GPU). `ŷ_head` is the head's roll axis (the axial lock holds it at ±ŝ). By Rodrigues
(û_head ⊥ ŷ_head), this is exactly "rotate û_head about the head's hinge axis ŷ_head by θ_rest."

**Biological framing.** Binding orients the head (the ⊥ hold pins û_head; the axial/roll lock pins ŷ_head);
the converter then rotates the neck relative to the **bound head**, about the head's hinge axis, by the
state-dependent rest angle. Actin polarity enters **only through the head's bound pose** — no term in the
swing references the filament, exactly as in real myosin. Same mechanics, first-principles derivation.

**Equivalence condition.** `ŷ_head × û_head = f̂` **iff the head is locked to +ŝ** (vertical û_head, ŷ_head=+ŝ)
— then head-frame ≡ `-dirswing` term-for-term. If the head locks to −ŝ, `ŷ_head × û_head = −f̂` and the neck
sweeps the **opposite** way. So the recast reproduces `-dirswing` **only to the extent the head's roll is
locked to a definite sign.**

## GATE 1 — single motor, Brownian off: IDENTICAL ✓
`AxLockGateHarness -hfswing`: LOCK ON axial fraction **1.000** at every filament roll φ; lever rear sweeps
**+7.80 nm barbed-ward** every power stroke; recovery → straight (θ=0), purely axial. Bit-for-bit the same
behavior as the f̂-referenced `-dirswing`. (The gate sets the canonical vertical/axial pose, i.e. ŷ_head=+ŝ,
so the two laws coincide.)

## GATE 2 — dense mat, d1000, LS-centroid: the speed MOVED (a real finding) ✗
Single 2 µm filament, d1000, 1.5 s, GPU, LS-centroid along axis (PROPER_SPEED_ANALYSIS):

| model | v_axial | axial frac | avg bound |
|---|---:|---:|---:|
| `-dirswing` (f̂-referenced) | **−3.0 µm/s** | 1.00 | 14.3 |
| `-hfswing` (head-frame) | **−1.86 µm/s** | 0.999 | 13.7 |

The head-frame recast still glides −x and still fully directed (axial fraction 0.999), but at **~0.62× the
speed**. Per the task's gate: *the speed moved ⇒ the head frame is NOT as fully locked as assumed.*

## Root cause — the axial lock pins the roll AXIS, not its SIGN (roll census)
`-rollcensus` (time-averaged over 20,515 bound-head samples across the 1.5 s run) — the sign of `head.yVec·ŝ`:

```
+ŝ = 50.6%   −ŝ = 49.4%    (essentially 50/50)
```

The axial/roll lock (`alignYVecTorqueAxial`, aligning `head.yVec` to the **nearer of ±ŝ**) drives the roll
axis to |head.yVec·ŝ| → 1 but leaves the **sign a free DOF** — ±ŝ are equiprobable (motors assemble with
head.yVec ⟂ ±ŝ, so binding + Brownian break the tie ~symmetrically). So ~half the bound heads have
ŷ_head=−ŝ and, under the head-frame law, sweep their neck **pointed-ward**.

- `-dirswing` is **robust** to this — it re-derives the direction from f̂ every step, so both ±ŝ heads sweep
  barbed-ward. That is exactly why it read −3.0 with axial fraction 1.00.
- `-hfswing` **exposes** it — the −ŝ heads sweep backward, dragging the net down to −1.86.

**Why not zero?** The naive "each −ŝ head cancels a +ŝ head" gives net factor (1−2p) ≈ 0.01 at p=0.49 — but the
observed factor is 0.62, and the drift stays cleanly axial (0.999). So the wrong-sign heads do **not** simply
cancel: load-dependent catch-slip preferentially releases heads fighting the glide, so the productive (+ŝ)
population dominates the net while both populations act within the same axial plane. (1−2p) is only a naive
upper bound on the loss.

## What it says about the head lock (the point)
"Binding orients the head" is **partially** realized in the current model: the ⊥ hold pins `û_head` and the
axial lock pins the roll **axis** |ŷ_head|, but the roll **sign** is unset. A fully stereospecific head
orientation — the sign included — is what real myosin's actin-binding interface provides, and it requires
**polarity information at bind**. Our lock derives ŝ from the bed normal × filament axis, which fixes the
axis but is sign-agnostic. So:

- The head-frame converter is mechanically equivalent to `-dirswing` **only under a complete (sign-fixed)
  head lock.** Under the present lock it is **not** — hence the −1.86 vs −3.0.
- This is a property of the **lock**, not the converter: the recast is the correct biological formulation; it
  simply reveals that the head is not yet fully orientation-locked. Not tuned (per the task constraint).

**To make the head-frame swing reproduce −3.0** (future, not done here): set the roll **sign**
stereospecifically at bind — i.e. align `head.yVec` to a definite +ŝ (not the nearer of ±ŝ), which the bind
already has the polarity to determine. That is a one-line change to the lock's sign convention, deferred as a
separate faithfulness step (it re-baselines the lock). For now, `-dirswing` (f̂-robust) remains the working
gliding model at −3.0 µm/s; `-hfswing` is the biologically-honest formulation that surfaced the missing
roll-sign DOF.

## Run
```
./run_axlock.sh -hfswing                                           # single-motor gate (identical to -dirswing)
GlidingHarness -gpu -matbed -hfswing -density 1000 -3js <dir> 150000    # mat glide (−1.86 µm/s)
GlidingHarness -gpu -matbed -grid -hfswing -rollcensus -density 1000 150000   # the roll-sign census
```

New: `CrossBridgeSystem.directedSwingHeadFrame`, `-hfswing`/`-rollcensus` (`GlidingHarness`),
`AxLockGateHarness -hfswing`. Production byte-identical; `BoA-v1ref` byte-clean.
