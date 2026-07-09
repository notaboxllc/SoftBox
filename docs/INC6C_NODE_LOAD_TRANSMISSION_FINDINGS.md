# Increment 6c — EXPERIMENT: conserve the myosin→node load without numerical stiffness — FINDINGS

**Date:** 2026-06-19. **Mode:** BUILD + MEASURE (authorized physics **deviation** from v1 — v1's node coalescence
was never experiment-validated; the goal is defensible physics: the captured cross-bridge force must reach the
node). All schemes **flagged, default OFF (`SCHEME=0`)** ⇒ prior assays + the current Test B byte-unchanged;
confirmed: scheme-0 Test B CPU≡GPU agree (MIN 0.9054, identical to pre-experiment), contractile PASS. `BoA-v1ref`
untouched. **No default flip / commit** — the choice is jba's + the planner's. Matched assay throughout: 120
myosins/node (40 singlet + 40 dimer), gap 1.2 µm, growth OFF, 40 000 steps, `-cpu -nodediag`.

## The problem (from the audit)
The captured cross-bridge force reaches the node ONLY through the soft fracMove surface tether; a bound myosin
**creeps** under load (tether relaxation ~100 steps ≈ the ~124-step bound lifetime) instead of dragging the node,
so the force dissipates into myosin slide + the visible inter-node filament stretch. **Measured loss: RAW captured
cross-bridge ~10–40 pN → NET node force ~1–3 pN (~10×).** Stiffening the *whole* 120-myosin shell to fix it risks
a ~120× aggregate-stiffness blow-up at the production dt — so the load from the ~2–5 captured myosins must reach
the node *without* global stiffening.

## Schemes implemented (each behind `-scheme N`, default 0)
1. **Direct load injection** (`NodeSystem.xbridgeInject`): a cross-captured myosin's cross-bridge head force is
   routed onto the NODE body at the surface attach point (with torque), as a **force, not a spring** ⇒ adds no
   stiffness; only the ~few captured contribute. Conserved once: head force → node + segment (segGather), and
   `applyHeadForce` is **skipped** for the node path (not added to the motor). Soft tether kept for retention.
2. **State-dependent stiff tether** (`NodeSystem.tetherBoundStiffen`): the surface-tether coeff is raised to
   `BOUND_COEFF` **only while a myosin is cross-captured**; the ~115 unbound keep the soft `1/N` retention coeff.
3. **Global stiffen (instructive baseline)**: raise ALL singlet tether coeffs to `BOUND_COEFF` (count-independent).
4. *(stretch, not built — 1 was decisive)* rigid position-slaving.

All are race-free (parallel-over-attachments / parallel-over-motors, no atomics/KernelContext); the new kernels
are device-agnostic plain methods (same shape as `tether`) ⇒ bit-identical CPU↔GPU by construction.

## COMPARISON TABLE (matched assay; numbers measured)
| scheme | conservation NET/RAW (node force ÷ captured x-bridge) | coalescence: MIN dist (Δ from 1.200) | shape | dt-stable? | retention (max tether strain) |
|---|---|---|---|---|---|
| **0 current** (soft `1/N` tether) | **~0.1** (15.7→0.0002 pN @t0; ~1–4 vs ~15–24 pN) | 0.9054 (Δ 0.295) | plateau | yes | 0.040 µm ✓ |
| **1 direct inject** | **~1.0** (15.7→15.7 pN @t0; NET ≈ RAW throughout) | **0.8353 (Δ 0.365)** ← best | stronger pull, still range-limited | **yes** | 0.040 µm ✓ |
| **2 bound-stiff** (0.07) | ~0.1–0.3 (≈ scheme 0; NET ~3–4 vs ~6–41 pN) | 0.9078 (Δ 0.292) ← ≈ no help | plateau | yes | 0.040 µm ✓ |
| 2 bound-stiff (0.3) | ~0.0–0.25 (NET ~2.7–6.2 vs ~18–20 pN) | (≈ scheme 0) | plateau | yes | ✓ |
| **3 global stiffen** (0.07) | ~0.5–0.6 (NET ~7–9 vs ~14–16 pN) | 0.8377 (Δ 0.362) | stronger pull | **yes @0.07 ONLY** | 0.019 µm ✓ (tighter) |
| 3 global stiffen (0.3 / 1.0 / 3.0 / 8.0) | — | **NaN — BLOW-UP** | — | **NO** | — |

**Stiffness wall located:** global stiffen is stable at 0.07 but **blows up (NaN) at 0.3** — and the safe coeff
shrinks as the myosin count grows, so scheme 3 sits right at the edge for 120 myosins and is fragile for a
variable-count ring. (The dimer coeff is already 0.08, near this edge.)

## Reading the numbers
- **Scheme 1 conserves the load** (NET ≈ RAW; at t=0 a lone captured myosin delivers its full 15.7 pN to the node
  vs 0.0002 pN under scheme 0) and gives the **best coalescence** (closes 0.365 µm vs 0.295), **stays dt-stable**,
  and **preserves retention** (the soft tether still holds the shell; injection is a separate force) — and because
  it adds **no spring stiffness**, it is **immune to the count-dependent stiffness wall** that makes scheme 3
  fragile. This is the scheme that satisfies "conserve the load WITHOUT global numerical stiffness."
- **Scheme 3 ties scheme 1 on coalescence** but only inside a narrow stable coeff window (blows up at 0.3; the
  window narrows with count) — not robust for the ring.
- **Scheme 2 did not help** (≈ scheme 0): stiffening only the *surface tether* of bound myosins still loses the
  load to creep within the bound lifetime, and at `BOUND_COEFF=0.07` it actually *softens* the load-bearing dimers
  (0.08→0.07). Raising it to 0.3 still didn't conserve (~0.25) — the surface-tether path is the wrong lever.
- **Honest limit (all schemes):** none "accelerates to contact." With growth OFF the fixed-length filaments give a
  **finite pull range** — conservation makes the pull *stronger* (closes more) but it still **plateaus** once the
  captured overlap is walked through. Accelerate-to-contact needs continuous filament feed (growth + turnover),
  orthogonal to load transmission.

## Force-balance detail (representative, `-nodediag`)
- Scheme 0 @t=0: node captured=1, RAW |Σ|=15.73 pN, **NET |F|=0.0002 pN** (≈0 reaches the node).
- Scheme 1 @t=0: node captured=1, RAW |Σ|=15.73 pN, **NET |F|=15.73 pN** (full load conserved).
- Scheme 1 @t≈10 000: NET ≈ RAW within the multi-capture/torque bookkeeping (NET 7–15 pN vs RAW 9–16 pN).
- Scheme 3 @t≈10 000: NET ≈ 0.5–1.0 × RAW (partial); coalesces but near the stability edge.

## Recommendation (grounded in the numbers)
**Scheme 1 (direct injection).** It is the only scheme that (a) **conserves** the captured load (NET≈RAW vs ~10×
loss), (b) gives the **best coalescence** (Δ 0.365), (c) is **dt-stable**, and (d) adds **no global stiffness** so
it is **robust to myosin count** (no stiffness wall) — exactly the stated goal. Scheme 3 matches its coalescence
but is fragile (wall at 0.3, count-dependent); scheme 2 doesn't conserve. **Recommended next step:** adopt scheme
1 in the consolidated node-force model, wiring it into the GPU TaskGraph (the fluent graph needs `bond` before the
node gather + an `xbInject` task + dropping `applyHead` for the node path) and validating CPU≡GPU there.

**Caveats to weigh before adopting (physics deviation, flag for jba/planner):**
- Scheme 1 idealizes the bound myosin as a **rigid lever**: the head force is removed from the motor body (it
  strokes "unloaded" via the joints) and delivered to the node. Bookkeeping is conserved once (node + segment, not
  motor), but the motor's own loaded-stroke dynamics are simplified. The cross-bridge spring strain (head-tip ↔
  bound-site) now feeds the node directly — measured stable here, but should be watched under growth-on / dense
  capture.
- It is a **deviation from v1** (authorized): v1 transmits everything through the soft surface tether. Adopt on
  the physics argument (load conservation), not faithfulness.
- Coalescence is still range-limited without turnover (see above).

## Deliverable status
Schemes 1/2/3 implemented + flagged (default OFF, no-op confirmed on CPU+GPU + §A); comparison table + force
balance measured; recommendation = scheme 1, grounded in conservation + stability + robustness. **No default
flip, no commit of a chosen scheme** — awaiting jba/planner. New code: `NodeSystem.xbridgeInject` /
`tetherBoundStiffen`, `TestBScprHarness` `-scheme`/`-boundcoeff` + the branched force section + `-nodediag`
conservation/retention readout. JOURNAL updated.
</content>
