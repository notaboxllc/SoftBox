# Phase-2 — speed levers: what raises the f̂-motor glide from ~3 toward skeletal 5–8 µm/s?

**Date:** 2026-07-02. Measurement + two flag-gated param sweeps (`-neckangle`, `-ratescale`, both default
byte-identical). **No default changed; no release change; `BoA-v1ref` untouched.** GPU device-resident
`-full` bed (13.37×2 µm, 26.74 µm²), 150k steps (1.5 s), dt 1e-5. The mat **is seedable** (`-seed n` →
`SEED = 0x6111D + 7919·n` drives the motor-placement RNG) ⇒ the spread below is over 3 real distinct mat
draws (seeds 0/1/2).

## Measurement standard (applied to every number)
Per config: **net `v_axial`** (LS-centroid drift along f̂, on-bed ≥1 s window, PROPER_SPEED_ANALYSIS — the
`LONG_ROW` estimator), **`avgBound`**, and **per-bound drift = v_axial/avgBound**, each as **mean ± population
SD over 3 mat draws**. Coverage-violated runs (filament reached a bed edge; `fullMat=VIOLATED`) are **excluded
from the velocity mean** (2 of 30 runs: d1500 s2, d2000 s1, ang80 s0, ang70×2 s1, ang70×4 s1 — flagged in the
raw logs). `axialFrac ≈ 0.99–1.00` on every run (fully directed −x glide). No single-run number is a result.

Raw logs: `RUN_LOGS/2026-07-01_step1_density.txt`, `RUN_LOGS/2026-07-01_step23_angle_rate.txt`.

## TL;DR — none of the three levers gives a defensible path to 5–8 µm/s; the net glide is tug-of-war-pinned near ~3

| lever | net v_axial gain | mechanism | faithful? | verdict |
|---|---|---|---|---|
| **density** (STEP 1) | plateau ~3.3 @ d1500; d1000→peak only **+7%** | avgBound↑ but per-bound drift↓ (tug-of-war) ⇒ product plateaus, turns over at d2000 | measurement choice | **not a lever** |
| **neck angle / step** (STEP 2) | **0%** (flat 60→70→80°) | step size does not move net transport at this operating point | 70° defensible, 80° probe — but both **null** | **not a lever** |
| **cycle rate / kinetics** (STEP 3) | **+6% (×2), +8% (×4)** | per-bound drift ↑ +33/+76% (V₀∝detach rate) **but duty collapses** 14.9→12.0→8.8 | ×2 plausibly skeletal, ×4 probe | **real per-head gain, eaten by duty collapse** |

The honest empirical invariant: **net ≈ avgBound × per-bound-drift ≈ 3.0–3.4 µm/s across all three levers** —
each knob trades duty against per-head efficiency and the product barely moves. There is **no defensible
single-lever route to 5–8**; even an optimistic stack (d1500 × rate2) is ~3.5. (Note the *honest* v1 net glide
is ~4.6 µm/s, not 8 — the "8.33" is the inflated `longWindowSpeedXY`, per PROPER_SPEED_ANALYSIS / the 4b-iv
dossier; even ~4.6 is not reached by these levers.)

---

## STEP 1 — density / duty (no code change) — sets the operating density

| density | net v_axial (µm/s) | avgBound | per-bound drift | n(clean) |
|--:|--:|--:|--:|--:|
| 250 | 1.89 ± 0.20 | 6.4 | 0.295 | 3 |
| 500 | 2.37 ± 0.08 | 9.95 | 0.238 | 3 |
| 750 | 2.82 ± 0.13 | 12.95 | 0.217 | 3 |
| 1000 | 3.07 ± 0.12 | 14.9 | 0.206 | 3 |
| **1500** | **3.28 ± 0.01** | 17.3 | 0.191 | 2 |
| 2000 | 3.08 ± 0.14 | 19.3 | 0.159 | 2 |

1. **Is d1000 past the peak?** No — net v_axial rises to a **broad, shallow peak at d1500 (~3.28)** then
   **turns over at d2000 (~3.08)**. d1000 (3.07) sits ~7% below the peak. So measuring at d1500 rather than
   d1000 buys only **+0.2 µm/s (+7%)** — a little free speed, but the peak (~3.3) is far short of 5–8.
2. **Tug-of-war confirmed:** per-bound drift falls **monotonically** 0.295 → 0.159 as density rises — every
   extra bound head is less efficient, so net rises sublinearly with avgBound and eventually reverses. This is
   the non-monotonic (peak-then-drop) shape the earlier axial-lock sweep flagged, now sharpened (the peak here
   is d1500, broader/higher than the earlier ~d500–750 read — same qualitative story).

**STEP 2/3 operating density = d1000** (the promoted reference — reproduces the −3.02 baseline exactly; near
the plateau peak; less tug-of-war-saturated than d1500 so per-head levers read cleanly; d1000 seeds 0/1/2 ARE
the 60° baseline row).

## STEP 2 — step size via neck angle (`-neckangle`, flag-gated) — also settles the 60/70 confound

| neck angle | net v_axial (µm/s) | avgBound | per-bound drift | faithfulness |
|--:|--:|--:|--:|---|
| 60° (default) | 3.07 ± 0.12 | 14.9 | 0.206 | legacy default, defensible |
| 70° | 3.08 ± 0.10 | 14.9 | 0.206 | **skeletal structural value — defensible** |
| 80° | 3.07 ± 0.08 | 14.9 | 0.207 | **sensitivity probe — not adoptable** |

**The step-size lever is FLAT.** 60→70→80° moves net v_axial by ~0 (all 3.07–3.08, well within spread),
leaves avgBound (~14.9) and per-bound drift (0.206) **unchanged**. Consistent with the standing result that
the swing's effective lever is HEAD_LEN and the J1/neck converter swing contributes ≈0 to the tip
(`stroke-effective-lever-is-headlen`; the 2026-06-16 "head-angle rest-angle θ is a NON-LEVER for ensemble
transport" finding). The nominal stroke angle does not set the per-head displacement here.

**Settles the release-reconcile confound:** the BoA(70°)/v2(60°) neck-angle difference contributes **~0** to the
−3.96/−3.0 gap. So the gap is neither release (`PHASE2_RELEASE_RECONCILE_FINDINGS.md`) **nor** neck angle — it
is the CPU(BoA)/GPU(v2) runner difference + the accepted 4b-iv ~0.87× parallel-scheme residual.
**Faithfulness:** 70° is structurally defensible (skeletal working-stroke value) but adopting it over 60° is
**speed-neutral**; 80° is an over-rotated probe and also null — no reason to move the default.

## STEP 3 — cycle rate / kinetics (`-ratescale`, scales catch-slip kOff + all nucleotide rates) at d1000, 70°

| rate ×scale | net v_axial (µm/s) | avgBound | per-bound drift | faithfulness |
|--:|--:|--:|--:|---|
| ×1 (= 70° above) | 3.08 ± 0.10 | 14.9 | 0.206 | baseline (kOff 100/s) |
| ×2 | 3.27 ± 0.05 | 12.0 | 0.274 | **plausibly in fast-skeletal range** |
| ×4 | 3.34 ± 0.10 | 8.8 | 0.363 | **high edge — probe** |

**The faithful lever behaves exactly as V₀ = step × detach-rate predicts at the SINGLE-HEAD level, but the
ENSEMBLE cannot cash it:**
- **per-bound drift rises +33% (×2) and +76% (×4)** — each bound head is genuinely faster (more productive
  turnover per unit bound time). The single-molecule ceiling **is** raised by kinetics, as intended.
- **but avgBound (duty) collapses** 14.9 → 12.0 → 8.8 (−19%, −41%): faster detachment shortens dwell.
- **net v_axial therefore rises only +6% (×2) / +8% (×4)** — the extra turnover is almost entirely eaten by
  the duty loss. This is the **default+LT lesson made quantitative**: the LT fast-nucleotide-detach cratered
  avgBound 7→0.7 and killed net speed; here the milder ×2–×4 scaling doesn't crater duty (8.8 heads is still
  plenty) but the duty×turnover product is near-conserved, so the "biologically-legitimate 2×" delivers **+6%
  net, not 2×.**

**Faithfulness:** ×2 (detach base ~2×) plausibly lands in the fast-skeletal ADP-release range; ×4 is at/above
the high edge (probe). But the distinction is academic for the speed question: **even the defensible ×2 reaches
only ~3.3, not ~6.** A rate that would hit 6 (if duty didn't collapse) is not reachable because duty *does*
collapse — the ensemble is not single-molecule-V₀-limited, it is duty×tug-of-war-limited.

---

## Synthesis — which lever(s) give a defensible path to 5–8, and which don't

- **Density** — plateau ~3.3 (peak d1500), +7% over the d1000 reference. Free but tiny; capped by tug-of-war.
  A measurement choice, not a model lever.
- **Neck angle / step** — **null** (0% across 60–80°). Not a lever; settles the 60/70 confound (angle ≈ 0 of
  the BoA/v2 gap). 70° is the defensible value but speed-neutral.
- **Cycle rate / kinetics** — the only lever that raises *per-head* efficiency (+76% at ×4, faithful V₀∝rate),
  but the ensemble net gain is **+6–8%** because duty collapses. The biologically-legitimate 2× ≠ a 2× glide.

**No defensible single lever, and no defensible stack, reaches 5–8 in the current architecture.** The net
glide is robustly pinned near **~3.0–3.4 µm/s** by the invariant `net ≈ avgBound × per-bound-drift`, whose two
factors trade off against every knob tried. Reaching a higher net glide would require **breaking the
duty×efficiency tradeoff** — raising per-head V₀ *without* the duty collapse (e.g. the step/stroke mechanism
itself, which is HEAD_LEN-limited here, or reducing co-bound resistance) — none of which is a density/angle/rate
knob and none is in scope for this task. Flag for the eventual Skeletal/NMII parameter file: **kinetics is the
right faithful place to set isoform speed, but in this ensemble it primarily sets duty, not net glide.**

## Runs (for the record)
```
GlidingHarness -gpu -full -grid -density <250..2000> -seed <0..2> 150000                 # STEP 1
GlidingHarness -gpu -full -grid -density 1000 -neckangle <70|80> -seed <0..2> 150000      # STEP 2
GlidingHarness -gpu -full -grid -density 1000 -neckangle 70 -ratescale <2|4> -seed <0..2> 150000  # STEP 3
```
New flags: `-neckangle <deg>` (cocked neck rest angle, default 60), `-ratescale <x>` (×scale on catch-slip
kOff + all nucleotide cycle rates, default 1). Both default byte-identical; exploration only, **no promotion**
(adoption is a separate PLANNER-signoff step).
