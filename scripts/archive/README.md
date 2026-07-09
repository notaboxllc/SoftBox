# scripts/archive/ — retained experiment/investigation drivers (EVIDENTIARY)

Relocated here from the repo root in the base-directory reorg (2026-07-08), alongside the
canonical-collapse journal archive sweep. These are **done, one-off experiment drivers** that are the
**reproduce-path for a kept findings doc** — retained on the same "preserve the evidence" logic as a
regression reference. They are **not** part of the live toolchain (the actively-reused subsystem /
benchmark validation drivers stay at the repo root).

**Running caveat.** Several of these pass flags that were **deleted in the canonical collapse (Stage 2)**
— e.g. `-ratefix`/`-structrate`/`-alignrate`/`-strokerate` (rate machinery), `-bondnoise`, `-varprobe`,
`-swingk`, `-faithfulrelease`, `-xbimplicit` (head-only). They therefore **do not run against current
`main`**; check them out at the pre-collapse tag **`pre-canonical-collapse-2026-07-08`** (or
`post-stage1-pre-deletion-2026-07-08`) to reproduce. A few (`run_promo_gate2_*`, `run_puresprings_*`
that use only kept flags) still run, but their internal relative paths (`./build.sh`, argfile) assume
the repo root — invoke from root or adjust.

## What each backs (finding → driver)
- **Bistability (GPU basin artifact)** — `run_bistab_cpu.sh`, `run_bistab_partC.sh` → `BISTABILITY_ORIGIN.md`
- **Springs formulation / promotion** — `run_puresprings_{cpuarb,gpu,step2,step4}.sh` → `PURE_SPRINGS.md`;
  `run_promo_gate2_{cpu,gpu}.sh` → `SPRINGS_PROMOTION.md`
- **dt-convergence study & reference ladders** — `run_dtconv.sh` → `DT_CONVERGENCE_FINDINGS.md`;
  `run_ratefix_conv.sh`/`run_sem_ladder.sh` → `PAIRS_RATE_AUDIT_FINDINGS.md`;
  `run_alignrate_conv.sh`/`run_strokerate_conv.sh` → `STROKE_DT_RATE_DIAGNOSIS.md`;
  `run_couple_conv.sh` → `COUPLED_IMPLICIT_XB_FINDINGS.md`; `run_rotimpl_conv.sh` → `ROTIMPLICIT_FEASIBILITY.md`;
  `run_xbtrap_conv.sh`/`run_xbtrap_parity.sh` → `XBTRAP_PROBE.md`
- **Kept integrator/probe references** — `run_segimpl_probe.sh`/`run_ktot_census.sh` → `SEG_IMPLICIT_FINDINGS.md`
  (`-segimplicit` is KEPT for the ring); `run_eomstab.sh` → `EOM_STABILITY_FINDINGS.md`;
  `run_substep.sh` → `SUBSTEP_FEASIBILITY_FINDINGS.md`; `run_xbstiff.sh` → `CROSSBRIDGE_STIFFNESS_SWEEP_FINDINGS.md`
- **Gliding / binding / release studies** — `run_radiussweep.sh` → `GLIDING_RADIUS_SWEEP_FINDINGS.md`;
  `run_bindsearch.sh` → `BINDING_SEARCH_REFORMULATION_FINDINGS.md`;
  `run_relforce.sh` → `RELEASE_FORCE_INPUT_FINDINGS.md`; `run_bondcorr.sh` → `BOUND_THERMAL_CORRELATION_FINDINGS.md`
- **Motor / phase / force-cap** — `run_axlock.sh` → `PHASE2_SPHEREHEAD_AXLOCK_FINDINGS.md`;
  `run_phaseC.sh`/`run_phaseC_forcecap.sh` → `GLIDING_4biv_FINDINGS.md` (force-cap; now `-forcecapdetach`);
  `run_phaseB.sh`/`run_fp64_phase1.sh` (fp64 exploration)
- **Scratch analysis helpers (generate findings plots)** — `scratch_boundgeom_plot.py`,
  `scratch_sd_mmfit.py`, `scratch_setafit.py`, `scratch_setasweep.sh`, `scratch_after.sh`
