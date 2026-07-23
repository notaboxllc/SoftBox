# EXISTING_HELICAL_WORK_VERDICT

**Read-only verdict (2026-07-23).** Branch `gpu-mat-bottlenecks-explicit-singlehead`. Synthesizes
`ACTIN_AZIMUTH_AND_HELICAL_SITE_AUDIT.md`, `ACTIN_BINDING_PATH_TRACE.md`,
`TWIRLING_DOF_AND_OBSERVABLE_AUDIT.md`, `ACTIN_AZIMUTH_GIT_HISTORY.md`, `ACTIN_AZIMUTH_CODE_MAP.csv`.

---

## Final category

> ## **C — a continuous azimuthal gate exists, but no discrete helical-site model exists.**
>
> Qualified by **B** (azimuth *state* — the roll DOF / rod frame — exists and is preserved on both runners,
> but the **canonical** explicit-S2 binding path does not read it) and **D** (the continuous gate + roll
> spring live in the **lumped/gliding** lineage; reaching the canonical explicit-S2 path requires a
> **port/reconciliation**, not a revival).

Why not the others:
- **Not A** (complete/reusable): the twirl-drive (off-axis attach) is unbuilt, no discrete sites exist, and
  the gate is not in the canonical path.
- **Not pure B**: more than inert state exists — a full continuous gate (Inc-2 hard + Inc-3 graded) and a
  torsional-roll coherence spring are built and flag-runnable.
- **Not E** (nothing reliable): a validated (bit/event-identical CPU↔GPU), default-off, byte-identical-canonical
  gate + roll spring genuinely exist.

---

## What exists, precisely

| Design piece | Status | Location |
|---|---|---|
| Filament axial-roll DOF (free, integrated, frame-preserved) | **Present** (always) | `RigidRodBody`/`FilamentStore` frame + integrator + `DerivedGeometrySystem` |
| Piece 3 — inter-segment torsional-roll coherence spring | **Built**, flag-gated default-off | `RollSpringSystem.rollForces` |
| Piece 4 — roll thermostat (observability) | **Built**, flag-gated default-off | `RollSpringSystem.dampRoll` |
| Piece 1 — azimuthal acceptance gate (throttle) | **Built (CONTINUOUS)**, flag-gated default-off, **lumped lineage only** | `BindingDetectionSystem.bindNearestAzim` (Inc-2), `bindNearestFalloff` (Inc-3) |
| Piece 2 — off-axis bond placement (twirl DRIVE) | **NOT built** | — (bonds attach on-axis in both lineages) |
| Discrete helical-site model (monomer index / 13/6 / occupancy grid / retained site ID) | **NOT built** | — |
| Steric co-occupancy exclusion (continuous axial-gap, the lever the gate experiments pointed to) | **Built** as a 5.4 nm axial-gap veto, CPU-only, default-off, **non-canonical-gate** | `ExplicitHmmDimer*` `occupancyVeto` |
| Canonical explicit-S2 gate reads azimuth? | **No** | `matGeomGate`/`matBindGateExplicit`/`dimerBindGate` |

**Prior experiments and why judged insufficient:** Inc-2 (hard orientational cutoff) and Inc-3 (graded
falloff, MAX-combined) — both a **decisive negative**: a per-head orientational throttle *scales* engagement
(~0.55–0.85× avgBound depending on sharpness) but **cannot cap co-occupancy**, so it never bends the
velocity–density curve to a plateau at any plausible acceptance window / steepness. The recorded conclusion:
the real capping lever is **steric co-occupancy exclusion**, not orientation. (Docs `AZIMUTHAL_GATE_INCREMENT2.md`,
`AZIMUTHAL_FALLOFF_INCREMENT3.md`.)

---

## Smallest safe next implementation step

**It depends on the goal — the two goals in the brief have different smallest steps:**

1. **If the goal is "site-limited binding" (throttle co-occupancy) in the CANONICAL explicit-S2 path:**
   the smallest safe step is to **promote the existing continuous axial-gap occupancy exclusion**
   (`occupancyVeto`, 5.4 nm) from the CPU-only `ExplicitHmmDimer3jsHarness` diagnostic into the canonical
   `matGeomGate`/`dimerBindGate` as a flag-gated, default-off gate — reusing the continuous `bindArc` /
   `filMatCoordUm` material coordinate already computed. This is the lever the gate experiments identified,
   already implemented and CPU-validated; it needs **no azimuth and no new state**. **This is the recommended
   first step** if the target is capping engagement.

2. **If the goal is filament TWIRLING (the "site-limited binding so that twirling is possible" target):**
   the smallest safe step is a **standalone, non-canonical prototype of Piece 2 (off-axis bond placement)** on
   the lumped/gliding path where the gate + roll spring already exist — attach the cross-bridge at radius R
   (use the **physical filament radius**, not v1's 0.8 nm render offset) at the site azimuth, storing one
   per-motor material ψ (relative to the rolling `seg.yVec`), and **confirm `bwx` responds** (a net-turns / ω
   readout, the Piece-4 observability). The torque channel is already open; this is the one missing link. Keep
   it flag-gated and default-off. Prototype the drive **before** any port to the canonical explicit path.

Either way, **do not start with a discrete-site (13/6 monomer-lattice) model** — nothing in the codebase needs
one yet, and both the gate and the twirl drive are expressible on the continuous coordinate.

---

## Reuse / do-not-revive / canonical-status guidance

**Reuse:**
- `RollSpringSystem` (Pieces 3 + 4) verbatim — validated, dt-robust springs-continuum form, bit-identical
  CPU↔GPU, default-off.
- The continuous gate machinery (`bindNearestAzim`/`bindNearestFalloff`) as the throttle template — including
  the PTX-safe fixed-bound site scan and the analytic `φ(s)=twistRate·(arc−½segLen)`.
- The **discarded ⊥-offset** in `reachTestDistSq` (`BindingDetectionSystem.java:74-76`) / the `nearestSeg2D`
  perpendicular — this is exactly the head azimuthal position ψ needed for both a real gate and off-axis
  attach.
- The explicit lineage's continuous axial-gap `occupancyVeto` for co-occupancy capping.

**Do NOT revive / re-attempt:**
- The hope that an **orientational acceptance gate alone saturates** the velocity–density curve — Inc-2/Inc-3
  closed this as a decisive negative. Do not re-run the orientation-only sweep expecting a plateau.
- v1's **helix handedness sign** (rendering screw, non-authoritative) and v1's `helixMonOffset` (0.8 nm render
  offset) as a twirl moment arm — derive handedness fresh; use the physical filament radius for the moment arm.
- Building a discrete-site lattice as a precondition — deferred until a second, genuinely site-resolved
  requirement appears (abstract-from-the-second-instance).

**Canonical vs named-noncanonical:**
- Keep this a **named, noncanonical, flag-gated extension** for now. The canonical explicit-S2 production path
  must stay azimuth-agnostic and byte-identical unless/until a twirl or site-limited-binding deliverable is
  actually required and validated. A **new canonical version is NOT required** to do the next step; both
  recommended next steps are additive, default-off, and byte-identical-when-off. Promotion to canonical (per
  the per-assay-class GPU sign-off) would come only after a validated deliverable, with a fresh CPU↔GPU
  equivalence pass on the changed hot kernel.

---

## One-line summary

A **continuous azimuthal acceptance gate** and a **torsional-roll coherence spring** exist (lumped/gliding
lineage, default-off, byte-identical-canonical), the **filament roll DOF is free and preserved everywhere**,
but there is **no discrete helical-site model**, the **canonical explicit-S2 binding path reads no azimuth**,
and the **twirl drive (off-axis bond placement) is unbuilt** — so the twirl loop is open. **Verdict C**, with
**B** and **D** qualifiers.
