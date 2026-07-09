# Phase-2 — reconcile the RELEASE pathway: v2 (SoftBox) ↔ active BoA (the −3.96 / −3.0 glide gap)

**Date:** 2026-07-01. Measurement + code audit only — **no code changed, no flag added, no long runs**.
`BoA-v1ref` untouched (and it is NOT the reference here — **active BoA at `~/Code/BoA/`** is, since active
BoA produced the −3.96 µm/s glide). Reference for the v2 side: the promoted default motor
(`PHASE2_DEFAULT_PROMOTION.md`, `GlidingHarness` with no flags).

## TL;DR — the release hypothesis is REFUTED; the task BAIL clause is met

The task hypothesized **v2 has a nucleotide-driven detachment that active BoA lacks**, starving v2's duty
(avgBound ~14 vs BoA ~21) and thereby its glide (−3.0 vs −3.96). **This is false.** v2's DEFAULT motor release
is **ALREADY catch-slip-on-`forceDotFil` only** — its nucleotide cycle drives **cocking, never detachment** —
and its catch-slip parameters are **bit-identical** to active BoA's. Per the task's explicit bail clause:

> *"If documenting v2's release reveals it's ALREADY catch-slip-only (no nucleotide-driven detachment), STOP
> and report — then the gap isn't release and STEP 1 is moot (go straight to the neck-angle confound)."*

⇒ **STEP 1 (`-v1release`), STEP 2 (the three-way d1000 assay), and STEP 3 (bound-in-ATP cost) are all MOOT
for the promoted default.** No `-v1release` harness was added; no d1000 runs were launched.

The gap is **NOT release**. Two genuine v2↔BoA differences remain, and **only geometry can explain the
direction of the duty gap**:
1. **Neck stroke angle — BoA 70° vs v2 60°** (the flagged confound; leading suspect for both the speed and
   the duty gap — a larger stroke shifts the cross-bridge force distribution and the per-head step).
2. **Break-force cap default — BoA 12 pN ON vs v2 OFF.** A real release-path difference, but it pushes duty
   the **WRONG way** (BoA detaches *more*, yet has *higher* avgBound) ⇒ it cannot be the source of v2's lower
   duty. Rarely fires anyway (catch peak ~6 pN ≪ 12 pN). Flagged, not causal.

Plus two measurement caveats (below): BoA's −3.96 is a **CPU** number (its f̂-directed swing is CPU-only;
its GPU still runs the legacy F9 motor), v2's −3.0 is a **GPU** number; and v2 carries the known 4b-iv
**parallel-scheme residual** (~0.87× one-step-stale SoA forces).

---

## STEP 0 — the two release pathways, path by path (measured, nothing changed)

### v2 DEFAULT motor (no flags: `SPHEREHEAD + AXLOCK + DIRSWING`; NOT config1/perphead/canonical/legacy/lymntaylor/atprecharge)

Dispatch confirmed in `GlidingHarness.stepOrig`/`buildPlan`:
- **Release** → `NucleotideCycleSystem.catchSlipRelease` (`GlidingHarness.java:424`, `:618`). `TAU_AVG=0`
  (instantaneous F), `FAITHFUL_RELEASE=false` (cap off).
- **Cycle** → `NucleotideCycleSystem.cycle` (`:445`, `:635`) — the **plain 4-state machine**. It writes only
  `nucleotideState`; it **never writes `boundSeg`** (`NucleotideCycleSystem.java:33-69`). `cycleAtpDetach`
  (the ATP-binding-detaches path) is gated `CONFIG1 && ATP_RELEASE` (`:442`) — the default is not CONFIG1.
- **Stroke** → `CrossBridgeSystem.directedSwing` (`:471`, `:652`) reads `nucleotideState` **only** to pick the
  rest angle (uncocked 0° / cocked 60°) and writes **torque, never `boundSeg`** (verified in the kernel body).

**⇒ the ONLY detachment path in the v2 default is `catchSlipRelease` on `forceDotFil`. The nucleotide cycle
is cocking-only.**

catch-slip params (`MotorStore.setKineticParams`, `MotorStore.java:249-266`):

| quantity | v2 value | source |
|---|---|---|
| rate | `kOff·(αCatch·e^(−F·xCatch/kT) + αSlip·e^(+F·xSlip/kT))` | `NucleotideCycleSystem.java:194` |
| F read | `forceDotFil` — the F8 tip-spring cross-bridge load · filament axis (instantaneous) | `:193` |
| kOff | 100 /s | `:249` |
| αCatch | 0.92 | `:250` |
| αSlip | 0.08 | `:251` |
| xCatch | 2.5 nm | `:252` |
| xSlip | 0.4 nm | `:253` |
| kT | 4.116e-21 J (Boltz 1.380662e-23 × 298.15 K) | `Constants.java:25-27` |
| break-force cap | 12 pN threshold, **capOn = OFF** (default) | `:266-267`, `setFaithfulRelease(false)` `GlidingHarness.java:346` |
| refractory | `ceil(myoRebindTime/dt)` = **1 step** (myoRebindTime 1e-5 s, dt 1e-5 s) | `:262`, `MotorStore.java:127` |
| blockProb | 1.0 (always enter the 1-step refractory) | `:270` |
| neck stroke | f̂-directed, θ 0°→**60°** | `swingParams` `GlidingHarness.java:388` |

### Active BoA (`~/Code/BoA/`, the −3.96 producer; f̂-directed neck-stroke default)

- **Release** → `MyoFilLink.ckRelease` (`MyoFilLink.java:440-483`):
  `P = kOff·dt·(αCatch·e^(−forceDotFil·xCatch/kT) + αSlip·e^(+forceDotFil·xSlip/kT))`
  (`MyoFilLink.java:469-471`), `forceDotFil` = cross-bridge spring force · filament axis (`:235`).
- **Break-force cap** → `if (forceMag > myosinBreakForce·1e-12) { release(); return; }`
  (`MyoFilLink.java:456-462`) — **ACTIVE by default** (not commented out), threshold **12.0 pN**
  (`Env.java:1259`).
- **Nucleotide cycle** → `MyoMotor.biochemStep` (`MyoMotor.java:228-283`) NONE→ATP→ADPPi→ADP. **No transition
  calls `release()`.** `dissociateADP` (`:277-282`) sets state NONE (load-gated, `forceDotFilTrack.average>0`
  returns) but the head **stays attached** until `ckRelease`/break-cap. Cycle ⇒ cocking only
  (`isCocked()=!isADPPi`, `:284-286`).
- **Neck stroke** → f̂-directed (`applyLeverMotorJointTorquePolarity`), **θ 0°→70°**, head fixed ⊥ (90°, no
  head swing) (`BoxOfActin.java:562-569`, `cockedLever_MotorAngle=70.0`).
- **Refractory** → `myoRebindTime = 1e-5 s` (`Env.java:1292`), enforced in `MyoMotor.java:488`.

### The DIFF table

| release path | v2 default | active BoA | match? |
|---|---|---|---|
| catch-slip formula | Guo–Guilford `kOff·(αC·e^(−F·xC/kT)+αS·e^(+F·xS/kT))` | **identical** | ✅ |
| F read | `forceDotFil` (F8 tip-spring load · f̂) | `forceDotFil` (x-bridge force · f̂) | ✅ same quantity |
| kOff | 100 /s | 100 /s | ✅ |
| αCatch / αSlip | 0.92 / 0.08 | 0.92 / 0.08 | ✅ |
| xCatch / xSlip | 2.5 nm / 0.4 nm | 2.5 nm / 0.4 nm | ✅ |
| kT | 4.116e-21 J | `Boltz·tempK` (same port) | ✅ |
| refractory | 1e-5 s (1 step) | 1e-5 s | ✅ |
| **nucleotide→detach** | **NONE** (cycle = cocking only) | **NONE** (cycle = cocking only) | ✅ **BOTH catch-slip-only** |
| **break-force cap** | 12 pN, **OFF** by default | 12 pN, **ON** by default | ❌ **DIFFERS** (wrong-direction; see above) |
| **neck stroke angle** | **60°** | **70°** | ❌ **DIFFERS** (the flagged confound) |

**The hypothesized divergence (v2 nucleotide-driven detachment) does not exist.** Both motors detach solely by
the force-dependent catch-slip on the along-filament cross-bridge load, with byte-identical constants, and both
cycles are cocking-only.

---

## STEP 1 / STEP 2 / STEP 3 — MOOT (bail invoked)

- **STEP 1 (`-v1release`)** — not built. Mapping v2's release to BoA's would mean: (a) catch-slip params —
  **already identical**, nothing to map; (b) break-force cap ON — **already reachable via the existing
  `-faithfulrelease` flag** (`MotorStore.setFaithfulRelease`, `kinParams[12]`), no new flag needed; (c) disable
  nucleotide-driven detachment — **nothing to disable**, the default has none. So `-v1release` reduces to
  `-faithfulrelease`, and (b) pushes duty the wrong way. No decisive experiment remains.
- **STEP 2 (three-way d1000 assay)** — not run. With the release variable already equal, the −3.96/−3.0
  comparison is a **geometry** comparison, not a release one; the standing default number (−3.02 / avgBound
  14.8, `PHASE2_DEFAULT_PROMOTION.md` Check D) already stands.
- **STEP 3 (bound-in-ATP cost)** — moot for the promoted default. The bound-in-ATP pathology and the
  cycle-driven ATP-detachment (`cycleAtpDetach`) it motivated live on the **CONFIG1 / perp-head lineage**, not
  the sphere-head default. The default `cycle()` does allow a bound head to sit in the ATP state, but there
  that state is simply "cocked" (`isCocked = state≠ADPPi`) and strokes normally — it is not a non-physical
  bound-in-ATP that any release was built to prevent. There is no cycle-release in the default to trade off.

---

## The residual gap — where −3.96 vs −3.0 (and avgBound 21 vs 14) actually comes from

Release is excluded. The remaining differences, in likely order of contribution:

1. **Neck stroke 70° (BoA) vs 60° (v2) — the leading suspect.** A larger stroke changes both the per-head
   forward step and the cross-bridge force trajectory that feeds `forceDotFil`, which sets the catch-slip
   lifetime. A stroke that drives the head further into the barbed-ward (catch-stabilized) force regime
   lengthens the bound dwell — consistent with BoA's **higher** avgBound and **faster** glide. **This is the
   one variable to change next (one at a time).** Not changed in this task.
2. **CPU (BoA) vs GPU (v2) measurement.** BoA's −3.96 f̂-directed swing is **CPU-only** —
   `applyLeverMotorJointTorquePolarity` is not on BoA's GPU joint kernel (BoA GPU still runs the legacy F9
   motor; see the 2026-07-01 JOURNAL "BoA GPU joint kernel is still polarity-blind" open item). v2's −3.0 is a
   **GPU** number. Not apples-to-apples on the runner.
3. **The known 4b-iv parallel-scheme residual (~0.87×).** v2's device path uses one-step-stale SoA forces
   (Jacobi-like) vs a sequential fresh-force update (Gauss–Seidel-like); this is a documented, accepted
   ~13% net-glide reducer on chaotic many-body trajectories (`GLIDING_4biv_RESIDUAL_DOSSIER.md`). It applies
   to any v2-GPU-vs-sequential comparison and partly accounts for a −3.96→−3.0 shift.
4. **Break-force cap default (12 pN ON in BoA / OFF in v2).** A real release-setting difference but
   direction-wrong for the duty gap and rarely triggered (peak catch force ~6 pN). If a like-for-like check is
   wanted, `-faithfulrelease` turns it on in v2 — expect it to *lower* duty slightly, not raise it.

## Verdict

**The −3.96 / −3.0 gap is not a release-pathway difference.** v2's default release is already active BoA's
release — catch-slip-on-`forceDotFil`, cocking-only cycle, bit-identical Guo–Guilford constants. The one
release-setting difference (break-cap default) is small and wrong-signed. The gap is **geometry + measurement**:
foremost the **neck stroke 70° vs 60°** (change this next, alone), compounded by the CPU-vs-GPU runner
difference and the accepted 4b-iv parallel-scheme residual.

## Run (for the record — nothing here was executed this task)
```
GlidingHarness -gpu -full -grid -density 1000 150000                 # the promoted default (−3.02), for reference
GlidingHarness -gpu -full -grid -density 1000 -faithfulrelease 150000 # the break-cap-ON bracket (== the only real
                                                                      #   release delta vs BoA; expect duty to DROP)
```
