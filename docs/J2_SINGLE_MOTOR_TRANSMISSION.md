# Passive J2 deterministic transmission

## Potential and stiffness ladder

The opt-in term is

```text
U_J2 = 1/2 kappa_J2 (theta_J2 - theta0)^2
tau_lever = -kappa_J2 (theta_J2 - theta0) axis_J2
tau_rod   = -tau_lever
```

`kappa_J2` is supplied by `-j2torsion` in pN·nm/rad, converted once to N·m/rad. `theta0=124.0°` is
state-independent. No timestep, drag, nucleotide state, force cap, or controller fraction occurs in this torque.

At 298.15 K (`kT=4.117 pN·nm`) the preregistered logarithmic ladder is:

| κ (pN·nm/rad) | label | U(10°)/kT | U(20°)/kT | U(30°)/kT |
|---:|---|---:|---:|---:|
| 0 | native | 0 | 0 | 0 |
| 0.01 | very weak | 0.00004 | 0.00015 | 0.00033 |
| 0.03 | very weak | 0.00011 | 0.00044 | 0.00100 |
| 0.1 | weak | 0.00037 | 0.00148 | 0.00333 |
| 0.3 | weak | 0.00111 | 0.00444 | 0.00999 |
| 1 | weak/moderate | 0.00370 | 0.0148 | 0.0333 |
| 3 | moderate | 0.0111 | 0.0444 | 0.0999 |
| 10 | moderate/strong | 0.0370 | 0.148 | 0.333 |
| 30 | thermal-scale | 0.111 | 0.444 | 0.999 |
| 100 | strong | 0.370 | 1.480 | 3.330 |
| 300 | deliberate high arm | 1.110 | 4.440 | 9.990 |

These are interpretability brackets, not experimentally measured myosin stiffnesses.

## Protocol

One motor is forcibly bound to a straight, held filament. Motor and filament Brownian forcing are zero. After a
40 ms ADP·Pi equilibration, the nucleotide is switched once to ADP and the full canonical force stack is integrated
for 1 ms. Separate identical-pose challenges impose `v={0,+8,-8}` µm/s. Three 4/8/12 nm out–hold–back–hold closed
cycles probe work closure. The complete pose, J2 axis/energy/torque, F8 decomposition, tail extension, J1 angle, and
DIRSWING error are emitted as `J2DET` and `J2SLIDE`.

The timestep check uses equal 40 ms equilibration and equal 1 ms stroke windows:

```bash
./scripts/run_gliding.sh -servoaudit -j2audit -density 1 -seed 1 \
  -j2torsion K -dt {1e-5,5e-6,2.5e-6} {4000,8000,16000}
```

## Pre-stroke compatibility gate

The 124° ADP rest does not match the deterministic ADP·Pi pose. More importantly, the zero-stiffness coordinate is
nearly neutral: the native deterministic equilibrium remains at 99.64°, but even κ=0.01 selects an almost
antiparallel 179.97° rod–lever pose. This is a finite change caused by an infinitesimal bias, not a force-amplitude
response proportional to κ.

| κ | pre-stroke J2 | pre-stroke torque (pN·nm) | pre-stroke energy (kT) |
|---:|---:|---:|---:|
| 0 | 99.64° | 0 | 0 |
| 0.1 | 179.86° | −0.097 | 0.0115 |
| 3 | 175.68° | −2.706 | 0.296 |
| 10 | 167.48° | −7.589 | 0.700 |
| 30 | 153.90° | −15.66 | 0.993 |
| 100 | 138.18° | −24.75 | 0.744 |
| 300 | 142.10° at dt=1e-5 | −94.76 | 3.64 |

The κ≥30 arms fail the preload gate: they carry roughly thermal-scale pre-stroke energy and 16–95 pN·nm internal
torque, with substantial anchor and transverse-force changes. κ=10 was timestep-stable but already carries
0.70 kT and 7.59 pN·nm in the pre-stroke pose, while giving no sustained transmission benefit below; it therefore
failed the deterministic benefit gate rather than being promoted simply because it remained numerically stable.
κ=0.1, 1, and 3 were retained as weak through stronger nonpathological pilot arms; κ=3 is the strongest reported
ensemble arm.

## Transmission at the finest timestep

The state switch itself changes only the DIRSWING target: pose, J2 angle, F8, anchor extension, and J2 energy are
identical immediately before and immediately after ADP·Pi→ADP, confirming that the passive term has no hidden
nucleotide dispatch. The subsequent resolved transient is:

| κ | phase | J2 | DIRSWING work (kT) | F8 axial (pN) | F8 transverse (pN) | DIRSWING error |
|---:|---|---:|---:|---:|---:|---:|
| 0 | pre / immediate | 99.64° | 0 | −0.212 | 2.771 | 12.29° / 55.31° |
| 0 | 0.020 ms | 102.26° | 3.64 | −0.926 | 2.897 | 33.66° |
| 0 | 0.100 ms | 103.72° | 5.01 | +0.152 | 2.875 | 13.66° |
| 0.1 | pre / immediate | 179.86° | 0 | +0.005 | 0.208 | 0.11° / 59.89° |
| 0.1 | 0.020 ms | 157.37° | 4.49 | −0.819 | 0.289 | 35.41° |
| 0.1 | 0.100 ms | 128.87° | 6.27 | +0.355 | 0.353 | 6.81° |
| 3 | pre / immediate | 175.68° | 0 | −0.158 | 0.153 | 3.08° / 62.95° |
| 3 | 0.020 ms | 159.32° | 4.99 | −0.957 | 0.236 | 36.35° |
| 3 | 0.100 ms | 128.84° | 6.86 | +0.294 | 0.369 | 6.15° |

The spring arms cause DIRSWING to do more work because it encounters a different pose; the J2 spring does not
provide work at the state switch. Most of the changed mechanics is a redirection away from the native transverse
constraint/anchor configuration, not an increase in sustained axial F8.

Zero imposed sliding at `dt=2.5e-6 s`:

| κ | mean J2 | peak tip–site Δ (nm) | relaxed Δ, 0.30–0.50 ms (nm) | max F8 (pN) | max F8 transverse (pN) | max J2 torque (pN·nm) |
|---:|---:|---:|---:|---:|---:|---:|
| 0 | 106.32° | +0.790 | −0.272 | 3.049 | 2.911 | 0 |
| 0.1 | 127.70° | +0.897 | −0.213 | 0.933 | 0.458 | 0.091 |
| 3 | 127.82° | +0.884 | −0.336 | 1.065 | 0.460 | 2.862 |
| 10 | 127.61° | +0.884 | −0.437 | 1.246 | 0.464 | 8.213 |
| 30 | 125.80° | +0.840 | −0.259 | 1.222 | 1.005 | 15.38 |
| 100 | 124.70° | +0.769 | −0.212 | 1.568 | 1.568 | 22.99 |

The weak spring slightly increases the resolved early displacement, but it does **not** increase useful axial F8.
Instead it selects a different three-body pose and removes much of the native transverse F8 load. The κ=3 arm
does not retain more late displacement than baseline. Under +8 µm/s the relaxed values are −0.044 nm (baseline),
+0.051 nm (κ=0.1), and −0.068 nm (κ=3); under −8 µm/s they are −0.497, −0.472, and −0.597 nm. There is no monotone
transmission benefit with stiffness.

The pose redistribution is also visible outside J2. At zero slide and `dt=2.5e-6 s`, final tail-anchor extension
is 3.10 nm in baseline but 0.545/0.548 nm for κ=0.1/3. Final J1 angle and DIRSWING error move from
49.94°/11.13° in baseline to 58.89°/1.80° and 58.98°/1.63°. The spring therefore lets the lever approach the
DIRSWING target while unloading the anchor and transverse F8; it does not turn that rearrangement into retained
actin-level displacement. Full rod, lever, and head centre/axis records at the pre, immediate, early, plateau, and
cycle poses are retained as `J2DET` rows in the raw logs; the analysis script reproduces the scalar transmission
table above.

DIRSWING work remains a finite stroke. At the finest timestep and 12 nm closed-cycle amplitude, net DIRSWING work
over two cycles is −0.089 kT (baseline), −0.089 kT (κ=0.1), and −0.078 kT (κ=3), rather than repeated positive work.

## Timestep gate

The 0.30–0.50 ms relaxed displacement, mean J2 coordinate, pre-stroke energy, final energy, and qualitative ordering
are stable over `dt={1e-5,5e-6,2.5e-6}` for κ≤30. The instantaneous peak F8/displacement shrinks as the canonical
explicit rotational transient is time-resolved; this happens in baseline too. Relative conclusions survive:
weak J2 bias selects a new pose, does not amplify axial load, and does not give monotone late retention.

κ=100 has noticeably poorer transient-energy convergence, and κ=300 develops large torque and altered final pose.
At `dt=1e-5`, κ=300 begins with 94.8 pN·nm torque and 3.64 kT stored, reaches 69.1 pN·nm during the stroke,
and gives a −1.58 nm peak under the resistive challenge rather than the weak-arm response. Together with their
preload and changed poses, those arms are rejected rather than interpreted biologically.

## Deterministic gate decision

The deterministic result is not beneficial passive transmission. It is a sensitive neutral-coordinate selection:
a tiny spring changes internal geometry, mostly reduces transverse load, and yields little robust late-displacement
gain. κ=0.1/1/3 proceeded only to test whether an ensemble operational benefit emerges despite this ambiguous
single-motor mechanism. κ≥30 stopped at the fidelity gate.
