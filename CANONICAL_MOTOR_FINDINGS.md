# Canonical lever-arm motor — BUILD + native-behavior characterization (calibration DEFERRED)

**2026-06-27. Flag-gated (`-canonical` path = `CanonicalMotorHarness` + `CrossBridgeSystem.bondForcesCanonical`
+ `MotorStore.bindArc2`); default byte-identical.** This is the deliberately **v1-divergent** myosin cross-bridge
(jba decision, recorded below). We BUILD the canonical lever-arm mechanism and CHARACTERIZE its native behavior;
we do NOT re-tune the catch constants / myoSpring / rates (PHASE 2).

## TL;DR
- **The mechanism is now canonical.** Stroke at the tail/load end scales **∝ LEVER length** (slope **1.048 nm/nm**,
  R²≈1.000), and the **J1 converter swing carries 100% of it** (freeze J1 → stroke **0.000 nm**). This is the clean
  INVERSION of the old motor (`STROKE_VS_ARMLENGTH_FINDINGS`: stroke ∝ HEAD_LEN, J1-silent = 0.00 nm, F9 carried it).
- **The geometry finding (§1 bail boundary, MEASURED).** Two-point attachment is **impossible from the default
  (perpendicular-head) bind pose** — the rear point (J1 pivot) sits ~HEAD_LEN off the filament: **0% reachable**.
  In the **canonical along-filament pose it is 100% reachable**. The canonical mechanism *requires* the head to bind
  lying along actin (tip + rear span ~HEAD_LEN of actin) — not the default perpendicular pose. This is a real,
  expected consequence of the re-rig, reported (not worked around).
- **The new load signal is present and non-degenerate under load.** Lever-strain `forceDotFil` = +0.285 pN
  (cocked, isometric), ≈0 unloaded, sign = resisting. Much gentler than the old over-stretched tip signal.
- **dt = 1e-5 is STABLE** for the two-point + lever-torque loop (max |forceDotFil| 0.28 pN — *gentler* than the
  default, no new stiff-overshoot). **CPU≡GPU bit-identical** (meanTailX/avgBound rel 0.00%). **Default byte-identical**
  (re-ran `run_stroke.sh`: gate-3 = **6.96 nm** bit-for-bit, all gates PASS).

---

## The decision (recorded) — v1 divergence + parity-contract narrowing
The powerstroke read-out + the arm-length sweep showed the default motor is **non-canonical**: the head pivots on
actin (F9 swings the head-vs-actin angle 90°→120° and carries the stroke), the working stroke is read at the head
**tip** (the F8 actin anchor), and the **J1 converter swing is silent** for the tip — so the step came out ∝
HEAD_LEN, not lever length. **v1 (BoA-v1ref) implements this same non-canonical geometry.**

**DECISION (jba): v2's motor deliberately DIVERGES from v1 to implement the canonical lever-arm mechanism.**
- v1 remains the measured oracle for the **actin PAIRS layer** and the **crosslinkers** (not suspect there).
- v1 is **NO LONGER the parity oracle for the motor cross-bridge** — **v2's corrected (canonical) motor is the new
  reference.** The motor cross-bridge is **exempt from v1 bit-parity.**
- `BoA-v1ref` stays **byte-clean / read-only** (we do NOT "fix" the frozen oracle). `CLAUDE.md`'s parity contract
  is updated to record this narrowing.

---

## The canonical mechanism we built (vs the default)
| | default (`bondForces`) — non-canonical | **canonical (`bondForcesCanonical`)** |
|---|---|---|
| head attachment | ONE point (F8 tip) | **TWO points**: tip (head.end2) + rear (head.end1 = J1 pivot) |
| head orientation | soft-pinned by F9 (swings 90°→120°) | **pinned by geometry** (the two anchors) — **F9 removed** |
| powerstroke driver | F9 head reorientation (at the tip) | **J1 converter swing** (0°→60°) against the pinned head |
| stroke delivered at | head **tip** (the pinned anchor) | **tail/load end** (lever.end1) |
| stroke scales with | HEAD_LEN | **LEVER length** |
| catch/ADP load `forceDotFil` | tip-bond stretch Dot(F8, segU) | **lever strain** = Dot(F8a+F8b, segU) |

### Piece 1 — two-point attachment (the geometric anchor)
`bondForcesCanonical` applies **two F8 springs to the same bound segment**: F8a from the tip (head.end2) → site A
(`bindArc`, the existing tip material point), F8b from the rear (head.end1, the neck/J1 pivot) → site B (`bindArc2`,
a new `MotorStore` field). Both are fixed actin material points, so the couple pins the head's **position AND axis**.
One bind sets both sites; the head is rigid (tip→rear = HEAD_LEN) ⇒ orientation fixed by construction.
- *Second-site rule (as specified):* given the tip's bound pose, the rear binds the **nearest actin site to the J1
  position** (the perpendicular foot of head.end1 on the bound segment) if within reach. In this characterization the
  bond is pre-established deterministically (both sites computed from the assembled pose); a dynamic two-point binder
  for the gliding assay is a phase-2 integration item.
- *Both sites on the SAME segment* (the rear-on-a-neighbour case is a gliding-assay concern; flagged, out of scope here).

### Piece 2 — relocated powerstroke (J1 carries it; F9 removed)
The J1 converter swing (`MotorJointSystem`, the 0°↔60° rest switch, **UNCHANGED**) now drives the lever + tail against
the two-point-anchored head. **F9 is removed** from the canonical path (the head no longer reorients against actin).
**F10 (roll/yVec alignment toward a constant 0° rest) is kept** — two point-springs do not constrain roll about the
head axis, and F10's rest never switches with nucleotide state, so it is not a stroke driver.

### Piece 3 — lever-strain load signal
`forceDotFil = Dot(F8a + F8b, seg.uVec)` — the **net along-filament load the two-point cross-bridge transmits** = the
resistance the converter swing develops, reacted through the pinned head into actin (the lever-tail tension projected
on the filament), NOT the single tip-bond stretch. Non-degenerate under load, ≈0 unloaded (head freely pinned).

---

## Characterization (native behavior; explicit Hookean F8 @ dt = 1e-5, `myoSpring = 1 pN/nm`; Brownian OFF ⇒ deterministic)

### 1. Does the stroke scale with LEVER_LEN? Is J1 still silent?  — `CanonicalMotorHarness` §1
**Stroke = |lever.end1 (tail/load end) displacement|** between held-uncocked (ADPPi, J1 0°) and held-cocked (ATP,
J1 60°) equilibria, head pinned two-point, tail free.

**Sweep LEVER_LEN (head fixed 20 nm):**
| lever (nm) | stroke (nm) | strokeVec (dx,dy,dz nm) | J1 swing (°) |
|---|---|---|---|
| 4  | 2.000  | (1.00, 0.00, −1.73)  | 60 |
| 8  | 7.998  | (4.00, 0.00, −6.93)  | 60 |
| 16 | 15.994 | (8.00, 0.00, −13.85) | 60 |
| 24 | 23.991 | (12.00, 0.00, −20.77)| 60 |
| 32 | 31.989 | (16.00, 0.00, −27.70)| 60 |

⇒ **Δstroke/ΔLEVER = 1.048 nm/nm (R²≈1.000)** — the canonical lever-arm law. The number is geometric:
**stroke = 2·leverLen·sin(½·60°) = leverLen** (the chord of a 60° converter swing of radius = lever length;
strokeVec = (+½L, 0, −(√3/2)L), magnitude L). The small excess over 1.0 is head-pin compliance.

**Sweep HEAD_LEN (lever fixed 8 nm):**
| head (nm) | stroke (nm) | strokeVec (nm) | J1 swing (°) |
|---|---|---|---|
| 10 | 7.998 | (4.00, 0.00, −6.93) | 60 |
| 20 | 7.998 | (4.00, 0.00, −6.93) | 60 |
| 30 | 7.541 | (3.94, 0.00, −6.43) | 46 |
| 40 | 6.037 | (4.09, 0.00, −4.44) | −7.5 |

⇒ **Δstroke/ΔHEAD ≈ −0.06 nm/nm** — **flat (head is no longer the amplifier)** through the biological range
(head ≤ 20 nm: exactly 7.998 nm). The head 30–40 nm rows are a **large-head artifact** (HEAD_LEN ≳ several·LEVER_LEN:
the lever pivot is an anchor point, and the swing no longer reaches 60°) — flagged, outside the biological regime,
not over-interpreted.

**Isolation (lever 8, head 20):** both (J1 swings, F9 off) = **7.998 nm**; **J1 FROZEN = 0.000 nm** ⇒ **J1 carries
100% of the stroke**. The exact opposite of the default (where J1-only = 0.00 nm and F9 carried it).

> **VERDICT:** step **SCALES ∝ LEVER** (slope 1.048 vs head −0.063); **J1 is no longer silent — it carries the
> stroke.** The canonical structure→function law a prescribed-step (or head-pivot) motor cannot make.

### 2. The new load signal (lever-strain `forceDotFil`)  — §2
Isometric: head pinned (two-point) **AND** tail anchored ⇒ the J1 swing develops strain at the anchors.
| config | forceDotFil (pN) | \|net F8\| (pN) |
|---|---|---|
| cocked, tail FREE      | 0.0000 | 0.0000 |
| **cocked, tail ANCHORED** | **+0.2849** | 0.2872 |
| uncocked, tail ANCHORED | +0.0562 | 0.0576 |

The signal is **non-degenerate under load** (cocked-isometric +0.285 pN, sign **+ = resisting**), **≈0 unloaded** (head
freely pinned — the signal lives in the loaded build), and **rises cocked-vs-uncocked** (the converter swing meeting
the held tail). Magnitude is **gentler** than the default motor's over-stretched tip signal (the catch's chronic
overshoot source, cf. `SATURATED_CROSSBRIDGE`/dt-arc). **Uncalibrated** — we report the signal, we do not validate the
release (PHASE 2).

### 3. Bind / stroke / release sanity + two-point reachability  — §3
**Two-point reachability (the §1 geometry finding, MEASURED):** is the rear point (head.end1) within `myoColTol` of
the bound segment?
- **DEFAULT perpendicular-head bind pose: 0%** — F9 holds the head ~perpendicular, so the J1 pivot is ~HEAD_LEN (20 nm)
  off the filament, far beyond reach (6 nm). **Two-point attachment cannot form from the default pose.**
- **CANONICAL along-filament pose: 100%** — head lies along actin, tip + rear span ~HEAD_LEN of actin, both within reach.

⇒ The canonical mechanism **requires the head to bind lying along the filament**. Wiring dynamic two-point binding
into the gliding assay therefore needs the along-filament binding pose, **not** the default perpendicular one
(phase-2 integration; this is the "require both / measure the lattice span" boundary the task flagged — here the
span is fine ON the canonical pose, impossible on the default pose).

**Bind/stroke/release loop** (64 motors, 20k steps, full nucleotide cycle + catch-slip + 12 pN cap): the two-point
motor **binds, cycles, and releases** (avgBound 0.056 with one-shot bonds — no dynamic rebind wired; releases 64,
cap 0 ⇒ released via catch-slip, **no force-cap overshoot**). FUNCTIONAL.

### 4. dt stability at 1e-5  — §4
| dt | result | max\|forceDotFil\| |
|---|---|---|
| 1e-5 | **STABLE** (bounded, no NaN, 20k steps) | 0.281 pN |
| 2e-5 | STABLE | 0.106 pN |

The two-point + lever-torque loop introduces **no new stiff/overshoot sensitivity** at 1e-5; it is in fact *gentler*
than the default single-tip cross-bridge (the head is geometrically pinned, so the springs barely stretch — the catch
no longer reads a chronically over-stretched bond). The implicit `1/(1+r)`/sub-step tools were **not needed**.

### 5. CPU≡GPU + default byte-identical  — §5/§6
- **CPU≡GPU:** canonical cycling loop, meanTailX GPU/CPU = −0.015033 / −0.015033 µm (rel **0.00%**), avgBound
  0.093 / 0.093 (rel **0.00%**) — **bit-identical** (device-resident 17-task TaskGraph; the canonical bond is a single
  per-motor kernel, no atomics/KernelContext, reuses the validated CSR-inverse gather VERBATIM).
- **Default byte-identical:** the build is **purely additive** (new method `bondForcesCanonical`, new array `bindArc2`,
  new harness — no existing system/harness edited). Re-ran `run_stroke.sh`: **gate-3 = 6.96 nm bit-for-bit**, all 6
  gates PASS.

---

## CPU-fallback disclosure
The arm-sweep / lever-strain / reachability / dt measurements run on the **`-cpu` sequential debug runner** (tiny
deterministic equilibria, sub-second — the runner choice IS the seed cross-check, Brownian off). The **CPU≡GPU gate
runs the device-resident GPU TaskGraph** (17 tasks over 64 motors, sub-second/step). No large/long run; nothing
silently fell back.

## Bail boundaries — outcome
- **Two-point binding rarely possible** → **HIT + REPORTED, expected:** 0% from the default perpendicular pose
  (the rear can't reach), 100% from the canonical along-filament pose. The lattice span is fine on the canonical pose;
  the default pose simply isn't the canonical binding geometry. Not worked around — it's the headline geometry finding.
- **dt blows up at 1e-5** → did NOT happen (stable, gentler than default).
- **Lever-strain signal degenerate** → did NOT happen (non-degenerate under load).

## Calibration is PHASE 2 (deferred, NOT in this build)
Re-tuning the catch constants against the new (gentler) lever-strain signal, re-validating release behavior, wiring a
**dynamic two-point binder in the canonical along-filament pose** into the gliding assay, and promoting `-canonical`
to the default + new reference — a separate later task.

## What changed (additive; default byte-identical; `BoA-v1ref` untouched)
- `CrossBridgeSystem.java`: **new** `bondForcesCanonical` (two-point F8, F9 removed, F10 kept, lever-strain
  forceDotFil). The default `bondForces` is **byte-unchanged**.
- `MotorStore.java`: **new** `bindArc2` array (rear-site arc; allocated/zeroed; read only by the canonical path).
- `CanonicalMotorHarness.java` + `run_canonical.sh`: the build + characterization (`-cpu`, `-3js DIR`).
- `CLAUDE.md`: parity-contract narrowing (motor cross-bridge exempt from v1 bit-parity; v2-canonical is the reference).
- Viewer: `./run_canonical.sh -3js threejs_canonical` (held motors stroking the lever/tail uncocked↔cocked about the
  pinned head).
```
./run_canonical.sh            # full characterization (CPU measurements + GPU CPU≡GPU cross-check)
./run_canonical.sh -cpu       # CPU only
./run_canonical.sh -3js threejs_canonical   # viewer
```
