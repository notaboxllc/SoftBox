# Phase-2 ATP-RELEASE COUPLING — a bound head that reaches ATP DETACHES (ATP-binding = detachment); the sustained bound-in-ATP invariant is now ENFORCED (config-1 / perp-head)

**2026-06-29. FIX (authorized).** Couples detachment to the ATP state in the config-1/perp-head gliding cycle:
a bound head that ends the nucleotide cycle in the ATP state detaches. Flag-scoped (`-config1`/`-perphead`,
default-on; A/B control `-noatprelease`). Bind side untouched, no rate/calibration/geometry retuning.
Default / canonical-non-config1 / v1 paths **byte-unchanged** (`NucleotideCycleSystem.cycle` literally
unchanged — 0 deletions). `BoA-v1ref` read-only / byte-clean. Re-baselines duty/avgBound/velocity for the
config-1/perp motor (the deliberate, authorized consequence). Full single-molecule / step∝lever / SET-A
re-validation is the SEPARATE next step.

---

## TL;DR — the invariant holds (bound-in-ATP → 0), on BOTH runners

| metric (v1box, dt=1e-6, 40k) | **perphead pre-fix** (`-noatprelease`) | **perphead FIX** | runner |
|---|---|---|---|
| **bound-in-ATP** | 65.9 % (CPU) / 72.5 % (GPU) | **0.0 % (CPU) / 0.00 % (GPU)** | both |
| **avgBound** | 26.2 (CPU) / 36.7 (GPU) | **4.03 (CPU) / 4.00 (GPU)** | both, **CPU≡GPU** |
| bound-state ADP | 32.5 % | **93.9 %** (the strained, force-generating state) | CPU |
| catch-slip release rate | 135 /s (≈ the 100 /s unloaded floor) | **548 /s** (now load-driven) | CPU |
| mean bound lifetime | 7.42 ms | **1.82 ms** (≈ the productive ADPPi→ADP→NONE duty) | CPU |
| net glide | −4.70 (CPU) / +0.06 (GPU, grip-locked) | **−1.38 (CPU) / −2.05 (GPU)** | both |

The fix detaches a bound head as soon as it is in the ATP state. The bound population is now **ADP-dominated
(93.9 %)** — exactly the strained, force-generating state the calibrated Guo–Guilford catch-slip is meant to
govern, and the catch-slip is now genuinely exercised (release 548 /s, a real forceDotFil distribution, no
longer pinned slack at the 100 /s floor). avgBound collapses 26→4 (CPU) / 37→4 (GPU) — CPU≡GPU agree (4.03 vs
4.00). Net glide flips from grip-locked (~0) to directed (−2 µm/s). **config-1** behaves the same (bound-in-ATP
0.0 %, avgBound 2.91) and, as a bonus, its dt=1e-6 numerical whip is **gone** (velocity NaN → +1.83 µm/s) — the
over-binding was feeding the stiffness blow-up.

---

## 1. The fix — `NucleotideCycleSystem.cycleAtpDetach`

A copy of `cycle()` whose state machine (NONE→ATP→ADPPi→ADP→NONE) is **byte-for-byte identical** (same RNG
salt/draw, same branches), with one addition at the end of each motor's update:

```java
nucleotideState.set(m, state);
// ATP-binding IS detachment — "no bound head persists in ATP". A bound head that ENDS this cycle in ATP
// detaches now, reusing the EXACT FREE_COOLDOWN/refractory of catchSlipRelease (refractorySteps=kinParams[10]).
if (bound && state == MotorStore.NUC_ATP) {
    if (refractorySteps > 0) { boundSeg.set(m, FREE_COOLDOWN); cooldown.set(m, refractorySteps); }
    else { boundSeg.set(m, FREE_BINDABLE); }
}
```
`bound` is the **start-of-cycle** bound flag (a head freed by the catch-slip earlier this step, boundSeg=−2,
is not re-touched). The head then hydrolyzes / re-primes **off** the filament (to the absorbing ADPPi state,
`offFilADPPi_ADP=0`) and rebinds primed via the normal geometric search.

**What is NOT touched** (verified): the catch-slip release runs BEFORE the cycle and is unchanged — it still
governs release-vs-stay during the strained ADP dwell (this adds the ATP terminus AFTER it, does not replace
it). The bind side is unchanged (no state gate, no nucleotide reset at bind). The powerstroke (ADPPi→ADP, the
J1 0°→60° switch in the bond kernel) is unchanged.

Routing (GlidingHarness, both runners): `if (CONFIG1 && ATP_RELEASE) cycleAtpDetach(…) else cycle(…)` — at
`stepOrig` (CPU) and the default branch of `buildPlan` (GPU). `cycle()` is otherwise called verbatim by every
other path (default, canonical-non-config1, `-freshread`, and the single-molecule/binder-diagnostic assays).

## 2. Why "detach AT the NONE→ATP transition" (Form A) ALONE was insufficient — the rebind-in-ATP hole

The audit (§7) proposed Form A: detach a bound head AT its NONE→ATP transition. Implemented literally, it only
**partially** worked — **perphead bound-in-ATP fell 65.9 %→56.9 % (CPU) and 72→74.5 % (GPU); avgBound 26→17.7
(CPU) / 38 (GPU)** — the invariant was NOT met. Root cause (a real, measured finding): Form A leaves the
detached head **in the ATP state** (unbound), and in the dense gliding bed (500/µm²) it **rebinds
geometrically before completing its ~10 ms off-filament ATP→ADPPi recovery** — so it rebinds *still in ATP*,
and no NONE→ATP transition ever fires for it again to trigger the Form-A detach. The audit's expectation that
"rebinding requires the primed ADP·Pi state" does not hold at the actual rates (recovery 10 ms ≫ rebind time).
There are **two** entries to bound-in-ATP — the cycle (NONE→ATP) AND a bind-in-ATP — and Form A covers only the
first.

The unified rule ("a bound head that IS in ATP detaches") covers both, with **zero window** in either case,
because **`cycle` runs AFTER `bind` in the gliding step order** (release → bind → snapPerp → **cycle** → bond):
a head that binds-in-ATP this step is detached by the cycle the same step, BEFORE the bond kernel — so it never
applies force while bound-in-ATP. This is the EXIT rule (the ATP state is incompatible with being bound), not a
bind-side state gate: the geometric binder is unchanged (a head may still bind in ATP; it is ejected before
the bond).

## 3. Atomicity — CPU≡GPU, no clobbered flag

The detach writes `boundSeg` (the same field the catch-slip already writes); each motor owns its slot
(race-free). On the GPU graph the `cycle` task runs after `bind` and before `bond`/`snapCanonicalHead`, and **no
later kernel writes `boundSeg`** (`bond`/`register`/`snapCanonicalHead` only read it; `snapCanonicalHead` guards
`boundSeg<0`) — so the freed flag is not overwritten (the body-pose-late rule is respected; `cycle` writes a
flag, not a body pose). The RNG draw is byte-for-byte `cycle()`'s ⇒ the state transitions are bit-identical to
`cycle()` and the only behavioral delta is the added `boundSeg` write. **Result: bound-in-ATP = 0.0 % on CPU
AND 0.00 % on GPU; avgBound 4.03 (CPU) vs 4.00 (GPU)** — aggregate-within-SEM, the standing chaotic-dynamics
CPU≡GPU standard. Device throughput unchanged (404 steps/s @ 2000 motors).

## 4. Regression — the full table (v1box, dt=1e-6, 40 000 steps)

**A/B is a same-build flag flip** (`-noatprelease` = the old decoupled cycle): the OFF run reproduces the audit
**exactly** (perphead CPU 65.9 % ATP / avgBound 26.17 / −4.703 µm/s), proving fix-OFF is byte-identical to
pre-fix.

| run | bound-in-ATP | avgBound | ADP % | catch-slip release | net glide | NaN |
|---|---|---|---|---|---|---|
| perphead pre-fix CPU (`-noatprelease`) | 65.9 % | 26.17 | 32.5 % | 135 /s | −4.70 | no |
| **perphead FIX CPU** | **0.0 %** | **4.03** | 93.9 % | 548 /s | −1.38 | no |
| perphead pre-fix GPU (`-noatprelease`) | 72.5 % | 36.70 | — | — | +0.06 | no |
| **perphead FIX GPU** | **0.00 %** | **4.00** | — | — | −2.05 (steady −2.23) | no |
| config1 pre-fix CPU (audit) | 37.5 % | 9.27 | 62.0 % | 99 /s | NaN (whip) | yes |
| **config1 FIX CPU** | **0.0 %** | **2.91** | 93.4 % | 678 /s | +1.83 | **no** (whip gone) |
| default motor CPU (byte-unchanged path) | 70.3 % | 18.08 | 27.5 % | 330 /s | −5.32 | no |

- **bound-in-ATP → 0** (the invariant, pass) on both runners and both motors.
- **avgBound drops** 26→4 / 37→4 (perphead), 9→3 (config1) — below v1's ~7.6 (reported, not tuned).
- **catch-slip now exercised:** the bound set is ADP-dominated (93.9 %) — the strained force-generating state;
  release rate rose 135→548 /s (a real load-driven rate, not the 100 /s unloaded floor), forceDotFil now a
  genuine signed distribution (mean −0.08 pN, ~48 % resisting). The calibrated Guo–Guilford bond is doing work.
- **directed glide improved** (perphead, GPU `-grid`, same v1-style estimator as the pre-fix number):
  **velFitX 0.46 → 1.93 µm/s, netX −0.57 → −2.05, avgBound ~42 → 3.7/4.5, instSpeed 14.4 → 20.0** — the
  grip-locked tug-of-war eased into a ~4×-stronger directed −x glide (still below the default's ~3.5 / v1's
  8.33; REPORTED, not tuned to a target).
- **Default v1 motor still glides** (−5.32 µm/s) — its path calls the unchanged `cycle` (it also over-binds at
  dt=1e-6, an out-of-scope property of the shared cycle; the fix does not touch it, as required). The default
  is byte-unchanged by construction (it never enters the `CONFIG1 && ATP_RELEASE` branch; `-noatprelease` is a
  no-op for it).

## 5. Scope, flags, what is deferred

- **Flag-scoped:** `ATP_RELEASE` default-on for CONFIG1 (so `-config1` and `-perphead` gliding inherit it);
  `-noatprelease` disables it (the A/B control). Non-CONFIG1 paths never read it.
- **Byte-unchanged:** `NucleotideCycleSystem.cycle` (0 deletions); the default/canonical-non-config1/`-freshread`
  GlidingHarness paths; the single-molecule (`singleStep`) and binder-diagnostic (`canonDiag`) assays (still on
  plain `cycle` — their re-validation is the deferred next step); every other harness (they call `cycle`).
  `BoA-v1ref` byte-clean.
- **Deliberate v1 divergence (recorded):** this switches on v1's commented-out ATP-coupled detach
  (`MyoFilLink.ckRelease` `isATP()?20000:0`); v1's active path is force-only. Consistent with "the motor
  cross-bridge is exempt from v1 bit-parity; v2's canonical motor is the reference" (CLAUDE.md,
  `CANONICAL_MOTOR_FINDINGS`).
- **Deferred (the separate next step):** single-molecule duty / catch re-check, step∝lever, thermal tail, the
  SET-A density sweep — all re-baseline against the new duty cycle. The config-1 dt=1e-6 whip is incidentally
  tamed here but its proper fix (implicit/sub-step J1) is unchanged and still pending.
- **New measurement instrumentation (no physics):** `GlidingHarness.boundInState` + a device-side bound-in-ATP
  readout in `gpuProbe` (pulls `nucleotideState` at sample cadence) — the GPU invariant assertion.

## 6. CPU-fallback disclosure
CPU `-diag` runs: host-side `stepOrig`, no TaskGraph (2000 motors × 40k steps, ~2 min). GPU runs:
device-resident `buildPlan` (~24 kernels, no per-step host pull; host reads boundSeg+nucleotideState at the
M/10 sample cadence only), 404 steps/s. Both runners exercised for the invariant + the A/B control.

## Reproduce
```
./run_gliding.sh -diag -perphead -v1box -dt 1e-6 40000                 # FIX: bound-in-ATP 0.0%, avgBound 4.03
./run_gliding.sh -diag -perphead -noatprelease -v1box -dt 1e-6 40000   # A/B control: 65.9% / 26.17 (= the audit)
./run_gliding.sh -gpu  -perphead -v1box -dt 1e-6 40000                 # GPU invariant: device bound-in-ATP 0.00%, avgBound 4.00
./run_gliding.sh -diag -config1  -v1box -dt 1e-6 40000                 # config1 FIX: 0.0%, avgBound 2.91, whip gone
```
