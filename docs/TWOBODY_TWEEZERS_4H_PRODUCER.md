# Experiment 4H — blinded single-motor laser-tweezers validation of the MD-informed explicit S2 (4G) model

**Role: PRODUCER.** This document records execution status only — completion, missing data, numerical failures,
paths, checksums, and reproduction commands. **It contains no scientific interpretation or verdict** about which
fixture is preferred; that is the separate analyst's task, to be performed against the blinded export without the
unblinding key.

**Date:** 2026-07-15 · **Branch:** `dt-convergence-study` · **Runner:** CPU sequential only (the two-body arc is
CPU-only). **dt:** 2.5e-6 (half-dt control 1.25e-6). **Default trap:** 0.05 pN/nm (swept in Assay 4).
`-exp4h` / `-twobody-tweezers-blinded`. Validated head/converter/lever/F8/binding gate/Lymn–Taylor chemistry +
kinetic constants/force ordering/RNG/filament mechanics/fixed-anchor reference/`BoA-v1ref` **untouched** (new
methods only; `g4*` fields read only by the `s2*`/`h4*` methods). `BoA-v1ref` byte-clean.

---

## Gate 0 — MD bending calibration (resolved before production)

**Decision: retain CURRENT_4G only; one blinded parameter block.** No material/EI correction is justified.

The reported ~4–5× transverse-stiffness "excess" is a **probe-definition artifact**, not a model error:
- The current `kTrans` probe pins the tip's **full 3D position (including axial)**, forbidding the bending
  foreshortening a real cantilever tip undergoes; a transverse displacement therefore engages the stiff
  fixed-contour **stretch** (measured free-tip axial ≡ EA/L = 70–420 pN/nm, resolution-exact).
- The **clamped-free** (force-controlled, free-tip) endpoint stiffness **converges toward the continuum 3EI/L³**
  as l0 refines (e.g. L=40: 0.71 → 0.84 → 0.91× target at l0 = 10 → 5 → 2.5 nm) — i.e. the bending **EI is
  correct**; the residual coarse-l0 softening is a bounded, convergent discretization effect (opposite sign to
  the reported "excess").
- The model's **search uses a free pivot** ⇒ it feels the clamped-free (MD-consistent) stiffness, not the pinned
  probe value.

Full sweep: `TWEEZERS_4G_BLINDED/gate0_bending_calibration.csv` (clamped-free vs fully-pinned vs axial endpoint
stiffness at l0 ∈ {10, 5, 2.5} nm for L ∈ {10, 20, 40, 60} nm; MD target 3EI/L³).

---

## Preregistered runs — completion status

| assay | preregistered target | completed | notes |
|---|---|---|---|
| Gate 0 | resolve calibration | **YES** | retain CURRENT_4G (one block) |
| Assay 1 — unloaded event-resolved stroke | ≥ 200 accepted events per condition | **YES** | A/B/C/D = 200 / 200 / 200 / 200 bound (every attempt bound in the search window) |
| Assay 2 — local bound-state stiffness | pre/post × loads {0,1,3} pN × δ {0.25,0.5,1.0} nm, matched-pose fixed, component tangents | **YES** | 72 local-stiffness rows + 24 component-tangent rows |
| Assay 3 — force clamp | ≥ 100 events per label per load; loads {0,0.5,1,2,3,4,5,−0.5,−1,−2} pN | **YES** | 40 aggregate cells (4 labels × 10 loads), 100 events/cell |
| Assay 4 — trap-stiffness dependence | traps {0.02, 0.05, 0.10} pN/nm | **YES** | 12 rows (unloaded stroke + post-stroke local stiffness) |
| Numerical controls | half-dt, contour, force balance, zero-S2-force-on-actin, seed restart, polarity covariance, no material switch, buckling probe, segmentation | **YES** | all pass/observational (see below) |

## Missing data

**None.** All preregistered assay cells produced. No accepted-event target went unmet (all 100 %-binding within the
search window; one single stroke censored at label B / +5 pN load, retained with `failClass=stroked_censored`).
Nonproductive and no-bind events are **retained** (never discarded) via the `failClass` column, per preregistration.

**Structural empties (not missing data):** the rigid-reference fixture has no S2 sub-structure, so its S2-specific
columns are empty/`NaN` in `event_data.csv`/`force_clamp.csv` and its S2 axial stiffness is `inf` (EA/L → ∞) in
`component_tangents.csv`. These are documented structural absences, not computation failures.

## Numerical failures

**None.** Numerical controls (`numerical_controls.csv`) all pass:
- fixed-seed restart bit-identical (all labels); contour conserved under the stroke (≤ 0.011 nm); internal beam
  force balance ≈ 0 (≤ 8e-13 pN); zero S2 force on actin (by construction); no nucleotide/binding material switch
  (beam ks/kb/l0/contour identical bound vs unbound); polarity covariance (world ≈ swap); half-dt deterministic
  stroke replicates (full ≈ half); free-tip segmentation converges (l0 10 → 5 nm). The compression-buckling probe
  is observational (reports buckled vs straight-compressed per fixture).

---

## Paths and checksums

- **Blinded export (for the analyst):** `TWEEZERS_4G_BLINDED/`
  - `manifest.json` (columns, units, sample sizes, exclusions, preregistered hypotheses; **label identities and the
    blinding seed WITHHELD**)
  - `gate0_bending_calibration.csv`, `event_data.csv`, `force_displacement.csv`, `local_stiffness.csv`,
    `component_tangents.csv`, `force_clamp.csv`, `trap_sweep.csv`, `numerical_controls.csv`
  - `trajectories/` — 12 per-event time series (3 per label)
  - `checksums.sha256` — **21 files**, all verify (`sha256sum -c checksums.sha256` → 21 OK)
- **Unblinding key (NOT for the analyst):** `UNBLIND_KEY_DO_NOT_GIVE_ANALYST.json` at the repo root — **outside**
  the export directory. Contains the label→fixture mapping and the blinding seed.
- **Producer console log:** `RUN_LOGS/exp4h_production.log`.

The four blinded fixtures are one rigid-support reference and three fixed-contour compliant-tail (explicit-S2)
variants of distinct free length, permuted to A/B/C/D by a withheld seed. No export file contains any label→fixture
mapping (verified); the design is stated generically in the manifest with identities marked WITHHELD.

---

## Exact reproduction commands

```
./scripts/build.sh
# Gate 0 only (bending calibration):
./scripts/run_lasertrap.sh -exp4h -gate0
# Full blinded production (defaults: seed 20260715, 200 Assay-1 events/label, 100 Assay-3 events/cell):
./scripts/run_lasertrap.sh -exp4h -export TWEEZERS_4G_BLINDED -key UNBLIND_KEY_DO_NOT_GIVE_ANALYST.json
# Smoke (tiny counts, structure only):
./scripts/run_lasertrap.sh -exp4h -smoke -export /tmp/h4smoke -key /tmp/h4smoke_key.json
# Verify checksums:
( cd TWEEZERS_4G_BLINDED && sha256sum -c checksums.sha256 )
# Flags: -seed <n> -nev1 <assay1 events/label> -nev3 <assay3 events/cell> -export <dir> -key <path>
```

Source: `softbox/TwoBodyConverterMotor.java` (Gate-0 probes `s2FreeTipTransK`/`s2FreeTipAxialK`/`s2PinnedTransK`/
`s2RelaxAllFree` + `phase4hGate0`; event engine `cycleStep4h`/`h4Build`/`h4RunEvent`; assays
`phase4hAssay1..4`/`phase4hControls`; blinding + export `phase4hProduce`/`phase4hManifestAndChecksums`), one
dispatch line in `softbox/LaserTrapHarness.java`.
