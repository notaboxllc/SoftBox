# GPU CANONICAL PRODUCTION SIGN-OFF — Part C of the freeze-closure

**Date 2026-07-22 · canon v2.** Records the per-assay-class validation model that makes **GPU the normal
canonical production path** for the gliding / large-ensemble assay classes, removes the user-facing
`-gpu-experimental` override requirement for the validated classes, and preserves hard-failure (no silent
fallback) for unvalidated classes. Builds on the trace in `GPU_PRODUCTION_PATH_RECONCILIATION.md`; **no
production sweep was rerun** — the existing metadata + CPU/GPU equivalence evidence are sufficient.

---

## 1. Governance (the project decision, now realized in code)

> **GPU is the canonical production path for gliding and large ensemble studies. CPU is supplemental** — the
> permanent oracle for unit tests, small assays (step-size, force-clamp), sanity checks, targeted CPU/GPU
> equivalence at points where a hot kernel / numerical method changes, and debugging.

**Exact trajectory identity is NOT the criterion for stochastic / chaotic ensemble work.** The counter-based
Wang-hash RNG is bit-identical CPU↔GPU by construction; the only divergence channel is float32 op-ordering on
the chaotic trajectory. The relevant standard is **aggregate-statistical (within-SEM) equivalence**
(`docs/CPU_GPU_VALIDATION_POLICY.md`).

## 2. Production runners / builders — traced and confirmed (no rerun)

| study | runner | builder | rev | flags | device evidence |
|---|---|---|---|---|---|
| Long single-head density sweep | `ExplicitCompleteMatHarness -production-cell` (`run_singlehead_gpu.sh`) | `buildGlidingGraph` (explicit-s2-l40) | single_head_sweep_long | `-backend gpu` (device) | `device-resident=true`, no CPU fallback, warm PTX compile ~969 ms, **0 invalid/solver** per cell |
| Long HMM-dimer density sweep | `ExplicitHmmDimerGlidingHarness -production-cell` (`run_hmm_gpu.sh`) → `runProductionCell`/`buildProductionGpu` | forked-dimer device kernel (`gpu-fd`, FLOAT) | `82b6762` | `-backend gpu -branchEA 0.03` | banner `kernel=gpu-fd device-resident=true`, warm PTX ~1284 ms, **0 invalid/solver**, `branchEA=0.03` |
| All-strong-bound rupture sensitivity | same dimer runner (`-rupture-mode 2`) | same device kernel | sm4_allstrong | `-backend gpu` | device-resident, 0 invalid/solver |

**Confirmed from existing metadata (Part C step 2):** device-resident execution; no CPU fallback (structurally
enforced — every refusal throws, no catch-and-continue); exact parameter manifest (same `nucParams`/`kinParams`/
`bindP` arrays; RNG bit-identical); **branchEA = 0.03** in every cell (`"branchEA":0.0300000`); **0 invalid /
0 solver** at every density/seed. The PTX warm-compile time exists **only** on the device path (the CPU object
solver compiles no PTX) — positive proof the solve ran on the GPU.

## 3. CPU/GPU equivalence evidence base for the gliding assay class

The forked-dimer / explicit-S2 gliding class is validated on:
- **Deterministic fixtures V1–V8** PASS (coord < 1e-4 nm, force < 1e-3 pN); **moving-actin** PASS; 100-step
  moving-actin PASS (`docs/matsoa/EXPLICIT_HMM_DIMER_GPU_BACKEND_FINDINGS.md` §3a).
- **Binding decisions** device == CPU oracle (T3/T4, negative decisions on 1797 heads validated).
- **Chemistry + catch-slip bit-identical CPU↔GPU** (T6/T7 — the deterministic per-motor Wang-hash gives
  event-identical transitions; 0 nuc mismatch). This is exactly the kernel the rigor-rupture default now
  exercises on-device.
- **0 invalid / 0 solver** across every completed production cell (single-head, HMM-dimer, rupture
  sensitivity).
- 30+ deterministic CPU↔GPU comparisons across the codebase, none ever caught a logic error
  (`docs/CPU_GPU_EQUIVALENCE_EVIDENCE.md`).

Per the statistical-equivalence policy, this is a **sufficient** basis to validate the chaotic-ensemble gliding
class for production. The **production-density aggregate-equivalence standing run** (`runG4d`) remains the
retained "small CPU/GPU equivalence suite" that a **future hot-kernel change** re-triggers — it is preserved,
not deleted, and is the mechanism for re-validating after any kernel edit.

## 4. Code: per-assay-class validation (replaces the blanket refusal)

`ExplicitHmmDimerGpuParams` (canon v2):
- New `productionValidated(Backend)` — **TRUE** for the forked-dimer gliding mechanics class
  (`Backend.GPU` / `GPU_MECH`); FALSE for the full experimental single-graph device timestep
  (`GPU_FULL_EXPERIMENTAL`) and any other class.
- `requireUsable(Backend)` rewritten: CPU / GPU_VALIDATE always allowed; **validated class ⇒ normal production,
  no override**; unvalidated device class ⇒ needs the master gate or the explicit `-gpu-experimental` override;
  otherwise **hard failure** with a clear message. **No silent fallback** (unchanged — every refusal throws).
- The legacy `DEVICE_VALIDATED` boolean is retained for the unvalidated / full-timestep classes; the
  standing-config self-check (`assertStandingConfig`) still aborts on any config drift (branchEA, topology, dt,
  fork geometry). `MotorGpuParams` (fixed/calibrated/explicit two-body models) keeps its hard-closed gate —
  those CPU-only / un-ported classes correctly still refuse GPU.

**Verified this session (post-change, rebuilt):**
- Dimer production cell runs **device-resident WITHOUT `-gpu-experimental`** — banner
  `production-validated(class)=true`, `[GPU canonical production path — … no CPU fallback]`, 0 invalid/solveFail.
- Single-head production cell runs device-resident, validated-class banner, 0 invalid/solveFail.
- `-backend gpu-full-experimental` (unvalidated) **hard-fails** with the per-class refusal message (no silent
  fallback).

## 5. Answers to the Part C questions

- **Is GPU the normal production path without an experimental override?** **YES** for the validated
  forked-dimer / explicit-S2 gliding classes.
- **Is there silent fallback?** **NO** — structurally impossible; every refusal throws.
- **Hard failure preserved for unvalidated classes / unsupported kernels?** **YES** —
  `GPU_FULL_EXPERIMENTAL`, un-ported two-body GPU models, and any standing-config drift all hard-fail.
- **CPU role?** Supplemental (oracle for small assays + targeted equivalence). The `gpu-validate` /
  `runG4d` equivalence suite is preserved for future kernel changes.
- **Exact trajectory identity required?** **No** — aggregate-statistical equivalence is the criterion for the
  chaotic-ensemble classes.

**No production sweep was rerun to satisfy governance** — the existing device metadata and CPU/GPU equivalence
evidence are sufficient, per the task's explicit instruction.
