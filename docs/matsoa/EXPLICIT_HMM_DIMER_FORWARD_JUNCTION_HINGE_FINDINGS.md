# Explicit HMM dimer — binding-triggered distal-S2 hinge (forward junction advance)

Tests the hypothesis that a localized conformational hinge at the distal end of the shared paired S2 (just below the
fork), activated when one head strongly binds, bends toward the barbed end and **translates the whole S2–fork junction
forward past the occupied actin site**, so the free head searches from an advanced junction. This is the successor to
the failed axial lead–lag (`EXPLICIT_HMM_DIMER_AXIAL_LEAD_LAG_FINDINGS.md`), which could not move the constraint-pinned
pivots. New mechanism `dirMech = 9`, default OFF; 5.4 nm occupancy exclusion kept on; single-head model /
TwoBodyConverterMotor / MotorModel / shared-S2 material / shared viewer unchanged; gates + exclusion fixtures re-pass
byte-identically. CPU-only.

## Headline — FAILS the mandatory fork-advance gate (J1), and is dynamically unstable
The mandatory J1 test measures the actual fork-junction position (`hingeAdvance = dot(pFork_active − pFork_baseline,
bHat)`), and the mechanism **does not move the fork forward — it can only move it backward**. Two decisive measured
facts explain why, and they are geometric (not an implementation bug — J6 internal-balance passes at 1e-18 pN):

1. **The distal-S2 tangent is already parallel to the barbed axis** (`tS2 · bHat = 0.992`). The shared S2 runs
   straight along the filament, and the fork sits at the **+x (barbed) tip of the straight anchored beam**. A
   barbed-ward bend therefore has **no forward room** — any bend rotates the fork backward or transversely (contour is
   conserved; the straight beam already maximizes the fork's barbed reach).
2. **The fork already sits ~21 nm BEHIND the bound site** (`baseline junctionLead = dot(pFork − pBind, bHat) = −20.99
   nm`). The bound head reaches ~21 nm *forward* of the fork to attach, so the premise "advance the fork *past* the
   bound site" would require a +21 nm fork translation — impossible for a distal bend of a straight, anchored,
   filament-parallel S2.

Measured J1: `hingeAdvance` goes 0 → **−0.23 nm** as φHinge rises 0 → 30° (monotonically *backward*); the junction
lead only becomes *more* negative (−20.99 → −21.22 nm). Across the full stiffness sweep (0.02×–1.0×) the advance is
negative everywhere. **J1 FAIL.** And dynamically the activated hinge is **unstable** (φ20/stiff1.0 → joint gap 3126
nm, peak F8 297 pN, invalid states); the only stable amplitude (φ2/stiff0.02, gap ~3.96 nm) is inert-to-backward
(8-seed forward fraction **0.30 < C0 0.652**) and still marginal (1/8 seeds gap > 10 nm).

**Conclusion: FORWARD-JUNCTION HINGE RESOLVES FORWARD BIAS = NO. READY TO PROMOTE = NO.** The hinge cannot advance
the fork because the S2 is filament-parallel with the fork at the beam's forward tip and the fork already trails the
bound site by ~21 nm. A genuine forward-junction advance would require a **structural** change to the assay geometry —
anchoring the S2 at an angle to the filament (anchor pointed-ward of the fork, giving forward angular room), or giving
the S2 slack (contour longer than the straight anchor→fork distance) so straightening advances the fork — not a bend
of the present straight beam. Absent that, the graded rearward penalty (C4, γ ≈ 0.25) remains the honest
phenomenological fallback.

## 1. Coordinates + metrics (deliverables 1, 2)
`bHat` = barbed tangent (+x). `pBind` = the bound head's actin attachment (world). `pFork` = fork junction (`nd[Ms]`).
`junctionLead_nm = dot(pFork − pBind, bHat)·1e3`; `hingeAdvance_nm = dot(pFork_active − pFork_baseline, bHat)·1e3`;
`freeF8Lead_nm = dot(pFreeF8 − pBind, bHat)·1e3`. All measured directly from `pFork` — never from branch angle, pivot
separation, world-x, or rendered displacement (per §1).

## 2–4. Localized distal-S2 hinge (deliverables 3, 4, 6)
Mechanism 9 acts on the **single shared hinge immediately below the fork** (the shared interior hinge whose child node
is the fork, `h[2] == Ms`) — the long lower S2 shaft and its material are untouched (shared-S2 contour change < 0.003%
in every run; the paired coiled-coil interpretation is preserved). When one head is strongly bound, that hinge's
preferred tangent is rotated toward `bHat` by φHinge (polarity-aware `rotToward`; it reverses with filament polarity),
via a conservative angular energy `Uhinge = 0.5·kHinge·θErr²` with kHinge = dirConvStiffMult × the shared-hinge
bending stiffness — forces/torque derived from the energy, no coordinate assignment, no external world force (J6:
|net node-force| = 1.1e-18 pN). Smooth ramped activation (H1 bind / H2 post-stroke / H3 stroke-ramped); default OFF ⇒
byte-identical.

## 5. Deterministic fixtures (deliverables 7, 9)
`./scripts/run_hmm_dimer_forward.sh -hinge`:
- **Baseline probe:** junctionLead −20.99 nm (fork 21 nm behind the bound site); distal-S2 tangent · bHat = 0.992.
- **J1 monotonic fork advance — FAIL:**
```
   phiHinge= 0° → hingeAdvance −0.000 nm, junctionLead −20.993 nm, freeF8Lead −0.005 nm
   phiHinge= 5° → hingeAdvance −0.008 nm, junctionLead −21.001 nm, freeF8Lead −0.029 nm
   phiHinge=10° → hingeAdvance −0.028 nm, junctionLead −21.021 nm, freeF8Lead −0.072 nm
   phiHinge=20° → hingeAdvance −0.105 nm, junctionLead −21.098 nm, freeF8Lead −0.218 nm
   phiHinge=30° → hingeAdvance −0.230 nm, junctionLead −21.223 nm, freeF8Lead −0.440 nm
```
  The fork moves *backward*; it never advances. Stiffness sweep (0.02×–1.0× at φ20) is negative throughout
  (−0.001 → −0.105 nm). **J1 FAIL** (needs monotonic advance > 1 nm).
- **J2 junction passes bound site — FAIL:** no parameter gives junctionLead > 0 (baseline −21 nm, only worsens).
- **J6 internal balance — PASS:** |net node-force| 1.1e-18 pN.
- **J3 polarity / J4 A-B swap / J5 mirror:** moot — there is no forward advance to reverse/swap/mirror (the polarity
  logic itself is correct, as the mech-8 fixtures established, but the fork does not move here).
- **J7 detached recovery:** activation is state-gated + ramped, relaxing to baseline on detach (default byte-identical).

## 6. Mechanical decomposition (deliverable 9)
The intended causal sequence (distal S2 bends → fork advances → bound branch trails → free branch originates from an
advanced fork → free F8 search moves forward) breaks at step 2: **the fork does not advance** (hingeAdvance ≤ 0). The
free F8 lead moves *backward* with φ (−0.44 nm at φ30). Intrinsic converter stroke 8.00 nm and head-vs-anchor 7.60 nm
are unaffected (the hinge is upstream of the heads). There is no reposition-vs-bindability trade to analyze because
there is no reposition.

## 7. Dynamic stability + health (deliverables 12, 13, 15)
Static solver clean (max gap 0.02 nm). **Dynamically the activated hinge is unstable:** φ20/stiff1.0 → gap 3126 nm,
peak F8 297 pN, 3 invalid states; φ5/stiff0.02 → gap 21.8 nm; only φ2/stiff0.02 stays near-healthy (gap 3.96 nm) but
is inert. A stable, effective amplitude does not exist — the same failure mode as the lead-lag branch torque.

## 8. Reference comparison (deliverable 17) + dynamic screen (deliverables 15, 16)
Because J1 fails and the mechanism is unstable/inert, no candidate qualified for high-stat promotion. An 8-seed
dynamic check of the only stable amplitude (φ2/stiff0.02) gives **forward binds 3 / backward 7 (forward fraction
0.30)** — *worse* than the C0 baseline (0.652) — with 1/8 seeds still exceeding a 10 nm joint gap. Against the standing
references (identical seeds/config, from the directional + lead-lag comparisons):
```
config             fwdFrac   clustered95%CI   note
C0 baseline        0.652–0.667  [0.33, 0.83]  reference
distal-S2 hinge    ~0.30 (8-seed)             WORSE + marginally unstable (fork does not advance)
C4 penalty γ0.25   0.842–0.867  [0.50, 1.00]  resolved
C3 hard veto       1.000        [1.00, 1.00]  resolved (upper bound)
```
Only the phenomenological C4/C3 resolve the bias; the hinge does not.

## 9. `-3js` movies (deliverable 18)
Matched (seed 13, 16000 steps, stride 15 → 1068 frames, exclusion 5.4 nm; fork sphere off, rod hidden):
| movie | dir | config | outcome |
|---|---|---|---|
| A symmetric control | `threejs_hmm_dimer_hinge_ctrl/` | C0 | fwd 2 / bwd 1 |
| B low-amplitude stable hinge | `threejs_hmm_dimer_hinge_mech/` | mech 9, φ2°/stiff0.02 | inert (fork does not advance; gap 3.96 nm) |
| F graded-penalty reference | `threejs_hmm_dimer_hinge_penalty/` | C4 γ0.25 | fwd 3 / bwd 0 (clean forward-only) |
(Movies C/D "~2.5 / ~5.4 nm junction-advance" and E "polarity-reversed advance" are not rendered — no configuration
produces any positive junction advance; every effective amplitude is dynamically unstable.)

## 10. Interpretation (deliverable 20)
This effective coarse-grained hinge does not represent a literal biological S2 hinge; the finding is architectural:
in the present assay the shared S2 is anchored **parallel** to the filament with the fork at its barbed tip and the
bound head reaching ~21 nm forward, so a distal bend cannot carry the junction past the bound site. A forward-junction
advance is only possible with a different **structural** layout (angled/pointed-ward anchor, or slack S2), tested
against this same J1 gate. If a resolved forward bias is required now, the graded rearward penalty (C4 γ≈0.25) is the
honest phenomenological fallback (it resolves the bias without a hard veto).

## 11. Commands / seeds / timestep / stride (deliverable 19)
- `dt = 2.5e-6 s`; deterministic fixtures Brownian-off; dynamic checks seeds 1–17 × 16000 steps; movies seed 13.
```
./scripts/run_hmm_dimer_forward.sh -hinge                        # baseline probe + J1 monotonicity + stiffness sweep + J6 + stroke
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_hinge_ctrl    -seed 13 ... -excl 5.4                                       # Movie A
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_hinge_mech    -seed 13 ... -excl 5.4 -mech 9 -mechamp 2 -mechstiff 0.02 -mechtrig 1   # Movie B
./scripts/run_hmm_dimer_3js.sh -3js threejs_hmm_dimer_hinge_penalty -seed 13 ... -excl 5.4 -rearpen 0.25                          # Movie F
```

## 12. Modified-file inventory (deliverable 20)
- `softbox/ExplicitHmmDimer.java` — mechanism 9 (distal-S2 hinge): the shared hinge below the fork gets a
  state-dependent preferred bend toward bHat with tunable stiffness, in `bendEnergy`. Gated on `dirMech==9`/`dirAct`;
  default byte-identical.
- `softbox/ExplicitHmmDimerForwardHarness.java` — `-hinge` (baseline probe + J1 + stiffness sweep + J6 + stroke),
  `hingeCase`/`hingeSurvey` with `junctionLead`/`hingeAdvance`/`freeF8Lead` metrics.
- `softbox/ExplicitHmmDimer3jsHarness.java` — reused (mechanism 9 rides the existing `-mech`/activation path).
- `docs/matsoa/EXPLICIT_HMM_DIMER_FORWARD_JUNCTION_HINGE_FINDINGS.md` — this report.
- **Unchanged:** single-head model, `TwoBodyConverterMotor.java`, `MotorModel.java`, `sim_viewer_boa.html`, shared-S2
  material, prior harness defaults.

## 13. Rejected-candidate log (deliverable 21)
- **J1 FAIL (all φ, all stiffness, all lengths):** hingeAdvance ≤ 0 (fork retracts) — the distal S2 is filament-parallel
  with the fork at the straight-beam barbed tip; no forward room.
- **J2 FAIL:** junctionLead stays ≈ −21 nm (fork never passes the bound site).
- **Dynamic instability:** φ ≥ 5° destabilises the solve (gap 21–3126 nm, peak up to 297 pN, invalid states); only
  φ2/stiff0.02 is near-stable and it is inert-to-backward (forward fraction 0.30 < baseline 0.652).
- Net: rejected on the mandatory fork-advance gate AND on stability — no candidate promoted.

## 14. FINAL STATUS BLOCK
```
FORWARD-JUNCTION HINGE IMPLEMENTED: YES (mechanism 9, distal shared hinge below the fork, default OFF)
HINGE LOCALIZED TO DISTAL S2: YES (single hinge below the fork; lower S2 shaft + material untouched; contour change <0.003%)
BASELINE JUNCTION LEAD: −20.99 nm (fork sits 21 nm BEHIND the bound actin site)
BEST HEALTHY HINGE ADVANCE: ≤ 0 nm (monotonically backward; −0.23 nm at φ30°)
BEST HEALTHY JUNCTION LEAD: −20.99 nm (never improves; worsens with φ)
JUNCTION PASSES BOUND SITE: NO
MINIMUM PARAMETERS TO PASS BOUND SITE: none exist (fork is at the straight-beam +x tip; needs +21 nm, geometrically impossible)
BEST HINGE LENGTH / TARGET ANGLE / STIFFNESS / TRIGGER / BOTH-BOUND RULE: — (no candidate advances the fork)
FREE-F8 FORWARD SHIFT: ≤ 0 nm (−0.44 nm at φ30°, backward)
+5.4 NM SITE CLEARANCE ≈ −5.4 NM SITE CLEARANCE (no forward advantage)
FORWARD SITE ORIENTATION GATE: PASS (unchanged; but no repositioning occurs)
BOUND-BRANCH FORCE / FREE-HEAD PRELOAD / ANCHOR REACTION: static baseline; dynamically unstable when activated (peak F8 up to 297 pN)
HINGE ENERGY: sub-kBT static; drives a dynamic instability when activated at useful amplitude
SHARED-S2 CONTOUR CHANGE: < 0.003 % (intact paired coiled coil preserved)
INTRINSIC CONVERTER STROKE: 8.00 nm ; HEAD STROKE RELATIVE TO ANCHOR: 7.60 nm (preserved)
MAX JOINT GAP: 0.02 nm static; 3.96 nm (φ2/stiff0.02) → 3126 nm (φ20/stiff1.0) DYNAMIC (unstable)
TIMESTEP CONVERGENCE: N/A (mechanism rejected before a dt study is meaningful)
INTERNAL FORCE/TORQUE BALANCE: PASS (1.1e-18 pN) ; SMOOTH ACTIVATION: PASS (default byte-identical) — but activation is a dynamic instability
TOTAL ONE-HEAD-BOUND EXPOSURE: ~140 ms (8-seed stable-hinge check)
TIME WITH JUNCTION AHEAD OF BOUND SITE: 0 % (junction never leads)
ACCEPTED FORWARD BINDS: 3 ; ACCEPTED BACKWARD BINDS: 7 (stable φ2/stiff0.02, 8 seeds)
FORWARD FRACTION: 0.30 (WORSE than C0 0.652)
CLUSTERED 95% CI: not computed (mechanism rejected on J1 + stability)
FORWARD/BACKWARD RATE RATIO: 0.43 (stable hinge) vs 2.0 (C0)
SECOND-HEAD BINDING RATE VS C0: lower + destabilised
TWO-HEAD-BOUND FRACTION VS C0: lower ; DOUBLE-BOUND DWELL VS C0: shorter
JUNCTION-LEAD/FORWARD-BIND ASSOCIATION: none (junction never leads ⇒ no causal advance to associate)
POLARITY REVERSAL / SPATIAL MIRROR / A-B RELABEL: moot (no advance to test; the rotToward polarity logic is itself correct — mech-8 fixtures)
INVALID STATES: 0 static / up to 3 dynamic (unstable configs)
SOLVER FAILURES: 0 (the dynamic failure is large joint gaps, not NaN)
CONTROL 3JS: threejs_hmm_dimer_hinge_ctrl/ (1068 frames)
LOW-HINGE 3JS: threejs_hmm_dimer_hinge_mech/ (1068 frames; φ2°/stiff0.02, inert)
2.5 NM / 5.4 NM JUNCTION-LEAD 3JS: — (not achievable) ; POLARITY-REVERSED 3JS: — (no advance)
GRADED-PENALTY REFERENCE 3JS: threejs_hmm_dimer_hinge_penalty/ (1068 frames; C4 γ0.25)
HINGE CREATES FORWARD SEARCH ORIGIN: NO
HINGE RESOLVES FORWARD BIAS: NO
OUTPERFORMS GRADED PENALTY: NO (worse than the no-mechanism baseline)
READY TO PROMOTE: NO
RECOMMENDED DEFAULT MODE: keep C0 (no directional mechanism); if a resolved forward bias is required, the graded rearward penalty γ≈0.25 (C4) is the least-artificial effective option
NEXT STEP: a distal-S2 bend cannot advance a fork that is already at the barbed tip of a straight, filament-parallel, anchored S2 and 21 nm behind the bound site. A forward-junction advance needs a STRUCTURAL geometry change — anchor the S2 at an angle (anchor pointed-ward of the fork, giving forward angular room) or add S2 slack (contour > straight anchor→fork distance) so straightening advances the fork — tested against this same J1 gate; else adopt the C4 γ0.25 phenomenological penalty.
```

**Success criterion (not met):** the binding-triggered distal-S2 hinge did **not** advance the common fork junction
toward the barbed end (`hingeAdvance ≤ 0`; `junctionLead` stays ≈ −21 nm, target > 0), did not shift the free head's
search forward, degraded rather than resolved the forward bias (0.30 < 0.652), and was dynamically unstable at every
effective amplitude — because the S2 is anchored parallel to the filament with the fork already at its barbed tip. The
stroke, shared-S2 integrity, and internal force balance are preserved, but the mechanism is rejected; a structural
geometry redesign or the phenomenological penalty is required.
