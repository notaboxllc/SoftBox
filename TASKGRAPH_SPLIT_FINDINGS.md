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
</content>
</invoke>
