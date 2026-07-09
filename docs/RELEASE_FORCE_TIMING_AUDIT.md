# Release-force TIMING audit: v2 (SoftBox) vs ACTIVE BoA (`~/Code/BoA`)

> **UPDATE 2026-07-03 — the candidate this audit named was EMPIRICALLY REFUTED by the `-freshread` A/B.**
> This audit correctly found a real timing *difference* (v2 default reads the catch load 1-step STALE; active BoA
> reads it FRESH) and flagged it as the *leading candidate* for the dense-regime capture-radius sign split. The
> follow-on A/B (`FRESHREAD_AB_FINDINGS.md`) tested it directly: making v2 read FRESH does **not** move v2 toward
> BoA's rising-net shape — it collapses v2's net glide *harder* (net −3.02→−0.51 @6 nm, reverses @8 nm) by deepening
> the co-bound tug-of-war. **⇒ the stale release-force is NOT the cause of the split** (the residual is the Jacobi
> seg-gather co-bound load-sharing, which release currency does not touch). The audit's own effect-size caveat
> (small per-step lag at dt=1e-5) was prescient. The timing finding below is still accurate; only the *causal
> hypothesis* is retracted.



**Date:** 2026-07-03. **Read-only code audit — no code changed, no runs.** Reference is **active BoA**
(`~/Code/BoA`, the −3.96 producer), NOT frozen `BoA-v1ref` (which carries the old head-swing motor and is not the
current motor). Neither active code is asserted correct — this finds *whether* the release-force timing differs
and *how*; which timing is physically right is a separate planner call.

---

## PLAIN ANSWER — does v2's release-force timing match active BoA's?

**NO. They differ, and it is exactly the hypothesized fresh-vs-stale split.**

- **Active BoA** (both runners): the catch-slip / break-cap read the cross-bridge load **FRESH — this step's
  force, the same evaluation that moves the bodies (0-step lag).**
- **v2 DEFAULT** (`stepOrig`, both runners): the catch-slip / break-cap read the load **STALE — `forceDotFil`
  registered at the END of the PREVIOUS step, one full integrate behind the force moving the bodies this step
  (1-step lag).**

The candidate fix is **already implemented and flag-gated in v2** (`-freshread` / `stepFresh`) — it is the exact
analog of the fix BoA already applied to its own GPU path (the "2026-06-04 release-read reconciliation"). It is
just **not the default**.

**Important refinement to the prior scheme-read** (`IMPLICIT_XB_CAPTURE_RADIUS_FINDINGS.md`): the difference is
**NOT** Jacobi-vs-Gauss–Seidel *co-bound-neighbor within-step motion*. **Both codes are Jacobi at the position
level** (accumulate every force from start-of-step positions, then integrate all bodies at once); in neither code
does a co-bound head's catch see a neighbor's *within-step* motion. The genuine, isolable difference is the
**release-force LAG** (BoA 0-step / v2-default 1-step), not sequential per-object position updates. BoA does **not**
do a sequential per-object position update.

---

## v2 (SoftBox) — the trace

**File:** `softbox/GlidingHarness.java` `stepOrig` (the DEFAULT step; `FRESH_READ=false`), and
`softbox/NucleotideCycleSystem.java` `catchSlipRelease`.

### Where the catch's `forceDotFil` / `forceMag` are SAMPLED
- The catch reads `mot.forceDotFil` / `mot.forceMag` in `NucleotideCycleSystem.catchSlipRelease`
  (`NucleotideCycleSystem.java:193` for `forceDotFil`, `:186` for the break-cap `forceMag`).
- Those buffers are **written by `CrossBridgeSystem.registerForceDot`** — and in `stepOrig` that write is the
  **last motor task of the step** (`GlidingHarness.java:107`), reading `sc.bondData` produced by
  `CrossBridgeSystem.bondForces` at `GlidingHarness.java:84` **from start-of-step positions** (before the motor
  integrate at `:96` and the filament integrate at `:119`).
- The catch runs at the **top of the next step** (`GlidingHarness.java:45`), **before** this step's `bondForces`
  (`:84`) recomputes the force that will actually move the bodies.

### Step ordering (`stepOrig`, default), motor arm:
1. `publishHead`, `reach` (`:31–32`) — no body motion.
2. **`catchSlipRelease`** (`:45`) — **reads `forceDotFil`/`forceMag` written at `:107` of the PREVIOUS step.**
3. `bindNearest` (`:51`), `cycle` (`:66`).
4. motor forces: zero/brownian/joints/anchor → **`bondForces` (`:84`)** writes `sc.bondData` from start-of-step
   positions.
5. `applyHeadForce` (`:90`), `directedSwing` (`:92`).
6. **`integrate` motor (`:96`)** — bodies move.
7. `deriveMot` (`:98`).
8. **`registerForceDot` (`:107`)** — writes `forceDotFil`/`forceMag` from the `sc.bondData` of THIS step (step-N
   start positions). ← consumed by the catch at the TOP of step N+1.
9. filament forces + CSR seg-gather (`:110–117`) → **`integrate` filament (`:119`)**.

### Fresh vs stale — v2: **STALE (1-step lag).**
`forceDotFil` is a persistent buffer written at `:107` (end of step N, from step-N start positions) and read at
`:45` (start of step N+1). Between the write and the read, step N's two integrates (`:96`, `:119`) have moved the
bodies. So when the catch fires, the force it reads reflects positions **one full integrate old** relative to the
current bodies — and it is a **separate, earlier evaluation** than the `bondForces` (`:84`) that moves the bodies
this step. Confirmed: it is not the same evaluation used to integrate this step; it is the prior step's registered
value. GPU mirrors this exactly (`buildPlan` default branch: `release` task at `:239` precedes the `bond`/`register`
tasks at `:269`/`:288`), so v2 CPU≡GPU is bit-identical on the staleness — this is **scheme, not runner**.

### Co-bound within-step neighbor motion (v2)? **NO.**
`bondForces` (`:84`) evaluates every bound head's cross-bridge force once from start-of-step head+filament
positions and writes both reactions into `bondData`; heads integrate; then `segGather` (`:117`) sums those same
start-of-step seg-side forces. No head sees another's within-step update (single-evaluation forward-Euler / Jacobi).

### v2 already has the fresh-read reorder (`-freshread` / `stepFresh`, `GlidingHarness.java:128–165`)
`stepFresh` computes `bondForces` + `segGather` + **`registerForceDot` (`:154`) BEFORE `catchSlipRelease` (`:155`)**,
so the catch reads **this step's** load; integration moves to the end (`:160`, `:163`). Forces still use
start-of-step state (forward-Euler unchanged, comment `:124–127`) — **the ONLY thing that changes is the catch's
force currency: 1-step-stale → this-step-fresh.** This is the isolated candidate fix, already wired on both runners
(GPU `FRESH_READ` branch `buildPlan:199–227`).

---

## Active BoA (`~/Code/BoA`) — the trace

**Files:** `boxOfActin/MyoFilLink.java` (`step`, `addForces`, `ckRelease`), `boxOfActin/BoxOfActin.java`
(the main loop), `boxOfActin/GPUMoveThing.java` (`bridgeMotorForceWriteback`).

### CPU path — where the catch's `forceDotFil` / `forceMag` are SAMPLED
`MyoFilLink.step()` (`MyoFilLink.java:152`), for each bound (non-device) motor, calls **in order, in the SAME
step**:
- `addForces()` (`:187`) — computes the cross-bridge spring force `F`, applies it via `incForceSum` to motor
  (`:252`) and seg (`:272`), **and writes** `forceMag = (dist−standoff)·myoSpring` (`:249`) and
  `forceDotFil = thisStepDot = Dot(F, seg.uVec)` (`:256`, `:267`) — from the **current (start-of-step) pose**
  (`myMotor.getCoordX()`… `:241`).
- `alignUVecTorque()` / `alignYVecTorque()` (`:188–189`) — torques.
- **`ckRelease()` (`:193`)** — reads `forceMag` (break-cap, `:482`) and `forceDotFil` (Guo–Guilford catch,
  `:495–497`) — the values `addForces` **just wrote this step**, from the same evaluation applied to the bodies.

The value the catch reads is **not** a cross-step cache: `addForces` sets `forceDotFil = thisStepDot` (`:267`)
where `F` is the very force `incForceSum`'d into the integration accumulator. Confirmed by the code comment
`MyoFilLink.java:166–167`: *"The CPU path below keeps ckRelease here because addForces has just written fresh
forces."* (There is a diagnostic env flag `DIAG_RELEASE_LAG`, `:257–264`, default OFF, that deliberately makes
ckRelease read `prevForceDotFil` to *mimic* the device's structural lag — off in production; noted for completeness.)

### CPU step ordering (BoxOfActin main loop)
Position integration is **deferred to a single `moveThings` pass** — BoA is **Jacobi at the position level**, not
sequential-per-object:
1. force waves — `runForceWave(myoJoints1)` (`BoxOfActin.java:1483`), `runForceWave(myoJoints2)` (`:1489`), then
   `startAllThreadSets(Env.stepStart)` (`:1498`) which runs every `Thing.step()` — i.e. `MyoFilLink.addForces` +
   `ckRelease` — accumulating into per-thread force slots. **All reads are start-of-step positions (nothing has
   integrated yet).**
2. `gatherForces` (`:1507`) — sum per-thread slots into the canonical SoA `forceSum`.
3. **`moveThings()` (`:1564`)** — integrate **all** things at once.

So `ckRelease` fires in phase (1), reading the force `addForces` computed in the same phase from start-of-step
positions — the **same** force phase (3) then integrates. **Fresh, 0-step lag.**

### Co-bound within-step neighbor motion (BoA)? **NO** (same as v2).
Because positions integrate only in `moveThings` (`:1564`), a co-bound head's `addForces` does **not** see a
neighbor's within-step motion either. Both codes are Jacobi at the position level. The BoA advantage is purely
**currency**: BoA's catch reads *this* step's force (every neighbor's start-of-THIS-step contribution), whereas
v2-default's catch reads *last* step's force (every neighbor's start-of-LAST-step contribution).

### GPU path — the device kernel computes F8/F9/F10 during `moveThings`; `bridgeMotorForceWriteback`
(`GPUMoveThing.java:6179`) drains `motorWriteback` into `link.forceMag`/`link.forceDotFil` (`:6195–6196`) and then
**immediately calls `link.ckRelease()` (`:6216`)** — same step, right after the fresh forces are written. Code
comment `:6210–6215`: *"invoke it here so the step-N release decision reads the step-N forces just written above.
Pre-fix it ran in the prior step phase against the moveThings(N-1) writeback (**1-step stale**)."*
- **Corroboration:** BoA's GPU path was itself **1-step-stale** — structurally identical to v2's `stepOrig` — until
  the 2026-06-04 "release-read reconciliation" moved `ckRelease` to fire immediately after the writeback. BoA's
  developers explicitly identified the 1-step-stale release-read as a defect and fixed it to read fresh. **v2's
  default is the pre-fix structure; v2's `-freshread` is the fix.** (One caveat in that same comment, `:7044–7047`:
  BoA judged the lag "harmless at dt=1e-4s… forces don't change measurably between consecutive ~100µs steps" — see
  the effect-size note below.)

---

## Two-way comparison

| aspect | active BoA (CPU + post-fix GPU) | v2 default (`stepOrig`, CPU+GPU) |
|---|---|---|
| position integration scheme | Jacobi (force waves → gather → one `moveThings`) | Jacobi (forces → integrate) |
| co-bound head sees neighbor's *within-step* motion | NO | NO |
| where the catch's `forceDotFil`/`forceMag` come from | `addForces`/device kernel, **this step** (same eval as integration) | `registerForceDot` at **end of previous step** (separate, earlier eval) |
| **release-force lag** | **0 steps (FRESH)** | **1 step (STALE)** |
| catch reads the same force that moves the bodies? | **YES** | **NO** (bodies move on `bondForces`@:84; catch reads prior `registerForceDot`) |
| CPU≡GPU on this timing | fresh on both (GPU reconciled 2026-06-04) | stale on both (bit-identical) |
| fresh-read available? | it's the production path | yes but OFF by default (`-freshread`/`stepFresh`) |

catch-slip **parameters** are already known bit-identical (`PHASE2_RELEASE_RECONCILE_FINDINGS.md`): kOff 100/s,
αCatch 0.92, αSlip 0.08, xCatch 2.5 nm, xSlip 0.4 nm, kT = Boltz·tempK, break-cap 12 pN. This audit changes none of
that — it isolates **when** the force fed to those identical formulas is sampled.

---

## FORK VERDICT — **they differ: BoA-fresh / v2-stale.**

Per the task's fork: **active BoA reads FRESH, v2 default reads STALE ⇒ the release-force timing DIFFERS, and it is
the leading candidate for the dense-regime co-bound sign split.** State plainly: the split is a **1-step
release-force lag** in v2's default that active BoA does not have (and that BoA explicitly removed from its own GPU
path). It is **not** a Jacobi-vs-Gauss–Seidel neighbor-motion difference — both codes are position-Jacobi; the
difference is the catch's force *currency*.

**Honest caveat on effect size (does NOT change the verdict, bounds its likely magnitude).** BoA judged the same
1-step lag "harmless at dt=1e-4s" (`GPUMoveThing.java:7046`). v2 runs at **dt=1e-5** (10× smaller), so the per-step
change in `forceDotFil` for an *isolated* head is even smaller — consistent with the observed *identical single-head
behavior*. The split appears only in the **dense** regime, where a segment's many co-bound heads can shift a given
head's load appreciably within one step even at small dt (the tug-of-war). Whether the 1-step lag is *quantitatively*
enough to produce the observed capture-radius sign split is an **empirical** question this code-read cannot settle —
but it is cheap to settle, because the reorder already exists (`-freshread`). Recommended (planner decision, NOT run
here): the `stepOrig` vs `stepFresh` A/B at the split's capture radii / density, reading per-bound drift + avgBound.
If the split closes under `-freshread`, it is confirmed the stale release-force; if not, the residual is in the
seg-gather load-sharing proper and the engagement-matched sweep is the next cut.

---

## Candidate-fix scope + compatibility (the fix already exists)

**Scope:** sample the catch load **post-force, same step** — reorder so `bondForces` → `segGather` →
`registerForceDot` run **before** `catchSlipRelease`, and move integration to the end of the step. This is **exactly
`stepFresh` / the `buildPlan` `FRESH_READ` branch**, already implemented for both runners
(`GlidingHarness.java:128–165`, `:199–227`). It changes **only** the catch's force currency; it does **not** change
the integration scheme (forces still start-of-step forward-Euler, `:124–127`), matching active BoA's
`addForces→ckRelease` order (and BoA's GPU `writeback→ckRelease` reconciliation).

**Compatibility — clean on both counts:**
- **Race-free CSR gather:** preserved. `stepFresh` runs the identical `csrHistogram/csrScan/csrScatter/segGather`
  over `bondData` (`:149–152`), just earlier in the step; the gather uses the **pre-release bound set** so Newton's
  3rd law holds (comment `:145`). No atomics, no `KernelContext` — the CSR-inverse template is untouched.
- **`-cpu` parity:** preserved by construction. Release RNG is wang-hash-keyed on `(motor, step, seed)`
  (`NucleotideCycleSystem.java:195`), so the draw is **order-independent** — reordering release relative to force
  yields identical draws ⇒ a clean A/B and bit-identical CPU≡GPU. The `FRESH_READ` GPU TaskGraph mirrors the CPU
  `stepFresh` task-for-task.

Making `-freshread` the default is therefore a low-risk flag flip, but it **re-baselines** the promoted default's
glide/avgBound and interacts with the accepted 4b-iv parallel-scheme residual — so it should be its own task
(measure the A/B first), not a side-effect.

---

## JOURNAL line
```
## 2026-07-03 — RELEASE-FORCE TIMING AUDIT (v2 vs active BoA): they DIFFER — BoA reads FRESH, v2-default reads STALE (1-step lag). Code-read only.
```
The catch-slip/break-cap read the cross-bridge load at different points: active BoA (CPU `MyoFilLink.step`:
addForces→ckRelease same step, `:187`→`:193`; GPU reconciled `bridgeMotorForceWriteback`→`ckRelease`,
`GPUMoveThing.java:6216`) samples **this step's** force = the same eval that integrates ⇒ **0-step lag, FRESH**.
v2 default (`GlidingHarness.stepOrig`: `catchSlipRelease` at `:45` reads `forceDotFil` written by
`registerForceDot` at `:107` of the PREVIOUS step) samples a force one full integrate behind the force moving the
bodies ⇒ **1-step lag, STALE**. Both codes are position-Jacobi (no within-step neighbor motion) — the difference is
release-force *currency*, NOT Gauss–Seidel neighbor updates (refines `IMPLICIT_XB_CAPTURE_RADIUS_FINDINGS.md`).
The candidate fix already exists (`-freshread`/`stepFresh`, both runners; == BoA's own GPU reconciliation) and is
compatible with the race-free CSR gather + `-cpu` bit-identity; the `stepOrig`↔`stepFresh` A/B at the split's
radii/density is the cheap next cut (not run). Report: `RELEASE_FORCE_TIMING_AUDIT.md`.
```
