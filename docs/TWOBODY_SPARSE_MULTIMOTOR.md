# Experiment 4B — sparse multi-motor composition of the two-body chemomechanical cycle

**Date:** 2026-07-14 · **Branch:** `dt-convergence-study` · **Commit at start:** `705a66b3e3e14ff1354ba30b9dac7d05bccd2255`
· **Runner:** CPU sequential only (`-gpu` unused). **Hardware:** aorus, one core, load ~0.2. **Wall-clock:** full
experiment ≈ 4 min. **dt:** primary 5e-6, convergence subset 2.5e-6. **Trap:** 0.05 pN/nm dual-end.

**Default-off, non-canonical prototype** (`[NON-CANONICAL TWO-BODY PROTOTYPE]`), `-exp4b` /
`-twobody-sparse-multimotor` in `softbox/TwoBodyConverterMotor.java`. **The Experiment 4A motor, canonical
kinetics, joint arithmetic, force ordering, RNG behaviour, and `BoA-v1ref` are untouched** (4B is new methods
only; `NucleotideCycleSystem`, `MotorStore`, `CrossBridgeSystem`, `GlidingHarness`, `CanonicalMotorHarness` all
byte-clean). No parameter was tuned during this experiment. This is not a velocity-fitting exercise and not a
gliding-density sweep.

---

## 0. Objective and controlling outcome

Determine how the validated 4A two-body chemomechanical cycle composes when **2–4 independent motors interact with
one shared filament** — do they stay chemically and mechanically well behaved, what duty ratio and
simultaneous-bound distribution emerge, how do co-bound motors share load, and does one motor's stroke or catch
strain disturb another?

**CONTROLLING OUTCOME: A — independent composition.** Motors cycle legally and stably at every N (0 forbidden
transitions, 0 stalls, 0 numerical instability across 40M+ motor-steps); the per-motor duty ratio is **flat with
N** and the occupancy is **near the independent-binomial expectation** (with only a small, filament-mediated
positive co-binding correlation at the rare ≥2-bound level); load sharing is **transient and well behaved**
(summed motor force equals the filament force to float precision; longest co-bound interval ~3 ms); native
strokes remain **predominantly pointedward** (72 % productive, flat with N); and there is **no persistent
mechanical locking** (the ADP dwell is flat with N — co-binding neither drives motors deeper into catch nor
prolongs attachment). **Recommendation: proceed to low-density single-filament gliding.**

---

## 1. Phase 2 — multi-motor architecture audit (traced before implementation)

| behaviour | existing source | current rule | 4B use |
|---|---|---|---|
| motor indexing / storage | `MotorStore` (SoA, N motors) | motor m's sub-bodies at `rodIdx/leverIdx/headIdx(m)=3m..3m+2`; per-motor `boundSeg/bindArc/nucleotideState/forceDotFil/cooldown/stats` | ONE `MotorStore(N)`; head slot 3m+2 |
| per-motor RNG keying | `NucleotideCycleSystem` wang-hash `(m·1000003)^(step·999983)^(seed·7919)^salt` | keyed on motor index m ⇒ **independent chemistry stream per motor** | inherited; search RNG salt also folds in m (`0x0A1+m·7919`) |
| binding candidate search | 3E gate (`gateMetrics`/`gatePasses`) over the live Brownian pose | per-motor, geometric + stereospecific; latches the live pose + material coordinate | per-motor `gateMetricsM`/`gatePassesM`, evaluated from the **pre-integration** filament state |
| simultaneous binding updates | — | each motor's latch writes only its own `boundSeg[m]`/`bindArc[m]` | binds decided from the same pre-integration filament state; distinct slots ⇒ race-free |
| multiple forces on one filament | `CrossBridgeSystem.segGather` over the boundSeg CSR-inverse | every bound motor's seg reaction summed into the segment's `forceSum` — **order-independent, no atomics** | reused verbatim; N motors on 1 segment |
| force accumulation ordering | CSR histogram/scan/scatter/gather | a segment-side sum over its bound motors ⇒ independent of motor iteration order | **verified: Σ_i F_{i,∥} = gathered filament force to ≈1e-7 pN** |
| motor detachment cleanup | `bondForces` (`if boundSeg<0 continue`) | a detached motor's bondData row is zeroed; its `boundSeg`/state are per-motor | one motor's detach cannot touch another's bond/state (per-motor arrays) |
| shared filament integration | `RigidRodLangevinIntegrationSystem.integrate` | the summed `forceSum` integrates the filament ONCE per step | filament integrated once after the gather |
| material-coordinate tracking | `bindArc[m]` (persistent, moves with actin) | latched at bind, never relatched | per-motor `bindArc[m]` |
| same-segment / same-coordinate binding | — | multiple motors MAY bind the same segment (the gather is built for it); **no same-material-coordinate exclusion exists** | not added (the model does not require one; anchors sit at distinct axial sites ⇒ distinct coordinates) |
| excluded volume between motors | — | none in the existing model | none added (150 nm axial spacing ≫ the ~12 nm body reach) |

**Explicit determinations (verified by construction + controls):**

- **multiple motors can bind the same segment** (there is only one) at **distinct material coordinates** set by
  their anchors; no same-coordinate exclusion rule exists and none was added.
- **force accumulation is order-independent** — the CSR segment-side gather sums per-motor reactions; a
  brute Σ_bound(seg-side axial) matched the gathered `forceSum` axial to **≤ 8.7e-7 pN** (float last-bit) at every
  N (`comparison_table.csv:forceBalErr_pN`).
- **binding decisions are made from the same pre-integration filament state** (all latches happen before the
  single integrate).
- **one motor's detachment cannot clear another's bond data** — bondData is per-motor (`m·STRIDE`); detach zeroes
  only that row; `boundSeg`/`nucleotideState` are per-motor arrays.
- **motor index sets the RNG stream** (chemistry keyed on m; search salt folds in m) ⇒ independent streams.
- **adding motors does not change motor 0's history when the extras never bind** — verified (control §5).
- **filament force is summed before a single integration**, not integrated once per motor.

**No explicit motor–motor coupling, synchronization, cooperative rate, or shared chemistry was introduced.**
Motors interact only through the shared filament mechanics and the existing CSR force gather.

---

## 2. Assay geometry (fixed before production)

One filament (translational + rotational mechanics enabled; thermal Brownian off, as in the 3E/4A trap-held
assay — the filament moves under motor + trap load) held by two harmonic end-traps (0.05 pN/nm, the 3F/4A value,
**not tuned**). N fixed motor anchors sit **below** the filament at evenly spaced interior axial sites:

- filament half-length **0.500 µm**; **axial motor spacing 0.150 µm; transverse spacing 0**;
- sites (µm from COM): N=1 {0}; N=2 {−0.075, +0.075}; N=3 {−0.15, 0, +0.15}; N=4 {−0.225, −0.075, +0.075, +0.225};
- all sites interior (|s| ≤ 0.225 < 0.35 = 0.7·half); anchors do not overlap (150 nm ≫ 12 nm body); **no motor
  begins bound** (all start free, searching, in ADP·Pi at a random pose);
- anchor placement uses the actin frame + the stereospecific binding pose only; **`barbedDir` does not enter the
  stroke sign** (the intrinsic converter target is the fixed material-frame θ_s of 3C/3D/4A);
- the same filament polarity and coordinate convention throughout.

The harmonic end-traps restore the filament toward its rest position, so there is **no net translocation** (this
is a trapped assay, not gliding); the resolvable observable is the **transient per-stroke displacement** and the
**force sharing**, exactly as intended.

---

## 3. Time step

Primary **dt = 5e-6**; convergence subset **dt = 2.5e-6** (the 4A ADP-dwell was still ~biased at 1e-5, so 1e-5 is
not used as controlling). Matched-seed episodes at the two fine steps (N=2):

| observable | dt = 5e-6 | dt = 2.5e-6 | note |
|---|---:|---:|---|
| per-motor bound fraction | 0.0137 | 0.0161 | +17 % (the inherited coarse-dt ADP-dwell bias) |
| mean number bound | 0.0274 | 0.0322 | tracks the bound fraction |
| ADP dwell (ms) | 1.258 | 1.047 | −17 % — the 4A native-cycle release dwell keeps a modest coarse-dt bias, converging toward finer dt |
| native stroke (nm) | 2.835 | 2.900 | dt-stable (mechanics) |

**The qualitative composition result — independent, flat-with-N, no locking — is dt-stable; both steps give the
same classification.** The ADP dwell (hence bound fraction) retains the known 4A coarse-dt release-dwell bias
(`docs/TWOBODY_BIOCHEMICAL_CYCLE.md §5`), monotonically shrinking with dt; the stroke and independence are dt-stable.

---

## 4. Primary analyses (N = 1, 2, 3, 4; primary dt = 5e-6; 24 episodes × 300 k steps each)

### A. Emergent duty ratio (`duty_ratio.csv`, `occupancy.csv`)

| N | bound frac / motor | mean # bound | ADP·Pi dwell (ms) | ADP dwell (ms) | ATP-free dwell (ms) | cycles/motor/s |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 0.0126 | 0.0126 | 0.096 | 1.199 | 10.64 | 9.4 |
| 2 | 0.0123 | 0.0247 | 0.098 | 1.253 | 10.58 | 8.9 |
| 3 | 0.0127 | 0.0382 | 0.102 | 1.293 | 10.42 | 8.9 |
| 4 | 0.0123 | 0.0493 | 0.103 | 1.256 | 10.24 | 8.8 |

The per-motor bound fraction is **flat with N** (0.0123–0.0127); mean # bound scales linearly ∝ N. Dwells match
the canonical single-motor values (ADP·Pi 0.10 ms, ATP-free 10.0 ms) and are **flat with N**. The duty ratio is
low (~0.013) because the ~10 ms off-filament ATP recovery + the Brownian rebinding search dominate the cycle — a
genuinely sparse, low-duty regime.

**Simultaneous-bound occupancy vs the independent-motor expectation** (binomial from the measured per-motor
bound fraction):

| N | P(≥2) observed | P(≥2) independent |
|---:|---:|---:|
| 2 | 3.3e-4 | 1.5e-4 |
| 3 | 1.2e-3 | 4.8e-4 |
| 4 | 1.7e-3 | 9.0e-4 |

P(1 bound) tracks the binomial closely; P(≥2) sits **~2× above** the independent expectation — a **small,
filament-mediated positive co-binding correlation** (a bound motor stabilises/repositions the shared filament,
slightly favouring a second capture). The absolute occupancy is tiny (P(≥2) ≤ 0.0017). This is a **mechanical**
correlation through the shared filament, **not biochemical cooperativity** (the chemistry is per-motor and
uncoupled).

### B. Native stroke distribution (`comparison_table.csv`)

| N | native stroke (nm) | native stroke, ≥1 other bound (nm) | productive fraction | near-zero/backward |
|---:|---:|---:|---:|---:|
| 1 | 2.91 | — | 0.718 | 0.047 |
| 2 | 2.91 | 3.39 | 0.719 | 0.061 |
| 3 | 2.96 | 2.68 | 0.732 | 0.056 |
| 4 | 2.90 | 2.65 | 0.722 | 0.065 |

Three distinct quantities, **not equal**: (1) the clean relaxed-capture mechanical capacity ≈ **6.9 nm** (4A
Phase 4); (2) the **native stochastic-cycle stroke ≈ 2.9 nm** (measured over a 0.4 ms event window from live
cycling poses — smaller and broader than the clean capacity, as 4A found); (3) the **net filament displacement
≈ 0** (harmonic-trap-restored). The native stroke is **flat with N** and its value while another motor is bound
(2.6–3.4 nm) is within the single-motor spread — **co-binding does not collapse the stroke.**

### C. Load sharing (`comparison_table.csv`, `catch_release.csv`)

- **Force balance:** Σ_i F_{i,∥} equals the gathered filament motor force to **≤ 8.7e-7 pN** at every N — no
  double-counting, order-independent (the CSR gather identity).
- mean |per-motor forceDotFil| = **0.65–0.67 pN**, flat with N (each bound motor carries its own post-stroke catch
  strain, ~+0.8 pN, independent of N).
- longest co-bound interval = **3.1 ms** (short; no long-lived opposing states); co-bound periods are transient.

### D. Catch-modulated release in the ensemble (`catch_release.csv`)

Binning every bound-ADP motor-step by its live `forceDotFil` and comparing the empirical ADP→NONE release rate to
the **unchanged** canonical `onADP·⟨g(F)⟩`:

| force bin (pN) | N=1 measRate | N=1 canon | N=4 measRate | N=4 canon |
|---:|---:|---:|---:|---:|
| −0.25 | 822 | 1030 | 946 | 1065 |
| +0.25 (dominant) | 879 | 881 | 823 | 888 |
| +0.75 | 742 | 684 | — | — |
| +1.25 | 648 | 528 | — | — |

The release rate follows the canonical catch–slip `g(F)` at **all N** — the dominant bin (+0.25 pN) matches to a
few percent. Co-bound motors each rest near +0.8 pN (mild catch), and **do not** push one another into deep catch
(no locking) or into slip; the ADP dwell is flat with N. `xCatch` was not retuned.

### E. Mechanical interference and productive cycling (`comparison_table.csv`)

Per Pi-release event, classified over the 0.4 ms window: **productive (≥1 nm pointedward) 72 %**, **near-zero
(|Δ|<1 nm) or backward 5–6 %**, **interrupted (motor detached within the window) ~22 %**, **all flat with N**. The
backward + near-zero fraction does **not** grow with N — co-bound motors do not mechanically block one another's
strokes. No transient or permanent stalls; no numerical instability.

### F. Net displacement and displacement per cycle

Net filament displacement ≈ 0 and net rate ≈ 0 at all N — the harmonic end-traps restore the filament (by design;
this is a trapped assay, not gliding). The **productive per-cycle displacement is the transient ≈ 2.9 nm
pointedward stroke** (analysis B), not a net translocation. "Net translocation" is deliberately not claimed.

### G. Motor independence and reproducibility (controls, §5)

All independence diagnostics pass (below).

---

## 5. Controls

- **N=1 regression:** reproduces the 4A single-motor cycle — legal transition graph (0 forbidden), ADP·Pi-only
  binding, Pi stroke, ADP dwell 1.20 ms (the 5e-6 value), ATP detachment, recovery (338/340), rebinding, 0 stalls,
  0 instability.
- **Inactive-neighbor independence:** N=4 with only motor 0 permitted to bind → the active motor's bound fraction
  **0.0125 vs the N=1 value 0.0126 (Δ = 0.0001)** — idle extra motors do not perturb the active motor's chemistry
  or force (per-motor RNG + per-motor state + zero force from non-binding motors).
- **Fixed-seed reproducibility:** two identical short runs give a **bit-identical** filament-trajectory hash; a
  different seed differs.
- **Index/anchor permutation:** swapping the two N=2 anchor sites leaves the ensemble mean-bound **invariant**
  (0.0139 vs 0.0139) — motor index does not create RNG correlation; the ensemble is exchangeable.
- **Force summation order-independence:** Σ_i F_{i,∥} = the gathered filament force to ≈1e-7 pN (§4C).
- **Chemistry controls:** 0 forbidden transitions at every N; ATP detachment fires once per cycle
  (detaches = strokes = releases); each motor recovers independently to ADP·Pi (recoveries ≈ detaches).
- **Polarity:** reversing the filament polarity (`swap`) keeps the net displacement ~0 in both (trap-nulled), so
  the polarity sign is read from the **pointedward productive-stroke fraction** (72 %) and the inherited 3C/3D/4A
  rotation-/swap-covariance, not from net displacement. `barbedDir` does not select the intrinsic stroke target.

---

## 6. Comparison table

| observable | N=1 | N=2 | N=3 | N=4 |
|---|---:|---:|---:|---:|
| bound fraction per motor | 0.0126 | 0.0123 | 0.0127 | 0.0123 |
| mean number bound | 0.0126 | 0.0247 | 0.0382 | 0.0493 |
| P(≥2 bound) | 0.0000 | 3.3e-4 | 1.2e-3 | 1.7e-3 |
| P(≥2) independent | ~0 | 1.5e-4 | 4.8e-4 | 9.0e-4 |
| ADP dwell (ms) | 1.199 | 1.253 | 1.293 | 1.256 |
| native stroke (nm) | 2.909 | 2.912 | 2.955 | 2.901 |
| productive stroke fraction | 0.718 | 0.719 | 0.732 | 0.722 |
| near-zero/backward fraction | 0.047 | 0.061 | 0.056 | 0.065 |
| displacement per cycle (net, nm) | ~0 | ~0 | ~0 | ~0 |
| net displacement rate (nm/s) | ~0 | ~0 | 0.03 | 0.04 |
| mean \|motor force\| (pN) | 0.662 | 0.647 | 0.657 | 0.665 |
| longest co-bound interval (ms) | 0.00 | 1.37 | 3.15 | 3.15 |
| force-balance error (pN) | 0.0 | 4.3e-7 | 8.7e-7 | 6.5e-7 |

---

## 7. Outcome classification

**Outcome A — independent composition.** Motors cycle legally and stably; the duty ratio is close to the
independent expectation (flat per-motor bound fraction; occupancy near-binomial with a small filament-mediated
positive co-binding correlation only at the rare ≥2 level); load sharing is transient and well behaved (exact
force balance, ~3 ms co-bound intervals); strokes remain predominantly pointedward (72 %, flat with N); no
persistent locking (flat ADP dwell). The mild P(≥2) super-binomial excess is a whisper of Outcome-B mechanical
coupling through the shared filament, but it does **not** materially change the dwell or stroke distributions
(both flat with N), so the controlling outcome is A.

---

## 8. Pass criteria

| # | criterion | result |
|---|---|---|
| 1 | N=1 reproduces the 4A baseline | ✓ |
| 2 | N=2,3,4 complete repeated cycles, no manual reset | ✓ (638/957/1266 cycles) |
| 3 | every motor follows the canonical transition graph independently | ✓ (0 forbidden, all N) |
| 4 | no motor-state / bond-state / RNG cross-talk | ✓ (inactive-neighbor Δ=0.0001; fixed-seed bit-identical; permutation-invariant) |
| 5 | summed motor forces match the filament force | ✓ (≤8.7e-7 pN) |
| 6 | ATP detachment + rebinding clean under co-binding | ✓ (detaches=strokes=releases; recoveries≈detaches) |
| 7 | native strokes predominantly polarity-correct | ✓ (72 % productive pointedward, flat with N) |
| 8 | simultaneous-bound states measurable + interpreted | ✓ (occupancy table vs binomial) |
| 9 | force-dependent ADP release follows the unchanged canonical law | ✓ (catch_release tracks onADP·⟨g⟩ at all N) |
| 10 | stable between dt=5e-6 and 2.5e-6 | ✓ qualitatively (same regime; ADP dwell keeps the inherited ~17 % coarse-dt bias, converging) |
| 11 | no canonical regression | ✓ (5 canonical files + BoA-v1ref byte-clean) |

**All 11 pass.** A pass did not require larger displacement, faster motion, or equal load sharing.

---

## 9. Recommendation for the next experiment

**Proceed to low-density single-filament gliding.** The sparse multi-motor composition is clean: independent
cycling, exact force sharing, no locking, no interference, no cross-talk. The natural next step is to free the
filament (remove the restoring traps, add a surface/bed with a small motor carpet) and measure a low-density
gliding velocity against the fine-dt canonical curve **as a regression, not a fitting target** — beginning the
ensemble ladder from 4A→4B toward the gliding assay. Carry forward: the native stochastic stroke (~2.9 nm) is
smaller than the clean capacity (~6.9 nm); the low duty (~0.013) is search+recovery-dominated (so gliding velocity
will be recruitment-limited); and the ADP dwell keeps a modest coarse-dt bias, so gliding should use dt ≤ 5e-6.
Do not begin that experiment in this run.

---

## 10. Deliverables and reproduction

```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp4b -out RUN_LOGS/twobody_sparse_multimotor/csv   # full experiment (~4 min, CPU)
./scripts/run_lasertrap.sh -exp4b -fast                                          # quick smoke
python3 scripts/twobody4b_analyze.py RUN_LOGS/twobody_sparse_multimotor/csv \
        RUN_LOGS/twobody_sparse_multimotor/exp4b_summary.png
./scripts/run_lasertrap.sh -exp4b -3js ~/Code/SoftBox/threejs_twobody4b          # viewer (N=3 shared filament)
# 4A regression (unchanged): ./scripts/run_lasertrap.sh -exp4a -fast
```

**Artifacts** (`RUN_LOGS/twobody_sparse_multimotor/`): `exp4b_full.log`, `exp4b_summary.png`, and
`csv/{comparison_table, occupancy, duty_ratio, catch_release, dt_convergence}.csv`. Source:
`softbox/TwoBodyConverterMotor.java` (`run4b` + `Multi`/`buildMulti`/`stepMulti` + the analysis methods), one
dispatch line in `softbox/LaserTrapHarness.java`.

**View:** `python3 SoftBox/sim_server.py 8000` from `~/Code`, open
`http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → newest).
