# Audit ALL per-step-fraction PAIRS coefficients (motor + FILAMENT); convert to per-time rates; isolate the true residual stiffness

**Date:** 2026-07-05. Flag-gated (`-filrate`, `-ratefix`, new; `-strokerate`/`-alignrate` unchanged); **default
byte-identical**; `BoA-v1ref` untouched. Follows `STROKE_DT_RATE_DIAGNOSIS.md` (motor swing/alignment torques are
per-step fractions; `-alignrate` cut the fine-dt glide runaway ~40 % but a **2.24× residual climb** survived —
measured with the FILAMENT's torques STILL per-step). jba's generalization: the per-step-fraction bug is a property
of the **master PAIRS torque-law convention**, so the FILAMENT's own bending/torsion coefficients carry the same
∝1/dt bias, and (stiffer filaments glide faster) the filament's dt-stiffening co-conspires in the 2.24× residual.
**You cannot dt-converge a model whose mechanical rate constants move with dt** — every per-step-fraction coeff
(motor AND filament) must be per-time before "convergence" is even well-defined.

---

## PLAIN ANSWER (headline)
**After converting ALL per-step-fraction PAIRS coefficients (motor rotational + filament) to per-time rates, how
much of the dt-climb remains, and is it dt-robust at production dt without an implicit/sub-step?**
→ **A ~1.8× residual remains (down from the 3.07× ± 0.19 baseline) — a REAL, substantial reduction, but NOT
dt-robust at production dt (~1.8× ≫ flat). A genuine rotational-integration stiffness survives ⇒ still needs a
rotational implicit / sub-step, but sized against the REDUCED ~1.8× residual, not the inflated 2.24×/3.11×.**
- **STEP 1:** the filament's own F3-bending (fracMove=0.5) and F4-torsion (fracMoveTorq=0.2) ARE per-step fractions
  (∝1/dt), same convention as the motor swing/alignment — **v1-inherited** (a shared latent dt-convention issue, a
  model improvement, not a v2 faithfulness divergence). `fracR` is geometry (not a rate); the catch is per-time.
- **STEP 3 (3-seed):** `-ratefix` (all per-step fractions → per-time) pulls the 2.5e-6 glide from 6.85 to ~3.9
  (−43 %) and the residual climb from 3.07× to ~1.8×. But ~1.8× survives ⇒ genuine explicit rotational-integration
  stiffness.
- **Caveats (honest):** single-seed was MISLEADING (seed-0 looked like "filament null, residual 2.1×"; multi-seed
  shows a bigger, noisier reduction to ~1.8×). The **filament-vs-motor split is unresolved** — allrate (motor-only)
  was only multi-seeded at seed 0; the extra reduction may be the filament OR motor-rate noise (the rate fixes
  raise run-to-run variance). ratefix@2.5e-6 is n=2 (seed-2 pending). jba's "stiffer filament glides faster" is not
  clearly borne out for this sparse single-filament AXIAL glide (it would matter more in dense/buckling assays).

---

## STEP 1 — full audit: every per-step-fraction coefficient (the `k·angle/((1/γ_a+1/γ_b)·dt)` pattern)

The integrator applies `Δx = F·(1e6/γ)·dt` (translation) and `Δθ = τ·dt/γ` (rotation). A coefficient is a
**per-step fraction** (relaxes a fixed fraction PER STEP, dt-independent per step ⇒ sim-time rate ∝ 1/dt) iff its
force/torque carries a `1/dt` that cancels the integrator `·dt`. Inventory for the gliding assay:

### MOTOR (from STROKE_DT_RATE_DIAGNOSIS; restated)
| coeff | file:line | value | class | in gliding default | glide role |
|---|---|--:|---|---|---|
| directedSwing stroke | `CrossBridgeSystem.directedSwing:251` | swingParams[0]=0.4 | **PER-STEP** (`k·ang/((..)·dt)`) | ON | negligible (`-strokerate`) |
| F9/F10/axlock align | `CrossBridgeSystem.bondForces:152/163/185` | xbParams[2]=0.4 | **PER-STEP** | ON | REAL ~40 % (`-alignrate`) |
| F8 tip torque R×F8 | `bondForces:137-141` | — | explicit positional torque (NOT a fracMove) | ON | the residual STIFFNESS |
| catch g(F) release | `catchSlipRelease:193-197` | — | **per-time rate** (`u<rate·dt`) | ON | not implicated |
| J1/J2 angular converter | `MotorJointSystem:139/183` | jointParams[3]/[7] | per-step form, but **=0 (OFF)** in gliding (DIRSWING sets [3]=0; [7]=0) | OFF | none |
| J1/J2 position spring | `MotorJointSystem:128/169` | jointParams[1]/[5] fracMove | **PER-STEP** | ON | STRUCTURAL (holds motor body rigid) |
| tail anchor | `TailAnchorSystem:59` | jointParams[9] anchorFracMove | **PER-STEP** (translational only, no torque) | ON | STRUCTURAL (holds rod to bed) |

### FILAMENT (the new target — jba's prediction)
| coeff | file:line | value | class | ∝1/dt bias? |
|---|---|--:|---|---|
| **F3 link spring** (fracMove) | `ChainBendingForceSystem.chainForces:207` | chainParams[1]=**0.5** | **PER-STEP** (`fracMove·strain/(dt·(moveC1+moveC2))`) | YES — the translational link AND its lever-arm **bending** torque (R×F, `Rscale=½·len·fracR`, lines 211-213/296-298) |
| **F4 torsion** (fracMoveTorq) | `ChainBendingForceSystem:227-232/313-318` | chainParams[3]=**0.2**, filTorqSpringActive=**0** ⇒ damped branch | **PER-STEP** (`fracMoveTorq·ang/((1/γ+1/γ)·dt)`) | YES — the segment↔segment **twist** |
| fracR (bend lever arm) | `:211/296` | chainParams[2]=0.1 | **geometry/magnitude** (scales torque size, NOT a rate) | NO — leave unconverted |
| F4 Hookean mode | `:228/314` | filTorqSpringActive=1 path | proper angular spring (`k·filTorqSpring·ang`, no `/dt`) | n/a — inactive in gliding |

**⇒ The filament F3-bending (fracMove=0.5) and F4-torsion (fracMoveTorq=0.2) ARE per-step fractions** ⇒ the
filament's angular relaxation (bending + twist) stiffens ∝1/dt exactly as jba predicted. `fracR` is geometry (not
converted). The Hookean F4 branch (a proper spring) is inactive here.

### OTHERS (not in the gliding scene)
Crosslinker torsion / node-tether / minifilament align all use the same PAIRS `fracMove/dt` convention (per-step),
but there are none in the gliding assay ⇒ not exercised here (they'd need the same treatment in their own assays).

### v1-inherited?
**YES.** The whole `fracMove·strain/(dt·mobility)` PAIRS convention is a direct port of v1 (CLAUDE.md 5a note: "the
`/dt` cancels the integrator `·dt` ⇒ dt-independent relaxation … is v1's design"). So the ∝1/dt mechanical-rate
bias is a **shared latent dt-convention issue in v1**, a **model improvement, not a v2 faithfulness divergence** —
flagged, not silently "fixed" (default byte-identical; conversion is opt-in).

### Scope decision for `-ratefix`
Convert the **glide-relevant** per-step fractions: motor rotational (`-strokerate` stroke + `-alignrate` F9/F10)
AND filament (`-filrate` fracMove + fracMoveTorq). The motor **structural** position joints (J1/J2 position,
anchor) are per-step but hold the motor BODY rigid (not glide-rate generators; the rotation-generating J1/J2
torsions are already OFF); converting a structural body constraint is a separate stability question and is **left
unconverted** (flagged) so the experiment isolates the glide-rate coefficients cleanly.

## STEP 2 — `-filrate` / `-ratefix` conversion (build-time, default byte-identical)

`-filrate`: at BUILD time (DT known) convert the filament PAIRS per-step fractions to per-time rates via
`k_eff = 1 − (1−k)^(DT/refDt)` (refDt=1e-5): `chainParams[1]` fracMove 0.5 → k_eff, `chainParams[3]` fracMoveTorq
0.2 → k_eff (`fracR` geometry unchanged). No kernel change (a constant per run) ⇒ CPU≡GPU trivially, default
byte-identical (k_eff==k at refDt). `-ratefix` = `-strokerate` + `-alignrate` + `-filrate` (all glide-relevant
per-step fractions). k_eff at the test dt: fracMove 0.5→0.159 (2.5e-6) / 0.084 (1.25e-6); fracMoveTorq
0.2→0.054 / 0.027; swing/align 0.4→0.120 / 0.062. Motor structural position-joints (J1/J2, anchor) NOT converted
(structural, torsions off — the flagged scope decision). GPU PTX + `-cpu` both run (3000-step smoke: all disclosures
print, no fault; velFitX@2.5e-6 preview 1.16 — far below allrate 5.50, hinting a large filament effect).

## STEP 3 — dt ladder: baseline / motor-rate / motor+filament-rate

**velFitX (glide, µm/s), seed 0, coltol10/d1000/-xbimplicit2:**

| row | scheme | 1e-5 | 2.5e-6 | 1.25e-6 | climb 1e-5→2.5e-6 |
|--|---|--:|--:|--:|--:|
| 1 | coupled (no rate fix)          | 2.020 | 6.279 | 10.064 | **3.11×** |
| 2 | motor rate (`-strokerate -alignrate`) | 2.459 | 5.503 | 5.880 | **2.24×** |
| 3 | **motor+FILAMENT (`-ratefix`)** | 2.459 | 5.088 | 5.896 | **2.07×** |

**3-SEED SEM (rows 1 & 3, the decision-critical residual; seeds 0/1/2; ratefix@2.5e-6 = 2 seeds, seed-2 pending):**

| row | scheme | velFitX @1e-5 | @2.5e-6 | residual (2.5e-6/1e-5) |
|--|---|--:|--:|--:|
| 1 | coupled (no rate fix) | 2.251 ± 0.185 (n=3) | 6.853 ± 0.287 (n=3) | **3.07× ± 0.19** (per-seed 3.11/3.38/2.72) |
| 3 | **ratefix (motor+FILAMENT)** | 2.069 ± 0.207 (n=3) | 3.913 ± 1.175 (n=2) | **~1.8×** (per-seed 2.07/1.37; ratio-of-means 1.89×) |

**The single-seed (seed-0) read was misleading — the multi-seed REVISES it.** On seed 0, ratefix ≈ allrate ≈ coupled
looked like "filament null, residual ~2.1×." But across 3 seeds the picture is noisier and the reduction LARGER:

**(a) `-ratefix` (all per-step fractions → per-time) REDUCES the residual from 3.07× → ~1.8×** — pulling the 2.5e-6
glide down from coupled 6.85 to ~3.9 (−43 %). So converting the rate-convention coefficients is a **real, substantial
dt-robustness improvement**, bigger than seed-0 implied.

**(b) But a ~1.8× residual SURVIVES ⇒ genuine explicit-integration stiffness remains.** ~1.8× is still well above
flat (≤1.1–1.2×), so even with every per-step-fraction PAIRS coefficient made per-time, the glide is NOT dt-robust at
production dt. That surviving ~1.8× is the rotational-integration stiffness (the F8 tip-torque + alignment `Δθ=τ·dt/γ`
overshoot) — the number the implicit/sub-step decision must be sized against.

**(c) The filament-vs-motor SPLIT is UNRESOLVED at this seed count.** allrate (motor-only) was multi-seeded only at
seed 0 (2.24× residual); ratefix (motor+filament) 3-seed is ~1.8×. ratefix < allrate-seed-0 HINTS the filament may
contribute after all — in tension with seed-0's ratefix≈allrate — but both sit inside the large run-to-run variance
(ratefix@2.5e-6 SEM ±1.2 on n=2; the rate fixes visibly INCREASE variance). **We cannot cleanly attribute the extra
reduction to the filament vs motor-rate noise without multi-seed allrate** (not run — flagged). What IS robust: the
baseline residual 3.07×±0.19, and that `-ratefix` leaves ~1.8× (down from 3.07×).

### FORK VERDICT — "row-3 climb REDUCED but still significant" (~3.07× → ~1.8×). Converting ALL per-step-fraction
### PAIRS coefficients (motor + filament) is a real dt-robustness win but does NOT reach production-dt dt-robustness;
### a ~1.8× genuine rotational-integration stiffness survives ⇒ **scope the rotational implicit / sub-step against the
### REDUCED ~1.8× residual (not the inflated 2.24×/3.11×).** The filament's specific share is within-noise / unresolved
### (seed-0 null vs multi-seed hint) — resolving it needs multi-seed `allrate`; the filament PAIRS ∝1/dt bias is real
### (STEP 1) and likely matters more in dense/buckling/contractile assays than in this sparse single-filament glide.

## Reproduce
```
# filament PAIRS per-step → per-time (default byte-identical; chainParams[1]/[3] converted at build):
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -filrate -dt <DT> -seed <s> <STEPS>
# ALL glide per-step fractions (motor rotational + filament): add -ratefix (= -strokerate -alignrate -filrate)
./run_gliding.sh ... -xbimplicit2 -ratefix -dt <DT> -seed <s> <STEPS>
#   dt 1e-5→120k, 2.5e-6→480k, 1.25e-6→960k. refDt override: -strokerefdt <s> (default 1e-5).
# batches: ./run_ratefix_conv.sh (seed-0 ladder)  ./run_sem_ladder.sh (3-seed rows 1&3)
#   logs RUN_LOGS/2026-07-05_ratefix_conv.txt, _sem_ladder.txt
```
Files: `GlidingHarness` (`FIL_RATE`/`RATE_FIX` flags; `rateFix(k)=1−(1−k)^(DT/refDt)` helper; build-time
`chainParams[1]`=fracMove, `chainParams[3]`=fracMoveTorq; `xbParams[2]`=alignK reuses `rateFix`). No kernel change
(constants per run) ⇒ CPU≡GPU trivially, default byte-identical. `BoA-v1ref` untouched.

## Open follow-ups (flagged, not done)
1. Multi-seed `allrate` (motor-only) at 1e-5/2.5e-6 to cleanly split the filament vs motor-rate share of the
   3.07×→~1.8× reduction (this run only multi-seeded coupled + ratefix).
2. Complete ratefix@2.5e-6 seed-2 (n=2 → n=3) to tighten the ~1.8× residual SEM.
3. The filament PAIRS ∝1/dt bias in DENSE / buckling / contractile assays (where filament stiffness sets the
   mechanics) — `-filrate` is the instrument; jba's stiffer-glides-faster mechanism is more likely to bite there.
4. The residual ~1.8× rotational-integration stiffness → the rotational implicit (extend the COUPLED_IMPLICIT_XB
   F8-translation star to head/segment ROTATION) or sub-step, sized against ~1.8× (not 2.24×/3.11×).
