# Increment 6c — AUDIT: the node force-coupling model (every force on the free node, vs v1) — FINDINGS

**Date:** 2026-06-19. **Mode:** READ + MEASURE only. No consolidation/coefficient/force-law edits;
`BoA-v1ref` read-only. The force-balance diagnostic (`-nodediag`) + the uncommitted two-sided `seedTetherNodeReact`
are part of the audited working-tree state. Deliverable: the enumerated node force paths, the v1 comparison, the
inconsistencies, the **load-transmission verdict**, and a proposed consolidated model.

## HEADLINE VERDICT
**v2's node force coefficients and roles are FAITHFUL to v1, coefficient-for-coefficient.** The budget-capped /
soft node contraction the user observed (RAW cross-bridge ~10–40 pN → NET node ~1–3 pN; force not scaling with
myosin count) is **v1's actual model, not a v2 mis-port.** v1 uses the identical surface-tether-as-load-path with
the identical `1/numNodeMyos` singlet coefficient. The only genuine gaps were **structural, not numeric**:
`seedTether` was one-sided (now fixed, faithfully), and the two node reactions use **two different** race-free
reduction implementations (cosmetic inconsistency). The consolidation is therefore **standardize + confirm
two-sided**, NOT re-coefficient.

## 1. Every force path on the free node body (`cpuStepStage1`, the node body `nb`)
`nb.forceSum`/`torqueSum` zeroed once (`:566`), accumulated by all paths, confined (`:599`), integrated once
(`:600`). Paths:

| # | path | two-sided onto node? | accumulation | coeff / role | faithful? |
|---|---|---|---|---|---|
| 1 | **Brownian** (`BrownianForceSystem.brownianForce`, `:569`) | self (thermal) | per-body, standard | `NODE_BROWN` scale | ✓ (FDT thermostat; `-nodebrown` damped, intentional) |
| 2 | **Containment** (`ContainmentSystem.confine`, `:599`) | self (wall) | per-body, standard | box law | ✓ (no-op when inside; faithful chamber) |
| 3 | **Myosin surface tether — singlet** (`NodeSystem.tether`→`backboneGather`, `:574/579`) | **yes** (−F at node **center**, no torque) | store `nodeData` + **CSR gather** | `attnForce/nSing` (= /live count) — **cohesion**, budget-capped | ✓ exact v1 port |
| 4 | **Myosin surface tether — dimer** (same) | **yes** (−F at node **surface point**, **with torque**) | store `nodeData` + **CSR gather** | `attnForce·myoDimerFracMove` = 0.08 (fixed/dimer, **scales** w/ count) — **load** | ✓ exact v1 port |
| 5 | **Cross-bridge** (captured myosin's `MyoFilLink`) | **indirect only** — reaches the node *through* paths 3/4 (head→body→rod→surface tether) | — | — | ✓ v1 also has no direct xbridge→node path |
| 6 | **Formin–node bond `seedTether`** (`:586`) + **`seedTetherNodeReact`** (`:590`, uncommitted) | **yes now** (−F at node **center**, no torque) | filament self-write; node via **parallel-over-nodes** reduction | `fracMove` — formin bond | ✓ port (was one-sided; reaction now faithful — see §3) |
| 7 | *(absent)* v1 `nodeTorqSpring` formin align-torque | n/a | — | optional in v1 | **not ported** (known deferred gap; alignment only, not load) |

**Force-coverage:** every path applies once; reactions land on the node now that it is free/integrated (after
the `seedReact` fix). No double-apply, no silent drop.

## 2. v1 node force model (the faithful reference, `BoA-v1ref/boxOfActin`, file:line)
- **`myoCt = Env.numNodeMyos`** (`ProteinNode.java:55`); **`myoDimerCt = Env.numNodeMyoDimers`** (`:61`). Both
  default **0** (`Env.java:390,391`) — node myosins are config-driven; **v1 nodes are typically dimer-carried**.
- **Singlet tether `keepMyosinsOnSurface`** (`ProteinNode.java:391-420`): `forceMag = attnForce·(1e-6·strain /
  Env.numNodeMyos)/(dt·(1/rod.bTransGam.y + 1/node.bTransGam.y))`; `rod.incForceSum(F)` (center, no torque) +
  `incForceSum(-F)` (node center, no torque). **Two-sided, `/numNodeMyos` = /live count (since myoCt==numNodeMyos)
  ⇒ budget-capped at `attnForce`.**
- **Dimer tether `keepMyosinDimersOnSurface`** (`:484-503`): `forceMag = attnForce·myoDimerFracMove·1e-6·strain/
  (dt·(…))`; `rod.incForceSum(F, end1)` (with torque) + `incForceSum(-F, attPt)` (node **surface point**, with
  torque). **Two-sided, fixed per-dimer coeff ⇒ scales with dimer count — the LOAD path.**
- **Formin–node bond `addNodeForces`** (`FilSegment.java`): `forceMag = fracMove·1e-6·strain/((1/bTransGam.x +
  1/node.bTransGam.x)·dt)`; `incForceSum(F, end2Pt)` (filament, with torque) + `end2Node.incForceSum(-F)` (node
  **center**, no torque); **+ optional** `nodeTorqSpring` align-torque (two-sided). **Two-sided.**
- **Cross-bridge load → node:** v1 has **no direct** path either; the captured myosin's head force propagates
  through the articulated body to the rod, and the **surface tether** (paths 3/4) transmits it to the node — the
  **same** mediation as v2.
- **Node movability:** `ProteinNode extends Thing`, integrates via `incCoord(deltaT, veloc)` (`:252`); `fixedNode`
  (default false) optionally pins it. **v1 contractile nodes are free/integrated by default** — the free-node
  regime is a v1 regime, not a v2 invention.

**⇒ v2 reproduces all four couplings with identical coefficients, force points, torque/no-torque, and
two-sidedness.** (v2's `NodeSystem.tether` singlet = center/no-torque, dimer = end1/surface-with-torque;
`backboneGather` reduces force **and** torque — confirmed.)

## 3. The uncommitted two-sided `seedTether` fix — placed in the model
`seedTetherNodeReact` applies `−F` to the node **center, no torque**, coeff `fracMove` — **exactly v1
`addNodeForces`'s node reaction** (`end2Node.incForceSum(-F)`, center, no torque, `fracMove`). **Directionally and
numerically faithful.** Confirmed: correct coeff/role, two-sided, lands on the node. It should ship **as part of
the consolidated model**, not in isolation. (v1's optional `nodeTorqSpring` align-torque remains unported — a
separate, alignment-only deferral.)

## 4. Load-transmission verdict (the live blocker) — **FAITHFUL-soft, NOT a mis-port**
- v1's singlet surface-tether coefficient **is** `1/numNodeMyos` = /live-count (`myoCt==numNodeMyos`) ⇒ v1 also
  **budget-caps the singlet path** at `attnForce`. v2's `/nSing` is the faithful port.
- v1's surface tether **is** the cross-bridge load path (no other route to the node) — same as v2.
- v1 does **not** structurally separate cohesion from load: it is **one tether per myosin type**, with the
  **coefficient encoding the role** — singlets `1/N` (cohesion-leaning, capped), dimers fixed (load-leaning,
  scaling). v2 matches.
- The in-v2 "inconsistency" with the minifilament is **resolved**: the node **dimer LOAD** coeff (0.08, fixed) is
  the **same kind** as the minifilament's `myoMiniFilFracMove` (0.07, fixed). The `1/N` only governs the
  **singlet COHESION** path, which the minifilament (no singlets) simply doesn't have. **The load paths are
  consistent; there is no mis-served coefficient.**
- **Measured** (`-nodediag`, gap 1.2, growth off): 18 myo → captured 2, NET node 2.5 pN; 120 myo → captured 5,
  NET node 3.0 pN. The cap is **not** the singlet `1/N` alone — it is **(a)** geometry-limited captures (~2–5 reach
  the partner filament regardless of total myosin count — the v1 binding gate/reach, faithful) and **(b)** the
  soft fracMove tether's relaxation (~100 steps at coeff 0.01) being comparable to the ~124-step catch-slip bound
  lifetime, so a myosin detaches before fully transmitting its load — **both v1-faithful mechanisms.** Adding
  *singlets* dilutes (capped); adding *dimers* helps only as far as captures + transmission allow.

**Conclusion:** v2's budget-capped, dimer-carried, geometry-+-soft-tether-limited node contraction is **v1's
behavior**. "Large stretch forces, small node motion" is faithful, not a bug. Faster contraction would require a
**deliberate deviation** from v1 (a stiffer/dedicated cross-bridge→node load path), decided explicitly — not a
silent fix.

## 5. Identified inconsistencies (structural, not numeric)
1. **`seedTether` was one-sided** (fixed-anchor leftover). Resolved by `seedTetherNodeReact` (faithful). — commit within the model.
2. **Two reduction implementations for the same "reduce onto the node" job:** surface tether uses store-`nodeData`
   + **CSR `backboneGather`** (O(nFil), keyed); `seedReact` uses **parallel-over-nodes** brute (O(nNode·nFil)).
   Both race-free/no-atomics/standard, but **not the same pattern** ⇒ cosmetic incoherence + the brute is
   O(nNode·nFil) (fine now, worse at ring scale).
3. **Role-conflation is faithful, but undocumented:** the singlet tether is simultaneously cohesion and (capped)
   load — by v1 design. Worth stating so the ring doesn't expect singlet count to scale contractile force.
4. **`nodeTorqSpring` unported** (v1 optional formin align-torque) — flag for the ring if node–filament alignment
   matters.

## 6. Proposed CONSOLIDATED node force model (the structure the ring builds on)
No coefficient changes (faithful). Consolidation = **two-sided everywhere + one accumulation pattern + documented
roles**:
1. **Every coupling two-sided** onto the free node: surface tether (3,4) ✓, formin bond (6) ✓ via `seedReact`,
   Brownian/containment self ✓. Ship `seedTetherNodeReact` as part of this.
2. **One race-free reduction pattern:** route the formin-bond node reaction through the **same `nodeData` + CSR
   `backboneGather`** machinery the surface tether uses (a gather keyed by `seedNode`), retiring the bespoke
   parallel-over-nodes brute. Result: all node reactions accumulate identically, O(nFil), ring-scalable.
3. **Coefficients/roles frozen to v1, documented:** singlet `attnForce/numNodeMyos` (cohesion, capped, no torque);
   dimer `attnForce·myoDimerFracMove` (load, scaling, with torque); formin `fracMove` (center, no torque). Node
   contractile load is **dimer-carried**; singlet count does **not** scale force.
4. **Do NOT separate cohesion from load** — v1 doesn't; the coefficient encodes the role. Match v1.
5. **Flag, don't add:** `nodeTorqSpring` (optional alignment) — only if the ring needs it.
6. **Explicit-deviation note (not in scope here):** if the ring needs stronger/faster contraction than v1's soft
   nodes give, that is a *deviation* (a dedicated stiffer cross-bridge→node load path) — a separate, opt-in
   decision, since v1's load path is faithfully soft.

## 7. Blast radius (for the scoped consolidation follow-on)
- **No coefficient changes ⇒ no validated assay changes.** Surface-tether (Stage A, node-contractile) and
  minifilament/contractile **load paths are untouched**.
- The only behavioral change is the formin-bond two-sidedness (`seedReact`), which is **faithful** and affects
  **only free-node nucleating scenes** (Test B, the future ring). Minifilament / contractile / gliding don't call
  `seedTether` ⇒ unaffected.
- Re-routing `seedReact` onto the CSR gather (step 2) is an implementation swap with **identical physics**
  (bit-identical to the parallel-over-nodes sum, deterministic) ⇒ Test B re-validates equal.
- **Caveat (per the boundary):** v1's node load path is itself research-churning/unsettled; this audit freezes to
  the *current* `BoA-v1ref`. If v1's node mechanics change, re-reconcile.

## Bottom line
The node force model maps cleanly and is **faithful to v1 in every coefficient, force point, and two-sidedness**.
The load-transmission "blocker" is **v1-faithful soft contraction**, not a mis-port — the singlet `1/N` is v1's
cohesion term (capped in v1 too), the dimer is the scaling load path (consistent with the minifilament), and the
cross-bridge reaches the node only through the surface tether (as in v1). The real defects were **structural**:
`seedTether` one-sidedness (fixed, faithfully) and two divergent reduction implementations. The consolidated
model = **two-sided everywhere + the single CSR-gather accumulation + v1-frozen coefficients with documented
cohesion-vs-load roles**, with `seedTetherNodeReact` landing inside it. Stronger-than-v1 contraction is an
explicit future deviation, not a bug fix.
</content>
