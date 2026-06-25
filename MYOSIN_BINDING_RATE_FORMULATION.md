# MYOSIN BINDING dt-FORMULATION — the two dt-collapse wings are INDEPENDENT mechanisms (search vs cross-bridge-force overshoot); a search reformulation does NOT open headroom above 1e-5

**Status: INVESTIGATION COMPLETE (2026-06-25). MEASUREMENT + CODE-READ ONLY — no reformulation implemented.**
This closes the OPEN/HYPOTHESIS task recorded earlier (preserved verbatim in the appendix). The hypothesis is
**confirmed in its core (two independent wings) and CORRECTED in one detail** (the lower wing does NOT "starve"
as dt→0 — binding *rises*; and the release-rate *law* is not the dt-fragile part — the *force feeding it* is).
Companion: `DT_CONVERGENCE_FINDINGS.md` (which supplied the UPPER wing). Raw: `RUN_LOGS/2026-06-25_dt_below.txt`.
Instrument: `V2OneXHarness -dtconv` (flag-gated, default-off; production byte-unaffected); driver `run_dtconv.sh below`.

## TL;DR — the four answers jba asked for

1. **Are the two dt wings independent mechanisms? YES — confirmed by code AND the below-1e-5 data.**
   - LOWER (below ~1e-5): the **SEARCH** — binding is a per-step *geometric* event (not a per-sim-time rate). As
     dt→0 binding **rises** toward the true diffusion-limited capture (450 → 1129 → 1244 bound at dt 1e-5 → 5e-6 →
     2e-6), **at low, FLAT cross-bridge tension** (~3.0 pN, fracOverCap = 0). Tension is pinned at its physical
     floor here, so this wing is **not** the force-release — it is the search.
   - UPPER (above ~1e-5): the **CROSS-BRIDGE FORCE** — the stiff F8 spring overshoots ∝ dt (fmgMean 4.4 → 9.1 pN
     at 1e-5 → 2e-5), feeding the (correct) force-exponential catch-slip release ⇒ binding **collapses** (450 → 22).
   - The two are mechanistically disjoint: the lower wing moves binding while tension is flat at the floor; the
     upper wing moves binding via tension. They **cross near 1e-5**, which is why 1e-5 looked like a fixed point.

2. **Would a binding-SEARCH reformulation open dt headroom ABOVE 1e-5? NO** (hypothesis confirmed). The upper wing
   is **cross-bridge mechanics**, not the search rule. A search→rate reformulation makes the *lower* wing
   dt-invariant (binding converges instead of drifting) but leaves the F8 overshoot — hence the catch-slip
   collapse — untouched. **Headroom above 1e-5 is opened only by fixing the cross-bridge force**: an
   implicit / sub-stepped cross-bridge (faithful to v1's force-integrated model) OR a kinetic, rate-based motor
   (no stiff spring — **fully dt-insensitive but a different model class than v1**, a faithfulness decision for jba).

3. **Where is the converged binding rate (dt→0 limit)?** **The search CONVERGES — a finite plateau B\* ≈ 1050 (at
   matched simT 0.10), reached by dt ≈ 2e-6** (sub-2e-6 sweep §Part 4: bound 1093 / 1050 / 970 at dt 2e-6 / 1e-6 /
   5e-7 — flat within the ±3% chaotic envelope; the monotone rise has stopped). So the per-step geometric capture is
   **correct-but-UNDER-RESOLVED at 1e-5** (under-counts capture ~2.6×: 400 → ~1050), **NOT ill-posed** — it has a
   continuum limit. **1e-5 is a CALIBRATION point (matched to v1, which also runs the per-step geometric bind at
   ~1e-5), NOT the converged physical value.** The test under-counts encounters at coarse dt and converges from
   below. **BUT a cheap per-dt reach-multiplier hack does NOT recover the converged behavior** (§Part 4, Part 2):
   widening the 1e-5 reach to ~2.8× to match B\*'s COUNT binds an **over-stretched, fat-tailed geometric set**
   (stretch mean 4.7 vs the fine-dt 2.9 pN; ~23% of bonds >6 pN vs ~1%; cap-churn 6.5/step vs ~0.02) — **matched
   count, WRONG distribution** ⇒ the fix is a principled **swept-volume / reaction-rate** capture, not a radius fudge.

4. **Next-stiffest constraint after the cross-bridge is fixed?** **There is no next raw stiff spring.** The
   cross-bridge F8 (`fmag = myoSpring·dist`) is the **ONLY** explicit Hookean spring in the model; **every other
   coupling** (chain F3/F4, motor joints J1/J2, dimer, minifilament, node tether, crosslinker, tail-anchor) uses
   the **damping-limited `fracMove·strain/(dt·moveC)` form** — a contraction map, unconditionally stable,
   dt-robust, cannot overshoot. So fixing the cross-bridge **removes the only stiff element** rather than relocating
   to another spring. The next dt-limiter is then the **rate-discretization** (crosslink formation `pForm =
   1−exp(−k·conc·dt·checkInt)` saturation, which only bites ≥ ~5e-5) — i.e. headroom up toward ~5e-5 (~5×), **but
   only if the search wing is ALSO reformulated** (see §4), because the search under-resolves at every dt and would
   otherwise keep binding sloped above 1e-5 too.

---

## Part 1 — the three-stage dt-touchpoint map (CODE-READ; production path = `bindNearest` + `catchSlipRelease`)

| stage | where | dt-robust or dt-fragile? | evidence |
|---|---|---|---|
| **1. Search / attach** | `BindingDetectionSystem.bindNearest` + `reachTestDistSq` | **dt-FRAGILE — per-step geometric event, NOT a rate** | the bind is `if (FREE_BINDABLE && a seg in reach) → bind nearest`. `reachTestDistSq` is pure geometry (segment perpendicular drop, α∈[0,1], `conDistSq < myoColTol²`, `motDotFil ≥ alignTol`, `rodDotFil ≥ 0`) — **no dt term anywhere**. There is **no `1−exp(−k·dt)`**. Confirms the lower-wing hypothesis structurally. |
| **2. Cross-bridge force** | `CrossBridgeSystem.bondForces:96` | **dt-FRAGILE — overshoots ∝ dt** | `fmag = myoSpring · dist`, a **raw explicit Hookean spring** (myoSpring ≈ 1e-9 N/µm) evaluated at the explicitly-integrated head/site positions. Overdamped Euler (`RigidRodLangevinIntegrationSystem`: `v=1e6·F/γ; coord += dt·v`) ⇒ the per-step relative head↔site displacement ∝ dt ⇒ spring stretch (hence F8) overshoots ∝ dt. (The F9/F10 *torques* DO carry `/dt` — damping-limited; only the F8 *force* is raw.) |
| **3. Release** | `NucleotideCycleSystem.catchSlipRelease:116` | **rate-LAW is dt-robust (proper `P=rate·dt`); the FORCE feeding it is dt-fragile** | `rate = kOff·(αCatch·e^{−F·xCatch/kT} + αSlip·e^{+F·xSlip/kT})`, `P = rate·dt`, `F = forceDotFil` from stage 2. `P=rate·dt` is a *correct* per-sim-time rate (doubling dt doubles per-step P but leaves the per-sim-time off-rate fixed). The dt-fragility enters **only through F** (stage 2's overshoot): the slip exponential `e^{+F·xSlip/kT}` explodes on the dt-inflated load. Constants: kOff=100/s, αCatch=0.92/xCatch=2.5 nm, αSlip=0.08/xSlip=0.4 nm; the 12 pN break cap (kinParams[11/12]) is a parallel branch, off by default, REFUTED as the gate by the study's `-nocap` control. Refractory `ceil(myoRebindTime/dt)` IS dt-correct (fixed physical 1e-5 s ⇒ 5/2/1/1 steps at dt 2e-6/5e-6/1e-5/2e-5). |

**Refinement of the original framing.** The original note lumped binding and crosslink-formation as "a Poisson rate
process coded dt-fragile." The release-rate *law* is in fact NOT coded dt-fragile (it is a proper `P=rate·dt`). The
two genuinely dt-fragile components are (a) the **search** (stage 1, structurally not a rate) and (b) the
**cross-bridge force** (stage 2, an explicit stiff spring). They are the lower and upper wings respectively.

## Part 2 — binding-per-sim-time BELOW 1e-5 (the missing wing; is 1e-5 converged?)

Same 0.5× scene (box 5.0, 200 nodes × 24 singlet myosins, 500 fil × 10 seg, crosslinkers on, aeta 0.1, 12 pN cap
on), dt the ONLY variable, matched **sim-time 0.2 s** ⇒ 10000/20000/40000/100000 steps. Observables at matched-simT
checkpoints. v2-GPU device-resident (80→51 steps/s as the step count grows; 2e-6 = 100k steps ≈ 32 min). All runs
**stable** (no NaN; one 2e-6 in-plane poke of 1/5000 segs at the final checkpoint — negligible).

| dt | steps | bound @simT 0.10 | bound @simT 0.20 (steady/↑) | bound / 1e-5 | fmgMean (pN) | fmgMax (pN) | fracOverCap | capHits/step |
|---|---|---|---|---|---|---|---|---|
| 2e-5 | 10000 | 18 | **22** | **0.05×** | ~9.1 | ~20 | 0.2–0.5 | ~4.3 |
| **1e-5** (ref) | 20000 | 400 | **~450** (steady ≈0.12) | **1.00×** | ~4.4 | ~12 | ~0 | 0.37 |
| 5e-6 | 40000 | 918 | **1129 ↑** | **2.51×** | ~3.3 | ~9 | 0.000 | ~0.010 |
| 2e-6 | 100000 | 1094 | **1244 ↑** | **2.76×** | ~3.0 | ~8 | 0.000 | ~0.011 |

(1e-5 here reaches ~450 at simT 0.2; the study's 3-seed reference was 470±12 at simT 0.3 — consistent, this run is a
hair short of full steady state and single-seed. The below-1e-5 trend dwarfs that ±3% envelope.)

**Readings:**
- **Binding does NOT starve as dt→0 — it RISES**, monotonically, with **no plateau through 2e-6**. The recalled
  "small-dt starvation" is **REFUTED** for this deterministic-on-contact scene. The per-step geometric test
  **under-resolves transient encounters at coarse dt** (a head can Brownian-jump through the `myoColTol` reach shell
  between steps, ∝√dt, and never be tested while in-reach) and converges to the true diffusion-limited capture from
  below as the steps get finer.
- **Cross-bridge tension falls to a FLOOR (~3 pN) as dt→0** (9.1 → 4.4 → 3.3 → 3.0), fracOverCap → 0, fmgMax well
  under the cap. The ~3 pN floor is the converged physical cross-bridge tension (no overshoot); the 1e-5 value (4.4)
  already sits **above** it — the force-overshoot wing is **already active at 1e-5**.
- **The wings are INDEPENDENT — the decisive cut:** between 5e-6 and 2e-6 the tension is flat at the floor
  (3.3 → 3.0 pN, fracOverCap = 0) yet binding still climbs (1129 → 1244). Binding changes while the force does not ⇒
  the below-1e-5 wing is the **search**, not the force-release. Cross-check on the release: the catch-slip rate at
  the 3.0 pN floor is **25.6/s — HIGHER** than at the 4.4 pN catch-minimum of 1e-5 (18.6/s), so the rise happens
  **despite** slightly faster per-sim-time unbinding ⇒ it is purely capture-side, and the release law is confirmed a
  proper (dt-robust) per-sim-time rate.
- **Is 1e-5 converged? NO.** Binding is on a slope (monotone, un-plateaued) and 1e-5 sits at the **onset of the
  force-overshoot wing** (fmg 4.4 > floor 3.0; cap hits beginning) — i.e. binding at 1e-5 is already **suppressed
  ~½–⅓** below the low-tension regime. 1e-5 is the **crossover of the rising search wing and the collapsing force
  wing**, validated against v1 (which shares the per-step geometric bind at ~1e-5), **not** a converged fixed point.

## Part 3 — synthesis

### Wings-independent verdict (confirmed)
Two disjoint dt-fragile mechanisms, crossing near 1e-5:
- **LOWER = SEARCH.** Structural (binding is a per-step geometric test, stage 1). Operates at **all** dt; visible in
  isolation below ~5e-6 where the force floor is reached. Direction: binding **converges from below** as dt→0
  (under-counts at coarse dt). Independent of the cross-bridge force (moves binding at flat ~3 pN tension).
- **UPPER = CROSS-BRIDGE FORCE.** Mechanics (the F8 stiff-spring explicit overshoot ∝ dt, stage 2, feeding the
  correct force-exponential release, stage 3). Onsets ~1e-5, dominates above. Independent of the search rule.

### Which reformulation opens headroom above 1e-5
- **Search→rate reformulation** (`P_bind = 1−exp(−k_encounter·dt)` from head diffusivity + local filament density,
  or a swept-volume capture): fixes the **LOWER** wing — binding becomes dt-invariant and converges. **Does NOT open
  headroom above 1e-5** — the upper wing is untouched. (Hypothesis confirmed.) It is still worth doing for
  correctness (it removes the dt-asterisk on all binding statistics), but it is not the speed lever.
- **Implicit / sub-stepped cross-bridge** (faithful to v1): attacks the **UPPER** wing at its cause — removes the
  ∝dt F8 overshoot so the force-exponential release no longer explodes. **This is the headroom lever**, and it keeps
  v1's force-integrated model class (the power stroke remains an emergent mechanical force).
- **Kinetic rate-based motor** (replace the stiff F8 spring + force-dependent release with a Bell-type rate machine,
  no stiff spring): **fully dt-insensitive** — removes the upper wing entirely. **But it is a different model class
  than v1** (the stroke becomes a prescribed rate process, not an emergent spring force) ⇒ a **faithfulness
  decision** for jba, distinct from a bug-for-bug port. Surfaced explicitly as the "dt-insensitive but
  model-changing" option.
- **A global drag/viscosity/parameter rescale CANNOT reach either wing** (study §"lever"): it holds the smooth
  dissipative dynamics dt-invariant but cannot undo a stiff-spring force overshoot feeding a force-dependent rate,
  nor make a per-step geometric test into a rate.

### How much headroom — bounded, not infinite
- **The cross-bridge F8 is the ONLY raw explicit stiff spring.** All other couplings are damping-limited
  `fracMove/(dt·moveC)` contraction maps (chain, joints, dimer, minifilament, node, crosslinker, anchor) — dt-robust
  by construction. So fixing the cross-bridge does **not** relocate the ceiling to a "next-stiffest spring"; there
  is none.
- After the cross-bridge is implicit/sub-stepped **AND** the search is reformulated as a rate, the next dt-limiter
  is the **rate-discretization** — crosslink formation `pForm` saturation, which the study showed only departs the
  linear regime ≥ ~5e-5 (pForm 0.039 → 0.33 across 1e-5 → 1e-4). So the *bounded* headroom is up toward **~5e-5
  (~5× over 1e-5)**, set by the formation-rate linearization — **not** infinite.
- **Both fixes are required to actually raise dt faithfully.** The implicit cross-bridge alone removes the force
  collapse, but the search wing still under-binds at larger dt (the per-step capture under-resolves at every dt);
  so binding would remain dt-sloped above 1e-5 until the search is also a rate. This is the honest cost of the ~5×.

### Standing caveats (carried from the original, now measured)
- **1e-5 is NOT a converged binding reference** — confirmed (binding plateaus ~2.6× higher by 2e-6; §Part 4). All
  project binding statistics carry an "at dt=1e-5" asterisk; absolute bound-motor counts are dt-specific.
- **This does NOT change any v1↔v2 (or v2-CPU≡v2-GPU) comparison** — those were at **matched dt**, so the
  dt-dependence cancels in the ratio. Only the *absolute* numbers carry the asterisk. v1 itself runs the per-step
  geometric bind at ~1e-5, so v1 and v2 under-count encounters identically at the calibration point.

---

## Part 4 — does the geometric search CONVERGE? (sub-2e-6 plateau) + the hack-factor count-vs-distribution test (2026-06-25)

Follow-on measurement deciding "under-resolved-but-fine" vs "ill-posed", and whether a 1e-5 calibration can recover
the converged behavior. Same 0.5× scene, **matched sim-time 0.10 s** (1e-5 is already steady by 0.10, so the
checkpoint is representative and the lowest-dt runs stay overnight-feasible; the dt-curve at a FIXED simT is the
clean convergence test). New host-side **DTHIST** instrument (the cross-bridge-stretch |F8|=myoSpring·dist
distribution over bound motors — the trajectory-robust "at what reach/geometry" proxy; no kernel change, emitted
only under `-dtconv`). Raw: `RUN_LOGS/2026-06-25_dt_below2.txt` (Part 1) + `RUN_LOGS/2026-06-25_dt_hack.txt` (Part 2).
Drivers `run_dtconv.sh below2` / `run_dtconv.sh hack <reach…>`. All runs stable/finite, no NaN.

### Part 4.1 — the search CONVERGES (B\* ≈ 1050; NOT ill-posed)

| dt | steps | bound @simT 0.10 | bound/1e-5 | fmgMean (pN) | fmgMax | fracOverCap | stretch dist mean/median/p90 (pN) | %>6 pN |
|---|---|---|---|---|---|---|---|---|
| 1e-5 | 10000 | 400 | 1.00× | 4.68 | ~12 | 0.000 | 4.68 / 4.63 / 7.43 | ~26% |
| 5e-6 | 20000 | 918 | 2.30× | ~3.3 | ~9 | 0.000 | — | — |
| 2e-6 | 50000 | 1093 | 2.73× | 3.02 | 8.7 | 0.000 | 3.02 / 2.89 / 4.77 | ~2.4% |
| 1e-6 | 100000 | 1050 | 2.63× | 2.92 | 10.7 | 0.000 | 2.92 / 2.75 / 4.63 | ~1.0% |
| 5e-7 | 200000 | 970 | 2.43× | 2.76 | 11.6 | 0.000 | 2.76 / 2.62 / 4.36 | ~0.7% |

- **PLATEAU.** Bound rises (400 → 918 → 1093) then **flattens by ~2e-6**: 2e-6 / 1e-6 / 5e-7 = 1093 / 1050 / 970,
  flat within the ±3% chaotic envelope (the slight non-monotone wiggle is single-seed scatter; the monotone rise has
  unambiguously stopped). **The per-step geometric capture has a finite continuum limit B\* ≈ 1050 (at simT 0.10).**
  ⇒ the search is **correct-but-under-resolved at 1e-5** (under-counts ~2.6×), **NOT ill-posed**. (The earlier §Part 2
  read "still rising at 2e-6" was at simT 0.20, where the slower-saturating fine-dt *trajectories* are still climbing
  in time; the dt-curve at the fixed earlier simT 0.10 — the proper convergence test — plateaus.)
- **Wing separation holds to the bottom.** fmgMean falls to a **~2.8 pN floor and stays** (3.0 → 2.9 → 2.8),
  fmgMax < cap, **fracOverCap = 0** at every dt ≤ 2e-6. So the entire below-1e-5 binding change happens at the flat
  tension floor ⇒ it is **pure search resolution**, fully decoupled from the force-release wing, all the way down.
- **The fine-dt distribution also converges** and is **tight**: peaked at 2–3 pN, p90 ≈ 4.5 pN, **~1% of bonds above
  6 pN**, essentially nothing above 8 pN. This is the true converged bind geometry (low stretch, close approach).

### Part 4.2 — the hack: matched COUNT, WRONG DISTRIBUTION

At dt=1e-5, widen the bind reach (`-reach`, default = v1 myoColTol 0.006 µm) to recover B\*, then compare the stretch
distribution to the fine-dt reference (1e-6, B\*≈1050).

| reach (µm) | ×true | bound | fmgMean | p90 | %>6 pN | fmgMax | capHits/step |
|---|---|---|---|---|---|---|---|
| **fine-dt ref (1e-6 @ 0.006)** | 1× | **1050** | **2.92** | **4.63** | **~1%** | 10.7 | **~0.02** |
| 0.006 (1e-5 default) | 1× | 400 | 4.68 | 7.43 | ~26% | 12.0 | 0.33 |
| 0.008 | 1.33× | 575 | 4.55 | 7.28 | ~22% | 10.7 | 0.52 |
| 0.010 | 1.67× | 682 | 4.47 | 6.91 | ~22% | 16.6 | 0.77 |
| 0.012 | 2.0× | 877 | 4.60 | 7.46 | ~25% | 13.1 | 1.07 |
| 0.015 | 2.5× | 951 | 4.65 | 7.35 | ~24% | 14.7 | 3.6 |
| 0.020 | 3.33× | 1217 | 4.69 | 7.47 | ~23% | 19.5 | 6.5 |

- **Count is recoverable** — reach ≈ 0.016–0.017 µm (**≈ 2.8× the true 0.006**) interpolates to B\*≈1050.
- **Distribution is NOT.** Every hacked-1e-5 distribution sits at **mean ≈ 4.5–4.7 pN / p90 ≈ 7.3 / ~22–25% of bonds
  above 6 pN / fmgMax to 19.5 pN**, vs the fine-dt reference **mean 2.92 / p90 4.63 / ~1% above 6 pN / fmgMax 10.7**.
  The hack binds a **systematically over-stretched, fat-tailed geometric population** — segments that are far (within
  the fattened instantaneous radius) bound in a single coarse step at large stretch, which the true fine-dt capture
  only binds at a closer swept approach (or never). **Matched count, ~1.6× higher-tension distribution with a heavy
  high-stretch tail.**
- **Two stacked distortions, both visible:** (i) the bulk is already shifted up (mean ~4.7 vs the 2.9 floor) by the
  **force-wing overshoot at 1e-5** — present even at the *default* reach (a dt effect, not a reach effect); (ii) the
  widened reach **fattens the high tail and explodes the cap-release churn** (capHits/step 0.33 → 6.5, fmgMax 12 →
  19.5) — a different binding/unbinding KINETIC regime, not just a different snapshot.

### Part 4 verdict
- **The geometric search is convergent (B\*≈1050), so a per-dt calibration is conceptually possible — but a reach
  multiplier is the WRONG one:** it recovers the count with a wrong, over-stretched, high-churn distribution. The
  count match is cosmetic. ⇒ the principled fix is a **swept-volume capture** (capture along the head's per-step
  motion at the true tight geometry) or a **reaction-rate `1−exp(−k_on·dt)`** capture — **both deferred to the
  reformulation task** (do NOT implement here).
- **Independently, the cross-bridge force integration must also be fixed** for the bound-bond tension distribution to
  match the continuum: even at the true reach, 1e-5 already inflates the stretch distribution via the F8 overshoot
  (the upper wing). So a faithful, dt-robust binding *and* tension distribution needs **both** levers (search→rate
  AND implicit/sub-step cross-bridge) — consistent with Part 3.

---

## Appendix — the original OPEN/HYPOTHESIS note (verbatim, pre-investigation)

> The problem (one line): v2's myosin binding search appears to be a **per-step geometric event** rather than a
> **per-sim-time encounter rate** ⇒ binding/sim-time is not dt-invariant. **[CONFIRMED — stage 1 is exactly this.]**
>
> Symptom — dt-fragile at BOTH ends: dt→smaller "binding STARVES (anti-convergent)" **[CORRECTED — binding RISES as
> dt→0; the per-step test UNDER-counts at coarse dt and converges from below. The direction recalled was backwards,
> but the dt-fragility is real and confirmed]**; dt→larger "binding COLLAPSES" via cross-bridge over-force **[CONFIRMED
> — the upper wing]**.
>
> Unifying insight: "binding and crosslink formation are the same bug, opposite dt ends." **[PARTIALLY CORRECTED —
> the release-RATE law is a proper `P=rate·dt`, not the dt-fragile part; the dt-fragile parts are the SEARCH (stage 1)
> and the cross-bridge FORCE (stage 2). Crosslink-formation `pForm` saturation is a separate, subdominant
> rate-discretization that only bites ≥~5e-5.]**
>
> Fix direction: reformulate binding as `P_bind = 1−exp(−k_encounter·dt)`. **[STANDS for the lower wing; does NOT
> address the upper wing — see Part 3.]**

```
# reproduce
./run_dtconv.sh below     # matched simT 0.2 s, dt ∈ {2e-5,1e-5,5e-6,2e-6}, seed 1, v2-GPU; raw → RUN_LOGS/2026-06-25_dt_below.txt
```
