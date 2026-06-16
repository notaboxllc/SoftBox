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

> **Closer (§6.7, 2026-06-15):** the variance characterization is done. The ~0.87× residual is **OUTSIDE
> v1's honest (autocorrelation-corrected) envelope** — a real −4.7 σ (pooled, n=24/16/16/15) systematic
> mean difference, but small: **≈1.2× v1's own seed-to-seed SD**, with heavily overlapping per-run
> distributions. Crucially it is **localized to precision/logic, NOT the GPU parallel reduction** (the gap
> is full-size on the *sequential* v2-CPU-vs-v1-CPU comparison; v1 and v2 each have CPU≡GPU at 14×2). ⇒
> Next step is the **numerical-match discriminator** (a double-precision build), not a reduction-order test.

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
   faithfulness checks (NOT tuning): (a) ~~is the inc-2 chain force — calibrated for *deflection* at
   `fracMoveTorq=0.265` — faithful at the *gliding* `0.2`?~~ **TESTED — FAITHFUL, ELIMINATED.** Ran v2's
   deflection characterization (`-characterize`) AND v1's (`BoxOfActin -bmDiag`) at the gliding
   `fracMoveTorq=0.2` (11-seg×32-mon pinned chain, fracR=0.1, the inc-2b setup): **v1 ratio 1.20240 vs v2
   1.20235, Δ 0.004 %** — and the 0.265 regression still matches (v1 0.99843 / v2 0.99831). v2's chain is
   as stiff as v1's at 0.2 to the inc-2b tolerance (≤0.05 %); both are ~20 % softer than the 0.265 beam
   target, identically. v1 and v2 share the identical damped-torsion law (`fracMoveTorq·(π/180)·angTween/
   ((1/bRotGam_i+1/bRotGam_j)·dt)`), linear in `fracMoveTorq` with no 0.265-baked constant — so it
   transfers cleanly to 0.2. **Caveat (measurement, not a gap):** that damped-torsion stiffness ∝ 1/dt, so
   the characterization MUST be at matched dt — the benchmark runs at dt=1e-4 (a wrong dt=1e-5 override
   made the chain look 10× stiffer); v1 and v2 share this dt-dependence faithfully. ⇒ Chain stiffness is
   NOT the residual cause; next is (b)/the cycle-under-load check. (b) ~~Does v1's catch-slip release
   the *resisting* (negative-forceDotFil) population marginally faster than ours?~~ **TESTED — the cycle
   is FAITHFUL; the residual is emergent (see §6.2 below).** (c) ~~the filament z
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
   tug-of-war of §5, present throughout. Two static candidates were then tested in turn (steps 1 & 2):

   **6.1a — chain stiffness at the gliding `fracMoveTorq=0.2` (step 1): FAITHFUL, ELIMINATED.** See §6(a)
   above: v1 (`-bmDiag`) and v2 (`-characterize`) deflection ratios match at 0.2 to Δ 0.004 % (1.20240 vs
   1.20235), and at 0.265 to 0.01 %. Identical damped-torsion law, no 0.265-baked constant. Not the cause.

   **6.2 — the nucleotide cycle under gliding load (step 2): cycle FAITHFUL; residual is EMERGENT.**
   Measurement only (raw `RUN_LOGS/2026-06-14_4biv_cycle.txt`). Three checks:
   - **Self-consistency (v2-internal, `-cycldiag`):** empirical per-state conditional transition rates
     under load match the validated nominal rates within ~10 % (NONE→ATP 98 %, ATP→ADPPi 97 %, ADPPi→ADP
     89 %, ADP→NONE|gate-open 111 %). The high ADP occupancy is the **load-gate** (open only 37 % of the
     time — assisting load holds ADP motors), NOT a malfunction. Cycle is self-consistent. ✓
   - **Drift (longer 0.3 s run):** v2's assist-fraction (~0.52) and glide (~4) **flatten** — no continuing
     drift; only z keeps settling. Confirms the residual is static (the 0.88→0.84 was second-order noise).
   - **v1-vs-v2 (scratch logging-only v1 build — `BoA-v1ref` byte-clean; `GlidingAssayEvaluator` shadowed
     in /tmp):** v1 and v2 share the **identical** cycle rates, the load-gated `dissociateADP`, and the
     Guo–Guilford catch-slip law+params (code-verified). Measured during gliding (4 v1 seeds, 1424 bound-
     obs): **occupancy MATCHES** (v1 ATP 58.9 / ADP 37.4 vs v2 59.8 / 36.6); **assist-fraction v1 54.4 %
     vs v2 51.5 %** — a small ~3 pp difference (~2 SE; v1 seed-to-seed 51.6–58.2). Near the 50/50 balance a
     3 pp shift in assist-fraction maps to a meaningful net-force difference, consistent with the ~13 %
     net residual.
   - **The one port gap (reported, NOT fixed — adding it is a physics change, and self-consistency passed
     so the prompt's bound forbids it):** v1's `ckRelease` has a **break-force release** (detach when
     cross-bridge tension > `myosinBreakForce` 12 pN) that v2 lacks. But v2's tension exceeds 12 pN only
     **0.56 %** of bound-steps (mean 5.1, max 17.7 pN), and that tail is ~60 % *assist* — so shedding it
     would *lower* assist-fraction, the WRONG direction to explain v1's higher enrichment. Not the cause;
     a small faithfulness gap worth the planner's note.

   **⇒ The cycle + release law are FAITHFUL (self-consistent, occupancy matches, identical law); the
   residual is a genuine EMERGENT collective-coordination difference — v2's bound population is marginally
   less assist-enriched (≈51.5 % vs v1's ≈54.4 %) at matched cycle/occupancy/release-law, amplified near
   the 50/50 tug-of-war into the ~13 % net-glide gap.** Both static candidates (chain, cycle) are
   eliminated as *faithfulness* gaps. This is a finding to document, not tune. Planner decides whether to
   accept the ~0.87× residual or scope the break-force port-gap addition / a deeper emergent-coordination
   study. **No physics edits.**

   **6.3 — is the residual a discretization (integration-scheme) difference? dt-test CONFOUNDED; per-step
   force law FAITHFUL (so the residual is purely the scheme).** Measurement only (raw `RUN_LOGS/
   2026-06-14_4biv_dt_force.txt`).
   - **dt-convergence test — bailed (confound found before burning the run).** Plan: refine dt 1e-5→1e-6
     for both codes and watch the v2/v1 ratio (a first-order scheme difference shrinks ∝dt). Audit of the
     dt-dependences: the cross-bridge `myoSpring` is a real spring (dt-correct), cycle is rate·dt, Brownian
     √dt, Langevin force/γ·dt — all dt-correct; the fracMove family is fraction-per-step but is part of
     v1's model and was held fixed (NOT scaled — planner direction). **But the binding is geometric/
     deterministic (a motor in reach binds within ONE step ⇒ effective k_on ∝ 1/dt) in BOTH codes** — so
     refining dt is non-physical for binding: at 1e-6, avgBound triples (v2 4×1: 6.4→19.9) into a
     different over-bound regime (NET barely moves, 3.60→3.90). The test can't hold the gliding regime
     fixed, so it cannot cleanly isolate the scheme difference. (Notably v1's fixture avgBound ~7.6 is
     itself a dt=1e-5 artifact of the deterministic binding.) **Update (§6.6, 2026-06-15): a SECOND
     dt-dependent binding artifact was found + fixed — v2's rebind cooldown was a fixed step count (=dt)
     vs v1's fixed `myoRebindTime` time; now `ceil(myoRebindTime/dt)`, commit `f2402b2`, bit-identical at
     production dt. Any future dt-refinement must use this build.**
   - **Per-step cross-bridge force cross-check — FAITHFUL (the planner's chosen alternative).** Dumped v1's
     EXACT compute-time bound config from an instrumented `MyoFilLink.addForces` (scratch v1, CPU, byte-
     clean ref) and fed it into v2's `bondForces` (`-forcetest`). v2 reproduces v1's head-side **F8 vector
     to float32 precision** (Δ ≤0.15 % on the smallest component, ≤0.013 % on the dominant ones; forceMag
     5.39399e-12 vs 5.39361e-12). Code-level the law is term-by-term identical (F8 spring, F9/F10 align
     torques rest 90/120, attach point `segC+(arc−½len)·u`, head tip `c+½·0.020·u`, all constants equal),
     and 4b-ii already bit-validated v2's force gather. **⇒ v2 computes the same cross-bridge force as v1
     for an identical config; the residual is NOT in the force computation.**
   - **⇒ All per-step physics is faithful (chain stiffness, nucleotide cycle, cross-bridge force). The
     ~0.87× residual is the EMERGENT effect of the integration-SCHEME difference — v2's parallel SoA
     kernels apply one-step-stale forces vs v1's sequential OOP fresh forces (the force-vs-state timing) —
     on the chaotic many-body gliding dynamics.** A clean dt→0 confirmation that it vanishes in the
     continuum is blocked by the deterministic-binding confound, but per-step force identity establishes
     the residual is the scheme, not the physics. Documented finding; not tunable. **No physics edits.**

   **6.4 — which part of the scheme? the release-read reorder A/B (tested).** The one *reorderable* timing
   difference: v2's catch-slip release + ADP-gate read a ONE-STEP-STALE `forceDotFil` (release/cycle run
   before bond/register in v2's pipeline), whereas v1 explicitly reconciled this (`MyoFilLink.java:114`,
   2026-06-04) so `ckRelease` consumes the FRESH same-step force. Mechanism: a stale read mis-times the
   release of a motor whose load just flipped to resisting ⇒ v2 retains resisters v1 would shed ⇒ lower
   assist-fraction. Tested with a clean A/B (`GlidingHarness -freshread`: compute force + register BEFORE
   release/cycle; wang-hash RNG is keyed so the draws are identical — only the read-timing changes):
   - **Assist-fraction: +0.43 pp toward v1** (52.27 % → 52.70 %, all 3 seeds positive). The release-read
     lag IS a real, systematic contributor to the directedness — confirming the timing hypothesis.
   - **Net glide: unchanged on BOTH runners** — CPU 4×1 (n=6): 4.13 ± 0.23 → 4.03 ± 0.16, Δ −0.10 ± 0.28;
     **production GPU TaskGraph, full 14×2 box (n=6): 3.96 ± 0.18 → 3.93 ± 0.18, Δ −0.03 ± 0.25** — within
     noise both ways. The assist gain is offset by an avgBound increase (GPU 7.46 → 7.73; better-timed catch
     retains more motors ⇒ more drag — the §5 tug-of-war again). The GPU `buildPlan` was reordered too (the
     `-freshread` toggle now covers the device path; default still reproduces the baseline exactly,
     4.062/7.178).
   - **⇒ The reorder changes the MECHANISM (assist balance, +0.43 pp) but NOT the net residual** (robust
     within noise on both CPU and the production GPU path). So the release-read timing is a small piece, not
     the net driver: the ~0.87× residual is dominated by the broader emergent/chaotic decorrelation of the
     parallel scheme (and its avgBound–drag coupling), which reordering kernels cannot remove. The position
     integration is forward-Euler in BOTH (no Gauss-Seidel difference to reorder away), and float32
     op-ordering chaos is irreducible. `-freshread` is a faithful-to-v1 toggle (CPU step + GPU TaskGraph;
     default off) the planner may adopt for fidelity, but it does not close the gliding-velocity gap.
     **No physics edits.**

   **6.5 — Per-step operation-ORDER audit: the kinetic order is faithful; the prime suspect (binding
   geometry) is ELIMINATED; no un-toggled divergence of any consequence remains.** §6.4 reordered ONE
   timing (the release-read) and found it shifts the mechanism (+0.43 pp assist) but not the net. The
   open gap that conclusion left: was the release-read the *only* per-step order divergence, or are there
   others (the prime suspect being binding-detection timing / the newly-bound motor's initial strain,
   which `-freshread` never touched)? This audit enumerates **every** kinetic operation's within-step
   position and read-staleness in both codes, code-verified. **Survey only — no test was run** (the
   contingent test's bar was not met; see the ranking). v1ref byte-clean.

   **The reference is v1's GPU path** — the net-glide oracle of §2 is `BoxOfActin -r -gpu`, and the
   production v2 path is the GPU TaskGraph. v1 itself runs *two* slightly different per-step orders (CPU
   vs GPU); both are given below because §6.4's CPU A/B used the CPU runner and the net oracle is GPU.

   **Within-step order (code-verified):**
   - **v1 CPU** (`BoxOfActin.doLoop`): BIND (`motCollStart` 1357 → `checkFilSegCollision`) → cross-bridge
     FORCE + RELEASE (`stepStart` 1420 → `MyoFilLink.addForces`+`ckRelease`, release reads the FRESH
     same-step force, reconciled 2026-06-04 `MyoFilLink.java:114`) → MOVE (`moveStart` 1509) → CYCLE
     (`biochemStart` 1523 → `dissociateADP`, gate reads the `forceDotFilTrack` 10-window avg whose newest
     entry is this step's fresh force).
   - **v1 GPU** (the oracle): FORCE → RELEASE (deferred to `bridgeMotorForceWriteback`, fresh step-N
     force) → MOVE (`moveThings` 1467) → **BIND drained AFTER the move** (`drainBoundResults` 1477) →
     CYCLE (1523). So on GPU a new bind takes effect for step N+1, and its first force is computed in N+1
     (the documented 1-step bind lag); the bound set that the step-N force/release act on is last step's.
   - **v2 default** (`stepOrig`/`buildPlan` else): RELEASE (stale `forceDotFil`) → BIND → CYCLE (stale
     10-avg) → motor forces incl. bond (this-step nuc state) → MOVE motor → `registerForceDot` → filament
     forces+gather → MOVE filament. A new bind takes effect step N and its force is applied step N.
   - **v2 `-freshread`** (`stepFresh`/`buildPlan` if): motor+filament forces incl. bond (LAST-step nuc
     state) → gather → `registerForceDot` → RELEASE (fresh) → BIND → CYCLE (fresh 10-avg) → MOVE motor →
     MOVE filament. Bond runs *before* bind, so a new bind's first force is step N+1 — matching v1 GPU.

   **Operation-by-operation staleness, v2 vs the v1 GPU oracle:**

   | operation | reads | v1 GPU (oracle) | v2 default | v2 `-freshread` | divergence |
   |---|---|---|---|---|---|
   | binding detection (geometry) | filament end1/end2 | start-of-step (pre-move) pose | start-of-step pose | start-of-step pose | **none — FAITHFUL** |
   | bind-point arc | `numer/√denom` along seg | `alpha·√denom` (`MyoMotor:421`) | `numer/√denom` (`BindingDetectionSystem:284`) | same | **none — bit-identical formula** |
   | newly-bound first force | step it first contributes | N+1 (bind drained post-move) | N (bind before bond) | N+1 (bond before bind) | default early by 1; `-freshread` matches |
   | bond force law | F8/F9/F10 + rest angles | start-of-step pose, last-step nuc state | start-of-step pose, **this-step** nuc state | start-of-step pose, last-step nuc state | default uses this-step state; `-freshread` matches (§6.3 `-forcetest` already validated the law to float32) |
   | catch-slip RATE `k_off(F)` | `forceDotFil` | FRESH same-step | **one-step-STALE** | FRESH | default stale; `-freshread` matches |
   | release decision | same `forceDotFil` read | FRESH | one-step-STALE | FRESH | (same single read as the rate — not a separate gap) |
   | cycle ADP-gate | 10-window `forceDotHist` avg | FRESH (newest entry = this step) | one-step-STALE | FRESH | default stale; `-freshread` matches |
   | bind vs release order | within-step phase | release-before-effective-bind (bind drains post-move/release) | release-before-bind | release-before-bind | **none vs GPU** (a gap only vs v1 *CPU*, which binds before release) |
   | position integration | forces from start-of-step | forward-Euler / Jacobi | forward-Euler / Jacobi | forward-Euler / Jacobi | **none — identical (established §6.3)** |

   **Flagged divergences, ranked by plausible contribution to the ~2.5–3 pp assist deficit:**

   1. **The four read-staleness/timing items (release rate+decision, cycle gate, bond nuc-state,
      newly-bound first-force) — ALL bundled in the single `-freshread` toggle, already A/B-tested
      (§6.4).** v2's *default* differs from the v1 GPU oracle on exactly these four, and `-freshread`
      corrects all four together (it is precisely "compute force + register before release/cycle, integrate
      last," which simultaneously freshens the release read, freshens the cycle gate, reverts the bond to
      last-step nuc state, and pushes the new-bind first-force to N+1). Mechanism points the right way
      (stale read mis-times the cull of a just-flipped resister ⇒ v2 retains resisters ⇒ lower assist).
      **Empirical result (§6.4): +0.43 pp assist toward v1, net unchanged on BOTH runners** (GPU 14×2 n=6:
      3.96→3.93). So this divergence is real, is the dominant *order* effect, and is **already
      characterized and toggleable** — there is nothing new to test here.
   2. **Bind-before-release vs release-before-bind — a gap only against v1 CPU, NOT the GPU oracle, and
      mechanically negligible.** v1 CPU orders bind→force→release, so a just-bound resister can be culled
      in its *first* step on fresh force; v2 (both toggles) orders release-before-bind, so a new bind is
      never first-step-culled. Direction is correct (v1 CPU sheds resisting new-binds marginally faster ⇒
      higher assist). **But (a)** the production oracle is v1 *GPU*, which *also* defers binding past
      release/move (the 1-step bind lag) — so against the actual oracle this is **not a divergence at
      all**; and **(b)** the magnitude is a first-step-only effect: ≈(new-binds/step ~0.056)×P(resist
      ~0.47)×(differential per-step release prob ~5e-4) ⇒ a standing-assist shift on the order of **~0.02
      pp**, ~100× too small to matter. Fails the contingent-test bar (clean *and* mechanically material):
      it is clean vs CPU but immaterial, and absent vs GPU.
   3. **Bind-target tie-break (v1 first-reachable via the `if(onFil)return` collision guard vs v2
      nearest-reachable).** Real implementation difference, but only manifests when ≥2 segments are
      simultaneously within the motor's ~tens-of-nm reach (rare given ~0.27 µm segments), and the choice
      is **not correlated with the glide direction** — no systematic assist/resist bias. Non-directional ⇒
      cannot produce a systematic mean shift. Noted for completeness; not a faithfulness gap of consequence.

   **The prime suspect is ELIMINATED.** Binding detection in BOTH codes reads start-of-step (last-completed,
   pre-move) filament geometry, and the bind-point arc is the *identical* formula (`numer/√denom`). The
   newly-bound motor's initial cross-bridge strain is therefore set by the same-staleness filament position
   and the same arc in both — so binding feeds assist-fraction *identically*. `-freshread` "did not touch
   it" because it did not need to: it was already faithful. (The bond force *given* a bound config was
   already float32-validated in §6.3 via `-forcetest`; this audit closes the remaining piece — that the
   *bind site itself* is chosen identically.)

   **⇒ Conclusion (Outcome 2 — the order is faithful; the residual is not an order artifact).** Beyond the
   four items the now-toggleable `-freshread` already corrects, there is **no per-step kinetic-operation
   order divergence of any consequence** between v2 and the v1 GPU oracle: binding geometry and bind-point
   are bit-faithful, position integration is identical (forward-Euler/Jacobi in both, §6.3), and the bond
   force law is float32-faithful (§6.3). The one un-toggled order difference (bind-vs-release) is absent
   against the GPU oracle and ~0.02 pp even against v1 CPU. This code-level audit **confirms the §6.4
   empirical result from first principles**: making v2's order fully faithful (via `-freshread`) recovers
   part of the assist balance but does not move the net — because the order was already faithful in every
   *consequential* respect, and the staleness items it corrects are second-order.

   Combined with the two facts the planner established — **position integration is identical** and **float32
   op-order chaos decorrelates the microstate without shifting the mean** — a systematic mean residual
   **cannot originate in anything modeled as faithful**: not the integration, not the chaos, not the
   binding, not the force law, and not the (now-faithful, toggleable) kinetic read-order. There is no
   remaining systematic-order mechanism to which the ~0.87× net / ~2.5–3 pp assist gap can be attributed.

   **⇒ This points to the residual lying within v1's own true ensemble uncertainty rather than being a real
   systematic gap.** The ~3–4 σ pooled significance (§3c) is computed against the SEM of short-run
   ensembles (n=8/16); v1's assist-fraction alone spans 51.6–58.2 % seed-to-seed (§6.2), and v2's 51.5 %
   sits at the low edge of that band. The honest closing step — **the planner's call, NOT run here** — is a
   **variance characterization**: does v2's net sit inside v1's *true long-window* spread (one long run, or
   a large matched-seed ensemble at fixed box), as opposed to the short-window SEM the σ-counting used? If
   yes, "robust within v1's ensemble" is the earned conclusion and "emergent" can be retired as the framing.
   If v2 falls cleanly outside v1's true spread, then — since this audit has exhausted the order channel —
   the cause would have to lie in something currently *believed* faithful (a hidden physics/constant gap),
   reopening §6.1a/§6.2 rather than the scheme. **No test run, no physics edits, no reorder committed** (the
   only faithful reorder candidate, the release-read, is already the committed `-freshread` toggle).

   **6.6 — Rebind refractory: dt-fix committed; cleared as the DIRECTEDNESS cause; a partial, favorable
   NET contributor flagged for a rate-faithful follow-up.** The §6.5 audit left one mechanism explicitly
   out of scope: the post-release rebind refractory (the minimum time a head waits before it may rebind).
   Verified this session: **v1** holds a fixed *time* `Env.myoRebindTime=1e-5 s` via a **static class-global**
   `bindTimer` (reset on release `MyoFilLink.java:315`, gated in `ontoFilament` `MyoMotor.java:455`);
   **v2** held a fixed *step count* (one `FREE_COOLDOWN` step). Two differences fell out: **(A) dt-scaling**
   (v2's duration = dt vs v1's fixed time — coincide at every production dt, diverge at dt≤1e-6) and
   **(B) refractory strength/character at fixed dt** (v2's clean 100%/1-step block vs v1's racy-global).

   **Phase A — dt-correct cooldown (committed, faithfulness fix).** Replaced the hardcoded one-step block
   with a per-motor counter set to `ceil(myoRebindTime/dt)` steps (`MotorStore.cooldown`; driven in
   `catchSlipRelease`), using v1's existing constant (no new rate/law/constant). Covers the CPU step + the
   GPU TaskGraph. **Gate PASSED:** at dt=1e-5 `ceil=1`, so it reproduces the baseline **bit-identically**
   (git-stash A/B, `-v1box -grid -seed 1 2000`, both runners: `GRID_ROW` identical — inst=6.042 netXY=2.928
   avgB=6.286). Closes the latent dt artifact (the 2nd dt-dependent binding artifact alongside §6.3's
   geometric-binding `k_on∝1/dt`). Commit `f2402b2`. Also added `-norefractory` (default off) for the bracket.

   **Phase B1 — v1's effective block rate** (scratch logging-only v1, `BoA-v1ref` byte-clean; `ontoFilament`
   instrumented to count would-be rebinds refused; fires on the GPU drain too via `GPUMotorBinding.java:1834`):

   | v1 path / box | effective block rate |
   |---|---|
   | GPU 14×2 (the net-glide **oracle**) | 0.317, 0.321, 0.303 → **0.31** |
   | GPU 4×1 | 0.309…0.328 → **0.31** (box-independent) |
   | **CPU 4×1** | **0.000** (0 of 748–1002 candidates) |

   So v1 is **mid-bracket on GPU (~31 %)** and **0 % on CPU** — the racy static-global makes v1's *own*
   refractory **path-dependent**. (The earlier "near-absent" guess was wrong for the GPU oracle.)

   **Phase B2 — v2 ON↔OFF bracket** (n=6, full 14×2; ON = current Phase-A block, OFF = `-norefractory`):

   | runner | net ON | net OFF | net swing | assist ON | assist OFF | **assist swing** | avgB ON→OFF |
   |---|---|---|---|---|---|---|---|
   | CPU (`-diag`) | 3.899 | 4.041 | +0.142 | 52.42 % | 52.45 % | **−0.03 pp** | 7.68→7.64 |
   | GPU (`-grid`) | 3.960 | 4.202 | +0.243 | — | — | — | 7.46→7.80 |

   Per-seed assist is **invariant** ON↔OFF (52.6/52.3/52.2/52.7/52.5/52.2 vs 52.7/52.1/52.8/52.4/52.4/52.3).
   The 1-seed v1box probe (ON>OFF) was noise; at the production box **OFF>ON** consistently — relaxing the
   refractory **raises net via avgBound** (binding *quantity*), and leaves **assist (directedness) untouched**.

   **Phase C — v1's own CPU-vs-GPU calibration** (4×1 matched box, the natural order-sensitivity scale):

   | v1 path | net | avgB | block |
   |---|---|---|---|
   | CPU 4×1 | **4.76** (n=5) | 7.19 | 0 % |
   | GPU 4×1 | **4.57** (n=6) | 6.97 | 31 % |

   v1's two paths differ by **~0.19 µm/s (~4 %)** in net, same direction as v2 (less block → higher net).
   So ~4 % is v1's intrinsic CPU-vs-GPU implementation/order-sensitivity for this mechanism.

   **Verdict.**
   1. **Directedness (the clean signal) — refractory CLEARED.** The assist-fraction ON↔OFF swing is
      **−0.03 pp**, ≪ the 2.5–3 pp gap, per-seed and on both runners. The ~50/50 tug-of-war / directedness
      deficit of §5/§6.5 is **not** the refractory. (The refractory acts on binding *quantity*, not the
      per-bound-motor *directedness*.)
   2. **Net — a partial, favorable, NOT-fully-closing contributor.** The refractory moves net via avgBound
      (swing +0.14 CPU / +0.24 GPU, OFF>ON). v1's oracle rate (31 %) is **more relaxed** than v2's Phase-A
      block (100 %/1-step), so a rate-faithful v2 would gain ~+0.1–0.17 µm/s net **toward** v1 — i.e. the
      current v2 refractory is slightly *too strong* and mildly *depresses* v2's net, making the §2 0.87×
      a touch conservative. But that closure is via **over-binding** (avgB rises past v1's), not by fixing
      directedness, so it shifts the net *number* without resolving the underlying deficit.
   3. **Scale check (Phase C).** v1's own CPU↔GPU net spread is ~4 %; the v2 net residual (~9–16 %) is
      several × larger, but its **refractory-attributable part (~4–6 %) is the same order** as v1's intrinsic
      path-sensitivity — i.e. that part of the gap is within v1's own implementation noise.
   4. **Flagged follow-up (NOT implemented — physics-of-rate change deferred per the prompt):** a
      **probabilistic block matching the v1-GPU oracle's ~31 % effective rate** (or a fractional
      `myoRebindTime`) is the rate-faithful refractory fix; it would raise v2 net partway toward v1 without
      touching assist. Caveat: v1's rate is itself **path-dependent** (0 % CPU / 31 % GPU), so "the faithful
      rate" = the **oracle (GPU) 31 %**. Phase A is dt-correct but deliberately still 100 %/1-step (the
      rate-match is the separate, flagged step).

   **⇒ The last un-cleared same-dt mechanism is now characterized: cleared as the cause of the directedness
   residual, and only a partial/favorable contributor to the net number (within v1's own ~4 % CPU-GPU
   spread for the closeable part).** Combined with §6.5 (kinetic order faithful) this leaves **no same-dt
   mechanism that explains the directedness deficit** — so the closer remains the **variance
   characterization** (does v2's net/assist sit within v1's *true* long-window ensemble spread; note v1's
   own CPU-vs-GPU paths already differ ~4 % in net), with the rate-faithful refractory as an independent,
   net-favorable fidelity improvement to fold in. Raw: `RUN_LOGS/2026-06-15_4biv_refractory.txt`.

   **6.7 — Variance characterization (the closer): the residual is OUTSIDE v1's honest envelope, small
   (~1.2× v1's seed-spread), and localized to PRECISION/LOGIC — NOT the parallel reduction.** The honest
   close to the §6.5/§6.6 chain (all same-dt mechanisms cleared as the directedness cause): does v2's net
   sit inside v1's *true* run-to-run spread, measured with autocorrelation-corrected, stationarity-checked
   ensembles rather than the short-run SEM the earlier ~3–4 σ used? Four configs — **v1-CPU, v1-GPU, v2-CPU
   runner, v2-GPU TaskGraph** — at the full 14×2 box (dt=1e-5, d=500/µm²), 10k steps (0.1 s), measurement
   only (existing instruments: v2 `-ztrace`/`-grid`, v1 `gliding_assay.dat`). Clean machine, serial (no
   concurrent sims). Primary statistic: **post-transient net-x glide** (t>0.02 s, the −x displacement
   rate), computed identically for all four. Raw: `RUN_LOGS/2026-06-15_variance/` (`variance_results.txt`,
   per-seed `.dat`/ztrace, `report.py`).

   **The four distributions** (post-transient net-x µm/s; SD = the true run-to-run envelope):

   | config | n | net-x mean | SD (envelope) | seed-SEM | assist-frac | avgB |
   |---|---|---|---|---|---|---|
   | v2-GPU TaskGraph | 24 | **4.000** | 0.408 | 0.083 | 52.84 % ± 1.96 | 7.37 |
   | v2-CPU runner    | 16 | **4.037** | 0.600 | 0.150 | 52.23 % ± 2.01 | 7.61 |
   | v1-GPU (oracle)  | 16 | **4.578** | 0.472 | 0.118 | — (§6.2 n=4: 54.4) | 6.58 |
   | v1-CPU           | 15 | **4.581** | 0.567 | 0.146 | — (§6.2 n=4: 54.4) | 7.35 |

   (v1-CPU seed 9 excluded: its gliding filament's assay output terminated at ~0.05 s on the CPU path,
   reproducibly — no full-window net; the GPU path of the same seed completed at net 5.3. n=15.)

   **Honest uncertainty — autocorrelation / effective N.** Within each run the per-interval glide velocity
   decorrelates in **τ ≈ 0.8–0.9 ms** (Sokal-windowed integrated ACF + batch-means), **≪ the 80 ms
   post-transient window** ⇒ ~57–64 effective samples per run. Because τ ≪ window, the runs ARE as
   independent as n suggests at the run level: the **effective-N-corrected pooled SEM (σ_v/√(n·N_eff))
   equals the naïve seed-SEM (SD/√n) to ≈0.12** for every config — so the seed-SEM is honest, not inflated
   by within-run correlation. (Conversely, a *single* 0.1 s run pins net only to ±0.6 — eff-N ≈ 57, σ_v ≈
   4 — so the seed-to-seed SD is dominated by finite-window chaotic sampling noise, and the **ensemble of
   short runs is the real averaging**; one long run cannot answer this. The pilot 30k run confirmed even
   0.3 s does not self-average.) Stationarity: assist/avgB flat across the run (no progressive
   disengagement, per §6.1); startup transient (first 2000 steps) excluded.

   **Mean stability.** Running mean vs seed count plateaus by n≈10 for all four (v2-GPU 3.95→3.92, v1-GPU
   4.83→4.55, v1-CPU →4.58, v2-CPU →4.03). Mean vs window length (long 30k runs): both codes' net rises
   slightly then plateaus ~4.2–4.3 by 0.2–0.3 s (v2-GPU-long n=3; v1-GPU-long n=1) with the ratio
   ≈constant — the 0.1 s ensemble means are representative, not a window artifact.

   **The three comparisons** (difference of means ± combined seed-SEM):

   | comparison | means | Δnet (µm/s) | σ | ratio |
   |---|---|---|---|---|
   | **v2-GPU vs v1-GPU** (production) | 4.000 vs 4.578 | **−0.578 ± 0.145** | **−4.0** | **0.874** |
   | **v2-CPU vs v1-CPU** (both sequential) | 4.037 vs 4.581 | −0.544 ± 0.210 | −2.6 | 0.881 |
   | v1-GPU vs v1-CPU (v1 self-spread) | 4.578 vs 4.581 | −0.003 ± 0.188 | −0.0 | 0.999 |
   | v2-GPU vs v2-CPU (v2 self-spread) | 4.000 vs 4.037 | −0.037 ± 0.172 | −0.2 | 0.991 |
   | **pooled** (v2 n=40 vs v1 n=31) | 4.015 vs 4.579 | −0.565 ± 0.120 | **−4.7** | **0.877** |

   **Verdict — OUTSIDE, but small.** The production gap is **−4.0 σ** (pooled −4.7 σ), a *real,
   statistically-resolved systematic mean difference* — it does **not** dissolve into v1's run-to-run
   variability when that variability is measured honestly (autocorrelation-corrected). Ratio **0.874–0.877**,
   exactly reproducing §2's n=8 full-run 0.873 — but now at n=24/16 with eff-N-honest SEM. **Magnitude:
   ≈1.2× v1's seed-to-seed SD** (0.578/0.472), ~13 %. The per-run distributions overlap heavily (22/24
   v2-GPU runs fall inside v1-GPU's seed range 3.54–5.44; a single v2 run is indistinguishable from a single
   v1 run) — the residual is a *mean shift*, not run-to-run noise. Note the §6.5/§6.6 premise that "v1
   disagrees with itself ~4 %" was a **4×1** finding: at the production **14×2** box v1's CPU and GPU paths
   agree to **0.003 (0.0 σ)** — the path-spread is box-dependent and ≈0 here, so by that yardstick the gap
   is many-σ outside.

   **Localization read — precision/logic, NOT the parallel reduction.** The decisive new result:
   - production gap (v1-GPU − v2-GPU) = **+0.578**; sequential gap (v1-CPU − v2-CPU) = **+0.544**.
   - **parallel-reduction excess = gap_GPU − gap_CPU = +0.033 (≈0).** The full residual is present on the
     *sequential* CPU-vs-CPU comparison at the same size; v2's own CPU≡GPU (Δ0.037) and v1's own CPU≡GPU
     (Δ0.003) at 14×2.
   - ⇒ Per the prompt's localization rule — *"if the gap in v2-CPU-vs-v1-CPU is essentially the full
     residual, the parallel reduction is NOT the cause (precision or logic is)"* — the **GPU parallel
     reduction is exonerated.** The ~13 % residual is carried by **float32 precision (v2) vs float64 (v1)**,
     and/or a residual **logic** difference (the §6.3 one-step-stale-force scheme is float32-faithful
     per-step and §6.4 showed reordering it doesn't move the net, leaving precision as the prime suspect).

   **Assist (directedness, mechanism).** v2 assist-fraction is tight and reproducible across runners: **52.8 %
   (GPU, n=24) / 52.2 % (CPU, n=16)**, SD ≈2 pp. v1 assist is only the §6.2 n=4 measurement (54.4 %, range
   **51.6–58.2 %**) — not re-measured here (it needs new v1 instrumentation + a sign-convention match; out of
   "existing instruments" scope, and the NET comparison already carries the verdict). Read together: the
   ~2–3 pp assist gap that drives the ~13 % net (§5's near-50/50 amplification) is **within** v1's own n=4
   assist seed-spread (6.6 pp), even though the *net* gap is resolved — consistent with assist being the
   noisier per-seed quantity and net the integrated one. (A clean follow-up — v1 assist at n≥16 — would
   tighten this, but does not change the net verdict.)

   **⇒ Bottom line (6.7).** The directedness/net residual is **OUTSIDE v1's honest envelope** — a real
   ~0.87× (−13 %) systematic mean difference at −4.0 σ (pooled −4.7 σ), **≈1.2× v1's seed-to-seed SD** —
   and it is **localized to precision/logic, NOT the GPU parallel reduction** (identical on the sequential
   CPU-vs-CPU path; v1 & v2 each CPU≡GPU at 14×2). This **closes the variance question** (the gap is not a
   short-run-SEM artifact) and **selects the contingent next step: the numerical-match discriminator** —
   push v2's numerics toward v1's (a double-precision `-cpu` variant, or a targeted double-accumulate),
   *not* a sequential-reduction-order test (the reduction is already exonerated). If the gap closes under
   double precision it is the deliberate float32 GPU tradeoff (document); if it persists, a hidden
   logic/constant difference remains and §6.1a/§6.2 reopen. **No physics edits.**

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
| **Gliding NET vs v1 — variance characterization (§6.7, n=24/16/16/15)** | ◑ **OUTSIDE v1's envelope**: 0.877× at −4.7 σ (pooled), ≈1.2× v1's seed-SD; **localized to precision/logic, NOT the parallel reduction** (CPU≡GPU within each code; gap full-size on v2-CPU-vs-v1-CPU) |

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
