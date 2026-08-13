# Bound-motor helical geometry — visual audit

**Geometry / visualization audit. No force law, no gate, no threshold and no default was changed. Nothing was
tuned. The only code added is a read-only audit mode, its figure script, and a standalone scene viewer.**

- **Worktree:** `/home/jba/Code/SoftBox` · **Branch:** `gpu-mat-bottlenecks-explicit-singlehead` · base commit `3119000`
- **Date:** 2026-08-12
- **Raw data + console log:** `RUN_LOGS/attachment_audit/bound_motor_visual_audit/`
- **Figures:** `docs/attachment/figures/bound_motor_helical_visual_audit/`
- **Interactive scenes:** `docs/attachment/figures/bound_motor_helical_visual_audit/scenes/` + `threejs_bound_geometry/`
- **Predecessors:** `SPARSE_LONG_PITCH_ACTIN_SITE_LATTICE.md` · `FILAMENT_Z_SLAB_AND_ACCESSIBILITY_RERUN.md` ·
  `PATH_B_SITE_ACCESSIBILITY_TELEMETRY.md` · `CANONICAL_ACTIN_ATTACHMENT_AUDIT.md` ·
  `docs/twirling/ZERO_SKEW_SPARSE_LATTICE_MIRROR_GPU.md`

---

## 1. Executive verdict

| # | question | answer |
|---|---|---|
| 1 | Do the effective binding sites form **one sparse helical track**? | **YES** — 10.800 nm rise, +54.000°/site, 72.000 nm repeat, R = 3.500 nm, verified from the coordinates the capture kernel itself uses. |
| 2 | Do multiple bound motors occupy that track in the expected way? | **YES** — 12 heads bound to 12 consecutive sites, one head per site, marching around the whole circumference; and the natural run uses 25 distinct sites spread over the full length *and* the full circumference. |
| 3 | Does a bound head "face outward" according to the **local normal of its own site**? | **NO.** |
| 4 | What is the head's orientation referenced to instead? | A **LAB-FIXED** Gram–Schmidt seed axis (`x̂`, or `ŷ` when the head axis is nearly along `x̂`), applied to the head's own long axis. Actin geometry never enters. |
| 5 | Is the current geometry what we intended? | **The actin side is. The motor side is not.** The site lattice, site identity, occupancy and the site-first capture path are all correct and material-frame covariant. The **bound-head orientation is the one part that carries no site information at all.** |

**The decisive measurement (Phase 6, fixture C).** Twelve heads, each docked perfectly facing its own site
(`n̂_site · êBind = +1.0000`, i.e. **0.00°**, for all twelve), at twelve site azimuths spanning 1.8 turns.
The head's transverse "binding-face" reference `h_perp` came out as **`(+1.000, +0.000, +0.000)` — the same
lab vector — for every single one.** Its azimuth in the filament's own material frame is **flat at 0.000°**
while the site normal advances **+54.000° per site**. Same result on the real reference-pose motor
(fixtures A/B: `h_perp = (0, 1, 0)` for every head) and in the **natural gliding run** (all 5 simultaneously
bound heads: `h_perp = (0, 1, 0)`).

**There is nothing to "fix" in the display: the model genuinely has no per-site head-orientation reference.**
Three separate channels that *could* supply one are all off or absent on this path:

- **F9 / F10** (the head-axis 90°/120° perpendicularity torque and the head-`yVec`→segment-`yVec` alignment
  torque) are **identically zero** in the explicit-S2 mat motor — `xbParams[2] = j1FMT = 0`, i.e. the bond is
  a pure zero-rest Hookean point spring with **no angular term whatsoever**.
- The one bound angular constraint in the solve is `½·k_bind·(ψ − ψ_actin)²` with **`ψ_actin = 0` a per-motor
  constant** about `econv`, a lab-fixed base-triad axis. It carries no actin information.
- `ChiralSiteSystem.headRollStep` — the orientational **registry** that would tie head orientation to the site
  frame — is **exactly inert** in production (`REG_K = 0`, `HEAD_ROLL = false`), and even when enabled it
  registers head *roll* to the site's **tangential/axial** direction `b̂ = cos(ε)·û_site + sin(ε)·t̂_site`,
  **not** to the outward normal.

This is a clean, previously-unstated corollary of §9g's mechanism finding: the M0 null (the sparse helical
lattice generates no chiral torque) has an immediate geometric reason — **at this operating point the actin
azimuth is coupled to the motor only through the position of a single point, never through an orientation.**

---

## 2. Current sparse lattice geometry (Phase 0 — frozen, verified, not assumed)

Read back out of the packed run configuration at the start of the audit
(`RUN_LOGS/attachment_audit/bound_motor_visual_audit/BV_run.txt`):

```
lattice mode        : every4 (SITE_MODE=3)
axial rise          : 10.8000 nm  ( = 4 x actinMonoRadius 2.700 nm )
azimuth per site    : +54.0000 deg (raw -666.0000 deg = 4 x -166.50 deg native twist)
long-pitch repeat   : 72.000 nm (6.667 sites per 360 deg)
site surface radius : 3.5000 nm  (Constants.radius = 3.5000 nm)
site phase          : FILAMENT-GLOBAL  phi(k) = k*(twistRate*rise)
capture path        : SITE-AWARE (siteGateA -> siteCommitB; no post-hoc snap)
occupancy           : one head per (filament, site) = ON ; z slab = ON
filament            : 12 segments x 175.5000 nm = 2.1060 um contour
g0 distance 3.00 nm | g4 preload 2.00 pN | g8 accessibility tol 1.000e-06 nm | searchHalf 3
PHASE-0 HARD STOPS: all clear (this IS the current every4 sparse lattice)
```

The audit **hard-stops** unless all six of `SITE_MODE == every4`, `rise == 10.8 nm`, `Δazimuth == +54°`,
`pitch == 72 nm`, `SITE_PHASE_GLOBAL`, and `siteAwareOn()` hold. No `every3` fallback is possible, and
site-aware capture cannot be silently bypassed.

**Site frame** (from `ChiralSiteSystem`, unchanged):

```
u_site = filUVec[s]                              pointed -> barbed material tangent
n_site = cos(phi)*segY + sin(phi)*segZ           outward radial material normal   (segZ = u x segY)
t_site = mirrorSign * (u_site x n_site)          site tangential
x_site = segCentre + (localArc - half)*u_site + R_actin * n_site
```

so the whole lattice translates, bends, tumbles and **rolls** with the filament, and nothing per-site is stored.

---

## 3. What defines a bound head's orientation (Phase 1 + Phase 7 — traced, not inferred)

Per-motor geometry, all from `TwoBodyBeamAnalyticGpu.matBeamGeom` over the base triad `(b̂, ê_conv, ê_up)`:

```
uB   = eup*cos(phi) + bhat*sin(phi)        C   = P + lb*uB                       (P = the S2 beam pivot)
xF8  = C + R_econv(psi) d0                 xH  = C - R_econv(psi) rc
```

and then, verbatim from `TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit` (the kernel that writes the head body):

```java
double dx = xF8x - xHx, ... ;                                  // the head long axis
uvx = dx/L; uvy = dy/L; uvz = dz/L;                            // head uVec  ==  eBind
double ax = Math.abs(uvx) < 0.9 ? 1 : 0,  ay = Math.abs(uvx) < 0.9 ? 0 : 1,  az = 0;   // <-- LAB-FIXED SEED
double dd = ax*uvx + ay*uvy + az*uvz;
yx = ax - dd*uvx;  yy = ay - dd*uvy;  yz = az - dd*uvz;        // head yVec  ==  h_perp
```

### Phase-7 answers, one line each

| question | answer |
|---|---|
| what vector is used as the head-orientation reference? | the **lab axis `x̂`** (or `ŷ` when `\|eBind·x̂\| ≥ 0.9`), Gram–Schmidt'd against the head's own long axis `eBind` |
| is that reference global, per-motor, per-filament, or per-site? | **global** — one lab axis, shared by every motor in the scene |
| does it update with the local outward normal of the bound helical site? | **no** — `filYVec`, `bindAzim`, `bindSite` and `n_site` appear nowhere in the head-placement path |
| does it rotate with filament roll? | **no** — the standing fixture D shows a 90° material roll rotates the lab-frame site azimuth by exactly +90.0000° while the head is untouched |
| does it react on the filament, the motor, both, or neither? | **neither, orientationally.** `j1FMT = 0` ⇒ F9/F10 contribute exactly 0 N·m; the only actin↔motor channel is the F8 **point** force `k_F8·(x_site − xF8tip)` and its equal-and-opposite reaction (which does produce a segment *torque*, purely from the moment arm `R_actin·n̂`) |
| is it currently active or effectively dormant? | the lab-seeded `h_perp` **is computed and stored every step** but is **mechanically dormant**: with `j1FMT = 0` and `REG_K = 0` nothing reads it for a force or a torque. The head's *pose* is set entirely by `(phi, psi)` from the S2 solve. |

**Where an actin-referenced orientation would live if it existed.** `ChiralSiteSystem.headRollStep` already
implements the machinery: it parallel-transports a material reference `headRef` about `eBind` and applies an
equal-and-opposite couple `τ = −k_Ω·Δ` toward a preferred material direction. Two facts about it matter here:
it is **off** (`REG_K = 0`, `HEAD_ROLL = false` ⇒ the kernel returns before touching anything), and its
preferred direction is `b̂ = cos(ε_bind)·û_site + sin(ε_bind)·t̂_site` — the site's **axial/tangential** sense,
**not** `n̂_site`. So even switching it on would not, as written, make the head face its own site normal.

---

## 4. Deterministic bound-motor fixture (Phases 2 and 3)

`./scripts/run_chiral_sites.sh -bound-viz` builds the canonical Path-B scene (12-segment filament, 1200-motor
lawn, z slab on, site-aware capture on), pins segment 0's material frame so azimuths are exactly known (the
standing `-site-fixtures` idiom), and places heads so that **their own F8 anchor sits 1.00 nm outside a chosen
effective site, along that site's own outward normal**. Capture is then decided by the real kernels
`ChiralSiteSystem.siteGateA → siteCommitB → siteOccupancyResolve`, and the head body pose is written by the
real `matPlaceHeadExplicit`; the bond is then evaluated by the real `CrossBridgeSystem.bondForcesSurface`.
No trajectory is advanced.

Three fixtures, differing **only** in the head-pose rule:

| fixture | head pose | requested | captured |
|---|---|---|---|
| **A** — contiguous, `k = 1…8` | a real lawn motor at its reference binding pose, **rigidly translated** (its own internal head vector `xF8 − xH`, `\|·\| = 3.8079 nm`, identical for all 1200 motors to 2.2e-16 µm) | 8 | **6** |
| **B** — every-other, `k = 1,3,…,15` | same | 8 | **7** |
| **C** — contiguous, `k = 1…12` | **radial approach**: the head docks facing its own site (`eBind = n̂_site`), same head length | 12 | **12** |

**Version A/B answer the task's "densest clear demo" question honestly, and surface a real gate fact.**
Sequential occupancy is *not* fully achievable with a rigidly-translated reference-pose motor: the sites that
fail are exactly the ones whose normal points steeply **away from the lawn**, and they fail on **g6**, the
head-side gate `(x_H − segCentre)·ê_up < A_SEMI[2] = 2.25 nm`:

```
k=2    azim +108.000 deg, n_z = +0.951 : head centre sits  +2.780 nm above the segment centre => g6 REJECTS
k=8    azim  +72.000 deg, n_z = +0.951 : head centre sits  +2.780 nm above the segment centre => g6 REJECTS
k=15   azim  +90.000 deg, n_z = +1.000 : head centre sits  +3.000 nm above the segment centre => g6 REJECTS
```

This is not a defect of the fixture; it is the gate doing its job for a motor that has not re-posed. In the
real assay the head reaches such sites with a different `(phi, psi)` and a filament floating ~11 nm above the
lawn — which is why the accessibility telemetry finds far-side sites occupied ~27 % of bound time.

**Version C is the full-ring demo** and also the cleanest orientation test: every head is placed in the
idealized stereospecific pose, *perfectly facing its own site*. If any part of the model tied head orientation
to the site, this is the configuration where it would show.

---

## 5. The `-3js` deliverable and how to open it

Two interactive routes are produced by the same run. **One `sim_server.py` from `~/Code` serves both.**

```bash
cd ~/Code && python3 SoftBox/sim_server.py 8000
```

### (a) The dedicated audit viewer — this is the one that answers the question

```
http://localhost:8000/SoftBox/bound_motor_geometry_viewer.html
```

`SoftBox/bound_motor_geometry_viewer.html` is a single self-contained Three.js page (same `importmap` pinning
as `sim_viewer_boa.html`; the project viewer itself is **not** forked or modified). It loads the emitted scene
JSONs from `docs/attachment/figures/bound_motor_helical_visual_audit/scenes/` and draws, with independent
toggles:

- translucent actin cylinder at R = 3.5 nm + centreline;
- **every** effective site (grey) with its index `k`, occupied ones highlighted, plus the site-to-site helical track;
- bound motor heads (sphere at `xH`, marker at `xF8`);
- **`n_site`** — local outward normal (cyan);
- **`h_perp`** — head `yVec`, the binding-face reference (magenta);
- **`eBind`** — head long axis (yellow); **`t_site`** (violet, off by default);
- the **F8 bond** `xF8 → x_site` (white);
- a live configuration panel and a per-head table of `∠(n, h⊥)` and `Δphase`.

Scene picker: **C (full ring, 12 sites)**, A, B, and the **natural short-run snapshot**. Preset camera buttons
give the oblique, side and end-on views. `?scene=<name>` loads any other emitted scene.

### (b) The project's own viewer

```
http://localhost:8000/SoftBox/sim_viewer_boa.html      -> Recent picker -> threejs_bound_geometry/<fixture>
```

The same run also writes frames to `threejs_bound_geometry/{fixtureA_contiguous,fixtureB_alternate,
fixtureC_radial_ring,natural}/frame_000000.json`. `sim_viewer_boa.html` is untouched.

**Schema note (fixed after a first attempt hung the viewer — worth recording, because the trap is live for
other writers).** The unified viewer requires the **array** form:

```json
"segments": [{"id":0, "end1":[x,y,z], "end2":[x,y,z], "r":0.0035, "motorSeg":false, "notADPRatio":1.0}]
"myosins" : [{"id":0, "rod":{"end1":[…],"end2":[…],"r":…,"invisible":false},
                       "lever":{"end1":[…],"end2":[…],"r":…},
                       "motor":{"end1":[…],"end2":[…],"r":…,"state":"ADP"}}]
```

The older **flat** `{x1,y1,z1,x2,y2,z2,r,c}` form — which `ChiralSiteHarness.writeFrame` (the legacy `-3js`
movie path) still emits — fails in two places: `loadFrame` filters out every segment lacking `end1`/`end2`,
and `updateMyosinData` then throws on `m.rod.invisible`. `loadFrame`'s `.catch` swallows the exception, so
**the HUD stays on "loading…" indefinitely** — a hang, not an error. The audit writer now emits the array
form and is validated field-by-field against the viewer's actual accesses. *(The legacy `writeFrame` movie
path was left alone: it is outside this audit's scope, but it is a standing hazard and is flagged here.)*

Channel map, chosen to read in the project viewer's fixed palette: actin as full-radius `segments`; every
effective site as a thin `motorSeg` radial stub (occupied ones longer/thicker); per bound head, myosin **A**
= rod `xH→xF8` (head axis) + lever `xF8→x_site` (F8 bond) + motor marker at the site (red), and myosin **B**
= rod `n_site` stick from the site + lever `h_perp` stick from the head + motor marker at the head (purple).
The fully colour-coded version is the dedicated audit viewer.

---

## 6. Static figures

All figures are plotted by `scripts/plot_bound_motor_audit.py` **directly from the TSVs the audit emits**,
which are themselves read back out of the running kernels. No generative graphics, no molecular art.

| figure | file | what it shows |
|---|---|---|
| **1** | `fig1_sparse_site_helix.png` | filament + the sparse `every4` sites labelled `k`, oblique and side; the side view is a clean sinusoid of period 72 nm |
| **2** | `fig2_bound_motors_on_sparse_sites.png` | 12 motors bound to consecutive sites, one head per site, F8 bonds drawn |
| **3** | `fig3_bound_motors_site_normals.png` | + `n_site` arrows — the normals fan around the filament, +54° per site |
| **4** | `fig4_head_orientation_vs_site_normal.png` | **the audit figure** — `n_site` (cyan) + `h_perp` (magenta) + `eBind` (yellow) in 3-D, over a phase panel: the site normal climbs +54°/site, `h_perp` is flat at 0° |
| **5** | `fig5_end_on.png` | end-on down the axis: occupied sites march around the whole circumference. In fixture C every head's `h_perp` projects to a **point at the centre** (it lies along the filament axis); in fixture B every head's `h_perp` is the **same** arrow |
| **6** | `fig6_natural_snapshot.png` | the natural run: bent filament, 5 simultaneously bound heads with the same overlays, plus an axial-vs-azimuth map of the 25 sites the run used |
| **7** | `fig7_unwrapped_lattice.png` | unwrapped lattice: a single straight sparse diagonal, fitted slope **5.0000 deg/nm ⇒ 360° every 72.00 nm**, with `h_perp`'s azimuth flat beneath it; below, the whole 2.106 µm filament wrapped |
| **8** | `fig8_reference_pose_head.png` | fixtures A and B — the same audit with a real reference-pose motor head; the grey sites are the ones g6 refuses from that pose |

Figure 4's lower panel and Figure 5's left panel are the two decisive images.

---

## 7. Natural short-run snapshot (Phase 5)

A real Path-B gliding run — production configuration, **not** a fixture: 1200 motors, 400 heads/µm²,
dt = 2.5e-6 s, 3000 steps (7.5 ms physical), site-aware capture, z slab on, filament Brownian on.
**Runner: the CPU sequential runner** (`ExplicitCompleteMatHarness.stepGlidingCPU`), disclosed — it executes
the *same* kernels as the device path, no GPU work was launched, wall clock 92.9 s. The representative frame
is the richest frame after the first quarter of the run: **step 2345, 5 simultaneously bound heads**.
Over the run, **25 distinct effective sites were occupied** (7458 bound-head-steps), spread over the full
length and the full circumference (Figure 6, right).

| motor | k | gArc nm | azim ° | n_site | h_perp | n·h⊥ | ∠ | phase(n) | phase(h⊥) | Δphase | bond nm |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 518 | 55 | 594.0 | +90.0 | (+0.015, −0.248, +0.969) | (0, 1, 0) | −0.2482 | 104.37° | +90.0 | −14.4 | −104.4 | 2.47 |
| 622 | 106 | 1144.8 | −36.0 | (−0.006, +0.313, −0.950) | (0, 1, 0) | +0.3127 | 71.78° | −36.0 | +35.8 | +71.8 | 9.88 |
| 132 | 128 | 1382.4 | +72.0 | (+0.003, −0.077, +0.997) | (0, 1, 0) | −0.0774 | 94.44° | +72.0 | −22.4 | −94.4 | 6.65 |
| 976 | 157 | 1695.6 | −162.0 | (−0.012, −0.697, +0.717) | (0, 1, 0) | −0.6973 | 134.21° | −162.0 | +63.8 | −134.2 | 7.08 |
| 1114 | 173 | 1868.4 | −18.0 | (+0.011, +0.986, +0.164) | (0, 1, 0) | +0.9863 | 9.48° | −18.0 | −27.5 | −9.5 | 7.20 |

**All five bound heads carry the identical lab vector `h_perp = (0, 1, 0)`** at five different site azimuths
spanning 252°. (`phase(h⊥)` differs between rows only because each head sits on a *different segment*, whose
material `yVec` differs — a constant lab vector maps to different material-frame phases. That is the signature
of a lab anchor, not of site tracking.) The runtime model behaves exactly as the deterministic fixture.

---

## 8. Numeric table — deterministic fixture C (Phase 6)

12 heads, radial-approach pose, `|xF8 − x_site| = 1.0000 nm` for all of them. Lengths nm, angles deg.
Full precision in `RUN_LOGS/attachment_audit/bound_motor_visual_audit/heads_fixtureC_radial_ring.tsv`
(which also carries `t_site`, `u_site`, `xF8`, `eBind`, and the `eBind` phase columns).

| k | gArc | azim | x_site | n_site | xH | h_perp | n·h⊥ | ∠ | phase(n) | phase(h⊥) | Δphase |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 10.8 | +54.0 | (−1042.200, +2.057, +2.832) | (0.000, +0.588, +0.809) | (−1042.200, +0.407, +0.560) | **(+1.000, 0.000, 0.000)** | +0.0000 | 90.00 | +54.0 | **+0.0** | −54.0 |
| 2 | 21.6 | +108.0 | (−1031.400, −1.082, +3.329) | (0.000, −0.309, +0.951) | (−1031.400, −0.214, +0.658) | **(+1.000, 0.000, 0.000)** | +0.0000 | 90.00 | +108.0 | **+0.0** | −108.0 |
| 3 | 32.4 | +162.0 | (−1020.600, −3.329, +1.082) | (0.000, −0.951, +0.309) | (−1020.600, −0.658, +0.214) | **(+1.000, 0.000, 0.000)** | +0.0000 | 90.00 | +162.0 | **+0.0** | −162.0 |
| 4 | 43.2 | −144.0 | (−1009.800, −2.832, −2.057) | (0.000, −0.809, −0.588) | (−1009.800, −0.560, −0.407) | **(+1.000, 0.000, 0.000)** | +0.0000 | 90.00 | −144.0 | **+0.0** | +144.0 |
| 5 | 54.0 | −90.0 | (−999.000, −0.000, −3.500) | (0.000, −0.000, −1.000) | (−999.000, −0.000, −0.692) | **(+1.000, 0.000, 0.000)** | −0.0000 | 90.00 | −90.0 | **+0.0** | +90.0 |
| 6 | 64.8 | −36.0 | (−988.200, +2.832, −2.057) | (0.000, +0.809, −0.588) | (−988.200, +0.560, −0.407) | **(+1.000, 0.000, 0.000)** | +0.0000 | 90.00 | −36.0 | **+0.0** | +36.0 |
| 7 | 75.6 | +18.0 | (−977.400, +3.329, +1.082) | (0.000, +0.951, +0.309) | (−977.400, +0.658, +0.214) | **(+1.000, 0.000, 0.000)** | +0.0000 | 90.00 | +18.0 | **+0.0** | −18.0 |
| 8 | 86.4 | +72.0 | (−966.600, +1.082, +3.329) | (0.000, +0.309, +0.951) | (−966.600, +0.214, +0.658) | **(+1.000, 0.000, 0.000)** | +0.0000 | 90.00 | +72.0 | **+0.0** | −72.0 |
| 9 | 97.2 | +126.0 | (−955.800, −2.057, +2.832) | (0.000, −0.588, +0.809) | (−955.800, −0.407, +0.560) | **(+1.000, 0.000, 0.000)** | +0.0000 | 90.00 | +126.0 | **+0.0** | −126.0 |
| 10 | 108.0 | +180.0 | (−945.000, −3.500, +0.000) | (0.000, −1.000, +0.000) | (−945.000, −0.692, +0.000) | **(+1.000, 0.000, 0.000)** | +0.0000 | 90.00 | +180.0 | **+0.0** | −180.0 |
| 11 | 118.8 | −126.0 | (−934.200, −2.057, −2.832) | (0.000, −0.588, −0.809) | (−934.200, −0.407, −0.560) | **(+1.000, 0.000, 0.000)** | −0.0000 | 90.00 | −126.0 | **+0.0** | +126.0 |
| 12 | 129.6 | −72.0 | (−923.400, +1.082, −3.329) | (0.000, +0.309, −0.951) | (−923.400, +0.214, −0.658) | **(+1.000, 0.000, 0.000)** | +0.0000 | 90.00 | −72.0 | **+0.0** | +72.0 |

For all twelve: `n̂_site · êBind = +1.0000` (**0.00°** — the head faces its own site exactly), and
`n̂_site · h_perp = 0` at **exactly 90.00°** for every one. The 90° is *not* evidence of tracking: it is
forced, because `h_perp ⊥ eBind` by construction and `eBind = n̂_site` in this fixture. The discriminating
column is `phase(h⊥)`.

---

## 9. Does `h_perp` track the local site normal?

**Test.** `Δphase = azimuth(h_perp) − azimuth(n_site)`, both measured in the **filament's own material frame**
`(segY, segZ)`, so the test is frame-covariant and says nothing about the lab.

- **tracking** ⇒ `Δphase` is **constant** across sites (the head reference turns with the helix);
- **not tracking** ⇒ `Δphase` sweeps by **−54° per site** (the head reference stands still while the site normal rotates).

| arm | `Δphase` range | spread | verdict |
|---|---|---|---|
| A — contiguous, reference-pose head | [−162.000, +144.000]° | **306.000°** | sweeps −54°/site |
| B — every-other site, reference-pose head | [−162.000, +126.000]° | **288.000°** | sweeps −108° per occupied site (= −54°/site) |
| C — contiguous, radial-approach head | [−180.000, +144.000]° | **324.000°** | sweeps −54°/site |
| natural gliding snapshot | [−134.21, +71.78]° | 205.99° | uncorrelated with site azimuth |

**Answer: NO — plainly, with no hedging.** `azimuth(h_perp)` is flat to **0.000°** across all twelve heads of
fixture C while `azimuth(n_site)` advances by exactly +54.000° per site. `Δphase` therefore just re-traces
`−azimuth(n_site)`. The head-perpendicular vector does **not** rotate with the site normal.

---

## 10. What is correct

1. **The site lattice.** `every4`, 10.800 nm rise, +54.000°/site, 72.000 nm long-pitch repeat, R = 3.500 nm,
   one continuous helix with no phase discontinuity across the 11 segment boundaries, no duplicate and no
   missing site. Confirmed here from the capture kernel's own coordinates and independently by the standing
   `-site-fixtures` suite, re-run green during this audit.
2. **Site occupancy.** One head per `(filament, site)`. Twelve heads bound to twelve consecutive sites in the
   fixture, and `k±1` remain independent physical sites. Nothing double-occupies.
3. **Site-first capture.** The banner reports `bindPath = site-aware`; the legacy centreline-acceptance +
   post-hoc snap is not wired. `g0`/`g4`/`g8` are evaluated against the **actual site**.
4. **Head location.** The bound head sits outside the actin surface facing its own site, and the F8 bond runs
   from the head's F8 anchor to the material site — the bond's moment arm is `R_actin·n̂_site`, which is why an
   off-axis bond can generate an axial torque at all.
5. **Material-frame covariance of the actin side.** Site position, site normal, site tangential and site
   identity are all rebuilt from the live `filUVec`/`filYVec` every step, so they translate, bend, tumble and
   roll with the filament. Nothing per-site is stored, and no lab axis enters the actin side.
6. **The runtime model uses the same geometry as the fixture** — verified by the natural snapshot, not assumed.

## 11. What is wrong

1. **The bound-head orientation is referenced to a lab axis, not to the local site.** `matPlaceHeadExplicit`
   builds the head's transverse reference by Gram–Schmidt of a hard-coded `x̂`/`ŷ` seed against the head's own
   long axis. **This is the single defect the audit was called to find.**
2. **No angular channel connects actin to the head on this path.** `j1FMT = 0` ⇒ F9 (the head-axis
   perpendicularity torque) and F10 (the head-`yVec` → segment-`yVec` alignment torque) are identically zero.
   The head's pose responds to actin *only* through a point force at `xF8`.
3. **The one "binding-face" spring in the solve is actin-blind.** `½·k_bind·(ψ − ψ_actin)²` with
   `ψ_actin = 0`, a per-motor constant about the lab-fixed `ê_conv`. (This was already flagged in
   `CURRENT_STATE §9d`; this audit shows what it costs geometrically.)
4. **The existing registry would not fix it as written.** `headRollStep` is inert (`REG_K = 0`), and its
   preferred direction is the site *tangential/axial* sense `b̂ = cos(ε)·û_site + sin(ε)·t̂_site`, not `n̂_site`.
5. **A secondary, smaller item, surfaced rather than hunted:** `g6` (`head centre < 2.25 nm above the segment
   centre`) is evaluated against the **segment centre**, i.e. against the centreline, not against the site or
   the actin surface. A rigidly-posed motor is therefore refused at the top-of-filament sites purely on the
   head-centre height. In the live assay other degrees of freedom compensate, but g6 remains one more gate
   that cannot see the site it is gating.

**Not wrong:** the site lattice, site occupancy, head location, the F8 reference direction, the local actin
frame hookup, and the runtime display. The defect is localized to **head orientation**.

---

## 12. Recommended next code change

**One focused change, default-off, small, and directly testable — do not bundle it with anything else.**

> **Give the bound head a per-site orientational reference: replace the lab-fixed Gram–Schmidt seed in
> `matPlaceHeadExplicit` with the bound site's own material frame, and (separately) supply the couple that
> makes it mechanical.**

Concretely, in two independent steps so each can be gated on its own:

1. **Step 1 — make `h_perp` site-referenced (kinematic only, zero mechanical effect today).**
   For a **bound** head (`boundSeg ≥ 0`), seed the Gram–Schmidt with the site's own material direction
   instead of `x̂`: `seed = t̂_site` (or `û_site`), reconstructed from `bindAzim` + the live segment frame
   exactly as `bondForcesSurface` already does. For an **unbound** head, keep the existing lab seed verbatim.
   Because `j1FMT = 0` and `REG_K = 0`, this is **provably trajectory-inert today** — a fact that should be
   *gated*, not assumed: assert byte-identical `bondData`, filament pose and bind decisions over a bounded
   run. That converts the audit's finding into a correct, covariant *representation* at zero physical risk,
   and it is the prerequisite for anything that reads head orientation later.

2. **Step 2 — supply the missing coupling, behind a flag, and measure it.** The machinery already exists:
   `ChiralSiteSystem.headRollStep` with `REG_K > 0`, equal-and-opposite into the segment torque slot. Two
   sub-decisions must be made explicitly and recorded, because the current code answers them by default and
   the answer has never been examined:
   - **which direction is preferred** — as written it is `b̂ = cos(ε)·û_site + sin(ε)·t̂_site` (axial/tangential).
     If the intended physics is "the head faces the site", the preferred direction must involve `n̂_site`, and
     that is a *change of law*, not a parameter. Decide and document it before turning `REG_K` up.
   - **what `k_Ω` should be.** `ChiralSiteHarness`'s diagnostic default is `2.0e-21 N·m/rad ≈ kT/rad²`. It is a
     transparent diagnostic value, not a measured one; any production use needs a stated basis.

   This is exactly follow-up **(2)** already named in `docs/twirling/ZERO_SKEW_SPARSE_LATTICE_MIRROR_GPU.md §14`
   ("exercise `REG_K` > 0, the registry couple that would convert site azimuth into a head-orientation
   constraint, i.e. supply the coupling §9g shows is absent"). **This audit independently confirms that the
   coupling is absent and identifies precisely where it is missing**, and it adds the reason the M0 null was
   inevitable at this operating point: the actin azimuth reaches the motor only through the position of one
   point.

**Explicitly NOT recommended now:** changing `g6`, changing `g0`/`g4`, adding a steric law, retuning any
angular threshold, or turning `REG_K` on in production before step 1 lands and its inertness is gated.
Nothing in the canonical or Path-A configuration is affected by this audit, and nothing was changed by it.

---

## 13. What was added, and the regression position

**New files only, plus one additive harness mode. No existing kernel, force law, gate, threshold or default
was modified. `BoA-v1ref` is byte-clean. Path A and the canonical gliding path are untouched.**

- `softbox/ChiralSiteHarness.java` — additive `-bound-viz` mode (`runBoundViz` + the `BHead`/writer helpers)
  and its flags `-bound-viz-dir`, `-bound-viz-steps`, `-bound-viz-heads`; `-3js <dir>` now routes to this mode
  when `-bound-viz` is given. Every other mode's dispatch is unchanged.
- `scripts/plot_bound_motor_audit.py` — the figures.
- `bound_motor_geometry_viewer.html` — the standalone audit viewer (`sim_viewer_boa.html` is a symlink to the
  BoA canonical viewer and was **not** touched).

**Regression (both re-run during this audit, both green):**

- `./scripts/run_chiral_sites.sh -site-fixtures` → **PASS** on all of A/C/C′/A′/A″/D/B/E/E′/F, including
  196 sites over 12 segments with `duplicates = 0`, `gaps = 0`, `max |d(axial) − rise| = 1.4e-13 nm` and
  `max azimuth deviation = 0.000000 deg`.
- `./scripts/run_chiral_sites.sh -fixtures` (the 24 chiral-site mechanism fixtures) → **24 PASS, 0 FAIL —
  ALL GATED CHECKS PASS**, the same as before this task.

---

## Appendix — commands

```bash
# the whole audit: lattice freeze + hard stops, orientation trace, 3 deterministic fixtures,
# the natural CPU snapshot, the numeric tables, all TSV/JSON scene data and the -3js frames
./scripts/run_chiral_sites.sh -bound-viz -bound-viz-steps 3000

# figures, straight from the emitted coordinates
python3 scripts/plot_bound_motor_audit.py

# interactive: one server from ~/Code serves both viewers
cd ~/Code && python3 SoftBox/sim_server.py 8000
#   audit viewer   http://localhost:8000/SoftBox/bound_motor_geometry_viewer.html
#   project viewer http://localhost:8000/SoftBox/sim_viewer_boa.html  -> threejs_bound_geometry/<fixture>

# standing regression guard for the lattice + capture path
./scripts/run_chiral_sites.sh -site-fixtures
```
