# Experiment 3F — Native Pi-Release Power Stroke on Naturally Captured Motors

**Date:** 2026-07-14 · **Branch:** `dt-convergence-study` · **Commit at start:** `2170aff937130c232ca999a6b5f87233f58b5776`
· **Runner:** CPU sequential only. **Hardware:** aorus (one core). **Wall-clock:** ≈ 30 s (118 events). **dt:**
search 1e-6, relaxation/dwell 1e-5. **Trap:** 0.05 pN/nm.

**Default-off, non-canonical** (`[NON-CANONICAL TWO-BODY PROTOTYPE]`), `-exp3f`/`-twobody-pistroke` in
`softbox/TwoBodyConverterMotor.java`. **Canonical motor, production defaults, and `BoA-v1ref` untouched.** 3C/3D/3E
preserved byte-identical (3F is new methods only). **Only Pi release + the power stroke are added** — NO ADP
release, ATP detachment, catch-slip, recovery, gliding carpets, or ensembles.

---

## 0. The integrated transition

The validated 3E sequence is extended, using **naturally captured motors** (from the 3E Brownian search — the
actual captured (φ,ψ,anchor,material-coordinate), NOT reconstructed ideal poses):

    unbound Brownian search → stereospecific ADP·Pi capture → passive pre-stroke relaxation
    → ADP·Pi → ADP  → converter target θ_s: −30° → +30°  → stable post-stroke dwell

The converter-target switch is the **FIXED material-frame ordering** established in 3C–3D
(θ_s^ADP·Pi = −30°, θ_s^ADP = +30°, a +60° swing = the 3D corrected stroke). The target is an **absolute**
material-frame angle, **not** selected from `barbedDir`. 118 natural captures were carried through the full
transition (`pistroke_events.csv`).

## 1. Preload distinction (resolved BEFORE any blinded data)

Three quantities that are easy to conflate, measured per event (`preload_distinction.csv`, n=118):

| quantity | definition | value |
|---|---|---:|
| **capture preload** | F8 bond force at the instant of capture (pose just latched, **pre-relaxation**) = k_F8·conDist_cap | **1.49 ± 0.39 pN** (0.44–2.00) |
| **relaxed preload** | F8 bond force after the passive ADP·Pi pre-stroke relaxation (θ_s=−30° dwell) | **0.10 ± 0.03 pN** |
| **pre-transition trap force** | the AXIAL force the trap reads on the filament at the pre-stroke dwell (the EXTERNAL dumbbell observable) | **−0.027 ± 0.148 pN** (−0.38…+0.24) |

**They are genuinely different and the distinction is mechanistic:**
- **Capture → relaxed (1.49 → 0.10 pN, a ~15× drop):** at capture the head is latched at a finite perpendicular
  offset (conDist ≈ 1.5 nm, gated < 3 nm). The passive F8 + converter + bound-orientation potentials then relax
  the pose, pulling the F8 point onto the material site ⇒ the bond stretch (and its force) collapses. The *capture*
  preload is a transient of the search geometry; the *relaxed* preload is the true resting bond tension.
- **Relaxed preload → pre-transition trap force (0.10 pN → ~0 pN):** the relaxed F8 bond is nearly **perpendicular**
  to the actin axis (the head grips the side of the filament), so its **axial projection** — the only component
  that loads the dumbbell traps — is essentially zero. The trap (the experimentalist's observable) therefore reads
  **no pre-stroke tension**, even though a small (0.10 pN) resting bond force exists internally. The pre-stroke
  preload is invisible to the tweezers; the step will start from an unloaded baseline.

This is the load-bearing clarification for the blinded assay (3G): the *externally observable* pre-stroke tension
is ~0, so a detected step is a clean displacement from a force-free baseline — not contaminated by a hidden
resting preload.

## 2. The integrated transition retains the 3D mechanics (118/118)

Per event, the Pi-release stroke was applied from the relaxed pre-stroke pose and measured externally
(`pistroke_events.csv`):

| observable | native captures (mean ± sd) | check |
|---|---|---|
| axial (pointedward) stroke | **6.90 ± 0.04 nm** | 118/118 in 5–8 nm ✓ |
| transverse | **0.10 ± 0.06 nm** | 118/118 ≤ 2 nm ✓ |
| force on actin · b̂ | **−0.664 ± 0.149 pN** (pointed) | 118/118 pointed-directed ✓ |
| pointed-end-first displacement | glide · p̂ > 0 | 118/118 ✓ |
| incremental attached stiffness k_ext (ADP) | **0.624 ± 0.004 pN/nm** | skeletal 0.5–2 ✓ |
| load sensitivity | stroke reduces under 2 pN opposing (6.90 → 6.67 nm) | 118/118 ✓ |
| stable post-stroke dwell | axial drift < 0.5 nm over the dwell | 118/118 ✓ |

The naturally-captured motors reproduce the 3D reference stroke (6.91 nm / 0.09 nm / 0.645 pN/nm) to within the
capture spread — **the Pi-release transition, integrated on real captures, preserves pointed-directed force,
pointed-first ~6.9 nm axial displacement, small transverse, skeletal stiffness, ~0 external pre-stroke preload,
load sensitivity, and a stable trap observable.**

## 3. Viewer

`-3js` → `~/Code/SoftBox/threejs_twobody3f_pistroke/` (77 frames): one natural capture carried through pre-stroke
relaxation → Pi-release stroke (θ_s −30°→+30°) → post-stroke dwell. Diagnostics (not physical objects): actin
polarity, converter axis, F8 bond, and the +30° reference-pose deviation arrows.

## 4. Decision

The integrated Pi-release power stroke on naturally captured motors is **validated**: the transition retains all
required properties (118/118), the three preload quantities are resolved and documented, and the externally
observable pre-stroke trap force is ~0 (a clean force-free baseline). **Ready for the blinded optical-tweezers
challenge (3G).** No ADP release / ATP detachment / catch-slip / recovery / gliding / ensembles added; no canonical
change.

---

## Artifacts & commands
- Preregistration: `RUN_LOGS/twobody_pi_stroke/PREREGISTRATION.md`; log `.../exp3f_full.log`.
- CSVs: `.../csv/preload_distinction.csv`, `.../csv/pistroke_events.csv`. Figure: `.../exp3f_summary.png`.
- Viewer: `~/Code/SoftBox/threejs_twobody3f_pistroke/`.
```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp3f -out RUN_LOGS/twobody_pi_stroke/csv -3js ~/Code/SoftBox/threejs_twobody3f
./scripts/run_lasertrap.sh -exp3f -fast          # quick smoke
python3 scripts/twobody3f_analyze.py RUN_LOGS/twobody_pi_stroke/csv RUN_LOGS/twobody_pi_stroke/exp3f_summary.png
```
