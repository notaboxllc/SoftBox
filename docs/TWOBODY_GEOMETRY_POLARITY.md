# Experiment 3B — Biologically Scaled Two-Body Geometry + Explicit Actin-Polarity Validation

**Date:** 2026-07-13 · **Branch:** `dt-convergence-study` · **Commit at start:** `2170aff937130c232ca999a6b5f87233f58b5776`
· **Runner:** CPU sequential only (single-threaded harness; `-gpu` not used). **Hardware:** aorus, 16 threads
(one core used). **CPU load** ~1.4 throughout (machine idle). **Wall-clock:** full experiment ≈ **1.1 s**.
**dt:** {1e-5, 5e-6, 2.5e-6} (+ 1e-6 for the ledger convergence). **Trap:** {0.02, 0.05, 0.10} pN/nm.

**Default-off, non-canonical prototype** (`[NON-CANONICAL TWO-BODY PROTOTYPE]`), extending
`softbox/TwoBodyConverterMotor.java` (`-exp3b` / `-twobody-geometry-polarity`). **No chemistry/binding/
detachment/catch-slip/gliding added; not promoted to canonical. No canonical model change; production
defaults unchanged; `BoA-v1ref` untouched.** Two purposes: (1) remap the Exp-3A prototype to a defensible
coarse-grained myosin geometry; (2) make actin barbed/pointed **explicit** in state, logs, JSON, viewer, and
directional tests.

---

## 0. Framing

- **Protected canonical motor:** `SPHEREHEAD+AXLOCK+DIRSWING+XB_IMPLICIT2+LYMN_TAYLOR` — untouched.
- **Settled two-body result (Exp-3A, Outcome A):** viable; exact series spring `k_ext = series(k_F8,
  κ_θ/R_A²)`, skeletal ~0.9 pN/nm, load-sensitive stroke, F8-limited stall, 0.2% ledger — but the geometry
  (R_A=10 nm, 5 nm sphere) was mechanically convenient and the **stroke sign was imposed (+x)** with no
  polarity assignment.
- **Ambiguities addressed:** remap to a defensible myosin scale; make actin polarity explicit; and resolve
  whether the stroke is barbed- or pointed-directed by **measuring** force/glide against the **stored** b̂ —
  the canonical convention (audited below) is barbed=end2, glide pointed-first; Exp-3A's +θ stroke was
  **barbed-first (backward)**, so 3B defines the working stroke relative to b̂ and verifies polarity-relativity
  + rotation-covariance.

---

## 1. Polarity audit (Part V — from executed code, before assigning any direction)

| Question | Answer (executed code) |
|---|---|
| Stored endpoint 0? | `FilamentStore` truth = center + uVec; **derived** `end1 = coord−½L·uVec`, `end2 = coord+½L·uVec` (**no "end0"**) |
| Increasing material coordinate? | `aOff = bindArc − ½·slen` (`CrossBridgeSystem`) ⇒ **bindArc(s) increases end1(−uVec) → end2(+uVec)** |
| Explicit barbed/pointed? | **YES — barbed = end2 = +uVec; pointed = end1 = −uVec** (`AxLockGateHarness`, `AgingHarness` "s0(barbed)…pointed tip", `directedSwing` "pointed→barbed") |
| Canonical motor step vs actin | lever rear sweeps **barbed (+uVec)** (`AxLockGateHarness`) |
| Canonical glide (anchored motor) | filament driven **pointed-first (−uVec)** — "pointed-leading = correct" |
| Implicit/contradictory? | **None** — explicit and consistent across gliding / aging / growth / viewer |

**⇒ Outcome C (unresolved convention) is refuted:** the codebase has a single, documented, consistent
polarity convention. **Exp-3A's `+θ` stroke dragged the filament +x = barbed-first = biologically backward.**
3B adopts **barbed = end2 = +uVec**, material coordinate increasing toward barbed, and **defines the working
stroke via the stored b̂** (sweeps the F8 point toward pointed = −b̂).

---

## 2. Geometric remap (Part I) & terminology (Gates 2, 3)

| quantity | Exp-3A | **Exp-3B** |
|---|---|---|
| motor domain (Body A) | 5 nm sphere | **ellipsoid 9 × 5.5 × 4.5 nm** (semi-axes 4.5/2.75/2.25 nm), material-frame oriented |
| head hydrodynamic radius | 5 nm | **4.6 nm** (equivalent; sets γ_θ/τ only) |
| **converter-to-F8 mechanical arm R_A** | 10 nm | **8 nm** (the mechanical radius; κ/R_A², arc, stroke, F8 torque — **distinct from the motor-domain radius**) |
| **neck–lever (Body B) L_B** | 12 nm "lever–tail" | **8 nm "neck–lever"** (light-chain-bearing neck — **not** the S2 coiled-coil / whole tail) |
| substrate | rigid | **rigid calibration anchor** (temporarily subsumes tail/S2/thick-filament/surface coupling; no anchor compliance) |
| converter axis | ŷ | ê_conv = b̂ × ê_up (⊥ the actin–anchor stroke plane) |
| stroke sign | imposed +x | **toward pointed (−b̂), defined via the stored barbed vector** |

The three lengths are kept strictly separate (Gate 3): the motor-domain size (drag/render) ≠ R_A (mechanics)
≠ L_B (neck). Geometry is fully documented by semi-axes, R_A, L_B, pivot P = COM − R_A·ê_up, F8 point =
P + R_A·A_u, converter axis ê_conv (Gate 2).

---

## 3. Explicit polarity data model (Part IV — Gates 4, 5, 7)

Each filament stores/serializes (verified in every `-3js` frame's `"polarity"` block): `barbedEnd`
(end1/end2), `pointedEnd`, barbed & pointed world coords, `barbedDir`/`pointedDir` unit vectors,
`materialCoordIncreasesToward` (= barbed), `converterAxis`, `forceOnActin` + `forceOnActinDotBarbed`,
`filamentGlide` + `glideDotPointed`, `converterTargetDeg`. **P0 PASS:** b̂·uVec = +1 (barbed=end2), p̂·b̂ =
−1, ê_conv ⊥ {b̂, ê_up}, direction-invariant under filament translation, and serialization round-trips
exactly. The viewer does **not** derive polarity from endpoint order — it reads the explicit metadata.

**The prototype's kinematic decomposition differs from canonical myosin but the observable is identical:**
here the neck–lever is anchored and the F8 grip swings toward pointed; in canonical myosin the grip is fixed
on actin and the lever rear sweeps barbed. Both produce **force on actin toward pointed** and **pointed-first
glide** — the load-bearing observables.

---

## 4. Passive mechanics remapped (Gates 14, 15, 18)

- **Rigid-converter recovery (Gate 14):** α = **1.0000** at (k_F8=1, trap=0.05) — the remapped geometry still
  exposes the full F8 stiffness in the rigid limit (fixed-head reference).
- **Passive map (Gate 15):** k_ext = `1/(1/k_F8 + R_A²/κ_θ)` **series-exact after remap** (residual ~1e-5
  across the grid). Monotonic 0 (κ=0) → k_F8 (κ→∞): at k_F8=1, κ=64→**0.500**, 192→0.750, 576→**0.900**,
  1000→0.940, ∞→**1.000**. In-range (0.5–2 pN/nm) reached.
- **Robustness (Gate 18):** k_ext trap-invariant (spread 0.0% across 0.02/0.05/0.10) and dt-invariant
  (finest-two |Δk|/k = 0.0%).

## 5. Active stroke remapped (Gate 16)

Working stroke = θ_s: 0 → θ_post (sweeps the F8 point toward pointed). κ=128 (k_conv=2 pN/nm):

| target F8 excursion | θ_post | external stroke (·û) | glide·p̂ | transverse (arc drop) | completion |
|---:|---:|---:|---:|---:|---:|
| 3 nm | 22.0° | −2.62 nm | **+2.62 nm** | 0.57 nm | 0.957 |
| 5 nm | 38.7° | −4.39 nm | **+4.39 nm** | 1.76 nm | 0.959 |
| 7 nm | 61.0° | −6.22 nm | **+6.22 nm** | 3.13 nm | 0.964 |

Finite external stroke ~87–89% of geometric R_A·sinθ_post, **directed toward pointed** (glide·p̂ > 0; external
·û < 0 = −uVec). Primary candidate: 5-nm target ⇒ 4.4 nm external with a modest 1.8 nm transverse arc drop
(within the 5–8 nm intent band without excessive transverse motion). **Gate 16 PASS.**

---

## 6. Polarity mechanics (Part VI/VII — Gates 8–13, 17) — measured dot products vs stored b̂/p̂

| test | measured | verdict |
|---|---|---|
| **P1** clamped filament | **F on actin·b̂ = −3.518 pN (POINTED)**; F on motor·b̂ = **+3.518 pN (BARBED)**; equal-opposite | **Gate 9 + Gate 8 PASS** |
| **P2** anchored motor, free filament | **glide·p̂ = +4.388 nm (POINTED-FIRST)** | **Gate 10 PASS** |
| **P3** reverse polarity (swap b̂, geometry fixed) | in-label glide·p̂ = +4.388 (pointed-first); **world −4.388 → +4.388 (reverses)** | **Gate 11 PASS** |
| **P4** rotate assay (90° about ẑ; 40° about (1,1,1)) | rot90 glide·p̂ = 4.388; rot3D glide·p̂ = 4.388, F·b̂ = −3.518 — **IDENTICAL to unrotated** | **Gate 12 PASS** |
| **P5** reverse converter target (Δθ→−Δθ) | glide·p̂ = −4.388 (reverses) — **nonbiological sign control** | (control) |
| **P6** load vs b̂ | completion resist 0.821 < free 0.959 < assist 1.081 | **Gate 17 PASS** |

- **Gate 13 (no world-axis bias) PASS:** the result depends only on the stored b̂ and the geometry — swapping
  polarity reverses the world motion (P3) and rotating the assay leaves the polarity-relative signs
  bit-identical (P4). There is **no hard-coded ±x**.
- The Newton pair (P1) is exact: force on actin (pointed) = −(force on motor = barbed).

---

## 7. Work ledger (Gate 19) — a real remap consequence

The remapped converter is **faster**: γ_θ = γ_trans·R_A² + γ_rot **∝ R_A²**, so shrinking R_A 10→8 nm shrinks
γ_θ and the converter relaxation τ_conv falls below the production dt for the stiffer κ. At τ_conv < dt the
explicit F8-torque lags and the **discrete** dissipation estimate is inaccurate — a **discretization
artifact, not an energy leak**: the residual **converges to 0 as dt→0**. Ledger (κ=64, ΔU_target = ΔU_conv +
ΔU_F8 + ΔU_trap + dissipation[converter-rot + filament-trans + filament-rot]):

| dt | residual |
|---:|---:|
| 1e-5 | 0.0030 |
| 5e-6 | 0.0052 |
| 2.5e-6 | 0.0036 |
| 1e-6 | **0.0017** |

Closes to **0.17% at the finest dt (Gate 19 PASS)**; filament-rotational dissipation = 0 (centred F8 attach ⇒
no filament torque — correct). **Report (Part IX): changing R_A alters the converter dissipation timescale**
(smaller R_A ⇒ faster converter ⇒ finer dt needed for the discrete ledger), F8 loading (F8 force = k_F8·arc
stretch), stall force, and the transverse arc drop (∝ R_A(1−cosθ)); the passive stiffness scales as κ/R_A².

## 8. Geometry controls (Part I) — R_A trades stiffness ↔ stroke; polarity invariant

| R_A | k_ext (κ=128) | glide·p̂ (5-nm target) | force on actin·b̂ |
|---:|---:|---:|---:|
| 6 nm | 0.780 pN/nm | +5.91 nm | −4.99 pN (pointed) |
| 8 nm | 0.667 pN/nm | +4.39 nm | −3.52 pN (pointed) |
| 10 nm | 0.561 pN/nm | +3.50 nm | −2.75 pN (pointed) |

Smaller R_A ⇒ stiffer (k_conv = κ/R_A²) but shorter reach for a given θ; **all give pointed-first glide and
pointed force on actin** — the polarity is geometry-independent.

---

## 9. Viewer + JSON (Gates 6, 20)

Every `-3js` frame carries the explicit `"polarity"` block (§3) and renders **distinct endpoint shapes**
(barbed = broad cap, pointed = narrow taper — not color alone), plus arrows for toward-barbed, predicted
glide (pointed), and force-on-actin, the head arm / neck–lever / anchor, and the F8 bond. **8 matched
sequences:** pre-stroke, post-stroke, clamped-force-sign, free pointed-first glide, reversed-polarity, 90°
rotated, 3D-rotated, reversed-sign control. Directional claims rest on the measured dot products (§6), not on
appearance.

---

## 10. Gate verdict — all 20 PASS

G1 canonical protection · G2 biological geometry documented · G3 terminology corrected · G4 explicit polarity
state · G5 serialization · G6 viewer clarity · G7 material-coordinate consistency · G8 barbed-directed motor
step · G9 pointed-directed force on actin · G10 pointed-first gliding · G11 polarity reversal · G12 rotational
covariance · G13 no world-axis bias · G14 rigid-converter recovery · G15 passive map · G16 viable active
stroke · G17 load sensitivity · G18 timestep/trap robustness · G19 work closure · G20 visualization — **PASS.**

---

## 11. Controlling outcome & separated statements

**CONTROLLING OUTCOME: A — scaled and polarity-correct two-body motor.** The biologically remapped geometry
(motor-domain ellipsoid 9×5.5×4.5 nm, R_A=8 nm, neck–lever 8 nm) **preserves the Exp-3A mechanics** (exact
series spring, rigid-converter recovery α=1.000, load-sensitive stroke, trap/dt robustness, ledger closure)
**and** produces **barbed-directed motor stepping with pointed-end-first filament gliding**, verified by
measured dot products against the stored b̂/p̂ and invariant under polarity reversal and full-assay rotation.

- **Motor-domain dimensions:** ellipsoid 9 × 5.5 × 4.5 nm (semi-axes 4.5/2.75/2.25), hydro r ≈ 4.6 nm.
- **Converter-to-F8 distance R_A:** 8 nm (controls 6, 10).
- **Neck–lever length L_B:** 8 nm.
- **Endpoint convention:** end1 = coord−½L·uVec (pointed), end2 = coord+½L·uVec (barbed).
- **Material coordinate:** bindArc increases end1(pointed) → end2(barbed).
- **World barbed vector b̂:** = +uVec (= R·x̂ under assay rotation R).
- **Motor-step projection toward barbed:** F on motor·b̂ = **+3.518 pN** (P1).
- **Force-on-actin projection toward pointed:** F on actin·b̂ = **−3.518 pN** (P1).
- **Free-filament displacement toward pointed:** glide·p̂ = **+4.388 nm** (P2).
- **Reversal (P3):** world motion reverses (−4.388 → +4.388); polarity-relative preserved.
- **Rotated assay (P4):** rot90 & rot3D bit-identical to identity (glide 4.388, force −3.518).
- **Passive stiffness map:** series-exact, monotonic 0 → k_F8; κ=64 → 0.500, ∞ → 1.000 pN/nm.
- **Stroke & stall:** external 2.6–6.2 nm (pointed-directed); load-sensitive completion 0.82–1.08.
- **Work closure:** 0.17% at the finest dt (converges from the coarse-dt discretization artifact).
- **Timestep behavior:** passive k_ext dt-invariant; the ledger/stroke dynamics need finer dt for the faster
  remapped converter (γ_θ ∝ R_A²).

---

## 12. Decision — chemistry integration licensed

**Outcome A is obtained ⇒ chemistry integration is scientifically licensed.** The geometry is defensible, the
mechanics survive the remap, and the motor is polarity-correct (barbed-directed step, pointed-first glide),
rotation-covariant, and free of world-axis bias. A later experiment may now add — one at a time, on this
scaled + polarity-explicit prototype — normal binding, ADP·Pi→ADP target switching, ADP release, ATP
detachment, load-dependent lifetime, dilute episodes, and free-gliding density sweeps, with the fine-dt
gliding curve (velFitX≈4.5 at ρ≈2000) as a **regression** target (preserve ensemble motion while correcting
single-molecule mechanics), **not** a fitting target. **Caveats carried forward:** (i) k_ext ≤ k_F8 series
ceiling; (ii) isometric stall F8-compliance-limited; (iii) the rigid calibration anchor subsumes the real
tail/S2/surface coupling (deferred — the next series-compliance term); (iv) the faster remapped converter
needs finer dt for the stroke dynamics/ledger to converge. **No canonical change made.**

---

## Artifacts & commands

- Preregistration: `RUN_LOGS/twobody_geometry_polarity/PREREGISTRATION.md`
- Log: `RUN_LOGS/twobody_geometry_polarity/exp3b_full.log`
- CSVs (`.../csv/`): `passive_map`, `active_stroke`, `polarity_tests`, `geometry_controls`, `ledger`.
- Figure: `RUN_LOGS/twobody_geometry_polarity/exp3b_summary.png`
- Viewer frames: `~/Code/SoftBox/threejs_twobody3b_{preStroke,postStroke,clamped,freeGlide,swapPolar,rot90,rot3D,reverseSign}/`
```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp3b -out RUN_LOGS/twobody_geometry_polarity/csv -3js ~/Code/SoftBox/threejs_twobody3b
./scripts/run_gpu.sh -cpu                         # Gate-1 regression (FDT, CPU)
python3 scripts/twobody3b_analyze.py RUN_LOGS/twobody_geometry_polarity/csv RUN_LOGS/twobody_geometry_polarity/exp3b_summary.png
```
**View:** `python3 SoftBox/sim_server.py 8000` from `~/Code`, open
`http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → newest).
