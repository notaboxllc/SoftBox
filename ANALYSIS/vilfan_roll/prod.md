
### Production — independent roll noise

| arm | v (µm/s) | ω (rad/s) | ⟨x_A⟩ nm | before % | ⟨θ_A⟩ rad | R | circ mean | M_A pN·nm | wind (rad) | Nb | events |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `prod_in_s101_mirror` | 0.0418 | 0.5521 | 1.679 | 59.21 | -0.1099 | 0.8095 | -0.1208 | 1.82 | 1.3247e+06 | 84 | 237024 |
| `prod_in_s101_native` | 0.04144 | -0.5112 | 1.495 | 58.33 | 0.1004 | 0.8096 | 0.1105 | -1.663 | 1.3473e+06 | 74 | 215468 |
| `prod_in_s102_mirror` | 0.04166 | 0.5108 | 1.572 | 58.55 | -0.102 | 0.8088 | -0.1127 | 1.69 | 1.317e+06 | 86 | 245078 |
| `prod_in_s102_native` | 0.04139 | -0.5144 | 1.593 | 58.7 | 0.1068 | 0.8101 | 0.1171 | -1.769 | 1.3298e+06 | 83 | 232526 |
| `prod_in_s103_mirror` | 0.04156 | 0.5052 | 1.566 | 58.64 | -0.1036 | 0.807 | -0.1148 | 1.716 | 1.3074e+06 | 90 | 255878 |
| `prod_in_s103_native` | 0.04133 | -0.5569 | 1.689 | 59.23 | 0.1124 | 0.8096 | 0.124 | -1.862 | 1.3289e+06 | 81 | 232616 |
| `prod_in_s104_mirror` | 0.04209 | 0.551 | 1.737 | 59.3 | -0.1115 | 0.8107 | -0.1228 | 1.846 | 1.3697e+06 | 66 | 194537 |
| `prod_in_s104_native` | 0.04194 | -0.5186 | 1.514 | 58.4 | 0.09944 | 0.8067 | 0.1089 | -1.647 | 1.3076e+06 | 90 | 256979 |

### Roll diagnostics — branch crossings, winding, angular bands (prod_in)

| band (rad) | forward | backward | total | validity (band ≫ step RMS 0.049) |
|---|---|---|---|---|
| 0 | 34940046 | 34940046 | 69880092 | RAW JITTER — never mechanistic |
| 0.01 | 234874412 | 234904462 | 469778874 | resolution-limited, DO NOT interpret |
| 0.025 | 77026993 | 77039012 | 154066005 | resolution-limited, DO NOT interpret |
| 0.05 | 27349122 | 27355131 | 54704253 | resolution-limited, DO NOT interpret |
| 0.1 | 6601470 | 6604474 | 13205944 | trustworthy |

- angular BRANCH crossings (±π): **155254**
- max missed-crossing probability: **1**; mean **0.00748**
- free-roll substeps (Nb = 0): 242; substep subdivisions 3722096
- sampled |Δθ| path (winding) 1.3284e+06 rad — RESOLUTION-DEPENDENT, quoted at the production substep only
- net |ΔΘ| over the analysed window: 63.035 rad = 10.03 turns

### Production: deterministic vs axial vs roll Brownian

| quantity | deterministic drag | axial Brownian | roll Brownian | roll/det | roll−det |
|---|---|---|---|---|---|
| ⟨x_A⟩ (nm) | 1.6445 ± 0.029 | 1.6407 ± 0.021 | **1.5728 ± 0.044** | **0.9564** | -0.0717 |
| before-centre (%) | 58.98 ± 0.13 | 58.95 ± 0.12 | **58.67 ± 0.2** | **0.9946** | -0.316 |
| ⟨θ_A⟩ (rad) | 0.1061 ± 0.003 | 0.10756 ± 0.0014 | **0.10478 ± 0.003** | **0.9875** | -0.00132 |
| attachment torque M_A (pN·nm) | -1.757 ± 0.05 | -1.7812 ± 0.023 | **-1.7351 ± 0.05** | **0.9875** | 0.0219 |
| ⟨ξ_A⟩ (nm) | 0.33868 ± 0.0088 | 0.33797 ± 0.0074 | **0.33089 ± 0.0044** | **0.977** | -0.0078 |
| v (µm/s) | 0.041709 ± 0.00013 | 0.041831 ± 6.9e-05 | **0.041526 ± 0.00014** | **0.9956** | -0.000183 |
| inverse pitch λ⁻¹ (µm⁻¹) | -1.982 ± 0.073 | -2.0489 ± 0.035 | **-2.0135 ± 0.044** | **1.016** | -0.0314 |
| duty ratio | 0.7421 ± 0.029 | 0.7478 ± 0.014 | **0.7448 ± 0.029** | **1.004** | 0.00268 |
| median bound heads | 81.75 ± 3.1 | 82.5 ± 1.5 | **82 ± 3.3** | **1.003** | 0.25 |
| **Ω_odd (rad/s)** | **-0.51936 ± 0.019** | -0.5439 ± 0.0071 | **-0.52753 ± 0.005** | **1.016** | -0.00817 |
| Ω_even (rad/s) | 0 exactly | — | 0.00224 ± 0.011 | — | — |
| circular resultant R | — | — | **0.809 ± 0.00077** | — | — |

### Real vs no-depletion shadow (the load-bearing causal control)

| seed | ⟨x_A⟩ real | ⟨x_A⟩ shadow | Δ_depletion | before real % | before shadow % |
|---|---|---|---|---|---|
| 101 | 1.4954 | -0.11412 | 1.6095 | 58.33 | 49.2 |
| 102 | 1.593 | -0.059318 | 1.6523 | 58.7 | 49.58 |
| 103 | 1.6887 | -0.022805 | 1.7115 | 59.23 | 49.84 |
| 104 | 1.5141 | -0.093491 | 1.6076 | 58.4 | 49.24 |
| **mean (n=4)** | **1.5728 ± 0.044** | **-0.072433 ± 0.02** | **1.6452 ± 0.024** | | |

**Δ_depletion = 1.6452 ± 0.0244 nm — resolved at 67.5σ.**
