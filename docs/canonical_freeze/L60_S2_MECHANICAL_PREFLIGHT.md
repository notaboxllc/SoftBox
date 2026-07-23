# L60 S2 MECHANICAL PREFLIGHT — Part C

**Date 2026-07-23 · rev at run time.** Proves the L60 configuration is internally valid BEFORE the ensemble
density sweep, and gives the direct L40-vs-L60 mechanical comparison. Sources: the Experiment-4G explicit-beam
characterization (`./scripts/run_lasertrap.sh -exp4g`, CPU) re-run at this revision, plus the device-resident
L60 gliding cell health (single-head GPU). **All checks PASS ⇒ the sweep is authorized.**

---

## 1. Explicit-beam mechanics — L40 vs L60 (fresh 4G run, this revision)

Same MD material moduli at every L (EA = 4.20e-9 N, EI = 7.20e-28 N·m²; per-seg ks = 420 pN/nm, kb = 7.2e-20
N·m; l0 = 10 nm). Only L (and M = L/10) change.

| L (nm) | M | REST contour drift (nm) | kTrans / bending (pN/nm) | k_ax tension (pN/nm) | kComp (pN/nm) | Euler buckle (pN) | stroke (nm) | search↔load |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
| **40** (canonical) | 4 | **+0.01** | 0.167 | **105** | 105 (straight) | **4.4** | **7.27** (105%) | DECOUPLED |
| **60** (sensitivity) | 6 | **+0.01** | 0.052 | **70** | **0.684** (buckles) | **2.0** | **7.26** (105%) | DECOUPLED |

Direct L40→L60 mechanical comparison (Part C required):
- **Axial reaction:** 105 → **70 pN/nm** (exact 1/L: EA/L). Matches the expected ~70 pN/nm.
- **Transverse reaction:** 0.167 → 0.052 pN/nm (≈1/L³ bending; softer at L60).
- **Compression response:** 105 (stiff, straight branch) → **0.684 pN/nm** (compliant, buckles) — a ~100×
  tension/compression asymmetry emerges at L60.
- **Taut/buckled state:** L40 sits on the compressed-straight branch (crit 4.4 pN); L60 **buckles cleanly under
  compression** (crit ≈ **2.0 pN**, kComp ≪ kTens).
- **Stroke transmission:** 7.27 → **7.26 nm** — L-robust (the working stroke is the head converter, not the beam).

## 2. Required preflight checks (Part C)

| check | result |
|---|---|
| contour conservation | **PASS** — drift +0.01 nm at L60 (Gate 3); end-to-end ≠ contour via bending (Gate 4) |
| beam solver convergence | **PASS** — coupled full-tangent solve stable; dt-stable (stroke 7.27 nm invariant across dt {5e-6,2.5e-6,1.25e-6}, Gate 11) |
| zero invalid states | **PASS** — L60 single-head gliding cell (ρ300, ρ500) reports invalid=0 |
| zero solver failures | **PASS** — solveFail=0 on the device gliding cells |
| no rate-cap warnings | **PASS** — 0 at production/gliding loads |
| axial stiffness ≈ 70 pN/nm | **PASS** — kTens = 70.00 pN/nm at L60 |
| Euler buckling ≈ 2 pN | **PASS** — crit-buckle = 2.0 pN at L60 |
| stable tension response | **PASS** — kTens 70 (straight, stiff) |
| compliant compressive buckling | **PASS** — kComp 0.684 pN/nm (buckles-soft), the emergent asymmetry |
| working stroke within validated range | **PASS** — 7.26 nm (105% of fixed; = the L40 value) |
| controls (polarity/rotation covariance, no stiffness switch) | **PASS** — stroke world=swap=rot90=7.27 nm; beam nucleotide-independent |
| L40 byte-identity after the L-parameterization code change | **PASS** — L40 single-head cell (ρ300 s101) reproduces velProd=+6.5604, meanBoundHeads=4.898 exactly (the beam-stride change is n·W = 210 at M=4 = the old SYS_STRIDE) |

## 3. Device-resident L60 gliding-cell health (single-head GPU)

The L60 single-head production cell runs **device-resident** (`buildGlidingGraph`, M=6, no CPU fallback) with
**0 invalid / 0 solveFail** at ρ300 and ρ500 (short-window checks); the beam SoA strides are M-derived (node
3(M+1)=21, scratch n·W=420 at M=6) and byte-identical to the L40 sizes at M=4.

## 4. L60 dimer implementation check (CPU)

The L60 dimer (`explicit-hmm-dimer-l60`, Ms=5, NF=7, NDOF=25) is built from the dimension-generic
`ExplicitHmmDimer.build`/`solve` via a settable `TOTAL_NM` (default 40 keeps L40 byte-identical). A ρ300 CPU
cell runs with **invalid=0, solveFail=0**, joint gap bounded (maxGap 4.55 nm), branch force bounded (peak 57 pN)
— internally valid. (The GPU forked-dimer kernel correctly refuses this non-(3,1,1) topology.)

## Verdict

**PASS.** Contour conserved, axial/bending/buckling scale exactly as MD predicts (k_ax 70, buckle 2.0,
kComp 0.68), stroke L-robust, 0 invalid/solver, L40 byte-identity preserved. The L60 configuration is
numerically and mechanically valid — the ensemble density sweep is authorized (Part D).
