# Explicit HMM dimer — dynamic one-dimer `-3js` visualization findings

A directly-viewable coded-geometry movie of ONE surface-anchored `explicit-hmm-dimer-s2-l40` going
through detached search → binding → ADP·Pi→ADP power stroke → coupled post-stroke motion → detachment →
renewed search, on a **real dynamic actin filament** through the **production per-head binding +
chemistry machinery**. NOT a hand-authored animation; NOT a replay of fixture coordinates; NOT the
fixed-actin F8 spring. New files only — the single-head explicit model + `ExplicitHmmDimer` core are
byte-unchanged; `TwoBodyConverterMotor`/`MotorModel` untouched.

New: `softbox/ExplicitHmmDimer3jsHarness.java`, `scripts/run_hmm_dimer_3js.sh`. CPU-only.

## Architecture (how it stays production-faithful)
A 2-motor `Glide2D` drives the exact stages of `TwoBodyConverterMotor.stepGlideS2` — **`unionActive`
cull → canonical half-open bind gate (`geom2D`/`nearestSeg2D`/`gate2D`, `bindMargin()`=1e-6 µm, ZERO
physical segment margin) → `cycleLymnTaylor` nucleotide chemistry → `thetaS4a` cocking → `placeHead2D` →
`CrossBridgeSystem.bondForces` F8h → CSR gather → `chainForces` → z-confine → filament
Brownian+integrate+derive** — with the **only** substitution being that the two independent per-head
`s2SolveM` tail solves are replaced by the coupled forked-tail `ExplicitHmmDimer.solve` (the two heads
share ONE S2 beam). Per-head converter/pivot state is bridged into the `Glide2D` each step; the
chemistry-driven θ_s (PRESTROKE at ADP·Pi → ADP_THETAS at ADP) drives the power stroke; the per-head
`forceDotFil`/`forceMag` are written back for the next step's catch-slip release. So binding, chemistry,
the stroke, detachment, and the filament dynamics are **100 % production**; only the shared-tail
coupling is the new dimer physics.

A gentle lateral (y) confinement mirrors the existing z-confine so the visible piece of the (long,
µm-scale) actin filament stays in the heads' plane — it remains fully dynamic (integrates, bends,
responds to motor load, full FDT Brownian); only its unbounded transverse random-walk (a finite-rod
artifact) is removed, sustaining repeated engagement.

## FINAL STATUS BLOCK (best natural trajectory)
```
3JS VIEWER WIRED: YES
REAL DYNAMIC ACTIN: YES
PRODUCTION BINDING PATH: YES
DETACHED SEARCH OBSERVED: YES
HEAD A BINDING OBSERVED: YES
HEAD B BINDING OBSERVED: YES
TWO-HEAD-BOUND STATE OBSERVED: YES
POWER STROKE OBSERVED: YES
PARTNER-HEAD COUPLING VISIBLE: YES
DETACHMENT OBSERVED: YES
MAX BINDING DISCONTINUITY: 3.02 nm  (finite force-onset, within the 6.35 nm max normal per-step tip
                                     motion; binding writes ONLY boundSeg+bindArc — no coordinate write)
PEAK F8 FORCE A/B: 9.04 / 9.96 pN
STROKE A/B: 6.94 / 6.72 nm   (partner-head induced via shared S2: 9.82 nm)
MAX JOINT GAP: 1.585 nm ; MAX CONTOUR DRIFT: 2.121 nm
INVALID STATES: 0
TRAJECTORY FILE: threejs_hmm_dimer/  (1068 frames)
EVENT LOG: threejs_hmm_dimer/dimer_events.csv
READY FOR USER VISUAL INSPECTION: YES
```

## Run parameters + exact command
- `dt = 2.5e-6 s`; **seed 3**; 16000 steps = **40 ms**; frame stride 15 (0.0375 ms) → **1068 frames**;
  splay 16°, initial gap 3 nm, filament 12 segments (~2.1 µm), full filament Brownian.
- **Exact command:**
  ```
  ./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer -seed 3 -steps 16000 -stride 15 -splay 16 -gap 3.0 -nseg 12
  ```
- Seed/placement search: `./scripts/run_hmm_dimer_3js.sh -scan` (every scanned seed reaches the full event
  set; seed 3 gives the most sustained engagement — bound-fraction 0.20, engaged across all four
  10-ms quartiles [83,52,16,12 bound frames/200], 48 two-head-bound frames). The single natural
  trajectory contains ALL required events, so a separate pre-bound "Movie B" is not needed (a pre-bound
  start is available by seeding both `nucleotideState=ADP` at a reachable pose if a stroke-focused clip
  is wanted; it would be labelled a pre-bound IC).

## Representative frame indices / times (open `threejs_hmm_dimer/frame_%06d.json`)
| event | frame | t (ms) |
|---|---|---|
| detached search | 9 | 0.34 |
| first contact / accepted binding | 46 | 1.73 |
| pre-stroke bound (ADP·Pi) | 46 | 1.73 |
| mid-stroke (mechanical relaxation) | 47–49 | 1.76–1.84 |
| post-stroke (ADP) | 47 | 1.76 |
| two-head-bound | 46 | 1.73 |
| detachment | 95 | 3.56 |
(the trajectory then re-searches and re-binds repeatedly through 40 ms — 214 bound frames total.)

## Viewer wiring (no viewer change required)
- **Two `myosins` entries** (head A / head B), each `{rod, lever, motor}`: rod = the proximal branch
  (pivot→fork), lever = the converter arm (pivot→head), motor = the head ellipsoid **colored by
  nucleotide state** (`NONE`=purple, `ATP`=yellow, `ADP·Pi`=orange, `ADP`=red) — so the chemistry and
  the stroke are visible on the heads.
- **Shared S2 beam + both branches** as `segments` (`motorSeg:true`) drawn straight from the dimer's own
  node coordinates (shared S2 = emergence→fork, neutral; branch A/B = fork→pivot, distinct age-ramp
  tints). **The fork is NOT approximated as two coincident single-head motors** — it is one shared node.
- **Fork node + common anchor** as `nodes` (grey spheres).
- **Real helical actin** via the existing actin renderer (`segments` with `isBarbedEnd` on the barbed
  terminal, `r = Constants.radius`).
- **F8 crossbridge** drawn as a thin `segments` line from the head F8 tip to the actin material
  attachment point (`e1 + bindArc·û`) whenever bound; absent when detached.
- All exported coordinates are **exactly** the simulation-state coordinates (host SoA), in µm.

## Physical sanity (all pass)
- common anchor fixed (clamped emergence, re-pinned each step); fork + branches continuous.
- **max joint/node gap 1.585 nm** (bounded, no contour explosion); **max contour drift 2.121 nm**.
- **peak F8 force 9.04 / 9.96 pN** per head — in the established explicit-motor range (single-head slice
  ~7.9 pN; modestly higher here under the dynamic cross-bridge load).
- **stroke 6.94 / 6.72 nm** per head — the myosin working stroke (single-head isometric characterization
  ~7.6 nm; slightly reduced here as the movable filament + shared tail take up part of the throw).
- **binding produces no coordinate discontinuity** (the gate writes only `boundSeg`+`bindArc`); the
  3.02 nm max at a binding instant is the finite cross-bridge **force onset**, well within the 6.35 nm
  largest normal per-step tip motion — no teleportation.
- **canonical half-open ownership, zero physical margin** (`bindMargin()`=1e-6 µm); every logged
  `bindArc ∈ [0, segLength]` — **0 invalid binding arcs**, **0 NaN/Inf/solver failures** over 16000 steps.
- **partner-head coupling** through the shared S2: a one-head stroke induces up to **9.82 nm** of partner
  head-tip motion (mechanically transmitted through the fork + shared beam) — the HMM shared-tail
  behaviour, visible in the movie as the partner head moving when its neighbour strokes.

## Event CSV (`threejs_hmm_dimer/dimer_events.csv`)
Per exported frame: `frame, t_ms, nucA, nucB, boundA, boundB, segA, arcA_nm, segB, arcB_nm, fA_pN,
fB_pN, forkDisp_nm, actinDisp_nm, nBound, maxGap_nm, contourDrift_nm` — the synchronized event log
(chemical states, per-head bound/segment/arc, per-head F8 force, fork + actin displacement, 0/1/2-bound
state, geometry-sanity columns).

## How to watch
`python3 SoftBox/sim_server.py 8000` from `~/Code`, then open
`http://localhost:8000/SoftBox/sim_viewer_boa.html` and pick the newest run (`threejs_hmm_dimer`).
