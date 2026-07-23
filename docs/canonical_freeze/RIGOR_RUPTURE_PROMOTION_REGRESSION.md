# RIGOR-RUPTURE CANONICAL-PROMOTION REGRESSION — Part B of the freeze-closure

**Date 2026-07-22 · canon v2 (`MotorModel.CANON_VERSION = 2`).** The narrow regression that promotes the
**rigor-only mechanical-rupture pathway (rupture_mode = 1)** from default-OFF to the **canonical production
default (ON)**. No chemistry, rigor, or ADP parameter was retuned; the only code change is the parse default
(mode 0 → mode 1) plus an explicit legacy-disable flag. **DECISION: all checks pass ⇒ rigor-only rupture is
now default ON.**

---

## 1. What changed in code

| harness | line (parse) | before | after |
|---|---|---|---|
| `ExplicitHmmDimerGlidingHarness` (dimer gliding) | ~647 | default 0 | **default 1**; `-no-rupture`/`-legacy-disable` ⇒ 0 |
| `ExplicitCompleteMatHarness` (single-head production) | ~975 | default 0 | **default 1**; `-no-rupture`/`-legacy-disable` ⇒ 0 |
| `ExplicitHmmDimerGpuValidation.runProductionCell` (dimer GPU cell) | ~1768 | default 0 | **default 1**; `-no-rupture`/`-legacy-disable` ⇒ 0 |
| `MotorModel.CANON_VERSION` | 102 | 1 | **2** |

- `-allrupture` still ⇒ mode 2 (all-strong-bound, OPTIONAL/NONCANONICAL — not promoted). `-rupture-mode N`
  still overrides explicitly. `-rigor-rupture` is now a no-op alias for the default (kept for back-compat).
- **`Sm4ForceLifetimeHarness` default is deliberately left OFF** — it is the force-clamp *validation
  apparatus*, whose `-selftest` gates encode the force-independent-rigor baseline; it invokes the rigor
  pathway **explicitly** (`-rigor-rupture`, ATP-free) for the rigor recovery study. The pathway (the shared
  `cycleLymnTaylorRigor` kernel) is identical across the force-clamp and gliding assays; only SM4's default
  stays OFF so the apparatus baseline is unchanged.
- The **device-resident GPU production path implements the rigor chemistry on-device** (the production
  TaskGraph dispatches `NucleotideCycleSystem::cycleLymnTaylorRigor` for mode 1;
  `ExplicitHmmDimerGpuValidation.java:1719`). The frozen-config abort (`:1782`) guards the *strain* failsafe
  (`ExplicitHmmDimerGpuParams.RUPTURE_MODE`), which is orthogonal and stays R0 — so the flip does **not** trip
  the standing-config gate, and CPU↔GPU remain parameter-identical.

## 2. Required checks — results

### CHECK 1 — Rigor force-clamp (recover the law)  ✅ PASS (existing evidence)
`RUN_LOGS/motor_validation/sm4_rigor_fixedanchor/`, `sm4_rigor_explicit/`, `sm4_blind_study/` (rev `0d7966e`,
ATP-free, ADP params frozen, n=200/cell, 0–25 pN ladder, opposing+assisting):
- Catch–slip law recovered on both fixtures: **xCatch 1.63 (fixed-anchor) / 1.54 (explicit)** vs planted 1.50;
  xSlip 0.51 / 0.55 vs 0.50; k0 146 / 173 vs 140 (Jensen thermal convexity on the compliant beam).
- **Peak near 7 pN:** rate-law peak force 6.88 / 7.44 pN; blind-recovered peak 7.086 pN [6.93, 7.23].
- Catch-slip decisively preferred: **ΔAIC 461 / 551** (open), **+7240** (blind, vs Bell).
- **Survival + censoring preserved:** 100% mechanical-rupture cause, **0 censored** (ATP-free window adequate),
  0 invalid / 0 solver.

### CHECK 2 — ATP-free ADP force-clamp (ordering + scale + stage-resolved recovery)  ✅ PASS (existing evidence)
`RUN_LOGS/motor_validation/sm4_adp_atpfree_protocol_corrected/` (rev `0d7966e`):
- **ADP > rigor ordering preserved:** ADP/rigor **1.171 (fixed-anchor) / 1.169 (explicit)** vs Guo & Guilford
  ~1.24.
- **Peak lifetime 32.6 ms** (G&G 31.7) · **peak force 6.7 pN** (G&G 6.4).
- **Stage-resolved recovery of the frozen params:** sequential beats single-barrier by **ΔAIC 679 / 717**;
  frozen (planted) params predict the data (seq_frozen ≈ seq_free, ΔAIC ~13); stage-1 ADP catch-slip
  xCatch 2.44–2.49 (planted 2.5), stage-2 rigor xCatch 1.60–1.62.
- **No retuning of onADP, xCatch, xSlip, or any rupture parameter.**

### CHECK 3 — Gliding impact (rupture ON vs OFF), paired seeds, single-head + dimer  ✅ PASS (existing evidence)
`RUN_LOGS/motor_validation/rigor_gliding_singlehead/` (GPU) + `rigor_gliding_dimer/` (CPU), dt=2.5e-6, 40 000
steps, densities {250, 700, 1500, 3000} (low / knee / saturated), seeds 101–110 paired ON/OFF:
- **Single-head pooled (resolved densities, n=22): +0.33 % ± 1.56 %, |t| = 0.21** — indistinguishable from
  zero; no density significant; every point inside its chaotic-seed envelope. Max bound-head change 1.77 %,
  max rupture fraction 2.14 % (only >2 % at d250 where total detachments are tiny), force@rupture ≈ 0 pN.
- **Dimer: velocity change ≤ 0.06 % where 0 ruptures (ON ≡ OFF bit-identical), pooled −0.45 % ± 0.68 %
  (|t| = 0.67)** — not significant. No material change in bound heads, ATP turnover, or density-response shape.
- **Velocity change within the previously observed negligible range.** (Contrast: the *all-strong-bound*
  mode 2 IS material on the dimer — kept OPTIONAL/NONCANONICAL, **not** promoted.)

### CHECK 4 — Regression (legacy-disable byte-identity, SM6, RNG, health)  ✅ PASS (certified this session, CPU)
- **Legacy-disable reproduces prior default-off byte-identically.** Pre-edit default (mode 0) reference:
  CPU dimer gliding smoke (`-density -seeds 2 -steps 200`) → SHA-256 `577e75ce…52cf`. Post-edit:
  `-no-rupture` → `577e75ce…52cf` (**exact match**); `-rupture-mode 0` → `577e75ce…52cf` (**exact match**).
- **No new RNG drift when disabled** — mode 0 dispatches the unmodified `cycleLymnTaylor` +
  `disableRigorRupture()`; the byte-identity above is the proof. (Even mode-1 adds **no** RNG draw: the rigor
  rupture is a single-uniform partition of the existing NONE draw.)
- **SM6 unchanged** — `Sm6DimerForceExtensionHarness` has **zero** chemistry references (rupture structurally
  absent from the CPU object solve); the smoke runs clean (1217 points), byte-identical a fortiori.
- **SM4 `-selftest` still ALL PASS** (SM4 default unchanged): gates A/B/C green, rigor 0.0315 ms vs ADP
  0.9590 ms distinguishable, 0 invalid / 0 solver.
- **New default (mode 1) is health-clean:** CPU dimer (0 invalid across all densities), GPU single-head
  production cell (device-resident, no CPU fallback, **0 invalid / 0 solveFail, rateCapWarn = 0**, 0 ruptures
  at saturating ATP — rigor is a rare channel), GPU dimer production cell (device-resident, 0 invalid/solve).
- **Rate-cap:** 0 in every gliding and production cell. The single nonzero occurrence (242 / 76 flagged steps)
  is confined to the **25 pN assisting sub-dt** force-clamp extreme — a *flagged, non-clipping* diagnostic at a
  non-production load (the known dt-substep item), not a rate-cap violation of the production physics.

## 3. Decision

**ALL CHECKS PASS.** Per the promotion criteria:
1. Rigor-only rupture (mode 1) is the **canonical production default (ON)**.
2. An explicit **legacy-disable** flag is retained (`-no-rupture` / `-legacy-disable` / `-rupture-mode 0`),
   byte-identical to the pre-v2 default.
3. The canonical model version is bumped: **`MotorModel.CANON_VERSION = 2`**; the manifest is regenerated.
4. **The same validated rigor pathway (`cycleLymnTaylorRigor`) is now used across the force-clamp and gliding
   assays** — force-clamp invokes it explicitly (the recovery/validation instrument), gliding runs it by
   default (production).

**No compensating tuning, no gliding-target fitting, no chemistry change.** The all-strong-bound (direct ADP,
mode 2) pathway is **not** promoted (remains OPTIONAL/NONCANONICAL — it is material on the dimer). The
double-counting guard is **satisfied without recalibration**: the ATP-free protocol already restored the
correct ADP > rigor ordering and approximate scale with the frozen params, and the gliding impact is
negligible, so promotion re-baselines neither gliding nor the ADP arm.

## 4. Effect on the baseline sweeps

The completed canonical gliding baseline sweeps ran at `rupture_mode = 0`. Because rigor ON ≡ OFF within the
chaotic-seed envelope (Check 3), those baselines remain valid canonical numbers and are **immutable** — the
mode-1 default is statistically indistinguishable from the mode-0 baseline. Re-running is neither required nor
warranted (no gliding re-baseline).
