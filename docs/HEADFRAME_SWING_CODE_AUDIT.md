# HEADFRAME_SWING_CODE_AUDIT — STEP-0 gate

**Purpose.** Mandatory code-inspection gate for the head-frame-vs-DIRSWING force–velocity
experiment (proposal hash e9328f…). Document the exact formulas + update sequence for
`directedSwing`, the existing head-frame swing, AXLOCK, SPHEREHEAD/F9, and the Lymn-Taylor
transition — and determine whether the branch is **single-factor** and whether **AXLOCK
overrides the head-frame swing target** (⇒ H3 near-foreordained) or the two contribute
**simultaneous torques on different DOFs** (⇒ H1 vs H3 stays empirical).

All line numbers are `softbox/CrossBridgeSystem.java` and `softbox/GlidingHarness.java` at the
commit this doc is written against (branch `dt-convergence-study`).

---

## 0. The canonical default stack this experiment runs on

`GlidingHarness` defaults (no flags), confirmed from the field declarations:

| flag | default | meaning |
|---|---|---|
| `SPHEREHEAD` | **true** | F9 rest frozen at 90° (perp-maintainer, `xbParams[9]=1`) — F9 no longer strokes |
| `AXLOCK` | **true** | F10 retargets head.yVec → ŝ = n̂bed×seg.uVec, head-only (`xbParams[10]=1`) |
| `DIRSWING` | **true** | `CrossBridgeSystem.directedSwing` is the sole stroke driver; J1 angular converter OFF (`jointParams[3]=0`) |
| `LYMN_TAYLOR` | **true** | single nucleotide-driven release (NONE→ATP); catch modulates ADP→NONE |
| `XB_IMPLICIT2` | **true** | coupled head+site implicit F8 |
| `NECK_ANGLE` | **60.0°** | θ_cocked (swingParams[3]); θ_uncocked = 0° (swingParams[2]) |

So the default `-vclamp` run **already IS** the proposal's fixed stack (SPHEREHEAD + AXLOCK
both ON, DIRSWING the control stroke). `-headswing` will select `directedSwingHeadFrame`
in place of `directedSwing`, changing **nothing else**.

---

## 1. `directedSwing` (DIRSWING, the CONTROL) — CrossBridgeSystem.java:222–272

Signature: `directedSwing(motorUVec, motorTorqueSum, motorBRotGam, filUVec, boundSeg, nucleotideState, swingParams, counts)`.
Runs as its own kernel/host call, AFTER `applyHeadForce`, BEFORE the integrator (CPU
step line 819; GPU task `dirSwing` line 1148).

Per bound motor `m` (`boundSeg[m] = s ≥ 0`), reading the **current** pose:
- `û_head` = motorUVec[head] (head = 3m+2), `û_lever` = motorUVec[lev] (lev = 3m+1).
- **f̂ = filUVec[s]** — the bound segment's uVec (the filament axis appears EXPLICITLY).
- rest angle `θ = (nuc ≠ ADP·Pi) ? θ_cocked : θ_uncocked` (60° cocked / 0° uncocked).
- **target lever direction** `û_L* = normalize( cos θ · û_head − sin θ · f̂ )`  (line 253).
- compliant alignment torque rotating `û_lever` → `û_L*`: axis = `û_lever × û_L*`,
  angle = acos(û_lever·û_L*), magnitude `mag = k·ang / ((1/γ_lev + 1/γ_head)·dt)`;
  **+mag·axis on the lever, −mag·axis on the head** (internal converter couple; lines 264–270).

**Target recomputation:** the target `û_L*` is **recomputed every force evaluation** from
the live `û_head` and the live `f̂`. It is **NOT stored at the chemical transition.** The
only thing the nucleotide state does is select which θ (cocked/uncocked) is used *this step*.

**Key property for the clamp:** in `-vclamp` the filament is a rigid straight source with
`uVec ≡ (1,0,0)` at all steps and all velocities (`runForceVelocity` re-imposes it every step,
lines 890–933, thermal OFF). So **f̂ ≡ +x̂ exactly, velocity-independent.** DIRSWING therefore
commands a perfectly axial swing reference at every v — this is the counterfactual baseline.

---

## 2. `directedSwingHeadFrame` (the head-frame recast, the VARIABLE) — CrossBridgeSystem.java:381–429

Signature: `directedSwingHeadFrame(motorUVec, motorYVec, motorTorqueSum, motorBRotGam, boundSeg, nucleotideState, swingParams, counts)`.

Per bound motor, reading the **current** pose:
- `û_head` = motorUVec[head], `ŷ_head` = motorYVec[head], `û_lever` = motorUVec[lev].
- **NO filUVec argument** — the filament axis does not appear in the swing law.
- rest angle θ selected identically (same θ_cocked/θ_uncocked from the SAME nucleotide state).
- **swing reference (head frame)** `p̂ = ŷ_head × û_head`  (line 412).
- **target lever direction** `û_L* = normalize( cos θ · û_head − sin θ · p̂ )`  (line 413).
- **identical** compliant torque form thereafter (axis = û_lever × û_L*, same `mag`, +lever/−head;
  lines 417–428) — byte-for-byte the same as `directedSwing` from line 254 onward.

**Target recomputation:** also **recomputed every step from the live head pose** (û_head, ŷ_head).
NOT stored/frozen at commitment. *(The proposal's caution — do not treat the head-frame target
as "fixed at commitment" — is satisfied: neither variant stores a target; both are live.)*

**Head-frame axes & sign.** The reference direction `p̂ = ŷ_head × û_head`. The comment (lines
370–376) and the identity: **when the head is fully locked** — ŷ_head = +ŝ = n̂bed×f̂ and û_head ⊥ f̂
in-plane — then `ŷ_head × û_head = f̂`, so `û_L*` is IDENTICAL to DIRSWING's `cos θ·û_head − sin θ·f̂`.
Actin polarity enters **only through the bound head pose** (which the locks pin), not through an
explicit f̂. No separate sign selection beyond the cross-product handedness.

---

## 3. The DIFF between the two branches (single-factor determination — H5 gate)

Line-by-line, the ONLY difference between `directedSwing` and `directedSwingHeadFrame` is the
construction of the swing reference used in the `−sin θ` term:

| quantity | directedSwing (control) | directedSwingHeadFrame (variable) |
|---|---|---|
| swing reference | **f̂ = filUVec[s]** | **ŷ_head × û_head** |
| target | cos θ·û_head − sin θ·**f̂** | cos θ·û_head − sin θ·**(ŷ_head×û_head)** |
| θ selection | nucleotide state → cocked/uncocked | **identical** |
| stiffness k, `mag` formula | k·ang/((1/γ_lev+1/γ_head)·dt) | **identical** |
| torque application | +lever / −head | **identical** |
| strokerate / spring (`swingParams[4]`) handling | present | **identical** |
| force application, F8, F9, F10, AXLOCK, release, binding, cycle | untouched | untouched |

**⇒ SINGLE FACTOR. No H5 contamination.** Switching the branch changes ONLY the swing-target
construction (`f̂` → `ŷ_head×û_head`); stiffness, rest angle, state timing, and force application
are all identical, and every other system (F8/F9/F10/AXLOCK/release/binding/nucleotide cycle)
is byte-unchanged. The head-frame branch READS one extra array (motorYVec) and DROPS one
(filUVec).

---

## 4. AXLOCK and F9 — do they OVERRIDE the head-frame swing target, or contribute simultaneous torques?

**This is the H1-vs-H3 pre-settle question.** Answer: **they contribute SIMULTANEOUS torques on
DIFFERENT DOFs; neither overrides the swing target.**

- **F9** (`bondForces`/canonical bond kernel, lines 143–154; frozen at 90° here because
  SPHEREHEAD sets `xbParams[9]=1`): a compliant alignment torque driving **head.uVec** toward the
  rest angle (90° here) relative to f̂. Acts on the HEAD's uVec.
- **AXLOCK / F10** (lines 169–191): a compliant alignment torque driving **head.yVec** toward
  ŝ = normalize(n̂bed×f̂), n̂bed = +ẑ ⇒ ŝ = (−suy, sux, 0). Head-only (no segment reaction).
  Acts on the HEAD's yVec (roll).
- **The swing** (`directedSwing`/`directedSwingHeadFrame`): a separate call/kernel that torques
  the **LEVER's uVec** toward `û_L*`, reacting on the head.

These are **three distinct torque channels applied within the same step**. F9 and AXLOCK are
computed in the bond kernel and deposited into the head's torqueSum; the swing is a later call
that adds to the lever/head torqueSum. **Neither F9 nor AXLOCK writes a swing target or overwrites
`û_L*`.** They instead shape the HEAD POSE (û_head, ŷ_head) that the head-frame swing READS on the
NEXT step. So AXLOCK does not "act after swing construction to override it" — the coupling is
**indirect, through the evolving head pose across steps**, not a within-step override.

**Therefore H3 is NOT foreordained by ordering.** Whether the head-frame swing collapses to
DIRSWING (H3) is an **empirical** question of how tightly F9+AXLOCK hold the head pose against the
disturbing F8-tip torque under sliding velocity. The run is licensed.

---

## 5. Mechanistic pre-analysis (what the numbers should reveal — NOT a substitute for the run)

The head-frame reference is `p̂ = ŷ_head × û_head`. Its axial productivity is `p̂·(−ĝ) = p̂·x̂`
(ĝ = glide axis = −x̂; fully-locked ⇒ p̂ = f̂ = +x̂ ⇒ p̂·x̂ = +1). Two competing effects govern
whether p̂ stays axial as clamp velocity v rises:

1. **Roll disturbance is axially NEUTRAL.** If the F8 drag rolls the whole head frame about x̂ by
   φ (û_head and ŷ_head rotate together about x̂), then p̂ = ŷ_head×û_head also rotates about x̂ —
   which leaves its x-component invariant ⇒ **p̂·x̂ unchanged.** A pure roll excursion (exactly the
   DOF AXLOCK controls) does **not** reduce axial productivity. This is the H3 channel.
2. **Out-of-lock TILT is axially COSTLY.** If the F8 drag tilts **head.uVec** away from its 90°
   angle to f̂ (toward/along f̂), or tips **head.yVec** out of the n̂bed plane, then p̂ acquires a
   non-axial component ⇒ **p̂·x̂ falls** ⇒ off-axis angle grows. Under `-vclamp` the F8 stretch
   grows with v (site slides faster than the head relaxes), so the disturbing tip-torque ∝ v and
   the steady head-pose offset ∝ v/(lock stiffness). This is the H1 channel.

The experiment measures which channel dominates: whether the velocity-driven head-pose distortion
is (mostly) axially-neutral roll (⇒ q_HF flat, ΔV₀≈0, **H3**) or out-of-lock tilt (⇒ q_HF declines,
off-axis angle grows, ΔV₀<0, **H1**). DIRSWING's counterfactual q_DIR is pinned at fully-axial
(f̂ ≡ +x̂) at every v, so it is the fixed baseline the head-frame reference is compared against.

**The gate cannot decide H1 vs H3 from code alone** (both channels are physically present; which
wins is a stiffness-vs-load balance). It DOES rule out the two shortcut verdicts: (a) H3 is **not**
foreordained by force-term ordering (§4), and (b) the branch is **single-factor** so any effect is
real, not H5 (§3).

---

## 6. `-swingdiag` diagnostics (additive, default-off) — what is logged

Implemented in `runForceVelocity` (the CPU-only clamp path), computed on the **post-step** head
pose for every BOUND motor in the steady window. All quantities are **commanded on the current
instantaneous geometry** (the head pose is this mode's own trajectory):

- `p̂_DIR = f̂ = seg.uVec` (unit) and `p̂_HF = normalize(ŷ_head × û_head)`.
- **q_DIR = p̂_DIR·ĝ**, **q_HF = p̂_HF·ĝ**, ĝ = (−1,0,0). *(Signed, per the proposal. Note the
  sign: fully-locked ⇒ p̂ = +x̂ ⇒ q = −1. To avoid ambiguity the report ALSO logs axial
  productivity a = p̂·x̂ = −q ∈ [−1,1] with +1 = fully axial-productive, and the unambiguous
  off-axis angle below.)*
- **off-axis angle** `θ_off = acos(clamp(p̂_HF · f̂))` — deviation of the head-frame reference from
  the filament axis; 0° when fully locked (H3), grows under out-of-lock tilt (H1). The scientific
  verdict rides on this quantity (sign-independent).
- **swing torque magnitude** and the **lever angular error** acos(û_lever·û_L*) for the active law.
- per-episode **axial impulse** (already accumulated as `epImp`) and an added **transverse impulse**
  accumulator (the |force ⊥ x̂| glide-plane component per episode).
- q_HF **at episode onset / running min / final** and the episode mean, plus complete-episode counts
  and right-censored counts.

Both p̂_DIR and p̂_HF are computable from geometry in EITHER active mode, so the counterfactual is
logged in both (in DIRSWING mode q_HF is the counterfactual head-frame reference; in `-headswing`
mode q_DIR is the counterfactual filament reference). The **primary force curve** `f̄_available(v)`
comes from the normal FVROW output of each mode's run (NOT from `-fvepisode`).

---

## 7. STEP-0 verdict

- **Single factor:** YES (§3) — only the swing reference (f̂ vs ŷ_head×û_head) changes; H5 excluded
  by construction. Byte-identity of the no-flag path is verified separately (pre/post diff).
- **AXLOCK overrides the head-frame target?** NO (§4) — F9, AXLOCK, and the swing are three distinct
  torque channels on different DOFs (head.uVec, head.yVec, lever.uVec); the swing target is never
  overwritten. Coupling is indirect (through the head pose, across steps). **H3 is NOT foreordained
  by ordering.**
- **Pre-settled?** NO — H1 (velocity-dependent out-of-lock tilt) vs H3 (axially-neutral roll,
  locks hold) is a genuine stiffness-vs-load empirical question (§5). Proceed to the paired clamp
  grid.
- Targets are **live-recomputed** in both variants (not frozen) — test as implemented.
