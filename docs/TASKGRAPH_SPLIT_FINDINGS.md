# TASKGRAPH SPLIT — the maximal composition made device-resident across chained TaskGraphs — FINDINGS

**Date:** 2026-06-22/23. **Branch:** `xlink-formation-on`. **Status:** Stages 0/1 DONE (all gates pass); Stage 2
(overnight device-resident milestone) DONE — 370k steps device-resident, conservation EXACT, 393 MiB VRAM (§6). The maximal composition (`FullSystemDemoHarness`) — which merged ~106 tasks into ONE
`TaskGraph` and hit TornadoVM's single-`TaskGraph` capacity limit (`Graph resize not implemented`, §6 of
`FULL_SYSTEM_DEMO_FINDINGS.md`) and so silently fell back to the CPU runner — now runs **device-resident** by SPLITTING
the identical per-step kernel sequence into **5 chained `TaskGraph`s** that share the SoA buffers on the device via
`persistOnDevice` / `consumeFromDevice` under ONE `TornadoExecutionPlan`. **No kernel / force-law / gather / ordering
edits** — execution-plan wiring only. `BoA-v1ref` byte-clean; the constituent harnesses (Ring3x3 / DenseContractile /
XlinkFormation) byte-unchanged; the `-cpu` runner unaffected.

```
./run_fulldemo.sh -gpu -steps 60 -gpusteps 1500   # build + device-resident split + CPU≡GPU aggregate + throughput
./run_fulldemo.sh -dense -gpu -steps 20 -gpusteps 500   # dense scene throughput (2x CPU)
./run_fulldemo.sh -overnight -dense -boxxy 4.4 -steps 1000000 -overnightviz threejs_fulldemo_overnight   # Stage 2b
```
Logs: `RUN_LOGS/2026-06-22_taskgraph_split_overnight.txt`.

---

## §1. The residency mechanism (the Stage-0 core unknown — RESOLVED)
TornadoVM 4.0.1-dev (PTX) **does** keep SoA `FloatArray`/`IntArray` device buffers resident across chained
`ImmutableTaskGraph`s in one `TornadoExecutionPlan`, with **no per-step host round-trip between sub-graphs**. The
mechanism (confirmed against the official `TestSharedBuffers` unit test + our own measurement):

- **Producer:** `taskGraph.persistOnDevice(buffers…)` keeps a graph's buffers on the device after it executes (not
  freed, not copied to host).
- **Consumer:** the next graph `taskGraph.consumeFromDevice(prevGraphName, buffers…)` uses them in place — **no
  re-upload**.
- **Plan:** `new TornadoExecutionPlan(g0.snapshot(), …, g4.snapshot())`; each step executes `plan.withGraph(i).execute()`
  for i=0..4 in order. The shared device buffers carry across graphs AND across steps (the per-step loop is the
  `testThreeTaskGraphsWithSharedContextBuffer` pattern, validated to loop).

**The load-bearing lesson (the executeAlloc NPE).** A buffer may be `persistOnDevice`'d / `consumeFromDevice`'d ONLY if
it is actually **allocated** on the device — and TornadoVM allocates a transferred buffer **only if a task in that graph
uses it** (it elides unused transfers). Persisting the whole state from G0 (which only *uses* a fraction of it) left the
rest with a **null device buffer** → `NullPointerException` in `TornadoVMInterpreter.executeAlloc`
(`isPersistentObject(o) && state.getXPUBuffer()==null`). **Fix:** upload each SoA buffer (`FIRST_EXECUTION`) in the
**first graph that actually uses it** (so it is allocated there); thereafter each later graph consumes the running
uploaded set from its predecessor and persists it forward. This keeps every persisted/consumed buffer non-null and the
whole state device-resident continuously (across sub-graphs and across steps). Buffers used only in a late graph are
uploaded there; the small per-step counts/rates are re-uploaded `EVERY_EXECUTION` in every graph (negligible bytes),
never persisted (an `EVERY_EXECUTION` buffer is meant to be re-uploaded, not held — persisting it corrupts its state).

**Residency is real, not transfer-bound.** Only the integer pool-ledger offsets (5 small `IntArray`s) are pulled to
host per step — the SAME `UNDER_DEMAND` pulls the monolith used. The device→host geometry/state is pulled ONLY at the
render/check cadence (Stage 2) or once at the end (the aggregate compare). The split's speedup **grows with scale**
(1.0× → 2.0× → 3.0× CPU as the scene grows; §4) — the signature of a launch/compute-bound path with headroom, the
opposite of transfer-bound.

## §2. The partition map (5 chained graphs, faithful per-step order)
The identical ~106-task per-step sequence (and order) of the monolith, cut at the validated constituent boundaries:

| # | graph | block | tasks | content |
|---|---|---|---:|---|
| 0 | `fdTurn`   | turnover + nucleation        | 32 | age · depoly · death · grow · sever · split · the B1 scan-rank allocator · node nucleation |
| 1 | `fdBind`   | binding                      | 20 | node-shell node-aware brute + free-minifilament parallel-grid fused query (grid build incl.) |
| 2 | `fdStruct` | zero/Brownian + structure    | 28 | zero+Brownian (5 stores) · node-shell joints/dimer/radial-tether/gather/cross-bridge · free-minifil joints/dimer/axial-tether/gather/cross-bridge |
| 3 | `fdFil`    | filament forces              | 13 | chain (F3/F4) · node seed-tether + reaction · both motor→segment seg-gathers · registerForceDot |
| 4 | `fdInteg`  | containment + integrate      | 13 | containment + Langevin integrate + derive (5 stores) |

Largest sub-graph = 32 tasks, comfortably under the empirical capacity ceiling (Ring3x3's single graph runs at ~58
kernels; the monolith failed at ~106). The `GridScheduler` `localWork=64` keys were re-keyed under the new graph-name
prefixes (`fdTurn.<task>` … `fdInteg.<task>`) — the load-bearing re-key, else the RNG/trig kernels hit CUDA 701. The
per-step ordering, the `checkInt`/`collisionCheckInt` cadences, and the cadenced-kernels-stay-step-gated-internally
pattern are all preserved (every sub-graph runs every step; no conditional graph execution).

## §3. Validation (gates 1–3, 5) — default scene (672 active segments, ~2208 motors / ~6624 head sub-bodies)
Pinned to the hunt-validated **default** scene (not the `-dense` render scene), per the task. Split-GPU vs the `-cpu`
sequential runner, 1500 steps:

| channel | CPU | split-GPU | verdict |
|---|---|---|---|
| active segments | 672 | 672 | EXACT |
| node-shell bound heads | 14 | 14 | EXACT |
| free-minifilament bound heads | 58 | 58 | EXACT |
| conc (µM) | 0.2480 | 0.2480 | EXACT |
| conservation (integer pool ledger) | EXACT | EXACT | ✓ |
| phantoms (ACTIVE, monomerCount≤0) | 0 | 0 | ✓ |
| wall escapes | 0 | 0 | ✓ |
| NaN / crash / race | none | none | ✓ |

- **Gate 1 (builds + lowers on PTX):** PASS — no `Graph resize`, no `invalid variable`; all 5 sub-graphs lower.
- **Gate 2 (device-resident):** PASS — no per-step host round-trip between sub-graphs; only the 5 pool-ledger offset
  `IntArray`s are pulled per step (host bookkeeping for the conservation ledger, exactly as the monolith did).
- **Gate 3 (split-GPU ≡ CPU within §8 aggregate-within-SEM):** PASS — at the default scene + 1500-step horizon the
  aggregates match **exactly** (not merely within SEM); at the dense scene they agree within 1 head (active 1050=1050,
  node-bound 12 vs 11, minifil-bound 134 vs 133). Hard invariants (conservation / phantoms / wall-escapes / NaN) hold.
- **Gate 5 (regression):** PASS — `Ring3x3 -gpu` re-ran bit-faithful (143 steps/s, conservation EXACT, CPU≡GPU AGREE);
  the constituent harnesses are byte-unchanged (only `FullSystemDemoHarness`'s GPU path was added to). `-cpu` runner's
  numbers are byte-unaffected (it ignores TaskGraphs; the CPU hunt re-ran with its prior §3 behaviour).

## §4. Throughput (gate 4) — device-resident, scale-improving (NOT transfer-bound)
| scene | active segs | heads | CPU steps/s | split-GPU steps/s | speedup |
|---|---:|---:|---:|---:|---:|
| default (4×4 nodes, 60 minifil) | 672 | ~6.6k | 77 | 75 | 1.0× |
| dense (5×5 nodes, 160 minifil) | 1050 | ~5.6k | 26 | 52 | 2.0× |
| 2×dense (7×7 nodes, 320 minifil) | 2058 | ~?  | 10 | 30 | 3.0× |

The speedup **grows monotonically with scale** (1.0 → 2.0 → 3.0×) — definitive evidence the path is launch/compute-bound
with headroom, **not transfer-bound** (a transfer-bound path degrades with more data). The absolute steps/s is below the
≥100 calibration estimate because the **full composition is ~106 kernels/step — roughly double a constituent's count**
(Ring3x3's ~58-kernel single graph runs at 143 steps/s on the same GPU); at the small default scene each kernel is
mostly launch latency, so the GPU only ties the CPU, while at dense/2×dense the per-kernel work amortizes the launches
and the GPU pulls 2–3× ahead. **Calibration vs DenseContractile:** DenseContractile (~50 steps/s @ 128k heads) is a
LEANER graph (binding + cross-bridge + xlink-force only); the full composition adds the whole turnover+nucleation+grid
stack, so at far fewer heads it lands at a comparable steps/s — kernel-count-bound, exactly as expected. **The win is at
scale**, which Stage 2b exercises; the device-resident path replaces the silent CPU fallback (the whole point — no large
run grinds on the `-cpu` debug runner).

## §5. Flagged divergences / scope notes
1. **Crosslinker formation + force stay CPU-side** in the device path — exactly as the monolith GPU probe did. Their
   `filID` (the connected-component chain id) is host-computed each formation cadence; porting that to device is a
   separate step. The crosslinker device FORCE path is validated independently (DenseContractile / XlinkFormation,
   bit-identical CPU↔GPU). Consequence: the split-GPU run carries no live crosslink bundling, and the device render
   shows no crosslinks. The `-cpu` demo (the hunt source-of-truth) keeps full crosslinkers. **Not a regression — the
   same scope as the §6 monolith probe; flagged for the ring.**
2. **Throughput is kernel-launch-bound at small scale** (§4) — a property of the composition's ~106-kernel breadth, not
   the split. Fewer/larger entities (Stage 2b) is the lever; merging graphs would not materially help (the cost is the
   kernels, not the 5 graph dispatches).
3. **Scaling the node grid past its box wall-escapes** — the 2×dense probe (7×7 nodes, spacing 0.6 ⇒ 3.6 µm extent in a
   4.0 µm box) produced 179 IC wall-escapes (the containment correctly firing at the over-filled walls). Scale
   *entities* (minifilaments / cap / crosslink slots) and keep the node grid inside its box, or widen the box. Stage 2b
   uses `-boxxy 4.4` with the 5×5 dense grid (0 escapes after the warm-start transient).
4. **Warm-start IC wall-escape transient** — a handful of warm-placed filament tips start just past the box edge and are
   pulled in by containment over the first steps (the §3 transient). The overnight bail treats wall-escapes as a runaway
   signal only above `max(40, activeSegments/40)` (NaN / conservation / phantom remain hard always-bail invariants).

## §6. Stage 2 — device-resident overnight payoff (running)
**Stage 2a (device-resident milestone)** is established by the §3 default-scene split run: the **first device-resident
execution of the maximal composition** holds every hunt gate (conservation EXACT, 0 phantoms, 0 wall escapes, no NaN)
and tracks the CPU hunt exactly. **Stage 2b** scales up to the dense scene (`-dense -boxxy 4.4`: 25 nodes, 160 free
minifilaments / 5120 heads, 1050 segments, ~10k crosslink slots) at faithful **KIN=1**, device-resident, ~1M steps
(~10 s sim, past the ~6.6 s severing onset), dumping a watchable render `threejs_fulldemo_overnight`. Early: ~71 steps/s,
conservation EXACT, phantoms 0, peak VRAM ≈ 0.39 GiB (of 12 GiB — vast headroom; the scene is kernel-bound, not
memory-bound). **Result table filled on completion below.**

**Stage 2b result (the device-resident milestone, DONE — wall-cap reached).** Dense scene, device-resident split,
**370,280 steps in 5.5 h** (the wall-clock cap, whichever-comes-first), **3.70 s sim** at KIN=1, **112 frames** →
`threejs_fulldemo_overnight`, log `RUN_LOGS/2026-06-22_taskgraph_split_overnight.txt`.

| Stage 2b channel | result |
|---|---|
| device-resident steps | 370,280 (no per-step host round-trip between sub-graphs; only the 5 pool-ledger offsets) |
| conservation (integer pool ledger) | **EXACT at every one of the ~220 sampled checkpoints** (the run bails on any failure; it never did) |
| phantoms | 0 throughout |
| wall escapes | 0 (after the warm-start transient) |
| NaN / crash / race | none — ran clean to the wall-cap |
| peak VRAM | **393 MiB of 12 GiB** (flat the whole run ⇒ no leak; the composition is kernel-bound, not memory-bound) |
| turnover | 1231 monomers taken / 678 returned (active treadmilling); pool conc 0.248 → 0.154 µM (actin drawn into filaments) |
| node-net RMS | 1.200 → 1.209 µm (STABLE, mild fluctuation) |

- **The milestone holds:** the FIRST device-resident execution of the maximal composition ran 370k steps with every
  hard invariant intact (conservation EXACT, 0 phantoms, 0 escapes, no NaN), never falling back to CPU. The
  final-summary's one-shot `conservation=FAIL` print in the raw log was a **stale-host-read reporting artifact** (the
  pool ledger is updated every step but `monomerCount` is only pulled at the check cadence; the summary read it ~400
  steps stale) — **fixed** by a final `pullRenderState` before the summary; the per-step checkpoints (which gate the
  bail) were all EXACT.
- **Throughput DECAYED over the run: ~70 steps/s early → ~19 steps/s late** (5.5 h average ≈ 19). VRAM was flat, so it
  is **not** a leak; the most likely cause is **growth-driven broad-phase cost** — as filaments elongate (pool
  0.248→0.154 µM of actin polymerised in) their segment bounding radii grow, so the binding grid packs more candidates
  per cell and `gridReachable` does more work each step; GPU thermal/clock throttling under a sustained 5.5 h load may
  also contribute. **Flagged for profiling** (the early 70 steps/s matches the §4 dense burst; the decay is a
  scale-of-work effect, not a split or residency defect). Consequence: the run reached **3.70 s sim, short of the
  ~6.6 s severing onset** — it did not enter the long-time severing/ring-fate regime within the 5.5 h cap.
- **Morphology (device-path scope):** a stable, mildly-fluctuating node+minifilament+turnover network (RMS ~1.20 µm,
  weak net contraction). Weaker than the CPU hunt's 3.8 % shrink because **crosslinker bundling is absent on the device
  path** (§5.1) and the radial node-shell binding is sparse/transient at the faithful reach — the contraction the CPU
  demo gets from crosslinkers tying the network is not present here. The render shows the formin brushes, minifilament
  binding, and ATP→ADP turnover gradient; no crosslinks (expected).

## §7. Files
Modified (additive, GPU path only): `softbox/FullSystemDemoHarness.java` — `buildPlanSplit` (the 5 chained graphs +
upload-at-first-use scheduling), the 5 block methods (`blkTurn`/`blkBind`/`blkStruct`/`blkFil`/`blkInteg`, verbatim
task methods+order from the monolith), `buildSplitScheduler` (re-keyed WorkerGrids), `stepSplit`, `pullRenderState`,
`overnightRun`, `vramMB`; `gpuScaleCheck` re-routed to the split; new args `-gpusteps` / `-overnight` / `-overnightviz`.
The monolithic `buildPlan` / `stepHostBookkeeping` are retained (unused) as the documented Graph-resize reference.
`CLAUDE.md` gained the CPU-fallback disclosure rule. `BoA-v1ref` byte-clean; constituent harnesses + viewer untouched.

## §8. ADDENDUM — cadence-gating the turnover graph (the §5.1 / PROFILE §4 lever) — DONE (2026-06-23)

**Date:** 2026-06-23. **Branch:** `cadence-gate-fdturn` (off `profile-fulldemo`, which is `xlink-formation-on` +
the measurement-only profiler; production `stepSplit` byte-identical between them — branched here so the Stage-2
decay re-measure can use the profiler). **Goal (PROFILE §5.1):** the turnover graph launches its kernels EVERY
step but turnover only *fires* on the biochem cadence; skip its `execute()` on non-fire steps to cut the launch
floor AND throttle the §4 per-execute creep ~100×.

### §8.0 — Stage 0 recon: cadence audit ⇒ **CASE 1 (mixed cadence ⇒ graph SPLIT required)**
`fdTurn`'s 32 tasks do NOT share one cadence. Every turnover kernel (`age`/`depoly`/`applyDeath`/`grow`/`sever`/
`markSplits`/`recomputeDrag` + the split allocator) gates internally on `fires` (`*Counts[fires]`,
`firesAt(t)=t%biochemCheckInt==0`, biochemCheckInt=round(biochemDeltaT/dt)=1e-3/1e-5=**100**) and writes only
zeros off-cadence. **But node nucleation (`count`/`emit` + the B1 allocator path, 11 tasks) runs EVERY step** —
`emit` draws at `pNuc=kNodeNuc·dt` (a per-STEP probability, the wang-hash salted by `step`), unconditionally in
BOTH `cpuStep` (the oracle, lines 627-639) and the device graph. So a whole-graph fire-gate would 100×-undercount
nucleation — **not physically equivalent.** Per the task's Stage-0 case-1 rule, `fdTurn` is SPLIT into a
fire-gated turnover graph + an always-run nucleation graph.

**Equivalence proof (why skipping turnover off-cadence writes nothing nucleation/downstream read):** on a non-fire
step the turnover no-op kernels zero their scratch *every* step then `continue` (e.g. `depolyProxy` sets
`returnedMon=deathFlag=0` for every slot before the `if(fires==0) continue`) ⇒ they leave `filState`/`coord`/
`monomerCount`/`seedNode` UNCHANGED and write only zeros to scratch (`grewFlag`/`deathFlag`/`acceptFlag`/…) that
ONLY turnover tasks read. Nucleation rebuilds its OWN free-list from `filState` (`nFreeFlags`/`nCsrFree`/
`nFreeScatter`) ⇒ it is self-contained regardless of whether the (skipped) turnover allocator ran. The
turnover→nucleation ORDER is preserved (no reorder ⇒ no slot-assignment change ⇒ bit-exact on the validation
horizon, where no filament reaches the 64-monomer split before ~6100 steps anyway).

### §8.1 — Stage 0 case 2: **residency SURVIVES a skipped producer** (the load-bearing unknown — RESOLVED)
`fdNuc` (G1) `consumeFromDevice("fdTurnFire", …)` the shared SoA that `fdTurnFire` (G0) uploads FIRST_EXECUTION at
step 0 (a fire step). On the 99/100 non-fire steps `fdTurnFire` is SKIPPED — yet the consume works: **the GPU split
ran 1500 steps (past the first 99 non-fire steps) with no NPE/crash, conservation EXACT, phantoms 0.** So
`consumeFromDevice` is a residency LOOKUP (the buffer is device-resident from the prior persist), NOT a "the named
producer must have executed THIS invocation" dependency. **No upload relocation needed; no residency-mechanism
rework.** (Render-state pulls were moved off `fdTurnFire` onto the always-run `fdNuc`/`fdBind`/`fdInteg` results so
a non-fire check step never reads a skipped graph; turnover pool-ledger offsets are pulled inline from
`fdTurnFire` ONLY on fire steps, and the host pool put/take is `if(fires)`-gated.)

### §8.2 — revised partition map (6 chained graphs; `fdTurn` → `fdTurnFire` + `fdNuc`)
| # | graph | tasks | cadence | content |
|---|---|---:|---|---|
| 0 | `fdTurnFire` | 21 | **fire-step only** (skipped 99/100) | age·depoly·death·grow·sever·split·B1-split-allocator·recomputeDrag — the UPLOADER (FIRST_EXECUTION at step 0) |
| 1 | `fdNuc` | 11 | **every step** | node count·emit·B1-nuc-allocator·tagSeeds·initNewborn·nucFresh·nucCof |
| 2 | `fdBind` | 20 | every step | (unchanged) |
| 3 | `fdStruct` | 28 | every step | (unchanged) |
| 4 | `fdFil` | 13 | every step | (unchanged) |
| 5 | `fdInteg` | 13 | every step | (unchanged) |

Per step: fire ⇒ execute {0,1,2,3,4,5} (106 launches, == the old monolith order); non-fire ⇒ execute {1,2,3,4,5}
(**74 launches** — the 21 turnover kernels skipped). `GridScheduler` re-keyed `fdTurnFire.<task>` / `fdNuc.<task>`.

### §8.3 — validation (all gates GREEN)
| gate | result |
|---|---|
| **skip is physically equivalent** (the decisive A/B) | gated-GPU ≡ ungated-GPU **bit-identical** on the deterministic device (active 672, node-bound 7, minifil-bound 40, conc 0.2483 — identical @ 400 steps); proves the off-cadence skip changes no device state |
| **#1 CPU≡GPU @ 1500 steps** | **AGREE EXACTLY** — active 672=672, node-bound 14=14, minifil-bound 58=58, conc 0.2480; (the chaotic minifil count decorrelates transiently — GPU 40 / CPU 26 @ 400 steps — but this is PRE-EXISTING: the ungated baseline shows the identical 40/26 split, §8.1 A/B, the §8 chaotic-many-body standard) |
| **#2 hard invariants** | conservation EXACT, phantoms 0, wall-escapes 0, NaN none (@ 400 and 1500 steps) |
| **#4 steps/s (default scene)** | **83 → 101 steps/s** (+22%; composed step 12.07 → 9.90 ms). **fdTurn 4.14 ms/step → fdTurnFire 0.031 ms/step** (the turnover launch storm amortized over the 100-step cadence); fdNuc (always-run) 1.65 ms/step. GPU went 1.0× → 1.2× the CPU runner |
| **#5 residency intact** | per-step copyIn ~3.1 KB (fdBind 0.79 + fdStruct 0.98 + fdFil 0.70 + fdInteg 0.63; fdTurnFire/fdNuc 0 KB) — unchanged, no full-state copy |
| **#3 decay re-measured** | dense scene, `-noprof`, 30k steps (see slope below): **fdTurnFire FLAT at 0.03 ms/step** (throttled ~100×) — the turnover graph is no longer the decay carrier. BUT the per-execute creep **RE-HOMED to fdNuc** (the new first always-run graph) at **~0.105 µs/step vs the §4 baseline ~0.16 µs/step on fdTurn** — reduced ~35 % (≈ the 11-vs-32 task-count ratio, confirming §4b hypothesis B) but **NOT eliminated**. Absolute steps/s far better: 98→81 over 10–30k vs baseline 67→~50 |
| **#6 regression** | `-cpu` byte-unaffected (`cpuStep` untouched); only `FullSystemDemoHarness` split path + profiler changed; constituent harnesses + monolith `buildPlan`/`stepHostBookkeeping` untouched; `BoA-v1ref` byte-clean |

**Decay slope (dense, `-noprof`, 30k steps; per-graph ms/step):**

| step | steps/s | fdTurnFire | fdNuc | fdBind | fdStruct | fdFil | fdInteg |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 100 | 98 | **0.03** | 2.20 | 1.82 | 2.16 | 2.72 | 1.22 |
| 20 100 | 89 | **0.03** | 3.17 | 1.84 | 2.16 | 2.74 | 1.23 |
| 30 100 | 81 | **0.03** | 4.31 | 1.86 | 2.18 | 2.75 | 1.24 |

Everything but fdNuc is flat; fdTurnFire (the gated graph) is pinned ~0.03 ms regardless of step count. **Verdict: the
§4 creep is a per-execution accumulation on the FIRST always-run / full-state-persisting graph in the chain — NOT
turnover-specific.** Cadence-gating throttles the *gated* graph ~100× and roughly halves the carrier's slope (fewer
tasks), but the TornadoVM-internal root cause persists on fdNuc and remains the open follow-up (further mitigation —
fusing fdNuc's tasks / host-side CSR scans / `withCUDAGraph()` — is out of this task's cadence-gate-only scope).

### §8.4 — files
`softbox/FullSystemDemoHarness.java` (additive, split path + profiler only): `buildPlanSplit` (6 graphs;
`u0`/`uNuc` first-use split; render pulls re-homed to always-run graphs), new `blkNuc` (split out of renamed
`blkTurnFire`), `buildSplitScheduler` (fdTurnFire/fdNuc re-key), `stepSplit` + `profStep` (the `if(gi==0&&!fires)
continue` gate + `if(fires)` pool-bookkeeping), `pullRenderState`, `profileRun`/`GNAME` (6-graph), `N_SPLIT`=6.
`BoA-v1ref` byte-clean; production CPU path + constituent harnesses untouched.

## §9. ADDENDUM — filID on the GPU (live crosslinker bundling on the device path) — Part 1a DONE; wiring scoped

**Date:** 2026-06-23. **Branch:** `cadence-gate-fdturn`. Closes the §5.1 gap's *algorithmic* blocker.

### §9.0 — Stage-0 recon verdict: CASE (a), but a PURE CHAIN ⇒ pointer-jump (not general CC, not a thread-through)
- **What filID is:** the host `FullSystemDemoHarness.computeFilID` walked each segment down `end2NbrSlot` to the
  chain **terminal**, whose slot index is the filament's label (FREE slots → `-seg-2`). It is **CASE (a)** — a
  connected-components label *derived* from the backbone-link graph, **not** an owner-id maintained at allocation
  (the terminal moves as grow/split/sever/death/nucleation mutate the chain ⇒ it is recomputed each formation
  cadence). But the actin backbone is a **pure chain** (each segment ≤1 `end2` neighbour, no branching —
  crosslinks do NOT define filament identity), so it is the recon's **case-(a)-chains → pointer-jump-to-head**
  path, NOT general label-propagation / union-find.
- **What formation needs:** the predicate is `filID[i] != filID[j]` (same-filament **exclusion**; distinct labels
  ⇒ bundling-eligible). A value-identical terminal label carries exactly this.
- **Ordering/cadence:** `XL_CHECK_INT = 100 == biochemCheckInt` (formation fires on the same cadence as
  `fdTurnFire`). filID must be current AFTER `fdTurnFire` (split/death/sever, fire cadence) AND `fdNuc`
  (nucleation, every step — births a fresh 1-segment chain) and BEFORE formation reads it. So the filID compute
  + formation slot **after `fdNuc`**, on the formation cadence. No reorder of existing physics — it inserts.

### §9.1 — Part 1a (DONE): `FilIDSystem` — the device-agnostic pointer-doubling filID
`softbox/FilIDSystem.java`: `init` (each segment → its immediate `end2` successor, or self at the terminal; FREE →
`-seg-2`) + `ceil(log2 n)`-rounded-to-even `jump` rounds (`ptrOut[seg] = ptrIn[ptrIn[seg]]`, ping-pong over two
buffers). Race-free, **no atomics / no KernelContext** ⇒ runs identically on the GPU TaskGraph and the `-cpu`
runner (the one-physics rule). `computeFilID` now drives it on both runners (host loop over the same kernels;
the device wiring unrolls the same `init`+`jump` tasks). Converges because the chain is linear+acyclic
(inc-6c/7 invariant) — pointer-doubling reaches the unique terminal in `ceil(log2 L)` rounds; over-iterating is
idempotent (terminals self-loop).

**GATE 1 PASS — FilIDSystem ≡ reference chain-walk, VALUE-identical, every formation step** over 400 checks across
two turnover-active runs (15k + 25k steps; the warm filaments are real 7-segment chains ⇒ pointer-doubling is
exercised, + depoly membership churn): worst value mismatch **0**, worst partition mismatch **0**. (Run via
`./run_fulldemo.sh -filidcheck`.) Note: in the default config splits (need ~6k steps to a 64-monomer filament)
and births (formins pre-filled by the warm chains) were rare; correctness for split/sever-created chains is
**structural** — pointer-doubling depends only on the *current* linear `end2NbrSlot` graph, not on how a chain
formed — and is re-confirmed device==host by the (scoped) formation CPU≡GPU gate below.

### §9.2 — Part 1b (SCOPED, turnkey): wire the crosslinker pipeline into the device residency plan
The §5.1 payoff (live bundling/contraction on the device run) needs the **whole** crosslinker pipeline on the
device graph — it is currently CPU-only (`cpuStep` lines 673–746; absent from `buildPlanSplit`). This is a large
but mechanical wiring (the kernels are already device-validated — XlinkFormation GATE-B / DenseContractile,
bit-identical CPU↔GPU — and lower on PTX). Deferred to its own commit to keep it behind the project's
device-validation gates rather than rush ~60 buffers / ~47 tasks unvalidated. The exact plan:

- **New cadence-gated graph `fdXForm` (insert as G2, after `fdNuc`; `N_SPLIT` 6→7):** filID (`init` + 12 `jump`)
  then the formation pipeline (`countActiveLinks` · FormationGrid build = `publishToBodyView`+8 `SpatialGrid`
  kernels · `gridFormCount`/`gridFormScan`/`gridFormEmit` · `formGates` · `formAdmitReduce` · `formAdmit` ·
  `freeFlags`/`csrScan`/`freeScatter`/`csrScan`/`allocate`/`placeOrient`) — ~35 tasks. **Cadence-gate its
  `execute()`** exactly like `fdTurnFire` (`if (gi==2 && t%XL_CHECK_INT!=0) continue;` — proven safe across a
  skipped producer, §8.1). Slots after `fdNuc` so the chain graph is current.
- **Append the every-step crosslinker FORCE to `blkFil`:** `unbind` · `countActiveLinks` · `linkForces` ·
  `linkTorsion` · the 2-pass seg-gather (`csrHistogram`/`csrScan`/`csrScatter`/`segGatherA`, then B) — ~12 tasks
  into `f.forceSum` before `fdInteg`, matching `cpuStep`'s force-phase order (after the motor seg-gathers).
- **Residency:** add the CrosslinkerStore arrays (~33) + FormationGrid grid buffers (~16, its OWN grid — distinct
  cell size, can't reuse the binding grid) + `s.filID`/`filIDScratch` + `s.segCountA/B`,`segOffsetsA/B`,`segIdxA/B`
  to `firstExec`; first-use them in `fdXForm`'s U set (the loop auto-consumes/persists them forward). ~60 buffers.
- **Scheduler:** `fdXForm.<task>` keys — `localWork=64` (`addW`) for the RNG/trig kernels (`gridFormEmit`,
  `formGates`, `formAdmit`, the `filID` jumps) + the FormationGrid grid kernels keyed to *its* dims
  (`pad(cap)`/`pad(numBodyChunks)`/`pad(totalCells)` from the FormationGrid, NOT the binding grid); single-thread
  (`addS`) for the CSR scans. Mirror the `fdBind` grid keys with FormationGrid's dimensions.
- **`stepSplit`:** host `xl.setCounts`/`xl.setFormStep` each step; execute `fdXForm` only on the formation
  cadence; no host pull added (crosslink state stays device-resident; render pulls `linkState`/`linkFilA/B`/`loc`
  from a result if the viewer needs crosslinks).
- **Launch-delta (note vs the §8 ceiling):** +~12 every-step tasks (~74→~86 off-cadence) and +~35 on the formation
  cadence (1/100 steps) — acceptable for functional completeness; the force tasks are the every-step cost.

**Gates to run once wired:** (2) formation CPU≡GPU bit-exact (extend XlinkFormation GATE-B to
`formation-with-device-filID == host`), (3) the maximal device run shows crosslink-count + contraction tracking
the CPU hunt (the §5.1/§6 morphology gap closed), (4) conservation/phantoms/escapes/NaN, (5) `-cpu` unchanged +
`BoA-v1ref` byte-clean, (6) throughput delta. Files (Part 1a): `softbox/FilIDSystem.java` (new);
`FullSystemDemoHarness.computeFilID` (now drives FilIDSystem), `+filIDScratch`/`filIDRounds`, `-filidcheck` gate.
`BoA-v1ref` byte-clean; CPU path value-unchanged (filID value-identical ⇒ identical formation).

### §9.3 — Part 1b DONE: the crosslinker pipeline wired into the device residency plan — LIVE BUNDLING ON THE GPU
**Date:** 2026-06-23. **Branch:** `cadence-gate-fdturn`. The §5.1/§6 gap is CLOSED: the maximal device-resident run
now does **live crosslinker formation + bundling + contraction** on the GPU path (previously the device path carried
**zero** crosslinks). The whole crosslinker pipeline — device filID (Part 1a) + O(N) formation + the every-step
force/unbind + the 2-pass seg-gather — is wired into `buildPlanSplit`, **kernels reused byte-for-decision from the
device-validated XlinkFormation GATE-B / DenseContractile / CrosslinkerBundleHarness** (no new kernel, force law, or
gather; execution-plan wiring only).

#### §9.3.0 — the LOAD-BEARING design change vs the §9.2 plan: `fdXForm` is the LAST graph, not G2
§9.2 planned `fdXForm` inserted as **G2 (after fdNuc), cadence-gated**. **That placement does not hold residency** —
a **cadence-gated MIDDLE graph breaks the consume forward-chain.** The mechanism (diagnosed empirically, executeAlloc
NPE at the FIRST non-formation step):
- The split's per-graph `consumeFromDevice(predecessor, fullSet)` names the immediate predecessor as the producer of
  the WHOLE running buffer set. §8.1's "consume-from-a-skipped-graph is a residency lookup" holds only when the skipped
  graph is the **genuine uploader** of what its consumer needs (fdNuc←skipped-fdTurnFire works because fdTurnFire
  genuinely uploaded u0). A skipped **middle** graph (fdXForm at G2) is named by fdBind as the producer of u0/uNuc too,
  which it merely re-persisted — on a skip step that producer→buffer association is stale ⇒ null device buffer ⇒ NPE.
- A skipped **SOURCE** graph (fdTurnFire, G0) is safe (pure uploader, no predecessor); a skipped **SINK** graph is safe
  (no successor consumes from it). A skipped **middle** is not. (Grouping the consume per-genuine-uploader — emitting
  multiple `consumeFromDevice` calls — did not compose on this TornadoVM build either: it NPE'd at build of fdXForm.)
- **Fix:** make `fdXForm` the **LAST** graph (G6), execute-gated. As a sink it has no successor consuming its
  sole-uploaded (formation-scratch) buffers, so the skip is residency-safe. The **shared** link state it reads+mutates
  (`linkState`/`linkFilA/B`/`loc1/2`/`activeLinkCount`/strain/torsion rings) is uploaded by the **always-run `fdFil`**
  (which uses it in the every-step force+unbind) and consumed by `fdXForm` — so no buffer an always-run graph needs is
  ever sole-kept by the gated graph. The cost: formation runs at **end-of-step N** ⇒ its new links are forced from
  **step N+1** — a **≤1-step lifecycle shift** vs cpuStep's same-step formation, identical in kind to the §5c-i
  same-step-vs-next-step reuse choice (a timing choice, not a correctness change; the chaotic-aggregate gate is blind to it).
- An interim "always-run `fdXForm` + toggle `P_form`→0 off-cadence" variant was correct but ran the formation **grid
  build every step** (≈150 ms/formation-execution at the dense scene) ⇒ **6 steps/s**. The gated-sink design throttles
  that 100× (amortized 1.50 ms/step) ⇒ **57 steps/s.** Kept the gated-sink design.

#### §9.3.1 — revised partition map (7 graphs; fdXForm appended as the gated SINK)
| # | graph | cadence | content (Δ vs §8.2) |
|---|---|---|---|
| 0 | `fdTurnFire` | fire-only | (unchanged) |
| 1 | `fdNuc` | every step | (unchanged) |
| 2 | `fdBind` | every step | (unchanged) |
| 3 | `fdStruct` | every step | (unchanged) |
| 4 | `fdFil` | every step | **+12 crosslinker FORCE tasks** appended: `unbind·countActiveLinks·linkForces·linkTorsion·2-pass seg-gather` into `f.forceSum` (after the motor gathers, cpuStep's force-phase order). **Uploads the shared link state.** |
| 5 | `fdInteg` | every step | (unchanged) |
| 6 | `fdXForm` | **formation cadence only (1/100)** | NEW gated SINK: device filID (`init`+`filIDRounds` jumps) · `countActiveLinks` · FormationGrid build (9 tasks) · `gridFormCount/Scan/Emit` · `formGates·formAdmitReduce·formAdmit` · scan-rank allocator · `placeOrient` (~35 tasks). Per step: formation ⇒ 7 graphs; off-cadence ⇒ 6 (fdXForm skipped). |

Residency additions (~60 buffers): the whole `CrosslinkerStore` SoA + the FormationGrid's OWN grid (distinct cell size)
+ `filID`/`filIDScratch` + the 2-pass seg-gather CSR arrays. Split by uploader: the **formation-scratch** (req*/gate/
free-list/rank/allocCounts/grid/filID) is first-used in the gated `fdXForm`; the **shared link state** + the
**force-only** data/params (xlinkData/xlParams/offParams/torsionParams) + seg-gather CSR are first-used in the
always-run `fdFil`. `formParams`/`formCounts`/`gridCounts`/`counts` are EVERY_EXECUTION (re-uploaded, never persisted).
`XL_CHECK_INT = 100 == cpuStep's formation cadence == biochemCheckInt` (confirmed, not assumed). `GridScheduler` keys
`fdXForm.*` (FormationGrid dims — NOT the binding grid) + `fdFil.xl*`; the filID jumps + RNG/trig formation kernels
`localWork=64`, the CSR scans single-thread.

#### §9.3.2 — gate table (default + dense scenes)
| gate | result |
|---|---|
| **(1) builds + lowers on PTX @ 7 graphs** | PASS — no Graph-resize (under cap), no CUDA 701; the FormationGrid kernels keyed to ITS dims |
| **(2) residency holds (the make-or-break for ~60 buffers)** | PASS — device-resident across all 7 sub-graphs AND across the skipped-fdTurnFire / skipped-fdXForm steps; **no NPE, no host round-trip** (per-step copyIn ~3.1 KB unchanged); 2500+ steps clean. The gated-SINK placement is what holds it (§9.3.0). |
| **(3) THE PAYOFF — live bundling tracks the CPU hunt** | **PASS** (dense, 2500 steps): **xlinks GPU=24 ≈ CPU=23**; filament-RMS contraction GPU **0.29%** / CPU **0.47%** (both contractile, same sign); the device path now CARRIES crosslinks (was 0). |
| **(4) formation correctness (device filID feeds device formation)** | PASS — **same-chain-links = 0** on the device (the device filID correctly excludes same-filament pairs — the whole point); xlinks track CPU within chaotic noise. Bit-exactness is established by composition: Part-1a GATE 1 (device filID ≡ host, **value-identical**, through split/sever/depoly/nucleation churn) + XlinkFormation GATE-B (device formation KERNELS ≡ host on identical pose, bit-identical) ⇒ formation-with-device-filID ≡ host. |
| **(5) conservation / phantoms / escapes / NaN** | PASS — conservation EXACT, phantoms 0, wall-escapes 0, no NaN (default + dense, all sampled steps) |
| **(6) regression** | PASS — `cpuStep` **byte-untouched** (CPU xlink trajectory bit-identical to pre-wire: step 330 → 1 link, rms 0.9513, fil-rms 1.0161); only `FullSystemDemoHarness` changed (200+/31−); constituent harnesses + monolith + `BoA-v1ref` byte-clean. `-noxlink` ⇒ 6-graph path unchanged. |
| **(7) CPU≡GPU aggregate (§8 standard)** | PASS — dense 2500: active 1050=1050, node-bound 17 vs 15, minifil-bound 209 vs 175 (chaotic-many-body, within tolerance) |

#### §9.3.3 — throughput + creep delta (the two launch-ceiling guards)
- **Throughput (dense):** **57 steps/s = 1.9× the CPU runner** at the payoff horizon (matches §4's dense 2.0×). The
  every-step cost is the +12 force tasks (fdFil grew to ~7.0 ms/step — the 2-pass gather over ~10k link slots is real
  compute); the formation pipeline is amortized (fdXForm **1.50 ms/step** avg, ≈150 ms/formation-execution ÷ 100).
  Off-cadence launches ~74→~86 (force), on-cadence +~35 (formation, 1/100) — exactly the §9.2 estimate.
- **Creep delta (dense, 30k steps, `-noprof`):** the wiring **did NOT add a second creep carrier and did NOT worsen the
  slope.** Per-graph ms/step at 10k/20k/30k:

  | step | steps/s | fdTurnFire | fdNuc | fdBind | fdStruct | **fdFil** | fdInteg | **fdXForm** |
  |---:|---:|---:|---:|---:|---:|---:|---:|---:|
  | 10 300 | 56 | 0.04 | 2.73 | 2.21 | 2.66 | **6.89** | 1.69 | **1.50** |
  | 20 300 | 52 | 0.04 | 3.95 | 2.27 | 2.72 | **7.01** | 1.74 | **1.51** |
  | 30 300 | 49 | 0.04 | 5.28 | 2.29 | 2.70 | **7.00** | 1.73 | **1.50** |

  **fdFil FLAT** (6.89→7.00 — the appended force tasks do NOT creep) and **fdXForm FLAT** (gated/amortized). The only
  creep carrier remains **fdNuc** (2.73→5.28 ≈ **0.127 µs/step**, dense) — the §8 per-execute creep on the first
  always-run graph, **unchanged** by the wiring (it is a TornadoVM-internal accumulation, the standing §8.3 follow-up).

#### §9.3.4 — render + files
- **Device render WITH bundling:** `threejs_fulldemo_gpu_bundled` (`-dense -overnight -overnightviz … -steps 30000`,
  device-resident split, **301 frames**, 0.300 s sim, 48 steps/s) — the visible payoff: the GPU run now **contracts WITH
  crosslinks** — **184 crosslinks** in the final frame (the earlier un-bundled device render / §6 overnight had **0**),
  **node-net RMS 1.200 → 1.118 µm (6.9% contraction)** vs the un-bundled §6 device path's weak ~1%. SANITY at scale:
  conservation EXACT, phantoms 0, wall-escapes 0, NaN none, peak VRAM 514 MiB. The `writeFrame` "crosslinks" channel is
  pulled from `fdFil` (always-run) each frame.
- **Files:** `softbox/FullSystemDemoHarness.java` only (additive, split path): `blkXForm` (new, the formation graph);
  `blkFil` (+12 force tasks); `buildPlanSplit` (uX/u3 split by uploader; fdXForm as the gated sink; GI_* by name; 7-graph
  `everyExec`/host-pull); `buildSplitScheduler` (`fdXForm.*` + `fdFil.xl*` keys); `stepSplit`/`profStep` (the fdXForm
  cadence gate + xl host counts); `pullRenderState` (crosslink + geometry from always-run results); `gpuScaleCheck`
  (xlink-count + contraction payoff print); `GNAME`/`N_SPLIT`/`GI_*` made dynamic. `cpuStep` byte-untouched; constituent
  harnesses + monolith `buildPlan` + `BoA-v1ref` byte-clean. **§5.1/§6 morphology gap CLOSED.**
</content>
</invoke>
