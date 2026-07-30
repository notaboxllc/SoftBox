
### Pilot — independent roll noise

| arm | v (µm/s) | ω (rad/s) | ⟨x_A⟩ nm | before % | ⟨θ_A⟩ rad | R | circ mean | M_A pN·nm | wind (rad) | Nb | events |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `pilot_in_s101_mirror` | 0.0418 | 0.5521 | 1.679 | 59.21 | -0.1099 | 0.8095 | -0.1208 | 1.82 | 1.3247e+06 | 84 | 237024 |
| `pilot_in_s101_native` | 0.04144 | -0.5112 | 1.495 | 58.33 | 0.1004 | 0.8096 | 0.1105 | -1.663 | 1.3473e+06 | 74 | 215468 |
| `pilot_in_s102_mirror` | 0.04166 | 0.5108 | 1.572 | 58.55 | -0.102 | 0.8088 | -0.1127 | 1.69 | 1.317e+06 | 86 | 245078 |
| `pilot_in_s102_native` | 0.04139 | -0.5144 | 1.593 | 58.7 | 0.1068 | 0.8101 | 0.1171 | -1.769 | 1.3298e+06 | 83 | 232526 |

### Pilot — ANTISYMMETRIC roll noise (pathwise correctness control)

| arm | v (µm/s) | ω (rad/s) | ⟨x_A⟩ nm | before % | ⟨θ_A⟩ rad | R | circ mean | M_A pN·nm | wind (rad) | Nb | events |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `pilot_an_s101_mirror` | 0.04144 | 0.5112 | 1.495 | 58.33 | -0.1004 | 0.8096 | -0.1105 | 1.663 | 1.3473e+06 | 74 | 215468 |
| `pilot_an_s101_native` | 0.04144 | -0.5112 | 1.495 | 58.33 | 0.1004 | 0.8096 | 0.1105 | -1.663 | 1.3473e+06 | 74 | 215468 |
| `pilot_an_s102_mirror` | 0.04139 | 0.5144 | 1.593 | 58.7 | -0.1068 | 0.8101 | -0.1171 | 1.769 | 1.3298e+06 | 83 | 232526 |
| `pilot_an_s102_native` | 0.04139 | -0.5144 | 1.593 | 58.7 | 0.1068 | 0.8101 | 0.1171 | -1.769 | 1.3298e+06 | 83 | 232526 |

### Roll diagnostics — branch crossings, winding, angular bands (pilot_in)

| band (rad) | forward | backward | total | validity (band ≫ step RMS 0.049) |
|---|---|---|---|---|
| 0 | 16926664 | 16926665 | 33853329 | RAW JITTER — never mechanistic |
| 0.01 | 118520181 | 118534889 | 237055070 | resolution-limited, DO NOT interpret |
| 0.025 | 38986420 | 38992302 | 77978722 | resolution-limited, DO NOT interpret |
| 0.05 | 13931888 | 13934828 | 27866716 | resolution-limited, DO NOT interpret |
| 0.1 | 3425490 | 3426959 | 6852449 | trustworthy |

- angular BRANCH crossings (±π): **0**
- max missed-crossing probability: **8.14e-26**; mean **3.07e-33**
- free-roll substeps (Nb = 0): 202; substep subdivisions 0
- sampled |Δθ| path (winding) 1.3386e+06 rad — RESOLUTION-DEPENDENT, quoted at the production substep only
- net |ΔΘ| over the analysed window: 61.538 rad = 9.794 turns

### Pilot variance and campaign sizing

| quantity | pilot mean | between-arm sd | SEM | n | n for 10% | n for 25% |
|---|---|---|---|---|---|---|
| ⟨x_A⟩ (nm) | 1.5849 | 0.0755 | 0.0378 | 4 | 4 | 1 |
| before-centre (%) | 58.7 | 0.374 | 0.187 | 4 | 1 | 1 |
| ⟨θ_A⟩ (rad) | 0.1048 | 0.00436 | 0.00218 | 4 | 3 | 1 |
| M_A (pN·nm) | 1.7355 | 0.0723 | 0.0361 | 4 | 3 | 1 |
| Ω_odd (rad/s) | -0.52212 | 0.0135 | 0.00953 | 2 | 2 | 1 |

- drift over the analysed window: |ΔΘ| = 66.25 rad in 120 s (Ω = 0.5521 rad/s)
- Ω_odd = -0.5221 ± 0.0095 rad/s at n=2: **nonzero drift resolved at 54.8σ**

### Pilot: three-way comparison

| quantity | deterministic drag | axial Brownian | roll Brownian | roll/det | roll−det |
|---|---|---|---|---|---|
| ⟨x_A⟩ (nm) | 1.6445 ± 0.029 | 1.6407 ± 0.021 | **1.5442 ± 0.049** | **0.939** | -0.1 |
| before-centre (%) | 58.98 ± 0.13 | 58.95 ± 0.12 | **58.52 ± 0.18** | **0.9921** | -0.466 |
| ⟨θ_A⟩ (rad) | 0.1061 ± 0.003 | 0.10756 ± 0.0014 | **0.10363 ± 0.0032** | **0.9767** | -0.00247 |
| attachment torque M_A (pN·nm) | -1.757 ± 0.05 | -1.7812 ± 0.023 | **-1.716 ± 0.053** | **0.9767** | 0.041 |
| ⟨ξ_A⟩ (nm) | 0.33868 ± 0.0088 | 0.33797 ± 0.0074 | **0.32342 ± 0.0017** | **0.9549** | -0.0153 |
| v (µm/s) | 0.041709 ± 0.00013 | 0.041831 ± 6.9e-05 | **0.041417 ± 2.8e-05** | **0.993** | -0.000292 |
| inverse pitch λ⁻¹ (µm⁻¹) | -1.982 ± 0.073 | -2.0489 ± 0.035 | **-1.9706 ± 0.0074** | **0.9942** | 0.0114 |
| duty ratio | 0.7421 ± 0.029 | 0.7478 ± 0.014 | **0.7131 ± 0.039** | **0.9609** | -0.029 |
| median bound heads | 81.75 ± 3.1 | 82.5 ± 1.5 | **78.5 ± 4.5** | **0.9602** | -3.25 |
| **Ω_odd (rad/s)** | **-0.51936 ± 0.019** | -0.5439 ± 0.0071 | **-0.52212 ± 0.0095** | **1.005** | -0.00275 |
| Ω_even (rad/s) | 0 exactly | — | 0.0093 ± 0.011 | — | — |
| circular resultant R | — | — | **0.8099 ± 0.00024** | — | — |

### Real vs no-depletion shadow (the load-bearing causal control)

| seed | ⟨x_A⟩ real | ⟨x_A⟩ shadow | Δ_depletion | before real % | before shadow % |
|---|---|---|---|---|---|
| 101 | 1.4954 | -0.11412 | 1.6095 | 58.33 | 49.2 |
| 102 | 1.593 | -0.059318 | 1.6523 | 58.7 | 49.58 |
| **mean (n=2)** | **1.5442 ± 0.049** | **-0.086718 ± 0.027** | **1.6309 ± 0.021** | | |

**Δ_depletion = 1.6309 ± 0.0214 nm — resolved at 76.2σ.**
