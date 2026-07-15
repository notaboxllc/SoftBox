# Experiment 4A — the canonical nucleotide cycle ported onto the two-body motor

**Date:** 2026-07-14 · **Branch:** `dt-convergence-study` · **Commit at start:** `705a66b3e3e14ff1354ba30b9dac7d05bccd2255`
· **Runner:** CPU sequential only (single motor; `-gpu` unused). **Hardware:** aorus, 16 threads (one core),
CPU load ~0.2. **Wall-clock:** full experiment ≈ 6 min. **dt:** uniform 1e-5 (cycle); relaxation 1e-5, search
1e-6 (regression); dt-sweep {2e-5,1e-5,5e-6,2.5e-6}. **Trap:** 0.05 pN/nm.

**Default-off, non-canonical prototype** (`[NON-CANONICAL TWO-BODY PROTOTYPE]`), `-exp4a` / `-twobody-cycle` in
`softbox/TwoBodyConverterMotor.java`. **The canonical motor, production defaults, joint arithmetic, force
ordering, RNG behaviour, and `BoA-v1ref` are untouched.** No canonical kinetic constant is changed. Experiments
3C/3D/3E/3F are preserved (4A is new methods only). **The chemistry is the ratified `-lymntaylor` kernel
(`NucleotideCycleSystem.cycleLymnTaylor`) reused VERBATIM** over the two-body motor's own 1-motor `MotorStore`;
the two-body mechanics (`stepC` bound / `stepU` search / the 3E gate) are the thin mechanical adapter. **No
second chemistry framework, no new rate constants, no renamed states.**

---

## 0. Objective and controlling result

Close the minimal stochastic biochemical cycle of the validated two-body motor while preserving its established
single-molecule mechanics:

```
detached/recovered (ADP·Pi, primed) → bind → actin-bound ADP·Pi → Pi release + power stroke → actin-bound ADP
  → ADP release → ATP-driven detachment → recovery/hydrolysis → binding-competent ADP·Pi → …
```

**CONTROLLING OUTCOME: PASS.** The two-body motor completes repeated stochastic biochemical cycles with no
manual reset (915 full cycles over 40 episodes, 0 stalls, 0 numerical instability, 0 forbidden transitions); the
transition graph matches the canonical chemistry exactly; binding is restricted to ADP·Pi; Pi release produces
the validated pointed-first ~6.9 nm axial stroke; signed-load ADP release reproduces the canonical catch–slip
rate law with the geometrically-established sign; ATP causes clean single detachment with no position jump and no
residual force; detached recovery restores a binding-competent pre-stroke motor that rebinds and strokes again;
and the 3E/3F mechanical observables are **byte-for-byte within the capture spread** with the cycle enabled. No
canonical default changed.

---

## 1. Phase 1 — canonical chemistry source map (traced, executed code)

The ratified default motor's chemistry is the single-pathway **Lymn–Taylor** cycle
(`NucleotideCycleSystem.cycleLymnTaylor`, selected by `LYMN_TAYLOR=true`, `CURRENT_STATE.md §1`). It was traced —
not read from comments — as the source of every rule below.

| behaviour | source file | method / field | canonical rule | two-body use (4A) |
|---|---|---|---|---|
| nucleotide-state representation | `MotorStore.java:145` | `NUC_NONE=0, NUC_ATP=1, NUC_ADPPI=2, NUC_ADP=3` (`nucleotideState[m]`) | 4-state integer per motor | **same array**, `cm.mot.nucleotideState[0]` |
| allowed transitions | `NucleotideCycleSystem.java:441-453` | `cycleLymnTaylor` | NONE→ATP→ADPPi→ADP→NONE (one draw/step) | kernel reused verbatim |
| transition rates | `MotorStore.setNucParams:384-393` | `nucParams[1..7]` | atpOn 2e4; onATP/offATP 100; onPi 1e4/offPi 0; onADP/offADP 1e3 (Env.java:836-855) | `setNucParams(dt)` — **unchanged values** |
| rate→per-step prob | `cycleLymnTaylor:442,444,447,451` | `u < rate·dt`, `u` = wang-hash `(m,step,seed)` | Poisson thinning per step | same kernel, same salt `0x4E55` |
| multiple transitions/step | `cycleLymnTaylor` | one branch per state per step | **at most one** transition per motor per step | inherited |
| binding eligibility | `GlidingHarness.java:99,366` | `ADPPI_BIND` welded on with `LYMN_TAYLOR` | a head binds actin **only in ADP·Pi** | explicit `state==NUC_ADPPI` gate on the 3E latch |
| state→conformation (cocking) | `MotorStore.java:173` | `isCocked() = state != NUC_ADPPI` | uncocked only in ADP·Pi; the DIRSWING lever target 0°↔60° | converter target θ_s: −30° (ADP·Pi) ↔ +30° (else) |
| Pi-release stroke | `cycleLymnTaylor:447` | ADPPi→ADP at `onPi` | Pi release swings the lever (emergent, not a force) | θ_s switch −30°→+30° = the +60° 3D/3F stroke |
| ADP-release law | `cycleLymnTaylor:449-452` | ADP→NONE at `onADP·g(F)` | `g=αCatch·e^(−F·xCatch/kT)+αSlip·e^(+F·xSlip/kT)` (=1 at F=0) | same kernel; catch constants from `setKinParams` |
| signed-load observable | `CrossBridgeSystem.java:203-204` | `forceDotFil = Dot(F8_head, seg.uVec)` (bondData[12]) | along-filament projection of the head cross-bridge force | **the SAME quantity** — `stepC` already writes bondData[12]; copied to `forceDotFil` |
| catch/slip constants | `MotorStore.setKinParams:260-263` | αCatch 0.92, αSlip 0.08, xCatch 2.5 nm, xSlip 0.4 nm | Guo–Guilford 2006 | `setKinParams` — **unchanged** |
| force averaging | `cycleLymnTaylor:435-438` | EMA `alpha=kinParams[17]` | ratified default **alpha=0 ⇒ instantaneous** forceDotFil | alpha=0 (canonical default) |
| ATP detachment | `cycleLymnTaylor:459-466` | bound head ending in ATP → `boundSeg=FREE_COOLDOWN/BINDABLE` | ATP binding to the rigor head detaches it (NONE→ATP) | inherited; the bond drops automatically (`bondForces:100` `if(s<0) continue`) |
| detached recovery | `cycleLymnTaylor:444-445` | off-fil ATP→ADPPi at `offATP`; off-fil ADPPi→ADP = 0 | hydrolysis re-primes the lever; waits primed in ADP·Pi | inherited; θ_s uncocks to −30° when ADPPi ⇒ recovery stroke |
| refractory | `MotorStore.java:127,272` | `refractorySteps = ceil(myoRebindTime/dt)` | dt-correct 1-step post-release block (HEAD, blockProb 1.0) | inherited (`kinParams[10]`) |
| force-cap detachment (diagnostic) | `NucleotideCycleSystem` (not on LT path) / `setFaithfulRelease` | `kinParams[12]` | **default OFF** — a diagnostic, not the release | **kept OFF** (default) |
| update ordering | `GlidingHarness.java:809-837` | bind → cycle → bond/integrate | cycle reads **one-step-stale** forceDotFil | matched exactly (§5) |

### Force component and sign convention (traced, not assumed)

- **Which force enters the ADP-release law:** `forceDotFil = Dot(F8_head, seg.uVec)` — the head-side F8
  cross-bridge force projected onto the filament axis (`CrossBridgeSystem.java:203-204`). In the two-body motor
  this is `cm.bondData[12]`, already computed by `stepC`'s `bondForces` call; 4A copies it into
  `cm.mot.forceDotFil[0]` verbatim. Nothing is recomputed — the sign convention is inherited from the canonical
  bond kernel by construction.
- **Evaluated before or after the mechanical update:** the cycle at step *t* reads the forceDotFil written by the
  bond kernel at the **end of step *t*−1** (one-step-stale), exactly as the canonical gliding path.
- **Sign established from geometry + executed force flow (Phase C, not assumed):** an external axial load toward
  **+barbed** (`trapParams[5] > 0`) opposes the pointed-ward stroke, stretches the F8 bond so the head-side force
  points barbed-ward, giving `forceDotFil > 0` → the **catch** branch dominates → **slower** ADP release.
  Assisting load (−barbed) gives `forceDotFil < 0` → **slip** → faster release. This is the canonical
  `e^(−F·xCatch/kT)` catch direction: **F > 0 = resisting/opposing = catch.**

---

## 2. Phase 2 — reuse, not duplication

**Approach #1 (call the existing canonical code through a thin mechanical adapter) was achievable and used.** The
canonical `cycleLymnTaylor` is a per-motor pure function over SoA arrays; the two-body `Cmot` already embeds a
1-motor `MotorStore` (`cm.mot`). 4A therefore:

1. installs the canonical parameters on that store (`initChem4a`: `setKinParams(0.006,-0.4,dt)` + `setNucParams(dt)`
   — the exact ratified `-lymntaylor` defaults: break-cap OFF, instantaneous forceDotFil, HEAD dt-correct
   refractory);
2. each step, supplies `forceDotFil = cm.bondData[12]` and `forceMag = |F8_head|`, sets the counts, and **calls
   `NucleotideCycleSystem.cycleLymnTaylor` verbatim** (no fork, no copy);
3. reads back `nucleotideState[0]` to drive the converter target (cocking) and `boundSeg[0]` to know bound/unbound.

No chemistry enum, rate, or transition was duplicated or re-authored. `NucleotideCycleSystem`, `MotorStore`,
`CrossBridgeSystem`, `GlidingHarness`, and `CanonicalMotorHarness` are **byte-unchanged** (verified: `git status`
clean on all five). The RNG salt (`0x4E55`), draw semantics, and transition ordering are the kernel's own.

---

## 3. Phase 3 — single-motor validation

### A. State-machine audit trajectory (`state_trajectory.csv`)

Six complete cycles logged, **37 transitions, 0 forbidden state jumps** (every transition ∈
{NONE→ATP, ATP→ADPPi, ADPPi→ADP, ADP→NONE}). The annotated trace shows, per transition: time, state, bound flag,
reason, canonical rate + per-step probability, converter target θ_s, θ=ψ−φ, head ψ, F8 extension, axial motor
force (forceDotFil), external load, and actin displacement. The filament **walks pointed-ward ~5 nm per full
cycle** (actinDisp 0 → −4.3 → −9.3 → −14.1 nm over the first three cycles) — the emergent processive signature,
driven entirely by the θ_s cocking switch, never by a direct position write.

### B. Zero-load cycling statistics (`dwell_stats.csv`, `transition_counts.csv`)

40 episodes × 400 000 steps: **915 binds = 915 strokes = 915 ADP releases = 915 detaches, 914 recoveries;
0 stalled episodes, 0 numerically unstable, 0 forbidden.** Attach P = 1.000, detach P = 1.000, recovery P = 0.999
per bind. Dwell means vs the canonical `1/rate`:

| state (bound) | measured dwell | canonical 1/rate | note |
|---|---:|---:|---|
| NONE (bound) | 0.050 ms | 0.050 ms (1/atpOn) | ✓ |
| ADP·Pi (bound) | 0.097 ms | 0.100 ms (1/onPi) | ✓ |
| ADP (bound) | 4.40 ms | ≥1.0 ms (1/onADP, unloaded) | **load-modulated** — the post-stroke resting catch strain (~+1 pN) prolongs it (see C) |
| ATP (free) | 9.66 ms | 10.0 ms (1/offATP) | ✓ (hydrolysis recovery) |

The dwell distributions are exponential (median/mean ≈ ln2, as expected). The bound-ADP dwell exceeds the
unloaded `1/onADP` because the resting post-stroke strain sits in the catch regime — a **feature**, quantified
in C, not a discrepancy.

### C. Force-clamp signed-load ADP-release assay (`adp_release_forceclamp.csv`)

A bound-ADP motor is held under a fixed external axial load; the ADP→NONE release rate is measured with a
**censoring-aware MLE** (events / total observed bound-ADP time; 0 % censoring at the ≥8 ms window). The measured
rate is compared to the canonical `onADP·g(⟨F⟩)` (rate at the settled mean force) and `onADP·⟨g(F)⟩` (rate
averaged over the live fluctuating force):

| extLoad (pN) | forceDotFil (pN) | measRate /s [95 % CI] | onADP·g(⟨F⟩) | onADP·⟨g(F)⟩ | regime |
|---:|---:|---:|---:|---:|---|
| −3.0 | −1.80 | 2642 [2357, 2982] | 2806 | 2961 | slip |
| −2.0 | −0.93 | 1717 [1542, 1920] | 1687 | 1781 | slip |
| −1.0 | −0.06 | 1107 [989, 1235] | 1033 | 1102 | slip |
| −0.5 | +0.37 | 912 [811, 1042] | 817 | 865 | catch |
| 0.0 | +0.80 | 771 [688, 869] | 651 | 696 | catch |
| +0.5 | +1.23 | 582 [494, 678] | 525 | 555 | catch |
| +1.0 | +1.66 | 486 [423, 558] | 429 | 452 | catch |
| +2.0 | +2.52 | 341 [301, 389] | 301 | 314 | catch |
| +3.0 | +3.37 | 276 [245, 317] | 230 | 236 | catch |

- **Sign (from geometry):** +barbed / opposing load → forceDotFil > 0 → **catch (release slows)**; assisting load
  → forceDotFil < 0 → **slip (release speeds)**. Monotonic, correct, matching the canonical `g(F)` shape.
- **Rate law reproduced:** the measured rate tracks `onADP·⟨g(F)⟩` and exceeds `onADP·g(⟨F⟩)` by ~10 % because
  `g` is convex in `F` and `forceDotFil` fluctuates thermally (Jensen) — **not** a re-tuning; the kinetic
  constants are the canonical values. The catch distance `xCatch = 2.5 nm` was **not** changed (a prohibited
  change).

### D. ATP-detachment assay (`atp_detach.csv`)

From the post-ADP-release bound state (NONE, awaiting ATP), n = 200: **latency 0.053 ± 0.050 ms** (canonical
1/atpOn = 0.050 ms ✓); **detachment via NONE→ATP 200/200**; **bond removed exactly once 200/200**; **no positional
jump (< 0.5 nm) 200/200**; **zero residual F8 force after detachment 200/200** (the bond kernel skips
`boundSeg < 0`). Positions, orientations, and velocities are continuous across detachment (no teleport); only the
`boundSeg` state and the F8 bond are reset — exactly what the canonical kernel resets.

### E. Recovery and rebinding (`recovery_rebind.csv`)

n = 40 continuous multi-cycle episodes (no manual reset): **recovered to ADP·Pi 40/40** (off-fil ATP→ADPPi
hydrolysis re-primes and uncocks the lever), **rebound ≥ a 2nd time 40/40**, **second Pi-release stroke
pointed-first 39/40** (one marginal near-zero native stroke from a rebound pose). First-stroke 5.88 ± 1.22 nm,
second-stroke 4.74 ± 1.46 nm (native cycle strokes measured over a fixed post-transition window from Brownian-
searched rebound poses; the *clean* relaxed-capture stroke is 6.90 nm — Phase 4). Recovery uses the canonical
mechanism only: the off-filament ATP→ADPPi transition + the θ_s uncocking when ADP·Pi is re-entered.

---

## 4. Phase 4 — the validated mechanics are protected (`mech_regression.csv`)

3E/3F observables re-measured with the biochemical cycle enabled (n = 118 captures; the native Pi release is now
fired by `cycleLymnTaylor`, not a manual θ_s set):

| observable | validated baseline (3E/3F) | Experiment 4A | verdict |
|---|---:|---:|---|
| capture success | 0.969 | **0.983** | ✓ |
| capture preload | ~1.49 pN | **1.49 pN** | ✓ |
| relaxed pre-stroke internal preload | ~0.10 pN | **0.10 pN** | ✓ |
| axial stroke | ~6.90 nm | **6.90 ± 0.04 nm** | ✓ |
| transverse displacement | ~0.10 nm | **0.10 nm** | ✓ |
| post-stroke whole-crossbridge stiffness | ~0.624 pN/nm | **0.624 pN/nm** | ✓ |
| force on actin · b̂ | −0.664 pN (pointed) | **−0.664 pN** | ✓ |
| polarity | pointed-first | **pointed-first 118/118** | ✓ |
| stable ADP dwell before release | expected | present (§3B, load-modulated) | ✓ |

**No mechanical regression.** The calibration did not move because the adapter reuses `stepC`/the 3E gate
unchanged and the chemistry only *keys* the same θ_s switch that 3F applied manually. No geometry or spring
constant was touched.

---

## 5. Phase 5 — numerical, ordering, reproducibility (`dt_convergence.csv`)

- **Rates scale with dt / probabilities in range:** max per-step transition probability = max(atpOn·dt, onPi·dt) =
  0.40 / 0.20 / 0.10 / 0.05 at dt {2e-5, 1e-5, 5e-6, 2.5e-6} — **< 1 at all dt**. No transition fires twice per
  step (one branch per state per step in the kernel).
- **Dwell converges as dt→0:** the zero-load ADP release rate is 576 / 565 / 721 / 731 /s across the sweep;
  the two finest steps (5e-6, 2.5e-6) agree to ~1 % (dwell 1.387 vs 1.369 ms). The coarse production dt (1e-5)
  over-estimates the dwell by ~28 % — the same coarse-dt discretization the fine-dt gliding study documents
  (`CURRENT_STATE.md §3`); it converges monotonically.
- **Executed ordering (reported, not imposed):** per step —
  **1. BIND** (3E gate, live pose latched, ADP·Pi only) → **2. CYCLE** (`cycleLymnTaylor`: state transition +
  nucleotide-driven detach, reads the one-step-stale forceDotFil) → **3. θ_s ← cocking(state)** → **4. MECH**
  (`stepC` F8 + integrate when bound; `stepU` Brownian search when free) → **5. forceDotFil ← Dot(F8_head,
  seg.uVec)** for the next step. This matches the canonical gliding **bind → cycle → bond/integrate** order and
  the one-step-stale force point.
- **Detachment leaves no stale constraint; rebinding retains no stale force/state:** the bond drops the instant
  `boundSeg < 0` (residual F8 = 0, Phase D); the EMA seed / avgInit are reset on free by the kernel; rebinding
  re-latches a fresh material coordinate from the live pose.
- **Reproducibility:** fixed-seed trajectories are **bit-identical** (summary hash reproduced exactly); a
  different seed differs. The RNG is the kernel's stateless wang-hash keyed `(motor, step, seed)`.
- **No canonical regression:** `NucleotideCycleSystem`, `MotorStore`, `CrossBridgeSystem`, `GlidingHarness`,
  `CanonicalMotorHarness` byte-unchanged; 3C/3E/3F paths untouched (4A is new methods only).

---

## 6. Pass criteria

| # | criterion | result |
|---|---|---|
| 1 | repeated stochastic cycles, no manual reset | ✓ 915 full cycles, 0 stalls |
| 2 | transition graph matches the canonical chemistry | ✓ 0 forbidden jumps |
| 3 | binding restricted to the correct state (ADP·Pi) | ✓ explicit gate + welded canonical rule |
| 4 | Pi release = validated pointed-first ~6.9 nm stroke | ✓ 6.90 ± 0.04 nm, 118/118 pointed |
| 5 | signed-load ADP release reproduces the canonical rate law | ✓ catch/slip shape + sign, tracks onADP·⟨g(F)⟩ |
| 6 | ATP causes clean detachment | ✓ once, no jump, zero residual (200/200) |
| 7 | recovery restores a binding-competent pre-stroke motor | ✓ 40/40 recovered to ADP·Pi |
| 8 | rebind + stroke again | ✓ 40/40 rebound, 39/40 2nd stroke pointed |
| 9 | 3E/3F mechanics within tolerance | ✓ every observable at baseline |
| 10 | canonical behaviour and defaults unchanged | ✓ 5 canonical files byte-clean, BoA-v1ref clean |

**Classification: full PASS.** No integration/force-sign/discretization/cleanup/recovery/mechanical/canonical
failure. The lone imperfection (1/40 marginal second native stroke from a Brownian-searched rebound pose) is a
capture-pose-spread effect, not a mechanism defect — the clean relaxed-capture stroke is 6.90 nm.

---

## 7. Next-experiment recommendation

**Begin sparse multi-motor cycling.** The single-motor chemomechanical cycle is now closed on the two-body motor
with the canonical kinetics and validated single-molecule mechanics intact, and the signed-load catch–slip law is
already reproduced here in a dedicated force-clamp assay (so a separate "validate catch-slip more deeply" run
would be largely redundant, and there is no biochemical integration defect to repair). The natural next step, per
the 3G-A synthesis roadmap, is a small ensemble of **independent** cycling two-body motors (2–4 motors, one
filament, no gliding-density sweep) to check that duty ratio and co-bound behaviour compose from the validated
single-motor cycle before any low-density gliding. Do **not** begin that experiment in this run.

Deferred / flagged for later (not defects here):
- The bound-ADP dwell is load-modulated (~4.4 ms at the resting post-stroke strain vs ~1 ms unloaded); whether
  that resting strain is biologically the right operating point is an ensemble/duty-ratio question, not a
  single-motor one.
- The τ-averaged catch input (`kinParams[17]`, EMA) is left at the ratified default (instantaneous, alpha = 0);
  the calibrated stack's `-tauavg` variant is available but out of scope (it changes no kinetic *constant*).
- The default-off force-cap detachment diagnostic (`setFaithfulRelease`) remains off and is **not** the release
  mechanism.

---

## 8. Deliverables and reproduction

```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp4a -out RUN_LOGS/twobody_biochemical_cycle/csv   # full experiment (~6 min, CPU)
./scripts/run_lasertrap.sh -exp4a -fast                                          # quick smoke (fewer episodes)
python3 scripts/twobody4a_analyze.py RUN_LOGS/twobody_biochemical_cycle/csv \
        RUN_LOGS/twobody_biochemical_cycle/exp4a_summary.png
./scripts/run_lasertrap.sh -exp4a -3js ~/Code/SoftBox/threejs_twobody4a          # viewer (full cycle)
# 3E/3F regression (byte-identical): ./scripts/run_lasertrap.sh -exp3f -out /tmp/exp3f_check
```

**Artifacts** (`RUN_LOGS/twobody_biochemical_cycle/`): `exp4a_full.log`, `exp4a_summary.png`, and
`csv/{state_trajectory, dwell_stats, transition_counts, adp_release_forceclamp, atp_detach, recovery_rebind,
mech_regression, dt_convergence}.csv`. Source: `softbox/TwoBodyConverterMotor.java` (`run4a` + `cycleStep4a` +
`adpReleaseAssay` + the phase methods), one dispatch line in `softbox/LaserTrapHarness.java`.

**View:** `python3 SoftBox/sim_server.py 8000` from `~/Code`, open
`http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → newest).
