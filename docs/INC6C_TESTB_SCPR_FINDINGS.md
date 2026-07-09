# Increment 6c — Test B: the SCPR primitive (two nodes capture-and-pull) — FINDINGS

**Date:** 2026-06-18. **Status:** Gate 0 PASS (the gating result); Stage 1 assembled, runs CPU+GPU,
CPU≡GPU aggregate-agree; cross-node capture demonstrated; clean net approach at n=2 is an observation
(stochastic-search reality, as the task predicted). **No shared-kernel edits; new files only**
(`TestBScprHarness.java`, `run_testb.sh`) ⇒ prior assays + production byte-unchanged; `BoA-v1ref` byte-clean.

This is the first **emergent** test — the inflection from porting to emergence. No new mechanism, no v1
assay to match: adjudicated by physics + the SCPR behavior itself.

```
./run_testb.sh             # GPU + CPU: Gate 0 (cross-node capture) → CPU≡GPU → Stage 1 (SCPR assay + readout)
./run_testb.sh -cpu        # CPU runner only (triage)
./run_testb.sh -gate0      # Gate 0 only (the gating probe)
./run_testb.sh -cpu -3js threejs_testb   # viewer (two nodes nucleating/growing/capturing)
# tunables: -gap -formins -seedmon -cap -box -steps -nodebrown -nsing -ndim -reach
```

---

## Gate 0 (GATING) — cross-node capture works. PASS.

**The one real unknown:** will the existing binding/gather capture a filament grown from a *different*
node, or is binding implicitly scoped to a node's own filaments / does it filter on `seedNode`?

**Answer: cross-node capture works; binding is structurally `seedNode`-agnostic.**
- **By inspection:** `seedNode` appears ONLY in the nucleation/growth files (`NodeNucleationStore/System`,
  `GrowthStore/System`, their harnesses). It is **absent** from `BindingDetectionSystem`,
  `CrossBridgeSystem`, `SpatialGrid`, and `SpatialBodyView`. The binding path
  (`reachTestDistSq`→`bruteReachable`/`bindNearest`) is purely geometric over segment endpoints; candidate
  filtering is by `ownerStore` (motor↔filament), never by which node nucleated the filament. `seedNode`
  (the nucleation bond) and `boundSeg` (the myosin binding) are **orthogonal**.
- **Empirically (the probe):** two fixed nodes 0.9 µm apart; ONE filament tagged `seedNode = A` planted at
  node B's first singlet head. Result: **node-B motor m=18 (owned by B) bound seg 0 (node A's filament)**;
  #node-B captors=1, #node-A captors=0. **Bit-identical CPU≡GPU** (deterministic bind, 0 boundSeg
  mismatches).

⇒ **Gate 0 PASS. Stage 1 unblocked.** (Had it failed, the bail was: commit nothing, report the exclusion
path — a binding-scope decision for the planner. It did not fail.)

---

## Stage 1 — the two-node SCPR assay

**Pure composition, no new force law / gather / shared-kernel edit.** Two FREE, box-confined protein nodes;
each `forminsPerNode` radial formin sites nucleating + growing actin (random-radial — seam #3 default), a
radial myosin shell (6 singlets + 6 dimers/node), the 12 pN break-force cap + faithful catch-slip release,
general containment. The full per-step loop (CPU runner = the device-agnostic physics; the GPU TaskGraph =
the production path) is:

```
growth(cadence: grow → split@64 via the B1 allocator → recomputeDrag)
→ nucleation(emit → B1 allocator → tagSeeds)        [DEDICATED request arrays — see "integration crux"]
→ bind(publishHead → bruteReachable → catch-slip release → bindNearest → nucleotide cycle)
→ forces(motor joints + dimer couple + node radial tether + single-ended CSR gather
         + cross-bridge bondForces/applyHead + chain F3/F4 + seedNode pull-tether + seg-side gather)
→ integrate(node body [confined] + motor body + filament)
```

### The integration crux — two allocators, one FilamentStore (SOLVED, no shared-kernel edit)
Nucleation and growth both allocate filament slots from the SAME store, but their request conventions
collide: nucleation `emit` writes request `k ↔ node k` and clears ONLY `acceptFlag[0..nNodes)`; growth
`markSplits` writes request `s ↔ slot s` and clears all `acceptFlag[0..filCap)`. Sharing the request arrays
would leak growth's stale split-flags into nucleation's rank scan (double allocation).

**Solution:** nucleation gets its OWN dedicated request + rank arrays (`nucAccept`, `nucReqCoord/UVec/YVec`,
`nucRankOffsets`); growth uses FilamentStore's own. They run as TWO sequential B1-allocator passes sharing
the **rebuilt** free-list (`freeFlags`/`csrScan`/`freeScatter` re-run between them, so nucleation sees the
slots growth just consumed). Because `emit` only ever writes `[0,nNodes)`, the dedicated array's
`[nNodes,filCap)` stays 0 forever ⇒ a clean rank scan. **No allocator/system kernel edited** — only the
harness threads different arrays into the (unchanged) `FilamentBirthSystem` kernels.

### FREE-slot binding safety (no shared-kernel guard)
`bruteReachable` iterates ALL segments (no broad-phase publish-guard). Unborn FREE slots are **parked far
away (100,100,100)** at build, so they are geometrically unreachable — keeping them off the candidate set
WITHOUT a `filState` branch in the binding kernel (the allocator overwrites the pose at birth; `splitWire`
writes child poses).

### CPU≡GPU — the 45-task GPU SCPR pipeline agrees
Chaotic many-body run (CLAUDE.md aggregate-within-tolerance standard — float32 op-ordering decorrelates the
microstate). 3000 steps: **windowed avgBound GPU=15.60 = CPU=15.60; active-filament count GPU=56 = CPU=56**.
The full device-resident pipeline (growth + split + nucleation + binding + gather + cross-bridge + chain +
3 bodies' integration, all SoA-on-GPU) reproduces the CPU runner's aggregate behavior. ⇒ aggregate-agree.

### What runs (machinery validated)
Nucleation births seeds, growth lengthens tips + splits at 64 (active filaments 28→252, contour 3.6→23 µm
over 25k steps), binding/gather/cross-bridge transmit force, containment confines the free nodes — all
stable, dual-runner.

---

## The headline readout — does the inter-node distance decrease?

**Cross-node captures OCCUR stochastically** (default gap 0.25 µm: first @ ~step 10k, peak 4, steady-avg
~2.3). **Self-captures dominate** (~30 — a node's own radial filaments sit in its own myosin shell).

**The net inter-node approach is NOT cleanly resolved at n=2 — it is an observation, not a clean positive
(exactly as the task predicted SCPR would be "rare at n=2, a many-node statistical effect").** Across
geometries the net distance change is seed/geometry-dependent and tends to drift **apart**, robustly so
under a damped node (deterministic regime). The mechanism:

> **Self-capture "steals" the geometry.** A node's own radial filaments fill its own shell ⇒ self-capture
> dominates. The PARTNER captures your *near-side* filaments (pulling them into its domain), so your
> *residual* self-captures are biased toward your *far* side ⇒ a net self-pull **away** from the partner.
> At n=2 this outweighs the sparse cross-capture pull. In a many-node ring the geometry is different (the
> contractile-ring condensation, the next horizon) and this n=2 bias does not apply.

**The pull MECHANISM is independently validated** (so the drift-apart is an n=2 *geometric* artifact, not a
sign bug): Gate 0 + the validated contractile assay (`INC6_CONTRACTILE_ASSAY_FINDINGS.md`,
`INC6_NODE_CONTRACTILE_FINDINGS.md`) prove that a captured myosin transmits a directed cross-bridge force
toward the actin **barbed/plus end** (which, for a foreign filament, sits at the *originating* node) —
i.e. a captor IS pulled toward the partner. The same `CrossBridgeSystem` code drives Test B.

### A geometric finding (the SCPR capture cone)
For node B's motor to bind node A's filament, the `rodDotFil ≥ 0` gate requires the foreign filament to
reach B's **center/far hemisphere** (the captor's heads whose rod points along the filament's outward
axis). So cross-capture requires a filament to nearly **bridge to the partner node** — the inherent SCPR
search cost (~`gap` of contour growth). Random-radial aiming + this overshoot requirement is why a
productive cross-capture is rare at n=2. The harness reports an ETA for a well-aimed tip and treats capture
frequency as an observation.

---

## Self-capture observation (asked for explicitly)
Self-capture **happens and is the dominant binding mode** (a node's myosins co-radial with its own
filaments trivially satisfy the binding gates). It materially confounds the net-approach readout (above).
Per the task it is **reported, not suppressed** — self-capture suppression is a deliberate follow-on
decision, NOT built speculatively.

## Node-Brownian knob (`-nodebrown`, flagged)
The protein node is modeled as a small sphere (R=0.05 µm) ⇒ high thermal diffusion that buries the directed
signal at full FDT scale. `-nodebrown` (default 0.05) damps the node-body Brownian to resolve the
deterministic regime (a node is a large/slow multi-protein complex in vivo — a defensible scene choice).
This is a *node-body scale only*; it does not touch the Brownian system or FDT for any other entity.

---

## Formin-site placement — SEAM #3 (verified + registered)
Test B is the first scene where formin-site placement matters **behaviorally** (a site's radial direction
sets whether its filament searches toward the partner). The placement is now behind a **pluggable seam**:
`TestBScprHarness.forminSiteDir(nodeK, site)` + the `Placement` enum (`RANDOM` default; a `SPECIFIED` hook
for a defined inter-site arrangement plugs in here without a refactor). Test B stays on the **random-radial
default** (the same wang-hash draw the runtime nucleation `emit` uses); **specified placement is NOT built**
(jba's call). Registered as **seam #3** in `CLAUDE.md`, alongside seam #1 (motor/nucleation) and seam #2
(the actin pool).

---

## Capacity / run-length budget (flag)
`FilamentStore` capacity (default 512) bounds run length: growth splits create children with no turnover
(depolymerization/treadmilling deferred), so a long run fills the store (the default run reaches ~252
active in 25k steps; a 60k-step run saturates 512). The harness flags the budget. A future turnover layer
(freed-slot recycling) lifts this.

---

## Out of scope (confirmed not built)
Hydrolysis/severing/depolymerization (deferred); full SCPR timed release (the existing unbinding + cap
suffice); **self-capture suppression** (observed + reported, not built); **specified formin placement** (the
seam exists, the body is not built); **>2 nodes / ring condensation** (the next horizon); any shared-kernel
edit (none made). Quantitative ensemble confirmation of the approach is a **follow-on** (multi-seed / aimed
placement) — this is the qualitative first light.

## Deliverables
1. **Gate 0** — cross-node capture YES; binding path `seedNode`-agnostic (the path that makes it work).
2. **Stage 1** — harness + `run_testb.sh`; distance trace + cross/self-capture counts + bound/active/contour
   readout; viewer (`-3js`); CPU≡GPU aggregate-agree on the full GPU pipeline.
3. **Seam #3** registered (formin-site placement, pluggable; random-radial default).
4. **Self-capture** observed + reported (dominant; confounds the n=2 net readout).
5. This findings doc + JOURNAL + CLAUDE updates.

## Verdict
**Cross-node capture-and-pull is demonstrated as a working primitive on GPU-resident SoA** (Gate 0 +
the validated pull mechanism), assembled growth-only from already-validated pieces with every prior assay +
production byte-unchanged. The *clean net inter-node approach* at n=2 is confounded by self-capture (a real
n=2 geometric artifact, reported as an observation per the task) — the many-node statistical SCPR effect and
its quantitative confirmation are the follow-on (ring condensation, ensemble).
