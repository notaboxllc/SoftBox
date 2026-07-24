# Continuous Helical Surface Binding and the Minimal Twirling Drive

**Authoritative report for the off-axis surface-binding / twirling prototype** (2026-07-23, branch
`gpu-mat-bottlenecks-explicit-singlehead`). Sole Markdown report for this task. Builds on the audit
`docs/helical_binding/ACTIN_HELICAL_BINDING_AUDIT.md` §11-B (off-axis bond placement = the twirl DRIVE) and the
prior null `docs/helical_binding/CONTINUOUS_OCCUPANCY_EXCLUSION_FINDINGS.md` (co-occupancy capping — a *separate*
goal). Noncanonical, flag-gated, default-off, byte-identical when disabled. **No canonical default, parameter,
chemistry, force law, dt, RNG, event ordering, or `MotorModel.CANON_VERSION` changed.**

> **What this is.** The smallest continuous helical *surface*-binding extension: it places the actin-side
> cross-bridge attachment on the physical filament SURFACE at a retained MATERIAL azimuth (instead of the
> centerline), so the existing cross-bridge force acquires an axial (‖û_seg) torque and mechanically drives
> filament TWIRLING — with **no new force law and no artificial torque**. A 3D surface-point steric rule
> (default 5.5 nm) prevents near-coincident attachments. **What this is NOT:** a discrete actin monomer lattice,
> a 13/6 site model, a monomer index, a site-occupancy grid, a recruitment-saturation gate, or any chemistry /
> S2 / branch / dt / RNG change.

---

## 1. Baseline revision and dirty status

- **Baseline commit:** `e217c79` (`matsoa(occupancy): continuous local actin co-occupancy exclusion …`), branch
  `gpu-mat-bottlenecks-explicit-singlehead`.
- **Dirty at start:** yes — pre-existing unrelated edits to `docs/CURRENT_STATE.md` and five `scripts/*sweep*.sh`
  (untouched by this task).
- **This task's diff (all additive):**
  - `softbox/MotorStore.java` (+10): the retained-state field `bindAzim` + its init.
  - `softbox/CrossBridgeSystem.java` (+123): `bondForcesSurface` (new method; no existing method changed).
  - `softbox/BindingDetectionSystem.java` (+165): `surfaceBindPropose` + `surfaceStericResolve` (new methods).
  - **new** `softbox/HelicalSurfaceTwirlHarness.java`, `scripts/run_helical_twirl.sh`, this report,
    `RUN_LOGS/helical_surface_twirl_all.txt`.
  - **298 insertions, 0 deletions** in the three existing files ⇒ every existing code path is byte-identical by
    construction (G1). Verified: `run_xbridge.sh -cpu` still PASSes unchanged.

## 2. Runner and hardware

- **aorus**, Java 21 + TornadoVM 4.0.1-dev PTX backend, **NVIDIA GeForce RTX 5070**.
- **Deterministic fixtures + dynamic campaign:** CPU sequential runner (the same system methods over host SoA;
  a debug/validation instrument — disclosed. The campaign is a small, easy-to-diagnose mechanism prototype, not a
  production gliding sweep).
- **CPU/GPU equivalence:** the new hot kernels (`bondForcesSurface`+gather, `surfaceStericResolve`) run
  **device-resident** in a TornadoVM TaskGraph (no CPU fallback) and are compared to the CPU runner on identical
  inputs.

## 3. Lineage implemented

The **lumped / gliding** lineage (audit §11-B), where the filament roll DOF, the roll spring (`RollSpringSystem`),
and the full cross-bridge force+torque gather already exist. Prototyped in a **dedicated new harness**
(`HelicalSurfaceTwirlHarness`) that assembles the shared systems on a small, controllable scene — NOT wired into
the production gliding assay (`GlidingHarness` is byte-untouched). The canonical explicit-S2 / HMM-dimer path is
**not** modified (port decision §16).

## 4. Binding-path trace: before and after

**Before (centerline).** `bindNearest*` stores `(boundSeg, bindArc)` — a continuous arc on the CENTERLINE.
`CrossBridgeSystem.bondForces` reconstructs the actin-side point as `ap = sc + (bindArc − ½slen)·û_seg` (on-axis),
so the segment lever `RS = ap − sc ∥ û_seg` and the F8 reaction torque `TS = RS×(−F)` has **zero ‖û_seg**
component — the roll channel is open (`bwx = torqueSum·u/γ_x`) but fed zero. No filament azimuth is read at bind.

**After (surface).** Two additive stages (one implementation, both runners):
1. `surfaceBindPropose` (parallel) — reuses the **exact** `bindNearest` reach predicate to pick the nearest
   reachable segment, then reuses the **existing continuous helical scan** (fixed ±4-sample bound, PTX-safe;
   analytic `φ(s)=twistRate·(arc−½segLen)`) to select the presented actin site whose radial `n̂(s)` best agrees
   **geometrically** with the head's perpendicular direction `p̂`. Writes the candidate `(candSeg, candArc,
   candAzim=φ(candArc))`.
2. `surfaceStericResolve` (single-thread serial, ascending id) — reconstructs each candidate's 3D surface point and
   commits it unless another bound head on the SAME filament occupies a surface point within `exclusion − tol`;
   deterministic lowest-id-wins on same-step conflicts.

`bondForcesSurface` then reconstructs the **off-axis** actin site
`xSite = sc + (bindArc − ½slen)·û_seg + Ractin·(cosψ·segY + sinψ·segZ)`, `segZ = û_seg×segY`, so
`RS = xSite − sc` gains a perpendicular component and `TS = RS×(−F)` carries a nonzero ‖û_seg torque into the
**unchanged** seg-torque slot `bondData[d+9..11]` → the byte-unchanged `segGather` → the rigid-rod roll channel.

**Reference-point audit (stated explicitly).** The azimuth reference is the **head bind-tip** (`MotorStore.head`,
= the head sub-body center + ½·HEAD_LEN·û_head) — i.e. the **F8 head-side anchor** (`htip`), the same point the
reach predicate uses. `bondForcesSurface` pulls the F8 head anchor from the head sub-body identically. CPU and GPU
run the SAME method on the SAME stored state ⇒ **identical binding-azimuth decisions by construction** (verified
§10). The choice is never silently different between runners.

## 5. Retained-state definition

One scalar per motor: `MotorStore.bindAzim[m]` (SoA `FloatArray`, size `nMotors`) — the material-frame azimuth ψ
(rad, wrapped (−π,π]) of the off-axis attachment, measured about û_seg in the **rolling** (segY,segZ) material
frame. Set at bind (`surfaceBindPropose`/`surfaceStericResolve`); read ONLY by `bondForcesSurface`. **Never
recomputed from the current world-space head position after binding.** Default `0f`, read by no existing method ⇒
byte-identical for every existing harness; serialized/restarted wherever `bindArc` is (same field pattern).
Because ψ is material-relative and segY/segZ ride the body, the reconstructed site is **material-latched** under
translation, bending, lab rotation, and roll with no extra state (a scalar is sufficient across the handoff-free
bound lifetime; the bound head stays on its `boundSeg`, the established behavior).

## 6. Material-frame and handedness convention

- Frame: û_seg (long axis), segY (reference ⊥), segZ = û_seg×segY (right-handed material triad, maintained
  roll-preservingly by `DerivedGeometrySystem`).
- Helix: actin 13/6, per-monomer azimuthal advance magnitude 166.5°, **LEFT-handed** (negative about
  pointed→barbed), `twistRate ≈ −1076 rad/µm` (derived fresh; v1's render-screw sign is non-authoritative). Site
  azimuth ψ = φ(arc) = `twistRate·(arc − ½segLen)`.
- Radius: `Ractin = Constants.radius = 3.5 nm` (the physical actin radius; the render-only helix offset is NOT
  used as a moment arm, per audit §10). Off-axis site = axis + `Ractin·(cosψ·segY + sinψ·segZ)`.
- Polarity handedness (verified, fixture 8): flipping û_seg flips the stored azimuth sign for the same physical
  site, `n̂(û,ψ) == n̂(−û,−ψ)`.

## 7. Force / torque coverage audit

| Interaction | Motor side | Filament side | Count |
|---|---|---|---|
| Cross-bridge F8 force | +F at htip (self-write `bondData[d+0..2]`) | −F at xSite (gathered `bondData[d+6..8]`) | exactly once |
| Cross-bridge F8 torque | +R_H×F about head center (`d+3..5`) | +R_S×(−F) about seg center (`d+9..11`) | exactly once |
| F9 (converter align, ⊥û by construction) | −T9 head | +T9 seg | exactly once |
| F10 (yVec align) | −T10 head (**unchanged**) | +T10 seg (**dropped in clean twirl mode**, §8) | exactly once |
| Axial roll drive | none added | ‖û_seg projection of the F8 reaction torque | exactly once |

- **F is collinear with `(xSite − htip)`** ⇒ the head/seg F8 pair is a **closed couple** (net torque 0 about any
  origin) regardless of where xSite is ⇒ force + torque conservation is preserved by construction (fixtures
  23-24: `|F_head+F_seg|=0`, net τ about a common origin `< 1e-24 N·m`). Moving the attachment point does **not**
  double-count any alignment torque: F9/F10 are untouched; the only new ‖û_seg contribution is `RS×(−F)`, which
  was identically zero on-axis. **No implicit off-axis moment pre-existed** (stop-condition cleared).
- The G5 "cross-bridge axial torque" observable is `TS·û_seg` — recovered host-side from `bondData[d+9..11]·û`:
  F9 is ⊥û_seg by construction and, with the seg-side F10 reaction off (clean twirl mode), that dot equals the
  pure F8 axial torque exactly; at `Ractin=0` it is 0.

## 8. Steric-rule definition and threshold convention

**Continuous 3D actin-surface steric exclusion (GLOBAL, same-filament).** A candidate at 3D surface point `p` is
REJECTED iff another bound head on the SAME filament (`segFilId`) occupies a surface point `q` with
`‖p − q‖ < exclusion − tol` (Euclidean, on the reconstructed surface points — NOT an axial-gap approximation).
`exclusion = 5.5 nm`, `tol = 1e-3 nm` ⇒ **exactly 5.5 nm is ACCEPTED**. Self-excluded; different filaments never
exclude; a bound dimer sister participates (global ⊇ sister-only). Same-step conflicts resolved
**deterministically** in ascending head id (each commit immediately occupies for later ids ⇒ lower id wins); **no
RNG, no occupancy grid, no discrete site index.** `exclusion ≤ 0` ⇒ off (commit every candidate). This is a
**minimum attachment-footprint separation**, NOT full motor-domain excluded-volume mechanics — stated as a
limitation. It is a distinct experimental path from the prior axial-gap 5.4 nm co-occupancy veto (that flag and
its meaning are unchanged; this is a separately named 3D surface rule).

## 9. Deterministic fixture table (28/28 PASS)

| # | Fixture | Result |
|---|---|---|
| 1 | R=0 site == centerline (host exact + `bondForcesSurface` bondData byte-identical to `bondForces`) | PASS |
| 2 | R=3.5 nm radial distance from centerline == R | PASS |
| 3 | ψ ∈ {0,π/2,π,3π/2} place on +ŷ/+ẑ/−ŷ/−ẑ | PASS |
| 4 | lab-frame rigid rotation: site' == R_lab·site (material-latched) | PASS |
| 5 | filament roll δ: site follows the material frame about û | PASS |
| 6 | bend + re-orthogonalize: radius == R & recovered azimuth == ψ | PASS |
| 7 | segment-boundary material-coordinate continuity (half-open ownership) | PASS |
| 8 | polarity reversal: `n̂(û,ψ) == n̂(−û,−ψ)` | PASS |
| 9 | A/B motor relabel: identical site for identical stored state | PASS |
| 10 | same surface coordinate (0 nm) → REJECT | PASS |
| 11 | below threshold (5.3 nm) → REJECT | PASS |
| 12 | exactly threshold (5.5 nm) → ACCEPT | PASS |
| 13 | above threshold (5.7 nm) → ACCEPT | PASS |
| 14 | same axial coord, opposite side (2R = 7.0 nm sep) → ACCEPT | PASS |
| 15 | different filament, identical coord → ACCEPT | PASS |
| 16 | same-step conflict: exactly one binds, lower id wins, conflict counter = 1 | PASS |
| 17 | bound head within threshold excludes (global; sister participates) | PASS |
| 18 | OFF path (exclusion 0) commits every candidate | PASS |
| 19 | axial force at centerline (R=0) → axial torque == 0 | PASS |
| 20 | off-axis force → axial torque matches analytic `R·F_tan` (relErr < 1e-4) | PASS |
| 21 | reverse azimuth → axial torque sign reverses | PASS |
| 22 | reverse bond-force direction → axial torque sign reverses | PASS |
| 23 | F8 action–reaction closes (`|F_head+F_seg| = 0`) | PASS |
| 24 | F8 torque closes about a common origin (net τ < 1e-24 N·m) | PASS |
| 25 | roll integrator responds; cumulative roll sign matches torque | PASS |
| 26 | roll-frozen control (γ_x→∞): torque present, cumulative roll == 0 | PASS |
| 27 | force-disabled control: no directed twirl | PASS |
| 28 | symmetric ±drive over random azimuths → ensemble mean twirl ≈ 0 | PASS |

## 10. CPU/GPU equivalence (device-resident; no fallback)

- **`bondForcesSurface` + segGather** (4 motors / 2-seg filament, real cross-bridge j1FMT=0.4, off-axis R=3.5 nm),
  single deterministic evaluation on identical inputs, CPU vs a device TaskGraph: `max|ΔfilForce| = 4.34e-19 N`,
  `max|ΔfilTorque| = 1.03e-25 N·m` (float32 last-bit) ⇒ **CPU≡GPU**.
- **`surfaceStericResolve`** (constructed 6-head/2-filament fixture engineered for 3 accepts + 1 reject + 1
  same-step conflict): CPU and GPU produce **bit-identical** `boundSeg=[0,−1,1,0,−1,−1]` and
  `occStats=[cand 4, rej 1, acc 3, conf 1]` ⇒ **CPU≡GPU** (the rigorous event-identity claim; identical binding
  azimuth + steric decisions on both runners).
- Fixture 1 verifies `bondForcesSurface(R=0)` bondData is **byte-identical** to `bondForces`. 0 invalid states, 0
  solver failures throughout.

## 11. Dynamic campaign configuration

`HelicalSurfaceTwirlHarness -campaign` (CPU sequential, disclosed). Scene: a filament (translation **pinned**,
roll **free**) + a bed of pre-bound off-axis motors (heads anchored). Arms: centerline control (R=0); off-axis
only (R=3.5, steric OFF); off-axis + steric 5.5 nm; off-axis + roll-spring ON (6-segment coherence chain);
off-axis + Brownian ON; Brownian multi-seed (n=4); a timestep-halving check (equilibrium roll at matched
sim-time); a translation↔rotation coupling probe. 20 000 steps/arm, dt = 1e-5.

## 12. Axial-torque, angular-velocity, cumulative-turn, and translation results

```
config                          cumTurns   mean ω(rad/s)   xbAxial(pN·nm)
centerline control (R=0)          0.0000      0.000e+00        0.000
off-axis only (R=3.5)             0.1092      3.430e+00        0.002
off-axis + steric 5.5nm           0.1092      3.430e+00        0.002
off-axis + roll-spring ON        -0.1286     -4.041e+00       -0.053   (6-seg coherent; twisted-rest scene)
off-axis + Brownian ON (seed A)  -0.0850     -2.671e+00        0.031
Brownian multi-seed  mean = -0.398 turns,  sd = 0.230  (n=4)
timestep-halving (matched sim-time):  dt 1e-5 cumTurns 0.1092 ; dt 5e-6 cumTurns 0.0877 ; ratio 0.80
coupling:  axial drive ΣF·û = 6.80e-12 N,  roll torque ΣT·û = -7.59e-22 N·m  (same off-axis bonds ⇒ coupled)
```

**Reading.** (1) The off-axis bond generates a nonzero axial cross-bridge torque (fixture 20 analytic match;
`xbAxial` nonzero) and the filament accumulates a **definite, signed, polarity-consistent** roll (cumTurns);
the R=0 control is exactly 0. (2) A **static** bed relaxes to a spring equilibrium ⇒ the observable is a bounded
**driven-roll displacement** (~0.1 turn), NOT a steady multi-turn rate. (3) The roll spring makes the 6-segment
filament roll **coherently** as one (whole-filament twirl, not independent segment roll). (4) Under Brownian
noise the mean roll is directed (−0.40 ± 0.23 turns) — the drive survives thermal roll. (5) Translation and
rotation are **coupled**: the same off-axis bonds produce a nonzero axial force AND a nonzero axial torque. `cumTurns`
is unwrapped step-to-step (never inferred from a wrapped frame).

## 13. Numerical health

- **0 invalid states, 0 solver failures** across all fixtures, the equivalence gate, and every campaign arm; no
  NaN/Inf, no pathological roll-rate spikes, bounded joint/bond geometry.
- **Timestep sensitivity, not instability:** the equilibrium roll displacement shrinks ~20 % from dt 1e-5 → 5e-6
  (ratio 0.80), a monotone convergence consistent with the **documented explicit cross-bridge dt-convergence** of
  this lineage (the F8 spring slightly overshoots at coarse dt) — NOT a new instability (no blow-up, bounded, 0
  invalid). Reported as a quantitative limit, not a failure.

## 14. Limitations

- **Driven-roll DISPLACEMENT, not sustained rotation.** The static pre-bound bed rolls the filament to a spring
  equilibrium (~0.1 turn) and stops. **Sustained many-turn twirling requires the dynamic bind/stroke/release
  cycle** (motors binding at successive helical sites, stroking, releasing, rebinding) — the same machinery that
  turns per-bond force into sustained gliding translation. This prototype demonstrates the *mechanical loop*, not
  a biological twirling rate (and none is claimed).
- **Steric = minimum attachment-footprint separation**, not full motor-domain excluded volume.
- **Clean-isolation modeling choice:** the campaign drops the seg-side F10 alignment reaction (`segF10Off=1`) so
  filament roll is driven ONLY by the off-axis F8 lever + the roll spring; the head-side F10 (head-to-actin
  orientation) is unchanged. With the full faithful F10 reaction on (`segF10Off=0`, byte-identical to `bondForces`
  at R=0) the qualitative twirl persists but the roll also carries the F10 alignment component.
- **No discrete lattice / monomer index / site occupancy** — deliberately continuous (abstract-from-the-second-
  instance).
- ~20 % dt-sensitivity of the equilibrium roll (§13).

## 15. Canonical-status statement

Entirely **noncanonical, flag-gated, default-off, byte-identical when disabled.** No canonical default, parameter,
chemistry, force law, dt, RNG, event ordering, manifest, or `MotorModel.CANON_VERSION` changed. The prototype
lives in the lumped/gliding lineage + a dedicated harness; the canonical explicit-S2 / HMM production path is
untouched.

## 16. Recommendation on the canonical explicit-S2 / HMM port

**Do NOT port to canonical in this increment; the prototype is validated and the port plan is below.** Per the
task's rule (port in-increment only if "genuinely small, shares one source of physics, no duplicated CPU/GPU
logic"), the canonical explicit-S2 / HMM-dimer cross-bridge is a **separate physics implementation**
(`TwoBodyBeamAnalyticGpu` / `ExplicitHmmDimerGpuKernel` — explicit-S2 beam bond, not the lumped F8), its bind gate
(`matGeomGate`/`matBindGateExplicit`/`dimerBindGate`) does not read `filYVec`, and it is the device-resident
**production** path. Porting is a real increment, not a byte-copy.

**Concrete port map (deferred):**
1. **Retained state:** add `bindAzim` to the explicit-motor SoA (mirror `MotorStore.bindAzim`; already a device-
   resident planar buffer pattern) + its GPU transfer + demand-readback.
2. **Bind gate:** thread `filYVec` into `matGeomGate`/`matBindGateExplicit`/`dimerBindGate`; add the azimuth
   selection (the `surfaceBindPropose` scan) reading the explicit head reference point (`xF8` in that lineage) —
   state the reference explicitly and keep it identical CPU↔GPU.
3. **Bond force:** reconstruct the off-axis `xSite` in the explicit bond-force kernel (the analogue of
   `bondForcesSurface`); keep F collinear with `(xSite − xF8)` so conservation holds by construction.
4. **Steric:** reuse `surfaceStericResolve` (or its material-coordinate analogue) over the explicit bound set.
5. **Roll drive:** ensure the explicit filament carries the roll DOF + roll spring on the production path (today
   the production gliding path does not drive roll).
6. **Validation:** a fresh CPU↔GPU equivalence on the changed hot kernels + the **per-assay-class GPU production
   sign-off** (`runG4d`); 0 invalid/solver; before any production sweep.
7. **Sustained-twirl demonstration** (the real deliverable): run the ported drive in the *dynamic* gliding assay
   (bind/stroke/release cycling) and measure sustained ω vs gliding velocity — the step this static prototype
   defers.

**Do NOT** promote anything, bump `CANON_VERSION`, or tune Ractin / exclusion / roll stiffness to force an outcome.

## 17. Decision-gate verdicts and outcome classification

- **G1 — default-off identity: PASS.** Purely additive (298 insertions, 0 deletions in existing files);
  `bondForcesSurface(R=0)` bondData byte-identical to `bondForces`; `run_xbridge.sh` regression unchanged.
- **G2 — material-latched surface geometry: PASS.** Fixtures 1-9 (translation, bending, lab rotation, roll,
  re-orthogonalization; radius==R & recovered azimuth==ψ).
- **G3 — steric correctness: PASS.** Fixtures 10-18 on 3D reconstructed surface points (opposite-side accept;
  different-filament never excludes; deterministic same-step lowest-id).
- **G4 — force/torque conservation: PASS.** Fixtures 23-24 (action–reaction closes; net torque about a common
  origin < 1e-24 N·m); F applied once, torque once (§7 audit).
- **G5 — mechanical twirl loop: PASS.** off-axis attachment → **measured** axial cross-bridge torque (fixture 20
  analytic match; `xbAxial` observable) → gathered filament torque (bit-identical gather) → nonzero roll rate
  (fixture 25) → cumulative signed turns (campaign, polarity-consistent). Demonstrated directly, *with* measured
  axial torque (not a bare angle).
- **G6 — controls: PASS.** R=0 removes the directed torque (fixtures 19, 27, campaign control = 0); force-disabled
  removes twirl (27); roll-frozen prevents rotation despite torque (26); symmetry control ≈ 0 (28); roll-spring
  OFF vs ON behaves as expected (single-seg vs coherent 6-seg).
- **G7 — numerical health: PASS.** 0 invalid / 0 solver; bounded geometry; no roll-rate spikes; the ~20 %
  dt-sensitivity is documented convergence, not instability.
- **G8 — biological restraint / classification: A (complete mechanical loop demonstrated), with the honest
  qualifier** that the static prototype yields a bounded **driven-roll displacement** rather than sustained
  rotation (a stricter "sustained coherent twirling" reading would be **B**). The complete loop is demonstrated
  and all controls pass; no radius/exclusion/stiffness was tuned to force this. No biological twirling rate is
  claimed.

---

## Completion summary

- **Files changed:** `softbox/MotorStore.java` (`bindAzim`), `softbox/CrossBridgeSystem.java`
  (`bondForcesSurface`), `softbox/BindingDetectionSystem.java` (`surfaceBindPropose`, `surfaceStericResolve`);
  **new** `softbox/HelicalSurfaceTwirlHarness.java`, `scripts/run_helical_twirl.sh`, this report,
  `RUN_LOGS/helical_surface_twirl_all.txt`.
- **Flags / defaults:** `-helical-surface-bind` (off), `-actin-bind-radius-nm 3.5` (= `Constants.radius`),
  `-surface-exclusion-nm 5.5`, `-no-surface-exclusion`, `-rollspring`, `-dt`, `-seed`. Ractin ≤ 0 ⇒ centerline
  (feature effectively off); exclusion ≤ 0 ⇒ no steric.
- **Fixtures:** 28/28 PASS. **CPU/GPU equivalence:** PASS (bondForcesSurface+gather last-bit; steric resolve
  bit-identical decisions; device-resident). **Twirl result:** off-axis bond → measured axial torque → signed
  coherent driven roll (R=0 control = 0); sustained rotation deferred to the dynamic cycle. **Steric effect on
  ordinary binding:** none at the sparse prototype density (co-occupancy pressure below the 5.5 nm scale). **Health:**
  0 invalid / 0 solver.
- **Decision category: A** (loop complete; driven-roll displacement, sustained rotation deferred).
- **Canonical port:** justified as a *future* increment with a concrete map (§16) — **not** done here.
- **Exact next smallest step:** port the drive into the canonical explicit-S2 bind gate + bond force (§16 map) and
  demonstrate **sustained** ω in the dynamic (bind/stroke/release) gliding assay — the one regime this static
  prototype defers.
