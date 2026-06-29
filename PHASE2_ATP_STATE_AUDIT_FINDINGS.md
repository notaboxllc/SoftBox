# Phase-2 ATP-STATE AUDIT — heads are SUSTAINED bound-in-ATP; the nucleotide clock and the detachment clock are DECOUPLED (root cause found; fix PROPOSED, NOT applied)

**2026-06-29. MEASUREMENT / AUDIT ONLY.** Instrumented the gliding ensemble (CPU `stepOrig`, dt=1e-6, v1box
2000 motors, 40000 steps) for `-config1` and `-perphead`, traced the cycle-advance + detachment code paths in
v2 and `BoA-v1ref`, and root-caused jba's observation. **No fix applied** (it re-baselines duty / velocity /
avgBound — planner sign-off required). `BoA-v1ref` byte-clean / read-only; no production path touched.

---

## TL;DR — the lead hypothesis is CONFIRMED at the code level AND empirically

The motor has **two clocks that biology fuses into one**:

1. the **nucleotide state machine** (`NucleotideCycleSystem.cycle`) advances NONE→ATP→ADPPi→ADP→NONE on its
   own schedule; it **never writes `boundSeg`**; and
2. the **detachment** (`NucleotideCycleSystem.catchSlipRelease` + the 4a `bindKinetics`) clears `boundSeg`
   **only** via the mechanical catch-slip rate (gated on the J1-lever-strain `forceDotFil`) + the optional
   12-pN break-force cap; it **never reads `nucleotideState`**.

In real myosin **ATP binding IS detachment** (one event). Here the two are independent: a bound head reaches
the ATP state and **nothing detaches it**. It then sits strongly bound through the entire ATP→ADPPi
dwell — which, with the calibrated v1 rates, is the **longest dwell in the whole cycle (10 ms on-filament)**.
Because the only detachment is mechanical and the config-1/perp geometry keeps the J1 strain ≈ 0
(forceDotFil ≈ 0.04 pN), the catch-slip release sits at its **unloaded floor (~100 /s)**, so heads almost
never release and persist across many full nucleotide cycles. The visible symptom is **sustained
bound-in-ATP** (jba's "many heads bound and yellow"); the systemic symptom is the **over-binding** (avgBound
26–42 vs v1 ~7.6) and the grip-locked low glide.

**This is a faithful port of v1's ACTIVE simplification, not a v2-introduced bug** — v1's live `ckRelease`
is also force-only and never reads the nucleotide state (v1's ATP-coupled detach is a **commented-out**
variant). v1 masks the leak in its default geometry, where the cross-bridge generates real load so the
force-driven release fires fast; the perp/config-1 low-strain geometry **unmasks** it.

---

## 1. The observation (jba, viewer)
Many heads render **bound AND in the ATP state (yellow)** for a sustained time — heavily in `-perphead`, a
few in `-config1`. A strongly-bound head in ATP is a biochemical contradiction (reaching ATP *is* leaving).

## 2. The two-clocks decoupling — the code trace (the root cause)

**Binding sets `boundSeg`, never the nucleotide state.** Every binder
(`BindingDetectionSystem.bindNearest` / `bindRate` / `bindCanonicalTwoPoint`) writes only `boundSeg` +
`bindArc`. A free head binds **in whatever nucleotide state it currently holds** (the cycle keeps running
off-filament too). No coupling at bind.

**The cycle advances the nucleotide state, never `boundSeg`** (`NucleotideCycleSystem.cycle`, lines 43–68):
it reads `bound = boundSeg.get(m) >= 0` only to **choose the on-/off-filament rate**, and writes only
`nucleotideState`. Reaching NUC_ATP does nothing to the bond.

**Detachment is purely mechanical, never reads the nucleotide state** (`catchSlipRelease`, lines 88–134):
the only writes of a free sentinel are
```
boundSeg.set(m, FREE_COOLDOWN | FREE_BINDABLE)
```
guarded by (a) the break-force cap `forceMag > breakForceN` (off by default), and (b) the catch-slip draw
`u < kOff·(αCatch·e^(−F·xCatch/kT) + αSlip·e^(+F·xSlip/kT))·dt`, with **F = forceDotFil** (the signed J1
lever strain). `nucleotideState` is **absent** from this method.

**Grep proof (whole codebase):** every `boundSeg.set(…, FREE_*)` lives in `bindKinetics` (4a) or
`catchSlipRelease`; **no detach anywhere is keyed on `NUC_ATP` / `isATP` / the nucleotide state.** The two
clocks never meet.

**The catch cannot even SEE the ATP state.** In config-1/perp, `forceDotFil` = the signed J1 lever strain
`(κ/L)·(θ_rest − θ)` with `j1Rest = 60°` unless ADPPi (then 0°). So **NONE, ATP and ADP share the same rest
angle (60°) and therefore the same strain** — the catch-slip rate is *identical* whether the head is in
rigor (NONE), ATP, or ADP. The detachment mechanism is structurally blind to ATP-binding.

## 3. The dwell-time arithmetic — why ATP is where heads pile up

v2 wires the exact v1 on-filament rates (`MotorStore.setNucParams`, = `Env.java:836–855`). Mean dwell of a
**continuously-bound** head in each state:

| transition (bound) | rate | mean dwell @ dt=1e-6 |
|---|---|---|
| NONE→ATP | atpOnMyo 2e4 /s | 50 µs (50 steps) |
| **ATP→ADPPi** | **myoOnFilATP_ADPPi 100 /s** | **10 ms (10 000 steps)** ← rate-limiting |
| ADPPi→ADP (the powerstroke) | myoOnFilADPPi_ADP 1e4 /s | 100 µs |
| ADP→NONE (load-gated) | myoOnFilADP_None 1e3 /s | 1 ms (when gate open) |

The **ATP dwell is 100–200× longer than every other state.** A head that should have detached at the
NONE→ATP transition instead spends ~10 ms strongly bound in ATP. (Biologically that 10 ms hydrolysis wait is
exactly the *detached*, weakly-bound search phase — here it happens gripped to actin.) Off-filament the
picture also fits: `offFilADPPi_ADP = 0`, so a free head walks NONE→ATP→**ADPPi (stuck, primed)** and tends
to **bind already-in-ATP-or-ADPPi**, then never leaves.

The mechanical floor coincides: the unloaded catch-slip rate is `kOff·(αCatch+αSlip) = 100·(0.92+0.08) =
100 /s` ⇒ a **10 ms** unloaded bound lifetime — the same order as the ATP dwell. So when the J1 strain is
≈ 0, the head's mechanical lifetime is no shorter than one ATP dwell, and it rides the whole cycle.

## 4. EMPIRICAL CONFIRMATION (instrument: `GlidingHarness -diag`, CPU, dt=1e-6, v1box, 40 000 steps)

`-diag` counts bound-by-state occupancy, mean bound lifetime, the catch-slip release rate, and the J1
strain, post-warmup (warm=2000). Pure measurement, no code change.

| metric | **`-config1`** | **`-perphead`** | biology |
|---|---|---|---|
| avgBound | 9.27 | **26.17** | ~7.6 (v1) |
| bound-state: **ATP** | **37.5 %** | **65.9 %** | **≈ 0 %** (ATP = detached) |
| bound-state: ADP | 62.0 % | 32.5 % | — |
| bound-state: ADPPi / NONE | 0.5 % / 0.0 % | 1.1 % / 0.5 % | — |
| forceDotFil (the catch input), mean | NaN¹ | **3.81e-14 N (≈ 0.04 pN)** | — |
| mean bound lifetime | 10 070 steps (10.07 ms) | 7 421 steps (7.42 ms) | — |
| catch-slip release rate | **99 /s** | **135 /s** | — |

¹ config-1's full gliding ensemble **numerically blows up at dt=1e-6** (velocity −5.8e7 µm/s, forceDotFil
NaN) — the known config-1 uncapped-Hookean-J1 whip (`PHASE2_SETA_DENSITY_SWEEP_FINDINGS`), a *separate*
instability. It does **not** affect the integer state-occupancy / lifetime counts, which are clean and make
the point; `-perphead` is the stable, decisive demonstrator.

**Reading:**
- **The catch sits at its unloaded floor.** Release rate 99–135 /s ≈ the F=0 floor of 100 /s; forceDotFil
  ≈ 0.04 pN. The mechanical lifetime is *not shortened by load* — heads release only at the slow biochemical
  floor, so they persist across the cycle. This is the `PHASE2_PERP_HEAD_FINDINGS` note ("the catch reads the
  J1 strain, which stays low; heads rarely release") quantified.
- **Sustained, not a flicker.** Per bound episode a typical head spends ≈ occupancy × lifetime in ATP:
  **perphead ≈ 0.659 × 7.42 ms ≈ 4.9 ms (~4 900 steps); config1 ≈ 0.375 × 10.07 ms ≈ 3.8 ms (~3 800
  steps).** Thousands of consecutive steps bound-in-ATP — emphatically sustained (a benign transient would be
  1–few steps).
- **config1 (few) vs perphead (many) = the SAME leak, wider in perp.** The decoupling is present in both;
  perp's ⊥-orientation torque pins the head and drives the *lever* (not the J1 hinge), so the J1 strain — and
  thus the catch input — is even closer to zero ⇒ release nearer the floor ⇒ MORE ATP occupancy (65.9 % vs
  37.5 %) and higher avgBound (26 vs 9). In config1 a chunk of the bound population is instead held in **ADP**
  by the load-gate (62 %), so its visible ATP fraction is lower — the leak is partly *masked*, not absent.

## 5. v1 contrast — v1 has the SAME decoupling in its ACTIVE path; it masks the leak by force-driven release

v1 `MyoMotor.biochemStep` (`BoA-v1ref/boxOfActin/MyoMotor.java:221–238`) is structurally identical to v2's
`cycle` — it advances `nucleotideState` and **never touches `onFil`**. v1's detachment lives in
`MyoFilLink.ckRelease` (`:318–361`), and the **active** body is exactly what v2 ported:
break-force cap → `inRigor` gate → Guo–Guilford catch-slip on `forceDotFil`. **It does not read the
nucleotide state.** So v1's live motor also runs decoupled clocks — and v2 is faithful to it.

The ATP-coupled detach *exists in v1, but is commented out* — `MyoFilLink.java:381–387`:
```java
/* public void ckRelease () {      // "biochem only... ie no force dependence"
     double releaseRate = myMotor.isATP() ? 20000.0 : 0;   // ATP ⇒ fast detach, else never
     if (rng < releaseRate*dt) release();
   } */
```
This is the canonical "ATP binding = detachment" coupling — and v1 chose the **force-only** variant instead.
(A middle variant at `:363–379` keeps force-dependence but *modulates* it by `notATP()` via
`notATPMyoReleaseMod` — also dead.) So:

- **v1 does NOT couple ATP→detach in its production path** — same architecture as v2. v1 therefore also has
  heads transiting ATP while bound.
- **v1 avoids the pathology** because in its *default* (non-canonical, non-perp) geometry the cross-bridge
  develops real signed load, so the force-driven catch-slip release fires fast and the calibrated mechanical
  lifetime gives avgBound ≈ 7.6. Each bound episode is **short**, so a head is mechanically ejected long
  before it can sit out a 10 ms ATP dwell — the leak is masked by a healthy release rate.
- **v2's perp/config-1 geometry removes that mask:** the head is pinned ⊥ (or two-point) and the J1 strain
  the catch reads stays ≈ 0, so the mechanical lifetime relaxes to the 10 ms floor and the ATP dwell becomes
  visible as sustained bound-in-ATP + over-binding.

So the bug is **architectural (decoupled clocks), exposed by a low-strain geometry**, not a miswired rate or
a missing call relative to v1's active code.

## 6. The candidate sub-causes (all addressed)

- **Two clocks that should be one** — ✅ **the root cause.** Confirmed by code (§2) + occupancy (§4).
- **Detachment gated on a strain threshold the geometry rarely meets** — ✅ contributing/amplifying.
  forceDotFil ≈ 0.04 pN keeps the catch at its 100 /s floor (§3–4). This is *why the leak is wide here* but
  not the leak itself (even a healthy catch wouldn't enforce ATP→detach; it would just eject heads on force).
- **ATP reached but the detach call missing / mis-ordered** — ✅ confirmed: there is **no** ATP-coupled detach
  call anywhere (grep, §2). Not a mis-order — it was never written into the active path (it's v1's commented
  variant).
- **GPU task-ordering / a clobbered bound-flag** — ❌ ruled out. The bug is in the algorithm
  (`cycle`/`catchSlipRelease`), identical on both runners; the instrument here is the CPU runner and it
  reproduces the over-binding + ATP occupancy. No runner dependence (consistent with the standing CPU≡GPU
  aggregate-within-SEM result). The body-pose-late rule is irrelevant — no detach is being overwritten
  because no ATP-detach is ever issued.

## 7. PROPOSED FIX (NOT applied — re-baselines duty/velocity/avgBound; planner sign-off required)

**Couple detachment to the ATP-binding event, as biology has it.** Concretely, two equivalent forms:

- **(A) Detach at the NONE→ATP transition (primary, most faithful).** In `cycle`, when a **bound** head fires
  NONE→ATP, *also* set `boundSeg` to the free sentinel (the same FREE_COOLDOWN/refractory the catch-slip uses)
  — ATP binding *is* the detachment. The head then hydrolyzes/re-primes **off** the filament (it walks to the
  stuck-ADPPi primed state) and rebinds via the normal geometric search. This restores the duty cycle:
  strongly bound only during ADPPi→ADP→NONE (the force-generating part), detached during the 10 ms
  ATP→ADPPi search.
- **(B) Equivalent: a deterministic release-on-ATP in `catchSlipRelease`** — if `nucleotideState == NUC_ATP`,
  detach this step (then fall through to the existing refractory). Same effect, localized to the detach
  kernel; pick whichever the planner prefers for graph/ordering cleanliness. (Form A is closer to v1's
  commented variant-3 in spirit; both make ATP-binding and detachment one event.)

**Do NOT instead "gate ATP-binding on being unbound"** (stall the head in NONE until the catch fires): that
freezes a head in rigor (NONE) indefinitely, which is the opposite non-physical artifact.

**Expected consequence (why it needs sign-off, not a quiet edit):** the duty cycle collapses from "bound
through the whole cycle" to "bound only for ADPPi+ADP+NONE ≈ 1.15 ms out of an ~11 ms cycle" — a **duty ~10 %**,
so **avgBound should drop sharply** (toward the v1 ~7.6 regime / lower) and the grip-lock should ease,
plausibly recovering glide. It **re-baselines** every calibrated emergent number — duty, velocity, avgBound,
and the `PHASE2_CATCH_*` / `PHASE2_KON` distributions — so it must be a deliberate, signed-off step, not a
side-effect.

**Faithfulness note for the planner.** This fix **diverges from v1's active path** (it switches on v1's
commented-out ATP-coupled detach). That is consistent with the standing posture that the **motor cross-bridge
is exempt from v1 bit-parity** and **v2's canonical motor is the reference** (CLAUDE.md, `CANONICAL_MOTOR_FINDINGS`):
v1's force-only release was tuned for v1's default geometry; the canonical/perp motor needs the duty cycle
that the ATP→detach coupling provides. Worth confirming whether, with ATP→detach on, the **default v1 motor**
still reproduces its gliding fixture (it should — its force-driven release already ejects heads before the ATP
dwell, so the coupling is mostly redundant there; verify, don't assume).

## 8. CPU-fallback disclosure
All instrumentation here is the **CPU `-diag` runner** (host-side `stepOrig`, no TaskGraph) — the intended
debug/measurement path; chosen so dt=1e-6 numerics + the per-step state read are not a confound. 2000 motors ×
40 000 steps, ~2 min each. No GPU run, no production path executed. The over-binding being reproduced on CPU
(avgBound 9.3/26.2) corroborates the GPU `-perphead` observation (~42) as a runner-independent algorithmic
effect.

## Reproduce
```
./run_gliding.sh -diag -perphead -v1box -dt 1e-6 40000   # 65.9% bound-in-ATP, avgBound 26, release ≈ floor
./run_gliding.sh -diag -config1  -v1box -dt 1e-6 40000   # 37.5% bound-in-ATP, avgBound 9  (velocity blows up — config-1 whip)
```
