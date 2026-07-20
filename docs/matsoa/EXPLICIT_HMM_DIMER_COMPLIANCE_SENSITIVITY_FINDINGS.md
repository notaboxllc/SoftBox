# Explicit HMM dimer — dimer-specific compliance vs the dense-mat joint-gap instability

Does the explicit HMM dimer's dense-mat joint-gap instability (maxGap up to ~670 nm in a minority of seeds,
`EXPLICIT_HMM_DIMER_GLIDING_DIRECTIONALITY_FINDINGS.md` §7/§9) and its timestep sensitivity arise **primarily from
excessive stiffness in the dimer-specific linkage** — the proximal-branch stretch, the proximal-branch bending, or
the fork angular coupling — and is there a **mechanically-plausible softening that removes the pathological
excursions while preserving stroke, inter-head coupling, second-head engagement, unbiased ensemble gliding, and the
single-head model**? The single-head myosin mat shows no analogous large joint-gap excursions; the instability
appears only after adding two proximal branches, the shared fork, two simultaneous actin constraints, and
differential load transfer. CPU-only. Single-head model / `TwoBodyConverterMotor` / `MotorModel` / shared-S2
material unchanged; the study touches only the explicit HMM dimer (`ExplicitHmmDimer` + its gliding harness).

Primary model throughout: **D0** (unbiased), **5.4 nm same-filament occupancy exclusion ON**, random dimer azimuth,
active-cull radius 120 nm, filament ≈1.93 µm, gap 8 nm, density 500 dimers/µm² (main screen), dt = 2.5e-6 s.

---

## 3. Stiffness-path audit (deliverable 1) — traced to the implementation, not assumed

The reference dimer is `ExplicitHmmDimer.build(Ms=3, Ma=1, Mb=1, splay=16°, dt, α=10°, branchEImult=0.25,
branchEAmult=1.0, branchLen=10 nm)` (harness constants `ALPHA=10, BREI=0.25, BREA=1.0, BRANCHLEN=10`). Node layout:
`E(node0, clamped) — 3 shared segments — F(fork, node 3) — 1 branch segment — P_A / P_B (pivots)`. Per-segment rest
lengths: shared `lS = (40−10)/3 = 10 nm`, branch `lB = 10/1 = 10 nm`.

| quantity | expression (in code) | reference value |
|---|---|---|
| **Branch axial stiffness** | `segKs[branch] = EA_SI·branchEAmult / (lB·1e-6)`  (`build`) | `4.2e-9·1.0 / 1e-8 = 0.42 N/m = ` **420 pN/nm** |
| Branch rest length | `segL0[branch] = lB` | **10 nm** |
| Branch axial force (in `nodeForces`) | `f = segKs·(len·1e-6 − l0m)` per branch segment | — |
| **Branch bending stiffness** | `hingeKb[fork] = EI_SI·branchEImult / (lrep·1e-6)` | `7.2e-28·0.25 / 1e-8 = ` **1.8e-20 N·m** per fork hinge |
| **Fork-root angular energy** | `E = ½·kb·θ²`, θ = angle(branch bond, α-rotated preferred dir) (`bendEnergy`, `hingeIsFork`) | rest half-angle ±α = ±10° |
| Emergence bending (shared) | `kbEmg = EI_SI / (lS·1e-6)` = 7.2e-20 N·m | (shared S2 — NOT a dimer-specific term) |

- **`REFERENCE BRANCH AXIAL STIFFNESS: 420 pN/nm`** — traced exactly to `d.segKs[branch]` with `EA_SI = EXP4G_EA_SI
  = 4.2e-9 N` (the frozen paired-coiled-coil EA, as-is), `lB = 10 nm`, `branchEAmult = BREA = 1.0`. **Not** assumed
  from a prior report.
- **`REFERENCE BRANCH EA SOURCE: EXP4G_EA_SI = 4.2e-9 N`** (Adamovic–Mijailović–Karplus 2008 S2-subdomain EA; the
  PAIRED coiled coil as one effective element, shared with the distal S2 material).
- **`REFERENCE BRANCH EI: 0.25·EI_SI = 0.25·7.2e-28 = 1.8e-28 N·m²`** (`branchEImult=0.25` from the fork-relaxation
  study; applied to the two fork hinges).
- **`REFERENCE FORK ANGULAR STIFFNESS: 1.8e-20 N·m`** per fork hinge (= EI·branchEImult / lrep, θ² penalty).
- **`STIFFNESS DOUBLE-COUNTING FOUND: NO`.** Each stretch element contributes once (`nodeForces` loops `seg[]`
  once); each bending hinge once (`bendEnergy` loops `hinge[]` once). The fork→branch **stretch** (a `seg[]`
  element, `segIsBranch=true`) and the fork **angular** hinge (`hingeIsFork=true`) are distinct terms. Both branches
  are symmetric: fork-A hinge rest +α, fork-B hinge rest −α, identical `hingeKb`; the two fork→branch segments have
  identical `segKs`.
- **Fork-root bending shares the branch-interior EI — but there is NO branch interior.** With `Ma=Mb=1` the branch
  has a single segment and therefore **zero interior bending hinges** (the `for(i=1;i<Ma;i++)` branch-hinge loop is
  empty). The **only** branch bending hinges are the two fork hinges. Consequently **branch bending ≡ fork-root
  angular coupling for this geometry**: a `branchEI` multiplier and a `forkK` multiplier scale the *same two
  hinges*. The new `-forkK` control was added (default 1.0, byte-identical) and confirmed **degenerate with
  `-branchEI`** for the reference `Ma=1` topology (identical fixtures — §4). `forkK` becomes independent only for a
  multi-segment branch (`Ma>1`), which the reference dimer does not use.

**Runtime controls (deliverable 2).** `-branchEA m`, `-branchEI m`, `-forkK m` (harness `CFG_EA/CFG_EI/CFG_FORK`),
each default `1.0` and **byte-identical** at 1.0 (verified: mat cell density-500 seed-101 reproduces the pre-change
velRaw −3.553 / meanBound 6.87 / maxGap 4.55 exactly). `-branchEA` scales branch stretch stiffness (`segKs` on the
two fork→branch segments); `-branchEI` scales the two fork-hinge bending stiffnesses (multiplicative on the 0.25
reference); `-forkK` scales the two fork hinges' bending multiplicatively on top of `branchEI`. They affect ONLY
`ExplicitHmmDimer` (the single-head model and `TwoBodyConverterMotor` never call it).

---

## 4. Controlled single-dimer fixtures (deliverable 3) — Brownian OFF, actin FIXED

`./scripts/run_hmm_gliding.sh -fixtures [-branchEA m] [-branchEI m] [-forkK m]`. F1 detached relaxation, F2
one-head-bound, F3 second-head binding transient (primary trigger), F4 double-bound stroke, F5 double-bound
detachment recoil. All isolate the **dimer-internal** transient (fixed actin, no thermal noise).

| setting | F1 collapse | F2 boundF8 (pN) | F2 coupling freeHeadMove (nm) | F2 forkAngle (°) | F3 peakBranchF (pN) | STROKE (nm) |
|---|---|---|---|---|---|---|
| **reference (1/1/1)** | no | 1.81 | 3.07 | 54.4 | 32.0 | 7.98 |
| branchEA 0.3 | no | 1.81 | 3.08 | 54.4 | 5.5 | 7.98 |
| branchEA 0.1 | no | 1.81 | 3.12 | 54.2 | 8.7 | 7.98 |
| branchEA 0.03 | no | 1.80 | 3.24 | 53.7 | 8.6 | 7.98 |
| branchEA 0.01 | no | 1.78 | 3.60 | 52.1 | 7.5 | 7.98 |
| branchEI 0.3 (≡forkK 0.3) | no | 0.65 | 0.94 | 68.6 | 80.4 | 7.94 |
| branchEI 0.1 (≡forkK 0.1) | no | 0.23 | 0.22 | 73.5 | 100.2 | 7.83 |
| branchEI 0.01 (≡forkK 0.01) | no | 0.02 | 0.011 | 75.3 | 110.9 | 7.84 |

Fixture findings:
- **No setting collapses the detached dimer (F1)** — fork angle holds at the 20° rest (=2α), zero branch extension,
  zero preload, at every multiplier. Softening is mechanically safe at rest.
- **Stroke is branch-independent (7.83–7.98 nm at every setting)** — expected, since `branchEA/EI/forkK` touch only
  the beam elements, never the head Cmot (`kF8/kconv`/lever). Confirms the promotion invariant "intrinsic converter
  stroke / static head stroke preserved" structurally *and* empirically.
- **branchEA softening is benign-to-helpful:** it reduces the F3 transient branch force (32 → ~7 pN), *preserves*
  bound-head force (~1.8 pN) and inter-head coupling (freeHeadMove 3.07 → 3.60 nm, if anything slightly stronger).
- **branchEI/forkK softening is HARMFUL:** it collapses the bound-head force (1.81 → 0.02 pN) and the inter-head
  coupling (3.07 → 0.011 nm), opens the fork (54° → 75°), and *raises* the branch axial transient (32 → 111 pN).
  Bending stiffness is what lets a bound head hold its geometry and transmit load through the fork; softening it
  makes the two heads effectively independent — a §7/§8 rejection condition.
- **The static fixtures never exceed ~110 pN branch force / ~1.8 nm gap** even at extreme softening — far below the
  moving-mat's ~1470 pN / ~670 nm. **The large excursions are a dynamic (moving-filament) phenomenon**, not a
  property of the bind/stroke/detach transients in a static frame. (F3/F4/F5 per-step traces:
  `RUN_LOGS/hmm_compliance_sensitivity/fixtures/`.)

---

## 5. Excursion-trigger classification (deliverables 4, 5) — the mat event census

The gap logger (`ExplicitHmmDimerGlidingHarness.run`) records, per step, the max joint gap over active dimers
(→ percentiles + rising-edge excursion counts at 4/10/50/100 nm), the peak branch axial force, the max/mean branch
extension, and — at every **per-dimer** >10 nm rising edge — the **concurrent same-step transition** on that dimer
(second-head bind / power stroke ADPPi→ADP / detachment / else = filament-motion-only). (This is a compact
per-dimer same-step classifier, not a raw 10-before/20-after window dump; it answers the trigger question directly
and is corroborated by the static fixtures.)

```
>10 nm excursion triggers (density 500, D0, 6 seeds × 5000 steps), summed:
  baseline   : 11 excursions | 2nd-bind 0  stroke 0  detach 0  FILAMENT-MOTION 11  (100%)
  branchEA0.1:  9 excursions | 2nd-bind 0  stroke 0  detach 0  FILAMENT-MOTION  9  (100%)
  branchEA0.03: 1 excursion  | 2nd-bind 0  stroke 0  detach 0  FILAMENT-MOTION  1  (100%)
```
**`PRIMARY GAP TRIGGER: FILAMENT-MOTION` — 100% of >10 nm excursions.** None coincide with a second-head bind, a
power stroke, or a detachment on the excursing dimer. Corroborated by the static fixtures (§4): with the actin
FIXED, the bind/stroke/detach transients never exceed ~2 nm gap / ~110 pN — the large excursions require the actin
to be **moving**. Mechanism: a moving filament drags a bound head; the near-rigid 420 pN/nm branch cannot yield, so
a small geometric mismatch is converted to a very large internal branch force (baseline peak **29 171 pN**, branch
extension up to 69 nm on a 10 nm branch) and a transient solver excursion (always recovering — 0 invalid, 0 NaN).
```
FRACTION OF LARGE GAPS NEAR SECOND-HEAD BINDING: 0.00
FRACTION NEAR DOUBLE-BOUND STROKE:               0.00
FRACTION NEAR DOUBLE-BOUND DETACHMENT:           0.00
```

## 6. Stage A — branch axial-stiffness sweep (deliverable 5). density 500, D0, 6 seeds × 5000 steps

```
branchEA | eff.k(pN/nm) | velProd(µm/s) meanB | maxGap  p999  p99  | exc>10/50/100 | peakBrF(pN) maxBrExt(nm) | two    fwd/bwd inv/sf
 1.0 ref |    420        | +1.48±0.76    4.21  | 270.3   6.96  2.75 | 11 / 4 / 3    |   29 171     69.5        | 0.0002 39/33   0/0
 0.3     |    126        | +1.78±1.08    4.32  |1220.6   7.92  2.96 | 10 / 3 / 3    |   28 540    226.5        | 0.0001 37/30   0/0
 0.1     |     42        | +1.54±0.70    4.45  |  68.9   5.45  3.14 |  9 / 2 / 0    |    1 570     37.4        | 0.0002 29/36   0/0
 0.03    |     12.6      | +1.49±0.86    4.46  |  11.8   4.36  2.78 |  1 / 0 / 0    |      103      8.1        | 0.0002 33/41   0/0
 0.01    |      4.2      | +1.98±0.50    4.74  |  14.9   3.84  3.31 |  2 / 0 / 0    |       27      6.3        | 0.0002 41/44   0/0
```
**Primary answer: YES — reducing branch axial stiffness by ~one order of magnitude sharply suppresses the large gap
excursions while preserving gliding and head coupling.** The transition is at branchEA ≈ 0.1→0.03: maxGap collapses
270 → 11.8 nm (**23×**), peak branch force 29 171 → 103 pN (**283×**), the >50/>100 nm excursions vanish, while
**velocity is unchanged** (+1.49 vs +1.48, within 1 %), meanBound *rises* slightly (4.21 → 4.46), two-head fraction
and second-head binding are preserved. The median gap rises modestly (1.02 → 1.43 nm) — the softer branch has a
slightly larger *typical* extension but no catastrophic tail (the healthy trade). **branchEA 0.3 is NOT enough**
(still 1220 nm, seed-dependent); **branchEA 0.03 is the stiffest setting that removes the pathological excursions**
(0.01 is marginally softer with an even lower peak force but is not needed).

## 7. Stage B — branch bending-stiffness sweep (deliverable 6). Same conditions

```
branchEI | velProd(µm/s) meanB | maxGap   p999  | exc>10/50/100 | peakBrF(pN)  maxBrExt(nm) | F2 coupling | F2 boundF8
 1.0 ref | +1.48±0.76    4.21  |  270.3   6.96  | 11 / 4 / 3    |    29 171      69.5        | 3.07 nm     | 1.81 pN
 0.3     | +0.61±0.87    4.26  |   91.4  11.80  | 18 / 3 / 0    |    19 905      47.4        | 0.94 nm     | 0.65 pN
 0.1     | +0.53±0.95    4.50  | 1388.4  11.15  | 21 / 5 / 3    |   182 988     435.7        | 0.22 nm     | 0.23 pN
 0.03    | +0.23±1.37    4.51  | 3816.6   7.99  | 14 / 3 / 2    | 1 602 959    3816.6        | (collapsed) | (collapsed)
 0.01    | +0.61±0.80    4.65  |  980.9   7.14  | 10 / 2 / 1    |   411 976     980.9        | 0.011 nm    | 0.02 pN
```
**REJECTED.** Softening the branch/fork **bending** is *destabilizing* in the moving mat — the opposite of the
static-fixture intuition. It (a) **halves-to-kills the glide velocity** (+1.48 → +0.23…+0.61) and (b) makes the
excursions **far worse** (maxGap up to 3817 nm, peak branch force up to **1.6 million pN**, branch extension to 3817
nm). Bending stiffness is **load-bearing** under dynamic gliding: it holds a bound head's geometry so it can carry
axial load through the fork. Softening it lets the fork flop under filament motion (the F2 fixture already showed
the fork opening 54°→75° and the bound-head force / inter-head coupling collapsing, §4). This is every §7/§8
rejection condition (heads decouple, fork collapses, engagement lost). **No promotable branch-EI candidate exists.**

## 8. Stage C — fork angular-coupling sweep (deliverable 7). Same conditions

**Result: `forkK` is DEGENERATE with `branchEI` for the reference `Ma=Mb=1` geometry** — the two multipliers scale
the *same two fork hinges* (the branch has no interior bending hinge; §3). The forkK sweep is **byte-identical** to
the branchEI sweep (velProd, meanBound, maxGap, peakBrF all match to the last digit; only the echoed multiplier
column differs), so §7's rejection carries over verbatim. A `forkK` distinct from `branchEI` would require a
multi-segment proximal branch (`Ma>1`); the reference dimer has none. **No promotable fork candidate exists.**

## 9. Limited combination screen (deliverable 8). density 500, D0, 6 seeds × 5000 steps

```
combo                       | velProd(µm/s) meanB | maxGap  p999  | exc>10/50/100 | peakBrF(pN) maxBrExt(nm) | inv/sf
 pure branchEA 0.03 (ref)   | +1.49±0.86    4.46  |  11.8   4.36  |  1 / 0 / 0    |     103       8.1        | 0/0
 EA0.3 + EI0.3              | +1.02±0.78    4.40  | 667.9  21.84  | 26 / 6 / 2    |  81 369     645.8        | 0/0
 EA0.1 + EI0.3              | +0.77±0.62    4.59  |  44.9   8.71  | 22 / 0 / 0    |   1 864      44.4        | 0/0
 EA0.1 + EI0.1              | +0.85±1.38    4.87  | 369.3  13.87  | 34 / 7 / 3    |  15 511     369.3        | 0/0
 EA0.03 + EI0.3             | +0.95±0.54    4.67  | 147.6   8.01  | 14 / 3 / 2    |   1 860     147.6        | 0/0
 EI0.3 + forkK0.3 (bend .09)| +0.46±1.29    4.59  | 670.3  12.75  | 18 / 7 / 3    | 281 510     670.3        | 0/0
```
**Distributing compliance across two terms is strictly WORSE than pure branch-axial softening.** Every combo that
adds *any* bending softening (EI<1) re-introduces the fork-flop instability — larger gaps (44.9–670 nm vs 11.8 nm),
larger peak forces (1 864–281 510 pN vs 103 pN) — **and** drops the velocity to +0.46…+1.02 (vs +1.49). The best
combo (EA0.1+EI0.3: maxGap 44.9 nm) is still worse on every axis than branchEA 0.03 alone. **`BEST COMBINATION` =
branchEA 0.03 with NO bending change** — modest distributed compliance does not help; the single branch-axial term
is the correct and sufficient lever.

## 11. Directionality preservation (deliverable 10). D0 vs D2 at density 500, 6 seeds × 5000 steps

```
setting        | D0 velProd     | D2 velProd     | D0 maxGap | D2 maxGap | D0 fwd/bwd | D2 fwd/bwd
 baseline      | +1.48±0.76     | +1.87±0.75     |  270.3    |  270.3    | 39/33      | 52/0
 branchEA 0.03 | +1.49±0.86     | +1.86±0.78     |   11.8    |   11.8    | 33/41      | 40/0
```
**`D0≈D2 AFTER SOFTENING: YES`.** Softening does not change the D0-vs-D2 relationship — D2 (hard rearward veto)
gives the same velocity as D0 within the seed SD for *both* the baseline and the softened dimer (the small D2−D0
offset is present in both and is inside the ±0.8 µm/s seed scatter, exactly the directionality-study Outcome A). D2
removes the backward second binds (bwd 41→0) **without changing maxGap** (11.8→11.8) and without a velocity gain
beyond noise. **`BACKWARD BINDS REMAIN SELF-FILTERING: YES`** — they stay a rare minority whose removal costs
nothing, and softening does not make them long-lived or dominant. The standing biological conclusion — *the ensemble
does not require a directional second-head rule* — is unchanged by the compliance fix.

## 10. Timestep sensitivity WITHOUT substeps (deliverable 9). dt=2.5e-6 vs 1.25e-6, 3 seeds × (5000/10000 steps)

```
setting  | dt(s)   | velProd | meanB | maxGap  p999  | exc>10/50/100 | peakBrF(pN)
 baseline| 2.5e-6  | +1.88   | 4.73  | 270.3   6.96  |  8 / 4 / 3    |   29 171
 baseline| 1.25e-6 | +2.39   | 4.75  |  64.2   3.54  |  6 / 1 / 0    |    1 592     ← gap/force drop 4–18× at dt/2
 EA 0.03 | 2.5e-6  | +2.17   | 4.87  |   8.4   4.36  |  0 / 0 / 0    |       96
 EA 0.03 | 1.25e-6 | +2.45   | 5.46  |   9.3   2.44  |  0 / 0 / 0    |       91     ← gap/force CONVERGED
 EA 0.01 | 2.5e-6  | +2.21   | 4.85  |   4.4   3.84  |  0 / 0 / 0    |       18
 EA 0.01 | 1.25e-6 | +2.25   | 5.15  |1053.0   4.50  |  2 / 2 / 2    |    2 603     ← a rare dt/2 excursion (too soft)
 EI 0.01 | 2.5e-6  | +1.05   | 5.14  | 980.9   7.14  |  6 / 2 / 1    |  411 976
 EI 0.01 | 1.25e-6 | +1.47   | 5.94  | 591.4   2.60  |  4 / 2 / 2    |  197 682     ← still catastrophic at both dt
```
**Most of the dt sensitivity IS the excessive branch stiffness.** At the reference stiffness the gap/force metrics
are strongly dt-dependent (maxGap 270→64, peak branch force 29 171→1 592 pN when dt is halved) — the signature of an
under-resolved stiff spring. **At branchEA 0.03 the gap and force are dt-CONVERGED** (maxGap 8.4→9.3 nm, peak force
96→91 pN — essentially unchanged), and the velocity dt-shift is roughly halved (baseline +27 % → EA0.03 +13 %).
- `DT VELOCITY DIFFERENCE, BASELINE: +27 %` (1.88→2.39). `DT VELOCITY DIFFERENCE, BEST(EA0.03): +13 %` (2.17→2.45).
- `DT GAP DIFFERENCE, BASELINE:` maxGap 270→64 (4.2×), peakBrF 29 171→1 592 (18×) — dt-sensitive.
  `DT GAP DIFFERENCE, BEST(EA0.03):` maxGap 8.4→9.3, peakBrF 96→91 — **dt-insensitive (converged).**
- **branchEA 0.01 is a shade too soft** — a rare dt/2 excursion reappears (maxGap 1053 in one seed); **0.03 is
  dt-stable in both directions**, a second reason to prefer 0.03 over 0.01.
- **The residual +13 % velocity dt-shift at EA 0.03 is the KNOWN explicit cross-bridge under-sampling** (the standing
  substep issue), a *separate* mechanism from the branch-stiffness joint-gap instability. Softening the branch fixes
  the joint-gap excursions and their dt-sensitivity; it does not (and is not meant to) fully converge the absolute
  velocity — that remains the cross-bridge substep's job.

## 12. Single-head regression control (deliverable 11)
`SINGLE-HEAD REGRESSION: PASS.` The single-head explicit-S2 mat (`run_lasertrap.sh -exp4g -glide`, a **separate,
byte-unchanged** code path — `TwoBodyConverterMotor.buildS2Mat`) glides normally at **−2.25 µm/s, avgBound 3.16**,
unaffected. Confirmed:
- **No analogous large joint-gap metric exists** — the single head is one converter about a *fixed* pivot with no
  multi-node forked beam, so there is no inter-branch continuity to gap (the metric is structurally absent; the mat
  output reports velocity/avgBound only).
- **Velocity unchanged** — `-branchEA/-branchEI/-forkK` are read ONLY inside `ExplicitHmmDimer` (grep-verified),
  which the single-head path never calls.
- **No shared-code regression** — `git diff` touches only `ExplicitHmmDimer.java` and the dimer harness;
  `TwoBodyConverterMotor.java` / `ExplicitSingleHeadHarness.java` are byte-unchanged (0 diff).

## 13. Ranked mechanical-health scorecard (deliverable 12)
Ranked by: (1) removal of extreme excursions, (2) peak-force reduction, (3) dt convergence, (4) velocity, (5)
meanBound, (6) two-head engagement, (7) stroke, (8) coupling, (9) simplicity, (10) plausibility. **Not by velocity.**

| rank | setting | maxGap | peakBrF | dt-converged? | velP | meanB | coupling | stroke | verdict |
|---|---|---|---|---|---|---|---|---|---|
| **1** | **branchEA 0.03** | **11.8** | **103** | **yes (gap/force)** | +1.49 | 4.46 | preserved (3.24 nm) | 7.98 | **PROMOTE** — all criteria pass |
| 2 | branchEA 0.01 | 14.9 | 27 | mostly (rare dt/2 event) | +1.98 | 4.74 | preserved (3.60) | 7.98 | viable, but softer than needed |
| 3 | branchEA 0.1 | 68.9 | 1 570 | partial | +1.54 | 4.45 | preserved (3.12) | 7.98 | helps, incomplete (>50 nm remain) |
| 4 | baseline (ref) | 270.3 | 29 171 | no | +1.48 | 4.21 | 3.07 | 7.98 | the instability |
| 5 | EA0.1+EI0.3 (best combo) | 44.9 | 1 864 | partial | +0.77 | 4.59 | weak | ~7.9 | worse than EA0.03 alone |
| — | branchEI / forkK any | 91–3817 | 2e4–1.6e6 | no | +0.23…+0.61 | 4.3–4.7 | collapsed | ~7.9 | REJECT (destabilizing) |

**Winner: `branchEA 0.03` (effective 12.6 pN/nm)** — the stiffest branch-axial setting that removes the pathological
excursions. Not the softest (0.01), per the "prefer the stiffest that removes them" rule.

## 14. Representative movies (deliverable 13)
Matched seed 101, density 500, D0, 5000 steps, fstride 200 (`-3js`, served under the repo):
`threejs_hmm_compliance_base/` (reference, the excursion), `threejs_hmm_compliance_ea0.03/` (best-EA, smooth),
`threejs_hmm_compliance_ea0.1/`, `threejs_hmm_compliance_ei0.1/` (fork-flop), `threejs_hmm_compliance_comboEA0.1EI0.3/`.

## 15. Commands / seeds / density / duration / timestep / stride (deliverable 14)
`dt = 2.5e-6 s` (mat sensitivity also 1.25e-6). Density 500 dimers/µm² (3.0×1.0 µm lawn), gap 8 nm, cullR 120 nm,
fil ≈1.93 µm, D0, seeds 101–106 (dt: 101–103). Reference dimer α=10°, branchLen 10 nm, splay 16°.
```
# byte-identical default check (velRaw −3.553 @ seed 101, 1000 steps):
./scripts/run_hmm_gliding.sh -matsmoke -mdensity 500 -seed 101 -steps 1000 -cullr 0.12 -fillen 2.0
# a single mat cell with a compliance multiplier (branchEA / branchEI / forkK, each default 1.0):
./scripts/run_hmm_gliding.sh -matsmoke -mdensity 500 -seed 101 -steps 5000 -cullr 0.12 -fillen 2.0 -branchEA 0.03
# F1–F5 controlled single-dimer fixtures (Brownian OFF, actin fixed):
./scripts/run_hmm_gliding.sh -fixtures -branchEA 0.03
# timestep sensitivity (mat -dt override):
./scripts/run_hmm_gliding.sh -matsmoke -mdensity 500 -seed 101 -steps 10000 -dt 1.25e-6 -branchEA 0.03 -cullr 0.12 -fillen 2.0
# matched movie:
./scripts/run_hmm_gliding.sh -3js threejs_hmm_compliance_ea0.03 -mat -mdensity 500 -dmode 0 -seed 101 -steps 5000 -fstride 200 -branchEA 0.03
# single-head regression control (separate byte-unchanged path):
./scripts/run_lasertrap.sh -exp4g -glide -smoke
```
Sweep orchestration + raw logs: `RUN_LOGS/hmm_compliance_sensitivity/{stageABC,combo,dt,fixtures}/`.

## 16. Modified-file inventory (deliverable 15)
- `softbox/ExplicitHmmDimer.java` — added the `forkKmult` field + build overload (default 1.0, byte-identical;
  scales the two fork hinges' bending on top of `branchEImult`), and census helpers
  (`maxBranchAxialForcePn`, `maxBranchExtNm`, `maxBranchExtFrac`, `forkAngleDeg`). `branchEAmult`/`branchEImult`
  already existed. **The validated single-head motor was NOT modified** (this is a separate class).
- `softbox/ExplicitHmmDimerGlidingHarness.java` — `-branchEA/-branchEI/-forkK` controls (harness `CFG_*`), a `-dt`
  override, the gap-distribution + branch-mechanics + per-dimer excursion-trigger instrumentation in `run()`, the
  extended `matSmoke` MATROW, and the `-fixtures` F1–F5 mode. (Untracked new file from the directionality study.)
- `docs/matsoa/EXPLICIT_HMM_DIMER_COMPLIANCE_SENSITIVITY_FINDINGS.md` — this report.
- `scripts/run_cells.sh` / `run_dt.sh` (scratchpad orchestrators) — parallel mat-cell drivers.
- **Unchanged / byte-clean:** `TwoBodyConverterMotor.java`, `ExplicitSingleHeadHarness.java`, `MotorModel.java`, the
  single-head model, the long shared-S2 material (EA/EI), chemistry, the bind gate, the 5.4 nm exclusion, D0.

## 17. FINAL STATUS BLOCK
```
REFERENCE BRANCH AXIAL STIFFNESS: 420 pN/nm  (= EA_SI 4.2e-9 N / lB 10 nm, branchEAmult 1.0)
REFERENCE BRANCH EA SOURCE: EXP4G_EA_SI = 4.2e-9 N (AMK-2008 S2-subdomain paired coiled coil, as-is)
REFERENCE BRANCH EI: 0.25·EI_SI = 1.8e-28 N·m²  (branchEImult=0.25; the two fork hinges)
REFERENCE FORK ANGULAR STIFFNESS: 1.8e-20 N·m per fork hinge  (= EI·branchEImult/lrep; DEGENERATE with branchEI for Ma=1)
STIFFNESS DOUBLE-COUNTING FOUND: NO (each stretch/hinge once; fork stretch ⟂ fork angular; both branches symmetric)
BASELINE MAX GAP: 270.3 nm (6 seeds; up to ~670–1220 nm on adverse seeds)
BASELINE 99.9% GAP: 6.96 nm (per-step max-gap p99.9; the excursions are rare tail spikes above a ~1 nm median)
BASELINE EXCURSIONS >10/50/100 NM: 11 / 4 / 3
PRIMARY GAP TRIGGER: FILAMENT-MOTION (100% of >10 nm excursions; 0% at second-bind/stroke/detach)
FRACTION OF LARGE GAPS NEAR SECOND-HEAD BINDING: 0.00
FRACTION NEAR DOUBLE-BOUND STROKE: 0.00
FRACTION NEAR DOUBLE-BOUND DETACHMENT: 0.00
BEST BRANCH-EA MULTIPLIER: 0.03
BEST BRANCH-EA EFFECTIVE STIFFNESS: 12.6 pN/nm
BEST BRANCH-EA MAX GAP: 11.8 nm  (99.9% 4.36 nm)
BEST BRANCH-EA VELOCITY: +1.49 µm/s (baseline +1.48; within 1%)
BEST BRANCH-EI MULTIPLIER: none promotable (softening branchEI is destabilizing in the moving mat)
BEST BRANCH-EI MAX GAP: 91–3817 nm (all settings WORSE than baseline or velocity-killing)
BEST BRANCH-EI VELOCITY: +0.23…+0.61 µm/s (glide halved-to-killed)
BEST FORK-K MULTIPLIER: none (forkK ≡ branchEI for Ma=1; same rejection)
BEST FORK-K MAX GAP: = branchEI (degenerate)
BEST FORK-K VELOCITY: = branchEI (degenerate)
BEST COMBINATION: branchEA 0.03 alone (no combination improves on it; adding any bending softening is worse)
BEST COMBINATION MAX GAP: 11.8 nm
BEST COMBINATION 99.9% GAP: 4.36 nm
BEST COMBINATION PEAK BRANCH FORCE: 103 pN
BEST COMBINATION PEAK F8 FORCE: ~3.4 pN/head (F/head unchanged from baseline)
BEST COMBINATION VELOCITY: +1.49 µm/s
BEST COMBINATION MEAN BOUND: 4.46
BEST COMBINATION TWO-HEAD-BOUND FRACTION: 0.0002 (= baseline; engagement preserved)
BEST COMBINATION DOUBLE-BOUND DWELL: unchanged from baseline regime (~0.1–0.3 ms; two-head events rare)
INTRINSIC CONVERTER STROKE: 8.0 nm (branch-independent by construction; head Cmot untouched)
STATIC HEAD STROKE: 7.98 nm (measured; 7.83–7.98 across all settings)
DETERMINISTIC PARTNER-COUPLING RATIO: preserved (F2 free-head move 3.07 nm ref → 3.24 nm @ EA0.03; branchEI collapses it to 0.01 nm)
DT VELOCITY DIFFERENCE, BASELINE: +27% (1.88→2.39 at dt/2)
DT VELOCITY DIFFERENCE, BEST: +13% (2.17→2.45 at dt/2)
DT GAP DIFFERENCE, BASELINE: maxGap 270→64 nm (4.2×), peakBrF 29171→1592 pN (18×) — strongly dt-dependent
DT GAP DIFFERENCE, BEST: maxGap 8.4→9.3 nm, peakBrF 96→91 pN — CONVERGED (dt-insensitive)
D0≈D2 AFTER SOFTENING: YES
BACKWARD BINDS REMAIN SELF-FILTERING: YES
SINGLE-HEAD REGRESSION: PASS
INVALID STATES: 0 (every cell, every setting)
SOLVER FAILURES: 0 (every cell — even the catastrophic branchEI forces recover, no NaN)
BASELINE 3JS: threejs_hmm_compliance_base/
BEST BRANCH-EA 3JS: threejs_hmm_compliance_ea0.03/
BEST BRANCH-EI 3JS: threejs_hmm_compliance_ei0.1/ (fork-flop, illustrative)
BEST FORK-K 3JS: = branchEI (degenerate)
BEST COMBINATION 3JS: threejs_hmm_compliance_ea0.03/ (best combo = pure branchEA)  + threejs_hmm_compliance_comboEA0.1EI0.3/
EXCESSIVE DIMER STIFFNESS EXPLAINS INSTABILITY: YES (specifically the proximal-branch AXIAL stiffness; NOT bending)
SUBSTEP STILL INDICATED: YES — but only for the residual ~13% absolute-velocity dt-convergence (the known
  explicit cross-bridge under-sampling), NOT for the joint-gap instability, which branchEA softening removes.
RECOMMENDED DIMER COMPLIANCE: branchEA = 0.03 (proximal-branch axial stiffness 420 → 12.6 pN/nm); branchEI/forkK UNCHANGED
READY FOR QUANTITATIVE MAT ASSAY: YES for stability/joint-gap health (excursions removed, dt-converged, 0 invalid);
  the absolute velocity still needs the cross-bridge substep for full dt-convergence (decoupled from this fix)
NEXT STEP: adopt branchEA=0.03 as the dimer default for the mat gliding assay; then add the cross-bridge substep to
  converge the residual absolute velocity (now the ONLY remaining dt-sensitivity, ~13%, and no longer entangled
  with the joint-gap instability). Optionally re-run the density sweep at branchEA 0.03 to re-baseline velocities.
```

**Success:** the study determines that the explicit dimer's dense-mat joint-gap instability and (most of) its
timestep sensitivity arise **primarily from excessive stiffness in the proximal-branch AXIAL stretch** (420 pN/nm) —
not the branch bending and not the fork angular coupling (softening either is *destabilizing*). The instability is
**filament-motion-driven** (100% of excursions; the moving actin drags bound heads through the near-rigid branch),
not triggered by second-head binding, stroke, or detachment. **branchEA = 0.03 (12.6 pN/nm)** is the stiffest
mechanically-plausible setting that removes the pathological excursions (maxGap 270→11.8 nm, peak force 29 171→103
pN, 0 invalid) while preserving the 8 nm converter stroke, inter-head coupling, second-head engagement, unbiased
(D0≈D2) ensemble gliding at +1.49 µm/s, and the single-head model — and it makes the gap/force metrics
dt-convergent. A cross-bridge substep remains indicated only for the residual ~13% absolute-velocity convergence.

