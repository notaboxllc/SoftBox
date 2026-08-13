# χ-aware capture geometry + site-normal orientation diagnosis

> **SUPERSEDED IN PART, 2026-08-13 — the target relation is now SPECIFIED, and this report used the WRONG
> VECTOR for it.** `docs/motor/SITE_NORMAL_HEAD_BINDING.md` establishes the canonical bound pose:
> **`xHeadHat = −n_site`**, where `xHeadHat` is the head's own local **+x** axis (its ellipsoid LONG axis).
> `xHeadHat` is **NEITHER `eBind` NOR `−eBind`**: `eBind = normalize(xF8 − xH)` is the direction of the
> material point `r_F8 = (+3.5, +1.5) nm`, which sits a FIXED **δ = 23.19859°** off the head's long axis
> (`δ = atan2(rF8y, rF8x)`, verified constant to 2.3e−12° over a full (ψ, χ) sweep). Every angle in §3, §4 and
> §5 below is measured on `eBind`, so it is off the head axis by that fixed 23.2°, and §3(a)'s conclusion
> *"neither +n_site nor −n_site is the model's target … the question has no good answer"* is **withdrawn**:
> the question now has an answer, it is **−n_site**, and it is measured on `xHeadHat`.
>
> **What still stands, unchanged:** §1 (the capture path was already χ-aware — the retraction below), §2
> (`n_site` is exactly the outward radial material normal, carried by the filament), §3(b) and §5 CASE C (the
> mismatch is inherited from the base-frame native rest orientation, which has no relation to a site normal),
> §4's rarity measurement as a statement about `eBind`, and §6's option 1 — which is what was implemented.

**STATUS: DIAGNOSTIC, COMPLETE. Two results, one of them a RETRACTION.**

**(1) There was nothing to repair in Phase 1.** Every production capture kernel *already* reads the exact
χ-aware head the solver moves, and none of them computes `eBind` at all. **The claim that the capture path
"recomputes the head pose from (φ, ψ) and ignores χ" — asserted in `RESTORED_3D_HEAD_TILT_DOF.md` §10.5 and
repeated as the headline of `POST_HEAD_FREEDOM_VALIDATION.md` §3/§4 — is WRONG, and this report retracts it.**

**(2) The ~54° orientation mismatch is CASE C.** It is inherited from the motor's own native rest orientation,
which is defined in the motor's base frame and has no relationship to a site normal: during spatial encounters
`eBind_rest` itself sits ~45–51° from `n_site`, and the head sits only ~21° from `eBind_rest`. `n_site` is
provably correct (Phase 5, exact), and the mismatch is not a sign error.

- **Worktree:** `/home/jba/Code/SoftBox` · **Branch:** `gpu-mat-bottlenecks-explicit-singlehead`
- **Date:** 2026-08-13 · **Raw:** `RUN_LOGS/motor_audit/chi_aware_capture_orientation/`
- **Runner:** CPU sequential runner; **no GPU work launched**.
- **Reproduce:**
  ```bash
  cd ~/Code/SoftBox && ./scripts/build.sh
  TDIR=$TORNADOVM_HOME/share/java/tornado
  java --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.ChiAwareCaptureAudit -all
  #   passes: -parity (1/2)  -nsite (5)  -ebind (6/7)  -cond (8/9)
  ```

**Nothing was changed.** No capture decision, gate, tolerance, rest pose, stiffness, chemistry or steric was
touched. No code needed to change, which is itself the Phase-1 result.

---

## 1. RETRACTION — the capture path was already χ-aware

Source audit of every production capture kernel, confirmed numerically:

| kernel | reads head position from | reads `q` for | χ-aware? |
|---|---|---|---|
| `siteGateA` (spatial: g0 dist, g4 preload, g6 height, g8 accessibility) | **`outGeom` only** (2 reads, **0** `q` reads) | — | **YES** |
| `matBindExplicit` (the non-site-aware twin) | **`outGeom`** (2 reads) | the angle gates only | **YES** |
| `siteCommitB` (motor-side: g1 ψ, g2 φ, g3 θ, g5 energy) | **nothing** — computes no head geometry | ψ, φ, θ_S, ψ_actin | **N/A** |

and `stepGlidingCPU` writes `outGeom` with `matBeamGeomTilt` whenever the tilt is on. The capture kernels and
the solver therefore read **the same buffer**.

Numerical proof over 200 randomised states × 12 motors (φ, ψ, χ and beam deformation all randomised):

```
max |xF8_capture - xF8_solver| = 0.000e+00 um
max |xH_capture  - xH_solver | = 0.000e+00 um
chi = 0 : max |matBeamGeomTilt - matBeamGeom| over all 9 components = 0.000e+00 um  -> BYTE-IDENTICAL
```

**What the earlier report actually measured.** `POST_HEAD_FREEDOM_VALIDATION.md` §3 compared `outGeom` against
a *fresh* `matBeamGeom` call with χ = 0 and labelled the latter "the geometry the capture path actually uses".
It is not; nothing uses it. The quantities (mean 4.97 nm, 39.2°) are real and remain interesting — they are the
**size of the χ degree of freedom itself**, i.e. how far the 3-D head departs from its planar projection — but
they are **not** an inconsistency in the capture path, and the sentence "the capture path is judging a head
~5 nm and ~39° away from the head the mechanics are moving" is **withdrawn**.

**And `eBind` is not in the capture path at all.** The production orientation gate is `|ψ − ψ_actin| < 25°`
(plus φ, θ and an energy budget) on the *converter coordinates*. It never forms the head's facing direction.
So "make capture use the χ-aware eBind" has no target: there is no eBind to correct. This is the
actin-blindness already on record, stated precisely.

---

## 2. PHASE 5 — `n_site` is exactly what it claims to be (CASE A EXCLUDED)

12 consecutive `every4` sites, `phase5_nsite.tsv`:

| check | result |
|---|---|
| `\|n_site\|` = 1 | **exact** (max deviation 0.00e+00) |
| `n_site · r̂_outward` | **1.000000000** at every site — it *is* the outward radial normal |
| site distance from the filament axis | **3.500 nm** = `R_actin` exactly — sites lie on the surface |
| azimuth advance per `every4` site | **+54.000°** (expected +54.00°) |
| rigid filament rotation | `\|n_site(R·fil) − R·n_site\|` = **3.19e−08** — carried by the filament |

**`n_site` is correct. There is no sign inversion and no convention error.** CASE A is excluded.

---

## 3. PHASE 6/7 — where the head actually sits, and what it is aiming at

`eBind = normalize(xF8 − xH)` (repository definition, `ChiralSiteSystem:41`) — the head long axis from the
head centre toward the F8 bond point. Measured on a natural capture (`phase67_ebind.tsv`):

| | 100 frames before capture | **at capture** | bound window |
|---|---:|---:|---:|
| ∠(eBind, **+**n_site) | 68.03° | **61.04°** | 76.96° |
| ∠(eBind, **−**n_site) | 111.97° | 118.96° | 103.04° |
| **∠(eBind_rest, n_site)** | **45.51°** | **51.20°** | 30.25° |
| ∠(eBind, eBind_rest) | — | **20.78°** | — |
| head-centre side of the site plane | −1.399 nm | **−1.982 nm** | −4.701 nm |

Two things follow, and they are the core of this report.

**(a) Neither +n_site nor −n_site is the model's target.** *(WITHDRAWN 2026-08-13 — see the banner. The
target is −n_site, and it is measured on `xHeadHat`, not on `eBind`.)* 61° from the outward normal, 119° from the inward
one. A head binding face-on from outside would give ~180°; a head whose axis pointed straight out would give
~0°. The model does neither, so **CASE B in its simple form is excluded**: the eBind *definition* is confirmed
and unambiguous, but the question "should it be +n_site or −n_site?" has no good answer because **the model's
rest orientation was never defined against a site normal in the first place**.

**(b) The mismatch is inherited from the rest pose, not from a failure to explore.** At capture the head is
only **20.8°** from its own native rest direction, and that rest direction is itself **51.2°** from `n_site`.
The head is doing exactly what its potential asks; the potential is aiming somewhere unrelated to the site.

**Provenance, and why this is expected.** `eBind_rest` is calibrated from `ψ_actin`, the historical
stereospecific head orientation — a **base-frame angle** that predates discrete helical sites by ten days
(`3769ff6`, 2026-07-24, added the sites; `ψ_actin` is inherited from the two-body arc). There is no mechanism
by which a base-frame rest angle would align with a per-site radial normal, and none was ever added.

**Head-centre side (a genuine geometric oddity, reported not fixed).** The head centre sits **inside** the
site's tangent plane throughout — −1.4 nm before capture, −2.0 nm at capture, −4.7 nm bound — i.e. on the
filament side of the site. That is the same fact as the steric overlap measured in
`POST_HEAD_FREEDOM_VALIDATION.md` §2 (bound head inside the actin 94 % of the time), seen from the site's
frame. It is why `eBind` leans toward **+**n_site rather than −n_site: the F8 tip is further from the axis than
the head centre. Head/actin sterics were **not** implemented here, as instructed; this is noted because the
orientation result and the overlap are geometrically the same defect.

---

## 4. PHASE 8/9 — conditional on TRUE spatial reach

116 814 detached steps; **559 (0.479 %) had the TRUE χ-aware xF8 within 3 nm of the nearest site.**
Raw: `phase89_conditional.tsv`.

> **Instrument note.** The first attempt at this comparison produced two identical arms: `scene()` called
> `resetChiral()`, which resets `K_DET_PNNM` to 5.0, so the k_det = 10 arm silently ran at 5. Fixed (the wanted
> value is now captured before the reset and restored before `packExMat` bakes it into `gateP`) and **guarded**
> — the probe now asserts the k_det actually in effect matches the request. Both arms below are real.

**Conditional distribution of ∠(eBind, n_site) given TRUE spatial reach:**

| statistic | **k_det = 5** | **k_det = 10** |
|---|---:|---:|
| detached steps | 116 814 | 118 646 |
| with xF8 within 3 nm | 559 (**0.479 %**) | 714 (**0.602 %**) |
| mean / median | **82.75° / 83.75°** | **65.55° / 60.27°** |
| p10 / p25 / p50 / p75 / p90 | 49.3 / 59.4 / 83.8 / 100.9 / 118.8° | 42.3 / 50.7 / 60.3 / 78.0 / 99.0° |
| ≤15° | 1.07 % | 1.12 % |
| **≤25° (the proposed gate)** | **2.68 %** | **1.54 %** |
| ≤35° | 3.40 % | 5.18 % |
| ≤45° | 6.44 % | 14.15 % |
| ≤60° | 26.65 % | 49.30 % |
| head centre INSIDE the site plane | 63.1 % | **80.0 %** |
| ∠(eBind_rest, n_site) mean / median | 47.86° / 45.96° | 50.97° / 49.30° |
| …of which within 25° | 0.89 % | 0.70 % |

**THE k_det COMPARISON IS THE SHARPEST EVIDENCE FOR CASE C IN THIS AUDIT.** Doubling the detached restoring
stiffness holds the head **closer to its own rest pose** — and because that rest pose is ~48–51° from `n_site`,
the whole distribution concentrates there: ≤60° nearly doubles (26.7 % → **49.3 %**), ≤45° more than doubles
(6.4 % → **14.2 %**)… **while ≤25° gets WORSE (2.68 % → 1.54 %).** Tightening the potential does not improve
site alignment; it pins the head more firmly at the *wrong* angle. No tolerance change, and no tuning of
`k_det`, can convert a rest pose that is ~50° off into one that satisfies a 25° site-normal gate.

**A selection effect worth naming.** Conditioned on spatial reach alone the mean is **82.8°**, but at actual
production captures it is **53.9°** (`POST_HEAD_FREEDOM_VALIDATION.md` §5). The production orientation gate
(`|ψ − ψ_actin| < 25°`) selects poses near the rest orientation, and the rest orientation is ~48° from n_site —
so today's gate *incidentally* improves site-normal alignment from ~83° to ~54°, without ever referring to a
site. It is a weak, accidental proxy for the thing the site-normal law would gate directly.

**PHASE 9 — by site azimuth.** Only two azimuth bins are ever reached:

| azimuth bin | n | mean d (nm) | mean ∠(eBind,n_site) | best | ≤25° |
|---|---:|---:|---:|---:|---:|
| side (0–60°) | 142 | 2.303 | 98.58° | **5.07°** | **10.56 %** |
| side (60–120°) | 417 | 2.246 | 77.36° | 33.84° | 0.00 % |
| lower / upper (±120–180°) | **0** | — | — | — | — |

**Two findings.** (i) The motor never reaches lower or upper sites at all in this geometry — every encounter is
on the filament's side, which is a reach restriction (a CASE-D contribution). (ii) Azimuth matters strongly:
the 0–60° bin achieves 10.6 % within 25° and a best of **5.07°**, while the 60–120° bin never gets closer than
33.8°. **So binding-compatible orientations do occur naturally — they are simply rare and confined to one
azimuth band.**

---

## 5. CASE CLASSIFICATION

| case | verdict |
|---|---|
| **A** `n_site` convention/sign wrong | **EXCLUDED** — Phase 5 exact on all five checks |
| **B** `eBind` convention misunderstood | **EXCLUDED as a sign error.** The definition is confirmed; but neither ±n_site is the target, because the target was never a site normal |
| **C** native detached rest orientation systematically incompatible with the site's stereospecific pose | **THIS IS THE CASE.** `eBind_rest` is 45–51° from `n_site` during encounters; the head sits 21° from `eBind_rest` |
| **D** filament height / reach geometry | **CONTRIBUTING** — the head centre is inside the site plane on **63 %** of reachable steps, and **only side azimuths (0–120°) are ever reached**; lower and upper sites get zero encounters. Not separated by a height sweep (Phase 10 not run) |
| **E** 25° simply narrower than natural | **CONTRIBUTING, but NOT the lever** — only 2.68 % (k_det 5) / 1.54 % (k_det 10) of spatially reachable poses are within 25°, and tightening k_det makes it *worse*, so the tolerance is not what stands between the model and site-normal binding |
| **F** combination | **The honest answer is C, with D and E as contributors.** |

**Root cause in one sentence:** the motor's rest orientation is defined in its own base frame and has no
relationship to the helical site normal, so a spatially reachable head is ~45–51° off before thermal motion is
even considered — and no amount of χ freedom fixes that, because χ lets the head *reach* orientations it could
not before but does not change what the potential *aims at*.

---

## 6. What this means for the binding law (reported, NOT actioned)

The decision is **not** "loosen 25°". Three coherent options, none taken here:

1. **Retarget the rest/bound orientation to the site frame** — i.e. make `ψ_actin` (or the bound branch's
   target) a function of the candidate site's normal instead of the base frame. This is the change the
   site-normal law was always going to require; §3 shows it is the *only* one that addresses the root cause.
2. **Define the intended bound geometry explicitly.** Before any gate can be written, the model needs a stated
   answer to "what angle should eBind make to n_site in a correctly bound head?" — the historical sphere-head
   stereospecific pose is the natural oracle and was not consulted here.
3. **Resolve the head-centre-inside-the-filament geometry**, since it is what tilts eBind toward +n_site. That
   is the steric coarse-graining defect, deferred by instruction.

**Not done / limitations:** Phase 3 (re-run capture statistics after the χ fix) is **moot** — there was no fix
to apply, so the existing statistics already describe the χ-aware path. Phase 10 (filament-height sweep) was
not run. Phase 11 viewer exports were not produced in this pass; the existing
`threejs_posthead_{sterics,eventC}` runs already show a real capture with the 25° cone and `n_site`.
