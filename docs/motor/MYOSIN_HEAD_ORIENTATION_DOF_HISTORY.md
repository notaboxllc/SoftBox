# Myosin head orientational DOF — history, loss, and restoration plan

**Archaeology and reconstruction planning. NO physics, default, kernel or parameter was changed. The only
code added is a read-only probe (`softbox/HeadOrientationDofProbe.java`) and its figure script.**

- **Worktree:** `/home/jba/Code/SoftBox` · **Branch:** `gpu-mat-bottlenecks-explicit-singlehead` · base commit `3119000`
- **Date:** 2026-08-12
- **Raw:** `RUN_LOGS/motor_audit/head_orientation_dof_history/`
- **Figures:** `docs/motor/figures/head_orientation_dof_history/`
- **Reproduce:** `java @$TORNADOVM_HOME/tornado-argfile --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.HeadOrientationDofProbe`
  then `python3 scripts/plot_head_orientation_dof.py`
- **Predecessors:** `docs/attachment/BOUND_MOTOR_HELICAL_GEOMETRY_VISUAL_AUDIT.md` ·
  `docs/attachment/HELICAL_SITE_NORMAL_BINDING_DECISION_BRIEF.md` ·
  `docs/attachment/CANONICAL_ACTIN_ATTACHMENT_AUDIT.md`

---

## 1. Executive verdict

**Yes — the older motor genuinely allowed one fixed motor's head to explore the full 2-sphere, and the freedom
was lost. It was not lost in a GPU port, a solver reduction or an accident: it was lost in the deliberate
2026-07-14 topology replacement of the articulated sphere-head motor by the two-body converter motor. What is
undocumented is the *consequence* — nowhere does any report state that the replacement collapsed the head's
orientational manifold from a 2-sphere to a single lab-fixed great circle.**

| | historical (sphere-head) | current (explicit-S2 two-body) |
|---|---|---|
| head representation | a genuine `RigidRodBody` sub-body | an algebraic function of (φ, ψ) |
| head orientation DOF | **3** (2 axis + 1 roll), dynamic | **1** (ψ), algebraic |
| integrated? | yes — shared `RigidRodLangevinIntegrationSystem` | **never integrated** |
| Brownian torque | yes, body-frame, all 3 axes | **none** |
| rotational drag | yes, Stokes sphere `8πηR³` | **none** |
| eBind reachable set | **2-sphere, 97.8 % of 4π measured** | **one great circle, 2.9 % of 4π** |
| every4 site azimuths facable within 25° | **20 of 20 = 100 %** | **6 of 20 = 30 %** |

**The 30.1 % mask reported in the decision brief is therefore an artifact of the 2026-07-14 topology change,
not a property of myosin and not a property of the helical lattice.** Restoring the DOF removes it *naturally*
— every site azimuth becomes reachable to within ~2° with no lawn disorder, no new tolerance and no new
stiffness. **`RAND_BASE_AZ` is not needed** and, on the evidence below, was itself introduced as a workaround
for this very restriction.

**But restoration is not a local repair.** The missing DOF was removed *together with* the entire head
topology; the current head has no inertia-free rigid body, no drag tensor, no Brownian channel and no place in
the 14-DOF implicit solve. See §15–16 for the smallest faithful path and §14 for what must be revalidated.

---

## 2. Current motor orientation DOFs

Base triad, from `TwoBodyConverterMotor.buildGlide2D`: `b̂ = +x̂` (filament axis), `ê_up = +ẑ` (substrate
normal), `ê_conv = ê_up × b̂ = +ŷ`. **Lab-fixed and shared by all 1200 lawn motors** (`RAND_BASE_AZ = false`).

| quantity | meaning | changes eBind? | rotation axis | dynamic? | Brownian? | drag? | stiffness | detached | bound |
|---|---|---|---|---|---|---|---|---|---|
| **ψ** | head/converter angle | **YES — it *is* the eBind angle** | `ê_conv` (fixed) | yes (solve) | yes, `γ_ψ` | `γ_ψ` | `k_bind(ψ−ψ_actin)`, 512 pN·nm/rad² | yes | yes |
| **φ** | neck-lever angle about the pivot | no (moves C, not the head's direction) | `ê_conv` (fixed) | yes (solve) | yes, `γ_φ` | `γ_φ` | none direct | yes | yes |
| **θ = ψ − φ** | converter coordinate | derived | — | derived | — | — | `k_conv(θ−θ_s)`, 128 pN·nm/rad² | yes | yes |
| **θ_s** | nucleotide rest angle (DIRSWING) | via ψ | — | switched by `matCock` | no | — | — | — | bound |
| beam nodes `1..M` | explicit S2 | **no** — moves the pivot P only | — | yes (solve) | yes, `γ_node` | yes | `ks`, `kb` | yes | yes |
| `b̂`, `ê_up`, `ê_conv` | base triad | defines the plane | — | **static** | no | no | — | — | — |
| **h_perp** (head `yVec`) | head transverse ref | no | — | **no** — lab Gram–Schmidt slave | no | no | none | yes | yes |
| **F9 / F10** | head-axis / head-roll alignment torques | would | — | **`j1FMT = 0` ⇒ identically zero** | — | — | — | — | — |
| **`headRef` / REG_K** | head roll registry | no (roll only) | `eBind` | `HEAD_ROLL=false` | optional | head `bRotGam` | `REG_K = 0` | yes | yes |

**CURRENT DIMENSION OF THE eBIND ORIENTATION MANIFOLD = 1** (a circle inside a 2-D linear subspace).

## 3. Proof that current eBind is planar

Analytic, from `geomC`/`geom2D`: `xF8 − xH = R_econv(ψ)·(b̂·rF8x + ê_up·rF8y)`, hence

```
eBind = R_econv(ψ) · p̂1 ,     p̂1 = normalize(b̂·rF8x + ê_up·rF8y) = (+0.9191, 0, +0.3939)
```

Gated against the **live `matBeamGeom` kernel** at ψ ∈ {0, 0.37, −0.61}: max deviation **1.396e-14**, and
`max |eBind · ê_conv| = 0.000e+00` **exactly**. φ does not appear — it moves the converter point C, not the
head's direction. Sweeping *both* coordinates over a full turn (130 501 samples, deliberately far beyond
anything the mechanics permit, so this is an **upper bound**):

```
covariance eigenvalues : 0.500693  0.499307  0.000000     ⇒ rank 2 (the plane)
equal-area coverage    : 74 of 2592 bins  =  0.359 sr  =  2.9 % of 4π
```

**Figure 1** (`fig1_current_ebind_locus.png`) shows the locus: a single great circle.

## 4. The last model with true 3-D eBind freedom — and it is not lost to history

**It is the canonical SPHEREHEAD articulated motor, and it is still in the tree and still runnable.**
`CURRENT_STATE §1` even names it the ratified default stack; what changed is that the *campaigns* moved to the
explicit-S2 motor. So no commit needs to be resurrected — the reference implementation is live.

Its head is sub-body `3m+2` of `MotorStore.body`, a `RigidRodBody`:

- **integrated** by `RigidRodLangevinIntegrationSystem.integrate` — body-frame overdamped Langevin,
  `ω = T/γ_rot` on all three body axes, orientation advanced by the v1 `moveThing` scalar update of `uVec`
  *and* `yVec` (full 3-D rotation, then renormalised);
- **Brownian torque** on all three axes from `BrownianForceSystem.brownianForce` (FDT, `sqrt(2kTγ/dt)`);
- **rotational drag** from `DragTensorSystem.sphereDragSI` — the Stokes sphere `γ_rot = 8πηR³`;
- and — the load-bearing identity — `CrossBridgeSystem.bondForces` reads exactly this `uVec` as the head axis
  (`htip = hc + ½·HEAD_LEN·hu`). **The historical `eBind` *is* the head sub-body's `uVec`.**

Measured (`HeadOrientationDofProbe`, one anchored detached motor, 4 ms, dt = 1e-5, full Brownian, shared
systems only, no filament):

```
covariance eigenvalues : 0.347563  0.329000  0.323437     ⇒ rank 3, near-isotropic
equal-area coverage    : 2535 of 2592 bins  =  12.290 sr  =  97.8 % of 4π
```

**Figure 2** and the **Figure 3 overlay** make the loss unambiguous: a filled sphere with one red circle on it.

## 5. Historical coordinate definitions

State per head sub-body: `coord` (3), `uVec` (3, unit), `yVec` (3, unit ⊥ `uVec`); `zVec = uVec × yVec`.
Orientation is carried as a **material triad**, not as angles, so it is covariant by construction and has no
gimbal or plane restriction. The update (`RigidRodLangevinIntegrationSystem`):

```
body torque  bt = Rᵀ·τ_lab + randTorque          (all three components)
body ω       bw = bt / bRotGam                    (per-axis rotational drag)
uVec ← normalize( uVec + yVec·(bw_z·dt) + zVec·(−bw_y·dt) )     ← 2 axis DOF
yVec ← normalize( uVec·(−bw_z·dt) + yVec + zVec·(bw_x·dt) )     ← 1 roll DOF
```

Constraints acting on it: `MotorJointSystem` J1 (lever–head positional joint + rest-angle spring),
`TailAnchorSystem`, and when bound `CrossBridgeSystem` F8 (spring), **F9** (head axis → 90° from the segment
axis, `j1FMT = 0.4`) and **F10** (head `yVec` → segment `yVec`).

## 6. Where the DOF was lost

There is no single "regression" commit; there is one **replacement**:

| commit | date | what it did |
|---|---|---|
| `a5c67cd` | 2026-07-01 | last sphere-head-era motor change ("promote the f̂-directed sphere-head neck-stroke to the DEFAULT myosin") |
| **`e17b5a4`** | **2026-07-14** | **"Two-body replacement-motor arc (3A–3G, 4A–4D)" — introduces `TwoBodyConverterMotor` with the (φ, ψ) algebraic head. This is the transition.** |
| `1b227c0` | 2026-07-15 | explicit MD-informed S2 (4G) — adds 3M beam DOF, all **positional** |
| `caffc19` | 2026-07-16 | analytic CPU solver promoted into production gliding |
| `22c69dc` | 2026-07-17 | device-resident explicit single-head slice — the GPU port **inherits** the reduction, it does not cause it |
| `3769ff6` | 2026-07-24 | chiral sites + head-roll DOF + **`RAND_BASE_AZ`** (see §13) |

**Bracket:** last model with 2-D eBind freedom = the sphere-head motor (still live); first planar model =
`e17b5a4`. Diff content: a 3-body articulated chain whose head is an integrated rigid body is replaced by a
2-body head–converter–lever whose head pose is `xF8 = C + R_econv(ψ)d₀`, `xH = C − R_econv(ψ)r_c`. The head's
`uVec`/`yVec` survive only as *outputs* (`matPlaceHeadExplicit`), written each step and never integrated.

**Which terms disappeared:** the head's rotational drag from the equations of motion (the tensor is still
computed and still passed to `bondForcesSurface`, but nothing integrates against it); the head's Brownian
torque; two of its three orientational coordinates; and — separately, on this path — F9/F10, since
`xbParams[2] = j1FMT = 0`.

## 7. Was it deliberate?

**The topology change was deliberate and well documented; the orientational consequence was not documented at
all.** The two-body arc's stated purpose (`CURRENT_STATE §9b`) was a *stiffer, mechanically recognisable*
motor — the sphere-head's externally observable compliance was far softer than intended. Exp 3C is described
as achieving "a genuine 2-DOF head–converter–lever motor", framing 2 DOF as a **gain** over 3A's 1 DOF. The
comparison against the sphere-head's 3-DOF rigid-body head is never made; `docs/MOTOR_MODELS.md`'s DOF table
counts only `(3M+2)` beam + φ + ψ and does not list the sphere-head motor at all.

**Classification: deliberate topology simplification, with an undocumented and (on the evidence of the last
three audits) unnoticed side effect.** Not a GPU shortcut — the GPU port is three commits downstream and
inherits it. Not a solver reduction — the implicit solver *gained* DOF (14), all positional.

## 8. What is missing, precisely

1. **Two orientational coordinates of the head long axis** (`eBind` out-of-plane tilt).
2. **The head's Brownian torque** on those coordinates.
3. **The head's rotational drag** entering the EOM (the *value* still exists and is still read by the bond
   kernel — only its dynamical role is gone).
4. **F9** — the head-axis alignment torque, `j1FMT = 0` on this path.
5. **F10 / roll** — separate concern, `REG_K = 0` (see `BOUND_MOTOR_HELICAL_GEOMETRY_VISUAL_AUDIT`).

## 9. Parameter provenance for the missing DOF

| parameter | value | units | code location | meaning | detached | bound | calibrated? |
|---|---|---|---|---|---|---|---|
| `HEAD_R` | 0.010 | µm | `MotorStore.HEAD_R` | head sphere radius | ✓ | ✓ | v1-inherited geometry |
| `HEAD_LEN` | 0.020 | µm | `MotorStore.HEAD_LEN` | head sub-body length; F8 tip at `+½·HEAD_LEN·uVec` | ✓ | ✓ | v1-inherited |
| head rotational drag | `γ_rot = 8πηR³` = **2.513e-24** N·m·s/rad at η = 0.1 Pa·s | N·m·s/rad | `DragTensorSystem.sphereDragSI` | Stokes sphere; the *second* drag formula in the project, introduced by the head | ✓ | ✓ | derived, not fitted |
| head Brownian rotational scale | `BRotCoeff` = 0.5 | — | `Constants.BRotCoeff`, applied in `assembleArticulated` | v1 persistence-length knob | ✓ | ✓ | v1 tuning knob (flagged as such in `Constants`) |
| Brownian torque amplitude | `sqrt(2kTγ/dt)` | N·m | `BrownianForceSystem` | FDT | ✓ | ✓ | derived |
| **F9 alignment coefficient** `j1FMT` | **0.4** (canonical), **0** (explicit-S2) | fracMove rate | `xbParams[2]`; `AxLockGateHarness`, `CanonicalMotorHarness` | head axis → 90° from the segment axis | — | ✓ | v1-inherited |
| F9 rest angle | 90° frozen (`f9Frozen`, `xbParams[9]=1`) | deg | `CrossBridgeSystem.bondForcesSurface` | perpendicularity maintainer | — | ✓ | settled in `CANONICAL_STROKE_DISAMBIGUATION` |

**So the missing DOF already had a rotational drag, an FDT-consistent Brownian torque, and a bound
orientational restoring torque — all with provenance.** It did **not** have a *stereospecific detached*
orientational stiffness (the head diffuses freely when detached), and it did **not** have an actin-normal
restoring torque: F9's reference is the filament **axis** (a polar constraint), never a site normal.

## 10. Detached-head orientation search: old vs current

| | current | historical |
|---|---|---|
| samples | 130 501 (exhaustive sweep) | 10 000 (4 ms, dt = 1e-5) |
| covariance rank | 2 (plane); locus is 1-D | **3, near-isotropic** (0.348/0.329/0.323) |
| solid angle | 0.359 sr (**2.9 %**) | 12.290 sr (**97.8 %**) |
| anisotropy | total — a single circle | mild; near-uniform coverage |
| continuity | continuous in ψ only | continuous over the sphere |

The historical head is essentially a freely-reorienting sphere on a compliant tether; the J1 joint spring and
the anchor bias its *position*, not its facing direction. In 4 ms it visits all but 57 of 2592 bins.

## 11. Relation to S2 "floppiness" — the key distinction

**Positional freedom is 3-D in both models; orientational freedom is not.** The explicit S2 beam adds `3M`
node coordinates, so the pivot `P` — and therefore `C`, `xF8` and `xH` — move in genuine 3-D. But
`matBeamGeom` builds the head from the **static base triad** `frame[0..8]`, never from the beam's distal
tangent:

```
uB = ê_up·cos φ + b̂·sin φ        C = P + l_b·uB        xF8 = C + R_econv(ψ)·d₀
```

`P` (from `nodes[M]`) enters as a pure **translation**. `convFrameStep`'s own javadoc confirms the intent —
"the beam nodes, the clamped base tangent `g4Tan`, the anchored base point `g4E` … stay in `frame` and are
read unrotated by the solver."

**So the S2 beam can bend anywhere in 3-D and eBind still cannot leave its plane.** This is the exact coupling
the analytic reduction lost: in a physical S2→converter→head chain, bending the S2 *reorients* what it
carries. Here it only *moves* it.

## 12. Relation to the helical-site normal law

Measured directly (`site_reach.tsv`, **Figure 4**), for **one fixed motor**, over the 20 distinct `every4`
azimuths, with the historical 25° stereospecific tolerance:

| | azimuths facable within 25° | best angle, worst azimuth |
|---|---|---|
| **current** | **6 / 20 = 30.0 %** | 90.00° (azimuths 0° and 180° — orthogonal, unreachable) |
| **historical** | **20 / 20 = 100 %** | **2.19°** |

The historical motor reaches *every* site normal to within ~2° — bottom, side and top alike — from a single
fixed anchor, with no lawn disorder. **Restoring the DOF dissolves the 30.1 % mask entirely and removes the
motivation for `RAND_BASE_AZ` as a fix.**

## 13. `RAND_BASE_AZ` — history and classification

- **Introduced:** `3769ff6`, 2026-07-24 — the *same* commit that added discrete actin sites, the head-roll DOF
  and askew binding. It post-dates the DOF loss by ten days.
- **Its own comment:** *"a SCENE control for the shared-base-frame artifact, not physics … Nothing about the
  motor's internal mechanics changes — only which way its (fixed, anchored) base plane faces."*
- **Production use: none.** It appears only in the `run_chiral_sites.sh` usage banner, the discrete-site
  findings doc, and `CANONICAL_ACTIN_ATTACHMENT_AUDIT` — which records it as *"used only as a control arm in
  the chiral campaign."* No campaign has ever run with it on.

**Classification: R3 — a workaround for the missing head orientational freedom**, with R2 (diagnostic control)
framing. The phrase it was written against — "the shared-base-frame artifact" — *is* the planar-eBind
restriction seen from the lawn side. It is not documented as biological lawn disorder, and no report justifies
either its default or its absence.

**It should not be used to hide the missing internal DOF.** Real lawn disorder may well be independently
justified, but that is a separate argument requiring its own provenance.

## 14. Validation impact of restoration

| validation | class | why |
|---|---|---|
| S2 force–extension, beam contour conservation | **likely invariant** | positional; untouched by a head orientation coordinate |
| converter angle θ, DIRSWING rest-angle switch | **likely invariant** | θ = ψ − φ unchanged if ψ is retained as one of the coordinates |
| axial stroke amplitude / polarity (3D, 6.9 nm) | **must rerun** | the stroke is read at the F8 tip, whose position depends on head orientation |
| whole-crossbridge stiffness `k_ext` ≈ 0.64 pN/nm | **must rerun** | a new compliant orientational mode adds series compliance — this is the single biggest risk |
| blind tweezers step/stiffness recovery (3G/3G-A) | **must rerun** | derived from the above |
| isolated equilibrium / FDT | **must rerun** | a new Brownian channel must be shown FDT-consistent |
| force-dependent lifetimes, catch–slip | **likely invariant** | driven by `forceDotFil`, not by head facing |
| ATP/ADP chemistry, Lymn–Taylor | **likely invariant** | no coupling to head orientation |
| capture statistics / recruitment | **likely to change** | that is the point |
| gliding velocity, density saturation | **must rerun** | recruitment and per-bond geometry both move |
| CPU/GPU parity | **must rerun** | new hot-kernel structure ⇒ triggered per `CPU_GPU_VALIDATION_POLICY` |
| twirling / chiral torque | **unknown — do not pre-judge** | a new orientational channel that couples to the site frame is exactly what §9g found absent |

**Verdict: this is a core-motor revalidation, not a local repair.** The two-body arc's mechanical
identity — skeletal stiffness, a 6.9 nm axial stroke, blind-recoverable — was its whole justification, and a
new compliant orientational mode is precisely the kind of thing that destroyed it in 4E.

## 15. Restoration options

### Option A — restore the historical coordinate and its mechanics into the current topology

Give the head back its `RigidRodBody` orientation: integrate `uVec`/`yVec` with the shared integrator, its
Stokes-sphere rotational drag and its FDT Brownian torque, and couple it to the converter through the existing
J1-style joint. `ψ` then becomes a *diagnostic* readout rather than a generalized coordinate.

- **coordinates:** +2 (axis) +1 (roll) per head, replacing ψ
- **parameters:** all historical, all with provenance (§9) — `HEAD_R`, `8πηR³`, `BRotCoeff`, F9 `j1FMT = 0.4`
- **effect on eBind:** full 2-sphere; site normals 100 % reachable
- **solver:** the head leaves the 14-DOF implicit block and rejoins the explicit rigid-body integrator ⇒ a
  **structural change to the hot kernel** and to the dt ceiling (the explicit head was one of the reasons the
  two-body motor went implicit)
- **GPU cost:** moderate; the shared integrator already lowers
- **burden:** the full §14 "must rerun" list
- **risk:** highest — it partly reverses the 2026-07-14 replacement and may reintroduce the softness the
  two-body arc was created to remove

### Option B — add the minimal equivalent coordinate in the current representation *(recommended)*

Keep the analytic head and the implicit solve; add **one** coordinate: a rotation `χ` of the motor's own
converter plane about `ê_up` — i.e. make `ê_conv` a *per-motor dynamic variable* instead of a lab constant.
Then

```
eBind = R_eup(χ) · R_econv(χ)(ψ) · p̂1
```

sweeps a 2-parameter patch of the sphere, and at `χ = 0` the model is **byte-identical to today**.

- **coordinates:** +1 per motor (χ), dynamic
- **parameters:** drag for χ can be **derived** the same way `γ_φ`/`γ_ψ` are (head translation at radius
  `l_b`, plus head rotation) — the same construction, not a new fit; Brownian follows by FDT
- **effect on eBind:** 2-D patch; whether it covers *all* azimuths depends on χ's range, which must be
  measured, not assumed
- **solver:** 14 → 15 DOF, same structure, same implicit machinery
- **burden:** §14, but the stroke/stiffness risk is far lower because the axial mechanics are untouched at
  χ = 0 and χ is orthogonal to the stroke plane
- **honest caveat:** this is **not** the historical mechanism. It is the minimal coordinate that restores the
  lost *capability* in the current representation. It must be labelled as such.

### Option C — new DOF, explicitly new physics

Only if A and B are both rejected: a fitted out-of-plane head tilt with its own stiffness. **No provenance
exists for such a stiffness** (§9 found none), so this would be new physics and should be resisted.

## 16. Recommended minimal restoration path

1. **Do not enable `RAND_BASE_AZ`** as the fix. It is an R3 workaround (§13) and would mask the real defect.
2. **Option B**, default-off behind one flag, `χ = 0` byte-identical to today.
3. Derive χ's drag by the *same* construction as `γ_φ`/`γ_ψ`; gate FDT on the new channel before anything else.
4. Re-measure, in order: FDT → stroke amplitude/polarity → `k_ext` → capture statistics. **Stop if `k_ext`
   moves materially** — that is the 4E failure mode and it invalidates the two-body arc's whole rationale.
5. Only then re-open the helical-site normal law (`HELICAL_SITE_NORMAL_BINDING_DECISION_BRIEF`), where the
   25° tolerance and the 512 pN·nm/rad² stiffness are already established and reusable unchanged.
6. Keep the sphere-head motor as the **reference oracle** for what the restored DOF should look like — it is
   live and its detached search envelope is now measured.

## 17. Hard unknowns

1. **Whether χ's reachable range covers all azimuths under real mechanics.** §12's 100 % figure is the
   *historical* motor's; Option B's coverage is bounded by χ's own dynamics and has not been measured.
2. **Whether restoring orientational compliance re-softens `k_ext`.** 4E showed recruitment gains and stroke
   loss share one compliance. The same trap plausibly applies here, and this audit cannot settle it.
3. **Whether the two-body arc's authors considered and rejected the 3-D head.** No document mentions it; it
   may have been an unstated simplification or simply not noticed.
4. **Whether lawn orientational disorder is independently justified.** Separable from this audit, but it must
   not be conflated with the internal DOF.
5. **The detached-head Brownian scale `BRotCoeff = 0.5`** is flagged in `Constants` as a v1 persistence-length
   tuning knob, not a measured quantity — it would carry into any restoration and deserves its own provenance
   check.
