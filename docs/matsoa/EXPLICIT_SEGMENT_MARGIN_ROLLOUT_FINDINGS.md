# Canonical rollout — half-open filament-segment ownership + removal of the 50 nm binding exclusion

**Follow-on to** `EXPLICIT_SEGMENT_MARGIN_PROVENANCE_FINDINGS.md` (which found the 50 nm segment-end margin is a legacy
numerical workaround masking an ownership-handoff defect, and validated the opt-in half-open fix). **This task rolls the
corrected ownership through all relevant two-body binding paths, removes the finite exclusion, keeps a byte-identical
legacy regression mode, and runs a short GPU density sweep** to confirm the leftward shift + preserved plateau.

**Framing (not a fit-parameter change):** the previous two-body binding excluded a fixed 50 nm interval around every
numerical filament-segment endpoint — an endpoint-disambiguation safeguard, not physically derived, that made binding
depend on segmentation length and masked an incomplete nearest-segment ownership handoff. The corrected implementation
assigns each material point to exactly one segment via deterministic half-open ownership and removes the finite
exclusion, keeping only machine-scale numerical tolerance. **It restores continuous filament accessibility under
discrete segmentation** — NOT loosened affinity, increased rate, a fitted curve, or added biological sites.

**Base commit:** `5a74e86`. `BoA-v1ref` byte-clean; no unrelated changes bundled.

---

## §1 — Pre-rollout freeze + implementation-site inventory (deliverable 1)
Every site enforcing the exclusion / overlap (from `git grep`):

| file | site | role |
|--|--|--|
| `TwoBodyBeamAnalyticGpu.matBindExplicit` | L432 foot `half+0.02`, L460 g7 `margin` | explicit-s2-l40 GPU + CPU-runner bind |
| `MatSoaSlice.matGeomGate` | L354 foot `half+0.02`, L379 g7 `margin` (params[28]) | calibrated mat slice bind |
| `TwoBodyConverterMotor.nearestSeg2D` | L4525 foot `half+0.02` | shared CPU nearest-seg (all two-body assays) |
| `TwoBodyConverterMotor.gate2D` | bindArc = `dot(xF8−e1,u)` | shared CPU gate metrics |
| `TwoBodyConverterMotor` g7 sites | L2230/3818/4570/4809/5297/5841/6809 `margin=0.05` | gateEval / stepGlide / stepGlideS2 / laser-trap |
| `ExplicitCompleteMatHarness.packExMat` | L77 `bindP[10]=0.05` | explicit bindP init |
| `ExplicitCompleteMatHarness.preBindReachable` | L380 `margin=0.01` | pre-bind helper (reads canonical nearestSeg2D/gate2D) |

Legacy regression baseline (pre-change): `-traj` §7 Δnode=1.3e-8 / §8 4.9e-8 PASS; `-gliding` §8 mism=0, §10 binds=9
detach=8 firstDiv=t178 invalid=0.

## §2/§3 — Canonical ownership contract + centralization (deliverables 2, 3)
**One toggle drives every path:** `TwoBodyConverterMotor.LEGACY_OWNERSHIP` (static; default `false` = canonical; set by
`-Dsoftbox.legacyOwnership=true` or the harness `-legacy` flag). `bindMargin()` returns the g7 arc margin
(`BIND_EPS=1e-6 µm` canonical / `0.05` legacy).

**Canonical arithmetic (identical, byte-faithful, across CPU `nearestSeg2D`/`gate2D`, GPU `matBindExplicit`, and
`MatSoaSlice.matGeomGate`):**
- candidate distance = distance to the **clamped closest point**: `footC = clamp(foot, −half, half)`, `d² = |xF8 −
  (c + footC·û)|²`;
- ownership half-open `foot ∈ [−half, half)` internal (the final segment includes its tip); a shared joint has exactly
  one deterministic owner (argmin; the measure-zero exact-joint tie resolves by first-min = lower index and gives the
  SAME world point / forces either way);
- accepted `bindArc = footC + half ∈ [0, segLength]`;
- g7: `bindArc ∈ (ε, 2·half − ε)`, `ε = 1e-6 µm` — **no finite physical exclusion**;
- interior points (`footC=foot`) are BYTE-IDENTICAL to legacy; only beyond-end/joint points differ (correct handoff).

The explicit mat path carries the mode in `bindP[12]` (0=canonical/1=legacy) — **the negative-margin opt-in sentinel is
retired**; `bindP[10]` is now a normal ≥0 margin. `packExMat`/`packGeomGateParams` set margin+mode from the toggle.

## §4 — Finite margin removed (deliverable 3)
The 50 nm exclusion is gone from the canonical path (replaced by `ε=1e-6 µm`); NOT replaced by 12.5 nm or any nm buffer.
Ownership is expressed explicitly (clamp + half-open), not encoded through a margin. The "interior material coordinate"
50 nm comment is superseded. Legacy mode remains only for regression, clearly labeled deprecated, and is NOT the default.

## §5 — Static + one-step validation (deliverables 4, 5, 6)
**§5.1 Joint probe (`JointMarginProbe`, bends 0/5/15/30°):** canonical column shows a **clean seg 5→6 handoff exactly at
the joint** (bindArc 0.175 → joint → 0.000 → 0.020, in-range both sides), **g7ε PASS on both sides** (only the
measure-zero exact-joint point fails), **no dead-zone**, exactly one owner, `bindArc` always in `[0,segLength]`, world
binding point continuous, **F8 force continuous across the joint** (4.0012 pN just-left = just-right; max |F8| jump
across the switch is the smooth lateral variation, not a discontinuity), max |Tseg| jump 0, no NaN, no zero-length basis.
**§5.2 Enumeration/polarity:** the argmin over clamped distances is enumeration-invariant except at the measure-zero
exact-joint tie (which yields the same world point/forces either way); the distance metric is polarity-invariant
(binding sign changes come from the downstream force law, not ownership).
**§5.3 CPU/GPU identity (canonical):** `-gliding` §8 one-step free-binding replay device `matBindExplicit` vs host
`geom2D+nearestSeg2D+gate2D` — **mism=0** (identical chosen segment + bindArc + bind count); no silent fallback; 0
invalid.

## §6 — Regression protection (deliverable 7)
**§6.1 Legacy byte-identical** (`-Dsoftbox.legacyOwnership=true`): `-traj` §7 Δnode=1.3e-8 / §8 4.9e-8 PASS; `-gliding`
§8 mism=0, §10 **binds=9 detach=8 firstDiv=t178 invalid=0 — EXACTLY the pre-change baseline.**
**§6.2 New canonical path:** `-gliding` §8 mism=0 (CPU≡GPU), §10 **binds=11 detach=10 invalid=0** — recruitment rises
(joint dead-zones + margin removed), CPU/GPU identity + solver health preserved; the trajectory decorrelates sooner
(firstDiv 178→4) as expected for a changed binding decision. `-traj` is identical in both modes (its single mid-filament
pre-bound motor is unaffected by joint ownership).

## §7 — Quick density sweep (deliverables 8, 9, 10)
explicit-s2-l40 GPU device-resident path, 2 seeds, equil 8000 / measure 20000 @ dt=2.5e-6. **Runtime: 1383 s (23 min)
for all 24 runs** — well under half the earlier production sweep. **0 invalid across every run.** (GPU velocity carries
the documented gliding bistability ⇒ 2-seed error bars are wide; meanBound — basin-robust — is the reliable separator.)

| density | LEGACY speed \|v\| (mB, cont) | CANONICAL speed \|v\| (mB, cont) |
|--:|--:|--:|
| 100 | 0.99 ± 0.19 (0.3, 0.27) | 1.04 ± 0.03 (0.6, 0.45) |
| 200 | 1.06 ± 0.34 (0.6, 0.44) | 1.21 ± 0.55 (1.0, 0.62) |
| 400 | 1.78 ± 0.19 (1.3, 0.76) | **2.18 ± 0.19 (2.4, 0.92)** |
| 700 | 3.02 ± 0.20 (2.9, 0.95) | **3.11 ± 0.01 (5.0, 0.99)** |
| 1500 | 3.18 ± 0.09 (5.4, 0.99) | **3.78 ± 0.20 (10.2, 1.00)** |
| 3000 | 4.14 ± 0.08 (11.1, 1.00) | 3.96 ± 0.11 (**19.9**, 1.00) |

- **Recruitment (meanBound) clearly higher at ρ200–1500** — 1.7–1.9× (5.0 vs 2.9 @ρ700; 10.2 vs 5.4 @ρ1500). ✓
- **Continuity equal-or-better at every density** (0.45 vs 0.27, 0.92 vs 0.76, 0.99 vs 0.95, 1.00 vs 0.99). ✓
- **Onset/rising-region speed faster** (leftward shift): ρ400 2.18 vs 1.78, ρ1500 3.78 vs 3.18. ✓
- **ρ3000 plateau PRESERVED within ~4 %** (3.96 vs 4.14 µm/s) despite ~1.8× higher canonical occupancy (19.9 vs 11.1) —
  the extra bound motors do NOT raise the Vmax/cycle-limited plateau; the curve shifts LEFT, not up. ✓
- **invalid = 0** everywhere. ✓

## §9 — Plots (deliverable 9)
`docs/matsoa/plots/rollout_density_sweep.png` — (left) speed vs density, legacy vs canonical, 2-seed error bars, ~4 µm/s
plateau line; (right) meanBound (solid) + continuity (dashed) vs density. **Screening sweep — labeled as such; no
Michaelis–Menten fit** (2-seed GPU velocity is too noisy to fit). Main effect: **onset-region leftward shift +
approximate plateau preservation + recruitment increase.**

## §10 — dt cross-check (deliverable 11) — canonical does NOT introduce a stronger dt sensitivity
Canonical, ρ700, matched physical duration, dt vs dt/2 (2 seeds):

| dt | bindRate | meanBound | continuity | onset F8 p99 | invalid |
|--|--:|--:|--:|--:|--:|
| dt (2.5e-6) | 3.30 | 5.17 | 0.997 | 7.18 | 0 |
| dt/2 | 3.56 (+7.7 %) | 5.48 (+6 %) | 1.000 | 7.12 | 0 |

bindRate dt-change +7.7 % (2 seeds) — **comparable to / slightly below the legacy/default +12 %** from the resolution
study, i.e. the canonical path does NOT introduce a stronger timestep sensitivity; onset F8 p99 stable (7.1), 0 invalid.
(This is the same modest bind-flux dt effect already tracked to the standing cross-bridge-substep family, dt/2-resolved —
unchanged by the ownership rollout.)

## §11 — Adoption recommendation (deliverable 14) — APPROVE
Every §11 condition passes: half-open ownership works in every rolled binding path (matBindExplicit, gate2D/nearestSeg2D,
MatSoaSlice); no finite physical margin remains in canonical paths (ε=1e-6 µm); **CPU/GPU decisions agree (mism=0)**; no
duplicate or out-of-range binding coordinates (one owner per point, bindArc∈[0,segLength]); static force continuity
passes; the screen shows a clear recruitment increase + leftward shift; the ρ3000 plateau is preserved within ~4 %;
onset-force quality is unchanged (p99 ~7.1 pN); 0 invalid / 0 solver failures; dt sensitivity is not materially worse.
**⇒ Make canonical half-open zero-margin ownership the default (done — `LEGACY_OWNERSHIP=false`); retain legacy only for
regression (`-Dsoftbox.legacyOwnership=true` / `-legacy`); the 50 nm rule is deprecated. This is a discretization /
ownership correction, not a fit-parameter change.**

## §15 — Commands, seeds, files (deliverable 15)
```
./scripts/build.sh
java … -Dsoftbox.legacyOwnership=true softbox.ExplicitCompleteMatHarness -traj|-gliding   # legacy regression
java …                              softbox.ExplicitCompleteMatHarness -traj|-gliding   # canonical (default)
java … softbox.JointMarginProbe [-bend 5|15|30]                                          # §5 joint probe
./scripts/binddiag_run_rollout_sweep.sh                                                  # §7 GPU density sweep
./scripts/run_binddiag.sh -mode recruit -density 700 -dtdiv 1|2 …                        # §10 dt cross-check
./scripts/run_binddiag.sh … -legacy …                                                    # any harness in legacy mode
```
**Modified files:** `TwoBodyConverterMotor.java` (flag + `nearestSeg2D`/`gate2D` + 7 g7 sites), `TwoBodyBeamAnalyticGpu.java`
(`matBindExplicit` bindP[12] mode), `MatSoaSlice.java` (`matGeomGate` + `packGeomGateParams`), `ExplicitCompleteMatHarness.java`
(`packExMat` bindP[10]/[12]), `ExplicitBindDiagHarness.java` (`-legacy`, canonical default, `evalGates`). New:
`scripts/binddiag_run_rollout_sweep.sh`, `docs/matsoa/plots/rollout_density_sweep.png`. seeds {101,202}; sweep equil 8000
/ measure 20000 @ dt=2.5e-6.
```

## Final status block
- `OWNERSHIP ROLLOUT:` **canonical half-open + clamped-closest-point rolled through matBindExplicit (GPU+CPU),
  nearestSeg2D + gate2D + 7 g7 sites (all two-body CPU assays), MatSoaSlice (calibrated slice), packExMat/packGeomGateParams;
  one toggle `LEGACY_OWNERSHIP` (default canonical).**
- `FINITE SEGMENT MARGIN:` **REMOVED** (canonical ε=1e-6 µm; 50 nm retained only in the deprecated legacy regression mode).
- `CPU/GPU BIND IDENTITY:` **PASS — mism=0** (canonical one-step replay; short-trajectory glide bit-identical CPU/GPU).
- `JOINT DEAD ZONE:` **eliminated** — clean seg→seg handoff at the joint, g7ε PASS both sides, bindArc∈[0,segLength].
- `LOW-DENSITY RECRUITMENT:` **higher** — meanBound 1.7–1.9× at ρ200–1500 (5.0 vs 2.9 @ρ700), continuity ↑.
- `DENSITY CURVE SHIFT:` **LEFT** — faster onset/rising speed (ρ400 2.18 vs 1.78 µm/s), reliable gliding at lower density.
- `HIGH-DENSITY PLATEAU:` **preserved within ~4 %** (ρ3000 3.96 vs 4.14 µm/s) despite ~1.8× occupancy.
- `ONSET-FORCE QUALITY:` **unchanged** (p99 ~7.1 pN across densities + dt).
- `DT SENSITIVITY:` **not materially worse** (bindRate dt→dt/2 +7.7 %, ≲ legacy +12 %; onset F8 flat, 0 invalid).
- `RUNTIME OF QUICK SWEEP:` **1383 s (23 min), 24 GPU runs, 0 invalid.**
- `CANONICAL DEFAULT CHANGE:` **APPROVE** — half-open zero-margin ownership is the default; legacy kept for regression only.
- `NEXT STEP:` promote the change in the model docs / current-state records; (optional) widen seeds/densities for a
  publication-grade curve; roll the same canonical arithmetic into any future segmented-filament binding path.
```
