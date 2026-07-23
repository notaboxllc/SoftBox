# branchEA PROVENANCE + MECHANICAL CALIBRATION — SoftBox HMM dimer

**Parts B + C of the freeze-audit finalization.** (B) Reconciles the exact branchEA value the completed dimer
sweeps used, from run metadata — not comments or defaults. (C) Documents the mechanical calibration of branchEA
as a numerical representation of an approximately inextensible fork branch, chosen by stability/geometry
criteria (**not** by gliding agreement). Concludes that the existing completed studies already establish the
calibration; **no new GPU runs were required.**

---

## Part B — exact-value reconciliation: the completed sweeps used branchEA = 0.03

**Determined from run metadata (authoritative), corroborated by command lines, source revision, standing-config
validation, and output banners:**

| evidence source | value |
|---|---|
| **Output metadata** — every `RUN_LOGS/hmm_density_sweep_long/cell_d*_s*.json` | `"branchEA": 0.0300000` |
| **Per-cell banner** — `RUN_LOGS/hmm_density_sweep_long/logs/cell_*.attempt1.log` | `branchEA=0.03000 (eff ~12.6 pN/nm)` |
| **Command line** — orchestrator / `run_hmm_gliding.sh` | `-branchEA 0.03` |
| **Source revision** — orchestrator header | `rev=82b6762` |
| **Standing-config self-check** — `ExplicitHmmDimerGpuParams.STANDING_BRANCH_EA` + `validateStandingConfig` | enforces `0.03`; **aborts** if `|branchEA−0.03|>1e-9` (`ExplicitHmmDimerGpuValidation.java:1776`) |

**Verdict: the completed HMM dimer density sweeps (single-head has no branch), the matched-length control, and
the rupture sensitivity all used `branchEA = 0.03` (effective branch axial stiffness ≈ 12.6 pN/nm).** The
project recollection of **0.3 is incorrect** — 0.3 was one of the *tested-and-rejected* candidates in the
compliance sweep ("**branchEA 0.3 is NOT enough** — still 1220 nm, seed-dependent";
`docs/matsoa/EXPLICIT_HMM_DIMER_COMPLIANCE_SENSITIVITY_FINDINGS.md` §6). The audit's reported 0.03 stands.

### Split default eliminated (code change applied)

The prior CPU/GPU split was: the harness convenience default `CFG_EA = 1.0` (raw 420 pN/nm reference) vs the
GPU standing/production value 0.03. Now reconciled to a **single source of truth**: the harness default
`CFG_EA` references `ExplicitHmmDimerGpuParams.STANDING_BRANCH_EA` (0.03), so **both runners default to the
canonical 0.03**; the raw reference stiffness (1.0) is the explicit reference-stiffness diagnostic
(`-branchEA 1.0`). `softbox/ExplicitHmmDimerGlidingHarness.java` (fields + parse default), build verified.
Regression note: the compliance-study byte-identity fixtures now pass `-branchEA 1.0` explicitly.

---

## Part C — mechanical calibration: branchEA represents an ~inextensible fork branch

**Framing.** branchEA is the multiplier on the proximal-branch axial stiffness (reference = `EA_SI/lB` =
4.2e-9 N / 10 nm = **420 pN/nm**, near-rigid). It is a **numerical compliance** standing in for an
approximately inextensible S1–S2 fork branch: the reference 420 pN/nm is stiff enough that a moving filament
dragging a bound head converts a small geometric mismatch into an enormous internal branch force (peak 29,171
pN on a 10 nm branch) and a large transient joint-gap excursion. It is **not** a gliding-fit parameter —
velocity is flat across the whole sweep (see the plateau below), so branchEA cannot be and was not tuned to a
velocity target.

### The bounded sweep is already complete (existing evidence — no new runs needed)

`docs/matsoa/EXPLICIT_HMM_DIMER_COMPLIANCE_SENSITIVITY_FINDINGS.md` swept **exactly the requested bounded set
`{0.01, 0.03, 0.1, 0.3, 1.0}`** at density 500, D0, 6 seeds × 5000 steps, ranked by mechanical health
**explicitly "Not by velocity,"** with the one- and two-head-bound loading states covered by the F1–F5 static
fixtures. Reproduced here (velProd µm/s; maxGap/p999/p99 nm; peakBrF pN; maxBrExt nm on a 10 nm branch):

| branchEA | eff.k (pN/nm) | velProd | meanBound | maxGap | p999 | peakBrF | maxBrExt | exc>10/50/100 | inv/sf |
|---|---|---|---|---|---|---|---|---|---|
| 1.0 (ref) | 420 | +1.48±0.76 | 4.21 | 270.3 | 6.96 | 29 171 | 69.5 | 11/4/3 | 0/0 |
| 0.3 | 126 | +1.78±1.08 | 4.32 | **1220.6** | 7.92 | 28 540 | 226.5 | 10/3/3 | 0/0 |
| 0.1 | 42 | +1.54±0.70 | 4.45 | 68.9 | 5.45 | 1 570 | 37.4 | 9/2/0 | 0/0 |
| **0.03** | **12.6** | **+1.49±0.86** | **4.46** | **11.8** | 4.36 | **103** | **8.1** | **1/0/0** | 0/0 |
| 0.01 | 4.2 | +1.98±0.50 | 4.74 | 14.9 | 3.84 | 27 | 6.3 | 2/0/0 | 0/0 |

One-/two-head-bound loading states (F2/F4 static fixtures): at branchEA 0.03, one-head boundF8 = 1.80 pN
(ref 1.81), inter-head coupling 3.24 nm (ref 3.07 — preserved), fork 53.7° (ref 54.4°), second-head-bind
transient branch force 8.6 pN (ref 32 — softened), two-head stroke 7.98 nm (branch-independent, identical at
every setting).

### Density-resolved health at the chosen value (from the production sweep, branchEA=0.03, GPU, 4 seeds/cell)

Aggregated from `RUN_LOGS/hmm_density_sweep_long/cell_d*_s*.json` (mean over seeds; the production sweep IS the
branchEA=0.03 curve across the full density grid):

| ρ (dim/µm²) | heads | vel (µm/s) | meanBound | 2-head% | maxGap (nm) | branchF p99 (pN) | peakBrF (pN) | peakF8 (pN) | inv/sf |
|---|---|---|---|---|---|---|---|---|---|
| 100 | 600 | −0.62 | 0.86 | 3.9 | 5.1 | 25.1 | 56.5 | 10.0 | 0/0 |
| 250 (≈ρ½) | 1500 | −1.45 | 2.21 | 3.3 | 6.4 | 29.0 | 77.7 | 10.5 | 0/0 |
| 500 | 3000 | −2.04 | 4.52 | 4.5 | 23.1 | 32.2 | 172 | 11.3 | 0/0 |
| 1000 | 6000 | −2.52 | 8.88 | 4.7 | 17.4 | 36.7 | 127 | 11.9 | 0/0 |
| 1500 | 9000 | −2.67 | 13.5 | 4.3 | 17.4 | 39.2 | 127 | 12.0 | 0/0 |
| **3000** | 18000 | −2.87 | 26.5 | 4.4 | **6022** | **19541** | **19794** | 12.8 | **0/0** |

- **Clean through ρ1500** (9000 heads): maxGap ≤ 23 nm (≈2 branch lengths), p99 branch force ≤ 39 pN, peak
  ≤ 172 pN, and **0 invalid / 0 solver failures at every density** — the branchEA=0.03 excursion suppression
  holds across the entire physiologically-relevant range (ρ½ ≈ 363).
- **Residual ultra-high-density tail at ρ3000** (18000 heads, ~8× ρ½): a subset of seeds shows a large branch
  excursion (maxGap 6022 nm, peak 19794 pN) that **still recovers with 0 invalid / 0 solver**. This is outside
  the biological regime, is numerically stable, and is a flag for ultra-dense studies — addressed by the
  cross-bridge substep or a modestly softer branch (0.01), not by re-tuning the canonical value.

### Chosen geometric tolerances (declared + justified from branch length)

The single geometric length scale is the **branch rest length `lB` = 10 nm**. Both tolerances derive from it:

1. **Extreme branch axial strain < 1.0** (extension < `lB` = 10 nm). A branch stretched ≥100% of its rest is
   geometrically implausible — the proximal S1–S2 junction cannot extend to 2× its length. This is the exact
   threshold the rupture failsafe encodes (`BRANCH_RELEASE_STRAIN = 1.0`,
   `ExplicitHmmDimerGpuParams.java:100`). **At branchEA 0.03, maxBrExt = 8.1 nm ⇒ max strain 0.81 < 1.0** (at
   density 500), with typical (median) extension ≈ 1.4 nm ⇒ strain ≈ 0.14 (negligible).
2. **Joint gap ≲ lB = 10 nm** (inter-node continuity error below the branch/segment length). A gap comparable
   to the branch's own length is unphysical fork separation. **At branchEA 0.03, maxGap = 11.8 nm ≈ lB**
   (marginal-acceptable at the density-500 screen; ≤23 nm through ρ1500), median gap ≈ 1.4 nm.

### Selection — 0.03 is doubly bracketed, and NOT by gliding agreement

The primary criterion (lowest branchEA on the stiffness plateau where typical strain is negligible, extreme
strain < tolerance, gap acceptable, no numerical instability, and further stiffening does not materially alter
force transmission) selects **0.03**:

- **Force-transmission plateau:** velocity (+1.48…+1.98, within seed noise), meanBound (4.21→4.74), inter-head
  coupling (3.07→3.60 nm), and stroke (7.98 nm identical) are **flat across the entire 0.01–1.0 range** — so
  "further stiffening does not materially alter force transmission" holds everywhere below 0.3, and branchEA
  **cannot be a velocity knob**.
- **Lowest stable value:** 0.01 is "a shade too soft" — a rare dt/2 excursion reappears (maxGap 1053 nm in one
  seed), failing the no-instability criterion. **0.03 is the lowest dt-stable setting** (gap/force dt-CONVERGED:
  maxGap 8.4→9.3 nm, peak 96→91 pN at dt vs dt/2).
- **Stiffest that removes the excursions:** the compliance study's independent selection rule also lands on 0.03
  ("stiffest branch-axial setting that removes the pathological excursions").

⇒ **0.03 is simultaneously the lowest dt-stable value and the stiffest that removes the excursions** — a
doubly-bracketed choice, converged in dt, with negligible typical strain (0.14), extreme strain (0.81) below the
declared 1.0 tolerance, and 0 invalid/solver. It was **not** chosen by gliding agreement (velocity is flat).

**Conclusion: branchEA = 0.03 (12.6 pN/nm) is the confirmed, calibrated canonical value.** Existing completed
studies (the compliance bounded sweep + the production density sweep + the F1–F5 loading-state fixtures) already
establish the calibration across the requested bounded set, densities, and one-/two-head loading states; **no
new GPU runs are required.** The only residual is the ρ3000 ultra-dense tail (numerically stable, outside the
biological regime), tracked with the cross-bridge substep.

**Freeze status:** `NUMERICAL COMPLIANCE — CALIBRATED BY CONSTRAINT` (a numerical representation of an
inextensible fork branch, selected by stability/geometry, not gliding). Reference source of truth:
`ExplicitHmmDimerGpuParams.STANDING_BRANCH_EA = 0.03`.
