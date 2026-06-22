# Increment 7 → Ring — 3×3 net + FULL dynamic turnover (treadmilling · aging · severing) + sphere nodes — FINDINGS

**Status: DONE (2026-06-22).** Two changes, both delivered: (1) **protein nodes now render as SPHERES** (the
viewer's existing grey-sphere channel — no viewer edit, BoA rendering untouched); (2) the 3×3 net (`Ring3x3Harness`)
now runs the **FULL simplified turnover machinery — growth + pointed depoly + AGING (the nucleotide cascade that
drives the ADP-depoly rate) + SEVERING (cofilin en-masse dissolve), formin-PINNED (release OFF)**. The integration
is **ROBUST at scale** (conservation EXACT, 0 phantoms, no crash/race on CPU + the device-resident GPU graph,
CPU≡GPU aggregate-agree) and **watchable** (filaments visibly grow out, age into the ADP gradient, and sever). A
genuine integration finding emerged + was surfaced and fixed (below).

**PURE COMPOSITION** — the turnover systems (`AgingSystem`, `SeveringSystem`, `DepolySystem.depolyProxy`) are reused
byte-unchanged; the harness wires them in the SeveringHarness combined order. The ONE additive shared-system change
is a surfaced-bug fix (`SeveringSystem.nucleateFreshCofilin`, §3). `BoA-v1ref` byte-clean; production untouched;
the turnover default-off discipline preserved elsewhere.

```
./run_ring3x3.sh                          # full turnover (aging+severing on, formin-pinned) — winds down (the finding)
./run_ring3x3.sh -nosever                 # +aging, no severing → the net COALESCES (39%)
./run_ring3x3.sh -noaging -nosever        # growth+depoly only (the prior experiment) → COALESCES (49%)
./run_ring3x3.sh -gpu -steps 30000        # + GPU device-resident scale/no-crash/CPU≡GPU-aggregate check
./run_ring3x3.sh -3js threejs_ring3x3_turnover -steps 15000   # viewer: sphere nodes, ADP gradient, severing
```
Log: `RUN_LOGS/2026-06-22_ring3x3_turnover.txt`.

---

## §1. Change 1 — protein nodes render as SPHERES (SoftBox-scoped; no viewer edit)
The verbatim BoA viewer (`sim_viewer_boa.html`) **already** renders a dedicated `data.nodes` array as grey,
semi-transparent **spheres** (`nodeMesh`, an `InstancedMesh` of `SphereGeometry`; `updateMyosinData(myosins,
minifilaments, nodes)` reads `nd.center` + `nd.r`). The 3×3 harness was previously emitting nodes as degenerate
"myosins" (rod=lever=motor collapsed to a point) — rendering them through the cylinder path. The fix is purely in
SoftBox's `FrameWriter`-style JSON: emit a proper `"nodes":[{"center":[x,y,z],"r":NODE_RADIUS}]` array (radius =
the node geometry, 0.05 µm). **No viewer code changed ⇒ BoA's rendering is untouched** (the constraint is honoured
by using the viewer's existing channel, not forking it). Verified: frames carry `nodes=9 (r=0.05)`, rendered as
spheres.

## §2. Change 2 — full dynamic turnover in the net (formin-pinned, release OFF)
The per-step loop now runs the full **SeveringHarness combined cadence** at every biochem step, generalised across
the 9-node net (the validated order): `age → depolyProxy(nucleotide-dependent) → death → grow → growthAtp →
cofilinAccumulate → cofilinDissolve → severDeath → markSplits → allocate → splitWire → splitInheritNuc →
[cofFrac reset] → recomputeDrag`, then nucleation (`emit → allocate → tagSeeds → initNewborn → nucleateFreshAtp →
nucleateFreshCofilin`). **Formin-PINNED**: the seedNode tether holds each filament's barbed end at its node, the
tether dissolve rate is 0 (no formin–actin release) — the ring-relevant mode (NOT the free-translocating render).

**Timescale compression (unchanged, stated).** All biochem rates ×`KIN=100` UNIFORMLY (growth k_on, pointed depoly
pATP/pADP, the cascade pH/pD, cofilin p_cof) ⇒ **every RATIO is preserved** (C_c, cascade-vs-transit,
cofilin-vs-aging), only the absolute turnover SPEED rises so it is visible against the slow mechanical clock.
Aging drives the pointed end to ADP ⇒ the depoly rate becomes `kADPOff1` ⇒ the critical concentration shifts to
`C_c_eff = (32/30)·kADPOff1/k_on = 0.2483 µM` (the aging build-A result); the pool is initialised there.

## §3. SURFACED + FIXED — the cofilin "poisoned slot" (the cofFrac dead-slot analog)
Composing **severing + nucleation + the slot recycle** (none of the prior harnesses did — SeveringHarness has no
nucleation; DeadSlotReuseHarness has no severing) surfaced a real lifecycle bug: a segment that **dissolves** is
`markFree`'d with its `cofFrac` still **> cofilinRatio** (that is WHY it dissolved). When nucleation or a split
reuses that slot, the stale `cofFrac` is still above threshold ⇒ the newborn **instantly re-dissolves on its first
cadence** (a "poisoned slot"). This is the exact cofilin analog of the nucFrac/monomerCount staleness the dead-slot
fix already closes (`initNewborn` + `nucleateFreshAtp`) — but for `cofFrac`, which neither prior fix touched.
**Fixed additively** (`SeveringSystem.nucleateFreshCofilin`: born slots get `cofFrac=0`, the same rank→slot
iteration as `nucleateFreshAtp`, one writer per slot ⇒ race-free), called after BOTH the split alloc and the
nucleation alloc. A fresh seed has no cofilin (=0); a split child sits at the young barbed tip (cofFrac≈0 ⇒ ≈
inheriting), and it clears any poison from the recycled free slot. Touches no existing kernel ⇒ SeveringHarness /
DeadSlot byte-unaffected. **This was necessary for correctness at scale** — without it 245 dissolves would poison
the free-list and corrupt the population. (Reported, not silently added — the discovery-boundary "surfaced
issue".)

---

## §4. THE FINDING — severing tips the net from COALESCING to WINDING DOWN
A clean three-way comparison at identical KIN=100, spacing 0.25, 6 formins, 30000 steps CPU (the only change is
which turnover layers are on):

| machinery | active segs (end) | RMS shrink | mode | cross-cap (avg) | sever events | conservation | phantoms |
|---|---|---|---|---|---|---|---|
| growth + depoly only (prior experiment) | 206 | **48.7%** | COALESCING | 21.3 | 0 | EXACT | 0 |
| + AGING (no severing) | 211 | **39.0%** | COALESCING | 18.5 | 0 | EXACT | 0 |
| **+ AGING + SEVERING (full)** | **4** | **2.0%** | **STABLE / wind-down** | 3.4 | **~245** | EXACT | 0 |

- **Growth+depoly** coalesces strongly (49%) — the §INC7_RING_3x3 result (here even higher, pool at the larger
  aging C_c ⇒ longer filaments ⇒ more reach/capture).
- **+Aging** still coalesces (39%): aging drives the pointed end to ADP ⇒ faster pointed depoly (kADPOff1) ⇒
  slightly shorter filaments ⇒ a mild reduction. Aging alone does NOT break coalescence.
- **+Severing** is the qualitative change. Cofilin dissolves whole 30-monomer segments en masse (returning **7215
  monomers in ~245 events** vs only **707** from pointed depoly — severing is 10× the dominant remover). The net's
  filament population **runs away to ~0** (active 216 → 4): the warm-started actin (6480 monomers) turns over into
  the pool, the capture network collapses (cross-cap 21 → 3, then 0), and the net does NOT coalesce.

**Why — the root cause (a real ring-relevant result).** **Formin-pinned single-tip growth cannot sustain a filament
against cofilin whole-segment severing.** Growth adds **1 monomer/cadence at the one barbed tip**; severing removes
a whole **30-monomer segment** per dissolve, and (KIN-fast) aging makes most of the filament body ADP ⇒
cofilin-severable. Once the body ages, multiple segments sever faster than the single tip can rebuild ⇒ the
filament collapses; freshly-nucleated seeds age and dissolve before they can grow out ⇒ the **population collapses
to ~0**. The transition is a **runaway with no coexistence window**: across `cofilinRatio` ∈ [0.5, 0.95] and `KIN`
∈ [15, 100], the net is **bistable** — either severing stays below threshold (high ratio / low KIN) ⇒ filaments
persist and the net coalesces but **severing essentially never fires** (0.85 ≡ 0.95 bit-identical), or severing
fires (~245 events) ⇒ **runaway dissolution**. There is no regime with sustained occasional severing AND a
persistent coalescing net at uniform KIN compression.

This is exactly the **severing-build wind-down flag** (`INC7_SEVERING_FINDINGS.md` §Notes: "total removal outpaces
single-tip growth ⇒ the filament fully turns over"), now confirmed **at multi-node net scale with nucleation on**:
nucleation does not rescue it (newborns dissolve before establishing).

---

## §5. Sphere-node render — the WATCH (`-3js`, 406 frames, 15000 steps)
The render shows all three turnover processes in the multi-node context, with **sphere-rendered nodes**:
```
frame 0    : 9 sphere nodes (r=0.05), 216 segments all GREEN (notADPRatio 1.0 = fresh ATP), 54 barbed "+" tips
frame 60   : segments reddening uniformly (notADPRatio 0.67) — the ATP→ADP-Pi→ADP cascade aging in
frame 150  : deep red (notADPRatio 0.26 = mostly ADP) — fully aged
frame 300+ : cofilin severs the aged segments → they VANISH + filaments fragment → the net's actin winds down
             (5 survivors, notADPRatio range 0.39–0.96 = the younger regrown tips vs the old)
```
**Watchable:** GROW (nodes sprout filaments + the barbed "+" tip), AGE (green→red reddening = the cascade gradient),
SEVER (segments dissolve/vanish + the filament fragments). A dissolved segment is `markFree`'d ⇒ it disappears from
the frame, so severing is directly visible as the net thinning out.
- **Aging-gradient caveat (flagged):** the warm filaments are all born ATP at t=0 ⇒ they age in LOCKSTEP, so the
  early gradient is **temporal** (the whole net reddens together) rather than a clean per-filament barbed→pointed
  spatial gradient. The spatial gradient appears on growing/regrown tips (the frame-300 survivors span 0.39–0.96).
  A pre-aged warm IC would show the spatial gradient at t=0 but would accelerate the wind-down (not done — it would
  confound the §4 finding).

## §6. Robustness AT SCALE (the integration success — the primary bar)
Holds in **every** regime tested (coalescing and winding-down), the whole point of the integration step:
- **Conservation EXACT** — the integer pool ledger held every sampled step, through the full grow/split/depoly/
  sever/death/nucleate churn (taken 1575 vs returned 7922 in the wind-down run, ledger exact).
- **Zero phantoms** — 0 ACTIVE slots ever born with a stale zero monomerCount, despite 245 dissolves + heavy slot
  recycling. The dead-slot fix (`initNewborn` + `nucleateFreshAtp`) AND the new `nucleateFreshCofilin` (§3) hold at
  scale: every recycled slot births a proper fresh, un-poisoned seed.
- **No crash / no race** — CPU (624 steps/s) AND the device-resident GPU TaskGraph (~58 kernels now incl. age /
  depolyProxy / cofilinAccumulate / cofilinDissolve / severDeath / splitInherit / the two fresh-resets).
- **CPU≡GPU aggregate-agree** (the §8 chaotic-many-body standard): @ 3000 steps active GPU=216 = CPU=216, bound
  GPU=16 = CPU=16, conc GPU=0.1989 = CPU=0.1989 — the lifecycle decisions (wang-hash + integer) track between
  runners; GPU 125 steps/s.

## §7. Flags (PAUSE + report — not silently changed)
- **Free-fragment barbed-end (end2) depoly is still the Stage-1 deferral** (`INC7_SEVERING_FINDINGS.md` §PAUSE).
  An interior dissolve exposes the outer sub-chain's NEW barbed end (end2); in v1 that end depolymerizes
  (kATPOff2/kADPOff2, faster than pointed). v2's free fragments shrink only from their pointed (end1) end. At net
  scale the fragments still turn over (pointed depoly + death → slot recycle, conservation exact) — they do NOT
  accumulate unboundedly — but they shrink slower than faithful and are untethered (drift, no node pull). **NOT
  added silently** (it would re-baseline the depoly path); reported as the next turnover layer.
- **The wind-down is the honest behaviour of the faithful machinery, not an instability/bug.** Conservation +
  phantoms are clean throughout; the population collapse is the physical consequence of single-tip growth vs
  whole-segment severing (the §4 root cause), exactly as the severing build flagged.
- **No coexistence regime found** for "sustained severing + persistent coalescing net" at uniform KIN — a real
  property, not a tuning failure. Sustained severing in a contractile structure needs a growth source that can
  replenish whole severed segments: multi-site / branched (Arp2/3) nucleation, or faster/processive barbed growth,
  or end2-depoly-aware fragment recycling — none present here.

## §8. What this reveals for the ring
1. **The full turnover machinery composes + is robust at multi-node scale** — conservation, phantoms, no-crash,
   CPU≡GPU all hold with growth+depoly+aging+severing+nucleation+recycle running together across 9 nodes. The
   integration is de-risked.
2. **Aging is benign for coalescence** (39% vs 49%); **severing is the lever that breaks it.** A contractile ring
   built on formin-pinned single-tip filaments will DISSOLVE under appreciable cofilin severing unless its growth
   can replenish whole severed segments. This sharpens the ring design: severing rate and the growth/nucleation
   source must be co-calibrated (a balance the current formin-pinned single-tip mode cannot strike).
3. **The cofFrac poison-clear (§3) is now a permanent precondition** for any scene that runs severing + nucleation
   together (the ring) — the cofilin member of the dead-slot lifecycle-reset family.
4. **Sphere-node rendering** is in place for all SoftBox node scenes (via the viewer's existing channel).

---

## §9. Addendum (2026-06-22) — CRANK the barbed polymerization: rapid extension RESCUES the wind-down
Per jba: re-run with barbed-end polymerization turned way up so filaments rapidly extend from the formins,
starting from a small filament per formin OR letting the formins nucleate their own. New knobs (CPU exploration;
default-inert ⇒ the default run is byte-unchanged): **`-polyboost K`** (add K monomers/cadence at the barbed tip —
the grow kernel called K× per cadence, the validated split machinery handles the overshoot), **`-pool µM`** (high
sustained [actin] so growth isn't C_c-limited), **`-warmseed n`** (one small n-monomer seed per formin),
**`-nowarm`** (no warm filaments — the formins nucleate their own, probabilistically at `kNodeNuc`).

**THE RESULT — cranked growth outpaces severing.** The §4 wind-down was specifically a *single-tip-growth-too-slow*
problem; cranking the barbed rate confirms it. With `-polyboost 5 -pool 30`, the filaments **do NOT collapse to ~0**
— they reach a **sustained dynamic steady state** (rapid growth ⇄ severing), conservation EXACT, phantoms 0:
- **`-warmseed 4 -pool 30 -polyboost 6`** (a small filament/formin): filaments **rapidly shoot out** — contour
  1.6 → 41 µm by step 5000 (54 → 432 segments via barbed growth + splitting) — then age green→red and cofilin
  severs the aged mass *en masse* → a **boom-bust** (the synchronized t=0 warm-start ages in lockstep), settling to
  low-level churn. Dramatic rapid extension; the boom outgrows the 2 µm box (use `-box`).
- **`-nowarm -pool 25 -polyboost 5 -box 3`** (formins nucleate their own): the cleaner watch — empty nodes →
  formins **probabilistically nucleate** seeds → each **rapidly extends out** → ages (a genuine population gradient
  now, ADP range 0.13–0.99, since staggered births desynchronize the ages) → severs → recycles, in a **SUSTAINED**
  dynamic state (active ~30–36, RMS stable, ~93 sever events) — no boom-bust, no collapse. Rapid growth balances
  severing continuously.

**Why it matters for the ring (confirms §8.2).** §8 concluded the ring "needs a growth source that can replenish
whole severed segments." This is the direct demonstration: **rapid barbed polymerization IS that source** — it
rescues the filament population from the severing wind-down and yields a sustained grow/age/sever steady state.
Coalescence is NOT recovered here (the fast churn gives few sustained captures — the warmseed boom mildly
DISPERSES, the nowarm state is RMS-stable); turning the churn into *contraction* needs the captures to outlast the
severing (a co-calibration of growth, severing, and capture lifetime — the next ring lever). Renders:
`threejs_ring3x3_rapid` (the nucleate-own sustained extension). `BoA-v1ref` byte-clean; production untouched.
```
./run_ring3x3.sh -warmseed 4 -pool 30 -polyboost 6 -box 4 -3js threejs_ring3x3_rapid   # small seed → rapid shoot-out (boom-bust)
./run_ring3x3.sh -nowarm -pool 25 -polyboost 5 -box 3 -3js threejs_ring3x3_rapid       # formins nucleate own → sustained rapid extension
```

## §10. Addendum (2026-06-22) — INCREASE the formin nucleation rate + RE-NUCLEATION after a severing loss
Per jba: increase the formin actin-nucleation rate, and ensure a formin that loses its filament (to severing here)
can nucleate a new one.

**Bug found + fixed — nucleation was left UNSCALED.** The formin nucleation rate was `pNuc = kNodeNuc·dt` while
every turnover process is ×KIN ⇒ nucleation ran **100× too slow** relative to the compressed turnover. A freed
formin waited ~10⁴ steps to refire. Fixed: `pNuc = kNodeNuc·dt·KIN·NUCBOOST` (the ×KIN is the consistency fix; the
new **`-nucboost f`** knob cranks it further). pNuc: 1e-4 (old) → **0.01** (×KIN) → **0.08** (×KIN×8).

**Re-nucleation after severing — the mechanism was already correct; the rate was the limiter.** When a formin's
node-held barbed-tip segment is severed/dies, `applyDeath` clears its `seedNode` (and detaches the distal chain as a
free fragment); `countBoundFil` recomputes `nodeBoundFil[k]` from `seedNode` each step ⇒ the freed formin drops
below `forminsPerNode` ⇒ `emit` refires. (An *interior* sever leaves the node-side stub `seedNode`-tagged ⇒ the
formin keeps its shortened filament — correct; it re-nucleates only when the node-held piece is fully gone.) With
the boosted rate this is now PROMPT. Measured (full turnover, `-nowarm -polyboost 5`, 20000 steps):

| nucboost | pNuc/step | filaments born | re-nucleations (born−slots) | formin occupancy | sever events | conservation | phantoms |
|---|---|---|---|---|---|---|---|
| 1 (×KIN) | 0.010 | 166 | 112 | **95%** | 1113 | EXACT | 0 |
| 8 | 0.080 | 216 | 162 | **96%** | 1259 | EXACT | 0 |

Despite 1100+ sever events, formins maintain **95–96% occupancy** — they promptly re-nucleate after each loss.

**REFINEMENT of §4 (important).** The earlier "severing → wind-down to ~0" was **partly a nucleation-too-slow
artifact**: with nucleation 100× too slow, freed formins couldn't refill ⇒ the population drained to ~4. With the
KIN-consistent rate, the default (warm, polyboost 1, full turnover) is now **STABLE, not collapsed** — formins
re-nucleate ~162×, occupancy 96%, **active ~54 sustained** (one short filament per formin slot) instead of ~4.
**Severing still prevents coalescence** (it keeps the filaments SHORT — they never reach neighbours), but the net no
longer dissolves to empty: it's a **sustained short-filament churn**. So the §4 conclusion is refined: *severing
caps filament length (⇒ no coalescence), but with proper nucleation the population is sustained, not extinguished* —
the collapse-to-empty was the slow-nucleation compounding the short length.

**Combined demo (`-nowarm -pool 25 -polyboost 5 -box 3 -nucboost 4`, the render `threejs_ring3x3_nuc`):** empty
sphere-nodes → formins **continuously nucleate** (fast) → filaments **rapidly extend** → age (wide population
gradient, ADP 0.12–0.96, from staggered births) → sever → **formins re-nucleate** → sustained dense churn
(200–324 active segments, no collapse, no boom-bust). The clearest watch of both features together. Conservation
EXACT, phantoms 0, no crash. **Default change (noted):** nucleation now ×KIN by default (the consistency fix); the
prior turnover runs used the unscaled rate (reproducible at `-nucboost 0.01`). The key §4 finding (severing caps
coalescence) stands; its severity (collapse vs sustained) depends on the nucleation rate.
```
./run_ring3x3.sh -nowarm -pool 25 -polyboost 5 -box 3 -nucboost 4 -3js threejs_ring3x3_nuc   # fast nucleate + extend + re-nucleate
./run_ring3x3.sh -cpu -nucboost 8        # crank nucleation further (95–96% formin occupancy under heavy severing)
```

## §11. Addendum (2026-06-22) — KIN=1, ALL REALISTIC RATES: the faithful filament-lifetime ↔ node-motion ratio
Per jba: run with all realistic rates and **KIN=1**, to PRESERVE the true relationship between filament lifetime
and the timescale of node motion via myosin walking. This is the decisive control — and it **corrects the §4
wind-down framing**.

**The key asymmetry KIN exposed.** KIN scaled ONLY the actin turnover (growth/depoly/aging/severing/nucleation).
The **myosin nucleotide cycle that walks the nodes (the power stroke / capture-and-pull) is KIN-INDEPENDENT** — it
always runs at its faithful rate (every dt). So `KIN=100` sped actin turnover 100× **relative to the node motion**,
artificially inverting the true ratio and making severing look like it dominates. **KIN=1 restores the faithful
ratio.**

**Result — at KIN=1 the net COALESCES strongly while turnover is essentially FROZEN** (full turnover machinery ON,
aging+severing, formin-pinned):
| run | steps (sim time) | RMS shrink | severing events | nucleations | depoly mono | active segs | occupancy | conservation |
|---|---|---|---|---|---|---|---|---|
| KIN=1, 30 000 (0.3 s) | — | **59%** (0.289→0.117) | **0** | **0** | 15 | 216 (static) | 100% | EXACT |
| KIN=1, 300 000 (3.0 s) | — | **78.5%** (→0.048) | **0** | **0** | 217 | 216 (static) | 100% | EXACT |
| (KIN=100, 30 000, §4) | — | 2% (wind-down) | ~245 | 216 | — | 4 | — | EXACT |

At realistic rates the filaments are **persistent on the node-motion timescale** — over 0.3 s they barely age
(notADPRatio 1.0→0.988) and **do not sever at all**; the net clumps via myosin walking (59%). Over 3 s they coalesce
fully (78.5%, clumped by ~1.6 s) and **still do not sever** (only the pointed-depoly rate has risen as the bodies
slowly age — 217 vs 15 monomers — confirming aging has engaged but severing has not). **The KIN=100 "severing tips
coalesce→wind-down" was an ARTIFACT of compressing turnover onto the mechanical timescale.**

**The faithful timescales (this scene's rates):**
- **Node coalescence (myosin walking):** the net clumps in ~0.3–1.6 s (30 000–160 000 steps). *KIN-independent.*
- **Filament aging** (ATP→ADP cascade): τ ≈ 1/kHydrolysis + 1/kDissociation = 1/0.3 + 1/1.0 ≈ **4.3 s** (≈430 000 steps).
- **Filament lifetime to sever** (cofilin reaches ratio 0.5 on an ADP segment, p_cof = cofilinConc·cofilinRate·bcΔt
  = 3e-4/cadence): aging + ~2 310 cadences ≈ **6.6 s** (≈660 000 steps).

⇒ **Node motion precedes filament turnover by several-fold** (coalescence ~1.6 s vs aging ~4.3 s vs severing
~6.6 s). The net coalesces and clumps *well before* a single filament severs. To OBSERVE severing at faithful rates
needs ~660 000+ steps (~18 min CPU); aging is visible by ~300 000.

**What this means for the ring (the corrected picture).** At realistic rates the contractile machinery (myosin
walking) operates **several-fold faster than filament turnover**, so a node net **coalesces robustly** — severing
does NOT prevent it (it is far too slow on the contraction timescale). Filament turnover matters for *sustained*
ring maintenance over many seconds, NOT for the initial coalescence/contraction. **The KIN compression is a tool to
*see* turnover in a short run, but it distorts the turnover-vs-mechanics competition** — the faithful (KIN=1)
relationship is the physical one, and it says **coalescence wins.** (Free nodes clump to a tight ball, RMS 0.048 µm
— a ring still needs the geometric/membrane constraint; §8.)
Renders: `threejs_ring3x3_kin1` (faithful-ratio coalescence, persistent green filaments). Run:
```
./run_ring3x3.sh -cpu -kin 1 -steps 30000                       # coalesces 59%, turnover frozen (fast)
./run_ring3x3.sh -cpu -kin 1 -steps 300000                      # coalesces 78.5%, aging engages, no severing yet (~8 min)
./run_ring3x3.sh -cpu -kin 1 -steps 30000 -3js threejs_ring3x3_kin1   # watch the faithful coalescence
```

## TL;DR
The 3×3 net now runs the **FULL turnover machinery (growth + depoly + AGING + SEVERING, formin-pinned)** with
**sphere-rendered nodes**, **robust at scale** (conservation EXACT, 0 phantoms, no crash, CPU≡GPU aggregate-agree)
and **watchable** (filaments grow out, redden through the ADP cascade, and sever/fragment). A clean finding: aging
is benign for coalescence (49%→39%) but **severing tips the net from coalescing to winding down** — cofilin
dissolves whole segments (7215 monomers / ~245 events) faster than formin-pinned single-tip growth + nucleation can
replenish, so the population runs away to ~0 (a bistable transition, no coexistence window). Surfaced + fixed a real
lifecycle bug (`nucleateFreshCofilin` — the cofilin analog of the dead-slot fix). Flagged (not added): free-fragment
end2 depoly. The ring needs a growth source that replenishes whole severed segments before sustained severing and
contraction can coexist. Pure composition; `BoA-v1ref` byte-clean; production untouched.
