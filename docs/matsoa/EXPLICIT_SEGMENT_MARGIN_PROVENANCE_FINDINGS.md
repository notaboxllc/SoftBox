# Segment-end binding margin — provenance + safe removal (explicit motor)

**Follow-on to** `EXPLICIT_BINDING_REACH_SENSITIVITY_FINDINGS.md` (which found the in-segment margin `bindP[10]=0.05 µm`
is the clean recruitment lever) **and** `EXPLICIT_BINDING_RESOLUTION_FINDINGS.md` / `EXPLICIT_BINDING_SNAP_FINDINGS.md`.
Two objectives: **(1)** where did the 50 nm segment-end exclusion come from and why; **(2)** can it be removed entirely
and replaced by explicit, deterministic joint handling. **Canonical production defaults UNCHANGED in this study.**

**Instruments (new, diagnostic-only):** `JointMarginProbe` (deterministic Brownian-off geometric + bond-force sweep
across one internal actin joint) + the segmentation arithmetic. `BoA-v1ref` byte-clean; production untouched.

---

## Headline
The 50 nm margin is a **legacy hard-coded constant with an endpoint-disambiguation intent but no derivation**, and it
is **masking an ownership-handoff gap**: `nearestSeg2D`'s first-min tie-break + `|foot|≤half+0.02` tolerance keeps a
segment owning world points **past its own joint**, producing `bindArc > segLength` there — so even at margin=0 the
join-neighbourhood fails g7. The margin hid this by excluding the whole near-joint region. **The correct fix is
deterministic half-open ownership (`foot∈[−half,half)`), NOT a finite exclusion length** — with it, margin can go to 0
(machine-ε), which is the ONLY segmentation-invariant choice. v1 and inc-4a never had this margin (they bind anywhere
the foot is on the segment).

---

## Part A — provenance (deliverables 1, 2, 3)

**A1 — implementation map.** The exclusion is the single-point gate `g7: bindArc ∈ (margin, 2·half − margin)`,
`margin=0.05` a HARD-CODED LOCAL literal (not a `Constants`/`Tol` field), repeated across the two-body motor arc:
`TwoBodyConverterMotor.java` gate sites L2222/3815/4569/4808/5296/5840/6808 (`gateEval`/`stepGlideS2`/laser-trap),
`TwoBodyBeamAnalyticGpu.matBindExplicit` L460 (the explicit GPU path, via `bindP[10]`), `MatSoaSlice` L379 (mat slice),
`ExplicitCompleteMatHarness.packExMat` L77 (`bindP[10]=0.05`), and `.java:380`. `bindArc = dot(xF8 − e1, u)` = foot-arc
from end1 (gate2D L7). **Unique to the two-body motor arc** — the original inc-4a `BindingDetectionSystem`
(`reachTestDistSq`, the v1 `MyoMotor.checkFilSegCollision` port) has **NO** end-margin: it accepts any foot with
`alpha∈[0,1]` (`docs/BINDING_SEARCH_SURVEY.md` L31; `INC6C_TESTB_SCPR_FINDINGS.md` L30 "purely geometric over segment
endpoints"). **v1 `MyoMotor.checkFilSegCollision`** (`BoA-v1ref` L410–420): `alpha=numer/denom; if(alpha<0||alpha>1)
return;` → binds anywhere on `[0,L]`, **no interior exclusion**.

**A2 — git provenance.** `git log -S "2*half-margin"` and `git blame` both point to the **introducing commit**:

- `INTRODUCING COMMIT:` **e17b5a4** — "Two-body replacement-motor arc: laser-trap characterization (3A–3G) + closed
  chemomechanical cycle (4A) + ensemble/gliding feasibility (4B–4D)"
- author Jonathan Alberts, 2026-07-14 21:47:58 −0700
- introduced **already as `margin=0.05`** (never a different value), with the sole inline rationale being the comment
  **`// interior material coordinate`** (L2230). It is a large squashed arc commit — no isolated diff, test, or issue
  explains the *value*. `1b227c0` (4G/4F/4E) later *copied* the same literal into the explicit/supported paths.

**A3 — classification.** DOCUMENTED rationale: the comment "interior material coordinate" ⇒ **segment-ownership /
endpoint disambiguation** (keep the bind point interior to one segment). NOT physical: v1 binds at `[0,L]` with no
exclusion, so this is **not an actin-end physical exclusion** and was **not ported from v1**. The *value* 0.05 µm has
**no documented derivation**; 50 nm is ~28 % of the half-segment — vastly larger than disambiguation needs
(disambiguation needs ~ε), and 0.05 µm is a generic "small distance" reused throughout this codebase (MANCHOR_Z,
matbox z-halfwidth, floorZ offset, …). ⇒ a **legacy round arbitrary buffer** with a disambiguation intent.

- `ORIGIN FOUND: YES`
- `INTRODUCING COMMIT: e17b5a4 (2026-07-14, two-body arc squash)`
- `ORIGINAL RATIONALE: endpoint/ownership disambiguation ("interior material coordinate"); the 50 nm VALUE is underived`
- `EVIDENCE QUALITY: intent DIRECT (code comment) / value UNKNOWN (no derivation) / "not physical, not from v1" DIRECT`

---

## Part B — correct zero-margin semantics (deliverable 4)

**B1 — unique ownership rule.** Current `nearestSeg2D` = min perpendicular distance over segments with
`|foot|≤half+0.02`, first-min tie-break. This is **deterministic** (iteration-order argmin, CPU/GPU identical) but it
**does NOT hand off at a joint**: the `+0.02` (20 nm) foot tolerance + first-min let a segment keep ownership of points
up to 20 nm past its own end2 (see B/C1). **Proposed rule (half-open):** segment `i` owns local foot `∈ [−half, half)`;
the last segment owns `[−half, half]`. Equivalent to a **global filament material coordinate** `S = i·segLen + bindArc`
mapped uniquely to one segment. Deterministic, thread-independent, and it hands off exactly at each joint.

**B2 — deduplication.** At/near a shared joint TWO segments satisfy `|foot|≤half+0.02` (foot-interior), but
`nearestSeg2D` returns exactly ONE (argmin) and each motor writes only its own `boundSeg` ⇒ **no double-binding**;
recruitment is proportional to filament LENGTH, not the number of joints (verified: exact-joint case = 2 foot-interior
candidates, 1 chosen). The half-open rule makes the single choice explicit (foot∈[−half,half) selects one).

**B3 — binding-coordinate continuity.** Across a joint the **world binding point and the global material coordinate are
continuous**; only the segment index switches discretely. The stored `bindArc` is continuous WITHIN a segment and, under
half-open ownership, resets to ~0 on the neighbour at the joint (the same world point). Under the CURRENT first-min
ownership `bindArc` instead runs PAST `segLength` (discontinuous representation — the defect the margin masks).

## Part C — static joint fixtures (deliverables 5, 6) — `JointMarginProbe`
Deterministic (Brownian-off) sweep of a synthetic F8 point across the internal joint 5|6 (segLength 0.17550 µm,
half 0.08775). `bindArc` (foot from end1), g7 for margins {0.05, 0.0125, 0.0}, half-open owner, world point.

**C1 — position sweep (collinear).** `nearestSeg2D` returns **segment 5 for the ENTIRE ±20 nm sweep (0 switches)** —
it never hands off to segment 6, so `bindArc` grows continuously 0.1555 → 0.1955, **exceeding segLength (0.1755) for
every point past the joint**. Consequently:
- **margin=0.05:** g7 PASS only for `bindArc<0.1255` ⇒ a **50 nm dead zone before the joint** (and 50 nm after, on seg 6).
- **margin=0.0125:** dead zone shrinks to ±12.5 nm.
- **margin=0.0:** STILL fails at/past the joint — because seg 5 owns those points with `bindArc≥segLength` (g7 upper
  bound `2·half=segLength` fails). **So removing the margin alone does not close the joint dead-zone; the OWNERSHIP is
  the cause.** The **half-open owner column** switches cleanly 5→6 exactly at Δx=0 — the correct handoff.

**C3 — exact-joint case.** 2 foot-interior candidate segments (5 and 6 share the point); `nearestSeg2D` deterministically
picks one (first-min ⇒ CPU/GPU identical); no NaN / zero-length tangent; half-open ownership selects exactly one
unconditionally.

**Bond force/torque continuity.** Head held fixed above the joint, bound site swept across it (with correct half-open
segment assignment): **|F8| is continuous across the joint switch** (4.0012 pN just-left = 4.0012 pN just-right; the
site is world-continuous) at bends 0/5/15/30°. The F8 spring is world-site-based ⇒ no jump. The tangent-dependent
alignment torques (F9/F10 use `filUVec[s]`) are the only quantity that could jump at a *bent* joint — a pre-existing
segmentation property the margin does NOT actually guard (a bond 50 nm inside still uses its own segment's tangent), and
in production the filament is near-collinear (chain F3/F4 holds joint gaps ~9.6 nm, per-joint angle a few °).

## Part E — segmentation-invariance (deliverable 9) — the decisive criterion
Bindable fraction of a segment vs discretization (exact arithmetic; total filament length fixed):

| margin | 1× (segL 0.1755) | 2× (0.0878) | 4× (0.0439) |
|--|--:|--:|--:|
| **0.05** | 43.0 % | **0 % — IMPOSSIBLE** (2·margin>segL) | **0 % — IMPOSSIBLE** |
| 0.0125 | 85.8 % | 71.5 % | 43.0 % |
| **0.0** | **100 %** | **100 %** | **100 %** |

**Only margin=0 is segmentation-invariant.** A fixed absolute margin (0.05 *or* 0.0125 µm) is NOT invariant — 0.05 µm
is *catastrophic* (binding becomes impossible at ≥2× refinement, since `2·margin > segLength`), and 0.0125 still shrinks
the bindable fraction with refinement. Zero-margin bindable length = filament length at every discretization, and it does
NOT create artificial per-joint recruitment (one owner per point, Part B2). **The segmentation criterion favours removal.**

## Part F — whole-assay validation (deliverable 10)
From `EXPLICIT_BINDING_REACH_SENSITIVITY_FINDINGS.md` §8 (CPU 100–700 + GPU 1500/3000, 3 seeds, 0 invalid): margin
0.05→0.0125→0 monotonically raises recruitment, **left-shifts** the density-velocity curve (faster + higher occupancy +
better continuity at ρ100–1500) with the **ρ3000 plateau preserved** (~−4 µm/s), onset F8 p99 flat (7.1–7.2 pN), 0
immediate-detach, 0 solver failures. Margin=0 gave the highest recruitment (ρ700 mB 4.84, bindRate 1.88×) with
continuity 1.000; its only geometric wart is the un-handed-off joint dead-zone (Part C1) — closed by the ownership fix.

## Part G — is any finite buffer necessary? (deliverable 13, minimum tolerance)
A finite buffer is justified ONLY by a demonstrated defect. Findings:
- duplicate candidate binding — **NO** (argmin ⇒ one owner; Part B2/C3).
- discontinuous world binding point / force — **NO** (world-continuous; |F8| continuous across the joint).
- CPU/GPU disagreement / iteration-order dependence — **NO** (deterministic argmin; pure geometry, identical by
  construction; the mat CPU≡GPU bind identity is already `0/600 mismatches`, `EXPLICIT_FREEBINDING_FINDINGS §8`).
- instability at exact joints / NaN / zero-length tangent — **NO**.
- non-convergence under segmentation refinement — **NO** for margin=0 (Part E).
- pathological immediate detachments / solver failures — **NO** (Part F, 0 across all runs).

The ONLY margin=0 numerical wrinkle is `bindArc == segLength` *exactly* at a joint failing the strict `<` bound (a
measure-zero point) — resolved by half-open ownership (the neighbour then owns it at `bindArc≈0`) or a machine-ε
tolerance. **`MINIMUM NECESSARY TOLERANCE:` machine-scale ε on the arc (≲1e-6 µm), NOT a nanometre exclusion.** No
physical buffer is warranted.

## Part H — recommendation + minimal patch (deliverables 13, 14)
`RECOMMENDED IMPLEMENTATION:` **replace the 50 nm margin with deterministic half-open ownership + a machine-ε arc
tolerance.** This removes the excluded physical length, guarantees unique ownership, keeps CPU/GPU identical, preserves
material-latched attachment and all canonical physics.

**Minimal production patch plan (NOT applied — pending approval):**
1. In `nearestSeg2D` (and the device `matBindExplicit` nearest-seg loop): tighten the foot-interior test to half-open
   `foot ∈ [−half, half)` (drop/shrink the `+0.02` overlap to ε), so each world point maps to exactly one segment and
   `bindArc ∈ [0, segLength)`. This is the load-bearing change (fixes the handoff the margin masked).
2. Set the g7 margin `bindP[10]` (and the hard-coded `margin=0.05` literals) to a machine-ε (e.g. `1e-6 µm`), or drop
   g7 to `bindArc ∈ [0, segLength)` given (1). Update ALL sites listed in A1 identically (CPU + GPU + mat) so the
   decision stays bit-for-bit shared.
3. Re-validate: `JointMarginProbe` (clean 5→6 handoff, no dead-zone), the mat one-step CPU/GPU bind identity (`0/N`
   mismatches), and the §8 density panel + §11 dt cross-check unchanged in shape (recruitment ↑, plateau preserved,
   quality flat).

`CANONICAL DEFAULT CHANGE: NEEDS MORE WORK` — the *analysis* endorses removal, but the safe patch requires the
**ownership change (step 1)**, not merely zeroing the margin (which alone leaves the joint dead-zone). Adopt only after
step-1 + re-validation; do not zero `bindP[10]` in isolation.

## IMPLEMENTATION + RE-VALIDATION (2026-07-18) — half-open ownership fix landed (opt-in, default byte-identical)
Implemented in `TwoBodyBeamAnalyticGpu.matBindExplicit` (the explicit-s2-l40 CPU-runner + GPU path) as a
**negative-margin sentinel**: `bindP[10] < 0` ⇒ half-open + **clamped-closest-point** nearest-seg (`footC =
clamp(foot,−half,half)`, distance to the clamped point) + `bindArc = footC + half ∈ [0,segLength]` + g7 with `ε=|margin|`.
`margin ≥ 0` ⇒ the exact legacy path (BYTE-IDENTICAL). Mirrored in `ExplicitBindDiagHarness.evalGates` + `-halfopen`
flag (sets `bindP[10]=−1e-6`) + `JointMarginProbe` (side-by-side sweep). For interior points `footC=foot` ⇒ identical;
differs only for beyond-end/joint points (correct handoff). **`nearestSeg2D`/`gate2D` (laser-trap/canongliding) and
`MatSoaSlice` were NOT changed — a full canonical rollout must apply the same sentinel there; this study lands +
validates it on the explicit-s2-l40 binding path (`matBindExplicit`).**

| validation | result |
|--|--|
| **Legacy byte-identical** (regression) | `ExplicitCompleteMatHarness -traj` §7 Δnode=1.3e-8 / §8 maxΔ=4.9e-8 PASS; `-gliding` §8 CPU/GPU bind-identity **mism=0**, §10 smoke reproduces the exact pre-change numbers (binds=9, firstDiv=t178, invalid=0) |
| **Half-open handoff** (`JointMarginProbe`) | clean seg 5→6 exactly at the joint; `bindArc` 0.155→(joint)→0.000→0.020 (in-range both sides); **g7ε PASSES on both sides** of the joint (only the measure-zero exact-joint point fails); no 50 nm dead-zone |
| **CPU≡GPU (half-open)** | glide d400 s101 **bit-identical** on both runners: v=−2.0328 µm/s, meanBound=2.141, binds=39, continuity=0.8664 (the clamped/half-open logic lowers identically on PTX) |
| **Recruitment (d700 s101)** | bindRate default 2.46 → margin=0 3.89 → **half-open 4.13**; meanBound 3.62 → 5.30 → **6.03**; the joint dead-zone closes on top of the margin removal (`loo_inseg` 1454→1198, the residual = correct off-filament-end rejection) |
| **Quality / stability** | onset F8 p99 flat (7.12 pN), 0 immediate-detach, **invalid=0**; GPU lowers + runs stably |

**Adoption status:** the fix is present + validated but **opt-in** (canonical default unchanged, byte-identical). Making
half-open the DEFAULT = flip the sentinel default + roll the same change into `nearestSeg2D`/`gate2D`/`MatSoaSlice` +
re-run the §8/§11 panels — a separate sanctioned step. `CANONICAL DEFAULT CHANGE:` still `DO NOT APPROVE` autonomously;
the fix is ready for jba's go.

## Deliverables index + commands (deliverable 15)
```
./scripts/build.sh
./scripts/run_binddiag.sh -mode contract          # bindP contract (incl. margin)
java … softbox.JointMarginProbe                    # Part B/C collinear sweep + exact-joint + bond-force
java … softbox.JointMarginProbe -bend 5|15|30      # Part C2 bent-joint variants
git log -S "2*half-margin" -- softbox/TwoBodyConverterMotor.java   # Part A provenance
```
Reports: `RUN_LOGS/binddiag/JOINT_MARGIN_PROBE*.md`. **New files:** `softbox/JointMarginProbe.java`. No shared system /
production file modified; no canonical default changed.

## Final status block
- `MARGIN ORIGIN:` commit **e17b5a4** (2026-07-14, two-body-arc squash), hard-coded `margin=0.05` labeled "interior
  material coordinate"; unique to the two-body arc, NOT in v1 / inc-4a.
- `ORIGINAL RATIONALE:` endpoint/ownership disambiguation (keep bindArc interior to one segment); the 50 nm VALUE is
  an underived round buffer.
- `RATIONALE STILL VALID: NO` — disambiguation is real but needs ε, not 50 nm; the margin also masks an ownership-handoff
  gap and is segmentation-catastrophic.
- `EXACT-JOINT OWNERSHIP:` deterministic first-min today (CPU/GPU identical); should be formalized as half-open
  `foot∈[−half,half)` ⇒ exactly one owner.
- `DUPLICATE CANDIDATES:` none bind twice (argmin; one owner per point) — 2 foot-interior candidates at a joint, 1 chosen.
- `FORCE/TORQUE CONTINUITY:` F8 force CONTINUOUS across the joint (world-site based); tangent torque a separate,
  near-collinear-small, pre-existing segmentation effect the margin does not guard.
- `SEGMENTATION INVARIANCE:` only margin=0 is invariant (100 % at all discretizations); 0.05 µm ⇒ impossible at ≥2×.
- `ZERO-MARGIN SAFETY:` SAFE **with** the half-open ownership fix; UNSAFE-ish alone (leaves an un-handed-off joint
  dead-zone). No duplicates, no discontinuity, no CPU/GPU split, no instability.
- `MINIMUM NECESSARY TOLERANCE:` machine-scale ε (~1e-6 µm) on the arc — no physical buffer.
- `RECOMMENDED IMPLEMENTATION:` half-open ownership + machine-ε (drop the 50 nm exclusion).
- `CANONICAL DEFAULT CHANGE: NEEDS MORE WORK` — endorse removal, but land the ownership change first; do not zero
  `bindP[10]` in isolation. Do not modify production defaults until approved.
```
