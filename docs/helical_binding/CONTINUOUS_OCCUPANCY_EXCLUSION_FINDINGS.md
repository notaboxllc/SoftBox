# Continuous Local Actin Co-occupancy Exclusion

**Authoritative report for the first noncanonical helical-binding extension** (2026-07-23, branch
`gpu-mat-bottlenecks-explicit-singlehead`). Sole Markdown report for this task; structured data in the sibling
`CONTINUOUS_OCCUPANCY_EXCLUSION_CELLS.csv` / `_EVENTS.csv` / `_MANIFEST.json`. Builds on the audit
`ACTIN_HELICAL_BINDING_AUDIT.md` §11-A (the recommended first step).

> **What this is NOT.** Not a discrete-site model, not a monomer lattice, not a 13/6 helical-site model, not
> azimuthal binding, off-axis attachment, twirling, or new chemistry. It is a **continuous minimum-separation
> rule** on the filament-global material coordinate. It changes **no canonical default, parameter, chemistry,
> version, or manifest.**

---

## 1. Question and scope

The audit (§8) established that a per-head *orientational* throttle scales engagement but **cannot cap
co-occupancy** and never bends the velocity–density curve to a plateau; the recorded conclusion is that the real
capping lever is **steric co-occupancy exclusion**. This task ports the existing continuous axial-gap occupancy
veto — a diagnostic, sister-head-only, CPU-only rule — into the **canonical explicit-S2 gliding lineage** as a
**named, flag-gated, default-OFF GLOBAL rule** (across *all* bound heads on a filament), and asks, at increasing
motor density, whether preventing multiple heads from occupying the same local actin region:

1. reduces bound-head recruitment; 2. shifts ρ½; 3. changes Vmax; 4. alters marginal force per added head;
5. changes the existence/shape of velocity saturation.

The first implementation answers only whether this continuous exclusion is **numerically valid** and
**behaviorally consequential** — deliberately narrow: no discrete sites, no azimuth, no tuning.

**Scope delivered:** (1) CPU explicit-S2 single-head; (2) GPU explicit-S2 single-head (device-resident); (3) CPU
HMM-dimer (global). Dimer GPU kernel deferred (as scoped).

---

## 2. Existing implementation audited (Phase 1)

The pre-existing veto lives in the **diagnostic HMM-dimer** lineage, CPU-only, default-off, **not** in the
canonical production gate:

| Aspect | Existing `occupancyVeto` (audited) |
|---|---|
| Location | `ExplicitHmmDimer3jsHarness.occupancyVeto` / `filMatCoordUm`; constant `ExplicitHmmDimerGpuParams.STANDING_EXCLUSION_NM = 5.4` |
| Rule | `reject iff |candMat − partnerMat|·1e3 < exclNm − tol` (tol = 1e-3 nm ⇒ exactly 5.4 nm ALLOWED); symmetric |
| Coordinate | `filMatCoordUm` — continuous material coordinate (µm) walking the chain from the pointed terminal (`end1NbrSlot==SENTINEL`) via `end2NbrSlot`; correct **across** segment boundaries; half-open ownership `bindArc = footC + half` |
| **Partner semantics** | **SISTER-HEAD-ONLY** — compares the candidate against the **dimer partner** `p = (N==2)?(1−m):-1` (or `m^1` in the gliding harness), never against every bound head |
| Ordering | applied AFTER the geometric 8-gate AND, BEFORE commit; a pure veto (never moves a head/alters coordinates) |
| Same-step conflict | head 0 is evaluated & commits before head 1 ⇒ deterministic lower-head-index tie-break |
| Runner | CPU-only; not in `matGeomGate`/`matBindExplicit`/`dimerBindGate` |

**Critical-question answer (verbatim to the task):** the existing veto compares a candidate **only against the
sister head of the same dimer**, *not* against every already-bound head on the same filament. Per the task, the
sister-only behavior is **preserved unchanged** (the named regression path — still the dimer default), and the
new experiment is implemented as a **clearly separate GLOBAL rule**.

---

## 3. Experimental rule and semantics (Phase 2)

**Continuous local actin co-occupancy exclusion (GLOBAL).** A candidate attachment at material coordinate
`s_candidate` is REJECTED when another eligible bound head on the **same filament** occupies `s_bound` with

```
|s_candidate − s_bound| < exclusionDistance     (exclusionDistance = 5.4 nm)
```

- **Global material coordinate.** `s = segCumArc[seg] + bindArc`, where `segCumArc[seg]` is the cumulative arc
  (µm) at the pointed end of `seg` (host-precomputed by `computeMaterialMaps`, **identical convention to
  `filMatCoordUm`**), and `bindArc` is the canonical half-open in-segment arc. **Continuous across segment
  boundaries; no discontinuity; polarity/topology-correct** (material order follows `end2NbrSlot`, not index).
- **Same filament only.** `segFilId[seg]` (connected-component id) gates the comparison; different filaments never
  exclude, even at identical material value.
- **Self-excluded**; the candidate never vetoes itself.
- **Sister included.** Under the global rule a bound dimer sister on the same filament **participates** (it is a
  bound head). This is the documented, intentional divergence from the sister-only veto (global ⊇ sister).
- **Boundary.** Reject only when `sep < exclusion − tol` (tol = 1e-3 nm) ⇒ **exactly 5.4 nm is ACCEPTED**.
- **Same-step conflicts — deterministic sequential.** Candidates are committed in **ascending head-id order**;
  each accepted head immediately becomes occupied for later same-step candidates ⇒ of two candidates within the
  threshold in one step, **exactly one binds and the lower head-id wins**. **No RNG, no occupancy lottery, no
  occupancy state array, no discrete site index.** CPU and GPU use the **same** semantics (one implementation).

Named — and only ever named — **“continuous local actin co-occupancy exclusion.”**

---

## 4. Code changes (Phase 3)

**Two-stage split (one implementation, both runners)** in `softbox/TwoBodyBeamAnalyticGpu.java`:

- **`matBindGateOnly`** — a parallel `@Parallel`-over-motors twin of `matBindExplicit` with **identical 8-gate
  arithmetic** (kept in sync, commented), but it **defers commit**: writes the geometric candidate to scratch
  (`candInt[m]`=segment, `candInt[N+m]`=accept, `candArc[m]`=arc). Race-free; every `m` fully written.
- **`matOccupancyResolve`** — a **single-thread serial** kernel (`for(@Parallel gid=0;gid<1;gid++)`, the CSR-scan
  idiom) that commits candidates in ascending id order applying the global exclusion + lowest-id conflict rule,
  reading `segCumArc`/`segFilId`, writing `boundSeg`/`bindArc`, and accumulating telemetry into `occStats`
  (`[geomCandidates, rejects, accepted, sameStepConflicts]`).
- **`computeMaterialMaps`** — host precompute (once per build; topology + per-segment length are static in the
  gliding assay) of `segCumArc` + `segFilId`.

**Wiring (default-OFF ⇒ byte-identical).** `softbox/ExplicitCompleteMatHarness.java`:
- Flag **`-occupancy-exclusion-nm <nm>`** (0/omitted/negative ⇒ OFF ⇒ the canonical `matBindExplicit` runs
  unchanged; no new tasks/buffers wired). `occOn()` gates every addition.
- ON path replaces the single `bind` task with `gateOnly → occResolve` on **both** the CPU runner
  (`stepGlidingCPU`) and the device graph (`buildGlidingGraph`, device-resident, single worker-grid over N +
  a 1-thread resolve). Scratch/maps/telemetry allocated in `packExMat`, transferred FIRST_EXECUTION, `occStats`
  read back each step. Exclusion visible in run metadata + JSON.
- Test-only hook `OCC_FORCE_ON` (never set by any runner) forces the gate-only→resolve pipeline at exclusion 0 so
  the OFF-path identity is checkable over a real binding trajectory.

**CPU HMM-dimer (global).** `softbox/ExplicitHmmDimerGlidingHarness.java`: flag **`-occupancy-global`** (default
OFF) replaces the sister-only veto with a global scan over all bound heads on the (single) filament, reusing
`filMatCoordUm` and the same reject convention; the sister-only path is untouched (regression).

**Governance:** MotorModel.CANON_VERSION (2), the canonical manifest, canonical defaults, chemistry, mechanics,
`Constants.java`, and `ExplicitHmmDimerGpuParams.java` (incl. `STANDING_EXCLUSION_NM`) are **all unchanged**. No
legacy parameter slot reused: the exclusion travels in a dedicated `occP=[exclNm, tolNm]` buffer.

---

## 5. Deterministic unit + geometry tests

`softbox/ContinuousOccupancyExclusionHarness.java` (`./scripts/run_occupancy_exclusion.sh -fixtures`, CPU-only).
**All 12 required tests PASS** (20 assertions):

| # | Test | Result |
|---|---|---|
| 1 | same coordinate (0 nm) | REJECT ✓ |
| 2 | below threshold (5.3 nm) | REJECT ✓ |
| 3 | at threshold (exactly 5.4 nm) | **ACCEPT** ✓ (reject iff sep < excl − tol) |
| 4 | above threshold (5.5 nm) | ACCEPT ✓ |
| 5 | different filament, identical material coord | ACCEPT ✓ (+ same-fil control REJECT ✓) |
| 6 | segment-boundary continuity (3 nm across boundary REJECT; 6 nm ACCEPT) | ✓ (continuous separations 3.000 / 6.000 nm) |
| 7 | half-open ownership — material coordinate continuous at every boundary | ✓ (max gap 0.000 nm) |
| 8 | polarity/material-coordinate consistency (index-reversed chain follows topology) | ✓ |
| 9 | candidate self-exclusion (lone candidate binds) | ✓ |
| 10 | dimer sister semantics (bound sister participates; 6 nm apart → both bind) | ✓ |
| 11 | same-step conflict (2.7 nm apart → exactly one; lower id wins; conflict counter=1; A/B-relabel identical) | ✓ |
| 12 | OFF-path identity (excl=0 pipeline ≡ canonical `matBindExplicit`, 600-step trajectory, 0 mismatches) | ✓ |

Convention decisions recorded: threshold **inclusive-allow** (test 3); sister **included** in the global rule
(test 10); same-step winner **lowest head-id, deterministic, no RNG** (test 11).

---

## 6. CPU/GPU equivalence

`./scripts/run_occupancy_exclusion.sh -equiv`. Two layers, both PASS:

- **§6A — deterministic resolve-kernel bit-identity (chaos-free).** A constructed 6-head / 2-filament fixture
  engineered to exercise **2 rejects (incl. 1 same-step conflict) + 2 accepts**. `matOccupancyResolve` on the CPU
  runner vs a single-task device graph over **identical inputs** ⇒ `boundSeg` + `occStats` **bit-identical, 0
  mismatches** (CPU commits `h0=REJECT, h1=ACCEPT, h3=ACCEPT, h5=REJECT`; stats `cand=4 rej=2 acc=2 conf=1`; GPU
  identical). This is the rigorous G3 event-identity claim.
- **§6B — real-pipeline event-identity + health.** Single-head explicit-s2-l40, occupancy 5.4 nm ON, ρ700: the
  bind trajectory is **bit-identical CPU↔GPU for the bit-close window** (occupancy decisions — candidates 6,
  accepted 6 — match exactly, **0 occStat divergences**) then decorrelates by chaotic float32 (expected, per the
  project standard, not tested). Mechanism fires (CPU rejects > 0); GPU accepts binds **device-resident (no
  silent CPU fallback)**; **0 invalid states / 0 solver failures.**

Aggregate agreement was **not** used for the deterministic gate: §6A is bit/event-identical on a fixed fixture.

---

## 7. Bounded density experiment

`./scripts/run_occupancy_density_experiment.sh` — single-head **explicit-s2-l40**, GPU device-resident, rigor
mode 1, canonical chemistry/mechanics, dt = 2.5e-6, whole-window LS velocity estimator (equil=0). Densities
**{150, 400, 700, 1500, 3000} heads/µm²**, seeds **101–104**, **40 000 steps**, **OFF vs ON 5.4 nm at paired
seeds**. A **5-density paired diagnostic**, NOT a replacement canonical sweep.

All 40 cells completed device-resident (steps/s 302–455; no CPU fallback). Seed-averaged (n=4), paired OFF vs ON.
Velocity in µm/s (productive = pointed-first; `velProd`); `Δvel` is the paired ON−OFF mean ± SE; per-cell data in
`CONTINUOUS_OCCUPANCY_EXCLUSION_CELLS.csv`, occupancy events in `_EVENTS.csv`.

| ρ (heads/µm²) | velProd OFF | velProd ON | Δvel (paired) | avgBound OFF → ON | Δbound | vel/bound OFF → ON | ATP OFF → ON | **occ. rej. frac (ON)** |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 150  | 1.198 ± 0.243 | 1.145 ± 0.215 | −0.053 ± 0.030 | 1.09 → 1.04 | −4.9% | 1.12 → 1.13 | 131 → 123 | **0.011** |
| 400  | 2.493 ± 0.101 | 2.363 ± 0.188 | −0.130 ± 0.114 | 2.85 → 2.87 | +1.0% | 0.89 → 0.84 | 368 → 364 | **0.097** |
| 700  | 2.800 ± 0.155 | 3.038 ± 0.153 | +0.238 ± 0.170 | 4.98 → 4.93 | −0.9% | 0.57 → 0.62 | 656 → 657 | **0.124** |
| 1500 | 3.601 ± 0.065 | 3.749 ± 0.085 | +0.148 ± 0.113 | 10.44 → 10.50 | +0.6% | 0.35 → 0.36 | 1430 → 1446 | **0.233** |
| 3000 | 4.005 ± 0.076 | 4.010 ± 0.049 | +0.005 ± 0.094 | 20.43 → 19.69 | −3.7% | 0.20 → 0.20 | 2813 → 2742 | **0.381** |

*(5-density paired diagnostic — any Vmax/ρ½ fit would be diagnostic-only; not fitted here.)*

---

## 8. Recruitment and velocity results

**The mechanism fires hard, and rises monotonically with density** — the ON occupancy-rejection fraction climbs
**1.1% → 9.7% → 12.4% → 23.3% → 38.1%** across ρ. At ρ3000 roughly **38% of geometric candidates are rejected**
(~1.4k–2.0k rejects/cell over 40 000 steps). So the exclusion is **numerically very active** at high density —
not inert code.

**Yet every emergent observable is essentially unchanged OFF vs ON:**

1. **Bound-head recruitment — NOT capped.** avgBound changes by −4.9 / +1.0 / −0.9 / +0.6 / −3.7 % across ρ:
   scatter around zero within the seed spread, **not monotonic in density**, not a cap. Even at ρ3000, rejecting
   38% of candidates lowers avgBound only 20.43 → 19.69 (−3.7%, ≈1 SD).
2. **ρ½ / density-response shape — unchanged.** The avgBound-vs-ρ and velocity-vs-ρ curves overlie OFF and ON.
3. **Vmax / velocity — unchanged.** Paired Δvel is small and **sign-mixed** (−0.05, −0.13, +0.24, +0.15, +0.005),
   none exceeding ~1.4 SE; at the ρ3000 plateau Δvel = +0.005 ± 0.094. No shift in the ~4 µm/s plateau.
4. **Marginal force per added head — unchanged.** vel/bound OFF ≈ ON at every density.
5. **Velocity saturation — unchanged in existence and shape.** Both arms saturate near ρ3000 at ~4 µm/s.

**Why active-but-inert (mechanistic).** At avgBound ≈ 20 over the ~2.1 µm filament the mean bound-head spacing is
≈ 100 nm ≫ 5.4 nm, so the filament is **far from 5.4-nm-saturated**. The exclusion prevents *co-occupancy* (a
second head within 5.4 nm of an incumbent), **not occupancy** — the incumbent already occupies and propels from
that local region, so a rejected near-neighbor removes **no propulsive contribution and frees no binding slot**.
The rejected candidates are redundant; the total number of occupied local regions (= avgBound) is set by binding
kinetics + filament geometry, not by the 5.4 nm rule. Same-step conflicts are vanishingly rare (**5 total across
all 20 ON cells / 800 000 step-cells**) — nearly every rejection is against a *previously*-bound head, confirming
the sequential lowest-id rule is exercised but the co-occupancy pressure is overwhelmingly temporal, not
simultaneous.

---

## 9. Health and performance

- **Numerical health.** Across all deterministic tests and every experiment cell: **invalid states = 0, solver
  failures = 0** (device redOut finiteness), no new rate-cap warnings, no pathological memory growth.
- **Numerical health across the full 40-cell experiment: invalid = 0, solver failures = 0** (every OFF and ON
  cell), no rate-cap warnings, no memory growth.
- **Performance.** The ON path adds a parallel gate-only pass + a single-thread serial resolve. Measured ON
  throughput over the 40 000-step production cells: **450 steps/s @ ρ150 → 302 steps/s @ ρ3000** (wall 88–133 s).
  The serial resolve is **not pathological** — it touches only gate-passing candidates and prunes to bound heads
  on the same filament; the modest slowdown with density tracks the rising candidate count, not a blow-up. GPU
  device-resident throughout; no CPU fallback observed.

---

## 10. Decision-gate verdicts

- **G1 — OFF-path identity.** **PASS.** The default-OFF single-head production cell PARTCROW is **bit-identical**
  to the git-HEAD baseline (verified by rebuild-and-compare, ρ700 s101). The gate-only→resolve(excl=0) pipeline
  reproduces canonical `matBindExplicit` binding decisions **bit-identically** over a 600-step trajectory (test
  12). Default OFF changes no wiring.
- **G2 — Geometry correctness.** **PASS.** Global material-coordinate exclusion is continuous across segment
  boundaries (tests 6, 7) and invariant to ownership/index representation (test 8); different filaments never
  exclude (test 5).
- **G3 — CPU/GPU semantics.** **PASS.** Deterministic resolve kernel bit-identical CPU↔GPU (§6A); real-pipeline
  binding events bit-identical while bit-close (§6B); no silent CPU fallback.
- **G4 — Numerical health.** **PASS.** invalid = 0, solver failures = 0 across all 40 production cells + all
  deterministic tests; no rate-cap warnings; no pathological performance/memory (302–455 steps/s).
- **G5 — Biological usefulness. Category A — No measurable effect (numerically active, behaviorally inert).**
  Under canonical continuous geometry the exclusion is **numerically very active** (rejection fraction rising to
  38% at ρ3000) but has **no measurable effect on any emergent observable**: recruitment is not capped (avgBound
  scatter ±5%, non-monotonic), ρ½ and the density-response shape are unchanged, Vmax/velocity is unchanged
  (paired Δvel ≤ 1.4 SE, sign-mixed), marginal force per head is unchanged, and velocity saturation is unchanged
  in existence and shape. **Not B** (no proportional recruitment throttle), **not C** (no preferential
  high-density recruitment reduction / no curve-shape change), **not D** (low-density gliding not impaired),
  **not E** (fully valid, healthy). This is the informative null the task anticipated — reported as-is, not
  tuned. **Consequence for the audit §8 hypothesis:** a *continuous* 5.4 nm co-occupancy exclusion **alone does
  not** cap recruitment or bend the velocity–density curve at canonical geometry, because the continuous
  single-head bind coordinate is far from 5.4-nm-saturated (≈100 nm mean head spacing).

---

## 11. Interpretation and limitations

- The 5.4 nm value is an **experimental continuous steric-separation rule** carried over from the diagnostic
  implementation (≈ one actin-monomer axial spacing). **No claim of biological truth**, no reproduction of actin
  monomer occupancy, the 13/6 helix, twirling, or site-specific chemistry.
- This is a **continuous** minimum-separation rule, **not** a discrete-site or lattice model, and carries **no
  site index / occupancy grid**.
- A density-dependent capping result would justify a later discrete-site or helical extension; a **null result is
  also informative** and is reported as-is (not tuned away).

---

## 12. Smallest justified next step

The result — active-but-inert at the canonical 5.4 nm scale — points to **one** cheap, decisive next experiment
before any structural elaboration:

- **An exclusion-DISTANCE sweep** (e.g. 5.4 → 11 → 22 → 36 nm) at ρ ∈ {1500, 3000} on the *same* single-head
  path, reusing this machinery unchanged (only the `-occupancy-exclusion-nm` value changes). This locates the
  continuous exclusion scale at which co-occupancy *begins* to cap recruitment and bend the velocity–density
  curve (if any) — turning the current null into a threshold. It needs no new code and no azimuth.

**Explicitly NOT next:** a discrete-site / 13/6-lattice / helical-site model. Nothing here justifies one yet —
the *continuous* rule at the canonical value is inert, so the first question is whether *any* continuous
exclusion scale caps recruitment (abstract-from-the-second-instance). Off-axis attachment / twirling remain a
separate goal (audit §11-B), unrelated to co-occupancy capping.

---

## Completion summary

- **Files changed:** `softbox/TwoBodyBeamAnalyticGpu.java` (`matBindGateOnly`, `matOccupancyResolve`,
  `computeMaterialMaps`); `softbox/ExplicitCompleteMatHarness.java` (flag `-occupancy-exclusion-nm`, ExMat
  scratch/maps/telemetry, gate-only→resolve wiring on CPU + GPU, JSON telemetry, `OCC_FORCE_ON` test hook);
  `softbox/ExplicitHmmDimerGlidingHarness.java` (`-occupancy-global`, sister-only preserved as regression);
  **new** `softbox/ContinuousOccupancyExclusionHarness.java`, `scripts/run_occupancy_exclusion.sh`,
  `scripts/run_occupancy_density_experiment.sh`, `scripts/occupancy_exclusion_analysis.py`, and this report + the
  three sibling data files.
- **Flag/config added:** `-occupancy-exclusion-nm <nm>` (single-head; 0/omitted = OFF = exact canonical path);
  `-occupancy-global [-occupancy-exclusion-nm <nm>]` (CPU HMM-dimer; default OFF = sister-only regression).
- **Canonical OFF behavior identical:** YES — bit-identical to the git-HEAD baseline (G1) + resolve(0) ≡ canonical.
- **CPU/GPU equivalence:** resolve kernel bit-identical CPU↔GPU on a constructed fixture (0 mismatches);
  real-pipeline binding events bit-identical while bit-close.
- **Bounded experiment:** velocity/recruitment/saturation unchanged OFF vs ON despite up to 38% candidate
  rejection at ρ3000; 0 invalid / 0 solver across 40 cells.
- **Decision category: A** (numerically active, behaviorally inert — informative null).
- **No canonical parameter, chemistry, default, version, or manifest changed** (`CANON_VERSION = 2`;
  `Constants.java`, `ExplicitHmmDimerGpuParams.java`/`STANDING_EXCLUSION_NM` untouched).
