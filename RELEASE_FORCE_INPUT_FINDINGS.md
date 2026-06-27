# RELEASE FORCE INPUT — the lumped catch-slip bond reads a dt-overshot force; can a TIME-AVERAGED force input make it dt-robust?

**MEASUREMENT + ADDITIVE (flag-gated), no physics edit.** Changes ONLY *what force the lumped release rate
reads* (instantaneous overshot `forceDotFil` vs a per-head EMA over τ_avg). The binding search, the cross-bridge
spring force law, and the catch-slip rate FORMULA are untouched. `BoA-v1ref` untouched. Sole occupant of aorus,
v2-GPU device-resident. Instrument: `V2OneXHarness -tauavg <s>` + the `RELROW` readout (both flag-gated,
default-off; production path byte-unaffected). Same 0.5× dt-study scene (box 5.0, 200 nodes × 24 singlet myosins,
500 fil × 10 seg, crosslinkers on, aeta 0.1). Companions: `DT_CONVERGENCE_FINDINGS.md` (the upper wing),
`MYOSIN_BINDING_RATE_FORMULATION.md` (the two independent wings). Driver `run_relforce.sh`; raw
`RUN_LOGS/2026-06-25_relforce_stage1.txt` / `_stage2.txt`.

## Framing (the corrected thesis posture)
Unbinding is **NOT claimed to emerge** — molecular detachment is below this coarse model's resolution. The
release is a **deliberately LUMPED, calibrated bond**: a phenomenological catch-slip `k_off(F)` tuned to
experiment. The goal here is a **dt-ROBUST lumped bond**, NOT emergence. (`RESEARCH_THESIS.md` §4 corrected
accordingly: the emergence claim lives in the three-body MECHANICS / force generation under the calibrated bond,
not in unbinding.) The count's residual dt-dependence is the lumped release reading the **dt-overshot
cross-bridge force F** — so the surgical question is whether feeding the release a *time-averaged* F restores the
converged behavior.

## The release path, as coded (Stage-1 code-read, confirmed)
`NucleotideCycleSystem.catchSlipRelease`: `rate = kOff·(αCatch·e^(−F·xCatch/kT) + αSlip·e^(+F·xSlip/kT))`,
**`P = rate·dt`** (a proper per-sim-time rate, NOT `1−exp(−rate·dt)` — equivalent to O(dt²) at these rates), with
**`F = forceDotFil` = the SIGNED along-filament cross-bridge load** (`Dot(F8, seg.uVec)`), not the unsigned
spring magnitude `|F8|`. Constants (v1 Env): kOff=100/s, αCatch=0.92, xCatch=2.5 nm, αSlip=0.08, xSlip=0.4 nm,
kT=4.142 pN·nm. A parallel deterministic branch (the 12 pN break cap, default-on in this scene) detaches when
`|F8| > 12 pN` — it reads the unsigned magnitude and is a *separate* channel (DT_CONVERGENCE `-nocap` control
proved it is secondary, not the gate).

**The decisive structural fact (signed F):** because F is signed and the catch exponent is `e^{−F·xCatch}`, the
catch term is bond-STRENGTHENING for F>0 (long life under positive load — the catch/load-holding regime) but
bond-WEAKENING for **F<0** (it *explodes*: e^{−F·xCatch}=e^{+|F|·xCatch}). The analytic catch-slip rate vs the
signed load:

(kT = 4.116 pN·nm):

| F (pN) | catch term (/s) | slip term (/s) | total rate (/s) | catchFrac | regime |
|---|---|---|---|---|---|
| −6.0 | 3518 | 4.5 | 3523 | 1.00 | negative-load EXPLOSION (overshoot artifact) |
| −3.0 | 569 | 6.0 | 575 | 0.99 | negative-load driven |
| −1.0 | 169 | 7.3 | 176 | 0.96 | |
| 0.0 | 92.0 | 8.0 | 100.0 | 0.92 | unloaded |
| +2.9 | 15.8 | 10.6 | 26.4 | 0.60 | the |F8| "floor" (catch load-holding) |
| +6.1 | 2.3 | 14.5 | 16.7 | 0.14 | catch–slip MINIMUM (longest life) |
| +9.1 | 0.4 | 19.4 | 19.7 | 0.02 | slip wing |
| +12.0 | 0.1 | 25.7 | 25.7 | 0.00 | slip wing |

The minimum-rate (longest-lifetime) load is **+6.1 pN**; below it the rate rises *as load falls* (catch
load-holding), and for F<0 it explodes. So the release rate is acutely sensitive to the **negative excursions**
of forceDotFil — exactly what an explicit stiff-spring overshoot produces.

## Method
- **Stage 1 (reference):** fine-dt sweep {1e-5, 5e-6, 2e-6, 1e-6}, instantaneous force, matched sim-time
  (0.10 s; 1e-6 to 0.06 s). New `RELROW`: per-checkpoint INTERVAL catch-slip off-rate `Δreleases /
  Δ(bound-motor-seconds)` (the per-bound-motor off-rate = 1/mean-lifetime; for a per-step Bernoulli(rate·dt) the
  lifetime is geometric ⇒ the distribution is pinned by this rate), the total off-rate incl. the cap channel, the
  bound-population signed-load (forceDotFil) mean, the rate-weighted "force at release" ⟨F·rate⟩/⟨rate⟩, the
  analytic catch-frac, and the analytic population mean rate.
- **Stage 2 (the test):** at coarse dt=1e-5, sweep τ_avg ∈ {1e-4 … 1e-2 s} with the release reading a per-head
  EMA `forceDotAvg += (dt/τ_avg)·(forceDotFil − forceDotAvg)` (seeded at bind), `-nocap` to isolate the catch-slip
  channel (+ a cap-on confirmation). Tests: (a) PLATEAU in τ_avg; (b) MATCH to the Stage-1 fine-dt reference;
  (c) CATCH PRESERVATION (does the load-dependence survive the averaging?). Plus a probe at the COLLAPSED dt=2e-5.

## STAGE 1 — the dt→0 release CONVERGES (the reference; GATE PASSED)

Steady-state (last 3 matched-simT checkpoints), instantaneous-force release, 12 pN cap ON:

| dt | catch-slip off-rate (/s) | total off-rate incl. cap (/s) | bound | fAtRelease (pN) | catchFrac | mean forceDotFil (pN) |
|---|---|---|---|---|---|---|
| 1e-5 (calib.) | **~428** | ~506 | ~400 | −4.2 | 0.97 | +0.3 |
| 5e-6 | ~204 | ~204 | ~905 | −2.5 | 0.96 | +0.2 |
| 2e-6 | ~177 | ~181 | ~1060 | −1.9 | 0.95 | +0.2 |
| 1e-6 (simT 0.06) | ~165 | ~181 | ~890↑ | −1.8 | 0.95 | +0.2 |

**Answers to the three Stage-1 questions:**
1. **Release-per-sim-time CONVERGES (plateau).** The per-bound-motor catch-slip off-rate falls 428 → 204 → 177 →
   165 /s with **decelerating increments** (−224, −27, −12) — a finite continuum limit **≈ 160 ± 15 /s, reached
   by dt ≈ 2e-6** (the SAME dt at which the binding count plateaus at B*≈1050, `MYOSIN_BINDING` §4). So the lumped
   release is **well-posed** — it has a dt→0 limit; the **1e-5 instantaneous off-rate (428/s) is ~2.6× the
   converged reference**, an overshoot inflation, not an ill-posed formulation. **GATE PASSED → Stage 2.**
2. **Per-step form:** `P = rate·dt` (confirmed in code), a proper per-sim-time rate; the cap channel (`|F8|>12 pN`)
   contributes ~80/s at 1e-5 but **vanishes as dt→0** (offRateTot→offRateCS by 5e-6) — i.e. the dt-dependence is
   the **catch-slip channel reading the overshot force**, exactly as DT_CONVERGENCE concluded.
3. **CATCH-dominated, but NEGATIVE-load-weighted — the structural crux.** At EVERY dt the release is
   catch-dominated (catchFrac 0.95–0.97) yet the rate-weighted **force at release is NEGATIVE** (−4.2 pN at 1e-5,
   converging to **−1.8 pN**). The mean bound load is small-positive (+0.2 pN), so the releases are carried by the
   **negative excursions** of forceDotFil, on which the catch term `e^{−F·xCatch}` *explodes*. The dt-overshoot
   makes those excursions larger (∝ dt: fAtRelease −4.2 → −1.8 as dt→0), which is the entire mechanism of the
   off-rate's 2.6× inflation at 1e-5. **The converged behavior is NOT the +2.9 pN "catch load-holding" regime that
   the |F8| magnitude floor suggested — it is negative-load-driven catch, set by the WIDTH of the (signed) load
   distribution, not its mean.** This is the fact that decides Stage 2.

**Reference for Stage 2 to reproduce:** off-rate ≈ **160/s**, bound ≈ **1050**, fAtRelease ≈ **−1.8 pN**,
catchFrac ≈ 0.95.

## STAGE 2 — time-averaged release force: a PLATEAU that OVER-CORRECTS (does NOT match fine-dt)

At dt=1e-5, release reads a per-head EMA `forceDotAvg` over τ_avg; cap OFF (isolates the catch-slip channel).
Steady state (mean of the last 2 matched-simT checkpoints):

| τ_avg | (steps = τ/dt) | bound | catch-slip off-rate (/s) | vs fine-dt ref |
|---|---|---|---|---|
| instantaneous | 1 | ~443 | ~441 | off-rate 2.7× HIGH, bound 0.42× (the 1e-5 overshoot) |
| 1e-4 | 10 | ~1217 | ~110 | |
| 2e-4 | 20 | ~1243 | ~103 | |
| 5e-4 | 50 | ~1312 | ~101 | |
| 1e-3 | 100 | ~1334 | ~101 | |
| 2e-3 | 200 | ~1311 | ~101 | |
| 5e-3 | 500 | ~1339 | ~103 | |
| 1e-2 | 1000 | ~1354 | ~100 | |
| **fine-dt reference (Stage 1)** | — | **~1050** | **~160** | (the target) |

- **(a) PLATEAU — YES.** From τ_avg = 1e-4 (10 steps) to 1e-2 (1000 steps), the off-rate is FLAT at ~100/s and
  bound flat at ~1300 — insensitive to the window over **two orders of magnitude**. A clean plateau.
- **(b) MATCH — NO.** The plateau is at the WRONG value: off-rate **~100/s vs the converged 160/s**; bound
  **~1300 vs B*≈1050**. Averaging **over-corrects** — it jumps PAST the fine-dt reference. It does suppress the
  dt-overshoot (441 → 100 removes the 2.7× inflation) but keeps going, landing ~38 % BELOW the converged off-rate
  and ~25 % ABOVE B*.
- **(c) CATCH PRESERVATION — NO (the mechanism of the miss).** The converged off-rate (160/s) is carried by the
  **WIDTH** of the signed-load distribution: the convex catch term `e^{−F·xCatch}` *rectifies* the negative
  excursions of forceDotFil into extra releases, so the true per-sim-time rate is `⟨rate(F)⟩` over the genuine
  (thermal + mechanical) load fluctuations. **Per-head time-averaging collapses F to its per-motor mean (~+0.2 pN)
  before the rate is computed ⇒ the release sees `rate(⟨F⟩) ≈ rate(+0.2 pN) ≈ 100/s` — the UNLOADED rate** (Jensen:
  for a convex rate, `rate(⟨F⟩) < ⟨rate(F)⟩`). Averaging doesn't just remove the overshoot — it **destroys the
  load/fluctuation rectification that IS the catch behavior.** The flat ~100/s plateau is the signature: it is
  pinned at the mean-load rate regardless of window.
- **The cap-off pathology (a faithfulness flag).** With averaging + cap off, the release no longer sheds a bond
  that is INSTANTANEOUSLY massively over-stretched (it reacts only to the smoothed force), so a tail of bonds runs
  to enormous instantaneous load (forceDotFil to −800 pN; the inst-F `popRateAnalytic`/`fAtRelease` columns
  overflow — they are computed on the instantaneous F, which is now pathological). The 12 pN cap (on the
  instantaneous |F8|) bounds this — see the cap-on probe — but that means the averaged catch-slip alone is
  **stability-degrading without the instantaneous-force cap as a backstop.**

### Probe A — the SHORT-τ window: a knife-edge off-rate match on a pathological distribution (cap off)

| τ_avg | steps | bound | off-rate (/s) | |F8| mean / p90 / **max** (pN) | fracOverCap |
|---|---|---|---|---|---|
| inst | 1 | ~440 | ~440 | 4.5 / 7.0 / ~12 | 0 (cap off) |
| **2e-5** | **2** | ~900 | **~155** ✓ | 5.6 / 8.2 / **96–146** | 0.04 |
| 3e-5 | 3 | ~1080 | ~130 | 5.5 / 8.1 / ~100 | 0.05 |
| 5e-5 | 5 | ~1130 | ~118 | 5.6 / 8.4 / **219** | 0.05 |
| ≥1e-4 | ≥10 | ~1300 | ~100 (plateau) | — / — / large | — |
| **fine-dt ref** | — | ~1050 | **~160** | **3.0 / 4.8 / ~11** | 0 |

- The off-rate matches the converged 160/s **only at τ_avg ≈ 2 steps** — and that is a **knife-edge on a steep
  monotone slope** (440 → 155 → 130 → 118 → 100 across 1 → 2 → 3 → 5 → ≥10 steps), NOT a plateau. The robust
  plateau is the over-corrected ~100/s.
- **Even at that matched-off-rate window the FORCE DISTRIBUTION is grossly wrong:** |F8| mean 5.6 vs the fine-dt
  3.0 pN, **fmgMax 96–219 pN** vs ~11, a fat high-stretch tail, and segment escapes (escXY 1–2). Matched count,
  **wrong distribution** — the exact count-vs-distribution distortion the binding "reach hack" showed
  (`MYOSIN_BINDING` §4.2). The 2-step average removes the worst 1-step spike (cutting the off-rate from 440 to
  155) but does NOT recover the tight converged geometry; it binds an over-stretched population.

### Probe B — cap ON: the bound count is recovered, but by the INSTANTANEOUS cap, not the averaging

| τ_avg | bound | catch-slip off-rate (/s) | total off-rate incl. cap (/s) |
|---|---|---|---|
| inst | ~400 | ~427 | ~502 |
| 5e-4 | ~1015 | ~91 | ~209 |
| 1e-3 | ~1014 | ~94 | ~221 |
| 2e-3 | ~1001 | ~94 | ~217 |
| 5e-3 | ~1008 | ~98 | ~223 |

With the 12 pN cap (on the **instantaneous** |F8|) restored, the averaged-release bound count plateaus at **~1010 ≈
B*≈1050** — looks like a match. But the mechanism is wrong: the **catch-slip channel is over-suppressed to ~94/s**
(vs the reference 160) and the **cap channel makes up ~120/s** of the releases. The fine-dt reference has cap≈0 and
catch-slip 160; here it is catch-slip 94 + cap 120. So the count is rescued **by the instantaneous-force cap doing
the shedding the over-smoothed catch-slip no longer does** — averaging did not solve the problem, the instantaneous
cap did. (It also means averaged catch-slip is **stability-degrading without the instantaneous cap** as backstop —
Probe A's cap-off runs to fmgMax 219 pN.)

### Probe C — the COLLAPSED dt=2e-5: averaging rescues from catastrophe, but only to a mediocre over-stretched state (cap off)

| τ_avg | bound | off-rate (/s) | |F8| mean (pN) |
|---|---|---|---|
| inst | **21** | **6902** | 4.6 |
| 1e-3 | 304 | 235 | 19 |
| 2e-3 | 335 | 197 | 43 |
| 5e-3 | 349 | 184 | 7 |
| 1e-2 | 361 | 172 | 46 |

At the dt where binding catastrophically collapses (DT_CONVERGENCE: 2e-5 → bound ~18), the instantaneous off-rate
is **6900/s** (the negative-load catch explosion on the dt=2e-5 overshoot). Averaging rescues binding **15×** (21
→ ~350) — it genuinely suppresses the collapse. But the recovered state is **far from faithful**: bound ~350 ≪ B*
1050 (and below even the 1e-5 instantaneous 400), with a pathological over-stretched distribution (mean |F8| up to
46 pN). Averaging converts a catastrophe into a mediocre, over-stretched partial bind — not a faithful run.

## THE FORK — averaging alone is INSUFFICIENT (the numerical reading is decisive)

**Result: the fork OPENS.** No τ_avg window simultaneously (i) suppresses the dt-overshoot, (ii) reproduces the
Stage-1 fine-dt reference (off-rate ≈160, bound ≈1050, the tight |F8| distribution), and (iii) preserves catch:
- The window long enough for a **robust plateau** (≥10 steps) **over-corrects** — it collapses `⟨rate(F)⟩` to
  `rate(⟨F⟩) ≈ rate(mean load) ≈ 100/s` (unloaded), destroying the load/fluctuation rectification that IS the
  catch behavior. Bound overshoots to ~1300 (cap off).
- The window short enough to **preserve the genuine signal** (~2 steps) sits on a knife-edge (not a plateau) and
  **still binds a pathologically over-stretched, fat-tailed population** (fmgMax 96–219 pN) — it removes the
  1-step spike but not the overshoot bias in the surviving bonds.
- The overshoot oscillation and the genuine thermal/mechanical load fluctuation live at the **SAME ≤2-step
  timescale**, so per-head averaging **cannot separate them** — any window that washes the overshoot also smears
  the load-rectification, and any window that keeps the load-rectification also keeps the overshoot.

**Which framing the result lands in — the NUMERICAL one, decisively.** The fine-dt instantaneous-force release is
ground truth (Stage 1: it converges, off-rate → 160, distribution → tight). Time-averaging the force does **not**
produce a better/more-physical bond — it produces either a mean-load-collapsed rate or an over-stretched
distribution. So the **PHYSICAL framing (a bond that integrates load over its lifetime → averaged strain is the
"right" input) is NOT supported here:** averaging degrades the distribution and the catch response rather than
improving them. The instantaneous strain is the correct physical input; the defect is purely that the **explicit
stiff-spring integrator computes that instantaneous force wrong (overshoot ∝ dt)**.

**⇒ Verdict: averaging alone is insufficient.** It names its two follow-ons (NOT built here):
1. **Sub-cycle the bound-head inner loop** (the numerical fix, the upper-wing lever from DT_CONVERGENCE /
   MYOSIN_BINDING): integrate the stiff cross-bridge + the catch-slip release at a small inner dt while the rest
   of the mechanics advances at a larger outer dt. This attacks the overshoot **at its source** and keeps the
   release reading the **instantaneous** (now-correct) force — preserving catch. This is the indicated path.
2. **A physical strain-integration bond** (the publishable deeper model): a bond whose detachment depends on
   genuine integrated strain history with a *physically* motivated memory kernel — distinct from the numerical
   EMA tested here, and validated against experiment, not against the fine-dt run. The fork data shows the *naive*
   force-average is not that model (it lacks a separation of overshoot from signal); a genuine version would need
   the cross-bridge force itself computed faithfully first (i.e. it still needs lever 1 underneath).

## CPU≡GPU + stability
- **CPU≡GPU (the new averaged kernel adds no divergence).** Deterministic `-brownoff -cmp`: step-0 max|Δcoord| =
  7.4e-5 µm (float32 last-bit) with bound-set Δ=0, and the subsequent chaotic divergence is **bit-for-bit the same
  trajectory as the instantaneous path** (identical step-100 bound-set Δ, identical RESULT 0.2676 µm) ⇒ the EMA is
  computed identically on both runners; the divergence is the pre-existing chaotic decorrelation of the
  **force-threshold stochastic release** (the §8 standard: bit-identity is unattainable for this chaotic
  release-bearing scene; aggregate-within-SEM is the gate). Brownian-on, both runners land in the same
  averaging-elevated regime (bound CPU 733 / GPU 619 at simT 0.015, both ≫ the ~400 instantaneous).
- **Stability.** No NaN in any run. **Averaged + cap ON = stable** (bound ~1010, |F8| bounded by the cap).
  **Averaged + cap OFF = stability-degrading** (|F8| runs to 96–219 pN, occasional segment escapes) — averaging
  removes the catch-slip's ability to shed an instantaneously over-stretched bond, so the instantaneous-force cap
  becomes load-bearing for stability. This is itself evidence that the instantaneous force carries necessary
  information the average discards.

## Coupling-to-binding caveat (carried, per the task)
These release results sit on the **geometric** binding search (the working default; behaviorally identical to the
saturated `kOn=1e8` rate search). The release reads the **bound population**, and a future *reaction-limited* `kOn`
calibration may lower binding and shift the bound-force distribution (cf. kOn=5e6 → ~322 vs 447), which would
re-weight the catch/slip decomposition and the converged off-rate. The qualitative fork (averaging cannot separate
overshoot from signal; sub-cycling is the lever) is a per-bond force-integration property independent of the
population size, but the **absolute** off-rate reference (~160/s) and B* carry an "at the current binding
calibration" asterisk and should be re-checked once binding is calibrated.

## Reproduce
```
./run_relforce.sh stage1      # fine-dt sweep, instantaneous (the converged reference: off-rate→160, B*≈1050)
./run_relforce.sh stage2      # τ_avg sweep @1e-5, cap OFF (the over-correcting plateau)
./run_relforce.sh shortwin    # τ_avg 2..5 steps (the knife-edge match + pathological distribution)
./run_relforce.sh stage2cap   # τ_avg sweep @1e-5, cap ON (count rescued by the instantaneous cap)
./run_relforce.sh collapse    # τ_avg @ dt=2e-5 (rescue-from-collapse, but mediocre/over-stretched)
./run_relforce.sh cmp         # CPU≡GPU spot-check
```
Raw: `RUN_LOGS/2026-06-25_relforce_stage1.txt`, `_stage2.txt`. Instrument: `V2OneXHarness -tauavg <s>` + `RELROW`
(both flag-gated, default-off; with neither set the harness is byte-identical to the committed benchmark).


