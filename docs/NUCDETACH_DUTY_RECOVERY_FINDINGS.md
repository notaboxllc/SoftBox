# Nucleotide-detach duty recovery — what caps the dwell, and can avgBound/glide be recovered at low duty?

**Date:** 2026-07-04. Flag-gated (`-adppibind` new; `-lymntaylor` unchanged); **default byte-identical**;
`BoA-v1ref` untouched. Builds on `NUCLEOTIDE_DETACH_FINDINGS.md` (the `-lymntaylor` cycle: dt-robust but LOW
duty). GPU device-resident `-full -grid` gliding TaskGraph unless noted; measurement additions are host-side
only (no physics/RNG touched).

## PLAIN ANSWER (headline)
1. **What caps the dwell:** the realized dense-bed dwell (~0.036 ms, ~25× short of the biochemical cycle) is
   **the bind-in-ATP ejection churn the earlier findings hypothesised — now MEASURED and CONFIRMED**, not a
   startup artifact and not honest turnover. The binder is **purely geometric (never nucleotide-gated)**, so a
   just-detached head (in ATP, mid ~10 ms recovery) is geometrically re-bound and ejected the SAME step. **91 %
   of all detach events are these ≤1-step churn ejections.** Gating strong-binding to the pre-stroke **ADP·Pi**
   state (`-adppibind`, the faithful weak→strong rule the cycle already *documents*) removes the churn: dwell
   **0.036 → 0.344 ms**, detach-rate **27900 → 2900 /s** — a clean ADP-release-limited biochemical clock.
2. **Can avgBound/glide be recovered at low densities:** **YES, via capture radius.** Widening `myoColTol`
   6→12 nm raises avgBound monotonically (d1000 1.12→1.70, d500 0.49→0.78) without saturating; glide rises then
   plateaus (~1.1–1.2 µm/s at d1000, dt=1e-5). Density is the stronger lever; the engagement threshold sits
   between d500 and d1000 — moved *toward* but not into the Uyeda band. The **dwell-fix itself is NOT the
   avgBound lever** (churn heads were near-zero-engagement; the gate costs ~15 % glide) — it is a faithfulness
   improvement; capture radius is the engagement lever.
3. **Does duty stay low & dt-robust when recovered:** **Low & physical: YES. dt-robust: NO.** Across the whole
   capture×density surface the gated duty stays low and biochemically-clocked (dwell 0.34 ms, detach ~2900 /s,
   avgBound O(1)) — but that duty, and the glide, are **not dt-robust**: they climb ~2×/~5.7× as dt→0 through the
   **explicit cross-bridge overshoot** (the catch `g(F)` and the per-bound drift both read the coarse-dt force).
   `-xbimplicit` only partially closes it; the real fix is the **sub-step / coupled cross-bridge** (numerics, not
   kinetics). So the nucleotide-detachment fixed the avgBound *saturation*, but a dt-robust-AND-physical duty is
   blocked on the integrator, not the kinetics.

---

## STEP A — what caps the dwell (measured; the findings' churn story is CONFIRMED, with a correction)

### A1 — bind-eligibility: the binder is PURELY GEOMETRIC (not ADP·Pi-gated)
`BindingDetectionSystem.bindNearest` (`BindingDetectionSystem.java:356-390`) is the `-lymntaylor` gliding binder
(dispatched `GlidingHarness.java:452` CPU / `:631,:655` GPU). Its **only** gate is the `FREE_BINDABLE` sentinel
(`:367`) + the geometric `reachTestDistSq` (perp distance ≤ `myoColTol` + alignment). **`nucleotideState` is
never read by any binder** (confirmed by full grep of `BindingDetectionSystem`). So a head in **any** nucleotide
state — NONE, ATP, ADP·Pi, ADP — can geometrically strong-bind. jba's expectation ("binding is already
ADP·Pi-gated") is **false in the code.** The `cycleLymnTaylor` comment "bind in ADP·Pi (…the binder side,
unchanged)" was **aspirational, never enforced** — the faithfulness gap this task closes.

Step order (both runners): `publishHead → bruteReachable → bindNearest (ungated) → cycleLymnTaylor
(cycle + the single NONE→ATP detach)`. Because bind runs **before** the cycle, a head can bind in step *t* and
be ejected by the cycle in the **same** step *t*.

### A2 — the census + the counting bug that hid the churn
A first (buggy) census suggested "no churn, 88 % clean ADP·Pi binds, dwell 0.35 ms." **That census was wrong.**
The `-diag` episode tracker counted a release only when `boundSeg == FREE_COOLDOWN` **and** `prevBound ≥ 0` — so
it **missed every same-step bind-then-eject episode** (the head is `FREE` at both post-step samples, `prevBound`
is never `≥0`). It undercounted releases **14×** and thus overstated dwell **~10×**. The kernel counters
(`mot.stats`, incremented inside `cycleLymnTaylor`) tell the truth, and **both `-grid` runners AND the corrected
`-diag` xcheck agree**:

| measure (v1box, coltol8, d1000, steady) | releases | mean dwell | detach-rate |
|---|--:|--:|--:|
| `-diag` episode tracker (BUGGED, FREE_COOLDOWN-only) | 977 | 33.5 steps / 0.335 ms | — |
| `mot.stats` (kernel counters, TRUE) | **14098** | **3.6 steps / 0.036 ms** | **27857 /s** |
| CPU `-grid` STATS_STEADY (independent) | — | 3.9 steps / 0.039 ms | 25540 /s |
| GPU `-grid` STATS_STEADY (independent) | — | 3.8 steps / 0.038 ms | 26016 /s |

The corrected census attributes the 14× gap to **same-step bind-then-eject episodes: 14098 − 977 ≈ 91 % of all
releases**. These are the **bind-in-ATP ejections**: a head detaches (NONE→ATP), is now FREE in ATP, and takes
~10 ms (`offATP` 100 /s) to recover ATP→ADP·Pi; during that window the ungated binder geometrically re-binds it,
and the very next cycle step ejects it (a bound head ending in ATP detaches). The **CPU/GPU "divergence" I briefly
suspected was a red herring** — both `-grid` runners agree at ~0.04 ms; only the buggy `-diag` disagreed.

### A3 — which rate sets the lifetime, and the ADP·Pi bind-gate (the fix, `-adppibind`)
The task's "25000 /s ≫ onADP 1000 /s" is explained: the aggregate detach-rate is **churn-dominated**, not set by
the ADP-release step. Gating strong-binding to ADP·Pi (a head binds only when `nucleotideState == NUC_ADPPI`;
`kinParams[20]`, default 0 ⇒ byte-identical) removes the churn and exposes the real clock:

| v1box coltol8 d1000, steady | dwell | detach-rate | releases | avgBound (sampled) | glide (µm/s) |
|---|--:|--:|--:|--:|--:|
| `-lymntaylor` (ungated) | 0.036 ms | 27857 /s | 14098 | 1.17 | −0.935 |
| `-lymntaylor -adppibind` (gated) | **0.344 ms** | **2909 /s** | **1104** | **1.18** | **−0.987** |

With the gate the turnover is **ADP-release-limited** (the `ADP→NONE` step, base `onADP` 1000 /s, load-modulated
by the catch `g(F)`; the assisting-load population `F<0` gives `g≈3` ⇒ ~0.34 ms, a physical loaded-skeletal dwell
~3× the unloaded 1 ms). The gated detach-rate 2909 /s ≈ `onADP · g` — the biochemical clock, no longer a churn
artifact. **This matches the `cycleLymnTaylor` design intent and myosin biochemistry** (strong binding is the
weak→strong ADP·Pi step; M.ATP is detached), so it is a **faithfulness fix**, not a tuning knob.

**Fork verdict: Fork 1 (rebind/ejection churn) — CONFIRMED and fixable.** BUT with an important qualification the
task anticipated: **the dwell-fix does NOT recover duty.** Killing the churn leaves the *sampled* avgBound ≈ flat
(CPU) / slightly lower (GPU, see below) — the churn heads were bound ≤1 step and contributed essentially no
steady engagement — so the low avgBound is **not caused by the churn**. The genuine engagement lever is **STEP B
(capture radius)**. The dwell-fix stands on its own as the faithful, dt-robust, biochemically-set turnover.

GPU cross-check (coltol10, d1000, 120k, 1 s window):

| config | velFitX | avgBound | dwell | detach-rate |
|---|--:|--:|--:|--:|
| ungated | 1.325 | 1.63 | 0.031 ms | 32075 /s |
| gated (`-adppibind`) | 1.126 | 1.50 | 0.344 ms | 2909 /s |

So at a wider reach the gate costs ~15 % glide / ~8 % avgBound (the churn heads apply a partial stroke in their
one bound step before ejection) — a small, honest faithfulness-vs-throughput trade, **not** an engagement gain.

**Measurement hygiene (fixed here):** the whole-run `STATS_ROW` dwell is **cumulative from step 0**
(`stats.init(0)` once, never reset; all heads start `NUC_NONE`), so its first ~10 ms are a startup avalanche that
biases the average low even in the ungated case is **not** the cause — but the cumulative average IS contaminated.
The new **`STATS_STEADY_ROW`** snapshots `stats` at a 0.20 s warmup cutoff and reports the delta (host-side; no
physics touch). Use `STATS_STEADY_ROW`, not `STATS_ROW`, for any steady-state dwell/detach number.

---

## STEP B — raise duty via capture radius; confirm duty stays LOW & dt-robust  [IN PROGRESS]

### B-dt — the dt re-confirm at the recovered operating point (coltol10/d1000) — the DECISIVE result
| config | dt | velFitX (µm/s) | avgBoundSteady | dwell (ms) | detach-rate (/s) |
|---|--:|--:|--:|--:|--:|
| ungated (`-lymntaylor`)  | 1e-5   | 1.325 | 2.41 | 0.031 | 32075 |
| ungated (`-lymntaylor`)  | 2.5e-6 | **7.558** | **4.26** | **0.030** | **33072** |
| **gated** (`-adppibind`) | 1e-5   | 1.126 | 1.50 | 0.344 | 2909 |
| **gated** (`-adppibind`) | 2.5e-6 | **6.477** | **3.45** | **0.733** | **1364** |

**Neither config is dt-robust in avgBound / glide — both climb ~2× / ~5.7× as dt refines 4×.** This splits into
two mechanisms:

1. **The detach-rate / dwell** *is* dt-robust in the **ungated** config (32075→33072 /s, 0.031→0.030 ms — the
   **bind-in-ATP churn is a genuine per-step, dt-independent clock**, confirming the prior findings' STEP-2 win)
   but is **dt-DEPENDENT in the gated** config (2909→1364 /s, 0.344→0.733 ms). With the churn removed, the sole
   detachment `ADP→NONE = onADP·g(F)` and the catch `g(F)` reads the **dt-dependent F8 overshoot**, so removing
   the churn *exposes* the catch's dt-coupling the churn was masking.
2. **avgBound and glide climb in BOTH configs regardless** (ungated avgBound 2.41→4.26 / glide 1.33→7.56; gated
   1.50→3.45 / 1.13→6.48) — and in the ungated config the **dwell is flat**, so this climb is NOT the detachment
   mechanism. It is the **standing cross-bridge overshoot**: at coarse dt the F8 spring is over-stretched, which
   both suppresses binding geometry and suppresses per-bound drift; as dt→0 the force converges, binding rises and
   per-bound glide rises (glide climbs *more* than avgBound ⇒ the per-bound drift itself converges upward). This
   is the same effect `viscosity-diagnostic-verdict` / `substep-feasibility-verdict` already flagged
   ("dt=1e-5 under-binds ~4×; converged glide needs a cross-bridge sub-step"), **independent of the cycle**.

**⇒ The honest STEP-B answer to "keep the dt-robust LOW physical duty":** the nucleotide-detachment removed the
avgBound **saturation** (no runaway to 216 — the prior win stands), but **dt-robustness of glide/avgBound is NOT
achieved**, and physical dwell (gated) is itself dt-dependent through the catch. Both point to **one shared root
cause — the coarse-dt cross-bridge (F8) overshoot — and one shared fix: a sub-step / implicit cross-bridge** so
the force (hence `g(F)`, binding geometry, and per-bound drift) is converged. This is a **numerics/integrator**
lever, not a kinetics knob; the capture-radius engagement lever (below) is valid at fixed dt but its absolute
µm/s are dt-underconverged until the cross-bridge is sub-stepped.

**B-dt-xbi — does the local-implicit cross-bridge (`-xbimplicit`) fix the dt-trend? Partially — NO.** The sweep
uses the **explicit** F8 spring (`XB_IMPLICIT=false`); I re-ran the gated dt-reconfirm with `-xbimplicit` (the
locally-implicit bound-head spring, `implicit-crossbridge-integrator-lever`) to test the root-cause claim directly:

| coltol10/d1000, gated | dt | velFitX | avgBound | dwell (ms) | detach (/s) |
|---|--:|--:|--:|--:|--:|
| explicit    | 1e-5   | 1.126 | 1.50 | 0.344 | 2909 |
| explicit    | 2.5e-6 | 6.477 | 3.45 | 0.733 | 1364 |
| xbimplicit  | 1e-5   | 1.756 | 1.95 | 0.456 | 2194 |
| xbimplicit  | 2.5e-6 | 7.173 | 4.04 | 0.739 | 1353 |

The implicit spring **raises the coarse-dt (1e-5) values toward the converged limit** (+56 % glide, +30 %
avgBound — the overshoot IS the culprit, confirming the root cause) but only **shrinks the 1e-5→2.5e-6 climb from
~5.7×→~4.1× (glide) and ~2.3×→~2.1× (avgBound)** — it does **not** flatten it. And xbimplicit @2.5e-6 (7.17)
*exceeds* explicit @2.5e-6 (6.48), so **neither scheme is converged even at 2.5e-6** — the true continuum glide is
higher still. This is exactly the standing verdict (`implicit-crossbridge-integrator-lever`): the **cheap
local-implicit form buys ~2× faithful-dt, not convergence**; only a **coupled / sub-step cross-bridge**
(`substep-feasibility-verdict`) closes it. **The dt-robust-AND-physical duty remains blocked on that integrator
work — it is not reachable from the kinetics (nucleotide cycle / bind-gate / catch) at all.**

### B-sweep — capture radius × density × 3 seeds (gated, dt=1e-5, 120k = ≥1 s window)
Means over 3 seeds (`velFitX` = LS-centroid axial glide; `avgB` = `avgBoundSteady`; dwell/detach = STATS_STEADY):

| density | coltol (nm) | velFitX (µm/s) | avgBound | dwell (ms) | detach (/s) |
|--:|--:|--:|--:|--:|--:|
| 1000 | 6  | 0.966 | 1.12 | 0.357 | 2803 |
| 1000 | 8  | 1.122 | 1.35 | 0.347 | 2882 |
| 1000 | 10 | 1.165 | 1.52 | 0.344 | 2906 |
| 1000 | 12 | 1.113 | 1.70 | 0.343 | 2916 |
| 500  | 6  | 0.441 | 0.49 | 0.354 | 2825 |
| 500  | 8  | 0.425 | 0.58 | 0.353 | 2835 |
| 500  | 10 | 0.491 | 0.67 | 0.352 | 2844 |
| 500  | 12 | 0.528 | 0.78 | 0.351 | 2846 |

Ungated gate-cost brackets (d1000, 1 seed): ct6 velFitX 0.721 / avgB 1.11 / dwell 0.052 / detach **19335**; ct12
velFitX 1.351 / avgB 2.68 / dwell 0.026 / detach **38698**.

**Reads:**
1. **Capture radius raises engagement (avgBound) monotonically at both densities** — d1000 1.12→1.35→1.52→1.70,
   d500 0.49→0.58→0.67→0.78 (ct 6→12). The engagement lever works, and it is **not saturating** (avgBound stays
   O(1), never the runaway carpet) — so the old tug-of-war/cancellation objection to widening reach is indeed
   gone in the low-duty cycle, as the task anticipated.
2. **Glide rises then plateaus** — d1000 velFitX ~0.97→1.12→1.17→1.11 (plateau ~1.1–1.2 µm/s; the ct12 dip is
   seed noise), d500 0.44→0.53. So `velFitX/avgBound` (per-bound drift) falls with reach (0.86→0.65 at d1000) — a
   **mild** residual co-bound tug-of-war, but a plateau, not the collapse of the saturated regime.
3. **Density is the stronger lever** — d500 (avgB 0.5–0.8, glide 0.44–0.53) vs d1000 (avgB 1.1–1.7, glide
   0.97–1.17). The engagement threshold sits **between d500 and d1000** — still **above Uyeda (~100–300 µm⁻²)**,
   the standing flat-single-tip sparse-binding residual; widening reach lowers it (d500/ct12 avgB 0.78 vs
   ct6 0.49) but does not reach the Uyeda band.
4. **Duty stays LOW and PHYSICAL across the whole surface** — gated dwell **0.34–0.36 ms** and detach
   **~2800–2920 /s** are essentially **constant** vs radius AND density (a per-bound kinetic clock, the
   ADP-release rate), and avgBound stays O(1). So the engagement gain is *pure duty-cycle physics*, not a slide
   back toward saturation. **Contrast the ungated churn:** its detach-rate *rises* with reach (19335→38698 /s,
   ct6→ct12 — wider reach ⇒ more geometric ATP-head rebinds ⇒ more churn), while the gate holds it flat ~2900 —
   so the bind-in-ATP churn is confirmed a *geometric-rebind* artifact, and the gate's value grows with reach.

### B-sweep-5e-6 — the same grid at dt=5e-6 (the convergence midpoint; gated, 240k steps)
Re-ran the full gated capture×density×3-seed grid at dt=5e-6 (means over 3 seeds):

| density | coltol (nm) | velFitX (µm/s) | avgBound | dwell (ms) |
|--:|--:|--:|--:|--:|
| 1000 | 6  | 2.094 | 1.21 | 0.632 |
| 1000 | 8  | 3.407 | 2.12 | 0.617 |
| 1000 | 10 | 3.840 | 2.65 | 0.613 |
| 1000 | 12 | 4.560 | 3.14 | 0.608 |
| 500  | 6  | 2.180 | 1.14 | 0.661 |
| 500  | 8  | 2.216 | 1.15 | 0.644 |
| 500  | 10 | 2.151 | 1.22 | 0.637 |
| 500  | 12 | 2.572 | 1.62 | 0.617 |

**Cross-dt convergence (gated, coltol10/d1000) — the surface climbs UNIFORMLY toward dt→0, not converged:**

| dt | velFitX | avgBound | dwell (ms) | detach (/s) |
|--:|--:|--:|--:|--:|
| 1e-5   | 1.165 | 1.52 | 0.344 | 2906 |
| 5e-6   | 3.840 | 2.65 | 0.613 | 1600 |
| 2.5e-6 | 6.477 | 3.45 | 0.733 | 1364 |

The 5e-6 midpoint confirms the dt-underconvergence is **smooth, monotonic, and uniform across every
capture-radius and density cell** (not a single-point artifact): halving dt raises avgBound ~1.75× then ~1.30×,
velFitX ~3.3× then ~1.7×, dwell ~1.8× then ~1.2×. The **engagement trend (capture radius ⇒ more avgBound) holds
at every dt**; only the absolute values shift up. Two dt-dependent nuances appear: (i) the avgBound climb-rate is
decelerating (1.75×→1.30×) — approaching convergence, slowly — while velFitX is still climbing fast; (ii) the
**density gap narrows at finer dt** (at 5e-6/coltol6, d500 velFitX 2.18 ≈ d1000 2.09 — the per-bound drift
converges upward more at low density where the co-bound tug-of-war is weaker), so the engagement *threshold*
itself is dt-dependent. **`BoA-v1ref` untouched; the 5e-6 grid spans jba's 18:19 `-xbimplicit2` rebuild but that
change is provably additive + flag-gated ⇒ the default/`-adppibind` path is byte-identical throughout (verified).**

**Parallel note (jba's `-xbimplicit2`, `COUPLED_IMPLICIT_XB_FINDINGS.md`):** the COUPLED head+site implicit
cross-bridge — the exact "sub-step/coupled" fix this report's B-dt verdict points to — was built in parallel and
is the **best partial to date** (unbiased; converges the binding + detach clock; velFitX climb 5.75×→3.11×) but
**still does not converge glide at dt=1e-5** (residual is the per-bound stroke/force, still explicit). This
independently **confirms the B-dt conclusion**: the dt-robust physical duty is an integrator problem, and even a
coupled-implicit F8 is not sufficient — the remaining residual is the explicit stroke/force, pointing to a full
cross-bridge sub-step.

### STEP-B verdict
**STEP-B verdict:** avgBound and glide **can be recovered at low density via capture radius** (engagement up,
threshold down toward — not into — Uyeda), and with `-adppibind` the **duty stays low, physical, and
biochemically-clocked** (dwell 0.34 ms, detach ~2900 /s, avgBound O(1)) — **at fixed dt.** The one thing that does
**not** hold is **dt-robustness of the absolute glide/avgBound** (B-dt): the whole surface is dt-underconverged at
1e-5 and climbs ~2×/~5.7× toward dt→0, via the explicit cross-bridge overshoot, only partially helped by
`-xbimplicit`. A dt=5e-6 rerun of this grid is in progress to map the surface's dt-shift.

Reproduce:
```
# STEP-A census + dwell fix (both runners; -adppibind default-off ⇒ byte-identical):
./run_gliding.sh -diag -lymntaylor            -v1box -density 1000 -coltol 8 30000   # ungated: dwell 0.036 ms
./run_gliding.sh -diag -lymntaylor -adppibind -v1box -density 1000 -coltol 8 30000   # gated:   dwell 0.344 ms
# STEP-B capture-radius sweep (GPU, ≥1 s window):
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -density <D> -coltol <ct> -seed <s> 120000
```
