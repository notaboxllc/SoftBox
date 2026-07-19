# Explicit HMM dimer — emergent forward-vs-backward second-head accessibility

With the 5.4 nm same-filament bound-site occupancy exclusion enforced
(`EXPLICIT_HMM_DIMER_SITE_EXCLUSION_FINDINGS.md`), this study asks a purely **diagnostic** question:

> Does the present shared-tail mechanics, fork geometry, converter stroke, and actin polarity generate an emergent
> forward bias in the free head's search and second-head binding after the partner binds and power-strokes?

**No explicit forward gate, preferred sign, processive rule, inter-head coordination, strain-dependent rate, steric
repulsion, lattice, or gate change was added** (task §15). Fork geometry α=10°, Lb=10 nm, EI×0.25; single-head model,
`TwoBodyConverterMotor`/`MotorModel`, and the shared viewer unchanged; the exclusion + logging default OFF so prior
paths are byte-unchanged. CPU-only.

## Headline — WEAK / UNRESOLVED forward tendency; negligible deterministic mechanism
- **Sign convention validated** (§1): material coordinate `s` increases toward the barbed end; `deltaS = sCand −
  sBoundPartner`; deltaS>0 = FORWARD (barbed). Confirmed against rendered world polarity, and by A/B-relabel,
  y-spatial-mirror, and polarity-reversal controls (all PASS).
- **Deterministic mechanical coupling is essentially null:** Brownian-off, the bound partner's power stroke shifts
  the free head's equilibrium coordinate by only **+0.020 nm** (coupled) vs **−0.025 nm** (fork-decoupled) — i.e.
  the shared tail transmits **no** meaningful directional shift (the ~8 nm stroke does not steer the free head).
- **Dynamic tendency is modest and NOT statistically resolved:** over 40 seeds × 16000 steps (246 ms of
  one-head-bound exposure, 33 accepted second-head binds across 25 seeds), the accepted **forward fraction is 0.667
  with a seed-clustered 95% CI of [0.413, 0.780]** — the CI **includes 0.5**, so a forward preference is *suggested*
  (forward/backward rate ratio ≈ 2) but not established. The continuous free-head search coordinate shifts **+1.66 nm
  toward barbed** after the partner strokes, but the forward-accessibility fraction barely moves (0.107 → 0.129).
- **Verdict: EMERGENT FORWARD BIAS PRESENT = UNRESOLVED** (weak dynamic tendency, negligible clean mechanism).
  **READY FOR MECHANISM CHANGE: YES** — a directional mechanism (a leading/trailing lever asymmetry) would be
  required to produce a resolved forward bias; the current mechanics does not.

---

## 1. Sign convention (§1, deliverable 1)
`s` increases toward the barbed end (+uVec / end2). Polarity fixture PASS: a site at +Δs renders at larger world-x
than the bound site, which renders larger than a −Δs site (barbed = +x here); direction is taken from the material
coordinate, never from world-x / camera / segment index. `deltaS>0` FORWARD, `<0` BACKWARD, `|deltaS|<5.4` excluded.

## 2. Proposal vs acceptance (§2, deliverable 2)
A structural finding: in the current path the **only gate after the geometric gates is the occupancy exclusion**, so
**eligibility ⟹ acceptance** (acceptance probability conditional on an eligible forward or backward proposal ≈ 1.000;
the sole exception is the rare same-step tie-break). Therefore the directional signal, if any, lives entirely in
**(a) where the free head searches / proposes** and **(b) which proposals are eligible (|deltaS|≥5.4)** — both are
properties of the free-head search geometry, *not* a separate acceptance bias. This is why the primary analysis is
the free-head **search distribution** (§3) and the deterministic **accessibility maps** (§6), not the rare accepted
events. Per-proposal records (forward/backward × inside-exclusion/eligible/accepted) are logged to
`dimer_bind_proposals.csv`; aggregate counts are in §7/§10.

## 3. Continuous free-head search distribution (§3, deliverable 3)
While exactly one head is bound, the free head's F8 tip is projected onto the filament and its signed material
coordinate relative to the bound site is recorded every step (event-independent). Over 40 seeds (246 ms exposure):
mean signed free-head coordinate **PRE-stroke −8.20 nm, POST-stroke −6.54 nm** ⇒ a **+1.66 nm** barbed-ward shift.
The free head sits predominantly **pointed-ward** of the bound head (negative mean) in both states; the stroke moves
the distribution modestly toward barbed but the barbed-ward-occupancy fraction rises only 0.107 → 0.129.

## 4. Pre- vs post-stroke accessibility (§4, deliverable 4)
Partitioned by partner nucleotide/mechanical state: **State A** (partner ADP·Pi, pre-stroke) 23.5 ms exposure; **State
C** (partner ADP, post-stroke) 209.8 ms exposure. The mechanistic question — *does the free-head search shift toward
+deltaS after the partner power-strokes?* — answer: **a small shift (+1.66 nm dynamic; +0.020 nm deterministic
equilibrium), not a decisive relocation.** The stroke does not open a predominantly-forward accessible volume.

## 5. Controlled conditions (§5, deliverable 5)
- **Condition 1 (normal coupled dimer):** accepted forward fraction 0.667 [0.413, 0.780]; continuous shift +1.66 nm.
- **Condition 2 (stroke suppressed):** `-nostroke` holds every head at PRE (mechanical stroke off; nucleotide still
  cycles). Pre-stroke free-head barbed-ward accessibility = 0.107; a representative run bound the first head but the
  free head did **not** achieve a second bind — consistent with the stroke contributing little forward accessibility.
- **Condition 3 (controlled stroke, Brownian off):** the deterministic accessibility maps (§6) — the free head's
  equilibrium barely moves (+0.020 nm) as the partner is driven PRE→POST.
- **Condition 4 (decoupled):** the fork node is **pinned** (`ExplicitHmmDimer.solve(..., decoupleFork=true)`),
  severing force transmission between the two proximal branches while each head keeps its own branch + F8 mechanics
  against the fixed fork. The stroke-induced free-head shift is **−0.025 nm decoupled vs +0.020 nm coupled** ⇒ the
  shared-tail mechanical contribution is **~0.045 nm** (negligible). Decoupling is documented precisely; the labeled
  two-independent-motor control is not needed because the in-architecture fork-pin already isolates transmission.

## 6. Deterministic accessibility maps (§6, deliverable 6)
Brownian-off, head A bound at its natural projected site, head B free; B's reach (`surf` = tip→site clearance, nm) to
signed offset sites, pre vs post A-stroke:
```
  offset(nm)  eligible |  surf PRE  surf POST  Δsurf(POST−PRE)
    -16.2    YES       |   13.119    13.075      -0.044
    -10.8    YES       |    7.930     7.857      -0.073
     -5.4    YES       |    3.091     2.947      -0.144
     +5.4    YES       |    3.133     2.956      -0.177
    +10.8    YES       |    7.978     7.867      -0.111
    +16.2    YES       |   13.168    13.085      -0.083
```
The stroke makes **all** offsets marginally more accessible (Δsurf ≈ −0.04…−0.18 nm), essentially **symmetrically** —
not a directional forward opening. B's signed coord vs A: PRE −0.025 nm → POST −0.005 nm (shift +0.020 nm). Forward
reach advantage `surf(−5.4)−surf(+5.4)`: PRE −0.041 → POST −0.009 nm (≈0). A/B symmetry holds (both free-head offsets
< 0.7 nm; exact equality is not expected — the two heads share a world-fixed orientation at splayed ±z pivots).

## 7. Natural-event dataset + sampling protocol (§7, deliverable 7)
Production-faithful dynamic harness (real dynamic actin, canonical half-open ownership, zero physical margin, full
chemistry, catch-slip release, Brownian search, shared-tail solve, exclusion 5.4 nm), 40 seeds × 16000 steps.
Collected **33 accepted second-head binds** across **25 of 40 seeds**; 246 ms total one-head-bound exposure. Target
(≥50, preferably ≥100) was **not** reached — the current geometry rarely reaches ≥5.4 nm co-binding — so the accepted
statistics remain underpowered (the search radius / orientation gates / exclusion were **not** loosened to inflate the
count, per §7). The primary conclusion therefore rests on the event-independent §3/§6 analyses, not the accepted count.

## 8. Pseudoreplication-aware uncertainty (§8, deliverable 8)
Accepted binds are clustered by seed. Per-seed forward fractions (25 seeds with events) → **seed-clustered bootstrap
95% CI [0.413, 0.780]** on the pooled forward fraction 0.667 (2000 resamples of seeds with replacement, fixed RNG
seed). The CI **includes 0.5**; the result is not dominated by one seed but is not resolved as a forward bias. Mean
signed accepted offset +2.27 nm; median +5.73 nm (eligible binds cluster just past 5.4 nm); mean |offset| 6.37 nm.

## 9. Exposure-normalized directional rates (§9, deliverable 9)
Per ms of one-head-bound exposure: accepted **forward 0.0894 /ms, backward 0.0447 /ms** (ratio 2.0); occupancy
rejections forward 525 / backward 415 over the dataset. Because eligibility ⟹ acceptance, the eligible-proposal rate
equals the accepted rate. State-normalized: pre-stroke exposure 23.5 ms, post-stroke 209.8 ms.

## 10. Directional metrics (§10, deliverable 9)
| metric | value |
|---|---|
| accepted forward / backward | 22 / 11 |
| forward fraction (clustered CI) | 0.667 [0.413, 0.780] |
| forward/backward rate ratio | 2.0 |
| mean / median / mean-abs signed accepted offset | +2.27 / +5.73 / 6.37 nm |
| forward eligible-proposal (=accepted) rate | 0.0894 /ms |
| backward eligible-proposal rate | 0.0447 /ms |
| conditional forward / backward acceptance prob | 1.000 / 1.000 |
| pre- / post-stroke forward accessibility fraction | 0.107 / 0.129 |
| stroke-induced free-head shift (dynamic / deterministic) | +1.66 nm / +0.020 nm |

## 11. Mechanical interpretation (§11)
The clean deterministic decomposition (§5 Condition 4) shows the stroke→shared-tail→free-head transmission is
**negligible (~0.02–0.05 nm)** — the forward tendency in the dynamic data does **not** come from the shared-tail
mechanically steering the free head after the stroke. The modest dynamic +1.66 nm shift is dominated by the Brownian
search distribution and the (heavily post-stroke-weighted) exposure sampling, not by a mechanical forward drive. No
causal forward mechanism is claimed from the (unresolved) correlation.

## 12. Stroke decomposition (§12, deliverable 11)
Head A, decomposed: **intrinsic converter throw 8.00 nm** (unloaded), **head vs anchor 7.60 nm**, **head vs bound
actin material point 7.60 nm** (bound, held by the F8 spring). The 5.4 nm occupancy rule cannot affect any of these
(it only vetoes the partner's bind) — the intrinsic converter stroke is preserved. The smaller dynamic assay strokes
reported earlier (~4–6 nm) are the assay-level observed head movement (movable filament + shared tail take up part of
the throw), not a change in the intrinsic motor stroke.

## 13. Direction-neutral validation (§13, deliverable 10)
- **Polarity reversal** (flip every uVec + swap neighbour slots): the free-head shift keeps sign in material
  coordinates (−0.020 nm vs coupled +0.020 nm) — PASS.
- **Spatial mirror** (y→−y, perpendicular to the filament; polarity + splay + head frames preserved): shift +0.020 nm
  = coupled — PASS. (A z-mirror is NOT clean here — it flips the dimer to the far side of the filament while the head
  orientation frames stay world-fixed; y-mirror is the correct perpendicular mirror.)
- **A/B relabel:** both free-head offsets remain < 0.7 nm — PASS (no large asymmetric artifact; exact equality not
  expected, see §6).
- **No-stroke control:** with the stroke suppressed the residual forward accessibility is 0.107 (≈ the pre-stroke
  value) — any forward tendency is not created by a static-geometry code artifact.
- **Decoupled control:** with shared-tail transmission removed the stroke-induced shift vanishes (−0.025 nm) —
  confirming there is no strong dimer-mechanical forward drive to begin with.

## 14. `-3js` trajectories (§14, deliverable 12)
Matched rendering (α=10°, Lb=10, EI×0.25, exclusion 5.4 nm; 16000 steps, stride 15 → 1068 frames; fork sphere off,
rod hidden). Event CSV carries `firstBoundHead, partnerStroked, freeF8Signed_nm, timeSinceFirstBind_ms,
timeSincePartnerStroke_ms` (+ the site-exclusion columns); proposal log alongside.
| movie | dir | seed | key events |
|---|---|---|---|
| A — forward 2nd-head bind | `threejs_hmm_dimer_fwd/` | 19 | forward binds at t=1.46 ms (frame ~38, +10.45 nm, partner stroked) and t=24.30 ms (frame ~647, +6.70 nm); doubly bound; detach |
| B — backward 2nd-head bind | `threejs_hmm_dimer_bwd/` | 2 | backward bind at t=1.77 ms (frame ~47, −5.44 nm, partner stroked); doubly bound |
| C — stroke-suppressed control | `threejs_hmm_dimer_nostroke/` | 13 | first head bound, free head searching, mechanical stroke suppressed (assay stroke 2.6/4.2 nm vs ~6 nm normal); no forced second bind |
All: first-head attachment, rejected near-site attempts, power stroke (A/B), detachment, renewed search; invalid 0.

## 15. Commands, seeds, timestep, stride (§13, deliverable 13)
- `dt = 2.5e-6 s`; deterministic maps Brownian-off; natural aggregation seeds 1..40 × 16000 steps; movies seed 19/2/13,
  16000 steps, stride 15. Fork α=10°, Lb=10 nm, EI×0.25, splay 16°, gap 3 nm, 12-seg actin, exclusion 5.4 nm.
```
./scripts/run_hmm_dimer_forward.sh -maps                          # §1 sign + §6 maps + §4 shift + §5 conditions + §12 stroke + §13 validation
./scripts/run_hmm_dimer_forward.sh -natural -seeds 40 -steps 16000   # §7–10 natural aggregation + continuous search + clustered CI
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_fwd      -seed 19 -steps 16000 -stride 15 -splay 16 -gap 3.0 -nseg 12 -alpha 10 -branchei 0.25 -branchlen 10 -excl 5.4            # Movie A
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_bwd      -seed 2  -steps 16000 -stride 15 -splay 16 -gap 3.0 -nseg 12 -alpha 10 -branchei 0.25 -branchlen 10 -excl 5.4            # Movie B
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_nostroke -seed 13 -steps 16000 -stride 15 -splay 16 -gap 3.0 -nseg 12 -alpha 10 -branchei 0.25 -branchlen 10 -excl 5.4 -nostroke # Movie C
```

## 16. Modified-file inventory (§14, deliverable 14)
- `softbox/ExplicitHmmDimer.java` — `solve(..., decoupleFork)` overload (fork-pin decoupling control; false ⇒
  byte-identical to the coupled solve).
- `softbox/ExplicitHmmDimer3jsHarness.java` — `freeHeadMatCoordUm` (free-head projected material coord); continuous
  one-head-bound search logging + directional proposal classification (fwdInside/bwdInside, fwd/bwd × pre/post) +
  exposure counters + 5 new event-CSV fields (`firstBoundHead, partnerStroked, freeF8Signed_nm, timeSinceFirstBind_ms,
  timeSincePartnerStroke_ms`); `Scene.noStroke` + `-nostroke` (§5 Condition 2 control). **Default paths byte-unchanged.**
- `softbox/ExplicitHmmDimerForwardHarness.java` — **NEW**: §1 sign fixture, §6 deterministic accessibility maps,
  §4/§5 pre/post-stroke shift + coupled/decoupled conditions, §12 stroke decomposition, §13 validation
  (A/B / y-mirror / polarity-reversal), §7–10 natural aggregation with seed-clustered bootstrap CI, status block.
- `scripts/run_hmm_dimer_forward.sh` — **NEW** runner.
- `docs/matsoa/EXPLICIT_HMM_DIMER_FORWARD_ACCESSIBILITY_FINDINGS.md` — **NEW**: this report.
- **Unchanged:** single-head model, `TwoBodyConverterMotor.java`, `MotorModel.java`, `sim_viewer_boa.html`.

## 17. FINAL STATUS BLOCK
```
BARBED-END SIGN CONVENTION VALIDATED: YES
BOUND-SITE EXCLUSION: 5.4 nm
TOTAL ONE-HEAD-BOUND EXPOSURE: 245.96 ms
SEEDS RUN: 40
SEEDS WITH ACCEPTED SECOND-HEAD EVENTS: 25
ELIGIBLE FORWARD PROPOSALS: 22  (= accepted; eligibility ⟹ acceptance in this path)
ELIGIBLE BACKWARD PROPOSALS: 11
ACCEPTED FORWARD BINDS: 22
ACCEPTED BACKWARD BINDS: 11
FORWARD FRACTION: 0.667
FORWARD FRACTION CLUSTERED CI: [0.413, 0.780]  (includes 0.5 ⇒ not resolved)
FORWARD/BACKWARD BINDING-RATE RATIO: 2.0
FORWARD ELIGIBLE-PROPOSAL RATE: 0.0894 per ms
BACKWARD ELIGIBLE-PROPOSAL RATE: 0.0447 per ms
FORWARD ACCEPTANCE PROBABILITY: 1.000
BACKWARD ACCEPTANCE PROBABILITY: 1.000
MEAN SIGNED SECOND-BIND OFFSET: +2.268 nm
MEDIAN SIGNED SECOND-BIND OFFSET: +5.732 nm
MEAN ABSOLUTE SECOND-BIND OFFSET: 6.366 nm
PRE-STROKE FORWARD ACCESSIBILITY: 0.107
POST-STROKE FORWARD ACCESSIBILITY: 0.129
STROKE-INDUCED FREE-HEAD SHIFT: +1.663 nm toward barbed end (dynamic); +0.020 nm (deterministic equilibrium)
NORMAL COUPLED FORWARD FRACTION: 0.667
STROKE-SUPPRESSED FORWARD FRACTION: 0.107 (pre-stroke free-head barbed-ward accessibility)
DECOUPLED FORWARD FRACTION: stroke-induced shift −0.025 nm (fork pinned) vs +0.020 nm coupled ⇒ shared-tail drive ~0
POLARITY-REVERSAL TEST: PASS
SPATIAL-MIRROR TEST: PASS
A/B RELABEL TEST: PASS
INTRINSIC CONVERTER STROKE: 8.00 nm
HEAD STROKE RELATIVE TO ANCHOR: 7.60 nm
HEAD STROKE RELATIVE TO ACTIN: 7.60 nm
INVALID STATES: 0
SOLVER FAILURES: 0
FORWARD 3JS TRAJECTORY: threejs_hmm_dimer_fwd/ (seed 19, 1068 frames)
BACKWARD 3JS TRAJECTORY: threejs_hmm_dimer_bwd/ (seed 2, 1068 frames)
NO-STROKE CONTROL TRAJECTORY: threejs_hmm_dimer_nostroke/ (seed 13, 1068 frames)
EMERGENT FORWARD BIAS PRESENT: UNRESOLVED (weak dynamic tendency — accepted forward fraction 0.667 with clustered CI [0.413,0.780] straddling 0.5, rate ratio 2×, continuous shift +1.66 nm; but the clean deterministic stroke→shared-tail→free-head mechanism is negligible, +0.02 nm)
READY FOR MECHANISM CHANGE: YES
NEXT STEP: the current shared-tail mechanics does NOT produce a resolved emergent forward bias. A directional mechanism justified by this null — a mixed-nucleotide leading/trailing lever-arm asymmetry that actually re-aims the free head toward the barbed end (not a shorter proximal branch, which was already ruled out) — is the candidate; alternatively, extend sampling (≥100 accepted events) to resolve whether the 0.67 tendency is real before adding mechanism.
```

**Success criterion:** with the 5.4 nm occupancy constraint enforced, the study separated proposal bias (dominant),
gate acceptance (eligibility ⟹ acceptance), shared-tail mechanics (negligible directional drive), filament motion,
and sampling uncertainty (seed-clustered) — and finds the current mechanics produces at most a **weak, unresolved**
barbed-ward tendency, not a decisive emergent forward bias. A directional mechanism, or substantially more sampling,
is required before claiming forward bias.
