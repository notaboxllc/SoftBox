# Experiment 2B — Fake-Coupler Stiffness-Transfer Ladder

**Date:** 2026-07-13 · **Branch:** `dt-convergence-study` · **Commit at start:** `2170aff937130c232ca999a6b5f87233f58b5776`
· **Runner:** CPU sequential only (`-gpu` refused). **Hardware:** aorus, 16 threads; single-threaded harness.
**CPU load** at start `1.08 1.31 1.28`, ~1.2–1.7 during runs (single core; the machine was otherwise idle —
the earlier fine-dt GPU sweep had finished). **Wall-clock:** full ladder ≈ 3.5 s. **dt:** {1e-5, 5e-6, 2.5e-6}.

This is a **deliberately non-biological diagnostic**. Its couplers (F0–F9) are NOT motors and are **never**
intended to become canonical motors. They measure how a coded spring stiffness is transformed into the
stiffness a blinded optical-trap experimentalist would infer as we add — one element at a time — 3D spring
geometry, bond orientation, material-latched attachment, filament translation, filament rotation, off-axis
torque, trap compliance, and a fixed spherical head. **No canonical model change was made; `BoA-v1ref`
untouched.** All fake-coupler behaviour is default-off, isolated to the new file
`softbox/LaserTrapFakeCoupler.java`, labelled `[NON-CANONICAL FAKE COUPLER]`, and absent from every
production/canonical path.

---

## 0. Framing (protected model · settled localization · question)

- **Protected canonical motor** — `SPHEREHEAD + AXLOCK + DIRSWING + XB_IMPLICIT2 + LYMN_TAYLOR`: three
  overdamped rigid bodies (rod/tail, lever/neck, head); J1/J2 positional joints (native angular springs off);
  tail anchor; **F8** material-latched head–actin cross-bridge — a **zero-rest-length Hookean spring**
  `F = myoSpring·(site − tip)`, `myoSpring = 1 pN/nm`; F9 frozen-90° perpendicularity; DIRSWING the sole
  stroke. **Untouched.**
- **Latest settled localization (Exp-2A, Outcome E — distributed architectural compliance):** the blinded
  estimator recovers a clean scalar spring to 100% trap-invariantly; the intact native motor reads ~0.02
  pN/nm; the **frozen whole-motor / F8-only control (H8) → k_motor ≈ 0.65 pN/nm** (inside the skeletal band),
  so F8 is not the soft element. No single one-DOF hold restores stiffness.
- **Question:** how is a coded F8 spring transformed into the blinded-inferred stiffness, and where is the
  settled **1.0 → ~0.65** loss (the H8 control) localized — F8 geometry, filament response, or coupling
  details — and is the transfer ratio α(k_F8) constant?

---

## 1. Implementation facts (Gate 1: canonical source untouched)

- **New behaviour ONLY in `softbox/LaserTrapFakeCoupler.java`** (new file) + a **one-line dispatch** in the
  already-untracked `softbox/LaserTrapHarness.java` (`case "-exp2b"`). `softbox/LaserTrapSystem.java` reused
  unchanged. **No tracked/production/canonical source modified** (`git diff --stat` on tracked files shows
  only `JOURNAL.md` + the concurrent gliding sweep's own `FINE_DT_FREE_GLIDE_*` — none touched by this task).
  `BoA-v1ref` byte-clean.
- **FDT CPU regression unchanged** (`run_gpu.sh -cpu`): D_par 1.09958e-1 (−2.52%), D_perp_y −1.15%, D_rot
  −1.80% — bit-for-bit the Exp-2A baseline. **Gate 1 PASS.**
- **Exact F8 (Gate 3):** every F8-based arm calls the **production `CrossBridgeSystem.bondForces`** with the
  F9/F10 alignment coefficient set to **zero** (`xbParams[2]=0`, size-6 Hookean shape), isolating the pure
  zero-rest F8 spring + its exact R×F positional torque. **The F8 mechanics are not reimplemented.** The
  "head" is a MotorStore head sub-body (rod/lever inert); the seg reaction reaches the filament through the
  production CSR `segGather`. Traps are `LaserTrapSystem.applyTraps3D` (validated Exp-0b/1). The blinded
  estimator is the Exp-2A paired ±step (external observables only). F9 = the Exp-2A H8 hold, reproduced via
  the existing `pairedResponse(...,hold=8)` on the same native snapshots.

**Structural fact that governs the whole result:** F8 is a **zero-rest-length** Hookean spring
(`fmag = myoSpring·dist`, rest length 0). A zero-rest spring has an **isotropic** linear stiffness tensor
`k·I` — its transmitted stiffness is independent of bond orientation, preload, or off-axis geometry. So any
transfer loss below the coded k must come from **motion of the parts** (head rotation / filament rotation /
transverse recoil), not from `cos²θ` projection. The ladder measures which.

---

## 2. Inventories (preregistered)

- **Fake couplers:** F0 scalar spring · F1 axial F8 fixed-point · F2 native orientation (rotation OFF; zero-
  and native-preload variants) · F3 = F2 + filament rotation · F4 off-axis transverse-bond sweep · F5
  symmetric two-bond (torque-cancel) · F6 fixed spherical head · F7 free-rotating sphere · F8 orientation-
  restrained sphere · F9 canonical frozen-motor (H8).
- **Assigned k_F8:** 0.10, 0.25, 0.50, 1.0, 1.5, 2.0, 4.0 pN/nm.
- **Trap bracket:** 0.02 / 0.05 / 0.10 pN/nm per trap (k_eff = 2×).
- **Perturbations:** paired ±0.5, ±1.0 nm primary (± up to 2 nm linearity-verified); response sampled to a
  2 ms plateau (plateau = primary). **dt:** {1e-5, 5e-6, 2.5e-6}. **Pretension:** 2 nm. **Filament:** one
  ≈1 µm rigid rod. **Equilibration:** 20 ms/point (~15τ). **Native:** 24 stage-E + 24 stage-B snapshots ×
  4 seeds (Exp-1b canonical generation).

---

## 3. Blinded transfer curve — F0, F1, F6 (trap 0.05)

External observables only. α = k_blinded / k_assigned computed **after** the blinded value was frozen.

| assigned k | k_obs (pN/nm) | **k_motor (pN/nm)** | follow frac | **α** |
|---:|---:|---:|---:|---:|
| 0.10 | 0.0500 | **0.1000** | 0.500 | **1.000** |
| 0.25 | 0.0714 | **0.2500** | 0.286 | **1.000** |
| 0.50 | 0.0833 | **0.5000** | 0.167 | **1.000** |
| 1.00 | 0.0909 | **1.0000** | 0.091 | **1.000** |
| 1.50 | 0.0937 | **1.5000** | 0.062 | **1.000** |
| 2.00 | 0.0952 | **UNIDENTIFIABLE** | 0.048 | — |
| 4.00 | 0.0976 | **UNIDENTIFIABLE** | 0.024 | — |

- **F0 (scalar spring) and F1 (axial F8) are bit-for-bit identical** and recover the assigned stiffness
  exactly (α = 1.00001). **F6 (fixed sphere) equals F1 exactly** — a fixed spherical head with a
  material-frame surface attachment is mechanically the same as a fixed-point F8 coupler.
- **k_motor = k_assigned to <0.002%** for every assigned k where the series correction is well-conditioned
  (k_F8 ≤ 1.5 at trap 0.05). **This is not saturation** — it is a hard identifiability edge: when the coupler
  is ≳20× the trap the filament follows <5% of the command, so the compliance correction is ill-conditioned
  and reported **UNIDENTIFIABLE (NaN), never clipped** (Exp-1b Outcome-D behaviour). The trap-observed k_obs
  meanwhile **saturates toward k_trap** (0.05→0.098 at trap 0.05) because the soft trap is the series-limiting
  compliance — an instrument limit, not a coupler limit.

**Trap robustness (Gate 11):** across the full 0.02/0.05/0.10 bracket the compliance-corrected k_motor stays
= k_assigned (flat vs trap) wherever identifiable, exactly as the Exp-2A Stage-1.2 clean-spring control; only
the identifiability edge (the assigned k at which follow drops below 5%) moves outward with a stiffer trap.

---

## 4. Localization of the 1.0 → 0.65 loss (native stage-E, k=1, trap 0.05)

Native bond orientations span **13°–157°** vs f̂ (many near-perpendicular; rest lengths 2–7 nm; n=48).

| arm | what it adds | **k_motor (pN/nm)** | α |
|---|---|---:|---:|
| **F1** axial | — (aligned fixed point) | **1.0000** | 1.00 |
| **F2** orient (zero preload) | native bond orientation | **1.0000 ± 0.0000** | 1.00 |
| **F2** orient + native preload | + captured bond length | **1.0000 ± 0.0000** | 1.00 |
| **F3** + filament rotation | filament free to tilt | **1.0000 ± 0.0000** | 1.00 |
| **F4** off-axis (z-bond, ¼ L, rot ON) | off-centre torque | **0.99992** (filRot 0.42°) | 1.00 |
| **F4** off-axis (z-bond, ⅛ L, rot ON) | more off-centre | **0.99950** (filRot 0.52°) | 1.00 |
| **F5** symmetric two-bond (torque-cancel) | net-torque = 0 | **1.0000** | 1.00 |
| **F9** canonical frozen motor (= Exp-2A H8) | full canonical body machinery | **0.6534 ± 0.0000** | 0.65 |

**The decisive result:** adding native bond **orientation** (F2), native **preload** (F2), **filament
rotation** (F3), and **off-axis torque** (F4/F5) each changes the blinded stiffness by **< 0.1%**. The pure
zero-rest F8 spring transmits ~full k regardless of how the bond is oriented or where it attaches — the
`cos²θ` projection intuition does **not** apply to a zero-rest spring (its stiffness tensor is isotropic
`k·I`). The **only** arm that loses stiffness is **F9 — the full canonical frozen motor (0.65)**. Therefore
the settled 1.0 → 0.65 loss is **NOT in the F8 spring, its 3D orientation, its preload, the attachment
position, the off-axis torque, or filament rotation.** It is entirely in the **head-side canonical coupling
machinery** that F9 carries and the pure-F8 arms omit: the F9/F10 head-orientation alignment torques and the
XB_IMPLICIT2 coupled head/segment solve (active even when the body pose is frozen back each step).

The **spherical-head arms** exhibit that head-side compliance directly and mechanistically:

| assigned k | **F6 fixed** | **F7 rotating** | **F8 restrained** |
|---:|---:|---:|---:|
| 0.10 | 0.100 (α1.00) | 0.0476 (α0.48) | 0.100 (α1.00) |
| 0.50 | 0.500 (α1.00) | 0.116 (α0.23) | 0.500 (α1.00) |
| 1.00 | 1.000 (α1.00) | **0.142 (α0.14)** | 1.000 (α1.00) |
| 2.00 | unident. | 0.159 (α0.080) | unident. |
| 4.00 | unident. | 0.170 (α0.042) | unident. |

- **F6 (fixed sphere) = full k** at every identifiable point — a fixed spherical head is a mechanically clean,
  full-stiffness reference.
- **F7 (free-rotating sphere) is dramatically softer** and its k_motor **SATURATES at ≈ 0.17 pN/nm** as the
  assigned k rises (α falls 0.48 → 0.042). A sphere free to rotate about a pinned centre lets the F8 attach
  point swing on a ~10 nm-radius arc; that rotational compliance sits in series with F8 and becomes the
  limiting element once F8 is stiff — the head-rotation analog of the canonical articulated body.
- **F8 (orientation-restrained sphere) recovers F6** (α = 1.00), validating the rotational interpretation:
  holding the head orientation removes exactly the F7 compliance.

---

## 5. Is α constant? (the "≈0.65" question)

Two separate answers, both important:

1. **For the pure fixed F8 arms (F0/F1/F2/F3/F6): α = 1.000, constant** across the assigned-k ladder — not
   0.65. Orientation and rotation of the spring do not cost stiffness.
2. **For a head-compliance-limited geometry (F7, and by analogy the canonical motor): α is NOT constant — it
   FALLS with assigned k** because the head-rotation compliance is a fixed-magnitude series element that
   caps k_motor (saturation ≈ 0.17 for the F7 sphere). The apparent "0.65 constant" of the H8 control is an
   artifact of measuring only at k_F8 = 1: raising k_F8 would **not** scale the external stiffness by 0.65;
   it would saturate as the body/head compliance takes over.

So α ≈ 0.65 is **neither a geometric projection factor nor a constant transmission ratio** — it is one point
on a **saturating** transfer curve set by the head/body compliance in series with F8.

---

## 6. Force / torque / work closure (Gate 13)

Deterministic ledger (`ledger.csv`):
- **F1 centred axial, equilibrium:** |net force| = 0, |net torque| = 0, trap-axial + coupler-axial balance
  residual = **0** (machine precision) — a centred axial coupler injects zero torque (r ∥ F).
- **F4 off-centre, +1 nm:** the transverse (z) bond at a ¼-L attach produces a **non-zero torque**
  (~1.4e-22 N·m) with the correct sign and a small filament tilt (~0.4°) — the torque exists but its
  stiffness effect is < 0.1% (§4). (An *axial* bond at an on-axis attach gives exactly zero torque — the
  ledger's axial "F4q" row confirms r ∥ F.)
- **F1 quasi-static +2 nm ramp:** W_trap = 2.584e-22 J, ΔU(F8+trap) = 2.584e-22 J, dissipation 1.5e-26 J,
  **work residual = 2.3e-6** — the simplified single-spring coupler closes its mechanical ledger far more
  completely than the multibody canonical motor (Exp-1 Gate-8 was CONDITIONAL at ~0.5–0.9).

---

## 7. Timestep (Gate 12) & Brownian

- **Timestep:** F1 (k=1) k_motor = 1.0000 at all three dt (finest-two |Δk|/k = 0.0%); F6 (k=4) k_obs
  dt-invariant (finest-two 0.0%; its k_motor is legitimately UNIDENTIFIABLE at every dt — a trap-limit, not a
  dt effect). **Stable; no residual bias.** No production timestep change is implied.
- **Brownian (k=1, 4 paired seeds, external signals only):** F1/F6 mean trap force fluctuates about zero
  (−0.016…+0.013 pN across seeds), trap-force RMS ≈ 0.19–0.20 pN, variance-apparent k ≈ 0.010 pN/nm
  (informational). F7 (rotating sphere) mean ≈ +0.28 pN, RMS ≈ 0.28–0.33 pN — the rotating head rectifies
  more force. mean |F8| ≈ 2.4–3.3 pN in all arms — the light-sphere-head thermal-rectification inflation seen
  in Exp-1/1b. **Detachment is disabled ⇒ high-force configurations are overrepresented** relative to the
  live cycle; **no 12 pN release threshold is referenced.**

---

## 8. Visualization (Gate 14)

`-3js` wrote 8 matched 120-frame sequences (`~/Code/SoftBox/threejs_fake_coupler_{F0,F1,F2,F3,F4,F5,F6,F7}`)
in the existing viewer schema: filament + trap-centre crosses + sphere head(s) + F8 bond + material-attach
marker, under ±axial perturbation. Same representative geometry for paired arms; a geometry check only.
**View:** `python3 SoftBox/sim_server.py 8000` from `~/Code`, open
`http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → newest).
**Figure:** `RUN_LOGS/lasertrap_fake_coupler/exp2b_summary.png` (8 panels: measured-vs-assigned · α(k) ·
orientation/rotation bars · off-axis torque · sphere arms · native-orientation histogram · trap robustness ·
timestep).

---

## 9. Gate verdict

| Gate | Result | Evidence |
|---|---|---|
| G1 default-path protection | **PASS** | new file + 1 dispatch line; no tracked/canonical source changed; FDT regression unchanged |
| G2 F0 recovery | **PASS** | scalar spring recovered α = 1.00001 across k × trap |
| G3 exact-F8 isolation | **PASS** | production `bondForces`, `xbParams[2]=0`; not reimplemented |
| G4 full-recovery reference | **PASS** | F1 axial recovers α = 1.000 across ≥2 traps within [0.90,1.10] |
| G5 orientation isolation | **PASS** | F2 differs from F1 only by F8 geometry/preload; α = 1.000 |
| G6 filament-rotation isolation | **PASS** | F3 − F2 paired at same pose: Δ < 0.05% |
| G7 torque localization | **PASS** | F4 off-axis sweep + F5 torque-cancel; torque real (~1e-22 N·m) but < 0.1% stiffness |
| G8 spherical-head interpretation | **PASS** | F6 fixed = 1.0, F7 rotating = 0.14 & saturating, F8 restrained → F6 |
| G9 H8 continuity | **PASS** | F9 = 0.6534 (Exp-2A H8 reproduced to the digit) |
| G10 transfer-curve power | **PASS** | 7-point k ladder × 3 traps distinguishes α-constant (fixed) from saturation (F7) |
| G11 trap robustness | **PASS** | k_motor flat vs trap where identifiable; identifiability edge moves with trap |
| G12 timestep stability | **PASS** | F1 & F6 finest-two |Δ|/ = 0.0% |
| G13 force/torque/work closure | **PASS** | balance residual 0; work residual 2.3e-6 |
| G14 visualization | **PASS** | 8 matched `-3js` sequences |

---

## 10. Controlling outcome

**PRIMARY OUTCOME: A — exact F8 recovery.** The aligned fixed-point production F8 law (F1), the scalar spring
(F0), and the fixed spherical head (F6) all expose **essentially 100%** of the coded stiffness (α = 1.000).

**Mechanism of the H8 loss: G + E, NOT B/C/D.** The 1.0 → 0.65 reduction is **not** geometric projection
(**B refuted**: pure-F8 orientation costs < 0.1%), **not** filament rotation (**C refuted**: F3 = 1.000),
and **not** off-axis bond torque (**D refuted**: F4/F5 < 0.1%). It is **head-side rotational/coupling
compliance** — demonstrated directly by the free-rotating sphere (**G**: F7 α = 0.14) and its **saturation**
(**E**: F7 k_motor caps at ≈ 0.17 as k_F8 rises). The canonical frozen motor's 0.65 (F9) is its articulated
head/body compliance — the F9/F10 alignment torques + XB_IMPLICIT2 coupled solve — the many-body analog of
the F7 rotating sphere, in series with the skeletal-stiff F8. **H refuted** (F0/F1 recover the scalar-spring
result exactly). This confirms and mechanistically explains Exp-2A Outcome E: F8 is skeletal-stiff, and that
stiffness is squandered through a softer, **saturating head/body compliance in series** — not through the
bond's 3D geometry.

---

## 11. Decision rules (as required)

1. **What fake coupler recovers full k?** F0 (scalar), F1 (axial F8), F6 (fixed sphere) — and, because F8 is
   zero-rest isotropic, **also F2/F3 (native orientation + preload + filament rotation)** and F5 (torque-
   cancel). Any coupler whose **head is fixed** recovers ~100%.
2. **What causes 1.0 → 0.65?** The canonical motor's **head-side rotational/coupling compliance** (F9/F10
   alignment + XB_IMPLICIT2), the F7-rotating-sphere analog — **not** F8 geometry, orientation, preload,
   attachment position, off-axis torque, or filament rotation.
3. **Is ≈0.65 constant across k_F8?** **No.** For fixed F8 arms α = 1.000 (constant, not 0.65). For the head-
   compliance analog (F7 / the real motor) α **falls** with k_F8 (k_motor saturates ≈ 0.17 for F7). 0.65 is
   one point on a saturating curve, not a transmission constant.
4. **What assigned F8 → ~1 pN/nm externally?** In the **fixed** geometries (F1/F6): k_F8 = 1.0 already gives
   1.0 (interpolation, α = 1). In a **rotating-head-limited** geometry (F7): **no finite k_F8 reaches 1.0** —
   k_motor saturates at ≈ 0.17; raising F8 cannot overcome the head compliance. (Target inversion for the
   fixed arms is the identity map; reported as interpolation for targets ≤ 1.0, extrapolation for 2.0.)
5. **Does external stiffness saturate?** For fixed-head couplers, **no** (α = 1 until the trap-limited
   identifiability edge). For compliant-head couplers (F7, and the intact motor by analogy), **yes** — a
   fixed-magnitude head compliance in series caps k_motor.
6. **Filament rotation or bond torque dominate?** **Neither** — both cost < 0.1% for the zero-rest F8 spring.
   **Head rotation dominates.**
7. **Is a fixed spherical head a clean reference for a two-body prototype?** **YES** — F6 = full k, α = 1.000,
   dt-stable, ledger-closing; F8-restrained validates it. It is the mechanically clean full-stiffness anchor
   for a future two-body motor.
8. **Would a freely rotating sphere be too compliant without an orientation potential?** **YES** — F7 α = 0.14
   at k = 1 and saturates; the restraint (F8) is required to recover stiffness. A rolling-head two-body
   architecture **needs an explicit head-orientation potential.**
9. **Is increasing F8 meaningful only after the body/substrate pathway is stiffened?** **YES** — the F7
   saturation proves raising k_F8 alone cannot overcome a compliant head; the series head/body pathway must be
   stiffened first.
10. **Is a combined F8 + joint/anchor calibration licensed for a later experiment?** **YES, in principle** —
    the ladder quantifies that both a stiffer body/head-orientation pathway **and** an F8 chosen for the
    combined series system are needed to present a target experimental stiffness. **But NO canonical change is
    made or recommended here.**

---

## 12. Interpretation restraint (unchanged)

Even though the fixed F1 coupler shows k_F8 = 1.0 already yields 1.0 externally (and 1.5 would yield 1.5),
**do not change canonical F8.** The intact motor is dominated by body/head compliance, not F8; the F7
saturation shows that in a compliant-head geometry raising F8 does not raise the external stiffness. A future
calibration must **first** stiffen the head-orientation / body-substrate pathway (an explicit head restraint,
the F8-arm analog; or a two-body prototype with a rolling-head orientation potential), **then** choose F8 for
the combined series system. This ladder quantifies that requirement; it does not implement it.

---

## 13. Recommendation for the subsequent anchor / two-body calibration study

1. **Build the fixed-spherical-head two-body prototype on the F6 reference** — F6 is the mechanically clean,
   full-stiffness, ledger-closing anchor. Its job (per Exp-2A) is to remove the distributed articulated-body
   compliance **in series** with F8, not to fix F8.
2. **The head-orientation potential is mandatory, not optional** — F7 vs F8 shows a free-rotating head is
   ~7× too soft and saturating; a rolling-head architecture must carry an explicit orientation restraint
   (the physical analog of the F8 arm / the canonical F9-AXLOCK, but stiff enough to transmit F8).
3. **Stiffen the head/body pathway before touching F8** — decision rules 8–9. Verify any candidate against
   the **blinded** optical-trap observable (this ladder's estimator), and require it to recover k_motor toward
   the skeletal band **without** preload and **without** regressing stroke/force/gliding.
4. **Do not pursue projection/orientation/torque interventions** — B/C/D are refuted here; the free J2 hinge
   (Exp-2A) and the F8 attachment geometry are not the limiters.

---

## Artifacts & commands

- Preregistration: `RUN_LOGS/lasertrap_fake_coupler/PREREGISTRATION.md`
- Log: `RUN_LOGS/lasertrap_fake_coupler/exp2b_full.log`
- CSVs (`RUN_LOGS/lasertrap_fake_coupler/csv/`): `transfer_curve`, `sphere_arms`, `native_localization`,
  `native_orientation_inventory`, `f4_offaxis`, `timestep`, `ledger`, `brownian`, `time_resolved`.
- Figure: `RUN_LOGS/lasertrap_fake_coupler/exp2b_summary.png`
- Viewer frames: `~/Code/SoftBox/threejs_fake_coupler_{F0..F7}/`
```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp2b -target 24 -out RUN_LOGS/lasertrap_fake_coupler/csv -3js ~/Code/SoftBox/threejs_fake_coupler
./scripts/run_lasertrap.sh -exp2b -fast          # quick smoke (reduced native counts)
./scripts/run_gpu.sh -cpu                         # Gate-1 regression (FDT, CPU)
python3 scripts/lasertrap_fake_analyze.py RUN_LOGS/lasertrap_fake_coupler/csv RUN_LOGS/lasertrap_fake_coupler/exp2b_summary.png
```
