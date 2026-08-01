# Rigid-Filament ±5° Skew Torque — High-Seed Measurement

**Question.** With many independent matched motor-field realizations, what is the ensemble-averaged
chirality-odd axial motor torque at ε = ±5° in the rigid, FDT-consistent filament model — and is it
resolved?

**Supersedes the seed statistics of** `RIGID_LOW_SKEW_TORQUE_QUICKLOOK.md`, which reached Q3 ("no
discernible torque") from **two** matched seeds. That report's own §11.1 identified the correct next step:
*more seeds, not longer arms.* This is that measurement.

**Gated on** `RIGID_15DEG_TORQUE_REVALIDATION.md` (**A15 PASS**): the identical pipeline resolves
τ_odd = −4.558e−22 ± 1.963e−22 N·m at ±15°, so the estimator demonstrably has power on a signal it is
known to contain.

**Answer: the chirality-odd torque IS resolved at 5°. Classification L5-PRESENT.**
τ_odd = **−1.037e−22 ± 3.503e−23 N·m** (|m|/SEM **2.96**, 10 of 12 seeds native, 95 % t interval
[−1.808e−22, −2.664e−23] **excluding zero**), Ω_drive_odd = **−32.00 ± 10.80 rad/s**. Every L5-PRESENT
criterion is met. 12 matched seeds, 24 arms, 0 invalid, 0 solver failures.

**This reverses the predecessor's Q3 verdict** — and it does so exactly as that report predicted it would if
anyone ever ran the seeds: *"more seeds, not longer arms."*

**One qualification travels with it, and must not be dropped: the signal is not stationary across the
measured window.** The odd torque is concentrated in the first quarter and decays to zero by the last
(Q1 −3.086e−22 → Q4 +4.357e−23; per-seed trend slope 2.04σ, paired Q1−Q4 2.18σ). At 15° there is **no** such
trend (0.01σ). So the resolved quantity is best described as a **decaying, not a steady-state, chiral
torque**, and this is the natural explanation of the 200 ms null. See §6.2.

---

## 1. Configuration

Identical to the 15° anchor in every respect except ε. Commit `4a38db7` (simulation code identical to
`30e8891`, the commit that produced the pre-existing 5° records). 10 µM ATP, 400 heads/µm², η = 0.01 Pa·s,
`filSegs=1`, FDT thermostat, rigor rupture OFF, `-rot-decomp`, GPU device-resident, monitored wrapper,
dt = 2.5e−7 s, 100 ms/arm, 25 % equilibration ⇒ **75 ms measured window**.

```
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -atp-map -gpu -eta 0.01 \
    -atp-points 10 -atp-eps-deg 5 -density 400 -seed <S> -seeds 2 \
    -atp-duration-ms 100 -filament-segments 1 -fil-thermostat fdt -rot-decomp
```

Run as five sequential 2-seed batches (103–104, 105–106, 107–108, 109–110, 111–112) so each batch closes two
complete matched pairs before the next begins, which is what the sequential stopping rule requires.

**Reuse of seeds 101 and 102.** Verified compatible before inclusion: production commit `30e8891` (identical
simulation code to the campaign commit), `filSegs=1`, `filModel=RIGID-1seg`, `thermostat=FDT`, decomposition
**transfer** active (τ_det, Ω_drive, Ω_Brown all populated and non-zero — not the §2.4 silent-zero mode),
`invalid=0`, `solverFail=0`. They are therefore included in the 100 ms ensemble rather than re-run.

**The 200 ms records are NOT mixed into this ensemble.** They are the *same trajectories* as the 100 ms arms
(counter-based, step-count-independent RNG), measured over a different, overlapping window — not independent
replicates. They are used only as a window diagnostic (§3).

---

## 2. Why the matched seed is the unit of independence — and blocks are not

The design specifies the matched-seed odd estimate as the independent sample and forbids pooling correlated
time blocks as though they were seeds. **This campaign measured that correlation directly**, and it matters,
because the predecessor report drew its central conclusion from a pooled-block SEM.

For each arm the harness records τ_odd over four **disjoint** quarter-blocks of the 75 ms measured window.
If those blocks were independent draws, an arm mean (the average of four) would have SD = σ_block / 2.
Comparing that prediction with the observed between-seed SD tests the assumption:

| skew | block SD | arm-mean SD *predicted if blocks independent* | **observed between-seed SD** | ratio |
|---|---|---|---|---|
| **±5° (n = 12 seeds, final)** | 3.527e−22 | 1.764e−22 | **1.213e−22** | **0.69** |
| ±5° (n = 4, interim — superseded) | 2.076e−22 | 1.038e−22 | 3.259e−23 | 0.31 |
| ±15° (n = 4 seeds) | 6.070e−22 | 3.035e−22 | **3.927e−22** | **1.29** |

**Blocks fail independence at both skews, in opposite directions.**

- At **15°** the ratio 1.29 > 1 ⇒ quarter-blocks are **positively correlated**; a pooled-block SEM is
  therefore **too small** and overstates significance.
- At **5°** the ratio 0.69 < 1 ⇒ quarter-blocks are **anti-correlated** (mean pairwise ρ̄ ≈ −0.18 at n = 12
  from `Var(mean of 4) = σ²/4 · (1 + 3ρ̄)`); excursions within an arm partly cancel, so the arm mean is
  *better* converged than the block scatter suggests and a pooled-block SEM is **too large**, understating
  significance.

**The n = 4 interim overstated this effect and is superseded.** At 4 seeds the ratio read 0.31 (ρ̄ ≈ −0.30,
close to the −1/3 theoretical floor for four variables); at 12 seeds it settles at 0.69 (ρ̄ ≈ −0.18). The
*direction* held; the magnitude did not. This is exactly the 3-df fragility flagged at the time, and it is
recorded rather than quietly overwritten.

Consequently **neither pooled-block figure is a valid uncertainty** — not the 15° anchor's 3.00σ (which
looked corroborating) and not the 5° ensemble's 1.66σ (which looks alarming). Both are quoted in this
programme strictly as **stationarity diagnostics**. The seed statistic is the inferential one at both skews.

### 2.1 Correction to `RIGID_LOW_SKEW_TORQUE_QUICKLOOK.md` §8

That report states: *"The two uncertainty estimates disagree, and the block one is the honest one."* On the
evidence above **that is not correct** — the block SEM is not honest, it is differently biased, and at 5° it
is biased **conservative**. The quicklook's 1.01σ block figure was therefore not the warning it was read as.

This is a correction to the *reasoning*, not an overturning of the *conclusion*. The quicklook's Q3 verdict
ultimately rested on the 200 ms re-run, not on blocks alone, and that re-run is addressed on its own terms in
§3.

**Resolved at n = 12.** The anti-correlation direction is confirmed with 11 df (ratio 0.69), so the
conclusion stands: the 5° pooled-block SEM is biased conservative and the quicklook's 1.01σ block figure was
not the warning it was read as.

---

## 3. The 200 ms window, re-examined at zero GPU cost

The predecessor's decisive evidence was that extending the same four arms to 200 ms collapsed τ_odd from
−6.99e−23 (2.43σ) to −7.07e−24 (0.05σ) with the seeds disagreeing in sign. Phase 2 is forbidden from running
200 ms arms, but the question can be settled from **stored traces** without new simulation.

**The instrument.** Each arm stores a 4001-point dense trace of `meanRoll`. Because the rigid-body Brownian
torque is a **state-independent** counter hash keyed by (body, step, seed), matched ±ε arms at one seed draw a
**bit-identical** Brownian sequence, so accumulated `Φ_Brown` cancels *exactly* in the odd difference. (The
records confirm this independently: `Om_Brown_odd = 0.0` exactly, and `Ω_total_odd = Ω_drive_odd + Ω_geom_odd`
with the geometric term ~0.05 % of the drift.) Therefore, for **any** sub-window,

```
Omega_odd(t1,t2) = 0.5 * [ (phi_+(t2) - phi_+(t1)) - (phi_-(t2) - phi_-(t1)) ] / (t2 - t1)
```

is a Brownian-free estimator of motor-driven odd rotation over that window. `scratch_rigid_window.py`.

**Validation.** On the 200 ms arm of seed 101 the 25–100 ms window returns **−12.6966 rad/s**, against that
seed's independently recorded 100 ms `Om_total_odd = −12.6965 rad/s`. Exact agreement — which simultaneously
re-confirms that the 200 ms arm reproduces the 100 ms trajectory.

**Result (seeds 101, 102 — the only seeds with 200 ms records):**

| window (ms) | seed 101 | seed 102 | mean | \|m\|/SEM |
|---|---|---|---|---|
| 25–100 (the production window) | −12.70 | −30.36 | −21.53 | 2.44 |
| 100–200 | **+38.04** | **−42.63** | −2.29 | 0.06 |
| 150–200 | **+63.61** | **−85.44** | −10.92 | 0.15 |

Finer 25 ms slices of the same two arms swing from **−116.9 to +128.9 rad/s**.

**Reading.** The later windows do **not** show a coherent sign reversal that would indicate the early window
is a transient being corrected. They show **sign-inconsistent excursions 3–5× larger in magnitude** than the
early window. Averaging the quiet, consistent early window together with the loud, inconsistent later one
dilutes the mean toward zero. The 200 ms "collapse" is therefore best read as a **variance** effect, not
evidence that the signal reverses or decays.

**What this does not establish.** Two seeds cannot settle whether the 25–100 ms window is systematically
special (e.g. incompletely equilibrated at the standing 25 % convention). This analysis removes the
*reversal* reading; it does not prove stationarity. Testing that properly needs longer arms at high seed
count, which is explicitly outside this campaign's authorized scope and is recorded as future work rather
than performed.

---

## 4. Results — 12 matched seeds, 24 arms

### 4.1 Primary

```
tau_odd  = -1.037367e-22 +- 3.502599e-23 N.m   |m|/SEM 2.96   seed-sign 83% (10 of 12)
                                               95% t CI (df=11) [-1.808289e-22, -2.664450e-23]  EXCLUDES ZERO
tau_even = -1.168182e-22 +- 1.923863e-22 N.m   |m|/SEM 0.61   seed-sign 50%   (unresolved, as expected)
```

### 4.2 Secondary and corroborating

```
Om_drive_odd = -31.998 +- 10.804 rad/s   |m|/SEM 2.96   95% CI [-55.778, -8.219]   = -5.09 turns/s
Om_total_odd = -31.981 +- 10.796 rad/s   |m|/SEM 2.96
Om_Brown_odd =   0.000 exactly
Om_geom_odd  = +1.739e-02 +- 8.206e-03 rad/s
mobility closure  max_seed |Om_drive_odd - M_roll*tau_odd| / |Om_drive_odd| = 3.798e-10
```

### 4.3 As twirling

```
v_even        = -0.32846 +- 0.01438 um/s      (the eps-EVEN phenotype; rock solid)
turns per um  = -15.010  +- 4.935   (3.04 sigma)
signed pitch  = -0.0666 um  (66.6 nm)
```

### 4.4 Per-seed matched pairs

| seed | τ_odd (N·m) | Ω_drive_odd (rad/s) | | seed | τ_odd (N·m) | Ω_drive_odd (rad/s) |
|---|---|---|---|---|---|---|
| 101 | −4.117e−23 | −12.70 | | 107 | **+8.978e−23** | +27.69 |
| 102 | −9.856e−23 | −30.40 | | 108 | −2.257e−22 | −69.62 |
| 103 | −1.179e−22 | −36.36 | | 109 | −1.596e−22 | −49.22 |
| 104 | −8.767e−23 | −27.04 | | 110 | −3.465e−22 | −106.88 |
| 105 | **+5.249e−23** | +16.19 | | 111 | −2.135e−22 | −65.87 |
| 106 | −3.181e−23 | −9.81 | | 112 | −6.472e−23 | −19.96 |

Ten of twelve carry the native negative sign; seeds 105 and 107 are the exceptions.

### 4.5 Robustness

```
median -9.311e-23   MAD 6.388e-23   SD 1.213e-22
leave-one-out means span -8.167e-23 .. -1.213e-22  ->  native sign retained 12 of 12
largest-|seed| share of sum|tau_odd_s| = 22.7%   (dominance guard < 35%)
```

### 4.6 Components, populations and health

| quantity | ε-even | ε-odd |
|---|---|---|
| bond-force moment | −1.168e−22 N·m | −1.037e−22 (2.96σ) |
| any other deterministic | +1.27e−30 N·m | −7.29e−31 (0.46σ) |
| **τ per bound head** | −5.287e−24 N·m | **−3.790e−24 ± 1.124e−24 (3.37σ)** |
| positive-torque population | +8.047e−20 N·m | −9.664e−22 (1.44σ) |
| negative-torque population | −8.058e−20 N·m | +8.627e−22 (1.25σ) |
| **mean occupancy N_b** | +28.001 | **−0.359 ± 0.229 (1.57σ)** |
| gliding velocity | −0.32846 µm/s | −3.36e−03 ± 8.55e−03 (0.39σ) |

Torque-component closure over all 24 arms: `max |τ_det − τ_bond − τ_other| / |τ_det| = 2.770e−11`. The
bond-force moment is the entire deterministic axial torque; `τ_other` is ~1e−8 of it.
`invalid = solverFail = rateCapWarns = ruptureEvents = 0` across all 24 arms.

**The occupancy confound raised at n = 8 dissolves.** It read −0.744 ± 0.229 (3.27σ) at n = 8 and is
**−0.359 ± 0.229 (1.57σ)** at n = 12 — unresolved. Independently, the occupancy-normalised signal
(τ per bound head, odd) is **3.37σ**, *stronger* than the raw 2.96σ. So the chiral torque is not an artifact
of one ε sign engaging more heads.

**Two other n = 8 observations also failed to survive, and are retracted here rather than left standing:**
the ε-odd shifts in the positive/negative torque populations read 3.64σ / 3.59σ at n = 8 and are
**1.44σ / 1.25σ** at n = 12. The "chiral signal is a 2.7 % residue of two individually well-resolved
population shifts" framing was an n = 8 artifact. What survives is only the uncontroversial part: the net
axial torque **is** a small residue of a near-cancelling tug-of-war (net/gross = 0.71 %), but the individual
population shifts are *not* separately resolved.

---

## 5. Sequential seed-count convergence

Fixed seed order 101 → 112, chosen before any result was seen.

| n | mean τ_odd (N·m) | SEM | \|m\|/SEM | sign % |
|---|---|---|---|---|
| 2 | −6.986e−23 | 2.869e−23 | 2.43 | 100 |
| 3 | −8.586e−23 | 2.303e−23 | 3.73 | 100 |
| 4 | −8.632e−23 | 1.629e−23 | **5.30** | 100 |
| 5 | −5.855e−23 | 3.050e−23 | 1.92 | 80 |
| 6 | −5.410e−23 | 2.530e−23 | 2.14 | 83 |
| 7 | −3.354e−23 | 2.966e−23 | **1.13** | 71 |
| 8 | −5.756e−23 | 3.516e−23 | 1.64 | 75 |
| 9 | −6.890e−23 | 3.302e−23 | 2.09 | 78 |
| 10 | −9.666e−23 | 4.053e−23 | 2.38 | 80 |
| 11 | −1.073e−22 | 3.817e−23 | 2.81 | 82 |
| **12** | **−1.037e−22** | **3.503e−23** | **2.96** | **83** |

**This trace is the strongest methodological argument in the whole programme.** The estimator passed through
5.30σ at n = 4 and 1.13σ at n = 7 on its way to 2.96σ at n = 12. Either intermediate point, quoted alone,
would have been badly wrong — 5.30σ would have claimed a result three times too strong, 1.13σ would have
declared a null. The n ≥ 8 evaluation floor and the fixed seed order are what prevented both.

**Early-success rule: correctly never fired.** Evaluated at n = 8 (failed 3 criteria: interval contained
zero, 1.64 < 2.5, half-split 99.9 %) and at n = 10 (failed 2: 2.38 < 2.5, half-split 78.8 %). The campaign ran
to the full n = 12 as designed.

---

## 6. Final classification — **L5-PRESENT**

| L5-PRESENT criterion | status |
|---|---|
| ensemble mean has the native sign | **PASS** — −1.037e−22 N·m |
| 95 % t interval excludes zero | **PASS** — [−1.808e−22, −2.664e−23] |
| at least 8 of 12 seed signs agree | **PASS** — 10 of 12 |
| leave-one-out means retain the same sign | **PASS** — 12 of 12 |
| no severe outlier dominance | **PASS** — 22.7 % < 35 % |
| Ω_drive_odd agrees through rigid mobility | **PASS** — 3.798e−10 |
| structural and numerical gates healthy | **PASS** — 0 invalid, 0 solver, closure 2.77e−11 |

**Optional extension to seeds 113–116: NOT justified and NOT run.** The extension is authorized only if
n = 12 lands L5-SUGGESTIVE or L5-UNDERPOWERED. It landed L5-PRESENT.

### 6.1 Reported result

```
tau_odd       = -1.037e-22 +- 3.503e-23 N.m     95% CI [-1.808e-22, -2.664e-23]
Om_drive_odd  =  -32.00   +- 10.80    rad/s     95% CI [ -55.78,    -8.22    ]
turns per um  =  -15.01   +-  4.94                       signed pitch -66.6 nm
tau per bound head (odd) = -3.790e-24 +- 1.124e-24 N.m   (3.37 sigma)
sign fraction 10/12 = 83%   |   rough twirl rate -5.09 turns/s
```

### 6.2 The stationarity qualification — load-bearing, not a footnote

The classification rubric contains no stationarity criterion, so L5-PRESENT is the correct formal verdict.
But the measurement is **not** stationary across its own window, and reporting the ensemble mean without this
would be misleading:

| | Q1 | Q2 | Q3 | Q4 |
|---|---|---|---|---|
| τ_odd per block, 5° (n = 12) | −3.086e−22 | −9.953e−23 | −5.038e−23 | **+4.357e−23** |
| τ_odd per block, 15° (n = 4) | −4.458e−22 | −6.136e−22 | −3.135e−22 | −4.502e−22 |

```
5 deg   paired Q1-Q4  -3.522e-22 +- 1.619e-22  (2.18 sigma)   per-seed trend slope 2.04 sigma, 8/12 seeds decaying
15 deg  paired Q1-Q4  +4.313e-24 +- 3.697e-22  (0.01 sigma)   per-seed trend slope 0.34 sigma, 2/4 seeds
5 deg   first half of window -2.041e-22 (2.52 sigma)  |  second half -3.404e-24 (0.06 sigma)
```

At 5° the odd torque **decays across the measured window**, from a Q1 value comparable to the *full-window*
15° signal down to zero (indeed marginally positive) by Q4. At 15° it is flat. Both trend statistics at 5°
sit just under the 95 % threshold (t₁₁ = 2.201 vs 2.04 and 2.18), so the decay is **suggestive, not
established** — but it is corroborated independently by §3's finding that the 100–200 ms windows carry no
resolved signal, and the two observations have the same shape.

**Consequence for interpretation.** The resolved 5° quantity is a **decaying chiral torque**, and the
ensemble mean is dominated by the early part of the window. It should **not** be quoted as a steady-state
twirling torque. The most economical account consistent with everything measured is that the standing 25 %
equilibration convention is **insufficient at 5°** — the arm is still relaxing into its steady state during
the first part of the "measured" window — while at 15° the genuine signal is large enough that any such
transient is not distinguishable.

**This is not settled here, and testing it is outside the authorized scope** (it needs longer arms at high
seed count, which Phase 2 explicitly forbids). It is recorded as the single highest-value follow-up.

---

## 7. 5° / 15° ratio

```
R_tau = tau_odd(5 deg) / tau_odd(15 deg) = 0.2276 +- 0.1246
   linear prediction   5/15        = 0.3333   ->  0.85 sigma away
   sine prediction  sin5/sin15     = 0.3367   ->  0.88 sigma away
```

The measured ratio is **consistent with proportional small-angle scaling** (< 1σ from both predictions),
though it sits below the central prediction. As recorded previously and re-confirmed here, **linear and
sine-like scaling are not separable by this assay**: the two predictions differ by 1 % against a ratio
uncertainty of 55 %. No scaling-law claim is made.

Rotationally the two skews differ more than the torque ratio alone suggests, because pitch compounds the
rotation and translation:

| | ±5° | ±15° |
|---|---|---|
| Ω_drive_odd | −32.00 ± 10.80 rad/s | −140.59 ± 60.57 rad/s |
| turns per µm | −15.01 ± 4.94 | −63.50 ± 28.56 |
| signed pitch | −66.6 nm | −15.7 nm |
| experimental comparator (POST-HOC) | ~0.47 µm, i.e. ~2.1 turns/µm | same |

Both skews give a pitch far tighter than the ~0.47 µm myosin-II comparator — by ~7× at 5° and ~30× at 15°.
That gap narrows in the direction of lower skew, but 5° is still an order of magnitude off. This is a
**post-hoc descriptive comparison, not a target**, and it is a cross-model statement besides; it is recorded
because it is the obvious next question, not because this campaign addressed it.
