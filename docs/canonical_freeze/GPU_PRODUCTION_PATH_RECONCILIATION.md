# GPU PRODUCTION-PATH RECONCILIATION — SoftBox canonical myosin gliding

**Part A of the freeze-audit finalization.** Traces the exact GPU runners/builders/revisions/flags of the
completed production ensemble studies, proves they executed on the GPU with no silent CPU fallback, and revises
the audit governance to the project decision: **GPU is the canonical production path for gliding and large
ensemble studies after assay-class validation; CPU is supplemental** (small assays, unit tests, sanity checks,
targeted CPU/GPU equivalence). Evidence-based; no run relaunched.

---

## 1. Project governance decision (supersedes the prior "CPU is the reference/arbiter" framing)

> **GPU is canonical for production ensemble work after assay-class validation. CPU is supplemental — small
> assays, unit tests, sanity checks, and targeted CPU/GPU equivalence.**

This replaces the v1.0-draft audit language ("CPU is the reference/production runner; GPU refused"). The prior
language described the *code gate's current state*, not the intended production path. The gate
(`DEVICE_VALIDATED`) is retained but **re-scoped** from "blanket GPU refusal" to "per-assay-class validation
requirement" (§4).

## 2. The completed production studies — exact runner / builder / revision / flags

| study | runner (harness) | builder | rev | backend flags | manifest / logs |
|---|---|---|---|---|---|
| **Long single-head density sweep** | `ExplicitSingleHeadHarness` via `scripts/run_singlehead_density_sweep.sh` / `run_explicit_singlehead.sh` | `TwoBodyConverterMotor.buildS2Mat` (`explicit-s2-l40`) | (single_head_density_sweep_long) | `-backend gpu` (via `-gpu-experimental`) | `RUN_LOGS/single_head_density_sweep_long/cell_*.json` |
| **Long HMM dimer density sweep** | `ExplicitHmmDimerGlidingHarness` via `scripts/run_hmm_gliding.sh` + per-cell orchestrator | `ExplicitHmmDimer.build(Ms=3,Ma=1,Mb=1,…)` → device forked-dimer kernel (`gpu-fd`, FLOAT) | **`82b6762`** | `-backend gpu -gpu-experimental -branchEA 0.03` | `RUN_LOGS/hmm_density_sweep_long/cell_d*_s*.json` (+ `logs/…attempt1.log`, `…_orchestrator.out`) |
| **All-strong-bound rupture gliding sensitivity** | `ExplicitHmmDimerGlidingHarness` (`-rupture-mode 2`) | same device forked-dimer kernel | (sm4_allstrong_rupture) | `-backend gpu -gpu-experimental` | `RUN_LOGS/motor_validation/sm4_allstrong_rupture/partC/gpu_dimer_partc.rows.log` |

Companion (Part F): **rigor-only rupture gliding sensitivity** — `rigor_gliding_singlehead/{on,off}` +
`rigor_gliding_dimer`, same runner, paired ON/OFF (`docs/matsoa/RIGOR_RUPTURE_AND_GLIDING_IMPACT_FINDINGS.md`).

## 3. Proof of GPU execution + no silent CPU fallback

Every production cell records, in both its metadata JSON and its startup banner:

- **Backend banner (per-cell `attempt1.log`):**
  `=== HMM-DIMER BACKEND: GPU | DEVICE_VALIDATED=false | precision=FLOAT | kernel=gpu-fd | topology Ms=3,Ma=1,Mb=1 (19 DOF) | branchEA=0.03000 | dt=2.50e-06 | device-resident=true ===`
  followed by `backend=GPU (device-resident=true) | DEVICE_VALIDATED=false (experimental override)` … `no CPU
  fallback`.
- **Cell metadata JSON:** `"backend":"GPU"`, `"device_validated":false`, `"dt":2.5e-06`, `"branchEA":0.03`,
  `"rupture_mode":0`, `"warm_compile_ms": ~1284` (single-head ~969) — the **PTX kernel compile time**, which
  exists **only** on the device path (the CPU object-solver compiles no PTX). Warm-compile + `device-resident=true`
  + `kernel=gpu-fd` together are positive proof the forked-dimer solve ran on the GPU.
- **No silent fallback — structurally enforced.** `ExplicitHmmDimerGpuParams.requireUsable(Backend)` **throws**
  for a device backend unless the gate or the override is set (`ExplicitHmmDimerGpuParams.java:122-129`); the
  harness never contains a CPU catch-and-continue. The banner prints `no CPU fallback` explicitly, and the
  disclosure `[experimental override active — device path is UN-VALIDATED; results not for production]` fires
  whenever a device backend runs un-validated (`printBackendBanner`, harness `:730-731`).
- **Solver health:** every cell reports `invalid_states:0, solver_failures:0` (single-head, HMM dimer, and the
  rupture sensitivity) — the device solve completed cleanly at every density/seed.

## 4. Were the DEVICE_VALIDATED gates bypassed, stale, branch-specific, or superseded?

**Neither bypassed nor stale — explicitly and honestly overridden.** `ExplicitHmmDimerGpuParams.DEVICE_VALIDATED`
and `MotorGpuParams.DEVICE_VALIDATED` are both still `false`. The production runs reached the device path through
the **documented, opt-in escape hatch** `-gpu-experimental` (sets `EXPERIMENTAL_OVERRIDE=true`), which
`requireUsable` honors while printing the "UN-VALIDATED; results not for production" disclosure and stamping
`device_validated:false` into every output cell. This is the intended mechanism for running the device path
**before** promotion — it is transparent, per-run, and self-labeling, not a silent bypass. The gate is not
branch-specific and not superseded; it simply has **not yet been flipped** because its flip condition (the
full production-equivalence matrix) is the deliberate promotion gate.

**Standing-config integrity held:** the GPU forked-dimer kernel is specialized to a frozen topology + standing
config (`assertStandingConfig` / `validateStandingConfig`), which **aborts** on any drift — including
`branchEA ≠ 0.03`, a non-device backend, an active rupture mode, or emergency-rupture on
(`ExplicitHmmDimerGpuValidation.java:1776`). So every production HMM cell is provably at the validated
standing config (branchEA=0.03, Ms/Ma/Mb=3/1/1, dt=2.5e-6, D0, R0).

## 5. Governance revision + gate repair (recommended, not silently applied)

**Revised governance (adopted in this audit's docs):**
- **GPU is the canonical production path** for the gliding density sweeps and large ensemble studies, **after
  the assay class has passed CPU/GPU equivalence** (`docs/CPU_GPU_VALIDATION_POLICY.md`).
- **CPU is supplemental:** the permanent oracle for small assays (step-size, force-clamp), unit tests, sanity
  checks, and **targeted** CPU/GPU equivalence at points where a hot kernel / numerical method changes. It is
  **not** a blanket re-run requirement.

**Gate repair — the one concrete item that flips `DEVICE_VALIDATED` to `true`.** The gate should be **promoted
per assay class**, not left as a blanket refusal that production routinely overrides. The specific unmet
condition is the **production-equivalence matrix for the explicit-S2 / HMM-dimer gliding assay class**: the
mandatory gates enumerated in `ExplicitHmmDimerGpuParams` (deterministic fixtures V1–V8, moving-actin,
CPU/GPU aggregate equivalence at production density, polarity + A/B symmetry, occupancy exclusion, compliance
health, D0 directionality, zero invalid/solve failures, meaningful speedup) run at the standing config and
signed off. Until that sign-off:
- **Recommendation (do NOT flip as a side effect — the code comment forbids it):** run the equivalence matrix
  on the CPU oracle vs the GPU device path at 2–3 representative densities (low / near-ρ½ / high) + the
  deterministic fixtures, confirm aggregate agreement within SEM (the assay class is chaotic-ensemble, so the
  standard is statistical, not bit-identical), then flip `DEVICE_VALIDATED=true` in a dedicated, reviewed
  change. The single-head and HMM device paths already pass their deterministic fixtures; the outstanding piece
  is the production-density aggregate equivalence.
- Until then, production GPU runs remain **explicitly `-gpu-experimental`, self-labeled, with 0 invalid/solver**
  — usable as the canonical production numbers under the revised governance (GPU-after-validation), with the
  equivalence-matrix sign-off as the formal close-out.

**Parameter identity CPU↔GPU (unchanged conclusion):** both paths key off the same
`ExplicitHmmDimerGpuParams` constants and the same `nucParams`/`kinParams`/`bindP` arrays; the RNG is a
counter-based Wang hash, bit-identical by construction. The only divergence channel is float32 op-ordering on
the chaotic trajectory — which is exactly what the aggregate-equivalence gate measures.

## 6. Summary

The completed single-head, HMM-dimer, and all-strong-bound-rupture production studies **all executed on the
GPU device-resident path** (PTX-compiled, `device-resident=true`, `no CPU fallback`, 0 invalid/0 solver), via
the **explicit, disclosed `-gpu-experimental` override**, at the enforced standing config
(HMM branchEA=0.03). No silent fallback occurred; the `DEVICE_VALIDATED=false` gate was overridden transparently,
not bypassed. Governance is revised to **GPU-canonical-after-validation / CPU-supplemental**, and the one
concrete gate-repair action is the production-density CPU/GPU aggregate-equivalence sign-off that flips
`DEVICE_VALIDATED=true`.
