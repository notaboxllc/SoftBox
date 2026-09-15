# Long, high-density, full-mat gliding assay of the revised site-normal motor

**STATUS: COMPLETE. Verdict: CLASS A — STRONG GLIDE, met on BOTH ensemble seeds independently.** The
+2.000 µm criterion was reached by seed 20260901 at step 911 827 (t = 2.2796 s) and by seed 20260902 at step
958 627 (t = 2.3966 s), both inside the pre-set 10^6-step horizon, on the CPU site-normal production path,
with nothing tuned. Every number below is from executed data.

- **Worktree:** `/home/jba/Code/SoftBox` · **Branch:** `gpu-mat-bottlenecks-explicit-singlehead`
- **Date:** 2026-08-13/15 · **Raw:** `RUN_LOGS/motor_audit/site_normal_long_glide{,_seed20260901,_seed20260902}/`
  (+ `..._ETA001_FAILED/` — the withdrawn η = 0.01 attempt, §3b)
- **Runner:** the **CPU sequential runner** throughout. **No GPU work is launched.**
- **NOTHING IS TUNED.** No molecular parameter, gate, threshold, rest pose, rate or chemistry setting was
  touched. The only new code is bookkeeping plus one *arithmetically neutral* execution change (§2).
- **Reproduce:**
  ```bash
  cd ~/Code/SoftBox && ./scripts/build.sh
  ./scripts/run_site_normal_long_glide.sh -gates        # Phase 0/3 pre-run gates
  ./scripts/run_site_normal_long_glide.sh -run          # the long trajectory
  ./scripts/run_site_normal_long_glide.sh -run -resume  # resume from checkpoint.bin
  # the ensemble seeds, with viewer frames:
  ./scripts/run_site_normal_long_glide.sh -run -eta 0.1 -dt 2.5e-6 -seed 20260902 -steps 1000000 \
       -workers 8 -out RUN_LOGS/motor_audit/site_normal_long_glide_seed20260902 \
       -3js RUN_LOGS/motor_audit/site_normal_long_glide_seed20260902/threejs_glide_seed20260902
  # analysis:
  python3 scripts/site_normal_long_glide_analysis.py [run_dir]   # single trajectory, Phase 12
  python3 scripts/site_normal_glide_ensemble.py                  # across-seed ensemble
  ```

---

## 0. HEADLINE

# THE REVISED SITE-NORMAL MOTOR GLIDES — CLASS A, STRONG GLIDE.

**+2.000 µm of net pointed-leading translation reached on BOTH independent ensemble seeds**, inside the
horizon, with nothing tuned. The criterion, the horizon and the stop rule were all fixed before any data
existed and none was altered.

| | seed 20260901 | seed 20260902 |
|---|---|---|
| stop | `TARGET_REACHED` step 911 828 | `TARGET_REACHED` step 958 628 |
| simulated time | 2.2796 s | 2.3966 s |
| net forward | **+2.00007 µm** | **+2.00006 µm** |
| live-axis cumulative | +1.99470 µm (0.3 %) | +2.00242 µm (0.1 %) |
| full-run LS velocity | **+0.8215 µm/s** | **+0.7898 µm/s** |
| centroid path length | 280.74 µm | 295.09 µm |
| avgBound (max) | 1.3348 (**8**) | 1.2530 (**8**) |
| captures / strokes | 3772 / 3673 | 3663 / 3573 |
| mean axial force | **−0.2817 pN** | **−0.3230 pN** |
| force samples neg : pos | 679 010 : 538 097 | 680 055 : 521 116 |
| duty P(0)/P(1)/P(≥2) | 0.261 / 0.349 / 0.391 | 0.283 / 0.362 / 0.354 |
| residence | 804 µs | 817 µs |
| detach ATP / rigor | **3770 / 0** | **3662 / 0** |
| azimuth lower/side/upper | 4 607 / **1 120 667** / 91 833 | 7 419 / **1 120 423** / 73 329 |
| peak axial force | 9.83 pN | 9.66 pN |
| invalid / solverFail | **0 / 0** | **0 / 0** |
| contour | 2.10600 µm conserved | 2.10600 µm conserved |

**Milestones, monotonic, none reversed, on both seeds:**

| µm | seed 20260901 | seed 20260902 |
|---|---|---|
| 0.25 | t = 0.2816 s | 0.2298 s |
| 0.50 | 0.5490 | 0.5621 |
| 1.00 | 1.0724 | 1.2270 |
| 1.50 | 1.7617 | 1.8623 |
| **2.00** | **2.2796** | **2.3966** |

Ten crossings across the two seeds plus the primary's two — **twelve in total, every one in the expected
pointed-leading direction, none reversed.**

**The polarity question is closed by the axis agreement.** `live-axis cumulative forward` (+2.00242 µm)
matches `net forward` on the FIXED initial axis (+2.00006 µm) to 0.1 %: the filament travelled two microns
**along its own axis**, not two microns of laboratory-x that happened to point the right way. That was the
Phase-6 distinction and it is unambiguous.

**Mechanism, now well sampled over 3663 attachments:**

| observable | value |
|---|---|
| duty distribution | P(0 bound) 0.283 · P(1) 0.362 · P(≥2) 0.354 · P(≥5) 0.0096 |
| mean residence | 327 steps = 817 µs |
| detachment cause | **3662 ATP / 0 rigor / 0 other** — the Lymn–Taylor pathway, exclusively |
| bound nucleotide occupancy | ADP 984 958 · ADP·Pi 143 373 · NONE 72 840 · **ATP 0** (bound heads are post-stroke, as required) |
| axial force decomposition | **680 055 negative vs 521 116 positive** samples — a real tug-of-war with a persistent 57:43 pointed bias |
| azimuth occupancy | lower 7 419 · **side 1 120 423 (93 %)** · upper 73 329 |
| peak axial force | 9.66 pN — physiological, below the 12 pN release cap |

The filament is **unbound 28 % of the time and glides anyway** — an intermittent, few-head regime, not a
continuously-engaged one.

---

## 0b. The primary trajectory (seed 20260813), stopped at t = 1.000 s by request

**Primary trajectory complete at t = 1.000 s (400 000 steps), CPU sequential runner, nothing tuned.**

| observable | value at t = 1.000 s |
|---|---|
| net forward displacement | **+0.94596 µm** (Brownian floor 0.19647 µm ⇒ **ratio 4.81**) |
| full-run LS forward velocity | **+0.9505 µm/s** |
| start→end quotient | +0.9359 µm/s |
| rolling 50 ms windows | +1.0130 µm/s, **24/24 positive** |
| rolling 100 ms windows | +1.0465 ± 0.323 µm/s, **34/34 positive** |
| drift-vs-diffusion | `ratio 4.81` vs **4.83 predicted for a constant 1.00 µm/s drift**; second-half `√t` slope **+0.388** |
| mean axial force on the filament | **−0.2560 pN** (negative = productive) |
| engagement | `avgBound` 1.34, **max 7 simultaneous**, 1727 captures / 1675 strokes |
| azimuth occupancy | lower 3489 · **side 503 092 (94 %)** · upper 29 989 |
| filament rotation | ended −5.9°, wandering ±3° — no gross twirling |
| numerical health | **invalid 0, solverFail 0**, contour exactly 2.10600 µm, z within ±6.4 nm |

**Every channel agrees and none is borderline:** 58 of 58 rolling windows positive across two window sizes,
three velocity estimators within 1 %, and an independent force measurement of the productive sign sustained
for the whole run. **Confirmed on two further independent seeds** (§6b): all three reach +0.25 µm in the
expected direction at 0.89–1.35 µm/s, all three with a negative mean axial force.

**The primary's own classification is B** (it was stopped at t = 1.0 s by request, before the criterion
could be reached); **the assay as a whole is class A**, met by seed 20260902 above.

### The two retractions this result had to survive — recorded, not overwritten

1. **At step 90 000 I called "DRIFT RESOLVED ≈1.26 µm/s" and then withdrew it.** The `√t` slope that
   supported it eroded (+0.509 → +0.302 → **+0.196**) as the ratio sat pinned near 3.1 for eight consecutive
   checkpoints while `t` nearly doubled. That withdrawal was correct at the time: t = 0.2–0.375 s contains a
   genuine slow patch (a +0.68 µm/s disjoint window inside an otherwise ~1.1 µm/s trajectory).
2. **The test then passed on its own unchanged terms.** By t = 0.9 s the ratio had climbed to 4.78 against a
   constant-drift prediction of 4.83 — agreement to 1 % — and the slope recovered to +0.445. **No criterion
   was moved.** The bar was set before the data existed, failed, and was later met.

> The reason this matters: the report's other main contribution is catching two overclaims in earlier work
> (§14i's `nPull`/`nDrag` polarity statistic and its velocity numbers). It would be worthless if this
> document made the same class of error, so the failed intermediate call is preserved in full.

**The contrast that makes the point.** The failed η = 0.01 arm "reached" +0.25 µm in **1605 steps** on an
11.6 nN force spike; this run took **74 270 steps** to reach the same displacement at **1.68 pN**, with
per-segment coverage still complete. That is the difference between a numerical artefact and a glide, and it
is why §3b had to be settled before any velocity could be quoted.

---

## 1. WHAT THIS ASSAY IS, AND WHAT IT IS NOT

It asks ONE question: **does the revised site-normal motor still glide?** It is a deliberately crude
compatibility test at deliberately high density over a deliberately long horizon. It is **not** a velocity
campaign, **not** a saturation fit, and **not** a density calibration. No result here should be read as a
measurement of the gliding velocity of this motor.

It supersedes, as the gliding evidence, the 5000-step `-glide-compat` smoke test recorded in
`docs/motor/SITE_NORMAL_HEAD_BINDING.md` §14i — which was far too short to resolve a velocity (its own
Brownian noise floor was 6.41 µm/s over the measurement window) and whose `nPull`/`nDrag` polarity reading
has already been **withdrawn** in `docs/motor/SITE_FRAME_POWER_STROKE_AUDIT.md`. The deterministic
site-frame fixture in that audit established that the bound power stroke has the **correct axial polarity at
8 of 8 helical azimuths**; this assay asks whether that translates into ensemble motility.

---

## 2. EXECUTION PATH — CPU, and the one execution change that made the run possible

### 2.1 CPU, stated up front

`ExplicitCompleteMatHarness.buildGlidingGraph` **throws** when `siteNormalOn()`: the four kernels the
site-normal + 3-D-head path needs (`matBeamGeomTilt`, `matS2SolveStepTilt`, `headAxisStep`,
`siteCoupleStep`) have no validated device lowering. Gate P0 asserts that refusal rather than assuming it.
There is therefore no GPU option, no silent fallback, and the old non-chi / non-site-normal GPU motor is
never substituted. The harness prints

```
EXECUTION = CPU SITE-NORMAL PRODUCTION PATH
```

### 2.2 Why culling had to become load-bearing

The explicit-S2 step is dominated by `matS2SolveStepTilt`, and that kernel is the one stage the explicit
path historically never culled — it solved **all N** motors every step (`stepGlidingCPU` literally set
`active[m] = 1` for every m, commented "no-cull smoke"). Measured on this machine:

| scene | full step | of which `matS2SolveStepTilt` |
|---|---:|---:|
| 1 200 motors (3×1 µm, 400/µm²) | 342.5 ms | 337.3 ms (98.5 %) |
| 21 000 motors (7×1 µm, 3000/µm²) | 5 860 ms | 5 863 ms (>99.9 %) |

At 5.9 s/step a 10^6-step trajectory is 68 days. The assay is impossible without culling the solve.

### 2.3 What was changed — and what was NOT

Two additive, default-inert changes, plus one new harness:

1. **`TwoBodyBeamAnalyticGpu.matS2SolveStepTilt`** gained a single guard at the top of its per-motor loop:
   `if (restC.get(8*nM + m) != counts.get(4)) continue;`. `restC` grew from `8N` to `9N` (row 8 is the new
   tag) and `exCounts` from 4 to 5 ints (element 4 is the worker id). **Both default to 0, so the test never
   fires on any existing path and every existing run is byte-identical.**
2. **`ExplicitCompleteMatHarness.stepGlidingCPU`** gained an optional `MatCullPlan`. With a null plan it is
   the historical all-active step, unchanged. With a plan it (a) runs the validated production per-segment
   UNION cull `MatSoaSlice.matCull` to fill `e.active`, and (b) tags kept motors round-robin with a worker id
   so the SAME kernel can be invoked once per worker.

**No force law, gate, threshold, rate, rest pose or chemistry setting was touched.** The step sequence is
the single existing `stepGlidingCPU` body — there is no second copy of the physics.

### 2.4 The cull, precisely

`MatSoaSlice.matCull` is the validated production rule: a motor is active iff it is **BOUND**, or its motor
site lies within `queryR` of the **clamped closest point of ANY live segment** — a per-segment union, never
a filament-midpoint window (the Exp-4D-ii correction). `queryR` is not chosen here: `buildS2Mat` already
defines it for this model as `G4_QUERYR (30 nm) + free S2 length (40 nm) + 10 nm margin = 80 nm`. It was
**not enlarged**.

A culled motor is unbound and out of binding range; its beam and joint angles simply do not advance while it
waits. Chemistry (`cycleLymnTaylor`) still runs over **all N** motors every step, as before.

---

## 3. PRE-RUN GATES — all PASS

Console: `RUN_LOGS/motor_audit/site_normal_long_glide/gates_console.txt`; report: `.../gates.md`.

### P0 — the device path REFUSES (execution path is CPU, not asserted by assumption)

`buildGlidingGraph(e, true)` on a site-normal scene throws
`"site-normal head binding has no validated device path yet (CPU runner only)"`. **PASS.**

### C — cull completeness (the Phase-3 hard requirement)

Protocol: the real production scene (21 000 motors, 3000/µm², 7×1 µm) stepped 3000 steps with the cull ON;
every 25 steps `ChiralSiteSystem.siteGateA` — the same kernel, same live state — is re-evaluated with **all
motors active**, and every motor whose candidate the brute gate ACCEPTS is checked against the cull.

| | |
|---|---:|
| brute-accepted candidates | 0 |
| accepted-but-CULLED | **0** |
| mean active set | 850.9 / 21 000 (4.05 %) |

Zero accepted candidates makes that literal test **vacuous at this horizon** — capture is rare (the funnel in
`SITE_NORMAL_HEAD_BINDING.md` §14 measures ~1e−5 accepted per eligible motor-step), so 120 sampled steps is
simply not enough events. It is reported, and it is **not** the evidence.

**The evidence is the geometric margin, which is the same requirement stated so that it can actually be
measured.** Gate `g0` cannot fire unless the LIVE head point `xF8` is within **3.00 nm** of a real site.
Over **2 417 570 culled motor-samples**, the closest any culled motor's `xF8` ever came to the filament was

> **57.11 nm — a factor 19.0 clear of the 3.00 nm acceptance radius.**

So no culled motor could have been a candidate, independently of how many candidates happened to be
accepted. **PASS**, and `queryR` was not enlarged.

### W — worker striping is arithmetic-neutral

1500 steps of the same culled scene, 1 worker vs 8 workers:
`max|Δq| = 0`, `max|Δnodes| = 0`, `max|Δchi| = 0`, `max|Δ filament coord| = 0`, boundSeg mismatches `0`.
**PASS — BIT-IDENTICAL**, so the parallelism is a scheduling change only.

### A — culled vs ALL-ACTIVE freeze control: the cull is a NO-OP, not merely a good approximation

Console: `gateA_console.txt`. The cull *freezes* a far motor's beam and joint angles while it waits, so the
question gate C does not answer is whether that changes the thermal history a motor brings back with it when
the filament arrives. Matched arms — same seed, same scene (400 heads/µm², 3×1 µm, 6000 steps), same
production `η = 0.1 / dt = 2.5e-6` — one with the cull, one with **every motor solved every step**:

| arm | avgBound | captures | bound-steps | net forward | wall |
|---|---:|---:|---:|---:|---:|
| culled (2 workers) | 0.1232 | 3 | 739 | +0.00143 µm | **237 s** |
| all-active (no cull) | 0.1232 | 3 | 739 | +0.00143 µm | **2610 s** |
| ratio | 1.000 | 1.000 | 1.000 | — | **11.0× faster** |

**Identical to every printed digit, including the net displacement.** I designed this as an
order-of-magnitude regime check and expected chaotic decorrelation; instead the trajectories coincide. The
reason is structural: the ONLY channel by which a detached motor can influence anything else is by binding,
and a culled motor is by construction out of binding range (gate C: never closer than 57 nm against a 3 nm
gate). Freezing its beam therefore changes nothing any other body can observe.

**Stated limit of this evidence.** It is 6000 steps at low density: it demonstrates the *mechanism* (culled
motors are causally disconnected), not a theorem that a stale-vs-thermalised angle can never matter once a
frozen motor re-enters range and binds. What it does establish is that the 11× speed-up costs nothing
measurable here, and the 2610 s arm confirms the no-cull path really did 11× the work.

### Worker scaling and the step budget (measurement, not a gate)

Production scene, ~1092 active motors:

| workers | ms/step | speedup |
|---:|---:|---:|
| 1 | 317.7 | 1.00 |
| 2 | 212.1 | 1.50 |
| 4 | 151.1 | 2.10 |
| 8 | 138.4 | 2.30 |
| **16** | **108.3** | **2.93** |

Per-stage cost at N = 21 000 (ms/step): `headRollStep` 2.59, `matBeamGeomTilt` 1.90, `matCull` 1.17,
`siteGateA` (culled) 1.02, everything else < 0.15 each — **total serial ≈ 8 ms**, so the step is ~93 %
the culled S2 solve. That solve is latency-bound rather than compute-bound (SMT at 16 threads beats 8
physical threads by 28 %), which is why the speedup saturates near 3×. The run uses **16 workers**.

---

## 3b. A LOAD-BEARING FINDING BEFORE ANY GLIDING NUMBER — the requested (dt, η) pair is UNSTABLE

**`dt = 2.5e-6 s` together with `η = 0.01 Pa·s` puts the F8 cross-bridge bond past the explicit
integrator's stability limit. It is not a marginal effect and it is not a property of the motor: it is the
integrator. The first attempt at this assay was run at that pair and had to be stopped.**

Raw: `RUN_LOGS/motor_audit/site_normal_long_glide_ETA001_FAILED/`.

### What happened

The run reported "MILESTONE +0.25 µm" at step 1605 and "+0.50 µm" at step **1606** — a quarter-micron in
ONE 2.5e-6 s timestep, i.e. ~10^5 µm/s. The milestone record shows the axial force summed over
bound heads at that step:

```
milestone  step   fwd_um    faxSum_pN
0.25       1605   0.32700   11592.7463      <-- 11.6 nN from ONE bound head
0.50       1606   0.52424     215.4801
```

By step 2000 the filament was at `z ∈ [−15.91, +13.47] µm` — ejected sixteen microns out of a chamber that
is nanometres deep — and had drifted off the lawn edge in y (`cy = 0.505`, lawn half-width 0.5). Every value
stayed **finite**, so a NaN/Inf check never fired. Finiteness is not health.

### The mechanism, measured

A per-step trace (`eta0.01_dt2.5e-6_trace.tsv`) shows the onset exactly. Nothing is wrong until step 169,
when a single motor binds. From that step:

| step | z-min (µm) | z-max (µm) | max bond force (pN) |
|---:|---:|---:|---:|
| 169 | −0.0028 | 0.0028 | 5.90 |
| 173 | −0.0083 | 0.0028 | 15.36 |
| 177 | −0.0164 | 0.0048 | 36.58 |
| 181 | −0.0297 | 0.0058 | 62.64 |
| 185 | −0.0428 | 0.0096 | 70.88 |
| 189 | −0.0693 | 0.0078 | 116.16 |
| 191 | −0.0764 | 0.0060 | 133.71 |

The z extremes **swap sign on every single step** while the amplitude grows geometrically. That is the
signature of the explicit overdamped update `Δx = (F/γ)·dt` when `dt·k/γ > 2`: the amplification factor is
`|1 − dt·k/γ|`. Measured growth is ×1.155 per step over 22 steps, i.e.

> **`dt·k_F8/γ ≈ 2.16` — just past the stability threshold of 2.**

The bond ends up 65.5 nm long (a cross-bridge that should be a few nm), because the filament is oscillating
away from a head that is anchored to the lawn.

### The control that identifies the cause

Identical scene, seed, cull, worker count and timestep; **only η differs**:

| arm | max bond force | filament z | attachment lifetimes |
|---|---|---|---|
| η = 0.01, dt = 2.5e-6 (**as requested**) | 5.9 → **134 pN**, growing | ±3 nm → ±85 nm → **±16 µm** | ~30 steps, then blow-up |
| η = 0.10, dt = 2.5e-6 (**canonical**) | **1–5 pN**, stationary | **±3.5 nm** for 2000 steps | 130, 230, 330, 430 steps |
| η = 0.01, dt = 2.5e-7 (**scaled dt**) | **3–5 pN**, stationary | **±3.5 nm** for 3000 steps | 130 → 520 steps, up to 5 bound |

This is not the cull (the cull only freezes DETACHED motors that are ≥57 nm away — gate C — and bound motors
are never culled), and not the striping (gate W is bit-identical). It is `γ ∝ η`: dropping η tenfold drops
the drag tenfold and multiplies `dt·k/γ` by ten.

### Why this was foreseeable, and what it implies for the earlier smoke test

`CLAUDE.md` already records the rule, from the viscosity campaign: the two force-law families relax
differently in η, so **`dt(η) = dt₀·η/η₀` is REQUIRED for coherence, not hygiene — never compare viscosities
at fixed dt.** For η = 0.01 that prescribes `dt = 2.5e-7`, which is exactly the third row above. It also
records a *faithful dt ceiling of 1e-5 set by cross-bridge overshoot* at the canonical η — scaled to
η = 0.01 that ceiling becomes 1e-6, and 2.5e-6 is above it.

**Consequence for the record:** the 5000-step `-glide-compat` smoke in `SITE_NORMAL_HEAD_BINDING.md` §14i ran
at this same (dt = 2.5e-6, η = 0.01) pair. Its per-seed "glide" values of **+1.65 / +11.39 / −6.80 µm/s** —
already reported as unresolved against a 6.41 µm/s noise floor — are therefore **not trustworthy even as
noise**: at least part of that scatter is this instability, not Brownian motion. They should not be quoted.

### What this assay does instead, and why

The primary trajectory is run at the **canonical, validated pair `η = 0.1 Pa·s`, `dt = 2.5e-6 s`**, with
every other parameter exactly as specified. This is a deliberate, stated deviation from the requested
η = 0.01, taken because:

1. the requested pair is numerically invalid — demonstrated above, not asserted;
2. making η = 0.01 valid requires `dt = 2.5e-7`, which buys only **0.25 s of simulated time** in the same
   wall-clock budget where the canonical pair buys **2.5 s**. The Brownian centroid noise floor happens to be
   the same 0.31 µm in both cases (D ∝ 1/η and T ∝ 1/η cancel), while the expected glide displacement is
   ~6× larger in the canonical arm — so the canonical pair has ~6× the signal-to-noise **for the motility
   question**;
3. the repo's own viscosity result is that gliding **speed** is nearly viscosity-insensitive
   (`v ∝ η^−0.20`), so "does it glide" is answerable at either viscosity.

**What is lost by this choice, stated plainly:** η = 0.1 has ~2.3× lower `avgBound` and suppresses twirling
below detectability. So the canonical arm is *conservative* for recruitment — if the motor glides here, it
glides; if recruitment turns out to be the limit, an η = 0.01 / dt = 2.5e-7 arm is the right follow-up and is
already shown stable above (and shows visibly better engagement: up to 5 bound heads by step 600).

### Health guards added as a consequence

Finiteness checks would not have caught this. The harness now stops on: `|bond force| > 200 pN` on 20
consecutive steps (the model's own faithful release cap is 12 pN, so 200 pN is unambiguously numerical);
filament centroid more than 0.5 µm off the lawn plane; or the centroid leaving the lawn footprint.

---

## 4. SCENE AND FIXED PARAMETERS (Phase 4)

| quantity | value | source |
|---|---|---|
| lawn | 7.0 × 1.0 µm | task specification |
| head density | 3000 heads/µm² | task specification |
| motors | 21 000 (single-headed ⇒ heads = motors) | `g4NMot = ρ·MX·MY` |
| actin | 12-segment flexible chain, contour ≈ 2.106 µm | `G4_NSEG`, canonical bending |
| dt | 2.5e-6 s | as specified |
| η | **0.1 Pa·s (canonical)** — the specified 0.01 is numerically unstable at this dt, see §3b | deviation, stated |
| filament Brownian | ON | |
| motor / S2 Brownian | ON | |
| `SITE_NORMAL_BIND` | ON, g6 RETIRED, g2 RETIRED | |
| site lattice | `every4` sparse helical | `PATH_B_SITE_MODE = 3` |
| capture gate | `angle(xHeadHat, −n_site) ≤ 25°` | untouched |
| `RAND_BASE_AZ` | off | |
| converter / binding / stroke skew | 0° | |
| steric, twirl torque | none | |
| z support | `matZConfine` (z-slab off — the configuration the prior smoke used) | |
| cull | per-segment UNION, `queryR = 80 nm` | `buildS2Mat` |
| max horizon | 10^6 steps = 2.5 s simulated | task specification |
| stop rule | net forward ≥ +2.000 µm, or ≤ −2.000 µm (reversal) | task specification |

**Polarity and the forward axis.** Barbed = end2 = `+uVec` (the audited Exp-3B convention, re-verified in
`SITE_FRAME_POWER_STROKE_AUDIT.md` §1). A free filament glides **pointed-first**, so the expected
translation direction is `−u_fil(0) = −x`, and `dx_forward(t)` is the signed projection of
`centroid(t) − centroid(0)` onto that FIXED initial axis. Absolute displacement is never the success
criterion.

---

## 5. TELEMETRY

Per-checkpoint rows are in `trajectory_summary.csv` (every 10 000 steps = 25 ms of simulated time, and every
2000 steps over the first 20 000), live status in `progress.json`, milestones in `milestones.tsv` +
`milestone_*nm.tsv`, mat coverage in `coverage_t0.tsv` / `coverage_final.tsv`.

### Mat coverage at t = 0 (Phase 16)

Cull candidates per filament segment, all 12 segments:

| seg | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| candidates | 144 | 158 | 159 | 156 | 148 | 157 | 149 | 145 | 143 | 171 | 156 | 145 |

Uniform to ±9 % across the whole contour — **no segment is motor-starved and the cull is contour-complete**,
which is the property the Exp-4D-ii midpoint-window defect violated.

### Trajectory

*(final numbers filled at the end; this is the interim record as of step 60 000, t = 0.150 s)*

**Health throughout:** `invalid = 0`, `solverFail = 0`, filament z confined to ±5.7 nm, contour 2.106 µm
conserved, `e2e` 2.068–2.083 µm, mean bend ≤ 0.4°, filament orientation within ±1.2° of its initial axis
(so the fixed-initial-axis and live-polarity-axis forward measures coincide to 0.1 %), active set stable at
~1080–1130, 125–129 ms/step.

**Engagement:** `avgBound` 1.17–1.38 with **up to 6 simultaneous bound heads**; 245 captures, 236 strokes,
245 ATP-pathway detachments by t = 0.150 s. Azimuth occupancy of bound sites is overwhelmingly the **side**
class: 636 lower / 68 093 side / 4 413 upper.

**The two independent channels agree.** *Kinematic:* net forward +0.188 µm against a Brownian floor of
0.076 µm. *Mechanical:* the mean axial force on the filament, classified **by force sign against filament
polarity** (NOT the retracted `F·v` power metric), settled from ≈ 0 to a stable **−0.22 pN** — negative is
the productive sign — with a propulsive sum of ≈ −1.08 pN against a resisting sum of ≈ +0.81 pN, i.e. a real
tug-of-war with a consistent pointed-directed bias rather than a 50/50 split.

> **Known reporting defect (not a physics bug):** the `distinctSites` CSV column samples the *instantaneous*
> bound-site count, so it logs 0 whenever a checkpoint lands on a zero-bound step. Distinct bound sites are
> reported from the milestone snapshots (`milestone_*nm.tsv`), which enumerate bound motors individually.

---

## 5b. NOTE ON READING THE EARLY TRAJECTORY

The Brownian centroid noise floor grows as `sqrt(2 D t)`; at the canonical viscosity `D_par ≈ 0.0193 µm²/s`,
so the floor is 0.020 µm at t = 10 ms, 0.098 µm at 0.25 s and 0.31 µm at 2.5 s. **A displacement is only
evidence of transport once it exceeds that curve**, and a velocity fitted over a few milliseconds is
meaningless no matter how large it looks — this is precisely the trap the withdrawn §14i smoke fell into.
Early checkpoints are recorded for health, not for velocity.

---

## 6. RESULT — the primary trajectory (seed 20260813, t = 1.000 s)

### 6a. The drift/diffusion discriminator, in full

A constant drift `v` makes `fwd/√(2Dt)` grow as `√t`; pure diffusion leaves it O(1) and flat, and makes a
fitted slope decay as `√(2D/t)`. Both were tracked from the start:

| t (s) | fwd (µm) | floor (µm) | ratio | verdict at the time |
|---:|---:|---:|---:|---|
| 0.050 | 0.0630 | 0.0439 | 1.43 | ambiguous |
| 0.100 | 0.1551 | 0.0621 | 2.50 | ambiguous |
| 0.225 | 0.2863 | 0.0932 | 3.07 | *called resolved — later withdrawn* |
| 0.300 | 0.3289 | 0.1076 | 3.06 | flat band |
| 0.375 | 0.3881 | 0.1203 | 3.23 | **withdrawn: ambiguous** |
| 0.525 | 0.5315 | 0.1424 | 3.73 | ambiguous |
| 0.900 | 0.8901 | 0.1864 | **4.78** | **resolved** (predicted 4.83 for 1.00 µm/s) |
| 1.000 | 0.9460 | 0.1965 | **4.81** | **resolved** |

### 6b. Milestones, and cross-seed reproducibility of them

| seed | +0.25 µm at | mean v to milestone | +0.50 µm at | chunk v |
|---|---|---:|---|---:|
| 20260813 (primary) | step 74 270, t = 0.1857 s | 1.346 µm/s | step 180 677, t = 0.4517 s | 1.107 µm/s |
| 20260902 | step 91 932, t = 0.2298 s | 1.088 µm/s | — | — |
| 20260901 | step 112 623, t = 0.2816 s | 0.888 µm/s | — | — |

Every crossing is smooth (each lands on the threshold, e.g. 0.25014 and 0.50006) at physiological force —
1.68 pN on 1 head, 6.64 pN on 4 heads, −0.48 pN on 2 heads. **Contrast the failed η = 0.01 arm, which
"reached" +0.25 µm in 1605 steps on an 11.6 nN spike and +0.50 µm one step later.**

### 6b-ii. The noise floor is CONSERVATIVE — the real scatter is 11× smaller than free Brownian

At a matched horizon t = 0.500 s the three independent lawns give:

| seed | v_matched (µm/s) | ratio | mean axial force (pN) |
|---|---:|---:|---:|
| 20260813 | +0.9942 | 3.56 | −0.2560 |
| 20260901 | +0.9384 | 3.10 | −0.2509 |
| 20260902 | +0.9701 | 3.29 | −0.3300 |
| **mean ± SEM** | **+0.9676 ± 0.0162** | 3.32 ± 0.13 | **−0.279 ± 0.026** |

Three independent seeds agreeing on velocity to **1.7 %** looked implausibly tight, so it was checked
against theory rather than accepted:

```
expected sd of an OLS slope over [0, 0.5 s] for a FREE filament : 0.305 µm/s
observed across-seed sd                                          : 0.028 µm/s   ⇒ 11x tighter
```

**This is physically expected and it matters for how every ratio in this report should be read.** With
`avgBound ≈ 1.3` the filament is intermittently **tethered** by bound cross-bridges, so its effective
diffusion is far below the free-filament value `D_par = 0.0193 µm²/s` used to build the floor
`√(2Dt)`. Consequences:

1. **Every signal/floor ratio quoted here is CONSERVATIVE, not inflated** — including the headline 4.81.
   The floor overestimates the real trajectory noise by roughly an order of magnitude, so the true
   significance of the displacement is materially higher than the stated ratio.
2. **The velocity is a reproducible property, not a per-trajectory accident.** 0.938 / 0.970 / 0.994 µm/s
   across independent lawns is a much stronger statement than the single-trajectory drift test can make.
3. It retrospectively explains the flat-ratio episode at t = 0.2–0.375 s that forced the §0 retraction: with
   the real noise an order of magnitude below the assumed floor, that stretch was a **genuine slow patch in
   the motor's behaviour**, not statistical wandering.

*(The free-filament `D_par` is retained as the floor rather than an empirical one, because it is the honest
a-priori null — an empirically fitted floor would be tuned by the very data being tested. It is simply
noted as conservative.)*

### 6c. Velocity shape (Phase 12)

`APPROXIMATELY STEADY`: 100 ms windows give first half +1.161, second half +0.932 µm/s. Not accelerating,
not pausing, not bursting. The trajectory does contain one genuine slow patch (t ≈ 0.15–0.30 s, +0.68 µm/s)
— that is what produced the flat ratio band and the withdrawn call.

### 6d. Mat coverage during translation (Phase 16)

Per-segment cull-candidate counts stay uniform as the filament translates, so the cull remains
contour-complete and no segment becomes motor-starved:

| | seg 0…11 |
|---|---|
| t = 0 | 144 158 159 156 148 157 149 145 143 171 156 145 |
| at +0.25 µm | 136 134 151 165 159 149 153 154 160 150 160 169 |
| at +0.50 µm | 158 157 131 133 134 148 151 173 179 163 142 170 |

### 6e. The +2 µm criterion — MET

**Reached by seed 20260902 at step 958 627 (t = 2.3966 s), inside the 10^6-step horizon** — see §0. The
primary itself was stopped at t = 1.0 s at the user's request so the machine could carry two visualised
ensemble seeds; it therefore ended at +0.946 µm and is individually class B. The criterion, the horizon and
the stop rule were all fixed before any data existed and none was altered.

**Margin note, recorded because it was genuinely close.** At step 880 000 seed 20260902 needed 0.165 µm with
120 000 steps left, and at one point its projection sat *behind* the pace required. It was explicitly decided
NOT to extend the 10^6 cap to secure a class-A outcome — moving a pre-registered horizon to obtain a
favourable classification is exactly the post-hoc adjustment this report exists to avoid. It made the
criterion on its own.

> **A note on `coverage_final.tsv`:** the primary was stopped externally at exactly step 400 000, so its
> end-of-run block (final coverage + `summary.txt`) did not execute. Nothing is lost — every cumulative
> telemetry channel is written into `trajectory_summary.csv` at each checkpoint, and coverage is recorded at
> both milestones above.

---

## 7. WHAT WAS NOT RUN, AND WHY

*(filled at the end)*
