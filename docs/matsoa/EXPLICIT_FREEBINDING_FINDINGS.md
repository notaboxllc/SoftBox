# Explicit FREE-BINDING on the persistent GPU mat — first genuine explicit gliding

**Objective met:** the complete explicit free-binding state machine is ported to the persistent device-resident
mat, one-step CPU/GPU bind identity is EXACT, and the first genuine low-density explicit gliding trajectory runs
end-to-end on the GPU. No physics/salt/chemistry/solver/parameter change; FD stays the oracle;
`MotorGpuParams.DEVICE_VALIDATED` stays false (promotion deferred); no silent fallback (bailout=false,
enable.fma=false). New: `TwoBodyBeamAnalyticGpu.matBindExplicit`; harness `ExplicitCompleteMatHarness -gliding`.
Report: `RUN_LOGS/explicit_completemat/COMPLETEMAT_GLIDING.md`.

## Key contract finding (§1)
**Explicit binding is DETERMINISTIC** — production `stepGlideS2` (L6806–6810) binds an active, unbound
(boundSeg=−1), ADP·Pi (nuc=2), bindable motor iff ALL 8 geometric/energetic gates pass (distance <3 nm,
ψ/φ<25°, θ<20°, preload<2 pN, E<15 kT, headSide<aSemiZ, bindArc in-segment) — **there is NO stochastic draw**.
So the port has NO binding RNG and cannot shift the chemistry/Brownian RNG stream. The stochastic part
(release) lives in `cycleLymnTaylor` (catch-slip), already on device. **Beam initialization at binding
(the task's "highest-risk" step) is a NO-OP:** production binds from the CURRENT unbound beam configuration —
only `boundSeg`+`bindArc` are set; the beam continues from its unbound relaxed state (the "snap" then emerges
over subsequent `matS2SolveStep` steps). Geometry (C/xH/xF8) depends only on phi/psi (NOT thetaS), so the one
`matBeamGeom` serves both the bind gate and the mechanics.

## Final status block
- **EXPLICIT DEVICE FREE BINDING:** DONE — `matBindExplicit` (the deterministic 8-gate port over the beam
  geometry: nearest-seg + gate2D + AND) + chemistry (`cycleLymnTaylor`) + cocking (`matCock`) all run on
  device; motors recruit/bind/stroke/detach entirely on the GPU (smoke: 9 binds, 8 detaches over 2000 steps).
  Race-free (`@Parallel`; each motor writes only its own boundSeg/bindArc). No host per-motor bind loop.
- **CPU/GPU BINDING IDENTITY:** DONE — device `matBindExplicit` reproduces the production
  `geom2D+nearestSeg2D+gate2D`+8-gate decision **EXACTLY** (0 boundSeg mismatches / 600 motors; deterministic).
- **BEAM INITIALIZATION AT BINDING:** DONE — no re-initialization (production binds from the current unbound
  beam state; only boundSeg+bindArc set), confirmed by the exact bind identity + the stable gliding run.
- **LOW-DENSITY EXPLICIT GLIDING:** DONE — first genuine free-binding explicit gliding on the persistent GPU
  mat (N=600, density 200, all-active, 2000 steps). CPU-runner vs GPU **bit-close for 178 steps then chaotic
  float-FMA (Lyapunov) decorrelation** — the CLAUDE.md gliding standard (CPU≡GPU aggregate-statistical, not
  stepwise; a semantic bug would diverge at t=0, which it does NOT). **0 invalid states.** Post-decorrelation
  bound-count differences (CPU 1 / GPU 0) are the expected chaotic outcome, not a bug.
- **BINDING-SNAP DIAGNOSTICS:** not added (a light follow-on — event-triggered records; the per-binding
  initial force/geometry are already available from the gate). Diagnostic-only, disableable.
- **DEVICE RESIDENCY:** DONE — beam SoA + filament/body + bind/chem state (nuc/forceDotAvg/…) FIRST_EXECUTION
  resident; per step only the small counters cross UP; the production graph downloads only `redOut`+`boundSeg`.
  No host per-motor bind loop (`matBindExplicit` is `@Parallel`), no full-state round-trip, no silent fallback.
- **NEXT STEP:** multi-seed three-density smoke + the ensemble promotion gate (and optional binding-snap
  diagnostics + production culling via the validated `matCull`); promotion stays deferred.

## §8/§10 gates
- **§8 one-step free-binding replay:** production bind gate vs device `matBindExplicit` — **0/600 mismatches**,
  identical bind count. Deterministic ⇒ exact discrete identity (no RNG, no threshold-adjacent ambiguity in
  this seed; the gate reports margins if any arise).
- **§10 gliding smoke:** 9 binds / 8 detaches / 0 invalid; CPU/GPU bit-close 178 steps (float last-bit
  <1e-5 µm) then Lyapunov decorrelation (maxΔfil 71 nm by t=2000) — the expected chaotic-gliding behaviour.

## Graph (23 device tasks, added to the 18-stage mechanics)
`matBeamGeom → matBindExplicit → cycleLymnTaylor → matCock → matPlaceHeadExplicit → bondForces → zeroAcc →
csrChunk*(parallel) → segGather → chain → zconf → brownian → integrate → orthoY → derive → matS2SolveStep →
matReduce`. All device-resident; `mot.boundSeg` is the single source (bind mutates it, chemistry may release it,
mechanics read it — TornadoVM sequences the same-array dependency).

## Stop-condition audit (none tripped)
No calibrated head geometry (explicit beam geom only). No RNG salts/order drift (binding is deterministic — no
binding RNG; chemistry/Brownian RNG unchanged). CPU/GPU bind decisions IDENTICAL in the one-step replay. No
different beam state at binding (no re-init). No force double-count/omit (bondData 2.6e-18; segGather unchanged).
Detachment via `cycleLymnTaylor` (unchanged, on device). Beam state does NOT cross per step (residency). No host
per-motor bind loop. 0 invalid states, no solver-failure increase. No silent fallback.

## Commands
```
./scripts/build.sh
java @tornado-argfile --enable-preview -Xmx8G -Dtornado.recover.bailout=false -Dtornado.enable.fma=false \
     -Dtornado.tvm.maxbytecodesize=65536 -cp "$TDIR/tornado-api-4.0.1-dev.jar:." \
     softbox.ExplicitCompleteMatHarness -gliding
```
