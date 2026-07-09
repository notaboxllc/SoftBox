# dt-CONVERGENCE STUDY — the largest FAITHFUL (no-tuning) mechanics dt, and which constraint sets the ceiling

**MEASUREMENT-ONLY.** dt is the only variable; no physics/rate/default edits; `BoA-v1ref` untouched. v2-GPU
device-resident path (sole occupant of aorus). Instrument: `V2OneXHarness` `-dtconv`/`-seed`/`-nocap` (all
flag-gated, default-off — the production benchmark path is byte-unaffected). Raw:
`RUN_LOGS/2026-06-24_dt_convergence.txt`.

## TL;DR verdict

- **Largest faithful (no-tuning, within-envelope) mechanics dt = 1e-5 s. There is NO headroom.** A mere +20 %
  (dt = 1.2e-5) already collapses the bound-motor count 2.5× (470 → ~190), far outside the tight ±3 % chaotic
  envelope. The **defensible faithful speedup from raising dt alone is ~1.0× — none.**
- **The ceiling is set by the cross-bridge force-dependent UNBINDING, driven by the explicit-integration
  OVERSHOOT of the stiff cross-bridge spring** (per-step force ∝ dt). Binding (bound-motor count) diverges
  catastrophically; conservation/contraction/stability do not bound it — they are far looser.
- **The specific "12 pN break cap is the gate" hypothesis is REFUTED by a cap-off control** (disabling the cap
  does NOT restore binding at 2e-5). The cap is a *parallel, secondary* symptom-shedder; the **catch-slip
  force-exponential release** (`rate ∝ e^{+F·xSlip/kT}`, `P = rate·dt`, F = the dt-inflated cross-bridge load)
  is the dominant collapse channel. **Both** are force-coupled to the same stiff-spring overshoot.
- **Stability limit ≫ accuracy limit.** Every dt through 1e-4 is perfectly stable (no NaN, no wall-escape, no
  blow-up — the releases *prevent* runaway by shedding over-force bonds). Stability > 1e-4; accuracy ≤ 1e-5.
  The two differ by **≥ 10×** — stability is the wrong gauge; the usable dt is the accuracy limit.
- **Implication (confirmed + strengthened): the lever for a larger faithful dt is sub-stepping / an implicit
  cross-bridge integrator**, NOT a global drag/viscosity rescale or parameter tuning. A rescale holds the
  *smooth dissipative* dynamics dt-invariant but cannot undo a stiff-spring force overshoot that feeds a
  force-dependent (exponential + hard-threshold) release rate — that rate depends on the actual force
  magnitude, which the explicit scheme over-drives ∝ dt regardless of drag.

## Method

Same **0.5× scene** (density-preserving half of the V2OneX 1× contractility standard): box **5.0 × 5.0 × 0.5 µm**,
**200 nodes × 24 singlet myosins (4800)**, **500 filaments × 10 seg (5000 segments)**, crosslinkers ON, aeta 0.1,
12 pN faithful break cap ON (the 1×-validated default). **dt is the ONLY variable** — every other parameter at its
1e-5-validated value; NO tuning.

Sweep **dt ∈ {1e-5, 1.2e-5, 1.5e-5, 2e-5, 5e-5, 1e-4}**, each run to the **same total simulated time 0.3 s** ⇒
30000 / 25000 / 20000 / 15000 / 6000 / 3000 steps. **Observables compared at matched SIM-TIME checkpoints**
(every 0.03 s), NOT matched step counts — the crux. The 1e-5 reference's chaotic run-to-run envelope is
established from **3 seeds** (independent IC + Brownian RNG); divergence is judged against that envelope, not a
single run.

Observables (per checkpoint): bound-motor count; crosslink count; in-plane radius of gyration R_g (contraction);
cross-bridge tension over bound motors (mean / max pN, fraction > 12 pN cap); cumulative cap-release events; wall
escapes; NaN. Mechanistic confirmation: the cross-bridge tension-vs-cap distribution across dt, plus a **cap-off
control** (`-nocap`) that isolates the cap's causal role.

## The 1e-5 reference envelope (3 seeds)

Steady-state bound-motor count (last ~5 checkpoints, simT 0.15–0.30):

| seed | bound (final, simT 0.30) | fmgMean (pN) | fracOverCap |
|---|---|---|---|
| 1 | 486 | 4.42 | 0.000 |
| 2 | 465 | 4.34 | 0.000 |
| 3 | 461 | 4.55 | 0.007 |

**Reference: bound ≈ 470 ± ~12 (±3 %).** Cross-bridges sit at ~4.4 pN — about **⅓ of the 12 pN cap** — and
essentially never breach it (fracOverCap ≈ 0). This is the faithful operating point. The envelope is tight enough
that single-seed cliff probes below are conclusive (a 2.5× drop is ~40σ outside it).

## Observable-vs-dt table (matched simT = 0.30 s, seed 1; 1e-5 = 3-seed mean)

| dt | steps | bound (steady) | bound / ref | fmgMean (pN) | fmgMax (pN) | fracOverCap | capHits/step | R_g,xy (µm) | escXY | NaN | stable |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **1e-5** (ref) | 30000 | **~470 ±12** | **1.00×** | 4.4 | ~11 | 0.00 | 0.41 | 1.68 | 0 | no | ✓ |
| 1.2e-5 | 25000 | ~190 | **0.40×** | 5.0 | 13.8 | 0.005 | 1.54 | 1.68 | 0 | no | ✓ |
| 1.5e-5 | 20000 | ~47 | **0.10×** | 7.5 | 14.4 | 0.14 | 3.59 | 1.68 | 0 | no | ✓ |
| 2e-5 | 15000 | ~18 | **0.04×** | 9.1 | ~20 | 0.30 | 4.34 | 1.68 | 0 | no | ✓ |
| 5e-5 | 6000 | ~10 | **0.02×** | ~15 | ~38 | 0.45 | 4.46 | 1.69 | 0 | no | ✓ |
| 1e-4 | 3000 | ~13 | **0.03×** | ~22 | up to 84 | 0.55 | 5.87 | 1.68 | 0 | no | ✓ |

Reading the columns:
- **bound count — the limiter.** Departs the ±3 % envelope IMMEDIATELY above 1e-5: even +20 % (1.2e-5) is a 2.5×
  collapse; by 2e-5 it is a **26× collapse**. Monotone, catastrophic, far outside chaotic scatter.
- **fmgMean / fmgMax — the cause.** Mean cross-bridge tension rises monotonically and roughly **∝ dt** (4.4 → 5.0
  → 7.5 → 9.1 → ~15 → ~22 pN) — the textbook explicit-overshoot signature for a stiff spring (per-step relative
  displacement ∝ dt ⇒ spring force overshoots ∝ dt). fmgMax reaches **84 pN** at 1e-4.
- **fracOverCap — the lockstep.** The fraction breaching the 12 pN cap rises **in lockstep** with the bound
  collapse (0.00 → 0.005 → 0.14 → 0.30 → 0.45 → 0.55). The cap engagement and the binding loss are the same event
  family.
- **R_g,xy — non-discriminating here.** Global in-plane contraction is flat (~1.68 µm) at EVERY dt including the
  reference: this sparse 0.5× scene does not measurably contract its global radius of gyration over 0.3 s (the
  cross-bridge work is local). So contraction cannot serve as a convergence metric in this scene — bound count
  and cross-bridge tension are the discriminators. (Reported honestly; not a defect.)
- **escXY / NaN — stability is NOT the gate** (see below).

## Mechanism — hypothesis stated, then REFUTED-in-form / CONFIRMED-in-essence by the cap-off control

**Hypothesis (as posed):** the **12 pN cross-bridge break cap** sets the dt ceiling; binding diverges at a smaller
dt than formation or the springs because the per-step force over-drives the cross-bridge past the threshold cap,
which deterministically detaches the motor.

**Correlation (supports a cap story):** the cross-bridge tension distribution rises with dt and the fraction over
the cap rises in lockstep with the bound collapse (table above). capHits/step climbs 0.41 → 5.9.

**Control (the decisive cut) — `-nocap`, disable the 12 pN cap, isolate its causal role:**

| dt | cap | steady bound | fmgMean (pN) | fmgMax (pN) | fracOverCap | NaN/escape |
|---|---|---|---|---|---|---|
| 1e-5 | ON | 486 | 4.42 | 10.2 | 0.000 | none |
| 1e-5 | **OFF** | **505** | 5.04 | 84.8 | 0.010 | none |
| 2e-5 | ON | 16 | 9.11 | 19.3 | 0.375 | none |
| 2e-5 | **OFF** | **11** | **29.6** | 103.4 | 0.636 | none |

**The cap-off control REFUTES the narrow hypothesis.** Removing the 12 pN cap does NOT rescue binding at 2e-5
(11 ≈ the cap-on 16 — both collapsed). What the cap was doing is *masking the true cross-bridge force*: with it
off, the surviving bonds run to **fmgMean 29.6 pN** (vs 9.1 capped) and 64 % exceed 12 pN — the cap had been
shedding exactly those. So the cap is a **parallel, secondary** shedding channel, **not the gate**.

**Corrected attribution (confirmed in essence).** The release path (`NucleotideCycleSystem.catchSlipRelease`)
has TWO force-coupled channels, BOTH fed by the same dt-inflated cross-bridge load:
1. the hard cap `forceMag > 12 pN → detach` (secondary; cap-off proves binding collapses without it), and
2. the **catch-slip release** `rate = kOff·(aCatch·e^{−F·xCatch/kT} + aSlip·e^{+F·xSlip/kT})`, `P = rate·dt`,
   with `F = forceDotFil` the cross-bridge load (DOMINANT).

Channel 2 carries the collapse. Two compounding dt-effects: (a) the **linear** `P = rate·dt` (doubling dt doubles
the per-step off-probability at fixed force — would give only ~2×), and (b) the **force-exponential slip term**
`e^{+F·xSlip/kT}` fed by the dt-overshot load F — which blows up super-linearly. The observed **26× collapse at
2e-5 ≫ the ~2× from the linear term alone**, and the cap-off run's fmgMean 29.6 pN, prove (b) dominates: the
explicit-integration overshoot of the stiff cross-bridge spring inflates F ∝ dt, the slip exponential explodes,
and motors detach almost as fast as they bind.

**Net:** the ceiling-setter is the **cross-bridge force-dependent unbinding as a whole**, driven by the stiff-spring
force overshoot — not the 12 pN cap specifically. The strategic conclusion is unchanged and *strengthened*: it is a
**force-dependent / force-thresholded** process, the kind a global rescale provably cannot reach.

**Crosslink rate-discretization (the other named effect) — present but subdominant, not the ceiling-setter.** The
formation gate `pForm = 1−exp(−k_on·conc·dt·checkInt)` does leave the linear `rate·Δt≪1` regime as dt grows
(pForm 0.039 → 0.077 → 0.18 → 0.33 across 1e-5 → 1e-4) — a real discretization. But the steady crosslink count is
non-monotonic in dt (≈160 / 180 / 47 / 88 / 57 / 45), dominated by coupling to the collapsed myosin network rather
than by pForm, and in any case the binding/cross-bridge accuracy limit bites at **dt ≤ 1.2e-5**, far below where
pForm saturation (≥ 5e-5) would matter. **The cross-bridge force, not formation, sets the ceiling.**

## Stability limit vs accuracy limit (reported SEPARATELY, per the brief)

- **STABILITY limit: > 1e-4 (not reached).** Every dt up to 1e-4 is finite, bounded, no wall-escape, no blow-up —
  on BOTH cap-on and cap-off. The force-dependent releases (and the cap) *prevent* runaway by shedding over-force
  bonds; the system degrades gracefully into a sparsely-bound state rather than exploding.
- **ACCURACY limit: ≤ 1e-5 (no headroom).** The bound-motor count leaves the tight ±3 % 1e-5 envelope at the
  smallest probe above it (1.2e-5 → 0.40×). The **usable dt is the accuracy limit, 1e-5**, a factor **≥ 10× below
  the stability limit.** Judging dt by "it didn't blow up" (1e-4 is stable) would be off by 10× — exactly the trap
  this study was built to avoid.

## Implication — the lever for a larger faithful dt

Because the limiter is a **force-dependent release fed by a stiff-spring force overshoot**, and a force threshold /
force-exponential rate does not rescale with drag the way smooth overdamped relaxation does:

- **Global rescale / viscosity / parameter tuning CANNOT reach it.** Rescaling aeta (or any drag) holds the smooth
  dissipative dynamics dt-invariant but leaves the per-step cross-bridge force overshoot (∝ dt) intact — and that
  overshoot is what the release rate sees. (Empirically confirmed-by-construction: dt is single-sourced; the
  Brownian `sqrt(2kT/dt)` and chain params already track dt, yet binding still collapses — there is no global
  knob that fixes it without changing the physics.)
- **The lever is sub-stepping / an implicit cross-bridge integrator** — reduce the per-step force overshoot of the
  stiff cross-bridge spring (sub-step the cross-bridge + nucleotide-release inner loop at ~1e-5 while the rest of
  the mechanics advances at a larger outer dt, or treat the cross-bridge implicitly). That directly attacks the
  cause (the ∝ dt overshoot of F) rather than a symptom. **This is the defensible path to a larger faithful dt;
  it is a future task.**

## Caveats / notes

- **Scene-definition note.** The prior exploratory "dt=1e-4 0.5× run" was not committed; "0.5×" is here *defined*
  as the density-preserving half of the V2OneX 1× standard (box 5.0, 200 nodes, 500 fil) and used consistently
  across all dt. The verdict is scale-robust — it is a per-bond force-overshoot property, independent of bond
  count. (The prior run's "crosslink jump handful→45–64" does not reproduce as a *jump* in this scene because the
  1e-5 baseline here already carries ~160 links; the crosslink count is a confounded secondary observable, not
  the limiter — see above.)
- **Runner.** v2-GPU device-resident (5 chained TaskGraphs), 70–84 steps/s at this 0.5× scale; the 1e-5 reference
  is the slow part (~6 min/seed), the higher-dt runs cheap. The cap-overshoot mechanism is a per-bond force-law
  property identical on CPU and GPU (one physics implementation; CPU≡GPU is validated aggregate-within-SEM), so
  the divergence is not a device-path artifact. `maxF` is GPU-stale (forceSum not pulled) and unused; stability is
  read from NaN + wall-escape + fmgMax.
- **Instrument cosmetic.** The DTROW `dt=` label uses `%.0e` (rounds 1.2e-5→"1e-05", 1.5e-5→"2e-05"); the true dt
  is preserved in the run's shell-prefix tag in the raw log. Harmless to the analysis; flagged for reuse.
- **MEASUREMENT-ONLY confirmed.** `-dt`/`-dtconv`/`-seed`/`-nocap` are flag-gated and default-off; with none set
  the harness is byte-identical to the committed benchmark. No physics/rate/default changed. `-dt`'s help text now
  warns that non-default dt changes integration accuracy (validate convergence before trusting).

```
# reproduce
./run_dtconv.sh 1            # Stage 1: coarse curve, 1 seed × {1e-5,2e-5,5e-5,1e-4}
./run_dtconv.sh stage2       # envelope (1e-5 seeds 2,3) + cliff probes (1.5e-5, 1.2e-5)
./run_1x.sh -gpu -dtconv -nocap -nodes 200 -nfil 500 -box 5.0 -dt 2e-5 -seed 1 -steps 15000   # cap-off control
```
