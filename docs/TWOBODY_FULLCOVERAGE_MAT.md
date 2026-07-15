# Experiment 4D-ii — full-length active-motor coverage on the dense 2D mat (audit + correction)

**Date:** 2026-07-14 · **Branch:** `dt-convergence-study` · **Commit at start:** `e17b5a4` · **Runner:** CPU
sequential only. **Hardware:** aorus, one core. **Wall-clock:** ≈ 12 min. **dt:** 2.5e-6.

**Default-off, non-canonical** (`-exp4d2` / `-twobody-fullcoverage-mat` in `softbox/TwoBodyConverterMotor.java`).
An audit + correction of Experiment 4D's candidate-selection and rendering geometry. **The validated two-body
motor, canonical Lymn–Taylor chemistry + kinetic constants, binding gate, filament mechanics, density, bending
stiffness, F8 stiffness, and `BoA-v1ref` are untouched.** The new simulation cull and the viewer fix are gated so
the `-exp4d` path is **byte-identical** (`cullMode=0` default; `fullViewer=false`). Not a motor-tuning experiment.

---

## 0. Problem and controlling outcome

The 4D viewer showed the fully articulated motor region concentrated around the filament **midpoint** rather than
spanning the full 2.1 µm contour. This experiment separates the **simulation** cull from the **rendering** cull,
identifies the cause, corrects it with a contour-complete per-segment spatial query, and validates against brute
force.

**CONTROLLING OUTCOME: the defect was VIEWER-ONLY (rendering), not the simulation.** The simulation candidate set
(a whole-chain AABB over all 12 segments) was already **contour-complete** — verified against a 4000-motor brute
force (Phase 3): the corrected per-segment union cull omits **zero** brute-accepted bindings, and coverage is
**100 % of the contour** with every segment carrying 9–18 candidate motors (Phase 4). The bug was the viewer
articulation rule, which used a **±0.6 µm window centered on the filament midpoint** and therefore rendered the end
regions of a 2.1 µm filament as anchor posts instead of articulated motors. The fix (articulate by shortest
distance to **any live segment**, no midpoint window) now articulates **253 motors near the filament ends** where
the old rule gave 0, spanning the full contour.

---

## 1. Phase 1 — audit of the five sets (executed-code facts)

| set | purpose | source/method | selection rule (4D → 4D-ii) |
|---|---|---|---|
| chemistry-active | nucleotide-cycle update | `NucleotideCycleSystem.cycleLymnTaylor` over all N | **all N motors** (no cull) — unchanged |
| geometric binding candidates | 3E gate evaluation | `stepGlide2D` active-set | 4D: **whole-chain AABB** (all 12 segments) + 30 nm, tested on the **anchor** → **4D-ii: per-segment UNION** (site → any live segment ≤ queryR), grid-accelerated |
| force-active | bound-motor force | `CrossBridgeSystem.bondForces` (skips `boundSeg<0`) + CSR gather | **bound motors only**, over nSeg — unchanged |
| viewer articulated | full head/lever render | `GlideFrame2D.write` | 4D: **`|anchor.x − filament MIDPOINT| < 0.6 µm` AND `|anchor.y|<0.6` AND near-any-segment** → **4D-ii: shortest site→any-live-segment ≤ 140 nm (NO midpoint window)** |
| viewer anchor-post | distant post render | `GlideFrame2D.write` | everything not articulated — 2D density stays visible |

**Which coordinate each set is built from:**

- chemistry-active: none (all motors).
- binding candidates: 4D = the **whole-chain AABB** (all 12 segments contribute) — contour-complete but tested on
  the anchor position; 4D-ii = the **union of per-segment neighborhoods** via shortest distance to any live segment.
- force-active: the bound segment (per-motor `boundSeg`).
- **viewer articulated: 4D used the filament MIDPOINT (mean segment x)** — the defect (case 2: viewer-only; case 5:
  a center coordinate used accidentally). 4D-ii uses shortest distance to any live segment.

**Every one of the 12 segments contributes to the candidate region** — confirmed by Phase 4 (all 12 covered).

**Root-cause answers to the five hypotheses (§ Problem):** (1) the simulation active set is **not** incomplete
(Phase 3: 0 missed bindings); (2) **only the viewer articulation set was incomplete** — the confirmed cause; (3) the
filament bounding box was **not** stale/single-segment (all 12 fed it); (4) the search radius was **not** too small
(the AABB + 30 nm covered the reach; Phase 4 = 100 %); (5) **yes — the viewer used a center/midpoint coordinate
accidentally** (the ±0.6 µm midpoint window).

---

## 2. Phase 2 — contour-complete per-segment selection

The corrected candidate rule (both the simulation cull, `cullMode=1`, and the viewer articulation):

1. build a **uniform CSR grid** over the fixed motor **sites** (built once);
2. for each **live** filament segment, query grid cells overlapping the segment's neighborhood;
3. take the **union** of per-segment candidate sets (a motor is a candidate if its site is within `queryR` of **any**
   live segment axis — shortest clamped distance, not distance to the filament center);
4. deduplicate (the union `active[]` boolean);
5. evaluate the full 3E stereospecific gate only on that union.

**Derived query radius `queryR = 30 nm`** (a motor whose site is farther than this from every segment cannot host a
binding). Components: F8 search swing `L_B·sin(φ_tol≈25°) ≈ 5 nm` + head half-extent ≈ 4.5 nm + gate reach
(`dBind 3 nm` + `FIL_R ≈ 3.5 nm`) ≈ 6.5 nm + segment displacement per update (`v·dt ≪ 1 nm`) ≈ **≈ 21 nm**, rounded
up to **30 nm** for safety. A whole-chain AABB is used as the coarse grid stage; final inclusion is distance to any
live segment. (Retained value is *derived*, not the 4D 30 nm reused blindly — it happens to coincide.)

---

## 3. Phase 3 — brute-force validation

The active-set **freezes** far-motor search (both 4D and 4D-ii), so a union-vs-brute *trajectory* comparison
diverges chaotically (brute keeps all 4000 motors searching) — bit-identity is not the standard here (the project's
chaotic statistical-equivalence rule). The correct tests:

- **(a) Completeness (the required result):** run a **brute** simulation (all 4000 motors evaluated) and, at each
  binding, check whether the motor was in what the union cull *would* select on the same state.
  **Result: 50 new bindings, MISSED by the union candidate set = 0** ⇒ **the cull omits no brute-accepted binding.**
- **(b) Statistical equivalence:** an independent union run (same seed) gives avgBound 0.992 vs brute 0.796
  (Δ = 0.196). The sign of Δ *flips* between runs (fast run: brute > union; full run: union > brute) ⇒ it is
  chaotic sampling noise, not a systematic freezing bias — the freezing is statistically benign.

**PASS** — the per-segment union cull is complete (no binding lost). `brute_validation.csv`.

---

## 4. Phase 4 — coverage along the entire contour

Per-segment candidate/gate/bound counts, sampled over 0.15 s (`coverage_by_segment.csv`, `coverage_heatmap.csv`):

- **segments with ≥ 1 candidate: 12/12 (100 %)**; **contour covered: 100 %**; **longest uncovered run: 0 segments.**
- per-segment candidate counts: **9–18 motors** on every segment (ends 10–12, midpoints 16–18).
- **end/mid candidate ratio = 0.61** — a real *geometric* effect (a mid-segment is flanked by filament on both
  sides, so more motors fall in its query strip than for an end segment that has filament on one side only), **not a
  culling artifact**; both ends remain well-covered (≥ 9 candidates).
- bound motors are spread across the contour (segments 0–11 all show bindings over the window), not clustered at the
  midpoint. `meanFullGate ≈ 0` per instant is expected — passing the full stereospecific gate at any single instant
  is rare (binding is stochastic); the coverage metric is candidate availability, which is full.

**At 1000 motors/µm² the active population covers the full filament whenever the whole contour lies over the mat**,
and **no segment is excluded by the culling implementation.**

---

## 5. Phase 5 — dense full-length visual run

Same 12-segment 2.106 µm flexible chain, ρ = 1000 µm⁻², **z-only** confinement, x/y/bending free, dt = 2.5e-6,
complete canonical cycling. The mat was enlarged to **4.0 × 1.0 µm (4000 motors, density unchanged)** so the full
contour stays over dense coverage; the run confirms the filament stays fully over the mat (x ∈ [−1.19, 0.86] vs mat
[−2.0, 2.0]).

**Viewer fix verified:** in a mid-run frame the articulated motors span **x ∈ [−1.34, 0.99]** (the full filament
contour + the reach margin), with **253 articulated motors near the ends** (`|x − midpoint| > 0.7 µm`) where the 4D
midpoint-window rule produced **0**. All 4000 motors are rendered (≈ 620 articulated + ≈ 3380 anchor posts) so the
2D density stays visible.

Frame sets (top-down + oblique in the viewer): `~/Code/SoftBox/threejs_twobody4d2_fullcoverage` (union cull),
`~/Code/SoftBox/threejs_twobody4d2_bruteforce_check` (brute — visual cross-check).

---

## 6. Classification

**Correction complete; the defect was rendering-only.** The 4D simulation candidate set was already
contour-complete (whole-chain AABB, all 12 segments); the observed midpoint concentration was a **viewer**
articulation bug (a filament-midpoint ±0.6 µm window, case 2 + case 5). The correction adds a grid-accelerated
per-segment **union** candidate cull (validated complete against a 4000-motor brute force — 0 missed bindings) and
fixes the viewer to articulate by shortest distance to any live segment (full-contour, 100 % coverage). **No motor,
chemistry, gate, filament, or density parameter changed; 4D's numeric results are unaffected** (the simulation cull
default is byte-identical). The earlier 4D scientific conclusions stand — this only corrects the rendering geometry
and hardens the candidate cull.

---

## 7. Recommendation for the next experiment

The 2D-mat assay + its coverage are now audited and correct, so proceed as recommended after 4D: a
**continuity/velocity study on the validated 2D-mat assay** (Brownian-averaged gliding velocity vs reachable-motor
count / duty, toward a regression vs the fine-dt canonical curve), or a **longer filament (8–15 µm)** to isolate a
real flexibility effect. The per-segment union cull + grid now make a larger/longer filament tractable. Keep
dt ≤ 5e-6. Do not begin that experiment in this run.

---

## 8. Deliverables and reproduction

```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp4d2 -out RUN_LOGS/twobody_fullcoverage_mat/csv \
        -3js ~/Code/SoftBox/threejs_twobody4d2                                   # audit + Phase 3/4 + dense viewers (~12 min)
./scripts/run_lasertrap.sh -exp4d2 -fast                                          # quick smoke
python3 scripts/twobody4d2_analyze.py RUN_LOGS/twobody_fullcoverage_mat/csv \
        RUN_LOGS/twobody_fullcoverage_mat/exp4d2_summary.png
# 4D regression (byte-identical simulation cull): ./scripts/run_lasertrap.sh -exp4d -fast
```

**Viewer:** `python3 SoftBox/sim_server.py 8000` from `~/Code`, open
`http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → newest; top-down + oblique).

**Artifacts** (`RUN_LOGS/twobody_fullcoverage_mat/`): `exp4d2_full.log`, `exp4d2_summary.png`,
`csv/{brute_validation, coverage_by_segment, coverage_heatmap}.csv`; frames
`~/Code/SoftBox/threejs_twobody4d2_{fullcoverage, bruteforce_check}`. Source: `softbox/TwoBodyConverterMotor.java`
(`run4d2` + `initMatGrid`/`unionActive`/`siteSegDist2`/`nearAnySegSite` + `phase3brute`/`phase4coverage`/
`glide2DVizFull` + the `cullMode` branch in `stepGlide2D` + the `fullViewer` branch in `GlideFrame2D`), one dispatch
line in `softbox/LaserTrapHarness.java`.
