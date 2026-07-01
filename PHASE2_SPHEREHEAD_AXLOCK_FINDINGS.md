# Phase-2 sphere-head — the AXIAL SWING-PLANE LOCK (§9.4c) port

**Date:** 2026-06-30. **Scope:** port the BoA `myoAxialSwingLock` (the fixed-head/neck-stroke motor's
axial roll lock) to v2, retargeting F10 from the segment's incidental yVec to ŝ = normalize(n̂bed × û_seg),
head-only. Flag-gated (`-axlock`), default byte-identical, `BoA-v1ref` untouched.

**Headline (honest):** the F10 retarget is correctly ported and **PROVEN at single-motor scale** — with the
intended vertical-head / axial-swing bind pose the lock holds the swing axial (axial fraction ≈ **1.00 at
every filament roll φ**, vs the un-locked swing collapsing to ~0 as the filament rolls). **But on the dense
GPU mat it produces NO net-glide improvement** (velFitX ≈ 0.84 → 0.84 µm/s at d500). The lock is
**necessary but not sufficient**: it *preserves* an axial swing, it does not *create* one from an
uncontrolled bind pose — and v2's sphere-head mat path (`bindNearest`) does **no per-bind pose reset**, so
the per-motor swing azimuth is never seeded axial. The missing co-requisite is exactly the BoA doc's own
§3/§8 open item: a **polarity-derived vertical-head, axial-swing bind pose**. The naive §9.4c retarget
alone does not lift the sphere-head out of the ~1 µm/s (net) regime.

**UPDATE (2026-07-01, §6):** the co-requisite is now built — a **deterministic polarity-directed power
stroke** (`CrossBridgeSystem.directedSwing`, `-dirswing`) that makes the neck sweep barbed-ward every cycle.
With it, the mat glide jumps to **v_axial ≈ −3.0 µm/s, axial fraction 1.00** (pointed-leading, skeletal
range). Lock (head roll) + directed swing (azimuth) together = a directed glide. See §6.

---

## 1. What v2's roll kernel targeted BEFORE vs the ŝ retarget

The dense-mat sphere-head runs on the **default motor's `CrossBridgeSystem.bondForces`** with F9 frozen at
90° (`-spherehead`, xbParams[9]=1). Its **F10** (the roll/azimuth alignment torque) targeted:

- **BEFORE (default):** `â = seg.yVec`, `b̂ = head.yVec`, θ0 = 0°, **two-body** (head −T10, seg +T10),
  drag axis `.x`. This is the BoA bug (`MyoFilLink.alignYVecTorque` targeting the segment's *incidental*
  roll): the head's swing plane then follows the filament's Brownian roll.

- **AFTER (`-axlock`, xbParams[10]=1):** `â = ŝ = normalize(n̂bed × û_seg)` with n̂bed = lab +Z ⇒
  ŝ = normalize(−suy, sux, 0); `b̂ = head.yVec`, θ0 = 0°, **HEAD-ONLY** (no segment reaction; align to the
  nearer of ±ŝ), drag axis `.x`. A faithful transcription of BoA `MyoFilLink.alignYVecTorqueAxial`
  (magnitude form + coefficient `k = myoJ1FracMoveTorq = 0.4` identical; compliant, NOT a stiff pin).

Implementation: `CrossBridgeSystem.bondForces`, flag-gated by xbParams **size** (≤10 ⇒ axLock=0 ⇒
byte-identical default and byte-identical `-spherehead`; size 11 with [10]=1 ⇒ the retarget). The default
head/seg write path is preserved bit-for-bit (`head = TH − T9 + hF10`, `hF10 = −T10` in the default branch
== the original `TH − T9 − T10`; the seg reaction `+T10` unchanged). Race-free / no atomics (head-only
self-write) ⇒ `-cpu` and the CSR gather stay valid.

The head-polar hold (a) is v2's frozen-F9 90° (both states — confirmed). The neck stroke (b) is v2's J1
converter (`MotorJointSystem`, 0°↔**60°**). **Flagged divergence:** the spec (§9.9) specifies neck 0°/**70°**;
v2's J1 cocked rest is **60°**. This 10° is a throw-magnitude difference, not a swing-plane one, and does
not bear on the axial-lock question; left at 60° (changing it globally would perturb the default motor).

---

## 2. Single-motor GATE (`AxLockGateHarness`, Brownian OFF) — the lock WORKS

Runs one bed-anchored sphere-head (rod→J2→neck→J1 0°→60° stroke→HEAD held ⊥ actin by frozen-F9) with the
head TIP F8-anchored to a HELD filament, over the **same production kernels the GPU mat runs**
(`MotorJointSystem.joints` + `TailAnchorSystem.anchor` + `CrossBridgeSystem.bondForces`). The filament is
rolled about its axis by φ (as it is under Brownian on the mat); the lever-rear (lever.end1) swing offset
from the anchor axis is measured, axial fraction = |Δx|/√(Δx²+Δy²). Vertical-head / axial-swing bind pose
(head.uVec=+z, head.yVec=ŝ, lever pre-tilted in the axial x–z plane).

```
  roll φ   | LOCK OFF (F10→seg.yVec)      | LOCK ON  (F10→ŝ)
  (deg)    | axialFrac  Δx(nm)   |Δ|(nm) | axialFrac  Δx(nm)  |Δ|(nm)
  0        | 1.000     -7.80     7.80    | 1.000     -7.80    7.80
  20       | 0.010     -0.15    15.00    | 1.000     -7.80    7.80
  45       | 0.379     -5.90    15.57    | 1.000     -7.80    7.80
  70       | 0.000     +0.00    18.04    | 1.000     -7.80    7.80
  85       | 0.000     +0.00    19.76    | 1.000     -7.80    7.80
```

**LOCK ON holds axial fraction = 1.000 and the swing |Δ| = 7.80 nm CONSTANT at every filament roll** —
the swing stays in the axial (û_seg–n̂bed) plane regardless of how the filament rolls. **LOCK OFF degrades
to ~0** as φ grows (the head roll follows the rolled seg.yVec, tilting the swing plane transverse; |Δ|
inflates to ~20 nm as the swing goes sideways). This is the §9.4c mechanism, working exactly as specified:
**the lock decouples the swing plane from the filament's incidental roll.** (φ=0: ŝ == seg.yVec, so lock
on == off, as expected.)

**Sweep sense:** the isolated single-motor sweep sign is set by the arbitrary lever pre-tilt sign, not by
polarity, so it is not diagnostic here (a manifestation of the §3/§8 bind-pose↔sweep coupling not yet being
a clean model rule). The mat glides −X (pointed-leading, correct) with the existing bind pose, so the
sweep sense on the production path is already right.

---

## 3. Dense GPU mat — the lock alone does NOT lift the net glide

Full 14×2 `-matbed` bed, GPU device-resident, dt=1e-5, `-grid` measurement (velFitX = −slope of the
steady-2nd-half centroid-x fit; inst = long-window path speed). 40000 steps.

| config | density | seed | velFitX (net) | inst (path) | netXY | avgB | fullMat | capFires |
|---|---|---|---|---|---|---|---|---|
| baseline `-spherehead` | 500 | 0 | 0.844 | 5.601 | 1.214 | 10.10 | YES | 0 |
| **`-axlock`** | 500 | 0 | 0.835 | 6.084 | 1.207 | 10.30 | YES | 0 |
| **`-axlock`** | 500 | 1 | 1.373 | 5.786 | 1.158 | 9.84 | — | 0 |
| **`-axlock`** | 500 | 2 | 0.660 | 5.860 | 1.170 | 10.11 | — | 0 |
| baseline `-spherehead` | 1000 | 0 | 0.808 | 5.678 | 1.292 | 15.27 | YES | 0 |
| **`-axlock`** | 1000 | 0 | 1.165 | 5.785 | 1.410 | 14.15 | — | 0 |

d500: baseline velFitX mean **1.10 ± 0.13** (n=3, from the 06-30 GPU-mat log) vs `-axlock` **0.96 ± 0.22**
(n=3) — overlapping ⇒ **no systematic change**. d1000 s0: 0.808 → 1.165 (a single-seed +, within the ~±0.2
seed scatter). `inst` (path speed) ≈ 5.6–6.1 µm/s in **both** configs. Densities 200/2000 not swept: the
single-density gate (the staging rule) showed no lift, so the full ladder is moot for this change.

**The lock does not change the net glide.** velFitX stays ~0.84 (d500) / ~0.9 (d1000) — statistically
identical to the `-spherehead` baseline. `inst` (the path/long-window speed) is ~5.6–6.1 µm/s **in both**
— i.e. the per-cycle transport is already skeletal-fast; the deficit is entirely that the motion is not
DIRECTED (net ≪ path, the filament wanders). The lock was expected to close net→path (BoA §5); it does not,
on v2's mat.

dt=1e-5 is **stable at mat scale** (fullMat=YES, capFires=0). **CPU≡GPU** (`-axlock` d200 s0, 15000 steps):
GPU velFitX 1.062 / CPU 0.938 (both −X); inst 6.037 vs 6.039 (bit-close); avgB 5.23 vs 4.60 — aggregate
agree within the chaotic many-body parallel envelope (the one-step-stale residual; the head-only F10 lock
is per-motor pure, no atomics, so `-cpu` stays valid).

---

## 4. Why the lock is necessary-but-not-sufficient on the mat (root cause)

The single-motor gate (§2) proves the lock keeps an **already-axial** swing axial. It does **not** create an
axial swing from an arbitrary one. The swing *azimuth* — which way the lever tips off the head around the
head axis — is set in v2's J1 (`torsionVec = cross(lever.uVec, head.uVec)`, drive angle(lever,head)→rest)
by whatever perturbation exists; **head.yVec (what the lock controls) does not pin it.** (BoA's
`Myosin.applyLeverMotorJointTorque` is byte-for-byte the same axis law — so this is not a v2-vs-BoA J1
difference.)

On the mat, motors are assembled vertical but with head.yVec=(1,0,0) and **no axial swing seed**, and the
sphere-head bind (`BindingDetectionSystem.bindNearest`) sets only boundSeg/bindArc — **no per-bind pose
reset**. Under the joint + anchor + cross-bridge forces + Brownian, each motor's swing azimuth is
effectively uncontrolled/random, so the per-motor stroke directions do not add coherently → the filament
wanders (path ≫ net). Holding head.yVec = ŝ (the lock) preserves a vertical head but leaves the swing
azimuth free ⇒ no net gain.

**The missing co-requisite is the bind pose** — a polarity-derived **vertical-head, axial-swing-seeded**
bound pose, re-applied on each bind (motors turn over fast: avgBound ~10 of 3480). This is precisely the
BoA doc's own §3/§8 caveat: *"the barbed-ward sweep direction currently relies on the demo IC neck-bend
(single-motor only) + the rod-gate bypass … not yet a clean, polarity-derived model rule; promoting this
model should first replace the gate-bypass + IC-bend hack with a bind pose whose barbed-ward sweep falls
out of filament polarity directly."* v2 inherits the same gap: the axial lock is one half of the fix; the
polarity-derived axial bind pose (with a per-rebind reset, both CPU + GPU kernel) is the other half, and is
**not** built here.

---

## 5. Verdict & next step

- **§9.4c axial lock: ported, correct, and single-motor-PROVEN** (axial fraction 1.0 lock-on vs degrading
  lock-off; matches BoA `alignYVecTorqueAxial` arithmetic). `-axlock` flag-gated; **default + `-spherehead`
  byte-identical** (xbParams-size gating; regression: baseline d500 s0 velFitX 0.844 / inst 5.601
  reproduced exactly on the new build).
- **NOT sufficient on the mat.** velFitX unchanged (~0.84). The sphere-head stays GLIDE-but-SLOW (~1 µm/s
  net) — the lock does not lift it into the 5–8 µm/s skeletal band, because the wander (net ≪ path) comes
  from the **uncontrolled swing azimuth / bind pose**, which the roll lock alone cannot fix.
- **Next (the actual remaining fix):** a **per-bind polarity-derived axial bind pose** for the sphere-head
  mat path — re-seat each freshly-bound motor to head.uVec ‖ n̂bed (vertical) + the lever pre-tilted in the
  axial plane toward the barbed end (so the stroke sweeps barbed-ward by polarity, not by an IC hack),
  re-applied on rebind, wired on CPU + GPU (a `snapCanonicalHead`-analog for the sphere-head). The
  single-motor gate (§2) predicts that pose + this lock → a directed axial glide; **without the bind pose,
  the lock is inert on the mat.**

New files/flag-gated only (`AxLockGateHarness`, `-axlock`, xbParams[10]); production byte-identical;
`BoA-v1ref` byte-clean.

---

## 6. Directed power stroke — the co-requisite that lands the glide (2026-07-01)

§4 predicted the lock needs a **polarity-derived swing direction** to work on the mat. Confirmed and fixed.

**The ill-defined direction (jba, single-motor viewer):** the J1 converter's torsion axis
`cross(lever.uVec, head.uVec)` is **degenerate at the straight/collinear recovery pose**, so each new power
stroke picks its azimuth from numerical residue and **flips between cycles** (pointed-ward on some,
barbed-ward on others). (The recovery correctly returns to the straight 0° config — traced; it is *not* a
60°-opposite swing.)

**Fix — `CrossBridgeSystem.directedSwing` (`-dirswing`, implies `-axlock`):** drive the neck toward a
polarity-defined target `û_L* = normalize(cos θ_rest·û_head − sin θ_rest·f̂)`, f̂ = bound-seg uVec
(pointed→barbed), so the neck REAR sweeps **barbed-ward every cycle**; the degenerate J1 angular converter is
turned OFF (jointParams[3]=0; the J1 position spring stays). Per-motor pure (motor m writes only its lever
3m+1 & head 3m+2 torque slots) ⇒ race-free, no atomics, CPU≡GPU. Wired into `GlidingHarness` (CPU step + GPU
TaskGraph + swingParams transfer) and `AxLockGateHarness` (default on; `-j1swing` = old degenerate J1).
Default byte-identical.

**Single-motor (Brownian off, trace):** every power stroke rearX = **+5.3 nm (+x = barbed)**, every recovery
→ straight (θ=0°), purely axial (rearY=0). No more flipping — deterministic.

**Dense mat — the payoff (single 2 µm filament, d1000, dt=1e-5, GPU):** the directed swing was the missing
half. Net glide jumped from the lock-alone null (~0.9) to velFitX **1.88** (40k steps). Full **1.5 s** run,
LS-centroid estimator (`~/Code/BoA/PROPER_SPEED_ANALYSIS.md` §2):

| window | v_axial (µm/s) | \|v\| | axial frac | avgBound |
|---|---:|---:|---:|---:|
| fully-on-bed steady [0.40, 1.00] s | **−3.05** | 3.05 | **1.000** | 14.3 |
| [0.30, 1.00] s | −3.12 | 3.12 | 0.999 | 14.4 |
| whole steady run [0.30, 1.50] s | −2.83 | 2.84 | 0.999 | 12.8 |

**v_axial ≈ −3.0 µm/s, pointed-leading (−x), axial fraction 1.00** (≈1.5 body-lengths/s; filament 1.93 µm).
Same regime as the BoA axial-lock reference (−2.09 µm/s at d1000/1.5 s); v2 slightly faster. The **axial
fraction 1.00** is the headline — the glide is now fully DIRECTED (net ≈ path), unlike the §3 wander
(net ≪ path) — so lock (head roll) + directed swing (azimuth) together = a directed, skeletal-range glide.

**Caveat (window):** `-matbed`'s ~2.9 µm −x runway is short for 1.5 s at 3 µm/s — the filament runs off the
bed edge past t≈1.0 s (avgBound 17→8), biasing the whole-run fit low (−2.83). The fully-on-bed window
(−3.0) is the valid measurement; a `-full` 14×2 bed (BoA geometry, ~12 µm runway) would give a clean
full-1.5 s window (≈27k motors, a heavier run). Run: `./run_axlock.sh` / `GlidingHarness -gpu -matbed
-dirswing -density 1000 -3js <dir> 150000`.
