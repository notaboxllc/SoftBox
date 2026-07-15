# Experiment 2A — Native-Pose Dynamic-Compliance Localization by Time-Resolved Optical-Trap Spectroscopy and One-DOF Diagnostic Cuts

**Date:** 2026-07-13 · **Branch:** `dt-convergence-study` · **Commit at start:** `2170aff937130c232ca999a6b5f87233f58b5776`
· **Runner:** CPU sequential only, single-threaded harness (GPU reserved for the fine-dt gliding sweep; `-gpu` refused). **Hardware:**
aorus, 16 threads. **CPU load** at start `3.00 2.46 2.38`, unchanged during the run (single-threaded; the concurrent GPU sweep dominates
the machine, this harness adds one core). **Wall-clock:** full assay (Stage 1 + 40 snapshots/stage generation + Stage 2–4 + timestep +
Brownian + viz + 6 `-3js` sequences) ≈ 30 s. **dt:** {1e-5, 5e-6, 2.5e-6}. **Seeds:** generation 0–3; Brownian 4000+.

This is the first step of the mechanical-stiffness intervention study. It is a **measurement + localization** experiment: it determines
which mechanical freedom(s) cause the experimentally visible softness of the canonical motor **before** any spring/restraint/replacement is
added. **No canonical model change was made.** The only non-canonical additions are default-OFF **diagnostic coordinate holds** (H0–H8),
used to localize compliance; they are labelled `[NON-CANONICAL DIAGNOSTIC HOLD]` and are **not candidate biological models**.

---

## 0. Repository state, protection, and default-path (Gate 1)

- **New behaviour only in the untracked `softbox/LaserTrapHarness.java`** (added `-exp2a`, the H0–H8 kinematic holds, the time-resolved
  estimator, Stage-1 validators, viz) and the untracked figure script `scripts/lasertrap_localization_analyze.py`. `softbox/LaserTrapSystem.java`
  reused unchanged. **No tracked/production/canonical source file was modified** (`git status` shows the `softbox/LaserTrap*.java` as untracked;
  the only tracked `M` entries — `JOURNAL.md`, `FINE_DT_FREE_GLIDE_RESULTS.md`, `FINE_DT_FREE_GLIDE_figure.png` — are the pre-existing prior
  work and the **concurrent GPU sweep's own outputs**, NOT touched by this task). `BoA-v1ref` untouched.
- **FDT CPU regression unchanged** (`run_gpu.sh -cpu`): D_par 1.09958e-1 (−2.52%), D_perp_y −1.15%, D_rot −1.80% — the canonical baseline, bit-for-bit
  as before. **Gate 1 PASS.**
- All new behaviour is default-off (only `-exp2a` triggers it).

---

## 1. Framing (what is protected, what was settled, what is asked)

- **Protected canonical motor** — `SPHEREHEAD + AXLOCK + DIRSWING + XB_IMPLICIT2 + LYMN_TAYLOR`: three overdamped rigid bodies (rod/tail,
  lever/neck, head); J1 lever–head positional joint (native angular spring OFF; DIRSWING supplies the 0°→60° stroke potential at ADP·Pi→ADP);
  J2 rod–lever positional joint (native angular spring zero — the settled free hinge); tail anchor tethers rod to substrate; F8 material-latched
  compliant head–actin cross-bridge (`myoSpring` = 1 pN/nm); F9 frozen-90° perpendicularity constraint (nucleotide-independent, not a stroke);
  AXLOCK maintains assay-plane geometry. **Untouched.**
- **Latest settled native-pose trap result (Exp 1b):** native median apparent stiffness ~0.015 (ADP·Pi bind) → ~0.031 pN/nm (ADP plateau), broad
  (p95 ~0.056), **no events in the skeletal 0.5–2 pN/nm band**, strongly trap-dependent (**Outcome D**: not uniquely identifiable, underlain by
  **B**: a broadly-present soft mode); dt-stable; secondary telemetry: tail-anchor extension + F8 load lead (r≈+0.37), free J1/J2 pivot weak/negative.
- **Question addressed here:** *which mechanical freedom is causally responsible*, established by paired one-DOF holds + time-resolved (bandwidth)
  spectroscopy, and whether a single interpretable intervention target exists or the three-body architecture itself must be simplified.

---

## 2. Blinded dynamic estimator (frozen definition)

At time `t` after a paired common-mode axial trap-center step `±Δx_command`, using **external observables only** (trap-center displacement,
left/right trap force, filament displacement, known trap stiffness, elapsed time):

- **Trap-observed** `k_obs(t) = ΔF_trap(t)/Δx_command`, paired-averaged over ±Δ (cancels the native pose's directional preload).
- **Compliance-corrected** `k_motor(t) = [paired ΔF_trap slope] / [paired filament-follow slope Δx_fil/Δx_command]`. When the filament follows
  <5% of the command (motor ≳20× the trap), the series correction is ill-conditioned ⇒ reported **UNIDENTIFIABLE (NaN)**, never clipped.
- **Relaxation fraction** `ΔF_trap(t)/ΔF_trap(plateau)`.

Internal channels (F8, body pose, anchor, internal forces) are **excluded** from the primary calculation and opened only in Stage 4, after the
blinded result is frozen. Times reported only where resolvable at the given dt (no sub-dt interpolation): first step, 10, 20, 50 µs, 0.1, 0.2, 0.5,
1, 2, 5, 20 ms.

---

## 3. Stage 1 — dynamic-estimator validation (Gate 2)

| check | result |
|---|---|
| **1.1 motor-free 3D dumbbell** | transient peak `k_obs` = **0.1000 pN/nm = k_eff (2·kAx)** exactly; plateau `k_obs`→0 (rigid rod fully follows the command); τ_pred 1.318 ms. **PASS.** |
| **1.2 known scalar-spring control** | across **3 springs × 3 traps** the compliance-corrected `k_motor` recovers the known spring to **100.0%** (0.0200/0.0200/0.0200 for 0.02; 0.1000×3 for 0.10; 1.0000×3 for 1.0). **The recovered `k_motor` is flat vs trap even though `k_obs` is trap-dependent** (e.g. for k=0.02: `k_obs` = 0.0133/0.0167/0.0182 at trap 0.02/0.05/0.10). **Gate 2 PASS.** |
| **1.3 synthetic Exp-1 poses** | `k_motor` plateau ADP·Pi **0.00186**, ADP **0.00401** — reproduces the Exp-1 static values (0.0019 / 0.0040). Continuity confirmed. |

**Decisive method result (answers the Exp-1b Gate-7 concern directly):** for a *clean scalar spring* the compliance-corrected estimator is
**trap-invariant and exact**. Therefore the trap-dependence seen in the native motor (Exp-1b Outcome D) is **genuine multibody ill-conditioning**
(the motor is comparable-to-softer than the trap), **not estimator failure**. The estimator is sound; the localization below is trustworthy.

---

## 4. Stage 2 — baseline native dynamic stiffness (H0), by state, bandwidth, trap (n=20/stage)

Blinded `k_obs(t)` (pN/nm), trap 0.05, primary step ±0.5 nm:

| stage | k@10µs | k@0.1ms | k@1ms | k@plateau |
|---|---:|---:|---:|---:|
| A bind ADP·Pi | 0.0993 | 0.0934 | 0.0558 | 0.0121 |
| B eq-ADP·Pi | 0.0993 | 0.0934 | 0.0557 | 0.0118 |
| C early-ADP | 0.0993 | 0.0934 | 0.0573 | 0.0166 |
| D post-stroke | 0.0993 | 0.0934 | 0.0573 | 0.0163 |
| E plateau | 0.0993 | 0.0934 | 0.0575 | **0.0196** |

- **The stiff early-time response is the INSTRUMENT, not the motor.** `k_obs@10µs` ≈ 0.099 ≈ `k_eff` = 2·kAx = 0.10 at *every* trap
  (0.040 / 0.099 / 0.197 for trap 0.02 / 0.05 / 0.10) — this is the trap+filament responding before the filament relaxes, tracking the trap
  stiffness exactly. The compliance-corrected motor is soft at all resolvable bandwidths. **→ Outcome A (bandwidth reconciliation) is REFUTED:
  there is no genuine stiff-early *motor* mode; the `k_obs(t)` decay is the trap/filament relaxation (τ≈1.3 ms) converging to the soft motor plateau.**
- Plateau stiffens **~1.6× A→E** (0.012→0.020), tracking the Exp-1b age/J2 progression.
- **Trap-dependence persists in the plateau** (stage E: 0.0119 / 0.0196 / 0.0255 at trap 0.02 / 0.05 / 0.10) — the Exp-1b Outcome-D instrument
  sensitivity, now shown to be genuine (Stage 1.2), not estimator conditioning.
- **Frozen-relaxed 20 ms endpoint** (stage E): plateau `k_obs` 0.0197 ≈ minimal-settle 0.0196 ⇒ the native pose **persists** (no collapse to a
  common compliant basin), reproducing Exp-1b Gate 10.
- **Linearity:** plateau `k_obs` = 0.0196 pN/nm at ±0.25/0.5/1.0/2.0 nm — flat ⇒ linear over the whole range (holds may substantially stiffen,
  so ±0.5 nm is retained as primary).

---

## 5. Stage 3 — diagnostic coordinate holds (one DOF at a time; n=20/stage; trap 0.05)

Paired within-snapshot vs H0. `k_obs` and compliance-corrected `k_motor` at plateau (pN/nm); `Δplat` = paired Δ(`k_obs`) vs H0; reaction ledger.

**Stage E (ADP plateau):**

| hold | k_obs@10µs | k_obs@plat | **k_motor@plat** | Δplat vs H0 | reactWork (J) | Fmax (pN) | preload (J) |
|---|---:|---:|---:|---:|---:|---:|---:|
| H0 intact | 0.0993 | 0.0196 | **0.0246** | 0 | 0 | 0 | 0 |
| H1 anchor-translation | 0.0993 | 0.0203 | 0.0256 | +0.0006 | 1.8e-19 | 0.53 | 1.8e-19 |
| **H2 rod-rotation (anchor pivot)** | 0.0993 | **0.0377** | **0.0605** | **+0.0181** | 5.0e-20 | 0.00 | 5.0e-20 |
| H3 J1 (lever–head) | 0.0993 | 0.0205 | 0.0260 | +0.0011 | 7.3e-19 | 0.00 | 7.2e-19 |
| H4 J2 (rod–lever) | 0.0993 | 0.0206 | 0.0260 | +0.0003 | 4.5e-20 | 0.00 | 4.5e-20 |
| H5 J1+J2 | 0.0993 | 0.0243 | 0.0323 | +0.0029 | 5.4e-19 | 0.00 | 5.4e-19 |
| H6 head-orientation | 0.0993 | 0.0198 | 0.0248 | +0.0007 | 1.7e-18 | 0.00 | 1.7e-18 |
| H7 rigid-chain | 0.0993 | 0.0243 | 0.0323 | +0.0029 | 5.4e-19 | 0.00 | 5.4e-19 |
| **H8 frozen-motor / F8-only** | 0.0993 | **0.0867** | **0.6534** | **+0.0671** | 2.5e-18 | 0.90 | 2.5e-18 |

Stage B (eq-ADP·Pi) is qualitatively identical (H2 leads, +0.020; H8 +0.075; J-holds small). **Mean(B,E) compliance-corrected `k_motor`:**
H0 = 0.019 · H2 = **0.054** · H7 = 0.027 · **H8 = 0.653**.

**The controlling numbers:**
1. **The F8 cross-bridge is NOT the soft element — Outcome F is REFUTED.** Freezing the entire motor pose (H8) leaves only F8 + filament + traps,
   and the compliance-corrected F8-only stiffness is **k_motor ≈ 0.65 pN/nm — inside the skeletal band (0.5–2)**. The near-vertical F8 attachment
   geometry *can* transmit near-skeletal stiffness. (Its `k_obs` still reads 0.087 because the soft trap is in series — the Outcome-D identifiability
   limit, not an F8 limit.)
2. **The softness is the ARTICULATED BODY in series with F8** — H8/H0 ≈ **34×**: the three-body chain is ~34× softer than the bond it carries.
3. **No single freedom restores stiffness.** The largest single-DOF hold, **H2 (rod rotation about the tail anchor)**, raises `k_motor` only ~3×
   (0.019→0.054) — recovering just **~5%** of the H0→H8 gap. Rod pivot about the anchor is the *leading* single contributor (the "anchored body
   pivoting about the tail anchor" of Exp-1/1b), but far from sufficient.
4. **Internal articulation is nearly neutral.** J1 (H3), J2 (H4), head orientation (H6) each move `k_motor` negligibly, and even locking **all**
   internal relative orientations (**H7 rigid-chain**, rod still free to translate/rotate at the anchor) recovers only ~1% of the gap
   (0.019→0.027). This reaffirms the settled J2-null and shows the compliance is **not** in the joint angles.
5. **The gap H7→H8** (0.027 → 0.653) is the rod's rigid-body motion at the anchor **plus** the bodies' translational freedom (which lets the head
   translate). Freeing *any* of those pathways softens the head's position; the compliance is **distributed** across them.

**Hold neutrality + work accounting (Gates 5, 10).** Every hold's reaction work is **~1e-18–1e-20 J** (negligible) and its zero-perturbation
preload ≈ its perturbed reaction work ⇒ the holds are **quasi-neutral and do not inject the stiffness**. Rotational holds apply zero translational
reaction; H1/H8 apply ≤0.9 pN. Crucially, **H2 raises `k_motor` 3× while injecting ~5e-20 J and zero reaction force** — a genuine, non-immobilizing
mechanical-transmission change (a *valid* lead by the intervention-relevance criteria), not an artifact of immobilizing the filament.

---

## 6. Gate 8 — leading-hold trap robustness

H2 (rod-rotation) improves the blinded `k_obs` plateau across the **full** trap bracket (stage E): trap 0.02 → 0.012→0.024; 0.05 → 0.020→0.038;
0.10 → 0.026→0.046. **Gate 8 PASS** (the lead is robust, not a single-trap artifact). H7 gives a smaller but consistent lift; H8 (F8-only) rises
steeply with trap (0.038→0.153) because its underlying `k_motor` (0.65) is stiffer than the trap ⇒ trap-limited `k_obs`.

---

## 7. Stage 4 — secondary internal telemetry (unblinded after the blinded result was frozen)

Displacement partition at +2 nm command (stage E, H0, mean): trap-stretch 1.21 nm, filament 0.79 nm; **F8 distance −0.81 nm, anchor extension
−0.81 nm**, ΔJ1 −1.0°, ΔJ2 +1.9°. The imposed motion is taken up by **anchor extension and F8 length change**, with the joint angles moving little
— consistent with the paired-hold localization (the compliance is in the anchor/rod-body + F8 pathway, not the articulation). This is correlational
telemetry; the **paired holds (§5) are the causal evidence** and they agree with it.

---

## 8. Timestep + Brownian arms

- **Timestep (Gate 9):** baseline `k_obs` plateau (stage E subset) 0.0143/0.0143/0.0143 and early 10µs 0.0993×3 across dt {1e-5, 5e-6, 2.5e-6}
  — **dt-stable, PASS.** H2 plateau 0.0375→0.0372→0.0372 (stable); H8 plateau 0.0867→0.0888→0.0898 (mild +3.5% drift, reported).
- **Brownian arm** (external trap signals only; detachment disabled ⇒ high-force configs overrepresented — stated; **no 12 pN cap referenced**,
  the hard-cap detach is a separate default-off diagnostic): H0 0.014, H2 0.037, H8 0.087 pN/nm — matching the deterministic localization. The
  ranking is unchanged by thermal forcing.

---

## 9. Validation-gate verdict

| Gate | Result | Evidence |
|---|---|---|
| G1 default-path | **PASS** | no tracked/canonical source modified; FDT CPU regression unchanged |
| G2 known-spring recovery | **PASS** | 100.0% across 3 springs × 3 traps; flat vs trap |
| G3 native snapshot fidelity | **PASS** | Exp-1b captured states; restart-reproducible |
| G4 time resolution | **PASS** | no time < dt reported |
| G5 hold neutrality | **PASS** | reaction work ~1e-18–1e-20 J; preload ≈ reaction; no hidden preload |
| G6 one-factor isolation | **PASS** | telemetry confirms each hold pins only its named DOF (J-holds move `k_motor` ~0; H2 removes only rod rotation) |
| G7 paired power | **PASS** | 40/stage generated, 20/stage used |
| G8 trap robustness | **PASS** | H2 improves across the full trap bracket |
| G9 timestep | **PASS** | early + plateau stable over finest two dt (H8 +3.5% reported) |
| G10 work accounting | **PASS** | hold reaction work measured, negligible; stiffness change not injected by the hold |
| G11 visualization | **PASS** | 6 `-3js` sequences (intact ADP·Pi/ADP, H2, H4, H7, H8) |

---

## 10. Explicit outcome

**CONTROLLING OUTCOME: E — distributed architectural compliance**, with **B flavour** (the leading single contributor is rod rotation about the
tail anchor). No single-DOF hold restores stiffness — the largest, H2 (anchor pivot), recovers only ~5% of the intact→F8-only gap; only rigidifying
the entire body (H8) reaches skeletal.

Separated statements:
- **Outcome A (bandwidth) — REFUTED.** The stiff short-time `k_obs` (~0.10) is the trap (instrument), not the motor. The compliance-corrected motor
  is soft at all resolvable bandwidths; there is no genuine stiff-early motor mode.
- **Outcome F (F8-geometry ceiling) — REFUTED.** The F8-only upper bound (H8) has `k_motor` ≈ **0.65 pN/nm — inside the skeletal band**. The
  cross-bridge bond and its attachment geometry *can* transmit near-skeletal stiffness; they are not the limiter.
- **Outcome C (articulation) — REJECTED as the mechanism.** J1/J2/head-orientation holds are near-neutral (reaffirming the settled J2-null); even
  the full internal rigid-chain (H7) recovers ~1% of the gap.
- **Outcome D (identifiability) — confirmed as the standing caveat.** Intact `k_motor` (~0.02) ≪ trap ⇒ the scalar value is trap-dependent — but
  the *localization* is robust (Stage 1.2 proves the estimator itself is sound and trap-invariant on a clean scalar).
- **Outcome B (anchor/rod-pivot) — the leading single contributor**, but insufficient alone.

In one sentence: **the canonical F8 cross-bridge is skeletal-stiff, but that stiffness is squandered through a ~34×-softer distributed compliance
of the anchored three-body chain in series with it — dominated by the rod's rigid-body freedom (rotation about the tail anchor) plus the
translational freedom that lets the head move — and no single freedom, nor even locking all internal articulation, recovers it.**

---

## 11. Decision — ranked next-study recommendation (no intervention implemented here)

1. **Physically-motivated rod-orientation restraint about the tail anchor** *(leading, cheapest, most interpretable).* H2 is the single largest
   valid, neutral, trap-robust, both-state lever. Physical analogue: an angular stiffness of the tail against the substrate (the anchored rod's
   pivot). It is only a *partial* fix (~3×, not skeletal) and must be shown, when built, to improve the blinded observable without preload and
   without regressing the stroke/force/gliding behaviour.
2. **Tail-anchor translational + rotational stiffening together** — the anchor region is where the rod-body compliance lives; anchor translation
   alone (H1) is neutral, but the rod pivots about it, so the anchored-rod coupling (translation+rotation) is the physical target that collapses
   the head's positional freedom.
3. **Simplified stiffer two-body (tail + spherical-head) prototype — SCIENTIFICALLY LICENSED.** Because no single internal hold reaches skeletal
   and only whole-body rigidification (H8) does, collapsing the three-body articulation into a stiffer two-body motor is the principled route to
   skeletal stiffness. **Nuance (important):** the F8 bond is *already* skeletal-stiff (H8 `k_motor` 0.65), so the two-body prototype's job is to
   remove the distributed articulated-body/anchor compliance *in series* with F8, **not** to fix F8 or the attachment geometry.
4. **Full-frame J1/J2 transmission — NOT recommended.** H3/H4/H5/H7 are negligible; reaffirms the settled J2-null.
5. **Revised attachment geometry — NOT needed for stiffness.** The F8-only control already reaches skeletal; the geometry is not the limiter.

**Is a two-body prototype scientifically licensed?** **YES** — the decision rule is met (no single hold succeeds; only rigidification approaches
experimental stiffness, and the rigid *internal* chain H7 does not — only freezing the anchor/rod rigid-body motion does). Recommended order:
try the cheap, interpretable anchor/rod-orientation restraint (1–2) first; escalate to the two-body prototype (3) if the partial restraint cannot
close enough of the ~34× gap without preload or gliding regressions.

---

## 12. Limitations

1. **Absolute scalar stiffness remains trap-dependent** for the intact motor (Outcome D) — report `k_obs` and `k_motor` as trap-dependent; the
   *localization ranking* is the robust deliverable, not a unique scalar.
2. Single rigid-rod filament; v=0-clamp native generation (one velocity — a velocity control is deferred, as in Exp-1b).
3. Diagnostic holds are exact kinematic projections; their reaction ledger uses the overdamped `F=γ·Δ/dt` estimate (negligible here, ~1e-18 J).
4. Brownian arm is a small check with detachment disabled ⇒ high-force configs overrepresented (labelled).
5. The holds are localization cuts, **not** biological models — H7/H8 are non-physical upper bounds by construction.

---

## Artifacts & commands
- Preregistration: `RUN_LOGS/lasertrap_compliance_localization/PREREGISTRATION.md`
- Log: `RUN_LOGS/lasertrap_compliance_localization/exp2a_full.log`
- CSVs: `RUN_LOGS/lasertrap_compliance_localization/csv/{exp2a_stage1, exp2a_baseline_dynamic, exp2a_holds}.csv`
- Figure: `RUN_LOGS/lasertrap_compliance_localization/exp2a_summary.png`
- Viewer frames: `~/Code/SoftBox/threejs_exp2a_{intact_ADPPi,intact_ADPplateau,lead_H2-rodRot,joint_H4,rigid_H7,F8only_H8}/`
```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp2a -target 40 -out RUN_LOGS/lasertrap_compliance_localization/csv -3js ~/Code/SoftBox/threejs_exp2a
./scripts/run_lasertrap.sh -exp2a -fast                      # quick smoke (reduced counts)
./scripts/run_gpu.sh -cpu                                    # Gate-1 regression (FDT, CPU)
python3 scripts/lasertrap_localization_analyze.py RUN_LOGS/lasertrap_compliance_localization/csv RUN_LOGS/lasertrap_compliance_localization/exp2a_summary.png
```
**View:** `python3 SoftBox/sim_server.py 8000` from `~/Code`, open `http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → newest).
