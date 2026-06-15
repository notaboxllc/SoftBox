# Soft Box Project Journal

Last updated: 2026-06-14

## 2026-06-14 — Increment 4b-iv residual step 2/2: nucleotide cycle under load — FAITHFUL; residual is EMERGENT
Second foundational faithfulness check on the static ~0.87× residual. **Is v2's nucleotide cycle different
or changing under gliding load? No — the cycle is faithful; the residual is a small emergent assist/resist
force-balance difference.** Measurement only — no physics edits; v1 instrumented by a scratch logging-only
build (`GlidingAssayEvaluator` shadowed in /tmp, `BoA-v1ref` byte-clean). Raw `RUN_LOGS/2026-06-14_4biv_
cycle.txt`. New (measurement): `GlidingHarness -cycldiag` + forceMag stats.

1. **Self-consistency (`-cycldiag`):** v2's empirical per-state conditional transition rates under load
   match the validated nominal within ~10 % (NONE→ATP 98 %, ATP→ADPPi 97 %, ADPPi→ADP 89 %, ADP→NONE|
   gate-open 111 %). The high ADP occupancy is the **load-gate** (open only 37 % of the time — assisting
   load holds ADP motors), NOT a malfunction.
2. **Drift (0.3 s run):** assist-fraction (~0.52) and glide (~4) FLATTEN — no continuing drift ⇒ static
   (the earlier 0.88→0.84 was second-order noise; only z keeps settling).
3. **v1 vs v2 (scratch v1 logging build, 4 seeds, 1424 bound-obs):** cycle rates, the load-gated
   `dissociateADP`, and the Guo–Guilford catch-slip law+params are **identical** (code-verified).
   **Occupancy MATCHES** (v1 ATP 58.9 / ADP 37.4 vs v2 59.8 / 36.6); **assist-fraction v1 54.4 % vs v2
   51.5 %** — a small ~3 pp difference (~2 SE; seed-1's 58 % regressed with more seeds). Near the 50/50
   balance, 3 pp maps to a meaningful net-force difference, consistent with the ~13 % residual.

**One port gap (reported, NOT fixed — physics change, and self-consistency passed):** v1's `ckRelease` has
a break-force release (cross-bridge tension > `myosinBreakForce` 12 pN) that v2 lacks — but v2's tension
exceeds 12 pN only 0.56 % of bound-steps and that tail is ~60 % assist, so shedding it would *lower*
assist-fraction (wrong direction). Not the cause.

**⇒ Both static candidates eliminated as faithfulness gaps (step 1 chain, step 2 cycle). The ~0.87×
residual is a genuine EMERGENT collective-coordination difference: v2's bound population is marginally less
assist-enriched (51.5 % vs v1's 54.4 %) at matched cycle/occupancy/release-law.** Diagnosis of the residual
is complete — a documented finding, not a tunable gap. Planner decides: accept the ~0.87×, or scope the
break-force port-gap / a deeper emergent-coordination study. `GLIDING_4biv_FINDINGS.md` §6 updated.

## 2026-06-14 — Increment 4b-iv residual step 1/2: chain stiffness at fracMoveTorq=0.2 — FAITHFUL, ELIMINATED
First of two foundational faithfulness checks on the static ~0.87× residual. **Is v2's chain as stiff as
v1's at the gliding `fracMoveTorq=0.2`?** (inc-2b validated only at 0.265.) **Yes — faithful, chain
eliminated.** Measurement only — no physics edits; v1ref instrumented (its real `-bmDiag`), never edited.

Ran the deflection characterization (11-seg×32-mon pinned chain, fracR=0.1) for both codes at 0.2 and 0.265:

| fracMoveTorq | v1 (`BoxOfActin -bmDiag`) | v2 (`-characterize`) | Δ |
|---|---|---|---|
| 0.265 (regression) | 0.99843 | 0.99831 | 0.01 % |
| **0.2 (gliding)** | **1.20240** | **1.20235** | **0.004 %** |

v2's chain matches v1 at 0.2 to ≤0.005 % — well within the inc-2b ≤0.05 % tolerance. Both are ~20 % softer
than the 0.265 beam target (ratio 1.20 vs 1.00), *identically* — that softness is a faithful property of
v1's gliding config, not a v2 gap. The two share the identical damped-torsion law (linear in `fracMoveTorq`,
no 0.265-baked constant), so it transfers cleanly.

**Gotcha worth recording:** the damped-torsion stiffness ∝ 1/dt — the deflection benchmark runs at dt=1e-4,
and an initial wrong dt=1e-5 override made v1 look 10× stiffer (ratio 0.10) and produced a spurious 10×
"divergence." With matched dt the codes agree to 5 sig-figs. (Both share this dt-dependence faithfully.)

⇒ **Chain stiffness is NOT the residual cause.** Per the prompt's bound, stop here for **step 2** (the
nucleotide-cycle-under-load check) — not improvised. `GLIDING_4biv_FINDINGS.md` §6(a) updated.

## 2026-06-14 — Increment 4b-iv z-settling probe: z-mechanism ELIMINATED (residual is static, not z-driven)
Bounded measurement-only probe of the ~0.87× net-directedness residual. The startup check had pointed at
§6(c): the filament *progressively losing motor support as its z settles*. Tested directly with
time-resolved 1 ms traces (v1 from `.dat` posZ/avgBound/vecMovedX; v2 via new `GlidingHarness -ztrace`:
centroid-z, tilt, avgBound, per-interval glide, bound-motor `forceDotFil` mean + assist-fraction), n=8,
both boxes. Raw `RUN_LOGS/2026-06-14_4biv_ztrace.txt`.

**Verdict: ELIMINATED.** (1) **Both codes settle to z ≈ −0.03…−0.04, nearly identically** (v1 14×2
−0.007→−0.036 vs v2 −0.002→−0.030; v1 4×1 −0.010→−0.031 vs v2 −0.006→−0.040; no consistent asymmetry — v2
settles *less* at 14×2). v2 does NOT sink more than v1. (2) **v1 settles in z just as much yet its glide
holds** (14×2 −2 %, 4×1 +14 %) — the direct counterexample: z-settling isn't the cause. (3) v2's
**assist-fraction is flat ~0.50–0.55 throughout** (no progressive disengagement; the ~50/50 tug-of-war is
present from the *start*); avgBound tracks v1 (~8.4→7.0). (4) The residual is **≈constant across the run**
(v2/v1 ≈ 0.88 early → 0.84 late) — present from the first bins, not a progressive collapse (the earlier
"~7 % decay" is a small second-order widening, not z-coupled).

**⇒ The residual is a STATIC coupling deficit (the §5 ~50/50 assist/resist tug-of-war), not progressive
z-settling.** Live candidates revert to chain-stiffness at the gliding `fracMoveTorq=0.2` (deflection-
calibrated at 0.265) and resisting-motor release timing — static determinants of the bound population's
assist/resist asymmetry. Probe stops here per its bound; planner decides whether to scope a fix or accept
the residual. **No physics edits**; committed `GlidingHarness -ztrace` (measurement) + `mot.forceDotFil`
added to the plan's host-transfer list. v1ref untouched (instrumented its real `.dat`).

## 2026-06-14 — Increment 4b-iv RECONCILED: the "0.51× velocity miss" was dominantly measurement-method
**The gliding-velocity "0.51× miss" was comparing two different MEASUREMENTS, not two physics.** Measured
the same way, multi-seed (n=8), at matched boxes, **v2 = 0.87× v1** — small, box-uniform, NOT a 2× miss
and NOT box-scaling. Measurement/protocol only — **no physics changed**. Full report + grid: `GLIDING_4biv_
FINDINGS.md`; raw `RUN_LOGS/2026-06-14_4biv_grid_reconciliation.txt`.

**Provenance of "8.33" (resolved).** It is v1's `longWindowSpeedXY` **at the end of a 0.1 s run** (BoA-v1ref
JOURNAL_ARCHIVE:8452, 10 seeds) — NOT a net glide and NOT in the validated table. The validated d=500
oracle (MYOSIN_VALIDATION L41/54) is `longWindowSpeedXY` **mean over ~0.5 s = 4.23 / median 3.70**, avgBound
6.91, and says explicitly to use net-displacement for honest comparison. The two v1 numbers differ ~2× by
window/run-length + an initial **settling jump** (v1's first interval literally reports `instantaneousSpeed
=309 µm/s` as the filament drops onto the bed). The net-vs-inflated gap is a measurement property present in
BOTH codes.

**The matched grid (NET = net displacement/time, v2 measured v1's exact `GlidingAssayEvaluator` way via the
new `GlidingHarness -grid`; v1 = real `BoxOfActin -r -gpu`, BOA_RNG_SEED 1–8; ±SEM, n=8):**

| box | v1 NET | v2 NET | v2/v1 | v1 inst | v2 inst | v1 avgB | v2 avgB |
|---|---|---|---|---|---|---|---|
| 4×1  | 4.61 ± 0.13 | 4.02 ± 0.15 | 0.87 | 7.39 | 6.88 | 7.47 | 7.20 |
| 14×2 | 4.69 ± 0.13 | 4.10 ± 0.18 | 0.87 | 7.33 | 6.92 | 7.29 | 7.60 |

**Decomposition.** (a) *Measurement method* dominates: v1's own NET @14×2 is **5.0, not 8.33** (8.33 is its
inflated lwEnd-of-short-run). (b) *Box scaling — NO mismatch*: v1 net +1.7 %, v2 net +2.0 % across box;
v2 reproduces v1's weak net box-scaling. The old "v1 climbs 4.4→8.33 while v2 flat" mixed v1's lwEnd with
v2's net. (c) *Residual*: a real but small **0.87× box-uniform** shortfall (~3σ/box, ~4σ pooled), specifically in **net
directedness** — `instantaneousSpeed` (total motion) and avgBound MATCH v1, but v2 converts less of that
motion into forward glide (the §5 co-bound tug-of-war, now sized at ~0.87× not ~0.5×; the n=3 snapshot's 0.76×/>5σ regressed to
0.87×/~3–4σ with the larger ensemble). The burrow target
is re-scoped from box-size/advance-per-stroke-2× to *coordination of the co-bound population*, ~−24 %.

**Decisive cell (v1 NET @ 14×2 = 4.69 ± 0.13, n=8)** — the apples-to-apples partner of v2's 4.10 — is ~4.7,
not ~8.3. **No physics edits**; committed the v1-style `measureGrid` measurement (instantaneous + net +
longWindow, sampled at v1's 100-step cadence) + the full-carpet viewer fix. v1ref left untouched (runs to a
scratch dir; the `-r` flag is required for headless, else BoxOfActin hangs after phase-plan).

## 2026-06-14 — Increment 4b-iv (gliding assay): assembled + works, velocity an OPEN FINDING
The gliding payoff — assemble 4a–4b-iii + the inc-2 chain filament into v1's gliding assay. **The
assembly works end-to-end: the filament glides −x, stably, with avgBound matching v1 — but the gliding
VELOCITY is below the v1 fixture, an open finding (NOT tuned).** Committed as a checkpoint at the
planner's explicit direction (the bail-out default was commit-nothing). Full report: `GLIDING_4biv_
FINDINGS.md`. New: `softbox/GlidingHarness.java`, `BindingDetectionSystem.bindNearest`, `run_gliding.sh`.

**What works (faithful config — density 500/µm², dt=1e-5, filament z=0 along +x, 11-seg 64-mono chain,
`fracMoveTorq=0.2`, surface = tail anchor + bound motors):** glides −x; stable over 30k steps (no NaN);
**avgBound 7.47 vs v1's 7.6** (big box); dwell-times/stroke/catch-slip/gather all from the validated
4b-i/ii/iii. A cheap-probe bug was caught + fixed early (motor Brownian had been omitted → heads stood
upright → avgBound=0; not a physics issue).

**The velocity finding.** v2 −4.0 µm/s vs the v1 fixture 8.33 (full 14×2 box). BUT — running v1 itself
at a matched small box (4×1) gives **6.66**, not 8.33: v1 drops ~20% from finite-size (the filament's
ends rotate toward the bed edges and lose support — the planner spotted this from the viewer). So the
gap vs a same-box v1 is **~0.64× (4.27 vs 6.66), the genuine remainder ~1.5× not 2×.** Localized by the
`-diag` instrument to the velocity coupling: **advance per power stroke 2.33 nm vs the 7 nm unloaded
stroke**, from a ~50/50 assist/resist tug-of-war among co-bound motors (weak net force). The big-box run
showed higher avgBound → slightly LOWER velocity (more drag) — v1 instead sustains high avgBound AND high
velocity, i.e. its bound population is coordinated/net-assisting. The levers (chain stiffness, myoSpring,
catch-slip, stroke) are all frozen validated constants ⇒ a faithful-config physics finding, not tuning.

**Direct v1 comparison (read-only v1ref built with `-encoding ISO-8859-1`; libs/.class are gitignored
build artifacts — worktree left clean):** v1 `glidingAssay500_val` at box 4×1 → 6.66 µm/s; viewer runs
`threejs_v1_gliding` vs `threejs_gliding` for side-by-side. Open for the planner (see the doc): the ~1.5×
coupling (chain-stiffness faithfulness at 0.2 vs the inc-2 deflection-calibrated 0.265; resisting-motor
release timing; filament-z).

**UPDATE — full-scale GPU resolution (the clean comparison).** Confirmed v1's MyoMotor nucleotide cycle
fires EVERY step (biochemStart phase ungated, BoxOfActin.java:1523; the biochemFireCt=10 is the FilSegment
poly/depoly gate, off in gliding) — v2's per-step cadence is faithful. Built the **GlidingHarness GPU
TaskGraph** (`buildPlan`, 23 kernels, one device-resident graph, `-gpu`; same systems as the CPU step,
only dispatch differs). Runs at the **full 14×2 box (~13.4k motors)**, stable, no per-step host pull.
Full-box multi-seed (3 seeds, 10k steps): **velocity 4.25 ± 0.32 µm/s vs the v1 fixture 8.33 ± 0.18 —
0.51×, a clean MISS outside SEM; avgBound 7.53 ± 0.50 MATCHES v1's 7.64 within SEM.** So binding is
faithful, the velocity coupling is ~half — the clean full-scale finding (NOT tuned; the mechanism burrow
is the scoped next move). **GPU throughput 386 steps/s @ 13.4k motors (~7.3× the CPU runner — measured GPU
386 vs CPU 52.6 steps/s; the earlier "~19×" was startup-contaminated)** — residency
wins this dense-proximity workload. CPU≡GPU aggregate-within-SEM (6×2 box: CPU 4.0/7.47 vs GPU 4.58/7.40).

**Existing paths unaffected:** 4b-iii stroke checkpoint PASS, 4a binding PASS, FDT 1.11676e-1 — bindNearest
is additive. **Increment 4 is NOT complete** (gliding velocity is a clean full-scale finding); 4a binding
+ 4b-i/ii/iii physics + the binding/residency/throughput of the gliding assembly stand validated.

## 2026-06-14 — Increment 4b-iii (new physics): nucleotide cycle + the power stroke (pinned checkpoint)
Added the genuinely-new physics of the stroke — the 4-state nucleotide cycle + the state-dependent
rest-angle switch (the stroke EMERGES from this, not a force law) + the full force-dependent catch-slip
— and validated it on a PINNED filament. **Scoped decision (planner): the gliding run itself (unpin +
surface + chain filament + dynamic binding + velocity/avgBound vs the v1 fixture) is deferred to a
dedicated increment (4b-iv); everything physical is now in place.** New: `softbox/NucleotideCycleSystem.
java`, `MotorStrokeHarness.java`, `run_stroke.sh`; rest-angle switching folded into `MotorJointSystem` +
`CrossBridgeSystem`; nucleotide state + the forceDotFil tracker added to `MotorStore`.

**Nucleotide cycle (`NucleotideCycleSystem.cycle`) — faithful MyoMotor.biochemStep port.** Per-motor
4-state machine NONE→ATP→ADPPi→ADP→NONE, run EVERY step (biochemStep cadence = 1; confirmed by the rate
analysis — at cadence 1000 the cycle would be 1000× too slow to glide), per-step transition probability
rate·dt with on/off-filament rates (Env.java:836-855: atpOnMyo 2e4, ATP→ADPPi 100, ADPPi→ADP 1e4,
ADP→NONE 1e3 /s). ADP→NONE is **load-gated**: it returns while the cross-bridge load's 10-window average
forceDotFil > 0 (the mechanochemical coupling, MyoMotor.java:271; ported as a per-motor ring buffer =
v1 ValueTracker(10)). `isCocked() = !isADPPi`. One wang-hash RNG draw per motor per step (distinct salt).

**Rest-angle switch (the stroke).** `MotorJointSystem` J1 lever-motor rest 0°(uncocked/ADPPi)↔60°(cocked)
and `CrossBridgeSystem` F9 motor-actin rest 90°↔120°, both keyed by the per-motor state. State flip →
rest angle changes → the F9/J1 alignment torques swing the head → the cross-bridge transmits a directional
pulse. The 4b-ii cross-bridge was restructured: `bondForces` now computes the bond once and stores
head-side(6)+seg-side(6)+forceDotFil(1) in bondData[13/motor]; `applyHeadForce` does the head self-write;
`registerForceDot` tracks the load; `segGather` sums seg-side over the CSR-inverse (the proven gather).

**Force-dependent catch-slip (`NucleotideCycleSystem.catchSlipRelease`).** The full Guo–Guilford form
rate = kOff·(αCatch·e^(−F·xCatch/kT)+αSlip·e^(+F·xSlip/kT)), F = forceDotFil (4a/4b-ii used the F=0 limit).

**Stroke checkpoint (`run_stroke.sh`) — 6 sharpened gates, ALL PASS:**

| gate | result |
|---|---|
| 1. cycle dwell == rate·dt | NONE 5.03 / ATP 984 / ADPPi 9.95 / ADP 98.8 steps vs 5/1000/10/100 (≤1.6%); cycle 0.0112 s — the 4-state analog of 4a's residence-time check |
| 2. regression guard | constant-ADPPi reproduces 4b-i (PASS) and 4b-ii (gather EXACT, segForce 3.6e-11→3.7e-12) exactly |
| 3. unloaded stroke | head tip Δ = (−5.87, 0, −3.75) nm ⇒ **6.96 nm** — a realistic myosin working stroke (lever 8 nm) |
| 4. directional force | cycling motors pulse net Σ filForce_x = −1.05e-8 N into the pinned filament (**−x**, the glide direction) |
| 5. catch-slip F-dependence | unbind rate 100→59→37→20 /s at load 0→1→2→4 pN (catch: +load stabilizes the bond), empirical == analytic |
| 6. CPU≡GPU | aggregate-within-SEM: mean filForce_x agrees 0.4%, avgBound 12.00/12.00 (the force-gated cycle decorrelates the microstate — banked standard) |

Gate 3 subtlety: in the loaded/pinned case the head tip is pinned by the cross-bridge spring so the
stroke is FORCE (gate 4), not tip motion; the unloaded stroke (F8 off, head swings freely under F9/J1)
is the ~7 nm working stroke — measured that. The −x/−z swing direction is the power-stroke geometry.

**CPU≡GPU validation standard applied.** Cycle-only (cross-bridge off, forceDotFil=0) is bit-identical
(pure integer RNG); the force-gated stroke (a float forceDotFil comparison flips gated transitions) is
aggregate-within-SEM — exactly the standard banked in 4b-ii.

**Existing paths unaffected (verified):** FDT D_par 1.11676e-1 (bit-identical), 4a binding off-rate
0.00999, broad-phase EXACT, 4b-i/ii reproduced via the regression guard. No bail-out triggered. The
stroke physics is validated; the gliding integration (4b-iv) is the next increment.

## 2026-06-14 — Increment 4b-ii: cross-bridge + the cross-entity gather (pinned)
Connected the articulated motor head (4b-i) to a **pinned** filament via the cross-bridge spring +
alignment torques, and built the **cross-entity motor→segment force+torque gather** — the design-risk
centerpiece, and the template every future multi-store coupling inherits. FIXED uncocked rest angle +
pinned filament ⇒ **no stroke, no motion, no gliding** (those need the nucleotide cycle, 4b-iii). New:
`softbox/CrossBridgeSystem.java`, `MotorXBridgeHarness.java`, `run_xbridge.sh`; + the CPU≡GPU
validation-standard note in CLAUDE.md (Task 0). **No existing file touched** (purely additive).

**Cross-bridge (`CrossBridgeSystem.bondForces`) — faithful `MyoFilLink` port.** Per bound motor, between
its head tip (head sub-body end2) and the bound site on the segment (attachPt = seg.coord + (bindArc −
½·segLen)·seg.uVec): F8 spring `F = myoSpring·dist` toward the site (addForces:187, myoSpring=1e-9 N/µm);
F9 uVec-alignment torque toward the motor–actin rest angle (FIXED 90° uncocked; alignUVecTorque); F10
yVec-alignment torque toward 0°. The cross-bridge force is applied at the head tip / the bound site, so
each end gets the POSITIONAL torque R×F (R in metres, the v1 `incForceSum(F,pt)` 2-arg semantics,
Thing.java:505). Equal-and-opposite: the head gets +F at its tip and −T9/−T10; the segment gets −F at
attachPt and +T9/+T10. The bond is computed ONCE: the head-side is applied to the head
sub-body (3m+2, self-write — one bond per head, race-free); the seg-side reaction is STORED in
`bondSeg6[m*6..]` for the gather.

**THE CROSS-ENTITY GATHER (the centerpiece, reusable infrastructure).** Motors write force to segments in
a DIFFERENT store — the race v1 hit with spawn()+shared taForce. Race-free WITHOUT atomics/`KernelContext`
(the dual-runner constraint) by a SEGMENT-SIDE gather over a **segment→bound-motors CSR-inverse** index:
`csrHistogram` (count bound motors per segment) → `csrScan` (prefix-sum offsets) → `csrScatter` (motor ids
grouped by segment) — exactly the inc-3 grid-CSR pattern keyed by `boundSeg` instead of cell, single-thread
+ serial (race-free, no atomics, both runners). Then `segGather`: each segment (one thread) sums its bound
motors' stored `bondSeg6` reactions into its OWN forceSum/torqueSum. The scatter visits motors in index
order ⇒ the per-segment list is sorted by m ⇒ the gather sums in the SAME order as the brute reference ⇒
**bit-identical** (not merely modulo float ordering). This is general infra — crosslinkers / nodes /
membrane↔ring reuse it (CSR-inverse keyed by the partner id + segment-side gather).

**Force-coverage audit** (each force/torque on exactly one path): F8 spring +F on the head (self-write
×1) / −F on the segment (gathered ×1) — equal-opposite by construction; F9 −T9 head / +T9 seg (×1); F10
−T10 head / +T10 seg (×1). The gather == brute equality (below) proves each seg-side contribution is
summed exactly once. No double-apply, no drop.

**Harness (`MotorXBridgeHarness`, 4 pinned segments, 12 motors = 3/seg, dt=1e-5, Brownian off).** Pinned
filament along x at z just above the standing head tips; 3 articulated motors under each segment. Binding
ESTABLISHED on the host (deterministic): publishHeadFromBody → bruteReachable → bindKinetics — 4a binding
re-exercised reading the **new head sub-body** (12/12 bound, [3 3 3 3] per segment ⇒ a multi-motor gather).
Then bonds frozen. Per cross-bridge step: zero → brownian(off) → joints → anchor → bondForces (head +
store) → integrate → derive → zero(fil) → CSR-inverse → segGather → bruteGather. The filament is pinned
(not integrated); its forceSum/torqueSum receive the gathered cross-bridge for validation.

**Gates (both runners): PASS.**

| check | GPU | CPU |
|---|---|---|
| gathered F+T == brute per-bond sum (max diff) | **0.0 EXACT** | **0.0 EXACT** |
| binding re-exercised (bound / per-seg) | 12/12 [3 3 3 3] | same |
| CPU≡GPU gathered force (max ΔF) | — | **7.3e-19 N** (float32 last-bit) |
| CPU≡GPU gathered torque (max ΔT) | — | **2.9e-26 N·m** |

Σ|segForce| starts 3.6e-11 N (heads 3 nm below the filament → F8 = myoSpring·3nm ≈ 3pN ×12) and relaxes to
a 3.7e-12 N steady residual as the heads are pulled to their bound sites + oriented to the filament (F9/F10)
— a clean static cross-bridge equilibrium (no stroke). CPU≡GPU is held to **bit-identity** here (per the
new validation standard: this config is near-static, not chaotic) and meets it to float32 last-bit.

**Existing paths unaffected (verified):** 4b-i articulated motor PASS, 4a binding off-rate 0.00999 +
reachable EXACT, inc-3 broad-phase EXACT, deflection ratio 0.99831 — all reproduce (only new files added,
no existing system touched). No bail-out triggered. The cross-entity gather is in hand as reusable
infrastructure. Ready for 4b-iii: the nucleotide cycle + rest-angle switching (the stroke) + F-dependent
catch-slip → unpin + surface → gliding velocity + avgBound vs the v1 fixture (8.33 µm/s, meanBound 7.6).

## 2026-06-14 — Increment 4b-i: articulated myosin motor (the body), isometric
Re-architected `MotorStore` from 4a's single point into v1's **3-body articulated myosin** — rod (tail,
anchored) → lever (neck) → head — held by two joints, integrated by the SHARED rigid-rod systems, and
validated **isometrically** (a bed of anchored motors holds its articulated shape under Brownian, no
filament). **No cross-bridge, no nucleotide cycle, no gliding, no surface** (those are 4b-ii/4b-iii).
This followed the 4b bail-out finding (v1's power stroke is emergent from this articulated body + a
nucleotide cycle + alignment torques, not a portable force). New: `softbox/RigidRodBody.java`,
`MotorJointSystem.java`, `TailAnchorSystem.java`, `MotorBodyHarness.java`, `run_motorbody.sh`; +CLAUDE.md
abstraction invariant (Task 0). Also folded the sharpened "abstract from the second instance" invariant
into CLAUDE.md.

**Rigid-rod-body factoring (the second-instance abstraction — VALIDATED).** `RigidRodBody` factors the
entity-agnostic rigid-rod layout (planar-SoA pose / drag / accumulators / Brownian) that was previously
inline in `FilamentStore`. FilamentStore now EMBEDS one and ALIASES its existing public arrays to the
body's (same objects ⇒ the validated FDT/deflection/chain paths see identical arrays — zero behavioural
change). MotorStore embeds one of 3·nMotors sub-bodies (3m=rod, 3m+1=lever, 3m+2=head). The SHARED
device systems — `BrownianForceSystem`, `RigidRodLangevinIntegrationSystem`, `DerivedGeometrySystem` —
run over `MotorStore.body` **UNCHANGED** (they already took raw arrays; no motor-specific
reimplementation needed). **Abstraction-leak rule held:** the one genuinely entity-specific piece is the
DRAG FORMULA — the diff the second instance revealed is rod-drag (actin seg / myo rod / myo lever, the
shared `DragTensorSystem.rodDragSI` helper) vs **sphere-drag** (the myo head, `sphereDragSI`). That's
localized in the host-side drag init (a stored parameter), NOT a forked device system — exactly the
invariant ("entity-specific physics localized; never hardcode it in the shared systems"). The rod-drag
formula is now ONE helper used by both stores (FDT re-verified bit-identical after the extraction).

**MotorStore layout.** Articulated `body` (RigidRodBody, 3·nMotors) + the bed anchor point (`anchor`,
reused from 4a, = the rod's fixed `end1`) + `bodyParams`[dt,brownMag] + `jointParams` (J1/J2 PAIRS
coeffs, rest angles, stall cap). The 4a binding interface (head/uVec/rodUVec/bound-state/…) is PRESERVED
as a published projection of the body (`publishHeadFromBody` — the "repoint"; inert this increment, no
filament, wired for 4b-ii). Geometry from v1 (Env.java:776-778): rod 0.080, lever 0.008, head 0.020 µm;
radii 0.003/0.002/0.010.

**Joint law (`MotorJointSystem`) — faithful port of Myosin.applyRodLeverJointForce (J2) +
applyLeverMotorJointForce/Torque (J1).** Structurally the inc-2 chain joint: a `moveCoeff`-normalized
PAIRS connection spring (forceMag = fracMove·strain/(dt·(mcA+mcB)), applied at body centre + an explicit
½·len·fracR lever-arm torque toward the joint end) + a bend torque toward a rest angle. Specialized to
the myosin topology: J2 connects rod.end2↔lever.end1 (rest 96°, **angular spring OFF** — v1
`myoJ2FracMoveTorq=0`, so a free hinge, connection-spring only); J1 connects lever.end2↔motor.end1 (rest
**0° uncocked** — FIXED state this increment; angular spring on, capped at the stall-force torque
`stallForce·0.5·motorDim·1e-18`, Myosin.java:241). **Ownership (race-free, no atomics — the chain
pattern):** one thread per sub-body; each computes the joint contributions ON ITSELF and writes only its
own forceSum/torqueSum; a joint is evaluated from both endpoints (forceMag symmetric ⇒ equal/opposite).
`TailAnchorSystem` ports `applyRodFixedPtForce`: a connection spring pulling rod.end1 to the bed point
with moveC1=0 (fixed point immovable), FORCE-only (v1's torque is commented out), reaction discarded.
Lever gets NO Brownian (v1 MyoLever.moveThing Brownian commented out); rod+head get it (attn 1.0) — set
via the per-sub-body `brownTransScale`/`brownRotScale`. Sign convention nailed from v1
`Pt3D.unitVec(a,b)=unit(a−b)` (the springs are attractive). dt=1e-5 (v1 gliding regime); the PAIRS
relaxation is dt-independent (forceMag∝1/dt, displacement∝force·dt), so the joints are stable for
fracMove<1.

**Force-coverage audit** (every force on exactly one path): J2 connection (rod-side + lever-side,
equal/opposite) ×1; J1 connection (lever-side + head-side) ×1; J1 bend torque (lever + −head) ×1; J2
bend torque ×1 (=0); anchor force on rod ×1; Brownian ×1 (rod+head only). No double-apply, no silent
drop. TaskGraph: zero → brownian → joints → anchor → integrate → derive.

**Isometric validation (`./run_motorbody.sh`, 64 motors, M=5000, dt=1e-5, GPU + `-cpu`): PASS.**

| step | gapJ1(nm) | gapJ2(nm) | anchor(nm) | angJ1(°) | angJ2(°) |
|---|---|---|---|---|---|
| 0    | 6.7  | 8.4  | 9.4  | 13.0 | 3.9  |
| 1250 | 13.8 | 16.9 | 14.8 | 16.5 | 106.1 |
| 4999 | 13.6 | 18.3 | 17.9 | 15.4 | 97.8 |

Joint gaps bounded (<30 nm, ≪ the 8–80 nm body sizes) and non-growing over 5000 steps — the 2a
"holds-together" check for an articulated body. J1 angle ~15° about its 0° rest (thermal; the head is
the tiny high-D body the J1 spring restrains). J2 free hinge settles at the ~96° thermal mean of an
unconstrained lever+head dangling from the rod tip (faithful — there's nothing holding the head "up"
without a filament; the cocked state / a bound filament does that in 4b-ii/iii).

**CPU≡GPU — aggregate-statistics test (chaotic system).** The per-runner joint tables are byte-identical
to printed precision; the GPU vs CPU aggregate gaps/angles agree to <1e-4 nm / <1e-5°. The per-body
MICROSTATE trajectory diverges at the float-noise level (max|Δcoord| 1.5e-5 → 0.011 nm over 5000 steps)
— the fma/transcendental op-ordering divergence (inc-2's 0.99831/0.99832 finding), amplified by the
dynamics and bounded far below body size. Bit-identity is unattainable (and unnecessary) for a chaotic
thermal many-body run; this is exactly how v1's own gliding agrees CPU-vs-GPU (8.326 vs 8.231 µm/s,
within SEM). Gate = aggregate agreement + bounded microstate (no logic blowup).

**Existing paths unaffected (verified bit-identical):** FDT D_par 1.11676e-1 / D_rot 1.89712e1 (baseline
config N=2048 M=8000), deflection ratio 0.99832 / τ 0.9920, inc-3 broad-phase EXACT, 4a binding off-rate
0.00999 + reachable EXACT. The FilamentStore embed/alias + the DragTensorSystem rod-drag extraction are
byte-clean. No bail-out triggered. Ready for 4b-ii (cross-bridge + alignment torques + the cross-entity
gather, head ↔ pinned filament).

## 2026-06-14 — Increment 4a: myosin motors + binding detection (first narrow-phase consumer)
Motors as a SECOND entity type + the first narrow-phase consumer of the broad-phase: binding
detection + bind/unbind kinetics. **No motion this increment** — no power stroke, no surface
confinement, no gliding velocity (all 4b). **Bound motors apply NO force.** New: `softbox/MotorStore.
java`, `softbox/BindingDetectionSystem.java`, `softbox/MotorBindingHarness.java`, `run_motor.sh`; one
constant added to `SpatialBodyView` (`STORE_MOTOR=1`). **Everything else — the broad-phase, the grid,
FilamentStore, the Brownian/integration/derive systems — is UNCHANGED.**

**Entity-agnostic design VALIDATED.** The grid/broad-phase (`SpatialGrid`) needed zero changes: motors
register into the existing `SpatialBodyView` via a second publisher (`MotorStore.publishToBodyView`,
center=head, boundingRadius=reach, ownerStore=STORE_MOTOR, ownerSlot=slot), occupying body slots
[nFil, nFil+nMot). The consumer (`BindingDetectionSystem.invertCandidates`) consumes the broad-phase
candidate pairs and FILTERS by `ownerStore` to motor↔segment pairs — all `FilSegment`/`Motor` type
logic lives in the consumer, none in the broad-phase. `invertCandidates` handles BOTH pair orderings
(motor=i/seg=j and seg=i/motor=j), so it is independent of publisher layout in the view; single-thread
serial ⇒ race-free, deterministic, bit-identical CPU↔GPU.

**MotorStore layout (SoA, source of truth, device-resident).** Planar SoA (stride nMotors): `head`
(bindTip = body-view center), `uVec` (head axis), `rodUVec` (rod axis), `anchor` (viewer link only).
Scalars: `reach` (= myoColTol, also the body bounding radius). Bound-state in ONE int `boundSeg[m]`:
≥0 → bound to that segment slot; −1 → free & bindable; −2 → free in the one-step rebind refractory
(v1 myoRebindTime 1e-5 s < dt=1e-4 s). `bindArc[m]` = arc-length bind site. `stats[2m|2m+1]` =
per-motor (bound-step, release) counters (race-free; host sums). `kinParams` carries the v1 catch-slip
constants (kOff=100/s, αCatch=0.92, αSlip=0.08, xCatch/xSlip) + reach/alignTol + forceDotFil(=0).

**Kinetics — FAITHFUL v1 mechanism (planner decision).** v1 myosin binds DETERMINISTICALLY on contact
(modulo refractory) and releases via the force-dependent Guo–Guilford catch-slip rate
(`MyoFilLink.ckRelease`, p = kOff·dt·[αCatch·e^(−F·xCatch/kT)+αSlip·e^(+F·xSlip/kT)]). At zero force
(no power stroke this increment) catch+slip = αCatch+αSlip = 1 ⇒ the release probability reduces
**EXACTLY** to p = kOff·dt = 0.01 — so 4a's off-rate IS v1's, in the no-force limit, with NO tuning;
the catch/slip terms are carried inert for 4b. Binding is deterministic (nearest reachable segment,
no RNG). RNG (release only) is the REUSED v1 wang-hash keyed (slot, stepCount, runSeed) with a MOTOR
salt — integer-identical on both runners. **The prompt's k_on/(k_on+k_off) equilibrium does NOT apply
(v1 has no stochastic on-rate); validated the off-rate instead** (see below). The exact bind-reach
predicate (`reachTestDistSq`) is v1 `MyoMotor.checkFilSegCollision`: perpendicular drop of the head
onto the segment, gated by α∈[0,1], conDist<myoColTol(6 nm), the head-align gate (motDotFil≥−0.4) and
the rod gate (rodDotFil≥0). ONE predicate, called by both the grid path and the brute reference.

**Assay (`MotorBindingHarness`, default M=3000, brownScale=0.02, dt=1e-4).** Static gliding-assay-like
config: 200 filaments (10×20, along x at z=0) with one "reachable" motor at each filament's centre
(conDist≈0) + 100 "control" motors a z-offset (40 nm ≫ 6 nm) above the plane. Filaments diffuse
(Brownian) at REDUCED amplitude — v1's 6 nm reach is tiny next to a full-amplitude diffusion step
(~4.5 nm), so a stable geometric reachable set needs gentle motion; the off-rate is reach-INDEPENDENT
(faithful mechanism unbinds only via stochastic release, never reach-loss), so it is unaffected. Motor
rods tilted toward +x (normalize(0.3,0,1)) so the v1 rodDotFil≥0 gate clears with margin (a vertical
rod sits EXACTLY on the gate boundary for a horizontal filament — a coin-flip on the filament's tiny
z-tilt; tilting it took reachMot 105→200 at step 0).

**Gate 1 — reachable-set EXACTNESS (exact, no tolerance): PASS on BOTH runners.** computeReachable
(grid path, consuming broad-phase candidates) == bruteReachable (every motor×segment) EXACTLY at every
sampled step, GPU and `-cpu`. Control motors NEVER reachable (negative control clean). reachMot 200 at
step 0 decaying 200→98 as filaments diffuse out of the 6 nm z-reach (dynamic reachable set, exercising
the consumer). No candidate overflow (maxCand=20 ≪ 256).

| step | gridPairs | brutePairs | match | reachMot | control |
|---|---|---|---|---|---|
| 0 | 200 | 200 | EXACT | 200 | clean |
| 750 | 176 | 176 | EXACT | 176 | clean |
| 1500 | 138 | 138 | EXACT | 138 | clean |
| 2999 | 98 | 98 | EXACT | 98 | clean |

**Gate 2 — off-rate STATISTICS (tol 5%): PASS.** Empirical per-step release p = totalReleases/
totalBoundSteps = 4739/474367 = **0.00999** vs analytic kOff·dt = **0.01000** (rel err **0.10%**); mean
bond lifetime 100.1 vs 100.0 steps. This validates the stochastic release machinery + RNG keying.
meanBound=158 motors, boundFraction=0.79 of 200 reachable (< the τ_on/(τ_on+τ_off)≈0.98 ideal because
reachMot decays — motors that lose reachability stay free). **Not k_on/(k_on+k_off)** (v1 binds
deterministically) and **not v1's avgBound≈7.6** (that needs the 4b power-stroke force) — neither is a
4a gate; the analytic off-rate is.

**Gate 3 — CPU≡GPU: bit-identical.** reachable set, bound-state (boundSeg), and stats all identical at
every sampled step; final totals bit-identical (boundSteps 474367/474367, releases 4739/4739). Positions/
uVec are bit-identical CPU↔GPU (the inc-3 result), so the predicate — even at the gate boundaries — and
the integer RNG agree exactly ⇒ identical bind/release decisions.

**No force written → production paths unaffected (verified).** `bindKinetics` writes only boundSeg/
bindArc/stats, never forceSum/torqueSum; integration reads only the filament force accumulators, which
motors never touch. Re-ran inc-3 broad-phase (CPU, candidate set EXACT == brute) and the deflection
benchmark (ratio 0.99832, τ 0.9920) — both reproduce their pre-inc-4 numbers exactly. FDT/deflection/
chain are unaffected (force-coverage: motor binding applies zero force this increment).

**Viewer (`-3js <dir>`).** Bound motors drawn red with a rod from the anchor to the bound site on the
segment (the link); free motors blue, rod pointing up. Emitted in the v1 viewer's `myosins` schema
(rod/lever/motor composite) so the unmodified `sim_viewer_boa.html` renders binding/release. No fork of
the viewer; `FrameWriter` stays segments-only (the motor frame writer is harness-local).

Open: the dynamic reachable set decays over a run (filaments diffuse out of the tiny 6 nm reach); 4b's
surface confinement + gliding will keep filaments engaged. No bail-out triggered.

## 2026-06-13 — Increment 3: entity-agnostic spatial grid + broad-phase
Device-resident uniform grid (CSR) + broad-phase that emits candidate interaction pairs.
**Infrastructure, not physics — no forces written this increment** (the first narrow-phase consumer
is motors, inc 4). Gate: exact set-equality vs an O(N²) brute-force reference, on **both** GPU and the
`-cpu` runner, CSR bit-identical CPU↔GPU, O(N) vs O(N²) scaling. New: `softbox/SpatialBodyView.java`,
`softbox/SpatialGrid.java`, `FilamentStore.publishToBodyView`, `softbox/BroadPhaseHarness.java`,
`run_grid.sh`.

**Body view (the entity-agnostic seam).** `SpatialBodyView` represents any collidable body as a
bounding SPHERE: `center` (planar SoA, plane stride = capacity) + `boundingRadius` + back-pointer
`ownerStore`/`ownerSlot`. The grid + broad-phase read ONLY the view — zero `FilSegment` knowledge.
`FilamentStore.publishToBodyView` (a device step) is the sole publisher now: center = segment coord,
boundingRadius = ½·segLength + actin radius (sphere bounds the capsule), ownerStore=STORE_FILAMENT,
ownerSlot=slot. Nodes/membrane/motors register into this same view later.

**CSR build (ported from v1 GPUMotorBinding).** cellId = ix + iy·nX + iz·nX·nY. Passes: bodyCell
(center cell, clamped) → gridZero → gridHistogram → **two-level parallel prefix-sum**
(gridScanLocal per-chunk exclusive scan + gridScanChunks single-thread scan of chunk totals +
gridScanAdd add-base/reset-cursor) → gridScatter. The parallel scan is the hard primitive, ported
from v1's gridScanLocal/Chunks/Add (GRID_SCAN_CHUNK=512). Histogram + order-independent scan + serial
scatter (bodies in index order) ⇒ CSR **bit-identical** (offsets + within-cell order), not merely
multiset-equal.

**Binning choice → provable completeness + exact match.** Each body bins into the single cell of its
CENTER; cellSize = 2·maxBoundingRadius + cutoff. Then any pair within reach (centerDist ≤ rᵢ+rⱼ+cutoff
≤ 2·maxR+cutoff = cellSize) has center cells ≤1 apart in every axis ⇒ in the 27-cell stencil. The
broad-phase re-applies the EXACT predicate (`distSq ≤ (rᵢ+rⱼ+cutoff)²`, same as brute force) before
emitting, so over-scanned cells are filtered and none are missed ⇒ candidate set == brute set exactly.
Center binning ⇒ each body in one cell ⇒ pair (i,j) discovered once by thread i ⇒ the i<j guard dedups
with no min-corner logic (unlike v1's AABB binning, which needed it). Output = per-body owned slices
candPartner[i·MAX_CAND+k]/candCount[i] (race-free, no atomics; overflow detected + reported).

**FINDING — KernelContext atomics dropped for dual-runner portability.** v1's production
`gridHistogramKernel` uses `context.atomicAdd` (a TornadoVM KernelContext device construct) which CANNOT
run on the plain-Java `-cpu` runner. To honour the one-implementation invariant (every kernel runs on
BOTH runners), the histogram + scatter are single-threaded (`@Parallel` range 1, serial inner loop) —
exactly v1's `gridAssembleKernel` oracle structure: race-free, O(N), no atomics, no KernelContext.
Serial on the GPU but O(N) (the parallel work is the scan + broad-phase). A future parallel
chunk-ownership histogram/scatter (v1's gridScatterChunkKernel pattern, also atomic-free) can replace
them without breaking the invariant.

**Validation (`./run_grid.sh [N [M]]`, default 512×2000; also N=2048).** Bodies = free rods diffusing
(inc-1 brownian→integrate, translational only) in a density-fixed cluster; grid rebuilt every step;
candidate vs brute compared as order-independent sets at 5 sampled steps.

| check | N=512 | N=2048 |
|---|---|---|
| grid set == brute set (GPU), all steps | EXACT | EXACT |
| grid set == brute set (CPU), all steps | EXACT | EXACT |
| CSR bit-identical CPU↔GPU, all steps | yes | yes |
| candidate set identical CPU↔GPU, all steps | yes | yes |
| max candidates/body (MAX_CAND=256) | 19 | 23 (no overflow) |

candPairs densest at step 0 (2639 @512, 11153 @2048), falling as the cluster spreads — physically
right. A per-sample interior check guards 27-stencil completeness (flags any body clamped to a grid
edge cell rather than silently missing a pair); none triggered.

**Scaling (fixed density, CPU runner, work + timing):**

| N | candPairs | bruteTests | gridTests | grid(ms) | brute(ms) |
|---|---|---|---|---|---|
| 512  | 2639  | 130,816   | 26,610  | 0.281 | 0.332 |
| 2048 | 11153 | 2,096,128 | 129,606 | 1.211 | 4.854 |

×N=4: bruteTests ×16.0 (=N²), gridTests ×4.9 (≈N); brute(ms) ×14.6 (≈N²), grid(ms) ×4.3 (≈N). Grid
already beats brute at N=512 and is 4× faster at N=2048, gap widening. (gridTests ×4.9 vs ideal 4.0 is
a cluster surface effect — fewer neighbours per body at the smaller cluster's relatively larger
surface; vanishes as N→∞, clearly linear not quadratic.)

**No forces written → production paths unaffected (verified):** GPU FDT (D_par 1.11676e-1, D_rot
1.89712e1), deflection (ratio 0.99831, τ 0.9920), chain (max gap 0.04262 µm) all reproduce their
pre-inc-3 numbers exactly. Device-resident: grid built on-device each step; host reads only at sampled
validation steps (UNDER_DEMAND). No bail-out triggered. Ready for inc 4 (motors) to add the first
narrow-phase consumer on the body view.

## 2026-06-13 — Pre-inc-3 interlude: CPU validation runner + device-agnostic invariant
A debugging/validation interlude before the broad-phase. Stood up a **sequential CPU runner that
executes the SAME system methods** (no TaskGraph) as a second runner, and recorded the
device-agnostic invariant in CLAUDE.md. **One physics implementation, two runners** — NOT a second
engine. This is the CPU reference for triaging increment-3 bugs as physics-logic vs PTX-lowering.

**Invariant recorded** (CLAUDE.md → Architecture invariants): one physics implementation; each system
written once as a kernel method over the SoA arrays; the GPU TaskGraph is production, the same methods
run sequentially on the CPU as a debug runner; never hand-write a CPU/double reimplementation (that
recreates v1's two-sources-of-truth drift); stay single-precision, fix float problems with better
algorithms not a parallel double path.

**Audit — kernel/orchestration split (a finding: clean, NO refactor needed).** Confirmed every system
body is a plain static method over `FloatArray`/`IntArray`/primitives with `@Parallel` loops, and
contains **zero** TaskGraph-only constructs — no `TaskGraph`/`WorkerGrid`/`GridScheduler`,
no `DataTransferMode`/`FIRST_EXECUTION`/`UNDER_DEMAND`. All orchestration (transfers, per-task worker
grids, block-size-64 launch config) lives in the harness's `build*Plan` methods, never in a kernel.
Systems checked: `BrownianForceSystem.brownianForce`, `RigidRodLangevinIntegrationSystem.integrate`,
`DerivedGeometrySystem.derive`, `ChainBendingForceSystem.zeroAccumulators/chainForces`,
`DragTensorSystem.run` (host init), + the deflection support kernels
`DeflectionSupport.seedAccumulators/pinEndpoints`. The architecture the invariant asserts was already
in place; the runner is the proof. (`@Parallel` is a marker annotation with no effect outside Tornado
compilation, so a direct call runs the loop sequentially as plain Java.)

**Runner abstraction (`Stepper`).** Added a 2-method `Stepper` interface in `DiffusionHarness`:
`execute()` (one step) + `pull(arrays...)` (device→host at output cadence). `GpuStepper` wraps the
existing `TornadoExecutionPlan.withGridScheduler(sched).execute()` and `res.transferToHost(...)`;
`CpuStepper` runs a `Runnable` that calls the same system methods in the same per-step order, with
`pull()` a no-op (host arrays ARE the truth). Three CPU step sequences mirror the three TaskGraphs
exactly: FDT `brownian→integrate→derived`; deflection `seed→chain→integrate→pin→derived`; chain
`zero→brownian→chain→integrate→derived`. `-cpu` flag selects the runner. **GPU production path
untouched** — `cpu=false` issues the identical TaskGraph calls in the identical order, and the GPU
numbers below match the pre-change baseline exactly.

**GPU≡CPU agreement (same N/M/seed; float32 last-bit tolerance):**

| check                | quantity                | GPU         | CPU         | delta             |
|----------------------|-------------------------|-------------|-------------|-------------------|
| FDT (N=2048, M=8000) | D_trans_par (µm²/s)     | 1.11676e-1  | 1.11676e-1  | 0 (to 6 sig figs) |
|                      | D_trans_perp y / z      | 7.36203e-2 / 7.53293e-2 | 7.36203e-2 / 7.53293e-2 | 0 |
|                      | D_rot_perp (rad²/s)     | 1.89712e+1  | 1.89712e+1  | 0 (to 6 sig figs) |
| static deflection    | ratio obs/analytic      | 0.99831     | 0.99832     | 1e-5 (5th decimal)|
|                      | τ_meas / τ_theo         | 0.9920      | 0.9920      | 0                 |
| free chain (16 seg)  | joint-gap max (µm)      | 0.04262     | 0.04262     | 0                 |
|                      | mean gap mid→late (µm)  | 0.01397→0.01376 | 0.01397→0.01376 | 0           |
|                      | end-to-end / bend-RMS   | 2.785 µm / 2.02° | 2.785 µm / 2.02° | 0          |

Agreement is bit-identical to printed precision on FDT and the chain; the lone visible divergence is
the deflection ratio's 5th decimal (0.99831 vs 0.99832) — exactly the expected fma/transcendental
ordering difference, not a logic divergence. The integer Wang-hash RNG is bit-for-bit identical on
both paths, so the only source of difference is float op ordering, which stays sub-ulp on the
aggregate statistics even after 10⁴–10⁵ steps. **Every system proven dispatch-agnostic; the invariant
demonstrably holds.** No bail-out triggered.

Open: `runViz`/`measureLp` still GPU-only (not part of the 3 validation checks; both reuse systems
already covered by FDT+chain, so coverage is complete). `Stepper.pull` varargs emits one benign javac
warning (passthrough of `FloatArray[]` to the varargs `transferToHost`).

## 2026-06-13 — Increment 2b: filament characterization toolkit (manual tuning)
Ported v1's filament-characterization MEASUREMENT side (deflection ratio, relaxation time τ,
persistence length Lp) as a manual-tuning instrument + the BRotCoeff fidelity fix. **The auto-tune /
coefficient-search loop was deliberately NOT ported** (v1 DeflectionTuner*/the `eitherTunerActive`
block — cleanly separable; left in v1 for a later decision). Lp and τ are instruments validated
against v1's *measurement*, not biological-target gates.

**BRotCoeff fidelity fix.** v1 applies rotational Brownian only to chain-end segments (≥1 free end)
scaled by BRotCoeff=0.5 (interior=0; FilSegment.moveThing:633-642, `if(!filAtEnd1|!filAtEnd2)`,
Env.java:583). v2 chain/Lp paths now use `interior?0:BRotCoeff` (was 1.0) — completes the 2a interior-
vs-end correction. Free chain bend RMS 3.54°→1.98° (less end jitter, matches v1's appearance). Static
deflection ratio unaffected (Brownian off) and **inc-1 FDT still PASS** (it uses bare 1.0, not
Constants.BRotCoeff) — both re-verified.

**τ (DeflectionSupport / -deflect).** Load → steady → release (counts[3]=0 gates extForce in
seedAccumulators, no buffer re-upload) → 1/e crossing of the decay (log-interpolated) = τ_meas;
τ_theo = N·ζ_perp·span³/(EI·π⁴), ζ_perp=midSeg bTransGam.y (port of v1 BoxOfActin.java:2933). Result:
τ_theo=0.05697 s (v1 prints 0.057 — exact, same formula); τ_meas=0.05652 s, **τ_meas/τ_theo=0.992**.

**Lp (-lp / measureLp).** Port of v1 accumulateLpData + computeLpMeas: free Brownian 539-seg/48-µm
chain (matches v1 testLpFilLength=48, monomerCt=32 — both `static final`, so v2 must match), tangent
correlation C(k)=⟨u_i·u_{i+k}⟩ EWMA(α), Lp=−1/slope of weighted (w=C²) log-fit over C_k>0.01.

**Unified entry point (-characterize):** one command → `{deflection ratio, τ_meas/τ_theo, Lp_meas}`
for the current coeffs (override fracR/fmt via flags, BRotCoeff via Constants). ~40 s. Example output:
ratio 0.9983, τ_meas/τ_theo 0.9920, Lp_meas 1441 µm.

**Cross-validation vs v1 (fixtures/filament_characterization_v1.md):**
- ratio: ≤0.05% across fracR (TIGHT, from the deflection-benchmark session).
- τ: τ_theo exact; τ_meas/τ_theo=0.992. (v1's τ_meas needs an interactive force-release, not headlessly
  capturable; the deterministic relaxation is pinned by the ≤0.04% static-ratio match.)
- Lp: the **C(s) measurement reproduces v1 to <0.05%** (C(1) 0.9987 vs 0.9989, C(538) 0.7366 vs 0.7370
  at fmt=0.265 — proving instrument + physics faithful). BUT the **scalar Lp_meas is ill-conditioned**
  (v1 785 µm vs v2 1441 µm, ~2×): the uncalibrated chain is far stiffer than its 48-µm contour, so C
  barely decays and 1/slope is noise-dominated — intrinsic to the metric (present in v1; why v1 has the
  auto-tune and treats Lp as a diagnostic). NOT a port bug (C(s) match proves it); NOT bailed. Lp is a
  faithfully-ported diagnostic, not a tightly-reproducing scalar at uncalibrated coeffs.

So: ratio + τ are tight quantitative cross-checks; Lp's C(s) is tight, its scalar is an honest
ill-conditioned diagnostic. Manual-tuning instrument complete; auto-tune deferred.

Open / next: increment 3 (spatial grid + broad-phase). Still deferred: the auto-tune loop (planner
decision), and whether to expose aeta/segment-length as -characterize flags (currently fracR/fmt only).

## 2026-06-13 — Deflection benchmark: v2 ≡ v1 force/torque coding (+ low-fracR float32 fix)
Validated the 2a chain force law against v1's deflection benchmark, settled a fracR-direction
puzzle, and found+fixed a float32 precision limit at very low fracR (stiff filaments). This is the
foundation of 2b (pins + load); the full ratio/τ/LP fixture is still 2b.

**Setup (replicates v1 -bmDiag exactly).** `softbox/DeflectionSupport.java` (seedAccumulators puts the
load on the midpoint forceSum; pinEndpoints does v1's `incCoord(anchor-endpoint)` hard endpoint
snap-back each step -> pinned-pinned, free rotation) + `runDeflection` (-deflect flag). 11 seg ×
32-mon (segLen 0.0891 µm, span 0.9801), Brownian off, F = 48·EI·frac/span² on the midpoint center,
EI = kT·Lp (Constants.EI, Lp=15 µm), frac=0.01. v1 built read-only to /tmp/v1classes (worktree never
touched). Measured obs = perpendicular distance of the midpoint center from the anchor line, averaged
over the converged 2nd half (jitter quantified — both v1 and v2 are steady, ≤1.3% pk-pk at default
coeffs; jitter is parameter-dependent in general).

**fracR direction — RESOLVED: bigger fracR = softer (jba was right).** v1 deflection ratio rises
0.392→0.998→2.190→2.777 as fracR 0.025→0.1→0.4→0.8 (the loaded benchmark; v1's Env.java:135 "bigger
= stiffer" comment is misleading). The earlier free-chain sweep looked flat/opposite ONLY because
interior rotational Brownian (the 2a-FIX bug) swamped the fracR signal; post-fix the free chain
softens with fracR too (v2 3.50°@0.1 → 6.23°@0.8), matching v1's free LP chain (2.71°→9.83°). No sign
error — fracR enters only via the (byte-identical) F3 lever torque; in a *free* chain its effect is
weak (link forces are tiny without a load), strong under *load*.

**Identical-coding proof + low-fracR float32 limit + fix.** v2 reproduces v1's deflection ratio:
| fracR | v1 | v2 acos(dot) | v2 asin(\|cross\|) poly |
|---|---|---|---|
| 0.025 | 0.39198 | 0.40038 (2.1%) | **0.39184 (0.04%)** |
| 0.1 | 0.99842 | 0.99986 (0.14%) | **0.99831 (0.01%)** |
| 0.4 | 2.19003 | 2.19046 (0.02%) | **2.18990 (0.006%)** |
| 0.8 | 2.77652 | 2.77681 (0.01%) | **2.77639 (0.005%)** |
With the original `acos(dot)` bending-angle calc, v2 matched v1 to ≤0.14% for fracR≥0.1 but drifted
to 2.1% at fracR=0.025 — a real, converged gap growing as fracR→0. Root cause: **float32 catastrophic
cancellation in acos(dot)** for small joint angles (cos t = 1 − t²/2, so the angle lives in the
cancelling 1−dot part; ~half the digits lost). Fix (`ChainBendingForceSystem.angleFromSinCos`):
recover the angle from `|cross| = sin t ~ t` (first-order, float32-safe) via a hand-rolled
`accurateAsin` (Taylor seed + 2 Newton passes — PTX has no Math.asin/atan2, same reason v1 hand-rolled
accurateAcos; verified Math.atan2 throws "unimplemented" on the PTX backend). Hybrid: asin(|cross|)
for small angles (|cross|≤|dot|), accurateAcos(dot) mid-range. Result: low-fracR gap 2.1%→**0.04%**,
and it tightened every other point + killed the residual jitter. So the force/torque CODING is
identical to v1 (≤0.04% across the loaded range); the prior low-fracR drift was float32, now mitigated.

**Why it matters going forward (jba):** stiff filaments — microtubules (Lp ~ 1–6 mm, ~100× actin) —
live in the small-joint-angle regime where acos(dot) float32 breaks down. The asin-polynomial keeps
the angle accurate ~100s× stiffer before float32 bites, without going to a full double-precision
pose. Kept as the default (a v2 numerical-robustness improvement over v1's plain acos; mathematically
the same angle). Free-chain connectivity + FDT re-verified PASS after the change.

Open / next: ready to move on to the **next BoA→SoftBox port**. Still-open 2b items: the full
deflection ratio/τ + LP/persistence-length fixture, and the `BRotCoeff=0.5` end-segment rotational
Brownian calibration (v2 currently uses 1.0).

## 2026-06-13 — Increment 2a FIX: smooth bend (interior rotational Brownian)
jba reported the chain bent with a visually "not smooth" awkwardness. Root cause: the harness set
`brownRotScale = 1` for **every** segment, so interior segments each got an independent rotational
Brownian kick — rotating segment k+1 opens joint k and closes joint k+1, making **adjacent joints
bend in opposite directions (zigzag)**. v1 deliberately gates this: `rScale = (filAtEnd1 &&
filAtEnd2) ? 0 : rs` ("only apply brownian torques to end filaments.. best matches expected angular
correlations"). Fix: rotational Brownian only on chain-end segments (≥1 free end); interior segments
reorient only via the deterministic chain torques responding to (collective, smooth) translational
Brownian. Objective confirmation — adjacent-joint bend-vector correlation: **−0.157 (zigzag) →
+0.652 (smooth arc)**; bend RMS 9.6°→3.5°, end-to-end/contour 0.984→0.992; connectivity still PASS,
FDT free-rod path unchanged. (The 3.5° vs WLC 8.76° gap is a Brownian-magnitude/fracMoveTorq
**calibration** matter for 2b, not a 2a smoothness/connectivity concern.)

Also added diagnostics this session: `-dt <s>`, `-fracR <v>`, `-fmt <v>` overrides and an RMS-bend
stiffness readout. Sign audit (prompted by jba): the F3 lever (end2 `+uVec`, end1 `−uVec`) and F4
torsion (both ends) in `ChainBendingForceSystem` are **byte-identical to v1's device
`chainPairForcesKernel`** (and agree with v1 CPU) — no porting sign error. Sweeps: decreasing
fracMoveTorq softens (19.9°@0.05 → 7.7°@0.6) as expected (F4 restoring, confirmed); fracR has a weak,
non-monotonic effect on free-chain bending (min near 0.4) — note this is the opposite of "increasing
fracR softens"; v1's own Env.java comment says "bigger numbers are stiffer", so flag for jba whether
v1's fracR convention is intended (its calibrated role is the pinned deflection test = 2b, not the
free thermal chain).

## 2026-06-13 — Increment 2a: linked filament chain (connectivity first) — PASS
Activated the inert `end1Nbr*/end2Nbr*` topology (no storage reshape) and ported v1's real PAIRS
chain force law. A free Brownian chain holds together as a connected, semiflexible filament.
**Deflection assay (ratio/τ) and persistence length are deliberately deferred to 2b** — this increment
gates only on connectivity (visual + joint-gap), not calibration.

- **Force law ported:** v1 **device** kernel `GPUMoveThing.chainPairForcesKernel`
  (`GPUMoveThing.java:1551-1896`) — F3 link spring + F4 bending/torsion — into
  `softbox/ChainBendingForceSystem.java`, cross-checked against the CPU reference
  `FilSegment.addLinkForces`/`addTorsionSpringForces`. Ported the device version because it is
  already the per-segment, self-write, read-only-neighbor, NO-atomic kernel: each joint is computed
  from both segments' perspectives, **owner = lower slot index** defines the canonical link direction
  so the two are exactly anti-parallel (Newton-3); each segment applies +F (owner) / −F (non-owner)
  to its OWN slot only. `accurateAcos` ported verbatim. Internals double (as v1), pose read float,
  forceSum written float. Lab-frame forces → forceSum/torqueSum; integration transforms lab→body.
- **Side decode (the A1 trap) — mapping + verification.** `end?NbrSide==0` → my end glued to
  neighbor's **end1** (tip = ncoord − L/2·nu); `==1` → neighbor's **end2** (+L/2·nu). Matches v1
  `FilSegment.setEnd*Links:2818-2832`. Chain wired head-to-tail: my end2→next.end1 (side 0), my
  end1→prev.end2 (side 1), sentinel −1 at the two free ends. Verified THREE ways: (1) code-level check
  of the wired side values vs v1's derivation (OK); (2) runtime joint-continuity gap stays bounded;
  (3) **negative control** — deliberately flipping the side flags makes the gap diverge to 0.20 µm
  (>0.5·segLen) and the chain collapse (end-to-end/contour 0.16), which the test correctly FAILS. So
  the bounded PASS is meaningful, not trivial.
- **TaskGraph order:** `zero accumulators → brownian + chain (fill; independent/self-only writes) →
  integrate (reads forceSum+randForce) → derived (refreshes end1/end2 for next step's chain reads)`.
  Chain forces at step N read step-(N−1) derived geometry, as in v1. `zeroAccumulators` is a new first
  task (forceSum/torqueSum are now written, so they must be cleared each step).
- **Force-coverage audit** (each force applied exactly once):
  | source | frame | path | applied |
  |---|---|---|---|
  | Brownian randForce/randTorque | body | BrownianForceSystem writes → integration reads | once |
  | F3 link spring | lab | ChainBendingForceSystem self-write `+=` → integration reads | once / joint / segment |
  | F4 bending/torsion | lab | same | once / joint / segment |
  Action-reaction: for a joint (i,j), both threads compute the SAME owner-perspective `linkUVec` from
  the same geometry, so segment i gets +F and j gets −F (equal-and-opposite); each writes only its own
  slot → no atomics, no double-count. F4 torsion likewise (+/− across the pair by the side-consistent
  cross products).
- **Validation (16-segment free chain, monomerCt=64, segLen 0.1755 µm, 40 000 steps, fracMove=0.5,
  fracR=0.1, fracMoveTorq=0.265, aeta=0.1, filTorqSpring inactive → damped F4):** side-decode OK; max
  joint-continuity gap **0.0685 µm**, bounded (<0.5·segLen=0.0878) and **stationary** (mean
  0.0223→0.0238 µm, no growth over 4 s); no NaN; segment count conserved. The equilibrium joint
  "breathing" is ~0.022 µm thermal (≈8× actinMonoRadius — actinMonoRadius is just the spring's
  link-point offset, not the thermal amplitude). **Visually connected + semiflexible.** Bonus sign:
  end-to-end/contour = 0.98, matching the wormlike-chain value for L=2.8 µm at Lp=15 µm (v1's
  persistence length) — bending stiffness already in the right regime, though calibration is 2b's job.
- FDT free-rod path (inc 1) re-verified **unchanged** (−2.52/−1.15/+0.08/−1.80 %, PASS). Adding
  `chainParams` to FilamentStore is additive (not in the FDT graph). `view_run.sh`-style watch:
  `./run_gpu.sh -chain <dir> [nSeg [M]]` dumps frames + reports the gap; `threejs_chain*/` gitignored.

Open / next: increment **2b** — pins + midpoint force + the deflection ratio/τ (and LP) fixture, layered
on this already-correct chain force law.

## 2026-06-13 — Increment 1.5: file-based Three.js frame output (watch the rods)
Output-only — get eyes on the sim before chain/bending. Ported v1 `ThreeJSWriter`'s `segments`
emission into `softbox/FrameWriter.java` (a host IO utility, not a device system) and reuse the v1
viewer + server **verbatim** (`sim_server.py`, `sim_viewer_boa.html` copied unchanged, md5 confirmed).

- **Schema** (`segments` only, per constraint): `{"frame":N,"t":T,"bounds":{xDim,yDim,zDim},
  "segments":[{"id","end1":[x,y,z],"end2":[x,y,z],"r","notADPRatio":1.0,"cofilinCount":0}]}`,
  files `frame_%06d.json`, output-dir auto-increment (`.NNN`) ported from v1. Verified in the viewer
  JS that `myosins`/`minifilaments`/`nodes`/`contractility` are all `if(...)`-guarded and `bounds` is
  optional — a segments-only frame renders with **no viewer modification** (no empty arrays needed).
  `r = actinWidth/2 = 0.0035 µm` (Constants.radius), as v1. Per-segment JSON is one method so a future
  generic "bodies+links" schema is a localized swap (deferred to the planner, pre-motors).
- **end1/end2** are the derived geometry (end1 = coord − L/2·uVec, end2 = coord + L/2·uVec — same
  formula as `DerivedGeometrySystem`). FrameWriter reconstructs them on the host from the
  already-pulled canonical pose (coord+uVec) + segLength, so the output path adds **no device
  transfer** beyond the harness's existing output-cadence `coord/uVec` `UNDER_DEMAND` pull.
- **Bounds:** fixed cube, side = 2·(clusterHalf + 5·√(2·D∥·T_total)) — sized to ~5σ of the expected
  diffusive spread over the run; framing only, not physics (free rods have no walls). Viewer builds
  the box from frame 0.
- **Wiring:** `-3js <dir>` flag in `DiffusionHarness`. Present → a dedicated viz run (default N=200 in
  a compact 0.3-µm cluster, random orientations, both Brownian components ON at bare amplitude),
  frames written at the existing output-cadence pull. **Absent → FDT path byte-for-byte unchanged**:
  re-ran `./run_gpu.sh`, got the identical inc-1 numbers (−2.52 / −1.15 / +0.08 / −1.80 %, PASS).
- **Verified render-ready:** `./view_run.sh 200 4000` → 201 frames, 0 non-finite coords across
  241 200 values, segment length 0.1755 µm, midpoint spread grew isotropically 0.17→0.32 µm std
  (anisotropy 1.05); the magnitude matches FDT (effective isotropic D≈0.088 µm²/s ⇒ predicted final
  std 0.317 vs measured ~0.32). `sim_server.py scan_runs` detects the folder (201 frames). Frame
  output is gitignored (`threejs_output*/`).

Open / next: increment 2 (actin chain / bending forces) — unchanged from inc-1's note. The generic-vs-
per-type frame schema decision is deferred to the planner, before motors land.

## 2026-06-13 — Increment 1: filament rigid-rod overdamped Langevin slice — FDT PASS
First real code. `softbox/` package: SoA component-array core + four named systems as TornadoVM PTX
kernels, validated against the fluctuation–dissipation (Einstein) relation on the aorus RTX 5070.
Built/ran with the v1 toolchain (Java 21 + TornadoVM 4.0.1-dev PTX, `--enable-preview`, `-g`,
`@tornado-argfile`). No chain/bending forces, neighbors, walls, motors, membrane, or biochem.

**Component-array layout (FilamentStore — the canonical store).** Pose `coord`/`uVec`/`yVec`;
derived (recomputed, NOT source of truth) `zVec`/`end1`/`end2`; geometry `monomerCount`→`segLength`;
body-frame diagonal drag/diffusion `bTransGam`/`bRotGam`/`bTransDiff`/`bRotDiff`; per-step
accumulators `forceSum`/`torqueSum` (deterministic, zeroed) + `randForce`/`randTorque` (Brownian);
per-rod `brownTransScale`/`brownRotScale`; inert chain topology `end1NbrSlot/Side`,`end2NbrSlot/Side`
(sentinel −1 = free; integer (slot,side) from birth per migration-doc A1, ready for increment 2 to
read without reshaping).

**FLAGGED layout decision — planar SoA, not one-array-per-component.** TornadoVM's `task()` tops out
at 15 args (`TornadoFunctions$Task15`); the integration kernel needs ~27 component planes, so strictly
separate `xPos[]`/`yPos[]`/`zPos[]` FloatArrays are impossible to launch. Each vector quantity is one
device buffer in planar `[X-plane | Y-plane | Z-plane]` layout (x's contiguous, then y's, then z's) —
genuine SoA (coalesced, non-interleaved), NOT AoS `[x0 y0 z0 x1…]`. Named `coordX/Y/Z()` plane
accessors keep each component findable. The architectural invariant (no per-object fields, no AoS
interleave, device-resident) holds; the packing is a device-arity accommodation only.

**Four systems (free functions over arrays; each one identifiable physics).**
1. `DragTensorSystem` (host, runs once) — ports `FilSegment.calculateProperties()` line-for-line.
2. `BrownianForceSystem` (device) — fills `randForce`/`randTorque` body-frame from diffusion tensors +
   the REUSED v1 device RNG (Wang hash + Box-Muller, keyed `(slot,stepCount,runSeed)`; verbatim from
   `GPUMoveThing.moveThingKernel`/`wangHash`, per RESIDENCY_INVENTORY §3 — no new RNG invented).
3. `RigidRodLangevinIntegrationSystem` (device) — overdamped Euler: body force = Rᵀ·forceSum +
   randForce → `bVeloc = 1e6·bForce/bTransGam`; body torque → `deltaBAng` rotation of uVec/yVec;
   port of `FilSegment.moveThing()` fused with the v1 device integration, minus inlined Brownian.
4. `DerivedGeometrySystem` (device) — recompute `zVec`/`end1`/`end2` and re-orthogonalize `yVec`
   (port of `Thing.recomputeDerivedSoA`). Output only this increment.
All four run as kernels over resident arrays in one TaskGraph (brownian→integrate→derived); pose is
FIRST_EXECUTION + pulled UNDER_DEMAND only at output cadence — **no per-step host pose pull**.

**Force-coverage audit (this slice has exactly two force sources).**
| force source | path | applied | notes |
|---|---|---|---|
| deterministic `forceSum`/`torqueSum` (lab) | init 0; no kernel writes it | genuinely **zero** | free rod: no chain/wall/motor/node forces exist yet |
| Brownian `randForce`/`randTorque` (body) | `BrownianForceSystem` writes once/step; `RigidRodLangevinIntegrationSystem` reads once | **exactly once** | no double-count; a 2× would give 4× D (+300%), not the observed ∓2% |
Verdict: every force applied on exactly one path — never zero-by-accident (the zero is real), never
twice. The FDT pass at the bare amplitude confirms no missing/extra factor.

**Code-fidelity cross-check (γ formula, code-level not run).** `DragTensorSystem.run()` reproduces
`FilSegment.calculateProperties()` (v1ref `FilSegment.java:420-441`) arithmetic byte-for-byte:
`bTransGam.x=(2πη·Lₘ)/(ln(Lₘ/2rₘ)+aParallel)`, `.y=.z=(4πη·Lₘ)/(…+aOrthog)`, `bRotGam.x=4πη·rₘ²·Lₘ`,
`.y=.z=(πη·Lₘ³)/(3(…+aTurning))`, then Einstein `D=kT/γ`; same min-length clamp (`stdSegLength·halfmono`
for a free/at-end rod), same `length=(monomerCt+1)·actinMonoRadius`, same constants (Boltz, tempK,
aeta=0.1, aParallel/aOrthog/aTurning). Only diff: SoftBox reads `Constants.*` where v1 reads
`Env.*.getValue()` (identical values). FDT self-consistency + faithful γ together pin both the
amplitude→drag coupling and the tensor values.

**Measurement protocol.** N=8192 free rods, monomerCt=64 (L=0.1755 µm), dt=1e-4 s, aeta=0.1 Pa·s.
- Config T (translational anisotropy): rotational Brownian OFF, orientation frozen along lab-x so body
  axes ≡ lab axes; M=20000 steps, pose pulled every 200; per-axis MSD slope through origin → D = slope/2.
- Config R (rotational): translational Brownian OFF; M=4000, uVec pulled every 20; orientational
  autocorrelation C(t)=⟨uₓ(t)⟩ fit to exp(−2D_rot·t) over C∈(0.2,0.95) (22 samples).
Both B-coefficients set to 1.0 so the bare relation D=kT/γ holds; v1's production BTransCoeff=1/
BRotCoeff=0.5 (and the lone-segment rot-Brownian-off rule) are biological persistence-length tuning
knobs, deliberately out of scope for the amplitude-coupling check.

**Validation numbers (measured vs FDT prediction from the SAME γ arrays; tol 5%).** γ_par=3.649e-08,
γ_perp=5.430e-08 N·s/m; γ_rot⊥=2.211e-22 N·m·s/rad.
| quantity | measured | FDT D=kT/γ | relErr |
|---|---|---|---|
| D_trans∥ (µm²/s) | 0.10996 | 0.11280 | −2.52% |
| D_trans⊥ y (µm²/s) | 0.07494 | 0.07581 | −1.15% |
| D_trans⊥ z (µm²/s) | 0.07587 | 0.07581 | +0.08% |
| D_rot⊥ (rad²/s) | 18.280 | 18.615 | −1.80% |
**FDT VALIDATION PASS.** Tolerance 5% justified a priori: float32 (v1 GPU path precision) + ~1/√N
ensemble noise + O(D·dt) first-order-Euler bias. The small consistent negative bias (≈−1 to −2.5%) is
that Euler/float32 systematic, not a wrong factor (which would be tens of %). Per Lesson 6 we did NOT
reach for double precision — the magnitude argument says a wrong integration factor is far likelier
than float rounding moving D by a measurable amount, and here the factor is right.

Two TornadoVM gotchas worth recording: (1) a kernel method literally named `kernel` collides with an
OpenCL/PTX reserved token — rename (we use `brownianForce`/`integrate`/`derive`); (2) the default
block size overflows the register file for these (RNG-/trig-heavy) kernels → CUDA 701
(LAUNCH_OUT_OF_RESOURCES) — set an explicit `WorkerGrid1D` localWork=64 via a `GridScheduler` keyed
`"rodLangevin.<task>"` (matches v1 `MOVE_KERNEL_BLOCK_SIZE`). Run log: `RUN_LOGS/2026-06-13_inc1_fdt.log`.

Open / next: increment 2 — actin chain / bending-force system. Starts READING the inert
`end1Nbr*/end2Nbr*` topology arrays laid down here (no storage reshape). The derived end1/end2
sign convention (end1 = coord − L/2·uVec) is now the one chain forces will read.

## 2026-06-13 — Increment 0: workspace scaffolded
Soft Box repo initialized at `~/Code/SoftBox` as a new repo (not a BoA branch). Frozen-v1 reference
set up as a read-only `git worktree` at `~/Code/BoA-v1ref`, detached at tag
`softbox-filref-2026-06-13` (pinned at v1 `main` HEAD; will re-point to `biology-production-v1` once
the v1 finish line is reached). `CLAUDE.md` seeded with the architecture invariants (integer-ID
entities, SoA component arrays as source of truth, systems as free functions, device residency from
day one), the reference/oracle discipline (fixtures frozen as data, not v1 code; read current main
for physics; reconciliation on v1 physics changes), the porting discipline (force-coverage audit,
minimal-repro first), and the proposed increment sequence (filament slice first). No physics yet.

Open / next: increment 1 — planner to design the FilSegment component-array layout + rigid-rod
Langevin integration system, with the v1 deflection / relaxation / LP benchmarks as the fixture.
