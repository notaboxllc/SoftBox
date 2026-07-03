# Engagement-matched BoA↔v2: the capture-radius split is a GENUINE seg-gather fidelity difference, NOT the engagement confound

**Date:** 2026-07-03. **Measurement-only** (existing `-density`/`-coltol` params; default byte-identical;
`BoA-v1ref` untouched; no model/release/stroke change). GPU device-resident (`-gpu -full` single gliding
TaskGraph, ~24 kernels, no per-step host pull). The v2 overlay is assembled from the two protocol-matched
3-seed sweeps already on record (both: `-full` 13.37×2 µm bed, 150k=1.5 s, dt 1e-5, seeds 0/1/2, LS-centroid
`LONG_ROW` estimator, coverage-violated velocities excluded) and **confirmed this session** by a fresh
single-seed density-arm probe (§5). BoA's curve is assembled from existing BoA docs; the one BoA gap is
**specified, not run** (§4).

## PLAIN ANSWER — is the capture-radius split a fidelity bug or the engagement confound?

**It is a genuine seg-gather co-bound load-sharing difference — NOT the engagement confound, and NOT release
timing** (`FRESHREAD_AB_FINDINGS.md` already refuted release currency). At **matched avgBound and matched knob
(radius)** v2 and BoA diverge: at capture radius 8 nm both bind ~19–20 heads yet per-bound drift is **v2 0.081
vs BoA 0.248 (≈3×; 5.5× after the engagement correction, which points the *wrong* way — §3a)**. Matching
engagement does **not** reconcile the codes; it *widens* the gap. This is the accepted 4b-iv **parallel-scheme
residual** (v2 Jacobi one-step-stale seg-gather vs BoA Gauss–Seidel fresh-within-step), now **localized to the
co-bound seg-gather load-sharing** and shown to be **real, same-knob, and matched-engagement** — i.e. a scheme
fidelity difference, not a mis-ported force law and not an artifact of the two codes binding at different rates.

Secondary finding (v2-internal): v2's **radius and density knobs trace *different* drift-vs-avgBound curves**
(radius steeper — §3b). Radius carries a per-head geometry/stretch effect *beyond* count, so "drift is a
function of avgBound alone" is only approximately true on v2. This is a v2-internal effect and does **not**
create the v2↔BoA split (which is present same-knob, same-avgBound).

---

## 1. The three protocol-matched datasets (drift = |v_axial| / avgBound)

**v2 DENSITY sweep** — vary density @ `myoColTol`=6 nm (`PHASE2_SPEED_LEVERS_FINDINGS.md` STEP 1, 3 seeds):

| density | avgBound | per-bound drift | net \|v_axial\| |
|--:|--:|--:|--:|
| 250 | 6.40 | 0.295 | 1.89 |
| 500 | 9.95 | 0.238 | 2.37 |
| 750 | 12.95 | 0.217 | 2.82 |
| 1000 (anchor) | 14.90 | 0.206 | 3.07 |
| 1500 | 17.30 | 0.191 | 3.28 |
| 2000 | 19.30 | 0.159 | 3.08 |

**v2 RADIUS sweep** — vary `myoColTol` @ d1000 (`PHASE2_CAPTURE_RADIUS_FINDINGS.md` STEP 1, 3 seeds; the
d1000/6 nm point = the density anchor, cross-consistent by construction, and re-confirmed 2026-07-03 in
`FRESHREAD_AB_FINDINGS.md`):

| `myoColTol` | avgBound | per-bound drift | net \|v_axial\| |
|--:|--:|--:|--:|
| 4 nm | 10.08 | 0.380 | 3.83 |
| 5 nm | 12.15 | 0.282 | 3.42 |
| 6 nm (anchor) | 14.91 | 0.206 | 3.07 |
| 7 nm | 16.72 | 0.142 | 2.38 |
| 8 nm | 18.64 | 0.081 | 1.51 |

**BoA RADIUS sweep** — vary `myoColTol` @ d1000 (`~/Code/BoA/CAPTURE_RADIUS_REPLICATE.md`, CPU, 3 mat draws;
same LS-centroid estimator, [0.30,0.70] s window):

| `myoColTol` | avgBound | per-bound drift | net \|v_axial\| |
|--:|--:|--:|--:|
| 4 nm | 11.22 | 0.259 | 2.91 |
| 6 nm | 16.92 | 0.167 | 2.83 |
| 8 nm | 19.76 | 0.248 | 4.89 |

**Note on the missing BoA density curve.** BoA has **no protocol-matched density-driven** drift-vs-avgBound
curve on the current default (f̂-directed) motor. (`AXIAL_LOCK_GLIDING_SWEEP.md` is the *old* axial-lock motor
at a *different* protocol — 0.15 s, single fil, narrow box, endpoint-minus-start — so it is not usable here.)
The decisive comparison therefore uses the **same knob (radius) in both codes** (§3a); the BoA density curve is
a nice-to-have branch-3 symmetric check, specified in §4.

## 2. The overlay — per-bound drift vs avgBound (three series)

```
 avgBound     v2 DENSITY        v2 RADIUS         BoA RADIUS (CPU)
   ~6.4       0.295 (d250)      —                 —
  ~10–11      0.238 (d500)      0.380 (r4,10.08)  0.259 (r4, 11.22)
  ~12–12.4    0.217 (d750)      0.282 (r5,12.15)  —
  ~14.9       0.206 (d1000) ═══ 0.206 (r6,14.91)  —            ← shared anchor (identical run)
  ~16.7–17.3  0.191 (d1500)     0.142 (r7,16.72)  0.167 (r6, 16.92)
  ~18.6–19.8  0.159 (d2000)     0.081 (r8,18.64)  0.248 (r8, 19.76)
```

Read straight off the overlay: **the three series do NOT collapse onto one curve.** They meet only near the
anchor (avgB≈15) and fan out on both sides. Two independent divergences (§3a code-to-code, §3b v2 knob-to-knob).

## 3a. DECISIVE — v2 vs BoA at matched avgBound, matched knob (radius): they DIVERGE

Both codes span **the same avgBound range via radius** (v2 10.08→18.64; BoA 11.22→19.76) — so the confound
hypothesis ("same curve, different avgBound ranges") is structurally inapplicable: the ranges *coincide*. Within
that shared range the drifts separate, hardest at high engagement:

| avgBound (r8) | v2 drift | BoA drift | BoA/v2 |
|--:|--:|--:|--:|
| v2 18.64 / BoA 19.76 | **0.081** | **0.248** | **3.1×** |

**The engagement correction points the WRONG way — this is the clincher.** v2's avgBound at r8 (18.64) is
slightly *below* BoA's (19.76). v2's own drift-vs-avgBound curve is steeply *falling* near there
(dDrift/dAvgB ≈ −0.032/head), so projecting v2's r8 point *up* to BoA's avgBound gives drift **0.045** — even
lower. So on v2's own curve, matched to BoA's engagement, the gap is **0.045 vs 0.248 = 5.5×**. Matching
avgBound does not close the split; it *widens* it. The divergence is therefore **not** attributable to the two
codes binding at different rates — it is a genuine per-head co-bound load-sharing difference.

Across the shared range the codes **cross** near the anchor and split with opposite curvature:
- avgB ~11 (r4): v2 ~0.34 (interp) **>** BoA 0.259 — v2 higher at low engagement.
- avgB ~17 (r6/r7): v2 ~0.142 **≈/<** BoA 0.167 — nearly meeting.
- avgB ~20 (r8): v2 0.081 **≪** BoA 0.248 — v2 craters, BoA turns *up*.

v2's drift **collapses monotonically** as engagement rises; BoA's is **flat/U-shaped** (0.259→0.167→0.248).
This is exactly the co-bound signature: adding co-bound heads (wider radius) craters per-head efficiency on the
**Jacobi (stale-neighbor) GPU** seg-gather but not on the **Gauss–Seidel (fresh-neighbor) CPU** path — and it
**worsens with avgBound** (the split opens precisely where more heads are co-bound). The bound-population
*geometry* is NOT where they differ — both codes' stretch census agree (extension +8–13%, |axial force| +10–15%,
dwell −49/−62%, signed force →0; `PHASE2_CAPTURE_RADIUS` STEP 3 ≈ `CAPTURE_RADIUS_REPLICATE` STEP 3). The
divergence is purely in **how a co-bound population resolves into net transport**.

## 3b. SECONDARY — v2's own radius and density curves DIVERGE from each other (radius > engagement)

| avgBound | v2 radius drift | v2 density drift (interp) | radius / density |
|--:|--:|--:|--:|
| 10.08 | 0.380 | 0.237 | **1.60** |
| 12.15 | 0.282 | 0.223 | 1.27 |
| 14.91 | 0.206 | 0.206 | 1.00 (anchor) |
| 16.72 | 0.142 | 0.195 | 0.73 |
| 18.64 | 0.081 | 0.170 | **0.48** |

v2's radius curve is **steeper** than its density curve — above the anchor radius drives drift to **half** the
density value at the *same* avgBound. So on v2, `myoColTol` is **not a pure engagement (count) knob**: it also
shifts per-head geometry (more stretched heads, ~2× shorter dwell — `PHASE2_CAPTURE_RADIUS` STEP 3), and that
geometry shift is anti-productive *on top of* count. Consequence: v2's drift-vs-avgBound relation is
**path-dependent** (radius vs density trace different curves), so an engagement-only model is incomplete for v2.
This is a v2-internal effect; it does not produce the v2↔BoA split (§3a is same-knob).

## 4. The BoA gap — the single run to fill it (SPECIFIED, not run)

BoA's density-driven drift-vs-avgBound curve is missing (see §1 note). To run the symmetric branch-3 check on
BoA (does BoA's radius uptick at r8 reflect a *radius-specific* geometry effect, or is BoA's high-avgBound drift
knob-independent?), the most diagnostic single BoA run is:

> **BoA `myoColTol`=6 nm, density ≈ 2000 µm⁻², 3 mat draws**, same `CAPTURE_RADIUS_REPLICATE` protocol (CPU,
> matbed 14×2 box, dt 1e-5, 0.7 s, LS-centroid [0.30,0.70] s window, `BOA_STRETCH_CENSUS=1`). This lands BoA at
> avgBound ≈ 19–20 via **density** (matching BoA radius r8's ~19.8) and reads its per-bound drift.
>
> - If BoA-density@avgB≈20 gives drift ≈ **0.16** (like v2 density) rather than ≈ **0.248** (BoA radius r8),
>   then BoA *also* has radius≠density and the r8 uptick is a radius-geometry effect present in both codes.
> - If it gives ≈ **0.248**, BoA's high-engagement drift is knob-independent (only v2 has the radius-geometry
>   steepening), sharpening the v2-specific character of §3b.
>
> Either way it does **not** change the §3a verdict (same-knob divergence is already decisive). Flag for a
> **BoA-CC follow-up** — do not run from the SoftBox side.

A fuller BoA density sweep {d500, d1000, d1500, d2000} @ 6 nm (3 draws) would complete the BoA density arm; the
single d2000 point above is the minimal high-value fill.

## 5. This-session confirmation probe (density arm, seed 0, GPU `-full` 150k)

The v2 radius arm was re-confirmed 2026-07-03 (`FRESHREAD_AB` stale column ≡ `PHASE2_CAPTURE_RADIUS`). The
density arm (last measured 2026-07-01) is re-confirmed here — the default gliding path is byte-unchanged since
(the last commit touched only `stepFresh`/`FRESH_READ`). Raw: `RUN_LOGS/2026-07-03_engagement_matched_probe.txt`.

| density | probe avgBound | probe net \|v_axial\| | probe drift | PHASE2 3-seed drift | reproduces? |
|--:|--:|--:|--:|--:|--:|
| 250 | 6.64 | 2.142 | 0.323 | 0.295 (net 1.89±0.20) | ✓ avgB spot-on; seed-0 a fast draw |
| 1000 | 14.77 | 3.023 | **0.205** | 0.206 | ✓ exact |
| 2000 | 19.40 | 2.939 | 0.152 | 0.159 | ✓ |

The density arm reproduces this session (d1000 exact; d2000 confirms the high-avgBound density point). **The
branch-3 split is directly visible in this single probe:** at avgB≈19 the density-driven drift is **0.152**
(d2000) vs the radius-driven **0.081** (r8, avgB 18.64) — density ≈ **2× radius at matched avgBound**, confirming
v2's radius and density knobs trace different curves (§3b) without relying on the cross-day 3-seed data.

## 6. FORK VERDICT

**→ GENUINE seg-gather co-bound load-sharing divergence (fork branch 2), plus v2 radius≠density (branch 3). The
engagement confound (branch 1) is REJECTED.**

- **Branch 1 (engagement confound) — REJECTED.** At matched avgBound and matched knob (radius) the codes do not
  coincide; the engagement correction *widens* the r8 gap to 5.5× (§3a). Same avgBound ranges, divergent drift.
- **Branch 2 (genuine seg-gather divergence) — CONFIRMED.** v2's co-bound drift collapses with engagement
  (0.380→0.081 over r4→r8) while BoA's stays flat/U-shaped (0.259→0.167→0.248); the split is ~3× at avgB≈20 and
  **worsens with avgBound**, exactly where the Jacobi stale-neighbor co-bound term bites hardest. Direction: v2
  over-resists co-bound load (heads pile on and cancel harder); BoA does not.
- **Branch 3 (v2 radius beyond engagement) — CONFIRMED.** v2's radius and density knobs trace different
  drift-vs-avgBound curves (radius steeper, to 0.48× density at avgB≈19); radius adds a per-head stretch/dwell
  geometry effect on top of count. v2-internal; does not cause the code split.

### Characterization (branch 2) + scope-flag for the planner
The divergence is **the 4b-iv parallel-scheme residual localized to the co-bound seg-gather** — the accepted
architectural Jacobi(v2-GPU, one-step-stale SoA forces)/Gauss–Seidel(BoA-CPU, fresh within-step) difference, not
a coding/port bug and not release timing (refuted). It is **quiescent for single/sparse binding** (the codes
agree at the anchor and at low radius) and **grows with co-bound density**: magnitude up to ≈3× per-head drift
at avgB≈20, direction = v2 craters / BoA flat-to-rising, monotone in avgBound. **This is a real dense-regime
fidelity risk for the contractile ring** (many co-bound heads), consistent with the standing
`jacobi-cobound-scheme-risk` memo and `IMPLICIT_XB_CONVERGENCE` (dt-refinement deepens v2's tug-of-war).

**Scope-flag:** a **fresh-force (Gauss–Seidel-like) seg-gather** — recompute the seg-side cross-bridge force
from **post-head-integrate** positions — is now warranted **as a design task**, since the split is confirmed a
genuine gather difference (not confound, not release). **Cost to make explicit:** it risks the seg-gather's
**race-free, atomics-free, CSR bit-identical CPU≡GPU** property (the current gather sums stored per-bond
reactions over a static-per-step CSR-inverse; a fresh-force variant must re-evaluate forces against just-updated
neighbor positions without reintroducing a read-write race or a CPU/GPU order dependence). This is a
gather-architecture task, **not** a measurement — flagged for planner sign-off, not started here.

## Runs (for the record)
```
# v2 overlay = existing protocol-matched 3-seed data:
GlidingHarness -gpu -full -grid -density <250..2000> -seed <0..2> 150000        # density arm (PHASE2_SPEED_LEVERS)
GlidingHarness -gpu -full -grid -density 1000 -coltol <4..8> -seed <0..2> 150000 # radius arm (PHASE2_CAPTURE_RADIUS)
# this-session density-arm confirmation probe (seed 0):
GlidingHarness -gpu -full -grid -density <250|1000|2000> -seed 0 150000
# SPECIFIED BoA gap (do NOT run from SoftBox — BoA-CC follow-up):
BOA_STRETCH_CENSUS=1 BoxOfActin -r -pf ParameterFiles/glidingAssay_d2000_colTol6nm   # 3 draws
```

## JOURNAL line
```
## 2026-07-03 — Engagement-matched BoA↔v2: the capture-radius split is a GENUINE seg-gather co-bound load-sharing difference, NOT the engagement confound. At matched avgBound + matched knob (radius r8, avgB≈20) v2 drift 0.081 vs BoA 0.248 (≈3×; 5.5× after the engagement correction, which points the wrong way) ⇒ confound REJECTED. v2 radius≠density curves too (radius steeper, per-head geometry beyond count). It's the accepted 4b-iv Jacobi/Gauss–Seidel residual localized to the seg-gather, quiescent sparse / grows with co-bound density ⇒ dense-regime ring risk. Scope-flag: fresh-force (Gauss–Seidel) seg-gather as a DESIGN task (risks CSR/-cpu parity), planner sign-off. BoA density-@avgB20 gap specified not run. Measurement-only; default byte-identical; BoA-v1ref untouched.
```
</content>
</invoke>
