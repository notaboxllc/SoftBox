# CPU/GPU equivalence evidence

**Compiled 2026-07-22.** Every row is quoted from an existing report or from the fresh benchmark of
§5. Nothing here is inferred. Where evidence was sought and not found, that is stated explicitly —
those "NO EVIDENCE FOUND" rows are as load-bearing as the positive ones.

This document is the factual basis for `CPU_GPU_VALIDATION_POLICY.md`. It makes no policy claims.

---

## 1. Structural facts that bound what CPU/GPU disagreement *can* be

**1.1 The RNG is bit-identical on both runners — by construction.**
Device kernels draw from an integer Wang hash keyed on `(entity_id, step, seed)`
(`NucleotideCycleSystem.java:25`, `BrownianForceSystem.java:35`, `Ring3x3Harness.java:192`).
`java.util.Random` appears **only** in host-side CPU analysis/test harnesses (bootstrap resampling,
FD Jacobian probes) — never in a device kernel.

> "RNG is counter-based ⇒ the random STREAM is bit-identical CPU↔GPU by key; only float op-ordering
> in the mechanics differs." — `docs/gpu_port/04_VALIDATION_GATES_AND_TOLERANCES.md:6-7`
> "The integer Wang-hash RNG is bit-for-bit identical on both paths, so the only source of
> difference is float op ordering" — `JOURNAL_ARCHIVE.md:354-355`

**Consequence:** *stochastic* does not imply *irreproducible* in this codebase. A stochastic assay
whose random draws do not depend on float-divergent state is bit-identical across runners — and §3
shows several are.

**1.2 Therefore the only divergence channel is float32 op-ordering**, entering in two ways:
(a) continuous drift of trajectories (bounded, Lyapunov-limited);
(b) **discrete decisions gated on a float threshold** (bind gates, catch-slip release), where a
last-bit difference flips a branch. (b) is the whole hazard.

---

## 2. Deterministic mechanics — 30+ comparisons, no disagreement beyond float last-bit

| # | Assay | Result as written | Citation |
|---|---|---|---|
| 1 | FDT diffusion | `D_trans_par 1.11676e-1` vs `1.11676e-1`, Δ 0 to 6 sig figs | `JOURNAL_ARCHIVE.md:343-345` |
| 2 | Static deflection | ratio 0.99831 (GPU) vs 0.99832 (CPU) — 5th decimal | `JOURNAL_ARCHIVE.md:346` |
| 3 | Free chain connectivity | joint-gap max 0.04262 = 0.04262, Δ 0 | `JOURNAL_ARCHIVE.md:348-350` |
| 4 | Broad-phase grid / CSR | "CSR bit-identical CPU↔GPU; parallel build == serial bit-identical" | `docs/GRID_PARALLEL_FINDINGS.md:86-87` |
| 5 | Cross-bridge CSR gather | "bit-identical … ΔF ~7e-19 N — float32 last-bit" | `CLAUDE.md:497-501` |
| 6 | Crosslinker force/gather (5a) | "force+gather bit-identical, pose float32 last-bit" | `CLAUDE.md:607` |
| 7 | Crosslinker unbind (5b) | "bit-identical (854=854 dead, 0 mismatched)" | `CLAUDE.md:632` |
| 8 | Crosslinker allocator (5c-i) | "bit-identical (0 mismatches, 400 churn steps)" | `CLAUDE.md:652` |
| 9 | Crosslinker formation (5c-ii) | "bit-identical (full pipeline, 400 churn steps, 0 mismatches)" | `CLAUDE.md:676` |
| 10 | Dimer coupling (6a) | "det 3.5e-6 µm / Brownian Δ0.000°" | `CLAUDE.md:745` |
| 11 | Minifilament tether (6b) | "det 4.5e-6 µm" | `CLAUDE.md:773` |
| 12 | Bipolar minifil under load | "gather==brute bit-identical (Δ0) … CPU≡GPU 7.4e-7/1.1e-7 µm" | `CLAUDE.md:816-817` |
| 13 | Containment box | "force ΔF=0.0 exact, torque float32 FMA last-bit" | `CLAUDE.md:890` |
| 14 | Filament birth / allocator | "CPU≡GPU Δ=0; born@0 ≡ preplaced max|Δpose|=0" | `CLAUDE.md:951-952` |
| 15 | Formin nucleation | "0 mismatches = bit-identical lifecycle, pose Δ 4.66e-10 µm" | `CLAUDE.md:988-989` |
| 16 | Filament growth / split@64 | "bit-identical lifecycle Δcoord 1.4e-9 µm"; 12000 steps "Δcoord 0.00" | `CLAUDE.md:1043-1046` |
| 17 | Dead-slot reuse | "2250 reuses all correct, conservation EXACT, CPU≡GPU bit-identical" | `CLAUDE.md:1177` |
| 18 | Canonical two-point motor | "bit-identical (meanTailX/avgBound rel 0.00%)" | `JOURNAL_ARCHIVE.md:2491` |
| 19 | Mat-SoA `matCull`/`matGeomGate`/`matBind` | "PASS 0 mismatches"; "segMism=0, acceptMism=0"; "boundSegMism=0" | `docs/matsoa/SLICE_STATUS.md:81-83` |
| 20 | Explicit beam GPU kernel, 10 fixtures | "max 2.5e-9 µm (7/10 bit-identical 0.0), 0 failures" | `docs/explicit_jac/GPU_KERNEL_FINDINGS.md:51-52` |
| 21 | Explicit complete-mat, 300 steps | "Δnode=4.93e-08 µm; first divergence: **no divergence (bit-close all 300 steps)**" | `RUN_LOGS/explicit_completemat/COMPLETEMAT_TRAJ.md:8` |
| 22 | CSR host- vs device-built | "pure-integer ⇒ host-built == device-built **bit-for-bit**" | `CLAUDE.md:360-361` |

**No basin flip, and no semantic disagreement, has ever been recorded in this class.**

---

## 3. Stochastic single-motor — bit-identical where the draw is not force-gated

| # | Assay | Result as written | Citation |
|---|---|---|---|
| 23 | Motor binding kinetics (4a) | "CPU≡GPU bit-identical (bound-state + stats)" | `CLAUDE.md:463-465` |
| 24 | HMM-dimer chemistry + catch-slip | "T6/T7 PASS — **bit-identical CPU↔GPU** (0 nuc mismatch; the deterministic per-motor wang-hash RNG gives event-identical transitions)" | `EXPLICIT_HMM_DIMER_GPU_BACKEND_FINDINGS.md:278-279` |
| 25 | HMM-dimer rupture events | "**event-identical PASS** (CPU 4 == GPU 4 releases at shift 25)" | same, `:322` |
| 26 | Nucleotide cycle + stroke (4b-iii) | "aggregate-within-SEM (**force-gated** cycle decorrelates)" | `CLAUDE.md:513` |

Rows 23–25 are bit-identical; row 26 is not — and the report itself names the reason: the cycle is
**force-gated**, so float-divergent force crosses a threshold. This is the (b) mechanism of §1.2,
observed in isolation.

---

## 4. Ensemble gliding — the only class where basin behaviour has ever been observed

### 4.1 The bistability finding itself

Operating point (`docs/BISTABILITY_ORIGIN.md:200`):
`-full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000 -dt 1e-5 -seed 0`.

Observed (`PURE_SPRINGS.md:154-162`, GPU 60k, 3 seeds, velFitX / avgB / per-bound):

| seed | raw | pure-springs | `-ratefix -structrate` |
|---|---|---|---|
| 0 | 2.895 / 3.332 (0.869) | 2.895 / 3.332 (0.869) | **2.112 / 2.870 (0.736)** |
| mean | 2.825 / 3.317 (0.852) | 2.825 / 3.317 (0.852) | **2.325 / 3.000 (0.774)** |

Four facts that constrain interpretation:

1. **The coefficient is bit-identical.** GPU raw k `0x3ecccccd`, recomputed `0x3ecccccd`, float-ULP
   Δ = 0, double residual 0.0 (`BISTABILITY_ORIGIN.md:48-54`).
2. **The GPU is deterministic run-to-run** — 3× raw and 3× ratefix each bit-identical, "every digit"
   (`:87-95`). **It is a fixed compiler-scheduling nudge, not a race.**
3. **The CPU is mono-stable HIGH** at this point; a swing-k sweep 0.40→0.10 gives "a smooth monotone
   decline, NOT a bistable jump", and the LOW signature is "not reproduced at any swing k" (`:125-146`).
4. **Mechanism, quoted** (`:13-24`): "the `exp/log` instructions perturb the PTX compiler's register
   allocation / scheduling / FMA-contraction of the *surrounding* per-thread torque arithmetic at the
   ULP level, and on this **bistable** operating point that last-bit perturbation deterministically
   tips the basin."

Precursor, same class — the `-allnoise` graph-split artifact: forcing the noise scale to exactly 1.0
(an arithmetic no-op) reproduced the *entire* claimed effect on GPU (per-bound 1.286 vs real 1.259 vs
OFF 0.774) while being byte-identical to OFF on CPU (`JOURNAL_ARCHIVE.md:3989-4041`).

**Explicit non-exclusion, carried forward** (`BISTABILITY_ORIGIN.md:183-185`): "Not fully excluded: a
genuine LOW attractor that the CPU simply never occupies from a fresh start … But nothing observed
supports it."

### 4.2 What the springs promotion changed

- Springs replaces the fraction-per-step recompute with a multiply; at production dt it is an exact
  ×1.0 no-op — raw ≡ springs ≡ ratefix "Every digit identical" (`PURE_SPRINGS.md:69-80`).
- **GATE 1**: "springs introduces ZERO flag-dependent hot-kernel transcendental … it *removes* the
  one swing exp/log that caused the flip" (`SPRINGS_PROMOTION.md:56-59`). The hazard was isolated to
  a single line, `CrossBridgeSystem.directedSwing:237` (`:32-38`).
- **GATE 2**: GPU-springs vs CPU-springs, 3 seeds/runner, 20k, d1000 — velFitX **3.215 vs 3.180
  (~1%)**, avgB 2.954 vs 2.911 (~1.5%), per-bound 1.089 vs 1.093 (~0.4%). "The GPU-springs path ==
  the deterministic CPU reference. GATE 2 PASSES." (`SPRINGS_PROMOTION.md:76-87`)
- Springs is **default-on**; byte-identical at production dt (`:120-126`).

**Residual hazard the promotion explicitly did NOT cover** (`SPRINGS_PROMOTION.md:61-65`): the
noise-correction flags, the implicit/integrator variants, and the motor/cycle/bind swaps.

### 4.3 Arbiter outcomes in gliding — the hit rate

| # | Point | CPU vs GPU | Verdict | Citation |
|---|---|---|---|---|
| 27 | d1000 coltol10 | 2.867 vs 2.895 (**0.97%**) | same basin | `JOURNAL.md:2046-2047` |
| 28 | d4000 coltol8 | 6.469 vs 6.439 (**0.5%**) | same basin | `JOURNAL.md:2028-2030` |
| 29 | d2000 coltol2 | avgBound 2.403 vs 2.405 (**0.08%**); velFitX +8% | same basin | `docs/COLTOL_REGIME_SWEEP.md:185-192` |
| 30 | d4000 azimuthal n=16 | avgB 7.44 vs 7.89; velFitX 3.92 vs 4.44 | "no basin flip" | `AZIMUTHAL_FALLOFF_INCREMENT3.md:91-93` |
| 31 | d2000 fine-dt | CPU 4.367/4.037 vs GPU ≈4.39 | agrees | `FINE_DT_FREE_GLIDE_RESULTS.md:182-183` |
| 32 | Calibrated mat ensemble | "within SEM for all 14 observables … every z ≤ 1.11" | agrees | `docs/gpu_port/INCREMENT4_STATUS.md:21-24` |
| **33** | **d8000 dt=1e-5 s0** | **CPU 9.933 vs GPU 13.84** | **GPU REFUTED** | `FINE_DT_FREE_GLIDE_RESULTS.md:184-191` |
| **34** | **d2000 force-balance** | **CPU ~20% below GPU** | standing basin offset | `JOURNAL.md:1665-1674` |

**Of ~9 gliding arbiter runs with numbers: 7 confirmed the GPU (0.08%–8%), 1 refuted a GPU result
outright (#33), 1 quantified a standing ~20% offset (#34).** The arbiter's hit rate in *ensemble
gliding* is therefore roughly 2 in 9 — **not negligible, and not a reason to retire it in this class.**

### 4.4 Basin-class divergence outside the springs gliding harness

- **HMM-dimer rupture pathology (C-6):** CPU probe ρ1500 s101 → 91.1 nm maxGap; GPU device engine
  same cell → 8.0 nm (pathology FALSE). Cause as written: "a genuine CPU↔GPU basin/path divergence on
  the very question of whether the pathology exists". GPU reproducible across runs and a reboot.
  (`EXPLICIT_HMM_DIMER_GPU_BACKEND_FINDINGS.md:339-353`)
- **Dense-vs-active graph moves *which seed* blows up (C-7):** ">50 nm event at ρ3000 s101 (69 nm)"
  under one graph vs "ρ3000 s102 →14.3 nm (clean here: 4.3 nm)" under another. Scope stated: "The
  aggregate density-response (velocity, binding, directionality) is **basin-robust**; only the rare
  high-density gap excursion is basin/seed-intermittent."
  (`EXPLICIT_HMM_DIMER_GPU_DENSITY_SWEEP_FINDINGS.md:100-107`)
- **HMM-dimer ρ500 bound heads:** GPU 6.52 vs CPU 4.46 — "a documented CPU↔GPU basin difference, not
  a new effect" (`:46-49`).

---

## 5. Fresh benchmark at the current revision (2026-07-22, rev `0d7966e`)

Run for this audit. Raw logs + provenance: `RUN_LOGS/validation/cpu_gpu_equivalence_2026-07-22/`.
Each harness runs a GPU TaskGraph and a CPU cross-check internally.

| assay | class | current-revision result | verdict |
|---|---|---|---|
| `run_grid` | deterministic | CSR + candidate set **bit-identical** at all sampled steps; exact set equality | PASS |
| `run_xbridge` | deterministic | gather == brute exact; CPU↔GPU bit-identity | PASS |
| `run_minifil` | deterministic | gather==brute **0.000e+00**; CPU≡GPU det max|Δ| 4.53e-06 µm | PASS |
| `run_dimer` | deterministic + thermal | maxRel 6.613e-08; Brownian aggregate leverAng GPU 151.611° = CPU 151.611°, **Δ 0.000°** | PASS |
| `run_motorbody` | deterministic + thermal | aggregate gaps < 1 nm / < 1°; microstate diverges at float-noise | PASS |
| `run_motor` | **stochastic single-motor** | **bit-identical**: boundSteps **474367/474367**, releases **4739/4739** | PASS |
| `run_stroke` | stochastic, force-gated | aggregate: mean filForce_x rel **0.4%**; avgBound **12.00/12.00 (0.0%)** | PASS |

**Ensemble gliding, sparse (`-v1box -grid`, 10 000 steps, 3 seeds, springs default):**

| seed | velFitX CPU / GPU | avgB CPU / GPU | inst CPU / GPU |
|---|---|---|---|
| 1 | 1.0360 / 1.0360 | 1.1580 / 1.1580 | 6.0150 / 6.0150 |
| 2 | 0.8970 / 0.8970 | 1.1490 / 1.1490 | 6.2770 / 6.2770 |
| 3 | 0.4000 / 0.4000 | 1.0400 / 1.0400 | 6.2390 / 6.2390 |

Identical to printed precision on every seed and every observable. The GPU arm was verified to be a
genuine device run (`measureGrid` builds a `TornadoExecutionPlan` and calls `execute()` per step,
`GlidingHarness.java:4123-4131`), not a silent fallback.

**Ensemble gliding, CANONICAL operating point** — the exact configuration at which the bistability was
found (`-full -grid -coltol 10 -density 1000 -seed 0`, 20 000 steps), re-run at rev `0d7966e` with the
springs default. Both runners explicitly parameterised (no `SWEPT-PARAM WARNING` in either log), runner
banners confirm CPU vs GPU:

| observable | CPU | GPU | Δ |
|---|---|---|---|
| velFitX | **3.264** | **3.105** | −4.9 % |
| avgBound | 3.299 | 3.174 | −3.8 % |
| avgBound (steady) | 3.307 | 3.050 | −7.8 % |
| instantaneous speed | 6.878 | 6.988 | +1.6 % |

Two things follow:

1. **Same basin.** The documented LOW-basin signature is velFitX ≈ 2.11 with per-bound ≈ 0.74. Both
   runners are at velFitX ≈ 3.1–3.3 / avgB ≈ 3.2–3.3 — the HIGH basin. **No basin flip at the current
   revision on the default (springs) path.** Agreement is ~5 % on velocity at n = 1 seed, consistent
   with the documented chaotic seed scatter and with the 3-seed springs GATE 2 (~1 %).
2. **The CPU path is exactly reproducible across two weeks and many commits.** The CPU value
   **velFitX 3.264 / avgB 3.307** reproduces the historical CPU arbiter value quoted in
   `PURE_SPRINGS.md` ("velFitX 3.264 / avgB 3.307") **to every digit**.

**Scope limit, stated honestly:** the `-v1box` rows above are the **sparse** regime (avgBound ≈ 1.1). Discrete
binding events are RNG-determined and bit-identical, and a ~0.6 ms dwell is too short for float drift
to flip the next gate — so agreement here does **not** license a claim about the dense regime. A
first `-full` attempt was run with `-density` **defaulted to 500** (the harness prints a
`[SWEPT-PARAM WARNING]`) and is therefore **not** a canonical dense point; it is excluded from any
conclusion. It showed seed 2 identical (0.991/0.991) and seed 1 differing (0.880 vs 1.034).

---

## 6. Causes of every recorded discrepancy — classified

| cause | evidence |
|---|---|
| **float32 op-ordering / chaotic decorrelation** (benign) | C-9 Stage-7 pose 1.68e-7 "ENTIRELY GPU FMA op-order … force Δ~1e-20 ⇒ no semantic error"; C-10 deflection 5th decimal; C-11 one bind-gate flip in 600; C-12 bounded Lyapunov plateaus (1.2e-2→8.7e-2 µm/200 steps) |
| **graph-split / transcendental basin tip** (the real hazard) | C-1 ratefix −18% velFitX; C-3 `-allnoise` no-op reproducing a 66% "effect"; C-5 d1000 s0 2.635→0.884; C-2 d8000 9.93 vs 13.84; C-6/C-7 dimer pathology + seed migration |
| **silent CPU fallback** | C-13, **two recorded cases**: FullSystemDemo's ~106-task graph exceeded TornadoVM capacity and "silently fell back to the CPU runner" (`docs/TASKGRAPH_SPLIT_FINDINGS.md:4-9`); and `calibrated-s2-l40 -gpu` "previously would have run on CPU … now refused" (`docs/gpu_port/MERGE_RECORD.md:42-46`) |
| **RNG differences** | **EXCLUDED BY CONSTRUCTION** — counter-based, bit-identical by key (§1.1) |
| **stale code / stale build** | **NO EVIDENCE FOUND** — no document attributes any CPU/GPU disagreement to a stale build, jar, or class |
| **timestep** | **NO EVIDENCE FOUND** — dt effects are documented as runner-independent, and were deliberately measured on the CPU arbiter to avoid the GPU confound (`PURE_SPRINGS.md:187-214`) |
| **genuine numerical/logic error caught by a CPU/GPU comparison** | **NO EVIDENCE FOUND** — the stop condition exists (`04_VALIDATION_GATES_AND_TOLERANCES.md:52-55`) but no doc records it firing |

---

## 7. Enforcement reality

- **No code enforces the CPU-arbiter rule.** `grep -rn -i "trust rule\|arbiter" softbox/*.java`
  returns only bistability *instrumentation* (`-swingkbits`, `-swingkprobe`) and one parity comment.
  There is no gate, assertion, or refusal that blocks a GPU-only gliding number from being reported.
  The rule exists solely as prose in `CLAUDE.md:127-144` plus hand-written lines in ~6 shell scripts.
- **`DEVICE_VALIDATED` is a different thing and IS enforced in code.** Both
  `ExplicitHmmDimerGpuParams.DEVICE_VALIDATED` (`:24`) and `MotorGpuParams.DEVICE_VALIDATED` (`:27`)
  are **`false`**; `requireDeviceValidated` "always throws", and `refuseGpu` never silently falls back.
  This gate is open and is **not** retired by anything in this audit.

---

## 8. Staleness found in the rule text

The rule at `CLAUDE.md:135-136` names as arbiter-gated: `-bondnoise`, `-allnoise`, `-thermcorr`,
`-syswide`, `-xbimplicit*`, `-segimplicit`, `-xbdash`, `-canonical`, `-config1`, `-perphead`,
`-lymntaylor`, `-tauavg`, `-freshread`, `-ratefix`.

Several of these **no longer exist**. `JOURNAL.md:2110-2113`: "**Removed** (preserved in git): rate
machinery, noise-correction family, failed integrators (`-xbimplicit`/`-xbdash`/`-xbsat`), superseded
kinetics (`-atprecharge` etc.), `-freshread`, motor recasts". The rule text was never updated.

---

## 9. Open commitments that touch this area

- The springs re-baseline sign-off flagged for jba (`SPRINGS_PROMOTION.md:128-135`) — **NO EVIDENCE
  FOUND** that it was recorded.
- The deferred matched ρ3000-s102 CPU-object arbiter for the HMM dimer
  (`EXPLICIT_HMM_DIMER_GPU_BACKEND_FINDINGS.md:363-365`) — a multi-hour CPU run, still open.
- The never-run single-head "CPU-arbiter spot check of one mid-density cell"
  (`SINGLE_HEAD_GPU_DENSITY_SWEEP_LONG_FINDINGS.md:209-210`).
- The full `DEVICE_VALIDATED` production-equivalence matrix (ρ100/500/750/1500 × ≥4 seeds × 5000
  steps, T9 polarity, long-run stability, ρ3000 stress).
