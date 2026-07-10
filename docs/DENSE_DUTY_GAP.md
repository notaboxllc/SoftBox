# Closing the dense-bed duty gap: 0.85 is a thin-window ENRICHMENT artifact (per-head duty ~0.06)

**Date:** 2026-07-09 · **Branch:** dt-convergence-study · **PART 1 READ-ONLY** (existing coltol=8 sweep log +
code) — **closes it; no run needed.** `BoA-v1ref` reference-only.

## Verdict (up front)

**The 0.85 closes as `avgBound/meanReach` enrichment with per-head duty ~0.06 — a thin-window artifact, NOT a
bypassed recovery.** Two facts settle it: (1) `meanReach ≈ 1.17 × avgBound` at **every** density (the reachable
window is thin and bound-dominated), and (2) the ~10 ms ATP→ADP·Pi recovery gate is **enforced** on the dense
bind path (code, below), so a released head **cannot** rebind for ~10 ms. The ~17× more recovering heads that
must therefore exist per filament are **absent from `meanReach`** — they sit in the filament's glide wake, out of
geometric reach. The reported "duty" is the bound fraction of the thin reachable window (0.85), not the head's
time-fraction bound (~0.06). **Motor confirmed low-duty; recovery enforced; go to force-summation/recruitment
(`DETACHMENT_CEILING_CODEREAD.md`).**

## PART 1a — `avgBound` vs `meanReach`, per density (the crux number)

From `RUN_LOGS/2026-07-09_canonical_density_sweep_coltol8.txt` `STATS_STEADY_ROW` (3-seed means):

| density | avgBound | **meanReach** | mR/avgB | duty (=avgB/mR) |
|---:|---:|---:|---:|---:|
| 100  | 0.154 | 0.17 | 1.08 | 0.847 |
| 250  | 0.358 | 0.40 | 1.12 | 0.831 |
| 500  | 1.004 | 1.20 | 1.20 | 0.849 |
| 1000 | 2.892 | 3.33 | 1.15 | 0.864 |
| 2000 | 5.597 | 6.60 | 1.18 | 0.849 |
| 4000 | 10.993 | 13.00 | 1.18 | 0.847 |
| 6000 | 16.136 | 18.83 | 1.17 | 0.856 |
| 8000 | 20.842 | 23.97 | 1.15 | 0.870 |

**`meanReach` is small — ≈ 1.17 × avgBound at every density, never hundreds.** At d1000, avgBound ≈ 2.9 and
meanReach ≈ 3.3 (not ~4, not hundreds): the reachable set is only ~15–18 % larger than the bound set. This is the
**thin-window** case the task flagged — a few persistently-bound heads dominate a small reachable set — and the
enrichment ratio is **density-independent** (which is why "duty" reads a flat ~0.85 across 100×).

## PART 1b — the arithmetic closes, quantitatively

**Per-head temporal duty.** τ_on = dwell ≈ 0.6 ms (density-flat, `STATS_STEADY`); τ_off ≥ τ_recovery ≈ 10 ms
(ATP→ADP·Pi at `offATP = 100/s`, enforced — PART 1c). So

```
r_perhead = τ_on/(τ_on + τ_off) ≈ 0.6/(0.6 + 10) ≈ 0.057
```

**The engaged pool per filament.** Each bound head is followed by a ~10 ms recovery, so at steady state the
recovering (ATP, free, un-bindable) heads outnumber the bound by τ_recovery/τ_on ≈ 10/0.6 ≈ **16.7×**. At d4000:
avgBound 11 ⇒ ~184 recovering heads exist per filament.

**Where are they?** If those 184 were geometrically reachable, `meanReach` would be ≈ avgBound × (1 + 16.7) ≈
**194**. Observed `meanReach` = **13.0**. So the recovering heads are **not** in `meanReach` — they are out of
reach (the filament, gliding at 6–9 µm/s, has moved ~60–90 nm per recovery time and left them in its −x wake).

**The gap closes exactly.** The reported population ratio and the per-head duty differ by the fraction of the
engaged pool that `meanReach` omits:

```
duty_reported / r_perhead  =  (total engaged pool) / meanReach  ≈  (17.7·avgB) / (1.18·avgB)  ≈  15
r_perhead × 15  ≈  0.057 × 15  ≈  0.85   ✓
```

So 0.85 = 15 × the true per-head duty, the 15 being precisely the out-of-reach recovering heads that the
`avgBound/meanReach` denominator drops. **Enrichment covers the whole 0.06 → 0.85 gap; no residual.** (This is the
same signature the single-molecule assay showed directly: `in-reach fraction while free ≈ 0.000` — a free head is
almost never in the reachable count, `DUTY_RATIO_DIAGNOSIS.md` PART B.)

## PART 1c — recovery is ENFORCED on the dense path (rules out the bypass branch)

The alternative (per-head duty genuinely ~0.85 because the dense bed rebinds in ~0.1 ms, bypassing recovery) is
**refuted by code**, so PART 2 is unnecessary:

- `bruteReachable` (the source of `reachCount`/`meanReach`) is **purely geometric and state-blind** — no
  `boundSeg`, no `nucleotideState` in scope (`BindingDetectionSystem.java:260-282`). So `meanReach` **does** count
  recovering heads when they are geometrically in reach; their absence from it is real (they are out of reach),
  not a counting exclusion.
- The ADP·Pi recovery gate **is welded on** for the canonical Lymn-Taylor path: `if (LYMN_TAYLOR &&
  !ALLOW_BIND_ANY) ADPPI_BIND = true` (`GlidingHarness.java:310`) ⇒ `kinParams[20]=1` (`:593`), and the dense
  binder `bindNearestFalloff` enforces it — `if (adppiGate && nucleotideState != NUC_ADPPI) continue`
  (`BindingDetectionSystem.java:502, 509`). A just-released head is in **ATP** and **cannot rebind** until it
  hydrolyzes to ADP·Pi (~10 ms at `offATP=100/s`, `MotorStore.java:388`). τ_off ≥ ~10 ms; r_perhead ≤ ~0.057 is a
  hard kinetic bound. **Recovery is not bypassed.**

## Plain statement

**Does the 0.85 close as `avgBound/meanReach` enrichment with per-head duty ~0.06, or is the dense bed genuinely
high-duty because recovery is bypassed?** It **closes as enrichment.** `meanReach ≈ 1.17 × avgBound` (thin,
bound-dominated) at every density; the ~17× recovering heads per filament sit in the glide wake, out of reach, so
they never enter the denominator; and the ADP·Pi recovery gate is enforced on the dense bind path
(`BindingDetectionSystem.java:502`), so τ_off ≥ ~10 ms and the true per-head temporal duty is ~0.057. The reported
0.85 is 15× that — the fraction of the thin reachable window that is bound, not the head's time-fraction bound.
**The dense bed is low-duty; recovery is enforced; the no-saturation problem is force-summation / recruitment, not
kinetics.** PART 2 (tagged dense-bed dwell run) is **not needed** — PART 1 closes it.
</content>
