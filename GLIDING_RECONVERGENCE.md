# Gliding convergence re-measure — convergent skeleton + required correction: how far is 1e-5 from dt→0?

**Date:** 2026-07-08. Four timesteps (1e-5, 5e-6, 1e-6, 5e-7), two correction arms, matched sim-time,
2–3 seeds. **Re-measures every stale convergence number** (all prior figures — per-bound 0.736→~1.57, "63 %
recovered," "~37 % coupled residual" — predate `-structrate`, i.e. were measured with the motor skeleton
FREEZING at fine dt). Diagnostic only, no promotion; float32; race-free; `BoA-v1ref` untouched.

Config `-full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000`, GPU device-resident
(~234 steps/s, 26740 motors), **matched SIM-TIME 0.6 s** (2nd-half 0.3 s steady window) ⇒ 60k/120k/600k/1.2M
steps. `-ratefix -structrate` ON throughout. Arms:
- **A — uncorrected** (`-ratefix -structrate`).
- **B — required correction on** (`+ -allnoise`: both F8-bond ends; the gate's REQUIRED single-bond
  correction — F9/F10/J1 already ratefixed). `-allnoise` factor **dt-adaptive, confirmed**:
  ×0.857/×0.889 @1e-5 → ×0.987/×0.990 @1e-6 → ×0.993/×0.995 @5e-7 → 1 as dt→0.

## The table (velFitX / avgBound / per-bound = velFitX/avgBound, mean ± SEM)

| dt | n | arm A velFitX | arm A avgB | **A per-bound** | arm B velFitX | arm B avgB | **B per-bound** | B−A |
|--:|:-:|--:|--:|--:|--:|--:|--:|--:|
| 1e-5 | 3 | 2.325 ± 0.119 | 3.000 ± 0.090 | **0.774 ± 0.019** | 3.261 ± 0.346 | 2.577 ± 0.087 | **1.259 ± 0.094** | +0.485 |
| 5e-6 | 3 | 3.066 ± 0.252 | 3.651 ± 0.287 | **0.839 ± 0.007** | 3.122 ± 0.303 | 2.216 ± 0.238 | **1.417 ± 0.079** | +0.578 |
| 1e-6 | 2 | 3.087 ± 0.029 | 4.076 ± 0.057 | **0.757 ± 0.004** | 3.543 ± 0.013 | 2.561 ± 0.018 | **1.383 ± 0.005** | +0.626 |
| 5e-7 | 2 | 3.638 ± 0.067 | 4.119 ± 0.173 | **0.884 ± 0.021** | 3.091 ± 0.624 | 2.024 ± 0.234 | **1.512 ± 0.134** | +0.628 |

per-seed per-bound — A: 1e-5[0.736,0.791,0.796] 5e-6[0.841,0.827,0.850] 1e-6[0.754,0.761] 5e-7[0.905,0.863];
B: 1e-5[1.397,1.080,1.299] 5e-6[1.561,1.290,1.399] 1e-6[1.379,1.388] 5e-7[1.646,1.378]. Seeds are tight at
1e-6 (both arms <1 %); the 5e-7 arm B is the noisiest point (SEM 0.13).

**Byte-identity anchor / clean natural control:** arm A @1e-5 (2.325 / 3.000 / 0.774, 3-seed) reproduces the
**stale no-`structrate` uncorrected 1e-5 EXACTLY** (`structrate` is byte-identical at refDt). So **arm A =
the stale uncorrected curve + the skeleton reformulation**; any fine-dt divergence is `-structrate` alone
(same config, same seeds, same harness). The stale no-`structrate` fine-dt reference is uncorr @6.25e-7 =
velFitX **7.086** / avgB 4.450 / per-bound **1.592** (`CONSTRAINED_VARIANCE_PROBE.md` §SYSTEM-WIDE STEP-3).

## Read-out (the four requested)

### 1. The dt→0 limit now — MOVED DOWN ~2×; approximately dt-stable, NOT razor-flat (partial bail)
The honest uncorrected curve (arm A) settles into a band — **velFitX ≈ 3.1–3.6, avgB ≈ 4.1 (cleanly
flattening 4.08→4.12), per-bound ≈ 0.76–0.88 (mean ~0.81)** — with **no systematic climb**. It does **not**
flatten to a razor-sharp value by 5e-7: per-bound wobbles 0.774/0.839/0.757/0.884 and velFitX steps up again
at 5e-7 (3.09→3.64), a residual ~10–15 % scatter (part 2-seed noise at fine dt, part mild dt non-monotonicity).
**Per the bail, this is reported as a band + residual, not asserted as a single converged number.**

**But the limit has unambiguously MOVED — down, ~2×.** Same-config, same-dt isolation: arm A (`structrate`)
@5e-7 = velFitX **3.638** / per-bound **0.884** vs the **stale no-`structrate`** @6.25e-7 = velFitX **7.086**
/ per-bound **1.592**. Making the skeleton dt-convergent **roughly HALVED the fine-dt glide and dropped
per-bound from ~1.59 toward ~0.85.**
- **Why:** the raw fracMove structural springs (J1/J2 connection + tail anchor) stiffen ∝1/dt as dt→0
  (`k=frac·γ/dt`) ⇒ the stale fine-dt runs had an **artificially rigid** motor skeleton (stiffer than
  production), which over-reacted the head stroke against the anchor and **inflated the transmitted glide up
  to ~7**. `-structrate` pins those springs at their **production stiffness** at every dt, so the fine-dt
  glide converges to the physically-consistent, lower value. **The stale ~1.57 per-bound limit was an
  artifact of the frozen/over-stiff skeleton; the honest limit is ~2× lower.**

### 2. The gap at production dt — per-bound is now NEARLY dt-honest; the residual is engagement, not efficiency
Against the honest uncorrected limit (per-bound ~0.81–0.88, velFitX ~3.5, avgB ~4.1), **uncorrected production
dt=1e-5** sits at per-bound 0.774 / velFitX 2.325 / avgB 3.000. So at production:
- **per-bound (efficiency): 0.774 → ~0.85 ≈ within ~10 %** — the per-HEAD efficiency is **nearly converged**
  at production dt. The stale "2× per-bound dt-error" is GONE; it was the skeleton-stiffening artifact.
- **avgBound (engagement): 3.000 → ~4.1 ≈ +27 %** — production **under-binds**.
- **velFitX (absolute glide): 2.325 → ~3.5 ≈ +35–50 %** — production **under-glides**, driven mostly by the
  avgBound engagement deficit (per-bound is ~right). This absolute-speed dt-gap is real and remains.

So **the production-dt dt-gap has moved OUT of the per-bound (efficiency) channel and INTO the avgBound
(engagement) channel** — the opposite decomposition from the stale picture. 1e-5 converts each bound head
about as efficiently as dt→0 does; it just binds ~27 % fewer heads.

### 3. Does the required correction shrink the gap? NO — it OVERSHOOTS the new (lower) limit, and does not converge to uncorrected
The A/B per-bound offset does **not** close at fine dt — it is +0.485/+0.578/+0.626/+0.628 across the ladder
(flat-to-slightly-widening), even as the `-allnoise` factor → 1 (×0.993 @5e-7). Arm B per-bound sits ~1.4–1.5
at fine dt, **well above** arm A's ~0.8. Consequences:
- Against the **honest limit (arm A ~0.85)**, arm B at production (1.259) **OVERSHOOTS by ~48 %** — the "required"
  bond correction pushes production per-bound **past** the converged value, it does not recover a gap toward it.
  (Uncorrected production 0.774 is already at the limit; there is essentially no per-bound gap for the
  correction to close.) This **inverts the stale conclusion** ("`-allnoise` recovers ~63 % of the per-bound
  gap") — that gap was the skeleton artifact.
- **OPEN PUZZLE (reported, not resolved):** a nominally **dt-vanishing** correction (factor →1) leaves a
  **non-vanishing** ~0.63 per-bound offset and a ~2× avgBound difference at 5e-7 (arm B avgB 2.0 vs arm A 4.1)
  — and the offset is **insensitive to the factor magnitude** (same ~0.63 at ×0.987 and ×0.993), so it is
  **not** the instantaneous noise-cut amplified. It looks like an accumulated/hysteretic effect of applying the
  small bound-body noise reduction every step over a long run (fewer detachments → a persistently different
  bound-population steady state), or two metastable glide regimes. Arm A and arm B **should** meet as dt→0 (both
  corrections → identity) but have not by 5e-7. Flagged for follow-up; do NOT read arm B's ~1.5 as "the limit."

### 4. Monotonicity — cleaner than the frozen skeleton, but not razor-flat
avgBound is cleanly monotonic-and-flattening for arm A (3.00→3.65→4.08→4.12). velFitX rises then wobbles
(2.33→3.07→3.09→3.64). per-bound wobbles in a tight band (±0.06). No skeleton-freezing mush at the fine end
(seeds tight at 1e-6) — the reformulation did its job — but a residual ~10–15 % scatter remains (2-seed noise +
mild dt-drift). The fine points would tighten with a 3rd seed; the qualitative verdict does not depend on it.

## Plain verdict

**With the skeleton now dt-convergent and the required correction applied, 1e-5 is far more dt-honest than the
stale numbers implied — because the limit MOVED.** The dt→0 per-bound limit dropped from the stale ~1.57 to
**~0.85** (velFitX ~7→~3.5), a ~2× fall, because the stale limit was inflated by the fracMove skeleton
stiffening ∝1/dt; `-structrate` holds the skeleton at production stiffness and gives the honest, lower limit.
Against that honest limit, **uncorrected production per-bound (0.774) is already ~converged (~10 %)** — the
production dt-gap is now in **engagement (avgBound +27 %) and absolute glide (velFitX +35–50 %)**, NOT in
per-head efficiency. The gate's **REQUIRED bond correction now OVERSHOOTS** the lower limit (production per-bound
0.774→1.259, ~48 % above ~0.85) rather than recovering a gap, and — an open puzzle — its per-bound offset does
**not** vanish as dt→0 despite its factor vanishing. **Do NOT re-commit to the old "~37 % coupled residual":**
it was defined against the inflated ~1.57 limit; on per-bound the residual is now small (~10 %), and the real
dt-gap lives in engagement/absolute glide. *Caveat:* arm A is approximately dt-stable in a ~0.76–0.88 band, not
razor-flat (partial bail invoked); a 3rd seed at the fine points and root-causing the A/B non-convergence are
the natural follow-ups.

## Reproduce
```
# per point (matched sim-time 0.6 s): steps = 0.6/dt = 60k/120k/600k/1.2M at 1e-5/5e-6/1e-6/5e-7
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -structrate           -coltol 10 -density 1000 -dt <DT> -seed <s> <STEPS>   # arm A (uncorrected)
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix -structrate -allnoise  -coltol 10 -density 1000 -dt <DT> -seed <s> <STEPS>   # arm B (required correction)
```
Logs: `RUN_LOGS/reconv/dt{DT}_s{seed}_{A,B}.txt`. Stale no-`structrate` reference:
`CONSTRAINED_VARIANCE_PROBE.md` §SYSTEM-WIDE STEP-3 (uncorr @6.25e-7: velFitX 7.086 / per-bound 1.592).
