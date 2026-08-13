# Canonical site-normal head binding — `xHeadHat = −n_site`

**STATUS: the binding law is implemented and every gate passes; the head geometry has been CORRECTED; and
capture is still blocked — by two LEGACY gates, which is a different answer from the one this report gave
yesterday.**

**Four headline results, in order of consequence.**

1. **THE HEAD GEOMETRY WAS WRONG, and it is fixed (§F8, §1).** `R_F8` carried an unintended transverse
   component — `(+3.5, +1.5) nm` instead of `(+3.5, 0)` — putting the actin-binding point **23.19859° off the
   ellipsoid's own long axis**. Corrected to `(+3.5, 0)` with `R_CONV = (−3.5, 0)`, so
   `eBind = normalize(xF8 − xH) = xHeadHat = the ellipsoid long axis` — **one vector, three names**, verified
   to `‖Δ‖ = 1.9e−16`. The `δ` machinery is deleted: one orientation convention, no Hessian cross term.
   **The previous claim that the 23.2° offset was "a RIGID property of the head body" is RETRACTED** — it was
   the signature of a coordinate bug.
2. **The 23.2° polar dead cone was real and is GONE (§10).** The head axis now reaches **100.00 % of 4π**
   (2000 equal-area bins), with **0** bins unreachable beyond the `|χ| ≤ 89°` pole clamp. The old geometry
   could not point the head within 23.2° of `±econv` — **8.09 % of the sphere** — and **2 of 7** `every4` site
   azimuths were canonically unreachable. **All 7 are reachable now.**
3. **The law itself is sound (§2–§9, §11, §16).** Capture gates the real candidate site; the bound potential
   minimises exactly at `xHeadHat = −n_site`; the filament reaction is exact; the term lives inside the
   implicit solve; `d xF8/dq` matches finite differences through the real kernel to **1.2e−07**; and the
   monitored CPU/GPU parity gate on the corrected geometry **passes**.
4. **Capture is still ZERO — and BOTH prior explanations were wrong (§14).** *(And §14i's own "no thrust"
   reading was itself wrong — see `docs/motor/SITE_FRAME_POWER_STROKE_AUDIT.md`.)*
   - The **"anti-correlation between position and orientation" is RETRACTED**: measured directly,
     `r(d, θ) = −0.004` (old geometry) and `+0.068` (new), with the conjunction count matching independence
     in both. The earlier "0 of 81" was a small-sample artefact.
   - **"CASE C (the detached rest pose) is the cause" is SUPERSEDED**: evaluating every gate independently,
     of the 10 candidates that are both in reach *and* correctly oriented, **`g2` (the lever-angle gate)
     rejects 10/10 and `g6` (head side) rejects 9/10**. `g6` is *structurally* incompatible with the law —
     it allows the head centre 2.25 nm above the segment centre, and the canonical pose needs **6.4 nm**
     (measured). The detached rest pose is still ~100° off, but it is no longer the binding constraint.

**⇒ Do not proceed to gliding or twirling.** The next decision is about `g6` and `g2`, not about
recalibrating `(c1, c2, c3)`. What exists for visual approval is the deterministic canonical-pose fixture on
the corrected geometry, §15.

- **Worktree:** `/home/jba/Code/SoftBox` · **Branch:** `gpu-mat-bottlenecks-explicit-singlehead`
- **Date:** 2026-08-13 · **Raw:** `RUN_LOGS/motor_audit/site_normal_head_binding/`
- **Runner:** the **CPU sequential runner** throughout — plain-Java kernel calls over the host SoA arrays, no
  TaskGraph, no device transfer. **No GPU work was launched**, so the mandatory GPU crash-monitoring path was
  not entered. The device path for this feature deliberately **refuses** rather than running unvalidated
  (`ExplicitCompleteMatHarness.buildGlidingGraph` throws when `siteNormalOn()`).
- **Reproduce:**
  ```bash
  cd ~/Code/SoftBox && ./scripts/build.sh
  TDIR=$TORNADOVM_HOME/share/java/tornado
  java --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.SiteNormalBindHarness -all
  #   passes: -phase0  -gates  -capture  -viewer     knobs: -steps N  -events N  -tol DEG  -seed N  -out DIR
  ```
- **Default-OFF and byte-identical when off** (`ExplicitCompleteMatHarness.SITE_NORMAL_BIND = false`). Every
  prior path — chemistry, `xCatch`, `k_bind`, `k_det`, S2 EI, the lever joint, the detached rest potential,
  the F8 bond, the filament geometry and every other capture threshold — is untouched.

---

## F8 LONG-AXIS GEOMETRY CORRECTION (2026-08-13) — read this first

**The head's F8 point was off the ellipsoid long axis by an unintended 1.5 nm, and that single coordinate
caused every "δ = 23.19859°" statement in the first version of this report.** It has been corrected.

### The intended geometry, now explicit in the code

| quantity | value |
|---|---|
| ellipsoid semi-axes `A_SEMI` | `4.50 × 2.75 × 2.25 nm` — the long axis **is** the head-local +x axis |
| **`R_F8`** | **`(+3.5, 0) nm`** — exactly ON the long axis |
| F8 inset from the +x tip | **1.0 nm** (`4.5 − 3.5`) |
| **`R_CONV`** | **`(−3.5, 0) nm`** — antipodal, also on the long axis |
| converter arm `\|r_F8 − r_conv\|` | **7.0 nm** (was 7.6158) |

```
    eBind = normalize(xF8 − xH)  =  xHeadHat  =  ellipsoid long axis        ONE vector, three names
```

### RETRACTION

The previous version of this report stated that `xHeadHat` is "`eBind` rotated by a FIXED 23.19859° about the
head's own third axis" and called that "a **rigid property of the head body**". **That is withdrawn.** The
offset was not a property of the head; it was the signature of an unintended transverse component in a single
material coordinate. The *measurements* in that section were correct — `δ` really was constant to 2.3e−12° —
but the *interpretation* was wrong, and the correct reading is that the constant was an artefact.

### Provenance (Phase 0 archaeology)

`R_F8 = (+3.5, +1.5)` / `R_CONV = (−3.5, −1.5)` were introduced together on **2026-07-14** in commit
**`e17b5a4`** ("Two-body replacement-motor arc"), the Exp-3C corrected topology. The evidence that the
transverse component was **incidental, not intended**:

1. **The in-code comment names the wrong quantity as the design variable.** `R_CONV` is annotated
   *"converter material point (**opposite corner**), µm (|r_F8−r_conv| ≈ 7.6 nm)"* — the *separation* is the
   stated design quantity; the transverse split is a by-product of picking a "corner" of the ellipse rather
   than a point on its axis. `docs/TWOBODY_TOPOLOGY_CORRECTION.md` §1 repeats exactly this framing.
2. **No citation, and not a frozen parameter.** `docs/canonical_freeze/CANONICAL_MOTOR_PARAMETER_INVENTORY.md`
   lists only *"converter arm |r_F8−r_conv| ≈ 7.6 nm"*, provenance **"geometric construction, no citation"**,
   grade F. The transverse component is not itself an inventoried quantity.
3. **No calibration depends on it.** `docs/TWOBODY_AXIAL_GEOMETRY.md` (Exp 3D) states the displacement
   *"depends only on (φ_pre, Δθ, L_B); ψ_actin / r_F8 / r_conv / v_off shift the absolute pose
   (overlaps/preload), **not the displacement**"*, and its `flatterPts` candidate — a deliberately less
   eccentric `(3.7, 1.0)` — scored **within noise** of the refined best (stroke 6.93 nm, transverse 0.09).
4. **It was never described as an offset F8 experiment.** No report anywhere treats the head's binding face as
   deliberately off-axis.

**Verdict: unclear-but-benign provenance, no dependency, and the user's stated canonical intent governs.**

### `R_CONV` — audited separately, and it DID have to change

`R_CONV` is the converter joint C expressed in the head frame. It does **not** enter the head axis at all:
`xH = C − R(r_conv)` and `xF8 = C + R(r_F8 − r_conv)` give `xF8 − xH = R(r_F8)` for **any** `r_conv`. So the
Phase-2 identity is secured by `r_F8` alone. `R_CONV` nevertheless had to move onto the axis, for two
independent reasons that are about topology, not about mirroring the old equality:

1. **The χ mobility derivation requires `ρ = xH − C` parallel to the head axis.** `matS2SolveStepTilt` derives
   `Γ_ψχ = 0` *exactly, everywhere* from `ρ = |r_conv|·eBind`, which holds only when `r_conv ∥ r_F8`. An
   off-axis `r_conv` reintroduces a mobility cross term and the plain Itô update stops being
   Boltzmann-consistent without a spurious-drift correction.
2. **`C`, `xH`, `xF8` must stay collinear with `xH` the midpoint** — the topology recorded in
   `docs/TWOBODY_TOPOLOGY_CORRECTION.md` §1, and the assumption every viewer and steric read-out makes.

Keeping `|r_conv| = |r_F8| = 3.5 nm` is the minimal change that satisfies both. **Consequence, stated and not
tuned back:** the converter arm drops `7.6158 → 7.0000 nm` (−8.1 %) and `γ_ψ` drops **−5.3 %** (its
translational term scales by `(3.5/3.8079)² = 0.8447`), so `τ = γ_ψ/k_bind` goes `0.7234 → 0.6853 µs`.
*(An alternative that preserves the 7.6 nm arm would be `r_F8 = (+3.808, 0)`; it was not taken because the
specification fixes 3.5 nm from the centre and 1.0 nm of inset.)*

**One knob is now inert:** `CONV_ECC_SCALE` (`-converter-f8-eccentricity-scale`) scales the *transverse*
component of `r_F8 − r_conv`, which is now identically zero — the knob multiplies zero and does nothing. It is
opt-in and default 1.0, so nothing on any default path changes; it is flagged here so a future study does not
silently get a no-op.

### One source of truth

`TwoBodyConverterMotor.R_F8` / `R_CONV` are the only definitions; every packed, CPU, GPU, dimer and viewer
representation clones from them through `build3core`. A repo-wide audit found **no stale `(3.5, 1.5)` copy**
(the other `0.0015`/`0.0035` literals are viewer radii, site arcs and slack sweeps). A static initializer now
asserts the pair is axial and antipodal, so the invariant cannot drift silently.

---

## 0. THE SPECIFICATION, and what it actually constrains

The canonical stereospecific bound pose is

```
    xHeadHat = −n_site          equivalently    dot(xHeadHat, n_site) = −1
```

the head's own local **+x** axis — its ellipsoid **LONG** axis — pointing radially **INWARD**, antiparallel to
the helically informed outward actin-site normal. The F8/binding end of the head therefore faces the filament
and the head body extends outward, away from actin.

---

## 1. PHASE 0/2 — what the head-local +x axis IS *(re-measured after the correction)*

The head is a rigid body carrying two material points in its own 2-D material frame
`{â = +b̂, n̂ = +ê_up}` (`TwoBodyConverterMotor.R_F8` / `R_CONV`), **both on the ellipsoid's long axis**:

| | |
|---|---|
| `r_F8` | `(+3.50, 0) nm` — the actin-binding point, **1.0 nm inboard of the +x tip** |
| `r_conv` | `(−3.50, 0) nm` = **exactly** `−r_F8` ⇒ `C`, `xH`, `xF8` are **collinear** and `xH` is their midpoint |
| ellipsoid semi-axes | `4.50 × 2.75 × 2.25 nm`; the long axis **is** the head-local +x axis |

`matBeamGeom` builds `xF8 = C + R(d0)` and `xH = C − R(r_conv)`, so `xF8 − xH = R(r_F8)` for every
configuration — `r_conv` never enters. With `r_F8` axial that gives

```
    eBind = normalize(xF8 − xH) = R(ψ, χ)·â = xHeadHat          IDENTICALLY, for every ψ and χ
```

Measured over **400 randomised (ψ, χ) states**, `xHeadHat` taken from the kernel (`outGeom` rows 9..11):

| check | result |
|---|---:|
| **`max ‖xHeadHat − eBind‖`** | **1.923e−16** — *the identity test* |
| `max ‖xHeadHat + eBind‖` | 2.000 (i.e. it is emphatically NOT `−eBind`) |
| `dot(xHeadHat, eBind)` range | `[1.000000000000, 1.000000000000]` |
| `max ‖xHeadHat_kernel − xHeadHat_host‖` | 4.08e−14 (buffer read vs the independent analytic construction) |
| `max \|‖xHeadHat‖ − 1\|` | 2.22e−16 |
| `χ = 0` identity: `max ‖xHeadHat − R(econv,ψ)·b̂‖` | **0.000e+00** — it *is* the head's material `â` axis |
| `δ = atan2(rF8y, rF8x)` | **0.00000000°** |

> The angle `∠(xHeadHat, eBind)` prints as ~1e−06° rather than 0 purely because `acos` is ill-conditioned at
> `dot → 1`. The **vector** difference, which is not, is 1.9e−16. That is why the identity is gated on
> `‖xHeadHat − eBind‖` and not on an angle.

### One axis for mechanics AND visualization

`SiteNormalBindSystem.headAxisStep` writes `xHeadHat` into `outGeom` rows 9..11 as `normalize(xF8 − xH)` —
straight from the two points the mechanics use — immediately after the chi-aware `matBeamGeomTilt` writes rows
0..8. The capture gate, the bound target, the steric read-out and the viewer ellipsoid all read **that one
buffer**, and `SiteNormalBindSystem.xHeadHatHost` builds the same vector **analytically** from `(ψ, χ)` and the
base triad as an independent cross-check.

> **Two visualization defects, both fixed.** The exports before 2026-08-13 drew the head ellipsoid **(a)** with
> its long axis along `eBind` — which, with the off-axis `r_F8`, was 23.2° from the true long axis — and
> **(b)** centred on the midpoint of `xH..xF8`, 1.9 nm from the true head centre `xH`. The steric measurement
> in `POST_HEAD_FREEDOM_VALIDATION.md` §2 used the same wrong axis. With the axial `r_F8` defect (a) cannot
> recur even in principle: the drawn axis and the F8 direction are the same vector.

---

## 2. PHASE 1 — the capture orientation gate is now against the ACTUAL candidate site

`ChiralSiteSystem.siteGateA`, per enumerated candidate site *k*, after the unchanged accessibility / distance /
preload gates:

```
    angle( xHeadHat , −n_site[k] )  ≤  25°     i.e.    dot( xHeadHat , −n_site[k] )  ≥  cos 25°
```

`n_site[k]` is the site's own outward radial **material** normal, built from the segment's live rolling frame —
the identical reconstruction `bondForcesSurface` and `headRollStep` use. The winner criterion is unchanged
(nearest real site among those passing *all* gates), so no new selection law is introduced.

**What was removed:** the actin-blind `|ψ − ψ_actin| < 25°` test (`g1`). **What was NOT touched:** real site
enumeration, `x_site`, spatial reach (`g0`, 3 nm), F8 preload (`g4`, 2 pN), head side (`g6`), in-segment arc
(`g7`), accessibility (`g8`), one-head-per-site occupancy, chemistry, `xCatch`, and the `φ`/`θ` converter
gates `g2`/`g3`.

**One consequential follow-on.** The energy budget `g5` contained `½ k_bind (ψ − ψ_actin)²` — the energy of the
now-superseded base-frame spring. In site mode it is replaced by `½ k_bind θ_bind²`, the energy the bond will
*actually* carry under the new potential. The converter term and the 15 kT threshold are unchanged. Leaving the
old term would have gated capture on a potential that no longer exists.

### ABSOLUTELY NO PRE-CAPTURE STEERING

The gate is a **boolean acceptance test only**. Nothing in `siteGateA`, `siteCommitB` or `headAxisStep` writes a
force, a torque or a rest direction; the candidate site exerts **zero** orientational torque, **zero**
attraction and **zero** retargeting on a detached head. `SiteNormalBindSystem.siteCoupleStep` — the only place
a site-derived torque is generated — returns immediately for any motor with `boundSeg < 0`. The detached head
arrives in whatever orientation its own thermal dynamics give it.

---

## 3. PHASE 2 — the latched site frame

At capture `siteCommitB` latches `bindSite[m]` (the filament-global site index), `bindArc[m]` and
`bindAzim[m]`. The bound target is thereafter rebuilt **every step** from that stored azimuth and the
segment's **live** material frame:

```
    n_boundSite = cos(φ_site)·segY + sin(φ_site)·segZ ,   segZ = u × segY ,  φ_site = bindAzim − mirror·epsBind
    x_site      = segCentre + (bindArc − ½L)·u + R_actin · n_boundSite
    eTarget     = −n_boundSite
```

so the target translates, bends, rotates and **rolls** with the filament. Nothing lab-fixed, no `ψ_actin`, no
stored base-frame direction and no later-timestep candidate enters. (`epsBind` is the askew bound-interface
offset carried in `bindAzim`; it is removed so the target is the TRUE site normal. In the canonical zero-skew
scene `epsBind = 0` and the two coincide exactly.)

---

## 4. PHASES 3–5 — the potential, its generalized forces, and the filament reaction

```
    θ_bind = angle(xHeadHat, −n_boundSite)
    U_bind = ½ k_bind θ_bind²                     k_bind = 5.1200e−19 N·m/rad² = 512 pN·nm/rad², UNCHANGED
    λ      = k_bind θ_bind / sin θ_bind           ( → k_bind as θ → 0 )
    T_head = λ ( xHeadHat × eTarget )
    T_fil  = − T_head                             EXACTLY, from the same U_bind
```

Both torques follow from the **same** `U_bind` by virtual work. `ψ` rotates the whole head rigidly about
`econv` and `χ` rigidly about `that = e0 × econv`, so for the head-fixed `xHeadHat`,
`∂/∂ψ = econv × xHeadHat` and `∂/∂χ = that × xHeadHat`; expanded,

```
    ∂xHeadHat/∂ψ = cos χ ( econv × e0 )
    ∂xHeadHat/∂χ = cos χ · econv − sin χ · e0
    Q_ψ = λ ( ∂xHeadHat/∂ψ · eTarget )      Q_χ = λ ( ∂xHeadHat/∂χ · eTarget )
```

The two directions are orthogonal for every `χ`, which is why the orientation Hessian is diagonal. `Q_φ = 0`
for the bound branch, correctly: the target belongs to the *filament*, so it has no motor-frame carriers.
**There is ONE orientation convention in the code** — the `cos δ` / `sin δ` branches, the tilted coordinate and
the `ψ–χ` cross term were deleted with the geometry correction.

The reaction is applied through the segment-side torque slot of `bondData` (`d+9..11`) — the byte-unchanged
`CrossBridgeSystem.segGather` channel the head-roll registry already uses. It is evaluated **once per step, at
one configuration**, immediately after `bondForces`, feeding both the gather (this step) and the solver's
target (this step), so the pair is equal-and-opposite by construction. The motor side is then re-linearised
implicitly about the solver's own iterate — the identical semi-implicit asymmetry the F8 bond force already has
(its `f8` is likewise frozen from `bondForces` while the solve iterates).

**No phenomenological twirling torque is introduced anywhere.**

---

## 5. PHASE 6 — numerical treatment (the term stays inside the implicit solve)

`τ = γ_ψ / k_bind = 0.7234 µs` against `dt = 2.50 µs` ⇒ **τ/dt = 0.289**. An explicit angular kick would be
unconditionally unstable, so the term is assembled into the same Newton system as the rest of the beam, with
the exact Gauss–Newton Hessian:

```
    H_ψψ = k_bind cos²χ
    H_χχ = k_bind
    H_ψχ = 0            EXACTLY — the psi and chi head-axis directions are orthogonal for every chi
```

### Analytic vs central finite differences of `U_bind` (h = 1e−6 rad)

Swept over **4 misalignments × 4 tilt AZIMUTHS**. The azimuth sweep matters: a single tilt direction can put
the entire misalignment into one coordinate and leave the other identically zero, which tests nothing — that
is what the first version of this gate was unknowingly doing. All 16 cells pass.

Pass criterion: `|Δ| ≤ 1e−6·max(|analytic|, |FD|) + 1e−27 N·m`. The absolute floor is the central-difference
noise (`U·ε/2h ≈ 2e−30 N·m`), ten orders below the ~1.8e−19 N·m torque at the gate; components that read as
"disagreeing" are the ones that are identically zero by symmetry at that tilt azimuth.

Gauss–Newton Hessian at θ = 0: `H_ψψ = 1.864e−20`, `H_χχ = 5.120e−19`, **`H_ψχ = 0.000e+00 N·m/rad²`**.

### `d xF8/dq` through the REAL geometry kernel

Because the F8 point is the force-transmission point, its own Jacobian was re-derived rather than assumed.
Central differences through `matBeamGeomTilt` against the analytic columns
`∂xF8/∂φ = econv × (C − P)`, `∂xF8/∂ψ = econv × (xF8 − C)`, `∂xF8/∂χ = that × (xF8 − C)`:

**worst relative error 1.22e−07** over 12 random `(φ, ψ, χ)` × 3 coordinates — the FD truncation floor at
`h = 1e−7` on µm-scale coordinates. The repaired `econv` projection is intact; the legacy `eup` defect is not
reintroduced.

### Stability at the production timestep

A bound motor released at 0 / 5 / 15 / 25° misalignment and stepped through the **full production step**:

| θ₀ | steps until the cycle detached it | max single-step \|Δθ\| | θ at detach |
|---:|---:|---:|---:|
| 0° | 139 | 10.64° | 7.42° |
| 5° | 139 | 9.02° | 7.42° |
| 15° | 139 | 9.02° | 7.42° |
| 25° | 139 | 11.57° | 7.51° |

Bounded, no blow-up, and all four arms converge to **θ ≈ 7.4°** — the initial condition is forgotten within a
few steps, against a thermal amplitude `sqrt(kT/k_bind) = 5.14°`. (The 139-step bond lifetime is the unchanged
Lymn–Taylor chemistry, not a numerical failure.)

---

## 6. PHASE 7 — capture ordering (no snap, no teleportation)

The production order inside one `stepGlidingCPU` is, verbatim:

```
  matBeamGeomTilt (chi-aware geometry)  ->  headAxisStep (xHeadHat into outGeom 9..11)
    ->  siteGateA (site enumeration + spatial gates + the 25 deg ORIENTATION GATE)
    ->  siteCommitB (converter gates, then LATCH: boundSeg / bindSite / bindArc / bindAzim)
    ->  chemistry -> cock -> placeHead -> bondForces
    ->  siteCoupleStep  (U_bind target -> restC ; EXACT reaction -> bondData)
    ->  segGather (the reaction reaches filament.torqueSum)  -> integrate -> derive
    ->  matS2SolveStepTilt  (the bound potential acts on THIS step's mechanical solve)
```

At capture the head is **not moved**: only which forces exist changes. `siteCoupleStep` is a no-op for
`boundSeg < 0`, so nothing site-derived acts before the latch, and the bound potential engages on the same
step's solve immediately after it.

---

## 7. PHASE 8 — the detached rest potential is UNCHANGED

Rows 0..2 of `restC` (the native head pose resolved in the live neck frame) are not written by any new code,
and the solver's detached branch is the **verbatim legacy arithmetic on `eBind`**, `k_det` untouched. The
three regimes stay distinct:

| regime | law |
|---|---|
| **DETACHED** | weak *internal* native head preference, live neck/converter frame, `k_det` |
| **CAPTURE** | an instantaneous stereospecific **geometric test** against a real actin site |
| **BOUND** | strong **site-relative** orientation, `xHeadHat → −n_boundSite`, `k_bind` |

---

## 8. PHASE 12 — the gates

| gate | what | result |
|---|---|:--:|
| **A** TARGET | at the analytic minimum: `‖xHeadHat − (−n_site)‖ = 5.55e−17`, `θ_bind = 8.5e−07 °`, `dot = 1.000000000000000`, `Q_ψ = −1.7e−35`, `Q_χ = +2.8e−35 N·m` | **PASS** |
| **B** RIGID COVARIANCE | 9 rigid rotations of filament + site + motor together: `max \|ΔU\| = 2.59e−06 kT`, `max \|Δθ\| = 4.56e−06 °` | **PASS** |
| **C** SITE MATERIAL FRAME | filament rotated alone: `max ‖n_site(R·fil) − R·n_site(fil)‖ = 1.83e−08`; `θ` matches the prediction to `3.79e−06 °` | **PASS** |
| **D** REACTION CLOSURE | `\|T_head + T_fil\| / \|T_head\| = 9.76e−09` (float32 `bondData`); FD virtual work `W_motor + W_filament = 0` to 4–6e−09 relative on three independent axes | **PASS** |
| **E** HELICAL AZIMUTHS | **7 of 7** every4 azimuths reachable (was 5 of 7): `dot(xHeadHat, −n_site) = 1.000000000000` at every one, head centre **+3.500 nm outward** at every one, clearance uniformly **−1.000 nm** | **PASS** |
| **6** FD | generalized forces vs central differences at 0/5/15/25° × 4 tilt azimuths | **PASS** |
| **5b** F8 JACOBIAN | `d xF8/d(φ, ψ, χ)` vs central differences through `matBeamGeomTilt`, worst rel **1.22e−07** | **PASS** |
| **6c** COVERAGE | head axis reaches **100.00 %** of 4π; **0** bins unreachable beyond the pole clamp | **PASS** |
| **6b** STABILITY | implicit, bounded, relaxes to the thermal amplitude at `dt = 2.5 µs` | **PASS** |
| **0** AXIS IDENTITY | **`max ‖xHeadHat − eBind‖ = 1.9e−16`**; kernel vs independent analytic twin 4.1e−14; `χ = 0` reproduces the material `â` axis to 0.0e+00 | **PASS** |

Gate B's `ΔU` floor is set by the **float32** storage of the filament's material frame, not by the law.

### The reaction is exactly equal and opposite

```
    T_head (analytic)      = (−9.4782e−20, +5.5711e−20, +7.6680e−20) N·m ,  |T| = 1.3404e−19
    T_fil  (read back from bondData) = (+9.4782e−20, −5.5711e−20, −7.6680e−20) N·m
```

It is exact in double; the 9.8e−09 residual is the float32 precision of the shared `bondData` bond channel that
every other bond reaction already uses.

---

## 9. PHASES 9/11 — the canonical bound geometry, and the overlap question

At the canonical pose with the F8 bond holding `xF8` at the site, the head is rigid with
`xH = x_site − |r_F8|·eBind`, and since `eBind` makes the fixed angle δ with `xHeadHat = −n_site`:

```
    dot( xH − x_site , n_site )  =  |r_F8| cos δ  =  3.500 × 1  =  +3.500 nm      (exactly R_actin)
```

Measured at **all 7** every4 azimuths: **+3.500 nm at every one.** (The pre-correction geometry reached the
same +3.500 nm by the coincidence `3.808 × cos 23.2° = 3.500`; it is now exact by construction.) The head centre sits **3.5 nm
outward of the site**, i.e. 7.0 nm from the filament axis — F8 on the filament-facing end, head body outward.
That is the specification realised.

### Residual head/actin overlap, WITHOUT any steric force

| geometry | signed clearance (nm) |
|---|---|
| previous BOUND poses (`POST_HEAD_FREEDOM_VALIDATION.md` §2) | mean **−3.086**, worst **−6.542**, overlapping 94.3 % of frames |
| **canonical pose, off-axis F8 (superseded)** | −0.922 … −0.996 across the 5 reachable azimuths |
| **canonical pose, AXIAL F8 (current)** | **−1.000 at all 7 azimuths** |

**Enforcing the canonical orientation substantially reduces but does not eliminate the overlap: the mean
penetration falls ~3.2× and the worst case ~6.6×, leaving a residual ≈1 nm.** That residual is arithmetic, not
accidental: the head centre is 3.500 nm outward of the site (7.000 nm from the axis) while the ellipsoid's
transverse semi-axis is 2.75 nm and `R_actin = 3.5 nm`, so the body's transverse extent reaches ~0.9–1.0 nm
inside the cylinder. **No steric force was added**, as instructed; this is a measurement.

---

## 10. PHASE 6 — the 23.2° dead cone existed, and the correction REMOVED it

With the off-axis `r_F8`, `xHeadHat·econv = cos δ · sin χ` was bounded by `cos δ`, so the head's +x axis could
**never** point within `δ = 23.2°` of `±econv` — `1 − cos δ = 8.09 %` of the sphere — and the canonical pose
was geometrically unreachable at those sites (**2 of 7** consecutive `every4` azimuths). With `δ = 0` the bound
becomes `|sin χ| ≤ 1` and the cap is empty.

Measured by sweeping `(ψ, χ)` on a 720 × 361 grid and binning `xHeadHat` into **2000 equal-area Fibonacci
bins**:

| | before (inferred analytically) | **after (measured)** |
|---|---|---|
| solid-angle coverage of the head axis | ≤ 91.9 % of 4π | **100.00 %** |
| bins unreachable beyond the `\|χ\| ≤ 89°` pole clamp | ~8.09 % of the sphere | **0** |
| Jacobian rank of `∂xHeadHat/∂(ψ, χ)` | 2, degenerating at the cap edge | **2 everywhere**; min singular value **0.0175** = `cos 89°`, i.e. it degenerates *only* at the pole clamp |
| `every4` site normals canonically reachable | 5 of 7 | **7 of 7 (100 %)** |

Seven consecutive `every4` sites advance `7 × 54° = 378°`, so they span the full azimuthal circle — the
coverage statement is not a lucky sample.

---

## 11. PHASE 9/10 — the ellipsoid, and the viewer

The viewer's `myosins[].motor` channel scales a unit sphere by `(r, 1.5r, r)` with the **1.5-axis along
`end2 − end1`** and the centre at their **midpoint**. The head is therefore emitted as

```
    end1 = xH − a·xHeadHat ,  end2 = xH + a·xHeadHat ,  r = A_SEMI[0]/1.5 = 3.0 nm
```

giving a long semi-axis of exactly `A_SEMI[0] = 4.5 nm` **along `xHeadHat`**, centred on the **true** head
centre `xH` (verified in the emitted frames: `|end2 − end1| = 8.99984 nm`, centre = `xH`). The transverse
semi-axes render as 3.0 nm against the model's 2.75 × 2.25 nm — the viewer has one radius, so the transverse
extent is drawn slightly fat; the long axis, which is what this law constrains, is exact.

**The previous export was wrong twice**: long axis along `eBind` (23.2° off) and centred on the midpoint of
`xH..xF8` (1.9 nm from `xH`). `sim_viewer_boa.html` is **not forked or modified** — every overlay rides its
existing channels.

### Overlays

| colour (`notADPRatio`) | what |
|---|---|
| 1.00 yellow, thick | the actin filament |
| 0.45 short stubs | the sparse `every4` site lattice, one stub per real site |
| 0.55 orange chain of 4 | the explicit S2 beam, element by element |
| 0.55 orange, short/fat | the surface anchor |
| 0.00 red stub | the site under evaluation (thickens when it becomes the bound site) |
| 0.30 arrow **out of** actin | `+n_site`, the outward radial material normal |
| 0.10 thin arrow **in** | `−n_site`, **the target** |
| 0.15 12-ray fan | the 25° acceptance cone about `−n_site`, apex outside the surface |
| 0.70 ray from the head centre | **`xHeadHat`**, the head-local +x axis |
| 0.85 near-yellow thin | the head/actin closest-approach line (diagnostic; no steric force) |
| light-blue cylinder | the lever / neck (P→C) |
| large ellipsoid | the **head body**, long semi-axis 4.5 nm along `xHeadHat`, centred on `xH` |
| small sphere | the **F8 point** — it must sit at the filament-facing end of the head |
| purple sphere + link | the bound site and the F8 bond (bound frames only) |

Leave **"age colour" ticked** — colour is the viewer's only per-segment channel and the coding depends on it.

---

## 12. What was NOT done, and why

1. **No steric force was implemented.** The clearance numbers are measurements, as instructed.
2. **No twirling was run and none is interpreted.** The conservative reaction exists and is gated
   (§8 gate D); nothing beyond that was measured.
3. **`k_bind`, `k_det`, `xCatch`, chemistry, the detached rest pose and every spatial threshold are
   untouched.** Nothing was tuned to make a gate pass.
4. **The site-normal law was not run on the device**, because its prerequisite is not there — see §16. One
   monitored CPU/GPU parity gate WAS run, on the changed geometry through the kernel that IS on the device
   path, and it passes.
5. **`BoA-v1ref` is untouched**, and the production default (`SITE_NORMAL_BIND = false`) is unchanged.
6. **`(c1, c2, c3)` — the detached native head pose — was NOT recalibrated**, and the detached search was not
   retargeted toward actin. That was the point of running the assay from scratch on the corrected geometry
   first; §14 says what the answer turned out to be.

## 13. Files

**New:** `softbox/SiteNormalBindSystem.java` (kernels + host helpers),
`softbox/SiteNormalBindHarness.java` (proof, gates, coverage, funnel, correlation, bottleneck, capture, viewer).

**Modified for the site-normal law, all additive and flag-gated:**
`TwoBodyBeamAnalyticGpu.matS2SolveStepTilt` (the bound branch's target selection — the orientation coordinate
and Hessian are now a SINGLE convention, the `δ` machinery having been deleted with the geometry correction);
`ChiralSiteSystem.siteGateA` / `siteCommitB` (the orientation gate and the `g5` energy term);
`ExplicitCompleteMatHarness` (flags, buffer growth — `outGeom` 9N→12N, `restC` 3N→8N, `candArc` N→2N,
`sbP` 27→30, new `snP` — CPU wiring, and the device-path refusal).

**Modified for the F8 long-axis correction:** `TwoBodyConverterMotor.R_F8` `(+3.5,+1.5) → (+3.5, 0)` and
`R_CONV` `(−3.5,−1.5) → (−3.5, 0)` nm, plus a static initializer that asserts the pair stays axial and
antipodal. Everything else clones from these two through `build3core`; a repo-wide audit found no stale copy.

**Byte-identity when off is by construction:** `siteNormalOn()` is false ⇒ neither new kernel is called ⇒
`restC[6N+m]` stays 0 ⇒ the solver takes its verbatim legacy branch, and `sbP[27] = 0` ⇒ `siteGateA` /
`siteCommitB` take theirs. Confirmed executably on the path that runs during the entire binding search:
**detached OFF ≡ ON, max |Δ(φ, ψ, χ, beam nodes)| = 0.000e+00 over 12 motors × 5000 steps.**

---

## 13b. PHASE 7 — single-motor mechanics rebaseline on the corrected geometry

F8 is the force-transmission point, so moving it can change the mechanics. Nothing was tuned to recover any
previous number; these are the corrected values as measured.

### Stroke / `k_ext` — the VALIDATED estimator (`LiveNeckHeadProbe -reg`, deterministic, Brownian OFF)

| arm | stroke (nm) | polarity | `k_ext` (pN/nm) | θ (deg) | S2 ext (nm) | contour | stable |
|---|---:|---|---:|---:|---:|---:|---|
| legacy (`eup` axis, no χ) | −8.458 | pointed | 0.7566 | −24.853 | 39.9196 | 0.999981 | yes |
| liveLeg (χ dynamic, live-frame rest, `eup`) | −8.458 | pointed | 1.0045 | −25.269 | 39.9085 | 0.999960 | yes |
| **live512 (+ exact `econv` axis)** | **−8.458** | **pointed** | **0.7566** | **−24.853** | **39.9196** | 0.999981 | **yes** |
| chi5 (+ `k_det` = 5 — **the production arm**) | −8.458 | pointed | 0.7566 | −24.853 | 39.9196 | 0.999981 | yes |
| chi10 (+ `k_det` = 10) | −8.458 | pointed | 0.7566 | −24.853 | 39.9196 | 0.999981 | yes |

**Against the pre-correction repaired default** recorded in `RESTORED_3D_HEAD_TILT_DOF.md` §13
(`econv` + lever joint, off-axis `r_F8`): stroke `−8.381 → −8.458 nm` (**+0.9 %**), `k_ext`
`0.7211 → 0.7566 pN/nm` (**+4.9 %**), θ `−24.972 → −24.853°`, S2 extension `39.9195 → 39.9196 nm`, contour
conservation `0.999995 → 0.999981`. **Polarity is pointed-first throughout and every arm is stable.**

**Readings, as measured.**

1. **The stroke survives the geometry correction essentially unchanged** (+0.9 %), and its polarity is
   untouched. That is the expected result: Exp 3D established that the displacement depends on
   `(φ_pre, Δθ, L_B)`, not on the head's material-point placement — and this is a direct test of that claim on
   the production explicit-S2 motor rather than on the two-body prototype.
2. **`k_ext` moves +4.9 %, slightly AWAY from the independently-calibrated `Cmot` fixed-anchor reference band
   (0.60–0.64 pN/nm).** It was 0.72 before and is 0.76 now, so the agreement with that band is marginally
   worse. This is reported, not corrected: nothing was tuned, and the band was not consulted while making the
   change. The direction is consistent with a shorter converter arm (7.0 vs 7.6 nm) transmitting load through
   a slightly stiffer path.
3. **The χ arms are numerically identical to `live512`.** `k_det` (5 vs 10) does not move a BOUND-state
   measurement, which is correct by construction — `matKbindGate` sets the bound stiffness to `k_bind` and
   `k_det` only applies while detached.
4. **S2 force–extension and contour conservation are unaffected** (contour within 2e−5 of unity in every arm),
   and the fixed-site relaxation remains stationary under the true potential gradient (`rel = 4.1e−09`,
   `MechanicsRepairProbe` gate D). **No arm is unstable.**

> **A second estimator disagrees, and it is the one the codebase already labels superseded.**
> `MechanicsRepairProbe -reg` reports the repaired default at stroke **−3.972 nm**, `k_ext` **2.099**,
> θ **11.708°**. That probe prints its own warning: its 1 pN/nm fixed-site probe spring is far *softer* than
> the motor, so the settled F8 displacement is dominated by the probe and the measurement is badly
> conditioned (it gives `k_ext ≈ 105` for the pre-repair arm). **Its numbers are recorded here for
> completeness and are NOT the baseline.**

---

## 14. PHASE 11 — the natural-capture result, and the standing question

> **SUPERSEDED 2026-08-13 by §14b–§14h.** Everything below was measured with the legacy `g6`/`g2` gates still
> live. They have since been RETIRED for the site-normal motor, and the motor **does** now bind naturally.
> The measurements below remain valid *as a description of the gated state* and are what identified `g6`/`g2`
> as the blockers; the "what to do next" recommendation at the end of this section is superseded by §14b.

**This is the finding that needs your decision, and it is the one the completion rule turns on.**

### The capture funnel — which gate actually stops the candidate

Host twin of the production gate chain, evaluated on every eligible detached step against the same enumerated
candidates `siteGateA` sees. Nothing is fed back; the production step has already decided. **200 000 steps,
same seed, same scene, detached rest pose untouched — the OFF-AXIS column is the pre-correction run and the
AXIAL column is the corrected geometry.**

| gate (production order) | OFF-AXIS `r_F8` | **AXIAL `r_F8`** |
|---|---:|---:|
| eligible (detached, ADP·Pi, bindable) | 200 000 | 200 000 |
| `g7` an in-segment site exists | 200 000 | 200 000 |
| `g6` head side | 159 276 (79.64 %) | 138 265 (69.13 %) |
| `g8` accessibility (approach from outside) | 155 682 (77.84 %) | 133 714 (66.86 %) |
| `g0` `\|xF8 − x_site\| < 3 nm` | 246 (0.123 %) | **74 (0.037 %)** |
| `g4` F8 preload < 2 pN (⇒ `< 2 nm`) | 81 (0.041 %) | **23 (0.012 %)** |
| **`g1'` orientation `≤ 25°`** | **0** | **0** |
| `g2` / `g3` / `g5` | 0 | 0 |

**Spatial reach got WORSE, and that is expected arithmetic, not a regression in the law:** `|r_F8|` shrank
`3.808 → 3.500 nm`, so the F8 point now sits 0.31 nm closer to the head centre and simply reaches a site less
often. Candidates clearing the whole spatial chain fell 81 → 23.

### Conditioned on the FULL spatial chain — the decisive distribution

| | OFF-AXIS (81 candidates) | **AXIAL (23 candidates)** |
|---|---:|---:|
| mean `angle(xHeadHat, −n_site)` | 106.79° | **90.87°** |
| BEST | 41.44° | **35.03°** |

```
    AXIAL histogram (10 deg bins, n = 23):
      [30,40)=2 [40,50)=2 [50,60)=3 [60,70)=3 [70,80)=2 | [100,110)=2 [110,120)=3 [120,130)=1 [130,140)=2 [140,150)=2 [160,170)=1
```

**The distribution is now BIMODAL, and the two modes are physically identifiable.** 12 of 23 sit in
`[30°, 80°)` and 11 in `[100°, 170°)`, with the `[80°, 100°)` band empty. That is exactly what the axial
geometry predicts:

- **head body OUTSIDE the filament, F8 reaching inward to the site** ⇒ `xF8 − xH` points inward ⇒
  `xHeadHat ≈ −n_site` ⇒ the low mode. This is the canonical approach.
- **head body threaded THROUGH the filament** (the known steric defect — the head centre is inside the site's
  tangent plane on ~63 % of reachable steps, `POST_HEAD_FREEDOM_VALIDATION.md` §2) ⇒ `xF8 − xH` points
  *outward* ⇒ `xHeadHat ≈ +n_site` ⇒ the high mode near 180°.

With the off-axis F8 the head axis was 23.2° away from the reach direction and the modes were smeared into a
single lump centred at 107°. **The correction did not merely shift the mean — it separated the population into
"approaching correctly" and "threaded through the actin", and the second mode is a STERIC artefact, not an
orientation-law failure.**

### Natural captures obtained: ZERO, across three independent horizons

| run | motor-steps | detached steps within 3 nm of a real site | mean angle | BEST angle | within 25 deg | **events** |
|---|---:|---:|---:|---:|---:|---:|
| 1 motor, 200 000 steps (funnel) | 2.0e5 | 246 (0.123 %) | 106.8 deg | **41.4 deg** | 0 | **0** |
| 1 motor, 600 000 steps | 6.0e5 | 2 990 (0.498 %) | 98.3 deg | **2.34 deg** | 62 (2.07 %) | **0** |
| **12 motors, 200 000 steps** | **2.4e6** | 938 (0.039 %) | 95.4 deg | **7.24 deg** | 34 (3.62 %) | **0** |

A 180-deg control (orientation gate accepting everything) also produced 0, because the rewritten `g5` energy
budget then binds at `theta <= 28.1 deg` — the two agree, which is itself a consistency check on the
implementation.

### PHASE 9 — the position/orientation correlation, measured directly, OLD vs NEW

Matched seed and scene, 150 000 steps per arm, the head long axis taken from the **analytic** construction in
both arms (so the legacy arm is measured on its true ellipsoid axis, not on `eBind`). Every detached candidate
evaluation with `d = |xF8 − x_site| < 6 nm`:

| | OFF-AXIS `r_F8` | **AXIAL `r_F8`** |
|---|---:|---:|
| n | 5 809 | 7 751 |
| mean `θ` | 97.67° | 105.21° |
| median | 102.11° | 106.02° |
| **best** | 2.46° | **0.55°** |
| `θ ≤ 25°` | 153 (2.63 %) | 81 (1.05 %) |
| **Pearson r(d, θ)** | **−0.0043** | **+0.0682** |
| `d < 2 nm` | 222 | 301 |
| **`d < 2 nm` AND `θ ≤ 25°`** | **4** | **4** |

### RETRACTION — there is no anti-correlation, and there never was

The previous version of this report concluded that spatial proximity and correct orientation are
**"anti-correlated"**, from the observation that 0 of 81 spatially-qualified candidates were within 25°.
**That conclusion is withdrawn.** Measured directly on ~6 000–8 000 candidate evaluations per arm:

- **`r(d, θ) ≈ 0` in BOTH geometries** (−0.004 and +0.068). The conditional mean `θ` is flat across distance
  bins — 101.9 / 100.6 / 97.3 / 97.8 / 97.0 / 97.9° from `[0,1)` to `[5,6)` nm in the old arm.
- **The conjunction is consistent with INDEPENDENCE.** Old: `P(d<2) = 3.8 %`, `P(θ≤25°) = 2.63 %`, expected
  `5809 × 0.038 × 0.0263 ≈ 5.8`, observed **4**. New: expected `7751 × 0.039 × 0.0105 ≈ 3.2`, observed **4**.
- The earlier "0 of 81" was a **small-sample artefact**: at independence the expected count in that sample was
  ≈1–4, and observing 0 is not evidence of a coupling.

**So the F8 correction did not need to break an anti-correlation, because there was none to break.** What it
did do is remove a genuine 23.2° geometric error, empty the polar dead cone, and make the head axis mean the
thing its name says.

### Natural captures: still ZERO — and now we know exactly which gates block

| run | motor-steps | within 3 nm of a real site | mean θ | best θ | θ ≤ 25° | **events** |
|---|---:|---:|---:|---:|---:|---:|
| 1 motor, 200 000 steps (funnel) | 2.0e5 | 74 (0.037 %) | 90.9° (post-`g4`) | 35.0° | 0 | **0** |
| **12 motors, 200 000 steps** | **2.4e6** | 1 373 (0.057 %) | 99.2° | **4.05°** | 36 (2.62 %) | **0** |

### PHASE 10 — every gate evaluated INDEPENDENTLY, which is what identifies a structural incompatibility

The ordered funnel answers *"which gate stopped the survivors"*. That is a different question from *"which
gate is incompatible with the canonical bound pose"* — a gate can be at odds with the law and never show up,
because an earlier gate already removed the candidate. Evaluating all eight gates independently on the same
**1 373** candidates with `d < 3 nm`:

| gate | marginal pass rate |
|---|---:|
| `g0` `d < 3 nm` | 100.000 % (by construction) |
| `g3` `θ_S` | 99.417 % |
| `g8` accessibility | 46.905 % |
| `g6` head side | 44.574 % |
| `g4` preload `< 2 pN` | 29.862 % |
| **`g2` φ** | **13.037 %** |
| `g5` energy `< 15 kT` | 3.205 % |
| **`g1'` orientation `≤ 25°`** | **2.622 %** |

**Of the 10 candidates that are BOTH spatially qualified (`g0` ∧ `g4`) AND correctly oriented (`g1'`):**

| which OTHER gate rejects them | |
|---|---:|
| **`g2` φ (the lever-angle gate)** | **10 / 10 = 100 %** |
| `g6` head side | 9 / 10 = 90 % |
| `g8` accessibility | 6 / 10 = 60 % |
| `g3` θ_S | 0 / 10 |
| `g5` energy | 0 / 10 |

**Two LEGACY gates — neither of them the orientation law, and neither revisited when the site-normal law was
written — reject every otherwise-qualified candidate.**

1. **`g6` (head side) is STRUCTURALLY incompatible with the canonical pose.** It requires the head centre to
   sit less than `A_SEMI[2] = 2.25 nm` above the segment centre along `ê_up`. The canonical bound pose puts
   the head centre **3.5 nm outward of the site**, i.e. `R_actin + 3.5 = 7.0 nm` from the axis. Measured
   directly: the observed head side averages **+2.541 nm**, but **the head side the canonical bound pose would
   have at these very sites averages +6.414 nm** — **2.85× the gate's own threshold.** `g6` was written for a
   head approaching from the lawn side and sitting at or below the filament; it cannot admit a head that
   stands off the surface, which is precisely what `xHeadHat = −n_site` requires.
2. **`g2` (`|φ − φ_pre| < 25°`) rejects 100 % of them.** The lever angle needed to hold the head in the
   canonical radial pose is not the pre-stroke lever angle the gate was calibrated around.

`g5`, the energy budget rewritten in terms of `θ_bind`, rejects **none** of them — so that rewrite is sound
and is not a hidden blocker.

**This supersedes the previous "CASE C is the cause" reading.** The detached rest orientation is still ~100°
off (that is real and unchanged), but it is no longer the *binding* constraint: even when the head does arrive
correctly oriented and in reach — which happens ~10 times per 200 000 steps — two legacy spatial/lever gates
reject it every time.

### What this does NOT mean

The binding **law** is not in question — every gate in §8 passes, the geometry is self-consistent (§9), and the
overlap improves 3–6× (§9). What is in question is the **detached search**: nothing in the current model aims
the head anywhere near the pose the specification requires.

### What to do next — and the order has CHANGED

**Recalibrating `(c1, c2, c3)` is no longer the first move.** Two legacy gates must be reconciled with the
canonical bound pose first, because they reject correctly-oriented in-reach candidates *unconditionally*:

1. **`g6` (head side) — reconcile or retire.** Its threshold (`2.25 nm`) contradicts the geometry the law
   specifies (`6.4 nm` measured). This is a genuine conflict between two stated intentions, not a tuning
   choice, and it needs your decision about which one governs.
2. **`g2` (φ) — re-derive its centre.** It is centred on `φ_pre`, the pre-stroke lever angle; the canonical
   radial head pose needs a different lever angle. 
3. **Only then** revisit the detached rest pose `(c1, c2, c3)` — the ~100° CASE-C offset is real, but with
   `g6`/`g2` as they stand, fixing it alone would still yield zero captures.
4. **Not viable:** more motors / longer horizons. 2.4e6 motor-steps already gave 0, and the blocking gates are
   structural, not rate-limited.

**Recommendation: option 2.** It is the smallest change, it introduces no new machinery, and it makes the
detached preference and the bound target two statements about the *same* geometry instead of two unrelated
ones. It must be a separate task — it re-baselines every capture statistic on record.

---

## 14b. LEGACY g6/g2 RETIREMENT (2026-08-13)

**Decision, per instruction: `g6` (head side) and `g2` (`|φ − φ_pre| < 25°`) are RETIRED for the canonical
site-normal motor — and ONLY there.** Both remain live whenever `SITE_NORMAL_BIND = false`, so legacy
reproduction is unaffected.

### Why

- **`g6` is structurally incompatible with a radially outward head.** It allows the head centre
  `A_SEMI[2] = 2.25 nm` above the segment centre along `ê_up`; the canonical pose puts it **6.414 nm** there
  (measured at the same sites — 2.85×). It was written for a head approaching from the lawn side and sitting
  at or below the filament.
- **`g2` is a historical PRE-STROKE lever-angle gate**, not a stereospecific head/site geometric requirement.
  Once the articulated motor places F8 at a real site with the head long axis along `−n_site` and the preload
  in range, there is no physical reason to *also* demand the lever stay near `φ_pre`.

Both were measured rejecting the candidates that satisfied everything else: of the 10 candidates simultaneously
in reach and correctly oriented, **`g2` rejected 10/10 and `g6` 9/10**.

### How — explicit branches, and telemetry that says RETIRED

`ChiralSiteSystem.siteGateA` guards g6 with `(orientSite == 0 || keepG6 != 0)`; `siteCommitB` guards g2 with
`(orientSite != 0 && keepG2 == 0)`. **Neither is faked as "pass = true"**: the funnel and the marginal
diagnostic print them as `[RETIRED]`, so "it stopped rejecting" can never be misread as "everything now
satisfies it". `sbP[30]/[31]` (`SITE_NORMAL_KEEP_G6/G2`) re-apply them for the ablation control only and are
never set on a production path.

**The active site-normal capture chain is now:** chemistry/nucleotide eligibility · a real `every4` site exists
(`g7`) · accessibility (`g8`) · `g0` `|xF8 − x_site| < 3 nm` · `g4` F8 preload `< 2 pN` ·
**`g1'` `angle(xHeadHat, −n_site) ≤ 25°`** · `g3` converter `θ_S` · `g5` energy budget (with the site-normal
`½ k_bind θ_bind²`) · one-head-per-site occupancy. Nothing else was removed.

### PHASE 2 — the OFF path is bit-identical

The two guard lines were reverted in a scratch copy of the tree, rebuilt, and both binaries run the LEGACY
path (`SITE_NORMAL_BIND = false`, so g6 and g2 are both live) over **12 motors × 150 000 steps** with a
fingerprint covering capture decisions (step and motor of every event), bound-step count, final `(φ, ψ, χ)`,
every beam node coordinate to 17 digits, and the final bond bookkeeping:

```
    diff pre-change post-change  ->  IDENTICAL — OFF path unchanged
```

> **Scope, stated honestly:** that scene produced **0 legacy captures** in 1.8e6 motor-steps, so the
> capture-decision part of the fingerprint is verified only in the trivial sense (both zero) plus the
> by-construction argument (`sbP[27] = 0` makes both new expressions reduce literally to the legacy ones).
> The trajectory and geometry equality is a full bit-for-bit match and is the strong part.

---

## 14c. NATURAL CAPTURE AFTER GATE RETIREMENT

### The funnel, before and after (200 000 steps, one bindable motor, same seed and scene)

| gate | g6/g2 ACTIVE | **g6/g2 RETIRED** |
|---|---:|---:|
| eligible | 200 000 | 197 782 |
| `g7` site exists | 100 % | 100 % |
| `g6` head side | 44.57 % | **[RETIRED]** |
| `g8` accessibility | 66.86 % | 97.53 % |
| `g0` `< 3 nm` | 74 (0.037 %) | **626 (0.317 %)** — **8.5×** |
| `g4` preload `< 2 pN` | 23 (0.012 %) | **185 (0.094 %)** — **8.0×** |
| **`g1'` orientation `≤ 25°`** | **0** | **2** |
| `g2` φ | 0 | **[RETIRED]** |
| `g3` / `g5` | 0 | 2 / 2 |
| **ALL PASS (captures)** | **0** | **2** |

**Retiring `g6` did not merely stop one rejection — it multiplied the spatially-qualified candidate supply by
~8×.** That is the same effect from two directions: `g6` was cutting precisely the outward-standing head poses
that both reach a site *and* satisfy the canonical orientation, so removing it raises `g8`'s pass rate from
67 % to 98 % and `g0`/`g4` by 8×.

### Which gate is rate-limiting NOW

**The orientation gate `g1'` itself.** Of the **185** candidates clearing the full spatial chain, **2** are
within 25° (1.08 %); mean `angle(xHeadHat, −n_site) = 100.43°`, **best 14.34°**. `g3` and `g5` reject none of
the survivors. The bottleneck has moved from two legacy gates that were structurally at odds with the law to
the law's own stereospecific criterion — which is the intended state.

### The captures themselves

**3 natural captures** in 400 000 steps × 12 bindable motors (4.8e6 motor-steps); all on the motor nearest the
filament, all at site 154.

| event | step | `θ_bind` at −1 | **at capture** | +1 | +5 | +20 | `g3` margin | `g5` energy | azimuth class |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 0 | 35 996 | 24.02° | **24.22°** | 3.88° | 2.65° | 4.81° | 5.33° | 11.24 kT | upper |
| 1 | 181 085 | 35.39° | **11.42°** | 2.07° | 6.68° | 8.77° | 10.71° | 3.01 kT | upper |
| 2 | 394 471 | 35.03° | **20.29°** | 9.05° | 4.41° | 10.93° | 8.59° | 8.15 kT | upper |

**Every capture satisfies the 25° criterion at the moment of capture, and the bound potential pulls the head
onto the target within one step** (24.2 → 3.9°, 11.4 → 2.1°, 20.3 → 9.1°), settling near the thermal amplitude
`sqrt(kT/k_bind) = 5.1°` by +20 steps.

> The recorded `|xF8 − x_site|` (2.63/3.85/4.59 nm) and preload (2.63/3.85/4.59 pN) exceed the 3 nm / 2 pN
> thresholds because they are read **post-solve** — the freshly formed bond has already pulled the head. The
> gate saw the pre-solve values. This is the same one-step offset documented in
> `POST_HEAD_FREEDOM_VALIDATION.md` §8.

### Do we now recruit across the helical lattice? NOT YET — but the band has MOVED

**All 3 captures are at "upper" azimuth** (site normal pointing away from the lawn): lower 0 | side 0 |
upper 3. The legacy path reached **only side azimuths (0–120°)** and never lower or upper
(`CHI_AWARE_CAPTURE_ORIENTATION_AUDIT.md` §4). So the recruited band has moved to exactly where the canonical
geometry says a radially standing head must bind — but it is still **one band, not the whole lattice**, and 3
events cannot distinguish "the upper band is preferred" from "the upper band is all this scene offers". This
is a genuine open question, not a resolved one.

### Yield, stated plainly

**3 events here, 5 in the longer run (§14g) — not the 20 requested.** The combined rate is ~1 per
2–4e6 motor-steps; 20 events would need ~5e7 motor-steps. Reported as measured, not extrapolated.

---

## 14d. THE g6/g2 ABLATION (explanatory; neither gate is reintroduced)

Matched seed, scene and horizon — 12 motors × 150 000 steps:

| arm | `g6` | `g2` | captures | bound-step duty |
|---|---|---|---:|---:|
| A | live | live | **0** | 0.0000 % |
| B | RETIRED | live | **0** | 0.0000 % |
| C | live | RETIRED | **0** | 0.0000 % |
| **D (production)** | **RETIRED** | **RETIRED** | **1** | 0.0109 % |

**Neither gate alone is "the" blocker — they had to go together.** Retiring either one leaves the other
rejecting essentially every otherwise-qualified candidate, which is exactly what the independent marginal
measurement predicted (`g2` rejected 10/10 of the qualified candidates and `g6` 9/10, so removing one still
leaves ~all rejected). **Counts are small (0/0/0/1) — the pattern is clear but the ratio is not quantified**,
and this control is explanatory only.

---

## 14e. PHASE 6 — the capture moment is mechanically smooth (NO SNAP)

Per-step motion **at the capture step** against the ordinary **detached** thermal step, over 4 799 665 baseline
steps:

| quantity | at capture | detached baseline | ratio |
|---|---:|---:|---:|
| `\|Δ xH\|` | 1.6383 nm | 1.5226 nm | **1.08** |
| `\|Δ xF8\|` | 2.0707 nm | 1.7920 nm | **1.16** |
| `\|Δ S2 extension\|` | 0.1298 nm | 0.2130 nm | **0.61** |
| `\|Δφ\|` / `\|Δψ\|` / `\|Δχ\|` | 6.91° / 4.39° / 13.86° | — | — |

**No teleportation, no violent angular snap, no S2 strain spike.** The capture step is mechanically
indistinguishable from a thermal step: the head-centre and F8 displacements are within 8–16 % of the detached
baseline, and the S2 extension actually changes *less* than usual. `|Δχ| = 13.86°` is exactly the free-head
thermal increment `sqrt(2kT·dt/γ_ψ) = 13.9°`, so it is the ordinary χ step, not a kick. **The implicit bound
spring is stable**: `τ/dt = 0.274`, and `θ_bind` relaxes 24.2 → 3.9° in one step without overshoot or
oscillation (§14c table).

---

## 14f. PHASE 5 — the fine-time viewer event, verified numerically ON THE FRAMES

`threejs_sitenormal_bound_capture`: **801 frames, one per integration timestep**, capture at frame 400
(sim step 35 996, t = 89.99 ms), 400 before and 400 after. Generated by a two-pass replay of the same
deterministic trajectory — pass 1 locates the capture cheaply, pass 2 records the window. No forced binding.

Measured on the emitted frames (197 of them bound):

```
    dot(xHeadHat, -n_site)   mean +0.9921    best +1.0000    worst +0.9120
    mean theta_bind          7.20 deg
    frame 400 (capture)      24.21 deg  ->  +1: 3.88 deg  ->  +5: 2.65 deg  ->  +20: 4.81 deg
    head centre outward      +4.6 .. +8.4 nm throughout the bound window
```

**The bound ellipsoid stands radially out from the filament with its long axis on `−n_site`, F8 on the
filament-facing end, head centre outward — for essentially the whole bound window.** The outward offset
exceeds the ideal 3.5 nm because the F8 bond is a spring under thermal load, not a rigid constraint.

---

## 14g. PHASE 7 — bond lifetime and duty

Long-horizon ensemble: **1 800 000 steps × 12 bindable motors = 2.16e7 motor-steps**, giving **5 natural
captures**, all resolved (every episode detached inside the 20 000-step tracking cap).

| quantity | value |
|---|---|
| bond lifetime | **mean 264.4 steps = 661.0 µs** ; median 89.0 steps = 222.5 µs ; p10 17, p90 197 steps |
| **duty** (bound steps / motor-steps) | **0.00612 %** |
| chemical state at release | **NONE × 5** — every episode ends on the Lymn–Taylor ATP-binding terminus |
| F8 extension at release | 4.316 nm |
| **mean orientation error while bound** | **6.68°** |
| `θ_bind` at capture → +20 steps | 15.73° → 5.51° |
| azimuth class | lower 0 · side 0 · **upper 5** |

**Readings.**

1. **The bound state is orientationally well-behaved: 6.68° mean error over the whole bound window**, against
   a thermal amplitude `sqrt(kT/k_bind) = 5.14°`. The site-normal potential holds the head essentially at its
   thermal floor — it is not fighting the mechanics.
2. **The lifetime distribution is strongly skewed** (mean 264 vs median 89 steps, p10 17 / p90 197): a
   population of short attachments plus occasional long ones. **Every release is in state `NONE`** — the
   ordinary ATP-binding detachment, not a mechanical rupture — so the chemistry is doing the releasing, as it
   should.
3. **Did the orientation mechanics radically alter bond persistence? No evidence that it did.** 661 µs mean is
   the same order as the repaired explicit-S2 motor's ~350 µs single-event persistence (139 steps to detach in
   the Phase-6b relaxation arms of §5), and every release goes through the normal chemical route. **But n = 5
   is far too small to compare distributions** — this is a sanity check, not a measurement of the lifetime
   distribution.
4. **Duty is the headline limitation: 0.0061 %.** That, not the bond, is what keeps `avgBound` low in the
   gliding assay (§14i).

*(The no-snap numbers on this much larger baseline — 21 598 673 detached steps — reproduce §14e: `|Δ xH|`
ratio **1.01**, `|Δ xF8|` **1.15**, `|Δ S2 ext|` **1.04**. The capture step is a thermal step.)*

**Note on capture yield:** 5 events in 2.16e7 motor-steps — still short of the 20 requested, and reported as
measured rather than extrapolated. The rate is ~1 per 4.3e6 motor-steps.

---

## 14h. PHASE 8 — gate retirement did NOT change bound mechanics

`LiveNeckHeadProbe -reg` re-run after the retirement reproduces the axial-F8 baseline **exactly**:

| arm | stroke (nm) | polarity | `k_ext` (pN/nm) | θ (deg) | S2 ext (nm) | contour | stable |
|---|---:|---|---:|---:|---:|---:|---|
| legacy | −8.458 | pointed | 0.7566 | −24.853 | 39.9196 | 0.999981 | yes |
| liveLeg | −8.458 | pointed | 1.0045 | −25.269 | 39.9085 | 0.999960 | yes |
| live512 | −8.458 | pointed | 0.7566 | −24.853 | 39.9196 | 0.999981 | yes |
| **chi5 (production)** | **−8.458** | **pointed** | **0.7566** | **−24.853** | 39.9196 | 0.999981 | **yes** |
| chi10 | −8.458 | pointed | 0.7566 | −24.853 | 39.9196 | 0.999981 | yes |

**All five arms identical to the pre-retirement values, digit for digit** — as they must be, since the
retirement touches only capture **eligibility**, never a force, a torque or a stiffness. Stroke and `k_ext`
are therefore unchanged by gate retirement: **−8.458 nm pointed-first, `k_ext` 0.7566 pN/nm**.

---

## 14i. GLIDING COMPATIBILITY — DOES THE REVISED MOTOR STILL GLIDE?

**Answer: NOT DEMONSTRATED. The machinery runs cleanly and the motor DOES recruit, but no directed transport
is produced — and the part of that statement which is actually resolvable says the bound population exerts no
net axial thrust.**

Smoke test, **not** a campaign: `ChiralSiteHarness -glide-compat -site-normal on -eta 0.01 -seeds 3
-steps 5000`. Axial F8, repaired `econv` axis, repaired S2→lever joint, 3-D head, site-normal capture and bound
orientation, **g6 and g2 retired**, density 400 heads/µm², 12 filament segments, filament Brownian ON,
η = 0.01 Pa·s, dt = 2.5 µs, 25 % equilibration. Nothing tuned.

| seed | glide (µm/s) | avgBound | nPull | nDrag | ω_fit (rad/s) | invalid | solverFail |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 101 | +1.647 | 0.262 | 0.128 | 0.134 | −227.6 | 0 | 0 |
| 102 | +11.392 | 1.284 | 0.657 | 0.627 | +218.9 | 0 | 0 |
| 103 | −6.795 | 0.624 | 0.325 | 0.299 | +38.3 | 0 | 0 |
| **mean ± SEM** | **+2.08 ± 5.25** | **0.723 ± 0.299** | 0.370 | 0.353 | +9.8 ± 129.7 | **0** | **0** |

### 1. Is directed gliding resolved? NO — and the estimator could not have resolved it

`|mean| < 2 SEM`, and **the sign flips across seeds** (+, +, −). That is not a marginal result, it is an
underpowered one, and the reason is quantitative: **the velocity estimator has a diffusive noise floor of
~6.4 µm/s at this window.**

```
    filament contour 2.106 µm, η = 0.01 Pa·s  ->  γ∥ = 2.134e−8 N·s/m,  D∥ = 0.1929 µm²/s
    measurement window T = 3750 steps = 9.375 ms
    RMS Brownian displacement over T   = 0.060 µm
    apparent-velocity floor sqrt(2D/T) = 6.41 µm/s
```

The observed seed-to-seed SD is 9.1 µm/s — the same scale. **A 2 µm/s glide could not have been detected in
this window even if it were there.** To resolve `v ≈ 2 µm/s` needs `T ≫ 2D/v²` ⇒ **~38 600 steps per seed**
(≈8× longer; hours of CPU per seed). *That is a limitation of the smoke test I ran, not a property of the
motor* — a longer window is the fix, and it is out of scope here.

### 2. The part I claimed was resolvable — WITHDRAWN 2026-08-13

> **RETRACTED.** The paragraph below reads `nPull`/`nDrag` as a polarity statement. It is not one:
> `ChiralSiteHarness:2290` classifies by `fax * vFil`, mechanical POWER against the instantaneous (here
> Brownian-dominated) filament velocity, so it returns ~50/50 for ANY force polarity. The `fAxPull ≈ fAxDrag`
> coincidence compares two different subgroups and is not a bias measure. The correct quantity — their SUM, the
> net axial force — is **+0.077 ± 0.213 pN, `|mean|/SEM = 0.36`, i.e. UNRESOLVED, not zero.** And the
> deterministic fixture shows the site-bound stroke has the **CORRECT, azimuth-invariant** polarity.
> See `docs/motor/SITE_FRAME_POWER_STROKE_AUDIT.md`.

### 2. (superseded text) The part that IS resolvable says there is no thrust

The pulling/resisting decomposition is a **time-average over bound samples**, not a displacement slope, so it
does not carry the diffusive floor:

```
    bound heads      nPull 0.3701   nDrag 0.3531      51.2 % pulling
    mean axial force pull +3.8546e−14 N   drag +3.8452e−14 N     — equal to 3 significant figures
```

**The bound population is a 50/50 tug-of-war with no net axial bias.** At this duty the motor is not
generating directed force, which is a resolved statement and is the more informative half of the answer.

### 3. What DID work

- **The motor recruits in a many-motor scene: `avgBound = 0.72 ± 0.30`** (was structurally 0 before the gate
  retirement). This is the compatibility result the phase was for — the site-normal law composes with the full
  gliding assembly.
- **Solver health is CLEAN: 0 invalid, 0 solver failures across all three seeds.** No instability, no
  pathological vertical displacement.
- `vFilMean` tracks the fitted glide in every seed (1.91 / 11.29 / −6.49 vs 1.65 / 11.39 / −6.79), so the
  estimator is consistent with the raw filament velocity — the scatter is in the physics, not the fit.

### 4. Why recruitment is still low

`avgBound = 0.72` at density 400 is far below the canonical motor's ≈7. The funnel explains it: the
orientation gate admits **1.08 %** of spatially-qualified candidates. Retiring `g6`/`g2` moved capture from
*impossible* to *rare*; it did not make it common.

### 5. Rotation — DIAGNOSTIC ONLY

`ω_fit = +9.8 ± 129.7 rad/s`, sign flipping across seeds (−227.6 / +218.9 / +38.3), `turns` ∈ [−0.027, +0.049].
**Unresolved and not interpreted. This is NOT a twirling result.**

### 6. PHASE 10 (density) and PHASE 11 (ATP) — NOT RUN, and why

- **Phase 10's precondition is "only if the initial glide test works". It did not**, so a density sweep would
  be measuring the same diffusive noise at three densities. Not run.
- **Phase 11 (ATP control) was launched and then cancelled** once the noise floor was quantified: with the
  powered velocity unresolvable, an ATP-depleted arm cannot resolve a difference in velocity either, and with
  `[ATP] = 0` the heads cannot detach (no NONE→ATP), so its diffusive floor is not even matched to the powered
  arm. It would have cost ~2 h to produce an uninterpretable comparison. **This is my scope call, stated
  explicitly rather than silently skipped.**

**The right next experiment is a longer window, not a bigger scene:** ≥ 40 000 steps per seed at this density
would put a 2 µm/s glide above the noise, and only then are density and ATP controls meaningful.

---

## 15. PHASE 11/13 — what to open, and what is deliberately NOT run

**No twirling campaign was run and no emergent rotation is interpreted** (Phase 13). The conservative reaction
was exercised only by the deterministic virtual-work fixture in §8 gate D. No gliding campaign was run.

**A natural capture is now available to film** (§14b retired the two blocking gates), so the primary
trajectory is a real binding event rather than a fixture:

```bash
cd ~/Code && python3 SoftBox/sim_server.py 8000
#  -> http://localhost:8000/SoftBox/sim_viewer_boa.html      ("Recent" picker, newest first)
```

| run | frames | what |
|---|---:|---|
| **`threejs_sitenormal_bound_capture`** | **801** | **THE ONE TO OPEN.** A **NATURAL** binding event, one frame per timestep, capture at frame 400 (sim step 35 996). See §14f |
| `threejs_f8axial_canonical` | 175 | the canonical pose held at all 7 `every4` azimuths — a deterministic FIXTURE, not a capture |
| `threejs_sitenormal_canonical` | 125 | superseded — the same fixture on the OFF-AXIS geometry, 5 azimuths (2 were unreachable). Keep only for the before/after comparison |

**Numerical visual acceptance, measured on the 175 emitted frames themselves:**

```
    min dot( normalize(xF8 − xH) , xHeadHat )  =  0.999999983843
    max | cross(xF8 − xH, xHeadHat) |          =  6.3e−04 nm
    max | |xF8 − xH| − 3.500 nm |              =  8.0e−04 nm
```

All three residuals are exactly the frame file's own print precision (coordinates are written to 1e−6 µm =
1e−3 nm). **There is no longer any offset — fixed or otherwise — between the drawn ellipsoid and its F8
point.** In the fixture the ellipsoid's long axis is radial at every azimuth, F8 sits on the filament-facing
end and the head centre 3.5 nm outward.

Regenerate with:

```bash
cd ~/Code/SoftBox && ./scripts/build.sh
TDIR=$TORNADOVM_HOME/share/java/tornado
java --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.SiteNormalBindHarness -viewer -js threejs_f8axial
#   emits <root>_capture when a natural capture occurs, otherwise the canonical fixture above
```

`RUN_LOGS/motor_audit/f8_long_axis_correction/simviewer/README.txt` carries the overlay key and the same
commands.

---

## 16. GPU / production path

**The monitored CPU/GPU parity gate for the corrected geometry PASSES.** `matS2SolveStep` is the production
explicit-S2 solver that IS on the device graph, and the corrected `R_F8`/`R_CONV` reach it through the packed
`params`, so it is the right instrument for this change:

```bash
./scripts/gpu-crash-recorder.sh --status          # recorder RUNNING (mandatory pre-check)
./scripts/run_gpu_monitored.sh ./scripts/run_mats2solve_gate.sh
#   §5 LOWERS=YES | §6 port-vs-s2SolveM = 1.2e-09 µm | GPU-vs-mirror = 9.7e-10 µm  ⇒ PASS
```

Recorder running, exit 0, no crash, evidence under `/home/jba/gpu-crash-records/monitored-runs/`.

**The site-normal law itself still refuses the device path — and the blocker is a PREREQUISITE, not this
feature.** The complete task list of `buildGlidingGraph` is:

```
beamGeom bind bond brChan brown chain chem cock convFrame convFrame2 csrHist csrReduce csrScan csrScatter
csrZero derive gateOnly geom headRoll integ occResolve orthoY place redBlk redFin s2solve segGather siteBind
siteGate siteOcc siteSnap strokeSkew surfAzim surfPrune tzone zconf zeroAcc zslab
```

There is no `beamGeomTilt`, no `s2solveTilt` and no `kbindGate`. **The entire χ-dynamic 3-D head stack has
never been on the device graph** — not just this feature's two kernels.

### Exactly what remains (audited, 2026-08-13)

| # | kernel to wire | args | note |
|---|---|---:|---|
| 1 | `MatSoaSlice.matKbindGate` | 4 | the binding-state stiffness gate; a prerequisite of the χ head, not of this feature |
| 2 | `TwoBodyBeamAnalyticGpu.matBeamGeomTilt` | 8 | **replaces** `beamGeom` |
| 3 | `SiteNormalBindSystem.headAxisStep` | 7 | new |
| 4 | `SiteNormalBindSystem.siteCoupleStep` | 12 | new; must sit after `bond` and before `csrZero`/`segGather` |
| 5 | `TwoBodyBeamAnalyticGpu.matS2SolveStepTilt` | **15** | **replaces** `s2solve`; exactly at TornadoVM's `task()` argument cap |

Plus four new device transfers (`chiHead`, `restC`, `snP`, `gateP`), and one unquantified risk: **whether the
tilt solver lowers at all.** It is the largest kernel in the graph — a 15×16 Gauss–Jordan against the current
14×15, with per-motor scratch 240 vs 210 doubles — on a backend where the *smaller* version already requires
the documented `-Dtornado.enable.fma=false` workaround.

**Decision, per this task's own instruction ("if wiring the device path becomes substantial enough to obscure
the physics result, report it cleanly and stop after CPU compatibility; do not hack around it"): STOPPED AT
CPU.** Five kernels including a replacement of the graph's largest, four new buffers, and a real lowering risk
is a port, not a wiring step; and claiming validation would additionally require lowering, geometry-parity,
step-parity and a monitored smoke for each. `buildGlidingGraph` continues to throw when `siteNormalOn()`.

**What HAS been validated on the device:** the monitored `matS2SolveStep` CPU/GPU parity gate on the corrected
axial-F8 geometry (§16 above) — port-vs-`s2SolveM` 1.2e−09 µm, GPU-vs-mirror 9.7e−10 µm, recorder running, no
crash. That covers the geometry change reaching the device path; it does **not** cover the tilt solver or the
site-normal kernels, and is not presented as if it did.

---

