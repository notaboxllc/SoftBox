# Explicit-S2 Gliding + Helical Surface Binding / Twirling

**Authoritative report for the dynamic-assay port** (2026-07-23, branch `gpu-mat-bottlenecks-explicit-singlehead`).
Sole Markdown report for this task. Ports the validated off-axis surface-binding drive
(`docs/CONTINUOUS_HELICAL_SURFACE_BINDING_AND_TWIRLING_FINDINGS.md`) into the **explicit-S2 single-head gliding
lineage** and asks: during *dynamic* bind–stroke–release gliding, does off-axis actin-surface attachment make the
filament rotate/twirl? A mechanism test, not a calibration. **Noncanonical, flag-gated, default-off,
byte-identical when disabled. No canonical default, parameter, chemistry, S2/stroke mechanics, dt, RNG, event
ordering, or `MotorModel.CANON_VERSION` changed.** No parameter was tuned to produce rotation.

---

## 1. Baseline revision and dirty status

- **Baseline commit:** `e217c79`, branch `gpu-mat-bottlenecks-explicit-singlehead`.
- **Dirty at start:** the prototype increment's edits (`MotorStore.bindAzim`, `CrossBridgeSystem.bondForcesSurface`,
  `BindingDetectionSystem` surface methods, `HelicalSurfaceTwirlHarness`) plus pre-existing unrelated doc/script
  edits.
- **This task's diff (additive):** `softbox/TwoBodyBeamAnalyticGpu.java` (+106: `matSurfaceAzim`,
  `matSurfaceStericPrune`; 0 deletions); `softbox/ExplicitCompleteMatHarness.java` (+48/−3: flag statics, `ExMat`
  fields, `packExMat` allocations, `stepGlidingCPU`/`buildGlidingGraph` surface wiring + the flag-gated bond swap;
  the 3 deletions are a fluent-chain break, behavior-preserving when off); **new**
  `softbox/ExplicitTwirlGlidingHarness.java`, `scripts/run_explicit_twirl.sh`, this report, `RUN_LOGS/explicit_twirl_*`.
  Feature-off is byte-identical (fixtures 1–2).

## 2. Runner and hardware

- **aorus**, Java 21 + TornadoVM 4.0.1-dev PTX, **NVIDIA GeForce RTX 5070**.
- **Port fixtures + dynamic campaign:** CPU sequential runner (disclosed). *(The campaign was run under the old,
  flag-broken launch; the full device graph is now available — §18 — so it may be re-run device-resident.)*
- **CPU/GPU equivalence:** the two new hot kernels run **device-resident** in a minimal TaskGraph, and the **full**
  surface-ON gliding graph also runs device-resident with the corrected flags (bailout disabled → no silent
  fallback). Requires `-Dtornado.enable.fma=false` (§18).

## 3. Binding-path port (before → after)

The explicit-S2 gliding step (`ExplicitCompleteMatHarness.stepGlidingCPU` / `buildGlidingGraph`, over
`TwoBodyConverterMotor.Glide2D`) already computes the cross-bridge force with the **lumped
`CrossBridgeSystem.bondForces`** over a **full rigid-rod `FilamentStore` whose roll DOF is integrated every step**
(`integrate` → `bwx = torqueSum·û/γ_x` → `yVec`; `orthogonalizeY`/`derive` roll-preserving). The explicit model's
`xbParams` has **align OFF (j1FMT=0) ⇒ pure F8**, so the only cross-bridge torque on the filament is the F8 couple
— no F9/F10 contamination, no roll spring, no artificial torque.

Per-step order after the port (surface ON): `matBeamGeom` → **bind** (`matBindExplicit`, unchanged ⇒ binding
DECISIONS + axial `bindArc` byte-identical) → **`matSurfaceAzim`** (retain material azimuth at the bind
transition) → **`matSurfaceStericPrune`** (optional 3D steric) → chem → cock → `matPlaceHeadExplicit` →
**`bondForcesSurface`** (off-axis site) → gather → chain → confine → Brownian → **integrate (rolls the filament)**
→ derive → `matS2SolveStep` → reduce.

## 4. Retained-state definition

`MotorStore.bindAzim[m]` (SoA float, one scalar/motor) — the material-frame azimuth ψ about û_seg in the rolling
(segY,segZ) frame. Set ONLY at the FREE→bound transition by `matSurfaceAzim` (tracked via `prevBound`/`justBound`);
never recomputed from the current head pose afterward; retained across steps while bound; reset on release/prune.
Consumed only by `bondForcesSurface` (via cos/sin ⇒ stored **unwrapped**, which also avoids `Math.floor` — which
does not lower on the PTX backend). Default 0, read by no canonical kernel ⇒ byte-identical when off.

## 5. Azimuth-selection rule (reference point audited)

At the bind transition, `matSurfaceAzim` uses **xF8** (the F8 head-side anchor, `outGeom[6N+m..8N+m]` — the SAME
point `matBindExplicit` uses to pick the segment; audited + stated explicitly, identical CPU↔GPU). It projects
xF8 onto the bound segment axis, forms the perpendicular direction p̂ = (xF8 − axis), and reuses the **existing
continuous helical scan** (fixed ±4-monomer bound, PTX-safe; analytic φ(s)=twistRate·(arc−½segLen), LEFT-handed
twistRate ≈ −1076 rad/µm) to select the presented site n̂(s)=cosφ·segY+sinφ·segZ best agreeing with p̂. It stores
that presentation's material azimuth and **keeps the canonical axial `bindArc`** (ordinary gliding translation
unchanged — only the azimuth is added). The samples are proposals on a continuous analytic helix, not discrete
sites.

## 6. Surface-site reconstruction + force/torque coverage

`bondForcesSurface` reconstructs `xSite = sc + (bindArc−½slen)·û + Ractin·(cosψ·segY + sinψ·segZ)`,
`Ractin = Constants.radius = 3.5 nm`. F stays collinear with (xSite−xF8) ⇒ the head/seg F8 pair is a **closed
couple** ⇒ force + torque conservation preserved by construction (validated in the prototype report; fixtures
23–24 there). The off-axis segment lever RS = xSite−sc gives `TS = RS×(−F)` a nonzero ‖û_seg component → the
byte-unchanged `segGather` → the live roll channel. Coverage: F8 force +F at xF8 / −F at xSite (once); F8 torque
about each body's center (once); axial roll drive = the ‖û_seg projection of the F8 reaction (once); **no F9/F10
in this model, so no alignment moment to double-count** (stop-condition cleared).

## 7. Deterministic port fixtures (10/10 PASS)

| # | Fixture | Result |
|---|---|---|
| 1 | feature OFF trajectory finite + canonical | PASS |
| 2 | R=0 surface ≡ canonical (bit-identical coord+redOut, 300 steps) | PASS |
| 3 | R=3.5 nm reconstructed site radial distance == R | PASS |
| 4 | retained azimuth constant while bound (material-latched) | PASS |
| 5 | steric: coincident (0 nm) → REJECT | PASS |
| 6 | steric: below threshold (5.3 nm) → REJECT | PASS |
| 7 | steric: exactly 5.5 nm → ACCEPT | PASS |
| 8 | steric: above threshold (5.7 nm) → ACCEPT | PASS |
| 9 | steric: opposite side (2R sep) → ACCEPT + lowest-id conflict | PASS |
| 10 | off-axis bond → nonzero F8 axial torque (net 1.92e-21 N·m); R=0 → ≈0 (7.5e-24) | PASS |

## 8. CPU/GPU equivalence

- **New kernels** `matSurfaceAzim` + `matSurfaceStericPrune`, isolated in a minimal device TaskGraph over a
  constructed bound-state fixture, single deterministic evaluation, CPU vs GPU: **Δboundseg=0, max|Δbindazim|=0.0,
  Δoccstats=0 — bit-identical, device-resident** (run with `-Dtornado.recover.bailout=false` ⇒ no silent
  sequential fallback). `bondForcesSurface` was already proven device-resident CPU≡GPU in the prototype report.
- Identical retained-azimuth decisions and identical steric accept/reject decisions on both runners by
  construction (one implementation). 0 invalid / 0 solver.

## 9. Dynamic experiment configuration

`ExplicitTwirlGlidingHarness -campaign` (CPU). Scene = `buildS2Mat` (explicit-s2-l40, nSeg=12 ≈2.1 µm filament,
dt=2.5e-6). Three arms at matched seeds/duration, **zero tuning between arms**: control (surface OFF); surface
(R=3.5, steric OFF); surface + steric 5.5 nm. Observables per step from host state: filament centroid·b̂ (glide),
per-segment unwrapped roll (mean + spread), F8 axial torque per bound head (from the persisted seg-torque slot),
accepted-azimuth histogram, steric rejects.

## 10. Torque decomposition, angular velocity, cumulative turns, turns/µm, steric

Two campaigns (ρ120: 4 seeds, 4000 steps; ρ200: 4 seeds, 3000 steps, with time-**accumulated** torque/azimuth
diagnosis over the whole run):

```
ρ120        arm                       glide µm/s  avgBound   turns   turns/µm     Σ|τ|/|Στ|
            control (surface OFF)       -2.286      1.04    -0.549     9.23           —
            surface (R=3.5)             -2.284      0.98    -1.275    64.82           —
            surface + steric 5.5nm      -2.334      0.96    -1.087    58.73           —
ρ200        control (surface OFF)       -3.402      2.10    -0.497    20.6          97.9
            surface (R=3.5)             -2.758      2.08    -0.292    25.6          47.7   ← turns SMALLER than control
            surface + steric 5.5nm      -2.724      2.10    -0.183    22.7         140.5
per-segment cumulative-turn SPREAD (surface): 3.89 turns (ρ120) / 2.50 turns (ρ200)  — ≫ the mean either way
accepted-azimuth histogram (ρ200 surface, 8 bins −π..π): [69 251 149 0 326 78 272 66]  — spread across all sides
```

**Reading — the net-rotation signal is NOT robust; the torques cancel.**
1. **Not directionally resolved across density.** At ρ120 the surface arm rolls MORE than control (−1.28 vs
   −0.55); at the better-sampled ρ200 it rolls LESS (−0.29 vs −0.50). The apparent ρ120 "excess" does not
   replicate — the net roll is dominated by thermal/measurement scatter (as û wobbles during gliding the roll
   reference drifts), not a robust off-axis-driven twirl.
2. **Per-head axial torques largely CANCEL.** The time-averaged `Σ|τ_i| / |Στ_i| = 7.2` (ρ200 surface) — the total
   torque magnitude is ~7× the net, i.e. most of it cancels. Net F8 axial torque ≈ 1.7e-21 N·m (small residual);
   per-head magnitude is real (fixture 10: 1.9e-21 N·m).
3. **No directional azimuthal bias (the cancellation mechanism).** The accepted-azimuth histogram is spread across
   all 8 bins — heads bind at essentially ALL azimuths around the thin filament, so their off-axis torque vectors
   point every way and sum to ~0. Off-axis point placement ALONE does not bias WHICH side binds.
4. **The roll is also INCOHERENT.** The per-segment cumulative-turn spread (3.89 / 2.50 turns) ≫ the mean —
   without a roll spring each segment rolls independently.
5. **Gliding: preserved-to-mildly-reduced.** Glide −2.28 vs −2.29 (ρ120, unchanged) but −2.76 vs −3.40 (ρ200, ~19%
   slower) at equal avgBound — the off-axis attachment adds a small rotational load at higher engagement.

## 11. Timestep result

A clean timestep-halving comparison is **inconclusive from this harness**: `buildS2Mat` couples dt into the beam
node/slack construction, so halving dt does not hold the scene fixed. The surface arm at dt=2.5e-6 gives a
bounded, finite glide + roll (glide −1.4 µm/s, turns −1.70 for seed A); no NaN/blow-up. A faithful dt-convergence
study needs the scene decoupled from dt (a follow-up), consistent with the documented explicit cross-bridge
dt-convergence limit of this lineage.

## 12. 3js output — directories and commands

Three dynamic-cycle movies (density 150, seed 101, 6000 steps, 151 frames each), each showing motor searching /
binding / stroke / detachment / rebinding / filament translation + any rotation:

```
threejs_explicit_twirl_control          (surface OFF)
threejs_explicit_twirl_surface          (surface ON, steric OFF)
threejs_explicit_twirl_surface_steric   (surface ON, steric ON)
```

Each frame (v1 viewer schema) carries: the actin **segments** (centerline rods); a per-segment **material-frame
roll-tick** (thin marker, center → center + 0.02·yVec, `r`=0.0008 — rotates with the SIMULATED `yVec`; a
visualization marker, NOT a force-bearing object); **off-axis bond lines** (bound head xF8 → reconstructed surface
site); and the **motors** (rod/lever/head, colored by nucleotide state). Watching the roll-tick shows the
per-segment roll directly.

**Generate:** `./scripts/run_explicit_twirl.sh -3js threejs_explicit_twirl -steps 6000 -stride 40 -density 150 -seed 101`
**View:** from `~/Code`: `python3 SoftBox/sim_server.py 8000`, then open
`http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → the twirl run).

## 13. Diagnosis — why sustained coherent twirling does not emerge

The mechanical loop is real per-head (fixture 10) but the net rotation is **weak, not robustly directional, and
incoherent**. Two missing pieces, in priority order:

- **H2/H1 — NO directional azimuthal bias ⇒ the torques cancel (PRIMARY).** The accepted-azimuth histogram is
  spread across all sides of the filament (`[69 251 149 0 326 78 272 66]`), so bound heads' off-axis torque
  vectors point every way and sum to ≈0: `Σ|τ_i|/|Στ_i| = 7.2` (most torque cancels). **Off-axis point placement
  alone is insufficient** — a real twirl needs a *stereospecific* binding constraint that biases WHICH azimuth
  binds (a local surface-normal / azimuth-dependent head-orientation gate, consistent with the helix), so that
  bound heads preferentially load one rotational sense. The explicit gate accepts a head at any azimuth around the
  thin (7 nm) filament; the material azimuth is *recorded* but does not *constrain* the accept decision. This is
  the task's H5 ("binding geometry incomplete") realized as the dominant limiter.
- **H3/H5 — NO roll-coherence spring ⇒ incoherent per-segment roll.** Per-segment `yVec` azimuths are uncoupled
  (chain F3/F4 straightens û but not roll of yVec about û), so even the residual net torque rolls each segment
  independently — spread (2.5–3.9 turns) ≫ mean. A coherent whole-filament twirl needs the inter-segment roll
  spring (`RollSpringSystem`, validated coherent in the lumped prototype) to lock the segments into one rigid
  helical body.
- **H4 — torque small vs roll drag + Brownian.** Per-head F8 axial torque ~1e-21 N·m; roll is the hottest,
  lowest-drag mode (Brownian alone gives control ~−0.5 turns), so even the uncancelled residual is near the
  thermal-roll floor.
- **H6 — rebinding does not advance helical phase.** Release/rebind re-selects the azimuth facing wherever the
  head then sits; with no ratchet the cycles do not wind a monotone twirl. (A consequence of H2 — no preferred
  sense to advance.)

**Best-supported: H2/H1 (cancellation from no azimuthal binding bias), then H3 (incoherence).** No empirical
torque, forced rotation, or damping was added; reported as-is.

## 14. Steric effect on normal binding + translation

The 3D 5.5 nm surface steric is **numerically active but behaviorally near-inert** on ordinary binding/translation
at these densities: avgBound 2.08→2.10 (ρ200) / 0.98→0.96 (ρ120), glide within noise, rejects ≈ 0 (mean head
spacing ≫ 5.5 nm). Consistent with the co-occupancy null — the continuous single-head bind coordinate is far from
5.5-nm-saturated. Separately disableable (`-no-surface-exclusion` / exclusion ≤ 0).

## 15. Decision-gate verdicts

- **G1 default-off identity: PASS** (feature OFF + R=0 both bit-identical to canonical over 300 steps; kernels
  additive).
- **G2 material-latched geometry: PASS** (fixtures 3–4; azimuth retained while bound; site rides the material
  frame).
- **G3 surface sterics: PASS** (fixtures 5–9; 3D reconstructed surface points; opposite-side accept;
  deterministic lowest-id).
- **G4 force/torque conservation: PASS** (closed F8 couple by construction; F applied once, no alignment moment
  to double-count).
- **G5 dynamic mechanical loop: PASS** — binding → retained surface azimuth → off-axis force → **measured**
  per-head axial torque (fixture 10, 1.9e-21 N·m) → **measured** angular response (a real, if largely cancelling,
  filament roll).
- **G6 sustained-twirl result: Category D** (qualified by B) — per-head axial torques occur but **dynamically
  CANCEL**: heads bind at all azimuths (uniform histogram) so `Σ|τ|/|Στ| = 7.2`, and the net roll is NOT robust
  across density (surface > control at ρ120 but < control at ρ200) ⇒ not directionally resolved (the B facet).
  Not A (not sustained/coherent/directional), not C (torque is real, not mere relaxation), not E (real per-head
  torque), not F (numerically healthy). The decisive evidence for **D** is the cancellation ratio + the uniform
  azimuth distribution.
- **G7 numerical health: PASS** — 0 invalid / 0 solver / bounded geometry / no roll-rate spikes; dt dependence
  disclosed (§11).
- **G8 visual deliverable: PASS** — the movies show gliding, binding, off-axis bond placement, and the
  material-frame roll via the per-segment roll-tick marker.

## 16. Numerical health

0 invalid states, 0 solver failures across fixtures, the equivalence gate, and every campaign arm; no NaN/Inf,
bounded joint/bond geometry, no pathological roll-rate spikes.

## 17. Canonical-status statement

Entirely noncanonical, flag-gated, default-off, byte-identical when disabled. No canonical default/parameter/
chemistry/S2/stroke/dt/RNG/manifest/`CANON_VERSION` change. The canonical explicit-S2 production path is
untouched.

## 18. Full-device-graph limitation — **RESOLVED (superseded)**

> **CORRECTION.** This section originally reported that the full explicit-S2 gliding device graph "does not lower
> on this machine — a pre-existing `matS2SolveStep` PTX fault." **That diagnosis was wrong.** The graph lowers and
> runs device-resident; the failure was a **launch-flag regression** — the run command omitted
> `-Dtornado.enable.fma=false` (a TornadoVM PTX-backend FMA lowering defect in `matS2SolveStep`; FMA on ⇒
> `ArithmeticLIRLowerable` NPE, FMA off ⇒ lowers cleanly). Root cause, matrix, fix, and G1–G5 validation:
> **`docs/EXPLICIT_S2_GPU_LOWERING_REGRESSION_FINDINGS.md`**.

With the corrected flags (`-Dtornado.recover.bailout=false -Dtornado.enable.fma=false
-Dtornado.tvm.maxbytecodesize=65536`, now in `scripts/run_explicit_twirl.sh`), BOTH the canonical surface-OFF and
the surface-ON gliding graphs lower and execute **device-resident with no fallback** (verified over 3 fresh
processes; `invalid=0`). The dynamic campaign reported in §10 was run on the **CPU** runner under the *old*
(flag-broken) launch — its physics/conclusions are unaffected (the CPU runner is the same one physics
implementation), but it **may now be re-run device-resident** for speed. The isolated new-kernel equivalence (§8)
stands, and is now supplemented by full-graph device-resident CPU/GPU agreement.

## 19. Exact next smallest step

**Add a stereospecific azimuthal binding constraint** — the primary missing piece the diagnosis identifies. Today
the material azimuth is *recorded* but the bind gate accepts a head at ANY azimuth around the thin filament
(uniform histogram ⇒ torques cancel, §13-H2). The smallest next step is to make the *accept* decision (or a graded
affinity) azimuth-dependent — reuse the validated continuous helical gate (`bindNearestAzim`/`bindNearestFalloff`,
which already thread `segYVec` and the analytic φ(s)) so a head preferentially binds the helical face pointing
toward it, biasing bound heads to load ONE rotational sense. Only with a nonzero net azimuthal bias will off-axis
attachment produce directed torque. **Then** add the inter-segment roll-coherence spring (`RollSpringSystem`) so
the biased torque rolls the filament as one coherent helical body (converting the residual incoherent roll into a
whole-filament twirl). Both are validated, additive, default-off lumped-lineage components; sequence them
azimuthal-bias → roll-spring, then revisit engagement (density) and a dt-decoupled timestep study.

---

## Completion summary

- **Files changed:** `softbox/TwoBodyBeamAnalyticGpu.java` (`matSurfaceAzim`, `matSurfaceStericPrune`),
  `softbox/ExplicitCompleteMatHarness.java` (flags + `ExMat`/`packExMat` + `stepGlidingCPU`/`buildGlidingGraph`
  surface wiring + bond swap); **new** `softbox/ExplicitTwirlGlidingHarness.java`,
  `scripts/run_explicit_twirl.sh`, this report. (`MotorStore.bindAzim` / `CrossBridgeSystem.bondForcesSurface`
  from the prototype increment.)
- **Flags:** `-helical-surface-bind` (via the harness; sets `ExplicitCompleteMatHarness.SURFACE_ON`),
  `-actin-bind-radius-nm 3.5`, `-surface-exclusion-nm 5.5`, `-no-surface-exclusion`, `-density/-seed/-steps/-stride`.
- **OFF-path identity:** bit-identical (fixtures 1–2). **CPU/GPU:** new kernels device-resident bit-identical
  (§8); the full gliding device graph (surface OFF **and** ON) also lowers + runs device-resident once
  `-Dtornado.enable.fma=false` is supplied — the earlier "does not lower" claim was a launch-flag regression, since
  fixed (§18; `docs/EXPLICIT_S2_GPU_LOWERING_REGRESSION_FINDINGS.md`).
- **Dynamic twirling: Category D** (qualified B) — per-head axial torque is real (fixture 10, 1.9e-21 N·m) but the
  torques **dynamically cancel** (Σ|τ|/|Στ| = 7.2; heads bind at ALL azimuths — uniform histogram) and the net roll
  is **not robust across density** (surface > control at ρ120, < control at ρ200); the roll is also incoherent
  (per-segment spread ≫ mean, no roll spring). **Effect on glide/binding:** preserved at ρ120, ~19% slower at ρ200
  (small rotational load), avgBound unchanged. **Steric:** active-but-near-inert. **Timestep:** bounded/finite;
  clean convergence inconclusive (dt-coupled scene).
- **3js:** `threejs_explicit_twirl_{control,surface,surface_steric}` (151 frames each) with roll-tick markers +
  off-axis bond lines.
- **Best-supported missing piece:** (1) a **stereospecific azimuthal binding bias** so heads don't bind all sides
  (the cancellation cause, H2/H1); (2) the **inter-segment roll-coherence spring** (H3). Off-axis placement alone
  is insufficient.
- **Exact next step:** make the bind gate azimuth-dependent (reuse `bindNearestAzim`/`bindNearestFalloff`), then
  add `RollSpringSystem` to the explicit-S2 filament (§19).
