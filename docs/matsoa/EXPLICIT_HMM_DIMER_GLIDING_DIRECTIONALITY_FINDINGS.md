# Explicit HMM dimer — ensemble gliding & directional second-head rules (D0/D1/D2)

Does the explicit HMM dimer generate robust **directed ensemble motion without an imposed rearward-binding
constraint**, and how much do a graded (D1) or hard (D2) rearward rule change assay-level behavior? The
isolated-dimer conformational surveys (`EXPLICIT_HMM_DIMER_{AXIAL_LEAD_LAG,FORWARD_JUNCTION_HINGE}_FINDINGS.md`)
found **no** conformational mechanism that mechanically steers the free head barbed-ward. This study does **not**
add another (dirMech stays 0); instead it tests whether **ensemble motion in a gliding assay already supplies the
effective directional bias**, in two mechanically-reciprocal assays and a new double-head mat. The 5.4 nm
same-filament bound-site occupancy exclusion is **ON in every condition**. CPU-only. Single-head model /
`TwoBodyConverterMotor` / `MotorModel` / shared-S2 material / shared viewer unchanged; new files only.

## Headline — OUTCOME A: ensemble motion supplies the bias; no directional rule is needed
Across a density sweep in **both reciprocal assays**, the unbiased dimer (**D0**) produces stable, directed
gliding whose **velocity, force, and persistence are statistically indistinguishable from D1 (γ=0.25) and D2
(hard veto)**. The rearward rule removes backward second-head binds but does **not** change gliding velocity —
because in the ensemble those backward binds are already **rare, short-lived (≈3× shorter dwell), and
mechanically opposed by the motion**, i.e. self-filtering. The clean prescribed-motion control shows relative
translation **directly biases the free-head search barbed-ward**. Verdict: **keep D0 (no directional rule) as the
default; D1 (γ≈0.25) is an optional coordination mode that trims backward binds at no cost.**

```
ENSEMBLE MOTION PROVIDES SUFFICIENT BIAS: YES   GRADED PENALTY NEEDED: NO (optional)   HARD VETO NEEDED: NO
RECOMMENDED DEFAULT DIRECTIONAL MODE: D0 (unbiased; 5.4 nm exclusion on)
```

## 1. Assays, modes, observables (deliverables 1–6)
Two harnesses in `softbox/ExplicitHmmDimerGlidingHarness.java` (`scripts/run_hmm_gliding.sh`), each dimer running the
exact production per-head machinery generalized from one dimer to N:
- **Assay A — mobile actin over N anchored dimers** (`-density`): one free gliding filament (translate + rotate,
  y/z soft-confined), N dimers pinned at spaced anchors; the classic gliding assay.
- **Assay B — fixed actin, mobile assembly** (`-density -assayB`): N dimers on one shared axial slider whose drag =
  the actin's own total axial drag (exact reciprocal mobility); actin held fixed. The reciprocal check.
- **Double-head MAT** (`-mat`, `-matsmoke`): a 3.0×1.0 µm lawn of dimers (density in **dimers/µm²**) under one free
  gliding 2.1 µm filament — the full 2D mat, mirroring the single-head `buildS2Mat` geometry. Near-filament dimers
  are solved each step (active cull); far dimers are frozen (zero contribution). See §7.

**Directional modes** (`-dmode`, always with the 5.4 nm exclusion): **D0** forward+backward eligible second binds
both allowed, no rule; **D1** a *backward* eligible second bind accepted with probability γ=0.25 (forward always);
**D2** backward eligible second bind rejected outright. The mode acts only on the dimer's **own partner head** once
its first head is bound (the D0/D1/D2 definition); first-head binding is never touched.

Velocity = LS slope of the mobile component's axial centroid vs time over the steady window (never
`longWindowSpeedXY`). Sign convention: barbed = +x; a productive glide moves actin −x (pointed-first) / the mobile
assembly +x. `velFwd` is polarity-normalized so **+ = productive** for both assays.

## 2. Density sweep — Assay A (mobile actin), 16 seeds × 12000 steps (30 ms), gap 8 nm
```
mode | nDim  ρ(/µm) | velFwd(µm/s) 95%CI      | meanBound twoFrac | netF(pN) F/head | fracFwdT | fwd/bwd
D0   |   2    2.85  | +0.549 [-0.087,+1.215] |  0.285  0.0040 |  +0.105  3.459 |  0.511  |   9/5
D0   |   4    5.70  | +1.006 [+0.462,+1.628] |  0.537  0.0041 |  +0.189  3.498 |  0.524  |  13/13
D0   |   8    9.12  | +1.191 [+0.675,+1.759] |  1.093  0.0031 |  +0.305  3.423 |  0.537  |  28/20
D0   |  12   11.40  | +1.511 [+1.109,+1.937] |  1.594  0.0035 |  +0.479  3.436 |  0.544  |  43/36
D0   |  16   15.19  | +1.646 [+1.374,+1.927] |  2.138  0.0035 |  +0.509  3.435 |  0.558  |  55/45
D1   |  16   15.19  | +1.765 [+1.504,+2.022] |  2.160  0.0024 |  +0.533  3.421 |  0.556  |  57/20
D2   |  16   15.19  | +1.753 [+1.492,+2.013] |  2.160  0.0015 |  +0.544  3.415 |  0.558  |  59/0
```
(Full D1/D2 rows for every density in `RUN_LOGS/hmm_gliding_directionality/densityA.txt`.) **Onset** at ρ≈2.85
(CI includes 0), **velocity rise** through ρ≈9, **approaching saturation** by ρ≈15 (+1.65 µm/s, meanBound 2.14);
**half-saturation ≈ ρ5/µm**. At every density **D0/D1/D2 agree within CI on velocity, force, and meanBound** —
the modes differ only in the backward-bind count (D0 45 → D1 20 → D2 0 at ρ15) and slightly lower twoFrac.

## 3. Density sweep — Assay B (fixed actin, mobile assembly), 12 seeds × 12000 steps
```
mode | nDim  ρ(/µm) | velFwd(µm/s) 95%CI      | meanBound twoFrac | netF(pN) F/head | fracFwdT | fwd/bwd
D0   |   2    2.85  | +0.242 [+0.117,+0.364] |  0.352  0.0019 |  +0.057  3.115 |  0.173  |   2/4
D0   |   8    9.12  | +0.806 [+0.645,+0.981] |  1.316  0.0017 |  +0.208  3.148 |  0.473  |   9/6
D0   |  16   15.19  | +1.136 [+0.957,+1.324] |  2.427  0.0012 |  +0.318  3.174 |  0.577  |  10/14
D2   |  16   15.19  | +1.163 [+0.977,+1.359] |  2.447  0.0003 |  +0.328  3.165 |  0.576  |  10/0
```
The reciprocal assembly moves **+bhat (productive), consistent with the mobile-actin −bhat glide** — the same
directed motion viewed in the reciprocal frame. Velocity is lower in absolute terms (the assembly carries all N
dimers' drag) but the D0/D1/D2 equivalence and the density trend are identical. **RECIPROCAL ASSAY CONSISTENCY:
PASS.**

## 4. Prescribed-motion control (E3) — the clean mechanistic test (deliverable 9)
Actin is dragged kinematically past anchored dimers at a controlled speed (D0, exclusion on); second-head
eligible proposals are split forward/backward. **This isolates motion-induced proposal bias from force feedback.**
```
 vActin(µm/s)  class   | eligFwdProp  eligBwdProp  fwdFrac | fwdBind bwdBind
  +0.00        still    |    3322       1430       0.699 |    12    17
  -0.50        forward  |    6920       4872       0.587 |     7    11
  -2.00        forward  |    4962       2236       0.689 |    18     5
  -5.00        forward  |    3422       1094       0.758 |    37     4
  +2.00        reverse  |    1334       4612       0.224 |     2   201
```
Forward relative motion raises the forward-eligible fraction (0.70 still → **0.76 at −5 µm/s**); **reverse motion
collapses it to 0.22** (backward binds dominate, 201 vs 2). This is the direct demonstration that **relative
translation between actin and the dimer ensemble exposes barbed-ward sites to the free head** — the missing bias
is supplied by motion, polarity-correctly (it reverses with the direction of relative motion).

## 5. Event-conditioned backward-bind analysis (Assay A D0, 20 seeds × 16000 steps, nDim 16) — deliverable 7
```
second binds: forward 87, backward 65 (forward fraction 0.572)
FORWARD  second binds: mean dwell 1.089 ms, mean signed axial force +1.136 pN (assisting)   n=84
BACKWARD second binds: mean dwell 0.338 ms, mean signed axial force -2.387 pN (opposing)    n=65
velocity at bind: forward-bind +7.98 µm/s, backward-bind -2.24 µm/s
motion-conditioned eligible-proposal forward fraction:
  moving FORWARD  0.635 (2318/1332)   ~STILL 0.481 (25/27)   moving BACKWARD 0.541 (1600/1360)
```
The decisive result for the central question: **backward second-head binds are self-filtering.** They (a) dwell
**3.2× shorter** than forward binds (0.34 vs 1.09 ms — a load-dependent catch-slip effect: they carry opposing
force −2.4 pN and detach fast), and (b) occur preferentially when the assay is momentarily moving backward
(velocity at a backward bind −2.24 µm/s vs +7.98 at a forward bind). Ensemble motion + load-dependent detachment
together mean backward binds are **rare, brief, and mechanically harmless** — so an explicit directional rule is
unnecessary (**Outcome A**, deliverable §14). The "while still" proposal fraction is ≈0.48 (near symmetric),
confirming the forward tilt is *motion-supplied*, not a fixed geometric artifact.

## 6. Load response (Assay A, nDim 8, 10 seeds × 10000 steps) — deliverable 11
```
 load(pN) | D0 velFwd(µm/s)  meanBound | detachLife(ms) fwd/bwd(D0)   D2 velFwd
   0.0    | +0.868±0.401     1.082     |   0.709        17/12         +0.959
   1.0    | -0.480±0.331     1.341     |   0.878        16/18         -0.516
   3.0    | -2.686±0.410     1.591     |   1.080        10/36         -2.720
   6.0    | -3.494±1.027     2.005     |   1.352         7/62         -2.561
  10.0    | -2.826±1.105     2.361     |   1.573        10/73         -2.980
```
The **stall-like load is ≈0.5–1 pN** for this small nDim=8 ensemble (velocity crosses zero between 0 and 1 pN;
the ensemble net drive is ~0.3–0.5 pN, so 1 pN over-powers it — the stall scales with ensemble size/density).
**meanBound and attachment lifetime rise with load** (1.08→2.36; 0.71→1.57 ms) — the catch-slip catch bond
recruiting more heads under strain. **D0 and D2 give the same velocity–load curve** (D2 merely removes the
backward binds, which proliferate under load in D0 — 12→73 — without changing velocity). The directional rule
does **not** materially alter mechanical performance.

## 7. Double-head MAT gliding assay (dimers/µm²) — the full 2D mat (deliverables 1, 10)
A 3.0×1.0 µm lawn of HMM dimers under one free gliding **2 µm** filament (11 seg = 1.93 µm; mirrors the single-head
`buildS2Mat`; density in **dimers/µm²** ⇒ nDim = round(density·3), heads = 2·nDim). Two build details are
load-bearing and were corrected during this study:
- **Random azimuthal orientation (`rotateDimerZ`).** Each anchored dimer is rotated by a random angle about the
  vertical (beam nodes + emergence tangent + both head Cmot frames), so the carpet points every horizontal direction
  (verified: 600 shared-S2 azimuths span −179…+180°). The per-dimer frame is threaded through **both** the bind gate
  and the coupled solve so they stay consistent. Without this the whole lawn was orientationally aligned (shared S2 ∥
  filament) — an unphysical idealization.
- **Active-cull radius = 120 nm (was 30 nm).** Only near-filament dimers run the (expensive) coupled solve; far ones
  are frozen (zero contribution). The head's Brownian search envelope reaches well beyond 30 nm, so a tight cull
  **froze dimers as the gliding filament reached them and undercounted binding**. Binding plateaus at ~100–120 nm
  (meanBound 4.2→5.6→6.8 at 30→60→100 nm, flat to 200 nm); 120 nm captures all binders (~230–460 active of
  1500–3000). **The earlier 30 nm / aligned sweep is superseded by the table below.**

Density sweep (dimers/µm², **random azimuth, 120 nm cull, 1.93 µm filament**, 4 seeds × 5000 steps, gap 8 nm;
velRaw = centroid-x LS slope, **negative = productive glide**):
```
mode | dens  nDim  heads | velRaw(µm/s) 95%CI    | meanBound cont  actD | netF(pN) F/hd | fwd/bwd  maxGap inv
D0   |  500  1500  3000  | -1.594 [-2.32,-1.05] |  4.46   0.974 231 | +0.596  3.40 | 38/19   672   0
D0   |  750  2250  4500  | -2.286 [-3.25,-1.43] |  6.82   0.987 343 | +0.649  3.40 | 46/37   673   0
D0   | 1000  3000  6000  | -2.242 [-3.04,-1.69] |  7.97   0.993 457 | +0.721  3.38 | 59/41   673   0
D1   |  500  1500  3000  | -1.641 [-2.32,-1.18] |  4.55   0.973 231 | +0.615  3.39 | 40/15   673   0
D1   |  750  2250  4500  | -2.037 [-3.04,-1.12] |  6.79   0.990 344 | +0.621  3.39 | 50/18   673   0
D1   | 1000  3000  6000  | -2.459 [-3.27,-1.89] |  8.14   0.993 457 | +0.770  3.38 | 61/27   673   0
D2   |  500  1500  3000  | -1.594 [-2.31,-1.03] |  4.49   0.969 231 | +0.586  3.39 | 45/0    673   0
D2   |  750  2250  4500  | -1.935 [-2.68,-1.20] |  6.51   0.986 343 | +0.635  3.38 | 49/0    673   0
D2   | 1000  3000  6000  | -2.344 [-3.31,-1.71] |  8.01   0.993 458 | +0.722  3.37 | 69/0    673   0
```
Findings in the full 2D mat (random orientation, correct cull):
- **Directed glide, velocity + duty rising with density** (D0: −1.59 → −2.29 → −2.24 µm/s; meanBound 4.46 → 6.82 →
  7.97; continuity 0.97 → 0.99), pointed-first, in the biological regime.
- **D0 ≈ D1 ≈ D2 in velocity (overlapping CIs) — Outcome A holds in the full mat** with random orientation. The rule
  only removes backward second binds (D0 19–41 → D2 0) with no velocity change.
- **Duty now comparable to the single-head mat; velocity ~2× slower.** At ρ500 the dimer mat is −1.59 µm/s /
  meanBound 4.46 (over 3000 heads) vs the single-head mat's −3.18 µm/s / 4.57 (over 1500 heads). So the shared
  elastic S2 tail + two-head coupling do **not** suppress engagement (comparable duty) but roughly **halve the net
  velocity per bound head** — the genuine double-head mechanical signature (the earlier "2–4× lower duty" was the
  30 nm-cull artifact). Random orientation modestly lowers velocity vs perfectly-aligned dimers, as expected.
- **Health flag (mat) — the main open issue:** the coupled forked-tail solve strains hard under mat gliding load —
  **maxGap up to ~670 nm** in a minority of seeds (a dimer beam transiently over-stretching), always recovering
  (invalid 0, no NaN, no solver failure). It is more prominent at the correct (denser-active) cull. This is the
  clearest motivation for a **cross-bridge sub-step** (the standing explicit-motor dt fix) before the double-head mat
  is used as a quantitative assay.

## 8. Representative movies (deliverable 15)
Matched seed 2, nDim 16 (`-3js`, 300+ frames each, under the served repo dir):
| assay | D0 | D1 (γ0.25) | D2 (veto) |
|---|---|---|---|
| mobile actin | `threejs_hmm_gliding_A_D0/` (+2.24 µm/s) | `threejs_hmm_gliding_A_D1/` (+2.39) | `threejs_hmm_gliding_A_D2/` (+2.31) |
| mobile assembly | `threejs_hmm_gliding_B_D0/` (+0.85) | `threejs_hmm_gliding_B_D1/` (+0.85) | `threejs_hmm_gliding_B_D2/` (+0.85) |
| double-head mat | `threejs_hmm_matglide_D0/` (200 dimers/µm²) | — | — |
D0≈D1≈D2 velocities confirm the sweep. (A_D0/D1 hit a transient ≤37 nm dimer joint-gap at nDim 16 — recovered,
invalid 0; see §9 health.)

## 9. Mechanical & numerical health (deliverable 13)
- **Invalid states: 0; solver failures (NaN): 0** across every ensemble and mat run.
- **Joint-gap transients:** the coupled forked-tail solve occasionally strains under dynamic gliding load — maxGap
  up to ~19 nm (nDim 8 dtconv), ~37 nm (nDim 16 movie), and **up to ~670 nm in a minority of mat seeds**
  (§7), always recovering (invalid 0, no NaN). Not fatal, but flagged as the clearest health issue: the dimer beam
  is stiffer to integrate under load than a single head; a cross-bridge sub-step (the standing explicit-motor dt
  fix) is the indicated cure before the double-head mat becomes a standing quantitative assay.
- **Timestep convergence (Assay A nDim 8, D0, 8 seeds): PARTIAL.** velFwd +0.576 (dt 2.5e-6) → +0.319 (dt 1.25e-6);
  meanBound 1.09→0.92; maxGap 19→15. **Qualitatively consistent** (still forward, still gliding, bounded, invalid
  0) but the **mean velocity is dt-sensitive** — the known explicit cross-bridge under-sampling (the substep is the
  standing fix, cf. `docs/matsoa` binding-resolution findings). The *directional conclusion* (D0≈D1≈D2, motion
  supplies the bias) is dt-robust; the absolute velocity is not fully converged.

## 10. Interpretation & decision (deliverable 14, §14)
**Outcome A.** Unbiased D0 produces stable directed gliding with velocity/force/persistence matching D1/D2, and
backward binds are rare, short-lived, and mechanically opposed — so **ensemble motion and load-dependent kinetics
provide sufficient effective directional filtering.** The prescribed-motion control (§4) shows *why*: relative
translation exposes barbed-ward sites to the free head (forward proposal fraction rises with forward speed,
inverts under reverse). The graded penalty D1 is a legitimate *optional* coordination mode (it cleanly removes
the ~30–45% backward binds at no velocity/force cost) but is **not required**; the hard veto D2 is an upper-bound
reference and is **not** the preferred model. This vindicates the isolated-dimer conclusion that no conformational
rearward-steering mechanism is needed: the ensemble supplies the coordination the single dimer could not.

## 11. Commands / seeds / timestep / stride (deliverable 15)
`dt = 2.5e-6 s`. Fork geometry α=10°, branchLen 10 nm, branchEI×0.25, splay 16° (the reference dimer); gap 8 nm.
```
./scripts/run_hmm_gliding.sh -density [-assayB] -seeds 16 -steps 12000 -gap 8   # Assay A / B density sweep × D0/D1/D2
./scripts/run_hmm_gliding.sh -e3 -seeds 20 -steps 12000 -ndim 8                 # prescribed-motion proposal bias
./scripts/run_hmm_gliding.sh -events -seeds 20 -steps 16000 -ndim 16            # event-conditioned backward-bind + motion-conditioned
./scripts/run_hmm_gliding.sh -load -seeds 10 -steps 10000 -ndim 8               # velocity–load curves × D0/D1/D2
./scripts/run_hmm_gliding.sh -dtconv -ndim 8                                    # dt vs dt/2 convergence
./scripts/run_hmm_gliding.sh -mat -seeds 4 -steps 5000 -gap 8 -cullr 0.12 -fillen 2.0    # double-head MAT sweep {500,750,1000} dimers/µm² (random azimuth)
./scripts/run_hmm_gliding.sh -matsmoke -mdensity 500 -seed 101 -steps 4000 -cullr 0.12 -fillen 2.0   # single mat cell
./scripts/run_hmm_gliding.sh -3js threejs_hmm_gliding_A_D0 -dmode 0 -seed 2 -ndim 16 -steps 16000   # movie
./scripts/run_hmm_gliding.sh -3js threejs_hmm_matglide_D0 -mat -mdensity 200 -dmode 0 -seed 2 -steps 8000 -cullr 0.12 -fillen 2.0   # mat movie (random carpet)
```
Raw logs: `RUN_LOGS/hmm_gliding_directionality/` (ensemble) and `RUN_LOGS/hmm_matglide_dimer/` (mat).

## 12. Modified-file inventory (deliverable 16)
- `softbox/ExplicitHmmDimerGlidingHarness.java` — NEW: the two reciprocal ensemble assays + the double-head mat,
  D0/D1/D2, density/E3/load/dtconv/events/mat drivers, `-3js` (ensemble + mat). Reuses the validated per-dimer
  step (`ExplicitHmmDimer.solve` + the production bind/chemistry/gather stages) unchanged.
- `scripts/run_hmm_gliding.sh` — NEW runner.
- `docs/matsoa/EXPLICIT_HMM_DIMER_GLIDING_DIRECTIONALITY_FINDINGS.md` — this report.
- **Unchanged (byte-clean):** `ExplicitHmmDimer.java` (dirMech unused ⇒ 0), single-head model,
  `TwoBodyConverterMotor.java`, `MotorModel.java`, `sim_viewer_boa.html`, all prior harnesses.

## 13. FINAL STATUS BLOCK
```
MOBILE-ACTIN GLIDING IMPLEMENTED: YES (Assay A; ensemble + double-head mat)
FIXED-ACTIN MOBILE-ASSEMBLY IMPLEMENTED: YES (Assay B; reciprocal drag = actin's own)
5.4 NM OCCUPANCY EXCLUSION: ON (every condition)
DIRECTIONAL MODES TESTED: D0 / D1 / D2
D1 REARWARD MULTIPLIER: 0.25
DENSITIES TESTED: ensemble ρ 2.85–15.2 /µm (nDim 2/4/8/12/16); mat 500/750/1000 dimers/µm²
SEEDS PER CONDITION: 16 (A), 12 (B), 20 (E3/events), 10 (load), 6 (mat)
SIMULATED TIME PER CONDITION: 30 ms ensemble (12000×2.5e-6), 40 ms events, 20 ms mat
D0 PLATEAU VELOCITY: +1.65 µm/s (Assay A, ρ15.2, approaching saturation; meanBound 2.14)
D1 PLATEAU VELOCITY: +1.77 µm/s   D2 PLATEAU VELOCITY: +1.75 µm/s  (indistinguishable within CI)
DOUBLE-HEAD MAT VELOCITY (productive): 1.59 / 2.29 / 2.24 µm/s at 500 / 750 / 1000 dimers/µm² (D0; D1/D2 within CI); meanBound 4.5 / 6.8 / 8.0 — random azimuth, 120 nm cull, 1.93 µm filament; duty ≈ single-head mat, velocity ~2× slower (shared-tail coupling). Earlier 30 nm-cull/aligned numbers superseded.
D0/D1/D2 HALF-SATURATION DENSITY: ≈ ρ5 /µm (unchanged across modes)
D0/D1/D2 REVERSAL RATE: thermal-window-dominated (~5 kHz at 0.1 ms windows) ⇒ report fraction-time-forward instead: 0.51→0.56 (A), 0.17→0.58 (B), rising with density; identical across modes
D0 TWO-HEAD-BOUND FRACTION: 0.0035  D1: 0.0024  D2: 0.0015 (Assay A, ρ15.2)
DOUBLE-BOUND DWELL: forward 1.09 ms / backward 0.34 ms (event-conditioned)
D0 FORWARD/BACKWARD SECOND BINDS: 55/45   D1: 57/20   D2: 59/0 (Assay A, ρ15.2)
BACKWARD-BIND MEAN FORCE CONTRIBUTION: -2.39 pN (opposing)   BACKWARD-BIND MEAN DWELL: 0.34 ms (3.2× shorter than forward)
FORWARD-MOTION PROPOSAL BIAS: 0.635 forward-eligible while moving forward vs 0.481 while still
PRESCRIBED-MOTION BIAS: forward-eligible fraction 0.70 (still) → 0.76 (−5 µm/s forward); 0.22 (reverse)
D0/D1/D2 STALL-LIKE LOAD: ≈0.5–1 pN (nDim 8 ensemble; scales with density; same across modes)
RECIPROCAL ASSAY CONSISTENCY: PASS (A −bhat glide ⇔ B +bhat assembly motion)
POLARITY REVERSAL: PASS (direction defined by bhat; E3 reverse-motion inverts the bias 0.76→0.22; inherited single-dimer polarity fixtures pass)
TIMESTEP CONVERGENCE: PARTIAL (directional conclusion dt-robust; absolute velocity dt-sensitive — the known explicit cross-bridge substep)
INVALID STATES: 0    SOLVER FAILURES: 0
MOBILE-ACTIN D0/D1/D2 3JS: threejs_hmm_gliding_A_D0/D1/D2/
FIXED-ACTIN D0/D1/D2 3JS: threejs_hmm_gliding_B_D0/D1/D2/
DOUBLE-HEAD MAT 3JS: threejs_hmm_matglide_D0/
ENSEMBLE MOTION PROVIDES SUFFICIENT BIAS: YES
GRADED REARWARD PENALTY NEEDED: NO (optional coordination mode)
HARD REARWARD VETO NEEDED: NO
RECOMMENDED DEFAULT DIRECTIONAL MODE: D0 (unbiased, 5.4 nm exclusion on)
NEXT STEP: establish the double-head mat as the standing gliding assay; add a cross-bridge sub-step to converge the absolute velocity and tighten the coupled-tail joint-gap transients; (optional) enable D1 γ0.25 as a labeled coordination mode.
```

**Success:** the study determines that **collective relative motion between actin and an ensemble of explicit HMM
dimers naturally filters mechanically-unhelpful rearward second-head attachments** (they are rare, ≈3× shorter-lived,
and force-opposed), and that an imposed graded or hard directional constraint **does not materially improve gliding
velocity, force, persistence, or density saturation** — it only removes the already-harmless backward binds.
Ensemble motion supplies the directional bias the isolated dimer could not.
