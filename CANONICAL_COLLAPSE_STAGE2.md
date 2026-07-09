# Canonical collapse — STAGE 2: delete dead paths + rename + reclassify (canonical stays byte-identical)

**Date:** 2026-07-08. Scope: delete the ratified dead paths, rename `-faithfulrelease`, reclassify
`coltol`/`density` as swept parameters — with the canonical default byte-identical before/after on both runners.
No diagnostic-scene re-routing (Stage 3). `BoA-v1ref` untouched.

---

## HEADLINE

**Did deleting the dead paths leave the canonical default byte-identical on both runners? YES** — all four gate
cells (800 + 6000 steps × CPU + GPU) reproduce the pre-deletion numbers to every printed digit (CPU velFitX
4.583 / 3.268; GPU 4.584 / 3.269). **What is canonical's honest steady-window glide? ≈ 2.83 µm/s** (GPU 3-seed
mean velFitX 2.825 ± 0.054, per-bound ~0.85; CPU spot-check 2.867), sitting near **Vmax (~2.9) of the skeletal
band (~1.5–4 µm/s)** — the earlier 4.583 was an 800-step transient, not a glide speed. **No BAIL.**

---

## PART 0 — fresh rollback tag (pushed)

Committed Stage 1 (`1b87c72`, tagged `post-stage1-pre-deletion-2026-07-08`, pushed to origin) — the recovery
point for these deletions. (Stage-0 tag `pre-canonical-collapse-2026-07-08`/`5b60b0a` remains the pre-Stage-1
point.)

---

## PART 1 — pre-deletion canonical reference (the acceptance target + the honest number)

**Byte-identical target** (canonical default `-full -grid -seed 0 -dt 1e-5 -coltol 10 -density 1000`), captured
on the Stage-1 binary BEFORE any deletion — this is exactly what PART 5 must reproduce:

| window | runner | velFitX | inst | instSteady | netXY | netSteady | netX | avgB | avgBsteady |
|---|---|---|---|---|---|---|---|---|---|
| 800 | CPU | 4.583 | 6.707 | 7.017 | 5.892 | 5.589 | −5.264 | 3.333 | 5.400 |
| 800 | GPU | 4.584 | 6.707 | 7.017 | 5.892 | 5.590 | −5.265 | 3.333 | 5.400 |
| 6000 | CPU | 3.268 | 6.556 | 7.145 | 2.767 | 3.129 | −2.767 | 3.328 | 3.710 |
| 6000 | GPU | 3.269 | 6.556 | 7.145 | 2.768 | 3.129 | −2.767 | 3.328 | 3.710 |

**Honest steady-window baseline** (jba's ask — for the record, NOT the gate). Matched sim-time 0.6 s = 60 000
steps, canonical default, GPU (springs-default is transcendental-free ⇒ GPU trustworthy, `SPRINGS_PROMOTION`
GATE-2) with one CPU spot-check:

| seed | runner | velFitX | avgBsteady | per-bound (velFitX/avgBsteady) |
|---|---|---|---|---|
| 0 | GPU | 2.895 | 3.332 | 0.869 |
| 1 | GPU | 2.718 | 3.103 | 0.876 |
| 2 | GPU | 2.861 | 3.515 | 0.814 |
| **mean** | **GPU** | **2.825 ± 0.054** (SEM) | **3.317** | **0.853** |
| 0 | CPU (spot) | 2.867 | 3.286 | 0.872 |

**CPU≡GPU basin agreement:** seed-0 velFitX 2.867 (CPU) vs 2.895 (GPU) ≈ 1%, avgBsteady 3.286 vs 3.332 ≈ 1.4% —
same HIGH basin, within the chaotic aggregate-SEM standard. **Canonical steady glide ≈ 2.83 µm/s vs the skeletal
band (~1.5–4 µm/s, Vmax ~2.9): canonical sits at the top of the band, right at Vmax.** Per-bound ~0.85. (The
800-step 4.583 / 6000-step 3.27 are settling transients on the way down to the ~2.83 steady value.)

---

## PART 2 — dead paths deleted (git tag preserves all)

Removed the flags AND their orphaned harness code (fields, parse, resolve, disclosures, Scene fields, buildScene
branches, buildPlan task-adds). **Guard honored: no canonical kernel body edited** — dead branches left inside
shared kernels (`bondForces` xbsat, `directedSwing` swing branches) are intentionally preserved so PTX
scheduling of the canonical path is byte-unchanged (the byte-identical gate confirms this held). Full-file grep:
**zero code references remain** to any deleted symbol.

| category | removed | notes |
|---|---|---|
| **Rate machinery** | `-ratefix`/`-strokerate`/`-alignrate`/`-filrate`/`-structrate` + helpers `rateFix()`/`rateFixAlpha()` + the STRUCT_RATE build block | ternaries simplified to `SPRINGS ? springify : raw`; springs untouched |
| **Noise family** | `-bondnoise`/`-bondnoisefac`/`-allnoise`/`-allnoisescale`/`-fracnoise`/`-thermcorr`/`-uninoise`/`-syswide`/`-syswiderb` + 6 Scene noise-param fields + all noise task-adds | the `scale*` kernel methods left orphaned in `BrownianForceSystem` (shared file; unused ⇒ not in canonical PTX) |
| **Failed integrators** | `-xbimplicit` (head-only), `-xbdash`/`-xbdashmech`, `-xbsat` | `XB_IMPLICIT‖XB_IMPLICIT2` → `XB_IMPLICIT2`; kept `xbImplPrev`/`xbImplParams`/`setImplicit` (used by canonical `-xbimplicit2`); `bondForces` xbsat body untouched; orphaned `implicitCorrect`/`dashpotForces`/`setDashpot` left |
| **Superseded kinetics** | `-atprecharge` (fully) | see deviations below for `-tauavg`/`-noatprelease` |
| **`-freshread`** | field/parse/error-check/`stepFresh`/the buildPlan reorder block | default step order retained |
| **Motor recasts** | `-hfswing`/`-rollsign`/`-mhatset` | xbParams ROLLSIGN size-12 branch collapsed to size-11; HFSWING dispatch → plain `if(DIRSWING)`; orphaned `directedSwingHeadFrame`/`setBindMhat` left; `captureBindTwist` kept (used by `-twistcensus`) |

**Confirmed:** no separate "neck-close/lay-down" motor flags exist beyond the recasts above (grep) — nothing else
to remove.

**Two guard-driven deviations from the literal delete list (flagged for jba, NOT deleted):**
- **`-tauavg` (TAU_AVG) KEPT** — it is a live parameter of the KEPT phase-2 diagnostics `dCalib` /
  `catchSlipRecal` / `singleMolecule`. Deleting it breaks a KEEP path. Canonical (Lymn-Taylor) never reads it ⇒
  byte-identity preserved. (`ATP_RECHARGE`, which also used the averaged-recharge path, WAS deleted.)
- **`-noatprelease` / `ATP_RELEASE` KEPT** — consumed only under the KEPT opt-in `-config1` motor
  (`cycleAtpDetach`). Deleting it breaks the config-1 phase-2 path. Canonical never reaches it ⇒ byte-identity
  preserved.

**Orphaned kernel methods left in shared system files** (dead, uncalled, do NOT enter the canonical PTX — safe;
a cosmetic sweep is a possible follow-up, not required): `BrownianForceSystem.scale{BoundHeadNoise,MotorNoise,
BoundSegNoise,ThermCorrSeg,SysWideMotor,SysWideSeg}`, `CrossBridgeSystem.{implicitCorrect,dashpotForces,
directedSwingHeadFrame,setBindMhat}`, `MotorStore.setDashpot`, `NucleotideCycleSystem.{catchSlipReleaseAvgRecharge,
catchSlipReleaseRecharge,cycleNoBoundAtp}`. Per the guard, unused methods in files shared with canonical kernels
are not deleted-through when there is any doubt — Java compiles them independently so they cost nothing at
runtime.

---

## PART 3 — rename `-faithfulrelease` → `-forcecapdetach` + `-detachcap <pN>`

- **`-faithfulrelease` → `-forcecapdetach`** (field `FAITHFUL_RELEASE` → `FORCE_CAP_DETACH`), opt-in, off by
  default. Behavior identical: a hard force-cap detachment trigger.
- **New `-detachcap <pN>` (default 12)** exposes the threshold (was hardcoded 12 pN). Wired through the existing
  `MotorStore.setFaithfulRelease(on, breakForcePN)` (a **shared API called by 11 harnesses** — method name left
  unchanged; only the gliding flag/field renamed). Off-by-default is byte-identical: `setFaithfulRelease(false,
  12.0)` sets `kinParams[12]=0` and `kinParams[11]=12 pN` — identical to the prior `(false, 0.0)` call (12 pN was
  already the default). Verified: `-forcecapdetach -detachcap 12` and `-detachcap 8` both parse and run.
- **NOT promoted into canon** (deferred, its own task, per Stage-1 flag).
- **Traceability:** the CAP_ROW diagnostic label still prints `faithfulRelease=ON/OFF` **intentionally** — it keeps
  prior logs/notes that reference `-faithfulrelease` searchable. Rename recorded in JOURNAL (old→new).

---

## PART 4 — reclassify `coltol` and `density` as swept parameters (D7)

Both are now marked (field comments) as **swept experimental conditions with NEUTRAL PLACEHOLDER defaults, NOT
canonical model values** (`density` 500, `coltol` 6 nm — placeholders the harness/diagnostics need to run, not
chosen operating points; always pass explicitly for reported runs). This is the marker-level reclassification the
task scoped for Stage 2 ("a comment/marker suffices for now"). **Strict obligatory-input enforcement** (erroring
on absence) **and per-diagnostic internal-density handling are deferred to Stage 3** (bundled with the
diagnostic-scene policy) — that avoids breaking the many diagnostics/scripts that currently rely on a runnable
default, which is exactly the "confirm no KEPT diagnostic silently depended on the old default" caveat. Byte-
identical (comment-only; no value or physics change).

---

## PART 5 — byte-identical acceptance gate (before ≡ after)

Canonical default, fixed `seed 0 / dt 1e-5 / coltol 10 / density 1000`, both windows, both runners:

| window | runner | BEFORE velFitX | AFTER velFitX | avgBsteady b/a | netX b/a | verdict |
|---|---|---|---|---|---|---|
| 800 | CPU | 4.583 | **4.583** | 5.400 / 5.400 | −5.264 / −5.264 | **byte-identical** |
| 800 | GPU | 4.584 | **4.584** | 5.400 / 5.400 | −5.265 / −5.265 | **byte-identical** |
| 6000 | CPU | 3.268 | **3.268** | 3.710 / 3.710 | −2.767 / −2.767 | **byte-identical** |
| 6000 | GPU | 3.269 | **3.269** | 3.710 / 3.710 | −2.767 / −2.767 | **byte-identical** |

Every printed metric matches to the digit on both runners. **The deletions were all dead — the canonical path did
not move.** GPU byte-identity additionally confirms no kernel-graph change leaked in (the guard on shared kernel
bodies held).

**KEEP-path checks (all pass):**
- **Legacy reachable + unchanged:** `-legacycycle -explicitxb -nosprings -legacymotor` → velFitX **13.057**,
  avgBsteady 21.000 — **byte-identical to the Stage-1 result**.
- **`-segimplicit`** runs (composes with canonical `-xbimplicit2`; disclosure + GRID_ROW produced).
- **`-forcecapdetach` / `-detachcap 12` / `-detachcap 8`** parse and run (CAP_ROW fires).
- **Diagnostic `-stretchcensus`** runs (STRETCH_ROW produced).

**No BAIL** — canonical byte-identical on both runners, all KEEP paths intact.

---

## Summary

- **Tag `post-stage1-pre-deletion-2026-07-08` (`1b87c72`) pushed** = the deletion rollback point.
- **Deleted:** rate machinery, the noise family, failed integrators (`-xbimplicit`/dash/sat), `-atprecharge`,
  `-freshread`, motor recasts (`-hfswing`/`-rollsign`/`-mhatset`) — harness entry points + orphaned harness code;
  shared kernel bodies + shared-file orphaned methods preserved per the guard.
- **Kept by guard (flagged for jba):** `-tauavg` (feeds kept phase-2 diagnostics), `-noatprelease`/`ATP_RELEASE`
  (feeds the kept opt-in config-1 motor).
- **Renamed:** `-faithfulrelease` → `-forcecapdetach`; added `-detachcap <pN>` (default 12). Not promoted.
- **Reclassified:** `coltol`, `density` marked swept-parameter placeholders (not canonical); strict obligatory
  enforcement deferred to Stage 3.
- **Acceptance:** canonical default **byte-identical before/after on CPU and GPU** at 800 + 6000 steps; legacy +
  `-segimplicit` + `-forcecapdetach` + a diagnostic all verified working. **No BAIL.**

**Did deleting the dead paths leave the canonical default byte-identical on both runners?** **YES.**
**Canonical's honest steady-window glide?** **≈ 2.83 µm/s (2.825 ± 0.054, per-bound ~0.85), at Vmax of the
skeletal band (~1.5–4, Vmax ~2.9).**

**Stage 3 awaits scoping:** the diagnostic-own-scene policy (the 8 builders that bypass `buildScene`), plus the
deferred strict `density`/`coltol` obligatory-input handling and the optional cosmetic sweep of the orphaned
shared-file methods + the `-tauavg`/`ATP_RELEASE` disposition (both currently guard-retained).
