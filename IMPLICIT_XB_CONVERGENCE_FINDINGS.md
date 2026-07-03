# Implicit cross-bridge at dt=1e-5 vs the dt-refined convergence target — PARTIAL (head-only insufficient)

**Date:** 2026-07-03. Validates the banked locally-implicit cross-bridge solve (`-xbimplicit`, already wired
CPU+GPU, default byte-identical) at the production dt=1e-5 against the dt-refined explicit convergence target on
the promoted default motor (sphere-head + axial-lock + directed-swing). GPU device-resident `-full` bed
(13.37×2 µm, ~26 740 motors), d1000, aeta=0.1, LS-centroid `LONG_ROW` estimator (`PROPER_SPEED_ANALYSIS.md`),
≥1 s on-bed window. **No model/release/stroke change; measurement only; `BoA-v1ref` untouched.** Raw:
`RUN_LOGS/2026-07-02_implicitxb_seed0.txt`, `…_seeds12.txt`.

## VERDICT — PARTIAL. The head-only implicit solve does NOT deliver the converged glide at production dt.
Implicit@1e-5 raises avgBound 14.8 → 21.5 and drops the glide 3.02 → 2.80 — a real, ~free improvement in the
right direction, but it closes only **~16 % of the avgBound gap** and **~27 % of the glide gap** to the dt-refined
target (avgBound 56.7 / glide 2.20). **We do NOT have the converged glide (~2.2) at production dt.** The reason is
structural and was predicted by the banked result: the head-only implicit fixes only the bound head's *own*
translational overshoot, but on the gliding assay the cross-bridge stretch variance is dominated by the **filament
(site) motion**, which the head-only solve leaves explicit. The converged glide needs a **coupled (head+site)
implicit solve or a sub-stepped cross-bridge** — the banked local form is insufficient here, as
`implicit-crossbridge-integrator-lever` flagged.

---

## STEP 0 — which spring overshoots? F8 (the cross-bridge Hookean tip-spring), NOT J1. → build gate PASSES.
**Structural (decisive).** The force-dependent catch-slip release and the 12 pN cap both read **F8 only**:
`forceDotFil = Dot(F8_spring, seg.uVec)` (bondData[12]) and `forceMag = |F8| = myoSpring·dist`
(`CrossBridgeSystem.bondForces`/`registerForceDot`). **No J1 quantity feeds the release.** F8 is a plain Hookean
spring (`fmag = myoSpring·dist`) with the explicit overshoot factor **r = k·dt/γ_head ≈ 0.531 at dt=1e-5**
(k=myoSpring=1 pN/nm, γ_head≈1.885e-8 N·s/m). J1/J2 are **fracMove PAIRS connection pins** (bounded joint-gap,
dt-robust — the chain F3/F4 form), and in the promoted default the J1 *angular* converter is OFF (directedSwing
drives the stroke). So F8 is the only dt-overshooting spring **and** the one driving false detachment.

**Empirical (the bound-population census, `-stretchcensus`, seed 0):** every F8 signature moves monotonically
with convergence, confirming the overshoot drives the detachment:

| config | F8 extension (nm ±sd) | \|fdFil\| (pN) | dwell (ms) | avgBound |
|--|--:|--:|--:|--:|
| explicit@1e-5 | 5.89 ± 5.01 | 3.27 | 0.63 | 14.8 |
| implicit@1e-5 | 5.09 ± 4.88 | 2.97 | 0.83 | 21.5 |
| explicit@5e-6 (target) | 4.30 ± **2.00** | 2.37 | 1.86 | 56.7 |

The explicit@1e-5 spring is over-stretched (5.9 nm) with huge variance (±5.0 nm — the spurious excursions the
catch exponential detonates on) and inflated axial load (3.27 pN) ⇒ short dwell (0.63 ms) ⇒ low avgBound. As the
solve converges, extension, its SD, |fdFil|, and dwell all relax toward the physical values. **Note the implicit
barely cuts the extension SD (5.01→4.88, −3 %) while convergence cuts it hard (→2.00)** — the SD is dominated by
the *site* (filament-glide) motion, not the head's own overshoot, which is exactly why head-only implicit only
partially converges.

**Correction to the task premise.** The banked solve is **NOT J1** — it is F8-implicit-on-the-head-translation
(`CrossBridgeSystem.snapshotHeadCenter`+`implicitCorrect`; the one JOURNAL line calling it "implicit-J1" is a
mislabel). Since it targets exactly the overshooting spring (F8), the STOP condition ("banked J1 won't fix an
F8 overshoot") does **not** apply — proceed. (It is nonetheless *insufficient* — see the verdict — but for a
different reason: head-only vs the site-dominated stretch, not wrong-spring.)

## STEP 2 — the convergence check (seed 0; mean±SD over seeds 0/1/2 where noted)

| config | avgBound | glide \|v_axial\| | per-bound drift | throughput (steps/s) | fullMat |
|--|--:|--:|--:|--:|:--|
| explicit dt=1e-5 (current default) | 14.8 | 3.02 | 0.204 | 259 | YES |
| **implicit dt=1e-5 (this task)** | **21.5** | **2.80** | **0.130** | **251 (−3 %)** | YES |
| explicit dt=5e-6 (waypoint, NOT converged) | 56.7 | 2.20 | 0.039 | 246 (2× the steps) | YES |
| explicit dt=2.5e-6 (bracket — still climbing) | 99.3 | 1.72 | 0.017 | 246 (4× the steps) | YES |

Implicit seeds 1/2 were **not completed** (stopped early — the approach is being reconsidered). The seed-0
result is decisive on its own: implicit@1e-5 avgBound 21.5 sits far below the target (56.7 at 5e-6, 99.3 at
2.5e-6) and well above the 14.8 explicit baseline (whose 3-seed SD was ±0.3), so the ~+45 % avgBound rise and
the PARTIAL verdict are unambiguous, not seed noise.

**Fraction of the gap closed (implicit vs the 1e-5→2.5e-6 span, since the target keeps moving):** avgBound
**~8 %** (14.8→21.5 against a ≥99 that is still rising), glide ~15 %. The drift moves relatively most (the
implicit does cool the head's thermal excursion, `σ²/(r(2+r))` vs `σ²/(r(2−r))`, 0.58×), but the headline
avgBound barely moves — the fast site is the bottleneck.

**Fork verdict: PARTIAL** (of the three: converged-at-production-dt / no-effect / partial). The implicit solve
helps but does not fully converge; a wider implicit scope (the *site* is still explicit) is required.

## STEP 3 — stability, cost, parity
- **Stability at dt=1e-5 implicit:** STABLE — fullMat=YES, no NaN, no whip (the whole point; the implicit head
  never overshoots). avgBound is steady, not collapsing.
- **Throughput:** implicit@1e-5 = 251 vs explicit@1e-5 = 259 steps/s — **~3 % overhead** (2 extra kernels:
  `xbSnap`+`xbImpl`, one launch each over nMotors) ⇒ **~free**, it does NOT eat the dt-refinement savings. But it
  also does not *deliver* the dt-refinement: the converged glide still needs explicit dt=5e-6 (2× the steps) or a
  wider implicit/sub-step. So the cost side is good; the convergence side is the shortfall.
- **CPU≡GPU (implicit path):** not separately re-run this session (stopped early). The `-xbimplicit`
  `snapshotHeadCenter`/`implicitCorrect` are plain per-head methods over the SoA arrays (one division per bound
  head, no atomics/KernelContext, disjoint writes) wired identically into the CPU `stepOrig` and the GPU
  TaskGraph — race-free by construction, the same one-impl/two-runner pattern already CPU≡GPU-validated for every
  other cross-bridge kernel. A short aggregate-within-SEM confirmation is a cheap loose end if the approach is
  revisited.

## Is 56.7 the true converged value? NO — convergence is not even bracketed by dt=2.5e-6.
The dt=2.5e-6 bracket **overshoots** 56.7 badly: avgBound **99.3** (vs 56.7 at 5e-6), glide **1.72** (vs 2.20),
dwell 3.16 ms (vs 1.86), F8 ext 4.10±1.74 nm. So **avgBound is still climbing and glide still falling** as dt
halves (14.8→56.7→99.3 ; 3.02→2.20→1.72). The "target" from the viscosity doc (56.7/2.20) was a **waypoint, not
convergence** — the true converged avgBound is **>99** (saturating only when ~all reachable heads stay bound) and
the converged glide is **≤1.7 and still dropping**. This makes the implicit's shortfall starker (it closes ~8 %
of a still-growing gap) AND revises the honest converged glide **downward** — it is **not ~2.2, it is ≤1.7**.

## Bottom line
**No — we do not have the converged glide at production dt, and the converged glide is even lower than previously
stated (≤1.7 µm/s, unbracketed at dt=2.5e-6, still falling — not 2.2, and certainly not 3.0).** `-xbimplicit` is a
cheap (~free, −3 % throughput), stable, correct-direction partial fix (avgBound 14.8→21.5, glide 3.02→2.80), but
the head-only scope closes only ~8 % of the avgBound gap because the gliding cross-bridge stretch is
**site-motion-dominated** (the census SD is set by the filament glide, not the head's own overshoot — implicit
cuts the SD only −3 % vs convergence's −60 %). The converged production glide requires the **coupled head+site
implicit solve or the sub-stepped cross-bridge** (`substep-feasibility-verdict`), not the local head-only form.
**Deeper flag for the planner:** the gliding assay is far more dt-sensitive than the ±3 % `dt-faithful-ceiling`
envelope suggested — avgBound roughly *doubles per dt-halving* with no plateau through 2.5e-6, so the production
dt=1e-5 glide (~3.0) is a heavily under-bound operating point, and the "true" faithful glide is a moving,
still-unconverged quantity that only a genuinely converged cross-bridge integrator will pin.

## Runs
```
GlidingHarness -gpu -full -grid -density 1000 -stretchcensus -xbimplicit -dt 1e-5   -seed <0..2> 150000   # implicit
GlidingHarness -gpu -full -grid -density 1000 -stretchcensus            -dt 1e-5   -seed 0      150000   # explicit baseline
GlidingHarness -gpu -full -grid -density 1000 -stretchcensus            -dt 5e-6   -seed 0      300000   # target
GlidingHarness -gpu -full -grid -density 1000 -stretchcensus            -dt 2.5e-6 -seed 0      600000   # bracket
```
Flags pre-existing (`-xbimplicit`, `-stretchcensus`); default byte-identical. `BoA-v1ref` byte-clean.
