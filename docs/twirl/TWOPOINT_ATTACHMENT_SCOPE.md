# Two-point actin attachment as an intrinsically chiral cross-bridge — scope, implementation, and NEGATIVE
> ⚠ **SUPERSEDED BY §8 (2026-09-09).** Sections 3–7 test a ONE-SIDED site pair (n, n−2) whose centroid
> sits 2.70 nm behind the bound site. With the sites STRADDLING (n−2, n+2) two-point attachment glides
> at 0.946× the single-spring control. The conclusion "two-point destroys gliding" is FALSE. See §8.


**Date** 2026-09-03 · **Status: EXPLORED, NOT PURSUED.** Flag `-twopoint` is built, default-off, byte-identical
when off. No canonical default changed. Explored autonomously while jba was away, per instruction.

---

## 1. Why this was worth trying

Every twirl mechanism in this project asserts its handedness through `mirror * eps` — the chirality is an INPUT.
§7.4/§7.4.1 then showed no structural channel supplies eps's magnitude (interface 0.2°, actin protomer 0.33°,
motor-side anchor 1.25°, against a 3–7° requirement).

A two-point attachment would be different. The structure says one motor domain contacts **two protomers on the
same long-pitch strand**, target `n` and ancillary `n−2` (Milligan 1996; Lorenz & Holmes 2010 AC3/AC1; Fujii &
Namba 2017). From our own lattice constants that pair is:

| | |
|---|---|
| axial separation (2 × monoSp) | **5.40 nm** |
| azimuthal separation (−2 × twistPerMon) | **−27.0°** |
| tangential chord at Ractin = 3.5 nm | **1.63 nm** |
| chord length / tilt off-axis | **5.64 nm / 16.8°** |

**So a head bridging n and n−2 is already chiral, with handedness set by ACTIN'S OWN HELIX and no imposed skew.**

### The torque relation
For two anchors with chord **d** and head force **F**, in the filament frame (û axial, n̂ radial, t̂ = û × n̂):

```
M·û  =  −d_t · F_n          (TANGENTIAL chord component × RADIAL force component)
```

Site-normal binding (`xHeadHat = −n_site`) makes F_n large, and d_t = 1.63 nm is free from the helix. At
F_n ~ 1 pN this gives ~1.6e−21 N·m against epsStroke's ~3.2e−21 — **same order, from geometry alone**.

---

## 2. What had to be built (the existing machinery does NOT do this)

`CrossBridgeSystem.bondForcesCanonical` + `MotorStore.bindArc2` already implement a two-point bond, but:
- both sites are on the segment **CENTRELINE** (`centre + arc·uVec`, no radius, no azimuth) ⇒ **zero azimuthal
  separation ⇒ no chirality**. It predates the helical-surface binding.
- it bonds the head's two **ends**, which are `HEAD_LEN` = **20 nm** apart, against a **5.64 nm** site chord.

New kernel `CrossBridgeSystem.bondForcesSurfaceTwoPoint`: the single F8 spring becomes two springs (total
stiffness preserved) from two anchors on the head's tip face to surface sites A = (bindArc, bindAzim) and
B = (bindArc − 2·monoSp, bindAzim − 2·twistPerMon). **F9 and F10 unchanged** — only the attachment topology
differs. Flag `-twopoint`, own params array, default off ⇒ never wired.

---

## 3. Result: THE MOTOR STOPS GLIDING. Three variants, all fail.

400k-step GPU smokes, matched seed, eps = 0 throughout (the point being that chirality should come from geometry):

| variant | travel (µm) | v (µm/s) | **v/base** | avgB | turns/µm | invalid |
|---|---|---|---|---|---|---|
| single-point (baseline) | 0.4966 | 1.079 | **1.00** | 1.019 | −1.001 | 0 |
| 2pt 50/50, foot ‖ yVec | 0.0486 | 0.163 | **0.15** | 0.877 | −2.466 | 0 |
| 2pt 50/50, foot ‖ yVec, MIRROR | 0.0652 | 0.202 | **0.19** | 0.764 | **+1.713** | 0 |
| 2pt 50/50, foot ‖ zVec | −0.0010 | 0.070 | **0.07** | 0.773 | −1.523 | 0 |
| 2pt 50/50, foot ‖ zVec, MIRROR | 0.0569 | 0.147 | **0.14** | 0.857 | −4.881 | 0 |
| 2pt 75/25, foot ‖ yVec | 0.0597 | 0.203 | **0.19** | 0.840 | −0.012 | 0 |
| 2pt 75/25, foot ‖ yVec, MIRROR | −0.0020 | 0.065 | **0.06** | 0.858 | −0.488 | 0 |

**Gliding collapses 5–15× in every variant.** 0 invalid / 0 solverFail throughout — not numerically broken,
mechanically different.

### Diagnosis: the second bond CLAMPS the head's orientation
Two springs 5.64 nm apart give the head a **torsional stiffness that a single point spring does not have**
(k·d²-scale). A single-point head is orientationally free and can reorient as the filament slides past; a
two-point head is clamped and resists that motion, so each bound head becomes a brake rather than a propulsor.

Two attempted fixes, both from a stated hypothesis, both measured and both refuted:
- **Foot axis yVec → zVec.** Hypothesis: F10 drives `head.yVec → seg.yVec` (radial) while the site chord is
  mostly axial, so the foot was held perpendicular to the chord it must span. **Refuted:** zVec was WORSE
  (v/base 0.07 vs 0.15) and lost the reversal.
- **Asymmetric weighting 75/25** (Lorenz & Holmes: primary 1295–1429 Å², ancillary 358–595 Å² ⇒ ancillary is
  22–30 %, so 50/50 over-clamps). Structurally motivated, and **also refuted:** v/base 0.19 / 0.06.

### ⚠ A single mirror reversal that I over-read, recorded as such
The **50/50 yVec** pair showed turns/µm −2.466 → **+1.713** under lattice mirroring, and at the time I called it
"the chirality signature". **It is not reproduced by either other variant** (zVec: −1.523 → −4.881, same sign;
75/25: −0.012 → −0.488, same sign), and it comes from **0.05–0.07 µm of travel at n = 1** — where the slope
estimator is nearly meaningless. On the evidence it is a coin flip, not a signature. Recorded because the
temptation to report it as the headline was real.

---

## 4. Status and what would be needed

**Not pursued.** The stopping criterion was fixed before the runs: a campaign only if gliding recovered to within
~2× of baseline, since a 5–15× velocity penalty makes turns/µm unreadable through the 1/v artifact that has
already caught this project twice (low-ATP; randomised base).

The GEOMETRY argument in §1 still stands and is the only route we have to a **derived** rather than asserted
handedness. What fails is this IMPLEMENTATION — two stiff zero-rest springs to fixed material points. Making it
work would need the second attachment to pin position without pinning orientation, e.g. a much softer ancillary
bond (<<25 %), an ancillary that bears load only in one direction, or a bond that releases and re-forms within an
attachment. Each is a further invented mechanism, which is the thing this exercise was trying to avoid.

**Third instance of the same pattern.** Exp 4E (recruitment vs stroke), the S2 catch-stiffening sweep
(transmission vs glide), and now two-point attachment (orientational pinning vs glide) all hit one trade-off:
**in this motor, anything that stiffens the actin attachment costs gliding.** That is now a robust and repeatedly
measured property, and is probably the more valuable output of this line than any individual mechanism test.

---

## §4 — The F9 "free the head" test: a NULL for a trivial reason (2026-09-04)

**Question asked:** would altering the head↔neck/converter connection to allow reorientation salvage
two-spring gliding? The two-point bond collapses glide to 0.06–0.19× baseline (§3), and the working
hypothesis was that the head is over-constrained — pinned by two springs AND the F9 head-vs-actin
alignment torque AND the site-normal `U_bind`. Removing F9 was the cheapest test of that.

**Implementation.** `bondForcesSurfaceTwoPoint` gained a `keepF9` gate (`xbParams[13]`, default 0),
exposed as `-twopoint-keepf9`. The gate was verified to reach the kernel (resolved buffer prints
`keepF9=0.0` vs `1.0`).

**Result: byte-identical, 60 000 GPU steps.** Turning F9 on or off changes nothing.

**Cause — measured, not inferred.** Three probes, in order:

| probe | result | rules out |
|---|---|---|
| flag reaches kernel? | `keepF9` 0.0 vs 1.0 in the resolved buffer | plumbing |
| forced 1e-18 N·m torque injected at the same site | `rollTurns` +28.7 vs −0.23, `avgBound` 0.51 vs 0.65 | torque channel not consumed |
| `restF9` forced to 45° (cannot coincide with the bound geometry) | still byte-identical | F9 live but sitting exactly at its rest angle |

The answer is **`j1FMT` (`xbParams[2]`, the alignment fracMoveTorq) `= 0.000`** in this harness. The F9
torque is `tm = j1FMT · … ≡ 0` regardless of geometry, rest angle, or nucleotide state. **F9 is dead
code in the site-normal gliding harness** — in the default single-point `bondForcesSurface` exactly as
much as in the two-point path.

**Consequences — one of these retracts a claim made in this document and in the kernel comments.**

1. The "the head is pinned THREE ways (two springs + F9 + kbind)" rationale is **WRONG**. F9 was never
   a constraint here. The head is pinned by the two springs plus the site-normal `U_bind`. Both the
   kernel comment and the `TWO_POINT_KEEP_F9` declaration have been corrected in place.
2. **The original question is UNTESTED, not refuted.** Because removing F9 was a no-op, this experiment
   carries no information about whether allowing head reorientation salvages two-spring gliding. It is
   NOT a third refuted fix, and must not be counted as one.
3. The constraint that would actually have to be relaxed to test the question is the **site-normal
   `U_bind` coupling** (`SiteNormalBindSystem.siteCoupleStep`, `restC`/`snP`), not F9. That experiment
   has not been run.

**Also void:** an earlier NO-F9 smoke reported as a null ran against a **stale binary** (the class file
postdated the run output; the build inside a backgrounded command had not completed before the runs
launched). Its numbers are void, not a result. The gate above is the correct re-run.

**Standing status of the two-point mechanism: unchanged and still negative on its own terms** — glide
0.06–0.19× baseline across both foot axes and both weightings (§3), with the mirror reversal not
reproducible. Nothing in §4 rehabilitates it; §4 only removes a wrong explanation for why it fails.

---

## §5 — Relaxing the UPSTREAM linkage does not rescue two-point gliding (2026-09-04)

**Question (jba).** §4 established that the head is pinned by the two springs + the site-normal `U_bind`,
not by F9. The site-normal channel is **reserved** — it is the candidate channel for a power-stroke
conformational change and must not be relaxed. So: is there a way to relax the *next-in-line* motor
linkage — neck / converter / S2 — that recovers gliding?

**Knobs (data-only, `-eta` / S2-fixture precedent).** Multiplicative scales on the per-motor `params`
slots upstream of the head. `params` is already per-motor planar storage read by both runners ⇒ no kernel
edit, no buffer resize, no TaskGraph change.

| linkage | slot | flag |
|---|---|---|
| converter torsional spring | `params[6] = kconvCode` | `-soft-conv <f>` |
| S2 axial stretch | `params[11] = g4ks` | `-soft-s2a <f>` |
| S2 bending | `params[13] = g4kb` | `-soft-s2b <f>` |

**FDT-safe by construction:** every Brownian amplitude is built from the drag gammas, never from these
stiffnesses, so softening correctly RAISES the equipartition amplitude (`kT/k`) without unbalancing
fluctuation-dissipation. Softer also means a LARGER dt stability margin, never smaller. `f = 1.0` verified
**byte-identical** (gate run, `wall_s` excluded).

**Run-length gate — the first attempt was VOID.** A 6-arm screen at 40 000 steps failed its own positive
control: two-point/baseline came out 0.52× instead of the known 0.06–0.19×. At that length filaments move
only 0.02–0.06 µm with avgBound < 1, so the velocity fit is noise-dominated and the arm-to-arm spread
matches the effect size. Those numbers are discarded, not reported. **At 200 000 steps the control passes**
(baseline 1.049 µm/s ≈ the historical 1.079; two-point 0.222 ⇒ **B/A = 0.212**), and only then were the
treatment arms read.

**Result (200k steps, seed 20260901, factor 0.1).**

| arm | v (µm/s) | vs A | avgBound | fwd (µm) |
|---|---|---|---|---|
| A single-point baseline | 1.049 | 1.000 | 0.88 | 0.241 |
| B two-point | 0.222 | 0.212 | 0.84 | 0.040 |
| C 2pt + soft converter | 0.293 | 0.280 | 0.51 | 0.038 |
| D 2pt + soft S2-bend | 0.281 | 0.268 | 0.86 | 0.049 |
| E 2pt + soft S2-axial | 0.159 | **0.152** | 0.82 | 0.007 |
| F **soft converter ONLY** (confound control) | 0.816 | 0.778 | 0.58 | 0.165 |
| G 2pt + all three soft | 0.350 | 0.334 | 0.59 | 0.068 |

**Softening ladder (all three linkages, two-point ON) — FLAT, not slow:**

| factor | 1.0 | 0.1 | 0.03 | 0.01 |
|---|---|---|---|---|
| v vs A | 0.212 | 0.334 | 0.247 | 0.343 |
| avgBound | 0.84 | 0.59 | 0.45 | 0.30 |

**VERDICT: NO. Relaxing the upstream linkage does not recover gliding.**

1. **The confound is excluded.** Arm **F** — a 10× softer converter with NO two-point bond — glides at
   0.78× baseline. A soft linkage still transmits force. So C/D/G failing is not "floppy linkage can't
   push"; the softening is simply irrelevant to what the two-point bond broke.
2. **Best case recovers ~15% of the B→A gap** and stays ~3× below baseline — outside the n=1 noise floor.
3. **Flat over 100× in stiffness** (0.21–0.34, non-monotonic), while avgBound decays monotonically
   0.84 → 0.30 ⇒ the knob is demonstrably biting; gliding just does not respond.
4. **S2 AXIAL softening makes it WORSE** (0.152×) — consistent with axial S2 stiffness being the channel
   that actually transmits propulsive force.

**Structural reading.** The two-point bond is a **torsional clamp at the actin interface** — two springs
5.6 nm apart pin the head's axis so it cannot reorient as the filament slides. Upstream compliance sits on
the *wrong side* of that clamp: nothing behind the head can grant the head permission to rotate against
actin. The only compliance that would release it is rotational freedom of the head **relative to actin** —
i.e. the site-normal channel, which is reserved. **The constraint and its only release are both in the
reserved channel**, which is why every reachable knob returns null.

**Caveats.** n = 1 per arm: this resolves several-fold effects, not 1.5× ones. The headline (nothing
reaches within 2× of A) is a several-fold statement and is safe; the ordering *among* C/D/G (0.25–0.34) is
NOT resolved and no significance is claimed for it. Single seed, single density, η = 0.10 Pa·s.

**Status.** The two-point mechanism remains a **recorded negative** (§3), and §5 closes the last route to
rescuing it that does not touch the reserved site-normal channel. `-soft-conv` / `-soft-s2a` / `-soft-s2b`
stay in the tree as validated, default-off, byte-identical-at-1.0 diagnostics.

---

## §6 — Loosening the site-normal channel does NOT rescue two-point gliding; §5's clamp reading is REFUTED (2026-09-04)

**Question (jba).** §5 argued the two-point cost is a torsional clamp at the actin interface whose only
release is the reserved site-normal channel. Test it directly: loosen that channel and see if gliding
recovers. If it does, ask what a power-stroke modulation of the same coupling could do for twirl.

**Knob.** `-soft-kbind <f>` scales `params[7] = kbindCode` (512 pN·nm/rad²), the torsional spring holding
`xHeadHat` to `eTarget = −n_site`. Applied at params fill so it reaches BOTH readers (`gateP[0]`, `snP[4]`,
each built from `params[7*N]`). Scales the **bound** stiffness only — `gateP[1]` (`kDet`, detached head)
keeps its own value. `f = 1.0` verified **byte-identical** vs `ctl_B`. DIAGNOSTIC ONLY: at small `f` the
latch no longer specifies bound head orientation, which is a *different* motor, not a better one.

**Single-seed ladder (200k, seed 20260901), paired against matched controls:**

| kbind | two-point | control (no 2pt) | 2pt/control |
|---|---|---|---|
| 1.0 | 0.222 | 1.049 | 0.212 |
| 0.3 | 0.287 | — | — |
| 0.1 | 0.125 | 0.977 | 0.128 |
| 0.03 | 0.509 | 1.171 | 0.434 |

Controls: loosening the latch ALONE barely moves single-point gliding (0.93×, 1.12×) ⇒ **the site-normal
latch is not what limits gliding in the working model.** The two-point ladder is non-monotonic
(0.212 / 0.273 / 0.119 / 0.485), so the ×0.03 point was flagged as a CANDIDATE only and taken to multi-seed.

**Multi-seed confirmation (200k, 4 matched seeds, B vs kbind ×0.03):**

| seed | B | H (×0.03) | H/B |
|---|---|---|---|
| 20260901 | 0.222 | 0.509 | 2.29 |
| 20260902 | 0.046 | 0.126 | 2.74 |
| 20260903 | 0.282 | 0.261 | 0.93 |
| 20260904 | 0.515 | 0.071 | 0.14 |

**paired H − B = −0.025 ± 0.154 µm/s, t = 0.16 ⇒ NULL.** B mean 0.266 ± 0.097 (0.254× baseline);
H mean 0.242 ± 0.098 (0.230× baseline). The ×0.03 single-seed 2.29× was a draw, not an effect.

### §6.1 — RETRACTION: the §5 clamp reading is not supported

§5 concluded *"the two-point bond is a torsional clamp at the actin interface; the only compliance that
would release it is rotational freedom of the head relative to actin — the reserved site-normal channel."*
That is a **falsifiable prediction**: release the clamp, recover the glide. **The prediction fails
(t = 0.16).** The clamp reading is therefore **REFUTED and withdrawn**. §5's *measurements* stand (upstream
linkage softening does not rescue gliding; the F control excludes the floppy-linkage confound); only its
*explanation* is retracted.

**The mechanism of the two-point penalty is NOT identified.** Leading untested candidate: an **axial
tug-of-war** — the two springs anchor to material sites 5.4 nm apart along the filament axis, so they
oppose one another through the stroke with one anchored AHEAD of the head. This is a hypothesis, recorded
to avoid replacing one unverified story with another; it has not been tested.

### §6.2 — LOAD-BEARING: two-point gliding is a HIGH-VARIANCE configuration

| configuration | runs | spread |
|---|---|---|
| two-point | 4 seeds | **0.046 – 0.515 µm/s (11×)** |
| single-point | 3 runs (A + 2 kbind-only controls) | 0.977 – 1.171 (±10%) |

**Consequence — every single-seed two-point number in this document is unreliable, including §3's
0.06–0.19× "collapse" range**, which was single-seed and is the figure this whole line of work has been
treating as established. At n = 4 the two-point mean is **0.254× baseline**, well ABOVE that range. The
qualitative conclusion (two-point costs gliding several-fold) survives; the specific ratios do not.
This also plausibly explains the §3 mirror reversal that failed to reproduce — it was one draw from an
11×-spread distribution.

**Carry-forward rule:** no two-point claim from a single seed. Minimum n = 4 paired, and report the paired
difference, not the ratio of means.

**Status.** Two-point remains a recorded negative. §4 (F9 inert), §5 (upstream softening null) and §6
(site-normal loosening null) close every rescue route attempted; none of them explains WHY, and §6.1
withdraws the explanation previously offered.

---

## §7 — DECOMPOSITION: the cause is ACTIN SITE SEPARATION, and §3–§6 were confounded (2026-09-05)

**Trigger (jba):** *"There must be a simple bottom line as to why the two-spring motor head binding cannot
glide. I'm suspicious of a sign error or other bug."* Correct instinct, wrong target: the kernel is sound,
but the **experimental configuration carried TWO independent defects**, which is why every single-cause
rescue in §5 and §6 returned null.

### §7.1 — Kernel correctness: TWO gates, both EXACT

| gate | configuration | result |
|---|---|---|
| GATE-1 | `foot=0, w=0` — one spring, tip anchor, site A | **reproduces single-point to 4 dp on all 4 seeds** |
| GATE-2 | `foot=0, w=0.5, pair=0` — two springs, tip anchor, SAME site | **reproduces single-point to 4 dp on all 4 seeds** |

Routed through `bondForcesSurfaceTwoPoint`, both degenerate configurations return the working motor
**exactly** (1.0487 / 0.8522 / 0.7661 / 0.5073). Springs sum correctly, reactions and torque bookkeeping are
correct. **THERE IS NO BUG IN THE TWO-POINT KERNEL.** These gates should have existed from the day the
feature was written; their absence is why a confounded setup went unnoticed through three rescue campaigns.

### §7.2 — The decomposition (200k steps, 4 matched seeds, SIGNED velocities)

| configuration | mean v (µm/s) | signs |
|---|---|---|
| single-point (SP) | **+0.794 ± 0.112** | `+ + + +` |
| GATE-1 / GATE-2 (degenerate) | **+0.794 ± 0.112** | `+ + + +` |
| one spring, anchor offset 2.82 nm (`w=0`) | −0.071 ± 0.121 | `+ + − −` |
| two springs, offset, `w=0.25` (the old default) | −0.132 ± 0.165 | `+ + − −` |
| two springs, offset, `w=0.5` (symmetric) | −0.049 ± 0.137 | `+ − − −` |
| two springs, NO offset, sites 2.7 nm apart (`pair=1`) | +0.074 ± 0.131 | `+ + − −` |
| two springs, NO offset, sites 5.5 nm apart (`pair=2`) | −0.117 ± 0.191 | `+ − − −` |

**TWO INDEPENDENT SUFFICIENT CAUSES**, either alone fatal:
1. **The 2.82 nm anchor offset** (my geometric embellishment representing a ~1600 Å² footprint on the head
   face). One spring + offset already fails.
2. **Actin site separation.** Zero offset, two springs, and failure is **already essentially total at ONE
   monomer (2.7 nm)**; `pair=0` (same site) is perfect.

Weighting is IRRELEVANT (w = 0 / 0.25 / 0.5 all ≈ 0 with scattered sign) ⇒ the §5-era "asymmetric weighting
leaves an uncancelled torque" idea is also wrong.

### §7.3 — Leading interpretation (FLAGGED — four prior explanations were refuted)

A single zero-rest spring is a **BALL JOINT**: it fixes position but exerts no torque about the attachment,
so the head pivots freely on actin. Two springs to *separated* sites form a **COUPLE** pinning the head's
orientation to the filament material frame, with stiffness growing with separation — matching the ladder
(0 nm perfect → 2.7 nm fatal → 5.5 nm fatal). **The stroke appears to require the head to pivot on its
actin attachment.** Consistent with the force decomposition: propulsive and dragging magnitudes are
UNCHANGED (faxPos +0.59…+0.76 vs single-point +0.51…+0.65; faxNeg −0.39…−0.90 vs −0.87…−1.00) while the
net collapses from **−0.43 pN to ≈ 0** — the motor is not weakened, it is **DE-RECTIFIED**.

**This is the §5 "torsional clamp" hypothesis, and §6 did NOT refute it.** §6 loosened `kbind`, the
site-normal *orientation latch* (head axis vs `−n_site`) — a DIFFERENT constraint that never touched the
two-spring couple. The §6.1 retraction of the clamp reading was therefore **itself unjustified** and is
hereby withdrawn; the clamp remains the leading (still unproven) explanation.

### §7.4 — STATUS OF §3–§6: confounded, conclusions weakened

Every two-point result before §7 was measured with BOTH defects present. Their conclusions survive only in
the weak form *"two-point AS IMPLEMENTED did not glide"*, NOT as tests of two-point attachment:
- **§3** (0.06–0.19× collapse, mirror reversals): single-seed AND doubly-confounded. Ratios void.
- **§5** (upstream linkage softening null): valid as measurements, but they were attempting to rescue a
  configuration with two independent defects. Uninformative about the mechanism.
- **§6** (site-normal loosening null): same, plus §6.1's retraction is now itself withdrawn (§7.3).
- **The converter-angle shift** (Δθ = −1.90 ± 0.75°, 4/4 seeds): most likely a CONSEQUENCE of the offset
  torque, not an independent finding. Not to be cited as a mechanism.

### §7.5 — What is actually established

1. The two-point kernel is **correct** (two exact gates).
2. Two springs to the **same** site ≡ the working motor, exactly.
3. Site separation of **even one monomer** destroys directional gliding.
4. The motor is **de-rectified**, not weakened: per-bond forces unchanged, net → 0, glide direction becomes
   seed-dependent.
5. Two-point gliding is intrinsically **high-variance / directionally unstable** — report SIGNED velocities
   at n ≥ 4; `abs()` hides the sign flips (an earlier reporting error of mine).

**Carry-forward:** any future two-point work must (a) keep GATE-1/GATE-2 as regression tests, (b) drop the
anchor offset unless independently justified, (c) treat "does the head need to pivot on actin?" as the open
question — testable directly by measuring stroke output vs attachment-couple stiffness.

---

# §8 — RESOLVED: two-point attachment WORKS. §3–§7 tested a one-sided site pair. (2026-09-09)

**The headline conclusion of this entire document — "two-point attachment destroys gliding" — is FALSE as
written.** It glides normally once the two sites straddle the bound site instead of trailing it.

| configuration | mean v (µm/s) | SEM | signs | vs single |
|---|---|---|---|---|
| single spring (control) | +1.149 | 0.243 | `+ + + +` | 1.000 |
| **two springs, sites n−2 and n+2 (STRADDLE)** | **+1.087** | **0.093** | `+ + + +` | **0.946** |
| two springs, sites n and n−2 (one-sided — ALL of §3–§7) | +0.083 | 0.122 | `+ − − +` | 0.072 |

Rigid single-segment filament, ρ=500, η=0.1, 200k steps, 4 matched seeds. The straddled arm is also LESS
variable than the control (SEM 0.093 vs 0.243) and holds MORE bound heads (avgBound higher on 3 of 4 seeds).

## §8.1 Why — the proof that made it findable

For N zero-rest springs of stiffness k/N from head points a_i to sites p_i:

```
F_total = SUM (k/N)(p_i - a_i) = k ( centroid(p) - centroid(a) )
```

and when the head anchors are placed symmetrically about the head tip, F_1 == F_2 exactly, so the head-side
and segment-side torques also reduce to those of that single resultant. **N springs ARE one spring of
stiffness k anchored at the SITE CENTROID.** Nothing about multiplicity matters; only where the centroid sits.

Sites n and n−2 both lie on the SAME side of the bound site, so their centroid sits **2.70 nm rearward**
(arc −2.70, azimuth +13.5°, radius 3.40 nm). A zero-rest spring drives the glide while the head is pointed-side
of its anchor and OPPOSES once it passes: a 2.70 nm rearward anchor is **54 % of a ~5 nm working stroke**, which
moves the propulsion→opposition crossover to before the attachment even begins. Every moment of every
attachment then pushed the filament backward, which is why the filament oscillated instead of gliding
(local windows ran −0.013, −0.041 µm).

Straddling with n−2 and n+2 puts the centroid at **arc 0.00, azimuth 0.0°, radius 3.12 nm** — on the bound
site, with only a 0.38 nm inward radial residue, which is transverse to the glide axis and costs nothing.

## §8.2 What is now void

**§3, §4, §5, §6 and §7 characterise a ONE-SIDED site pair and say so nowhere.** As tests of two-point / multi-
contact attachment they are void. Specifically retracted:

- **§3** — the 0.06–0.19× "collapse" and the non-reproducing mirror reversal.
- **§5** — upstream linkage softening. Valid measurements, but they were trying to rescue a bond whose anchor
  was 2.70 nm out of place.
- **§6** — site-normal loosening. Same.
- **§7** — the whole decomposition, its two exact kernel gates, the anchor-offset/site-separation "two
  independent sufficient causes", and the couple analysis. The GATES remain valid and useful (the kernel is
  correct); the CONCLUSIONS about the mechanism do not.
- The `docs/twirl/HEAD_CONFORMATION_JUSTIFICATION.md` framing of two-point as an excluded structural
  alternative — it is NOT excluded.

**Eight explanations were offered and refuted before the right one:** torsional clamp, converter-angle shift,
misaligned feet, axial cancellation between springs, torsional decoherence, a filament couple, numerical
ringing, and spring frustration. Each was constructed after seeing data rather than predicted before it. The
surviving account was reached from two questions posed by jba — that zero-rest springs require a relaxed state,
and that N springs of k/N must behave like one spring of k — the second of which turns the problem into
"where is the centroid", which is answerable by inspection.

## §8.3 What this reopens — the important part

Two-point attachment was the route to a **DERIVED** rather than ASSERTED handedness: the chirality would come
from the helical geometry of the two sites instead of from an imposed `epsStroke` offset. That route was closed
on the strength of §3–§7 and is now **OPEN**.

The experiment: a straddled multi-contact bond glides normally, so measure whether the helical asymmetry of its
sites produces twirl **on its own, with eps = 0**. Note the straddled pair is not symmetric in azimuth — n−2 sits
at +27.0° and n+2 at −27.0°, but the intervening lattice is chiral — so there is a real question to answer.

New flags: `-twopoint-straddle` (sites at n±pairMon), `-twopoint-alignfeet` (feet along the site chord; a true
zero-energy state, but DYNAMICALLY IDENTICAL to foot=0 because the resultant is unchanged — kept only to
document that).

## §8.4 — Negative control: FOOT placement is irrelevant, SITE placement is everything

A ladder varying only the head-side foot geometry, with the sites left ONE-SIDED (n, n−2):

| foot geometry | residual stretch/spring | outcome |
|---|---|---|
| foot = 0 (both feet on the tip) | 2.82 nm | fails |
| foot = 2.82 nm along hyVec | 0.82 nm | fails |
| feet chord-aligned (true zero-energy state) | 0.00 nm | fails |
| **sites straddled (n−2, n+2), foot untouched** | — | **0.946x control** |

(The ladder was stopped early and its arms are unevenly complete, so its numbers are not quoted as figures;
the qualitative result is unambiguous and matches the algebra.)

This is exactly what `F_total = k(centroid(p) − centroid(a))` predicts: with symmetric head anchors the feet
cancel out of the resultant entirely, so no foot placement — including a genuine zero-energy one — can move
a bond whose SITE centroid is 2.70 nm out of place. Three campaigns varied the foot geometry before the site
geometry was questioned.

## §8.5 — How many contacts does a patch need? (DOF analysis, 2026-09-09)

| springs | DOF constrained (of 6) | character |
|---|---|---|
| 1 | 3 (position) | ball joint — resists NO torque |
| 2 | 5 | HINGE — rotation about the line joining them is FREE |
| **3, non-collinear** | **6** | minimum for a genuine patch |
| 4+ | 6 | redundant: no new DOF, but distributes load and restores symmetry |

**This is not a formality for twirl.** The straddled pair's connecting line is (+10.80, 0.00, −3.18) nm — only
**16.4° from the FILAMENT AXIS** — so `cos(16.4°) = 0.96` of the axial-torque channel is unconstrained. Axial
torque IS twirl. A two-contact bond therefore glides well (0.946x, measured) while being nearly blind to the
torque the twirl experiment is trying to measure.

**⇒ any derived-handedness test needs ≥3 non-collinear contacts.** Two will not transmit the relevant torque.

Third-site placement matters as much as count — perpendicular offset from the n−2/n+2 line:
`n±1` 6.70 nm, `n±3` 7.31 nm (both good); **`n±4` only 1.11 nm — nearly collinear, weak roll constraint.**

**Caveat on large patches.** The real interface is ~1600 Å² on ONE FACE, but consecutive actin monomers are
~166.5° apart, so monomer-indexed sets wrap around the filament fast: an n−2..n+2 5-point set has its centroid
at radius **0.59 nm**, essentially on the axis. Any candidate set must be checked for BOTH centroid position and
angular spread before use.
