# Increment 6c — Test B′: clean aimed SCPR (sparse, separated, SPECIFIED placement) — FINDINGS

**Date:** 2026-06-18. **Status:** **SUCCESS — the SCPR capture-and-pull primitive demonstrated cleanly over a
gap.** Two well-separated nodes, each growing ONE actin AIMED at the partner, capture one another's filaments
and **measurably approach (0.60 → 0.42 µm, Δ=0.176 µm, EXCEEDS Brownian noise)**. Seam #3's **SPECIFIED**
placement realized (aim-at-partner). **Extends `TestBScprHarness` — no new harness, no shared-kernel edit, no
existing value changed** ⇒ prior assays + production byte-unchanged; `BoA-v1ref` byte-clean.

```
./run_testb.sh -aimed              # GPU+CPU: Gate0 → CPU≡GPU → the clean aimed SCPR run (distance trace)
./run_testb.sh -cpu -aimed         # CPU runner only
./run_testb.sh -cpu -aimed -3js threejs_testb_aimed   # viewer
# -aimed presets SPECIFIED placement, forminsPerNode=1, gap 0.6 µm, SEED_MON 64, nodebrown 0.02, 30k steps;
# all CLI-tunable (put -aimed FIRST; later flags override).
```

## Why a cleaner scene (jba's design)
Test B's first scene was confounded by design: many random-radial filaments per node (self-capture rampant,
hard to read) and a 0.25 µm near-contact start (not a search-capture-pull *over a gap*). The primitive is sound
(Gate 0 + the contractile-assay-validated inward pull) but the scene buried it. Test B′ is the legible test:
**sparse (1 filament/node) + AIMED at the partner + well-SEPARATED**, over a real gap.

## What was built
1. **Seam #3 `SPECIFIED` placement body** (realizing the stubbed hook): `forminSiteDir(node,site)` under
   `Placement.SPECIFIED` returns the unit vector from the node toward its **target** (`NODE_TARGET[k]` = the
   partner). General (a specifiable aim/target per site; multi-site spreads a small cone); this test =
   aim-at-partner. The seam stays real — RANDOM (Test B) and SPECIFIED (Test B′) are both live.
2. **The clean `-aimed` preset:** two FREE box-confined nodes, `forminsPerNode = 1`, SPECIFIED placement (left
   aims right, right aims left), gap **0.6 µm** (well-separated — the myosin shells, radius ~0.16 µm, do NOT
   overlap), the radial shell + 12 pN cap + faithful release + containment unchanged, nucleation + growth ON.
3. **Pre-grown aimed filament:** the aimed filament is laid down at build as a linear multi-segment chain
   (`placeAimedChain`) that **OVERSHOOTS the partner** (reach ≈ 1.3·gap). This is required by the polarity gate
   (below) and makes capture happen EARLY so the *pull* is legible; growth stays ON (the tip keeps extending).
4. **Self-capture readout:** capture-phase self/cross **count AND transmitted force** (jba's thesis test).

## The capture-cone / overshoot finding (why pre-grow + overshoot)
The `rodDotFil ≥ 0` binding gate requires the captor's head-rod to point along the foreign filament's outward
axis. Node A's aimed filament points A→B; the heads that can bind it are on B's **far hemisphere** (rod along
A→B). So **cross-capture requires the foreign filament to reach the captor's far side** (overshoot past the
partner node), not merely touch its near side. The myosin then strokes toward the actin **barbed/plus end** (at
A) ⇒ the captor (B) is pulled toward A. This is the inherent SCPR search cost; pre-growing the aimed filament to
overshoot lets capture + pull be observed in a feasible run.

## Headline result — the nodes measurably approach
| quantity | value |
|---|---|
| start distance | 0.600 µm |
| **min distance (initial approach)** | **0.424 µm @ step 11812** |
| **initial approach Δ** | **0.176 µm** |
| Brownian wander to min (0.118 s) | rms ≈ 0.003 µm |
| verdict | **approach EXCEEDS noise (≈60×)** ⇒ SCPR capture-and-pull demonstrated |
| cross-node captures | first @ step 311, peak 8, capture-phase avg 3.5 |

Each node's aimed filament grows toward the partner (placement), the partner's far-hemisphere myosins capture
it as it overshoots (cross-capture), and the directed stroke draws the nodes together — **a clean,
beyond-noise approach over a real gap.** `./run_testb.sh -aimed` prints `STAGE 1 demonstrates SCPR
capture-and-pull (nodes approach beyond noise)`.

## jba's "self-capture negligible by LAYOUT" thesis — REFUTED in magnitude, but NON-BLOCKING (honest finding)
Per the discovery boundary (report a real finding, don't paper over): **self-capture is NOT negligible.** The
aimed layout *reduces* self-capture vs the random scene (count ~30 → ~5), but does **not** preclude it:
capture-phase self/cross **count ≈ 4.6 / 3.5**, transmitted **force self ≈ 20.0 pN vs cross ≈ 14.5 pN
(self/cross ≈ 1.38)**. The reason is geometric — the aimed filament must **exit through its own node's
partner-facing hemisphere**, where that node's same-direction heads (rodDotFil = +1) capture it. A radial shell
+ an aimed filament cannot avoid this without a shell gap at the formin site (a self-capture-suppression rule —
explicitly NOT built).

**BUT the approach succeeds anyway** because self-capture is **internal to a node** (A's heads on A's own
filament pull A's motors toward A's own center ⇒ ~zero net node displacement), while **cross-capture is the only
mode that produces net inter-node motion** (B's heads on A's filament pull B toward A). So the directed pull is
carried entirely by cross-capture; self-capture is significant in force but non-blocking in *net effect*. ⇒
jba's intuition holds *operationally* (the layout makes the signal legible and the approach clean) even though
self-capture is not literally negligible.

## Post-min OVERRUN (OUT OF SCOPE, expected)
After the min, the nodes drift apart (0.42 → 0.81 µm) and cross-capture decays to 0. This is the
**monotonic-growth / no-depolymerization** artifact the task flagged out of scope: the aimed filament keeps
growing and **OVERRUNS the closing gap** — as the nodes close, the filament (tethered at its node, ever-
lengthening) no longer presents a far-side overshoot to the partner, capture geometry breaks, and the residual
self-capture + growth pushes the nodes back apart. **This test is the INITIAL approach signal only**; the
sustained-contraction regime needs turnover / treadmilling / a release clock (deferred layers). The harness
detects and flags the overrun explicitly.

## CPU≡GPU
The aimed scene runs on the full 45-task device-resident GPU pipeline; **CPU≡GPU aggregate-agree** (windowed
avgBound GPU 2.50 = CPU 2.50, active-fil 18 = 18). Gate 0 (deterministic) is bit-identical CPU≡GPU.

## Constraints honored
One physics impl, both runners; float32; dt held. **No shared-kernel edit; no existing value changed; new code
is confined to `TestBScprHarness` + the `-aimed` preset.** Reused byte-unchanged: growth, nucleation,
binding/gather, the 12 pN cap, containment, the free-node config, `ActinPool`, wang-hash RNG, the two-allocator
integration. Prior assays + production byte-unchanged; `BoA-v1ref` byte-clean.

## OUT of scope (confirmed not built)
Long-run overrun/buckling fix (turnover/depoly — deferred; this is the initial-approach test); random-radial
self-capture dissection (superseded by this clean scene); SPECIFIED placement beyond aim-at-partner (the seam
stays general; only aim-at-partner built); self-capture suppression RULE (not built — layout + the
internal-vs-net argument handle legibility); >2 nodes / ring condensation (next horizon); any shared-kernel edit
(none).

## Deliverables
1. Seam #3 **SPECIFIED placement body** (aim-at-partner) — realized.
2. The clean `-aimed` preset/mode + the run.
3. **Inter-node distance trace** (start→min approach, EXCEEDS noise) + cross-capture counts + **self-capture
   count & transmitted force** + the overrun flag + a viewer (`threejs_testb_aimed`).
4. This findings doc + JOURNAL + CLAUDE updates.

## Verdict
**The SCPR capture-and-pull primitive is demonstrated CLEANLY over a gap** (initial approach 0.176 µm, ≈60×
above noise) with SPECIFIED aim-at-partner placement, on GPU-resident SoA (CPU≡GPU), every prior assay +
production byte-unchanged. Self-capture is significant in force but **non-blocking** (internal to a node;
cross-capture carries the net pull) — jba's layout intuition holds operationally; the literal "negligible"
is refuted and reported. Sustained contraction past the meeting point needs turnover (deferred); ring
condensation is the next horizon.
