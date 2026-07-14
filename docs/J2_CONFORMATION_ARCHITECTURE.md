# Passive J2 conformation architecture

This audit describes the executed branch state before adding J2 instrumentation or a passive potential. The
baseline is branch `dt-convergence-study` at `df310f5aa5cfd2ced851ae7ea01014d20e179ca8`, including the retained
uncommitted performance patch that evaluates each articulated motor's joints once.

## Executed topology and order

Each motor occupies three rigid-body slots: `3m=rod`, `3m+1=lever`, and `3m+2=head`. J2 is the rod-lever
connection. In every canonical CPU timestep, `MotorJointSystem.joints` runs after the motor force/torque reset and
Brownian-force generation, and before the tail anchor, F8/F9/AXLOCK, DIRSWING, and motor integration. The GPU task
graph has the same ordering. A passive J2 term belongs inside `MotorJointSystem.joints`, alongside the existing J2
connection, so no task or update is inserted or reordered.

The Java boolean called `CANONICAL` selects a separate two-point experimental motor and is false in this study.
The ratified default stack is the field-default `SPHEREHEAD + AXLOCK + DIRSWING + XB_IMPLICIT2 + LYMN_TAYLOR` path.

## J2 positional joint

The connected material points are:

```text
rod point   A = rod.center   + 0.5 * rod.length   * rod.u   = rod.end2
lever point B = lever.center - 0.5 * lever.length * lever.u = lever.end1
d = A - B
e = d / |d|
```

The connection magnitude is the inherited PAIRS controller:

```text
Fmag = j2FracMove * 1e-6 * |d| / (dt * (moveCrod + moveClever))
```

Here positions and lengths are stored in micrometres, `1e-6` converts the gap to metres, `moveC` is the effective
mobility along the link, and `Fmag` is newtons. `j2FracMove` is dimensionless and controls a fraction of positional
relaxation per step; with the default fixed-spring conversion its stored value scales with `dt/refDt`. It is not a
physical torsional stiffness.

The exact body-side actions are:

```text
rod force    = -Fmag * e
lever force  = +Fmag * e

rod torque   += ( +0.5e-6 * rod.length   * j2FracR * rod.u   ) x rod force
lever torque += ( -0.5e-6 * lever.length * j2FracR * lever.u ) x lever force
```

Thus the translational forces are equal and opposite. `j2FracR` is a dimensionless lever-arm fraction, not a
stiffness. The explicit `R x F` terms approximate application at the two joint ends while the forces themselves are
stored on body centres.

The recent performance change did not remove or approximate this connection. The optimized loop has one parallel
iteration per motor, loads the three poses once, evaluates the active J2 translational connection exactly once, and
writes only that motor's rod/lever/head accumulator slots. The deterministic clamp output remained byte-identical.

## Existing angular J2 controller

The relative bend coordinate is currently the shortest unsigned angle between the rod and lever longitudinal axes:

```text
axis  = normalize(rod.u x lever.u)
theta = acos(clamp(rod.u dot lever.u, -1, +1))
```

This is the angle between the two stored `+u` directions. If the joint is drawn using the two rays that leave J2,
the rod-side ray is `-rod.u` and the lever-side ray is `+lever.u`, so that visual/internal angle is `pi-theta`
(the 124° primary axis angle corresponds to a 56° outgoing-ray angle).

The legacy optional angular controller is:

```text
tauLegacy = j2FracMoveTorq * (pi/180) * (thetaDeg - j2RestDeg)
            / ((1/gammaRotY_rod + 1/gammaRotY_lever) * dt)
rod torque    += axis * tauLegacy
lever torque  -= axis * tauLegacy
```

`jointParams[7]=j2FracMoveTorq` is dimensionless fraction-per-step angular relaxation, `jointParams[8]` is a rest
angle in degrees, the drag tensors are in SI units, and the resulting torque is N m. Because the formula divides by
`dt` and rotational mobility, it is a controller, not the derivative of a timestep-independent physical potential.

All ordinary `MotorStore.setJointParams` initializations set `[7]=0.0` and `[8]=96.0`. The canonical resolution
block changes J1 `[3]` for DIRSWING and the positional coefficients `[1]`, `[5]`, and `[9]` for fixed-spring
semantics, but never changes J2 angular `[7]`. Config-1 copies the same zero into its larger parameter array. The
motor-body, stroke, cross-bridge, gliding, clamp, CPU, and GPU paths therefore all have zero native J2 angular
torque under canonical initialization.

This agrees with the read-only v1 provenance: `Env.myoJ2FracMoveTorq=0.00`, while
`Myosin.applyRodLeverJointTorque` contains the dormant 96-degree fraction-per-step law. No `BoA-v1ref` file is
modified by this study.

## Exact-zero optimization

`MotorJointSystem.joints` computes separate legacy and physical activity predicates and combines them. When both
are false, it skips the cross
product, normalization, refined `acos`, drag reads, and zero multiplication. It still executes the complete J2
positional block first. This is why the passive implementation must have its own activity predicate: reusing
`j2FracMoveTorq` would both restore a timestep-scaled controller and violate the requested physical units.

## Passive potential implementation

The default-off extension uses a separate physical stiffness and rest angle in an extended parameter buffer:

```text
kappaJ2 = pN nm/rad at the command line, converted once to N m/rad
theta0  = radians, state-independent
U       = 0.5 * kappaJ2 * (theta - theta0)^2
```

Command-line stiffness is converted by `1 pN nm = 1e-21 N m` at scene construction. `jointParams[14]` holds
`kappaJ2` in N m/rad and `[15]` holds `theta0` in radians. The ordinary buffer remains size 11 (or size 14 for
Config-1); it is extended to size 16 only after an explicit `-j2torsion` or `-j2rest` argument.

For `0 < theta < pi`, `axis=normalize(rod.u x lever.u)` is the shortest-rotation axis. If
`delta=theta-theta0`, the equal-and-opposite vector torques are:

```text
rod torque    += axis * kappaJ2 * delta
lever torque  -= axis * kappaJ2 * delta
```

Those signs reduce `theta` when `delta>0` and increase it when `delta<0`; the generalized restoring torque is
`-dU/dtheta = -kappaJ2*delta`. The production kernel uses its twice-Newton-refined device-safe `accurateAcos` on
the clamped dot product to obtain the shortest angle in `[0,pi]`; the host logger uses the equivalent
`atan2(|cross|,dot)` definition. This is a three-dimensional bend/cone potential: its torque axis is normal to the
instantaneous rod–lever plane and drives bending within that plane, but it does not select a fixed plane, an azimuth
around the rod, or relative axial twist. Every pose on the `theta=theta0` cone has the same energy. At exactly
parallel or antiparallel axes the bend direction is geometrically undefined, so the vector
torque must be zero rather than inventing a nucleotide- or frame-dependent axis; the observed native distribution
contains rare near-singular poses and the deterministic audit explicitly exposes their sensitivity.

The physical torque is accumulated in the existing J2 block immediately after the unchanged positional `R x F`
contributions and before J1 is evaluated. It adds no force, no external torque, no state lookup, no `dt`, no
stochastic draw, and no new task. With stiffness exactly zero, the default parameter buffer and executed arithmetic
remain unchanged.

The primary rest is 124.0 degrees, the pooled 0.1-degree-bin median of 7,027 unmodified `v=0` bound-ADP samples
aged 0.30–0.50 ms. `-j2rest` permits a diagnostic override but never changes state during a run.

## Protected invariants

The study did not change the J2 positional connection, J1, DIRSWING, F8/F9/AXLOCK, tail anchor, chemistry,
binding, RNG, force accumulation order, implicit correction, clamp semantics, or canonical defaults. A diagnostic
rest override changes only an explicitly opted-in run. Native-angle logging is host-side and default-off.

For the 16,000-step zero-stiffness clamp, the deterministic scientific/event stream selected by
`^(J2EP|J2SUMMARY|J2COR|J2AGE|FVROW|CMPLROW)` is byte-identical before the physical implementation, immediately
after it, and after the final rebuild: SHA-256
`8bcd0273537405c0d5d3792420add1da47020757b13d12b99dcf6a8655affe8f` (Level 1). The final diagnostics-off full
output has SHA-256 `66d4e060c5097b64ad118b249272a00b2a2b013295398e9611e1750a94fe8464` and is byte-identical to the same
audit-enabled run after removing its intentional `J2*` records. An older audit-enabled whole-file reference has
SHA-256 `7aa3e700f3ffd6463daa22a63f37e962749343fefaf2b6888f9ec2a0978a8a29`; the final form differs only by the
subsequently added logging-only `J2MECH` summary line, and removing that line returns `cmp=0` and the old hash.
The deterministic torque contract also rotates an otherwise identical 120-degree bend from the XY plane into XZ;
the torque vector follows the new instantaneous plane with unchanged magnitude (`plane-invariant=true`). This
directly tests that the implementation is a three-dimensional cone potential, not a fixed-plane constraint.
