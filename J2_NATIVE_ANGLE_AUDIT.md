# Native J2 angle audit

## Coordinate and protocol

The logger is enabled only by `-j2audit`; it writes no simulation array. The reported coordinate is

```text
theta_J2 = atan2(|rod.u x lever.u|, rod.u dot lever.u),  0 <= theta_J2 <= pi
axis_J2  = normalize(rod.u x lever.u)
```

There is no body-supplied plane that remains coherent over the native ensemble: the ADP·Pi axis is nearly
isotropic and even the ADP axis has only about 0.7 resultant coherence. A signed scalar angle would therefore
invent a reference. The audit reports the shortest unsigned angle together with its directed shortest-rotation
axis. Histograms use 0.1-degree bins.

The clamp audit command was, for every `v={0,8,12,14,16}` and CPU seed `s={0,1,2,3}`:

```bash
./scripts/run_gliding.sh -matbox 50 -density 300 -dt 5e-6 \
  -seed "$s" -vclamp "$v" -j2audit 16000
```

This gives 0.080 s per run and a 0.053 s measurement window after warm-up. The complete raw records are under
`RUN_LOGS/j2_conformation/native_clamp/`; `scripts/j2_analyze.py --section native` reproduces the tables.

## Zero-velocity state and rest-pose provenance

At `v=0`, pooling all four seeds gives:

| population | samples | mean ± SD | P05 / P25 / median / P75 / P95 |
|---|---:|---:|---:|
| bound ADP·Pi | 4,948 | 103.00° ± 39.19° | 34.13° / 75.48° / 103.98° / 134.30° / 159.78° |
| bound ADP | 33,188 | 122.15° ± 24.87° | 79.55° / 105.80° / 123.23° / 141.25° / 157.53° |
| bound ADP, age 0.30–0.50 ms | 7,027 | 122.05° ± 25.34° | 80.60° / 105.43° / **124.0°** / 141.93° / 157.58° |
| all bound states | 40,863 | 120.02° ± 27.57° | 71.58° / 103.58° / 122.08° / 140.88° / 157.85° |

Except for the pooled plateau median (computed from the merged 0.1° histogram), percentiles in this and the
free-glide table are the means of the per-seed empirical percentiles. The analysis output labels that distinction.

The primary rest angle is therefore 124.0°, the exact pooled histogram median of the unmodified, zero-velocity,
bound-ADP 0.30–0.50 ms plateau. It was selected before any spring run. It is not a fitted structural angle.

The deterministic ADP·Pi equilibrium used by the transmission harness is 99.643°, so this pose-matched ADP rest
has a 24.357° pre-stroke mismatch before the spring is applied. In the stochastic population the ADP·Pi coordinate
is much broader than this one deterministic pose.

## Velocity dependence

State-separated means remain broad across the clamp grid:

| velocity (µm/s) | ADP·Pi mean ± SD | ADP mean ± SD |
|---:|---:|---:|
| 0 | 103.00° ± 39.19° | 122.15° ± 24.87° |
| 8 | 100.04° ± 39.78° | 122.39° ± 26.16° |
| 12 | 95.14° ± 42.15° | 120.24° ± 26.31° |
| 14 | 98.72° ± 41.26° | 121.30° ± 25.33° |
| 16 | 96.06° ± 40.90° | 121.26° ± 24.91° |

The age-matched ADP plateau remains broad and moves little with velocity:

| velocity (µm/s) | samples | pooled mean ± SD | P05 / P25 / median / P75 / P95 |
|---:|---:|---:|---:|
| 0 | 7,027 | 122.05° ± 25.34° | 80.60° / 105.43° / 123.25° / 141.93° / 157.58° |
| 8 | 6,612 | 122.70° ± 25.22° | 79.20° / 107.20° / 124.95° / 142.45° / 157.45° |
| 12 | 5,932 | 120.78° ± 25.25° | 75.90° / 105.43° / 121.98° / 138.43° / 158.00° |
| 14 | 5,746 | 121.44° ± 24.08° | 78.15° / 106.23° / 122.93° / 138.23° / 158.00° |
| 16 | 5,375 | 121.30° ± 23.97° | 78.15° / 106.75° / 124.05° / 137.95° / 157.90° |

Thus sliding speed does not reveal a sharply shifted native rest pose. It changes the episode force/strain history
far more than it changes the plateau J2 distribution.

## Episode and load associations

The coordinate relaxes with attachment age rather than behaving as an unconstrained white-noise angle. Selected
pooled age bins are:

| velocity | age bin (ms) | samples | mean J2 | mean F8 axial (pN) | mean F8 transverse (pN) | DIRSWING error |
|---:|---:|---:|---:|---:|---:|---:|
| 0 | 0–0.025 | 1,370 | 105.15° | +0.420 | 3.834 | 59.40° |
| 0 | 0.10–0.125 | 1,306 | 113.86° | +0.142 | 2.929 | 16.93° |
| 0 | 0.30–0.325 | 1,088 | 120.11° | +0.661 | 2.803 | 8.60° |
| 0 | 0.50–0.525 | 811 | 123.00° | +0.713 | 2.801 | 7.12° |
| 8 | 0–0.025 | 1,380 | 101.66° | +0.366 | 3.760 | 57.77° |
| 8 | 0.50–0.525 | 715 | 124.70° | +0.295 | 2.899 | 7.22° |
| 12 | 0–0.025 | 1,410 | 99.09° | +0.280 | 3.776 | 57.55° |
| 12 | 0.50–0.525 | 643 | 121.87° | +0.129 | 2.980 | 7.20° |
| 16 | 0–0.025 | 1,310 | 99.15° | +0.329 | 3.755 | 57.94° |
| 16 | 0.50–0.525 | 596 | 123.08° | +0.180 | 2.922 | 7.69° |

The early coordinate moves from roughly 99–105° toward 122–125° as DIRSWING target error relaxes. Sliding changes
the axial F8 history but only weakly changes the late J2 pose. The complete 0.025 ms age table is emitted by the
analysis script.

Across 1,379 completed native clamp episodes (all five velocities), Pearson correlations with episode-mean J2
angle are:

| paired observable | r |
|---|---:|
| attachment lifetime | +0.169 |
| net episode impulse | +0.099 |
| peak tip–site displacement | −0.092 |
| relaxed tip–site displacement | −0.065 |
| mean F8 axial force | +0.086 |
| mean F8 transverse force | −0.164 |
| mean DIRSWING target error | −0.220 |

The logger also emits `J2AGE` rows at 0.025 ms resolution and per-episode `J2EP` rows containing lifetime,
positive/negative net impulse, peak/relaxed displacement, F8 axial/transverse force, and DIRSWING error. These raw
records, rather than state-averaged values, are used for the correlations.

## Native free gliding

Two paired CPU seeds at low, intermediate, and high nominal density used `dt=1e-5 s` for 0.12 s per trajectory.
The CPU census runs every timestep so that the sub-millisecond age and episode statistics are not aliased by the
GPU study's 1 ms host-sampling interval. The unmodified canonical results are:

| density (µm⁻²) | population | samples | mean ± SD | P05 / P25 / median / P75 / P95 |
|---:|---|---:|---:|---:|
| 50 | ADP·Pi | 596 | 102.51° ± 34.01° | 45.15° / 81.85° / 106.55° / 125.05° / 154.20° |
| 50 | ADP plateau | 593 | 118.07° ± 24.77° | 78.65° / 100.40° / 122.25° / 132.70° / 163.70° |
| 500 | ADP·Pi | 5,444 | 100.79° ± 37.68° | 35.05° / 73.95° / 101.90° / 129.80° / 158.70° |
| 500 | ADP plateau | 6,182 | 124.74° ± 25.56° | 81.65° / 106.65° / 126.05° / 143.60° / 164.30° |
| 2,000 | ADP·Pi | 23,261 | 104.10° ± 36.22° | 38.40° / 79.15° / 107.10° / 131.25° / 158.90° |
| 2,000 | ADP plateau | 25,114 | 128.97° ± 25.44° | 83.10° / 112.00° / 131.20° / 148.55° / 166.15° |

The late pose shifts modestly with density, but never becomes narrow: the plateau centre rises from 118.1° at
density 50 to 129.0° at density 2,000 while its SD remains about 25°. The first 0.025 ms age bin is
99.83°/102.44°/106.05° at densities 50/500/2,000; the 0.50–0.525 ms bin is
120.69°/124.75°/129.63°. This repeats the clamp finding that attachment age is the dominant progression, with a
smaller load/density accommodation.

Episode-mean angle correlations at densities 50/500/2,000, respectively, are:

| observable | density 50 | density 500 | density 2,000 |
|---|---:|---:|---:|
| lifetime | +0.057 | +0.182 | +0.128 |
| net impulse | +0.080 | +0.120 | +0.065 |
| peak tip–site displacement | −0.177 | −0.000 | −0.045 |
| relaxed tip–site displacement | −0.094 | −0.021 | −0.003 |
| mean F8 axial force | +0.207 | +0.050 | +0.046 |
| mean F8 transverse force | +0.007 | −0.157 | −0.119 |
| mean DIRSWING error | −0.082 | −0.249 | −0.232 |

The low-density column contains only 55 completed episodes and is correspondingly noisy; the intermediate and
high columns contain 627 and 2,547. Neither gliding nor clamp shows a strong monotonic link between J2 angle and
episode impulse, lifetime, peak displacement, or relaxed displacement.

## Interpretation

J2 is a broadly compliant accommodation mode with a weak emergent ADP pose, not a rigid coordinate with one narrow
native angle. The ADP plateau has a reproducible centre near 124° and an approximately 25° SD, while the pre-stroke
population is broader and its rotation axis is poorly coherent. Load and velocity modulate J2 only weakly. This is
consistent with the proposed mechanical escape route, but it also means that any nonzero conformation term can
select a pose in an otherwise nearly neutral direction; a large response to a tiny spring is not by itself evidence
for a biologically large stiffness.

The GPU density trajectories separately report their sampled J2 distribution, torque, energy, F8 split, and
anchor extension in `J2_DENSITY_SATURATION.md`; they are not substituted for the age-resolved CPU census above.
