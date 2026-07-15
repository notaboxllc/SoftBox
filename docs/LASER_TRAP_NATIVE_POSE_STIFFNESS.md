# Experiment 1b — Native-Pose, Blinded Optical-Trap Stiffness Spectroscopy

**Date:** 2026-07-13 · **Branch:** `dt-convergence-study` · **Commit at start:** `2170aff` (working tree: only new untracked lasertrap files + pre-existing untracked `docs/CURRENT_STATE.md`; `JOURNAL.md` carries the Exp-0/1 appends) · **Runner:** CPU sequential only, single-threaded harness. **Hardware:** aorus (16 threads). GPU untouched (~89% by the fine-dt gliding sweep). Load average ~2.1–2.7 throughout. **Wall-clock:** generation ~2 s; full assay (generation + ~1000 replays + gates + Brownian + viz) ~25 s. **dt:** {1e-5,5e-6,2.5e-6}. **Seeds:** generation 0–3; Brownian 4000–4003.

This is a **measurement and identifiability** experiment. It determines what stiffness an experimentalist would infer when the trap assay begins from **naturally generated** canonical attachment poses, rather than the synthetic vertical forced-bound pose of Experiment 1. **No canonical stiffness, constraint, anchor, J1/J2, F8, F9, AXLOCK, DIRSWING, chemistry, or binding rule was changed; no spring was added.**

---

## 1. Implementation facts (Gate 1: canonical source untouched — empty tracked-source diff)

**New behaviour only in the two untracked lasertrap files:**
- `softbox/LaserTrapHarness.java` — added `-nativegen` (probe), `-exp1b`. Native generation composes the **exact production** systems in the `GlidingHarness.stepOrig` order **with binding/cycle/release ON** (`publishHeadFromBody` → `bruteReachable` → `bindNearest` → `cycleLymnTaylor` → joints/anchor/bondForces/applyHeadForce/directedSwing/integrate/XB_IMPLICIT2), the filament **re-clamped to v=0** each step (kinematic clamp). `kinParams[20]=1` (ADP·Pi-only binding, canonical). Springs baking verbatim. Replay reuses the validated Experiment-1 passive mechanics (`passiveStep`) + `LaserTrapSystem.applyTraps3D`.
- `softbox/LaserTrapSystem.java` (from Exp-0b) — reused unchanged.
- `scripts/lasertrap_native_analyze.py` (figure).

**Scene.** Generation: single actin rod (n=1, L≈1 µm) at z=0 (canonical `FIL_Z`), **velocity-clamped v=0**; a **dilute 64-motor bed** (anchors at z=−0.05, spread under the filament). The clamped filament decouples the motors ⇒ each is an independent single-molecule episode (the episode-kernel trick). Replay: each captured motor is reconstructed alone with 3D traps at the native filament endpoints (2 nm pretension, f̂ = filament axis), nucleotide frozen, binding/release/cycle/Brownian off.

---

## 2. Stage 1–2: native pose generation (Gate 2, 6) + coordinate reconciliation (Gate 4)

**Authentic canonical binding.** The unbound articulated motor is a **free-jointed chain** (J1/J2 angular springs off) whose head floods a ~0.1 µm sphere — single-motor binding is diffusion-limited; the 64-motor bed makes it efficient (393 binds/60 k steps in the probe). Captured **60 snapshots/stage** across 4 seeds (Gate 6 PASS, ≥50/stage), 0 attrition at capture.

**Coordinate reconciliation (Gate 4).** The snapshot logger, the native J2 audit, and Experiment 1 all use the **same** shortest-unsigned coordinate θ_J2 = atan2(|rod.u × lever.u|, rod.u·lever.u). Native means: **A/ADP·Pi 104.2°, E/ADP-plateau 142.0°** — matching the settled native audit (ADP·Pi ~103°, ADP-plateau ~122–124°; the E band runs a little higher here). Experiment 1's synthetic values were **0.0° (ADP·Pi) / 62.9° (ADP)** — a **collinear vertical assembly** (rod ∥ lever), far from any native pose. **Same formula, different poses:** Exp-1's 0°/62.9° do NOT correspond to the ~103°/122° native values because the synthetic construction is non-native (collinear), whereas real binding produces a strongly bent motor. This is the central reason Experiment 1 needed this follow-up.

**Restart fidelity (Gate 3 PASS).** A restored snapshot reproduces its captured native angles (J1/J2 within 0.5°) and its short deterministic continuation is bit-reproducible across independent restores (max deviation <1e-9 µm over 20 steps).

---

## 3. Stage 3–4: blinded stiffness distributions (Gate 5)

Blinded = compliance-corrected k_motor computed from **trap force + filament displacement only** (force balance: at equilibrium the motor force = −net trap force; k_motor = ΔF_trap/Δx_fil). Telemetry (F8/J1/J2/anchor) stored separately for §5. Relaxed static replay (20 ms settle), perturbations ±{0.5,1,2} nm.

| stage | n | median | mean | sd | p5 | p95 | fracNeg | J2nat | drift | unstable | poorfit |
|---|---|---|---|---|---|---|---|---|---|---|---|
| A bind ADP·Pi | 60 | **0.0154** | 0.0200 | 0.0148 | 0.0015 | 0.0481 | 3.3% | 104° | 2.0 nm | 0/60 | 0 |
| B eq-ADP·Pi | 60 | 0.0148 | 0.0204 | 0.0158 | 0.0015 | 0.0480 | 5.0% | 123° | 1.4 nm | 0/60 | 1 |
| C early-ADP | 60 | 0.0222 | 0.0251 | 0.0176 | −0.0015 | 0.0554 | 6.7% | 129° | 2.3 nm | 0/60 | 1 |
| D post-stroke | 60 | 0.0244 | 0.0269 | 0.0178 | −0.0004 | 0.0561 | 6.7% | 143° | 2.3 nm | 0/60 | 1 |
| E plateau | 60 | **0.0310** | 0.0296 | 0.0192 | 0.0023 | 0.0561 | 1.7% | 142° | 2.3 nm | 0/60 | 2 |

**Blinded findings (measurement):**
- **Native poses are ~8× stiffer than the synthetic pose** (native A 0.0154 vs synthetic ADP·Pi 0.0019; native E 0.0310 vs synthetic ADP 0.0040) — the synthetic collinear pose was anomalously soft.
- **Still 16–65× below skeletal** whole-cross-bridge optical-trap values (~0.5–2 pN/nm). No pose reaches skeletal: even p95 (~0.056 pN/nm) is ~10× below.
- **Attachment-age dependence:** stiffens ~2× from bind (A 0.015) to plateau (E 0.031), tracking J2 104°→142° — a modest post-stroke stiffening.
- **Broad distribution:** sd ≈ mean, p5–p95 spans ~30×; a small tail of near-zero/negative fitted stiffness (fracNeg 2–7%, kept + reported, not censored). Linear fit good (r²>0.9; 0–2 poor fits/stage kept).

---

## 4. Identifiability, timestep, native-vs-relaxed (Gates 7, 8, 9, 10)

- **Gate 7 (trap-stiffness invariance) — FAIL ⇒ Outcome D.** The compliance-corrected k_motor **depends on the trap stiffness**: 0.0172 / 0.0246 / 0.0293 pN/nm at trap 0.02 / 0.05 / 0.10 pN/nm (stage E subset) — a **49% spread**. Because the motor (~0.02–0.03 pN/nm) is **comparable-to-softer than the trap**, the series-compliance correction is ill-conditioned and the inferred "motor stiffness" tracks the instrument. A biologically interpretable scalar motor stiffness is **not uniquely identifiable** from these observables.
- **Gate 8 (timestep) — PASS.** k_motor = 0.0132 pN/nm at all three dt (finest-two |Δk|/k = 0.3%); dt-invariant.
- **Gate 9 (linearity/identifiability).** Force–displacement is linear over ±2 nm (r²>0.9 for ~97% of events); the estimator is identifiable for the large majority, with a reported soft/near-zero tail. Two estimators (LS slope and paired ±1 nm local) agree.
- **Gate 10 (native-instantaneous vs frozen-relaxed).** Reported separately. **Native-instantaneous is ill-posed** (median −0.115 pN/nm): with a minimal restart settle, the ongoing native→trap-rig relaxation transient corrupts the perturbation slope. The **relaxed** value (0.0246) is the well-defined observable. The initial settle drift is only ~2 nm ⇒ **the native pose persists** (it does not collapse onto a common compliant equilibrium during replay); native and relaxed sample the same shallow basin, and the identifiable stiffness is the equilibrated one (which is trap-dependent, Gate 7).

---

## 5. Secondary internal-mechanism analysis (unblinded AFTER the primary was frozen — explains, does not redefine)

Within stage E (fixed nucleotide state, so correlations are not driven by state):
- **r(k, anchor extension) = +0.37** (figure f: stiffer events have larger tail-anchor extension, with a two-branch structure);
- **r(k, |F8|) = +0.37** (larger cross-bridge load ⇒ stiffer);
- **r(k, J2nat) = −0.18…−0.28** (more-bent J2 ⇒ slightly softer).

The tail-anchor extension and the F8 load are the leading internal predictors; the free J2/J1 pivot is weakly (negatively) associated. Mechanistically the softness is the **anchored articulated body pivoting about the tail anchor** (consistent with Experiment 1 and the episode-kernel finding that the compliant body absorbs ~85% of the stroke). This is correlational within one stage; causality is not claimed.

**Brownian diagnostic (detachment disabled).** Stage-E replay with Brownian on: |F8| median 3.44 pN, p95 6.33, p99 7.46, tail ~8.9 pN; trap-force RMS 0.56 pN. The mean |F8| is inflated by light-sphere-head thermal rectification (as in Exp-1). **Detachment is disabled here ⇒ high-|F8| configurations are overrepresented relative to the live canonical cycle.** The default canonical path has **no** 12 pN cap; `-forcecapdetach` is a separate default-off diagnostic and is not invoked.

---

## 6. Visual verification (Gate 11)
`-3js` writes 7 sequences (`threejs_native_{A_bind,B_eqADPPi,C_earlyADP,D_postStroke,E_plateau,soft,stiff}`, 93 frames each) — each a native snapshot replayed in the trap rig (filament + trap crosses + connectors + motor rod/lever/head + anchor + F8 bond) under ±axial perturbation, showing the bent native geometry (J2 112–157°). Representative soft/stiff events chosen **after** the primary distribution was frozen. **View:** `python3 SoftBox/sim_server.py 8000` from `~/Code`, open `http://localhost:8000/SoftBox/sim_viewer_boa.html`. Figure: `RUN_LOGS/lasertrap_native/exp1b_summary.png`. Geometry check only.

---

## 7. Gate verdict
| Gate | Result | Evidence |
|---|---|---|
| G1 default-path | PASS | empty tracked-source diff; canonical untouched |
| G2 authentic native poses | PASS | canonical Lymn-Taylor binding; J2 matches the native audit |
| G3 restart fidelity | PASS | restored pose == snapshot; deterministic continuation reproducible |
| G4 coordinate reconciliation | PASS | same atan2 formula; synthetic 0/62.9° collinear ≠ native 104/142° bent |
| G5 blinded integrity | PASS | k from trap force + filament motion only; telemetry stored separately |
| G6 pose-distribution power | PASS | 60/stage × 4 seeds |
| G7 trap-stiffness robustness | **FAIL** | 49% trap-bracket spread ⇒ Outcome D |
| G8 timestep | PASS | dt-invariant (0.3%) |
| G9 linearity/identifiability | PASS (with tail) | r²>0.9 for ~97%; soft/near-zero tail reported |
| G10 native-vs-relaxed | reported | instantaneous ill-posed; relaxed well-defined; pose persists (drift ~2 nm) |
| G11 visualization | PASS | native geometry + trap replay rendered |

---

## 8. Explicit outcome

**PRIMARY OUTCOME: D — stiffness is NOT uniquely identifiable** (the compliance-corrected value depends materially on trap stiffness), **underlain by B — a broadly-present canonical soft mode** (native poses remain 16–65× too soft), with a **C flavour** (pose/age-dependent spread, A→E ~2×, p5–p95 ~30×). It is **NOT a pure synthetic-pose artifact (Outcome A)**: native poses are ~8× stiffer than the synthetic collinear pose, so the Experiment-1 value was an underestimate, but native poses are still far below skeletal.

### Separated statements (as required)
- **Blinded apparent stiffness distribution:** median 0.015→0.031 pN/nm (A→E), broad (p5–p95 ~0.002–0.056).
- **Compliance-corrected distribution:** same order; median 0.0154 (A) / 0.0310 (E); **trap-dependent** (0.017–0.029 across the bracket).
- **ADP·Pi vs ADP:** native ADP-plateau ~2× stiffer than native ADP·Pi (0.031 vs 0.015).
- **Attachment-age dependence:** monotone A→E stiffening (~2×), tracking J2.
- **Synthetic vs native:** native ~8× stiffer than synthetic (both states).
- **Native vs frozen-relaxed:** native pose persists (drift ~2 nm); instantaneous stiffness ill-posed; relaxed is the identifiable observable.
- **Trap-stiffness dependence:** strong (49% spread) ⇒ the decisive Outcome-D signal.
- **Timestep dependence:** none (0.3%).
- **Fraction comparable with skeletal (0.5–2 pN/nm):** **~0%** (even p95 is ~10× below).

---

## 9. Limitations
1. **Absolute stiffness is not identifiable** (Gate 7) — report the trap-observed and compliance-corrected values as trap-dependent, not a unique scalar.
2. Single rigid-rod filament; v=0-clamp generation (the primary distribution is one velocity — a small velocity control is deferred).
3. Replay geometry inherits Exp-1's near-vertical F8 bond and the soft transverse trap; the native pose was generated against a rigid clamp and relaxes ~2 nm into the soft-trap rig (reported).
4. Provisional stiffness bracket. Detachment-disabled Brownian overrepresents high-force configs (§5).
5. Secondary correlations are within-stage and correlational; causality not claimed.

---

## 10. Intervention license (no model change made)
Because the motor is genuinely too soft (Outcome B/D), a mechanical-stiffness intervention **is scientifically motivated** — but **none is implemented here**, per the restraint. Ranked candidate compliance sources from §5, for a **later** one-factor, default-off study that must first show it improves the blinded optical-trap observable **without** damaging stroke, force, or gliding:
1. **Tail-anchor compliance** (leading predictor, r=+0.37; the anchored body pivots).
2. **J1/J2 articulated pivoting** (the free-jointed arm — but J2-angle correlation is weak/negative; the settled J2-null result cautions against a naive J2 spring).
3. **F8 projection / attachment orientation** (r(|F8|)=+0.37; near-vertical bond geometry).
4. **Head-orientation freedom (F9/AXLOCK) / rod-lever geometry.**

A licensed intervention should target the tail-anchor / articulated-pivot compliance first, one element at a time, default-off, with the blinded trap observable as the gate — and must not regress the validated stroke/force/gliding behaviour.

---

## Artifacts & commands
- Preregistration `RUN_LOGS/lasertrap_native/PREREGISTRATION.md`; log `RUN_LOGS/lasertrap_native/exp1b_full.log`; per-event blinded table `RUN_LOGS/lasertrap_native/csv/exp1b_blinded.csv`; figure `RUN_LOGS/lasertrap_native/exp1b_summary.png`; viewer `~/Code/SoftBox/threejs_native_*/`.
```
./scripts/build.sh
./scripts/run_lasertrap.sh -nativegen                                          # native-binding probe (Gate 2 check)
./scripts/run_lasertrap.sh -exp1b -out RUN_LOGS/lasertrap_native/csv           # full assay (all gates + distributions)
./scripts/run_lasertrap.sh -exp1b -3js ~/Code/SoftBox/threejs_native           # + viewer frames
./scripts/run_gpu.sh -cpu                                                       # Gate-1 regression (FDT, CPU)
python3 scripts/lasertrap_native_analyze.py RUN_LOGS/lasertrap_native/csv RUN_LOGS/lasertrap_native/exp1b_summary.png
```
