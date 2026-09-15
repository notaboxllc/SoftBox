> ⚠ **TORSIONAL COHERENCE INVALIDATION (2026-09-06)** — twirl MAGNITUDES in this document were
> measured with NO inter-segment torsional coupling (segments rolled independently; internal twist drift
> reached +9.1 turns). Absolute pitch / turns-per-µm / Ω values here are VOID pending re-measurement with
> `-rollspring`. Sign, antisymmetry and null results are unaffected. See
> `docs/twirl/TORSIONAL_COHERENCE_INVALIDATION.md`.

# Site-lattice chirality alone does NOT produce twirling (eps = 0 control)

**Campaign** `RUN_LOGS/motor_audit/campaigns_2026-08/TWIRL_REPRO_GPU` · 2026-08-22/23 · GPU device-resident
d500, eta 0.10, dt 1.25e-6, lawn 10 x 2.0 um, filament launched at x=+2.5 gliding toward -x, target 4.0 um.
NATIVE x3 vs MIRROR x3 (matched seeds). All six `STOP=TARGET_REACHED`, 0 invalid, 0 solverFail, 0 Xid.

## Result

| arm | turns/um | per seed |
|---|---|---|
| NATIVE | +0.120 +- 0.228 | -0.337 / +0.330 / +0.365 |
| MIRROR | -0.106 +- 0.247 | -0.440 / -0.256 / +0.377 |

**Chiral (eps-odd) component = (native - mirror)/2 = +0.113 +- 0.168 turns/um — 0.7 sigma. NULL.**
2-sigma upper bound on |chiral twirl|: **0.45 turns/um**.

## Why this is an exclusion, not merely an underpowered null

The PATH_B (`every4`) site lattice has 10.8 nm rise and +54 deg azimuthal advance per site = a **72 nm helical
repeat**. A filament rigidly tracking its own site register would twirl at **13.9 turns/um** — excluded here at
82 sigma. The experimentally reported twirl (~1 turn/um) is excluded at **5.3 sigma**.

Note the experiment is itself ~14x SLOWER than actin's helical repeat, so the real assay's twirl cannot be
lattice-tracking either. This null is therefore CONSISTENT with the experimental literature.

## Scope — what was actually tested

Site-lattice chirality **combined with an achiral, site-normal binding law** (`xHeadHat = -n_site`). That law is
mirror-symmetric about the plane containing the filament axis and the site normal, so it plausibly *cannot*
convert lattice handedness into net torque regardless of site arrangement. The honest claim is therefore
**"helical site placement + achiral site-normal binding produces no twirl"**, not "helical placement cannot
produce twirl". Breaking that symmetry (motor/converter skew, `EPS_BIND_DEG`/`EPS_STROKE_DEG`) is the next test;
**this campaign is its eps = 0 control.**

## Methodology notes (hard-won; see JOURNAL 2026-08-21/23)

- **Omega (net roll / run length) is NOT a rotation rate** — roll is diffusion-dominated and Omega scales as
  T^-1/2. Report drift + D separately, and turns per um of TRAVEL as the experimental observable. See
  `twirl-omega-is-diffusive-artifact` memory.
- **Never verdict on "the arm means have opposite signs"** — two noisy means straddling zero do that half the
  time. This analyser reported "REVERSES" on a 0.7-sigma null until fixed.
- **Size MAT_Y from measured lateral wander, not binding reach.** `-maty 0.5` (reach ~0.183 um) let the filament
  diffuse and yaw off the motor strip; capture rate collapsed 1.01 -> 0.41 /ms with NO motor defect. The
  `yMargin_um` trajectory column now detects this.
- Arm means moved from -0.297 (n=2) to +0.120 (n=3) — noise-dominated; do not read n=2 twirl results.
