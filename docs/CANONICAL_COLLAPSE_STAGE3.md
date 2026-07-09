# Canonical collapse — STAGE 3: kill silent diagnostic divergence + finish parameter reclassification + tidy

**Date:** 2026-07-08. The finishing stage of the canonical collapse: make every diagnostic's model visible,
enforce the swept-parameter reclassification, sweep the safe orphaned dead code, and mechanism-name the last
lineage label — with the canonical default byte-identical throughout. `BoA-v1ref` untouched.

---

## HEADLINE

**Can any diagnostic still silently run a non-canonical model? NO** — the 8 own-scene diagnostics (+ `canonDiag`)
now emit a `[NON-CANONICAL DIAGNOSTIC]` banner; the canonical-scene diagnostics (`diag`/`cycldiag`/`brakeDiagnose`)
run `buildScene` (canonical) by construction. **Is the canonical default still byte-identical? YES** — CPU
4.583/3.268, GPU 4.584/3.269 (800/6000 steps), unchanged from Stage 2; legacy 13.057 unchanged. **What needs jba's
confirm?** Nothing on ROUTE (there are zero ROUTE candidates — all 8 deliberately probe a non-canonical model);
`-tauavg`/`ATP_RELEASE` are retained (feed KEEP paths) pending any decision to cull their dependents. **The
canonical collapse is COMPLETE.**

---

## PART 0 — fresh rollback tag (pushed)

`post-stage2-2026-07-08` at `a10a871` (committed Stage 2), pushed to origin — the Stage-3 recovery point.

---

## PART 1 — diagnostic-scene divergence policy (all MARK, zero ROUTE)

**Finding:** none of the 8 own-scene diagnostics measures the canonical model. Each deliberately runs a phase-2
**config-1/perp motor**, a **raw-law fine-dt single-motor** scene, or a **bare kernel probe** — which is the
correct thing for what it measures. Routing any to canonical `buildScene` would measure a *different motor than
the one it is calibrating* ⇒ **defeats its purpose**. So every one is **MARK**; **zero ROUTE candidates** (nothing
for jba to confirm).

| diagnostic | measures | dt | model / scene | disposition |
|---|---|---|---|---|
| `headTiltSweep` | config-1/perp powerstroke force-decomp vs head-tilt θ | 1e-6 | raw chainParams (decompRun), config-1/perp single motor | **MARK** |
| `forceDecomp` | config-1/perp powerstroke force decomposition | 1e-6 | raw chainParams (decompRun), config-1/perp single motor | **MARK** |
| `boundGeom` | config-1 bound-state geometry | 1e-6 | raw chainParams, config-1 single motor | **MARK** |
| `stiffnessAngleSweep` | κ + angle sensitivity of peak≈stall | — | analytic (no dynamics scene) | **MARK** |
| `singleMolecule` | config-1 single-molecule duty | 1e-5 | `buildScene` but **config-1** motor (not sphere-head) | **MARK** |
| `dCalib` | config-1 catch force-sensitivity | 1e-5 | config-1 single-molecule | **MARK** |
| `catchSlipRecal` | config-1 catch-slip recal (Guo&Guilford) | 1e-5 | config-1 single-molecule | **MARK** |
| `swingKProbe` | kernel swing-coefficient extraction | 1e-5 | bare kernel probe (no physics scene) | **MARK** |
| `canonDiag` (bonus) | config-1 two-point binder instrument | 1e-5 | config-1 | **MARK** |

**Applied:** a centralized `diagMark(String)` helper prints
`  [NON-CANONICAL DIAGNOSTIC] <what> — does NOT run the canonical production model (sphere-head+springs+
Lymn-Taylor @dt=1e-5). See CANONICAL_COLLAPSE_STAGE3.md.` at each dispatch site (verified live: `-single` emits
it). **Byte-identical** (label only — these paths were never the canonical run). Note (dt column): even the 1e-5
config-1 diagnostics would not be byte-identical if "routed", because the *motor* differs (config-1 vs sphere-head)
— routing is wrong for them on the model axis, not just the springs axis.

**Canonical-scene diagnostics (no mark needed):** `diag` / `cycldiag` / `brakeDiagnose` take the `buildScene`
canonical `sc` (sphere-head+springs, no CONFIG1 flag) ⇒ they already reflect canonical ⇒ no silent divergence.

**Net requirement met:** every diagnostic either runs `buildScene` (canonical) or carries an explicit
non-canonical banner. No diagnostic can be silently mistaken for canonical.

---

## PART 2 — `coltol` / `density` enforcement (finish D7)

**Chosen form: loud placeholder warning on the main gliding run** (the task's "neutral placeholder that cannot
read as a chosen operating point"), NOT a hard error. **Rationale from the KEEP-path audit:** `run_gliding.sh`'s
own documented bare CPU probe and several live scripts (`run_densesweep`/`run_phaseB`/`run_phaseC`/`run_substep`/
`run_xbstiff`/`run_fp64_phase1`) invoke the main run WITHOUT `-density`; a hard error would break them. (The other
no-`-density` scripts — `run_allnoise_parity`/`run_thermcorr_dtvanish`/`run_xbdash`/`run_xbimplicit_gliding` —
already reference Stage-2-deleted flags and are independently dead.)

- `DENSITY_SET` / `COL_TOL_SET` track explicit passing. The main gliding run (before `grid`/`gpu`/`probe`) prints
  `  [SWEPT-PARAM WARNING] -density defaulted to 500.0/µm², -coltol defaulted to 6.0 nm — PLACEHOLDER value(s),
  NOT a canonical operating point. Pass … explicitly for any REPORTED gliding run (D7).` when either is unset
  (verified fires). **Diagnostics are exempt** (they `return` before the check; their internal density/coltol is
  a diagnostic condition). **Byte-identical when params are passed** (verified: 0 warning lines with `-density
  -coltol` set) ⇒ all reported/gated runs unaffected; a silent meaningful default can no longer survive.
- `coltol` stays a marked placeholder (physically needed to run); not made obligatory (task's softer treatment).
- **Deferred (Stage-4-if-ever):** strict obligatory-erroring is intentionally not adopted — it would break the
  bare-probe KEEP paths for no safety gain over the loud warning.

---

## PART 3 — orphaned dead-method sweep (partial; byte-identical-verified)

**Critical correction to the Stage-2 record:** a cross-*harness* grep showed **4 of the methods Stage 2 listed as
orphaned are LIVE in other harnesses** and must NOT be removed (removing them = BAIL):
- `CrossBridgeSystem.implicitCorrect`, `.dashpotForces`, `MotorStore.setDashpot` → called by **`V2OneXHarness`**.
- `CrossBridgeSystem.directedSwingHeadFrame` → called by **`AxLockGateHarness`**.

**Removed (verified 0 callers across ALL harnesses; gate byte-identical after):** the **6 orphaned noise `scale*`
methods** in `BrownianForceSystem.java` (`scaleBoundHeadNoise`/`scaleMotorNoise`/`scaleBoundSegNoise`/
`scaleThermCorrSeg`/`scaleSysWideMotor`/`scaleSysWideSeg`) — a clean contiguous block (the flagged noise-hazard
family) directly before the canonical `brownianForce` kernel, which is untouched. Removing uncalled methods does
not change the canonical PTX; the gate (PART 6) confirms byte-identical.

**Left as documented-orphaned (benign, scattered; deferred to an optional future cosmetic sweep):**
`CrossBridgeSystem.setBindMhat`, `NucleotideCycleSystem.catchSlipReleaseAvgRecharge` / `.catchSlipReleaseRecharge`
/ `.cycleNoBoundAtp` (0 callers; `cycleNoBoundAtp` still referenced in design comments). Not worth touching the
bistability-sensitive shared files further for zero functional gain.

---

## PART 4 — mechanism-name the force-cap label

`CAP_ROW … faithfulRelease=ON/OFF` → `CAP_ROW … forcecapdetach=ON/OFF` (mechanism name matching the Stage-2
`-faithfulrelease`→`-forcecapdetach` flag rename). The lineage term now lives in git history, not current output.
(Rename recorded in JOURNAL: `-faithfulrelease`→`-forcecapdetach`, `faithfulRelease=`→`forcecapdetach=`.)

---

## PART 5 — `-tauavg` / `ATP_RELEASE` disposition (retained; documented)

Both remain (guard-retained in Stage 2), **default: keep** — deleting either breaks a KEEP path, and canonical
reads neither ⇒ byte-identical:
- **`-tauavg`** — feeds the kept phase-2 diagnostics `dCalib` / `catchSlipRecal` / `singleMolecule` (each holds a
  `τ` window via `TAU_AVG`).
- **`ATP_RELEASE` / `-noatprelease`** — consumed only by the kept opt-in `-config1` motor (`cycleAtpDetach`).

**Delete only if jba also culls the dependent phase-2 diagnostics / the config-1 motor** — flagged, not decided
here.

---

## PART 6 — acceptance gate

Canonical default `-full -grid -seed 0 -dt 1e-5 -coltol 10 -density 1000`, both windows, both runners, vs the
Stage-2 numbers:

| window | runner | Stage-2 | Stage-3 | verdict |
|---|---|---|---|---|
| 800 | CPU | 4.583 | **4.583** | byte-identical |
| 800 | GPU | 4.584 | **4.584** | byte-identical |
| 6000 | CPU | 3.268 | **3.268** | byte-identical |
| 6000 | GPU | 3.269 | **3.269** | byte-identical |

- **MARK diagnostics:** byte-identical (label only) — `-single` emits the banner; canonical GRID_ROW unchanged.
- **ROUTE:** none applied (none exist).
- **Enforcement:** canonical run with explicit params = byte-identical (0 warning lines); bare run emits the
  placeholder warning and still runs; diagnostics exempt.
- **PART-3 removal:** canonical byte-identical (the 6 noise methods were dead) — confirmed, not assumed.
- **KEEP paths:** legacy (`-legacycycle -explicitxb -nosprings -legacymotor`) = velFitX **13.057** (unchanged);
  `V2OneXHarness`/`AxLockGateHarness` compile (kept methods intact); `-forcecapdetach`/`-segimplicit` intact.

**No BAIL.** Canonical byte-identical on both runners; no KEEP path broken.

---

## Summary — the canonical collapse is COMPLETE

- **Tag `post-stage2-2026-07-08` (`a10a871`) pushed.**
- **PART 1:** all 8 own-scene diagnostics (+ `canonDiag`) MARKED `[NON-CANONICAL DIAGNOSTIC]`; zero ROUTE (all
  deliberately non-canonical by design). No diagnostic can silently pass as canonical.
- **PART 2:** `density`/`coltol` enforced via a loud placeholder warning on the main run (KEEP-path-safe;
  byte-identical when passed); hard-error deferred (would break bare-probe KEEP paths).
- **PART 3:** removed the 6 orphaned noise `scale*` methods (gate byte-identical); **corrected the Stage-2 record**
  — 4 "orphaned" methods are actually live in `V2OneXHarness`/`AxLockGateHarness` (kept); scattered remainder left
  documented.
- **PART 4:** CAP_ROW label mechanism-named `forcecapdetach=`.
- **PART 5:** `-tauavg` + `ATP_RELEASE` retained (feed KEEP paths), documented; cull only with their dependents.
- **PART 6:** canonical byte-identical (CPU 4.583/3.268, GPU 4.584/3.269), legacy 13.057, MARK+WARNING verified.

**Can any diagnostic still silently run a non-canonical model?** **NO.** **Is the canonical default byte-identical?**
**YES.** **Needs jba's confirm:** nothing on ROUTE; the `-tauavg`/`ATP_RELEASE` retention (keep unless the phase-2
dependents are culled).

**Collapse status: DONE** (Stages 1–3: bake → delete/rename/reclassify → visibility/enforcement/tidy; canonical
byte-identical throughout, rollback tags at each stage). **Deferred (each its own task, per the ratified set):**
the phase-2 two-point canonical motor (`-canonical`/`-config1`/`-perphead`, opt-in); the `-forcecapdetach`
promotion decision; the springs fine-dt recalibration (below refDt); and the optional cosmetic sweep of the
remaining documented-orphaned methods + the `-tauavg`/`ATP_RELEASE` disposition.
