# Powered zero-skew native-vs-mirror study — sparse long-pitch actin lattice

**Mechanistic screening campaign. No physics changed, no parameter tuned, no new chirality introduced, no new
device kernel. Every arm ran at eps = 0.**

- **Worktree:** `/home/jba/Code/SoftBox` · **Branch:** `gpu-mat-bottlenecks-explicit-singlehead` · base commit `3119000`
- **Date:** 2026-08-12
- **Raw data:** `RUN_LOGS/chiral_sites/zero_skew_sparse_mirror/` (48 arm records + 48 episode files)
- **Figures:** `docs/twirling/figures/zero_skew_sparse_mirror/`
- **Predecessor:** `docs/attachment/SPARSE_LONG_PITCH_ACTIN_SITE_LATTICE.md` (the corrected geometry and its
  n = 2 pilot, §13 of that report)

---

## 1. Executive verdict — **M0: no mirror-odd torque**

**The sparse long-pitch helical actin-binding geometry does NOT generate a reproducible handed deterministic
torque through the explicit flexible-S2 motor at zero imposed motor skew.**

At n = 24 matched native/mirror seed pairs:

| | value |
|---|---|
| **tau_mirror_odd** | **−1.602e−23 ± 8.500e−23 N·m** |
| **\|mean\| / SEM** | **0.19** |
| **95 % t CI** | **[−1.919e−22, +1.598e−22] N·m — includes zero** |
| **matched-seed signs** | **12− / 12+ — exactly even** |
| **exact two-sided sign test** | **p = 1.0000** |
| secondary: Omega_mirror_odd | −1.785 ± 2.665 rad/s (0.67 sigma, 14−/10+, p = 0.54) |
| secondary: turns_mirror_odd | −1.11e−03 ± 6.26e−03 (0.18 sigma, 12−/12+, p = 1.00) |
| invalid / solverFail | **0 / 0** across all 48 arms |

**The n = 2 pilot result is refuted, not merely unresolved.** The pilot's apparent effect was
tau_mirror_odd = −3.65e−22 N·m (2/2 seed reversal). The per-seed SD measured here is 4.16e−22 N·m, so this
design detects |tau_mirror_odd| ≈ **2.4–2.5e−22 N·m** with 80 % power at alpha = 0.05 (paired-t MDE
≈ (t_0.025,23 + t_0.20,23)·SD/√n) — comfortably below the pilot value. Nothing was detected, and the 95 % CI
**excludes the pilot estimate**.

**Upper bound worth quoting:** at zero imposed motor skew the lattice-handed deterministic axial torque
satisfies **|tau_mirror_odd| < 1.92e−22 N·m (95 %)**. For scale, the viscosity campaign's ±15° converter-skew
arms carry tau_odd ≈ −2.7e−22 N·m, so geometry alone contributes less than that and is consistent with zero.

**One sanity channel flagged and investigated (§8): mean glide differs native vs mirror by 2.10 sigma
uncorrected.** It does not survive multiplicity correction across the six sanity channels, its sign test gives
p = 0.15, it is uncorrelated with the torque channel (r = −0.09), and it tracks ordinary engagement noise
(r = −0.60 with the avgBound difference). It does not undermine the torque null; it is the one thing a
follow-up should re-check.

---

## 2. Preregistered analysis plan

Fixed in code (`ChiralSiteHarness`, "PREREGISTERED ANALYSIS PLAN") **before the n = 24 data existed**; the
only prior was the n = 2 pilot.

| element | preregistered choice |
|---|---|
| primary endpoint | `tau_mirror_odd = 0.5*(tau_native − tau_mirror)` per matched seed |
| replicate axis | independent **seeds** — never trajectory blocks |
| directional prior | the pilot gave native tau < 0, mirror tau > 0 ⇒ tau_mirror_odd < 0 |
| secondary | `Omega_mirror_odd`, `turns_mirror_odd` — **not required to resolve** |
| control | `tau_mirror_even = 0.5*(tau_native + tau_mirror)` must not carry the effect |
| ladder | n = 8, 12, 16, 20, 24, each reporting mean, SEM, \|mean\|/SEM, 95 % t CI, sign count, sign test |
| futility | stop early **only** if at n = 16 \|mean\|/SEM < 0.5 **and** the sign count is within 1 of even |
| achiral sanity | glide / avgBound / attachment flux / mean z must match native vs mirror within noise |
| sign convention | the project's existing axial-torque convention, unchanged |

**The futility rule was evaluated at n = 16 and did NOT fire** (|mean|/SEM = 0.61 ≥ 0.5, although the signs
were exactly 8−/8+). The campaign therefore ran to the full n = 24, which was the preferred discipline anyway.

---

## 3. Frozen candidate configuration

Selected with `-zsm-campaign`, which calls `zsmFreezeCandidate()`; the **expanded** configuration is printed
and asserted at run start (§4, Gate 1a). Every arm:

```
sites=every4(rise=10.800 nm, dPhi/site=+54.00 deg, phase=filament-global)  bindPath=site-aware
headRollDof=ON  headRollBrownian=ON  registryK=0.000e+00 N·m/rad
binding-skew-deg=+0.00   stroke-skew-deg=+0.00   converter-stroke-skew-deg=+0.00
siteExclusive=ON  mirror=±1  capture=12.0 nm  Ractin=3.50 nm  randomBaseAzimuth=OFF  surfaceBond=ON
z SLAB ON (walls −9.93 … +70.07 nm surface limits); harmonic matZConfine NOT wired
```

| requirement | actual |
|---|---|
| sparse `every4` lattice | rise = 10.8000 nm = 4 × `actinMonoRadius` |
| filament-global phase | `sbP[25] = 1` |
| site-aware capture | `bindPath = site-aware` (never `centreline+snap`) |
| z slab ON, harmonic OFF | `zSlabOn() = true`; the two are mutually exclusive branches |
| site exclusivity | ON |
| surface radius | 3.5000 nm = `Constants.radius` |
| head roll | exactly as validated (`HEAD_ROLL` ON, `REG_K` = 0 ⇒ registry couple inert) |
| chemistry / motor | native Lymn–Taylor, same explicit-S2 motor, untouched |
| **imposed skew** | **conv + bind + stroke = 0.0 deg exactly** |
| Vilfan target-zone hazard | OFF |

Scene: 12-segment filament, filament Brownian ON, density 400 heads/µm², eta = 0.1 Pa·s, dt = 2.5e−6 s,
8000 steps = **20.0 ms physical**, `EQUIL_FRAC = 0.25` ⇒ 2000 steps discarded, **15.0 ms measured** — the same
duration and equilibration as the compatibility pilot, identical between native and mirror.

Seeds **7001–7024**, a fresh range not used for any model selection. Native and mirror are **interleaved per
seed** (7001 native, 7001 mirror, 7002 native, …) so each pair is acquired back-to-back under identical
machine conditions.

---

## 4. Pre-launch gates

`./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -zsm-gates -gpu`
(`RUN_LOGS/chiral_sites/zero_skew_sparse_mirror/G0_pre_launch_gates.txt`). **ALL PASS.**

| gate | check | result |
|---|---|---|
| **1a / 2 / 4** | expanded config; lattice, phase, site-aware capture, slab, exclusivity, radius, zero skew, TZ off | all **OK** (§3 table) |
| **1b** | `MIRROR_SIGN = −1` is an **exact geometric reflection** | 196 sites both signs; max\|d(axial)\| = **0.00e+00 µm**; max\|phi_nat + phi_mir\| = **0.00e+00 deg**; max\|dy\| = **0.00e+00**; max\|z_nat + z_mir\| = **0.00e+00 µm**; rise / radius / dBind / preload / N / nSeg identical ⇒ **EXACT REFLECTION** |
| **1c** | RNG pairing under reflection | max\|d(filament Brownian draw)\| = **0.000e+00** ⇒ **bit-identical**. The counter-based Wang-hash streams are keyed on (entity, step, seed) and the mirror changes only `chiP`/`sbP` values, so the pair is matched **at the RNG source**; trajectories diverge only through the reflected forces |
| **3** | z boundary | `matZSlab` wired, `matZConfine` not; walls −9.93 … +70.07 nm; pilot upper-wall contact **0.000 %**, lower wall 0.176 % of segment-steps |
| **5** | telemetry inertness (BUDGET + EPISODE_TELEM + ACCESS_TELEM OFF vs ON, matched seed, 1200 steps) | \|d tau\| = \|d glide\| = \|d avgBound\| = \|d omegaFit\| = \|d turns\| = **0.000e+00 exactly** ⇒ **trajectory-inert** |
| **6** | CPU/GPU | **no new device kernel** — the telemetry is transfer-only (`EPISODE_TELEM` adds `transferToHost` alone) plus host-side reduction. Site enumeration/capture equivalence was gated separately at 1 and 12 segments: `siteIdMism = 0`, `bindMism = 0`, `max|dAzim| = 0` (predecessor report §10) |

Gate 1b is the load-bearing one: the mirror arm is a genuine reflection of the actin lattice with every
non-chiral quantity held bit-identical, which is what makes the odd/even decomposition meaningful.

---

## 5. Instrumentation

**Nothing new was built.** Every quantity the task asked to collect already existed in the episode record
(`ConvBudget`) and the accessibility telemetry (`AccessTel`), both of which are host-side reductions over
buffers the graph already crosses back:

| requested | existing field |
|---|---|
| A. binding-site azimuth | `F_SITE`, `F_AZIM` per episode; `hBeta`/`hBetaBind` histograms; NEAR/SIDE/FAR class; filament roll phase (`rollN`/`rollTau`) |
| B. S2 strain / geometry | `F_S2EXT` (axial extension), `F_S2BEND` (transverse/bend proxy), `F_PHI`, `F_PSI`, `F_WF8` (F8 work), `F_FAX`/`F_FTAN`/`F_FRAD` |
| C. torque | `F_TAUAX` per episode; `F_JPRE`/`F_JSTROKE`/`F_JEARLY`/`F_JLATE` (angular impulse by phase); `tauPos`/`tauNeg`/`tauAbs`; `ageTau` |
| D. stroke phase / nucleotide | `F_ATTACH`/`F_STROKE`/`F_DETACH`, `F_PRELIFE`/`F_POSTLIFE`, `F_NSTROKE`, `tauByState[0..3]`, `tauPre`/`tauPost`, `evTau` |
| E. axial mechanical role | `roleN`/`roleFax`/`roleTau` (puller / dragger), `tauPull`/`tauDrag` |

Per-arm scalars are persisted as a named-row TSV and the **raw per-episode records** as a second TSV
(695 native + 700 mirror episodes total), so the mechanism analysis needs no re-running.

---

## 6. Primary endpoint — sequential ladder

| n | mean (N·m) | SEM | \|m\|/SEM | 95 % t CI | signs | sign-p |
|---:|---:|---:|---:|---|---:|---:|
| 2 | +6.212e−23 | 9.157e−23 | 0.68 | [−1.101e−21, +1.226e−21] | 1− / 1+ | 1.0000 |
| 4 | +1.213e−22 | 8.610e−23 | 1.41 | [−1.526e−22, +3.953e−22] | 1− / 3+ | 0.6250 |
| 8 | −6.042e−23 | 1.359e−22 | 0.44 | [−3.819e−22, +2.611e−22] | 4− / 4+ | 1.0000 |
| 12 | −1.120e−23 | 1.105e−22 | 0.10 | [−2.545e−22, +2.321e−22] | 6− / 6+ | 1.0000 |
| 16 | −7.121e−23 | 1.158e−22 | 0.61 | [−3.180e−22, +1.756e−22] | 8− / 8+ | 1.0000 |
| 20 | −3.948e−23 | 9.678e−23 | 0.41 | [−2.420e−22, +1.631e−22] | 10− / 10+ | 1.0000 |
| **24** | **−1.602e−23** | **8.500e−23** | **0.19** | **[−1.919e−22, +1.598e−22]** | **12− / 12+** | **1.0000** |

The convergence is textbook and **non-monotonic in the way the project has repeatedly warned about**: the
estimate wanders positive at n = 4 (1.41 sigma), crosses zero, and settles at 0.19 sigma. At no ladder rung
did the interval exclude zero, and the sign count is even at every rung from n = 8 onward. **Figure 3.**

**Mirror-even control:** tau_even = −9.250e−23 ± 6.099e−23 N·m (1.52 sigma, 15− / 9+, p = 0.31). It is
*larger in magnitude* than the odd channel — i.e. what little mean torque the native arm carries is
predominantly **achiral background**, not handed. It is itself unresolved.

**Raw arms:**

| quantity | native | mirror |
|---|---|---|
| tau (N·m) | −1.085e−22 ± 1.18e−22 | −7.648e−23 ± 8.96e−23 |
| omegaFit (rad/s) | −3.598 ± 7.71 | −0.028 ± 6.65 |
| turns | −4.795e−03 ± 1.84e−02 | −2.572e−03 ± 1.47e−02 |

Neither arm's torque is individually resolved either (0.92 and 0.85 sigma).

---

## 7. Secondary endpoint — rotation

| quantity | mean | SEM | \|m\|/SEM | 95 % CI | signs | sign-p |
|---|---|---|---|---|---|---|
| Omega_mirror_odd (rad/s) | −1.785 | 2.665 | 0.67 | [−7.30, +3.73] | 14− / 10+ | 0.5413 |
| turns_mirror_odd | −1.112e−03 | 6.258e−03 | 0.18 | [−1.41e−02, +1.18e−02] | 12− / 12+ | 1.0000 |

**Rotation does not resolve, which was expected and is not required** for the torque experiment: the pilot
already showed that Brownian trajectory rotation is far noisier than the deterministic torque. Here both
channels are null, so no interpretive tension arises. **Figure 4.**

---

## 8. Achiral transport sanity — one flag, investigated

| quantity (mirror-odd) | mean | SEM | \|m\|/SEM | verdict |
|---|---|---|---|---|
| **glide (µm/s)** | **−0.1538** | **0.0733** | **2.10** | **flagged — see below** |
| avgBound | +0.0234 | 0.0598 | 0.39 | matched |
| attachment flux (binds/s) | +1.39 | 74.79 | 0.02 | matched |
| mean z (nm) | −0.166 | 0.380 | 0.44 | matched |
| stroke rate (/s) | −9.72 | 75.09 | 0.13 | matched |
| upper-wall contact fraction | +2.03e−06 | 2.03e−06 | 1.00 | matched |

Native glide −1.912 ± 0.160 µm/s vs mirror −1.605 ± 0.175 µm/s (ratio 1.19). Both glide in the correct
(negative) direction. **Investigation, as required before interpreting the torque result:**

1. **Multiplicity.** Six sanity channels were tested. The Bonferroni threshold at alpha = 0.05 is |t| > 2.81;
   the observed |t| = 2.10 does not reach it.
2. **Sign test.** 16− / 8+, exact two-sided **p = 0.15** — not significant.
3. **Not the torque channel.** corr(glide_odd, tau_odd) = **−0.09**: the seeds with a glide asymmetry are not
   the seeds with a torque asymmetry, so this is not a chiral drive leaking into transport.
4. **It tracks engagement noise.** corr(glide_odd, avgBound_odd) = **−0.60**: seeds where the native arm
   happens to hold more bound heads glide faster. avgBound itself is matched at 0.39 sigma, so this is
   ordinary trajectory-level fluctuation propagating into velocity, not a systematic recruitment asymmetry
   (attachment flux matches at 0.02 sigma).
5. **Not stable across halves.** First 12 seeds −0.109 ± 0.106 (1.02 sigma); last 12 seeds −0.199 ± 0.104
   (1.91 sigma). Same sign, neither half resolves.

**Conclusion: the flag does not undermine the torque null**, but it is not dismissed either — it is the single
channel a follow-up should re-check with more seeds. Note that under a reflection of the lattice, axial
transport is an even quantity, so a genuine nonzero odd component would be meaningful if it survived.

---

## 9. Mechanism analysis

Because the primary endpoint is null there is no effect to localize; the decomposition is reported to show
that **no sub-channel hides a resolved effect** that the total averages away.

**Torque sub-totals, mirror-odd channel** (none exceeds 1.7 sigma):

| channel | mean (N·m) | SEM | \|m\|/SEM |
|---|---|---|---|
| pre-stroke (ADP·Pi dwell) | +2.124e−23 | 2.222e−23 | 0.96 |
| post-stroke | −3.726e−23 | 7.911e−23 | 0.47 |
| puller heads | +8.87e−26 | 4.156e−23 | 0.00 |
| dragger heads | −1.665e−23 | 4.420e−23 | 0.38 |
| positive-torque heads | +6.764e−23 | 1.662e−22 | 0.41 |
| negative-torque heads | −8.367e−23 | 1.636e−22 | 0.51 |
| nucleotide state 0 | +1.390e−23 | 1.159e−23 | 1.20 |
| state 2 (ADP·Pi) | −3.696e−24 | 2.024e−23 | 0.18 |
| state 3 (ADP) | −2.623e−23 | 8.186e−23 | 0.32 |
| NEAR sites (summed) | +3.890e−19 | 2.388e−19 | 1.63 |
| SIDE sites (summed) | −2.447e−19 | 2.287e−19 | 1.07 |
| FAR sites (summed) | −2.404e−19 | 2.009e−19 | 1.20 |

The NEAR/SIDE/FAR sums are the largest excursions (1.0–1.6 sigma) and **cancel** against one another
(+3.89 − 2.45 − 2.40 ≈ −0.96, in units of 1e−19), consistent with noise rather than a class-localized drive.

**Attachment-age resolution** (Figure 7): the mirror-odd torque scatters about zero at **every** age bin from
0 steps to >511 steps; the largest is 0.65 sigma. There is no signature at capture, none growing through the
stroke, none in post-stroke drag, and no sign change with age.

**Site azimuth × torque** (Figure 5): occupancy is strongly bimodal in azimuth — the lawn-facing preference
inherited from the z-slab study — and is **the same in both arms**, as it must be for a reflected lattice
(NEAR 0.389 ± 0.023 native vs 0.391 ± 0.025 mirror; FAR 0.224 ± 0.020 vs 0.247 ± 0.019). The mirror-odd
per-sample torque in each of the 18 azimuth bins scatters about zero with no coherent pattern; the largest bin
reaches 2.1 sigma out of 18 bins, which is expected by chance.

**Site azimuth × S2 strain** (Figure 6, 695 native + 700 mirror episodes): **no coupling.** S2 axial extension
is flat at ≈ 38.5 nm across all azimuth bins in both arms, and the transverse/bend proxy is flat at
≈ 1.3–1.5e−20. Selecting a particular helical site does **not** produce a systematic lateral or S2 strain, and
there is therefore no strain asymmetry available to reverse under reflection. **This is the clearest
mechanistic statement in the report:** the sparse helical geometry changes *where* the head attaches, but at
this motor's compliance the attachment azimuth does not measurably bias the strain the motor develops.

**Axial mechanical role:** puller and dragger channels are both null (0.00 and 0.38 sigma), so the earlier
project finding that chiral torque can be decoupled from axial role is neither confirmed nor contradicted
here — there is no torque to decompose.

---

## 10. Figures

`docs/twirling/figures/zero_skew_sparse_mirror/`, all produced by `scripts/plot_zero_skew_mirror.py` from the
campaign records:

| figure | file | content |
|---|---|---|
| 1 | `fig1_pairs_torque.png` | per-seed native vs mirror torque, matched pairs connected |
| 2 | `fig2_mirror_odd_torque.png` | per-seed mirror-odd torque with mean and 95 % CI — **the primary result** |
| 3 | `fig3_sequential.png` | cumulative mean and CI vs n, for the primary endpoint and its mirror-even control |
| 4 | `fig4_angular_velocity.png` | native vs mirror angular velocity and its odd channel (secondary) |
| 5 | `fig5_azimuth_torque.png` | occupancy by site azimuth, and the mirror-odd per-sample torque per bin |
| 6 | `fig6_azimuth_strain.png` | site azimuth vs S2 axial extension, bend proxy and episode torque |
| 7 | `fig7_age_torque.png` | attachment-age resolved torque, native/mirror and mirror-odd |

Figure 3 is the one to read first: the CI collapses symmetrically around zero as n grows.

---

## 11. Classification

**M0 — no mirror-odd torque.** The matched mirror-odd torque is consistent with zero (0.19 sigma, CI includes
zero) and the signs are unstructured (exactly 12− / 12+, sign-test p = 1.0). This is a **negative result with
teeth**, not an underpowered one: the design had > 80 % power against the pilot's effect size and its 95 % CI
excludes that value.

Not M1 (the effect does not merely fail to reach threshold — the point estimate is at zero and the signs are
even). Not M2/M3. Not M4 (native and mirror behave as a clean reflection: Gate 1b exact, azimuth occupancy
matched, all engagement channels matched). Not M5 (all gates pass, invalid = solverFail = 0).

---

## 12. What may and may not be concluded

**May be stated:**

> Under the corrected sparse long-pitch helical binding geometry — 10.8 nm site spacing, +54° per site,
> filament-global phase, site-aware capture, one head per site, free filament height within the validated slab
> — the actin-side geometry alone does **not** produce a detectable handed deterministic axial torque through
> the explicit flexible-S2 motor. At 24 matched native/mirror seed pairs the lattice-handed torque is bounded
> by |tau_mirror_odd| < 1.92e−22 N·m (95 %), below the torque the ±15° converter-skew arms carry.
>
> The mechanism telemetry gives the reason: at this motor's compliance the binding-site azimuth does not
> measurably bias S2 strain (extension and bend are flat across azimuth in both arms), so there is no
> handed strain channel for the lattice to drive.

**May NOT be stated:** that twirling is impossible in this model; that motor skew is necessary in vivo; that
the helical geometry is unimportant. The corrected geometry remains the physically right actin representation
regardless of this null — it fixes a genuine phase discontinuity and a missing-site defect, and it changes
recruitment and glide (predecessor report §12). This campaign says only that **it is not, by itself, a
twirling generator at this operating point**.

---

## 13. Limitations

1. **One operating point.** Density 400 heads/µm², eta = 0.1 Pa·s (the viscosity study established that the
   canonical eta actively suppresses twirling — Ω_odd is 1.06 sigma even at n = 24 with ±15° skew), L = 40 nm
   S2, 12 segments, 20 ms. A null here does **not** transfer to eta = 0.01, to higher density, or to longer
   durations.
2. **20 ms is short.** The design deliberately uses seeds, not duration, as the replicate axis; but a
   deterministic drive far below this bound could still accumulate over seconds. That is a separate
   experiment (§14).
3. **No orientational disorder in the lawn.** `RAND_BASE_AZ = false`: every motor shares the lab triad. This
   is the standing idealization of the single-head lawn, not a choice made here, but it means the ensemble
   cannot average over motor azimuth.
4. **`REG_K = 0`** — the bound orientational registry couple is inert, as in every campaign arm. A stiff
   registry is the obvious candidate for coupling site azimuth to head orientation and was not exercised.
5. **The glide flag (§8)** is unresolved at 2.10 sigma uncorrected.
6. **Episode counts are modest** (≈ 29 per arm, 1395 total), so the azimuth × strain analysis is powered for
   flat-vs-strongly-structured, not for a subtle gradient.
7. **Statistical hygiene note:** the shared `T95_TWO_SIDED` table clamped to the normal approximation 1.960
   above df = 20, making n = 24 intervals slightly too narrow. It was **extended to df = 40** (t_23 = 2.069)
   as part of this task — a lookup constant, and the correction is conservative. Previously published n = 24
   intervals computed with 1.960 were marginally narrow; no conclusion depends on the third digit.

---

## 14. Recommended next scientific step

**Do not re-run this screen at more seeds.** The primary channel is at zero with even signs and the design
already excludes the pilot effect; more seeds at this operating point buy little.

In priority order:

1. **Test the operating point, not the sample size.** The viscosity campaign's decisive finding is that
   rotation is **drag-limited** and that eta = 0.1 Pa·s suppresses twirling below detectability. The single
   most informative repeat of this experiment is the same zero-skew native/mirror pair at **eta = 0.01 Pa·s**,
   where Ω_odd rose 22.8× for the skewed motor. If lattice geometry generates any handed torque, that is where
   it becomes visible. Cost is comparable (≈ 25 min GPU for n = 24 at matched physical duration).
2. **Exercise the registry couple.** `REG_K > 0` is the mechanism that would convert a site azimuth into a
   head-orientation constraint, i.e. the missing link that §9 shows is absent (azimuth does not bias S2
   strain). A small `REG_K` ladder at eps = 0, native vs mirror, is the direct test of "could this geometry
   ever drive rotation".
3. **Only if (1) or (2) resolves** — the seconds-long zero-skew native-vs-mirror accumulation experiment, to
   ask whether a resolved deterministic torque becomes observable rotation. It is premature now: there is no
   torque to accumulate.

---

## Appendix — commands and cost

```bash
# pre-launch gates (must pass before any arm runs)
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -zsm-gates -gpu

# the campaign: 24 matched native/mirror pairs, interleaved, resume-safe
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh \
    -zsm-campaign -gpu -seeds 24 -steps 8000 -zsm-seed0 7001

# re-report from the records (no simulation)
./scripts/run_chiral_sites.sh -zsm-report -seeds 24 -steps 8000 -zsm-seed0 7001

# figures
python3 scripts/plot_zero_skew_mirror.py
```

**Cost and coexistence.** 48 arms, GPU device-resident throughout, **24.4 min wall-clock** (≈ 30 s/arm),
one GPU process at a time. The GPU was idle at launch (0 % utilization, no compute processes). Four unrelated
single-core-pinned CPU `-longrun` jobs from the **`softbox-rigid-filament-skew-twirling`** worktree were
running throughout and were left untouched — they load classes from their own tree, so rebuilds here cannot
affect them, and this campaign's host-side work occupied one of the four free physical cores.
