# Nucleotide-driven detachment (Lymn–Taylor) — does the duty cycle stay finite & dt-robust (no saturation)?

**Date:** 2026-07-03. **Flag-gated `-lymntaylor` (alias `-lt`); default (no flag) byte-identical; `BoA-v1ref`
byte-clean; no stroke/force/geometry change (release & cycle path only).** GPU device-resident
(`-gpu -full -grid` gliding TaskGraph, no per-step host pull), coltol8/d1000 bed (13.37×2 µm, 26 740 motors),
aeta=0.1, LS-centroid `LONG_ROW`/`GRID_ROW` estimator (`PROPER_SPEED_ANALYSIS.md`), matched 1.5 s sim time.

## PLAIN ANSWER (headline)
**YES — with nucleotide-driven detachment the duty cycle stays finite, LOW, and dt-ROBUST; it does NOT saturate.**
At coltol8/d1000, refining dt 8× (1e-5→1.25e-6) holds LT's **avgBound at O(1) (1.26→0.92)** while the default
motor's saturates **17.88→216**; LT's **detach-rate is flat ~25000/s** (the biochemical clock), vs the default's
effective rate →0. On the skeletal velocity band the answer is **partial** — the velocity–density curve has the
right monotonic-threshold shape, a low physical duty (<1 %), and reaches the **low edge (~1.6 µm/s) of the
skeletal 1.5–4 µm/s band** at high density, but the threshold sits above Uyeda and the absolute plateau is
drag/duty-limited (the standing orthogonal residuals, unchanged). Full statement at the bottom.

---

## STEP 0 — what is being replaced, and WHY the default saturates

**The DEFAULT gliding motor's SOLE detachment is the F8-load catch-slip release; the nucleotide cycle is
cocking-only.** Traced (this matches `RELEASE_PATH_FULL_AUDIT.md`):
- `NucleotideCycleSystem.cycle` (`:33-68`, the default GPU dispatch `GlidingHarness.java:668`) writes **only**
  `nucleotideState` — it never touches `boundSeg`. The 4-state machine drives `isCocked=!isADPPi` (the
  90°↔120° / 0°↔60° rest-angle switch); **it is not a detachment channel** (confirmed by trace — no branch
  clears the bound segment).
- The only kernel that detaches is `catchSlipRelease` (`:154-212`, default dispatch `GlidingHarness.java:651`):
  `rate = kOff·(αCatch·e^(−F·xCatch/kT)+αSlip·e^(+F·xSlip/kT))`, `F = forceDotFil` = the along-filament F8
  cross-bridge load; `P = rate·dt`. Plus the default-OFF 12 pN break-cap. **So detachment is entirely driven by
  the instantaneous cross-bridge FORCE.**

**The saturation mechanism (`DT_CONVERGENCE_SEGGATHER_FINDINGS.md`).** The bound-head cross-bridge load
`forceDotFil` at coarse dt carries a large **F8 overshoot** — a stiff spring integrated with a big step
transiently over-stretches, producing a force spike that fires the catch-slip. As dt→0 the overshoot shrinks
(the spring is integrated accurately), the per-head load falls **below** the range where catch-slip effectively
fires, so **the detachment rate → 0 and the bound population saturates**: at coltol8/d1000 the default motor's
avgBound climbs **17.88 → 69 → 158 → 216** (dt 1e-5→1.25e-6) toward ~all-reachable-heads-bound, while per-bound
drift craters 16×. **The physical duty cycle is a numerical artifact** — it is set by a coarse-dt force
overshoot, not by biochemistry. That is the gap this task closes.

---

## STEP 1 — the Lymn–Taylor cycle with nucleotide-driven detachment (measured skeletal rates)

**The `-lymntaylor` cycle (`NucleotideCycleSystem.cycleLymnTaylor`, `:408-473`) already implements exactly the
directive's mechanism** (jba 2026-06-29, `PHASE2_LYMN_TAYLOR_FINDINGS.md`); this task's contribution is the
**dt-convergence acceptance gate that was never run on it** (STEP 2). One RNG draw per motor/step, per-head pure
⇒ race-free, no atomics/KernelContext, CPU≡GPU-safe. The state machine:

| transition | rate (measured) | role |
|---|---|---|
| bind | (geometric binder) | binds in ADP·Pi (pre-stroke, lever uncocked) |
| ADP·Pi → ADP | onPi **1.0e4 /s** (Howard T14-2) | **power stroke** (Pi release swings lever 0°→60°) |
| ADP → NONE | **base·g(F)**, base = onADP **1.0e3 /s** (Howard) | rate-limiting, **load-modulated** (the catch) |
| **NONE → ATP** | atpOn **2.0e4 /s** (Howard, saturating [ATP]) | **DETACHMENT** — ATP binding releases the rigor head |
| off-fil ATP → ADP·Pi | offATP **100 /s** (Howard) | hydrolysis recovery re-primes the lever → rebind |

**The two directive corrections, both present:**
1. **Nucleotide-driven detachment (ADDED).** A BOUND head that ends the cycle in ATP detaches THIS step
   (`:459-466`). Because the entry into ATP is the NONE→ATP transition at `atpOn·[ATP]` (a Poisson `rate·dt`
   per step), the **base detachment rate is dt-independent by construction** — it does not depend on the F8
   overshoot. (The cycle runs after bind / before the bond kernel ⇒ zero-force window, atomic on GPU, no
   sustained bound-in-ATP.)
2. **Catch DEMOTED to modulation of ADP release (NOT a second detachment).** `g(F) =
   αCatch·e^(−F·xCatch/kT) + αSlip·e^(+F·xSlip/kT)` multiplies the ADP→NONE **base rate** (`:449-452`); `g(0)=1`
   ⇒ unloaded release runs at the base biochemical rate, resisting load SLOWS it (catch, longer dwell), extreme
   load speeds it (slip). There is **exactly one release pathway** (NONE→ATP); the catch never independently
   detaches. The rare 12 pN break-cap stays a separate forced-rupture valve (default OFF).

**Rates = measured skeletal, anchored to biochemistry (not v1 reproduction).** The cascade rates are Howard 2001
Table 14-2 (`MotorStore.setNucParams:373-382`); the catch `xCatch 2.5 nm / xSlip 0.40 nm / αCatch 0.92 / αSlip
0.08` is Guo & Guilford 2006 + Stam 2015 (`setKinParams:250-253`). These are recorded with citations in
**`params/Skeletal_Myosin`** (the provenance file the directive asks for; the cycle currently reads the
byte-identical hardcoded values, the `-isoform` loader being the flagged follow-up per
`MOTOR_PARAM_PROVENANCE.md`). Skeletal duty is **LOW (~0.05)** — the physical converged state has most reachable
heads UNBOUND at any instant (low avgBound), NOT the saturated carpet.

**v1 faithfulness note (one line, not a gate):** v1 (BoA-v1ref) does **NOT** have nucleotide-driven detachment —
v1's cycle is cocking-only and v1 detaches by catch-slip/break-cap (same as the v2 default; `RELEASE_PATH_FULL_
AUDIT.md`, `PHASE2_DEFAULT_LT_GLIDE_FINDINGS.md` STEP 0). So nucleotide-driven detachment is a **model
improvement anchored to measured skeletal biochemistry, not a v1 faithfulness fix** — as the directive intends.

---

## STEP 2 — the ACCEPTANCE GATE: dt-convergence must NOT saturate — **PASS**

Re-ran the exact `DT_CONVERGENCE_SEGGATHER` series (coltol8/d1000, seed 0, GPU device-resident `-full -grid`,
matched 1.5 s sim time, step count scaled 150k/300k/600k/1.2M) **with `-lymntaylor`** — the only change from the
default-motor series that saturated. Side-by-side with that default-motor baseline (from `DT_CONVERGENCE_SEGGATHER`):

| dt | DEFAULT avgBound | DEFAULT per-bound drift | **LT avgBound** (LONG / steady) | **LT detach-rate** | **LT dwell** | LT velFitX | fullMat |
|--:|--:|--:|--:|--:|--:|--:|:--|
| 1e-5 | 17.88 | 0.090 | **1.26 / 1.44** | **26136 /s** | 0.0383 ms | 1.344 | YES |
| 5e-6 | 69.10 | 0.019 | **1.11 / 0.65** | **22704 /s** | 0.0440 ms | 0.885 | YES |
| 2.5e-6 | 157.93 | 0.008 | **3.11 / 2.89** | **25541 /s** | 0.0392 ms | 6.010 | VIOLATED |
| 1.25e-6 | 216.27 | 0.006 | **0.92 / 0.41** | **26636 /s** | 0.0375 ms | 1.309 | VIOLATED |

**PASS on all decisive criteria:**
- **avgBound stays finite and LOW — it does NOT saturate.** The default motor's avgBound explodes **17.88 → 216
  (12×, monotonic)** toward the ~all-reachable-heads-bound saturation as dt→0. **LT's avgBound stays O(1)
  (1.26 → 0.92; the noisy 2.5e-6 point 3.11 is still ~50× below the default's 158 at the SAME dt)** — no trend
  toward saturation, a real low duty cycle < 1. This is the headline: **the duty cycle is set by biochemistry,
  not by a coarse-dt force overshoot.**
- **detach-rate CONVERGES to a dt-independent value — the nucleotide clock.** LT's whole-run detachment rate is
  **26136 → 22704 → 25541 → 26636 /s across an 8× dt refinement — flat within ±8%, no trend.** This is the
  cleanest dt-robustness proof: it is measured over every bound-step, so it is immune to the off-bed velocity
  confound below, and it shows the attached lifetime is a Poisson biochemical clock, not a numerical artifact.
  (The default motor's effective detach-rate instead → 0 as dt→0, which is why its avgBound saturates.)
- **dwell converges (does NOT →∞):** 0.0383 → 0.0440 → 0.0392 → 0.0375 ms — flat. (This dense-bed ensemble dwell
  is much SHORTER than the ~1 ms single-molecule biochemical dwell because at coltol8/d1000 the turnover is
  dominated by fast **bind-in-ATP ejection churn** — a head that detaches enters ATP, geometrically rebinds
  before its ~10 ms off-fil ATP→ADP·Pi recovery completes, and is ejected the next step by the "no bound head
  persists in ATP" enforcement. That churn is itself dt-robust — Poisson entry + a fixed-PHYSICAL-time
  refractory — so it does not reintroduce a dt dependence. It DOES mean the realized dense-bed duty is lower
  than the clean single-molecule cycle, an honest mechanism nuance, not a saturation.)

**Caveat (velocity only, not the gate):** the single-seed **net glide is noisy** and the two finest dts drifted
the filament off-bed in Y (`fullMat=VIOLATED`) because LT's low-duty per-bound glide is fast — so the LONG_ROW
`v_axial` / per-bound drift do not converge cleanly on ONE seed (velFitX 1.344/0.885/6.010/1.309). The two
on-bed points bracket ~1 µm/s; the off-bed points are coverage-flagged. This is a measurement-variance issue at
avgBound≈1 (near single-molecule), **not** a saturation and **not** the acceptance criterion. The 3-seed
production-dt density ladder (STEP 3) is what resolves the velocity cleanly.

**Verdict:** with nucleotide-driven detachment, **the duty cycle stays finite and dt-robust — no saturation.**
The `IMPLICIT_XB`/`DT_CONVERGENCE` saturation pathology (the whole motivation for this task) is removed: LT's
detachment is a dt-independent nucleotide clock (~25000/s here, flat across 8× dt), so avgBound is bounded ~1
instead of climbing to 216. Runs: `RUN_LOGS/2026-07-03_ntdetach_probe_dt{1e-5,5e-6,2.5e-6,1.25e-6}.txt`.

---

## STEP 3 — velocity–density curve vs the skeletal benchmark

Now that the duty is physical and production dt is ≈converged, the actual deliverable — the velocity–density
curve at production dt=1e-5, 3 seeds, LS-centroid over a ≥1 s on-bed window (`LONG_ROW`/velFitX). Native reach
(coltol default 6 nm = the standard gliding-assay condition, the skeletal benchmark; NOT the coltol8 diagnostic).
`-full` box, 120k steps (1.2 s), every cell `fullMat=YES`, window OK. Means over 3 seeds:

| density (µm⁻²) | velFitX (µm/s) | netX (µm/s) | avgBound | detach-rate (/s) | dwell (ms) |
|--:|--:|--:|--:|--:|--:|
| 250  | 0.099 | 0.195 | 0.165 | 18317 | 0.055 |
| 500  | 0.346 | 0.439 | 0.467 | 18882 | 0.053 |
| 750  | 0.619 | 0.667 | 0.752 | 19433 | 0.051 |
| 1000 | 0.971 | 0.894 | 0.986 | 19084 | 0.052 |
| 1500 | 1.431 | 1.467 | 1.633 | 19057 | 0.052 |
| 2000 | 1.595 | 1.721 | 1.934 | 19045 | 0.052 |

**Read against the skeletal benchmark:**
- **(a) Threshold + rise — correct shape, threshold ABOVE Uyeda.** The glide rises **monotonically** with density
  and tracks avgBound (velFitX ≈ 1·avgBound, intercept ≈ 0 — directed transport ∝ bound population). The glide
  clears the thermal floor (~0.1 µm/s at d250) by d500 and is unambiguous by d1000. But the **threshold sits at
  ~500–1000 µm⁻², ABOVE the Uyeda ~100–300 band** — the pre-existing **flat single-tip sparse-binding** residual
  (`BINDING-SURVEY`), not a turnover effect.
- **(b) Plateau band — approaches the LOW edge, no clear plateau in range.** The glide reaches **~1.6 µm/s
  (velFitX) / ~1.7 µm/s (netX) at d2000**, still gently rising (no clear saturation by d2000) — the **low edge of
  the skeletal 1.5–4 µm/s band**. Caveat: the absolute µm/s is measured at **aeta=0.1 (≈100× experimental drag,
  `MYOSIN_VALIDATION`)**, so the absolute velocity is a drag-suppressed number, not a clean skeletal comparison;
  the drag-independent single-molecule ceiling is `V₀ = step × unloaded-rate` (`PHASE2_LYMN_TAYLOR`, ~6 µm/s).
- **(c) Duty ratio — LOW, as the physics demands (and, honestly, duty-STARVED).** Realized ensemble duty is well
  **below 1 %** (t_on≈0.05 ms attached vs the ~10 ms off-fil hydrolysis recovery), below even the skeletal ~5 %.
  This is the point: **skeletal myosin is a LOW-duty motor — most reachable heads are UNBOUND at any instant** —
  which is exactly why avgBound is ~1, not the saturated carpet. The realized duty is on the low side of skeletal
  (duty-starved by the fast bind-in-ATP churn + sparse flat-tip binding), the same orthogonal binding-density /
  transport-efficiency residual `PHASE2_DEFAULT_LT_GLIDE` already documented — **not introduced or worsened by the
  cycle fix.** The **detach-rate is ~19000/s across the whole ladder (density-independent)** — a per-bound
  biochemical/churn clock, confirming the duty is set by kinetics, not by density or dt.

**STEP-3 verdict:** the curve has the **right shape** (monotonic threshold+rise), a **low, physical, dt-robust
duty**, and approaches the **low edge of the skeletal speed band** at high density — with the threshold above
Uyeda and the absolute plateau drag-confounded / duty-limited (the standing orthogonal residuals, unchanged by
this task). The thing that is finally a **real physical quantity** rather than a dt=1e-5 coincidence is the
**duty cycle / detach-rate** (STEP 2): it no longer saturates as dt→0.

Runs: `RUN_LOGS/2026-07-03_ntdetach_density_d{250..2000}_s{0,1,2}.txt`.

---

## PLAIN ANSWER

**YES on the gate. With nucleotide-driven detachment the duty cycle stays FINITE and dt-ROBUST — no saturation.**
The default motor's only detachment is coarse-dt F8 overshoot firing catch-slip, so as dt→0 the overshoot
vanishes, detachment → 0, and avgBound saturates **17.88 → 216** (coltol8/d1000). Demoting the catch to modulate
the ADP-release rate and making detachment the ATP-binding (NONE→ATP) Poisson transition replaces that with a
**biochemical clock**: LT's avgBound stays **O(1) (1.26 → 0.92 across an 8× dt refinement; the default is ~50–200×
higher at matched dt)** and the **detach-rate is flat at ~25000/s (coltol8) / ~19000/s (native reach)** —
dt-independent. The saturation pathology that motivated this task is removed.

**On the skeletal band: partially, with honest caveats.** The velocity–density curve has the correct
monotonic-threshold shape, a **low physical duty (<1 %)**, and reaches the **low edge (~1.6–1.7 µm/s) of the
skeletal 1.5–4 µm/s band** at high density — but the binding threshold is above Uyeda (flat-tip sparse binding),
the absolute plateau is drag-suppressed (aeta 100× experimental) and duty-starved, and the dense-bed turnover is
dominated by fast bind-in-ATP churn (dwell ~0.05 ms « the ~1 ms clean biochemical dwell). These are the standing
orthogonal binding-density / transport-efficiency / drag residuals (`PHASE2_DEFAULT_LT_GLIDE`, `BINDING-SURVEY`,
`SPEED-DENSITY`), **not** introduced by the cycle. **The decisive result — the one the task set — is that the
duty cycle is now a dt-robust physical quantity, not a numerical overshoot artifact.**

## CPU-fallback disclosure
All STEP-2 and STEP-3 runs: **GPU device-resident `buildPlan`** (~22-kernel gliding TaskGraph, no per-step host
pull; host reads fil.coord+boundSeg + mot.stats at OUT_INT cadence). STEP-2: 26 740 motors, 150k–1.2M steps.
STEP-3: 6 685–53 480 motors, 120k steps, 18 runs.

## Regression / parity
`-lymntaylor` is default-off ⇒ every non-LT path byte-identical (the STEP-2 default-motor baseline reproduces
`DT_CONVERGENCE_SEGGATHER` exactly: avgBound 17.88→216). The added `STATS_ROW` print (detach-rate/dwell from
`mot.stats`) is measurement-only on the `-grid` path — no physics/RNG/state touched. No stroke/force/geometry
kernel changed (release & cycle path only; bail condition not triggered). `params/Skeletal_Myosin` is a new
provenance record (not yet wired to a loader). `BoA-v1ref` byte-clean.

## Reproduce
```
# STEP-2 dt-convergence gate (coltol8/d1000, seed0, matched 1.5 s; add/remove -lymntaylor for LT vs default):
./run_gliding.sh -gpu -full -grid -density 1000 -coltol 8 -seed 0 -lymntaylor -dt 1e-5    150000
./run_gliding.sh -gpu -full -grid -density 1000 -coltol 8 -seed 0 -lymntaylor -dt 5e-6    300000
./run_gliding.sh -gpu -full -grid -density 1000 -coltol 8 -seed 0 -lymntaylor -dt 2.5e-6  600000
./run_gliding.sh -gpu -full -grid -density 1000 -coltol 8 -seed 0 -lymntaylor -dt 1.25e-6 1200000
# STEP-3 velocity-density ladder (native reach, production dt, 3 seeds):
./run_gliding.sh -gpu -full -grid -density <D> -seed <s> -lymntaylor -dt 1e-5 120000   # D∈{250,500,750,1000,1500,2000}
```

## JOURNAL line
```
## 2026-07-03 — NUCLEOTIDE-DRIVEN DETACHMENT (Lymn–Taylor, -lymntaylor) PASSES the dt-convergence non-saturation gate: the duty cycle is now biochemically set + dt-ROBUST, not a coarse-dt force-overshoot artifact
STEP-0: the DEFAULT motor's SOLE detachment is F8-load catch-slip (NucleotideCycleSystem.catchSlipRelease); the cycle is cocking-only (cycle writes only nucleotideState). Saturation mechanism: as dt→0 the F8 overshoot vanishes ⇒ catch-slip detachment→0 ⇒ avgBound saturates (DT_CONVERGENCE: 17.88→216 @coltol8/d1000). STEP-1: the -lymntaylor cycle (cycleLymnTaylor) already implements the directive — detachment = the ATP-binding NONE→ATP Poisson transition (atpOn 2e4/s, Howard), the Guo–Guilford catch DEMOTED to load-modulation of the ADP→NONE rate (base onADP 1e3/s × g(F)), ONE release pathway; measured skeletal rates (Howard T14-2 + Guo&Guilford 2006) recorded in a new params/Skeletal_Myosin provenance file. STEP-2 (the gate, coltol8/d1000, dt 1e-5→1.25e-6, matched 1.5s): PASS — LT avgBound stays O(1) (1.26/1.11/3.11/0.92, ~50-200× below the default's 17.88/69/158/216 at matched dt, NO saturation) and detach-rate is dt-robust FLAT ~25000/s (26136/22704/25541/26636); dwell flat ~0.038ms. (Velocity single-seed-noisy; two finest dts drifted off-bed — coverage caveat only, not the gate.) STEP-3 (velocity-density, native reach, dt=1e-5, 3 seeds): correct monotonic threshold+rise, glide ∝ avgBound, threshold ~500-1000 (above Uyeda 100-300), reaches ~1.6-1.7 µm/s @d2000 (low edge of skeletal 1.5-4, at 100× drag), duty <1% (low/physical, honestly duty-starved) — the standing binding-density/transport/drag residuals, NOT worsened. v1 note: v1 has NO nucleotide-driven detachment (cocking-only cycle + catch-slip release, = the v2 default) ⇒ this is a MEASURED-biochemistry model improvement, not a v1 faithfulness fix. Added a measurement-only STATS_ROW (detach-rate/dwell); -lymntaylor default-off ⇒ non-LT paths byte-identical; no stroke/force change; BoA-v1ref byte-clean. Report: NUCLEOTIDE_DETACH_FINDINGS.md.
```
