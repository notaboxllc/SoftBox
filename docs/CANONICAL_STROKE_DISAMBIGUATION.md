# CANONICAL_STROKE_DISAMBIGUATION — does SoftBox apply one power stroke or two?

**Date:** 2026-07-12 · **Branch:** dt-convergence-study · **Runner:** CPU deterministic (velocity-clamp arbiter),
dt=1e-5. `BoA-v1ref` untouched. **AUDIT — live production-kernel instrumentation** (`-strokeaudit`, default-off
**byte-identical**: FVROW verified char-for-char unchanged). The instrumentation reads the **actual `bondData` the
production `bondForces` kernel wrote** and reconciles it against a pose-diagnostic recomputed from the exact snapshot
pose — it does not re-run the physics.

> **OUTCOME A (primary) + OUTCOME E (enabling).** The canonical stack applies **ONE nucleotide-triggered power stroke —
> DIRSWING** (the lever target switches 0°→60° at ADP·Pi→ADP). **F9 is NOT a stroke: SPHEREHEAD freezes it at a fixed
> 90° target (`xbParams[9]=f9Frozen=1`), nucleotide-INDEPENDENT — it is the compliant ⊥-maintainer.** Proven against the
> **live kernel**: recomputing the head torque with `restF9=90°` matches `bondData[3..5]` to **0.0000 pN·nm at every step
> and both velocities**, while `restF9=120°` is off by a fixed **52 pN·nm**. **`PLATEAU_ORIGIN_AUDIT.md` was wrong**
> (Outcome E): its `plateauBalance()` diagnostic used a nucleotide-switched `cocked ? 120 : 90` F9 target that the live
> SPHEREHEAD branch does not use — inflating the F9 torque from the true **~24 pN·nm** (a small perp-maintainer, ~6° off
> 90°) to a spurious **62 pN·nm** (a fake 35°-off "F9 stroke"). **The diagnostic is now fixed; the "F9-dominant stroke"
> claim is retracted** (see the correction to that doc below). **There are NOT two strokes** — there is one stroke
> (DIRSWING) + one orientation constraint (F9@90°) + one plane constraint (AXLOCK) + the F8 tip anchor. PART 5's
> two-stroke ablation is **not triggered** (F9 is not a stroke channel).

---

## PART 1 — static code-path audit (the executed branch, not the names)

Production call path for one bound motor (`GlidingHarness.stepOrig`, canonical defaults CANONICAL=**false**,
SPHEREHEAD/AXLOCK/DIRSWING/XB_IMPLICIT2/LYMN_TAYLOR=**true**):

1. **Nucleotide transition** — `NucleotideCycleSystem.cycleLymnTaylor` (line 808): ADP·Pi→ADP at the fixed rate
   `onPi`; the state used by the force kernels below is the **post-cycle** state.
2. **`bondForces`** (line 830, the `else` branch — CANONICAL is off so it is *not* `bondForcesCanonical`), with
   `sc.xbParams` (SPHEREHEAD ⇒ size-11, `[9]=1`, `[10]=1`):
   - **F8** (`bondForces:117-141`): Hookean spring tip→site; head force + torque `TH = R_H×F`. No nucleotide
     dependence. (The production F8 is then made implicit by `XB_IMPLICIT2`; the pose-time spring evaluation here is
     what `bondData` records.)
   - **F9** (`bondForces:143-153`): `restF9 = (f9Frozen != 0) ? 90.0 : (nuc≠ADP·Pi ? 120 : 90)`. **`f9Frozen =
     xbParams[9] = 1` ⇒ `restF9 = 90.0` in ALL states.** Torque `T9 = j1FMT·(∠(seg,head)−90)·t̂9 /((1/γ_h+1/γ_s)·dt)`,
     `t̂9 = seg×head`; head gets `−T9`, seg gets `+T9`. **Nucleotide-INDEPENDENT.**
   - **F10 / AXLOCK** (`bondForces:155-191`): `axLock = xbParams[10] = 1` ⇒ head-only torque aligning `head.yVec →
     ŝ = n̂bed×seg.u` (`n̂bed`=lab +Z). No nucleotide dependence.
3. **`applyHeadForce`** (834) gathers `bondData` into `forceSum/torqueSum`.
4. **`directedSwing`** (838): `rest = (nuc≠ADP·Pi) ? θ_cocked(60°) : θ_uncocked(0°)`; target lever dir
   `= cos(rest)·head − sin(rest)·seg`; torque `mag = k·∠(lever,target)/((1/γ_l+1/γ_h)·dt)` about `lever×target`;
   **lever gets `+mag·â`, head gets `−mag·â`**. **Nucleotide-DEPENDENT target ⇒ the stroke.**
5. **J1/J2 joints** (`MotorJointSystem.joints`, 817): position (connection) springs. **J1 angular torsion is OFF** —
   `if (DIRSWING) sc.jointParams.set(3,0f)` (line 727) zeroes `j1FracMoveTorq`, so the J1 converter is replaced by
   DIRSWING. J2 angular is off by construction (`fracMoveTorq=0`). `TailAnchorSystem.anchor` (818): rod-tail position
   spring, no torque.
6. **Integration** (843): overdamped Langevin over the summed `forceSum/torqueSum`.

### Nucleotide-state truth table (live-confirmed, PART 2)

| State | F9 target | Actual F9 branch | F9 torque active? | DIRSWING target | DIRSWING torque active? | J1 angular spring? | AXLOCK? |
|---|---|---|---|---|---|---|---|
| unbound | — | `bondForces` `if(s<0) continue` | **no** (skipped) | — | no (skipped) | off | no |
| bound ADP·Pi | **90°** | `f9Frozen?90:…` → 90 | yes — ⊥-maintainer | **0°** (θ_uncocked) | yes (holds lever ≈0°) | off (jp[3]=0) | yes |
| 1st eval after ADP·Pi→ADP | **90°** | → 90 | yes — ⊥-maintainer | **60°** ← *just switched* | yes — **swings lever 0°→60° (the stroke)** | off | yes |
| sustained bound ADP | **90°** | → 90 | yes — ⊥-maintainer | **60°** | yes (holds lever ≈60°) | off | yes |
| ATP detachment | — (LYMN_TAYLOR NONE→ATP = detach; boundSeg→FREE) | skipped once unbound | no | — | no | off | no |

**F9's target is 90° in every bound state — it never changes with nucleotide state.** Only DIRSWING's target changes
(0°→60°) at the transition.

---

## PART 2 — live production-kernel audit (`-strokeaudit`)

Default-off logging that snapshots the exact pose `bondForces` uses, then after the step reads the live `bondData`
head torque and recomputes each channel from the snapshot. Header (auto-printed from the live params):

```
LIVE production stack: bondForces branch=bondForces ; xbParams[9]=f9Frozen=1 ; xbParams[10]=axLock=1 ;
  swing θ_uncocked=0 θ_cocked=60 ; DIRSWING=true LYMN_TAYLOR=true XB_IMPLICIT2=true
⇒ live F9 rest = FROZEN 90° (nucleotide-INDEPENDENT) ; DIRSWING lever target switches 0°→60° at the transition
```

Representative per-step rows across an ADP·Pi→ADP transition (motor 160, v=0):

| t | nuc | restF9 (live) | **d90** | **d120** | swRest | swErr | note |
|---:|---|---:|---:|---:|---:|---:|---|
| 286 | ADP·Pi | 90 | **0.0000** | 52.05 | **0** | 29.1 | pre-stroke: swing target 0° |
| 287 | ADP | 90 | **0.0000** | 52.05 | **60** | 87.8 | ADP·Pi→ADP: **swing target jumps 0°→60°** |
| 288–299 | ADP | 90 | **0.0000** | 52.05 | 60 | 64→10 | stroke: lever swings toward 60°, swErr decays |

- **`d90 ≡ |recompute(restF9=90) − live bondData head torque| = 0.0000 pN·nm`** at **every step, both v=0 and v=16**
  (max d90 = 0.0000). The live kernel uses **90°**.
- **`d120 = 52.0464 pN·nm`** (fixed) — `restF9=120°` does **not** reproduce the live torque.
- **`restF9=90` in every nucleotide state**, including at and after the transition — F9 is nucleotide-independent.
- **`swRest` (DIRSWING target) switches exactly 0°→60° at the transition step** and `swErr` (lever-to-target error)
  then decays from ~88° to ~10° over ~0.1 ms — the lever swinging to the new target **is** the stroke.

---

## PART 3 — reconcile `plateauBalance()` vs the live kernel (the suspected failure mode — CONFIRMED)

> *Does `plateauBalance()` use a 120° F9 target while the live SPHEREHEAD branch uses 90°?* **YES.**

The `PLATEAU_ORIGIN_AUDIT.md` diagnostic hardcoded `restF9 = cocked ? 120.0 : 90.0`, ignoring `xbParams[9]=f9Frozen`.
The live kernel uses **90°** (d90=0 vs d120=52, above). **Fixed** (`plateauBalance` now reads `f9Frozen` and uses 90°;
verified d90=0). Corrected plateau balance at v=0 (survivor-avg, the same run as the prior doc):

| quantity | prior (buggy 120°) | **corrected (live 90°)** |
|---|---:|---:|
| **T9 (F9) torque** | 62 pN·nm | **24 pN·nm** |
| **F9 deviation** | −34.8° (fake 35°-off "stroke") | **−5.9°** (small ⊥-maintainer wobble) |
| TH (F8), hF10 (AXLOCK), Tsw, Tj1 | 33 / 23 / 6 / 0 | 33 / 23 / 6 / 0 (unchanged) |

**⇒ F9 is NOT the dominant "stroke" torque.** Corrected, the head counter-torque to the F8 restoring torque (TH≈33)
is **F9-⊥-maintainer (≈24) + AXLOCK (≈23) + swing (≈6)** — F9-perp and AXLOCK co-dominant, neither a stroke.

**Corrected cross-neck re-emergence table** (`RUN_LOGS/2026-07-12_plateau_grid_f9fixed.txt`, survivor-avg ± sem over 4
seeds; head torques pN·nm; F9@90°):

| quantity | neck 40° | neck 60° | neck 80° | v=16 (neck60) | reading |
|---|---:|---:|---:|---:|---|
| TH (F8 restoring) | 32.24 | 32.74 | 33.20 | 31.82 | — |
| **T9 (F9 ⊥-maintainer)** | **23.47** | **23.69** | **23.76** | **21.98** | small, **re-emerges** (was buggy 62) |
| **hF10 (AXLOCK)** | 23.44 | 23.16 | 22.83 | 23.23 | **co-dominant with F9-⊥**, re-emerges |
| Tsw (swing) | 8.22 | 6.04 | 4.93 | 5.91 | smallest; only term tracking neck |
| **F9 dev (from 90°)** | **−6.1** | **−6.7** | **−7.5** | **−1.6** | small ⊥ wobble (was buggy −35°); →0 at v=16 |
| AXLOCK dev | 25.8 | 25.5 | 25.1 | 25.6 | re-emerges |

So the corrected balance is **F9-⊥ ≈ AXLOCK co-dominant** (both ≈23, flat across a 1.9× neck-swing change), with F9
only ~6° off its fixed 90° target (and just ~1.6° off at the v=16 ceiling). The **directional/idealization findings of
the plateau audit survive** (AXLOCK load-bearing — now clearly *co-dominant, not second to F9*; constraint-set;
thermal-scale; frustrated stroke) — only the mislabeling of the ⊥-maintainer F9 as the dominant stroke torque was wrong.

---

## PART 4 — stroke-channel work accounting

`P_i = τ_i·ω` integrated over tracked windows (kT), binned by transition phase:

| channel | pre-ADP·Pi | stroke (≤10 steps post) | plateau |
|---|---:|---:|---:|
| **F9** | +20 | +22 | **+112** |
| **DIRSWING** | +81 | +55 | +22 |

**Read with the caveat that raw τ·ω is confounded by head-noise** (the light sphere head fluctuates ±30°/step even at
v=0 thermal-off, so both channels exchange large work riding/fighting the jitter — raw magnitude is NOT a clean stroke
discriminator). The **structure** still separates them: **DIRSWING work is front-loaded** (pre+stroke = 136 ≫ plateau
22 — active while the lever swings, then quiesces), whereas **F9 work is duration-proportional** (plateau 112, the
long-lived ongoing ⊥-maintenance — no transition concentration). **The decisive discriminator is the target change**
(per the task's own definition: a stroke needs a *nucleotide-dependent target change* AND transition-coupled positive
work): **DIRSWING's target switches 0°→60° at the transition and it does its work as the lever swings there; F9's target
never changes, so its work is not transition-coupled.** Only **DIRSWING injects stroke work coupled to ADP·Pi→ADP.**

---

## PART 5 — ablation: NOT triggered

The condition ("*if the live runtime confirms that both F9 and DIRSWING are active stroke-like channels*") is **not
met**: F9 is a nucleotide-independent ⊥-constraint, not a stroke. There is no second stroke channel to ablate against,
so the two-stroke selector suite is **not built** (correctly — building it would presuppose the refuted premise). The
single stroke (DIRSWING) and its nucleotide-triggered target switch are already isolated by the PART-2 live evidence.

---

## Outcome classification — **A (primary), with E as the enabling error**

- **Outcome A — one active stroke mechanism. SELECTED.** SPHEREHEAD fixes F9 at a nucleotide-independent 90°
  (⊥-maintainer, not stroke-like); **DIRSWING is the only nucleotide-triggered stroke channel** (target 0°→60°,
  transition-coupled work). *Implication (per the task): the previous plateau audit's 120° F9 reconstruction was
  incorrect and must be revised.* — Done (PART 3 + the appended correction).
- **Outcome E — diagnostic/runtime disagreement. ALSO TRUE (the cause of the prior error).** `plateauBalance()` used a
  120° F9 target the live kernel does not use ⇒ the prior F9 torque attribution was invalid. **Now corrected and
  live-validated (d90=0).**
- **Outcome B (two strokes) — REFUTED.** F9's target is nucleotide-independent; it cannot perform a
  transition-coupled stroke. There is no compounded stroke architecture.
- Outcomes C, D — not applicable (F9 is a genuine constraint doing real ⊥-maintenance torque, not a negligible one, so
  it is not merely a terminology quibble (C); and the F9 target is not flag/order-dependent within the canonical
  stack (D) — it is uniformly 90° whenever SPHEREHEAD is on, which is the canonical default).

## Narrowly-worded conclusion (the four distinct things)

- **Head-orientation constraint:** **F9** — a fixed 90° head–actin ⊥-maintainer, nucleotide-**independent**, torque
  ≈24 pN·nm (mean magnitude, fluctuating with the head), does ⊥-maintenance work proportional to dwell. **NOT a stroke.**
- **Lever swing:** **DIRSWING** — the sole power stroke; nucleotide-**dependent** lever target 0°→60° at ADP·Pi→ADP,
  with transition-coupled positive work. **The one stroke.**
- **Nucleotide-dependent stroke:** exactly one — DIRSWING. (J1 angular converter is OFF, replaced by DIRSWING.)
- **Passive reaction / plane torque:** **AXLOCK** (F10, head.yVec→axial plane, nucleotide-independent constraint,
  ≈23 pN·nm) and the **F8 tip anchor** (bond restoring force/torque). Constraints, not strokes.

**SoftBox applies ONE power stroke (DIRSWING), plus two orientation constraints (F9@90°, AXLOCK) and the F8 anchor.**

## Artifacts

- Code: `-strokeaudit` (live reconciliation `strokeReconcile` + `omega` + per-phase work) in `GlidingHarness`;
  **fix** to `plateauBalance()` (F9 respects `f9Frozen`). Default-off **byte-identical**.
- Runs: live audit `scripts/run_gliding.sh -matbox 50 -density 300 -vclamp {0,16} -strokeaudit 4000`; corrected plateau
  grid `RUN_LOGS/2026-07-12_plateau_grid_f9fixed.txt`.
- Commands:
```
scripts/run_gliding.sh -matbox 50 -density 300 -vclamp 0  -strokeaudit 4000   # v=0  live reconciliation + work
scripts/run_gliding.sh -matbox 50 -density 300 -vclamp 16 -strokeaudit 4000   # v=16 live reconciliation
scripts/run_gliding.sh -matbox 50 -density 2000 -vclamp 0 -epkernel 8000      # corrected plateau balance (F9@90°)
```
- Constraints honored: audit/logging only; live-vs-diagnostic reconciled to numerical precision (d90=0, not a
  magnitude match); executed branch followed (not names/comments); F9 called a stroke only if it had a nucleotide
  target change + coupled work (it has neither) — it is named a constraint; `BoA-v1ref` untouched; prior doc corrected.
