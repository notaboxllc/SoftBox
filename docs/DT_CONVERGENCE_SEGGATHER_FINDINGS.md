# dt-convergence at the capture-radius split: v2's dense-regime drift CRATERS as dt→0 — it does NOT rise toward BoA (BRANCH 2)

**Date:** 2026-07-03. **Measurement-only** (existing params + `-dt`; default byte-identical; race-free/CPU≡GPU
gather untouched; `BoA-v1ref` untouched; no model/release/stroke change). GPU device-resident (`-gpu -full`
single gliding TaskGraph ~24 kernels, no per-step host pull), coltol8/d1000 bed (13.37×2 µm, 26 740 motors),
aeta=0.1, LS-centroid `LONG_ROW` estimator (`PROPER_SPEED_ANALYSIS.md`), ≥1 s on-bed window, matched 1.5 s sim
time at every dt (step count scaled 150k/300k/600k/1.2M). Raw:
`RUN_LOGS/2026-07-03_dtconv_coltol8_dt{1e-5,5e-6,2.5e-6,1.25e-6}_seed0.txt`.

## PLAIN ANSWER — as dt→0, does v2's dense-regime drift move toward BoA's ~0.25?

**NO. It craters toward zero.** At the widest BoA↔v2 split (`myoColTol`=8 nm, d1000), refining dt 8× drives
per-bound drift **0.090 → 0.0056 (16× LOWER, monotonic)** while avgBound **explodes 17.88 → 216 (12×)**. v2's
per-bound drift shows **zero tendency to rise toward BoA's coarse 0.248** — it collapses monotonically as dt→0
and moves **further from** BoA, not toward it. **⇒ the Jacobi seg-gather is NOT the artifact that a
Gauss–Seidel/fresh-force gather would "fix" up to BoA's value; the converged dense-regime answer is genuinely
LOW.** This is **fork branch 2**: v2's converged behavior is low; the split is NOT pure staleness; **BoA's high
coarse-dt drift (0.248) is the outlier that must ITSELF be convergence-checked before anyone rebuilds v2's
gather. Do NOT scope the fresh-force seg-gather to match BoA yet.**

---

## 1. The dt-refinement series (coltol8/d1000, seed 0, matched 1.5 s sim time)

| dt | steps | avgBound | net \|v_axial\| | **per-bound drift** | axialFrac | window | wall |
|--:|--:|--:|--:|--:|--:|--:|--:|
| 1e-5 (production default) | 150k | 17.88 | 1.609 | **0.0900** | 0.951 | 1.30 s OK | 9:43 |
| 5e-6 | 300k | 69.10 | 1.332 | **0.0193** | 0.983 | 1.30 s OK | 19:02 |
| 2.5e-6 | 600k | 157.93 | 1.213 | **0.0077** | 0.999 | 1.30 s OK | 37:35 |
| 1.25e-6 | 1.2M | 216.27 | 1.201 | **0.0056** | 0.994 | 1.30 s OK | 1:19:19 |

per-bound drift = |v_axial| / avgBound (LS-centroid on the filament axis; axialFrac ≥0.95 everywhere ⇒ all clean
axial glides, no edge-violated velocities; every window ≥1 s). The seed-0 coarse point (avgBound 17.88, drift
0.090) reproduces the engagement-doc coltol8 3-seed mean (18.64, 0.081) within seed scatter ⇒ seed 0 is
representative; the trajectory below is a **16× monotonic** effect that dwarfs any seed noise (coarse-dt seed SD
≈ ±0.3 avgBound, ±0.01 drift), so single-seed is decisive on direction (§6).

**Three monotonic trends as dt→0:**
- **avgBound → saturation.** 17.88 → 69.10 → 157.93 → 216.27; the per-halving increment (+51, +89, +58) is
  **decelerating** past 2.5e-6 ⇒ approaching a plateau near ~220–250 (≈ all reachable heads permanently bound).
  This is the F8-overshoot convergence (`IMPLICIT_XB_CONVERGENCE`): coarse dt spuriously detaches over-stretched
  heads; refining dt removes the false detachment ⇒ engagement climbs toward its true (saturated) value.
- **net glide → a finite limit ~1.20 µm/s.** 1.609 → 1.332 → 1.213 → 1.201; increments (−0.277, −0.119, −0.012)
  **converge** ⇒ the converged coltol8 net glide is ≈ **1.20 µm/s**, *below* the coarse-dt 1.61 (not a divergence
  — a real, lower converged value).
- **per-bound drift → ~0.005.** 0.0900 → 0.0193 → 0.0077 → 0.0056; craters 16×, monotone, no upturn. Low because
  avgBound is huge (a near-saturated carpet spreads the same ~1.2 µm/s net over ~220 bound heads).

## 2. The drift-vs-avgBound plane — v2's dt-refined trajectory stays on its OWN collapsing curve, away from BoA

The reference curves (coarse dt=1e-5), from `ENGAGEMENT_MATCHED_FINDINGS.md`:

```
 avgBound     v2 Jacobi RADIUS (coarse)   BoA RADIUS (coarse)      v2 dt-REFINED @coltol8 (this work)
   ~10–11     0.380 (r4,10.08)            0.259 (r4,11.22)         —
   ~15        0.206 (r6,14.91)            0.167 (r6,16.92) [~17]   —
   ~18–20     0.081 (r8,18.64)            0.248 (r8,19.76)         0.090 (dt1e-5, 17.88)   ← coincides w/ v2 curve
   ~69        —                           —                        0.0193 (dt5e-6)
   ~158       —                           —                        0.0077 (dt2.5e-6)
   ~216       —                           —                        0.0056 (dt1.25e-6)
```

Read straight off the plane:

- **The dt=1e-5 point sits ON v2's own Jacobi engagement curve** (17.88/0.090 ≈ r8 18.64/0.081). Consistent
  anchor — dt-refinement starts where the engagement sweep ends.
- **As dt→0, v2 continues DOWN-RIGHT** — drift falls monotonically toward 0 as avgBound climbs past 20 → 216.
  It **extends v2's own collapsing Jacobi curve**; it never turns up.
- **BoA's curve turns UP at high engagement** (U-shaped, 0.259 → 0.167 → 0.248 at avgBound 20). **v2's dt-refined
  trajectory passes straight through avgBound≈20 still falling (0.090→) and keeps falling** — it does the exact
  *opposite* of BoA's upturn. dt-refinement moves v2 **further from** BoA's curve.

**Verdict on the task's precise diagnostic** ("does dt-refinement move v2 off its Jacobi curve *toward* BoA's, or
just along it?"): v2 moves **along/below its own Jacobi curve toward drift→0**, with **no migration toward BoA's
U-shape**. The scheme difference does **not** vanish in the dt→0 limit — it **widens** (v2→~0.005, BoA sits at
0.248). ⇒ **branch 2.**

## 3. The load-bearing caveat — the dt→0 limit is a near-SATURATED over-bound state; per-bound drift is not the clean adjudicator there

dt-refinement drives avgBound to saturation (~220), and at saturation the tug-of-war craters per-bound drift for
**any** scheme (the same ~1.2 µm/s net spread over ~10× more bound heads). So the literal fork answer ("drift
does not rise toward BoA") is unambiguous, but it must be read with the mechanism in view:

- **Per-bound drift is only comparable at MATCHED avgBound.** It falls steeply with engagement (tug-of-war), so
  at v2's dt→0 avgBound (~220) vs BoA's coarse avgBound (~20) the drifts are apples-to-oranges (10× engagement
  gap ⇒ naturally ~10–50× drift gap). The clean scheme comparison is the **matched-engagement** one already on
  record (`ENGAGEMENT_MATCHED`: v2 0.081 vs BoA 0.248 at avgBound≈19) — and **dt-convergence gives it no support
  in BoA's favor**: v2's scheme, refined, drives drift *down*, never up toward BoA.
- **Neither code's dt=1e-5 point is "converged."** Both are chosen operating points. avgBound roughly doubles per
  dt-halving with no plateau until saturation (this work + `IMPLICIT_XB_CONVERGENCE`), so the faithful glide is a
  moving, still-climbing-engagement quantity. The **converged net glide** at coltol8 is ≈1.20 µm/s (§1), a real
  finite number, but it corresponds to a physically degenerate ~fully-bound carpet — a limit the faithful
  *explicit* cross-bridge reaches only because it lacks a dt-independent detachment (the standing
  `dt-faithful-ceiling` / sub-step story).

**Net:** the dt→0 limit **refutes** the hypothesis that BoA's 0.248 is the convergence target of v2's scheme
(branch 1). It does **not** by itself certify v2's coarse 0.081 as "the faithful answer" — it shows the faithful
answer is engagement-dependent and, in the dt→0 (saturated) limit, low for v2's scheme. The genuine
same-engagement scheme difference remains real (ENGAGEMENT_MATCHED) and is **not resolved toward BoA** by
convergence.

## 4. FORK VERDICT — BRANCH 2

**→ v2's dt→0 drift STAYS COLLAPSED (craters 16× to ~0.006); it does NOT rise toward BoA's ~0.25.** The Jacobi
seg-gather is **not** a staleness artifact whose true limit is BoA's high drift. Therefore:

- **The fresh-force (Gauss–Seidel-like) seg-gather redesign is NOT yet justified as "chasing the faithful
  target."** BoA's coarse 0.248 is not v2's converged limit; rebuilding v2's race-free CSR gather to reproduce it
  would be chasing an unvalidated target — and would risk the CSR / `-cpu` bit-identical parity for no
  established fidelity gain. **Do not scope it to match BoA yet.**
- **BoA MUST be convergence-checked first** (its Gauss–Seidel coarse point could itself be an order-dependent
  shortcut, or it could converge to the same low saturated limit). The decisive follow-up is the BoA dt-refine at
  coltol8 (§5). Only its result settles whether the two schemes converge to the **same** (degenerate-low) limit
  [⇒ the coarse split is purely an operating-point artifact; adjudicate at matched engagement, already done] or
  to **different** limits [⇒ a genuine scheme difference persists in the limit; then decide which limit is
  physical].

This is fully consistent with, and sharpens, the standing `jacobi-cobound-scheme-risk` memo and
`IMPLICIT_XB_CONVERGENCE`: dt-refinement **deepens** v2's tug-of-war (avgBound↑, drift↓), it does not relax it
toward BoA.

## 5. SPECIFIED BoA convergence runs (BoA-CC follow-up — do NOT run from SoftBox)

Mirror this series on active BoA to close the fork. `CAPTURE_RADIUS_REPLICATE.md` protocol (CPU, matbed 14×2 box,
LS-centroid [0.30,0.70] s window, `BOA_STRETCH_CENSUS=1` for the census), **coltol8, density 1000, 3 mat draws**,
**dt = 1e-5 / 5e-6 / 2.5e-6** (BoA CPU is slower ⇒ the 1.25e-6 point is optional; 2.5e-6 already brackets the
trend). Report per dt: avgBound, net |v_axial|, per-bound drift.

```
BOA_STRETCH_CENSUS=1 BoxOfActin -r -pf ParameterFiles/glidingAssay_d1000_colTol8nm   # dt 1e-5 (baseline, 3 draws)
#   + repeat with the BoA dt override set to 5e-6 and 2.5e-6 (scale nSteps to hold the same ≥1 s window)
```

**Reads:**
- **If BoA's drift at coltol8 ALSO craters** (avgBound explodes, drift → ~0 as dt→0), the two schemes converge to
  the **same** near-saturated low limit ⇒ the coarse-dt split (0.081 vs 0.248) is an **operating-point artifact**
  of the two codes sitting at different dt/engagement; the real comparison is matched-engagement (done) and **no
  gather redesign is warranted**.
- **If BoA's drift STAYS high** (~0.248) or converges to a value **well above** v2's saturated limit while
  avgBound behaves differently, the schemes converge to **different** limits ⇒ a genuine converged scheme
  difference; then adjudicate which limit is physical (likely requires the sub-step/coupled-implicit cross-bridge
  that gives *both* codes a dt-independent operating point — `substep-feasibility-verdict`), **before** any
  gather rebuild.

Either read **supersedes** the coarse-dt inference "Gauss–Seidel is the likely reference." v2's series already
refutes the specific claim that BoA's 0.248 is v2's convergence target.

## 6. Staging / cost / parity

- **Staging done as specified:** single-seed dt=1e-5 (probe) + dt=1.25e-6 (finest, the expensive endpoint,
  1:19:19 wall) FIRST to confirm direction + wall time, then the two intermediates (dt=5e-6, 2.5e-6) queued to run
  after the finest (no GPU contention). Total single-seed trajectory ≈ 2.4 h GPU.
- **Single-seed is decisive on direction.** The effect is a **16× monotonic** collapse; seed-0 reproduces the
  coltol8 3-seed coarse mean; seed SD on coarse drift is ≈±0.01. A full **4-dt × 3-seed** grid (≈7 h GPU) would
  refine the exact converged numbers but **cannot flip a 16× monotone verdict** — flagged as an **optional
  confirmation**, not run (the cost is not justified by the certainty gain).
- **Parity untouched.** `-dt` is a pre-existing measurement flag; the seg-gather / release / stroke / model are
  byte-unchanged; default byte-identical; `BoA-v1ref` byte-clean. Coverage clean: axialFrac ≥0.95 and window
  ≥1 s at every dt (finer dt = same sim time = same on-bed runway) — no edge-violated velocities.

## Runs (for the record)
```
GlidingHarness -gpu -full -grid -density 1000 -coltol 8 -seed 0            150000   # dt=1e-5   (default)
GlidingHarness -gpu -full -grid -density 1000 -coltol 8 -seed 0 -dt 5e-6   300000   # dt=5e-6
GlidingHarness -gpu -full -grid -density 1000 -coltol 8 -seed 0 -dt 2.5e-6 600000   # dt=2.5e-6
GlidingHarness -gpu -full -grid -density 1000 -coltol 8 -seed 0 -dt 1.25e-6 1200000 # dt=1.25e-6 (finest)
# SPECIFIED BoA convergence (do NOT run from SoftBox — BoA-CC follow-up): §5.
```

## JOURNAL line
```
## 2026-07-03 — dt-convergence at the capture-radius split (coltol8/d1000, the widest BoA↔v2 split): v2's per-bound drift CRATERS 16× (0.090→0.0056) as dt→0 (1e-5→1.25e-6), avgBound explodes 12× (17.88→216, toward saturation), net glide converges to ~1.20 µm/s. v2's dt-refined trajectory stays on/below its OWN collapsing Jacobi drift-vs-avgBound curve — it does NOT rise toward BoA's 0.248, moving FURTHER from BoA's U-shaped curve, not toward it ⇒ FORK BRANCH 2: converged dense-regime answer genuinely LOW, split is NOT pure staleness, BoA's high coarse drift is the outlier. Do NOT scope the fresh-force seg-gather to match BoA yet — BoA must be dt-convergence-checked first (SPECIFIED coltol8 dt 1e-5/5e-6/2.5e-6, 3 draws, BoA-CC). Caveat: dt→0 limit is a near-saturated over-bound state; per-bound drift only comparable at matched engagement (ENGAGEMENT_MATCHED, v2 0.081 vs BoA 0.248 @ avgB≈19 stands; convergence gives it no support in BoA's favor). Measurement-only (existing params + -dt); default byte-identical; seg-gather/CPU≡GPU untouched; BoA-v1ref byte-clean. Report: DT_CONVERGENCE_SEGGATHER_FINDINGS.md.
```
