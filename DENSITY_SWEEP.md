# Canonical model — first velocity–density sweep vs the skeletal band

**Date:** 2026-07-08 · **Branch:** dt-convergence-study · **Runner:** GPU (springs = transcendental-free ⇒
GPU-trustworthy; GATE-2 basin verified) + 1 CPU basin-arbiter point.
**Status: FIRST STAB** — steady window is solid-but-short (0.6 s run / 3-seed); read the *shape and ballpark*,
not publishable absolute numbers. A longer, more-seeded confirm is the follow-up if the shape holds.

---

## STEP 1 — script audit

**The flagged `run_densesweep.sh` is a PRE-COLLAPSE GHOST — not used.** It drives
`softbox.DenseGlidingHarness -scale {0.5,2,4,8}` (a box-*scale* dense throughput benchmark keyed to
`BENCHMARK_dense.md`), which is a different harness entirely. It does **not**: invoke the canonical gliding
model, sweep motor *density*, sweep *coltol*, or emit the `GRID_ROW` velocity/avgBound row. Reusing it would
have measured the wrong thing. Left in place (it's a valid dense-throughput script); a clean driver was written
instead.

**Clean driver written:** `scripts/run_canonical_density_sweep.sh`.

**Canonical invocation used (bare — model is the post-collapse default):**
```
./run_gliding.sh -gpu -full -grid -coltol 10 -density <D> -seed <s> 60000
```
- Springs (`-pairsprings -alignsprings -structsprings`), Lymn-Taylor (`-lymntaylor`), ADP·Pi-only binding
  (`-adppibind`, welded when LYMN_TAYLOR ∧ ¬ALLOW_BIND_ANY), and the coupled implicit cross-bridge
  (`-xbimplicit2`) are **all DEFAULT-ON** in the current source (canonical collapse, 2026-07-08). No model
  flags are passed.
- **`density` + `coltol` passed explicitly** (they are swept experimental conditions with placeholder defaults;
  passing them also suppresses the D7 `[SWEPT-PARAM WARNING]`).
- **`M = 60000` steps @ dt=1e-5** (velFitX/avgBsteady over the 2nd-half steady window) — matches the capstone
  ≈2.83 baseline (`PURE_SPRINGS STEP-3 GPU, 60000 steps`), so the d1000 point cross-checks the established
  reference.

**Audit verification (bare ≡ explicit canonical):** at d1000/seed0/3000 steps, the bare invocation and the
explicit GATE-2 flag stack (`-pairsprings -alignsprings -structsprings -lymntaylor -adppibind -xbimplicit2`)
produced a **byte-identical** `GRID_ROW` (velFitX=1.850, avgBsteady=2.750, every field). ⇒ bare = canonical.

**Working-tree note (production-safety):** the tree carries uncommitted Stage-3 canonical-collapse cleanup
(cosmetic `[NON-CANONICAL DIAGNOSTIC]` banners on diagnostic paths, the D7 swept-param warning, dead-code
deletion of the default-off `-allnoise`/`-thermcorr`/`-syswide` noise-correction family, a `CAP_ROW` label
rename). **None of it touches the canonical gliding physics path** (`measureGrid`, springs, Lymn-Taylor,
xbimplicit2 byte-unchanged); classes rebuilt clean from current source; no concurrent process. `BoA-v1ref`
untouched.

---

## STEP 2/3 — the velocity–density curve

All 16 runs (5 densities × 3 seeds GPU + 1 CPU arbiter) completed clean — **no NaN / blow-up / exception**
at any density. `coltol = 10 nm` held fixed. Raw: `RUN_LOGS/2026-07-08_canonical_density_sweep.txt`.

| density (µm⁻²) | nMot | **velFitX** (µm/s) mean±SEM | **avgBsteady** mean±SEM | per-bound (=velFitX/avgB) | inst (µm/s) |
|---:|---:|---:|---:|---:|---:|
| 100  | 2 674  | **0.143 ± 0.025** | 0.165 ± 0.007 | 0.87 | 6.44 |
| 250  | 6 685  | **0.473 ± 0.059** | 0.574 ± 0.050 | 0.82 | 6.44 |
| 500  | 13 370 | **1.100 ± 0.194** | 1.435 ± 0.182 | 0.77 | 6.56 |
| 1000 | 26 740 | **2.825 ± 0.054** | 3.317 ± 0.119 | 0.85 | 6.96 |
| 2000 | 53 480 | **3.821 ± 0.358** | 5.342 ± 0.678 | 0.72 | 7.40 |

(velFitX and avgBound reported **separately** — the arc's recurring trap; per-bound shown as a derived aid, not
the headline. d1000 reproduces the capstone baseline exactly: 2.825 ± 0.054, per-bound 0.85.)

### The velocity–density curve vs the skeletal band (~1.5–4 µm/s, Vmax ~2.9)
- **velFitX rises monotonically and begins to saturate above d1000.** The rise decelerates sharply at the top:
  d500→d1000 is +157 % (1.10→2.83) but d1000→d2000 is only +35 % (2.83→3.82) for the same 2× density step — the
  knee of a saturating curve.
- **Shape is sigmoidal/threshold-like, NOT simple hyperbolic.** A Michaelis–Menten fit anchored on d1000/d2000
  gives Vmax ≈ 5.9, KM ≈ 1090, half-max ≈ d1090 — but it **over-predicts the low end 2–3×** (predicts d250=1.10
  vs measured 0.47; d500=1.86 vs 1.10). The real curve has a **slow foot below d500**, consistent with an
  **engagement threshold**: avgBound < 1 at d ≤ 500, so the filament is sub-processively engaged and net
  directedness is throttled. Treat Vmax/KM as rough — the 5 points don't support over-fitting, and the curve is
  not plateaued at d2000.
- **Where it sits vs the band:** at **d1000, velFitX ≈ 2.83 = Vmax of the skeletal band (~2.9)** — the capstone
  operating point, top of the band. The nominal **Uyeda ~100–300 band maps to the rising foot here** (net velFitX
  0.14–0.47), *not* to Vmax.

### The important nuance — instantaneous speed is ~density-independent (the biological signature)
The **instantaneous** speed `inst` is **nearly flat: 6.4 → 7.4 µm/s (+15 %) across a 20× density change** — this
is the classic gliding-assay result (motor speed is set by the duty cycle, ~independent of density above the
processivity threshold; near v1's 8.33 µm/s fixture). So the strong rise in **net** velFitX is **not** the motors
going faster — it is **engagement**: the underlying speed-when-moving is constant, and density buys *directed
duty*. This is exactly why **per-bound is ~flat (~0.8 µm/s/head)** — per-head transport efficiency is
density-independent; velocity ∝ engagement.

### avgBound vs density (the deficit channel)
avgBound rises **super-linearly at low density then sub-linearly at high** (0.165 → 5.342, a 32× rise over 20×
density): +131 % across d500→d1000 but +61 % across d1000→d2000 — the same saturating knee as velFitX (they move
together, per-bound flat). Engagement crosses **avgBound ≈ 1 between d250 and d500**, the processivity foot; the
production operating point d1000 sits at avgBound ≈ 3.3, and d2000 reaches ≈ 5.3 before the sub-linear roll-off.
The production-dt engagement deficit lives in *this* channel (capstone open item), but it is not resolved here —
this sweep only maps how engagement scales, at fixed dt=1e-5.

### CPU≡GPU basin agreement at d1000 (the trust check)
| | velFitX | avgBsteady |
|---|---:|---:|
| GPU seed0 | 2.895 | 3.332 |
| CPU seed0 (arbiter) | 2.867 | 3.286 |
| Δ | **0.97 %** | **1.4 %** |

**Same HIGH basin, aggregate agreement within ~1–1.4 %** (well inside the chaotic aggregate-SEM standard). The
transcendental-free springs default makes the GPU internally deterministic; the CPU arbiter confirms the GPU
curve is not reading a flipped basin at this known-bistable operating point. **No basin disagreement ⇒ the curve
is trustworthy.**

### Plain verdict (first look)
**Yes — the canonical model produces a sensible, biologically-plausible velocity–density curve.** It rises
monotonically, saturates above d1000, and the instantaneous motor speed is ~density-independent at ~7 µm/s (the
correct gliding-assay signature, near the v1 fixture). At d1000 the net velFitX ≈ 2.83 sits right at Vmax of the
skeletal band (~2.9). The velocity rise is cleanly **engagement-driven** (per-bound flat ~0.8), not a
motor-speed artifact. Nothing looks broken — monotonic, saturating in the right place, no runaway.

**Flags (honest first-stab caveats):**
- **Short window / seed scatter.** d500 (seed0 low: 0.72 vs 1.34/1.24) and d2000 (seed2 low: 3.12/3.99 vs
  4.06–4.28/5.9–6.1) carry wide 3-seed spread — the known short-window single-seed velFitX noise. SEM is sizable
  there (d500 ±0.19, d2000 ±0.36); the shape is solid but those two absolute numbers are the softest.
- **Curve is sub-hyperbolic at the low end** — a threshold foot, not a defect: net directedness is
  engagement-limited where avgBound < 1 (d ≤ 500). Worth stating because a naive MM half-max (≈d1090) misreads
  the foot.
- **Vmax not reached at d2000** (velFitX still climbing +35 %); the true plateau/Vmax is an extrapolation — a
  longer, more-seeded run (and perhaps a d4000 point) would pin Vmax and KM if the shape is worth formalizing.

**This is a first stab (0.6 s / 3-seed).** The shape is clean and in the right ballpark ⇒ a longer, more-seeded
confirm (≥1 s window, ≥6 seeds, add d4000) is the warranted follow-up before quoting absolute Vmax/KM.
