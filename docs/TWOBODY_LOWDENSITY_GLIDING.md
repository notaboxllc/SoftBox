# Experiment 4C — first low-density gliding of the cycling two-body motor

**Date:** 2026-07-14 · **Branch:** `dt-convergence-study` · **Commit at start:** `705a66b3e3e14ff1354ba30b9dac7d05bccd2255`
· **Runner:** CPU sequential only (`-gpu` refused). **Hardware:** aorus, one core. **Wall-clock:** full experiment
≈ 5 min. **dt:** primary 2.5e-6, comparison 5e-6 (matched physical duration 0.20 s). **Surface confinement:**
kTr = 2.0 pN/nm (transverse), axial FREE.

**Default-off, non-canonical prototype** (`[NON-CANONICAL TWO-BODY PROTOTYPE]`), `-exp4c` /
`-twobody-lowdensity-gliding` in `softbox/TwoBodyConverterMotor.java`. **The 4A motor, canonical Lymn–Taylor
kernel, RNG behaviour, force ordering, validated two-body mechanics, and `BoA-v1ref` are untouched** (4C is new
methods only; the shared `Multi`/`stepMulti` gained one guarded filament-Brownian line that is byte-identical to
4B when off). No parameter was tuned. This is a **feasibility + mechanism** experiment — **not** a density sweep,
velocity fit, canonical-curve match, or tuning run.

---

## 0. Objective and controlling outcome

The first free-filament gliding tests of the complete cycling two-body motor: can a free filament stay recruited to
a sparse bed of independently cycling two-body motors, move in the correct polarity, and cycle without pathology?

**CONTROLLING OUTCOME: FEASIBILITY DEMONSTRATED — recruitment + correct polarity + completing strokes + stable
mechanics; motion is intermittent (recruitment-limited) at these low densities.** A free Brownian filament is
recruited to the bed (engagement scales cleanly with density), **glides pointed-end-first (correct polarity) at
every density** (per-stroke directed displacement +3.2 to +3.3 nm, dt-stable), the Pi-release strokes **complete
(~1.0)** before ADP release/detachment, the filament stays in the motor plane (RMS z/y drift ~1 nm, out-of-plane
tilt 0.1°), and there are **0 forbidden transitions**. At the tested sparse densities the engagement is
**intermittent** (continuity — fraction of time ≥1 motor bound — rises 0.05 → 0.08 → 0.15 with density; the longest
unbound gap shrinks 200 → 160 → 64 ms), so **sustained continuous gliding is not reached at ≤1000 motors/µm²** —
the low single-motor duty (~0.013, search+recovery-dominated, carried forward from 4B) is the recruitment limiter.
The **net velocity is Brownian-noise-limited** at this low duty over these run lengths; the **per-stroke directed
displacement** is the robust polarity/productivity signal.

---

## 1. Source map — the canonical gliding apparatus reused

| behaviour | canonical source | Experiment 4C use |
|---|---|---|
| motor-bed placement | `GlidingHarness` — a density-faithful strip of random anchors (`nMot = round(DENSITY·bedX·bedY)`) | random anchors over a `[−2.0, 0.6] µm × ±8 nm` strip; each placed by the 3E construction to reach `(ax, ay, 0)` |
| density units | `DENSITY` motors/µm² (areal) | same; nMot = density·bedX·bedY (10/21/42 at 250/500/1000) |
| filament initialization | rigid/chain filament along the assay axis | single rigid rod (~1 µm, the 3E/4A/4B filament) along +x, at the origin |
| filament Brownian motion | `brownTransScale = BTransCoeff` (1.0), `BRotCoeff` (0.5) | **ON** — translational + rotational thermal (`filBrown`); `setParams(dt, brownianForceMag(dt))` |
| surface confinement | soft z/plane confinement (`-zconfine`/`-matbox`; out-of-plane runaway is a known issue) | reuse `applyTraps3D` with **kAx = 0 (axial FREE) + kTr = 2 pN/nm** ⇒ transverse y,z pinned to the plane (~1 nm), x glides |
| force accumulation | `CrossBridgeSystem` CSR gather (segGather over boundSeg) | reused verbatim (the 4B path); every bound motor's reaction summed into `fil.forceSum` |
| single integration | filament integrated ONCE after all motor forces summed | **verified**: `stepMulti` does gather → Brownian → confine → `integrate` once → derive |
| polarity convention | velocity = LS slope of centroid on the filament axis; negative = pointed-leading = correct | per-stroke displacement · p̂ (>0 = pointed-first) as the robust signal; net COM·b̂ velocity secondary |
| velocity measurement | `velFitX` LS slope over long runs | net-displacement rate (Brownian-averaged over episodes) + the per-stroke directed displacement; per-episode OLS kept as a (noise-dominated) diagnostic |
| `-3js` export | SoA `segments`/`myosins` channels | `GlideFrame`: the filament as the actin segment + all bed motors as articulated `myosins` (bound colored), downsampled to ~1500 frames |

**Explicitly verified — the filament is integrated ONCE per step after all motor forces are summed** (the CSR
gather writes `fil.forceSum`, then Brownian + confinement add to it, then a single `RigidRodLangevinIntegrationSystem.integrate`).

**Implementation note (a real bug found + fixed):** the shared `stepMulti` never advanced the *filament's* RNG
step index, so with Brownian ON `BrownianForceSystem` re-drew the **same** force every step → a constant DC force
(a spurious ~165 µm/s drift, visible even in the 0-motor control). Fixed by advancing `fil.counts` each step
(guarded by `filBrown` ⇒ 4B stays byte-identical). Post-fix the 0-motor control shows proper bounded Brownian
motion (COM within ~±60 nm over 0.2 s, D_par ≈ 0.031 µm²/s) and ~0 net velocity.

---

## 2. Assay geometry and density conversion (fixed before production)

- **Filament:** one rigid rod ~1 µm along +x, at the origin, translational + rotational Brownian ON.
- **Surface:** transverse harmonic confinement (kTr = 2 pN/nm at both ends via `applyTraps3D`, kAx = 0) pins y,z to
  the motor plane (RMS ~1 nm) while the axial glide DOF is free.
- **Bed:** random anchors over `x ∈ [−2.0, +0.6] µm` (2.6 µm −x runway) × `y ∈ ±8 nm` (a density-faithful strip,
  the canonical convention), each placed by the 3E construction so its ideal pre-stroke head reaches `(ax, ay, 0)`;
  a motor binds only when the gliding filament passes within the 3E gate reach — **recruitment emerges from the
  gate**, not from placement. `barbedDir` does not enter the stroke sign.
- **Density conversion:** `nMot = round(density · 2.6 µm · 0.016 µm)` ⇒ **low 250 → 10, medium 500 → 21,
  high-low 1000 → 42 motors; control 0.** Reachable motors (|ay| within the plane-reach) ≈ 7/16/32.
- Same footprint, filament length, surface, and initial placement for all conditions.

---

## 3. Time step

Primary **dt = 2.5e-6**; comparison **dt = 5e-6** (matched physical duration 0.20 s, not matched step counts). The
4B carry-forward — native stroke mechanics dt-stable but duty ratio / ADP dwell still ~17 % different between these
steps — holds here: the polarity, stroke displacement, and completion are dt-stable; the engagement (avgBound)
carries the modest coarse-dt variation (§4.7).

---

## 4. The seven feasibility questions

Primary dt = 2.5e-6, 8 episodes × 0.20 s per condition.

### 4.1 Can a free filament remain recruited to a sparse bed? — **Yes, and it scales with density.**

| density (µm⁻²) | nMot | reachable | avgBound | continuity (frac ≥1 bound) |
|---:|---:|---:|---:|---:|
| 0 (control) | 0 | — | 0.000 | 0.00 |
| 250 | 10 | 7 | 0.047 | 0.05 |
| 500 | 21 | 16 | 0.093 | 0.08 |
| 1000 | 42 | 32 | 0.169 | 0.15 |

Engagement rises monotonically and roughly linearly with density; the control never binds (clean reference).

### 4.2 Does it move in the correct polarity? — **Yes: pointed-end-first at every density.**

Per-stroke directed filament displacement · p̂ (Brownian-free): **+3.19 / +3.34 / +3.32 nm** at 250/500/1000 (dt-
stable: +3.19 vs +3.39, +3.34 vs +2.80, +3.32 vs +3.25 at the two dt). Positive = the filament moves toward its
pointed end = **pointed-end-first glide, the correct polarity** (inheriting the 3C/3D/4A intrinsic handedness; not
`barbedDir`). The net COM·b̂ velocity trends slightly negative at high density (−0.45/−0.57 µm/s) but is
Brownian-noise-limited (see 4.7).

### 4.3 Is motion sustained, intermittent, or absent? — **Intermittent** at these sparse densities.

Continuity (fraction of time ≥1 bound) is 0.05/0.08/0.15; the longest unbound gap shrinks 200 → 160 → 64 ms with
density. Motion is present and productive (pointed strokes) but **not continuous** — the filament engages in
bursts separated by unbound intervals. Not absent (the control is absent; every motorized density engages).

### 4.4 How many motors are required for continuity? — **More than tested here.**

Even at 1000 motors/µm² (32 reachable) the continuity is only 0.15. The limiter is the **low single-motor duty
ratio (~0.013, search+recovery-dominated — 4B)**: continuity ≈ 1 − (1 − duty)^(reachable engaged), and with duty
~0.013 the reachable count must be much larger (or the duty higher) for continuous coverage. Continuous gliding is
recruitment-limited, not force-limited.

### 4.5 What fraction of the stroke completes before release/detachment? — **≈ 1.0 (essentially complete).**

Converter completion at the window after Pi release: **0.99 / 0.99 / 1.01** at 250/500/1000 (dt-stable). The
strokes fully complete their −30°→+30° converter swing before ADP release or ATP detachment — the ~1.25 ms ADP
dwell is long relative to the ~0.2 ms stroke, so the stroke is not truncated.

### 4.6 Excessive rotation, transverse drift, or loss of contact? — **No; well confined.**

RMS z drift ~1.0 nm, RMS y drift ~1.0 nm, RMS out-of-plane tilt 0.1° at all densities — the surface confinement
holds the filament in the motor plane, so there is no drift-induced loss of contact. (The intermittency in 4.3 is
recruitment/duty, not out-of-plane escape.)

### 4.7 Are the statistics stable with time step? — **Qualitatively yes; engagement carries a coarse-dt variation.**

| density | avgBound 2.5e-6 | avgBound 5e-6 | strokeDisp 2.5e-6 | strokeDisp 5e-6 | completion 2.5e-6 | completion 5e-6 |
|---:|---:|---:|---:|---:|---:|---:|
| 250 | 0.047 | 0.034 | +3.19 | +3.39 | 0.99 | 1.02 |
| 500 | 0.093 | 0.076 | +3.34 | +2.80 | 0.99 | 0.99 |
| 1000 | 0.169 | 0.206 | +3.32 | +3.25 | 1.01 | 1.01 |

Polarity (stroke displacement sign + magnitude) and completion are dt-stable; avgBound differs modestly between
the two steps (~15–20 %, noisy at these low counts) — the inherited 4B coarse-dt engagement/duty variation. The
**qualitative feasibility conclusions (recruitment, polarity, completion, confinement) are dt-stable.**

---

## 5. Phase 1 — `-3js` smoke visualizations

Watchable trajectories (fixed seed, ~0.15 s, downsampled to ~1500 frames each) were written for direct inspection:

```
~/Code/SoftBox/threejs_twobody4c_control    (0 motors — free Brownian reference)
~/Code/SoftBox/threejs_twobody4c_low        (250/µm²)
~/Code/SoftBox/threejs_twobody4c_medium     (500/µm²)
~/Code/SoftBox/threejs_twobody4c_highlow    (1000/µm²)
```

Each frame carries the filament as the actin segment (pointed→barbed) and the full motor bed as articulated
`myosins` (bound motors flagged + nucleotide-state-labelled). **View:** `python3 SoftBox/sim_server.py 8000` from
`~/Code`, open `http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → newest).

---

## 6. Controls

- **No-motor control (0 motors):** avgBound 0.000, 0 strokes, net velocity ~0 ± Brownian noise, gap = the full
  run — a clean free-Brownian reference (after the RNG-step fix, §1).
- **Chemistry:** 0 forbidden transitions at every density; every stroke is a legal ADP·Pi→ADP transition; ATP
  detachment and recovery run per-motor (inherited from 4A/4B).
- **Force accumulation:** the CSR gather (validated bit-exact in 4B, force balance ≤1e-7 pN) is reused unchanged;
  the filament integrates once per step after summation.

---

## 7. Classification

**Feasibility DEMONSTRATED (positive), motion recruitment-limited.** The complete cycling two-body motor glides a
free filament in the correct (pointed-first) polarity with completing strokes, stable in-plane confinement, and no
chemical or numerical pathology; engagement scales cleanly with density. It is **not yet a sustained glide** at
≤1000 motors/µm² — continuity < 1, limited by the low single-motor duty (search+recovery-dominated). This is the
expected outcome for a first low-density feasibility test and is not a failure (recruitment succeeds, polarity is
correct, mechanics are clean) — it localizes the next question to **duty/recruitment**, not force generation.

---

## 8. Recommendation for the next experiment

**Run one focused recruitment/continuity study** before any velocity calibration: the feasibility and polarity are
established, so the controlling unknown is now *why continuity stays < 1* — i.e. the low duty (~0.013) set by the
long off-filament ATP recovery (~10 ms) + the Brownian rebinding search. The cheapest next step is to measure
continuity vs (a) reachable-motor count at higher density / a wider reachable strip and (b) the search/recovery
duty, to find the density (or duty) at which coverage becomes continuous — then, once continuous, measure a proper
gliding velocity against the fine-dt canonical curve **as a regression, not a fit**. Do not begin that experiment
in this run. Carry-forward: the net velocity is Brownian-limited at low duty (use longer runs / higher duty for a
velocity number); the per-stroke directed displacement (~+3.3 nm pointed) is the robust polarity signal; keep
dt ≤ 5e-6 (engagement carries a coarse-dt bias).

---

## 9. Deliverables and reproduction

```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp4c -out RUN_LOGS/twobody_lowdensity_gliding/csv   # full experiment (~5 min, CPU)
./scripts/run_lasertrap.sh -exp4c -fast                                          # quick smoke
./scripts/run_lasertrap.sh -exp4c -smoke -3js ~/Code/SoftBox/threejs_twobody4c   # Phase-1 viewer frames only
python3 scripts/twobody4c_analyze.py RUN_LOGS/twobody_lowdensity_gliding/csv \
        RUN_LOGS/twobody_lowdensity_gliding/exp4c_summary.png
# 4A / 4B regressions (unchanged): ./scripts/run_lasertrap.sh -exp4a -fast ; ./scripts/run_lasertrap.sh -exp4b -fast
```

**Artifacts** (`RUN_LOGS/twobody_lowdensity_gliding/`): `exp4c_full.log`, `exp4c_summary.png`, and
`csv/{gliding_summary, dt_comparison}.csv`; viewer frames `~/Code/SoftBox/threejs_twobody4c_{control,low,medium,highlow}`.
Source: `softbox/TwoBodyConverterMotor.java` (`run4c` + `Multi`/`buildGlide`/`measureGlide` + `GlideFrame`; the
`filBrown` line in `stepMulti`), one dispatch line in `softbox/LaserTrapHarness.java`.
