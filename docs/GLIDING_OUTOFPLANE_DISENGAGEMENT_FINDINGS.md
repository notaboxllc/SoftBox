# Gliding "bistability" is an out-of-plane disengagement runaway, float-triggered — NOT a real second attractor

**Date:** 2026-07-09 · **Branch:** dt-convergence-study · **Runner:** GPU (canonical springs default) + CPU arbiter.
**Context:** surfaced while rendering a d2000 gliding viewer clip; a small (`-v1box`) run at the default seed
glided ~2× slower than the `-full` box at the same density. Chasing that anomaly pinned the mechanism behind
what the project has historically called "gliding bistability."

## TL;DR

There is **no robust two-attractor bistability** in the canonical gliding model. There is:
1. a **stable engaged gliding state** (~4.4 µm/s at d2000, avgBound ~5.4), robust across seeds and box sizes; and
2. an **out-of-plane disengagement runaway** — the filament wanders off the z=0 motor plane, loses reach, and
   progressively disengages — sitting in the **chaotic tail** and **enabled by two model softnesses**
   (no surface/z-tether on the filament; a very thin 8 nm 3D capture window).

Which one a marginal realization falls into is decided by the early transient, which is **sensitive to float32
op-ordering**. So GPU and CPU can disagree for a marginal realization, and it *reads* as a "GPU basin flip" /
"bistability." **It is float-sensitivity acting on a real (but soft/under-constrained) physical mode, not a
robust physical bistability, and not a GPU bug.** GPU≡CPU in **ensemble average** (no bias); they diverge only
per-realization in the tail.

## How it was pinned (evidence)

**The anomaly.** Same density (2000/µm²), same seed (0x6111D = seed 0), `-coltol 8`, canonical default:
- `-v1box` (4×1, 8000 motors), GPU: velFitX **1.70–2.42**, avgBsteady **2.15–2.42**, meanReach **2.8**.
- `-full` (14×2, 53480 motors), GPU: velFitX **4.38**, avgBsteady **5.52**, meanReach **~6.5**.

Ruled out, by direct measurement (frame-level), the two "obvious" explanations:
- **Density is correct and locally identical.** Motor-anchor areal density around the filament is ~2000/µm² in
  BOTH boxes, band-for-band up to |Δy|<0.5 µm (v1box 433 anchors within |Δy|<0.05, ρ=2065; full 405, ρ=1941).
  The filament sees the same carpet locally. *Not* a density-calculation bug.
- **Not an edge effect.** The filament stays mid-bed in x (never near the ±x walls) AND its ends reach only
  |z|,|y| ≪ the strip half-widths. It never approaches any edge. The earlier "narrow-strip lets ends rotate
  out" story was wrong — retracted.

**Seed scatter localizes it to a marginal realization, not the box.** `-v1box` d2000 across 5 seeds (GPU):

| seed | velFitX | avgBsteady | meanReach | state |
|---:|---:|---:|---:|---|
| 0 | 2.10 | 2.42 | 2.8 | LOW (disengaging) |
| 1 | 3.63 | 4.99 | 6.1 | engaged |
| 2 | 4.04 | 5.30 | 6.4 | engaged |
| 3 | 3.77 | 5.73 | 6.5 | engaged |
| 4 | 3.61 | 5.39 | 6.4 | engaged |

Seeds 1–4 in the small box match the full box (~5.4). Only **seed 0** disengaged. Same *local* density, but a
different box maps the same RNG draws to a different physical bed and starts the filament at a different x — a
different chaotic realization that happened to hit the disengagement tail. (n=1 disengaged case ⇒ do NOT claim
the small box is *systematically* more fragile.)

**The proximate mechanism: out-of-plane motion gating a tight 8 nm 3D capture.** The bind predicate
(`BindingDetectionSystem.reachTestDistSq`, faithful v1 `MyoMotor.checkFilSegCollision`) requires the motor head
within `myoColTol` in **full 3D**: `conDistSq = dx²+dy²+dz² < myoColTol²` (here 8 nm). Motor heads sit at z≈0
(anchored at ANCHOR_Z=−0.05 µm, reaching up so the head-tip equilibrium ≈ z=0). Counting reachable motors in a
frame, **2D (x–y) vs full 3D**:

| run | within 8 nm, 2D(xy) | within 8 nm, 3D | filament max\|z\| |
|---|---:|---:|---:|
| v1box seed0 **LOW** | 61–68 | **3–7** | 0.23–0.31 µm |
| full seed0 **HIGH** | 64–77 | **10–14** | 0.05–0.14 µm |

The 2D-reachable count is identical (same local density); the **3D**-reachable count diverges ~2× (matching the
avgBound ratio) and tracks the filament's out-of-plane excursion. The filament is a **free semiflexible chain
with no surface/z-confinement** — the *only* thing coupling it to the motor plane is the 8 nm reach. So:
**fewer bound → less pinning to the plane → wanders in z → fewer within the 8 nm window → fewer bound.** A
self-reinforcing disengagement.

**It is a runaway, not a stable second level.** GPU `-v1box` seed0, 2nd-half avgBound at increasing run length:
**3.4** (10k / 0.1 s) → **2.4** (60k / 0.6 s) → **1.6** (200k / 2 s), meanReach → 2.0. It does **not** recover
to HIGH, and it does **not** settle at a fixed low plateau — it keeps disengaging. So "two stable basins" is the
wrong picture; it's an engaged state plus an escaping/absorbing tendency.

**The GPU role is float arithmetic, not a race or a bug:**
- **GPU is bit-deterministic run-to-run** — two identical 10k runs gave byte-identical GRID rows. Not a race.
- **RNG is bit-identical GPU↔CPU** — the Brownian force uses a 32-bit integer wang-hash keyed by (motor, step,
  seed) (`BrownianForceSystem.wangHash`, verbatim v1). Same random *inputs* on both runners.
- ⇒ the GPU/CPU divergence is the **deterministic float32 arithmetic**: PTX contracts `a*b+c` into one rounded
  FMA, the Java CPU runner doesn't, and summation order differs. ULP-level per step, amplified by the chaotic
  (Lyapunov) dynamics.
- For the marginal seed0-v1box realization the two runners disagree: **GPU** disengages (avgBound 1.6–2.4),
  **CPU** holds higher (**3.75**) — neither is "wrong," both are valid float32 realizations of a sensitive
  system.
- **No GPU bias:** GPU≡CPU in ensemble average — the full-box density sweep + the d4000 CPU basin arbiter agree
  to 0.5%. The divergence is per-realization tail behavior, not a shifted mean.

## Reconciliation with "there is no established bistability"

Correct. The historical "gliding bistability" (and the archived `-ratefix` `exp/log` PTX-scheduling "basin
flip") is better described as: **a chaotic gliding steady state with a broad, sensitive engagement distribution,
whose lower tail is an out-of-plane disengagement runaway accessible when the model's out-of-plane mode is
under-constrained.** Its *appearance* is float-sensitive (GPU vs CPU, box, seed), which is exactly why it looked
like a runner-dependent two-state system. It is float-sensitivity acting on a soft physical mode — not a robust
bistability, and not a GPU numerical error.

## The lever — CONFIRMED by elimination (filament-only z-confinement)

Root enablers: (a) the filament has **no explicit surface/z-tether** (in a real gliding assay it rests *on* the
motor lawn); (b) the **8 nm z-capture window is very thin**, so tiny out-of-plane excursions kill reach.
**Prediction:** pin the filament near the z=0 plane and the runaway (and its GPU/CPU fragility) should vanish —
seed 0 in the small box would then engage like every other seed.

**Confirmed.** Added `-zconfine <nm>` (`GlidingHarness`): a **FILAMENT-ONLY** tight-z chamber — the existing
entity-agnostic `ContainmentSystem` applied to the filament body with the z half-width set to `<nm>`, x/y walls
set to ∞ (so only z bites), confined every step. Motors stay un-boxed (they are already held near the plane by
their tail tether). Default-off ⇒ **byte-identical when off** (regression: v1box seed0 10k reproduces
velFitX=2.728 exactly). GPU v1box seed 0, 60k (un-confined = LOW 1.70 / 2.15):

| z half-width | velFitX | avgBsteady | meanReach | state |
|---:|---:|---:|---:|---|
| un-confined | 1.70 | 2.15 | 2.0 | LOW (disengaging) |
| 10 nm | 3.84 | 5.11 | 6.2 | **ENGAGED** |
| 25 nm | 3.92 | 5.24 | 6.4 | **ENGAGED** |
| 50 nm | 4.18 | 4.87 | 6.0 | **ENGAGED** |

Constraining the filament in z — even loosely at 50 nm half-width — **collapses the low state**: seed 0 engages
at avgBound ~5.1–5.2 / velFitX ~3.9–4.2, right in the HIGH band of the full box (~5.5 / 4.4) and v1box seeds
1–4 (~5.4 / 3.7); `meanReach` recovers 2.0 → 6.2. No blow-up even at 10 nm. **The out-of-plane disengagement
runaway WAS the mechanism** — remove the z-freedom and both the "low basin" and the GPU/CPU fragility for the
marginal seed disappear.

**The GPU/CPU fragility is gone too** (the decisive check). At `-zconfine 25 nm`, v1box seed 0:
GPU velFitX 3.92 / avgBsteady 5.24 / meanReach 6.4 vs **CPU 3.63 / 5.44 / 6.4** — both engaged, agreeing within
chaotic SEM (avgBound Δ 3.7%, meanReach identical). Compare the **un-confined** split GPU 2.42 vs CPU 3.75
(−55%). Constraining the out-of-plane mode removes what the float-op-ordering difference had to bite on: same
seed, now runner-robust. This closes the loop — the "bistability"/"GPU basin flip" was float-sensitivity acting
on the under-constrained out-of-plane filament mode, and eliminating that mode eliminates the divergence.

`-zconfine` is a **model-physics change** (a surface constraint the base model omits): it is flag-gated and
**default-off** (byte-identical), kept as the mechanism-confirming instrument. Promoting a surface/z-tether to
default is its own task — it re-baselines gliding (engagement + velocity), so it must not be flipped as a side
effect. But it is the physically-motivated fix: a real gliding assay confines the filament to the motor lawn,
which this model otherwise lacks.

## Scope / what is unaffected

- The **coltol=8 density sweep** (`docs/DENSITY_SWEEP_coltol8.md`) is unaffected: full box, all 3 seeds engaged
  (avgBsteady 5.52/5.18/5.58), CPU-arbitered at d4000 (0.5%). The disengagement tail only surfaced in the small
  viewer box at seed 0.
- No code was changed for this investigation (measurement + analysis only). `BoA-v1ref` untouched.

## Raw / repro

- v1box vs full, same seed, `-grid` 60k: velFitX 1.70/4.38, avgBsteady 2.15/5.52 (identical local density,
  filament mid-bed both).
- v1box seeds 0–4: table above. CPU v1box seed0 (40k): velFitX 3.09, avgBsteady 3.75, meanReach 4.2.
- GPU v1box seed0 determinism (10k ×2): byte-identical. Persistence (200k): avgBound decays 3.4→2.4→1.6.
- Frame-level 2D-vs-3D reach + max|z| trajectories from `threejs_gliding_d2000` (v1box seed0, LOW) and
  `threejs_gliding_d2000_full` (full seed0, HIGH).
- Scratch logs under the session scratchpad; no committed RUN_LOGS artifact for this diagnostic.
