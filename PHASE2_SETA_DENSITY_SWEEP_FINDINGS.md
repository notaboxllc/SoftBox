# Phase-2 SET-A — speed–density EMERGENT benchmark (force-matched κ, frozen kinetics, full-mat GPU-resident)

**2026-06-28. Flag-gated harness additions (`-matbed`, `velFitX`/coverage in `measureGrid`); the κ default WAS
changed (authorized: force-match to 5 pN skeletal stall). Bond (xCatch 2.5), τ (0.5 ms), kOn (2e5), and the
nucleotide cycle HELD — NO kinetics nudge (the clean read). GPU device-resident; sole occupant of aorus;
`BoA-v1ref` byte-clean.**

## TL;DR — the clean read uncovers TWO separable problems; the speed–density curve cannot be extracted
1. **Step 0 (κ force-match) DONE:** κ = **3.82e-20 N·m/rad** ⇒ tip-stiffness **0.597 pN/nm**, independent motor
   stall **5.00 pN** (was 8.4 pN @ κ=6.4e-20). New production default. Tightens the 4c peak≈stall to 6.0/5.0 = **1.2**.
2. **Full-mat geometry DONE:** a 5.8 × 1.2 µm bed (filament 1.93 µm at the +x edge, glides −x through a uniform motor
   lattice, ~2.9 µm runway) + a per-run coverage check windowed to the measurement window. Verified full-mat for
   density ≤ 4000.
3. **The assembled CALIBRATED config-1 motor has two distinct failures at the faithful dt, and a controlled
   dt=1e-6 + default-motor pair DISENTANGLES them:**
   - **(a) NUMERICAL instability — curable.** At the faithful **dt=1e-5** the filament is **violently whipped**
     (segments thrown; max bend **844 nm**, straightness folds to **0.247**). At **dt=1e-6 (10× finer) this VANISHES**
     — the filament is **dead straight** (max bend **21 nm**, straightness **1.000**). It is an explicit-integration
     overshoot of the **uncapped Hookean-J1** under collective load (the instability the Config-1 build BANKED). The
     banked **implicit-J1 `1/(1+r)` / cross-bridge sub-step** is the fix. (Earlier I mis-read this as dt-insensitive:
     `instSteady` *grows* with finer dt purely because the sampling interval shrinks; the **filament-bending** metric
     is the correct instability proxy and shows finer dt cures it.)
   - **(b) NO COHERENT TRANSPORT — physical.** With the numerics clean at dt=1e-6 (filament straight, stable), the
     calibrated motor **still does not glide**: directed velocity **≈ 0.25 µm/s** (essentially stationary; centroid
     moved 0.05 µm in 0.12 s), vs the **default motor's −5.7 µm/s** on the identical bed. The directedness collapse
     is **real**, not a numerical artifact — the dt=1e-6 control removes the only confound and confirms it.
4. **It is NOT the κ force-match** (old κ 6.4e-20 and new κ 3.82e-20 both give ≈0 net) and **NOT a harness/integrator
   bug** (the **default v1-faithful motor glides −5.7 µm/s and stays straight** on the same bed, same dt). Both
   failures are **specific to the calibrated config-1 cross-bridge** (PAIRS pins + uncapped Hookean-J1).
5. **The prior "≈9 µm/s @4000" was a settling artifact** — measured over M=8000 (a window DURING the one-time −x
   settling jump) and via the settling-/window-inflated `longWindowSpeedXY`. The honest post-settling slope is ≈0.
6. **MM fit: there is no rising-saturating curve to fit.** The directed velocity is flat/sign-flipping near zero
   (mean velFitX −0.46/−0.40/−1.36/+1.40/−0.20 µm/s over density 500→4000, all within noise of 0). A formal MM fit
   rails KM to the grid edge — **non-physical** (artifact of fitting MM to noise). No Vmax/KM. **NO kinetics retune
   attempted** (the clean read is the point).

---

## Step 0 — force-match κ (authorized default change)
`stall = (κ/L)·θ` (Hookean-J1 converter at full 60° deflection; the validated one-shot held-pose `motorStallPN`
eval, exact = analytic). Target 5 pN skeletal unitary force (Finer 1994):

| quantity | old (4c/4d) | **force-matched** |
|---|---|---|
| κ (N·m/rad) | 6.4e-20 | **3.82e-20** |
| tip-stiffness κ/L² (pN/nm) | 1.00 | **0.597** |
| independent stall (pN) | 8.38 | **5.00** |
| 4c peak/stall (peak 6.0 pN) | 0.72 | **1.20** |

Live-verified: `[matbed] … κ=3.82e-20 (tip 0.597 pN/nm, stall 5.00 pN)`. As anticipated, force-side self-consistency
only — it does NOT restore the glide (old/new κ both ≈0 net) and is NOT the cause of either failure. `KAPPA` default
updated in `GlidingHarness.java` (re-baselines the `-csrecal`/`-stiffsweep` stall readout to 5.0 pN — those are
measurement instruments, not gates).

## Full-mat geometry (the measurement is honest)
- **Mechanism:** a single long bed (NOT periodic — TornadoVM binding/cross-bridge are not wrapped). The filament
  (11 seg, **1.93 µm**) starts at the **+x edge** (x0=1.6) and glides −x through a uniform random motor lattice at
  density `D`; bed `x∈[−3.2,2.6]` (5.8 µm), `y∈[−0.6,0.6]`, `nMot = D·6.96`. The −x runway (~2.9 µm) + y-width cover
  the glide + rotation/Brownian wander.
- **Coverage check** windowed to the measurement window (steady 2nd half that `velFitX` is fit over): per-sample
  filament min/max x,y vs the bed edges; `fullMat = minMargin > 0.05 µm` (`COV_ROW`). Held for D ≤ 4000.
- **velFitX (the velocity for the curve):** directed glide = **−slope of a least-squares fit of centroid x(t)** over
  the steady 2nd half — the min-variance directed-drift estimator (the 2-point net chord and `instantaneousSpeed`
  badly overcount the large thermal/tug-of-war wander).

## The decisive controls — default motor + dt=1e-6 (identical bed, density 1000, 6960 motors)
Filament shape from the dumped viewer frames (straightness = end-to-end / contour; max bend = max node deviation
from the end-to-end chord). Directed glide = centroid-x slope.

| run | dt | straightness (mean / **min**) | max bend | directed glide | verdict |
|---|---|---|---|---|---|
| **DEFAULT** (v1-faithful F8) | 1e-5 | 0.999 / 0.966 | 111 nm | **−5.7 µm/s** | straight, glides ✓ |
| **CONFIG1** (calibrated) | 1e-5 | 0.956 / **0.247** | **844 nm** | ≈0 (+x drift) | **whipped** + no glide |
| **CONFIG1** (calibrated) | **1e-6** | **1.000 / 1.000** | **21 nm** | **0.25 µm/s** | straight ✓ but **stationary** |

⇒ **(a) the whipping is numerical (dt=1e-6 cures it: 844→21 nm); (b) the no-glide is physical (persists at dt=1e-6:
0.25 vs the default's 5.7 µm/s).** The default motor on the same bed isolates BOTH to the config-1 cross-bridge
(bed, chain F3/F4, containment, integrator, dt all held; only the cross-bridge mechanism differs).
Viewer runs: `threejs_default_d1000`, `threejs_setadensity_d1000` (config1 dt=1e-5), `threejs_config1_dt1e-6_d1000`.

## The sweep (GPU device-resident, full-mat, post-settling slope) — multi-seed
Calibrated config1 path (PAIRS + Hookean-J1 + kOn 2e5 + averaged catch), faithful dt=1e-5, M=40000 (knee scan
M=30000), seeds 0–3 (+knee seed). Throughput ~240–410 steps/s over 7k–28k motors.

| density | n | avgBound | velFitX (µm/s, mean ± SEM) | note |
|---|---|---|---|---|
| 500  | 5 | 2.2  | **−0.46 ± 0.09** | sparse binding |
| 1000 | 5 | 5.4  | **−0.40 ± 0.14** | avgBound near v1 ~7; net ≈0 |
| 2000 | 5 | 10.3 | **−1.36 ± 0.72** | oscillation-dominated (whipping); window-unstable (−3.77/+0.13/…) |
| 3000 | 3 | 12.7 | +1.40 ± 1.76 | **numerical blowups begin** (NaN seeds dropped) |
| 4000 | 4 | 13.8 | −0.20 ± 0.98 | mostly NaN; few survivors meaningless |
| 6000 | 1 | 25.6 | (NaN) | **unstable** |

Directed velocity is flat, sign-inconsistent, and ≈0 where measurable; `instSteady` (the wander) explodes with
density (6→113 µm/s) while net stays ≈0; **numerical blowups start at density ~3000** (the whipping going terminal).
avgBound rises with density and reaches v1's ~7 near density ~1000–1500 — **binding is fine; force coherence is not.**

## CPU≡GPU (density 1000, M=20000)
The benchmark quantity (directed velocity) AGREES within the chaotic envelope: **GPU velFitX 0.05** vs **CPU mean
+0.20 ± 0.27** (3 seeds: 0.41/0.53/−0.34) — both ≈0. avgBound is bursty/intermittent (GPU 7.4; CPU 1.4/6.6/2.8,
mean 3.6) but the CPU and GPU ranges OVERLAP (1.4–7.4 on both runners) — within the aggregate-within-SEM standard
for this chaotic, bursty binding. No silent CPU fallback (the sweep is GPU device-resident; this is the only CPU run).

## MM fit + Walcott/Warshaw comparison
No rising-saturating Michaelis-Menten form exists in the data — the directed velocity is flat near zero. A formal
fit rails KM to the search-grid edge (non-physical); there is **no model Vmax/KM**. The Walcott/Warshaw skeletal
control (Vmax ≈ 2.9 µm/s, saturating with P(N)<1) **cannot be placed** until coherent transport is recovered.
Plots: `seta_speed_density.png` (V vs density, flat near 0), `seta_speed_density_normalized.png` (binding rises with
density while directed glide stays ≈0 — the directedness diagnostic).

## Bail boundaries — outcome (TRIGGERED, reported, nothing forced)
- **"Curve does NOT saturate (no plateau) → report":** TRIGGERED — flat near zero, no MM form.
- **"Contradicting the full-mat / clean-read premise → commit nothing [physics], report":** the single-molecule
  calibration does not transfer to coherent ensemble gliding. **No kinetics nudged; no physics edited** (only the
  authorized κ force-match + measurement-only harness additions).
- **High density numerically unstable (≥3000 NaN):** reported — the uncapped-J1 overshoot going terminal.

## What this means for the planner (the two threads to pick up)
1. **NUMERICAL (known, banked fix):** the explicit uncapped-Hookean-J1 overshoots under collective load → whipping
   at dt=1e-5, terminal NaN at density ≥3000. **Fix = the banked implicit-J1 `1/(1+r)` update or a cross-bridge
   sub-step** (MOTOR_BENCHMARK_TARGETS §6 / the dt-arc tools). dt=1e-6 is a stopgap (10× cost) that confirms the fix
   direction but is not a production answer. This must land BEFORE any production-density ensemble run.
2. **PHYSICAL (the real open question):** even fully stabilized (dt=1e-6, straight), the calibrated config-1 motor
   produces **~0.25 µm/s, not transport.** The single-molecule calibration nails duty, catch-slip, peak≈stall, and
   step∝lever, yet the ensemble does NOT glide — whereas the v1-faithful default motor (same bed) glides −5.7 µm/s.
   So the directedness lives in something the canonical/config-1 re-architecture changed: candidates to investigate
   — the **powerstroke→filament force coupling/polarity** (config-1 drives the lever via J1 + PAIRS pins; does the
   stroke project onto the filament axis as net −x force, or cancel?), the **two-point head pin** suppressing the
   working displacement, and the **averaged-catch / reaction-limited-kOn** timing vs the stroke. This is the
   transferability gap (single-molecule-correct, ensemble-incoherent) and is the substantive next investigation —
   NOT a kinetics tuning.

## What changed (additive; default-motor path byte-unchanged; `BoA-v1ref` untouched)
- `GlidingHarness.java`: `KAPPA` default 6.4e-20 → **3.82e-20** (force-matched); `-matbed` full-mat bed; `measureGrid`
  gains the post-settling least-squares `velFitX`, the windowed full-mat coverage check (`COV_ROW`), and the
  `[matbed]` banner. Default (non-canonical) gliding path unaffected (regression: still glides −x, straight).
```
# the sweep (per density D, seed n):
./run_gliding.sh -matbed -config1 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -density <D> -grid -gpu -seed <n> 40000
# the decisive controls (viewer):
./run_gliding.sh -matbed -density 1000 -gpu -3js threejs_default_d1000 20000                         # default: glides, straight
./run_gliding.sh -matbed -config1 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -density 1000 -dt 1e-6 -gpu -3js threejs_config1_dt1e-6_d1000 120000   # config1 dt=1e-6: straight but stationary
```

## CPU-fallback disclosure
The sweep runs **device-resident on the GPU TaskGraph** (~240–410 steps/s). CPU appears only in the one CPU≡GPU
cross-check above. No silent CPU fallback.
