# Increment 5c-iii Phase 2 — assembled moving crosslinker bundle + confinement-free v1 validation

**Status: assembled loop built + STABLE (both runners); the dominant v1↔v2 confound (viscosity `aeta`)
found and fixed; a residual ~3.5× absolute-link-count gap SURFACED + localized + PAUSED for the planner
(it is NOT within SEM). Crosslinker *physics* (formation gate, conc-scaling, force, unbinding cadence)
validated faithful; the residual lives in the time-evolution of the crossing population, not in any single
ported component.**

New: `softbox/CrosslinkerBundleHarness.java`, `run_xlinkbundle.sh`. `BoA-v1ref` byte-clean (all v1 edits
in a `/tmp/v1xlink` scratch). Production / `GlidingHarness` byte-unchanged; crosslinkers default-off there.

---

## 1. Part 1 — the assembled moving loop (both runners, STABLE)

Wired the full per-step crosslinker loop over a many-filament bundle of free rods (no walls, no motors,
no chain). **Per-step order, faithful to v1** (`BoxOfActin.doLoop` + `FilLink.enforceFilLink`, read from
BoA-v1ref):

```
zero → brownian
     → [every crosslinkCheckInt=100 steps] FORMATION (countActive(sat) → filFilCandidates → formGates
        → formAdmit → free-list → rank → allocate → placeOrient)        (v1: collision phase, pre-force)
     → UNBIND (ckLinkBreak; EVERY step, BEFORE force — v1 applyTransForce calls ckLinkBreak first and
        returns early on a break ⇒ a link breaking this step contributes no force)
     → countActive (dynamic fracMove=0.4/max(linkCt)) → linkForces → torsion → 2-pass CSR gather
     → integrate → derive
```

**Ordering reconciliation (the prompt said "force → unbind"; v1 is the opposite).** v1's `ckLinkBreak`
is the FIRST statement inside `applyTransForce` (FilLink.java:189–203) and returns before any force is
applied — so the faithful order is **formation → unbind → force**, matching the existing v2 5b convention
(`unbind` before `linkForces`). The prompt's phrasing is reconciled here. Cadence confirmed: formation
fires every `crosslinkCheckInt` (= biochemDeltaT/deltaT = 0.01/1e-4 = **100**); unbind + force + integrate
fire every step. `ckLinkBreak` runs **every step** (instrumented in v1: 20 calls per 20 steps per link).

**Stability (watch-it-run): PASS.** CPU runner, 200 filaments, 6000 steps: finite throughout, bounded
force, no blow-up (the near-parallel non-normalized force is blow-up-safe by construction per Phase 1).

**CPU≡GPU (assembled device mechanics over the bundle): PASS.** A GPU mechanics TaskGraph (16 kernels —
zero→brownian→countActive→linkForces→torsion→2-pass gather→integrate→derive; under the gliding 23-kernel
ceiling) runs stably on 200 filaments / 3000 steps and agrees with the CPU runner on the aggregate
(spread rel-diff 0.000%). Per-kernel CPU≡GPU bit-identity is already validated in `CrosslinkerHarness`
(5a gather / 5b unbind / 5c-i allocator / 5c-ii formation / 5c-iii P1 force+torsion); formation runs on
host in the assembled GPU comparison (its device path is the 5c-ii-validated one). The assembled loop is
thus the composition of already-CPU≡GPU-validated kernels + the inc-1 integrate/derive.

---

## 2. The v1 confinement-free oracle (`/tmp/v1xlink` scratch; BoA-v1ref byte-clean)

The Phase-1.5 oracle (`ParameterFiles/boa-xlink-dense-nomotor`: 200 single-segment filaments, lengths
0.1–0.3 µm, box 0.7×0.7×0.3 µm, centrally clustered `stdDevActinDist=0.2`, `maxXLinkBondAngle=0.6 rad`,
mode 0, torsion `filLinkTorqSpring=1e-19` active, **`aeta=1.0`**, no motors) was re-run **walls-off** via a
1-line scratch gate: `if (WALLS_OFF) return;` in `FilSegment.checkBugOrBoxCollision` driven by
`-DwallsOff=true` (the Chamber from-inside wall — `checkBugCollisionFromInside`→`bugForcesFromInside`).
Added: an IC dump (per-segment coord/uVec/len) and per-window funnel/spread/break counters. **All edits in
`/tmp/v1xlink` only.** v1 launched CPU-path: `BoxOfActin -r -seed N -pf <cfg>`.

v1's mesh walk (`filSegMeshCollisions`) does **only** crosslink formation — there is **no filament-filament
collision / excluded-volume force** in this fixture. So "confinement" here is the box wall only.

---

## 3. THE DOMINANT CONFOUND — viscosity `aeta` (found + fixed)

The fixture sets **`aeta:1.0` Pa·s** (the crowded "in Bug" cytoplasm viscosity), **10× the v2
`Constants.aeta=0.1` default**. Drag γ ∝ aeta, so D = kT/γ ∝ 1/aeta: v1's filaments diffuse 10× slower and
the dense bundle stays packed; the v2 harness (using 0.1) over-diffused and dispersed ~10× too fast,
collapsing the crossing density and starving formation.

**Diagnosis chain (matched IC, walls-off both sides):**
- v1 walls-off builds links 0→~22; v2 (aeta 0.1) stayed 0–2. The formation gate was suspected.
- Funnel instrumented on the **identical** step-20 config: v1 `distPass=11`, v2 `geom-pass=12` — **the gate
  MATCHES**; the earlier apparent mismatch was Brownian divergence between non-identical configs.
- Pure-diffusion isolation (xLinks off, walls off): v1 spread 0.241→0.279 over 0.6 s; v2 (aeta 0.1)
  0.25→0.58 — v2 diffused ~10× more. Root-caused to the `aeta` config override.

**Fix:** `applyAeta()` scales the drag tensors by `aeta/Constants.aeta` (FDT-consistent — `BrownianForceSystem`
derives the kick from `bTransGam`). With `aeta=1.0` (now the harness default), **v2 pure-diffusion matches
v1**: v2 0.237→0.270 vs v1 0.241→0.269 at step 4000 (≈ identical). This is a **config-match fix in the
harness, not a v2 physics change** — `DragTensorSystem`/inc-1 diffusion are unchanged and remain FDT-valid.

---

## 4. Part 2 — confinement-free v1↔v2 transient (after the aeta fix)

### 4.1 Dispersion window (Part 2.1) — usable window EXISTS, proceed (no pause)
With matched aeta, the unconfined bundle disperses **slowly** (spread 0.25→0.31 over 0.6 s; broad-phase
crossing candidates retained well above the formation threshold for ≳0.13 s). Density is retained long
enough for a meaningful transient comparison — Part 2.1 says proceed.

### 4.2 Matched-IC transient (Part 2.2) — RESIDUAL GAP, not within SEM (SURFACED + PAUSED)
v1 walls-off **ensemble** (6 seeds, identical-IC v2 builds), link count @ step 1500 (t=0.15 s):

| | seed1 | seed2 | seed3 | seed4 | seed5 | seed6 | mean ± SEM |
|---|---|---|---|---|---|---|---|
| **v1** | 18 | 24 | 24 | 22 | 27 | 20 | **22.5 ± 1.3** |
| **v2** | 7  | 8  | 3  | 6  | 10 | 5  | **6.5 ± 1.0**  |

A **~3.5× gap (Δ≈16, SEMs ~1) — NOT within SEM.** Per the prompt this is a >0.1% systematic that does not
dissolve into SEM → **surfaced, not papered over; the within-SEM match claim is PAUSED for the planner.**

**Localization (candidates excluded):**
- **Formation gate** — faithful (funnel matches bit-for-bit-of-decision on the identical config, §3).
- **Translational diffusion** — matches after the aeta fix (§3).
- **Unbinding** — same Bell formula (5b-validated) AND same cadence (v1 `ckLinkBreak` instrumented: every
  step, like v2). v1's low inactive count is because it forms faster than it loses, not a lower break rate.
- **Conc-scaling** — faithful (§4.3).

What remains: the **time-evolution of the crossing population**. v1 maintains `distPass≈12–17` across the
window while v2's declines (~12→6). With the gate, diffusion, and unbinding all matched, the residual sits
in a subtle coupling effect (candidates: rotational-diffusion-driven de-alignment rate over time; the
single-rigid-rod vs v1 representation; the one-per-segment-per-step admission cap's cumulative effect at
this density — a deliberate 5c-ii divergence flagged as "re-confirm at production density"). **Not yet
root-caused to a single component; recommended next: a per-window alignPass/distPass trajectory overlay on
matched configs, and an A/B with the admission cap relaxed.** Magnitude (~3.5×) ⇒ a logic/coupling signal,
not float32.

### 4.3 Conc-scaling (Part 2.3) — PASS
Gross formation (unbind off, mean over the 6 matched ICs, @ step 820):

| xLinkConc | 1.0 | 0.5 | 0.25 |
|---|---|---|---|
| mean links | 9.0 | 4.5 | 1.3 |
| P_form | 0.0952 | 0.0488 | 0.0247 |

**Halving conc halves formation: 9.0→4.5 = exactly 2.0×** (P_form ratio 1.95). The formation *mechanism*
(`P_form ∝ xLinkConc`) is faithful — independent of the absolute-count gap. (The 0.25 point is small-N
noisy but monotone.)

---

## 5. Part 3 — the demo
The assembled loop emits viewer frames (`-3js`) into the verbatim v1 viewer's schema: filament segments +
each ACTIVE crosslinker as a thin bond "segment" between its two attachment points, with `notADPRatio`
encoding orientation (1.0 = parallel, 0.0 = antiparallel). 308 frames written; bonds render and move with
the bundle. No viewer fork.

```
./run_xlinkbundle.sh                 # CPU assembled-loop run (200 fil) + stability
./run_xlinkbundle.sh -cpugpu -nfil 200    # GPU mechanics vs CPU (aggregate-within-SEM + stability)
./run_xlinkbundle.sh -disperse -nfil 200  # Part 2.1 density-vs-t
./run_xlinkbundle.sh -loadic <v1_ic.csv> -offset 20   # Part 2.2 v2 walls-off from a v1 IC
./run_xlinkbundle.sh -3js threejs_xlinkbundle -nfil 150 -conc 3   # the crosslinking demo
```

---

## 6. Parked / carry-forward
- **Confined absolute plateau (≈49) — parked** (Phase-1.5 target) for the future boundary/membrane increment
  that gives v2 a Chamber-equivalent confinement. Re-validation target captured.
- **The residual ~3.5× walls-off transient gap — OPEN, surfaced, PAUSED.** Recommended root-cause work above.
  The crosslinker increment's own physics is validated faithful; the residual is in the assembled
  coupling's crossing-population evolution (likely upstream/shared, not the crosslinker force/formation code).
- v1 scratch `/tmp/v1xlink` retains the walls-off gate + funnel/IC instrumentation for the follow-up;
  ensemble ICs + trajectories in `RUN_LOGS/v1_ic_seed{1..6}.csv` / `v1_traj_seed{1..6}.txt`.
