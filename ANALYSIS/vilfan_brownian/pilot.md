
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
