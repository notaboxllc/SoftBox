# Increment 7 — TREADMILLING STEADY STATE vs first-principles balance — FINDINGS

**Status: DONE (2026-06-19). VALIDATION PASS (CPU + GPU).** The growth+depoly **coupling** produces a
first-principles-correct treadmilling steady state: **[actin] converges to the critical concentration
C_c = k_off1/k_on, independent of initial conditions and total actin; L bounds at the conservation-consistent
L_ss** — confirming turnover **bounds filament length** (the monotonic-growth overrun fix). Adjudicated against
**physics** (CLAUDE.md §8), not v1 numbers. **MEASUREMENT only — no physics change; no default flip.** Run:
`./run_treadmill.sh [-cpu]`. Log: `RUN_LOGS/2026-06-19_treadmill_steadystate.txt`.

## The prediction — computed BEFORE measuring
A single formin filament with barbed growth (rate `= k_on·[actin]`, first-order) + pointed depoly (fixed
`k_off1`) in a **closed finite pool** reaches treadmilling steady state when growth = depoly:
- **`[actin]_ss = C_c = k_off1 / k_on`** — the critical concentration, set by the **rate balance**, INDEPENDENT
  of total actin or starting length.
- **`L_ss`** by conservation: `L_ss·µMperMon = total_actin − C_c` (more total ⇒ longer L_ss, **same C_c**).

With the build constants **`k_on = kATPOn2WithFormin = 11.6 µM⁻¹s⁻¹`**, **`k_off1 = kATPOff1 = 0.8 s⁻¹`**:
> **C_c = 0.8 / 11.6 = 0.068966 µM**  (the ideal continuum prediction).

**Discrete-model granularity correction (also first-principles, NOT a fit).** The implementation removes monomers
in segment-granular units with a death floor: a pointed segment **born at `stdSegLength = 32`** depolymerizes via
**`32 − (actinSeed−1) = 30`** rate-`k_off1` events down to `monomerCount = 2`, then **DIES returning the last 2
monomers en masse** (not at rate `k_off1`). So the effective off-rate is **×32/30** faster ⇒
> **C_c_eff = (stdSegLength / (stdSegLength − (actinSeed−1)))·C_c = (32/30)·0.068966 = 0.073563 µM.**

This is computed from fixed model constants (`stdSegLength`, `actinSeed`), not fitted. The measurement is
adjudicated against **both**: it should land at **C_c_eff** (the discrete model), ≈ +6.7% above the ideal **C_c**.

## Assay (measurement only; mechanisms already built)
ONE fixed node, ONE formin filament, growth ON + depoly ON, **closed pool** (box volume V = 1.0 µm³ ⇒
`µMperMon = 1.6606e-3 µM/monomer`; default `k_off1 = 0.8/s`). The steady state is a property of the biochem
balance, so the scalar `[actin]`/`L` are measured from the cadence loop (grow + split + depoly + death; the
per-step mechanical dynamics don't touch `monomerCount`/pool). `[actin] = pool.conc()`; `L = Σ monomerCount`.
Relaxation rate `a = k_on·µMperMon·biochemΔt ⇒ τ = 1/a ≈ 51914 cadences` (1 cadence = biochemΔt = 1e-3 s).

The approach is a **damped overshoot oscillation** (barbed growth responds instantly to [actin]; pointed depoly is
[actin]-independent ⇒ the length overshoots, then the pointed end trims it). So steady-state means are taken over
a long post-convergence window (≥12τ) and averaged over several seeds (single filament ⇒ ±√p_ss Poisson scatter
on the free-monomer count, ~±15% instantaneous).

## Results

### Convergence trace (single run, total = 350 monomers; cadence/τ : [actin] µM : L monomers)
```
 0.0τ 0.564596  10     4.0τ 0.061441 313 (overshoot)   8.0τ 0.069744 308
 2.0τ 0.137828 267     6.0τ 0.071405 307             ≥6τ: damped oscillation around C_c_eff
```
Steady ⟨[actin]⟩ over the last 8τ = **0.072542 µM** (C_c 5.2%, **C_c_eff 1.4%**); conservation EXACT.

### Gate 1 — convergence from BOTH directions (same total = 350, n=8 seeds, 22τ, last 13τ)
| start | [actin]_ss (µM) | vs C_c | vs C_c_eff | L_ss (mono) |
|---|---|---|---|---|
| short (10 mono, **grows**)   | 0.073662 | 6.8% | **0.1%** | 306 |
| long  (341 mono, **shrinks**)| 0.074106 | 7.5% | **0.7%** | 305 |

**Both initial conditions converge to the SAME steady [actin]** (agree to **0.6%**) and the **same L_ss** (agree
to **0.1%**) — initial-condition independence confirmed; both match **C_c_eff** to <1%. Conservation EXACT.

### Gate 2 — C_c invariance across total actin (n=5 seeds, 10τ from near-L_ss)
| total (mono) | total (µM) | [actin]_ss (µM) | vs C_c | vs C_c_eff | L_ss vs predicted |
|---|---|---|---|---|---|
| 200 | 0.33212 | 0.075099 | 8.9% | 2.1% | 155 vs 156 (0.6%) |
| 350 | 0.58120 | 0.075074 | 8.9% | 2.1% | 305 vs 306 (0.3%) |
| 500 | 0.83029 | 0.071652 | 3.9% | 2.6% | 457 vs 456 (0.3%) |

**[actin]_ss is INVARIANT across total actin** (spread **3.1%** about mean **0.073942 µM**, vs C_c_eff 0.073563 —
**0.5%**); **L_ss scales linearly with total** (rel ≤0.6%), exactly as conservation predicts. The critical
concentration is set by the **rate balance**, independent of how much actin is in the box — the defining property
of a critical concentration.

### Conservation (the hard invariant)
**EXACT** in every run (integer ledger `Σ monomerCount(active) = monInit + totalTaken − totalReturned`), across
the full dynamic grow/split/depoly/death churn — never created or destroyed.

### CPU≡GPU
20000-cadence treadmill pipeline, identical 3-step protocol (P from pool → kernels → host pool update): mismatches
**monomer = 0, state = 0, link = 0**; `[actin]` CPU = GPU = **0.391896** (bit-identical); L = 114 both. The
wang-hash grow/depoly decisions + the integer split/death/markFree are **bit-identical** CPU↔GPU; only `coord` is
float32. (Stronger than the §8 aggregate-within-SEM standard — the lifecycle is exactly deterministic.)

## Verdict
**The growth+depoly coupling is physically correct and turnover bounds filament length.** `[actin]` converges to
the critical concentration `C_c = k_off1/k_on` (matched to **0.5%** once the **computed** segment-granularity
correction `C_c_eff = (32/30)·C_c` from the death floor is accounted for; ~+6.7% above the ideal continuum C_c) —
**independent of initial conditions** (both directions → same, 0.6%) and **independent of total actin** (invariant,
spread 3.1%); **L bounds at the conservation-consistent L_ss** (scales with total, ≤0.6%); **conservation EXACT**;
**CPU≡GPU bit-identical**. This is the §8 first-principles adjudication: the emergent steady state matches the
rate-balance physics, not v1's (uncalibrated) numbers.

## Notes / flags
- **The C_c → C_c_eff offset (+6.7%) is a REAL, COMPUTED finding, NOT tuned.** It is the segment-granular death
  floor (a pointed segment's last 2 monomers leave via en-masse death rather than rate-`k_off1` events),
  `C_c_eff = (stdSegLength/(stdSegLength−(actinSeed−1)))·C_c`, derived from fixed model constants — matched to
  ~0.5%. No physics or parameter was changed to make it agree; the granularity-corrected prediction is what the
  discrete model should give. (Larger `stdSegLength` would shrink the correction; not changed.)
- **Single-filament scatter** (±√p_ss ≈ ±15% on the free-monomer count) is intrinsic; steady-state means need
  long windows (≥12τ) + several seeds — the gate thresholds (≤5% agreement, ≤7% vs C_c_eff) reflect this Poisson
  floor, NOT the physics.
- **Dead-slot reuse** is exercised here (pointed segments die, splits reclaim the slots — `splitWire` sets
  `monomerCount`), but **NO nucleation** runs, so the `INC7_STAGE1_FINDINGS.md` nucleation-monomerCount flag is
  not triggered (stays a separate ring-time fix).
- **Default-OFF elsewhere** (turnover flags enabled for this assay only); `BoA-v1ref` byte-clean; production
  untouched; no default flip.

## TL;DR
Growth + depoly **treadmill to a critical concentration** `[actin]_ss = C_c = k_off1/k_on` (0.069 µM ideal;
0.0736 µM with the computed +6.7% death-floor granularity correction — measured 0.0739, **0.5%**), **invariant
across initial conditions and total actin**, with `L` bounding at the conservation-consistent `L_ss` (scales with
total). Conservation EXACT; CPU≡GPU bit-identical. **The coupling is physically correct and turnover bounds
filament length** — the overrun fix, adjudicated against first principles (§8). No physics change.
