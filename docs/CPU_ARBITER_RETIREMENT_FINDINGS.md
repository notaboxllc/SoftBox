# CPU-arbiter retirement — audit findings and decision

**Date 2026-07-22 · code rev `0d7966e` · governance task, no physics change.**
Policy produced: `CPU_GPU_VALIDATION_POLICY.md`. Evidence: `CPU_GPU_EQUIVALENCE_EVIDENCE.md`.

---

## 1. Final decision

**The BLANKET CPU-arbiter rule is RETIRED.** Device-path results are primary once their assay class
has passed a CPU/GPU equivalence benchmark at the current revision.

**The narrow, evidence-supported core of the rule is RETAINED** for chaotic many-body ensembles
(gliding, dense contractile networks). It is retained because it still has a measured hit rate, not
out of caution.

Three things are explicitly **not** retired:
- `DEVICE_VALIDATED` (a separate, code-enforced promotion gate, still `false` on both explicit paths);
- the no-silent-fallback discipline;
- the numerical health checks (`invalid_states`, `solver_failures`, exact integer/state equality).

---

## 2. What the audit found

### 2.1 The rule was over-applied relative to its own text

The rule as written (`CLAUDE.md:127-144`, 2026-07-08) scopes itself to **gliding**: "a GPU *gliding*
result REQUIRES a CPU-arbiter cross-check when…". In practice it was cited far more broadly — in
single-motor validation documents, in sweep tooling, in campaign prompts, and in the project-state
summary, which generalised it to "Reported GPU curves require CPU-arbiter spot checks". The audit
found ~58 files carrying the language.

### 2.2 The evidence does not support the general form

- **30+ deterministic-mechanics comparisons**: all bit-identical or float-last-bit. No basin flip,
  ever, in this class.
- **Stochastic single-motor**: bit-identical / event-identical, because the RNG is a counter-based
  Wang hash keyed on `(id, step, seed)` — identical on both runners by construction. Confirmed again
  at the current revision: motor binding **474367/474367 boundSteps, 4739/4739 releases**.
- **`NO EVIDENCE FOUND` that a CPU/GPU comparison has ever caught a logic error** in this project.
  The stop condition exists in the gate ladder but no document records it firing.
- **`NO EVIDENCE FOUND` for stale code or timestep as a cause** of any CPU/GPU discrepancy.
- **RNG differences are excluded by construction**, and this is documented in two independent places.

Every recorded discrepancy falls into exactly three buckets: benign float op-ordering / chaotic
decorrelation; the graph-split / transcendental basin tip (gliding-class only); and **two recorded
silent CPU fallbacks** — which are a real hazard but an entirely different one, addressed by
`bailout=false` and the `DEVICE_VALIDATED` refusal, not by duplicating runs.

### 2.3 The rule's text had gone stale

`CLAUDE.md:135-136` listed as arbiter-gated: `-bondnoise`, `-allnoise`, `-thermcorr`, `-syswide`,
`-xbimplicit*`, `-xbdash`, `-freshread` — flags **deleted in the canonical collapse**
(`JOURNAL.md:2110-2113`). The rule was never updated. A governance rule naming non-existent flags
cannot be applied as written.

### 2.4 The rule was never enforced in code

`grep -rn -i "trust rule|arbiter" softbox/*.java` returns only bistability *instrumentation*
(`-swingkbits`, `-swingkprobe`) and one parity comment. There is no gate, assertion, or refusal
anywhere that blocks a GPU-only number from being reported. The rule lived entirely in prose plus
hand-written lines in ~6 shell scripts. (By contrast `DEVICE_VALIDATED` **is** enforced —
`requireDeviceValidated` always throws.)

### 2.5 …but the hazard it names is real, and still is

Of ~9 gliding arbiter runs with recorded numbers: **7 confirmed the GPU** (0.08 %–8 %), **1 refuted a
GPU result outright** — d8000 dt=1e-5, CPU 9.93 vs GPU 13.84, which changed the conclusion and caused
that density to be dropped — and **1 exposed a standing ~20 % offset** at d2000. Two further
basin-class divergences exist outside the springs harness, including one where CPU and GPU disagree
on *whether a pathology exists at all* (dimer ρ1500: CPU 91.1 nm vs GPU 8.0 nm maxGap).

A ~2-in-9 hit rate is far too high to discard. **Retiring the blanket rule while keeping the
chaotic-ensemble clause is what the evidence actually licenses.**

---

## 3. Fresh evidence generated for this audit (rev `0d7966e`, 2026-07-22)

The GPU was idle, so rather than rely only on historical claims the equivalence benchmark was re-run
at the current revision. Raw logs: `RUN_LOGS/validation/cpu_gpu_equivalence_2026-07-22/`.

**Seven gate harnesses — all PASS:** grid (CSR + candidate set bit-identical), xbridge (gather==brute
exact, CPU↔GPU bit-identity), minifil (0.000e+00), dimer (maxRel 6.6e-08, Brownian Δ 0.000°),
motorbody (aggregate < 1 nm / < 1°), motor (**bit-identical** stochastic assay), stroke (force-gated:
aggregate 0.4 % / 0.0 %).

**Ensemble gliding at the CANONICAL operating point** — the exact configuration where the bistability
was found, re-run with the springs default:

| observable | CPU | GPU | Δ |
|---|---|---|---|
| velFitX | 3.264 | 3.105 | −4.9 % |
| avgBound (steady) | 3.307 | 3.050 | −7.8 % |

**Both runners are in the HIGH basin** (the LOW signature is velFitX ≈ 2.11 / per-bound ≈ 0.74) — no
basin flip on the default path at the current revision. And the CPU value **reproduces the historical
CPU arbiter number to every digit** (`PURE_SPRINGS.md`: velFitX 3.264 / avgB 3.307), across two weeks
and many commits.

**A scope limit is recorded honestly:** the sparse `-v1box` comparison came out identical to printed
precision on all 3 seeds, but that regime (avgBound ≈ 1.1) is dominated by RNG-determined discrete
events over dwells too short for float drift to flip a gate — so it does **not** license a dense-regime
claim. A first `-full` attempt was discarded from all conclusions because `-density` silently defaulted
to 500 (the harness emitted a `[SWEPT-PARAM WARNING]`); the canonical run above was re-done with both
parameters explicit and no warning in either log.

---

## 4. Classification of the language found (task item 2)

| assay class | files/uses | verdict |
|---|---|---|
| Deterministic unit/mechanics | inc-1…inc-7 validation docs, gate harnesses | **Rule never applied here and never needed** — bit-identity is the standard |
| Stochastic single-motor | 4b-iii stroke, HMM chemistry/rupture, SM4 docs | **Retire** — event-identical or aggregate-within-SEM suffices |
| Ensemble gliding | `BISTABILITY_ORIGIN`, `PURE_SPRINGS`, `SPRINGS_PROMOTION`, density/azimuthal/coltol/fine-dt sweeps, `GlidingHarness` comments | **RETAIN, narrowed** to structural A/Bs, outliers, and one periodic spot check |
| Production density sweep | single-head + HMM-dimer sweeps, `CURRENT_STATE` | **Retire the per-sweep requirement**; inherit the underlying class |
| New kernel / structural change | `gpu_port/*`, `DEVICE_VALIDATED` gates | **RETAIN and strengthen** — this is where CPU confirmation belongs |

Historical findings documents (`BISTABILITY_ORIGIN.md`, `PURE_SPRINGS.md`, `SPRINGS_PROMOTION.md`,
per-campaign reports, `scripts/archive/*`) were **deliberately left unedited**. They are the record of
what was believed and measured at the time; rewriting them would destroy the audit trail. The task
required preserving the historical explanation, and the new policy carries it forward explicitly.

---

## 5. Changes made

| file | change |
|---|---|
| `CLAUDE.md` | Replaced the "GPU-number trust rule" block with the evidence-based policy summary + a **preserved historical note** explaining why the old rule existed; added the explicit statement that `DEVICE_VALIDATED` is separate and **not** retired; flagged the deleted-flag staleness |
| `docs/CURRENT_STATE.md` | Runner caveat rewritten to the new policy; the "CPU-arbiter points" work item scoped to the retained cases |
| `scripts/run_canonical_density_sweep.sh` | Comment: "standing GPU-number trust rule" → policy §3/§5 periodic spot check (1 of 15 cells) |
| `scripts/run_canonical_density_sweep_coltol8.sh` | Same, dense regime (1 of 24 cells) |
| `scripts/run_azimuthal_falloff_sweep.sh` | Reclassified as policy §3(9) — a genuine structural bind-path A/B, **still required** |
| `scripts/run_coltol_regime_arbiter.sh` | Documented that coltol is a **data-only scalar** ⇒ not a mandated confirmation; retained as an **opt-in outlier check** only |
| `scripts/finedt_free_glide_sweep.sh` | Comment records that this is the arbiter set that **refuted** a GPU result — retained and justified |
| new | `CPU_GPU_VALIDATION_POLICY.md`, `CPU_GPU_EQUIVALENCE_EVIDENCE.md`, this document |
| new | `RUN_LOGS/validation/cpu_gpu_equivalence_2026-07-22/` — raw benchmark logs + provenance |

**No script's physics arguments were changed, and no CPU run was deleted.** The two scripts whose CPU
arm was doing real work (the fine-dt arbiter set, the azimuthal structural A/B) were explicitly
retained and their justification strengthened.

---

## 6. Answers to the required final statements

**Is the blanket CPU-arbiter rule retired?**
**Yes.** GPU/device results are primary for validated assay classes at a validated revision. The
narrow chaotic-ensemble clause survives as a triggered check, not a standing requirement.

**Which device paths are currently validated?**
At rev `0d7966e`: broad-phase grid/CSR, cross-bridge gather, minifilament gather, dimer coupling,
articulated motor body, motor binding kinetics, nucleotide cycle + stroke, and sparse-regime gliding
— all re-benchmarked today. Dense/canonical gliding is validated as **same-basin, ~5 % agreement at
n=1 seed** and remains in the spot-check regime. The explicit-S2 single-head and HMM-dimer device
paths pass their fixture and equivalence gates but remain `DEVICE_VALIDATED = false`, blocked on a
production-equivalence matrix that has not been run.

**Which assay classes still need CPU spot checks?**
Only chaotic many-body ensembles (Class C), and only for: structural-difference A/Bs; rare-event and
outlier questions; and one periodic mid-range check per campaign reporting an absolute number. Plus
Class E (new kernel / structural change / RNG / dt / device-path promotion) regardless of class.

**What future changes trigger revalidation?**
TornadoVM/driver/GPU hardware change; Java or FP-affecting compiler flags; any device kernel body or
per-step task-sequence/graph-split change; RNG implementation or keying; production dt or integrator;
`DEVICE_VALIDATED` promotion. Each revalidation must be recorded under
`RUN_LOGS/validation/cpu_gpu_equivalence_<date>/` with revision and toolchain — **a validation claim
without a recorded revision is void.**

---

## 7. Open items this audit surfaces but does not close

1. The springs re-baseline sign-off flagged for jba — **no evidence it was ever recorded**.
2. The deferred matched ρ3000-s102 CPU-object arbiter for the HMM dimer (multi-hour CPU run).
3. The never-run single-head "CPU spot check of one mid-density cell" — under the new policy this is
   exactly the one retained obligation for that campaign, and it is still outstanding.
4. The full `DEVICE_VALIDATED` production-equivalence matrix for both explicit paths.
5. The dense-regime CPU/GPU agreement is established at **n = 1 seed** (~5 %). A 3–4 seed repeat would
   convert that from "same basin" to a quantitative equivalence claim.
