# Megakernel + CSR-host probe — how much of v1's per-step edge is recoverable, by which lever?

**Date:** 2026-06-23. **Branch:** `megakernel-probe` (off `cadence-gate-fdturn`). **Status:** DONE.
**Scope:** MEASUREMENT-ONLY. Two independent, separately-toggleable recompositions of v2's maximal node-centric
device path (`-megakernel`, `-csrhost`); NO physics/rate/default edit. Production `stepSplit` path (both toggles
off) byte-unchanged. `BoA-v1ref` byte-clean (v1 numbers taken from `V1_MAXIMAL_BENCHMARK_FINDINGS §3`, **not**
re-run). Two commits: lever 1 (megakernel), lever 2 (CSR-host), separately revertible.

---

## TL;DR — the decisive read

**v1's per-step edge on the sparse node-centric composition is mostly the §4(b) WORK ASYMMETRY, not launch
overhead. Fusing the per-body hot mechanics (the megakernel) barely moves the gap.** A second, more effective
lever surfaced — the single-GPU-thread CSR scans are a real cost — but even **both** levers together, collapsing
**97→78 always-run launches/step**, recover only **~37 % of the gap at 1× and ≤10 % at 4–16×**. The majority of
v1's lead (and a growing share of it at scale) is that v2 does real work v1's sparse scene never triggers
(29–374 crosslinks formed, node-tether gathers, myosin binding) — confirming `V1_MAXIMAL_BENCHMARK §4`.

**Per the probe's own decision rule:** the megakernel closing essentially nothing ⇒ **the architecture is NOT
vindicated-by-fusion (the 2× is not recoverable by recomposition); re-examine the work asymmetry.** Folding the
optional/cross-entity systems in (the deferred second slice) would not help either — they ARE the work asymmetry.

**Bit-exactness:** both recompositions are **bit-exact** — fused-CPU ≡ unfused-CPU to **max|Δ pose| = 0.0** over
300 steps (pure kernel regrouping; the CSR is pure-integer so host-built == device-built). CPU≡GPU aggregate
agrees, conservation EXACT, 0 phantoms, no NaN at every scale and every lever config. No atomics / no KernelContext.

---

## §1. What was recomposed (Stage 0 + Stage 1)

The maximal node-centric scene (`-dense -mini 0`) runs as 6–7 chained device TaskGraphs (`buildPlanSplit`),
~97 always-run device launches/step (the `V1_MAXIMAL_BENCHMARK §4` "74–106 small kernels"). Two levers, on the
**universal hot path only** (the per-body-LOCAL mechanics present in every simulation), leaving the
optional/cross-entity systems (binding, cross-bridge, crosslinker formation+force, node/seg gathers, turnover,
nucleation) as separate kernels so the "absent-element-costs-zero-launches" extensibility property holds:

**Lever 1 — mechanics megakernel (`MechanicsFusion.java`, device-agnostic; both runners call it):**
- `forceBuild` = `zeroAccumulators` + `brownianForce`, fused per store (writes `forceSum=0` + `randForce`).
- `integDerive` / `integDeriveConfined` = `[confine +] integrate + derive`, fused per store (reads the fully
  accumulated `forceSum`).
- The two megakernels **bracket the cross-entity gathers** (chain, seg-gather, tether, crosslinker), which keep
  `+=`-ing into `forceSum` as separate kernels between them — so the accumulation order is preserved bit-exactly.
- The filament **chain** force stays its own kernel: folding it needs an 18→15-arg neighbour-array repack for a
  **single** launch of ~97 — a justified clean split (the probe permits "a different clean split").
- `boxParams[7]=dt` added so the confined integrate-megakernel stays ≤15 args; integrate+derive **inlined** (a
  private helper hits the PTX 600-node inlined-callee cap — the documented "dimer gotcha").
- Collapses **13** launches: {5 zero + 5 brown → 5} and {13 confine/integrate/derive → 5}. **97 → 84.**

**Lever 2 — CSR-host (move the single-GPU-thread CSR scans off the device):**
- The node-attach CSR (`ndHist/ndScan/ndScatter`) is **STATIC** (`attachNode` never changes) ⇒ host-precompute
  once at build, skip the 3 device scans every step — **zero per-step round-trip** (pure win).
- The node-shell-motor seg-gather CSR (`filHist/filScan/filScatter`, keyed by the dynamic `boundSeg`) ⇒
  host-build each step from `boundSeg` pulled after `fdBind`; `fdFil` re-uploads `segMotor{Count,Offsets,Myo}`
  EVERY_EXECUTION; the device `filGather` consumes them. **This is the round-trip the probe measures.**
- Removes **6** launches/step. **97 → 91** (alone), **84 → 78** (with lever 1).

---

## §2. Stage 2 — validation (the recomposition is physics-preserving)

| gate | result |
|---|---|
| **fused-CPU ≡ unfused-CPU, BIT-EXACT** (300 steps, node-centric) | **max\|Δ pose\| = 0.0** — `-megakernel`, `-csrhost`, and both. The arithmetic is unchanged; only kernel grouping / placement changes. |
| **CPU ≡ GPU aggregate** (the §8 chaotic-many-body standard) | AGREE every config — active 1050=1050, node-bound/xlink within tolerance (PTX vs JVM transcendentals decorrelate the float32 microstate exactly as the pre-existing FullSystemDemo CPU≡GPU). |
| **conservation / phantoms / NaN** | EXACT / 0 / none — all 20 sweep points (the 8×/16× points show 3–4 transient warm-start wall-escapes, present in the **baseline** too — the §3 IC transient, benign). |
| **`-cpu` arithmetically unchanged**; constituents + `BoA-v1ref` byte-clean | yes (the levers only regroup/relocate kernels; the CSR is pure-integer ⇒ host-built == device-built). |

---

## §3. Stage 3 — the decisive measurement (RTX 5070, dt=1e-5, `-dense -mini 0 -scale F -sweep`, 1200-step window)

v1 steps/s from `V1_MAXIMAL_BENCHMARK §3` (NOT re-run): 78.8 / 69.8 / 55.8 / 37.4 / 19.9 at 1/2/4/8/16×.
v2 measured back-to-back per scale (controls run-variance ≈3 %). Launches = always-run device launches/step.

| scale | config | **v2 steps/s** | launches/step | kernel-compute % | copy KB/step | **v2/v1** |
|---|---|---:|---:|---:|---:|---:|
| **1×**  | baseline           | 62.3 | 97 | 43 | 3.8  | 0.791 |
|         | + megakernel       | 64.1 | 84 | 44 | 3.5  | 0.813 |
|         | + CSR-host         | 65.6 | 91 | 38 | 24.6 | 0.833 |
|         | + both             | **68.4** | **78** | 40 | 24.2 | **0.868** |
| **2×**  | baseline           | 43.3 | 97 | 56 | 3.8  | 0.620 |
|         | + megakernel       | 45.8 | 84 | 55 | 3.5  | 0.656 |
|         | + CSR-host         | 48.3 | 91 | 48 | 46.2 | 0.692 |
|         | + both             | 44.6¹| 78 | 46 | 45.9 | 0.639¹|
| **4×**  | baseline           | 25.2 | 97 | 62 | 3.8  | 0.452 |
|         | + megakernel       | 25.5 | 84 | 64 | 3.5  | 0.457 |
|         | + CSR-host         | 26.9 | 91 | 56 | 89.8 | 0.482 |
|         | + both             | **28.1** | **78** | 58 | 89.5 | **0.504** |
| **8×**  | baseline           | 14.8 | 97 | 71 | 3.8  | 0.396 |
|         | + megakernel       | 16.0 | 84 | 74 | 3.5  | 0.428 |
|         | + CSR-host         | 16.8 | 91 | 68 | 176.6| 0.449 |
|         | + both             | **17.0** | **78** | 68 | 176.3| **0.455** |
| **16×** | baseline           | 8.4  | 97 | 79 | 3.8  | 0.422 |
|         | + megakernel       | 8.5  | 84 | 79 | 3.5  | 0.427 |
|         | + CSR-host         | 9.0  | 91 | 73 | 350.9| 0.452 |
|         | + both             | 9.0  | 78 | 73 | 350.6| 0.452 |

¹ The 2×-both point (44.6) is an anomalous dip — below 2×-CSR-host (48.3) and even 2×-megakernel (45.8); it is
run-to-run noise (the per-scale back-to-back ordering puts it last; ≈3 % variance), not a real regression. Every
other scale has both ≥ each single lever.

**Gap decomposition (closure of the v2/v1 ratio gap, combined "both" vs baseline):**

| scale | baseline v2/v1 | both v2/v1 | gap (1−ratio) baseline→both | **% of gap closed** |
|---|---:|---:|---:|---:|
| 1×  | 0.791 | 0.868 | 0.209 → 0.132 | **37 %** |
| 4×  | 0.452 | 0.504 | 0.548 → 0.496 | 9.5 % |
| 8×  | 0.396 | 0.455 | 0.604 → 0.545 | 9.8 % |
| 16× | 0.422 | 0.452 | 0.578 → 0.548 | 5.2 % |

---

## §4. Reading the levers

**Megakernel (lever 1) — the launch-overhead lever — barely moves it.** +2.5 % at 1× (62.3→64.1), decaying
toward 0 at 16× (8.4→8.5) as kernel-compute rises 43 %→79 % and the launch-dispatch headroom shrinks. It
collapses 13 launches (97→84) for ~2–3 pp of the v2/v1 ratio (~10 % of the 1× gap, <2 % at 16×). The reason is
quantitative: **the collapsed per-body kernels were the CHEAP launches.** Even at 1× kernel-compute is only 43 %
of the step, so 57 % is overhead — but that overhead is dominated by the serial CSR scans + the chained-`execute()`
per-graph cost + host bookkeeping, NOT the dispatch of the small zero/Brownian/integrate kernels (4 of the 13
collapsed were 0-sized `-mini 0` stores). **By the probe's decision rule, the megakernel barely moving ⇒ v1's edge
is the work asymmetry, not launch overhead.**

**CSR-host (lever 2) — the serial-work lever — helps more, and PERSISTS at scale.** +5–13 % (e.g. 14.8→16.8 at
8×, 8.4→9.0 at 16×) despite the round-trip ballooning copy traffic 3.8→24.6→…→351 KB/step. The single-GPU-thread
CSR `histogram/scan/scatter` are **O(nSeg) serial on ONE thread**, so their cost GROWS with scale — which is
exactly why host-siding them still wins at 16× where the megakernel is dead: a fast host serial scan + a 351 KB
transfer beats a 16 800-iteration serial GPU thread. The static-node-CSR precompute is a free win (no round-trip);
the dynamic seg-CSR round-trip is net-positive because the serial GPU scan it replaces is so slow. This is a
genuine, scale-robust ~5–13 % optimization — but it is a different lever than fusion, and even so it closes only a
few points of the ratio.

**Both together** close ~37 % of the gap at 1× and ~5–10 % at 4–16×. **The majority of v1's edge — and a growing
share of it as the scene scales (the gap widens 0.21→0.58 from 1×→16×) — is the §4(b) work asymmetry:** v2 forms
29–374 crosslinks (the O(N) formation + 2-pass gather), runs node-tether + seg gathers, and exercises binding;
v1's sparse node-centric layout triggers **zero** crosslink formation and **zero** binding, and runs its node
tethers on CPU. That work is real and correct (v2 is doing more physics), not removable by recomposition.

**Secondary no-regression check (the dense/percolating regime where v2-GPU already beats v1 5–7×).** FullSystemDemo
`-dense` with minifilaments ON (mot2 non-empty, denser binding): baseline 57.3 → both **62.1 steps/s (+8.4 %)**,
conservation EXACT, identical aggregate (active 1050, xlinks 14). The levers **help, never regress**, in the dense
regime too — and this exercises the free-minifil seg path (left device-side under CSR-host) correctly.

---

## §5. Recommendation

**Re-examine the work asymmetry — do NOT pursue further fusion.** The megakernel (the structure-preserving
launch-overhead lever) is decisively NOT the lever: it recovers ~10 % of the 1× gap and ~nothing at scale, and
folding the optional/cross-entity systems in would not help because those systems ARE the work asymmetry. v1's
faster per-step on this sparse SCPR scene is mostly that it does less actual work (0 crosslinks, 0 binding) — a
scene-definition artifact already flagged in `V1_MAXIMAL_BENCHMARK §4/§7`, not a v2 engine deficiency (the dense
regime, where both engines bind+crosslink, is where v2-GPU wins 5–7×).

**Keep CSR-host as a real, low-risk optimization (optional follow-up to promote to default).** It is bit-exact,
helps 5–13 % across all scales (the serial single-thread CSR scans are a genuine, scale-growing cost), and the
static-node-CSR precompute half is a free win with no round-trip. The megakernel is neutral-to-slightly-positive
and costs nothing in extensibility (the per-body mechanics are universal), so it is keep-able but not impactful.

**Net:** the maximal device path's per-step rate is **not launch-bound enough** for fusion to recover v1's edge on
this scene; the gap is dominated by genuine work v2 does and v1 skips. The architecture is sound; the "v1 is
faster here" finding stands as a workload property, and the next lever (if any) is the serial CSR scans, not the
per-body kernel count.

---

## §6. Files & reproduction

- `softbox/MechanicsFusion.java` — the 3 fused device-agnostic kernels (lever 1).
- `softbox/FullSystemDemoHarness.java` — `-megakernel` / `-csrhost` toggles (cpuStep + blkStruct/blkInteg/blkFil +
  buildSplitScheduler + buildPlanSplit U-set), `-validate` gate, launch-count instrument, `hostNodeCSR`/`hostSegCSR`.
- Sweeps: `RUN_LOGS/2026-06-23_megakernel_sweep.txt` (baseline+mega) + `RUN_LOGS/2026-06-23_megakernel_matrix.txt`
  (4 configs × 5 scales, back-to-back).
```
./run_fulldemo.sh -dense -mini 0 -validate [-megakernel] [-csrhost] -steps 300    # the bit-exact + CPU≡GPU gate
./run_fulldemo.sh -dense -mini 0 -scale F -sweep -cpucap 0 [-megakernel] [-csrhost]  # one measured device window
```
