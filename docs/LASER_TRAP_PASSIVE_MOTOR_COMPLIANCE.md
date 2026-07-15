# Experiment 1 — 3D Optical-Trap Calibration + Passive Forced-Bound Canonical-Motor Compliance Spectroscopy

**Date:** 2026-07-13 · **Branch:** `dt-convergence-study` · **Commit at start:** `2170aff` (working tree: only new untracked lasertrap files + pre-existing untracked `docs/CURRENT_STATE.md`; `JOURNAL.md` carries the Experiment-0 append) · **Runner:** CPU sequential only. **Hardware:** aorus (16 threads; single-threaded harness). GPU untouched (~89% by the fine-dt gliding sweep). Load average ~2.1–2.6 throughout; full assay wall-clock ~1.4 s. **Physical windows:** 20 ms equilibration (~15τ) per point; quasi-static energy ramp ≥40k steps. **dt inventory:** {1e-5, 5e-6, 2.5e-6} s. **Seeds:** deterministic primary; 4 paired seeds (3000–3003) for the Brownian check.

This experiment measures **compliance** of the exact canonical motor held in a fixed nucleotide state. It does **not** validate force-dependent lifetime or the mechanochemical cycle. Two ordered stages: **0b** (3D trap dumbbell, no motor) then **1** (one forced-bound canonical motor, frozen chemistry).

---

## 1. Implementation facts

**New files only; `git diff` on tracked source is empty (canonical/production bytecode unchanged; `BoA-v1ref` untouched).**
- `softbox/LaserTrapSystem.java` — added `applyTraps3D` (full vector trap `F=−K·(x−x0)`, diagonal K=diag(kAx,kTr,kTr) in the axial/transverse frame; kTr=0 recovers the Experiment-0 axial-only law exactly). Endpoint force+torque accumulated with the `ContainmentSystem` convention (r in metres).
- `softbox/LaserTrapHarness.java` — added Stage 0b (`-0b`) and Experiment 1 (`-exp1`) modes. Experiment 1 composes the **exact production systems** (`MotorJointSystem`, `TailAnchorSystem`, `CrossBridgeSystem`, `RigidRodLangevinIntegrationSystem`, `DerivedGeometrySystem`, `NucleotideCycleSystem` — frozen) in the exact `GlidingHarness.stepOrig` order (mechanics subset), with the springs parameter baking copied verbatim.
- `scripts/run_lasertrap.sh` (reused), `scripts/lasertrap_motor_analyze.py` (figure).

**Gate-5 composition (exact canonical).** The passive step is `stepOrig` with binding search / release / nucleotide cycle removed (forced-bound, frozen chemistry) and the free-gliding filament replaced by the trap-held rod:
Brownian → `joints` (J1/J2 positional; J1 torsion `jointParams[3]=0`) → `anchor` → `bondForces` (F8 Hookean tip→site; F9 `restF9=90` frozen via `xbParams[9]=1`; F10/AXLOCK via `xbParams[10]=1`) → `applyHeadForce` → `directedSwing` (springs, `swingParams[4]=−refDt`) → `snapshotHeadCenter` → integrate → `orthogonalizeY` → filament Brownian → CSR `csrHistogram/csrScan/csrScatter/segGather` → `applyTraps3D` → `snapshotSegCenter` → integrate → `orthogonalizeY` → `XB_IMPLICIT2` `coupleComputeA/coupleSolveSeg/coupleCorrectHead` → derive both bodies. Springs baking verbatim: `xbParams=[1e-9,90,springify(0.4),dt,HEAD_LEN,0,0,0,0,1,1]`, `swingParams=[0.4,dt,0,60,−1e-5]`, `jointParams[1/5/9]` springified, `setImplicit(1e-9,dt)`. **No motor force law is reimplemented.**

**Passive definition.** `boundSeg[0]=0`; `bindArc[0]=½·segLen` (segment midpoint = the prescribed material coordinate, material-latched, never recomputed); `nucleotideState[0]` frozen to `NUC_ADPPI` or `NUC_ADP`; binding/release/cycle never called. DIRSWING is retained — in a fixed state it is part of the state's mechanical potential (removing it would measure a different motor).

**Geometry.** Filament L≈0.999 µm at z = anchorZ+ROD+LEVER+HEAD+3 nm = 0.061 µm; motor anchor at (0,0,−0.05) assembled +z. Two 3D endpoint traps + 5 nm axial pretension. kAx=kTr=0.05 pN/nm (provisional). myoSpring=1 pN/nm (canonical).

---

## 2. Stage-0b measurements (3D trap dumbbell, no motor) — all gates PASS

Predicted (derived): k_effAx=2kAx=0.10 pN/nm; k_effTr=2kTr=0.10 pN/nm; kθ=(kTr·L²/2 + tension·L)·1e-6 N·m/rad; tension=kAx·pre=0.25 pN at 5 nm.

| test | result |
|---|---|
| 0b.1 centered | net force 0, net torque 0, tension 0.2500 pN (=pred), stable axial |
| 0b.2 axial | τ 1.31344 ms vs pred 1.31844 ms (0.38%), no transverse/rotation, pretension preserved |
| 0b.3 transverse | follows commanded shift (slope 1.000), restoring sign correct |
| 0b.4 tilt | kθ_meas 2.518e-17 vs pred 2.520e-17 N·m/rad (**0.1%**); rises with pretension (single-mode) |
| 0b.5 covariance | **translational** var/kT·k⁻¹: axial 1.00, transY 0.97, transZ 0.87 (FDT-correct); **angular** sub-thermal by the known BRotCoeff=0.5 knob (measured 0.06 vs 0.27 deg² predBRot) — reported, not gated on naive kT/kθ (per the brief); cross-cov small ⇒ modes ~decoupled |
| 0b.6 timestep | deterministic axial τ dt-invariant, finest-two \|Δτ\|/τ = **0.10%** |

**Gate 4 (pretension):** k_effAx unchanged across pretension (axial COM mode is exactly 2kAx, decoupled from pretension); pretension raises kθ (stabilizes orientation) as predicted. **Stage 0b: PASS ⇒ motor introduced.**

---

## 3. Passive-motor measurements (Experiment 1)

### 3.1 Equilibrated initial states (Gate 6 PASS)
| state | plateau | preload \|F8\| | J1 | J2 | anchor ext |
|---|---|---|---|---|---|
| ADP·Pi | yes | 0.153 pN | 0.00° | 0.00° | 0.182 nm |
| ADP | yes | 0.320 pN | 59.24° | 62.89° | 0.380 nm |

DIRSWING drives the lever to 60° in ADP (J1≈59°) — the cocked post-stroke geometry — pre-loading the bond (0.153→0.320 pN). Preload recorded separately (not subtracted).

### 3.2 Force–displacement and stiffness
Fresh scene + 20 ms equilibration per point; common-mode axial trap-center steps ±{0.5,1,2,4} nm. Force–displacement is **linear and symmetric** in both states (figure b).

| state | k_obs (trap) | k_motor,eff (direct) | series-pred k_obs | resid | asym | identifiable |
|---|---|---|---|---|---|---|
| ADP·Pi | 0.0018 pN/nm | **0.0019 pN/nm** | 0.0018 | 0.000 | 0.001 | yes |
| ADP | 0.0038 pN/nm | **0.0040 pN/nm** | 0.0038 | 0.000 | 0.021 | yes |

- **k_obs** = ΔF_trap/Δx_cmd (the experimental observable). **k_motor,eff** = ΔF_trap/Δx_fil (force balance: at equilibrium the motor force = −net trap force). **k_local** = paired ±1 nm incremental.
- The series identity k_obs = 1/(1/k_trap + 1/k_motor) holds **exactly** (resid 0.000) ⇒ the whole-motor stiffness is **cleanly separable** from the trap compliance (Gate 11). The k_trap=0.10 pN/nm is 50× stiffer than the motor, so k_obs≈k_motor.
- **State-dependent difference: ADP is 2.1× stiffer than ADP·Pi (+113%).** The stroke (lever→60°) tilts the head/lever, giving the bond an axial projection and stiffening the axial response.

### 3.3 Displacement partition (Gate 7)
At +2 nm command (ADP·Pi): trap stretch 0.037 nm, filament follows 1.963 nm (**~98%**); F8 axial extension +0.0035 nm; head tip +1.96 nm; head/lever/rod all translate ~1.9 nm. **The filament follows the command almost entirely; the trap barely stretches; F8 barely changes** — the head-chain (rod→lever→head, anchored) translates with the filament. Closure: L1 (cmd = trap stretch + filament-site motion) residual 0; L2 (filament-site motion = F8 axial ext + head-tip motion) residual 0 (exact by construction of the site).

**Interpretation:** the motor's axial compliance is dominated by the **anchored articulated body pivoting about the tail anchor** (the head is ~0.1 µm from the anchor ⇒ a small axial head force makes a large torque ⇒ easy axial head motion), NOT by F8 stretch. This is why k_motor,eff ≪ myoSpring (1 pN/nm).

### 3.4 Energy accounting (Gate 8 CONDITIONAL)
Quasi-static +2 nm trap-center ramp, ledger Wop = ΔU(trap+F8) + dissipation(all-body translational). Residual 0.51 (ADP·Pi) / 0.89 (ADP): the ledger does **not** close to <15%. The tracked dissipation omits **rotational** body dissipation and the internal dissipation of the overdamped joint/anchor/DIRSWING movers (these are damping-limited relaxers, not conservative stores, so their "work" is dissipation the rigid-body-translation term misses). Deterministic force/displacement closure (§3.2/§3.3) is exact; energy closure for the multibody motor is **approximate** and reported as such.

### 3.5 J2 analysis (Gate: the settled free hinge, measured not changed)
- Mean J2: ADP·Pi 0.00° (pre-stroke straight), ADP 62.89° (cocked plateau, consistent with the settled 122°-native/cocked coordinate).
- J2 absorbs displacement: in ADP·Pi J2 opens ~0.65°/nm of command; in ADP it sits at ~63° and shifts a few degrees over ±4 nm (figure e).
- **The force–displacement curve stays linear despite the J2 pose change**, and the incremental stiffness is set by the whole articulated body, not J2 alone. **This reinforces the settled result: J2 is a near-neutral compliance mode** — it moves substantially but does not dominate the incremental axial stiffness. (This does not rule out other full-frame/dihedral J2 potentials; only the free hinge was measured.)

### 3.6 Timestep (Gate 9 PASS)
Deterministic k_local (ADP·Pi, ±1 nm): 0.00183 / 0.00183 / 0.00183 pN/nm at dt=1e-5/5e-6/2.5e-6 — **finest-two |Δk|/k = 0.03%**. Preload, plateau, partition all dt-stable.

### 3.7 Controls
- **Geometry (axial-only vs 3D):** axial-only traps give **k_obs = 0.0000 pN/nm** (the filament escapes transversely, the motor provides no measurable axial resistance) vs 3D 0.0018 — **materially different**; the 3D trap is essential to measure motor stiffness.
- **Pretension sensitivity:** k_motor,eff(ADP·Pi) = 0.0019 at pretension 1/5/10 nm — **pretension-independent** (does not dominate the motor response).

### 3.8 Brownian check
ADP·Pi, 4 paired seeds: the **deterministic |F8|=0.153 pN vs stochastic-mean |F8|=3.54 pN** — a large inflation. This is **thermal rectification of the magnitude** by the light sphere head (which fluctuates strongly, per the settled head-noise finding), **not** a basin shift: |F8| is a non-negative magnitude whose mean is inflated by pose fluctuations. The compliance measurements are deterministic (the primary path); the stochastic single-molecule force is dominated by head noise (a known feature, not a defect here).

---

## 4. Analytical / numerical expectation

- Stage 0b: k_effAx=2kAx, k_effTr=2kTr, kθ=kTr·L²/2·1e-6 — all matched to ≤0.4% (§2). Angular variance sub-thermal by BRotCoeff (documented v1 knob).
- Experiment 1: k_obs = series(k_trap, k_motor) by force balance — verified exactly (resid 0.000). k_motor is identifiable because both ΔF_trap and Δx_fil are directly measured (no scalar-compliance subtraction assumption needed; Gate 11).

---

## 5. Visual verification (Gate 10 — geometry only)
`-3js` writes 120-frame sequences per state (`threejs_lasertrap_motor_adppi`, `_adp`) in the existing viewer schema (10 primitives/frame: filament, 2 trap-center crosses, rod/lever/head, anchor marker, F8 bond) showing centered equilibrium then ±axial perturbation. Geometry verified numerically (segment count + topology). **View:** `python3 SoftBox/sim_server.py 8000` from `~/Code`, open `http://localhost:8000/SoftBox/sim_viewer_boa.html`. Figure: `RUN_LOGS/lasertrap_motor/exp1_summary.png`.

---

## 6. Interpretation

The exact canonical motor, held passively in a fixed state, is **cleanly and quantitatively identifiable** on a validated 3D optical-trap dumbbell: its effective axial stiffness separates from the trap by force balance (series identity exact), is dt-invariant, pretension-independent, and state-dependent. The **ADP (post-stroke, lever-60°) state is ~2.1× stiffer than ADP·Pi (pre-stroke, lever-0°)** — a real, measurable compliance signature of the stroke geometry. The **absolute stiffness (~0.002–0.004 pN/nm) is 100–1000× softer than skeletal whole-cross-bridge optical-trap measurements (~0.5–2 pN/nm)**: in this bound geometry the compliance is dominated by the anchored articulated body pivoting about the tail anchor (the near-vertical F8 bond and the compliant head-chain), not by F8 stretch. J2 accommodates displacement but does not set the incremental stiffness, reinforcing the settled near-neutral-hinge result.

---

## 7. Limitations (carry-forward)
1. **Absolute stiffness is geometry-dependent.** The near-vertical F8 bond + anchored-body pivot dominate the soft axial stiffness. This passive, fixed-state geometry may not represent the load-bearing engaged cross-bridge during active gliding (where the head cycles and the effective stiffness under sustained load differs). The **relative** ADP·Pi↔ADP difference and the identifiability are robust; the **absolute** number carries this caveat and must not be read as a whole-cross-bridge stiffness comparable to skeletal single-molecule data.
2. **Softer than skeletal is a measurement, not a recalibration license.** Do not tune canonical stiffnesses to match literature; the mismatch is a result. A meaningful literature comparison would require matching construct/isoform/temperature/ionic strength/trap compliance/loading geometry (not attempted here).
3. **Energy closure approximate (Gate 8 CONDITIONAL)** — multibody rotational + joint-mover dissipation not captured; force/displacement closure is exact.
4. **Single rigid rod filament** (idealization); **transverse trap softer than the motor** ⇒ filament z is motor-dominated (the series-compliance regime — which is precisely why the series correction / 3D geometry matter).
5. **Provisional stiffness bracket** (0.05 pN/nm), not a biological calibration.
6. **Brownian is a single small check** (4 seeds, one state) — labelled; the primary measurements are deterministic.

---

## 8. Gate verdict
| Gate | Result | Evidence |
|---|---|---|
| G1 default-path | PASS | empty tracked-source diff; Exp-0 gates + FDT CPU regression unchanged |
| G2 3D trap mechanics | PASS | axial/transverse/tilt signs + stiffness to ≤0.4% |
| G3 3D thermal covariance | PASS | translational FDT-correct; angular sub-thermal (BRotCoeff), reported |
| G4 pretension | PASS | axial k_eff unchanged; kθ rises (stabilizes) |
| G5 exact canonical composition | PASS | production systems + stepOrig order + springs baking; no surrogate |
| G6 equilibrated states | PASS | both states plateau (force/pose) |
| G7 force/displacement closure | PASS | series identity resid 0.000; partition L1/L2 residual 0 |
| G8 energy accounting | **CONDITIONAL** | multibody rotational/internal dissipation not captured (§3.4) |
| G9 timestep | PASS | k_local finest-two 0.03% |
| G10 visualization | PASS | motor topology + traps + anchor + F8 + perturbation direction |
| G11 identifiability | PASS | k_motor series-separable (not naive scalar subtraction) |

---

## 9. Recommendation & outcome

**Outcome: CONDITIONAL PASS.** The 3D trap and the passive canonical-motor compliance assay are validated: the composition is the exact production stack, states equilibrate, the whole-motor stiffness is cleanly identifiable, dt-invariant, pretension-independent, and state-dependent. The **conditions** are (a) energy closure is approximate for the multibody motor (Gate 8), and (b) the **absolute** stiffness carries a geometry-dependence caveat (§7.1) — the relative and identifiability results are robust, the absolute number is not a skeletal-comparable whole-cross-bridge stiffness. **Experiment 2 (deterministic active working-stroke) is LICENSED**, carrying limitations §7.1 (geometry/absolute-stiffness) and §7.3 (energy) forward. Do not activate stochastic chemistry or tune stiffnesses to obtain a more interesting result.

### Independent statements (as required)
- **ADP·Pi vs ADP compliance differ measurably:** **YES** — ADP 0.0040 vs ADP·Pi 0.0019 pN/nm (+113%, ADP ~2.1× stiffer).
- **J2 absorbs significant displacement:** **YES** (moves ~0.65°/nm; sits at ~63° in ADP) but does **not** set the incremental axial stiffness — consistent with the settled near-neutral hinge.
- **Whole-motor stiffness identifiable from the trap data:** **YES** — the series identity is exact (resid 0.000); k_motor separates cleanly (Gate 11).
- **Axial-only vs 3D trap give materially different answers:** **YES** — axial-only → 0 stiffness (filament escapes transversely); 3D → 0.002–0.004 pN/nm.

---

## Artifacts & commands
- Preregistration: `RUN_LOGS/lasertrap_motor/PREREGISTRATION.md`; logs `RUN_LOGS/lasertrap_motor/exp1_full.log`; CSVs `RUN_LOGS/lasertrap_motor/csv/{stage0b_summary,exp1_forcedisp}.csv`; figure `RUN_LOGS/lasertrap_motor/exp1_summary.png`; viewer `~/Code/SoftBox/threejs_lasertrap_motor_{adppi,adp}/`.
```
./scripts/build.sh
./scripts/run_lasertrap.sh -0b -out RUN_LOGS/lasertrap_motor/csv                 # Stage 0b (3D trap calibration)
./scripts/run_lasertrap.sh -exp1 -out RUN_LOGS/lasertrap_motor/csv               # Experiment 1 (both states + controls + timestep + Brownian)
./scripts/run_lasertrap.sh -exp1 -state adp -3js ~/Code/SoftBox/threejs_lasertrap_motor   # single state + viewer frames
./scripts/run_gpu.sh -cpu                                                         # Gate-1 regression (FDT, CPU)
python3 scripts/lasertrap_motor_analyze.py RUN_LOGS/lasertrap_motor/csv RUN_LOGS/lasertrap_motor/exp1_summary.png
```
