# Motor Validation Campaign — SM4 + SM6 interim synthesis

**Date 2026-07-21 · code rev `73d82bf` · scratch build `e667650b54f9` · CPU-only throughout.**
**NO physics or mechanics parameter was changed. No parameter change is recommended yet.**

Datasets:
- `RUN_LOGS/motor_validation/sm4_adp_opposing_production/` — 2400 events, explicit-s2-l40, tensile
- `RUN_LOGS/motor_validation/sm4_adp_assisting_fixed_anchor/` — 2400 events, fixed-anchor, compressive
- `RUN_LOGS/motor_validation/sm6_force_extension/` — 1341 single-head + 1217 dimer points

---

## 1. Is the coded ADP catch law recovered? — **YES**

The SM4 tensile production grid recovers all four coded parameters of

```
g(F) = aCatch·exp(−F·xCatch/kT) + aSlip·exp(+F·xSlip/kT)
```

once the thermal-convexity (Jensen) bias is removed:

| parameter | fitted (tensile, Jensen-corrected) | coded | agreement |
|---|---|---|---|
| k0 (unloaded ADP rate, 1/s) | 1041.0 ± 54.1 | 1000 (`onADP`) | +0.76σ |
| aCatch | 0.9232 ± 0.0082 | 0.92 | +0.39σ |
| xCatch (nm) | 2.270 ± 0.172 | 2.5 | −1.3σ |
| xSlip (nm) | 0.4095 ± 0.0251 | 0.4 | +0.4σ |

χ²/dof = 0.14, structureless residuals against realized load.

**A necessary correction was identified in the process.** The rate law is evaluated on the
*instantaneous* bond load, which fluctuates thermally (SD 1.35 pN on the explicit fixture). Because
`g` is convex, `⟨g(F)⟩ > g(⟨F⟩)`, and fitting at the mean load inflates the apparent unloaded rate by
**+36 %** (1356 vs 1000). The correction is exact and analytic for Gaussian load. **Any future
calibration against experimental lifetimes must apply it** — the bias is larger than the experimental
uncertainty the assay is meant to resolve. The athermal `fixed-anchor` fixture, which has zero load
SD, independently returns k0 = 1044.6 ± 41.6 with no correction, confirming the diagnosis.

---

## 2. Fitted catch optimum and its uncertainty

- Coded analytic optimum: **6.106 pN**.
- Measured RMST peak: **6 pN (5.350 ms, CI [4.655, 6.045])**; 8 pN gives 5.169 ms ([4.494, 5.844]).
- Fitted optimum from the free fit: **6.90 pN**.

**The optimum lies in the 6–8 pN band but 6 vs 8 is not resolved at n = 200** — the CIs overlap
heavily. Resolving it needs finer spacing around 6–8 pN or roughly 4× more events; it cannot be
extracted by re-analysing the existing data.

---

## 3. Identifiability — including one correction to the campaign's expectation

| parameter | identifiable? | best determination | from |
|---|---|---|---|
| **xCatch** | **YES** | **2.469 ± 0.064 nm (±2.6 %)** | fixed-anchor **assisting** arm |
| | | 2.270 ± 0.172 nm (±7.6 %) | explicit **tensile** arm |
| **xSlip** | **YES** | **0.4095 ± 0.0251 nm (±6 %)** | explicit **tensile** arm |
| | **NO** | 9.4 ± 110 nm — unidentifiable | fixed-anchor assisting arm |
| k0 (`onADP`) | YES | 1041 ± 54 / 1045 ± 42 | both arms, agreeing |
| aCatch | YES | 0.9232 ± 0.0082 | tensile arm |
| rigor load dependence | **NOT REPRESENTABLE** | — | structural (§5) |

**Correction to the campaign plan.** The task anticipated that the assisting arm would identify
`xSlip`. It cannot, and the reason is in the law itself: at **negative** F the *catch* exponential
diverges while the slip term decays, so the assisting arm is catch-dominated. `xSlip` is identified
on the **tensile** arm's falling limb beyond the optimum. The assisting arm instead gives the
campaign's **most precise xCatch** (±2.6 %), because a divergent exponential is a much stiffer
constraint than a decaying one. Both arms are still worth having — they constrain xCatch by
independent routes (one thermal, one athermal) and agree.

### What remains non-identifiable

1. **The 6 vs 8 pN optimum location** at current statistics (§2).
2. **Anything about rigor load dependence** — structurally absent (§5).
3. **The assisting branch beyond |F| ≈ 10 pN**, on *any* fixture: the ADP→NONE rate there exceeds
   1/dt, so the transition fires in the first timestep and the measured rate is pinned at exactly
   400 000/s. This is a **timestep resolution limit, not a fixture limit** — those three cells
   (−15, −20, −25 pN) were excluded from all fits. Extending the window requires a smaller dt.
4. **k0 vs aCatch separately** — they share the F→0 intercept and are only separable because
   `aCatch + aSlip = 1` is imposed.

---

## 4. Explicit-S2 buckling — mechanically explained

SM6 answers the question the SM4 smoke raised.

- `explicit-s2-l40` is **23.5× stiffer axially than transversely** (0.9906 vs 0.0421 pN/nm) — the
  designed MD-informed anisotropy (stretch ∝ 1/L, bending ∝ 1/L³). Such an element is an Euler
  column: under axial compression it buckles rather than resisting.
- **Buckling onset −7.5 nm.** Beyond it the beam bend angle goes 0.1° → 72° → 157°, and the
  transmitted axial force **plateaus at ≈ 7 pN** and never rises.
- **Energy partition is the proof**: at +15 nm (tension) the F8 spring holds 29.3 kT with *zero*
  bending; at −15 nm (compression) the work is stored as **11.7 kT of beam bending** against only
  6.1 kT in the bond. The catch–slip law reads the axial bond load, so once the beam buckles the
  kinetics stop seeing the applied force.
- The buckling is **elastic and fully reversible** — a complete 0 → +20 → −20 → 0 cycle shows
  **zero hysteresis** and full recovery, with only the fixture's built-in preload as residual.
- `fixed-anchor` and `calibrated-s2-l40` show **zero bending at any displacement** and transmit
  faithfully to −20 nm — which is why `fixed-anchor` was the correct fixture for the slip-side grid.
- The dimer inherits the same anisotropy (30–47× axial:transverse), so this is a property of the
  S2/branch architecture, not of the single-head fixture.

---

## 5. The rigor blocker

Rigor lifetime is **exactly** force-independent — bit-identical RMST (0.04088 ms) across all seven
force cells. In `cycleLymnTaylor` the catch–slip factor multiplies **only** the ADP→NONE step; the
sole exit from `NUC_NONE` is ATP binding at the constant `atpOn`. No harness change can introduce
load dependence into a constant rate.

Consequences: force-dependent rigor lifetimes, rigor dynamic force spectroscopy (SM5), and any
rigor/ADP discrimination *by load response* are currently unrepresentable. Three candidate physics
changes, their risks, the double-counting hazard, and the 8-item regression suite required before any
such change is accepted are documented in
**`docs/SM4_RIGOR_PATHWAY_DECISION_RECORD.md`**. Nothing was implemented, per instruction.

---

## 6. Surrogate divergence

`calibrated-s2-l40` **does not reproduce the explicit beam it was fitted to** in this quasistatic
test: axial k(0) 0.6194 vs explicit 0.9906, and it **never buckles**. It behaves like the *rigid*
`fixed-anchor` fixture (0.6296) rather than like the explicit beam. The 4I calibration matched the
beam's relaxed-pivot axial reaction; it evidently does not carry the compressive instability or the
small-signal axial stiffness in this configuration.

This bounds the surrogate's domain of validity — it does not invalidate it for the tensile,
load-dominated production use it was adopted for, but that bound was previously unmeasured. Any
production result depending on the compressive branch must state its fixture.

Useful check in the other direction: `fixed-anchor`'s k(0) = **0.6296 pN/nm** independently
reproduces the project's established ≈ 0.63 pN/nm whole-cross-bridge stiffness from the blinded
tweezers work.

---

## 7. Implications

### For gliding
- The catch law is **quantitatively correct as coded** — gliding speed should not be "fixed" by
  adjusting `xCatch`/`xSlip`, which are now independently validated against their own assay.
- Gliding heads experience both tensile (resisting) and compressive (assisting) load. The compressive
  side of the **explicit** motor buckles above ~7 pN transmitted, so in a gliding ensemble an
  assisting-loaded explicit head is **mechanically limited, not kinetically limited**. Any
  interpretation of explicit-motor gliding that assumes assisting heads transmit their share of load
  is wrong above that threshold.
- Because the calibrated surrogate does not buckle, **explicit and calibrated gliding differ
  structurally on the assisting side** — a candidate contributor to the known explicit-vs-calibrated
  gliding gap that has not previously been attributed.

### For minifilament construction
- Two-head-bound is **2.10× stiffer than one-head-bound at zero head separation, decaying to 1.21× at
  11 nm**. A minifilament's effective stiffness will therefore depend strongly on the axial spacing
  of its engaged heads, not just on how many are engaged.
- Loading one head of a bound pair recovers essentially the one-head stiffness (0.912 vs 0.848) — the
  heads act in parallel through the fork rather than in series, so an unloaded partner contributes
  little mechanical support.
- The large axial:transverse anisotropy means minifilament heads will be very compliant to
  off-axis load; bipolar geometries that load heads transversely will behave far softer than an
  axial-stiffness estimate suggests.

---

## 8. Is any parameter change recommended? — **NO**

Every parameter this campaign can identify is already at a value consistent with its own assay:

- `xCatch`, `xSlip`, `aCatch`, `onADP`: recovered within 1.3σ of their coded values. Nothing to change.
- The only quantities that *are* mis-estimable are apparatus artefacts (the Jensen bias, the dt
  resolution limit), and the fix for those is analysis method and dt, not model parameters.
- The rigor gap is a **structural** limitation requiring new physics, not a parameter change (§5).

**Comparison against experiment is a separate, still-open step** (§9), and no parameter should move
until that comparison is done with conditions verified.

---

## 9. Literature comparison — status and required care

The campaign document identifies Guo & Guilford's skeletal actomyosin lifetime work as the SM4
reference, reporting catch–slip behaviour in both rigor and ADP states with lifetime maximised near
6 pN and ADP bonds longer-lived than rigor near the optimum
([pmc.ncbi.nlm.nih.gov/articles/PMC1502541](https://pmc.ncbi.nlm.nih.gov/articles/PMC1502541/)).

**Qualitative comparison (safe to state now):**
- The model's ADP arm reproduces a catch–slip optimum in the **6–8 pN** band, qualitatively matching
  the reported ~6 pN optimum.
- The model's **rigor** arm does **not** reproduce rigor catch–slip — it is flat by construction.
  This is a genuine model/experiment discrepancy, not a tuning gap.

**Not established, and deliberately not claimed:**
- No numeric agreement is asserted. The experimental state, loading direction and force convention,
  nucleotide and ATP conditions, temperature, and ionic conditions have **not been verified against
  the source in this task**, and the project's own rate set is a documented multi-temperature
  skeletal stitch rather than a single-condition set. Absolute lifetimes are therefore not comparable
  without that verification.
- Nothing here is digitized from the source; this is qualitative comparison only.

**Required before adopting any numeric literature target:** verify state, direction, force
convention, [ATP], temperature and ionic strength; state whether values are digitized or quoted; and
re-express the model prediction under the matched condition — including the Jensen correction of §1.

---

## 10. Regression suite required before applying any calibration

1. Flag-gated and default-off; all existing harnesses byte-identical with the flag off.
2. SM4 tensile grid re-run — Jensen-corrected fit must still recover k0/aCatch/xCatch/xSlip within
   current uncertainties (the double-counting guard).
3. SM4 assisting grid re-run on `fixed-anchor` — xCatch must remain 2.47 ± 0.06 nm.
4. SM6 single-head and dimer curves bit-identical for a kinetics-only change (and re-measured for any
   mechanics change).
5. Gliding velocity and avgBound re-measured **on the CPU arbiter** — the standing GPU-number trust
   rule applies, since a hot-kernel structural change is exactly the class that flips the bistable
   basin.
6. Duty ratio / attachment statistics in the sparse-ensemble and dimer assays.
7. Conservation and health: 0 invalid states, 0 solver failures, 0 phantom detachments.
8. Fixture identity retained in every table and fit; no pooling of explicit-S2 and fixed-anchor data
   without explicit fixture-specific mechanics.

---

## 11. Isolation from the active GPU campaign

The matched-length (`-nseg 11`) single-head density sweep owned the GPU throughout. Its per-cell JVMs
load `.class` files from the repo tree, so a normal `./scripts/build.sh` would have changed the code
path of every subsequent cell.

- All SM4/SM6 code was compiled to a **scratch class directory**, prepended to the assay classpath.
- **All 287 production `.class` files verified unchanged by both MD5 and mtime**, before and after.
- No new `.class` file was written into the repo (287 before, 287 after).
- All runs CPU-only, `nice -n 19`, at most two concurrent JVMs (~13 % of 16 cores).
- The sweep advanced to 19/20 cells with **0 failures**; GPU utilisation stayed at its normal 42–50 %.
