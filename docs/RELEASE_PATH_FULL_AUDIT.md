# RELEASE-PATH FULL AUDIT — v2 (SoftBox) vs ACTIVE BoA, end to end

**Date:** 2026-07-03. **Read-only code audit — no code changed, no runs.** Reference is **active BoA**
(`~/Code/BoA`, the −3.96 µm/s producer), NOT frozen `BoA-v1ref` (old head-swing motor). v2 side = the promoted
DEFAULT gliding motor (no flags: SPHEREHEAD + AXLOCK + DIRSWING). Neither code is asserted correct — this
tabulates *what differs*; which behavior is right is a planner call. Every claim is a traced code path with
file:line, not inferred from parameter equality.

This extends two prior audits that each checked only a slice: `PHASE2_RELEASE_RECONCILE_FINDINGS.md` (catch-slip
PARAMETERS only, asserted "both cocking-only" without tracing the cycle) and `RELEASE_FORCE_TIMING_AUDIT.md`
(the catch-slip force CURRENCY only). Mandate: because one real release-adjacent divergence (the 1-step
release-force lag) is already proven, *every* other aspect must be verified, not assumed.

---

## PLAIN BOTTOM LINE

**The nucleotide cycle is bit-for-bit the same state machine with the same rates, the same every-step cadence,
the same load-gate, and the same cocking-only property in both codes. Beyond the already-known release-force
lag, the audit finds NO new *mechanism* or *parameter* divergence — but it finds THREE additional
CURRENCY/ORDERING divergences, all a direct consequence of v2's one structural choice (register the
cross-bridge load at the END of the step), plus two inert/known items:**

1. **[known] break-force cap default** — v2 OFF / BoA ON (12 pN). Wrong-direction for the duty gap; rarely fires.
2. **[known] catch-slip release-force currency** — v2 STALE (1-step lag) / BoA FRESH. *Refuted* as the split
   cause by the `-freshread` A/B (`FRESHREAD_AB_FINDINGS.md`).
3. **[NEW] cycle ADP→NONE load-gate currency** — v2 reads the 10-window `forceDotFil` average STALE / BoA FRESH.
   *Same structural cause as #2, a DISTINCT consumer* the timing audit never named.
4. **[NEW] stroke/cocking-state ordering** — v2 runs `cycle` BEFORE the stroke (stroke reads this-step
   post-cycle state); BoA runs `biochemStep` AFTER the stroke (stroke reads prior-step state). A 1-step
   cocking-state lag, in the OPPOSITE direction (v2 fresher).
5. **[NEW, minor] intra-step {release,bind} order** — BoA binds before it releases (a head can bind+release the
   same step); v2 releases before it binds (a fresh bond cannot release its bind-step).
6. **[NEW, inert] `inRigor` bypass** — BoA's `ckRelease` has `if(inRigor) return;`; set ONLY for ProteinNode
   myosins, never in the gliding assay. v2 has no rigor state. Inert for gliding.
7. **[known, deliberate] refractory mechanism** — same 1-step value; v2's clean `FREE_COOLDOWN` sentinel vs BoA's
   `bindTimer` static-global race (dossier Part 2). v2 deliberately does NOT inherit the race.

**Could any bear on the co-bound capture-radius split?** #3 and #4 flip together with EXACTLY the `-freshread`
reorder that was already A/B-tested and found to *deepen* the split, not close it. So no *new isolable* candidate
emerges: the residual remains the Jacobi seg-gather co-bound load-sharing (`FRESHREAD_AB_FINDINGS.md` §FORK
VERDICT), which none of these touch. Caveat: `-freshread` bundles #2+#3+#4; they have not been isolated
*individually*, only tested as a bundle (which moved glide the wrong way).

---

## A. THE NUCLEOTIDE CYCLE (state machine)

### A1 — states & transition graph — **SAME**

| | v2 | active BoA |
|---|---|---|
| encoding | `NUC_NONE=0, NUC_ATP=1, NUC_ADPPI=2, NUC_ADP=3` (`MotorStore.java:145`) | `NONE=0, ATP=1, ADPPi=2, ADP=3` (`MyoMotor.java:84-88`) |
| graph | NONE→ATP→ADPPi→ADP→NONE (`NucleotideCycleSystem.cycle:49-66`) | NONE→ATP→ADPPi→ADP→NONE (`biochemStep:230-247` switch) |
| direction | forward only, one transition/step | forward only, one transition/step |

v2 `cycle()` is the DEFAULT (dispatched `GlidingHarness.java:465`; the `cycleAtpDetach`/`cycleNoBoundAtp`/
`cycleLymnTaylor` variants are all flag-gated OFF). BoA `biochemStep` switches on `nucleotideState` and calls
`atpOnMyo`/`hydrolize`/`dissociatePi`/`dissociateADP`.

### A2 — per-transition rates — **SAME (all six)**

| transition | v2 (`MotorStore.setNucParams:374-381`) | BoA (`Env.java`) | match |
|---|---|---|---|
| NONE→ATP | `atpOn` = 2.0e4 /s | `atpOnMyo_init` = 2e4 (`:1329`) | ✅ |
| ATP→ADPPi (on) | `onATP` = 100 /s | `myoOnFilATP_ADPPi_init` = 100 (`:1332`) | ✅ |
| ATP→ADPPi (off) | `offATP` = 100 /s | `myoOffFilATP_ADPPi_init` = 100 (`:1341`) | ✅ |
| ADPPi→ADP (on) | `onPi` = 1.0e4 /s (power stroke) | `myoOnFilADPPi_ADP_init` = 1e4 (`:1335`) | ✅ |
| ADPPi→ADP (off) | `offPi` = 0 | `myoOffFilADPPi_ADP_init` = 0 (`:1344`) | ✅ |
| ADP→NONE (on/off) | `onADP`=`offADP` = 1.0e3 /s (load-gated) | `myoOnFilADP_None_init` = 1e3 (`:1338`) | ✅ |

BoA `dissociateADP` uses `myoOnFilADP_None` for both on- and off-filament (no branch); v2 uses `onADP=offADP=1e3`
— same effective value.

### A3 — rate application & cadence — **SAME**

Both fire each transition as a **per-step probability `rate·dt`** with **one uniform draw per motor per step**,
run **every step** (biochem cadence = 1 for the motor):

- v2: `if (u < atpOn*dt)` etc.; `u` from a wang-hash keyed `(m, step, seed)` salt `0x4E55`
  (`NucleotideCycleSystem.java:46-47`). `dt = nucParams[0]` = the caller's stepping dt = **DT=1e-5**
  (`GlidingHarness.java:32,349`), NOT a hardcoded `Constants.deltaT`.
- BoA: `if (rng.nextDouble() < atpOnMyo·Env.deltaT)` etc. (`MyoMotor.java:258,265,273,281`); `Env.deltaT` = **1e-5**.

**Cadence divergence RULED OUT (a candidate this audit specifically chased):** BoA has `biochemDeltaT=1e-3`
(`Env.java:111`) and `biochemCheckInt=biochemDeltaT/deltaT` (`Env.java:1627`), which gates the *actin/monomer/
crosslink* biochem (they multiply by `biochemDeltaT` explicitly — `Monomer.java:184`, `FilSegment.java:3059`).
But the **motor** cycle is NOT cadence-gated: `startAllThreadSets(Env.biochemStart)` is called
**unconditionally every step** (`BoxOfActin.java:1623`), `Thing.biochemStep()` runs for all Things with no gate
(`Thing.java:377-383`), and `MyoMotor.biochemStep`'s transitions use `Env.deltaT` (not `biochemDeltaT`). ⇒ the
motor cycle runs every step at `rate·deltaT` in BOTH codes. **No cadence/dt divergence.** (RNG *streams* differ —
v2 wang-hash vs BoA `rng.nextDouble()` — but both are order-independent per-motor draws; the scheme is the same.)

### A4 — load-gating of ADP→NONE — **SAME mechanism; differs only in CURRENCY (see #3/D11)**

Both gate ONLY the ADP→NONE transition, on the SAME quantity, window, sign, and threshold:

- v2 (`NucleotideCycleSystem.java:57-65`): sum the 10-slot `forceDotHist` ring, `avg *= 0.1`; roll ADP→NONE only
  `if (avg <= 0f)`. Ring is a boxcar (v1 `ValueTracker(10)`), written by `registerForceDot`
  (`CrossBridgeSystem.java:971-973`).
- BoA (`MyoMotor.dissociateADP:279-283`): `if (tipLink.forceDotFilTrack.averageVal() > 0) return;` then roll.
  `forceDotFilTrack = new ValueTracker(10)` (`MyoFilLink.java:48`), fed by `registerValue(thisStepDot)`
  (`:266`).

Force quantity = signed along-filament cross-bridge load `forceDotFil` (positive = barbed-ward = load-stabilizing
⇒ blocks dissociation). Window = 10-sample boxcar. Threshold = 0. **Sign, window, threshold all match.** No other
transition has a force gate in either code. (Minor, sign-irrelevant: v2 always divides by 10; BoA's
`averageVal()` may divide by the registered count during the first <10 steps — cannot flip the sign, benign.)

**The one difference is *when* the average is sampled** — v2 STALE, BoA FRESH — a distinct consumer of the same
end-of-step registration as the catch-slip; see difference #3 and D11.

### A5 — cocking trigger & the stroke's state currency — cocking predicate SAME; **stroke reads state at
different vintage (difference #4)**

- Predicate identical: v2 `isCocked = state != NUC_ADPPI` (`MotorStore.java:167`); BoA `isCocked = !isADPPi()`
  (`MyoMotor.java:286-288`). Cocked ⇒ {NONE,ATP,ADP}; uncocked ⇒ ADPPi.
- The stroke keys off it identically: v2 `directedSwing` picks `rest = (state != NUC_ADPPI) ? θ_cocked : θ_uncocked`
  (`CrossBridgeSystem.java`, θ_uncocked=0°, θ_cocked=**60°**, `swingParams` `GlidingHarness.java:408`); BoA selects
  `cockedMotor_ActinAngle` via `isCocked()` in the alignment torque, cocked neck = **70°** (the flagged
  geometry confound, `PHASE2_RELEASE_RECONCILE_FINDINGS.md` — a stroke-angle difference, out of scope here).
- **Ordering difference (in scope, A5's "pre- or post-cycle" question):** v2 runs `cycle` (`:465`) BEFORE the
  stroke (`directedSwing` `:491`) ⇒ the stroke reads THIS step's *post-cycle* state. BoA runs `biochemStep`
  (`:1623`, step end) AFTER the stroke force (`:1483-1500`) ⇒ the stroke reads the *prior* step's state. A 1-step
  cocking-state lag, v2 fresher. See difference #4.

### A6 — does the cycle ever detach? — **SAME: cocking-only in BOTH** (the reconcile assertion, now TRACED)

- v2: `cycle()` writes ONLY `nucleotideState`; it never touches `boundSeg` (`NucleotideCycleSystem.java:49-67`).
  The detaching variant `cycleAtpDetach` (which DOES write `boundSeg` at `:141-144`) is gated `CONFIG1 &&
  ATP_RELEASE` (`GlidingHarness.java:462`) — the sphere-head default is not CONFIG1, so it is never dispatched.
- BoA: every branch of `biochemStep` traced — `atpOnMyo`/`hydrolize`/`dissociatePi`/`dissociateADP` — **none
  calls `release()` or clears the bound segment.** `dissociateADP` sets state NONE but the head stays attached
  (`onFil` unchanged) until `ckRelease`. Detachment lives solely in `MyoFilLink.release()`, reached from
  `ckRelease` (break-cap / catch-slip) and `validateSeg` (invalid-segment).

⇒ **"Both cocking-only" is CONFIRMED by trace, not merely asserted.** The nucleotide cycle drives cocking; it is
never a detachment channel in either default.

---

## B. CATCH-SLIP RELEASE

### B7 — formula + constants — **SAME (bit-identical)**

`rate = kOff·(αCatch·e^(−F·xCatch/kT) + αSlip·e^(+F·xSlip/kT))`, `P = rate·dt`:

- v2 `catchSlipRelease` (`NucleotideCycleSystem.java:194`), consts `MotorStore.setKinParams:249-255`.
- BoA `ckRelease` (`MyoFilLink.java:495-497,503`), consts `Env.java`.

| | v2 | BoA | match |
|---|---|---|---|
| kOff | 100 /s (`:249`) | `kOff_init`=100 (`Env.java:1315`) | ✅ |
| αCatch | 0.92 (`:250`) | `alphaCatch_init`=0.92 (`:1299`) | ✅ |
| αSlip | 0.08 (`:251`) | `alphaSlip_init`=0.08 (`:1303`) | ✅ |
| xCatch | 2.5e-9 m (`:252`) | `xCatch_init`=2.5e-9 (`:1307`) | ✅ |
| xSlip | 0.4e-9 m (`:253`) | `xSlip_init`=0.4e-9 (`:1311`) | ✅ |
| kT | `Constants.kT` = Boltz·298.15 ≈ 4.114e-21 J (`:254`) | `Boltz·tempK` = 1.380662e-23·298.15 (`:495`) | ✅ |

### B8 — force read + currency — SAME quantity; **CURRENCY DIFFERS (difference #2, known)**

Both read the signed along-filament cross-bridge load `forceDotFil = Dot(F8, seg.uVec)` (v2 stores it in
`bondData[d+12]` `CrossBridgeSystem.java:203`, publishes via `registerForceDot:968`; BoA `thisStepDot`
`MyoFilLink.java:256,267`). Same quantity, same projection axis, same sign convention.

**Currency:** v2 reads a value written at the END of the PREVIOUS step (`registerForceDot` `GlidingHarness.java:506`),
read at the TOP of this step (`catchSlipRelease` `:444`) = **1-step STALE**. BoA writes `forceDotFil` in
`addForces` (`MyoFilLink.java:267`) and reads it in `ckRelease` (`:495`) **the same step, same evaluation** =
**FRESH** (GPU reconciled the same way, `GPUMoveThing.java:6195-6216`). This is `RELEASE_FORCE_TIMING_AUDIT.md`'s
finding, restated for completeness. (BoA has a default-OFF diagnostic `DIAG_RELEASE_LAG`, `MyoFilLink.java:257-264`,
that deliberately makes CPU read stale to mimic the old device lag — off in production.)

### B9 — refractory / rebind — same VALUE, mechanism differs (difference #7, known/deliberate)

- v2: `refractorySteps = ceil(myoRebindTime/dt)` = **1** at dt=1e-5 (`MotorStore.java:262`, `MYO_REBIND_TIME=1e-5`);
  on release `boundSeg→FREE_COOLDOWN`, counted down to `FREE_BINDABLE` (`NucleotideCycleSystem.java:203-209`).
  `blockProb=1.0` default ⇒ every release enters the block (`:174`, `MotorStore.java:270`).
- BoA: `myoRebindTime=1e-5` (`Env.java:1325`); a motor accumulates `bindTimer += deltaT` (`MyoMotor.java:188`),
  reset to 0 on release (`MyoFilLink.java:463`), and cannot rebind until `bindTimer ≥ myoRebindTime`
  (`ontoFilament` gate `MyoMotor.java:487`) ⇒ **1-step block** at dt=1e-5.

Same 1-step physical block. Mechanism differs: v2's per-motor sentinel countdown vs BoA's `bindTimer` — the latter
is the static-global race flagged in `GLIDING_4biv_RESIDUAL_DOSSIER.md` Part 2; v2 deliberately does NOT reproduce
it (correctly not inheriting a v1 bug).

---

## C. BREAK-FORCE CAP

### C10 — 12 pN cap — SAME threshold/force/order; **DEFAULT DIFFERS (difference #1, known)**

Both: `if (forceMag > myosinBreakForce·1e-12) { release; return; }` as the FIRST branch of the release routine,
BEFORE the catch-slip draw; force read = `forceMag = |F8|` (the cross-bridge spring magnitude).

- v2 `catchSlipRelease:186-191`; threshold `kinParams[11]=12.0e-12 N` (`MotorStore.java:266`); **`capOn=kinParams[12]=0`
  ⇒ DEFAULT OFF** (`:267`), flipped on only by `-faithfulrelease` (`setFaithfulRelease` `:341-344`).
- BoA `ckRelease:482-487`; threshold `myosinBreakForce_init=12.0 pN` (`Env.java:1292`); **ACTIVE by default**
  (not commented out).

BoA also has the `inRigor` bypass right after the cap (`MyoFilLink.java:490`: `if(inRigor) return;` — a rigor head
never catch-slip-releases); INERT in gliding (see difference #6). v2 has no rigor state, so the order collapses to
break-cap → catch-slip.

---

## D11 — FULL STEP ORDERING, both codes

### v2 default — `GlidingHarness.stepOrig` (FRESH_READ=false), GPU `buildPlan` mirrors it

| # | task | line | note |
|---|---|---|---|
| 1 | publishHeadFromBody | 430 | |
| 2 | bruteReachable (binding search) | 431 | |
| 3 | **catchSlipRelease** | 444 | reads **STALE** `forceDotFil`/`forceMag` (from #15 of prev step) |
| 4 | bindNearest | 450 | fresh binds |
| 5 | **cycle** (nucleotide) | 465 | ADP→NONE gate reads **STALE** 10-window `forceDotHist` (from #15 prev step) |
| 6 | zero / brownian / joints / anchor (motor) | 466-471 | joints read this-step post-cycle state |
| 7 | **bondForces** (F8/F9/F10) | 483 | computes THIS step's force → `bondData[12]` |
| 8 | applyHeadForce; **directedSwing** (stroke) | 489,491 | stroke reads THIS step's post-cycle state |
| 9 | integrate motor | 495 | bodies move |
| 10 | deriveMot | 497 | |
| 11 | **registerForceDot** | 506 | writes `forceDotFil`/`forceMag`/`forceDotHist` from THIS `bondData` → consumed at #3/#5 NEXT step |
| 12 | zero/brownian/chain (fil) + CSR + **segGather** | 509-516 | Jacobi seg-side gather from start-of-step `bondData` |
| 13 | integrate filament | 518 | |

### active BoA — `BoxOfActin` main loop (CPU); GPU via `bridgeMotorForceWriteback`

| # | phase | line | note |
|---|---|---|---|
| 1 | mesh collisions | 1366-1394 | |
| 2 | motor binding (detect) | 1407-1437 | binds BEFORE release |
| 3 | Brownian | 1442-1447 | |
| 4 | crosslinker forces | 1454 | |
| 5 | membrane links | 1465-1470 | |
| 6 | myosin joints | 1483-1489 | |
| 7 | `Thing.step()` → `MyoFilLink.step`: **addForces (writes FRESH forceDotFil/forceMag/tracker)** → alignUVec → alignYVec → **ckRelease** | 1498-1500; `MyoFilLink.java:187-193` | force + release, SAME evaluation, FRESH |
| 8 | gatherForces | 1507-1509 | |
| 9 | benchmark force | 1521-1524 | |
| 10 | advanceBiochemCadence | 1542 | |
| 11 | **moveThings** (integrate) | 1553-1609 | GPU: `bridgeMotorForceWriteback`→FRESH `forceDotFil`→`ckRelease` here (`GPUMoveThing.java:6195-6216`) |
| 12 | pin forces | 1613 | |
| 13 | **biochemStep** (nucleotide cycle) | 1623 | runs at step END; ADP→NONE gate reads FRESH tracker (fed at #7) |
| 14 | resetCounters | 1628 | |

**The structural contrast:** BoA computes force → releases → integrates → cycles (release & cycle both read
this-step force; stroke at #7 reads *prior*-step nucleotide state). v2 releases → binds → cycles → computes force
→ strokes → integrates → registers (release & cycle both read *last*-step force via #11-prev; stroke at #8 reads
*this*-step post-cycle state). Same position-Jacobi integration in both; the differences are purely *when each
release-adjacent quantity is sampled*.

---

## MASTER DIFFERENCE TABLE

| # | item | v2 default | active BoA | SAME/DIFF | significance |
|---|---|---|---|---|---|
| A1 | states/order | NONE→ATP→ADPPi→ADP→NONE | identical | SAME | — |
| A2 | 6 cycle rates | 2e4/100/100/1e4/0/1e3 | identical | SAME | — |
| A3 | cadence & dt | every step, `rate·dt`, dt=1e-5, 1 draw | every step, `rate·deltaT`, 1e-5, 1 draw | SAME | cadence candidate ruled out |
| A4 | ADP→NONE load gate (mechanism) | 10-window boxcar, avg≤0 allows | 10-window ValueTracker, avg>0 blocks | SAME | identical gate |
| A5 | isCocked | `state≠ADPPI` | `!isADPPi` | SAME | — |
| A6 | cycle detaches? | never (cocking-only) | never (cocking-only) | SAME | "both cocking-only" confirmed |
| B7 | catch-slip formula+consts | Guo–Guilford, kOff100/0.92/0.08/2.5/0.4nm | identical | SAME | — |
| B8 | catch force quantity | `forceDotFil` (signed, along-fil) | `forceDotFil` | SAME | — |
| **#2** | **catch-slip force CURRENCY** | **STALE (1-step lag)** | **FRESH (same eval)** | **DIFF** | known; REFUTED as split cause (`-freshread`) |
| **#3** | **ADP→NONE gate CURRENCY** | **STALE 10-window** | **FRESH 10-window** | **DIFF (NEW)** | same cause as #2, distinct consumer; bundled into `-freshread` |
| **#4** | **stroke reads nucleotide state** | this-step (cycle before stroke) | prior-step (cycle after stroke) | **DIFF (NEW)** | 1-step cocking lag, OPPOSITE direction; <1% of steps |
| **#5** | intra-step {release,bind} order | release then bind | bind then release | DIFF (NEW) | fresh bond can't release its bind-step (v2); can (BoA); ≤1-step |
| B9/#7 | refractory | `FREE_COOLDOWN` 1-step, blockProb 1.0 | `bindTimer` 1-step (static-global race) | DIFF (value SAME) | known/deliberate; v2 omits the race |
| **#1** | break-cap default | OFF (12 pN, `-faithfulrelease`) | ON (12 pN) | DIFF | known; wrong-direction, rarely fires (peak ~6 pN) |
| **#6** | `inRigor` bypass | none (no rigor state) | `if(inRigor)return` | DIFF (INERT) | ProteinNode-only; never set in gliding |

---

## ALL DIFFERENCES — dense-regime relevance + A/B-testability

1. **Break-force cap default (#1).** Reads `forceMag=|F8|`, fires before catch-slip. *Dense relevance:* BoA
   detaches MORE at high force ⇒ pushes duty DOWN, the wrong way to explain v2's LOWER avgBound; peak catch
   ~6 pN ≪ 12 pN so it rarely fires. Not a split candidate. **A/B: `-faithfulrelease` (exists).**
2. **Catch-slip release-force currency (#2).** *Dense relevance:* the leading prior candidate — **already REFUTED**
   (`FRESHREAD_AB_FINDINGS.md`: fresh collapses v2's net HARDER, −3.02→−0.51 @6 nm, reverses @8 nm — deeper
   tug-of-war, away from BoA). **A/B: `-freshread` (exists; done).**
3. **Cycle ADP→NONE load-gate currency (#3) — NEW.** v2's gate reads the 10-window average from the prior step;
   BoA's reads this-step. *Dense relevance:* same stale-force family as #2 — a stale gate can let a head now under
   load still roll ADP→NONE (or vice-versa), nudging cocking/duty; but it is small at dt=1e-5 and it flips WITH
   `-freshread` (stepFresh moves `registerForceDot` before `cycle` too, `GlidingHarness.java:559-562`), i.e. it was
   *bundled into the refuted A/B*. Not a new isolable candidate. **A/B: `-freshread` (bundled, not isolated).**
4. **Stroke/cocking-state ordering (#4) — NEW.** v2's stroke reads this-step post-cycle state; BoA's reads
   prior-step state. *Dense relevance:* affects only motor-steps with a state transition (<1% at these rates), and
   in the OPPOSITE currency direction to #2/#3 — unlikely to drive a capture-radius *sign* split. Also bundled into
   `-freshread` (the "force-before-biochem ordering" the FRESHREAD doc flags). **A/B: `-freshread` (bundled).**
5. **Intra-step {release,bind} order (#5) — NEW.** BoA can bind and release a head in one step; v2 cannot (release
   precedes bind). *Dense relevance:* a ≤1-step shift in the earliest release of a fresh bond; negligible vs the
   1-step force lag already tested. Not separately flagged. **A/B: would need a new reorder flag (not built).**
6. **`inRigor` bypass (#6) — INERT.** Only ProteinNode myosins set it (`ProteinNode.java:544`); the gliding assay
   never does. *Dense relevance:* none for gliding. **Relevant only if v2 later ports node-rigor myosins.** No A/B.
7. **Refractory mechanism (#7) — known/deliberate.** Same 1-step value; v2 omits BoA's `bindTimer` race by design
   (dossier Part 2). *Dense relevance:* the race is ~0.31 GPU / 0% CPU in BoA; v2 not reproducing it is correct,
   not a gap. **A/B: not applicable (deliberate divergence).**

**New instrumentation that would ISOLATE (none built):** a flag that moves ONLY the cycle's `registerForceDot`
consumption (#3) without the catch-slip currency (#2) or the stroke ordering (#4) — i.e., decompose `stepFresh`
into three independently-toggleable reorders. Only worth building if the planner wants to isolate #3 from the
already-refuted bundle; the standing evidence (fresh-bundle deepens the split) makes that low-priority.

---

## VERDICT

Beyond the already-known **release-force lag (#2)**, there are **three additional genuine release-path
differences (#3 cycle load-gate currency, #4 stroke-state ordering, #5 bind/release order)** plus the **known
break-cap default (#1)** and **two inert/deliberate items (#6 inRigor, #7 refractory race)**. Every *mechanism*,
*rate*, *cadence*, *load-gate*, *cocking predicate*, and *catch-slip parameter* is **bit-for-bit identical** — and
the reconcile doc's "both cocking-only" is now **traced and confirmed**, not merely asserted.

**Which could bear on the co-bound capture-radius split?** The two NEW currency/ordering differences (#3, #4)
are structurally bundled into EXACTLY the `-freshread` reorder that was already A/B-tested and found to *deepen*
the split (not close it). So the audit surfaces **no new isolable candidate**: the residual remains the **Jacobi
seg-gather co-bound load-sharing**, which none of #1–#7 touches. The honest caveat is that `-freshread` bundles
#2+#3+#4 and has not decomposed them; but as a bundle they move glide the wrong way, so decomposition is not the
promising lead — the engagement-matched seg-gather comparison (`IMPLICIT_XB_CAPTURE_RADIUS`/`FRESHREAD_AB` next
cut) is.

Which code is correct (fresh vs stale currency; break-cap on/off; stroke-state timing; the `bindTimer` race) is
a **planner call** — this audit only puts the differences on the table.

---

## JOURNAL line

```
## 2026-07-03 — RELEASE-PATH FULL AUDIT (v2 vs active BoA): cycle state-machine, rates, cadence, load-gate, cocking, catch-slip, break-cap, step-order. Read-only.
```
Traced the ENTIRE release path end to end. Cycle is bit-identical: same states/order, same 6 rates
(2e4/100/1e4/0/1e3), same EVERY-STEP `rate·deltaT` cadence (the biochemDeltaT cadence gates actin/crosslink
biochem, NOT the motor — `BoxOfActin.java:1623` runs `biochemStart` unconditionally), same 10-window boxcar
ADP→NONE load-gate (avg≤0/>0, sign+threshold match), same `isCocked=!isADPPi`, and BOTH cocking-only (v2
`cycleAtpDetach` gated off; BoA `biochemStep` never releases — traced, confirming the reconcile assertion).
Catch-slip formula+constants bit-identical. Beyond the KNOWN release-force lag (#2, refuted as split cause),
found 3 NEW currency/ordering diffs — #3 cycle load-gate reads STALE (v2)/FRESH (BoA), #4 stroke reads
this-step (v2)/prior-step (BoA) nucleotide state, #5 v2 releases-before-binds vs BoA binds-before-releases —
all consequences of v2 registering the load at step END; #3+#4 flip WITH `-freshread` (bundled into the refuted
A/B). Plus known break-cap default (#1, OFF v2/ON BoA, wrong-direction) and inert `inRigor` (#6, ProteinNode-only)
+ deliberate `bindTimer`-race omission (#7). NO new isolable capture-radius-split candidate; residual stays the
Jacobi seg-gather. Report: `RELEASE_PATH_FULL_AUDIT.md`.
```
