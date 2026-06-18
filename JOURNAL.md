# Soft Box Project Journal

Last updated: 2026-06-18

## 2026-06-18 — INC 6c STAGE B2: the node NUCLEATION-FUNCTION (formin actin nucleation) — DONE
The node's implicit-formin actin nucleation (seam #1, additive over Stage A) — **the first dynamic actin
CREATION in SoftBox**, completing the node (motor-bundle + nucleation). **All 8 gates PASS GPU + CPU; all
prior harnesses bit-identical.** Built from jba's behavioral spec (NOT a v1 port). Report:
`INC6C_NODE_STAGEB2_FINDINGS.md`.
- **Behavior (jba's spec):** per node per step — birth a fixed-length seed (B1 allocator, UNCHANGED) at
  `kNodeNuc·dt`; hold it with an ELASTIC fracMove tether (node-center ↔ seed-end); DISSOLVE the bond at a
  constant rate → free filament; the born seed is Brownian-DAMPED. Deplete the actin pool (seam #2).
- **v1 clean specifics reproduced:** kNodeNuc=10/node·s, actinSeed=3 (≈10.8 nm), tether =
  `fracMove·1e-6·strain/(dt·(1/fil.bTransGam.x+1/node.bTransGam.x))` fracMove=0.5 attach-at-node-center
  (the SAME spring as NodeSystem.tether / minifilament), nodeTetherDetachRate=0.001/s.
- **FLAGGED v1 drift (built jba's spec, did NOT copy):** (1) v1's detach + max-strain triggers are INACTIVE
  by default (Parameter `false`) — B2 enables the rate; (2) the v1 node-tether release is a CONSTANT rate,
  NOT Bell/log-stretch (recon §2a wording imprecise); (3) v1 has an optional nodeTorqSpring align torque
  (1e-18, active) NOT in jba's spec — B2 omits it (positional tether only; flag for jba); (4) forminsPerNode
  default 0 = off (production no-op).
- **THE DAMPING PRINCIPLE (jba, generalizes to the membrane nucleation):** a short fixed-length seed flails
  at full thermal; the formin's TIGHT hold is a STIFF constraint inexpressible at the large production dt
  (the same fracMove dt-stiffness family). The fix = a SOFT elastic tether (positional, dt-compatible) +
  artificial Brownian damping (~30×, the seed only) compensating for the tether's softness — a legitimate
  dt-compensating approximation, deliberately non-FDT for the seed (existing filaments keep scale 1.0). NOT
  an FDT bug; NOT node-coupling stiffness (the tether handles coupling).
- **Architecture:** `NodeNucleationSystem` (countBoundFil/emit/tagSeeds/seedTether/dissolve — wang-hash RNG,
  no atomics, dual-runner). Lifecycle: `filState` (B1, slot alive?) ⟂ `seedNode` (B2, tethered to which
  node? `<0`=free). Dissolution sets seedNode=-1 but keeps filState ACTIVE ⇒ free filament (slot NOT freed;
  turnover deferred). Born seed damping = B1 birthParams (NO Brownian-system edit). **The ONE shared-kernel
  touch (B1-flagged):** a guarded `publishToBodyView` OVERLOAD — FREE slot → STORE_NONE (excluded from the
  narrow-phase); the 8-arg is byte-unchanged, the 9-arg ≡ it when all-active. `ActinPool` = seam #2 (scalar
  now / field later, behind available()/take()).
- **Gates (`run_nodenuc.sh`, both runners):** 1 rate (1.097e-4 vs 1.0e-4, 9.7% / Poisson); 2 tether (force
  vs v1 double-ref rel 2.1e-8, relaxes/bounded); 3 dissolution (pre-tethered 4000, empirical pDetach 2.0015e-2
  vs 2.0e-2 = **0.1%**; freed seeds stay ACTIVE — elevated 2000/s test, v1's 0.001/s validated by formula);
  4 pool (depletes exactly + available() gate stops emission, pool dry); 5 no-op-when-off (forminsPerNode=0 ⇒
  0 births, Δcoord=0); 6 CPU≡GPU (seedNode/filState 0 mismatches = bit-identical lifecycle; pose Δ 4.66e-10
  µm); 8 damping (wander 4.35e-5 damped vs 1.30e-3 undamped); P publish-guard (FREE→STORE_NONE, no-op when
  all-active).
- **Regression:** filbirth/node/grid/motor/minifil/dimerglide/miniglide/contractile all PASS (bit-identical).
  `BoA-v1ref` byte-clean; production untouched.
- **New files + additive edits only:** `ActinPool`, `NodeNucleationStore`, `NodeNucleationSystem`,
  `NodeNucleationHarness`, `run_nodenuc.sh`; +`SpatialBodyView.STORE_NONE`, +`FilamentStore.publishToBodyView`
  9-arg overload, +`Constants` nucleation/pool consts.
- **THE LAST ENTITY PORT LANDS** — the node is complete. Migration edge (waiting on v1/membrane): growth/
  polymerization, filament death/turnover, the membrane formin nucleation (damping principle generalizes),
  branched networks, dynamic cortex, the optional nodeTorqSpring. Horizon: a fixed-anchor minimal contractile
  ring (nucleating nodes + the contractile-assay tension read — all primitives now exist).

## 2026-06-18 — INC 6c STAGE B1: the FilamentStore runtime-birth lifecycle — DONE
The **first dynamic filament creation in SoftBox** (`FilamentStore` was fully static through inc 6). **All 3
gates PASS GPU + CPU; every prior harness bit-identical.** Report: `INC6C_NODE_STAGEB1_FINDINGS.md`.
- **What:** a per-slot birth lifecycle on the foundational entity (recon §2 risk). v2-side infrastructure,
  INDEPENDENT of v1's churning nucleation specifics, so it proceeds now, validated with a SYNTHETIC birth.
  B2 later wires the node's real nucleation as the birth SOURCE.
- **`filState` sentinel** (mirrors crosslinker `linkState`): `>=0` ACTIVE / `<0` FREE. **Default all-ACTIVE
  (0)** ⇒ every existing harness unaffected (no-op-when-all-active).
- **Allocator = inc-5 scan-rank free-list reused VERBATIM, one level up** (`FilamentBirthSystem`): the two
  prefix sums run `CrossBridgeSystem.csrScan` byte-unchanged; `freeFlags`/`freeScatter` are the inc-5
  companions; `allocate` claims `freeList[rank<nFree]` (overflow-clamped), writes the FIXED-LENGTH seed pose
  (v1 actinSeed=3 ⇒ ≈10.8 nm; growth deferred), turns on Brownian, flips FREE→ACTIVE. Distinct ranks →
  distinct slots ⇒ race-free, no atomics ⇒ **bit-identical CPU↔GPU**. A born seed = a free rod (neighbors
  -1; v1 nucleates one FilSegment born bonded to the NODE, not a segment).
- **THE LOAD-BEARING DECISION — the active-guard is DATA-DRIVEN, not a per-kernel branch.** A FREE slot is
  inert by its data (`markFree` zeroes brownTransScale/brownRotScale ⇒ no Brownian; neighbors -1 ⇒ free rod,
  not a neighbor of anyone; parked inside the box ⇒ containment no-ops; forceSum=0 ⇒ integrator v=0). So
  **NO shared device kernel is touched** (integrate/Brownian/derive/chain/containment/gather byte-unchanged)
  ⇒ the no-op-when-all-active guarantee is BY CONSTRUCTION (prior harnesses byte-unchanged). The ONE branch
  B2 will add — keeping a FREE slot out of the broad-phase (publish-time `filState` guard) — is deferred;
  B1's synthetic harness parks FREE slots off the candidate set by geometry (gate C proves it).
- **Gates (`run_filbirth.sh`, both runners):** A allocator — free-list index order, distinct-slot/no-double-
  alloc, born payload, slot-stability (Design A), overflow clamp, same-step reuse after a synthetic free,
  CPU≡GPU bit-identical (Δ=0). B born@0 ≡ preplaced bit-identical (Brownian off AND on, max|Δpose|=0) +
  FREE slot inert (stays exactly parked) + non-J filaments unperturbed (Δ=0) + participates after birth +
  CPU≡GPU (Δ=0). C a born filament is bound (0 pre-birth → 8 post-birth) + gathers cross-bridge load
  (|F|>0, gather==brute Δ=0); a parked FREE filament is NOT bound (geometry excludes it).
- **Regression (no-op-when-all-active):** node, minifil, dimer, dimerglide, miniglide, stroke, xbridge,
  motor, contractile, xlink all re-run PASS; foundational FDT within 5%. `BoA-v1ref` byte-clean; production
  untouched.
- **New files only + 1 additive edit:** `FilamentBirthSystem`, `FilamentBirthHarness`, `run_filbirth.sh`;
  `FilamentStore` gained the sentinel + allocator scratch + helpers (constructor delegates; callers unchanged).
- **For the planner (B2):** replace the synthetic driver with the per-node nucleation emitter (rate
  `kNodeNuc·dt`, pre-bonded, fills the same request arrays; allocator unchanged); re-confirm nucleation
  specifics vs a fresh `ProteinNode.java` snapshot; add the publish-time `filState` guard. Growth deferred.

## 2026-06-18 — INC 6c STAGE A: the protein NODE entity (radial motor-bundle, fixed anchor) — DONE
The protein node built FRESH as a motor-bundle, reusing the SETTLED minifilament machinery. **All 7 gates
PASS GPU + CPU.** Report: `INC6C_NODE_STAGEA_FINDINGS.md`.
- **What:** a fixed-anchor sphere node (the **4th `RigidRodBody`**, isotropic sphere drag, radius 0.05 µm,
  never integrated = the v1 `AnchorNode` immobilization) owning radially-splayed singlet myosins + dimers.
  The node mechanism IS the minifilament's (rigid body owning motor-children via fracMove tether + a
  single-ended backbone-side gather); the only differences are GEOMETRY (radial sphere-surface splay vs
  axial clusters) + the node also carries singlets. Both are placement.
- **The ONE new kernel = `NodeSystem.tether`** (radial surface tether) — faithful port of v1
  `ProteinNode.keepMyosinsOnSurface`/`keepMyosinDimersOnSurface`: the SAME fracMove spring LAW as the
  minifilament (`F=coeff·1e-6·strain/(dt·(1/rod.bTransGam.y+1/node.bTransGam.y))`, from rod end1) with
  RADIAL attach (`surface = coord + ru·u + ry·y + rz·z`, zVec=u×y in-kernel). Singlet: coeff
  `attnForce/numNodeMyos` (0.4/nSing), force at rod CENTER (no torque); dimer: coeff
  `attnForce·myoDimerFracMove` (0.08), force at rod END1 (+torque), node reaction at the surface point.
  NO axis-align torque (verified in BoA-v1ref — unlike the minifilament).
- **Reused BYTE-UNCHANGED (no fork):** the single-ended gather (`CrossBridge.csr*` keyed by attachNode +
  `MiniFilamentSystem.backboneGather` over a stride-6 nodeData); binding + cross-bridge; the 12 pN cap
  (`setFaithfulRelease`); `ContainmentSystem`; the shared rod systems; Motor/Dimer stores + coupling.
- **The radial tether is node-SPECIFIC, not a fork:** radial splay genuinely needs y/z offsets + the
  singlet/dimer torque asymmetry, inexpressible by the axial minifilament tether. It's the node's
  localized physics (the per-entity-system pattern) reusing the LAW + the gather machinery byte-unchanged.
- **Gates:** #1a gather==brute isolated (Δ0, momentum 3.4e-20 N, 12 singlet+12 dimer owned); #1b gather
  UNDER LOAD (node + fil gather==brute Δ0 at real cross-bridge load, full-system momentum 1.6e-19 N,
  **CPU≡GPU 2.1e-6 µm**, 23-task TaskGraph); #2 radial head binds via the real pathway; #3 the 12 pN cap
  fires on a 13 pN node bond (capStats=1); #4 containment confines the node body (0.180→0.167 µm); #5
  fixed anchor Δpose=0 under load; #6 all-OFF≡HEAD bit-identical + control.
- **TornadoVM:** 20 logical tether args → 15 via planar packing (attachKey=node|motor, radial=X|Y|Z,
  signed attachCoeffK carries atEnd1) + in-kernel zVec.
- **Seam #1 kept open** for Stage B (nucleation + runtime filament birth — the inc-5 scan-rank allocator
  is the template; re-confirm the nucleation specifics vs a fresh `ProteinNode.java` snapshot at build).
- New files only: `NodeStore`, `NodeSystem`, `ProteinNodeHarness`, `run_node.sh`. No shared file touched
  ⇒ prior harnesses byte-unchanged (minifil + dimer re-run PASS). `BoA-v1ref` byte-clean; production
  untouched; node default-off in production.
```
./run_node.sh              # GPU + CPU cross-check (gather, gather-under-load, binding, cap, containment, anchor)
./run_node.sh -cpu         # CPU runner only (triage)
./run_node.sh -3js threejs_node -n 3   # viewer (radially-splayed nodes)
```

## 2026-06-18 — FAITHFUL DENSE GLIDING COMPUTE BENCHMARK (multi-filament + grid binding) — vs BoA `BENCHMARK_dense.md`. The directly-matching harness for BoA's dense-gliding weak-scaling sweep. **Headline: with FAITHFUL multi-filament grid binding, SoftBox is 2–4× SLOWER than BoA's GPU, gap WIDENING with scale — bottlenecked by the single-threaded inc-3 grid build.** New file `DenseGlidingHarness.java` only; `GlidingHarness`/all systems/stores reused (a few default-off flags added to `GlidingHarness`); `BoA-v1ref` byte-clean.
**What it is:** 400·scale filaments + 98000·scale motors over BoA's box schedule `boxXY=14·√scale × 0.5 µm`, density 500 motors/µm² — the dense gliding bed, NOT the single-filament velocity assay. Binding uses the inc-3 device GRID broad-phase + the inc-4a consumer (publishers → grid build → `broadPhase` → `invertCandidates` → `computeReachable`), feeding the SAME `reachSeg/reachCount` that `bindNearest` consumes; everything downstream is the validated `GlidingHarness` gliding force chain. **GRID==BRUTE GATE PASS** (`-gridcheck`): the grid reachable set == `bruteReachable` (every motor×segment) bit-exact on identical positions — the dense binding path is faithful. (The gate must compare both reachables PRE-integrate; my first cut compared across an integrate step → spurious mismatch — fixed.)
**Faithful GPU sweep (RTX 5070; grid binding; warmup-windowed ms/step; clean — no broadphase overflow maxCand≤88<256, no NaN):** data `RUN_LOGS_densesweep.txt`.

| scale | motors | filaments | SoftBox GPU ms/step | BoA GPU ms/step | SoftBox/BoA | avgBound |
|---|---|---|---|---|---|---|
| 0.5× | 49 000 | 200 | 113.6 | 53.4 | 2.1× slower | 805 |
| 1× | 98 000 | 400 | 259.3 | 89.7 | 2.9× slower | 2007 |
| 2× | 196 000 | 800 | 539.1 | 168.7 | 3.2× slower | 3327 |
| 4× | 392 000 | 1 600 | 1171.0 | 343.7 | 3.4× slower | 4659 |
| 8× | 784 000 | 3 200 | 2515.6 | 659.1 | 3.8× slower | 6663 |

**Root cause = the single-threaded inc-3 grid build.** `SpatialGrid.gridHistogram` + `gridScatter` are `@Parallel(gid<1)` ONE-GPU-thread O(N) passes (designed race-free for inc-3's N=512); at 50k–800k view bodies they dominate and serialize. The bare motor core is cheap (see below); the per-step time is grid-build-bound and slightly super-linear (113→259→539→1171→2516 ≈ ∝N^1.1). **The optimization lever = a PARALLEL grid build (counting-sort / atomic-free segmented histogram), exactly what BoA's `gridScatter` already is** (it was BoA's dominant kernel too).
**⚠️ DO NOT trust the earlier single-filament "motor-mat" numbers as a comparison** (`GlidingHarness -densemat`, this session: 1× 11.6 / 8× 84.3 ms/step, ~8× "faster" than BoA; `-box` adds ≤2.6%). Those are **binding-FREE** (one filament, brute over 11 segs) — they measure motor integration throughput only and are NOT a faithful dense-gliding comparison. The faithful number is the table above (2–4× slower).
**`-brute` diagnostic (GPU-parallel `bruteReachable` instead of the grid, to isolate the grid-build cost): added but BROKEN** — hits a TornadoVM `failed guarantee: invalid variable` lowering error in the brute-branch TaskGraph. Default-off; the validated grid path is unaffected (`-gridcheck` still PASS). **Debug tomorrow** (or go straight for the parallel grid build, the real fix).
**New:** `DenseGlidingHarness.java` (modes `-gridcheck` / `-scale N [-cpu] [-brute] M`), `run_densesweep.sh`, `RUN_LOGS_densesweep.txt`; `GlidingHarness` gained default-off `-densemat N`/`-box`/`-boxall` (single-filament motor-mat probes — existing paths functionally unchanged). **Tomorrow:** parallelize the grid build (the lever), re-sweep; fix/retire `-brute`; optional CPU sweep at small scales.

## 2026-06-17 — DEFERRED BENCHMARK TARGET — match BoA v5 dense-contractile GPU/CPU sweep (BLOCKED ON POLYMERIZATION). Survey/scoping only; no SoftBox code written. The goal: reproduce BoA's dense contractile compute sweep in SoftBox's ECS/GPU path to test the "ECS is faster than the BoA GPU path" hypothesis. **CANNOT run yet — the v5 filaments POLYMERIZE (turnover ON) and SoftBox has no polymerization / runtime filament birth-and-growth** (the unbuilt inc-6c element — see `INC6_NODE_RECON.md` §2). Revisit once polymerization lands.
**The BoA target = "dense contractile benchmark v5: 4× density (40× areal)" (2026-06-13).** Source: `~/Code/BoA/JOURNAL.md:718` + `~/Code/BoA/BENCHMARK_contractile_dense.md` (v4 base) + data/driver `~/Code/BoA/RUN_LOGS/2026-06-13_dense_v5_4xdensity/` (`bcd_summary.txt`, `run_bcd.sh`, `gen_fixture.py`). Fixture base `ParameterFiles/boa10-64Seg-dyn-dense` (`-dyn` ⇒ turnover ON; 64-monomer segs; segs grow ~2.8× over a run — 1× ends ~23.7k segs from 4k fils).
**Exact scene to match (counts + box, per scale; dt=1e-4; 650 steps = warmup 300 + window 350; ms/step = window_wall/350 via in-process windowed timing):** `boxXY=10·√scale µm`, depth 0.5; `initialFilaments = initialMyoMiniFils = 4000·scale`; each minifil 16 dimers (8/end) ⇒ 32 heads; crosslinkers `grab=0.05, maxFilLinkDist=0.02, xLinkOnRate=400, xLinkConc=1.0`.

| scale | boxXY µm | filaments | minifils | active xlinks (GPU) | BoA CPU ms/step | BoA GPU ms/step | BoA GPU/CPU |
|---|---|---|---|---|---|---|---|
| 0.5× | 7.071 | 2 000 | 2 000 | 1 693 | 117.2 | 86.4 | 0.74 |
| 1× | 10.0 | 4 000 | 4 000 | 3 363 | 215.0 | 134.5 | 0.63 |
| 2× | 14.142 | 8 000 | 8 000 | 6 575 | 434.3 | 246.4 | 0.57 |
| 4× | 20.0 | 16 000 | 16 000 | 12 579 | 865.6 | 494.3 | 0.57 |
| 8× | 28.284 | 32 000 | 32 000 | 25 136 | 1777.2 | 1030.2 | 0.58 |

BoA verdict: at this 40× density GPU **wins** at every scale, saturating ~1.75× faster (GPU/CPU≈0.57). 8× ≈ 32k fil + 32k minifil ≈ **~1M heads**; BoA hit ~25.5 GB RSS / 3.8 GB VRAM (`-Xmx26G`). **The number to beat is the BoA GPU column.**
**SoftBox gap before this can run (the build, once polymerization exists):** (1) **polymerization/turnover** — the hard blocker; (2) a NEW dense-contractile-network harness composing minifilament binding + cross-bridge gather + nucleotide cycle + chain forces + crosslinker formation/force/unbind + containment + integration into ONE device-resident GPU TaskGraph at up to 32k+32k entities (today: `ContractileAssayHarness`=1 minifil+2 fil fixed; crosslinkers validated but never wired to a minifil scene); (3) raise `SpatialGrid.MAX_CAND=256` + structure array caps for 40× areal density (silent broad-phase drops = unfaithful). All SoftBox structure pieces are validated in isolation (6a/6b/glide/contractile-assay/containment + 5a–5c-iii crosslinkers) — the work is composition-at-scale + turnover, not new physics. Run GPU + CPU (the GPU/CPU ratio is the comparable quantity); de-risk at 0.5×/1× before 4×/8×.

## 2026-06-17 — Increment 6 — GENERAL IN-VITRO-CHAMBER CONTAINMENT BOX DONE. A general, entity-agnostic containment primitive (the simulation-domain boundary / in vitro chamber); the contractile assay consumes it with ZERO regression. 7 contractile gates PASS GPU+CPU + 9 prior harnesses re-run bit-identical. NEW file `ContainmentSystem.java` only + the contractile harness; no shared system/store touched; production/`GlidingHarness` byte-unchanged; `BoA-v1ref` byte-clean. Report: `INC6_CONTAINMENT_FINDINGS.md`.
**What it is / is NOT:** a general `ContainmentSystem` confining **positions, not class identities** — one kernel over a `RigidRodBody`'s pose+drag+accumulators, invoked per store like the shared integrator/Brownian/derive ⇒ confines filament segments, motor sub-bodies, minifilament backbones, (future) nodes with the SAME code. It is the **in-vitro experimental chamber** (coverslip/flow-cell), a reusable primitive every in vitro assay consumes — **NOT the membrane subsystem** (the deferred dynamic cortex, inc 7, is a distinct later thing).
**Faithful to v1 — the free-body box law** (shared detection + per-type force): detection = `Chamber.amICollidingOuter` (`Chamber.java:125-138`, per endpoint `forceUVec_i=sign(d_i)·(halfDim_i−R)−d_i`, ZERO axes still pointing inward — this is where the no-op-inside lives); force = `MyoMiniFilament.checkOuterBugCollision` (`:546-560`, `mag=nodeFracMove·1e-6·delta·bTransGam.x/collisionDeltaT`, `F=mag·f̂` at each endpoint ⇒ `forceSum+=F`, `torqueSum+=r×F`, `r=(end−ctr)·1e-6`), fired every `collisionCheckInt=collisionDeltaT/dt=10` steps via a step-gate on `counts[1]` (GPU graph stays fixed; no-op on non-check steps). v1 defaults `nodeFracMove=0.5`, `collisionDeltaT=1e-4`, R=0.005 µm.
**NOT ported (flagged — abstract-from-the-second-instance):** v1's SEPARATE `FilSegment.bugForcesFromInside` law (`0.1·min(fturn,ftrans)`, an extra torque-drag clamp). Never exercised — the contractile filaments are pinned+inset 0.10 µm so a free filament never reaches a wall; un-exercised second law deferred to its second instance. (v1 also leaves `MyoMotor`s un-boxed, `MyoMotor.java:183` commented out — only the minifilament BACKBONE is actively confined; v2 matches.)
**The SAFETY property — no-op when not binding:** inside ⇒ delta 0 ⇒ accumulators **not touched at all** (no `+=0`, no write) ⇒ adding it to an in-bounds harness is BIT-IDENTICAL. Gate #5: inside ⇒ |force|+|torque|=0 exactly; pushed out ⇒ inward force; **HUGE-box ≡ box-off bit-identical** over 400 dynamic steps (Δcoord/Δfilforce/Δbbforce=0, 0 mismatched bonds).
**7 gates PASS GPU+CPU:** #1 crux (chain-inclusive read), #4 no-motor control, **#5 SAFETY (no-op bit-identity)**, **#6 GENERAL** (a filament seg / motor sub-body / backbone each placed past the +y wall pushed back inward — one kernel, all stores), **#7 box CPU≡GPU** (8-body wall bed straddling all walls: force ΔF=0.0 EXACT, torque ΔT=7.7e-34 N·m on ~1e-18 = float32 FMA last-bit), #2 headline contracts box-ON (A=2.20/B=1.99 pN, avgBound 6.53, peak 3.97), #3 CPU≡GPU full.
**Drift finding (gate #3) — the box is a FAITHFUL QUIESCENT no-op here (report, don't paper over):** matched box OFF vs ON (CPU 50k, identical scene) is **bit-identical on every channel** (tension 2.094/2.094, peak 3.972, avgBound 7.43, |y|max 0.070, |z|max 0.045). **The box never fires in the cap-ON scene:** the free minifilament's residual drift is dominated by the AXIAL (x) direction (backbone center x∈[−0.119,+0.018] µm) where the box is 4.0 µm wide (half-wall 1.995, **17× the drift** — can't tighten it); the lateral Y/Z motion the thin box DOES tightly confine (walls 0.145/0.095) stays at 0.070/0.045 — the **12 pN cap already keeps the minifilament well inside the chamber**. So the box neither tightens the (axial) residual nor perturbs the (within-SEM) match — bit-identical box-on/off = the strongest "no regression." Consistent with the milestone history (the cap, c7a2257, was the dominant steadiness fix; the box was wanted for its own sake as the general primitive). Gate #6 proves it fires the instant a real assay body crosses a wall; a tighter chamber / denser / cap-OFF scene would engage it.
**Regression:** 9 prior harnesses re-run unchanged PASS — `dimer`/`minifil`/`dimerglide`/`miniglide`/`motorbody`/`xbridge`/`stroke`/`motor` + gliding smoke (glides −x, avgBound 6.29). New: `ContainmentSystem`, contractile-harness `-drift` mode + gates #5/#6/#7. Optional next: stronger engagement / multiple minifilaments / 6c nodes (a tighter chamber would make the box load-bearing).

## 2026-06-17 — Increment 6 — MINIMAL CONTRACTILE ASSAY DONE (the first genuinely contractile test). All 4 gates PASS GPU+CPU. A faithful ASSEMBLY of the validated structures + one new device kernel (`PinSystem`); NO new force law, NO new gather. New files only; all systems/stores reused byte-unchanged; production/`GlidingHarness` byte-unchanged; `BoA-v1ref` byte-clean. Report: `INC6_CONTRACTILE_ASSAY_FINDINGS.md`; spec: `INC6_CONTRACTILITY_ASSAY_SURVEY.md`. (Reworked per jba to the GENERAL BIOLOGICAL minifilament model — see below.)
**Scene (the biological minifilament model, faithful to v1 `makeContractilityAssay`):** a central bipolar minifilament backbone (+x) owning 16 dimers (8/end), a **FULLY FREE rigid body undergoing Brownian motion** (backbone + rods + heads; NO pin, NO centering — held only by its bipolar bonds), with **3D radially-splayed heads** (each dimer's splay plane at a distinct azimuthal φ around the backbone ⇒ heads fan out all around it). Two anti-parallel 8-seg filament chains **offset in Y at ±0.05 µm** (v1 `contractFilYOffset`) — filament A (plus +x, pinned +x) at +Y, filament B (plus −x, pinned −x) at −Y, straddling the minifilament. v1 `rodDotFil≥0` sorts polarity ⇒ end2 heads bind only A, end1 only B (both poles engage). ONLY the filament plus-ends are pinned (`PinSystem`, v1 `applyBenchmarkPins`); tension read at those pins.
**Brownian thermal search = the binding enabler (jba's key point, and v1's):** v1's dimer rods are AXIAL (`makeMyosinDimers` — radial offset commented out) ⇒ a head tip reaches only ~(lever+head)≈28 nm perpendicular while filaments sit at 50 nm; the gap is bridged by the bind capture radius + the **Brownian wiggle of rods/heads/backbone** ("thermal search is the essential enabler"). **Freeing the minifilament + 3D splay RAISED the tension ~13× (~0.37→~4.7 pN mean)** vs the earlier (now-deleted) centered/planar version — the model correction was the fix, not a tune.
**THE CRUX (the one correctness item) — chain-inclusive tension read — PASS decisively:** the minifilament binds INTERIOR segments; the force propagates via the chain (F3/F4) to the pinned plus-end. **v2 has NO separate jointForceSum — the v1 GPU `addDeviceJointForce` gotcha CANNOT recur**: `ChainBendingForceSystem.chainForces` + `CrossBridge.segGather` both `+=` into the SAME `fil.forceSum`. Read order: `zeroFil→chain→segGather→CAPTURE pinSeg.forceSum·buildDir (PRE-snap)→integrate→PinSystem.snap`. Gate #1 (controlled): perturb an interior seg 5 links from the pin — chain ON ⇒ pin reads 2.46 pN (purely chain-transmitted), chain OFF ⇒ 0, read SUMS chain+direct cross-bridge exactly.
**4 gates:** (#1) crux above; (#4) no-motor control — pinned tips held EXACTLY (Δ0), steady tension relaxes to 3.3e-4 pN; (#2) **IT CONTRACTS** — both poles engage (avgBound ~3/pole), both anchor tensions net-contractile (A≈+6.6 B≈+2.7 pN, asymmetric — free-body drift), mean ~4.7 pN = **~14000× the no-motor baseline**; (#3) **CPU≡GPU** — (a) deterministic chain+PIN (no-motor, 600 steps) **bit-identical** float32 (coord/pin Δ1.2e-7, forceSum Δ5.4e-17) validates `PinSystem` on device; (b) chaotic full-Brownian path (incl. `brownMot`/`brownBb`) aggregate-agree (bound GPU4/CPU5, Lyapunov-decorrelated).
**Free-body behavior (SURFACED):** the FREE minifilament drifts (~0.1 µm) and engages in BURSTS (peak ~24 pN) — the honest biological behavior; per-pole tension fluctuates + is asymmetric (random drift dir; averages over seeds). Gate is on the long-run NET (both anchors contractile + engage + ≫baseline), NOT a stationary plateau (a free minifilament gives none). Held-bound is intrinsically unstable on a pinned filament (strain can't relax ⇒ dynamic catch-slip release mandatory — v1's reason). Stronger/steadier plateau ⇒ more co-engaged heads (denser field / multiple minifilaments / 6c node) or v1's confining chamber.
**Fidelity:** filaments offset ±0.05 µm (v1), fully free Brownian minifilament (v1), 3D azimuthal head splay (v1 biological model), axial dimer rods (v1), thermal search (v1), dynamic catch-slip (v1). NOT yet ported: v1's confining chamber box (the free minifilament currently drifts — flagged for later). **v1 cross-check:** readout SET reproduced 1:1 (viewer panel = v1 `ThreeJSWriter:262-277`); a tight numeric match deferred (v1 uncalibrated, §8 posture; chamber not yet ported). jba's viewer eye is final.
**New:** `PinSystem` (v1 `applyBenchmarkPins` port), `ContractileAssayHarness`, `run_contractile.sh`. Regression: 6a/6b/dimer-glide/mini-glide all rerun PASS.
**Step-1→4 verification + matched v1 comparison + the 12 pN cap fix (the decisive cut):** (1) model verified — minifilament FULLY FREE (no pin/anchor/spring), ONLY filament plus-ends pinned, tension read at those pins, both poles engage; freeing it + 3D splay raised tension ~13× from the bespoke version. (2) readout panel complete (v1 schema). (3) **Step-3 force-coverage audit** (`-audit`): pinned-seg `forceSum` = chain + gather, residual **0** (frozen-pose), 0 motors on the pin ⇒ pin force purely chain-transmitted; the v1 `jointForceSum`-omission gotcha CANNOT occur. (4) **THE FIX — the 12 pN break-force cap was OFF in v2** (jba spotted numerical stiffness ~frame 220: bound segments tossed around). v1's `MyoFilLink.ckRelease:334` applies it UNCONDITIONALLY before the catch-slip roll (comment: "combat stiffness and force insanity"); v2 had the faithful port but not enabled ⇒ uncapped cross-bridge force on low-drag segments = the stiffness + inflated/bursty tension (~4.7 pN mean, ~24 pN peak, asymmetric). **Enabled it (`setFaithfulRelease`, faithful to v1's always-on cap) ⇒ steady symmetric ~2 pN, peak ~4 pN, stiffness gone.** Matched v1 (BoA-v1ref `/tmp` scratch, CPU 50k) vs v2 (cap ON): avgBound 5.38/6.5, avgTension **1.84/~2.0 pN**, peak **3.32/~4.0 pN**, both symmetric — **v2 ≈ v1 within SEM on every channel**. **Verdict: SHARED FAITHFUL PHYSICS, quantitatively matched** — the dominant missing piece was the cap (not the confining box, the earlier hypothesis). 4 gates re-PASS GPU+CPU with the cap on. Report `INC6_CONTRACTILE_ASSAY_FINDINGS.md` §4/§6b/§7b.
Next within inc 6: optional — port v1's confining chamber box (removes the residual mild drift); stronger engagement / dynamic assembly/`myoMiniLifetime`; then 6c nodes (fresh v1 node snapshot per the recon settledness gate).

## 2026-06-17 — Increment 6 glide part 2 DONE — MINIFILAMENT-GLIDE (the 6b single-ended backbone gather is now LOAD-BEARING). All 4 active gates PASS GPU+CPU. New harness only; `CrossBridge`/`MiniFilamentSystem`/`DimerCouplingSystem` + all stores reused byte-unchanged; production/`GlidingHarness` byte-unchanged; `BoA-v1ref` byte-clean.
**What's new (scale + LOAD):** the full transmission chain bound head→`CrossBridge`→head body→J1/J2→rod→dimer-coupling(6a)→minifilament tether(6b)→`backboneGather` is now load-bearing end-to-end (6b validated the gather isometrically; dimer-glide validated the bind/walk + the gate; this combines them on a pinned filament with the backbone gathering the collective load). THE HEADLINE = `backboneGather`==brute UNDER LOAD, bit-identical.
**v1 verification — NO minifilament-level binding gate:** `BoA-v1ref/.../MyoMiniFilament.constrainEnd1/End2Dimers` (`:436-528`) tether UNCONDITIONALLY (only `cohesionOnDevice()`/`removeMe` skip — device/lifecycle, not binding); `countBoundMotors` (`:317-332`) diagnostic-only. ⇒ the ONLY binding gate in any myosin-structure coupling is the per-dimer `MyosinDimer:276` lever-align (already ported, dimer-glide). No port decision, no pause.
**Geometry (single-polarity engagement, correct physics):** backbone +x at z=0 (FREE, no anchor — tethers hold it); 6b-splayed dimers; a +x filament over the end2 up-head field. The v1 bind predicate's `rodDotFil≥0` admits ONLY end2 dimers' up-heads (rods +x); end1 rods (−x) don't bind a +x filament ⇒ on a SINGLE filament one polarity engages (8/32 heads bind). Genuine bipolar STALL/contraction needs the two-antiparallel-filament geometry (next increment). Two INDEPENDENT single-ended gathers per step — backbone-keyed (`headBackboneSlot`, tether reactions) + segment-keyed (`boundSeg`, cross-bridge reactions), both `CrossBridge.csr*` VERBATIM.
**4 gates (vs `BoA-v1ref`):** (#1) force transmission UNDER LOAD (HEADLINE) — after 200 held-ADPPi steps build tether strain: **backbone gather==brute bit-identical (Δ0)** at load **2.81e-14 N** (>0; ~0 at step 1 before rods displace), **fil gather==brute bit-identical (Δ0)** (32/128 bound), **momentum** |Σmotor+Σbackbone+Σfil|=**9.8e-20 N**, **CPU≡GPU** (300 loaded steps) max|Δmotor|**7.4e-7**/|Δbackbone|**1.1e-7** µm; (#2) **binding gates at population scale** — 16 dimers mixed states, align fires 11 / suppressed 5 (both-bound), all match v1:276; minifilament-level gate NONE; (#3) bipolar collective (emergent, observe) — FREE backbone walks **Δx=+10.85 nm**, sign tracks the mean gathered net Fx, avgBound 8/32 (single-polarity ⇒ walks not stalls); (#5) **all-OFF≡HEAD** — tether-off ≡ dimer-glide path bit-identical (Δ0), control: tether-on differs 1.6e-2 µm. (#4 CPU≡GPU folds into #1; free-DOF FDT inherited from 6b gate D.)
**Regression guard:** `run_dimer.sh`/`run_minifil.sh`/`run_dimerglide.sh` all re-ran bit-identical PASS (no existing source touched). New: `MiniGlideHarness`, `run_miniglide.sh`. Report: `INC6_GLIDE_MINIFIL_FINDINGS.md`. Next: the contractile two-antiparallel-filament geometry (first genuinely contractile test); dynamic assembly; 6c nodes.

## 2026-06-17 — Increment 6 glide part 1 DONE — DIMER-GLIDE (the dimer is now a FUNCTIONAL two-head motor). All 4 gates PASS GPU+CPU. The one new physics = the binding-dependent coupling gate (one-impl, gated). `CrossBridge` reused byte-unchanged; production/`GlidingHarness` byte-unchanged; `BoA-v1ref` byte-clean.
**What's new vs 6a/6b (free heads, gate always-true):** (1) heads bind/walk via `CrossBridge` (additive, unchanged); (2) **binding-dependent gate** — the dimer lever-align is now `boundSeg`-gated: SUPPRESSED when BOTH heads bound (v1 `MyosinDimer.java:276` `!myo1.onFil|!myo2.onFil`; `onFil⟺boundSeg≥0`), else fires; rod-couplings UNCONDITIONAL (verified: line 276 is the ONLY binding gate in the dimer coupling); (3) the full force-transmission chain head→J1→lever→J2→rod→dimer-coupling→partner-rod, end-to-end. Free dimer (NO anchor) translocates.
**One-impl:** `DimerCouplingSystem.couple` gained `boundSeg` + one guard; **bit-identical for 6a/6b** (their boundSeg all FREE_BINDABLE=-1 ⇒ align always fires = pre-glide path). 6a (6 gates) + 6b (5 gates) **re-ran bit-identical PASS** — no forked gated/ungated copy.
**4 gates (vs `BoA-v1ref`):** (#1) force transmission — fil gather==bruteGather bit-identical (Δ0) + momentum |Σmotor+Σfil|=2e-19 N + **CPU≡GPU** 4.4e-8 µm (300 det steps); (#2) **binding gate bit-for-decision** — align fires both-free/one-bound (lever torque rel 4.6e-10 vs v1), suppressed both-bound (exactly 0); (#3) two-head translocation — free dimer walks **+9.38 nm +x toward the actin plus-end** (the Newton reaction to 4b-iii's −x FILAMENT force — the free MOTOR walks opposite to the surface-assay filament glide; emergent, v1 informational); (#5) **all-OFF≡HEAD** dimer-off ≡ single-motor/4b-iii path bit-identical (Δ0), control: dimer-on differs 2.7e-3 µm.
**Sign note:** the free dimer walks +x (plus-end, biological myosin-II direction), NOT −x; 4b-iii's −x is the FILAMENT glide (anchored motors), the free motor is the Newton-opposite. Initial gate sign was backwards, corrected. New: `DimerGlideHarness`, `run_dimerglide.sh`; modified `DimerCouplingSystem`/`MyosinDimerHarness`/`MiniFilamentHarness` (the +boundSeg gate, re-validated). Report: `INC6_GLIDE_DIMER_FINDINGS.md`. Next: minifilament-glide (32 heads + backbone gather under load).

## 2026-06-17 — Increment 6b DONE — myosin MINIFILAMENT (backbone owns N dimers, single-ended one-pass gather). All 5 gates PASS GPU+CPU. The central favorable recon finding realized. New files only; `CrossBridgeSystem` CSR reused VERBATIM (byte-unchanged); production/`GlidingHarness` byte-unchanged; `BoA-v1ref` byte-clean.
**The architecture (recon §2, CONFIRMED — single-ended, LESS than the crosslinker two-pass):** the backbone OWNS its dimers (one consumer, many writers, each dimer keyed to one backbone via `headBackboneSlot`) — same shape as motor→segment (`boundSeg`). (1) `MiniFilamentSystem.tether`: each dimer self-writes the rod-side force+torque into its own rod slot + stores the backbone-side reaction in `miniData` (race-free, no atomics); (2) `CrossBridge.csrHistogram/csrScan/csrScatter` keyed by `headBackboneSlot` **REUSED VERBATIM** (pure int-key ops; `miniCounts[0]=nDimers,[3]=nBackbones`); (3) `backboneGather` sums each backbone's dimers (the `segGather` pattern). ONE pass, no compound key, no new gather machinery.
**Backbone = 3rd `RigidRodBody` instance** (len 0.180, rad 0.005): shared Brownian/integrate/derive/drag run over it UNCHANGED. **Faithful port** of v1 `MyoMiniFilament.constrainEnd1/End2Dimers` (`:436-528`): tether `F=myoMiniFilFracMove·1e-6·strain/(dt·(1/rod.bTransGam.y+1/bb.bTransGam.y))` — plain perpendicular drag, **NO moveCoeff** (simpler than the dimer rod-coupling), at `myo1.myoRod.end1` → axial attach point; align torque `myoMiniFilAlign·…` to ±backbone axis (rest 0). v1 defaults `myoMiniFilFracMove=0.07`, `myoMiniFilAlign=0.01`, `numMyoDimersEachEnd=8`. Attach offset is purely AXIAL (`makeMyosinDimers:393-424`, y=z=0) ⇒ `attach=bb.coord+axial·bb.uVec` (only uVec needed — kept the tether kernel ≤15 args).
**5 gates (vs `BoA-v1ref`):** (A) **gather==brute bit-identical** (Δ0) + tether arithmetic vs independent v1 double-ref **maxRel 3.7e-8** + momentum (gathered + Σrod self-write) 2e-19 N≈0; (B) isometric hold — Brownian-off exact fixed point (8.9e-8 µm), Brownian-on bounded thermal (2.8e-2 µm, soft tether jiggle, no fly-apart); (C) **CPU≡GPU** det max|Δmotor|4.5e-6 / |Δbackbone|5.4e-7 µm (float32 last-bit); (D) FDT self-consistency — stationary (halves Δ2.2%) bounded (dt is a physics param, no dt-independent ½kT — cf §6a-thermo); (E) **all-OFF≡HEAD** tether-off ≡ bare 6a dimer-bed bit-identical (Δ0).
**TornadoVM:** tether math inlined in the top-level kernel (6a 600-node cap pattern); csr* reused as `addSingle` (WorkerGrid1D(1)) tasks. New: `MiniFilamentStore`, `MiniFilamentSystem`, `MiniFilamentHarness`, `run_minifil.sh`. Report: `INC6B_MINIFILAMENT_FINDINGS.md`. Next: glide integration (heads bind→force through the structure; recon check #4) + dynamic assembly; 6c nodes (fresh v1 snapshot).

## 2026-06-17 — Rotational-thermostat equipartition diagnostic (post-6a) — the gate-D 1.40× RESOLVED; rotational foundation CLEAR ⇒ 6b proceeds. Diagnostic only (no production fix); `BoA-v1ref` byte-clean; new file `ThermostatDiag.java` (committable instrumentation).
**Question:** 6a gate D found the dimer lever fluctuation at 1.40× the AR(1) ½kT estimate. The dimer lever is the FIRST rotational DOF anchored to equipartition ⇒ is v2's rotational thermalization at ½kT, or off ~1.4× (which would silently bias EVERY rotational DOF incl. the gliding assay — pass v1-matching, fail physics)? Cleared before 6b stacks a backbone + N levers.
**Cut 1 (DECISIVE) — thermostat at ½kT.** `DiffusionHarness` Config R (already present): free-rod rotational diffusion D_rot=18.28 vs FDT `kT/bRotGam`=18.61 rad²/s ⇒ **−1.8% (≈1.0×, NOT 1.4× over)**; translational control clean (−2.5%/−1.2%/+0.1%). The Brownian amplitude is FDT-consistent BY CONSTRUCTION (`randTorque=rScale·√(2kT/dt)·√(bRotGam)·g` ⇒ injected per-step angular MSD = 2·(kT/γ)·dt) and the integrator accumulates it faithfully. ⇒ NO upstream rotational-thermostat miscalibration.
**Cut 3 — confined rotational DOF at the EXACT discrete equipartition.** A single rod (Brownian ON) held to a fixed rest by the SAME fracMove torsional law (isolates the scheme from the lever's indirect drive + gate-D AR(1) crudeness): `meas/predDiscrete=0.992`, predDiscrete=`4kT·dt/(γ·c(2−c))` the exact discrete-AR(1) steady state (2 transverse DOF, per-step decay c=coeff=0.4). The naive continuum `2kT/k_θ` under-predicts by `1/(1−c/2)=1.25×` — EXACTLY the apparent 1.24×, a discrete-vs-continuum AR(1) correction NOT a thermostat error.
**Cut 2 — dt-scaled but exactly the scheme's own equipartition at each dt.** `⟨θ²⟩∝dt` (4.6e-3/9.2e-3/1.8e-2 at dt 5e-6/1e-5/2e-5), yet meas/predDiscrete≈1 throughout (0.996/0.992/0.987). The fracMove relaxation is NOT a fixed-stiffness spring (`k_θ=coeff·γ/dt`) ⇒ its equilibrium amplitude is dt-set (SCHEME-RELATIVE, shared by the §8 crosslinker which samples its OWN Boltzmann), but is EXACTLY the scheme's discrete equipartition at each fixed production dt. No dt-independent ½kT-physical anchor exists for a damping-limited DOF; FDT self-consistency is the right test and it holds.
**READ:** thermostat at ½kT (Cut 1) + confined rotational DOF at exact discrete equipartition (Cut 3, 0.992) ⇒ the gate-D **1.40× = the 1.25× discrete-vs-continuum AR(1) factor × residual gate-D crudeness** (lever Brownian-OFF ⇒ indirect drive; gate-D σ² measured align-off ≠ ρ measured align-on). **Benign — NO thermostat fix; rotational foundation CLEAR ⇒ 6b proceeds.** Report: `INC6A_DIMER_FINDINGS.md` §6a-thermo. New: `ThermostatDiag.java` (run `softbox.ThermostatDiag`).

## 2026-06-17 — Increment 6a DONE — myosin DIMER coupling (two motors, no-gather self-write) on an isometric bed. All 6 gates PASS GPU+CPU. The SIMPLEST of the three myosin-structure couplings (recon §2). New files only; production/`GlidingHarness` byte-unchanged; `BoA-v1ref` byte-clean.
**The architecture (recon §2, confirmed):** a dimer is a 1:1 pairing of two motors; each rod/lever sub-body belongs to EXACTLY one dimer ⇒ the dimer computes its reaction once and **self-writes both sides directly into its two uniquely-owned slots — NO CSR gather, no atomics, no KernelContext** (contrast the motor→segment single-ended gather + the crosslinker two-pass). Disjoint pairing `motorA(d)=2d`, `motorB(d)=2d+1` ⇒ one writer/slot.
**Faithful port (component-port; v1 = per-component oracle):** force law = the validated PAIRS spring (`moveC` reused VERBATIM from `MotorJointSystem`), 4 rod-coupling variants (v1 `applyRodCouplingEnd1/2/End1End2/End2End1`, `MyosinDimer.java:163–273`; parallel=End1+End2, antiparallel=End1End2+End2End1) + lever-align torque restoring **160°** (`alignUVecLeversTorque:111–135`; `leverAngle:9`). v1 defaults `myoDimerFracMove=0.2`, `myoDimerLeverFracMoveTorq=0.4`. Lever-arm = full `0.5·rodLen·(±uVec)` (NO fracR, unlike the joints). Heads FREE ⇒ v1's off-fil align gate always true.
**6 gates (vs `BoA-v1ref`, co-developed not fixtures):** (A) **force arithmetic** isolated vs an independent **double** reference (literal v1 acos+sin moveCoeff) — **maxRel 6.6e-8**, rod force exactly equal-opposite (\|F_A+F_B\|=0); (B) **rest hold** — the Y-shape rest (coincident rods, levers 160°) is an EXACT Brownian-off fixed point (gap 6.8e-9 µm, ang 160.0000°, COM fixed); (C) **relaxation + dt-invariance** — displaced rod decays, dt-invariant to 8.4e-7 (the `/dt`↔integrator cancellation, like 5a); (D) **lever angle** Brownian-on — **stationary** (halves Δ0.015°), **bounded** (std 8.6°), **FDT-thermal-scale** (measVar/predVar 1.40, ρ0.849); mean 152.6° is a fluctuation shift of the bounded θ∈[0,180] coord below the 160° rest (B proves 160° is the exact fixed point ⇒ not a sign drift); (E) **CPU≡GPU** — deterministic 3.5e-6 µm (float32 last-bit), Brownian aggregate Δ0.000°; (F) **all-OFF≡HEAD** — bare two-motor path bit-identical (Δ0), control: coupling-off lever drifts to 106° ⇒ the coupling IS what pins 160°.
**§8 posture applied to D:** v1's structures were never experiment-calibrated ⇒ v1 is the component-port oracle (gate A) but NOT a quantitative oracle for the emergent lever-angle distribution; D is gated on FDT self-consistency (physics), not v1's number. fracMove subtlety: deterministic per-step decay is dt-independent, Brownian noise/step ∝ dt ⇒ fluctuation amplitude is scheme-relative; hence the **measured** σ²/(1−ρ²) FDT check, not a derived-½kT match.
**TornadoVM (load-bearing for 6b):** the rod-link math must be INLINED into the top-level @Parallel kernel — a helper with 2× inlined `moveC` hits the **600-node inlined-callee cap** (`TornadoInliningException`, 602>600); the kernel itself has no cap; only small `moveC`/`accurateAcos` are inlined.
New: `DimerStore`, `DimerCouplingSystem`, `MyosinDimerHarness`, `run_dimer.sh`. Report: `INC6A_DIMER_FINDINGS.md`. Next: 6b minifilament (backbone owns N dimers; single-ended `CrossBridge` CSR gather, backbone-side).

## 2026-06-17 — Increment 5c-iii Phase 2 RESIDUAL RESOLVED — crosslinkers PHYSICALLY VALIDATED (equipartition/FDT). Both ~3.5×-gap channels are v2-correct / v1-deviation; v1 is NOT a quantitative crosslinker oracle. ACCEPT v2, no production fix. (Diagnostic + harness only; production/`CrosslinkerSystem`/`CrosslinkerStore`/`GlidingHarness` byte-unchanged; `BoA-v1ref` byte-clean.)
**New planner posture (jba):** v1's crosslinkers were **never calibrated to experiment** ⇒ v1 is a faithful **component-port** reference but **NOT a quantitative oracle** for crosslinker *emergent* behavior. So the gap is adjudicated against **first-principles physics**, not v1's numbers. Added the **component-port vs emergent-quantitative** distinction to CLAUDE.md (Oracle posture / carry-forward).
**Part A — ROOT #1 (formation, §7.5) CLOSED; calibration DISSOLVED.** v2's one-draw-per-distinct-crossing is the correct model; v1's ~1.9× is a mesh-binning artifact (P_form drawn per mesh-VISIT). v1 was never tuned to an experimental density ⇒ **nothing to recover** ⇒ do NOT import the multiplicity, do NOT compensate `xLinkOnRate`. Dissolved, not deferred. The parked ≈49 plateau is **reframed** as a future v2 self-consistency / physical-plausibility check (formation≈dissolution at confinement), NOT a "hit 49" target.
**Part B — ROOT #2 (retention) adjudicated by PHYSICS — v2 at equipartition; v1 is the deviation.** KEY: the crosslinker spring is a **central conservative force** (magnitude depends only on link length L) ⇒ FDT-consistent overdamped dynamics MUST sample the **Boltzmann distribution of its own potential**, `P(L) ∝ L²·exp(−U(L)/kT)`, `U(L)=∫f(L')dL'`. Built a single-link 2-rod harness (`-singlelink`) with an in-code Boltzmann predictor + a ladder isolating thermostat from geometry:
- **B2** (Brownian OFF, deterministic relaxation): k_decay=0.00633/step (τ=158 steps); **CPU≡GPU bit-identical** (max|Δcoord|=4.66e-10 µm ≈0.5 ULP).
- **B1a — DECISIVE** (ISOTROPIC drag, ON-COM, no rotation — the clean control): measured ⟨strain⟩ **1.132/1.162** (2 seeds) vs Boltzmann **1.130**, **ratio 1.001/1.028**; histogram tracks `L²exp(−U/kT)` bin-for-bin. ⇒ v2 injects EXACTLY the FDT/equipartition thermal energy. **Thermostat correct.**
- **B1b** (anisotropic drag, ON-COM) ≈ B1a (1.139 vs 1.132) — **confirms drag-independence** (equilibrium depends only on U,kT — a 2nd correctness sign).
- **B1c** (off-COM + rotation ON, realistic) ⟨strain⟩ **~0.93** ≈ the assembled-bundle v2 ~0.89 (§7.6) — rotation relieves strain (physical configurational effect).
**The read (decision matrix):** v2 sits AT the thermal equilibrium of the (uncalibrated, thermally *soft*) v1 force law (~1.13 ON-COM / ~0.93 realistic); **v1's bundle strain ~0.42 is FAR BELOW it ⇒ v1 is the sub-thermal outlier, not v2** (plausible v1 origin — multi-segment short filaments / links break before thermalizing — NOT root-caused, v1 non-oracle). ⇒ **v2 matches equipartition ⇒ ACCEPT v2, NO production fix, crosslinkers physically validated.** No shared-scope bug (B3 not needed). The whole ~3.5× gap = {a v1 formation artifact we decline} × {v1 colder than the shared force law's equilibrium} — **v2 is the more physics-faithful model in BOTH channels.** Next: 5d (Arp2/3). New: `CrosslinkerBundleHarness.{runSingleLink,buildSingleLink,boltzmannPredict,boltzmannHist}` + `-singlelink`. Report `INC5C-iii_PHASE2_FINDINGS.md` §8.

## 2026-06-16 — Increment 5c-iii Phase 2 RESIDUAL ROOT-CAUSED (diagnostic pass, no production changes) — the ~3.5× gap = TWO multiplicative channels, NEITHER the admission cap: (1) formation ~1.9× = a v1 mesh double-DRAW artifact; (2) retention ~2× = v2 links carry ~2.1× strain → ~2× Bell breaks. Cap EXONERATED; rotational diffusion MATCHES.
**The reframing (`-nounbind` decomposition, 6-seed matched-IC @ step1400):** v1 net ~22, v2 GROSS (`-nounbind`) ~14, v2 net ~4.7. So the gap splits into **formation** (v2 gross 0.45× v1) × **retention** (v2 keeps 34% vs v1 71%) ≈ 0.22 ≈ observed. v1's logged `filLinkCt` is net-active (swap-remove decrements) ⇒ v1's near-monotonic rise is genuine accumulation. **Both channels real, comparable, multiplicative — the §4.2 "time-evolution of the crossing population" framing was incomplete (it saw only the formation half, and compared v2's deduped count against v1's mesh-INFLATED raw count).**
**Cut 1 (admission cap) — EXONERATED.** Instrumented per-event cap-specific drops: at the fixture conc=1, **0 cap drops** over the full 6000-step run (cum gatePass=50, capDrop=0); 2.8% even at 3× conc. gatePass ~1/event over 200 segments ⇒ same-segment contention ≈ 0 (matches the 5c-ii 0.93% self-check). Per the prompt, drops ~nil ⇒ **Cut 1b skipped** (relaxing a non-binding cap is a no-op). The 5c-ii one-per-seg admission stands; no race-free multi-admission needed.
**Cut 2 (rotational diffusion) — MATCHES.** xLinks-off C(t)=⟨u(t)·u(0)⟩ over 1400 steps: v1 1.000→0.914 (D_rot~0.321/s) vs v2 1.000→0.909 (~0.341/s) = **~6%**, like translational (§3). Not the "v2 rotates faster" upstream-seed failure; no pause. (aeta scales both bTransGam+bRotGam ⇒ FDT-consistent.)
**Cut 3 (alignPass/distPass) — v2's distinct crossings MATCH v1; v1's RAW distPass is inflated ~1.9×.** Added a v1 distinct-pair dedup: coarse multiplicity ~1.0–1.3 (mild) but **distPass multiplicity ~1.9×** (close/distance-passing pairs share multiple mesh cells ⇒ visited ~2×). **v1 distPassDistinct ~10 ≈ v2 geom ~11.5** — crossing populations MATCH; v2 NOT crossing-deficient. The §4.2 "v2 distPass ~half v1" was v2-deduped-vs-v1-raw (apples-to-oranges).
**ROOT #1 (formation): v1 mesh multi-visit ⇒ multiple P_form DRAWS per crossing.** v1 runs an independent `rng<P_form` draw per mesh-VISIT inside checkToLink, so a crossing visited ~1.9× gets ~1.9 draws ⇒ effective per-crossing form ≈1−(1−0.0952)^1.9 ≈ **0.17** vs v2's faithful one-draw **0.095**. A v1 IMPLEMENTATION ARTIFACT (formation prob set by mesh binning, not P_form alone); v2's one-draw-per-crossing is the MORE correct model. Decisive lever: boosting v2 to `-conc 3` (≈v1 effective rate) only reaches 11.2 (vs v1 22.5) — retention still binds.
**ROOT #2 (retention): v2 links carry ~2.1× the strain.** Direct measurement (6-seed, steps 600–1500): v2 mean active-link strain **~0.89** vs v1 **~0.42** ⇒ P_break ratio ~2× (k_off=1+exp(2·strain)) ⇒ v2 ~46–113 breaks/run vs v1 ~0–9. NOT diffusion (matched), NOT torsion (`-notorsion` unchanged), NOT cap, NOT density/feedback (gap persists at matched LOW link count: v1 nActive=4→0.31 vs v2 links=2→0.91; v2 `-conc 3` with 6–17 links stays ~0.6–1.1). Localized to the **single-link Brownian-driven steady-state strain** — the translational link-force+integrator relaxation of an OFF-COM attachment under Brownian forcing. **5a validated only PURE decay (Brownian OFF) to 0.0012%; the Brownian-driven steady strain that GOVERNS unbinding was never checked.**
**Read (interpretation matrix): branch 3** (neither cap nor diffusion → deeper), but narrowed into the two-channel decomposition above. **Decisive next cut (named, NOT run):** co-developed single-link Brownian steady-state strain check (identical 2-rod IC, OFF-COM attachment, Brownian ON, v1 vs v2; measure steady strain + attachment rotational relaxation) — extends 5a into the unbinding-governing regime. **Recommended fix path (planner):** (1) accept v2's faithful one-draw-per-crossing as more correct, document the ~1.9× mesh-multiplicity offset from v1's inflated count (do NOT import the artifact); (2) run the named single-link Brownian-strain check — the load-bearing half that doesn't close by boosting formation; if a rotational-relaxation discrepancy surfaces that's the production fix, else it's the rigid-rod remainder (accept, like §6.7). Plateau ≈49 stays parked for the membrane increment.
**Diagnostic only — production byte-unchanged** (only `CrosslinkerBundleHarness.java` gained default-off toggles `-straindiag`/`-rotdiff`/`-notorsion`/formdiag-CAP; `CrosslinkerSystem`/`CrosslinkerStore`/`GlidingHarness` untouched). `BoA-v1ref` byte-clean (all v1 edits in `/tmp/v1xlink`: `[XSTRAIN]`/`[ROTDIFF]`/distinct-pair counters + `boa-xlink-noform` config). Report: `INC5C-iii_PHASE2_FINDINGS.md` §7.

## 2026-06-16 — Increment 5c-iii Phase 2 — assembled moving bundle BUILT + STABLE (both runners); dominant v1↔v2 confound (aeta viscosity 10×) FOUND+FIXED; residual ~3.5× link-count gap SURFACED+PAUSED. Crosslinker physics validated faithful.
**Built.** `CrosslinkerBundleHarness` — the full per-step crosslinker loop (formation↔force/torsion↔unbind↔integrate) over a many-filament bundle of free rods (no walls/motors/chain), both runners. **Per-step order faithful to v1** (`BoxOfActin.doLoop`+`FilLink.enforceFilLink`, read from BoA-v1ref): zero→brownian→[every checkInt=100] formation→unbind(ckLinkBreak, every step, BEFORE force — v1 returns early on a break)→countActive(dynamic fracMove)→linkForces→torsion→2-pass gather→integrate→derive. **Ordering reconciliation:** the prompt said "force→unbind"; v1's actual order is unbind-before-force (ckLinkBreak is the first stmt in applyTransForce) — matches the existing v2 5b convention. New: `run_xlinkbundle.sh`. `BoA-v1ref` byte-clean (all v1 edits in a `/tmp/v1xlink` scratch); production/`GlidingHarness` byte-unchanged.
**Part 1 — STABLE both runners.** CPU 200 fil × 6000 steps: finite, bounded force, no blow-up. GPU mechanics graph (16 kernels, < gliding's 23 ceiling) on 200 fil × 3000 steps: stable + CPU≡GPU aggregate (spread rel-diff 0.000%). Per-kernel CPU≡GPU bit-identity already validated (5a–5c-iii); assembled loop = composition of validated kernels + inc-1 integrate/derive.
**The dominant confound — `aeta` (FOUND+FIXED).** The fixture sets `aeta:1.0` Pa·s (crowded "in Bug" viscosity), **10× the v2 `Constants.aeta=0.1` default**. Drag γ∝aeta ⇒ D∝1/aeta: v1's bundle stays packed, v2 (aeta 0.1) over-diffused 10× and dispersed → starved formation. Diagnosed via a matched-IC walls-off v1 scratch (Chamber gated off by `-DwallsOff`; IC dumped + loaded into v2): the formation **gate MATCHES** on the identical config (v1 distPass=11 vs v2 geom-pass=12 — earlier mismatch was Brownian divergence), and pure-diffusion isolation pinned the 10× to `aeta`. **Fix:** `applyAeta()` scales the drag tensors (FDT-consistent — Brownian kick derives from bTransGam); NOT a physics change (inc-1 diffusion unchanged). Post-fix v2 pure-diffusion matches v1 (0.237→0.270 vs 0.241→0.269 @ step 4000).
**Part 2 — confinement-free transient (post-fix).** (2.1 dispersion window) usable window exists — bundle disperses slowly (spread 0.25→0.31/0.6 s), proceed. (2.2 matched-IC, 6-seed ensemble @ step 1500) **v1 22.5±1.3 vs v2 6.5±1.0 — a ~3.5× gap, NOT within SEM → SURFACED + within-SEM claim PAUSED for the planner.** Excluded as the cause: formation gate (matches), translational diffusion (matches post-aeta), unbinding (same Bell + same every-step `ckLinkBreak` cadence — v1 instrumented), conc-scaling (faithful). Residual sits in the **time-evolution of the crossing population** (v1 holds distPass~12–17; v2 declines ~12→6) — a subtle coupling effect (candidates: rotational de-alignment rate, single-rod vs v1 representation, the one-per-seg admission cap at density). Not root-caused to one component; recommended next: matched-config alignPass/distPass trajectory overlay + cap-relaxed A/B. (2.3 conc-scaling) **PASS — halving xLinkConc halves formation (9.0→4.5 = exactly 2.0×, P_form ratio 1.95).**
**Part 3 — demo.** `-3js` emits filament segments + each ACTIVE crosslinker as a thin bond into the verbatim viewer schema (notADPRatio encodes parallel/antiparallel); 308 frames, no viewer fork.
**Parked.** Confined absolute plateau (≈49) parked for the boundary/membrane increment (v2 lacks Chamber confinement). The residual ~3.5× walls-off gap is OPEN/PAUSED. Crosslinker *physics* (gate, conc-scaling, force, unbinding) validated faithful; the residual is in the assembled coupling, likely upstream/shared (not the crosslinker code). Ensemble ICs+trajectories in `RUN_LOGS/v1_ic_seed{1..6}.csv`/`v1_traj_seed{1..6}.txt`. Report: `INC5C-iii_PHASE2_FINDINGS.md`.

## 2026-06-16 — Increment 5c-iii Phase 1 — DONE: dynamic fracMove + torsion (default-ON) ported, analytic gate green. Next: Phase 1.5 (v1-bundle setup cost) → Phase 2 (running-v1 plateau).
**Built (Phase 1, the cheap analytic gate).** Full v1 `FilLink` force now live: (1) **dynamic `fracMove = 0.4/max(getLinkCt(segA),getLinkCt(segB),1)` recomputed per step** — v1's count-keying read exactly (`applyTransForce:206`, the MAX of both segments' counts — unambiguous, **no pause**); 5a's `linkForces` already had the formula, 5c-iii feeds it the per-step `activeLinkCount` (existing `countActiveLinks`) instead of the static count, covering count UP (formation) and DOWN (death — **absorbs 5b's deferred fracMove-on-death**). (2) **Torsion ported because it's ON by default** — v1 `Parameter` defaults active=true and `filLinkTorqSpring` (1e-19 N/rad) is constructed active ⇒ `applyForces` runs `applyTorsionForce`. New `CrosslinkerSystem.linkTorsion`: axis unit(uA×uB)[∥]/unit(uA×−uB)[anti], angTween fastAcos(dot)/|fastAcos(dot)−π|, magnitude filLinkTorqSpring·angTween over a **5-slot ring** (v1 ValueTracker(filLinkForcesToAve=5)), +T/−T added to the seg-side torque payload (gathered), v1 `checkPt3D` guard (skip exactly-parallel |cross|=0 — also the §5c-ii degenerate-geometry guard) ported, PTX-safe `fastAcos` (accurateAcos middle). New store fields torqueMagHist/Place (reset on formation in placeOrient) + torsionParams + linkOrientSame (already persisted 5c-ii). Near-parallel **translational** force is blow-up-safe by construction (forceVec=curForceMag·linkVec, linkVec NOT normalized — v1 verbatim). `BoA-v1ref` byte-clean; production byte-unchanged; default-off.
**Phase 1 validation (analytic vs v1; all PASS GPU+CPU).** Dynamic fracMove vs v1 `applyTransForce`: 3-link central fil (0.4/3) then a death (0.4/2) → force matches v1 to **rel 5.26e-8** (count up+down). Torsion vs v1 `applyTorsionForce` (parallel+antiparallel, 5-ring, step-for-step): **rel 5.45e-8**. **CPU≡GPU bit-identical** (force+torsion+gather, off-centre links, 50 steps: ΔforceSum=ΔtorqueSum=0). all-OFF≡HEAD: torsion-OFF ≡ translational-only (no-op when disabled); torsion-ON contributes; all 5a/5b/5c-i/5c-ii gates reproduce.
**De-racing divergence (documented).** v1 `getLinkCt` accumulates within-step as links register ⇒ multi-link `fracMove` is order/thread-dependent; v2 uses the deterministic TOTAL active count (the intended converged value) — §6.12-family de-racing, single-link bit-exact to v1.
**Phase 1.5 (v1-bundle setup gate) — v1 oracle CHEAP & CAPTURED; v2 matched bundle is the large piece → PAUSED for the planner.** v1 ships `ParameterFiles/boa-xlink-dense-nomotor` (200 short fils, 0.7³ box, no motors, static turnover, formation+unbinding+force live; mode 0, maxXLinkBondAngle 0.6rad≈34°, filLinkTorqSpring active). `/tmp/v1xlink` scratch (BoA-v1ref BYTE-CLEAN) + 1-line `FilLink.filLinkCt` log after `updateCounters()`, CPU path (`@tornado-argfile`, no -gpu) → **clean plateau ≈ 49 links/200 fils, stable from t≈0.6s** (19→36→46→48→**49**…50 over 2s; ~few min/seed). **The deferred running-v1 oracle is now in hand.** Cadence: biochemDeltaT 0.01/deltaT 1e-4 ⇒ crosslinkCheckInt=100 ⇒ dtCheck=0.01 ⇒ **P_form≈0.0952** (every 100 steps). **But the v2 side is the large remaining piece:** v2 has NO moving many-filament crosslinked bundle, and a matched comparison needs (1) **box confinement — a force v2 LACKS** (v1's Chamber F1 keeps density; without a matched wall the density→plateau comparison is confounded — a real missing-physics decision, not silent wiring), (2) the combined moving loop assembled (unvalidated dynamic force↔formation↔unbind coupling — stability risk), (3) matched random IC/density. Per the gate's "don't improvise a big harness; the planner decides how to source the oracle" → **Phase 2 PAUSED.** Recommendation: (A) authorize the v2 moving-bundle build, scoping the confinement model; or (B) accept Phase 1's analytic closure + a v2-only self-consistency plateau (formation≈dissolution + halve-xLinkConc scaling — no v1 absolute-count/confinement match needed); or (C) other sourcing. Phase 1 committed+green; v1 target (≈49) captured for whichever path. Findings: `INC5A_CROSSLINKER_FINDINGS.md` (§5c-iii Phase 1.5).

## 2026-06-16 — Increment 5c-ii — DONE: crosslinker formation (broad-phase FIL×FIL + checkToLink gates + P_form + one-per-seg admission), validated green. Cap NON-BINDING (0.93% upper bound). Next: 5c-iii (force law + running-v1 steady-state).
**Built.** Real formation filling the 5c-i request arrays (allocator UNCHANGED). Pipeline/step (after 5b unbind): `filFilCandidates` (broad-phase FIL×FIL) → `formGates` (per-candidate-local: alignment + lineSeg-distance + orientSame + P_form) → `formAdmitReduce`/`countActiveLinks`/`formAdmit` (one-per-seg admission) → 5c-i allocator → `placeOrient`. Both runners, localWork=64 on the RNG/gate kernels. **Analytic-oracle only** (gate-by-gate vs v1; running-v1 bundle stays DEFERRED to 5c-iii). Default-off (pForm=0). `BoA-v1ref` byte-clean; production byte-unchanged. New: 6 CrosslinkerSystem kernels + formation block in CrosslinkerStore + 5c-ii checks in CrosslinkerHarness.
**Admission (planner decision, as specified).** Geometry/RNG gates per-candidate-local (race-free). Saturation+spacing cross-candidate ⇒ **cap one new link/segment/step** via a deterministic per-segment **min-candidate-index reduction**: c admitted iff it's the min gate-passing candidate index for BOTH its segments AND both pass saturation (count<maxXLinksOnSeg) AND spacing (≥minSep from every existing link) — all vs start-of-step state. ≤1 new link/seg/step ⇒ saturation+spacing EXACT with no same-step cross-candidate dependency (no parallel-greedy machine). Single-thread min-reduce + parallel read-only admit ⇒ race-free, bit-identical CPU↔GPU. Deliberate non-binding divergence from v1 (admits all in scan order) — self-checked (below).
**Faithfulness discoveries.** (1) **`Math.acos` does NOT lower on the PTX backend** (`emitReinterpret unimplemented`) → `fastAcos`'s middle branch swapped to the PTX-proven `accurateAcos` poly; the default `maxXLinkBondAngle=π/12` lands the threshold in the |dot|>0.95 **sqrt** branch (ported verbatim ⇒ bit-exact), and the middle only decides far-misaligned pairs (angle≥18.2°>15° ⇒ fail either way ⇒ decision-bit-exact). (2) **Firing cadence ported** (not ambiguous, no pause): fires every `crosslinkCheckInt`=biochemDeltaT/deltaT=10; `dtCheck=deltaT·10=1e-4`; `P_form=1−exp(−10·1·1e-3)=9.995e-4`. (3) v1's `lineSegmentIntersectTest` is degenerate for exactly-parallel segs (parallel guard → no collision) and ill-conditioned near-parallel ⇒ v1 forms at near-parallel **crossings** (interior closest-approach); test scene is a crossing bundle (xy-tilt × z-stack) accordingly; the parallel-guard component-wise `<1e-20` quirk ported verbatim. RNG salts XLFP/XLJ1/XLJ2 (distinct from break XLKB + motor salts); loc-jitter magnitude faithful (±minFilLinkSep), specific value diverges from v1 MT (established RNG-divergence posture). `orientSame` persisted to `linkOrientSame` (forward-compat with deferred torsion); allocate unchanged.
**Validation (numbers; `./run_xlink.sh`, all 6 PASS GPU+CPU).** #1 broad-phase (8fil×2seg, nCand=112): set==host-enum, unordered/distinct, **same-fil excluded**, complete superset ✓. #2 gate arithmetic vs v1 checkToLink (modes 0/1/−1, **828 candidates** spanning both gate boundaries): **0 decision mismatches** ✓. #3 P_form: formula Δ=0.000%, empirical fire fraction @pForm=0.30 = **0.29970 (Δ=0.101%)** ✓. **#4 contention self-check (36-fil dense focal bundle, default params, 20k steps): gate-passers=3657, contention=34 seg-steps, cap-dropped=34 = 0.93% of would-be formations ⇒ cap NON-BINDING** (contention∝N²·P_form²; this near-worst-case focal density is an UPPER bound — realistic distributed crossings ≪ this; no pause). #5 CPU≡GPU full pipeline (20-fil, 400 churn steps): formed-link sets (state+payload+orientSame) **0 mismatches** ✓. #6 all-OFF≡HEAD (pForm=0 ≡ 5b/5c-i path, 0 mismatches; 5a/5b/5c-i gates reproduce) ✓.
**Carry-forward.** Running-v1 oracle + `fracMove`-on-count still **DEFERRED to 5c-iii**. Report: `INC5A_CROSSLINKER_FINDINGS.md` (§5c-ii appended).

## 2026-06-16 — Increment 5c-i — DONE: Design-A scan-rank free-list link allocator in isolation (synthetic driver), validated green. Design A CONFIRMED (existing links never move). Next: 5c-ii (broad-phase + checkToLink gates + P_form).
**Built.** The Design-A allocator (free-list build + request rank + allocate + overflow clamp) + a synthetic deterministic driver, wired into both runners with the **death→free-list→allocate** phase order. **Allocator bookkeeping only** (a formed link need not produce a correct force yet — OUT: broad-phase/`checkToLink`/`P_form`=5c-ii, force law/`fracMove`-on-count + running-v1=5c-iii). Default-off (K=0). `BoA-v1ref` byte-clean; production/`GlidingHarness` byte-unchanged. New: `CrosslinkerSystem.freeFlags/freeScatter/allocate` + formation block in `CrosslinkerStore` (freeList/offsets/counts + request/rank arrays) + 5c-i checks in `CrosslinkerHarness`.
**Allocator (Design A, as specified — no redesign).** Reuses the validated single-threaded `CrossBridgeSystem.csrScan` prefix-sum **VERBATIM for BOTH** prefix sums: (1) free-list = `freeFlags`(freeCount[s]=linkState[s]<0?1:0) → `csrScan`(→freeOffsets, [C]=nFree) → `freeScatter`(stream-compaction: freeList[freeOffsets[s]]=s, index order); (2) rank = `csrScan`(acceptFlag→rankOffsets, [K]=nAccepted); (3) `allocate`: request rank r claims freeList[r], writes payload, inits FRESH strain ring, flips linkState FREE→ACTIVE — distinct ranks→distinct free slots ⇒ **one writer/slot, race-free, no atomics/KernelContext**; (4) clamp `if(rank>=nFree) continue`. Index-ordered free-list+ranks ⇒ fully deterministic assignment, **no sort** (that pause condition didn't trigger). The flag+compaction-scatter companions are the standard prefix-sum-compaction idiom (single-threaded/index-ordered, bit-identical CPU↔GPU like csrScatter); the bit-identical-critical SCAN is reused, not reinvented.
**Design-A invariants — all hold; Design A CONFIRMED (no kick-back to Design B).** **Existing links never move** (slot-stability check: allocate never overwrites a slot ACTIVE-before-it, 300-step churn — PASS); **no compaction ever needed**. Gather loop bound already iterates `counts[0]=C`(capacity) so no change there; only the §5b `if(active)` guard. One necessary companion: **`linkForces` gained a 1-line hole-skip** (`if(linkFilA<0) continue`) — Design A introduces never-used FREE slots with key −1 and linkForces indexes `filEnd1[key]` (would OOB at −1); this is an OOB-safety guard on the force-compute kernel (NOT a gather change), bit-identical to 5b on all-active/dead-but-keyed scenes.
**Phase order + v1 comparison.** Step runs unbind/death(5b)→build→rank→allocate, so a same-step 5b death frees a slot the same-step formation reuses (check #3). **v1** forms at collision(start)/frees at cleanup(end) via `setInactiveFilLinks` ⇒ a v1 death is reusable step N+1; **v2-5c-i does same-step reuse** (die-then-form in one pass) — the planner-specified SoA ordering; shifts *when* a slot is reusable by ≤1 step, not lifecycle correctness.
**Validation (synthetic / self-consistency — no v1 oracle; `./run_xlink.sh`, all 7 PASS GPU+CPU).** #1 distinct-slot/no-double-alloc + #2 free-list correctness (empty pool K=8: free-list=FREE in index order nFree=64; 8 distinct ACTIVE, payloads land, fresh rings); #3 death→same-step reuse (first @slot14 step11, fresh ring+this-step payload) + #5 slot-stability (every step); #4 overflow clamp (nFree=2,nAccepted=5 → exactly 2 form lowest-rank, 3 not-formed, no OOB); **#6 CPU≡GPU bit-identical (C=32,K=6,400 churn steps → 0 field mismatches** across slot assignments+payloads+strain rings+free-list+ranks); #7 all-OFF≡HEAD (K=0 ≡ 5b unbind-only path, 0 mismatches; all 5a/5b gates reproduce).
**Carry-forward.** 5c-ii replaces only the synthetic `fillRequests` (broad-phase FIL×FIL + checkToLink gates + P_form RNG fill the same request arrays; the allocator rides underneath unchanged; RNG kernel ⇒ localWork=64). `reqLoc2` carried a synthetic fingerprint (step·1000+r) to verify landing. Running-v1 oracle + fracMove-on-count still **DEFERRED to 5c-iii**. Report: `INC5A_CROSSLINKER_FINDINGS.md` (§5c-i appended).

## 2026-06-16 — Increment 5b — DONE: Bell-model crosslinker unbinding + link-lifecycle death half (sentinel field + one gather guard), validated green. Next: 5c (formation/birth + broad-phase + the running-v1 steady-state oracle).
**Built (on 5a's pre-placed scene).** `CrosslinkerSystem.unbind` (faithful v1 `FilLink` port: strain register + `ckLinkBreak`) + the lifecycle DEATH half + the one `if(active)` gather guard. **Death only** (formation/broad-phase/allocation/compaction = 5c). One physics impl, both runners; default-off (`unbindOn`) so all-OFF≡HEAD holds. `BoA-v1ref` byte-clean; `GlidingHarness`/production byte-unchanged. Same harness/script (`run_xlink.sh` now runs 5a+5b).
**Lifecycle contract (items 1–4; item 5 birth = 5c).** Fixed-capacity SoA pool `CrosslinkerStore(C,nSeg)`; ONE authoritative sentinel field `linkState` mirroring motor `boundSeg` (`>=0`=`LINK_ACTIVE`, `<0`=`LINK_FREE`/dead/allocatable); **exactly one** `if(linkState<0) continue` guard added to `segGatherA`/`segGatherB` (+ matching guard in the `bruteGather` reference) — the CSR template (`CrossBridgeSystem.csr*`) stays **reused VERBATIM** (dead links keep key≥0, stay in the index; the gather guard drops their payload); death = self-write `LINK_FREE` into the link's own slot (race-free, no compaction). Field shaped so 5c allocates FREE→ACTIVE with no rework.
**Discovery — the strain track is a BOXCAR, not an exponential EWMA.** v1 `strainTrack` = `ValueTracker(filLinkStrainToAve=10)` = a 10-slot sliding-window circular buffer, `averageVal`=sum(all 10)/10 always (initial zeros included). Ported as `strainHist[k*10+p]`/`strainPlace[k]` (the proven `forceDotHist` ring; circular-write sequence bit-identical to v1's `registerValue` pre-check-wrap). `STRAIN_WIN=10`.
**Force law + RNG.** `unbind` (before `linkForces`, matching v1 ckLinkBreak-before-force ⇒ a link breaking this step contributes no force): strain=max(linkLength−restLength,0)/restLength → boxcar → `k_off=linkOffConst+linkOffCoeff·exp(aveStrain·linkOffExp)` → `P_break=k_off·dt` → wang-hash `u<P_break` ⇒ death. RNG = reused v1 wang-hash keyed (link,step,seed), **salt `0x584C4B42` "XLKB"** (distinct from NUC `0x4E55`/refractory `0x52465241`/release `0x4D54`); `u=(h>>>1)/2147483647f`; integer mixer ⇒ bit-identical CPU↔GPU, no atomics/KernelContext. One draw/link/step ⇒ break kernel uses `WorkerGrid1D localWork=64` (CLAUDE.md RNG gotcha, absent in 5a). **Note: k_off(strain 0)=const+coeff=2 /s** (not 1; the prompt's "≈linkOffConst" is approximate — validated vs the faithful formula).
**Validation (numbers; `./run_xlink.sh`, all gates PASS GPU+CPU).** **#1 (gate) arithmetic**: k_off/P_break vs v1 literal formula **Δ=0.000%**; EWMA step-for-step (40-step ramp+hold) v2(float32) vs v1 ValueTracker(double) **Δ=2.6e-6%** (float32 storage floor). **#3 empirical off-rate** (frozen pose, 20k links/strain, dt=1e-4): strain 0 → P_emp 2.027e-4 vs k_off·dt 2.0e-4 (Δ1.37%, ~1.3σ, 9039 deaths); strain 0.5 → Δ0.030%; strain 1.0 → Δ0.0054% (matches across the Bell exp; Δ shrinks as deaths grow ⇒ pure sampling). **DEATH→INERT** (full pipeline): loaded link (|F|1.49e-13 N) breaks @step35, self-writes sentinel, gathered force **exactly 0** after (gather=brute=0). **CPU≡GPU break path bit-identical**: 400 steps, GPU dead=CPU dead=854, **0 mismatched**. **all-OFF≡HEAD**: unbind OFF ≡ 5a path bit-identical (Δ=0, both runners); 5a's own gates (rest hold, decay 0.0012%, gather bit-exact) reproduce unchanged.
**Carry-forward.** Running-v1 oracle stays **DEFERRED to 5c** (formation steady-state needs it). fracMove-on-death (v1 `getLinkCt` recomputed each step) deferred to 5c (5b gates don't depend on it). New: `CrosslinkerSystem.unbind`+lifecycle/strain fields in `CrosslinkerStore`+5b checks in `CrosslinkerHarness`. Report: `INC5A_CROSSLINKER_FINDINGS.md` (§5b appended).

## 2026-06-16 — Increment 5a — DONE: passive crosslinker static spring + the DOUBLE-ENDED fil↔fil gather (the recon §2 design risk), validated green. Next: 5b (Bell unbinding) / 5c (formation + broad-phase).
**Built (greenfield, recon §1/§2/§3a).** `CrosslinkerStore` (SoA: `linkFilA/B` integer slots + `loc1/2` arcs + `filLinkCt` + `xlinkData` stride-12 reaction scratch), `CrosslinkerSystem` (`linkForces` = faithful v1 `FilLink.applyTransForce` port + `segGatherA/B` + `bruteGather`), `CrosslinkerHarness`+`run_xlink.sh`, `SpatialBodyView.STORE_CROSSLINKER=2`. **5a scope only**: links pre-placed + STATIC (no formation/`ckLinkBreak`/torsion/Arp2/3 — 5b/5c/later). `GlidingHarness`/production **byte-unchanged**; `BoA-v1ref` byte-clean.
**The double-ended gather (the one hard design decision) — implemented as the planner specified, no redesign, no bail.** Two-pass single-ended CSR-inverse: reuse the validated `CrossBridgeSystem` CSR template (`csrHistogram→csrScan→csrScatter`) **verbatim, run twice** — pass A keyed by `linkFilA`, pass B keyed by `linkFilB`. Each crosslinker computes its reaction ONCE and **self-writes** both side reactions into its own `xlinkData` row (`{+forceVec,+τ}`→filA, `{−forceVec,−τ}`→filB; race-free like motor `bondData`); each pass sums the matching side into the filament's `forceSum`/`torqueSum` (`+=`). Two-pass hit **no wall** ⇒ the unified-2N-CSR was unnecessary. Gather == brute per-link sum **bit-identical** (force+torque, both runners); force+gather **bit-identical CPU↔GPU** (the bail-critical "two-pass CSR can't be made bit-identical" — DISPROVEN). Keyed by integer filament slots only (no `instanceof`).
**Porting subtlety (the bail-critical one) — RESOLVED, faithful, commit.** v1's `applyTransForce` is damping-limited: `F = fracMove·1e-6·(linkLength−restLength)/dt · 1/(1/γ1+1/γ2)`, `forceVec=F·linkVec` (linkVec NOT normalized — v1 quirk, ported verbatim; γ=`bTransGam.x` parallel). Traced force→position: v1 `incForceSum`→`forceSum`, then `moveThing` `v=1e6·F/γ; pos+=dt·v`; v2's `RigidRodLangevinIntegrationSystem` is the inc-1 line-for-line port of that. The `/dt` **cancels** the integrator `·dt` ⇒ per-step relaxation **dt-INDEPENDENT**; drag in BOTH the force-law denominator and the integrator is v1's **design**, not a double-count. v2 reproduces it because it ports both unchanged.
**Validation (numbers; `./run_xlink.sh`, dt=1e-5, M=4000).** **CHECK #1 rest hold**: (a) exact rest (sep=restLength, Brownian off) → max|Δcoord|=**0.00 µm**/4000 steps (no spurious rest force); (b) stretched release (0.025→0.0125 µm) → COM-z drift **0.0014% of relax** (equal-opposite ⇒ COM fixed; sub-ULP geometry). **CHECK #2 (decisive) stretch relaxation**: measured per-step decay rate k=**0.00365176** vs analytic-from-v1-arithmetic (k=fracMove·(γpar/γperp)·L) **0.00365172** = **0.0012% match** (≪0.1%-is-logic floor ⇒ float32, faithful); **τ=273.84 steps=2.738 ms**; **dt-independence Δ=0.0000%** (k(dt/10) identical — the damping-limited cancellation faithfully reproduced). **GATHER EXACT** max|gather−brute|=0.00. **CPU≡GPU** force+gather bit-identical (forceSum diff 0.00 at steps 1/10/100); pose diverges only by integration float32 last-bit, saturating **9.31e-10 µm ≈0.5 ULP** at the 0.0125-µm scale (rel 6.5e-8). **all-OFF≡HEAD** crosslinker pipeline over nLinks=0 ≡ bare filament path, **bit-identical** (Δcoord/ΔuVec=0.00, Brownian on) on GPU+CPU.
**v1 decay oracle — posture.** Decay constant fully determined by force law + integrator, both confirmed faithful (per-step force matched v1's formula bit-exactly = 3.91865e-15 N; integrator the FDT/deflection/chain-validated inc-1 port; running v2 matches the analytic-from-v1-code to 0.0012% + dt-invariant). A full v1-`FilSegment` standalone run (Crucible/`theBox`, WorkerScratch pool, monomer bookkeeping, Env param system for a 2-seg scene) **assessed disproportionately invasive for 5a and deferred** (same call as §6.8 fp64); becomes natural in 5b/5c. **Carry**: v1 `getLinkCt` accumulates per-step (order/thread-dependent for multi-link-per-seg) — 5a uses the total static count (=1 ⇒ fracMove=0.4 exact); multi-link `fracMove` faithfulness is a 5c concern, flagged. `restLength` commented "nm" in v1 but is **µm** (0.0125=12.5 nm). Full report: `INC5A_CROSSLINKER_FINDINGS.md`.

## 2026-06-16 — Increment 4b-iv CLOSED: −13% net-glide residual ACCEPTED as the irreducible parallel-scheme remainder; §6.12 refractory-confound gate failed; cap-promotion recorded-but-deferred, clean refractory kept. Next: increment 5 (crosslinkers).
**§6.12 (refractory-confound gate — FAILED).** Tested whether the −13% is inflated by v1's refractory **race** under-blocking (effective ~0.31 vs intended 1.0): lengthen `myoRebindTime` in both codes so the race is a small fraction of the window, then re-measure the gap. **Gate failed** — v1's effective block rate is **window-INDEPENDENT** (~0.27–0.34 across a **1000×** `myoRebindTime` sweep, 1e-5→1e-2 s): `bindTimer` is a **static class-global race** (~0.31 GPU / 0% CPU), not a tunable refractory, so the race cannot be lengthened away (it *is* the window-setting mechanism). The confound is separately **bounded 0.0σ** by the existing control **v1-CPU 4.581 vs v1-GPU 4.578** (the serialized race-free v1 net == the racy v1 net → v1's refractory race does not move v1's net). Phase 2 not run; v2's `-refractorysteps N` probe built+verified then reverted; `BoA-v1ref` byte-clean. Full detail in the prior §6.12 entry below.
**4b-iv CLOSED.** The −13% / −4σ net-glide residual (v2 **4.000** vs v1 **4.578**, **0.874×**, box-uniform; n=24/16) is the **irreducible parallel-scheme remainder** — v2's one-step-stale SoA forces (Jacobi-like) vs v1's sequential fresh-force update (Gauss–Seidel-like) on a chaotic many-body trajectory — **real but within v1's chaotic envelope** (22/24 v2 runs inside v1's seed range; v1 same-seed assist SD 3.3 pp via Lyapunov divergence; v2 bit-reproducible). The exclusion chain §6.8–6.12 is complete: **precision** (§6.8 RULED OUT, float32 floor ≲0.1%); **localized assist-balance constant** (§6.9 — none; every per-state/load/pose rate + bindArc/poseAngle/load distribution matches within v1's chaotic same-seed SD); **force-cap** (§6.10 net-flat, −0.13/≤1σ); **refractory rate** (§6.11 ±0.16/≤1σ, sign-unstable); **refractory-race confound** (§6.12 gate failed + 0.0σ control). Net is **decoupled** from assist-balance (§6.4/6.9 move it without moving net), avgBound (§6.10 cap drops avgBound −17%/−5σ, net flat), and refractory rate (§6.6/6.11). **Accepted** — architectural SoA/GPU-residency tradeoff, same category as the float32 decision; not a bug, not precision, not a tunable constant.
**Decisions.** (1) **Promote the 12 pN force-cap** (`-faithfulrelease`, §6.10) — a real v1 feature (`MyoFilLink.ckRelease`), faithfully ported, CPU≡GPU — **recorded as decided, execution DEFERRED to its own task** (flipping the default re-baselines §7: avgBound 7.6→6.5, the §6.7/§6.9 distributions). **NOT flipped here.** (2) **Keep v2's clean per-motor 1-step refractory** — v1's `bindTimer` is a non-physical static-global race (parameter-vestigial); v2 deliberately does **not** reproduce it — a **de-racing divergence**, not a faithfulness gap.
**Consolidated reference:** `GLIDING_4biv_RESIDUAL_DOSSIER.md` (Part 1 = the residual investigation + the decoupling that pins it; Part 2 = the BoA `bindTimer` static-global-race **bug write-up for a future BoA-active fix** — explicitly NOT `BoA-v1ref`, which stays the frozen byte-clean oracle).
**Superseded leads (don't re-trust):** §6.2 "v1 assist 54.4%" was an **n=4 draw** (≈52% at n=6, §6.9 — no assist deficit); §6.6 "~4–6% net from the refractory" **did not survive n=16** (§6.11; its primary verdict — refractory acts on binding quantity not directedness — stands); §6.8 "1.37% coherent ≈ 2–3 pp assist deficit" cross-check is **coincidental** (the deficit was small-n). §6.8's **core stands** (precision ruled out, floor ≲0.1%).
**Next: increment 5 — crosslinkers / Arp2/3** (code-state recon for the planner: `INC5_CROSSLINKER_RECON.md`). Key flag: the motor→filament CSR-inverse gather is **single-ended** and does **not** cover filament↔filament coupling as-is (crosslinkers are double-ended) — a new gather pattern is needed; flagged for the planner, not designed here.

## 2026-06-16 — Increment 4b-iv §6.12: refractory-confound test (is −13% inflated by v1's refractory race?) — Phase-1 gate FAILS (v1 effective block rate window-INDEPENDENT), Phase 2 not run, nothing committed
Tests jba's confound: every residual measurement compared v1 (racy ~0.31 *effective* refractory, §6.6) vs v2 (clean 100%/1-step) — conflating the parallel-scheme gap with a refractory-impl gap. If v1's race under-blocks (0.31 vs intended 1.0) it inflates v1 binding→net, so part of −13% could be *v1 too fast*. Probe: lengthen `myoRebindTime` in both codes so the race is a small fraction of the window, re-measure the gap. **Cheap Phase-1 gate (prerequisite): does lengthening raise v1's effective block rate?** Scratch instrumented v1 `/tmp/v1scratch` (REBIND_DIAG logging-only; `BoA-v1ref` byte-clean, diff confirms); `myoRebindTime` changed **purely via param file** (one parameter, no rebuild). **Result (GPU 14×2, ~3k steps): effective block rate is WINDOW-INDEPENDENT** — N=1 (1e-5) 0.27/0.30, N=4 (4e-5) 0.27/0.34, N=40 (4e-4) 0.27, N=1000 (1e-2) 0.31 — flat across a **1000× sweep**. N=4 not materially > 0.31. **⇒ GATE FAILS: the race scales with the window, can't be suppressed by lengthening.** Mechanism: `bindTimer` is a static class-global (`MyoMotor:73`) advanced `+=deltaT` by every motor's step() (~N·deltaT≈0.13s/step ≫ any tested window) and reset to 0 on any release ⇒ at the drain it's bimodal (≈0 just-reset → blocked / ≫window → never), so blocked fraction (~0.31) is set by the racy reset-vs-check concurrency, not by N. **Phase 2 (2-pt net gap) NOT run; no Δ₁/Δ₄.** v2 clean deterministic N-step block (`-refractorysteps N`, race-free, NOT §6.11's probabilistic path) built+verified (default/`-refractorysteps 1`≡HEAD bit-identical; `-refractorysteps 4` blocks more, avgB 6.000→5.813) then **reverted** (Phase 2 didn't run) — v2 production untouched. **Read: confound not testable via lengthening (v1 rate is structural ~0.31, window-invariant), but already bounded small** by §6.11 (v2 matched to 0.31 → net unmoved) + §6.6/§6.7 (v1's own block-rate net sensitivity ≲4%, 0σ at 14×2) ⇒ residual ~entirely irreducible parallel-scheme. **No physics edits; nothing promoted; `BoA-v1ref` byte-clean; nothing committed.** `GLIDING_4biv_FINDINGS.md` §6.12; raw `RUN_LOGS/2026-06-16_4biv_refractory_confound/`.

## 2026-06-16 — Increment 4b-iv §6.11: rate-faithful rebind refractory (`myoRebindTime`) behind default-off `-faithfulrefractory`; bundled 2×2 A/B with the §6.10 cap — refractory-fixable net chunk ≈0 (contradicts §6.6), residual ~entirely irreducible-scheme
Implements §6.6's flagged rate-faithful refractory. **v1**: `MyoMotor.bindTimer` is a **`static`** class-global (`:73`), `+=deltaT` per motor-step (`:179`), reset to 0 on any release (`MyoFilLink:315`), gates rebind `bindTimer<myoRebindTime` (1e-5 s, `:455`) — racy/path-dependent, measured **GPU-oracle effective block rate ≈0.31** (CPU 0%, §6.6 B1). **v2 HEAD**: clean per-motor 100%/1-step block. **Fix (race-free)**: match v1's *rate* not its races — a per-(motor,step,seed) wang-hash (salt `0x52465241`) enters the existing dt-correct cooldown with probability `FAITHFUL_BLOCK_PROB=0.31` (= v1 GPU-oracle rate; §6.6's position in the ON-100%↔OFF-0% bracket), else immediately bindable. **FSM untouched** (same FREE_COOLDOWN/BINDABLE, same `bindNearest`); `kinParams[13]`, `setFaithfulRefractory()`, `GlidingHarness -faithfulrefractory` (default off, coexists with §6.10 `-faithfulrelease`). `blockProb≥1.0` (default) guards the RNG branch off ⇒ HEAD path. **Verify: all-OFF≡HEAD bit-identical** (GPU GRID_ROW every field, stash A/B), **-faithfulrefractory CPU≡GPU bit-identical**, **bundle CPU≡GPU bit-identical** (+CAP_ROW). **A/B (GPU 14×2, 10k, n=16 grid / n=3 assist)**: cell1 (cap OFF+HEAD refr) reproduces §6.10-OFF *exactly* (net 4.224/avgB 7.891) ⇒ clean **2×2 factorial**. **Refractory net effect ±0.16 (≤1σ, sign-unstable: −0.14 cap-off, +0.16 cap-on)** ⇒ **refractory-fixable net chunk ≈0 — contradicts §6.6's ~4–6% prediction** (extrapolated from an n=6 bracket + a 1-seed v1box probe §6.6 itself flagged noise). **Reopens §6.6's secondary net-contributor claim; primary verdict re-confirmed** — directedness untouched (assist flat 0.520/0.520/0.528). **avgBound**: the gentler refractory **offsets the cap's over-suppression** (cap-alone 6.528 → bundle 6.822, toward v1 7.29); bundle avgB 6.822 closer to v1 than HEAD 7.891. **Bundle net 4.256 (+0.19σ vs HEAD; −2.0σ vs v1 4.578)** — porting both confirmed divergences moves net negligibly. **Decomposition: both logic ports (cap §6.10 + refractory §6.11) close ≈0 of the net residual ⇒ residual ~entirely irreducible parallel-scheme remainder** (§6.7/§6.8/§6.9 class). Toggles committed **default-off; NOT flipped, 4b-iv NOT closed** (planner's call — promotion re-baselines §6.7/§6.9 + avgBound 7.89→6.82). `GLIDING_4biv_FINDINGS.md` §6.11; raw `RUN_LOGS/2026-06-16_4biv_refractory_bundle/`.

## 2026-06-16 — Increment 4b-iv §6.10: port v1's break-force (12 pN) release — faithful port behind default-off `-faithfulrelease`; A/B shows it's NOT the residual
Closes the one confirmed v1 logic divergence (§6.2): v1 `MyoFilLink.ckRelease:334` has a deterministic force-cap release (detach when cross-bridge spring magnitude `forceMag`=myoSpring·dist > `myosinBreakForce` 12 pN) that v2's `catchSlipRelease` lacked. **Faithful port** (same compared quantity `forceMag`, same 12 pN threshold, same release target + refractory, same ordering — first, before the catch-slip draw; v2 has no `inRigor` so v1's order collapses to break-force→catch-slip). Surfaces `forceMag` in `CrossBridgeSystem.registerForceDot` as a `sqrt` of the already-stored head-side force vector (NO force-law change), in lockstep with `forceDotFil`. One impl, both runners (CPU + GPU TaskGraph); wang-hash RNG ⇒ pre-empting a capped motor's draw perturbs no other motor. **Verify: OFF≡HEAD bit-identical** (GPU GRID_ROW every field), **ON CPU≡GPU bit-identical** short-horizon (GRID_ROW+CAP_ROW). **A/B at power** (GPU 14×2, 10k steps, n=16): **net glide does NOT close** — netXY OFF 4.224±0.130 vs ON 4.098±0.114 (−0.73σ, slightly the wrong way; v1 oracle 4.578); cap **fires ~0.5%/bound-step** (capRate 0.00494, matches §6.2's 0.56%); avgBound −1.36/−5σ (genuine re-patterning, transient-weighted) but **assist-fraction flat** (0.520→0.525, n=3) ⇒ re-patterning doesn't shift the balance or net (consistent §6.9/§6.4). **Read: faithfulness restored, residual stays emergent-scheme-class.** Toggle committed **default-off; default NOT flipped** — promotion re-baselines all prior numbers (avgBound 7.6→6.5, §6.7/§6.9), planner's call. `GLIDING_4biv_FINDINGS.md` §6.10; raw `RUN_LOGS/2026-06-16_4biv_forcecap/`.

## 2026-06-16 — Increment 4b-iv §6.9: decompose the assist deficit — NOT a localized constant; gap doesn't reproduce at matched n (dissolves into v1's chaotic same-seed variance). Read = (B) accept, close 4b-iv
Tests §6.8's bug-class lead (a rate/geometry/integration **constant** biasing the assist/resist balance). New default-off `GlidingHarness -assistlog` (per-bound-motor `{state, assistSign, forceDotFil, bindArc, poseAngle}` tuple at output cadence; **GRID_ROW bit-identical to HEAD** — production no-op) + a `/tmp/v1assist` scratch-logging `GlidingAssayEvaluator` shadow (`BoA-v1ref` byte-clean), same tuple, identical assist def (`Dot(F_head,segU)`, assist=forceDotFil>0). 14×2, dt 1e-5, 10k steps, post-transient, **n=6 each** (v2 prod GPU TaskGraph; v1 `-r -gpu` oracle). **(a) Marginals MATCH**: assist v2 52.63±0.51 vs v1 51.98±0.74 → gap **+0.65 pp (+0.7σ, v2 higher)**; occupancy ATP 56.8/ADP 39.8 both; net load +0.7σ. The §6.2 "2–3 pp deficit" (n=4) does NOT reproduce. **Cause = v1's chaotic same-seed variance**: v1 GPU seed-1 ×5 = 56.2/48.8/50.2/53.9/55.5% (SD **3.3 pp**, range 7.4) — same `BOA_RNG_SEED`, divergent microstate; v2 GPU seed-1 ×2 bit-identical (SD 0). **(b) Joints**: per-state (ATP Δ−0.08, ADP Δ+0.85 pp), per-load, per-bindArc, per-poseAngle assist **rates all track v1**; bindArc/poseAngle/load **distributions match** (poseAngle 116.95 vs 115.65). **No rate offset, no distribution offset tracking any input ⇒ (A) localized constant RULED OUT; (B) emergent/within-envelope.** Reconciles §6.7/§6.8: the −13% **net**-glide residual stays real but is **not carried by an assist-balance deficit** (matches §6.4: `-freshread` moved assist +0.43 pp, not net) — it's the parallel one-step-stale scheme on the chaotic trajectory mean, architectural not tunable. **Recommend: accept the ~0.87× net residual, close 4b-iv.** No production physics changed (`-assistlog`/`-forcebias` default-off). `GLIDING_4biv_FINDINGS.md` §6.9; raw `RUN_LOGS/2026-06-15_4biv_assist_decomp/`.

## 2026-06-15 — Increment 4b-iv §6.8: fp64 DISCRIMINATOR — precision RULED OUT, residual is LOGIC-class (not the float32 tradeoff)
The §6.7 −4σ gliding residual (v2 4.00 vs v1 4.58) was localized to **precision-or-logic** (parallel reduction
exonerated). §6.8 decides via the cheap Phase-1 susceptibility pre-filter — the expensive fp64 build was unneeded.

- **Instrument (committed, diagnostic):** `-forcebias <ε>` injects a uniform −x seg-side force bias per bound motor
  in `CrossBridgeSystem.bondForces` (`nFx − ε`). `ε=0` is **bit-identical** to the production GPU path (verified,
  every GRID_ROW field). Padded all four `xbParams` constructions to 6 elems (slot[5]=bias) so `get(5)` is in bounds.
- **Phase 1 (GPU, 14×2, 10k, n=5/ε; ε unit U=1e-4×5.4e-12=5.4e-16):** susceptibility is **linear**, slope
  S≈**7.8e12** µm/s per force-unit (ε=300 & 1000 agree to 0.3%; sublinear only far above as avgB collapses).
  ⇒ float32-scale response S·U ≈ **0.004 µm/s** at ε=1× (measured small points +0.02/+0.07/+0.21 at ε=1/0.5/2, all
  ≈0 within SEM ~0.2). The 1e-4 coherent bias moves net **≪ 0.578, by ~140×**. Producing the residual needs **137×U
  = 1.37% of the per-motor force, fully coherent** — float32 is ~0.01% AND incoherent.
- **Verdict:** Phase-1 ruled out precision → **LOGIC/constant difference** (bug-class). Overturns §6.7's
  "precision prime suspect" lean. The 1.37%-coherent-force scale = the **~2–3pp assist-fraction deficit** §6.2/§6.7
  measured — a real directedness/balance difference, not a rounding artifact. **Flagged for planner; NOT chased
  overnight** (reopen §6.1a chain / §6.2 cycle constants the single-config matched-state tests didn't reach).
- **Phase 2a (assessed, NOT built):** clean fp64 CPU path = wildly invasive (SoA state all `FloatArray`, shared
  CPU/GPU stores, no `DoubleArray`; forbidden parallel-double-path or breaks the GPU production path) → **bail per
  the prompt**, Phase 1 settled it.
- **Precision floor (corollary):** float32 perturbs this near-cancelling glide observable by ≲0.1% — NOT the
  bottleneck; the ~13% gap is model-level. Carry into contractile work: >0.1% discrepancy there = logic, not float32.
- No physics/rate/constant edits to the production path; `-forcebias` default 0. Raw: `RUN_LOGS/2026-06-15_4biv_fp64/`.

## 2026-06-15 — Docs: added "Document map & oracle posture" section to CLAUDE.md (root-doc roles + inc-5-onward porting-equivalence oracle shift). No source touched.

## 2026-06-15 — Increment 4b-iv residual: VARIANCE CHARACTERIZATION (the closer) — OUTSIDE v1's envelope, localized to precision/logic NOT the parallel reduction
The honest close to §6.5/§6.6 (all same-dt mechanisms cleared as the directedness cause): is the ~0.87×
net residual a real systematic difference, or within v1's true run-to-run spread? Four configs —
**v1-CPU, v1-GPU, v2-CPU runner, v2-GPU TaskGraph** — at the full 14×2 box (dt=1e-5, d=500/µm²), 10k steps
(0.1 s), n=24/16/16/15, clean machine, serial (no concurrent sims). Measurement only, existing instruments
(v2 `-ztrace`/`-grid`, v1 `gliding_assay.dat`). `GLIDING_4biv_FINDINGS.md` §6.7; raw
`RUN_LOGS/2026-06-15_variance/` (`variance_results.txt`, per-seed data, `report.py`).

- **Four distributions (post-transient net-x µm/s, SD = run-to-run envelope):** v2-GPU 4.000 (SD 0.408,
  n=24) · v2-CPU 4.037 (SD 0.600) · v1-GPU 4.578 (SD 0.472) · v1-CPU 4.581 (SD 0.567). assist: v2-GPU 52.8 %,
  v2-CPU 52.2 % (v1 assist not re-measured — §6.2 n=4 = 54.4 %, range 51.6–58.2 %).
- **Honest uncertainty.** Per-interval glide velocity decorrelates in **τ ≈ 0.8–0.9 ms ≪ 80 ms window**
  (Sokal ACF + batch-means) ⇒ ~57–64 eff samples/run; the **eff-N-corrected pooled SEM = naïve seed-SEM
  (≈0.12)** — seed-SEM is honest, not inflated. A *single* 0.1 s run pins net only to ±0.6 (chaotic
  finite-window noise) ⇒ the **seed ensemble is the real averaging**, not any one run. Means plateau by
  n≈10; net ≈window-independent (long 30k runs plateau ~4.2–4.3).
- **Comparisons (Δnet ± combined seed-SEM):** v2-GPU vs v1-GPU **−0.578 ± 0.145 (−4.0 σ, 0.874×)**; v2-CPU
  vs v1-CPU −0.544 ± 0.210 (−2.6 σ, 0.881×); v1-GPU vs v1-CPU −0.003 (0.0 σ); v2-GPU vs v2-CPU −0.037
  (−0.2 σ); **pooled −0.565 ± 0.120 (−4.7 σ, 0.877×)**.
- **⇒ VERDICT: OUTSIDE, but small.** A real, eff-N-honest systematic mean difference (−4.0/−4.7 σ) — NOT a
  short-run-SEM artifact — of ratio 0.877 (reproducing §2's 0.873 at higher n), **≈1.2× v1's seed-to-seed
  SD** (~13 %). Per-run distributions overlap heavily (22/24 v2-GPU runs inside v1-GPU's range) — a *mean
  shift*, not run-to-run noise. (The §6.5/§6.6 "v1 disagrees with itself ~4 %" was a **4×1** result; at
  14×2 v1's CPU/GPU paths agree to 0.003.)
- **⇒ LOCALIZATION (the decisive new result): precision/logic, NOT the parallel reduction.** Production gap
  (v1-GPU−v2-GPU)=+0.578 ≈ sequential gap (v1-CPU−v2-CPU)=+0.544; **parallel-reduction excess = +0.033
  (≈0)**. The full residual is present on the *sequential* CPU-vs-CPU path; v2 & v1 each CPU≡GPU at 14×2.
  Per the prompt's rule, the GPU parallel reduction is **exonerated**; the ~13 % is **float32 (v2) vs
  float64 (v1) precision** and/or a residual **logic** difference.
- **⇒ Contingent next step SELECTED (not run): the numerical-match discriminator** — a double-precision
  `-cpu` variant (or targeted double-accumulate), NOT a reduction-order test (reduction already exonerated).
  Gap closes under fp64 ⇒ the deliberate float32 GPU tradeoff (document); persists ⇒ a hidden logic/constant
  gap, reopen §6.1a/§6.2. **No physics edits.** (Note: v1-CPU seed 9 excluded — assay output reproducibly
  terminated at ~0.05 s on the CPU path; n=15. Machine clean; one idle stale shell at 0 % CPU, no
  contention.)

## 2026-06-15 — Increment 4b-iv residual: rebind refractory — dt-fix committed; cleared for directedness; partial NET contributor flagged
Phase A (faithfulness fix, committed `f2402b2`) + Phase B/C (measurement) on the one mechanism §6.5 left
out of scope: the post-release rebind refractory. v1 holds a fixed TIME (`myoRebindTime=1e-5 s`, racy
static-global `bindTimer`); v2 held a fixed STEP COUNT (1 `FREE_COOLDOWN` step = dt). `GLIDING_4biv_FINDINGS.md`
§6.6; raw `RUN_LOGS/2026-06-15_4biv_refractory.txt`.

- **Phase A — dt-correct cooldown, COMMITTED.** Per-motor `MotorStore.cooldown` set to `ceil(myoRebindTime/dt)`
  steps (v1's existing constant — no new rate/law), driven in `catchSlipRelease`, both CPU step + GPU
  TaskGraph. **Bit-identical no-op at dt=1e-5** (git-stash A/B, both runners: GRID_ROW inst=6.042 netXY=2.928
  avgB=6.286 identical) — `ceil(1e-5/1e-5)=1` reproduces the old one-step transition. Closes the 2nd
  dt-dependent binding artifact (alongside §6.3's geometric `k_on∝1/dt`). Added `-norefractory` (default off).
- **Phase B1 — v1 effective block rate** (scratch instrumented v1, byte-clean ref; fires on the GPU drain
  too): **GPU oracle ~0.31** (0.317/0.321/0.303, box-independent: 4×1 also ~0.31), **CPU 0.0**. The racy
  static-global makes v1's own refractory PATH-DEPENDENT (mid-bracket on GPU, absent on CPU).
- **Phase B2 — v2 ON/OFF bracket (n=6, 14×2):** **assist swing −0.03 pp** (per-seed invariant, ≪ 2.5–3 pp
  gap) ⇒ refractory does NOT touch directedness. NET swing +0.14 (CPU) / +0.24 (GPU), OFF>ON — relaxing
  raises net via **avgBound** (binding quantity), avgB 7.46→7.80 GPU.
- **Phase C — v1 internal CPU-vs-GPU (4×1):** CPU net 4.76 (0% block) vs GPU 4.57 (31% block) — **~4 %**
  intrinsic path/order-sensitivity, same direction (less block → higher net).

- **⇒ VERDICT: refractory CLEARED as the directedness cause** (assist swing ~0). It's a partial, FAVORABLE
  NET contributor: v1 (31%) is more relaxed than v2's Phase-A block (100%/1-step), so a rate-faithful v2
  would gain ~+0.1–0.17 net toward v1 — but via over-binding, not directedness, and only ~4–6% (the order
  of v1's own CPU-GPU spread). **Flagged follow-up (NOT implemented):** probabilistic block matching the
  v1-GPU oracle's ~31% rate (v1's rate is path-dependent ⇒ match the oracle). With §6.5 this exhausts the
  same-dt mechanisms for the directedness deficit ⇒ closer = variance characterization. No physics edits
  beyond the committed dt-fix. (Note: runs done on a shared machine w/ a concurrent membrane sim + a stale
  16h waiter; a cd-bug collision corrupted 2 of the v1 runs — excluded; block-rate counters + 101-row
  monotonic .dats unaffected.)

## 2026-06-15 — Increment 4b-iv residual: per-step operation-ORDER audit — order is FAITHFUL, prime suspect ELIMINATED
Code-level audit (survey only, no test run; v1ref byte-clean) of every kinetic operation's within-step
position + read-staleness in v2 vs v1, to find any order divergence beyond the release-read (§6.4). Built
the side-by-side timeline for v1 CPU, **v1 GPU (the net-glide oracle)**, v2 default, and v2 `-freshread`.
`GLIDING_4biv_FINDINGS.md` §6.5.

- **v1 order (CPU):** BIND (`checkFilSegCollision`) → FORCE+RELEASE (`addForces`+`ckRelease`, fresh
  same-step force) → MOVE → CYCLE (`dissociateADP`, 10-window avg w/ fresh newest entry). **v1 GPU:**
  FORCE → RELEASE (writeback, fresh) → MOVE → **BIND drained AFTER move** (1-step bind lag) → CYCLE.
- **Prime suspect ELIMINATED.** Binding detection in BOTH reads start-of-step (pre-move) filament geometry,
  and the bind-point arc is the *identical* formula: v1 `alpha·√denom` (`MyoMotor:421`) == v2 `numer/√denom`
  (`BindingDetectionSystem:284`). So a new bind's initial cross-bridge strain is set by same-staleness pose
  + same arc ⇒ feeds assist-fraction identically. `-freshread` never touched it because it was already
  faithful (§6.3's `-forcetest` validated force *given* a config; this closes the bind-*site*-selection piece).
- **The only v2-default-vs-v1-GPU divergences are the FOUR items `-freshread` already bundles+corrects**
  (release rate+decision read, cycle gate read, bond nuc-state, newly-bound first-force timing) — already
  A/B-tested (§6.4: +0.43 pp assist, net unchanged both runners). Nothing new to test there.
- **One un-toggled order diff — bind-before-release (v1 CPU) vs release-before-bind (v2) — fails the
  contingent-test bar.** It is *not* a divergence against the GPU oracle (v1 GPU also defers binding past
  release/move), and even vs v1 CPU it is a first-step-only cull worth ~0.02 pp assist — ~100× too small.
  Bind-target tie-break (v1 first-reachable vs v2 nearest) is rare + non-directional ⇒ no systematic bias.
- **⇒ Outcome 2: the kinetic order is faithful in every consequential respect; the residual is NOT an order
  artifact.** Confirms §6.4 from first principles. With position-integration identical + chaos not shifting
  the mean, a systematic mean residual cannot originate in anything modeled as faithful — pointing to the
  residual lying within v1's TRUE ensemble uncertainty (v1 assist 51.6–58.2 % seed-to-seed; v2 51.5 % at the
  low edge). Recommended closing step (planner's call, NOT run): a **variance characterization** — does v2's
  net sit inside v1's true long-window spread vs the short-run SEM the ~3–4 σ used? **No physics edits, no
  reorder committed** (the release-read is already the `-freshread` toggle, CPU step + GPU TaskGraph).

## 2026-06-15 — Increment 4b-iv residual: release-read reorder A/B — shifts the mechanism, NOT the net residual
Tested whether reordering v2's integration scheme changes the residual. The one reorderable timing
difference: v2's catch-slip release + ADP-gate read a ONE-STEP-STALE forceDotFil (release/cycle before
bond/register), vs v1's reconciled order where ckRelease consumes the FRESH same-step force
(`MyoFilLink.java:114`). Added `GlidingHarness -freshread` (compute force+register BEFORE release/cycle;
keyed wang-hash RNG ⇒ identical draws — a clean A/B; CPU step only, GPU plan unchanged). Measurement only.

- **Assist-fraction: +0.43 pp toward v1** (52.27→52.70 %, all 3 seeds positive) — the release-read lag IS a
  real systematic contributor to the directedness (confirms the timing hypothesis).
- **Net glide: unchanged on both runners** — CPU 4×1 (n=6) Δ −0.10±0.28; **production GPU TaskGraph full
  14×2 (n=6): 3.96±0.18 → 3.93±0.18, Δ −0.03±0.25** — within noise. Offset by an avgBound rise (GPU
  7.46→7.73; better-timed catch retains more motors ⇒ drag, the §5 tug-of-war). GPU buildPlan reordered too.
- **⇒ Reordering moves the MECHANISM but not the net residual.** The release-read timing is a small piece;
  the ~0.87× net residual is robust — dominated by the broader emergent/chaotic decorrelation of the
  parallel scheme + the avgBound–drag coupling, which kernel reordering can't remove (position integration
  is forward-Euler in both — no Gauss-Seidel to reorder away; float32 op-order chaos is irreducible).
  `-freshread` is a faithful-to-v1 toggle (default off) the planner may adopt for fidelity; it does not
  close the velocity gap. `GLIDING_4biv_FINDINGS.md` §6.4. No physics edits.

## 2026-06-15 — Increment 4b-iv residual: dt-test CONFOUNDED, per-step force FAITHFUL ⇒ residual is the SCHEME
The decisive test for whether the ~0.87× residual is a discretization/integration-scheme difference
(vanishes as dt→0) or a real unfound difference. **Measurement only — no physics edits.** Raw
`RUN_LOGS/2026-06-14_4biv_dt_force.txt`.

**dt-convergence test — BAILED on a confound (caught before the ~1 hr v1 run).** Planner directed NOT to
scale the fracMove family (it's part of v1's model, not a separable rate). Audited the dt-dependences:
`myoSpring` is a real spring (dt-correct), cycle rate·dt, Brownian √dt, Langevin force/γ·dt — all
dt-correct. **But binding is geometric/deterministic — a motor in reach binds within ONE step ⇒ effective
k_on ∝ 1/dt — in BOTH codes.** So refining dt changes the binding regime: v2 4×1 at dt=1e-6, avgBound
**triples 6.4→19.9** (NET ~robust 3.60→3.90) — an over-bound regime, not the gliding fixture. The test
can't hold the regime fixed ⇒ can't isolate the scheme. (v1's fixture avgBound ~7.6 is itself a dt=1e-5
artifact of the deterministic binding.)

**Per-step cross-bridge force cross-check — FAITHFUL (the planner-chosen alternative).** Dumped v1's EXACT
compute-time bound config (instrumented scratch `MyoFilLink.addForces`, CPU; `BoA-v1ref` byte-clean) and
fed it into v2's `bondForces` (`GlidingHarness -forcetest`): **v2 reproduces v1's head-side F8 vector to
float32 precision** (Δ ≤0.15 % smallest component, ≤0.013 % dominant; forceMag 5.39399e-12 vs 5.39361e-12;
forceDotFil ~0 both — near-perpendicular config). Code is term-by-term identical (F8 spring, F9/F10 rest
90/120, attach `segC+(arc−½len)·u`, tip `c+½·0.020·u`, all constants equal); 4b-ii bit-validated the gather.

**⇒ All per-step physics is faithful (chain stiffness, nucleotide cycle, cross-bridge force). The ~0.87×
residual is the EMERGENT effect of the integration-SCHEME difference — v2's parallel SoA kernels apply
one-step-stale forces vs v1's sequential OOP fresh forces (force-vs-state timing) — on the chaotic
gliding dynamics.** The clean dt→0 confirmation is blocked by the deterministic-binding confound, but the
per-step force identity establishes the residual is the scheme, not the physics. 4b-iv residual diagnosis
complete: not chain, not cycle, not force-law — the parallel-vs-sequential update scheme. New (measurement):
`GlidingHarness -dt` (dt override) + `-forcetest` (cross-code force check). `GLIDING_4biv_FINDINGS.md` §6.3.

## 2026-06-14 — Increment 4b-iv residual step 2/2: nucleotide cycle under load — FAITHFUL; residual is EMERGENT
Second foundational faithfulness check on the static ~0.87× residual. **Is v2's nucleotide cycle different
or changing under gliding load? No — the cycle is faithful; the residual is a small emergent assist/resist
force-balance difference.** Measurement only — no physics edits; v1 instrumented by a scratch logging-only
build (`GlidingAssayEvaluator` shadowed in /tmp, `BoA-v1ref` byte-clean). Raw `RUN_LOGS/2026-06-14_4biv_
cycle.txt`. New (measurement): `GlidingHarness -cycldiag` + forceMag stats.

1. **Self-consistency (`-cycldiag`):** v2's empirical per-state conditional transition rates under load
   match the validated nominal within ~10 % (NONE→ATP 98 %, ATP→ADPPi 97 %, ADPPi→ADP 89 %, ADP→NONE|
   gate-open 111 %). The high ADP occupancy is the **load-gate** (open only 37 % of the time — assisting
   load holds ADP motors), NOT a malfunction.
2. **Drift (0.3 s run):** assist-fraction (~0.52) and glide (~4) FLATTEN — no continuing drift ⇒ static
   (the earlier 0.88→0.84 was second-order noise; only z keeps settling).
3. **v1 vs v2 (scratch v1 logging build, 4 seeds, 1424 bound-obs):** cycle rates, the load-gated
   `dissociateADP`, and the Guo–Guilford catch-slip law+params are **identical** (code-verified).
   **Occupancy MATCHES** (v1 ATP 58.9 / ADP 37.4 vs v2 59.8 / 36.6); **assist-fraction v1 54.4 % vs v2
   51.5 %** — a small ~3 pp difference (~2 SE; seed-1's 58 % regressed with more seeds). Near the 50/50
   balance, 3 pp maps to a meaningful net-force difference, consistent with the ~13 % residual.

**One port gap (reported, NOT fixed — physics change, and self-consistency passed):** v1's `ckRelease` has
a break-force release (cross-bridge tension > `myosinBreakForce` 12 pN) that v2 lacks — but v2's tension
exceeds 12 pN only 0.56 % of bound-steps and that tail is ~60 % assist, so shedding it would *lower*
assist-fraction (wrong direction). Not the cause.

**⇒ Both static candidates eliminated as faithfulness gaps (step 1 chain, step 2 cycle). The ~0.87×
residual is a genuine EMERGENT collective-coordination difference: v2's bound population is marginally less
assist-enriched (51.5 % vs v1's 54.4 %) at matched cycle/occupancy/release-law.** Diagnosis of the residual
is complete — a documented finding, not a tunable gap. Planner decides: accept the ~0.87×, or scope the
break-force port-gap / a deeper emergent-coordination study. `GLIDING_4biv_FINDINGS.md` §6 updated.

## 2026-06-14 — Increment 4b-iv residual step 1/2: chain stiffness at fracMoveTorq=0.2 — FAITHFUL, ELIMINATED
First of two foundational faithfulness checks on the static ~0.87× residual. **Is v2's chain as stiff as
v1's at the gliding `fracMoveTorq=0.2`?** (inc-2b validated only at 0.265.) **Yes — faithful, chain
eliminated.** Measurement only — no physics edits; v1ref instrumented (its real `-bmDiag`), never edited.

Ran the deflection characterization (11-seg×32-mon pinned chain, fracR=0.1) for both codes at 0.2 and 0.265:

| fracMoveTorq | v1 (`BoxOfActin -bmDiag`) | v2 (`-characterize`) | Δ |
|---|---|---|---|
| 0.265 (regression) | 0.99843 | 0.99831 | 0.01 % |
| **0.2 (gliding)** | **1.20240** | **1.20235** | **0.004 %** |

v2's chain matches v1 at 0.2 to ≤0.005 % — well within the inc-2b ≤0.05 % tolerance. Both are ~20 % softer
than the 0.265 beam target (ratio 1.20 vs 1.00), *identically* — that softness is a faithful property of
v1's gliding config, not a v2 gap. The two share the identical damped-torsion law (linear in `fracMoveTorq`,
no 0.265-baked constant), so it transfers cleanly.

**Gotcha worth recording:** the damped-torsion stiffness ∝ 1/dt — the deflection benchmark runs at dt=1e-4,
and an initial wrong dt=1e-5 override made v1 look 10× stiffer (ratio 0.10) and produced a spurious 10×
"divergence." With matched dt the codes agree to 5 sig-figs. (Both share this dt-dependence faithfully.)

⇒ **Chain stiffness is NOT the residual cause.** Per the prompt's bound, stop here for **step 2** (the
nucleotide-cycle-under-load check) — not improvised. `GLIDING_4biv_FINDINGS.md` §6(a) updated.

## 2026-06-14 — Increment 4b-iv z-settling probe: z-mechanism ELIMINATED (residual is static, not z-driven)
Bounded measurement-only probe of the ~0.87× net-directedness residual. The startup check had pointed at
§6(c): the filament *progressively losing motor support as its z settles*. Tested directly with
time-resolved 1 ms traces (v1 from `.dat` posZ/avgBound/vecMovedX; v2 via new `GlidingHarness -ztrace`:
centroid-z, tilt, avgBound, per-interval glide, bound-motor `forceDotFil` mean + assist-fraction), n=8,
both boxes. Raw `RUN_LOGS/2026-06-14_4biv_ztrace.txt`.

**Verdict: ELIMINATED.** (1) **Both codes settle to z ≈ −0.03…−0.04, nearly identically** (v1 14×2
−0.007→−0.036 vs v2 −0.002→−0.030; v1 4×1 −0.010→−0.031 vs v2 −0.006→−0.040; no consistent asymmetry — v2
settles *less* at 14×2). v2 does NOT sink more than v1. (2) **v1 settles in z just as much yet its glide
holds** (14×2 −2 %, 4×1 +14 %) — the direct counterexample: z-settling isn't the cause. (3) v2's
**assist-fraction is flat ~0.50–0.55 throughout** (no progressive disengagement; the ~50/50 tug-of-war is
present from the *start*); avgBound tracks v1 (~8.4→7.0). (4) The residual is **≈constant across the run**
(v2/v1 ≈ 0.88 early → 0.84 late) — present from the first bins, not a progressive collapse (the earlier
"~7 % decay" is a small second-order widening, not z-coupled).

**⇒ The residual is a STATIC coupling deficit (the §5 ~50/50 assist/resist tug-of-war), not progressive
z-settling.** Live candidates revert to chain-stiffness at the gliding `fracMoveTorq=0.2` (deflection-
calibrated at 0.265) and resisting-motor release timing — static determinants of the bound population's
assist/resist asymmetry. Probe stops here per its bound; planner decides whether to scope a fix or accept
the residual. **No physics edits**; committed `GlidingHarness -ztrace` (measurement) + `mot.forceDotFil`
added to the plan's host-transfer list. v1ref untouched (instrumented its real `.dat`).

## 2026-06-14 — Increment 4b-iv RECONCILED: the "0.51× velocity miss" was dominantly measurement-method
**The gliding-velocity "0.51× miss" was comparing two different MEASUREMENTS, not two physics.** Measured
the same way, multi-seed (n=8), at matched boxes, **v2 = 0.87× v1** — small, box-uniform, NOT a 2× miss
and NOT box-scaling. Measurement/protocol only — **no physics changed**. Full report + grid: `GLIDING_4biv_
FINDINGS.md`; raw `RUN_LOGS/2026-06-14_4biv_grid_reconciliation.txt`.

**Provenance of "8.33" (resolved).** It is v1's `longWindowSpeedXY` **at the end of a 0.1 s run** (BoA-v1ref
JOURNAL_ARCHIVE:8452, 10 seeds) — NOT a net glide and NOT in the validated table. The validated d=500
oracle (MYOSIN_VALIDATION L41/54) is `longWindowSpeedXY` **mean over ~0.5 s = 4.23 / median 3.70**, avgBound
6.91, and says explicitly to use net-displacement for honest comparison. The two v1 numbers differ ~2× by
window/run-length + an initial **settling jump** (v1's first interval literally reports `instantaneousSpeed
=309 µm/s` as the filament drops onto the bed). The net-vs-inflated gap is a measurement property present in
BOTH codes.

**The matched grid (NET = net displacement/time, v2 measured v1's exact `GlidingAssayEvaluator` way via the
new `GlidingHarness -grid`; v1 = real `BoxOfActin -r -gpu`, BOA_RNG_SEED 1–8; ±SEM, n=8):**

| box | v1 NET | v2 NET | v2/v1 | v1 inst | v2 inst | v1 avgB | v2 avgB |
|---|---|---|---|---|---|---|---|
| 4×1  | 4.61 ± 0.13 | 4.02 ± 0.15 | 0.87 | 7.39 | 6.88 | 7.47 | 7.20 |
| 14×2 | 4.69 ± 0.13 | 4.10 ± 0.18 | 0.87 | 7.33 | 6.92 | 7.29 | 7.60 |

**Decomposition.** (a) *Measurement method* dominates: v1's own NET @14×2 is **5.0, not 8.33** (8.33 is its
inflated lwEnd-of-short-run). (b) *Box scaling — NO mismatch*: v1 net +1.7 %, v2 net +2.0 % across box;
v2 reproduces v1's weak net box-scaling. The old "v1 climbs 4.4→8.33 while v2 flat" mixed v1's lwEnd with
v2's net. (c) *Residual*: a real but small **0.87× box-uniform** shortfall (~3σ/box, ~4σ pooled), specifically in **net
directedness** — `instantaneousSpeed` (total motion) and avgBound MATCH v1, but v2 converts less of that
motion into forward glide (the §5 co-bound tug-of-war, now sized at ~0.87× not ~0.5×; the n=3 snapshot's 0.76×/>5σ regressed to
0.87×/~3–4σ with the larger ensemble). The burrow target
is re-scoped from box-size/advance-per-stroke-2× to *coordination of the co-bound population*, ~−24 %.

**Decisive cell (v1 NET @ 14×2 = 4.69 ± 0.13, n=8)** — the apples-to-apples partner of v2's 4.10 — is ~4.7,
not ~8.3. **No physics edits**; committed the v1-style `measureGrid` measurement (instantaneous + net +
longWindow, sampled at v1's 100-step cadence) + the full-carpet viewer fix. v1ref left untouched (runs to a
scratch dir; the `-r` flag is required for headless, else BoxOfActin hangs after phase-plan).

## 2026-06-14 — Increment 4b-iv (gliding assay): assembled + works, velocity an OPEN FINDING
The gliding payoff — assemble 4a–4b-iii + the inc-2 chain filament into v1's gliding assay. **The
assembly works end-to-end: the filament glides −x, stably, with avgBound matching v1 — but the gliding
VELOCITY is below the v1 fixture, an open finding (NOT tuned).** Committed as a checkpoint at the
planner's explicit direction (the bail-out default was commit-nothing). Full report: `GLIDING_4biv_
FINDINGS.md`. New: `softbox/GlidingHarness.java`, `BindingDetectionSystem.bindNearest`, `run_gliding.sh`.

**What works (faithful config — density 500/µm², dt=1e-5, filament z=0 along +x, 11-seg 64-mono chain,
`fracMoveTorq=0.2`, surface = tail anchor + bound motors):** glides −x; stable over 30k steps (no NaN);
**avgBound 7.47 vs v1's 7.6** (big box); dwell-times/stroke/catch-slip/gather all from the validated
4b-i/ii/iii. A cheap-probe bug was caught + fixed early (motor Brownian had been omitted → heads stood
upright → avgBound=0; not a physics issue).

**The velocity finding.** v2 −4.0 µm/s vs the v1 fixture 8.33 (full 14×2 box). BUT — running v1 itself
at a matched small box (4×1) gives **6.66**, not 8.33: v1 drops ~20% from finite-size (the filament's
ends rotate toward the bed edges and lose support — the planner spotted this from the viewer). So the
gap vs a same-box v1 is **~0.64× (4.27 vs 6.66), the genuine remainder ~1.5× not 2×.** Localized by the
`-diag` instrument to the velocity coupling: **advance per power stroke 2.33 nm vs the 7 nm unloaded
stroke**, from a ~50/50 assist/resist tug-of-war among co-bound motors (weak net force). The big-box run
showed higher avgBound → slightly LOWER velocity (more drag) — v1 instead sustains high avgBound AND high
velocity, i.e. its bound population is coordinated/net-assisting. The levers (chain stiffness, myoSpring,
catch-slip, stroke) are all frozen validated constants ⇒ a faithful-config physics finding, not tuning.

**Direct v1 comparison (read-only v1ref built with `-encoding ISO-8859-1`; libs/.class are gitignored
build artifacts — worktree left clean):** v1 `glidingAssay500_val` at box 4×1 → 6.66 µm/s; viewer runs
`threejs_v1_gliding` vs `threejs_gliding` for side-by-side. Open for the planner (see the doc): the ~1.5×
coupling (chain-stiffness faithfulness at 0.2 vs the inc-2 deflection-calibrated 0.265; resisting-motor
release timing; filament-z).

**UPDATE — full-scale GPU resolution (the clean comparison).** Confirmed v1's MyoMotor nucleotide cycle
fires EVERY step (biochemStart phase ungated, BoxOfActin.java:1523; the biochemFireCt=10 is the FilSegment
poly/depoly gate, off in gliding) — v2's per-step cadence is faithful. Built the **GlidingHarness GPU
TaskGraph** (`buildPlan`, 23 kernels, one device-resident graph, `-gpu`; same systems as the CPU step,
only dispatch differs). Runs at the **full 14×2 box (~13.4k motors)**, stable, no per-step host pull.
Full-box multi-seed (3 seeds, 10k steps): **velocity 4.25 ± 0.32 µm/s vs the v1 fixture 8.33 ± 0.18 —
0.51×, a clean MISS outside SEM; avgBound 7.53 ± 0.50 MATCHES v1's 7.64 within SEM.** So binding is
faithful, the velocity coupling is ~half — the clean full-scale finding (NOT tuned; the mechanism burrow
is the scoped next move). **GPU throughput 386 steps/s @ 13.4k motors (~7.3× the CPU runner — measured GPU
386 vs CPU 52.6 steps/s; the earlier "~19×" was startup-contaminated)** — residency
wins this dense-proximity workload. CPU≡GPU aggregate-within-SEM (6×2 box: CPU 4.0/7.47 vs GPU 4.58/7.40).

**Existing paths unaffected:** 4b-iii stroke checkpoint PASS, 4a binding PASS, FDT 1.11676e-1 — bindNearest
is additive. **Increment 4 is NOT complete** (gliding velocity is a clean full-scale finding); 4a binding
+ 4b-i/ii/iii physics + the binding/residency/throughput of the gliding assembly stand validated.

## 2026-06-14 — Increment 4b-iii (new physics): nucleotide cycle + the power stroke (pinned checkpoint)
Added the genuinely-new physics of the stroke — the 4-state nucleotide cycle + the state-dependent
rest-angle switch (the stroke EMERGES from this, not a force law) + the full force-dependent catch-slip
— and validated it on a PINNED filament. **Scoped decision (planner): the gliding run itself (unpin +
surface + chain filament + dynamic binding + velocity/avgBound vs the v1 fixture) is deferred to a
dedicated increment (4b-iv); everything physical is now in place.** New: `softbox/NucleotideCycleSystem.
java`, `MotorStrokeHarness.java`, `run_stroke.sh`; rest-angle switching folded into `MotorJointSystem` +
`CrossBridgeSystem`; nucleotide state + the forceDotFil tracker added to `MotorStore`.

**Nucleotide cycle (`NucleotideCycleSystem.cycle`) — faithful MyoMotor.biochemStep port.** Per-motor
4-state machine NONE→ATP→ADPPi→ADP→NONE, run EVERY step (biochemStep cadence = 1; confirmed by the rate
analysis — at cadence 1000 the cycle would be 1000× too slow to glide), per-step transition probability
rate·dt with on/off-filament rates (Env.java:836-855: atpOnMyo 2e4, ATP→ADPPi 100, ADPPi→ADP 1e4,
ADP→NONE 1e3 /s). ADP→NONE is **load-gated**: it returns while the cross-bridge load's 10-window average
forceDotFil > 0 (the mechanochemical coupling, MyoMotor.java:271; ported as a per-motor ring buffer =
v1 ValueTracker(10)). `isCocked() = !isADPPi`. One wang-hash RNG draw per motor per step (distinct salt).

**Rest-angle switch (the stroke).** `MotorJointSystem` J1 lever-motor rest 0°(uncocked/ADPPi)↔60°(cocked)
and `CrossBridgeSystem` F9 motor-actin rest 90°↔120°, both keyed by the per-motor state. State flip →
rest angle changes → the F9/J1 alignment torques swing the head → the cross-bridge transmits a directional
pulse. The 4b-ii cross-bridge was restructured: `bondForces` now computes the bond once and stores
head-side(6)+seg-side(6)+forceDotFil(1) in bondData[13/motor]; `applyHeadForce` does the head self-write;
`registerForceDot` tracks the load; `segGather` sums seg-side over the CSR-inverse (the proven gather).

**Force-dependent catch-slip (`NucleotideCycleSystem.catchSlipRelease`).** The full Guo–Guilford form
rate = kOff·(αCatch·e^(−F·xCatch/kT)+αSlip·e^(+F·xSlip/kT)), F = forceDotFil (4a/4b-ii used the F=0 limit).

**Stroke checkpoint (`run_stroke.sh`) — 6 sharpened gates, ALL PASS:**

| gate | result |
|---|---|
| 1. cycle dwell == rate·dt | NONE 5.03 / ATP 984 / ADPPi 9.95 / ADP 98.8 steps vs 5/1000/10/100 (≤1.6%); cycle 0.0112 s — the 4-state analog of 4a's residence-time check |
| 2. regression guard | constant-ADPPi reproduces 4b-i (PASS) and 4b-ii (gather EXACT, segForce 3.6e-11→3.7e-12) exactly |
| 3. unloaded stroke | head tip Δ = (−5.87, 0, −3.75) nm ⇒ **6.96 nm** — a realistic myosin working stroke (lever 8 nm) |
| 4. directional force | cycling motors pulse net Σ filForce_x = −1.05e-8 N into the pinned filament (**−x**, the glide direction) |
| 5. catch-slip F-dependence | unbind rate 100→59→37→20 /s at load 0→1→2→4 pN (catch: +load stabilizes the bond), empirical == analytic |
| 6. CPU≡GPU | aggregate-within-SEM: mean filForce_x agrees 0.4%, avgBound 12.00/12.00 (the force-gated cycle decorrelates the microstate — banked standard) |

Gate 3 subtlety: in the loaded/pinned case the head tip is pinned by the cross-bridge spring so the
stroke is FORCE (gate 4), not tip motion; the unloaded stroke (F8 off, head swings freely under F9/J1)
is the ~7 nm working stroke — measured that. The −x/−z swing direction is the power-stroke geometry.

**CPU≡GPU validation standard applied.** Cycle-only (cross-bridge off, forceDotFil=0) is bit-identical
(pure integer RNG); the force-gated stroke (a float forceDotFil comparison flips gated transitions) is
aggregate-within-SEM — exactly the standard banked in 4b-ii.

**Existing paths unaffected (verified):** FDT D_par 1.11676e-1 (bit-identical), 4a binding off-rate
0.00999, broad-phase EXACT, 4b-i/ii reproduced via the regression guard. No bail-out triggered. The
stroke physics is validated; the gliding integration (4b-iv) is the next increment.

## 2026-06-14 — Increment 4b-ii: cross-bridge + the cross-entity gather (pinned)
Connected the articulated motor head (4b-i) to a **pinned** filament via the cross-bridge spring +
alignment torques, and built the **cross-entity motor→segment force+torque gather** — the design-risk
centerpiece, and the template every future multi-store coupling inherits. FIXED uncocked rest angle +
pinned filament ⇒ **no stroke, no motion, no gliding** (those need the nucleotide cycle, 4b-iii). New:
`softbox/CrossBridgeSystem.java`, `MotorXBridgeHarness.java`, `run_xbridge.sh`; + the CPU≡GPU
validation-standard note in CLAUDE.md (Task 0). **No existing file touched** (purely additive).

**Cross-bridge (`CrossBridgeSystem.bondForces`) — faithful `MyoFilLink` port.** Per bound motor, between
its head tip (head sub-body end2) and the bound site on the segment (attachPt = seg.coord + (bindArc −
½·segLen)·seg.uVec): F8 spring `F = myoSpring·dist` toward the site (addForces:187, myoSpring=1e-9 N/µm);
F9 uVec-alignment torque toward the motor–actin rest angle (FIXED 90° uncocked; alignUVecTorque); F10
yVec-alignment torque toward 0°. The cross-bridge force is applied at the head tip / the bound site, so
each end gets the POSITIONAL torque R×F (R in metres, the v1 `incForceSum(F,pt)` 2-arg semantics,
Thing.java:505). Equal-and-opposite: the head gets +F at its tip and −T9/−T10; the segment gets −F at
attachPt and +T9/+T10. The bond is computed ONCE: the head-side is applied to the head
sub-body (3m+2, self-write — one bond per head, race-free); the seg-side reaction is STORED in
`bondSeg6[m*6..]` for the gather.

**THE CROSS-ENTITY GATHER (the centerpiece, reusable infrastructure).** Motors write force to segments in
a DIFFERENT store — the race v1 hit with spawn()+shared taForce. Race-free WITHOUT atomics/`KernelContext`
(the dual-runner constraint) by a SEGMENT-SIDE gather over a **segment→bound-motors CSR-inverse** index:
`csrHistogram` (count bound motors per segment) → `csrScan` (prefix-sum offsets) → `csrScatter` (motor ids
grouped by segment) — exactly the inc-3 grid-CSR pattern keyed by `boundSeg` instead of cell, single-thread
+ serial (race-free, no atomics, both runners). Then `segGather`: each segment (one thread) sums its bound
motors' stored `bondSeg6` reactions into its OWN forceSum/torqueSum. The scatter visits motors in index
order ⇒ the per-segment list is sorted by m ⇒ the gather sums in the SAME order as the brute reference ⇒
**bit-identical** (not merely modulo float ordering). This is general infra — crosslinkers / nodes /
membrane↔ring reuse it (CSR-inverse keyed by the partner id + segment-side gather).

**Force-coverage audit** (each force/torque on exactly one path): F8 spring +F on the head (self-write
×1) / −F on the segment (gathered ×1) — equal-opposite by construction; F9 −T9 head / +T9 seg (×1); F10
−T10 head / +T10 seg (×1). The gather == brute equality (below) proves each seg-side contribution is
summed exactly once. No double-apply, no drop.

**Harness (`MotorXBridgeHarness`, 4 pinned segments, 12 motors = 3/seg, dt=1e-5, Brownian off).** Pinned
filament along x at z just above the standing head tips; 3 articulated motors under each segment. Binding
ESTABLISHED on the host (deterministic): publishHeadFromBody → bruteReachable → bindKinetics — 4a binding
re-exercised reading the **new head sub-body** (12/12 bound, [3 3 3 3] per segment ⇒ a multi-motor gather).
Then bonds frozen. Per cross-bridge step: zero → brownian(off) → joints → anchor → bondForces (head +
store) → integrate → derive → zero(fil) → CSR-inverse → segGather → bruteGather. The filament is pinned
(not integrated); its forceSum/torqueSum receive the gathered cross-bridge for validation.

**Gates (both runners): PASS.**

| check | GPU | CPU |
|---|---|---|
| gathered F+T == brute per-bond sum (max diff) | **0.0 EXACT** | **0.0 EXACT** |
| binding re-exercised (bound / per-seg) | 12/12 [3 3 3 3] | same |
| CPU≡GPU gathered force (max ΔF) | — | **7.3e-19 N** (float32 last-bit) |
| CPU≡GPU gathered torque (max ΔT) | — | **2.9e-26 N·m** |

Σ|segForce| starts 3.6e-11 N (heads 3 nm below the filament → F8 = myoSpring·3nm ≈ 3pN ×12) and relaxes to
a 3.7e-12 N steady residual as the heads are pulled to their bound sites + oriented to the filament (F9/F10)
— a clean static cross-bridge equilibrium (no stroke). CPU≡GPU is held to **bit-identity** here (per the
new validation standard: this config is near-static, not chaotic) and meets it to float32 last-bit.

**Existing paths unaffected (verified):** 4b-i articulated motor PASS, 4a binding off-rate 0.00999 +
reachable EXACT, inc-3 broad-phase EXACT, deflection ratio 0.99831 — all reproduce (only new files added,
no existing system touched). No bail-out triggered. The cross-entity gather is in hand as reusable
infrastructure. Ready for 4b-iii: the nucleotide cycle + rest-angle switching (the stroke) + F-dependent
catch-slip → unpin + surface → gliding velocity + avgBound vs the v1 fixture (8.33 µm/s, meanBound 7.6).

## 2026-06-14 — Increment 4b-i: articulated myosin motor (the body), isometric
Re-architected `MotorStore` from 4a's single point into v1's **3-body articulated myosin** — rod (tail,
anchored) → lever (neck) → head — held by two joints, integrated by the SHARED rigid-rod systems, and
validated **isometrically** (a bed of anchored motors holds its articulated shape under Brownian, no
filament). **No cross-bridge, no nucleotide cycle, no gliding, no surface** (those are 4b-ii/4b-iii).
This followed the 4b bail-out finding (v1's power stroke is emergent from this articulated body + a
nucleotide cycle + alignment torques, not a portable force). New: `softbox/RigidRodBody.java`,
`MotorJointSystem.java`, `TailAnchorSystem.java`, `MotorBodyHarness.java`, `run_motorbody.sh`; +CLAUDE.md
abstraction invariant (Task 0). Also folded the sharpened "abstract from the second instance" invariant
into CLAUDE.md.

**Rigid-rod-body factoring (the second-instance abstraction — VALIDATED).** `RigidRodBody` factors the
entity-agnostic rigid-rod layout (planar-SoA pose / drag / accumulators / Brownian) that was previously
inline in `FilamentStore`. FilamentStore now EMBEDS one and ALIASES its existing public arrays to the
body's (same objects ⇒ the validated FDT/deflection/chain paths see identical arrays — zero behavioural
change). MotorStore embeds one of 3·nMotors sub-bodies (3m=rod, 3m+1=lever, 3m+2=head). The SHARED
device systems — `BrownianForceSystem`, `RigidRodLangevinIntegrationSystem`, `DerivedGeometrySystem` —
run over `MotorStore.body` **UNCHANGED** (they already took raw arrays; no motor-specific
reimplementation needed). **Abstraction-leak rule held:** the one genuinely entity-specific piece is the
DRAG FORMULA — the diff the second instance revealed is rod-drag (actin seg / myo rod / myo lever, the
shared `DragTensorSystem.rodDragSI` helper) vs **sphere-drag** (the myo head, `sphereDragSI`). That's
localized in the host-side drag init (a stored parameter), NOT a forked device system — exactly the
invariant ("entity-specific physics localized; never hardcode it in the shared systems"). The rod-drag
formula is now ONE helper used by both stores (FDT re-verified bit-identical after the extraction).

**MotorStore layout.** Articulated `body` (RigidRodBody, 3·nMotors) + the bed anchor point (`anchor`,
reused from 4a, = the rod's fixed `end1`) + `bodyParams`[dt,brownMag] + `jointParams` (J1/J2 PAIRS
coeffs, rest angles, stall cap). The 4a binding interface (head/uVec/rodUVec/bound-state/…) is PRESERVED
as a published projection of the body (`publishHeadFromBody` — the "repoint"; inert this increment, no
filament, wired for 4b-ii). Geometry from v1 (Env.java:776-778): rod 0.080, lever 0.008, head 0.020 µm;
radii 0.003/0.002/0.010.

**Joint law (`MotorJointSystem`) — faithful port of Myosin.applyRodLeverJointForce (J2) +
applyLeverMotorJointForce/Torque (J1).** Structurally the inc-2 chain joint: a `moveCoeff`-normalized
PAIRS connection spring (forceMag = fracMove·strain/(dt·(mcA+mcB)), applied at body centre + an explicit
½·len·fracR lever-arm torque toward the joint end) + a bend torque toward a rest angle. Specialized to
the myosin topology: J2 connects rod.end2↔lever.end1 (rest 96°, **angular spring OFF** — v1
`myoJ2FracMoveTorq=0`, so a free hinge, connection-spring only); J1 connects lever.end2↔motor.end1 (rest
**0° uncocked** — FIXED state this increment; angular spring on, capped at the stall-force torque
`stallForce·0.5·motorDim·1e-18`, Myosin.java:241). **Ownership (race-free, no atomics — the chain
pattern):** one thread per sub-body; each computes the joint contributions ON ITSELF and writes only its
own forceSum/torqueSum; a joint is evaluated from both endpoints (forceMag symmetric ⇒ equal/opposite).
`TailAnchorSystem` ports `applyRodFixedPtForce`: a connection spring pulling rod.end1 to the bed point
with moveC1=0 (fixed point immovable), FORCE-only (v1's torque is commented out), reaction discarded.
Lever gets NO Brownian (v1 MyoLever.moveThing Brownian commented out); rod+head get it (attn 1.0) — set
via the per-sub-body `brownTransScale`/`brownRotScale`. Sign convention nailed from v1
`Pt3D.unitVec(a,b)=unit(a−b)` (the springs are attractive). dt=1e-5 (v1 gliding regime); the PAIRS
relaxation is dt-independent (forceMag∝1/dt, displacement∝force·dt), so the joints are stable for
fracMove<1.

**Force-coverage audit** (every force on exactly one path): J2 connection (rod-side + lever-side,
equal/opposite) ×1; J1 connection (lever-side + head-side) ×1; J1 bend torque (lever + −head) ×1; J2
bend torque ×1 (=0); anchor force on rod ×1; Brownian ×1 (rod+head only). No double-apply, no silent
drop. TaskGraph: zero → brownian → joints → anchor → integrate → derive.

**Isometric validation (`./run_motorbody.sh`, 64 motors, M=5000, dt=1e-5, GPU + `-cpu`): PASS.**

| step | gapJ1(nm) | gapJ2(nm) | anchor(nm) | angJ1(°) | angJ2(°) |
|---|---|---|---|---|---|
| 0    | 6.7  | 8.4  | 9.4  | 13.0 | 3.9  |
| 1250 | 13.8 | 16.9 | 14.8 | 16.5 | 106.1 |
| 4999 | 13.6 | 18.3 | 17.9 | 15.4 | 97.8 |

Joint gaps bounded (<30 nm, ≪ the 8–80 nm body sizes) and non-growing over 5000 steps — the 2a
"holds-together" check for an articulated body. J1 angle ~15° about its 0° rest (thermal; the head is
the tiny high-D body the J1 spring restrains). J2 free hinge settles at the ~96° thermal mean of an
unconstrained lever+head dangling from the rod tip (faithful — there's nothing holding the head "up"
without a filament; the cocked state / a bound filament does that in 4b-ii/iii).

**CPU≡GPU — aggregate-statistics test (chaotic system).** The per-runner joint tables are byte-identical
to printed precision; the GPU vs CPU aggregate gaps/angles agree to <1e-4 nm / <1e-5°. The per-body
MICROSTATE trajectory diverges at the float-noise level (max|Δcoord| 1.5e-5 → 0.011 nm over 5000 steps)
— the fma/transcendental op-ordering divergence (inc-2's 0.99831/0.99832 finding), amplified by the
dynamics and bounded far below body size. Bit-identity is unattainable (and unnecessary) for a chaotic
thermal many-body run; this is exactly how v1's own gliding agrees CPU-vs-GPU (8.326 vs 8.231 µm/s,
within SEM). Gate = aggregate agreement + bounded microstate (no logic blowup).

**Existing paths unaffected (verified bit-identical):** FDT D_par 1.11676e-1 / D_rot 1.89712e1 (baseline
config N=2048 M=8000), deflection ratio 0.99832 / τ 0.9920, inc-3 broad-phase EXACT, 4a binding off-rate
0.00999 + reachable EXACT. The FilamentStore embed/alias + the DragTensorSystem rod-drag extraction are
byte-clean. No bail-out triggered. Ready for 4b-ii (cross-bridge + alignment torques + the cross-entity
gather, head ↔ pinned filament).

## 2026-06-14 — Increment 4a: myosin motors + binding detection (first narrow-phase consumer)
Motors as a SECOND entity type + the first narrow-phase consumer of the broad-phase: binding
detection + bind/unbind kinetics. **No motion this increment** — no power stroke, no surface
confinement, no gliding velocity (all 4b). **Bound motors apply NO force.** New: `softbox/MotorStore.
java`, `softbox/BindingDetectionSystem.java`, `softbox/MotorBindingHarness.java`, `run_motor.sh`; one
constant added to `SpatialBodyView` (`STORE_MOTOR=1`). **Everything else — the broad-phase, the grid,
FilamentStore, the Brownian/integration/derive systems — is UNCHANGED.**

**Entity-agnostic design VALIDATED.** The grid/broad-phase (`SpatialGrid`) needed zero changes: motors
register into the existing `SpatialBodyView` via a second publisher (`MotorStore.publishToBodyView`,
center=head, boundingRadius=reach, ownerStore=STORE_MOTOR, ownerSlot=slot), occupying body slots
[nFil, nFil+nMot). The consumer (`BindingDetectionSystem.invertCandidates`) consumes the broad-phase
candidate pairs and FILTERS by `ownerStore` to motor↔segment pairs — all `FilSegment`/`Motor` type
logic lives in the consumer, none in the broad-phase. `invertCandidates` handles BOTH pair orderings
(motor=i/seg=j and seg=i/motor=j), so it is independent of publisher layout in the view; single-thread
serial ⇒ race-free, deterministic, bit-identical CPU↔GPU.

**MotorStore layout (SoA, source of truth, device-resident).** Planar SoA (stride nMotors): `head`
(bindTip = body-view center), `uVec` (head axis), `rodUVec` (rod axis), `anchor` (viewer link only).
Scalars: `reach` (= myoColTol, also the body bounding radius). Bound-state in ONE int `boundSeg[m]`:
≥0 → bound to that segment slot; −1 → free & bindable; −2 → free in the one-step rebind refractory
(v1 myoRebindTime 1e-5 s < dt=1e-4 s). `bindArc[m]` = arc-length bind site. `stats[2m|2m+1]` =
per-motor (bound-step, release) counters (race-free; host sums). `kinParams` carries the v1 catch-slip
constants (kOff=100/s, αCatch=0.92, αSlip=0.08, xCatch/xSlip) + reach/alignTol + forceDotFil(=0).

**Kinetics — FAITHFUL v1 mechanism (planner decision).** v1 myosin binds DETERMINISTICALLY on contact
(modulo refractory) and releases via the force-dependent Guo–Guilford catch-slip rate
(`MyoFilLink.ckRelease`, p = kOff·dt·[αCatch·e^(−F·xCatch/kT)+αSlip·e^(+F·xSlip/kT)]). At zero force
(no power stroke this increment) catch+slip = αCatch+αSlip = 1 ⇒ the release probability reduces
**EXACTLY** to p = kOff·dt = 0.01 — so 4a's off-rate IS v1's, in the no-force limit, with NO tuning;
the catch/slip terms are carried inert for 4b. Binding is deterministic (nearest reachable segment,
no RNG). RNG (release only) is the REUSED v1 wang-hash keyed (slot, stepCount, runSeed) with a MOTOR
salt — integer-identical on both runners. **The prompt's k_on/(k_on+k_off) equilibrium does NOT apply
(v1 has no stochastic on-rate); validated the off-rate instead** (see below). The exact bind-reach
predicate (`reachTestDistSq`) is v1 `MyoMotor.checkFilSegCollision`: perpendicular drop of the head
onto the segment, gated by α∈[0,1], conDist<myoColTol(6 nm), the head-align gate (motDotFil≥−0.4) and
the rod gate (rodDotFil≥0). ONE predicate, called by both the grid path and the brute reference.

**Assay (`MotorBindingHarness`, default M=3000, brownScale=0.02, dt=1e-4).** Static gliding-assay-like
config: 200 filaments (10×20, along x at z=0) with one "reachable" motor at each filament's centre
(conDist≈0) + 100 "control" motors a z-offset (40 nm ≫ 6 nm) above the plane. Filaments diffuse
(Brownian) at REDUCED amplitude — v1's 6 nm reach is tiny next to a full-amplitude diffusion step
(~4.5 nm), so a stable geometric reachable set needs gentle motion; the off-rate is reach-INDEPENDENT
(faithful mechanism unbinds only via stochastic release, never reach-loss), so it is unaffected. Motor
rods tilted toward +x (normalize(0.3,0,1)) so the v1 rodDotFil≥0 gate clears with margin (a vertical
rod sits EXACTLY on the gate boundary for a horizontal filament — a coin-flip on the filament's tiny
z-tilt; tilting it took reachMot 105→200 at step 0).

**Gate 1 — reachable-set EXACTNESS (exact, no tolerance): PASS on BOTH runners.** computeReachable
(grid path, consuming broad-phase candidates) == bruteReachable (every motor×segment) EXACTLY at every
sampled step, GPU and `-cpu`. Control motors NEVER reachable (negative control clean). reachMot 200 at
step 0 decaying 200→98 as filaments diffuse out of the 6 nm z-reach (dynamic reachable set, exercising
the consumer). No candidate overflow (maxCand=20 ≪ 256).

| step | gridPairs | brutePairs | match | reachMot | control |
|---|---|---|---|---|---|
| 0 | 200 | 200 | EXACT | 200 | clean |
| 750 | 176 | 176 | EXACT | 176 | clean |
| 1500 | 138 | 138 | EXACT | 138 | clean |
| 2999 | 98 | 98 | EXACT | 98 | clean |

**Gate 2 — off-rate STATISTICS (tol 5%): PASS.** Empirical per-step release p = totalReleases/
totalBoundSteps = 4739/474367 = **0.00999** vs analytic kOff·dt = **0.01000** (rel err **0.10%**); mean
bond lifetime 100.1 vs 100.0 steps. This validates the stochastic release machinery + RNG keying.
meanBound=158 motors, boundFraction=0.79 of 200 reachable (< the τ_on/(τ_on+τ_off)≈0.98 ideal because
reachMot decays — motors that lose reachability stay free). **Not k_on/(k_on+k_off)** (v1 binds
deterministically) and **not v1's avgBound≈7.6** (that needs the 4b power-stroke force) — neither is a
4a gate; the analytic off-rate is.

**Gate 3 — CPU≡GPU: bit-identical.** reachable set, bound-state (boundSeg), and stats all identical at
every sampled step; final totals bit-identical (boundSteps 474367/474367, releases 4739/4739). Positions/
uVec are bit-identical CPU↔GPU (the inc-3 result), so the predicate — even at the gate boundaries — and
the integer RNG agree exactly ⇒ identical bind/release decisions.

**No force written → production paths unaffected (verified).** `bindKinetics` writes only boundSeg/
bindArc/stats, never forceSum/torqueSum; integration reads only the filament force accumulators, which
motors never touch. Re-ran inc-3 broad-phase (CPU, candidate set EXACT == brute) and the deflection
benchmark (ratio 0.99832, τ 0.9920) — both reproduce their pre-inc-4 numbers exactly. FDT/deflection/
chain are unaffected (force-coverage: motor binding applies zero force this increment).

**Viewer (`-3js <dir>`).** Bound motors drawn red with a rod from the anchor to the bound site on the
segment (the link); free motors blue, rod pointing up. Emitted in the v1 viewer's `myosins` schema
(rod/lever/motor composite) so the unmodified `sim_viewer_boa.html` renders binding/release. No fork of
the viewer; `FrameWriter` stays segments-only (the motor frame writer is harness-local).

Open: the dynamic reachable set decays over a run (filaments diffuse out of the tiny 6 nm reach); 4b's
surface confinement + gliding will keep filaments engaged. No bail-out triggered.

## 2026-06-13 — Increment 3: entity-agnostic spatial grid + broad-phase
Device-resident uniform grid (CSR) + broad-phase that emits candidate interaction pairs.
**Infrastructure, not physics — no forces written this increment** (the first narrow-phase consumer
is motors, inc 4). Gate: exact set-equality vs an O(N²) brute-force reference, on **both** GPU and the
`-cpu` runner, CSR bit-identical CPU↔GPU, O(N) vs O(N²) scaling. New: `softbox/SpatialBodyView.java`,
`softbox/SpatialGrid.java`, `FilamentStore.publishToBodyView`, `softbox/BroadPhaseHarness.java`,
`run_grid.sh`.

**Body view (the entity-agnostic seam).** `SpatialBodyView` represents any collidable body as a
bounding SPHERE: `center` (planar SoA, plane stride = capacity) + `boundingRadius` + back-pointer
`ownerStore`/`ownerSlot`. The grid + broad-phase read ONLY the view — zero `FilSegment` knowledge.
`FilamentStore.publishToBodyView` (a device step) is the sole publisher now: center = segment coord,
boundingRadius = ½·segLength + actin radius (sphere bounds the capsule), ownerStore=STORE_FILAMENT,
ownerSlot=slot. Nodes/membrane/motors register into this same view later.

**CSR build (ported from v1 GPUMotorBinding).** cellId = ix + iy·nX + iz·nX·nY. Passes: bodyCell
(center cell, clamped) → gridZero → gridHistogram → **two-level parallel prefix-sum**
(gridScanLocal per-chunk exclusive scan + gridScanChunks single-thread scan of chunk totals +
gridScanAdd add-base/reset-cursor) → gridScatter. The parallel scan is the hard primitive, ported
from v1's gridScanLocal/Chunks/Add (GRID_SCAN_CHUNK=512). Histogram + order-independent scan + serial
scatter (bodies in index order) ⇒ CSR **bit-identical** (offsets + within-cell order), not merely
multiset-equal.

**Binning choice → provable completeness + exact match.** Each body bins into the single cell of its
CENTER; cellSize = 2·maxBoundingRadius + cutoff. Then any pair within reach (centerDist ≤ rᵢ+rⱼ+cutoff
≤ 2·maxR+cutoff = cellSize) has center cells ≤1 apart in every axis ⇒ in the 27-cell stencil. The
broad-phase re-applies the EXACT predicate (`distSq ≤ (rᵢ+rⱼ+cutoff)²`, same as brute force) before
emitting, so over-scanned cells are filtered and none are missed ⇒ candidate set == brute set exactly.
Center binning ⇒ each body in one cell ⇒ pair (i,j) discovered once by thread i ⇒ the i<j guard dedups
with no min-corner logic (unlike v1's AABB binning, which needed it). Output = per-body owned slices
candPartner[i·MAX_CAND+k]/candCount[i] (race-free, no atomics; overflow detected + reported).

**FINDING — KernelContext atomics dropped for dual-runner portability.** v1's production
`gridHistogramKernel` uses `context.atomicAdd` (a TornadoVM KernelContext device construct) which CANNOT
run on the plain-Java `-cpu` runner. To honour the one-implementation invariant (every kernel runs on
BOTH runners), the histogram + scatter are single-threaded (`@Parallel` range 1, serial inner loop) —
exactly v1's `gridAssembleKernel` oracle structure: race-free, O(N), no atomics, no KernelContext.
Serial on the GPU but O(N) (the parallel work is the scan + broad-phase). A future parallel
chunk-ownership histogram/scatter (v1's gridScatterChunkKernel pattern, also atomic-free) can replace
them without breaking the invariant.

**Validation (`./run_grid.sh [N [M]]`, default 512×2000; also N=2048).** Bodies = free rods diffusing
(inc-1 brownian→integrate, translational only) in a density-fixed cluster; grid rebuilt every step;
candidate vs brute compared as order-independent sets at 5 sampled steps.

| check | N=512 | N=2048 |
|---|---|---|
| grid set == brute set (GPU), all steps | EXACT | EXACT |
| grid set == brute set (CPU), all steps | EXACT | EXACT |
| CSR bit-identical CPU↔GPU, all steps | yes | yes |
| candidate set identical CPU↔GPU, all steps | yes | yes |
| max candidates/body (MAX_CAND=256) | 19 | 23 (no overflow) |

candPairs densest at step 0 (2639 @512, 11153 @2048), falling as the cluster spreads — physically
right. A per-sample interior check guards 27-stencil completeness (flags any body clamped to a grid
edge cell rather than silently missing a pair); none triggered.

**Scaling (fixed density, CPU runner, work + timing):**

| N | candPairs | bruteTests | gridTests | grid(ms) | brute(ms) |
|---|---|---|---|---|---|
| 512  | 2639  | 130,816   | 26,610  | 0.281 | 0.332 |
| 2048 | 11153 | 2,096,128 | 129,606 | 1.211 | 4.854 |

×N=4: bruteTests ×16.0 (=N²), gridTests ×4.9 (≈N); brute(ms) ×14.6 (≈N²), grid(ms) ×4.3 (≈N). Grid
already beats brute at N=512 and is 4× faster at N=2048, gap widening. (gridTests ×4.9 vs ideal 4.0 is
a cluster surface effect — fewer neighbours per body at the smaller cluster's relatively larger
surface; vanishes as N→∞, clearly linear not quadratic.)

**No forces written → production paths unaffected (verified):** GPU FDT (D_par 1.11676e-1, D_rot
1.89712e1), deflection (ratio 0.99831, τ 0.9920), chain (max gap 0.04262 µm) all reproduce their
pre-inc-3 numbers exactly. Device-resident: grid built on-device each step; host reads only at sampled
validation steps (UNDER_DEMAND). No bail-out triggered. Ready for inc 4 (motors) to add the first
narrow-phase consumer on the body view.

## 2026-06-13 — Pre-inc-3 interlude: CPU validation runner + device-agnostic invariant
A debugging/validation interlude before the broad-phase. Stood up a **sequential CPU runner that
executes the SAME system methods** (no TaskGraph) as a second runner, and recorded the
device-agnostic invariant in CLAUDE.md. **One physics implementation, two runners** — NOT a second
engine. This is the CPU reference for triaging increment-3 bugs as physics-logic vs PTX-lowering.

**Invariant recorded** (CLAUDE.md → Architecture invariants): one physics implementation; each system
written once as a kernel method over the SoA arrays; the GPU TaskGraph is production, the same methods
run sequentially on the CPU as a debug runner; never hand-write a CPU/double reimplementation (that
recreates v1's two-sources-of-truth drift); stay single-precision, fix float problems with better
algorithms not a parallel double path.

**Audit — kernel/orchestration split (a finding: clean, NO refactor needed).** Confirmed every system
body is a plain static method over `FloatArray`/`IntArray`/primitives with `@Parallel` loops, and
contains **zero** TaskGraph-only constructs — no `TaskGraph`/`WorkerGrid`/`GridScheduler`,
no `DataTransferMode`/`FIRST_EXECUTION`/`UNDER_DEMAND`. All orchestration (transfers, per-task worker
grids, block-size-64 launch config) lives in the harness's `build*Plan` methods, never in a kernel.
Systems checked: `BrownianForceSystem.brownianForce`, `RigidRodLangevinIntegrationSystem.integrate`,
`DerivedGeometrySystem.derive`, `ChainBendingForceSystem.zeroAccumulators/chainForces`,
`DragTensorSystem.run` (host init), + the deflection support kernels
`DeflectionSupport.seedAccumulators/pinEndpoints`. The architecture the invariant asserts was already
in place; the runner is the proof. (`@Parallel` is a marker annotation with no effect outside Tornado
compilation, so a direct call runs the loop sequentially as plain Java.)

**Runner abstraction (`Stepper`).** Added a 2-method `Stepper` interface in `DiffusionHarness`:
`execute()` (one step) + `pull(arrays...)` (device→host at output cadence). `GpuStepper` wraps the
existing `TornadoExecutionPlan.withGridScheduler(sched).execute()` and `res.transferToHost(...)`;
`CpuStepper` runs a `Runnable` that calls the same system methods in the same per-step order, with
`pull()` a no-op (host arrays ARE the truth). Three CPU step sequences mirror the three TaskGraphs
exactly: FDT `brownian→integrate→derived`; deflection `seed→chain→integrate→pin→derived`; chain
`zero→brownian→chain→integrate→derived`. `-cpu` flag selects the runner. **GPU production path
untouched** — `cpu=false` issues the identical TaskGraph calls in the identical order, and the GPU
numbers below match the pre-change baseline exactly.

**GPU≡CPU agreement (same N/M/seed; float32 last-bit tolerance):**

| check                | quantity                | GPU         | CPU         | delta             |
|----------------------|-------------------------|-------------|-------------|-------------------|
| FDT (N=2048, M=8000) | D_trans_par (µm²/s)     | 1.11676e-1  | 1.11676e-1  | 0 (to 6 sig figs) |
|                      | D_trans_perp y / z      | 7.36203e-2 / 7.53293e-2 | 7.36203e-2 / 7.53293e-2 | 0 |
|                      | D_rot_perp (rad²/s)     | 1.89712e+1  | 1.89712e+1  | 0 (to 6 sig figs) |
| static deflection    | ratio obs/analytic      | 0.99831     | 0.99832     | 1e-5 (5th decimal)|
|                      | τ_meas / τ_theo         | 0.9920      | 0.9920      | 0                 |
| free chain (16 seg)  | joint-gap max (µm)      | 0.04262     | 0.04262     | 0                 |
|                      | mean gap mid→late (µm)  | 0.01397→0.01376 | 0.01397→0.01376 | 0           |
|                      | end-to-end / bend-RMS   | 2.785 µm / 2.02° | 2.785 µm / 2.02° | 0          |

Agreement is bit-identical to printed precision on FDT and the chain; the lone visible divergence is
the deflection ratio's 5th decimal (0.99831 vs 0.99832) — exactly the expected fma/transcendental
ordering difference, not a logic divergence. The integer Wang-hash RNG is bit-for-bit identical on
both paths, so the only source of difference is float op ordering, which stays sub-ulp on the
aggregate statistics even after 10⁴–10⁵ steps. **Every system proven dispatch-agnostic; the invariant
demonstrably holds.** No bail-out triggered.

Open: `runViz`/`measureLp` still GPU-only (not part of the 3 validation checks; both reuse systems
already covered by FDT+chain, so coverage is complete). `Stepper.pull` varargs emits one benign javac
warning (passthrough of `FloatArray[]` to the varargs `transferToHost`).

## 2026-06-13 — Increment 2b: filament characterization toolkit (manual tuning)
Ported v1's filament-characterization MEASUREMENT side (deflection ratio, relaxation time τ,
persistence length Lp) as a manual-tuning instrument + the BRotCoeff fidelity fix. **The auto-tune /
coefficient-search loop was deliberately NOT ported** (v1 DeflectionTuner*/the `eitherTunerActive`
block — cleanly separable; left in v1 for a later decision). Lp and τ are instruments validated
against v1's *measurement*, not biological-target gates.

**BRotCoeff fidelity fix.** v1 applies rotational Brownian only to chain-end segments (≥1 free end)
scaled by BRotCoeff=0.5 (interior=0; FilSegment.moveThing:633-642, `if(!filAtEnd1|!filAtEnd2)`,
Env.java:583). v2 chain/Lp paths now use `interior?0:BRotCoeff` (was 1.0) — completes the 2a interior-
vs-end correction. Free chain bend RMS 3.54°→1.98° (less end jitter, matches v1's appearance). Static
deflection ratio unaffected (Brownian off) and **inc-1 FDT still PASS** (it uses bare 1.0, not
Constants.BRotCoeff) — both re-verified.

**τ (DeflectionSupport / -deflect).** Load → steady → release (counts[3]=0 gates extForce in
seedAccumulators, no buffer re-upload) → 1/e crossing of the decay (log-interpolated) = τ_meas;
τ_theo = N·ζ_perp·span³/(EI·π⁴), ζ_perp=midSeg bTransGam.y (port of v1 BoxOfActin.java:2933). Result:
τ_theo=0.05697 s (v1 prints 0.057 — exact, same formula); τ_meas=0.05652 s, **τ_meas/τ_theo=0.992**.

**Lp (-lp / measureLp).** Port of v1 accumulateLpData + computeLpMeas: free Brownian 539-seg/48-µm
chain (matches v1 testLpFilLength=48, monomerCt=32 — both `static final`, so v2 must match), tangent
correlation C(k)=⟨u_i·u_{i+k}⟩ EWMA(α), Lp=−1/slope of weighted (w=C²) log-fit over C_k>0.01.

**Unified entry point (-characterize):** one command → `{deflection ratio, τ_meas/τ_theo, Lp_meas}`
for the current coeffs (override fracR/fmt via flags, BRotCoeff via Constants). ~40 s. Example output:
ratio 0.9983, τ_meas/τ_theo 0.9920, Lp_meas 1441 µm.

**Cross-validation vs v1 (fixtures/filament_characterization_v1.md):**
- ratio: ≤0.05% across fracR (TIGHT, from the deflection-benchmark session).
- τ: τ_theo exact; τ_meas/τ_theo=0.992. (v1's τ_meas needs an interactive force-release, not headlessly
  capturable; the deterministic relaxation is pinned by the ≤0.04% static-ratio match.)
- Lp: the **C(s) measurement reproduces v1 to <0.05%** (C(1) 0.9987 vs 0.9989, C(538) 0.7366 vs 0.7370
  at fmt=0.265 — proving instrument + physics faithful). BUT the **scalar Lp_meas is ill-conditioned**
  (v1 785 µm vs v2 1441 µm, ~2×): the uncalibrated chain is far stiffer than its 48-µm contour, so C
  barely decays and 1/slope is noise-dominated — intrinsic to the metric (present in v1; why v1 has the
  auto-tune and treats Lp as a diagnostic). NOT a port bug (C(s) match proves it); NOT bailed. Lp is a
  faithfully-ported diagnostic, not a tightly-reproducing scalar at uncalibrated coeffs.

So: ratio + τ are tight quantitative cross-checks; Lp's C(s) is tight, its scalar is an honest
ill-conditioned diagnostic. Manual-tuning instrument complete; auto-tune deferred.

Open / next: increment 3 (spatial grid + broad-phase). Still deferred: the auto-tune loop (planner
decision), and whether to expose aeta/segment-length as -characterize flags (currently fracR/fmt only).

## 2026-06-13 — Deflection benchmark: v2 ≡ v1 force/torque coding (+ low-fracR float32 fix)
Validated the 2a chain force law against v1's deflection benchmark, settled a fracR-direction
puzzle, and found+fixed a float32 precision limit at very low fracR (stiff filaments). This is the
foundation of 2b (pins + load); the full ratio/τ/LP fixture is still 2b.

**Setup (replicates v1 -bmDiag exactly).** `softbox/DeflectionSupport.java` (seedAccumulators puts the
load on the midpoint forceSum; pinEndpoints does v1's `incCoord(anchor-endpoint)` hard endpoint
snap-back each step -> pinned-pinned, free rotation) + `runDeflection` (-deflect flag). 11 seg ×
32-mon (segLen 0.0891 µm, span 0.9801), Brownian off, F = 48·EI·frac/span² on the midpoint center,
EI = kT·Lp (Constants.EI, Lp=15 µm), frac=0.01. v1 built read-only to /tmp/v1classes (worktree never
touched). Measured obs = perpendicular distance of the midpoint center from the anchor line, averaged
over the converged 2nd half (jitter quantified — both v1 and v2 are steady, ≤1.3% pk-pk at default
coeffs; jitter is parameter-dependent in general).

**fracR direction — RESOLVED: bigger fracR = softer (jba was right).** v1 deflection ratio rises
0.392→0.998→2.190→2.777 as fracR 0.025→0.1→0.4→0.8 (the loaded benchmark; v1's Env.java:135 "bigger
= stiffer" comment is misleading). The earlier free-chain sweep looked flat/opposite ONLY because
interior rotational Brownian (the 2a-FIX bug) swamped the fracR signal; post-fix the free chain
softens with fracR too (v2 3.50°@0.1 → 6.23°@0.8), matching v1's free LP chain (2.71°→9.83°). No sign
error — fracR enters only via the (byte-identical) F3 lever torque; in a *free* chain its effect is
weak (link forces are tiny without a load), strong under *load*.

**Identical-coding proof + low-fracR float32 limit + fix.** v2 reproduces v1's deflection ratio:
| fracR | v1 | v2 acos(dot) | v2 asin(\|cross\|) poly |
|---|---|---|---|
| 0.025 | 0.39198 | 0.40038 (2.1%) | **0.39184 (0.04%)** |
| 0.1 | 0.99842 | 0.99986 (0.14%) | **0.99831 (0.01%)** |
| 0.4 | 2.19003 | 2.19046 (0.02%) | **2.18990 (0.006%)** |
| 0.8 | 2.77652 | 2.77681 (0.01%) | **2.77639 (0.005%)** |
With the original `acos(dot)` bending-angle calc, v2 matched v1 to ≤0.14% for fracR≥0.1 but drifted
to 2.1% at fracR=0.025 — a real, converged gap growing as fracR→0. Root cause: **float32 catastrophic
cancellation in acos(dot)** for small joint angles (cos t = 1 − t²/2, so the angle lives in the
cancelling 1−dot part; ~half the digits lost). Fix (`ChainBendingForceSystem.angleFromSinCos`):
recover the angle from `|cross| = sin t ~ t` (first-order, float32-safe) via a hand-rolled
`accurateAsin` (Taylor seed + 2 Newton passes — PTX has no Math.asin/atan2, same reason v1 hand-rolled
accurateAcos; verified Math.atan2 throws "unimplemented" on the PTX backend). Hybrid: asin(|cross|)
for small angles (|cross|≤|dot|), accurateAcos(dot) mid-range. Result: low-fracR gap 2.1%→**0.04%**,
and it tightened every other point + killed the residual jitter. So the force/torque CODING is
identical to v1 (≤0.04% across the loaded range); the prior low-fracR drift was float32, now mitigated.

**Why it matters going forward (jba):** stiff filaments — microtubules (Lp ~ 1–6 mm, ~100× actin) —
live in the small-joint-angle regime where acos(dot) float32 breaks down. The asin-polynomial keeps
the angle accurate ~100s× stiffer before float32 bites, without going to a full double-precision
pose. Kept as the default (a v2 numerical-robustness improvement over v1's plain acos; mathematically
the same angle). Free-chain connectivity + FDT re-verified PASS after the change.

Open / next: ready to move on to the **next BoA→SoftBox port**. Still-open 2b items: the full
deflection ratio/τ + LP/persistence-length fixture, and the `BRotCoeff=0.5` end-segment rotational
Brownian calibration (v2 currently uses 1.0).

## 2026-06-13 — Increment 2a FIX: smooth bend (interior rotational Brownian)
jba reported the chain bent with a visually "not smooth" awkwardness. Root cause: the harness set
`brownRotScale = 1` for **every** segment, so interior segments each got an independent rotational
Brownian kick — rotating segment k+1 opens joint k and closes joint k+1, making **adjacent joints
bend in opposite directions (zigzag)**. v1 deliberately gates this: `rScale = (filAtEnd1 &&
filAtEnd2) ? 0 : rs` ("only apply brownian torques to end filaments.. best matches expected angular
correlations"). Fix: rotational Brownian only on chain-end segments (≥1 free end); interior segments
reorient only via the deterministic chain torques responding to (collective, smooth) translational
Brownian. Objective confirmation — adjacent-joint bend-vector correlation: **−0.157 (zigzag) →
+0.652 (smooth arc)**; bend RMS 9.6°→3.5°, end-to-end/contour 0.984→0.992; connectivity still PASS,
FDT free-rod path unchanged. (The 3.5° vs WLC 8.76° gap is a Brownian-magnitude/fracMoveTorq
**calibration** matter for 2b, not a 2a smoothness/connectivity concern.)

Also added diagnostics this session: `-dt <s>`, `-fracR <v>`, `-fmt <v>` overrides and an RMS-bend
stiffness readout. Sign audit (prompted by jba): the F3 lever (end2 `+uVec`, end1 `−uVec`) and F4
torsion (both ends) in `ChainBendingForceSystem` are **byte-identical to v1's device
`chainPairForcesKernel`** (and agree with v1 CPU) — no porting sign error. Sweeps: decreasing
fracMoveTorq softens (19.9°@0.05 → 7.7°@0.6) as expected (F4 restoring, confirmed); fracR has a weak,
non-monotonic effect on free-chain bending (min near 0.4) — note this is the opposite of "increasing
fracR softens"; v1's own Env.java comment says "bigger numbers are stiffer", so flag for jba whether
v1's fracR convention is intended (its calibrated role is the pinned deflection test = 2b, not the
free thermal chain).

## 2026-06-13 — Increment 2a: linked filament chain (connectivity first) — PASS
Activated the inert `end1Nbr*/end2Nbr*` topology (no storage reshape) and ported v1's real PAIRS
chain force law. A free Brownian chain holds together as a connected, semiflexible filament.
**Deflection assay (ratio/τ) and persistence length are deliberately deferred to 2b** — this increment
gates only on connectivity (visual + joint-gap), not calibration.

- **Force law ported:** v1 **device** kernel `GPUMoveThing.chainPairForcesKernel`
  (`GPUMoveThing.java:1551-1896`) — F3 link spring + F4 bending/torsion — into
  `softbox/ChainBendingForceSystem.java`, cross-checked against the CPU reference
  `FilSegment.addLinkForces`/`addTorsionSpringForces`. Ported the device version because it is
  already the per-segment, self-write, read-only-neighbor, NO-atomic kernel: each joint is computed
  from both segments' perspectives, **owner = lower slot index** defines the canonical link direction
  so the two are exactly anti-parallel (Newton-3); each segment applies +F (owner) / −F (non-owner)
  to its OWN slot only. `accurateAcos` ported verbatim. Internals double (as v1), pose read float,
  forceSum written float. Lab-frame forces → forceSum/torqueSum; integration transforms lab→body.
- **Side decode (the A1 trap) — mapping + verification.** `end?NbrSide==0` → my end glued to
  neighbor's **end1** (tip = ncoord − L/2·nu); `==1` → neighbor's **end2** (+L/2·nu). Matches v1
  `FilSegment.setEnd*Links:2818-2832`. Chain wired head-to-tail: my end2→next.end1 (side 0), my
  end1→prev.end2 (side 1), sentinel −1 at the two free ends. Verified THREE ways: (1) code-level check
  of the wired side values vs v1's derivation (OK); (2) runtime joint-continuity gap stays bounded;
  (3) **negative control** — deliberately flipping the side flags makes the gap diverge to 0.20 µm
  (>0.5·segLen) and the chain collapse (end-to-end/contour 0.16), which the test correctly FAILS. So
  the bounded PASS is meaningful, not trivial.
- **TaskGraph order:** `zero accumulators → brownian + chain (fill; independent/self-only writes) →
  integrate (reads forceSum+randForce) → derived (refreshes end1/end2 for next step's chain reads)`.
  Chain forces at step N read step-(N−1) derived geometry, as in v1. `zeroAccumulators` is a new first
  task (forceSum/torqueSum are now written, so they must be cleared each step).
- **Force-coverage audit** (each force applied exactly once):
  | source | frame | path | applied |
  |---|---|---|---|
  | Brownian randForce/randTorque | body | BrownianForceSystem writes → integration reads | once |
  | F3 link spring | lab | ChainBendingForceSystem self-write `+=` → integration reads | once / joint / segment |
  | F4 bending/torsion | lab | same | once / joint / segment |
  Action-reaction: for a joint (i,j), both threads compute the SAME owner-perspective `linkUVec` from
  the same geometry, so segment i gets +F and j gets −F (equal-and-opposite); each writes only its own
  slot → no atomics, no double-count. F4 torsion likewise (+/− across the pair by the side-consistent
  cross products).
- **Validation (16-segment free chain, monomerCt=64, segLen 0.1755 µm, 40 000 steps, fracMove=0.5,
  fracR=0.1, fracMoveTorq=0.265, aeta=0.1, filTorqSpring inactive → damped F4):** side-decode OK; max
  joint-continuity gap **0.0685 µm**, bounded (<0.5·segLen=0.0878) and **stationary** (mean
  0.0223→0.0238 µm, no growth over 4 s); no NaN; segment count conserved. The equilibrium joint
  "breathing" is ~0.022 µm thermal (≈8× actinMonoRadius — actinMonoRadius is just the spring's
  link-point offset, not the thermal amplitude). **Visually connected + semiflexible.** Bonus sign:
  end-to-end/contour = 0.98, matching the wormlike-chain value for L=2.8 µm at Lp=15 µm (v1's
  persistence length) — bending stiffness already in the right regime, though calibration is 2b's job.
- FDT free-rod path (inc 1) re-verified **unchanged** (−2.52/−1.15/+0.08/−1.80 %, PASS). Adding
  `chainParams` to FilamentStore is additive (not in the FDT graph). `view_run.sh`-style watch:
  `./run_gpu.sh -chain <dir> [nSeg [M]]` dumps frames + reports the gap; `threejs_chain*/` gitignored.

Open / next: increment **2b** — pins + midpoint force + the deflection ratio/τ (and LP) fixture, layered
on this already-correct chain force law.

## 2026-06-13 — Increment 1.5: file-based Three.js frame output (watch the rods)
Output-only — get eyes on the sim before chain/bending. Ported v1 `ThreeJSWriter`'s `segments`
emission into `softbox/FrameWriter.java` (a host IO utility, not a device system) and reuse the v1
viewer + server **verbatim** (`sim_server.py`, `sim_viewer_boa.html` copied unchanged, md5 confirmed).

- **Schema** (`segments` only, per constraint): `{"frame":N,"t":T,"bounds":{xDim,yDim,zDim},
  "segments":[{"id","end1":[x,y,z],"end2":[x,y,z],"r","notADPRatio":1.0,"cofilinCount":0}]}`,
  files `frame_%06d.json`, output-dir auto-increment (`.NNN`) ported from v1. Verified in the viewer
  JS that `myosins`/`minifilaments`/`nodes`/`contractility` are all `if(...)`-guarded and `bounds` is
  optional — a segments-only frame renders with **no viewer modification** (no empty arrays needed).
  `r = actinWidth/2 = 0.0035 µm` (Constants.radius), as v1. Per-segment JSON is one method so a future
  generic "bodies+links" schema is a localized swap (deferred to the planner, pre-motors).
- **end1/end2** are the derived geometry (end1 = coord − L/2·uVec, end2 = coord + L/2·uVec — same
  formula as `DerivedGeometrySystem`). FrameWriter reconstructs them on the host from the
  already-pulled canonical pose (coord+uVec) + segLength, so the output path adds **no device
  transfer** beyond the harness's existing output-cadence `coord/uVec` `UNDER_DEMAND` pull.
- **Bounds:** fixed cube, side = 2·(clusterHalf + 5·√(2·D∥·T_total)) — sized to ~5σ of the expected
  diffusive spread over the run; framing only, not physics (free rods have no walls). Viewer builds
  the box from frame 0.
- **Wiring:** `-3js <dir>` flag in `DiffusionHarness`. Present → a dedicated viz run (default N=200 in
  a compact 0.3-µm cluster, random orientations, both Brownian components ON at bare amplitude),
  frames written at the existing output-cadence pull. **Absent → FDT path byte-for-byte unchanged**:
  re-ran `./run_gpu.sh`, got the identical inc-1 numbers (−2.52 / −1.15 / +0.08 / −1.80 %, PASS).
- **Verified render-ready:** `./view_run.sh 200 4000` → 201 frames, 0 non-finite coords across
  241 200 values, segment length 0.1755 µm, midpoint spread grew isotropically 0.17→0.32 µm std
  (anisotropy 1.05); the magnitude matches FDT (effective isotropic D≈0.088 µm²/s ⇒ predicted final
  std 0.317 vs measured ~0.32). `sim_server.py scan_runs` detects the folder (201 frames). Frame
  output is gitignored (`threejs_output*/`).

Open / next: increment 2 (actin chain / bending forces) — unchanged from inc-1's note. The generic-vs-
per-type frame schema decision is deferred to the planner, before motors land.

## 2026-06-13 — Increment 1: filament rigid-rod overdamped Langevin slice — FDT PASS
First real code. `softbox/` package: SoA component-array core + four named systems as TornadoVM PTX
kernels, validated against the fluctuation–dissipation (Einstein) relation on the aorus RTX 5070.
Built/ran with the v1 toolchain (Java 21 + TornadoVM 4.0.1-dev PTX, `--enable-preview`, `-g`,
`@tornado-argfile`). No chain/bending forces, neighbors, walls, motors, membrane, or biochem.

**Component-array layout (FilamentStore — the canonical store).** Pose `coord`/`uVec`/`yVec`;
derived (recomputed, NOT source of truth) `zVec`/`end1`/`end2`; geometry `monomerCount`→`segLength`;
body-frame diagonal drag/diffusion `bTransGam`/`bRotGam`/`bTransDiff`/`bRotDiff`; per-step
accumulators `forceSum`/`torqueSum` (deterministic, zeroed) + `randForce`/`randTorque` (Brownian);
per-rod `brownTransScale`/`brownRotScale`; inert chain topology `end1NbrSlot/Side`,`end2NbrSlot/Side`
(sentinel −1 = free; integer (slot,side) from birth per migration-doc A1, ready for increment 2 to
read without reshaping).

**FLAGGED layout decision — planar SoA, not one-array-per-component.** TornadoVM's `task()` tops out
at 15 args (`TornadoFunctions$Task15`); the integration kernel needs ~27 component planes, so strictly
separate `xPos[]`/`yPos[]`/`zPos[]` FloatArrays are impossible to launch. Each vector quantity is one
device buffer in planar `[X-plane | Y-plane | Z-plane]` layout (x's contiguous, then y's, then z's) —
genuine SoA (coalesced, non-interleaved), NOT AoS `[x0 y0 z0 x1…]`. Named `coordX/Y/Z()` plane
accessors keep each component findable. The architectural invariant (no per-object fields, no AoS
interleave, device-resident) holds; the packing is a device-arity accommodation only.

**Four systems (free functions over arrays; each one identifiable physics).**
1. `DragTensorSystem` (host, runs once) — ports `FilSegment.calculateProperties()` line-for-line.
2. `BrownianForceSystem` (device) — fills `randForce`/`randTorque` body-frame from diffusion tensors +
   the REUSED v1 device RNG (Wang hash + Box-Muller, keyed `(slot,stepCount,runSeed)`; verbatim from
   `GPUMoveThing.moveThingKernel`/`wangHash`, per RESIDENCY_INVENTORY §3 — no new RNG invented).
3. `RigidRodLangevinIntegrationSystem` (device) — overdamped Euler: body force = Rᵀ·forceSum +
   randForce → `bVeloc = 1e6·bForce/bTransGam`; body torque → `deltaBAng` rotation of uVec/yVec;
   port of `FilSegment.moveThing()` fused with the v1 device integration, minus inlined Brownian.
4. `DerivedGeometrySystem` (device) — recompute `zVec`/`end1`/`end2` and re-orthogonalize `yVec`
   (port of `Thing.recomputeDerivedSoA`). Output only this increment.
All four run as kernels over resident arrays in one TaskGraph (brownian→integrate→derived); pose is
FIRST_EXECUTION + pulled UNDER_DEMAND only at output cadence — **no per-step host pose pull**.

**Force-coverage audit (this slice has exactly two force sources).**
| force source | path | applied | notes |
|---|---|---|---|
| deterministic `forceSum`/`torqueSum` (lab) | init 0; no kernel writes it | genuinely **zero** | free rod: no chain/wall/motor/node forces exist yet |
| Brownian `randForce`/`randTorque` (body) | `BrownianForceSystem` writes once/step; `RigidRodLangevinIntegrationSystem` reads once | **exactly once** | no double-count; a 2× would give 4× D (+300%), not the observed ∓2% |
Verdict: every force applied on exactly one path — never zero-by-accident (the zero is real), never
twice. The FDT pass at the bare amplitude confirms no missing/extra factor.

**Code-fidelity cross-check (γ formula, code-level not run).** `DragTensorSystem.run()` reproduces
`FilSegment.calculateProperties()` (v1ref `FilSegment.java:420-441`) arithmetic byte-for-byte:
`bTransGam.x=(2πη·Lₘ)/(ln(Lₘ/2rₘ)+aParallel)`, `.y=.z=(4πη·Lₘ)/(…+aOrthog)`, `bRotGam.x=4πη·rₘ²·Lₘ`,
`.y=.z=(πη·Lₘ³)/(3(…+aTurning))`, then Einstein `D=kT/γ`; same min-length clamp (`stdSegLength·halfmono`
for a free/at-end rod), same `length=(monomerCt+1)·actinMonoRadius`, same constants (Boltz, tempK,
aeta=0.1, aParallel/aOrthog/aTurning). Only diff: SoftBox reads `Constants.*` where v1 reads
`Env.*.getValue()` (identical values). FDT self-consistency + faithful γ together pin both the
amplitude→drag coupling and the tensor values.

**Measurement protocol.** N=8192 free rods, monomerCt=64 (L=0.1755 µm), dt=1e-4 s, aeta=0.1 Pa·s.
- Config T (translational anisotropy): rotational Brownian OFF, orientation frozen along lab-x so body
  axes ≡ lab axes; M=20000 steps, pose pulled every 200; per-axis MSD slope through origin → D = slope/2.
- Config R (rotational): translational Brownian OFF; M=4000, uVec pulled every 20; orientational
  autocorrelation C(t)=⟨uₓ(t)⟩ fit to exp(−2D_rot·t) over C∈(0.2,0.95) (22 samples).
Both B-coefficients set to 1.0 so the bare relation D=kT/γ holds; v1's production BTransCoeff=1/
BRotCoeff=0.5 (and the lone-segment rot-Brownian-off rule) are biological persistence-length tuning
knobs, deliberately out of scope for the amplitude-coupling check.

**Validation numbers (measured vs FDT prediction from the SAME γ arrays; tol 5%).** γ_par=3.649e-08,
γ_perp=5.430e-08 N·s/m; γ_rot⊥=2.211e-22 N·m·s/rad.
| quantity | measured | FDT D=kT/γ | relErr |
|---|---|---|---|
| D_trans∥ (µm²/s) | 0.10996 | 0.11280 | −2.52% |
| D_trans⊥ y (µm²/s) | 0.07494 | 0.07581 | −1.15% |
| D_trans⊥ z (µm²/s) | 0.07587 | 0.07581 | +0.08% |
| D_rot⊥ (rad²/s) | 18.280 | 18.615 | −1.80% |
**FDT VALIDATION PASS.** Tolerance 5% justified a priori: float32 (v1 GPU path precision) + ~1/√N
ensemble noise + O(D·dt) first-order-Euler bias. The small consistent negative bias (≈−1 to −2.5%) is
that Euler/float32 systematic, not a wrong factor (which would be tens of %). Per Lesson 6 we did NOT
reach for double precision — the magnitude argument says a wrong integration factor is far likelier
than float rounding moving D by a measurable amount, and here the factor is right.

Two TornadoVM gotchas worth recording: (1) a kernel method literally named `kernel` collides with an
OpenCL/PTX reserved token — rename (we use `brownianForce`/`integrate`/`derive`); (2) the default
block size overflows the register file for these (RNG-/trig-heavy) kernels → CUDA 701
(LAUNCH_OUT_OF_RESOURCES) — set an explicit `WorkerGrid1D` localWork=64 via a `GridScheduler` keyed
`"rodLangevin.<task>"` (matches v1 `MOVE_KERNEL_BLOCK_SIZE`). Run log: `RUN_LOGS/2026-06-13_inc1_fdt.log`.

Open / next: increment 2 — actin chain / bending-force system. Starts READING the inert
`end1Nbr*/end2Nbr*` topology arrays laid down here (no storage reshape). The derived end1/end2
sign convention (end1 = coord − L/2·uVec) is now the one chain forces will read.

## 2026-06-13 — Increment 0: workspace scaffolded
Soft Box repo initialized at `~/Code/SoftBox` as a new repo (not a BoA branch). Frozen-v1 reference
set up as a read-only `git worktree` at `~/Code/BoA-v1ref`, detached at tag
`softbox-filref-2026-06-13` (pinned at v1 `main` HEAD; will re-point to `biology-production-v1` once
the v1 finish line is reached). `CLAUDE.md` seeded with the architecture invariants (integer-ID
entities, SoA component arrays as source of truth, systems as free functions, device residency from
day one), the reference/oracle discipline (fixtures frozen as data, not v1 code; read current main
for physics; reconciliation on v1 physics changes), the porting discipline (force-coverage audit,
minimal-repro first), and the proposed increment sequence (filament slice first). No physics yet.

Open / next: increment 1 — planner to design the FilSegment component-array layout + rigid-rod
Langevin integration system, with the v1 deflection / relaxation / LP benchmarks as the fixture.
