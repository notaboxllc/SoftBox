# BINDING-SEARCH REFORMULATION — a physical per-sim-time encounter rate (additive, flag-gated)

**Status: DONE — implemented, validated, and a PREMISE-CORRECTING finding (2026-06-25).** Implements the
reformulation deferred by `MYOSIN_BINDING_RATE_FORMULATION.md` §4. Serves `RESEARCH_THESIS.md` §5/§9
(binding must be a **physical encounter process, not a fitted geometric knob**). **The headline result is
NOT the one the task hypothesized** (a rate search does NOT make binding-per-sim-time dt-flat) — the
measurement *re-diagnoses* the dt-dependence: the geometric search's capture **FLUX is already
≈dt-invariant**; the bound-COUNT's ~2.4× rise from 1e-5→fine-dt is the **bound LIFETIME** rising as the
cross-bridge **FORCE overshoot** (the upper wing) relaxes — NOT a capture/search artifact. The
reformulation still delivers the thesis goal (a physical, calibratable, tight-geometry search) and is a
faithful drop-in, but it is **not** a standalone dt lever — confirming `MYOSIN_BINDING…` Part 3.

Scene: the 0.5× dt-study scene (box 5.0, 200 nodes × 24 singlet myosins, 500 fil × 10 seg, crosslinkers
on, aeta 0.1, 12 pN cap on), matched **sim-time 0.10 s**, v2-GPU device-resident. dt the only variable.
Raw: `RUN_LOGS/2026-06-25_bindsearch_sweep.txt`. Driver `run_bindsearch.sh`.

---

## 1. What was built (additive, flag-gated, device-agnostic — geometric `bindNearest` stays the default)
- **`BindingDetectionSystem.bindRate`** — the rate capture. A FREE_BINDABLE motor binds with
  **P = 1 − exp(−kOn · Δl_eff · dt)**, where Δl_eff is the **path-average chord** of filament through a
  **TIGHT** capture sphere (radius = myoColTol = 0.006 µm, the fine-dt-tight geometry — **NOT** a fattened
  radius) over the head's swept segment [headPrev → head] this step (**formulation B, swept**;
  **formulation A, instantaneous** = the headPrev≡head special case ⇒ point chord). NSAMP=8 swept
  quadrature. If binding fires, the segment is chosen ∝ its rate (a second independent draw) and the bond
  site is the perpendicular foot of the current head (close approach ⇒ low cross-bridge stretch). kOn =
  `kinParams[14]` (µm⁻¹ s⁻¹), the physical encounter-rate handle. Wang-hash "BRAT" salt (distinct from the
  bind/release salts), no atomics/KernelContext ⇒ runs on both runners.
- **`gridReachableWide`** — candidate gather at a widened radius (myoColTol + margin, `kinParams[15]`) so
  the swept path's fly-by segments enter the candidate list; the chord PHYSICS keeps the tight radius.
- **`snapshotHead`** — per-step head→headPrev copy (formulation B).
- **`MotorStore`** — kinParams 14→16 ([14]=kOn, [15]=candReach) + `setSearchParams`.
- **`V2OneXHarness`** — `-ratesearch` (B default), `-pointsearch` (A), `-kon <v>`, `-candmargin <v>`,
  + a `bindFlux` column on DTROW (turnover = catch-slip + cap releases per sim-time ≈ the binding flux at
  steady state). Wired into BOTH the CPU runner and the 5-chained-graph GPU device-resident path.

The geometric path is byte-unchanged ⇒ **production is byte-identical with the flag off**. `BoA-v1ref`
untouched. This is a deliberate **model improvement** (the search now diverges from v1's geometric
capture); it re-baselines binding validation, and the geometric path stays available for v1 comparison.

## 2. The hypothesis — and how the data revised it
**Hypothesis (task):** the geometric per-step capture under-resolves at coarse dt (heads Brownian-jump
THROUGH the tight reach shell between step boundaries — fly-bys); replace it with a per-sim-time RATE at
the SAME tight geometry ⇒ binding becomes dt-invariant (flat vs the geometric's "2.6× rise") AND the
swept path catches the fly-bys. Per-step head rms displacement ≈ 0.0037 µm vs the 0.006 µm radius (~0.6×),
so fly-bys were *expected* to be a real but modest coarse-dt effect.

**What the data showed (§4):** fly-bys are **minor** (formulation A ≈ B: identical at 2e-6, +7% at 1e-5),
and — decisively — the geometric capture **FLUX is already ≈dt-invariant**. The "2.6× rise" everyone
attributed to the search is in the bound **COUNT**, and **count = flux × lifetime**: the flux is flat, the
**lifetime** triples as the cross-bridge **force overshoot relaxes** (the upper wing). So the dt-dependence
of binding was never primarily a search problem.

## 3. Calibration — kOn (the physical, optical-trap-calibratable handle)
Two regimes, both validated. With Δl≈0.008 µm the per-step capture saturation parameter is
kOn·Δl·dt ≈ (8e-9·kOn)·(dt/1e-5):
- **Diffusion-limited (saturated), kOn ≈ 1e8 (shipped default `KON_DEFAULT`):** kOn·Δl·dt ≫ 1 ⇒ P≈1 on
  contact ⇒ a faithful **drop-in for the geometric** (matches count/flux/distribution — see the caveat
  §6 on de-saturation). Reproduces the converged B*≈1050 at fine dt.
- **Reaction-limited, kOn ≈ 5e6:** kOn·Δl·dt ≲ 1 ⇒ a genuine probabilistic rate (lower count).
- **`B*≈1050 is NOT recoverable at dt=1e-5 by the search` at any kOn** — the count saturates at ~430 (≈
  the geometric 400) by kOn=2e7 (calibration scan: kOn 5e5/5e6/2e7/8e7 → 114/322/396/429 bound @1e-5).
  B* is a **converged fine-dt reference**, not a 1e-5 target; the 1e-5 count is force-wing(lifetime)-limited.

## 4. THE SWEEP (matched simT 0.10 s; geometric vs rate at two kOn × dt)

| variant | dt | bound | bindFlux (s⁻¹) | mean pN | p90 pN | %>6 pN | capHits |
|---|---|---|---|---|---|---|---|
| **GEOMETRIC** | 1e-5 | 447 | 2.06e5 | 4.54 | 7.07 | 22% | 3843 |
| | 2e-6 | 1069 | 1.54e5 | 2.95 | 4.58 | 1.1% | 705 |
| | 1e-6 | 1048 | 1.55e5 | 2.93 | 4.47 | 1.3% | 1702 |
| **RATE kOn=1e8** | 1e-5 | 424 | 2.10e5 | 4.33 | 6.98 | 20% | 3849 |
| (diffusion-limited) | 2e-6 | 1032 | 1.46e5 | 2.99 | 4.73 | 2.3% | 661 |
| | 1e-6 | 980 | 1.43e5 | 2.87 | 4.60 | ~1% | 1655 |
| **RATE kOn=5e6** | 1e-5 | 322 | 1.39e5 | 4.70 | 7.72 | 24% | 2195 |
| (reaction-limited) | 2e-6 | 685 | 9.64e4 | 2.99 | 4.60 | 1.7% | 457 |
| | 1e-6 | 618 | 9.80e4 | 2.90 | 4.58 | ~1% | 1182 |

Fine-dt reference (from `MYOSIN_BINDING…` §4.1): mean 2.92 / p90 4.63 / ~1% >6 pN / cap-churn ~0.02/step.
All runs **stable, no NaN, escXY=0** throughout.

### Findings from the table
1. **Distribution (the "decisive gate") — the rate search reproduces the tight fine-dt reference at fine
   dt**, at BOTH kOn (mean ~2.9–3.0 / p90 ~4.6 / ~1–2% >6 pN @ 2e-6 & 1e-6). At dt=1e-5 the distribution
   is fat (mean ~4.5 / ~24% >6 pN) — but this is **the cross-bridge FORCE wing** (the F8 overshoot ∝ dt),
   present **identically in the geometric** (4.54 / 22% @1e-5), NOT the search. The search keeps the bind
   GEOMETRY tight at every dt (binds at the perpendicular foot within myoColTol). **The hack's pathology
   is ABSENT**: at the hack's matched count the hack gave mean ~4.7 / p90 ~7.4 / 22–25% >6 pN / cap-churn
   6.5/step / fmgMax 19.5; the rate search's distribution simply equals the geometric's at each dt and
   converges to the tight reference at fine dt, with cap-churn ~0.07/step (457 hits / 50000 steps @ 2e-6).
2. **The capture FLUX is ≈dt-invariant** — for BOTH geometric and rate. Net of the spurious cap-churn,
   the productive flux is roughly flat (GEO ~1.68e5→~1.46e5 across 1e-5→1e-6, ±~13%; RATE-k5e6
   ~1.17e5→~8.6e4, ±~26%). It does **NOT** rise 2.6×. The total turnover flux is even slightly HIGHER at
   1e-5 (inflated by the high force-wing cap-churn).
3. **The bound COUNT's ~2.4× rise is the LIFETIME (force wing), not capture.** count/flux = mean bound
   lifetime: GEO 447/2.06e5 = 2.2 ms @1e-5 → 1069/1.54e5 = 6.9 ms @2e-6 (≈3.2× longer). As dt→0 the F8
   overshoot relaxes (fmgMean 4.5→2.9, cap-churn 3843→705) ⇒ slower release ⇒ longer-lived bonds ⇒ higher
   steady count. This is the **upper wing**, untouchable by a search reformulation.
4. **B*≈1050 reproduced at FINE dt, not at 1e-5.** RATE-k1e8 @2e-6 = 1032 ≈ B*; @1e-5 = 424 ≈ geometric.
   Confirms B* is a converged reference, and the 1e-5 deficit is lifetime(force-wing)-limited for *both*
   search formulations.
5. **A (point) vs B (swept): fly-bys are minor.** A=B at 2e-6 (685=685); B = A+7% at 1e-5 (322 vs 300).
   The swept exposure integral is implemented and correct (it's the dt-invariant ∫chord dt), but in this
   scene/dt-range it contributes ≤7% — consistent with the ~0.6× displacement/radius geometry. The
   per-step capture is therefore NOT badly under-resolving — corroborating finding 2.

## 5. CPU≡GPU / stability
- **CPU≡GPU = aggregate-within-SEM** (the project's chaotic-many-body standard, `CLAUDE.md`), **NOT
  bit-identical**: Brownian-on @1e-5/400 steps CPU 300 vs GPU 299 bound (links 11/10); Brownian-off
  (deterministic IC) CPU 12 = GPU 12 bound, bound-set Δ=2. Unlike the geometric `bindNearest`
  (deterministic ⇒ bit-identical), `bindRate` uses `Math.exp` in P_bind, whose float32 last-bit can flip
  a near-threshold bind decision ⇒ chaotic decorrelation — **exactly like the existing stochastic
  catch-slip release** (`NucleotideCycleSystem.catchSlipRelease`, also exp+RNG, also aggregate-within-SEM).
  This is the expected standard for a stochastic process, not a regression.
- **Conservation:** N/A in this scene (static IC filaments, no actin-pool turnover/nucleation) — no
  phantoms possible; stability/no-NaN/no-escape held at every dt.

## 6. CAVEAT — the "drop-in for geometric" equivalence is regime- and range-bounded
The kOn=1e8 ≈ geometric match holds **only in the saturated regime and over the tested dt range
{1e-5, 2e-6, 1e-6}**, and **already de-saturates at the fine end**. Because P=1−exp(−kOn·Δl·dt) ∝ dt at
fixed kOn, the saturation parameter kOn·Δl·dt ≈ 8e5·dt: ≈8 @1e-5 (P≈0.9997, fully saturated, 424≈447),
≈1.6 @2e-6 (P≈0.80, 1032 vs 1069, ~3% under), ≈0.8 @1e-6 (P≈0.55, 980 vs 1048, ~6.5% under). Push dt
smaller at fixed kOn and the per-step capture keeps de-saturating ∝dt ⇒ the rate leaves the saturated
regime and drops toward the reaction-limited count (cf. k5e6). **At fixed kOn the rate search is NOT a
geometric clone across arbitrary dt** — it coincides only where kOn·Δl·dt ≳ a few, a window that shrinks
as dt→0. The genuinely dt-robust properties are the **flux** (≈invariant) and the **tight bind geometry**
(invariant), not a literal count-match to the geometric.

## 7. VERDICT
- **Thesis goal achieved (`RESEARCH_THESIS.md` §5/§9):** binding is now a **physical per-sim-time
  encounter rate** with a single calibratable handle (kOn, the optical-trap on-rate target), at the
  **fine-dt-tight capture geometry** (no fattened-radius knob, no hack fat tail). The prescribed geometric
  search is removed in favor of an encounter-rate process — the methods-paper requirement.
- **The task's headline hypothesis is REFUTED and re-diagnosed (a real finding):** a search reformulation
  does **NOT** make binding-per-sim-time dt-invariant, because the geometric search's dt-dependence is
  **NOT** primarily a capture under-resolution. The capture FLUX is already ≈dt-invariant; the ~2.4×
  bound-COUNT rise (1e-5→fine) is the **bound LIFETIME** growing as the cross-bridge **FORCE overshoot**
  relaxes. This **corrects** `MYOSIN_BINDING_RATE_FORMULATION.md` §2/§4's "the below-1e-5 rise is pure
  search, decoupled from force" attribution (that held only on the narrow 5e-6→2e-6 flat-tension segment;
  the bulk 1e-5→fine rise is the force wing), and **confirms** its §3 conclusion that **the
  implicit/sub-step cross-bridge is the dt lever**, not the search.
- **Net:** ship the rate search as the physical, calibratable, flag-gated binding model (default off;
  geometric stays the v1-faithful comparator). It makes binding *physical* and keeps the distribution
  *tight*, but **the dt ceiling is the cross-bridge force wing** — the binding search is a correctness/
  thesis win, not a speed lever (as `MYOSIN_BINDING…` Part 2 already concluded). The cross-bridge
  implicit/sub-step fix remains the complementary, separate piece.

## 8. Gate audit (read-only code inspection) — what the searches filter, and what kOn must represent
Shared predicate `reachTestDistSq` (alignTol=−0.4, myoColTol=0.006 µm). Gate order, hard unless noted:

| gate | bindNearest (geometric) | bindRate (rate) |
|---|---|---|
| motor FREE_BINDABLE (refractory upstream) | yes | yes |
| candidate gather (27-cell, FILAMENT) | `gridReachable`, **tight** myoColTol | `gridReachableWide`, **widened** candReach |
| non-degenerate seg (denom>0) | yes | yes |
| **α∈[0,1] foot-on-segment** | yes | yes (per swept sample) |
| **conDist < myoColTol** (tight 0.006) | yes | yes — chord uses r2=myoColTol² (**tight**, not candReach) |
| **motDotFil ≥ alignTol (−0.4)** (head·fil align) | yes | **yes** (continue ⇒ 0 rate) |
| **rodDotFil ≥ 0** (rod·fil orientation) | yes | **yes** (continue ⇒ 0 rate) |
| selection | nearest (min perp dist) | ∝ rate (chord-weighted) |
| reaction step | deterministic **P=1** | probabilistic **P=1−exp(−kOn·Δl·dt)** |

**Alignment IS preserved in bindRate, as a HARD binary filter** (mis-aligned ⇒ continue ⇒ 0 rate ⇒ cannot
bind), NOT a continuous modulation — the rate is modulated only by Δl (overlap chord through the tight
sphere), not by alignment quality. The widened candReach loosens only the candidate GATHER distance; the
bind PHYSICS re-clips to the tight myoColTol. **⇒ the geometric-vs-rate sweep is apples-to-apples** (same
admissibility gates; bindRate differs only in: swept fly-by resolution [minor, A≈B], rate-weighted vs
nearest selection, probabilistic vs deterministic reaction).

**Gate → physical filter:** encounter/diffusion = explicit (grid + tight sphere + the head's resolved
Brownian trajectory). Orientation/alignment = explicit but **COARSE** (two hard gates: a ~114° half-angle
cone via alignTol=−0.4, + rod within 90° via rodDotFil≥0 — reject only gross mis-orientation, binary, no
graded cosine). Reaction/isomerization = the geometric's deterministic P=1, replaced by the rate's
P=1−exp(−kOn·Δl·dt). **⇒ kOn must represent the reaction/isomerization success-per-admissible-encounter +
the FINE orientational selectivity the coarse gates miss; it must NOT re-include the gross-orientation
rejection (already explicit) nor the diffusional encounter rate (explicit) — so kOn ≠ the full solution
k_on (which bundles all of orientation × reaction).** A clean reaction-only kOn would require tightening
the alignment gate into a graded/narrow orientational acceptance (a model change, out of scope).

```
# reproduce
./run_bindsearch.sh dtsweep   # (edit KON in-script) rate-B across dt; or use /tmp/sweep.sh (geo + 2 kOn × 3 dt)
./run_1x.sh -gpu -ratesearch -nodes 200 -nfil 500 -box 5.0 -dt 1e-5 -steps 10000 -dtconv   # default kOn=1e8
./run_1x.sh -gpu -ratesearch -pointsearch ...   # formulation A (instantaneous, no swept)
./run_1x.sh -cmp -ratesearch -nodes 200 -nfil 500 -box 5.0 -dt 1e-5 -steps 400   # CPU≡GPU (aggregate-within-SEM)
```
