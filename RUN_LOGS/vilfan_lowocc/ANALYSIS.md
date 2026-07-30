# Vilfan low-occupancy mechanism pilot -- reduction

## 0. OCC-1 occupancy accounting gate

Event-clock gap time divided by the committed zero-occupancy residence. Residence is
committed only when an interval CLOSES at a chemical event, so the two clocks are the same
decomposition summed in a different order: the gate's tolerance is therefore floating-point
summation error over N intervals, preregistered as 1e-12 relative. It is not vacuous -- it
compares the gap-detection path (inGap edge logic) against the occupancy histogram path,
and in this exact form it caught orphaned residence at the motor-field edge (ratio 0.912).

| arm | intervals | ratio | |1-ratio| | bad intervals | excess (s) | verdict |
|---|---|---|---|---|---|---|
| gate_mp1off_H | 0 | n/a | n/a | 0 | 0 | VACUOUS (no gaps) |
| gate_mp1off_K | 13 | 1 | 0 | 0 | 0 | PASS |
| gate_mp1off_S1 | 0 | n/a | n/a | 0 | 0 | VACUOUS (no gaps) |
| gate_mp1on_H | 0 | n/a | n/a | 0 | 0 | VACUOUS (no gaps) |
| gate_mp1on_K | 13 | 1 | 0 | 0 | 0 | PASS |
| gate_mp1on_S1 | 0 | n/a | n/a | 0 | 0 | VACUOUS (no gaps) |
| gate_pw_mir_H | 0 | n/a | n/a | 0 | 0 | VACUOUS (no gaps) |
| gate_pw_mir_K | 13 | 1 | 0 | 0 | 0 | PASS |
| gate_pw_mir_S1 | 0 | n/a | n/a | 0 | 0 | VACUOUS (no gaps) |
| gate_pw_nat_H | 0 | n/a | n/a | 0 | 0 | VACUOUS (no gaps) |
| gate_pw_nat_K | 13 | 1 | 0 | 0 | 0 | PASS |
| gate_pw_nat_S1 | 0 | n/a | n/a | 0 | 0 | VACUOUS (no gaps) |

**OCC-1: PASS**

## 1. MP-1 matched-path integrity and PW pathwise-mirror gates

MP-1: enabling the shadow read-out must not perturb the trajectory (it consumes no random
number, removes no motor and applies no force), so every arm quantity must be BIT-identical.

| regime | velUmPerS | omegaRadPerS | meanXaNm | occMean | nEventsAnalysed | windingRad | verdict |
|---|---|---|---|---|---|---|---|
| H | identical | identical | identical | identical | identical | identical | PASS |
| S1 | identical | identical | identical | identical | identical | identical | PASS |
| K | identical | identical | identical | identical | identical | identical | PASS |

**MP-1: PASS**

PW: pathwise mirror at the SAME seed -- lattice mirrored, roll noise sign flipped
(mirror-ODD), axial noise untouched (mirror-EVEN). Roll must reverse; the even part
(half-sum) must sit far below the odd part (half-difference).

| regime | Omega native | Omega mirror | Omega_odd | Omega_even | |even/odd| | verdict |
|---|---|---|---|---|---|---|
| H | -0.48008 | +0.48008 | -0.48008 | +0 | 0 | PASS |
| S1 | +0.44112 | -0.44112 | +0.44112 | +0 | 0 | PASS |
| K | -0.033844 | +0.033844 | -0.033844 | +0 | 0 | PASS |

**PW: PASS**

## 0. OCC-1 occupancy accounting gate

Event-clock gap time divided by the committed zero-occupancy residence. Residence is
committed only when an interval CLOSES at a chemical event, so the two clocks are the same
decomposition summed in a different order: the gate's tolerance is therefore floating-point
summation error over N intervals, preregistered as 1e-12 relative. It is not vacuous -- it
compares the gap-detection path (inGap edge logic) against the occupancy histogram path,
and in this exact form it caught orphaned residence at the motor-field edge (ratio 0.912).

| arm | intervals | ratio | |1-ratio| | bad intervals | excess (s) | verdict |
|---|---|---|---|---|---|---|
| pil_H_s101_mirror | 0 | n/a | n/a | 0 | 0 | VACUOUS (no gaps) |
| pil_H_s101_native | 0 | n/a | n/a | 0 | 0 | VACUOUS (no gaps) |
| pil_H_s102_mirror | 0 | n/a | n/a | 0 | 0 | VACUOUS (no gaps) |
| pil_H_s102_native | 0 | n/a | n/a | 0 | 0 | VACUOUS (no gaps) |
| pil_K_s101_mirror | 210 | 1 | 0 | 0 | 0 | PASS |
| pil_K_s101_native | 221 | 1.001301953 | 0.0013 | 0 | 0 | FAIL |
| pil_K_s102_mirror | 196 | 1 | 0 | 0 | 0 | PASS |
| pil_K_s102_native | 216 | 1 | 0 | 0 | 0 | PASS |
| pil_S1_s101_mirror | 5 | 1 | 0 | 0 | 0 | PASS |
| pil_S1_s101_native | 6 | 1 | 0 | 0 | 0 | PASS |
| pil_S1_s102_mirror | 6 | 1 | 0 | 0 | 0 | PASS |
| pil_S1_s102_native | 9 | 1 | 0 | 0 | 0 | PASS |
| s1x_s101_mirror | 676 | 1 | 0 | 0 | 0 | PASS |
| s1x_s101_native | 635 | 1 | 0 | 0 | 0 | PASS |
| s1x_s102_mirror | 686 | 1 | 0 | 0 | 0 | PASS |
| s1x_s102_native | 608 | 1 | 0 | 0 | 0 | PASS |
| s1x_s103_mirror | 1127 | 1 | 0 | 0 | 0 | PASS |
| s1x_s103_native | 898 | 1 | 0 | 0 | 0 | PASS |
| s1x_s104_mirror | 370 | 1 | 0 | 0 | 0 | PASS |
| s1x_s104_native | 429 | 1 | 0 | 0 | 0 | PASS |
| s1x_s105_mirror | 1344 | 1 | 0 | 0 | 0 | PASS |
| s1x_s105_native | 1497 | 1 | 0 | 0 | 0 | PASS |
| s1x_s106_mirror | 777 | 1 | 0 | 0 | 0 | PASS |
| s1x_s106_native | 820 | 1 | 0 | 0 | 0 | PASS |
| s1x_s107_mirror | 940 | 1 | 0 | 0 | 0 | PASS |
| s1x_s107_native | 978 | 1 | 0 | 0 | 0 | PASS |
| s1x_s108_mirror | 771 | 1 | 0 | 0 | 0 | PASS |
| s1x_s108_native | 667 | 1 | 0 | 0 | 0 | PASS |

**OCC-1: FAIL**

## 2. Regime summary (native arms, both seeds)

| regime | seed | N_b | median | P(0) | gaps | mean gap (ms) | C_Theta | C_X | v (um/s) | Omega (rad/s) | <x_A> nm | before % |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| H | 101 | 74.165 | 6 | 0.0000 | 0 | 0.000 |    n/a |    n/a | 0.04162 | -0.4966 | 1.5254 | 58.53 |
| H | 102 | 82.628 | 6 | 0.0000 | 0 | 0.000 |    n/a |    n/a | 0.04196 | -0.5453 | 1.6742 | 58.97 |
| S1 | 101 | 3.744 | 4 | 0.0009 | 6 | 18.994 |  0.055 | -0.260 | 0.03274 | +1.5784 | -3.2217 | 29.16 |
| S1 | 102 | 3.573 | 4 | 0.0009 | 9 | 12.084 | -0.008 | -0.310 | 0.04086 | -0.0497 | 0.7616 | 53.46 |
| K | 101 | 1.257 | 1 | 0.2965 | 222 | 160.593 |  0.081 |  0.037 | 0.05062 | +0.5884 | 0.1657 | 50.99 |
| K | 102 | 1.379 | 1 | 0.2481 | 216 | 137.846 |  0.083 | -0.045 | 0.03009 | +2.3715 | 0.1203 | 51.30 |

## 3. Conditional attachment analysis by occupancy category

Delta_depletion = <x_A>_real - <x_A>_shadow, both measured on the SAME realised trajectory,
the shadow restricted to the same occupancy/gap label. A NEGATIVE value means realised
attachments land further before the zone centre than blind availability predicts, which is
Vilfan target-zone depletion. Sign convention is fixed by the parent studies.


### Regime H

| seed | category | attachments | <x_A> real nm | <x_A> shadow nm | Delta nm | SEM nm | sigma | before % |
|---|---|---|---|---|---|---|---|---|
| 101 | first-post-gap | 0 | n/a | n/a | n/a | n/a | n/a | n/a |
| 101 | early-post-gap | 0 | n/a | n/a | n/a | n/a | n/a | n/a |
| 101 | tethered-sparse | 0 | n/a | n/a | n/a | n/a | n/a | n/a |
| 101 | tethered-multihead | 44059 | 1.5254 | -0.1028 | +1.6282 |  0.0401 |   40.6 | 58.53 |
| 102 | first-post-gap | 0 | n/a | n/a | n/a | n/a | n/a | n/a |
| 102 | early-post-gap | 0 | n/a | n/a | n/a | n/a | n/a | n/a |
| 102 | tethered-sparse | 0 | n/a | n/a | n/a | n/a | n/a | n/a |
| 102 | tethered-multihead | 49392 | 1.6742 | -0.0443 | +1.7185 |  0.0379 |   45.3 | 58.97 |

### Regime S1

| seed | category | attachments | <x_A> real nm | <x_A> shadow nm | Delta nm | SEM nm | sigma | before % |
|---|---|---|---|---|---|---|---|---|
| 101 | first-post-gap | 6 | 1.9090 |  1.2161 | +0.6929 |  3.0295 |    0.2 | 83.33 |
| 101 | early-post-gap | 31 | -1.7221 | -2.5298 | +0.8077 |  1.1237 |    0.7 | 45.16 |
| 101 | tethered-sparse | 576 | -3.4414 | -2.8268 | -0.6146 |  0.2857 |    2.2 | 23.96 |
| 101 | tethered-multihead | 1640 | -3.1982 | -3.2193 | +0.0210 |  0.1845 |    0.1 | 30.49 |
| 102 | first-post-gap | 9 | -0.5396 | -1.2939 | +0.7543 |  2.1368 |    0.4 | 66.67 |
| 102 | early-post-gap | 43 | 0.3523 | -1.6450 | +1.9973 |  1.1456 |    1.7 | 58.14 |
| 102 | tethered-sparse | 685 | 0.3371 | -1.0208 | +1.3579 |  0.2780 |    4.9 | 51.39 |
| 102 | tethered-multihead | 1444 | 0.9843 | -1.0551 | +2.0394 |  0.2045 |   10.0 | 54.22 |

### Regime K

| seed | category | attachments | <x_A> real nm | <x_A> shadow nm | Delta nm | SEM nm | sigma | before % |
|---|---|---|---|---|---|---|---|---|
| 101 | first-post-gap | 221 | 0.1905 | -0.0441 | +0.2346 |  0.4501 |    0.5 | 49.77 |
| 101 | early-post-gap | 183 | -0.2806 | -0.0841 | -0.1965 |  0.4514 |    0.4 | 47.54 |
| 101 | tethered-sparse | 256 | 0.1266 | -0.1313 | +0.2580 |  0.4165 |    0.6 | 50.78 |
| 101 | tethered-multihead | 101 | 1.1201 |  0.1038 | +1.0163 |  0.5907 |    1.7 | 60.40 |
| 102 | first-post-gap | 216 | 0.5889 |  0.0345 | +0.5544 |  0.4116 |    1.3 | 52.78 |
| 102 | early-post-gap | 214 | 0.1943 | -0.0573 | +0.2516 |  0.4224 |    0.6 | 52.34 |
| 102 | tethered-sparse | 306 | -0.2279 |  0.0092 | -0.2371 |  0.3688 |    0.6 | 49.02 |
| 102 | tethered-multihead | 112 | 0.0266 | -0.0301 | +0.0568 |  0.5395 |    0.1 | 52.68 |

## 4. First-post-gap attachments binned by gap duration (bins preregistered)

Bins <0.5 / 0.5-2 / 2-10 / >10 ms are fixed from the free-diffusion memory scales
(axial 1/e = 0.424 ms, angular 1/e = 2.045 ms) and were NOT redefined after seeing results.


### Regime H

| seed | bin | n | mean gap (ms) | <x_A> nm | before % | expected C_Theta | expected C_X |
|---|---|---|---|---|---|---|---|
| 101 | <0.5 ms | 0 | n/a | n/a | n/a | | |
| 101 | 0.5-2 ms | 0 | n/a | n/a | n/a | | |
| 101 | 2-10 ms | 0 | n/a | n/a | n/a | | |
| 101 | >10 ms | 0 | n/a | n/a | n/a | | |
| 102 | <0.5 ms | 0 | n/a | n/a | n/a | | |
| 102 | 0.5-2 ms | 0 | n/a | n/a | n/a | | |
| 102 | 2-10 ms | 0 | n/a | n/a | n/a | | |
| 102 | >10 ms | 0 | n/a | n/a | n/a | | |

### Regime S1

| seed | bin | n | mean gap (ms) | <x_A> nm | before % | expected C_Theta | expected C_X |
|---|---|---|---|---|---|---|---|
| 101 | <0.5 ms | 0 | n/a | n/a | n/a | | |
| 101 | 0.5-2 ms | 1 | 1.134 | -10.9369 | 0.00 | 0.5744 | 0.0689 |
| 101 | 2-10 ms | 3 | 6.523 | 3.7172 | 100.00 | 0.0412 | 0.0000 |
| 101 | >10 ms | 2 | 46.629 | 5.6195 | 100.00 | 0.0000 | 0.0000 |
| 102 | <0.5 ms | 1 | 0.353 | 2.1344 | 100.00 | 0.8415 | 0.4348 |
| 102 | 0.5-2 ms | 1 | 1.419 | 0.4825 | 100.00 | 0.4996 | 0.0351 |
| 102 | 2-10 ms | 4 | 7.105 | 1.4064 | 75.00 | 0.0310 | 0.0000 |
| 102 | >10 ms | 3 | 26.186 | -4.3663 | 33.33 | 0.0000 | 0.0000 |

### Regime K

| seed | bin | n | mean gap (ms) | <x_A> nm | before % | expected C_Theta | expected C_X |
|---|---|---|---|---|---|---|---|
| 101 | <0.5 ms | 0 | n/a | n/a | n/a | | |
| 101 | 0.5-2 ms | 0 | n/a | n/a | n/a | | |
| 101 | 2-10 ms | 9 | 5.923 | 2.8564 | 66.67 | 0.0552 | 0.0000 |
| 101 | >10 ms | 212 | 167.698 | 0.0773 | 49.06 | 0.0000 | 0.0000 |
| 102 | <0.5 ms | 1 | 0.380 | 0.3494 | 100.00 | 0.8306 | 0.4083 |
| 102 | 0.5-2 ms | 1 | 1.828 | -2.7967 | 0.00 | 0.4091 | 0.0134 |
| 102 | 2-10 ms | 10 | 5.662 | -0.1355 | 50.00 | 0.0627 | 0.0000 |
| 102 | >10 ms | 204 | 145.666 | 0.6422 | 52.94 | 0.0000 | 0.0000 |

## 5. LOAD-BEARING TEST -- Regime S1: long-gap first-post-gap vs continuously tethered

If target-zone depletion is carried by PHASE MEMORY across the gap, an attachment that
follows a gap longer than every memory time (>10 ms: C_Theta < 7e-3, C_X < 1e-9) must show
a MARKEDLY WEAKER depletion bias than one taken while the filament stayed tethered. If the
two are equal, phase memory is NOT the carrier and the hypothesis is disproved.

| seed | quantity | first-post-gap (>10 ms) | continuously tethered | difference |
|---|---|---|---|---|
| 101 | n | 2 | 2216 | |
| 101 | <x_A> nm | +5.6195 | -3.2614 | +8.8809 |
| 101 | before-centre % | 100.00 | 28.79 | +71.21 |
| 102 | n | 3 | 2129 | |
| 102 | <x_A> nm | -4.3663 | +0.7760 | -5.1423 |
| 102 | before-centre % | 33.33 | 53.31 | -19.98 |

**Pooled (both seeds):** n = 5 long-gap vs 4345 tethered; <x_A> -0.3720 vs -1.2831 nm (difference +0.9111); before-centre 60.00 % vs 40.81 % (difference +19.19 pp, +0.88 sigma on the binomial proportions).

## 6. Steady versus intermittent roll

Roll is attributed per EPISODE (a contiguous stretch with N_b >= 1); roll during a
zero-bound gap is free rotational diffusion and carries no mechanism. Torque impulse per
episode is exact in the overdamped model: integral(M dt) = gamma_Theta * dTheta, with
gamma_Theta = kBT / D_Theta taken from the arm's own recorded D_Theta.

| regime | seed | episodes | mean dur (ms) | sum dTheta | mean impulse (pN nm s) | top 1% | top 5% | top 10% | sign + % |
|---|---|---|---|---|---|---|---|---|---|
| H | 101 | 0 (single uninterrupted episode) | | | | | | | |
| H | 102 | 0 (single uninterrupted episode) | | | | | | | |
| S1 | 101 | 6 | 22269.347 | +202.6 | +0.286 | +0.360 | +0.360 | +0.360 | 83.3 |
| S1 | 102 | 9 | 12765.515 | -11.15 | -0.01049 | -1.221 | -1.221 | -1.221 | 33.3 |
| K | 101 | 221 | 376.837 | -14.67 | -0.0005619 | +0.192 | +0.610 | +1.120 | 44.8 |
| K | 102 | 216 | 417.636 | -2.964 | -0.0001162 | -0.956 | -2.147 | -2.720 | 49.1 |

### Blockwise rotation, drift quality and motion class

| regime | seed | Omega full | 1st half | 2nd half | blocks | Omega block mean +- sd | sign stability % | roll R^2 | axial R^2 | class |
|---|---|---|---|---|---|---|---|---|---|---|
| H | 101 | -0.4966 | -0.4945 | -0.4996 | 20 | -0.4883 +- 0.1018 | 100 | 0.9990 | 1.0000 | steady drift |
| H | 102 | -0.5453 | -0.5455 | -0.5448 | 20 | -0.5442 +- 0.1134 | 100 | 0.9990 | 1.0000 | steady drift |
| S1 | 101 | +1.5784 | +1.6035 | +1.5635 | 20 | +1.5208 +- 1.2277 | 90 | 0.9767 | 0.9970 | steady drift |
| S1 | 102 | -0.0497 | +0.2538 | -0.3564 | 20 | -0.1634 +- 0.8661 | 60 | 0.6701 | 0.9974 | symmetric diffusion |
| K | 101 | +0.5884 | -1.3394 | +2.5532 | 20 | +1.6677 +- 7.0745 | 70 | 0.1504 | 0.9529 | intermittent signed bursts |
| K | 102 | +2.3715 | +3.9116 | +0.8323 | 20 | +2.0880 +- 3.2788 | 70 | 0.6694 | 0.2354 | intermittent signed bursts |

### Angular displacement by instantaneous occupancy, and motor-free time

| regime | seed | dTheta at N_b=0 | 1 | 2 | >=3 | time at 0 (s) | 1 | 2 | >=3 | motor-free substeps | motor-free % |
|---|---|---|---|---|---|---|---|---|---|---|
| H | 101 | +0 | +0 | +0 | -59.64 |  0.000 |  0.000 |  0.000 | 119.997 | 0 | 0.00 |
| H | 102 | +0 | +0 | +0 | -65.4 |  0.000 |  0.000 |  0.000 | 120.000 | 0 | 0.00 |
| S1 | 101 | +10.67 | +13.62 | +60.36 | +105.3 |  0.114 |  1.082 |  9.689 | 109.227 | 0 | 0.00 |
| S1 | 102 | +3.888 | -6.219 | +17.07 | -20.89 |  0.109 |  2.469 | 13.535 | 103.886 | 0 | 0.00 |
| K | 101 | +88.64 | -22.02 | +12.26 | -6.832 | 35.605 | 42.059 | 26.042 | 16.397 | 0 | 0.00 |
| K | 102 | +291 | -10.82 | +1.307 | +2.898 | 29.775 | 42.484 | 28.204 | 19.539 | 0 | 0.00 |

### Waiting time between signed roll bursts

A BURST is an episode whose |dTheta| exceeds the median episode |dTheta|; the waiting time
is the interval between successive burst onsets. An exponential-like distribution
(sd ~ mean) indicates Poisson bursts; sd << mean indicates near-periodic engagement.

| regime | seed | bursts | mean wait (ms) | sd (ms) | sd/mean | median wait (ms) |
|---|---|---|---|---|---|---|
| H | 101 | 0 | n/a | n/a | n/a | n/a |
| H | 102 | 0 | n/a | n/a | n/a | n/a |
| S1 | 101 | 2 | n/a | n/a | n/a | n/a |
| S1 | 102 | 4 | 33082.136 | 38421.059 | 1.161 | 10980.850 |
| K | 101 | 110 | 1073.797 | 883.613 | 0.823 | 757.937 |
| K | 102 | 107 | 1126.363 | 1047.990 | 0.930 | 892.847 |

## 6b. Inferential mirror pairs (independent noise)

Requirements: Omega_even consistent with zero; depletion bias mirror-EVEN; angular bias and
torque mirror-ODD. With independent noise these hold in expectation, not pathwise, so the
comparison is against the seed-to-seed spread.

| regime | seed | Omega nat | Omega mir | Omega_odd | Omega_even | <x_A> nat | <x_A> mir | <th_A> nat | <th_A> mir | torque nat | torque mir |
|---|---|---|---|---|---|---|---|---|---|---|---|
| H | 101 | -0.4966 | +0.5142 | -0.5054 | +0.0088 | +1.525 | +1.522 | +0.0981 | -0.0977 | -1.625 | +1.618 |
| H | 102 | -0.5453 | +0.5177 | -0.5315 | -0.0138 | +1.674 | +1.573 | +0.1085 | -0.0999 | -1.796 | +1.655 |
| S1 | 101 | +1.5784 | -1.1895 | +1.3840 | +0.1944 | -3.222 | -2.722 | -0.2181 | +0.1862 | +3.611 | -3.083 |
| S1 | 102 | -0.0497 | +0.3535 | -0.2016 | +0.1519 | +0.762 | +0.972 | +0.0427 | -0.0709 | -0.7071 | +1.175 |
| K | 101 | +0.5884 | -1.8102 | +1.1993 | -0.6109 | +0.166 | +0.169 | +0.0136 | -0.0112 | -0.2255 | +0.1856 |
| K | 102 | +2.3715 | +0.4505 | +0.9605 | +1.4110 | +0.120 | -0.040 | +0.0027 | -0.0007 | -0.04524 | +0.01092 |

## 8b. WITHIN-ARM paired contrast (the primary estimator)

This, not the pooled contrast, is the correct estimator. In the sparse regime the ABSOLUTE
before-centre fraction of tethered attachments swings from 19 % to 69 % between motor-field
realisations, while the long-gap fraction sits at ~50 % in every one. Pooling attachments across
arms therefore compares a stable ~49 % against a count-weighted mixture of a swinging quantity and
manufactures a contrast that is absent within any arm -- a textbook Simpson reversal. Pooling gave
-5.8 sigma; the paired estimator over the same 16 arms gives -0.54 sigma.


### Regime H: no arm has >= 30 attachments in BOTH categories


### Regime S1

| arm | n tethered | n long-gap | tethered before % | long-gap before % | difference pp |
|---|---|---|---|---|---|
| s1x_s101_mirror | 4089 | 479 | 48.42 | 50.94 | +2.52 |
| s1x_s101_native | 4726 | 440 | 45.30 | 51.14 | +5.83 |
| s1x_s102_mirror | 3907 | 449 | 47.45 | 50.33 | +2.88 |
| s1x_s102_native | 4007 | 390 | 48.42 | 47.95 | -0.47 |
| s1x_s103_mirror | 459 | 869 | 18.95 | 47.41 | +28.46 |
| s1x_s103_native | 652 | 640 | 22.39 | 51.56 | +29.17 |
| s1x_s104_mirror | 4216 | 194 | 51.92 | 48.97 | -2.95 |
| s1x_s104_native | 4157 | 232 | 53.28 | 52.16 | -1.13 |
| s1x_s105_mirror | 58 | 1000 | 62.07 | 48.90 | -13.17 |
| s1x_s105_native | 63 | 1127 | 60.32 | 48.98 | -11.34 |
| s1x_s106_mirror | 2505 | 527 | 68.94 | 50.85 | -18.09 |
| s1x_s106_native | 2318 | 589 | 64.11 | 46.69 | -17.42 |
| s1x_s107_mirror | 1366 | 668 | 50.37 | 47.01 | -3.36 |
| s1x_s107_native | 802 | 724 | 53.12 | 46.82 | -6.29 |
| s1x_s108_mirror | 2652 | 518 | 60.14 | 50.97 | -9.18 |
| s1x_s108_native | 2546 | 445 | 64.49 | 48.54 | -15.95 |

- paired before-centre difference: **-1.91 +- 3.53 pp = -0.54 sigma** over 16 arms
- paired <x_A> difference: **-0.2151 +- 0.4718 nm = -0.46 sigma**
- tethered before-centre across arms: 51.23 +- 3.47 % (range 18.95-68.94)
- long-gap before-centre across arms: 49.33 +- 0.46 % (range 46.69-52.16)

### Regime K

| arm | n tethered | n long-gap | tethered before % | long-gap before % | difference pp |
|---|---|---|---|---|---|
| pil_K_s101_mirror | 394 | 194 | 51.78 | 44.85 | -6.93 |
| pil_K_s101_native | 357 | 212 | 53.50 | 49.06 | -4.44 |
| pil_K_s102_mirror | 445 | 179 | 49.66 | 48.04 | -1.62 |
| pil_K_s102_native | 418 | 204 | 50.00 | 52.94 | +2.94 |

- paired before-centre difference: **-2.51 +- 2.12 pp = -1.19 sigma** over 4 arms
- paired <x_A> difference: **-0.0026 +- 0.3033 nm = -0.01 sigma**
- tethered before-centre across arms: 51.24 +- 0.89 % (range 49.66-53.50)
- long-gap before-centre across arms: 48.72 +- 1.67 % (range 44.85-52.94)

## 8. Pooled conditional contrast and pilot outcome

| regime | set | n | <x_A> nm | SEM | Delta vs shadow nm | sigma |
|---|---|---|---|---|---|---|
| H | first-post-gap | 0 |      n/a |     n/a |      n/a |    n/a |
| H | early-post-gap | 0 |      n/a |     n/a |      n/a |    n/a |
| H | tethered (sparse+multi) | 93451 |   1.6041 |  0.0276 |   1.6760 |   60.8 |
| S1 | first-post-gap | 6547 |  -0.0679 |  0.0782 |   0.4675 |    6.0 |
| S1 | early-post-gap | 3323 |   0.8571 |  0.1209 |   3.6148 |   29.9 |
| S1 | tethered (sparse+multi) | 23616 |   0.0052 |  0.0492 |   1.6038 |   32.6 |
| K | first-post-gap | 437 |   0.3874 |  0.3051 |   0.3939 |    1.3 |
| K | early-post-gap | 397 |  -0.0246 |  0.3083 |   0.0452 |    0.1 |
| K | tethered (sparse+multi) | 775 |   0.1017 |  0.2284 |   0.1322 |    0.6 |

| H | first-post-gap <0.5 ms | 0 |      n/a |     n/a | | |
| H | first-post-gap 0.5-2 ms | 0 |      n/a |     n/a | | |
| H | first-post-gap 2-10 ms | 0 |      n/a |     n/a | | |
| H | first-post-gap >10 ms | 0 |      n/a |     n/a | | |
| S1 | first-post-gap <0.5 ms | 128 |  -1.2974 |  0.5519 | | |
| S1 | first-post-gap 0.5-2 ms | 320 |  -0.0398 |  0.3525 | | |
| S1 | first-post-gap 2-10 ms | 1507 |  -0.2052 |  0.1621 | | |
| S1 | first-post-gap >10 ms | 4592 |   0.0094 |  0.0936 | | |
| K | first-post-gap <0.5 ms | 1 |      n/a |     n/a | | |
| K | first-post-gap 0.5-2 ms | 1 |      n/a |     n/a | | |
| K | first-post-gap 2-10 ms | 19 |   1.2817 |  1.4324 | | |
| K | first-post-gap >10 ms | 416 |   0.3543 |  0.3139 | | |

### Outcome assignment

- S1 long-gap (>10 ms) <x_A> = +0.0094 +- 0.0936 nm (n = 4592); continuously tethered <x_A> = +0.0052 +- 0.0492 nm (n = 23616); difference +0.0042 +- 0.1057 nm = +0.04 sigma.
- before-centre fraction: long-gap 48.95 % (n = 4592) against tethered 50.00 % (n = 23616) = -1.29 sigma.
- The two are statistically INDISTINGUISHABLE -> the phase-memory hypothesis is NOT supported by the load-bearing test (P5 direction).

## 9. Sizing the reduced production campaign

Per-attachment spread sd is measured here; the seeds needed for a target resolution follow
from the observed attachment RATE per arm, so the design variable is simulated seconds.

| regime | category | sd (nm) | attachments per analysed second | seconds for SEM 0.10 nm | seconds for SEM 0.05 nm |
|---|---|---|---|---|---|
| H | first-post-gap |     n/a |    0.00 |       n/a |       n/a |
| H | tethered (sparse+multi) |   8.425 |  389.38 |      18.2 |      72.9 |
| S1 | first-post-gap |   6.328 |   27.27 |     146.8 |     587.4 |
| S1 | tethered (sparse+multi) |   7.561 |   98.35 |      58.1 |     232.5 |
| K | first-post-gap |   6.378 |    1.82 |    2234.9 |    8939.7 |
| K | tethered (sparse+multi) |   6.359 |    3.23 |    1252.8 |    5011.0 |

## 9b. Sizing from the PAIRED per-arm variance (the design that matters)

The design variable is the number of motor-field REALISATIONS, not the run length: the
per-arm scatter is set by which motors the filament sits on. Arms are 480 s (S1) / 120 s (K).

| regime | arms so far | per-arm sd, before-centre (pp) | arms for SEM 2 pp | SEM 1 pp | per-arm sd, <x_A> (nm) | arms for SEM 0.3 nm |
|---|---|---|---|---|---|---|
| S1 | 16 | 14.10 | 50 | 199 | 1.887 | 40 |
| K | 4 | 4.23 | 4 | 18 | 0.607 | 4 |

To ask whether the sparse-regime TETHERED bias differs from H's 58.7 %, the relevant
spread is the per-arm tethered before-centre sd:

| regime | per-arm sd (pp) | arms for SEM 2 pp | current mean +- SEM | separation from H |
|---|---|---|---|---|
| S1 | 13.86 | 48 | 51.23 +- 3.47 % | 2.16 sigma |
| K | 1.77 | 1 | 51.24 +- 0.89 % | 8.42 sigma |

## 7. Pilot sufficiency gates

| gate | observed | required | verdict |
|---|---|---|---|
| S1 zero-bound gaps >= 100 | 6547 | 100 | PASS |
| S1 first-post-gap attachments >= 100 | 6547 | 100 | PASS |
| S1 long-gap (>10 ms) first-post-gap >= 30 | 4592 | 30 | PASS |
| S1 tethered-sparse attachments >= 1000 | 15246 | 1000 | PASS |
| H attachments >= 10000 | 93451 | 10000 | PASS |

Arms carrying a note (halt / cap): none
Arms with roll-cap failures: none (must be none)

**Sufficiency: PASS**


# S1 sufficiency extension (8 field realisations x 480 s)


## 0. OCC-1 occupancy accounting gate

Event-clock gap time divided by the committed zero-occupancy residence. Residence is
committed only when an interval CLOSES at a chemical event, so the two clocks are the same
decomposition summed in a different order: the gate's tolerance is therefore floating-point
summation error over N intervals, preregistered as 1e-12 relative. It is not vacuous -- it
compares the gap-detection path (inGap edge logic) against the occupancy histogram path,
and in this exact form it caught orphaned residence at the motor-field edge (ratio 0.912).

| arm | intervals | ratio | |1-ratio| | bad intervals | excess (s) | verdict |
|---|---|---|---|---|---|---|
| s1x_s101_mirror | 676 | 1 | 0 | 0 | 0 | PASS |
| s1x_s101_native | 635 | 1 | 0 | 0 | 0 | PASS |
| s1x_s102_mirror | 686 | 1 | 0 | 0 | 0 | PASS |
| s1x_s102_native | 608 | 1 | 0 | 0 | 0 | PASS |
| s1x_s103_mirror | 1127 | 1 | 0 | 0 | 0 | PASS |
| s1x_s103_native | 898 | 1 | 0 | 0 | 0 | PASS |
| s1x_s104_mirror | 370 | 1 | 0 | 0 | 0 | PASS |
| s1x_s104_native | 429 | 1 | 0 | 0 | 0 | PASS |
| s1x_s105_mirror | 1344 | 1 | 0 | 0 | 0 | PASS |
| s1x_s105_native | 1497 | 1 | 0 | 0 | 0 | PASS |
| s1x_s106_mirror | 777 | 1 | 0 | 0 | 0 | PASS |
| s1x_s106_native | 820 | 1 | 0 | 0 | 0 | PASS |
| s1x_s107_mirror | 940 | 1 | 0 | 0 | 0 | PASS |
| s1x_s107_native | 978 | 1 | 0 | 0 | 0 | PASS |
| s1x_s108_mirror | 771 | 1 | 0 | 0 | 0 | PASS |
| s1x_s108_native | 667 | 1 | 0 | 0 | 0 | PASS |

**OCC-1: PASS**

## E1. Per-realisation spread at rho = 0.35 /um

| field seed | arm | N_b | P(0) | gaps | mean gap (ms) | max gap (s) | motor-free % | v (um/s) | Omega |
|---|---|---|---|---|---|---|---|---|---|
| 101 | s1x_s101_mirror | 2.167 | 0.0841 | 676 | 59.74 | 11.839 | 4.0 | 0.04735 | -0.1953 |
| 101 | s1x_s101_native | 2.368 | 0.0542 | 635 | 40.98 | 2.757 | 1.2 | 0.03625 | -0.1372 |
| 102 | s1x_s102_mirror | 2.179 | 0.0405 | 686 | 28.34 | 0.305 | 0.0 | 0.03579 | -0.2753 |
| 102 | s1x_s102_native | 2.163 | 0.0339 | 608 | 26.74 | 0.210 | 0.0 | 0.03584 | +0.2463 |
| 103 | s1x_s103_mirror | 0.717 | 0.4150 | 1127 | 176.82 | 120.465 | 32.9 | 0.04171 | -0.4866 |
| 103 | s1x_s103_native | 0.744 | 0.4518 | 898 | 241.83 | 81.506 | 38.5 | 0.02433 | +2.3425 |
| 104 | s1x_s104_mirror | 2.222 | 0.0146 | 370 | 18.97 | 0.219 | 0.0 | 0.04367 | -0.1322 |
| 104 | s1x_s104_native | 2.213 | 0.0162 | 429 | 18.18 | 0.164 | 0.0 | 0.04273 | +0.0554 |
| 105 | s1x_s105_mirror | 0.573 | 0.4454 | 1344 | 162.42 | 121.332 | 34.6 | 0.02516 | +0.3481 |
| 105 | s1x_s105_native | 0.691 | 0.3341 | 1497 | 107.96 | 84.063 | 22.3 | 0.02520 | +0.8329 |
| 106 | s1x_s106_mirror | 1.630 | 0.1345 | 777 | 83.12 | 25.508 | 8.9 | 0.03569 | +1.0445 |
| 106 | s1x_s106_native | 1.482 | 0.2042 | 820 | 119.54 | 25.640 | 14.8 | 0.04016 | -0.1324 |
| 107 | s1x_s107_mirror | 0.825 | 0.4752 | 940 | 324.13 | 187.283 | 42.7 | 0.01816 | -1.1223 |
| 107 | s1x_s107_native | 0.808 | 0.4364 | 978 | 216.52 | 72.569 | 36.5 | 0.02361 | -0.3326 |
| 108 | s1x_s108_mirror | 1.663 | 0.1355 | 771 | 84.35 | 30.885 | 8.6 | 0.04778 | +1.5847 |
| 108 | s1x_s108_native | 1.542 | 0.2027 | 667 | 150.16 | 35.173 | 16.3 | 0.02161 | -1.6233 |

## E2. Pooled conditional contrast over all realisations

| set | n | <x_A> nm | SEM | Delta vs shadow nm | sigma | before % |
|---|---|---|---|---|---|---|
| first-post-gap | 13223 | -0.0472 | 0.0552 |  -0.5425 |    9.8 | 49.29 |
| early-post-gap | 6554 | +0.8849 | 0.0864 |   3.6499 |   42.3 | 56.27 |
| tethered-sparse | 28384 | +0.5408 | 0.0432 |   2.0472 |   47.4 | 53.69 |
| tethered-multihead | 10139 | -0.1421 | 0.0795 |   1.3086 |   16.5 | 48.82 |
| tethered (sparse+multi) | 38523 | +0.3611 | 0.0381 |   1.8376 |   48.2 | 52.41 |
| first-post-gap <0.5 ms | 254 | -1.0524 | 0.3891 |      n/a |    n/a | 41.34 |
| first-post-gap 0.5-2 ms | 676 | +0.0106 | 0.2392 |      n/a |    n/a | 50.59 |
| first-post-gap 2-10 ms | 3002 | -0.0170 | 0.1177 |      n/a |    n/a | 50.43 |
| first-post-gap >10 ms | 9291 | -0.0337 | 0.0657 |      n/a |    n/a | 49.05 |

### E3. LOAD-BEARING TEST, pooled over realisations

- long-gap (>10 ms) first-post-gap: n = 9291, <x_A> = -0.0337 +- 0.0657 nm, before-centre 49.05 %
- continuously tethered:            n = 38523, <x_A> = +0.3611 +- 0.0381 nm, before-centre 52.41 %
- difference in <x_A>: -0.3947 +- 0.0759 nm = **-5.20 sigma**
- difference in before-centre fraction: -3.36 pp = **-5.82 sigma**

## E4. S1 mirror pairs over 8 field realisations (independent noise, SHARED field)

| field | Omega nat | Omega mir | Omega_odd | Omega_even | <x_A> nat | <x_A> mir | <th_A> nat | <th_A> mir | torque nat | torque mir |
|---|---|---|---|---|---|---|---|---|---|---|
| 101 | -0.1372 | -0.1953 | +0.0290 | -0.1663 | -0.519 | -0.178 | -0.02914 | +0.01183 | +0.4825 | -0.1959 |
| 102 | +0.2463 | -0.2753 | +0.2608 | -0.0145 | -0.139 | -0.086 | -0.01365 | +0.00198 | +0.226 | -0.03273 |
| 103 | +2.3425 | -0.4866 | +1.4146 | +0.9280 | -1.915 | -1.716 | -0.12019 | +0.13079 | +1.99 | -2.166 |
| 104 | +0.0554 | -0.1322 | +0.0938 | -0.0384 | +0.387 | +0.114 | +0.02368 | -0.00987 | -0.3921 | +0.1635 |
| 105 | +0.8329 | +0.3481 | +0.2424 | +0.5905 | +0.058 | +0.218 | +0.00180 | -0.01416 | -0.02989 | +0.2346 |
| 106 | -0.1324 | +1.0445 | -0.5884 | +0.4561 | +1.520 | +2.375 | +0.10455 | -0.15630 | -1.731 | +2.588 |
| 107 | -0.3326 | -1.1223 | +0.3949 | -0.7275 | +0.190 | -0.047 | +0.01019 | +0.00235 | -0.1688 | -0.03894 |
| 108 | -1.6233 | +1.5847 | -1.6040 | -0.0193 | +1.913 | +1.372 | +0.13042 | -0.08702 | -2.16 | +1.441 |

- **Omega_odd** over 8 realisations: +0.0304 +- 0.3049 rad/s = 0.10 sigma; sign split 6/8 positive

- **Omega_even** over 8 realisations: +0.1261 +- 0.1817 rad/s = 0.69 sigma; sign split 3/8 positive

## E5. Steady versus intermittent roll, sparse regime

| field | arm | episodes | mean dur (ms) | sum dTheta | top 1% | top 5% | top 10% | sign + % | roll R^2 | blocks sign stab % | class |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 101 | s1x_s101_mirror | 677 | 651.14 | +124.6 | -0.393 | +0.160 | +0.427 | 59.8 | 0.2054 |    55 | symmetric diffusion |
| 101 | s1x_s101_native | 636 | 737.21 | +36.44 | +3.970 | +2.941 | +1.678 | 46.4 | 0.1771 |    55 | symmetric diffusion |
| 102 | s1x_s102_mirror | 686 | 671.71 | -72.89 | -0.126 | +0.150 | +0.365 | 45.8 | 0.4571 |    55 | symmetric diffusion |
| 102 | s1x_s102_native | 608 | 764.56 | +138.6 | +0.262 | +0.523 | +0.738 | 50.7 | 0.4868 |    50 | symmetric diffusion |
| 103 | s1x_s103_mirror | 1127 | 250.04 | -204.9 | +0.430 | +0.629 | +0.691 | 45.7 | 0.6498 |    60 | symmetric diffusion |
| 103 | s1x_s103_native | 899 | 299.05 | +221.4 | +0.366 | +0.771 | +0.888 | 54.7 | 0.9597 |    70 | intermittent signed bursts |
| 104 | s1x_s104_mirror | 370 | 1273.03 | +46.65 | +0.357 | -0.013 | +0.197 | 51.1 | 0.3400 |    50 | symmetric diffusion |
| 104 | s1x_s104_native | 429 | 1096.19 | -114.1 | -0.080 | -0.107 | +0.171 | 44.1 | 0.5780 |    55 | symmetric diffusion |
| 105 | s1x_s105_mirror | 1344 | 203.54 | +34.27 | +0.233 | +0.165 | +0.656 | 50.7 | 0.3863 |    55 | symmetric diffusion |
| 105 | s1x_s105_native | 1497 | 215.47 | +11.07 | -1.533 | -1.223 | -0.872 | 50.4 | 0.0046 |    55 | symmetric diffusion |
| 106 | s1x_s106_mirror | 778 | 536.87 | +456.2 | +0.196 | +0.489 | +0.721 | 60.0 | 0.9566 |    95 | intermittent signed bursts |
| 106 | s1x_s106_native | 820 | 458.18 | -271.4 | +0.347 | +0.792 | +0.940 | 46.1 | 0.0025 |    75 | intermittent signed bursts |
| 107 | s1x_s107_mirror | 940 | 358.57 | -56.52 | -0.289 | +0.698 | +0.832 | 48.3 | 0.3713 |    65 | intermittent signed bursts |
| 107 | s1x_s107_native | 978 | 279.97 | +20.74 | +0.906 | +0.984 | +0.423 | 51.8 | 0.6848 |    65 | noisy drift |
| 108 | s1x_s108_mirror | 771 | 540.10 | +265 | +0.128 | +0.504 | +0.783 | 56.7 | 0.8997 |    75 | intermittent signed bursts |
| 108 | s1x_s108_native | 667 | 592.24 | -402.3 | +0.145 | +0.461 | +0.652 | 39.0 | 0.9416 |    80 | intermittent signed bursts |
