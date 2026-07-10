# ROLL_SPRING_PROTOTYPE — the inter-segment torsional-roll spring (physical twist), risk-first

**Azimuthal-binding Increment 1 (2026-07-09).** The roll spring + thermostat built in ISOLATION (no binding,
no off-axis bond, no motors, no turnover) to answer the one route-deciding question before anything is built
on the frame. **New files only** (`RollSpringSystem`, `RollSpringHarness`, `scripts/run_rollspring.sh`) — every
existing file, and thus canonical/production, is byte-identical by construction (verified: no existing file
touched; the FDT/gliding/contractile paths are unchanged).

## THE QUESTION → THE PLAIN ANSWER

> Is there a torsional-roll stiffness that makes a multi-segment filament hold a COHERENT, visible helical
> twist AND stay numerically STABLE at production dt = 1e-5?

**YES — ROUTE OUTCOME (A). The physical route is VIABLE.** A 64-segment filament holds a coherent helical
twist (per-joint std **±2.5°** about the 72° rest at the default thermostat) that is **stable over 200 000
steps (= 2.0 s simulated) with no blow-up**, **bit-identical CPU≡GPU** (max Δ 1.76e-5°). The spring is what
creates the coherence — with it OFF the frames scramble to std 93° (outcome C). ⇒ **proceed to Increment 2
(off-axis bond).**

## The spring form — dt-robust fraction-per-step, NOT raw Hookean (the load-bearing choice)

**Rest twist (geometry + handedness).** Actin 13/6 genetic helix, per-monomer azimuthal advance magnitude
166.5° (v1 `helixAngInc = π/13.333` ⇒ advance = π − helixAngInc). Handedness **derived fresh** — actin is a
LEFT-handed genetic helix ⇒ `twistPerMon = −166.5°` about the pointed→barbed axis (v1's rendering screw sign
is non-authoritative, not lifted). Per-joint rest = `stdSegLength · twistPerMon`, WRAPPED to (−π,π] =
**+72.0°** (the coarse frame twist; see the aliasing flag below). The relaxed filament is physically twisted —
the frames trace the helix.

**Torque — the fraction-per-step (damping-limited) family**, the same dt-robust trick as the chain torsion
(`filTorqSpringActive=0` branch), `MyoFilLink.alignYVecTorque`, and the PAIRS `moveC`:

```
Tmag = f · e / ((1/bRGx_owner + 1/bRGx_other) · dt)     e = wrap(φ_other − rest)   [about u_owner]
Δroll per step = Tmag/bRGx · dt = f·e/2  (equal drags)  ⇒ fraction-per-step, α = f, STABLE for f<2.
```

This is **the** design decision. The roll DOF has tiny rotational drag (`bRGx = 4πη·R²·L ≈ 1.4e-24
N·m·s/rad`), so a **raw Hookean** `T=k·e` has `dt_crit ∝ γ_roll/k` — `α = k·dt/γ_roll` and the fraction form
sidesteps it (`α = f`, independent of the tiny γ). Race-free: each joint is computed from both segments'
perspectives; the OWNER (lower slot) defines the canonical frame, owner writes `+Tmag·u_owner` / other writes
`−Tmag·u_owner` (exactly equal-opposite, roll angular momentum conserved), each writes only its own
`torqueSum` (+=), which the integrator projects onto u → the `bwx` roll channel (Build-Read verdict B). The
`ChainBendingForceSystem` two-explicit-block neighbour pattern (a free-end `continue` in an inner loop
mis-lowers on PTX — found and fixed during bring-up).

**Thermostat (`-rolldamp`).** A separate kernel attenuates the ‖u (roll) component of the Brownian torque
(`randTorque` x-plane) by `rolldamp`; y/z bending kicks untouched. `rolldamp=1.0` ⇒ ×1.0f ⇒ byte-identical;
`0.1` = the default. It cools the low-drag roll mode (stability margin) and quiets roll so the twist is
observable.

## Results (`./scripts/run_rollspring.sh`, 64-seg filament, dt=1e-5, roll kicked on ALL segments = the hard test)

**Coherence + long-run stability (headline, f=0.5, rolldamp=0.1, M=200 000 = 2.0 s sim):**
- mean per-joint twist **71.81°** (≈ 72° rest); std across joints **±2.57°** ⇒ **COHERENT**.
- coherence-std mid→late **2.35 → 2.40°**, ratio **1.022**, **no NaN** ⇒ **STABLE** (bounded, not growing).

**Stiffness sweep — the fraction form has a comfortable window; raw Hooke does not:**

| fraction f | std across joints | verdict | | raw-Hooke k (N·m/rad) | α=k·dt/γ | std | verdict |
|---|---|---|---|---|---|---|---|
| 0.10 | 4.70° | coherent+stable | | 1e-21 | 0.007 | 12.1° | under-stiff |
| 0.30 | 2.78° | coherent+stable | | 1e-20 | 0.073 | 3.8° | usable (narrow) |
| 0.50 | 2.29° | **coherent+stable** | | 1e-19 | 0.73 | 52.6° | **rings** |
| 0.80 | 2.27° | coherent+stable | | 2.7e-19 | 1.97 | 71.8° | rings (edge) |
| 1.00 | 4.14° | coherent+stable | | 5e-19 | 3.65 | 75.8° | over-threshold |
| 1.50 | 54.2° | incoherent (α→2 ring) | | 1e-18 | 7.29 | 77.5° | over-threshold |

The fraction form is coherent+stable across **f ∈ [0.1, 1.0]** (sweet spot ~0.5, std ±2.3°). Raw Hooke has
only a **narrow** usable band (α ≈ 0.03–0.1): below it's under-stiff, and it **rings** by α≈0.7 and heads to
`dt_crit` blow-up at α>2 (`θ_{n+1}=θ_n(1−α)` diverges for α>2) — confirming why the fraction-per-step form is
the right tool. Splitting the closure between both bodies (`f·e/2` each) is better-conditioned than raw
Hooke's independent `α·e`.

**Thermostat is the coherence knob (equipartition-at-reduced-temperature):** coherence std ≈
`rolldamp · sqrt(kT/k_eff)`, so std ∝ rolldamp —

| rolldamp | std across joints | verdict |
|---|---|---|
| 0.1 (default) | ±2.5° | tight coherent |
| 1.0 (full thermal, f=0.5) | ±24.5° | coherent but loose (mean 71.2°, stable) |
| 1.0 (full thermal, f=1.0) | ±21.8° | coherent (stiffer helps slightly) |

Even at **full thermal roll** the frames hold a net twist (mean ≈ 71°, bounded, no blow-up). Coherence for the
downstream ~30–60° binding acceptance window is comfortable at rolldamp ≤ ~0.3.

**No-spring control (f=0, rolldamp=1.0):** std **93.2°**, mean 9.7° (random) ⇒ **INCOHERENT, outcome C** — the
frames scramble to ~uniform without the spring. This is the decisive control: **the spring is what creates the
coherence**, not the initial condition.

**CPU≡GPU parity:** single-step bit-identical (`torqueSum_x`, `yVec` exact); deterministic perturb-and-relax
(Brownian off, 2000 steps) **max Δ per-joint twist 1.76e-5°** ⇒ **PASS** (float32 last-bit). The CPU triage run
reproduces the GPU coherence (std 2.54° vs 2.57°).

**Handedness.** The coarse per-joint frame twist is **+72°** — this is the microscopic left-handed
`−166.5°/mon` ALIASED over 32 monomers (`32·−166.5° mod 360 = +72°`). The segment frame carries only the
COARSE (net-mod-2π) phase; the true left-handed microscopic handedness lives in the **intra-segment analytic
interpolation** `φ(arc)` (deferred to the binding increment, which reads `frame_phase + (2π/P)·(arc within
segment)`). The sign is set correctly at the per-monomer constant (`twistPerMon = −166.5°`, LEFT-handed).

## Route outcome & what it means for Increment 2

**(A) A stable coherent regime exists at production dt = 1e-5.** The physical-phase route is viable — the
segment `yVec` frames cohere into a real helical frame (no analytic φ₀ scalar, no chain-walk, no severing-φ₀
problem). Recommended operating point: **fraction form, f ≈ 0.5, rolldamp ≈ 0.1** (±2.5° coherence, `α=0.5`
half the stability margin). Increment 2 (off-axis bond) can build the azimuthal gate + off-axis attachment on
this frame; the twirl torque enters the same `bwx` roll channel this spring already drives.

## Flags / caveats (for the planner)
- **Coarse aliasing ⇒ intra-segment interpolation is REQUIRED in Inc 2.** The frame resolves only the net twist
  mod 2π; the binding gate must combine `frame_phase + (2π/P)·arc-within-segment` to see the true 166.5°/mon
  helix. Not a blocker — it's the design's stated split (coarse=frame, fine=analytic).
- **General-topology rest-sign decode deferred.** The prototype assumes a single slot-ordered chain (slot 0
  pointed → slot n−1 barbed, end2→higher slot) so ONE scalar rest is correct for both joint sides. A branched
  or arbitrarily-wired topology needs the reciprocal end-side decoded for the rest sign — trivial add when
  motors/turnover arrive.
- **Non-uniform monomerCount ⇒ per-joint rest.** Uniform segments here ⇒ a scalar rest. Growth/split give
  variable segment lengths ⇒ the rest becomes per-joint (`monomersBetween · twistPerMon`); cheap, a per-joint
  scalar instead of a global one.
- **Straight-start is a degenerate saddle (finding).** From a torsionally-straight (un-twisted) start with free
  ends the interior joint-torques cancel (uniform error) and the twist winds in only from the ends —
  float-sensitive and slow. Relevant to how a freshly-nucleated filament acquires its twist; the relaxed
  (twisted-rest) start is well-conditioned. Use the twisted-rest initial condition when seeding filaments.
- **Roll is invisible on a roll-symmetric rod render** — the twist is in the frame azimuth, so `-3js` shows an
  ordinary filament; the quantitative twist-profile is the readout (no viewer frames dumped for that reason).

## Runner disclosure
GPU device-resident TaskGraph (`zero → brownian → dampRoll → chain → rollspring → integrate → derived`, 7
kernels, 64 segments, pose FIRST_EXECUTION-resident, pulled UNDER_DEMAND at cadence). 200 000 steps completed
comfortably (~thousands of steps/s at this tiny scale). `-cpu` runs the identical system methods sequentially.

```
./scripts/run_rollspring.sh                 # GPU coherence + long-run stability + CPU≡GPU parity  (outcome A)
./scripts/run_rollspring.sh -sweep          # fraction-f sweep + raw-Hooke blow-up contrast
./scripts/run_rollspring.sh -rolldamp 1.0   # full-thermal hard test
./scripts/run_rollspring.sh -rollstiff 0 -rolldamp 1.0   # no-spring control (INCOHERENT, outcome C)
./scripts/run_rollspring.sh -cpu            # CPU triage
```

**Default OFF / byte-identical:** new files only; no existing file, default, or canonical path touched.
`BoA-v1ref` untouched. No binding / motors / turnover this increment.

---

# SPRINGS-CONTINUUM CONVERSION (Increment 1b, 2026-07-09)

**Plain answer: YES on all three — the springs form matches the fraction at 1e-5, stays stable, and now
converges with dt. The roll coupling is now the SAME springs-continuum object as every canonical constraint;
no fraction-per-step exception remains.**

Increment 1's roll coupling was a **fraction-per-step** law (`Tmag = f·e/((1/γ_o+1/γ_t)·dt)`, α=f) — stable and
coherent, but the ONE fraction-per-step law smuggled back after the canonical collapse made every constraint a
fixed spring. Its effective stiffness `k = f·γ_red/dt` grows ∝1/dt, so its coherence would **freeze as dt→0**
(the frozen-skeleton artifact class): invisible at 1e-5, but dt-fragile once binding rides on it. Converted to
the canonical springs object.

**The conversion (mirrors `GlidingHarness` §PAIRS_SPRINGS / `DT_AUDIT_AND_SPRINGS.md`).** A FIXED stiffness
`k_roll = f·γ_roll_red/refDt` via `springify(f)=f·(dt/refDt)` fed into the SAME kernel ⇒ the `dt` **cancels to
`refDt`**. Concretely mode 2 uses `refDt` in the denominator instead of `dt`:
`Tmag = f·e/((1/bRGx_owner+1/bRGx_other)·refDt)`, `refDt=1e-5`, `γ_roll_red = 1/(1/bRGx_o+1/bRGx_t)`. Fixed
stiffness (dt-independent), torque about `u_owner`, equal-opposite, race-free — the Inc-1 `bwx` channel
unchanged. Now the **default** roll form (`-fraction` opts back to the old law for contrast).

**1. refDt-equivalence — springs ≡ fraction at 1e-5 (BYTE-IDENTICAL).** At `dt==refDt` the floats `dt` and
`refDt` are the same bits ⇒ identical arithmetic. Verified: mean 71.71°, **std 2.54° (both forms)**, CPU≡GPU
parity identical (max Δ 1.76e-5°), same COHERENT/STABLE verdicts. All Increment-1 numbers stand.

**2. Stability preserved at 1e-5.** Springs long run **M=200 000 (=2.0 s sim):** std ±2.57°, ratio 1.022, no
NaN → STABLE; CPU≡GPU PASS; ROUTE (A). Identical to the Inc-1 fraction long run. Springs α = `k_roll·dt/γ =
f·dt/refDt` = f=0.5 at production, and DECREASES below refDt (more stable at finer dt) — the stable-f window is
unchanged at refDt (springs≡fraction there).

**3. dt-convergence — the payoff (the property the fraction form lacked).** Same sim-time (0.10 s) per dt,
FDT-consistent Brownian at each dt:

| dt | steps | FRACTION std | SPRINGS std |
|---|---|---|---|
| 1.0e-5 (=refDt) | 10 000 | 2.40° | 2.40° |
| 5.0e-6 | 20 000 | 1.68° | 2.15° |
| 2.5e-6 | 40 000 | 1.17° | 2.05° |
| 1.0e-6 | 100 000 | **0.74°** | **2.00°** |

The **fraction std shrinks exactly ∝√dt** (`2.40·√(dt/refDt)`: √0.5→1.70, √0.25→1.20, √0.1→0.76 — measured
1.68/1.17/0.74) ⇒ it **freezes** toward 0 as dt→0 (the artifact). The **springs std converges** to the
dt-stable equipartition value `≈ rolldamp·√(kT/k_roll) ≈ 2.0°`, dt-independent — **dt-honest, like the rest of
the canonical model.** (The small 2.40→2.00 settling is the forward-Euler discrete-variance inflation
`1/(1−α/2)` relaxing as springs α=f·dt/refDt→0.)

**CPU≡GPU on the reformed spring:** bit-identical single step; perturb-relax max Δ 1.76e-5° (PASS) — the new
mode-2 arithmetic lowers correctly on PTX (spot-checked per the CPU-arbiter rule; no repeat of the Inc-1
`continue` mis-lowering). **Default-OFF byte-identity:** new files only, no existing/canonical file touched
(FDT re-validated PASS); at production dt=refDt the springs default is byte-identical to the Inc-1 fraction
result. `BoA-v1ref` untouched.

```
./scripts/run_rollspring.sh                 # DEFAULT = springs-continuum (coherence + stability + CPU≡GPU)
./scripts/run_rollspring.sh -dtconv         # the payoff: fraction std ∝√dt (freezes) vs springs std dt-stable
./scripts/run_rollspring.sh -fraction       # opt into the old dt-dependent fraction form (contrast)
```

**⇒ The roll spring is now dt-honest. Increment 2 (off-axis bond) builds the azimuthal gate + off-axis
attachment on this springs-continuum frame.**
