# Finite kOn: does restoring a real binding rate cross into release-limited saturation? — the decisive sweep

**Date:** 2026-07-10 · **Branch:** dt-convergence-study · **Runner:** GPU (springs = transcendental-free default) ·
`BoA-v1ref` untouched · **Diagnostic only — no default change** (`-glidekon` flag-gated, default-off byte-identical).
Closes the one lever never turned: `BINDING_RATE_SURVEY.md` showed the canonical gliding bind is **deterministic**
(`bindNearest:394`, no draw ⇒ effective kOn = ∞ ⇒ permanent supply-limitation), which is why every eligibility gate
(orientation `AZIMUTHAL_FALLOFF_INCREMENT3.md`, coltol `COLTOL_REGIME_SWEEP.md`, brake-hold `RECRUIT_SHED_BALANCE.md`)
only scaled. Here we restore the historical finite-kOn binder and test the timescale crossover directly.

> **VERDICT — NO CROSSOVER. A real finite binding rate does NOT flatten the velocity–density curve at any engaged
> kOn — it only SCALES (and, below engagement, collapses).** The decisive ratio velFitX(d8000)/velFitX(d2000) stays
> **≈ 1.85–2.16 at every engaged kOn** (it never falls toward 1 while the filament is bound); it reaches ~1.10 only
> at kOn = 1.5e4, where velocity has **collapsed to ~0.7 µm/s with avgBound ~0.1** — a both-near-zero trivial flatten,
> NOT the engaged ~d/τ_on (~6–8 µm/s) plateau the "win" requires. velFitX and avgBound **fall together** as kOn drops
> (the shrinking-pool fingerprint, identical to the coltol sweep); dwell stays ~0.6 ms at every kOn (kOn touches
> capture, not release). The refill clock **can** be turned (kOn genuinely starves binding — avgBound 4.75 → 0.11 at
> d2000), but turning it produces a shrinking engaged pool that collapses to unbound, **never a density-independent
> saturated velocity.** **The timescale hypothesis is refuted on the clean, rate-side test. The missing ceiling is
> structural — the bound-head POPULATION (steric co-occupancy / a displacement clutch), not the binding rate.**

---

## STEP 1 — the re-route (small, byte-identical at default) + faithfulness

**`-glidekon <k>`** (`GlidingHarness`): when kOn > 0, routes the canonical (non-canonical, non-azimuthal) bind branch
from the deterministic `bindNearest` to **`bindRateGated`** — `BindingDetectionSystem.bindRate` VERBATIM (path-average
chord through the tight myoColTol capture sphere, `pBind = 1 − exp(−kOn·Δl·dt)`, kOn = `kinParams[14]`, rate-∝ segment
pick, "BRAT" wang-hash draw) **plus the two eligibility guards the deterministic baseline already applies** (`-nobind`
kinParams[19]; the ADP·Pi strong-bind gate kinParams[20], welded on for canonical Lymn–Taylor). Preserving the ADP·Pi
gate is what makes the high-kOn check meaningful — otherwise a high-kOn run would bind a strictly larger set than the
gated baseline. Formulation A (headPrev ≡ head ⇒ point chord) is exact here: at dt = 1e-5 the head moves ≪ myoColTol
per step, so the swept and point chords coincide (mot.head passed as both args; V2OneXHarness proves same-buffer-twice
lowers on the PTX backend). **Default off ⇒ `bindNearest` ⇒ byte-identical by construction** (only an `else if
(GLIDE_KON)` branch added; no existing default-path line touched). Both runners + the device TaskGraph; the "bind"
task already runs at localWork = 64 (the RNG group). `bindRate` + its V2OneXHarness caller stay byte-untouched.

**High-kOn faithfulness (the re-route reproduces the deterministic baseline), d4000, 10k, GPU:**

| arm | velFitX | avgBsteady | instSteady | dwell |
|---|---:|---:|---:|---:|
| baseline (no flag, `bindNearest`) | 6.907 | 10.49 | 8.57 | ~0.63 ms |
| **`-glidekon 1e8`** (pBind → 1) | 6.074 | **10.61** | 8.22 | **0.628 ms** |

avgBsteady 10.61 ≈ 10.49 (1 %), dwell 0.628 ms (release untouched), velFitX within the single-seed chaotic spread
(the added RNG draw decorrelates but is aggregate-equal — the same status `-azfalloff n=0` established). GPU lowered
cleanly (no Graph-resize). **The re-route is faithful.** ✓

## STEP 2 — centering the ladder analytically

Refill-time ≈ τ_on when the per-head bind rate `kOn·Δl ≈ 1/τ_on`. With τ_on ≈ 0.6 ms (⇒ 1/τ_on ≈ 1667 /s, confirmed:
measured dwell 0.58–0.64 ms) and the capture chord `Δl ≈ 2·myoColTol = 0.012 µm`:

```
kOn* ≈ (1/τ_on) / Δl ≈ 1667 / 0.012 ≈ 1.4e5 µm⁻¹ s⁻¹     (occupancy ≈ ½ estimate)
```

Ladder centered on it, {10×, 1×, 0.1×} = **{1.5e6, 1.5e5, 1.5e4}** + the deterministic baseline (kOn off) as the
anchor. *In hindsight the estimate ran ~5× low* (occupancy-½ landed nearer kOn ≈ 1.5e6, where measured avgBound is
~half the baseline) — but the 100×-spanning ladder **bracketed the true crossover region**, so the test is decisive
regardless.

## STEP 3 — the minimal decisive sweep

`-gpu -full -grid -matbox 50 -glidekon <k> -density {2000,8000} -seed 0 15000` (chamber keeps d8000 coverage-clean per
`COLTOL_REGIME_SWEEP.md`; d8000 chosen for the strongest baseline climb — ~1.85× vs ~1.9× for d4000). Single-seed,
15k. Raw: `RUN_LOGS/2026-07-10_glidekon_crossover.txt`.

| kOn (µm⁻¹s⁻¹) | d2000 velFitX | d2000 avgB | d8000 velFitX | d8000 avgB | **ratio d8/d2** | avgB≥1 (engaged)? | dwell (ms) |
|---:|---:|---:|---:|---:|:--:|:--:|---:|
| 1.5e4 | 0.680 | 0.11 | 0.745 | 0.26 | **1.10** | ✗ (unbound) | 0.62–0.74 |
| 1.5e5 | 1.261 | 0.84 | 2.718 | 2.61 | **2.16** | marginal | 0.65–0.67 |
| 1.5e6 | 3.759 | 2.83 | 7.827 | 10.38 | **2.08** | ✓ | 0.62–0.62 |
| **∞ (det. baseline)** | 4.615 | 4.75 | 8.521 | 18.08 | **1.85** | ✓ | 0.58–0.61 |

### The decisive read — the ratio does NOT collapse to 1 while engaged

- **Every engaged kOn climbs ~2×.** Baseline 1.85, kOn 1.5e6 → 2.08, kOn 1.5e5 → 2.16. velFitX keeps its climbing
  shape at each kOn (d2000 → d8000: 4.62 → 8.52, 3.76 → 7.83, 1.26 → 2.72). **No flattening.** The ratio actually
  *rises* slightly as kOn drops through the engaged range (2.16 > 1.85), then only falls at collapse.
- **The 1.10 at kOn = 1.5e4 is the collapse, not the win.** avgBound 0.11 / 0.26 (essentially unbound), velFitX ~0.7,
  net-directed motion netX ~ −0.4 to −0.6 µm (the instSteady ~6.8 there is 3D Brownian skating, not directed glide).
  Both densities sit near zero ⇒ ratio ≈ 1 *trivially*. This is over-restriction (outcome #2), not the engaged
  density-independent plateau at ≈ d/τ_on (outcome #1).
- **The occupancy-½ region is on the ladder and shows no flatten.** kOn 1.5e6 has avgBound ≈ ½ the baseline (2.83 vs
  4.75 at d2000) — the refill ≈ τ_on regime — and still climbs 2.08×. Higher kOn only returns toward the deterministic
  1.85; lower kOn collapses. No engaged crossover exists anywhere on a 100×-spanning ladder.

### The mechanism — kOn is a pool lever, same as coltol (from the rate side)

velFitX and avgBound **fall together** as kOn drops (d2000: velFitX 4.62 → 3.76 → 1.26 → 0.68 while avgB 4.75 → 2.83
→ 0.84 → 0.11) — the shrinking-pool fingerprint, the exact opposite of a ceiling (flat velFitX / climbing avgBound).
Per-bound efficiency (velFitX/avgB) *rises* as kOn thins the pool (d2000 engaged: 0.97 → 1.33 → 1.50; d8000: 0.47 →
0.75 → 1.04) — fewer co-bound heads ⇒ less co-bound tug-of-war ⇒ each head more efficient — but the pool shrinks
faster than efficiency rises, so **net velFitX falls with the head count.** This is the identical structure
`COLTOL_REGIME_SWEEP.md` found: kOn shrinks the *bound pool*, it does not slow *refill relative to release* into a
saturated plateau. dwell = 0.58–0.74 ms at every kOn confirms kOn touches only capture — the release clock is
untouched, so there is no relative-timescale flip.

### Reading against the three predicted outcomes

1. **Crossover to saturation (the win)** — ratio → ~1 AND flat value ≈ d/τ_on AND engaged. **DID NOT HAPPEN.** No
   point is flat-at-engaged; the engaged ratio stays ~1.85–2.16.
2. **Over-restriction (collapse)** — kOn so low binding starves and velFitX collapses toward 0. **This is kOn = 1.5e4**
   (avgBound ~0.1, velFitX ~0.7, ratio ~1.1 via both-near-zero).
3. **kOn just scales (no crossover)** — binding still beats release wherever the filament is engaged ⇒ still
   supply-limited, kOn just scales the pool. **THIS ONE, across the whole engaged range.**

## GPU-number trust

All finite-kOn arms share hot-kernel structure (same `bindRateGated`, kOn a scalar) ⇒ the ratio *within* the
finite-kOn arms carries no basin-flip hazard; the deterministic baseline is the one structurally-different anchor.
The core finding — a **5.6× monotonic avgBound suppression** (4.75 → 0.84 → 0.11 at d2000) reproduced at both
densities and tracking velFitX — is a kinetic-starvation magnitude no last-bit basin flip can fake, and the
"engaged ratio ≈ 2, never 1" trend is reproduced across three structure-sharing kOn points plus the baseline. Per the
task scoping a CPU arbiter is run only *if the crossover appears*; it did not, and the negative is robust across four
points. (The high-kOn faithfulness check already cross-validated the re-route against the deterministic baseline.)

## Plain statement

**Does restoring a real binding rate flatten the velocity–density curve (crossover, finite kOn saturates) or only
scale (structural)?** **It only scales.** A finite kOn genuinely throttles binding — the refill clock is real and
turnable (avgBound falls 5.6× from the deterministic baseline to kOn = 1.5e5) — but at no engaged kOn does the
velFitX–density ratio fall toward 1: it stays ~1.85–2.16, the curve keeps its ~2× climbing shape, and velFitX falls
together with avgBound (the shrinking-pool fingerprint). The ratio reaches ~1.1 only where binding has collapsed to
unbound (kOn = 1.5e4, avgBound ~0.1, velFitX ~0.7) — a trivial both-near-zero flatten, not the engaged ≈ d/τ_on
plateau. **The timescale/regime-crossover hypothesis is refuted on the clean rate-side test.** Every lever that
changes *which/how-many* heads bind — orientation, coltol, brake-hold, and now the binding *rate* itself — only
scales the pool; none crosses into release-limited saturation.

## Outcome → next step

**Ratio never collapses to ~1 while engaged (no crossover even with a real rate)** ⇒ **the ceiling is structural, now
earned rather than assumed.** The controlling lever is the bound-head **POPULATION** — a **steric co-occupancy
exclusion** (head footprint: no two bound heads within a few nm axially) and/or a **force→displacement stroke-limit
clutch** — matching `DETACHMENT_CEILING_CODEREAD.md` (overdamped force-summation ∝ N, no per-head displacement clutch)
and `AZIMUTHAL_FALLOFF_INCREMENT3.md`. **Next step: build the steric co-occupancy cap / displacement clutch** and test
whether *it* installs the density-independent ceiling that no kinetic/geometric/rate lever could.

## Scope / provenance

- New: `BindingDetectionSystem.bindRateGated` (gliding-only, = `bindRate` + the baseline's `-nobind`/ADP·Pi guards);
  `GlidingHarness` `-glidekon <k>` flag routing the canonical CPU + GPU bind branch; `scripts/run_glidekon_crossover.sh`.
- Default-off ⇒ byte-identical (verified: default path untouched; baseline d4000 velFitX 6.907 reproduces the known
  AZFALLOFF/COLTOL baseline). High-kOn faithfulness verified (avgBound 10.61 ≈ 10.49). `bindRate` + V2OneXHarness
  byte-untouched. `BoA-v1ref` untouched.
- Ladder centered analytically (kOn* ≈ 1.4e5); estimate ran ~5× low but the 100×-span bracketed the crossover region.
  Single-seed 15k (trend, not precision — the question is the ratio's shape, which is unambiguous).

## Commands
```
scripts/run_gliding.sh -gpu -full -grid -matbox 50 -glidekon <k> -density <D> -seed 0 15000   # a sweep point
scripts/run_gliding.sh -gpu -full -grid -matbox 50 -glidekon 1e8 -density 4000 -seed 0 10000   # high-kOn faithfulness (≈ baseline)
scripts/run_glidekon_crossover.sh    # the full 2-density × 4-kOn decisive sweep
```
`-glidekon` default-off ⇒ byte-identical; gliding-only; `BoA-v1ref` untouched.
</content>
