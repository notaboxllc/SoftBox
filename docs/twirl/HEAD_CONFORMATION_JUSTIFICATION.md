# The head-side conformational origin of the stroke skew — justification

**Status (2026-09-08): the standing justification for the `epsStroke` twirl result.** The chirality that
reproduces the experimental twirl pitch is introduced as a small tangential offset. This note argues that the
offset is a faithful representation of a **conformational change of the myosin head**, and states exactly what
is and is not derived.

## 1. The two formulations

| | actin-side (`-stroke-skew`, `epsStroke`) | head-side (`-f8tan`, `HeadConformationSystem`) |
|---|---|---|
| what moves | the bound SITE azimuth (`bindAzim += mirror*eps`) | the head's binding point `x_F8`, by `delta` along `t_hat = u_hat x n_hat` |
| defensibility | awkward — actin does not move its own binding site | a conformational change of the head |
| at eps = 1.5 deg / delta = 0.092 nm | identical bond force (zero-rest spring: moving either endpoint is the same force) | |

`delta = R*eps` with `R = 3.5 nm`: **eps = 1.506 deg <=> delta = 0.092 nm = 0.92 Angstrom**, against a ~5 nm
working stroke. The chiral component is ~2 % of the stroke.

## 2. The physical reading

> The actin-binding interface lies **off** the head's long axis — as it does in the real protein, where the
> actin-binding surface (loop 2, the helix-loop-helix, the cardiomyopathy loop) sits on a FACE of the motor
> domain, not at the tip on its symmetry axis. At the power stroke the head **rolls about its long axis** by a
> few degrees. The roll carries the off-axis interface ~1 Angstrom tangentially, around the filament. The actin
> site is a material point and cannot follow, so the cross-bridge acquires a circumferential strain; the
> resulting force at 3.5 nm radius torques the filament. Summed over bound heads, that is the twirl.

Structural numbers: interface offset `rho`, roll `alpha`, with `delta = rho*alpha`.

| rho (nm) | alpha for delta = 0.092 nm |
|---|---|
| 1.0 | 5.3 deg |
| 1.5 | 3.5 deg |
| 2.0 | 2.6 deg |

## 3. Why the IMPLICIT implementation is exact, not an approximation

The implementation imposes the tangential displacement and **pins the head axis** to its pre-shift value
(`HeadConformationSystem` writes `outGeom[G_XHAT]` before displacing `x_F8`). That is not a fudge; it is what
the explicit geometry would produce, for one structural reason:

> **A roll about the head's long axis leaves the long axis direction invariant.**

- `x_hat_head` IS that axis. Rolling about it does not change it.
- The "stick straight out" constraint is a PURE COUPLE, `T = lambda (x_hat_head x e_target)` with
  `e_target = -n_hat_site` — it constrains only `x_hat_head`, and is therefore **blind to head roll**.
- So in the explicit model a roll `alpha` moves the off-axis interface by `rho*alpha` and leaves the kbind
  couple untouched. The implicit model imposes the same interface motion and leaves the same couple untouched.

They agree term by term. Being a couple, the constraint also has **no application point**, so there is no
"does it still act at the same place relative to the centre of mass" question to answer — a couple is the same
about every point.

**The one omission is the drag cost of rolling the head**, and it is negligible:

| rho | alpha | gamma_roll | roll torque | vs bond torque (1 pN at 3.5 nm) |
|---|---|---|---|---|
| 2.0 nm | 2.6 deg | ~4.7e-26 N.m.s | 2.2e-24 N.m | **0.062 %** |
| 1.5 nm | 3.5 deg | ~4.7e-26 N.m.s | 2.9e-24 N.m | **0.083 %** |

Three orders of magnitude below the bond torque, over a ~1 ms stroke.

**Consequence of the pin:** after the shift the head-body `uVec` (bond side) and `x_hat_head` (kbind side)
differ by `delta/|r_F8|` ~ 1.5 deg. **That divergence IS the conformational change** and is intentional. The
pin is written into the kernel so the behaviour does not depend on task ordering.

## 4. What is NOT derived — state this plainly

- **`delta` is an input, not a prediction.** The implicit model takes it. The explicit build (move `r_F8` off
  axis, drive a real roll) would tie `delta = rho*alpha` to two structural quantities and make it a prediction.
  What can be claimed: the `delta` required to match experiment is **~1 Angstrom**, a reasonable magnitude —
  not that the model derives it.
- **Handedness comes from the SENSE of the roll**, a structural chirality of the conformational change. It is
  what the mirror control flips, and it is asserted, not derived.
- **Measured behaviour differs from the actin-side version.** At `delta` = 0.092 -> 0.184 nm the twirl changes
  by only -4 %, where `epsStroke` roughly doubles over the equivalent range. Force-equivalence holds; torque
  equivalence does not (moving `x_F8` changes its lever arm about the head centre). Unexplained.

## 5. Head geometry — a stated limitation

**The model's head is a SPHERE dynamically.** Drag is `6*pi*eta*R_head` with `R_head` = 5 nm; the ellipsoid
`A_SEMI = {4.5, 2.75, 2.25} nm` never enters the drag or the bond. `A_SEMI` is used only for (a) rendering,
(b) one binding-gate threshold (`A_SEMI[2]` = 2.25 nm), (c) diagnostics.

Two problems follow:

1. **The drawn ellipsoid asserts an orientation the mechanics never uses.** The kbind latch holds
   `x_hat_head` antiparallel to the site normal — the head "sticks straight out" radially. In the real
   acto-myosin complex the motor domain's long axis lies roughly ALONG the filament with the converter at the
   barbed-proximal end. That geometry has been attempted and produced unexplained failures; it is not what the
   current model implements.
2. **The three head sizes disagree**: drawn semi-major 4.5 nm, drag sphere 5 nm, equal-volume sphere 3.03 nm.

**Recommendation:** render the head as a plain sphere at the drag radius. It is honest about what the model
is (an isotropic blob with a designated binding point), removes the implied and unvalidated orientation, and
makes the picture consistent with the dynamics. This is a rendering change plus a decision on the `A_SEMI[2]`
gate threshold; it changes no mechanics.

## 6. Where the numbers come from

Rigid single-segment filament (no chain coefficients, no torsional coupling — nothing uncalibrated),
rho = 2000 motors/um^2, eta = 0.1 Pa.s. `epsStroke` ladder: odd/eps = -1.32 / -1.41 / -1.49 / -1.51 at
eps = 1 / 2 / 4 / 8 deg (near-linear, mildly saturating), giving **eps ~ 1.4-1.6 deg** for the experimental
2.1 turns/um. Head-side at delta = 0.092 nm: 2.67 turns/um, pitch 0.375 um (single seed, 0.6 um).
Experimental comparator: Beausang 2008, 0.47 +/- 0.19 um, ~2.1 turns/um — see the averaging-convention and
protein-identity caveats in `ACTIN_SITE_LATTICE_LITERATURE_BASIS.md` s9.
