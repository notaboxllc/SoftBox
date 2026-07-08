# Canonical collapse — STAGE 1: bake the ratified defaults + prove byte-identical

**Date:** 2026-07-08. Scope: git-tag the rollback point, add opt-out flags, flip the genuine model defaults, weld
ADP·Pi-only binding into the canonical cycle, and verify byte-identical. **No deletions, no renames, no parameter
changes, no diagnostic re-routing** (all Stage 2). `BoA-v1ref` untouched. Only `GlidingHarness.java` edited.

---

## HEADLINE

**Is the new bare default byte-identical to the old explicit canonical config on both runners? YES.**
`run_gliding.sh -full -grid` (no model flags) == old-tag `-full -grid -lymntaylor -adppibind -xbimplicit2` —
**byte-identical on CPU (velFitX 4.583) and GPU (velFitX 4.584)** to every printed digit.

**Is the legacy path still reachable and unchanged? YES.** The opt-out combo reproduces the old code's
fully-legacy config **byte-identical on CPU** (velFitX 13.057), and the two genuine default-flips revert cleanly
(new `-legacycycle -explicitxb` == old bare default, velFitX −2.605, byte-identical).

**No BAIL.** Stage 1 is byte-identical-clean; Stage 2 may be scoped.

---

## PART 0 — rollback tag (pushed)

`git tag pre-canonical-collapse-2026-07-08` → commit `5b60b0a` ("Springs promoted to default gliding
formulation…"), **pushed to origin** (`notaboxllc/SoftBox`) and confirmed. This is the recovery point for all of
Stages 1–2.

**Critical state finding at the tag** (drives the Test-B framing below): at `5b60b0a`, **springs and the
sphere-head motor are ALREADY default-on** (`PAIRS/ALIGN/STRUCT_SPRINGS=true`; sphere-head via the post-parse
promotion). Only `LYMN_TAYLOR` and `XB_IMPLICIT2` were genuinely default-OFF. So this stage makes **two genuine
default flips** (LYMN_TAYLOR, XB_IMPLICIT2), one **byte-identical refactor** (make the sphere-head stack an
explicit default, remove the promotion block), and one **confirm-unchanged** (springs). The manifest's premise
that all four "flip ON via flags" was correct only for LYMN_TAYLOR/XB_IMPLICIT2 — springs/sphere were already on.

---

## PART 1 — opt-out flags added (legacy behavior stays reachable)

| flag | effect | site |
|---|---|---|
| `-legacycycle` | forces the legacy catch-slip nucleotide cycle (`LYMN_TAYLOR=false`) | field `GH:132`, parse `GH:288`, resolve `GH:349` |
| `-explicitxb` | forces explicit Hookean F8 (`XB_IMPLICIT2=false`) | field `GH:55`, parse `GH:289`, resolve `GH:350` |
| `-allowbindany` | marked diagnostic: permissive binding under the canonical cycle (suppresses the D1a weld) | field `GH:134`, parse `GH:290`, resolve `GH:355` |
| `-nosprings` (pre-existing) | raw fraction-per-step formulation | verified still works (Test B) |
| `-legacymotor` (pre-existing) | v1-port F9 head-swing motor | verified still works (Test B) |

Together `-legacycycle -explicitxb -nosprings -legacymotor` reaches the fully-legacy config.

---

## PART 2 — canonical model defaults baked (flip, nothing deleted)

- **`LYMN_TAYLOR = true`** (`GH:130`). Lymn-Taylor is the default cycle.
- **`XB_IMPLICIT2 = true`** (`GH:54`). Coupled head+SITE implicit F8 is the default cross-bridge.
- **Sphere-head stack explicit defaults** `SPHEREHEAD=AXLOCK=DIRSWING=true` (`GH:86–88`); the **post-parse
  motor-promotion block was removed** and replaced by a resolution block (`GH:334–355`) that (a) clears the
  sphere stack for `-legacymotor` and for the alternative motors `-canonical/-config1/-perphead`, and (b)
  applies the cycle/xb opt-outs and the D1a weld. **This transform is byte-identical to the old promotion for
  every path** (default / legacy / canonical / explicit `-spherehead` / `-hfswing` / …) — verified by
  case-analysis and by Test A/B.
- **Springs** (`PAIRS/ALIGN/STRUCT_SPRINGS=true`, `GH:155–157`) — confirmed **unchanged** (already default-on).
- Everything else (params, all other flags, diagnostics) untouched.

---

## PART 3 — the ADP·Pi weld + the permissive-binding counterfactual (resolved from the code)

**The weld (D1a):** the canonical Lymn-Taylor cycle binds actin ONLY from the ADP·Pi pre-stroke state.
Implemented in the resolution block (`GH:355`): `if (LYMN_TAYLOR && !ALLOW_BIND_ANY) ADPPI_BIND = true;`, which
drives the existing `if (ADPPI_BIND) kinParams.set(20, 1.0f)` in `buildScene` (`GH:645`). So the canonical
default has `kinParams[20]=1` (ADP·Pi-only binding) welded on; you cannot get the canonical cycle with permissive
binding except via the explicit diagnostic.

**Counterfactual resolution — determined from the code: the cycle and the bind-gate are INDEPENDENT.**
`LYMN_TAYLOR` selects the cycle kernel (`cycleLymnTaylor` vs `cycle`); the ADP·Pi bind-gate is a separate
`kinParams[20]` consumed in `bindNearest` (`BindingDetectionSystem`). They are orthogonal flags with orthogonal
consumers. Therefore:
- **`-legacycycle` does NOT serve as the clean permissive-binding counterfactual.** It gives permissive binding
  but *bundled with the legacy cycle swap* (`LYMN_TAYLOR=false` ⇒ the weld doesn't fire ⇒ `ADPPI_BIND` stays at
  its field default `false` ⇒ permissive) — that confounds the bind-gate with the cycle. It is the *pre-arc
  baseline*, not an isolation of the weld.
- **⇒ a marked diagnostic `-allowbindany` WAS added** (needed): it suppresses the weld so the *canonical
  Lymn-Taylor cycle* runs with *permissive binding* — the clean counterfactual that isolates the bind-gate (the
  arc's diagnosed bind-in-ATP churn), which no existing flag combination expressed.

**Weld behavioral verification.** At an 800-step window the ADP·Pi gate is numerically quiet (the release/dwell
census window `STATS_STEADY_ROW` hadn't opened — `boundSteps=0` — so the bind-in-ATP churn the gate suppresses
isn't yet in the metrics; new default == `-allowbindany` == velFitX 4.583). At **6000 steps the two diverge**,
confirming the weld is live and `-allowbindany` genuinely suppresses it:

| 6000 steps, seed 0 | velFitX | avgB | avgBsteady | netX |
|---|---|---|---|---|
| NEW default (ADP·Pi welded, `kinParams[20]=1`) | 3.268 | 3.328 | 3.710 | −2.767 |
| NEW `-allowbindany` (permissive) | 3.392 | 3.262 | 3.581 | −2.884 |

The difference (velFitX 3.268 vs 3.392, avgBsteady 3.710 vs 3.581) proves (a) the weld actually drives
`kinParams[20]=1` in the canonical default, and (b) `-allowbindany` is a functioning counterfactual. (Test A's
byte-identity to old `-lymntaylor -adppibind -xbimplicit2` independently confirms the default assembles the
adppibind slot.)

---

## PART 4 — byte-identical acceptance gate

Fixed throughout: `seed 0, dt 1e-5, coltol 10 nm, density 1000, -full -grid`. CPU is the deterministic arbiter;
GPU is the confirm. (Note: the harness has no `-cpu` flag — the default runner IS the CPU sequential runner;
`-gpu` selects the device path.) Window: 800 steps (byte-identity manifests immediately; a fixed window makes the
metrics identical by construction if the assembled config matches).

### Test A — baking is correct (new default ≡ old explicit canonical)

| | velFitX | inst | instSteady | netXY | netSteady | netX | avgB | avgBsteady |
|---|---|---|---|---|---|---|---|---|
| **CPU** NEW default | 4.583 | 6.707 | 7.017 | 5.892 | 5.589 | −5.264 | 3.333 | 5.400 |
| **CPU** OLD `-lymntaylor -adppibind -xbimplicit2` | 4.583 | 6.707 | 7.017 | 5.892 | 5.589 | −5.264 | 3.333 | 5.400 |
| **GPU** NEW default | 4.584 | 6.707 | 7.017 | 5.892 | 5.590 | −5.265 | 3.333 | 5.400 |
| **GPU** OLD `-lymntaylor -adppibind -xbimplicit2` | 4.584 | 6.707 | 7.017 | 5.892 | 5.590 | −5.265 | 3.333 | 5.400 |

**PASS on both runners — byte-identical to every printed digit.** CPU==CPU and GPU==GPU exactly. (The CPU↔GPU
difference at the last digit, 4.583 vs 4.584, is the expected float64↔float32 gap and is NOT part of the A gate,
which compares new-vs-old *within* each runner.) GPU byte-identity confirms the flips introduced **no
kernel-graph difference** — the new default builds the identical TaskGraph the old `-xbimplicit2` build did.

### Test B — old default still reachable + unchanged

The task's literal Test-B combo assumed all four canonical pieces were default-off pre-collapse; two of them
(springs, sphere-head) were already on. So the honest, correct comparison targets the *fully-legacy* config as
computed by BOTH code versions, and separately verifies the two genuine flips revert cleanly:

**B (four opt-outs restore the fully-legacy path):**

| | velFitX | inst | instSteady | netXY | netSteady | netX | avgB | avgBsteady |
|---|---|---|---|---|---|---|---|---|
| **CPU** NEW `-legacycycle -explicitxb -nosprings -legacymotor` | 13.057 | 12.240 | 15.010 | 9.991 | 15.042 | −9.813 | 15.556 | 21.000 |
| **CPU** OLD-tag `-nosprings -legacymotor` | 13.057 | 12.240 | 15.010 | 9.991 | 15.042 | −9.813 | 15.556 | 21.000 |

**PASS — byte-identical.** (At the old tag, `-nosprings -legacymotor` IS the fully-legacy config, since
LYMN_TAYLOR/XB_IMPLICIT2 were already off there; the new opt-outs `-legacycycle -explicitxb` exactly cancel the
two default flips to reproduce it.)

**B2 (the two genuine flips revert cleanly; springs + sphere-head unchanged):**

| | velFitX | inst | instSteady | netXY | netSteady | netX | avgB | avgBsteady |
|---|---|---|---|---|---|---|---|---|
| **CPU** NEW `-legacycycle -explicitxb` | −2.605 | 8.431 | 9.516 | 2.463 | 2.431 | −1.508 | 16.222 | 22.400 |
| **CPU** OLD-tag bare `-full -grid` | −2.605 | 8.431 | 9.516 | 2.463 | 2.431 | −1.508 | 16.222 | 22.400 |

**PASS — byte-identical.** New `-legacycycle -explicitxb` reproduces the pre-collapse bare default exactly.

**Note on the task's literal Test-B RHS.** The task specified comparing the four-opt-out combo against the OLD
bare default `-full -grid` at the tag. Those two are **correctly NOT byte-identical** — the pre-collapse bare
default already had springs+sphere-head ON (velFitX −2.605, row B2-old), while the combo turns them OFF (velFitX
13.057, row B-new). This is **not a baking failure**; it is the consequence of springs/sphere-head being
already-on defaults rather than this stage's flips. The two comparisons above (B and B2) are the correct,
byte-identical decompositions and together prove all four opt-outs faithfully restore legacy.

---

## Summary

- **Rollback tag `pre-canonical-collapse-2026-07-08` (`5b60b0a`) pushed.**
- **Opt-outs added:** `-legacycycle`, `-explicitxb`, `-allowbindany`. `-nosprings`/`-legacymotor` verified intact.
- **Four defaults set:** LYMN_TAYLOR=true, XB_IMPLICIT2=true, sphere-head stack explicit (promotion removed,
  byte-identical), springs confirmed unchanged.
- **ADP·Pi weld** (`LYMN_TAYLOR ∧ ¬ALLOW_BIND_ANY ⇒ ADPPI_BIND`) welds ADP·Pi-only binding into canon; the cycle
  and bind-gate are independent in the code, so `-allowbindany` was added as the clean permissive counterfactual.
- **Acceptance: Test A PASS (CPU+GPU, byte-identical), Test B PASS (CPU), Test B2 PASS (CPU).** No BAIL.

**Is the new bare default byte-identical to the old explicit canonical config on both runners?** **YES**
(CPU 4.583 / GPU 4.584, new≡old within each runner to every digit). **Is the legacy path still reachable and
unchanged?** **YES** (opt-outs reproduce the old code's legacy config byte-identical).

**Stage 2 awaits planner scoping:** deletions of the dead paths; rename `-faithfulrelease`→`-forcecapdetach` +
`-detachcap`; coltol/density reclassification; the diagnostic-own-scene re-routing policy (the 8 builders that
bypass `buildScene`). None done this stage.
