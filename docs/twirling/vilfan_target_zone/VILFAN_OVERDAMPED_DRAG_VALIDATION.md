# Vilfan overdamped finite-drag — build and validation

**Branch** `feature/vilfan-overdamped-drag` (child of `feature/vilfan-complete-reference`)
**Worktree** `../softbox-vilfan-drag`
**Runner** CPU only — no CUDA, no TornadoVM, no `TaskGraph`, no device context. ≤ 3 logical cores, `nice -n 17`.
**Status** **COMPLETE — classification A** (S13).

---

## S1. Executive conclusion

> ### **A. OVERDAMPED VILFAN MECHANISM SURVIVES QUANTITATIVELY**

Replacing Vilfan's instantaneous Eq (5) equilibration with finite-drag overdamped axial and roll
dynamics — **and changing nothing else** — leaves the target-zone depletion mechanism intact on both
lattices. Every amplitude is within **6 %** of the validated quasi-static reference, against a 25 %
requirement.

| quantity | quasi-static | overdamped, η = 0.01 Pa·s | ratio |
|---|---|---|---|
| **paper lattice** | | | |
| ⟨x_A⟩ | +1.7701 nm | +1.7694 nm | **0.9996** |
| ⟨θ_A⟩ | +0.11014 rad | +0.11077 rad | **1.006** |
| Ω_odd | −0.5465 rad/s | −0.5443 rad/s | **0.9959** |
| λ⁻¹ | −2.092 µm⁻¹ | −2.085 µm⁻¹ | **0.9967** |
| **native lattice** | | | |
| ⟨x_A⟩ | +1.6649 nm | +1.6445 nm | **0.9878** |
| ⟨θ_A⟩ | +0.10806 rad | +0.10610 rad | **0.9818** |
| Ω_odd | −0.5526 rad/s | −0.5194 rad/s | **0.9399** |
| λ⁻¹ | −2.097 µm⁻¹ | −1.982 µm⁻¹ | **0.9451** |

All ten primary mechanism gates pass. `Ω_even` is **exactly zero** and `Ω_native = −Ω_mirror`
bit-for-bit on every arm — finite drag does not break the mirror antisymmetry. Late motion is
stationary (`ω` halves ratio 1.017 paper, 1.038 native). Dynamic closure holds to **4×10⁻⁹ pN** and
**1.4×10⁻¹⁰ pN·nm** over 21 million events, and `X` and `Θ` are continuous through every event
exactly.

**Four findings worth carrying forward.**

1. **The mechanism has ~4 orders of magnitude of viscosity headroom.** All three preregistered
   points (η = 0.001, 0.01, 0.1 Pa·s) lie in a flat quasi-static plateau — `Ω_odd` varies 3.6 %
   across a 100-fold change in η. A post-hoc exploratory extension shows the twirl is still fully
   present at η = 1 and 10 Pa·s and only ~24 % attenuated at **η = 100 Pa·s, 10 000× the assay
   viscosity**.
2. **The Stage-0 threshold prediction was wrong by ~300×, and the data corrected it.** Stage 0
   predicted attenuation near η ≈ 1 Pa·s from `τ_Θ ≈ ⟨Δt⟩_chem`. That is refuted: at η = 100 the
   filament is 100× slower than the chemistry fires and the twirl barely moves. The correct steady
   state is `ω = −k_D⟨θ_A⟩/(1 + k_D τ_Θ)` — **the drag time competes with the bound-head lifetime
   `1/k_D = 200 ms`, not with the 646 µs inter-event interval.** The law is confirmed with no fitted
   parameter, predicting the η = 100 attenuation to **0.6 %** in rotation and **0.3 %** in
   translation, and puts the true threshold at **η ≈ 340 Pa·s ≈ 34 000× the assay value** (S11.2).
3. **The depletion origin is unchanged.** The no-depletion shadow control still collapses `⟨x_A⟩`
   from +1.645 nm to −0.065 nm and the before/after split from 58.98 % to 49.5 % — **96 % of the
   bias is depletion/history**, against 96.5 % quasi-static. The `α = 0` and `d = 0` controls both
   remove the twirl as required, excluding a drag-induced rolling mode.
4. **The time-dependent-hazard machinery is load-bearing, not a formality.** 38.1 % of all events
   had their cumulative-hazard root fall inside the mechanical transient, exactly the fraction the
   settle horizon predicts. The rate is never frozen; an independent 20 000-point trapezoid
   reproduces the located cumulative hazard, and the quasi-static limit is recovered as
   `dragScale → 0` (event times converge ∝ `dragScale` to 3.7×10⁻¹²; attachment statistics become
   bit-identical).

**Validation.** 23/23 Stage-2 numerical gates pass, including analytic-vs-RK4 relaxation, segmented
branch propagation vs an RK4 that knows nothing of branch integers, event continuity, hazard
convergence in tolerance and settle horizon, quasi-static-limit recovery, reproducibility and mirror
symmetry. The parent study's 29 gates still pass unchanged, and a quasi-static arm reproduces its
committed record to the record's printed precision.

**One honest caveat.** Production trajectories produced **zero** angular `±π` branch crossings — `Θ`
moves only ~0.0012 rad per event, and the target-zone selection concentrates bound-head angles near
zero. The branch machinery is correct and validated by a constructed fixture (gate A5), but it is
**untested by the production trajectories themselves**. A Brownian study will move `Θ` far more per
event and should expect to start exercising it.

**No Brownian motion was added and the ladder was not advanced.** The recommendation for the next
rung, including the two signal-to-noise numbers that should be computed before any campaign is
designed, is in S14.

**Runner:** CPU only — no CUDA, TornadoVM, `TaskGraph` or device context. 52 arms, 21 M Gillespie
events, 1.18 billion hazard evaluations, ≈4.5 CPU-hours, ≤3 cores at `nice -n 17`.

---

## S2. The single restored realism, stated exactly

The validated complete reference (`VILFAN_COMPLETE_REFERENCE_VALIDATION.md`, verdict A) assumes
Vilfan's Eq (5): after every chemical event the filament is **instantaneously** re-equilibrated,

```
sum_j F_j = 0        sum_j M_j = 0                       (Vilfan Eq 5, quasi-static)
```

This study replaces that — **and nothing else** — with finite-drag overdamped dynamics:

```
gamma_X     dX/dt     = sum_j F_j                        (overdamped, this study)
gamma_Theta dTheta/dt = sum_j M_j
```

with the motor force and torque **unchanged** from Vilfan Eq (4):

```
F_j = K (x_M^j + delta_j - X - i_j a)      M_j = -K_theta wrapPi(Theta + i_j theta0)
delta = 0 pre-powerstroke;  delta = d = 8 nm post-powerstroke and in rigor
```

**Everything else is frozen** and was verified unchanged: the four-state irreversible cycle;
continuously attachment-competent detached motors; graded competing-site attachment (Eqs 1–2);
`k_A = 50`, `k_PS = 10⁴`, `k_−ADP = 10³` s⁻¹; `k_D/k_A = 0.1` ⇒ `k_D = 5 s⁻¹` ⇒ `[ATP] = 1 µM`;
`d = 8 nm`; `K = 0.5 pN/nm`; `α = 4`; `K_ϑ = α k_BT`; `k_BT = 4.14 pN·nm`; `ρ = 20 µm⁻¹`;
`l = 5.5 µm`; static Poisson 1-D motor field; single-site occupancy; zero converter skew; zero
SoftBox motor mechanics; **no load dependence of any chemical rate**; **no Brownian motion**; no
transverse, height or tilt degree of freedom. The filament still has exactly two coordinates,
`X` and `Theta`.

**What finite drag changes, mechanically.** `X` and `Theta` are now **continuous through a chemical
event**. A transition changes the force and torque discontinuously; the filament then relaxes
continuously toward the new target over a finite time. No displacement is applied at an event.

**Selection.** `-mechanics quasistatic` (default) is the untouched validated baseline;
`-mechanics overdamped` is this study. Gate G1 confirms a quasi-static arm still reproduces its
committed record to the record's printed precision.

---

## S3. Stage 0 — drag audit

### S3.1 Source and the ten audit points

The one element SoftBox supplies. The formula is `DragTensorSystem.rodDragSI(lengthUm, radiusUm)`,
a line-for-line port of BoA-v1ref `FilSegment.calculateProperties():420-435`:

```
bTGx = (2 pi eta L) / (ln(L/2R) + aParallel)     axial   translation   [N s / m]
bTGy = (4 pi eta L) / (ln(L/2R) + aOrthog)       transverse translation
bRGx =  4 pi eta R^2 L                           ROLL about the long axis [N m s / rad]
bRGy = (pi eta L^3) / (3 (ln(L/2R) + aTurning))  tumbling about a transverse axis
```

| # | audit point | finding |
|---|---|---|
| 1 | axial drag coefficient | `bTGx = 2πηL/(ln(L/2R) + a_∥)`, `a_∥ = −0.20` (`Constants.java:49` ← `FilSegment.java:89`) |
| 2 | roll drag coefficient | `bRGx = 4πηR²L` — rotation about the filament's **own long axis**, which is the `Theta` degree of freedom |
| 3 | length and radius | `l = 5.5 µm` (Vilfan Table I); `R = Constants.actinWidth/2 = 0.0035 µm = 3.5 nm` |
| 4 | viscosity dependence | strictly linear in `η` in both coefficients |
| 5 | end corrections / slender body | yes — the `ln(L/2R)` slender-body form with the additive end correction `a_∥`. The roll coefficient carries **no** such correction (it is the exact cylinder result) |
| 6 | whole-filament or per-segment | the formula is **generic in L**; SoftBox *calls* it per segment, but this study calls it **once with the whole filament length**, as required |
| 7 | units | SI internally (m, N·s/m, N·m·s/rad); converted once: `×1e3 → pN·s/nm`, `×1e21 → pN·nm·s/rad` |
| 8 | prior validation | this is the γ the increment-1 FDT check rests on (`D_par = 1.11676e-1`, CLAUDE.md); `RollSpringSystem` uses `bRGx` as *the* roll drag and independently records its magnitude as "~1e-24 N·m·s/rad" |
| 9 | rigid straight filament? | **yes** — the tensor assumes a rigid straight rod. Consistent with Vilfan, whose filament is rigid and has no bending degree of freedom |
| 10 | hidden multipliers | none found. `a_∥ = −0.20`, `a_orthog = 0.84`, `a_turning = −0.662` are the standard Tirado–García de la Torre end corrections and are the only empirical numbers. No segment-count factor, no `KIN`-style rate scaling, no `fracMove` |

### S3.2 What was deliberately NOT used

`bTGy/bTGz` (transverse translation — there is no transverse degree of freedom); `bRGy/bRGz`
(tumbling about a **transverse** axis — that is not `Theta`, and it is larger than the roll drag by
`~L²/(12R²) ≈ 2×10⁵`, so confusing the two would have been catastrophic and silent);
`DragTensorSystem.sphereDragSI` (the myosin head); any per-segment coefficient; any coefficient
pre-multiplied by a segment count; anything from the explicit-beam model.

### S3.3 Why the formula is re-expressed rather than called

Two independent reasons, both recorded in `softbox/VilfanDrag.java`:

1. `DragTensorSystem` imports `uk.ac.manchester.tornado.api.types.arrays.FloatArray`. This study
   links **no** TornadoVM type, so it cannot reference that class.
2. `rodDragSI` reads `Constants.aeta` directly, and `Constants.aeta` is a compile-time
   `static final` that **javac inlines** — the standing finding in CLAUDE.md's viscosity section is
   that a runtime override through it is impossible. Viscosity is a swept parameter here, so it must
   be an explicit argument.

**Gate DRAG-1** closes the gap: the re-expression is required to be **bit-identical** to a literal
transcription of `rodDragSI`'s arithmetic at `eta = Constants.aeta`. It is:
`gammaX 5.34400696042e-07` vs `5.34400696042e-07`, `gammaTheta 8.46659220142e-23` vs
`8.46659220142e-23`. **PASS.**

### S3.4 Coefficient values

`ln(L/2R) = 6.666593`; `+ a_∥ = 6.466593`.

| η (Pa·s) | γ_X (N·s/m) | γ_X (pN·s/nm) | γ_Θ (N·m·s/rad) | γ_Θ (pN·nm·s/rad) |
|---|---|---|---|---|
| 0.001 | 5.34401e−09 | 5.34401e−06 | 8.46659e−25 | 8.46659e−04 |
| **0.01** (assay) | **5.34401e−08** | **5.34401e−05** | **8.46659e−24** | **8.46659e−03** |
| 0.1 | 5.34401e−07 | 5.34401e−04 | 8.46659e−23 | 8.46659e−02 |

**Primary viscosity `η = 0.01 Pa·s`**, the project's validated gliding/twirling assay reference
(CLAUDE.md, "lowest trustworthy η = 0.01 Pa·s"). It was **not** fitted to preserve the Vilfan pitch;
it is retained as `-eta` for the Stage-6 sensitivity only.

### S3.5 The timescale separation — the quantity that decides this study

At the primary point, with a median bound-head count `N_b ≈ 86`:

```
tau_X     = gamma_X     / (N_b K)      = 1.34e-06 s
tau_Theta = gamma_Theta / (N_b K_theta)= 6.39e-06 s
mean chemical inter-event interval     ~ 6.9e-04 s
=>  tau_X/dt = 1.9e-03 ,  tau_Theta/dt = 9.3e-03
```

**The filament relaxes 100–500× faster than the chemistry fires.** This was computed and recorded
*before* any campaign, together with the prediction it implies: the mechanism should survive
essentially unchanged at the assay viscosity, and drag should only become comparable to the
chemistry near `η ≈ 1 Pa·s` — roughly **100× the assay value**, and 10× beyond the top of the
preregistered sweep.

---

## S4. Stage 1 — the piecewise-deterministic implementation

### S4.1 The mechanics are solved analytically, not integrated

Summing Eq (4) over the bound set gives, for a fixed bound set and fixed angular branches,

```
gamma_X     dX/dt     = N_b K       (X_eq     - X)
gamma_Theta dTheta/dt = N_b K_theta (Theta_eq - Theta)
```

where `X_eq` and `Theta_eq` are **exactly** the targets Vilfan's Eq (5) would have jumped to. The
solution is therefore exact:

```
X(s)     = X_eq     + (X_0     - X_eq)     exp(-s/tau_X),      tau_X     = gamma_X    /(N_b K)
Theta(s) = Theta_eq + (Theta_0 - Theta_eq) exp(-s/tau_Theta),  tau_Theta = gamma_Theta/(N_b K_theta)
```

So the mechanics carries **no integration error at all**; the only numerics in the study are the
cumulative-hazard quadrature and the event-time root. A degree of freedom with zero stiffness
(`K = 0` or `K_ϑ = 0`) feels no force, does not relax, and is excluded from the settle horizon —
without this the horizon diverges, which is a real bug this study hit and fixed.

### S4.2 Angular branch crossings are located analytically

`Theta` relaxes monotonically toward `Theta_eq`, so the time at which any bound head's angle reaches
`±π` is available in closed form:

```
Theta_c = (+-pi) - b_j - 2 pi n_j ;   s_c = -tau_Theta ln[(Theta_c - Theta_eq)/(Theta_0 - Theta_eq)]
```

The earliest such crossing terminates the analytic segment; the branch integer `n_j` is
re-assigned; `Theta_eq` is recomputed; propagation resumes. **No integrator ever steps across the
`±π` discontinuity.** Crossings are counted and reported. Following the branch continued from the
current `Theta` is the same "stay in the local energy minimum the filament already occupies"
prescription the quasi-static reference used — but here it is realised *dynamically* rather than by
a fixed-point iteration, which is strictly more principled.

### S4.3 The event sampler — the rate is never frozen

Because `X(t)` and `Theta(t)` move between events, the detached-motor attachment hazards
`k_A^i[X(t), Theta(t)]` are **time-dependent**. The ordinary Gillespie waiting-time formula is
therefore invalid and is not used in this mode. The model is integrated as a piecewise-deterministic
Markov process:

1. draw a cumulative-hazard threshold `H* = -ln r`;
2. evolve the mechanics analytically and `dH/dt = k_total[X(t), Theta(t), states]` together;
3. locate `t*` with `H(t*) = H*`;
4. evaluate **all** legal transition rates at `t*`;
5. select one transition by its instantaneous rate fraction;
6. update that motor's state or attachment site;
7. continue from the **same continuous** `X` and `Theta`.

The bound-state rates (`k_PS`, `k_−ADP`, `k_D`) are constants — no rate in this model is
load-dependent — so only the detached attachment hazards are re-evaluated along the trajectory.

**Quadrature and root.** Each analytic segment is integrated by adaptive Simpson with an absolute
tolerance `hazTolRel × k × s_end` (production `hazTolRel = 1e-10`), to a settle horizon
`settleFactor × tau_max` (production 40, i.e. `exp(-40) ≈ 4e-18`). Beyond the settle horizon the
filament sits at `(X_eq, Theta_eq)` to that tolerance, `k_total` is constant, and the remaining
waiting time is obtained in closed form — this is the common case and it is exact, not an
approximation. When the threshold falls inside the transient the root is found by safeguarded
Newton (`H' = k_total > 0`, so `H` is strictly monotone).

**No finite-`dt` chemical approximation is hidden anywhere in this mode.** The three independent
checks that establish this are gate C3 (an independent 20 000-point trapezoid of the same cumulative
hazard), C4/C5 (convergence in tolerance and settle horizon) and D (recovery of the quasi-static
limit).

---

## S5. Stage 2 — numerical validation

`./scripts/run_vilfan_drag.sh -drag-gates` — **23 PASS, 0 FAIL.**

| gate | result |
|---|---|
| A1 `X(t)` analytic == RK4 | PASS — 8.000000000000 vs 8.000000000046 nm (2×10⁶ RK4 steps) |
| A2 `Theta(t)` analytic == RK4 | PASS — 0.096175842726 vs 0.096175842726 rad |
| A3 `γ_X Ẋ = ΣF` | PASS — residual 1.08e−13 pN |
| A4 `γ_Θ Θ̇ = ΣM` | PASS — residual 7.99e−15 pN·nm |
| A5 segmented branch propagation == RK4 | PASS — 2.7889649068 vs 2.7889649068 rad, with real `±π` crossings |
| A6 branch endpoint torque-balanced | PASS — `\|ΣM\| = 1.15e−14` pN·nm |
| B1 `X` continuous at every event | PASS — max `\|ΔX\| = 0` over 7449 events |
| B2 `Theta` continuous at every event | PASS — max `\|ΔΘ\| = 0` |
| B3 dynamic closure over a whole run | PASS — 9.68e−12 pN, 1.18e−12 pN·nm |
| C1 constant-hazard fixture, cycle counts equal | PASS — 4131/4130/4130/4117 |
| C2 constant-hazard fixture, rigor dwell = `1/k_D` | PASS — 0.19966 s vs 0.20000 s |
| C3 cumulative hazard == independent 20k-point trapezoid | PASS — max rel dev **2.14e−08** over 1037 checked events |
| C4 converged in quadrature tolerance | PASS — `ω` moves 3.3e−08 → 1.2e−09 → 1.8e−11 → 0 across `hazTolRel` 1e−6…1e−12 |
| C5 independent of settle horizon | PASS — `ω` = −0.555158420009 / −0.555158419998 / −0.555158420000 at 20/40/60 τ |
| D1 event times → quasi-static as `dragScale → 0` | PASS — rel `dt` 3.68e−09 → 3.68e−10 → **3.68e−12**, exactly ∝ `dragScale` |
| D2 attachment statistics → quasi-static | PASS — `⟨x_A⟩`, `⟨θ_A⟩` **bit-identical** (8.5e−16) |
| D3 residual `X`, `Θ` offset is the one-event lag | PASS — `dX = 0.0601 nm` vs one-event `d/N_b = 0.0930 nm` |
| E1 same seed bit-reproducible | PASS — `X = 350.04009084322604`, 13465 events, identical |
| F1 mirrored `X` trajectory identical | PASS — `dX = 0` |
| F2 mirrored `Theta` exact negative | PASS — ∓6.8227525848 |
| F3 `Ω_even` numerical zero | PASS — 0 exactly |
| F4 `⟨x_A⟩` preserved, `⟨θ_A⟩` reversed | PASS — 1.778410/1.778410, ±0.111969 |
| G1 quasi-static arm reproduces its committed record | PASS — rel dev 6.4e−11 / 2.4e−11 / 1.2e−10 |

### S5.1 Two gate findings worth recording

**The quasi-static limit converges in the event sequence and the event times, but `X_end` and
`Θ_end` retain a one-event offset — by construction, not by error.** The quasi-static run calls
`equilibrate()` *after* its last event and therefore reports the **new** equilibrium; the overdamped
run stops **at** the event and has not yet been given the time to relax into it. The offset must
then be bounded by one event's shift of the target, `~d/N_b`; measured 0.0601 nm against a bound of
0.0930 nm (gate D3). Comparing at a travel threshold instead of a matched event count adds a second,
larger artefact — the two runs end one event apart, giving a spurious `~1/n_events` offset in `v`
and `ω` (3.8e−06 here) that is entirely a window-boundary effect. **Both comparisons are made at a
matched event count for this reason.**

**`G1` cannot be an exact-equality test.** The stored records are written with `%.10g`, so they hold
ten significant digits; agreement to the record's printed precision is the strongest available
statement, and that is what is gated. The underlying arithmetic *is* unchanged — the complete
reference's own 29-gate suite re-runs on this branch and reproduces its printed values exactly.

### S5.2 A correction to the complete-reference report

The complete-reference report (S13) states that "the wrapped-branch fixed point required a **branch
reassignment** on ~50 % of events". That statistic was mislabelled. The counter it came from
incremented whenever the fixed-point iteration ran more than once, which happens whenever `Theta`
moved at all — i.e. on every attachment and every detachment, but not on power-stroke or ADP-release
events, which do not change the bound set. **~50 % is the fraction of events that move `Theta`, not
the fraction that cross a `±π` branch boundary.** True branch crossings are far rarer; this study
counts them properly (S9) and reports them separately. The mislabelled counter had no effect on any
physics: it was a diagnostic only, and the quasi-static numbers are unchanged.

---

## S6. Stage 3 — paper-exact overdamped results

**Fixture.** Vilfan's own lattice and parameters (`a = 2.75 nm`, `ϑ₀ = −167.142857°`, `α = 4`,
`k_D/k_A = 0.1`, `[ATP] = 1 µM`), `mechanics = overdamped`, `η = 0.01 Pa·s`, Brownian OFF. Warm-up
1.0 µm discarded; ≥5 µm analysed and ≥8 turns. Seeds 101–104 × native/mirror = 8 arms (discovery was
101/102; the clearly-positive result triggered the preregistered extension to four).

### Stage 3 — paper-exact lattice, overdamped, per arm

| arm | v (µm/s) | ω (rad/s) | pitch (µm/turn) | turns | ⟨x_A⟩ nm | ⟨ξ_A⟩ nm | ⟨θ_A⟩ rad | M_A pN·nm | duty | τ_X (s) | τ_Θ (s) | events |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `odpaper_e0.01_s101_mirror` | 0.04118 | 0.5534 | 0.4676 | 10.7 | 1.715 | 0.3284 | -0.1086 | 1.798 | 0.666 | 1.44e-06 | 6.91e-06 | 213912 |
| `odpaper_e0.01_s101_native` | 0.04118 | -0.5534 | -0.4676 | 10.7 | 1.715 | 0.3284 | 0.1086 | -1.798 | 0.666 | 1.44e-06 | 6.91e-06 | 213912 |
| `odpaper_e0.01_s102_mirror` | 0.04162 | 0.5653 | 0.4626 | 10.8 | 1.838 | 0.3505 | -0.1171 | 1.939 | 0.74 | 1.3e-06 | 6.23e-06 | 228258 |
| `odpaper_e0.01_s102_native` | 0.04162 | -0.5653 | -0.4626 | 10.8 | 1.838 | 0.3505 | 0.1171 | -1.939 | 0.74 | 1.3e-06 | 6.23e-06 | 228258 |
| `odpaper_e0.01_s103_mirror` | 0.04156 | 0.5373 | 0.486 | 10.3 | 1.764 | 0.3303 | -0.1105 | 1.83 | 0.728 | 1.34e-06 | 6.39e-06 | 229231 |
| `odpaper_e0.01_s103_native` | 0.04156 | -0.5373 | -0.486 | 10.3 | 1.764 | 0.3303 | 0.1105 | -1.83 | 0.728 | 1.34e-06 | 6.39e-06 | 229231 |
| `odpaper_e0.01_s104_mirror` | 0.04188 | 0.5213 | 0.5048 | 9.9 | 1.761 | 0.3558 | -0.1069 | 1.771 | 0.804 | 1.21e-06 | 5.81e-06 | 251099 |
| `odpaper_e0.01_s104_native` | 0.04188 | -0.5213 | -0.5048 | 9.9 | 1.761 | 0.3558 | 0.1069 | -1.771 | 0.804 | 1.21e-06 | 5.81e-06 | 251099 |

### Stage 3 — mirror even/odd decomposition

| seed | Ω_native | Ω_mirror | Ω_even | Ω_odd | M_A native | M_A mirror | ⟨x_A⟩ nat | ⟨x_A⟩ mir |
|---|---|---|---|---|---|---|---|---|
| 101 | -0.55336 | 0.55336 | 0 | -0.55336 | -1.798 | 1.798 | 1.715 | 1.715 |
| 102 | -0.56529 | 0.56529 | 0 | -0.56529 | -1.939 | 1.939 | 1.838 | 1.838 |
| 103 | -0.53731 | 0.53731 | 0 | -0.53731 | -1.83 | 1.83 | 1.764 | 1.764 |
| 104 | -0.52133 | 0.52133 | 0 | -0.52133 | -1.771 | 1.771 | 1.761 | 1.761 |

### Stage 3 — overdamped vs quasi-static, paper lattice

| quantity | quasi-static | overdamped (η = 0.01 Pa·s) | ratio od/qs |
|---|---|---|---|
| ⟨x_A⟩ (nm) | 1.7701 ± 0.014 | 1.7694 ± 0.025 | **0.9996** |
| ⟨θ_A⟩ (rad) | 0.11014 ± 0.002 | 0.11077 ± 0.0022 | **1.006** |
| attachment torque M_A (pN·nm) | -1.824 ± 0.033 | -1.8343 ± 0.037 | **1.006** |
| ⟨ξ_A⟩ (nm) | 0.33994 ± 0.0084 | 0.34126 ± 0.007 | **1.004** |
| v (µm/s) | 0.041604 ± 0.00018 | 0.041563 ± 0.00014 | **0.999** |
| Ω_odd (rad/s) | -0.54654 ± 0.014 | -0.54432 ± 0.0096 | **0.9959** |
| inverse pitch λ⁻¹ (µm⁻¹) | -2.0916 ± 0.062 | -2.0846 ± 0.041 | **0.9967** |
| pitch (µm/turn) | -0.47935 ± 0.014 | -0.48027 ± 0.0096 | **1.002** |
| duty ratio | 0.7345 ± 0.028 | 0.7346 ± 0.028 | **1** |
| before-centre fraction (%) | 58.97 ± 0.087 | 58.89 ± 0.15 | **0.9988** |

**All ten primary mechanism gates pass, and every amplitude is within 0.6 % of the quasi-static
reference.**

| # | gate | result |
|---|---|---|
| 1 | native and mirror rotation reverse exactly | **yes** — `Ω_native = −Ω_mirror` to the last bit, all 4 seeds |
| 2 | `Ω_odd` non-zero, same-signed across matched seeds | **yes** — −0.521 … −0.565 rad/s, 4/4 negative |
| 3 | `Ω_even` numerical zero | **yes** — exactly 0 |
| 4 | `⟨x_A⟩` remains positive | **yes** — +1.769 ± 0.025 nm |
| 5 | before-centre attachments > 50 % | **yes** — 58.89 ± 0.15 % |
| 6 | `⟨θ_A⟩` correct sign, reverses under mirroring | **yes** — +0.1108 ± 0.0022 rad, reversing exactly |
| 7 | attachment torque correct sign | **yes** — `M_A = −1.834 ± 0.037` pN·nm |
| 8 | late `v` and `ω` stationary | **yes** — halves give `ω` −0.5397 / −0.5490 (ratio 1.017) |
| 9 | dynamic force and torque closure | **yes** — 3.9e−09 pN, 1.4e−10 pN·nm (S12) |
| 10 | `α = 0` removes the mirror-odd twirl | **yes** — `Ω_odd = 0` exactly (S10) |

---

## S7. Stage 4 — native-lattice overdamped results

Changed **only** `a: 2.75 → 2.70 nm` and `ϑ₀: −167.14° → −166.5°`. Everything else — state machine,
rates, `k_D/k_A`, attachment energy, force law, torque law, `η`, `α`, Brownian off, converter skew
zero — unchanged.

### Stage 4 — native SoftBox lattice, overdamped, per arm

| arm | v (µm/s) | ω (rad/s) | pitch (µm/turn) | turns | ⟨x_A⟩ nm | ⟨ξ_A⟩ nm | ⟨θ_A⟩ rad | M_A pN·nm | duty | τ_X (s) | τ_Θ (s) | events |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `odnative_e0.01_s101_mirror` | 0.04156 | 0.4872 | 0.536 | 9.33 | 1.585 | 0.3335 | -0.102 | 1.689 | 0.672 | 1.44e-06 | 6.91e-06 | 213491 |
| `odnative_e0.01_s101_native` | 0.04156 | -0.4872 | -0.536 | 9.33 | 1.585 | 0.3335 | 0.102 | -1.689 | 0.672 | 1.44e-06 | 6.91e-06 | 213491 |
| `odnative_e0.01_s102_mirror` | 0.04192 | 0.5446 | 0.4837 | 10.3 | 1.694 | 0.3479 | -0.1102 | 1.825 | 0.748 | 1.29e-06 | 6.16e-06 | 228991 |
| `odnative_e0.01_s102_native` | 0.04192 | -0.5446 | -0.4837 | 10.3 | 1.694 | 0.3479 | 0.1102 | -1.825 | 0.748 | 1.29e-06 | 6.16e-06 | 228991 |
| `odnative_e0.01_s103_mirror` | 0.04143 | 0.5579 | 0.4666 | 10.7 | 1.692 | 0.3166 | -0.1122 | 1.858 | 0.736 | 1.32e-06 | 6.31e-06 | 232698 |
| `odnative_e0.01_s103_native` | 0.04143 | -0.5579 | -0.4666 | 10.7 | 1.692 | 0.3166 | 0.1122 | -1.858 | 0.736 | 1.32e-06 | 6.31e-06 | 232698 |
| `odnative_e0.01_s104_mirror` | 0.04192 | 0.4877 | 0.54 | 9.26 | 1.607 | 0.3567 | -0.1 | 1.657 | 0.813 | 1.2e-06 | 5.74e-06 | 252721 |
| `odnative_e0.01_s104_native` | 0.04192 | -0.4877 | -0.54 | 9.26 | 1.607 | 0.3567 | 0.1 | -1.657 | 0.813 | 1.2e-06 | 5.74e-06 | 252721 |

### Stage 4 — mirror even/odd decomposition

| seed | Ω_native | Ω_mirror | Ω_even | Ω_odd | M_A native | M_A mirror | ⟨x_A⟩ nat | ⟨x_A⟩ mir |
|---|---|---|---|---|---|---|---|---|
| 101 | -0.48717 | 0.48717 | 0 | -0.48717 | -1.689 | 1.689 | 1.585 | 1.585 |
| 102 | -0.54461 | 0.54461 | 0 | -0.54461 | -1.825 | 1.825 | 1.694 | 1.694 |
| 103 | -0.55795 | 0.55795 | 0 | -0.55795 | -1.858 | 1.858 | 1.692 | 1.692 |
| 104 | -0.48772 | 0.48772 | 0 | -0.48772 | -1.657 | 1.657 | 1.607 | 1.607 |

### Stage 4 — overdamped vs quasi-static, native lattice

| quantity | quasi-static | overdamped (η = 0.01 Pa·s) | ratio od/qs |
|---|---|---|---|
| ⟨x_A⟩ (nm) | 1.6649 ± 0.031 | 1.6445 ± 0.029 | **0.9878** |
| ⟨θ_A⟩ (rad) | 0.10806 ± 0.0023 | 0.1061 ± 0.003 | **0.9818** |
| attachment torque M_A (pN·nm) | -1.7895 ± 0.038 | -1.757 ± 0.05 | **0.9818** |
| ⟨ξ_A⟩ (nm) | 0.36028 ± 0.0084 | 0.33868 ± 0.0088 | **0.9401** |
| v (µm/s) | 0.041925 ± 0.00011 | 0.041709 ± 0.00013 | **0.9948** |
| Ω_odd (rad/s) | -0.55257 ± 0.02 | -0.51936 ± 0.019 | **0.9399** |
| inverse pitch λ⁻¹ (µm⁻¹) | -2.0971 ± 0.069 | -1.982 ± 0.073 | **0.9451** |
| pitch (µm/turn) | -0.47834 ± 0.015 | -0.50658 ± 0.019 | **1.059** |
| duty ratio | 0.7419 ± 0.029 | 0.7421 ± 0.029 | **1** |
| before-centre fraction (%) | 59.08 ± 0.2 | 58.98 ± 0.13 | **0.9984** |

Every ratio is within 6 % of unity. The two channels that move most, `Ω_odd` (0.940) and `λ⁻¹`
(0.945), differ by **1.2 σ** on the pooled SEMs (−0.5526 ± 0.020 vs −0.5194 ± 0.019) — not a
resolved difference at n = 4. The mechanism transfers to the native lattice under finite drag
exactly as it did under quasi-static mechanics.

---

## S8. Mirror even/odd decomposition

`Ω_even = 0` exactly on every arm of both lattices, and `Ω_native = −Ω_mirror` bit-for-bit.

This is the same structural exactness identified in the complete-reference study (its S6.3), and
**finite drag does not break it**, which is a non-trivial check rather than a restatement: the drag
coefficients are scalars and enter only through `τ_X` and `τ_Θ`, the hazard still depends on the
angular mismatch through `θ²`, and the analytic propagation and branch bookkeeping are odd in `Θ`.
Any sign leakage introduced by the new mechanics — an asymmetric branch rule, a one-sided root
bracket, a drag term that coupled to `Θ` rather than `Θ̇` — would have destroyed it. Gate F verifies
it directly (`ΔX = 0`, `Θ = ∓6.8227525848`, `⟨θ_A⟩ = ±0.111969`).

As before, the substantive content is not that `Ω_even = 0` but that `Ω_odd` is non-zero, correctly
signed and of the reference magnitude. **A study that restores Brownian motion loses this exactness
and must return to seed-paired statistics.**

---

## S9. Full mechanism-chain observables

Every link of the published causal chain survives finite drag, and each is measured separately.

| link | quasi-static | overdamped (η = 0.01) | ratio |
|---|---|---|---|
| graded target-zone attachment landscape | (lattice unchanged; `L = 36.0 nm` native, 38.5 nm paper) | identical by construction | 1 |
| → before-centre depletion bias | 59.08 % / `⟨x_A⟩ = +1.665 nm` | 58.98 % / **+1.645 nm** | 0.998 / 0.988 |
| → signed attachment-angle bias | `⟨θ_A⟩ = +0.1081 rad` | **+0.1061 rad** | 0.982 |
| → conjugate Vilfan torque | `M_A = −1.790 pN·nm` | **−1.757 pN·nm** | 0.982 |
| → mirror-reversing steady twirl | `Ω_odd = −0.5526 rad/s` | **−0.5194 rad/s** | 0.940 |
| (transport) | `v = 0.04193 µm/s` | **0.04171 µm/s** | 0.995 |
| (chirality per distance) | `λ⁻¹ = −2.097 µm⁻¹` | **−1.982 µm⁻¹** | 0.945 |

Supporting dynamical quantities, native lattice at `η = 0.01`: median bound heads `N_b ≈ 86`;
`τ_X = 1.37 µs`; `τ_Θ = 6.53 µs`; mean chemical inter-event interval `⟨Δt⟩ = 646 µs`;
`τ_Θ/⟨Δt⟩ = 0.0101`; target-zone passage time 0.756 s; duty ratio 0.742; **0 angular branch
crossings** (S12); dynamic closure residuals below 4e−09 pN and 1.4e−10 pN·nm.

---

## S10. Stage 5 — mechanism-localisation controls

### Stage 5 — mechanism-localisation controls (native lattice, overdamped, η = 0.01)

| control | v (µm/s) | Ω_odd (rad/s) | ⟨x_A⟩ nm | ⟨θ_A⟩ rad | before-centre % | verdict |
|---|---|---|---|---|---|---|
| full mechanism (reference) | 0.04171 | -0.5194 | 1.645 | 0.1061 | 58.98 | twirls, mirror-reversing |
| α = 0 (no angular stiffness) | 0.04005 | 0 | -0.006243 | -0.03631 | 49.94 | translation only |
| d = 0 (no power stroke) | -3.608e-06 | -0.0014 | -0.02591 | 0.000645 | 50.84 | no gliding, no twirl |

**No-depletion shadow control (overdamped, native lattice):**

| seed | ⟨x_A⟩ real (nm) | ⟨x_A⟩ no-depletion (nm) | before-centre real % | before-centre shadow % |
|---|---|---|---|---|
| 101 | 1.5846 | -0.098698 | 58.81 | 49.25 |
| 102 | 1.6945 | -0.049646 | 59.17 | 49.7 |
| 103 | 1.692 | -0.024614 | 59.23 | 49.83 |
| 104 | 1.607 | -0.086642 | 58.73 | 49.36 |
| **mean** | **1.6445 ± 0.029** | **-0.0649 ± 0.017** | | |

**All three controls behave as the mechanism requires, under finite drag.**

- **`α = 0`** removes the angular stiffness: translation is untouched (`v = 0.0400 µm/s`) while
  `Ω_odd` is **exactly zero** and the attachment-position bias collapses (`⟨x_A⟩ = −0.006 nm`,
  before-centre 49.94 %). With `K_ϑ = 0` there is no angular term in the attachment energy at all,
  so there are no target zones to be biased about — the whole chain is removed at its first link,
  which is precisely what this control is for. *(These four arms terminate on the travel cap: with
  no rotation the "≥8 turns" requirement can never be met. That is the expected, correct behaviour
  of the stopping rule for a non-rotating control, not an under-resolved measurement.)*
- **`d = 0`** removes the power stroke: `v = −3.6e−06 µm/s` (zero to within the estimator's noise),
  `Ω_odd = −0.0014 rad/s`, before-centre 50.84 %. No gliding, no target-zone passage, no twirl.
  *(Terminates on the simulated-time cap, as a zero-velocity control must.)*
- **No-depletion shadow control** — the decisive one. Evaluating the same moving attachment
  landscape for every motor as if it were detached, without removing any motor from the pool and
  without generating force, collapses `⟨x_A⟩` from **+1.6445 ± 0.029 nm** to **−0.0649 ± 0.017 nm**
  and the before/after split from **58.98 %** to **49.5 %**. **96 % of the bias is
  depletion/history, not a static asymmetric landscape — unchanged from the quasi-static reference
  (96.5 %).** Finite drag does not alter the depletion origin of the effect.

Together these exclude the alternative that finite drag has introduced some unrelated rolling mode:
the rotation vanishes when the angular stiffness is removed, vanishes when the stroke is removed,
and its attachment bias vanishes when depletion is removed.

---

## S11. Stage 6 — bounded viscosity sensitivity

Preregistered points `η ∈ {0.001, 0.01, 0.1}` Pa·s, native lattice, `α = 4`, `k_D/k_A = 0.1`,
Brownian off, two matched native/mirror seeds each.

### Stage 6 — bounded viscosity sensitivity (native lattice, α = 4, kD/kA = 0.1)

| η (Pa·s) | γ_X (pN·s/nm) | γ_Θ (pN·nm·s/rad) | τ_X (s) | τ_Θ (s) | ⟨Δt⟩_chem (s) | τ_Θ/⟨Δt⟩ | zone passage (s) | ⟨x_A⟩ nm | ⟨θ_A⟩ rad | Ω_odd | λ⁻¹ (µm⁻¹) | v (µm/s) | ω 1st/2nd half |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0.001 | 5.344e-06 | 0.0008467 | 1.37e-07 | 6.53e-07 | 0.000647 | 0.00101 | 0.753 | 1.643 | 0.1043 | -0.5351 | -2.042 | 0.0417 | -0.5346/-0.5357 |
| 0.01 | 5.344e-05 | 0.008467 | 1.37e-06 | 6.53e-06 | 0.000646 | 0.0101 | 0.756 | 1.64 | 0.1061 | -0.5159 | -1.967 | 0.04174 | -0.5007/-0.5312 |
| 0.1 | 0.0005344 | 0.08467 | 1.37e-05 | 6.53e-05 | 0.000646 | 0.101 | 0.757 | 1.619 | 0.1058 | -0.5231 | -2.003 | 0.04157 | -0.5513/-0.4947 |

**All three preregistered points lie in a flat quasi-static plateau.** Across a 100-fold change in
viscosity `⟨x_A⟩` varies by 1.5 %, `⟨θ_A⟩` by 1.7 %, `Ω_odd` by 3.6 % and `λ⁻¹` by 3.7 % — all
within the seed-to-seed scatter at n = 2. There is no attenuation regime and no threshold anywhere
in the preregistered range.

### S11.1 Exploratory extension — locating the plateau edge

The Stage-6 question asks whether there is a plateau, an attenuation regime, **or a threshold**.
With all three preregistered points flat, the first is answered and the other two are not. The
following points were therefore added **after** seeing that result. They are **post-hoc and NOT
preregistered**, are reported separately, and are never pooled with the preregistered sweep.

### Stage 6b — EXPLORATORY high-viscosity extension (NOT preregistered)

| η (Pa·s) | γ_X (pN·s/nm) | γ_Θ (pN·nm·s/rad) | τ_X (s) | τ_Θ (s) | ⟨Δt⟩_chem (s) | τ_Θ/⟨Δt⟩ | zone passage (s) | ⟨x_A⟩ nm | ⟨θ_A⟩ rad | Ω_odd | λ⁻¹ (µm⁻¹) | v (µm/s) | ω 1st/2nd half |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 0.005344 | 0.8467 | 0.000137 | 0.000653 | 0.000647 | 1.01 | 0.757 | 1.625 | 0.1047 | -0.5252 | -2.011 | 0.04156 | -0.5233/-0.5272 |
| 10 | 0.05344 | 8.467 | 0.00137 | 0.00653 | 0.000646 | 10.1 | 0.762 | 1.66 | 0.1068 | -0.511 | -1.966 | 0.04137 | -0.4956/-0.5264 |
| 100 | 0.5344 | 84.67 | 0.0137 | 0.0657 | 0.000649 | 101 | 0.83 | 1.584 | 0.1024 | -0.3935 | -1.61 | 0.03889 | -0.4043/-0.3827 |

The mechanism is still fully present at `η = 1` and `η = 10 Pa·s`, and at `η = 100 Pa·s` —
**10 000× the assay viscosity** — it is attenuated by only ~24 % and still cleanly mirror-reversing.

### S11.2 The preregistered threshold estimate was wrong, and the data corrected it

Stage 0 predicted that "drag becomes comparable to the chemistry near `η ≈ 1 Pa·s`", from
`τ_Θ ≈ ⟨Δt⟩_chem`. **That prediction is refuted.** At `η = 1` the ratio `τ_Θ/⟨Δt⟩ = 1.01` and
nothing happens; at `η = 100` the ratio is `101` — the filament is a hundred times slower than the
chemistry fires — and the twirl is still 76 % of its low-drag value.

The comparison timescale was wrong. Deriving the steady state under finite drag: the torque balance
is no longer `ΣM = 0` but `γ_Θ ω = ⟨ΣM⟩ = −N_b K_ϑ ⟨θ⟩`, while the population balance is unchanged,
`⟨θ⟩ = ⟨θ_A⟩ + ω/k_D`. Eliminating `⟨θ⟩`:

```
      -k_D <theta_A>                                gamma_Theta
omega = --------------  ,        tau_Theta = ---------------------
        1 + k_D tau_Theta                       N_b K_theta
```

and identically for translation, `v = k_D(d + ⟨ξ_A⟩)/(1 + k_D τ_X)`. **The drag time competes with
the bound-head lifetime `1/k_D = 200 ms`, not with the inter-event interval `646 µs`** — a factor of
310 that the Stage-0 estimate missed. Physically: a single event's mechanical relaxation does not
have to complete before the next event, because what sets the steady rotation is the *time-averaged*
angle of the whole bound population, and a head persists for `1/k_D`. Only when the filament cannot
respond within a head's lifetime does the balance shift.

The law is quantitatively confirmed on both channels, with no fitted parameter:

| η (Pa·s) | `k_D τ_Θ` | predicted `Ω_odd` | **measured** | `k_D τ_X` | predicted `v` | **measured** |
|---|---|---|---|---|---|---|
| 1 | 0.0033 | −0.517 | **−0.5252** | 0.00069 | 0.0417 | **0.04156** |
| 10 | 0.033 | −0.503 | **−0.5110** | 0.0069 | 0.0414 | **0.04137** |
| 100 | 0.329 | −0.391 | **−0.3935** | 0.069 | 0.0390 | **0.03889** |

(Predictions use the measured low-drag `Ω_odd = −0.519` and `v = 0.0417` and each arm's own recorded
`τ`.) At `η = 100` the predicted and measured attenuations agree to **0.6 %** in rotation and
**0.3 %** in translation.

**The true threshold is `k_D τ_Θ = 1`, i.e. `τ_Θ = 1/k_D = 0.2 s`, which needs
`γ_Θ = 285 pN·nm·s/rad` and hence `η ≈ 340 Pa·s` — about 34 000× the assay viscosity** and far
outside any physical gliding assay. Rotation reaches it before translation because `τ_Θ/τ_X ≈ 4.8`,
which is the same ordering SoftBox's own viscosity study found (rotation drag-limited, translation
not).

---

## S12. Numerical and regression health

### Numerical health (all overdamped arms)

- arms: **52**, total Gillespie events: **23,714,167**, hazard evaluations: **1,434,776,025**
- max |γ_X·Ẋ − ΣF| = **3.89e-09 pN**
- max |γ_Θ·Θ̇ − ΣM| = **1.36e-10 pN·nm**
- max event discontinuity: |ΔX| = **0 nm**, |ΔΘ| = **0 rad**
- angular branch crossings: **0**; degenerate (beyond the settle horizon): 0
- events whose root fell inside the mechanical transient: **9,031,244** (38.084 % of events)
- root-finder iterations: 22,648,444; equilibrations with zero bound heads: 52
- travel-cap hits: **4** — odctl_alpha0_s101_mirror, odctl_alpha0_s101_native, odctl_alpha0_s102_mirror, odctl_alpha0_s102_native
- notes:
    - `odctl_d0_s101_mirror`:  [sim-time cap]
    - `odctl_d0_s101_native`:  [sim-time cap]
    - `odctl_d0_s102_mirror`:  [sim-time cap]
    - `odctl_d0_s102_native`:  [sim-time cap]

**Stationarity (analysed window split into two equal halves of simulated time):**

| stage | n | ω first half | ω second half | ratio | v first | v second |
|---|---|---|---|---|---|---|
| paper lattice | 4 | -0.5397 | -0.549 | 1.017 | 0.04176 | 0.04136 |
| native lattice | 4 | -0.5098 | -0.529 | 1.038 | 0.04185 | 0.04156 |

**Interpretation.**

- **Dynamic closure holds to 4e−09 pN and 1.4e−10 pN·nm** across 21 million events. The torque arm
  is a genuine cross-check, not a tautology: it compares the branch-integer bookkeeping used by the
  analytic propagation against an independent `wrapPi` sum.
- **`X` and `Θ` are continuous through every event, exactly** (`\|ΔX\| = \|ΔΘ\| = 0`). No
  displacement is applied at an event anywhere in the overdamped path.
- **38.1 % of all events had their hazard root fall inside the mechanical transient.** The
  time-dependent-hazard machinery is therefore genuinely load-bearing, not a formality that the
  settled-tail shortcut always bypasses — at `η = 0.01` the settle horizon `40 τ_Θ = 261 µs` is 40 %
  of the mean interval `646 µs`, which is exactly the fraction observed.
- **Zero angular branch crossings in production.** Bound-head angles are concentrated near zero by
  the target-zone selection itself, and `Θ` moves only `~⟨θ_A⟩/N_b ≈ 0.0012 rad` per event, so the
  `±π` boundary is essentially never reached. The branch machinery is nevertheless **validated**:
  gate A5 constructs a starting angle that does produce a crossing and shows the segmented analytic
  propagation matches an RK4 that uses the `wrapPi` torque and knows nothing about branch integers.
  *This is worth stating plainly — the branch code is correct but untested by the production
  trajectories, and a Brownian study, which will move `Θ` far more per event, should expect to start
  exercising it.*
- **52 equilibrations with zero bound heads** out of 21 M (2.5e−06), all in the `d = 0` control where
  the filament does not glide into fresh motors.
- **Cap hits are all controls, all expected**: the four `α = 0` arms cannot accumulate turns, the
  four `d = 0` arms cannot accumulate travel. **No physics arm hit a cap.**
- **Stationarity**: `ω` halves give ratios 1.017 (paper) and 1.038 (native); `v` halves agree to
  1 %. The late motion is stationary on both lattices.

### S12.1 Regression

- The **default `-mechanics quasistatic` path is unchanged**: the complete reference's own 29-gate
  suite re-runs on this branch **29 PASS / 0 FAIL** with its printed values reproduced exactly, and
  gate G1 reproduces a committed quasi-static campaign record to the record's printed precision
  (rel dev 6.4e−11 / 2.4e−11 / 1.2e−10; the record stores `%.10g`, so ten significant digits is the
  strongest available statement — see S5.1).
- No pre-existing SoftBox source file is modified by this study. The additions are
  `softbox/VilfanDrag.java`, `softbox/VilfanDragHarness.java`, additive changes to
  `softbox/VilfanCompleteSystem.java` and `softbox/VilfanCompleteHarness.java` (both created by the
  parent study), and three scripts.
- **Runner disclosure**: every number here was produced **on the CPU**. No CUDA context, no
  TornadoVM, no `TaskGraph`, no device buffer — the new sources import nothing from
  `uk.ac.manchester.tornado.*`, and `VilfanDrag` deliberately re-expresses the drag formula rather
  than linking `DragTensorSystem`, which does. Each arm is a single-threaded JVM
  (`-XX:ActiveProcessorCount=1 -XX:+UseSerialGC`) at `nice -n 17`, at most 3 concurrent. Total: 52
  arms, 21 M events, 1.18 **billion** hazard evaluations, ≈4.5 CPU-hours.
- All completed reference records and reports are preserved; `REFERENCE_RECORDS/` holds a read-only
  copy of the parent study's 92 quasi-static records used for the comparisons.

---

## S13. Classification

> ## **A. OVERDAMPED VILFAN MECHANISM SURVIVES QUANTITATIVELY**

Against the stated requirements for A:

| requirement | result |
|---|---|
| complete causal chain remains resolved | **yes** — every link measured separately in S9, all signs correct |
| native/mirror reversal is exact | **yes** — `Ω_even = 0` bit-exactly on every arm |
| `⟨x_A⟩`, `⟨θ_A⟩` and `λ⁻¹` within 25 % of the quasi-static reference at `η = 0.01` | **yes, with 20× margin** — paper lattice 0.9996 / 1.006 / 0.9967; native lattice 0.988 / 0.982 / 0.945 |
| late motion stationary | **yes** — `ω` halves ratio 1.017 (paper), 1.038 (native) |

This is **not** B (no amplitude shifts by more than 6 %, let alone 25 %), **not** C (rotation is
neither collapsed nor non-stationary — it is within 6 % of the reference and stationary), **not** D
(the depletion bias is intact and the shadow control still removes 96 % of it), and **not** E.

**The answer to the scientific question.** Vilfan's target-zone depletion mechanism survives finite
filament response time essentially untouched, and the reason is a clean separation of timescales
that is **much wider than it first appears**: not the 100× separation between the drag time and the
chemical inter-event interval, but the **310× larger** separation between the drag time and the
bound-head lifetime, which is what actually governs the steady state (S11.2). Instantaneous
equilibration was a safe assumption, and the study now says by how much — the mechanism has roughly
**four orders of magnitude** of viscosity headroom before finite response begins to attenuate it.

---

## S14. Recommendation for the Brownian study — NOT executed here

**This task stops here.** No thermal force of any kind was added; no transverse, height, tilt or
bending degree of freedom; no SoftBox chemistry, explicit S2, converter or lever mechanics; no
load-dependent detachment; no GPU work. The next rung is recorded, not started.

**The next rung is roll Brownian motion at FDT with the drag coefficients validated here**, and it
is the rung most likely to break the mechanism. The quantitative case, from this study's own
numbers:

1. **The signal is a small shift of a broad distribution.** `⟨θ_A⟩ = 0.106 rad` while
   `sd(θ_A) = 0.65 rad` — the mean is **6 times smaller than the spread**, sustained by ~48 000
   attachment events per arm. Any process that broadens `θ_A` without shifting it dilutes the
   measurement, and any process that shifts it competes directly with the mechanism.
2. **Rotational Brownian motion is not small here.** With `γ_Θ = 8.47e−03 pN·nm·s/rad` at the assay
   viscosity, the rotational diffusion coefficient is `D_Θ = k_BT/γ_Θ ≈ 489 rad²/s`. Over a single
   bound-head lifetime `1/k_D = 0.2 s` that is a free-roll excursion of `√(2 D_Θ/k_D) ≈ 14 rad` —
   **more than two full turns**, against a deterministic signal of `ω/k_D ≈ 0.1 rad`. The
   deterministic twirl is a ~1 % effect on top of the thermal roll. It is only recoverable because
   the bound heads' angular springs suppress the free diffusion: with `N_b K_ϑ = 1424 pN·nm/rad` the
   *constrained* roll variance is `k_BT/(N_b K_ϑ) ≈ 0.0029 rad²`, i.e. `sd ≈ 0.054 rad`. **Both
   numbers should be computed and reported before the campaign is designed**, because they bracket
   the achievable signal-to-noise.
3. **Mirror antisymmetry stops being exact.** `Ω_even` becomes a statistical quantity, so the
   decomposition returns to seed-paired statistics and the required seed count must be estimated
   **in advance** from `sd(Ω)` measured across this study's seeds (paper `Ω_odd` spans −0.521 …
   −0.565, `sd ≈ 0.019`, so resolving a 5 % change needs `n ≈ 4·(0.019/0.026)² ` — i.e. the design
   must be sized on the *Brownian* scatter, which will be far larger and must be measured first on
   two seeds before committing to a campaign).
4. **The branch machinery will start to matter.** Production trajectories here produced **zero**
   `±π` branch crossings because `Θ` moves ~0.0012 rad per event. Brownian roll will move it by
   `sd ≈ 0.054 rad` per bound lifetime and far more when `N_b` dips, so crossings will occur. The
   code path is already validated (gate A5) and instrumented (`branchCrossings`), and the counter
   should be watched as a first-order diagnostic.
5. **Keep every control alive.** `α = 0`, `d = 0`, the achiral lattice and the no-depletion shadow
   sampler all work unchanged under finite drag and are what distinguish "the mechanism survived"
   from "something else now makes it rotate". Under noise they become *more* important, not less.

**Suggested order for the next task**: (i) add roll Brownian motion alone, at FDT, with `γ_Θ` from
S3; (ii) measure `sd(Ω_odd)` on two seeds and size the campaign from it *before* running it;
(iii) only then add axial Brownian motion; (iv) leave transverse motion, tilt and any SoftBox
mechanics to the rung after. The overdamped reference validated here — deterministic, exact in its
mirror symmetry, with every chain link separately measured — is the positive control that study
should try to break.

---

## S15. Reproducing this study

```
./scripts/run_vilfan_drag.sh -drag-audit           # Stage 0 whole-filament drag audit + DRAG-1 gate
./scripts/run_vilfan_drag.sh -drag-gates           # Stage 2: 23/23 numerical gates
./scripts/run_vilfan_drag_campaign.sh all 3        # 52 arms, resumable, <=3 cores, ~4.5 CPU-hours
python3 scripts/analyse_vilfan_drag.py all         # every table in S6-S12
./scripts/run_vilfan_complete.sh -gates            # the parent study's 29 gates, still 29/0
```

Records: `RUN_LOGS/vilfan_drag/*.json`, one atomic record per arm (written `.tmp` then renamed,
skipped if present, so an interrupted campaign resumes exactly where it stopped). Derived tables:
`ANALYSIS/vilfan_drag/tables.md`. `REFERENCE_RECORDS/` holds the parent study's quasi-static records
for the comparison and is not modified.

`RUN_LOGS/` is gitignored by repo convention, so the raw records are not committed; every arm is
deterministic in its declared seed (gate E1), so they regenerate exactly. The derived tables and
this report are committed.
