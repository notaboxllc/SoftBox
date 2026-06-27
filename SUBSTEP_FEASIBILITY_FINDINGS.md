# SUBSTEP_FEASIBILITY — should we build the bound-cross-bridge sub-step inner loop?

**MEASUREMENT-ONLY / ADDITIVE, flag-gated `-substep` (default-off ⇒ byte-identical; no integrator change, no new
physics).** Decides, BEFORE building any inner loop, the two numbers the sub-stepping task hinges on:
**(Measure 1)** the SPEEDUP CEILING — bound fraction × the bound-cross-bridge slice's CPU-time fraction X ⇒ the
implied net speedup at a 10× inner ratio; and **(Measure 2)** the SITE-HANDLING tier — how far the bound site moves
in one outer dt vs the cross-bridge operating length, directed-vs-diffusive. Both measured on faithful existing
runs. `BoA-v1ref` untouched; production default unchanged. Sole occupant of aorus.

Instruments: `SiteMotionTracker` (host-side per-bound-motor site tracking over outer-dt windows) wired into
`V2OneXHarness` (dense 1× + the 0.5× dt-study scene) and `GlidingHarness` (4×1 v1box), + inline CPU slice timers.
Raw: `RUN_LOGS/2026-06-26_substep_feasibility.txt`. Reproduce: `./run_substep.sh all` (CPU; ~2.5 h).

## TL;DR verdict — **BUILD IT, with INTERPOLATED-SITE handling.** A ~5–8× lever, not a ~1.5× one.

- **Measure 1 — the slice is TINY and scale-invariant ⇒ the ceiling is HIGH.** The bound-cross-bridge work (F8/F9/F10
  + applyHead + register + catch-slip release + the BOUND-head advance) is only **X ≈ 0.012–0.024 of the per-step CPU
  wall** in every scene (the step is dominated by the binding search, the filament chain, crosslinkers, node tethers,
  and the FULL motor-body advance — none of which the inner loop repeats). ⇒ **implied net speedup ≈ 8.5×** at a 10×
  inner ratio, **roughly constant from gliding to the dense 1× scene** (X stays ~0.017 because slice and step both
  scale with bound count). This is a **big lever**, not the ~1.5× the pessimistic case feared.
- **Measure 2 — every scene is INTERPOLATED-SITE (the MIDDLE tier), not frozen, not co-stepped.** The site moves
  **~3 nm per outer dt (fine-dt)**, which is **comparable to the cross-bridge stretch** (net/|stretch| ≈ 0.7–1.0) ⇒
  **FROZEN-site would be unfaithful** (it discards a site motion as large as the bond it perturbs — the
  IMPLICIT_CROSSBRIDGE operator-split error, here measured directly). BUT the motion is **strongly DIRECTED**
  (drift/jitter ≈ 2.4–2.6, **93–95 % of windows directed** at fine dt) ⇒ a **cheap linear site-predictor captures
  most of it**; the **diffusive residual jitter is only ~1.2 nm** (≪ myoColTol 6 nm, ~0.4× the stretch) ⇒ the
  expensive **co-stepped/coupled solve is NOT required.**
- **Overall:** the economics (Measure 1) strongly favour building, and the site-handling requirement (Measure 2) is
  the affordable middle tier (interpolated, not co-stepped) **uniformly across all three scenes** — including the
  gliding stress-test, where the fast site is the most directed (95 %) and therefore the most interpolable.

## Measure 1 — the SPEEDUP CEILING (bound fraction × bound-work fraction X)

The inner loop repeats ONLY the bound-cross-bridge slice; everything else advances once per outer step. Net speedup
at a 10× inner ratio ≈ `10 / (1 + 9X)`, X = the slice's fraction of the per-step cost. Slice (frozen-site CORE) =
`bondForces + applyHeadForce + registerForceDot + catch-slip release + the BOUND-head advance` (Brownian re-drawn +
integrate + derive on the bound heads only = `boundFrac/3` of the motor-body advance, since a bound head is 1 of
3·nMotors sub-bodies). The motor→seg GATHER onto the filament is added in the FULL (co-stepped) variant. CPU runner
(work-bound — the regime where the repeated-work cost is real); GPU launch-count given separately below.

| scene | dt | bound / total (frac) | step ms | slice CORE ms | **X** | **ceiling** | X full / ceiling |
|---|---|---|---|---|---|---|---|
| **gliding** 4×1 | 1e-5 (v1-calib op) | 7.5 / 2000 (**0.37 %**) | 2.72 | 0.053 | **0.0196** | **8.50×** | 0.0218 / 8.36× |
| **gliding** 4×1 | 1e-6 (fine) | 18.1 / 2000 (0.91 %) | 2.74 | 0.059 | 0.0216 | 8.37× | 0.0239 / 8.23× |
| **0.5× dt-study** | 1e-5 (op) | 441 / 4800 (9.2 %) | 30.8 | 0.350 | **0.0114** | **9.07×** | 0.0143 / 8.86× |
| **0.5× dt-study** | 1e-6 (fine/conv) | 788 / 4800 (16.4 %) | 31.1 | 0.519 | 0.0167 | 8.69× | 0.0199 / 8.48× |
| **dense 1×** (flagship) | 1e-5 (op) | 907 / 9600 (9.4 %) | 64.3 | 0.786 | **0.0122** | **9.01×** | 0.0151 / 8.80× |
| **dense 1×** (flagship) | 1e-6 (fine/conv) | 1605 / 9600 (16.7 %) | 65.2 | 1.128 | 0.0173 | 8.65× | 0.0204 / 8.45× |

- **The slice is launch/loop-overhead-cheap.** Even in the dense 1× scene the whole bound-cross-bridge slice is ~1.1
  ms of a ~65 ms step. The per-step cost is dominated by the binding search (grid build + reachable), the filament
  chain (F3/F4), crosslinkers (formation + force + Bell unbind + 2-pass gather), node tethers/gathers, and the FULL
  motor-body Brownian+integrate over all 3·nMotors sub-bodies — **none repeated by the inner loop.**
- **Scale-invariance (the favourable scaling the task predicted).** Dense 1× (9600 motors) and the 0.5× scene (4800
  motors) give **near-identical X** (0.0173 vs 0.0167 at fine dt): the slice and the step both scale ~linearly with
  bound count, so the **ceiling holds ~8.7× at scale** — sub-stepping does not erode as the scene grows. (At a much
  larger scale where filament/crosslinker work outgrows the motor count, X would *shrink* further ⇒ ceiling rises
  toward 10×.)
- **Bound fraction (the converged operating point).** Dense/0.5× converge to **~16–17 % bound** at fine dt (the
  "moderate" the task expected for dense). Gliding is **<1 % bound** (the tiny-bound-fraction stress case — at its
  v1-calibrated 1e-5 operating point avgBound 7.5 ≈ v1's 7.6, 0.37 %). Either way the slice is small; the speedup
  ceiling is set by the slice's COMPUTE fraction, which is tiny in all cases.

### GPU launch-count cross-check (the deployed device path)

The device path is kernel-COUNT-bound (~8000 launches/s, fixed ~125 µs/launch — `PROFILE_FULLDEMO_FINDINGS`). So on
the GPU the slice's relative cost is a **launch-count fraction**, and because the bound work is tiny each repeated
kernel still costs a full launch ⇒ **GPU X_launch > CPU X_work ⇒ GPU ceiling LOWER**. Inner-loop slice ≈ 7 kernels
(bond, applyHead, register, release, head-Brownian, head-integrate, head-derive), **fusable to ~2–3** (all over the
bound-head set):

| runner | total kernels/step | slice kernels | X_launch | ceiling | fused (≈3) ceiling |
|---|---|---|---|---|---|
| gliding GPU | ~23 | 7 | 0.30 | 2.7× | 4.6× |
| dense 1× GPU | ~55 (G0–G3; G4 gated) | 7 | 0.13 | 4.6× | 6.7× |

⇒ **GPU ceiling ~2.7–6.7×**, recovered toward the top by **fusing the inner-loop kernels** into 1–3 over the bound
set (a build-phase task). Still a strong lever on both runners; the CPU number is the inner-loop COST argument, the
GPU number is the deployed-path cost.

## Measure 2 — the SITE-HANDLING REQUIREMENT (per-outer-dt site motion)

For each window (W = outerDt/dt steps) in which a motor stays continuously bound to the same segment, the tracker
records the bound site `= segCoord + (bindArc − ½·segLen)·segUVec` (exactly what `CrossBridgeSystem.bondForces`
reads) and reports the per-outer-dt **net displacement**, the least-squares **drift** (directed/interpolable) and the
RMS **jitter** about that line (diffusive/un-interpolable), vs the mean |stretch| (= forceMag/myoSpring) and
myoColTol (6 nm). Outer dt = **1e-4 s**. Fine-dt (1e-6) rows are the faithful/converged measurement; W=100 samples.

| scene | dt | net mean / p90 / max (nm) | drift / jitter (nm) | drift/jit | directed % | mean|stretch| (nm) | **net/stretch** | net/tol | tier |
|---|---|---|---|---|---|---|---|---|---|
| gliding | 1e-5 | 4.90 / 7 / 32.2 | 4.67 / 2.26 | 2.07 | 88.7 % | 6.13 | 0.80 | 0.82 | INTERP |
| **gliding** | **1e-6** | **3.09 / 4 / 11.8** | **3.14 / 1.21** | **2.60** | **94.6 %** | 4.21 | **0.73** | 0.51 | **INTERP** |
| 0.5× | 1e-5 | 4.65 / 7 / 16.6 | 4.54 / 2.07 | 2.19 | 90.5 % | 4.26 | 1.09 | 0.77 | INTERP |
| **0.5×** | **1e-6** | **2.96 / 4 / 12.0** | **3.06 / 1.24** | **2.46** | **93.2 %** | 2.85 | **1.04** | 0.49 | **INTERP** |
| dense 1× | 1e-5 | 4.64 / 7 / 16.6 | 4.54 / 2.07 | 2.19 | 90.5 % | 4.49 | 1.03 | 0.77 | INTERP |
| **dense 1×** | **1e-6** | **2.93 / 4 / 13.6** | **3.03 / 1.24** | **2.45** | **93.2 %** | 2.85 | **1.03** | 0.49 | **INTERP** |

- **Frozen-site is NOT faithful (any scene).** net/|stretch| ≈ 0.7–1.0: the site moves about as far in one outer dt as
  the bond it stretches, so freezing it injects a load error ≈ the full net motion (~3 nm × k 1 pN/nm ≈ **~3 pN**),
  comparable to the mean cross-bridge tension (~2.85 pN converged). This is the explicit-SITE operator-split error
  `IMPLICIT_CROSSBRIDGE §"the split error MATTERS"` named — **measured directly here**, and it is large.
- **But the motion is DIRECTED ⇒ interpolation captures it.** drift/jitter ≈ 2.4–2.6 (fine dt), 93–95 % of windows
  directed: over 1e-4 s the site moves mostly **coherently** (gliding glide; contractile collective pull + chain
  relaxation), with only a small thermal wiggle. A linear site-predictor reduces the site error from the full net
  (~3 nm) down to the **diffusive residual jitter ~1.2 nm** (~0.4× the stretch, ~0.2× myoColTol) — a ~2.5× reduction.
- **Co-stepping is NOT required.** The diffusive (un-interpolable) part is small (jitter ~1.2 nm ⇒ ~1.2 pN residual
  load), well under myoColTol and below the mean tension. The expensive co-stepped-neighbourhood / fully-coupled
  solve would only be needed if the site motion were large AND diffusive — it is large but directed.
- **Gliding (the stress test) is the MOST interpolable, not the least.** The task flagged gliding's fast site as the
  worst case for frozen-site — confirmed (net/stretch 0.73, frozen unfaithful) — but precisely because it is fast it
  is the **most DIRECTED** (95 % directed, drift/jitter 2.6), so the linear predictor works best there. The hard case
  for interpolation would be a *slow diffusive* site; no scene exhibits one.
- **dt-robustness of the conclusion.** The picture is identical at the operating dt (1e-5) and the converged fine dt
  (1e-6): net/stretch ≈ 1.0 and directed both times. Fine dt sharpens it (net 4.6→2.9 nm as the unconverged 1e-5
  over-stretch relaxes; mean|stretch| 4.3→2.85 nm tracks the converged 2.8 pN tension; directed-fraction rises).

## The decision (per scene + overall)

| scene | bound frac | ceiling (CPU / GPU-fused) | site tier | per-scene call |
|---|---|---|---|---|
| **gliding** (tiny bound, fast site) | <1 % | 8.4× / 4.6× | INTERPOLATED (95 % directed) | **build — cheap; interpolate the fast directed site** |
| **0.5× dt-study** | ~16 % | 8.7× / — | INTERPOLATED (93 % directed) | **build — cheap; interpolate** |
| **dense 1×** (flagship) | ~17 % | 8.7× / 6.7× | INTERPOLATED (93 % directed) | **build — cheap at scale; interpolate** |

**Overall recommendation: BUILD the bound-cross-bridge + catch-slip-release sub-step inner loop, with an
INTERPOLATED-SITE predictor** (linear extrapolation of each bound site from the outer-step trajectory), NOT frozen-
site and NOT the co-stepped/coupled solve.

- **Why build (Measure 1):** the repeated slice is ~1–2 % of the step on CPU (8.5× ceiling) and ~13–30 % of the
  launches on GPU (2.7–6.7× ceiling, → ~5–7× with inner-kernel fusion), **scale-invariant**. The economics clear the
  bar in every scene — this is the lever the IMPLICIT_CROSSBRIDGE finding pointed at (the cheap local-implicit head
  bought only ~2×; sub-stepping's ceiling is ~5–8×).
- **Why interpolated, not frozen (Measure 2):** frozen-site injects a ~3 pN load error (net/stretch ≈ 1.0) — the same
  explicit-site operator-split error that capped the implicit head — so frozen would re-import the very error
  sub-stepping is meant to beat. The site motion is directed (93–95 %), so a linear predictor removes most of it.
- **Why not co-stepped:** the diffusive residual after interpolation is small (~1.2 nm, ~1.2 pN, ≪ tol) ⇒ the
  coupled/co-stepped neighbourhood (approaching Cytosim's implicit solve, expensive) is not warranted by these
  scenes. **Caveat:** interpolated-site does NOT zero the site error — a ~1.2 pN diffusive residual remains per outer
  step; if the catch proves sensitive to exactly that residual (the fast diffusive load excursions), the reduced-pair
  / co-stepped site is the documented next tier. But the head's own fast thermal — the dominant catch driver — IS
  resolved by the inner loop, which is the point.

## Premise checks (flagged, supported but not re-proven here)
- The 10× speedup model assumes ONLY the cross-bridge+release needs the fine inner dt; the rest (filaments, chain,
  crosslinkers, binding search) is stable at the 1e-4 outer dt. Supported by the existing findings (the dt ceiling is
  set by the cross-bridge overshoot → force-dependent release — `dt-faithful-ceiling`; the binding-search flux is
  ~dt-invariant — `BINDING_SEARCH_REFORMULATION`). A coarse-outer-dt stability check of the non-cross-bridge
  subsystems is the first build-phase gate.
- The site-motion windows count only motors continuously bound to the same segment for the full outer dt; motors that
  unbind/rebind mid-window are dropped (no inner loop applies to them). The directed-fraction is over completed bound
  windows.

## What was built (measurement-only, reverts to byte-identical default)
- `softbox/SiteMotionTracker.java` — host-side per-bound-motor site tracker (net / LS-drift / RMS-jitter over outer-dt
  windows); plain Java, no kernel/atomics/device work.
- `V2OneXHarness` / `GlidingHarness`: `-substep` (+ `-outerdt <s>`, default 1e-4) — instantiate the tracker over the
  settled 2nd half, time the bound-cross-bridge slice (`tns()` accumulators, zero overhead when off), and print the
  Measure-1 + Measure-2 report. All gated by `if (SUBSTEP)` ⇒ default path byte-identical (`tns()` returns 0, no
  nanoTime, no tracker).
- `run_substep.sh` (`smoke` | `all`). No shared system/kernel touched; production default unchanged; `BoA-v1ref` clean.
