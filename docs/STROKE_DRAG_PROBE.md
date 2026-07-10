# Does a bound head resist when dragged past its stroke — or is the attachment a conveyor belt?

**Date:** 2026-07-09 · **Branch:** dt-convergence-study · PART A = single-motor CPU probe (deterministic,
Brownian OFF, tiny); PART B = read-only code fact. `BoA-v1ref` reference-only. Runner:
`EomStabilityHarness -dragprobe` (new mode, default byte-identical — additive; no shared code touched).

## Bottom line

**The attachment is MATERIAL-LATCHED, not a conveyor.** The F8 cross-bridge stores a **material label**
(`bindArc`, an arc-length in the segment's material frame) **frozen at bind time** and re-derives the
world attachment each step by transforming that frozen arc through the segment's **current** pose. So as
the filament glides, the attachment stays pinned to a **receding material point**, F8 strain accumulates,
and a bound head **resists** being dragged past its stroke (a real, growing backward force, later shed by
the signed catch-slip). **C1 / C2 / C3 are all REFUTED — the attachment and the stroke target are both
correct.** This closes the one gap `DETACHMENT_CEILING_CODEREAD.md` left open (it argued this from code;
this probe *measures* it and rules out the conveyor A/B directly).

The porting-hazard hypothesis is wrong; the reason V∝N with no d/τ_on ceiling is the *orthogonal*
structural fact `DETACHMENT_CEILING_CODEREAD.md` already identified (force-summation with no
displacement-metering clutch + high-duty regime), **not** a sliding attachment.

---

## PART A — the quasi-static force–displacement trace (measured)

**Setup.** One filament segment (uVec = +x), one permanently-bound myosin head, motor body **FROZEN** at
its bound pose, Brownian **OFF** ⇒ fully deterministic. The filament is advanced rigidly along the glide
axis (+x = seg.uVec) in 2 nm increments from −10 nm to +24 nm — through and **past** the ~7 nm stroke. At
each Δ we read **F_x** = the F8 seg-side force *on the filament* along +x (`bondData[6]`) and **ap_x** =
the stored attachment's world position `segCenter + (bindArc−½·segLen)·seg.uVec`.

Two arms, identical except for the attachment bookkeeping:
- **MATERIAL** (the real production code) — `bindArc` frozen at bind time.
- **CONVEYOR** (synthetic C1 control) — `bindArc` **re-derived each Δ** to the perp-foot of the (frozen)
  head tip, i.e. "wherever the tip is now."

k_F8 = 1.00 pN/nm; HEAD_LEN = 20 nm; frozen tip x = 0.00000 µm; bindArc(frozen) = 0.04455 µm; segLen =
0.0891 µm. F_x>0 pushes the filament +x; F_x<0 **resists** (−x).

```
  Δ(nm)   |  MATERIAL (real code)      |  CONVEYOR (C1 control)     | tip_x
          |   F_x(pN)     ap_x(µm)     |   F_x(pN)     ap_x(µm)     | (µm)
  -10.0   |  +10.0000    -0.01000      |    0.0000    -0.00000      | 0.00000
   -8.0   |   +8.0000    -0.00800      |    0.0000     0.00000      | 0.00000
   -6.0   |   +6.0000    -0.00600      |    0.0000     0.00000      | 0.00000
   -4.0   |   +4.0000    -0.00400      |    0.0000     0.00000      | 0.00000
   -2.0   |   +2.0000    -0.00200      |    0.0000     0.00000      | 0.00000
   +0.0   |   -0.0000     0.00000      |    0.0000     0.00000      | 0.00000   ← zero-crossing
   +2.0   |   -2.0000     0.00200      |    0.0000    -0.00000      | 0.00000
   +4.0   |   -4.0000     0.00400      |    0.0000    -0.00000      | 0.00000
   +6.0   |   -6.0000     0.00600      |    0.0000    -0.00000      | 0.00000
   +8.0   |   -8.0000     0.00800      |    0.0000    -0.00000      | 0.00000
  +10.0   |  -10.0000     0.01000      |    0.0000     0.00000      | 0.00000
  +12.0   |  -12.0000     0.01200      |    0.0000    -0.00000      | 0.00000
  +14.0   |  -14.0000     0.01400      |    0.0000    -0.00000      | 0.00000
  +16.0   |  -16.0000     0.01600      |    0.0000    -0.00000      | 0.00000
  +18.0   |  -18.0000     0.01800      |    0.0000     0.00000      | 0.00000
  +20.0   |  -20.0000     0.02000      |    0.0000     0.00000      | 0.00000
  +22.0   |  -22.0000     0.02200      |    0.0000    -0.00000      | 0.00000
  +24.0   |  -24.0000     0.02400      |    0.0000    -0.00000      | 0.00000

  MATERIAL: dF_x/dΔ = −1.0000 pN/nm  (= −k_F8 exactly)
  CONVEYOR: dF_x/dΔ = +0.00000 pN/nm (flat, ≈0 for every Δ)
```

**Reading the trace (the decisive physics demand):**

1. **F_x reverses sign — the bond RESISTS past the stroke.** MATERIAL F_x is a clean linear cross-bridge
   spring: it **drives** the filament (+F_x) when the material site is behind the tip, **crosses zero**
   where site == tip, and goes **negative (resisting)** — growing without bound — as the filament is
   dragged past. Slope = **−1.0000 pN/nm = exactly −k_F8**: a Hookean spring anchored to a fixed material
   point, dragged through the head. This is exactly a real cross-bridge dragged past its stroke pulling
   back. (In this frozen-head probe the zero sits at Δ=0, the *bind* pose; in the live motor the
   `directedSwing` torque first rotates the head to its post-stroke orientation, translating the tip ~one
   stroke barbed-ward and moving the F_x zero-crossing to ~one stroke displacement — the mechanism is
   identical, the stroke only sets *where* the zero sits. See PART B/C3.)

2. **The attachment world position tracks the filament — pinned to a receding material point.** MATERIAL
   ap_x moves **1:1 with Δ** (−0.010 → +0.024 µm) while the head tip stays at 0.00000. The stored
   attachment is *material*: it recedes with the filament, so strain accumulates.

3. **The CONVEYOR control is what "sliding attachment" would look like — and it is NOT what the code
   does.** When `bindArc` is re-derived to the tip's perp-foot each step, F_x stays **≈0 for every Δ** and
   ap_x sits **under the fixed tip** (never moves with the filament). The head would re-grip at zero axial
   strain every step and stroke forever — a conveyor belt. The real code produces the opposite trace.

**PART-A verdict: the bond resists, the resistance grows linearly with drag distance, and the attachment
is pinned to a material point. C1 REFUTED empirically.**

---

## PART B — the code fact (read-only)

### B1 — attachment reference at bind time: **MATERIAL label** (not geometric-recomputed).

At a bind decision the binder computes the arc-length of the head's projection onto the segment and stores
it once:

```java
// BindingDetectionSystem.bindNearest (production gliding path), :388-394
float numer = (mx-e1x)*r1x + (my-e1y)*r1y + (mz-e1z)*r1z;   // r1 = e2−e1 (segment axis)
bestArc = numer / (float) Math.sqrt(denom);                 // arc-length bind site
if (bestSeg >= 0) { boundSeg.set(m, bestSeg); bindArc.set(m, bestArc); }
```

Crucially this write is **gated to the free→bound transition only** — `:368`
`if (boundSeg.get(m) != MotorStore.FREE_BINDABLE) continue;` skips the entire binding search (the *only*
thing that writes `bindArc`) for any already-bound motor. **Every** binder in the tree carries the same
guard — `bindKinetics` (:314/:325, the `else` FREE_BINDABLE branch), `bindNearest` (:368), `bindNearestAzim`
(:429), `bindNearestFalloff` (:507), `bindCanonicalTwoPoint` (:598), `bindRate` (:834),
`bindNearestNodeAware` (:974). A grep confirms the **only** writers of `bindArc` are these bind decisions
and harness setup; **no system re-derives it per step.** ⇒ `bindArc` is **frozen for the whole bound
lifetime** — a material label carried by the segment, not "wherever the tip is now."

### B2 — per-step endpoint computation (the crux): **transformed-material (Lagrangian)**, not re-solved-nearest.

Each step `CrossBridgeSystem.bondForces` (`:113-118`) builds the F8 endpoint from the frozen material arc
and the segment's **current** pose:

```java
double slen = filSegLength.get(s);
double aOff = bindArc.get(m) - 0.5 * slen;                          // frozen material arc, from seg center
double apx = scx + aOff*sux, apy = scy + aOff*suy, apz = scz + aOff*suz;   // CURRENT segCenter + CURRENT uVec
double dx = apx - htipx, ...;  double fmag = myoSpring * dist;      // F8 = spring toward the material site
```

`bondForces` **reads** `bindArc`; it never solves a perp-foot or nearest point of the current head. The
attachment moves **with** the filament (current `scx`/`sux`), so strain accumulates as the filament glides
— exactly the PART-A MATERIAL trace. This is the **Lagrangian / transformed-material** branch. **The
Eulerian re-solve-to-nearest (conveyor) is absent from the code.** ⇒ **C1 refuted.**

### B3 — stroke zero-point: **latched FIXED orientation target** (not sustained force, not re-neutralizing).

The power stroke is `CrossBridgeSystem.directedSwing` (`:251-253`): the swing rest angle is a
**nucleotide-state-fixed** value and the target is built from the head uVec and the **filament axis**:

```java
double rest = (nucleotideState.get(m) != MotorStore.NUC_ADPPI) ? thetaC : thetaU;   // FIXED angle, state-switched
double tx = c*hux - sn*fx, ...;    // target lever dir; f̂ = bound-seg uVec (an ORIENTATION, not a point)
```

It is a **spring toward a latched orientation**, switched only by the nucleotide state — **not** a constant
f̂-directed sustained force (would be C3) and **not** a target re-neutralized each step (would be C2). Angle
is invariant under filament **translation**, so gliding does not re-neutralize it; the target sets the
head's post-stroke pose (hence the F_x zero-crossing location), and past that pose the material-anchored F8
(B1/B2) delivers the backward drag. ⇒ **C2 and C3 refuted.**

---

## Cross-validation & verdict

| Candidate | Symptom (PART A) | Mechanism (PART B) | Verdict |
|---|---|---|---|
| **C1** sliding/conveyor attachment | F_x flat ≈0, ap_x under fixed tip — **NOT observed** (that's the control) | attachment is **frozen material label** transformed by current pose (B1/B2) | **REFUTED** |
| **C2** re-neutralizing target | — | swing target is a **fixed state-switched** angle, not re-neutralized (B3) | **REFUTED** |
| **C3** sustained-force stroke | — | swing is a **spring toward a latched orientation**, not a constant force (B3) | **REFUTED** |
| **"correct"** | F_x drives → crosses zero → **resists past the stroke** (slope −k_F8); ap_x pinned to a receding material point | material-latched attachment + latched stroke target | **CONFIRMED** |

**Plain root-cause answer.** *Does a bound head resist when the filament is dragged past its stroke?*
**Yes.** The F8 cross-bridge is a material-anchored Hookean spring whose filament-side foot is a frozen
arc-length transformed by the segment's current pose (Lagrangian), so dragging the filament past the head
strains the bond backward and it resists (measured slope = −k_F8 exactly), until the signed catch-slip
sheds it. The attachment does **not** slide (C1), the target does **not** re-neutralize (C2), and the
stroke is **not** a sustained force (C3). The velocity-climb / missing-d/τ_on-ceiling puzzle is therefore
**not** a porting hazard in the attachment or stroke bookkeeping — it is the orthogonal
force-summation-without-a-displacement-clutch + high-duty-regime structural fact already documented in
`DETACHMENT_CEILING_CODEREAD.md` (do not "fix" the attachment or the stroke; they are correct).

**Reproduce:** `bash scripts/archive/run_eomstab.sh -dragprobe` (CPU, deterministic, seconds).
</content>
</invoke>
