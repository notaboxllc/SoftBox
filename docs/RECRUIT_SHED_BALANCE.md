# The recruit-vs-shed balance — does slowing the release of spent (brake) heads create the missing velocity ceiling?

**Date:** 2026-07-10 · **Branch:** dt-convergence-study · **Runner:** GPU (springs = transcendental-free ⇒
GPU-trustworthy) + 1 CPU basin-arbiter point at the decisive d4000 s=0.03. `BoA-v1ref` untouched. z-unconfined
(as the prior azimuthal/density sweeps). Diagnostic only — **no default rate change.**

> **VERDICT — NO. The missing ceiling is NOT a recruit/shed rate-balance the shed side controls.** Slowing the
> shedding of back-strained brake heads works exactly as intended on the mechanism — brakes are retained (dwell
> 0.63 → 5.7 ms, detach 1592 → 175/s, avgBound 5 → 50) **and they measurably bite** (per-bound efficiency
> *collapses* 0.74 → 0.087). But velFitX **does not cap**: at d2000 it stays flat (~4) because the efficiency
> collapse (~8.5×) coincidentally cancels the recruitment growth (~10×); at **coverage-clean d4000 it RISES +56 %**
> (6.23 → 9.70) because recruitment (~7.5×) *outruns* the efficiency collapse (~5×). Slowing shed **steepens** the
> velFitX-vs-density curve rather than flattening it — the opposite of a ceiling — and even a **33×-below-skeletal**
> shed rate (s=0.03) fails to install one. Resistance genuinely **aggregates** (per-bound collapses), it just
> cannot **win the net** because co-bound recruitment is **unbounded**: retention adds heads faster than each head
> loses efficiency. **The controlling lever is the bound-head POPULATION (steric co-occupancy / a displacement
> clutch), not the shed rate.** Part B confirms the mirror: recruitment reduction (`-azfalloff 16`) also only
> scales, never caps. Both the recruit knob and the shed knob fail ⇒ the ceiling is **structural**, confirming
> `DETACHMENT_CEILING_CODEREAD.md` and `AZIMUTHAL_FALLOFF_INCREMENT3.md`.

## The knob — `-brakehold <s>` (flag-gated, default 1.0 = skeletal ⇒ byte-identical)

The signed catch-slip release rate on the canonical Lymn–Taylor path
(`NucleotideCycleSystem.java:449-452`, `cycleLymnTaylor`):

```
g(F) = αCatch·e^(−F·xCatch/kT) + αSlip·e^(+F·xSlip/kT)      // F = τ-averaged forceDotFil, SIGNED
rate(ADP→NONE) = onADP · g(F)                               // then NONE→ATP detaches the rigor head
```

By the **physics** (not the α name): a post-stroke, back-strained, *resisting* (brake) head has **F < 0**, so the
**catch term** `αCatch·e^(−F·xCatch/kT) = αCatch·e^(+|F|·xCatch/kT)` **explodes** — that is the term that sheds
resisting heads (fast release the harder they resist). `-brakehold <s>` scales **αCatch** (`kinParams[1]`, skeletal
0.92) by `s`: **s < 1 slows brake shedding** (brakes persist longer). Skeletal Guo–Guilford anchor at **s = 1.0**;
the edit is guarded `if (BRAKE_HOLD != 1.0)` ⇒ s = 1.0 is **byte-identical canonical** by construction (the guard
skips the only write) — **verified**: no-flag vs `-brakehold 1.0` (d2000 s0 5k) are bit-identical GRID_ROW (velFitX
3.724, avgB 5.392, every field). Consumed by the live release (`cycleLymnTaylor`) + the non-LT `catchSlipRelease*`.

**Framing (diagnostic, not a retune-to-fit):** αCatch is skeletal-anchored (Guo–Guilford, a *measured*
single-molecule detachment-vs-load quantity). The sweep anchors at s = 1.0 and reports how far any capping value
sits from skeletal. **No capping value exists at any tested slip** (down to s = 0.03 = 33× slower than skeletal) —
so there is nothing to retune; the finding is that the shed rate is not the lever, at any multiple.

## PART A — slow the brake shed: `-brakehold s` × density (coltol = 8, single-seed 30k)

velFitX **and** avgBound reported **separately** (the recurring trap); per-bound = velFitX/avgBound; dwell/detach
from `STATS_STEADY_ROW`; `fullMat` from `COV_ROW` (measurement-window edge margins). Raw:
`RUN_LOGS/2026-07-09_recruit_shed_sweep.txt` + `RUN_LOGS/2026-07-10_recruit_shed_cov.txt`.

| density | s | **velFitX** | **avgBsteady** | per-bound | dwell (ms) | detach (/s) | fullMat |
|---:|---:|---:|---:|---:|---:|---:|:--:|
| **2000** | 1.0 (skel) | 3.88 | 5.24 | 0.740 | 0.63 | 1592 | YES |
|  | 0.3 | 4.79 | 12.20 | 0.393 | 1.40 | 714 | YES |
|  | 0.1 | 3.95 | 28.11 | 0.141 | 3.22 | 310 | YES |
|  | 0.03 | 4.37 | 49.95 | 0.087 | 5.71 | 175 | YES |
| **4000** | 1.0 (skel) | 6.23 | 10.65 | 0.585 | 0.59 | 1692 | YES |
|  | 0.3 | 7.19 | 24.44 | 0.294 | 1.41 | 708 | YES |
|  | 0.1 | 8.92 | 45.15 | 0.198 | 2.62 | 381 | YES |
|  | **0.03** | **9.70** | **80.04** | 0.121 | 4.20 | 238 | **YES** |
| **8000** | 1.0 (skel) | 9.55 | 20.88 | 0.457 | 0.58 | 1718 | YES |
|  | 0.3 | (26.99) | (45.52) | — | 1.13 | 884 | ~VIOLATED |
|  | 0.1 | (26.08) | (42.41) | — | 2.07 | 483 | **VIOLATED** |
|  | 0.03 | (22.56) | (56.74) | — | 3.23 | 310 | **VIOLATED** |

**d8000 slow-shed points are EXCLUDED (coverage artifact).** At s ≤ 0.1 the filament wandered ~2 µm off the bed in
**y** (`marginY = −0.97 / −0.63`, `fullMat = VIOLATED`); the apparent 22–27 µm/s "explosion" is edge-corrupted
motion, not glide (`avgBsteady` also drops below whole-run `avgB`, `meanReach` collapses 25 → 10 as the filament
leaves the populated strip). The baseline doc already flagged d8000 as coverage-marginal at just 9.4 µm/s; at these
speeds it is firmly violated. d2000 and d4000 are `fullMat = YES` at **every** slip including s = 0.03.

### Does the velFitX-vs-density curve flatten? **NO — it STEEPENS.**

Coverage-clean densities only (d2000, d4000):

| slip | velFitX @ d2000 | velFitX @ d4000 | curve slope (d2000→d4000) |
|---:|---:|---:|---:|
| s = 1.0 (skeletal) | 3.88 | 6.23 | +61 % |
| s = 0.03 (33× slower shed) | 4.37 | 9.70 | **+122 %** |

Slowing shed makes the velocity–density curve **steeper**, not flatter. A genuine ceiling would flatten it. At
fixed d4000, slowing shed **raises** velFitX (6.23 → 9.70) — the retained brakes do not install a cap; they add net
forward motion. **velFitX does not approach the d/τ_on ≈ 6–8 µm/s anchor as brakes persist — it climbs *past* it**
(baseline d4000 is already 6.2, in-band; slow-shed pushes it to 9.7, above-band, away from the anchor).

### The fixed-density fingerprint check (velFitX↓/avgBound↑ = brakes biting)

- **d2000:** avgBound climbs 5 → 50 (10×) while velFitX stays flat (~4). This *is* the local "flat-velFitX /
  climbing-avgBound" fingerprint — **but** the balance is coincidental: efficiency collapse (per-bound 0.74 →
  0.087 = 8.5×) ≈ recruitment growth (10×), so they cancel to flat. It is not a hard cap.
- **d4000:** avgBound climbs 10 → 80 (7.5×) while velFitX **RISES** 6.23 → 9.70. Fingerprint **fails** — recruitment
  (7.5×) outruns the efficiency collapse (per-bound 0.585 → 0.121 = 4.8×), so the net goes up.

The "flat" at d2000 is therefore a **density-specific coincidence** (the balance point where recruitment ≈ efficiency
collapse), not a structural ceiling — which is exactly why it evaporates into a *rise* at d4000. A real ceiling
would hold the fingerprint at every density **and** flatten the density curve; neither holds.

### Resistance DOES aggregate — but recruitment is unbounded (the mechanistic core)

This refines the `DETACHMENT_CEILING_CODEREAD` picture. That read argued the correct catch (slip-backward)
*prevents* brakes persisting, so resistance "can't aggregate." Here we **force** brakes to persist and measure the
result directly: **per-bound efficiency collapses 5–8×** (d2000 0.74 → 0.087; d4000 0.585 → 0.121) — so retained
brakes **do** aggregate resistance; each head converts far less of its motion into net directed glide, exactly the
co-bound tug-of-war deepening. **The aggregation is real.** What it *cannot* do is cap the net, because the number
of co-bound heads is **unbounded** — the same slowing that retains a brake retains a driver and piles on more
co-bound heads (avgBound 5 → 50), and nothing limits how many heads act on a segment (no steric co-occupancy cap,
no per-head displacement clutch). So aggregate resistance grows, but the *population* grows at least as fast, and
the net never caps. **The lever is the bound-head population (a steric/co-occupancy or displacement-clutch cap),
not the shed rate.**

## PART B — CONTROL: recruitment reduction (`-azfalloff 16`) — should NOT cap (and does not)

Reusing the falloff recruitment knob at n = 16 across the same 3 densities (single-seed 30k):

| density | velFitX | avgBsteady | vs baseline (velFitX / avgB) | dwell (ms) | detach (/s) |
|---:|---:|---:|---:|---:|---:|
| 2000 | 2.19 | 3.39 | 0.56× / 0.65× | 0.64 | 1566 |
| 4000 | 4.44 | 7.89 | 0.71× / 0.74× | 0.58 | 1720 |
| 8000 | 6.14 | 15.10 | 0.64× / 0.72× | 0.58 | 1732 |

Recruitment reduction **scales both velFitX and avgBound down together** by a roughly density-independent factor and
**both keep climbing with density** (velFitX 2.19 → 4.44 → 6.14). **No cap.** Kinetics are **unchanged** vs baseline
(dwell 0.58–0.64 ms, detach 1566–1732/s — the falloff touches only binding, not shedding), the clean control: the
*level* (recruit side) is not the lever either. This reproduces `AZIMUTHAL_FALLOFF_INCREMENT3.md` in the same
batch. The distinction the prompt drew — recruit-reduction scales (drops both), shed-slowing retains (raises
avgBound) — is confirmed; **neither installs a ceiling.**

## CPU basin-arbiter (the decisive point: d4000 s = 0.03, coverage-clean, avgBound ≈ 80)

Kinetics change on the bistability-sensitive path, so the steepest-slip / highest coverage-clean point gets a CPU
cross-check (the GPU-number-trust rule; a basin flip would roughly halve/double both channels). Raw:
`RUN_LOGS/2026-07-10_recruit_shed_cpuarbiter.txt`.

| | velFitX | avgBsteady | fullMat |
|---|---:|---:|:--:|
| GPU d4000 s=0.03 (30k) | 9.703 | 80.040 | YES |
| **CPU d4000 s=0.03 (20k arbiter)** | **9.063** | **78.505** | **YES** |

**Same HIGH basin — confirmed.** velFitX matches to ~7 % (9.063 vs 9.703, well inside the chaotic single-seed
spread), avgBound to ~2 % (78.5 vs 80.0). A flipped LOW basin would roughly **halve** both — the opposite of what
is seen. The slow-shed rise is **real**, not a GPU-execution artifact, and the avgBound ≈ 80 co-bound regime is
**stable on the deterministic CPU runner** (no blow-up — `-xbimplicit2` alone holds even here, consistent with the
`-ktotcensus` sub-threshold-in-gliding finding). CPU also `fullMat = YES` (55 min, ~6 steps/s).

## Verdict

**Is the missing ceiling a recruit/shed rate-balance the shed side controls?** **No.** The shed knob does everything
mechanistically expected — retains brakes (dwell ↑, detach ↓, avgBound ↑) that **measurably bite** (per-bound
efficiency collapses 5–8×) — yet velFitX **does not cap**: flat at d2000 (a coincidental recruitment ≈
efficiency-collapse cancellation), **rising +56 % at coverage-clean d4000**, and the velFitX-vs-density curve
**steepens** rather than flattening. Even a 33×-below-skeletal shed rate installs no ceiling, so there is no capping
value to report a distance-from-skeletal for — **the shed rate is not the lever at any multiple.** Resistance
genuinely aggregates (the per-bound collapse), but co-bound recruitment is **unbounded**, so retention adds heads
faster than each loses efficiency and the net never caps. **The controlling lever is the bound-head population — a
steric co-occupancy / head-footprint cap, or a per-head force→displacement clutch — not a kinetic shed-rate
retune.** Part B (recruitment reduction also only scales, never caps) closes the mirror: both the recruit side and
the shed side fail, so the ceiling is **structural**, exactly as `DETACHMENT_CEILING_CODEREAD.md` (overdamped
force-summation, no displacement clutch) and `AZIMUTHAL_FALLOFF_INCREMENT3.md` (orientation/recruitment scales,
doesn't cap) concluded. **Do not chase a shed-rate retune; build the co-occupancy / displacement clutch.**

## Commands
```
scripts/run_gliding.sh -brakehold <s> -gpu -full -grid -coltol 8 -density <D> -seed 0 30000   # slow the brake shed (s<1); s=1.0 ⇒ byte-identical
scripts/run_recruit_shed_sweep.sh    # PART A grid (s × density) + PART B recruitment control
scripts/run_recruit_shed_cov.sh      # high-density coverage re-run (fullMat classification)
```
Default OFF / byte-identical (guard `BRAKE_HOLD != 1.0`); gliding-only; `BoA-v1ref` untouched; z-unconfined.
```
