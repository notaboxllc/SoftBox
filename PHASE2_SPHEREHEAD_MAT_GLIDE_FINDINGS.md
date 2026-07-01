# Phase-2 — sphere-head full-mat gliding assay: the velFitX gate → +X (no −X glide); duty-cycle blocker

**2026-06-30. New files / flag-gated only ⇒ production byte-identical; `BoA-v1ref` byte-clean. Standing
rule honored: no glide is claimed — the gate measures velFitX vs the `-nobind` floor and ADJUDICATES.**

## Standing rule (restated)
No glide velocity counts unless it comes from the full-mat assay (velFitX over the steady 2nd half,
`-nobind` thermal floor, n≥3 + SEM, dt=1e-5, density ladder, vs the skeletal anchor 5–8 µm/s). This
report runs the **single-density velFitX gate** the task asks to stage behind — and it adjudicates the
mechanism BEFORE spending the full ladder / the ~28k-motor GPU bed.

## The gate result — the mechanism glides the WRONG way
Reduced-scale CPU gate (40 motors, one 0.4 µm filament, 60k steps, dt=1e-5; velFitX = −slope of the
steady-2nd-half LS fit of centroid x(t), so **velFitX>0 = −x glide = correct**):

| run | velFitX (µm/s, mean ± SEM, n=3) | avgBound |
|---|---|---|
| **STROKE-ON** | **−0.084 ± 0.007** | 6.23 |
| FLOOR (`-nobind`) | +0.000 ± 0.000 | 0 |
| **glide vs floor** | **−0.084** (above floor, real) | — |

**velFitX is NEGATIVE ⇒ the filament glides +X — the WRONG polarity** (the correct gliding direction is
−x, away from the barbed/plus end). It is **above the floor** (the heavy filament barely diffuses), so the
+X motion is real transport, not noise. Binding is healthy (avgBound 6.2); **stable at dt=1e-5** (no
NaN/whip at this scale, the compliant step damped by the filament drag as predicted).

**⇒ ADJUDICATION: NO −X glide. The sphere-head mat transports, but +X (~0.08 µm/s) — the wrong way.**

## Why — the duty cycle (NOT the stroke geometry, NOT a bind artifact)
- **The single-motor −X (M3) measured only HALF the cycle.** M3 settles uncocked then cocks once and
  measures — capturing only the **−X power stroke**. The full bind→stroke→**recover**→release cycle also
  has the **+X recovery stroke** (uncock, J1 60°→0°). In the mat the complete cycle runs, and the net is +X.
- **It is NOT a bind impulse:** removing the canonical-pose reset (`-noreset`) gives the SAME +X (net
  +233 vs +200 nm) — the +X is intrinsic to the cycle, not the pose snap.
- **Root cause:** for net −X transport the motor must **release while cocked/loaded and recover UNBOUND**
  (v1's cycle detaches at NONE→ATP while cocked, then recovers off-filament). The mat reuses
  `catchSlipRelease`, which at the sphere-head's sub-pN loads fires at a ~random phase (the catch is inert
  there) — so motors frequently recover **while still bound**, dragging the filament +X and reversing the net.

## What this means for the full assay (the task's ladder / 28k GPU bed)
- **The full density ladder + the dense ~28k-motor GPU bed were NOT run** — and should not be yet:
  1. The **direction is +X (wrong)**; a rigorous ladder would only confirm +X at every density (no −X glide).
  2. The dense bed needs the **sphere-head on the GPU** (sphereBond + perpTorque + the J1 converter + the
     bind/pose handling, all as a device graph + the head-side gather) — a large build not yet done.
  Running either now would burn the budget to rigorously document a +X that the gate already settles.
- **dt=1e-5 stability (the must-check):** PASS at this scale (capFires n/a — break-force cap off;
  avgBound healthy, no NaN). The mat-scale claim (heavy filament damps the single-motor compliant
  overshoot) **held** at 40 motors / 0.4 µm filament; re-confirm at production scale once the direction is fixed.
- **CPU≡GPU:** not run (no GPU sphere-head path yet); deferred with the GPU build.

## The blocker + the path (the prerequisite for a −X glide proof)
**The glide proof is BLOCKED on duty-cycle coordination, not the stroke.** The stroke geometry, polarity,
spring (1 pN/nm), catch-slip load (axial F·seg.uVec), and the compliant perp-orientation torque are all
correct (single-motor verified). The missing piece is **release-while-cocked → recover-unbound**:
1. Couple detachment to the cocked/post-stroke state (the v1 NONE→ATP pattern, or an explicit
   release-on-cocked) so the +X recovery happens off the filament.
2. Re-run THIS gate → confirm velFitX flips to **>0 (−x glide)** above the floor.
3. THEN build the GPU sphere-head mat and run the full ladder (velFitX ± SEM, n≥3, density 200–2000, vs
   the skeletal anchor 5–8 µm/s) + CPU≡GPU.

## Reproduce
```
java … softbox.SphereHeadHarness -glide -dt 1e-5                       # the velFitX gate (n=3 + nobind floor)
java … softbox.SphereHeadHarness -glide -3js threejs_spherehead_glide  # the visual mat (glides +X)
java … softbox.SphereHeadHarness -glide -noreset                       # +X persists ⇒ not a bind impulse
```

## JOURNAL-ready line
`2026-06-30 — sphere-head full-mat gliding assay, single-density velFitX gate (CPU, 40 motors, n=3 +
-nobind floor, dt=1e-5): velFitX = −0.084 ± 0.007 µm/s = the filament glides +X (WRONG polarity), above the
~0 floor, avgBound 6.23, dt-stable. ⇒ NO −X glide. Root cause = the DUTY CYCLE: the single-motor M3 −X was
only the power-stroke half; the full bind→stroke→recover→release cycle nets +X because the +X recovery
isn't separated from the −X stroke by release timing (catchSlipRelease fires at a random phase; the
sub-pN catch is inert). NOT a bind impulse (+X persists -noreset). The stroke geometry/polarity/spring(1
pN/nm)/catch-load are all correct (single-motor verified); the blocker is release-while-cocked →
recover-unbound (v1's NONE→ATP cocked detachment). The full density ladder + dense ~28k GPU bed are
DEFERRED (would confirm +X; need the GPU sphere-head build) until the duty cycle is fixed and the gate
flips to velFitX>0. New files only ⇒ production byte-identical; BoA-v1ref byte-clean. Report:
PHASE2_SPHEREHEAD_MAT_GLIDE_FINDINGS.md.`
