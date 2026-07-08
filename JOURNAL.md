# Soft Box Project Journal

# 2026-07-08 — CANONICAL COLLAPSE STAGE 1: the ratified canonical set is now the DEFAULT (byte-identical verified; tag = rollback index)
Baked jba's ratified canonical gliding model as the sole/default path, gated on byte-identical acceptance (both
runners). **Rollback tag `pre-canonical-collapse-2026-07-08` (`5b60b0a`) pushed FIRST.** **State finding:** at the tag,
springs AND the sphere-head motor were ALREADY default-on — only `LYMN_TAYLOR` and `XB_IMPLICIT2` were genuinely
default-OFF, so this stage makes two real flips, one byte-identical refactor (sphere-head stack → explicit field
defaults, post-parse promotion block REMOVED), and one confirm-unchanged (springs). **Flips:** `LYMN_TAYLOR=true`,
`XB_IMPLICIT2=true`, `SPHEREHEAD/AXLOCK/DIRSWING=true`. **D1a weld:** `if (LYMN_TAYLOR && !ALLOW_BIND_ANY) ADPPI_BIND=true`
welds ADP·Pi-only binding into the canonical cycle (drives `kinParams[20]=1`). **Opt-outs added:** `-legacycycle`
(legacy catch-slip cycle), `-explicitxb` (explicit Hookean F8), `-allowbindany` (marked diagnostic — permissive binding
under the canonical cycle). PART-3 resolution: the cycle and the bind-gate are INDEPENDENT in the code, so `-legacycycle`
does NOT cleanly isolate permissive binding (it bundles the cycle swap) ⇒ `-allowbindany` was needed and added as the
clean counterfactual. **NO deletions/renames/param-changes/diagnostic-re-routing (all Stage 2). `BoA-v1ref` untouched;
only GlidingHarness.java edited.** **ACCEPTANCE — all PASS, byte-identical:** Test A (new bare default ≡ old-tag
`-lymntaylor -adppibind -xbimplicit2`): CPU velFitX 4.583 / GPU 4.584, new≡old to every digit on EACH runner (GPU
byte-identity ⇒ no kernel-graph difference introduced). Test B (new `-legacycycle -explicitxb -nosprings -legacymotor`
≡ old-tag `-nosprings -legacymotor`): CPU velFitX 13.057 identical. Test B2 (new `-legacycycle -explicitxb` ≡ old bare
default): CPU velFitX −2.605 identical. **The task's literal Test-B RHS (combo vs old BARE default) correctly differs —
springs+sphere were already-on defaults, not this stage's flips; documented, not a failure.** No BAIL. Report:
`CANONICAL_COLLAPSE_STAGE1.md`. **Stage 2 (deletions / rename `-faithfulrelease`→`-forcecapdetach`+`-detachcap` /
coltol-density reclassification / diagnostic-own-scene policy) awaits verification sign-off + planner scoping.**

# 2026-07-08 — CANONICAL MANIFEST: complete model-fork enumeration produced (READ-ONLY; collapse awaits jba's ratification)
Produced `CANONICAL_MANIFEST.md` — every physics/model fork in the gliding code, each with current default, code
site(s) that read it (file:line), alternatives, physical effect, and a ratification category (1 SETTLED-CANONICAL /
2 CHOICE-NOT-PROOF / 3 PARAMETER / 4 DEAD-ARTIFACT / 5 DIAGNOSTIC). Purpose: kill the silent-mis-mapping failure mode
(the way `-ratefix` LOW basin masqueraded as baseline) BEFORE any collapse to a single path. **No edits/deletions/
hardcoding this session; `BoA-v1ref` untouched.** **Headline finding — the bare code default ≠ the config jba treats
as canonical:** `run_gliding.sh` with no flags = sphere-head motor (promoted POST-PARSE at `GH:336–340`, not via field
defaults) + springs (default-on) + LEGACY catch-slip cycle + EXPLICIT cross-bridge; the SPRINGS_PROMOTION GATE-2 /
active dt-study "reference" config adds `-lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000` — a FOUR-FORK
gap, and Lymn-Taylor (jba's stated canonical kinetics) is DEFAULT-OFF. **CHOICE-NOT-PROOF forks flagged for explicit
decision:** (i) Lymn-Taylor+adppibind (canonical by framing, off in code); (ii) `-xbimplicit2` — GATE-2 uses it but
THESIS §11 banks EXPLICIT as the validation operating decision (direct contradiction); (iii) springs-continuum
calibration (freezes an un-retuned fine-dt limit, SPRINGS_PROMOTION §DEFERRED); (iv) sphere-head vs the phase-2
canonical two-point motor; (v) `-faithfulrelease` (own task, re-baselines avgBound). **Collapse-time divergence risks
listed:** 8 diagnostic modes build their OWN scene (`headTiltSweep`/`boundGeom`/`forceDecomp`/`stiffnessAngleSweep`/
`dCalib`/`catchSlipRecal`/`singleMolecule`/`swingKProbe`) with raw chainParams, bypassing the springs freeze. **Dead
paths to remove:** the noise-correction family (all inert/artifact), `-xbimplicit`/`-xbdash*`/`-xbsat`, `-atprecharge`/
`-tauavg`, the `-ratefix` rate machinery, `-freshread`, and the documented-negative recasts. Report ends with the
proposed sole-path set, the tunables to preserve, the dead list, and the 7 explicit decisions jba must make. **No
collapse this session.**

# 2026-07-08 — SPRINGS PROMOTED TO DEFAULT: the deterministic, transcendental-free canonical gliding formulation (gated, verified, jba re-baseline sign-off flagged)
Promoted the pure-springs formulation to the gliding default — the fix for the GPU reproducibility hazard
(BISTABILITY_ORIGIN: the -ratefix swing exp/log perturbs PTX scheduling → tips the bistable basin to LOW; springs
use a transcendental-free MULTIPLY swing, same production-dt physics). Gated on two gates, both PASS. **GATE 1
(hazard map, agent-assisted):** the ONLY flag-dependent hot-kernel transcendental in the raw/ratefix/springs toggle
is the swing exp/log (`directedSwing:237`); all other rate flags bake coeffs HOST-side (data, not kernel structure);
the default-config toggle adds/removes NO TaskGraph task (isolates the hazard to that one branch). **Springs
introduces ZERO flag-dependent hot-kernel transcendental** (swing→multiply `:241`; chain/align/struct coeffs
host-baked bit-identical) — CONFIRMED clean, it REMOVES the offending exp/log. Springs does NOT cover the OTHER
scheduling hazards (noise-correction -bondnoise/-allnoise/-thermcorr/-syswide add sqrt tasks; -xbimplicit*/-segimplicit/
-canonical/-config1/-lymntaylor/-tauavg/-freshread add/swap tasks) → those stay CPU-arbiter-gated. **GATE 2 (trust
test, GPU==CPU, -full 20k ×3 seeds):** GPU-springs mean velFitX 3.215/pb 1.089 vs CPU-springs 3.180/1.093 — **same
HIGH basin, ~1% aggregate agreement** (far within SEM), and **STABLE** (no NaN/blow-up across density 500/1000/2000,
coltol 6). **GATE 3 (standing rule):** added the "GPU-number trust rule" to CLAUDE.md (CPU-arbiter cross-check when
arms differ in hot-kernel structure / for absolute validation numbers / periodic baseline spot-check; GPU=explore,
CPU=basin arbiter) with the GATE-1 hazard-flag list. **PROMOTION APPLIED:** `PAIRS/ALIGN/STRUCT_SPRINGS` default
true; `-nosprings` opt-out restores raw (`-nosprings -ratefix -structrate` for the rate path); disclosures print
"SPRINGS DEFAULT-ON" + a redundant-rate-flag note. **Verified BYTE-IDENTICAL at production dt=1e-5** (default ≡
-nosprings ≡ explicit-springs = velFitX 8.225/avgB 4.000/netX −6.341 to the digit). Springs flags read ONLY in
buildScene ⇒ other harnesses + the fine-dt decompRun modes UNTOUCHED. **Re-baseline (jba sign-off flagged):**
numerically identical at production dt ⇒ all prior production-dt validation numbers STAND; the fine-dt convergence
reference moves to the springs continuum (differs from the rate continuum by −ln(1−frac)/frac); the GPU gliding
baseline is now the transcendental-free HIGH basin (velFitX ~3.2/pb ~1.0), NOT the -ratefix-seeded LOW (2.11/0.74) of
every prior table; cleanly reversible (three booleans / -nosprings). **DEFERRED (tracked, NOT done):** filament
retuning — springs' fixed stiffnesses are numerically identical to current at production dt (no retune now); needed
only if production runs BELOW refDt or the stiffnesses are given physical meaning (Lp/bending-modulus calibration).
Report: `SPRINGS_PROMOTION.md`; logs `RUN_LOGS/2026-07-08_promo_gate2_*.txt`.

# 2026-07-08 — GLIDING BISTABILITY = a GPU EXECUTION ARTIFACT (deterministic PTX-scheduling nudge from exp/log), not a real two-basin feature
Localized the origin of the GPU raw-HIGH vs `-ratefix`-LOW gliding split (PURE_SPRINGS). Added measurement/debug knobs
only (`-swingkprobe` extract the exact runner swing coeff; `-swingkbits <int|0x..>` force it, size-4, both runners);
default byte-identical; `BoA-v1ref` untouched. **VERDICT: GPU EXECUTION ARTIFACT, not a real chaotic bistability.**
**PART A (crux):** the ratefix in-kernel swing coeff `k=1−exp((dt/refDt)·log(1−0.4))` computes to **bit-identical
`0.4f` — 0 DOUBLE residual — on the GPU exactly as CPU** (probe). There is NO coefficient ULP to seed a real basin
selection. Forcing the CPU to the GPU coeff (`-swingkbits 0x3ecccccd`=raw) stays HIGH (velFitX 3.209/pb 0.920); the
CPU's own ratefix path (same bit-identical k) is bit-identical to raw ⇒ **A-HIGH** (the flip is beyond the coefficient).
**The trigger is the exp/log INSTRUCTIONS, not the value/buffer:** GPU-springs (size-5 swingParams, *multiply* branch,
same 0.4f) ≡ raw/HIGH; GPU-ratefix (size-5, *transcendental* branch, same 0.4f) = LOW ⇒ the exp/log perturbs PTX
scheduling/FMA-contraction of the surrounding torque math at ULP → tips the sensitive operating point. **PART C:** GPU
raw ×3 and ratefix ×3 **bit-identical run-to-run** ⇒ the flip is a **deterministic** compiler-scheduling artifact (NOT
a race; which is why it read as a stable "effect" across the `-allnoise`/`-thermcorr` tables). **PART B:** CPU is
**mono-stable HIGH** — all 3 seeds HIGH (velFitX 3.0–3.6/pb ≥0.92); sweeping swing k down (0.40→0.10) smoothly declines
glide but per-bound stays ~0.9–1.2 and NEVER reproduces the GPU-LOW signature (avgB ~2.87 *with* pb ~0.74); LOW is not
CPU-reachable by the coefficient. **BAILED (reported):** B2 (seed CPU from a GPU-LOW microstate) + true D-hysteresis
both need state-dump/continuation infra that doesn't exist — and D's premise (real/CPU-reachable basin) wasn't met.
Can't fully exclude a CPU-un-occupied attractor reachable only from the exact GPU microstate, but no evidence supports
it. **Practical rule:** basin selection by a last-bit PTX-scheduling nudge is not physically controlled ⇒ run a
deterministic transcendental-free swing path (springs multiply, or CPU-verified); **the honest gliding number is the
CPU/HIGH-basin value (velFitX ~3.2, pb ~0.9), NOT the ratefix-seeded LOW (2.11/0.74)** that every prior GPU table
called "baseline." Report: `BISTABILITY_ORIGIN.md`; logs `RUN_LOGS/2026-07-08_bistab_*.txt`. Default byte-identical.

# 2026-07-08 — PURE-SPRING reformulation: production-dt engagement is FORMULATION-INDEPENDENT (springs≡rates≡raw bit-identical @refDt)
Rebuilt EVERY fraction-per-step constraint as a genuine fixed spring — chain F3/F4 (`-pairsprings`), motor
F9/F10/swing (`-alignsprings`), and the NEW structural J1/J2/tail-anchor (`-structsprings`, mirrors `-structrate`
with `springify`) — with the rate machinery (`-ratefix`/`-structrate`) OFF, to test jba's Q: is the production-dt
avgBound (engagement) deficit driven by the constraint FORMULATION? Ported the springs flags into the
`dt-convergence-study` tree; additive, flag-gated, default byte-identical; replace-NOT-compose verified (spring
flags take precedence over the matching rate flag; in the pure-spring arm the rate flags are off ⇒ no mode
double-treated); `BoA-v1ref` untouched. **ANSWER: NO — and it CANNOT, by construction.** At production dt=refDt=1e-5,
`DT/refDt=1.0` exactly ⇒ `springify(k)=rateFix(k)=k`; the build-time coefficients are **bit-identical floats**
(0x3f000000/0x3e4ccccd/0x3ecccccd for 0.5/0.2/0.4, computed directly). The three freeze-forms are *defined to
coincide at refDt* and only diverge below it. **CPU ARBITER (basin-stable, mandatory), `-full` 26740 motors, dt=1e-5,
20k, seed 0: raw ≡ pure-springs ≡ `-ratefix -structrate` BIT-IDENTICAL to every digit** (velFitX 3.264, avgBsteady
3.307, netX −2.956). ⇒ engagement is formulation-INDEPENDENT; the ~27% avgBound deficit lives in kinetics/capture
geometry, NOT the freeze-form. **STEP-1 parity** PASS (springs≡raw bit-identical, springify is an exact ×1.0).
**STEP-2 powerstroke force** PRESERVED exactly (`-stretchcensus` fdFilPN 0.709/2.097 bit-identical all 3 arms — the
whole production-dt dynamics is bit-identical). **STEP-3 GPU** (3 seeds, 60k): raw≡springs bit-identical EVERY seed
(2.895/2.718/2.861), but `-ratefix` sits systematically in a LOWER basin (2.112/2.338/2.525, per-bound
[0.736/0.791/0.796] = the JOURNAL `-allnoise` OFF baseline bit-for-bit) — the ONLY difference from raw at refDt is
the ratefix in-kernel swing `exp/log`, whose GPU-**float** ULP tips the bistable glide basin. On CPU (double exp/log
= exact 0.4) it vanishes ⇒ **a BASIN FLIP, not a formulation effect** (springs — the real formulation change — does
NOT move off raw; only the transcendental path does). **Reproducibility exposure surfaced:** the prior gliding
baseline (velFitX 2.112 / per-bound 0.736, in every `-allnoise`/`-thermcorr`/re-convergence table) was always run
WITH `-ratefix` ⇒ it was the ratefix-seeded LOW basin; GPU raw/springs + the CPU-deterministic value sit in a HIGHER
basin (extends the 2026-07-08 bistability note — GPU gliding baselines' basin is decided by last-bit scheduling; any
GPU A/B whose arms differ in a transcendental-bearing flag is contaminated; trust the CPU). **STEP-4 moved limit**
(CPU v1box, basin-stable, matched 0.06s): at 1e-6 the forms DIVERGE — raw FREEZES high (velFitX 2.072→9.094, 4.4×,
frozen-skeleton ∝1/dt artifact), springs (2.542/4.455) vs ratefix (3.148/5.073) differ by the `−ln(1−k)/k` continuum
factor (springs ~0.81× velFitX, the expected re-baseline, not a regression); BOTH convergent arms bind MORE at 1e-6
(avgB +33%/+51% vs the 3.355 @1e-5) ⇒ the engagement deficit is dt-related + formulation-independent. Report:
`PURE_SPRINGS.md`; logs `RUN_LOGS/2026-07-08_puresprings_*.txt`. Diagnostic, default byte-identical, NOT promoted.

# 2026-07-08 — `-allnoise` is dead: a GPU execution artifact, not a correction; and the corrected convergence picture

Purpose: record the resolution of the `-allnoise` puzzle (it was never applying its stated physics), correct
the convergence picture that follows, and flag a genuine reproducibility exposure the diagnosis surfaced.

## What `-allnoise` turned out to be

`-allnoise` scaled the injected Brownian noise on the cross-bridge bond ends by √((2−α)/2) — the
equipartition variance correction. On the GPU (the runner every A/B table used) its apparent effect was **not
the noise physics at all**. Decisive test: force the applied scale to **exactly 1.0** (multiply the noise by
1 — arithmetically a no-op) with the code path active. On the CPU that is byte-identical to OFF, as it must
be. On the GPU it reproduced the **entire** `-allnoise` per-bound "recovery" (per-bound 1.286 at scale-1.0 vs
1.259 for the real factor vs 0.774 OFF). A change that changes nothing cannot cause an effect — so the effect
was never the correction.

Mechanism: enabling the code path adds two tasks to the GPU TaskGraph; TornadoVM recompiles/reschedules,
which perturbs unrelated kernels at the last-bit (ULP) level. That is normally nothing — but the gliding
steady state at this operating point is **chaotic and bistable** (two glide regimes), so a last-bit nudge
deterministically tips it into a low-avgBound / high-per-bound basin (landing tightly across all three seeds:
per-bound 1.29/1.29/1.27 — systematic, not scatter). The `√((2−α)/2)` factor's genuine contribution measured
≈ 0.

## Consequences

- **Drop `-allnoise`.** It was inert; removing it costs nothing. The earlier "63% recovered / required
  correction / overshoot" narrative was the artifact, not physics.
- **Both proposed fix paths are closed by measurement.** The isolated F8 bond is a clean *linear*
  (Ornstein–Uhlenbeck) mode with a *variance-only* Euler–Maruyama error and *no* mean/drift bias, so: the
  exact OU propagator equals `-allnoise` equals a faithful variance fix (path 1 — a "better/correct EM
  scheme" — offers nothing new), and the mode is linear (path 2 — implicit rotation for a nonlinear coupling
  — does not apply). Nothing to build on the cross-bridge integration for the convergence question.
- **The equilibrium-gate physics still stands** — the F8 bond genuinely obeys kT/k and the correction target
  was right. It is just that the *implementation's measured effect* was an execution artifact, and, with the
  convergent skeleton, there was no per-bound gap for it to close in the first place.

## The corrected convergence picture (what is actually true at 1e-5)

- The per-bound (**per-head efficiency**) at production dt is **nearly converged** — arm A per-bound sits in a
  ~0.76–0.88 band across 1e-5→5e-7 with no systematic climb. (Reported as a *band*, not a sharp value: the
  fine points did not razor-flatten and were 2-seed — partial bail. So "within ~10%" is a loose band, not a
  pinned convergence.)
- The remaining production-dt gap has moved into **avgBound (engagement)**: 1e-5 binds ~27% fewer heads than
  the fine-dt limit, which drives **absolute glide (velFitX) low by ~35–50%**. If absolute gliding speed is
  the validation target, this engagement deficit — NOT any per-bound/efficiency error, and nothing to do with
  `-allnoise` — is the real open item.
- Net: the old "~2× dt-bias" is gone (it was the frozen-skeleton artifact, fixed by `-structrate`); what
  remains is an engagement (avgBound) deficit at production dt.

## Reproducibility exposure flagged (separate from `-allnoise`)

The gliding steady state at this operating point is **bistable, and a last-bit perturbation flips it.** That is
a property of the model/operating point, not of `-allnoise`. It means *any* change that alters GPU kernel
scheduling (a new task, a compiler/driver update, a reordered buffer) can silently move which basin a
production run lands in — a real reproducibility risk for the gliding assay, and grounds for a skeptical
re-read of any GPU A/B whose two arms differed in *graph structure* (some prior "effects" may have been basin
flips). Open question, not chased yet: is the bistability physical (two real gliding regimes) or a fragility
of this parameter point? Noted for awareness; deferred by choice.

## Next (jba's call)

Plod ahead on the **avgBound (engagement) deficit** — why production dt binds ~27% fewer heads than the
converged limit. First concrete test requested: strip the fraction-per-step PAIRS coefficients and `-ratefix`
out entirely and replace every such constraint (chain, alignment, structural) with genuine **linear and
torsional springs** (fixed stiffness), to see the model's behavior with no per-step-fraction rate machinery
anywhere. The bistability is acknowledged but deferred (unclear what to do about it, and it does not block the
engagement question).

## Addendum (2026-07-08) — diagnosis re-run independently + reproducible instruments persisted
Re-ran the full STEP 1→4 diagnosis from clean and **reproduced every number** above (60k×3-seed GPU:
scale-1.0 per-bound 1.286 [1.294/1.294/1.271] ≈ real 1.259 [1.397/1.080/1.299] ≫ OFF 0.774 [0.736/0.791/0.796];
CPU scale-1.0 ≡ OFF byte-identical; STEP-3/4 isolated F8 mode variance-only + mean-unbiased + linear ⇒
OU≡`-allnoise`). Added STEP-2 on the deterministic runner: the genuine factor IS a real but **small,
threshold-like/non-monotonic** lever (CPU `-allnoisescale 0.90` avgB −29%, real `-allnoise` −12%, per-bound
0.532→0.659 +24% — vs the GPU factor-1.0 artifact's +66%), dt-vanishing — confirming the near-bistable "two
glide regimes" and that the large GPU offset is the factor-independent graph-split artifact. Instruments now
persisted (were missing): `GlidingHarness -allnoisescale <x>` (force applied factors, code path active),
`EomStabilityHarness -varmean` (isolated-mode Var+mean OFF/ON/OU). Detailed report:
`CONSTRAINED_VARIANCE_PROBE.md` §`-allnoise` DIAGNOSIS; log `RUN_LOGS/2026-07-08_allnoise_diagnosis.txt`.
Default byte-identical (real `-allnoise` reproduces the prior table's 1.397); `BoA-v1ref` byte-clean.

# 2026-07-08 — GLIDING RE-CONVERGENCE (4 dt × 2 arm, convergent skeleton): the dt→0 limit MOVED DOWN ~2×
Re-measured the gliding dt-convergence now that `-structrate` makes the motor skeleton dt-convergent (every
prior number — per-bound 0.736→~1.57, "63% recovered," "~37% coupled residual" — predated it, i.e. was measured
with the J1/J2/anchor springs FREEZING at fine dt). Sweep 1e-5/5e-6/1e-6/5e-7, matched sim-time 0.6s, 2–3 seeds,
GPU device-resident, `-ratefix -structrate` throughout. Arm A = uncorrected; Arm B = + `-allnoise` (the gate's
REQUIRED both-F8-bond-end correction, factor dt-adaptive ×0.857@1e-5→×0.993@5e-7 confirmed). **HEADLINE: the
dt→0 limit MOVED DOWN ~2×.** Clean natural control (arm A byte-identical to the stale uncorr curve @1e-5):
arm A(`structrate`) @5e-7 = velFitX 3.638 / per-bound 0.884 vs the STALE no-structrate @6.25e-7 = velFitX 7.086
/ per-bound 1.592. **Reason:** raw fracMove structural springs stiffen ∝1/dt ⇒ the stale fine-dt runs had an
ARTIFICIALLY RIGID skeleton (over-reacts the stroke against the anchor → inflated glide); `-structrate` pins them
at production stiffness ⇒ the honest, ~2× lower limit. **The stale ~1.57 per-bound limit was a frozen-skeleton
artifact.** **(1) dt→0 limit:** approximately dt-STABLE but NOT razor-flat (partial bail) — per-bound band
~0.76–0.88 (mean ~0.81, wobble ±0.06, part 2-seed noise), avgB cleanly flattening ~4.1, velFitX ~3.1–3.6; NO
systematic 2× climb. **(2) production gap:** uncorrected per-bound 0.774 → limit ~0.85 ≈ **within ~10% — per-HEAD
efficiency is NEARLY dt-honest at 1e-5**; the remaining dt-gap is in avgBound (+27%, under-binds) and velFitX
(+35–50%, under-glides) ⇒ **the gap moved OUT of efficiency, INTO engagement/absolute-glide** (opposite of the
stale decomposition). **(3) required correction:** does NOT shrink a gap — it OVERSHOOTS the new lower limit
(production per-bound 0.774→1.259 = ~48% ABOVE ~0.85); the A/B offset (+0.49→+0.63) does NOT close at fine dt
even as `-allnoise`'s factor→1 — an OPEN PUZZLE (a nominally dt-vanishing correction with a non-vanishing ~0.63
per-bound offset, insensitive to factor magnitude ⇒ accumulated/hysteretic, not instantaneous; arm A & B should
meet as dt→0 but haven't by 5e-7 — flagged, not resolved). **(4) monotonicity:** cleaner than the frozen skeleton
(no fine-end mush) but residual ~10–15% scatter. **Do NOT re-commit to the old "37% coupled residual" — redefined
against the inflated limit; on per-bound the residual is now ~10%.** Report: `GLIDING_RECONVERGENCE.md`; logs
`RUN_LOGS/reconv/`. Follow-ups: 3rd seed at fine points; root-cause the A/B non-convergence. Diagnostic, no promotion.

# 2026-07-07 — STRUCTURAL-MODE REFORMULATION (`-structrate`): the motor skeleton is now uniformly dt-convergent
The `-vargate` flagged the motor's passive structural joints (J2 hinge + tail anchor) as NEEDS-REFORMULATION —
equilibrium but dt-FLAT (fracMove α=frac) ⇒ their variance FREEZES ∝dt toward zero as dt→0 (the driven
stroke/alignment F9/F10/swing + the chain F3/F4 were already ratefixed; these were left out). `-structrate`
brings them to dt-convergent per-TIME rates (same build-time `rateFix` as `-filrate`/`-alignrate`), for
CONSISTENCY OF REALISM (not transport). **PART 1 (code read, the spec):** the un-converted structural terms are
the CONNECTION/POSITION springs `jointParams[1]` (J1) + `[5]` (J2) + the tail anchor `[9]` (all fracMove=0.4,
cleanly fraction-per-step — PAIRS form). **J1/J2 taxonomy resolved:** the "J1 swing converter" (`jointParams[3]`)
is the powerstroke driver — ZEROED in glide (dirswing) + replaced by `directedSwing`/F9 which ARE ratefixed
(`-strokerate`/`-alignrate`) ⇒ already covered; the structural angular torsions `[3]/[7]` are inert in glide
(zero coeff); `fracR [2]/[6]` is geometry. **PART 2 (reformulation):** pure build-time coeff substitution on
`[1]/[5]/[9]` = `rateFix(0.4)`, UNCHANGED kernels ⇒ race-free/CPU≡GPU; NOT folded into `-ratefix`; default
byte-identical. **Gliding transport impact @1e-5 (`-full ... -ratefix -coltol10 -d1000`, 60k GPU): BYTE-IDENTICAL**
(velFitX 2.112, avgB 2.870, per-bound 0.736, detach 1624, duty 0.811 — every digit; GRID_ROW+STATS_STEADY
`diff`-clean) ⇒ **the re-baseline cost at production dt is EXACTLY ZERO** (production dt = refDt ⇒ rateFix(0.4)=0.4);
it only bites below refDt (the dt-ladders). **PART 3 (re-gate `-varprobe -structrate`):** Axis-2 FLIPS dt-FLAT→
dt-VANISHING — J2 gap α 0.24(flat)→0.242@1e-5, 0.016@6.25e-7; anchor 0.29→0.294→0.024; J1 gap 0.35→0.354→0.023
(∝dt, ~15× over 16× dt); E→1; and the absolute Var now CONVERGES to a fixed physical spread (anchor 11.1→8.5,
J2 16.1→13.0 nm²) instead of freezing toward zero. Axis-1 ρ≈1 (passive/equilibrium) ⇒ decision
NEEDS-REFORMULATION → **REQUIRED**. **J1 ringing-edge re-check (`-vargate -structrate`):** J1-swing ρ stays ≈1
(0.997/1.001/1.005/0.990) — equilibrium survives; production-dt α_off=1.0238 (ringing edge) BYTE-IDENTICAL
(structrate can't move production @refDt); at finer dt the swing is better-resolved (1.02→0.13, Var→fixed 24.7).
**⇒ the motor skeleton is now uniformly dt-convergent, at ZERO production-dt cost; J1's equilibrium verdict holds.**
Report: `CONSTRAINED_VARIANCE_PROBE.md` §STRUCTURAL-MODE REFORMULATION; logs `RUN_LOGS/2026-07-07_structrate_*`.
New code: `GlidingHarness`/`EomStabilityHarness -structrate`. Default byte-identical; `BoA-v1ref` byte-clean.
**Diagnostic — NOT promoted to default (re-baselines every dt-ladder ⇒ planner sign-off).**

# 2026-07-07 — Uniform equilibrium gate: which thermal corrections are required, decided by measurement

Purpose: resolve, by one uniform test applied to every constrained mode, which Euler–Maruyama variance
corrections are physically required versus forbidden — with no mode classified by whether it helps the glide.
This settles the F9/F10 "faithful but harmful" paradox and surfaces the actual model defect.

## The gate

Every constrained mode was run through the same two-part test, under its real operating conditions:

- **Equilibrium vs driven — by a target-free discriminator.** For a mode, compare its fluctuation *while the
  powerstroke is driving* to its fluctuation *while held*, at the same nucleotide state:
  ρ = (variance while driving) / (variance while held). ρ ≈ 1 means the drive moves the mode's *average
  position* but leaves its *jitter* untouched — equilibrium about a moving mean. ρ ≫ 1 means the drive pumps
  the fluctuation itself — genuinely non-equilibrium. This needs no kT/k value, which is essential because the
  angular modes' analytic stiffness is geometry-dependent and unreliable.
- **dt-vanishing vs dt-flat.** Does the correction factor → 1 as the timestep → 0 (faithful; it only
  accelerates convergence) or stay constant (fraction-per-step; correcting it re-baselines the model)?

## Headline

**No mode is forbidden at biological cycling rates.** The prime forbidden candidate — the F9/F10 powerstroke
alignment and the J1 swing — measured **equilibrium** (ρ = 0.99–1.06 at every timestep). The powerstroke
relocates the mode's mean (F9 90°→116°, J1 14°→58°); the fluctuation about that moving mean stays thermal. So
imposing kT/k on these modes is the *correct* target, not a wrong equilibrium constraint.

## The paradox resolved

F9/F10 is an equilibrium mode (the correction is right) **and** cooling it moves per-head glide *away* from the
converged number — and these do not conflict. The gate answers "is kT/k the right target for this mode's
fluctuation?" (yes). It does not answer "does correcting it help transport?" When F9/F10 is corrected
faithfully, **the converged target itself moves** — there is no fixed truth being degraded. The earlier
instinct that "one of the two corrections must be wrong" was itself mistaken: it assumed a fixed target that
does not exist.

## The honest caveat — regime dependence

Equilibrium is not unconditional. The *same* modes measure **driven** (ρ up to ~9) when the nucleotide state
is switched faster than the mode can relax. They are equilibrium here only because the biological dwell times
(~10–1000 steps) are much longer than the mode relaxation times (~1–2 steps), so each state fully settles
before the next switch. This separation of timescales holds at both timesteps tested. The classification is
defensible precisely because it rests on a *measured* timescale separation, not an assertion.

## The correction set, decided by physics (not by transport)

- **REQUIRED** (equilibrium + dt-vanishing): both cross-bridge-bond ends (bound head, bound segment), and — as
  production runs them (rate-converted) — the F9/F10 alignment and the J1 swing.
- **NEEDS-REFORMULATION** (equilibrium, but dt-flat — the mode *freezes* toward zero spread as dt→0 instead of
  converging to a physical value, so there is no honest kT/k to restore; noise-correcting it would re-baseline
  the model): the motor structural joints **J2** and the **tail anchor**, and the filament chain links before
  rate-conversion.
- **FORBIDDEN**: empty at biological rates.

## The real defect this surfaced

The work item is not "add more thermostat corrections." It is that the motor's own structural skeleton — the
J2 hinge and the tail anchor — is **thermally ill-posed**: as the timestep shrinks, those modes' fluctuation
freezes toward zero rather than approaching a physical spread. A model cannot claim a *consistent depth of
realism* while its own skeleton freezes at dt→0. The fix is to reformulate these fraction-per-step structural
constraints into dt-convergent form (the same rate-conversion the driven and chain modes already receive),
after which the equilibrium correction applies uniformly wherever it is required.

## Scope correction (the missed subset)

Contrary to the earlier assumption that all fraction-per-step constraints receive the rate-conversion
treatment, the **passive structural** constraints (J2 hinge, tail anchor) were left out. The **driven**
stroke/alignment (F9/F10, J1 swing) and the **chain** (via the chain rate-conversion) *are* covered. So the gap
is specifically the static skeletal constraints — plausibly missed because they produce no visible signature in
a gliding trace. (Terminology note: the earlier dt-audit's "J1/J2 torsions" and this gate's "J1 swing
converted" refer to *different* force terms — the swing converter versus the structural torsion springs — to be
disambiguated precisely in the reformulation spec.)

## Caveat on J1

The J1 equilibrium verdict rests on ρ ≈ 1 while its own relaxation is barely resolved at production dt (α ≈ 1,
the ringing edge). ρ held across all four timesteps, so the call stands, but J1 is the one mode to re-check
after reformulation rather than lean on its production-dt number alone.

## Next

Reformulate the dt-flat structural modes (J2, tail anchor) to dt-convergent form, then re-gate to confirm they
flip to dt-vanishing + REQUIRED. This is a model-integrity fix (uniform dt-convergence of the whole model),
largely orthogonal to the gliding transport number — these modes are stiff and near-static, so the expected
transport impact is small; measure it so the re-baseline cost of promotion is known.

# 2026-07-07 — UNIFORM EQUILIBRIUM GATE (`-vargate`): which thermal corrections are REQUIRED/FORBIDDEN, decided by measurement
Ran every constrained mode through ONE two-axis gate under REAL operating conditions (Axis 1: Var about the
CONDITIONAL/per-nucleotide-state mean vs `kT/k·2/(2−α)`, driven modes measured drive-ON *cycling* AND drive-OFF;
Axis 2: α@1e-5 vs α@6.25e-7). New code only (`EomStabilityHarness -vargate`: `runVarGate`/`angHold`/`angDrive`/
`setupAngScene`/`angStep`/`measureChainModes`), measurement-only, Brownian ON, single motor, **default
byte-identical**; `BoA-v1ref` byte-clean. **HEADLINE: no mode is FORBIDDEN, none ILL-DEFINED at biological cycling
rates.** The prime FORBIDDEN candidate — the F9/F10 powerstroke-ALIGNMENT + the J1 swing — is **REFUTED by
measurement**: they are **equilibrium about a MOVING mean** (the rest-angle switch relocates the conditional mean —
F9 90°→116°, J1 14°→58° — but the per-state conditional variance == the held drive-off variance at that state:
**ρ per-state 0.99–1.06 at every dt**). So imposing `kT/k` is NOT a wrong equilibrium constraint for them. The
FAST-square-wave control (T½≈τ) DOES inflate the running-mean variance (J1 51→449 — the gate correctly detects the
driven regime), but τ_mode≈1–2 steps ≪ the biological dwell 10–1000 steps ⇒ the SLOW/conditional regime is
operative ⇒ equilibrium is the real-condition verdict. Instrument reconfirmed (F8 gate E 1.271 vs 1.264). Chain
F3/F4: equilibrium (passive) but **dt-FLAT fracMove as-integrated** (α 0.361≈0.363 / 0.566≈0.570, Var∝dt) ⇒
NEEDS-REFORMULATION until `-filrate`. **Correction set (physics, not glide): REQUIRED** = both F8 ends + (ratefixed)
F9/F10/J1 (equilibrium + dt-vanishing); **NEEDS-REFORMULATION** = J2/tail-anchor + chain/align coeffs before ratefix
(equilibrium + dt-flat fracMove — make dt-convergent, THEN correct); **FORBIDDEN = empty** (biology not in the fast-
drive regime). This is ORTHOGONAL to transport: F9/F10 being "directedness-load-bearing" (§SYSTEM-WIDE STEP-2,
cooling moves per-bound away from the limit) is a *transport* consequence, NOT a non-equilibrium signature — the gate
answers "is `kT/k` the wrong target?" (no), not "does cooling help glide?". Matches the bond-only `-allnoise` ceiling
and classifies NOTHING by whether it moves the glide. Report: `CONSTRAINED_VARIANCE_PROBE.md` §UNIFORM EQUILIBRIUM
GATE; log `RUN_LOGS/2026-07-07_vargate.txt`; `./run_eomstab.sh -vargate`. Diagnostic, not promoted.

# 2026-07-07 — SYSTEM-WIDE thermal correction, done correctly (`-syswide`/`-syswiderb`)
Applied the `√((2−α)/2)` thermostat SYSTEM-WIDE with the single-constraint discipline (each mode's OWN α, NEVER
the summed N·k that over-cooled `-thermcorr`) + the new free-standing-motor-chain coverage. New race-free kernels
`BrownianForceSystem.scaleSysWideMotor`/`scaleSysWideSeg` (per-motor/per-segment own-slot); default byte-identical
(uncorr velFitX=2.112 reproduced; `-allnoise`=1.397 reproduced); `BoA-v1ref` byte-clean. **STEP-1b classification:**
F8 head-trans + F8 seg-trans(single-bond) + F9/F10 head-rot + chain-links are **FAITHFUL** (dt-vanishing, α∝dt via
`-ratefix`); **J1/J2/tail-anchor free-motor chain is RE-BASELINE** (dt-FLAT fracMove α=0.40 at both dts) — confirms
the task's flag. **Terminology:** both corrections are FAITHFUL (neither "wrong"); the objective axis is distance
to the measured dt→0 limit (per-bound ≈1.57) PER CHANNEL — "helpful/harmful" is wrong because it's channel-
dependent (head-rot cooling moves avgB TOWARD the limit but the per-bound ratio AWAY). **STEP-2 (1e-5, 4-arm):**
bond `-allnoise` moves per-bound +63 % toward the limit; +F9/F10 head-rot moves per-bound back away 1.40→0.75
(directedness-load-bearing) while over-retaining avgB; **free-motor-chain cooling moves binding only −13 % avgB,
per-bound-neutral ⇒ jba's question resolved: the coltol-10 search is only WEAKLY diffusion-limited** (the earlier
"free-myosin cooling starves binding" reading was mostly the BOUND head-rot + N·k over-cool).
**STEP-3 (matched-sim-time dt-ladder):** faithful `-syswide` **converges to the same dt→0 limit as uncorr**
(per-bound 1.549≈1.592 @6.25e-7; measured converged ≈1.57, below the 1.78 ROTIMPLICIT ref) but closes **~0 % of
the production-dt gap** (0.767≈uncorr 0.774) — the F8-bond cooling (toward the limit) is CANCELLED by the head-rot
cooling (away from it).
**The bond-only correction remains the ceiling; the fix stays the coupled sub-step, not a per-mode thermostat.**
Report: `CONSTRAINED_VARIANCE_PROBE.md` §SYSTEM-WIDE. Diagnostics only, not promoted.

# 2026-07-07 — dt-convergence arc, consolidated: the reducible-thermal / coupled-transport decomposition

Purpose: fold the whole dt-convergence investigation into one clean picture and correct several
descriptions that changed wording across sessions and made the record look like it was flip-flopping.
The underlying result has actually been stable; the labels were not. Terms are spelled out here rather
than abbreviated.

## The result in plain terms

The model's gliding output depends on the timestep. At the production timestep (dt = 1e-5 s) it
under-represents the converged (dt → 0) glide by roughly 2× in per-head transport. That dt-dependence
splits into two parts:

- **A reducible thermal part (~63% of the gap).** The coarse-timestep stochastic integrator injects
  thermal kicks sized for a *free* body onto bodies that are actually *constrained* by a spring. On a
  constrained body this over-fluctuates the constrained coordinate. For the cross-bridge bond (the spring
  between a bound myosin head and its filament attachment site) this excess jitter inflates the force the
  detachment machinery sees, which shortens the time a head stays bound and suppresses the glide each bound
  head produces. Removing the excess recovers ~63% of the gap at the production timestep — and, critically,
  the correction **vanishes as dt → 0** (it is a genuine numerical-error fix, not a re-tuning; see
  "Corrections to earlier framings" below).

- **A remaining part (~37%).** This lives in the *coupled* motion of bodies that are bound together — the
  cross-bridge treated as a genuine two-body system, and a filament segment held by several motors at once.
  No per-body correction — thermal or integrator — reaches it. It requires a treatment that solves the
  bound bodies together.

## The mechanism, stated once

The explicit stochastic integrator (Euler–Maruyama) over-samples the equilibrium spread of any
spring-constrained coordinate by the factor 2/(2 − α), where α = (stiffness × timestep) / (drag). For the
cross-bridge bond at the production timestep α ≈ 0.42, giving ~1.3× excess variance (~1.8× excess in
root-mean-square force). Because the detachment rate curves upward (is convex) in force, an inflated force
*spread* inflates the *mean* detachment rate — shorter dwells, less transport per bound head. Scaling the
injected random force on that mode by √((2 − α)/2) restores the correct equilibrium spread and recovers the
transport. This is the same measured, validated mechanism throughout; only its accounting changed between
sessions.

## Reducible sources — all now excluded as the residual

Every dt-dependence we could name as *reducible* has been tested and removed as the explanation for the
remainder:

1. **Integration accuracy of the deterministic part.** A second-order (trapezoidal) integrator was
   17× more accurate on the isolated relaxation yet made no difference in the assay — not the residual.
2. **Thermal over-fluctuation of constrained bonds.** This is the ~63% — real and characterized.
3. **Detachment-rate sampling.** The detachment rate is convex in force and is evaluated once per step;
   we suspected that under a changing load this mis-estimates the rate. Tested deterministically under a
   ramping load, it is flat with timestep (< 0.2% at production dt). The ~3.5% that appears once thermal
   noise is on is *entirely* the same cross-bridge-bond over-fluctuation from (2), not a separate kinetics
   error. So detachment is not an independent residual.

By elimination — not by assertion — what remains (~37%) is the coupled loaded transport.

## Corrections to earlier framings (so they don't calcify in the record)

a. **The thermal correction is faithful and vanishes as dt → 0.** Decisive check: a genuine flat 2% cut to
   *all* thermal noise is inert at the fine timestep (−1.5% on glide), exactly as any dt-vanishing
   correction must be. An earlier reading — that the correction "overshoots" the converged answer at fine
   timestep, implying the glide is ~15× hypersensitive to noise amplitude — was **wrong**. The apparent
   overshoot was the multi-motor over-cooling in (b), not amplitude sensitivity.

b. **The multi-motor over-cooling is a mis-sized correction, not correct physics applied honestly.** For a
   segment held by several motors we estimated the segment's effective stiffness by *summing* the individual
   bond stiffnesses. That overestimates the true combined stiffness (the bonds attach at different points and
   partly pull against one another), so we used too large an α and cooled too hard — and because α was
   wrong, that correction does *not* vanish with timestep as it should. This one bug, wearing three
   costumes, is what made the arc look like it flip-flopped: it appeared as "load-aware recovers less," as
   the "fine-dt overshoot," and as the mislabeled "hypersensitivity." The fix is the **correlated (joint)
   stiffness** of the co-bound bodies, not the sum of their separate stiffnesses.

c. **The free-standing motor body chain is constrained, not free, and has not yet been corrected.** A myosin
   is three rigid bodies — head, neck/lever, tail — joined by springs, with the tail anchored to the
   surface. So even an *unbound* head is constrained through its neck to the anchored tail; those modes are
   over-heated too and deserve the same correction. An earlier statement that "unbound means no constraint,
   so nothing to correct" was **wrong**: only a truly unconstrained body has α ≈ 0. We have *not* yet
   applied the correction to this free-motor body chain, and by the system-wide principle below we should.

d. **"Cooling the head's angular motion reduces binding" is a re-measure trigger, not a reason to stop.**
   Binding proceeds by a free head's angular thermal search for an attachment site. The correct thermal
   correction reduces that search rate — because the search was riding on numerically *inflated* angular
   fluctuation. So the reduced binding is the correct correction acting on an over-heated rate, judged
   against a converged target that was itself measured with the search over-heated. That means the target is
   not yet honest, not that the correction is harmful. (Scope note: confirm exactly which heads and which
   modes the current correction actually reached before relying on this.)

## Governing principle (jba)

The over-fluctuation correction is physically correct and must be applied **system-wide**, wherever a real
spring/joint constraint exists — not selectively to fit a particular target. Truly unconstrained bodies
self-exclude (their α ≈ 0, so their correction ≈ 1, i.e. nothing). Applying it selectively to preserve a
target is exactly what would produce an indefensible model that is "right only at one timestep."

## Next actions (sequenced)

1. **Apply the correction system-wide**, including the free-standing motor body chain (head–neck–tail),
   each mode using its own stiffness. Confirm each newly-corrected mode's correction vanishes as dt → 0 (the
   same faithfulness test the translational bond correction passed).
2. **Re-measure the converged target** with these modes correctly cooled. The current converged reference
   was measured with the free-head search (and other modes) still over-heated, so it is not yet the honest
   target; the binding-search rate in particular will shift. The size of the "remaining" gap is not known
   until this is done.
3. **Only then, size and address the coupled-transport remainder.** Decide between a targeted sub-step on
   the few bound heads and a coupled ("off-diagonal") implicit solve that uses the correlated stiffness of
   co-bound bodies. This is the same coupled-stiffness problem that has been the true residual all along.

## Open items / caveats

- The correlated-stiffness correction (the joint spread of all bodies bound to one segment) is more than a
  per-body number; its live-compute cost is unknown and is the main risk of the coupled fix.
- The ~37% remainder is provisional — step 2 moves the goalposts.
- Confirm the exact scope of what the current corrections cooled (which heads, which modes).

## 2026-07-07 — OVERSHOOT DIAGNOSIS (A) + DETACHMENT-RATE ISOLATION (B) → the fine-dt "overshoot" is NOT hypersensitivity (it's thermcorr4 load-aware over-suppression); detachment kinetics are dt-flat. `CONSTRAINED_VARIANCE_PROBE.md`.
**Re-tests the two claims the `-thermcorr` section closed on: (i) a genuine fine-dt overshoot, (ii) proving ~15× noise-amplitude
hypersensitivity.** Both rested on a 6.25e-7 run at **150k steps = 0.094 s** (vs 0.6 s @production) with no uniform control. New
code only: `GlidingHarness -uninoise <fac>` (flat brownianForceMag×fac, both stores) + `EomStabilityHarness -detachramp`; default
byte-identical (uncorr seed0 velFitX=2.112 reproduced), `BoA-v1ref` untouched. **PART A — matched 0.6 s (960k steps @6.25e-7), 2
seeds fine / 3 coarse:** (A1) the overshoot **SURVIVES and GROWS** at matched time — thermcorr4 per-bound **2.36 vs uncorr 1.59
(+48%)**, avgBound halved 4.45→1.99; windowing shifted the uncorr *reference* −17% (short-window 1.914→1.59, it was transient-
inflated) but did NOT create the overshoot (+29%→+48%, windowing was masking it). (A2, DECISIVE) a **genuine flat ×0.98 on ALL
Brownian (`-uninoise 0.98`) is INERT at fine dt** — velFitX 7.09→7.08 (−0.1%), per-bound 1.59→1.57 — while thermcorr4 swings velFitX
−34% / avgBound −55%. **A clean 2% noise cut does ~nothing at 6.25e-7 ⇒ the "~15× hypersensitivity" reading is REFUTED.** The swing
is thermcorr4's **load-aware `α=N·k_F8·dt/γ` term** (the ONLY term >few-% at fine dt: single-bond α→0.03⇒×0.99, but N·α is O(1) up
to the α→2 clamp ×0.1 on multiply-bound segments) **over-cooling the transport-bearing bound segments** — the **same Jacobi-
stiffness-sum breakdown as coarse arm 3 (−17pp)**, now shown to drive the fine-dt "overshoot." Sensitivity to a flat cut is +11% @1e-5
but ≈0 @6.25e-7 — it DECREASES with finer dt (opposite of the claimed fine-dt hypersensitivity). **⇒ overturns the section's "Plain
verdict (2)" mechanism** (a faithful uniform dt-vanishing correction is CORRECTLY inert at fine dt; there is no noise-amplitude
hypersensitivity to fight); what STANDS is load-aware over-suppression + cooling-load-bearing-modes-is-harmful + the coupled-sub-step
fix, and the `-allnoise` ~63% @production (single-bond, α large @1e-5) is untouched. **PART B — `-detachramp` (deterministic, ~s):**
the exact catch-slip rate `kOff·(αC·e^(−F·xC/kT)+αS·e^(+F·xS/kT))` under a ramping load, sampled once/step exactly as `catchSlipRelease`
reads forceDotFil, mis-estimates ⟨F_detach⟩ by **−0.07% (Ḟ=1 pN/ms) → −0.19% (aggressive 30 pN/ms) @1e-5**, shrinking 1st-order ⇒
**deterministic detachment is dt-FLAT; NO reducible convex-rate-sampling error.** B2: the ~3.5% detachment dt-dependence with thermal
force fluctuation is **entirely the F8-well variance inflation** (E=1.36→1); correcting variance (E=1) collapses it to −0.13% (the
pure-sampling residual) ⇒ the detachment dt-dependence IS the thermostat-variance mechanism, not a separate kinetics error. **NET: every
reducible dt-source is now excluded** — integration order (XBTRAP NULL), thermostat variance (faithful uniform correction inert @fine
dt), convex detachment sampling (<0.2%, dt-flat) — leaving the **coupled loaded transport** (EOM_STABILITY/STROKE_TIP → sub-step) as the
irreducibility candidate reached by ELIMINATION. Logs `RUN_LOGS/2026-07-06_{overshoot_A,detachramp}.txt`; `run_overshoot_A.sh`,
`run_eomstab.sh -detachramp`. Diagnostic, not promoted.

## 2026-07-06 — COMPREHENSIVE per-body-α correction (`-thermcorr`) → correcting EVERYTHING does NOT beat `-allnoise`; ~63% is the CEILING (not a lower bound). `CONSTRAINED_VARIANCE_PROBE.md`.
**Per-body α from ACTUAL total constraint stiffness** (load-aware seg N·k_F8 + chain on ALL segs + motor modes). New kernel
`scaleThermCorrSeg` (per-SEGMENT own-slot ⇒ race-free; α=N·aPerMotor + nNbr·aPerNbr, both ∝dt, clamp α<1.98). `-thermcorr 3/4/5`;
default byte-id. **Cumulative 5-arm A/B @1e-5 seed0 (`-full` 26740 mot, 60k), per-bound / marginal-pp-of-gap:** baseline 0.736 →
**allnoise 1.397 (63%, +63)** → tc3 load-aware **1.221 (46%, −17)** → tc4 +chain **1.260 (50%, +4)** → tc5 +motor **0.859 (12%, −38)**.
**⇒ correcting every body does NOT recover more — the ~63% (allnoise) is the CEILING, REFUTING jba's "multi-bound under-corrected"
prediction AND the prior "63% is a lower bound" claim.** (1) **Load-aware LOSES −17pp: N·k_F8 (Jacobi stiffness-sum) OVERESTIMATES the
coupled COM stiffness** ⇒ over-suppresses multi-bound segs (up to the α→2 clamp, noise ×0.1); the uniform single-bond ×0.889 was
near-optimal. (2) **Chain-on-unbound-neighbors +4pp** — jba's transmission hypothesis holds DIRECTIONALLY but is a SMALL chunk of the
37%. (3) **Motor modes COLLAPSE −38pp** (head-rot cooling: detach 889→1343, dwell 1.13→0.74, velFitX 2.82→1.99) — the F9/F10 angular
over-fluctuation is a load-bearing search/stroke mode, not correctable (2nd confirmation, via stroke this time vs binding for
`-fracnoise`). **dt-vanishing — the KEY nuance: the FACTOR vanishes (∝dt ⇒ scale ≈0.98 @6.25e-7 vs 0.61–0.89 @1e-5, 16× smaller) but
the EFFECT does NOT.** Robust `-full` uncorr-vs-thermcorr4: @1e-5 per-bound 0.736→1.260 (toward converged); @6.25e-7 uncorr 1.914
(≈converged) → thermcorr4 **2.469 (OVERSHOOTS +29%, avgBound HALVED 4.05→1.93)**. ⇒ glide is **hypersensitive to noise amplitude
(~15× gain)** — a 2% factor → 29% per-bound ⇒ the correction is **NOT a clean dt-fix**; it points the right way @production but
over-cools/overshoots @fine dt = substantially a noise-amplitude RE-BASELINE. **This CONFIRMS the over-fluctuation mechanism is a
major driver BUT shows a per-mode noise scale is the wrong INSTRUMENT** (can't get the coupled stiffness right — load-aware fails —
and the hypersensitivity demands precision it can't deliver). **⇒ the remaining ~37% AND the overshoot both point to the COUPLED fix
(sub-step/implicit on the loaded cross-bridge — correct the DYNAMICS, not blunt the NOISE), not a thermostat.** (v1box empirical is
further confounded by small-box chaos, sign-flipping vs `-full`.) Diagnostic, not promoted. `run_thermcorr_ab.sh` /
`run_thermcorr_dtvanish_full.sh`, `RUN_LOGS/2026-07-06_thermcorr_{ab,dtvanish_full}.txt`.

## 2026-07-06 — CEILING TEST: FULL constrained-mode correction (`-allnoise`) → recovers ~63% of the per-bound dt-bias (SHATTERS the head-only ~10%). The thermostat mechanism is MAJOR, not minor. `CONSTRAINED_VARIANCE_PROBE.md`.
**Correcting BOTH F8-bond ends' translation, not just the head's.** New kernels: `scaleMotorNoise` (per-motor own head/rod
slot ⇒ race-free) + `scaleBoundSegNoise` (per-SEGMENT own slot, gated by the CSR histogram `segMotorCount` — race-free
WITHOUT the gather, one-step-stale). Wired into GlidingHarness CPU+GPU buildPlan; `-allnoise` (head-trans ×0.857 + seg-trans
×0.889, real-spring/dt-vanishing) / `-fracnoise` (+ head-rot ×0.894 + rod ×0.894, fracMove RE-BASELINE). Default byte-identical;
per-mode α from each mode's own k/γ. **4-arm A/B @1e-5 seed0 (`-full`, 60k):** per-bound **baseline 0.736 → bondnoise 0.826
(~9% gap) → allnoise 1.397 (~63% of the ~1.04 gap to converged 1.78) → fracnoise 0.744**. Arm-3 chain coherent + strong:
detach/s 1624→**933** (−43%), dwell 0.62→**1.07ms**, velFitX 2.11→**3.77** (+78%, ≫SEM); avgBound dips (fewer rebinds) ⇒ the
gain is per-bound EFFICIENCY. **⇒ the SEGMENT side (the transported body, the OTHER bond end) DOMINATES** — the head-only test
under-corrected by half; **the constrained-variance thermostat is a MAJOR per-bound dt-driver, OVERTURNING the `-bondnoise`
"minor" reading.** Faithful (dt-adaptive→1; the ~63% is a LOWER bound — multi-bound segs under-corrected). **Arm 4 fracMove
re-baseline COLLAPSES engagement** (avgBound 2.70→1.48: cooling F9/F10 head-angular over-fluctuation kills the thermal SEARCH
binding relies on) ⇒ harmful, not dt-recovery — reported separately. Remaining ~37% = orientation integration (still-explicit
F8-tip rotation) + collective loaded transport (EOM_STABILITY/STROKE_TIP → sub-step). **CPU≡GPU:** baseline bit-identical; the
correction's per-step brownTransScale writes tip into expected chaotic decorrelation (window-erratic, not a bug); 3-seed
aggregate velFitX GPU 1.996±0.24 ≈ CPU 2.074±0.14 (within SEM); seg correction race-free (no bail). Diagnostic, not promoted.
`run_allnoise_ab.sh` / `run_allnoise_parity.sh`, `RUN_LOGS/2026-07-06_allnoise_{ab,parity}.txt`.

## 2026-07-06 — WIRED the √((2−α)/2) bond-noise correction into the gliding assay (`-bondnoise`) → moves per-bound +12% toward converged (real but MINOR, saturates — SUPERSEDED by the `-allnoise` ceiling test below/above). `CONSTRAINED_VARIANCE_PROBE.md`.
**The downstream correction from the variance probe.** `BrownianForceSystem.scaleBoundHeadNoise` scales each BOUND head's
translational Brownian by `√((2−α)/2)`, α=k_F8·dt/γ_head (per-motor, writes own head slot 3m+2 ⇒ race-free/no-atomics,
CPU≡GPU by construction; segment side NOT corrected — shared by ≥1 motor ⇒ not race-free, and the head is the dominant
low-drag mode). Wired into `GlidingHarness` stepOrig/stepFresh + the GPU buildPlan (both branches + transfer + worker grid);
`-bondnoise` (principled α_head ⇒ ×0.857 @1e-5) / `-bondnoisefac <x>` (override, e.g. 0.534 = the assembled empirical
α_eff≈1.43). Default byte-identical (all gated; chain-split reproduces the identical graph off); dt-adaptive (→1 as dt→0 ⇒
only bites at coarse dt). **A/B @1e-5 seed 0 (`-full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix`, 60k):** per-bound
**0.736 → 0.826** (+12%, ×0.857) / 0.815 (×0.534) — a fully coherent chain (detach/s 1624→1495, dwell 0.62→0.67 ms,
avgBound 2.87→3.24, velFitX 2.11→2.68/+27% ≈4× SEM). ⇒ **CONFIRMS** the bond over-fluctuation inflates catch-slip
detachment and drags production per-bound down — **but MINOR (~9–10% of the ~1.04 dt-gap to the converged 1.78) and it
SATURATES** (the full ×0.534 correction ≈ the mild ×0.857 ⇒ fully taming the bond adds nothing). The remaining ~90% is the
loaded/cycling collective transport ([[stroke-tip-implicit-probe-bail]] / EOM_STABILITY collective loaded force → sub-step),
which a single-bond thermostat can't reach. Faithfulness (restores Var=kT/k, moves toward the dt→0 truth), not a tuning knob;
stays a diagnostic (not promoted). Single-seed; 2–3 seed confirm flagged. `run_bondnoise_ab.sh`,
`RUN_LOGS/2026-07-06_bondnoise_ab.txt`.

## 2026-07-06 — PROBE: constrained-body thermal over-fluctuation vs dt, per mode → mechanism VALIDATED; the F8 BOND dominates (3.4× @1e-5), not the joints. `CONSTRAINED_VARIANCE_PROBE.md`.
**Every body gets a FREE-body thermal kick, but a constrained body isn't free** ⇒ explicit Euler–Maruyama over-fluctuates a
mode of stiffness k to `Var_EM=(kT/k)·2/(2−α)`, α=k·dt/γ. **STEP-1 GATE PASS (bulletproof):** the isolated F8 bond mode's
measured variance tracks `2/(2−α)` across a 128× dt ladder for TWO α (x∥ γ_∥, y/z⊥ γ_⊥) vs ONE γ-independent `kT/k_F8=4.116
nm²` — 6.19/6.15 @4e-5 (α=1.67) → **1.271/1.265 @1e-5 (α=0.42)** → 1.03/1.01 @3e-7; α_emp=α_analytic to 3 digits. The
over-fluctuation is REAL, climbs toward the α→2 edge at coarse dt, → 1 as dt→0. **STEP-2 (assembled motor, per mode):** two
families — REAL springs (F8, α∝dt, excess vanishes) vs **fracMove joints (J1/J2/anchor, α=frac≈0.4 dt-FIXED ⇒ a PERMANENT
~1.14–1.22× excess** that never converges, Var∝dt). **Ranking @1e-5: F8 bond 3.38× ≫ J1 1.22 > anchor 1.17 > J2 1.14** —
jba's stiff-joint hypothesis REFUTED; the bond dominates because its free end is the LOW-DRAG sphere head (α 0.42→~1.4 once
unpinned, softened further by the joint net; DIVERGES at dt≥2e-5, the EOM instability). **Plausibility:** 3.4× bond-stretch
var = 3.4× bond-FORCE var (~1.84× RMS) feeds the exponential catch-slip + stroke rectification — right locus, right dt-trend
⇒ a CREDIBLE chunk of the 2.2× per-bound bias, but EQUILIBRIUM-measured (stroke held) so causation needs the loaded/cycling
channel + the downstream `√((2−α)/2)` bond-noise correction (NOT built). Measurement-only, Brownian ON, single motor;
`-varprobe`, default byte-identical (default relaxation grid unchanged); `BoA-v1ref` byte-clean.

## 2026-07-06 — PROBE: is the gliding per-bound dt-bias the smooth F8-translation relaxation (cheap 2nd-order integrator fix) or kinetics/event-timing (needs a sub-step)? → STEP 1 PASS, STEP 2 NULL. `XBTRAP_PROBE.md`.
**The trapezoidal discriminator.** At α=k_F8·dt/γ≈0.42 the exact F8-translation per-step gap-fraction is 0.343;
explicit overshoots (0.42), backward-Euler undershoots (0.30), **trapezoidal/Crank–Nicolson (midpoint) lands 0.35 —
near-exact and 2nd-order** — so it is the one integrator that discriminates whether the per-bound bias is the smooth
F8-translation truncation. **CN ≡ the `-xbimplicit2` coupled star with r→r/2** (θ-method derivation: CN = backward-
Euler with dt→dt/2 in the implicit operator); since the explicit F8 impulse uses `xbParams` not `xbImplParams`,
`-xbtrap` just **halves `myoSpring` in `xbImplParams`** ⇒ the race-free CSR star kernels are **byte-unchanged** (parity
+ default byte-identity inherited). **STEP 1 (`-f8relax`, deterministic, isolated smooth F8-translation relaxation,
head frozen/fixed site, Brownian off): PASS** — trapezoidal converges **2nd-order** (err-vs-continuum ratio 0.25/dt-
halving vs explicit & BE's 0.50) and lands at the dt→0 continuum **already at dt=1e-5** (CN 1e-5 error 0.054 nm vs
explicit 0.949 = **17.6× smaller**); per-step gap-fractions **0.419/0.295/0.346 = analytic** (operator verified).
Sampling FIX vs the STROKE_TIP artifact: sample at a FIXED T_probe=2e-5 s (exact integer step-count ∀ dt), not
round(τ/dt) which lands each dt at a different sim-time. **STEP 2 (`-xbtrap`, GPU device-resident gliding assay,
paired short-window `-full -grid -lymntaylor -adppibind -ratefix -coltol10 -density1000 -matband -earlystop@0.2s`,
seed0): NULL** — at dt=1e-5 per-bound `-xbtrap` 1.39 ≈ explicit 1.34 ≈ `-xbimplicit2` 1.24 (within ~15% single-seed
SEM), and glide **still climbs 1.95× to 2.5e-6 ≈ `-xbimplicit2`'s 1.97×** (unbiased — all schemes agree ~5.4–5.9 µm/s
at 2.5e-6). **Despite STEP 1's 17× accuracy edge, CN ≡ BE in the assay** ⇒ the residual dt-bias is **provably NOT the
smooth F8-translation truncation** (else CN's edge would show), it is the **kinetics/discrete event-timing** (the
residual climb is in the BINDING COUNT avgBound, not the per-bound — which `-ratefix` already dt-flattens). **⇒
sub-step indicated** (on the collective loaded stroke/force/kinetics, the F8 stretch already stabilized by
`-xbimplicit2`). The STEP-1/STEP-2 contrast is the constructive proof of COUPLED_IMPLICIT_XB's inference. CPU≡GPU
parity confirmed on `-xbtrap` (byte-unchanged star kernels). New: `EomStabilityHarness -f8relax`, `GlidingHarness
-xbtrap`, `run_xbtrap_conv.sh`/`run_xbtrap_parity.sh`; logs `RUN_LOGS/2026-07-06_xbtrap_step{1,2}.txt`. Default
byte-identical; `BoA-v1ref` byte-clean.

## 2026-07-06 — PROBE: does making ONLY the F8-tip orientation implicit collapse the per-bound dt-bias? → STEP 1 BAILS (the bias is NOT a standalone per-motor stroke property). `STROKE_TIP_IMPLICIT_PROBE.md`.
**Driven single-motor probe** (`EomStabilityHarness -stroke`): one anchored motor drives ONE deterministic power
stroke (SPHEREHEAD+AXLOCK+DIRSWING, rate-fix on, Brownian off) into a **FREE** filament (viscous-drag-only load).
**STEP-1 GATE = BAIL:** the per-stroke net displacement is **dt-FLAT** — −1.723 nm @1e-5 → −1.760 nm @3.125e-7,
**1.02× over 32× dt** vs the ~2.2× gliding per-bound bias (0.82→1.78). A free permanently-bound head relaxes its
delivered stroke to a **dt-independent geometric endpoint** (F8→0 at equilibrium ⇒ tip=site, fixed by the
rate-fixed head orientation, not the step size). The real forward-Euler dt-dependence lives in the **transient**
(the 5·τ position swings +0.083→−0.718 nm, converging **first-order in dt**) but decays to the same endpoint ⇒
nets to zero over a single equilibrated stroke. **⇒ STEP 2 (the F8-tip implicit) NOT built** — there is no
isolated single-stroke bias to collapse (it's already its own converged value). **Verdict: NULL-in-isolation** —
a pure F8-tip implicit does not address the per-bound bias; the ~2.2× is a **loaded, non-equilibrium,
cycling-ensemble transport** effect (the gliding filament never relaxes), so the **sub-step must act on the
collective loaded transport, not one motor's F8-tip stiffness**. Answers ROTIMPLICIT's open F8-tip-fraction
question (not isolably large ⇒ favors the sub-step over the heavy dense 6-DOF rotational star); consistent with
`EOM_STABILITY_FINDINGS` (limiter = collective loaded force) + `jacobi-cobound-scheme-risk`. New code path only;
default byte-identical (the default relaxation grid reproduces `EOM_STABILITY_FINDINGS` exactly); `BoA-v1ref`
byte-clean. Log `RUN_LOGS/2026-07-06_stroke_tip_implicit_probe.txt`.

## 2026-07-06 — CONVERGED-dt (5e-7) glide vs CAPTURE RADIUS (2–6 nm): velFitX 5.4→7.2 µm/s is ENGAGEMENT-driven (avgBound 2.6→4.0), per-bound ≈2.0 RADIUS-INVARIANT; the ~2–3× skeletal-Vmax overshoot is present at every radius. Two opt-in levers (mat-shrink + early-stop), default byte-identical. `GLIDING_RADIUS_SWEEP_FINDINGS.md`.
**Two levers built (flag-gated, default byte-identical; `BoA-v1ref` untouched).** (a) `-earlystop` — host-side
batch-means-SEM monitor on the steady-window (2nd-half) velFitX (the reported metric), stops at relSEM<5% (≥5
batches ≥5 ms each; min-window 0.03 s; hard cap 0.15 s → `NOT-CONVERGED@cap`); thresholds all args
(`-esthresh/-esminwin/-escap/-esbatch/-esinterval`). (b) `-matband <excµm>` — density-preserving **subset**
shrink of the never-visited −x motor tail: seed the full bed RNG, DROP motors with anchor x<bandXlo (kept motors
keep identical draws ⇒ strict subset, same areal density); dropped motors sit a margin > motor-x-reach+capture
below the −x-most swept point ⇒ provably never in capture range; runtime edge-guard flags an undersized band.
`bXhi`/`bYhalf` kept full (ends-over-motors physics untouched). **STEP-2 gate (coltol4, seed0, dt5e-7) PASS:**
mat-shrink parity — shrunk (7532 mot) velFitX 5.578±0.543 / avgB 2.863 vs full-mat (26740) 5.678±0.679 / 2.865
(Δ0.10 ≪ SEM; the residual = motor re-indexing decorrelating the per-motor wang-hash RNG, chaotic within-SEM),
**1.7× faster** (394 vs 230 st/s); early-stop sound (metric==reported, no premature stop) but 5% unreachable in
0.15 s at avgB≈2.9 ⇒ points cap at ~8–12% single-seed relSEM (accepted). **STEP-3 sweep (coltol 2/3/4/5/6,
shrunk+ES, dt5e-7, seed0):** velFitX 5.36/6.11/5.58/6.59/7.15 µm/s (±~10%, all @cap), avgBsteady
2.61/2.79/2.86/3.28/3.98, **per-bound velFitX/avgB = 2.05/2.19/1.95/2.01/1.80 — FLAT ≈2.0, radius-invariant.**
⇒ **capture radius is an ENGAGEMENT knob (sets avgBound), NOT a per-bound-efficiency knob.** `-ktotcensus`:
meanK_tot(eng)≈1.1 pN/nm, maxK_tot 3–4, fracEng≥3.8≈0 at EVERY radius ⇒ gliding is **sub-threshold** for the
collective-load instability (explicit stable at 5e-7; residual = per-bound stroke/rotational stiffness, not load;
consistent with SEG_IMPLICIT). **coltol6 rejoins the ROTIMPLICIT Part-B ref** (velFitX 7.15≈7.3–8, per-bound
1.80≈1.78, avgB 3.98≈4.1–4.3). **Plain read:** converged-dt glide sits **above skeletal Vmax≈2.9 at every
radius** (~1.85× @2 nm → ~2.5× @6 nm); tightening the radius LOWERS absolute speed (fewer bound heads) but does
**NOT** remove the per-bound ~2.0 overshoot — the ~2–3× overshoot is a **per-bound** (rotational-stroke
stiffness) property, not an over-wide-capture artifact; narrowing the search radius won't pull converged-dt glide
into the skeletal band. Edge-guard clear all points. New: `GlidingHarness` `-earlystop`/`-matband`/`esMonitor`,
`run_radiussweep.sh`; log `RUN_LOGS/2026-07-06_radiussweep.txt`.

## 2026-07-06 — SCOPING: `-rotimplicit` feasibility (Part A code-read) + the explicit `-ratefix` convergence reference (Part B ladder). NO edits (scoping only). `ROTIMPLICIT_FEASIBILITY.md`.
**Part A (feasibility, code read) — CLEAN but a NEW dense 6-DOF star, sufficiency UNPROVEN.** The F8 tip-torque
`T_H=R_H×F8=k·R_H×(site−hc)` linearizes about the current orientation into `K_rot=k[(R_H·w)I−R_H wᵀ]` (`w=site−hc`)
— a closed-form backward-Euler solve, **no Newton**, and it **stays on-block** (F8 never couples two segments/heads
⇒ per-segment CSR Schur elimination still valid; chain torsion held explicit as in the translation star). **So NOT
FORCED/off-block.** BUT: (1) `K_rot` is dense/non-symmetric (not the translation star's isotropic `k·I`) ⇒ NOT a
scalar-per-axis divide but a dense **6×6** (center+orientation) solve; (2) the coupling `w=site−hc` **re-introduces
the translation↔rotation `c_i` offset that CANCELLED in `-xbimplicit2`** — the per-segment central unknown grows
3→6, so it's a genuinely new coupled star, materially heavier than `-xbimplicit2`; (3) scoped to the F8 tip-torque
it does NOT implicitize the F9/F10/directedSwing alignment torques (0.4/step ≈24°/stroke-step — constraint-like,
NOT small-angle-linearizable ⇒ Newton if folded in; they stay on `-alignrate`), so its ability to close the full
STROKE_DT 2.24× residual is **unmeasured**. Default `-full` path confirmed = SPHEREHEAD+AXLOCK+DIRSWING
(`GlidingHarness:244-246`) ⇒ `bondForces` (axlock) + `directedSwing` is the analyzed law. Bail NOT triggered (the
stiff term IS orientation and IS explicit). **Recommend a targeted F8-tip-only rotational isolation before building
— if the F8-tip fraction of the residual is small, the sub-step (exact nonlinear stroke, no linearization gamble)
beats the heavy star.**
**Part B (convergence reference, GPU device-resident, seed 0) — the `-ratefix` stack CONVERGES, flat by 6.25e-7.**
`-full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix`, coltol10/d1000, dt 1e-5/2.5e-6/1.25e-6/6.25e-7
(120k/480k/960k/1920k). **Per-bound drift (velFitX/avgBound) 0.822 → 1.381 → 1.642 → 1.783**, climb DECAYING
(1.68× → 1.19× → **1.086×** per refine); avgBound 2.99 → 3.69 → 3.59 → 4.11 (last step 1.145×). Both robust metrics
≤~1.15 across 1.25e-6→6.25e-7 ⇒ **flat — did NOT extend to 3.125e-7** (velFitX alone still 1.24× but that's the
single-seed-noisy metric, driven by the avgBound dip-recovery). **This EXTENDS STROKE_DT** (it stopped at 2.5e-6 /
"2.24× residual"): the residual rotational stiffness is real but **BOUNDED — a finite ~2.2× overshoot that flattens
by 6.25e-7**, not a runaway. **Converged reference: per-bound ≈1.78 (→~1.9), avgBound ≈4.1–4.3, velFitX ≈7.3–8 µm/s.**
Explicit production dt=1e-5 UNDER-shoots the converged by **~2.2× per-bound / ~3× velFitX** — that ~2.2× is the
`-rotimplicit`/sub-step target. **Fallback cost of "just run at 6.25e-7" = 16× dt ⇒ ~16× wall/sim-s** (~7 → ~118
min/sim-s; ~230 steps/s throughout). Raw `RUN_LOGS/2026-07-05_rotimpl_conv.txt`; batch `run_rotimpl_conv.sh`.

## 2026-07-05 — BUILT the DIAGONAL per-segment IMPLICIT loaded cross-bridge force (`-segimplicit`): correct + cheap + parity-clean, but a DENSE/RING-regime cure, NOT the gliding dt-fix (the low-duty gliding regime is SUB-THRESHOLD for the collective-load instability).
The production version of `-extimplicit` (EOM_STABILITY control): backward-Euler on the per-segment COLLECTIVE
cross-bridge stiffness, `q_imp,a=(q_e,a+rK_a·q_n,a)/(1+rK_a)`, `rK_a=K_tot·dt·1e6/γ_a`, `K_tot=k_s·myoSpring`
(RIGID head — the full F8 stiffness, NOT `-xbimplicit2`'s head-softened `Σ(1−B)`), body-frame rotate like
`coupleSolveSeg`. **`k_s` is free from the existing `boundSeg` CSR-inverse** ⇒ ONE new PURE per-segment kernel
(`CrossBridgeSystem.segImplicitSolve`), no matrix/iteration/atomics/KernelContext, disjoint writes.
**STEP 1 — built, default byte-identical, CPU≡GPU.** Flag-gated `-segimplicit`; wired into stepOrig + buildPlan.
The explicit baseline reproduces NUCDETACH's B-dt numbers to the digit (1.126/1.501 @1e-5, 6.477/3.365 @2.5e-6) ⇒
byte-identical default CONFIRMED. CPU≡GPU aggregate-within-SEM (v1box 20k: velFitX 2.35/2.64, avgB 1.87/2.14);
`segImplicitSolve` lowers on PTX; seed-0 deterministic.
**STEP 3 (the framing) — the instability is a DENSE-regime phenomenon.** New read-only `-ktotcensus` instrument
(per-segment `K_tot=k_s·myoSpring` vs the EOM ~1.4/~3.8 pN/nm thresholds). Under the dt-faithful LOW-duty kinetics
(`-lymntaylor -adppibind`, the dt-ladder's regime) `K_tot` is dominated by k_s=1 (~1 pN/nm), maxK_tot=3.0, and the
**blow-up threshold (3.8) is NEVER reached** (0.00 % of segments; marginal 1.4 only 1–4 %) — invariant to density
(×2) and capture radius (10→14 nm). Under DEFAULT high-duty kinetics (avgBound 15–22) the mean load is ~2.2 pN/nm
and **4–13 % of segments exceed blow-up** (maxK_tot 6–7). ⇒ the collective-load instability is barely triggered in
the low-duty gliding assay; it bites in the DENSE / high-binding / contractile / RING regime.
**STEP 2 (the test) — `-segimplicit` does NOT flatten the gliding dt-climb.** Clean ISOLATION A/B (`-segimplicit`
vs pure explicit, seed 0, dt 1e-5/2.5e-6): it RAISES binding/glide ~20 % at production dt (correct direction — the
implicit segment doesn't overshoot away from its bound heads) but the dt-climb ratio is essentially UNCHANGED
(velFitX 5.75×→5.42×, avgBound 2.24×→2.16×) — NOT dt-robust. Exactly STEP-3's prediction: the gliding regime is
sub-threshold, so curing the collective load shifts the operating point but leaves the climb, which is the
still-explicit **per-bound stroke** (COUPLED_IMPLICIT_XB's target). **Single seed** (velFitX noisy, avgBound
robust); the 3-seed ladder was NOT run because the climb didn't flatten (task's staged gate) + STEP-3 is decisive.
**FLAG — `-segimplicit` + `-xbimplicit2` do NOT compose cleanly on the segment** (both correct the center; the
retained `-xbimplicit2` head phases read the rigid q with coupled A_i/B_i ⇒ over-damps binding at fine dt, a false
"flattening" artifact). **Use `-segimplicit` STANDALONE.** **STEP 4 (dt-honest v–density) SKIPPED** (STEP 2 didn't
converge). **⇒ `-segimplicit` is the DENSE/contractile/RING-regime cure (built, validated, default-off); the
gliding dt residual still needs the cross-bridge sub-step.** No kinetics/stroke/rate change; `BoA-v1ref` untouched;
production byte-unchanged. Report: `SEG_IMPLICIT_FINDINGS.md`; logs `RUN_LOGS/2026-07-05_{segimpl_isolation,
segimpl_probe,ktot_census_clean}.txt`. New: `CrossBridgeSystem.segImplicitSolve` + `GlidingHarness` `-segimplicit`/
`-ktotcensus`.

## 2026-07-05 — dt-CONVERGENCE ARC RESOLVED: the instability is explicit-Euler on the COLLECTIVE LOADED cross-bridge force (threshold ~4 pN/nm); cure = implicit loaded force. Localized cleanly by the external-spring EOM-stability harness.

The long dt-convergence chase (glide/avgBound climbing as dt→0) is now **diagnosed to root cause with the cure demonstrated as a control.** The chase was slow because it was run through the dense gliding assay, where `net = avgBound × per-bound-drift` and *every* factor carries its own dt-dependence — binding count, dwell, tug-of-war cancellation, filament relaxation, and (separately) rate-convention bugs. That signal is unreadable. A single-motor / single-filament **external-spring EOM-stability harness** (Brownian off, deterministic, no transport inferred) stripped all confounds and gave a textbook answer.

### The finding (EOM_STABILITY_FINDINGS)
- **The explicit forward-Euler integration of the filament EOM is genuinely unstable at production dt=1e-5 under a realistic elastic load.** `dt_crit ∝ γ/(k_ext + k_F8)` — `k_eff/γ` perfectly linear in k_ext (slope 4.19e4/pN·nm⁻¹, intercept = the 1 pN/nm bound-F8): the canonical explicit-Euler load-stiffness fingerprint. Rigid pure-elastic load ⇒ marginal (ring) at **k_ext ≈ 1.4 pN/nm**, blow-up at **≈ 3.8 pN/nm**. A dense-ensemble-scale rigid load (~15–20 pN/nm) sits at α ≈ 7–11 ≫ 2 — deep in the growing-oscillation regime.
- **The stiff coupling is the collective LOADED force a segment sees — NOT any motor-internal coupling.** Three toggles localize it by elimination, all reproducing the same load-stiffness law: `-rot` (motor rotation/F9/F10/stroke free) **identical** ⇒ rotational couplings don't set it; `-nof8` shifts the intercept by exactly 1 pN/nm ⇒ F8 is a fixed additive term, not the limiter (so the per-motor coupled-F8 star removes only ~1 of ~15–20 pN/nm); `-chain 10` soft (dt_crit ~3e-4 at k_ext=0) ⇒ **the filament internal DOFs (bending/torsion) are NOT the bottleneck** — this closes jba's filament-stiffening question.
- **Cure demonstrated (`-extimplicit`, control):** making the loaded force implicit (backward-Euler operator split) removes the k_ext dependence of the boundary — the entire production dt=1e-5 column is stable at every realistic k_ext. The only residual limit is the still-explicit F8 at dt≥5e-5 (far above production).
- **CPU≡GPU:** the instability is bit-identical on both runners (ρ=−5.699 both) — a genuine property of the explicit scheme, not a runner artifact.

### What this resolves, and what it re-scopes
- **The fix target MOVED.** All prior implicit work (the coupled F8 star, `COUPLED_IMPLICIT_XB`) was **per-motor** — one head's own stretch (~1 pN/nm). The instability is the **sum** of cross-bridge loads on a segment from **all** its bound heads. So the implicit/sub-step must cover the **collective loaded cross-bridge force per segment** (the seg-gather aggregate), not per-bond. That is the real, singular remaining build.
- **Exonerated (done chasing):** motor rotational couplings, F8-alone, filament chain bending/torsion — none set the stability limit. The per-step-fraction PAIRS rate conventions (`STROKE_DT_RATE_DIAGNOSIS`, `PAIRS_RATE_AUDIT`) are a *separate, real* model-definition dt-dependence (`-ratefix` cut the residual 3.07×→~1.8×) — a keeper flag, and likely more relevant in dense/buckling/contractile assays — but they are NOT this instability.
- **The ~1.8× PAIRS residual is now understood as a MIX:** part genuine loaded-force instability (this finding), part model-changing-with-dt (rate conventions + tug-of-war/binding-count scaling). The harness separates the mechanism from the confounds.

### Caveat (do not over-read)
The harness load is a **rigid, pure-elastic worst case.** The real ensemble is compliant, mobile, and load-shared across a filament's segments, so the *effective* per-segment stiffness is softer than a literal 15–20 pN/nm anchor. So this proves the **mechanism and the ~4 pN/nm threshold** — not that every dense scene crosses it. Whether a given scene's dt-climb is this instability vs the model-definition confounds depends on its effective load. Not the "phantom" verdict (the mechanism is real and cheap to trigger), and not motor-internal.

### Standing plan
1. **Build:** implicit (or sub-step) treatment of the **collective loaded cross-bridge force per filament segment** — reusing the race-free CSR-inverse gather that already aggregates those per-segment loads. Sized against the ~4 pN/nm threshold. (`-extimplicit` is the proven control; the production version applies the same operator split to the real per-segment cross-bridge sum.)
2. **Keep** `-ratefix` (per-step→per-time PAIRS conversion) as a flagged instrument — real dt-robustness win, load-bearing for dense/ring assays, default-off pending sign-off.
3. **Then** the dt-honest velocity–density validation number (finally meaningful once the loaded-force instability is cured at production dt).
4. Deferred: the flag-cleanup pass (strip speculative side-mechanisms to a single default path, keep Brownian-off/`-cpu`/legacy-motor) once the motor + integrator restabilize.

**Bottom line:** a many-day confounded dt-chase is now a one-line stability law (`dt_crit ∝ γ/(k_ext+k_F8)`) with a demonstrated cure. The remaining integrator work is singular and scoped: make the per-segment collective cross-bridge load implicit.

## 2026-07-05 — EOM-STABILITY probe (external-spring-loaded single-motor/single-filament): the explicit filament EOM IS a GENUINE LOAD-STIFFNESS instability at dt=1e-5; the stiff coupling is the LOADED force, NOT the motor rotational couplings / F8 / chain.
A numerical-stability test (NOT a transport measurement) that strips the dense-assay confound (`net = avgBound ×
per-bound-drift`, every factor dt-dependent): single filament + one PERMANENTLY-bound motor + an external elastic COM
tether (stiffness `k_ext`, standing in for the ensemble load), Brownian OFF ⇒ deterministic. **PRIMARY (relaxation/
impulse):** settle → anchor spring at eq → displace +2 nm → release → classify the overdamped decay (MONO/RING/GROW as
`α=1e6·k_eff·dt/γ` crosses 1,2). **Headline: `dt_crit ∝ γ/(k_ext+k_F8)`** — `k_eff/γ` PERFECTLY LINEAR in k_ext (slope
4.19e4/pN·nm, intercept = the 1 pN/nm bound-F8), the textbook explicit-Euler load-stiffness fingerprint. Under a rigid
elastic load, dt=1e-5 goes MARGINAL at k_ext≈1.4 and UNSTABLE (blows) at ≈3.8 pN/nm; a dense-ensemble load ~15–20 pN/nm
⇒ α≈7–11 ≫ 2, deep in the growing regime. **Toggles localize decisively (all SAME load scaling):** `-rot` (motor body
+ fil rotation free) IDENTICAL ⇒ rotational couplings don't set the limit; `-nof8` shifts the intercept by exactly
1 pN/nm ⇒ F8 is a fixed additive term, not the limiter (so F8-implicit removes only ~1 of ~15–20 pN/nm); `-chain 10`
(F3/F4 on) same load law, chain's own bending mode soft (dt_crit ~3e-4) ⇒ filament internal DOFs not the bottleneck
(answers jba's filament-stiffening Q). **CURE (`-extimplicit`, control):** making the LOADED force implicit
(backward-Euler split) REMOVES the k_ext dependence — the whole production dt=1e-5 column is STABLE at every k_ext; only
the still-explicit F8 limits (dt_crit_F8 ~2.4e-5 ≫ production). **SECONDARY (`-drive`):** the driven stroke vs 15 pN/nm
DIVERGES explicitly across 2e-5…5e-6 (the unstable regime the map predicts), stays bounded/settles with the loaded
force implicit (residual pm-scale dt-drift = the known stroke per-step-fraction confound, STROKE_DT_RATE, not the
solver). **CPU≡GPU parity:** bit-identical (4.66e-7), both diverge identically ⇒ genuine scheme property. **⇒ the fix
is an implicit/sub-step of the COLLECTIVE LOADED cross-bridge force** (sharper than the prior arc: the limiter is the
load MAGNITUDE, not a motor-internal coupling), consistent with `substep-feasibility-verdict`. **Caveat:** rigid
pure-elastic = the worst case; the real ensemble is compliant/mobile/load-shared (softer effective per-seg stiffness),
so this fixes the threshold+mechanism; whether a scene's dt-climb is this instability depends on whether its effective
load crosses ~4 pN/nm. NOT the "phantom" verdict (mechanism genuine + cheap to trigger), NOT motor-internal. New files
only (`ExternalSpringSystem`, `EomStabilityHarness`, `run_eomstab.sh`); default byte-identical; `BoA-v1ref` untouched.
Report: `EOM_STABILITY_FINDINGS.md`; log `RUN_LOGS/2026-07-05_eom_stability.txt`.

## 2026-07-05 — Audit ALL per-step-fraction PAIRS coefficients (motor + FILAMENT), convert to per-time (`-filrate`/`-ratefix`): the residual dt-climb REDUCES 3.07×→~1.8× but SURVIVES → genuine rotational-integration stiffness (sized against ~1.8×, not the inflated 2.24×).
jba's generalization of STROKE_DT_RATE: the per-step-fraction bug is the master **PAIRS torque-law convention**, not
just the motor — so the FILAMENT's own bending/torsion coefficients carry the same ∝1/dt bias (and stiffer filaments
glide faster). **STEP 1 (audit):** confirmed the filament F3 link+bending (`chainParams[1]` fracMove=0.5) and F4
torsion (`chainParams[3]` fracMoveTorq=0.2, filTorqSpringActive=0 damped branch) ARE per-step fractions (`fracMove·
strain/(dt·mobility)`, the `/dt` cancels the integrator `·dt`) — the same convention as the motor swing/alignment;
`fracR`=0.1 is geometry (not a rate); catch g(F) is per-time. Motor structural joints (J1/J2 position, anchor)
per-step but structural (torsions OFF in gliding). **v1-INHERITED** (CLAUDE.md 5a: the `/dt`-cancels design is v1's)
⇒ a shared latent dt-convention issue, a **model improvement, NOT a v2 faithfulness divergence** (flagged,
default-byte-identical, opt-in). **STEP 2:** `-filrate` (build-time `k_eff=1−(1−k)^(dt/refDt)` on chainParams[1]/[3],
no kernel change), `-ratefix` = strokerate+alignrate+filrate. **STEP 3 (3-seed SEM, coltol10/d1000 -xbimplicit2):**
coupled residual (velFitX 2.5e-6/1e-5) **3.07× ± 0.19** (n=3); `-ratefix` **~1.8×** (velFitX 2.5e-6 6.85→3.9, −43 %;
per-seed 2.07/1.37, n=2 at 2.5e-6). **Single-seed was MISLEADING** — seed-0 looked like "filament null, residual
2.1×"; multi-seed shows a bigger, noisier reduction to ~1.8×. **⇒ converting ALL per-step fractions is a REAL
dt-robustness win (3.07×→~1.8×) but NOT dt-robust at production dt (~1.8× ≫ flat) — a genuine explicit
rotational-integration stiffness survives.** WHICH PATH: scope the rotational implicit (extend the COUPLED_IMPLICIT_XB
F8-translation star to head/segment ROTATION) or sub-step, **sized against the REDUCED ~1.8× residual, not 2.24×/
3.11×**. **Open (flagged):** the filament-vs-motor split is UNRESOLVED (allrate multi-seeded at seed-0 only; the extra
reduction may be filament OR motor-rate noise — the rate fixes raise variance; ratefix@2.5e-6 n=2, seed-2 pending);
jba's stiffer-filament-glides-faster not clearly borne out for this SPARSE single-filament axial glide (likely bites
harder in dense/buckling/contractile assays — `-filrate` is the instrument). Report: `PAIRS_RATE_AUDIT_FINDINGS.md`.
`BoA-v1ref` untouched; default byte-identical.

## 2026-07-05 — Stroke-RATE vs rotational-STIFFNESS diagnosis: the residual glide dt-climb is BOTH (a real F9/F10 alignment-RATE component + a residual rotational STIFFNESS) — the cheap rate fix is NOT sufficient → scope the rotational implicit/sub-step.
Ruling out a dt-dependent per-step-fraction RATE before scoping full-motor-implicit-vs-sub-step for the residual
per-bound glide climb that COUPLED_IMPLICIT_XB left (velFitX/avgBound 0.73→1.74→2.40 across dt 1e-5/2.5e-6/1.25e-6).
**STEP 1 (code read, decisive on mechanism):** the master torque law scales swing/alignment torques as
`|τ| = k·(θ−θ0)/((1/γ_a+1/γ_b)·dt)` ⇒ the integrator's `Δθ=τ·dt/γ` makes the `·dt` CANCEL ⇒ the relaxation is a
FIXED FRACTION PER STEP (k=0.4), dt-independent per step ⇒ the sim-time stroke/alignment DURATION shrinks ∝dt ⇒ the
**rate scales ∝1/dt BY CONSTRUCTION**. This holds for the directedSwing power stroke (swingParams[0]=0.4) AND the
F9/F10/axlock alignment torques (xbParams[2]=0.4). The catch `g(F)` is a PROPER per-time rate (`u<rate·dt`,
dt-correct) — not implicated (its dt-dependence was via F8, already fixed by the coupled star). **STEP 3 (the fix,
flag-gated, default byte-identical):** `-strokerate` (in-kernel `k_eff=1−(1−k)^(dt/refDt)`) + `-alignrate`
(build-time `xbParams[2]`, DT fixed ⇒ constant, no kernel change). dt series (coltol10/d1000 -xbimplicit2, seed 0):
**velFitX 1e-5/2.5e-6/1.25e-6 — coupled 2.02/6.28/10.06, -strokerate 2.46/4.70/9.68, -strokerate -alignrate
2.46/5.50/5.88.** Reads (vs ~20% single-seed noise, set by the 1e-5 no-op point): (1) the **stroke** rate is
NEGLIGIBLE for the steady glide (strokerate≈coupled) — the glide is set by the sustained F8 pull over the ~600-step
dwell, not the ~6-step stroke transient; (2) the **F9/F10 alignment** rate is a REAL contributor — allrate/coupled
velFitX drops 1.22→0.88→**0.58** as dt→0 (allrate 42% below coupled at 1.25e-6, beyond noise; the ∝1/dt fingerprint
grows with refinement); (3) **but even all rotational rates converted does NOT reach dt-robustness — allrate still
climbs 2.24× from 1e-5→2.5e-6** (beyond noise). **⇒ BOTH: a real, cheaply-mitigable alignment-rate component + a
residual rotational STIFFNESS** (the explicit forward-Euler rotational integration `Δθ=τ·dt/γ` of the head/segment
orientation — the `R×F8` tip torque + alignment — under-resolves the stiff orientation dynamics that set the F8-stretch
tip geometry, converging only as dt→0). **WHICH PATH: the cheap rate fix (esp. -alignrate, −40% fine-dt) is a
worthwhile REAL PARTIAL but NOT sufficient; scope the full-motor (rotational) implicit — extend the coupled star to
the head/segment ROTATION — or the sub-step.** Keep `-strokerate`/`-alignrate` as flag-gated instruments (default
byte-identical). Caveat: single-seed noise limits the precise rate/stiffness split (multi-seed would sharpen, doesn't
change the path). Report: `STROKE_DT_RATE_DIAGNOSIS.md`. `BoA-v1ref` untouched.

## 2026-07-04 — COUPLED head+SITE implicit cross-bridge (`-xbimplicit2`): PARITY-preserving, UNBIASED, best partial — but does NOT converge at dt=1e-5. → sub-step fallback.
The remaining blocker to a dt-robust physical glide is the coarse-dt F8 overshoot (numerics, not kinetics —
NUCDETACH). Tested whether solving the bound head AND its filament site TOGETHER implicitly (not head-only, which
IMPLICIT_XB_CONVERGENCE showed is site-motion-dominated and only partial) converges at production dt.
**STEP 0 (parity — the go/no-go): GO.** F8 is a zero-rest-length Hookean spring (`F=k·d`) ⇒ the implicit step is
LINEAR (no Newton); F8 never couples two segments (each head→1 segment; chain coupling is separate, held explicit)
⇒ the stiffness BLOCK-DIAGONALIZES into per-segment **STARS** (1 segment + its k_s bound heads) with a CLOSED FORM
(head isotropic sphere ⇒ scalar r_h; segment rod ⇒ diagonal drag in its body frame; the bond offset c_i CANCELS).
Expressible as per-head-pure → CSR-gather → per-head-pure over the EXISTING `boundSeg` CSR-inverse
(`segMotorOffsets`/`segMotorMyo`, the `segGather` template) — no atomics, no KernelContext, disjoint writes ⇒
race-free CSR/`-cpu` parity PRESERVED. No global/iterative solve. **STEP 1: built (`-xbimplicit2`, 5 additive
CrossBridgeSystem kernels + scratch; CPU stepOrig + GPU buildPlan default-branch, body-writes LATE per the PTX
gotcha), default byte-identical (structural + the batch's flag-off `explicit_1e-5` reproduces NUCDETACH's B-dt to
all digits), algebra EXACT vs a direct backward-Euler dense solve (max 8.9e-16 over k_s∈{1,2,3,5}, iso+aniso,
nonzero offsets), CPU≡GPU aggregate-within-SEM.** **STEP 2 (convergence, `-full -grid` coltol10/d1000/seed0):
NOT converged — still CLIMBS.** Coupled velFitX 2.020→6.279 (**3.1×**) & avgBound 2.774→3.615 (1.30×) as dt
1e-5→2.5e-6. It IS the best partial (velFitX climb: explicit 5.75× / head-only 4.08× / coupled 3.11×; avgBound
2.23×/2.07×/**1.30×**), it is UNBIASED (coupled@2.5e-6 ≈ explicit@2.5e-6 ⇒ same continuum limit), and it CONVERGES
the binding + the detach clock (avgBound nearly flat; coupled@1e-5 dwell 0.62 ms/1616 /s ≈ explicit@2.5e-6). But
making F8 **translation** implicit does NOT converge the **per-bound GLIDE** (velFitX/avgBound 0.73→1.73) — that
residual lives in the still-explicit **stroke/rotation/force** couplings. **⇒ We do NOT get the dt-robust physical
duty at production dt from the coupled implicit alone; FALL BACK to sub-stepping the cross-bridge**
(`substep-feasibility-verdict`). Keep `-xbimplicit2` (free, unbiased, parity-clean partial; can pre-stabilize the
F8 stretch inside a sub-step). STEP 3 (dt-honest velocity–density) SKIPPED (only if converged). Report:
`COUPLED_IMPLICIT_XB_FINDINGS.md`. `BoA-v1ref` untouched; default byte-identical.

## 2026-07-04 — NUCDETACH DUTY RECOVERY: the short dwell IS bind-in-ATP ejection churn (confirmed); the ADP·Pi bind-gate (`-adppibind`) makes it physical; capture radius recovers avgBound; but dt-robust-AND-physical duty is blocked on the cross-bridge sub-step, NOT the kinetics.
STEP A — the binder `bindNearest` is PURELY GEOMETRIC (only gate = FREE_BINDABLE; `nucleotideState` never read;
`BindingDetectionSystem.java:356-390`), NOT ADP·Pi-gated (jba's expectation false in code; the `cycleLymnTaylor`
"bind in ADP·Pi" comment was aspirational). The realized dense-bed dwell ~0.036 ms (25× short of the biochemical
cycle) IS the **bind-in-ATP ejection churn** the prior findings hypothesised — MEASURED and CONFIRMED: a
just-detached head (in ATP, mid ~10 ms recovery) is geometrically re-bound and ejected the SAME step; **91 % of
detach events are these ≤1-step churn ejections** (`mot.stats` 14098 releases vs 977 episode-tracker — the tracker
had a bug: it counted only FREE_COOLDOWN releases, missing every same-step bind-eject; fixed to `bs<0` + a stats
xcheck). Both `-grid` runners agree at 0.036 ms (NOT a CPU/GPU divergence, NOT a startup artifact — though the
cumulative `STATS_ROW` IS contaminated by the all-heads-start-NONE startup avalanche; new `STATS_STEADY_ROW`
snapshots a 0.2 s warmup cutoff). FIX = the ADP·Pi strong-bind gate `-adppibind` (kinParams[20], default
byte-identical; the faithful weak→strong rule): dwell 0.036→0.344 ms, detach 27857→2909 /s — a clean
ADP-release-limited clock. **Fork 1 confirmed.** BUT the gate does NOT recover avgBound/glide (churn heads were
near-zero-engagement; the gate costs ~15 % glide) — engagement is a STEP-B lever, not the dwell-fix.
STEP B — capture-radius × density × 3-seed sweep (gated, dt=1e-5, GPU `-full -grid`): capture radius raises
avgBound monotonically & non-saturating (d1000 1.12→1.70, d500 0.49→0.78 for coltol 6→12 nm); glide plateaus
~1.1–1.2 µm/s (d1000); density the stronger lever; threshold between d500 & d1000 (still above Uyeda). Duty stays
LOW & PHYSICAL across the surface (dwell 0.34 ms, detach ~2900 /s, avgBound O(1), all ~constant vs radius/density
— a per-bound kinetic clock; the ungated churn detach instead RISES with reach 19335→38698 /s ⇒ the churn is a
geometric-rebind artifact). **dt RE-CONFIRM (coltol10/d1000) — the decisive result: NEITHER config is dt-robust in
avgBound/glide** (both climb ~2×/~5.7× as dt 1e-5→2.5e-6). Ungated detach/dwell ARE dt-flat (churn clock, prior
win) but avgBound/glide climb anyway (dwell flat ⇒ it's not the detachment); gated detach/dwell are dt-DEPENDENT
(the catch `g(F)` reads the dt-dependent F8 overshoot). Root cause = the STANDING explicit cross-bridge overshoot,
independent of the cycle. `-xbimplicit` (tested) raises the coarse-dt values toward converged (+56 % glide at 1e-5)
but only shrinks the climb ~5.7×→~4.1× — the cheap local-implicit buys ~2× faithful-dt, NOT convergence; the fix
is the **sub-step / coupled cross-bridge** (`substep-feasibility-verdict`). ⇒ nucleotide-detachment fixed the
avgBound SATURATION; a dt-robust physical duty is an INTEGRATOR problem, not reachable from kinetics. Regression:
dimerglide `-cpu` PASS (the `bindNearest`+kinParams 20→21 change is byte-safe cross-harness); default byte-identical;
`BoA-v1ref` untouched. dt=5e-6 rerun of the full grid — DONE: the surface climbs UNIFORMLY (coltol10/d1000 gated
velFitX 1.17→3.84→6.48, avgB 1.52→2.65→3.45 for dt 1e-5→5e-6→2.5e-6; avgB climb-rate decelerating 1.75×→1.30×,
velFitX still fast; density gap narrows at finer dt). The 5e-6 grid spanned jba's parallel 18:19 `-xbimplicit2`
rebuild but that change is provably ADDITIVE + flag-gated ⇒ the default/`-adppibind` path is byte-identical
throughout (verified). jba's parallel COUPLED implicit XB (`-xbimplicit2`, `COUPLED_IMPLICIT_XB_FINDINGS.md`) —
the exact fix this verdict points to — is the best partial (converges binding+detach clock, velFitX climb
5.75×→3.11×) but STILL doesn't converge glide at 1e-5 (residual = the explicit stroke/force) ⇒ independently
confirms even a coupled-implicit F8 isn't enough; needs a full cross-bridge sub-step. Report:
`NUCDETACH_DUTY_RECOVERY_FINDINGS.md`.

## 2026-07-03 — NUCLEOTIDE-DRIVEN DETACHMENT (Lymn–Taylor, `-lymntaylor`) PASSES the dt-convergence non-saturation gate: the duty cycle is now biochemically set + dt-ROBUST, not a coarse-dt force-overshoot artifact.
STEP-0: the DEFAULT motor's SOLE detachment is F8-load catch-slip (`NucleotideCycleSystem.catchSlipRelease`); the
cycle is cocking-only (`cycle` writes only `nucleotideState`). Saturation mechanism: as dt→0 the F8 overshoot
vanishes ⇒ catch-slip detachment→0 ⇒ avgBound saturates (`DT_CONVERGENCE`: 17.88→216 @coltol8/d1000). STEP-1: the
`-lymntaylor` cycle (`cycleLymnTaylor`) already implements the directive — detachment = the ATP-binding NONE→ATP
Poisson transition (atpOn 2e4/s, Howard), the Guo–Guilford catch DEMOTED to load-modulation of the ADP→NONE rate
(base onADP 1e3/s × g(F)), ONE release pathway; measured skeletal rates (Howard T14-2 + Guo&Guilford 2006)
recorded in a new `params/Skeletal_Myosin` provenance file (loader = flagged follow-up). STEP-2 (the gate,
coltol8/d1000, dt 1e-5→1.25e-6, matched 1.5 s): **PASS** — LT avgBound stays O(1) (1.26/1.11/3.11/0.92, ~50–200×
below the default's 17.88/69/158/216 at matched dt, NO saturation) and detach-rate is dt-robust FLAT ~25000/s
(26136/22704/25541/26636); dwell flat ~0.038 ms. (Velocity single-seed-noisy; the two finest dts drifted off-bed
— a coverage caveat only, not the gate.) STEP-3 (velocity-density, native reach, dt=1e-5, 3 seeds): correct
monotonic threshold+rise, glide ∝ avgBound, threshold ~500–1000 (above Uyeda 100–300), reaches ~1.6–1.7 µm/s
@d2000 (low edge of skeletal 1.5–4, at 100× drag), duty <1 % (low/physical, honestly duty-starved) — the standing
binding-density / transport / drag residuals, NOT worsened. v1 note: v1 has NO nucleotide-driven detachment
(cocking-only cycle + catch-slip release, = the v2 default) ⇒ this is a MEASURED-biochemistry model improvement,
not a v1 faithfulness fix. Added a measurement-only `STATS_ROW` (detach-rate/dwell); `-lymntaylor` default-off ⇒
non-LT paths byte-identical; no stroke/force change; `BoA-v1ref` byte-clean. Report: `NUCLEOTIDE_DETACH_FINDINGS.md`.

## 2026-07-03 — dt-convergence at the capture-radius split (coltol8/d1000, the widest BoA↔v2 split): v2's per-bound drift CRATERS 16× as dt→0 — it does NOT rise toward BoA (FORK BRANCH 2).
Refined dt 1e-5→5e-6→2.5e-6→1.25e-6 at the widest split (coltol8, d1000, -full, seed 0, matched 1.5 s sim time).
per-bound drift 0.0900→0.0193→0.0077→0.0056 (16×, monotone) while avgBound EXPLODES 17.88→69.10→157.93→216.27
(12×, toward saturation, increments decelerating) and net glide CONVERGES 1.609→1.332→1.213→1.201 (→~1.20 µm/s).
On the drift-vs-avgBound plane v2's dt=1e-5 point sits ON its own Jacobi engagement curve (17.88/0.090 ≈ r8
18.64/0.081), and dt-refinement continues DOWN that curve toward drift→0 — it passes straight through avgBound≈20
still falling while BoA's curve turns UP there (0.248) ⇒ v2 moves FURTHER from BoA, not toward it. FORK BRANCH 2:
the converged dense-regime answer is genuinely LOW; the split is NOT a pure Jacobi-staleness artifact that a
Gauss–Seidel/fresh-force gather would "fix" up to BoA's 0.248; BoA's high coarse drift is the outlier. ⇒ DO NOT
scope the fresh-force seg-gather to match BoA yet — BoA must itself be dt-convergence-checked first (SPECIFIED:
coltol8, d1000, dt 1e-5/5e-6/2.5e-6, 3 draws, CAPTURE_RADIUS_REPLICATE protocol — BoA-CC follow-up, not run from
SoftBox). CAVEAT: the dt→0 limit is a near-SATURATED over-bound state (~220 bound), so per-bound drift is only
comparable at MATCHED engagement — the genuine same-engagement scheme difference (ENGAGEMENT_MATCHED: v2 0.081 vs
BoA 0.248 @ avgB≈19) STANDS and convergence gives it no support in BoA's favor. Single-seed decisive (16× monotone
≫ seed SD ±0.01; seed 0 reproduces the coltol8 3-seed coarse mean); 4-dt×3-seed grid flagged optional, not run.
Measurement-only (existing params + `-dt`); seg-gather/release/stroke/model byte-unchanged; default byte-identical;
CPU≡GPU/CSR untouched; BoA-v1ref byte-clean. Report: DT_CONVERGENCE_SEGGATHER_FINDINGS.md; raw
RUN_LOGS/2026-07-03_dtconv_coltol8_dt{1e-5,5e-6,2.5e-6,1.25e-6}_seed0.txt.

## 2026-07-03 — Stroke-fidelity census vs engagement (v2, radius knob coltol4→coltol8 @ d1000, 3 seeds): v2's neck-powerstroke STAYS FAITHFUL as engagement rises ⇒ the capture-radius split is the seg-gather, NOT the motor.
New read-only `-strokecensus` (per-bound-head neck-powerstroke pose fidelity: swing axial fraction, barbed-sweep
fraction, stroked fraction, swing angle + histogram; default-off byte-identical; CPU≡GPU aggregate). Radius knob
(the split's own axis) LOW coltol4 (avgB 10.08) vs HIGH coltol8 (avgB 18.64). **Every stroke-DIRECTION metric is
FLAT** across the +85 % engagement rise that craters per-bound drift −78 %: barbed-sweep **0.997→0.998** (≈BoA's
99.6 %), swing axial frac **0.956→0.957** (>92 % of stroked heads in the top 0.9–1.0 axial bin at both), swing
angle **57.4→57.1°**, stroked frac 0.925→0.921, mhat pole **~49→49 %**, roll ŝ **~50→50 %**; per-stroke **|force|
even RISES +15 %** (3.08→3.55 pN). The one moving transport metric — signed impulse/head **−56 %** (the task's
pre-flagged "motor effect") — **decomposes into non-stroke causes:** co-bound signed-force cancellation (|force|↑,
signed↓ = the gather) + catch-slip dwell-halving (0.87→0.45 ms, kinetics on stretched binding). **Both move
IDENTICALLY in BoA** (signed 0.238→0.136, |force| 2.90→3.18, dwell 0.640→0.244) **whose net RISES** ⇒ metric ③ is
NOT the code-differentiator. **⇒ FORK BRANCH 1 (clean confirmation): jba's motor/stroke-degradation hypothesis is
RULED OUT; the `ENGAGEMENT_MATCHED` seg-gather attribution STANDS.** Also explains `-freshread` deepening the
collapse (more retained heads → more gather cancellation, stroke unchanged). The split is now triple-excluded: not
release timing (`FRESHREAD_AB`), not the engagement confound (`ENGAGEMENT_MATCHED`), not the motor (this census) —
it is the co-bound seg-gather (Jacobi/GS) load-sharing; the fresh-force gather remains the warranted DESIGN task
(planner sign-off, risks CSR/`-cpu` parity). Validation: GRID_ROW byte-identical with/without the flag; CPU≡GPU
census aggregate (barbed 0.9962 vs 0.9961); no stroke kernel touched (bail condition never triggered). BoA
barbed/axial-vs-engagement run SPECIFIED not run (BoA-CC). `BoA-v1ref` untouched. Report:
`STROKE_FIDELITY_CENSUS_FINDINGS.md`; raw `RUN_LOGS/2026-07-03_stroke_fidelity_census.txt`.

## 2026-07-03 — Engagement-matched BoA↔v2: the capture-radius split is a GENUINE seg-gather co-bound load-sharing difference, NOT the engagement confound.
At matched avgBound + matched knob (radius r8, avgB≈20) v2 per-bound drift **0.081** vs BoA **0.248** (≈3×; **5.5×**
after the engagement correction, which points the WRONG way — v2 binds fewer heads at r8 and its own drift curve
falls steeply, so projecting to BoA's avgBound *widens* the gap) ⇒ **confound REJECTED** (branch 1). Both codes
span the same avgBound range (~10–20) via radius, so "same curve, different ranges" is inapplicable; within it the
drifts cross at the anchor and split — v2 craters monotonically (0.380→0.081 over r4→r8), BoA flat/U-shaped
(0.259→0.167→0.248). **Branch 2 CONFIRMED** = the accepted 4b-iv Jacobi(v2-GPU stale)/Gauss–Seidel(BoA-CPU fresh)
parallel-scheme residual **localized to the co-bound seg-gather**: quiescent sparse, **grows with co-bound
density** (dense-regime ring risk; cf. `jacobi-cobound-scheme-risk`). **Branch 3 CONFIRMED** = v2's radius and
density knobs trace *different* drift-vs-avgBound curves (radius steeper, 0.48× density at avgB≈19; this-session
probe: density d2000 drift 0.152 vs radius r8 0.081 at avgB≈19) — radius carries per-head stretch/dwell geometry
beyond count (v2-internal, not the code split). Overlay assembled from the two protocol-matched 3-seed sweeps
(`PHASE2_SPEED_LEVERS` density arm, `PHASE2_CAPTURE_RADIUS` radius arm) + `CAPTURE_RADIUS_REPLICATE` (BoA radius),
density arm re-confirmed this session (probe seed 0, d1000 drift 0.205≡0.206). **Scope-flag:** a fresh-force
(Gauss–Seidel) seg-gather is now a warranted DESIGN task (risks the race-free/CSR/`-cpu`-parity property) — planner
sign-off, NOT started. BoA density-@avgB20 gap SPECIFIED not run (BoA-CC follow-up). Measurement-only; default
byte-identical; `BoA-v1ref` untouched. Report: `ENGAGEMENT_MATCHED_FINDINGS.md`; raw
`RUN_LOGS/2026-07-03_engagement_matched_probe.txt`.

## 2026-07-03 — RELEASE-PATH FULL AUDIT (v2 vs active BoA): cycle state-machine, rates, cadence, load-gate, cocking, catch-slip, break-cap, step-order. Read-only; no code changed, no runs. `BoA-v1ref` untouched.
Traced the ENTIRE motor release path end to end (the reconcile doc's "both cocking-only" was an assertion; the
timing audit checked only catch-slip force currency — this verifies everything else). **Cycle is bit-identical:**
same states/order (NONE→ATP→ADPPi→ADP→NONE), same 6 rates (2e4/100/100/1e4/0/1e3 — v2 `MotorStore.setNucParams`
== BoA `Env.java:1329-1345`), same **EVERY-STEP** `rate·deltaT` cadence at dt=1e-5 (**the biochemDeltaT/
biochemCheckInt cadence gates actin/monomer/crosslink biochem, NOT the motor** — `BoxOfActin.java:1623` runs
`biochemStart` unconditionally, `MyoMotor.biochemStep` uses `Env.deltaT`; candidate cadence divergence RULED OUT),
same 10-window boxcar ADP→NONE load-gate (forceDotFil avg≤0 v2 / >0-blocks BoA — sign+threshold+window match),
same `isCocked=!isADPPi`, and **BOTH cocking-only** (v2 `cycleAtpDetach` gated `CONFIG1&&ATP_RELEASE` off; BoA
`biochemStep` never calls `release()` — every branch traced). Catch-slip formula+constants bit-identical (kOff100/
0.92/0.08/2.5nm/0.4nm). **Beyond the KNOWN release-force lag (#2, already refuted as the split cause), found 3 NEW
release-adjacent diffs, all from v2 registering the load at step END:** #3 the cycle's ADP→NONE load-gate reads
STALE (v2)/FRESH (BoA); #4 the stroke reads THIS-step post-cycle state (v2)/PRIOR-step state (BoA) — opposite
currency direction, <1% of steps; #5 v2 releases-before-binds vs BoA binds-before-releases. **#3+#4 flip WITH
`-freshread`** (stepFresh moves registerForceDot before cycle+catch), i.e. bundled into the already-refuted A/B ⇒
NO new isolable capture-radius-split candidate; residual stays the **Jacobi seg-gather co-bound load-sharing**.
Plus known break-cap default (#1 OFF v2/ON BoA, 12 pN, wrong-direction+rarely-fires) and inert/deliberate items
(#6 `inRigor` bypass = ProteinNode-only, never gliding; #7 `bindTimer`-race omission = deliberate). Which currency/
default/timing is correct = planner call. Report: `RELEASE_PATH_FULL_AUDIT.md`.

## 2026-07-03 — `-freshread` A/B: fresh release-force does NOT close the BoA/v2 capture-radius split — it collapses v2's net HARDER. Stale release-force REFUTED as the cause. Fixed the orphaned stepFresh; no promotion. `BoA-v1ref` untouched; default byte-unchanged.
Tests whether making v2's catch-slip read the FRESH (this-step) cross-bridge load closes the dense-regime
capture-radius sign split (`RELEASE_FORCE_TIMING_AUDIT.md`). **STEP 0 provenance — jba's recollection CONFIRMED.**
`-freshread`/`stepFresh` = the 4b-iv release-read reorder (`35ef395`+`1c28b3b`, 2026-06-15), v2 analog of BoA's
2026-06-04 GPU release-read reconciliation; left gated because at 4b-iv it shifted the MECHANISM (assist +0.43 pp)
but NOT the net residual. Then the **f̂-motor promotion** (`a5c67cd`, 2026-07-01) wired `directedSwing` into
`stepOrig` ONLY — `stepFresh`/`FRESH_READ` were dropped forward (orphaned, strokeless). **Scoped fix (jba-approved):**
the only real gap for the default motor was the missing `directedSwing` task (J1-off already shared via
`sc.jointParams===mot.jointParams`; SPHEREHEAD+AXLOCK via shared `sc.xbParams`) — added it to `stepFresh` + the
`FRESH_READ` buildPlan, identical to `stepOrig`. Validated CPU≡GPU **bit-identical** on the fixed `-freshread`;
`stepOrig` byte-unchanged (stale col reproduces PHASE2). **STEP 1 A/B (GPU `-full` d1000, 150k, LONG_ROW, drift =
|v_axial|/avgBound):** stale drift 0.384/0.205/0.090 (4/6/8 nm, ≡ PHASE2) → **fresh 0.244 / 0.030 / reversed**; net
−3.72/−3.02/−1.61 → **fresh −2.51 / −0.51 / +2.1(coverage-violated, sign-reversed)**. **Fresh does NOT flatten v2
toward BoA's rising-net shape — it makes v2's collapse STEEPER** (net → 0 @6 nm, reverses @8 nm), while RAISING
avgBound (14.8→16.7) — a deeper co-bound tug-of-war (the `IMPLICIT_XB_CAPTURE_RADIUS` mechanism: more retained heads
→ more cancellation → less net). Notably **stale v2 AGREES with BoA at 6 nm** (both net ≈ −3.02); fresh breaks it.
**⇒ FORK: the split does NOT close; the 1-step stale release-force is REFUTED as the cause** (empirically refutes the
`RELEASE_FORCE_TIMING_AUDIT` candidate) — the residual is the **seg-gather co-bound load-sharing**, untouched by
release currency. **No promotion** (also fresh degrades glide ⇒ would wreck the assay). Next cut (planner):
engagement-matched BoA↔v2 + a fresh-force seg-gather (CSR-parity design task). Kept: the `stepFresh` faithfulness fix
(default-OFF) as a now-valid A/B instrument. Caveat: `stepFresh` bundles the full v1-order reorder (release currency
+ force-before-biochem), so not a currency-only isolation — but the direction (collapse away from BoA) is robust.
Report: `FRESHREAD_AB_FINDINGS.md`; raw `RUN_LOGS/2026-07-03_freshread_ab.txt`.

## 2026-07-03 — RELEASE-FORCE TIMING AUDIT (v2 vs active BoA): they DIFFER — BoA reads FRESH, v2-default reads STALE (1-step lag). Code-read only; nothing changed; `BoA-v1ref` untouched.
The catch-slip/break-cap read the cross-bridge load at DIFFERENT points in the step. **Active BoA** (CPU
`MyoFilLink.step`: `addForces`→`ckRelease` in the same step, `:187`→`:193`, so `ckRelease` reads the `forceDotFil`
`addForces` just wrote at `:267`; GPU reconciled 2026-06-04 — `bridgeMotorForceWriteback`→`ckRelease`,
`GPUMoveThing.java:6216`) samples **this step's** force = the same evaluation that integrates ⇒ **0-step lag,
FRESH**. **v2 default** (`GlidingHarness.stepOrig`: `catchSlipRelease` at `:45` reads `mot.forceDotFil` written by
`registerForceDot` at `:107` of the PREVIOUS step) samples a force one full integrate behind the force actually
moving the bodies this step (`bondForces`@`:84`) ⇒ **1-step lag, STALE** (bit-identical on both v2 runners ⇒ scheme,
not runner). **Refines the prior scheme-read:** BOTH codes are Jacobi at the position level (BoA = force-waves →
`gatherForces` → one `moveThings`@`BoxOfActin.java:1564`; v2 = forces → integrate) — neither catch sees a co-bound
neighbor's *within-step* motion. The difference is the release-force **currency/lag**, NOT Gauss–Seidel sequential
per-object updates (corrects `IMPLICIT_XB_CAPTURE_RADIUS_FINDINGS.md`'s "BoA Gauss–Seidel/fresh" framing). BoA
itself flagged the identical 1-step lag as a defect and removed it from its GPU path; v2's default IS the pre-fix
structure. **Candidate fix already exists** — `-freshread`/`stepFresh` (both runners) computes force + `register`
BEFORE release (integration last, forward-Euler unchanged), == BoA's own reconciliation; compatible with the
race-free CSR gather (pre-release bound set, no atomics) and `-cpu` bit-identity (order-independent wang-hash draws).
Effect-size caveat: BoA judged the lag "harmless at dt=1e-4"; v2 runs dt=1e-5 (per-step drift smaller) — the split
is dense-regime only, so a `stepOrig`↔`stepFresh` A/B at the split's capture radii/density is the cheap decisive
cut (NOT run — planner call; flipping the default re-baselines the promoted glide/avgBound). Report:
`RELEASE_FORCE_TIMING_AUDIT.md`.

## 2026-07-03 — IMPLICIT CROSS-BRIDGE @dt=1e-5 vs the dt-refined target: PARTIAL (head-only insufficient) + the gliding assay is UNBRACKETED-dt-sensitive. Measurement only; default byte-identical; `BoA-v1ref` untouched.
Goal: does the banked `-xbimplicit` (F8 spring implicit on the bound-head translation, already wired CPU+GPU)
reproduce the dt-refined converged binding at production dt? Promoted default motor, GPU `-full` d1000, LONG_ROW.
**STEP 0 — which spring overshoots? F8, NOT J1 (gate PASSES).** The catch-slip release + 12 pN cap read F8 ONLY
(`forceDotFil=Dot(F8,segU)`, `forceMag=|F8|=myoSpring·dist`); J1/J2 are dt-robust fracMove PAIRS pins that don't
feed the release (and the promoted default runs the J1 angular converter OFF). F8 is Hookean with overshoot
r=k·dt/γ_head≈0.531@1e-5. Census (`-stretchcensus`) confirms empirically: F8 ext/var/|fdFil|/dwell all move
monotonically with convergence (ext 5.89±5.01/dwell0.63@exp1e-5 → 4.30±2.00/1.86@exp5e-6). **NB the banked solve
is F8-on-head, NOT "J1" (that JOURNAL label was a mislabel) — so it targets the right spring; the STOP condition
didn't apply.** **STEP 2 — PARTIAL.** implicit@1e-5 = avgBound **21.5**, glide **2.80**, drift 0.130 — between
explicit@1e-5 (14.8/3.02/0.204) and the target (56.7/2.20/0.039), closing only ~8–16 % of the gap. **Cause
(predicted by the banked result):** head-only implicit fixes only the head's own overshoot, but the gliding
cross-bridge stretch VARIANCE is dominated by the fast filament (site) motion (implicit cuts the census ext-SD
only −3 % vs convergence's −60 %) ⇒ site-explicit is the bottleneck. **STEP 2b — the target 56.7 is NOT
converged.** dt=2.5e-6 overshoots it: avgBound **99.3**, glide **1.72**, still climbing (avgB roughly doubles per
dt-halving, 14.8→56.7→99.3; glide 3.02→2.20→1.72, no plateau through 2.5e-6). ⇒ the honest converged glide is
**≤1.7 and unbracketed, NOT 2.2/3.0**; the gliding assay is FAR more dt-sensitive than the ±3 % dt-faithful
envelope implied, and production dt=1e-5 is a heavily under-bound operating point. **STEP 3 — cheap but
insufficient:** implicit adds ~3 % per-step (2 kernels, `xbSnap`+`xbImpl`) ⇒ ~free, does NOT eat the dt-refine
savings — but also doesn't deliver them (only ~8–16 % converged). Stable (fullMat=YES, no NaN). CPU≡GPU not
re-run (stopped early; race-free by construction). **VERDICT: NO converged glide at production dt from the
head-only form; needs the COUPLED head+site implicit solve or the SUB-STEPPED cross-bridge**
(`substep-feasibility-verdict`). Seeds 1/2 stopped early (approach being reconsidered) — seed-0 decisive. Report:
`IMPLICIT_XB_CONVERGENCE_FINDINGS.md`.

## 2026-07-03 — CAPTURE-RADIUS sign-split unification under implicit XB: PREMISE NOT MET (not run) + the scheme read. Analysis only; `BoA-v1ref` untouched.
Follow-on gated on `-xbimplicit` converging; it only PARTIALLY converged (21.5 vs ≥99) ⇒ per its own gate, the
radius sweep is **not run** (a 16 %-converged solve can't test "convergence unifies," and head-only implicit
doesn't even touch the named mechanism — it leaves the seg-gather stale). **Scheme read (as requested):** v2 is
**Jacobi/one-step-stale** — `bondForces` evaluates each bound head's cross-bridge force ONCE from start-of-step
head+filament positions; the head integrates; then the filament `segGather` sums those SAME start-of-step
seg-side forces (no head sees another's within-step update) — **identical on both v2 runners** (CPU `stepOrig` ≡
GPU TaskGraph, same CSR gather) ⇒ the BoA↔v2 split is **scheme (BoA Gauss–Seidel/fresh vs v2 Jacobi/stale), NOT
runner.** **Key insight (the confound):** "convergence" does two opposing things to per-bound drift — less
staleness (↑drift, the hoped effect) vs MORE bound heads (↓drift, tug-of-war) — and the data show the SECOND
dominates: at 6 nm, refining dt drives v2 drift 0.206→0.130→0.039→0.017 (1e-5→impl→5e-6→2.5e-6), monotonically
AWAY from BoA's flat ~0.167, not toward it. So convergence DEEPENS v2's tug-of-war ⇒ the sign split is **most
likely a genuine scheme difference (Jacobi vs Gauss–Seidel co-bound load-sharing), not an under-convergence
artifact** — and it can't be cleanly tested by dt refinement (convergence is confounded with engagement). Clean
test = match ENGAGEMENT (avgBound) across codes, and/or a fresh-force v2 gather (a design task, risks the
race-free CSR/`-cpu` parity). **Escalated:** v2's stale-force co-bound scheme over-produces tug-of-war — a
dense-regime fidelity risk for the flagship RING (many co-bound motors/filament); settle before trusting ring
quantitatives. Report: `IMPLICIT_XB_CAPTURE_RADIUS_FINDINGS.md`.

## 2026-07-02 — VISCOSITY DIAGNOSTIC: the ~3 µm/s glide is CYCLE / TUG-OF-WAR-limited, NOT drag-limited. New flag-gated `-aeta`; default byte-identical, no promotion (η is physical, not a speed dial).
The question: is ~3 the motor's real V₀ (cycle-limited) or is filament drag capping it (drag-limited, true V₀
higher)? GPU `-full` d1000, LONG_ROW net v_axial + avgBound. Filament-only drag scale (`-aeta`, FDT-consistent
`applyAeta`; default 0.1 ⇒ r=1.0 no-op byte-identical — reproduces the −3.023/14.77 baseline exactly).
**STEP 1 — η sweep at production dt=1e-5 (3 seeds):** η↓ does NOT raise the glide, it COLLAPSES it — net
3.07→0.96→~0.25 (µm/s) as aeta 0.1→0.05→0.025, driven by an avgBound crash 14.9→4.2→0.15 (×0.25 ≈ unbound,
axialFrac 0.12–0.64). This is the **whip / dt-instability** regime: at fixed dt=1e-5, `dt/aeta`↓ ⇒ the filament
translates too far/step ⇒ cross-bridge overshoot ⇒ force-dependent release detaches heads (`dt-faithful-ceiling`;
rescaling drag down ≡ raising the step). Jitter speed RISES 6.3→16.0. A drag-limited glide would rise as η
falls — this collapses ⇒ NOT drag-limited. **STEP 2 — dt-co-scaled (hold `dt/aeta`=1e-4 ⇒ constant stability;
seed0):** with the step faithful, ×0.5 binding recovers to **28.4** (~2× baseline 14.8) — proving the STEP-1
collapse was numerical — yet net glide is **FLAT (+14%)**, per-bound drift HALVES (0.204→0.121). `v ∝ η^-0.18`
(drag-limited = `η^-1`, +100%; edge-violated ×0.25 at most `η^-0.31`) ⇒ the tug-of-war invariant
`net ≈ avgBound × per-bound-drift` holds under the viscosity knob — lower drag recruits more co-bound heads that
CANCEL. **STEP 3 — dt-only control (aeta=0.1, dt 1e-5→5e-6):** disentangles the confound and exposes a
convergence issue — refining dt 2× at fixed η QUADRUPLES avgBound (14.8→**56.7**) and LOWERS the glide to
**2.20** (drift craters 0.204→0.039): the operating dt=1e-5 under-binds ~4× (overshoot detaches ¾ of physically-
bound heads), and MORE heads ⇒ LOWER glide (tug-of-war, unmistakable). **VERDICT: cycle / tug-of-war-limited,
NOT drag-limited** — ~3 is the real operating glide, no faster V₀ behind filament drag; viscosity acts mainly by
changing engagement, which the co-bound cancellation eats. **5th independent lever** (after density/angle/rate/
radius) to hit the same ceiling. **Planner flag (dt-convergence study):** dt=1e-5 is NOT avgBound-converged; the
converged glide is ~2.2 not 3.0 (deeper tug-of-war); prior Phase-2 lever numbers sit at this under-converged
point (conclusion strengthens, absolute is dt-sensitive) — needs the cross-bridge sub-step for a converged
re-baseline. No NaN; 1 coverage-violation (×0.25 faithful, excluded). `BoA-v1ref` byte-clean; default byte-
identical; no release/stroke/kinetics change. Report: `VISCOSITY_DIAGNOSTIC_FINDINGS.md`.

## 2026-07-02 — CAPTURE RADIUS (`myoColTol`) sweep: NOT a distinct speed axis on v2 — REFUTES BoA's CPU +48%. Larger radius LOWERS net glide (per-bound drift collapses); it walks the tug-of-war ceiling STEEPER than density. New flag-gated `-coltol`/`-stretchcensus`; default byte-identical, no promotion.
GPU `-full` d1000 150k, 3 seedable mat draws, LONG_ROW net v_axial + avgBound + per-bound drift + a read-only
bound-population census (`-stretchcensus`: extension/per-head axial force/dwell). BoA (CPU, single-run 5/6/7 nm)
claimed avgBound↑ with per-motor drift FLAT ~0.19 ⇒ net **+48%** and hypothesised `myoColTol` as the master
engagement knob that beats the tug-of-war. **On v2 it's the OPPOSITE.** **STEP 1 (4/5/6/7/8 nm):** net |v_axial|
FALLS monotonically **3.83→3.42→3.07→2.38→1.51** (−60%) while avgBound RISES 10.1→18.6 (+85%) and per-bound
drift COLLAPSES **0.380→0.081**. The 6nm/d1000 point (drift 0.206, avgB 14.9) reproduces the density-sweep d1000
anchor exactly (cross-consistent). **STEP 2 overlay (drift vs avgBound):** radius & density BOTH lie on falling
curves that CROSS at the shared 6nm/d1000 anchor; below it radius has HIGHER drift, above it LOWER — radius's
curve is **STEEPER**, so it does NOT hold drift flat (BoA's claim), it collapses drift FASTER than density ⇒
`myoColTol` is **not a distinct axis**, it plunges down the SAME tug-of-war ceiling. Net turns over below 4nm
(smallest = fastest). **Sign-flip origin:** the accepted 4b-iv parallel residual — BoA-CPU (Gauss–Seidel, fresh
forces) has mild co-bound resistance; v2-GPU (Jacobi, stale forces) resists harder, and more co-bound heads
(larger radius) amplify it (v2 avgBound lower at every radius yet steeper drift decay). **STEP 3 (4 vs 8nm
geometry):** ext +13% (5.54→6.27nm), |fdFil| +15% (3.08→3.55pN) — a REAL mild stretch/force shift (not
count-only) — BUT dwell −49% (0.87→0.45ms) and **signed** fdFil stays ~0.14pN ≪ |fd|3.3 (axial CANCELLATION) ⇒
the stretched heads add opposing force, not thrust, and detach 2× faster ⇒ drift falls. Geometry works AGAINST
transport; dominant story is COUNT, extra count anti-productive. **STEP 4 (density-response per radius):** the
saturation/peak density shifts DOWN with radius — 4nm still rising at d1500 (peak >1500), 6nm peaks ~1500, 8nm
already falling by d500 (peak <500); 8nm/d500 avgBound 14.2 ≈ 6nm/d1000 14.9 (same duty, half the density).
**Faithfulness:** `myoColTol` sets WHERE the density-response saturates, not a speed; NO radius reproduces the
experimental (density,speed) jointly (8nm's low sat-density has ~1.5 speed; 4nm's 4.51 near honest-v1 4.6 but
sat >1500 & below physical head-reach) — the v2 parallel co-bound penalty means high engagement (low sat-density)
craters speed. Set `myoColTol` by binding geometry + plateau density, NOT net speed; 6nm already high-engagement,
no speed argument to raise it. **Validation:** default byte-identical (override only when COL_TOL≠0.006); CPU≡GPU
bit-identical at the widened reach (coltol 8, d250, 5000 steps); race-free (host-side census). `BoA-v1ref`
byte-clean; no release/stroke change; NO promotion (adoption = separate signoff). → `PHASE2_CAPTURE_RADIUS_FINDINGS.md`.

## 2026-07-02 — SPEED LEVERS: none of density/step/kinetics gives a defensible path to skeletal 5–8; net glide is tug-of-war-pinned ~3. Two flag-gated sweeps (`-neckangle`, `-ratescale`); default byte-identical, no promotion.
GPU `-full` 150k, 3 seedable mat draws each, LONG_ROW net v_axial + avgBound + per-bound drift (mean±SD).
**STEP 1 (density, no code):** net v_axial rises to a broad shallow peak **d1500 ~3.28** then turns over at
d2000 (~3.08); **d1000 (3.07) is ~7% below peak, NOT past it** — density buys only +7%, peak ≪ 5–8. Per-bound
drift falls **monotonically** 0.295(d250)→0.159(d2000) = the tug-of-war signature. Op-density for 2/3 = d1000
(reference, near-peak, cleaner per-head signal). **STEP 2 (neck angle `-neckangle`, step size):** **FLAT** —
60→70→80° moves net v_axial ~0 (3.07/3.08/3.07), avgBound + per-bound unchanged (0.206). Step size is NOT a
lever here (consistent with stroke-effective-lever=HEAD_LEN / θ-is-a-non-lever). **Settles the 60/70 confound:
neck angle ≈ 0 of the BoA(−3.96,70°)/v2(−3.0,60°) gap** ⇒ the gap is CPU(BoA)/GPU(v2) + the 4b-iv ~0.87×
parallel residual, not angle. 70° defensible but speed-neutral; 80° a null probe. **STEP 3 (cycle rate
`-ratescale` = ×kOff + all nucleotide rates, at d1000/70°):** per-bound drift RISES +33%(×2)/+76%(×4) — V₀∝
detach-rate holds at the single-head level — **but avgBound COLLAPSES 14.9→12.0→8.8**, so **net rises only
+6%(×2)/+8%(×4)**. The "biologically-legitimate 2×" ≠ a 2× glide; the duty×turnover product is near-conserved
(the default+LT lesson, quantified). ×2 plausibly fast-skeletal, ×4 a probe — but neither reaches ~6.
**Synthesis:** net ≈ avgBound×per-bound ≈ **3.0–3.4 across ALL levers** — no defensible single lever or stack
reaches 5–8 (even optimistic d1500×rate2 ~3.5; the honest v1 net is ~4.6, not 8). Reaching higher needs
BREAKING the duty×efficiency tradeoff (the HEAD_LEN-limited step, or less co-bound resistance) — not a
density/angle/rate knob, out of scope. New flags default byte-identical; `BoA-v1ref` clean; no release change;
NO promotion (adoption = separate signoff). → `PHASE2_SPEED_LEVERS_FINDINGS.md`.

## 2026-07-01 — RELEASE RECONCILE (v2 ↔ active BoA): the −3.96/−3.0 gap is NOT release — v2 default is ALREADY catch-slip-only; BAIL invoked (STEP 1/2/3 moot). Audit only, no code changed, no runs.
Documented both release pathways EXACTLY (STEP 0). **v2 DEFAULT motor** (no flags = SPHEREHEAD+AXLOCK+DIRSWING):
the ONLY detachment is `catchSlipRelease` on `forceDotFil` (kOff 100/s, αCatch 0.92, αSlip 0.08, xCatch 2.5nm,
xSlip 0.4nm, kT 4.116e-21J); the plain `cycle()` writes only `nucleotideState` (**cocking, never boundSeg**),
`directedSwing` reads state only to pick the 0°/60° rest angle (torque, not release); break-cap 12pN is **OFF**
by default; `cycleAtpDetach` (ATP-binding=detach) is CONFIG1-only, not the default. **Active BoA (`~/Code/BoA/`,
the −3.96 producer):** `MyoFilLink.ckRelease` catch-slip on `forceDotFil` with **bit-identical** params;
`MyoMotor.biochemStep` cycle is cocking-only (`dissociateADP` sets state NONE, does NOT `release()`); neck stroke
0→**70°** (`BoxOfActin.java:565`); break-cap 12pN **ON**; refractory 1e-5s. **⇒ the hypothesized v2
nucleotide-driven detachment DOES NOT EXIST** — both motors are catch-slip-on-F8-load only, cocking-only cycle,
identical Guo–Guilford constants. Per the task's explicit bail clause, **STEP 1 (`-v1release`), STEP 2 (d1000
three-way), STEP 3 (bound-in-ATP) are MOOT** and were not built/run. Two real differences remain, neither a
nucleotide release: (1) **neck 70°(BoA) vs 60°(v2)** — the flagged confound + leading suspect for the duty/speed
gap (larger stroke → different cross-bridge force → longer catch lifetime → higher avgBound), **change next, one
at a time**; (2) break-cap default (BoA ON / v2 OFF) — wrong-direction for the duty gap (BoA detaches more yet
avgBound higher) + rarely fires (peak ~6pN ≪ 12pN), already reachable via `-faithfulrelease`. Caveats: BoA −3.96
is **CPU** (its f̂-swing is CPU-only; BoA GPU still legacy-F9), v2 −3.0 is **GPU**; plus the accepted 4b-iv
~0.87× parallel-scheme residual. **No `-v1release` added; default byte-identical; `BoA-v1ref` byte-clean.**
→ `PHASE2_RELEASE_RECONCILE_FINDINGS.md`.

## 2026-07-01 — DONE: promoted the f̂-directed sphere-head neck-stroke to the DEFAULT myosin (implements the DECISION below). New `-legacymotor` restores the old motor. Default CHANGED (contract = two bit-reproductions + CPU≡GPU).
Collapsed `-spherehead -axlock -dirswing` into the default (runs with NO flags, GPU + `-cpu`): frozen-F9-90°
(perp maintainer) + axial swing lock (F10→ŝ) + f̂-directed neck powerstroke (`CrossBridgeSystem.directedSwing`,
rear sweeps barbed-ward, θ 0→60°, J1 angular converter OFF). A single post-arg-parse block sets
`SPHEREHEAD=AXLOCK=DIRSWING=true` unless `-legacymotor` (old v1-port F9 head-swing) or an alternative bond law
(`-canonical`/`-config1`/`-perphead`) is selected — so it sets EXACTLY the booleans the explicit `-dirswing`
set (no force-law change). Release UNCHANGED (default catch-slip + standard 4-state cycle). **Regression (the
new contract, bit-reproducible CPU):** (A) new default (no flags) ≡ old `-dirswing` **bit-for-bit**; (B)
`-legacymotor` ≡ old default (built from the pre-promotion commit) **bit-for-bit**; (C) CPU≡GPU on the new
default (matbed d500: GPU avgBound 10.5/10.0 vs CPU 10.3/9.5, within SEM). **Physics unchanged (Check D):** new
DEFAULT GPU `-full` d1000 150k → **v_axial −3.02 µm/s, axial frac 0.996, avgBound 14.8** (bit-identical to the
standalone `-dirswing`). `-hfswing`/`-rollsign`/`-mhatset` stay OFF (documented negatives). `BoA-v1ref`
byte-clean; no kinetics/geometry retune; the release examination (incl. a possible v2→v1 catch-slip-only map)
is the NEXT task. New: `-legacymotor` + the promotion block (`GlidingHarness`); `-dirswing` retained as an
explicit alias of the default. → `PHASE2_DEFAULT_PROMOTION.md`.

## 2026-07-01 — DECISION: adopt the f̂-referenced (filament-directed) neck powerstroke as THE motor; promote to default. The head-frame/mhat arc is the negative-result evidence that motivates it.

After a long faithfulness detour (head-frame swing + roll-sign + mhat locks), the simplifying decision: **the neck powerstroke is referenced to the filament axis f̂ (the neck rotates toward the actin + end), not to the head's own frame.** Promote the fixed-head / axial-lock / f̂-directed-neck model to the default myosin. (PLANNER/PI sign-off given — this re-baselines prior validation numbers, per the standing rule.)

### The biological justification (why f̂-reference is faithful, not a shortcut)
The motor domain does **not** bind F-actin as a blob in an arbitrary pose — binding is **stereospecific**: the actin interface registers the head to the filament in one deterministic orientation. So the bound head is effectively **clamped to actin**, and "swing the neck relative to the bound head" ≡ "swing the neck relative to the filament." Referencing the stroke to f̂ is therefore a faithful stand-in for a neck swinging off an actin-registered head — not a modeling convenience. In a proper flat-head bind the neck region nearly touches the actin, so **"the neck lever rotates toward the actin + end"** is a reasonable simplified stroke. A structural specialist might contest the exact lever-to-actin angle; absent a specific refutation, this is the working, defensible model.

### The negative results that motivate it (the head-frame arc)
We built the head-frame stroke (swing referenced to the head's OWN frame: `cosθ·û_head − sinθ·(ŷ_head×û_head)`) to be more first-principles. It glides correct-polarity but **slower**: BoA −3.96→−2.54, v2 −3.0→−2.15. We then ruled out every "recoverable artifact" explanation:
- **Not the discrete roll-axis** — the axial lock (roll axis → ŝ=n̂×f̂) already holds it; single-motor axial fraction 1.0.
- **Not the roll SIGN** — `-rollsign` fixed yVec→+ŝ (census 50/50→99.5%), speed only −1.86→−2.15. Cheap (twist ~26–48°), but didn't recover.
- **Not thermal jiggle** — head-lock stiffness sweep moved speed the WRONG way and collapsed binding (pin-absorption / explicit-stiffness whipping); k≈0.4 is already near-optimal. Refuted.
- **Not saturation/density** — the gap persists ~1.4–1.8× across d250–1000, including sparse d250. Refuted.
- **Not the head-axis (mhat) sign** — set at bind (initialization, tip-preserving, NOT a persistent torque; avgBound held ~21, sign retained 0.97 no-decay), speed stayed ~−1.9. Fork 3: the 50/50 mhat is irrelevant to speed. (The earlier persistent-torque mhat lock was a mis-diagnosis — it pin-absorbed, −2.54→+0.22.)

**The residual, understood:** the head-frame law references the head's own orientation, which is (a) systematically tilted (mhat·n̂ mean ~+0.83, ~34° off-axis, not symmetric jiggle) and (b) subject to **stroke-reaction feedback** — the swing is computed from û_head and its reaction pushes û_head, a loop the external f̂ reference structurally lacks (f̂ can't be shoved). So the head-frame motor is irreducibly a bit slower and noisier. That gap is not a fixable inefficiency — but it is also **not a reason to prefer the head-frame law**, because the biology (stereospecific bind = head clamped to actin) says f̂-reference is the correct registration anyway. So we adopt the faster AND defensible one. The head-frame/mhat experiments stand as the documented evidence for that choice, not wasted work.

### What is promoted (the default motor)
Fixed head at **90° ⊥ strut** (no head swing, both nucleotide states) + **axial swing lock** (roll axis → ŝ=n̂_bed×û_seg, swing plane contains f̂) + **f̂-directed neck powerstroke** (`myoNeckStrokePolarity` / v2 `-dirswing`: rear sweeps barbed-ward, θ 0→70° BoA / 0→60° v2). The old F9 head-swing motor is preserved behind a **legacy flag** (it is the prior validated oracle — 4b-iv −13%, SET-A −5.7). The head-frame, roll-sign, and mhat-lock flags stay OFF as documented negatives. **Release pathway UNCHANGED** (catch-slip on the F8 tip-spring load, per v1) — not bundled into this promotion.

### Open / next
- **Release, examined in detail** (the next task) — v1/BoA is catch-slip-on-F8; v2 uses the standard catch-slip + 4-state cycle; a clean v2→v1 catch-slip-only map for apples-to-apples, and whether LT (bound-in-ATP fix) belongs in the default.
- **The BoA(−3.96)/v2(−3.0) gap** — same stroke law + same release class, so it's geometry (neck 70° vs 60°) or a residual; reconcile when examining release.
- **BoA GPU joint kernel** is still polarity-blind — porting the f̂-directed swing to the device kernel is required before BoA GPU-default is correct (v2's `-dirswing` already runs on the GPU dense mat).
- Promotion re-baselines all prior default-motor validation numbers.

## 2026-07-01 — sphere-head: the HEAD-AXIS (mhat) sign — censused (v2 IS 50/50, like BoA), then bind-time set from polarity ⇒ HOLDS but is NOT a speed lever (costs binding). New `-mhatcensus`/`-mhatset`; default byte-identical.
STEP 0 (`-mhatcensus`, `GlidingHarness.mhatTally` = sign of `head.uVec·n̂bed`): under `-hfswing -rollsign` at
d1000 v2's mhat is **~50/50 (+ẑ 52.4%, mean 0.52 sd 0.14, no drift)** — a genuine SECOND free sign (the roll
lock pins ŷ_head=+ŝ and F9 pins û_head⊥f̂, leaving û_head=±n̂bed free). Convention: v2's head-frame swing reads
`motorUVec[head]` directly (`p=ŷ×û`), NO reversed uVecR variant — **productive pole = +ẑ** (then p=f̂,
barbed-ward). STEP 1 (`-mhatset`, `CrossBridgeSystem.setBindMhat`): at each fresh bind (prevBound gate, placed
LATE = body-write PTX gotcha) reorient the head to +ẑ/+ŝ — **init only, no persistent torque**, impulse-free
(reorient about the bound tip so F8 is preserved), wrong-pole-only (touch only the ~48% at −ẑ). STEP 2 (GPU
`-full` d1000, LONG_ROW, seed 0x6111D): **the census HOLDS (≥97% +ẑ, no decay) in every variant, but avgBound
never returns to ~14 and the net glide never recovers** — monotonic in reorientation amount: free-sign 13.9 /
−1.65 → wrong-only 6.0 / −0.94 → impulse-free-all 4.3 / −1.03 → hard-snap 1.0 / −0.20. **Mechanism:** the head
is over-determined by TWO attachments (F8-to-filament + J1-to-lever); a bind-instant ~180° reorientation of the
un-oriented, doubly-tethered head disturbs one (F8 hard-snap → catch-slip collapse; impulse-free → J1 impulse →
still releases). **VERDICT (a 4th outcome, not the task's A/B/C):** the mhat sign STICKS once set but is NOT a
recoverable speed lever — fixing it costs more binding than the per-head directedness it buys; a persistent
torque would pin-absorb (BoA). `-hfswing -rollsign` (−2.15/−1.65) stands as the honest head-referenced glide;
the mhat 50/50 is a structural DOF of the doubly-tethered sphere-head under the head-frame law. Release pathway
(all runs): default `catchSlipRelease` (catch-slip on F8 `forceDotFil`) + standard `cycle` = BoA's
catch-slip-on-F8 family. CPU≡GPU by construction (per-motor pure). `BoA-v1ref` byte-clean; production
byte-identical (all gated on `-mhatset`/`-mhatcensus`). → `PHASE2_MHAT_BIND_FINDINGS.md`.

## 2026-07-01 — sphere-head: the −2.15(hfswing+rollsign) vs −3.0(dirswing) gap DIAGNOSED — NOT head-noise (TEST 1 refutes), NOT saturation (TEST 2 refutes); it's a real, density-independent, IRREDUCIBLE stroke-law difference. All speeds from the settled long assay.
Added the long-assay `LONG_ROW` estimator (on-bed LS-centroid, ≥1 s window; cross-checked vs frame LS:
rollsign d1000 −2.11 vs −2.15) and `-headlock <mult>` (head-lock stiffness knob). **Release pathway (stated):
ALL sphere-head configs use the SAME default `catchSlipRelease` + standard `cycle`** — the −3.0/−2.15 spread
is a pure stroke-law difference, no release confound. **TEST 1 (head-lock stiffness sweep, `-full` d500):
REFUTES the thermal-head-noise claim** — stiffening moves `-hfswing -rollsign` the WRONG way (−1.56 → −1.02 →
+0.14 → +0.28 at ×1/×2/×4/×8) and collapses avgBound (8.7 → 7.1 → 1.7 → 0.2; explicit-stiffness whipping
detaches heads). Speed does NOT climb toward −3.0 ⇒ the gap is not a coolable/stiffenable head-orientation
noise; the compliant k=0.4 is already near-optimal. **TEST 2 (two laws vs density, d250–1000): REFUTES
saturation** — `-dirswing` rises MONOTONICALLY (−1.90→−2.48→−2.69→−3.0, not saturated at d1000) and the gap
PERSISTS ~1.4–1.8× at every density INCLUDING sparse d250 (avgBound 6.4). Overturns the prior
"binding-saturated ×1.16" aside. **Gap robust (n=3, d500): dirswing −2.46±0.02 vs rollsign −1.56±0.10 SEM,
1.58×, >5σ.** **Convergence:** LS slope settles by a ~0.7–0.9 s window (dirswing→−3.0, rollsign→−2.1) — the
numbers are statistically settled well under 1 s; run length is set by settling, not a fixed sim time.
**Resolved reading:** the gap is a genuine, IRREDUCIBLE per-head difference — the head-frame law both binds
fewer heads (d500 avgBound 8.7 vs 10.4, ×1.19) and strokes less effectively per head, because its converter
references + reacts on the head's own dynamically-imperfect frame vs `-dirswing`'s external clean f̂; and it's
NOT a tunable inefficiency (the one knob, head-lock stiffness, backfires). **−2.15 is the honest glide of the
head-referenced motor; −3.0 is faster only via the f̂ modeling convenience.** Corrects the prior docs' "just
head thermal noise, −2.15 more physical" inference (the Brownian-off single-motor identity still holds; the
"reducible noise" framing was wrong). New: `-headlock`, `LONG_ROW`; diagnostic only, default byte-identical;
`BoA-v1ref` clean. Report: `PHASE2_HFSWING_GAP_FINDINGS.md`.

## 2026-07-01 — sphere-head: STEREOSPECIFIC roll sign (`-rollsign`, +ŝ from polarity) — fixes the roll-sign DOF (census 50/50 → 99.5% +ŝ), CHEAP (twist mean 48.5°, avgBound/lifetime unchanged), speed −1.86 → −2.15 but NOT back to −3.0 (residual = head-orientation noise, not sign).
Fixes the `-hfswing` roll-sign free DOF: the axial lock aligned head.yVec to the NEARER of ±ŝ (sign-free,
50/50); `-rollsign` aligns to **+ŝ specifically** (ŝ = n̂bed×û_seg is the barbed sign, from filament
polarity) — one gated branch in `CrossBridgeSystem.bondForces` (skip the sign-flip). Per-motor pure, CPU≡GPU,
default byte-identical. **Single motor: unchanged** (barbed, axial 1.00). **Mat d1000 1.5 s LS-centroid:**
`-rollsign` **−2.15 µm/s** (axial 0.999, avgBound 14.0) vs `-hfswing` −1.86 vs `-dirswing` −3.0. **Roll
census: 99.5% +ŝ** (was 50/50) — the fix works at the DOF level. **Twist cost (`-twistcensus`, 36k binds):
mean arrival 48.5°, only 15% far-side (>90°), avgBound/lifetime IDENTICAL to `-hfswing` (14.0/0.59 ms vs
13.5/0.60 ms)** ⇒ the roll-sign lock is **cheap/faithful, NOT a forced-twist artifact**; the +ŝ arrivals are
self-generated (held heads rebind near +ŝ; `-hfswing` arrivals are uniform, mean 90°). **Why still < −3.0:**
NOT the sign (now 99.5% +ŝ) — the head-frame law references the head's own thermally-fluctuating orientation
(û_head, ŷ_head jiggle; fresh heads mid-convergence), while `-dirswing` references the clean fixed f̂. Proven
by the Brownian-OFF single motor, where the two laws are IDENTICAL — the gap appears only under mat Brownian.
So `-dirswing`'s −3.0 is a mild noise-free-reference overestimate; **−2.15 (head-referenced) is arguably the
more physical glide.** (The mat is also binding-saturated: 50/50→99.5% +ŝ buys only ×1.16, not the naive ×2.)
**Verdict:** `-hfswing -rollsign` is now working AND first-principles AND cheap-assembly — the defensible
motor; `-dirswing` kept only if the higher (noise-free) number is wanted. No retune. New: `-rollsign`,
`-twistcensus` (`CrossBridgeSystem.captureBindTwist`), `-rollcensus` accumulation. `BoA-v1ref` clean.
Report: `PHASE2_ROLLSIGN_FINDINGS.md`.

## 2026-07-01 — sphere-head: HEAD-FRAME converter recast (`-hfswing`, `directedSwingHeadFrame`) — biologically-defensible power stroke; reproduces the single motor EXACTLY but reveals the axial lock leaves the head ROLL-SIGN free (50/50) ⇒ mat glide −1.86 vs −3.0 µm/s.
Recast the directed swing so the target is derived from the head's OWN frame — target û_lever* =
normalize(cos θ·û_head − sin θ·(ŷ_head × û_head)), **no f̂ in the swing law** (Rodrigues: rotate û_head about
the head hinge ŷ_head by θ_rest). Biology: binding orients the head; the converter rotates the neck relative
to the bound head; actin polarity enters only via the head's bound pose. Equals `-dirswing`'s
`cos θ·û_head − sin θ·f̂` **iff ŷ_head×û_head = f̂**, i.e. iff the head is locked to +ŝ. **Single-motor gate:
IDENTICAL** (axial fraction 1.000, rear +7.80 nm barbed, recovery→straight — the gate sets ŷ_head=+ŝ).
**Mat (d1000, 1.5 s, LS-centroid): the speed MOVED — −1.86 µm/s vs `-dirswing` −3.0** (axial fraction still
0.999, avgBound ~14). **Root cause (`-rollcensus`, 20,515 bound-head samples): the axial lock pins the roll
AXIS but not its SIGN — head.yVec·ŝ is 50.6% +ŝ / 49.4% −ŝ (≈50/50, a free DOF).** The −ŝ heads sweep the
neck pointed-ward under the head-frame law; `-dirswing` is robust (re-derives from f̂, both signs sweep
barbed), so it hid this. Not a cancellation to zero (0.62×, not (1−2p)≈0) — load-dependent catch-slip favors
the productive heads, drift stays axial. **The finding: "binding orients the head" pins û_head (⊥) and the
roll axis |ŷ_head| but NOT the roll sign; a fully stereospecific lock needs polarity at bind (which a
bed-normal×axis lock lacks).** The recast is the correct biological formulation; it surfaced an incomplete
head lock — NOT tuned (per task). Fix (future, deferred): align head.yVec to a DEFINITE +ŝ at bind (lock
sign convention), then head-frame reproduces −3.0. `-dirswing` stays the working model meanwhile. New:
`CrossBridgeSystem.directedSwingHeadFrame`, `-hfswing`/`-rollcensus`, `AxLockGateHarness -hfswing`. Default
byte-identical; `BoA-v1ref` clean. Report: `PHASE2_HEADFRAME_SWING_FINDINGS.md`.

## 2026-07-01 — sphere-head motor: axial swing-plane lock (§9.4c) + deterministic directed power stroke ⇒ directed glide ≈ 3.0 µm/s
Two flag-gated additions to the sphere-head gliding motor (F9-frozen ⊥ head + J1 neck-swing); default
byte-identical, `BoA-v1ref` clean.
**(1) Axial lock (`-axlock`, `CrossBridgeSystem.bondForces` xbParams[10]):** F10 retargeted from the
segment's incidental yVec to ŝ = normalize(n̂bed×û_seg), head-only (faithful port of BoA
`alignYVecTorqueAxial`; compliant k=0.4, no pin). Fixes the swing PLANE — single-motor axial fraction 1.00
at any filament roll (vs →0 un-locked). Alone on the mat: no net-glide gain (velFitX ~0.84→~0.96, null;
CPU≡GPU agree) — the swing AZIMUTH was still uncontrolled.
**(2) Directed power stroke (`-dirswing`, `CrossBridgeSystem.directedSwing`):** the J1 converter axis
cross(lever,head) is degenerate at the straight recovery pose ⇒ the stroke direction FLIPPED between cycles
(jba's viewer catch). Replaced by a torque driving the neck toward the polarity-defined target
û_L* = normalize(cos θ·û_head − sin θ·f̂), so the rear sweeps barbed-ward EVERY cycle; J1 angular converter
off (position spring kept). Per-motor pure ⇒ race-free, CPU≡GPU.
**Result:** single-motor stroke reliably barbed-ward (every power stroke rear +x, recovery→straight, purely
axial). Mat (single 2 µm filament, d1000, 1.5 s, GPU): **v_axial ≈ −3.0 µm/s, axial fraction 1.00, avgBound
~14, pointed-leading** — LS-centroid over the fully-on-bed window (t≤1.06 s, per
`~/Code/BoA/PROPER_SPEED_ANALYSIS.md`). Same regime as BoA's −2.09; the axial-fraction-1.00 (net≈path)
directed glide is the win vs the earlier ~1 µm/s wander. Lock fixes the plane, directed swing the azimuth.
New: `AxLockGateHarness`, `-axlock`/`-dirswing`/`-j1swing`, `CrossBridgeSystem.{bondForces axLock,
directedSwing}`. Reports: `SPHEREHEAD_MOTOR_RESULTS.md`, `PHASE2_SPHEREHEAD_AXLOCK_FINDINGS.md`.

## 2026-06-30 — sphere-head on the GPU DENSE MAT (the real assay): GLIDES −X (velFitX peak ~1.1 µm/s @ d500), but SLOW (sub-skeletal). The 40-motor "+X" was a reduced-scale ARTIFACT — refuted on the full bed.
**STEP 1 — GPU build (the efficient faithful realization):** the three-body sphere-head = F8 tip anchor + the head-vs-actin
angle FROZEN at 90° (the compliant ⊥ perp-maintainer, no F9 power stroke) + the J1 converter neck-swing (0↔60°) as the
ONLY stroke. GlidingHarness's default motor already runs F8+F9+F10+J1 on the validated device-resident dense-mat TaskGraph
(CSR gather, bind-on-proximity, nucleotide cycle, catch-slip, velFitX, `-nobind` floor, density ladder), so `-spherehead`
realizes it by freezing F9's rest at 90° (`xbParams[9]=1`) — F10 supplies the azimuth reference the single-motor CPU
harness lacked. ONE flag, NO new device code, `-cpu` stays valid; default-off ⇒ production BYTE-IDENTICAL (regression:
default d1000 velFitX 5.703 unchanged); `BoA-v1ref` byte-clean. **STEP 2 — the dense-mat ladder (GPU device-resident,
full 14×2 bed, 40k, n=3, dt=1e-5):** velFitX (>0=−x) = **0.41±0.12 / 1.10±0.13 / 0.91±0.05 / 0.53±0.12 µm/s @ density
200/500/1000/2000** (avgBound 4.8/9.3/14.9/18.7); floor (`-nobind`) velFitX −0.38 (diffusion noise). **EVERY density glides
−X, unambiguously above floor, dt=1e-5 STABLE at mat scale** (fullMat=YES, capFires=0 to 13920 motors — no implicit/substep
needed). **CPU≡GPU (d500): GPU 1.12 / CPU 1.81, both −X, avgB agree** (the one-step-stale parallel residual is larger than
the F9 motor's — the weak J1 stroke is more sensitive; sign+magnitude agree = the chaotic standard). **ADJUDICATION:
GLIDE-but-SLOW.** The sphere-head (J1 neck-swing, head held ⊥, F8 anchor, F10 azimuth) is a WORKING −x gliding motor on the
real dense GPU mat — but velFitX peaks ~1.1 µm/s (d500), **below the 5–8 µm/s skeletal band** and ~5× under the default F9
motor's 5.7 (the J1 neck-swing is a weaker stroke than F9 head-reorientation, per STROKE_VS_ARMLENGTH). **Non-monotonic:**
velFitX peaks at d500 and DROPS at d2000 (0.53) while avgBound keeps rising — over-binding/tug-of-war; the lever is per-head
transport efficiency, NOT direction or binding. **The 40-motor CPU "+X gate" (prior task) was a reduced-scale artifact —
refuted on the full mat (no F10 azimuth + no collective dynamics at 40 motors).** Glide is adjudicated only on the full mat
(standing rule honored). New flag only ⇒ production byte-identical. Report: `PHASE2_SPHEREHEAD_GPU_MAT_FINDINGS.md`; raw
`RUN_LOGS/2026-06-30_spherehead_gpu_mat_sweep.txt`.

## 2026-06-30 — sphere-head full-mat gliding assay (single-density velFitX gate): velFitX = −0.084 µm/s ⇒ glides +X (WRONG), NO −X glide. Blocker = the DUTY CYCLE (release-while-cocked), not the stroke.
Ran the single-density velFitX gate (the task's stage-behind gate) on the corrected three-body sphere-head mat: CPU,
40 motors, one 0.4 µm filament, n=3 + `-nobind` floor, dt=1e-5; velFitX = −slope of the steady-2nd-half centroid-x fit
(>0 = −x glide = correct). **Result: STROKE-ON velFitX = −0.084 ± 0.007 µm/s, FLOOR +0.000 ± 0.000, avgBound 6.23,
dt-stable** ⇒ velFitX NEGATIVE = **the filament glides +X (WRONG polarity), above the ~0 floor (real, not noise). NO
−X glide.** **Root cause = the DUTY CYCLE, not the stroke geometry:** the single-motor M3 −X measured only the
**power-stroke half** (settle uncocked → cock once → measure); the full bind→stroke→**recover**→release cycle also has
the **+X recovery stroke** (J1 60°→0°), and in the mat it nets +X because the recovery isn't separated from the stroke
by release timing — `catchSlipRelease` fires at a ~random phase (the sub-pN catch is inert), so motors recover **while
still bound**, dragging +X. **NOT a bind impulse** (+X persists `-noreset`: +233 vs +200 nm). The stroke
geometry/polarity, spring (1 pN/nm), catch-slip load (axial F·seg.uVec), and the compliant perp-orientation torque are
all CORRECT (single-motor verified). **The full density ladder + the dense ~28k-motor GPU bed were NOT run — deferred:**
(1) the direction is +X, so a ladder would only confirm +X at every density; (2) the dense bed needs a GPU sphere-head
build (sphereBond + perpTorque + J1 + the head-side gather as a device graph) not yet done. **Path to the glide proof:**
couple detachment to the cocked/post-stroke state (v1's NONE→ATP cocked detachment ⇒ recover UNBOUND) → re-run the gate,
confirm velFitX flips >0 (−x) → then build the GPU mat for the full ladder + CPU≡GPU. dt=1e-5 stability PASS at this
scale (the filament drag damped the compliant overshoot, as predicted). New files/flag-gated only ⇒ production
byte-identical; `BoA-v1ref` byte-clean. Report: `PHASE2_SPHEREHEAD_MAT_GLIDE_FINDINGS.md`.

## 2026-06-30 — sphere-head FIX: three-body topology RESTORED, but re-measure → BAIL. The powerstroke's axial directedness LIVES in F9 (the head–actin angle the abstraction deleted); the J1 converter swings ⊥ to actin. NO glide claim.
Fixed the body-count collapse: the motor is again **rod →(J2)→ neck →(J1)→ HEAD**, three distinct rigid bodies, both
joints intact, the head a **sphere of real extent (R=10 nm)** carrying the actin attachment at the head TIP (= v1's F8
contact, off-centre) ↔ a fixed filament site; compliant v1 tail; F9 deleted. **Viewer (`threejs_spherehead`, 320 frames)
shows three distinct bodies** — rod/tail, short neck, big head ball standing the neck OFF the filament — the 2-body "T"
is gone. **BUT re-measure (single motor, dt=1e-6) → the restored motor does NOT transport, triggering the bail:** (1)
**collinear degeneracy** — J1's bend axis cross(lever,head)=0 when collinear, and the uncocked rest is exactly 0°
(collinear), so the converter can't deterministically self-start (M1=0, θ stuck at 0°); v1's F9 has no such degeneracy
because it references the FILAMENT axis (that's *why* v1's stroke is F9, not J1). (2) **perpendicular swing** — breaking
the degeneracy with a 25° head tilt, the converter does swing (θ→60°) but moves the head Z-dominated (−2.24 nm Z, −0.22 nm
axial, axial frac 0.10); the free filament moves +0.78 nm (+X, ≈0) ⇒ **no axial transport.** M2 impulse-free bind 0.000;
M4 thermal fair test (Brownian ON, n=4) stroke-ON −25.8 vs stroke-OFF −36.7 nm ⇒ ON−OFF +11 nm (no clean −X; noisy floor,
consistent with no transport). **Root cause: the powerstroke's axial directedness LIVES in F9 (head-vs-FILAMENT) — the
very "head-actin angle" the sphere abstraction called non-physical and deleted.** The J1 converter (head-vs-LEVER) bends ⊥
to actin. BoA Run 2 transported only because it HELD the head (supplying the actin reference). **J1 kept FREE, head NOT
pinned ⇒ the bail is "the orientation-free sphere abstraction is wrong," NOT pin-absorption from over-constraining.** The
faithful model needs the head's actin-orientation back (a COMPLIANT F9-like reference toward the actin axis — the
directedness, not a rigid clamp — or accept v1's F9 as the stroke). NO glide claim (standing rule; full-mat assay not run).
New files/flag-gated only ⇒ production byte-identical; `BoA-v1ref` byte-clean. Report: `PHASE2_SPHEREHEAD_FIX_FINDINGS.md`.

## 2026-06-30 — sphere-head Part 1: the TAIL CONSTRAINT vs v1 RESOLVED — v1 is COMPLIANT, the rigid clamp was UNFAITHFUL; the faithful compliant sphere-head delivers the −X step with NO rigid pin. Part 2: -3js frames. Part 3 (glide proof) NOT RUN ⇒ glide UNPROVEN.
**Standing rule applied:** no glide is accepted without the full myosin-mat assay (velFitX, thermal floor, n≥3, dt=1e-5);
single-motor steps are kinematics, NOT glide. **The sphere-head is "gate passed, glide UNPROVEN."**
**PART 1 (gate on the assay's validity):** the build's rigid tail clamp (position+orientation) was the SAME CLASS as the
v2 two-point head pin / BoA 180° pin — add a rigid constraint so the lever reaches its angle. Checked v1 (`BoA-v1ref`):
(1) tail = **COMPLIANT point-spring**, force only — `MyosinFixed.applyRodFixedPtForce` (`MyosinFixed.java:51-67`); the
torque line `:70` is **commented out** ⇒ NOT a rigid clamp; (2) rod orientation **FREE** — `myoJ2FracMoveTorq=0.00`
(`Env.java:159`), no J2 angular spring; (3) the stroke (F9) reacts on the **FILAMENT + head, NEVER the rod**
(`MyoFilLink.java:248/251`). ⇒ the rigid clamp + reacting-the-swing-on-the-rod were both unfaithful. **Fix:** react the
swing torque on the **FILAMENT** (`SphereHeadSystem.swing` reactOnFil, mirroring F9) + drop the clamp (v1 compliant
point-anchor only, `-compliant`). The lever then reorients about its own center like v1's head under F9. **Re-run (M1/M3,
compliant): M1 geometric step −8.13 nm (axial, −X), M2 impulse-free (+0.000), M3 −4.22 nm delivered to a FREE filament
(−X, lever→125° clean, dt=1e-6)** — ~half the geometric throw because the compliant contact+tail share the displacement
(correct for a compliant linkage). **The compliant, v1-faithful sphere-head DELIVERS the step with NO rigid pin — the bail
condition (rigid orientation pin required) is NOT triggered.** dt note: compliant is stable+correct at dt=1e-5 with
realistic ensemble filament drag (`-filgam 20`, θ→125°); the low-drag 1-seg TOY filament goes explicit-stiffness unstable
at dt=1e-5 (lever→26°) — the known dt-ceiling, re-check at ensemble scale in Part 3. **PART 2 (-3js):** `threejs_spherehead`
(89 frames, compliant stroke dt=1e-6, lever sweeps in-plane toward barbed, filament steps −X, whole linkage flexes =
compliant not pinned) + `threejs_spherehead_rigid_overshoot` (labeled dt=1e-5 toy −135 nm artifact). **PART 3 (the
full-mat velFitX assay = the ONLY glide proof): NOT RUN ⇒ glide UNPROVEN.** Next increment: wire the compliant sphere-head
into the gliding mat (one new GPU piece — route the motor-side gather to the LEVER + gather the swing's filament-torque
reaction; CPU sequential is race-free with direct writes) + catch-slip rebind + matbed velFitX sweep + re-check dt=1e-5 at
scale. **Kinematics ✓ (v1-faithful, no pin), glide ✗ (unproven).** New files/flag-gated only ⇒ production byte-identical;
`BoA-v1ref` byte-clean. Report: `PHASE2_SPHEREHEAD_GLIDE_FINDINGS.md`.

## 2026-06-30 — SPHERE-HEAD motor BUILT + the step/direction gate PASSED (the clean form of BoA Run 2): −9.18 nm skeletal −X step, impulse-free bind, step EMERGES from geometry (no pin)
Built the sphere-head anchor motor in v2 and ran the MEASURE-FIRST gate (single motor, CPU) before any ensemble glide.
**The mechanism:** motor domain = a point/sphere anchor at the converter/neck tip (lever.end2; moment arm = LEVER_LEN
8 nm — the correct lever, NOT the over-long 20 nm head). One compliant Hookean anchor↔fixed-material-site spring
(`SphereHeadSystem.sphereBond`); NO head axis, NO F9, NO head-to-filament angle. Power stroke = the lever swinging
about the **rigidly bed-clamped rod** (`SphereHeadSystem.swing`), swing axis **ŝ = lever×fhat defined EXPLICITLY ⟂ the
filament** (the fix for the BoA pin-absorption failure, whose head×lever axis rotated with the pose), θ 55°↔125° (70°,
centred on 90° for max axial throw). Load to catch-slip = the anchor force along the filament axis. v1 catch-slip
biochem. NO rigid/orientation/two-point pin — **the bail condition (a pin needed to make it work) did NOT trigger.**
**The rigid-tail tether was load-bearing:** point-anchoring only the rod's tail let the swing reaction spin the rod →
continuous walk (M2 +186 nm, M1 throw 3.6 nm); clamping the rod pose fully (the physical bed-rigid tail) → M1 the full
9.18 nm, M2 exactly 0. **GATE (all PASS):** M1 per-cycle geometric step **−9.18 nm = 2·L·sin(35°) exactly, purely axial,
−X** (skeletal 5–10 nm); M2 impulse-free bind (bound-not-stroking ⇒ Δx=0, the BoA Runs 4–5 rectification artifact is
absent); M3 direction **−X**, delivered step **exactly −9.18 nm at dt=1e-6 (lever reaches target θ=125°, stable)**. The
dt=1e-5 toy-filament M3 overshoot (−135 nm, lever flattens to 170°) is the **explicit anchor-spring stiffness on a
low-drag 1-seg free filament** (k·dt/γ — the known dt-ceiling family), NOT a mechanism flaw: **at dt=1e-5 with realistic
ensemble filament drag (×5/×20/×50 — the gliding 11-seg ~1.9 µm filament) the delivered step is exactly −9.18 nm,
stable** ⇒ the ensemble is de-risked. **The step EMERGES from the swing geometry — no invented axial force.** New files
only (`SphereHeadSystem`, `SphereHeadHarness`) ⇒ production + every other harness byte-identical; `BoA-v1ref` byte-clean.
**Next increment (unblocked):** wire sphere-head into the multi-motor gliding harness (the GPU gather routes the
motor-side force to the LEVER not the head — a small gather-target change) + catch-slip rebind + matbed → velFitX vs the
thermal floor, n≥3, vs the skeletal anchor; re-check dt=1e-5 whipping at ensemble scale (banked implicit/substep fix if
it appears). Report: `PHASE2_SPHEREHEAD_FINDINGS.md`.

## 2026-06-30 — BoA GEOMETRY EXPLORATION: neck-swing is VIABLE (Run 2, 8.5 µm/s) and skeletal-faithful; the flat-head detour is a dead end via PIN-ABSORPTION (same failure as the v2 canonical rigid pin); resolution = abstract the motor domain as a SPHERE/anchor point.

Done in the BoA code directly (fast iteration, v1's known-good catch-slip biochem) to settle two questions before committing a v2 build: is the **neck-swing** (canonical mechanics) viable, and does **flat-head binding** work. Reference runs on the d1000 bed: baseline head-swing 14 µm/s / avgBound 11.5 / −X.

### Result 1 — the neck-swing TRANSPORTS, and it's the more faithful velocity (Run 2)
Holding the head fixed (no head-swing) and routing the full 70° power stroke through the **neck/lever** glides **8.5 µm/s at avgBound 15.4, −X**. So neck-swing-only is viable on a single compliant anchor — the canonical mechanics work; the catastrophic v2 failure was never the neck-swing. **And the speed "drop" 14→8.5 is a faithfulness GAIN, not a deficit:** the baseline's 14 µm/s is *super-skeletal* because the head-swing uses the over-long ~20 nm head as the lever; the neck (~8 nm) is the correct lever, and 8.5 µm/s lands in the skeletal 5–8 band. Step ∝ lever length, exactly as the literature says. **Run 2 is the existence proof and the better motor.**

### Result 2 — the flat-head (180°) detour is a DEAD END: pin-absorption (Runs 3–7)
Trying to lay the head literally flat (180°, collinear with actin) failed through a chain that ends in one root cause:
- **Run 3** (180° rest + tip-offset): binding collapsed (avgBound 0.36) — the 180° rest fights the bind-capture alignment gate; heads self-release.
- **Run 4** (center-bind + 30° anti-parallel gate): binding holds (avgBound 14.7) but glides **+X (wrong)** — a bind-*search*-on-tip vs tether-on-center ~10 nm offset injected a spurious barbed-ward rectification.
- **Run 5** (search/attach/tether all on center): artifact removed ⇒ filament **stationary** (+0.057 µm), avgBound 28.6. Correct for a strain-free flat bind with no stroke.
- **Runs 6/7** (perpendicular neck rest, 70° stroke, both senses): net motion barely moves (+0.10 / +0.04 µm), **never flips sign**, all weakly barbed-ward.

**Root cause (Runs 5–7):** with the head **rigidly pinned flat by a strong `alignUVecTorque`**, the neck-stroke torque is reacted by the head pin instead of translating the bound contact — the lever swings but does ~no axial work. The residual ±X drift is a bind/release rectification artifact, NOT the stroke (the sign is unaffected by stroke sense). **This is the SAME failure as the v2 canonical motor's rigid two-point pin** (there the converter torque became a transverse couple; here the neck-stroke torque is absorbed by the orientation pin). Tuning the swing centering (55→125) and flipping the lever didn't help because the stroke was never the thing producing the motion.

### The lesson (now seen TWICE, two different mechanisms, one failure)
**Rigidly constraining the head's pose kills transport** — whether the v2 rigid two-point PAIRS pin or the BoA 180° orientation pin. The stroke torque dumps into the rigid constraint instead of the filament. **The compliant single anchor is what works** (v1/default; Run 2). This is the through-line of the whole motor arc.

### The resolution — abstract the motor domain as a SPHERE / anchor point
The head-to-filament *angle* was never physical: the motor domain is a compact blob, not a rod, and "the rod's angle to actin" was a modeling artifact that caused four distinct failures (bind-gate fights, the 90°-vs-180° question, pin-absorption, sign artifacts). **Modeling the head as a sphere / point anchor is the MORE faithful abstraction** — head binds at a footprint, the lever pivots about that attachment — and it dissolves all four at once: no orientation to pin (no pin-absorption), no flat-vs-perpendicular question, no alignment gate vs rest-angle conflict. Run 2 is the working mechanism; the sphere-head is its clean form. **What stays real (and must be built correctly):** the lever **pivot point** relative to the anchor (the moment arm = step size, ~8 nm lever → ~9 nm step → skeletal V₀) and the swing **plane + sense** (plane contains the filament axis, swing axis ⟂ to it, sense → −X).

### Plan
Build the **sphere-head anchor motor in v2** (`SoftBox`): point/sphere anchor + single compliant attachment + lever pivoting about it + 70° axial swing → the clean form of Run 2. Keep **v1 catch-slip biochem** (known-good, holds duty); biochem revisit (LT, the duty×turnover tradeoff) is the deferred follow-on against a working baseline. **Measure the per-cycle filament step + direction FIRST** (skeletal ~5–10 nm, −X), then the rigorous large-mat assay vs the skeletal anchor. NO rigid pin, NO orientation pin. (Optional first checkpoint: port Run 2's 90°-held + neck-swing to v2 to confirm v2 reproduces the 8.5 µm/s before simplifying the head to a sphere.)

**Open / deferred:** the duty×turnover reconciliation (v1 catch-slip's high duty vs LT's bound-in-ATP fix — the prior finding); whether sphere-head + LT holds skeletal duty; the lever pivot/length tuning if the measured step is off-target.

## 2026-06-30 — DEFAULT (v1-port) motor on the Lymn-Taylor cycle: glides −x, STABLE, CPU≡GPU — but SLOW (duty-starved), not skeletal 5–8
Ran the **DEFAULT motor** (the v1 port: F8 tip spring + F9 90→120° stroke + J1 lever; catch reads the F8 tip-spring
`forceDotFil`) through `-lymntaylor` in a rigorous full-mat gliding assay. **STEP 0 (BoA-v1ref, READ-ONLY):** v1's
detachment is **catch-slip/break-force, NOT nucleotide-driven** — the cycle drives only the cocking (`isCocked=!isADPPi`,
MyoMotor.java:277-279); unbinding is the 12 pN cap (MyoFilLink:334-340) + Guo&Guilford catch-slip on forceDotFil
(:347-359), independent of nucleotide state (dissociateADP :270-275 does NOT call release). ⇒ **"default+LT" is the
v1-port STROKE + a fast nucleotide DETACHMENT v1 never ran — a NEW combination, NOT "restoring v1 whole"** (jba's "LT≈v1
cycle" holds for cocking, not for release). **STEP 1 (no wiring needed):** the LT branches were already gated on
`LYMN_TAYLOR` alone (independent of CONFIG1/CANONICAL); the default `bondForces` is byte-unchanged, `registerForceDot`
feeds the default's native F8 load to `cycleLymnTaylor`. Confirmed: cycles, cocks, detaches, bound-in-ATP 0.0%, −x.
**A/B (diag, d500):** LT vs the native no-LT default — avgBound **7.16→0.74**, bound-in-ATP **59.8%→0.0%**, bound-time
**1.29→0.41 ms**: LT fixes the bound-in-ATP pathology but **collapses duty**. **STEP 2 (rigorous: full-mat `-matbed`,
40k, velFitX steady-slope, n=3, GPU device-resident, dt=1e-5):** glides −x, **monotonic with density**, stable (capFires=0,
fullMat=YES, no NaN to d2000): velFitX **0.05/0.84/1.39/3.00 µm/s @ density 200/500/1000/2000** (avgBound 0.19/0.71/1.11/2.21);
**thermal floor (`-nobind`, avgBound=0): velFitX −0.38, netX −0.03** ⇒ glide real & ~8× floor at d2000. CPU≡GPU
aggregate-within-SEM (GPU velFitX 2.20 / CPU 1.62 @ d500 20k; ranges overlap, CPU lower = the one-step-stale residual).
**OUTCOME #2 — glides −x but SLOW (≤3 µm/s, below skeletal 5–8); LT CHANGED the default's transport.** Diagnosis:
ensemble glide ∝ avgBound × per-head transport; LT's fast NONE→ATP detachment starves duty (avgBound never reaches v1's
~7), so the single-molecule V₀≈6 (step×rate) does NOT translate. Headline tension: the native no-LT default glides FASTER
(−3.3 diag / −5.7 SET-A, avgBound ~7) — **LT slows the default, trading ensemble duty for turnover-correctness +
bound-in-ATP fix.** Neither config is fully skeletal at once; the gap is purely avgBound (duty). Next lever (out of scope):
raise avgBound (capture/geometry `myoColTol`/`alignTol`/density at skeletal kinetics — binding is bind-on-contact, no kOn
to raise; or slow `onADP` toward Myo2, which re-lowers turnover). **Minimal wiring:** added `-nobind` thermal-floor control
(`kinParams[19]`, default-off byte-identical — regression: glide_d500_s0 reproduces velFitX 0.886/avgB 0.713 exactly).
`BoA-v1ref` byte-clean; default (no `-lymntaylor`) byte-identical; no motor-physics retune, no default flip. Report:
`PHASE2_DEFAULT_LT_GLIDE_FINDINGS.md`.

## 2026-06-29 — PLANNER RECKONING: the canonical-motor regression, fully reconstructed — corrected v1 mechanism (PIVOT not slide), the FOUR coupled regressions, the reversed head, and the dt history the journal never recorded. The DEFAULT (v1-port) motor is the ONLY validated glider.

This is connective cross-session context the per-task docs don't carry. It corrects three things this journal got wrong or never wrote down, after a `BoA-v1ref` read (`V1_MOTOR_MECHANISM.md`) and a literature check settled the mechanism.

### The v1 motor — what it ACTUALLY does (CORRECTING this journal's "slides along actin")
From `BoA-v1ref` (byte-clean oracle), not memory or paraphrase: the bound head is anchored at a **FIXED material site** (`posOnSeg` constant, single writer, zeroed only at release). The F8 tip Hookean spring runs head-tip ↔ that fixed site; **the catch-slip reads the F8 tip-spring's along-filament force**. The powerstroke is the head **REORIENTING** — F9 (`alignUVecTorque`) swings the head↔filament angle 90°→120°, J1 swings the lever 0→60° — which strains the F8 spring and **translates the whole filament as a rigid body**. **It is a PIVOT/lever about a fixed anchor; the contact does NOT slide along actin** (re-anchoring is discrete, only at unbind→rebind). The 06-28/06-29 wording "the swing slides the head's contact ALONG actin" is **WRONG** and is retired here. jba's "no axial sliding" memory was correct.

### The canonical rebuild (06-27) introduced FOUR coupled regressions, not three
The rebuild was motivated by `STROKE_VS_ARMLENGTH` (the v1/default stroke is carried by F9 head-reorientation ∝ HEAD_LEN, J1 silent — non-canonical). Making it canonical bundled four changes, and the journal only cleanly diagnosed two:
1. **F9 removed → stroke moved to the J1 neck-swing.** Faithful in intent, but the neck-swing as built delivers force *weakly/transversely* (see #2), where F9's head-swing delivered it *strongly*.
2. **Single compliant tip anchor → rigid TWO-POINT pin.** This is the transport killer (FORCE-DECOMP: 22:1 transverse:axial; the head can't reorient, so the converter torque is reacted as a bending couple = the SET-A whipping).
3. **Load moved from the F8 tip-spring force → J1 lever strain** (mechanism-flag #2). **NEW CAUSAL CHAIN (never recorded):** this was not an independent choice — replacing the reporting Hookean spring with a non-reporting PAIRS pin *forced* it, because a stiff geometric pin doesn't expose a force to feed the catch. v1 read the **real attachment load**; the rebuild had nothing left but lever strain, which SLIP-DIRECTION showed is *decoupled* from the real axial force. So flag #2 is a regression with a known-good v1 predecessor, not a deferred curiosity.
4. **Head laid down REVERSED fore-aft** (corrects the journal's dismissal). Config-1/perphead place the tip toward the barbed end and the J1 converter toward the pointed end. Literature (plus-end myosins: lever points toward the barbed end pre-stroke, converter moves toward the +end) requires the **opposite** — converter/J1 toward barbed, motor-domain tip toward pointed. **The +x glide that config-1/perphead showed — dismissed in the perphead entry as "a uperp-direction/converter-polarity calibration choice" — is a FLIPPED HEAD, not a tuning knob.** v1/default glides −x because its orientation is already correct. (Code-confirm the exact pose vs swing direction; the literature answer and the observed +x sign make it very likely.)

### The dt history (recorded here because it never was, and every "why is it like this" traced back to it)
The dt arc (06-24→06-26) was on the v1-style motor: 1e-5 is the **faithful ceiling** — the F8 Hookean tip-spring overshoot inflates the off-rate ~2.6× vs the 1e-6 limit but stays *stable*; 1e-4 collapses unconditionally (k·dt/γ > 2). Six attempts to push past 1e-5 (search reformulation, force-averaging, thermal correlation, dashpot, saturation, local-implicit) all failed/under-delivered; the implicit-J1 solve was **banked** (~2× faithful-dt, not 10×). The principle: resolve finer detail as dt falls, but operate in a large-enough dt for tractable runs — 1e-5 is the sweet spot. **Per jba's recollection (to verify): the instability that drove the PAIRS pins was the *two translational* PAIRS pins, not the single Hookean tip spring** — i.e. a single compliant tip anchor may be both faithful and stable, and the two-point geometry created the problem the pins were adopted to solve.

### The flawed-gliding-test caveat (the reason this reconstruction was needed)
Several rebuild-era "still gliding" reports were **characterization runs, not large-mat/long-run assays**, and missed that the filament didn't really glide. SET-A (06-28) — the first rigorous assay of the canonical motor — caught it (no transport + whipping) and flagged the earlier "≈9 µm/s @4000" as a settling artifact. **Applying the caveat: the only trustworthy glides on record are 4b-iv (06-16, the default/old motor, −13% residual), SET-A (the default control, −5.7 µm/s), and the 06-29 LT speed-density. The canonical/perphead motor has NEVER passed a rigorous gliding assay. The DEFAULT (v1-port) motor is the only validated glider.**

### The plan (sequenced)
1. **Default + LT, rigorous large-mat assay** (`CC_PROMPT_default_LT_glide`): does the validated-glider motor reach skeletal speed (5–8 µm/s) on the LT cycle? Confirm v1's actual cycle first so we know if this *restores v1* or is a *new combination*. Goal: see SOMETHING glide at the skeletal target.
2. **Baby step toward canonical** (`CC_PROMPT_j1_stroke`): on the working default, hold F9 fixed at 90° and make J1 the **sole** config-changer (0→60/70°) — flat motor domain, swinging neck, on the same single compliant anchor, no pin, no load-signal change. **Measure the filament-transport stroke BEFORE gliding** (J1 doesn't move the head tip; STROKE_VS_ARMLENGTH warns J1-alone may give ~0 or reversed). Stroke present → glide (sign read as diagnostic); stroke tiny → lever-geometry, not mechanism.
3. **Faithful follow-on (named, deferred):** a *neck*-swing that projects force as strongly as v1's *head*-swing — flat head, lever-driven, compliant single anchor, **no rigid pin**, head oriented converter-barbed/tip-pointed. This is the canonical rebuild's actual goal, reached without the four-change demolition.

**Open to verify:** v1's nucleotide cycle (= LT?); the single-tip-spring dt stability claim; the code-confirmed reversed-head pose. **Mechanism-flag #2** (J1-strain-as-load) is now understood as a pin-forced regression, not a deferred curiosity — the default's tip-spring load is the known-good signal.

## 2026-06-29 — LYMN-TAYLOR CYCLE restored (undo -atprecharge): nucleotide-driven fast detachment ⇒ skeletal V₀ ~6 µm/s (was Myo2 ~1); flag-gated -lymntaylor, default byte-identical, CPU≡GPU
Implemented the validated single-pathway Lymn-Taylor cycle (`NucleotideCycleSystem.cycleLymnTaylor`), replacing the
`-atprecharge` experiments. **ONE release: NONE→ATP = detachment** (ATP binding releases the rigor head); the 4c Guo &
Guilford catch **MODULATES the ADP→NONE rate** (base = Howard ~1e3/s × g(F), g=1 at F=0), **NOT a release pathway** —
catch reused VERBATIM (not re-calibrated), only its base moved from kOff 100/s to the cascade 1e3/s. **bound-in-ATP
enforced ≈0** (a bound head ending in ATP detaches; cycle runs after bind/before bond ⇒ zero-force window, the
ATP-STATE-AUDIT clock-decoupling bug stays fixed). **VALIDATED** (perphead flat θ=0, calibrated stack): unloaded
detachment **141→651/s** (base-cycle ~870/s), bound-time **7.07→1.07–1.54 ms**, **V₀=step×rate ~1.0→~6.0 µm/s
(SKELETAL band; was Myo2)** — the MOTOR-PARAM-PROVENANCE turnover deficit (the deleted Howard ADP→NONE) is FIXED;
**bound-in-ATP 0.0 %**; **dt=1e-5 stable** (capRate 0, fullMat YES, no whip/NaN to density 2000); **CPU≡GPU
aggregate-within-SEM** (avgB 0.64≈0.62, netX −0.44≈−0.40, inst 6.5≈6.4). **Co-retune:** stale kOn 2e5→**2e6** (10×)
lifts binding (avgB + −x glide rise monotonically with density, glide clear ≥ d1000); 1e7 = diminishing returns
(encounter/geometry-limited). **ORTHOGONAL residual (pre-existing, NOT the turnover; flagged, out of scope):** density
threshold ~500–1000 (above Uyeda 100–300) + measured netX dt/drag-suppressed (~0.5–1 vs V₀ 6) = the flat single-tip
sparse binding (BINDING-SURVEY) + per-head transport-efficiency (SLIP-DIRECTION ~0.05 pN/head) + dt=1e-5/aeta-100×
suppression (SPEED-DENSITY / DT-CONVERGENCE). **THESIS: mechanism-flag #1 RESOLVED** — cycling-detachment is now a
**parameterized rate** (ADP→NONE base + NONE→ATP), not a code branch ⇒ a clean isoform swap (skeletal ~1e3/s, Myo2
~1.4e2/s, same mechanism); J1-strain-as-load flag (#2) stays deferred. Additive: `cycleLymnTaylor` + LT branches in
`stepOrig`/`buildPlan`/`singleStep` (skip the separate release task, route cycle to LT) + measureGrid pulls only
`stats` on LT (no cap task ⇒ capStats not a device var). **Flag-gated `-lymntaylor` (alias `-lt`); default/-atprecharge
byte-identical** (regression: 141/s, 7.07 ms reproduced exactly); `BoA-v1ref` byte-clean; **NOT flipped to default**
(intended as production once the orthogonal binding/efficiency levers land). Report: `PHASE2_LYMN_TAYLOR_FINDINGS.md`;
raw `RUN_LOGS/2026-06-29_lt_*.txt`. Next (separate): binding-density + transport-efficiency levers, then default LT.

## 2026-06-29 — MOTOR-PARAM PROVENANCE AUDIT (read-only/design-only): skeletal-faithful mechanics, Myo2-slow turnover; the slow turnover is a MECHANISM choice (-atprecharge deletes the fast biochemical detachment), not a drifted constant
Audited every motor constant for isoform provenance + classified physics/numerical/murky + designed the Skeletal_Myosin
file schema. **(A)** Catch-slip is genuinely SKELETAL (Guo & Guilford 2006 PNAS 103:9844 + Stam 2015: xCatch 2.5/xSlip
0.40/αC·αS 0.92·0.08/peak ~6 pN), stall 5 pN (Finer 1994), step ~6.96 nm, cascade rates Howard 2001 Table 14-2 —
**mechanics faithful-skeletal.** **(B)** Turnover is Myo2-regime: **V₀ = step×unloaded-rate = 6.96 nm × 143/s ≈ 1.0
µm/s** vs skeletal 5–8; the entire gap is **bound-time (7.1 ms vs ~1.25 ms, ~5.7×)**, NOT step or κ. **ROOT = the
`-atprecharge` model makes the catch-slip bond (kOff 100/s, GG mechanical regime, realized ~141/s) the SOLE
detachment, DELETING the Howard biochemical ADP→NONE (1e3/s, ~1 ms, skeletal-fast) the cascade already has** — the
mechanical-bond-as-cycling-rate conflation, CONFIRMED; it also explains the slip-direction "catch at unloaded floor
for every head". Duty 1.69 % vs skeletal ~5 %; the turnover fix must co-retune the (already-stale, BINDING-SURVEY)
kOn. **(C)** Designed `params/Skeletal_Myosin` (measured constants + inline citations; ⚠ kOff/kOn flagged drifted/
stale), a SEPARATE `params/Actin_Filament` (phalloidin Lp — actin varies independently), and `-isoform <name>`
reading `params/<name>_Myosin` with **default = no read ⇒ BYTE-IDENTICAL** until a non-default isoform is selected.
**THESIS FLAG:** mechanics+catch+cascade are clean parameter swaps, but TWO items are still mechanism in CODE — the
**detachment PATHWAY** (biochemical-cycle vs catch-slip-only, a `-atprecharge`/`ATP_RELEASE`/kernel branch) and the
**J1-strain-as-load** choice; the turnover follow-up should parameterize the detachment rate to make the falsifiability
boundary clean. Internal inconsistency: skeletal catch peak (6 pN) on Myo2-slow turnover (7 ms); stall sourced two
ways (v2 5 pN Finer / v1 Env 6 pN). Murky-flagged (NOT bucketed): myoColTol (6 nm capture reach), tauAvg (0.5 ms catch
EMA), myosinBreakForce (12 pN, off), the weak-binding candReach. **NO code edit, no file created/wired, no default
flipped, nothing committed; `BoA-v1ref` byte-clean.** Deliverable: `MOTOR_PARAM_PROVENANCE.md`. Follow-up (separate):
wire the file + the turnover fix.

## 2026-06-29 — SLIP-DIRECTION DIAGNOSIS: does the catch-slip release post-stroke heads? → BRAKE REFUTED, not a sign bug (BAIL on the fix). Measurement-only `-brakediag`, default byte-identical
Tested the hypothesis that high-avgBound-but-low-netX (avgBound ~15 @ density 3000, netX −0.46 µm/s) is a BRAKE: held
post-stroke ADP heads strain backward and resist while the catch mis-scores them into the held/catch regime. **Part A
(code trace):** the catch load `forceDotFil` is **signed** (not magnitude) and IS the J1 lever strain `(κ/L)·(60°−θ)`,
**not** the head's axial pin force. Convention (confirmed by `-csrecal`/`-fext`): `forceDotFil>0` = resisting → catch
(held to the +6 pN peak); `<0` = assisting → catch exponential detonates → fast release ⇒ **the bond is ALREADY
directional; assisting load already routes to fast release — no sign to flip.** **Part B (new `-brakediag`, CPU
measurement, additive/default-byte-identical): the brake is REFUTED on two independent cuts at the dense operating
point** (`-perphead -headtilt 0 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -atprecharge -matbed -density 3000 -dt 1e-5`,
avgBound 15.2): **(1)** binned by time-since-stroke, **LONG-held (>3 ms) post-stroke heads ASSIST** (mean axial on the
filament **−0.059 pN**, − = glide), they don't brake; ensemble net **−0.031 pN/head (forward)**; only the brief FRESH
(≤1 ms) bin mildly brakes (+0.045, transient). **(2)** the axial-force-sign × catch-load-sign **2×2 cross-tab is
statistically INDEPENDENT (~1 pp)** — `forceDotFil` carries **no information about the real axial-force direction**, so
it cannot "mis-score" braking heads; **of braking heads 57% read SLIP / 43% catch** (braking heads read the
fast-release side MORE, not less). Releases vs signed load are ~symmetric/slightly assist-biased (42% resist / 58%
assist) — no "catch holds the brakes" signature. **Root reframe:** the real loads (~0.03–0.06 pN axial) and the catch
load (~0.1–0.2 pN lever strain) are an order of magnitude below the ±1–6 pN catch/slip scale ⇒ the catch-slip runs near
its unloaded floor (~100–140/s) for ALL heads, load-insensitive — it isn't holding anything. **VERDICT = BAIL (both
bail-outs trigger):** bond already correctly signed (bail-1) AND no brake — held heads don't resist (bail-2). **Part C
NOT applied; Part D not run; no physics edit.** The deficit is **per-head transport FORCE-MAGNITUDE / duty / coherence**
(articulated motor ~0.05 pN/head vs the old point-motor's ~pN; the force–velocity / transverse-misprojection /
stale-kOn-duty levers already flagged in `PHASE2_SPEED_DENSITY` + `PHASE2_POSTFIX_VEL_FORCEDECOMP` +
`BINDING_SEARCH_SURVEY`) — NOT the catch-slip release direction. Flagged: `cyclediag` mislabels `forceDotFil>0` as
"assist" (backwards; diagnostic-only). `-csrecal` resisting-side curve intact (peak 56.8 ms @ +6.0 pN). `BoA-v1ref`
byte-clean; nothing committed. New: `-brakediag`/`brakeDiagnose`/`binOf` in GlidingHarness (gated, default byte-id).
Report: `PHASE2_SLIP_DIRECTION_FINDINGS.md`; raw `RUN_LOGS/2026-06-29_brakediag_d3000.txt`.

## 2026-06-29 — MOTOR FIX (jba-directed): ATP-RECHARGE coupling — the nucleotide cycle is SLAVED to the force-based catch-slip release (replaces the cycleAtpDetach dice-roll detach) — flag-gated `-atprecharge`, default byte-identical, CPU≡GPU
Acting on jba's correctness call (the binding survey exposed that the default `cycleAtpDetach` releases a bound head via a force-INDEPENDENT Monte-Carlo nucleotide roll — NONE→ATP→detach — which is wrong: a bound head should release ONLY through the force-based Guo–Guilford catch-slip). New flag-gated mechanism (`-atprecharge`, default-off), per jba's corrected spec: **(1)** the ONLY release is the force-based catch-slip (NO dice-roll detach; overrides ATP_RELEASE); **(2)** ADP→NONE is TIED TO RELEASE — `cycleNoBoundAtp` drops the cycle's ADP→NONE entirely, so a BOUND head HOLDS in ADP (cocked, force-bearing, post-power-stroke) until the catch-slip detaches it; the release kernels (`catchSlipRelease{,Avg}Recharge`) set `nucleotideState←NUC_NONE` on every release (the ADP→NONE coupling); **(3)** the freed NONE head takes up a new ATP by MONTE CARLO (the normal free-head NONE→ATP roll, atpOn=2e4 fast at saturating [ATP]; reducing atpOn is the low-[ATP] extension) → ATP→ADPPi (the ~10 ms hydrolysis recovery) → waits primed in ADPPi → rebinds. NONE→ATP is gated `!bound` (a bound head never recharges ATP). **Additive — 3 NEW methods in `NucleotideCycleSystem` (no existing kernel touched) + `ATP_RECHARGE` branches in GlidingHarness `stepOrig`(CPU)+`buildPlan`(GPU), all gated on the default-false flag ⇒ every existing path BYTE-IDENTICAL** (default `-diag` reproduced 0.40 avgBound / ADP 94% / 577 /s exactly). **Validated (`-diag`, flat θ=0 + calibrated, v1box, dt=1e-5):** bound population now **ADP 96.9%** (the cocked force-bearing dwell, held to release — the biologically correct state), release rate **577→141 /s** (≈ the catch-slip floor, NOT the dice), mean bound-time **1.7→7.1 ms**, avgBound **0.40→2.47** (longer lifetime ⇒ higher duty). **Gliding velocity (density 3000, 40k, n=2):** netX **−0.35/−0.56** (mean −0.46 µm/s, −x), avgBound **~15**, fullMat=YES — same regime as before (correctness change, not a velocity lever, since ADP/NONE are both cocked). **CPU≡GPU bit-identical** (grid avgB 2.654 both runners; new kernels lower on PTX). **Residual FLAG:** bound-in-ATP 2.1% — the rebind-in-ATP edge (a free head spends ~10 ms in ATP during hydrolysis recovery and can rebind there); eliminating it needs a bind-state gate (skip binding while in ATP) — NOT added (jba's simple form). **Single-molecule calibration path NOT wired** (still plain cycle) — re-calibrating kOn on this corrected cycle is the follow-up (the survey's stale-calibration flag). `BoA-v1ref` byte-clean. `./run_gliding.sh -diag -perphead -headtilt 0 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -atprecharge -v1box -dt 1e-5 8000`.

## 2026-06-29 — BINDING-SEARCH SURVEY (READ-ONLY / measurement-only): the flat-motor sparse binding is NEITHER a transcription bug (H1) NOR a geometric two-search cost (H2) — the "7× vs config-1" is a pre/post-ATP-FIX CONFLATION; the real lever is DUTY/LIFETIME
Surveyed the motor binding-decision end-to-end to distinguish H1 (calibrated kOn/capture not transferring = a bug) from H2 (flat single-tip head genuinely captures less than the two-point head). **NO code edit, no retune, no commit; `BoA-v1ref` byte-clean.** **A — the binding decision is byte-IDENTICAL across config-1, perphead/flat, the single-molecule calibration, CPU `stepOrig`, and GPU `buildPlan`:** all use `publishHeadFromBody` (search point = head TIP, one point) → `bruteReachable`/`reachTestDistSq` (capture = 6 nm sphere `myoColTol=kinParams[7]` + 2 orientation gates) → `bindCanonicalTwoPoint` (config-1 AND perphead both `CANONICAL` — SAME binder: single-tip search + the Version-B formability gate `rearArc=tipArc−HEAD_LEN≥0` + the reaction-limited `pBind=1−exp(−kOn·chord·kinParams[6])`, dt single-sourced from `kinParams[6]`, NO hardcoded `Constants.deltaT`) → `snapCanonicalHead`. **`git diff 13aa8fd..HEAD` of the search/commit is EMPTY** ⇒ the binding search is byte-identical to the 4b-calibration baseline; the ONLY binding-files change since is `61d2f18`'s `cycleAtpDetach` (the ATP-release fix — a LIFETIME change, not a search change). **H1 REFUTED** (no transcription bug; CPU≡GPU identical kernels; dt clean). **A.4 H2 REFUTED:** config-1 never had two searches — the tip search is single-point for both; "two-point" is the BOND, not the search; the binder is identical for config-1 and flat. **B (the decisive 2×2, GPU density 4000, ATP on/off):** config-1 ON **1.73** / OFF **15.19**; flat ON **1.91** / OFF **20.35** ⇒ **config-1 ≈ flat in BOTH regimes** (flat marginally HIGHER, not 7× lower), and the **ATP-release fix alone flips avgBound ~9-11×**. ⇒ **the "7× sparser than config-1" was a CONFLATION** — the 4b config-1 "19" was the ATP-OFF regime (`13aa8fd`, 06-27), the speed-density flat "2.78" is the ATP-ON regime (post-`61d2f18`, 06-29). **B.3:** the SAME geometric search gives avgBound ~20 (ATP-off) ⇒ accessibility/cross-section is NOT the bottleneck; the ~470× KM is a **DUTY/LIFETIME** cost (reaction-limited kOn=2e5 + the ATP-release-shortened lifetime), not geometry or a bug. **ONE FLAG for the planner (not actioned — re-baselines validation, needs sign-off):** the kOn=2e5 / 1.5%-duty calibration is **STALE** — tuned on config-1 + the plain `cycle`, but the benchmark runs perphead + `cycleAtpDetach`, so the benchmark's realized duty ≠ the calibrated 1.5 % (a calibration↔benchmark divergence in the LIFETIME — the single-molecule harness still reports 1.5 % because it uses the plain `cycle`). Re-calibrating kOn on the current ATP-release cycle is the duty fix. Report: `BINDING_SEARCH_SURVEY.md`; raw `RUN_LOGS/2026-06-29_bind_conflation.txt`. `./run_gliding.sh -gpu -config1 [-noatprelease] -kon 2e5 -tauavg 0.5 -xcatch 2.5 -matbed -density 4000 -dt 1e-5 -grid 20000`.

## 2026-06-29 — phase-2 SPEED–DENSITY vs EXPERIMENT (MEASUREMENT-ONLY): dt=1e-5 whip REFUTED on the flat motor; MM SHAPE correct but a VELOCITY DEFICIT at physical density (KM ~470× experimental) — lever is duty/binding, not geometry
Ran the speed–density benchmark on the adopted flat-θ=0 motor (`-perphead -headtilt 0`) + post-ATP cycle + κ=3.82e-20 + the 4b/4c-calibrated catch/kOn/τ (`-kon 2e5 -tauavg 0.5 -xcatch 2.5`), gated behind a dt=1e-5 stability check. **NO code edit, no retune, no default flip; `BoA-v1ref` byte-clean** (existing flags + GRID/COV/CAP diagnostics only). **STAGE 0 (dt gate): dt=1e-5 is STABLE across the entire ladder 500-8000** (every cell fullMat=YES, capRate=0, no NaN) — the stale SET-A "whips at 1e-5" verdict is **REFUTED** on the flat motor (one pin + rotational torque + ATP-fix + softer κ all moved stabilizing; the whip was config-1-specific). **Binding is dt-converged** (avgBound 2.78@1e-5 ≈ 2.7@1e-6, density 4000) but the **glide velocity is modestly dt-suppressed at 1e-5 (~1.6×, NOT 5×** — the probe's apparent 5× was a short-0.04s-window/transient artifact, confirmed by the same window effect within 1e-5 and a longer dt=1e-6/80k cross-check). ⇒ **sweep at dt=1e-5; implicit-J1 NOT needed.** **STAGE 1 (vs EXPERIMENT, n=3, 40k, matbed):** the net −x glide rises monotonically and bends over MM-style (netX −0.105→−0.803 µm/s over density 500→8000; avgBound 0.30→6.24); **V/Vmax vs N/KM tracks the universal x/(1+x) to |Δ|≤0.03** — the SHAPE is correct. **BUT the fitted KM ≈ 7500 µm⁻² is ~470× the experimental KM≈16**, so on the ABSOLUTE density axis the curve is shifted ~470× too far: **at a normal dense-assay density (~500/µm², experiment ~97% saturated at ~2.8 µm/s) the model glides only ~0.1-0.17 µm/s — a few % of experiment.** It reaches the right velocity order only at NON-physical densities (≥8000); **jba: don't exceed experimental density ⇒ the 16000-32000 tail was NOT run** (unphysical). **VERDICT = OUTCOME 2 (velocity DEFICIT at physical density):** MM shape correct, but not gliding-functional where real assays operate — the normalized "shape pass" rescales away the 470× gap and overstates functionality. **Lever = DUTY/BINDING density, not geometry** (the head-angle sweep already showed angle is a non-lever): the flat single-tip head binds **~7× sparser** than the config-1 two-point head of the 4b study (avgBound 2.78 vs 19 @ density 4000, same kOn), stacked on the 1.5% reaction-limited duty ⇒ KM pushed ~470× high. **FLAGGED for the planner (open thread 4):** revisit kOn/duty or the flat-head engagement (NOT the geometry); no retune here. **instSteady ~6.2 µm/s is the density-INDEPENDENT thermal-wander floor (NOT grip-lock)** — the directed glide is a real signal rising out of it. avgBound = internal diagnostic only (no pass/fail). Report: `PHASE2_SPEED_DENSITY_FINDINGS.md`; raw `RUN_LOGS/2026-06-29_sd_{stage0,stage1,probe,dtresolve,forcetail}.txt`. `./run_gliding.sh -gpu -perphead -headtilt 0 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -matbed -density <D> -dt 1e-5 -grid -seed <n> 40000`.

## 2026-06-29 — phase-2 HEAD-ANGLE SWEEP (MEASUREMENT-ONLY): hold the perp-head topology fixed, vary ONLY the head↔filament rest angle θ — the head ANGLE is a NON-LEVER for ensemble transport (outcome c); FLAT binding glides as well as ⊥ (favorable faithfulness payoff)
Removed the attachment-topology confound from `PHASE2_PERP_HEAD` (config-1 two-pins vs perp-head one-pin+torque): held the perp-head topology FIXED (single tip pin + the ⊥-orientation PAIRS torque throughout) and rotated ONLY the torque's target angle θ between head-aligned-with-actin (θ=0, the biological FLAT prior) and full-⊥ (θ=90, = perp-head). **Surface-free angle rule** (`-headtilt <deg>`/`-headtiltsweep`, baked into the frozen `perpRest` so the bond kernel is UNCHANGED): `perpRest=normalize(u−(u·f)f)`, `f_hat=sign(u·f)·f`, `Target(θ)=cosθ·f_hat+sinθ·perpRest`. Verified `snapPerpRest` is surface-free in the non-degenerate case (ẑ only in the `mag<0.1` fallback, which the OFF-AXIS Stage-1 bind φ=40° avoids). **Stage 1 (single-motor force-decomp filter, dt=1e-6, Brownian off, CPU):** the ⊥-torque WINS at every θ — the head HOLDS the commanded angle (head∠f≈θ, |Δ|≤3°); but transport-grade settled axial force appears ONLY at θ=90 (**+1.96 pN, 2.56:1** transverse:axial; θ=90 reproduces the published perp-head +1.94/2.35:1), is ~0 and sign-unstable for θ∈[0,75] — because the converter delivers axial force only when the head is ⊥ AND the lever lies along actin (lever∠f→3.2°, J1 strained 84.6° projecting axially), which co-occur only near θ=90 (geometry, not a torque failure). **Stage 2 (the functional readout — ensemble glide, GPU device-resident `buildPlan`, matbed, dt=1e-6, 40k, θ∈{0,45,90}×density{500,1000}×n=3, all 18 cells fullMat=YES):** EVERY θ glides −x and RISES with density; **flat θ=0 (netX −1.00→−2.59 µm/s) ≈ ⊥ θ=90 (−1.22→−2.54), within SEM**; the **intermediate θ=45 is the WEAKEST (−0.74→−1.26) despite the HIGHEST binding (avgBound 6.98 vs 6.80)** — it grip-locks. **VERDICT = OUTCOME (c), refined: the head↔filament rest angle is a NON-LEVER for ensemble transport** — (a) intermediate-best REJECTED (θ=45 is worst), (b) only-near-⊥-glides REJECTED (flat glides equally). The sharp single-motor isometric peak at θ=90 does NOT transfer to an ensemble-velocity advantage over flat (isometric≠ensemble, again). **FAVORABLE faithfulness payoff (the headline):** the literature-correct FLAT head (θ=0) transports as well as the biologically-wrong ⊥ stance ⇒ the model does NOT need the perpendicular head for transport; the planner's "rotate the head↔neck rest on a flat two-point head" surgery (synthesis open-thread 1b) is de-risked — flatness costs no transport. **Flagged follow-on (NOT built):** the production/ring tiebreaker for the degenerate (u∥f) bind = tip-pin with no torque + DEFER the perpRest freeze until the ⊥ component crosses a threshold, then snap (the sweep is Brownian-off/off-axis ⇒ no degeneracy, not exercised). **Additive/flag-gated; default+config1+canonical+`BoA-v1ref` BYTE-IDENTICAL** (git-stash A/B on default+config1 diags identical; `-headtilt 90`≡`-perphead` bit-for-bit; run_stroke 6.96nm PASS). New: `MotorStore.headTiltCS`, `CrossBridgeSystem.snapPerpRest` +tilt param, `-headtilt`/`-headtiltsweep`/`-offaxis`, `headTiltSweep()`, off-axis branch in `decompRun`. Report: `PHASE2_HEADANGLE_SWEEP_FINDINGS.md`; raw `RUN_LOGS/2026-06-29_headtilt_stage{1,2}.txt`. `./run_gliding.sh -headtiltsweep` (Stage 1) / `./run_gliding.sh -gpu -perphead -headtilt <θ> -matbed -density <D> -dt 1e-6 -grid -seed <n> 40000` (Stage 2).

## 2026-06-29 — PLANNER SYNTHESIS: the motor-transport arc (calibration → SET-A failure → red-herring correction → ATP-release fix), parameterization logic, and the OPEN threads. (Connective reasoning the per-task findings docs don't carry; status is HONEST — transport UNBLOCKED, not fully restored.)

**Purpose:** the per-task findings docs (`PHASE2_*_FINDINGS.md`) hold the measured data; this entry captures the planner-level reasoning, the framing decisions, and — importantly — the places where a first reading was WRONG and got corrected. Read this for the "why," the findings docs for the numbers.

### The κ / peak≈stall parameterization logic (the standing rule for setting motor stiffness)
- **Set κ by matching the measured UNITARY FORCE, not the raw stiffness.** The model's stall = stiffness × stroke, and the stall IS a force, so it must be consistent with the measured single-molecule force (~5 pN skeletal, Finer). Force-matched: **κ 6.4e-20 → 3.82e-20 N·m/rad (tip 1.0 → 0.597 pN/nm, stall 8.4 → 5.0 pN)** — authorized, applied, now the config-1/perphead default.
- **Why not match the stiffness:** Kaya-Higuchi's 2.6 pN/nm × 8 nm stroke = ~21 pN, which CONTRADICTS the measured ~5 pN skeletal unitary force. A linear spring can't honor both. The self-consistent skeletal point (force ~5 pN, stroke ~8 nm ⇒ stiffness ~0.6 pN/nm) sits INSIDE the tuning band; the 2.6 pN/nm "decoupling" in the stiffness sweep is an artifact of using a stiffness that overpredicts the force. **The nonlinear (Kaya-Higuchi buckling-asymmetric) spring is what reconciles a high tension-stiffness with a low delivered force** (stiff in tension, softens before stiffness×stroke) — banked for the FORCE-VELOCITY benchmark, where the assisting/buckling side governs. Not needed (and ~invisible) until then.
- **The stiffness sweep result, stated honestly:** peak/stall holds within 2× across tip 0.36–1.45 pN/nm (crosses 1.0 at 0.73), picking the SOFT-to-moderate regime where NMII/Myo2 likely sit. But the "4× robust band" is PARTLY just the geometry of a 1/stiffness relation under a 2× tolerance — the meaningful content is the CENTERING (soft regime) + the production point being inside, NOT a deep attractor. The angle is DEGENERATE (≤6% across 45–80° at fixed stroke+stiffness) ⇒ 60° vs 70° is a faithfulness choice, not a performance one.

### peak≈stall is an O(1) COHERENCE check, NOT optimal tuning (the thesis-framing decision)
We should NOT expect peak/stall = 1.0, and writeups must not claim "the bond is tuned to the motor" (a just-so story a reviewer will reject). We expect **O(1)** for a MECHANISTIC (not adaptive) reason: the catch-slip bond's load-sensitivity and the motor's force output are readouts of the **same strained lever**, so they share a force scale by construction — a working motor REQUIRES them in the same ballpark (a bond peaking at 0.1 pN on a 6 pN motor would detach before generating force). So peak≈stall is a **falsifiable internal-coherence test** (mechanics & kinetics agree on the force scale), which the model PASSES (0.72 pre-force-match, 1.2 post). Frame as "internally coherent," NEVER "reproduces biological tuning." The interesting result was always "it's O(1) and falls out without being forced," not "it's exactly 1.0" (which would be suspicious in a coarse model).

### v1 IS A SHAPE-ONLY ORACLE (correcting a recurring slip)
v1 is validated ONLY for the density-velocity SHAPE (rising→saturating), and roughly. It is **NOT** a quantitative anchor for absolute glide velocity, avgBound, or bound-state fractions. Do not report v2 numbers as "below v1 / wrong vs v1" — the only quantitative velocity anchor is the EXPERIMENT (Walcott/Warshaw skeletal Vmax ~2.9 µm/s, V = Vmax·N/(KM+N), KM ~16; the units-robust comparison is normalized V/Vmax vs N/KM).

### The transport arc — what happened, with the corrections
1. **SET-A density sweep FAILED** (`PHASE2_SETA_DENSITY_SWEEP`): the calibrated config-1 motor did NOT transport (velocity flat/~0, no MM curve), with TWO separable problems — (a) NUMERICAL whip at dt=1e-5 (uncapped Hookean-J1 overshoot; banked implicit-J1 fix; dt=1e-6 stopgap), (b) PHYSICAL no-coherent-transport even when stable.
2. **Force-decomposition detour** (`PHASE2_FORCE_DECOMPOSITION` → `PHASE2_BOUND_GEOMETRY`): read ~22:1 transverse:axial and chased a "stroke misprojection" geometry story. **The bound-geometry report refined it:** swing plane CONTAINS the axis, the real issue was the TWO-POINT head pin (head can't slide, axial swing goes to the anchor, only the transverse reaction reaches actin).
3. **Perp-head experiment** (`PHASE2_PERP_HEAD`): removed the rear pin, held the head ⊥ via a PAIRS torque. Single-motor force-decomp gate PASSED (22:1 → 2.35:1, axial force 0.036 → 1.94 pN). **BUT the addendum showed the ENSEMBLE glided WORSE** (−0.57 vs the two-point's weak glide), avgBound ~42, grip-locked.
4. **THE RED-HERRING CORRECTION (jba, key):** the whole axial-force story does NOT predict ensemble transport. The OLD two-point motor DID glide weakly (−0.5 to −2 µm/s) — a motor with "22:1 transverse, ~zero axial" CANNOT glide, so the isometric force-decomposition headline CONTRADICTED the observed motion. The decomposition measured an ISOMETRIC HELD SNAPSHOT, not ensemble directed drift; instantaneous transverse averages out across the stochastic many-motor cycle, only the axial accumulates. **Consequence: the 22:1 / 2.35:1 numbers were measured on a BROKEN motor (mostly slack ATP heads at ~0 strain) and may be ARTIFACTS — pending re-measurement on the cycling motor.** The real question was never "is the force axial" but "why is the directed VELOCITY low."
5. **The ATP-state catch (jba, from the viewer):** many bound heads SUSTAINED in the ATP state (impossible — ATP binding IS detachment). Audit (`PHASE2_ATP_STATE_AUDIT`) root-caused it: the nucleotide cycle and the detachment are DECOUPLED CLOCKS — the cycle advances to ATP on its own schedule, detachment is force-only (catch-slip on J1 strain) and never reads the nucleotide state. The ATP dwell (10 ms, rate-limiting) is spent strongly bound; at the low J1 strain of this geometry the catch sits at its unloaded floor (~100/s) so heads ride the whole cycle. **v1 HAS THE SAME DECOUPLING** (its ATP-coupled detach `isATP()?20000:0` is COMMENTED OUT; active path is force-only) — masked in v1's default geometry by fast force-driven release, UNMASKED by the low-strain canonical/perp geometry. A faithful port of v1's active simplification exposed by a new operating point, not a v2 bug.
6. **THE FIX** (`PHASE2_ATP_RELEASE_COUPLING`, authorized): a bound head reaching ATP DETACHES (the canonical coupling; switches on v1's commented-out variant — the second deliberate v1 motor divergence). **Form A (detach AT the NONE→ATP transition) was INSUFFICIENT** — detached-in-ATP heads rebind before finishing the 10 ms recovery ⇒ rebind still-in-ATP (the audit's "rebinds primed" assumption fails at real rates). The UNIFIED EXIT RULE ("any bound head in ATP detaches") covers both entries with zero window (cycle runs after bind). **RESULT: bound-in-ATP → 0 (CPU≡GPU); avgBound 26→4 (perphead), 9→3 (config1); bound set 94% strained-ADP; catch-slip now EXERCISED (release 135→548/s, real signed forceDotFil — the calibrated Guo&Guilford bond was INERT in the ensemble before this); config1's dt=1e-6 whip incidentally GONE (over-binding was feeding it); default v1 motor byte-unchanged, still glides.**

### HONEST STATUS — transport UNBLOCKED, not fully restored
The release fix is a clean, complete success ON ITS OWN TERMS (invariant enforced, catch-slip alive, grip-lock gone, numerics incidentally tamed, CPU≡GPU, default untouched). But it did NOT fully restore velocity: directed glide went grip-locked-~0 → ~−2 µm/s (perphead) — a real improvement, but the motor still cycles correctly and glides slower than expected. **So the stuck release was a MAJOR layer but not the whole low-velocity story; ≥1 more contributor remains.** Do not write this as "transport fixed" — write it as "transport unblocked; residual velocity gap open."

### POST-FIX ADJUDICATION (`PHASE2_POSTFIX_VEL_FORCEDECOMP`, measurement-only) — perphead WINS on magnitude; the SIGN is the new open question
Ran the pending post-fix measurement. **Premise held** (both motors bound-in-ATP 0.0%, ADP-dominated ~94%, catch-slip exercised 548/678 /s). Two clean results:
- **Part 1 (velocity):** **perphead glides −x, ~1.2 → 2.5 µm/s, RISING with density, stable/low-SEM** (the experiment-like rising shape, same order as the ~2.9 µm/s skeletal Vmax). **config1 glides +x, ~1.6 µm/s, but is numerically FRAGILE** — the dt=1e-6 whip **RECURS at density 1000** (1/3 seeds exploded), no clean density trend. ⇒ **the config1 whip is NOT fully tamed by the release fix** — it was only gone at the v1box/500 operating point; it's density/seed-dependent and the implicit-J1 fix is still needed. **Opposite glide signs CONFIRMED** on clean multi-seed (config1 +x, perphead −x).
- **Part 2 (force decomp, the adjudication):** **CRITICAL methodological catch by CC** — the `-forcedecomp` instrument NEVER ran the gliding cycle / ATP-release / catch-slip (single motor, hand-set nucleotide states, one fired stroke), so the slack-ATP regime NEVER entered it. ⇒ the old 22:1 / 2.35:1 were ALWAYS measured on a strained firing stroke, **NOT slack-ATP artifacts** — they are **intrinsic stroke geometry**, and the fix couldn't and didn't change them (reproduced bit-for-bit). Through the productive phase: **perphead ⟨axial⟩ ≈ +2 pN (2.3:1); config1 ⟨axial⟩ ≈ 0, sign-unstable (≥24:1). perphead ~80–100× more axial impulse per cycle.**
- **ADJUDICATION = OUTCOME (a): perphead clearly delivers more axial transport force than config1 — the geometry change is REAL, not a broken-state artifact.** My earlier "the force-decomposition is a red herring" was right about the ENSEMBLE-velocity claim (isometric force ≠ ensemble drift) but the perphead-vs-config1 force-GEOMETRY difference is real and survives on the cycling motor. Both claims hold: isometric force doesn't predict ensemble *velocity*, AND perphead's stroke geometry is genuinely more axial.
- **THE SIGN QUESTION (single-motor +x ↔ perphead ensemble −x; config1 +x ↔ +x).** CC flagged this as a "confound." **jba's LEADING HYPOTHESIS (an interpretation, NOT directly tested — record as the hypothesis to test first, do NOT treat as a conclusion):** there is likely NO confound — (i) perphead's **−x ensemble glide is the REAL, correct transport** (the single-motor +x isometric force is simply not the predictive quantity — the same isometric≠ensemble lesson as the red-herring correction; trust the ensemble); (ii) config1's **+x is NOT coherent transport but a small +x GEOMETRIC BIAS** that survives only at low binding and **washes out as density rises** (which is exactly why CC saw config1's +x "deteriorate / no clean trend" at higher density — read as the bias being cancelled by more bound heads resisting, not as numerical fragility alone). This reading is consistent with the force decomposition (config1 axial ≈ 0 / sign-unstable ⇒ no coherent transport; perphead +2 pN axial ⇒ real motor). **If this holds, the two opposite signs are a working motor (perphead, −x) + an artifact (config1, +x), not a contradiction.** BUT it is UNTESTED — the next chat should test it, not assume it.

### OPEN THREADS (for the next session — the geometry-fix chat)
1. **THE GEOMETRY FIX (the next arc).** perphead is the better transport geometry (settled, measured). **Two things to do, in order:**
   (a) **TEST jba's hypothesis** (above) that perphead −x is real transport and config1 +x is a wash-out low-density bias — e.g. confirm config1's +x vanishes/reverses as density rises while perphead's −x strengthens (the density trend is the discriminator). Resolve "is there a sign confound" by measurement BEFORE tuning.
   (b) **The faithful IMPLEMENTATION of the perphead geometry:** get the (correct) perphead swing by **rotating the head→neck RESTING ANGLE on a FLAT, two-point-bound head — NOT by standing the head perpendicular.** The literature is clear the motor domain binds FLAT along actin (perpendicular is the LEVER, not the head); current perphead achieves the right mechanics by the wrong means (tilts the head + a surface-referenced ẑ-fallback uperp that won't transfer to the ring). The faithful surgery = keep the two-point flat head, rotate the head↔neck rest (~90°) to re-aim the converter swing. **CAUTION (measure, don't assume):** this RESTORES the two-point pin, which is what SUPPRESSED axial delivery in original config1 — so confirm the re-aimed swing on a flat two-point head transports −x like the single-tip perphead, rather than re-bending like config1. One confirming measurement, because the two-point pin has bitten before.
2. **config1 whip NOT fully tamed:** recurs at density >500 / per-seed; the implicit-J1 (1/(1+r)) or sub-step fix is still required before any production-density run (the release fix only removed it at v1box/500).
3. **Residual velocity / duty:** perphead ~1.2–2.5 µm/s is the right ORDER (vs experiment ~2.9) and rises with density — better than I feared; not obviously a problem now. But avgBound ~4 / release 548 /s — whether the duty over-corrected is still worth a look during re-validation.
4. **Re-validation suite (DEFERRED, re-run on the new duty cycle):** single-molecule duty/catch re-check (duty was calibrated WITH the release bug — re-verify), step∝lever, thermal tail, then SET-A density (the real benchmark, once the sign + whip are settled).

### TO ALSO FILE (conceptual conclusions that belong in MOTOR_BENCHMARK_TARGETS.md when next touched)
- the force-vs-stiffness parameterization rule (match unitary force; Kaya-Higuchi inconsistency; nonlinear spring as the bridge, deferred to F-V);
- the peak≈stall = O(1)-coherence-NOT-tuning framing;
- the SET-A experimental anchor (Walcott Vmax 2.9, the normalized V/Vmax vs N/KM comparison) and the v1-shape-only-oracle clarification.

## 2026-06-29 — phase-2 POST-FIX re-measure (MEASUREMENT-ONLY): clean glide velocity (config1+perphead) + force decomposition RE-RUN through the productive phase — perphead STILL more axial; 22:1/2.35:1 were NOT slack-ATP artifacts
Re-measured velocity + the axial/transverse force decomposition on the **post-fix** (ATP-release ON) motor, after the fix made the bound set **ADP-dominated** (premise confirmed: `-diag` config1 bound-in-ATP 0.0%/ADP 93.4%/lifetime 1.474ms/release 678/s/+1.83µm/s; perphead 0.0%/93.9%/1.824ms/548/s/−1.38µm/s). **Part 1 (velocity, GPU device-resident, same `-matbed` bed, dt=1e-6, 40k, n=3 seeds, densities 500+1000):** **perphead glides −x, RISES with density (netX −1.22±0.10 → −2.54±0.51 µm/s; avgBound 4.1→6.8), STABLE/low-variance, all fullMat=YES** — the experiment-like rising shape (Vmax~2.9µm/s order). **config1 glides +x (netX +1.65±0.53 @ d500) but is NUMERICALLY FRAGILE — the dt=1e-6 whip RECURS at density 1000 (1/3 seeds exploded, fullMat VIOLATED), high seed-variance, no clean density trend** ⇒ the ATP-fix tamed config1's whip only at the v1box/500 operating point, not at d1000. **Opposite glide signs CONFIRMED multi-seed** (config1 +x, perphead −x). v1 NOT used as anchor (only the ~2.9µm/s experimental Vmax + density shape). **Part 2 (force decomp, single motor, `-forcedecomp`):** **reproduces the OLD numbers BIT-FOR-BIT (config1 22:1, perphead 2.35:1 transverse:axial)** — DECISIVE because this instrument fires ONE stroke with hand-set nucleotide states, Brownian off, and **NEVER runs the gliding cycle / ATP-release / catch-slip**; the slack-ATP regime was an ENSEMBLE property that never entered it. ⇒ **22:1 / 2.35:1 were NOT artifacts of the broken slack-ATP state — they are intrinsic stroke GEOMETRY the fix cannot and did not touch.** Read **through the productive phase** (the strained ADP dwell the catch-slip governs, first 0.5–2ms): **perphead ⟨axial⟩≈+2.0 pN, transverse:axial ~2.3:1** (holds — head reaches its 87.9° ⊥ fixed point in ~0.3ms); **config1 ⟨axial⟩≈0 pN (sign-unstable, even crosses zero), transverse:axial 24→806:1**. **Per-cycle axial impulse = ⟨axial⟩×lifetime: perphead +0.0036 pN·s vs config1 ≈−4e-5 pN·s ⇒ perphead ~80–100× more axial.** **ADJUDICATION = OUTCOME (a): on the correctly-cycling motor perphead STILL clearly delivers more axial (transport) force than config1 — the geometry change matters; NOT a broken-state artifact.** **Sign confound (FLAG, pre-existing, not resolved):** the single-motor decomp predicts the ensemble glide sign for config1 (+x↔+x) but NOT for perphead (single-motor axial +x ↔ ensemble glides −x ⇒ perphead's −x glide is a dynamic/collective effect, not the isometric force; = PERP_HEAD flag #2). **MEASUREMENT-ONLY: no physics edit/retune/geometry/default change** — additive productive-phase readout in `decompRun` only (settled numbers byte-identical). CPU-fallback disclosed (Part 1 GPU `buildPlan` 3480/6960 motors; premise+Part 2 CPU). `BoA-v1ref` byte-clean. Report: `PHASE2_POSTFIX_VEL_FORCEDECOMP_FINDINGS.md`. `./run_gliding.sh -gpu -perphead -matbed -density 1000 -dt 1e-6 -grid -seed 0 40000` / `./run_gliding.sh -perphead -forcedecomp`.

## 2026-06-29 — phase-2 ATP-RELEASE COUPLING (FIX, authorized): a bound head that reaches ATP DETACHES (ATP-binding = detachment); sustained bound-in-ATP invariant ENFORCED (bound-in-ATP→0, CPU≡GPU)
Implemented the audit's fix: `NucleotideCycleSystem.cycleAtpDetach` — a copy of `cycle()` (state machine byte-for-byte, same RNG salt/draw) that adds ONE rule: **a bound head ENDING the cycle in NUC_ATP detaches**, reusing the EXACT FREE_COOLDOWN/refractory of catchSlipRelease. Routed in GlidingHarness `stepOrig`(CPU)+`buildPlan`(GPU) via `if (CONFIG1 && ATP_RELEASE) cycleAtpDetach else cycle`; default-on for config1/perphead, A/B control `-noatprelease`. **`cycle()` byte-unchanged (0 deletions) ⇒ every other harness/path untouched**; bind side untouched (no state gate, no nucleotide reset); catch-slip + powerstroke untouched (the ATP detach is the terminus AFTER the catch-slip, which still governs the strained ADP dwell); no rate/calibration/geometry retune. **KEY SUB-FINDING — Form A alone (detach only AT the NONE→ATP transition, as the audit proposed) was INSUFFICIENT** (perphead bound-in-ATP only 66→57% CPU / 72→74% GPU, avgBound 26→17.7/38): Form A leaves the detached head IN the ATP state, and in the dense bed it **rebinds before completing its ~10ms off-fil ATP→ADPPi recovery ⇒ rebinds still-in-ATP** (the audit's "rebinding requires the primed state" assumption fails at the real rates). Two entries to bound-in-ATP (the cycle NONE→ATP AND a bind-in-ATP); the **unified rule** ("any bound head in ATP detaches") covers both with **zero window** because `cycle` runs AFTER `bind` in the step order (a bind-in-ATP head is ejected the same step, before the bond kernel ⇒ no force applied). The EXIT rule, NOT a bind-side gate (the binder still binds in ATP; the head is just ejected). **REGRESSION (v1box, dt=1e-6, 40k; A/B = same-build flag flip):** `-noatprelease` reproduces the audit EXACTLY (perphead CPU 65.9% ATP / avgBound 26.17 / −4.70) ⇒ fix-OFF byte-identical to pre-fix. **perphead FIX: bound-in-ATP 0.0% (CPU) / 0.00% (GPU)** — invariant ENFORCED on both runners; **avgBound 26/37 → 4.03(CPU)≡4.00(GPU)** (CPU≡GPU within SEM, atomic on device — the cycle task writes boundSeg after bind, no later kernel clobbers it; 404 steps/s). Bound set now **ADP-dominated 93.9%** (the strained force-generating state) ⇒ the **catch-slip is now genuinely exercised** (release 135→548/s, a real load-driven rate not the 100/s unloaded floor; forceDotFil a real signed dist). **Directed glide restored** (GPU `-grid` velFitX 0.46→1.93 µm/s, netX −0.57→−2.05, instSpeed 14.4→20.0; still below default ~3.5 / v1 8.33 — REPORTED, not tuned). **config1 FIX: 0.0% ATP, avgBound 9→2.91, and its dt=1e-6 whip is GONE (velocity NaN→+1.83)** — the over-binding was feeding the stiffness blow-up. **Default v1 motor still glides −5.32 µm/s** (its path calls the unchanged `cycle`; byte-unchanged by construction). Deliberate v1 divergence (switches on v1's commented `MyoFilLink.ckRelease isATP()?20000:0`; consistent with motor-cross-bridge-exempt-from-v1-parity). DEFERRED (separate next step): single-molecule duty/catch re-check, step∝lever, thermal tail, SET-A density (all re-baseline on the new duty). New: `cycleAtpDetach`, `ATP_RELEASE`/`-noatprelease`, `boundInState` + gpuProbe device bound-in-ATP probe (measurement). `BoA-v1ref` byte-clean. Report: `PHASE2_ATP_RELEASE_COUPLING_FINDINGS.md`. `./run_gliding.sh -diag -perphead -v1box -dt 1e-6 40000` (FIX) / `... -noatprelease ...` (A/B control) / `-gpu ...` (GPU invariant).

## 2026-06-29 — phase-2 ATP-STATE AUDIT (measurement-only): sustained bound-in-ATP ROOT-CAUSED to the nucleotide↔detachment clock DECOUPLING; fix PROPOSED, NOT applied
Audited jba's viewer observation (many heads bound AND in the ATP state = yellow, sustained — heavily in `-perphead`, a few in `-config1`), a biochemical contradiction (reaching ATP *is* leaving). **ROOT CAUSE = the lead hypothesis, confirmed by code + empirically: two clocks biology fuses into one are DECOUPLED.** (1) `NucleotideCycleSystem.cycle` advances NONE→ATP→ADPPi→ADP→NONE on its own schedule and **never writes `boundSeg`**; (2) detachment (`catchSlipRelease` + 4a `bindKinetics`) clears `boundSeg` **only** via the mechanical catch-slip rate (gated on the J1-lever-strain `forceDotFil`) + the 12-pN cap and **never reads `nucleotideState`** — grep proof: every `boundSeg.set(…,FREE_*)` is in those two methods, NONE keyed on ATP. So **ATP-binding is not coupled to detachment**: a bound head reaches ATP and nothing detaches it. **Dwell arithmetic (the engine):** the on-fil rates (= v1 Env) make ATP→ADPPi the rate-limiting step — **ATP dwell = 1/100 = 10 ms (10 000 steps @ dt=1e-6), 100–200× every other state**; biologically that 10 ms hydrolysis wait is the *detached* search phase, here spent strongly bound. The mechanical floor coincides: unloaded catch rate `kOff·(αC+αS)=100/s` ⇒ 10 ms lifetime, so at ≈0 J1 strain the head rides the whole cycle. **Instrument (`-diag`, CPU, dt=1e-6, v1box 2000, 40k):** `-perphead` (stable demonstrator) **65.9% of bound heads in ATP**, forceDotFil **≈0.04 pN** (catch sees ≈0 load), release **135/s ≈ the 100/s floor**, mean bound 7.4 ms, **avgBound 26**; `-config1` **37.5% ATP** (rest held in ADP by the load-gate), release 99/s, avgBound 9 (its full ensemble velocity NaN-blows-up at dt=1e-6 = the separate config-1 whip; state counts clean). **Per-head sustained, not a flicker:** ≈0.66×7.4 ≈ **4.9 ms (~4900 steps) bound-in-ATP per episode** (perphead). config1-few-vs-perphead-many = the SAME leak, wider in perp (the ⊥-torque drives the lever not the J1 hinge ⇒ J1 strain nearer 0 ⇒ release nearer the floor). **v1 contrast:** v1's biochemStep is identical (advances state, never `onFil`) and v1's **active** `MyoFilLink.ckRelease` is force-only too (never reads the nucleotide state — the ATP-coupled detach `isATP()?20000:0` is **commented out** at `:381-387`); v1 **masks** the leak because its DEFAULT geometry generates real load ⇒ force-driven release fires fast (avgBound ~7.6, heads ejected before the ATP dwell); the perp/config-1 low-strain geometry **unmasks** it. ⇒ this is a faithful port of v1's active simplification exposed by a low-strain geometry, **not** a v2 bug/mis-order/GPU-ordering (CPU reproduces it; no runner dependence). **PROPOSED fix (NOT applied — re-baselines duty/velocity/avgBound, planner sign-off):** couple detachment to ATP-binding — (A) detach at the bound NONE→ATP transition in `cycle` (head then hydrolyzes/re-primes OFF-fil + rebinds; restores duty ~10%), or equivalently (B) deterministic release-on-ATP in `catchSlipRelease`. Diverges from v1's active path (= switches on v1's commented variant-3), consistent with the motor-cross-bridge-exempt-from-v1-parity posture; verify the default v1 motor still hits its gliding fixture (should — its force release already ejects pre-ATP). Report: `PHASE2_ATP_STATE_AUDIT_FINDINGS.md`. AUDIT ONLY — no fix applied, `BoA-v1ref` byte-clean, no production path touched. `./run_gliding.sh -diag -perphead -v1box -dt 1e-6 40000`.

## 2026-06-29 — phase-2 PERP-HEAD: remove the rear head pin, hold the head ⊥ to actin with a PAIRS torque — TRANSPORT RESTORED (force-decomp gate PASS)
Acted on the bound-geometry diagnosis (the two-point head pin can't slide on actin ⇒ the converter swing delivers only a transverse bending couple). jba's fix, ONE structural change to config-1: **(1) REMOVE the rear pin** (pin B/`bindArc2`); **(2) KEEP the tip pin** (pin A/`bindArc`) + the tip binding search; **(3) ADD a PAIRS-form ⊥-orientation torque** driving head.uVec → `perpRest`, the **frozen-at-bind** ⊥-to-filament direction nearest the bind uVec — same dt-robust `fracMove·angle/((1/Γ_rot,h+1/Γ_rot,s)·dt)` damping-limited form as F9/F10 (no Hookean overshoot), strength 0.50 = the prior rear-pin scale, +T head/−T seg. Everything else HELD (Hookean-J1 0°↔60° converter, averaged catch, kOn, τ, κ=3.82e-20, `forceDotFil = signed J1 lever strain`). Gate = `-forcedecomp` on the new head (single motor, gliding topology, dt=1e-6, Brownian OFF), three-way vs old config-1 + default. **uperp = (0,0,+1)** — bind head is axial (along x̂) ⇒ degenerate ⇒ the **ẑ-fallback fires** (project the surface normal; head driven to stand up ⊥). **GATE PASSES both criteria:** **(a) the head HOLDS ⊥** — head∠x̂ settles **87.9°**, a STABLE fixed point (the orientation torque WINS over J1: J1 sits at 82.6°/22° off its 60° rest = it drives the lever, lever swings to ∠x̂ 5.3° nearly along actin); **(b) transverse:axial FLIPS 22:1 → 2.35:1** (sustained held axial force **0.036 → +1.94 pN, 53×**; transverse 0.789→4.56), into the **default v1 motor's order** — the artifact (≈zero axial) is GONE, transport restored. Released cross-check: filament now translates axially (Δx +5173 nm vs old −822). **Flags (report only, NO further changes per the task):** not yet axial-DOMINATED (transverse still ~2.3× axial; the standing head's tip pull has a large ⊥/−z component — surface-absorbed in a real assay, or tune later); axial **sign +x** (default glides −x) = a uperp-direction/converter-polarity choice for calibration, not a mechanism failure. No bail boundary triggered (head holds ⊥, transport improved, stable at dt=1e-6). Mechanism gate ONLY — no ensemble/kinetics/step∝lever. **Additive/flag-gated:** new `CrossBridgeSystem.bondForcesCanonicalConfig1Perp` + `MotorStore.perpRest` (default-zero, read only by the perp kernel) + `-perphead`/`-perpfrac`; the existing config-1 kernel + default path + `BoA-v1ref` byte-unchanged; CPU host-side <2s, no GPU. Report: `PHASE2_PERP_HEAD_FINDINGS.md`; gate `./run_gliding.sh -perphead -forcedecomp`.
**Addendum — PERP-HEAD wired into the GLIDING pipeline (CPU `stepOrig` + GPU device-resident `buildPlan`).** `-perphead` decoupled from the gate ⇒ a gliding-capable motor variant (gate is now `-perphead -forcedecomp`). New one-shot `CrossBridgeSystem.snapPerpRest` FREEZES `perpRest` at each fresh bind (gated on `canonSnap`, read before `snapCanonicalHead` clears it; ẑ-fallback for the near-axial bind pose; writes only `perpRest` ⇒ safe early on the GPU graph); bond routes to the perp kernel; `xbParamsC1` → size-6 (`[5]=orientFracMove`); `perpRest` added to device transfers. **All PERPHEAD-gated ⇒ default/config-1/canonical byte-unchanged** (default gliding re-ran −6.6 µm/s, avgBound 7.34). **The perp motor DOES glide −x, stably (dt=1e-6)** — but converts little to net transport + heavily over-binds: GPU v1box 40k (0.04s) net x-vel **−0.57 µm/s**, velFitX 0.46, inst 14.4, **avgBound ~42** (vs default −6.6/~7 and v1 8.33/7.6). So the single-motor mechanism win (axial restored, head ⊥) does NOT yet yield ensemble speed — the ⊥-held heads grip-lock in a many-motor tug-of-war and rarely release (the catch reads a low J1 strain); needs release/duty calibration + dt<1e-5 (whips at 1e-5 like config-1). An ensemble OBSERVATION, not a re-tune. `BoA-v1ref` byte-clean. `./run_gliding.sh -gpu -perphead -v1box -dt 1e-6 -grid 40000` (+ `-3js threejs_perphead`).

## 2026-06-29 — phase-2 BOUND GEOMETRY (measurement-only): the powerstroke misprojection is the TWO-POINT HEAD PIN, NOT the swing-plane orientation (refines the force-decomp framing)
Reported the config-1 motor's actual bound pose through the stroke (uncocked J1 0° vs cocked J1 60°), gliding topology, dt=1e-6, Brownian OFF (`-boundgeom`; reuses the `-forcedecomp` single-motor setup, NO kernel edit). **SURPRISE that refines `PHASE2_FORCE_DECOMPOSITION`:** the converter **swing plane CONTAINS the filament axis** (sweeps in x–z, lever rotation axis = −ŷ ⇒ normal ŷ ⊥ x̂) and the **lever LOAD end sweeps ALONG the filament** (Δx +3.6 nm > Δz −1.7 nm, ~2:1 axial) — both *canonical-like*. Yet the filament still gets only transverse force, because the **head is rigidly TWO-POINT-pinned** (head ∠x̂ 2.2°→0.4°, head-tip Δ≈0.04/0.10 nm ≈ 0): it **cannot slide along actin**, so the axial stroke is delivered to the **ANCHOR side** (lever.end1→rod→fixed tail; rod rotates about the anchor, rod.uVec_x −0.088→−0.046), and the head transmits to actin only the **transverse reaction** of the converter torque (tv=lever×head ≈ +ŷ, reacted by the two x-separated pins as a ±z bending couple = the SET-A whipping). Motor "stands" with rod ≈⊥ actin (∠x̂ ≈86°), head flat on actin (∠x̂ ≈0°), lever swinging 20°→62°. **WHY (the artifact):** the **two-point head pin** (head can't slide on actin) — NOT the anchor direction, NOT a swing-plane-perpendicular issue (the plane contains x̂). The head-vs-lever bound orientation (head along x̂, lever swinging in x–z) sets the converter torque axis to ŷ so the x-separated pins react it transversely. **Canonical myosin:** the swing slides the head's contact ALONG actin (axial transport); the two-point rigid pin breaks exactly that. **Planner (report only):** to transport, the head must convert the swing into axial force on actin — relax the two-point pin to a single sliding attachment / add the default's F9 head-pivot-on-actin (90°→120°, dropped by the canonical re-architecture) / re-orient the converter axis. Schematic (the key artifact): `bound_geometry_schematic.png` (x–z swing-plane view + head/pin zoom). Measurement-only; CPU <2s; default & `BoA-v1ref` byte-clean. Report: `PHASE2_BOUND_GEOMETRY_FINDINGS.md`; `./run_gliding.sh -boundgeom`.

## 2026-06-28 — phase-2 FORCE DECOMPOSITION (measurement-only): WHY config-1 barely glides — the powerstroke force is delivered ~22:1 TRANSVERSE:axial (misprojected/bending), NOT a cancelling couple, NOT inverted polarity
Decomposed the force the config-1 cross-bridge delivers to the FILAMENT in the gliding (transport) topology — the ONE path the single-molecule ladder never tested (tail anchored, head two-point-pinned, filament must move). Single motor, dt=1e-6, Brownian OFF, deterministic (`-forcedecomp`; host replica of the PAIRS pin formula, NO kernel edit). Fire one powerstroke (J1 rest 0°→60°), decompose the seg-side force on segU + the two PAIRS pins (tip@bindArc, rear@bindArc2) separately. **VERDICT = DIAGNOSIS 2 (MISPROJECTED/TRANSVERSE STROKE):** net force on the filament is **transverse 0.789 pN vs axial 0.036 pN ≈ 22:1** (11:1 at the transient) — overwhelmingly ⊥ to the transport axis (it BENDS the filament = the SET-A whipping), only ~1/22 axial ⇒ the **weak** ~0.5–2 µm/s glide (vs the default v1 motor's clean **−x axial −0.045 pN** / −5.7 µm/s, the transport reference). **Diagnosis 1 (cancelling couple) RULED OUT** — the two pins' axial forces ADD (+0.017,+0.019; |net|/(|A|+|B|)=1.0), they don't oppose; the opposition is in the TRANSVERSE (z) channel (a bending couple). **Diagnosis 3 (inverted polarity) NOT robustly supported** — the isometric axial residual reads +x but is tiny+confounded (flips to −x on release; the dt=1e-6 ensemble glides −x: velFitX +0.45/+2.44/+1.59). **Mechanism (geometry):** in the gliding bound pose the motor stands ⊥ to the filament (rod/lever ≈+z down to the anchor, head ≈+x along actin) ⇒ J1 torsion `tv = lever×head ≈ +y` ⇒ the converter swing rotates the head about +y, moving the tip in **z (⊥ filament)** ⇒ transverse force. The default transports by a DIFFERENT mechanism the canonical re-architecture dropped — F9 head-pivot-on-actin (90°→120°, the "stroke=HEAD_LEN" result) sliding the contact ALONG +x. One mechanism explains BOTH SET-A findings (whipping + weak transport): a transverse-delivered stroke. **REPORTED, NOT fixed** (transverse≫axial is a planner geometry decision — head-pivot-on-actin term / in-plane converter axis / revisit the two-point head pin). Released cross-check (qualitative): config-1 moves more ⊥ (Δz −1260 > Δx −822 nm), default more axial (Δx −4006 > Δz +2388 nm). Measurement-only; CPU <2s; default & `BoA-v1ref` byte-clean. Report: `PHASE2_FORCE_DECOMPOSITION_FINDINGS.md`; `./run_gliding.sh -forcedecomp`.

## 2026-06-28 — phase-2 SET-A speed–density EMERGENT benchmark: κ force-matched to 5 pN; clean read finds TWO separable failures (numerical whipping + physical weak/no transport) — the curve cannot be extracted
Force-matched κ (authorized default change) to the 5 pN skeletal stall: **κ=3.82e-20, tip 0.597 pN/nm, stall 5.00 pN** (was 8.4); tightens 4c peak≈stall to 1.2. Built the full-mat bed (`-matbed`: 5.8×1.2µm, filament at +x edge glides −x through a uniform lattice, ~2.9µm runway) + a measurement-window coverage check + a post-settling least-squares `velFitX` (the directed-drift estimator; the 2-pt net chord and instantaneous speed badly overcount the thermal/tug-of-war wander). GPU device-resident sweep, kinetics FROZEN (no nudge). **Result: the calibrated config-1 motor does NOT yield a rising-saturating curve** — at faithful dt=1e-5 the directed velocity is flat/sign-flipping near zero (velFitX −0.46/−0.40/−1.36 µm/s @ density 500/1000/2000), numerical blowups begin at density ~3000, NaN by 8000. **A dt=1e-6 + default-motor control pair DISENTANGLES two effects:** (a) the violent **whipping is NUMERICAL** — uncapped-Hookean-J1 overshoot, **cured by 10× finer dt** (max filament bend 844→21 nm, straightness 0.25→1.000); (b) the **weak/no transport is PHYSICAL** — at dt=1e-6 (filament dead straight, stable) it glides only ~0.5–2 µm/s vs the **default v1 motor's −5.7** on the same bed. Neither is κ (old/new κ both ≈0) nor a harness bug (default glides straight). Prior "≈9 µm/s @4000" was a settling artifact (M=8000 = the settling window). **MM fit: no curve** (flat near 0 ⇒ no Vmax/KM; non-physical fit rails KM to the grid edge); Walcott/Warshaw Vmax 2.9 cannot be placed. CPU≡GPU velocity agrees (~0, chaotic-bursty avgBound overlaps). Bail boundaries TRIGGERED, reported, nothing forced. Two planner threads: (1) numerical — land the banked implicit-J1 `1/(1+r)` / cross-bridge sub-step before any production-density ensemble; (2) physical — the transferability gap (single-molecule-correct, ensemble-incoherent), root-caused by the force-decomposition above (transverse misprojection). Plots `seta_speed_density*.png`; default & `BoA-v1ref` byte-clean. Report: `PHASE2_SETA_DENSITY_SWEEP_FINDINGS.md`; `./run_gliding.sh -matbed -config1 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -density <D> -grid -gpu -seed <n> 40000`.

## 2026-06-28 — phase-2 step 4d: κ + powerstroke-angle SENSITIVITY of the emergent peak≈stall — ROBUST across a ~4× stiffness band; angle degenerate at fixed stroke+stiffness (MEASUREMENT-ONLY, no default flipped)
Mapped how sensitive the 4c emergent peak≈stall tuning (catch-slip peak 6.0pN ≈ independent stall 8.4pN) is to crossbridge stiffness + powerstroke angle. MEASUREMENT-ONLY: production κ (6.4e-20, tip 1pN/nm) + angle (60°) UNCHANGED; bond (xCatch 2.5)/τ/kOn HELD; one-shot held-pose `motorStallPN` evals (sub-second, no GPU/long run). Parameterized `motorStallPN(κ,L,angle)` via an imposed-deflection held-lever trick (lever at 60+θ ⇒ |deflection|=θ; NO kernel edit), `-stiffsweep`. Bond peak HELD at the κ-independent 6.0pN. **Sweep 1 (κ, tip 0.36→3 pN/nm):** kernel stall = analytic (κ/L)·(π/3) = tip×8.38 EXACTLY; peak/stall ∝ 1/stiffness; **peak≈stall (ratio 0.5–2.0) holds for tip ≈ 0.36→1.45 pN/nm (a ~4× band, ROBUST not fine-tuned), crossing 1.0 at 0.73**; production 1pN/nm INSIDE (0.72), slow ~0.5 inside (1.43), stiff Kaya-Higuchi 2.6 OUTSIDE (0.28 — mechanical stall outruns the held skeletal bond). **Sweep 2a (angle, FIXED lever 8nm):** stroke 2L·sin(θ/2) + stall (κ/L)·θ both RISE with angle (45→80°: stroke 6.1→10.3nm, stall 6.3→11.2pN); kernel=analytic exact. **Sweep 2b (angle, FIXED stroke 8nm + FIXED tip-stiffness):** stall 8.21→8.69pN across 45–80° = **6% ⇒ ~ANGLE-INVARIANT** (the chord-vs-arc θ/(2sin(θ/2)) correction) — 60° vs 70° doesn't materially change the motor once stroke+stiffness are pinned (the degeneracy: stall ≈ stiffness×stroke). **Nonlinear-spring note (comment only):** stall is a TENSION-side property ⇒ the linear κ is the tension-side stiffness, a future buckling-asymmetric J1 leaves peak≈stall intact + only reshapes the assisting-side/F-V curve. **Verdict: the emergent tuning is ROBUST across the soft-to-moderate stiffness envelope bracketing production, not a razor edge; angle degenerate at fixed stroke+stiffness.** Graphs: `stiffness_angle_sweep.png`. Default byte-identical (−2.459/8.65, run_stroke 6.96nm); κ/angle UNCHANGED; `BoA-v1ref` byte-clean; sole occupant of aorus. Report: `PHASE2_STIFFNESS_ANGLE_SWEEP_FINDINGS.md`; `./run_canongliding.sh -stiffsweep`.

## 2026-06-28 — phase-2 step 4c: catch-slip bond recalibrated to Guo & Guilford (BOTH pathways, ~6 pN peak) + the EMERGENT "peak ≈ stall" tuning CONFIRMED (thesis showcase); flag-gated, NO default flipped, κ untouched
4b calibrated the catch against Veigel (catch-only), leaving slip floating. Recalibrated BOTH pathways to Guo & Guilford 2006 (the exact two-parallel-Bell structure the model uses): **xCatch=2.5nm (GG x_c), xSlip=0.40nm (GG x_s), αCatch/αSlip=11.5 (GG k_c/k_s 11.7), kOff=100/s base.** Swept lifetime vs RESISTING load PAST the peak (0→+10 pN) on the averaged catch (τ=0.5ms HELD). **Piece 1 — catch-slip SHAPE reproduced: RISE→PEAK→FALL** (11ms@0 → peak 68ms@~6.0pN → 48ms@10pN); **peak FORCE 6.0 pN matches GG ~6.4** (FIRM target); loaded-regime 23–68ms vs GG ~10–30ms (~2×, within the scatter the authors caveat; unloaded anchored at the duty-calibrated 10ms, NOT GG's anomalous 2.7s which the task says ignore). Before/after the re-target xCatch 3.65→2.5: peak 5.0→6.0pN (predicted ~4.8→6); 4c supersedes 4b's xCatch (GG full two-pathway curve better-determined than the Veigel single-point; trade-off: Veigel "1pN halves" now ~0.62 not 0.50, reported). **Piece 2 — EMERGENT peak≈stall tuning (THESIS-CRITICAL): catch-slip peak 6.0pN ≈ INDEPENDENT motor stall 8.4pN** (κ·60°/L, one-shot held-pose bond eval, κ NOT touched), **peak/stall=0.72 within ~2× scatter ⇒ the mechanokinetic tuning EMERGES** (two independently-computed quantities coincide; Guo & Guilford's deepest claim reproduced as structure-function, not forced). Pathway ordering preserved (catch fast+load-sensitive, slip slow+insensitive). **Piece 3 — kOn re-check:** recal barely shifts the zero-load dwell ⇒ duty 1.69% at kОn 2e5 (in 1–2% target), reaction-limited (P_cap 0.015 ≪1, Guard 1) — no re-tune. Flagship note: SET-A skeletal ~6pN; for Myo2 the peak should track Myo2's stall — the RELATIONSHIP peak≈stall is the transferable claim. F-V curve held DOWNSTREAM (bond pinned to bond data protects the F-V emergence claim). **Default byte-identical** (−2.459/8.65, run_stroke 6.96nm); `BoA-v1ref` byte-clean; CPU measurement instrument; sole occupant of aorus. Calibrated values (xCatch 2.5, xSlip 0.40, ratio 11.5, kOff 100, kОn 2e5, τ 0.5ms, κ 6.4e-20) reported for sign-off; NO default flipped. Report: `PHASE2_CATCHSLIP_RECAL_FINDINGS.md`; `./run_canongliding.sh -csrecal -kon 2e5 -xcatch 2.5`.

## 2026-06-27 — phase-2 step 4b: catch FORCE-DEPENDENCE calibrated to Veigel d≈2.7nm (effective 2.78nm) on the averaged catch + joint kOn re-tune + transferability RESOLVED via speed-density; thesis-critical; flag-gated, NO default flipped
The thesis-critical calibration, on the 4a fluctuation-robust (averaged, τ=0.5ms) catch. Sequenced d→kOn→transferability (each knob on its cleanest observable). **Piece 1 (d→Veigel):** swept `xCatch`, measured detachment rate (1/t_on) vs sustained load (`-fext`) single-molecule; **xCatch=3.65nm → effective d=2.78nm** (0→+1pN rate ratio 0.509 ≈ Veigel "1 pN halves"; base ~9ms dwell preserved — d is the SLOPE not the intercept). Effective d SATURATES below nominal xCatch (the assisting-slip term offsets the catch at +load ⇒ 3.65 not the naive 2.97); calibrated FRESH on the averaged config (instantaneous d would mis-set). **THESIS framing:** d is the lumped calibrated number, but the load it responds to is the REAL J1 lever strain `(κ/L)·deflection` (emergent), NOT a prescribed k_off(F) — fits ONE number, the curve emerges from d×real load. **Correspondence:** `-fext` adds to the SAME forceDotFil the catch reads ⇒ catch-input↔real-J1-strain 1:1 BY CONSTRUCTION (no gain); native isometric J1-strain 0.087pN, same pN scale as the calibration loads (external-load→J1-strain mechanical gain = the deferred force-velocity benchmark). xSlip left + flagged (force-velocity target). **Piece 2 (joint kOn re-tune):** the 4a t_on fix raised duty to ~3.5% at kОn 5e5; **kОn=2.0e5 re-hits duty 1.54%** (2.5e5→1.87%), STILL reaction-limited (P_cap 0.014 ≪1, Guard 1 holds); d doesn't change the zero-mean duty (sequential isolation clean). **Piece 3 (transferability — RESOLVED via density):** at calibrated (d 2.78nm, kОn 2e5, τ 0.5ms) the gliding speed RISES with density and REACHES the experimental V0 — ~1µm/s @500/µm² (sparse v1box) → **~9 @4000 → ~11 @10000** (myosin-II target / v1's 8.33). ONE parameterization hits BOTH single-molecule duty 1.5% AND experimental V0 — the step-3 velocity tension was a LOW-DENSITY artifact (rising-saturating speed-density, the correct experimental form), dissolves at realistic density. **Compared to EXPERIMENT not v1.** **Default byte-identical** (no override ⇒ −2.459/8.65, run_stroke 6.96nm); **CPU≡GPU** within-SEM (avgBound 2.11/1.80). `BoA-v1ref` byte-clean; sole occupant of aorus. **Calibrated values (xCatch 3.65nm/d 2.78nm, kОn 2e5, τ 0.5ms) reported for sign-off; NO default flipped.** NEXT: the full speed-density + force-velocity ensemble benchmarks (V0/density-knee fit, the external-load→J1-strain gain). Report: `PHASE2_CATCH_DCALIB_FINDINGS.md`; `./run_canongliding.sh -dcalib -kon 5e5 -xcatch 3.65`.

## 2026-06-27 — phase-2 step 4a: the Jensen/t_on over-release FIXED by time-averaging the catch input — THE GATE: averaging WORKS on the cleaned-up Config-1 signal; flag-gated, NO default flipped
Tested whether time-averaging the catch input (the banked `RELEASE_FORCE_INPUT`/`-tauavg`, which FAILED on the OLD overshoot-contaminated dt-arc signal) fixes the step-3 Guard-2 t_on shortfall (3.85ms vs 10ms — the Jensen effect: convex exponential catch over-releases on the ±RMS J1-strain fluctuation at mean≈0). Catch constants+kOn(5e5)+κ HELD; only instantaneous→averaged. Wired `catchSlipReleaseAvg` (per-head EMA of forceDotFil, window τ) into the Config-1 single-molecule (`singleStep`) + gliding (CPU `stepOrig` + GPU `buildPlan`) via `-tauavg <ms>`; added a sustained-load injection `kinParams[18]=F_ext` (`-fext`, the force-response guard) + an autocorrelation diagnostic (`-acorr`). **FORK VERDICT: AVERAGING WORKS (τ≈0.5ms, plateau 0.1–1ms).** **(1) t_on RECOVERS** 3.85→~9.5ms at zero load over τ∈[0.1,1]ms (Jensen fixed; dips at τ≥2ms = window touching τ_load). **(2) force-response GUARD PASS** — at τ=0.5ms t_on is monotonic **1.0ms(−3pN)→9.5ms(0)→33.5ms(+3pN)** (33× range), shifted up ~2.5× vs instantaneous (uniform Jensen fix, NOT load-smoothing; the EMA passes the DC sustained load, suppresses the AC thermal). **(3) timescale separation CLEAN** — τ_thermal≈**0.01ms** (J1-strain autocorr white beyond 1 step, var 2.2pN²) ≪ τ_avg(0.1–1ms) ≪ τ_load(~ms). **Why it works now:** Config-1 removed the stiff-spring overshoot spikes that no window could suppress in the dt arc ⇒ the signal is clean white thermal fluctuation around a real mean ⇒ well-posed. **PEEK:** restored t_on raises gliding |v| 0.2→1.0–1.5 µm/s (~4×) + avgBound 3.7→~7 (into v1's 7.6 ballpark) — the velocity tension begins resolving (one problem/two observables). The t_on fix raised single-molecule duty 1.34→~3.9% ⇒ step-4b joint kOn re-tune. **CPU≡GPU** within-SEM (avgBound 7.49/7.80); **default byte-identical** (kinParams grown 18→20 size-gated; default gliding 8.65, run_stroke 6.96nm). `BoA-v1ref` byte-clean; sole occupant of aorus. **NEXT (step 4b):** catch force-dependence calibration (xCatch/d → Veigel d≈2.7nm) on the averaged input + joint kOn re-tune (re-hit duty 1–2%) + gliding-velocity transferability re-check. Report: `PHASE2_CATCH_AVERAGING_FINDINGS.md`; `./run_canongliding.sh -single -kon 5e5 -tauavg 0.5`.

## 2026-06-27 — phase-2 step 3: the FIRST real calibration — reaction-limited kOn on Config-1, against the single-molecule DUTY (kOn≈5e5; Guard-1 PASS, Guard-2/transferability flag step-4 catch); flag-gated, NO default flipped
Calibrated the binding rate out of the saturated/geometric bind-on-contact regime into the **reaction-limited** regime against the **single-molecule duty ratio** (NOT the geometry-confounded gliding avgBound/2000 — trap §1), catch+κ HELD. **Build:** a kOn gate in the Config-1 binder (`bindCanonicalTwoPoint`): a formable tip-capture binds with `P=1−exp(−kOn·Δl·dt)`, Δl the filament chord through the tight capture sphere (reuses the rate-search physics; `kinParams[14]`, ≤0 ⇒ saturated/byte-identical; wang-hash "C1KO" ⇒ CPU↔GPU reproducible). **Single-molecule assay** (`-single`): 256 Config-1 motors held in PROXIMITY under a FIXED filament (anchor z positions the cycling head's bob at the filament ⇒ kinetically-gated duty). **kOn SWEEP (single-molecule):** duty monotonic in kOn, hits ~1–2% at **kOn≈5e5 µm⁻¹s⁻¹** (1.34%) with per-encounter **P_capture 0.058/0.034 ≪1 ⇒ REACTION-LIMITED (Guard 1 PASS** — NOT saturation-compensated). **Guard 2 FLAG: t_on≈3.85ms vs ~10ms target (~2.6× short)** — the **Jensen effect** of the fluctuating J1-strain (mean≈0, RMS≈2.5pN) on the instantaneous-load exponential catch (it over-releases under fluctuating load even at zero mean); the kOn calibration is NOT corrupted (duty hit reaction-limited) but is conditional on the current catch ⇒ step-4 catch fix (time-averaged input, the banked RELEASE_FORCE_INPUT) needs a small **joint kOn re-tune**. **Transferability PARTIAL:** the single-molecule kOn gives a SENSIBLE ensemble binding fraction (gliding avgBound 18→3–4, into v1's 7.6 ballpark, no longer over-binding) + the expected duty→glide-magnitude trend (|v| rises with avgBound), BUT at v1box density duty 1.34% is below the velocity-saturation knee ⇒ weak glide (|v|≈0.2 vs v1 8.33); matching v1 velocity needs higher duty (avgBound~6–8, kOn~1e6) or higher density ⇒ the single-molecule-duty and gliding-velocity operating points DIFFER (entangled with the short t_on) — resolve JOINTLY in step 4. **CPU≡GPU** within-SEM (avgBound 4.38/5.20). **No default flipped** (KON=0 saturated, byte-identical: default gliding −2.459/8.65, run_stroke 6.96nm). `BoA-v1ref` byte-clean; sole occupant of aorus. **NEXT (step 4):** catch re-calibration vs the J1-strain (time-averaged input to fix the Jensen-shortened t_on) JOINTLY with a kOn re-tune to hit single-molecule duty AND ensemble velocity. Report: `PHASE2_KON_CALIBRATION_FINDINGS.md`; `./run_canongliding.sh -single -kon 5e5`.

## 2026-06-27 — phase-2 step 2: the COMPLETE Config-1 cross-bridge (PAIRS attachments + Hookean J1) BUILT + characterized — tail vanishes, duty recovers, polarity CORRECTS to −x; calibration DEFERRED; flag-gated, default byte-identical
Built the full composed cross-bridge (MOTOR_BENCHMARK_TARGETS §6) instead of diagnosing the half-built two-F8 motor's thermal tail (PHASE2_VERSIONB §5: RMS 8.9 / max 90–116 pN). **Division of labor:** the two SOFT translational F8 springs (attachment + load-signal, Brownian head dragged by the fast filament = the tail source) are replaced by (1) **TIP+REAR PAIRS attachments** (`CrossBridgeSystem.bondForcesCanonicalConfig1`, dt-robust `fracMove·1e-6·strain/(dt·(mcHead+mcSeg))`, `moveC` reused VERBATIM) — stiff geometric pins holding the head RIGIDLY (position+orientation; roll free ⇒ F9+F10 dropped), reporting NO load; (2) **J1 → Hookean torsional spring** (`MotorJointSystem` config1 branch, size-gated: `T=κ·(θ−θ_rest)`, no /dt, NO stall cap ⇒ stall force EMERGES; rest still switches 0°↔60° ⇒ J1 still DRIVES the stroke); (3) **forceDotFil → signed J1 lever strain** `(κ/L)·(θ_rest−θ)` (PAIRS pins, J1 reports ⇒ the §6 "can PAIRS expose load?" gating question SIDESTEPPED). κ=k_tip·L²≈6.4e-20 N·m/rad, PAIRS fracMove=0.5 (UNCALIBRATED). **NATIVE CHARACTERIZATION (`-config1diag`, v1box, dt=1e-5):** **(1) THE TAIL VANISHES** — J1-strain RMS **2.5** (was 8.9), max **17.9 pN** (was 90–116), p99 6.5; **(2) dt-scaling BENIGN** — max|forceDotFil| BOUNDED+~dt-invariant (17.9/17.1/20.2 pN over dt 1e-5/5e-6/2e-6; mean/RMS rise modestly = longer bond lifetime at small dt with the uncalibrated catch + unequal sim-time window, NOT a ∝1/√dt MAX blow-up), STABLE at every dt; **(3) J1 Hookean STABLE at the operating lever** (8 nm, no NaN/overshoot 12k steps) — **bail touched+reported:** lever 4 nm (sub-biological) goes NaN (§6 caveat-a drag-vs-stiffness arm mismatch; implicit 1/(1+r) fix banked, NOT pre-applied); **(4) GLIDES −x — polarity CORRECTED** (≈−1.5…−5 µm/s uncalibrated, direction matches v1+default; the two-F8 +x was a soft-spring SLIDING artifact — rigid pins remove the slide ⇒ correct minus-end-first glide); **(5) step∝lever HOLDS** (`CanonicalMotorHarness -config1`: 8/16/24/32 nm at lever 8/16/24/32, slope ~1.0, J1 carries 100% — geometry unchanged by the spring-law swap); **(6) duty SENSIBLE** — avgBound ~18/2000 (~0.9%) up 30× from two-F8's ~0.6 (the tail WAS the duty killer); now OVER-binds ~2× v1 = the uncalibrated geometric kOn (next step). **CPU≡GPU** aggregate-within-SEM (CPU avgBound 18.7±1.0 / GPU sparse ~16.6; both −x). **Default byte-identical** (`run_stroke` gate-3 6.96 nm bit-for-bit; default gliding CPU −2.459/8.65; `run_dimer` PASS — the size-gated config1 J1 branch is byte-identical for every existing harness). `BoA-v1ref` byte-clean; sole occupant of aorus. **NEXT:** reaction-limited kOn + catch-vs-J1-strain + κ calibration. Report: `PHASE2_CONFIG1_FINDINGS.md`; `./run_canongliding.sh -config1diag`.

## 2026-06-27 — phase-2 step 1: DYNAMIC Version-B two-point binder WIRED into the live thermal gliding assay + characterized (calibration DEFERRED) — flag-gated, default byte-identical
Built the Version-B binder (immediate two-point collapse) into the `-canonical` gliding path with thermal forces ON, and characterized native binding. **No calibration** (kOn + catch UNCHANGED). **The build:** the single-point tip search is RETAINED VERBATIM; on tip-capture, test if the along-filament two-point pose is FORMABLE (rear `bindArc2 = bindArc − HEAD_LEN` lands on the SAME bound segment) and, if so, register BOTH bonds + SNAP the head along the filament in the same step; else bind fails. New `BindingDetectionSystem.bindCanonicalTwoPoint` (decision) + `snapCanonicalHead` (the head snap) + `MotorStore.canonSnap` flag; `GlidingHarness -canonical` swaps bind→two-point + bond→`bondForcesCanonical` (CPU `stepOrig` + the 23-kernel GPU `buildPlan`); `-canondiag` instruments it. **GPU-safety finding (load-bearing device rule):** doing the head-pose snap inside the EARLY decision kernel silently broke the PTX-lowered bind (GPU avgBound 0 vs CPU 0.95); bisected to graph POSITION — a **body-pose-writing task placed before the force/integrate tasks** breaks binding (even a no-op early task; the decision kernel alone binds 0.81). **Fix: place the `snapHead` task LATE (after `deriveMot`, like `integrate`)** ⇒ GPU binds; the CPU snaps at the same late point ⇒ identical timing, CPU≡GPU tracks to printed precision. **NATIVE CHARACTERIZATION (`-canondiag`, v1box 2000-motor bed, dt=1e-5, myoSpring=1 pN/nm):** (1) dynamic two-point capture FORMS live (1488/1822 tip-captures); (2) formation success **~82 %**, failures **100 % rear-off-the-minus-end** (mean fail tip-arc 10 nm < HEAD_LEN 20 nm, ≈ HEAD_LEN/segLen 11 % — the same-segment-only restriction; rear-on-neighbour is the flagged follow-on); (1-chk) **legal-pose PASS** — snapped `motDotFil = 1.0000 ≥ −0.40`, 0 violations (gates ACCEPT the canonical pose); (2-chk) **snap magnitude REPORTED** — head rot mean 34°/max 113°, rear disp mean 11/max 33 nm (jolts J1's dt-robust fracMove CONNECTION spring, not the stiff cross-bridge), bind `|forceDotFil| ≈ 0` (GENTLE — both F8 ⟂ axis), `|net F8| ≤ 12 pN` bounded perpendicular pin; (3) native **avgBound ≈ 0.6–1.0 / 2000 (LOW, not over-binding)** — the thermal lever-strain tail drives fast release ⇒ low duty (feeds the deferred kOn+catch calibration); (4) **GLIDES — directed +x ≈ 2.9–3.5 µm/s** (opposite the default −x; uncalibrated; re-rigged stroke polarity); (5) **THE KEY OPEN QUESTION — dt-gentleness only PARTIALLY survives thermal forces (REPORTED, not fixed):** phase-1's +0.285 pN was Brownian-OFF; with thermal ON the bound head Brownian-wanders against the soft 1 pN/nm springs ⇒ heavy tail mean −0.8 / RMS 8.9 / **max|forceDotFil| 90–116 pN** (implicit/sub-step tools banked, deploy decided at calibration); (6) **dt 1e-5 STABLE** (no NaN, 12k steps; also 2e-5). **CPU≡GPU** net velocity to printed precision, avgBound 0.64/0.644 within SEM. **Default byte-identical** (`run_stroke` gate-3 6.96 nm bit-for-bit; default gliding CPU −2.459/8.65 + GPU −x/8.60 unchanged; phase-1 `run_canonical` all-green). `BoA-v1ref` byte-clean; sole occupant of aorus. **NEXT phase-2 step:** reaction-limited kOn + catch-vs-lever-strain calibration. Report: `PHASE2_VERSIONB_BINDER_FINDINGS.md`; `./run_canongliding.sh -canondiag`.

## 2026-06-27 — phase-2 binder decided (Version B), + open ordering question (binder + kOn together?) — DOC-ONLY
The canonical motor's two-point attachment is NOT a two-site search — the single-point tip search is retained unchanged; the second (rear/J1) anchor is a *consequence* of the rigid head, not a second search.
- **DECISION — Version B (immediate collapse to two-point), jba.** On single-point tip-capture success, test whether the **along-filament two-point pose is formable** (the reachability test from `CANONICAL_MOTOR_FINDINGS` §3, applied at the moment of capture); if yes, **snap the head to the along-filament pose and register BOTH bonds in the same step** (no transient, no single-point dwell); if no, **the bind fails and the search continues** (don't force a two-point bond where geometry/occupancy forbids it). Chosen over Version A because it (a) never opens a single-point window where the head can pivot — i.e. cannot leak the old non-canonical mechanism back in at bind time, (b) adds no new roll-in timescale to interact with dt, (c) matches the model's existing coarse-capture / lumped-bond abstraction grain.
- **Version A (single-point bind → head rolls into the along-filament pose → rear binds on arrival) is the parked FALLBACK**, keyed to a specific future trigger: adopt A only if measurement shows the **single-point weak-bound dwell matters** — e.g. binding *rate* needs the roll-in, load biases tip-capture→two-point completion (a force-dependence of *binding* B can't represent), or the duty ratio needs the weak-bound population B collapses away. None on the table now; revisit only if a finding demands it. (If A is ever used, the powerstroke/J1 swing MUST be guarded to not fire during the single-point transient.)
- **One Version-B check to verify when built:** forcing the along-filament pose at bind time must be a *legal* bound pose under the existing search orientation gates (motDotFil, rodDotFil, α-foot) — confirm the canonical pose isn't one the gates would otherwise reject.

**Open phase-2 ORDERING question (decide at phase-2 start).** Three threads land on overlapping subsystems: (1) the Version-B two-point binder [binding search], (2) the deferred **kOn reaction-limited calibration** [binding search — myosin-actin binding is reaction-limited; the geometric bind-on-contact is the wrong regime], (3) **catch re-calibration against the new lever-strain `forceDotFil`** [release]. (1) and (2) are BOTH the binding search ⇒ natural to **rebuild the search once** (canonical-pose two-point capture AND the reaction-limited rate together), THEN re-calibrate the catch on the rebuilt motor — vs doing them separately and opening the search twice. Plus: promote `-canonical` to default + new reference, and re-run gliding/contractile WITH thermal forces (does the phase-1 dt-gentleness — geometrically-pinned head barely stretches — hold under thermal load?). Decide the ordering fresh at phase-2 start.

## 2026-06-27 — CANONICAL lever-arm motor BUILT + characterized (v1-DIVERGENT, deliberate; calibration DEFERRED) — flag-gated, default byte-identical
Re-rigged the myosin cross-bridge to the canonical lever-arm mechanism, the deliberate v1 divergence jba decided after the powerstroke read-out + `STROKE_VS_ARMLENGTH` showed the default (and v1) motor is NON-canonical (head pivots on actin via F9, stroke read at the F8 tip, J1 converter SILENT ⇒ step ∝ HEAD_LEN). **Three coupled pieces, all additive (`CrossBridgeSystem.bondForcesCanonical` + `MotorStore.bindArc2` + `CanonicalMotorHarness`/`run_canonical.sh`; the default `bondForces` byte-UNCHANGED; `BoA-v1ref` byte-clean):** (1) **TWO-POINT attachment** — F8a tip(head.end2)→site A + F8b rear(head.end1 = J1 pivot)→site B (`bindArc2`), both fixed actin material points on the bound seg ⇒ head orientation pinned by GEOMETRY; (2) **F9 REMOVED** (head no longer reorients against actin; F10 roll-align kept — two point-springs don't constrain roll, and F10's rest never state-switches ⇒ not a stroke driver) ⇒ the unchanged J1 0°↔60° converter swing drives the LEVER+tail against the pinned head; (3) **lever-strain load** `forceDotFil = Dot(F8a+F8b, segU)` (net two-point load = the converter-swing resistance reacted through the pinned head), not the tip stretch. **NATIVE BEHAVIOR (explicit Hookean F8 @ 1e-5, `myoSpring`=1 pN/nm, Brownian-off ⇒ deterministic; `-cpu` for the equilibria, GPU TaskGraph for CPU≡GPU):** **(1) the stroke is now CANONICAL — tail/load-end step ∝ LEVER, slope 1.048 nm/nm R²≈1** (stroke = 2·leverLen·sin30° = leverLen, the 60°-swing chord; 2.0/8.0/16.0/24.0/32.0 nm at lever 4/8/16/24/32), **FLAT in HEAD_LEN** (−0.06 nm/nm through head≤20 nm; head 30–40 is a large-head artifact, flagged), and **J1 carries 100%** (freeze J1 → stroke 0.000 nm; F9 already gone) — the exact INVERSION of the default's J1-silent/∝HEAD_LEN result. **(2) the §1 GEOMETRY FINDING (bail boundary, MEASURED):** two-point attachment is **0% reachable from the default perpendicular-head bind pose** (the J1-pivot rear is ~HEAD_LEN off the filament ≫ myoColTol) but **100% from the canonical along-filament pose** — the canonical motor REQUIRES the head to bind lying along actin (tip+rear span ~HEAD_LEN of actin); a dynamic two-point binder for the gliding assay needs that pose (phase-2 integration). **(3) the lever-strain SIGNAL is non-degenerate under load** (+0.285 pN cocked-isometric, sign = resisting; ≈0 unloaded by construction; gentler than the default's over-stretched tip signal). **(4) dt=1e-5 STABLE** (max|forceDotFil| 0.28 pN — *gentler* than the default single-tip cross-bridge, NO new stiff overshoot; implicit/sub-step tools not needed). **(5) CPU≡GPU bit-identical** (meanTailX/avgBound rel 0.00%; canonical bond is one per-motor kernel, reuses the CSR gather VERBATIM, no atomics) + **default byte-identical** (re-ran `run_stroke.sh`: gate-3 = 6.96 nm bit-for-bit, all 6 gates PASS). **Bind/stroke/release functional** (binds, cycles, releases via catch-slip, cap=0 — no force-cap overshoot). **DECISION RECORDED in CLAUDE.md parity contract:** the motor cross-bridge is **exempt from v1 bit-parity**, v2-canonical is the new reference; v1 stays the oracle for the actin PAIRS layer + crosslinkers. **Calibration (re-tune the catch vs the new lever-strain signal, dynamic two-point binder in the canonical pose, promote `-canonical` to default) is PHASE 2 — deferred.** Report: `CANONICAL_MOTOR_FINDINGS.md`; viewer `./run_canonical.sh -3js threejs_canonical`.

## 2026-06-26 — banked cross-bridge refinement (composed architecture, calibration-time) — DOC-ONLY
For the force∝1/L half of the lever-arm chain: **tip→PAIRS** (geometric dt-robust attachment, may dissolve the raw-F8 dt ceiling) + **J1→Hookean** (converter-spring carrying the compliance AND the catch load-signal; k_tip=κ/L² ⇒ stiffness∝1/L², force∝1/L emergent; κ≈1e-19–1e-18 N·m/rad). Steady-state 1e-5 stability ~unchanged (lever-arm factor cancels in r_rot), but dropping the J1 stall cap re-exposes transient overshoot (implicit `1/(1+r)` fix transfers). Gating question: can PAIRS expose unclipped bond tension for the catch, or does velocity-limiting clip the high-load tail (= the failed saturation lever by another route)? Deferred — 2 coupled structural changes, re-baselines v1 + re-validates from binding up. Available-now result stays §3 step∝L on the current motor. See `MOTOR_BENCHMARK_TARGETS.md` §6.

## 2026-06-26 — STROKE vs ARM-LENGTH: the working stroke EMERGES ∝ arm length, carried by the HEAD reorientation (F9), not the lever swing (J1) — MEASUREMENT-ONLY, flag-gated, default byte-identical
The lever-arm structure→function showcase, reachable with NO powerstroke change (`POWERSTROKE_MECHANICS_READOUT` confirmed the stroke is a fixed-angle converter rotation ⇒ the linear step should EMERGE from geometry × angle). Measured the in-silico unloaded-stroke assay (single fixed motor, pinned filament, F8 explicit Hookean @ 1e-5, `myoSpring`=1 pN/nm; held fixed: swing angles, all rates, spring, binding — only arm length varies; CPU runner, Brownian-off ⇒ deterministic equilibrium). **Additive `-armsweep` + `-leverlen`/`-headlen`/`-isolate` on `MotorStrokeHarness` (default = production constants ⇒ byte-identical; gate-3 re-runs 6.96 nm) + one SIZE-GUARDED optional read each in `MotorJointSystem`(`jointParams[11]` freezes J1 rest) / `CrossBridgeSystem`(`xbParams[9]` freezes F9 rest) — the established satMode pattern, byte-identical for every existing caller; `BoA-v1ref` untouched, production untouched.** **EMERGENCE CONFIRMED** (varying arm length moves the stroke continuously — a prescribed step cannot). **Stroke ∝ HEAD_LEN, ~LINEAR (slope ≈ 0.41 nm/nm, dominant on-axis dx −0.40 nm/nm, R²≈1; 3.05/6.96/11.12/15.34 nm over head 10/20/30/40); stroke ~FLAT/non-monotonic in LEVER_LEN (5–7 nm, the vector just rotates −x→−z).** **DOMINANT ARM = the HEAD reorientation (F9), NOT the lever swing (J1) — decisive isolation: J1-only = 0.00 nm, F9-only = 10.89 nm (both = 6.96, so J1 partially OPPOSES F9, sum 156%).** The model's effective amplifier is HEAD_LEN because F8 attaches at the head tip & F9 directly reorients the head about the actin contact (the converter rotation is split J1 60° + F9 30°). **FORCE ∝ arm length (RISING):** clamped stall `myoSpring·stroke` ∝ HEAD_LEN (3.0→15.3 pN over head 10→40) — the **OPPOSITE sign** from the real lever-arm trade-off (real long levers more compliant ⇒ force ∝ 1/L); the model gives force ∝ L because `myoSpring` is a SET constant, not a geometry-derived compliance. Getting real force ∝ 1/L needs **stiffness-from-geometry** (`myoSpring ∝ 1/L²`) — SCOPED FUTURE, NOT done (touches the v1-calibrated stiffness the dt arc rests on). **LITERATURE:** lands on the engineered-lever **linear** law, slope right ORDER — model 0.41 vs single-headed myosin-V **0.74 nm step / nm lever** (Purcell/Sweeney/Spudich 2002 PNAS: 7/16/20 nm at 1/4/6 IQ ≈ 2.6 nm/IQ; Ruff/Manstein 2001; Uyeda/Spudich 1996) — within ~2×, both a fraction-of-arm set by the converter angle; honest nuance = the model's amplifier is the HEAD, not the neck. **VERDICT (available-now):** the working stroke emerges from converter-rotation geometry & scales ~linearly with the amplifying-arm length, matching engineered-lever data — without a declared step. Report: `STROKE_VS_ARMLENGTH_FINDINGS.md`; `./run_stroke.sh -armsweep`.

## 2026-06-26 — dt-arc RESOLVED; benchmark on explicit Hookean 1e-5; implicit + sub-stepping banked — DOC-ONLY (decision record)
Six levers eliminated (softer spring / release-averaging / thermal-correlation / saturation / explicit-dashpot / cheap local-implicit) ⇒ cross-bridge dt ceiling is intrinsic, fix lives in the integrator (re-derives Cytosim's implicit+constraint route). **Decision:** benchmark/validate on **explicit Hookean cross-bridge @ 1e-5** (simplest, fewest artifacts, preserves the v1≡v2 oracle; spring ~1 pN/nm is the sharp direct tuning knob; 1 pN/nm = v1 internal ref, NOT experimentally validated). **Banked:** (A) implicit cross-bridge — validated more-converged integrator (halves 1e-5 error, recovers signed-load tail), **deferred to experimental-calibration time** (adopting now re-baselines v1 calibration + breaks the oracle); (B) sub-stepping bound CB+release inner loop — validated POC (~8.5× CPU ceiling, scale-invariant; interpolated-site tier), **deferred until wall-clock demands**, with two unverified build gates (GPU inner-kernel fusion mandatory; the 1e-4-outer-dt non-cross-bridge faithfulness premise NEVER tested — nothing in the arc ran at 1e-4). dt-headroom arc complete as a characterization. See `RESEARCH_THESIS §11`.

## 2026-06-26 — SUBSTEP FEASIBILITY: the two go/no-go numbers BEFORE building the cross-bridge sub-step inner loop — VERDICT: BUILD IT, with INTERPOLATED-SITE handling (a ~5–8× lever, not ~1.5×) — MEASUREMENT-ONLY, flag-gated
Measured, on faithful existing runs (NO integrator change, NO new physics), the two numbers the sub-stepping task hinges on, across the three scenes (gliding 4×1, the 0.5× dt-study scene, the dense 1× contractility flagship — the latter two are V2OneX param variants, gliding is GlidingHarness). **Additive `-substep` (+ `-outerdt`, default 1e-4) on V2OneX + Gliding (CPU runner); new host-side `SiteMotionTracker` (per-bound-motor site `= segCoord+(bindArc−½segLen)·segUVec` tracked over outer-dt windows → net displacement + least-squares DRIFT vs RMS JITTER) + inline `tns()` CPU slice timers; flag-off ⇒ `tns()` returns 0, no tracker, byte-identical; `BoA-v1ref` untouched; production default unchanged.** **MEASURE 1 — SPEEDUP CEILING (bound fraction × bound-cross-bridge slice CPU-time fraction X; net speedup ≈ 10/(1+9X) at a 10× inner ratio):** the slice (bondForces+applyHead+register+catch-slip release + the BOUND-head advance only) is **X ≈ 0.012–0.024 of the per-step wall in EVERY scene** (gliding 0.020, 0.5× 0.017, dense 0.017 at fine dt) ⇒ **implied net speedup ≈ 8.4–9.1×, ~scale-invariant** (slice and step both scale with bound count; the step is dominated by binding search + chain + crosslinkers + node tethers + the FULL motor-body advance, none repeated). Converged bound fraction ~16–17 % (dense/0.5×) / <1 % (gliding). **GPU launch-count cross-check (the device path is launch-bound ~125 µs/kernel):** the slice ≈7 kernels of ~23 (gliding) / ~55 (dense) ⇒ X_launch 0.13–0.30 ⇒ GPU ceiling **2.7–6.7×**, recovered toward the top by FUSING the inner-loop kernels into 1–3 (a build-phase task) — lower than CPU because tiny bound work still costs full launches. **MEASURE 2 — SITE-HANDLING TIER (per-outer-dt site motion vs the cross-bridge stretch):** the site moves **~3 nm per outer dt (fine dt)**, **COMPARABLE to the stretch** (net/|stretch| ≈ 0.7–1.0 in all scenes) ⇒ **FROZEN-site is UNFAITHFUL** (it discards a ~3 pN load motion = the IMPLICIT_CROSSBRIDGE explicit-site operator-split error, measured directly) — BUT the motion is **strongly DIRECTED** (drift/jitter 2.4–2.6, **93–95 % of windows directed** at fine dt; the diffusive residual jitter only ~1.2 nm ≪ myoColTol 6 nm) ⇒ **a cheap LINEAR site-predictor (INTERPOLATED-SITE) captures most of it; the co-stepped/coupled solve is NOT required.** **Gliding (the predicted frozen-site stress test) is the MOST interpolable, not the least** — its fast site is the most directed (95 %). **OVERALL: BUILD the bound-cross-bridge + catch-slip sub-step inner loop with an INTERPOLATED-SITE predictor** — economics clear the bar everywhere (8.5× CPU / ~5–7× GPU-fused ceiling), site-handling is the affordable middle tier (interpolated, not frozen, not co-stepped) uniformly. **Caveat (honest):** interpolation reduces the site error from ~net (3 nm/3 pN) to the diffusive residual (~1.2 nm/1.2 pN), it does NOT zero it; the head's own fast thermal (the dominant catch driver) IS resolved by the inner loop (the point), but if the catch proves sensitive to the residual diffusive site load, the reduced-pair/co-stepped site is the documented next tier. **Premise (flagged):** the 10× model assumes only the cross-bridge+release needs the fine inner dt (supported: dt ceiling = cross-bridge overshoot; binding flux ~dt-invariant) — a coarse-outer-dt stability check of the non-cross-bridge subsystems is the first build-phase gate. dt-robust (1e-5 op ≈ 1e-6 fine, fine sharpens it). Default path re-smoked clean. Report: `SUBSTEP_FEASIBILITY_FINDINGS.md`; `run_substep.sh`; raw `RUN_LOGS/2026-06-26_substep_feasibility.txt`.

## 2026-06-26 — IMPLICIT CROSS-BRIDGE (locally-implicit spring): the INTEGRATOR lever the 5 force-law failures pointed at — STABLE-BUT-UNFAITHFUL, ~2× faithful-dt (NOT 10×); the cheap local form is insufficient — MEASUREMENT-ONLY, flag-gated
Built the cheapest form of the integrator lever all five force-law/noise levers converged on: make the stiff cross-bridge SPRING implicit (evaluate it at the NEW head position) while leaving the NOISE explicit/FDT. The head is a Stokes SPHERE ⇒ ISOTROPIC γ ⇒ the linearly-implicit overdamped step on the head center is the **closed-form scalar blend `c_imp=(c_exp+r·c_n)/(1+r)`, `r=myoSpring·dt·1e6/γ_head`** (the `site` term cancels; no orientation, no velocity ⇒ sidesteps the dashpot's fatal `√(2D/dt)` flaw). **Additive `-xbimplicit` on V2OneX (CPU + 5-graph GPU split, in fdInteg) + Gliding (CPU + GPU graph); new `CrossBridgeSystem.snapshotHeadCenter` (pre-integration c_n) + `implicitCorrect` (post-integrate blend, bound heads only); `MotorStore.xbImplPrev`/`xbImplParams`/`setImplicit`. The SHARED `RigidRodLangevinIntegrationSystem` is BYTE-UNCHANGED (no entity leak into the shared integrator) — implicit is a post-integration position correction. Flag-off ⇒ no kernel runs, no buffer joins any graph ⇒ byte-identical; `BoA-v1ref` untouched. Bound-head TRANSLATION only — torque/F9/F10/catch-formula/binding-search untouched.** **STAGE 0 fine-dt reference (explicit, 0.5× scene, GPU):** converged bound **~1050**, fmgMean **2.8 pN**, signed-load **p10 −2.2 / p90 2.8 / fracNeg 0.45**, off-rate ~170 — the v1-matched explicit-1e-5 point (bound ~400) is itself ~2.6× below this dt→0 fixed point. **STAGE 1 (1e-5) — WIN over explicit:** implicit reproduces the fine-dt reference MARKEDLY better — bound **727 vs 400** (toward 1050), fmgMean **3.50 vs 4.68** (toward 2.8), signed-load **p10 −2.73 vs −4.13** (fine −2.2 — the negative TAIL nearly recovered), off-rate **259 vs 427** (fine ~170). It HALVES the 1e-5 dt-error and recovers the distribution SHAPE — the predicted mechanism (kill the head overshoot ⇒ narrow the spurious negative-load excursions the catch `e^{−F·xCatch}` detonates on; AR(1) stretch variance `σ²/(r(2+r))` vs explicit `σ²/(r(2−r))`, 0.58× colder). Gliding (CPU v1box) confirms: avgBound 11.7 vs 7.1 vs ~20 fine-dt. **STAGE 2 (≥2e-5) — STABLE-BUT-UNFAITHFUL (the crux):** stable at 1e-4 by construction (`1/(1+5.3)=0.16`, bounded, fmgMax 37 vs explicit-cap-off runaway 84–103), and the overshoot IS suppressed (fracOverCap 0.048 vs explicit 0.30 @2e-5) — BUT binding still COLLAPSES: bound **42/23/20** at 2e-5/5e-5/1e-4 vs converged 1050 (only ~2× explicit, both ≪ converged), signed-load tail blows out (p10 → −7…−10). **Stability ≠ accuracy.** **Largest faithful dt (bracket, within ~3–5% of 1050): implicit ≈5e-6 (bound 1024=97%) vs explicit ≈2–3e-6 (5e-6→915=87%) — a ~2× win, NOT the order-of-magnitude hoped for.** **WHY the cheap form caps out:** the catch reads a load inflated by THREE dt-error sources — explicit **SITE** motion (operator-split), start-of-step **STALENESS**, **THERMAL** under-resolution — of which head-only implicit fixes only the head's share (residual fmgMean 7 pN @2e-5, 2.5× converged, despite the implicit head). **The split error is REAL + scene-dependent — proven by the gliding↔contractile contrast** (gliding's fast-GLIDING site ⇒ implicit collapses at 2e-5 *like explicit*, recovers only 58% @1e-5; contractile's slow dense sites ⇒ 69% @1e-5) — NOT by a reduced-pair A/B (the reduced-pair, needing the seg-gather, addresses only pair compliance not the collective site motion/staleness ⇒ flagged, NOT built). **CPU≡GPU:** implicit-ON deterministic (`-brownoff`) divergence 6.2e-3 µm = implicit-OFF 6.6e-3 (adds NO new disagreement; residual is the pre-existing chaotic float32 decorrelation — aggregate-within-SEM). **Operating-point shift caveat:** implicit@1e-5 (727) ≠ explicit@1e-5 (400) ⇒ adopting implicit re-baselines the v1 calibration (tuned at explicit 1e-5). **VERDICT — the integrator is the right place but the CHEAP local form isn't enough:** the headroom lives in the **fully-coupled implicit solve** (head+site+chain together — Cytosim's stiff-bond-as-constraint, re-derived bottom-up) and/or **sub-stepping the cross-bridge+release inner loop**; the next attempt starts there, not from another local closed form. **RESEARCH_THESIS §9 + §10e added** (the 6th attempt, first INSIDE the integrator; SHARPENS §10b — we reached Cytosim's *specific* coupled-implicit design by exhausting the cheaper rung too). Report: `IMPLICIT_CROSSBRIDGE_FINDINGS.md`; `run_xbimplicit.sh` / `run_xbimplicit_gliding.sh`; raw `RUN_LOGS/2026-06-26_xbimplicit_{contractile,gliding}.txt`.

## 2026-06-26 — CROSS-BRIDGE DASHPOT (Kelvin-Voigt): the velocity-discriminating lever DETUNES gliding & does NOT extend the dt ceiling — fails for a NEW reason (the thermal random-walk velocity), not magnitude overlap — MEASUREMENT-ONLY, flag-gated
Tested the one lever NOT killed a priori by the magnitude-overlap argument that sank the four force-law levers (softer-spring/release-avg/thermal-corr/saturation): a PARALLEL dashpot on F8 (`F8 = k·stretch + γ_xb·d(stretch)/dt`), which discriminates on stretch VELOCITY (history-aware) and adds drag to the stretch mode only ⇒ `r = k·dt/(γ_head+γ_xb)` drops without softening the spring or slowing the free head's search. **Additive `-xbdash <γ_mult>` (+ `-xbdashmech`) on Gliding (GPU+CPU) + V2OneX (CPU only); new `CrossBridgeSystem.dashpotForces` kernel after bondForces (per-bond stored previous bond vector `MotorStore.xbPrevStretch`/`xbDashInit`, the forceDotAvg/avgInit reset-on-unbind pattern); γ_xb=γ_mult·γ_head read per-head in SI ⇒ unit-consistent; Hookean (γ_mult=0) ⇒ kernel not wired ⇒ production byte-unchanged; F8 translational ONLY (binding search/catch formula/F9-F10/12 pN cap untouched); `BoA-v1ref` untouched.** `-xbdashmech` = dashpot mechanical force only (catch reads spring load), isolating overshoot-suppression from the catch-detonation artifact. **STAGE 1 (γ_xb sweep @ dt=1e-5, gliding 4×1, n=2): DETUNING, NO benign range** — Hookean avgB 6.6/netX −3.5; γ_xb=0.1 already degrades 3–8× (avgB→~1); γ_xb≥0.5 collapses binding (avgB <0.3, netX flips +) ; γ_xb=4 fully unbinds (avgB 0). **The collapse is MECHANICAL, not catch-detonation:** `-xbdashmech` is only marginally less bad (avgB ~0.4) & still collapses, and the cap is OFF in gliding. **STAGE 2 (dt extension 2e-5/5e-5/1e-4, Hookean vs r_eff<1 γ_xb): NO extension** — no γ_xb at any dt recovers binding (all avgB ≤0.5, mostly ~0); the dashpot makes the already-fragile coarse-dt gliding worse; the failure mode is binding COLLAPSE (an unbound head exerts no force ⇒ no NaN to stabilize — every run finite). **Secondary (V2OneX contractile, k=1 pN/nm, CPU): the dashpot INFLATES the signed-load tail** — Hookean bound 383/fmgMean 4.2/p10 −3.5 → γ_xb=1 bound **collapses to 4**/fmgMean **10.6**/p10 **−11.4**/fracOverCap 0.50/capHits ~10000 (opposite of bounding the tail). **THE NEW FAILURE REASON (the value of this result):** the bound head is a BROWNIAN coordinate whose explicit finite-difference velocity `(b_n−b_{n-1})/dt` is dominated by the THERMAL random-walk velocity `√(2D/dt)` (which DIVERGES as dt→0), not the deterministic overshoot. So the explicit dashpot exerts a spurious anti-thermal force `F_th ≈ γ_xb·√(2·kT·γ_head/dt)` ≈ γ_xb·3.9 pN @1e-5 (as large as the whole cross-bridge signal) that cools the bound mode & collapses binding. At fine dt there's no overshoot to suppress (only harm); at coarse dt the γ_xb needed to suppress the (now-real) overshoot scales up exactly enough to keep `F_th≈10 pN` (cancellation ⇒ no operating point at any dt). **An explicit dashpot is the wrong tool for a thermally-fluctuating coordinate** ⇒ the flagged follow-on (NOT built) is a SEMI-IMPLICIT dashpot (an INTEGRATOR change — true drag on the head's equation of motion, FDT-consistent — not an explicit force from a noisy finite-difference), the same implicit/sub-step direction all five levers point to. **CPU≡GPU:** aggregate-within-SEM (γ_xb=1 @1e-5: GPU avgB 0.098/CPU 0.078, both collapse; kernel lowers identically on PTX, no RNG/trig). **Regression:** no-flag gliding reproduces the committed baseline (avgB 6.6); xbridge (gather==brute exact, CPU≡GPU bit-identical) + stroke (5 gates incl. CPU≡GPU) PASS with the new MotorStore arrays present. **RESEARCH_THESIS.md §10 added:** the dt-ceiling characterization as a methods contribution (FIVE levers mapped, all fail — four magnitude-based + the dashpot velocity-based); the Cytosim framing (this bottom-up re-derives why the field went implicit/constraint; the prescribed-detachment Hand decouples detachment from integration but structurally CANNOT show the emergent stiffness→load→detachment coupling the resolved motor has — the stiffness-sensitivity result is the demo; CAVEAT: mechanism of the difference, not yet experimental superiority — calibration-gated); cross-bridge-LOCAL vs global-viscosity (global is a near-wash). Report: `CROSSBRIDGE_DASHPOT_FINDINGS.md`; `run_xbdash.sh`; raw `RUN_LOGS/2026-06-26_xbdash_{stage1,stage2,followup}.txt`.

## 2026-06-26 — SATURATED CROSS-BRIDGE DIAGNOSTIC: no static F8 saturation recovers the faithfully-integrated 2 pN/nm distribution — the overshoot & the real force OVERLAP in magnitude ⇒ the valuable INTEGRATION-not-force-law NULL — MEASUREMENT-ONLY, flag-gated
Used the 2 pN/nm @ 1e-5 catch-explosion COLLAPSE as a clean bench (by `r=k·dt/γ` it IS the k=1.0@2e-5 overshoot, studied at the fast, well-behaved 1e-5): does a saturating F8 let 2 pN/nm reproduce its OWN faithfully-integrated self — the SIGNED-LOAD DISTRIBUTION, not just the bound count? An **integration-fidelity** test (NOT calibration: v1's gliding is an unpublished internal ref, not experimental — softened the `CROSSBRIDGE_STIFFNESS_SWEEP` "experiment-calibrated" overclaim to "v1-reference" as part of this). **Additive `-xbsat <mode> <Fmax_pN> <onset_pN>` on V2OneXHarness + GlidingHarness, gated by `xbParams` SIZE (size-6 ⇒ satMode=0 ⇒ plain Hookean, BYTE-IDENTICAL; size-9 only when set) ⇒ all 16 other harnesses + the no-flag path byte-unchanged; F8 translational ONLY — binding search, catch-slip FORMULA, F9/F10, 12 pN cap untouched; `BoA-v1ref` untouched; v2-GPU device-resident, 0.5× dt-study scene, 84 steps/s.** New `CrossBridgeSystem.bondForces` saturation block: caps |F8| above onset (direction unchanged ⇒ F, both torques, forceDotFil rescale consistently); 4 modes = sharpness×symmetry {1 sym-tanh, 2 sym-hardclip, 3 asym(compression-only)-tanh, 4 asym-hardclip} — tanh via `Math.exp` (lowers on PTX like the catch `e^{−F·xCatch}`); CPU+GPU both exercised, no NaN. **STAGE 1 — the FAITHFUL (fine-dt) 2 pN/nm REFERENCE** (k=2, plain Hookean, dt 2e-6 & 1e-6 ≡ k=1@4e-6 & 2e-6 plateau, n=2, simT 0.06; both dt agree): bound **~396**, off-rate (catch-slip) **~340/s**, **fmgMean 4.33 / fmgMax 11.1 pN**, signed-load **p10 −3.31 / fracNeg 0.45 / meanNeg −2.15** — a TIGHT, SMOOTH distribution with a real tail (max/mean ≈ 2.6). A genuinely stiffer/higher-release/lower-bound motor than faithful k=1 (B*≈1050, off~160) — the test is recovering THIS self. (vs the collapse: bound ~10, off ~39000, fmgMean 13, fmgMax 26, p10 −12.) **STAGE 2 — saturation sweep @ 2 pN/nm dt=1e-5 (mode×Fmax{4,5,6,8}×onset{0,3}, 25 runs):** **(1) compression-only asymmetry (modes 3,4) does NOT even recover binding** (bound 25–44, fmgMax 18–24, fracOverCap 0.12–0.31) — saturating only forceDotFil<0 leaves the TENSILE overshoot to over-run the head; the catch still detonates ⇒ the cap must bound the SYMMETRIC magnitude (= the head displacement / the overshoot itself); the "buckle in compression" intuition is ineffective. **(2) symmetric saturation (modes 1,2) recovers binding + directed glide along a one-parameter Fmax trade-off the reference is NOT on:** Fmax≈4–5 matches fmgMean(3.7–4.9) & the tail(p10 −3.0…−3.8) but **OVER-binds 1.5–2×** (bound 600–800); Fmax≈6 matches the COUNT (bound 428) but distribution wrong (fmgMean +32% =5.7, p10 too deep −4.8, off +32%) — the **count-match trap** realized. **(3) decisive structural signature: every recovering config PINS `fmgMax = Fmax` exactly** (4/5/6/8 — the whole bound population piles at the cap because the overshoot still drives every head there) — the faithful SMOOTH tail (mean 4.3, max 11, no pile-up) is **structurally unreachable** by a static cap. **Secondary gliding confirm (4×1, n=2):** no-sat avgB ~0.2/netX **+0.5** (no glide); CLIP6 avgB ~10/netX **−3.1** (glide RESTORED); TANH5 avgB ~19/netX **−2.7** (restored but over-binds) — saturation un-collapses the motor (a stability band-aid) but doesn't reproduce it faithfully. **VERDICT — the valuable INTEGRATION-not-force-law NULL:** the overshoot (~6–26 pN) and the motor's REAL force tail (to ~11 pN) **OVERLAP in the 6–11 pN band** — a cap above 11 (keep the real tail) is too high to tame the overshoot (collapse fmgMean 13); a cap below ~6 (tame the overshoot) destroys the real tail & over-binds. **No instantaneous force-magnitude law separates them** ⇒ the fix must be in the INTEGRATION (sub-step / implicit cross-bridge), established CHEAPLY on the 2 pN/nm bench instead of at 1e-4. The **FOURTH independent force-law lever to fail** (with softer-spring detune, release-force averaging, thermal correlation) — all converge: the cross-bridge force magnitude is load-bearing & entangled with the overshoot; the headroom needs a better cross-bridge integrator, not a reshaped force law. Follow-on (NOT run): whether the recovering sat lets 1 pN/nm reach 1e-4 (expected to fail identically). All runs stable/finite/conserving. Report: `SATURATED_CROSSBRIDGE_DIAGNOSTIC_FINDINGS.md`; `run_xbsat.sh`; raw `RUN_LOGS/2026-06-26_xbsat_stage{1,2}_*.txt`, `_gliding_confirm.txt`.

## 2026-06-26 — CROSS-BRIDGE STIFFNESS SWEEP: a softer (still measured-valid) myoSpring is NOT behaviorally indistinguishable from 1 pN/nm — it DETUNES the motor ⇒ keep 1 pN/nm, the PAIRS-saturation build is the dt lever — MEASUREMENT-ONLY, flag-gated
Tested the gating question before any PAIRS build: is 0.5 pN/nm (low end of the measured 0.5–2 range) behaviorally indistinguishable from the default 1 pN/nm? If yes, the softer Hookean buys most of a stable 1e-4 for free (dt_threshold ∝ 1/myoSpring). **Additive `-myospring <pN/nm>` on GlidingHarness + V2OneXHarness (default-off ⇒ production byte-unchanged); the F8 force LAW, binding search, catch-slip FORMULA, 12 pN cap, dt=1e-5 all UNCHANGED; `BoA-v1ref` untouched; v2-GPU device-resident both scenes (82.5 steps/s @ the 0.5× scene).** Sweep myoSpring ∈ {0.5,1,2} pN/nm on two scenes. **VERDICT — NOT a free win; 0.5 is DECISIVELY distinguishable from 1 in BOTH scenes (far outside seed scatter):** GLIDING (4×1, n=4) NET velocity **2.45±0.11 / 3.80±0.17 / 0.53 µm/s** + avgBound **24.4 / 6.9 / 0.25** at 0.5/1.0/2.0 — velocity is **non-monotonic, PEAKS at the default 1.0** (0.5 over-binds 3.5× and glides −36%; 2.0 collapses binding, netX flips positive = no directed glide); only k=1.0 reproduces v1's calibrated avgBound ≈7.5 + the committed baseline (net 4.02±0.15/avgB 7.20 → the **default-unchanged check**). CONTRACTILE (0.5× dt-study, n=3, simT 0.10): bound **1116 / 418 / 11**, off-rate **141 / 422 / ~39000 /s**, fmgMean **2.4 / 4.5 / 12.1 pN**, p10 **−1.8 / −3.8 / −7 pN** — the whole signed-load distribution rigidly scales with stiffness; RgXY flat ~1.69 (non-discriminating in this sparse scene, per the dt study). **MECHANISTIC CORE — stiffness & dt enter the overshoot ONLY via r = k·dt/γ:** the sweep traces the SAME curve as the dt study at matched k·dt (k=0.5@1e-5 ≡ k=1.0@~5e-6 converged regime 1116/141; k=1.0@1e-5 = the 1e-5 reference 418/422; k=2.0@1e-5 ≡ k=1.0@2e-5 collapse ~11). So softening myoSpring 2× IS numerically halving dt for the cross-bridge — but it reaches a converged-LOOKING bound population by **halving the force the motor exerts**, and that force is what the experiment-calibrated gliding depends on (the contractile "0.5 looks more converged than 1.0@1e-5" is the k·dt coincidence, NOT a calibration — gliding avgBound 24 vs v1's 7.5 proves 0.5 is mis-calibrated; the motor's kOff/catch-slip/reach were jointly tuned WITH k=1.0@1e-5). **dt_threshold = 2γ_head/myoSpring** (γ_head=1.885e-8): 7.54e-5 / 3.77e-5 / 1.885e-5 — **correcting the task premise:** r at 0.5pN/nm,1e-4 = **2.65 (UNSTABLE)**, not the task's "1.3"; the softer Hookean is deterministically stable only to ~7.6e-5, and the OPERATIVE limit is lower still (the catch-explosion binding collapse moves only to ~2–4e-5 by k·dt scaling, NOT 1e-4). **⇒ the cheap "adopt 0.5, run Hookean at 1e-4" path is REJECTED; the cross-bridge stiffness genuinely matters (behavior pins myoSpring ≈ 1 pN/nm sharply — the upper measured 2 pN/nm is even unreachable at the validated 1e-5); the PAIRS-saturation element (preserve 1-pN/nm force up to threshold, saturate above) is the dt lever** — a THIRD independent confirmation (with RELEASE_FORCE_INPUT + BOUND_THERMAL_CORRELATION) that the cross-bridge force magnitude is load-bearing and can't be traded for stability. All runs stable/finite/conserving (the k=2.0 collapse is the expected catch-explosion, not a crash). Report: `CROSSBRIDGE_STIFFNESS_SWEEP_FINDINGS.md`; `run_xbstiff.sh`; raw `RUN_LOGS/2026-06-26_xbstiff_{gliding,contractile}.txt`.

## 2026-06-25 — BOUND THERMAL CORRELATION: a constraint-aware thermostat (correlate the bound head's thermal kick to its filament's) does NOT tame the coarse-dt cross-bridge tail — the FORK opens (the overshoot is DETERMINISTIC, not thermal) — MEASUREMENT + flag-gated
Tested whether correlating a bound head's Brownian kick to its filament contact's (removing the spurious *relative*-coordinate thermal noise a stiff bond should constrain away — the noise-side analog of constraint-not-stiff-potential) tames the cross-bridge signed-load distribution toward 1e-4. **Additive, flag-gated (`-bondcorr <α>` + the new `SLHIST` signed-load-distribution readout, default-off ⇒ production byte-unchanged); changes ONLY the bound-head thermal-noise correlation** — the binding search, the F8 spring law, the catch-slip FORMULA untouched; `BoA-v1ref` untouched; v2-GPU device-resident, same 0.5× scene. New `BondThermalCorrelationSystem.correlateBoundHead`: variance-preserving `η_head = α·η_fil + √(1−α²)·ξ_head` (correlate the UNIT draws then apply each body's own FDT amplitude ⇒ α tunes correlation, NOT temperature), correlate to the segment's RAW thermal draw (not its net force — care-point #1), isotropic/translational, head↔filament-contact only. **Self-write, NOT a gather** (each head reads one segment; recompute both bodies' raw draws bit-for-bit from BrownianForceSystem's wang-hash/Box-Muller, blend, overwrite `randForce[head]`; race-free, no atomics, both runners). **α=0 byte-identity GATE PASSED** (`-bondcorr 0` ≡ no-flag, bit-identical DTROW+SLHIST on CPU AND GPU). **STAGE 1 reference (instantaneous, fine-dt):** the converged 2e-6 signed-load distribution is TIGHT (bound 1093, off-rate 175/s, p10 −2.2 pN, **0 % below −8 pN**, release-weighted load peaked at −1) vs the 1e-5 overshoot's DEEP tail (bound 400, off-rate 427, p10 −4.2, **18–65 % in the <−8 pN bin**) — the target shape. **STAGE 2 (α-sweep @ dt=1e-4, cap off): TOTAL COLLAPSE at every α** (bound 5–10 vs 1093, loads ±65…±380 pN) — the predicted **deterministic spring-stability ceiling** `k_bond·dt/γ_head = 5.3 ≫ 2` (unconditional explicit divergence; noise can't fix a deterministic blow-up; cap-on bounds NaN to ~57 pN but the count still collapses). **CEILING (2e-5…5e-5):** already collapsed (the known catch-explosion ceiling, bound 12–21 / off-rate ~6000–7000) and α gives NO rescue (2e-5: α=0→21/6902, α=0.51→21/7031 with a WORSE tail). **WORK15 — the decisive test @ the WORKING dt=1e-5:** α **MONOTONICALLY WORSENS** it (off-rate 432→493→528→563→635→672 for α 0→0.9; bound 440→273) — moving AWAY from the 175/1093 reference. The marginal width (meanF ~0.2, p90 ~4) is PRESERVED at every α ⇒ the variance-preserving formulation works as designed (temperature unchanged, relative variance attenuated per `Var(Δr)=2dt[D_h+D_f−2α√(D_hD_f)]`) — **but attenuating the thermal relative variance does NOT attenuate the deterministic negative excursions.** **VERDICT — the FORK opens (the task's anticipated outcome), decisively:** no α reproduces the tail at any dt and α actively degrades it at a working dt ⇒ the negative-load excursions the catch `e^{−F·xCatch}` rectifies are dominated by the **DETERMINISTIC explicit stiff-spring overshoot** (the one-step-stale relative position over a coarse step; plus uncorrelated rotational + segment-non-thermal channels), NOT the independent thermal relative noise. **(b) Stiffness anchor** `α_pred=k_bond·dt/(k_bond·dt+γ_head)` (0.35@1e-5 … 0.84@1e-4) is clean but its PREMISE (relative noise is the channel) is refuted — best empirical α=0; the same algebra read as STABILITY (`dt<2γ/k=3.8e-5`) IS borne out (1e-4 unconditionally collapses). **(c) dt-weakness:** best-α=0 at all dt (vacuously constant; the lever doesn't engage). **CPU≡GPU:** α=0 bit-identical per runner; bondcorr step-0 Δcoord 7.4e-5 µm / bound-set Δ=0, then chaotic release decorrelation (§8 aggregate standard, identical to the `-tauavg` path). **Generalizes to crosslinkers** (same over-counted-relative-noise; the self-write mechanism ports two-ended) — but the verdict transfers as a CAUTION: test the channel decomposition (deterministic-overshoot vs thermal-relative) first; the crosslinker spring is damping-limited (§5a) so it may be more thermal. **Multi-head-per-filament over-correlation flagged (unsolved, ring-scale).** This is the SECOND independent attack on the release wing to fail for the same root reason (force-averaging was the first) ⇒ sharpens that **sub-stepping the stiff cross-bridge is the indicated and remaining lever** (`RESEARCH_THESIS.md` §9). Report: `BOUND_THERMAL_CORRELATION_FINDINGS.md`; `run_bondcorr.sh`; raw `RUN_LOGS/2026-06-25_bondcorr.txt`, `_work15.txt`.

## 2026-06-25 — RELEASE FORCE INPUT: the dt→0 lumped catch-slip bond CONVERGES (off-rate→160/s, B*≈1050); a TIME-AVERAGED release force does NOT make it dt-robust — the fork OPENS toward sub-cycling (MEASUREMENT + flag-gated)
Characterized the dt→0 lumped release and tested feeding it a per-head **time-averaged** cross-bridge force instead of the instantaneous overshot F. **Additive, flag-gated (`-tauavg <s>` + the `RELROW` readout, default-off ⇒ production byte-unchanged); changes ONLY the F the catch-slip rate reads** — the spring law, the catch-slip FORMULA, the binding search are untouched; `BoA-v1ref` untouched; v2-GPU device-resident, same 0.5× scene. New EMA folded into a `catchSlipReleaseAvg` variant (EMA updated in-kernel from the last-step force the fdBind release already holds ⇒ no cross-graph buffer plumbing; an earlier separate-kernel design tripped a TornadoVM device-buffer NPE). **Code-read first:** the catch-slip reads the **SIGNED** along-fil load `forceDotFil` (not the |F8| magnitude), `P=rate·dt`; because catch is `e^{−F·xCatch}` it **EXPLODES for F<0** ⇒ the release is driven by the **negative excursions** of the load, which the explicit stiff-spring overshoot inflates ∝ dt. **STAGE 1 (fine-dt {1e-5,5e-6,2e-6,1e-6}, instantaneous): the release CONVERGES** — per-bound-motor catch-slip off-rate 428→204→177→**165**/s with decelerating increments ⇒ finite limit **≈160/s by dt≈2e-6** (same dt as the B*≈1050 binding plateau). 1e-5's 428/s is **2.6× the converged ref** (the overshoot inflation). Catch-dominated (catchFrac 0.95) but **NEGATIVE-load-weighted** (fAtRelease −4.2→−1.8 pN) — set by the WIDTH of the signed-load distribution, not the +2.9 pN |F8| "floor". GATE PASSED. **STAGE 2 (τ_avg sweep @1e-5): a PLATEAU that OVER-CORRECTS.** Averaging (any τ_avg 1e-4→1e-2 = 10→1000 steps, cap off) flattens off-rate at **~100/s and bound ~1300** — flat over 2 decades but the WRONG value (vs ref 160 / 1050): it collapses `⟨rate(F)⟩→rate(⟨F⟩)≈rate(mean≈+0.2 pN)≈unloaded` (Jensen — destroys the load/fluctuation rectification = the catch behavior). **Short-τ probe:** the off-rate matches 160 ONLY at a **knife-edge ~2-step window** (440→155→130→118→100 across 1→2→3→5→≥10 steps, NOT a plateau), and EVEN there the force distribution is pathological (|F8| mean 5.6 vs 3.0, **fmgMax 96–219 pN** vs 11, escapes) — matched count, WRONG distribution. **Cap-on probe:** bound recovers to ~1010≈B* but via the **instantaneous-force 12 pN cap** (cap channel ~120/s) while catch-slip is over-suppressed to 94/s — the cap, not the averaging, does the work (and averaged+cap-off is **stability-degrading**, |F8|→219 pN). **Collapse probe (dt=2e-5):** averaging rescues binding 15× (21→~350) from the off-rate-6900/s catastrophe but only to a mediocre over-stretched state (≪B*). **THE FORK OPENS — averaging alone is INSUFFICIENT:** no window both washes the overshoot AND reproduces fine-dt AND preserves catch (the overshoot and the genuine load fluctuation share the ≤2-step timescale ⇒ inseparable by per-head averaging). **Numerical framing wins decisively:** fine-dt instantaneous is ground truth, averaging produces either a mean-collapsed rate or a pathological distribution ⇒ the **PHYSICAL "averaged-strain is the right input" framing is NOT supported**; instantaneous strain is correct, the defect is the explicit integrator computing it wrong. ⇒ names its follow-ons (NOT built): **(1) sub-cycle the bound-head inner loop** (attacks the overshoot at source, keeps instantaneous F ⇒ preserves catch — the indicated lever) and/or **(2) a genuine physical strain-integration bond** (needs lever 1 underneath). **CPU≡GPU:** the EMA is float32-last-bit identical (brownoff step-0 Δcoord 7.4e-5, bound-set Δ=0) and the subsequent divergence is **bit-for-bit identical to the instantaneous path** ⇒ no new divergence (the chaotic force-threshold-release decorrelation, aggregate-within-SEM standard). Coupling caveat: absolute off-rate/B* carry an "at current binding calibration" asterisk (reaction-limited kOn would re-weight). **RESEARCH_THESIS.md §4 CORRECTED:** unbinding is a deliberately LUMPED calibrated bond (not claimed to emerge — below the model's resolution); the emergence claim lives in the MECHANICS/force generation; the unbinding goal is a dt-ROBUST lumped bond, not emergence. Report: `RELEASE_FORCE_INPUT_FINDINGS.md`; `run_relforce.sh`; raw `RUN_LOGS/2026-06-25_relforce_stage{1,2}.txt`.

## 2026-06-25 — BINDING-SEARCH REFORMULATION: a physical per-sim-time encounter rate (flag-gated, built + validated) — and a PREMISE-CORRECTING finding (the dt rise is the FORCE wing, not the search)
Implemented the reformulation deferred by `MYOSIN_BINDING_RATE_FORMULATION.md` §4 (serves `RESEARCH_THESIS.md` §5/§9: binding must be a physical encounter process, not a fitted geometric knob). **Additive, flag-gated, geometric `bindNearest` stays the default ⇒ production byte-unchanged; `BoA-v1ref` untouched.** New `BindingDetectionSystem.bindRate` (**P=1−exp(−kOn·Δl_eff·dt)**, Δl_eff the **path-average chord** through a TIGHT capture sphere = myoColTol over the head's swept segment [headPrev→head] — **formulation B swept**; **A instantaneous** = headPrev≡head; binds at the perpendicular foot ⇒ low-stretch; "BRAT"-salt wang-hash, no atomics) + `gridReachableWide` (widened candidate gather, tight chord physics) + `snapshotHead`; `MotorStore` kinParams 14→16 ([14]=kOn the calibratable handle, [15]=candReach) + `setSearchParams`; `V2OneXHarness` `-ratesearch`/`-pointsearch`/`-kon`/`-candmargin` + a **bindFlux** DTROW column (turnover=catch-slip+cap releases/simT ≈ binding flux). Wired into BOTH the CPU runner and the 5-graph GPU device-resident path; lowers clean on PTX, stable, no NaN. **SWEEP (0.5× scene, matched simT 0.10, dt {1e-5,2e-6,1e-6}, v2-GPU; raw `RUN_LOGS/2026-06-25_bindsearch_sweep.txt`).** **Distribution (decisive gate) — PASS at fine dt:** rate reproduces the tight fine-dt reference (mean ~2.9–3.0 / p90 ~4.6 / ~1–2% >6 pN @ ≤2e-6) at both kOn; the fat 1e-5 distribution (mean ~4.5 / ~24% >6 pN) is the **cross-bridge FORCE wing**, present IDENTICALLY in the geometric (4.54 / 22%) — the search keeps the bind geometry tight, so the **hack's pathology is ABSENT** (no fat tail beyond the force wing; cap-churn ~0.07/step not 6.5). **THE PREMISE-CORRECTING RESULT:** the capture **FLUX is already ≈dt-invariant** for BOTH geometric and rate (productive flux flat ±~13–26%, NOT a 2.6× rise); the **bound-COUNT's ~2.4× rise (447→1069 @1e-5→2e-6, mirrored by rate 424→1032) is the bound LIFETIME** (count/flux: 2.2→6.9 ms) growing as the F8 overshoot relaxes (fmgMean 4.5→2.9, cap-churn 3843→705) — the **upper (force) wing**, untouchable by a search reformulation. So the task's headline hypothesis (a rate search makes binding-per-sim-time dt-flat) is **REFUTED + re-diagnosed**, correcting `MYOSIN_BINDING…` §2/§4's "pure search" attribution (that held only on the narrow 5e-6→2e-6 flat-tension segment) and confirming its §3 (the implicit/sub-step cross-bridge is the dt lever, not the search). **B*≈1050 reproduced at FINE dt (rate-k1e8 @2e-6=1032), NOT at 1e-5 (saturates ~430≈geometric)** — B* is a converged reference, the 1e-5 count is lifetime-limited; calibration scan kOn 5e5/5e6/2e7/8e7→114/322/396/429 bound @1e-5 (saturating, never 1050). **A vs B: fly-bys minor** (A=B @2e-6; B=A+7% @1e-5; ~0.6× displacement/radius) ⇒ per-step capture is NOT badly under-resolving (corroborates the flux finding). **CPU≡GPU = aggregate-within-SEM** (Brownian-on 300 vs 299 bound; Brownian-off 12=12, set Δ=2) — NOT bit-identical, because `Math.exp` in P_bind flips near-threshold decisions at float32 last-bit, exactly like the existing stochastic catch-slip release (the chaotic-many-body standard, `CLAUDE.md`). Default kOn=1e8 (diffusion-limited) is a faithful **drop-in for the geometric over the tested dt range in the SATURATED regime** — but it **de-saturates at the fine end** (P=1−exp(−kOn·Δl·dt)∝dt: ≈8/1.6/0.8 → 424≈447, 1032 vs 1069, 980 vs 1048) and is NOT a geometric clone at arbitrary dt (flagged). **VERDICT: ship the physical, calibratable, tight-geometry rate search (thesis §5/§9 win — binding is no longer a prescribed knob); it makes binding physical + keeps the distribution tight, but the dt ceiling is the cross-bridge FORCE wing — a correctness/thesis win, NOT a speed lever (per Part 2). The implicit/sub-step cross-bridge remains the complementary, separate piece.** Report: `BINDING_SEARCH_REFORMULATION_FINDINGS.md`; `run_bindsearch.sh`.

## 2026-06-25 — BINDING-SEARCH CONVERGENCE: the geometric search PLATEAUS (B*≈1050, NOT ill-posed); a reach-multiplier hack recovers COUNT but NOT distribution — MEASUREMENT-ONLY
Decided "under-resolved-but-fine vs ill-posed" by extending the convergence plot below 2e-6 (matched simT 0.10 s, dt ∈ {1e-5,2e-6,1e-6,5e-7}, same 0.5× scene, v2-GPU; `run_dtconv.sh below2`; raw `RUN_LOGS/2026-06-25_dt_below2.txt`) + a new host-side **DTHIST** cross-bridge-stretch-distribution instrument (no kernel change, `-dtconv`-gated). **Part 1 — the search CONVERGES.** Bound @simT 0.10: 1e-5→400, 5e-6→918, 2e-6→**1093**, 1e-6→**1050**, 5e-7→**970** — rises then **flattens by ~2e-6** (2e-6/1e-6/5e-7 flat within ±3% chaotic scatter; monotone rise stopped). ⇒ a finite continuum limit **B*≈1050**; the per-step geometric capture is **correct-but-UNDER-RESOLVED at 1e-5** (under-counts ~2.6×), **NOT ill-posed**. (The earlier "still rising at 2e-6" was at simT 0.20 = slower-saturating trajectories climbing in TIME; the dt-curve at fixed simT 0.10 plateaus.) **Wing separation holds to the bottom:** fmgMean falls to a **~2.8 pN floor and stays** (3.0→2.9→2.8), fracOverCap=0 at every dt≤2e-6 ⇒ the below-1e-5 change is **pure search**, decoupled from the force wing. Fine-dt distribution is **tight** (peaked 2–3 pN, p90≈4.5, ~1% of bonds >6 pN). **Part 2 — the hack: matched COUNT, WRONG DISTRIBUTION.** At dt=1e-5, widening `-reach` to **≈2.8× (0.017 µm)** recovers the count (0.020→1217), but every hacked distribution sits at **mean ~4.7 / p90 ~7.4 / ~23% bonds >6 pN / fmgMax to 19.5 pN / cap-churn 6.5/step** vs fine-dt **2.9 / 4.6 / ~1% / 10.7 / ~0.02** — an over-stretched, fat-tailed, high-churn population (far segments bound in one coarse step at large stretch). Two stacked distortions: (i) the 1e-5 force-wing overshoot already inflates the bulk at the DEFAULT reach; (ii) the widened reach fattens the tail + explodes cap-churn. **Verdict: the search is convergent so a calibration is conceptually possible, but a reach multiplier is the WRONG one (cosmetic count match) ⇒ the principled fix is a swept-volume / reaction-rate `1−exp(−k_on·dt)` capture (deferred to the reformulation task); AND the cross-bridge integration must ALSO be fixed (implicit/sub-step) for the tension distribution to match — both levers, consistent with Part 3.** All runs stable/finite. Report appended: `MYOSIN_BINDING_RATE_FORMULATION.md` §Part 4. `BoA-v1ref` byte-clean; DTHIST + `-reach` are measurement-only/flag-gated; production byte-unchanged.

## 2026-06-25 — MYOSIN BINDING dt-FORMULATION: the two dt-collapse wings are INDEPENDENT (search vs cross-bridge force); a search reformulation does NOT open headroom above 1e-5 — MEASUREMENT + CODE-READ ONLY
Investigated jba's question (would a dt-insensitive binding reformulation also behave above 1e-5?) by reading v2's bind/cross-bridge/release kernels + sweeping dt BELOW 1e-5 (matched simT 0.2 s, dt ∈ {2e-5,1e-5,5e-6,2e-6}, same 0.5× scene, v2-GPU; `-dtconv` instrument, `run_dtconv.sh below`; raw `RUN_LOGS/2026-06-25_dt_below.txt`). **Part 1 (code-read) — three-stage map:** (1) SEARCH (`BindingDetectionSystem.bindNearest`+`reachTestDistSq`) is a per-step GEOMETRIC test, **no dt, deterministic-on-contact — NOT a `1−exp(−k·dt)` rate** ⇒ dt-fragile (lower wing, confirmed); (2) CROSS-BRIDGE FORCE (`CrossBridgeSystem:96` `fmag=myoSpring·dist`, myoSpring≈1e-9 N/µm) is the **ONLY raw explicit Hookean spring** in the model, read at the explicitly-integrated position ⇒ overshoots ∝ dt (upper wing); (3) RELEASE (`NucleotideCycleSystem.catchSlipRelease:116`) `rate=kOff·(αC·e^{−F·xC/kT}+αS·e^{+F·xS/kT})`, `P=rate·dt` — the rate-LAW is a PROPER (dt-robust) per-sim-time rate; only the FORCE F feeding it is dt-fragile (refines the original "rate coded dt-fragile" framing). **Part 2 (below-1e-5 curve, bound @simT 0.20):** 2e-5→**22**, 1e-5→**~450**, 5e-6→**1129↑**, 2e-6→**1244↑** — binding **RISES** monotonically as dt→0 (recalled "starvation" REFUTED; the per-step test UNDER-counts encounters at coarse dt, converges from below), at **LOW FLAT cross-bridge tension** (fmgMean 9.1→4.4→3.3→3.0 pN, fracOverCap→0). **1e-5 is NOT converged** — un-plateaued through 2e-6 (≳2.9× higher, dt→0 limit unreached) and sits at the **ONSET of the force wing** (fmg 4.4 already > the 3.0 pN floor). **Wings INDEPENDENT — decisive cut:** 5e-6→2e-6 tension flat at floor yet binding still climbs (1129→1244) ⇒ below-1e-5 change is the SEARCH not the force; release rate at 3.0 pN (25.6/s) is even HIGHER than at 1e-5's 4.4 pN catch-min (18.6/s) ⇒ the rise is purely capture-side. **Part 3 verdict:** search→rate reformulation fixes the LOWER wing (binding dt-invariant) but **does NOT open headroom above 1e-5** (hypothesis confirmed — upper wing is mechanics); headroom comes only from an **implicit/sub-stepped cross-bridge** (faithful to v1) OR a **kinetic rate-based motor** (fully dt-insensitive but a DIFFERENT MODEL CLASS than v1 — a faithfulness decision, surfaced for jba). **No next-stiffest spring** — the cross-bridge F8 is the only raw Hookean; every other coupling (chain/joints/dimer/minifil/node/crosslink/anchor) is damping-limited `fracMove/(dt·moveC)` (dt-robust). After fixing it, the next limiter is the rate-discretization (crosslink pForm saturation ≥~5e-5) ⇒ bounded headroom ~5×, AND only if the search is also reformulated (it under-resolves at every dt). Comparisons at matched dt unaffected (dt cancels in ratios). Report: `MYOSIN_BINDING_RATE_FORMULATION.md` (rewritten as the completed investigation). `BoA-v1ref` byte-clean; no physics/rate/default edits; production byte-unchanged.

## 2026-06-24 — dt-CONVERGENCE STUDY: the largest FAITHFUL (no-tuning) mechanics dt = 1e-5 (NO headroom); the cross-bridge force-OVERSHOOT sets the ceiling — MEASUREMENT-ONLY
Swept dt ∈ {1e-5,1.2e-5,1.5e-5,2e-5,5e-5,1e-4} at MATCHED sim-time (0.3 s ⇒ 30000…3000 steps), same 0.5× scene (box 5.0, 200 nodes, 500 fil), dt the ONLY variable, NO tuning, v2-GPU. New flag-gated MEASUREMENT-ONLY instruments (`-dtconv`/`-seed`/`-nocap`; production byte-unaffected). **1e-5 reference envelope tight: bound 470 ±12 (±3 %, n=3), cross-bridges at ~4.4 pN ≈ ⅓ of the 12 pN cap, fracOverCap ≈ 0.** **ACCURACY CEILING = 1e-5 with NO headroom:** bound-motor count collapses **2.5× already at 1.2e-5 (+20 %)** → 0.40× envelope, **26× at 2e-5**, monotone + far outside chaotic scatter ⇒ defensible faithful dt-speedup ≈ **1.0× (none)**. **Cause = explicit-overshoot of the stiff cross-bridge spring:** fmgMean rises ∝ dt (4.4→5.0→7.5→9.1→~15→~22 pN; fmgMax to 84 pN @1e-4), fracOverCap in lockstep (0.00→0.55). **HYPOTHESIS (12 pN cap is the gate) REFUTED by the cap-off control:** `-nocap` does NOT rescue binding at 2e-5 (11 ≈ capped 16) — it just unmasks the force (fmgMean 9.1→29.6 pN). The cap is a parallel symptom-shedder; the **catch-slip force-EXPONENTIAL release** (`rate∝e^{+F·xSlip/kT}`, P=rate·dt, F = the dt-inflated load) is the dominant collapse channel (26× ≫ the ~2× from the linear rate·dt ⇒ the force-coupled term dominates). Strategic claim CONFIRMED + strengthened: it's a FORCE-dependent release ⇒ a global drag/viscosity rescale/tuning provably can't reach it. **STABILITY ≫ ACCURACY:** every dt to 1e-4 stable (no NaN/escape/blow-up — releases shed over-force bonds, preventing runaway); stability >1e-4, accuracy ≤1e-5, ≥10× apart — "it didn't blow up" mis-reads the usable dt by 10×. Crosslink rate-discretization (pForm 0.039→0.33) present but subdominant + non-monotonic (confounded by the binding collapse), NOT the ceiling-setter. RgXY flat (~1.68) at every dt incl. reference ⇒ non-discriminating in this sparse scene. **IMPLICATION: the lever for a larger faithful dt is sub-stepping / an implicit cross-bridge integrator (attack the ∝dt overshoot directly) — a future task.** Report: `DT_CONVERGENCE_FINDINGS.md`; raw `RUN_LOGS/2026-06-24_dt_convergence.txt`; `run_dtconv.sh`. `BoA-v1ref` byte-clean; no physics/rate/default edits.

## 2026-06-24 — CROSSOVER DIAGNOSIS: the high-scale v2-GPU/v1-GPU crossover is an ARTIFACT of v1-GPU under-binding (verdict (a), ~100%), MEASUREMENT-ONLY
Re-analyzed the 4-way grid + re-measured v2 per-graph timing (uncommitted `-pergraph` instrument) + READ v1's GPU binding path (BoA-v1ref, read-only; no edits). **Cheap cut:** per-doubling step-time ratios v2-GPU 1.85/1.98/2.05 (≈ ideal 2.0 = honest ∝1/N) vs v1-GPU **1.25/1.34/1.52 (impossible for a full-work path ⇒ dropped work)**. **Thrust 1 — lead C confirmed:** v1-GPU binds 53/41/27/**19%** of v1-CPU @ 1/2/4/8× (near-FROZEN absolute bound 245→573 vs v1-CPU 459→3067); v2 control has NO divergence (v2-GPU/v2-CPU 118→162%, both track scene). **Work-normalized (bound-motors processed/wall-s = steps/s×bound): v2-GPU 4.7–6.1× AHEAD at EVERY scale — the crossover VANISHES.** Raw crossover ~4×; **work-normalized crossover: NONE in [1×,8×]**. **WHY v1-GPU under-binds:** `MyoMotor.bindTimer` is `static` (ONE global, `:73`), reset to 0 by any release (`MyoFilLink:315`), refractory gate `bindTimer<myoRebindTime(=1e-5=dt)` (`MyoMotor:455`) enforced SERIALLY on the host during the GPU bind-unpack (`GPUMotorBinding:~1840`) — the device `bindKernel` is purely geometric/first-hit (`:643-760`); rising releases/step gate out a growing fraction of candidates ⇒ widening deficit. = the documented BoA `bindTimer` static-global race (RESIDUAL_DOSSIER Part 2), a BoA concern NOT a v2 issue. **Thrust 2 — v2 per-graph scaling (re-measured, reproduces doc steps/s):** TOTAL **p=0.98 (HONESTLY LINEAR)**; dominant `fdFil` (62%@8×) p=0.98 dead-linear; only minor super-linear leans `fdXForm` (xlink formation broad-phase, p=1.11) + `fdBind` (motor reachable broad-phase, last-doubling 2.63×) — the real v2 high-density targets but NOT the crossover. **Verdict: (a) artifact, ~100%; faint (b) named but non-causal.** Report: `CROSSOVER_DIAGNOSIS_FINDINGS.md`. Instrument uncommitted (production byte-unaffected when `-pergraph` absent); BoA-v1ref/v1scratch byte-clean.

## 2026-06-24 — STANDARD 1× BENCHMARK: four-way (v1/v2 × CPU/GPU) sweep 1×/2×/4×/8×, MEASUREMENT-ONLY
**Parity gate PASSED** after 2 scene-param standardizations (NOT model edits): v2 myosin reach `REACH 0.025→0.006 µm` (v1 `Env.myoColTol`, was 4.2× over-reach ⇒ bound 1971 vs v1 ~550) + v2 crosslink on-rate `XLINK_ON_RATE 10→40` (v1 pf_1x, ⇒ pForm 0.00995→**0.0392 == v1**). All other ~30 params verified 1:1 (dt 1e-5, box ±3.5355, 64 mono/seg → 0.1755 µm segLen, crosslink reach 0.0108 µm ALREADY matched, aeta 0.1, 24 myo/node, deterministic bind, catch-slip kOff=100, 12 pN cap, treadmill OFF). Added `-reach`/`-xlonrate` CLI flags + flipped the two defaults. Post-fix **v2-CPU bound count tracks v1-CPU within ~10% at all scales** (506/915/1567/2757 vs 459/894/1611/3067) — work-matched. v1 measured via `BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=50` (warmup-excluded ms/step); v2 harness excludes warmup natively. **Steady-state steps/s 1×/2×/4×/8×:** v1-CPU 30.4/18.7/10.9/5.86 · v1-GPU 21.4/17.1/12.8/8.4 · v2-CPU 15.3/7.6/3.8/1.8 · v2-GPU **49.5/26.7/13.5/6.6**. **Crossovers:** v1 GPU>CPU at 2–4× · v2-GPU always > v2-CPU (≥3.2×) · cross-engine v2-GPU vs v1-GPU cross at ~4×. **3 named leads (flagged, NOT chased):** (A) v2-CPU single-thread runner 2.0→3.3× slower than v1's 16-thread pool, gap WIDENS with scale; (B) v2-GPU's lead over v1-GPU erodes 2.31×→0.79× across scale; (C) v1's OWN CPU≢GPU binding diverges+widens (v1-GPU binds 53%→19% of v1-CPU @ 1×→8×) — v1-internal confound that flatters v1-GPU at scale. VRAM: v2-GPU 508 MiB(1×)/1420(8×) vs v1-GPU flat ~1.6–1.8 GB (v2 far leaner). All 16 cells MEASURED (none extrapolated); all stable/finite/conserving. Report: `STANDARD_BENCHMARK_4WAY_FINDINGS.md`. Scaled v1 PFs in `/tmp/v1_fdt_diag/pf_{2,4,8}x*`.

## 2026-06-24 — V2OneX GPU chained-split DONE (device-resident, all gates green): v2-GPU 50 steps/s
Branch `v2onex-gpu-split`, **Part 2 committed.** Wired `runGpu` to a device-resident 5-graph chained split (`buildPlanSplit`/`stepSplit` + `blkBind`/`blkStruct`/`blkFil`/`blkInteg`/`blkXForm` + `buildSplitScheduler` + `hostNodeCSR`/`hostSegCSR`), a faithful PORT of FullSystemDemo's split adapted to V2OneX's clean subset (ONE grid-bound node-shell motor population, crosslinkers ON, NO turnover/nucleation/free-minifilament). **Lowered on PTX the FIRST attempt** — no Graph-resize, no CUDA 701, no executeAlloc NPE (the `V2ONEX_GPU_FINDINGS.md` partition + GridScheduler re-keying were correct as written; the only iteration was adding the CPU≡GPU `-cmp`/`-brownoff` validation harness). Partition: G0 fdBind (publish+grid+reach+bind+cycle) · G1 fdStruct (zero/brown + joints/dimer/tether/node-gather/bond) · G2 fdFil (chain + seg-gather + xlink force/2-pass, the xlink link-state UPLOADER) · G3 fdInteg (confine/integrate/derive) · G4 fdXForm (device filID + crosslinker FORMATION, **cadence-gated SINK** t%100==0). **Gates (aorus RTX 5070):** (1) lowers clean; (2) device-resident — per-step host xfer = `mot.boundSeg` ≈38 KB (CSR-host) + render pulls at report cadence, NO full-state copy (`-devicecsr` also resident, 46.3 steps/s, CSR bit-identical); (3) **CPU≡GPU** via `-cmp -brownoff` — bound-set Δ ≤ 5/9600, coord Δ a BOUNDED chaotic plateau (1.2e-2→8.7e-2 µm over 200 steps, slow Lyapunov, no NaN/divergence — the accepted many-body op-ordering decorrelation); Brownian-ON bound-count trajectories match ±5/2141 (0.2%) over 1500 steps; (4) work parity — bound heads ±3–5 every checkpoint (CPU 2141 = GPU 2141 @ 1500), links same small-N regime; (5) **v2-GPU = 50.0 steps/s** (warm-excluded) = **3.6× v2-CPU 13.7, 2.3× v1-GPU 22** (four-way: v1-CPU 29 / v1-GPU 22 / v2-CPU 13.7 / v2-GPU 50.0); (6) disclosure honored (runGpu device-resident, no silent fallback). **No per-execute creep** (no fdNuc carrier; fdXForm a throttled SINK; steps/s flat 50.2→50.0 over 1500→2000). New `-cmp`/`-brownoff`/`-devicecsr` flags; FullSystemDemo + all validated paths untouched; no shared-kernel/physics edit. Report: `V2ONEX_GPU_FINDINGS.md` (gate table filled).

Last updated: 2026-06-24

## 2026-06-24 — V2OneX: IC parity fix DONE; GPU chained-split PLANNED (paused before behavioral commit)
Branch `v2onex-gpu-split`. **Part 1 (committed):** v2 IC now places filaments by v1's `makeRandomFilament` (two random box points → in-plane axis) — z-poke 1580→0/10000, box geometry already matched (v1 `rdmPtInside`=±boxXDim/2). Re-baseline v2-CPU 1× = **13.7 steps/s** (v1-CPU 29). **Part 2 (PAUSED, no behavioral commit):** the device-resident `runGpu` port is fully SPECIFIED (`V2ONEX_GPU_FINDINGS.md`) — 5 chained graphs fdBind·fdStruct·fdFil·fdInteg + gated fdXForm SINK (V2OneX = clean subset of FullSystemDemo's split, minus turnover/nucleation/minifilament; node-shell binding == the mot2 GRID path). Held back from a blind one-shot commit because the ≈80-buffer per-graph residency bookkeeping + GridScheduler keying + CPU≡GPU bit-validation need GPU-in-the-loop iteration (the template's own executeAlloc-NPE/CUDA-701 lessons), and the bail rule forbids committing unvalidated behavioral code. `runGpu` left unchanged (still discloses the blocker — gate-6 compliant, not silent). Next GPU-attached session: §'fast path'.

Last updated: 2026-06-24

## 2026-06-24 — NEW "1x" CONTRACTILITY BENCHMARK STANDARD (declared by jba) + v1 CPU/GPU baseline
**THE 1x SCENE (find it here):** a shallow-slab contractility test — **box 7.071×7.071×0.5 µm = 25 µm³**;
**400 protein nodes**, each carrying **24 singlet myosins** (`numNodeMyos:24`, `numNodeMyoDimers:0`) = **9600 myosins**;
**1000 filaments × 10 segments** (`minFilLength:1.72`/`maxFilLength:1.82`) ≈ **10000 segments** (balanced so #segs ≈ #myo
≈ 10k); **crosslinking ON** (`xLinkOnRate:40`, `xLinkConc:1.0`); **aeta=0.1**; **treadmilling/biochem OFF**
(`noMonomersSimd:true` — static IC filaments, NO formin nucleation); random placement (`rdmPtInside`). v1 PF:
`/tmp/v1_fdt_diag/pf_1x` (scratch build `/tmp/v1scratch` = BoA-v1ref + a ThreeJSWriter crosslink-emit addition;
BoA-v1ref byte-clean). **v1 baseline (10000 steps): CPU 29 steps/s (349.7 s, 2.25 GB); GPU 22 steps/s (457.0 s incl.
~40-60 s JIT warmup, 4.24 GB); GPU/CPU=0.77×.** **Crossover finding:** the v1 GPU device path is kernel-launch-bound at
this scale — scaling 250→1000 filaments barely moved GPU (29→22 steps/s, work nearly free) but cratered CPU
(180→29), so GPU/CPU climbed 0.16×→0.77×; "1x" sits AT the CPU/GPU crossover (GPU overtakes only at larger scale —
v1max 16× was GPU 386 vs CPU 52, ~7×). GPU runs fine with `noMonomersSimd:true` for this singlet-myosin/no-minifil
config (no `Graph resize`). CPU 516 vs GPU 324 crosslinks = expected float32/RNG-ordering divergence (aggregate, not
bit-identical). **v2 (SoftBox) 1x harness BUILT — `softbox.V2OneXHarness` + `run_1x.sh`** (new files only, no shared
edits; pure composition of validated subsystems): ONE shared `FilamentStore` of 1000 static IC chain filaments (10
seg, random pose, biochemically inert — no growth/depoly/aging/sever/nucleation) that BOTH the 400×24 node singlet
myosins bind (grid binding + CrossBridge + nucleotide cycle/stroke + node gather) AND crosslinkers link; containment;
aeta=0.1 (Constants default). Scene built EXACT: 400 nodes / 10000 segs / 9600 myo / 40000 xlink slots, box 25 µm³.
**v2 CPU baseline: 13.9 steps/s** (vs v1 CPU 29 → **v2 ~2× slower** — grid-binding + per-step formation overhead;
profiling follow-up). Stable, no NaN, binding climbs 235→930 heads (contractile). **Parity deviations flagged:** (a)
**filament orientation** — v2 uses uniform-random orientation; v1's `makeRandomFilament` places by two random
in-box endpoints ⇒ in-plane bias (≤~17° tilt in the 0.5 µm slab), so v2 has 1580/10000 segs poking past ±z (bounded)
where v1 fits the slab — fix for EXACT parity = match v1's endpoint placement; (b) segLen 0.1755 vs nominal 0.176
(integer-monomer, 0.3%); (c) crosslinks slow to form from random placement (0 @200 steps vs v1's 516 @10k — compare
at 10k); (d) **GPU path = TODO** (the FullSystemDemo `Graph resize` single-TaskGraph blocker; CPU is the v1-comparable
baseline). `run_1x.sh -cpu -steps N` (`-gpu` falls back to CPU w/ notice).

## 2026-06-24 — DILUTE single-free-body FDT diagnostic EXECUTED: v1 free bodies move at CORRECT FDT (gate PASSES)
Ran the recommended clean diagnostic on byte-clean `BoA-v1ref` (CPU, external `/tmp` PF — no repo edit): **1 free
filament (single 0.194 µm rod) + 1 free node** (`numNodeMyos=0`, bare sphere, **known D=5e-15** control) in a 3 µm
empty box at **aeta=1.0**, **treadmilling OFF** (`noMonomersSimd=1` rigid rods; all poly/depoly/aging/sever/nucleation/
crosslink rates 0), full Brownian. 30k steps, 1204 frames @0.25 ms. **RESULT — bare-amplitude probe (per-frame MSD,
1203 samples): filament 1.007× FDT, node 0.990×; node fit recovers its set D 5.22e-15 vs 5.00e-15 (1.04×) ⇒ validates
pipeline + v1 amplitude.** Filament tracks the node at every lag (long-lag MSD rollover appears in BOTH ⇒ single-traj
statistics, not a filament deficit). Rotational 0.23× = the deliberate `BRotCoeff=0.5` amplitude (by design). **VERDICT:
v1's free bodies move at correct translational FDT ⇒ the dense-scene sub-FDT (prior entry) was network CONFINEMENT,
confirmed by removing it ⇒ NOT a free-body suppression, no fix needed ⇒ go build the matched benchmark scene.** Frames
viewable: `threejs_output_v1fdt_diag` (sim_server). `BoA-v1ref` byte-clean; no code change. Report:
`V1_STRAIGHT_FILAMENT_FINDINGS.md` §D.

## 2026-06-24 — v1 two filament populations + free-body FDT@aeta=1.0 check: CONFOUNDED BY CONFINEMENT (observation-only)
Gate before scene-matching: do v1's genuinely FREE bodies move at correct FDT amplitude (aeta=1.0 fixed yardstick),
or are they suppressed? Measured from existing GPU render frames `/tmp/v1max/threejs_v1_16x_free/` (23 frames,
0.022 s; the named `threejs_output_v1_16x_diag` doesn't exist — this is the matching freemotion render). No new run,
no edits. **Measurement 1 — TWO populations CONFIRMED:** free-IC **6095/6219 (98 %)**, mean 0.191 µm, actively
treadmilling+splitting (segment count grows **1984→6219**); formin-nucleated **124 (2 %)**, short stubs (mean
0.066/median 0.011 µm), count matches `kNodeNuc·400·0.022≈88–124`. Both prior single-population readings were real.
**Measurement 2 — FDT check CONFOUNDED:** per-segment displacement is treadmill/split-dominated (free-IC full set
reads ~8× ABOVE FDT — artifact); the constant-length+isolated (uncrosslinkable, un-tethered) subset reads ~0.17×
FDT but is **survivorship-biased toward stuck filaments**. **Decisive control = the NODE** (`nodeTransDiff=5e-15`
set directly ⇒ amplitude correct *by construction*): it STILL reads **0.06× that D** with a **plateauing MSD**
(1.9→5.5 nm² over 1–8 ms vs free 30→240) ⇒ confined to a ~2 nm cage by its own network — so **"below FDT" is NOT
diagnostic of an amplitude bug here; sub-FDT motion is network CONFINEMENT**, plus aeta=1.0 making true FDT small
(~7 nm/1 ms). Also: **no free myosin population exists** — all 7200 myosins are node-anchored (`minifilaments:0`;
`onFil=0` = unbound-from-filament, still node-tethered). **VERDICT: NOT a demonstrable free-body suppression; gate
INCONCLUSIVE from these frames** (1 ms/22 ms resolution + dense network can't isolate the bare amplitude). Do NOT
declare/fix a bug. **Recommend (flagged, not run): a dilute single-free-body diagnostic** (1 filament + 1 node in an
empty box, aeta=1.0, dump EVERY step, MSD vs 6Dt) to read the bare amplitude cleanly. Category stays (c) by design
(short+confined) with the caveat that free-FDT amplitude is **unverified** at this resolution. No code change.
Report: `V1_STRAIGHT_FILAMENT_FINDINGS.md` (Addendum A–C).

## 2026-06-24 — Why are v1's "free" filaments straight while treadmilling? CODE READ (observation-only)
Question gated by two prior wrong inferences ⇒ code read, not a mechanism guess. Scene = `/tmp/v1max`
`v1max_16x_freemotion` GPU render (`BoxOfActin -r -gpu`). **Leading hypothesis ("GPU Brownian gate zeroes their
thermal scale") FALSIFIED.** `Env.brownianFilMotionOff` is **never set** (no PF key, no code assignment — `bFilOff`
always false); per-segment `f.brownianOff` is **benchmark-only** (`makeStraightChain` `:4002` + `-deflect` `:2917`,
re-derived complete). The GPU gate (`GPUMoveThing.java:6399-6433/6511-6525`) applies **full translational Brownian**
to every node-scene filament. **Scene-ground-truth correction (from PF + frame):** this is **NOT a free-filament
assay** — it's a 400-node formin (`forminsPerNode:6`, `kNodeNuc:10`, release 1/s) + crosslinker (`xLinkOnRate:40`,
`xLinkTransAttn:1.0`, `maxLinksOnSeg:10`) network; the 7200 myosins = 400 nodes × 18 motors (jba's hub observation
✓); 0 minifil; 6219 short segments (mean **0.188 µm** ≪ Lp~10 µm). **"Barely moving" = two BY-DESIGN constraints,
both faithful CPU+GPU:** (M1, dominant) formin/node attachment slaves the filament to a near-stationary node
(`nodeTransDiff:5e-15` ⇒ node RMS/frame `sqrt(2·5e-15·1e-3)≈3.2 nm`, matching the recon's 3–5 nm); (M2) crosslink
Brownian attenuation `1/(1+xLinkTransAttn·linkedToCt)` (`linkedToCt`=crosslinker degree, `FilSegment.java:626,635`
CPU / `:6519-6522` GPU). **84/16 split** = constrained (attached/crosslinked, barely move) vs uncrosslinked-free
(full FDT ~80 nm) — a boolean, not a uniform gate; and 80 nm is invisible at a 16 µm/0.022 s field. **"STRAIGHT"** =
short+stiff + **end-segments-only rotational Brownian** (`rScale=0` when `(filAtEnd1&&filAtEnd2)`=interior;
`filAtEnd*`="has a linked neighbour at that end", `:2818-2832`) — **this convention AGREES with v2**
(`DiffusionHarness.java:543-544`), NOT a divergence. **The ONE real v1↔v2 divergence: v2 OMITS the crosslink
Brownian attenuation** (v2's `filLinkCt` feeds only the force-law `fracMove`, never `brownTransScale/brownRotScale`)
⇒ v2's crosslinked filaments are thermally louder by design. **Category (c) BY DESIGN** (no flag tripped, no bug;
short + genuinely constrained). No code change. Report: `V1_STRAIGHT_FILAMENT_FINDINGS.md`.

## 2026-06-24 — CSR-host promoted to the PRODUCTION DEFAULT (re-validate + re-baseline)
Branch `cadence-gate-fdturn` (the probe work FF-merged onto it). jba signed off on making CSR-host the default; per
CLAUDE.md a default flip re-baselines prior validation numbers, so this is the full job (not a flag flip). **Default
now = full CSR-host:** (1) the STATIC node-attach CSR-inverse (`attachNode` fixed) is host-precomputed once,
UNCONDITIONAL (`hostNodeCSR`; the 3 device scans never built) — pure win ~+2–3 % all scales; (2) the DYNAMIC
node-shell seg-gather CSR (`boundSeg`-keyed) is host-built each step (`hostSegCSR`, from `boundSeg` pulled after
`fdBind`) + re-uploaded `EVERY_EXECUTION` into `fdFil`. **`-devicecsr`** reverts (2) to the device path (static (1)
stays — bit-identical to device, so `-devicecsr` reproduces the old default's RESULTS exactly); **`-megakernel`
stays OPT-IN.** **Crossover investigation (the load-bearing decision):** an isolated warm-session draw read the
dynamic part −4.7 % at 1× (the bail trigger), but a **controlled 3-config back-to-back** (old-device(97) / static-
only(94) / full(91), same thermal state/scale) showed the dynamic part **net-positive at EVERY tested scale**
(+3.5/+3.5/+8.6/+8.3/+5.9 % at 1/2/4/8/16×) — the −4.7 % was a thermal outlier, so the bail toward static-only was
NOT taken; full CSR-host ships as default. **Re-baseline (controlled, vs old full-device): +6.8/+7.2/+7.0/+10.5/+7.1 %
at 1/2/4/8/16×** ⇒ new-default v2 steps/s **67.7/47.9/30.4/16.9/9.0** (v2/v1 ≈ 0.86/0.69/0.54/0.45/0.45 — narrows but
doesn't reverse v1's lead; the §4(b) work asymmetry still dominates, confirmed by the megakernel probe). **Re-validated
on the new default AND `-devicecsr`:** CPU≡GPU AGREE (identical aggregate — CSR is pure-integer ⇒ host==device bit-for-
bit), conservation EXACT, 0 phantoms, no NaN; `-cpu` arithmetic unchanged (cpuStep always computed CSR host-side);
constituent spot-check (node harness) green; `BoA-v1ref` byte-clean. **Creep guard:** the `EVERY_EXECUTION` re-upload
is WITHIN the existing `fdFil` execute() (not a new execute) ⇒ no new per-execute creep carrier; 4000-step window
showed no anomalous decay (the §8 creep is ~0.005 % at that horizon, sub-noise). **Scale caveat (flagged):** the
dynamic round-trip's copy traffic grows ∝ scale (≈350 KB/step at 16×) — re-verify net-positive at ring-scale before a
very large run; `-devicecsr` is the escape hatch. `SCALE_SWEEP_FINDINGS`/`V1_MAXIMAL_BENCHMARK §3` carry re-baseline
banners. Report: `MEGAKERNEL_PROBE_FINDINGS.md` (UPDATE banner); log `RUN_LOGS/2026-06-24_csrhost_default_rebaseline.txt`.


---
Archived through 2026-06-23 — see JOURNAL_ARCHIVE.md
