# V2OneXHarness — IC parity (DONE) + GPU chained-TaskGraph split (PLANNED, paused before behavioral commit)

Branch `v2onex-gpu-split`. Part 1 committed; Part 2 is a **documented PAUSE** (no behavioral commit) — see §Status.

## Part 1 — in-plane filament IC parity — DONE ✓ (committed)
v2 now places IC filaments by v1's `FilSegment.makeRandomFilament` method: pick two random points in the box,
reject until separation ≈ chain contour, axis = unit(p2−p1), centre = midpoint. In the 0.5 µm slab this biases the
axis **in-plane** (≤~17° tilt) exactly as v1, and the two endpoints land in the box so the fixed-length chain fits
the slab. **Box geometry already matched** (v1 `rdmPtInside` = ±boxXDim/2 ⇒ ±3.5355 = v2 box; the `8·dimX·dimY·dimZ`
volume is a legacy metric that deflated `fActinUM` 8×, NOT a geometry diff). CPU re-validated: stable, finite,
**z-poke 1580→0/10000** (max |z| 0.268 µm), binding climbs 631→1734 heads, **13.7 steps/s** (≈ pre-fix 13.9 — work
unchanged). **Re-baseline: v2-CPU 1× ≈ 13.7 steps/s** on the corrected IC (v1-CPU 29 ⇒ v2 ~2× slower).

## Part 2 — GPU split — STATUS: PLANNED, NOT COMMITTED
`runGpu` is left **behaviorally unchanged** (it already prints the Graph-resize-blocker disclosure then runs CPU —
honest, gate-6 compliant, NOT a silent fallback). The full device-resident port is specified below and is a faithful
PORT of FullSystemDemo's `buildPlanSplit`/`stepSplit`, but completing it to **passing all 6 gates** requires
GPU-in-the-loop iteration (the ≈80-buffer per-graph residency bookkeeping and the GridScheduler keying are exactly
the two things the template's own history shows were found empirically — the executeAlloc-NPE §1 and the CUDA-701
GridScheduler trap). A blind one-shot autonomous port that committed unvalidated behavioral code would violate the
"HARD BAIL — commit nothing behavioral if CPU≡GPU breaks / residency can't hold" rule. So this is paused with a
near-executable plan for the next GPU-attached session.

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

### Gate table — PENDING (paused before GPU validation)
| gate | target | status |
|---|---|---|
| 1 builds + lowers on PTX (no Graph-resize, no CUDA 701) | — | **not run** |
| 2 device-resident (per-step transfer ~few KB) | — | not run |
| 3 CPU≡GPU vs `cpuStep` (bit/aggregate-SEM) + conservation + 0 phantoms + no NaN | — | not run |
| 4 work parity (bound-motor + crosslink counts) | — | not run |
| 5 throughput (warmup-excluded steps/s) vs v1-GPU 22 / v2-CPU 13.7 | — | not run |
| 6 disclosure honored (no silent CPU fallback) | runGpu prints the blocker | **HELD** (stub unchanged — discloses) |

**Per-execute creep:** expected ABSENT/smaller than FullSystemDemo — the `fdNuc` per-`execute()` creep carrier
(TASKGRAPH_SPLIT_FINDINGS) is NOT in this partition (no nucleation graph); the only gated graph (fdXForm) is a
throttled SINK, so any residual chained-`execute()` accrual is the cadence-diluted G0–G3 baseline.

## Next session (GPU-attached) — fast path
1. Add `buildPlanSplit`/`stepSplit`/`blkBind`/`blkStruct`/`blkFil`/`blkInteg`/`blkXForm`/`buildSplitScheduler` to
   V2OneXHarness, modeled line-for-line on FullSystemDemo with the deletions above; wire `runGpu` to it.
2. Reuse `hostNodeCSR`/`hostSegCSR` (or port them — they're FilamentStore/MotorStore-generic).
3. Iterate on GPU: (a) lowers? (Graph-resize → split a graph further; unlikely at 5); (b) CUDA 701 → fix the grid
   key sizing; (c) executeAlloc NPE → a persisted buffer no task in its graph uses (move its first-use); (d) CPU≡GPU
   on a 50-step bit check (Brownian off) then aggregate-SEM with Brownian on.
4. Report v2-GPU steps/s for the four-way (v1-CPU 29 / v1-GPU 22 / v2-CPU 13.7 / v2-GPU ?).
