# Low-[ATP] Gliding Occupancy versus Motor Density

**Bounded exploratory screen.** Places the low-[ATP] SoftBox gliding assay on the occupancy axis established
by the completed Vilfan target-zone ladder. It is *not* a second publication-grade density campaign.

- **Date:** 2026-07-30 · branch `gpu-mat-bottlenecks-explicit-singlehead`
- **Owning report for the low-[ATP] arc:** `docs/twirling/LOW_ATP_GLIDING_TWIRLING_FINDINGS.md` (this file is a
  focused companion; it does not restate that report's audit, validation or ladder)
- **Vilfan reference, NOT rerun or modified:** `docs/VILFAN_TARGET_ZONE_BINDING_AND_TWIRLING_FINDINGS.md`
- **Raw records:** `RUN_LOGS/chiral_sites/lowatp/atpden_r*.tsv` (+ `.nbhist.tsv`, `.nested.tsv`, `.trace.tsv`)
- **Analysis outputs:** `RUN_LOGS/lowatp/density_occupancy_10uM_200ms.{txt,csv}`, `density_twirl_perhead.{txt,csv,png}`, `density_*.png`
- **Analysis scripts:** `scripts/lowatp_density_analysis.py`, `scripts/lowatp_density_twirl_analysis.py`
- **Driver logs:** `RUN_LOGS/lowatp/density_phase{1,2,3_twirl}.txt`

---

## 1. Question

At the 10 µM ATP condition of the classic myosin-II twirling assay, how does simultaneous bound-head
occupancy depend on motor density, and specifically:

> **Does healthy low-ATP gliding persist at motor densities where occupancy falls below the Vilfan
> target-zone depletion threshold?**

**Answer (§7.2): D1.** Gliding is sustained — net-directed and continuously attached — at mean N_b = **9.85**
(100 heads/µm², 4/4 arms forward) and **5.28** (50 heads/µm², net-directed but 1 of 4 field realizations fails),
both clearly below the Vilfan weak-depletion bracket of 13.8. Occupancy scales as **N_b ∝ ρ^0.768 ± 0.034**,
carried entirely by attachment flux with residence time density-invariant; **P(N_b = 0) = 0 everywhere**.

**And the follow-on question (§8): our own mechanism does NOT need a crowd.** The ε-odd chiral torque is
resolved at every density with the correct sign in **12 of 12 matched pairs**, and **τ_odd/N_b is statistically
invariant** across a 5.8× occupancy cut (pooled regression t = −1.29, n.s.). The skewed-converter mechanism is
**per-head**, still at full per-head strength at N_b ≈ 5 — so a collective mechanism can be faulted for
requiring a large bound population without inconsistency. Note however that **low occupancy is a worse place to
*observe* twirling** (turns per µm falls ~3×), even though it is where the mechanism is best demonstrated.

---

## 2. Exact inherited configuration

The **only** production variable is the motor-head surface density. Everything below is inherited verbatim
from the low-[ATP] 400 heads/µm² ladder and was read from source, not assumed.

### 2.1 Motor, chemistry and solvent

| quantity | value | where |
|---|---|---|
| assay | explicit-S2 / discrete-site / linear converter ramp gliding | `ChiralSiteHarness` → `ExplicitCompleteMatHarness.buildGlidingGraph` |
| motor model | explicit S2 beam, homogeneous free length **L = 40 nm** | `buildS2Mat(DENSITY, DTR, 40.0, …)` |
| S2 beam discretization | `l0 = 10 nm` (M = 4 segments), `EA = 4.2e-9 N`, `EI = 7.2e-28 N·m²`, node drag radius 5 nm | `EXP4G_L0_NM`, `EXP4G_EA_SI`, `EXP4G_EI_SI`, `EXP4G_RNODE_NM` |
| initial S2 sag | 1.5 nm | `EXPLICIT_GLIDE_SLACK_NM` |
| chemistry kernel | `NucleotideCycleSystem.cycleLymnTaylor` (identical on both runners) | one `chem` task |
| **[ATP]** | **10 µM** ⇒ `atpOn` (NONE→ATP) = **100 s⁻¹** | `atpOn = 2.0e4 s⁻¹ × ([ATP]/2000 µM)` |
| other rates | hydrolysis 100 s⁻¹ (on/off filament), Pi release 1.0e4 s⁻¹ (on) / 0 (off), ADP release 1.0e3 s⁻¹ × g(F) | `nucParams[2..7]` |
| catch–slip | `kOff = 100 s⁻¹`, `alphaCatch = 0.92`, `alphaSlip = 0.08`, **`xCatch = 2.5 nm`**, `xSlip = 0.4 nm` | `MotorStore.setKinParams` |
| rigor mechanical rupture | **OFF** (`RUPTURE_MODE = 0`, `RIGOR_ON = false`) ⇒ **ATP binding is the sole detachment pathway** | `ChiralSiteHarness` never sets `RUPTURE_MODE` |
| solvent viscosity | **η = 0.01 Pa·s** (the low-η operating point of the viscosity campaign) | `-eta 0.01` |
| timestep | `dt = DT·η/aeta = 2.5e-7 s` (the mechanically scaled dt) | `atpSetDuration` |

### 2.2 Filament, lawn and geometry

| quantity | value | where |
|---|---|---|
| filament | 12 segments × 64 monomers, contour ≈ **2.11 µm**, semiflexible chain (Lp ≈ 17 µm) | `G4_NSEG`, `G4_MONO` |
| filament Brownian | **ON**, all four channels | `setBrownianPolicy(true,…)` |
| surface confinement | z-only, 2.0 pN/nm (the coverslip normal) | `G4_KZ` |
| mat footprint | **3.0 × 1.0 µm = 3.0 µm²** | `G4_MATX`, `G4_MATY` |
| motors on the mat | `round(density × 3.0)` | `g4NMot` |
| broad-phase query radius | 0.080 µm (= 0.030 + L + 0.010) | `buildS2Mat` |
| binding gate | `d < 3.0 nm`, ψ < 25°, φ < 25°, θ < 20°, preload < 2.0 pN, energy < 15 kT | `Tol` defaults |
| segment ownership | **canonical half-open**, machine-ε tolerance (`LEGACY_OWNERSHIP = false`) | `bindMargin`, `nearestSeg2D` |
| converter skew ε | **+15°, single sign** | `ATP_EPS_DEG` |
| lattice | native (not mirrored) | `ATP_MIRROR = +1` |

### 2.3 Window

| quantity | value |
|---|---|
| physical duration per arm | **200 ms** (800,000 steps at dt = 2.5e-7 s) |
| warm-up discarded | **25 % = 50 ms** (`EQUIL_FRAC`) |
| **analysed window** | **150 ms** |
| measurement blocks | 5 (`NBLK`); dense trace sampled every 50 µs |

> **Window-length note — the brief's "warm-up 5–10 s, analysed 20–30 s" cannot be honoured, and should not be.**
> This assay's arms are *milliseconds* of physical time: 200 ms already costs ≈ 40–55 min of GPU wall-clock per
> arm at 800,000 steps. A 20–30 s analysed window would be ~150× longer — roughly **four days per arm**, ~100×
> over the study's own 10 h cap. The 200 ms / 150 ms window used here is not a shortcut: it is the duration
> **chosen by the low-[ATP] study's own preregistered duration pilot** (six gates on episode count, directed
> displacement, final-half stability, nested-window agreement, startup transient and stationarity) for exactly
> these observables. See `LOW_ATP_GLIDING_TWIRLING_FINDINGS.md` §Stage 2.

---

## 3. What was added to the code, and proof it changed nothing

Two additive changes to `softbox/ChiralSiteHarness.java`; no kernel, no force law, no rate, no scene parameter.

1. **`P(N_b)` instrumentation.** A per-measurement-step histogram of the instantaneous bound-head count `nb`
   — the exact quantity `avgBound` is the mean of. `nb` is already computed by the measurement loop; the
   histogram only reads it. Nothing is written back into the simulation and no RNG stream is touched. It is
   dumped to a `*.nbhist.tsv` sidecar so any binning can be derived offline without a rerun.
2. **`-atp-density-map` mode with a density-tagged record namespace.** The original `atpId` did **not** tag
   density, so a density ladder written into it would have silently reused or collided with the completed
   400 heads/µm² records. The new arms live in a separate `atpden_r<density>_…` namespace, which leaves the
   completed ladder byte-untouched and makes the study's own 400 anchor a genuine fresh run under the current
   instrumentation. (`-density-points`, `-atp-density-report`.)

**Inertness demonstrated, not asserted** — the discipline used for the per-head instrumentation at
`7eb9104`. Two short arms (5 ms, 400 heads/µm², 10 µM, seeds matched) were run on the changed build, then the
change was stashed, the pre-change binary rebuilt, and the same arms rerun:

| check | result |
|---|---|
| pre-existing record fields | **108 / 108 byte-identical** on both arms (7 new fields are additive) |
| `*.trace.tsv` dense trace | **byte-identical** |
| `*.nested.tsv` prefix readout | **byte-identical** |
| histogram consistency | mean of `nbHist` = **35.216067** vs the record's `avgBound` = 35.216 — the same number |

Records: `RUN_LOGS/chiral_sites/lowatp/atp_u0010.00_d00005000_{p,n}_101.*`.

One further change, in `scripts/run_lowatp_campaign.sh`: the record counter now also excludes `*.nbhist.tsv`,
so the driver's stall detection still counts one record per arm.

`BoA-v1ref` untouched. No default changed: `-atp-density-map` is off unless asked for, and every other
harness path is byte-unchanged.

---

## 4. Provenance of the 400 heads/µm² anchor — and why it was rerun

The brief allows reusing the existing 400 heads/µm² record if it is configuration-compatible. It is — but it
predates the `P(N_b)` histogram, which is the study's central new observable, so 400 was rerun as an anchor arm
under the new namespace at seed 101, +ε, identical in every other respect.

That rerun doubles as the **D5 configuration-mismatch test, and it excludes D5 decisively.** Comparing the
fresh anchor `atpden_r0400.0_u0010.00_d00200000_p_101` against the existing
`atp_u0010.00_d00200000_p_101` (written at rev `f19749a7`, this study at rev `a5e6400a`):

| check | result |
|---|---|
| shared record fields | **80 / 81 byte-identical** |
| `*.trace.tsv` (dense 50 µs trajectory) | **byte-identical** |
| `*.nested.tsv` (nested-window prefix readout) | **byte-identical** |
| the single differing field | `qOmega`: −9.0704 (old) vs −108.845 (new) = **exactly ×12** |

The one difference is `qOmega`, a **diagnostic-only** field, and the factor is exactly `nSeg = 12` — i.e. it is
precisely the whole-filament roll-drag correction documented on 2026-07-28 ("records written before this fix
carry `qOmega` LOW BY A FACTOR nSeg"). No claim in this study or the low-[ATP] study uses `qOmega`.

**⇒ The existing low-[ATP] 400-density records are fully comparable to the current model and harness.** The
trajectory itself is bit-reproducible across three weeks of repository history.

### 4.1 The 400-density occupancy baseline, and its seed spread

The existing four-seed ladder at 10 µM, +ε only:

| seed | 101 | 102 | 103 | 104 | mean | SD |
|---|---|---|---|---|---|---|
| mean N_b | 22.56 | 34.94 | 29.52 | 34.35 | **30.34** | **5.66 (18.7 %)** |
| glide (µm/s) | −0.301 | −0.229 | −0.279 | −0.318 | −0.281 | 0.038 |

Two consequences, both load-bearing for how this screen is read:

- The brief's "N_b ≈ 29.0 at 400" is confirmed (ensemble 30.3 ± 5.7 over four seeds, or 30.8 over both ε signs).
- **Seed-to-seed spread at fixed density is ~19 %**, which is larger than several of the density-to-density
  contrasts would be if occupancy were near-linear. The ladder is therefore run **seed-matched at seed 101**, and
  density scaling is read as a *paired ratio against the seed-101 anchor* rather than against the ensemble mean.
  Seed 101 is the low member of the four, so its absolute occupancies run ~26 % below the ensemble; the
  Vilfan-bracket comparison in §7 states both.

---

## 5. Arm inventory and runtime

All arms **GPU device-resident** (`buildGlidingGraph`, single TaskGraph, `-Dtornado.recover.bailout=false` so a
lowering failure throws — no silent CPU fallback), launched through `scripts/run_gpu_monitored.sh` with the
external crash recorder verified running, wrapped in the crash-resilient campaign driver.

| phase | ρ (heads/µm²) | seed | ε | arms | wall-clock |
|---|---|---|---|---|---|
| 1 (anchor) | 400 | 101 | +15° | 1 | 3032 s |
| 1 | 200 | 101 | +15° | 1 | 2767 s |
| 1 | 100 | 101 | +15° | 1 | 2794 s |
| 1 | 50 | 101 | +15° | 1 | 2868 s |
| 2 (replicate) | 200 | 102 | +15° | 1 | 2794 s |
| 2 (replicate) | 100 | 102 | +15° | 1 | 2861 s |
| 2 (replicate) | 50 | 102 | +15° | 1 | 3162 s |
| 3 (paired ε) | 100 | 101–104 | ±15° | 6 new | ~4.9 h |
| 3 (paired ε) | 50 | 101–104 | ±15° | 6 new | ~5.0 h |

**7 new production arms for the density screen**, plus **12 further arms for the §8 chiral test** (a scope
extension requested explicitly after the screen's result was in hand — see §8). The screen itself went one over
the brief's ceiling of 6, and the reason is recorded here rather than buried: 400 was **not** replicated because it already carries four independent seeds in the completed ladder
(§4.1), which is stronger replication than a fifth arm would add, so all three replicate slots went to the
densities that actually decide the question. Phase 1 ≈ **3.2 h**; Phase 2 ≈ 2.5 h; **screen total ≈ 5.7 h**, inside the 6 h target. Phase 3 (§8) added
≈ 9.9 h, taking the whole task to **≈ 15.6 h — past the brief's 10 h cap, by explicit request**. One driver
attempt per phase, no retries, no crashes, no faults of any kind.

**Note on throughput.** Arms ran at 252–280 steps/s against 343 steps/s for the historical n = 4 campaign. This
was **contention with a concurrent unrelated GPU session** on the same device (a `-ratchet-campaign` run), not
thermal state, and it affects wall-clock only — the RNG is counter-based and seeded, so contention cannot
change arithmetic (confirmed by the §4 byte-identical reproduction).

Throughput was measured to be **density-independent** (35.3 s at 1200 motors vs 31.2 s at 150 motors for 8000
steps) — the launch-bound regime `CLAUDE.md` documents — so a thinner lawn buys no wall-clock.

**Health across all Phase-1 arms: 0 invalid states, 0 solver failures, 0 rate-cap warnings, 0 rigor ruptures,
0 unclassified detachments; ATP binding accounts for 100 % of detachments at every density.**

---

## 6. Occupancy and gliding

### 6.1 The ladder (7 arms; +ε; 150 ms analysed)

| ρ | motors on mat | motors within reach* | seed | mean N_b | median | SD | v (µm/s) | class |
|---|---|---|---|---|---|---|---|---|
| 400 | 1200 | ≈135 | 101 | **22.56** | 22 | 3.54 | −0.301 | G2 |
| 200 | 600 | ≈68 | 101 | **14.61** | 15 | 2.72 | −0.408 | G1 |
| 200 | 600 | ≈68 | 102 | **19.02** | 19 | 3.07 | −0.470 | G2 |
| 100 | 300 | ≈34 | 101 | **8.09** | 8 | 2.01 | −0.260 | G2 |
| 100 | 300 | ≈34 | 102 | **11.78** | 12 | 2.57 | −0.378 | G2 |
| 50 | 150 | ≈17 | 101 | **4.58** | 5 | 1.32 | −0.203 | G2 |
| 50 | 150 | ≈17 | 102 | **6.46** | 6 | 1.70 | −0.222 | G2 |

\* geometric estimate: anchors within the harness's own 0.080 µm broad-phase query radius of the 2.11 µm
contour. The records do not store an exact reachable count, so this is an estimate, labelled as one.

The completed ladder supplies the 400 point at n = 4 (+ε): N_b = 30.34 ± 2.86, v = −0.2815 ± 0.0194.

**Extended replication (from §8's campaign).** The §8 paired-ε arms include +ε arms at seeds 103–104, which are
valid occupancy/gliding data on identical configuration and take the screen to **n = 4 at both 100 and 50**:

| ρ | seed | N_b | v (µm/s) | |
|---|---|---|---|---|
| 100 | 103 | 9.57 | −0.196 | forward |
| 100 | 104 | 10.82 | −0.325 | forward |
| 50 | 103 | 4.39 | −0.269 | forward |
| 50 | 104 | 3.91 | **+0.060** | **not forward** |

At n = 4: **100 heads/µm² gives N_b = 8.09–11.78, v = −0.290 ± 0.040, 4/4 forward.** **50 heads/µm² gives
N_b = 3.91–6.46, v = −0.159 ± 0.074, 3/4 forward** — see §6.5a.

### 6.2 P(N_b) — the distribution, not just its mean

| ρ | seed | P(0) | P(1) | P(2) | P(3–5) | P(6–10) | P(11–20) | P(>20) | **P(≤2)** |
|---|---|---|---|---|---|---|---|---|---|
| 400 | 101 | 0 | 0 | 0 | 0 | 0 | 0.288 | 0.712 | **0** |
| 200 | 101 | 0 | 0 | 0 | 0 | 0.081 | 0.919 | 0 | **0** |
| 200 | 102 | 0 | 0 | 0 | 0 | 0 | 0.693 | 0.307 | **0** |
| 100 | 101 | 0 | 0 | 0 | 0.109 | 0.778 | 0.113 | 0 | **0** |
| 100 | 102 | 0 | 0 | 0 | 0.002 | 0.326 | 0.673 | 0 | **0** |
| 50 | 101 | 0 | 0.011 | 0.054 | 0.677 | 0.258 | 0 | 0 | **0.066** |
| 50 | 102 | 0 | 0 | 0.008 | 0.253 | 0.732 | 0.008 | 0 | **0.008** |

**P(N_b = 0) = 0 at every density and every seed.** Over 600,000 measurement steps per arm the filament is
never once fully detached, even at 50 heads/µm² with ~17 motors within reach. The distributions are unimodal
and narrow (`SD/mean` 0.16 at 400 rising only to 0.29 at 50); thinning the lawn 8× **shifts** the occupancy
distribution without breaking it up or opening a detached tail. Weak-attachment time appears only at
50 heads/µm², and only at 0.8–6.6 %.

A structural aside worth recording: occupancy is **sub-Poissonian at every density** — the measured SD is
0.62–0.75× √N_b throughout. Bound heads are negatively correlated rather than binding independently, which is
what exclusion/competition looks like and is consistent with §6.4.

### 6.3 Occupancy is sub-proportional to density — N_b ∝ ρ^0.77

Read as **seed-paired ratios against that seed's own 400 arm** (the correct comparison, given the 19 % seed
spread of §4.1):

| ρ | seed | N_b/N_b(400) | ρ/400 | local exponent |
|---|---|---|---|---|
| 200 | 101 | 0.648 | 0.500 | 0.627 |
| 200 | 102 | 0.545 | 0.500 | 0.877 |
| 100 | 101 | 0.359 | 0.250 | 0.740 |
| 100 | 102 | 0.337 | 0.250 | 0.784 |
| 50 | 101 | 0.203 | 0.125 | 0.767 |
| 50 | 102 | 0.185 | 0.125 | 0.812 |

**Exponent over all six seed-paired contrasts: 0.768 ± 0.034 (SD 0.083, n = 6).** An 8× density cut buys only
a ~5× occupancy cut. `N_b(ρ) ≈ N_b(400)·ρ/400` is **not** a good description and the brief was right not to
assume it. The single-contrast exponents scatter (0.63–0.88); the pooled value is tight.

### 6.4 Where the deviation comes from — flux, not residence

`N_b = attachment flux × mean residence` closes to within ~1 % at every arm, so this is a decomposition, not a
fit:

| ρ | seed | attach /s | attach /s **per motor** | residence (ms) | flux × residence | measured N_b |
|---|---|---|---|---|---|---|
| 400 | 101 | 2027 | 1.69 | 11.21 | 22.71 | 22.56 |
| 200 | 101 | 1353 | 2.26 | 10.90 | 14.76 | 14.61 |
| 200 | 102 | 1747 | 2.91 | 10.77 | 18.81 | 19.02 |
| 100 | 101 | 647 | 2.16 | 12.51 | 8.09 | 8.09 |
| 100 | 102 | 1173 | 3.91 | 9.93 | 11.65 | 11.78 |
| 50 | 101 | 380 | 2.53 | 11.83 | 4.50 | 4.58 |
| 50 | 102 | 580 | 3.87 | 11.41 | 6.62 | 6.46 |

- **Residence time is density-invariant** (9.9–12.5 ms, no trend with ρ). This is a consistency check on the
  physics rather than a discovery: detachment on this path is 100 % ATP-triggered at a fixed 100 s⁻¹ hazard, so
  it *should* be blind to how many neighbours a head has, and it is.
- **The entire deviation from proportionality sits in attachment flux per motor, which rises as the lawn
  thins.** Equivalently, each motor is recruited more often when it has less company.
- **Cause: not separated by this screen.** Two readings fit: (a) binding competition — discrete actin sites are
  exclusive on this path, so at high density a head that reaches the filament more often finds its target
  already taken (the sub-Poissonian statistics of §6.2 are independent support); (b) a load-dependent change in
  how the filament rides above the lawn. Distinguishing them needs a control this study did not run. **The
  measurement stands; the mechanism is stated as untested.**

### 6.5 Velocity has a genuine optimum near 200 heads/µm²

| ρ | n | v mean (µm/s) | SD | values |
|---|---|---|---|---|
| 400 | 4 | −0.2815 | 0.0387 | −0.301, −0.229, −0.279, −0.318 |
| 200 | 2 | **−0.4394** | 0.0438 | −0.408, −0.470 |
| 100 | 2 | −0.3193 | 0.0833 | −0.260, −0.378 |
| 50 | 2 | −0.2125 | 0.0133 | −0.203, −0.222 |

**200 vs 400: −0.158 ± 0.037 µm/s, 4.32σ (Welch).** Both replicate seeds at 200 fall outside the entire range
of the four 400 arms, so **halving the motor density makes the filament glide ~1.6× FASTER**, and this is not
a sampling artifact. Below 200 velocity falls again (200 → 100 is −0.120 ± 0.066, 1.8σ — suggestive only;
200 → 50 is unambiguous). The optimum is consistent with a tug-of-war reading — surplus motors at high density
act as drag as often as propulsion — but this screen does not decompose propulsive versus opposing force, so
that remains an interpretation, not a result.

**Correction to an interim statement.** At n = 1 per density this non-monotonicity was reported as *not*
established, because the 100-vs-200 gap was then smaller than the within-arm half-to-half variation. With the
replicates in hand, the 200-vs-400 leg is solid at 4.32σ; only the 200-vs-100 leg remains suggestive.

### 6.5a One field realization at 50 heads/µm² does not glide

At n = 4, the **lowest-occupancy realization in the entire study (seed 104, N_b = 3.91) has v = +0.060 µm/s** —
no net forward travel over 150 ms, class **G4** by this study's own rule. It is the only such arm out of 11
single-ε arms across the whole ladder.

Two readings, and both belong in the record:

- **As a single-ε arm** — the configuration a physical assay actually realizes, since converter skew is a fixed
  property of the motor, not something nature averages over — **1 of 4 lawn realizations at 50 heads/µm² fails
  to glide.**
- **As the symmetrized ε-EVEN estimator** (§8 supplies the −ε partners), **all four seeds at 50 heads/µm² glide
  forward** and tightly: −0.194, −0.280, −0.254, −0.263, mean **−0.248 ± 0.019 µm/s**. Seed 104's +ε arm
  (+0.060) is paired with a −ε arm at −0.586, so its even component is solidly forward.

The single-ε arm-to-arm scatter at 50 is large (SD 0.148 µm/s), so the non-gliding arm sits **within the noise
of a distribution whose mean is clearly forward** — it is most simply read as a fluctuation of a forward-gliding
process, not a distinct failure mode. But at n = 4 the onset of genuine intermittency cannot be excluded, and
the honest boundary statement is: **gliding is robust at N_b ≈ 10 and becomes realization-dependent by
N_b ≈ 4.** An earlier interim statement in this task ("no floor in sight down to 50 heads/µm²") was made before
seeds 103–104 existed and is superseded by this paragraph.

### 6.6 Believability, and what the 150 ms window does and does not support

| ρ | seed | v full | v 1st half | v 2nd half | N_b 1st | N_b 2nd | 15 ms-block CV | blocks not net-forward |
|---|---|---|---|---|---|---|---|---|
| 400 | 101 | −0.301 | −0.302 | −0.293 | 21.5 | 23.6 | 0.57 | 0/10 |
| 200 | 101 | −0.408 | −0.349 | −0.497 | 15.0 | 14.2 | 0.48 | 0/10 |
| 200 | 102 | −0.470 | −0.656 | −0.269 | 18.6 | 19.5 | 0.66 | 0/10 |
| 100 | 101 | −0.260 | −0.394 | −0.135 | 7.6 | 8.5 | 0.89 | 1/10 |
| 100 | 102 | −0.378 | −0.258 | −0.485 | 12.6 | 11.0 | 0.59 | 0/10 |
| 50 | 101 | −0.203 | −0.216 | −0.201 | 4.0 | 5.1 | 2.58 | 2/10 |
| 50 | 102 | −0.222 | −0.359 | −0.098 | 6.1 | 6.8 | 1.20 | 1/10 |

Against the brief's believability criteria:

- **First/second-half agreement — met for occupancy, not for velocity.** Occupancy halves agree within ~13 % in
  every arm; velocity halves differ by up to 3.7×. Since the primary observable is occupancy this is the right
  side of the trade, but per-arm velocity should be read as ±30 % at this window.
- **Histogram stability — met.** Unimodal, narrow, no bimodality or heavy tail at any density.
- **No single long detached interval dominates the mean** — P(N_b = 0) is exactly zero everywhere.
- **Replicate gives the same qualitative classification — met.** At 200, 100 and 50 both seeds land in the same
  class (G2 at 100 and 50; G1/G2 at 200, a boundary case discussed below).
- **Not an artifact of field truncation** — the filament (2.11 µm) sits inside the 3.0 × 1.0 µm mat at all
  times and motors are present across it at every density.

**Fraction of single-ε arms failing to sustain gliding: 1 / 11** (§6.5a; 0/7 within the screen itself, the one failure appearing only at n = 4 at the lowest density). Every arm is net-directed in the pointed-leading (−x)
direction, the sign convention indicating correct transport.

*Event-weighted mean occupancy (brief observable 10) is not available — the records carry no per-episode
occupancy weighting, and adding it was out of scope for an exploratory screen.*

### 6.7 The G1–G4 classification, and an honest note on its top boundary

Classification is taken from trajectory **and** occupancy, not velocity alone: G1 = every block net-forward,
stable block velocity, detached time < 1 %; G2 = net-directed with ≤ 25 % of blocks stalled or backward;
G3 = ≤ 50 %; G4 = no net directed travel. Blocks are 15 ms (one tenth of the analysed window).

The 400 arm scores G2 rather than G1 **only** because its block CV is 0.57 against a 0.5 cut, while 200/101
scores 0.48 — a hair on a threshold chosen for this analysis, and the labels do **not** meaningfully separate
them (both have 0/10 blocks stalled or backward). The gradation that *is* real is at the bottom: 0, 0, 1 and 2
blocks out of 10 not net-forward as density falls 400 → 50, i.e. transport gets choppier but never reverses.
Read the raw statistics in §6.6, not the label.

---

## 7. Vilfan overlay and decision

### 7.1 Overlay

Exploratory brackets from the completed target-zone ladder — weak/near-zero depletion through N_b ≈ 13.8,
crossover 13.8–29, operational point ≈ 29. **These are read off a different model and are not thresholds of
this assay.**

| ρ | mean N_b (this study) | seeds | position vs the brackets | gliding |
|---|---|---|---|---|
| 400 | 30.34 (n = 4, completed ladder) / 22.56 (seed 101) | — | at / just below the **operational point** | G2 |
| 200 | 16.82 (14.61, 19.02) | 101, 102 | inside the **crossover** | G1 / G2 |
| 100 | **9.94** (8.09, 11.78) | 101, 102 | **BELOW the weak-depletion bracket** | G2, G2 |
| 50 | **5.52** (4.58, 6.46) | 101, 102 | **BELOW the weak-depletion bracket** | G2, G2 |

Both seeds at both low densities land below 13.8; no seed at 100 or 50 reaches the bracket.

### 7.2 Decision: **D1 — low-density gliding below the Vilfan threshold**

At **100 heads/µm²**, gliding remains **G2 — net-directed and continuously attached — at n = 4 with 4/4 arms
forward** (v = −0.290 ± 0.040 µm/s, 1.03× the 400 anchor), while mean occupancy is **9.85**, clearly below the
13.8 weak-depletion bracket. This density alone establishes D1 and does so unambiguously.

At **50 heads/µm²** (N_b = 5.28) gliding is still net-directed on the ε-even estimator at 4/4 seeds
(−0.248 ± 0.019 µm/s), but **1 of 4 single-ε realizations fails to glide** (§6.5a). D1 holds here too, with the
qualification that this density sits at the onset of realization-dependence rather than comfortably inside the
gliding regime.

**Interpretation, in the brief's own words:** the experimental low-ATP assay could support gliding, and
potentially skew-driven twirling, in a regime where Vilfan depletion is weak or absent. This supports **T3**
(Vilfan as a *general* explanation is not required for gliding at these conditions) over T2.

**Competing decisions, and why they are rejected:**

- **D2** (gliding requires or produces N_b in 14–29) — rejected: gliding is sustained at N_b ≈ 9.9 with 4/4
  arms forward and full anchor velocity, a factor 1.4 below the bracket; and still net-directed at N_b ≈ 5.3.
- **D3** (gliding requires N_b ≳ 29) — rejected outright: the *fastest* gliding in the whole study occurs at
  N_b ≈ 17, well below 29, and the anchor at N_b ≈ 30 is slower.
- **D4** (field/sampling variation dominates) — rejected, with a caveat. Seed spread is real and substantial
  (~19 % at 400; seed 102 runs 1.3–1.5× above seed 101 throughout, since the seed sets both the lawn
  realization and the thermal trajectory), but it is *smaller than the effect*: both seeds at 100 and 50 give
  the same classification and the same side of the bracket, and the seed-paired exponent is tight
  (0.768 ± 0.034). Two caveats: **200 heads/µm² straddles the bracket** (14.61 vs 19.02) and should not be
  assigned to either regime at n = 2; and at **50 heads/µm² realization variation is no longer negligible** —
  one of four lawns does not glide (§6.5a), which is a real field-variation effect, just not one large enough
  to overturn the decision.
- **D5** (configuration mismatch) — **excluded decisively**, see §4: the fresh 400 anchor reproduces the
  three-week-old record on 80/81 fields byte-identical with a byte-identical trajectory trace, the sole
  difference being the documented diagnostic `qOmega` fix by exactly its documented factor of nSeg = 12.

### 7.3 What this does and does not establish about the experiment

- Simulated density is **heads/µm² of active, correctly oriented, surface-reachable motor**. The classic assay
  reported a **solution/loading concentration**, which is not the same quantity and is not convertible without
  knowing the inactive-adsorption fraction, orientation distribution and accessible reach.
- This study therefore answers: *given the SoftBox model, how low can density and occupancy fall while low-ATP
  gliding remains viable?* Answer: **robustly to 100 heads/µm² / N_b ≈ 9.9 (4/4 arms forward), and marginally to
  50 heads/µm² / N_b ≈ 5.3, where 1 of 4 field realizations fails.** The onset of realization-dependence is
  therefore around N_b ≈ 4–5, which is the useful boundary statement.
- It does **not** establish the experimental density, and no claim about the experiment's operating occupancy
  should be drawn from it.

### 7.4 Consequences for the provisional T2/T3 Vilfan interpretation

The result removes the strongest version of the T2 objection — that low-ATP gliding must itself sit in the
Vilfan operating regime, so the mechanism cannot be faulted for needing a crowd. It does not, on its own,
show that *our* mechanism works at low occupancy: gliding is the ε-EVEN phenotype, and twirling is ε-ODD. That
is a separate question, and §8 addresses it.

### 7.5 Recommendation on a later low-density skew-twirling test

**Warranted, and it was run — see §8, which answers it.** The free per-head readout available from these arms shows the
per-head torque *scale* is invariant across the density ladder to 2.8 %, the same invariance the ATP study
found across 400× in [ATP]. But at a single ε sign the *chiral* content is buried in a ~0.1–0.5 % residual that
is not separable from the achiral background, so that invariance is a necessary condition only. Resolving it
requires matched ±ε pairs, which is what §8 runs.

---

## 8. Does the skewed-converter mechanism itself work at low occupancy?

**Motivation (jba, mid-task).** Investigations of the Vilfan explanation suggest it may require a large bound
population. If we are going to fault that mechanism for such a restriction, we are obliged to know whether
**our own** skewed-converter mechanism carries the same one. §7 answers only the ε-EVEN phenotype (gliding);
chirality is the ε-ODD phenotype and is a separate question.

### 8.1 Design

The ε-odd response is defined **only on a matched ±ε pair at the same seed**,
`X_odd(seed) = ½[X(+ε,seed) − X(−ε,seed)]`, with the seed as the independent statistical unit. The screen ran
+ε only, so this needed the −ε partners.

**The discriminator:**

| hypothesis | prediction |
|---|---|
| **PER-HEAD** — each bound head contributes its own chiral torque | τ_odd ∝ N_b ⇒ **τ_odd/N_b invariant** with density |
| **COLLECTIVE** (target-zone-depletion kind) | τ_odd/N_b **falls** as occupancy drops, vanishing below the occupancy the collective effect needs |

12 new arms gave n = 4 matched pairs at 100 and 50 heads/µm². The 400 comparator is the **completed low-ATP
ladder, reused unchanged** — legitimate because §4 proved it byte-identical to a fresh run, which also keeps
every density on the **same runner** (GPU), so runner is never confounded with density.

### 8.2 Result — the chiral torque is PER-HEAD

| ρ | n | mean N_b | τ_odd (N·m) | σ | seeds correct sign | **τ_odd/N_b** | ratio to 400 | Welch p |
|---|---|---|---|---|---|---|---|---|
| 400 | 4 | 30.83 ± 2.95 | −1.687e-22 ± 4.16e-23 | **4.05** | **4/4** | −5.245e-24 ± 1.16e-24 | 1.000 | — |
| 100 | 4 | 9.85 ± 0.64 | −7.198e-23 ± 1.75e-23 | **4.11** | **4/4** | −7.361e-24 ± 1.93e-24 | 1.40 | 0.35 |
| 50 | 4 | 5.28 ± 0.46 | −4.521e-23 ± 1.32e-23 | **3.43** | **4/4** | −8.366e-24 ± 2.25e-24 | 1.60 | 0.22 |

**τ_odd is resolved at every density, with the correct chiral sign in 12 of 12 matched pairs.** A 5.8×
occupancy cut — from N_b = 30.8 to 5.3, i.e. 2.6× below the Vilfan weak-depletion bracket — leaves the chiral
torque per bound head statistically unchanged.

Pooling all 12 pairs and regressing |τ_odd/N_b| on ln(density) gives **slope = −1.51e-24 ± 1.17e-24 per
ln-unit, t = −1.29 (df = 10), r = −0.378, Spearman ρ = −0.224** — **no significant occupancy dependence**
(|t| < 2.23). The pairwise contrasts agree (p = 0.35, 0.22).

**⇒ The skewed-converter mechanism is PER-HEAD. It does not require a large bound population, and it is still
generating full-strength per-head chiral torque at N_b ≈ 5.** We may therefore fault a mechanism for requiring
a crowd without inconsistency: ours demonstrably does not.

### 8.3 Three honest qualifications

1. **The nominal trend is upward, not flat — but it is not significant.** τ_odd/N_b rises monotonically
   1.00 → 1.40 → 1.60 as occupancy falls. Monotonicity across three densities is more suggestive than any
   single contrast, and it would fit the reduced-interference picture of §6.4 (fewer heads, less mutual
   competition). But t = −1.29 and Spearman ρ = −0.224 do not support a claim, and at 45 % seed CV with n = 4
   this is comfortably within chance. **Claimed: no occupancy dependence. Not claimed: enhancement at low
   occupancy.** Testing that properly would need n ≈ 12–16 per density.
2. **Ω_odd is the weaker estimator, and it degrades faster than τ_odd.** 4.51σ (4/4) at 400, 2.15σ (3/4) at
   100, 3.04σ (4/4) at 50. τ_odd is a direct time-average; Ω_odd is a slope fit that also carries achiral
   rotational noise. The **torque** claim is solid at every density; a claim about *observable rotation* at low
   occupancy is weaker and should not be made from these n.
3. **My own power forecast was wrong, in the useful direction.** I predicted ~2.3σ at 100 and ~1.6σ at 50 from
   a √N_b noise-scaling argument; the measured values are 4.11σ and 3.43σ. The noise does not scale as
   assumed — recorded so the next power estimate on this assay is not built on the same wrong model.

### 8.4 Consequence: low occupancy is a WORSE place to look for twirling, not better

The mechanism being per-head does **not** make low density a good experimental regime for observing twirl, and
the data say the opposite:

| ρ | mean N_b | turns per µm (ε-odd) |
|---|---|---|
| 400 | 30.83 | **−30.32 ± 7.53** |
| 100 | 9.85 | −10.37 ± 5.95 |
| 50 | 5.28 | −10.91 ± 3.12 |

τ_odd falls with N_b while v_even stays roughly constant (−0.275, −0.344, −0.248 µm/s), so rotation per unit
distance drops ~3× from 400 to 100 and does not recover. **An experimenter hunting twirling should work at
high motor density; the low-density regime is where the mechanism is demonstrated, not where it is observed.**
This confirms the prior recorded in §7.5 before the campaign ran.

### 8.5 Health

**24 arms (12 matched pairs), 0 invalid states, 0 solver failures, 0 rate-cap warnings, 0 rigor ruptures, 0
unclassified detachments; ATP binding accounts for 100 % of detachments at every density.** One driver attempt,
no retries. Outputs: `RUN_LOGS/lowatp/density_twirl_perhead.{txt,csv,png}`; analysis
`scripts/lowatp_density_twirl_analysis.py`.

### 8.6 What is NOT established

- **The mirror control was not run at these densities.** §8 shows the ε-odd response is per-head and
  sign-consistent, inheriting the chiral-origin evidence from the completed low-ATP mirror control at
  400 heads/µm². A mirror control at 100/50 would close that independently; it was not run.
- **No claim about twirling *pitch*** at low occupancy — that was outside scope throughout.
- **n = 4 per density.** Adequate for the flat-vs-falling discrimination (which it settles), inadequate for
  resolving the nominal 1.6× upward trend.
