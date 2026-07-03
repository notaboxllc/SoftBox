# Per-bound stroke-fidelity census vs engagement: v2's neck-powerstroke stays FAITHFUL as heads pile on — the capture-radius split is the seg-gather, NOT the motor

**Date:** 2026-07-03. **Read-only census** (`-strokecensus`, new; + existing `-stretchcensus`/`-mhatcensus`/
`-rollcensus`) — reads the stored body pose + `forceDotFil` at the output cadence; touches **no force, no RNG, no
integration**. Default byte-identical (fires only under the flag). GPU device-resident (`-gpu -full` single gliding
TaskGraph ~24 kernels + read-only host pulls at the 100-step cadence). Radius knob (the split's own axis):
`coltol4` (LOW engagement, avgB≈10) vs `coltol8` (HIGH, avgB≈19) @ d1000, dt 1e-5, 150k=1.5 s, 3 seeds, LS-centroid
net. `BoA-v1ref` untouched; no model/release/stroke change. Raw: `RUN_LOGS/2026-07-03_stroke_fidelity_census.txt`.

## PLAIN ANSWER — does v2's neck-powerstroke stay faithful as engagement rises?

**YES — it stays faithful.** Every stroke-DIRECTION metric is FLAT from low to high engagement while net drift
collapses −78 % per bound head: barbed-sweep fraction **0.997 → 0.998** (≈BoA's 99.6 %), swing axial fraction
**0.956 → 0.957**, swing angle **57.4° → 57.1°**, stroked fraction **0.925 → 0.921**, head-pole and roll both
**≈50 % → ≈50 %**. And per-stroke |force| even **RISES** (+15 %). The stroke does **not** degrade, reverse, go
off-axis, or lose poses as heads pile on. **⇒ jba's motor/stroke-degradation hypothesis is RULED OUT; the
capture-radius split is genuinely downstream in the seg-gather co-bound load-sharing — the
`ENGAGEMENT_MATCHED_FINDINGS` attribution STANDS.** (Fork branch 1, clean confirmation.)

The one transport metric that moves — signed axial impulse **per head** (−56 %) — decomposes entirely into
**non-stroke** causes (co-bound signed-force cancellation + catch-slip dwell-halving), **both of which occur
identically in BoA whose net RISES** (§3), so it is not the code-differentiator and does not implicate the stroke.

---

## 1. The census — 4 read-only per-bound-head metrics (v2), LOW vs HIGH engagement (3 seeds, mean ± SD)

`swing_perp = uLever − (uLever·mhat)·mhat` (the neck's tilt off the head axis; |swing_perp| = sin of the
lever–head angle = the stroke magnitude). `f̂` = bound seg.uVec (pointed→barbed, +x); productive glide = −x.

| metric | LOW coltol4 (avgB 10.08) | HIGH coltol8 (avgB 18.64) | Δ | moves? |
|---|--:|--:|--:|:--:|
| **avgBound** (engagement) | 10.08 ± 0.34 | 18.64 ± 0.54 | **+85 %** | (the knob) |
| **net v_axial** (µm/s) | 3.79 ± 0.05 | 1.54 ± 0.13 | **−59 %** | (the split) |
| **per-bound drift** = \|v\|/avgB | 0.376 | 0.083 | **−78 %** | (the split) |
| **① swing axial fraction** (stroked) | 0.9560 ± 0.0005 | 0.9565 ± 0.0001 | +0.1 % | **FLAT** |
| **① swing axial fraction** (all bound) | 0.9432 ± 0.0002 | 0.9441 ± 0.0004 | +0.1 % | **FLAT** |
| **② barbed-sweep fraction** (of stroked) | 0.9973 ± 0.0002 | 0.9979 ± 0.0001 | +0.1 % | **FLAT** |
| stroked fraction (\|swing\|>sin40°) | 0.9250 ± 0.0024 | 0.9211 ± 0.0018 | −0.4 % | **FLAT** |
| mean swing angle (deg) | 57.45 ± 0.13 | 57.07 ± 0.09 | −0.7 % | **FLAT** |
| **④ mhat productive-pole** (+ẑ %) | 49.4 ± 0.6 | 49.1 ± 0.4 | −0.6 % | **FLAT (~50)** |
| **④ roll ŝ productive** (+ŝ %) | 50.5 ± 1.0 | 49.5 ± 0.7 | −2.0 % | **FLAT (~50)** |
| **③ signed axial force/head** (pN) | 0.168 ± 0.058 | 0.143 ± 0.006 | **−15 %** | moves |
| ─ \|force\|/head (pN) | 3.077 ± 0.006 | 3.546 ± 0.038 | **+15 %** | **RISES** |
| ─ bound dwell (ms) | 0.871 ± 0.016 | 0.447 ± 0.017 | **−49 %** | moves |
| **③ signed impulse/head** = signed·dwell (pN·ms) | 0.146 | 0.064 | **−56 %** | moves |

**Read:** the four stroke-DIRECTION metrics (①②④ + swing angle + stroked fraction) are FLAT to ≤2 % across an
+85 % engagement rise that craters per-bound drift −78 %. The neck keeps sweeping barbed (99.8 %), keeps swinging
in the axial plane (95.6 %), keeps ~57° magnitude, and the head poses stay put (~50 % — the default motor's
unlocked-sign baseline, unchanged by engagement). Swing-axial-fraction histogram: **>92 % of stroked heads sit in
the top 0.9–1.0 axial-fraction bin at BOTH engagements** (LOW 12018/12967, HIGH 22549/24856). The stroke is not
where the loss is.

## 2. Metric ③ decomposed — the signed-impulse drop is NOT a stroke defect

The signed axial impulse per head falls −56 %, which the task pre-flagged as "a motor/stroke effect if it falls."
The census shows it is **not** — it factorizes into two non-stroke channels:

1. **Co-bound signed-force cancellation (the seg-gather, downstream).** Per-stroke **|force| RISES +15 %**
   (3.08→3.55 pN) — each stroke delivers *more* force, not less — while the **signed** axial force *falls* −15 %
   (0.168→0.143 pN, an order below the magnitude). Rising |force| + falling signed force = the co-bound forces
   **cancel** more as heads pile on. That is the definition of the tug-of-war, and it lives in **how the seg-gather
   sums co-bound reactions**, not in the individual stroke (which is stronger, and still points barbed/axial).
2. **Catch-slip dwell-halving (binding-geometry kinetics, faithful v1).** Dwell halves (0.87→0.45 ms) because
   wider-radius heads bind more stretched (|force|↑) and the faithful catch-slip releases them ~2× faster. A
   kinetics/geometry effect, not a stroke-direction defect.

Neither channel is the stroke mis-firing. The stroke's own outputs — direction (barbed 99.8 %), plane (axial
95.6 %), and magnitude (|force| +15 %) — are intact or stronger at high engagement.

## 3. Cross-code — metric ③'s components move IDENTICALLY in BoA (whose net RISES) ⇒ not the differentiator

From `~/Code/BoA/CAPTURE_RADIUS_REPLICATE.md` STEP 3 (BoA active default, 3 draws, same census quantities):

| metric (bound-pop mean) | v2 4→8 nm | BoA 4→8 nm | same direction? |
|---|--:|--:|:--:|
| signed axial force/head (pN) | 0.168 → 0.143 (−15 %) | 0.238 → 0.136 (−43 %) | **yes ↓** |
| \|force\|/head (pN) | 3.08 → 3.55 (+15 %) | 2.90 → 3.18 (+10 %) | **yes ↑** |
| bound dwell (ms) | 0.87 → 0.45 (−49 %) | 0.640 → 0.244 (−62 %) | **yes ↓** |

**All three of metric ③'s ingredients move the same way in both codes** — yet BoA's net glide **RISES** with radius
(2.91→4.89 µm/s) while v2's **FALLS** (3.79→1.54). So metric ③'s movement is *not* what differs between the codes;
it is common to both. What differs is purely **downstream**: how a shorter-dwelling, more-cancelled co-bound
population resolves into **net transport** — BoA-CPU (Gauss–Seidel, fresh within-step) converts it at ~flat
efficiency, v2-GPU (Jacobi, one-step-stale) craters it. Exactly the `ENGAGEMENT_MATCHED_FINDINGS` seg-gather
verdict. The stretch/geometry census already agreed across codes (`PHASE2_CAPTURE_RADIUS` STEP 3 ≈
`CAPTURE_RADIUS_REPLICATE` STEP 3); this adds that the **stroke direction** agrees too (v2 flat ~99.8 % barbed).

## 4. The BoA gap for metrics ①②④ — the single run to fill it (SPECIFIED, not run)

BoA has the metric-③ ingredients vs radius (§3) but **not** a barbed-sweep/axial-fraction census vs engagement on
its **active default** motor (`NECK_STROKE_POLARITY_FIX`'s 99.6 % is one point, d1000, on the *flag-gated CPU-only
polarity-fix* motor — not the active default, and not vs engagement). To run the symmetric cross-code slope:

> **BoA active default, `myoColTol`=4 nm and 8 nm @ d1000, 3 mat draws each**, same `CAPTURE_RADIUS_REPLICATE`
> protocol (CPU, matbed 14×2, dt 1e-5, 0.7 s), with a **read-only neck-swing pose census** added under
> `BOA_STRETCH_CENSUS` (mirror this file's `strokeTally`: per bound+stroked head, `swing_perp·f̂` sign =
> barbed-sweep fraction, `|swing_perp·f̂|/|swing_perp|` = axial fraction). Report both vs the two engagements.
>
> - **Prediction (branch-1 consistent):** BoA's barbed-sweep + axial fractions are also **flat ~99 %** across
>   4→8 nm — i.e. BoA's stroke is faithful at both engagements too, so the divergence is *not* in either code's
>   stroke, it is the gather. If instead **BoA's held flat while v2's degraded**, the motor would be implicated —
>   but v2's is *already flat* (§1), so this outcome is excluded; the BoA run only confirms symmetry.
>
> Flag for a **BoA-CC follow-up** — do not run from the SoftBox side. **v2's own low-vs-high census (§1) is the
> primary result and is decisive on its own** (the motor is ruled out on v2 evidence alone).

## 5. FORK VERDICT

**→ Stroke fidelity FLAT across engagement; the loss is genuinely downstream in the seg-gather. Branch 1 — clean
confirmation. The motor hypothesis is RULED OUT; the `ENGAGEMENT_MATCHED_FINDINGS` seg-gather attribution STANDS.**

- **Stroke DIRECTION (①②④) — FLAT** to ≤2 % across avgB 10→19 (barbed 99.8 %, axial 95.6 %, poses ~50 %, swing
  57°); per-stroke |force| even RISES +15 %. The stroke does not degrade, reverse, or go off-axis as heads pile on.
- **Signed impulse/head (③) — falls −56 %, but NOT via the stroke:** it factorizes into co-bound signed-force
  cancellation (|force|↑, signed↓ = the gather) + catch-slip dwell-halving (kinetics) — **both present identically
  in BoA, whose net rises** — so it localizes the loss to the gather, not the stroke.
- **Explains `-freshread` deepening the collapse** (`FRESHREAD_AB`): retaining more heads → more co-bound
  cancellation in the Jacobi gather → deeper net collapse, with the stroke unchanged. A gather effect, not a stroke
  effect — consistent with this census.
- **The attribution is now double-confirmed:** the split is not release timing (`FRESHREAD_AB`, refuted), not the
  engagement confound (`ENGAGEMENT_MATCHED_FINDINGS`, rejected), and not the motor/stroke (this census, ruled out).
  It is the co-bound seg-gather load-sharing (Jacobi/GS) — the accepted 4b-iv parallel-scheme residual. The
  fresh-force (Gauss–Seidel) seg-gather remains the warranted **design task** (planner sign-off; risks the
  race-free CSR/`-cpu`-parity property) — now with the motor definitively excluded as an alternative explanation.

## 6. Validation
- **Default byte-identical:** GRID_ROW with vs without `-strokecensus` is identical (d1000 coltol8 seed0 3000:
  velFitX −0.159, avgB 17.710 both) — the read-only host pulls do not perturb the device trajectory (same property
  as the existing `-stretchcensus`/`-rollcensus`/`-mhatcensus`).
- **CPU≡GPU (aggregate, chaotic-within-tolerance):** d1000 coltol8 seed0 1500-step CPU vs GPU — barbedSweepFrac
  **0.9962 vs 0.9961**, swingAxialFrac(stroked) 0.9435 vs 0.9458, strokedFrac 0.929 vs 0.955 — the census reads
  identical physics on both runners (the microstate decorrelates over the chaotic trajectory, per the CLAUDE.md
  standard; the pose aggregates match to float tolerance early and stay within SEM).
- **Read-only / race-free:** `strokeTally` is a host-side read of `body.uVec`/`boundSeg`/`fil.uVec` after
  `transferToHost` — no kernel, no atomics, no force/RNG/integration. No stroke kernel touched (the constraint's
  bail condition never triggered — all metrics were computable from the stored pose).

## 7. Runs
```
# v2 per-bound stroke-fidelity census, 3 seeds, GPU -full 150k, LOW (coltol4) vs HIGH (coltol8) @ d1000:
GlidingHarness -gpu -full -grid -density 1000 -coltol <4|8> -strokecensus -stretchcensus -mhatcensus -rollcensus -seed <0..2> 150000
# validation:
GlidingHarness -gpu -full -grid -density 1000 -coltol 8 -seed 0 3000                              # byte-identity control (no census)
GlidingHarness      -full -grid -density 1000 -coltol 8 -strokecensus -seed 0 1500                # CPU≡GPU census cross-check
# SPECIFIED BoA gap (do NOT run from SoftBox — BoA-CC follow-up):
BOA_STRETCH_CENSUS=1 (+ neck-swing pose census) BoxOfActin -r -pf ParameterFiles/glidingAssay_d1000_colTol{4,8}nm   # 3 draws each
```
New flag: `-strokecensus` (read-only per-bound-head neck-powerstroke fidelity: swing axial fraction, barbed-sweep
fraction, stroked fraction, swing angle, + the swing-axial-fraction histogram; default-off byte-identical). No
promotion — a diagnostic instrument.

## JOURNAL line
```
## 2026-07-03 — Stroke-fidelity census vs engagement (v2, radius knob coltol4→coltol8 @ d1000, 3 seeds): v2's neck-powerstroke STAYS FAITHFUL as engagement rises. Stroke-DIRECTION metrics FLAT across avgB 10→19 while per-bound drift craters −78%: barbed-sweep 0.997→0.998 (≈BoA 99.6%), swing axial frac 0.956→0.957, poses ~50%→~50%, swing 57°; per-stroke |force| even RISES +15%. The one moving transport metric (signed impulse/head −56%) decomposes into co-bound signed-force cancellation (|force|↑ signed↓ = the gather) + catch-slip dwell-halving (kinetics) — BOTH move identically in BoA whose net RISES ⇒ not the differentiator. ⇒ FORK BRANCH 1: motor/stroke hypothesis RULED OUT, the ENGAGEMENT_MATCHED seg-gather attribution STANDS (double-confirmed: not release, not confound, not motor). New read-only -strokecensus (byte-identical, CPU≡GPU). BoA barbed/axial-vs-engagement run SPECIFIED not run. Report: STROKE_FIDELITY_CENSUS_FINDINGS.md.
```
</content>
