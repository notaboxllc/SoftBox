# Post-head-freedom validation — S2→lever coupling, head/actin sterics, and a SHADOW site-normal capture gate

> **§5's shadow gate measured the WRONG VECTOR, 2026-08-13.** It scored `angle(eBind, n_site)` against a 25°
> tolerance. The canonical law (`docs/motor/SITE_NORMAL_HEAD_BINDING.md`) gates
> `angle(xHeadHat, −n_site) ≤ 25°`, where `xHeadHat` is the head-local **+x** axis — a FIXED **23.19859°**
> from `eBind`, and referenced to the **INWARD** normal, not the outward one. §5's "0 of 20 captures qualify"
> therefore describes a different quantity; the corrected census is in the new report. **§1, §2 and §6 stand
> unchanged.** §2's overlap measurement in particular remains the reference number the canonical orientation
> is compared against (bound mean −3.09 nm, worst −6.54 nm, inside the actin 94.3 % of the time), and §2's
> ellipsoid was drawn with its long axis along `eBind` — also now corrected.

**STATUS: DIAGNOSTIC, COMPLETE. Three questions answered. (A) The repaired S2→lever junction is mechanically
sound. (B) The head/actin overlap is REAL — the bound head is inside the actin cylinder 94 % of the time. (C)
The true χ-aware head does NOT reach helical sites in orientations the proposed 25° site-normal gate would
accept: 0 of 20 production captures qualify, and binding-compatible poses occur ~0.011 % of the time. NOTHING
was changed — no capture decision, force, torque, threshold, rate or parameter.**

- **Worktree:** `/home/jba/Code/SoftBox` · **Branch:** `gpu-mat-bottlenecks-explicit-singlehead`
- **Date:** 2026-08-13 · **Raw:** `RUN_LOGS/motor_audit/post_head_freedom_validation/`
- **Predecessors:** `docs/motor/RESTORED_3D_HEAD_TILT_DOF.md` (§13–§16, the mechanics repair) ·
  `docs/motor/MYOSIN_HEAD_ORIENTATION_DOF_HISTORY.md`
- **Runner: the CPU sequential runner throughout** — plain-Java kernel calls over the host SoA arrays, no
  TaskGraph, no device transfer. Single-motor deterministic assay class per
  `docs/CPU_GPU_VALIDATION_POLICY.md`; **no GPU work was launched**, so the mandatory GPU crash-monitoring path
  was not entered.
- **Reproduce:**
  ```bash
  cd ~/Code/SoftBox && ./scripts/build.sh
  TDIR=$TORNADOVM_HOME/share/java/tornado
  java --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.PostHeadFreedomProbe -all
  #   passes: -lever  -sterics  -shadow    knobs: -lever-steps N  -shadow-steps N  -events N  -seed N  -out DIR
  ```

---

## 0. What "diagnostic" means here, precisely

The shadow gate is evaluated **after** the production step has already decided, from state that step wrote, and
is **never fed back**. The steric test measures a clearance and applies **no force**. No site steering, no
pre-binding torque, no candidate-derived orientation potential was added. Chemistry, `xCatch`, `k_bind`,
`k_det`, S2 EI, the lever-joint stiffness, the filament geometry and every capture threshold are untouched.

**Physics state** — the REPAIRED default mechanics with the validated 3-D head ON:

| | |
|---|---|
| F8 virtual-work axis | `econv` (repaired) |
| S2→lever terminal bending joint | ON — the beam's own `kbend` = EI/l₀ = 7.2000e−20 N·m/rad² |
| head orientation | χ **dynamic**, `k_det` = 5 pN·nm/rad², weak live neck-frame detached rest |
| `RAND_BASE_AZ` / converter skew | off / off |

Scene: the canonical Path-B one-filament / one-bindable-motor construction the single-motor movie gate
validated — sparse `every4` lattice, filament-global helical phase, site-aware capture, production
dt = 2.5 µs. The filament is frozen (the one visualization fixture); the motor sees the real filament, real
sites, real bond.

---

## 1. TEST 1 — Does S2→lever moment transfer look physical? YES

One motor, detached, **200 000 steps (500 ms)**, every timestep evaluated.
Raw: `lever_joint/lever_joint_trace.tsv`, `lever_joint/lever_joint_stats.tsv`.

| quantity | value | reading |
|---|---:|---|
| mean θ_joint | **81.825°** (rest 81.732°) | sits on its rest angle to **0.093°** |
| SD(θ_joint) | **10.746°** | **below** the joint's own thermal amplitude `sqrt(kT/kbend)` = 13.70° |
| max single-timestep \|Δθ_joint\| | 38.925° (3.62 SD) at step 110 396 | one step in 200 000 |
| single-timestep jumps > 3 × thermal SD (41.10°) | **0 of 199 999** | no non-physical jumps |
| autocorrelation | lag-10 steps −0.0024, lag-100 +0.0012; τ₁/ₑ ≈ 25 µs | bounded, decorrelating, **no drift** |
| mean \|θ_joint − θ₀\| | **8.594°** | vs mean **interior** beam bend **12.397°** |

**The terminal joint is LESS strained than the beam's own interior joints.** There is no sharp or
non-physical kink at the terminal node: the S2 bends smoothly and the lever rides it. θ_joint is thermally
bounded about its rest angle, and the lever does not independently spin — the pre-repair free rotor gave
SD(ψ) = 976° for the same coordinate pair (`RESTORED_3D_HEAD_TILT_DOF.md` §14.5).

> **One statistic in the probe's own output is INVALID and is not used.** `corr(angle(sHat), angle(uB))`
> (printed as −0.3095) is a Pearson correlation of `atan2` angles. φ wanders over hundreds of degrees, so both
> series wrap at ±180° and the correlation is meaningless. The per-increment correlation (+0.1348) is also the
> wrong statistic — per-step increments are dominated by each body's independent thermal kick. **The valid
> tracking evidence is the bounded θ_joint above:** a free hinge has no restoring force at all.

---

## 2. TEST 2 — Does the head body penetrate the actin cylinder? YES, REALLY

Raw: `head_actin_sterics/clearance_trace.tsv` (800 frames spanning detached approach → capture → bound →
release). **No steric force was implemented.**

**Method.** The head is the model's **ellipsoid**, `A_SEMI` = 4.5 × 2.75 × 2.25 nm, long axis along eBind. Its
transverse axes are **not roll-registered** in this model (`REG_K` = 0), so the larger transverse semi-axis is
used — the conservative choice. Actin radius 3.5 nm. Clearance is measured radially, which is exact because
the closest point on a cylinder to an exterior point lies along the radial direction:
`clearance = d_axis − R_actin − support_ellipsoid(r̂)`.

| state | frames | mean clearance | min | frames overlapping |
|---|---:|---:|---:|---:|
| **BOUND** | 105 | **−3.086 nm** | −6.251 nm | **99 / 105 = 94.3 %** |
| detached | 695 | +1.760 nm | −6.542 nm | 279 / 695 = **40.1 %** |

Stage by stage: frame −1 **−2.539 nm** · binding frame **−1.498 nm** · first 20 bound frames mean
**−2.035 nm**, min −3.550 nm.

**Deepest penetration −6.542 nm**, at which point the head centre is **0.206 nm from the filament
centreline** — the head is threaded essentially through the axis of the actin. Deepest points: on actin
(0.61273, 0.00339, −0.00086) µm, on head (0.61273, −0.00295, 0.00075) µm. This is a genuine 3-D overlap, not
a camera projection; orbit `threejs_posthead_sterics` to confirm.

**Mechanism, and it is not subtle:** only the F8 *point* is constrained. The head *body* has no steric
interaction with actin anywhere on this path, so a detached head diffuses straight through the filament (40 %
of frames in this near-filament scene) and a bound head sits inside it.

> **Correction to the probe's own console output:** the line "later bound pose (+200..): mean +5.612 nm" is
> **mislabelled**. The bond ends at relative frame 104, so that window is *post-release*, not a later bound
> pose. The table above is authoritative.

---

## 3. TEST 3 — How different is the χ-aware head from its planar projection? LARGE

> **CORRECTION 2026-08-13 — the framing of this section and §4 is WITHDRAWN.** It called the χ = 0
> recomputation "the geometry the capture path actually uses". It is not: `siteGateA` and `matBindExplicit`
> read xF8/xH from `outGeom`, which the solver writes with `matBeamGeomTilt`, so **the capture path was
> already using the exact χ-aware head** (verified to 0.000e+00 in
> `docs/motor/CHI_AWARE_CAPTURE_ORIENTATION_AUDIT.md` §1). The numbers below are correct and remain useful —
> they measure **how large the χ degree of freedom is**, i.e. how far the 3-D head departs from its planar
> projection — but they are **NOT** an inconsistency in the capture path, and the sentence "the capture path
> is judging a head ~5 nm and ~39° away from the head the mechanics are moving" is retracted.

**134 696 DETACHED candidate evaluations.** Bound frames are excluded: a bound motor always carries a site,
and including them swamps the statistic with the post-capture pose. "Legacy" is the geometry the capture path
actually uses — recomputed from (φ, ψ) with χ ignored; "true" is the χ-aware head the solver integrates.

| | mean | max |
|---|---:|---:|
| \|xF8_3D − xF8_legacy\| | **4.972 nm** | 10.676 nm |
| ∠(eBind_3D, eBind_legacy) | **39.21°** | 89.00° |

For scale, the spatial capture gate is **3 nm** — so χ moves the head by more than the gate radius. That is a
statement about the size of the coordinate, **not** about the capture path (see the correction above).

## 4. TEST 4 — How often does ignoring χ change spatial eligibility?

**974 of 134 696 = 0.72 %** of detached evaluations have their `< 3 nm` decision flipped by χ. Small in
aggregate — but capture events are *selected* at the rare moments the legacy geometry happens to be close, so
this rate must not be read as the rate at events (§5 measures that directly).

---

## 5. TEST 5 — Event classification: NOT ONE of 20 captures survives the proposed gate

20 natural production capture events. Each is classified on the **pre-solve** geometry — the state the gate
actually saw — not the post-solve state (see §8). Raw: `shadow_capture/event_table.tsv`.

| class | meaning | count |
|---|---|---:|
| **A** | production accepts, shadow (χ-aware reach **and** ≤25° to n_site) also accepts | **0 (0 %)** |
| **B** | production accepts, shadow rejects — true xF8 too far | **0 (0 %)** |
| **C** | production accepts, shadow rejects — **eBind > 25° from n_site** | **20 (100 %)** |
| **D** | production rejects, shadow *would* accept (steps, not events) | 15 |

∠(eBind_3D, n_site) at production capture: **min 43.63°, mean 53.89°, max 64.33°.**

**The spatial criterion is NOT the obstacle.** The true χ-aware xF8 was inside 3 nm at all 20 events
(0.873–1.995 nm). **The entire disagreement is orientational.**

**Q8 — do binding-compatible poses occur naturally at all?** Yes, but rarely: **15 of 134 696 detached
evaluations = 0.011 %**, roughly 1 step in 9 000.

**Consequence, stated plainly:** adopting a 25° site-normal gate as it stands would reduce capture by about
three orders of magnitude. That is a decision about the tolerance, the head's rest orientation, or the
definition of the site normal — not a small correction.

---

## 6. TEST 7 — No pre-steering

Over the 100 frames before each of the 20 captures:

| | |
|---|---|
| mean trend of ∠(eBind, n_site) | **−0.0292 deg/frame** — essentially flat |
| mean range of ∠(eBind, n_site) | **75.28°** — it explores, it does not funnel |
| monotonically-approaching captures | **0 of 20** |
| candidate-identity switches | 7.25 per 100 frames; max \|jump in angle\| at a switch 71.95° |

No monotonic attraction toward n_site and no funnelling. The large jumps at candidate switches are simply a
*different site* becoming nearest — a relabelling, not a torque. There is no candidate-derived orientation
potential anywhere in the code path. **The head arrives in whatever orientation its own thermal dynamics give
it.**

---

## 7. TEST 8 — What activating the site-normal spring would cost

`k_bind` = 5.1200e−19 N·m/rad², `γ_ψ` = 3.7036e−25 N·m·s/rad. **The spring was NOT activated.**

| pose | U = ½ k_bind θ² | restoring torque |
|---|---:|---:|
| at the 25° gate ceiling | **11.84 kT** | 2.234e−19 N·m |
| at today's mean capture (53.89°) | **55.0 kT** | — |

Gating at ≤25° reduces the initial bound strain **~4.7×** — a real improvement, but **11.8 kT is still a
substantial snap, not a gentle one**.

**And it would not be resolved:** `τ = γ_ψ/k_bind = 0.723 µs` against `dt = 2.5 µs` gives **τ/dt = 0.29**. The
bound orientational relaxation is faster than a single timestep. This is the standing sub-step issue; it is
stated here, not fixed.

---

## 8. Instrumentation caveats — four flaws found and fixed in the probe itself

These are recorded because the first passes produced **wrong** headline numbers, and only the final ones
should be used.

1. **Mis-normalized means.** The χ-delta sums were accumulated over all steps but divided by the candidate
   count, printing the impossible "mean 208 nm, max 10.7 nm".
2. **Classification measured one solver step too late (material).** The capture gate sees the geometry at the
   *start* of the step; classifying on post-solve state measures the head *after* the freshly-formed bond has
   already pulled it, which inflated the "true xF8 too far" class. Events are now classified on the pre-solve
   state.
3. **Wrong population for Tests 3/4.** The evaluations were dominated by **bound** frames (a bound motor
   always carries a site). Tests 3/4 now use detached candidate evaluations only.
4. **Missing nearest-site fallback.** `candInt` is populated only on the steps the kernel records a candidate,
   so a shadow gate keyed to it was evaluated almost nowhere — and the step *before* a capture usually has
   none, producing NaN classifications. Every detached step is now evaluated against the nearest site.

The pre-fix pass reported "50 % class B / 50 % class C" and later "100 % class B"; **both were artifacts**.
The corrected result is 100 % class C.

---

## 9. What is NOT done

1. **No steric force was implemented** — Test 2 is a measurement only.
2. **No capture decision was changed**, and the site-normal law is **not** implemented.
3. **Test 6 is incomplete, by fact and by omission.** Clip (1) — "a production capture the shadow law would
   also accept" — **cannot be produced**: zero class-A events exist. Clip (3) — an event where ignoring χ
   flips the spatial decision — was **not exported**, though 974 such steps exist.
4. **One seed, one motor, one filament.** 20 events is enough to say "none of 20 is within 25°"; it is not an
   ensemble measurement of the capture-rate cost.
5. The 15 class-D steps were counted on post-solve state (a detached head has no bond pulling it, so the bias
   is one thermal step, not a capture transient) — adequate for a rarity estimate, not for a rate.

---

## 10. Viewer trajectories

```bash
cd ~/Code && python3 SoftBox/sim_server.py 8000       # if not already running
#   -> http://localhost:8000/SoftBox/sim_viewer_boa.html    ("Recent" picker, newest first)
```

| run (in the **Recent** picker) | frames | what |
|---|---:|---|
| `threejs_posthead_lever` | 1500 | TEST 1 — detached S2 + lever, one frame per timestep |
| `threejs_posthead_sterics` | 500 (binding at 299) | TEST 2 — orbit the bound head to verify the overlap in 3-D |
| `threejs_posthead_eventC` | 151 (binding at 100) | TEST 5 — a production capture the 25° gate would reject |

> **Viewer runs live at the REPO ROOT (`SoftBox/threejs_*`), not under `RUN_LOGS/`.** `sim_server.py`
> discovers a run folder by walking only `MAX_DEPTH = 4` levels below its root (`~/Code`), so frames buried
> deeper — e.g. `SoftBox/RUN_LOGS/motor_audit/<study>/simviewer_events/<run>`, which is 6 levels — are written
> correctly but **never appear in the Recent picker**. This was hit once and is now enforced by
> `PostHeadFreedomProbe.JS_ROOT`.

`sim_viewer_boa.html` is the project's canonical viewer and is **not forked or modified** (CLAUDE.md), so every
overlay rides its existing channels. Colour is its only per-segment channel (`notADPRatio`, rendered
`rgb(1, a, 0)` with age-colour on, the default):

| colour | what |
|---|---|
| **yellow**, thick | actin filament |
| **orange**, thin chain of 4 | the explicit S2 beam, element by element |
| **orange**, short/fat, pointing down | the surface anchor |
| **red**, short stub on the filament | the site under evaluation (thickens when it becomes the bound site) |
| **dark orange**, 12-ray fan | the **25° acceptance cone** around `n_site` |
| **near-yellow** thin line | the head↔actin closest-approach line (the steric diagnostic) |
| light-blue cylinder | lever / neck (P→C) |
| sphere, nucleotide-coloured | the **head body**, drawn at its true 4.5 nm semi-axis |
| thin ray from the head centre | **eBind** |
| purple sphere | bound site + the F8 bond (bound frames only) |

Untick "age colour" and every segment reverts to uniform magenta — the coding above depends on it being on.

---

## 11. The three completion-rule questions

**A. Do the repaired S2→lever mechanics look sane?** **YES.** Bounded, on-rest, no spin, no terminal kink, no
non-physical jumps in 200 000 steps, and less strained than the beam's own interior joints.

**B. Is head/actin overlap a real steric problem?** **YES.** Bound: inside the actin 94.3 % of the time, mean
−3.09 nm, worst −6.54 nm with the head centre 0.21 nm off the filament axis. Detached: through the filament
40 % of the time. Only the F8 point is constrained; the head body is sterically absent.

**C. Does the true χ-aware head reach helical sites in orientations the 25° gate would accept?** **NO — not as
things stand.** 0 of 20 production captures qualify (43.6–64.3°, mean 53.9°), and qualifying poses occur in
0.011 % of detached steps. The spatial reach is fine; the orientation is not.

**Recommendation — SUPERSEDED 2026-08-13.** The original recommendation ("close the ~39°/~5 nm gap between
the capture geometry and the integrated head") rested on the retracted §3 framing; there was no gap to close.
The follow-up audit (`docs/motor/CHI_AWARE_CAPTURE_ORIENTATION_AUDIT.md`) shows the orientation mismatch is
**CASE C**: the motor's rest orientation is defined in its base frame and has no relationship to a site normal
(`eBind_rest` is itself 45–51° from `n_site` during encounters, and the head sits ~21° from `eBind_rest`).
