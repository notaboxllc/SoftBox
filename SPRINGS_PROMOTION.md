# Promote pure-springs to the default canonical gliding formulation — the deterministic, transcendental-free GPU path

**Date:** 2026-07-08. Follow-on to `PURE_SPRINGS.md` + `BISTABILITY_ORIGIN.md` (the GPU LOW-basin flip is a
**deterministic PTX-scheduling artifact triggered by the `exp/log` in the `-ratefix` swing kernel**; the springs
formulation reaches identical production-dt physics with a transcendental-free **multiply** swing branch). This
session verifies and (gated) promotes springs to the default. Measurement + a `CLAUDE.md` edit + a default flip;
no physics change (springs ≡ current at production dt=refDt=1e-5); raw/ratefix retained as opt-in; `BoA-v1ref`
untouched.

---

## HEADLINE — is springs verified deterministic-and-correct, and has it been promoted?

**YES — springs is verified deterministic-and-correct, and it has been PROMOTED to the default.** GATE 1: the
only flag-dependent hot-kernel transcendental in the raw/ratefix/springs toggle is the `-ratefix` swing
`exp/log`; **springs introduces none** (swing→multiply, other coeffs host-baked bit-identical) and *removes*
the one that caused the flip. GATE 2: GPU-springs == CPU-springs, both HIGH basin, aggregate agreement ~1%
(velFitX 3.215 vs 3.180, per-bound 1.089 vs 1.093), **stable** (no NaN/blow-up across densities/coltol).
Default flipped: `PAIRS/ALIGN/STRUCT_SPRINGS = true`, `-nosprings` opt-out; **byte-identical at production
dt** (default ≡ `-nosprings` ≡ explicit-springs = velFitX 8.225 to the digit). Reversible; **flagged for jba's
re-baseline sign-off.** Springs does NOT cover the other scheduling-hazard flags (noise-correction, implicit,
canonical/cycle/freshread) — those stay CPU-arbiter-gated (GATE 3, now codified in `CLAUDE.md`).

---

## GATE 1 — hot-kernel transcendental hazard map

The reproducibility hazard is a **flag-dependent transcendental in a hot (per-step) kernel** whose PRESENCE
(not value) perturbs PTX scheduling of the surrounding float math → tips the bistable basin. Always-present
transcendentals (same op in every arm) set the absolute basin but do NOT contaminate A/B comparisons.

**Structural fact (the isolation):** every rate/spring reformulation flag EXCEPT the swing bakes its
coefficient **host-side** in `buildScene` (`chainParams`/`xbParams`/`jointParams`) — same compiled kernel,
different data. The **only** coefficient recomputed IN-KERNEL each step is the directed-swing `k`
(`CrossBridgeSystem.directedSwing:235–242`). And in the default config the raw↔`-ratefix`↔springs toggle
**adds/removes NO TaskGraph task** (DIRSWING/LYMN_TAYLOR/ADPPI_BIND/XB_IMPLICIT2 on in all three arms); the
sole difference is that swing kernel's internal branch. So the entire hazard is isolated to `:237`.

**Flag-dependent hot-kernel transcendental / structural hazards (the ones that contaminate A/Bs):**

| flag(s) | site | op | present/absent |
|---|---|---|---|
| **`-ratefix`/`-strokerate`** | `directedSwing:237` | in-kernel **exp+log** (`1−exp(·log)`) | present size-5 `[4]>0`; absent raw (size-4) / springs (multiply). **THE basin-flip hazard.** Value bit-identical 0.4f — the *instructions* perturb PTX scheduling. |
| `-alignsprings` (springs) | `directedSwing:241` | plain **multiply** | the CURE, transcendental-free (not a hazard). |
| `-bondnoise`/`-allnoise`/`-thermcorr`/`-syswide` | `scaleBoundHeadNoise:130` / `scaleMotorNoise:189` / … | `sqrt((2−α)/2)` **+ ADD tasks** | present only with the flag — the documented `-allnoise` graph-structure basin-flip artifact. |
| `-xbdash`/`-xbimplicit`/`-segimplicit`/`-canonical`/`-config1`/`-perphead`/`-twistcensus`/`-box`/`-freshread`/`-tauavg`/`-lymntaylor` | various | ADD/REMOVE/SWAP tasks (`sqrt`/`acos`/`exp`) | structural graph changes (freshread reorders the whole graph; lymntaylor removes `release`) |

Data-only (NOT hazards — same kernel, different value): `-adppibind`, `-coltol`, `-density`, `-swingkbits`,
`-alignrate`, `-filrate`, `-structrate`, `-pairsprings`, `-structsprings`.

**Always-present hot-kernel transcendentals** (set the absolute basin, identical in every arm ⇒ not A/B
hazards): Box–Muller Brownian (`brownianForce`: log/sqrt/cos/sin), catch-slip `exp(±F·x/kT)`
(`cycleLymnTaylor:43`), `bindNearest:480` `1−exp(−kOn·chord·dt)`, `accurateAcos`/`accurateAsin` in
`bondForces`/`directedSwing`/`joints`/`chainForces`, and `sqrt` normalizations throughout.

**GATE 1 verdict — CONFIRMED: springs introduces ZERO flag-dependent hot-kernel transcendental.**
`-alignsprings` takes the multiply branch (`:241`, no exp/log); `-pairsprings`/`-structsprings` bake their
coeffs host-side (`:552,553,701–703`) bit-identical to raw at refDt. Springs is the correct deterministic
production path — it *removes* the one swing exp/log that caused the flip.

**What springs does NOT cover (must stay CPU-arbiter-gated):** the Brownian-noise-correction flags
(`-bondnoise`/`-allnoise`/`-thermcorr`/`-syswide` — each adds a `sqrt` task, the `-allnoise` artifact class),
the integrator/implicit variants (`-xbimplicit*`/`-segimplicit`/`-xbdash`), and the motor/cycle/bind swaps
(`-canonical`/`-config1`/`-perphead`/`-lymntaylor`/`-tauavg`/`-freshread`). Springs is orthogonal to these;
any GPU A/B toggling one of them is graph-contaminated → GATE 3.

---

## GATE 2 — GPU-springs == CPU-springs, full-length + stable (the trust test)

Config `-full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000 -dt 1e-5`, springs
(`-pairsprings -alignsprings -structsprings`), 20k, 3 seeds each runner. per-bound = velFitX/avgBsteady.
Reference basins: **HIGH** ~velFitX 2.9–3.3 / per-bound ~0.85–1.0; **LOW** (the GPU `-ratefix` artifact)
velFitX 2.11 / per-bound 0.74.

| seed | GPU-springs (velFitX/avgB/pb) | CPU-springs (velFitX/avgB/pb) |
|---|---|---|
| 0 | 3.105 / 3.050 / 1.018 | 3.264 / 3.307 / 0.987 |
| 1 | 3.166 / 3.040 / 1.041 | 3.157 / 2.614 / 1.208 |
| 2 | 3.374 / 2.772 / 1.217 | 3.119 / 2.812 / 1.109 |
| mean | **3.215 / 2.954 / 1.089** | **3.180 / 2.911 / 1.093** |

**Same basin? YES — and the aggregate agreement is excellent.** GPU-springs and CPU-springs both land squarely
HIGH (velFitX 3.1–3.4, per-bound ~1.0–1.2) — nowhere near the LOW basin (velFitX 2.11 / per-bound 0.74). Mean
agreement: **velFitX 3.215 vs 3.180 (~1%), avgB 2.954 vs 2.911 (~1.5%), per-bound 1.089 vs 1.093 (~0.4%)** —
far within the chaotic aggregate-SEM standard (they are not bit-identical — float vs double — but the same
basin, agreeing in aggregate). **The GPU-springs path == the deterministic CPU reference. GATE 2 PASSES.**

**Stability (jba's explicit ask) — PASS.** All springs runs complete with **no NaN / no blow-up / no runaway**
over the full length (no `NaN`/`Exception`/`Infinity` in any log). Variants stable across the range: density
500 → velFitX 1.798 / avgB 0.901 (sparse), density 1000 → 3.1–3.4 / ~3.0, density 2000 → velFitX 4.638 / avgB
6.030 (dense), coltol 6 → 2.370 / 1.554. Springs at/below refDt are ≤ current stiffness ⇒ at least as stable —
confirmed, not assumed.

---

## GATE 3 — the CPU-arbiter standing rule (codified in CLAUDE.md)

Added to `CLAUDE.md` (the failure mode was *silent* — a wrong GPU basin masqueraded as a stable baseline
across a dozen tables). The rule: a GPU gliding result needs a CPU-arbiter cross-check when (a) it is an A/B
whose arms differ in hot-kernel structure (any flag on the GATE-1 hazard map), (b) it is an absolute number
used for validation / a reported result, or (c) as a periodic baseline spot-check. Smallest scale/window that
resolves the basin. GPU = fast exploration; CPU = basin arbiter. **APPLIED** — added to `CLAUDE.md` under the
Architecture invariants (the "GPU-number trust rule", next to the CPU≡GPU validation standard), with the
explicit list of hazard flags from GATE 1.

---

## PROMOTION — flip springs to the default canonical formulation

**Blast radius (investigated, contained).** The springs flags (`PAIRS_SPRINGS/ALIGN_SPRINGS/STRUCT_SPRINGS`)
are read ONLY in `buildScene` (`GlidingHarness.java:552,553,653,688,698`). `buildScene` is used by the main
gliding run and `singleRun` (calibration) — **bit-identical to raw at production dt** (springs ≡ raw at
refDt=1e-5). The fine-dt diagnostic modes (`decompRun`/`headTiltSweep`, dt=1e-6) build their OWN scene (set
`chainParams` directly) and are **UNAFFECTED**. Other harnesses are separate mains. ⇒ flipping the default is
byte-identical everywhere at production dt; the only change is the dt-convergence reference (which *should*
move to the springs continuum — the canonical dt-convergent, transcendental-free formulation, replacing the
LOW-basin-seeding `-ratefix`).

**The flip (APPLIED):** `PAIRS_SPRINGS/ALIGN_SPRINGS/STRUCT_SPRINGS` default **true**
(`GlidingHarness.java:150–152`); new `-nosprings` opt-out clears all three (`:245`); `-ratefix`/`-structrate`/raw
retained (used with `-nosprings`) for comparison; disclosures print "SPRINGS DEFAULT-ON" + a note when a rate
flag is redundantly set (overridden below refDt). Cleanly reversible (three booleans / one flag). **Verified
byte-identical at production dt:** default ≡ `-nosprings` ≡ explicit `-pairsprings -alignsprings -structsprings`
= velFitX 8.225 / avgBsteady 4.000 / netX −6.341 to the digit (`-full`, dt=1e-5, seed 0, 500 steps). Springs
flags are read ONLY in `buildScene`, so other harnesses and the fine-dt `decompRun` modes are untouched.

**Re-baseline note (planner/PI sign-off — flagged for jba):**
- **Numerically identical at production dt=1e-5** ⇒ all prior validation numbers **stand at production dt** (the
  refDt coincidence).
- The **fine-dt convergence target moves to the springs continuum** (differs from the rate continuum by the
  `−ln(1−frac)/frac` factor) — the moved reference.
- The GPU gliding baseline is now the transcendental-free HIGH basin (velFitX ~3.1, per-bound ~1.0), NOT the
  `-ratefix`-seeded LOW basin (2.11/0.74) of the prior tables.
- Reversible; **jba confirms or reverts.**

---

## DEFERRED — filament retuning (tracked, NOT done this session)

Springs reinterpret the fraction-per-step coefficients as fixed stiffnesses. At production dt they are
numerically identical to current ⇒ **no retuning needed now**. Retuning becomes necessary only if/when (a)
production runs **below refDt** (springs and rate continua diverge), or (b) the fixed stiffnesses are given
**physical meaning** (calibrated to filament persistence length / bending modulus, not "whatever reproduces
production"). Tracked as future work; not opened this session.

---

## Reproduce
```
./run_gliding.sh -swingkprobe / -gpu -swingkprobe   # the swing coeff is bit-identical 0.4f (BISTABILITY_ORIGIN)
run_promo_gate2_gpu.sh   # GPU-springs 3 seeds + stability variants
run_promo_gate2_cpu.sh   # CPU-springs 3 seeds (basin arbiter)
# after promotion: default runs use springs; -nosprings restores raw; -nosprings -ratefix -structrate = old ratefix
```
Logs `RUN_LOGS/2026-07-08_promo_gate2_*.txt`.
