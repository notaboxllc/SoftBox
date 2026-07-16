# TwoBodyGpuKernels CPU-runner validation vs golden fixtures

| fixture | model | tier | fields | worst field | maxRelΔ | maxAbsΔ | verdict |
|---|---|---|---|---|---|---|---|
| fixed-anchor_bound_baseline | fixed-anchor | T3 | 20 | - | 0.00e+00 | 1.47e-08 | PASS |
| fixed-anchor_bound_multistep | fixed-anchor | T3 | 20 | - | 0.00e+00 | 1.47e-08 | PASS |
| fixed-anchor_prestroke_adppi | fixed-anchor | T3 | 20 | - | 0.00e+00 | 1.47e-08 | PASS |
| fixed-anchor_poststroke_adp | fixed-anchor | T3 | 20 | - | 0.00e+00 | 1.47e-08 | PASS |
| fixed-anchor_brownian | fixed-anchor | T3 | 20 | - | 0.00e+00 | 1.47e-08 | PASS |
| fixed-anchor_unbound_search | fixed-anchor | T3 | 20 | - | 0.00e+00 | 1.46e-08 | PASS |
| fixed-anchor_axial_low | fixed-anchor | T3 | 20 | - | 0.00e+00 | 3.96e-09 | PASS |
| fixed-anchor_axial_high | fixed-anchor | T3 | 20 | - | 0.00e+00 | 4.58e-09 | PASS |
| fixed-anchor_transverse | fixed-anchor | T3 | 20 | - | 0.00e+00 | 1.47e-08 | PASS |
| fixed-anchor_near_detach | fixed-anchor | T3 | 20 | - | 0.00e+00 | 1.34e-08 | PASS |
| calibrated-s2-l40_bound_baseline | calibrated-s2-l40 | T3 | 20 | - | 0.00e+00 | 1.66e-08 | PASS |
| calibrated-s2-l40_bound_multistep | calibrated-s2-l40 | T3 | 20 | - | 0.00e+00 | 1.64e-08 | PASS |
| calibrated-s2-l40_prestroke_adppi | calibrated-s2-l40 | T3 | 20 | - | 0.00e+00 | 1.66e-08 | PASS |
| calibrated-s2-l40_poststroke_adp | calibrated-s2-l40 | T3 | 20 | - | 0.00e+00 | 1.66e-08 | PASS |
| calibrated-s2-l40_brownian | calibrated-s2-l40 | T3 | 20 | - | 0.00e+00 | 1.75e-08 | PASS |
| calibrated-s2-l40_unbound_search | calibrated-s2-l40 | T3 | 20 | - | 0.00e+00 | 1.35e-08 | PASS |
| calibrated-s2-l40_axial_low | calibrated-s2-l40 | T3 | 20 | - | 0.00e+00 | 2.62e-08 | PASS |
| calibrated-s2-l40_axial_high | calibrated-s2-l40 | T3 | 20 | - | 0.00e+00 | 6.00e-09 | PASS |
| calibrated-s2-l40_transverse | calibrated-s2-l40 | T3 | 20 | - | 0.00e+00 | 1.66e-08 | PASS |
| calibrated-s2-l40_near_detach | calibrated-s2-l40 | T3 | 20 | - | 0.00e+00 | 2.00e-08 | PASS |
| explicit-s2-l40_bound_baseline | explicit-s2-l40 | T4 | 38 | - | 0.00e+00 | 0.00e+00 | PASS |
| explicit-s2-l40_bound_multistep | explicit-s2-l40 | T4 | 38 | - | 0.00e+00 | 0.00e+00 | PASS |
| explicit-s2-l40_prestroke_adppi | explicit-s2-l40 | T4 | 38 | - | 0.00e+00 | 0.00e+00 | PASS |
| explicit-s2-l40_poststroke_adp | explicit-s2-l40 | T4 | 38 | - | 0.00e+00 | 0.00e+00 | PASS |
| explicit-s2-l40_brownian | explicit-s2-l40 | T4 | 38 | - | 0.00e+00 | 0.00e+00 | PASS |
| explicit-s2-l40_unbound_search | explicit-s2-l40 | T4 | 38 | - | 0.00e+00 | 0.00e+00 | PASS |
| explicit-s2-l40_bend | explicit-s2-l40 | T4 | 38 | - | 0.00e+00 | 0.00e+00 | PASS |
| explicit-s2-l40_taut | explicit-s2-l40 | T4 | 38 | - | 0.00e+00 | 0.00e+00 | PASS |
| explicit-s2-l40_mixed | explicit-s2-l40 | T4 | 38 | - | 0.00e+00 | 0.00e+00 | PASS |
| explicit-s2-l40_high_axial | explicit-s2-l40 | T4 | 38 | - | 0.00e+00 | 0.00e+00 | PASS |

**Result: 30/30 fixtures PASS, 0 FAIL.**
