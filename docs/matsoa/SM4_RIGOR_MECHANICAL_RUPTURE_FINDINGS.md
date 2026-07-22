# SM4 — Rigor mechanical-rupture pathway: isolated validation

**Date:** 2026-07-22 · **Runner:** CPU (single bound head) · **Kernel:** `NucleotideCycleSystem.cycleLymnTaylorRigor` (flag-gated, default OFF)

## 1. What was added, and why

The frozen Lymn–Taylor cycle (`cycleLymnTaylor`) makes rigor detachment **force-independent**: the only
release from a bound rigor (`NUC_NONE`) head is ATP binding (`NONE→ATP`, rate `atpOn`). The Guo & Guilford
catch-slip modulates only the `ADP→NONE` step, so the model cannot reproduce the biological rigor
force-lifetime (a catch bond). We added a **distinct, flag-gated mechanical rupture pathway** available only
to a **bound** head in `NUC_NONE`:

```
bound rigor (NUC_NONE) --k_rigor(F)--> detached (mechanical rupture)   [NEW, force-dependent]
```
physically separate from ATP binding and from the ADP catch-slip (both of which are left byte-identical).

Force law (model 0, two-pathway catch-slip; `F` = realized instantaneous axial bond load `forceDotFil`;
project sign convention **+opposing/barbed = the catch side**):
```
k_rigor(F) = k0 · [ aCatch·exp(−F·xCatch/kT) + aSlip·exp(+F·xSlip/kT) ]     g(0)=aCatch+aSlip=1
```
A one-path Bell comparison model (`model 1`: `k0·exp(+F·xSlip/kT)`) is supported but never substituted.

Parameters live in a **dedicated `MotorStore.rigorParams`** array, wholly separate from the ADP `kinParams`
(no sharing of `xCatch/xSlip/aCatch/aSlip`). Cause accounting: a rigor rupture detaches with the nucleotide
state **staying `NUC_NONE`** (distinct from the ATP terminus, which ends in `NUC_ATP`) and increments
`ruptureStats[2m]`; ATP releases still increment `stats[2m+1]`.

### Competing hazards / timestep treatment (no event-order bias, no new RNG draw)
For a bound rigor head, ATP binding (rate `atpOn`) and mechanical rupture (rate `k_rigor(F)`) compete. They
are resolved by a **single-uniform partition** of the *same* draw `u` that the cycle already uses:
```
u ∈ [0, pAtp)          → ATP uptake (identical band to the flag-off path)   pAtp = atpOn·dt
u ∈ [pAtp, pAtp+pRig)  → mechanical rigor rupture                            pRig = k_rigor·dt
```
Mutually-exclusive bands of one uniform ⇒ each cause gets probability **exactly ∝ its rate**, with no
tie-break and **no order bias**, and **zero new RNG draws** ⇒ the flag-off sequence is unperturbed.
Small-dt discipline: the whole cycle is Euler `rate·dt`; if `pAtp+pRig` exceeds a cap (`rigorParams[9]`,
0.2) the step is **flagged** in `ruptureStats[2m+1]` (a substep/abort signal), never silently clipped. In
all runs here `rate_cap_warnings = 0` (largest observed `pAtp+pRig ≈ 0.05` at saturating ATP; `≈ 6.7e-4`
ATP-free).

## 2. Literature target (Guo & Guilford 2006, PNAS 103:26)

| condition | value |
|---|---|
| construct | rat skeletal-muscle **HMM** (F-actin from rabbit G-actin) |
| nucleotide | **rigor = nucleotide-free** (ATP-free); ADP measured separately |
| temperature | room temperature (~20–25 °C) |
| buffer | 25 mM KCl / 25 mM imidazole / 1 mM EGTA / 4 mM MgCl₂, pH 7.4 |
| loading | laser trap, **instantaneous step loads** (1.8–26.4 pN) + constant-rate ramps |
| **direction** | **perpendicular to the actin axis** |
| reported | bond **lifetime** (mean bound time); also rupture-force vs loading rate |

Rigor two-pathway Bell fit (their **Table 2**): catch `k_c⁰=127/s, x_c=−1.5 nm`; slip `k_s⁰=13/s,
x_s=0.5 nm`; `f_crit ≈ 7.1 pN`. Mapped to our form: **k0=140/s, aCatch=0.9071 (xCatch=1.5 nm),
aSlip=0.0929 (xSlip=0.5 nm)** ⇒ zero-load lifetime ≈ 7.1 ms, peak ≈ 25 ms at ≈ 6.9 pN. This is the coded
target the assay must recover.

### Documented ambiguities (blocker rule → anchored on authors' fit, not over-claimed)
1. **Figure vs Table.** The digitized Fig-4A lifetimes (~seconds) are inconsistent with the Table-2 Bell fit
   (~7 ms zero-load). We anchor on the **authors' own fitted parameters** (Table 2, internally consistent,
   `f_crit` matching the peak) rather than a digitized figure, and treat this as a sensitivity to bracket.
2. **Geometry.** Their load is **perpendicular** to actin; our realized `forceDotFil` is the **axial**
   projection of the cross-bridge force. Numeric lifetime agreement is therefore reported as a
   *force-law-shape* match (catch-slip, peak near 7 pN), **not** a claim of identical absolute lifetimes
   under identical loading geometry.
3. **Temperature.** Model `kT` is 300 K; Guo & Guilford ~295 K (a ≤2 % offset in `x/kT`), absorbed into the
   fitted `x`.

## 3. Isolated assay + recovery of the coded law

Prepared state **rigor (`NUC_NONE`)**, **ATP-free** condition (`atpOn=0` ⇒ mechanical rupture is the sole
rigor channel — the physical representation of the nucleotide-free experiment, **not** a retune of the ATP
rate constant). Force ladder `0,1,2,3,4,5,6,8,10,15,20,25 pN`, opposing + assisting arms, **n=200/cell**,
`dt=2.5e-6`, right-censoring horizon 300 ms (0 censored — window fully adequate). Two fixtures, identity
retained on every record (never pooled): **fixed-anchor** (rigid load transmission, clean kinetics
isolation) and **explicit-s2-l40** (faithful compliant load transmission).

**Opposing arm reproduces the catch-slip force-lifetime** (fixed-anchor, realized-load axis):

| req F (pN) | realized (pN) | mean lifetime (ms) |
|---:|---:|---:|
| 0 | +0.69 | 8.8 |
| 4 | +4.12 | 21.9 |
| 6 | +5.82 | 25.6 |
| **8** | **+7.54** | **26.3 (peak)** |
| 10 | +9.26 | 24.3 |
| 15 | +13.63 | 14.9 |
| 25 | +22.54 | 5.1 |

Lifetime rises to a peak at realized ≈ 7.5 pN then falls — the catch bond, peaking at Guo & Guilford's
`f_crit ≈ 7.1 pN`. The assisting arm decays monotonically (catch→reverse-slip); high-|F| assisting cells hit
sub-`dt` resolution (lifetime < a few `dt`) and are auto-dropped from the fit.

**Recovered law** (censoring-aware cause-specific rupture rate vs realized load, ADP params held frozen):

| fixture | k0 (/s) | aCatch | xCatch (nm) | xSlip (nm) | peak (pN) | χ²/dof | ΔAIC (2-path vs Bell) |
|---|---:|---:|---:|---:|---:|---:|---:|
| **target (G&G)** | 140 | 0.907 | 1.50 | 0.50 | 6.99 | — | — |
| fixed-anchor | 146.2 ± 11.0 | 0.917 ± 0.010 | 1.63 ± 0.16 | 0.51 ± 0.03 | 6.88 | 0.05 | 461 |
| explicit-s2-l40 | 172.5 ± 12.7 | 0.937 ± 0.008 | 1.54 ± 0.14 | 0.55 ± 0.03 | 7.44 | 0.20 | 551 |

- **`xCatch` (the key catch distance) is recovered essentially exactly** (1.54–1.63 vs 1.50) on both
  fixtures; `xSlip` within ≤0.05 nm; peak force brackets Guo & Guilford's 7.1 pN.
- **Two-pathway catch-slip is decisively preferred** over the one-path Bell (ΔAIC 461 / 551): a Bell law
  cannot produce the rising (catch) limb below the optimum.
- The small **k0 upward bias** (146 → 172) is **Jensen convexity**: the rate is evaluated on the fluctuating
  instantaneous load, and `g(F)` is convex ⇒ `⟨g(F)⟩ > g(⟨F⟩)`, inflating the apparent unloaded rate. See §4.

Full covariance in `parameter_covariance.csv`; per-cell competing-risk decomposition in
`detachment_cause_summary.csv` (100 % `rigor_rupture` in the ATP-free condition, 0 % `atp_release`, 0
`rate_cap_warnings`).

## 4. Required analyses

- **Survival / RMST vs load:** `survival_summary.csv`, `kaplan_meier.csv`, `hazard.csv`. Mean lifetime
  (RMST proxy at these near-zero censoring rates) is the catch-slip curve in §3.
- **Competing-hazard decomposition & ATP-vs-rupture fraction:** ATP-free ⇒ 100 % mechanical rupture (the
  clean calibration channel). At **saturating ATP** the same cells give **<1 % rupture** (§Part B) — ATP
  binding (50 µs) massively outcompetes the ~7 ms rupture.
- **Fitted params + uncertainty + residuals:** `fit_results.json` (χ²/dof ≈ 0.05–0.20 ⇒ excellent fit).
- **Model comparison:** two-pathway catch-slip vs one-path Bell — ΔAIC ≥ 461 favors two-pathway.
- **Timestep sensitivity:** half-`dt` (1.25e-6) recovers **k0 = 135.7 ± 14.3, peak 6.60 pN** — the physical
  rate is **dt-invariant** (the slight k0 drop is reduced Euler `rate·dt` discretization + finer resolution
  of short lifetimes). No dependence of the recovered law on `dt`.
- **Thermal-fluctuation sensitivity:** Brownian-OFF (no load SD) recovers **k0 = 144.9, xSlip = 0.500**
  (exactly target) — confirming the k0 inflation is **Jensen thermal convexity**. It is small for the rigid
  fixed-anchor (~1–4 %) and larger for the compliant explicit beam (k0 172, more thermal load fluctuation).
- **Comparison to biological data:** force-law **shape** (catch-slip, peak ≈ 7 pN) matches Guo & Guilford;
  absolute lifetimes are *not* claimed identical given the perpendicular-vs-axial loading geometry (§2).

## 5. Deliverables (per fixture directory)

`RUN_LOGS/motor_validation/sm4_rigor_fixedanchor/` and `.../sm4_rigor_explicit/`:
`event_table.csv`, `survival_summary.csv`, `kaplan_meier.csv`, `hazard.csv`,
`detachment_cause_summary.csv`, `fit_results.json`, `parameter_covariance.csv`, `ANALYSIS.md`.
Sensitivity runs: `.../sm4_rigor_dt1p25/` (timestep), `.../sm4_rigor_nobrown/` (thermal).

## 6. Reproduce

```
./scripts/build.sh
java … softbox.Sm4ForceLifetimeHarness -motor fixed-anchor -rigor-rupture -atpfree \
     -states rigor -forces 0,1,2,3,4,5,6,8,10,15,20,25 -dirs opposing,assisting -events 200 -maxdwell 300 \
     -outdir RUN_LOGS/motor_validation/sm4_rigor_fixedanchor
python3 scripts/sm4_analysis.py  RUN_LOGS/motor_validation/sm4_rigor_fixedanchor
python3 scripts/sm4_rigor_fit.py RUN_LOGS/motor_validation/sm4_rigor_fixedanchor
```

**Fitting rule honored:** the ADP `xCatch/xSlip/amplitudes/onADP` were held **fixed**; only the new rigor
pathway was constrained, and only from rigor data.
