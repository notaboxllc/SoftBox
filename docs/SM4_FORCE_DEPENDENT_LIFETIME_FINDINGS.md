# SM4 — Force-dependent attachment lifetime (the `xCatch` assay): apparatus build + validation

**Status: APPARATUS COMPLETE AND VALIDATED. Smoke data only — NOT calibration data.**
**Date: 2026-07-21 · code rev `73d82bf` · config hash `f503ab717e9f74ea` · CPU-only.**
**The concurrent GPU single-head density sweep was NOT disturbed (see §7).**

---

## 1. What was built

An apparatus that measures actomyosin **attachment lifetime** under a **constant axial load** from a
**named prepared nucleotide state**, suitable for later calibration of `xCatch`, `xSlip` and the
unloaded catch/slip pathway rates.

| artefact | role |
|---|---|
| `softbox/Sm4ForceLifetimeHarness.java` | the assay: prepare → clamp → observe first passage |
| `scripts/sm4_survival.py` | Kaplan–Meier / Greenwood / RMST / hazard core (numpy only, self-tested) |
| `scripts/sm4_analysis.py` | event JSON → `event_table.csv`, `survival_summary.csv`, `kaplan_meier.csv`, `hazard.csv`, `ANALYSIS.md` |
| `scripts/run_sm4.sh` | runner (`-selftest`, `-smoke`, `-grid`) |
| `RUN_LOGS/motor_validation/sm4_force_lifetime/` | smoke outputs (560 events, 14 cells) |

**No physics was retuned. No existing source file was modified.** The harness only (a) builds a bound
motor through the centralized `TwoBodyConverterMotor.buildBoundMotor` builder, (b) installs the
canonical rates via `initChem4a`, (c) overrides the initial nucleotide state, (d) applies a constant
external axial force through the existing `trapParams[5]` channel, and (e) waits for natural
detachment through the existing chemistry kernel.

---

## 2. Mechanism: what "attachment lifetime" IS in this model

The explicit motor's chemistry is `NucleotideCycleSystem.cycleLymnTaylor`. It has **exactly one
detachment pathway**: a bound head that ends a step in `NUC_ATP` detaches (ATP binding releases the
rigor head). The Guo & Guilford catch–slip term

```
g(F) = aCatch·exp(−F·xCatch/kT) + aSlip·exp(+F·xSlip/kT)          g(0) = 0.92 + 0.08 = 1.000000
```

is the **load modulation of the `ADP → NONE` rate**, *not* a release pathway. Therefore:

| prepared state | route to detachment | force-dependent? |
|---|---|---|
| **ADP** | `ADP →(onADP·g(F))→ NONE →(atpOn)→ ATP` = detach | **YES** — all of it in the first step |
| **rigor** (`NUC_NONE`) | `NONE →(atpOn)→ ATP` = detach | **NO — structurally none** |
| **cycling** (`ADP·Pi`) | `ADP·Pi →(onPi)→ ADP → …` as above | yes, after the stroke |

Rates (`nucParams`): `atpOn = 2e4/s`, `onPi = 1e4/s`, `onADP = 1e3/s`.
Catch (`kinParams`): `aCatch = 0.92`, `aSlip = 0.08`, `xCatch = 2.5 nm`, `xSlip = 0.4 nm`, EMA `alpha = 0` (instantaneous F).

**`cycleLymnTaylor` never reads** `kOff[0]`, `breakForce[11]`, `breakCap[12]` or `fExt[18]`. The
12 pN break cap is therefore *structurally inert* on this path — detachment in this assay is 100 %
natural chemistry, not a cap or a failsafe.

---

## 3. Sign convention (exact)

External force enters through `Cmot.trapParams[5]`: a deterministic axial force in newtons applied to
the filament **at its COM along `cm.uvecPhys`, with zero torque**, added inside
`LaserTrapSystem.applyTraps3D`. The harness writes it in the polarity-safe form
`F·1e-12·dot(cm.bhat, cm.uvecPhys)`. Project convention: **barbed = end2 = `+uVec`**.

```
signedForce > 0  →  toward the BARBED end  →  OPPOSING (resisting) load  →  CATCH side
signedForce < 0  →  toward the POINTED end →  ASSISTING load            →  SLIP  side
```

The **realized** cross-bridge load is `forceDotFil = bondData[12] = Dot(F8 head force, seg.uVec)`, in pN.
This matches the pre-existing `adpReleaseAssay` convention ("+barbed = opposing").

---

## 4. Validation results

### A. Force clamp

Realized load vs request, measured as the mean of `forceDotFil` over the attached window (n = 40/cell):

| requested (pN) | opposing realized | rel. err | assisting realized | rel. err |
|---:|---:|---:|---:|---:|
| 0 | +0.74 | (preload) | +0.74 | (preload) |
| 3 | **+3.48** | +0.16 | **−1.94** | −0.35 |
| 6 | **+6.20** | +0.03 | **−2.17** | −0.64 |
| 10 | **+9.82** | −0.02 | **−3.08** | −0.69 |

- **Sign reverses on direction reversal** at every force — convention verified.
- **The opposing (catch) arm is a faithful force clamp** (≤3 % error at 6 and 10 pN).
- **The assisting (slip) arm SATURATES near −2…−3 pN** — see §5.2. This is a real limitation.
- `F = 0` carries a **+0.74 pN residual preload** from the fixture's built-in `PRE_NM = 2.0 nm`.
  "Zero force" means zero *external* force, not zero bond load. Per-sample SD ≈ 0.95–1.9 pN (thermal).

### B. Prepared states

- `rigor` → t=0 state `NONE(rigor)`, bound ✔ | `adp` → `ADP`, bound ✔ | `cycling` → `ADPPi`, bound ✔
- **No illegal transition before t = 0**: the settle phase (8000 steps at load) runs **mechanics only** —
  the chemistry kernel is not called — and the state is asserted equal to the request at t = 0.
- Every step is audited against the legal transition set; **0 forbidden transitions in 560 events.**
- **Rigor and ADP are unambiguously distinguishable**: 0.041 ms vs 1.07 ms unloaded (26×).

### C. First-passage logic

- Detachment is recorded **exactly once** (loop breaks on the `boundSeg ≥ 0 → < 0` edge).
- **Right-censored events are preserved**, carrying their full window as observed time.
- An event **already detached at initialization aborts as invalid** (`already-detached-at-init`).
- A sub-timestep observation window is **refused** rather than silently reported as lifetime 0
  (this bug was found by the gate and fixed).
- Smoke run: **0 invalid, 0 invalid states, 0 solver failures / 560 events.**

### D. Survival analysis

Implemented in `scripts/sm4_survival.py`, validated against closed-form results in its self-test
(`python3 scripts/sm4_survival.py`): KM equals the empirical SF without censoring; reproduces a
textbook interleaved-censoring worked example; **RMST recovers the exact exponential value under
heavy censoring (0.01259 vs 0.01264 s) while the naive mean collapses to 12.6 ms against a true
20 ms** — which is why the naive mean is *withheld* whenever a cell has any censoring.

Outputs: Kaplan–Meier with at-risk counts and **log-log (exponential-Greenwood) CIs**; median with CI
(reported `n.i.` when not identifiable); p90/p99; **RMST + 95 % CI as the headline location
statistic**; piecewise-constant hazard using **person-time exposure** (so censored events contribute
partial exposure). **Timesteps are never treated as independent samples — the unit of analysis is the
attachment event throughout.**

### E. Regression protection

| check | result |
|---|---|
| production motor model changed | **no** — no existing `.java` modified (only 3 new files added) |
| default chemistry rates changed | **no** — `Constants`/`Env`/`MotorStore`/`NucleotideCycleSystem` untouched |
| running density-sweep code path altered | **no** — `ExplicitCompleteMatHarness.java` untouched (mtime 21:16:33, predates sweep start 21:19 and all SM4 work) |
| repo `.class` files rewritten | **no** — compiled to a scratch dir; repo bytecode MD5s unchanged; no `.class` newer than sweep start |
| rupture / emergency enabled globally | **no** — `RUPTURE_MODE = 0`, `EMERGENCY_ON = false`; harness **aborts (exit 3)** if either is non-default |

### F. Literature-ready force grid

Supported and wired (`./scripts/run_sm4.sh -grid`): **0, 1, 2, 3, 4, 5, 6, 8, 10, 15, 20, 25 pN**
× {rigor, adp} × {opposing, assisting}, 200 events/cell, 40 ms window.
**Deliberately NOT run** — this is a long CPU production run to be launched knowingly.

---

## 5. Scientific findings

### 5.1 The assay recovers the coded law's own optimum (positive control)

With the coded `xCatch = 2.5 nm`, `xSlip = 0.4 nm`, `aCatch = 0.92`, `aSlip = 0.08`, the analytic
lifetime optimum of `1/g(F)` is at

```
F* = ln(aCatch·xCatch / (aSlip·xSlip)) / ((xCatch + xSlip)/kT) = 6.106 pN
```

The measured ADP/opposing RMST peaks at **6 pN** (RMST 5.40 ms [4.02, 6.77]), vs 3.89 ms at 3 pN and
4.83 ms at 10 pN. Measured lifetimes track the analytic prediction at the *realized* load at
0.70–1.00×, consistently **below** it — the correct direction for a **convex** `g` averaged over
thermal force fluctuations (Jensen: `⟨g(F)⟩ > g(⟨F⟩)` ⇒ faster release). The apparatus therefore
measures what it is supposed to measure, and this ~6 pN optimum is the same qualitative feature Guo
& Guilford report.

**Caveat:** at n = 40 the 6 pN and 10 pN CIs overlap — **the peak is indicated, not yet resolved.**
Resolving it is exactly what the production grid is for.

### 5.2 BLOCKER (partial): rigor lifetime is structurally force-independent

**Rigor RMST is bit-identical (0.04088 ms, median 0.0225 ms) across all seven force cells** — 0, ±3,
±6, ±10 pN. This is not noise: rigor detachment depends only on `atpOn`, and because the RNG stream
is keyed on `(motor, step, seed)` and is force-independent, identical seeds give identical
detachment steps. It doubles as a clean positive control: **any ADP lifetime difference is causally
attributable to force, not to RNG drift.**

**Consequence for calibration:** the model has **no force-dependent rigor-detachment pathway**. Guo &
Guilford's *rigor* catch–slip arm **cannot be calibrated against this model as it stands**.

- **Exact blocker:** in `NucleotideCycleSystem.cycleLymnTaylor` (`NucleotideCycleSystem.java:441-479`),
  the catch–slip factor `g(F)` multiplies **only** the `ADP → NONE` rate. From `NUC_NONE` the sole
  transition is `NONE → ATP` at the constant `atpOn`, with no force term anywhere.
- **Minimum harness-only capability missing:** none — no harness change can create the force
  dependence. It requires a **physics change**: a load-dependent `NONE → ATP` rate (or an explicit
  mechanical rigor-rupture pathway). That is out of scope for an execution task and must not be done
  silently.
- **Why an approximation would be scientifically misleading:** fitting `xCatch`/`xSlip` to a *flat*
  simulated rigor curve would either (a) drive the parameters to meaningless values, or (b) tempt one
  to inject force dependence into the terminal ATP step, which would double-count load dependence
  once the ADP arm is also fitted — and would silently change the gliding and clamp predictions the
  campaign explicitly wants to keep as *predictions*.

**What IS calibratable now:** the **ADP arm**, which carries the full catch–slip force dependence and
shows a clean, correctly-located optimum. That is the primary `xCatch` channel the campaign asks for.

### 5.3 BLOCKER (partial): the assisting arm is not force-clamped on the explicit model

The assisting (slip-side) realized load saturates near **−2…−3 pN** no matter how large the request
(−0.69 rel. err at 10 pN). A three-fixture control isolates the cause:

| requested (assisting) | `explicit-s2-l40` | `fixed-anchor` | `calibrated-s2-l40` |
|---:|---:|---:|---:|
| 3 pN | −1.91 | **−1.91** | **−2.11** |
| 6 pN | −1.91 | **−4.55** | **−4.72** |
| 10 pN | −2.80 | **−8.09** | **−8.24** |

The rigid fixed-anchor fixture and the analytic calibrated surrogate both transmit assisting load
faithfully; **only the explicit S2 beam saturates.** This is compressive **buckling of the explicit
S2 beam** — a real, expected property of that model (it is precisely what SM6 is designed to
characterize), not an apparatus defect.

**Two consequences:**
1. **`xSlip` cannot be calibrated from assisting-load lifetimes on `explicit-s2-l40`** beyond ~3 pN,
   because the motor never experiences the requested load. Use `fixed-anchor` for the slip arm, or
   apply the load through a path that does not compress S2.
2. **The calibrated surrogate does not reproduce the explicit beam's compressive branch.** It was
   fitted to the explicit beam's *relaxed-pivot reaction* (Experiment 4I) and evidently matches in
   tension but not in compression. This is a surrogate/reference divergence worth recording against
   SM6 and against any assisting-load production work.

---

## 6. Smoke matrix (apparatus-validation data — NOT calibration data)

`explicit-s2-l40`, CPU, dt = 2.5e-6 s, settle 8000 steps, 20 ms window, n = 40/cell, 560 events, 87 s wall.

| state | dir | F (pN) | RMST (ms) | median (ms) | detach/cens |
|---|---|---:|---:|---:|---:|
| rigor | any | 0, ±3, ±6, ±10 | 0.0409 | 0.0225 | 40/0 (all cells identical) |
| adp | — | 0 | 1.07 | 0.815 | 40/0 |
| adp | opposing | 3 | 3.89 | 2.77 | 39/1 |
| adp | opposing | **6** | **5.40** | **4.47** | 38/2 |
| adp | opposing | 10 | 4.83 | 3.99 | 39/1 |
| adp | assisting | 3 | 0.259 | 0.238 | 40/0 |
| adp | assisting | 6 | 0.214 | 0.198 | 40/0 |
| adp | assisting | 10 | 0.163 | 0.138 | 40/0 |

Censoring appears only where it should (the long-lived opposing ADP cells), confirming both survival
and censoring behaviour are exercised.

---

## 7. Sweep non-interference

The GPU single-head density sweep (`ExplicitCompleteMatHarness`, per-cell JVMs, `-cp …:.`) ran
throughout. Because those JVMs load `.class` files **from the repo tree**, a normal
`./scripts/build.sh` would have changed the code path of every subsequent cell. All SM4 code was
therefore compiled to a **scratch output directory** and run with that directory prepended to the
classpath. Verified: repo `.class` MD5s unchanged; no `.class` file newer than the sweep's start; the
sweep advanced 36 → 50 of 52 cells with **0 failures** across the work. All SM4 runs were `nice`d and
CPU-only; GPU utilisation stayed at its normal 38–42 %.

`scripts/run_sm4.sh` documents the scratch-build procedure (`SM4_CLASSES=…`) for reuse during any
future long campaign.

---

## 8. Recommended next steps

1. **Do not** change `xCatch`/`xSlip` yet — the smoke matrix is apparatus validation.
2. Run the production grid (`./scripts/run_sm4.sh -grid`) **for the ADP arm**, to resolve the peak
   location and the catch/slip slopes with n = 200 and the full 12-force ladder.
3. Run the **slip arm on `fixed-anchor`** (or a non-compressive load path), per §5.3.
4. Escalate the **rigor force-independence** (§5.2) as a modelling decision, not a calibration task.
5. SM6 should quantify the explicit-S2 compressive branch and the surrogate divergence found in §5.3.
