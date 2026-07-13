# Passive J2 force–velocity and episode-kernel results

## Screen design

Arms passing the deterministic preload gate were `kappa={0,0.1,1,3}` pN·nm/rad. The initial paired screen used
`v={0,8,12,14,16,18}` µm/s, four CPU seeds, `dt=5e-6 s`, 16,000 steps, density 300 µm⁻², and the exact canonical
motor stack. Eight paired seeds were available at 12/14/16 for baseline and κ=3 after zero refinement.

Because this low-density zero was shallow, baseline and κ=3 were repeated at density 1000 µm⁻² with eight paired
seeds. Each force point contains about 1,700 complete episodes across seeds. The powered rerun also enabled the
read-only J2 census at every step.

Commands are reproduced by `scripts/run_j2_conformation.sh fv` and `fv_powered`; results are parsed by
`scripts/j2_analyze.py --section fv`.

## Initial four-arm screen

Mean `fbar_avail` in pN (95% t intervals are typically 0.09–0.26 pN and are retained in the analysis output):

| κ | v=0 | v=8 | v=12 | v=14 | v=16 | v=18 |
|---:|---:|---:|---:|---:|---:|---:|
| 0 | +0.476 | +0.169 | +0.015 | −0.063 | −0.140 | −0.147 |
| 0.1 | +0.487 | +0.169 | +0.052 | −0.032 | −0.086 | −0.160 |
| 1 | +0.472 | +0.212 | +0.060 | −0.050 | −0.099 | −0.152 |
| 3 | +0.483 | +0.168 | +0.020 | −0.038 | −0.124 | −0.173 |

No arm measurably increases zero-velocity force per available or bound head. Occupancy, attachment impulse, and
lifetime also lack a monotone stiffness response. The eight-seed κ=3 refinement is indistinguishable from baseline.

## Powered zero bracket

At density 1000 µm⁻²:

| κ | v | fbar_avail (pN, mean ± 95% CI) | fbar_bound (pN) | mean bound | attachment rate (s⁻¹ per available) | lifetime (ms) | mean episode impulse (pN·ms) | episodes |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0 | 10 | +0.101 ± 0.047 | +0.124 | 2.488 | 1,345 | 0.611 | +0.075 | 1,736 |
| 0 | 12 | +0.052 ± 0.048 | +0.064 | 2.380 | 1,410 | 0.578 | +0.037 | 1,759 |
| 0 | 14 | −0.011 ± 0.058 | −0.014 | 2.230 | 1,469 | 0.553 | −0.008 | 1,721 |
| 0 | 16 | −0.091 ± 0.077 | −0.113 | 2.237 | 1,493 | 0.546 | −0.060 | 1,750 |
| 3 | 10 | +0.121 ± 0.080 | +0.147 | 2.427 | 1,359 | 0.603 | +0.090 | 1,717 |
| 3 | 12 | +0.062 ± 0.072 | +0.076 | 2.367 | 1,393 | 0.587 | +0.044 | 1,722 |
| 3 | 14 | −0.007 ± 0.084 | −0.009 | 2.277 | 1,441 | 0.563 | −0.005 | 1,730 |
| 3 | 16 | −0.084 ± 0.087 | −0.104 | 2.222 | 1,479 | 0.550 | −0.057 | 1,727 |

The zero of the mean curve by 12–14 interpolation is 13.66 µm/s for baseline and 13.81 µm/s for κ=3. Per-seed
local linear fits over 10–16 µm/s give 13.90 ± 2.35 and 13.99 ± 2.42 µm/s respectively; their paired difference is
**+0.095 ± 2.455 µm/s (95% CI)**. The interval excludes a resolved claim that passive J2 stiffness raises or
lowers the ceiling in this screen.

The mean local slopes are −0.0319 pN/(µm/s) for baseline and −0.0342 for κ=3; the paired slope change is
−0.0023 ± 0.0130 pN/(µm/s). The larger eight-seed bracket therefore does not confirm the earlier four-seed
steepening impression.

Paired κ=3 minus baseline force differences are +0.020 ± 0.068 pN at 10, +0.010 ± 0.072 at 12,
+0.004 ± 0.076 at 14, and +0.007 ± 0.095 at 16 µm/s. None is distinguishable from seed variation.

The mechanical census shows internal pose selection without force amplification:

| v | arm | mean J2 | F8 axial (pN) | F8 transverse (pN) | DIRSWING error | anchor (nm) | mean J2 energy (pN·nm) | max J2 torque (pN·nm) |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 10 | baseline | 116.15° | +0.124 | 2.893 | 13.20° | 7.223 | 0 | 0 |
| 10 | κ=3 | 119.68° | +0.147 | 2.888 | 13.22° | 7.180 | 0.306 | 6.285 |
| 12 | baseline | 115.63° | +0.064 | 2.895 | 13.54° | 7.214 | 0 | 0 |
| 12 | κ=3 | 119.48° | +0.076 | 2.898 | 13.35° | 7.199 | 0.310 | 6.271 |
| 14 | baseline | 115.47° | −0.014 | 2.907 | 13.87° | 7.227 | 0 | 0 |
| 14 | κ=3 | 119.07° | −0.009 | 2.895 | 13.52° | 7.178 | 0.306 | 6.219 |
| 16 | baseline | 115.36° | −0.113 | 2.914 | 13.86° | 7.222 | 0 | 0 |
| 16 | κ=3 | 118.92° | −0.104 | 2.902 | 13.66° | 7.193 | 0.305 | 6.243 |

κ=3 shifts the all-bound mean bend by about 3.5° and stores 0.074–0.075 kT on average, but F8 magnitude/split,
anchor extension, DIRSWING error, occupancy, attachment rate, and lifetime remain near baseline. Its rare maximum
energy is about 6.5 pN·nm (1.6 kT), consistent with the broad native tail rather than a persistent preload.

## Episode-kernel attribution

Baseline and κ=3 were rerun with `-epkernel` at all six velocities, four paired seeds. The kernel accounts exactly
for all folded bound steps (`EPKSTAT` completeness identity in every log).

The effect is age-dependent:

| v | metric | baseline | κ=3 |
|---:|---|---:|---:|
| 0 | force at age 0.025 ms (pN) | +0.316 | +0.685 |
| 8 | force at age 0.025 ms (pN) | +0.334 | +0.622 |
| 0 | force at age 0.50 ms (pN) | +0.783 | +0.518 |
| 8 | force at age 0.50 ms (pN) | +0.229 | +0.056 |
| 12 | force at age 0.50 ms (pN) | +0.254 | −0.321 |
| 14 | force at age 0.50 ms (pN) | +0.066 | −0.332 |
| 16 | force at age 0.50 ms (pN) | +0.182 | −0.705 |
| 18 | force at age 0.50 ms (pN) | −0.043 | −0.630 |

κ=3 therefore amplifies the earliest force transient at low velocity but makes the survivor-conditioned late force
more negative as velocity rises. It does not simply scale the baseline waveform.

Episode-level decomposition is consistent:

| v | arm | lifetime (ms) | I+ (pN·ms) | I− (pN·ms) | net (pN·ms) |
|---:|---|---:|---:|---:|---:|
| 0 | baseline | 0.739 | +0.907 | −0.494 | +0.413 |
| 0 | κ=3 | 0.712 | +0.880 | −0.453 | +0.427 |
| 12 | baseline | 0.575 | +0.539 | −0.511 | +0.028 |
| 12 | κ=3 | 0.559 | +0.508 | −0.510 | −0.002 |
| 16 | baseline | 0.553 | +0.473 | −0.538 | −0.065 |
| 16 | κ=3 | 0.542 | +0.443 | −0.550 | −0.107 |
| 18 | baseline | 0.525 | +0.439 | −0.530 | −0.091 |
| 18 | κ=3 | 0.545 | +0.441 | −0.555 | −0.114 |

Force-reversal time remains about 0.03 ms and the reversal fraction remains 98.5–99.6%. Mean tip–site strain range
remains about 10.0–10.6 nm. Lifetimes change little. The main attribution is therefore a changed strain/force
trajectory (larger early force, more negative late force), not a large change in force-selective survival.

The independent 0.15–0.25 ms torque balance rules out simple load transfer into the other angular constraints:

| v | arm | F8 axial (pN) | F8 torque (pN·nm) | F9 torque (pN·nm) | AXLOCK torque (pN·nm) | J2 torque proxy (pN·nm) | anchor (nm) |
|---:|---|---:|---:|---:|---:|---:|---:|
| 0 | baseline | +0.435 | 29.64 | 23.58 | 25.04 | 0 | 7.27 |
| 0 | κ=3 | +0.363 | 29.09 | 22.29 | 24.88 | 1.40 | 7.11 |
| 12 | baseline | −0.047 | 28.87 | 22.41 | 24.37 | 0 | 7.25 |
| 12 | κ=3 | −0.167 | 28.64 | 21.79 | 24.92 | 1.22 | 7.54 |
| 16 | baseline | −0.167 | 28.95 | 22.34 | 23.97 | 0 | 7.28 |
| 16 | κ=3 | −0.346 | 28.97 | 21.96 | 25.16 | 1.19 | 7.36 |

The J2 torque proxy is `κ` times the mean signed angle deviation. Neither F9 nor AXLOCK torque grows sharply;
anchor extension remains within about 0.3 nm of baseline. At high velocity the main change is the more negative
axial F8 component already seen in the kernel.

## Force–velocity decision

The age-conditioned kernel contains a waveform redistribution, but the powered eight-seed mean slope, V0, force,
occupancy, and kinetics are all unresolved from baseline. This is not a supported Outcome B and there is no
force-amplification result. Force–velocity data therefore do not justify retaining the spring; the primary study
classification depends on the density-saturation gate.
