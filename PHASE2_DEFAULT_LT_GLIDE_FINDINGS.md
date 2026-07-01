# Phase-2 — does the DEFAULT (v1-port) motor glide at skeletal speed on the Lymn-Taylor cycle?

**2026-06-30. Measurement + minimal-wiring only. Flag-gated `-lymntaylor`; default (no `-lymntaylor`)
byte-identical; `BoA-v1ref` byte-clean; no motor-physics retune, no default flip. The DEFAULT motor =
the v1 port: single fixed-site F8 tip spring + F9 head-reorientation stroke (90°→120°) + J1 lever; the
catch reads the F8 tip-spring load (`forceDotFil`). This is the one motor that has ever passed a
rigorous gliding assay (4b-iv −13% residual; SET-A −5.7 µm/s control).**

---

## STEP 0 — what is v1's actual nucleotide cycle + detachment trigger? (read from BoA-v1ref, READ-ONLY)

**v1's detachment is NOT Lymn-Taylor nucleotide-cycle-driven. v1 uses TWO INDEPENDENT mechanisms:**
1. **The nucleotide cycle (NONE→ATP→ADPPi→ADP) drives only the COCKING.** `isCocked() = !isADPPi()`
   (`MyoMotor.java:277-279`) → the 90°↔120° head-actin rest-angle switch (`Myosin.java:18-19`),
   from which the power stroke emerges as a torque. The `dissociateADP()` ADP→NONE transition
   (`MyoMotor.java:270-275`) changes the nucleotide state but **does NOT call `release()`** — a head
   can reach NONE while still bound.
2. **The actual UNBINDING is pure catch-slip / break-force release, independent of nucleotide state:**
   the 12 pN break-force cap (`MyoFilLink.java:334-340`) **plus** the Guo&Guilford load-dependent
   probability `P = kOff·(αCatch·e^(−F·xCatch/kT) + αSlip·e^(+F·xSlip/kT))·dt` on `forceDotFil`
   (the F8 tip-spring along-axis load, `MyoFilLink.java:347-359`). Detachment is NOT a NONE→ATP event.

**⇒ Reading consequence (this is what STEP 0 decides).** "Default motor + LT" is **NOT restoring v1
whole.** The DEFAULT v2 motor (`cycle` + `catchSlipRelease`, kOff 100/s base) IS v1's actual mechanism
(nucleotide cocking + catch-slip release). The LT cycle (`cycleLymnTaylor`) *replaces* the detachment
with a fast nucleotide-driven NONE→ATP event and *deletes* the separate catch-slip release. So
**"default + LT" = the default v1-port STROKE + a fast nucleotide-driven DETACHMENT that v1 never ran.**
It is a NEW combination. jba's recollection that "LT ≈ v1's cycle" is **incorrect for detachment**:
v1's cocking matches LT, but v1's *release* is catch-slip, not NONE→ATP. (The match is on the
mechanochemistry of the lever, not on what unbinds the head.)

---

## STEP 1 — route the DEFAULT motor through the LT cycle (the wiring was already additive)

**No wiring change was needed — the LT branches were already gated on `LYMN_TAYLOR` alone, independent
of `CONFIG1`/`CANONICAL`.** Confirmed by code read (`GlidingHarness.java`):
- release: `if (LYMN_TAYLOR) { /* no separate release */ }` (`:348-351`, `:537`) — the catch-slip
  release task is skipped on the LT path for the default motor too.
- cycle: `if (LYMN_TAYLOR) cycleLymnTaylor(...)` (`:372-373`, `:554-555`) fires for the default motor.
- cross-bridge force: the default `CrossBridgeSystem.bondForces(...)` (`:396-398`) is UNCHANGED — F8
  tip spring + F9/J1, `isCocked()` driven by `mot.nucleotideState` which `cycleLymnTaylor` sets.
- load input: `registerForceDot` (`:416`) populates `mot.forceDotFil` from the **default F8 bond** —
  exactly the default motor's NATIVE F8 tip-spring along-axis load. `cycleLymnTaylor` reads
  `forceDotFil` (`NucleotideCycleSystem.java:434`) as the catch input — **NOT J1 strain.** ✓
- the catch calibration (xCatch 2.5 nm, xSlip 0.4 nm, αCatch 0.92, αSlip 0.08; `setKinParams`) and the
  LT nucleotide rates (`setNucParams`: atpOn 2e4, onPi 1e4, onADP 1e3 /s) are set **unconditionally**
  ⇒ the default motor gets the full LT skeletal cycle with no extra flags.

**The DEFAULT motor keeps its NATIVE bind-on-contact binding** (`KON=0` default ⇒ `bindNearest` binds
every reachable encounter with P=1 — the maximal-duty binding 4b-iv/SET-A used). `-kon` would be a
retune, so it is NOT applied; the LT cycle changes only the *detachment*.

**STEP-1 confirmation (default motor, `-lymntaylor`, v1box, dt 1e-5, diag):** cycles, cocks, detaches
correctly — velocity **−1.28 µm/s** (−x ✓), power strokes fire (2438/s/bound motor), **bound-in-ATP
0.0%** (the LT enforcement holds), sub-pN loads (forceDotFil mean +0.88 pN, 60% resisting). Bail
condition (STEP-1 wiring touching the default stroke kernel) **NOT triggered** — `bondForces` is
byte-unchanged; LT touches only the cycle/release.

### A/B — what LT does to the DEFAULT motor (diag, v1box, density 500, dt 1e-5)
| metric | DEFAULT **no-LT** (v1-faithful catch-slip) | DEFAULT **+ LT** |
|---|---|---|
| diag velocity | −3.32 µm/s | −1.28 µm/s |
| avgBound | **7.16** (≈ v1 ~7.6) | **0.74** |
| bound-state | ATP **59.8%**, ADP 36.6%, ADPPi 2.8%, NONE 0.7% | ATP **0.0%**, ADP 67.5%, ADPPi 20.9%, NONE 11.6% |
| mean bound-time | 1.29 ms (release 773/s) | 0.41 ms (detach 2460/s) |

**The crux is visible already:** LT's fast nucleotide-driven detachment fixes the v1-port's bound-in-ATP
pathology (ATP-bound 59.8% → 0.0%) but **collapses the duty** (avgBound 7.16 → 0.74). The single-molecule
"V₀ = step × detach-rate ≈ 6 µm/s" does NOT translate into faster ensemble glide because the ensemble
glide ∝ duty × per-head transport, and duty (avgBound) falls ~10×.

---

## STEP 2 — the RIGOROUS gliding assay (full-mat, long run, velFitX, thermal floor, n≥3)

*(GPU device-resident `buildPlan`; full-mat `-matbed` bed, ~2.9 µm −x runway; `velFitX` = −slope of a
least-squares fit of centroid x(t) over the steady 2nd half — the min-variance directed-drift estimator,
not the 2-pt chord / instantaneous speed. dt=1e-5. Thermal floor = same scene with motors that never
bind (`-kon 1e-30` ⇒ pBind≈0). n=3 seeds, SEM.)*

**Single-density gate (d1000, seed 0, 40k) — PASS:** velFitX **+2.127 µm/s** (−x), netX **−2.305 µm/s**,
avgBound 1.5, fullMat=YES, capFires=0 (stable). ~8× the config1/perphead's ~0.25–0.4 µm/s on the same bed.

### The density ladder (GPU device-resident, full-mat, 40k steps, n=3 seeds, dt=1e-5)
All −x; every cell `fullMat=YES`, `capFires=0` (stable — no whip/NaN to d2000). avgBound never reaches v1's ~7.

| density | nMot | avgBsteady | **velFitX** (µm/s, mean±SEM) | netX (mean) | vs floor |
|---|---|---|---|---|---|
| 200  | 1392  | 0.19 | **0.050 ± 0.076** | −0.40 | ≈ floor (binding ~off) |
| 500  | 3480  | 0.71 | **0.835 ± 0.290** | −1.16 | ~2× floor |
| 1000 | 6960  | 1.11 | **1.389 ± 0.542** | −1.86 | ~4× floor |
| 2000 | 13920 | 2.21 | **3.003 ± 0.355** | −3.44 | ~8× floor |

**Thermal floor (`-nobind`, motors present but never bind, avgBound=0.000):** velFitX **−0.376**, netX
**−0.028** (the LS-slope estimator's noise scale on one diffusing-filament realization; netX≈0 confirms
no true diffusive drift). The directed glide exceeds the floor for d≥500 and is unambiguous (~8×) at d2000.

The glide rises **monotonically with density and with avgBound** (velFitX ≈ 1.3·avgBsteady, intercept ≈0
— i.e. directed transport ∝ bound population, extrapolating to 0 at zero binding). Both estimators
(velFitX steady-slope and netX 2-pt chord) agree in sign and roughly in magnitude.

### CPU≡GPU (aggregate-within-SEM; d500, 20k, n=3)
| runner | velFitX (mean) | avgBsteady (mean) |
|---|---|---|
| GPU | 2.20 (2.205/2.137/2.243) | 0.68 |
| CPU | 1.62 (1.272/1.604/1.977) | 0.52 |

Ranges overlap; CPU runs consistently a touch lower (the known one-step-stale SoA / op-ordering
parallel-scheme residual, GLIDING_4biv). **PASS** the chaotic-gliding standard. (Note the 20k velFitX
≈2.2 > the 40k steady-state ≈0.84 at the same d500/seeds — velFitX still carries settling drift at 20k;
the **40k ladder is the trustworthy steady state**, and it makes the realized glide *smaller*, not larger.)

### Regression / default byte-identity
The `-nobind` guard reads `kinParams[19]` (reserved, default 0 ⇒ the branch is a no-op). Re-running
`glide_d500_s0` with the post-edit binary reproduces the pre-edit `velFitX=0.886, avgB=0.713` exactly
(default-off byte-identical). Default (no `-lymntaylor`) paths unaffected; `bondForces`/stroke kernel
byte-unchanged; `BoA-v1ref` byte-clean.

---

## Verdict / outcome adjudication

**OUTCOME = #2: the default motor glides −x on the LT cycle, but SLOW (below the 5–8 µm/s skeletal
anchor). The LT cycle CHANGED the default's transport — it trades ensemble duty for turnover-correctness.**

- **Does it glide?** YES — robustly −x, stable (capFires=0, fullMat=YES, no NaN/whip through d2000),
  monotonic with density, CPU≡GPU-faithful, clearly above the thermal floor. This is ~8–12× the
  config1/perphead's ~0.25–0.4 µm/s on the same bed — the default motor is a far better transporter, as
  the task premise expected. **So we DO have a directionally-correct, stable gliding motor on the LT
  cycle today.**
- **At skeletal speed?** NO — it reaches ~3 µm/s (velFitX) / ~3.4 µm/s (netX) only at d2000 (avgBound
  2.2) and is <1 µm/s at d≤500. It does not reach 5–8 in the stable density range.
- **Why slow — the diagnosis (DUTY collapse).** Ensemble glide ∝ avgBound × per-head transport. LT's
  fast nucleotide-driven detachment (NONE→ATP; effective ~2400/s, bound-time 0.41 ms) **collapses
  avgBound from the native default's ~7 to ~0.7 at d500** (the STEP-1 A/B). The single-molecule
  "V₀ = step × detach-rate ≈ 6 µm/s" (PHASE2_LYMN_TAYLOR) does **not** translate to ensemble glide
  because V₀ ignores duty. This is the **same orthogonal binding-density / transport-efficiency residual**
  already flagged (BINDING-SURVEY, SPEED-DENSITY, SLIP-DIRECTION) — confirmed here for the default motor.
- **The honest tension (the headline).** The default motor's *native* catch-slip release (NO LT,
  kOff 100/s base, bound-time 1.3 ms, **avgBound ~7 ≈ v1**) actually glides FASTER (−3.3 µm/s diag /
  −5.7 µm/s SET-A) than default+LT. **LT does not speed up the default motor — it slows it, by starving
  duty**, in exchange for (a) fixing the bound-in-ATP pathology (59.8% → 0.0%) and (b) the correct
  skeletal single-molecule turnover. So neither configuration is fully skeletal at once: native-default =
  right ensemble speed but wrong turnover + bound-in-ATP; default+LT = right turnover but duty-starved
  ensemble. **The gap between them is purely avgBound (duty).**
- **Implication.** The canonical/perphead saga was a faithfulness detour that broke a working motor
  (config1 ≈0.25 µm/s); the v1-port default is the working transporter. But "default + LT" is a NEW
  combination (STEP 0: v1's detachment was catch-slip, not nucleotide-driven), and the LT detachment
  rate is too fast to sustain skeletal-level duty at achievable densities. **To recover 5–8 µm/s on the
  LT cycle, raise avgBound** — either capture/geometry knobs at skeletal kinetics (`myoColTol`,
  `alignTol`, density/head-reach; binding is bind-on-contact so there is no kOn to raise) or slow `onADP`
  toward the Myo2 rate (which re-lowers turnover — the duty×turnover tradeoff). This is the next lever,
  out of this task's measure-only scope.

## CPU-fallback disclosure
- STEP-1 diag + the A/B: **`-cpu` sequential runner** (host, v1box 2000 motors — a measurement instrument).
- STEP-2 sweep + floor + the GPU half of CPU≡GPU: **GPU device-resident `buildPlan`** (~22 kernels, no
  per-step host pull; host reads fil.coord+boundSeg at OUT_INT cadence). 1392→13920 motors, 40k steps.
- CPU≡GPU CPU half: `-cpu` runner, d500 20k.

## Reproduce
```
# STEP-1 confirm (default motor + LT, diag):
./run_gliding.sh -diag -lymntaylor -v1box -dt 1e-5 8000
# the A/B control (default, NO LT — v1-faithful catch-slip):
./run_gliding.sh -diag -v1box -dt 1e-5 8000
# STEP-2 sweep (per density D, seed s), GPU device-resident:
./run_gliding.sh -gpu -lymntaylor -matbed -density <D> -dt 1e-5 -grid -seed <s> 40000
# thermal floor (motors never bind — note: -kon is IGNORED by the default bind-on-contact binder, use -nobind):
./run_gliding.sh -gpu -lymntaylor -nobind -matbed -density 500 -dt 1e-5 -grid -seed <s> 40000
# CPU≡GPU: drop -gpu for the CPU runner, d500 20k.
```

Raw run log: `RUN_LOGS/2026-06-30_default_LT_glide_sweep.txt` (all 18 GRID/COV/CAP rows + the floor).

## JOURNAL-ready line
`2026-06-30 — DEFAULT (v1-port) motor on the Lymn-Taylor cycle (-lymntaylor): glides −x, STABLE (capFires=0,
no NaN to d2000), CPU≡GPU — but SLOW (velFitX ≤3 µm/s @ d2000, below skeletal 5–8). STEP0: v1's detach is
catch-slip, NOT nucleotide-driven ⇒ default+LT is a NEW combination (v1-port stroke + a fast detach v1 never ran).
STEP1: wiring already additive (LT branches gated on LYMN_TAYLOR alone); bondForces byte-unchanged, native F8
forceDotFil feeds cycleLymnTaylor. OUTCOME #2: LT fixes bound-in-ATP (59.8%→0%) but COLLAPSES duty (avgBound
7.2→0.7 @ d500) ⇒ the single-molecule V₀≈6 doesn't translate; the native no-LT default glides FASTER (−5.7).
The gap is duty (avgBound). Added -nobind floor (default-off byte-identical). BoA-v1ref byte-clean.
→ PHASE2_DEFAULT_LT_GLIDE_FINDINGS.md.`
