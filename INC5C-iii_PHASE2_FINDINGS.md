# Increment 5c-iii Phase 2 — assembled moving crosslinker bundle + confinement-free v1 validation

**Status: assembled loop built + STABLE (both runners); the dominant v1↔v2 confound (viscosity `aeta`)
found and fixed; a residual ~3.5× absolute-link-count gap SURFACED + localized + PAUSED for the planner
(it is NOT within SEM). Crosslinker *physics* (formation gate, conc-scaling, force, unbinding cadence)
validated faithful; the residual lives in the time-evolution of the crossing population, not in any single
ported component.**

**UPDATE (§7, residual root-cause pass): the ~3.5× gap is now ROOT-CAUSED — it is NOT one mechanism but
TWO independent multiplicative channels, NEITHER the admission cap. (1) FORMATION ~1.9× = a v1
mesh-binning ARTIFACT (v1 draws P_form once per mesh-VISIT, and close crossings are visited ~1.9× ⇒ v1
over-forms; v2's one-draw-per-crossing is the more correct model — v2's distinct crossing population MATCHES
v1's). (2) RETENTION ~2× = v2 crosslinks carry ~2.1× the per-link strain (measured: v2 ~0.89 vs v1 ~0.42)
⇒ ~2× the Bell-unbinding rate; localized to the single-link Brownian-driven steady-state strain (a regime
5a never validated — 5a checked pure decay only). Admission cap EXONERATED (0 drops at fixture conc);
rotational diffusion MATCHES (~6%). Diagnostic only; no production changes. See §7.**

**RESOLVED (§8, physics-adjudicated close — v1 is NOT a quantitative crosslinker oracle): both channels are
v2-correct / v1-deviation; crosslinkers PHYSICALLY VALIDATED. ROOT #1 (formation) — v2's one-draw-per-crossing
is correct; v1's 1.9× is a mesh artifact and the calibration question is DISSOLVED (v1 never tuned to
experiment ⇒ nothing to recover; do NOT import it). ROOT #2 (retention) — the decisive single-link test shows
v2's Brownian steady-state strain MATCHES the Boltzmann/equipartition prediction of its own force law to 0.1%
(`P(L)∝L²exp(−U/kT)`, ratio 1.001); v2 sits AT thermal equilibrium (strain ~1.13 ON-COM / ~0.93 realistic)
while v1's ~0.42 is FAR BELOW it ⇒ v1 is the sub-thermal deviation, not v2. ⇒ ACCEPT v2, NO production fix.
See §8.**

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

---

## 7. RESIDUAL ROOT-CAUSE PASS (the ~3.5× gap, diagnostic only — no production changes)

**Result: the ~3.5× gap is NOT one mechanism — it decomposes MULTIPLICATIVELY into two independent
channels, and NEITHER is the admission cap. (1) a FORMATION-rate gap (~1.9×) root-caused to a v1
mesh-binning ARTIFACT (v1 over-draws P_form per crossing), and (2) a RETENTION gap (~2×) root-caused to
v2 crosslinks carrying ~2.1× the per-link strain → ~2× the Bell-unbinding rate, localized to the
single-link Brownian-driven steady-state strain (a regime 5a never validated).** The admission cap and
rotational diffusion are both EXONERATED. Diagnostic instrumentation only; `BoA-v1ref` byte-clean (all v1
edits in `/tmp/v1xlink`); production / `GlidingHarness` / `CrosslinkerSystem` / `CrosslinkerStore`
byte-unchanged (only `CrosslinkerBundleHarness.java` gained diagnostic toggles).

### 7.1 The decomposition (the reframing). `-nounbind` splits gross formation from retention.
6-seed matched-IC ensemble, walls-off both sides, aeta=1.0, @ step ~1400:

| | v1 net | v2 gross (`-nounbind`) | v2 net |
|---|---|---|---|
| mean | **~22** | **~14** | **~4.7** |

- **Formation (gross):** v2 ~0.45× v1 (v1 gross ≈ net+breaks ≈ 22–27 since v1 barely breaks).
- **Retention:** v2 keeps 14→4.7 (**34%**); v1 keeps ~27→22 (**~71%**) — v2 breaks ~2× more.
- Product 0.45 × (0.34/0.71) ≈ 0.22 ≈ the observed 4.7/22 ⇒ both channels real, ~comparable, multiplicative.
- v1's logged `filLinkCt` is **net-active** (swap-remove decrements), so v1's near-monotonic rise is genuine
  accumulation, not a cumulative counter.

### 7.2 Cut 1 — admission cap: EXONERATED (Cut 1a; Cut 1b unnecessary).
Instrumented the per-event cap-specific drops (gate-passers that lost the one-per-segment min-candidate
contest), full 6000-step run, seed1:

| conc | cum gatePass | cum capDrop |
|---|---|---|
| 1.0 (fixture) | 50 | **0** |
| 3.0 (stress)  | 144 | 4 (2.8%) |

`gatePass` is ~1/event spread over 200 segments ⇒ same-segment contention ≈ 0 ⇒ **zero cap drops at the
fixture density.** Matches the 5c-ii self-check (0.93% upper bound). Per the prompt, drops ~nil ⇒ cap
exonerated ⇒ **Cut 1b (cap-relaxed A/B) skipped** — relaxing a cap that never binds changes nothing. The
5c-ii one-per-segment-per-step admission stands; the heavier race-free multi-admission is NOT needed.

### 7.3 Cut 2 — rotational diffusion: MATCHES (no upstream-seed failure).
xLinks-off, matched IC, orientational autocorrelation C(t)=⟨u(t)·u(0)⟩ over 1400 steps:

| | C drop (1400 steps) | implied D_rot |
|---|---|---|
| v1 (`xLinkConc=0` scratch) | 1.000→0.914 | ~0.321 /s |
| v2 (`-rotdiff`) | 1.000→0.909 | ~0.341 /s |

**~6% — matched, like translational (§3).** Not the "v2 rotates faster" failure mode; no pause. (The aeta
fix scales both `bTransGam` and `bRotGam`, so FDT-consistent rotational diffusion carried over.)

### 7.4 Cut 3 — alignPass/distPass: v2's distinct crossings MATCH v1; v1's raw distPass is INFLATED.
Instrumented v1 with a distinct-pair dedup (`distinctPairs`, `distPassPairs`) against the raw funnel:

| per formation event (v1 seed1) | v1 raw | v1 DISTINCT | v2 geom(<grab) |
|---|---|---|---|
| coarse `calls` / `distinctPairs` | ~6000–8000 | ~6000–6280 | (coarse ~5200–6000) |
| **distPass** | **~12–29** | **~5–18** | **~8–16** |

- Coarse multiplicity ~1.0–1.3 (mild). **distPass multiplicity ~1.9×**: the close (distance-passing) pairs
  are spatially adjacent ⇒ they share multiple mesh cells ⇒ the mesh walk visits each ~2×.
- **v1 distPassDistinct (~10) ≈ v2 geom (~11.5).** The crossing populations MATCH; v2 is NOT crossing-deficient.
  v1's raw ctlDistPass (~25) was inflated ~2× by mesh multi-cell visiting. The earlier §4.2 "v2 distPass ~half
  v1" compared v2's deduped count against v1's RAW count — an apples-to-oranges artifact, now corrected.

### 7.5 ROOT #1 (formation channel) — v1's mesh multi-visit gives MULTIPLE P_form draws per crossing.
v1 runs an **independent `rng.nextDouble() < P_form` draw inside `checkToLink` per mesh-VISIT** (not per
distinct crossing). A close crossing visited ~1.9× gets ~1.9 independent draws ⇒ effective per-crossing
formation ≈ 1−(1−0.0952)^1.9 ≈ **0.17** vs v2's faithful one-draw **0.095** (a ~1.8–2× over-formation; the
measured v1 `formed/distPassDistinct` ≈ 0.26 is even higher, consistent with the closest pairs being visited
the most). **This is a v1 IMPLEMENTATION ARTIFACT** — v1's formation probability per crossing is set by mesh
binning, not by `P_form` alone. v2's one-draw-per-crossing is arguably the MORE physically correct model.
Confirmed by the decisive lever: boosting v2 to `-conc 3` (P_form≈0.26, ≈ v1's effective rate) **raises v2
formation but the count only reaches ~11 (vs v1 22.5)** — because the retention channel still binds (§7.6).

| v2 link @ step1500 (6-seed mean) | conc=1 | conc=2 | conc=3 | v1 |
|---|---|---|---|---|
| | 6.5 | 8.2 | 11.2 | 22.5 |

### 7.6 ROOT #2 (retention channel) — v2 crosslinks carry ~2.1× the strain → ~2× Bell breaks.
Direct measurement of mean instantaneous active-link strain (= (linkLength−restLength)/restLength), 6-seed,
steps 600–1500:

| | mean active-link strain |
|---|---|
| v1 (`[XSTRAIN]` scratch) | 0.39 / 0.42 / 0.45 → **~0.42** |
| v2 (`-straindiag`) | 0.76–1.06 → **~0.89** |

At strain 0.42 vs 0.89, `k_off = 1+exp(2·strain)` gives P_break ratio ~2× ⇒ v2 breaks ~2× more (v2 ~46–113
breaks/run vs v1 ~0–9). **This strain gap is intrinsic and NOT explained by:** diffusion (§7.3 trans + rot
matched); torsion (`-notorsion` leaves v2 strain ~0.6–1.2, breaks 49 vs 46 — unchanged); the admission cap
(§7.2); or density/feedback (**at matched LOW link count the gap persists** — v1 nActive=4 → strain 0.31,
v2 links=2 → strain 0.91; and v2 `-conc 3` with 6–17 links keeps strain ~0.6–1.1, not lower). It is localized
to the **single-link Brownian-driven steady-state strain** — the translational link-force + integrator
relaxation of an OFF-COM attachment under Brownian forcing. **5a validated only PURE decay (Brownian OFF) to
0.0012%; the Brownian-driven steady-state strain — the quantity that actually governs unbinding — was never
checked.** A given v2 crosslink between two Brownian rods simply sits ~2× more stretched than the equivalent
v1 link, so it breaks ~2× faster.

### 7.7 Read against the interpretation matrix + the decisive next cut.
- Cut 1b would close the gap → **NO** (cap exonerated, 0 drops).
- Cut 2 mismatched → **NO** (matches within ~6%).
- ⇒ lands on matrix branch 3 ("neither cap nor diffusion; the residual is deeper — representation / coupled
  dynamics"), but narrowed far past "deeper" into a **two-channel decomposition**: formation = v1 mesh
  double-draw artifact (v2 more correct); retention = intrinsic single-link Brownian strain.
- **Decisive next cut (named, NOT run):** a co-developed single-link **Brownian steady-state strain** check —
  identical 2-rod IC with a deliberately OFF-COM attachment, Brownian ON, v1 vs v2, measure steady-state
  strain + the rotational relaxation of the attachment point. This extends 5a into the unbinding-governing
  regime. If v2 relaxes the off-COM attachment slower (rotationally) ⇒ a real translational-force/integrator
  port discrepancy to fix; if it matches ⇒ a many-body emergent difference and the retention gap is irreducible.

### 7.8 Recommended fix path (for the planner to scope — NOT implemented).
1. **Formation channel (v1 artifact):** treat v1's per-crossing over-formation as a mesh-binning artifact, NOT
   a target. Prefer **accepting v2's faithful one-draw-per-crossing as the more correct model** and documenting
   that v2's absolute link count will sit below v1's inflated count by the mesh-multiplicity factor (~1.9×).
   (A cosmetic alternative — scaling v2's P_form by the mean multiplicity — would import a v1 artifact; not recommended.)
2. **Retention channel (load-bearing):** run the §7.7 single-link Brownian-strain check. This is the half that
   does NOT close by boosting formation, so it is where the real fidelity question lives. If a rotational-relaxation
   discrepancy surfaces, that is the production fix; if not, the retention gap is the rigid-rod-representation
   remainder (analogous to the §6.7 gliding parallel-scheme remainder — accept + document).
3. The confined absolute plateau (≈49) stays parked for the boundary/membrane increment.

### 7.9 Instrumentation (committed in the harness; v1 edits in `/tmp/v1xlink` only).
- v2 `CrosslinkerBundleHarness`: `-Dstraindiag` (per-step ACTIVE→FREE break tracking + mean/max active-link
  strain), `-Drotdiff` (xLinks-off C(t)=⟨u·u0⟩), `-notorsion` (torsion-source probe), `-Dformdiag` CAP rows
  (Cut 1a cap-drop count). Default-off; production paths byte-unchanged.
- v1 `/tmp/v1xlink`: `[XLINKCT]` gained `distinctPairs`/`distPassDistinct`; new `[XSTRAIN]` (mean/max active
  strain) + `[ROTDIFF]` (C(t)); `ParameterFiles/boa-xlink-noform` (xLinkConc=0 for the rotational-diffusion
  isolation). `BoA-v1ref` byte-clean.

---

## 8. PART A — Root-1 CLOSED (formation calibration DISSOLVED) + PART B — retention adjudicated by PHYSICS

**New planner posture (jba):** v1's crosslinkers were **never calibrated against experiment** — v1 is a faithful
**component-port** reference, but **NOT a quantitative oracle** for crosslinker *emergent* behavior. So the ~3.5×
gap is adjudicated against **first-principles physics**, not against v1's numbers. Result: **both channels are now
v2-correct / v1-deviation, and crosslinkers are physically validated.** Diagnostic + harness only; production /
`CrosslinkerSystem` / `CrosslinkerStore` / `GlidingHarness` byte-unchanged; `BoA-v1ref` byte-clean.

### 8.1 PART A — ROOT #1 (formation, §7.5) CLOSED. Calibration question dissolved, not deferred.
v2's **one-draw-per-distinct-crossing** formation is the physically correct model; v1's ~1.9× higher formation is a
**mesh-binning artifact** (multiple `rng<P_form` draws per crossing, one per mesh-cell visit — §7.5). Since v1's
crosslinkers were never tuned to an experimental density, **there is no experimental link density to recover** ⇒
**do NOT import v1's mesh multiplicity and do NOT compensate `xLinkOnRate`.** The calibration question is
**DISSOLVED** (there is nothing to calibrate to), not parked. v2's absolute link count sitting below v1's inflated
count by ~1.9× is *expected and correct*. (Recorded in CLAUDE.md carry-forward + the ≈49-plateau reframe.)

### 8.2 PART B — the physical target (B0): equipartition of a CONSERVATIVE central force.
The crosslinker spring is a **central conservative force**: its magnitude depends only on the link length
`L = |pt2 − pt1|` (`forceVec = curForceMag·linkVec`, `curForceMag ∝ stretch`). A conservative force + the
FDT-consistent overdamped Langevin dynamics (inc-1-validated Brownian kick `∝ √(2kTγ/dt)`) has a unique
steady state: the **Boltzmann distribution of its own potential**,
`P(L) ∝ L²·exp(−U(L)/kT)`, with `U(L) = ∫_{L0}^{L} f(L')·dL'` (3D radial Jacobian `L²`; `f(L)` = the exact ported
force law). This makes "is v2's steady-state strain physically correct?" a **sharp, unit-clean test**: does the
measured steady-state link-length histogram match `L²·exp(−U/kT)` computed from v2's *own* force law? **Equipartition
is drag-independent** — the equilibrium depends only on `U` and `kT`, NOT on γ (which only sets the relaxation
*timescale*). `U(L)` is computed by numerically integrating the exact `f(L)` (energies in J; `dL` in metres).

### 8.3 PART B — measurements (`run_xlinkbundle.sh -singlelink`; 2 rods, 1 link, dt=1e-4, aeta=1.0, ~55k samples).
A ladder isolates the thermostat from configurational geometry. `k_decay` (B2, Brownian OFF) = **0.00633/step**
(τ=158 steps); **CPU≡GPU bit-identical** on the deterministic relaxation (max|Δcoord| = 4.66e-10 µm ≈ 0.5 ULP).

| case | geometry | drag | measured ⟨strain⟩ | Boltzmann ⟨strain⟩ | ratio |
|---|---|---|---|---|---|
| **B1a** (decisive) | ON-COM, no rotation | **isotropic** | **1.132** (seed1) / **1.162** (seed7) | **1.130** | **1.001 / 1.028** |
| B1b | ON-COM, no rotation | anisotropic (real) | 1.139 | (1.130) | — |
| B1c | OFF-COM + rotation ON | anisotropic (real) | 0.954 / 0.917 | — | — |

- **B1a — DECISIVE: v2 matches the Boltzmann/equipartition prediction to 0.1% (seed1) / 2.8% (seed7).** The
  link-length histogram tracks `L²·exp(−U/kT)` bin-for-bin across the bulk (the one over-filled bin at `L≈L0` is
  the `strain=max(·,0)` clamp folding the free `L<L0` mass onto `L0` — a reporting artifact, not physics).
  ⇒ **v2 injects exactly the FDT/equipartition thermal energy into the link DOF. The thermostat is correct.**
- **B1b ≈ B1a** (1.139 vs 1.132) **confirms drag-independence** — anisotropic vs isotropic drag give the same
  equilibrium, exactly as Boltzmann requires (drag sets only the timescale). A second, independent correctness sign.
- **B1c** (off-COM lever + rotational wander) gives ⟨strain⟩ ≈ **0.93**, *lower* than ON-COM (rotation lets the rods
  reorient to relieve strain). This realistic single-link value ≈ the assembled-bundle v2 strain **~0.89** (§7.6) —
  internally consistent.

### 8.4 PART B — the read (decision logic): v2 is at equipartition; **v1 is the deviation.**
- v2's single-link strain **sits AT the thermal equilibrium** of the (uncalibrated, thermally *soft*) v1 force law:
  ~1.13 (ON-COM) / ~0.93 (realistic).
- v1's bundle strain **~0.42 (§7.6) is FAR BELOW** that equilibrium — i.e. **v1 is the under-strained (sub-thermal)
  outlier**, not v2. (Plausible v1-side origin, NOT root-caused — v1 is non-oracle: v1 represents these short
  filaments as *multiple jointed sub-segments* so a link attaches to a shorter, less-mobile piece, and/or v1's
  links form/break faster than they thermalize. Either is a v1 representational/dynamical characteristic, not a
  target.)
- Per the prompt's decision matrix: **v2 matches equipartition (B1 ≈ B0) ⇒ v2 is physically correct; the retention
  "gap" is v1's (non-oracle) deviation ⇒ ACCEPT v2, NO production fix, crosslinkers physically validated.** No
  shared-scope concern arises (there is no bug to localize; B2/B3 not needed).

### 8.5 The ~3.5× gap is now FULLY ACCOUNTED — both channels v2-correct.
- **Formation (~1.9×):** v1 mesh-double-draw artifact; v2 one-draw-per-crossing is more correct (§8.1).
- **Retention (~2×):** v2 sits at FDT/equipartition (strain ~0.9); v1 sits sub-thermally (strain ~0.42). v2 correct.
- ⇒ The entire walls-off link-count gap = {a v1 formation artifact we decline to import} × {v1 being colder than the
  thermodynamic equilibrium of the shared force law}. **v2 is the more physics-faithful model in BOTH channels.**
  Crosslinkers (force law, formation gate, conc-scaling, Bell unbinding, AND the Brownian steady-state strain that
  governs unbinding) are **validated faithful + physically sound.** Move to 5d (Arp2/3).

### 8.6 Carry-forward (crosslinker → 5d / membrane).
- The **≈49 confined plateau** is reframed (Part A): a future-increment **v2 self-consistency / physical-plausibility**
  check (formation≈dissolution at confinement), **NOT a "hit v1's 49" target** (v1 uncalibrated).
- The v1 force law is **thermally soft** (equilibrium strain ~1 at physiological kT, `U(8·L0)/kT ≈ 52`) — recorded
  as a property of the uncalibrated v1 model. If crosslinker stiffness is ever calibrated to experiment (α-actinin
  etc.), it is a *force-law* change (re-tag + re-validate), independent of this port-fidelity result.

### 8.7 Instrumentation (Part B; committed in the harness).
`CrosslinkerBundleHarness -singlelink` (+`-seed`): builds the 2-rod/1-link scene and runs B2 (deterministic
relaxation + CPU≡GPU) and the B1a/b/c steady-state ladder with the in-code Boltzmann `L²·exp(−U/kT)` predictor +
histogram overlay. `buildSingleLink`/`runSteadyState`/`boltzmannPredict`/`boltzmannHist`. Default path
byte-unchanged; `BoA-v1ref` not touched (v1 strain ~0.42 reused from §7.6 as the informational cross-check — a clean
standalone-v1 2-rod run is disproportionately invasive, the same judgment as 5a §5, and unnecessary since the
adjudication is v2-vs-physics).
