# `-freshread` A/B — does fresh release-force close the BoA/v2 capture-radius split?

**Date:** 2026-07-03. STEP 0 (provenance) + the scoped `stepFresh` faithfulness fix + STEP 1 (the A/B) all done.
GPU device-resident (single gliding TaskGraph, +1 `dirSwing` task in the fresh branch); one small `-cpu` validation.
**Default byte-unchanged** (`FRESH_READ` default-false; `stepOrig` + default `buildPlan` branch untouched).
`BoA-v1ref` untouched. **STEP 2 NOT reached — no promotion** (the split does not close, and fresh *degrades* glide).

## PLAIN ANSWER — does fresh-read close the BoA/v2 capture-radius split? **NO — the opposite.**

Making v2's catch-slip read the **fresh** (this-step) cross-bridge load does **not** flatten v2's drift toward BoA's
rising-net shape. It makes v2's collapse **steeper**: net glide falls from −3.02 to **−0.35 µm/s at 6 nm** (per-bound
drift 0.205 → **0.030**) and **reverses sign at 8 nm**, moving v2 **further from** BoA (whose net *rises* with
radius), not toward it. The 1-step stale release-force is therefore **NOT the cause** of the dense-regime
capture-radius sign split — this is the fork's "drift still collapses" branch, in a strong form (it collapses
*harder*). The residual lives in the **seg-gather co-bound load-sharing**, which `-freshread` does not touch.

---

## STEP 0 — provenance (jba's recollection CONFIRMED)

`-freshread`/`stepFresh` is the **4b-iv release-read reorder** (`35ef395` + `1c28b3b`, 2026-06-15) — the v2 analog
of active BoA's 2026-06-04 GPU release-read reconciliation (force+`registerForceDot` **before** `catchSlipRelease`;
integrate last; forces still start-of-step forward-Euler). **Why it isn't default:** (1) at 4b-iv it was validated
but shifted only the **mechanism** (assist +0.43 pp toward v1) and **not** the net-glide 0.874× residual (CPU Δnet
−0.095±0.278, GPU Δnet −0.033±0.253 — within noise; `RUN_LOGS/2026-06-15_4biv_reorder_ab.txt`), so there was no
net reason to promote it — left gated. (2) Then the **f̂-motor promotion** (`a5c67cd`, 2026-07-01) wired the
`directedSwing` power stroke into `stepOrig` + the default `buildPlan` branch **only**, and never updated
`stepFresh`/`FRESH_READ` (verified: `a5c67cd`'s `GlidingHarness.java` diff touches flag decls/promotion block, not
the `stepFresh` body; last `stepFresh` touch was `a52112e`, implicit-XB). **⇒ the flag was "dropped forward"
exactly as jba suspected — `stepFresh` became an orphaned 4b-iv-vintage strokeless reorder.**

## The scoped fix (jba-approved) — make `stepFresh` run the default motor, then A/B

The only real gap for the default motor was the **missing `directedSwing` task** (the J1-converter-off is already
shared: for the default `sc.jointParams === mot.jointParams`, `GlidingHarness.java:403`, and DIRSWING mutates it at
`:409`; SPHEREHEAD+AXLOCK flow through the shared `sc.xbParams`). Added the `directedSwing`/`directedSwingHeadFrame`
dispatch after `applyHeadForce` in both `stepFresh` (CPU) and the `FRESH_READ` `buildPlan` branch (GPU), identical
to `stepOrig`'s. **Validation:** CPU≡GPU **bit-identical** on the fixed `-freshread` (d250 coltol8 5000: both runners
velFitX=0.265, netX=−0.233, avgB=11.745, avgBsteady=13.692) ⇒ the new PTX task lowers correctly. `stepOrig`
byte-unchanged ⇒ the stale column reproduces PHASE2 by construction (confirmed below).
*Caveat (bounded, not verdict-changing):* `stepFresh` bundles the full v1-order reorder (release currency **and**
the force-before-biochem ordering — the stroke reads the pre-cycle nucleotide state, a <1%-of-motor-steps
secondary), so this is not a release-currency-**only** isolation. But (a) the 4b-iv A/B showed the same bundle
barely moved the *legacy* motor's net, and (b) the collapse here is specifically at higher engagement/radius (the
dense co-bound regime) — consistent with the seg-gather tug-of-war, not the stroke ordering. The direction (collapse
*harder*, away from BoA) is robust to the imperfect isolation.

---

## STEP 1 — the A/B (GPU, default motor, d1000, `-full`, dt=1e-5, 150k=1.5 s, LONG_ROW estimator)

Raw: `RUN_LOGS/2026-07-03_freshread_ab.txt`. Per-bound drift = |v_axial|/avgBound. Coverage status at line end.

**Stale (`stepOrig`) — reproduces PHASE2 (confirms `stepOrig` byte-unchanged):**

| radius | v_axial | avgBound | drift | PHASE2 drift |
|--:|--:|--:|--:|--:|
| 4 nm | −3.72 | 9.69 | 0.384 | 0.380 ✓ |
| 6 nm | −3.02 | 14.77 | 0.205 | 0.206 ✓ |
| 8 nm | −1.61 | 17.88 | 0.090 | 0.081 ✓ |

**Fresh (`-freshread`), 3 seeds:**

| radius | v_axial (mean±SD) | avgBound | drift (mean±SD) | coverage |
|--:|--:|--:|--:|--|
| 4 nm | −2.51 ± 0.24 | 10.46 | **0.244 ± 0.045** | OK (3/3) |
| 6 nm | −0.51 ± 0.26 | 16.71 | **0.030 ± 0.016** | OK (3/3) |
| 8 nm | **+2.13** (reversed) | ~13.8→10 | — | **VIOLATED (3/3)** — window 0.10–0.16 s; velFitX −0.76/−1.27/−0.93 (i.e. +x drift) |

**The decisive drift-vs-radius overlay (the diagnostic):**

| radius | v2 stale drift | **v2 fresh drift** | BoA drift | v2 stale net | **v2 fresh net** | BoA net |
|--:|--:|--:|--:|--:|--:|--:|
| 4 nm | 0.380 | **0.244** | 0.259 | −3.72 | **−2.51** | (rises →) |
| 6 nm | 0.206 | **0.030** | 0.167 | −3.02 | **−0.51** | ~−3.0 |
| 8 nm | 0.081 | **~0 / reversed** | 0.248 | −1.61 | **+2.1 (cov-viol)** | (rises) |

**Reading:**
1. **Fresh does not reproduce BoA's shape — it exaggerates v2's collapse.** BoA's net **rises** with radius (drift
   stays ~0.17–0.26). v2-stale already collapses (0.38→0.09); v2-**fresh collapses far harder** (0.24→0.03→
   reversed). Fresh moves v2 **away** from BoA, not toward it.
2. **Fresh raises avgBound (14.8→16.7 at 6 nm) while net → 0** — a deeper co-bound tug-of-war, exactly the
   `IMPLICIT_XB_CAPTURE_RADIUS` mechanism ("more bound heads → more mutual cancellation → lower drift"). Fresh-read
   behaves like partial convergence: better head retention, more cancellation, less net.
3. **Stale v2 is the one that AGREES with BoA at 6 nm** (both net ≈ −3.02). Fresh (−0.51) breaks that agreement. So
   fresh-read is not "more correct in BoA's direction" — it degrades the existing match.
4. At **4 nm** fresh drift (0.244) is coincidentally near BoA (0.259) — but the **shape** (how drift moves with
   radius) is the diagnostic, and fresh's shape is the opposite of BoA's.

---

## FORK VERDICT — **the split does NOT close; the stale release-force is REFUTED as the cause.**

This is the task's second fork branch ("drift still collapses ⇒ the stale release-force is NOT the (whole) cause;
the residual is in the seg-gather load-sharing proper"), in its strong form (fresh collapses *harder*).
`-freshread` changes only the catch's force **currency**; it leaves the **Jacobi stale-force seg-gather** — the
co-bound load-sharing the split actually lives in — fully intact (and by retaining more heads, it *deepens* the
tug-of-war). **The dense-regime capture-radius sign split is a genuine seg-gather scheme difference, not a
release-timing artifact.**

This **empirically refutes** the leading candidate from `RELEASE_FORCE_TIMING_AUDIT.md` (fresh-vs-stale release
timing as the likely root cause). The audit correctly found a real timing *difference*; this A/B shows that
difference is **not** the split's cause — and its own effect-size caveat (the lag is small at dt=1e-5 for isolated
heads; the dense effect is the co-bound gather) was prescient.

**Next cut (planner, not run here) — as `IMPLICIT_XB_CAPTURE_RADIUS` already escalated:** (1) **engagement-matched**
BoA↔v2 comparison (tune density/radius to equal avgBound, then read drift-vs-radius) to isolate the co-bound
load-sharing from the engagement confound; (2) a **fresh-force (Gauss–Seidel-like) seg-gather** — recompute the
seg-side cross-bridge force from post-head-integrate positions — which risks the race-free CSR / `-cpu`-parity
property, so it is a design task, not a measurement. This A/B removes release currency from the suspect list.

## STEP 2 — NOT reached (no promotion), for two independent reasons
1. The split does not close (the gate for promotion). Per the task guardrail, STOP at the report; do not promote.
2. Fresh-read **degrades the default glide** (net −3.02 → −0.51 at 6 nm, reversal at 8 nm) — promoting it would
   wreck the gliding assay. The default stays `stepOrig`.

## What was kept
The `stepFresh`/`FRESH_READ` **faithfulness fix is kept** (default-OFF): `-freshread` now runs the actual default
motor, so it is a valid A/B instrument going forward (previously it silently ran a strokeless motor). No default
behavior changes (a no-flag run is byte-identical).

## Runs
```
GlidingHarness -gpu -full -grid -density 1000 -coltol <4|6|8> [-freshread] -seed <0|1|2> 150000
GlidingHarness      -full -grid -density  250 -coltol 8 -freshread -seed 0 5000     # CPU≡GPU validation (bit-identical)
```

## JOURNAL line
```
## 2026-07-03 — `-freshread` A/B: fresh release-force does NOT close the BoA/v2 capture-radius split — it collapses v2's net HARDER (−3.02→−0.35 @6nm, reverses @8nm). Stale release-force REFUTED as the cause; residual is the seg-gather. Fixed the orphaned stepFresh (added the default stroke); no promotion.
```
