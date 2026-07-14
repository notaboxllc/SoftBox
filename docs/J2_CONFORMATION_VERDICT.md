# Passive J2 conformation verdict

## Decision

**Primary classification: Outcome E -- mechanical null.** The defensible weak-to-moderate cone springs alter the
internal J2 angle and store energy, but do not produce a monotone increase in retained actin-level displacement,
useful axial force per motor, force-zero velocity, or density performance. Arms strong enough to impose several
`kT` fail the preload/internal-torque gate and are a secondary Outcome-D stopping condition; no apparent effect is
timestep-dependent, so Outcome F is not indicated.

The canonical default remains the free J2 hinge. The physical spring implementation is useful as a default-off
diagnostic, but no result in this study licenses changing the canonical motor.

The study began on branch `dt-convergence-study` at base commit
`df310f5aa5cfd2ced851ae7ea01014d20e179ca8` with the documented uncommitted performance/J2 patches. During the
long run the branch advanced externally to `f53797215d1999bc915c002f0407a9ffa96fa326`, a fine-dt reference commit
containing only documentation, a figure, and analysis scripts; it did not change the compiled J2/simulation code.
The final validation was run at that HEAD with the J2 patch still uncommitted. The environment was OpenJDK
21.0.11, an 8-core/16-thread Intel i9-9900K, 31 GiB RAM, and an NVIDIA GeForce RTX 5070 (12,227 MiB,
driver 595.71.05). GPU trajectories used the repository's TornadoVM 4.0.1-dev PTX runner and its existing JVM
flags.

## Executed code path and protected model

The tested stack is the field-default `SPHEREHEAD + AXLOCK + DIRSWING + XB_IMPLICIT2 + LYMN_TAYLOR` motor, not the
separate Java `CANONICAL`/Config-1 experiment. Per timestep, the motor kernel resets forces and applies Brownian
forcing, evaluates the rod–lever J2 and lever–head J1 positional joints, applies the tail anchor, F8/F9/AXLOCK,
DIRSWING, integrates all three motor bodies, then performs the canonical coupled implicit F8 correction. Chemistry,
binding search/order, RNG calls, force laws, and task order are unchanged.

J2's active positional connection remains `rod.end2` to `lever.end1` and is evaluated exactly once per motor. The
optional torque is accumulated inside that already-executed J2 block, after the unchanged position-joint `R x F`
terms and before J1. Full geometry, signs, units, legacy parameter semantics, and the exact-zero performance path
are documented in `J2_CONFORMATION_ARCHITECTURE.md`.

## Physical coordinate and potential

Because the native rotation axes do not supply a coherent reference plane, the scalar coordinate is the shortest
unsigned bend

```text
theta_J2 = atan2(|rod.u x lever.u|, rod.u dot lever.u),  0 <= theta <= pi
axis_J2  = normalize(rod.u x lever.u).
```

The 124-degree value is between the stored `+u` axes; equivalently, the two rays leaving the J2 joint form
`180-124=56 degrees` (`-rod.u` versus `+lever.u`).

The opt-in term is

```text
U_J2 = 1/2 kappa_J2 (theta_J2 - theta0)^2
rod torque   += axis_J2 kappa_J2 (theta_J2 - theta0)
lever torque -= axis_J2 kappa_J2 (theta_J2 - theta0).
```

Thus it adds equal-and-opposite torques, no force and no external torque. `kappa_J2` is specified in pN·nm/rad,
converted once to N·m/rad, and has no `dt`, drag, controller fraction, or nucleotide state. The scalar potential is
fully three-dimensional but angle-only: its torque axis is normal to the instantaneous rod–lever plane, it bends
within that plane, and it leaves azimuth/twist free. All orientations on the 124-degree cone are degenerate; it is
not a fixed-plane hinge constraint. The potential is continuous over `[0,pi]`; the vector gradient is necessarily
undefined at exact parallel/antiparallel axes, where
the implementation applies zero torque instead of inventing a state-dependent plane. This geometric singularity
is relevant because weak springs select near-antiparallel pre-stroke poses.

`-j2torsion K` enables the term. `-j2rest DEG` is an explicit diagnostic override. The ordinary parameter buffer is
not extended unless one of those flags is supplied, and the default stiffness is exactly zero.

## Native coordinate and rest-angle provenance

Four paired CPU seeds at `dt=5e-6 s`, density 300 µm⁻² and clamp velocities
`v={0,8,12,14,16}` show a broad accommodation coordinate. At zero velocity, bound ADP·Pi is
`103.00 +/- 39.19 degrees`, while bound ADP is `122.15 +/- 24.87 degrees`. The age-matched bound-ADP plateau
(0.30–0.50 ms) contains 7,027 samples with mean `122.05 +/- 25.34 degrees` and pooled 0.1-degree-bin median
**124.0 degrees**. That preregistered median is `theta0`; it was selected before any spring run and was not fitted
to an outcome.

The plateau mean remains 120.8–122.7 degrees from 0 to 16 µm/s. Attachment age, rather than velocity, explains the
clearest evolution: native J2 moves from about 99–105 degrees during the first 0.025 ms toward 122–125 degrees by
0.5 ms as DIRSWING error relaxes. Episode-mean angle has only weak associations with lifetime (`r=+0.169`), net
impulse (`+0.099`), peak/relaxed tip displacement (`-0.092/-0.065`), axial F8 (`+0.086`), transverse F8
(`-0.164`), and DIRSWING error (`-0.220`). See `J2_NATIVE_ANGLE_AUDIT.md` for distributions and free-glide results.

The age-resolved native free-glide census gives the same interpretation. At densities 50/500/2,000 µm⁻² the
ADP-plateau means are 118.1°/124.7°/129.0°, all with about 25° SD. Thus load/density shifts the late pose modestly
but does not reveal a narrow conformation; angle correlations with episode impulse, lifetime, and relaxed
displacement remain weak.

## Stiffness ladder

At 298.15 K (`kT=4.117 pN nm`), the full diagnostic ladder was
`kappa={0,0.01,0.03,0.1,0.3,1,3,10,30,100,300}` pN·nm/rad. At a 30-degree deviation those arms store
`{0,0.00033,0.0010,0.0033,0.010,0.033,0.100,0.333,0.999,3.33,9.99} kT`; the report also tabulates 10- and
20-degree energies. These are interpretability brackets, not experimental stiffness estimates.

## Deterministic transmission and timestep gate

The deterministic native ADP·Pi pose is 99.64 degrees, giving a 24.36-degree mismatch from the ADP-derived rest.
The coordinate is mechanically near-neutral: even `kappa=0.01` selects an almost antiparallel pre-stroke pose.
At `kappa=0.1`, the pose is 179.86 degrees with only 0.0115 kT stored; at `kappa=3`, it is 175.68 degrees with
0.296 kT and 2.71 pN·nm torque. This finite pose change from an infinitesimal bias is not proportional force
transmission.

At `dt=2.5e-6 s`, baseline peak/relaxed tip–site displacement is `+0.790/-0.272 nm`; `kappa=0.1` gives
`+0.897/-0.213 nm`, and `kappa=3` gives `+0.884/-0.336 nm`. The weak arms reduce maximum transverse F8 from
2.91 pN to about 0.46 pN but do not increase sustained axial F8 or retained displacement monotonically. Forward,
resistive, and closed-cycle challenges reach the same conclusion. The ADP·Pi→ADP state switch leaves J2 energy
and pose unchanged instantaneously, confirming the absence of a second conformational transition.

Equal-duration tests at `dt={1e-5,5e-6,2.5e-6} s` preserve the qualitative ordering for `kappa<=30`. The 100 and
300 arms have poorer transient convergence; `kappa>=30` also produces 16–95 pN·nm pre-stroke torque and fails the
preload/overconstraint gate. `kappa=10` is timestep-stable but already carries 0.70 kT and 7.59 pN·nm pre-stroke
without improving sustained axial transmission, so it fails the deterministic benefit gate. Only
`kappa={0.1,1,3}` proceeded to the ensemble screen.

## Force–velocity and episode-kernel attribution

The initial four-arm screen used four paired seeds at six velocities, with eight-seed refinement near the zero.
A powered baseline-versus-`kappa=3` bracket at density 1000 µm⁻² used eight paired seeds and contains roughly
1,700 completed episodes per velocity. Mean-curve crossings are 13.66 and 13.81 µm/s. Per-seed local fits give

```text
Delta V0 (kappa=3 - baseline) = +0.095 +/- 2.455 um/s (95% CI).
```

Paired force changes at 10/12/14/16 µm/s are `+0.020/+0.010/+0.004/+0.007 pN`, each with a confidence interval
spanning zero. The paired slope change is `-0.0023 +/- 0.0130 pN/(um/s)`. There is no resolved gain in
zero-velocity or per-bound force, no steepening, and no resolved V0 shift. Although `kappa=3` shifts the mean
all-bound J2 angle by about 3.5° and stores about 0.075 kT on average, F8 split, anchor extension, DIRSWING error,
occupancy, attachment rate, and lifetime remain near baseline.

The age-resolved kernel does reveal a waveform change. At low velocity `kappa=3` raises the 0.025 ms force
transient; at 12–18 µm/s it makes the survivor-conditioned 0.5 ms force more negative. Lifetime and force-reversal
time change little. This is a changed strain/force trajectory, not force amplification or a large change in
force-selective survival. `J2_FORCE_VELOCITY.md` contains the paired table and complete attribution.

## Free-gliding density saturation

Two paired GPU seeds used densities 50--8,000 for baseline and kappa=0.1. The weak-arm density-8,000 trajectories
then triggered the fidelity gate (18.83 versus 8.62 µm/s between seeds, one coverage failure, 95% interval
`+/-64.84 µm/s`), so kappa=3 was stopped at density 4,000 as preregistered.

On the common valid range through density 4,000, the baseline/weak/strong velocities at the top point are
`6.157/6.096/6.254 µm/s`; paired arm-minus-baseline differences are `-0.062 +/- 2.725` and
`+0.097 +/- 0.845 µm/s`. At that density force per bound motor is `0.276/0.248/0.245 pN`, mean transverse F8 is
`2.867/2.923/2.877 pN`, mean bound count is `11.77/12.11/12.13`, and mean anchor extension is
`7.327/7.299/7.321 nm`. Kappa=3 shifts mean J2 from 122.48 to 125.09 degrees and stores 0.311 pN nm
(0.076 `kT`) while leaving these operational outputs near baseline.

No valid curve reaches a plateau. Formal matched-range Michaelis--Menten fits give
`(V_inf,rho_1/2)=(21.0,9402),(22.0,9991),(73.7,43092)` for baseline/weak/strong, but every half-density lies outside
the grid, the kappa=3 linear alternative has lower SSE, and omitting its highest point sends the fit to
`(909,5.49e5)`. These are fit-failure diagnostics, not biological parameter estimates. There is no supported
leftward knee shift; `J2_DENSITY_SATURATION.md` reports every velocity, paired interval, mechanical readout, fit,
high-density increment, and coverage gate.

## Fidelity, implementation, and validation level

The default-off implementation does not change J1, DIRSWING, F8/F9/AXLOCK, the anchor, chemistry, binding, RNG,
or numerical ordering. A dedicated torque contract verifies zero-path raw-float identity, force-free action,
equal-and-opposite rod/lever torque, no head torque, correct magnitude, state independence, and timestep
independence. Its result is:

```text
J2_TORSION_TEST PASS zero-byte-path=true force-free=true equal-opposite=true
state-independent=true dt-independent=true plane-invariant=true torque_pNnm=-0.209439
```

The zero-stiffness 16,000-step clamp's deterministic scientific/event stream (`J2EP`, distribution/correlation/
age summaries, `FVROW`, and `CMPLROW`) is byte-identical before the physical implementation, after it, and after
the final rebuild: SHA-256 `8bcd0273537405c0d5d3792420add1da47020757b13d12b99dcf6a8655affe8f` (`cmp=0`). The final diagnostics-off
whole output has SHA-256 `66d4e060c5097b64ad118b249272a00b2a2b013295398e9611e1750a94fe8464` and exactly matches the audit-enabled
run after removing intentional `J2*` records. This and the raw-float torque contract establish Level 1 for the
disabled physical term without pretending that newly added logging lines are byte-identical output. Spring-on CPU
comparisons are Level 2 by construction for deterministic contracts; ensemble effects are Level 3 and are
reported only with paired seeds and confidence intervals. Existing deterministic motor/body, binding,
crossbridge/gather, and canonical short-glide tests pass.

A matched 30,000-step, density-500, seed-7 kappa=3 CPU/GPU check kept full carpet coverage on both paths. CPU/GPU
values were: F8 magnitude 3.745/3.660 pN, transverse F8 2.861/2.757 pN, J2 energy 0.336/0.330 pN nm, anchor
extension 7.361/7.226 nm, detachment rate 1,607/1,680 s^-1, and dwell 0.622/0.595 ms. Mean J2 was
121.97/118.30 degrees against a 26--27-degree SD; maximum torque was 6.35/5.67 pN nm. The stochastic trajectories
were not numerically identical: velocity was 0.829/0.229 µm/s and mean bound count 1.503/1.119. This single-seed
check supports qualitative kernel/mechanical consistency and finds no GPU-only pathology, but it is explicitly
Level 3 and does not establish statistical CPU/GPU equivalence of gliding velocity. Reproduce it with
`./scripts/run_j2_conformation.sh consistency 30000`.

## Strongest objection and recommendation

The strongest objection is structural, not statistical: one state-independent unsigned bend angle cannot be
preload-free for both the very broad ADP·Pi ensemble and the ADP plateau. The baseline J2 direction is nearly
neutral, so a thermally negligible bias selects a finite near-antiparallel pre-stroke pose, close to a coordinate
singularity, before it supplies meaningful resisting energy. Any apparent operational benefit could therefore be
pose selection within an underconstrained three-body network rather than evidence for native lever–rod torsional
stiffness. Conversely, a mechanical null for this cone potential would not rule out a passive dihedral or
full-frame conformation that also fixes the bend plane; that would be a different scientific model and was not
introduced opportunistically here.

Safe to retain: the default-off physical potential, contract test, logging, scripts, and analysis tools.

Not recommended as a canonical change: every tested nonzero arm. Strong arms are preload/pathology-limited; weak
arms do not yet satisfy the simultaneous displacement, density-knee, V0, timestep, and internal-force gates.

Do not alter as a performance/tuning response: the J2 positional arithmetic, candidate ordering, DIRSWING/F8/F9/
AXLOCK network, stochastic kinetics, or update order. They encode the protected scientific semantics.
