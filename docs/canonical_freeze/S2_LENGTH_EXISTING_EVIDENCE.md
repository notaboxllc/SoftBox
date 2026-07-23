# S2 LENGTH — EXISTING EVIDENCE INVENTORY

**Part E of the freeze-audit finalization.** Inventories the completed short studies that already vary the S2
length L ∈ {10, 20, 40, 60} nm, decides whether qualitative robustness is already established, and reframes the
remaining open question as the **free/exposed S2 contour length + surface boundary condition** — **not** the S2
material stiffness (which is MD/literature-constrained; `S2_MD_PROVENANCE_CORRECTION.md`).

---

## 1. The completed L-sweep (Experiment 4G, `docs/TWOBODY_MD_INFORMED_S2.md`)

L ∈ {10, 20, 40, 60} nm is a **preregistered** set (`EXP4G_L_NM = {10,20,40,60}`,
`TwoBodyConverterMotor.java:6270`), interpreted as **assay boundary conditions**: short = strongly-adsorbed /
tweezers-like; intermediate = partially-supported gliding surface; long = exposed proximal tail / native search
(§129–130). The explicit fixed-contour beam was built and probed at each length:

| L (nm) | M | soft-transverse k (pN/nm) | axial tension k (pN/nm) | axial compression k (pN/nm) | Euler buckle (pN) | stroke drift (nm) | verdict |
|---:|---:|---:|---:|---:|---:|---:|---|
| 10 | 1 | 8.84 | 420 | 420 | 71.1 | +0.00 | stiff-line (strongly-supported) |
| 20 | 2 | 1.21 | 210 | 210 | 17.8 | +0.00 | stiff-line |
| **40** | 4 | 0.167 | **105** | 105 | 4.4 | +0.01 | stiff-line* (production) |
| 60 | 6 | 0.052 | 70 | **0.68** | 2.0 | +0.01 | **bends + buckles** |

## 2. Per-length summary (the five requested axes)

- **Effective axial response** — falls exactly as 1/L (420/210/105/70 = ks/M), the MD-predicted axial scaling.
  Production L40 → k_ax = 105 pN/nm.
- **Bending response** — soft-transverse stiffness falls ≈ 1/L³ (8.84 → 0.052 pN/nm), **approaching the MD
  k_lat ≈ 0.01 at L60** — the emergent anisotropy matches the MD scaling with no prescribed Cartesian spring.
- **Buckling threshold** — Euler critical ∝ 1/L² (71 → 2.0 pN). At L60 compression buckles cleanly
  (kComp 0.68 ≪ kTens 70, a 100× tension/compression asymmetry, emergent). At L40 the beam is on the
  compressed-straight branch (crit 4.4 pN; a deterministic-probe robustness limit, not a physics claim).
- **Numerical stability** — contour conserved to ≤ 0.01 nm under stroke and bending at every L; the coupled
  full-tangent solve is stable (a stretch-only tangent bug was found + fixed, §5 of the 4G report). Production
  dt = 2.5e-6 stable at every L; stroke is dt-invariant (7.27 nm at L20 across dt {5e-6, 2.5e-6, 1.25e-6}).
- **Force transmission / stroke / surrogate fit** — **stroke is L-robust** (contour drift +0.00…+0.01 nm at
  every L; the working stroke comes from the head converter, not the beam). The **decoupling** (search-mobile
  transverse + stiff axial) **emerges for L ≥ 40 nm**; L < 40 is the strongly-supported regime (a *correct*
  representation of a short/adsorbed tail, not a decoupling failure). The calibrated surrogate is fit at L40
  (`CALIBRATED_S2_L40`); the force-clamp FXB fixture is the L40 compliant explicit beam.

## 3. Is robustness already established? — YES, for the material + mechanics

- **Material stiffness:** MD/literature-constrained across the range (EA/EI fixed; the per-length k's are the
  1/L, 1/L³ consequences of the frozen moduli). Not open. (`S2_MD_PROVENANCE_CORRECTION.md`.)
- **Mechanics robustness:** stroke L-robust; axial/bending/buckling scale predictably and match MD; contour
  conserved; solve stable; the decoupling holds for L ≥ 40. The qualitative behavior over the plausible
  exposed-length range (40–60 nm) is **already established** at the single-molecule / beam level.
- **Not yet directly measured:** an **ensemble gliding density sweep at L60** (vs the L40 production curve) to
  confirm Vmax / ρ½ robustness to the exposed length. The single-molecule robustness is established; the
  ensemble-at-L60 point is the one gap. Given the mechanics are L-robust (stroke unchanged; axial only rescaled;
  the calibrated surrogate re-fits per length trivially), the expected ensemble effect is a modest Vmax rescale
  with ρ½ preserved (the pattern dimerization and the margin/ownership change both show). **A single confirmatory
  L60 gliding density sweep is the only optional new run** — recommended for the cooperativity §6.2 compliance
  panel, not required to declare qualitative robustness.

## 4. The remaining open question — reframed

> **Open: the free/exposed S2 contour length and its surface boundary condition** — i.e. how much of the ~60 nm
> S2 is exposed above the coverslip vs adsorbed/supported (short/adsorbed → strongly-supported stiff-line;
> exposed → bends + buckles). This is a **geometry / boundary-condition** question, **not** an S2 material-
> stiffness question. L40 is the production choice for a partially-supported gliding surface; L60 is the
> exposed-tail limit; both are built and mechanically characterized.

This is the correct framing for the freeze: the S2 **material constants are frozen** (MD-anchored); what remains
open is the **assay geometry** (exposed length + emergence boundary), bounded to 40–60 nm, with the mechanics
already shown L-robust. New runs are recommended **only** as an optional single L60 gliding sweep to close the
ensemble-level robustness, since the beam-level and material-level robustness is already established.
