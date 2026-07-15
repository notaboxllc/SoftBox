# Experiment 3E — Two-Body Binding-Capture Integration (Brownian + stereospecific search)

**Date:** 2026-07-14 · **Branch:** `dt-convergence-study` · **Commit at start:** `2170aff937130c232ca999a6b5f87233f58b5776`
· **Runner:** CPU sequential only (`-gpu` refused). **Hardware:** aorus, 16 threads (one core). **Wall-clock:** full
experiment ≈ **7 s**. **dt:** search 1e-6, relaxation 1e-5, timestep control {5e-6,2.5e-6,1e-6,5e-7}. **Trap:** 0.05
pN/nm.

**Default-off, non-canonical prototype** (`[NON-CANONICAL TWO-BODY PROTOTYPE]`) extending
`softbox/TwoBodyConverterMotor.java` via a NEW `-exp3e`/`-twobody-capture` path. **No canonical model change;
production defaults unchanged; `BoA-v1ref` byte-clean; the canonical motor is NOT modified.** Experiments 3C & 3D
preserved byte-identical (all 3C CSVs reproduce bit-for-bit; the 3E code is new methods only). **No Pi release,
power stroke, ADP release, ATP detachment, catch-slip kinetics, or gliding carpets** — the motor is frozen in the
ADP·Pi pre-stroke state; the only new physics are Brownian search + stereospecific binding capture.

---

## 0. Model

**Objective.** Add normal Brownian motion + a stereospecific binding search that *naturally recruits* poses near
the validated 3D pre-stroke basin (φ_pre=+30°, ψ_actin≈0, θ_s=−30°, ~0 preload), WITHOUT snapping motors into
the pose after binding.

**Search.** The neck–lever Brownian-swings about the FIXED tail anchor: (φ,ψ) diffuse under FDT torques + the
converter spring on θ=ψ−φ (the common swing is free). Because the converter slaves ψ≈φ+θ_s, near φ≈30° the head
naturally presents ψ≈0 at the actin site — the stereospecific basin. The trap-held filament is the fixed target
(filament Brownian off, as in the optical-trap assay). Each step, a candidate attachment is evaluated in the
**LOCAL ACTIN MATERIAL FRAME** (all quantities via the frame vectors b̂/ê_up/ê_conv ⇐ the assay rotation Rm ⇒
rotation-covariant, no world-axis preference) against named, explicit gates:

| gate | quantity | default tolerance |
|---|---|---|
| distance | head F8-point ⟂ distance to the actin surface | < 3.0 nm |
| material coordinate | persistent bindArc (arc-length foot from end1) | interior only |
| binding-face orientation | \|ψ − ψ_actin\| | < 25° |
| neck–lever angle | \|φ − φ_pre(+30°)\| | < 25° |
| converter error | \|θ − θ_s\| | < 20° |
| F8 preload | k_F8·conDist | < 2.0 pN |
| converter + head-orientation energy | ½κ_conv(θ−θ_s)² + ½κ_bind(ψ−ψ_actin)² | < 15 kT |
| steric | head not crossed to the actin far side / anchor interpenetration | — |

Accept iff all pass ⇒ **latch** boundSeg + bindArc = the CURRENT material coordinate, keep the CURRENT (φ,ψ). The
F8 / converter / bound-head-orientation potentials then relax it (θ_s frozen at −30°, no Δθ). No teleport, no
change of the attachment coordinate, no directly setting φ=30°. Tolerances are named parameters, NOT tuned to
recover a canonical binding rate.

---

## 1. Binding-generation study (260 dilute, independent, multi-seed episodes)

Anchor at the 3D-ideal position + a small dilute jitter (±4/±1.5/±1.5 nm in b̂/ê_conv/ê_up); initial (φ,ψ) drawn
wide (φ_pre±75°, ±60°); Brownian search up to 40 µs or first capture.

**Result — the search naturally recruits the pre-stroke basin (`events_main.csv`):**
- **252 / 260 accepted (96.9 %)**; capture-time 632 ± 2806 steps (a genuine Brownian search: some capture at
  once, the tail searches ~24 000 steps — `-3js …_search` renders one such trajectory).
- **capture pose: φ = 29.0 ± 11.2°, ψ = −1.1 ± 8.3°** — clustered at the +30°/0° basin (Fig. panel 1).
- material coordinate bindArc = 499.2 ± 3.0 nm from end1 (interior); conDist = 1.46 ± 0.41 nm; **F8 preload =
  1.46 ± 0.41 pN**; **capture energy E = 1.9 ± 1.8 kT** (physical, low-strain).
- **IMPLAUSIBLE captures (E > 15 kT strained/misoriented) = 0 / 252 (0.0 %).**
- Rejection-by-gate at near-miss closest approach (`rejection_main.csv`): the **orientation (ψ), lever (φ), and
  energy** gates do the selecting (8/8/8 of the 8 near-misses); the distance/preload/steric/interior gates do not
  (they are satisfied at near-contact). The stereospecific gates are what shape the basin.

## 2. Post-capture relaxation + stroke replay (kept SEPARATE from the binding stats)

Each capture is relaxed under the frozen-ADP·Pi potentials (`relaxed_poses.csv`) — the pose settles into the 3D
basin (φ→30°, ψ→0, conDist→0). Then a representative set is frozen and the validated 3D Δθ=+60° stroke applied
(`stroke_replay.csv`). **Native captured poses preserve the 3D mechanics — 140/140 on every criterion:**

| observable | native captures (mean ± sd) | pass |
|---|---|---|
| axial (pointedward) stroke | **6.91 ± 0.05 nm** | 140/140 in 5–8 nm ✓ |
| transverse | **0.12 ± 0.07 nm** | 140/140 ≤ 2 nm ✓ |
| trap stiffness k_ext | **0.644 ± 0.004 pN/nm** | 140/140 in 0.5–2 ✓ |
| pre-stroke preload | 0.68 ± 0.14 pN | low ✓ |
| pointed-first glide | — | 140/140 ✓ |
| load-sensitive (2 pN opposing reduces the stroke) | — | 140/140 ✓ |

The naturally-captured, relaxed poses reproduce the 3D reference (6.91 nm / 0.09 nm / 0.645 pN/nm) to within the
capture spread. **The +30° mechanical basin is reached by binding, not imposed.**

## 3. Essential controls

- **Spatial-only (orientation gates OFF)** — 100 % acceptance BUT the capture pose scatters (**φ = 33.6 ± 66.3°,
  ψ = 6.6 ± 63.1°**) and **28.3 % of captures are physically implausible (E = 80.5 ± 279.8 kT, up to ~1000 kT)** —
  grossly misoriented, high-strain "bonds" that could not actually form. This is why stereospecificity matters:
  the orientation gates reject non-physical captures at the moment of binding. (Note: because the relaxation basin
  is single-well, even a scattered latched pose *relaxes* to a good stroke — so the distinction stereospecificity
  makes is in **capture realism**, not the post-relaxation stroke. Both facts are real and reported.)
- **Narrow (±8°) vs broad (±55°) windows** — narrow tightens the basin (**φ = 30.7 ± 4.1°, ψ = 0.4 ± 4.2°, E =
  0.4 ± 0.4 kT**, 0 % implausible); broad loosens it (**φ = 26.9 ± 17.3°, ψ = −0.8 ± 10.5°**, 3.4 % implausible).
  Acceptance stays high in all (93–98 %) given the search time — the window controls the captured **pose spread
  and realism**, not whether binding happens. **Crucially, the replayed stroke/stiffness are UNCHANGED across
  windows** (6.90–6.91 nm, 0.10–0.14 nm transverse) — the passive relaxation basin absorbs the capture spread, so
  tolerance width does NOT strongly control the mechanical output.
- **Polarity reversal + rotated assay** — acceptance **identity = swap = rot90 = rot3D = 0.983** (bit-equal). The
  binding rule rotates with the actin material frame; there is no hidden world-axis preference.
- **Restart fidelity** — same seed ⇒ **bit-identical** (seed 7: capture step 3, φ=0.8175, ψ=0.2310, reproduced
  exactly; the search RNG is stateless wang-hash keyed).
- **Timestep** — binding acceptance ≈ dt-robust (0.983 / 0.983 / 0.967 / 0.933 over {5e-6…5e-7}; the mild drift is
  within the 60-episode SEM at a fixed 40 µs physical search window); post-capture relaxation is **dt-exact** —
  φ→30.0°, ψ→0.0°, conDist→0.00 nm at every dt (`timestep.csv`).

## 4. Viewer

`-3js` sequences (`~/Code/SoftBox/threejs_twobody3e_{search,capture,relax,stroke,edgePose}/`): the Brownian search
with rejected near-contacts → accepted native capture (`_search`, 50 frames); the good captured pose (`_capture`);
the short ADP·Pi relaxation (`_relax`); the subsequent stroke from a naturally captured pose (`_stroke`); a
poor-but-accepted edge pose from the broad window (`_edgePose`). Each frame carries — as **diagnostics, not
physical objects** — actin polarity (toward_barbed/toward_pointed), the converter axis, the F8 bond to the
candidate site (physical_bond F8), and the **+30° pre-stroke reference pose** as deviation arrows
(pre→current on head/F8/converter, i.e. the pose/orientation error). Physical bodies (motor_domain, neck_lever,
converter_joint, fixed_anchor) are typed separately.

---

## 5. Decision

- **Does binding naturally recruit the intended mechanical basin?** **YES.** The Brownian search over the
  fixed-anchor neck-lever swing captures at φ = 29.0 ± 11.2°, ψ = −1.1 ± 8.3°, E = 1.9 kT — right at the validated
  +30°/0° pre-stroke basin, with 0 % implausible captures. No snap, no teleport, no setting φ=30°; the pose and
  material coordinate are latched from the live diffusion and relaxed by the passive potentials.
- **Do tolerance widths strongly control stroke or stiffness?** **NO.** Windows control the captured pose spread
  and realism (implausible fraction), but the passive relaxation basin absorbs the spread ⇒ the replayed stroke
  (6.90–6.91 nm) and stiffness (0.644 pN/nm) are unchanged across narrow/main/broad. (Spatial-only is the sole
  regime that admits non-physical captures — the orientation gates exist to reject those, not to tune the output.)
- **Do native captured poses preserve the 3D mechanics?** **YES** — 140/140: pointed-first, 5–8 nm axial, ≤2 nm
  transverse, 0.5–2 pN/nm stiffness, low preload, load-sensitive.
- **Is the search ready for the ADP·Pi→ADP transition?** **YES.** The capture basin is stereospecific,
  rotation-covariant, world-axis-free, restart-faithful, dt-robust (stats) / dt-exact (relaxation), and delivers
  poses that reproduce the 3D stroke. **Chemistry integration (the ADP·Pi→ADP converter target switch) is licensed
  as the next experiment.**

**Controlling conclusion:** the validated +30° pre-stroke geometry is reachable through a reasonable stereospecific
binding basin without snapping and without excessive rejection (96.9 % acceptance). The binding-capture study
**passes**; the ADP·Pi→ADP transition may be added next.

---

## Artifacts & commands
- Preregistration: `RUN_LOGS/twobody_binding_capture/PREREGISTRATION.md`; log `.../exp3e_full.log`.
- CSVs (`.../csv/`): `events_{main,spatialOnly,narrow,broad}`, `rejection_{main,spatialOnly,narrow,broad}`,
  `relaxed_poses`, `stroke_replay`, `timestep`. Figure: `.../exp3e_summary.png`.
- Viewer: `~/Code/SoftBox/threejs_twobody3e_{search,capture,relax,stroke,edgePose}/`.
```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp3e -out RUN_LOGS/twobody_binding_capture/csv -3js ~/Code/SoftBox/threejs_twobody3e
./scripts/run_lasertrap.sh -exp3e -fast                        # quick smoke (fewer episodes)
./scripts/run_lasertrap.sh -exp3c -out /tmp/exp3c_check        # 3C regression: byte-identical
./scripts/run_lasertrap.sh -exp3d -out /tmp/exp3d_check        # 3D regression
python3 scripts/twobody3e_analyze.py RUN_LOGS/twobody_binding_capture/csv RUN_LOGS/twobody_binding_capture/exp3e_summary.png
```
**View:** `python3 SoftBox/sim_server.py 8000` from `~/Code`, open
`http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → newest).

**Parameter definitions (named tolerances, `TwoBodyConverterMotor.Tol`):** `dBindNm`=3.0 (surface distance),
`psiDeg`=25 (binding-face orientation), `phiDeg`=25 (neck-lever angle vs +30°), `thetaDeg`=20 (converter error),
`preloadPn`=2.0 (F8 preload), `energyKt`=15 (converter+orientation energy), `orientOn` (spatial-only sets false).
Dilute jitter box ±4/±1.5/±1.5 nm; search dt 1e-6, relaxation dt 1e-5; intrinsic ADP·Pi converter target θ_s=−30°.
