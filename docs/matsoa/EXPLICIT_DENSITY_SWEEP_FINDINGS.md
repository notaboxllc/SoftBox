# Explicit density-saturation sweep (Phase C) — GPU gliding velocity, explicit vs calibrated

Both models run device-resident on the GPU at matched geometry (same buildGlide2D density → same N/area),
free binding, dt=2.5e-6. Velocity = LS slope of the resident filament centroid·b̂ over the measured window
(negative = pointed-first = correct). Signed velocity reported; |speed| used for the saturation fit.

## C3. Primary observables by density
| model | density | N | vel µm/s (±SE) | |speed| | avgBound | continuity | boundFrac | netForce pN | invalid |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| calibrated-s2-l40 | 100 | 300 | -0.687±0.293 | 0.687 | 0.52 | 0.40 | 0.0017 | 0.28 | 0 |
| calibrated-s2-l40 | 200 | 600 | -1.196±0.281 | 1.196 | 1.03 | 0.65 | 0.0017 | 0.46 | 0 |
| calibrated-s2-l40 | 400 | 1200 | -1.809±0.072 | 1.809 | 2.05 | 0.88 | 0.0017 | 0.76 | 0 |
| calibrated-s2-l40 | 700 | 2100 | -2.437±0.056 | 2.437 | 3.43 | 0.97 | 0.0016 | 1.05 | 0 |
| calibrated-s2-l40 | 1000 | 3000 | -2.475±0.064 | 2.475 | 4.69 | 0.99 | 0.0016 | 1.07 | 0 |
| calibrated-s2-l40 | 1500 | 4500 | -2.612±0.133 | 2.612 | 6.61 | 1.00 | 0.0015 | 1.14 | 0 |
| calibrated-s2-l40 | 2000 | 6000 | -2.792±0.123 | 2.792 | 9.44 | 1.00 | 0.0016 | 1.21 | 0 |
| calibrated-s2-l40 | 2500 | 7500 | -2.898±0.038 | 2.898 | 11.71 | 1.00 | 0.0016 | 1.23 | 0 |
| calibrated-s2-l40 | 3000 | 9000 | -2.877±0.057 | 2.877 | 14.16 | 1.00 | 0.0016 | 1.22 | 0 |
| explicit-s2-l40 | 100 | 300 | -0.994±0.346 | 0.994 | 0.47 | 0.39 | 0.0015 | 0.37 | 0 |
| explicit-s2-l40 | 200 | 600 | -1.503±0.108 | 1.503 | 0.89 | 0.61 | 0.0015 | 0.61 | 0 |
| explicit-s2-l40 | 400 | 1200 | -2.308±0.092 | 2.308 | 1.79 | 0.83 | 0.0015 | 0.97 | 0 |
| explicit-s2-l40 | 700 | 2100 | -2.625±0.181 | 2.625 | 2.79 | 0.95 | 0.0013 | 1.15 | 0 |
| explicit-s2-l40 | 1000 | 3000 | -3.291±0.224 | 3.291 | 4.09 | 0.98 | 0.0014 | 1.41 | 0 |
| explicit-s2-l40 | 1500 | 4500 | -3.547±0.119 | 3.547 | 5.97 | 1.00 | 0.0013 | 1.51 | 0 |
| explicit-s2-l40 | 2000 | 6000 | -3.710±0.125 | 3.710 | 7.64 | 1.00 | 0.0013 | 1.63 | 0 |
| explicit-s2-l40 | 2500 | 7500 | -3.996±0.077 | 3.996 | 9.15 | 1.00 | 0.0012 | 1.73 | 0 |
| explicit-s2-l40 | 3000 | 9000 | -4.205±0.030 | 4.205 | 11.09 | 1.00 | 0.0012 | 1.82 | 0 |
| explicit-s2-l40 | 3500 | 10500 | -4.013±0.034 | 4.013 | 12.97 | 1.00 | 0.0012 | 1.75 | 0 |

## C4. Saturation characterization (|v|(ρ) = v_max·ρ/(K_ρ+ρ))
- **calibrated-s2-l40**: v_max ≈ 3.246 µm/s, half-saturation K_ρ ≈ 317 /µm², ρ@90%plateau ≈ 2852, ρ@95% ≈ 6021, fit RMS 0.083 µm/s; descriptive 5%-plateau onset ≈ 1000.0 /µm².
- **explicit-s2-l40**: v_max ≈ 4.594 µm/s, half-saturation K_ρ ≈ 423 /µm², ρ@90%plateau ≈ 3804, ρ@95% ≈ 8030, fit RMS 0.115 µm/s; descriptive 5%-plateau onset ≈ 2000.0 /µm².

## C5. Explicit vs calibrated speed ratio by density
| density | explicit |speed| | calibrated |speed| | exp/cal ratio | exp avgBound | cal avgBound |
|---:|---:|---:|---:|---:|---:|
| 100 | 0.994 | 0.687 | 1.45× | 0.47 | 0.52 |
| 200 | 1.503 | 1.196 | 1.26× | 0.89 | 1.03 |
| 400 | 2.308 | 1.809 | 1.28× | 1.79 | 2.05 |
| 700 | 2.625 | 2.437 | 1.08× | 2.79 | 3.43 |
| 1000 | 3.291 | 2.475 | 1.33× | 4.09 | 4.69 |
| 1500 | 3.547 | 2.612 | 1.36× | 5.97 | 6.61 |
| 2000 | 3.710 | 2.792 | 1.33× | 7.64 | 9.44 |
| 2500 | 3.996 | 2.898 | 1.38× | 9.15 | 11.71 |
| 3000 | 4.205 | 2.877 | 1.46× | 11.09 | 14.16 |

- Plateau speed: explicit v_max ≈ 4.594 vs calibrated 3.246 µm/s (ratio 1.42×).
- Half-saturation: explicit K_ρ ≈ 423 vs calibrated 317 /µm² (approach steepness).
- Interpretation: differences that track avgBound/continuity are binding/chemistry-driven, not purely mechanical.
