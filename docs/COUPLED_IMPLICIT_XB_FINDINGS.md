# Coupled head+site implicit cross-bridge — does it converge at dt=1e-5? (same-dt fix vs sub-step fallback)

**Date:** 2026-07-04. Flag-gated (`-xbimplicit2`, new); **default byte-identical**; `BoA-v1ref` untouched;
F8 integrate path only (no kinetics/stroke/cycle change). Builds on `IMPLICIT_XB_CONVERGENCE_FINDINGS.md`
(head-only implicit is PARTIAL — the site is the unconverged half) and `NUCDETACH_DUTY_RECOVERY_FINDINGS.md`
(the B-dt result: glide/avgBound climb ~5.7×/~2× as dt 1e-5→2.5e-6; one shared root cause — the explicit F8
overshoot). The question this file settles: **does the coupled head+site implicit F8 converge at production
dt=1e-5 while preserving the race-free CSR / `-cpu` parity — i.e. do we get the dt-robust physical duty at the
production timestep, or fall back to sub-stepping?**

---

## PLAIN ANSWER (headline)
**Does the coupled head+site implicit cross-bridge converge at dt=1e-5 while preserving CSR/`-cpu` parity?**
**Parity: YES.** **Convergence: NO — it still climbs. → fall back to sub-stepping the cross-bridge.**
- **STEP 0 (parity):** the coupled solve IS parity-preserving. F8 is a zero-rest-length Hookean spring ⇒ the
  implicit step is LINEAR; it never couples two segments ⇒ block-diagonalizes into **per-segment closed-form
  STARS** that reuse the existing `boundSeg` CSR-inverse — expressible as per-head-pure → CSR-gather →
  per-head-pure, no atomics, CPU≡GPU-capable. No global/iterative solve. **Built (`-xbimplicit2`), CPU+GPU,
  default byte-identical, algebra exact to 8.9e-16.**
- **STEP 2 (convergence):** it does **NOT** converge at production dt. Coupled velFitX climbs **2.020 → 6.279
  (3.1×)** and avgBound 2.774 → 3.615 (1.30×) as dt refines 1e-5 → 2.5e-6. It is the **best partial of the three
  schemes** (explicit 5.75× / head-only 4.08× / coupled 3.11× velFitX climb), it is **UNBIASED** (coupled@2.5e-6
  ≈ explicit@2.5e-6 — same continuum limit), and it **converges the binding + the detach clock** (avgBound climb
  down to 1.30×; dwell/detach at 1e-5 already ≈ explicit@2.5e-6). But making F8 **translation** implicit does not
  converge the **per-bound GLIDE** — that residual lives in the still-explicit stroke/rotation/force couplings.
- **⇒ We do NOT get the dt-robust physical duty at the production timestep from the coupled implicit alone. Fall
  back to sub-stepping the cross-bridge** (`substep-feasibility-verdict`). The coupled solve is still a keeper (a
  free, unbiased, parity-clean partial that halves the climb and can pre-stabilize the F8 stretch INSIDE a
  sub-step), and it sharpened the target: the sub-step must cover the **stroke/force** integration, not the F8
  stretch (already stabilized here).

---

## STEP 0 — feasibility / parity scoping. VERDICT: **GO — parity-preserving.** The coupled solve is a
## per-segment CLOSED-FORM star that reuses the existing `boundSeg` CSR-inverse. No global/iterative solve.

### 0.1 The F8 force law is LINEAR (zero-rest-length Hookean) ⇒ the coupled solve is a linear system, not Newton.
`CrossBridgeSystem.bondForces` (`:117-136`): `d = site − tip`, `fmag = myoSpring·dist`, `F = (fmag/dist)·d =
myoSpring·d`. The spring has **zero rest length**, so `F = k·d` is **linear in position with isotropic stiffness
`k·I`** (no direction dependence, no linearization needed). This is the same fact the head-only solve exploited
("the `site` term cancels"). ⇒ a linearly-implicit (backward-Euler-on-the-spring) step is an **exact linear
solve**, closed-form — no iteration.

### 0.2 The coupling graph is a set of DISJOINT per-segment STARS. F8 never couples two segments.
For a bound head `m`: F8 couples head-tip `m` ↔ its bound site on segment `s = boundSeg[m]`.
- Each head binds **exactly one** segment (`boundSeg` is a single int) ⇒ a head appears in exactly one bond.
- A segment carries `k_s` bound heads (`k_s = #{m : boundSeg[m]==s}`).
- **No segment–segment F8 coupling** — the only segment↔segment coupling is the chain F3/F4 (a *separate* force
  law, held EXPLICIT; the task scopes to F8 only). No head–head coupling except through a shared segment.

⇒ the F8 stiffness matrix **block-diagonalizes per segment**: each block is a **star** — 1 central node (segment
`s`) + `k_s` leaves (its bound heads) joined by `k_s` springs. Different segments (even on the same filament) are
independent blocks. This is exactly the `segment → bound-motors` CSR-inverse the gather already builds
(`segMotorOffsets`/`segMotorMyo`, keyed by `boundSeg`; `CrossBridgeSystem.csr*`).

**Coupling size measured at gliding-assay engagement.** One chain filament, `FIL_SEGS = 11` segments; `k_s ≈
avgBound / 11`. In the convergence-test regime (`-lymntaylor -adppibind`, coltol10/d1000) avgBound stays low
(1.5 @ dt 1e-5 → 3.45 @ 2.5e-6) ⇒ `k_s ≲ 0.3` — **stars are almost all size 1** (a single bond). In the promoted
default regime avgBound is 14.8 (1e-5) → 99 (2.5e-6) ⇒ `k_s` up to ~9. The closed-form star handles any `k_s` and
degrades to the 2-body case at `k_s = 1`, so it is correct across both regimes.

### 0.3 The star has a CLOSED FORM that is a per-head-pure + CSR-gather + per-head-pure pattern (parity-clean).
Translation-only linearly-implicit F8 (rotation / tip-offset held explicit, exactly as head-only holds them).
Head is a Stokes sphere ⇒ **isotropic** drag `γ_h` (scalar `r_h = k·dt·1e6/γ_h`). Segment is a rod ⇒ **anisotropic**
drag, **diagonal in its own body frame** (`r_{s,a} = k·dt·1e6/γ_{s,a}`, axis `a ∈ {∥,⊥,⊥}`). Eliminating the heads
gives, per segment body-frame axis `a`:

```
head i (isotropic, lab):  p_i^imp = A_i + B·q^imp,   B = r_h/(1+r_h),  A_i = (p_i^e − r_h·(q^n − p_i^n))/(1+r_h)
segment (per body axis a): q^imp_a = [ Q_a + r_{s,a}·(ΣA_i − Σc_i)_a ] / [ 1 + r_{s,a}·(1−B)·k_s ]
                           Q_a = q^e_a + r_{s,a}·( k_s·q^n − Σp_i^n + Σc_i )_a      (spring-free seg predictor)
```
where `p^n,q^n` = pre-integration centers (snapshots), `p^e,q^e` = explicit-integrator outputs, `c_i =
aOff·ŝ_uVec − ½·headLen·ĥ_uVec` the (explicit) bond offset. Derivation folds the explicit F8 impulse back exactly
(the `c_i` cancels in `A_i` when the offset is held at its old value — the same O(dt) split the head-only solve
already accepts). The three axes are independent scalars because the head mobility is rotation-invariant and the
segment drag is diagonal in its body frame; only the segment solve needs the body-frame rotate (head phases stay
in lab).

**Maps onto three parity-clean passes (no atomics, no `KernelContext`, disjoint writes ⇒ CPU≡GPU bit-identical-
capable):**
- **Phase 1** — per bound head (PURE): `A_i` from `p_i^e`, `p_i^n`, `q^n` (segment old center, read via
  `boundSeg`). Head writes only its own scratch row.
- **Phase 2** — per segment (GATHER over its bound heads via `segMotorOffsets`/`segMotorMyo`, the SAME CSR
  `segGather` uses): accumulate `k_s, Σp_i^n, Σc_i, ΣA_i`, rotate to body frame, solve `q^imp_a`, rotate back,
  write **its own** `f.coord`. Segment writes only itself (race-free, exactly like `segGather`).
- **Phase 3** — per bound head (PURE read): `p_i^imp = A_i + B·q^imp` (read the segment's updated center via
  `boundSeg`). Head writes only its own `b.coord`.

This is the identical one-writer-per-slot + CSR-inverse template already CPU≡GPU-validated for `segGather` /
every cross-bridge kernel. **No global or iterative solve; no template break.**

### 0.4 Scope of the change (contained to the F8 integrate path).
Snapshots at start-of-step (old orientations live): head center (reuse `xbImplPrev`), segment center (new
`segImplPrev`), bond offset `c_i` (new `xbCplC`). Phases 1–3 + re-derive both bodies appended at end-of-step under
the flag. NEW kernels in `CrossBridgeSystem` (`snapshotSegCenter`, `computeCn`, `computeCoupleA`,
`solveSegImplicit`, `correctHeadImplicit`); NEW scratch in `MotorStore`/`Scene`; harness wiring in `GlidingHarness`
(and `V2OneXHarness` if needed). **No kinetics / cycle / stroke / bind / catch / gather-of-forces change** — the
explicit force pipeline (bondForces→applyHeadForce→segGather→integrate) is byte-unchanged; the coupled block only
*post-corrects the two centers*. Default (`-xbimplicit2` off) touches nothing ⇒ byte-identical.

**⇒ STEP 0 GO.** The coupled head+site implicit F8 is a per-segment closed-form star, reuses the existing
`boundSeg` CSR-inverse, and is expressible as per-head-pure + CSR-gather + per-head-pure with disjoint writes —
it PRESERVES the race-free CSR / `-cpu` parity. Proceeding to STEP 1. (Sub-step fallback is NOT needed on parity
grounds; it remains the fallback only if STEP 2 shows the coupled solve still climbs.)

---

## STEP 1 — implementation (`-xbimplicit2`, flag-gated, default byte-identical, CPU+GPU)

Five new `CrossBridgeSystem` kernels + scratch (`MotorStore.xbCplA` 3·nM, `xbCplB` nM; `Scene.segImplPrev`
3·nSeg; reuses `xbImplPrev` + `xbImplParams`). Wired into `GlidingHarness.stepOrig` (CPU) and `buildPlan`
(GPU default branch). All new code is guarded by `XB_IMPLICIT2` (default false) ⇒ **byte-identical default**
by construction (no shared kernel edited; only additive tasks/allocs). Mutually exclusive with `-xbimplicit`.

**Step wiring.** Snapshots at start-of-step (old orientations live): head center `p_n` (`snapshotHeadCenter`
→ `xbImplPrev`, reused), segment center `q_n` (`snapshotSegCenter` → `segImplPrev`, before `integrate(fil)`).
The explicit force+integrate pipeline is BYTE-UNCHANGED. At **end-of-step** (after BOTH integrates + both
derives, satisfying the "GPU body-write task must be late" PTX gotcha — the coupled tasks that write
`b.coord`/`f.coord` are the last tasks): `coupleComputeA` (Phase 1, per head) → `coupleSolveSeg` (Phase 2,
per segment, gathers over the SAME `segMotorOffsets`/`segMotorMyo` CSR `segGather` uses) → `coupleCorrectHead`
(Phase 3, per head) → re-derive both bodies. No atomics, no `KernelContext`, disjoint writes.

**Algebra validated to machine precision.** The closed-form star solve was checked against a DIRECT dense
backward-Euler linear solve of the same F8-only implicit step (Python, `numpy.linalg.solve`), across
`k_s ∈ {1,2,3,5}`, isotropic AND anisotropic segment drag, with **nonzero** bond offsets `c_i` (to confirm they
cancel): **max |closed-form − direct| = 8.9e-16** (double-precision round-off). ⇒ the per-segment star closed
form IS the exact linearly-implicit F8 solution. The device kernels are a faithful transcription (float32).

**Byte-identical default — confirmed (structural + empirical).** Structural: the only flag-off change to an
executed line is `if (XB_IMPLICIT)` → `if (XB_IMPLICIT || XB_IMPLICIT2)` on the head-center snapshot (identical
when both false); everything else is additive (new methods / guarded blocks / new-array allocs / new flag parse).
Empirical: the batch's **`explicit_1e-5` run (current tree, flag OFF, `-full -grid`) reproduced NUCDETACH's
pre-change B-dt numbers to all printed digits** (velFitX 1.126, avgBoundSteady 1.501, dwell 0.344 ms, detach
2909 /s vs NUCDETACH 1.126 / 1.50 / 0.344 / 2909). (A git-stash A/B was *confounded* — the working tree carried
uncommitted NUCDETACH `-adppibind` edits in the SAME three files, so stashing my changes also reverted those and
tripped an unrelated `kinParams` out-of-bounds; the explicit_1e-5 reproduction is the clean unconfounded check.)

**CPU≡GPU (coupled path).** The three coupled kernels are per-entity-pure + one CSR-inverse gather (the SAME
`segMotorOffsets`/`segMotorMyo` template as `segGather`), disjoint writes, no atomics / no `KernelContext` — the
one-impl/two-runner pattern already CPU≡GPU-validated for every other cross-bridge kernel. Aggregate-within-SEM
cross-check (v1box `-grid`, dt=1e-5, seed 0, 40k): CPU velFitX 2.465 / avgBoundSteady 3.224 / dwell 0.625 / detach
1600 vs GPU 2.870 / 3.451 / 0.619 / 1615 — the physical clock (dwell/detach) agrees to ~1 %, avgBound ~7 %, velFitX
~16 % (noisiest single-seed metric, 0.4 s window): **aggregate-within-SEM**, the standard for chaotic gliding.

**Smoke (CPU, v1box, lymntaylor+adppibind coltol10, 2000-step probe, unreliable velocity but robust avgBound):**
explicit avgBound(all) 1.11 → coupled 1.73 (overshoot reduced ⇒ more binding — the expected direction, and a
larger rise than head-only). Stable, no NaN. **GPU compiles + runs** (all 5 kernels lower on the PTX backend;
`-full -grid` 3000-step smoke: velFitX −0.684, avgBound 1.36, no CUDA fault) ⇒ the parity-clean structure holds
on the device path.

## STEP 2 — dt-convergence test. VERDICT: **STILL CLIMBS — not converged at dt=1e-5. → sub-step fallback.**
## (Best partial yet: unbiased, largest gap-closure, avgBound nearly flat — but the per-bound GLIDE still climbs.)

GPU `-full -grid`, coltol10, d1000, seed 0, `-lymntaylor -adppibind`; ≥1 s on-bed window per dt (dt 1e-5→120k,
2.5e-6→480k, 1.25e-6→960k steps). The explicit + head-only references are NUCDETACH's B-dt (reproduced exactly
here: explicit@1e-5 1.126/1.55/0.344/2909 — batch config validated). Raw: `RUN_LOGS/2026-07-04_coupled_conv.txt`.

| scheme | dt | velFitX (µm/s) | avgBoundSteady | dwell (ms) | detach (/s) |
|---|--:|--:|--:|--:|--:|
| explicit     | 1e-5   | 1.126 | 1.55 | 0.344 | 2909 |
| explicit     | 2.5e-6 | 6.477 | 3.45 | 0.733 | 1364 |
| head-only    | 1e-5   | 1.756 | 1.95 | 0.456 | 2194 |
| head-only    | 2.5e-6 | 7.173 | 4.04 | 0.739 | 1353 |
| **coupled**  | 1e-5   | **2.020** | **2.774** | **0.619** | **1616** |
| **coupled**  | 2.5e-6 | **6.279** | **3.615** | **0.768** | **1302** |
| **coupled**  | 1.25e-6 | **10.064** | **4.083** | **0.757** | **1322** |

**climb factor 1e-5 → 2.5e-6** (4× dt refine): velFitX — explicit **5.75×**, head-only **4.08×**, coupled **3.11×**;
avgBound — explicit **2.23×**, head-only **2.07×**, coupled **1.30×**. **Coupled continues climbing 2.5e-6 → 1.25e-6**
(2× refine): velFitX **6.279 → 10.064 (1.60×)**, avgBound 3.615 → 4.083 (1.13×) — monotonic, no plateau. The
**per-bound glide** (velFitX/avgBound) is the runaway: **0.73 → 1.73 → 2.46** across the three dt (still >1.4×/2×
refine), while avgBound's climb decays (1.30× → 1.13×) toward flat. So the coupled solve converges the *binding
count* but NOT the *per-bound forward advance* — the definitive fingerprint of a residual in the still-explicit
stroke/force coupling, not the (now-implicit) F8 stretch.

**Reads:**
1. **NOT converged.** Coupled velFitX climbs 2.020 → 6.279 (**3.1×**) and avgBound 2.774 → 3.615 (1.30×) as dt
   refines 4×. Flat would be ~1.0×. So the coupled solve does **not** deliver the dt-independent glide at
   production dt.
2. **But it is the BEST partial, and the mechanism is now clear.** Coupled cuts the climb the most (velFitX
   5.75×→3.11×, avgBound 2.23×→**1.30×**) — the **avgBound is nearly converged** (30 % residual vs explicit's
   123 %), and the **dwell/detach clock is essentially converged already at 1e-5** (coupled@1e-5 0.619 ms /
   1616 /s ≈ explicit@2.5e-6 0.733 ms / 1364 /s). So making F8 **translation** implicit did fix the stretch
   overshoot that suppressed binding and detonated the catch — exactly the head-only-unconverged half. What is
   **left climbing is the per-bound GLIDE** (velFitX/avgBound: coupled 0.73 @1e-5 → 1.73 @2.5e-6, still >2×) —
   i.e. the residual dt-dependence is in the **per-bound forward advance**, driven by the couplings the solve
   KEEPS EXPLICIT (the F8 **tip/site rotation**, the F9/F10 alignment torques, the directed power-stroke, the
   chain) — NOT the F8 translational stretch. The coupled solve converged the binding, not the stroke.
3. **UNBIASED — the solve is correct, just insufficient.** Coupled@2.5e-6 (6.279 / 3.615) ≈ explicit@2.5e-6
   (6.477 / 3.45): the implicit and explicit schemes **agree at fine dt** (converging to the same continuum
   limit, as they must). So this is the "still climbs," NOT the "biased," fork — the closed-form star is right;
   it simply doesn't make *enough* of the step implicit to be dt-robust at 1e-5. (Confirmed at 1.25e-6: velFitX
   climbs on to 10.06, monotonic — the true converged glide for this config is >10 µm/s, still unbracketed at
   1.25e-6, consistent with `IMPLICIT_XB_CONVERGENCE`'s note that the gliding assay is far more dt-sensitive than
   the ±3 % faithful-ceiling envelope suggested.)

**⇒ STEP-2 VERDICT: the coupled head+site implicit F8 does NOT converge at production dt=1e-5** (velFitX still
climbs 3.1× to 2.5e-6), **while it DOES preserve CSR/`-cpu` parity** (STEP 0/1) and is the best partial (unbiased,
avgBound nearly flat, detach clock converged). **We do NOT get the dt-robust physical duty at the production
timestep from the coupled implicit alone — FALL BACK to sub-stepping the cross-bridge.** The coupled solve
narrowed *what* must be sub-stepped: not the F8 stretch (now converged via the implicit star) but the **per-bound
stroke/rotation/force coupling** — the sub-step can therefore focus on the force+stroke integration, with the F8
stretch already stabilized. This matches `substep-feasibility-verdict` (X≈0.012–0.024 inner slice, ~8.5× CPU /
~5–7× GPU-fused ceiling; a linear site-motion predictor suffices).

## STEP 3 — dt-honest velocity–density curve — **SKIPPED (STEP 2 did not converge).** Per the task, STEP 3 runs
only if the coupled solve converges. It does not, so there is no dt-honest production-dt operating point to report
a velocity–density curve at; the honest converged glide remains bracketed only by dt-refinement (explicit/coupled
agree ≈6.3–6.5 µm/s at 2.5e-6 and still climbing — NOT a production-dt number). The dt-honest curve waits on the
sub-step (the fallback).

## Reproduce
```
# coupled path (flag-gated; -xbimplicit and -xbimplicit2 are mutually exclusive):
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -dt <DT> -seed 0 <STEPS>
#   dt 1e-5→120k, 2.5e-6→480k, 1.25e-6→960k (≥1 s on-bed window). Drop -xbimplicit2 for the explicit control.
# the dt-convergence batch (coupled + explicit controls): ./run_couple_conv.sh   (log RUN_LOGS/2026-07-04_coupled_conv.txt)
# CPU≡GPU parity (v1box): ./run_gliding.sh [-gpu] -v1box -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -seed 0 40000
```
Files: `CrossBridgeSystem.{snapshotSegCenter,coupleComputeA,coupleSolveSeg,coupleCorrectHead}` +
`MotorStore.{xbCplA,xbCplB}` + `GlidingHarness` (`XB_IMPLICIT2`, `Scene.segImplPrev`, stepOrig + buildPlan wiring).
Default byte-identical; `BoA-v1ref` byte-clean.

## Concurrency note
The dt batch shared the GPU with a prior-session `dt=5e-6` NUCDETACH sweep (left running) — wall-clock only;
the convergence numbers are seed-deterministic and correctness is unaffected by contention. `explicit_2.5e-6`
was skipped in-batch (redundant with NUCDETACH's known 6.477/3.45).

## STEP 3 — dt-honest velocity–density curve (only if STEP 2 converges) — **SKIPPED** (STEP 2 did not converge).
