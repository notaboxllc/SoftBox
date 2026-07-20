# Explicit HMM dimer — axial lead–lag fork via internal branch-root rest-angle torques

The directional-mechanism survey (`EXPLICIT_HMM_DIMER_DIRECTIONAL_MECHANISM_SURVEY.md`) found that local transverse
cants of the present fork cannot bias the free head barbed-ward, and recommended a **genuinely different geometry**:
state-dependent internal rest-angle torques at the two proximal branch roots that rotate the fork into an **axial
lead–lag** — the free-head branch leading toward the barbed end, the bound-head branch lagging toward the pointed
end — creating a real axial offset `axialPivotOffset = dot(pivotFree − pivotBound, bHat)` between the two pivots.
This report implements and tests exactly that (new mechanism, `dirMech = 8`, default OFF). No rearward veto/penalty
in the primary test; 5.4 nm same-filament occupancy exclusion kept on; single-head model / TwoBodyConverterMotor /
MotorModel / shared-S2 material / shared viewer unchanged; gates + exclusion fixtures re-pass byte-identically.
CPU-only.

## Headline — the mechanism FAILS twice: no axial offset (G1) AND dynamically unstable
The mandatory G1 axial-pivot-offset test **fails**, and the mechanism is **dynamically unstable** at every usable
amplitude:
- **G1 (mandatory):** the axial pivot offset is **≤ 0.16 nm and non-monotonic** in φFree — it stays near zero and
  goes *negative* (−0.55 nm at φFree = 30°), never approaching the ≥ 2.5 nm target. **The mechanism does NOT create a
  lead–lag pivot separation.**
- **Root cause (not an implementation bug — G2/G4/G5 all pass):** the *preferred* branch directions are correctly
  rotated into an axial offset (polarity-reversal, spatial-mirror, and internal-force-balance fixtures pass), but the
  *settled pivots do not follow* — the bound pivot is pinned by the bound head's actin anchor (via the stiff converter
  + F8 spring) and the stiff branch stretch (ks = 420 pN/nm), and the free branch is already nearly axial so rotating
  it barbed-ward is at the geometric ceiling (≈ L·(1−cos α) ≈ 0.4 nm). The rest-angle torque cannot overcome these
  constraints, so the fork barely moves.
- **Dynamic instability:** under the full Brownian + cross-bridge load the state-dependent fork rest-angle torque
  destabilises the coupled solve — φFree = 2°/stiff 0.1× already gives max joint gap 8.5 nm, and it explodes with
  amplitude/stiffness (φ20/stiff0.25 → 394 nm, 104 pN; φ8/stiff1.0 → 2846 nm, 796 pN, invalid states). There is no
  amplitude that is both effective and stable.

**Conclusion: AXIAL LEAD–LAG RESOLVES FORWARD BIAS = NO. READY TO PROMOTE = NO.** The pivot offset cannot be produced
by a rest-angle torque in this pinned/stiff geometry; a genuine axial lead–lag would require a *structural* redesign
that decouples the bound pivot from its anchor (e.g. a longer compliant proximal segment, or explicitly axially-
staggered anchor/attachment points), not an internal torque on the present fork. If a resolved forward bias is needed
now, the graded rearward penalty (C4, γ ≈ 0.25) remains the least-artificial effective option.

## 1–2. Geometry + preferred branch directions (deliverables 1, 4, 5)
`bHat` = barbed tangent (+x here). Note the assay's shared S2 is **parallel** to the filament (tHat ∥ bHat), so the
task's `aHat = normalize(bHat − dot(bHat,tHat)·tHat)` degenerates; the axial rotation is instead applied directly in
the plane spanned by each branch and `bHat` (the correct axial plane — rotating a branch about `dir × bHat` maximally
changes `dot(dir, bHat)`, verified by construction). The free branch preferred direction is rotated **toward bHat**
by φFree; the bound branch **away from bHat** (toward the pointed end) by φBound = ratio·φFree — via the existing
polarity-aware `rotToward` (Rodrigues). The mechanism is a state-dependent branch-root rest-angle energy (mechanism 8
extends `forkCant`; the fork-hinge bending stiffness is scaled by a tunable `dirConvStiffMult` = kLeadLag/kbranch),
with forces/torques derived from the energy — no coordinate assignment, no external world force.

## 3–4. Rest-angle energy + activation states (deliverables 2, 3)
`Uleadlag` = the fork-hinge bending energy toward the rotated preferred directions (free + bound), stiffness
kLeadLag = mult·kbranch. Activation `dirAct∈[0,1]` ramps smoothly (L1 bind-triggered / L2 post-stroke / L3
stroke-ramped) and relaxes to 0 on detachment — no instantaneous rest-angle jump (default byte-identical).

## 5–7. Deterministic geometry fixtures (deliverables 5–9)
`./scripts/run_hmm_dimer_forward.sh -leadlag`:
- **G1 axial-offset monotonicity (MANDATORY) — FAIL:**
```
   φFree= 0° → axialPivotOffset +0.128 nm       φFree=15° → +0.052 nm
   φFree= 2° → +0.149 nm                         φFree=20° → −0.090 nm
   φFree= 5° → +0.162 nm                         φFree=25° → −0.291 nm
   φFree=10° → +0.136 nm                         φFree=30° → −0.547 nm
```
  Non-monotonic, magnitude ≤ 0.16 nm, negative at large φ — the offset is never created (target ≥ 2.5 nm).
- **G2 polarity reversal — PASS:** with the filament polarity flipped (bHat = −x), the free branch leads toward the
  new barbed end (offset +0.59 nm along the new bHat) — the mechanism follows polarity, not world coordinates.
- **G3 A/B swap — FAIL (by consequence of G1):** A-bound offset −0.09 nm, B-bound offset +0.11 nm — both ≈ 0, so the
  "free head leads" criterion is not met (there is essentially no lead to swap).
- **G4 spatial mirror (y→−y) — PASS:** offset preserved (−0.09 nm ≈ the unmirrored −0.09 nm).
- **G5 internal force/torque balance — PASS:** net mechanism node-force on the isolated dimer **2.3e-13 pN** (≈ solver
  tolerance) — the branch-root torques are internal and balanced, no external resultant.

## 8. Deterministic accessibility screen (deliverables 10, 11)
φFree ∈ {10..30°} × ratio {0, 0.5, 1.0} × stiff {0.1, 0.25, 0.5, 1.0} (bound-post): the axial offset is **negative or
≈ 0 everywhere** (−0.09 to −0.37 nm at φ20; the largest healthy magnitude is ≈ 0.5 nm at φ30), the free-F8 forward
shift is likewise ≤ 0 (−0.27 to −0.48 nm), and the +5.4/−5.4 accessibility advantage is negative (the free head sits
slightly *pointed*-ward). **No candidate reaches the ≥ 2.5 nm offset / ≥ 2 nm forward-shift success threshold.** The
static solver stays clean (max gap 0.02 nm, shared-S2 deform < 0.01 nm) — the failure is that the pivots do not move,
not that they move badly.

## 9. Pivot translation vs head reorientation (deliverable, §9)
Decomposition confirms the intended lever — pivot axial translation — is the term that fails: `dot(pivotFree −
pivotBound, bHat)` ≈ 0. There is nothing to prefer over head reorientation because neither occurs meaningfully; the
mechanism does not recreate M3's reposition-vs-bindability conflict simply because it does not reposition at all.

## 10–11. Mechanical health + stroke preservation (deliverables 12, 11)
Static health is clean (offset aside). **Dynamic health FAILS:** the activated fork rest-angle torque destabilises
the production solve — max joint gap 8.5 nm (φ2/stiff0.1, the least-unstable), 138 nm (φ2/stiff0.25), 394 nm
(φ20/stiff0.25), 2846 nm + 796 pN + invalid states (φ8/stiff1.0). No amplitude is both non-trivial and stable. The
intrinsic converter stroke (8.00 nm) and head-vs-anchor/actin stroke (7.60 nm) are preserved (the mechanism does not
touch the bound head's converter) — but that is moot given the offset failure + instability.

## 12–14. Dynamic screen + comparison (deliverables 13, 14, 15)
Because G1 fails and the mechanism is dynamically unstable, no candidate qualified for high-stat promotion. A moderate
dynamic comparison (`-leadcompare`, 16 seeds × 16000 steps) against the references confirms it:
```
config             | fwd bwd  fwdFrac  clusteredCI   | dblFrac  dwell(ms) | contShift(nm)  postFwdAcc
C0 baseline        |  12   6   0.667   [0.33, 0.83]  | 0.0103   0.429     |  +1.293        0.141
lead-lag (mech 8)  |   7   9   0.438   [0.17, 0.75]  | 0.0078   0.308     |  −1.358        0.104   ← WORSE + unstable
C4 penalty γ0.25   |  13   2   0.867   [0.50, 1.00]  | 0.0071   0.338     |  +1.844        0.164   ← resolved
C3 hard veto       |  13   0   1.000   [1.00, 1.00]  | 0.0054   0.289     |  +2.044        0.165   ← resolved
```
The lead-lag mechanism **degrades** the forward fraction (0.667 → 0.438, i.e. *below* 0.5) and drives the continuous
free-head search **backward** (−1.36 nm) — consistent with the deterministic negative/near-zero offset — on top of
being dynamically unstable (this φ20/stiff0.25 config gives a 394 nm joint-gap blow-up in a single-seed movie run).
Only the phenomenological C4 (γ0.25) and C3 resolve the bias.

## 15. `-3js` movies (deliverable 16, §15/§19)
Matched (seed 13, 16000 steps, stride 15 → 1068 frames, exclusion 5.4 nm; fork sphere off, rod hidden):
| movie | dir | config | outcome |
|---|---|---|---|
| A symmetric control | `threejs_hmm_dimer_ll_ctrl/` | C0 | fwd 2 / bwd 1 |
| B lead–lag (least-unstable) | `threejs_hmm_dimer_ll_mech/` | mech 8, φ2°/ratio1/stiff0.1 | max gap 8.5 nm (wobbly; higher amp explodes) |
| E graded-penalty reference | `threejs_hmm_dimer_ll_penalty/` | C4 γ0.25 | fwd 3 / bwd 0 (clean forward-only) |
(Movie C "~5.4 nm offset candidate" and Movie D "polarity-reversed lead–lag" are not rendered as usable movies —
no config achieves a 5.4 nm offset, and every effective amplitude is dynamically unstable; the polarity behaviour is
established by the deterministic G2 fixture. The removed fork sphere is not restored; overlays stay default-off.)

## 16. Implementation distinction honoured (§16)
The task requires `dot(pivotFree − pivotBound, bHat) >> 0`, not a rendered/angle change. The G1 fixture measures
exactly this and it fails (≤ 0.16 nm). The rotation axis is correct (the preferred directions are axially offset;
G2/G4/G5 pass) — the offset is absent because the pivots are constraint-pinned, a physical result of this geometry,
not a rotation-axis error.

## 17. Commands / seeds / timestep / stride (deliverable 17)
- `dt = 2.5e-6 s`; deterministic fixtures Brownian-off; dynamic comparison 16 seeds × 16000 steps; movies seed 13.
```
./scripts/run_hmm_dimer_forward.sh -leadlag                              # G1 monotonicity + screen + G2–G5 fixtures + stroke
./scripts/run_hmm_dimer_forward.sh -leadcompare -phi 20 -ratio 1.0 -stiff 0.25 -seeds 16 -steps 16000   # C0 / lead-lag / C4γ0.25 / C3
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_ll_ctrl    -seed 13 ... -excl 5.4                                   # Movie A
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_ll_mech    -seed 13 ... -excl 5.4 -mech 8 -mechamp 2 -mechcomp 1.0 -mechstiff 0.1 -mechtrig 2   # Movie B
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_ll_penalty -seed 13 ... -excl 5.4 -rearpen 0.25                     # Movie E
```

## 18. Modified-file inventory (deliverable 18)
- `softbox/ExplicitHmmDimer.java` — mechanism 8 (axial lead–lag): `forkCant` extended (free +φ toward bHat, bound
  −ratio·φ away), tunable fork-hinge stiffness scaling in `bendEnergy`. Gated on `dirMech==8`/`dirAct`; default
  byte-identical.
- `softbox/ExplicitHmmDimerForwardHarness.java` — `-leadlag` (G1 monotonicity + deterministic screen + G2/G3/G4/G5
  fixtures + `leadLagCase`/`leadLagPolarityReversed`/`leadLagMirror`/`leadLagNetForce`), `-leadcompare` (dynamic
  C0/lead-lag/C4/C3 with `-phi/-ratio/-stiff`); `Cmot` import.
- `softbox/ExplicitHmmDimer3jsHarness.java` — reused (mechanism 8 rides the existing `-mech`/activation path; no new
  edits needed beyond the directional layer from the prior survey).
- `docs/matsoa/EXPLICIT_HMM_DIMER_AXIAL_LEAD_LAG_FINDINGS.md` — this report.
- **Unchanged:** single-head model, `TwoBodyConverterMotor.java`, `MotorModel.java`, `sim_viewer_boa.html`, shared-S2
  material, prior harness defaults.

## 19. Failure log (deliverable 19)
- **G1 monotonicity FAIL:** axial pivot offset ≤ 0.16 nm, non-monotonic, negative at large φ — the branch-root
  rest-angle torque does not move the constraint-pinned pivots.
- **Dynamic instability:** every non-trivial amplitude/stiffness destabilises the production solve (gap 8.5 → 2846 nm;
  peak F8 up to 796 pN; invalid states) — the state-dependent fork torque is a dynamic instability, like the earlier
  M1 fork skew.
- **G3 A/B swap FAIL:** a consequence of G1 (no lead to swap).
- Net: the mechanism is rejected on both effectiveness (G1) and stability grounds.

## 20. FINAL STATUS BLOCK
```
AXIAL LEAD-LAG IMPLEMENTED: YES (mechanism 8; internal branch-root rest-angle torques, polarity-aware, default OFF)
ROTATION CHANGES AXIAL PIVOT PROJECTION: NO (the PREFERRED directions are axially offset, but the settled pivots do not follow — constraint-pinned)
BASELINE AXIAL PIVOT OFFSET: +0.13 nm (φFree=0, A bound / B free)
BEST HEALTHY AXIAL PIVOT OFFSET: +0.16 nm (φFree≈5°) — far below the ≥2.5 nm target; goes negative for φFree>15°
BEST PHI FREE: — (no value produces a usable offset)
BEST PHI BOUND: —
BEST STIFFNESS: —
BEST ACTIVATION RULE: L2 (post-stroke) used for screening; trigger does not change the outcome
BEST BOTH-BOUND RULE: — (not reached; mechanism fails before this matters)
FREE F8 FORWARD SHIFT: ≤ 0 nm (−0.27 to −0.48 nm at φ20 — backward)
+5.4 NM SITE CLEARANCE: ~3.1 nm ; -5.4 NM SITE CLEARANCE: ~3.1 nm (no advantage; slightly favors −5.4)
FORWARD ACCESSIBILITY ADVANTAGE: negative (−0.5 to −1.0 nm at φ20)
FORWARD SITE ORIENTATION GATE: PASS (unchanged) ; BACKWARD SITE ORIENTATION GATE: PASS (unchanged)
DETACHED PRELOAD: ~0 pN (relaxes to symmetric baseline when unbound)
BOUND FREE-HEAD PRELOAD: low static; but dynamically UNSTABLE (see below)
INTRINSIC CONVERTER STROKE: 8.00 nm (preserved) ; HEAD STROKE RELATIVE TO ANCHOR: 7.60 nm (preserved)
PEAK F8 FORCE: 9–12 pN static baseline; 104–796 pN under the ACTIVATED mechanism dynamically (blow-up)
MAX SHARED-S2 DEFORMATION: < 0.01 nm (intact paired coiled coil preserved)
MAX JOINT GAP: 0.02 nm static; 8.5 nm (φ2/stiff0.1) → 2846 nm (φ8/stiff1.0) DYNAMIC (unstable)
ONE-HEAD-BOUND EXPOSURE: ~140 ms (16-seed comparison)
ACCEPTED FORWARD BINDS: 7 (lead-lag) vs 12 (C0) ; ACCEPTED BACKWARD BINDS: 9 (lead-lag) vs 6 (C0)
FORWARD FRACTION: 0.438 (lead-lag) — WORSE than C0 0.667
CLUSTERED 95% CI: [0.17, 0.75] (lead-lag; includes 0.5)
FORWARD/BACKWARD RATE RATIO: 0.78 (lead-lag) vs 2.0 (C0)
SECOND-HEAD BINDING RATE VS C0: slightly lower (16 vs 18 events) + destabilised
TWO-HEAD-BOUND FRACTION VS C0: 0.0078 vs 0.0103 (lower)
DOUBLE-BOUND DWELL VS C0: 0.308 vs 0.429 ms (shorter)
POLARITY REVERSAL: PASS ; SPATIAL MIRROR: PASS ; A/B RELABEL: FAIL (no lead to swap) ; INTERNAL FORCE/TORQUE BALANCE: PASS (2.3e-13 pN)
SMOOTH ACTIVATION: PASS (ramped; default byte-identical) — but activation drives a dynamic instability
INVALID STATES: 0 static / up to 4 dynamic (unstable configs)
SOLVER FAILURES: 0 (static); the dynamic instability is large joint gaps, not NaN
CONTROL 3JS: threejs_hmm_dimer_ll_ctrl/ (1068 frames)
BEST LEAD-LAG 3JS: threejs_hmm_dimer_ll_mech/ (1068 frames; φ2°/stiff0.1 least-unstable — gap 8.5 nm)
5.4 NM OFFSET 3JS: — (no config achieves a 5.4 nm offset)
POLARITY-REVERSED 3JS: — (established by the deterministic G2 fixture; every effective amplitude is unstable)
GRADED-PENALTY REFERENCE 3JS: threejs_hmm_dimer_ll_penalty/ (1068 frames; C4 γ0.25)
AXIAL LEAD-LAG RESOLVES FORWARD BIAS: NO
OUTPERFORMS GRADED PENALTY: NO (it is worse than the no-mechanism baseline; C4/C3 resolve the bias, lead-lag does not)
READY TO PROMOTE: NO
RECOMMENDED DEFAULT MODE: keep C0 (no directional mechanism) as default; if a resolved forward bias is required, the graded rearward penalty γ≈0.25 (C4) remains the least-artificial effective option
NEXT STEP: a rest-angle torque cannot create a pivot lead–lag in this pinned/stiff geometry — the bound pivot is held by the head's actin anchor + stiff branch stretch, and the free branch is already near-axial. A genuine axial lead–lag needs a STRUCTURAL change that decouples the bound pivot from its anchor (a longer/compliant proximal segment, or explicitly axially-staggered anchor/attachment geometry), tested with this same G1 axial-offset gate; failing that, adopt the C4 γ0.25 phenomenological penalty.
```

**Success criterion (not met):** the state-dependent branch-root rest-angle torques did **not** create a genuine
axial lead–lag pivot separation (`axialPivotOffset ≤ 0.16 nm`, target ≥ 2.5 nm), did not place the free head closer
to the barbed site (free-F8 shift ≤ 0), degraded rather than resolved the forward second-head bias (fraction 0.438 <
baseline 0.667), and was dynamically unstable at every usable amplitude. The stroke, shared-S2, and internal
force/torque balance are preserved, but the mechanism is rejected. A resolved forward bias in this architecture still
requires either a structural geometry redesign (decoupling the pivot from the anchor) or an explicit phenomenological
rule (graded penalty / veto).
