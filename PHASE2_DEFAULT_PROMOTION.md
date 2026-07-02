# Phase-2 — promote the f̂-directed neck-stroke motor to the DEFAULT myosin

**Date:** 2026-07-01. The DEFAULT motor CHANGES (so "default byte-identical" no longer applies — the new
contract is the two reproductions below + CPU≡GPU). `BoA-v1ref` untouched. No kinetics/geometry retune; no
release change.

## The decision (from the 2026-07-01 JOURNAL entry)
Adopt the **f̂-referenced (filament-directed) neck powerstroke** (`-dirswing`) as the default GPU myosin.
Biological basis: stereospecific binding clamps the head to actin, so an f̂-referenced neck swing is the
faithful registration; it is robust to the roll/head-axis free signs that sank the head-frame recast
(`PHASE2_HFSWING_GAP_FINDINGS`, `PHASE2_MHAT_BIND_FINDINGS`), and it gives the skeletal −3.0 µm/s glide on the
GPU dense mat (`SPHEREHEAD_MOTOR_RESULTS`, `PHASE2_SPHEREHEAD_AXLOCK_FINDINGS`).

## The flag collapse — the new default = frozen-F9-90° + axlock + dirswing
The default motor (runs with **NO flags**, on both the GPU TaskGraph and the `-cpu` runner) is now the
composition previously spelled `-spherehead -axlock -dirswing`:
- **Fixed head at 90° ⊥** — F9 rest frozen at 90° (`xbParams[9/…]`), no head swing (`SPHEREHEAD`).
- **Axial swing lock** — F10 retargeted to ŝ = n̂bed × û_seg, head-only (`AXLOCK`).
- **f̂-directed neck powerstroke** — `CrossBridgeSystem.directedSwing` (rear sweeps barbed-ward, θ 0→60°;
  the J1 angular converter is turned OFF, `jointParams[3]=0`) (`DIRSWING`).
- `-hfswing`, `-rollsign`, mhat-lock/bind-set stay **OFF** (documented negatives; the f̂-directed stroke is
  robust to the roll sign, so they are not needed).
- **Release UNCHANGED** — the default catch-slip + standard 4-state `NucleotideCycleSystem.cycle`.

**Mechanism:** a single post-arg-parse block sets `SPHEREHEAD=AXLOCK=DIRSWING=true` unless a legacy or
alternative motor was explicitly selected — setting **exactly** the three booleans the explicit `-dirswing`
path set, so no force law changed; only the default flag values did.

```java
if (LEGACYMOTOR) {                       // old F9 head-swing motor (sphere-head stack OFF)
    SPHEREHEAD = false; AXLOCK = false; DIRSWING = false; HFSWING = false; ROLLSIGN = false; MHATSET = false;
} else if (!CANONICAL && !CONFIG1 && !PERPHEAD) {   // the alternative bond laws select their own motor
    SPHEREHEAD = true; AXLOCK = true; DIRSWING = true;   // == old `-dirswing`
}
```

## The `-legacymotor` flag (default OFF) — the old motor kept reachable
`-legacymotor` restores the OLD default: the v1-port **F9 head-swing** motor (F9 rest switches 90°↔120°,
the state-dependent head-vs-actin power stroke; J1 angular converter ON; no axial lock; no directedSwing) —
the prior validated glider, kept reachable for regression/oracle checks.

## Regression — the new contract (all runs seed 0x6111D, `-matbed -grid -density 300 4000`, CPU, bit-reproducible)

| check | comparison | result |
|---|---|---|
| **A** | new **default** (no flags) ≡ old **`-dirswing`** | **IDENTICAL** (bit-for-bit; same booleans → same path) |
| **B** | **`-legacymotor`** ≡ old **default** (HEAD, no flags) | **IDENTICAL** (bit-for-bit; built from the pre-promotion commit) |
| sanity | new default ≠ `-legacymotor` | **DIFFER** (the promotion genuinely changed the default) |

- **A** — ran the new default and `-dirswing` back-to-back; every physics line identical. By construction: the
  promotion block sets the same three booleans `-dirswing` sets, and nothing else differs.
- **B** — `git stash`ed the promotion, rebuilt the pre-promotion HEAD, ran it with no flags (= the old
  default); rebuilt the promoted tree, ran `-legacymotor`; every physics line identical. `-legacymotor`
  reproduces the old F9 head-swing default exactly.

## Confirm the promotion didn't change the physics
- **Check D — new DEFAULT reproduces −3.0 ✓** (GPU dense mat, no flags, `-full` d1000, 150k steps, LONG_ROW
  LS-centroid along f̂, on-bed window [0.20,1.50]s): **v_axial = −3.02 µm/s, axial frac 0.996, avgBound 14.8.**
  Skeletal, pointed-leading, fully directed — and bit-identical to the standalone `-dirswing` control
  (−3.023 / 0.996 / 14.77), as Check A guarantees. The promotion did not change the physics.
- **Check C — CPU≡GPU on the new default ✓** (`-matbed -grid -density 500`, 60k steps, one density):
  **GPU avgBound 10.51 (all) / 9.98 (steady) vs CPU 10.29 / 9.54** — agree within ~2–5%, the chaotic-mat
  aggregate-within-SEM standard (the on-bed window is SHORT at 0.4 s here so the per-window *speeds*,
  GPU −2.47 vs CPU −2.76, are thermally noisy; avgBound is the robust aggregate and it matches). The default
  `directedSwing` task + race-free CSR seg-gather run identically on both runners.

## CPU / GPU validity
The default motor runs on BOTH the device-resident GPU TaskGraph and the `-cpu` sequential runner (the
`directedSwing` task + the race-free CSR seg-gather, no atomics/KernelContext). CPU≡GPU is the chaotic-mat
aggregate-within-SEM standard (Check C). `BoA-v1ref` byte-clean.

## Run
```
GlidingHarness -gpu -full -grid -density 1000 150000     # the NEW DEFAULT (f̂-directed sphere-head), −3.0
GlidingHarness -gpu -full -grid -legacymotor 150000      # the OLD default (v1-port F9 head-swing), reachable
```
New: `-legacymotor` + the post-arg-parse promotion block (`GlidingHarness`). The default changed;
`-dirswing` is retained as an explicit alias of the default.
</content>
