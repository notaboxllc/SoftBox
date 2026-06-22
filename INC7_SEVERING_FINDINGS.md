# Increment 7 — SEVERING build (B): cofilin en-masse dissolve + the combined watchable turnover system — FINDINGS

**Status: DONE (2026-06-20). 6 gates PASS GPU + CPU; trigger faithful to v1 (analytic Δ 1.25e-7, dissolve fires
bit-for-decision at the cofilinRatio crossing); interior dissolve → two valid reciprocal sub-chains; conservation
EXACT through the dissolve; CPU≡GPU bit-identical; no-op-when-off ≡ aging baseline bit-identical; the combined
turnover (growth + depoly + aging + severing) runs in one sim; default-OFF.** Completes the simplified treadmilling
system (recon `INC7_TURNOVER_RECON.md` §1e/§3b; the dissolve REUSES the validated Stage-1 death path). Run:
`./run_severing.sh [-cpu] [-3js <dir>]`. Log: `RUN_LOGS/2026-06-20_severing_validation.txt`. **No default flip.**

## What was built (ADDITIVE — reuses the Stage-1 death path; no existing kernel touched)
Severing = v1's en-masse **WHOLE-SEGMENT dissolve** (`checkCofilinDissolve`), NOT a mid-segment cut. A segment
whose cofilin fraction crosses `cofilinRatio` dissolves: all its monomers return to the pool en masse, its two
neighbor sub-chains (existing segments, correct `monomerCount`/`nucFrac`) become separate filaments via the
interior link-break — **no new slot, no `monomerCount`/`nucFrac` init**.

- **`SeverStore`** — the per-segment cofilin fraction `cofFrac[filCap]`, the derived cofilin params
  (`p_cof = cofilinConc·cofilinRate·biochemΔt`, `cofilinRatio`), and SEPARATE death scratch from depoly
  (`severDeathFlag`/`severReturnedMon`/`severReturnedOffsets`) so a slot that both depoly's and dissolves in one
  cadence is counted once per pass (conservation exact — the depoly pass runs to completion before the sever pass,
  which skips already-FREE slots).
- **`SeveringSystem`** — 2 device-agnostic kernels:
  - **`cofilinAccumulate`** — `f_cof += (f_ADP − f_cof)·p_cof` (the aggregate of v1's per-monomer Bernoulli
    binding over the ADP-not-yet-cofilin monomers; cofilin binds only ADP ⇒ `f_cof ≤ f_ADP`). The proxy's `f_ADP`
    (build A) is the input.
  - **`cofilinDissolve`** — flag `severDeathFlag=1` + `severReturnedMon=monomerCount` when `f_cof > cofilinRatio`
    (bit-for-decision with v1's `cofilinCt/monomerCt > cofilinRatio`).
- **The dissolve mechanism REUSES `DepolySystem.applyDeath` byte-unchanged**: markFree + en-masse
  `pool.put(monomerCount)` + break BOTH neighbor links → two valid reciprocal sub-chains + clear `seedNode`. An
  interior dissolve fragments the filament; a pointed-tip dissolve shortens it (both the gate-3b/4 machinery, now
  cofilin-driven).
- **`Constants`** — `cofilinRate=0.1`, `cofilinConc=3.0`, `bundleStableFactor=2.0`, `cofilinRatio=1.0`
  (additions only; default 1.0 ⇒ dissolve OFF).
- **`SeveringHarness`** + **`run_severing.sh`** — the 6-gate validation + the combined `-3js` render.
- **Viewer hook** — already provided by build A's `FrameWriter.writeFrame(FilamentStore, AgingStore, t)` (it skips
  FREE slots): a dissolved segment is markFree'd ⇒ it **vanishes** from the frame, and the filament shows as two
  separate chains ⇒ the fragmentation is directly watchable. No new FrameWriter code.

## Gates (all PASS, GPU + CPU)
**1 — trigger faithfulness.** With `f_ADP` held =1, the per-segment cofilin fraction matches the analytic
`f_cof(n) = 1 − (1−p_cof)^n` to **max 1.25e-7** at n=1000/3000/10000. The dissolve fires at **exactly** the
predicted threshold-crossing cadence (n*=2311 for ratio 0.5 = `ceil(ln(0.5)/ln(1−p_cof))`), en-masse
`returnedMon = 32`. Bit-for-decision on v1's `cofilinCt/monomerCt > cofilinRatio` formula.

**2 — cofilin-DRIVEN interior dissolve → two valid sub-chains.** Chain A—B—C, B driven fully ADP ⇒ cofilin
accumulates ⇒ B dissolves at cadence 2311 (the cofilin trigger, not a forced deathFlag): B dead + fully unlinked;
{A} and {C} are valid reciprocal free-ended sub-chains; `Σmono 96→64` (+32 returned). The gate-3b link-break,
now cofilin-driven.

**3 — conservation EXACT through the dissolve.** 200000 cadences with dissolves firing (ratio 0.5):
`Σnow == monInit + taken − returned` exact (integer ledger), sampled every 20000 cadences. The en-masse
`put(monomerCount)` extends the gate-1 ledger.

**4 — CPU≡GPU (dissolve decision + link-break).** 40000-cadence full combined biochem pipeline (19-task
device-resident graph: age → depolyProxy → … → cofilinAccumulate → cofilinDissolve → severDeath → … →
splitInherit): mismatches monomer=0 state=0 link=0, [actin] CPU=GPU=0.581202 — **bit-identical** through the
dynamic dissolve/death/fragment/split churn.

**5 — no-op-when-off ≡ aging baseline.** `cofilinRatio=1.0` ⇒ `f_cof ≤ f_ADP ≤ 1` never crosses ⇒ no dissolve;
40000 cadences with the sever pass running vs the aging baseline (no sever pass): mismatches monomer=0 state=0
link=0, pool match — **bit-identical** (the severing kernels write only their own scratch).

**6 — the COMBINED watchable render.** ONE node, pre-existing formin filament, ALL systems on (growth + depoly +
aging + severing): runs stably, dissolves fire (7 events), conservation EXACT. `-3js` dumps frames coloured by
the ADP cascade (build A's hook); dissolving segments vanish + the filament fragments. The growth-first scene
(start short, high [actin]) shows polymerization (3→7 segments) → the aging gradient develops → the oldest
segments dissolve + fragment, over ~13 s before the no-nucleation wind-down (see Flags).

## Lock-step / cadence + race-freedom (new code only)
`p_cof` is DERIVED from `cofilinConc·cofilinRate·biochemDeltaT` each cadence (never stale). `cofilinAccumulate` and
`cofilinDissolve` are per-active-slot self-writes; the link-break is in the reused `applyDeath`. Neighbor-scatter
safety (flagged in `SeveringSystem`): two ADJACENT co-dissolving segments both write the link between them, but to
the SAME value (−1) ⇒ idempotent ⇒ race-free even for adjacent co-dissolves (stronger than depoly's
one-tip-per-filament guarantee). No atomics / KernelContext.

## PAUSE + REPORT (discovery boundary — NOT silently implemented)
- **Faithful FREE-FRAGMENT barbed-end dynamics would require barbed-end (end2) depoly — a Stage-1 deferral
  (pointed-only).** An interior dissolve exposes the outer sub-chain's NEW barbed end (end2); in v1 that end
  depolymerizes (`kATPOff2=1.4`/`kADPOff2=7.2`, faster than pointed). v2's outer fragment shrinks only from its
  pointed end (end1) here. **The severing build is correct + conserved without it** (all gates pass); end2 depoly
  is **NOT added silently** — flagged as the next layer (it would re-baseline the Stage-1 depoly path). Per the
  scope's discovery boundary, reported not implemented.
- **Tropomyosin protection is NOT modeled (no tropo state in v2).** In v1, tropomyosin (`tropoOnRate=1.0`,
  `tropoOffRate=0` ⇒ sticks) competes with cofilin for monomers, protecting them. The per-segment proxy carries no
  tropo state, so cofilin binds unimpeded here — faithful to v1's cofilin *formula* modulo tropo. A Stage-4-style
  per-monomer fidelity item (recon §4 vestigial). Reported, not invented.
- **Cofilin bundling-resistance (`/(bundleStableFactor·linkCt)`) is the faithful v1 formula but unexercised here**
  (the turnover assay carries NO crosslinkers ⇒ `linkCt=0` ⇒ unbundled, matching v1's `linkCt=0` path exactly).
  Exercised only with the crosslinker subsystem present — a ring-integration concern.

## Notes / flags for the planner
- **The combined render winds down without nucleation (flagged, expected).** A single closed-pool filament with
  severing spawns multiple free fragments (each depolymerizing from its own pointed end) while only ONE tip grows
  (nucleation deferred) ⇒ total removal outpaces single-tip growth ⇒ the filament fully turns over in ~10–30 s.
  Conservation holds throughout. The nucleation-driven SUSTAINED full lifecycle (a node continuously nucleating +
  growing + aging + severing) is the **ring-ward next step** (the dead-slot `monomerCount` reuse flag from
  `INC7_STAGE1_FINDINGS.md` applies there).
- **Default-OFF overall** (no validated assay runs severing); `BoA-v1ref` byte-clean; production untouched. The
  build-A aging, Stage-1 depoly, and growth regressions all re-run PASS.
- **FOLLOW-ON (flagged, NOT done here): the v1 AGGREGATE adjudication** — the cofilin-driven length distribution
  vs v1's turnover-stress fixture (`boa10-64Seg-turnoverstress`), via the parity contract (`V1_V2_PARITY.md`).
  This is where tropomyosin protection + bundling + the full fragment dynamics would be felt; it is the proper
  emergent-behavior comparison (§8) and is the next validation, not part of this build.

## Addendum (2026-06-20) — persistent treadmill render + barbed-end "+" fix
- **Barbed-end "+" restored in the viewer.** The verbatim v1 viewer places its "+" sprite at `end2` of any segment
  with `"isBarbedEnd":true` in the frame JSON (viewer `updateBarbedEnds`). SoftBox's `FrameWriter` had **never**
  emitted that field (only v1's `ThreeJSWriter` did) ⇒ no "+" on any SoftBox render. Fixed additively: both
  `appendSegment` overloads now emit `"isBarbedEnd": (end2NbrSlot[i] < 0)` — the barbed terminal (barbed=end2,
  settled). All SoftBox renders now show the "+" at the growing barbed tip.
- **Free-treadmilling render (`renderTreadmill`, run via `-3js`).** The closed-pool combined render winds down
  (no nucleation; §Notes). The watchable render instead is an **UNANCHORED, TRANSLATING** treadmilling filament:
  - **Persistence** — a **BUFFERED pool** (`cadenceCpu`'s new `bufferedPool` flag skips the pool take/put ⇒
    `[actin]` held constant ~4 µM ⇒ `P_grow` stays high — the "adjust polymerization to keep up" lever) + a
    **formin-capped barbed tip** (the `seedNode≥0` tip kept ATP-fresh each cadence — modeling formin's processive
    ATP-actin incorporation + cofilin protection — so it never ages/dissolves and always regrows).
  - **TRANSLATION (the "barbed end never moves" fix).** `GrowthSystem.grow` keeps the barbed end (end2) FIXED and
    only extends the pointed end — the formin-AT-A-NODE geometry (barbed clamped, no translation). The render
    converts this to a FREE PROCESSIVE-formin filament by rigidly translating the whole filament `+δ·uVec` per
    barbed monomer added (`δ=actinMonoRadius`, `translateMainFilament`): the barbed tip ADVANCES, pointed depoly
    makes the pointed tip FOLLOW ⇒ the filament treadmills *through space* at constant length. A render geometry
    choice (monomer bookkeeping/rates unchanged).
  - **Unanchored mechanics** — full Brownian + chain integration, **no node tether / no containment** ⇒ the
    filament moves, wiggles, diffuses (positional constraints unlocked).
  - Result: the filament **PERSISTS + TRANSLATES** (active ~24–26 segments over 60 000 cadences; barbed tip
    advances a net **≈14 µm**, monotonic 9.0 → −4.9 µm; leading barbed "+" tip young/green `fATP=1.0` → trailing
    pointed end old/red `fADP=0.99`; occasional severing + fragmentation). A demo render (buffered pool +
    formin-fresh tip + the translation are viewer modeling choices, flagged — NOT the closed-pool conservation
    gate). Run: `./run_severing.sh -cpu -3js threejs_treadmill`. Gates 1–6 unchanged (PASS GPU+CPU).

## TL;DR
Filaments **SEVER** — a per-segment cofilin fraction accumulates off the proxy's `f_ADP` (faithful aggregate of
v1's per-monomer binding, analytic Δ 1.25e-7), and a segment crossing `cofilinRatio` **dissolves en masse**
(bit-for-decision with v1's `checkCofilinDissolve`), the filament **fragments into two valid reciprocal
sub-chains** (the reused Stage-1 death path), monomers conserved. The **full simplified turnover machinery
(growth + depoly + aging + severing) runs together in one watchable sim**, default-off, race-free, **CPU≡GPU
bit-identical**, conservation EXACT. 6 gates PASS both runners. Flagged (PAUSE + report, not silently added):
faithful free-fragment barbed-end dynamics need end2 depoly (a Stage-1 deferral); tropomyosin protection is not
modeled (no tropo state); the v1 aggregate length-distribution comparison is the follow-on. **No default flip.**
The completed simplified treadmilling system.
