# Explicit-S2 GPU Lowering Regression — Root Cause + Fix

**Authoritative regression report** (2026-07-23, branch `gpu-mat-bottlenecks-explicit-singlehead`). The dynamic
twirling report (`EXPLICIT_GLIDING_HELICAL_SURFACE_TWIRLING_FINDINGS.md` §18) stated the full explicit-S2 gliding
TaskGraph "does not lower on this machine — a pre-existing `matS2SolveStep` PTX fault." **That framing was wrong.**
The graph lowers and runs device-resident; the failure was a **launch-flag regression**: the run command omitted
`-Dtornado.enable.fma=false`. A compiler/runtime investigation, no physics changed.

---

## 1. Root cause (one line)

**The explicit-S2 solver `matS2SolveStep` FMA-lowers to an `ArithmeticLIRLowerable` NPE on the TornadoVM PTX
backend when `tornado.enable.fma=true` (the default). It is a KNOWN FMA lowering defect; the known-good launch
requires `-Dtornado.enable.fma=false`.** The regression was purely in the *launch command* — `scripts/run_explicit_twirl.sh`
(and the prior hand-run `-equiv`/`-gliding` commands) omitted `-Dtornado.enable.fma=false` and used
`-Dtornado.tvm.maxbytecodesize=16384` instead of the known-good `65536`. **No source, physics, chemistry, S2
mechanics, dt, or numerics changed.**

## 2. Known-good baseline

The device-resident explicit-S2 single-head gliding graph (`ExplicitCompleteMatHarness.buildGlidingGraph`) is
launched by **`scripts/run_singlehead_gpu.sh`** (the occupancy / single-head density-sweep production launcher):

```
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx8G \
     -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.ExplicitCompleteMatHarness -production-cell ...
```

The sibling HMM-dimer launcher `scripts/run_hmm_gpu.sh` uses the same `-Dtornado.enable.fma=false` (+
`maxbytecodesize=262144 -Dtornado.compiler.fullInlining=true` for the larger dimer solver). **Every validated
device-resident explicit-S2 GPU run in this project disabled FMA.** The commit that reported the "fault"
(`0ead849`, this session) never ran the graph with FMA off.

## 3. Current environment

- **git:** commit `0ead849`, branch `gpu-mat-bottlenecks-explicit-singlehead` (clean rebuild from scratch:
  `rm softbox/*.class && ./scripts/build.sh` → 295 classes).
- **Java:** OpenJDK 21.0.11 (Ubuntu, +JVMCI, `--enable-preview`, ParallelGC — from `tornado-argfile`).
- **TornadoVM:** 4.0.1-dev PTX backend (`tornadovm-4.0.1-dev-ptx-linux-amd64`).
- **GPU / driver:** NVIDIA GeForce RTX 5070, driver 595.71.05.
- **JVM properties (fix):** `-Dtornado.recover.bailout=false -Dtornado.enable.fma=false
  -Dtornado.tvm.maxbytecodesize=65536`. FMA disabled; bailout recovery disabled (a lowering failure THROWS — no
  silent CPU fallback).

## 4. Exact reproducer

- **FAIL** (default FMA on): `-Dtornado.recover.bailout=false -Dtornado.enable.fma=true -Dtornado.tvm.maxbytecodesize=65536
  … ExplicitCompleteMatHarness -gliding` ⇒
  `java.lang.NullPointerException: Cannot invoke "org.graalvm.compiler.graph.Node.isAlive()"` at
  `org.graalvm.compiler.nodes.spi.ArithmeticLIRLowerable.generate` → `PTXLIRGenerationPhase` while compiling
  **`matS2SolveStep`** → `execute FAILED @t=0` (with bailout=false; with the default bailout=true it silently
  runs sequential — the "silent fallback" the twirling report hit).
- **PASS** (FMA off): the same command with `-Dtornado.enable.fma=false` lowers + executes device-resident.

## 5. Comparison matrix (clean build, RTX 5070)

| Case | Command | Lowers | Executes | Device-resident | First failing task / phase |
|---|---|:---:|:---:|:---:|---|
| A. Isolated mat solver | `-traj` (`buildExMatGraph`, incl. `matS2SolveStep`), FMA **off** | YES | YES | YES | — |
| B. Historical canonical graph | `-gliding` (`buildGlidingGraph`, surface absent), FMA **off** | YES | YES | YES | — |
| C. Current surface-OFF graph | `-gliding`, FMA **off** | YES | YES | YES | — |
| C′. Current surface-OFF graph | `-gliding`, FMA **ON** | **NO** | — | — | `matS2SolveStep` / `ArithmeticLIRLowerable.generate` (PTXLIRGenerationPhase) |
| D. Current surface-ON graph | `-equiv` full `buildGlidingGraph` (SURFACE_ON), FMA **off** | YES | YES | YES | — |
| —. Isolated new kernels | `matSurfaceAzim`+`matSurfaceStericPrune` minimal graph, FMA off | YES | YES | YES | — |

Results: A `§7 Δnode=1.3e-8, §8 300 steps firstDiv=none`; C `§8 mism=0, §10 binds=11 detach=10 invalid=0 PASS`;
C′ the NPE above; D `200 device-resident steps, max|ΔfilCoord|=5e-2 µm, firstDiv t=4 (chaotic float op-order),
finite, no fallback`.

## 6. Interpretation

Matches the interpretation-matrix row **"fail only with FMA on / pass with FMA off ⇒ known or renewed FMA
lowering defect"** ⇒ an **environment/launch regression**, not a source or graph-composition regression. The
isolated solver (A) and the canonical surface-OFF graph (B/C) both lower with FMA off; only FMA-on fails (C′). It
is NOT the new surface tasks (C, surface-OFF, fails identically with FMA on and passes with FMA off; D, surface-ON,
passes with FMA off). It is NOT graph composition (A isolated fails/passes on the same FMA switch).

## 7. FMA findings

- Same NPE with FMA on across `-traj`, `-gliding`, and (from the prior session) the surface graph.
- With FMA off the failure disappears entirely and execution is device-resident.
- The failing compiler node is `ArithmeticLIRLowerable` (an FMA/arithmetic node the PTX backend mis-lowers). FMA
  fuses `a*b±c` into an arithmetic node whose operand handling hits the null-operand path in the PTX LIR builder.
- Numerical output with FMA off matches the CPU runner over the bit-close window (§8/§9) — disabling FMA is the
  *validated* production configuration, not a numerical compromise.

## 8. Source-history audit

| File / method | Change since known-good | Could affect lowering? | Tested? |
|---|---|:---:|:---:|
| `TwoBodyBeamAnalyticGpu.matS2SolveStep` | **none** (unchanged this session) | n/a (FMA-sensitive, always was) | YES — lowers with FMA off (A/B/C) |
| `matBeamGeom` / `matPlaceHeadExplicit` / helpers | none | n/a | YES (A) |
| `matSurfaceAzim` / `matSurfaceStericPrune` (new) | added (additive) | lower independently + in D | YES (D + isolated) |
| `ExplicitCompleteMatHarness.buildGlidingGraph` | +surface tasks/transfers gated by `surfOn()` (default off) | surface-OFF topology unchanged | YES (B/C ≡; D) |
| `scripts/run_explicit_twirl.sh` | **the regression:** omitted `-Denable.fma=false`, used bytecode 16384 | **YES — the cause** | YES (fixed) |

`matS2SolveStep` is byte-unchanged; the surface additions are gated and do not alter the surface-OFF graph
topology (C lowers identically to B). The only load-bearing change is the launcher's missing FMA flag.

## 9. Graph-composition audit

Not implicated. The isolated solver graph (A) already fails with FMA on and passes with FMA off — the FMA switch,
not task count / ordering / buffer aliasing / transfer modes, determines lowering. The surface-ON graph (D) — the
maximal composition (matBeamGeom → bind → surfAzim → surfPrune → chem → cock → place → bondForcesSurface → CSR →
gather → chain → confine → brown → integrate → derive → s2solve → reduce) — lowers and executes device-resident
with FMA off.

## 10. Fix

**`scripts/run_explicit_twirl.sh`** now launches with the known-good flags (matching `run_singlehead_gpu.sh`):
`-Xmx8G -Dtornado.recover.bailout=false -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536`. No source
/ physics / graph change. `ExplicitTwirlGlidingHarness.runEquiv` restored to test the **full surface-ON gliding
graph** device-resident (G3) in addition to the isolated-kernel bit-identity check; its stale "pre-existing fault"
comments are removed.

## 11. Validation gates

- **G1 — canonical surface-OFF device execution: PASS.** `-gliding` (FMA off, bailout off) lowers + executes
  device-resident, no CPU fallback, RTX 5070 selected, `invalid=0`, `solver failures=0`, binds=11 detach=10.
- **G2 — scientific identity: PASS.** Device vs CPU runner: `§8 bind-identity mism=0` over the bit-close window;
  `§10 binds=11 detach=10 boundC=1/1 invalid=0`. `max|ΔfilCoord|=1.3e-1 µm` from `firstDiv t=4` is chaotic float
  op-order decorrelation (the documented explicit-S2 CPU↔GPU standard — aggregate/bit-close, not long-horizon
  bit-identity), NOT a material change in scientific output.
- **G3 — surface-ON graph: PASS.** The full surface-ON `buildGlidingGraph` lowers + runs device-resident (200
  steps, finite, no fallback); the new tasks are device-resident; retained-azimuth + steric decisions are
  bit-identical CPU↔GPU on the deterministic fixture (Δboundseg=0, Δbindazim=0, Δoccstats=0).
- **G4 — repeated-process reproducibility: PASS.** 3 fresh JVM processes each lower + execute device-resident with
  identical results (binds=11, detach=10, maxΔfil=1.3e-1, firstDiv=t=4, invalid=0) — no compiler-cache / stale-state
  dependence.
- **G5 — script propagation: PASS.** Running through `scripts/run_explicit_twirl.sh -equiv` (not a hand-written
  command) lowers the full graph device-resident — which is only possible with `-Dtornado.enable.fma=false` active
  (FMA-on deterministically NPEs), proving the script propagates the property.

## 12. Remaining limitations

- None for lowering. The device path requires `-Dtornado.enable.fma=false` (a TornadoVM PTX-backend FMA defect,
  external to this repo) — this is the standing, validated configuration for all explicit-S2 GPU work, now
  correctly encoded in every launcher. The underlying backend FMA defect is an upstream TornadoVM follow-up, not a
  local fix.
- CPU↔GPU agreement is bit-close then chaotically decorrelates (float op-order) — the documented standard for this
  chaotic assay; aggregate-statistical, not long-horizon bit-identity.

## 13. Exact next step

**Work on the stereospecific azimuthal gate may safely resume.** The full explicit-S2 gliding graph — surface OFF
and ON — lowers and runs device-resident with no silent fallback, finite state, and unchanged scientific output;
the dynamic campaign may now run device-resident (much faster than CPU) using `scripts/run_explicit_twirl.sh`. The
next scientific step (from the twirling report §19) is the azimuth-dependent bind gate, then the roll-coherence
spring.

---

## Completion summary

- **Root cause:** launch-flag regression — the explicit-S2 solver `matS2SolveStep` needs `-Dtornado.enable.fma=false`
  (a PTX-backend FMA lowering defect ⇒ `ArithmeticLIRLowerable` NPE); `run_explicit_twirl.sh` omitted it (and used
  bytecode 16384 vs the known-good 65536). Not source, not graph composition, not the new surface tasks.
- **Files changed:** `scripts/run_explicit_twirl.sh` (add `-Dtornado.enable.fma=false`, bytecode 65536, `-Xmx8G`);
  `softbox/ExplicitTwirlGlidingHarness.java` (`runEquiv` restored to test the full surface-ON graph device-resident;
  stale comments removed); `docs/EXPLICIT_GLIDING_HELICAL_SURFACE_TWIRLING_FINDINGS.md` §18 corrected; this report;
  JOURNAL.
- **Known-good command:** `run_singlehead_gpu.sh -production-cell …` / any explicit-S2 launch with FMA off.
- **Repaired command:** `./scripts/run_explicit_twirl.sh -equiv` (or `-campaign`, etc.).
- **Isolated solver:** PASS (FMA off). **Surface-OFF full graph:** PASS (FMA off) / FAIL (FMA on → NPE).
  **Surface-ON full graph:** PASS (FMA off), device-resident. **Device residency + no fallback:** proven
  (bailout=false throws; runs succeed). **CPU/GPU scientific equivalence:** PASS (bind-identity mism=0, invalid=0,
  chaotic decorrelation only). **Repeated-process:** PASS 3/3.
- **Remaining limitation:** the device path requires FMA off (upstream TornadoVM defect), now encoded in all
  launchers.
- **Azimuthal-gate work may resume: YES.**
