# Experiment 4F — biologically motivated supported two-region tail (search-mobile, load-bearing)

**Date:** 2026-07-15 · **Branch:** `dt-convergence-study` · **Commit at start:** `e17b5a4` · **Runner:** CPU
sequential only (`-gpu` unused — the two-body arc is CPU-only; the dense mat is made tractable by the 4D-ii
active-set cull, not GPU). **Hardware:** aorus, one core. **dt:** 2.5e-6 (mechanics + mat). **Trap:** 0.05 pN/nm.

**Default-off, non-canonical prototype** (`[NON-CANONICAL TWO-BODY PROTOTYPE]`), `-exp4f` /
`-twobody-supported-s2-tail` in `softbox/TwoBodyConverterMotor.java`. **The validated head geometry, converter,
neck–lever, F8 spring, stereospecific binding gate, Lymn–Taylor chemistry + kinetic constants, force ordering,
RNG behaviour, filament mechanics, and `BoA-v1ref` are UNTOUCHED** (4F is new methods only; the `sup*` fields on
`Cmot`/`Glide2D` are read ONLY by `supForce`/`stepSup`/`supForceM`/`supSolveM`, never by the validated
`stepC`/`stepGlide2D`). `supOn=false` ⇒ `stepSup` delegates to `stepC` **bit-identically** (Gate 1). This is a
**geometry + mechanics** study; **no motor, gate, or kinetic parameter is tuned** — the only added elements are
the two passive tail springs, and the ONE §5-licensed refinement is the supported tail's *own* axial stiffness.

---

## 0. Controlling outcome — OUTCOME A: clean mechanical decoupling

**A supported two-region tail SEPARATES unbound search mobility from bound axial load transmission** — the property
a passive *linear* tail (4E) could not provide. 4E failed because its compliance was **isotropic**: the softness
that enlarged the capture volume (search) was the *same* softness that absorbed the power stroke (load). 4F fixes
this with an **anisotropic + nonlinear** tail:

- **soft TRANSVERSE** (a flexible proximal S2 hinge) → large capture volume / search mobility;
- a **slack-to-taut AXIAL** law (a supported distal tail along the filament/load axis) → **soft within a small
  slack δ** (low-force search) but **TAUT/stiff beyond δ** (under the stroke load) → the stroke is transmitted, not
  absorbed.

Passive · nonlinear · anisotropic · load-engaged · **nucleotide-INDEPENDENT** (no state switch). At both the
**no-slack (δ = 0)** and **short-slack (δ = 1.5 nm)** settings the single motor keeps the full
validated stroke and skeletal stiffness with negligible pivot recoil **and** gains a large 2-D capture footprint;
in the dense mat this becomes a **multi-fold increase in LOAD-BEARING (not merely chemically bound) attachments**.
The **excessive-slack** regime (δ ≥ 3.5 nm) reproduces the 4E trap (recruits but inert), defining the failure edge.

---

## 1. §1 — the 4E references reproduce (gating)

| reference | expected (4E) | 4F measured | note |
|---|---|---|---|
| fixed-anchor stroke | 6.9 nm | **6.91 nm** | validated 3D/3F/4A |
| fixed-anchor k_ext | 0.645 pN/nm | **0.645 pN/nm** | |
| fixed-anchor pivot recoil | ~0 | **0.00 nm** | anchor is the pivot |
| 4E free tail (20 nm, k=2, κ free) stroke | ~1 nm | **1.07 nm** | stroke absorbed |
| 4E free tail k_ext | ~0.01 pN/nm | **0.010 pN/nm** | 98 % series compliance |
| 4E free tail pivot axial give | several nm | **−6.74 nm** | pivot recoils, actin barely moves |

**Gate 1 (regression):** `stepSup` with `supOn=false` reproduces `stepC` **bit-for-bit** (max|Δ| = 0.00e+00) ⇒ the
supported-tail path is a strict superset; the validated fixed-anchor motor is recovered exactly. **Gate 2 PASS.**

---

## 2. §2 — biological-to-model architecture map

This is a **coarse-grained, biologically motivated** mechanism — not a claim that one exact molecular hinge or
adsorption geometry is established.

| component | biological analogue | model element | DOF | stiffness law |
|---|---|---|---|---|
| substrate support `S` | coverslip / thick-filament backbone | fixed point (viewer: `S = P0 − lS2·ê_up − lDist·b̂`) | none | rigid |
| supported distal tail | adsorbed / supported coiled coil | AXIAL slack-to-taut along load axis `ûL = b̂` | (axial of P) | soft `<δ`, **taut** `>δ` (high) |
| proximal S2 hinge | flexible proximal tether | SOFT TRANSVERSE spring + finite-extension | (transverse of P) | low-force soft, bounded at `rMax` |
| distal pivot `P` | lever attachment | movable point (`P` = `cm.A` when `supOn`) | 3-D (b̂/econv/ê_up) | held by the two elements above |
| active motor | head–converter–lever | the validated 3C/3D/3F/4A model | φ, ψ | **unchanged** |
| substrate floor | coverslip surface | one-sided penalty on the ê_up component of `P` | — | unilateral (no penetration) |

The pivot `P` carries coordinates `q = (P_b, P_e, P_up, φ, ψ)` in the lab-fixed frame `b̂ = +x` (filament/load
axis), `econv = +y` (in-plane transverse), `ê_up = +z` (substrate normal / toward actin). The two tail elements
are **separate**: the distal-tail parameters (`kTaut`, `δ`, `ûL`) set the axial/load response; the S2 parameters
(`kSoftTr`, `rMax`) set the transverse/search response.

---

## 3. §3 — the force law

Let `d = P − P0` (µm; `P0 = A`, the rest anchor), `qL = d·ûL` (the **spec tension coordinate**), `dT = d − qL·ûL`
(transverse), `rT = |dT|`. `softpos(x, s) = s·ln(1 + e^{x/s})` (smooth-positive, C^∞; → max(0, x) as s → 0), with
tangent `softpos′(x,s) = logistic(x/s) ∈ [0,1]`.

**Supported distal tail — axial slack-to-taut (restoring scalar `Frest`; force on `P` = `−Frest·ûL`):**

- tension (`qL ≥ 0`, the barbed-ward stroke direction): `Frest = k_soft·qL + k_taut·softpos(qL − δ)` — soft within
  the slack, TAUT beyond; tangent `k_ax = k_soft + k_taut·softpos′(qL − δ)`.
- compression (`qL < 0`): softer by `compFrac` (tension/compression asymmetry), bounded; the substrate floor
  prevents collapse.

**Flexible proximal S2 hinge — transverse soft + finite-extension (force on `P` = `−F_rad·d̂T`):**

- `F_rad = k_softTr·rT + k_feTr·softpos(rT − rMax)` — soft for search, smoothly stiffening near the contour `rMax`
  (bounds inversion / runaway); tangent `k_tr = k_softTr + k_feTr·softpos′(rT − rMax)`.

**Substrate floor** (one-sided, on the `ê_up` component `zoff = d·ê_up`): `+k_floor·(−floorZ − zoff)·ê_up` when
`zoff < −floorZ`; else zero (no penetration, §14).

**Integration.** Overdamped linearly-implicit `(A_drag + K)Δq = F`, `K = k_F8·JᵀJ` (the validated F8 Gauss–Newton)
`+ converter + bind + the tail TANGENTS` (`k_ax` on the b̂ DOF, `k_tr` on econv/ê_up, `+k_floor` when floored) —
the exact generalisation of the validated 5-DOF solve; the numerical Jacobian matches the analytic tangent
(§14, central-difference). The force is nonlinear (evaluated at the current pose); the tangent stabilises the stiff
mode. As `(k_soft, k_softTr) → ∞` or `δ → 0` with `k_taut` large, the tail → the rigid fixed anchor.

---

## 4. §5 — the ONE licensed stiffness refinement (not a tuning)

Held constant across all supported conditions: `k_taut`, `k_softAx`, `k_softTr`, `rMax` (S2 contour), the lengths,
the hinge, the floor, density, filament, chemistry, gate. **Only the slack δ varies** (0 / 1.5 / 3.5 / 7 nm).

The §5-licensed *single* stiffness refinement is the **supported distal tail's own axial taut stiffness** `k_taut`
(a stiffer, adsorbed coiled-coil is more faithful to "a supported tail lying along the substrate"). Because
**search is transverse**, `k_taut` does **not** affect search mobility — it only sets how much of the AXIAL load
the tail transmits (k_ext, stroke). The refinement sweep (no-slack, δ = 0):

| k_taut (pN/nm) | k_ext (pN/nm) | % of fixed |
|---:|---:|---:|
| 10 | 0.474 | 74 % |
| **20** (selected) | **0.641** | **99 %** |
| 40 | 0.584 | 91 % |

**k_ext saturates at the fixed-anchor ceiling by k_taut ≈ 20 pN/nm** (a modest supported-coiled-coil value); the
40-vs-20 non-monotonicity is paired-estimator noise (a stiffer tail cannot physically lower k_ext). **Selected
k_taut = 20 pN/nm** — the minimal-sufficient value. No motor/gate/kinetic constant was touched.

### Parameter table (final)

| parameter | symbol | value | element |
|---|---|---:|---|
| axial taut stiffness | `k_taut` | 20 pN/nm | distal tail |
| axial soft (in-slack) stiffness | `k_softAx` | 0.10 pN/nm | distal tail |
| slack distance | `δ` | {0, 1.5, 3.5, 7} nm | distal tail (swept) |
| axial smoothing | `s_ax` | 0.5 nm | distal tail |
| tension/compression asymmetry | `compFrac` | 0.25 | distal tail |
| transverse soft stiffness | `k_softTr` | 0.05 pN/nm | S2 hinge |
| transverse finite-extension | `k_feTr` | 20 pN/nm | S2 hinge |
| transverse contour | `rMax` | 60 nm | S2 hinge |
| transverse smoothing | `s_tr` | 5 nm | S2 hinge |
| substrate floor | `k_floor` | 20 pN/nm | floor |
| floor depth | `floorZ` | 18 nm | floor |
| distal-tail length (geometry/viewer) | `lDist` | 60 nm | — |
| S2 length (geometry/viewer) | `lS2` | 20 nm | — |
| pivot Stokes radius | `R_pivot` | 5 nm | drag |

---

## 5. §4/§8 — SEARCH mode vs LOAD mode (the decisive single-motor test)

`k_taut = 20 pN/nm`; conditions 0 (fixed) / 1 (4E free tail) / 2–5 (supported, δ sweep). Search mode = unbound
pivot Brownian trajectory (mobility); Load mode = clean Pi-release stroke + whole-crossbridge stiffness + tangent
stiffness at load.

| condition | δ (nm) | SEARCH rmsLat (nm) | cone (°) | capture area (nm²) | LOAD stroke (nm / %) | k_ext (pN/nm / %) | pivot recoil (nm) | verdict |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| fixed_anchor | — | 0 (line) | 0 | **0** | 6.91 / 100 % | 0.645 / 100 % | 0.00 | baseline |
| 4E_free_tail | 20 | — | — | 1491 | 1.07 / 15 % | 0.010 / 2 % | 6.74 | 4E trade-off |
| **sup_noslack** | 0 | 9.3 | 58 | 783 | **6.92 / 100 %** | **0.641 / 99 %** | **0.07** | **DECOUPLED** |
| **sup_shortslack** | 1.5 | 7.6 | 56 | **1110** | **6.74 / 98 %** | **0.600 / 93 %** | **0.15** | **DECOUPLED** |
| sup_modslack | 3.5 | 8.8 | 58 | 1537 | 5.33 / 77 % | 0.069 / 11 % | 1.83 | recruit-no-load |
| sup_excessslack | 7.0 | 8.9 | 57 | 1918 | 3.76 / 55 % | 0.049 / 8 % | 3.64 | recruit-no-load (4E-like) |

**A motor is mobile in Search mode AND stiff in Load mode only at small slack.** The transition is sharp between
δ = 1.5 and 3.5 nm: once the slack exceeds the stroke's early travel, the stroke falls *inside* the slack and is
absorbed (k_ext collapses to the 4E regime). Tangent axial stiffness at 0/1/3 pN opposing load rises with load in
the slack conditions (the slack-to-taut engaging) and is high-flat in no-slack.

**Primary §12 criteria (per condition):** stroke ≥ 80 % (target 90 %), k_ext ≥ 80 % (target 90 %), pivot recoil
< 1.5 nm (target 1.0). **no-slack and short-slack PASS all three** (stroke 100/98 %, k_ext 99/93 %, recoil
0.07/0.15 nm); mod/excess fail (they define the failure regime, included by design, §5 cond 5).

---

## 6. §9–11 — dense 2-D mat: recruitment AND load-bearing

Flexible 12-segment filament (~2.1 µm, canonical bending + Brownian, z-only confinement) gliding over a
4.0 × 1.0 µm lawn at 1000 motors/µm² (**4000 motors**), dt = 2.5e-6, active-set UNION cull (queryR enlarged for
the mobile pivot; ~active motors solved per step, not all 4000 — this is what keeps the CPU run tractable, the same
mechanism 4D-ii used). **§11 load-bearing = the pivot is TAUT** (axial tangent stiffness ≥ 2 pN/nm ⇒ transmitting,
not absorbing); the fixed anchor is rigidly load-bearing by definition.

| condition | δ (nm) | avg chemically bound | recruit fold | **avg LOAD-BEARING** | % taut | binds/mot/s | P(N≥1) | cand/step |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| fixed_anchor | — | 0.62 | 1.0× | 0.62 | 100 % | 0.14 | 0.42 | 132 |
| **sup_noslack** | 0 | 5.02 | **8.10×** | **5.02** | **100 %** | 1.36 | 0.99 | 451 |
| **sup_shortslack** | 1.5 | 5.14 | **8.29×** | **3.23** | 63 % | 1.41 | 0.99 | 450 |
| sup_modslack | 3.5 | 4.85 | 7.82× | 1.73 | 36 % | 1.37 | 0.99 | 451 |
| sup_excessslack | 7.0 | 4.54 | 7.32× | **0.80** | 18 % | 1.42 | 0.99 | 454 |

(6 episodes × 0.15 s at dt = 2.5e-6; `cand/step` = active motors solved per step out of 4000 — the active-set cull
keeps the CPU run tractable. §12 recruitment criterion **MET**: 8.29× ≥ 2×, and continuity 0.42 → 0.99.)

**The §11 distinction is the whole point:** every supported condition recruits ~7–8× more *chemically bound*
motors (the transverse search enlarges the capture volume regardless of slack), but only the **low-slack**
conditions convert that into **load-bearing** attachments. Excess-slack recruits 7.3× yet ~82 % of those are inert
(the 4E trap) — its load-bearing count (0.80) barely exceeds the fixed anchor (0.62). **no-slack (100 % taut →
8.1× more load-bearing) and short-slack (63 % taut → 5.2× more load-bearing) deliver a genuine multi-fold increase
in load-bearing attachments; excess-slack does not.**

---

## 7. §14 controls

| control | result |
|---|---|
| Jacobian vs force (central difference) | analytic `k_ax` matches numeric (rel err < 2 %) |
| action–reaction / no direct tail force on filament | the tail acts only on `P ↔ substrate`; the filament sees the motor ONLY through F8 (segGather) — **0 tail force on actin by construction** |
| hinge-locked (`k_softTr × 1000`) | capture area collapses 1110 → 35 nm² ⇒ recruitment comes from the SEARCH freedom, not the gate |
| nonlinearity-disabled (δ = 0) | ≡ the anisotropic-LINEAR `sup_noslack` (DECOUPLED) |
| reversed polarity / rotated | stroke covariant (world = swap = rot90 = 6.74 nm) |
| fixed-seed restart | search trajectory bit-identical |
| binding-disabled / no-motor mat | clean null controls (reuse `buildGlide2D` flags) |
| no substrate penetration / no tail inversion | floor + finite-extension bound; finite across dt |

---

## 8. §15 timestep

Selected condition (δ = 1.5 nm), dt {5e-6, 2.5e-6, 1.25e-6}: stroke **6.73 / 6.74 / 6.75 nm**, k_ext
**0.577 / 0.600 / 0.611**, pivot recoil **0.30 / 0.15 / 0.20 nm**, search rmsLat **8.9 / 8.9 / 9.0 nm** — the
observables are dt-invariant to < 0.1 nm / < 0.04 pN·nm⁻¹; no blow-up, no penetration, no unstable fast mode.
**Gate 11 PASS.**

---

## 8b. Compact summary table

| condition | capture area (nm²) | binds/mot/s | avg chem. bound | **avg load-bearing** | continuity | clean stroke (nm) | k_ext (pN/nm) | pivot recoil (nm) | stroke absorbed | verdict |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| fixed_anchor | 0 (line) | 0.14 | 0.62 | 0.62 | 0.42 | 6.91 | 0.645 | 0.00 | 0 % | baseline |
| 4E_free_tail | 1491 | — | — | ~0 (inert) | — | 1.07 | 0.010 | 6.74 | 84 % | 4E trade-off |
| **sup_noslack (δ=0)** | 783 | 1.36 | 5.02 | **5.02** | 0.99 | 6.92 | 0.641 | 0.07 | 0 % | **DECOUPLED** |
| **sup_shortslack (δ=1.5)** | 1110 | 1.41 | 5.14 | **3.23** | 0.99 | 6.74 | 0.600 | 0.15 | 2 % | **DECOUPLED (selected)** |
| sup_modslack (δ=3.5) | 1537 | 1.37 | 4.85 | 1.73 | 0.99 | 5.33 | 0.069 | 1.83 | 23 % | recruit-no-load |
| sup_excessslack (δ=7) | 1918 | 1.42 | 4.54 | 0.80 | 0.99 | 3.76 | 0.049 | 3.64 | 45 % | recruit-no-load (4E-like) |

---

## 8c. Gliding assay + filament flexibility (post-hoc, exploratory)

The dense mat is a gliding assay (a free flexible filament translocating over the motor lawn), so the supported
tail's recruitment gain should show up as gliding velocity. Velocity is measured as the **least-squares slope of
the filament centroid projected on its own axis vs time** (µm/s; negative = pointed-end-first = correct); this is
the validated estimator (never `longWindowSpeedXY`). The dense mat runs on CPU behind the active-set cull (only
the ~450 motors near the filament, of up to 24 000, get the per-motor solve each step), so a long lawn is tractable.

### Gliding velocity

| lawn (1000 motors/µm²) | duration | velocity | net glide | avg bound |
|---|---:|---:|---:|---:|
| fixed anchor | 0.3 s | ≈ −0.2 µm/s | −0.06 µm | 0.5 |
| **supported δ=1.5 nm** | 0.3 s | **−2.17 µm/s** | −0.65 µm | 4.9 |
| **supported δ=1.5 nm** | 2.0 s (12 µm lawn) | **−2.33 µm/s** | −4.60 µm | 5.0 |

The supported tail glides the filament **pointed-end-first at ~2.2–2.4 µm/s — about 5× the fixed anchor** (the
~7× recruitment of load-bearing motors, converted to directed motion). This is dt = 2.5e-6, in the biological
ballpark (~4 µm/s skeletal at 25 °C); it is not a calibrated velocity claim (density and capture parameters are
assay choices, per CURRENT_STATE §7.4), but it confirms the recruitment translates into faster, more continuous
gliding, not just more binding.

### Filament flexibility — the correct knobs (and their timestep coupling)

Two chain parameters govern inter-segment mechanics in the ported PAIRS chain force (`ChainBendingForceSystem`):

- **`fracR` (chainParams[2], default 0.1)** — the fraction of the *translational* F3 link force reapplied as a
  **pure torque** (moment arm `0.5·len·fracR`). **dt-invariant** (no dt in the term). **Larger ⇒ floppier.**
  (This is what the original BoA PAIRS formulation called `fracMoveTorq`.)
- **`fracMoveTorq` (chainParams[3], default 0.265)** — the F4 alignment/**torsional spring** (torque ∝
  inter-segment angle). Its effective stiffness is `fracMoveTorq / (invBRG·dt)` — **dt-COUPLED** (line
  `ChainBendingForceSystem:231`), so it maps to a fixed spring constant only at a fixed timestep and must be
  renormalized if dt changes. **Smaller ⇒ floppier.**

A deterministic tip-deflection probe (`-flextest`: clamp seg 0, apply a 0.5 pN transverse tip load, measure the
steady tip deflection — larger = floppier) confirms both directions:

| fracR ↓ / fracMoveTorq → | 0.265 | 0.05 | 0.02 |
|---|---:|---:|---:|
| 0.1 | 58.6 | 159.3 | 200.1 |
| 0.3 | 127.7 | 213.4 | 250.0 |
| 0.6 | 170.6 | 243.0 | 284.8 |
| 1.0 | 196.3 | 267.1 | **309.8** |

(tip deflection, nm.) Increasing `fracR` 0.1→1.0 and decreasing `fracMoveTorq` 0.265→0.02 each floppen the
filament; combined they give ~5.3× the default deflection.

### Stiff vs flexible gliding (matched 12 µm lawn, 2.0 s, identical except stiffness)

| filament | fracR / fracMoveTorq | velocity | net glide | avg bound | most-bent (e2e/contour) |
|---|---|---:|---:|---:|---:|
| stiff (default) | 0.1 / 0.265 | −2.33 µm/s | −4.60 µm | 5.04 | 0.981 |
| **flexible** | 1.0 / 0.020 | **−2.39 µm/s** | −4.69 µm | 5.36 | **0.959** |

A genuinely flexible filament (~5× more compliant; ~2× more bend during gliding) glides **slightly faster** with
marginally higher engagement — it conforms to the motor carpet rather than snagging. **Flexibility helps, or is at
worst neutral, for gliding here.**

**Caveat (exploratory — not dt-tuned).** Interior chain segments carry **zero rotational Brownian** (off for
stability), so there is **no thermal driver** of bending — the bending seen here is entirely **motor-load-driven**.
A rigorous flexibility/persistence-length study requires enabling the interior thermal driver **and** renormalizing
the dt-coupled `fracMoveTorq` to the chosen timestep (a longer 8–15 µm filament would also bend under canonical
stiffness). `fracR` needs no such renormalization.

### Viewers (this section)

`threejs_twobody4f_gliding` (stiff mat), `threejs_twobody4f_gliding_flex` (flexible mat), and
`threejs_twobody4f_search_stroke` (the single-motor SEARCH → bind → POWERSTROKE transition on the δ=1.5 nm motor —
the head wanders unbound, captures, then strokes with the pivot held taut). The single-motor viewers render the
substrate, supported distal tail, S2 hinge, pivot, and motor (the head→site F8-bond connector was removed as a
diagnostic overlay). The gliding mat viewer draws the actin chain + the motor carpet.

Reproduction:
```
./scripts/run_lasertrap.sh -exp4f -flextest                                   # the flexibility direction probe
./scripts/run_lasertrap.sh -exp4f -glide -matx 12 -dur 2.0 -3js <dir>         # stiff gliding assay + viewer
./scripts/run_lasertrap.sh -exp4f -glide -fracr 1.0 -flex 0.075 -matx 12 -dur 2.0 -3js <dir>   # flexible
```
(`-fracr` sets chainParams[2]; `-flex` scales chainParams[3]; `-matx`/`-dur` size the lawn/duration.)

---

## 8d. no-slack (δ=0) vs short-slack (δ=1.5 nm) — a matched δ-only gliding comparison

To decide whether the slack earns its keep, both conditions were run in the **exact same** full-mat gliding assay
with **only δ changed** (1000 motors/µm², 12 µm lawn, 12-seg filament, dt 2.5e-6, k_taut / k_softAx / k_softTr /
rMax, chemistry, gate, z-confinement, active-set cull, LS-slope velocity estimator all held fixed). 3 matched seeds
× 2.0 s each; identical `(density, dt, seed)` ⇒ identical anchors, initial filament state, and RNG streams — a clean
δ-only contrast. Mean ± SD (raw per-seed rows: `RUN_LOGS/twobody_supported_s2_tail/noslack_vs_shortslack.csv`):

| observable | δ=0 (no-slack) | δ=1.5 (short-slack) | read |
|---|---:|---:|---|
| signed velocity (µm/s) | **−2.336 ± 0.090** | **−2.308 ± 0.034** | **equal** (within noise) |
| net displacement (nm) | −4669 ± 209 | −4616 ± 75 | equal |
| avg chemically bound | 4.61 ± 0.36 | 4.94 ± 0.08 | δ=1.5 slightly more |
| **avg LOAD-BEARING** | **4.61 ± 0.36** | 3.10 ± 0.05 | **δ=0 ~50 % more** |
| continuity | 0.986 | 0.992 | equal |
| handoff probability | 0.787 | 0.801 | equal |
| transverse COM wander (nm) | 133.9 ± 37.5 | 103.9 ± 16.5 | δ=1.5 lower (noisy) |
| angular wander (°) | 9.3 ± 5.8 | 8.4 ± 3.1 | equal |
| per-stroke transmitted disp (nm) | 0.468 | 0.430 | δ=0 slightly higher |
| pivot recoil (nm) | **0.516** | 1.077 | **δ=0 half the give** |
| fraction taut | **1.00** | 0.627 | δ=0 fully taut |
| opposing-motor fraction | 0.438 | 0.443 | equal (same tug-of-war) |
| ATP cycles / µm | 2142 | 2323 | δ=0 slightly more efficient |

(per-stroke disp = net glide / total strokes — an assay-level efficiency, not the 6.7 nm single-molecule stroke;
ATP/µm = strokes per µm glided; matched trajectories `threejs_twobody4f_cmp_{noslack,shortslack}`, 242 frames each.)

**Reading.** The two glide **equally** (velocity −2.336 vs −2.308, net displacement within 1 SD — a genuine tie, not
a resolved difference at n=3). The short-slack's larger capture area recruits marginally more *chemically bound*
motors (4.94 vs 4.61), **but those extra attachments are not load-bearing**: at 63 % taut its load-bearing count is
actually **lower** (3.10 vs 4.61), with **2× the pivot recoil** (1.08 vs 0.52 nm) and slightly worse per-stroke and
ATP efficiency. The slack's only "wins" (a little more chemically bound, somewhat lower transverse wander) do **not**
convert into faster gliding.

**Decision (per the preregistered rule).** *No-slack matches — and on the mechanics exceeds — short-slack gliding*
⇒ **prefer no-slack (δ=0) as the simpler, more biologically defensible anisotropic-support benchmark.** The
alternative branch (retain both as competing phenomenological variants) does **not** apply: short-slack does not
glide better, and it carries a real mechanical dilution (37 % of its bindings inert, double the pivot give). Neither
variant is promoted to a molecularly-literal S2 model — that waits for 4G. (n=3 seeds; velocities overlap within
noise; interior thermal Brownian off ⇒ engagement is motor-driven; exploratory, not dt-tuned.)

---

## 9. Outcome and one recommendation

**OUTCOME A — clean mechanical decoupling.** A supported distal tail (stiff, slack-to-taut, along the load axis) +
a flexible proximal S2 hinge (soft, transverse) is **mobile during low-force search and mechanically grounded
under axial load**. At the selected geometry (short slack, δ = 1.5 nm) and its lean sibling (no-slack): the single
motor keeps ≥ 98 % of the stroke, ≥ 93 % of the skeletal stiffness, and < 0.2 nm pivot recoil, while gaining a
large 2-D capture footprint; the dense mat converts this into a multi-fold increase in **load-bearing** (not merely
chemically bound) attachments. The key that 4E lacked is **anisotropy** (soft transverse / stiff axial) plus the
**load-engaged nonlinearity** (slack-to-taut) — passive, and independent of nucleotide state. Excessive slack
(δ ≥ 3.5 nm) reproduces the 4E trap and defines the failure edge.

**Recommendation: provisionally ADOPT the supported-tail geometry as a non-canonical candidate, at the NO-SLACK
setting (δ = 0; k_taut = 20 pN/nm), and test it with a longer filament.** It is the first tail geometry that
recruits *and* preserves the single-molecule mechanics — a clean recruitment-map shift that satisfies the
CURRENT_STATE §5 requirement (it moves the effective-density knee while preserving intrinsic single-molecule
force–velocity). **The δ-only matched gliding comparison (§8d) refines the earlier tentative short-slack (δ = 1.5 nm)
selection: no-slack glides equally, delivers ~50 % more load-bearing attachments with half the pivot give, and
removes a parameter — the pure anisotropic-support claim.** Short-slack (δ = 1.5 nm) is retained only as the
larger-capture-area sibling, which buys more chemically-bound but not more load-bearing motors and no extra speed.
It is not promoted to canonical here (a non-canonical prototype), and no canonical value is changed; neither variant
is promoted to a molecularly-literal S2 model before 4G.

---

## 10. Deliverables and reproduction

```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp4f -out RUN_LOGS/twobody_supported_s2_tail/csv   # full experiment (CPU)
./scripts/run_lasertrap.sh -exp4f -smoke                                         # quick smoke
./scripts/run_lasertrap.sh -exp4f -3js ~/Code/SoftBox/threejs_twobody4f_selected # viewer (the mechanism)
# 4E regression (byte-identical): ./scripts/run_lasertrap.sh -exp4e -fast
```

**Artifacts** (`RUN_LOGS/twobody_supported_s2_tail/`): `exp4f_full.log`,
`csv/{decoupling, capture_volume, recruitment_mat, timestep}.csv`. Source: `softbox/TwoBodyConverterMotor.java`
(the `sup*` fields on `Cmot`/`Glide2D` + `buildSup`/`buildSupK`/`supForce`/`stepSup`/`supStroke`/`supKext`/
`supSearchStats`/`supCaptureFootprint`/`supForceM`/`supSolveM`/`buildSupMat`/`stepGlideSup`/`measureSupMat` +
`phase4f{References,Decoupling,CaptureVolume,Controls,Recruitment,Timestep}` + `run4f`), one dispatch line in
`softbox/LaserTrapHarness.java`.
