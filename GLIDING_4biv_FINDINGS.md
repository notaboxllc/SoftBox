# Increment 4b-iv (gliding assay) — RECONCILED: the "0.51× miss" was dominantly measurement-method

**Status: RECONCILED (measurement/protocol only — no physics changed).** The prior "0.51× velocity miss"
compared two *different measurements*: v2's **net-displacement** glide speed against v1's
**`longWindowSpeedXY`-at-end-of-a-0.1 s-run** (the "8.33 fixture"). Measured the SAME way, multi-seed, at
matched boxes, **v2 = 0.87× v1** — a small, box-UNIFORM residual, not a 2× miss and not a box-scaling
mismatch. v2 reproduces v1's avgBound, its `instantaneousSpeed`, and its (weak) box-size scaling; the
residual is specifically in **net directedness** (v2 converts a smaller fraction of its matched total
motion into forward glide — the co-bound tug-of-war of §5, now correctly sized at ~0.87× not ~0.5×).

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
- The net-vs-inflated gap is a property of the *measurement*, present in BOTH codes: at 14×2 (n=8), v1's
  `instantaneousSpeed` ≈ 7.3 and its `longWindowSpeedXY`-at-end ≈ 7.8 vs its **net ≈ 4.7**; v2's
  `instantaneousSpeed` ≈ 6.9 vs its **net ≈ 4.1**.

## 2. The grid — multi-seed, v2 measured v1's way (both statistics, both boxes)

All cells: `glidingAssay500_val` regime (dt 1e-5, d=500/µm², fracMove/fracR/fracMoveTorq = 0.5/0.1/0.2),
10 000 steps = 0.1 s, toFileInterval = 100. **NET** = net centroid displacement / elapsed time (the honest
glide); **inst** = mean per-interval 3D `instantaneousSpeed`; both computed by v1's exact
`GlidingAssayEvaluator` algorithm (ported into `GlidingHarness.measureGrid` for v2, `-grid`). v1 = real
`BoxOfActin -r -gpu` runs, `BOA_RNG_SEED` 1–8; v2 = `GlidingHarness -grid -gpu` seeds 1–8. ± = SEM (**n=8**).
Raw: `RUN_LOGS/2026-06-14_4biv_grid_reconciliation.txt`.

| box  | code  | **NET (µm/s)**   | instantaneous | avgBound |
|------|-------|------------------|---------------|----------|
| 4×1  | v1ref | **4.61 ± 0.13**  | 7.39          | 7.47     |
| 4×1  | v2    | **4.02 ± 0.15**  | 6.88          | 7.20     |
| 14×2 | v1ref | **4.69 ± 0.13**  | 7.33          | 7.29     |
| 14×2 | v2    | **4.10 ± 0.18**  | 6.92          | 7.60     |

Reference (not re-run): v1 14×2 `longWindowSpeedXY`-at-end-of-0.1 s = **8.33 ± 0.18** (10 seeds, the old
"fixture"); v1 d=500 validated `longWindowSpeedXY` mean over 0.5 s = **4.23** / median 3.70, avgBound 6.91.
The decisive cell (v1 **NET** @ 14×2 = **4.69 ± 0.13**) is the apples-to-apples partner of v2's net 4.10 —
it is ~4.7, NOT ~8.3.

(The earlier n=3 snapshot read v1 4.71/5.02 vs v2 3.66/3.76 → ratio ~0.76; tightening to n=8 regressed both
codes toward the middle — the n=3 v1 set held a high-outlier seed and the n=3 v2 set ran low — landing the
ratio at **0.87**. This is the expected small-n correction; the n=8 values supersede the n=3 ones.)

**Simulation time + startup window (sensitivity check).** Each cell is 0.1 s simulated (10k steps × dt
1e-5), centroid sampled every 100 steps (1 ms). The NET above is over the **full 0.1 s** — both codes
include their own binding-establishment transient, so it is apples-to-apples (v1 incidentally starts one
interval in, t=0.001 s, dropping its 0.31 µm settling jump). Re-measuring on the **steady 2nd half
(t > 0.05 s, startup-excluded, n=8)** *widens* the residual rather than closing it — the opposite of a
startup artifact:

| net window | v1 4×1 | v2 4×1 | ratio | v1 14×2 | v2 14×2 | ratio |
|---|---|---|---|---|---|---|
| full 0.1 s | 4.61 ± 0.13 | 4.02 ± 0.15 | 0.87 | 4.69 ± 0.13 | 4.10 ± 0.18 | 0.87 |
| steady t>0.05 s | 4.80 ± 0.21 | 3.72 ± 0.20 | **0.78** (3.8 σ) | 4.71 ± 0.15 | 3.84 ± 0.28 | **0.82** (2.8 σ) |

v1 **holds/rises** over the run (4×1 4.61→4.80, 14×2 4.69→4.71) while v2 **decelerates ~7 %** (4×1
4.02→3.72, 14×2 4.10→3.84), consistently at both boxes — v2 has a *faster startup but a slower sustained
glide*. So the full-run **0.87× is the conservative (friendlier-to-v2) estimate**; the sustained-glide
residual is ~0.80×. This is **not** a finite-strip edge effect (over 0.1 s the filament travels only
~0.4 µm and stays ≥1.3 µm from the bed edges). The z-probe (§6.1) then showed this small decay is **not**
z-driven either (v1 settles in z just as much yet holds glide) — the residual is ≈constant across the run
(v2/v1 ≈ 0.88 early → 0.84 late), so the ~7 % is a minor second-order widening, not the dominant effect.
`measureGrid` emits `netSteady` in the GRID_ROW for this check.

## 3. Decomposition — the three contributors, separated

- **(a) Measurement method — the dominant factor.** The "8.33 → ~4.5" collapse is the net-vs-
  `longWindowSpeedXY`-at-short-run-end difference (+ the startup jump). v1's own NET at 14×2 is **4.7**,
  not 8.33. The original "0.51×" (v2 net 4.25 vs v1 lwEnd 8.33) was mostly this measurement mismatch.
  Matching the statistic is most of the reconciliation.
- **(b) Box-size scaling — NO mismatch.** Under one matched statistic (NET): v1 4.61 → 4.69 (**+1.7 %**);
  v2 4.02 → 4.10 (**+2.0 %**). **Both codes show only weak positive box scaling in net terms — v2
  reproduces it.** The earlier "v1 climbs 4.4 → 8.33 across boxes while v2 stays flat" was comparing v1's
  *lwEnd* (8.33) against v2's *net* — an artifact of mixing statistics, not a real edge/finite-size
  divergence. (v1's `instantaneousSpeed` runs ~7.36 vs v2's ~6.90 — within ~6 %, a minor secondary
  difference, not the headline.)
- **(c) The residual — small, box-uniform, real (n=8).** At matched box + matched (NET) statistic:
  - 4×1:  v2/v1 = **0.87** (4.02 ± 0.15 vs 4.61 ± 0.13; gap 0.59 ± 0.20, ≈ 3.0 σ)
  - 14×2: v2/v1 = **0.87** (4.10 ± 0.18 vs 4.69 ± 0.13; gap 0.59 ± 0.22, ≈ 2.7 σ)
  - pooled (box-uniform ⇒ n=16 each): v2/v1 = **0.873**, gap 0.59 ± 0.14, **≈ 4.1 σ**
  A genuine ~**0.87×** shortfall (~13 %), **uniform across boxes** and outside SEM — but a far cry from
  0.51×, and NOT a box-size effect. (The n=3 snapshot put this at ~0.76× / >5 σ; the n=8 ensemble corrects
  it to ~0.87× / ~3–4 σ — smaller and at more modest significance.)

## 4. Verdict

**The gate substantially closes; the original 2× framing is wrong.** Matched box + matched velocity
statistic, multi-seed: v2's net glide is **0.87× v1's**, box-uniform, with **avgBound and
`instantaneousSpeed` matching v1**. The "0.51×" was dominantly a measurement-method conflation (net vs
`longWindowSpeedXY`-at-end-of-short-run) plus the startup settling jump; the box-size and box-scaling
framings are resolved (v2 reproduces v1's weak net box-scaling).

A **small, sharp, box-uniform ~0.87× residual remains** and it is **not** in binding, density, geometry,
box size, or total motion (all match) — it is specifically in **net directedness**: v2 and v1 make nearly
the same total per-interval motion (`instantaneousSpeed` matches), but v2 converts a smaller fraction of
it into forward glide. That is exactly the co-bound tug-of-war / advance-per-stroke signature of §5,
**now correctly sized at ~0.87× (≈ −13 %), not ~0.5×.** This is the proper, much-smaller target for any
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
per stroke, even though total motion (instantaneous) matches v1. For v1 to net ~13 % more glide at the
same avgBound and the same instantaneous activity, its bound population must be marginally more
asymmetrically assisting (resisting motors clear slightly faster / fewer bind mid-resist). The levers that
would raise net directedness (chain `fracMoveTorq`, `myoSpring`, the catch-slip params, the 7 nm stroke)
are all **FROZEN validated constants** — chasing the last ~13 % by changing any is the forbidden tuning.
So the residual is a faithful-config finding about collective stroke-transmission directedness under
motion, sized at ~0.87×.

## 6. Open questions for the planner (re-scoped)

1. **The ~0.87× net-directedness residual** (box-uniform, instantaneous + avgBound matched). Candidate
   faithfulness checks (NOT tuning): (a) is the inc-2 chain force — calibrated for *deflection* at
   `fracMoveTorq=0.265` — faithful at the *gliding* `0.2`? A stiffer chain transmits strokes more
   directedly and resists local bending that scatters motion sideways. (b) Does v1's catch-slip release
   the *resisting* (negative-forceDotFil) population marginally faster than ours? (c) ~~the filament z
   settles in v2 — does v1's stay nearer 0?~~ **TESTED — ELIMINATED (see below).**

   **z-settling probe (n=8, measurement only — `GlidingHarness -ztrace` + v1 `.dat` posZ; raw
   `RUN_LOGS/2026-06-14_4biv_ztrace.txt`): the residual is NOT progressive z-settling/disengagement.**
   Time-resolved 1 ms traces over the 0.1 s run show:
   - **Both codes settle to z ≈ −0.03…−0.04, nearly identically** — v1 14×2 −0.007→−0.036 vs v2
     −0.002→−0.030; v1 4×1 −0.010→−0.031 vs v2 −0.006→−0.040 (no consistent asymmetry — v2 settles
     *less* at 14×2, slightly more at 4×1). v2 does **not** sink more than v1.
   - **v1 settles in z just as much as v2 yet its glide holds** (v1 glide early→late: 14×2 −2 %, 4×1
     +14 %) — the direct counterexample: z-settling does not cause the glide difference.
   - **v2's assist fraction is flat ~0.50–0.55 throughout** (14×2 0.535→0.504); avgBound tracks v1's
     (both ~8.4→7.0). No progressive disengagement — the ~50/50 tug-of-war is present from the *start*.
   - The residual is **≈constant across the run** (v2/v1 ≈ 0.88 early → 0.84 late), present from the
     first bins, not a progressive collapse. (The earlier "v2 decays ~7 %" is a small second-order
     widening, not z-coupled and not the dominant residual.)

   **⇒ The residual is a *static* coupling deficit, not z-driven.** It is the ~50/50 assist/resist
   tug-of-war of §5, present throughout — so the live candidates revert to (a) chain stiffness at the
   gliding `fracMoveTorq=0.2` and (b) resisting-motor release timing, the static determinants of the
   bound population's assist/resist asymmetry. **Planner decides; not pursued here.**
2. **Box scaling is now closed** as a target — both codes scale weakly and equally in net terms.
3. **Commit policy.** The reconciliation is measurement-only; committed as a methodology + harness update.
   The residual is correctly sized and re-targeted; whether to burrow is the planner's call.

## 7. What is and isn't validated

| aspect | status |
|---|---|
| Assembly integrates (binding+cycle+cross-bridge+gather+stroke+chain+catch-slip) | ✓ works, stable |
| Glide direction −x | ✓ |
| avgBound vs v1 (matched box, n=8) | ✓ 7.60 vs 7.29 @14×2; 7.20 vs 7.47 @4×1 |
| `instantaneousSpeed` vs v1 (matched box, n=8) | ✓ 6.92 vs 7.33 @14×2; 6.88 vs 7.39 @4×1 |
| Box-size NET scaling vs v1 | ✓ both weak (+~2%); v2 reproduces it |
| GPU TaskGraph (23 kernels, device-resident, full 14×2 box) | ✓ builds, runs, stable, no per-step pull |
| GPU throughput | ✓ 386 steps/s @ 13.4k motors (~7.3× CPU runner: GPU 386 vs CPU 52.6 steps/s) |
| CPU≡GPU on gliding (aggregate-within-SEM) | ✓ avgBound matches; velocity in chaotic spread |
| **Gliding NET velocity vs v1 (matched box+statistic)** | ◑ **0.87× box-uniform residual** (was mis-framed as 0.51×) — small, sharp, in net directedness |

## 8. Reproduce

```
# v2 — the matched grid (both v1 statistics, multi-seed):
./run_gliding.sh -gpu -v1box -grid -seed 1 10000     # 4×1 box (2000 motors)
./run_gliding.sh -gpu -full  -grid -seed 1 10000     # 14×2 box (~13.4k motors)
#   prints GRID_ROW: inst / instSteady / netXY (full 0.1s) / netSteady (2nd-half, startup-excluded) / netX / lwXY / avgB
./run_gliding.sh -diag 10000        # mechanism instrument (state dist, force balance, advance/stroke)
./run_gliding.sh -gpu -3js threejs_gliding 20000     # viewer (full motor carpet)

# v1ref (read-only worktree; outputs to a scratch dir — never written into v1ref):
#   cd /tmp/scratch; BOA_RNG_SEED=<n> java @argfile … BoxOfActin -r -gpu -3js <dir> \
#       -pf BoA-v1ref/ParameterFiles/glidingAssay500_val     # 14×2;  …/glidingAssay500_4x1 for 4×1
#   gliding_assay.dat has instantaneousSpeed (col 13) + longWindowSpeedXY (col 14); NET from posX/posY.
```
Viewer: `threejs_gliding` (v2) vs `threejs_v1_gliding` (v1), same nucleotide-state coloring.
