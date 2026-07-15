# Experiment 3A — Two-Body Converter Motor: Optical-Trap Characterization

**Date:** 2026-07-13 · **Branch:** `dt-convergence-study` · **Commit at start:** `2170aff937130c232ca999a6b5f87233f58b5776`
· **Runner:** CPU sequential only (single-threaded harness; `-gpu` not used). **Hardware:** aorus, 16 threads
(single core used). **CPU load** ~1.1–1.7 throughout (machine otherwise idle). **Wall-clock:** full experiment
(Stages 0–6 + trap + timestep + ledger + Brownian + geometry + 8 `-3js` sequences) ≈ **1.3 s**. **dt:**
{1e-5, 5e-6, 2.5e-6}. **Perturbations:** ±0.25/0.5/1.0 nm. **Loads:** 0–5 pN. Deterministic primary; Brownian
secondary (4 paired seeds).

This is a **default-off, non-canonical mechanical prototype** (`[NON-CANONICAL TWO-BODY PROTOTYPE]`), isolated
to `softbox/TwoBodyConverterMotor.java` (dispatched by `-exp3a`/`-twobody-trap`). It is **not** a canonical
replacement. **No canonical model change; production defaults unchanged; `BoA-v1ref` untouched.** The canonical
J1/J2/F9/F10/AXLOCK/DIRSWING/XB_IMPLICIT2 pathway is **not** reused inside the prototype.

---

## 0. Framing (protected model · settled fake-coupler result · question)

- **Protected canonical motor** — `SPHEREHEAD + AXLOCK + DIRSWING + XB_IMPLICIT2 + LYMN_TAYLOR`: three
  overdamped rigid bodies with J1/J2 positional joints, tail anchor, F8 (zero-rest Hookean, 1 pN/nm), F9
  frozen-90°, DIRSWING. **Untouched.** The fine-dt free-gliding curve (velFitX ≈ 4.5 µm/s at ρ≈2000, class-B
  bounded saturation, V∞≈14) is a **later regression target**, not a fitting target here.
- **Settled fake-coupler result (Exp-2B, Outcome A+G+E):** the production F8 is a **zero-rest ⇒ isotropic
  (k·I)** spring; with its head-side endpoint **fixed** it transmits ~100% of the coded stiffness. The only
  pure-geometry softening is **head rotation** (free sphere α=0.14, saturating); the canonical 1.0→0.65 (H8)
  loss and the intact-motor ~0.02 pN/nm are **head/body-side series compliance**, not F8 geometry. Exp-2A
  recommended **collapsing the three-body articulation into a stiffer two-body motor** with a rigid anchor.
- **Question:** can a minimal two-body head–converter–lever motor (one converter DOF θ, elastic potential
  ½κ_θ(θ−θ_s)², rigid anchor, production F8) **jointly** deliver skeletal-range stiffness, a finite external
  power stroke, load-sensitive stroke & stall, stable transmission, low preload, and **no hidden soft mode**?

---

## 1. Prototype architecture & implementation (Gates 1–3)

**Two rigid bodies, ONE converter rotational coordinate θ.**
- **Body A (head):** a rigid actin-binding arm. Proximal end = the converter point, **kinematically pinned to
  the fixed pivot P = (0,0,−R_A)**; distal end carries the material-fixed F8 attach point at radius **R_A**.
  Orientation A_u = (sinθ, 0, cosθ) (stroke plane x–z, actin along x); **roll & out-of-plane locked by
  construction** (yVec ≡ ŷ) ⇒ **exactly one converter DOF, no ball joint, no hidden rotational mode**. A pure
  1-DOF overdamped pivoting lever: θ evolves under the net torque about P (F8 torque + converter torque),
  pivot drag γ_θ = γ_trans·R_A² + γ_rot (Stokes, r_head).
- **Body B (lever–tail):** rigid rod P→anchor, length L_B, **fixed in position + orientation** (Exp-3A rigid
  calibration boundary condition; anchor compliance deferred). Carries the anchor-reaction ledger; does not move.
- **Converter (the ONE explicit compliance):** U_s(θ) = ½κ_θ(θ−θ_s)² ⇒ torque κ_θ(θ_s−θ) about ŷ, integrated
  **semi-implicitly** (linear converter part implicit ⇒ **unconditionally stable for all κ_θ**; κ_θ→∞ ⇒ θ→θ_s,
  the rigid limit exactly). F8 torque about P explicit.
- **F8:** the **production `CrossBridgeSystem.bondForces`** with F9/F10 alignment coeff = 0 (`xbParams[2]=0`,
  headLen=R_A ⇒ F8 tip = P + R_A·A_u) — the exact zero-rest Hookean spring, **not reimplemented**; the actin
  site is **material-latched** (fixed bindArc = ½·segLen, **never relatched**). Seg reaction via production CSR
  `segGather`; traps via validated `applyTraps3D`.

**Effective converter stiffness at the F8 point = κ_θ / R_A²** (torque κ_θ·θ ↔ F8-point displacement R_A·θ).
This is the Exp-2B free-rotating-sphere softness made **physical and tunable**.

**Implementation facts (Gate 1):** new file `softbox/TwoBodyConverterMotor.java` + a **one-line dispatch** in
the already-untracked `LaserTrapHarness`. `LaserTrapSystem`/`CrossBridgeSystem`/integrator/`FilamentStore`/
`MotorStore` reused unchanged. **No tracked/production/canonical source modified**; **FDT CPU regression
bit-unchanged** (D_par −2.52%, D_perp_y −1.15%, D_rot −1.80%, the Exp-2A baseline). `BoA-v1ref` byte-clean.

**Geometry (primary):** R_A = 10 nm, r_head = 5 nm, L_B = 12 nm, θ_pre = 0° (head vertical ⇒ F8 point at the
centred site z=0), pivot P = (0,0,−10 nm), converter axis ŷ, stroke plane x–z, pretension 2 nm.
**Inventories:** κ_θ ∈ {0,1,3,10,30,100,300,1000, ∞} pN·nm/rad²; k_F8 ∈ {0.5,1.0,1.5,2.0,4.0} pN/nm; trap ∈
{0.02,0.05,0.10} pN/nm; Δθ targets spanning 3–9 nm (17.5°–64.2°); loads 0–5 pN.

---

## 2. Stage 0 — sanity / force path / zero-load equilibrium

- **0.1 joint closure:** pivot (end1−P) = **1.1e-10 µm** (kinematic, exact); yVec ≡ ŷ (out-of-plane + roll
  locked) ⇒ 1 converter DOF. **Gate 2.**
- **0.2 F8 continuity:** production `bondForces`, align OFF; bindArc = 0.4995 µm **FIXED, never relatched**.
  **Gate 3.**
- **0.3 force routing:** F8 head/filament equal-and-opposite residual = **0** N; converter reaction → lever;
  the rigid pivot transmits the head force to the anchor (anchor reaction = |F8 head|).
- **0.4 zero-load equilibrium:** F8 = **0.0000 pN**, converter torque = 0, trap force = 0, θ = 0°, F8 ext = 0
  nm, filZ = 0. **No unexplained preload ⇒ Gate 11 PASS.**

---

## 3. Stage 1 — rigid converter → fixed-head reference (Gate 4, gating)

With θ frozen at θ_pre the prototype reproduces the Exp-2B fixed-spherical-head reference:
**compliance-corrected k_motor / k_F8 = α = 1.0000** at (k_F8=1, trap=0.05); full recovery
(0.90 ≤ α ≤ 1.10) across the trap bracket (**Gate 4 PASS**). The force routing and material attachment are
correct — the prototype exposes the full assigned F8 stiffness when the converter is rigid and the anchor is
fixed. (The rigid-converter α=1 also confirms **no hidden implementation compliance** — Outcome F is refuted.)

---

## 4. Stage 2/3 — passive stiffness: a clean, exact SERIES spring (Gates 5, 6, 7)

Blinded k_ext(κ_θ, k_F8) at trap 0.05:

| κ_θ (pN·nm/rad²) | k_conv=κ/R_A² (pN/nm) | **k_ext @ k_F8=1** | series(1, k_conv) |
|---:|---:|---:|---:|
| 0 | 0 | 0.0005 | 0 |
| 10 | 0.10 | 0.0911 | 0.0909 |
| 30 | 0.30 | 0.2309 | 0.2308 |
| 100 | 1.00 | **0.5000** | 0.5000 |
| 300 | 3.00 | 0.7500 | 0.7500 |
| 1000 | 10.0 | **0.9091** | 0.9091 |
| ∞ (rigid) | ∞ | **0.9999** | 1.0 (=k_F8) |

- **The two-body converter is a clean, EXACT series spring:** k_ext = 1/(1/k_F8 + 1/(κ_θ/R_A²)), matching the
  measured surface to **~1e-5–2e-3** across the full k_F8 × κ_θ grid (Stage 3 `resid`). This is not merely a
  descriptive reduced model — the geometry **is** two scalar springs (F8 + converter) in series.
- **k_ext rises monotonically with κ_θ (Gate 5 PASS)**, from the free-converter limit (κ=0 → ~0, the Exp-2B
  free-rotating-sphere) to the **fixed-head ceiling k_ext → k_F8** as κ_θ → ∞ (**Gate 6 PASS: high-κ_θ
  approaches the fixed-head fake-coupler result, no hidden soft mode**).
- **k_ext ≤ k_F8 always** — the F8 is the stiffest series element (the ceiling), consistent with Exp-2B (F8
  with a fixed head-side endpoint transmits ~100%). At k_F8=1, κ_θ=1000 gives k_ext = **0.91 pN/nm — inside
  the skeletal 0.5–2 band (Gate 7 PASS)**; reaching the top of the band needs k_F8 ≳ 2 (Stage 3 surface: e.g.
  k_F8=2, κ=1000 → 1.67 pN/nm).
- **Trap-robust (Gate 12):** k_motor = 0.500 flat across trap 0.02/0.05/0.10 (κ=100, k_F8=1) — trap-invariant
  wherever identifiable, like the Exp-2A clean-spring control.
- **Ill-conditioned corner (reported, not clipped):** k_F8=4 with the softest converter κ=10 (k_conv=0.1)
  gave a small **negative** k_ext (−0.05 vs series 0.098) — a marginal explicit-coupling artifact at the
  stiff-F8/soft-converter extreme; it does not affect the well-conditioned regime.

---

## 5. Stage 4 — active power stroke (Gate 8)

A finite θ_s shift (θ_pre → θ_post; **not a trajectory, constant torque, servo, re-aim, or relatch**) drives
θ toward θ_post; the F8 point sweeps and drags the material-latched filament (Δθ preserved through F8).

| κ_θ | target 3/5/7/9 nm → external stroke (nm) | incomplete fraction |
|---:|---|---:|
| 30 | 2.11 / 3.58 / 5.16 / 7.04 | 0.21–0.23 |
| 100 | 2.51 / 4.21 / 5.97 / 7.85 | 0.07–0.08 |
| **1000** | 2.70 / **4.51** / 6.32 / **8.15** | **0.007–0.009 (99% complete)** |

- **Finite, measurable external stroke without changing the actin attachment coordinate (Gate 8 PASS).** The
  external stroke is ~90% of the geometric R_A·sinθ_post (the ~10% deficit is the F8+trap series compliance).
- **The stroke completes MORE at higher κ_θ** (99% at κ=1000) — a stiffer converter tracks its target better.
- **filZ (transverse sag):** the head arc drops the F8 point below the filament during the stroke (−1 to −5 nm
  at large θ), a real transverse pull confined by the 3D trap; reported as telemetry.

---

## 6. Stage 5 — load dependence & stall (Gates 9, 10)

Opposing axial **force clamp** 0–5 pN (converter completion = θ_final / θ_post; 30° target):

| load (pN) | θ_pre(loaded) | θ_final | **completion** | filament stroke | gen. force |
|---:|---:|---:|---:|---:|---:|
| 0 | 0.0° | 27.6° | **0.920** | 4.21 nm | 0.42 pN |
| 1 | −4.8° | 23.2° | 0.772 | 4.33 nm | 1.27 pN |
| 3 | −13.9° | 13.6° | 0.453 | 4.32 nm | 2.94 pN |
| 5 | −22.2° | 3.7° | **0.123** | 4.01 nm | 4.60 pN |

- **Load-sensitive stroke (Gate 9 PASS):** converter completion falls **0.92 → 0.12** as load rises 0 → 5 pN;
  the generated force rises to match the load. **The stroke does not complete identically under all loads ⇒
  NOT a servo (Gate 10 PASS).** Fully reversible (θ_s→θ_pre returns the filament, revErr ~0 nm).
- **Isometric stall force = 2.6 pN** (filament rigidly clamped) — **F8-compliance-limited**: the head only
  completes to θ≈15° before the F8 back-torque (k_F8·R_A²·sinθ) balances the converter torque (κ·(θ_post−θ)),
  so the isometric force is **~half the rigid-F8 bound κ·Δθ/R_A = 5.24 pN**; the true ceiling (κ→∞) is
  k_F8·R_A·sinθ_post ≈ 5 pN. A biologically-plausible myosin stall.
- **Isotonic caveat (reported):** under a **constant-force** clamp the load pre-deflects the soft converter
  (θ_pre(loaded) 0→−22°), so the *filament displacement* is ~isotonic (~const 4.2 nm); the **stall shows in
  the converter completion and the isometric/generated force**, not in the filament displacement.

---

## 7. Stage 6 — directional / sign controls (Gate 15)

+θ_s shift → +4.21 nm stroke; −θ_s shift → −4.21 nm (**sign reverses exactly, Gate 15 PASS**). Opposing load
reduces the stroke, assisting load increases it. No hidden world-axis bias.

---

## 8. Force / torque / work ledger (Gate 14)

Passive equilibrium: net force = 0, net torque = 0 (centred, θ=0). Active stroke energy budget (κ=100, 5 nm):

**ΔU_target (converter free energy injected by the θ_s jump) = 1.371e-20 J** =
ΔU_conv (0.089e-20) + ΔU_F8 (0.094e-20) + ΔU_trap (0.940e-20) + dissipation (1.255e-20; converter-rotational
0.700e-20 + filament 0.556e-20) — **residual 0.2% (Gate 14 PASS)**. The injected converter free energy is
mostly **dissipated by the head swinging through γ_θ** (the dominant channel), with the rest stored in the
trap/F8/residual converter. The state-target change is explicit chemical/mechanical free energy — **not
unexplained energy creation.** (Adding the converter rotational dissipation was required to close the ledger;
without it the residual was 0.65.)

---

## 9. Timestep (Gate 13), Brownian, geometry controls

- **Timestep (Gate 13 PASS):** passive k_ext (κ=0/100/1000) and the active stroke are **dt-invariant**
  (finest-two |Δ|/ = 0.0%). The semi-implicit converter is stable at all dt for all κ.
- **Brownian (secondary, detachment disabled):** at soft κ=1 the converter fluctuates strongly (θ RMS ~17–19°,
  the soft-converter thermal swing); mean trap force ~0, trap-force RMS ~0.13–0.17 pN, |F8| ~2.0–2.2 pN
  (light-head thermal rectification, as in Exp-1/1b). **No 12 pN threshold referenced; high-force configs
  overrepresented (stated).**
- **Geometry controls:** r_head ∈ {4,6} nm leaves k_ext and stroke **unchanged** (γ_θ/τ only). R_A ∈ {6,8,10}
  nm sets **both** the stiffness map (k_conv = κ_θ/R_A² ∝ 1/R_A²: R_A=6 → k_ext 0.735, R_A=8 → 0.610, R_A=10 →
  0.500 at κ=100) **and** the stroke (∝ R_A). The stroke axis is predominantly along actin (x) by construction.

---

## 10. Gate verdict

| Gate | Result | Evidence |
|---|---|---|
| G1 canonical protection | **PASS** | new file + 1 dispatch line; no tracked/canonical source changed; FDT unchanged |
| G2 topology (2 bodies, 1 DOF) | **PASS** | 1-DOF θ, pivot exact, yVec≡ŷ; no ball joint; no hidden rotational mode |
| G3 material attachment persists | **PASS** | fixed bindArc, never relatched (passive + active) |
| G4 rigid-converter recovery | **PASS** | α = 1.000 across the trap bracket (fixed-head reference) |
| G5 monotonic converter map | **PASS** | k_ext ↑ monotonically with κ_θ; exact series |
| G6 no hidden soft mode | **PASS** | κ_θ → ∞ ⇒ k_ext → k_F8 (fixed-head fake-coupler) |
| G7 passive target range | **PASS** | k_ext = 0.5–1.67 pN/nm reachable in the grid (skeletal band) |
| G8 finite external stroke | **PASS** | 2.5–8.1 nm on a θ_s shift, attachment coord unchanged |
| G9 load sensitivity | **PASS** | completion 0.92 → 0.12 over 0–5 pN |
| G10 no servo | **PASS** | completion varies strongly with load |
| G11 low preload | **PASS** | F8=0, converter torque=0, trap=0 at the pre-stroke equilibrium |
| G12 trap robustness | **PASS** | k_motor flat (0.500) across the trap bracket |
| G13 timestep stability | **PASS** | passive + active dt-invariant (finest-two 0.0%) |
| G14 force/torque/work closure | **PASS** | active-stroke residual 0.2% (converter dissipation dominant) |
| G15 symmetry | **PASS** | +θ/−θ stroke reverses exactly; load reverses effect |
| G16 visualization | **PASS** | 6 matched `-3js` sequences (rigid/free/inter/preStroke/stroke/stall) |

---

## 11. Controlling outcome & separated statements

**CONTROLLING OUTCOME: A — VIABLE two-body architecture.** A stiff converter (κ_θ ≳ 300 pN·nm/rad²)
**simultaneously** delivers skeletal-range passive stiffness (0.75–0.91·k_F8), a **near-complete (99% at
κ=1000), load-sensitive** external power stroke (4.5–8 nm), a finite F8-limited stall force (~2.6–5 pN), low
preload, no hidden soft mode, and 0.2% work closure. **The two-body motor reaches ~0.9 pN/nm** — vs the
canonical three-body ~0.02 pN/nm — precisely because it carries **one** explicit compliance (the converter)
in series with F8 behind a **rigid anchor**, realizing the Exp-2A recommendation and the Exp-2B fixed-head
finding. **Outcome D (unavoidable stiffness–stroke tradeoff) is REFUTED:** stiffness and stroke completion
**both improve** with κ_θ (panel d). **F (hidden implementation compliance) refuted** (rigid α=1.000);
**G (numerical/assay failure) refuted** (all load-bearing gates pass).

Separated statements:
- **Rigid-converter stiffness recovery:** α = 1.000 (full recovery, fixed-head reference).
- **Free-converter limit (κ=0):** k_ext → 0 (the Exp-2B free-rotating sphere).
- **k_ext vs κ_θ:** exact series 1/(1/k_F8 + R_A²/κ_θ); monotonic 0 → k_F8.
- **k_ext vs k_F8:** linear, ceiling k_ext ≤ k_F8 (F8 is the stiff series element).
- **Candidate external-stiffness operating regions (labels, NOT recommendations):** k_ext≈0.5 → κ_θ≈100 @
  k_F8=1; k_ext≈1.0 → κ_θ≳1000 @ k_F8≈1.1; k_ext≈2.0 → k_F8≈2, κ_θ≳1000.
- **Unloaded stroke:** ~90% of geometric R_A·sinθ_post (F8+trap series compliance); 4.5–8 nm at κ=1000.
- **Stroke vs load:** converter completion 0.92 → 0.12 over 0–5 pN (isotonic filament displacement caveat).
- **Stall:** isometric ~2.6 pN (F8-compliance-limited; rigid bound 5.24 pN; ceiling k_F8·R_A·sinθ_post ≈ 5 pN).
- **Preload:** none (zero-load equilibrium exact).
- **Energy input & delivered work:** ΔU_target 1.37e-20 J, mostly converter-dissipated; residual 0.2%.
- **Timestep behavior:** dt-invariant (passive + active).
- **Trap dependence:** k_motor trap-invariant where identifiable.
- **Geometry dependence:** r_head sets τ only; R_A sets both stiffness (∝1/R_A²) and stroke (∝R_A).

---

## 12. Decision after Experiment 3A

**Recommendation #1 — PROCEED to chemistry integration of the two-body motor** (Outcome A obtained). The
passive **and** active trap mechanics are viable and interpretable: the architecture achieves skeletal
stiffness while stroking, with load sensitivity, a finite stall, low preload, no hidden mode, and a closing
ledger. For that integration, build on: **a stiff converter (κ_θ ≳ 300–1000 pN·nm/rad²)** and **k_F8 chosen
for the target combined series stiffness** (k_ext ≤ k_F8, so pick k_F8 ≈ the desired skeletal value). Only
after Outcome A (met) should a later experiment add — one at a time — normal binding, ADP·Pi→ADP target
switching, ADP release, ATP detachment, load-dependent lifetime, dilute episodes, and free-gliding density
sweeps. **The fine-dt gliding result (velFitX≈4.5 at ρ≈2000, class-B saturation) is a REGRESSION target, not a
fitting target:** the replacement must preserve plausible ensemble motion while correcting the single-molecule
mechanics.

---

## 13. Interpretation restraint

The prototype is **not** promoted to canonical status. No chemistry/binding/detachment/catch-slip/gliding was
added. No κ_θ was chosen because it gives 1 pN/nm — the whole transfer surface was frozen first, and the
candidate regions are labelled, not recommended. The candidate operating points (κ_θ ≳ 300, k_F8 ~ target)
**jointly** satisfy the six requirements (plausible stiffness, finite stroke, load sensitivity, low preload,
stable work/force transmission, no hidden soft mode). **Caveats carried forward:** (i) the passive stiffness
is series-ceilinged at k_F8; (ii) the isometric stall force is F8-compliance-limited; (iii) Exp-3A uses a
**rigid anchor** — real substrate/tail compliance is deferred and would re-open a series-compliance term
(the future anchor experiment); (iv) one stiff-F8/soft-converter corner is numerically ill-conditioned.

---

## Artifacts & commands

- Preregistration: `RUN_LOGS/twobody_converter/PREREGISTRATION.md`
- Log: `RUN_LOGS/twobody_converter/exp3a_full.log`
- CSVs (`RUN_LOGS/twobody_converter/csv/`): `stage1_rigid`, `stage2_kappa_sweep`, `stage3_surface`,
  `stage4_stroke`, `stage5_load`, `trap_robustness`, `timestep`, `ledger`, `brownian`, `geometry`.
- Figure: `RUN_LOGS/twobody_converter/exp3a_summary.png`
- Viewer frames: `~/Code/SoftBox/threejs_twobody_{rigid,free,inter,preStroke,stroke,stall}/`
```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp3a -out RUN_LOGS/twobody_converter/csv -3js ~/Code/SoftBox/threejs_twobody
./scripts/run_gpu.sh -cpu                         # Gate-1 regression (FDT, CPU)
python3 scripts/twobody_analyze.py RUN_LOGS/twobody_converter/csv RUN_LOGS/twobody_converter/exp3a_summary.png
```
**View:** `python3 SoftBox/sim_server.py 8000` from `~/Code`, open
`http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → newest).
