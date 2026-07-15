# Experiment 4D — flexible filament gliding over a dense 2D myosin mat

**Date:** 2026-07-14 · **Branch:** `dt-convergence-study` · **Commit at start:** `705a66b3e3e14ff1354ba30b9dac7d05bccd2255`
· **Runner:** CPU sequential only (`-gpu` refused). **Hardware:** aorus, one core. **Wall-clock:** full experiment
≈ 35 min. **dt:** primary 2.5e-6, comparison 5e-6 (matched physical duration 0.5 s). **Surface:** z-only
confinement kz = 2.0 pN/nm (x/y/rotation/bending free).

**Default-off, non-canonical prototype** (`[NON-CANONICAL TWO-BODY PROTOTYPE]`), `-exp4d` /
`-twobody-flexible-mat-gliding` in `softbox/TwoBodyConverterMotor.java`. **The validated two-body motor, canonical
Lymn–Taylor chemistry + kinetic constants, binding gate, converter geometry, F8 stiffness, force ordering, RNG
behaviour, the canonical chain law, and `BoA-v1ref` are untouched** (4D is new methods only; the shared
`Multi`/`stepMulti` from 4B/4C is unchanged — 4D uses a separate `Glide2D` path). No parameter was tuned; the
bending stiffness is the canonical actin value. This is an **assay-geometry + feasibility + visualization**
experiment — **not** a density sweep, velocity fit, or tuning run.

---

## 0. Motivation and controlling outcome

Experiment 4C established recruitment + event-level polarity but used a **rigid rod** in a **narrow strip** (2.6 µm
× 0.016 µm, 42 motors) harmonically confined in **both** transverse directions. 4D corrects those assay
limitations **without changing the motor**: a genuinely **flexible chain** filament over a **true 2D motor lawn**
(ρ = 1000 µm⁻², ~3000 motors) with **z-only** surface confinement (x/y/in-plane-rotation/bending free).

**CONTROLLING OUTCOME: FEASIBILITY DEMONSTRATED — a flexible filament stays recruited to the dense 2D mat, glides
pointed-first, engages independent motors along different sections simultaneously, explores laterally and bends
gently without leaving the surface or buckling, and conserves its contour; at canonical actin stiffness the 2 µm
filament is nearly rigid, so flexible ≈ rigid.** Engagement (avgBound ≈ 0.38, continuity ≈ 0.30) is **higher than
the 4C strip** at the same nominal density (the 2D lawn offers more reachable motors). Both null controls are
clean (no-motor and binding-disabled show avgBound 0 and ~0 velocity — the mat anchors create no directed motion),
there are 0 forbidden transitions, and the contour is exactly conserved. The one substantive flexible-vs-rigid
difference is mechanical, not recruitment: the **flexible chain's compliance absorbs part of each stroke into
local bending/lag**, so its per-stroke COM displacement (+1.08 nm) is smaller than the rigid rod's (+1.88 nm).

---

## 1. Source map — the canonical flexible-chain model reused

| behaviour | source | canonical rule | Experiment 4D use |
|---|---|---|---|
| chain/segment construction | `DiffusionHarness.runChain` | `FilamentStore(nSeg)`, straight along +x, per-segment `monomerCount` | 12 segments, straight, centered on the mat |
| contour length | segLen·nSeg | segLen = (MONOMER_CT+1)·actinMonoRadius | 12 × 0.176 µm ≈ **2.106 µm** |
| segment length | `MONOMER_CT = 64` | 65·actinMonoRadius ≈ 0.176 µm | same |
| bending stiffness | `ChainBendingForceSystem` (F4) | canonical actin (Lp ≈ 17 µm), **not softened** | reused verbatim |
| persistence-length mapping | `Constants.EI = kT·Lp` | actin Lp | unchanged (⇒ 2 µm filament is nearly rigid) |
| stretching constraint | `ChainBendingForceSystem` F3 link spring | keeps adjacent segment ends together | reused; **joint gap bounded ~9.6 nm** |
| translational Brownian | `BrownianForceSystem` (`BTransCoeff`=1.0) | per-segment FDT | ON |
| rotational/bending Brownian | `BRotCoeff`=0.5, **end segments only** (interior=0) | v1 chain rule | reused verbatim |
| segment–segment force transfer | F3/F4 into the shared `forceSum`/`torqueSum` | PAIRS law | reused |
| motor force → material coordinate | `CrossBridgeSystem.bondForces` reads `boundSeg`+`bindArc`, the bound segment's local coord/uVec | per-segment | motor binds a specific chain segment; F8 to its local material site |
| chain integration ordering | zero → brownian → chain → integrate → derive | one integrate/step | **each segment integrated once/step** after all forces summed |
| surface interaction | z-confinement (the coverslip normal) | soft z restraint | per-segment `−kz·z` (z-only; x/y free) |
| spatial neighbor search | (canonical `SpatialGrid` idea) | evaluate only nearby motors | **active-set cull**: motors within the filament bbox + 30 nm margin (~377 of 3000/step) |
| `-3js` flexible export | `segments`/`myosins` channels | per-segment chain + motors | `GlideFrame2D`: 12-segment chain + 2D lawn (full articulation near the filament, anchor posts far) |

**Explicitly verified:**

- **contour is conserved** — condition A contour = **2.106 µm = the initial 2.106 µm** (exact); the F3 link spring
  holds it (max interior joint gap ~9.6 nm, bounded).
- **motor forces act on the correct local segment/material coordinate** — `bondForces` reads `boundSeg[m]` and that
  segment's own coord/uVec/segLength; binding latches `bindArc` on the nearest segment.
- **the local filament tangent is used for the signed force** — `forceDotFil = Dot(F, boundSeg.uVec)` reads the
  bound segment's local tangent (automatic in `bondForces`); binding uses the nearest-segment geometry.
- **each chain segment is integrated exactly once per step** (single `RigidRodLangevinIntegrationSystem.integrate`
  after zero → brownian → chain → gather → z-confine).
- **motor reactions sum correctly onto the chain** — the CSR gather (keyed by `boundSeg`, general over nSeg) sums
  every bound motor's reaction into the correct segment's `forceSum` (the 4b-ii/5a bit-exact template).
- **no motor binds using a stale rigid-rod axis** — the gate/nearest-segment use the live per-segment geometry.
  *(Approximation flagged: the stereospecific orientation gate ψ≈ψ_actin uses the lab frame, not the local tangent;
  valid for the semiflexible ~straight filament — bend < 1° — the distance/material-coordinate/signed-force all use
  the local segment.)*

---

## 2. Flexible filament + surface (fixed before production)

- **Filament:** a semiflexible chain, 12 segments, ~2.106 µm contour, canonical actin bending (Lp ≈ 17 µm, **not
  softened**), free ends, translational + (end-segment) rotational + internal bending Brownian ON. Initialized
  straight along +x at the mat centre.
- **Surface:** **z-only** harmonic confinement (kz = 2.0 pN/nm per segment) — the coverslip normal. **x translation
  free, y translation free, in-plane rotation + bending free; z retained** near the motor plane. No y-centering, no
  end traps, no axial trap. Realized normal fluctuation: **RMS z ≈ 1.1 nm** (the filament stays in the
  motor-accessible layer).
- **Rigid comparison (D):** a single rigid rod of the same end-to-end length (2.106 µm), same 2D mat, same
  chemistry, x/y free, z-only confinement.

---

## 3. Dense 2D motor mat

| quantity | value |
|---|---|
| requested density | 1000 motors/µm² |
| mat footprint | 3.0 µm (x) × 1.0 µm (y) = **3.0 µm²** |
| motor count N = ρ·A | **3000** |
| realized density | 1000 /µm² |
| nearest-neighbor distance | **15.4 ± … nm** (random Poisson placement, fixed seed) |
| reachable at t=0 (within gate) | ~29 motors |
| active candidates evaluated/step | **~377 of 3000** (the spatial cull) |

Anchors are placed at random (x,y) over the surface via the 3E construction (ideal head reaching `(ax,ay,0)`); a
motor binds only when the gliding filament passes within the gate reach — recruitment **emerges** from the gate.
`barbedDir` is not in the stroke sign.

---

## 4. The seven questions (primary dt = 2.5e-6, 6 episodes × 0.5 s)

| observable | A flexible | D rigid | B no-motor | C no-bind |
|---|---:|---:|---:|---:|
| avgBound | 0.379 | 0.388 | 0.000 | 0.000 |
| continuity (frac ≥1 bound) | 0.299 | 0.282 | 0.00 | 0.00 |
| per-stroke disp · p̂ (nm) | **+1.08** | **+1.88** | — | — |
| polarity | pointed-first ✓ | pointed-first ✓ | — | — |
| net velocity (µm/s) | 0.56 | 0.47 | −0.04 | 0.02 |
| mean adjacent-segment bend (°) | 0.25 | 0.00 | 0.25 | 0.25 |
| end-to-end / contour | 0.986 | 1.000 | 0.986 | 0.986 |
| contour (µm) | 2.106 | 2.106 | 2.106 | 2.106 |
| max joint gap (nm) | 9.6 | 0.0 | 9.3 | 9.7 |
| lateral y exploration (nm) | 112 | — | — | — |
| z drift (nm) | 1.1 | 1.0 | 1.1 | 1.1 |
| mean distinct bound segments | 1.24 | 1.00 | — | — |
| max distinct bound segments | **7** | 1 | — | — |

1. **Does a flexible filament remain recruited to a dense 2D mat? — Yes.** avgBound 0.38, continuity 0.30 —
   **higher than the 4C strip (0.15 at the same density)** because the 2D lawn offers more reachable motors.
2. **Correct polarity? — Yes.** Per-stroke directed displacement +1.08 nm (flexible) / +1.88 nm (rigid), both
   pointed-first; the net velocity trends positive/small (Brownian-limited at low duty, as 4C).
3. **Does flexibility increase availability/continuity? — No (essentially flexible ≈ rigid).** avgBound 0.379 vs
   0.388, continuity 0.299 vs 0.282 — the flexible chain does **not** recruit more motors. At canonical actin
   stiffness a 2 µm filament is nearly rigid (bend < 1°), so it cannot bend toward off-axis motors; flexibility
   is subtle at this scale. This is the honest physics (actin is stiff), not a defect.
4. **Bend + lateral exploration without leaving the surface? — Yes.** Gentle thermal bending (0.25° adjacent, the
   same as the free-chain control ⇒ motors add no bending), lateral y exploration ~112 nm, RMS z ~1.1 nm (stays in
   the motor layer), contour conserved — the filament explores the 2D mat laterally and stays on the surface.
5. **Do different sections engage independent motors simultaneously? — Yes.** Up to **7 distinct segments bound to
   different motors at once** (mean 1.24) — genuine multi-section engagement along the flexible filament.
6. **Does curvature help recruitment or produce snagging/buckling/conflicting forces? — Neither (no pathology).**
   No buckling or snagging (contour conserved, joint gaps bounded ~9.6 nm); at canonical stiffness the curvature is
   minimal, so it neither materially helps recruitment nor causes conflict. The chain does not fold or self-trap.
7. **How does flexible differ from rigid on the identical mat? — Nearly identical recruitment; the difference is
   mechanical compliance.** Same avgBound/continuity and both pointed-first, but the **flexible per-stroke COM
   displacement is smaller** (+1.08 vs +1.88 nm): the chain's compliance absorbs part of each stroke into local
   bending/segment lag rather than translating the whole COM — the same compliance story as the single-molecule
   two-body motor, now at the filament level.

### dt comparison (matched physical duration)

| | A avgBound | A strokeDisp | D avgBound | D strokeDisp |
|---|---:|---:|---:|---:|
| dt = 2.5e-6 | 0.379 | +1.08 | 0.388 | +1.88 |
| dt = 5.0e-6 | 0.330 | +1.15 | 0.320 | +1.98 |

Polarity, bending, and stroke displacement are dt-stable; engagement carries the ~15 % coarse-dt variation
(the inherited 4B/4C duty bias). The qualitative conclusions are dt-stable.

---

## 5. Controls

- **B — flexible no-motor:** avgBound 0.000, net velocity −0.04 µm/s (~0), gentle thermal bending 0.25 ° (the
  filament's intrinsic thermal shape). A clean free-flexible-chain reference.
- **C — flexible binding-disabled (full mat present, motors cannot bind):** avgBound 0.000, net velocity 0.02 µm/s
  (~0) — **the anchor lawn and surface geometry create no directed motion**; directedness requires binding + the
  stroke.
- **Chemistry:** 0 forbidden transitions; per-motor independent cycling (inherited from 4A/4B).
- **Force accumulation:** the CSR gather (bit-exact in 4B) reused unchanged; the chain integrates once/step.
- **Efficiency:** the active-set cull evaluates ~377 of 3000 motors/step — a true 2D lawn handled efficiently.

---

## 6. Classification

**Feasibility DEMONSTRATED (positive); the 4C assay limitations are corrected.** A genuinely flexible filament
glides over a genuine dense 2D motor mat in the correct polarity, with multi-section engagement, lateral
exploration, surface retention, contour conservation, and no buckling/snagging or pathology; both null controls
are clean. The **flexibility effect on recruitment is small** — because canonical actin at 2 µm is nearly rigid —
so flexible ≈ rigid in engagement, while flexibility slightly softens the per-stroke COM output (compliance). This
is the expected, physically honest result at canonical bending stiffness (I did not soften it to force dramatic
bending). It is not a failure: recruitment succeeds and the 2D mat improves continuity over the strip.

---

## 7. Recommendation for the next experiment

The assay geometry is now realistic (2D mat + flexible chain), so the controlling unknowns move back to **duty and
velocity**. Two defensible next steps, pick one:

- **(preferred) A proper continuity/velocity study on the validated 2D-mat assay** — measure continuity and a
  Brownian-averaged gliding velocity vs the reachable-motor count / duty (longer runs, more episodes), toward a
  velocity comparison with the fine-dt canonical curve **as a regression, not a fit**. The low single-motor duty
  (~0.013, search+recovery-dominated) remains the recruitment limiter.
- **(if the biological question is long-filament flexibility) a longer filament** (e.g. 8–15 µm, where
  Lp/contour < 1 so bending is real) to test whether flexibility then increases availability/continuity — the 2 µm
  filament here is too stiff to isolate a flexibility effect.

Do not begin that experiment in this run. Keep dt ≤ 5e-6 (engagement carries a coarse-dt bias); the per-stroke
directed displacement is the robust polarity signal.

---

## 8. Deliverables and reproduction

```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp4d -out RUN_LOGS/twobody_flexible_mat_gliding/csv   # full experiment (~35 min, CPU)
./scripts/run_lasertrap.sh -exp4d -fast                                            # quick smoke
./scripts/run_lasertrap.sh -exp4d -smoke -3js ~/Code/SoftBox/threejs_twobody4d     # viewer frames only (4 conditions)
python3 scripts/twobody4d_analyze.py RUN_LOGS/twobody_flexible_mat_gliding/csv \
        RUN_LOGS/twobody_flexible_mat_gliding/exp4d_summary.png
# 4A / 4B / 4C regressions (unchanged): ./scripts/run_lasertrap.sh -exp4a -fast ; -exp4b -fast ; -exp4c -fast
```

**Viewer** (top-down + oblique in the viewer): `python3 SoftBox/sim_server.py 8000` from `~/Code`, open
`http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → newest). Frame sets:
`~/Code/SoftBox/threejs_twobody4d_{flexible_active, flexible_control, flexible_nobind, rigid_active}` — the flexible
12-segment chain + the 3000-motor 2D lawn (bound motors highlighted; near-filament motors fully articulated; distant
motors as anchor posts to keep the 2D density visible).

**Artifacts** (`RUN_LOGS/twobody_flexible_mat_gliding/`): `exp4d_full.log`, `exp4d_summary.png`,
`csv/gliding2d_summary.csv`. Source: `softbox/TwoBodyConverterMotor.java` (`run4d` + `Glide2D`/`buildGlide2D`/
`stepGlide2D`/`measureGlide2D` + `GlideFrame2D`), one dispatch line in `softbox/LaserTrapHarness.java`.
