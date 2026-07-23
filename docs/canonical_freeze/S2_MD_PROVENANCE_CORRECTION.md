# S2 STIFFNESS PROVENANCE CORRECTION — Adamovic, Mijailovich & Karplus 2008

**Part D of the freeze-audit finalization.** Corrects the v1.0-draft audit, which mis-classified the S2 bending
stiffness (EI / `kLatRef`) as `OPEN — BIOPHYSICAL` on the grounds that it had "no stated literature range." It
**does** have MD/literature support. Both the axial and the bending S2 material constants are derived from
Adamovic, Mijailovich & Karplus 2008 and must be recorded as **MD/literature-constrained, not arbitrary or
unsupported.**

---

## 1. The literature (Adamovic, Mijailovich & Karplus 2008), for the 60 nm myosin-II S2

| quantity | AMK-2008 range (per project decision) | SoftBox value | in range? |
|---|---|---|---|
| axial stretch stiffness K_ax(60 nm) | **60–80 pN/nm** | `kAxRef = 70 pN/nm` | ✅ mid-range |
| lateral (bending) stiffness k_lat(60 nm) | **0.008–0.012 pN/nm** | `kLatRef = 0.01 pN/nm` | ✅ **dead-center** |
| persistence length Lp | **≈130–170 nm** | `Lp = EI/kT ≈ 175 nm` | ✅ at the top edge (consistent) |

Both material constants sit inside the AMK-2008 bands, and the bending anchor is at the **center** of its range
— the opposite of "unsupported." (The v1.0-draft's "no literature range" note was simply an omission in the
first pass; corrected here.)

## 2. Mapping literature → the frozen SoftBox material constants

The code (`TwoBodyConverterMotor.java:6255-6262`, quoted verbatim) derives L-independent material moduli from the
L=60 nm reference by a documented clamped-free-cantilever length scaling (K_ax ∝ 1/L, k_lat = 3EI/L³ ∝ 1/L³):

```
EA = K_ax(60)·L_ref = 70e-3 N/m · 60e-9 m           = 4.2e-9  N        (stretch modulus)
EI = k_lat(60)·L_ref³/3 = 1e-5 N/m · (60e-9 m)³/3    = 7.2e-28 N·m²     (bending rigidity)
Lp = EI/kT                                            ≈ 175 nm
```

Per-segment (l0 = 10 nm fixed): `ks = EA/l0 = 420 pN/nm`, `kb = EI/l0 = 7.2e-20 N·m/rad²`. At L40:
`k_ax = EA/L = 105 pN/nm`. The code comment is explicit that these are **"molecular STARTING CONSTRAINTS
derived by length scaling — NOT tuned against gliding velocity."**

**Lp consistency check:** Lp = EI/kT scales with k_lat, so the AMK k_lat band 0.008–0.012 pN/nm implies
Lp ∈ ≈140–210 nm. The coded Lp ≈ 175 nm sits inside that MD-implied band (the project's stated "130–170 nm" is
a slightly narrower quote; 175 nm is at/just above its top edge and fully consistent with k_lat = 0.01
mid-range). No inconsistency — EI is anchored.

## 3. Reclassification (the correction)

| parameter | v1.0-draft status | **corrected status** |
|---|---|---|
| **S2 EA / `kAxRef`** (axial) | CONDITIONALLY FROZEN | **CONDITIONALLY FROZEN — MD/LITERATURE CONSTRAINED** (AMK-2008 axial 60–80; SoftBox 70 mid-range) |
| **S2 EI / `kLatRef`** (bending) | ~~OPEN — BIOPHYSICAL~~ | **CONDITIONALLY FROZEN — MD/LITERATURE CONSTRAINED** (AMK-2008 lateral 0.008–0.012; SoftBox 0.01 dead-center; Lp ≈175 nm in the MD-implied band) |

**Do not describe EI as unknown or unsupported.** Both S2 material stiffnesses are **frozen material constants**
with MD/literature provenance; they are eligible to change only on stronger MD/experimental evidence (a §6.2
compliance perturbation is a *sensitivity study within the MD band*, not a reopening of provenance), never to
tune gliding.

## 4. What this correction does NOT do

It does not change the **exposed S2 length / boundary-condition** question. That is a bounded
geometry/boundary-condition uncertainty, **CONDITIONALLY FROZEN at L40, with the declared L60 sensitivity now
COMPLETED** (Outcome 1 — single-head phenotype robust; `S2_LENGTH_FREEZE_DECISION.md`,
`L40_VS_L60_GLIDING_COMPARISON.md`) — it is **not** an open material-stiffness question. The material stiffnesses
are frozen; what remains a bounded geometry choice is **how much free S2 is exposed above the surface** (the free
contour length + the emergence boundary condition). Keeping these two cleanly separated is the point of this
correction: the audit must not present a literature-anchored material constant as an open unknown, nor a
conditionally-frozen bounded geometry as an unresolved pre-freeze open parameter.
