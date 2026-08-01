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

**Status: IN PROGRESS — results pending; methodology below is final.**

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
| ±5° (n = 4 seeds) | 2.076e−22 | 1.038e−22 | **3.259e−23** | **0.31** |
| ±15° (n = 4 seeds) | 6.070e−22 | 3.035e−22 | **3.927e−22** | **1.29** |

**Blocks fail independence at both skews, in opposite directions.**

- At **15°** the ratio 1.29 > 1 ⇒ quarter-blocks are **positively correlated**; a pooled-block SEM is
  therefore **too small** and overstates significance.
- At **5°** the ratio 0.31 < 1 ⇒ quarter-blocks are **anti-correlated** (mean pairwise ρ̄ ≈ −0.30 from
  `Var(mean of 4) = σ²/4 · (1 + 3ρ̄)`); excursions within an arm partly cancel, so the arm mean is *better*
  converged than the block scatter suggests and a pooled-block SEM is **too large**, understating
  significance.

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

**Caveat, stated plainly:** with 4 seeds each SD estimate carries 3 df and is itself noisy, so the *direction*
of the block correlation is indicative rather than established. The n = 12 ensemble re-tests it.

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

## 4. Results — PENDING

## 5. Sequential seed-count convergence — PENDING

## 6. Final classification — PENDING

## 7. 5° / 15° ratio — PENDING
