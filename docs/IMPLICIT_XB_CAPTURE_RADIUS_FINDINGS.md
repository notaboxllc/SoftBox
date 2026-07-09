# Does the implicit cross-bridge unify the BoA/v2 capture-radius sign split? — PREMISE NOT MET (report, don't run)

**Date:** 2026-07-03. Standalone follow-on to `IMPLICIT_XB_CONVERGENCE_FINDINGS.md`. **Measurement/analysis
only; no code change; `BoA-v1ref` untouched.**

## Gate: the follow-on's premise is NOT met — do not run the sweep.
The follow-on requires a solve that **reproduces the converged binding at dt=1e-5**. It does not: the head-only
`-xbimplicit` is a **PARTIAL** convergence — avgBound 14.8 → **21.5** at 1e-5 vs the dt-refined target **56.7**
(closes ~16 % of the gap; `IMPLICIT_XB_CONVERGENCE_FINDINGS.md`). Per the task's own gate ("If `-implicitxb`
isn't … converged yet, do not proceed — report that the premise isn't met"), the capture-radius sweep under
`-xbimplicit` is **not run**: it would be confounded (a 16 %-converged solve cannot cleanly test a
"convergence-unifies" hypothesis), and — see below — the head-only solve doesn't even touch the mechanism the
hypothesis names.

## The scheme read (fresh vs stale co-bound gather) — one paragraph, as requested.
**v2 is Jacobi / one-step-stale; BoA is Gauss–Seidel / fresh.** In v2's step (`GlidingHarness.stepOrig`),
`CrossBridgeSystem.bondForces` evaluates every bound head's cross-bridge force **once, from the start-of-step
head and filament positions**, and writes both the head-side and seg-side reactions into `bondData`. The head
sub-bodies then integrate, and only afterward does the filament's `segGather` (the `boundSeg`-keyed CSR-inverse)
**sum those same start-of-step seg-side forces** and integrate the segment. So a segment's co-bound heads each
contribute a force computed from stale (start-of-step) positions — no head sees another's within-step update:
classic single-evaluation forward-Euler **Jacobi**. This is **identical on both v2 runners** (the `-cpu`
`stepOrig` and the GPU TaskGraph run the same CSR gather over the same `bondData`) ⇒ v2 CPU≡GPU is bit-identical
and the BoA↔v2 split is **scheme (BoA Gauss–Seidel, fresh within-step neighbor positions — sequential per-object
update — vs v2 Jacobi, stale), NOT a CPU-vs-GPU or runner effect.** Note `-xbimplicit` corrects only the head's
*own* translation *after* it integrates; it does **not** recompute the seg-gather force, so it leaves the Jacobi
seg-gather staleness — the exact mechanism the hypothesis names — fully intact.

## Why convergence is unlikely to unify the codes (the confound the money data already exposes).
The hypothesis assumes convergence reduces the stale-force co-bound resistance ⇒ drift flattens toward BoA. But
"convergence" (smaller effective dt) does **two** opposing things to per-bound drift, and the data show the
**wrong one dominates**. At the shared **6 nm** anchor, refining the integrator takes v2's per-bound drift
**DOWN, not up**:

| 6 nm, d1000, seed 0 | avgBound | per-bound drift |
|--|--:|--:|
| v2 explicit@1e-5 | 14.8 | 0.206 |
| v2 implicit@1e-5 (partial) | 21.5 | 0.130 |
| v2 explicit@5e-6 | 56.7 | 0.039 |
| v2 explicit@2.5e-6 (still climbing) | 99.3 | **0.017** |
| BoA-CPU (Gauss–Seidel) | 16.4 | 0.167 |

v2's drift at 6 nm **collapses monotonically and without plateau** as the integrator converges
(0.206 → 0.130 → 0.039 → 0.017), moving **ever further away** from BoA's ~0.167, not toward it — and convergence
is not even bracketed by dt=2.5e-6 (avgBound still doubling per dt-halving). The reason: convergence's dominant effect here is **more bound heads** (14.8 → 56.7), and more
co-bound heads mean **more real mutual cancellation** (the tug-of-war, `net ≈ avgBound × per-bound-drift`,
established across five levers in `VISCOSITY_DIAGNOSTIC_FINDINGS.md`) ⇒ **lower** drift. Any staleness reduction
is swamped by the engagement increase. So a converged capture-radius sweep would run at hugely higher avgBound at
every radius and, if anything, show a **steeper** drift collapse — the opposite of unification. **Convergence is
confounded with engagement; you cannot converge dt without deepening the tug-of-war.**

## Fork verdict: leaning "scheme difference that survives convergence" — but not cleanly testable this way.
The clean "convergence-unifies" test the follow-on wants is **not available via `-xbimplicit`** (partial + wrong
mechanism) and is **intrinsically confounded via dt** (convergence changes engagement, which drives the drift
via the tug-of-war). The 6 nm cross-dt evidence points to the split being a **genuine scheme difference**
(Jacobi stale-force vs Gauss–Seidel fresh-force co-bound load-sharing) rather than a pure under-convergence
artifact — v2's tug-of-war does not soften with convergence, it hardens. But this is one radius, not the
radius→drift *shape*, so it is **suggestive, not decisive**.

**What would actually test it (recommended, planner decision — not run here):**
1. **Match ENGAGEMENT, not dt.** Compare BoA and v2 at equal avgBound (tune density/radius so both codes sit at
   the same bound-head count), then read per-bound drift vs radius. This isolates the scheme's co-bound
   load-sharing from the engagement confound.
2. **A fresh-force (Gauss–Seidel-like) v2 gather** — recompute the seg-side cross-bridge force from post-head-
   integrate positions (or a red-black / colored update) — and re-run the radius sweep. If v2's drift then
   flattens toward BoA at matched engagement, the split is the stale-force scheme; if not, it's deeper physics.
   This risks the race-free CSR / `-cpu`-parity property (a fresh-force gather is order-dependent) ⇒ a design
   task, not a measurement.

## Escalation — dense-regime co-bound fidelity is a standing concern for the ring work.
Independent of capture radius: v2's **Jacobi stale-force co-bound gather** produces a tug-of-war (per-bound
drift falls with engagement) that BoA's Gauss–Seidel scheme shows more weakly. This is a **dense-regime,
many-heads-per-filament** effect — exactly the regime of the flagship contractile **ring** (many nodes, many
co-bound motors per filament). If v2's stale-force scheme over-produces co-bound resistance, ring contraction
rates / stresses could be systematically biased. **Flag for the planner:** before the ring's quantitative
results are trusted, settle whether v2's co-bound load-sharing is scheme-biased (test 1 above), and scope whether
a fresh-force gather is worth the CSR-parity cost (test 2). **Not fixed here** — flagged, per the task.

## Bottom line
Premise not met ⇒ the `-xbimplicit` radius sweep is **not run**. The scheme is Jacobi (v2, both runners) vs
Gauss–Seidel (BoA). The available cross-dt evidence at 6 nm indicates convergence **deepens** v2's tug-of-war
(drift 0.206→0.039) rather than flattening it toward BoA — so the sign split is **most likely a genuine scheme
difference**, and a clean test needs engagement-matching and/or a fresh-force gather, not dt refinement. The
dense-regime co-bound fidelity risk is escalated for the ring.
