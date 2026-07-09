# Increment 7 → Ring — EXPERIMENT: a 3×3 net of nucleating, treadmilling nodes — FINDINGS

**Status: DONE (2026-06-22). The net COALESCES.** A 3×3 grid of free, box-confined protein nodes — each sprouting
4–6 randomly-oriented **treadmilling** formin filaments + carrying the validated myosin shell — finds neighbours,
captures one another's filaments, and **clumps into a single connected cluster** (scheme 0, the soft tether). The
first multi-node SCPR coalescence test. **The collective-load question is answered: scheme 0 is sufficient** (no
scheme-1 signal). The integration is de-risked at scale: **conservation EXACT, zero phantoms, no crash/race** on
both the CPU and the device-resident GPU path. Adjudicated against the SCPR behaviour itself + physics (CLAUDE.md
§8) — no v1 oracle (no v1 measurement of a node net exists).

**PURE COMPOSITION** — NO new force law, NO new gather, NO shared-kernel edit. New files only: `Ring3x3Harness`,
`run_ring3x3.sh`. Every system reused byte-unchanged (capture-and-pull, formin nucleation, growth+split,
fixed-rate depoly+death, the dead-slot recycle, containment, the scheme-0 node tether). `BoA-v1ref` byte-clean;
production untouched; no default change.

```
./run_ring3x3.sh                          # CPU experiment (default 3×3, spacing 0.25, 6 formins, 30000 steps) — COALESCES
./run_ring3x3.sh -gpu -steps 30000        # + GPU device-resident scale/no-crash/throughput check
./run_ring3x3.sh -spacing 0.40 -formins 6 # the SPARSE regime (partial/no coalescence — the reach-vs-spacing edge)
./run_ring3x3.sh -3js threejs_ring3x3     # viewer frames (the net sprouting, reaching, capturing, clumping)
```
Log: `RUN_LOGS/2026-06-22_ring3x3_default.txt`.

---

## The scene (jba's spec, realised)
- **9 FREE nodes** in a 3×3 planar grid (Z=0), box-confined (a 2 µm cube; the in-vitro chamber), node-Brownian
  damped (`-nodebrown 0.03` — a node is a large/slow complex in vivo).
- **6 randomly-oriented formins per node** (the seam-#3 RANDOM-radial placement; `-formins`, spec range 4–6); each
  formin holds ONE treadmilling chain (seedNode-tethered at the node-side barbed end2).
- **Treadmilling**: formin barbed (node-side) growth + **FIXED-rate pointed (outer) depoly** + death ⇒ a
  **pool-bounded reach** (the Test B′ monotonic-overrun fix). **Aging + severing OFF** for this first cut (fixed
  rate, per the spec).
- **Myosins per node**: the validated Test B shell — 6 radial singlets + 6 radial dimers (18 motor sub-bodies/node,
  162 across the net) — for capture-and-pull.
- **Coalescence = scheme 0** (the validated soft node tether). Scheme 1 was the fallback only; it was **NOT needed**
  (see §5).
- **One SHARED finite actin pool**; the **dead-slot fix** (`NodeNucleationSystem.initNewborn`) in place ⇒ turnover
  + nucleation coexist at scale.

### Timescale compression (reported, not hidden)
The mechanical clock (node motion, captures) and the biochemical clock (treadmilling turnover) are separated by
~1000× in the real system — far too wide to see both in one feasible run (the faithful treadmill equilibration is
τ ≈ 5×10⁴ cadences, INC7_TREADMILL_FINDINGS). So the biochem kinetics are accelerated by **`KIN=100`** which scales
**BOTH `k_on` and `k_off1` equally** ⇒ **`C_c = k_off1/k_on` is PRESERVED** (the steady reach is unchanged) — only
the turnover SPEED rises. Filaments are **warm-started near their pool-consistent steady reach** so the scene begins
in the "neighbours just within reach" regime; the pool is initialised at `[actin] = C_c_eff` so growth ≈ depoly
(steady treadmilling, no growth race). This is a legitimate clock-compression, stated plainly; the reach is
**pool-bounded by conservation** (the INC7 treadmilling result), not by unbounded growth.

---

## §1. The key setup — calibrating the reach to the spacing
**Reach is set by the warm-start length (= the pool-consistent steady reach) and the shared pool by conservation:**
with `N_fil ≈ 9·formins = 54` filaments drawing on one pool at `[actin]=C_c`, the per-filament reach is bounded
(more filaments ⇒ shorter each — the shared-pool constraint). The **controlling parameter for coalescence is
reach/spacing**, with an important refinement from Test B′:

> **Capture needs OVERSHOOT, not just touch.** The `rodDotFil ≥ 0` polarity gate (the validated bind predicate)
> requires the foreign filament to reach the captor's **far hemisphere** — i.e. to extend *past* the partner node,
> not merely touch its near side. So the calibrated reach target is `OVERSHOOT·spacing` with **OVERSHOOT ≈ 1.35×**
> (the Test B′ value). With reach ≈ spacing exactly (no overshoot), captures are sparse even between aligned nodes.

**Chosen default:** spacing **0.25 µm**, warm chain **4 segments × 30 monomers** ⇒ warm reach **0.335 µm**
(reach/spacing = **1.34×**) — "reachable (just within reach)", non-overlapping nodes (centres 5× the node radius
apart). Per-node total contour ≈ 2.0 µm (6 formins), [actin] ≈ C_c_eff = 0.0736 µM, total actin 1.42 µM.

### The reach-vs-spacing sweep (the calibration — formins=6, 20000 steps)
| spacing (µm) | warm reach (µm) | reach/spacing | outcome | RMS shrink | cross-cap (avg) | linked pairs | largest cluster | conservation | phantoms |
|---|---|---|---|---|---|---|---|---|---|
| 0.20 | 0.251 | 1.25 | **COALESCING** | 27.4% | 18.9 | 21 | **9/9** | EXACT | 0 |
| 0.25 | 0.335 | 1.34 | **COALESCING** | 26.3% | 19.0 | 21 | **9/9** | EXACT | 0 |
| 0.30 | 0.419 | 1.40 | **COALESCING** | 23.4% | 19.2 | 18 | **9/9** | EXACT | 0 |
| 0.35 | 0.502 | 1.43 | PARTIAL | 7.8% | 11.4 | 11 | 9/9 | EXACT | 0 |
| 0.40 | 0.502 | 1.25 | PARTIAL | 4.8% | 6.3 | 8 | 8/9 | EXACT | 0 |

**Reading:** a clean monotonic transition. **Dense net (spacing ≤ 0.30 µm) ⇒ full coalescence** (all 9 nodes in one
cluster, ~19 cross-captures, ~25% RMS shrink). **Sparse net (0.35–0.40 µm) ⇒ partial** (still connected but few
captures, little net motion). The transition is governed by spacing (node density / the angular size a neighbour
subtends) MORE than by the bare overshoot ratio — note 0.35 has a *larger* overshoot (1.43) than 0.40 (1.25) yet
both are partial, while 0.20–0.30 (overshoot 1.25–1.40) all coalesce. **Conservation EXACT and phantoms 0 in
EVERY regime.** Too far (≥0.40) is the spacing-null the task warned of; the chosen 0.25 is in the productive band.

---

## §2. Does the net coalesce? — YES (the headline, default config, 30000 steps CPU)
```
net RMS extent: start=0.2887 → min/end=0.1703 µm   (shrink 41.0%, MONOTONIC to the end)
net bbox diagonal: 0.7071 → 0.5358 µm
COALESCENCE MODE: COALESCING (net clumps)
```
The 9-node net contracts its RMS radius by **41%** (and its bounding-box diagonal by 24%), monotonically, over the
run. The render (§6) shows the classic progression: the net sprouts a dense apron of treadmilling filaments,
neighbours' filaments interpenetrate, myosins capture them, and the captured-and-pulled nodes draw inward into a
tight clump.

**Outcome mode: FULL coalescence** (in the calibrated regime). PARTIAL at the sparse edge; no DISPERSAL, no
pathology (no buckling, no single node dominating, no fly-apart) in any regime tested.

---

## §3. The capture network — a fully-connected cluster
```
capture network: 23 distinct node-pairs linked, 9 nodes participate; largest connected cluster = 9 of 9
linked pairs (node↔node : captures): 0↔1:135 0↔3:88 0↔6:87 1↔4:93 1↔5:132 2↔5:179 2↔8:117 3↔7:194
                                      5↔7:123 5↔8:120 6↔7:84 6↔8:100 7↔8:141  … (23 pairs total)
```
A **single connected capture network spans all 9 nodes** (the largest connected component = 9/9). 23 distinct
node-pairs are linked — MORE than the 12 nearest-neighbour grid edges, because as the net contracts, second-nearest
(diagonal) neighbours also come within reach and capture. **Self-capture = 0** throughout (the barbed=end2
convention swap + the v1 node-held-tip exclusion correctly reject a node grabbing its own filament — INC6C). The
network is the mechanism: every node is pulled toward several neighbours simultaneously ⇒ the net contracts as a
whole rather than fragmenting into isolated pairs (the n=2 Test B artifact is GONE at n=9).

---

## §4. Reach vs spacing (measured)
```
mean filament contour = 2.00 µm/node (summed over its 6 formins); per-formin-chain reach ≈ 0.334 µm vs spacing 0.250 µm
```
Measured per-formin reach (**0.334 µm**) matches the warm-start target and the conservation prediction; it
comfortably exceeds the 0.250 µm spacing (the overshoot the capture cone needs). Total contour stays ~stable
(treadmilling steady state — the pool is bounded, filaments don't run away), confirming the reach is **pool-bounded
by conservation**, not growing without limit (the Test B′ overrun is fixed by turnover + the shared pool).

---

## §5. Force-transmission read — SCHEME 0 IS SUFFICIENT (no scheme-1 signal)
```
max inter-node cross-bridge bond stretch with little node motion = 0.023 µm
=> stretch modest / nodes move ⇒ scheme-0 soft tether transmits the collective load (no scheme-1 signal)
```
The diagnostic the task flagged — **large inter-node filament stretch persisting WITHOUT node motion** (the signal
that the soft tether fails to transmit collective load) — **did NOT appear**. The cross-bridge bond stretch stays
small (~23 nm) and the nodes visibly move (the 41% contraction). The collective load of ~19 simultaneous
cross-captures is transmitted through the scheme-0 soft node tether well enough to pull the net together. **Scheme 0
is sufficient at this scale; scheme 1 is NOT needed** (it was not switched — reported as instructed). This is the
key de-risking result for the ring: the validated soft-tether coupling scales from n=2 (Test B) to a 9-node
collective.

---

## §6. The render (`-3js threejs_ring3x3`, 401 frames)
Progression of the net (RMS extent : cross-captures): the net begins as a 3×3 lattice of nodes each haloed by a
dense, radially-splayed apron of treadmilling filaments; neighbours' aprons overlap, myosins capture the foreign
filaments, and the captured nodes are reeled inward.
```
step 0     : extent 0.289 µm, 4 captures   (lattice + sprouting aprons)
step 7500  : extent 0.254 µm, 16 captures  (aprons interpenetrate, capture network forming)
step 15000 : extent 0.235 µm, 18 captures  (net visibly contracting)
step 22500 : extent 0.203 µm, 19 captures  (tight clump forming)
step 30000 : extent 0.170 µm, 15 captures  (coalesced; geometry crowds, some captures release/re-form)
```
The viewer shows 9 node spheres + the filament forest + the cycling myosins, in the v1 contractility-panel schema
(`sim_viewer_boa.html`).

---

## §7. Sanity / robustness AT SCALE
- **Conservation EXACT** — the integer pool ledger `Σ monomerCount(active) = monInit + taken − returned` held at
  every sampled step, in EVERY run and EVERY spacing. The recycle at 9 nodes × 6 formins (~54 filaments, ~216
  active segments) conserves actin exactly through the full grow/split/depoly/death/nucleate churn.
- **Turnover genuinely exercised at scale:** pool ledger over the default run = **1364 monomers taken**
  (growth+nucleation) vs **1377 returned** (depoly+death) — brisk treadmilling churn, taken ≈ returned (steady state
  near C_c, as designed), with the dead-slot recycle reclaiming dead pointed-segment slots for new nucleations.
- **Zero phantoms** — 0 ACTIVE slots ever born with a stale zero monomerCount. The dead-slot fix
  (`initNewborn` ⇒ monomerCount=actinSeed, segLength=seedLen) holds at scale: every recycled slot births a proper
  fresh seed.
- **No crash / no race** — ran to completion on the CPU (616 steps/s) AND on the device-resident GPU TaskGraph.

### GPU device-resident scale / no-crash check (`-gpu`, 3000 steps)
```
GPU ran 3000 steps at scale, NO crash/race on the parallel path; 135 steps/s
GPU aggregate: bound heads=20, active segments=216, conc=0.0694 µM, conservation=EXACT, phantoms=0
```
The full ~50-kernel device-resident pipeline (turnover + nucleation + binding + gather + the scan-rank allocator,
all on the GPU) ran at scale with **no race, no crash, conservation EXACT, phantoms 0**, and an aggregate bound-head
count (20) matching the CPU (~19) — the chaotic-many-body aggregate-agreement standard (CPU≡GPU bit-identity is not
the bar, CLAUDE.md §8). The allocator + turnover/nucleation coexistence are validated on the parallel path at
9-node scale. *(GPU is slower than CPU HERE only because the net is small — 162 motors — so the 50-launch overhead
dominates; GPU wins at large N, cf. the gliding 13.4k-motor result.)*

---

## §8. What this reveals for the ring
1. **The mechanisms COMPOSE.** Capture-and-pull, formin nucleation, polymerization growth+split, fixed-rate
   treadmilling depoly+death, and the dead-slot recycle run together, at 9-node scale, on both runners, and produce
   the intended emergent behaviour — a coalescing net. The ring integration is de-risked.
2. **Scheme 0 is enough.** The validated soft node tether transmits the collective load of a 9-node net (no
   scheme-1 signal). The ring does not need a stiffer coupling for force transmission.
3. **Reach/spacing is THE calibration knob**, with the **capture cone requiring ~1.35× overshoot** (the foreign
   filament must reach the captor's far hemisphere). The productive band is reach ≳ 1.3× spacing AND a dense net
   (spacing ≲ 0.30 µm here); beyond ~0.35 µm the random-orientation capture rate falls and coalescence weakens.
4. **Random 3D orientation is inefficient in a planar net** (the main loss): ~half of each node's formins point
   out-of-plane into empty space, and of the in-plane ones only those aimed roughly at a neighbour (and overshooting)
   capture. The net still coalesces because density compensates — but a real **planar/ring geometry would benefit
   from an in-plane (or toward-neighbour) nucleation bias** (the seam-#3 SPECIFIED placement, already built for Test
   B′). This is the first concrete lever the ring step can pull to raise capture efficiency.
5. **Turnover bounds the reach** (no Test B′ overrun) AND **exercises the dead-slot recycle at scale** with exact
   conservation — the precondition for a *sustained* contractile ring (filaments must turn over for the ring to keep
   contracting rather than jam, INC6C Test B′ overrun note).

### What's missing / next (for the ring)
- **No geometric/membrane constraint yet** — these are FREE nodes, so coalescence = clumping into a ball, not a
  *ring*. The ring geometry needs the membrane/cortex constraint (a later increment) to hold the nodes on a surface
  so contraction yields a ring, not a clump. (The net did NOT need anchoring to stay coherent — it clumps, it does
  not fly apart — so the constraint is about GEOMETRY, not stability.)
- **In-plane / toward-neighbour nucleation bias** (seam-#3 SPECIFIED) would sharply raise capture efficiency in a
  planar/ring layout — the cheapest next lever.
- **Faithful (un-compressed) kinetics** at production scale would need either very long runs or a multi-rate clock;
  the `KIN` compression (C_c-preserving) is the stand-in used here.
- Many-node **fixed-anchor minimal contractile ring** (the originally-foreshadowed step): a ring of these nodes on a
  fixed-radius constraint + the contractile-assay tension read.

---

## TL;DR
A 3×3 net of free, box-confined protein nodes — each sprouting 4–6 randomly-oriented **treadmilling** formin
filaments + the validated myosin shell — **finds neighbours and COALESCES** into a single connected 9-node cluster
(RMS extent −41%), pulled together by cross-node capture-and-pull through the **scheme-0 soft tether (sufficient —
no scheme-1 signal needed)**. The reach-vs-spacing calibration is clean and monotonic (coalesce for spacing ≤0.30
µm at ~1.35× reach overshoot; partial beyond 0.35 µm); the dominant inefficiency is 3D-random orientation in a
planar net (an in-plane bias is the next lever). The integration is de-risked at scale — **conservation EXACT, zero
phantoms, no crash/race** on CPU and the device-resident GPU path; treadmilling + nucleation + dead-slot recycle
coexist with exact actin conservation. Pure composition (no new physics); `BoA-v1ref` byte-clean, production
untouched. **The mechanisms compose; the ring's next needs are a geometric/membrane constraint (clump → ring) and
an in-plane nucleation bias.**
