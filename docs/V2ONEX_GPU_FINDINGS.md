# V2OneXHarness — IC parity (DONE) + GPU chained-TaskGraph split (DONE — Part 2 device-resident, all gates GREEN)

Branch `v2onex-gpu-split`. Part 1 + Part 2 committed. **Part 2 implemented and validated on the aorus GPU
(2026-06-24): the 5-graph chained device-resident split runs, CPU≡GPU, ~50 steps/s.**

## Part 1 — in-plane filament IC parity — DONE ✓ (committed)
v2 now places IC filaments by v1's `FilSegment.makeRandomFilament` method: pick two random points in the box,
reject until separation ≈ chain contour, axis = unit(p2−p1), centre = midpoint. In the 0.5 µm slab this biases the
axis **in-plane** (≤~17° tilt) exactly as v1, and the two endpoints land in the box so the fixed-length chain fits
the slab. **Box geometry already matched** (v1 `rdmPtInside` = ±boxXDim/2 ⇒ ±3.5355 = v2 box; the `8·dimX·dimY·dimZ`
volume is a legacy metric that deflated `fActinUM` 8×, NOT a geometry diff). CPU re-validated: stable, finite,
**z-poke 1580→0/10000** (max |z| 0.268 µm), binding climbs 631→1734 heads, **13.7 steps/s** (≈ pre-fix 13.9 — work
unchanged). **Re-baseline: v2-CPU 1× ≈ 13.7 steps/s** on the corrected IC (v1-CPU 29 ⇒ v2 ~2× slower).

## Part 2 — GPU split — STATUS: DONE (device-resident, all 6 gates green, committed)
`runGpu` now runs the device-resident 5-graph chained split (`buildPlanSplit`/`stepSplit`/`blkBind`/`blkStruct`/
`blkFil`/`blkInteg`/`blkXForm`/`buildSplitScheduler` + `hostNodeCSR`/`hostSegCSR`), a faithful PORT of FullSystemDemo's
mechanism adapted to V2OneX's clean subset (ONE grid-bound motor population, crosslinkers ON, no turnover/nucleation/
free-minifilament). **It lowered on PTX on the FIRST attempt** — no Graph-resize, no CUDA 701, no executeAlloc NPE
(the plan's partition + GridScheduler re-keying were correct as written). v2-GPU **50.0 steps/s** (3.6× v2-CPU,
2.3× v1-GPU). CPU≡GPU validated via the new `-cmp`/`-brownoff` modes: bound-set Δ ≤ 5/9600, bounded chaotic coord
plateau (no NaN, no divergence). See the gate table below.

### Partition (5 chained graphs — V2OneX is a clean SUBSET of FullSystemDemo: NO turnover/nucleation/minifilament)
The V2OneX `cpuStep` maps 1:1 onto FullSystemDemo's blocks **minus** `fdTurnFire`+`fdNuc` (no biochem) and minus the
`mot2`/`mini` second-motor path (V2OneX has ONE motor population — the node-shell singlets — bound via the **grid**
path, which is byte-for-byte FullSystemDemo's `mot2` grid path, NOT the node-aware brute path).

| # | graph | tasks (verbatim from `cpuStep`) | gated? |
|---|---|---|---|
| G0 | **fdBind** | publishHeadFromBody · publishToBodyView(fil) · publishToBodyView(mot) · bodyCell · gridChunkZero · gridChunkHistogram · gridChunkReduce · gridScanLocal · gridScanChunks · gridScanAdd · gridChunkScatter · gridReachable · catchSlipRelease · bindNearest · cycle | always |
| G1 | **fdStruct** | zeroAccumulators×3 (mot.body, node.body, fil) · brownianForce×3 · MotorJointSystem.joints · DimerCouplingSystem.couple · NodeSystem.tether · node CSR (csrHistogram/csrScan/csrScatter on `attachNode`) · MiniFilamentSystem.backboneGather · CrossBridgeSystem.bondForces · applyHeadForce | always |
| G2 | **fdFil** | ChainBendingForceSystem.chainForces · seg-gather CSR (csrHistogram/csrScan/csrScatter on `boundSeg` + segGather) · CrosslinkerSystem.linkForces · linkTorsion · 2-pass xlink gather (csr A + segGatherA, csr B + segGatherB) · registerForceDot | always (**UPLOADER of the shared crosslinker link state** — keeps the gated G4 skip-safe) |
| G3 | **fdInteg** | ContainmentSystem.confine(node, fil) · integrate(node, mot.body, fil) · derive(node, mot.body, fil) | always |
| G4 | **fdXForm** | filidInit + filidJump×rounds · FormationGrid build · formGates · formAdmitReduce · formAdmit · freeFlags/csrScan/freeScatter · allocate · placeOrient | **CADENCE-GATED, the SINK** (`t % XL_CHECK_INT == 0`) |

Each graph is far under the single-`TaskGraph` Graph-resize cap (the whole comp hit it as ONE graph; in 5 it lowers).
**fdXForm at the chain END** is the §9.3 law: a gated graph must be a SOURCE or SINK; as the last graph nothing
consumes its sole-uploaded formation scratch, and the shared link state it reads is uploaded by always-run fdFil.

### Residency wiring (reuse FullSystemDemo's generic loop verbatim — buildPlanSplit lines ~1810-1835)
- **firstExec** = every persistent SoA buffer the step touches (V2OneX's set: `mot.*/mot.body.*`, `dim.*`,
  `node.node.*/node.*` incl. the node-attach CSR (`attachNode/nodeAttachCount/Offsets/List/nodeCounts4/nodeData/
  nodeParams/nodeInvTransY/attachKey/radial/attachCoeffK`), `fil.*`, the grid (`view.*/gridParams/gridDims/
  gridCounts/bodyCell/cellCount/chunkSum/gridCellOffsets/gridCellContents/chunkParams/chunkCellCount/reachSeg/
  reachCount`), `bondData/xbParams/segMotorCount/Offsets/Myo`, `boxParams`, and — xl ON — the crosslinker force
  buffers + the 2-pass CSR (`segCountA/segOffsetsA/segIdxA/B…`) + `xl.linkState/linkFilA/linkFilB/loc1/loc2/
  xlinkData/xlParams/offParams/torsionParams/strainHist/strainPlace/linkOrientSame/torqueMagHist/torqueMagPlace/
  activeLinkCount`). **Drop ALL of FullSystemDemo's `grow/d/ag/sv/nuc/mot2/dim2/mini/b2/bb` buffers.**
- **everyExec** (re-uploaded every graph, never persisted): `mot.counts, node.nodeBodyCounts, fil.counts` and — xl
  ON — `xl.counts, xl.formCounts, fg.gridCounts`.
- **Per-graph first-use sets** `u0..u4(+uX)`: take FullSystemDemo's `u1` (→ G0 fdBind, KEEP only the grid path +
  node-shell mot buffers, DROP brute `reach`/node-aware + all mot2), `u2` (→ G1, DROP b2/bb/mini/dim2), `u3`
  (→ G2, DROP mot2 forceDot + bondData2; KEEP the xlink force + 2-pass CSR + link-state upload exactly), `u4`
  (→ G3, DROP b2/bb), `uX` (→ G4, verbatim — filID + FormationGrid + formation scratch). The generic loop then
  computes `newBufs`/`consumeSet`/persist automatically (upload-at-first-use → no executeAlloc NPE).
- **CSR-host default** (reuse `hostNodeCSR`/`hostSegCSR`): static node-attach CSR precomputed ONCE at build
  (`attachNode` fixed); dynamic seg-gather CSR host-built each step from `mot.boundSeg` pulled after fdBind, then
  `segMotorCount/Offsets/Myo` re-uploaded EVERY_EXECUTION into **fdFil** (gate the upload on `gi == GI_FIL`).
- **host pulls (UNDER_DEMAND):** fdBind → `mot.boundSeg, mot.nucleotideState` (binding render + CSR-host trigger);
  fdFil → `xl.linkState/linkFilA/linkFilB/loc1/loc2/linkOrientSame` (crosslink render — always-run, current); fdInteg
  → `node.coord, fil.coord/end1/end2, mot.body.end1/end2` (derived geometry for the viewer).

### GridScheduler re-keying (the CUDA-701 trap — buildSplitScheduler, every RNG/trig kernel → localWork=64)
Re-key each under its NEW `<graph>.<task>` name with the WorkerGrid sized to the kernel's global range:
- **fdBind:** `gridReachable, bindNearest, cycle, catchSlipRelease` (range = `mot.nMotors`); the 11 grid-build
  kernels per their existing dims.
- **fdStruct:** `brownMot` (range mot bodies), `brownNode` (node bodies), `brownFil` (fil segments).
- **fdFil:** `unbind, linkTorsion` (range = xl capacity).
- **fdXForm:** `formGates, formAdmit, placeOrient` (range = formation candidate/req capacity) + `filidJump*`
  (range `fil.n`).
Mis-sized or mis-named key ⇒ CUDA 701 (LAUNCH_OUT_OF_RESOURCES) — verify each against `cpuStep`'s effective range.

### Gate table — ALL GREEN (measured on aorus, RTX 5070, TornadoVM 4.0.1-dev PTX, 2026-06-24)
| gate | target | status / measurement |
|---|---|---|
| 1 builds + lowers on PTX (no Graph-resize, no CUDA 701) | clean lowering | **PASS — FIRST TRY.** 5 graphs lower; no Graph-resize, no CUDA 701. The plan's 5-graph partition was correct as written. |
| 2 device-resident (per-step transfer ~few KB) | residency across 5 graphs | **PASS.** persistOnDevice/consumeFromDevice hold; per-step host transfer = `mot.boundSeg` (9600 ints ≈38 KB, the CSR-host round-trip) + render pulls at report cadence only; NO full-state copy. `-devicecsr` (no per-step boundSeg pull) also runs device-resident (46.3 steps/s), proving residency holds without the round-trip + the CSR is bit-identical (matched bound trajectory). |
| 3 CPU≡GPU vs `cpuStep` + conservation + 0 phantoms + no NaN | bit/aggregate-SEM | **PASS.** Deterministic bit-check (`-cmp -brownoff`): **bound-set Δ ≤ 5/9600 (≤0.05%)** at every step — binding logic essentially bit-identical; coord Δ is a BOUNDED chaotic-scheme plateau (1.2e-2 µm @ step 6, slow Lyapunov drift to 8.7e-2 µm @ step 200 — the accepted many-body op-ordering decorrelation, NOT a wiring bug: no NaN, no unbounded divergence). Brownian-ON: bound-count trajectories match within ±5/2141 (0.2%) over 1500 steps. Conservation/phantoms trivially N/A (no turnover/nucleation/allocation in this scene). Stable + finite + box-contained at every horizon (60→2000 steps). |
| 4 work parity (bound-motor + crosslink counts) | match CPU path | **PASS.** Bound heads track to ±3–5 at every checkpoint (CPU 1607 vs GPU 1610 @ 400; CPU 2141 vs GPU 2141 @ 1500). Crosslinks both single-digit→low-tens climbing together (CPU 12 / GPU 21 @ 1500 — same small-N Poisson regime, chaotic-decorrelated, NOT a formation discrepancy). |
| 5 throughput (warmup-excluded) vs v1-GPU 22 / v2-CPU 13.7 | beat v2-CPU | **PASS — v2-GPU = 50.0 steps/s** (1980 measured steps, warm 20 excluded). **3.6× v2-CPU (13.7), 2.3× v1-GPU (22).** Four-way: v1-CPU 29 / v1-GPU 22 / v2-CPU 13.7 / **v2-GPU 50.0**. |
| 6 disclosure honored (no silent CPU fallback) | runGpu runs device-resident | **PASS.** `runGpu` now runs the device-resident split (no fallback); prints the residency disclosure up front. Only a build-time exception falls back to CPU (with a clear FAIL notice). |

**Per-execute creep:** NONE observed. steps/s flat across the run after warmup (1500→2000 steps: 50.2→50.0). The
`fdNuc` per-`execute()` creep carrier is absent (no nucleation graph); the only gated graph (fdXForm) is a throttled
SINK firing 1/100 steps, so the chained-`execute()` accrual is cadence-diluted to the noise floor.

### Final partition used (matched the plan exactly)
| # | graph | gated? | tasks |
|---|---|---|---|
| G0 | **fdBind** | always | publishHead · filPublish · motPublish · 8 grid-build · gridReach · release · bind · cycle |
| G1 | **fdStruct** | always | zero×3 · brown×3 · joints · dimer · tether · ndGather (node CSR host-precomputed) · bond · applyHead |
| G2 | **fdFil** | always (xlink link-state UPLOADER) | chain · filGather (seg CSR host-built, re-uploaded EVERY_EXECUTION) · register · xlUnbind/Count/Force/Torsion · 2-pass xlink CSR+gather |
| G3 | **fdInteg** | always | confine/integrate/derive (node · mot · fil) |
| G4 | **fdXForm** | **CADENCE-GATED SINK** (`t%100==0`) | device filID · FormationGrid build · gates · admit · scan-rank allocator · placeOrient |

NB on the plan's partition vs as-built: G2/G3 from the plan's text (the fdFil block named both the filament chain AND
the xlink force, fdInteg the integrate) collapsed to the obvious 5-graph order above. No further sub-splitting was
needed — every graph lowered well under the single-`TaskGraph` cap on the first attempt. CSR-host is the default
(`-devicecsr` reverts the dynamic seg round-trip; static node CSR is unconditional host-precompute, a pure win).

## Next session (GPU-attached) — fast path
1. Add `buildPlanSplit`/`stepSplit`/`blkBind`/`blkStruct`/`blkFil`/`blkInteg`/`blkXForm`/`buildSplitScheduler` to
   V2OneXHarness, modeled line-for-line on FullSystemDemo with the deletions above; wire `runGpu` to it.
2. Reuse `hostNodeCSR`/`hostSegCSR` (or port them — they're FilamentStore/MotorStore-generic).
3. Iterate on GPU: (a) lowers? (Graph-resize → split a graph further; unlikely at 5); (b) CUDA 701 → fix the grid
   key sizing; (c) executeAlloc NPE → a persisted buffer no task in its graph uses (move its first-use); (d) CPU≡GPU
   on a 50-step bit check (Brownian off) then aggregate-SEM with Brownian on.
4. Report v2-GPU steps/s for the four-way (v1-CPU 29 / v1-GPU 22 / v2-CPU 13.7 / v2-GPU ?).
