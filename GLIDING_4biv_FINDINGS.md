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
     itself a dt=1e-5 artifact of the deterministic binding.)
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
   - **Net glide: unchanged** (4×1, n=6: 4.13 ± 0.23 → 4.03 ± 0.16, Δ −0.10 ± 0.28 — within noise), because
     the assist gain is offset by an avgBound increase (7.22 → 7.47; better-timed catch retains more motors
     ⇒ more drag — the §5 tug-of-war again).
   - **⇒ The reorder changes the MECHANISM (assist balance, +0.43 pp) but NOT the net residual** (robust
     within noise). So the release-read timing is a small piece, not the net driver: the ~0.87× residual is
     dominated by the broader emergent/chaotic decorrelation of the parallel scheme (and its avgBound–drag
     coupling), which reordering kernels cannot remove. The position integration is forward-Euler in BOTH
     (no Gauss-Seidel difference to reorder away), and float32 op-ordering chaos is irreducible. `-freshread`
     is a faithful-to-v1 toggle (CPU; default off) the planner may adopt for fidelity, but it does not close
     the gliding-velocity gap. **No physics edits.**
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
