# Rigid-Filament ±15° Skew Torque — Power Anchor

**Question.** Under the identical rigid-filament, FDT-consistent, noise-decomposed pipeline that returned a
null at 5°, does a converter skew of ±15° produce a clear chirality-odd axial motor torque?

This is a **power calibration**, not a new physical question. 15° carries a signal the standing chiral-site
campaign already measured (τ_odd ≈ −2.7e−22 N·m, Ω_odd ≈ −91 rad/s, mirror-reversed), so it establishes
whether this estimator can resolve a chiral torque it is known to contain — before any further low-skew work.
It is the gate for the high-seed 5° stage.

**Answer: YES — the rigid model revalidates a chirality-odd motor torque at 15°. Classification A15 (PASS).**
τ_odd = **−4.558e−22 ± 1.963e−22 N·m** (|m|/SEM **2.32**, native negative sign, 3 of 4 matched seeds),
Ω_drive_odd = **−140.6 ± 60.6 rad/s**. All eight A15 conditions and the preferred |m|/SEM ≥ 2 strength
criterion are met; the borderline seed extension was therefore **not** triggered. The high-seed 5° stage was
launched immediately.

**Stated honestly:** at n = 4 the Student-t 95 % interval (df = 3, t = 3.182) is
[−1.081e−21, +1.690e−22] and **contains zero**. The gate explicitly does not require a formal p < 0.05 at
this n; the result rests on sign, magnitude, leave-one-out stability and the mirror convention being
coherent, which they are. This is a *power anchor*, not a significance claim.

---

## 1. Commit, provenance and commands

| item | value |
|---|---|
| worktree | `/home/jba/Code/softbox-rigid-filament-skew-twirling` |
| branch | `feature/rigid-filament-skew-twirling` |
| commit (production) | `4a38db7` |
| simulation code | **identical to `30e8891`**, the commit that produced the 5° records — `git diff 30e8891 HEAD -- softbox/ scripts/` was empty before the only change below |
| the one delta | derived-CSV naming + atomic rename (`atpCsv`). Analysis-layer artifact; **no arm executes it** |
| runner | **GPU device-resident**, single `glide` TaskGraph (`buildGlidingGraph(prod)`), PTX / RTX 5070 |
| monitoring | `run_gpu_monitored.sh` outermost on every arm; external recorder pid 1988, session `20260728T071146Z` |

Production command (one process per seed block; `atpArm` reuses any COMPLETE record, so re-entry is idempotent):

```
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -atp-map -gpu -eta 0.01 \
    -atp-points 10 -atp-eps-deg 15 -density 400 -seed 101 -seeds 4 \
    -atp-duration-ms 100 -filament-segments 1 -fil-thermostat fdt -rot-decomp
```

Resolved scene (from the record provenance line):

```
filSegs=1  filModel=RIGID-1seg  thermostat=FDT  rupture_mode=0
atpUM=10.00  atpOn=100.0 /s  density=400 heads/µm²  nMotors=1200
eps=±15.0°  mirror=1  eta=0.01 Pa·s  dt=2.5000e-07 s  durationS=0.100  equil=0.25
```

`gamma_roll = 3.241935e-24 N·m·s`, `M_roll = 3.084577e+23 rad/(s·N·m)` — identical to the 5° stage, as it
must be (`γ_roll = 4πηR²L` is extensive in length and the scene is unchanged apart from ε).

**Duration accounting.** Each arm simulates 100 ms; the standing 25 % equilibration is discarded, so the
measured window is **75 ms** and the four disjoint blocks are **18.75 ms** each. The convention was not
changed for this study.

**Namespace.** Rigid 15° records are `rigid_thfdt_u0010.00_e0150_r0400.0_d00100000_{p|n}_<seed>` — the
`e0150` field makes them structurally incapable of aliasing onto the 5° (`e0050`) set or any flexible record.
The historical flexible 15° campaign records are **not** reused as production evidence here.

---

## 2. Pre-run check — PASS

Run at the **production skew**, in the distinct 10 ms `d00010000` namespace so it can never be confused with
or reused as a production record. Log `RUN_LOGS/rigid/sanity_15deg_10ms.txt`; both signs, seed 101.

| check | result |
|---|---|
| `filSegs = 1` | **yes** — `filSegs=1 filModel=RIGID-1seg`; `atpArm`'s structural guard throws otherwise |
| `filModel = RIGID-1seg` | **yes** |
| thermostat = FDT | **yes** — `thermostat=FDT` |
| rotational decomposition active | **yes** — `rdSteps = measSteps = 30000`, `rdBlockSteps = 7500` |
| decomposition **transfer** active | **yes** — τ_det = −2.4059e−21, Ω_drive = −742.1, Ω_Brown = +536.7, Ω_geom = +0.254 rad/s, all Φ finite. **Not** the §2.4 silent-zero mode, in which every one of these reads exactly 0.0 |
| rigor rupture OFF | **yes** — `rupture_mode=0`, `detachRigor=0`, `ruptureEvents=0`, `detATP` fraction 1.000 |
| binding occurs | **yes** — avgBound 29.45 (+ε) / 32.09 (−ε) of 1200 heads |
| torque records populated | **yes** — every `tauDet/tauBond/tauOther/omDrive/omBrown/omGeom/omTotal/phi*` field finite and non-zero |
| invalid / solver failures | **none** — `invalid=0`, `solverFail=0` on both arms |
| torque-component closure | `|τ_det − τ_bond − τ_other| / |τ_det|` ≈ **9e−12** |

The 10 ms pair is a health fixture, not evidence (one seed). For the record it returned τ_odd = −2.897e−22 N·m,
Ω_drive_odd = −89.4 rad/s on the native negative sign, with `Om_Brown_odd` exactly 0.

**Reuse note.** An existing validated sanity record at 5° was available, but the pipeline was re-checked at
ε = 15° because ε is the variable under test; the cost was ~5 minutes.

---

## 3. Analysis layer

The in-harness `reportRigidTorque` prints per-seed values, disjoint blocks, halves, components and
populations. It does **not** print Student-t intervals, leave-one-seed-out means, robust summaries,
single-seed influence, or the running estimate versus seed count. Those are computed by
`scratch_rigid_odd.py`, which reads the durable `.tsv` records directly and writes nothing.

**It is validated against the published 5° result**: re-run over the existing `e0050 / d00100000` records it
reproduces `τ_odd = −6.986231e−23 ± 2.869278e−23`, `|m|/SEM 2.43`, `Ω_drive_odd = −21.5497 ± 8.8505` and
mobility closure 3.798e−10 — the quicklook's numbers exactly.

Estimators, per seed *s* (the matched seed is the unit of independence throughout):

```
tau_odd_s        = 0.5 * [ tau_det(+eps,s) - tau_det(-eps,s) ]
tau_even_s       = 0.5 * [ tau_det(+eps,s) + tau_det(-eps,s) ]
Omega_drive_odd_s= 0.5 * [ Omega_drive(+eps,s) - Omega_drive(-eps,s) ]
Omega_total_odd_s= 0.5 * [ Omega_total(+eps,s) - Omega_total(-eps,s) ]
Omega_Brown_odd_s= 0.5 * [ Omega_Brown(+eps,s) - Omega_Brown(-eps,s) ]   (identically 0 by construction)
```

---

## 4. Scheduling and GPU health

**Two-process concurrency was trialled and REJECTED.** Full record: `RUN_LOGS/rigid/CONCURRENCY_TRIAL.md`.

Measured on the production arms: a second skew process raises skew-aggregate throughput 272.0 → 382.5 steps/s
(**+40.6 %**) with each arm slowed 29.7 %, ample memory (797 MiB of 12227), no throttling, no Xid, no NVRM —
i.e. the authorized retention criteria pass as literally written. But **total throughput across all processes
rose only 5.6 %** (542.6 → 573.1 steps/s): ~73 % of the gain was taken from the repository owner's unrelated
concurrent thermal-mechanism campaign (−29.7 %), not extracted from idle capacity. Serial completion of the
full authorized tree is ≈ 11.4 h against a 20 h cap, so the ≈ 3.3 h saved was not needed. **The campaign runs
one skew arm at a time.**

GPU utilisation was flat at 53 % in both configurations and is **not** the diagnostic it appears to be: this
graph is kernel-launch-bound (CLAUDE.md: ~115–130 µs fixed host cost per launch, ~8000 launches/s ceiling,
~50 kernels/step), so `nvidia-smi` utilisation — the fraction of sampled time with ≥1 kernel resident, not SM
occupancy — pins at partial utilisation regardless. The steps/s ledger is what decides it.

Throughput used for planning: **≈ 272–277 steps/s** solo device-resident ⇒ ≈ 24–25 min per 100 ms arm.

---

## 5. Arm ledger

`RUN_LOGS/rigid/ARM_LEDGER.md` carries the authoritative per-arm state.

| block | planned | complete | failed | rerun | reused |
|---|---|---|---|---|---|
| 15° sanity (10 ms) | 2 | 2 | 0 | 0 | 0 |
| **15° anchor (100 ms)** | **8** | **8** | 0 | 0 | 0 |
| 15° borderline extension (105–108) | — | — | — | — | not triggered |

One arm was terminated during the campaign and is **not** in this ledger as a failure: `p_102` at ±15° was
killed 87 783 steps into its first attempt when the concurrency trial was reverted. `atpWrite` is temp-file +
atomic-rename, so it left no partial record and no stray `.tmp`; the arm was simply re-run from the serial
chain and its record is the one tabulated above. The kill produced the documented §5b teardown signature
(exit 134, `SIGSEGV` in `libcuda.so.1`, `hs_err_pid3350710.log`) — **not** a device fault: the shutdown hook
reached `SHUTDOWN_HOOK_COMPLETED`, and no Xid or NVRM message appeared.

---

## 6. Results

### 6.1 Arm inventory

Logs `RUN_LOGS/rigid/prod_15deg_s101.txt` (seed 101) and `prod_15deg_seeds101-104.txt` (the rest; seed 101
reused instantly). All eight arms ran to completion, every process exited 0.

| # | record | glide µm/s | Ω_fit rad/s | avgBound | invalid | solverFail | wall |
|---|---|---|---|---|---|---|---|
| 1 | `..._e0150_..._d00100000_p_101` | −0.389 | −237.25 | 23.88 | 0 | 0 | 1657.5 s |
| 2 | `..._n_101` | −0.265 | +397.09 | 19.94 | 0 | 0 | 1555.9 s |
| 3 | `..._p_102` | −0.382 | −3.74 | 25.44 | 0 | 0 | 1488.3 s |
| 4 | `..._p_103` | −0.370 | −24.83 | 24.21 | 0 | 0 | 1511.6 s |
| 5 | `..._p_104` | −0.354 | −270.85 | 34.02 | 0 | 0 | 1551.1 s |
| 6 | `..._n_102` | −0.262 | −67.46 | 28.79 | 0 | 0 | 1511.7 s |
| 7 | `..._n_103` | −0.393 | +135.02 | 25.77 | 0 | 0 | 1542.7 s |
| 8 | `..._n_104` | −0.377 | +133.00 | 31.29 | 0 | 0 | 1540.8 s |

Gliding is directed and negative (pointed-leading) in **all eight** arms (mean −0.349 µm/s); occupancy is
stable and usable (19.94–34.02 of 1200 heads); **zero invalid, zero solver failures, zero rate-cap warnings,
zero rupture events**.

Total anchor GPU wall-clock **12 359.6 s = 3.43 h** for 8 arms (mean 1545 s/arm ≈ 259 steps/s). Arms 1–2
overlap the concurrency trial and are correspondingly slower/noisier in wall-time; that affects scheduling
only, never the physics — the trial changed no model parameter, RNG key or record identity.

Note the raw per-arm `Ω_fit` values swing from −270.9 to +397.1 rad/s and do **not** track τ_odd: the realized
roll is Brownian-dominated in absolute terms (`D_roll = kT/γ_roll = 1278 rad²/s`, so free thermal roll alone
random-walks ≈ 13.8 rad ≈ 2.2 turns over the 75 ms window, an apparent ±185 rad/s). This is exactly why the
gate is τ_odd and why Ω enters only through the matched-pair odd combination, where the Brownian stream
cancels identically.

### 6.2 Primary — chirality-odd deterministic motor torque

```
tau_odd  = -4.557705e-22 +- 1.963490e-22 N.m   |m|/SEM 2.32   seed-sign 75%   95% t CI [-1.081e-21, +1.690e-22]
tau_even = -2.983098e-22 +- 1.661239e-22 N.m   |m|/SEM 1.80   seed-sign 75%
```

### 6.3 Secondary — motor-driven angular drift, via `M_roll = 3.084577e+23 rad/(s·N·m)`

```
Om_drive_odd  = -140.586 +- 60.565 rad/s   |m|/SEM 2.32   seed-sign 75%
Om_drive_even =  -92.016 +- 51.242 rad/s   |m|/SEM 1.80
```

**Mobility closure is exact**: `max_seed |Ω_drive_odd − M_roll·τ_odd| / |Ω_drive_odd| = 2.348e−11`.

### 6.4 Corroborating

```
Om_total_odd = -140.477 +- 60.526 rad/s    |m|/SEM 2.32
Om_Brown_odd =    0.000 exactly            (common-mode; cancels identically on matched seeds)
Om_geom_odd  = +1.0922e-01 +- 3.9722e-02 rad/s
```

`Om_Brown_odd = 0` to the bit, as required by the matched-pair construction — the ±ε arms at one seed draw a
bit-identical, state-independent counter-hash Brownian sequence. Hence
`Ω_total_odd = Ω_drive_odd + Ω_geom_odd` exactly, and the geometric term is 0.08 % of the drift.

### 6.5 Per-seed matched pairs

| seed | τ_det(+ε) | τ_det(−ε) | **τ_odd** | Ω_drive_odd | N_b(+ε) | N_b(−ε) |
|---|---|---|---|---|---|---|
| 101 | −1.365250e−21 | +2.097910e−22 | **−7.875204e−22** | −242.92 | 23.88 | 19.94 |
| 102 | −1.784168e−22 | −3.003231e−22 | **+6.095315e−23** | +18.80 | 25.44 | 28.79 |
| 103 | −2.156636e−22 | +5.108076e−22 | **−3.632356e−22** | −112.04 | 24.21 | 25.77 |
| 104 | −1.256991e−21 | +2.095674e−22 | **−7.332791e−22** | −226.19 | 34.03 | 31.29 |

Seed 102 carries the wrong sign and is 12× smaller than seed 101 — the per-seed scatter is large, exactly as
the 5° stage's diagnosis predicts.

### 6.6 Robustness

```
median  -5.482573e-22    MAD 2.121424e-22    SD 3.926979e-22
leave-one-out means:  -3.4519e-22  -6.2801e-22  -4.8662e-22  -3.6327e-22   -> native sign retained 4 of 4
largest-|seed| share of sum|tau_odd_s| = 40.5%
first-half seeds -3.6328e-22   vs  second-half seeds -5.4826e-22   (40.6% relative split)
```

**No single seed determines the sign** — every leave-one-out mean stays negative, spanning
−3.45e−22 to −6.28e−22. The median (−5.48e−22) is *more* negative than the mean, i.e. the mean is not being
manufactured by one outlier; if anything the wrong-signed seed 102 pulls the mean toward zero.

The largest single seed contributes 40.5 % of Σ|τ_odd|, above the 35 % dominance guard — but that guard is
specified for the 5° early-success rule, not for A15, and at n = 4 an even split is already 25 %. The
A15-relevant test is the leave-one-out behaviour, which passes 4 of 4.

### 6.7 Running ensemble estimate (fixed seed order 101, 102, 103, 104)

| n | mean τ_odd | SEM | \|m\|/SEM | sign % |
|---|---|---|---|---|
| 2 | −3.632836e−22 | 4.242368e−22 | 0.86 | 50 |
| 3 | −3.632676e−22 | 2.449332e−22 | 1.48 | 67 |
| 4 | **−4.557705e−22** | **1.963490e−22** | **2.32** | **75** |

The estimate strengthens monotonically with n, and the SEM shrinks roughly as 1/√n (4.24 → 2.45 → 1.96e−22
against the √n prediction 4.24 → 3.46 → 3.00e−22 from the n = 2 value — it shrinks *faster*, because the n = 2
SEM was an unreliable 1-df estimate). This is the opposite of the 5° stage's pathology, where doubling the
window *grew* the SEM 4.6×.

### 6.8 Components and populations

| quantity | ε-even | ε-odd |
|---|---|---|
| bond-force moment | −2.9831e−22 N·m | −4.5577e−22 N·m |
| any other deterministic contribution | +1.57e−30 N·m | −3.05e−30 N·m |
| τ per bound head | −1.0429e−23 N·m | −1.7080e−23 N·m (2.30σ) |
| positive-torque head population | +7.7338e−20 N·m | +5.93e−22 (0.27σ) |
| negative-torque head population | −7.7636e−20 N·m | −1.05e−21 (0.44σ) |
| mean occupancy N_b | +26.669 | **+0.218 ± 0.865 (0.25σ)** |
| gliding velocity | −0.34908 µm/s | −0.02471 ± 0.02092 µm/s (1.18σ) |

**The bond-force moment is the entire deterministic axial torque.** Torque-component closure over all eight
arms: `max |τ_det − τ_bond − τ_other| / |τ_det| = 3.249e−11`. There is no fourth contribution and no missing
channel — as preregistered, at n = 1 segment there is no chain torsion and z-confinement enters `forceSum`
only. `τ_other` is ~1e−8 of `τ_det`, i.e. **seven orders below the signal**; its nominally large σ (42) is a
statement about how *reproducibly tiny* it is, not about physical relevance.

**The signed head populations are ~170× larger than their net** (+7.73e−20 and −7.76e−20 against −2.98e−22;
net/gross = 0.49 %). The net axial torque remains a small residue of a near-cancelling tug-of-war between
heads rolling the filament in opposite senses. That is the variance source, and it is physical.

### 6.9 Stationarity

Disjoint quarter-blocks of each arm's 75 ms measured window (diagnostic only — **not** independent replicates):

| | Q1 | Q2 | Q3 | Q4 |
|---|---|---|---|---|
| τ_odd per block | −4.4583e−22 | −6.1363e−22 | −3.1347e−22 | −4.5015e−22 |

```
pooled 16 disjoint blocks: -4.557705e-22 +- 1.517583e-22   |m|/SEM 3.00   block-sign 62%
first half  -5.297339e-22 +- 2.414695e-22   |m|/SEM 2.19   sign 75%
second half -3.818071e-22 +- 1.562695e-22   |m|/SEM 2.44   sign 75%
```

**All four quarter-blocks carry the native negative sign**, and both halves agree in sign and magnitude
(−5.30e−22 vs −3.82e−22). This is the decisive qualitative contrast with the 5° stage: there, the block
estimate *undercut* the seed estimate (2.43σ → 1.01σ) and the whole second half reversed; here the block
estimate **corroborates and strengthens** it (2.32σ → 3.00σ) with no half-window reversal. Blocks are not
replicates and the 3.00σ is not quoted as the result — but the *direction* of the disagreement is
informative, and at 5° it was the warning that proved correct.

---

## 7. A15 / F15 adjudication — **A15 PASS**

| # | A15 condition | status |
|---|---|---|
| 1 | ≥ 3 of 4 matched seeds carry the native sign | **PASS** — 3 of 4 negative (seed 102 positive) |
| 2 | four-seed mean τ_odd has the native sign | **PASS** — −4.558e−22 N·m |
| 3 | magnitude clearly above the decomposition/numerical floor | **PASS** — signal 4.56e−22 vs τ_other ≈ 3e−30, seven orders |
| 4 | not explained by systematic occupancy difference between ±15° | **PASS** — occupancy odd +0.218 ± 0.865 N_b, **0.25σ**, unresolved |
| 5 | Ω_drive_odd has the corresponding sign and exact mobility closure | **PASS** — −140.6 rad/s, closure 2.348e−11 |
| 6 | torque components close | **PASS** — 3.249e−11 over all arms |
| 7 | no invalid or solver failures | **PASS** — 0 / 0 across all eight arms |
| 8 | no single seed determines the sign of the leave-one-out mean | **PASS** — all four LOO means negative |
| — | *preferred strength* \|mean\|/SEM ≥ 2 | **MET — 2.32** |

**Borderline extension (seeds 105–108): NOT triggered.** It fires only if `|m|/SEM < 2` or a leave-one-out
mean becomes unstable. Neither holds.

**F15 is not met on any limb**: the mean is not near zero, signs are not random (3 of 4 plus all four blocks),
the mean sign is the established native one, leave-one-out means are stable, decomposition and structural
validation pass, and no arm was invalid or showed an occupancy asymmetry.

**Consequence: the high-seed ±5° stage is justified and was launched immediately.**

### 7.1 What this does and does not establish

It establishes that **this estimator, on this rigid FDT pipeline, resolves a chirality-odd motor torque at a
skew where one is known to exist** — the power calibration the 5° null could not supply. It does **not**
establish statistical significance at n = 4: the 95 % t interval contains zero, and the honest strength is
2.32σ on four matched seeds with one seed of the wrong sign.

---

## 8. Cross-model comparison with the legacy flexible 15° result

Descriptive only. Equality is **not** required and is not claimed.

| | legacy flexible campaign | **rigid, this work** |
|---|---|---|
| τ_odd | ≈ −2.7e−22 N·m | **−4.558e−22 ± 1.963e−22 N·m** |
| Ω_odd | ≈ −91 rad/s | **−140.6 ± 60.6 rad/s** (Ω_drive_odd) |
| sign | negative (native), mirror-reversed | negative (native) |

Same sign, same order of magnitude, rigid larger by ≈ 1.7× — well inside the rigid measurement's own
uncertainty. Per the standing interpretive rule this is a **cross-model descriptive comparison**: the rigid
model removes internal bending, segment material roll, end-only rotational forcing and non-uniform effective
temperature *by construction, not by test*, so the legacy flexible numbers are neither corrected nor retracted
by this result.

### 8.1 What the anchor predicts for 5°

Proportional scaling from this anchor gives a 5° expectation of
`−4.558e−22 × (5/15)` ≈ **−1.52e−22 N·m** (the sine prediction, ×`sin5°/sin15°` = 0.337, gives −1.54e−22 —
the two are **not separable** at this variance, as recorded previously: over 1–5°, `sin(ε)/ε` varies by 0.13 %).

That predicted magnitude is **larger** than both the 5° 100 ms point estimate (−6.99e−23) and the 200 ms
bound (|τ_odd| ≲ 1.3e−22). So the anchor does not merely license the 5° stage — it sharpens what that stage
must distinguish.

**Power projection, stated with its uncertainty.** The between-seed SD here is 3.93e−22 N·m. If the 5° SD were
comparable (plausible, since the noise source — the achiral tug-of-war imbalance — is largely ε-independent),
then SEM at n = 12 would be ≈ 1.13e−22 and `|m|/SEM` ≈ 1.3, i.e. **12 seeds might not resolve 5°**. If instead
the 5° scatter is genuinely smaller (the 2-seed 100 ms estimate suggested SD ≈ 4e−23, but that is a 1-df
number and unreliable), 12 seeds would resolve it comfortably. The honest position is that the projection
spans both outcomes, which is precisely why the authorized design runs the seeds rather than predicting them.

