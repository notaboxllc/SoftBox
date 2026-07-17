# Explicit-S2 motor binding transient ("snap") — CPU-only diagnostic

**Date:** 2026-07-17 · **Runner:** CPU-only, single-thread, no TaskGraph/GPU (did not touch the running GPU
density sweep; `softbox/*.class` never recompiled — harness built to a scratch classes dir).
**Instrument:** `RUN_LOGS/binding_snap_cpu/BindingSnapHarness.java.txt` (source), CSVs + `run.log` in
`RUN_LOGS/binding_snap_cpu/`. **Motive:** the viewer showed a small filament translation at myosin binding
(memory `binding-snap-observation`); this quantifies it.

## Verdict

**`SMALL CONVERGENT PASSIVE ATTACHMENT RELAXATION` — physical, not a coordinate/timestep artifact.**
Binding writes **only** `boundSeg` + `bindArc` (zero coordinate teleport, confirmed by code *and* a
bit-exact 0 measurement); the visible motion is the **finite overdamped onset of the cross-bridge force**,
which relaxes over ~1 ms. It scales cleanly ∝ dt (→0 as dt→0) and its cumulative magnitude converges as
dt→0 — the two signatures of a physical force-onset transient, not a discontinuity.

## 1. Event ordering (frozen from production `stepGlideS2`, L6803-6830)

Per step: `geom → BIND → chemistry → cock → placeHead → bondForces → gather → filament-integrate →
s2SolveStep`. The discrete bind (L6810, and the device `matBindExplicit` L461) executes
`boundSeg[m]=s; bindArc[m]=arc` and **nothing else** — it does not touch the beam nodes, pivot `P`,
`phi`, `psi`, the head pose, or the filament. `matPlaceHeadExplicit` places the head at the beam's *own*
computed geometry (`matBeamGeom`, a pure function of the unchanged beam), so it introduces no jump at
binding either. The newly-active bond force reaches the filament **on the same step as binding** (bondForces
→ gather → integrate, all after the assignment) and relaxes over subsequent steps.

## 2. Decomposition (explicit-s2-l40; deterministic, Brownian off; controlled central fixture, gap 2.5 nm)

| quantity | value | interpretation |
|---|---:|---|
| **A. coordinate discontinuity at the bind assignment** | fil 0, beam 0, head 0, Δφ 0, Δψ 0 (**exactly**) | **no teleport / projection** — only metadata changes |
| onset \|F8\| (step 0) | 10.2 pN | finite; see gate note below |
| **B. first-step filament displacement** | axial +0.39 nm, transverse +0.39 nm, rot 0 | one overdamped Euler step under the onset force |
| **C. cumulative passive relaxation (1 ms)** | axial +2.4 nm, transverse +0.07 nm | strain relaxes; settles by ~1 ms |
| attachment ΔE | +0.27 kT | tiny, physical |
| power stroke (separate ADP·Pi→ADP phase) | −4.96 nm | for scale |
| \|first-step\| / \|stroke\| | 0.079 | first step is small |
| cumulative / \|stroke\| | 0.49 | passive relaxation ≈ half a working stroke |

**Onset-force / gate note (finding):** the binding gate caps the *converter* preload
(`kF8Code·conDist < 2 pN`), but the **cross-bridge F8 spring** in `bondForces` (`xbParams[0]`) is stiffer,
so the *actual* onset \|F8\| at bind is ~6–10 pN (up to ~13 pN for 3.5 nm gaps). The gate does **not** bound
the cross-bridge onset force. This stays below the 12 pN faithful-release cap for ≤~3 nm gaps; larger gaps
would trip it (in production, chemistry/detachment/cap — all frozen here — would truncate the transient).

## 3. dt-convergence (§5 — the decisive test; geometry fixed by relax-to-rest, physical window matched)

| dt (s) | first-step axial (nm) | nm/µs | onset \|F8\| (pN) | cumulative @1 ms (nm) |
|---:|---:|---:|---:|---:|
| 2.5e-6 | +0.389904 | 0.15596 | 10.179 | +2.42 |
| 1.25e-6 | +0.194967 | 0.15597 | 10.179 | +2.52 |
| 6.25e-7 | +0.097483 | 0.15597 | 10.179 | +3.24 |
| 3.125e-7 | +0.048727 | 0.15593 | 10.179 | +3.28 |

- **First-step ∝ dt exactly**: `firstAx(dt) = 1.560e5·dt + 5.6e-17 nm`, **intercept ≈ 0, R² = 1.00000**;
  `firstAx/dt` constant to 5 sig-figs. A timestep-independent discontinuity would show a non-zero intercept —
  it does not. Onset force is dt-independent (10.179 pN at every dt), confirming the bind geometry is clean.
- **Cumulative converges as dt→0** (+2.4 → +3.3 nm): a finite physical relaxation, not a divergence.

## 4. Geometry ensemble (§6/§7 — 45 controlled fixtures: 5 axial × 3 gap × 3 lateral on one segment)

`firstStep axial` mean **+0.39 nm (sd 0)**, `firstStep transverse` +0.40 ± 0.04 nm, `cumulative axial`
+2.31 ± 0.24 nm (**45/45 positive**), `cum/|stroke|` 0.47 ± 0.04, coord-jump 0 for **all** fixtures.

- **First-step axial is essentially invariant (~+0.39 nm)** across gap/position because it is set by the
  ~fixed head-tip converter geometry; the *transverse* first step tracks the gap. The **cumulative** relaxation
  carries the axial signal.
- **Directional bias (§7): the cumulative passive relaxation is consistently POSITIVE** (opposite to the
  −x power stroke), robust across an orientation sweep (φ ± 20°: 9/9 positive, magnitude 0.27→4.56 nm,
  monotone in φ) and the 3 natural real-motor fixtures (cumAx +3.0/+4.5/+7.0 nm; cum/\|stroke\| 0.59/0.75/0.90).
  So attachment nudges the filament *backward* by a fraction of a stroke before the stroke drives it forward.
- Magnitude scales with how far the head must relax to its bound rest — orientation-dependent, bounded.

## 5. Binding-transient vs power stroke (§8)

first-step is ~8 % of a stroke (small); the **cumulative passive attachment relaxation is ~0.5–0.9 × the
working stroke** (median 0.48 controlled; up to 0.90 for the widest-gap natural fixture). This exceeds the
25 % "flag" threshold, but it is a *physical convergent* relaxation (§3), oriented opposite the stroke, and
one-time (per attachment) — it does not accumulate per cycle. It is real and worth being aware of when reading
per-attachment working-stroke measurements, but it is not evidence of a modelling artifact.

## 6. Explicit vs calibrated (§10 — matched: same motor 191, same segment 8, same 45 targets)

| median over matched fixtures | explicit-s2-l40 | calibrated-s2-l40 |
|---|---:|---:|
| coordinate jump at bind (µm) | 0 | 0 |
| onset \|F8\| (pN) | 10.37 | 10.37 |
| first-step axial (nm) | +0.39 | +0.39 |
| cumulative axial (nm) | **+2.34** | **−6.83** |
| power stroke (nm) | −4.89 | −5.61 |
| cum / \|stroke\| | **0.48** | **4.97** |

- The **binding contract is model-independent**: zero coordinate jump, identical onset force, identical
  first-step in *both* models (same `bondForces` + placed head geometry). The **stroke agrees** in sign and
  magnitude (~−5 nm) once measured in the same segment frame.
- **The transient is not "amplified by explicit beam compliance" — the opposite.** The calibrated
  *movable-pivot* surrogate produces a **much larger, geometry-sensitive, less-bounded** attachment excursion
  (median \|6.8\| nm, sd 45 nm, sign-variable, ~5× the stroke), because its pivot is held only by the soft
  supported-tail law and drifts under the sustained bond load. The explicit **anchored** beam resists axial
  pull → a small, tight, consistently-signed transient (+2.3 ± 0.2 nm). Chemistry/detachment/12-pN-cap (frozen
  in this assay) would truncate the calibrated drift in production; the number is an upper bound of the
  "if it stayed bound" excursion, and a flag that the surrogate's *unloaded-attachment* transient is not a
  faithful match to the explicit beam's (its **fit target was loaded mechanics**, memory `experiment4i…`).

## 7. Brownian context (§9)

Per-step thermal RMS of the bound segment ≈ 0.80 nm; over a 1 ms (400-step) window the thermal random-walk
wander is ≈ 16 nm — **larger** than the explicit deterministic passive relaxation (~2.3 nm). So in a live
Brownian run the explicit "snap" is *within* thermal motion and not a dramatic discrete event (consistent with
the observation being "possibly not an issue"). The paired bound−unbound difference is noisy
(−45 ± 94 nm/8 seeds) because binding couples the filament to the thermally-driven beam — a qualitative
"distinguishable from a single thermal step, comparable to thermal wander," not a precise number.

## Status block

- `COORDINATE DISCONTINUITY AT BIND:` **none** — exactly 0 in fil/beam/head/φ/ψ, both models (code + measured).
- `FIRST-STEP DISPLACEMENT SCALING:` **∝ dt, intercept ≈ 0 (5.6e-17 nm, R²=1.0)** ⇒ finite force onset.
- `CUMULATIVE PRE-STROKE RELAXATION:` explicit **+2.3 nm (converges as dt→0), ≈0.48× stroke, sign +** (opposite the stroke).
- `MEAN DIRECTIONAL BIAS:` explicit **consistently positive** (backward), orientation-robust; calibrated large/variable.
- `RELATIVE TO POWER STROKE:` first-step ~8 %; cumulative ~48–90 % (explicit); ~500 % (calibrated, unbounded pivot drift).
- `EXPLICIT VS CALIBRATED:` identical bind contract + onset + first-step + stroke; **calibrated transient ~15× larger and geometry-sensitive** (movable pivot), NOT explicit-beam-amplified.
- `LIKELY PHYSICAL / NUMERICAL / MIXED:` **PHYSICAL** (convergent force-onset relaxation); no artifact.
- `RECOMMENDED NEXT ACTION:` no code change to the binding law. (a) Be aware per-attachment working-stroke reads
  carry a ~0.5-stroke backward pre-stroke relaxation in the explicit model. (b) The calibrated surrogate's
  large unloaded-attachment drift is a fidelity gap vs the explicit beam worth noting if attachment transients
  (not just steady load) ever enter a calibration target. (c) Optional: quantify in the planned laser-tweezers
  experiment as originally intended.

## Method / faithfulness notes

- Drove the **real** production system methods in the real `stepGlideS2` order (bondForces, CSR gather,
  `RigidRodLangevinIntegrationSystem.integrate`, `s2SolveM`/`supSolveM`), with bind/chemistry/stroke controlled
  externally so the mechanical transient is isolated (chemistry not called ⇒ nucleotide frozen ⇒ no stroke, no
  catch-slip detach; stroke triggered as a separate ADP phase). The one re-implemented method is a verbatim copy
  of `s2SolveM`/`supSolveM` with the beam-Brownian `brownTorque` terms gated by a boolean (identical arithmetic
  otherwise) — a throwaway diagnostic variant, not a second physics path.
- Controlled fixtures rigidly translate a rest-relaxed donor motor so its F8 point sits at a prescribed target
  (A[m] is aliased to the last beam node — translate nodes only, never A separately, or the beam is stretched).
- Pre-bind relaxes the motor to its dt-independent fixed point so the bind geometry (hence first-step ∝ dt) is
  clean; cumulative measured over a fixed *physical* window (steps = T/dt) across dt.
