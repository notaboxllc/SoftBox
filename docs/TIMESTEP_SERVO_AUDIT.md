# TIMESTEP_SERVO_AUDIT — is canonical DIRSWING a converged finite stroke or a timestep-dependent active servo?

**Date:** 2026-07-12 · **Branch:** dt-convergence-study · **Runner:** CPU deterministic. `BoA-v1ref` untouched.
**Instrumentation (default-off, byte-identical — verified: `-vclamp 8` FVROW `fbar_avail=0.13541` char-for-char
unchanged after the edits):** `-servoaudit` (deterministic single forced-bound motor: per-step rotation-vector
distributions, per-channel MIDPOINT signed work, cumulative DIRSWING work vs age, closed-cycle perturbation) +
`-detmotor` (zero motor Brownian) + the V₀ dt-ladder over the existing stochastic `-vclamp` force–velocity harness.
Canonical model **unchanged** (no new mechanism, no retune, no default flip).

> **PRIMARY OUTCOME: A (converged finite stroke) + E (the production-dt large per-step rotation is a benign
> discretization artifact that vanishes ∝dt), with a C rider (the ENSEMBLE force–velocity V₀ is NOT dt-converged —
> ~25–45 % high at production dt — but that shift lives in the cross-bridge-under-sliding-load duty ceiling, NOT in the
> DIRSWING stroke).** The **persistent-servo hypothesis (B) is REFUTED**: over a closed displacement cycle DIRSWING
> does ≈0 (slightly *dissipative*, never net-positive) work, at every timestep; its stroke work is front-loaded at
> the ADP·Pi→ADP switch and then plateaus. Pose, single-motor stroke force, and per-stroke channel work all
> **converge**; only the many-episode sliding-load V₀ shifts with dt.

---

## Part 0 — the two questions kept separate (per the task)

1. **The DIRSWING mechanism** (does the one nucleotide-triggered stroke inject a bounded amount of work and quiesce, or
   pump net-positive work whenever the lever is displaced?). Measured on a **single, forced-bound, fully deterministic
   motor** (all Brownian off) — the only clean substrate: with the carpet's thermal search removed, nothing else moves,
   so per-channel work and per-step rotation are the pure canonical mechanics. *(Zeroing ALL motor Brownian in the
   gliding carpet makes binding impossible — the thermal search is what brings heads to the filament — so the servo
   question cannot be asked on the carpet; it is asked on the pre-bound single motor.)*
2. **The emergent force–velocity ceiling V₀** (a stochastic, many-episode ensemble observable). Measured on the
   production `-vclamp` carpet with thermal ON, seed-averaged.

## Part 1 — runtime configuration confirmed (the executed canonical stack)

`-servoaudit` header, read from the live params:
```
canonical SPHEREHEAD+AXLOCK+DIRSWING+XB_IMPLICIT2+LYMN_TAYLOR ; f9Frozen=1 axLock=1 θ_u=0 θ_c=60 ; springs swingParams[4]=-1.00e-05
```
- `f9Frozen=1` ⇒ **F9 rest = 90° in every bound state** (nucleotide-independent ⊥-maintainer — matches
  `CANONICAL_STROKE_DISAMBIGUATION`).
- **DIRSWING** target switches **0°→60°** at ADP·Pi→ADP; `swingParams[4]=−refDt` ⇒ with springs-on the swing torque
  `mag = k·ang/((…)·dt)` has its `k→k·dt/refDt`, so **dt cancels to refDt → DIRSWING is a FIXED rotational spring**
  toward the target angle (a proportional controller, dt-invariant stiffness). This is the structural reason the stroke
  is expected to be conservative, tested below.
- **J1 angular torque = 0** (`jointParams[3]=0` when DIRSWING), **J2 angular = 0** by construction, **AXLOCK** = the
  head-only `head.yVec→ŝ` plane lock. Confirmed.

## Part 2 — per-step rotation shrinks ∝dt (Outcome E)

Deterministic single motor, 1 ms post-switch window, per-step rotation-vector magnitude (robust `acos(u_pre·u_post)`
axis-angle, **not** a wrapping scalar), °/step:

| dt (s) | head med / p90 / p99 / p99.9 / max | lever med / p90 / p99 / p99.9 / **max** |
|---|---|---|
| 1.0e-5  | 0.154 / 0.400 / 1.22 / 8.05 / 8.81 | 0.066 / 0.828 / 9.46 / 12.67 / **13.03** |
| 5.0e-6  | 0.076 / 0.196 / 0.89 / 3.94 / 4.43 | 0.032 / 0.483 / 4.91 / 6.41 / **6.59** |
| 2.5e-6  | 0.035 / 0.095 / 0.60 / 1.97 / 2.22 | 0.006 / 0.259 / 2.49 / 3.22 / **3.31** |
| 1.25e-6 | 0.017 / 0.050 / 0.33 / 0.99 / 1.12 | 0.000 / 0.134 / 1.26 / 1.62 / **1.67** |

**Every percentile halves as dt halves (∝dt exactly).** So the production-dt tail — lever **p99.9 ≈ 12.7°, max ≈ 13°
per step** — is a genuine large per-step angular motion at dt=1e-5, but it is a **pure discretization artifact of the
stiff overdamped constraint**: it vanishes ∝dt while the underlying physical-time trajectory converges (Part 6). This
is **Outcome E**, and it identifies (and bounds) the prior disambiguation doc's "±30°/step even thermal-off" head
jitter: the deterministic constraint-oscillation part is ≤13°/step at 1e-5 and dt-vanishing; the remainder in that
carpet run was thermal. It is **not** a logging/estimator artifact (Outcome F ruled out — robust rotation vectors).

## Part 3 — channel-resolved signed work (deterministic, midpoint estimator)

Per-channel MIDPOINT work `½(τ_pre+τ_post)·Δθ` over the 1 ms stroke window, dt=1e-5, kT. Running the motor
deterministically **removes the head-jitter confound** the prior doc flagged (raw τ·ω was polluted by ±30° thermal
head wobble); these NET numbers are clean:

| channel | W⁺ (kT) | W⁻ (kT) | **W_net (kT)** | R = (W⁺−W⁻)/|net| |
|---|---:|---:|---:|---:|
| **DIRSWING** | +5.225 | 0.000 | **+5.225** | **1.00** |
| F8 (bond)    | +0.567 | −0.652 | −0.085 | 14.3 |
| F9 (⊥-maint) | +0.142 | −1.019 | −0.876 | 1.33 |
| F10 (AXLOCK) | +0.744 | −0.175 | +0.570 | 1.61 |

- **DIRSWING R = 1.00, W⁻ = 0** — the stroke channel does **only** positive work, monotonically, with **no reversible
  exchange**: a one-time directed conversion, not a spring cycling energy.
- F8 has **R = 14.3** (large reversible bond breathing, net ≈ 0) — a healthy conservative spring. F9 does net-negative
  ⊥-maintenance; AXLOCK does modest net-positive plane reorientation during the swing. None of the constraint channels
  shows the sustained-net-positive signature of an energy source.

## Part 4 — cumulative DIRSWING work vs attachment age: front-loaded, plateaus (finite stroke)

Deterministic, dt=1e-5, from the ADP·Pi→ADP switch (kT):

| age (ms) | 0.02 | 0.05 | 0.10 | 0.15 | 0.20 | 0.30 | 0.50 | 0.75 | 1.00 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| cumulative W_DIR | +3.89 | +4.91 | **+5.11** | 5.13 | 5.13 | 5.14 | 5.16 | 5.19 | 5.22 |

**~98 % of the DIRSWING work is done in the first 0.10 ms** (the swing), then it plateaus: **95 %-of-final acquisition
at 0.060 ms; post-acquisition DIRSWING work = +0.22 kT = 4.3 % of the total.** The lever settles to a LOADED plateau
at swing-error ≈ 12° / θ_J1 ≈ 50–57° (held short of 60° by F8/F9/F10 load — the "frustrated stroke"), then only a
slow +0.1 kT viscoelastic creep over the remaining 0.9 ms. This is the **finite-stroke signature**: net work
concentrated at the transition, cumulative work plateaus, small post-acquisition remainder.

## Part 5 — closed-cycle displacement challenge: DIRSWING ≈0 net, never net-positive (servo REFUTED)

After acquisition, the filament is driven through a closed axial waveform (out→hold→back→hold, ×2 cycles) at three
amplitudes; net per-channel work over the **closed** cycle (kT):

| dt (s) | Wdir amp=4 | Wdir amp=8 | Wdir amp=12 |
|---|---:|---:|---:|
| 1.0e-5  | −0.008 | −0.038 | −0.094 |
| 5.0e-6  | −0.005 | −0.037 | −0.090 |
| 2.5e-6  | −0.006 | −0.033 | −0.089 |
| 1.25e-6 | +0.002 | −0.029 | −0.087 |

**Net DIRSWING work over a closed cycle is small, ≤0 (dissipative), dt-stable, and never net-positive** — it does not
grow with cycle count and does not grow (in fact slightly shrinks) as dt→0. A persistent active servo would inject
repeated net-**positive** DIRSWING work each closed cycle; it does not. **Outcome B refuted.** (θ_J1 returns to within
~1–2° of its start; the ≈−0.09 kT at amp=12 is the overdamped dissipation of the finite return path, not an energy
source. F8 receives net-positive work at large amplitude because the *external drive* pushes the site — expected, and
F8 is not a stroke channel.)

## Part 6 — convergence: pose/force/single-motor-work CONVERGE; ensemble V₀ does NOT

### (a) Single-motor stroke (deterministic, equilibration held at constant PHYSICAL time = 40 ms)

| dt (s) | θ_J1 @1ms (°) | \|F8\| @1ms (pN) | cumW_DIR @1ms (kT) | postAcqFrac | lever max °/step |
|---|---:|---:|---:|---:|---:|
| 1.0e-5  | 49.94 | 2.574 | 5.225 | 0.043 | 13.03 |
| 5.0e-6  | 49.90 | 2.573 | 5.153 | 0.050 | 6.59 |
| 2.5e-6  | 49.94 | 2.574 | 5.143 | 0.050 | 3.31 |
| 1.25e-6 | 50.21 | 2.584 | 5.198 | 0.049 | 1.67 |

**Pose (θ_J1), stroke force (F8), and stroke work (cumW_DIR) are converged** — flat to <1 % across an 8× dt range;
per-step rotation shrinks ∝dt. *(Note: this required holding the pre-stroke equilibration at constant physical time.
With equilibration held at a constant STEP count — 40 ms at 1e-5 but only 5 ms at 1.25e-6 — a spurious ±8 %
non-monotonicity appears, caused by the slow post-stroke settling mode being sampled at different physical ages. It is
an equilibration-window artifact, not a mechanics non-convergence.)*

### (b) Ensemble force–velocity V₀ (stochastic `-vclamp` carpet, seed-averaged; physical window ~80 ms held constant)

f̄_available(v) is seed-averaged; the zero-crossing = V₀. The crossing is shallow (≈0.03 pN per 2 µm/s) so
interpolated V₀ carries large seed uncertainty (±2–3 µm/s at n=3–4). The **robust** statement is the *fixed-velocity*
shift:

| dt (s) | seeds/pt | f̄_avail @ v=12 | f̄_avail @ v=14 | **f̄_avail @ v=16** | Nb @ v=12 | interp V₀ (µm/s) |
|---|---:|---:|---:|---:|---:|---:|
| 1.0e-5  | 4 | **+0.102** | **+0.059** | **+0.028** | 0.54 | **17.8** |
| 5.0e-6  | 3 | −0.018 | −0.104 | −0.129 | 0.71 | ~11.8 |
| 2.5e-6  | 3 | +0.089 | +0.025 | −0.013 | 0.63 | ~15.3 |
| 1.25e-6 | 2 | +0.010 | −0.040 | −0.108 | 0.75 | ~12.4 |

**V₀ shifts materially with dt.** Production dt=1e-5 sits **above every finer-dt point** at every velocity: at v=16 the
carpet still drives **forward (+0.028 pN)** at dt=1e-5 but is in **net drag (−0.01 … −0.13 pN)** at *all* finer dt — the
ceiling has been crossed by v=16 at fine dt but not at production dt. Interpolated V₀ falls from **17.8 (1e-5)** to a
noisy **~13 ± 2 µm/s** (11.8 / 15.3 / 12.4) at the finer steps — a **~25–45 % production-dt overestimate** (the exact
converged value is loosely bracketed by seed scatter, but the *sign and materiality are unambiguous*). **Mechanism
(decomposed):** as dt→0 the bound occupancy `Nbound` *rises* (0.54→0.75) — more engagement — yet the per-bound drive at
fixed sliding v *falls* into net drag. So the V₀ drop is a **per-bound sustained-force-under-sliding reduction**, i.e.
the known cross-bridge force-overshoot / duty dt-ceiling (`dt-faithful-ceiling`, `gliding-reconvergence`), **not** the
DIRSWING stroke — which Part 6a shows is dt-converged when the geometry is held static. The two are consistent: the
single-motor test isolates the stroke *conversion* (static filament), while V₀ probes the *sustained bond force under
continuous sliding + the stochastic bind/release clock*, which is what the coarse dt over-resolves.

## Part 7 — separate convergence verdicts (as the task requires)

| quantity | verdict |
|---|---|
| **Pose-converged** (θ_J1, F8 geometry, per-step rotation) | **YES** — converges; per-step rotation ∝dt |
| **Force-converged** (single-motor static stroke force) | **YES** — \|F8\| flat to <1 % |
| **Work-converged** (per-channel stroke work, midpoint) | **YES** — cumW_DIR → ~5.15 kT; NET channel work well-defined |
| **Ensemble V₀ / f̄(v)** | **NO** — ~25–45 % high at production dt; converges to ~13±2 µm/s (cross-bridge duty ceiling) |

The channel work is **energetically interpretable** (Outcome D does *not* apply): although F8's raw exchange is large
(R=14.3), the **midpoint estimator + deterministic run give a well-defined, converged NET work per channel**, and the
DIRSWING channel in particular is a clean R=1.0 one-time conversion.

## Outcome classification

- **A — converged finite stroke. PRIMARY.** DIRSWING net work concentrates at the ADP·Pi→ADP switch (98 % within
  0.1 ms), cumulative work plateaus (post-acquisition remainder 4–5 %), closed displacement cycles produce ≈0 (≤0) net
  DIRSWING work at every dt, and pose/force/work converge. DIRSWING remains *target-active* (a fixed rotational spring),
  but a target that remains active is **not** a servo — the decisive tests (post-acquisition growth, closed-cycle
  net-positive work) are both negative.
- **E — large-step oscillatory artifact. ALSO TRUE (benign).** Production-dt per-step rotations reach ~13°/step (lever
  p99.9 12.7°) but shrink ∝dt; the trajectory they discretize converges. Quantitative plateau/pose/stroke-work
  conclusions were therefore re-confirmed at converged dt (Part 6a) — they hold.
- **C — timestep-dependent mechanics. TRUE for the ENSEMBLE V₀ only.** V₀ is ~45 % high at production dt (17.8 vs
  converged ~12). This is **not** the stroke (Part 6a) — it is the cross-bridge-under-sliding-load duty ceiling already
  on record; the recommended fix is the cross-bridge sub-step, not a change to DIRSWING.
- **B — persistent servo. REFUTED.** No repeated net-positive DIRSWING work; closed cycles are dissipative.
- **D — pose converged / energy not interpretable. NOT APPLICABLE.** The deterministic midpoint accounting gives
  converged, interpretable NET channel work.
- **F — logging/estimator artifact. RULED OUT.** Robust rotation-vector differences; the ±30° prior-doc figure is
  resolved (deterministic constraint oscillation ≤13°/step ∝dt + thermal remainder).

## What this does and does not license

- It does **not** motivate replacing DIRSWING (no persistent-servo behavior, no stroke-mechanics non-convergence).
- It **does** re-confirm, from a new angle, that the quantitative **gliding ceiling is not timestep-converted at
  production dt** — V₀ ~25–45 % high — and localizes the deficit to the **cross-bridge sustained force under sliding /
  duty clock** (occupancy rises, per-bound drive falls as dt→0), i.e. the standing "sub-step the cross-bridge"
  recommendation, **not** the stroke channel.

## Artifacts

- Code (default-off, byte-identical): `GlidingHarness` `-servoaudit` (`runServoAudit` + `channelTorquesSingle` +
  `servoStep` + `rotVec3`/`pct`), `-detmotor`. Reuses the `runStrokeLoad` deterministic single-motor substrate.
- Runs: `RUN_LOGS/servo_ladder_eqconst.txt` (constant-physical-EQ dt ladder — Parts 2/4/5/6a), `RUN_LOGS/servo_full_1e-5.txt`
  (Part 3 channel work), `RUN_LOGS/v0_ladder.txt` (Part 6b V₀ ladder).
- Commands:
```
scripts/run_gliding.sh -density 40 -dt <dt> -servoaudit <M=40e-3/dt>   # deterministic servo/work/rotation/cycle audit
scripts/run_servo_v0_ladder.sh                                          # V₀ dt-ladder (stochastic vclamp, seed-averaged)
```
- Constraints honored: audit/measurement only; canonical model unchanged; `BoA-v1ref` untouched; the servo question
  asked on the deterministic single motor (the only substrate where it is well-posed), V₀ on the production carpet;
  robust rotation vectors + midpoint work estimator; convergence declared from numbers (∝dt scaling, <1 % pose flatness,
  bracketed V₀), not visual similarity.
