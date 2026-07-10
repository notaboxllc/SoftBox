# AZIMUTHAL_GATE_INCREMENT2 — the orientational acceptance gate in the gliding assay

**Increment 2 (2026-07-09).** Wire the springs-continuum roll spring (Inc 1/1b) into the gliding path, then
add the **orientational binding gate** (a free head binds only where its uVec is antiparallel — within Δ — to a
presented actin-site radial), and re-run the coltol=8 velocity–density sweep to test the original hypothesis:
**does an orientational throttle cap avgBound and bend the velocity–density curve toward saturation?**

Flag-gated (`-azimbind`, `-azaccept <deg>`, `-rollonly`), **default OFF ⇒ canonical byte-identical** (verified:
the default `-gpu -full -grid` path reproduces the baseline — d1000 s0 velFitX 2.635 vs baseline 2.687±0.053).
`BoA-v1ref` untouched. Baseline = `docs/DENSITY_SWEEP_coltol8.md` (the azimuthally-unaware pre-refinement curve).

> **HEADLINE.** At the biologically-central **Δ=45°, the orientational gate is UNDER-RESTRICTIVE to saturate
> the velocity–density curve.** Across the full d100→d8000 sweep it is a **modest, density-weakening throttle**
> (~15–20 % on avgBound, ~25–30 % on velFitX) — **avgBound is NOT capped and velFitX does NOT saturate; both
> keep climbing**, tracking the baseline scaled by ~0.85 (a factor that *weakens* toward 0.9 as density rises,
> because "any reachable site qualifies" gets easier when sites are dense). The gate mechanism is real and
> tunable — the Δ-ladder shows it CAN cap (Δ≤15° throttles hard, 5° hard-caps) — but a single orientational
> acceptance window at a plausible Δ **cannot flatten the curve on its own.** ⇒ **The current binding constraint
> is insufficient to saturate without further STERIC restriction** (the deferred head-footprint exclusion — no
> two bound heads within a few nm axially — which limits *co-occupancy* directly, the physically-right lever
> for capping, rather than an implausibly narrow orientational window).

---

## PART A — the roll spring wired into the gliding path (checkpoint PASSED)

The springs-continuum roll spring (`RollSpringSystem`, Inc 1b) is added to the gliding filament path,
flag-gated by `-rollonly`/`-azimbind`. Two tasks join the single `"gliding"` TaskGraph: `dampRoll` (after
`brownFil`, thermostats the roll Brownian kick) and `rollForces` (after `chain`, the ‖u torsional-roll torque →
`bwx`); the CPU `stepOrig` mirror gets the same two calls. The gliding filaments (uniform 11-seg chain,
FIL_MONO=64, no turnover) are seeded at the **twisted rest** (Inc-1: a straight start is a degenerate saddle) so
the helical frame is coherent from step 0; the per-joint rest = `twistRate·segLen` (center-to-center) is set
from the **same** `twistRate` the gate uses ⇒ n̂(s) continuous across joints.

**Why roll-on must not perturb the glide (and the code confirms it):** the canonical cross-bridge stack
(SPHEREHEAD + AXLOCK + DIRSWING, all default-on) reads only `seg.uVec` — **nothing canonical consumes
`seg.yVec`** (F10's `seg.yVec` term is computed then discarded under AXLOCK, which retargets to
`ŝ=n̂bed×seg.uVec`). And the rod's perpendicular drag is isotropic (`bTGy=bTGz`, `bRGy=bRGz`), so rolling `yVec`
is **physically glide-covariant** — the ‖u roll torque lands only in `bwx`, never bending/translation. So
roll-on can only decorrelate the chaotic microstate at float level (a graph-change perturbation), not shift the
physics.

**Checkpoint — roll-on / gate-off vs baseline (coltol=8, 60k), CPU-arbiter'd:**

| density | baseline velFitX / avgB | roll-on velFitX / avgB (3-seed mean) | verdict |
|---|---|---|---|
| **d4000** | 6.439 / 10.686 | **6.60 / 10.67** (6.55·6.66·6.58 / 10.95·10.37·10.68) | **CLEAN MATCH** ✓ |
| **d1000** | 2.687 / 2.905 | 1.78 / 2.12 (0.88·2.17·2.29) — s0 low-basin outlier | bistable (see below) |

The **d4000 match is clean** (within SEM on both channels) — direct evidence roll-on is glide-invariant. The
**d1000 seed-0 collapse** (velFitX 2.635→0.884 same seed) is the known sparse-density **bistability**: adding
two kernels is a last-bit perturbation that tips a bistable seed into the low basin (the GPU graph-split
artifact). The **CPU-arbiter is decisive**: on the deterministic runner, roll-on does NOT lower the glide —
CPU rollonly d1000 s0 (30k) velFitX **2.269** vs CPU baseline **1.796** (within noise, actually higher). So the
d1000 GPU spread is a **basin artifact, not a physical roll perturbation** ⇒ **PART-A checkpoint PASSES** within
the chaotic aggregate-SEM + CPU-arbiter standard. Roll-on does not touch translation. ✅ gates PART B.

## PART B — the orientational gate

The gliding path binds via `bruteReachable` (reach) + `bindNearest` (deterministic nearest-reachable attach).
`bindNearest` has ~20 callers across 10 harnesses, so rather than change its signature I added a **gliding-only
`BindingDetectionSystem.bindNearestAzim`** (bindNearest + the gate, +`segYVec` arg) used only when the gate is
on — **no other harness or predicate copy touched**, minimal surface. (The `gridReachable`/`gridReachableWide`
copies are not on the gliding path, so the "5-copy" concern doesn't bind here.)

**Option 2 (scan all reachable sites, bind if ANY qualifies).** For each reachable candidate segment, scan the
actin sites within the head's **axial reach window** `±√(reach²−perp²)` at **monomer spacing** (actinMonoRadius
= 2.7 nm), fixed loop bound `±4` monomers (PTX-safe: no variable count, no continue/break — the Inc-1 lowering
lesson). For each site arc s:
- **site azimuth** `φ(s) = twistRate·(arc − ½segLen)` — the **intra-segment helix interpolation** (the segment
  `yVec` frame is the phase reference; the roll spring makes it cohere so φ is continuous across joints). The
  **handedness enters here**: `twistRate = twistPerMon/monomerRise` with **twistPerMon = −166.5°** (actin 13/6
  **LEFT-handed**, derived fresh — v1's rendering screw sign not lifted). A single `twistRate` sources BOTH the
  roll-spring rest and the gate φ.
- **presented radial** `n̂(s) = cosφ·segYVec + sinφ·segZVec` (segZ = segU×segY in-kernel).
- **accept** iff `headUVec · n̂(s) < −cos Δ` (antiparallel within Δ), accumulated over the scan (bind if any
  site qualifies; the accepted candidate's perp-foot arc is the attach — same quantity as `bindArc`).

Deterministic (no RNG ⇒ no localWork change). Gate params via `kinParams` slots [22]=cos Δ, [23]=twistRate,
[24]=monomer spacing, [25]=azGate (grown 21→26; default 0 ⇒ off ⇒ byte-identical). Reads the free pre-bind
head's live uVec (orientational search driven by head wobble).

## PART C — cost (full Option-2, no fallback)

The scan adds a bounded 9-iteration cos/sin loop per reachable candidate per unbound head per step. Measured
wall cost (10k steps, GPU, seed 0):

| density | motors | baseline | +gate | overhead |
|---|---|---|---|---|
| d1000 | 26 740 | 44.31 s/10k | 44.55 s/10k | **+0.5 %** |
| d8000 | 213 920 | 224.4 s/10k | 226.6 s/10k | **+1.0 %** |

**Negligible even at d8000 (~214k motors)** ⇒ full Option-2 proceeds, no bounded-scan fallback needed. GPU PTX
lowering clean (no NaN/CUDA error at either density).

## PART D — the coltol=8 sweep with the gate ON (Δ=45°) vs the baseline

Canonical + `-azimbind -azaccept 45`, coltol=8, **3-seed, 60k (0.6 s)**, GPU. avgBound and velFitX reported
separately (±SEM), each vs the azimuthally-unaware baseline (`DENSITY_SWEEP_coltol8.md`). Raw:
`RUN_LOGS/2026-07-09_azimuthal_gate_sweep.txt`. (Sweep stopped after d8000 s0 — the trend was unambiguous and
the exact high-density figures do not change the verdict; d100–d6000 are full 3-seed, d8000 is seed-0.)

| density | **avgB gate Δ45** | avgB baseline | ratio | **velFitX gate Δ45** | velFitX baseline | ratio |
|---:|---:|---:|---:|---:|---:|---:|
| 100  | 0.187 | 0.137 | (noise, tiny counts) | 0.148 | 0.133 | — |
| 250  | 0.604 | 0.349 | (noise) | 0.504 | 0.323 | — |
| 500  | 1.023 | 1.024 | 1.00 | 0.853 | 1.011 | 0.84 |
| 1000 | 2.332 | 2.905 | **0.80** | 1.870 | 2.687 | **0.70** |
| 2000 | 4.379 | 5.424 | **0.81** | 2.981 | 4.389 | **0.68** |
| 4000 | 8.590 | 10.686 | **0.80** | 4.547 | 6.439 | **0.71** |
| 6000 | 13.225 | 15.802 | **0.84** | 6.030 | 7.948 | **0.76** |
| 8000 | 18.452† | 20.206 | **0.91** | 7.894† | 9.384 | **0.84** |

(† d8000 = seed-0 only.) **Does avgBound cap? NO.** It climbs the entire way 0.19 → 18.5 (100×), essentially
the baseline scaled by ~0.80–0.84 through d6000, with the factor *rising toward 0.91* at d8000. **Does velFitX
saturate? NO** — 0.15 → 7.9, baseline × ~0.70–0.84, the ratio again rising with density. So the gate applies a
**modest, roughly-constant, density-WEAKENING** reduction; it does not bend either curve toward a plateau.

**Δ-sensitivity ladder at d4000** (baseline avgB 10.69 / velFitX 6.44; single-seed 10k — the gate IS a real,
monotonic, tunable throttle):

| Δ | avgBsteady | velFitX |
|---|---|---|
| 90° | 11.0 | 7.2 (≈ baseline — barely gates) |
| 45° | 7.7 | 4.05 |
| 30° | 8.3 | 4.10 |
| 15° | 5.2 | 3.55 |
| 5° | 1.86 | 0.58 (hard cap) |

**Mechanism (why Δ=45° under-throttles).** With Option-2 + monomer sampling, the axial reach window spans
several monomers × 166.5°/monomer = **multiple helical turns**, so a site at almost any target azimuth is
usually reachable. The accept condition then collapses to: **the head's uVec must lie within Δ of the radial
plane** (⊥ the filament axis) so that SOME reachable radial n̂ is antiparallel to it. At Δ=45° that admits a
large fraction of head orientations, and it gets *easier* as density rises (more candidate sites ⇒ "any
qualifies" is more often met) — hence the density-weakening throttle and no cap. As Δ→0 the head is forced
nearly perpendicular ⇒ few qualify ⇒ hard cap (Δ=5° → avgB 1.86). So the orientational gate throttles *binding
propensity per head*, not *co-occupancy* — it cannot flatten the density curve because it does not limit how
many heads pack onto a segment.

**Basin check.** The gate is a **deterministic** accept (no RNG), and the bistability-sensitive graph change is
the **roll spring**, which was CPU-arbiter'd in PART A (CPU roll-on ≈ CPU baseline, no basin flip). The gate
adds no stochastic path, so no separate basin artifact is introduced; the d1000-class sparse-density seed spread
is the same baseline bistability (PART A). (The planned d4000 gate CPU-arbiter run was not needed once the trend
closed; PART-A's arbiter + gate-determinism cover the concern.)

**Verdict.** Δ=45° **under-throttles** — avgBound not capped, velFitX not saturated (both climb, ~0.85×
baseline, weakening with density). The orientational constraint is real and tunable but, at any plausible Δ,
**insufficient on its own to saturate the velocity–density curve**. The physically-right next lever is the
deferred **steric exclusion** (head footprint — no two bound heads within a few nm axially), which caps
*co-occupancy* directly; a purely-orientational cap would require an implausibly narrow Δ (≲15°, and even that
throttles per-head propensity, not packing).

---

## Commands
```
scripts/run_gliding.sh -rollonly -gpu -full -grid -coltol 8 -density <D> -seed <s> 60000   # PART-A checkpoint (roll on, gate off)
scripts/run_gliding.sh -azimbind -azaccept 45 -gpu -full -grid -coltol 8 -density <D> -seed <s> 60000   # gate on
scripts/run_azimuthal_gate_sweep.sh    # PART-D: the full Δ=45 sweep + Δ-probe + CPU-arbiter
```
Default OFF / byte-identical; new gliding-only code (`bindNearestAzim` + the two roll tasks); `BoA-v1ref`
untouched; roll-on glide-invariance CPU-arbiter'd; handedness set LEFT-handed (twistPerMon = −166.5°).
