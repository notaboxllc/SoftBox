# SM4 — ADP protocol correction (ATP-free) — Summary

**Date:** 2026-07-22 · rev `0d7966e` · CPU · **no kinetic parameter changed** (only an additive,
default-preserving `-atpscale` [ATP] interface added to the SM4 harness). Full study + deliverables:
**`RUN_LOGS/motor_validation/sm4_adp_atpfree_protocol_corrected/`** (6 reports, 8 tables, 10 plots).
Read first: `ADP_PROTOCOL_CORRECTION_DECISION.md` and `SM4_ADP_ATPFREE_FINDINGS.md`.

## Task
The blind SM4 study left the ADP arm a "partial pass": wrong ADP<rigor ordering vs Guo & Guilford, and a
fixed-anchor fit bias. This task audited ATP-concentration handling, verified `[ATP]=0`, reconstructed the
biological ADP protocol, reran the ADP lifetime study **ATP-free**, and decided whether the discrepancy
persists — **without touching any ADP/rigor/gliding parameter**.

## Findings
- **Part A audit.** `atpOn` is a **pseudo-first-order rate** (no explicit `[ATP]`; v1 `atpOnMyo=2e4/s`
  = saturating ATP). `atpOn=0` ≡ `[ATP]=0`; on the canonical `cycleLymnTaylor(Rigor)` path there is **no
  break-cap/emergency/alternate release** — so `[ATP]=0` cleanly removes ATP-triggered detachment.
- **Part B.** The `-atpscale` interface is linear in [ATP]; **[ATP]=0 → 0 detachments (100% censored)**;
  exponential survival; 0 invalid / 0 rate-cap.
- **Part C (Guo & Guilford, read from source).** ADP = 2 mM MgADP; **endpoint = physical bond RUPTURE**
  of the ADP-state bond (not ADP release); head stays in ADP (no turnover); ⊥ loading; single-exponential
  survival; Table-2 ADP fit (catch k°176/x2.5 nm, slip k°15/x0.40 nm, f_crit 6.4 pN) = **exactly SoftBox's
  ADP catch–slip shape**, but SoftBox applies it to a *release* (base 1000/s) vs their *rupture* (191/s).
- **Part D/E/G.** ATP-free ADP is a genuine **two-stage sequential** process (ADP→NONE release, then
  rigor rupture; 100% rupture endpoint). The **sequential model beats single-barrier by ΔAIC ~700**;
  **frozen (planted) params predict the data** (ΔAIC ~13 vs free fit). Stage-resolved fits **recover the
  planted ADP catch–slip** (xCatch 2.44 vs 2.5, peak 6.12 vs 6.11) and the rigor rupture (stage 2).
- **Headline (biological):** ATP-free ADP is now **17% longer-lived than rigor** (1.17× vs Guo &
  Guilford 1.24×) with **peak lifetime 32.6 ms vs their 31.7 ms** and **peak force 6.7 pN vs 6.4 pN** —
  the **ordering flip is the fix** (the prior ATP-present ordering was a protocol artifact of the fast ATP
  terminus truncating the bond).
- **Part F.** The prior blind fixed-anchor bias is **reproduced and explained**: pooling the
  faithfully-transmitted **assisting arm** into a single-barrier fit (compounded by fitting a single
  barrier to a two-stage lifetime) drove xCatch→1.18, peak→9.9; **opposing-only / stage-resolved fitting
  recovers 2.44 / 6.12** → the ADP assay-recovery upgrades from partial-pass to pass. Analysis artifact,
  not a calibration error.

## Decision (Part H)
- **Gate D not triggered** — ATP handling is correct.
- **A (protocol correction) resolves the ordering + approximate scale** — no ADP retuning needed.
- **Analysis correction (Part F) resolves parameter recovery.**
- **Residual (flagged, NOT built):** Guo & Guilford's ADP bond ruptures **directly** (single-exponential,
  5.2 ms unloaded), whereas SoftBox reaches rupture via a chemical release first (hypoexponential,
  unloaded ~2× high). A **direct ADP-bound rupture channel** (ADP distances as a *rupture*) would be the
  faithful refinement — a separate declared phase, not required for the ordering/peak, not tuned here.

**The apparent ADP discrepancy does NOT persist under the protocol-correct ATP-free condition. No
recalibration performed or warranted.**
