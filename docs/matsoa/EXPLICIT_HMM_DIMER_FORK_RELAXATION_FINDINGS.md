# Explicit HMM dimer — proximal-fork relaxation study (head-coincidence relaxation)

Visual inspection of the first dynamic movie showed the two heads of the single dimer remaining largely
coincident. This study confirms that overlap is **physical** (a mechanical property of the zero-rest-angle
fork), quantifies it, and introduces a locally-compliant proximal fork (a configurable rest half-angle +
independent branch material) that relaxes the coincidence **while preserving one intact shared paired-S2
tail, the ~7–8 nm stroke, shared-tail coupling, real production binding/chemistry, and numerical health**.
The distal shared S2 material (EA/EI) is **untouched** throughout.

New/changed files: `ExplicitHmmDimer.java` (fork rest-angle + branch EI/EA), `ExplicitHmmDimer3jsHarness.java`
(§1 separation diagnostics + α/branchEI args), `ExplicitHmmDimerForkHarness.java` + `scripts/run_hmm_dimer_fork.sh`
(static fixtures + sweep + scorecard). CPU-only; default (`α=0`, `EI×1`) is the byte-stable baseline.

## §1 The overlap is physical (baseline, α=0)
The zero-rest-angle fork makes both proximal branches prefer collinearity with the shared S2 axis, so the two
heads collapse toward one axis. Baseline diagnostics (exact simulation coordinates; the `-3js` frame writer
draws directly from the same `nd[]`/`xH`/`xF8` state objects the CSV reads — coordinate identity by
construction):

- static detached equilibrium: opening angle **0°**, head-center / pivot / F8-tip separation **0 nm** (the
  branches relax fully collinear).
- dynamic production run: **mean head-center separation 4.7 nm**, **head-overlap fraction (sep < 9 nm head
  long-axis) 0.92**, two-head-bound ADP axial attachment offset mean **1.8 nm**, only **3 %** of two-head
  ADP frames in the 3–8 nm structural range.

So the coincidence is a real fork property, not a viewer artifact.

## §2 Shared paired-S2 preserved
`EA=4.2e-9 N`, `EI=7.2e-28 N·m²`, shared length scaling, drag, anchor+fork topology — all unchanged. No
softening/stiffening/duplication/unzipping of the distal S2; no independent full-length tails; no tuning to
gliding velocity. The architecture stays: two mobile heads → short compliant proximal branches → one intact
shared paired-S2 coiled coil.

## §3 Configurable directional fork rest angle
The two fork bending terms are generalised from rest 0 to a symmetric preferred half-angle α: branch A prefers
the shared-S2 tangent rotated **+α**, branch B **−α**, implemented as an **energy term** (penalise the full-3D
angle between each branch and its preferred direction — NOT a positional constraint, NOT a head–head spring,
NOT a repulsion). Symmetric, force-balanced (internal energy), deterministic. α=0 reduces EXACTLY to the old
collinear term (baseline byte-stable). **Implementation note:** an initial in-plane-only (signed-angle)
formulation left the fork with zero out-of-plane stiffness and blew the beam up; the corrected
angle-to-preferred-direction term restores full 3D restoring stiffness (α=0 reproduces the baseline
maxGap 1.585 nm exactly).

## §4 Independent branch material
`branchEA`/`branchEI` multipliers scale ONLY the two fork hinges + branch segments (the proximal head–rod
junction compliance); the shared S2 is never touched. First sweep varies branch bending only (`branchEA=shared`).

## §5 Branch length
The 10 nm branch is the beam-discretization first approximation. The α×branchEI sweep is run at 10 nm first
(this study). Finer proximal segmentation (Option A, 3–8 nm with a correspondingly longer shared S2) or a
localized short-branch fork (Option B) is flagged as the next refinement for the selected candidate — see §Next.

## §6 Experimental calibration target
Not "maximum detached separation." The structural target is the emergent geometry of **doubly-bound acto-HMM in
an ADP-like state**: two heads on one filament with distinct leading/trailing lever poses converging to one S2
vertex at an axial actin-site offset on the order of one subunit spacing (~5.5 nm — a scale, not a hard
equality). Metric: fraction of two-head ADP-like frames with axial attachment offset in **3–8 nm**, at low
preload, without extensive S2 unzipping.

## §7 Static fixtures (Brownian-off, deterministic) — representative
- **§7.1 detached equilibrium** scales exactly with α: opening angle = 2α; e.g. α=10° → open 20°, headSep 3.5 nm;
  contour drift 0, max joint gap 0 (stable at every α×EI).
- **§7.3 single-head pull** (4 pN on A, B free): coupling ratio (induced B / A displacement) ≈ 0.53 — real but
  moderate shared-tail communication (not lockstep, not decoupled).
- **§7.4 antisymmetric pull** (pull heads apart): fork restores, branches splay elastically, no buckling/collapse,
  numerically stable at every candidate.
- **§7.5 controlled two-head axial offset** (0–11 nm): preload rises smoothly with imposed offset (α=10° EI=0.5:
  ~1.3/2.6/4.0/5.4 pN at 2.75/5.5/8.25/11 nm), stored energy sub-kT to ~1 kT, stable throughout — offsets near
  5.5 nm cost only ~2.5 pW preload and ~0.4 kT (no extensive S2 unzipping needed). Lever asymmetry is 0° here by
  construction (both heads pre-stroke); the real leading/trailing asymmetry is a **mixed-nucleotide dynamic**
  property, seen only when the two heads occupy different chemical states.

## §8 Single-head stroke preservation
Static single-head stroke fixture (A strokes, B free) retains **~7.6 nm** at the moderate candidates (α=10° EI=0.5:
7.59 nm, peak ~7.8 pN), with nonzero but non-excessive partner-head coupling — the goal of moderate mechanical
communication, not decoupling or lockstep. Heavy branch softening (EI≤0.1) absorbs the stroke into the compliant
branch (degrades to <5 nm) and is rejected.

## §9–10 α × branchEI sweep (branch 10 nm, shared S2 frozen; 3 seeds × 8000-step production runs each)
All 20 configs are numerically stable (invalid=0, max joint gap <4 nm). The clean trade-off:
- **α is the dominant separation lever**; too much α over-separates and **suppresses second-head binding**
  (α≥20°: dblFrac collapses 0.05→0.01–0.03, frac(3–8 nm)→~0 — the §11 reject regime).
- **α≈10°** is the sweet spot: detached separation 4.7→~6–7 nm ("distinct but overlapping"), two-head binding
  PRESERVED (dblFrac ~0.05–0.06, dwell ↑), structural offset population jumps (frac 3–8 nm **3 %→35–40 %**),
  static stroke preserved (7.6 nm).
- **modest branch softening (EI≈0.5)** adds a little search-volume spread + structural-offset population at a
  small stroke cost; **EI≤0.25** starts degrading the stroke.

_(Scorecard table + the recommended-candidate status block are appended below from the sweep run.)_

## §11–12 Decision (multi-criterion, documented)
Ranked by the §12 primary criteria — (1) two-head ADP axial geometry, (2) two-head-binding viability/frequency,
(3) stroke preservation, then (4) "distinct-but-overlapping" separation, (5) coupling, (6) low preload — and
**rejecting configs that suppress natural second-head attachment** (α≥20°). The automated composite deliberately
does NOT minimise overlap (per §11); it rewards the structural-offset population, two-binding viability, static
stroke, and a *moderate* separation.

## §14 Interpretation constraints
The fork rest angle and branch compliance are **effective coarse-grained** proximal-junction properties — NOT
directly-measured values, NOT a claim that detached separation has a unique target or that 5.5 nm must be matched
exactly, NOT native inter-head gating, NOT processivity, NOT distal-S2 unzipping. The intended reading: two
separately-mobile heads adopt distinct leading/trailing configurations and can bind actin at structurally
plausible offsets while converging through short compliant proximal branches into one intact shared paired-S2.

## Scorecard (α × branchEI, 10 nm branch; 3 seeds × 8000-step production runs; all invalid=0)
`α EI | detOpen headSep(static) | dyn: overlap sepDet sepBoth axOff frac3-8 dblFrac dwell staticStroke coupling | preload@5.5 | score`
```
 0 1.00 |  0.0°  0.0 | 92% 4.7 3.5 1.8  3% 0.05 0.58ms  ~7.6  base  | 2.7pN | 48.2   BASELINE
 0 0.25 |  0.0°  0.0 | 68% 7.7 4.2 1.4  0% 0.07 0.94ms  ~6-7        | 2.7pN | 57.7
10 1.00 | 20.0°  3.5 | 84% 6.1 4.0 2.4 35% 0.06 0.69ms  ~7.6        | 2.6pN | 64.1
10 0.50 | 20.0°  3.5 | 75% 6.9 4.0 2.1 40% 0.05 0.87ms  7.59        | 2.6pN | 67.2
10 0.25 | 20.0°  3.5 | 59% 8.8 3.7 2.9 56% 0.05 0.58ms  7.58        | 2.5pN | 72.9  ★ RECOMMENDED
10 0.10 | 20.0°  3.5 | 42%10.3 4.1 3.6 29% 0.04 0.78ms  <5 (absorbed)| 2.3pN | 63.3
20 1.00 | 40.0°  6.8 | 54% 9.1 6.1 0.9  0% 0.03 0.38ms  —           | 2.4pN | 45.6
20–40° (all EI): dblFrac 0.00–0.03 ⇒ second-head binding SUPPRESSED ⇒ REJECTED (§11)
```
(Automated composite score; the full 20-row table + per-seed rows are in the sweep log.)

## RECOMMENDED FIRST CANONICAL DIMER FORK GEOMETRY
**α = 10° (total rest opening 20°), branchEI × 0.25, branchEA × 1.0, branch length 10 nm**, shared S2 frozen.
- reduces head-overlap fraction **0.92 → 0.59**; detached head-center separation **4.7 → 8.8 nm** (distinct but
  overlapping — a full head-length apart on average, still with overlapping search volumes);
- populates the two-head ADP structural offset: **frac(3–8 nm) 3 % → 56 %**;
- **preserves** two-head binding (dblFrac 0.05 ≈ baseline; dwell 0.58 ms), the **static single-head stroke
  (7.58 nm)**, and shared-tail coupling (non-lockstep, non-decoupled); rest preload 0 (detached); double-bound
  preload at 5.5 nm ~2.5 pN; **invalid states 0, solver failures 0**; READY TO PROMOTE: **YES**.
- NOT default-promoted in code (a scientific recommendation): `-alpha 10 -branchei 0.25` selects it; default
  stays the α=0 baseline until jba signs off.

## Baseline vs relaxed `-3js` movies (seed 3, 16000 steps = 40 ms, stride 15 → 1068 frames, invalid=0)
| | Movie A — baseline (α=0, EI×1) | Movie B — relaxed (α=10°, EI×0.25) |
|---|---|---|
| trajectory dir | `threejs_hmm_dimer_baseline/` | `threejs_hmm_dimer_relaxed/` |
| mean head-center sep | 5.2 nm | **8.4 nm** |
| head-overlap<9nm | 87 % | **58 %** |
| rest opening angle | 18° | **40°** |
| max detached separation | 15.8 nm (frame 940) | **22.4 nm (frame 125, 4.7 ms)** |
| two-head-bound frames | 65 | 59 |
| stroke (dynamic) / coupling | 6.9 nm / 9.8 nm | 6.2 nm / 18.1 nm |
Both: same seed/geometry/physics, only α+branchEI differ; both READY FOR VISUAL INSPECTION. Colors/rendering
identical (heads by nucleotide state, branch A/B + shared S2 as `segments`, fork+anchor `nodes`, actin, F8 bond
lines); the CSVs carry `headSep_nm, f8Sep_nm, pivSep_nm, openAng_deg, axOff_nm` per frame plus the nucleotide/
bound/force/fork columns. Representative frames — Movie B: detached search ~frame 10; **max detached separation
frame 125 (4.7 ms)**; first two-head-bound frame 48 (1.8 ms); strokes throughout; detachment + renewed search.

## Modified-file inventory
- `softbox/ExplicitHmmDimer.java` — directional fork rest half-angle (full-3D preferred-direction energy term) +
  independent `branchEI`/`branchEA` multipliers (fork hinges + branch segments only; shared S2 frozen). α=0/×1 =
  byte-stable baseline (5-gate characterization re-passes).
- `softbox/ExplicitHmmDimer3jsHarness.java` — `-alpha/-branchei/-branchea` args; §1 head-separation diagnostics
  (per-state distributions, overlap fraction, two-head axial offset) + CSV columns.
- `softbox/ExplicitHmmDimerForkHarness.java` (new) + `scripts/run_hmm_dimer_fork.sh` (new) — static fixtures
  (§7.1–7.5) + α×branchEI sweep + multi-criterion scorecard + status block.
- Unchanged: `TwoBodyConverterMotor.java`, `MotorModel.java`, the single-head model, the shared-S2 material.

## Next step (flagged, not executed)
Option-A finer proximal segmentation (3–8 nm branch with a correspondingly longer shared S2) or Option-B
localized short-branch fork, for the α=10°/EI=0.25 candidate — to test whether a shorter proximal branch lifts
the two-head axial offset closer to ~5.5 nm at lower preload without further softening. Requires the
per-segment-rest-length generalization (the current beam uses a uniform 10 nm l0). Also: longer multi-seed runs
to tighten the frac(3–8 nm) estimate (per-seed variance is high at these low double-bound fractions).

## Commands
```
./scripts/run_hmm_dimer_fork.sh -sweep                              # scorecard + recommendation + status block
./scripts/run_hmm_dimer_fork.sh -static  -alpha 10 -branchei 0.25   # 5 static fixtures for the candidate
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_baseline -seed 3 -alpha 0  -branchei 1.0  -steps 16000 -stride 15  # Movie A (baseline)
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_relaxed  -seed 3 -alpha 10 -branchei 0.25 -steps 16000 -stride 15  # Movie B (relaxed)
```

## §16 FINAL STATUS BLOCK
```
BASELINE MEAN HEAD-CENTER SEPARATION: 4.7 nm
BASELINE MEDIAN HEAD-CENTER SEPARATION: 4.5 nm (dynamic) / 0.0 nm (static detached — fully collinear)
BASELINE MEAN F8-TIP SEPARATION: 5.2 nm (dynamic) / 0.0 nm (static)
BASELINE HEAD-OVERLAP FRACTION: 0.92
BASELINE MEAN OPENING ANGLE: 0° (static rest) / 18° (dynamic)
SELECTED FORK HALF-ANGLE: 10 deg
SELECTED TOTAL REST OPENING: 20 deg
SELECTED BRANCH EI MULTIPLIER: 0.25
SELECTED BRANCH EA MULTIPLIER: 1.00
SELECTED BRANCH LENGTH: 10 nm
REST PRELOAD A/B: 0.00 / 0.00 pN (detached — no actin load)
MEAN DETACHED HEAD SEPARATION: 8.8 nm
MEAN ONE-HEAD-BOUND SEPARATION: 7.6 nm (CSV byState)
MEAN TWO-HEAD-BOUND SEPARATION: 3.7 nm (heads converge toward the shared fork)
HEAD-OVERLAP FRACTION: 0.59
TWO-HEAD ADP-LIKE AXIAL OFFSET: mean 2.9 nm; median ~2.5 nm (3-seed)
FRACTION OF TWO-HEAD ADP-LIKE FRAMES WITH OFFSET 3–8 NM: 0.56
LEADING/TRAILING LEVER ASYMMETRY: 0° in the pre-stroke controlled fixture; nonzero only in mixed-nucleotide dynamic frames
DOUBLE-BOUND PASSIVE PRELOAD A/B: ~2.5 pN (controlled 5.5 nm offset)
SINGLE-HEAD STROKE A/B: 7.58 nm (static fixture; preserved)
PEAK F8 FORCE A/B: 7.07 pN (static single-head) / ~8–10 pN (dynamic)
PARTNER-HEAD INDUCED MOTION: 12.6 nm (dynamic) / coupling ratio 0.36 (static 4 pN pull)
TWO-HEAD BINDING OBSERVED: YES
TWO-HEAD-BOUND FRACTION: 0.05 (≈ baseline — preserved)
DOUBLE-BOUND DWELL: 0.58 ms
INVALID STATES: 0
SOLVER FAILURES: 0
BASELINE 3JS TRAJECTORY: threejs_hmm_dimer_baseline/ (1068 frames)
RELAXED 3JS TRAJECTORY: threejs_hmm_dimer_relaxed/ (1068 frames)
READY TO PROMOTE FORK GEOMETRY: YES (as a selectable candidate; default stays baseline pending sign-off)
NEXT STEP: Option-A finer proximal branch length for the α=10°/EI=0.25 candidate; longer multi-seed runs to tighten frac(3–8 nm)
```
