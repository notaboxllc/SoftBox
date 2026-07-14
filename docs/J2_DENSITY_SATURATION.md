# Passive J2 free-gliding density saturation

## Design

The density gate compares baseline, the smallest arm that changed the deterministic pose/early transmission
(`kappa=0.1 pN nm/rad`), and the strongest arm retained after the deterministic benefit/preload screen
(`kappa=3 pN nm/rad`). Baseline and the smallest arm used the paired grid

```text
density = {50,100,250,500,1000,2000,4000,8000} motors/um^2
seed    = {0,1}
dt      = 1e-5 s
steps   = 30000 (0.30 s)
```

The κ=0.1 d8000 trajectories then triggered the preregistered stop gate: the seeds gave 18.83 and 8.62 µm/s,
one lost full-bed coverage, and their t interval was ±64.84 µm/s. The stronger κ=3 arm was consequently stopped
at density 4,000 rather than completing an unlicensed d8000 escalation.

Every trajectory uses the exact canonical stack with `-gpu -full -grid -matbox 50 -coltol 10`; only the explicit
default-off `-j2torsion` value differs. The directed velocity is the least-squares `-x` centroid drift over the
second half of the run. Runs are short pilot trajectories rather than the repository's >=1 s production gliding
standard, so paired effects and fit sensitivity are emphasized and confidence intervals with only two seeds are
treated as screening intervals, not definitive uncertainty estimates.

The reproducible command is:

```bash
./scripts/run_j2_conformation.sh density 30000
python3 scripts/j2_analyze.py --section density
```

For each arm, the bounded model is

```text
V(rho) = V_inf rho / (rho + rho_half).
```

It is compared with an unconstrained linear alternative, and refit after omitting the highest-density point.
Native free-glide episode/age diagnostics at selected low, intermediate, and high density use the CPU-only
per-timestep census (`scripts/run_j2_conformation.sh native_glide 12000`); the GPU density logs sample state,
angle, force, energy, torque, and anchor extension every 1 ms.

## Directed velocity and paired effects

Values are mean directed velocity in µm/s followed by a 95% t interval. `Delta` is the paired arm-minus-baseline
difference; with only two seeds these are deliberately treated as pilot intervals.

| density (µm^-2) | baseline | kappa=0.1 | paired Delta | kappa=3 | paired Delta |
|---:|---:|---:|---:|---:|---:|
| 50 | -0.269 +/- 0.076 | -0.225 +/- 0.330 | +0.044 +/- 0.254 | -0.208 +/- 0.407 | +0.061 +/- 0.483 |
| 100 | -0.220 +/- 0.013 | -0.047 +/- 1.239 | +0.174 +/- 1.252 | -0.248 +/- 0.438 | -0.028 +/- 0.426 |
| 250 | -0.003 +/- 0.559 | +0.023 +/- 0.934 | +0.026 +/- 0.375 | +0.004 +/- 0.152 | +0.007 +/- 0.407 |
| 500 | +0.547 +/- 0.515 | +0.754 +/- 0.070 | +0.207 +/- 0.445 | +0.514 +/- 0.457 | -0.033 +/- 0.057 |
| 1,000 | +2.309 +/- 0.108 | +1.599 +/- 0.032 | -0.710 +/- 0.076 | +2.176 +/- 0.616 | -0.133 +/- 0.508 |
| 2,000 | +3.996 +/- 0.394 | +4.432 +/- 5.965 | +0.436 +/- 6.359 | +3.197 +/- 3.628 | -0.800 +/- 4.021 |
| 4,000 | +6.157 +/- 0.864 | +6.096 +/- 1.861 | -0.062 +/- 2.725 | +6.254 +/- 0.019 | +0.097 +/- 0.845 |
| 8,000 | +9.771 +/- 5.241 | +13.723 +/- 64.839 | +3.953 +/- 59.597 | fidelity-gated | -- |

The apparently precise negative weak-arm difference at density 1,000 is not monotone in density or stiffness and
is not reproduced by kappa=3. No retained arm gives a resolved, directionally consistent velocity gain.

At density 4,000 the principal mechanical readouts are:

| kappa | mean bound | raw geometrically reachable | force/bound (pN) | F8 axial (pN) | F8 transverse (pN) | max F8 (pN) | mean J2 | mean J2 energy (pN nm) | max J2 torque (pN nm) | anchor (nm) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0 | 11.77 | 15.50 | +0.276 | +0.279 | 2.867 | 10.78 | 122.48 deg | 0 | 0 | 7.327 |
| 0.1 | 12.11 | 16.20 | +0.248 | +0.242 | 2.923 | 26.86 | 123.34 deg | 0.013 | 0.204 | 7.299 |
| 3 | 12.13 | 16.60 | +0.245 | +0.250 | 2.877 | 11.48 | 125.09 deg | 0.311 | 5.893 | 7.321 |

The kappa=0.1 maximum is a rare trajectory extremum, not a shift in its mean F8 distribution; the stronger arm
does not reproduce it. Mean dwell time at this density is 0.597/0.587/0.590 ms and duty is
0.778/0.768/0.767 for baseline/weak/strong. Bound count, raw reach count, kinetics, force per bound motor,
transverse load, and anchor extension therefore show no useful monotone transmission change. `meanReach` is the
raw number of current unbound-head geometries passing reach, not `bound union reach`, and must not be interpreted
as an occupancy denominator.

## Saturation fits

Formal fits on the common, fidelity-valid domain through density 4,000 are:

| kappa | V_inf (µm/s) | rho_1/2 (µm^-2) | MM SSE | linear SSE |
|---:|---:|---:|---:|---:|
| 0 | 21.02 | 9,402 | 1.096 | 1.572 |
| 0.1 | 22.03 | 9,991 | 1.315 | 1.792 |
| 3 | 73.71 | 43,092 | 0.807 | 0.664 |

These are **not identified plateau estimates**. The highest valid velocity increment remains +2.161 µm/s for
baseline, +1.664 for kappa=0.1, and +3.057 for kappa=3 between densities 2,000 and 4,000. The fitted half-density
lies beyond the sampled domain for every arm; kappa=3 is better described by the unbounded linear alternative.
Omitting its density-4,000 point sends the formal fit to `V_inf=909 µm/s, rho_1/2=5.49e5`, demonstrating fit
instability rather than saturation. Including the rejected weak-arm density-8,000 point likewise drives its fit
to `V_inf=1,711 µm/s` at the `rho_1/2=1e6` search bound. Even the baseline extrapolation exceeds the independently
measured approximately 14 µm/s force-zero bracket.

## Interpretation and gate

The density screen finds no leftward knee shift and does not resolve either `V_inf` or `rho_1/2`. Through the
matched valid range, kappa=0.1 and kappa=3 track baseline within seed variability while changing J2 pose/energy;
neither increases force per bound motor. The one weak-arm point that is separated from baseline is slower, not a
useful saturation improvement.

At density 8,000, baseline and kappa=0.1 both develop large rare F8/transverse forces (mean maxima about
85/68 pN), one seed per arm loses full-bed coverage, and the weak-arm seeds diverge by 10.21 µm/s. That point is
excluded from biological inference and triggers the preregistered stop on stronger-arm escalation. It is a
trajectory/fidelity limit, not evidence that kappa=0.1 itself causes overconstraint.

Thus the free-gliding result supplies no evidence for beneficial passive transmission. Because the pilot grid
never reaches a valid plateau, it also cannot by itself prove equality of the asymptotic parameters; it supports
the mechanical-null classification only in combination with the deterministic and powered force-velocity gates.
