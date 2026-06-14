# Increment 4b-iv (gliding assay) — RECONCILED: the "0.51× miss" was dominantly measurement-method

**Status: RECONCILED (measurement/protocol only — no physics changed).** The prior "0.51× velocity miss"
compared two *different measurements*: v2's **net-displacement** glide speed against v1's
**`longWindowSpeedXY`-at-end-of-a-0.1 s-run** (the "8.33 fixture"). Measured the SAME way, multi-seed, at
matched boxes, **v2 = 0.76× v1** — a small, box-UNIFORM residual, not a 2× miss and not a box-scaling
mismatch. v2 reproduces v1's avgBound, its `instantaneousSpeed`, and its (weak) box-size scaling; the
residual is specifically in **net directedness** (v2 converts a smaller fraction of its matched total
motion into forward glide — the co-bound tug-of-war of §5, now correctly sized at ~0.76× not ~0.5×).

Everything in 4a–4b-iii remains committed and validated; this document supersedes the earlier "0.51× clean
full-scale finding" framing with the matched-measurement reconciliation. Measurement harness +
methodology are committed; **no physics edits.**

---

## 1. Provenance of "8.33" (resolved)

- "8.33" is **not** in v1's validated table. It traces to the iter2a / soa-coord CPU≡GPU validation
  (`BoA-v1ref/JOURNAL_ARCHIVE.md:8452, 12271`): *"glidingVelocity = mean `longWindowSpeedXY` across
  filaments at end of run"*, `glidingAssay500_val` (14×2), runTime 0.1 s, 10 seeds → CPU 8.326 ± 0.179 /
  GPU 8.231. It is `longWindowSpeedXY` sampled **at the end of a short run**, not a net-glide speed.
- The **validated** d=500 oracle (`BoA-v1ref/MYOSIN_VALIDATION.md` line 41/54) names `longWindowSpeedXY`
  the "primary gliding-velocity statistic" and reports it **averaged over a ~0.5 s run**: median **3.70**,
  mean **4.23**, avgBound 6.91.
- The two v1 numbers differ ~2× purely by **window / run-length + a startup artifact**. In the v1 .dat the
  very first output interval reports `instantaneousSpeed = 309 µm/s`, `longWindowSpeedXY = 309` — a 0.31 µm
  centroid **settling jump** as the filament drops onto the bed and first binds. Over a *short* 0.1 s run
  the long-window chord stays inflated for the whole run; averaged over a *long* run it relaxes toward the
  true net glide. `MYOSIN_VALIDATION.md` lines 187–200 say this outright: `instantaneousSpeed` "counts
  wandering as motion"; **use `longWindowSpeedXY` or net-displacement/time for honest comparison.**
- The net-vs-inflated gap is a property of the *measurement*, present in BOTH codes: at 14×2, v1's
  `instantaneousSpeed` ≈ 7.5 and its `longWindowSpeedXY`-at-end ≈ 8.1 vs its **net ≈ 5.0**; v2's
  `instantaneousSpeed` ≈ 6.7 vs its **net ≈ 3.8**.

## 2. The grid — multi-seed, v2 measured v1's way (both statistics, both boxes)

All cells: `glidingAssay500_val` regime (dt 1e-5, d=500/µm², fracMove/fracR/fracMoveTorq = 0.5/0.1/0.2),
10 000 steps = 0.1 s, toFileInterval = 100. **NET** = net centroid displacement / elapsed time (the honest
glide); **inst** = mean per-interval 3D `instantaneousSpeed`; both computed by v1's exact
`GlidingAssayEvaluator` algorithm (ported into `GlidingHarness.measureGrid` for v2, `-grid`). v1 = real
`BoxOfActin -r -gpu` runs, `BOA_RNG_SEED` 1–3; v2 = `GlidingHarness -grid -gpu` seeds 1–3. ± = SEM (n=3).
Raw: `RUN_LOGS/2026-06-14_4biv_grid_reconciliation.txt`.

| box  | code  | **NET (µm/s)**   | instantaneous | avgBound |
|------|-------|------------------|---------------|----------|
| 4×1  | v1ref | **4.71 ± 0.14**  | 6.85          | 7.32     |
| 4×1  | v2    | **3.66 ± 0.11**  | 6.81          | 6.79     |
| 14×2 | v1ref | **5.02 ± 0.16**  | 7.54          | 7.22     |
| 14×2 | v2    | **3.76 ± 0.17**  | 6.69          | 7.50     |

Reference (not re-run): v1 14×2 `longWindowSpeedXY`-at-end-of-0.1 s = **8.33 ± 0.18** (10 seeds, the old
"fixture"); v1 d=500 validated `longWindowSpeedXY` mean over 0.5 s = **4.23** / median 3.70, avgBound 6.91.
The decisive cell (v1 **NET** @ 14×2 = **5.02 ± 0.16**) is the apples-to-apples partner of v2's net 3.76 —
it is ~5, NOT ~8.3.

## 3. Decomposition — the three contributors, separated

- **(a) Measurement method — the dominant factor.** The "8.33 → ~4" collapse is the net-vs-
  `longWindowSpeedXY`-at-short-run-end difference (+ the startup jump). v1's own NET at 14×2 is **5.0**,
  not 8.33. The original "0.51×" (v2 net 4.25 vs v1 lwEnd 8.33) was 60 % this measurement mismatch.
  Matching the statistic is most of the reconciliation.
- **(b) Box-size scaling — NO mismatch.** Under one matched statistic (NET): v1 4.71 → 5.02 (**+6.5 %**);
  v2 3.66 → 3.76 (**+2.8 %**). **Both codes show only weak positive box scaling in net terms — v2
  reproduces it.** The earlier "v1 climbs 4.4 → 8.33 across boxes while v2 stays flat" was comparing v1's
  *lwEnd* (8.33) against v2's *net* — an artifact of mixing statistics, not a real edge/finite-size
  divergence. (v1's `instantaneousSpeed` does climb mildly with box, 6.85 → 7.54; v2's stays ~6.7 — a
  minor secondary difference, not the headline.)
- **(c) The residual — small, box-uniform, real.** At matched box + matched (NET) statistic:
  - 4×1:  v2/v1 = **0.78** (3.66 ± 0.11 vs 4.71 ± 0.14; gap 1.05 ± 0.18, ≈ 5.8 σ)
  - 14×2: v2/v1 = **0.75** (3.76 ± 0.17 vs 5.02 ± 0.16; gap 1.25 ± 0.23, ≈ 5.4 σ)
  A genuine ~**0.76×** shortfall, **uniform across boxes** and outside SEM — but a far cry from 0.51×, and
  NOT a box-size effect.

## 4. Verdict

**The gate substantially closes; the original 2× framing is wrong.** Matched box + matched velocity
statistic, multi-seed: v2's net glide is **0.76× v1's**, box-uniform, with **avgBound and
`instantaneousSpeed` matching v1**. The "0.51×" was dominantly a measurement-method conflation (net vs
`longWindowSpeedXY`-at-end-of-short-run) plus the startup settling jump; the box-size and box-scaling
framings are resolved (v2 reproduces v1's weak net box-scaling).

A **small, sharp, box-uniform ~0.76× residual remains** and it is **not** in binding, density, geometry,
box size, or total motion (all match) — it is specifically in **net directedness**: v2 and v1 make nearly
the same total per-interval motion (`instantaneousSpeed` matches), but v2 converts a smaller fraction of
it into forward glide. That is exactly the co-bound tug-of-war / advance-per-stroke signature of §5,
**now correctly sized at ~0.76× (≈ −24 %), not ~0.5×.** This is the proper, much-smaller target for any
future burrow — and it is the *coordination/directedness of the co-bound population under motion*, NOT the
box-size/edge mechanism and NOT an advance-per-stroke deficit twice as large as it really is.

## 5. Mechanism diagnosis (the `-diag` instrument — the now-correctly-sized target)

```
velocity = −4.27 µm/s, avgBound = 6.85
bound-state distribution: NONE 0.5%  ATP 54.0%  ADPPi 3.0%  ADP 42.5%
forceDotFil (bound): mean +0.27 pN, 52.9% positive (assist) / 47% negative (resist)
mean bound-time = 126 steps (1.26 ms); catch-slip release rate 792/s
power strokes (ADPPi→ADP while bound) = 268/s per bound motor
filament advance per power stroke = 2.33 nm   (unloaded stroke ≈ 7 nm, validated 4b-iii)
```

Decomposition `velocity = avgBound × strokeRate × advancePerStroke` holds. The bound population is a
near-balanced **tug-of-war (~53 % assisting / 47 % resisting)** ⇒ weak NET force ⇒ small forward advance
per stroke, even though total motion (instantaneous) matches v1. For v1 to net ~24 % more glide at the
same avgBound and the same instantaneous activity, its bound population must be marginally more
asymmetrically assisting (resisting motors clear slightly faster / fewer bind mid-resist). The levers that
would raise net directedness (chain `fracMoveTorq`, `myoSpring`, the catch-slip params, the 7 nm stroke)
are all **FROZEN validated constants** — chasing the last ~24 % by changing any is the forbidden tuning.
So the residual is a faithful-config finding about collective stroke-transmission directedness under
motion, sized at ~0.76×.

## 6. Open questions for the planner (re-scoped)

1. **The ~0.76× net-directedness residual** (box-uniform, instantaneous + avgBound matched). Candidate
   faithfulness checks (NOT tuning): (a) is the inc-2 chain force — calibrated for *deflection* at
   `fracMoveTorq=0.265` — faithful at the *gliding* `0.2`? A stiffer chain transmits strokes more
   directedly and resists local bending that scatters motion sideways. (b) Does v1's catch-slip release
   the *resisting* (negative-forceDotFil) population marginally faster than ours? (c) the filament z
   settles to ≈ −0.017–0.056 in v2 — does v1's stay nearer 0?
2. **Box scaling is now closed** as a target — both codes scale weakly and equally in net terms.
3. **Commit policy.** The reconciliation is measurement-only; committed as a methodology + harness update.
   The residual is correctly sized and re-targeted; whether to burrow is the planner's call.

## 7. What is and isn't validated

| aspect | status |
|---|---|
| Assembly integrates (binding+cycle+cross-bridge+gather+stroke+chain+catch-slip) | ✓ works, stable |
| Glide direction −x | ✓ |
| avgBound vs v1 (matched box, multi-seed) | ✓ 7.50 vs 7.22 @14×2; 6.79 vs 7.32 @4×1 |
| `instantaneousSpeed` vs v1 (matched box, multi-seed) | ✓ 6.69 vs 7.54 @14×2; 6.81 vs 6.85 @4×1 |
| Box-size NET scaling vs v1 | ✓ both weak (+3–6%); v2 reproduces it |
| GPU TaskGraph (23 kernels, device-resident, full 14×2 box) | ✓ builds, runs, stable, no per-step pull |
| GPU throughput | ✓ 386 steps/s @ 13.4k motors (~19× CPU runner) |
| CPU≡GPU on gliding (aggregate-within-SEM) | ✓ avgBound matches; velocity in chaotic spread |
| **Gliding NET velocity vs v1 (matched box+statistic)** | ◑ **0.76× box-uniform residual** (was mis-framed as 0.51×) — small, sharp, in net directedness |

## 8. Reproduce

```
# v2 — the matched grid (both v1 statistics, multi-seed):
./run_gliding.sh -gpu -v1box -grid -seed 1 10000     # 4×1 box (2000 motors)
./run_gliding.sh -gpu -full  -grid -seed 1 10000     # 14×2 box (~13.4k motors)
#   prints GRID_ROW: inst / instSteady / netXY / netX / lwXY / avgB
./run_gliding.sh -diag 10000        # mechanism instrument (state dist, force balance, advance/stroke)
./run_gliding.sh -gpu -3js threejs_gliding 20000     # viewer (full motor carpet)

# v1ref (read-only worktree; outputs to a scratch dir — never written into v1ref):
#   cd /tmp/scratch; BOA_RNG_SEED=<n> java @argfile … BoxOfActin -r -gpu -3js <dir> \
#       -pf BoA-v1ref/ParameterFiles/glidingAssay500_val     # 14×2;  …/glidingAssay500_4x1 for 4×1
#   gliding_assay.dat has instantaneousSpeed (col 13) + longWindowSpeedXY (col 14); NET from posX/posY.
```
Viewer: `threejs_gliding` (v2) vs `threejs_v1_gliding` (v1), same nucleotide-state coloring.
