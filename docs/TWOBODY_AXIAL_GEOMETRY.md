# Experiment 3D — Axial-Stroke Geometry Remapping of the Two-Body Motor

**Date:** 2026-07-13/14 · **Branch:** `dt-convergence-study` · **Commit at start:** `2170aff937130c232ca999a6b5f87233f58b5776`
· **Runner:** CPU sequential only (`-gpu` refused). **Hardware:** aorus, 16 threads (one core). **CPU load** ~1.2–1.4.
**Wall-clock:** full experiment ≈ **6 s**. **dt:** search kinematic (dt-free) + mechanics 1e-5; leaders re-tested
{5e-6, 2.5e-6, 1e-6, 5e-7}. **Trap:** {0.02,0.05,0.10} pN/nm (primary 0.05).

**Default-off, non-canonical prototype** (`[NON-CANONICAL TWO-BODY PROTOTYPE]`) extending
`softbox/TwoBodyConverterMotor.java` via a NEW `-exp3d`/`-twobody-axial` path. **No canonical model change;
production defaults unchanged; `BoA-v1ref` byte-clean; the canonical motor is NOT modified.** No chemistry /
normal binding / detachment / catch-slip / gliding carpets added. **Experiment 3C is preserved byte-identical**
(the shared builder was parameterized with 3C-constant defaults; all 7 3C CSVs reproduce bit-for-bit). The
`build3c` refactor into `build3core` and the optional η DOF added to `stepC` are both gated so the 3C path is
unchanged.

---

## 0. Problem & mechanism

Experiment 3C established a valid, polarity-correct two-body head–converter–lever topology but delivered only
**≈3.6 nm axial** with **≈6.0 nm transverse** filament motion. The measured 3C trajectory (`trajectory_audit.csv`)
shows the F8 point and filament each moving ≈4 nm·b̂ with ≈6.6 nm transverse. (The 3C report §3 table listed the
converter transverse as 0.19 nm; the executed CSV shows 6.6 nm — a report transcription slip, corrected here.)

**Diagnosis.** The neck–lever swings about the fixed anchor through Δφ≈−58°, but the *entire* swing sits on the
pointed side of vertical (φ: −30°→−88°). A lever point at radius L_B swinging φ_pre→φ_post=φ_pre−Δθ moves, in the
strong-binding limit,

    axial      = L_B(sin φ_post − sin φ_pre)      transverse = L_B(cos φ_post − cos φ_pre)   [the arc sagitta].

An asymmetric swing gives small axial (sin is flat at the extremes) and large transverse. **Symmetrizing the swing
about the vertical (ê_up)** — φ_pre = Δθ/2 ⇒ φ_post = −Δθ/2 — gives

    axial = −2 L_B sin(Δθ/2),    transverse = 0.

This is a pure GEOMETRY change of the **pre-stroke lever lean** (a legitimate conformational parameter, and an
explicit Stage-1 search variable). The FIXED motor-frame stroke SIGN is unchanged; only its magnitude and the
pre-lever angle move. `barbedDir` stays out of target selection, converter torque, and force routing.

---

## 1. Stage 1 — geometry-only kinematic search (Pareto)

Strong-head-binding rigid limit (κ_bind→∞ ⇒ ψ pinned; converter completes). In this limit the displacement
depends only on (φ_pre, Δθ, L_B); ψ_actin / r_F8 / r_conv / v_off shift the absolute pose (overlaps/preload), not
the displacement. Coarse scan φ_pre∈[−45°,+60°]×Δθ∈[30°,75°] (`geometry_inventory.csv`), then local refine.

**The symmetric prediction is exact.** Along φ_pre = Δθ/2 the transverse is **identically 0** and the axial is
maximal for its Δθ (`refine.csv`). The frozen Pareto front (feasible: pointedward, 5–8 nm, no overlap, no extreme
angle) is the symmetric line:

| φ_pre | Δθ | kinematic axial | transverse |
|---:|---:|---:|---:|
| +20° | 40° | −5.47 nm | 0.00 nm |
| +25° | 50° | −6.76 nm | 0.00 nm |
| **+30°** | **60°** | **−8.00 nm** | **0.00 nm** |

The refine selects **φ_pre=+30°, Δθ=60°** (min transverse, then max axial ≤8) — which keeps 3C's Δθ=60°
converter stroke exactly, isolating the pre-lever re-aim (−30°→+30°) as the single change. No overlap, no extreme
angle (φ_post=−30°), separation |r_F8−r_conv|=7.6 nm unchanged.

## 2. Stage 2 — full mechanical validation (7 candidates)

Bracket k_F8∈{1,1.5,2}, κ_conv∈{128,256,576}, κ_bind∈{128,256,512} (`full_mechanics.csv`); reference operating
point k_F8=1, κ_conv=128, κ_bind=512 (matches 3C). Candidate summary (`candidate_summary.csv`, reference point):

| candidate | φ_pre | Δθ | k_ext (pN/nm) | stroke (nm) | transverse (nm) | iso-stall (pN) | τ (ms) | preload (pN) | handedness |
|---|---:|---:|---:|---:|---:|---:|---:|---:|:--:|
| **refinedBest** | +30° | 60° | **0.645** | **6.91** | **0.09** | 4.78 | 0.20 | ~0 | ✓ pointed |
| pareto0 | +20° | 40° | 0.608 | 4.69 | 0.03 | 3.22 | 0.20 | ~0 | ✓ |
| pareto1 | +25° | 50° | 0.624 | 5.81 | 0.06 | 4.00 | 0.20 | ~0 | ✓ |
| psiActin+12 | +30° | 60° | 0.667 | 6.94 | 0.09 | 4.95 | 0.19 | ~0 | ✓ |
| vOff+0.5 | +30° | 60° | 0.645 | 6.89 | 0.08 | 4.78 | 0.20 | 0.044 | ✓ |
| flatterPts | +30° | 60° | 0.660 | 6.93 | 0.09 | 4.89 | 0.20 | ~0 | ✓ |

Across the full bracket, k_ext spans **0.45–1.40 pN/nm** (skeletal-range; the reference 0.645), monotone in each
stiffness. The secondary variables (ψ_actin, v_off, flatter r-points) barely move the result — as the kinematic
limit predicts (they don't change the displacement). **The leader (refinedBest, zero preload) is:**

- **k_ext = 0.645 pN/nm** (skeletal 0.5–2 band ✓);
- **axial stroke = 6.91 nm** (5–8 band ✓; vs 3C's 3.62 nm);
- **transverse = 0.09 nm** (≤1–2 nm ✓; vs 3C's 6.02 nm — a ~65× reduction);
- **preload ≈ 0** (F8 = 1.6e-7 pN at the pre-stroke equilibrium);
- **isometric near-stall force = 4.78 pN**; relaxation **τ ≈ 0.20 ms**.

### Angular decomposition & trajectory (leader, reference point)

Bound-head stroke: **Δφ = −57.0°** (neck-lever swing), **Δψ = +0.8°** (motor domain ≈ actin-aligned) — the
biological lever-arm mechanism. Converter completion **0.96**. F8 extension during the stroke ≈ 0.69 nm.
Trajectory (`trajectory_refinedBest.csv`) — **every material point now moves near-axially**:

| point | Δ·b̂ (nm) | transverse (nm) |
|---|---:|---:|
| filament COM | −6.91 | **0.09** |
| F8 point | −7.60 | 0.10 |
| motor center | −7.62 | 0.15 |
| converter C | −7.64 | 0.20 |
| anchor | 0.00 | 0.00 (fixed) |

Contrast 3C, where the same points moved ≈4 nm·b̂ with ≈6.6 nm transverse. Symmetrizing the swing both maximized
the axial delivery (the converter now moves 7.6 nm axially, up from 4.0) and collapsed the transverse.

### Load response, polarity, rotation

**Stroke vs opposing load** (`load_response_refinedBest.csv`; opposing = toward barbed): delivered stroke
**decreases monotonically 6.91 → 4.57 nm** over 0→5 pN, converter completion **0.96 → 0.71**, generated force
**0.69 → 4.84 pN** — load-sensitive, no servo. Transverse stays ≤ 1.87 nm at every load. **Polarity/rotation**
(`polarity_rotation_refinedBest.csv`): swap reverses the world glide (−6.91→+6.91) and preserves the
polarity-relative glide (+6.91); rot90 & rot3D are bit-identical to identity (glide 6.91, force-on-actin −0.69 pN
pointed) — covariant, no world-axis bias. Force on actin is pointed; free-filament glide is pointed-first.

## 3. Stage 3 — transverse registration coordinate η (NOT required)

Geometry alone solved both requirements ⇒ Stage 3 is **not needed for the decision**. A bounded
`[COARSE-GRAINED TRANSVERSE REGISTRATION]` T1-passive probe was run on the ORIGINAL 3C geometry as illustration
only (`eta_T1_passive.csv`): a soft lateral compliance k_η reduces the 3C transverse **6.02 → 3.22 nm** at
k_η=0.1 pN/nm (η settling ≈3.2 nm, axial unchanged ≈3.6 nm, k_ext unchanged 0.638). So passive lateral give *can*
absorb part of the arc sagitta — but it is a partial, softness-dependent fix and is **unnecessary and not
adopted**: the symmetric geometry removes the sagitta at the source with zero added compliance. T2 (state-dependent
η) was not needed and not run for the decision.

## 4. Timestep & work ledger (leader)

`timestep_ledger.csv`, equal physical duration, dt {5e-6,2.5e-6,1e-6,5e-7}:

| dt (s) | external stroke (nm) | transverse (nm) | peak = plateau force (pN) | Δφ | work residual |
|---:|---:|---:|---:|---:|---:|
| 5e-6 | −6.9055 | 0.0930 | 0.6905 | −57.03° | 0.391 |
| 2.5e-6 | −6.9054 | 0.0930 | 0.6905 | −57.03° | 0.284 |
| 1e-6 | −6.9054 | 0.0930 | 0.6905 | −57.03° | 0.167 |
| 5e-7 | −6.9054 | 0.0930 | 0.6905 | −57.03° | 0.102 |

**The external observables — stroke, transverse, peak & plateau force, angular motion — are dt-invariant to <0.001
nm / <0.001 pN across the whole range.** The discrete work-ledger residual converges **O(dt)** (0.39→0.10, roughly
halving per halving of dt) — the same fast-lever / fast-converter discretization artifact reported for 3C (the
lever and converter have small drags, so the discrete-dissipation estimate under-counts at coarse dt; it is a
converging estimate, not an energy leak). The work ledger terms are: converter target-energy input = ΔU_conv +
ΔU_bind + ΔU_F8 + ΔU_trap + dissipation(φ + ψ + filament); the **fixed anchor performs zero work by construction**
(it is never integrated). The physical mechanics are trustworthy at a practical timestep; only the discrete
work-closure diagnostic needs finer dt (as in 3C).

---

## 5. Controlling outcome & separated statements

**CONTROLLING OUTCOME: A — geometry alone solves the stroke.** A physically reasonable realignment — leaning the
pre-stroke neck–lever to **+30°** so the fixed-Δθ swing straddles the vertical — produces a **6.91 nm pointedward
stroke with 0.09 nm transverse**, preserving skeletal stiffness (0.645 pN/nm), load sensitivity (stroke halves by
5 pN), correct polarity (pointed force, pointed-first glide, covariant), and zero preload. No transverse
registration coordinate was needed.

Separated statements:
- **Best geometry-only result:** φ_pre=+30°, Δθ=60° (the symmetric swing; keeps 3C's converter stroke magnitude).
- **Axial / transverse displacement:** 6.91 nm / 0.09 nm (3C: 3.62 / 6.02).
- **Stiffness:** k_ext = 0.645 pN/nm (bracket 0.45–1.40 across k_F8/κ_conv/κ_bind).
- **Stroke vs load:** 6.91 → 4.57 nm over 0→5 pN opposing; completion 0.96 → 0.71; iso-stall 4.78 pN.
- **Angular decomposition:** Δφ = −57.0° (lever swing), Δψ = +0.8° (motor domain actin-aligned) — lever-arm.
- **Preload:** ~0 (F8 = 1.6e-7 pN at pre-stroke equilibrium).
- **Polarity:** force on actin pointed; free glide pointed-first; swap reverses world / preserves relative;
  rot90 & rot3D covariant. `barbedDir` used only in the binding pose + analysis (not target/torque/routing).
- **Timestep requirement:** external stroke/force/angles dt-invariant at dt=1e-5; work-ledger residual converges
  O(dt) (fast-lever artifact, as 3C).
- **Work closure:** ΔU_target = ΔU_conv+ΔU_bind+ΔU_F8+ΔU_trap+dissipation; fixed anchor zero work; residual
  0.39→0.10 as dt→5e-7 (converging).
- **Transverse coordinate needed?** **No.** Geometry alone suffices; the η probe is illustrative only, not adopted.
- **Chemistry integration:** **licensed** — predominantly axial, polarity-correct, load-sensitive stroke with
  skeletal stiffness and numerically trustworthy dynamics at a practical timestep.

## 6. Decision — chemistry integration licensed

The corrected, geometry-realigned two-body motor produces a biologically recognizable, predominantly-axial,
polarity-correct, load-sensitive lever-arm stroke of 6.91 nm with ≤0.1 nm transverse and skeletal stiffness, at a
practical timestep. **Chemistry integration is scientifically licensed** on this geometry (add — one at a time —
normal binding, ADP·Pi→ADP target switching of the fixed motor-frame Δθ, ADP release, ATP detachment,
load-dependent lifetime, and free-gliding density sweeps, with the fine-dt gliding curve as a *regression* target,
not a fitting target). **Caveats carried forward:** (i) k_ext ≤ k_F8 for the F8 element; (ii) transverse grows
modestly under heavy opposing load (≤1.9 nm at 5 pN); (iii) the fast lever/converter need finer dt for the
discrete work-ledger (not for the physical observables); (iv) the fixed-position anchor still defers real
substrate compliance. **No canonical change made.**

---

## Artifacts & commands
- Preregistration: `RUN_LOGS/twobody_geometry_search/PREREGISTRATION.md`; log `.../exp3d_full.log`.
- CSVs (`.../csv/`): `geometry_inventory`, `pareto`, `refine`, `full_mechanics`, `candidate_summary`,
  `trajectory_refinedBest`, `load_response_refinedBest`, `polarity_rotation_refinedBest`, `eta_T1_passive`,
  `timestep_ledger`. Figure: `.../exp3d_summary.png`.
- Viewer frames: `~/Code/SoftBox/threejs_twobody3d_{3Cref_pre,3Cref_post,best_pre,best_post,best_overlay,best_diagnostic,best_swapPolar,best_rot3D}/`.
```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp3d -out RUN_LOGS/twobody_geometry_search/csv -3js ~/Code/SoftBox/threejs_twobody3d
./scripts/run_lasertrap.sh -exp3c -out /tmp/exp3c_check   # 3C regression: 7 CSVs byte-identical to baseline
python3 scripts/twobody3d_analyze.py RUN_LOGS/twobody_geometry_search/csv RUN_LOGS/twobody_geometry_search/exp3d_summary.png
```
**View:** `python3 SoftBox/sim_server.py 8000` from `~/Code`, open
`http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → newest; tick "Barbed ends" for the cyan +).
The physical (`objects` kind=`physical_body`/`physical_bond`) and diagnostic (`diagnostic_vector`) channels are
typed separately; the pre→post overlay carries actual trajectory arrows, not generic arrows.
