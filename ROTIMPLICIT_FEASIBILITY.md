# Is `-rotimplicit` feasible as a parity-clean linearized rotational star, and where does the explicit gliding stack converge?

**Date:** 2026-07-05. **Scoping only — NO source edits this session** (Part A is a code read; Part B is runs on
existing flags). Default trivially byte-identical; `BoA-v1ref` untouched. Follows `STROKE_DT_RATE_DIAGNOSIS.md`
(the residual is BOTH a real F9/F10 alignment per-step-fraction rate AND a residual rotational STIFFNESS —
`-alignrate` cuts the fine-dt runaway ~40 % but a 2.24× residual survives), `COUPLED_IMPLICIT_XB_FINDINGS.md`
(the F8 **translation** star: exact-linear, scalar-per-axis, parity-clean), and `SEG_IMPLICIT_FINDINGS.md` (the
diagonal collective-load implicit is a dense/ring cure — gliding is sub-threshold).

---

## HEADLINE (one line)
**Part A — `-rotimplicit` is FEASIBLE as a parity-clean, on-block, closed-form (no-Newton) linearized star — BUT
it is NOT a scalar augmentation of the F8-translation star: it is a NEW dense 6-DOF (center+orientation) coupled
per-segment star (the translation↔rotation `c_i` coupling that CANCELLED in `-xbimplicit2` RE-ENTERS as the
central unknown), and scoped to the F8 tip-torque it does NOT implicitize the large-per-step F9/F10/swing
alignment torques (those are not small-angle-linearizable and stay on the `-alignrate` rate path). So: clean
parity, materially heavier build than `-xbimplicit2`, sufficiency-for-the-full-residual UNPROVEN.**
**Part B — the `-ratefix` explicit stack CONVERGES (it does not run away): per-bound drift 0.822 → 1.381 → 1.642
→ 1.783 across dt 1e-5 → 6.25e-7, with the climb DECAYING (1.68× → 1.19× → 1.086× per refine) — flat (per-bound
climb ≤~1.09, avgBound ≤~1.15) by dt=6.25e-7. Converged reference: per-bound ≈ 1.78 (→ ~1.9 extrapolated),
avgBound ≈ 4.1–4.3, velFitX ≈ 7.3–8 µm/s. The explicit production-dt (1e-5) glide UNDER-shoots the converged by
~2.2× on per-bound / ~3× on velFitX — that ~2.2× per-bound gap is exactly what `-rotimplicit` (or the sub-step)
must recover at production dt. Fallback cost of "just run at 6.25e-7" = 16× the wall-clock per simulated second
(~7 → ~118 min/sim-s).**

---

## PART A — FEASIBILITY SCOPING (code read + reasoning; no edits, no build)

Default `-full` gliding path = **SPHEREHEAD + AXLOCK + DIRSWING** (`GlidingHarness.java:244-246`), so the motor
law is `CrossBridgeSystem.bondForces` (axLock branch) + `CrossBridgeSystem.directedSwing`, integrated by
`RigidRodLangevinIntegrationSystem.integrate`. The F8 **translation** (head center + segment center) is already
implicit under `-xbimplicit2` (`coupleComputeA`/`coupleSolveSeg`/`coupleCorrectHead`); everything ORIENTATION is
explicit forward-Euler.

### A1 — Torque enumeration (what is integrated EXPLICITLY on orientation)

The orientation update is explicit forward-Euler: `Δθ = (T/γ_rot)·dt`, applied as an infinitesimal frame rotation
of `uVec`/`yVec` then renormalized (`RigidRodLangevinIntegrationSystem:93-110`). Every torque below lands in
`torqueSum` and is advanced by this explicit step. `hbRGy/sbRGy` etc. are the body-frame rotational drags.

**On the HEAD sub-body (`3m+2`) orientation:**

| term | law / location | form | depends on |
|---|---|---|---|
| **F8 tip-torque `T_H = R_H×F8`** | `bondForces:137-138` | `R_H = ½·headLen·ĥ_u·1e-6`, `F8 = k·(site−tip)`, `k=myoSpring=1 pN/nm` | head pose (center+uVec via tip), seg pose (site `=sc+aOff·ŝ_u`) → the **F8 stretch vector** |
| **F9 `−T9`** | `bondForces:143-153` | alignment: head.uVec→seg.uVec to rest 90°/120° (nucleotide switch); `tm=j1FMT·Δang·DEG2RAD/((1/γ+1/γ)·dt)`, `j1FMT=0.4` **per-step fraction** | head.uVec, seg.uVec, nucleotide state |
| **F10/axlock `hF10`** | `bondForces:155-191` | head.yVec→ŝ=n̂bed×seg.uVec (axlock, head-only), same `0.4·ang/(…·dt)` **per-step fraction** | head.yVec, seg.uVec |
| **directedSwing `−mag·â`** | `directedSwing:260-266` | drives lever→target (`û_L*=cosθ·û_head−sinθ·f̂`); `mag=k·ang/((1/γ_lev+1/γ_head)·dt)`, `k=0.4` **per-step fraction**; +mag on lever, −mag on head | head.uVec, seg.uVec, nucleotide state |

**On the SEGMENT (`s`) orientation:**

| term | law / location | form | depends on |
|---|---|---|---|
| **F8 seg-torque `T_S = R_S×(−F8)`** | `bondForces:139-141` | `R_S = aOff·ŝ_u·1e-6` (bound-site offset along the segment) | seg pose, head pose (F8 stretch) |
| **F9/F10 seg-side `+T9`,`+T10`** | `bondForces:200-202` | reactions of the head alignments (0 for F10 under axlock) | as above |
| **chain F3/F4 torsion** | `ChainBendingForceSystem` | seg↔neighbour-seg bending/torsion | neighbour segments |

**Already implicit / rate-treated (NOT pure-explicit orientation):**
- F8 **translation** (head+seg CENTERS) — implicit via the `-xbimplicit2` star. The seg/head **ORIENTATION** is
  NOT — the star writes only `filCoord`/`bodyCoord` (centers), never uVec/yVec.
- `-ratefix` = build-time per-step→per-time conversion of the **PAIRS chain** fracMove/fracMoveTorq (orthogonal).
- `-alignrate`/`-strokerate` (STROKE_DT) convert the F9/F10/swing per-step **coefficient** to a per-time rate —
  but that is a COEFFICIENT change; the integration stays explicit forward-Euler.
- The catch `g(F)` release is a proper per-time rate (dt-correct) — not in this channel.

⇒ **The premise holds:** the F8 tip-torque `R×F8`, the F9/F10/axlock alignment, and the directedSwing stroke are
all advanced by **explicit forward-Euler on orientation**. None is implicitly integrated. (Bail condition NOT
triggered: the stiff term IS orientation and IS explicit.)

### A2 — Linearizability of the F8 tip-torque

Because F8 is a zero-rest Hookean spring, `F8 = k·(site − tip)`, `tip = hc + R_H`. The head tip-torque is
`T_H = R_H × F8 = k·R_H × (site − hc)` (the `R_H×R_H` self-term vanishes). Perturb the head orientation by a small
axis-angle `δφ`: `R_H → R_H + δφ×R_H`, so with `w ≡ site − hc`,

```
δT_H = k·(δφ×R_H)×w = k·[ R_H (w·δφ) − (R_H·w) δφ ]  =  −K_rot · δφ,
K_rot = k·[ (R_H·w) I − R_H w^T ].
```

**So YES — it linearizes about the current orientation into a per-body rotational stiffness `K_rot`, and
backward-Euler `(I + (dt/γ_rot)·K_rot)·δφ = (dt/γ_rot)·T_H(φ_n)` is a closed-form 3×3 solve per head — no Newton.**
But two structural facts distinguish it sharply from the translation star:

1. **`K_rot` is DENSE and NON-SYMMETRIC** (`R_H w^T` is a general rank-1 outer product, symmetric only if
   `R_H ∥ w`). It is NOT the isotropic `k·I` that let the translation star decouple into three independent
   scalar body-axis divides. The rotational solve is an irreducible small **matrix** solve (3×3 per head, 6×6 per
   segment after coupling in translation — see A3), not a scalar.
2. **It couples head-orientation ↔ segment-pose, not neighbours.** `w = site − hc` with `site = sc + aOff·ŝ_u`:
   the head's rotational stiffness depends on the segment CENTER **and** the segment ORIENTATION (`ŝ_u`), and
   symmetrically `T_S = R_S×(−F8)` couples segment orientation to head pose. Crucially F8 **never couples two
   segments or two heads** (a head binds exactly one segment; `boundSeg` is a single int) — so the operator
   **STAYS inside the per-segment CSR block** (chain torsion, the only seg↔seg orientation coupling, is held
   explicit exactly as the translation star / `-extimplicit` diagonal did). **No off-block, no Newton.**

### A3 — Composition with the translation star: the `c_i` coupling RE-ENTERS

The `-xbimplicit2` translation star was clean precisely because it **froze the bond offsets** `c_i = aOff·ŝ_u −
½headLen·ĥ_u` at their old value and folded them into `A_i`, where they **cancelled** (COUPLED §0.3). **The
rotational channel IS those offsets becoming implicit** (`R_H = ½headLen·ĥ_u` and `R_S = aOff·ŝ_u` are exactly the
rotating halves of `c_i`). So the translation↔rotation coupling the pure-translation star deliberately dropped
now **re-enters as the central unknown**:

- The per-bond stiffness grows from the translation star's isotropic `k·I` (3+3 centers) to a **12×12 per-bond
  block** (6 head DOF + 6 seg DOF): translation-translation `= k·I` (clean), translation-rotation `= k·[R]×`
  (skew), rotation-rotation `= K_rot` (dense/non-symmetric).
- The Schur elimination that gave the translation star its `A_i,B_i` form still applies **structurally** (F8 is
  block-diagonal per segment), but the per-head elimination is now a **6×6** (center+orientation) inverse and the
  per-segment central solve is a **6×6 dense non-symmetric** solve, not a scalar-per-axis divide.

⇒ **it augments the same star (translation+rotation solved together, still per-segment, still closed-form) — but
expands it into a dense 6-DOF coupled solve; the `c_i` coupling re-enters, and the clean scalar structure is
lost.** No iteration is forced (one linearization per step), and nothing leaves the block.

**The load-bearing caveat — scope vs sufficiency.** The F8 tip-torque linearization is valid because the head's
per-step F8-tip angle is small at steady glide (a genuine physical stiffness). But the DOMINANT per-step
rotational motion is the **F9/F10/swing alignment** torques, which by design relax **0.4 of the remaining angle
per step** (≈24° in one step across a 60° stroke) — a constraint-like, NOT small-angle, motion. Linearizing them
into the implicit operator is INVALID (a linear stiffness cannot represent a fixed-fraction-per-step constraint
without re-evaluation ⇒ Newton), so they must stay on the explicit / `-alignrate` rate path. Therefore
`-rotimplicit` scoped to the F8 tip-torque implicitizes **only the F8-tip half** of the rotational stiffness.
STROKE_DT's residual (2.24× after `-alignrate`) is attributed to "R×F8 tip torque + alignment" but was **not
isolated to the F8-tip fraction** (single-seed). So the star's ability to close the full residual is **unproven**
and must be measured (a targeted F8-tip-torque-only rotational isolation) before the build is judged worth it.

### A4 — VERDICT: **CLEAN (feasible) — but a NEW dense 6-DOF star, and sufficiency is UNPROVEN.**

- **Parity / structure:** CLEAN. On-block (per-segment CSR, no seg↔seg, no head↔head via F8), closed-form (no
  Newton, one linearization/step), expressible as the SAME per-head-pure → CSR-gather → per-head-pure pattern
  (disjoint writes, no atomics, no `KernelContext`) → CPU≡GPU-capable. The bail condition is not triggered.
- **Cost:** materially heavier than `-xbimplicit2` — a dense non-symmetric 6×6 (segment) + 6×6 (per head)
  linearly-implicit solve with analytic rotation Jacobians (`[·]×` skews), NOT the scalar-per-axis divide.
- **Sufficiency:** OPEN. Scoped to F8 tip-torque it leaves the large-per-step F9/F10/swing alignment on the
  explicit/`-alignrate` path; whether the F8-tip fraction is the bulk of the 2.24× residual is unmeasured.

**⇒ Not FORCED (no off-block, no mandatory Newton) — so the build is not automatically as invasive as sub-stepping
on parity grounds. But it is a genuinely new coupled star (not an augmentation-by-a-scalar), and its payoff is
capped by the unproven F8-tip fraction of the residual. Recommend: before scoping the build, run a targeted
F8-tip-torque rotational isolation (does making ONLY `R×F8` implicit move the fine-dt per-bound climb?) against
the Part-B reference. If that fraction is small, sub-step (which handles the full nonlinear rotational stroke
exactly, no linearization gamble) is the better lever; if large, the dense rotational star is worth it.**

---

## PART B — EXPLICIT CONVERGENCE REFERENCE (measurement only)

Config: `-full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix`, coltol10, density 1000, **seed 0**, GPU
device-resident. dt ladder 1e-5 (120k) → 2.5e-6 (480k) → 1.25e-6 (960k) → 6.25e-7 (1920k), ≥1 s sim window each.
`velFitX`/`avgBsteady` from `GRID_ROW`; `avgBoundSteady` from `STATS_STEADY_ROW`. Batch: `run_rotimpl_conv.sh`,
log `RUN_LOGS/2026-07-05_rotimpl_conv.txt`.

**Runner disclosure:** GPU device-resident TaskGraph (the `-xbimplicit2`+`-ratefix` gliding path, the same
23-kernel + coupled-star device graph validated in COUPLED/STROKE_DT). Single seed (deliberate — decisive on the
*presence + location* of a floor; the rigorous multi-seed reference is deferred to the `-rotimplicit` acceptance
ladder).

| dt | steps | velFitX (noisy) | avgBsteady | per-bound = velFitX/avgBound | climb per refine (velFitX / avgB / per-bound) | wall / cost |
|--:|--:|--:|--:|--:|--:|--:|
| 1e-5 | 120k | 2.459 | 2.993 | **0.822** | — | 8.5 min · 7.1 min/sim-s |
| 2.5e-6 | 480k | 5.088 | 3.685 | **1.381** | ×2.07 / ×1.23 / ×1.68 (4×) | 34 min · 28 min/sim-s |
| 1.25e-6 | 960k | 5.896 | 3.591 | **1.642** | ×1.16 / ×0.97 / ×1.19 (2×) | 70 min · 58 min/sim-s |
| 6.25e-7 | 1920k | 7.330 | 4.112 | **1.783** | ×1.24 / ×1.15 / **×1.086** (2×) | 141 min · 118 min/sim-s |

**Reads (lean on per-bound + avgBound; velFitX is the single-seed-noisy metric):**
1. **The `-ratefix` explicit stack CONVERGES — it does not run away.** Per-bound drift climbs 0.822 → 1.381 →
   1.642 → 1.783, but the per-refine climb DECAYS geometrically (increments 0.559 → 0.261 → 0.141, ratio ≈0.5),
   and the last 2× step (1.25e-6→6.25e-7) is only **1.086×** — below the ~1.15 flat threshold. avgBound's last
   step is **1.145×** (≈flat; the 2.5e-6→1.25e-6 dip 3.685→3.591 is single-seed wobble, recovered at 6.25e-7).
   This EXTENDS STROKE_DT (which stopped at 2.5e-6 and saw "2.24× residual"): the residual rotational stiffness
   is **real but BOUNDED — a finite ~2.2× overshoot that flattens by 6.25e-7**, not an unbounded runaway.
2. **Decision: DO NOT extend to 3.125e-7.** The gate (extend only if the 1.25e-6→6.25e-7 climb is still >~1.15)
   is not met — per-bound 1.086×, avgBound 1.145× are both ≤~1.15. The reference is established. **Caveat:**
   velFitX alone still shows 1.24× (its rise is mostly the avgBound dip-recovery, not per-bound), so the
   single-seed velFitX floor is noisy — the convergence read rests on the robust per-bound (1.086×) + avgBound.
3. **The gap `-rotimplicit`/sub-step must close.** Explicit at production dt=1e-5: per-bound **0.822**, velFitX
   **2.459**, avgBound **2.99**. Converged (~6.25e-7): per-bound **≈1.78** (→~1.9), velFitX **≈7.3–8**, avgBound
   **≈4.1–4.3**. So production-dt UNDER-shoots the converged glide by **~2.2× per-bound / ~3× velFitX / ~1.4×
   avgBound**. A dt-robust production-dt scheme must recover that ~2.2× per-bound at dt=1e-5.

**Converged reference value:** per-bound drift **≈ 1.78** (measured @6.25e-7; ~1.9 extrapolated), avgBound
**≈ 4.1–4.3**, velFitX **≈ 7.3–8 µm/s** (noisy). **dt at which it flattens:** **6.25e-7** (per-bound climb drops
to 1.086×). **Fallback cost of "just run at 6.25e-7":** **16× production dt ⇒ ~16× wall per simulated second**
(~7.1 → ~118 min/sim-s; ~235 steps/s throughout, so the cost is purely the step count).

Raw: `RUN_LOGS/2026-07-05_rotimpl_conv.txt`; batch `run_rotimpl_conv.sh`; log `.last_run_status`.

---

## Plain statement
**`-rotimplicit` IS feasible as a parity-clean linearized rotational star (on-block per-segment CSR, closed-form,
no Newton) — but it is a NEW dense 6-DOF center+orientation coupled star (the translation↔rotation `c_i` coupling
that cancelled in `-xbimplicit2` re-enters), materially heavier than the scalar `-xbimplicit2`, and scoped to the
F8 tip-torque it leaves the large-per-step F9/F10/swing alignment on the `-alignrate` rate path — so its
sufficiency for the full residual is unproven. The explicit `-ratefix` stack CONVERGES and flattens by
dt=6.25e-7 (per-bound climb 1.086×) at per-bound ≈1.78 / velFitX ≈7.3–8 µm/s — a bounded ~2.2× per-bound overshoot
above the explicit production-dt value (0.822), recoverable only at 16× the wall-clock; that ~2.2× is the target,
and the recommended next step is a targeted F8-tip-only rotational isolation to size how much of it the (heavy)
star would actually recover before committing to build it vs the sub-step.**
