# Standard 1× Contractility Benchmark — Four-Way Performance Sweep (v1/v2 × CPU/GPU)

**Date:** 2026-06-24 · aorus, TornadoVM 4.0.1-dev PTX, RTX 5070 · **MEASUREMENT-ONLY** (no model edits).
Sole GPU occupant. v1 = BoA scratch `/tmp/v1scratch` (physics == BoA-v1ref); v2 = SoftBox `V2OneXHarness`.

The biochem-free 1× standard scene: box 7.071×7.071×0.5 µm · 400 nodes × 24 singlet myosins (9600 myo) ·
1000 filaments × 10 segments (≈10k segs) · crosslinking ON · aeta 0.1 · treadmilling/biochem OFF.

## TL;DR
- **Parity gate PASSED** after two scene-param standardizations (NOT model edits): v2 myosin reach 0.025→**0.006 µm**
  (v1 `myoColTol`) and v2 crosslink on-rate 10→**40** (v1 pf). Both matched physical reach / actual rate. After the
  fix, v2-CPU bound-motor count tracks v1-CPU within ~10% at all scales (work-matched).
- **Steady-state steps/s (warmup-excluded), 1×/2×/4×/8×:** v1-CPU 30.4/18.7/10.9/5.86 · v1-GPU 21.4/17.1/12.8/8.4 ·
  v2-CPU 15.3/7.6/3.8/1.8 · v2-GPU **49.5/26.7/13.5/6.6**.
- **Crossovers:** v1 GPU-overtakes-CPU at **2–4×**; v2-GPU **always** beats v2-CPU (≥3.2×, crossover below 1×);
  cross-engine **v2-GPU vs v1-GPU cross at ~4×** (v2 ahead below, behind above — but high-scale is work-confounded).
- **Three named leads (not chased):** (A) v2-CPU single-thread path is 2.0→3.3× slower than v1-CPU's 16-thread pool,
  WIDENING with scale; (B) v2-GPU's headline lead over v1-GPU erodes 2.31×→0.79× across scale; (C) v1's OWN CPU≢GPU
  binding work diverges and widens (v1-GPU binds 53%→19% of v1-CPU) — a v1-internal confound that flatters v1-GPU at
  scale. v2's VRAM is far leaner (1× 508 MiB / 8× 1420 MiB vs v1-GPU's flat ~1.6–1.8 GB).

---

## PART 1 — PARITY TABLE (THE GATE)

**Measurement instrument (v1):** `BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=50` → `[STEP_PROFILE] ... (Y ms/step)`
over the window `[50, end)`, warmup-excluded. steps/s = 1000/Y. v2 harness excludes warmup natively.

Every parameter verified against the ACTUAL v1 PF (`/tmp/v1_fdt_diag/pf_1x`) + BoA-v1ref source and the v2
`V2OneXHarness`/`Constants` source. The PF **overrides** the v1 source defaults (e.g. dt 1e-4→1e-5,
filSegLength 32→64) — the PF is the run's source of truth; the v1 source supplies the formulas.

| Quantity | v1 param (file/PF) | v1 effective value | v2 param | v2 value | Match |
|---|---|---|---|---|---|
| dt (step) | `deltaT` (PF) | **1e-5 s** | harness `dt` | 1e-5 s | ✓ |
| biochem dt | `biochemDeltaT` (PF) | 1e-3 s | `XL_CHECK_INT`·dt | 1e-3 s | ✓ |
| box dims | `boxXDim/Y/Z` (PF) | 7.071×7.071×0.5 | `BOX_XY/BOX_Z` | 7.071×7.071×0.5 | ✓ |
| box geometry | `rdmPtInside`=±boxDim/2 | ±3.5355 µm | half=0.5·BOX_XY | ±3.5355 µm | ✓ |
| filament count | `initialFilaments` (PF) | 1000 | `N_FIL` | 1000 | ✓ |
| segments/filament | (contour/seg, ~10) | ~9.9 (9885 segs) | `SEG_PER_FIL` | 10 (10000 segs) | ≈ (−1.2%) |
| monomers/segment | `filSegLength`/`stdSegLength` (PF) | **64** | `FIL_MONO` | 64 | ✓ |
| `actinMonoDiam` | `Env.java:528` | 0.0054 µm | `Constants.actinMonoDiam` | 0.0054 µm | ✓ |
| segment length | `(monomerCt+1)·actinMonoRadius` | 0.1755 µm (meanSeg 0.181*) | `(FIL_MONO+1)·monoR` | 0.1755 µm | ✓ (formula) |
| contour length | `minFilLength..maxFilLength` (PF) | 1.72–1.82 µm | `SEG·segLen` | 1.755 µm | ✓ (in window) |
| aeta (viscosity) | `aeta` (PF) | 0.1 Pa·s | `Constants.aeta` | 0.1 Pa·s | ✓ |
| `BTransCoeff` | PF | 1.0 | `Constants.BTransCoeff` | 1.0 | ✓ |
| `BRotCoeff` | PF | 0.5 | `Constants.BRotCoeff` | 0.5 | ✓ |
| node count | `initialNodes/equilNodes` (PF) | 400 | `N_NODES` | 400 | ✓ |
| node radius | `nodeRadius` (PF) | 0.05 µm | `NodeStore.NODE_RADIUS` | 0.05 µm | ✓ |
| singlet myo/node | `numNodeMyos` (PF) | 24 | `N_SING` | 24 | ✓ |
| dimers/node | `numNodeMyoDimers` (PF) | 0 | `N_DIM` | 0 | ✓ |
| **myosin bind reach** | `myoColTol` (`Env.java:826`) | **0.006 µm** | `REACH` → kinParams[7] | **0.006 µm** | ✓ **(FIXED)** |
| myosin align gate | `myoMotorAlignWithFilTolerance` | −0.4 (cos) | `ALIGN_TOL` → kinParams[8] | −0.4 | ✓ |
| myosin rod gate | `rodDotFil ≥ 0` | yes | `reachTestDistSq` | yes | ✓ |
| binding model | `MyoMotor.checkFilSegCollision` | DETERMINISTIC | `bindNearest` | deterministic | ✓ |
| node-held self-bind excl. | `soaNodeAtEnd2 ⇒ return` | yes | seedNode=−1 (IC, moot) | moot here | ✓† |
| myosin release | Guo-Guilford catch-slip | kOff=100/s | `KOFF` / catchSlip | kOff=100/s | ✓ |
| 12 pN break cap | `myosinBreakForce`=12 pN | ON | `setFaithfulRelease(true)` | ON | ✓ |
| **crosslink on-rate** | `xLinkOnRate` (PF) | **40.0** | `XLINK_ON_RATE` | **40.0** | ✓ **(FIXED)** |
| crosslink conc | `xLinkConc` (PF) | 1.0 µM | `XLINK_CONC` | 1.0 µM | ✓ |
| crosslink reach | `crossLinkGrabDist`=2·actinMonoDiam | 0.0108 µm | `GRAB_DIST`=2·actinMonoDiam | 0.0108 µm | ✓ |
| formation cadence | `crosslinkCheckInt`=biochemΔ/dt | 100 steps | `XL_CHECK_INT` | 100 | ✓ |
| **P_form** | `1−exp(−kon·conc·dtCheck)` | **0.0392** | `pForm()` | **0.0392** | ✓ **(now matches)** |
| max links/seg | `maxXLinksOnSeg` (PF) | 10 | `MAX_LINKS_ON_SEG` | 10 | ✓ |
| max bond angle | `maxXLinkBondAngle` (PF) | 0.6 rad | `XL_MAX_ANGLE` | 0.6 rad | ✓ |
| treadmilling/biochem | `noMonomersSimd`+all kATP/cof/nuc=0 | OFF | (no growth systems) | OFF | ✓ |

\* v1 `meanSegLenUM=0.181` (vs 0.1755): v1 builds each 1.72–1.82 µm IC filament from `monCt=filL/monoR−1`
(~654 mono) then splits into ~64-mono segments; the remainder segment carries the leftover ⇒ mean slightly
above 0.1755 and ~9.9 seg/fil (9885 total, not 10000). A one-time IC-build rounding, stable (biochem OFF),
~1% — **non-dominant**, accepted.

† node-held self-binding exclusion: the v2 IC filaments are NOT node-nucleated (`seedNode=−1`), so the
exclusion is moot in this scene on BOTH engines (v1's 1000 IC filaments are also free, not node-held).

### GATE DECISION: **PASSED** — after two standardizations (benchmark scene params, NOT model physics):

1. **myosin bind reach** `REACH 0.025 → 0.006` µm (v1 `myoColTol`). The 0.025 was a v2 carry-over; v1's
   physical reach is 0.006. 4.2× over-reach ⇒ v2 bound ~3.6× too many motors (1971 vs v1 ~550). **Fixed.**
2. **crosslink on-rate** `XLINK_ON_RATE 10 → 40` (v1 pf_1x `xLinkOnRate=40`). v2 was the dense-preset default
   10; the 1× PF uses 40 ⇒ P_form 0.00995 vs 0.0392, ~4× too few links. **Fixed.** pForm now matches exactly.

Both were genuine, reconcilable mismatches matching **physical reach / actual rate**, not labels. The
crosslink REACH (0.0108 µm) already matched. After the fixes, v2 work tracks v1 (PART 3).

---

## PART 2 — FOUR-WAY STEADY-STATE STEPS/S GRID (warmup-excluded)

All measured (none extrapolated). v1: `[STEP_PROFILE]` window `[50, end)`, steps/s = 1000/(ms/step). v2: harness
window after 20 (GPU) / 5 (CPU) warmup steps. Measured-window sizes: 250–540 steps (see notes).

### Primary: warmup-EXCLUDED steady-state **steps/s**

| scale | entities (nodes/fil/seg/myo) | v1-CPU | v1-GPU | v2-CPU | v2-GPU |
|---|---|---|---|---|---|
| **1×** | 400 / 1000 / ~10k / 9.6k | **30.4** | **21.4** | **15.3** | **49.5** |
| **2×** | 800 / 2000 / ~20k / 19.2k | **18.7** | **17.1** | **7.6** | **26.7** |
| **4×** | 1600 / 4000 / ~40k / 38.4k | **10.9** | **12.8** | **3.8** | **13.5** |
| **8×** | 3200 / 8000 / ~80k / 76.8k | **5.86** | **8.4** | **1.8** | **6.6** |

All 16 cells MEASURED (none extrapolated). Measured-window sizes: v1 350–540 steps, v2-GPU 520, v2-CPU 245–395.

### ms/step (the raw measurement)

| scale | v1-CPU | v1-GPU | v2-CPU | v2-GPU |
|---|---|---|---|---|
| 1× | 32.86 | 46.70 | 65.4 | 20.2 |
| 2× | 53.39 | 58.35 | 131.6 | 37.5 |
| 4× | 91.37 | 78.38 | 263.2 | 74.1 |
| 8× | 170.73 | 119.31 | 555.6 | 151.5 |

### Wall-clock incl-warmup (LABELED SEPARATELY — run duration, NOT per-step rate)

These are the timed-window walls (not whole-program). Whole-program wall adds JVM start + scene build + (GPU)
PTX-JIT compile (tens of seconds, larger at scale). Window walls: v1-CPU 1×/2×/4×/8× = 14.8/21.4/32.0/51.2 s;
v1-GPU = 25.7/32.1/39.3/53.8 s; v2-GPU 1×/2×/4×/8× = 10.5/19.5/38.6/78.7 s (520 measured steps each).

### VRAM (GPU paths, nvidia-smi peak during the timed window)

v1-GPU: 1× ~1614, 2× ~1586, 4× ~1702, 8× ~1778 MiB (v1 pre-allocates large device buffers ⇒ ~FLAT ~1.6–1.8 GB,
nearly scale-independent). v2-GPU: **1× peak 508 MiB, 8× peak 1420 MiB** (scales with the scene). v2's device
footprint is markedly LEANER than v1's and grows ∝ scene rather than v1's flat reservation — v2 fits ~8× the scene in
less VRAM than v1's 1×. (Idle GPU baseline 209 MiB.)

---

## PART 3 — WORK-MATCH VERIFICATION (precondition for interpreting timings)

Biochem off ⇒ the only work asymmetry is binding + crosslinking. After the PART-1 parity fixes:

### Bound-motor count (mean over the timed window)

| scale | v1-CPU (mean) | v1-GPU (mean) | v2-CPU† (final) | v2-GPU† (final) |
|---|---|---|---|---|
| 1× | 459 | 245 | 506 | 598 |
| 2× | 894 | 367 | 915 | 1106 |
| 4× | 1611 | 430 | 1567 | 2252 |
| 8× | 3067 | 573 | 2757 | 4472 |

† v2 counts are the harness's final-step instantaneous bound count (still mildly climbing in the shorter v2 window);
v1's are the run-mean `meanBoundMotors`. **v2-CPU tracks v1-CPU within ~10% at every scale** (506/915/1567/2757 vs
459/894/1611/3067) — a decisive work-match on the CPU path, and a ~9× improvement over the pre-parity-fix 1971-vs-459
(4.3× mismatch). v2-GPU runs slightly hotter (binds a bit more) than v2-CPU because the v2-GPU window had more
settled steps; it stays the same order as v1-CPU.

**KEY WORK-MATCH FINDING (a PARITY observation, surfaced):** v1's **own CPU vs GPU bound-motor counts diverge
strongly** — v1-GPU binds ~half of v1-CPU (245 vs 459 @ 1×, and the gap WIDENS with scale: 573 vs 3067 @ 8× ⇒
v1-GPU binds only ~19% of v1-CPU at 8×). This is a **pre-existing v1 internal CPU≢GPU divergence**, not introduced
here. It means v1-GPU does **progressively less binding/cross-bridge work** than v1-CPU as scale grows, so v1-GPU's
apparent speed advantage at high scale is partly a **lighter workload**, not pure throughput. v2-CPU≈v2-GPU bound
counts agree far more tightly (the v2 CPU≡GPU gate: bound-set Δ≤5/9600, aggregate-within-SEM).

### Crosslink count (linkCtMeanSettled — back-half mean)

| scale | v1-CPU (settled) | v1-GPU (settled) | v2-CPU (final) | v2-GPU (final) |
|---|---|---|---|---|
| 1× | 48 | 29 | 7 | 20 |
| 2× | 71 | 66 | 28 | 56 |
| 4× | 110 | 87 | 42 | 123 |
| 8× | 177 | 142 | 74 | 212 |

v1-CPU vs v1-GPU crosslinks also diverge (float32/RNG-ordering on stochastic formation, expected). v2's counts are in
the same BAND and SCALE the same way (monotone-increasing with F); pForm now matches v1 EXACTLY at 0.0392. v2's link
counts read low at 1× because the v2 windows are shorter (245–520 steps) and links are still ACCUMULATING toward the v1
settled mean (the v2 1× smoke reached 33 links by step 800, vs the final-window-instantaneous 7–20 here) — a
window-length artifact, not a formation-rate deficit (the rate matches). **Crosslink work is comparable** in order and
scaling after the parity fix; the residual is window-length + the v1-internal CPU≢GPU envelope. NB: the link count is a
**minor** fraction of the per-step kernel cost vs the dominant bound-motor cross-bridge work (which IS tightly matched,
±10% CPU↔CPU) — so timing comparability rests on the bound-motor match, which holds.

---

## PART 4 — DISCREPANCY GRID (bottleneck leads — flagged, NOT chased)

Pairwise ratios (steps/s) — all from MEASURED values:

| ratio | 1× | 2× | 4× | 8× | reading |
|---|---|---|---|---|---|
| **v2-GPU / v1-GPU** (headline) | 2.31× | 1.56× | 1.05× | 0.79× | v2-GPU starts AHEAD, **converges & crosses BELOW at ~4–8×** |
| **v2-CPU / v1-CPU** | 0.50× | 0.41× | 0.35× | 0.31× | v2-CPU **2.0→3.3× slower** than v1-CPU, gap WIDENS with scale |
| **v2-GPU / v2-CPU** (v2 crossover) | 3.24× | 3.51× | 3.55× | 3.67× | v2-GPU always beats v2-CPU (no crossover — GPU wins from ≤1×) |
| **v1-GPU / v1-CPU** (v1 crossover) | 0.70× | 0.91× | 1.17× | 1.43× | v1-GPU crosses ABOVE v1-CPU between **2× and 4×** |

### Named bottleneck leads (for SEPARATE investigation — not investigated here):

**LEAD A — "v2 sequential-path inefficiency, WIDENING with scale" (the v2-CPU vs v1-CPU gap).** v2-CPU is 2.0× slower
than v1-CPU at 1× and the gap **WIDENS to 3.3× by 8×** (ratio 0.50 → 0.41 → 0.35 → 0.31), yet v2-GPU is *faster* than
v1-GPU at small scale. The second-order tell fires hard: **v2 pays in the SEQUENTIAL path, not the device path.** v1
runs CPU on a 16-worker ThreadSet pool (multi-threaded); v2's `-cpu` runner executes the `@Parallel` kernels as
**plain single-thread Java for-loops** (the device-agnostic debug runner) — so the baseline gap is v2-CPU
single-threaded vs v1-CPU ~16-way. The *widening* with scale is the extra lead: v2-CPU's single-thread O(nSeg) CSR
scans + per-step host bookkeeping grow ∝ scale on one core (the documented CSR-host serial cost), so v2-CPU loses
ground as the scene grows. EXPECTED in kind (v2-CPU is a validation instrument, never a production path) but it is the
single largest cross-engine CPU asymmetry and it scales the wrong way — worth a look if v2-CPU is ever used for a large
triage run.

**LEAD B — "the v2-GPU↔v1-GPU crossover inverts vs the prior (1×) expectation" (headline departure).** Prior memory
said v2-GPU ≈ 2× v1-GPU at 1× and *improving* with scale. The measured trend is the OPPOSITE direction at scale:
v2-GPU/v1-GPU = 2.31× (1×) → 0.79× (8×) — v2-GPU's lead ERODES and it falls BEHIND v1-GPU by 8×. BUT this is
**confounded by LEAD C** — v1-GPU does progressively less binding work at scale (PART 3), so v1-GPU's high-scale
"speed" is partly a lighter load. The true per-unit-work comparison needs the work-normalized rate; raw steps/s
flatters v1-GPU at 8×. Lead: is v2-GPU's high-scale slowdown the documented chained-`execute()` per-step creep / the
CSR-host round-trip (∝ scale), or genuine kernel cost? (The CLAUDE caveat: "re-verify CSR-host net-positive at
ring-scale.")

**LEAD C — "v1's own CPU≢GPU work divergence widens with scale" (a v1-internal finding).** v1-GPU binds 53% (1×) →
19% (8×) of v1-CPU's bound motors. This is a v1 BoA-active concern (the GPU binding path under-binds at scale), not a
v2 issue — flagged for the BoA side. It makes every v1-CPU-vs-v1-GPU timing ratio a work-MISMATCHED comparison at high
scale (v1-GPU's 1.43× CPU-beating at 8× is partly fewer bound motors).

### Crossover scales (the two asked-for):
- **v1 engine:** GPU overtakes CPU between **2× and 4×** (0.91× → 1.17×). "1×" sits just below v1's crossover (0.70×) —
  matches the established prior.
- **v2 engine:** GPU **always** beats CPU (3.2× at 1×, no crossover in range) — v2's device-resident path amortizes
  from 1× because the v2-CPU sequential runner is single-threaded (LEAD A), so the v2 crossover is **below 1×**.
- **cross-engine GPU:** v2-GPU vs v1-GPU cross at **~4×** (1.05×), with v2 ahead below and behind above — but see LEAD
  B/C (the high-scale crossing is work-confounded by v1-GPU's lighter binding load).

---

## SANITY (per path/scale)
All 16 runs: stable, finite, no NaN/crash, bounded containment. v2 conservation EXACT / 0 phantoms (biochem off ⇒ no
allocation). Per-engine CPU≡GPU: v2 is the validated aggregate-within-SEM gate (bound-set Δ≤5/9600). v1's CPU vs GPU
**aggregate** diverges (bound 459 vs 245 @1×) — this is v1's documented chaotic float32/RNG-ordering decorrelation
AMPLIFIED by the v1-GPU under-binding (LEAD C); it is v1-internal, pre-existing, and flagged.
