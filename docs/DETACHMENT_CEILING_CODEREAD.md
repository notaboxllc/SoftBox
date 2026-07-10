# Does the canonical motor have a detachment-limited speed ceiling? — READ-ONLY code diagnostic

**Date:** 2026-07-09 · **Branch:** dt-convergence-study · **Mode:** READ-ONLY (no edits, no runs, no aorus).
`BoA-v1ref` reference-only. Concurrent-safe with the azimuthal-falloff sweep.

**The question.** The field's gliding ceiling is **kinetic, not steric**: V = d/τ_on (stroke ÷ bound time), and
above a threshold density more bound heads do **not** add speed, because a post-stroke head that stays attached
becomes a **backward brake** the filament must wait to shed by detachment. Our model does the opposite —
velFitX climbs with avgBound, no plateau (`DENSITY_SWEEP_coltol8.md`, `AZIMUTHAL_GATE_INCREMENT2.md`). This read
determines whether the **drag/ceiling mechanism EXISTS in code** (⇒ measure/retune) or is **structurally
missing** (⇒ build it), and which of T1/T2/T3 is the culprit.

## The live canonical path (what actually runs)

The default `-gpu -full -grid` invocation (no motor flags) resolves to the **sphere-head neck-powerstroke stack**
— `GlidingHarness.java:77-79` (`SPHEREHEAD`/`AXLOCK`/`DIRSWING` all default-`true`), `:255` (`LYMN_TAYLOR`
default-on), `:221` (`XB_IMPLICIT2` default-on). The three kernels that matter here:

| Role | Kernel (default path) | Wire site |
|---|---|---|
| F8 cross-bridge spring | `CrossBridgeSystem.bondForces` (F9 **frozen** 90°, sphere head) | `GlidingHarness.java:757`, `:881` |
| Power stroke | `CrossBridgeSystem.directedSwing` (J1 angular converter OFF, `jointParams[3]=0` @ `:669`) | `:762`, `:883` |
| Detachment | `NucleotideCycleSystem.cycleLymnTaylor` (single nucleotide-driven release) | `:735`, `:863` |

The `bondForcesCanonical*`/`config1*` variants and `catchSlipRelease` force-cap are **not** on the default path
(flag-gated). Verdicts below are for the *live* stack; the variants are noted where they'd change an answer.

---

## T1 — post-stroke bond drag: is backward resistance representable? **VERDICT: YES (present).**

**The F8 cross-bridge is a bidirectional, material-anchored Hookean spring.** `bondForces`
(`CrossBridgeSystem.java:117-141`):

- The spring pulls the head tip toward a **fixed material site** on the segment. The site arc `aOff = bindArc −
  ½·segLen` (`:114-115`) is **frozen at bind time** — `bindArc` is a material point that translates *with* the
  filament, it is not re-set to "wherever the tip is now." So the spring has a real, moving anchor.
- `F = myoSpring·(site − tip)` (`:118-136`), applied **+F to the head** and **−F to the segment** (`:194`,
  `:199`). Zero rest length, no directional gate, no release-at-stroke-completion, no saturation on the default
  path (the `satMode` clamp is `-xbsat`-only, size-gated off at `:80-81`).

**Consequence — backward drag IS delivered.** Take a head that has finished its stroke and stays bound while the
filament glides forward (dragged by neighbours). The material site moves forward with the filament; the tip lags
(the head is tethered to the bed through the compliant J1/lever/anchor chain). So `(site − tip)` grows a forward
component ⇒ the **seg-side reaction −F points backward, opposing the glide.** A still-attached post-stroke head
resists. Nothing zeroes or releases the spring at stroke end.

**Empirical corroboration (not just code).** The density sweep's **per-bound efficiency collapse 0.95 → 0.46**
(`DENSITY_SWEEP_coltol8.md` §"Plateau") IS this backward drag, measured: co-bound heads on the same/adjacent
segments fight each other (the tug-of-war). Backward resistance is not merely representable — it is demonstrably
being delivered to the filament. **T1 is not the culprit.**

## T2 — detachment: strain-*direction*-dependent, or magnitude-blind? **VERDICT: SIGNED (present).**

**The canonical release is strain-DIRECTION-signed, not `|F|`.** On the Lymn-Taylor path the sole release is the
ADP→NONE step, **load-modulated by the Guo–Guilford catch-slip on the SIGNED load**
(`NucleotideCycleSystem.java:449-452`):

```
g(F) = αCatch·e^(−F·xCatch/kT) + αSlip·e^(+F·xSlip/kT)      // F = (τ-averaged) forceDotFil, SIGNED
rate(ADP→NONE) = onADP · g(F)                               // NONE→ATP then detaches the rigor head (:461)
```

`F = forceDotFil = Dot(F8, seg.uVec)` is a **signed projection on the filament axis** (`bondForces:204`), so the
rate distinguishes a **driver** (F>0 — resisting/forward strained ⇒ catch term small ⇒ *slower* release ⇒ stays)
from a **dragger** (F<0 — post-stroke, back-strained ⇒ catch term `e^(+|F|xCatch)` explodes ⇒ *faster* release ⇒
shed). That is exactly catch-forward / slip-backward. The same signed form is the `catchSlipRelease`
Guo–Guilford rate (`:193-194`) on the non-Lymn-Taylor path.

The **magnitude-only** channel — `-forcecapdetach`, `capOn && forceMag > breakForceN` (`:186`) — is a
direction-blind `|F8|>12 pN` cap, and it is **opt-in / NOT canon** (`capStats`, size-gated). It is not the live
release. **T2 is not the culprit.**

## T3 — powerstroke target: fixed post-stroke, or filament-tracking? **VERDICT: FIXED-orientation (present).**

**The swing target is a nucleotide-state-fixed ORIENTATION that re-neutralizes only in angle — and angle is
invariant under filament translation.** `directedSwing` (`CrossBridgeSystem.java:251-253`):

```
rest = (state != ADP·Pi) ? θ_cocked : θ_uncocked            // a FIXED angle, switched by nucleotide state (:251)
target lever û* = cos(rest)·û_head − sin(rest)·f̂           // f̂ = bound-seg uVec (:253)
```

After the stroke the head sits in ADP, `rest = θ_cocked` — a **constant**. The target is a *direction* built from
`û_head` and the filament *axis* `f̂`. As the filament **glides (translates)**, `f̂` does not rotate, so the swing
target direction is unchanged; the swing torque relaxes to zero once the lever reaches it and stays there. The
target therefore **does not track the filament translationally** and does **not** re-neutralize the transport
spring. (F9 is frozen at 90° on the sphere-head path, `bondForces:144` with `f9Frozen=1`; it is a perp-maintainer,
not a tracking stroke target. The transport that a moving filament strains is F8, and F8's anchor is the fixed
material `bindArc`, not a tracking setpoint — see T1.) **T3 is not the culprit.**

---

## HEADLINE — the mechanism's *ingredients* are all present; the *emergent kinetic ceiling* is still absent, for a reason ORTHOGONAL to T1/T2/T3

All three targets check out on the live path: **backward drag is representable (T1), detachment is
strain-direction-signed (T2), the powerstroke target is fixed (T3).** The prompt's hypothesis — that T1 or T3 is
structurally missing — is **REFUTED**. No single target is wrong.

**Yet V = d/τ_on does not hold** (velFitX climbs with N, no plateau). The gap is at a different level — two
structural facts that no amount of T1/T2/T3 retuning touches:

1. **Overdamped, instantaneous force-summation with NO per-head displacement clutch.** The integrator is
   overdamped Langevin: `V = Σforce / γ_filament`, evaluated fresh each step. The power stroke is applied as a
   **force** (a torque through `directedSwing`, transmitted by the F8 spring), **not** as a metered *d of
   displacement* that the head then refuses to let the filament exceed. So a post-stroke head contributes a force
   term to the sum, but nothing imposes the field's core constraint — *"the filament may not advance past d per
   attachment until I detach."* N bound heads ⇒ N× net force ⇒ V ∝ N. There is no mechanism by which attached
   heads *cap displacement*; they only *add/subtract force*. This is the structural absence.

2. **The model operates HIGH-duty (~0.85), density-flat — the wrong regime for the kinetic ceiling.** The
   kinetic d/τ_on ceiling is a **low-duty (~0.05)** phenomenon (a single leading head meters the advance). The
   density sweep measures **duty ≈ 0.85, dwell ≈ 0.60 ms, detach ≈ 1600/s, all density-INDEPENDENT**
   (`DENSITY_SWEEP_coltol8.md` §"Kinetics are density-independent"). At duty 0.85 nearly every reachable head is
   bound nearly all the time, so raising density just recruits more co-bound heads (avgBound ∝ density). The
   saturation that *does* appear is **co-bound interference** (per-bound 0.95→0.46) — a soft efficiency droop that
   *decelerates* the climb but never flattens it.

3. **(Subtle, and worth stating) T2 working CORRECTLY actively works AGAINST a plateau.** Catch-forward /
   slip-backward (T2) **sheds the backward brakes fast** (dragger F<0 ⇒ `e^(+|F|xCatch)` ⇒ quick release) while
   **retaining the forward drivers** (F>0 ⇒ catch ⇒ stay). That keeps the co-bound population net-forward-biased,
   which is *why* velFitX keeps rising instead of the tug-of-war cancelling to a plateau. The very mechanism that
   the ceiling needs *on the overrun side* (a driver that has reached end-of-stroke becoming a persistent brake)
   is dissolved by the catch removing back-strained heads. A correct catch **prevents** the brake from
   persisting; it does not build a ceiling.

**So: present-but-wrong-regime + a missing force→displacement clutch, NOT a missing T1/T2/T3 primitive.** The
ceiling is not one broken line you can fix; it needs either (a) a **displacement-metering / stroke-limit clutch**
that converts an attached head into a hard cap on further advance (the integrator-level change), or (b) moving the
model into a **low-duty regime** where τ_on genuinely rate-limits per-head throughput, or (c) a **steric
co-occupancy cap** (head-footprint exclusion) that limits how many heads can effectively act on a segment — which
is independently the lever `AZIMUTHAL_GATE_INCREMENT2.md` §"Verdict" converges on. A τ_on sweep alone cannot
reveal (a); this code read shows why.

---

## Code-predicted duty ratio, and whether anything makes it climb with density

**Predicted duty ≈ high (~0.85), density-flat — confirmed against the sweep.** On the Lymn-Taylor path the bound
lifetime is set by the ADP→NONE base rate `onADP` × the catch `g(F)` (`NucleotideCycleSystem.java:451`) and the
subsequent fast NONE→ATP detach (`:442`, `:461`); τ_off is the geometric rebind + the one-step refractory
(`kinParams[10]`). With onADP at skeletal ~1e3/s the unloaded dwell is ~1 ms, matching the measured **dwell ≈
0.60 ms, duty ≈ 0.85** (`DENSITY_SWEEP_coltol8.md`). This is a **high-duty motor**, not the ~0.05 skeletal
low-duty the d/τ_on ceiling assumes — a second reason the model doesn't live where the ceiling bites.

**Does anything make duty climb with density?** In code, **no explicit density coupling exists**: binding is a
per-head geometric reach search (no local-motor-count term), and `g(F)` depends on the head's *own* signed load,
not neighbour count. There is *one implicit* channel — the co-bound tug-of-war raises per-head |F| (T1), and the
catch (T2) would prolong a *forward-strained* driver's dwell → a weak duty-vs-density feedback. **But the measured
duty is flat** (`STATS_STEADY_ROW`, density-independent), so this feedback is negligible in practice: the velocity
climb runs entirely through **avgBound (number recruited)**, not through duty. So "no ceiling" is *not* a runaway
duty; it is recruitment × un-capped force-summation.

## Instrumentation scope for the deferred duty / τ_on-vs-V measurement

**Per-head τ_on/τ_off is ALREADY logged — no new instrumentation needed for the duty measurement.**
`cycleLymnTaylor` writes `stats[2m]` = bound-steps and `stats[2m+1]` = releases per motor
(`NucleotideCycleSystem.java:460, 462`; `catchSlipRelease` mirrors at `:168, :198`). τ_on = (bound-steps /
releases)·dt, duty = bound-steps / total-steps, detach-rate = releases / bound-time — which is exactly what the
harness already prints as `STATS_STEADY_ROW` (dwell / duty / detach). So the **duty-vs-density** half is a
re-read of existing output.

**The fixed-high-density τ_on-vs-V probe DOES need a run (GPU, deferred until aorus frees).** It needs a way to
**vary τ_on at fixed density** and read velFitX — i.e. scale the ADP→NONE base `onADP` (`nucParams[6]`) and/or the
detach `atpOn`, or the catch base, across a small ladder at one dense point (say d4000), plotting velFitX vs the
measured dwell. That isolates whether V responds to τ_on as d/τ_on (ceiling present, mistuned) or is insensitive
(ceiling absent, force-summation dominates) — the discriminator this code read predicts will come out
**insensitive-to-τ_on / linear-in-N**, i.e. no kinetic ceiling. No code change is required beyond exposing an
`onADP`/`atpOn` scale on the sweep driver; the counters to read it back already exist.

---

## Plain statement (the deliverable's bottom line)

**Can a post-stroke bound head drag the filament backward and be shed by strain-dependent detachment — i.e. does
V = d/τ_on hold in this model?** The *ingredients* are all there: a post-stroke head **can** drag the filament
backward (T1 — F8 is a bidirectional, material-anchored spring, and the measured per-bound collapse proves the
drag is delivered), and it **is** shed by **signed, strain-direction-dependent** detachment (T2 — Guo–Guilford
catch-slip on signed `forceDotFil`), with a **fixed** (non-tracking) powerstroke target (T3). **But V = d/τ_on
does NOT hold.** Because the integrator is overdamped force-summation with **no per-head displacement clutch**,
the stroke is a *force* and every bound head only ever *adds force* — so N× heads ⇒ ∝N× net glide, no kinetic
cap; and the model runs **high-duty (~0.85)**, the wrong regime for the ceiling, so the only saturation present is
a soft co-bound *interference* droop (per-bound 0.95→0.46) that decelerates but never flattens the climb. The
missing physics is **not** any of T1/T2/T3 — it is the **force→displacement stroke-limit clutch** (integrator
level) and the **low-duty / steric-co-occupancy regime** in which the kinetic ceiling lives. **Build the clutch /
co-occupancy cap; do not chase a τ_on retune, and do not "fix" T1 or T3 — they are already correct.**
</content>
</invoke>
