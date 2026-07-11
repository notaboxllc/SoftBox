# The 25 °C gliding-assay target (Vmax calibration, STEP 0 — READ-ONLY)

**Date:** 2026-07-11 · **Branch:** dt-convergence-study · **READ-ONLY** literature audit (no code, no runs).
Companion: `MOTOR_PARAMETER_PROVENANCE_25C.md` (Table 2, the model rates). This is Table 1 + the chosen primary
target. **Purpose:** fix the condition-matched experimental velocity the calibration would aim at, so we don't
calibrate to the wrong experiment.

> **PRIMARY TARGET (if a target is used at all): ≈ 4.2 µm/s — fast skeletal myosin, ~25 °C, saturating ATP, ~50 mM
> ionic strength (Rossi et al. 2012, the one exact condition-matched anchor). Spread across clean sources ≈ 3–5
> µm/s at ~25 °C / ~50 mM.** But two caveats gate its use: (1) the SoftBox gliding assay is parameterized
> **skeletal** (Howard cycle + Guo&Guilford catch-slip + Finer unitary force — see Table 2), so a **skeletal** target
> is correct and the `NMII_BIOLOGY.md` intent is not the target; (2) **ionic strength is the dominant hidden
> variable** — the clean ~4 µm/s numbers are at ~25–50 mM; velocity **falls** toward true cytoplasmic ~150 mM
> (Homsher 1992), so at physiological salt the target is **< 4 µm/s**. **The provisional "1.5–4 µm/s" band's LOW end
> (~1.5) is not supported for skeletal 25 °C at low salt; ~4 is the anchor, ~3–5 the range.** Net: the model's
> current directed glide (netX ≈ −1.8 µm/s at biological head count, ~4 µm/s at higher engagement — `FORCE_BALANCE_
> CLOSURE.md`, `BIO_BOUNDCOUNT_TEST.md`) is **already inside this 3–5 µm/s target band** — reinforcing the Table-2
> conclusion that there is little "missing Vmax" for a temperature correction to recover.

---

## SoftBox gliding conditions (what the target must be matched to)

From `MYOSIN_VALIDATION.md` + the harness: single ~2 µm actin filament (11 seg × 64 monomers, phalloidin-stiffness
regime), motor carpet, **dt-model kT = 25 °C**, aeta = 0.1 Pa·s (~100× buffer — a numerical choice, velocity is
drag-insensitive here), saturating/deterministic binding (effective saturating ATP), motor density swept. The prior
v1 validation band was "**skeletal myosin II ~5–8 µm/s at saturating ATP, room temperature**" (`MYOSIN_VALIDATION.md`
:31) — but "room temperature" there is unspecified (20–25 °C) and ionic strength unstated, which is exactly the
imprecision this audit fixes.

## TABLE 1 — candidate 25 °C-region skeletal gliding-assay measurements

| Source | Isoform · species | **T** | [ATP] | Ionic strength / buffer | Actin / surface | **Velocity** | Confidence |
|---|---|---|---|---|---|---|---|
| **Rossi et al. 2012** (PMC3510724) | pure **fast rat** MHC-2B, HMM | **25 °C** | 2.0 mM (sat; Km 64 µM) | ~50 mM (25 mM MOPS pH 7.4, 25 mM KCl, 4 mM MgCl₂) | phalloidin F-actin, nitrocellulose | **4.20 ± 0.07 µm/s** (2.63 @20°, 13.19 @35°) | **HIGH — exact, condition-matched** |
| **Kron & Spudich 1986** (PMC386485) | **rabbit** skeletal myosin | 24 °C | saturating | low (~25–50 mM) | rhodamine-phalloidin actin, myosin on glass | **3–4 µm/s** | High (range) |
| **Anson 1992** (PMID 1533250) | **rabbit** skeletal | 3–42 °C (break ~15.4 °C) | saturating | low | rhodamine-phalloidin | **~4 µm/s @25 °C (EXTRAPOLATED)** | Med |
| **Homsher, Wang, Sellers 1992** (PMID 1550212) | **rabbit** skeletal HMM | 15–30 °C | varied | **10–150 mM tested** | phalloidin, nitrocellulose | **number UNRECOVERABLE** (paywalled); trend: V ↓ as ionic strength ↑ (50→150 mM) | Low (number) / High (trend) |
| **Rossi et al. 2005** (J Appl Physiol) | pure **fast rat** skeletal | 10–35 °C | ~2 mM | ~50 mM | phalloidin, nitrocellulose | **fast 25 °C value UNRECOVERABLE** (paywalled); sister 2012 paper = 4.20 at identical conditions | Med (inferred) |

**Cross-species note:** #1/#5 are rat, #2–4 rabbit. Mammalian fast-skeletal myosins track closely (no consistent
rat↔rabbit velocity offset reported), so a rat anchor is defensible for a rabbit-cycle model — the isoform *class*
(fast skeletal) is what matters, and it matches Table 2.

## Chosen PRIMARY target — and why

**Rossi et al. 2012 — 4.20 ± 0.07 µm/s (fast rat skeletal HMM, 25 °C, 2 mM ATP, ~50 mM ionic strength).** Why:
1. **Only source with an EXACT, fully-stated 25 °C measurement** (not extrapolated, not a range) at saturating ATP —
   the temperature the SoftBox kT is already set to.
2. **Conditions best match the SoftBox gliding assay**: HMM (heads-on-a-surface, like the fixed-motor carpet),
   phalloidin-stabilized actin (matches the phalloidin-stiffness filament regime), saturating ATP (matches the
   deterministic/saturating binding). Fast-skeletal isoform matches the skeletal cycle (Table 2).
3. **It anchors a temperature series** (2.63 @20° / 4.20 @25° / 13.19 @35°), giving the Q₁₀ ≈ 2.4 that Table 2 uses —
   internally consistent provenance.
Kron & Spudich (3–4) and Anson (~4 extrapolated) **bracket** it, so ≈ 4 µm/s is robust across labs.

**Uncertainty / range to carry:** target = **4.2 µm/s ± ~1** (band **3–5 µm/s**) at ~25 °C / ~50 mM. **If the intended
condition is physiological ionic strength (~150 mM), the target is LOWER (< 4 µm/s)** — Homsher 1992 shows the
monotonic salt-dependent decrease; the exact 150 mM number is a required primary pull (paywalled). Choose the ionic
strength deliberately before using any number.

## Verdict on the provisional band and whether a target is even needed

- **The "1.5–4 µm/s (point ~2.9)" band is only partly supported.** Its **upper end (~4) is right** for skeletal
  25 °C / low salt; its **lower end (~1.5) is NOT** a skeletal 25 °C saturated-ATP number at low salt — it would
  require high ionic strength, sub-saturating ATP, or a slow isoform, none of which match the assay. Do not treat
  1.5 as the target floor without a matched source.
- **A calibration target may not be needed at all.** Per `MOTOR_PARAMETER_PROVENANCE_25C.md`, temperature is not the
  lever (the velocity-limiting ADP release is already ~25 °C-equivalent), and the model's directed glide already
  sits in the 3–5 µm/s band. So this target's main use is a **validation check**, not a temperature-calibration
  goalpost. If the model is later found to glide outside 3–5 µm/s, adjudicate via the mechanochemical operating point
  (stroke/duty/force-balance), matched to this target at a **deliberately chosen ionic strength**.

## Sources
- Rossi et al. 2012 — https://pmc.ncbi.nlm.nih.gov/articles/PMC3510724/ (fast rat skeletal, 25 °C, 4.20 µm/s)
- Kron & Spudich 1986 *PNAS* — https://pmc.ncbi.nlm.nih.gov/articles/PMC386485/ (rabbit skeletal, 24 °C, 3–4 µm/s)
- Anson 1992 *JMB* — https://pubmed.ncbi.nlm.nih.gov/1533250/ (rabbit skeletal, T-dependence; ~4 @25 °C extrapolated)
- Homsher, Wang & Sellers 1992 *Am J Physiol* — https://pubmed.ncbi.nlm.nih.gov/1550212/ (ionic-strength dependence; number paywalled)
- Rossi et al. 2005 *J Appl Physiol* — https://journals.physiology.org/doi/full/10.1152/japplphysiol.00543.2005 (Q₁₀ ≈ 2.38, fast skeletal 10–25 °C)
- v1 target of record: `BoA-v1ref/MYOSIN_VALIDATION.md` (skeletal ~5–8 µm/s, "room temperature", unspecified salt).
</content>
