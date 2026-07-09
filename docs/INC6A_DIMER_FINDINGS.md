# Increment 6a — myosin dimer coupling (two motors, no-gather self-write): findings

**Status: DONE (2026-06-17). All 6 gates PASS on GPU + CPU.** The first myosin-structure assembly.
v2 = SoftBox (`softbox/`); v1 oracle = `BoA-v1ref` (read-only, byte-clean). Plan: the inc-6 recon
(`INC6_MYOSTRUCT_RECON.md` §2 — dimer = NO gather; §3a — v1 model; §6 — reuse table).

## What 6a is
A **dimer** = a 1:1 structural pairing of two articulated motors (v1 `MyosinDimer`: `myo1`,`myo2` —
two full rod→lever→head Myosins joined by inter-rod springs + a lever-alignment torque). Validated on
a **pre-placed isometric bed**, **static** assembly, heads **FREE** (no filament / cross-bridge / glide —
those are later). The shared rigid-rod systems + the per-motor J1/J2 joints run over `MotorStore.body`
UNCHANGED; only `DimerCouplingSystem` is dimer-specific.

## The architecture (recon §2, confirmed in code) — the SIMPLEST coupling: NO gather
Each motor (hence each rod/lever sub-body) belongs to **exactly one** dimer, so the dimer computes its
reaction once and **self-writes both sides directly** into its two uniquely-owned rod/lever sub-body
slots — a direct two-slot write, **race-free with no atomics, no KernelContext, NO CSR gather** (contrast
the motor→segment single-ended gather and the crosslinker two-pass). Disjoint pairing `motorA(d)=2d`,
`motorB(d)=2d+1` ⇒ each sub-body has a single writer. This is the *favorable* finding the recon flagged.

## Faithful port (component-port; v1 = the per-component oracle)
- **Force law = the validated PAIRS spring** (same as the intra-motor J1/J2 joints): `F = fracMove·1e-6·
  strain/(dt·(moveCa+moveCb))`, the `moveCoeff` anisotropic mobility (reused VERBATIM as
  `MotorJointSystem.moveC`; sign-independent in cosβ² so v1's `uVec`/`uVecR` end argument is immaterial).
  v1's lever-arm is the **full** half-rod-length `0.5·rodLen·(±uVec)` (NO fracR — unlike the joints).
- **Four rod-coupling variants** (v1 `applyRodCouplingEnd1`/`End2`/`End1End2`/`End2End1`,
  `MyosinDimer.java:163–273`): parallel = End1+End2 (`endSel` (-1,-1),(+1,+1)); antiparallel = End1End2+
  End2End1 ((-1,+1),(+1,-1)). Implemented as an inner 2-iteration loop.
- **Lever-align torque** (v1 `alignUVecLeversTorque`, `:111–135`): restore the lever-to-lever angle to
  **160°** (`leverAngle`, `:9`); `torsionMag = coeff·(π/180)·(ang-160)/((1/bRGy_A+1/bRGy_B)·dt)`,
  `accurateAcos` (device-safe, the §5c-ii note: `Math.acos` does not lower on PTX), v1 `checkPt3D` →
  degenerate-axis guard. Fires in parallel mode (heads free ⇒ v1's "align only if a head is off-filament"
  gate is always true this increment).
- **v1 defaults** (verified `Env.java` 2026-06-17): `myoDimerFracMove=0.2` (`:165`),
  `myoDimerLeverFracMoveTorq=0.4` (`:161`), `leverAngle=160°`, `myoRodLength=0.080` (`:776`).

## Validation (co-developed small-scale vs `BoA-v1ref`, not fixtures)

| # | gate | result |
|---|---|---|
| **A** | **force arithmetic** (isolated couple, KNOWN displaced geom) vs an independent **double** reference using the LITERAL v1 path (acos+sin in moveCoeff) | **maxRel = 6.6e-8** (≪0.1%, the >0.1%-is-logic rule); rod force exactly **equal-and-opposite** (\|F_rodA+F_rodB\| = 0.0 N). Component-port gate. |
| **B** | **rest hold** (rest-config dimer, Brownian OFF) | rod-rod gap **6.8e-9 µm** (≈0), lever angle **160.0000°**, COM fixed — the Y-shape rest is an **exact mechanical fixed point** (all joint+coupling strains 0). |
| **C** | **relaxation + dt-invariance** (displace a rod 5 nm, Brownian OFF) | decays (5.0e-3→7.0e-4 µm), per-step ratio ≈**0.834**; **dt-invariant to 8.4e-7** across dt 1e-5↔1e-6 (the `/dt`↔integrator cancellation, like 5a). |
| **D** | **lever angle** (Brownian ON) | **stationary** (halves μ1=152.57°, μ2=152.56°, Δ=0.015°), **bounded** (std 8.6°), **FDT-thermal-scale** (measVar/predVar = 1.40, ρ=0.849). Mean 152.6° = a **fluctuation shift of the bounded θ∈[0,180] coordinate** below the 160° rest under the head-driven swing (B proves 160° is the exact Brownian-off fixed point ⇒ NOT a sign drift). |
| **E** | **CPU≡GPU** | deterministic (1000 steps, Brownian off) max\|Δpose\| = **3.5e-6 µm** (float32 last-bit, non-chaotic); Brownian aggregate (2000 steps) lever-angle **Δ=0.000°**. |
| **F** | **all-OFF ≡ HEAD** | dimer coupling off ≡ bare two-motor path **bit-identical (Δ=0.0)**; control: coupling-off lever angle drifts to **106°** ⇒ the dimer coupling IS what pins 160°. |

**Force-coverage audit (per subsystem, applied exactly once):** rod-rod spring — +F on rodA / −F on
rodB (exact, gate A); rod-rod lever-arm torque — R×F on each rod (once); lever-align torque — +τ on
leverA / −τ on leverB (once). No force zero-dropped, none double-applied.

## Equipartition posture (CLAUDE.md §8) — why D is gated on FDT-consistency, not v1's number
v1's myosin structures were never calibrated to experiment ⇒ v1 is the **component-port** oracle (force
law/rate/joint — gate A, bit-for-decision) but **NOT** a quantitative oracle for **emergent** structure
behavior (the steady lever-angle distribution). So D is judged against **physics**: the align torque pins
a **stationary, bounded, thermal-scale** distribution at the FDT steady-state of its own (damping-limited)
law — verified — not against v1's angle. (A subtlety of the fracMove scheme: the deterministic per-step
decay is dt-independent while the Brownian noise per step ∝ dt, so the fluctuation amplitude is itself
dt-scaled — the absolute ½kT-anchor is scheme-relative; this is why D uses the **measured** σ²/(1−ρ²)
FDT self-consistency, not a derived-constant match.) A future v1 cross-check (does v1 sit sub-thermal, as
its crosslinker strain did in §8?) is informational, not a target.

## Files
New: `DimerStore.java`, `DimerCouplingSystem.java`, `MyosinDimerHarness.java`, `run_dimer.sh`. No existing
file touched (production / `GlidingHarness` byte-unchanged; `BoA-v1ref` byte-clean). No `STORE_*` constant
needed (dimers are pre-placed, no broad-phase publisher this increment).
```
./run_dimer.sh              # GPU + CPU cross-check (32 dimers / 64 motors): A–F
./run_dimer.sh -cpu         # CPU runner only (triage)
./run_dimer.sh -3js threejs_dimer -n 9   # viewer (Y-shaped dimers)
```

## TornadoVM note (load-bearing for the next structure increments)
The rod-link math must be **inlined into the top-level @Parallel kernel**, NOT factored into a helper:
a helper with 2× inlined `moveC` exceeds TornadoVM's **600-node inlined-callee cap** (`TornadoInlining
Exception`, node count 602). The kernel method itself has no such cap; only the small `moveC`/`accurateAcos`
helpers are inlined. (Reuse this pattern for 6b's minifilament gather kernel.)

## §6a-thermo — rotational-thermostat equipartition diagnostic (the gate-D 1.40×, RESOLVED)

**Why.** Gate D found the lever fluctuation at **1.40× the AR(1) ½kT estimate**, tentatively called
"scheme-relative." That was incomplete (the crosslinker is *also* fracMove-coupled and matched
equipartition cleanly, §8). The dimer lever is the **first rotational DOF anchored to equipartition**,
so the real question: is v2's **rotational thermalization** at ½kT, or off ~1.4× (which would silently
bias *every* rotational DOF — filaments, motors, the gliding assay — passing v1-matching while failing
physics)? Cleared before 6b stacks a backbone + N levers on it. Diagnostic only — **no production fix**.

**Cut 1 (DECISIVE) — free-rod rotational thermostat is at ½kT.** Already in `DiffusionHarness` Config R
(`./run_gpu.sh -cpu`): a free rod's orientational autocorrelation `⟨u(t)·u(0)⟩ = exp(−2 D_rot t)` gives
`D_rot = 18.28` vs the FDT prediction `kT/bRotGam = 18.61 rad²/s` — **−1.8% (≈1.0×, slightly UNDER, NOT
1.4× over)**. Translational control clean (D_par −2.5%, D_perp −1.2%/+0.1%). The Brownian amplitude is
FDT-consistent **by construction** (`randTorque = rScale·√(2kT/dt)·√(bRotGam)·g` ⇒ injected per-step
angular MSD = 2·(kT/bRotGam)·dt = 2 D_rot dt) and the integrator faithfully accumulates it. **⇒ no
upstream rotational-thermostat miscalibration; the dimer 1.40× is NOT a thermostat bug.** (The planned v1
free-rod cross-check is **moot**: the decision logic only invokes it on a ~1.4× free-rod result; Cut 1 is
clean — and v2's Brownian/integration are ported **0-diff** from v1, so v1 shares the same FDT-consistent
thermostat by construction. Not run — `BoA-v1ref` byte-clean.)

**Cut 3 — a clean, directly-thermostatted confined rotational DOF sits at the scheme's EXACT discrete
equipartition.** A single rod (Brownian ON) held to a fixed rest by the same fracMove torsional law as
the lever-align (isolates the scheme from the lever's *indirect* drive + the gate-D AR(1) crudeness):
`measVar/predDiscrete = 0.992` where `predDiscrete = 4kT·dt/(γ·c(2−c))` is the **exact discrete-AR(1)**
steady state (2 transverse DOF, per-step decay c = coeff = 0.4). The naive **continuum** `2kT/k_θ`
under-predicts by `1/(1−c/2) = 1.25×` — exactly the apparent 1.24× — a **discrete-vs-continuum AR(1)
correction, not a thermostat error**. (`softbox/ThermostatDiag.java`.)

**Cut 2 — the confined fluctuation is dt-scaled but exactly the scheme's own equipartition at each dt.**
`⟨θ²⟩ ∝ dt` (4.6e-3 / 9.2e-3 / 1.8e-2 rad² at dt 5e-6/1e-5/2e-5), yet `meas/predDiscrete ≈ 1.0`
throughout (0.996/0.992/0.987). The fracMove relaxation is **not a fixed-stiffness spring** —
`k_θ = coeff·γ/dt` — so its equilibrium amplitude is dt-set (**scheme-relative**, shared by the §8
translational crosslinker, which likewise samples its *own* Boltzmann), but it is **exactly** the
scheme's own discrete equipartition at each fixed (production) dt. There is no dt-independent
½kT-physical anchor for a damping-limited DOF; FDT self-consistency is the right test, and it holds.

**Read / decision.** Rotational **thermostat at ½kT** (Cut 1) + a directly-thermostatted confined
rotational DOF at the **exact discrete equipartition** (Cut 3, 0.992) ⇒ the gate-D **1.40× = the 1.25×
discrete-vs-continuum AR(1) factor × residual gate-D crudeness** (the lever is Brownian-OFF ⇒ indirectly
driven; gate D's σ² was measured align-off while ρ was align-on — these don't compose into a clean AR(1)).
**Benign — no thermostat fix; the rotational foundation is CLEAR ⇒ 6b proceeds.** Run: `softbox.ThermostatDiag`.

## Next: 6b (minifilament)
The backbone (a 3rd `RigidRodBody` instance) owns N dimers; the head↔backbone coupling is **single-ended**
(reuse the `CrossBridge` CSR-inverse ONE pass, backbone-side — recon §2/§6). 6a's `DimerStore`/coupling is
the unit a minifilament assembles onto a shared backbone.
