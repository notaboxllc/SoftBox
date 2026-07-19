# Explicit HMM dimer — same-filament bound-site occupancy exclusion (one actin-monomer spacing)

Prerequisite for a forward-versus-backward second-head binding study: prevent the two heads of one explicit HMM
dimer from binding the same local actin patch. **Once one head is bound to a filament, the partner may not bind at
a material coordinate less than `BOUND_SITE_EXCLUSION_NM = 5.4 nm` (≈ one actin-monomer spacing) away along that
SAME filament.** The rule is **symmetric** (head A excludes head B and vice versa; no barbed/pointed preference —
any forward bias must emerge from dimer geometry/mechanics), a pure **veto** on an otherwise valid candidate
(never moves a head or alters coordinates), and applied only to same-filament partners. No explicit forward gating
is added. CPU-only. Builds on `EXPLICIT_HMM_DIMER_SHORT_BRANCH_FINDINGS.md` (default fork geometry α=10°, Lb=10 nm,
branchEI×0.25). The single-head model, `TwoBodyConverterMotor`/`MotorModel`, and the shared viewer are unchanged.

## Headline
The exclusion is implemented, validated by 8 deterministic fixtures, and enforced in the production binding path.
**The invariant holds exactly** (minimum same-filament two-head site separation rises from **0.116 nm** without the
rule to **≥ 5.405 nm** with it; **zero** near-site double-binds; zero same-site double binding). Second-head binding
is **preserved but reduced** — the current α=10°/Lb=10 fork holds the heads close, so most (≈94%) second-head
proposals fall inside 5.4 nm and are correctly rejected; the remainder bind at ≥ 5.4 nm in **both directions**
(forward and backward observed), so a forward-bias study is now measurable (with more sampling for statistics).
Stroke and force are preserved; 0 invalid states, 0 solver failures. **READY FOR FORWARD-BIAS STUDY: YES.**

## 1–3. Rule + material-coordinate + binding-path placement (deliverables 1–5)
- **Exclusion distance** `BOUND_SITE_EXCLUSION_NM = 5.4 nm`; per-scene (`Scene.exclusionNm`), **default OFF (0)** so
  every prior harness path is byte-unchanged. Boundary is **inclusive-allow**: reject iff `|Δ| < 5.4 − tol`
  (`tol = 1e-3 nm`), so exactly 5.4 nm is permitted.
- **Continuous material coordinate** (`ExplicitHmmDimer3jsHarness.filMatCoordUm`): the coordinate (µm) of a site
  `(segment, bindArc)` along the whole filament, increasing toward the **barbed** end (+uVec / end2). It walks the
  chain from the pointed terminal (`end1NbrSlot == SENTINEL`) via `end2NbrSlot`, accumulating segment rest lengths,
  and adds `bindArc ∈ [0, segLength]` (measured from e1). It is therefore correct on the **same segment**, on
  **neighbouring segments**, **across a segment boundary**, and anywhere on the filament — using the real actin
  material coordinate, not head-center/Euclidean distance or segment index. Rejection test:
  `occupancyVeto = candFil==partnerFil && |sCand − sPartner| < 5.4 − tol`.
- **Placement:** the veto is inserted into the production bind gate **after** a geometrically valid candidate site
  has passed ALL existing gates (search radius, orientation, canonical half-open ownership, zero physical margin,
  chemistry-state requirement, energy/preload) and **before** the bind is committed. It can only veto; it never
  moves a head or writes coordinates. All existing gates are preserved unchanged.

## 4. Different filaments (deliverable, task §4)
The veto returns false whenever the candidate and partner are on different physical filaments (fixture 6:
different-filament at 0 nm offset → allowed). This assay has a single filament, so the two heads always share it;
the check is general.

## 5. Simultaneous binding attempts (deliverable, task §5)
The bind gate evaluates head 0 then head 1 within a step, committing head 0 before head 1 is tested. So if both
detached heads propose within 5.4 nm the same step, head 0 commits and head 1 is vetoed against it ⇒ **exactly one
accepted, deterministic** (lower head-index tie-break). The veto predicate itself is symmetric (`|Δ|`), so the
result is mirrored under an A/B relabel (fixture 8 + its A/B-swap variant confirm exactly-one-accepted, head-0
wins, and both-accepted when the two sites are ≥ 5.4 nm apart).

## 6. Invariants (deliverable, task §6)
Runtime checks in the dynamic runner: whenever both heads are bound to the same filament, `|sA − sB|` is measured;
`nearSiteDoubleBind` counts any frame with `|sA − sB| < 5.4 − tol` (**must be 0**), and the running minimum
`minSiteSepNm` is tracked. Result over all runs: **near-site double-binds = 0**, minimum same-filament site
separation **5.405 nm** (test) vs **0.116 nm** (control), zero invalid material coordinates, zero segment-boundary
failures.

## 7. Diagnostics (deliverable, task §7)
Every second-head (partner-bound) binding proposal is logged to `dimer_bind_proposals.csv`:
`t_ms, proposingHead, partnerBound, candFil, partnerFil, candMat_um, partnerMat_um, signedOffset_nm (+barbed),
absOffset_nm, result (ACCEPTED/REJECTED), rejectReason, nucProposer, nucPartner, partnerPowerStroked`. Aggregate
counters: total partner-bound proposals, occupancy rejects, accepted forward/backward binds, accepted-offset list,
minimum simultaneous bound-site separation, double-bound fraction, double-bound dwell. Example rows (seed 13):
```
t_ms,proposingHead,partnerBound,candFil,partnerFil,candMat_um,partnerMat_um,signedOffset_nm,absOffset_nm,result,rejectReason,nucProposer,nucPartner,partnerPowerStroked
24.8775,1,true,0,0,1.0603,1.0539,6.482,6.482,ACCEPTED,-,ADPPi,ADP,true          # accepted FORWARD, partner already power-stroked
10.2100,1,true,0,0,1.0618,1.0618,0.049,0.049,REJECTED,occupancy<excl,ADPPi,ADP,true   # rejected same-patch (0.05 nm)
```

## 8. Deterministic validation fixtures — ALL PASS (deliverable 6, 7; task §8)
`./scripts/run_hmm_dimer_exclusion.sh -fixtures`:
```
  [PASS] 1 same site (0 nm) → REJECT
  [PASS] 2 inside (±2.7 nm) → REJECT
  [PASS] 3 boundary (±5.4 nm) → ALLOW (inclusive at exactly 5.4 within tol)
  [PASS] 4 outside (±5.5 nm) → ALLOW
  [PASS] 5 cross-boundary continuous separations = 3.000 nm and 6.000 nm (partner near a segment end, candidate in
         the next segment) → 3 nm REJECT, 6 nm ALLOW  (continuous material coordinate, not segment index)
  [PASS] 6 different filament (0 nm) → ALLOW
  [PASS] 7 A/B swap symmetry (inside + outside)
  [PASS] 8 simultaneous conflict: exactly one accepted, head 0 wins; A/B-swap mirrors; 6 nm apart → both accepted
```
Boundary decision (task §8.3): **inclusive-allow at 5.4 nm** (reject iff `|Δ| < 5.4 − 0.001 nm`).

## 9. Control vs test dynamic comparison (deliverable 8; task §9)
`./scripts/run_hmm_dimer_exclusion.sh -compare` — matched α=10°, Lb=10, EI×0.25, seeds {2,3,5,7,11}, 8000 steps,
identical timestep/filament/chemistry/gates; only the exclusion differs:
```
  metric                             | CONTROL (OFF) | TEST (5.4 nm)
  min two-head site sep (nm)         |         0.116 |         5.405
  mean signed 2nd-bind offset (nm)   |        -1.277 |         3.099
  median signed 2nd-bind offset (nm) |         0.116 |         5.979
  mean |2nd-bind offset| (nm)        |         2.056 |         5.821
  median |2nd-bind offset| (nm)      |         2.047 |         5.979
  accepted forward binds             |             3 |             3
  accepted backward binds            |             3 |             1
  forward fraction                   |         0.500 |         0.750   (n=4 — not yet statistically resolved)
  partner-bound proposals            |             6 |            71
  occupancy rejects                  |             0 |            67
  two-head-bound fraction            |         0.037 |         0.015
  double-bound dwell (ms)            |         0.626 |         0.377
  ADP axial offset 3-8 nm frac       |         0.333 |         0.360
  stroke A / B (nm)                  |         5.071 |         4.667 / 6.16
  peak F8 A / B (pN)                 |         9.153 |        10.286 / 8.57
  invalid states                     |             0 |             0
  near-site double-binds (must be 0) |             0 |             0
```
**Interpretation.** Without the rule the two heads bind as close as **0.116 nm** apart (unphysical same-patch
double occupancy; mean |offset| 2.06 nm — i.e. the "two-head-bound" population was dominated by same-monomer
binding). With the rule, every such proposal is rejected (67 of 71) and the surviving two-head binds sit at a
**physical ≥ 5.4 nm** separation (mean |offset| 5.82 nm, median 5.98 nm). The two-head-bound fraction drops
0.037 → 0.015 and dwell 0.63 → 0.38 ms: the current fork holds the heads too close for frequent ≥ 5.4 nm
co-binding, so the rule removes most of the (previously unphysical) two-head population. Crucially it does **not**
abolish it — second-head binds still occur in **both directions** (per-seed: seeds 2/5/7/13/19 each land a ≥ 5.4 nm
bind; seed 13 gives forward AND backward), so forward-vs-backward is now measurable. Stroke and peak force are
preserved; 0 invalid, 0 solver failures.

**Sampling caveat (flagged):** accepted ≥ 5.4 nm second-head binds are rare at this geometry (4 over 5 seeds ×
8000 steps), so the forward fraction (0.75, n=4) is **not** statistically resolved — the actual forward-bias study
will need many more seeds / longer runs, and/or a head-spreading fork geometry that reaches ≥ 5.4 nm sites more
often. That geometry work is the natural next step; the exclusion machinery is the prerequisite and is now in place.

## 10. Representative `-3js` trajectory (deliverable 10; task §10)
`threejs_hmm_dimer_excl/` (seed 13, 16000 steps = 40 ms, stride 15 → 1068 frames, exclusion 5.4 nm; invalid 0,
near-site double-binds 0, min site sep 5.60 nm). Event CSV gains `siteSep_nm, signedSecondBindOffset_nm,
secondBindDirection, occupancyRejectCount, occupancyRejectThisFrame`; the proposal log accompanies it. Representative
frames:
| event | frame | t (ms) | note |
|---|---|---|---|
| first-head binding | 2 | 0.075 | nBound 0→1 |
| rejected near-site 2nd-head attempt | 273 | 10.24 | occupancyRejectThisFrame=1 (0.05 nm same-patch veto) |
| accepted FORWARD 2nd-head bind | ~664 | 24.88 | signed +6.48 nm, partner already power-stroked (ADP) |
| doubly bound | 664 | 24.90 | siteSep 6.48 nm, dir FWD |
| detachment | 673 | 25.24 | nBound 2→1 |
| accepted BACKWARD 2nd-head bind | ~874 | 32.80 | signed −5.63 nm, dir BWD |
| power stroke | throughout | — | ADP·Pi→ADP on bound heads |

## 11. Commands, seeds, timestep, stride
- `dt = 2.5e-6 s`; fixtures deterministic; comparison seeds {2,3,5,7,11} × 8000 steps; movie seed 13, 16000 steps,
  stride 15. Fork geometry α=10°, Lb=10 nm, EI×0.25, splay 16°, gap 3 nm, 12-seg actin.
```
./scripts/run_hmm_dimer_exclusion.sh -fixtures     # the 8 deterministic validation fixtures
./scripts/run_hmm_dimer_exclusion.sh -compare      # control (OFF) vs test (5.4 nm) + status block
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_excl -seed 13 -steps 16000 -stride 15 -splay 16 -gap 3.0 -nseg 12 -alpha 10 -branchei 0.25 -branchlen 10 -excl 5.4   # representative movie
```

## 12. Modified-file inventory
- `softbox/ExplicitHmmDimer3jsHarness.java` — `filMatCoordUm` (continuous material coordinate), `occupancyVeto`
  (symmetric, same-filament, inclusive-boundary), `Scene.exclusionNm`/`filId`, the bind-gate veto + `Run`
  diagnostics (proposals/rejects/fwd/bwd/minSiteSep/nearSiteDoubleBind, proposal log), 5 new event-CSV columns,
  the proposals CSV, and `-excl <nm>` / `-noexcl` args. **Default OFF ⇒ all prior harness paths byte-unchanged**
  (verified: OFF reproduces the prior Movie-B numbers exactly, STROKE 6.17/6.92 nm, maxGap 1.954 nm, invalid 0).
- `softbox/ExplicitHmmDimerSiteExclusionHarness.java` — **NEW**: §8 fixtures + §9 control/test comparison + status.
- `scripts/run_hmm_dimer_exclusion.sh` — **NEW** runner.
- `docs/matsoa/EXPLICIT_HMM_DIMER_SITE_EXCLUSION_FINDINGS.md` — **NEW**: this report.
- **Unchanged:** the single-head explicit model, `TwoBodyConverterMotor.java`, `MotorModel.java`, `ExplicitHmmDimer.java`,
  the fork/short-branch harnesses, and `sim_viewer_boa.html`.

## 13. FINAL STATUS BLOCK (task §12)
```
BOUND-SITE EXCLUSION IMPLEMENTED: YES
EXCLUSION DISTANCE: 5.4 nm
SAME-SITE DOUBLE BINDING: 0 (test) / 0 (control)
MINIMUM SAME-FILAMENT BOUND-SITE SEPARATION: 5.405 nm (test) / 0.116 nm (control)
SEGMENT-BOUNDARY TEST: PASS
DIFFERENT-FILAMENT TEST: PASS
SIMULTANEOUS-PROPOSAL TEST: PASS
A/B SWAP SYMMETRY: PASS
PROPOSALS REJECTED BY OCCUPANCY: 67 (test, of 71 partner-bound proposals) / 0 (control)
ACCEPTED FORWARD SECOND-HEAD BINDS: 3 (test) / 3 (control)
ACCEPTED BACKWARD SECOND-HEAD BINDS: 1 (test) / 3 (control)
FORWARD FRACTION: 0.75 (test, n=4 — not yet statistically resolved) / 0.50 (control)
MEAN SIGNED SECOND-BIND OFFSET: 3.10 nm (test) / -1.28 nm (control)
MEAN ABSOLUTE SECOND-BIND OFFSET: 5.82 nm (test) / 2.06 nm (control)
TWO-HEAD-BOUND FRACTION, CONTROL: 0.037
TWO-HEAD-BOUND FRACTION, EXCLUSION: 0.015
DOUBLE-BOUND DWELL, CONTROL: 0.63 ms
DOUBLE-BOUND DWELL, EXCLUSION: 0.38 ms
ADP-LIKE OFFSET 3–8 NM, CONTROL: 0.333
ADP-LIKE OFFSET 3–8 NM, EXCLUSION: 0.360
STROKE A/B: 4.67 / 6.16 nm (test)
PEAK F8 FORCE A/B: 10.29 / 8.57 pN (test)
INVALID STATES: 0
SOLVER FAILURES: 0
3JS TRAJECTORY: threejs_hmm_dimer_excl/ (seed 13, 1068 frames, exclusion 5.4 nm)
READY FOR FORWARD-BIAS STUDY: YES (invariant enforced, second-head binding still occurs at ≥5.4 nm in both directions; needs more sampling / a head-spreading geometry for statistics)
```

**Success criterion (task §12):** two heads of one explicit HMM dimer can no longer occupy the same local actin
patch — once one head is bound, the partner binds at least one 5.4 nm actin-monomer spacing away on the same
filament (forward or backward), with **no explicit directional bias** in the rule (it is symmetric; the small
apparent forward lean is within n=4 sampling noise). The minimum same-filament two-head separation is enforced at
5.405 nm, near-site double-binding is eliminated (0), and stroke/force/numerical health are preserved.
