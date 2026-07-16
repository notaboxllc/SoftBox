# Phase 4/5/6 — Validation gates & preregistered numerical tolerances

Preregistered BEFORE any GPU numbers exist, so a gate cannot be back-fit to a result. Two comparison
regimes, per the CLAUDE.md CPU≡GPU standard:
- **Deterministic / short-horizon (isolated step, prescribed sequence, static geometry):** bit-exact
  where both sides are the same precision; otherwise a tight float tolerance. RNG is counter-based ⇒ the
  random STREAM is bit-identical CPU↔GPU by key; only float op-ordering in the mechanics differs.
- **Chaotic many-body / long-horizon (gliding trajectory):** float32 op-order decorrelates the microstate
  (Lyapunov). Bit-identity is unattainable and is NOT the test — the standard is **aggregate-statistical
  agreement within SEM**, and the **CPU-double runner is the basin arbiter** for any A/B or reported number
  (GPU-number-trust rule: the gliding steady state is chaotic + bistable).

## Tolerance ladder (preregistered)

| tier | quantity | tolerance | rationale |
|------|----------|-----------|-----------|
| T0 frozen params | GPU packed constants vs `MotorModel.{calibrated,explicit}()` | **exact `==`** | matches `assertFrozenParamsConsistent`; a GPU constant that differs by 1 ULP fails |
| T1 RNG stream | wang-hash draw for a given `(m,step,seed,salt)` | **bit-identical** | pure integer hash; must match or the port mis-keyed |
| T2 CSR gather | `segGather` force/torque onto actin (integer CSR) | **bit-identical** | integer counting-sort ⇒ order-independent; the inc-5/6 standard |
| T3 calibrated isolated step (float32 GPU vs double CPU) | pose `A,φ,ψ`; bondData[0..11]; supForce | **rel ≤ 1e-4**, abs ≤ 1e-6 (µm / pN) | analytic 5×5; float32-vs-double rounding only, no chaos |
| T4 explicit isolated step (double GPU vs double CPU) | node coords; end pose; reaction F/τ; contour error; bending energy | **rel ≤ 1e-6** node/pose, contour error within CPU's own per-step drift | same double arithmetic; only op-order/FMA differences |
| T4′ explicit isolated step (float32 analytic-Jacobian GPU, IF pursued) | same | **rel ≤ 1e-3** pose, contour error ≤ 2× CPU drift | float32 tolerance; must be justified as exact-Jacobian, not a surrogate |
| T5 short prescribed sequence (N≤50 steps, Brownian OFF) | terminal pose + reaction | T3/T4 tolerance, accumulated | deterministic; drift must stay bounded, not grow super-linearly |
| T5b short prescribed sequence (Brownian ON, same seed) | terminal pose | statistical: within the CPU seed-to-seed SD | chaotic even short; RNG stream identical but mechanics decorrelate |
| T6 ensemble gliding (n≥8 seeds) | velMean, avgBound, continuity, handoff, ATP/µm, net axial force balance, propulsive/dragging counts | **within SEM** (|Δ| ≤ 2·SEM of the CPU ensemble) AND CPU-arbiter cross-check agrees | the production faithfulness gate; matches how v1 CPU-vs-GPU agree |
| T7 half-dt consistency | velMean, avgBound at dt and dt/2 | GPU shows the SAME dt-trend as CPU (the ts_cull relation), within SEM | dt convergence must not change on device |
| T8 solver failure/continuity | explicit beam: fraction of steps with contour error > threshold; NaN/degenerate | **≤ the validated CPU rate** | no new beam-solve failures beyond CPU |

## Per-phase gate lists (mirror the task's Phase 4/6 requirements)

**Phase 4 — calibrated GPU (gates 1–7):**
1. T0 frozen params exact.  2. T3 isolated force+pose.  3. stroke & recoil (impose ADPPI→ADP, compare the
resulting Δpose/Δpivot) within T3.  4. T5 short prescribed sequences.  5. **no missed candidates** — under
matched cull/bind inputs the GPU active-set == CPU active-set (set equality, exact) and the bind-gate
decision bit matches per motor.  6. T6 ensemble gliding.  7. T7 half-dt.
Rule: do NOT claim validation from a single matched trajectory when RNG ordering differs — use BOTH the
replay (T3–T5) AND the ensemble (T6) with the CPU-double arbiter.

**Phase 5 — explicit beam microbenchmark (thousands of saved configs):** compare node positions, end
pose, reaction force/torque, contour preservation, bending energy, axial/tangent stiffness where
measurable, iteration count (fixed here — no inner loop), convergence/failure rate. **Report full
distributions (median, p95, max), not only maxima.** Special attention: variable convergence across
motors (should be NONE — the production step is fixed-work), warp divergence (only the 14×14 partial
pivot + floor branch), pathological near-buckling states, contour-error accumulation, and single- vs
double-precision sensitivity (expect float32 to FAIL the FD tangent → the analytic-Jacobian evidence).

**Phase 6 — explicit integrated GPU:** T4 isolated mechanics; short-trajectory stability; load-bearing
fraction; avgBound; continuity; handoff; signed velocity; transverse/angular wander; ATP/µm; propulsive
vs dragging decomposition; beam-solve failure rate ≤ CPU; contour preservation; T7 dt convergence; cull
adequacy. The current explicit-S2 CPU sweep results are the REFERENCE and must not be overwritten.

## Stop / bail conditions (per the task)
Stop and report if: a CPU/GPU discrepancy cannot be explained by float op-order or RNG-stream differences
(→ a logic bug, not float noise — recall the >0.1 % systematic = logic signal, <0.1 % = float32 floor
rule); the port would require changing validated motor mechanics; or a smoke test perturbs the sweep.
Never conceal incomplete validation or substitute approximate mechanics for the explicit beam.
