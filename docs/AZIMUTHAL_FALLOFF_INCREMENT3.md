# AZIMUTHAL_FALLOFF_INCREMENT3 — graded orientational affinity (does a smooth falloff saturate the curve?)

**Increment 3 (2026-07-09).** Inc-2's hard antiparallel cutoff under-throttled (avgBound not capped, velFitX not
saturated — the "any reachable site qualifies" rule washed out the helical registration). This increment
replaces the hard cutoff with a **graded orientational affinity** (steepness knob `n`), combined by **MAX** over
reachable sites and applied as a **bind-rate multiplier**, to test: **does a smooth-enough falloff cap avgBound
and bend velFitX to a plateau where the hard gate could not — or does orientation fail to cap at any sharpness,
pointing to co-occupancy/steric as the real lever?**

Flag `-azfalloff <n>` (implies `-azimbind`), default OFF ⇒ canonical byte-identical. `BoA-v1ref` untouched.
Baseline = `docs/DENSITY_SWEEP_coltol8.md`; the hard-gate result = `docs/AZIMUTHAL_GATE_INCREMENT2.md`.

> **HEADLINE — a graded falloff does NOT saturate the curve at ANY steepness.** Across n=16 and n=32 the
> velocity–density curve keeps climbing (avgBound 1.9→15.1 at n16, 1.5→13.1 at n32) — the falloff applies a
> roughly **density-INDEPENDENT scale-down** (~0.7× avgB at n=16, ~0.65× at n=32, ~0.55× at n=64), never a
> plateau. The MAX-combine **did** fix the Inc-2 density-washout (the throttle ratio is now flat instead of
> *rising* with density) — a real improvement — but a per-head orientational throttle **scales** engagement, it
> cannot **cap co-occupancy**: MAX-combine finds the best azimuth in a multi-turn reach window regardless of
> density, so binding propensity depends on head orientation, not on how many heads already pack a segment.
> **⇒ Orientation fails to cap at any sharpness; the real lever is co-occupancy/STERIC exclusion** (the deferred
> head-footprint constraint) — consistent with the Inc-2 verdict and the concurrent detachment-ceiling read
> (force-summation ∝ N, no per-head displacement clutch). This is the decisive negative that closes the
> orientation route.

## The change (only the accept rule; Inc-2 scan/interp/handedness reused verbatim)

Kept unchanged from Inc 2: the reachable-site scan, the intra-segment interpolation `φ(s)=twistRate·(arc−½segLen)`,
the presented radial `n̂(s)=cosφ·segY+sinφ·segZ`, and the LEFT-handed `twistRate = −166.5°/mon`. **Changed only the
accept rule** (`BindingDetectionSystem.bindNearestFalloff`, a new gliding-only method — `bindNearest`'s ~20
callers + the Inc-2 `bindNearestAzim` untouched):

- **Graded affinity** per reachable site: `a(s) = ((−headU·n̂(s) + 1)/2)^n ∈ [0,1]` — 1 at perfect antiparallel,
  smoothly → 0 at parallel. `n` is the single steepness knob (`-azfalloff n`): **n=0 ⇒ a≡1 ⇒ no gating ⇒
  baseline**; n→∞ → the Inc-2 hard cutoff. Brackets both known behaviors.
- **MAX-combine** over reachable sites: `b_best = max_s (1−headU·n̂)/2`, `a_best = b_best^n` (computed as
  `exp(n·log b_best)`; exp/log lower on PTX). **NOT sum** — sum reintroduces the density-washout (more reachable
  monomers → more total affinity → easier binding, the Inc-2 failure). MAX = "bind to the **best-registered**
  reachable site at its affinity," which keeps strong binding sparse. The head attaches at the best-registered
  site's arc (`bestArc`), selected by max `b`.
- **Rate multiplier + race-free draw.** The gliding bind path is **deterministic-nearest with no existing draw**,
  so a draw was **added**: a race-free wang-hash `u < a_best` (per-motor, keyed `(m,step,seed)`, salt "AZBD"
  =0x415A4244), **drawn LAST** — after every cheap deterministic reject (not-bindable / -nobind / ADP·Pi-gate /
  no reachable oriented site), so RNG fires only for the few heads with a genuinely reachable oriented site. The
  `bind` task already runs at localWork=64 (the nM `addW` group) ⇒ no scheduler change; per-motor pure writes ⇒
  race-free, no KernelContext, CPU≡GPU-safe. A perfectly-registered site (b_best=1) binds at exactly today's
  rate (P=1); poorly-registered sites bind rarely.
- **n=0 recovery:** `a_best=1` ⇒ always-bind. Because the draw consumes RNG it is **NOT bit-identical** to the
  deterministic baseline (a fresh draw decorrelates the chaotic trajectory) but is **aggregate-equal** — verified:
  d4000 s0 (10k) n=0 velFitX 6.73 / avgB 10.75 vs baseline 6.44 / 10.69 (within noise). ✓

## Sweep — n on the coltol=8 curve

Canonical + `-azimbind -azfalloff n`, coltol=8, GPU (single-seed 30k exploration first — avgBound equilibrates in
the 2nd-half window; velFitX noisier but the cap/no-cap *trend* is the question; 3-seed 60k confirmation only if a
cap regime appears). Raw: `RUN_LOGS/2026-07-09_azimuthal_falloff_sweep.txt`.

**Smoke (d4000 s0, 10k) — the throttle turns on with n:**

| n | avgBsteady | velFitX | vs baseline (10.69 / 6.44) |
|---|---|---|---|
| 0 | 10.75 | 6.73 | recovers baseline ✓ |
| 4 | 10.29 | 6.24 | −4 % / −3 % (mild) |
| 16 | 7.73 | 4.36 | −28 % / −32 % (≈ hard Δ=45°) |

**A. Density-response at steep n (the crux — does avgBound flatten? NO).** avgBsteady / velFitX (single-seed 30k):

| density | baseline avgB | n=16 avgB (ratio) | n=32 avgB (ratio) | | n=16 velFitX | n=32 velFitX |
|---:|---:|---:|---:|---|---:|---:|
| 1000 | 2.905 | 1.94 (0.67) | 1.50 (0.52) | | 0.77 | 1.19 |
| 2000 | 5.424 | 3.39 (0.63) | 3.55 (0.65) | | 2.19 | 1.84 |
| 4000 | 10.686 | 7.89 (0.74) | 7.12 (0.67) | | 4.44 | 3.54 |
| 6000 | 15.802 | 12.32 (0.78) | 10.11 (0.64) | | 5.42 | 4.14 |
| 8000 | 20.206 | 15.10 (0.75) | 13.07 (0.65) | | 6.14 | 4.72 |

avgBound **climbs monotonically** at both n (1.9→15.1, 1.5→13.1); the throttle ratio is **roughly constant**
(~0.7 at n16, ~0.65 at n32) — it does NOT fall toward a flat avgBound. velFitX likewise keeps climbing. **No
cap, no saturation.** (Contrast the Inc-2 hard gate, whose ratio *rose* 0.80→0.91 with density — MAX-combine
removed that washout, so the ratio is now flat, but flat-ratio still means avgB = baseline × const → climbs.)

**B. n-response at d4000 (avgBsteady, single-seed 30k) — monotone scale-down, diminishing, never a low cap:**

| n | 0 | 1 | 2 | 4 | 8 | 16 | 32 | 64 |
|---|---|---|---|---|---|---|---|---|
| avgB | 10.93 | 10.64 | 9.75 | 10.49 | 8.42 | 7.89 | 7.12 | 6.06 |
| velFitX | 6.72 | 6.24 | 5.65 | 6.10 | 5.12 | 4.44 | 3.54 | 2.73 |

n=0 recovers baseline (10.93 / 6.72 ≈ 10.69 / 6.44 ✓). Raising n reduces avgBound smoothly but with strong
diminishing returns — even n=64 only reaches 0.55× baseline, and it is a *scale-down at d4000*, not a
density-flat cap (per Part A).

**C. GPU-vs-CPU cross-check at d4000 n=16.** GPU avgB 7.89 / velFitX 4.44; CPU avgB 7.44 / velFitX 3.92 —
within the chaotic 30k spread, **no basin flip** ⇒ the graded-falloff GPU number is trustworthy through the
bind-path change. ✓

## Verdict

**A graded orientational falloff does NOT saturate the velocity–density curve at any steepness.** It scales
avgBound and velFitX down by a roughly **density-independent** factor (the MAX-combine improvement over the
Inc-2 hard gate, which had removed the washout) — but both keep climbing with density. The orientational
constraint, hard or graded, throttles **per-head binding propensity**, not **co-occupancy**: with MAX-combine
over a multi-turn axial reach window the best azimuth is essentially always reachable, so binding depends on
head orientation (density-independent), never on segment packing. **Orientation is closed as the saturation
lever.** The physically-right lever is the deferred **steric co-occupancy exclusion** (head footprint — no two
bound heads within a few nm axially), which limits packing directly — matching the concurrent
`DETACHMENT_CEILING_CODEREAD` conclusion (overdamped force-summation ∝ N with no per-head displacement clutch;
fix = a force→displacement stroke-limit clutch and/or a steric co-occupancy cap, not a kinetic/orientational
retune).

---

## Commands
```
scripts/run_gliding.sh -azfalloff <n> -gpu -full -grid -coltol 8 -density <D> -seed <s> 60000   # graded affinity
scripts/run_gliding.sh -azfalloff 0  ...   # n=0 baseline recovery (aggregate-equal; draw added)
scripts/run_azimuthal_falloff_sweep.sh     # the n-sweep + density-response + GPU/CPU cross-check
```
Default OFF / byte-identical; gliding-only `bindNearestFalloff` (no other harness/predicate touched);
`BoA-v1ref` untouched; Inc-2 scan/interp/handedness reused verbatim; handedness LEFT-handed (−166.5°/mon).
