# Fine-timestep velocity-clamp architecture and performance map

This map was written before implementation changes on branch `dt-convergence-study` at
`df310f5aa5cfd2ced851ae7ea01014d20e179ca8` (2026-07-12). It describes executed code, not just the older
reports. Measured percentages and allocation evidence are reported separately in `PERFORMANCE_BASELINE.md`.

## Entry points and canonical configuration

The simulation entry point for gliding and force-velocity work is `softbox.GlidingHarness.main`. The wrapper
`scripts/run_gliding.sh` starts Java 21 with the TornadoVM PTX module path, Parallel GC, a 6 GiB heap, and
`-Dtornado.tvm.maxbytecodesize=65536`. Despite that wrapper, `-vclamp` dispatches directly to
`runForceVelocity`, which calls the sequential CPU `stepOrig` and never builds or executes a Tornado task graph.
The CPU path is intentional: it is the deterministic basin arbiter and the clamp overwrites the filament pose
on the host every step.

The canonical model is assembled by the field defaults plus the resolution block in
`GlidingHarness.main:339-359`:

- motor: `SPHEREHEAD + AXLOCK + DIRSWING`;
- constraint interpretation: fixed springs (`PAIRS_SPRINGS + ALIGN_SPRINGS + STRUCT_SPRINGS`);
- cross-bridge translation: coupled head-and-site backward-Euler star (`XB_IMPLICIT2`);
- kinetics: `LYMN_TAYLOR`, with the welded ADP.Pi-only bind gate;
- force law, rates, ordering, RNG keying, timestep semantics, and defaults are the scientific invariants of this
  study.

The Java boolean named `CANONICAL` is a separate phase-2 two-point motor experiment and remains false here; it
must not be confused with the ratified default physical stack above. Therefore the production clamp uses
`bindNearest` and the parameter-selected SPHEREHEAD/AXLOCK `bondForces` branch, not
`bindCanonicalTwoPoint`/`bondForcesCanonical`.

`-density` and `-coltol` are experimental inputs rather than canonical constants. The current powered V0 study
uses the compact clamp bed selected by `-vclamp`, `-matbox 50`, density 2,000 motors/um2, and the default
6 nm capture radius. That creates 2,400 motors over a 6.0 x 0.2 um bed and an 11-segment (~2 um) filament.

Relevant drivers are:

- `scripts/run_fvtest.sh`: force-velocity sweeps;
- `scripts/run_fvB_clean.sh`: one-episode Variant-B confirmation;
- `scripts/run_servo_v0_ladder.sh`: the original timestep ladder;
- the pre-existing untracked `scratch_v0_runpt.sh` and `scratch_v0_analyze.py`: the currently powered 8-seed,
  constant-80-ms V0 reference study in `RUN_LOGS/v0fine`.

## State layout

The source of truth is structure-of-arrays primitive storage, using TornadoVM `FloatArray` and `IntArray`.

| Store | Important state | Size in the clamp case |
|---|---|---:|
| `FilamentStore` | segment center and frame, derived ends, drag, Brownian, force/torque, chain topology | 11 segments |
| `MotorStore` | one nucleotide/binding record per motor, bind arc, force history, implicit buffers | 2,400 motors |
| `RigidRodBody` inside `MotorStore` | rod, lever, head center/frame/drag/force/Brownian arrays | 7,200 bodies |
| `Scene.bondData` | 13 floats per motor: head force/torque, segment reaction, axial load | 31,200 floats |
| reach arrays | up to `SpatialGrid.MAX_CAND` segment IDs per motor plus count | motor-scaled |
| bound-segment inverse | count/offset per segment and stable motor IDs | 11 + motors |

`coord`, `uVec`, and `yVec` are canonical pose. `DerivedGeometrySystem.derive` reconstructs `zVec`, `end1`, and
`end2` and re-orthogonalizes the frame.

## One complete canonical CPU timestep

`GlidingHarness.stepOrig` is the authoritative order. Its order must not change.

1. **Step/RNG counters.** `MotorStore.setCounts(t, seed, nSeg)` and the filament step counter are updated.
   Stochastic draws are Wang hashes keyed by slot, outer step, run seed, and a subsystem salt; there is no mutable
   shared RNG stream.
2. **Head publication.** `MotorStore.publishHeadFromBody` publishes each articulated head tip and rod/head axes.
3. **Binding search.** `BindingDetectionSystem.bruteReachable` scans segments in ascending slot order for every
   motor and writes all exactly eligible segments in that order. It applies the v1 end-range, perpendicular
   distance, head-alignment, and rod-direction predicates.
4. **Release/cycling and attachment.** On the canonical Lymn-Taylor route there is no separate catch-slip release
   kernel. `bindNearest` first attaches a free, primed ADP.Pi head to the nearest reachable segment, preserving
   candidate order and storing the projected material-site arc. Then `cycleLymnTaylor` performs exactly one
   per-motor nucleotide draw: NONE -> ATP, ATP -> ADP.Pi, ADP.Pi -> ADP, or load-modulated ADP -> NONE. A bound
   head that ends in ATP detaches and enters the physical-time refractory state. The load is the preceding
   timestep's registered `forceDotFil`.
5. **Motor force reset and Brownian.** Motor-body deterministic force/torque arrays are zeroed.
   `BrownianForceSystem` creates three Box-Muller pairs for every rod, lever, and head, using the step-keyed Wang
   hashes and FDT amplitude `sqrt(2 kT gamma / dt)`.
6. **F8/F9/F10/AXLOCK, joints, and anchor.** `MotorJointSystem.joints` builds J2 and J1 connection forces;
   DIRSWING disables the old J1 angular converter. `TailAnchorSystem.anchor` tethers each rod end to its fixed bed
   point. `CrossBridgeSystem.bondForces` evaluates bound motors once: Hookean F8 at the stored actin site, F9's
   frozen 90-degree sphere-head constraint, and F10 retargeted by AXLOCK to the bed-defined axial swing plane.
   It stores equal-and-opposite head/segment data and the axial motor-side load.
7. **DIRSWING and head force.** `applyHeadForce` gathers each motor's F8/alignment result into its own head body.
   `directedSwing` applies the nucleotide-dependent, polarity-directed 0-to-60-degree converter couple to lever
   and head. Spring mode scales its per-step coefficient by `dt/refDt`, preserving fixed stiffness.
8. **Motor integration.** For coupled implicit F8, head centers are snapshotted. The explicit overdamped
   Langevin update integrates all motor bodies and `derive` rebuilds their frames and ends.
9. **Load registration.** `registerForceDot` publishes the current F8 axial load and magnitude and advances the
   ten-sample force history. These values are read by the next timestep's chemistry.
10. **Filament/joint/anchor forces.** Filament accumulators are zeroed; filament Brownian and PAIRS chain link,
    bend, and torsion forces are built. In a clamp run filament Brownian scales are zero, but the same kernel is
    still invoked.
11. **Cross-entity gather.** Three stable serial CSR passes invert `boundSeg` without atomics; `segGather` sums
    segment-side F8 reactions in motor-index order. Optional containment follows.
12. **Filament integration and coupled implicit correction.** Segment centers are snapshotted, the full explicit
    filament update and derive run, then `coupleComputeA -> coupleSolveSeg -> coupleCorrectHead` applies the
    closed-form per-segment coupled F8 star. Both motor and filament geometry are re-derived.
13. **Velocity source.** `runForceVelocity` overwrites every segment with the exact rigid +x template at
    `COM_x = bedCenter - v*dt*(t+1)` and derives geometry again. The within-step filament response is deliberately
    discarded; changing this would change the scientific clamp semantics.
14. **Measurement.** A host loop over every motor reads bound/reach state, F8 force, nucleotide, and articulated
    geometry. It accumulates `fbar_avail`, bound/reach counts, attachment/detachment counts, episode impulse and
    work, lifetime, stroke count, and the completion-angle readout. Optional stroke/episode diagnostics add
    deeper work only behind their flags. The run prints `FVROW`, `CMPLROW`, and `XBROW` after the loop; no
    per-step strings are formatted in the ordinary V0 route.

## Preliminary performance map

This is a call-volume hypothesis map to guide profiling, not a measured hotspot ranking.

| Region | Work per step in the 2,400-motor clamp | Category | Initial risk |
|---|---:|---|---|
| Brownian motor-body generation | 7,200 bodies; 18 hashes and 6 transcendentals per motor | physical kernel | very high CPU cost, scientifically sensitive |
| articulated joints + integration + derive | 3 bodies/motor, repeated square roots/trig/normalization | physical kernel | high CPU cost |
| reach search | 2,400 x 11 exact pair tests, including accepted-pair normalization | binding | moderate/high; all candidate/order semantics sensitive |
| nearest bind | eligible free heads re-test their reachable candidates | binding | lower volume but repeated predicate work |
| canonical Lymn-Taylor | 2,400 draws; catch exponentials only in ADP | kinetics | moderate; RNG/order sensitive |
| bond/AXLOCK/DIRSWING | all motors cleared, bound subset does full F8 and angular work | physical kernel | occupancy dependent |
| output measurement loop | 2,400 host records; completion `acos` for every measured bound-ADP head | analysis | potentially material and separable from physics |
| bound-to-segment CSR | three serial motor passes plus an 11-segment gather | object/plumbing | moderate; stable ordering is an invariant |
| filament chain/integration/implicit solve | only 11 segments | physical kernel | likely small in this clamp workload |
| startup/output formatting | once per JVM/run | logging | likely small for 8k-64k steps |

The fine-dt cost is almost exactly proportional to step count because physical simulation time is fixed:
8k/16k/32k/64k steps at 1e-5/5e-6/2.5e-6/1.25e-6 s. The clamp currently has no intra-run parallelism, so
multi-core throughput comes only from running independent seed/velocity JVMs concurrently. That is useful for a
ladder but does not reduce latency or CPU-hours per scientific point.

## Validation and benchmark assets

There is no conventional JUnit suite. Validation is executable harnesses plus exact output comparisons:

- `scripts/build.sh` compiles every source with Java 21 preview enabled;
- `scripts/run_motor.sh`, `run_motorbody.sh`, `run_xbridge.sh`, and `run_stroke.sh` cover deterministic motor,
  joints, F8/gather, cycle, and stroke cases;
- `run_gliding.sh` supplies the canonical short CPU gliding probe and `-gpu` cross-check;
- prior canonical-collapse gates compare `GRID_ROW` output before/after on CPU and GPU at 800 and 6,000 steps;
- broad-phase and binding harnesses compare exact reachable sets and CPU/GPU primitive buffers;
- the V0 route exposes its scientific checks in `FVROW`: force, availability/occupancy, attachment rate, impulse,
  episode count, lifetime, stroke rate, and work;
- episode-kernel mode has a force-impulse completeness identity, but it is a diagnostic rather than the ordinary
  powered ladder path;
- prior reports in `docs/` record byte-identity, convergence, and statistical gates. They are useful provenance,
  but the retained optimizations were re-run against fresh baselines from this commit.

## Scientific invariants for performance work

The following are protected even if profiling identifies them as expensive:

- exact canonical model and parameters;
- one outer step means one update at the supplied `dt`; no hidden substep or cadence change;
- binding eligibility, nearest-site rule, ascending candidate order, and stored bind arc;
- Wang-hash formulas, salts, number of draws, and step/slot/seed keying;
- nucleotide probabilities and force input vintage;
- force formulas, F8/F9/AXLOCK/DIRSWING targets, fixed-spring interpretation, and float/double conversions;
- force accumulation and full timestep ordering;
- stable motor-index ordering of the segment gather;
- clamp overwrite timing and prescribed position;
- default diagnostics and output values.

Optimizations that only remove proven redundant computation, allocation, or disabled-diagnostic work can target
Level 1 (byte-identical). Any floating-point reorder must be isolated behind a default-off flag and cannot be a
default recommendation without broader validation.
