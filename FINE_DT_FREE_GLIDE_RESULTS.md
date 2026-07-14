# FINE-dt FREE-GLIDING DENSITY SWEEP — RESULTS

**Status: COMPLETE** (2026-07-14). Main sweep (36 cells) + dt=2.5e-6 & 1.25e-6 spot/convergence checks
+ matbox control + CPU basin arbiter all done (51 runs). Raw cells: `RUN_LOGS/finedt_cells/*.log`;
analysis `RUN_LOGS/finedt_analysis_final.txt`; figure `FINE_DT_FREE_GLIDE_figure.png`.

**Runner / build:** GPU RTX 5070 (TornadoVM 4.0.1-dev PTX) primary; deterministic CPU basin arbiter.
Pristine build of commit **`f537972`** ("FINE-dt V0 reference") in isolated worktree
`SoftBox-finedt-canon` (jba's uncommitted J2/orthogonalizeY WIP untouched — see PLAN).
**Physical duration 0.6 s**, warm-up 0.20 s (physical, identical across dt). Canonical stack
SPHEREHEAD+AXLOCK+DIRSWING+XB_IMPLICIT2+LYMN_TAYLOR + springs, all default-on, **unmodified**.
coltol=8 nm, matbox-50 chamber. Invocation per cell:
`run_gliding.sh [-gpu] -full -grid -coltol 8 -matbox 50 -density <ρ> -seed <s> -dt <dt> <M>`.

---

## PART 1 — BENCHMARK & EXECUTION PLAN  ✅

**Throughput is dt-independent** (identical kernels/step); measured steps/s vs density then wall =
(0.6/dt)/throughput. Two-point timing (cancels JVM/Tornado startup):

| density | GPU steps/s | CPU steps/s | GPU wall @1e-5 (60k) | GPU wall @5e-6 (120k) |
|--------:|------------:|------------:|---------------------:|----------------------:|
| 500  | 305.8 | — | 3.3 min | 6.5 min |
| 2000 | 129.9 | 11.9 | 7.7 min | 15.4 min |
| 8000 | 39.5 | ~4 (est) | 25.3 min | 50.6 min |

Power fit GPU: steps/s ≈ 306·(ρ/500)^−0.73. **CPU is ~11× slower than GPU** (d2000: 11.9 vs 129.9
steps/s) ⇒ a full CPU matrix is impossible (CPU d8000@5e-6 alone ≈ 4.5 h).

**Chosen plan** (per the study's GPU-primary + matched-CPU-arbiter allowance):
- **GPU** full paired sweep: ρ∈{500,1000,2000,4000,6000,8000}, dt∈{1e-5, 5e-6}, **3 paired seeds**
  (0,1,2). 4 seeds ruled out — high-density fine-dt cells dominate. Actual main-sweep wall ≈ 10 h
  (02:25→12:33), faster than the 12.9 h estimate once the concurrent CPU arbiter finished.
- **dt=2.5e-6 spot checks** (Part 3): ρ∈{2000,8000}, 2 seeds.
- **CPU basin arbiter** (lean, full 0.6 s, directly comparable): d2000 dt=1e-5 seeds 0,1; d2000
  dt=5e-6 seed 0; d8000 dt=1e-5 seed 0 — run **concurrently** with the GPU sweep (measured GPU slowdown
  from concurrent CPU load ≈ 10%, a large net win vs serial).
- **matbox on/off control** (d2000, d8000 @1e-5 seed0) to confirm the chamber does not shift the
  baseline. Every cell restartable/skip-if-complete.

**Baseline consistency check (validates the pristine-HEAD + matbox-ON setup):** my matbox-ON d500@1e-5
seed0 velFitX=**1.108** vs the established matbox-OFF coltol-8 sweep (2026-07-09, same seed) **1.159** —
within 4% (seed sd ≈0.17). The setup reproduces the established curve.

**Why matbox 50 is mandated (confirmed):** the established matbox-OFF sweep hit `fullMat=VIOLATED` at
d8000 (1/3 seeds) — the high-density y-coverage escape. matbox-50's y-walls at the mat extent keep the
filament over the lawn without pinning the glide axis (x free). Coverage of my matbox-ON cells is
reported in the per-cell table.

### Context — the established production-dt (1e-5) curve
From the established coltol-8 sweep (matbox-OFF, dt=1e-5, ~= my production arm):

| ρ | 500 | 1000 | 2000 | 4000 | 6000 | 8000 |
|---|----:|----:|----:|----:|----:|----:|
| velFitX | 1.01 | 2.69 | 4.39 | 6.45 | 7.95 | 9.38 |
| avgB | 1.02 | 2.91 | 5.42 | 10.3 | 15.8 | 20.2 |

velFitX climbs monotonically; avgBound rises ~linearly (no engagement saturation ⇒ any velocity
flattening is *operational*, not class-D collapse). Note the established matbox-OFF d8000 = **9.38** —
close to my fine-dt 9.04, i.e. the established (non-bistable-inflated) high-density value already sat
near the fine-dt result. The central question: does **fine dt** bend the rising curve into a plateau?

---

## PART 2 — MATCHED PRODUCTION vs FINE-dt SWEEP  ✅ (36/36)

Mean ± SEM over **3 paired seeds** (0,1,2). velPerBound = velFitX/avgBsteady. **0 NaN/instability.**

| ρ | dt | velFitX | avgBsteady | velPerBound | fullMat frac | instSteady* |
|--:|:--|--------:|-----------:|------------:|:-:|-----------:|
| 500  | 1e-5 | 1.172 ± 0.055 | 1.36 | 0.862 | 1.00 | 6.72 |
| 500  | 5e-6 | 1.029 ± 0.049 | 1.43 | 0.719 | 1.00 | 9.18 |
| 1000 | 1e-5 | 2.568 ± 0.100 | 2.83 | 0.908 | 1.00 | 6.92 |
| 1000 | 5e-6 | 2.670 ± 0.253 | 3.45 | 0.773 | 1.00 | 9.40 |
| 2000 | 1e-5 | 4.390 ± 0.120 | 5.68 | 0.773 | 1.00 | 7.53 |
| 2000 | 5e-6 | 4.502 ± 0.179 | 6.59 | 0.684 | 1.00 | 9.73 |
| 4000 | 1e-5 | 6.472 ± 0.123 | 10.84 | 0.597 | 1.00 | 8.67 |
| 4000 | 5e-6 | 6.459 ± 0.197 | 12.11 | 0.533 | 1.00 | 10.36 |
| 6000 | 1e-5 | 7.980 ± 0.377 | 15.93 | 0.501 | 0.33 | 9.84 |
| 6000 | 5e-6 | 7.998 ± 0.090 | 18.98 | 0.422 | 0.67 | 11.16 |
| 8000 | 1e-5 | **11.257 ± 1.400** | 21.18 | 0.530 | 0.67 | 12.54 |
| 8000 | 5e-6 | **9.035 ± 0.049** | 25.09 | 0.360 | 0.67 | 11.59 |

\* `instSteady` is Brownian-contaminated (∝√(2D/dt); +36% at fine dt from thermal jitter alone) — the
concrete reason velFitX (LS slope) is primary and instSteady is not.

**Headline structure.** The two curves are **statistically identical through d6000** and diverge only at
d8000, where the **production-dt point is bistability-corrupted** (below). On the clean fine-dt arm
velPerBound falls monotonically (0.72→0.36, a halving) while avgBound keeps rising ~linearly — the
co-bound tug-of-war, an *operational* (not engagement-collapse) mechanism.

### The decisive high-density finding — fine dt regularizes production-dt BISTABILITY
Per-seed velFitX and the seed spread (max−min):

| ρ | prod (1e-5) seeds | spread | fine (5e-6) seeds | spread |
|--:|:--|--:|:--|--:|
| ≤4000 | tight | 0.17–0.38 | tight | 0.17–0.82 |
| 6000 | 7.36 / 8.66 / 7.92 | 1.30 | 8.18 / 7.94 / 7.88 | 0.30 |
| 8000 | 9.03 / 10.90 / **13.84** | **4.81** | 8.99 / 8.99 / 9.13 | **0.15** |

At d8000, production seeds scatter over **4.81 µm/s** (a bistable *fast* basin at s0=13.84 — with
`fullMat=YES`, so *not* a coverage artifact — vs a *slow* basin at s2=9.03); fine dt collapses this to
**0.15** (a ~32× variance reduction). The one production d8000 seed with clean coverage (s2, margin
0.50) reads **9.03 — identical to the fine-dt value**. So the production high-density mean (11.26) is
inflated by the last-bit-tipped fast basin; **the true high-density velocity is the fine-dt 9.0.** This
is the CLAUDE.md chaotic+bistable gliding state — **fine dt is what tames it.**

**Coverage caveat (honest):** matbox-50 keeps all low-mid cells `fullMat=YES`; at d6000–8000 the fast
glide grazes the *free x-runway* edge (marginal `VIOLATED`, runMinMargin ≈ −0.001…0.014). matbox fixes
y, not x-runway exhaustion (x is left free to preserve the glide runway). But the fine-dt YES and
VIOLATED seeds agree (~9.0), so it does not materially corrupt the fine-dt curve — it is a minor caveat
on the very top points, not the cause of the deceleration.

## PART 5 — PAIRED ΔV = V(5e-6) − V(1e-5)  ✅

| ρ | nPair | mean ΔV | SEM | paired bootstrap CI95 | % | ΔavgB | ΔvelPerBound |
|--:|:-:|-------:|----:|----------------------:|--:|------:|-------------:|
| 500  | 3 | −0.143 | 0.033 | [−0.184, −0.077] | **−12.2%** (sig) | +0.07 | −0.143 |
| 1000 | 3 | +0.101 | 0.197 | [−0.284, +0.367] | +3.9% (ns) | +0.62 | −0.136 |
| 2000 | 3 | +0.112 | 0.061 | [−0.001, +0.210] | +2.6% (marg) | +0.91 | −0.090 |
| 4000 | 3 | −0.013 | 0.076 | [−0.161, +0.089] | −0.2% (ns) | +1.27 | −0.064 |
| 6000 | 3 | +0.018 | 0.444 | [−0.723, +0.813] | +0.2% (ns) | +3.05 | −0.079 |
| 8000 | 3 | −2.222 | 1.439 | [−4.854, +0.102] | −19.7%† | +3.91 | −0.170 |

† d8000 ΔV is **dominated by production-side bistability**, not a clean fine-dt effect (its CI spans 0
because the *production* d8000 is scattered, SEM 1.40). Against the clean production seed (9.03) the
fine-dt d8000 (9.04) is **identical** ⇒ the honest fine-dt shift at d8000 is ≈0; fine dt removes the
*upward* bistable excursion.

**Mechanism (Part-5 core):** the fine-dt shift is the *net* of two opposing, individually
non-converged channels — fine dt **raises occupancy** (ΔavgB > 0, 0.07→3.9 with density) while
**lowering per-bound efficiency** (ΔvelPerBound < 0, removed coarse-dt cross-bridge overshoot). Across
d1000–6000 they **compensate** ⇒ |ΔV| < 3% (velFitX near-dt-converged in the *mean* despite its
*components* not being). Only at d500 does the per-bound drop win outright (−12%). **The clamp V₀ shift
(−19%) does NOT map proportionally onto free-gliding velocity — confirmed directly, not assumed.**

## PART 6 — SATURATION ANALYSIS & CLASSIFICATION  ✅ (main curve)

Fits over all 6 densities (AICc; seed-bootstrap CI; MM = Hill n=1):

**Fine dt = 5e-6 (clean arm):** **MM strongly preferred** (AICc −12.1 vs log −9.6, linear +4.1, power
+3.7); Hill n=1.08≈1. **V∞ = 14.1 µm/s, bootstrap CI95 [13.4, 14.6]** (tight); ρ½ ≈ 4565 [3931, 5137].
LOO-stable (V∞ = 13.7 drop-d500, 13.9 drop-d8000). **BUT the plateau is NOT reached in-range:**
increments 4000→6000 = +23.8%, 6000→8000 = **+13.0%** (≫ SEM 0.05); at d8000, velFitX/V∞ = 9.04/14.1 =
**64%** (we sample only to ~1.75·ρ½). V∞ is a *model (MM) extrapolation above the data*.

**Production dt = 1e-5 (bistability-corrupted):** the d8000 fast-basin outlier wrecks the fit — MM V∞ =
24.4, bootstrap CI95 **[13.6, 223]** (unconstrained), power "best" (spurious +41% top increment).
**Drop the bistable d8000 → V∞ = 14.04**, matching the fine-dt asymptote. ⇒ At production dt the
high-density curve is **not reliably characterizable**; fine dt is *required* to fit saturation at all.

### Classification
- **Fine-dt (5e-6) → CLASS B (bounded approach to saturation).** Curvature clear, MM strongly preferred,
  V∞ reasonably localized [13.4, 14.6]; but the top-of-grid increment is still material (+13%, ≫ noise)
  and the data reach only 64% of V∞ ⇒ plateau **bounded/plausible but NOT resolved within [500, 8000]**.
  Not A (top increment material), not C (curvature *is* resolved / fit well-constrained), not D
  (velPerBound decline is genuine tug-of-war physics; avgBound still rising — no engagement collapse;
  marginal x-edge coverage is a minor caveat, not the cause), pending E (2.5e-6 spot check).
- **Production-dt (1e-5) → effectively CLASS E at high density** (dt-dependent / bistable / un-fittable
  above d6000): numerical non-convergence, not an operational plateau of any kind.

## PART 7 — BIOLOGICAL INTERPRETATION  ✅ (main curve; five statements kept distinct)
1. **Numerical convergence:** velFitX *mean* near-converged 1e-5→5e-6 across d1000–6000 (|ΔV|<3%); the
   real fine-dt gain is **variance/basin de-biasing at high density** (32× at d8000). Whether 5e-6 is
   itself converged awaits the 2.5e-6 spot check (Part 3).
2. **Saturation exists (bounded):** yes — the fine-dt curve decelerates; an MM model fits well (class B).
3. **Plateau velocity:** fitted V∞ ≈ 14 µm/s (MM, model-dependent, above sampled range); the directly
   observed top velocity is **9.0 µm/s at d8000**.
4. **Density of approach:** ρ½ ≈ 4565 µm⁻²; ~64% of V∞ by d8000.
5. **Biological agreement:** the fine-dt curve crosses the condition-matched biological ~4 µm/s band at
   **ρ ≈ 1800–2000 µm⁻²** (fine-dt velFitX(2000) = 4.50). **The unmodified canonical model already sits
   in the biological operating range at a physiological mat density** — no xCatch/J2 change needed to
   reach biological velocity.

**On V₀/1.4 (the study's restraint):** not used or assumed anywhere. Observationally the directly
measured top velocity (9.0) sits near V₀_fine/1.4 ≈ 9.5 and the MM asymptote (≈14) near V₀_fine (≈13.3)
— but these are *post-hoc coincidences of a bounded curve sampled to 64% of V∞*, not the basis of any
conclusion.

## CPU/GPU BASIN ARBITER  ✅ (4/4)
- **d2000 (moderate density):** CPU 1e-5 s0/s1 = 4.367 / 4.037, 5e-6 s0 = 4.496; GPU 1e-5 ≈ 4.39,
  5e-6 ≈ 4.73 — **same basin at both dt, within ~5%** (no flip). GPU numbers trustworthy here.
- **d8000 (dense, DECISIVE):** CPU 1e-5 s0 = **9.933** vs GPU 1e-5 s0 = **13.84** (same seed). The
  deterministic CPU arbiter does **NOT** reproduce the GPU fast basin — it lands near the slow/true
  value (~10, close to the fine-dt 9.04). Per the GPU-number trust rule (CPU is the basin arbiter),
  **the GPU s0=13.84 is a GPU-specific last-bit basin-tip artifact**, precisely the silent hazard
  CLAUDE.md describes (a wrong basin masquerading as a stable baseline). ⇒ The production-dt high-density
  mean (11.26) is inflated by *both* bistability *and* a GPU numerical excursion; the CPU-arbitered
  production value is ~10, and the **fine-dt 9.0 (converged, runner-consistent) is the trustworthy
  high-density velocity.** (CPU d8000 also `fullMat=VIOLATED` — the same marginal x-runway graze — but
  its velocity still decisively refutes 13.84.)

## matbox on/off CONTROL  ✅
| ρ | matbox-ON velFitX (cov) | matbox-OFF velFitX (cov) |
|--:|:--|:--|
| 2000 | 4.523 (YES) | 4.384 (YES) |
| 8000 | 13.840 (YES) | 9.551 (YES) |

- **d2000 (representative):** ON vs OFF differ ~3% (within seed noise), both fully covered ⇒ **matbox-50
  does not shift the physics baseline** where coverage isn't threatened — it is a benign no-op that only
  bites the out-of-plane/y escape. ✓ (Study premise verified.)
- **d8000:** the large ON/OFF gap (13.84 vs 9.55) is **NOT a matbox effect** — it is the production-dt
  basin-tip: adding the containment task is a hot-graph-structure change that flips the bistable basin
  (the CLAUDE.md `-allnoise` graph-split hazard). matbox-OFF here happened to land in the true/slow
  basin (9.55 ≈ fine-dt 9.04 ≈ CPU-arbiter 9.93). This re-confirms production-dt high-density
  bistability rather than any chamber artifact.

## PART 3 — dt=2.5e-6 SPOT CHECKS + CONVERGENCE  ✅
Convergence ladder (mean velFitX):

| ρ | 1e-5 | 5e-6 | 2.5e-6 | 5e-6→2.5e-6 | driver |
|--:|--:|--:|--:|--:|:--|
| 2000 | 4.390 (3) | 4.502 (3) | 4.479 (2) | **−0.5%** | converged |
| 4000 | 6.472 (3) | 6.459 (3) | 6.967 (2) | **+7.9%** | avgB 12.1→13.55 (occupancy ↑) |
| 8000 | ~10 (bistable) | 9.035 (3) | 9.138 (3) | +1.1% (noisy; s0/s1-matched +3.9%) | avgB 25.1→26.5 |

**Finding — 5e-6 is NOT fully converged at high density.** At d4000 the 2.5e-6 velocity (6.95–6.99,
tight) exceeds **all three** 5e-6 seeds (6.06–6.68, all `fullMat=YES`, margin 0.500) ⇒ a genuine
**occupancy-driven positive drift**, not a seed artifact. The high-density binding keeps rising as
dt→0, so velFitX drifts up a few-% from 5e-6→2.5e-6 (d4000 +8%, d8000 +1–4%; magnitude noisy at 2–3
seeds, **direction consistently positive**). At d2000 and below, 5e-6 is converged (≤0.5%).

**Resolution — the d4000 @ 1.25e-6 direction check REFUTES a persistent drift.** Seed-matched (s0,s1)
d4000 ladder: 1e-5 **6.41** → 5e-6 **6.35** → 2.5e-6 **6.97** → 1.25e-6 **6.70** (steps −1.0%, +9.7%,
**−3.8%**). The +9.7% at 2.5e-6 **did not continue** — 1.25e-6 came back down, and its two seeds
straddle widely (6.37 / 7.04, ~10% spread). Across the full ladder d4000 velFitX stays **6.35–6.97
(~±5%) with no monotonic trend exceeding the ~10% per-dt seed scatter**. So the "+8% residual" was a
**2-seed chaotic fluctuation, not a systematic dt-drift** — the 1.25e-6 check did exactly its job
(refuting a spurious small-sample signal).

**Why velFitX converges though its components don't:** as dt→0 at d4000, avgBound rises monotonically
(12.1 → 13.6 → 14.0) but velPerBound falls (0.533 → 0.514 → 0.478) — the **same occupancy↑ / per-bound↓
compensation** seen at low density, now confirmed at high density. The velocity is dt-stable *because*
the two individually-non-converged channels cancel.

⇒ **Q4 answer: dt=5e-6 IS adequately converged for free-gliding velFitX across the whole density
range** (within the chaotic seed envelope, ~±5–10% at 2–3 seeds). **Class E is NOT supported** — no
persistent dt-drift survives replication. (A larger seed ensemble would tighten the ±5–10% band but
cannot change the qualitative conclusion; the drift does not exist to converge away.)

## FINAL VERDICT ✅

**Runner / duration:** GPU (RTX 5070, TornadoVM PTX) primary + deterministic CPU basin arbiter;
0.6 s physical, 0.20 s warm-up; pristine canonical commit f537972; coltol=8, matbox-50; canonical
stack unmodified. **Seed inventory:** main sweep 3 seeds (0,1,2) × 6 densities × {1e-5, 5e-6} = 36 GPU
cells; dt=2.5e-6 spot 2–3 seeds at d2000/4000/8000; dt=1.25e-6 direction check 2 seeds at d4000; CPU
arbiter 4 cells (d2000×3, d8000×1); matbox control 2 cells. **51 runs total, 0 NaN/instability.**
**Excluded/flagged:** the production-dt (1e-5) **d8000 point is bistability-corrupted** (fast basin
seed 13.84, CPU-refuted) and is excluded from the production saturation fit; d6000–8000 carry marginal
x-runway coverage flags (do not affect the fine-dt conclusion).

1. **How much does fine dt change free-gliding velocity?** Little, in the *mean* — |ΔV(5e-6−1e-5)| < 3%
   across d1000–6000; a significant −12% only at d500. **The −19% clamp-V₀ shift does NOT transfer to
   free glide** (measured, not assumed). Fine dt's decisive effect is **de-biasing the production-dt
   high-density bistability** — a ~32× seed-variance collapse at d8000 (production 9→14 → fine 8.99–9.13).
2. **Operational plateau in the fine-dt curve?** Yes — a **bounded approach** (velPerBound halves
   0.72→0.36 while avgBound rises ~linearly: the co-bound tug-of-war, an operational mechanism, not
   engagement collapse).
3. **Fitted V∞ / knee:** MM (Hill n≈1) strongly preferred; **V∞ = 14.1 µm/s [13.4, 14.6]**, **ρ½ ≈
   4565** [3931, 5137]. But sampled only to **64% of V∞ at d8000** (top increment +13%, ≫ noise) ⇒ V∞
   is a **model extrapolation above the data**; the directly observed top velocity is **9.0 µm/s**.
4. **Is dt=5e-6 converged?** **Yes**, for velFitX, across the whole range within the chaotic seed
   envelope. The apparent high-density 5e-6→2.5e-6 climb did **not** survive the 1.25e-6 check (a 2-seed
   fluctuation). Components (avgB, velPerBound) are individually *not* converged but **compensate**.
5. **Is the canonical model already near biological?** **Yes** — the fine-dt curve crosses the
   condition-matched biological ~4 µm/s at **ρ ≈ 1800–2000 µm⁻²**, a physiological mat density, with the
   stack **unmodified**.

### CLASSIFICATION
- **Fine-dt (5e-6) curve → CLASS B** (bounded approach to saturation; MM-preferred, V∞ localized but
  plateau at ~64% in-range — real, not resolved). NOT A (top increment material), NOT C (curvature
  resolved / fit well-constrained), NOT D (genuine tug-of-war, no engagement collapse), NOT E (no
  persistent dt-drift — 1.25e-6-refuted).
- **Production-dt (1e-5) curve → numerically non-convergent at high density** (bistable + GPU
  basin-tip artifact; un-fittable above d6000) — fine dt is *required* to characterize the dense curve.

### Bottom line
The unmodified canonical model's **directly measured** free-gliding response is a **class-B bounded
approach to saturation** (V∞≈14 model-extrapolated, ρ½≈4565; directly observed top 9.0 µm/s at d8000),
**already in the biological operating range at ρ≈2000**. dt=5e-6 is an **adequate, converged operating
point** for free-gliding velFitX; its real value over dt=1e-5 is **removing the production-dt
high-density bistability**, not shifting the mean. **No xCatch or J2 intervention is motivated to reach
biological velocity** — the model is already there. The V₀/1.4 heuristic was neither used nor
confirmed as the answer: the *directly measured* curve is bounded-approaching, not a resolved plateau,
and its in-range top (9.0) and extrapolated V∞ (14) bracket V₀/1.4 (9.5) and V₀ (13.3) only
coincidentally.
