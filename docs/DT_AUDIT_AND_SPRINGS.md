# dt-dependence audit + the PAIRS-as-fixed-SPRINGS test instrument

**Date:** 2026-07-06. **PART A** (audit + authored instrument) **and PART B (EXECUTED, scoped short-window)
COMPLETE.** Part A: code read + derivation. Part B: build + B0 parity + the paired springs-vs-`-ratefix` short
comparison at dt=1.25e-6 (see the **PART B (EXECUTED)** section at the end).

Branch: **`pairs-springs`** (worktree `~/Code/SoftBox-springs`; rebased onto `~/Code/SoftBox` HEAD `e0d1e6d` for
the `-matband`/`-earlystop` levers). Default byte-identical; `BoA-v1ref` untouched. Flags: `-pairsprings`,
`-alignsprings`.

---

## HEADLINE (read this first — the premise correction)

Two findings up front, before the instrument:

1. **`-ratefix` scope — RESOLVED from the code (not the docs): `-ratefix` = `-strokerate` + `-alignrate` +
   `-filrate` — ALL three.** `GlidingHarness.java` (worktree line 181):
   `else if (args[i].equals("-ratefix")) { RATE_FIX = true; STROKE_RATE = true; ALIGN_RATE = true; FIL_RATE = true; }`.
   **`PAIRS_RATE_AUDIT_FINDINGS.md` is correct** ("`-ratefix` = `-strokerate` + `-alignrate` + `-filrate`", its
   line 85). **`ROTIMPLICIT_FEASIBILITY.md` is WRONG** where it states (line 63) "`-ratefix` = ... conversion of
   the **PAIRS chain** fracMove/fracMoveTorq" and lists `-alignrate`/`-strokerate` as *separate/orthogonal*
   (lines 63–65). They are NOT separate — `-ratefix` turns them on.

2. **CONSEQUENCE — the task's motivating premise is FACTUALLY OFF (flagged, not fatal).** The task states the
   converged reference (per-bound ≈1.78, `ROTIMPLICIT` Part B) "was measured with `-ratefix` but **not**
   `-alignrate`/`-strokerate`, so the motor alignment was still in raw fraction-per-step mode." That is
   incorrect: `ROTIMPLICIT` Part B's config was `-full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix`
   (its line 149), and since `-ratefix` **includes** `-alignrate`+`-strokerate`, **the motor alignment AND the
   stroke WERE already converted to per-time rates in that reference.** So the per-bound-1.78 converged reference
   is **NOT** a blend contaminated by uncompensated alignment — every per-step-fraction glide coefficient (motor
   rotational + filament) was rate-converted. The residual ~2.2× above production-dt in that reference is what
   `STROKE_DT`/`PAIRS_RATE` already concluded it is: **genuine forward-Euler rotational-integration stiffness,
   not model-definition rate.**

**Why this does NOT trigger the bail, and the instrument is re-motivated (not abandoned).** The bail condition
"the audit contradicts the 'residual is model-definition rate' premise" is aimed at the case where the audit
shows there is *nothing to isolate*. That is not the situation. The raw per-step-fraction laws ARE
model-definition dt-dependent (§A confirms it), and the springs remain a genuinely **different** dt-invariant
reformulation from `-ratefix`, so B1/B2 still answer a real, un-answered question — just a re-framed one:

> `-ratefix` freezes each per-step fraction as an **exact geometric-decay per-TIME rate**
> (`k_eff = 1−(1−k)^(dt/refDt)`), which is **integration-error-free** for that isolated relaxation (a stable
> geometric decay, never overshoots). `-pairsprings` instead freezes it as a genuine **fixed-stiffness Hookean
> spring** (`k = frac·γ_red/refDt`) integrated by the **same forward-Euler** as everything else — which
> **re-introduces** forward-Euler error into the PAIRS relaxation. The two agree at `dt=refDt` (byte-identical)
> but **converge to different continuum models** (different time constants, factor `−ln(1−k)/k`: 1.386 for
> `k=0.5`, 1.116 for `0.2`, 1.277 for `0.4`).

So the sharpened B1/B2 verdict is **not** "how much of 1.78 was uncompensated alignment" (answer: none) but:

- **Is the converged reference robust to HOW the rate is frozen?** (springs-ladder limit vs the `-ratefix`
  geometric-decay limit — do the two dt-invariant reformulations converge to the same glide?)
- **Does treating PAIRS/alignment as honest fixed springs (the physically-faithful reading of a stiff
  link/bending/converter) expose forward-Euler numerical stiffness that `-ratefix`'s exact-relaxation form
  HIDES?** i.e., under the spring reading, is the production-dt=1e-5 glide short of the spring-continuum by a
  numerical (integration) gap — the gap an implicit/sub-step would close — and is that gap the ~2.2×?

**jba decision point (deferred to the Part-B gate, by design):** given the premise correction, decide whether
the re-framed B1/B2 cross-check is worth the GPU ladders (≤16× wall at 6.25e-7). The instrument is authored and
committed so the run is one `go` away; nothing is spent until then. If the answer is "the residual is already
known-numerical, skip it," Part A still stands alone as the audit + scope resolution.

---

## PART A1 — the dt-dependence audit (every site a timestep enters a law)

**Taxonomy.** **NUM** = forward-Euler on a real force/torque (dt→0 convergent to a fixed model; legitimate).
**MODEL/frac** = a `frac·gap/((1/γa+1/γb)·dt)` force / `Δ=frac·gap` relaxation — the `1/dt` cancels the
integrator `·dt`, so the per-STEP relaxation is dt-independent ⇒ the physical RATE ∝ 1/dt (NOT convergent; a
model-definition coefficient calibrated at the production dt). **RATE** = proper `prob = k·dt` kinetics
(dt-correct). **NOISE** = Brownian (must scale √dt). **CLAMP/guard** = per-step displacement/force cap (bites
harder at large dt ⇒ silently dt-dependent). **GEOM** = a geometry scale, not a rate.

### The glide-critical laws (read directly, load-bearing for B1/B2)

| # | site | expression | class | dt-compensated today? |
|---|---|---|---|---|
| F3 link spring | `ChainBendingForceSystem:206-209,291-294` | `forceMag = fracMove·1e-6·strain/(dt·(moveC1+moveC2))`, `fracMove=chainParams[1]=0.5` | **MODEL/frac** | `-filrate`/`-ratefix` (rate); **`-pairsprings`** (spring) — NEW |
| F3 bending torque | `:211-213,296-298` | `T=R×F`, `R=½·len·fracR·ûVec`, `fracR=chainParams[2]=0.1` | rides on F3 (`fracMove`); `fracR`=**GEOM** | via `fracMove` (fracR unchanged) |
| F4 torsion (damped) | `:231,317` (gliding branch, `filTorqSpringActive=0`) | `torsionMag = fracMoveTorq·(π/180)·ang/((1/γ+1/γ)·dt)`, `fracMoveTorq=chainParams[3]=0.2` | **MODEL/frac** | `-filrate`/`-ratefix`; **`-pairsprings`** — NEW |
| F4 torsion (Hookean) | `:228,314` (`filTorqSpringActive=1`, inactive in glide) | `torsionMag = fracMoveTorq·filTorqSpring·ang` | already a spring (no `/dt`) | n/a (inactive) |
| F9/F10/axlock align | `CrossBridgeSystem:152,163,185` | `tm = j1FMT·(π/180)·ang/((1/γ+1/γ)·dt)`, `j1FMT=xbParams[2]=0.4` | **MODEL/frac (torque)** | `-alignrate`/`-ratefix`; **`-alignsprings`** — NEW |
| directedSwing stroke | `CrossBridgeSystem:260,415` (+`directedSwingHeadFrame`) | `mag = k·(π/180)·ang/((1/γ_lev+1/γ_head)·dt)`, `k=swingParams[0]=0.4` | **MODEL/frac (torque)** | `-strokerate`/`-ratefix`; **`-alignsprings`** — NEW (swingParams[4]=−refDt sentinel) |
| F8 tip/site torque | `bondForces:137-141` | `T=R×F8`, `F8=myoSpring·(site−tip)` positional Hookean | **NUM** (real positional torque; not a fracMove) | translation implicit via `-xbimplicit2`; ORIENTATION explicit (the residual stiffness) |
| F8 spring cap | `bondForces:76` (`-xbsat`, size>6, off by default) | caps `|F8|` above onset | **CLAMP/guard** | off by default |
| catch-slip release `g(F)` | `NucleotideCycleSystem:194-197` | `rate=kOff·(αC·e^(−F·xCatch/kT)+αS·e^(F·xSlip/kT))`; `u<rate·dt` | **RATE** (load-gated, dt-correct) | correct as-is |
| nucleotide 4-state | `NucleotideCycleSystem:50,53,56,64` | `u<rate·dt`, NONE→ATP→ADPPi→ADP→NONE | **RATE** | correct as-is |
| binding opportunity | `BindingDetectionSystem:194-220`, `reachTestDistSq:77` | `conDistSq ≥ myoColTol²` per-step geometric contact test | **per-STEP geometric check** (NOT a rate) | see note ‡ |
| binding (kОn variant) | `BindingDetectionSystem:479-481` (`-kon`, PHASE2) | `pBind = 1−exp(−kOn·chord·dt)` | **RATE** (reaction-limited) | dt-correct when enabled |
| Langevin translation | `RigidRodLangevinIntegrationSystem:76-89` | `v=1e6·F/γ; coord += dt·v` (`Δx=F·dt/γ`) | **NUM** (overdamped Euler) — this is the `·dt` the fracMove `/dt` cancels | — |
| Langevin orientation | `:92-113` | `Δθ = ±(T/γ)·dt`, frame update + renormalize | **NUM** | — |
| Brownian FDT | `BrownianForceSystem:57,98-104` | `randForce = tScale·√(2kT/dt)·√(γ)·g` | **NOISE — √dt CONFIRMED** (∝1/√dt force × `·dt` integrator ⇒ displacement ∝ √dt, correct) | correct as-is |

‡ **Capture note (directly relevant to the radius sweep):** binding opportunity is a **per-STEP geometric
contact test**, NOT a per-time rate — so at finer dt there are more contact checks per unit sim-time. This is a
*latent* per-step bias in binding flux, EXCEPT it is largely self-limiting because binding is **deterministic
bind-nearest-on-contact** (no per-step RNG draw) so a head in reach binds on the first step and stays; and the
optional `-kon` path (`pBind=1−exp(−kOn·chord·dt)`) IS a proper per-time rate. Flag: the pure geometric path is
not dt-rate-scaled; `PAIRS_RATE`/`STROKE_DT` treat the residual as a lifetime (force-wing) effect, consistent
with `binding-search-reformulation` (capture flux already ≈dt-invariant; the bound-count rise is the force wing).

### The rest of the codebase (breadth — same taxonomy, for completeness; none in the gliding scene)

Every subsystem carries the SAME `fracMove`/`fracMoveTorq` per-step-fraction convention (v1-inherited), so each
would need the same treatment in its own assay:

- **Crosslinkers** `CrosslinkerSystem`: trans spring `:97` **MODEL/frac**; torsion `:794-800` MODEL/frac
  (coeff-gain, no `/dt`); Bell unbind `:276` `kOff·dt` **RATE**; formation `:672` `P_form=1−exp(−kon·conc·dt)`
  **RATE**.
- **Nodes** `NodeSystem:91,210` radial tether **MODEL/frac**; `NodeNucleationSystem`: seedTether `:132,177`
  **MODEL/frac**, aim-rotate `:205` MODEL/frac (torque), nucleation `:69` `kNodeNuc·dt` **RATE**, detach `:257`
  `rate·dt` **RATE**.
- **Minifilament/dimer** `MiniFilamentSystem:101,120` + `DimerCouplingSystem:126,174`: tethers + align
  **MODEL/frac**.
- **Containment** `ContainmentSystem:102`: `mag = coeff·1e-6·delta·γ/collisionDeltaT` **MODEL/frac** — with a
  **`collisionDeltaT` (≠ stepping deltaT)** in the denominator AND a `collisionCheckInt=collisionDeltaT/deltaT`
  firing cadence (silently coupled to both timescales; `delta==0` inside ⇒ safe no-op).
- **Motor body** `MotorJointSystem:128,169` J1/J2 position springs + `:139,183` angular converters +
  `TailAnchorSystem:59`: all **MODEL/frac** (STRUCTURAL — hold the motor body rigid; the J1/J2 *torsions* are
  OFF in glide; left unconverted by both `-ratefix` and the springs — a flagged scope choice, PAIRS_RATE §STEP-3.
  The config-1 Hookean J1 `:180` `κ·θ` is a real **NUM** spring, off by default).
- **SphereHead** `SphereHeadSystem:162,212`: align + lever-swing torques **MODEL/frac** (these ARE the default
  gliding motor's rotational laws — same class as CrossBridge's; `-alignsprings`/`-alignrate` target the
  CrossBridge copies used by the SPHEREHEAD+AXLOCK+DIRSWING default path via `xbParams[2]`+swingParams).
- **Turnover** (`GrowthSystem:86`, `DepolySystem:87,132`, `AgingSystem:48-55`, `SeveringSystem:47`): all fire on
  the **biochemDeltaT** cadence (`biochemCheckInt=round(biochemDeltaT/dt)`) with `rate·biochemDeltaT`
  probabilities ⇒ **RATE** (aging is a forward-Euler **NUM** ODE on the nucleotide cascade). **KIN** scales the
  §7/§node kinetic rate constants (Ring3x3Harness `:268-283`) — NOT any mechanical fracMove law.
- **The only unconditionally dt→0-convergent laws:** the Langevin integrator (§9, with √dt-correct noise) and
  `ExternalSpringSystem:63-65` (`r=1e6·k·dt/γ`, operator-split backward-Euler — dt-stable/convergent).

**Audit bottom line.** Every `fracMove`/`fracMoveTorq` mechanical law (filament F3/F4, motor F9/F10/swing,
crosslinker/node/minifil/dimer tethers, motor joints, containment, SphereHead) is **MODEL/frac** — physical rate
∝ 1/dt, calibrated at production dt, NOT convergent. All kinetics (`rate·dt`, `rate·biochemDeltaT`,
catch-slip, nucleotide, nucleation, Bell unbind) are proper per-time **RATE**s. Brownian is √dt-correct
**NOISE**. The F8 tip torque is a real positional **NUM** torque (its orientation channel is the residual
explicit stiffness). This CONFIRMS `PAIRS_RATE` STEP-1 and extends it with breadth: the per-step-fraction
convention is codebase-wide, and in the glide scene exactly the F3/F4 (`-filrate`) + F9/F10/swing
(`-alignrate`/`-strokerate`) laws are the model-definition sites — all four are what `-ratefix` already
converts, and all four are what `-pairsprings`+`-alignsprings` now freeze as springs instead.

---

## PART A — the per-pair `k` derivation + unit check (paper/CPU-scratch)

**The PAIRS law (F3, `ChainBendingForceSystem:206-209`):**
`forceMag = fracMove·1e-6·strain / (dt·(moveC1+moveC2))`, force along the link `luVec`; `moveC1,moveC2` are the
per-body projected mobilities `= cos²β/γ_trans,∥ + sin²/γ_trans,⊥ + …/γ_rot` (units 1/drag).

**The integrator (`RigidRodLangevinIntegrationSystem:76-89`):** `v = 1e6·F/γ`, `coord += dt·v`, i.e. each body
moves `Δ_body = 1e6·(F/γ)·dt` along the force. Summing both bodies' contributions along the link, the
**relative link closure per step** is `Δ_rel = 1e6·(moveC1+moveC2)·forceMag·dt`. Substitute `forceMag`:

```
Δ_rel = 1e6·(moveC1+moveC2)·dt · [ fracMove·1e-6·strain / (dt·(moveC1+moveC2)) ]
      = fracMove·strain.                     ← dt, (moveC1+moveC2), and 1e6·1e-6 ALL cancel.
```

**⇒ Unit check / fraction-per-step CONFIRMED:** the link relaxes exactly `Δ_rel = fracMove·gap` per step,
**independent of dt** and of the mobility — the textbook fraction-per-step law (physical rate = fracMove/dt).
(F4 torsion is identical with `Δθ_rel = fracMoveTorq·ang`; F9/F10/swing identical with `k` in place of frac.)

**The fixed-stiffness spring and its `k`.** A Hookean `F_spring = k·gap` with `k = frac·γ_red/refDt`,
`γ_red ≡ 1/(1e6·(moveC1+moveC2))` (the per-pair reduced drag mapping relative force→relative velocity,
`v_rel = 1e6·(moveC1+moveC2)·F`). Forward-Euler on it: `Δ_rel = 1e6·(moveC1+moveC2)·F_spring·dt =
frac·(dt/refDt)·gap`.

**Realization = a build-time coefficient substitution, NO kernel edit** (the elegance): feed the **unchanged
kernel** the coefficient `frac_code = frac·(dt/refDt) ≡ springify(frac)`. Then the kernel computes
`forceMag = frac·(dt/refDt)·1e-6·strain/(dt·(moveC1+moveC2)) = frac·1e-6·strain/(refDt·(moveC1+moveC2))` — the
running `dt` **cancels to `refDt`** ⇒ a **fixed stiffness** `= frac·γ_red/refDt`, integrated by the same
forward-Euler. The per-pair `γ_red` is the kernel's own `1/(1e6·(moveC1+moveC2))`, computed per pair from each
body's drag ⇒ **pinned bodies (1/γ→0) are honoured automatically** (their moveC term vanishes; nothing to do).

- **Unit check of `k`:** `[k] = [frac]·[γ_red]/[refDt] = 1·(force·time/length)/time = force/length` ✓ (a
  stiffness). And `Δ_rel(dt=refDt) = frac·(refDt/refDt)·gap = frac·gap` ✓ — **identical to the raw PAIRS move at
  `dt=refDt` by construction** (⇒ byte-identical default at production dt=1e-5).
- **Stability:** the Euler step fraction is `α = frac·(dt/refDt) ≤ frac < 1` for `dt ≤ refDt` (the ladder only
  refines) ⇒ monotone, no new instability introduced.
- **springify vs rateFix (why they differ off-refDt):** `springify(k)=k·(dt/refDt)` (linear); `rateFix(k)=
  1−(1−k)^(dt/refDt)` (geometric). Equal at `dt=refDt`; for `dt=2.5e-6` (k=0.5): `springify=0.125`,
  `rateFix=0.159` — the spring is the slightly softer/forward-Euler form, the rate is the exact geometric decay.

**Sample pair (F3, fracMove=0.5, refDt=1e-5).** At the production dt=1e-5, `springify(0.5)=0.5·(1e-5/1e-5)=0.5`
⇒ `Δ=0.5·gap` (unchanged). At dt=2.5e-6, `springify(0.5)=0.125` ⇒ `Δ=0.125·gap` = the fixed spring's smaller
Euler step, continuum `Δ/dt → 0.5·gap/refDt` (rate 0.5/refDt, fixed). Bail condition "per-pair k not cleanly
derivable / law not fraction-per-step" is **NOT** triggered — every targeted law is exactly fraction-per-step
and `k` follows in one line.

---

## PART A2 — the authored (un-built) instrument

**Flags (both opt-in; default byte-identical; general "fraction-per-step → fixed spring" evaluator):**

- **`-pairsprings` (B1):** freeze the FILAMENT PAIRS coefficients as fixed springs —
  `chainParams[1]` fracMove (F3 link + its lever-arm bending torque, which rides on fracMove) and
  `chainParams[3]` fracMoveTorq (F4 torsion). `fracR` (geometry) untouched.
- **`-alignsprings` (B2):** ALSO freeze the motor alignment — `xbParams[2]` (F9/F10/axlock, build-time) and the
  directedSwing stroke (`swingParams[4]=−refDt` sentinel, one in-kernel branch). Structured as the trivial
  extension of the same evaluator, exactly as the task asked.

**Precedence:** a spring flag OVERRIDES the corresponding rate flag for the SAME coefficient slots, so the task's
configs work as written: `-ratefix -pairsprings` ⇒ PAIRS as springs + alignment as `-ratefix` k_eff rates (B1);
add `-alignsprings` ⇒ alignment also as springs (B2).

**Realization — pure build-time coefficient substitution, race-free by construction (CSR discipline held):**

| slot | file:line (worktree) | change |
|---|---|---|
| helper | `GlidingHarness.java:348` | `static float springify(double k){ return (float)(k*(DT/STROKE_REF_DT)); }` |
| PAIRS | `:384-385` | `filFracMove = PAIRS_SPRINGS ? springify(0.5) : (FIL_RATE ? rateFix(0.5) : 0.5f)` (and 0.2) |
| align F9/F10 | `:454` | `alignK = ALIGN_SPRINGS ? springify(0.4) : (ALIGN_RATE ? rateFix(0.4) : 0.4f)` |
| swing | `:489-491` | `ALIGN_SPRINGS` ⇒ `swingParams[4] = −STROKE_REF_DT` (sentinel) |
| swing kernel | `CrossBridgeSystem.java:238-241,396-399` | `else if (refDt < 0.0) k = k*(dt/(-refDt));` in `directedSwing` + `directedSwingHeadFrame` |
| decls / parse / disclosure | `GlidingHarness.java:116-117,193-194,307-308` | flags + `-pairsprings`/`-alignsprings` + printf disclosures |

**Why the CSR/`-cpu` discipline holds by construction:** `-pairsprings` and the `-alignsprings` `xbParams[2]`
change touch **only scalar constants fed to the UNCHANGED kernels** (chainForces, bondForces) — no kernel body,
no gather, no atomics, no `KernelContext` ⇒ CPU≡GPU trivially (a constant per run) and the race-free
segment-side CSR-inverse is untouched. The **one kernel edit** (the swing `refDt<0` branch) is a scalar
recompute of `k` from `swingParams[4]`, additive and behind a sentinel (default `swingParams` size 4 ⇒ the whole
`if(size>4)` block is skipped) ⇒ default byte-identical, and CPU≡GPU (Math.abs/`-refDt` lower on PTX, same
arithmetic as the existing `refDt>0` branch).

**Default byte-identity (the B0 gate, argued; to be MEASURED in B0):** with no spring flag, every coefficient is
the raw `0.5/0.2/0.4` and `swingParams` is size 4 ⇒ identical to current HEAD. Moreover **at production
dt=1e-5=refDt**, `springify(k)=k` for every k, and `swingParams[4]=−1e-5` gives kernel `k=0.4·(1e-5/1e-5)=0.4`
⇒ the ENTIRE spring stack is byte-identical to the raw law (and to the rate stack) at 1e-5. Divergence appears
only at finer dt. **First compile + all execution are Part B** (no build performed this session).

**Files changed (worktree `~/Code/SoftBox-springs`, branch `pairs-springs`):**
`softbox/GlidingHarness.java`, `softbox/CrossBridgeSystem.java`. (This doc + a JOURNAL line added.)

---

## PART B — the plan (GATED on jba's go + free aorus; re-motivated per the HEADLINE)

- **B0 — build + parity.** Build `pairs-springs`. Confirm **default (`-pairsprings` off) byte-identical** to
  HEAD. CPU≡GPU on `-pairsprings` (aggregate-within-SEM, the chaotic-gliding standard).
- **B1 — PAIRS-as-springs ladder.** Config `-full -grid -lymntaylor -adppibind -xbimplicit2 -ratefix
  **-pairsprings**`, seed 0, coltol10/d1000, dt `1e-5 → 2.5e-6 → 1.25e-6 → 6.25e-7` (120k/480k/960k/1920k).
  Report velFitX/avgBound/per-bound vs dt + climb ratios, **against the existing `-ratefix` seed-0 6.25e-7
  reference (per-bound 1.78, `ROTIMPLICIT` Part B).** Agreement ⇒ the converged glide is robust to the
  freeze-form (springs' fixed-stiffness == ratefix's geometric-rate in the limit, at least for glide);
  disagreement ⇒ the "converged reference" depends on HOW the rate was frozen, and springs (the physically-honest
  fixed-stiffness reading) are the trustworthy instrument for sizing the numerical residual.
- **B2 — add `-alignsprings`, re-run the ladder.** Freezes F9/F10 + swing as springs too. Report the same table.
- **Verdict to state plainly:** with PAIRS (B1) and alignment (B2) frozen as fixed springs, **how much of the
  ~2.2× residual is left at production dt=1e-5** — the TRUE forward-Euler numerical residual the implicit
  (`-rotimplicit`) / sub-step must recover. (Given the HEADLINE, this is now framed as a spring-vs-rate
  cross-check + a numerical-residual sizing, not a contamination cleanup.)
- **Progress logging:** the 6.25e-7 runs are ~16× wall; log `t=<sim_time> of <runtime>` + ETA per dt point to
  `.last_run_status`.

**Reproduce (Part B):**
```
# B0 parity
./build.sh
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -dt 1e-5 -seed 0 120000            # ≡ HEAD (byte-identical default)
./run_gliding.sh -gpu ... -xbimplicit2 -pairsprings -dt 1e-5 -seed 0 120000                                                       # ≡ raw at refDt (byte-identical)
# B1 (PAIRS springs)   dt 1e-5→120k, 2.5e-6→480k, 1.25e-6→960k, 6.25e-7→1920k:
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 -xbimplicit2 -ratefix -pairsprings -dt <DT> -seed 0 <STEPS>
# B2 (+ alignment springs): add -alignsprings
./run_gliding.sh -gpu ... -xbimplicit2 -ratefix -pairsprings -alignsprings -dt <DT> -seed 0 <STEPS>
```

---

## Bail assessment (why Part A committed, not bailed)

The three bail triggers: (1) **per-pair `k` not cleanly derivable / law not fraction-per-step** — NOT triggered
(§A: every targeted law is exactly fraction-per-step; `k=frac·γ_red/refDt` in one line, unit-checked). (2)
**default byte-identical fails in B0** — a Part-B measurement, not yet run (argued green above). (3) **audit
contradicts the residual-is-model-definition premise** — the audit shows the *converged reference* already
rate-compensated the model-definition dt-dependence (so that specific 1.78 residual is numerical, not
model-definition), which **corrects the task's framing** but does **not** empty the springs of purpose: they are
a distinct dt-invariant reformulation (fixed stiffness vs geometric rate) and answer the re-framed
robustness/numerical-sizing question. The correction is flagged LOUDLY here and routed to jba at the Part-B gate
(the natural decision point, already gated on jba's explicit go) rather than as a silent unilateral bail.
Committing an un-built, default-byte-identical instrument to an isolated throwaway branch harms nothing and
leaves the run one `go` away.

---

# PART B (EXECUTED) — springs vs `-ratefix` at 1.25e-6: the freeze-form barely moves per-bound

**Date:** 2026-07-06. GPU device-resident (`-full -grid ... -xbimplicit2`, the 23-kernel + coupled-star path);
aorus free (2% idle, no other java). Scoped short-window paired comparison (seed 0). Springs stay a diagnostic —
default byte-identical, NOT promoted. `BoA-v1ref` untouched.

## HEADLINE

**NULL on the decisive metric.** Freezing the stroke + F9/F10 alignment as **fixed Hookean springs**
(`-alignsprings`, ~0.81× as fast as the `-ratefix` geometric-decay at this dt) moves **per-bound from 1.690 →
1.608 — a −4.9% / ~0.5σ paired shift, within noise.** ⇒ the ~1.6–2.0 per-bound overshoot is **freeze-form-robust
= REAL per-head mechanics, NOT a stroke-law continuum artifact.** The retune lever is stroke/detachment kinetics
(κ pinned by force-matching), **not** the rate-vs-spring continuum choice. Secondary: velFitX and avgBound both
dropped ~11–15% (~1.8σ) — an **absolute-engagement** effect (softer springs bind slightly fewer heads), NOT a
per-bound-efficiency effect; direction matches the analytic expectation (slower alignment ⇒ lower absolute
glide), so the glide is mildly stroke-rate-limited in *absolute speed* but *not* in per-bound efficiency.
Arm 3 (filament freeze) NOT triggered (no per-bound shift to attribute).

## B0 — build + byte-identical parity (PASS; the build cross-check)

Built `pairs-springs` (rebased onto `e0d1e6d`; the JOURNAL + GlidingHarness rebase conflicts resolved keeping
BOTH the sweep's `-matband`/`-earlystop` and the spring flags). Two parity confirmations at **dt=1e-5**:

1. **default (no spring flag) ≡ HEAD — proven textually.** The only changed default-path lines are 4 ternary
   wraps (`filFracMove`, `filFracMoveTorq`, `alignK`, `swingParams`); each flags-off branch is the **verbatim
   original expression** ⇒ default computed values bit-identical to `e0d1e6d`.
2. **`-pairsprings -alignsprings` ≡ raw at refDt — BIT-IDENTICAL (measured).** Same config (seed 0, coltol10,
   d1000, 20k steps), raw vs springs: `velFitX 3.105 = 3.105`, `avgBsteady 3.050 = 3.050`, `inst 6.988 = 6.988`,
   `netX −2.862 = −2.862` — every digit. Confirms `springify(k)=k` at refDt (incl. the swing sentinel
   `swingParams[4]=−refDt` collapsing to `k·(dt/|refDt|)=k`). Bail (parity fail) NOT triggered.

## B1 — the paired short comparison (dt=1.25e-6, seed 0, coltol10, d1000, 0.2 s window)

Config `-full -grid -lymntaylor -adppibind -xbimplicit2 -matband 1.8 -earlystop -esthresh 1e-9 -escap 0.2`,
160k steps. `-esthresh 1e-9` forces early-stop to NEVER fire ⇒ **both arms run to the identical 0.2 s cap
window** (paired), while `EARLYSTOP_ROW` still reports the honest batch-means SEM (20 batches/arm). `-matband 1.8`
⇒ identical shrunk bed (nMot=8693 both arms), edge-guard **clear** both (minSegX 2.79/2.97 > bandXlo+guard 2.10).
per-bound = velFitX / avgBsteady (GRID_ROW), the ROTIMPLICIT convention.

| arm | scheme | velFitX ± SEM (relSEM) | avgBsteady | **per-bound** | duty | detach/s | dwell ms |
|--:|---|--:|--:|--:|--:|--:|--:|
| 1 | `-ratefix` (geometric baseline) | **7.447 ± 0.495** (6.6%) | 4.407 | **1.690** | 0.803 | 1255 | 0.797 |
| 2 | `-ratefix -alignsprings` (stroke+F9/F10 fixed springs) | **6.320 ± 0.401** (6.4%) | 3.931 | **1.608** | 0.802 | 1312 | 0.762 |

Coefficient at 1.25e-6: alignment `rateFix(0.4)=0.0618` (arm1) vs `springify(0.4)=0.0500` (arm2) ⇒ the spring is
**0.81×** the geometric-decay fraction (the `−ln(1−0.4)/0.4≈1.28` continuum gap). Swing likewise 0.0618→0.0500
(negligible per STROKE_DT). Filament PAIRS geometric in BOTH arms (arm2 = `-alignsprings` only).

**Paired shift (arm2 − arm1):**

| metric | Δ | % | ~σ (combined SEM) | read |
|---|--:|--:|--:|---|
| velFitX | −1.127 | −15.1% | ~1.8σ | probable real drop — **absolute-engagement** |
| avgBsteady | −0.476 | −10.8% | — | fewer bound heads under softer springs |
| **per-bound** | **−0.082** | **−4.9%** | **~0.5σ** | **NULL — freeze-form-robust** |

## Read-out (plain)

- **The decisive per-bound metric is a NULL** (−4.9%, ~0.5σ). Both arms sit at per-bound ~1.6–1.7 (short-window;
  cf. ROTIMPLICIT full-window 1.642 at this dt — arm1's 1.690 is the expected short-window inflation, in the
  sane 1.5–1.8 neighborhood the config-sanity check asks for). Freezing the stroke-law's ambiguous freeze-form
  (geometric rate → fixed Hookean spring, a ~19%-slower alignment relaxation) does **not** pull per-bound toward
  a distinctly lower band. ⇒ **the ~2× per-bound overshoot is real per-head mechanics, not the stroke-law
  continuum choice.** Per the read-out map, this is the **null** branch: the retune lever is stroke/detachment
  kinetics, not the continuum decision.
- **The velFitX/avgBound co-drop (~11–15%, ~1.8σ) is an engagement, not efficiency, effect.** avgBound fell
  4.41→3.93 and velFitX fell proportionally, so per-bound held — exactly the GLIDING_RADIUS_SWEEP separation
  (capture/engagement sets avgBound; per-bound is a separate stroke property). Direction matches the analytic
  expectation (softer/slower springs ⇒ lower absolute glide), so the glide is **mildly stroke-rate-limited in
  absolute speed but NOT in per-bound efficiency** — the freeze-form is an engagement-level knob.
- **How much of the ~2× overshoot is freeze-form/continuum vs real mechanics?** **At most ~5% (0.08 of ~1.6–2.0),
  within noise — i.e. effectively none.** The overshoot is real per-head mechanics.

## Follow-up (flagged, not run)

- **Arm 3 (`-ratefix -pairsprings -alignsprings`, filament freeze) — NOT triggered** by the gating rule (no
  per-bound shift worth attributing; and PAIRS_RATE already found the filament share within-noise/unresolved for
  this sparse single-filament AXIAL glide — its freeze-form is even less likely to move per-bound than the
  alignment's, which was null).
- **A 2–3 seed confirm on arms 1 & 2** would tighten the ~1.8σ single-seed velFitX **engagement** drop **if that
  absolute-speed freeze-sensitivity matters for a later decision.** It does **not** affect the per-bound overshoot
  verdict (the null rests on per-bound's 0.5σ). Reported as needed-if-relevant rather than guessed.

Raw: `RUN_LOGS/2026-07-06_pairsprings_B0.txt`, `_B1.txt`; runners `run_b0_parity.sh`, `run_b1_arms.sh`.

## Plain statement

At dt=1.25e-6 the two equally-v1-faithful freeze-forms of the stroke/alignment per-step law — `-ratefix`'s exact
geometric-decay rate and `-alignsprings`'s fixed Hookean spring (~0.81× as fast) — give the **same per-bound
glide within paired noise** (1.690 vs 1.608, ~0.5σ). So the ~2× per-bound overshoot above skeletal Vmax is **NOT
a stroke-law continuum artifact** — it is real per-head mechanics, to be addressed by stroke/detachment-kinetics
retune, not by choosing the rate vs spring continuum. The freeze-form does shift **absolute** engagement
(velFitX/avgBound ~11–15%, single-seed ~1.8σ, softer-spring-slower as predicted), a separate engagement-level
effect that a 2–3 seed run could tighten if the absolute number is later needed.
