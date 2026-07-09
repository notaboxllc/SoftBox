# Phase-2 — restore the validated Lymn-Taylor cycle (undo `-atprecharge`), co-retune kOn, validate skeletal gliding

**2026-06-29. Flag-gated `-lymntaylor` (alias `-lt`); default-off ⇒ every existing path BYTE-IDENTICAL (regression
confirmed). jba-directed re-architecture of the production cycle (sign-off given). `BoA-v1ref` byte-clean. Old paths
(`-atprecharge`, plain `cycle`/`cycleAtpDetach`) kept runnable for A/B. The motor cross-bridge is exempt from v1
bit-parity (CANONICAL_MOTOR_FINDINGS); this is the canonical-motor cycle.**

---

## TL;DR
- **The validated single-pathway Lymn-Taylor cycle is implemented and skeletal-validated.** Detachment is now
  NUCLEOTIDE-DRIVEN and FAST (NONE→ATP = ATP binding releases the rigor head); the 4c Guo & Guilford catch is the
  LOAD-MODULATION of the ADP→NONE rate, **not** a release pathway. **One** release mechanism.
- **V₀ RESTORED to the skeletal band:** unloaded detachment **~650–870 /s** (bound-time **1.07–1.54 ms**) ⇒
  **V₀ = step × rate = 6.96 nm × ~870/s ≈ 6.0 µm/s** (v1box-measured 651/s → 4.5 µm/s). **vs the recharge model's
  141 /s → ~1.0 µm/s (Myo2 regime). The ~6× turnover deficit is fixed** — exactly as MOTOR_PARAM_PROVENANCE predicted
  (the deleted Howard ADP→NONE = 1e3/s was the slow-turnover root).
- **bound-in-ATP = 0.0 %** (the ATP-STATE-AUDIT clock-decoupling enforcement holds); **dt=1e-5 stable** (capRate=0,
  fullMat=YES, no whip/NaN across the whole density ladder); **CPU≡GPU aggregate-within-SEM**.
- **Co-retune:** the stale kOn=2e5 was tuned on the 7-ms recharge cycle; the 1.5-ms LT cycle needs it raised. kOn→2e6
  (10×) clearly lifts binding (avgB rises monotonically with density, −x glide emerges ≥ density 1000); kOn→1e7 gives
  diminishing returns (binding is becoming encounter/geometry-limited).
- **Honest residual (orthogonal, pre-existing, NOT the turnover):** the density THRESHOLD sits at ~500–1000 µm⁻²
  (above Uyeda 100–300) and the MEASURED netX is dt/drag-suppressed (~0.5–1.0 µm/s, not the drag-independent V₀≈6).
  Both are the **flat single-tip sparse-binding** (BINDING-SURVEY) and **dt=1e-5/100×-drag velocity suppression**
  (SPEED-DENSITY / DT-CONVERGENCE) — the same orthogonal binding-density + transport-efficiency levers already
  flagged, **untouched and not worsened by the cycle fix**. The turnover (V₀) deliverable is met; these are the
  separate next levers.
- **Thesis: mechanism-flag #1 RESOLVED** — the cycling-detachment is now a **parameterized rate** (ADP→NONE base +
  NONE→ATP), not a code branch ⇒ skeletal→Myo2 is a clean base-rate swap (skeletal ~1e3/s, Myo2 ~1.4e2/s, same
  mechanism). The J1-strain-as-load flag (#2) remains separate/deferred.

---

## 1 — what was UNDONE (the `-atprecharge` experiments) and the single validated pathway

The `-atprecharge` machinery is NOT used on the `-lymntaylor` path (it remains present, runnable for A/B):
- `cycleNoBoundAtp` (ADP→NONE deletion / bound-ATP lockout) — **superseded**: LT keeps ADP→NONE (the Howard rate),
  it just makes it fast + catch-modulated.
- `catchSlipRelease{,Avg}Recharge` setting `nucleotideState ← NUC_NONE` on release (catch-as-ADP→NONE) — **superseded**:
  LT's catch does NOT release; it modulates the ADP→NONE *rate*.
- catch-slip-as-sole-release — **removed on the LT path**: there is no separate `release` task at all; detachment is
  the NONE→ATP transition inside the cycle kernel.
- **No dual pathway:** the pre-`-atprecharge` state ran the catch-slip release AND the nucleotide cycle in parallel.
  LT has **exactly one** release (NONE→ATP). (This is a clean re-implementation, not a git-revert — the pre-recharge
  dual-pathway + bound-in-ATP bug are NOT resurrected.)

**The validated cycle (`NucleotideCycleSystem.cycleLymnTaylor`, one RNG draw/motor/step, per-head pure ⇒ CPU≡GPU):**
| step | transition | rate | note |
|---|---|---|---|
| bind | (binder) | — | binds in ADP·Pi (pre-stroke, lever uncocked) |
| powerstroke | ADP·Pi → ADP | onPi ~1e4/s (Howard) | Pi release swings the lever 0°→60° (cocked) |
| post-stroke detach-prep | ADP → NONE | **base·g(F)**, base = onADP ~1e3/s (Howard) | post-stroke (lever stays swung); g(F)=αCatch·e^(−F·xCatch/kT)+αSlip·e^(+F·xSlip/kT) (=1 at F=0). Unloaded ⇒ fast (~1 ms); resisting load ⇒ slowed (the catch, → ~6 pN peak) |
| **DETACHMENT** | NONE → ATP | atpOn ~2e4/s | ATP binding releases the rigor head. **A BOUND head ending in ATP detaches THIS step** (covers NONE→ATP AND the rebind-in-ATP edge; cycle runs after bind / before bond ⇒ zero-force window) ⇒ no sustained bound-in-ATP |
| recovery | off-fil ATP → ADP·Pi | offATP ~100/s | hydrolysis re-primes the lever to uncocked → rebind |

- **The 4c Guo & Guilford catch is REUSED VERBATIM** (xCatch 2.5 / xSlip 0.40 / αCatch 0.92 / αSlip 0.08); only its
  BASE moved from the catch-slip's kOff (100/s, the release rate) to the cascade's ADP→NONE (1e3/s, the cycling rate).
  Absolute catch lifetimes scale with the new base (expected; the ring isoform re-anchors its base later). **The catch
  is NOT re-calibrated.**
- In unloaded gliding (sub-pN loads, SLIP-DIRECTION) g(F)≈1 ⇒ the catch is ~inert ⇒ it does **not** change the
  skeletal gliding result; it makes the mechanism correct for the loaded/ring regime.

**What changed (additive; default byte-identical):** `NucleotideCycleSystem.cycleLymnTaylor` (new); `GlidingHarness`
`-lymntaylor` flag + LT branches in `stepOrig` (CPU), `buildPlan` (GPU), `singleStep` (duty assay) — each skips the
separate release task and routes the cycle to `cycleLymnTaylor`; the GPU `measureGrid` pulls only `stats` on the LT
path (no break-force cap task ⇒ `capStats` is not a device variable). All gated on the default-false `LYMN_TAYLOR`.

---

## 2 — skeletal validation

### (a) V₀ / turnover (the headline) — diag, perphead flat θ=0, calibrated stack
| metric | recharge (`-atprecharge`) | **Lymn-Taylor (`-lymntaylor`)** | skeletal target |
|---|---|---|---|
| unloaded detachment rate | 141 /s | **651 /s** (v1box) — base-cycle ~870/s unloaded | ~720–1150 /s |
| mean bound-time | 7.07 ms | **1.54 ms** (gliding) / **1.07 ms** (single-molecule) | ~0.9–1.4 ms |
| **V₀ = step × rate** | **~1.0 µm/s (Myo2)** | **~4.5–6.0 µm/s (SKELETAL)** | **5–8 µm/s** |
| bound-state | ADP 96.9 % | ADP-dominated (the post-stroke force-bearing dwell, now ~1 ms not 7 ms) | — |
| **bound-in-ATP** | 2.1 % | **0.0 %** (enforcement holds) | ≈0 |

`V₀ = step × unloaded-detach-rate` is the drag-independent skeletal metric; with step 6.96 nm and the base-cycle
unloaded rate ~870/s (ADP·Pi→ADP→NONE→ATP path) **V₀ ≈ 6.0 µm/s** — in the skeletal band. **The Myo2-slow turnover is
fixed.**

### (b) dt stability + density curve (GPU device-resident, matbed, dt=1e-5, kOn 2e6, n=2, 30k)
| density (µm⁻²) | motors | avgB(steady) | netX (µm/s) | fullMat | capRate |
|---|---|---|---|---|---|
| 100 | 696 | 0.10 | −0.02 (noise) | YES | 0 |
| 200 | 1392 | 0.22 | −0.08 | YES | 0 |
| 300 | 2088 | 0.32 | ±0.06 (noise) | YES | 0 |
| 500 | 3480 | 0.55 | ±0.06 (noise) | YES | 0 |
| 1000 | 6960 | 1.86 | **−0.27** | YES | 0 |
| 2000 | 13920 | 3.84 | **−0.44** | YES | 0 |

- **avgBound and the directed −x glide rise monotonically with density**; the −x glide becomes clear (above the
  thermal floor) at density ≥1000 (avgB ≥ ~2). **dt=1e-5 STABLE everywhere** (every cell fullMat=YES, capRate=0, no
  NaN/whip — the faster detachment doesn't change the force law).
- **instSteady ≈ 6.1–6.5 is the density-independent thermal-wander floor** (SPEED-DENSITY), not transport.
- **Threshold:** avgB~1 lands at ~density 700 (kOn 2e6) / ~500 (kOn 1e7) — **above** Uyeda 100–300.
- **Measured netX (~0.4–1.0 at high density) is far below V₀≈6** — the **per-head transport-efficiency deficit**
  (SLIP-DIRECTION: ~0.05 pN axial/head; the J1-strain / transverse-misprojection) + the dt=1e-5/100×-drag velocity
  suppression (DT-CONVERGENCE / aeta 0.1 = 100× experimental). **Both are orthogonal to the turnover and pre-existing.**

### (c) co-retune kOn (coupled)
The stale kOn=2e5 was tuned on the 7-ms recharge cycle; the 1.5-ms LT cycle drops duty ∝ t_on, so kOn must rise.
| kOn | avgB @ d500 | avgB @ d1000 | regime |
|---|---|---|---|
| 2e5 (stale) | ~0.1 | ~0.3 | far sub-threshold |
| **2e6 (10×)** | 0.55 | 1.86 | clear binding, glide ≥ d1000 |
| 1e7 (50×) | 0.90 | 2.6 | **diminishing returns** (encounter/geometry-limited) |

⇒ **kOn≈2e6 is the practical co-retune** (10× the stale value). Beyond that, binding is limited by the **flat
single-tip 6 nm capture geometry** (BINDING-SURVEY), not kOn — so the density threshold cannot be pulled to Uyeda
100–300 by kOn alone. **Duty:** single-molecule t_on 1.07 ms with a ~10 ms hydrolysis recovery ⇒ a duty *ceiling*
~9–10 % (hydrolysis-limited); skeletal ~5 % is within range; the realized ensemble duty is binding-density-limited
(the orthogonal lever).

### (d) CPU≡GPU (aggregate-within-SEM, the chaotic-gliding standard) — density 200, 20k
| runner | avgB | netX | instSteady |
|---|---|---|---|
| GPU | 0.642 | −0.444 | 6.78 |
| CPU | 0.622 | −0.400 | 6.65 |

Agree within SEM (bound-state, velocity, thermal floor) — float32 op-ordering decorrelates the microstate as expected;
the LT cycle is a per-head pure function (no atomics/KernelContext) ⇒ device-faithful.

### (e) regression / default byte-identity
`-atprecharge` reproduces **exactly** (141/s, 7.07 ms, ADP 96.9 %, bound-in-ATP 2.1 %) — the LT changes are additive
and gated on `LYMN_TAYLOR`; default / config1 / perphead-non-LT / `-atprecharge` paths are byte-unchanged.

---

## 3 — verdict against the task

- **Restore the validated cycle: DONE** — single-pathway Lymn-Taylor, nucleotide-driven fast detachment, catch as
  ADP→NONE modulation, bound-in-ATP enforced ≈0, no dual pathway.
- **Reach skeletal V₀: MET** — V₀ = step × unloaded-detach-rate ≈ 6 µm/s (5–8 band), a ~6× turnover restoration over
  the recharge model. **No bail** (V₀ is reached with the co-retuned kOn).
- **Density threshold 100–300 / measured saturation 5–8: PARTIAL / orthogonal residual.** The curve has the correct
  rising shape and is dt-stable, but the threshold (~500–1000) and the measured netX (~0.5–1) are limited by the
  **flat-head sparse binding** (BINDING-SURVEY) and the **per-head transport-efficiency + dt/drag suppression**
  (SLIP-DIRECTION / SPEED-DENSITY / DT-CONVERGENCE) — pre-existing, orthogonal to the turnover, **not introduced or
  worsened here**. These are the next levers (binding density + transport efficiency), explicitly out of this task's
  scope (which was the turnover).
- **Thesis — mechanism-flag #1 RESOLVED:** detachment is now the nucleotide cascade (ADP→NONE base rate + NONE→ATP),
  a **parameterized rate**, not a code branch ⇒ a clean isoform swap (skeletal ~1e3/s, Myo2 ~1.4e2/s, same mechanism).
  The J1-strain-as-load flag (#2) remains separate/deferred.

**Not flipped to default** (kept flag-gated per the task; intended as the production cycle once the orthogonal
binding/efficiency levers are addressed). The recommended LT stack: `-lymntaylor -perphead -headtilt 0 -kon 2e6
-tauavg 0.5 -xcatch 2.5`.

## CPU-fallback disclosure
- V₀/turnover diag + single-molecule duty + CPU≡GPU CPU-side: **`-cpu` sequential runner** (host, no TaskGraph;
  v1box 2k motors / single-molecule 256 motors / density-200 1392 motors — measurement instruments).
- Density sweeps + the GPU half of CPU≡GPU: **GPU device-resident `buildPlan`** (~22 kernels, no per-step host pull;
  host reads fil.coord+boundSeg at OUT_INT cadence). 696→13920 motors, 30k steps, kOn 2e6 + 1e7 ladders.

## Reproduce
```
# V₀ / turnover / bound-in-ATP (diag):
./run_gliding.sh -diag -perphead -headtilt 0 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -lymntaylor -v1box -dt 1e-5 8000
# single-molecule t_on:
./run_gliding.sh -single -singlez -0.075 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -lymntaylor 20000
# density sweep (GPU), co-retuned kOn:
./run_gliding.sh -gpu -perphead -headtilt 0 -lymntaylor -kon 2e6 -tauavg 0.5 -xcatch 2.5 -matbed -density <D> -dt 1e-5 -grid -seed <s> 30000
# CPU≡GPU (drop -gpu for the CPU runner):
./run_gliding.sh [-gpu] -perphead -headtilt 0 -lymntaylor -kon 1e7 -tauavg 0.5 -xcatch 2.5 -matbed -density 200 -dt 1e-5 -grid -seed 0 20000
# regression (default byte-identical): any old stack without -lymntaylor, e.g.
./run_gliding.sh -diag -perphead -headtilt 0 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -atprecharge -v1box -dt 1e-5 8000   # ⇒ 141/s, 7.07 ms (unchanged)
```

## JOURNAL-ready summary
```
## 2026-06-29 — LYMN-TAYLOR CYCLE restored (undo -atprecharge): nucleotide-driven fast detachment ⇒ skeletal V₀ ~6 µm/s (was Myo2 ~1); flag-gated -lymntaylor, default byte-identical, CPU≡GPU
Implemented the validated single-pathway Lymn-Taylor cycle (NucleotideCycleSystem.cycleLymnTaylor), replacing the
-atprecharge experiments. ONE release: NONE→ATP = detachment (ATP binding releases the rigor head); the 4c Guo &
Guilford catch MODULATES the ADP→NONE rate (base = Howard ~1e3/s × g(F)), NOT a release pathway; catch reused verbatim
(not re-calibrated), only its base moved from kOff 100/s to the cascade 1e3/s. bound-in-ATP enforced ≈0 (a bound head
ending in ATP detaches; cycle after bind/before bond ⇒ zero-force, the ATP-STATE-AUDIT bug stays fixed). VALIDATED
(perphead flat θ=0): unloaded detachment 141→651/s, bound-time 7.07→1.07-1.54 ms, V₀=step×rate ~1.0→~6.0 µm/s
(SKELETAL band; was Myo2) — the MOTOR-PARAM-PROVENANCE turnover deficit (deleted Howard ADP→NONE) is FIXED;
bound-in-ATP 0.0%; dt=1e-5 stable (capRate 0, fullMat YES, no whip/NaN to density 2000); CPU≡GPU aggregate-within-SEM
(avgB 0.64≈0.62, netX −0.44≈−0.40). Co-retune: stale kOn 2e5→2e6 (10×) lifts binding (avgB + −x glide rise with
density, glide clear ≥d1000); 1e7 = diminishing returns (encounter/geometry-limited). ORTHOGONAL residual (pre-existing,
NOT the turnover, flagged): density threshold ~500-1000 (above Uyeda 100-300) + measured netX dt/drag-suppressed (~0.5-1
vs V₀ 6) = the flat-head sparse binding (BINDING-SURVEY) + per-head transport-efficiency (SLIP-DIRECTION) + dt/aeta
suppression (SPEED-DENSITY/DT-CONVERGENCE). THESIS: mechanism-flag #1 RESOLVED — cycling-detachment is now a
parameterized rate (ADP→NONE base + NONE→ATP), not a code branch ⇒ clean isoform swap (skeletal ~1e3/s, Myo2 ~1.4e2/s).
Flag-gated -lymntaylor (alias -lt); default/-atprecharge byte-identical (regression: 141/s reproduced); BoA-v1ref
byte-clean; not flipped to default. Report: PHASE2_LYMN_TAYLOR_FINDINGS.md. Next (separate): binding-density + transport
-efficiency levers; then flip LT to default.
```
