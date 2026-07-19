# Explicit HMM dimer — short-branch (variable proximal branch length) study

Refinement of the proximal-fork relaxation candidate (`α=10°`, `branchEI×0.25`, branch 10 nm — see
`EXPLICIT_HMM_DIMER_FORK_RELAXATION_FINDINGS.md`). This study **generalizes the forked beam to a variable
proximal branch length** (3/5/6/8/10 nm, shared S2 = 40 − Lb) via **per-segment rest lengths**, and asks
whether a shorter, more biologically plausible proximal compliant region improves the emergent doubly-bound
ADP-like axial geometry (toward the ~5.5 nm structural scale) **without** weakening the intact shared paired-S2,
suppressing second-head attachment, degrading the ~7–8 nm stroke, or destabilizing the solver. The distal
shared-S2 material (EA/EI) is **frozen throughout**. The validated single-head explicit model
(`TwoBodyConverterMotor`/`MotorModel`) is **byte-unchanged**; the shared viewer is **not modified**. CPU-only.

## Headline result (honest, negative on the central hypothesis)
**A shorter proximal branch does NOT improve the emergent doubly-bound geometry.** In this architecture the
detached head-center separation is geometrically set by `≈ branch length × sin(fork angle)` (the pivots sit at
the branch tips), so **shortening the branch shrinks the head separation and therefore the structural-offset
population** — it does not lift the ADP-like axial offset toward 5.5 nm. The **10 nm branch (α=10°) remains the
best** candidate for the 3–8 nm offset population; **no branch length reaches a mean ADP axial offset of ~5.5 nm**
(all candidates sit at ~1.6–2.6 nm mean, median ~1–4 nm). The shorter branches (Lb 5–8 nm) are viable and
numerically stable **at branchEI×0.50** and preserve the stroke, but with a **lower** 3–8 nm offset population —
a biological-plausibility-vs-emergent-metric tradeoff, not an improvement. **READY TO PROMOTE SHORT-BRANCH
GEOMETRY: NO** (the 10 nm reference stands; the short branch is offered as a stable, more-shared-S2 alternative if
plausibility is weighted over the offset metric).

---

## 1. Variable proximal branch length — implementation (deliverables 1, 2)
`ExplicitHmmDimer.build(Ms,Ma,Mb, splay, dt, αDeg, brEI, brEA, branchLenNm)` (new overload). The proximal branch
contour `Lb=branchLenNm`; the shared S2 contour is `40 − Lb`, so each head's emergence→pivot path stays **40 nm**.
Implemented cleanly through **per-element rest lengths**, NOT coordinate distortion at a wrong 10 nm mechanical
length:

| element | rest length | stiffness |
|---|---|---|
| shared segment (×Ms) | `(40−Lb)/Ms` nm | `ks = EA / l0_seg` |
| branch segment (×Ma) | `Lb/Ma` nm | `ks = EA·brEA / l0_seg` |
| bending hinge | — | `kb = EI_eff / lrep`, `lrep = ½(l0_before + l0_after)` |
| clamped emergence | — | `kb = EI / (first shared seg rest len)` |
| node drag | — | fixed `6πη·aeta·rNode`, rNode = 5 nm (per node, unchanged) |

Documented changes (per §2 of the task): **stretch** now reads per-segment `segKs[]`/`segL0[]`; **bending** reads
per-hinge `hingeKb[]` (and `kbEmg` for the emergence); **drag** is unchanged (per-node Stokes, node count fixed);
**initialization** places nodes at the per-segment rest spacings; **node indexing / solver dimension** are
UNCHANGED (Ms=3, Ma=Mb=1 ⇒ 5 free nodes, n=19 always — only rest spacings + per-element stiffness change); the
**solver tangent** is the same FD oracle over the free-node DOF (structure unchanged); **viewer export** gets a
`-branchlen` arg and the shared-S2/branch beam segments are drawn straight from the (re-spaced) node coordinates.

**Exact reduction at Lb = 10 nm (deliverable 2): CONFIRMED byte-identical.** With Lb=10 every rest length is
10 nm, so `segKs/hingeKb/kbEmg` collapse to the old uniform-`l0` values. The 5 characterisation gates reproduce
the pre-refactor numbers bit-for-bit (gate 2 stroke −7.64 nm / 7.87 pN; gate 4 coupling Δ0.1049 pN / 0.1012 nm;
gate 5 ΣF = 1.60e-12 pN), and the dynamic seed-3 trajectory is unchanged (STROKE 7.68/5.23 nm, partner 11.162 nm,
maxGap 1.585 nm, invalid 0).

## 2. Option-A vs Option-B feasibility (deliverable 3)
- **Option A** = explicit finite proximal branch beam (Lb ∈ {3,5,6,8,10} nm). **Statically EXACT at every Lb**
  (detached/controlled/stroke/anti-pull max joint gap ≤ 0.02 nm, contour drift 0, stroke preserved).
- **Option B** = localized compliant fork (a very short 2 nm branch, `branchEA×1.0` stiff stretch ⇒ compliance
  carried by the fork hinge; the closest realizable analog of "zero-length branch + localized element").

`./scripts/run_hmm_dimer_shortbranch.sh -optionb`:
```
  variant                 Lb  brEI brEA | statGap medGap spk dynGap drift inv | stroke dblFrac frac3-8 | robustness
  A explicit branch      10  0.25 1.00 |   0.00   1.95  0   4.34  12.8  0 |  7.58  0.04   42% | STABLE
  A explicit branch       8  0.25 1.00 |   0.00   4.17  1  13.15  20.3  0 |  7.60  0.04   22% | FRAGILE (short-seg transient)
  A explicit branch       6  0.25 1.00 |   0.00   4.81  1  30.08  47.4  0 |  7.63  0.05   22% | FRAGILE
  A explicit branch       5  0.25 1.00 |   0.00   5.36  1  32.14  45.0  0 |  7.64  0.05   14% | FRAGILE
  A explicit branch       3  0.25 1.00 |   0.00  10.38  3  45.29  50.7  1 |  7.67  0.05   16% | FRAGILE
  A explicit branch      10  0.50 1.00 |   0.00   1.91  0   2.26   3.8  0 |  7.59  0.05   54% | STABLE
  A explicit branch       8  0.50 1.00 |   0.00   1.98  0   3.36   6.0  0 |  7.61  0.05   19% | STABLE
  A explicit branch       6  0.50 1.00 |   0.00   2.31  0   7.74  11.9  0 |  7.64  0.06   17% | STABLE
  A explicit branch       5  0.50 1.00 |   0.00   2.46  0   7.30  12.4  0 |  7.65  0.05   27% | STABLE
  A explicit branch       3  0.50 1.00 |   0.00   2.61  1  11.38  19.9  0 |  7.67  0.05   25% | FRAGILE
  B localized 2nm fork    2  0.25 1.00 |   0.00  26.98  5 225.88 271.4  2 |  7.68  0.06    0% | FRAGILE
  B localized 2nm fork    2  0.50 1.00 |   0.00   2.46  1  12.59  22.2  0 |  7.68  0.04   20% | FRAGILE
```
**Verdict:** Option A is statically exact at every Lb. Under the stochastic dynamic cross-bridge load, very short
segments with **soft** branch bending (EI×0.25, Lb≤5, and the 2 nm localized fork) develop large transient joint
gaps — the short-segment stiffness limit of the one-Newton-step-per-timestep solver. **Stiffer** branch bending
(EI×0.50) keeps even short branches robust (median per-seed gap ~2 nm, 0 spikes) down to Lb=5. A **true**
zero-length coincident-pivot localized element **cannot create head separation** in this architecture — the heads
carry world-fixed frames and the separation comes entirely from pivot placement (∝ branch length × sin α); a
zero-length branch collapses the heads onto one point. So **Option B is both geometrically limited and numerically
fragile** ⇒ **Option A with a moderate Lb (EI×0.50 if short) is the transparent, stable choice, and is adopted.**

## 3. Distal shared S2 frozen (deliverable 6 / task §4)
`EA=4.2e-9 N`, `EI=7.2e-28 N·m²` unchanged; only `segL0`/`segKs` for the shared segments are re-spaced when Lb
changes (the *material* EA/EI is identical). No softening/stiffening/duplication/unzipping. In the controlled
two-head offset fixtures the shared-S2 deformation stays ≤ 0.005 nm across all offsets (the branches, not the S2,
accommodate the imposed offset) — the intact shared coiled coil is preserved.

## 4. Primary matrix + scorecard (deliverables 4, 5, 11, 13)
`./scripts/run_hmm_dimer_shortbranch.sh -sweep` (5 seeds {2,3,5,7,11} × 8000 steps; controls first):
```
  Lb   S2  α  EI  | detSep detOvl 1bSep 2bSep | axMean axMed f3-8 | pre@5.5 dblFr dwell | stroke peakF coup | statGap medGap spk | status
  10   30  0 1.00 |   5.1    90%   4.3   3.6 |   2.1   1.6    9% |   2.7  0.04  0.47 |  7.70  7.93 0.75 | stat0.00 med 1.45 spk0 | LEGACY control
  10   30 10 0.25 |   8.9    57%   7.5   3.9 |   2.2   3.9   42% |   2.5  0.04  0.63 |  7.58  7.82 0.36 | stat0.00 med 1.95 spk0 | REFERENCE control
  ---
   3   37 10 0.25 |   4.8    88%   2.9   3.3 |   2.0   2.1   16% |   2.7  0.05  0.48 |  7.67  7.90 0.64 | stat0.00 med10.38 spk3 | fragile
   3   37 10 0.50 |   5.3    85%   2.7   3.0 |   2.2   2.9   25% |   2.7  0.05  0.63 |  7.67  7.90 0.78 | stat0.00 med 2.61 spk1 | stable*
   5   35 10 0.25 |   5.9    84%   4.4   3.3 |   2.0   2.1   14% |   2.6  0.05  0.54 |  7.64  7.87 0.53 | stat0.00 med 5.36 spk1 | fragile
   5   35 10 0.50 |   5.4    85%   3.8   3.2 |   1.8   1.5   27% |   2.7  0.05  0.59 |  7.65  7.88 0.69 | stat0.00 med 2.46 spk0 | STABLE
   6   34 10 0.25 |   6.2    83%   4.7   3.6 |   1.7   1.5   22% |   2.6  0.05  0.60 |  7.63  7.86 0.48 | stat0.00 med 4.81 spk1 | fragile
   6   34 10 0.50 |   5.5    86%   4.3   3.3 |   1.6   1.3   17% |   2.7  0.06  0.63 |  7.64  7.87 0.65 | stat0.00 med 2.31 spk0 | STABLE
   8   32 10 0.25 |   7.4    72%   6.1   4.3 |   2.0   1.1   22% |   2.5  0.04  0.52 |  7.60  7.84 0.41 | stat0.00 med 4.17 spk1 | fragile
   8   32 10 0.50 |   6.2    82%   5.3   4.1 |   2.1   1.5   19% |   2.6  0.05  0.53 |  7.61  7.85 0.59 | stat0.00 med 1.98 spk0 | STABLE
  10   30 10 0.25 |   8.9    57%   7.5   3.9 |   2.2   3.9   42% |   2.5  0.04  0.63 |  7.58  7.82 0.36 | stat0.00 med 1.95 spk0 | reference
  10   30 10 0.50 |   7.3    73%   6.5   4.0 |   2.5   3.1   54% |   2.6  0.05  0.84 |  7.59  7.83 0.53 | stat0.00 med 1.91 spk0 | best emergent
```
(`detSep/1bSep/2bSep` = detached / one-head-bound / two-head-bound mean head-center separation nm; `axMean/axMed` =
two-head ADP axial offset nm; `f3-8` = fraction of ADP two-head frames with 3–8 nm offset; `pre@5.5` = controlled
5.5 nm two-head preload pN; `coup` = deterministic 4 pN single-pull coupling ratio; `medGap` = median-over-seeds of
per-seed max dynamic joint gap; `spk` = #seeds whose max gap exceeded 10 nm.) An 8-seed × 12000-step confirmation
reproduced the ordering (Lb=10 EI×0.25 frac 51%, axMean 2.6; the short branches lower).

**Reading the scorecard:**
1. **frac(3–8 nm) is highest at Lb=10** (42–54%) and declines for shorter branches (Lb=8 19%, Lb=6 17%, Lb=5 27% —
   noisy at these low double-bound fractions, dblFrac~0.05). Short branches do not increase the structural-offset
   population.
2. **No candidate reaches a mean ADP axial offset near 5.5 nm** (all 1.6–2.6 nm). The 5.5 nm scale is not achieved
   by varying branch length.
3. **Detached separation ∝ branch length** (Lb 3→10: 4.8→8.9 nm at EI×0.25), confirming the geometric coupling —
   shorter branch ⇒ smaller separation.
4. **Stroke preserved everywhere** (7.58–7.67 nm static), preload@5.5 flat (~2.5–2.7 pN), so short branches cost
   nothing on stroke or preload.
5. **Coupling tightens as the branch shortens** (ratio 0.36 at Lb=10 → 0.64–0.69 at Lb=3–5) — still moderate
   (< 0.95, non-lockstep), a physically sensible "stiffer proximal junction transmits more."

## 5. Numerical health — a pre-existing dynamic-solver caveat, NOT a short-branch pathology
The **static** solver is exact at every Lb (gap 0). The **dynamic** production path exhibits **rare, seed-dependent
large transient joint-gap excursions** under the stochastic cross-bridge load. Critically, these are **present in
the byte-identical α=0 / EI×1 / Lb=10 legacy reference** (per-seed max gap: most seeds 1–3 nm, but seed 5 spikes to
15.9 nm; an 8-seed pool hit a >50 nm transient once, invalid=1). So the spikes are a property of the
one-Newton-step-per-timestep coupled solver, **not** introduced by the per-element refactor and **not**
short-branch-specific in a clean way. They are reported via the **spike-robust** `medGap` (median per-seed max gap)
and `spk` (spike incidence), not the noisy pooled max. Interpreted this way, the real signal is: **soft short
branches (EI×0.25, Lb≤8) elevate the spike rate; stiffer short branches (EI×0.50) are as robust as the reference.**
No run diverged to NaN (0 solver failures); the "invalid" frames are rare transients the solver recovers from.

## 6. Static detached equilibrium (deliverable 6 / task §6)
Deterministic Brownian-off detached equilibration is **stable at every candidate**: opening angle = 2α exactly,
no passive head preload (A=B=0 pN detached), contour drift 0, max joint gap 0, no collapse to collinearity, no
branch buckling, stored energy 0 at rest. Detached head separation scales with branch length (Lb 3/5/6/8/10 →
1.0/1.7/2.1/2.8/3.5 nm static at α=10°; the dynamic means are larger, 4.8–8.9 nm, from Brownian search spread).

## 7. Controlled double-bound structural geometry (deliverable 7, 8 / task §7)
Both heads pre-bound at controlled axial offsets 0–11 nm. Preload rises smoothly and is **≈ branch-length-
independent** (dominated by the F8 cross-bridge spring, not the tail): at 5.5 nm, preload A/B ≈ 2.5–2.7 pN for
**every** Lb — i.e. **the short branch does NOT increase the 5.5 nm preload** (deliverable 8: reference Lb=10 2.46
pN, Lb=6 2.66 pN — flat). Stored energy stays sub-kT to ~1 kT; the **shared-S2 deformation stays ≤ 0.005 nm** (the
branches, not the intact S2, take up the imposed offset); max joint gap ≤ 0.01 nm; stable throughout. Lever
asymmetry is 0° by construction here (both heads pre-stroke); the real leading/trailing asymmetry is a
mixed-nucleotide dynamic property.

## 8. Single-head stroke preservation + decomposed coupling (deliverables 9, 10 / task §8)
**Stroke preserved** at every candidate (static single-head A-strokes-B-free fixture): 7.58 nm (Lb=10) → 7.67 nm
(Lb=3), peak ~7.8–7.9 pN — the short, stiffer branch actually preserves the stroke marginally better (less throw
absorbed into a long compliant branch). None falls below the 6.5–7 nm floor.

**Partner-coupling decomposition (task §8 — the primary metric is the DETERMINISTIC induced-displacement ratio,
NOT raw dynamic excursion):**
1. **Pure mechanical partner response** (deterministic stroke fixture, Brownian off, fixed actin): partner
   head-center displacement 0.29–0.92 nm when one head strokes; the deterministic 4 pN single-pull coupling ratio
   is **0.36 (Lb=10) → 0.65 (Lb=6) → 0.64 (Lb=3)** — moderate, tightening as the branch shortens.
2. **Shared actin/filament translation** — present only in the dynamic run (the movable filament moves under load).
3. **Brownian displacement** — the detached/searching partner's thermal wander (dynamic).
4. **Partner-head chemistry-driven motion** — the partner's own stroke/binding when it cycles (dynamic).
5. **Total observed dynamic partner excursion** — the raw `partnerInduced` in the movies (9–18 nm) conflates 1–4
   and is therefore an over-estimate; it is reported but is NOT the coupling metric.
The primary coupling number is item 1 (the deterministic ratio ~0.36–0.65) — **moderate, non-lockstep,
non-decoupled**, exactly the design goal.

## 9. Natural dynamic production-path runs (deliverable 11) + structural-offset distributions (deliverable 12)
Each candidate: 5 seeds (sweep) / 8 seeds (confirmation) × ≥ 8000 steps of the **exact production per-head path**
(real dynamic actin, canonical half-open ownership, zero physical margin, production binding gates, per-head
Lymn–Taylor chemistry, chemistry-triggered stroke, catch-slip release, real F8 forces, real filament integration,
Brownian search, shared-tail dimer solve — only the two independent `s2SolveM` replaced by the coupled forked
solve). Recorded per run: detached/one/two-head-bound separation, overlap fraction, opening angle, 0/1/2-head
fractions, two-head dwell, ADP two-head axial offset (mean/median + fraction in 3–8 nm), per-head F8 force,
stroke, detachments, invalid states, solver failures (see the scorecard §4 + the event CSVs). Two-head binding
remains viable at every candidate (dblFrac ~0.04–0.06 ≈ baseline; dwell ~0.5–0.8 ms).

## 10. Experimental ranking (task §10) — outcome
Ranked by (1) two-head binding viable → (2) ADP axial offset toward 5.5 nm → (3) frac(3–8 nm) → (4) preload@5.5
not increased → (5) stroke preserved → coupling moderate → overlap improved → numerical health. **No short branch
out-ranks the 10 nm reference** on the primary (emergent-geometry) criteria: the offset population is highest at
Lb=10, and no branch length approaches 5.5 nm. Among genuinely short branches, **Lb=6 nm with branchEI×0.50** is
the best *stable* option (STABLE, stroke 7.64 nm, preload@5.5 2.66 pN, moderate coupling 0.65, shared S2 34 nm >
the reference's 30 nm) — but with a lower 3–8 nm population (~17%). It is a biological-plausibility-vs-metric
tradeoff, not an improvement, so it is offered as an alternative, not a promotion.

## 11. `-3js` visualization comparison (deliverable 14 / task §12)
Matched trajectories (seed 3, 16000 steps = 40 ms, stride 15 → 1068 frames, splay 16°, gap 3 nm, 12-seg actin;
identical camera/scale/coloring/actin geometry/frame stride). Each shows detached search → first-head binding →
second-head binding → power stroke → partner response → detachment → renewed search (all events present, invalid 0):

| | Movie A — legacy | Movie B — relaxed reference | Movie C — best short branch |
|---|---|---|---|
| geometry | α=0°, Lb=10, EI×1.0 | α=10°, Lb=10, EI×0.25 | α=10°, **Lb=6**, EI×0.50 |
| shared S2 | 30 nm | 30 nm | **34 nm** |
| dir | `threejs_hmm_dimer_shortbranch_A/` | `threejs_hmm_dimer_shortbranch_B/` | `threejs_hmm_dimer_shortbranch_C/` |
| stroke A/B (dyn) | 6.94 / 6.72 nm | 6.17 / 6.92 nm | 5.75 / 6.92 nm |
| peak F8 A/B | 9.04 / 9.96 pN | 8.11 / 9.44 pN | 10.59 / 10.37 pN |
| max joint gap | 1.585 nm | 1.954 nm | 3.314 nm |
| invalid | 0 | 0 | 0 |

CSV per frame (`dimer_events.csv`): `headSep_nm, f8Sep_nm, pivSep_nm, openAng_deg, axOff_nm`, nucleotide states,
bound states, per-head force, fork displacement, actin displacement. Representative frames (all three): detached
search ~frame 9; first accepted binding ~frame 46; two-head-bound ~frame 46–48; power stroke ~frame 47; detachment
~frame 95; then repeated re-search/re-bind through 40 ms.

## 12. Grey converter sphere — identification + removal (deliverables 15, 16, 17 / task §13, §14)
**Source (deliverable 15):** the translucent grey sphere at the converter/pivot region is the **fork node**,
emitted by the harness frame writer as a viewer `nodes` entry (`center = fork, r = 0.006 µm = 6 nm`). Verified by
inspecting the exported JSON: the r=6 nm grey sphere sits at the fork coordinate (0, −0.18, −25.15 nm), ~10 nm
from the two pivots (9.96/9.61 nm) and ~13 nm from the converters — squarely in the converter/pivot region. The
viewer renders every `nodes` entry as a grey, opacity-0.35 sphere (the same channel BoA uses for real protein
nodes); the intended meaning was a fork-junction marker. It is **redundant** — the fork is already drawn as the
vertex where the shared-S2 cylinder meets the two branch cylinders.

**Removal (deliverable 16):** the fork-node sphere is **default-OFF**; it is restored with `-forknode` for
debugging (deliverable: DEBUG TOGGLE AVAILABLE = YES). The common **anchor** sphere (r=9 nm) is kept; the common
**fork** is kept as the beam-cylinder junction; no F8/head/lever/branch/shared-S2/actin geometry is removed. **The
shared viewer `sim_viewer_boa.html` is NOT modified** (BoA relies on the generic grey-node rendering) — the fix is
in what the harness *emits*, in `ExplicitHmmDimer3jsHarness.FrameOut`. Verified in the exported frames: after =
1 node (anchor only); before (`-forknode`) = 2 nodes (fork + anchor).

**Before/after screenshots:** the browser-screenshot tooling was declined this session, so matched
before/after **trajectories** are provided instead of rendered PNGs, with the removal **verified programmatically**
in the frame JSON (node count 2→1; fork sphere absent). To capture the images: `python3 SoftBox/sim_server.py 8000`
from `~/Code`, open `http://localhost:8000/SoftBox/sim_viewer_boa.html`, pick `threejs_hmm_dimer_beforefork`
(before — grey sphere present at the fork) then `threejs_hmm_dimer_shortbranch_B` (after — sphere gone), same frame
(~60) and camera. Both are identical except the fork sphere.

**Rendering-object inventory (deliverable 17 / task §14) — the complete dimer render set:**
| object | channel | meaning | notes |
|---|---|---|---|
| actin filament | `segments` (r=Constants.radius) | the real dynamic actin | `isBarbedEnd` on the barbed terminal |
| shared S2 beam | `segments` motorSeg (col 0.2) | emergence→fork, intact paired coiled coil | one cylinder |
| branch A | `segments` motorSeg (col 0.0) | fork→pivot A | tinted distinct from B |
| branch B | `segments` motorSeg (col 1.0) | fork→pivot B | tinted distinct from A |
| F8 crossbridge A/B | `segments` motorSeg (r=0.0015) | head tip→actin attachment | only when bound |
| anchor (emergence) | `nodes` grey sphere (r=9 nm) | the common clamped anchor | **kept** |
| fork node | `nodes` grey sphere (r=6 nm) | fork-junction marker | **default-OFF** (`-forknode` to restore) |
| head A/B lever | `myosins.lever` (r=0.0022) | converter arm (pivot→C→head) | one per head |
| head A/B motor | `myosins.motor` ellipsoid (r=0.0045) | head domain, colored by nucleotide state | NONE=purple/ATP=yellow/ADP·Pi=orange/ADP=red |
| head A/B rod | `myosins.rod` | (the proximal branch) | **emitted INVISIBLE** — see below |

**Viewer cleanliness audit (task §14):** the one genuine duplication found was the **myosin `rod` overlapping the
branch beam segment** — both drew the coincident pivot→fork cylinder. Fixed by emitting the myosin rod
**invisible** (keeping the tinted beam-segment branch, which carries the A/B distinction and continuity into the
shared S2). No other duplicates: single fork/anchor markers, one F8 line per bound head, one head ellipsoid per
head, shared S2 fully visible; the head opacity 0.75 is intentional (domain reads as solid while the lever shows
through). No broad viewer redesign.

## 13. Interpretation constraints (task §15)
The branch length, fork half-angle, and branch EI are **effective coarse-grained** proximal-junction properties,
NOT directly-measured molecular values; 5.5 nm is a structural *scale*, not a hard equality target; detached head
separation has no unique experimental value; the dimer has no native inter-head biochemical gating, is not
processive, and the shared S2 does not extensively unzip. The correct reading: **two separately-mobile heads
connected through a short effective proximal compliant junction to one intact paired-S2 coiled coil; parameters
selected by emergent doubly-bound geometry, preserved stroke, viable second-head attachment, moderate coupling,
and numerical stability.**

## 14. Exact commands, seeds, timestep, duration, stride (deliverable 18)
- `dt = 2.5e-6 s`; sweep seeds {2,3,5,7,11} (×8000 steps) + confirmation seeds {2,3,5,7,11,13,17,19} (×12000);
  movies seed 3, 16000 steps, stride 15.
```
./scripts/run_hmm_dimer_shortbranch.sh -sweep                                   # primary matrix + controls + scorecard + status
./scripts/run_hmm_dimer_shortbranch.sh -optionb                                 # Option-A vs Option-B feasibility
./scripts/run_hmm_dimer_shortbranch.sh -static  -branchlen 6 -alpha 10 -branchei 0.50   # detailed static fixtures
./scripts/run_hmm_dimer_shortbranch.sh -offsets -branchlen 6 -alpha 10 -branchei 0.50   # controlled offsets
./scripts/run_hmm_dimer_shortbranch.sh -cell -branchlen 10 -branchei 0.25 -alpha 10 -seeds 8 -steps 12000  # high-stat cell
# matched movies (seed 3, 16000 steps, stride 15 → 1068 frames):
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_shortbranch_A -seed 3 -steps 16000 -stride 15 -splay 16 -gap 3.0 -nseg 12 -alpha 0  -branchei 1.0  -branchlen 10   # Movie A legacy
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_shortbranch_B -seed 3 -steps 16000 -stride 15 -splay 16 -gap 3.0 -nseg 12 -alpha 10 -branchei 0.25 -branchlen 10   # Movie B reference
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_shortbranch_C -seed 3 -steps 16000 -stride 15 -splay 16 -gap 3.0 -nseg 12 -alpha 10 -branchei 0.50 -branchlen 6    # Movie C short
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_beforefork    -seed 3 -steps 16000 -stride 15 -splay 16 -gap 3.0 -nseg 12 -alpha 10 -branchei 0.25 -branchlen 10 -forknode  # "before" (grey sphere ON)
```

## 15. Modified-file inventory (deliverable 19)
- `softbox/ExplicitHmmDimer.java` — variable proximal branch length via per-segment rest lengths + per-hinge
  stiffness (`segL0/segKs/hingeKb/kbEmg`, `TOTAL_NM`, `build(...,branchLenNm)` overload, `segLenOf`); bendEnergy /
  nodeForces / maxJointGap generalized. **Reduces byte-identically at Lb=10.**
- `softbox/ExplicitHmmDimer3jsHarness.java` — `-branchlen` arg + branch-aware `buildScene`; §13 fork-node sphere
  gated default-OFF (`-forknode`, `SHOW_FORK_SPHERE`); §14 duplicate myosin rod emitted invisible.
- `softbox/ExplicitHmmDimerForkHarness.java` — branch-length overloads on detached/singlePull/antiPull/controlled/
  stroke/dyn/dynAgg; per-segment stretchEnergy.
- `softbox/ExplicitHmmDimerShortBranchHarness.java` — **NEW**: the short-branch study (`-sweep`/`-static`/
  `-offsets`/`-optionb`/`-cell`), scorecard, ranking, status block, region energies + strains.
- `scripts/run_hmm_dimer_shortbranch.sh` — **NEW** runner.
- `docs/matsoa/EXPLICIT_HMM_DIMER_SHORT_BRANCH_FINDINGS.md` — **NEW**: this report.
- **Unchanged:** `TwoBodyConverterMotor.java`, `MotorModel.java`, the single-head explicit model, and
  `sim_viewer_boa.html` (the shared viewer is NOT modified).

## 16. FINAL STATUS BLOCK (deliverable 20 / task §17)
```
VARIABLE BRANCH LENGTH IMPLEMENTED: YES
10 NM REFERENCE EXACTLY PRESERVED: YES (5 gates + dynamic seed-3 byte-identical)
LOCALIZED FORK OPTION TESTED: YES (Option B — geometrically limited + numerically fragile; Option A adopted)
SELECTED BRANCH LENGTH: 10 nm (reference retained; best STABLE short alternative = 6 nm, EI×0.50)
SELECTED SHARED S2 LENGTH: 30 nm (reference; 34 nm for the 6 nm short alternative)
SELECTED FORK HALF-ANGLE: 10 deg
SELECTED BRANCH EI MULTIPLIER: 0.25 (reference) / 0.50 (short alternative + more robust)
DETACHED MEAN HEAD SEPARATION: 8.9 nm (reference) / 5.5 nm (6 nm short)
DETACHED HEAD-OVERLAP FRACTION: 0.57 (reference) / 0.86 (6 nm short)
ONE-HEAD-BOUND MEAN SEPARATION: 7.5 nm (reference) / 4.3 nm (6 nm short)
TWO-HEAD-BOUND MEAN SEPARATION: 3.9 nm (reference) / 3.3 nm (6 nm short)
ADP-LIKE AXIAL OFFSET: mean 2.2 nm; median 3.9 nm (reference) — no candidate reaches ~5.5 nm mean
FRACTION OF ADP-LIKE FRAMES WITH OFFSET 3-8 NM: 0.42 (reference) — highest of all candidates; short branches lower
CONTROLLED PRELOAD AT 5.5 NM A/B: 2.64/2.29 pN (reference) / 2.70/2.62 pN (6 nm short) — NOT increased by shortening
STATIC SINGLE-HEAD STROKE: 7.58 nm (reference) / 7.64 nm (6 nm short) — preserved at every candidate
PEAK F8 FORCE: 7.82 pN (static) / 8–11 pN (dynamic)
DETERMINISTIC PARTNER-COUPLING RATIO: 0.36 (reference) / 0.65 (6 nm short) — moderate, non-lockstep
DYNAMIC PARTNER MOTION DECOMPOSED: YES (deterministic mechanical vs actin translation vs Brownian vs partner chemistry vs total)
TWO-HEAD BINDING OBSERVED: YES
TWO-HEAD-BOUND FRACTION: 0.04 (reference) / 0.06 (6 nm short) — preserved
DOUBLE-BOUND DWELL: 0.63 ms (reference) / 0.63 ms (6 nm short)
MAX JOINT GAP: static 0.00 nm (exact, every Lb); dynamic — median per-seed ~1.9-2.3 nm (rare seed-dependent transient spikes, present in the reference too; 0 NaN)
MAX CONTOUR DRIFT: ~2-6 nm typical (transient)
INVALID STATES: 0 in the seed-3 movies; rare transient gap>50 nm frames at high seed count (solver recovers)
SOLVER FAILURES: 0 (no NaN/Inf)
GREY CONVERTER SPHERE SOURCE: the fork node emitted as a viewer `nodes` entry (r=6 nm grey sphere at the fork, ~10 nm from the pivots)
GREY CONVERTER SPHERE REMOVED: YES (default-OFF in the harness frame writer; anchor kept; fork shown by beam cylinders; shared viewer unmodified)
DEBUG TOGGLE AVAILABLE: YES (-forknode restores the fork sphere)
BASELINE 3JS TRAJECTORY: threejs_hmm_dimer_shortbranch_A/ (1068 frames)
10 NM RELAXED TRAJECTORY: threejs_hmm_dimer_shortbranch_B/ (1068 frames)
SHORT-BRANCH TRAJECTORY: threejs_hmm_dimer_shortbranch_C/ (1068 frames; Lb=6, EI×0.50)
READY TO PROMOTE SHORT-BRANCH GEOMETRY: NO — a shorter branch does not improve the emergent doubly-bound geometry (head separation ∝ branch length ⇒ shortening lowers the 3-8 nm offset population); the 10 nm reference stands
NEXT STEP: if biological plausibility (more shared S2) is prioritized over the offset metric, adopt Lb=6 nm EI×0.50 (stable, stroke-preserving); otherwise retain the 10 nm reference. To lift the ADP axial offset toward 5.5 nm, the lever needs a mixed-nucleotide leading/trailing asymmetry mechanism, not a shorter proximal branch.
```

**Success criterion (task §17):** a shorter proximal compliant region did **not** improve the natural doubly-bound
geometry toward the 5.5 nm structural scale (the geometric coupling of head separation to branch length prevents
it), though it preserves the shared S2, second-head attachment, the working stroke, and numerical stability. The
`-3js` rendering now clearly shows the motor geometry **without** the unexplained translucent converter sphere.
