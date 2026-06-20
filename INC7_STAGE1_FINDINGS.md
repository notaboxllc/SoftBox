# Increment 7 Stage 0 + Stage 1 — pool-return + conservation + pointed-end depoly + filament death — FINDINGS

**Status: DONE (2026-06-19). 7 gates PASS GPU + CPU; conservation EXACT; no-op-when-off bit-identical; growth /
nodenuc regressions bit-identical; default-OFF.** The reverse-of-growth turnover foundation (recon
`INC7_TURNOVER_RECON.md`, Stages 0+1). Run: `./run_depoly.sh [-cpu]`. **No default flip.**

## What was built
Filaments now **shrink at the pointed end (end1) and DIE** — the slot freed, monomers conserved exactly back to
the pool — reversibly with growth. New files only + two additive edits; every prior assay byte-unchanged.

- **`ActinPool.put(int)` + an exact integer conservation ledger** (Stage 0). The pool gains `put(n)` (the
  reverse of `take(n)`; v1 `Crucible.putMonomer`) and two `long` tallies (`totalTaken`/`totalReturned`) so the
  harness asserts `pool + Σ monomerCount = const` in **exact integer monomer units**, independent of the µM-scalar
  float. The seam is unchanged — the rate still reads `conc()`; the ledger is bookkeeping only.
- **`DepolySystem`** — two device-agnostic kernels:
  - **`depoly`** — per ACTIVE pointed-tip segment (`end1NbrSlot < 0`), at the FIXED rate `P = offRate·biochemDeltaT`:
    if `monomerCount >= actinSeed(3)` draw a wang-hash and on fire `monomerCount−−`, `coord += ½·mono·uVec` (the
    **exact reverse of growth's lengthen** — end2/barbed fixed, end1/pointed retracts inward), `returnedMon=1`;
    else (`< actinSeed`) `deathFlag=1`, `returnedMon=monomerCount` (the **en-masse** return).
  - **`applyDeath`** — per flagged-dead slot: `markFree` (filState FREE + Brownian scale 0 + monomerCount 0),
    **break BOTH neighbor links** (set the neighbors' back-pointers to −1 ⇒ valid sub-chains), and **clear the
    node tether** (`seedNode = −1`).
- **`DepolyStore`** — the depoly params + the FIXED rate (default `Constants.kATPOff1 = 0.8/s`) + the
  returned-monomer counter scratch (reused `csrScan`) + the **derived** probability + the biochem cadence.
- **`DepolyHarness`** + **`run_depoly.sh`** — the 7-gate validation, both runners.
- **`Constants`** — `kATPOff1 = 0.8/s` (pointed ATP-off), `kADPOff1 = 2.7/s` (Stage 3) — additions only.

## The recon's favorable findings, realized
- **Slot recycle was mostly built** (recon §3a): death = **`markFree` (a self-write of the FREE sentinel)** — the
  exact inverse of B1's `allocate`. **NO swap-compaction** (Design A slot-stability), structurally avoiding v1's
  `removeFilSegment` `packRange` ClassCast desync. A freed slot re-enters the scan-rank free-list the **SAME
  cadence** ⇒ reclaimable by a split (gate 2). Genuinely-new = `ActinPool.put` + the depoly kernels.
- **Pointed-end depoly = the reverse of growth** (recon §3d). The coord-shift sign flips (`+½·mono·uVec` vs
  growth's `−`), end2 (barbed/node) stays fixed, end1 (pointed) retracts. The segLength/drag update **REUSES
  `GrowthSystem.recomputeDrag` byte-unchanged**.
- **The node bound-count needs NO atomic decrement** (recon §3c, realized cleaner than v1). v1's `filamentOff`
  decrements; v2's `NodeNucleationSystem.countBoundFil` **recomputes** `nodeBoundFil[k]` from `seedNode` each
  cadence ⇒ death just clears `seedNode` (a self-write). Race-free, no atomics.
- **Severing's hard case is absent in Stage 1** but its machinery is validated: `applyDeath` breaks BOTH links,
  so an interior death yields **two valid reciprocal sub-chains** (gate 3b) — the general link-break Stage 2's
  whole-segment dissolve will reuse.

## Lock-step / cadence discipline (the new code only)
- `biochemDeltaT` (the 1e-3 s biochem clock) is **DERIVED** into the depoly probability `P = offRate·biochemDeltaT`
  each cadence in `DepolyStore.refreshRate` — **never a stale copy** (the chain-dt class avoided). The fire
  cadence `biochemCheckInt = round(biochemDeltaT/dt)` is derived in the constructor. Gate 6 confirms the empirical
  `P_emp = 0.00082` vs `offRate·biochemDeltaT = 0.00080` (rel 2.7%, ~4200 events) at the **default** rate.
- This build **assigns no per-assay `deltaT`/`biochemDeltaT`** — they're read, not set; no existing system was
  refactored for the broader global-vars pass.

## Race-freedom (no atomics / no KernelContext — `-cpu` safe)
- `depoly` — per-slot self-write (monomerCount/coord/returnedMon/deathFlag).
- `applyDeath` — the dying slot self-writes; the neighbor back-pointer writes are a **scatter to DISJOINT slots**
  (a pointed tip's node-ward neighbor is interior ⇒ not itself dying; only ONE segment per filament dies per
  cadence ⇒ distinct dying tips ⇒ distinct neighbors). Verified bit-identical CPU≡GPU (gate 4).
- pool return is a HOST scalar add over the `csrScan` total (the reused prefix-sum) — depoly is rate-FIXED
  (pool-INDEPENDENT), so the GPU lifecycle is self-contained (the pool needn't round-trip per step).

## Gates (all PASS, GPU + CPU)
0. **behavior** — 64 free filaments shrink + die: active 64→0, contour 2.2464→0.0000 µm, Σmonomer 768→0, FREE
   slots 64→128.
1. **conservation EXACT** — (a) depoly-only: `Fnow(0) + returned(768) == F0(768)`; `Δconc == returned·µMper`.
   (b) **grow+depoly combined**: `Fnow(227) == F0(160) + taken(163) − returned(96)` — both directions exercised,
   exact integer.
2. **slot-recycle** — slot 0 dies → freed; the same cadence slot 1's split child lands in **the freed slot 0**
   (monomerCount 32). Death→free-list→allocate, same step.
3. **link-break** — (a) pointed-tip death (C): B becomes the new pointed tip, A—B intact. (b) **interior death
   (B): two valid reciprocal sub-chains {A},{C}, B fully unlinked.**
4. **CPU≡GPU** — depoly + death + recomputeDrag + integrate over 300 fire-every steps (filaments die): mismatches
   monomer=0 state=0 link=0 (bit-identical lifecycle), max|Δcoord| = 0.00 µm.
5. **no-op-when-off** — depoly OFF ⇒ lifecycle bit-identical to a no-depoly baseline, max|Δcoord| = 0.00 (the
   flag is truly inert when off; depoly touches only scratch).
6. **rate-wiring** — `P_emp 0.00082 vs offRate·biochemDeltaT 0.00080` (rel 2.7%) at the default `kATPOff1`, no
   death contamination (derived-probability / cadence lock-step).

## Regression (no-op-when-off + additive-only)
- Touched files: `Constants.java` (additions only, no existing value changed), `ActinPool.java` (additive `put` +
  ledger; `take`'s conc math byte-unchanged). New files: `DepolySystem`, `DepolyStore`, `DepolyHarness`,
  `run_depoly.sh`.
- **Growth** (`run_growth.sh -cpu`) and **node-nucleation** (`run_nodenuc.sh -cpu`) — the two ActinPool
  consumers — re-run **PASS** (bit-identical; only a tally field added to `take`).

## Notes / flags for the planner
- **TEST rate vs DEFAULT.** The death/conservation/CPU≡GPU gates use a TEST `offRate = 100/s` (P=0.1/fire) to
  exercise death on a short horizon; the **default is `kATPOff1 = 0.8/s`** (P=8e-4/fire — biologically slow). The
  machinery is rate-independent; gate 6 validates the real default-rate wiring.
- **Stage 1 only kills pointed tips.** Depoly acts on `end1NbrSlot < 0` (the outermost segment), so a filament is
  consumed pointed-end-first, one segment per filament per cadence ⇒ death never produces two sub-chains in Stage
  1 (it shortens the chain). `applyDeath`'s two-link break is the GENERAL machinery (validated via a direct
  interior death, gate 3b) that **Stage 2's whole-segment cofilin dissolve** will drive.
- **Reused-slot monomerCount.** A dead slot is set `monomerCount = 0`; reuse paths set it (split via `splitWire`).
  **B2 nucleation's `allocate` does NOT set monomerCount** (it assumes the seed's pre-init value) — so if a dead
  slot (monomerCount 0) is later reused by **nucleation**, its monomerCount would be stale 0, not `actinSeed`.
  **Not exercised in Stage 1** (gate 2 reuses via split). **FLAG:** when turnover + nucleation run together (the
  ring), `NodeNucleationSystem` (or `allocate`) must (re)set the born seed's `monomerCount = actinSeed`. A
  one-line fix at that integration point; called out so it isn't missed.
- **Defaults / next.** Default-OFF (no validated assay runs depoly). Stage 2 = en-masse old-ADP-segment dissolve
  + the per-segment ADP-fraction proxy (the lighter disposal, threshold a tunable seam). Stage 3 = nucleotide-
  dependent rates (`kADPOff1` added) + treadmilling. `BoA-v1ref` byte-clean; production untouched.

## TL;DR
Filaments **shrink at the pointed end and DIE** — slot freed via `markFree` (Design A, no compaction), monomers
**conserved exactly** (integer-exact, both directions) back to the pool — the **reverse of growth**, default-OFF,
race-free (no atomics), **CPU≡GPU bit-identical**. Slot recycle reclaims same-cadence (death→free-list→allocate);
the death link-break leaves valid sub-chains (the Stage 2 hook). 7 gates PASS both runners; growth/nodenuc
bit-identical. **No default flip.**
