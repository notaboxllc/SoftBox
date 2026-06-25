# CROSSOVER DIAGNOSIS — is the high-scale v2-GPU/v1-GPU crossover REAL or an ARTIFACT of v1-GPU under-binding?

**Date:** 2026-06-24 · aorus, TornadoVM 4.0.1-dev PTX, RTX 5070 · **MEASUREMENT-ONLY** (no model edits, either engine).
Sole GPU occupant. v1 = BoA scratch `/tmp/v1scratch` (physics == BoA-v1ref, read-only). v2 = SoftBox `V2OneXHarness`.
Inputs: the `STANDARD_BENCHMARK_4WAY_FINDINGS.md` grid + PART-3 work-match counts; v2 per-graph timing **re-measured**
here with an uncommitted `-pergraph` instrument (flagged, §FILES); v1's GPU binding path **read** in BoA-v1ref.

## TL;DR — THE VERDICT: **(a) — the crossover is an ARTIFACT of v1-GPU under-binding.**

- The raw `steps/s` crossover (v2-GPU falls behind v1-GPU at ~4×) is **NOT** a v2 super-linear kernel. v2-GPU's
  whole device path scales **honestly linearly** (TOTAL exponent **p=0.98**, per-doubling 1.87/2.04/1.97 ≈ ideal 2.0).
- v1-GPU's apparent high-scale advantage is **dropped work**: v1-GPU binds **53 % (1×) → 19 % (8×)** of v1-CPU's
  bound motors (PART-3 lead C, **confirmed**). The bound-motor cross-bridge force is the dominant per-step cost, so
  v1-GPU's `steps/s` is flattered by carrying ~8× less binding load at 8×.
- **Work-normalized** (bound-motors processed per wall-second), **v2-GPU is 4.7–6.1× ahead at EVERY scale — the
  crossover VANISHES.** Raw crossover ≈ **4×**; **work-normalized crossover: none in [1×, 8×]** (v2-GPU never crosses).
- **WHY v1-GPU under-binds (one line):** v1's rebind refractory is gated by a **`static` (one global) `bindTimer`**
  reset to 0 on *any* motor release; the GPU path applies that gate **serially on the host** while *unpacking* the
  parallel bind kernel's results, so once releases-per-step rise with scale the global timer blocks proportionally
  more binds — a known v1 static-global race (the `bindTimer` bug in `GLIDING_4biv_RESIDUAL_DOSSIER.md` Part 2),
  **NOT a v2 issue**.

Proportion: the high-scale crossover is **~100 % (a)**. There is a *faint* (b) component — two minor v2 graphs lean
super-linear (`fdBind` last-doubling 2.63×, `fdXForm` p=1.11) — but together they explain the difference between the
TOTAL's 1.87→1.97 lean and ideal 2.0, **not** the crossover; the dominant graph (`fdFil`, 62 % of the step) is p=0.98.

---

## CHEAP FIRST CUT — per-doubling step-time ratios (no run; ideal weak-scaling = 2.0×)

Step-time ratio per doubling = `steps/s[N] / steps/s[2N]` (= the 1/throughput ratio = honest if ∝1/N ⇒ 2.0×).

| path | 1×→2× | 2×→4× | 4×→8× | reading |
|---|---|---|---|---|
| **v1-CPU** | 1.63 | 1.72 | 1.86 | sub-linear → approaching honest at scale (16-thread pool amortizes) |
| **v1-GPU** | **1.25** | **1.34** | **1.52** | **far below 2.0 = a full-work path CANNOT do this** ⇒ dropped work / huge fixed overhead |
| **v2-CPU** | 2.01 | 2.00 | 2.11 | honest ∝1/N (single-thread, faint super-linear at top from O(nSeg) CSR scans) |
| **v2-GPU** | 1.85 | 1.98 | 2.05 | ≈ ideal 2.0 (honest ∝1/N), faint super-linear lean only at the top |

**The framing the data forces:** v2-GPU's ~2.0×/doubling is the signature of an honest full-work path whose cost ∝ N.
v1-GPU's 1.25–1.52×/doubling is **impossible for a full-work path** — it means each doubling adds far less than 2× the
work. The thrusts below show that "missing work" is **bound motors** (Thrust 1) and that v2's curve has **no genuine
super-linear kernel** driving the crossover (Thrust 2).

---

## THRUST 1 — quantify lead C + re-read the crossover WORK-NORMALIZED

### Lead-C deficit curve (v1-GPU bound / v1-CPU bound) — CONFIRMED

Bound-motor counts from PART 3 (v1 run-mean `meanBoundMotors`; spot-checked in `/tmp/v1_fdt_diag/{cpu,gpu}_1x.log`
which independently show the same qualitative deficit, 928 vs 294 ≈ 32 % on a longer run):

| scale | v1-GPU bound | v1-CPU bound | **v1-GPU / v1-CPU** |
|---|---|---|---|
| 1× | 245 | 459 | **53 %** |
| 2× | 367 | 894 | **41 %** |
| 4× | 430 | 1611 | **27 %** |
| 8× | 573 | 3067 | **19 %** |

The deficit **widens monotonically** with scale, and v1-GPU's bound count is nearly **flat** (245→573, ~2.3× over an
8× scene) while v1-CPU's tracks the scene (459→3067, ~6.7×). v1-GPU is binding a near-constant *absolute* number of
motors regardless of scene size — the signature of a per-step *global* binding ceiling, not a per-motor rate.

**Control (v2-GPU / v2-CPU bound) — v2 does NOT have the divergence:**

| scale | v2-GPU | v2-CPU | v2-GPU / v2-CPU |
|---|---|---|---|
| 1× | 598 | 506 | 118 % |
| 2× | 1106 | 915 | 121 % |
| 4× | 2252 | 1567 | 144 % |
| 8× | 4472 | 2757 | 162 % |

v2-GPU binds *slightly more* than v2-CPU (the v2-GPU window had more settled steps; the validated CPU≡GPU gate is
bound-set Δ≤5/9600), and both **track the scene** — no dropped-work divergence. The lead-C asymmetry is **v1-specific**.

### Work-normalized throughput — does the crossover survive?

The bound-motor cross-bridge force is the dominant per-step kernel cost (PART 3; the crosslink count is a minor
fraction). The honest cross-engine metric is **cross-bridge work units processed per wall-second = steps/s × bound**:

| scale | v1-GPU bm/s | v2-GPU bm/s | **v2/v1 (work-normalized)** | raw steps/s v2/v1 |
|---|---|---|---|---|
| 1× | 5 243 | 29 601 | **5.65×** | 2.31× |
| 2× | 6 276 | 29 530 | **4.71×** | 1.56× |
| 4× | 5 504 | 30 402 | **5.52×** | 1.05× |
| 8× | 4 813 | 29 515 | **6.13×** | 0.79× |

**The crossover does NOT survive normalization.** Raw `steps/s` crosses at ~4× (v2 ahead below, behind above), but
**per unit of binding work v2-GPU is 4.7–6.1× ahead at all four scales** — and the gap is *flat-to-widening*, the
opposite of a crossing. Note v2-GPU's bm/s is ~constant (~29.5k) — v2's ms/step grows in lock-step with its bound
count (linear) — while v1-GPU's bm/s is also ~constant (~5k) but on a near-frozen bound count, so its `steps/s` decays
slowly and *looks* like it scales well. **The "good v1-GPU scaling" is the deficit in disguise.**

- **Raw crossover scale: ~4×.**
- **Work-normalized crossover scale: NONE in [1×, 8×]** (v2-GPU never falls behind per unit work; a crossover would
  require v1-GPU to do ≥4.7× more work/s, i.e. extrapolating the bm/s curves they do not cross within range).

### WHY v1-GPU under-binds (read-only characterization — REPORTED, not fixed)

Root cause: **a `static`-global rebind-refractory gate applied serially during the GPU unpack.**

- `MyoMotor.bindTimer` is declared **`static double`** (`BoA-v1ref/boxOfActin/MyoMotor.java:73`) — **one variable
  shared by ALL motors**, not per-motor state.
- The bind refractory gate is `if (bindTimer < Env.myoRebindTime) return;` in `MyoMotor.ontoFilament`
  (`MyoMotor.java:455`), with `myoRebindTime = 1e-5 s = dt` (`Env.java:832`). `bindTimer` is `+= dt` once per step
  (`:179`) and **reset to 0 on any release** (`MyoFilLink.java:315`).
- The **GPU bind kernel** (`GPUMotorBinding.bindKernel`, `:643-760`) is purely geometric — it applies only the
  align/`rodDotFil≥0`/α∈[0,1]/`myoColTolSq` gates and the **"first hit wins, break"** 27-cell walk, and crucially
  does **NOT** evaluate the `bindTimer` refractory on the device. The refractory is enforced **on the host**, in the
  **serial unpack loop** that walks `boundSegId` in motor order and calls `ontoFilament` per hit
  (`GPUMotorBinding.java:~1834-1842`, comment: *"the bindTimer gate live inside ontoFilament"*).
- **Consequence:** because `bindTimer` is a single global reset to 0 by every release, once any motor releases in a
  step, the global timer sits below `myoRebindTime` and the serial host unpack **gates out subsequent candidate binds
  in that same batch**. As scale rises, releases-per-step rise, so a *growing fraction* of the kernel's geometric bind
  candidates are rejected at unpack — producing the **monotone-widening 53 %→19 % deficit** and the near-flat absolute
  bound count. v1-CPU's multi-threaded `ThreadSet` interleaves tick/bind/release per motor differently and does not
  serialize all binds behind one global timer the same way (lead C is v1-CPU≢v1-GPU, 0 % on a comparable v2 gate).

This is exactly the **`bindTimer` static-global race** already documented for the BoA side in
`GLIDING_4biv_RESIDUAL_DOSSIER.md` Part 2 (≈0.31 GPU / 0 % CPU, "parameter-vestigial"). It is a **v1 BoA-active
concern**, not a v2 issue — flagged, **not fixed** (read-only oracle).

---

## THRUST 2 — v2 per-kernel/per-graph scaling (is there a genuine super-linear kernel?)

**Re-measured** with the uncommitted `-pergraph` instrument (per-graph `execute()` wall, `nanoTime`, warmup-excluded,
520 measured steps each). Reproduces the doc's `steps/s` (49.7/26.6/13.0/6.6 vs doc 49.5/26.7/13.5/6.6). `fdXForm`
is cadence-gated (fires 1/100 steps) so its ms/step is its fire-cost amortized over all steps — its *scaling* is still
the right quantity to read.

### Per-graph ms/step, per-doubling ratio, log-log scaling exponent (time ∝ N^p; ideal weak-scaling p=1.0)

| graph | 1× | 2× | 4× | 8× | r₁ | r₂ | r₃ | **exp p** | % of step @8× |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **fdFil** (chain + node-seg-gather + xlink force/torsion/2-pass) | 12.34 | 23.80 | 47.58 | 93.39 | 1.93 | 2.00 | 1.96 | **0.98** | **61.8 %** |
| **fdXForm** (xlink FORMATION, cadence-gated; FIL×FIL broad-phase) | 4.71 | 10.10 | 23.56 | 46.45 | 2.14 | 2.33 | 1.97 | **1.11** | 30.7 % |
| **fdBind** (grid build + reachable broad-phase) | 1.41 | 1.86 | 3.14 | 8.26 | 1.32 | 1.69 | **2.63** | 0.84 | 5.5 % |
| fdStruct (zero/Brownian + node-shell structure) | 0.85 | 0.98 | 1.26 | 1.70 | 1.15 | 1.29 | 1.35 | 0.34 | 1.1 % |
| fdInteg (containment + integrate + derive) | 0.69 | 0.71 | 0.76 | 0.80 | 1.02 | 1.08 | 1.04 | 0.07 | 0.5 % |
| hostCSR (CSR-host round-trip: boundSeg pull + host scan) | 0.066 | 0.119 | 0.257 | 0.600 | 1.79 | 2.17 | 2.33 | 1.07 | 0.4 % |
| **TOTAL** | 20.07 | 37.56 | 76.56 | 151.20 | 1.87 | 2.04 | 1.97 | **0.98** | 100 % |

### Reading

- **TOTAL p=0.98 — v2-GPU's device path is honestly linear (∝N).** There is **no kernel bending hard enough to drive
  the crossover.** The faint raw-steps/s lean (1.85→2.05/doubling) is within measurement noise of ideal 2.0 plus the
  two minor super-linear graphs below.
- **fdFil — the DOMINANT graph (62 % of the step at 8×) — is p=0.98, dead-linear** (per-doubling 1.93/2.00/1.96). The
  2-pass crosslink gather and node-seg-gather do **not** bend; the CSR-inverse gathers scale ∝ link/bond slots ∝ N.
- **fdXForm — p=1.11, MILDLY super-linear** (30.7 % @8×): the FIL×FIL formation broad-phase generates more
  candidate pairs per cell as density rises (contention ∝ N²·P_form², capped per-seg). Cadence-gated, so its
  amortized cost is modest, but it is the **clearest genuine super-linear lean** — a concrete optimization target if
  formation density ever dominates.
- **fdBind — p=0.84 overall but the FINAL doubling jumps to 2.63×**: the grid reachable broad-phase shows an
  **emerging super-linear lean at 8×** (more candidates per cell → longer per-motor 27-cell scans). Only 5.5 % of the
  step, but it is the second named density-sensitive kernel and would grow if the scene densified further.
- **hostCSR — p=1.07, ∝scale** (the CLAUDE.md-flagged CSR-host dynamic round-trip ≈350 KB/step at 16×). Confirmed
  ∝N here, but **0.4 % of the step** at these scales — net-positive holds through 8× as documented; re-verify only at
  much larger scale.

**Verdict for Thrust 2: every dominant kernel is ∝N (p≈1.0). The two super-linear leans (`fdXForm` p=1.11, `fdBind`
last-doubling 2.63×) are minor and density-driven, and together account for only the small gap between TOTAL's
1.87→1.97 and ideal 2.0 — they are the named real v2 high-density weaknesses but are NOT the crossover.**

---

## SYNTHESIS — the verdict

**(a) The crossover is an ARTIFACT of v1-GPU under-binding.** Proportion ≈ **100 % (a)**, with a faint, named **(b)**
present but non-causal:

- **(a)** v1-GPU drops binding work monotonically with scale (lead C, 53 %→19 %), carrying a near-frozen ~250–570
  bound motors vs v1-CPU's 459–3067. The dominant per-step cost is the bound-motor cross-bridge force, so v1-GPU's
  `steps/s` is flattered at scale. **Work-normalized (bm/s), v2-GPU is 4.7–6.1× ahead at every scale and the crossover
  vanishes.**
- **(b)** v2-GPU's own path is honestly linear (TOTAL p=0.98). The only super-linear leans are minor: `fdXForm`
  (formation broad-phase, p=1.11) and `fdBind` (reachable broad-phase, 2.63× at the 8× doubling). They explain the
  small 1.87→1.97 super-linear lean in v2's raw curve but **not** the crossover (the crossover is driven by v1-GPU's
  baseline collapsing, not v2's cost exploding).

**Crossover scales:**
- **Raw `steps/s`: ~4×** (v2-GPU ahead below, behind above).
- **Work-normalized (bm/s): NONE in [1×, 8×]** — v2-GPU is uniformly 4.7–6.1× ahead per unit binding work.

**Named super-linear v2 kernels (the real high-density weakness, for a future optimization, not the crossover):**
`fdXForm` (crosslinker **formation broad-phase**, p≈1.11) and `fdBind` (motor-binding **reachable broad-phase**,
last-doubling 2.63×). Both are candidate-density-driven broad-phase passes — the expected place for a super-linear
lean, both minor at current scale.

**One-line WHY v1-GPU under-binds:** the rebind refractory is a **single `static` `bindTimer`** reset by any release
and enforced **serially on the host during the GPU bind-unpack**, so rising releases-per-step gate out a growing
fraction of the device kernel's bind candidates — the documented BoA `bindTimer` static-global race, not a v2 issue.

---

## CARRY-FORWARD / FLAGS
- The work-normalized result means **v2-GPU has no high-scale throughput regression** — the "crossover" is a v1-GPU
  baseline artifact. Do not chase a v2 fix for it.
- The two named super-linear broad-phase leans (`fdXForm`, `fdBind`) are the **genuine** v2 high-density targets if a
  much-denser scene is run; both are candidate-generation passes (fuse / coarsen candidate sets, or tighten the cell
  stencil). Neither is urgent at ≤8×.
- v1-GPU's `bindTimer` under-binding is a **BoA-active** concern (read-only here), already on the BoA backlog
  (`GLIDING_4biv_RESIDUAL_DOSSIER.md` Part 2).

## FILES / INSTRUMENTATION (uncommitted — flagged for revert)
- `softbox/V2OneXHarness.java` — **MEASUREMENT-ONLY** `-pergraph` per-graph `execute()` wall timer (static `PERGRAPH`
  flag, `pgWall[6]`, accumulate around each `plan.withGraph(gi).execute()` + the host-CSR block, reset post-warmup,
  print mean ms/step at run end). When `-pergraph` is absent the production path is **byte-unaffected** (the only
  added cost is two `if (PERGRAPH)` checks per graph, both false). **NOT committed** — revert or keep clearly
  uncommitted. No kernel / force-law / ordering / default edit. `/tmp/v1scratch` & BoA-v1ref byte-clean (read-only).
- Raw measured per-graph numbers above are from 4× `./run_1x.sh -gpu -pergraph -nodes <N> -nfil <N> -steps 540`.
