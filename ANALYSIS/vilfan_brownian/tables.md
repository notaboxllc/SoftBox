
### Stage 6 — pilot, independent axial noise

| arm | v (µm/s) | ω (rad/s) | ⟨x_A⟩ nm | before % | ⟨θ_A⟩ rad | M_A pN·nm | net (nm) | max back (nm) | Nb | events |
|---|---|---|---|---|---|---|---|---|---|---|
| `pilot_in_s101_mirror` | 0.0414 | 0.5399 | 1.696 | 59.21 | -0.1068 | 1.768 | 4968.3 | 11.6 | 83 | 235451 |
| `pilot_in_s101_native` | 0.04171 | -0.484 | 1.582 | 58.66 | 0.1015 | -1.681 | 5005.5 | 6.2 | 74 | 214971 |
| `pilot_in_s102_mirror` | 0.04145 | 0.5204 | 1.52 | 58.24 | -0.1008 | 1.669 | 4974.4 | 6.12 | 85 | 244792 |
| `pilot_in_s102_native` | 0.0419 | -0.5604 | 1.693 | 59.12 | 0.1089 | -1.803 | 5028.1 | 19.6 | 83 | 232305 |

### Stage 6 — pilot, COMMON axial noise (correctness control, not an independent sample)

| arm | v (µm/s) | ω (rad/s) | ⟨x_A⟩ nm | before % | ⟨θ_A⟩ rad | M_A pN·nm | net (nm) | max back (nm) | Nb | events |
|---|---|---|---|---|---|---|---|---|---|---|
| `pilot_cn_s101_mirror` | 0.04171 | 0.484 | 1.582 | 58.66 | -0.1015 | 1.681 | 5005.5 | 6.2 | 74 | 214971 |
| `pilot_cn_s101_native` | 0.04171 | -0.484 | 1.582 | 58.66 | 0.1015 | -1.681 | 5005.5 | 6.2 | 74 | 214971 |
| `pilot_cn_s102_mirror` | 0.0419 | 0.5604 | 1.693 | 59.12 | -0.1089 | 1.803 | 5028.1 | 19.6 | 83 | 232305 |
| `pilot_cn_s102_native` | 0.0419 | -0.5604 | 1.693 | 59.12 | 0.1089 | -1.803 | 5028.1 | 19.6 | 83 | 232305 |

### Stage 5 — target-zone crossing and recrossing diagnostics (pilot)

| hysteresis band (nm) | forward crossings | backward crossings | per zone passage |
|---|---|---|---|
| 0  <- raw jitter, not physical | 289147 | 289146 | 1.04e+03 |
| 0.5 | 97374 | 97374 | 349 |
| 1 | 22089 | 22088 | 79.3 |
| 2.7  <- one actin subunit | 340 | 340 | 1.22 |
| 5 | 337 | 337 | 1.21 |

- raw zone-centre sign changes: **592,556** (jitter-dominated — see the band-0 row)
- net forward travel 10,034 nm over 279 zone passages; max backward excursion **19.63 nm** = 54.52 % of the zone period
- sampled axial path length 21,003,514 nm vs net 10,034 nm (ratio 2093.3). Path length is RESOLUTION-DEPENDENT (an OU path has unbounded variation); it is quoted at the production substep and must not be compared across step sizes.

### Stage 7 — pilot variance and campaign sizing

| quantity | pilot mean | between-seed sd | SEM | n |
|---|---|---|---|---|
| ⟨x_A⟩ (nm) | 1.6372 | 0.0787 | 0.0556 | 2 |
| before-centre (%) | 58.89 | 0.32 | 0.226 | 2 |
| ⟨θ_A⟩ (rad) | 0.10518 | 0.00522 | 0.00369 | 2 |
| Ω_odd (rad/s) | -0.52616 | 0.0201 | 0.0142 | 2 |

- between-seed sd(⟨x_A⟩) = **0.0787 nm**. To detect the H1/H2 boundary (a 25 % change = 0.411 nm) at two-sided 95 % / 80 % power needs **n ≈ 1 seeds per arm**.
    - a 10 % change (0.164 nm) needs n ≈ 4
    - a 25 % change (0.411 nm) needs n ≈ 1

### Pilot: deterministic drag vs axial Brownian

| quantity | deterministic drag | axial Brownian | ratio | difference |
|---|---|---|---|---|
| ⟨x_A⟩ (nm) | 1.6445 ± 0.029 | 1.6372 ± 0.056 | **0.9955** | -0.00733 |
| before-centre (%) | 58.98 ± 0.13 | 58.89 ± 0.23 | **0.9984** | -0.0939 |
| ⟨θ_A⟩ (rad) | 0.1061 ± 0.003 | 0.10518 ± 0.0037 | **0.9914** | -0.000916 |
| attachment torque M_A (pN·nm) | -1.757 ± 0.05 | -1.7418 ± 0.061 | **0.9914** | 0.0152 |
| ⟨ξ_A⟩ (nm) | 0.33868 ± 0.0088 | 0.35087 ± 0.02 | **1.036** | 0.0122 |
| v (µm/s) | 0.041709 ± 0.00013 | 0.041805 ± 9.4e-05 | **1.002** | 9.63e-05 |
| inverse pitch λ⁻¹ (µm⁻¹) | -1.982 ± 0.073 | -1.9877 ± 0.14 | **1.003** | -0.00566 |
| duty ratio | 0.7421 ± 0.029 | 0.7115 ± 0.038 | **0.9588** | -0.0306 |
| median bound heads | 81.75 ± 3.1 | 78.5 ± 4.5 | **0.9602** | -3.25 |
| **Ω_odd (rad/s)** | **-0.51936 ± 0.019** | **-0.52616 ± 0.014** | **1.013** | -0.0068 |
| Ω_even (rad/s) | -0 | 0.00398 ± 0.024 | — | — |

### Stage 8 — production, independent axial noise

| arm | v (µm/s) | ω (rad/s) | ⟨x_A⟩ nm | before % | ⟨θ_A⟩ rad | M_A pN·nm | net (nm) | max back (nm) | Nb | events |
|---|---|---|---|---|---|---|---|---|---|---|
| `prod_in_s101_mirror` | 0.0414 | 0.5399 | 1.696 | 59.21 | -0.1068 | 1.768 | 4968.3 | 11.6 | 83 | 235451 |
| `prod_in_s101_native` | 0.04171 | -0.484 | 1.582 | 58.66 | 0.1015 | -1.681 | 5005.5 | 6.2 | 74 | 214971 |
| `prod_in_s102_mirror` | 0.04145 | 0.5204 | 1.52 | 58.24 | -0.1008 | 1.669 | 4974.4 | 6.12 | 85 | 244792 |
| `prod_in_s102_native` | 0.0419 | -0.5604 | 1.693 | 59.12 | 0.1089 | -1.803 | 5028.1 | 19.6 | 83 | 232305 |
| `prod_in_s103_mirror` | 0.04184 | 0.5619 | 1.641 | 59.14 | -0.1106 | 1.831 | 5021.1 | 8.45 | 90 | 255749 |
| `prod_in_s103_native` | 0.04173 | -0.5504 | 1.699 | 59.21 | 0.1123 | -1.859 | 5008.1 | 7.42 | 81 | 232877 |
| `prod_in_s104_mirror` | 0.04151 | 0.5732 | 1.722 | 59.3 | -0.1141 | 1.889 | 4981.7 | 13.3 | 65 | 193131 |
| `prod_in_s104_native` | 0.04147 | -0.5479 | 1.596 | 58.78 | 0.1059 | -1.754 | 4976.3 | 14.2 | 89 | 256820 |
| `prod_in_s105_mirror` | 0.04196 | 0.5728 | 1.729 | 59.52 | -0.1141 | 1.889 | 5034.9 | 13.3 | 82 | 235408 |
| `prod_in_s105_native` | 0.04211 | -0.5529 | 1.689 | 59.1 | 0.1108 | -1.835 | 5053.4 | 9.94 | 84 | 239258 |
| `prod_in_s106_mirror` | 0.04157 | 0.5538 | 1.575 | 58.72 | -0.1081 | 1.79 | 4988.4 | 8.06 | 75 | 217495 |
| `prod_in_s106_native` | 0.04195 | -0.5412 | 1.571 | 58.53 | 0.105 | -1.739 | 5034.6 | 7.07 | 84 | 241540 |
| `prod_in_s107_mirror` | 0.04155 | 0.516 | 1.516 | 58.09 | -0.1029 | 1.704 | 4985.8 | 6.22 | 74 | 215627 |
| `prod_in_s107_native` | 0.04183 | -0.5144 | 1.591 | 58.69 | 0.1045 | -1.731 | 5019.4 | 6.26 | 85 | 240055 |
| `prod_in_s108_mirror` | 0.04146 | 0.5563 | 1.743 | 59.4 | -0.1145 | 1.897 | 4974.9 | 7.74 | 75 | 208022 |
| `prod_in_s108_native` | 0.04194 | -0.5569 | 1.705 | 59.49 | 0.1116 | -1.848 | 5032.7 | 8.19 | 80 | 227495 |

### Stage 5 — recrossing diagnostics (production)

| hysteresis band (nm) | forward crossings | backward crossings | per zone passage |
|---|---|---|---|
| 0  <- raw jitter, not physical | 1127364 | 1127364 | 1.01e+03 |
| 0.5 | 364397 | 364397 | 327 |
| 1 | 77046 | 77046 | 69.1 |
| 2.7  <- one actin subunit | 1359 | 1360 | 1.22 |
| 5 | 1346 | 1348 | 1.21 |

- raw zone-centre sign changes: **2,271,339** (jitter-dominated — see the band-0 row)
- net forward travel 40,158 nm over 1115 zone passages; max backward excursion **19.63 nm** = 54.52 % of the zone period
- sampled axial path length 82,121,118 nm vs net 40,158 nm (ratio 2045.0). Path length is RESOLUTION-DEPENDENT (an OU path has unbounded variation); it is quoted at the production substep and must not be compared across step sizes.

### Stage 8 — deterministic drag vs axial Brownian (production)

| quantity | deterministic drag | axial Brownian | ratio | difference |
|---|---|---|---|---|
| ⟨x_A⟩ (nm) | 1.6445 ± 0.029 | 1.6407 ± 0.021 | **0.9977** | -0.00377 |
| before-centre (%) | 58.98 ± 0.13 | 58.95 ± 0.12 | **0.9994** | -0.0363 |
| ⟨θ_A⟩ (rad) | 0.1061 ± 0.003 | 0.10756 ± 0.0014 | **1.014** | 0.00146 |
| attachment torque M_A (pN·nm) | -1.757 ± 0.05 | -1.7812 ± 0.023 | **1.014** | -0.0241 |
| ⟨ξ_A⟩ (nm) | 0.33868 ± 0.0088 | 0.33797 ± 0.0074 | **0.9979** | -0.000716 |
| v (µm/s) | 0.041709 ± 0.00013 | 0.041831 ± 6.9e-05 | **1.003** | 0.000122 |
| inverse pitch λ⁻¹ (µm⁻¹) | -1.982 ± 0.073 | -2.0489 ± 0.035 | **1.034** | -0.0668 |
| duty ratio | 0.7421 ± 0.029 | 0.7478 ± 0.014 | **1.008** | 0.00567 |
| median bound heads | 81.75 ± 3.1 | 82.5 ± 1.5 | **1.009** | 0.75 |
| **Ω_odd (rad/s)** | **-0.51936 ± 0.019** | **-0.5439 ± 0.0071** | **1.047** | -0.0245 |
| Ω_even (rad/s) | -0 | 0.00538 ± 0.0048 | — | — |

### Real vs no-depletion shadow attachment bias (the load-bearing control)

| seed | ⟨x_A⟩ real (nm) | ⟨x_A⟩ shadow (nm) | Δ_depletion (nm) | before real % | before shadow % |
|---|---|---|---|---|---|
| 101 | 1.5815 | -0.1218 | 1.7033 | 58.66 | 49.08 |
| 102 | 1.6928 | -0.045997 | 1.7388 | 59.12 | 49.7 |
| 103 | 1.6987 | -0.027419 | 1.7261 | 59.21 | 49.79 |
| 104 | 1.5963 | -0.087727 | 1.684 | 58.78 | 49.34 |
| 105 | 1.6892 | -0.026614 | 1.7158 | 59.1 | 49.83 |
| 106 | 1.571 | -0.11676 | 1.6878 | 58.53 | 49.11 |
| 107 | 1.5909 | -0.096118 | 1.6871 | 58.69 | 49.3 |
| 108 | 1.7054 | -0.022305 | 1.7277 | 59.49 | 49.83 |
| **mean (n=8)** | **1.6407 ± 0.021** | **-0.068092 ± 0.015** | **1.7088 ± 0.0075** | | |

**Δ_depletion = 1.7088 ± 0.0075 nm — resolved at 227.2σ.**

### Stage 9 — controls, all under identical axial-Brownian dynamics

| control | v (µm/s) | Ω_odd (rad/s) | Ω_even | ⟨x_A⟩ nm | before % | verdict |
|---|---|---|---|---|---|---|
| full mechanism | 0.04183 | -0.5439 ± 0.0071 | 0.00538 | 1.641 | 58.95 | twirls, mirror-reversing |
| α = 0 | 0.04016 | 0 ± 0 | 0 | 0.04983 | 50.18 | no angular energy ⇒ no twirl |
| d = 0 | -1.478e-05 | -0.0001283 ± 0.00084 | -0.00275 | -0.0367 | 50.85 | no stroke ⇒ no gliding, no twirl |
| achiral lattice (ϑ₀ = 0) | 0.04016 | 0 ± 0 | 0 | 0 | 100 | no chirality ⇒ no mirror-odd rotation |
| Brownian OFF | 0.04173 | -0.526 ± 0.011 | 0.0059 | 1.645 | 58.98 | deterministic positive control |

### Numerical health

- arms **56**, events **13,545,568**, stochastic substeps **1,388,289,068**, bridge draws **1,715,304,036**
- max event discontinuity |ΔX| = **0 nm**, |ΔΘ| = **0 rad**
- max roll closure residual |γ_Θ Θ̇ − ΣM| = **1.38e-10 pN·nm**
- free-diffusion substeps (Nb = 0): **2,468**; zero-bound equilibrations 2,476
- angular branch crossings: **0**
- travel-cap hits: 0
