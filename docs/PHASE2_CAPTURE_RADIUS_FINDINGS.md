# Phase-2 — capture-radius (`myoColTol`) sweep: NOT a distinct speed axis on v2 — it walks the tug-of-war ceiling *steeper* than density

**Date:** 2026-07-02. Flag-gated param sweep (`-coltol <nm>`) + a read-only bound-population census
(`-stretchcensus`), both **default byte-identical**. **No default changed; no release/stroke change;
`BoA-v1ref` untouched.** GPU device-resident `-full` bed (13.37×2 µm), **d1000** (~26 740 motors), 150k steps
(1.5 s), dt 1e-5. The mat is seedable (`-seed 0/1/2`) ⇒ spreads are over 3 distinct mat draws.

## Measurement standard (same as the speed-lever task)
Per radius: **net `v_axial`** (LS-centroid drift along f̂, on-bed ≥1 s window, `PROPER_SPEED_ANALYSIS.md` —
the `LONG_ROW` estimator), **`avgBound`**, **per-bound drift = |v_axial|/avgBound**, each **mean ± population
SD over 3 mat draws**. Coverage-violated runs (`fullMat=VIOLATED`, filament reached a bed edge) are **excluded
from the velocity mean** (kept for avgBound, a low-variance readout). `axialFrac ≈ 0.95–1.00` throughout
(directed −x glide). Raw logs: `RUN_LOGS/2026-07-02_capture_radius_probe.txt` (seed 0),
`…_capture_radius_sweep.txt` (seeds 1/2), `…_capture_radius_satdensity.txt` (STEP-4 density check).

## TL;DR — capture radius is the OPPOSITE of what BoA's CPU side-result claimed: on v2 more radius LOWERS net glide
BoA (CPU, single-run 5/6/7 nm) reported avgBound rising with **per-motor drift ~flat (~0.19)** ⇒ net **+48 %**,
and hypothesised `myoColTol` as "the one axis that raises duty without the per-head efficiency tax." **On v2
(GPU, 3-seed, 4–8 nm) that does not hold.** avgBound rises monotonically (as BoA found), **but per-bound drift
COLLAPSES 0.380 → 0.081**, so **net `v_axial` FALLS 2.5× (−3.83 → −1.51 µm/s)**. `myoColTol` is **not** a distinct
engagement axis that beats the tug-of-war — it walks the **same** duty ceiling, only **steeper** than density.
There is **no route to skeletal 5–8 here**; the fastest point in the whole sweep is the *smallest* radius (4 nm,
−3.83), still short of the ~4.6 honest-v1 net.

---

## STEP 1 — the radius sweep (GPU, default motor, d1000, 3 seeds)

| `myoColTol` | net \|v_axial\| (µm/s) | avgBound | per-bound drift | dwell (ms) | ext (nm) | \|fdFil\| (pN) | n(clean) |
|--:|--:|--:|--:|--:|--:|--:|--:|
| 4 nm | **3.83 ± 0.03** | 10.08 ± 0.34 | **0.380** | 0.87 | 5.54 | 3.08 | 2 |
| 5 nm | **3.42 ± 0.06** | 12.15 ± 0.43 | **0.282** | 0.74 | 5.67 | 3.16 | 2 |
| 6 nm (default) | **3.07 ± 0.12** | 14.91 ± 0.34 | **0.206** | 0.62 | 5.95 | 3.29 | 3 |
| 7 nm | **2.38** (n=1) | 16.72 ± 0.11 | **0.142** | 0.52 | 6.06 | 3.41 | 1 |
| 8 nm | **1.51 ± 0.15** | 18.64 ± 0.54 | **0.081** | 0.45 | 6.27 | 3.55 | 2 |

(ext = anchor-spring extension = |F8|/myoSpring, the perpendicular tip-to-site distance; |fdFil| = mean
magnitude of the per-head along-filament force. Coverage-violated velocity points excluded: 4 nm s0, 5 nm s1,
7 nm s0+s2, 8 nm s0 — all mild lateral-Y edge excursions; the axial trend is unaffected and avgBound is kept.)

1. **Monotonic, large, and DOWN.** Over 4→8 nm net \|v_axial\| falls **−60 %** while avgBound rises **+85 %**.
   The default 6 nm sits mid-slope, not on a plateau (BoA's one correct observation: it's a sensitive regime).
2. **Tug-of-war, sharpened.** Per-bound drift falls **monotonically 0.380 → 0.081** — every extra bound head is
   *much* less productive, and here the product `avgBound × drift` is net-**decreasing** across the whole range.
   The 6 nm/d1000 point (drift 0.206, avgBound 14.9) reproduces the density-sweep d1000 anchor exactly
   (`PHASE2_SPEED_LEVERS` d1000: 0.206 / 14.9) — the two sweeps are cross-consistent at their shared point.

### vs BoA's CPU numbers (the refutation)
| radius | BoA CPU \|v\| | BoA avgB | BoA drift | v2 GPU \|v\| | v2 avgB | v2 drift |
|--:|--:|--:|--:|--:|--:|--:|
| 5 nm | 2.76 | 14.7 | 0.187 | 3.42 | 12.15 | 0.282 |
| 6 nm | 3.02 | 16.4 | 0.185 | 3.07 | 14.91 | 0.206 |
| 7 nm | 4.09 | 19.0 | 0.215 | 2.38 | 16.72 | 0.142 |

At **6 nm** the two agree on velocity (3.02 ≈ 3.07) — but the **trend inverts at 7 nm**: BoA rises (+35 %), v2
falls (−22 %). BoA's drift reads ~flat (~0.19, but single-run, ±15 % mat noise, and its 7 nm 0.215 is already
the highest of the three); v2's 3-seed drift is a clean monotonic collapse. **Most likely origin of the sign
flip:** the accepted **4b-iv parallel-scheme residual** — BoA-CPU is sequential (Gauss–Seidel, fresh
within-step forces) so co-bound heads see each other's updated positions and resist less; v2-GPU is
Jacobi-parallel (one-step-stale SoA forces) so co-bound heads resist harder. Adding co-bound heads (larger
radius) *amplifies* that parallel co-bound resistance, which BoA-CPU largely lacks. This is consistent with v2's
lower avgBound at every radius (12.2/14.9/16.7 vs 14.7/16.4/19.0) yet steeper drift decay.

## STEP 2 — the decisive overlay: per-bound drift vs avgBound (distinct axis, or the same duty curve?)

Plotting **per-bound drift against avgBound**, radius-sweep points (vary `myoColTol` @ d1000) over density-sweep
points (vary density @ 6 nm, from `PHASE2_SPEED_LEVERS`):

```
 avgBound   RADIUS-sweep drift        DENSITY-sweep drift
   ~6.4            —                     0.295  (d250)
  ~9.95     0.380 (r4, avgB 10.08)       0.238  (d500)
  ~12.4     0.282 (r5, avgB 12.15)       0.217  (d750)
  ~14.9     0.206 (r6, avgB 14.91)  ==   0.206  (d1000)   <- shared anchor (same run)
  ~17.0     0.142 (r7, avgB 16.72)       0.191  (d1500, avgB 17.3)
  ~18.9     0.081 (r8, avgB 18.64)       0.159  (d2000, avgB 19.3)
```

**Both sweeps lie on falling drift-vs-avgBound curves; they CROSS at the shared 6 nm/d1000 anchor.** Below the
anchor the radius curve sits *above* density (r4 0.380 vs d500 0.238 at matched avgBound ≈10); above it the
radius curve sits *below* density (r8 0.081 vs d2000 0.159 at matched avgBound ≈19 — roughly **half**). So the
radius drift-vs-avgBound curve is **steeper** than density's.

**Answer to the decisive question:** `myoColTol` is **NOT the "genuinely different axis"** BoA hypothesised. It
does **not** recruit equally-efficient heads (drift is not held flat); it collapses per-head efficiency **faster**
than density does. It walks along — indeed *plunges down* — the **same tug-of-war ceiling**, so it cannot beat it.
**Where does net turn over?** For density, net peaks at d1500 then drops. For radius, net is **already past its
peak at the smallest radius tested** — net rises as radius *shrinks*, so the net-maximising radius is **≤ 4 nm**
(smaller than the default). There is no radius that buys net speed over 6 nm; every step up costs net glide.

## STEP 3 — geometry/stretch check (4 nm vs 8 nm extremes): mild geometry shift, but it works AGAINST transport

jba's hypothesis: a larger radius admits more "stretched-out" configurations that may transmit force differently
per head (a mechanism *on top of* count). Census over the bound population (`-stretchcensus`, read-only —
`forceMag`/`forceDotFil`/`stats`, no force touched):

| metric (bound-pop mean) | 4 nm | 8 nm | Δ |
|--|--:|--:|--:|
| anchor-spring extension (nm) | 5.54 | 6.27 | **+13 %** (more stretched) |
| \|forceDotFil\| per head (pN) | 3.08 | 3.55 | **+15 %** (higher per-head axial force) |
| **signed** forceDotFil (pN) | 0.17 | 0.14 | ~0, radius-invariant (**axial cancellation**) |
| bound dwell (ms) | 0.87 | 0.45 | **−49 %** (detaches ~2× faster) |

- **The geometry effect is REAL but small and anti-productive.** 8 nm motors *are* more stretched (+13 %) and do
  carry higher per-head |axial force| (+15 %). So it is **not purely a count effect** — there is a measurable
  stretch/force shift. **But it does not raise per-bound drift; drift *falls*.** Two reasons the extra force is
  unproductive: (1) the **signed** mean forceDotFil stays ~0.13–0.17 pN — an order of magnitude below the ~3.3 pN
  magnitude — so the per-head axial forces largely **cancel** across the co-bound population (the tug-of-war,
  quantified); the extra stretched heads add opposing force, not net thrust. (2) **dwell halves** — stretched
  heads bind at larger offset/load and detach ~2× faster, so each contributes less directed displacement.
- **Verdict:** primarily a **COUNT** effect (more heads), with a mild geometry shift that makes each *added* head
  slightly more stretched/forceful yet **less** productive. The stretch geometry does **not** explain BoA's
  +48 %; on v2 it points the wrong way. This is fully consistent with STEP 2 (drift collapses, doesn't hold flat).

## STEP 4 — faithfulness read: what radius is physically right, and where does the density-response saturate?
`myoColTol` is a **physical** parameter (how close a head's tip must be to the filament axis to bind — head
reach / binding geometry), **not a free speed dial**. The point is not "crank it to hit 5–8" (which is
impossible here — larger radius *lowers* net). The defensible use is **matching the experimental density-response**.

**Coarse density-response at 3 radii (net \|v_axial\| µm/s, seed 0; 6 nm column = the 3-seed density-sweep mean
from `PHASE2_SPEED_LEVERS`).** `*` = coverage-violated (kept, informational; the peak-location read is unaffected).

| density (µm⁻²) | 4 nm | 6 nm | 8 nm |
|--:|--:|--:|--:|
| 500 | 2.77 | 2.37 | 1.86 |
| 1000 | 3.72\* | 3.07 | 1.61\* |
| 1500 | **4.51** (still rising) | **3.28** (peak) | 0.79 |
| 2000 | — | 3.08 (turned over) | — |
| **peak / saturation density** | **> 1500** (not yet saturated) | **≈ 1500** | **< 500** (already past peak) |

**The saturation/peak density shifts *down* as radius rises** — the clean confirmation of the engagement/duty
picture. A larger radius reaches the tug-of-war-limited plateau at **fewer motors**: 8 nm is already past its peak
by d500 (net falls 1.86→0.79 over d500→1500), while 4 nm is still climbing at d1500 (2.77→4.51). Duty confirms
it: 8 nm/d500 avgBound (14.2) ≈ 6 nm/d1000 (14.9) — **same engagement at half the density.** At *every* fixed
density the radius→net trend is DOWN, and it steepens with density (d500: 2.77/2.37/1.86; d1500: 4.51/3.28/0.79).

**Physical read.** `myoColTol` sets **where the density-response saturates**, not a speed. There is **no radius
that reproduces the experimental saturation point jointly**: the closest-to-Uyeda low saturation density is 8 nm
(<500 µm⁻²) but its plateau speed is ~1.5–1.9 µm/s (far below 5–8); the fastest v2 point anywhere is 4 nm/d1500
(4.51, near the honest-v1 ~4.6) but 4 nm saturates *above* 1500 and is *below* the physical head-reach default.
The v2 **parallel co-bound penalty** is the obstacle: high engagement (→ low saturation density, matching Uyeda)
*also* craters the speed, so this knob cannot land the experimental (density, speed) pair at once. **The
defensible way to fix `myoColTol` is by binding geometry / head reach and by the plateau *density*, not by net
speed; the 6 nm default is already on the high-engagement side and there is no speed argument to raise it.**

---

## Synthesis
- **`myoColTol` is not a distinct speed axis on v2.** It sits on the same falling drift-vs-avgBound curve as
  density and descends it **steeper**; net glide falls monotonically with radius. BoA's CPU "+48 %, drift-flat"
  does **not** reproduce — most plausibly the 4b-iv parallel co-bound residual (Jacobi vs Gauss–Seidel).
- **The tug-of-war invariant holds and tightens:** `net ≈ avgBound × per-bound-drift`, and radius trades duty
  against per-head efficiency *worse* than density. No lever tried (density / neck angle / rate / radius) breaks
  it; net stays pinned ≤ ~3.3 and radius can only *lower* it.
- **Flag for the isoform parameter file:** set `myoColTol` from **binding geometry / head reach**, and validate
  it by the **density at which glide plateaus**, not by net speed. Do **not** treat it as a speed knob.

## Runs (for the record)
```
GlidingHarness -gpu -full -grid -density 1000 -coltol <4..8> -stretchcensus -seed <0..2> 150000   # STEP 1/2/3
GlidingHarness -gpu -full -grid -density <500|1500> -coltol <4|8> -seed 0 150000                  # STEP 4
```
**Validation.** *Default byte-identical:* the override fires only when `COL_TOL != 0.006` and the census only
when `-stretchcensus` — a no-flag run is unchanged (only a diagnostic header line gains a `myoColTol=…` field).
*CPU≡GPU at the widened reach* (coltol 8, d250, 5000 steps): GPU ≡ CPU **bit-identical to printed precision**
(velFitX 1.172, avgB 11.314, avgBsteady 11.577, netX −1.163 all equal; netSteady 1.885/1.884 = float32 last-bit)
⇒ the widened `kinParams[7]` flows through `reachTestDistSq`/`bindNearest` identically on both runners.
*Race-free:* `-stretchcensus` is a host-side read after `transferToHost` (no kernel, no atomics). `BoA-v1ref`
byte-clean; no release/stroke/kinetics change.

New flags: `-coltol <nm>` (bind capture radius = `myoColTol` = `kinParams[7]`; default 6 nm ⇒ byte-identical;
overrides the perp tip-to-axis reach in both `bruteReachable` + `bindNearest`, CPU + GPU — GlidingHarness binds
brute-force so there is no grid-cell interaction); `-stretchcensus` (read-only bound-population geometry census:
extension / per-head axial force / dwell; default-off byte-identical, touches no force kernel). Exploration only,
**no promotion** (adoption/retuning is a separate PLANNER-signoff step).
