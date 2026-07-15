# Experiment 0 — Virtual Optical-Trap FILAMENT Calibration Assay

**Date:** 2026-07-13 · **Branch:** `dt-convergence-study` · **Commit at start:** `2170aff` (working tree: only new untracked files + the pre-existing untracked `docs/CURRENT_STATE.md`) · **Runner:** CPU sequential only. · **Hardware:** aorus (16 threads; single-threaded harness). GPU left untouched (~89% util by the fine-dt gliding sweep). CPU load average at start 2.7, during runs unchanged. Wall-clock: full assay ≈1.2 s.

This is a **setup-validation** experiment. It contains **no motor, no nucleotide chemistry, no binding, no crosslinker, no turnover** — only a single straight rigid actin rod held between two virtual harmonic optical traps, built by composing the existing SoftBox drag / Brownian / rigid-rod-Langevin / derive systems. Its purpose is to prove the trap geometry, force balance, thermal equilibrium, relaxation, timestep behaviour, energy accounting, logging, and visualization are correct **before** a canonical motor is introduced.

---

## 1. Implementation facts

**New files only (canonical path untouched, `git diff` on tracked files empty):**
- `softbox/LaserTrapSystem.java` — the trap force+torque kernel: `F_trap,i = −k_i[(x_i−x0_i)·f̂]f̂` on the derived endpoints (LEFT→end1, RIGHT→end2), accumulated as force into `forceSum` and torque `r×F` (r = endpoint−center, µm→m) into `torqueSum`, exactly the `ContainmentSystem` endpoint convention (r in metres, F in N ⇒ N·m matching the SI rotational drag). A deterministic axial external force (Phase A5) is applied at the COM (no torque). A separately-labeled `applyAxisPrep` orientational-preparation term exists but is **default OFF** and was **not used** (see §6).
- `softbox/LaserTrapHarness.java` — the assay (Phases A–C, energy, dt-invariance, `-3js`, CSV/structured logging).
- `scripts/run_lasertrap.sh` — CPU-only launcher (refuses `-gpu`).
- `scripts/lasertrap_analyze.py` — the summary figure.

**Scene / idealization (stated honestly).** ONE rigid rod (`FilamentStore` n=1, L=0.99900 µm; monomerCount 369), uVec = assay axis f̂ = x̂. A single rigid segment **cannot carry internal axial strain**, so the "common-mode translation must not induce internal strain" concern (A2) is structurally impossible to violate — we still report end-to-end length constancy as the explicit check. Canonical Brownian scales (BTransCoeff=1.0, BRotCoeff=0.5) when thermal.

**Sign convention (documented).** `ext_L = (end1 − x0L)·f̂`; the force `−k·ext` restores the endpoint toward its trap center. LEFT force <0 (along −f̂) and RIGHT force >0 (along +f̂) ⇒ the rod is under **tension** when the traps are pulled apart. Equal-and-opposite: both endpoint forces enter `forceSum`; their torques enter `torqueSum`; a perfectly axial rod has r∥F ⇒ zero trap torque.

**Why the COM axial mode is analytically clean.** The two endpoint x-offsets ±(L/2)u_x cancel in the **sum** of the endpoint forces, so `F_net = −(k_L+k_R)(x_c−x_eq)` **independent of orientation**, and the trap potential separates (no x_c·θ cross term). Therefore the effective stiffness for the two-trap COM mode is **k_eff = k_L+k_R** (NOT one trap stiffness), the effective drag is γ_∥ (rod parallel translational drag), and:

| quantity | analytic prediction |
|---|---|
| equipartition | `⟨δx²⟩ = kT/k_eff` (drag- and orientation-independent) |
| relaxation | `τ = γ_∥ / (10⁶·k_eff)`  (10⁶ = N/µm→N/m) |
| constant force | `x_eq = F/k_eff` |

**Measured geometry (dt=1e-5, k=0.05 pN/nm each ⇒ k_eff=1.0e-10 N/µm):** γ_∥ = 1.3184e-07, γ_⊥ = 2.1641e-07 N·s/m; τ_pred = 1.31844e-03 s; var_pred = 4.1164e-05 µm² (σ_pred = 6.42 nm).

**Trap-stiffness bracket (PROVISIONAL, labelled so):** 0.02 / 0.05 / 0.10 pN/nm ("compliant / intermediate / stiff"). This decade is in the range of single-bead optical-trap stiffnesses used in skeletal-myosin dumbbell assays (~0.02–0.1 pN/nm), but here it is used **only to exercise the harness**, not as a biological calibration. Assign biological meaning only after the intended reference assay's trap stiffness is fixed.

---

## 2. Direct measurements

### Phase A — deterministic mechanics (Brownian OFF)
- **A1 centered:** net |F| = 0, |τ| = 0 N·m, COM drift = 0, angle = 0°, end-to-end = 0.99900 µm. Stable.
- **A2 common-mode (5/10/20 nm):** COM follows the translated equilibrium with follow error ≤ 1.2e-07 µm; residual net force ≤ 1.3e-17 N; **internal strain = 0** (rigid); angle = 0°.
- **A3 differential (2/4/8 nm apart):** tension_meas = k·d **exactly** (1.0e-13 / 2.0e-13 / 4.0e-13 N); LEFT force <0, RIGHT force >0 (tension, correct signs); net force = 0; no COM drift.
- **A4 relaxation (10 nm release):** τ_meas = 1.31344e-03 s vs τ_pred = 1.31844e-03 s (**relErr 0.38%**); single exponential over ~4 decades.
- **A5 constant force (±0.5/1/2 pN):** x_eq = F/k_eff to **<0.001%** each; fitted k_eff = 1.00001e-10 N/µm; **R² = 1.0000000**; linear both signs.
- **Deterministic dt-invariance** (dt = 1e-5 / 5e-6 / 2.5e-6): τ = 1.31344 / 1.31594 / 1.31719 ms (monotone → τ_pred 1.31844 ms), **finest-two |Δτ|/τ = 0.09%**; k_eff invariant to <0.003%.

### Phase B — thermal equilibrium (canonical Brownian ON; 8 paired seeds, dt∈{1e-5,5e-6,2.5e-6}, 0.3 s each arm)
- **Equipartition:** ⟨var/var_pred⟩ = **0.9853 ± 0.0176** (sd 0.0862, n=24). Axial-position histogram matches N(0, kT/k_eff), σ_pred = 6.42 nm (figure b).
- **Timestep (paired, equal duration):** Δvar/var_pred (dt=2.5e-6 vs 5e-6) = **−0.0496 ± 0.0323** (n=8 paired) — within noise of zero.
- **Angular excursion:** orientation RMS **3.4–7.1°**; transverse COM RMS ~0.1 µm. No tumbling, no escape over the run.
- ACF-fitted thermal τ is noisy (≈0.7–2.3 ms, centered near 1.32 ms) with ~70 independent samples/run — a cross-check, not the primary τ (A4 is).

### Phase C — synthetic step recovery (deterministic)
Common-mode trap-center steps of 2/5/8/11 nm × the 3-point stiffness bracket. Raw filament displacement **fully recovers** the step (rigid rod follows the equilibrium: recovered = step to <0.01%). Peak trap-force transient = k_eff·step (e.g. 0.05 pN/nm, 11 nm ⇒ 1.10 pN). Relaxation τ = 3.29 / 1.31 / 0.654 ms for k = 0.02 / 0.05 / 0.10 pN/nm (**τ ∝ 1/k**). The observation operator — moving-average(50 steps) + downsample(20 steps) — is reported **separately** from the raw signal and was **not tuned** to any target; on these steps it reproduces the raw amplitude (window ≪ settle time).

### Gate 7 — energy accounting (deterministic)
- **Free relaxation:** released trap PE ΔU = 5.00e-21 J vs viscous dissipation 5.02e-21 J ⇒ **residual 0.38%**.
- **Differential ramp:** operator work W_center = 4.576e-21 J vs (ΔU + dissipation) = 4.576e-21 J ⇒ **residual 0.0%** (quasi-static ⇒ dissipation ≈ 0, W_center = ΔU).

---

## 3. Comparison with analytical expectations

| observable | measured | predicted | agreement |
|---|---|---|---|
| centered net force / torque | 0 / 0 | 0 / 0 | exact |
| differential tension | k·d | k·d | exact (<1e-3) |
| k_eff (A5 fit) | 1.00001e-10 N/µm | 1.0e-10 (=k_L+k_R) | 0.001% |
| constant-force x_eq | F/k_eff | F/k_eff | <0.001%, R²=1.0 |
| relaxation τ (A4) | 1.31344 ms | 1.31844 ms | 0.38% (explicit-Euler O(α)) |
| τ dt→0 (finest-two) | Δ 0.09% | dt-invariant | ✓ |
| equipartition ⟨δx²⟩ | 0.985·var_pred | kT/k_eff | within 0.85·SEM |
| energy balance | residual ≤0.4% | closed | ✓ |

The 0.38% low bias of τ at dt=1e-5 and the ~+0.4% Euler–Maruyama over-fluctuation are the **known explicit-integration O(α)** effects (α = k_eff·dt·10⁶/γ ≈ 7.6e-3 at dt=1e-5); both vanish monotonically as dt→0 and are far below the thermal sampling scatter.

---

## 4. Visual verification (Gate 6 — separate from quantitative validation)

`-3js` writes 230 frames in the **existing viewer schema** (`frame/t/bounds/segments`, same as `FrameWriter`/the BoA viewer — no viewer edit, no private format) composing the dumbbell from segment primitives: the filament (actin radius), both trap centers (cross markers), both endpoint attachment points, both endpoint→center connectors (force direction), and a 50 nm scale bar (10 primitives/frame). The frames were **numerically verified**: frame 0 filament center = 0.00 nm (centered), frame 30 = +40.00 nm (the deliberate displacement), final frame = 0.00 nm (relaxation); schema keys all present. Output at `~/Code/SoftBox/threejs_lasertrap/`.

**Workflow:** `python3 SoftBox/sim_server.py 8000` from `~/Code`, then open `http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → newest). Live in-browser inspection is recommended but is a setup check only; the mechanics rest on §2–3, not on the render.

**Figure:** `RUN_LOGS/lasertrap/lasertrap_summary.png` (2×2: deterministic force response · equipartition histogram · relaxation decay · timestep behaviour).

---

## 5. Interpretation

The optical-trap–filament machinery is quantitatively correct. Force balance closes to machine precision; the two-trap effective stiffness is exactly k_L+k_R (not one trap stiffness — the geometry was derived, not assumed); the axial COM coordinate is an exact 1-D harmonic oscillator whose equilibrium fluctuation obeys equipartition at temperature T and whose relaxation obeys the drag/stiffness timescale; energy is conserved to <0.4%. The primary trap observables (k_eff, τ, x_eq) are **dt-invariant** to well below any sampling noise — a decisive, noise-free timestep result independent of the thermal-variance estimate. Phase C defines a clean observation operator (filter + cadence, kept separate from raw) ready to apply to a later motor stroke.

---

## 6. Limitations (carry-forward)

1. **Single rigid rod (idealization).** One rigid segment has no internal bending/stretch modes. The dumbbell picture is exact for the trap mechanics but a longer multi-segment filament (chain F3/F4) is a distinct, un-tested regime; A2's "no internal strain" is trivially satisfied here and must be re-checked if a chain is used.
2. **Axial-projected traps do NOT confine transverse or orientation.** Per the assignment's `F_trap = −k[(x−x0)·f̂]f̂`, only the axial mode is trapped; the transverse COM (~0.1 µm RMS) and orientation (3–7° RMS, a soft ~quartic potential) diffuse freely. Over these runs the rod neither tumbled nor escaped, so **no orientational preparation was applied** (the `applyAxisPrep` term stayed OFF). A real 3D bead trap would confine these modes; a **forced-bound motor applies transverse load**, so the motor experiment must decide whether to (a) use full-3D traps, (b) enable the weak axis preparation, or (c) accept and monitor the free transverse/rotational DOF. This is the main item to carry forward.
3. **Provisional stiffness bracket.** 0.02/0.05/0.10 pN/nm validates the harness across a decade of k but is **not** a biological calibration; fix it to the intended reference assay before assigning meaning.
4. **Thermal timestep leg is sampling-limited.** The Phase-B paired Δvar/var_pred = −5%±3% is consistent with zero but noisy (~70 independent samples/arm); the decisive timestep evidence is the deterministic τ dt-invariance (0.09%). Reported directly, not hidden behind overlapping CIs.
5. **Canonical BRotCoeff=0.5** halves rotational Brownian amplitude (a v1 persistence-length knob, sub-FDT for rotation) — irrelevant to the axial equipartition (decoupled) but means the orientation distribution is not a naive kT/k_θ; reported, not corrected.

---

## 7. Gate verdict

| Gate | Result | Evidence |
|---|---|---|
| **G1** default-path protection | **PASS** | empty `git diff` on tracked files; FDT CPU regression unchanged (DiffusionHarness). |
| **G2** deterministic force balance | **PASS** | A1 net/torque = 0; A3 tension = k·d exact; A2 follow <1e-7 µm, strain 0; A5 R²=1.0. |
| **G3** thermal equipartition | **PASS** | var/var_pred = 0.985 ± 0.018 (n=24), within 0.85·SEM of 1. |
| **G4** relaxation dynamics | **PASS** | A4 τ within 0.38% of γ_∥/(10⁶·k_eff), single exponential. |
| **G5** timestep behaviour | **PASS** | deterministic τ finest-two Δ 0.09%; thermal paired Δvar −5%±3% (within noise, reported). |
| **G6** visualization | **PASS** | dumbbell + traps + attachments + connectors + scale in the viewer schema; geometry verified. |
| **G7** energy accounting | **PASS** | free-relax residual 0.38%; ramp residual 0.0%. |

All seven load-bearing gates pass at their preregistered tolerances.

---

## 8. Recommendation & outcome

**Outcome: CONDITIONAL PASS.** The trap–filament assay is validated: every preregistered gate passes, the mechanics match analytics to machine precision, thermal equilibrium and energy balance hold, and the timestep behaviour is dt-invariant in the primary observables. The single **condition to carry into the motor experiment** is Limitation §6.2 — the axial-projected traps do not confine the transverse/orientational DOF, and a forced-bound motor loads those DOF, so Experiment 1 must explicitly choose how to handle them (full-3D traps, the weak axis preparation, or monitored free DOF). The single-rod idealization (§6.1) and the provisional stiffness bracket (§6.3) are the other carry-forward notes.

**Experiment 1 (passive forced-bound motor) is LICENSED**, conditioned on §6.2. Do not fix the transverse/orientation handling by tuning; decide it as an explicit modelling choice at the start of Experiment 1.

---

## Artifacts
- Preregistration: `RUN_LOGS/lasertrap/PREREGISTRATION.md`
- Logs: `RUN_LOGS/lasertrap/{phaseA.log, phaseBCE.log, full_assay_8seed.log}`
- CSVs: `RUN_LOGS/lasertrap/csv/{phaseA_summary, phaseA4_relaxation, phaseA_dtInvariance, phaseB_summary, phaseB_ts_*, phaseC_stepRecovery, energy_ledger}.csv`
- Figure: `RUN_LOGS/lasertrap/lasertrap_summary.png`
- Viewer frames: `~/Code/SoftBox/threejs_lasertrap/`

## Exact commands
```
./scripts/build.sh
./scripts/run_lasertrap.sh -A                                   # Phase A deterministic
./scripts/run_lasertrap.sh -seeds 8 -dur 0.3 -out RUN_LOGS/lasertrap/csv   # full assay (A+B+C+energy)
./scripts/run_lasertrap.sh -3js ~/Code/SoftBox/threejs_lasertrap           # visualization
./scripts/run_gpu.sh -cpu                                       # Gate-1 regression (FDT, CPU runner)
python3 scripts/lasertrap_analyze.py RUN_LOGS/lasertrap/csv RUN_LOGS/lasertrap/lasertrap_summary.png
```
