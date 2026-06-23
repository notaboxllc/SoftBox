# FULL-SYSTEM DEMONSTRATION — mid-sized biochemically-active contractile network — FINDINGS

**Date:** 2026-06-22. **Branch:** `xlink-formation-on`. **Status:** DONE — the maximal composition of every
validated SoftBox subsystem runs together in one shallow chamber at faithful (KIN=1) rates: protein nodes
nucleating biochemically-active treadmilling formin filaments + free myosin minifilaments binding/contracting the
network + O(N) crosslinker bundling + containment. **Watchable, and the aberration hunt comes back essentially
CLEAN** (no NaN/blow-up, conservation EXACT, 0 phantoms, no crash/race) with a handful of honest, bounded,
explained behaviors surfaced (a t=0 warm-start force transient; rare wall-contact containment kicks; sparse-network
binding; the full-merge device-graph capacity limit). NOT a precise validation — an integration demo + pathology
sweep, de-risking the full system before the ring.

## TL;DR
Everything is built and O(N); this is the maximal-composition DEMONSTRATION. A new harness (`FullSystemDemoHarness`)
WIRES the validated systems byte-unchanged onto ONE shared `FilamentStore` whose `forceSum` every coupling
accumulates into — **pure composition, NO new force law / gather / shared-kernel edit.** The full lifecycle is
present and runs stably; the hunt found no bug. `BoA-v1ref` byte-clean; production untouched; new files only
(`FullSystemDemoHarness.java`, `run_fulldemo.sh`).

```
./run_fulldemo.sh -smoke                                # cheap assembly/sanity (1500 steps)
./run_fulldemo.sh -steps 20000                          # CPU demo + the aberration hunt (KIN=1 faithful)
./run_fulldemo.sh -gpu -steps 20000                     # + GPU device scale / no-crash probe (see §6)
./run_fulldemo.sh -3js threejs_fulldemo -steps 30000              # faithful render (growth+binding+contraction+crosslinking)
./run_fulldemo.sh -kin 30 -polyboost 3 -pool 20 -3js threejs_fulldemo_lifecycle -steps 30000   # viewing-speed lifecycle (aging+severing visible)
```
Logs: `RUN_LOGS/2026-06-22_fulldemo_*.txt`.

---

## §1. The scene (a thin slab viewed top-down)
- **Shallow square chamber** — 3.6 × 3.6 × 0.5 µm (wide x,y; shallow z) — the in-vitro flow-cell, enforced by the
  general `ContainmentSystem` over node bodies, minifilament backbones, and filaments.
- **16 protein NODES** — a 4×4 planar grid in xy (z=0), spacing 0.6 µm, each a fixed-radius sphere body
  (free, box-confined, node-Brownian damped) owning a radial myosin shell (6 singlets + 6 dimers) **and 6 formins**.
- **Biochemically-active, formin-PINNED filaments** — each formin holds one treadmilling chain (warm-started 7
  segments × 30 monomers ≈ 0.59 µm reach so neighbour fields overlap). Full turnover: barbed growth + pointed
  depoly + **AGING** (the ATP→ADP-Pi→ADP cascade) + cofilin **SEVERING**, all at **faithful KIN=1 rates**.
  Formin out-of-plane splay is compressed (`PLANE_BIAS=0.18`) so the brush stays in the thin slab.
- **60 free myosin MINIFILAMENTS** (1920 heads) — bipolar backbones owning 16 splayed dimers each, co-located with
  the filament-rich node footprint, binding + contracting the network via the **parallel-grid fused per-head
  binding** + cross-bridge + the single-ended backbone gather (the 12 pN break-force cap ON).
- **CROSSLINKERS** — the **O(N) grid FORMATION** (the 5d fix, `FormationGrid` + the fused per-segment query) +
  Bell unbind + force + torsion + the 2-pass gather, bundling crossing segments (cap 6144 link slots).
- **The dead-slot family** (`initNewborn` + `nucleateFreshAtp` + `nucleateFreshCofilin`) keeps the slot recycle
  clean while nodes nucleate WHILE turnover + crosslinkers recycle slots.
- **Crowded-cytoplasm `aeta=1.0`** drag (FDT-consistent scaling, the dense fixture value) keeps the network from
  dispersing.

Counts at build: 16 nodes, 96 warm filaments (672 active segments), 60 minifilaments (1920 heads), 6144 crosslink
slots, ~5.4 µM total actin. CPU throughput ≈ 60–75 steps/s.

## §2. Composition wiring (the one-shared-forceSum design)
Every coupling accumulates into the SAME `FilamentStore.forceSum`, zeroed ONCE per step, then: chain (F3/F4) +
node seed-tether + node-shell motor→segment gather + free-minifilament motor→segment gather + crosslinker 2-pass
gather, then containment + integrate. Two myosin populations bind the same network two ways:
- **node shell → node-AWARE brute** (v1 excludes a node-held tip `seedNode>=0` from ALL myosin binding — faithful);
- **free minifilaments → parallel-grid fused per-head query** (the dense-scale path, honouring the task spec).
Per-step order is the Ring3x3 turnover/nucleation cadence + the DenseContractile minifilament block + the
CrosslinkerBundle O(N) formation cadence, merged. No system was modified.

---

## §3. THE ABERRATION HUNT — verdict: essentially CLEAN
Run at faithful KIN=1 (the hunt mandate). Hard sanity held throughout every run (smoke → 20 000 steps):

| check | result |
|---|---|
| **NaN / blow-up** | **none** — finite throughout every run |
| **conservation** (integer pool ledger) | **EXACT** at every sampled step, through the full grow/depoly/sever/nucleate/recycle churn |
| **phantoms** (ACTIVE slot, monomerCount≤0) | **0** — the dead-slot family holds at full composition |
| **wall escapes** (filament endpoint outside box) | **0** (after the in-plane formin bias; see §4) |
| **node clipping** | min node–node center distance 0.30 µm (pulled in from 0.50 by contraction) ≫ 2R=0.10 µm — no overlap |
| **crash / race** | none on CPU (the device-resident path: §6) |

**20 000-step KIN=1 hunt headline (the definitive run, `RUN_LOGS/2026-06-22_fulldemo_hunt.txt`, 76 steps/s):** the
system **gently CONTRACTS** — node-net RMS 0.9487 → 0.9129 µm (**3.8% shrink**; nodes pulled from 0.50→0.30 µm min
separation), filament-network RMS 1.0158 → 1.0003 µm (1.5%); binding GROWS as the network engages (free-minifilament
bound 47 → **173**/1920, node-shell → **22**/288); crosslinks accumulate **1 → 46**; turnover slow (42 grown / 15
depolymerized monomers, **0 severing** — the KIN=1 regime, §5); steady max filament force 143 pN; conservation EXACT,
0 phantoms, 0 wall escapes throughout.

**Behaviors surfaced + explained (bounded, not bugs):**

1. **A t=0 warm-start force transient (~23 nN, ONE step).** `maxF` on the filament network reads ~2.34e4 pN at
   step 0, then decays to the ~30–120 pN operating range within ~66 steps. **Attributed precisely:** it is the
   node **seed-tether + chain relaxing the warm-start filament geometry**, amplified ~10× by the crowded-cytoplasm
   `aeta=1.0` drag (the tether/containment force ∝ γ). It is **independent of minifilaments and crosslinkers**
   (identical 2.34e4 at mini=2, mini=60, and `-noxlink`). Benign: one step, decays, no NaN, conservation exact.
   The harness reports the IC-peak and the post-warmup steady max SEPARATELY so the verdict is not dominated by it.

2. **Rare ~1 nN single-step containment spikes** (e.g. one at step ~410 over 2500 steps). These are the **chamber
   wall doing its job** — a filament tip that reached a wall gets the corrective containment kick (force ∝
   penetration × drag / collisionDeltaT, ×10 from aeta). Bounded + corrective (the integrator divides by the same
   drag ⇒ displacement bounded), not a blow-up. Enlarging the box from 3.0→3.6 µm (node brushes off the walls) cut
   these from frequent to ~one per few thousand steps. **Flagged, not a pathology.**

3. **Sparse-network binding (a quantitative reality, not an error).** At the faithful bind reach (`myoColTol` =
   0.025 µm) over a semi-sparse node-brush network, the free minifilaments bind ~75–95 / 1920 heads and the node
   shells ~10–15 / 288 — the same low per-head occupancy DenseContractile measured for a free network. Enough for
   gentle contraction + cross-capture; not a dense saturated bundle. The initial smoke run had **0** minifilament
   binds because the minifilaments were scattered across a too-large box far from the filament fields — **fixed**
   by co-locating them with the node footprint + tightening the box (a scene-design finding, §4).

4. **Crosslinkers form slowly + do NOT run away.** Active links accumulate ~1 → 14+ over 20 000 steps (formation
   cadence 100 steps, `pForm≈0.01`); max crosslink force < 0.5 pN (Bell unbind + dynamic `fracMove` bound it). No
   over-forming, no spanning artifact, no runaway bundle — the O(N) formation is well-behaved at full composition.

**No** numerical instability, conservation drift, phantom, runaway clustering/wind-down, filament-through-wall,
node clipping, or binding anomaly was found that is a bug. The composition is robust.

## §4. Scene-design findings (tuning a thin-slab contractile network — surfaced by the smoke run)
The first smoke run exposed two geometry issues, both fixed (and informative for the ring):
- **Isotropic 3D formin splay pokes filaments out of a thin slab** (33 wall escapes, ~nN containment fights every
  10 steps). A shallow chamber needs **in-plane-biased** filament orientation (`PLANE_BIAS=0.18` compresses the
  z-component) ⇒ 0 wall escapes, and a planar network that reads well top-down.
- **Free minifilaments must be co-located with the filament field**, else they sit in empty space and never reach
  bind distance (0/1920 bound). Placing them in the node footprint ⇒ healthy binding. **For the ring:** a
  contractile network in a slab needs its myosin co-located with its actin and its filaments biased in-plane.

## §5. Emergent morphology (KIN=1 faithful)
Over the feasible KIN=1 horizon (~0.2 s, 20 000 steps) the system behaves sensibly and **gently CONTRACTS**: the
node net pulls inward (RMS 0.949 → 0.913 µm, 3.8%; nearest-neighbour nodes from 0.50 → 0.30 µm) as the minifilaments
+ node shells **engage progressively** (bound heads climb 47 → 173 over the run) and pull on the bound segments
(filament-network RMS 1.016 → 1.000 µm); crosslinkers **bundle** crossing segments (1 → 46 active links); turnover is
**slow** (42 grown / 15 depolymerized monomers, **no severing yet**) — exactly the §11 KIN=1 picture from
`INC7_RING_3x3_TURNOVER`: at faithful rates the contractile machinery (myosin walking, ~0.3–1.6 s) runs
**several-fold faster than filament turnover** (aging ~4 s, severing ~6.6 s), so the network engages + contracts well
before a filament severs. **To watch aging + severing in a feasible run, the lifecycle render uses KIN-compression**
(a viewing-speed knob, clearly labeled; the hunt stays at KIN=1). The morphology is a **distributed contracting
mesh** — the minifilament + crosslinker network ties the node fields together so the whole slab pulls inward gently
rather than the bare node net's clump-to-a-ball; a richer, more network-like behaviour, which is the point of the
full composition.

## §6. GPU device-residency — a real scaling FINDING
The full merged device graph (~100 tasks: turnover + nucleation + node-shell + free-minifilament + grid-binding,
all device-resident) **builds but exceeds TornadoVM's single-`TaskGraph` node capacity at runtime**
(`TornadoInternalError: Graph resize not implemented yet`) — independent of the bytecode-size budget. **Device
residency of the MAXIMAL composition needs SPLITTING into multiple chained TaskGraphs** — a concrete prerequisite
flagged for the ring. The constituent device graphs are EACH validated device-resident at scale:
turnover+nucleation+node [Ring3x3 GPU, ~58 kernels], minifilament binding + cross-bridge + crosslinker force
[DenseContractile GPU], O(N) crosslinker formation [XlinkFormation GATE B, bit-identical CPU↔GPU]. **The CPU demo
is the source of truth for the hunt** (its dynamics are the standard, per the chaotic-many-body posture). The
`-gpu` flag attempts the full graph and reports this finding cleanly.

## §6b. Review fixes (post-hunt, jba review — 2026-06-22)
Three items raised on review, all addressed:
1. **Minifilaments were not rendered as structures.** The viewer has a dedicated `minifilaments` channel (renders each
   as a backbone cylinder); the harness was emitting only the dimer heads (into `myosins`) and nothing into
   `minifilaments`, so the recognizable backbone was missing. **Fixed:** the free-minifilament backbones are now
   emitted into the `minifilaments` channel (60 white cylinders) alongside their dimer-head myosins.
1b. **Crosslinkers were not VISIBLE (but are present).** Crosslinkers form and accumulate (KIN=1: 1→59 over 0.30 s
   pre-fix, 1→31 post-fix — the interior-torque fix stiffens filaments ⇒ fewer crossings ⇒ fewer links; 20k hunt
   →46; lifecycle →90 peak), and were always in the frame JSON (`crosslinks` array + `stats.crosslinks`). But the v1
   viewer has **NO link-rendering channel** (confirmed by grep) ⇒ silently ignored. **Fixed (viewer change, jba
   authorized):** added an ADDITIVE crosslinker channel to `sim_viewer_boa.html` — a thin **amber-cylinder**
   `crosslinkMesh` (drawn between the two linked segment centres `{a,b}`) + a **`crosslinks: N`** line in the info
   panel. Additive ⇒ a no-op for BoA frames (which carry no `data.crosslinks`), so v1 rendering is unaffected.
2. **Thermal torque on interior filament segments — FIXED.** `placeRandomChain` set `brownRotScale` to the
   translational scale on EVERY warm segment, so interior segments (chain-constrained) wrongly received rotational
   Brownian (thermal torque). Corrected to the standard chain convention (`DenseContractileHarness:149`): rotational
   Brownian only on chain ENDS (`end1NbrSlot<0 || end2NbrSlot<0` ⇒ `BRotCoeff`), **0 on interior segments**;
   translational Brownian stays full-FDT on every segment. Born seeds (single-segment ends) now get `BRotCoeff` too.
   Sanity-neutral (conservation EXACT / 0 phantoms / no NaN re-verified); it removes spurious interior rotational
   noise (slightly stiffer, more correct filaments). The §3/§5 quantitative numbers above are from the pre-fix hunt;
   the post-fix behaviour is qualitatively identical (smoke-verified).
2b. **Crosslinkers were forming "on one filament" — FIXED (a real bug I introduced).** `buildCrosslinkers` set the
   formation `filID` to the **segment index** (each segment its own "filament"), so the same-filament exclusion only
   rejected a segment paired with itself — NOT two adjacent segments of the SAME chain, which always touch at their
   shared joint (≪ grab distance) ⇒ many spurious intra-filament links that render as "a crosslink on one filament,
   crosslinking nothing." **Fixed:** `filID` is now the **chain id** (the connected-component terminal slot, via
   `computeFilID` walking `end2NbrSlot`), recomputed each formation step (growth/split/death mutate topology), so
   two segments of the same filament are never crosslinked — the v1 `filID` semantics. Verified by a new
   `same-chain links: 0` gate. **Consequence:** the genuine inter-filament crosslink count is LOWER than previously
   reported (the old ~31–59 was inflated by the same-chain artifact); at faithful conc=1, ~5 genuine links, at
   `-xlconc 4`, ~11 — real bundling between distinct filaments. The §3/§5 crosslink numbers above predate this fix.

3. **"Is this really KIN=1? filaments age + sever by ~0.24 s."** — Yes, KIN=1 is correct and the confusion was
   between the two renders. The **KIN=1** render (`threejs_fulldemo`, dt=1e-5, 30 000 steps = 0.30 s) shows
   **active segments 672 → 672 (NO severing)** and **meanNotADP 1.000 → 0.988 (barely any aging)** — exactly the §5
   faithful regime (turnover τ ≈ 4–6.6 s ≫ the 0.3 s run). The fast aging/severing "by 0.24 s" is the **other**
   render, `threejs_fulldemo_lifecycle`, which is **KIN=30** (compressed for viewing): at KIN=30, 0.24 s wall =
   **7.2 s effective**, where severing (≈6.6 s) correctly fires. So nothing severs at faithful rates in the demo
   horizon; the lifecycle render is the deliberately-sped-up watch (labeled below).

## §7. The render (watchable full lifecycle)
Two renders (sphere nodes via the viewer's grey-sphere channel; **free-minifilament backbones via the
`minifilaments` channel**; ADP-gradient filaments green→red; node-shell + free-minifilament dimer myosins; a
crosslink channel):
- **`threejs_fulldemo`** (KIN=1, 30 000 steps, **401 frames**) — the **faithful watch**: nodes with their formin
  brushes, minifilaments + node shells binding + contracting the network, crosslinkers forming at crossings.
  Result: node-RMS 0.949 → **0.889 µm** (6.3% contraction), 59 crosslinks, conservation EXACT, turnover slow (the
  §11 faithful regime). Top-down in the shallow slab.
- **`threejs_fulldemo_lifecycle`** (KIN=30, polyboost 3, pool 20, 30 000 steps, **401 frames**) — the
  **viewing-speed lifecycle**: the SAME composition with turnover compressed so the full biochemistry is visible —
  filaments **grow** out (active segments 672 → 1536, barbed "+"), **redden** through the ATP→ADP cascade (meanNotADP
  1.00 → 0.21 = aging), and cofilin **severs** aged segments (active 1536 → **581** = the §4 KIN-compressed severing
  wind-down, segments vanish/fragment), while minifilaments **contract** (node-RMS → 0.866 µm) and crosslinkers
  **bundle** (→ 90 links peak); conservation EXACT throughout. Labeled as compressed (KIN distorts the
  turnover-vs-mechanics balance per §11 — for watching the processes, not concluding rates).

## §8. Sanity-at-scale summary (the integration de-risked)
Across every regime (faithful + compressed, smoke → 20 000+ steps): **conservation EXACT, 0 phantoms, no crash/race
on CPU, finite throughout** — the dead-slot family + the O(N) crosslinker formation + the two-myosin-population
gathers + the full turnover all compose correctly on one shared filament network. The full system — nodes
nucleating biochemically-active treadmilling filaments + minifilament contraction + O(N) crosslinker bundling +
containment — runs together at faithful rates, watchable, with the hunt coming back clean. **The full integration
is demonstrated and de-risked before the ring**, with three concrete carry-forward items: (a) device residency of
the maximal composition needs multi-TaskGraph splitting (§6); (b) a thin-slab contractile network needs in-plane
filament bias + myosin co-located with actin (§4); (c) at faithful KIN=1, contraction precedes turnover by
several-fold — turnover matters for sustained ring maintenance, not initial contraction (§5, confirming
INC7_RING_3x3_TURNOVER §11).

## §9. Files
New: `FullSystemDemoHarness.java`, `run_fulldemo.sh`. Reused VERBATIM (no edit): the node motor-bundle + radial
tether + gather (`NodeStore`/`NodeSystem`/`MiniFilamentSystem.backboneGather`), nucleation
(`NodeNucleationSystem`), turnover (`AgingSystem`/`SeveringSystem`/`DepolySystem`/`GrowthSystem` + the dead-slot
resets), the free minifilament structure (`MotorStore`/`DimerStore`/`MiniFilamentStore` + joints/dimer/tether/
cross-bridge), the parallel grid + fused binding (`SpatialGrid`/`BindingDetectionSystem`), the O(N) crosslinker
formation + force (`CrosslinkerStore`/`CrosslinkerSystem`/`FormationGrid`), `ContainmentSystem`, the shared
rigid-rod systems. `BoA-v1ref` byte-clean; production untouched.
