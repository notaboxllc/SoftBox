# CPU/GPU validation policy

**Effective 2026-07-22. Supersedes the blanket "GPU-number trust rule / CPU is the basin arbiter"
(CLAUDE.md, 2026-07-08).** Evidence: `CPU_GPU_EQUIVALENCE_EVIDENCE.md`. Audit and rationale:
`CPU_ARBITER_RETIREMENT_FINDINGS.md`.

This policy changes **no physics** and **weakens no numerical health check**.

---

## 1. Principle

**A GPU/device-path result is primary once that assay class has passed a CPU/GPU equivalence
benchmark at the current code revision.** CPU confirmation is a *triggered* instrument, not a
standing tax on every run.

The old rule treated the CPU as the source of truth for all GPU numbers. The evidence does not
support that generality: across 30+ deterministic comparisons and several stochastic ones, CPU and
GPU agree bit-for-bit or to float last-bit, and **no CPU/GPU comparison has ever caught a logic
error** in this project. What the evidence *does* support is a narrow, real hazard confined to
**chaotic many-body ensembles**, where a last-bit perturbation can tip a discrete, force-gated
branch and change an aggregate.

**The divergence channel is exactly one thing:** float32 op-ordering. The RNG is a counter-based
Wang hash, bit-identical on both runners by construction, so stochasticity alone never causes
disagreement. Op-ordering matters only when (a) it accumulates on a chaotic trajectory, or (b) it
flips a decision taken on a float threshold.

---

## 2. Assay classes and equivalence criteria

### Class A — Deterministic mechanics
*Force laws, gathers, CSR builds, allocators, geometry, containment, quasistatic force-extension,
short-horizon non-chaotic integration.*

- **Criterion:** bit-identical, or agreement at float32 last-bit with a stated tolerance
  (pose ≤ 1e-5 µm; force ≤ 1e-15 N; integer/CSR/lifecycle state **exactly** equal).
- **Integer-valued state (CSR, set membership, slot allocation, bind decisions at t=0) must match
  EXACTLY.** Any integer mismatch is a bug, never a tolerance question.
- **GPU is primary. No CPU confirmation required** once the class benchmark passes at the current rev.

### Class B — Stochastic single-motor / single-entity
*Binding kinetics, nucleotide cycles, catch-slip release, rupture events, lifetime assays.*

- **Criterion, default:** **event-identical** — same transitions at the same steps, 0 state
  mismatches. This is achievable and has been achieved (§3 of the evidence doc), because the RNG is
  a pure hash.
- **Criterion, force-gated variants** (where the draw is compared against a force-dependent rate):
  agreement of **survival curves, fitted parameters, and their confidence intervals**; means within
  combined SEM. Trajectory-level identity is **not** required and must not be demanded.
- **GPU is primary.** CPU confirmation required only under §3.

### Class C — Ensemble gliding and other chaotic many-body assays
*Gliding mats, dense contractile networks, ring condensation — anything where many force-gated
motors feed back through a shared filament.*

- **Criterion:** **aggregate-statistical agreement over matched conditions.** Compare velocity,
  bound heads, attachment lifetime, ATP turnover, force distributions, and failure counts.
  Agreement means ensemble means within combined SEM (or a stated ≤ few-% band), with matched
  density, coltol, dt, step count and seed set.
- **Trajectory-level or seedwise identity is explicitly NOT required** and is not evidence of
  anything: float32 op-ordering decorrelates the microstate by design.
- **This is the only class in which basin behaviour has ever been observed.** CPU spot checks are
  retained here — see §3.

### Class D — Production sweeps and parameter studies
*Repeated runs of an already-validated path over a density/parameter ladder.*

- **Criterion:** inherits the criterion of its underlying assay class.
- **CPU confirmation is NOT required per sweep or per parameter point.** One periodic spot check per
  campaign at a mid-range operating point is sufficient, and only for Class C sweeps.

### Class E — New kernel or structural physics change
*A new hot kernel, a changed integrator, a changed RNG, an added/removed TaskGraph task, a new
device path being promoted.*

- **Criterion:** the full ladder — Class A bit-identity on deterministic fixtures, Class B
  event-identity, and a Class C ensemble check if the change touches a chaotic path.
- **CPU confirmation IS required.** See §3.

---

## 3. When CPU confirmation is required

CPU confirmation is **required** for:

1. **A new hot kernel** — anything added to a per-step device graph.
2. **A structural physics change** — a new/changed force law, integrator, or constraint scheme.
3. **A changed RNG implementation** — the counter-based-hash property in §1 is load-bearing; if it
   changes, every Class B equivalence claim must be re-earned.
4. **A changed timestep or integrator** on a path whose equivalence was established at a different dt.
5. **An unresolved CPU/GPU discrepancy** — any disagreement not explained by float op-ordering.
6. **Invalid states or solver failures** — a non-zero count voids the GPU result until diagnosed.
7. **Extreme outlier cells** — rare high-strain/high-gap excursions, seed-intermittent pathologies,
   and any "does seed X blow up?" question. The evidence shows these are genuinely basin-sensitive
   (dimer ρ1500: CPU 91.1 nm vs GPU 8.0 nm on the very existence of the pathology).
8. **Promotion of a previously unvalidated device path** — i.e. flipping any `DEVICE_VALIDATED`.
9. **A Class C A/B whose arms differ in hot-kernel structure** — adding/removing a task or toggling
   an in-kernel transcendental. This is the one surviving clause of the old rule, and it is retained
   because the `-allnoise` case proved an arithmetic no-op can manufacture a 66 % "effect".
10. **A Class C absolute number used as a headline validation result**, as a periodic spot check —
    one mid-range cell per campaign, at the smallest scale/window that resolves the question.

CPU confirmation is **NOT required** for:

- Every production sweep, or every cell of one.
- Every parameter study on a validated path.
- Class A or Class B results at a validated revision.
- Data-only flags that change no kernel structure (density, coltol, seed, step count, output
  cadence). These share identical hot-kernel structure and carry no basin hazard.

**A CPU confirmation need not be full scale** — use the smallest scale and window that resolves the
question. This clause is inherited unchanged from the old rule, which most scripts already honoured.

---

## 4. Currently validated device paths

Validated at rev `0d7966e` (2026-07-22) by the benchmark in §5 of the evidence document:

| path | class | status |
|---|---|---|
| Broad-phase grid / CSR | A | **VALIDATED** — bit-identical |
| Cross-bridge gather | A | **VALIDATED** — bit-identical |
| Minifilament backbone gather | A | **VALIDATED** — 0.000e+00 |
| Dimer coupling | A | **VALIDATED** — maxRel 6.6e-08, Brownian Δ 0.000° |
| Articulated motor body | A | **VALIDATED** — aggregate < 1 nm / < 1° |
| Motor binding kinetics | B | **VALIDATED** — bit-identical (474367/474367, 4739/4739) |
| Nucleotide cycle + stroke | B (force-gated) | **VALIDATED** — aggregate 0.4 % / 0.0 % |
| Gliding, sparse (`-v1box`) | C | **VALIDATED** — identical to printed precision, 3 seeds |
| Gliding, dense/canonical | C | **SPOT-CHECK REGIME** — see §5 |
| Explicit-S2 single head (device) | A/B | Device fixtures pass (1.1e-11 µm; 300-step bit-close), but `MotorGpuParams.DEVICE_VALIDATED = false` |
| HMM dimer (device) | A/B | G1a–G5 pass incl. chemistry bit-identity, but `ExplicitHmmDimerGpuParams.DEVICE_VALIDATED = false` |

**`DEVICE_VALIDATED` is unaffected by this policy.** It is a separate, code-enforced promotion gate
that remains `false` for both explicit paths, blocked on a production-equivalence matrix that has not
been run. Nothing here opens it, and no `-gpu` refusal is relaxed.

---

## 5. Ensemble gliding: what is retained and why

The blanket rule is retired, but **CPU spot checks are retained for Class C** on evidence, not
caution. Of ~9 gliding arbiter runs with recorded numbers, 7 confirmed the GPU (0.08 %–8 %), **one
refuted a GPU result outright** (d8000 dt=1e-5: CPU 9.93 vs GPU 13.84, which changed the conclusion
and caused that density to be dropped), and one quantified a standing ~20 % offset at d2000. A ~2-in-9
hit rate is too high to discard.

Retained obligations for Class C:
- A/B arms differing in hot-kernel structure → CPU cross-check or a same-graph factor-1.0 control.
- Rare-event / outlier questions → CPU arbitration.
- One periodic mid-range spot check per campaign whose headline is an absolute number.

Not retained: duplicating whole sweeps, arbitrating data-only parameter changes, or treating the CPU
as the source of truth for classes A, B and D.

---

## 6. Revalidation triggers

The Class benchmark must be re-run when any of the following changes:

- the TornadoVM version, PTX backend, driver, or GPU hardware;
- Java version or compiler flags affecting FP (`-Dtornado.enable.fma`, fast-math, precision flags);
- any device kernel body, or the per-step task sequence / graph split;
- the RNG implementation or its keying;
- the production timestep or integrator;
- promotion of a device path (`DEVICE_VALIDATED`).

Record each benchmark under `RUN_LOGS/validation/cpu_gpu_equivalence_<date>/` with code revision,
host, toolchain versions, and raw logs. A validation claim without a recorded revision is void.

---

## 7. Non-negotiables (unchanged)

- **No silent CPU fallback, ever.** Two have been recorded historically (a TaskGraph capacity
  overflow, and a capability flag mistaken for a validation gate). Keep
  `-Dtornado.recover.bailout=false`, and keep disclosing the runner before any long run.
- **Every run reports `invalid_states` and `solver_failures`.** Non-zero voids the result.
- **Integer/state equality is exact.** Set membership, CSR, allocation and lifecycle mismatches are
  bugs, not tolerances.
- **Report the runner** in every findings document and every JSON record.
- **Do not pool CPU and GPU values into one fitted curve** without stating the runner per point.
