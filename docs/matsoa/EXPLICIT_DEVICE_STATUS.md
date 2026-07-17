# Explicit-S2-L40 device-resident integration — status against Parts F–J

> **UPDATE (2026-07-17): the explicit SINGLE-HEAD device-resident vertical slice is BUILT + VALIDATED.**
> Persistent explicit beam SoA + build-time explicit Step-7 dispatch + one dynamic single-head trajectory
> (relax→stroke→force→detach→recoil, unloaded + 4 pN loaded) that stays on the GPU across 400 timesteps —
> reusing `beamRelaxAnalytic` (maxIt=1 = one production `s2Solve` step; NO second solver) + a new
> `beamObserve` reduction so the beam state never leaves the device (320 B in / 232 B out per step). All
> §1–§10 gates PASS: init EXACT, lowering probe Δnode=1.1e-11, relaxation gate GPU-vs-FD 9.1e-9 (0 class
> changes / 0 new failures), trajectory −7.70 nm working stroke / 7.92 pN peak with solver-status identical to
> CPU, loaded effective stiffness ≈1.00 pN/nm / 0 failures. This UPGRADES the F1/F2/F3/G1/G2/G3 line below
> from "isolated beam only" to a **complete device-resident single-head slice**. Report:
> `docs/matsoa/EXPLICIT_SINGLEHEAD_FINDINGS.md`; harness `ExplicitSingleHeadHarness`. The remaining gap to
> Part H (explicit GLIDING) is the `matS2Solve` coupled Stage-10 mat port (unchanged from below).


**Summary:** the one hard, gating risk of explicit-on-GPU — *does the explicit beam solve lower to PTX?* — is
**RESOLVED** and the isolated device kernel is **validated**. What remains for end-to-end explicit *gliding*
on the persistent mat is a real, bounded next increment (the `s2SolveM` gliding-coupling Stage-10 port), NOT
a resolved deliverable. This document records exactly what is done vs. remaining, honestly, and gives the
Part-J promotion decision. No beam physics/energy/solver criteria changed; FD stays the permanent oracle;
`MotorGpuParams.DEVICE_VALIDATED` stays false.

---

## What is DONE + validated (re-confirmed in the current merged tree, RTX 5070, bailout=false)

### F4 / G1 / G3 (isolated) — the analytic explicit beam GPU kernel (`TwoBodyBeamAnalyticGpu.beamRelaxAnalytic`)
- **Lowers + executes on device (C2 probe):** `status=0`, finite outputs; **GPU vs CPU-mirror max|Δnode| =
  0.000e+00** (bit-identical). The flat scratch + runtime-bound loops + analytic Jacobian collapse the kernel
  below TornadoVM's 600-node inline cap that the nested-FD `explicitBeamStep` (973 nodes, `double[][]`,
  `Math.acos`) violated. **Required backend flag `-Dtornado.enable.fma=false`** (the default PTX FMA phase
  NPEs in `PTXFMANode.generate`); it also tightens CPU↔GPU agreement.
- **Bound single-head relaxation (C3 device fixture gate — this IS Part G1):** all 10 explicit golden
  fixtures relaxed on device vs both CPU references. **GPU-analytic vs CPU-analytic max 2.5e-9 µm** (7/10
  bit-identical), **GPU-analytic vs CPU-FD max 9.1e-9 µm** (shares the FD basin), energyRel ≤ 1.2e-4,
  contourΔ ≤ 2.6e-8 µm, **0 solver failures**, iteration counts match CPU.
- **Device residency + throughput (C4, this is the Part-I beam number):** the batch beam solve is
  device-resident (warm re-execute of the resident plan); **≈70× the CPU-analytic solver, ≈900× the CPU-FD
  solver at batch 65536** (23205 vs 334 vs 26 solves/s), 0 failures at every batch. FP64 (~1/64 FP32 on the
  consumer RTX 5070) is the ceiling; still climbing at 65536.

Harness `ExplicitBeamGpuHarness` (`-probe`/`-gate`/`-bench`), kernel `TwoBodyBeamAnalyticGpu`,
`scripts/run_explicitgpu.sh`. Re-run log: `RUN_LOGS/matsoa/explicit_gpu_recheck.txt`.

### Shared infrastructure (F1 model-agnostic stages) — READY
Parts A–E delivered the optimized shared mat device path (parallel CSR + parallel reduction, bit-identical).
The explicit model reuses stages 1–6/8/11 (`matCull`, `matGeomGate`, `matBind`, chemistry, `bondForces`, the
**now-parallel** CSR gather, filament Langevin, the **now-parallel** reductions) VERBATIM — it differs ONLY
in Stage 10 (Step-7). So the shared infrastructure the explicit path rides on is built and validated.

---

## What REMAINS (the bounded next increment — honestly scoped, NOT done here)

### F1–F3 — persistent explicit SoA state + init + build-time dispatch INTO THE MAT
The beam kernel is validated in ISOLATION (`ExplicitBeamGpuHarness`), not yet integrated as Stage 10 of the
persistent `MatSoaSlice` trajectory. Integration needs the explicit per-motor SoA (`SOA_SCHEMA.md` §explicit:
`g4Node` flat 3(M+1)·N, `g4E`, `beamMat` per-motor tile N·n(n+1), `beamForce`, `beamIters/Conv/ContourErr`),
a CPU→device init path, and build-time model dispatch (two TaskGraphs sharing stages 1–6/8/11; select by
`MotorModel` at build — no per-motor `if(model)` in the hot kernel). Memory: ≈1.95 KB/motor (`beamMat`
dominates) ⇒ ≈17.5 MB at N=9000 (fine on 11.5 GB).

### The crux port — `matS2Solve` (mat Stage-10 = mat port of `s2SolveM`)
The isolated `beamRelaxAnalytic` relaxes the beam given an endpoint boundary force. The *gliding* Stage-10
(`s2SolveM`) additionally couples the beam to the motor via the F8/converter/bind Gauss–Newton on
{pivot, φ, ψ} with the MAT salts (`0x4811/0x4841/0x4842 + m·7919`). **This coupled kernel is not yet ported
to a lowering mat kernel.** The `beamRelaxAnalytic` result (lowers, analytic Jacobian, no nested FD)
strongly de-risks it — the dominant non-lowering sub-routine is gone — but the coupled assembly is larger
than the isolated beam, so its lowering under the inline cap must be re-probed in isolation (bailout=false)
BEFORE wiring, exactly as the extension note requires. **If it does not fit, the honest fallback is a valid
split: calibrated GPU + explicit Step-10 on CPU** (the CPU analytic solver, already the ~6.9× production
default).

### G2 — dynamic single-head (stroke / recoil / assisting+resisting load / near-isometric)
The C3 fixture gate covers static relaxation, not the dynamic stroke/recoil sequences. G2 drives the beam
through those on device vs CPU-analytic (+ FD arbitration). Buildable on the isolated kernel today (it does
not need the gliding mat) — a good next concrete step, smaller than H.

### H / I(gliding) / the explicit half of J — end-to-end explicit gliding on the mat
Requires F1–F3 + `matS2Solve` above, then the 200/700/1500 µm⁻² validation (+ half-dt + cull controls) vs
CPU-analytic and FD arbitration, and the gliding throughput CSV. Gated on the `matS2Solve` lowering re-probe.

---

## Part J — promotion decisions

### Calibrated
**`CALIBRATED DOUBLE GPU VALIDATED BUT NOT PROMOTED`.**
The device-resident double calibrated mat path is validated (Part 7 ensemble within-SEM, CPU≡GPU) AND now
has its serial bottlenecks removed (Parts A–E: device-kernel 6.2× at N=9000, wall +20–49 % at N≥2100,
bit-identical semantic no-op, GPU t=0 identity, incl. N=9000/density-3000). Promotion / flipping
`DEVICE_VALIDATED` remains a coordinator decision pending (a) the broader full-production scene and (b) a
float `matStep7` (now the top remaining kernel; the FP64 solve is the residual lever, not a serial-over-N
bottleneck). Float stays experimental/opt-in (Part-8 finding: full-loop FP32 is scientifically valid but
does not improve production-scale throughput; the targeted lever is `matStep7` specifically).

### Explicit
**`EXPLICIT GPU VALIDATED FOR SINGLE-HEAD TRAP ONLY`.**
The analytic explicit beam solve — which IS the single-head trap mechanics — lowers to PTX and is
device-validated (bit-identical to the CPU mirror; shares the CPU-FD basin; 0 failures; ≈70× CPU-analytic at
batch). End-to-end explicit *gliding* on the persistent mat is NOT yet integrated (F1–F3 + `matS2Solve`
Stage-10 + G2/H validation remain, gated on the coupled-kernel lowering re-probe). **CPU FD remains the
permanent physical oracle.** No silent fallback: a request for explicit GPU beyond the validated isolated
kernel must fail clearly, never invoke calibrated mechanics.

---

## Stop-condition audit (none tripped in the work done here)
CSR replacement drops/dups/reorders a motor — NO (C3 exact). Reduction changes a measurement definition — NO
(D4: same defs, integer-exact, COM bit-id). Integer totals differ — NO. Force/torque systematic ensemble
bias — NO (bit-identical CPU no-op; GPU t=0 identity). Explicit beam energy/solver criteria changed — NO
(unchanged; re-confirmed). Explicit GPU silently invokes calibrated — N/A (not integrated; will fail clearly
by design). Full state crosses host↔device every step — NO (production residency: 576 B up / 64 B down).
GPU silent fallback — NO (bailout=false throughout).
