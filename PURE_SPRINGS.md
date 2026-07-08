# Pure-spring reformulation — every fraction-per-step constraint as a real linear/torsional spring; does it change engagement (avgBound) at production dt?

**Date:** 2026-07-08. Continuation of the 2026-07-08 `-allnoise`-dead / gliding-re-convergence arc (JOURNAL).
New code path only (`-pairsprings -alignsprings -structsprings`, ported+extended in the current
`dt-convergence-study` working tree); **default byte-identical**; `BoA-v1ref` untouched; no promotion; no
concurrent production on aorus. float32; race-free (build-time scalar coeff substitutions into UNCHANGED
kernels + one additive sentinel branch in the swing kernel).

---

## PLAIN ANSWER (headline — read this first)

**No. The pure-spring formulation does NOT change avgBound at production dt=1e-5 — and it CANNOT, by
construction.** At the production timestep the three formulations of every constraint —

- **raw** fraction-per-step (`Δ=frac·gap`),
- **fixed spring** `springify(frac)=frac·(DT/refDt)` (the new `-pairsprings/-alignsprings/-structsprings`), and
- **geometric rate** `rateFix(frac)=1−(1−frac)^(DT/refDt)` (the current `-ratefix/-structrate`)

— are **coefficient-identical** (bit-identical floats — verified directly, STEP 1), because `refDt =
production dt = 1e-5`, so `DT/refDt = 1.0` exactly and both `springify(k)=k` and `rateFix(k)=k`. They are all
three *defined to coincide at refDt* and only diverge at finer dt. Measured on the **basin-stable CPU arbiter**
(`-full`, 26740 motors, dt=1e-5, seed 0, 20k steps): raw ≡ pure-springs ≡ `-ratefix -structrate` are
**bit-identical to every printed digit** — velFitX 3.264 = 3.264 = 3.264, **avgBsteady 3.307 = 3.307 = 3.307**,
netX −2.956 (STEP 3 CPU below; the 500-step STEP-1 parity check gave the same three-way bit-identity).

**⇒ The production-dt engagement (avgBound) deficit is formulation-INDEPENDENT.** It is not driven by the
constraint formulation (spring vs rate vs raw), because at production dt there is only ONE formulation. The
deficit lives elsewhere — kinetics / capture geometry (the JOURNAL 2026-07-08 read: 1e-5 binds ~27% fewer
heads than the fine-dt limit), NOT in how the constraints are frozen. The pure-spring reformulation gives the
model a cleaner, honestly-fixed-stiffness foundation **for free** (it reproduces production exactly and only
changes behavior below refDt), but it does not explain — and is not a lever on — the engagement deficit.

This is a **structural null**, not a small measured null: the answer follows from the definition of the
freeze-forms and is confirmed bit-for-bit on the arbiter. Any nonzero avgBound difference seen between these
arms on the **GPU** at production dt is therefore a **basin flip** (a last-bit TaskGraph-scheduling
perturbation tipping the bistable glide state — the 2026-07-08 `-allnoise` lesson), NOT a formulation effect.

---

## STEP 1 — the full pure-spring set + production-dt parity

### The spring set (which modes, replace-not-compose, `k` values)

Every glide-relevant fraction-per-step constraint is now freezable as a fixed spring, mirroring the existing
`-ratefix`/`-structrate` rate conversions but with `springify` instead of `rateFix`:

| flag | modes | slots | `k` at 1e-5 | `k` at 1e-6 |
|---|---|---|---|---|
| `-pairsprings` | filament F3 link + bending, F4 torsion | chainParams[1]=0.5, [3]=0.2 | 0.5 / 0.2 | 0.05 / 0.02 |
| `-alignsprings` | motor F9/F10/axlock align + directedSwing stroke | xbParams[2]=0.4, swingParams[4]=−refDt | 0.4 | 0.04 |
| `-structsprings` (NEW) | J1/J2 connection + tail-anchor position springs | jointParams[1]/[5]/[9]=0.4 | 0.4 | 0.04 |

`springify(k)=k·(DT/refDt)` fed into the UNCHANGED kernel `F=k_code·gap/((moveC1+moveC2)·dt)`: the running dt
cancels to refDt ⇒ a fixed stiffness `k_spring=k·γ_red/refDt` (γ_red=1/(moveC1+moveC2), supplied per-pair
in-kernel ⇒ pinned bodies honoured), integrated by the same forward-Euler. `-structsprings` is the one new
flag (mirrors the `-structrate` block; precedence over it). The swing uses the pre-existing `swingParams[4]`
sentinel: `<0` ⇒ the fixed-spring branch `k←k·(dt/|refDt|)` (one additive `else if` in
`CrossBridgeSystem.directedSwing`/`directedSwingHeadFrame`).

**Replace-NOT-compose (bail condition #2 — NOT triggered).** Each spring flag takes precedence over its
matching rate flag for the SAME slot via `PAIRS_SPRINGS ? springify : (FIL_RATE ? rateFix : raw)`. In the
pure-spring arm the rate flags are OFF, so every converted mode gets the spring form and nothing else; in the
current arm the spring flags are OFF. No mode ever receives both a spring and a rate-converted update
(verified by reading each ternary — the flags-off branch of each is the verbatim original expression).

### Production-dt parity (MEASURED, decisive)

At dt=1e-5=refDt, `DT/STROKE_REF_DT=1.0` exactly ⇒ `springify(k)=k` (a float multiply by 1.0, exact) and the
swing sentinel collapses to `k·1.0=k`. So the pure-spring set reproduces the raw law **bit-for-bit**, and the
rate set does too. CPU runner (deterministic), `-full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10
-density 1000 -dt 1e-5 -seed 0`, 500 steps:

| arm | velFitX | inst | netX | avgBsteady |
|---|---|---|---|---|
| raw (no flags) | 8.225 | 7.063 | −6.341 | 4.000 |
| **pure-springs** `-pairsprings -alignsprings -structsprings` | **8.225** | **7.063** | **−6.341** | **4.000** |
| current `-ratefix -structrate` | 8.225 | 7.063 | −6.341 | 4.000 |

**Every digit identical.** Default byte-identical (all spring flags off ≡ HEAD — textually guaranteed: each
flags-off ternary branch is the verbatim original) AND the per-step motion of each converted mode matches the
fraction-per-step move exactly at production dt (the parity the task asked to confirm).

**The build-time coefficients are bit-identical floats across all three freeze-forms** (computed directly,
double `Math.exp/log` → float):

| k | raw | `rateFix(k)` @1e-5 | `springify(k)` @1e-5 |
|---|---|---|---|
| 0.5 | 0x3f000000 | 0x3f000000 | 0x3f000000 |
| 0.2 | 0x3e4ccccd | 0x3e4ccccd | 0x3e4ccccd |
| 0.4 | 0x3ecccccd | 0x3ecccccd | 0x3ecccccd |

So `filFracMove`/`filFracMoveTorq` (chainParams) and `alignK` (xbParams[2]) — all baked host-side — are
literally the same bits in every arm. The **one** coefficient recomputed IN-KERNEL each step is the swing k
(`swingParams[0]=0.4` base, recomputed if `swingParams.size>4`): raw (size 4) skips it (k=0.4f); pure-springs
(size 5, [4]<0) computes `k=0.4·(dt/|refDt|)=0.4·1.0` — an **exact multiply** ⇒ 0.4f bit-identical to raw;
`-ratefix` (size 5, [4]>0) computes `k=1−exp((dt/refDt)·log(0.6))` — a **transcendental** that on the GPU's
float-precision `exp/log` need not land exactly on 0.4f. **This is the whole difference between the arms at
production dt: a single in-kernel exp/log in the ratefix swing, whose GPU-float ULP is the basin-flip seed
(STEP 3 GPU).** On the CPU (double `exp/log`, exact 0.4) it vanishes.

---

## STEP 2 — powerstroke-force preservation

The swing/alignment are **driven, force-generating** modes; converting them to springs preserves the per-step
motion at refDt but could change the force profile. Since the *entire* production-dt dynamics is bit-identical
(STEP 1), the powerstroke force is preserved **exactly** at production dt. Confirmed on the force channel via
`-stretchcensus` (per-head axial load `fdFilPN` = the unitary/stall force delivered to the filament), CPU,
`-full`, dt=1e-5, seed 0, 4000 steps:

| arm | boundSamples | ext (nm, mean±sd) | **fdFilPN mean / |mean|** |
|---|---|---|---|
| raw | 116 | 3.828 ± 1.854 | **0.709 / 2.097** |
| pure-springs | 116 | 3.828 ± 1.854 | **0.709 / 2.097** |
| current `-ratefix -structrate` | 116 | 3.828 ± 1.854 | 0.709 / 2.097 |

(all three bit-identical, as STEP 1 guarantees)

**Verdict: PRESERVED (exact/bit-identical at production dt).** The pure-spring powerstroke delivers the same
per-head axial force as the current formulation — the conversion is a no-op at refDt. The force profiles only
diverge below refDt, where `springify` gives a proportionally softer per-step relaxation than `rateFix` (the
continuum factor `−ln(1−k)/k`; DT_AUDIT_AND_SPRINGS Part B measured this alignment-only at 1.25e-6:
per-bound null, absolute engagement ~11–15% softer under springs).

---

## STEP 3 — engagement comparison at production dt (CPU arbiter + GPU)

Config: dt=1e-5, `-full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000`, 60k steps.
per-bound = velFitX / avgBsteady.

### CPU arbiter (basin-stable, deterministic) — dt=1e-5, `-full` 26740 motors, 20k steps, seed 0

| arm | velFitX | inst | netX | **avgBsteady** |
|---|---|---|---|---|
| raw | 3.264 | 6.878 | −2.956 | **3.307** |
| **pure-springs** `-pairsprings -alignsprings -structsprings` | **3.264** | **6.878** | **−2.956** | **3.307** |
| current `-ratefix -structrate` | 3.264 | 6.878 | −2.956 | 3.307 |

**All three bit-identical to every digit** over the full 20k-step chaotic run. On the basin-stable CPU arbiter
the pure-spring formulation produces **exactly** the same avgBound (3.307) as the current formulation — and so
does raw. **Arbiter verdict: engagement (avgBound) is formulation-INDEPENDENT at production dt.** (Note even
`-ratefix`'s in-kernel swing `exp/log`, computed in double on the CPU, lands bit-identical to raw here — the
double roundtrip hits raw's k exactly; only the GPU's float `exp/log` ULP differs.)

### GPU (production scale, 60k steps, 3 seeds — the basin-flip check)

velFitX / avgBsteady / (per-bound). raw and pure-springs GRID_ROWs are **bit-identical** every seed (same
velFitX, same avgBsteady, same boundSteps/releases in STATS — literally the same run):

| seed | raw | pure-springs | current `-ratefix -structrate` |
|---|---|---|---|
| 0 | 2.895 / 3.332 (0.869) | **2.895 / 3.332 (0.869)** | 2.112 / 2.870 (0.736) |
| 1 | 2.718 / 3.103 (0.876) | **2.718 / 3.103 (0.876)** | 2.338 / 2.957 (0.791) |
| 2 | 2.861 / 3.515 (0.814) | **2.861 / 3.515 (0.814)** | 2.525 / 3.173 (0.796) |
| mean | 2.825 / 3.317 (0.852) | **2.825 / 3.317 (0.852)** | 2.325 / 3.000 (0.774) |

**Two facts:** (1) **pure-springs ≡ raw bit-identical on the GPU too**, every seed — a genuine formulation
change (fixed springs) that lands on exactly the raw numbers, because `springify` is an exact ×1.0. (2)
**`-ratefix -structrate` sits systematically in a lower basin** (velFitX −18%, avgB −10%, per-bound 0.774 vs
0.852) across all 3 seeds — this is the established prior baseline (velFitX 2.112 / per-bound 0.736 at seed 0
reproduces every `-allnoise`/`-thermcorr` table).

**Reconcile:** the ratefix arm's ONLY difference from raw at production dt is the in-kernel swing `exp/log`
(build-time coefficients bit-identical, STEP 1); on the GPU that transcendental's float-ULP is a last-bit
perturbation, and the gliding steady state here is **bistable** (JOURNAL 2026-07-08), so it tips ratefix into
the lower basin — **a basin flip, not a formulation effect.** The decisive tell: the actual formulation change
(springs) does NOT move off raw, while only the rate-machinery's transcendental path does. The **CPU arbiter
(above) settles it**: all three arms are bit-identical on the basin-stable CPU, so the GPU ratefix split IS a
basin flip.

**Reproducibility exposure (surfaced, worth flagging).** The GPU raw/springs arms sit in the HIGH basin
(velFitX ~2.8, per-bound ~0.85); GPU `-ratefix` sits in the LOW basin (velFitX ~2.3, per-bound ~0.77). The
prior gliding baseline — velFitX 2.112 / per-bound 0.736 — reproduced in every `-allnoise`/`-thermcorr`/
re-convergence table, was always measured **with `-ratefix`**, i.e. it was the ratefix-seeded LOW basin, not
the basin-stable value. (Exact confirmation: my GPU ratefix per-bound across seeds [0.736 / 0.791 / 0.796]
matches the JOURNAL 2026-07-08 `-allnoise`-diagnosis OFF baseline [0.736 / 0.791 / 0.796] bit-for-bit — the
prior "baseline" was this arm.) The CPU-deterministic value (velFitX 3.264, avgB 3.307, this window) and the GPU
raw/springs runs land in the HIGH basin. This extends the JOURNAL 2026-07-08 bistability note: on the GPU the
gliding baseline's basin is decided by a last-bit scheduling perturbation, and `-ratefix` vs raw pick different
basins there (though they are physically identical, as the CPU proves). Any GPU gliding A/B whose arms differ
in whether `-ratefix` (or any transcendental-bearing flag) is present is contaminated by this; trust the CPU.

---

## STEP 4 — the moved limit (one fine-dt point, CPU v1box = basin-stable arbiter)

At dt=1e-6 the freeze-forms genuinely diverge (`springify(0.4)=0.04` vs `rateFix(0.4)=0.0491` vs raw frozen at
0.4). Run on the CPU v1box (4000 motors, basin-stable ⇒ no GPU-bistability confound), matched sim-time 0.06 s,
seed 0.

velFitX / avgBsteady, per-bound = velFitX/avgBsteady:

| dt | raw (frozen frac) | pure-springs (fixed k) | current (geometric rate) |
|---|---|---|---|
| 1e-5 (anchor) | 2.072 / 3.355 (0.618) | ≡ raw (bit-identical) | ≡ raw (bit-identical) |
| 1e-6 | 9.094 / 3.558 (2.556) | **2.542 / 4.455 (0.571)** | **3.148 / 5.073 (0.621)** |

**Read (three-way divergence at 1e-6, on the basin-stable CPU arbiter):**
1. **raw FREEZES high.** velFitX inflates **4.4×** (2.072→9.094) from 1e-5 to 1e-6 while avgBound barely moves
   — the fraction-per-step skeleton stiffens ∝1/dt, over-reacting the stroke against a now-rigid anchor ⇒
   inflated per-bound glide (2.556). This is the **frozen-skeleton artifact** the JOURNAL 2026-07-08
   re-convergence identified — raw is NOT a valid fine-dt reference.
2. **springs and ratefix are dt-convergent and sit far below raw** (velFitX 2.5–3.1 vs 9.1), and **differ from
   each other by the expected `−ln(1−k)/k` continuum factor**: pure-springs is the softer freeze (velFitX
   2.542 ≈ 0.81× ratefix's 3.148; avgB 4.455 ≈ 0.88× ratefix's 5.073). This is the **moved limit** — a REAL
   formulation difference (measured on the basin-stable CPU, so NOT a basin flip), and exactly the anticipated
   re-baseline, not a regression. Per-bound is closer (0.571 vs 0.621, ~8%), echoing DT_AUDIT_AND_SPRINGS Part
   B's per-bound-near-null / absolute-engagement-softer split, now for the FULL pure-spring set.
3. **The engagement deficit is dt-related and formulation-independent.** BOTH convergent arms bind MORE at 1e-6
   than at 1e-5 (avgBsteady 4.455 / 5.073 vs the 3.355 anchor = +33% / +51%), reproducing the JOURNAL
   "1e-5 under-binds ~27% vs the fine-dt limit" — and it appears identically under springs and rates, so the
   deficit is in **engagement/dt**, not in the constraint formulation.

---

## PLAIN FRAMING (what the model does with every constraint as a real spring)

With chain, alignment, and structural constraints ALL rebuilt as genuine fixed linear/torsional springs, the
model at **production dt is exactly what it always was** — the springs, the geometric rates, and the raw
fraction-per-step law are byte-identical there by construction (refDt = production dt). The reformulation is a
strict *below-refDt* change: it removes the frozen-skeleton artifact (raw stiffens ∝1/dt at fine dt) and
replaces it with an honest fixed stiffness, converging (forward-Euler) to a continuum limit that differs from
the geometric-rate limit only by the known `−ln(1−k)/k` factor.

**Does it explain the engagement deficit? No.** The ~27% production-dt avgBound deficit is
formulation-independent (STEP 1/3 CPU): all three constraint formulations coincide at 1e-5, so the deficit
cannot be a formulation artifact. Springs are the cleaner foundation, but the engagement lead is elsewhere
(kinetics / capture geometry), consistent with the 2026-07-08 re-convergence picture (the per-head efficiency
is nearly dt-honest at 1e-5; the gap is in engagement/absolute-glide).

---

## Reproduce
```
./build.sh
# STEP 1 parity (CPU, bit-identical at refDt):
./run_gliding.sh -full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000 -dt 1e-5 -seed 0 500
./run_gliding.sh -pairsprings -alignsprings -structsprings <same...> 500      # ≡ raw bit-identical
./run_gliding.sh -ratefix -structrate <same...> 500                           # ≡ raw bit-identical
# STEP 2 stroke force:  add -stretchcensus, 4000 steps  (run_puresprings_step2.sh)
# STEP 3 CPU arbiter (basin-stable, 20k, decisive):    run_puresprings_cpuarb.sh
# STEP 3 GPU 3-seed basin-flip check:                  run_puresprings_gpu.sh
# STEP 4 moved limit (CPU v1box, 1e-6):                run_puresprings_step4.sh
```
Files: `softbox/GlidingHarness.java` (+`springify`, `-pairsprings/-alignsprings/-structsprings`),
`softbox/CrossBridgeSystem.java` (+swing `refDt<0` fixed-spring branch). Logs:
`RUN_LOGS/2026-07-08_puresprings_*.txt`.
