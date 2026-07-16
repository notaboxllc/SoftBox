# Soft Box Project Journal

### 2026-07-15 — EXPERIMENT 4I: calibrate the cheap 4F pivot surrogate DIRECTLY to the explicit 4G S2 beam — OUTCOME B (faithful mechanics, modest ensemble drift)

Default-off `-exp4i` / `-twobody-s2-surrogate-calibration`, **CPU-only**. Derives the 4F reduced-pivot-law parameters FROM the 4G mechanistic beam so a large sim can use 4F as a directly-linked surrogate for 4G. **The surrogate = the validated 4F `supForce` law + params fitted to 4G + ONE optional smooth Euler compression-buckling branch, ALL gated behind `CAL_ON` (default false) ⇒ 4F AND 4G stay byte-identical (Gate 1, max|Δ|=0).** Validated head/converter/lever/F8/gate/Lymn–Taylor chemistry+constants/RNG/filament mechanics/`BoA-v1ref` UNTOUCHED (new methods only; the `supBuckle*` fields read only by the guarded branch). `BoA-v1ref` byte-clean; production untouched. Report: `docs/TWOBODY_4G_TO_4F_CALIBRATION.md`; deliverables `RUN_LOGS/twobody_4g_to_4f_calibration/` (8 CSVs + `INDEP_VALIDATION/` package for a fresh session).

- **The ONE common coordinate (§2, the 4H lesson):** both models compared in the PIVOT displacement `d=P−P0` + the PIVOT REACTION force — 4G via `s2RelaxHold` (interior beam relaxed, both ends pinned); 4F via `supForce`. NOT 4G pinned-tip vs 4F external-lever (different generalized coordinates). Transverse sampled **free-axial** (`calS2SampleTrans`: pivot pinned transverse, axial FREE to foreshorten) — pinning full-3D engages the fixed-contour STRETCH (the 4H `kTrans` artifact), not the bending a free pivot feels.
- **Phase A/B (fit at L40, train split):** k_ax=105 pN/nm (= ks/M = EA/L, EXACT); k_tr=0.026 pN/nm (free-axial bending ≈3EI/L³); rMax=20.3 nm (visited envelope). δ DROPPED (4G holds no rest slack — it straightens). Effective potential separable ⇒ symmetric tangent; passive ∮F·dl≈−5e-29 J (conservative).
- **§6 compression = a LENGTH-dependent asymmetry; L40 does NOT realize it.** 4G-L40 compression stays STRAIGHT-STIFF (the documented 4G §6 probe limitation; Euler crit 4.4 pN = 0.04 nm), and motors load the tail in TENSION anyway ⇒ the frozen L40 block reproduces 4G-realized = SYMMETRIC-STIFF compression. The Euler buckling (crit ∝1/L²) is a LONGER-beam feature, enabled+validated at L60 (Phase D). Faithful to 4G's own finding ("slack-to-taut relocates to buckling, appears only at L60").
- **Phase C — mechanical calibration ESSENTIALLY EXACT:** frozen-test force-vector error median 0.0% / p95 4.3% (VISITED); axial tangent 0.0%; visited transverse 3–4% — all PASS. stroke 6.90 vs 7.27 (−5%), pivot recoil identical (0.007). **BUT search rmsLat 7.6 vs 10.8 (−29%) + capture 357 vs 576 (−38%) DRIFT** — mechanically-derived k_tr gives a tighter search than 4G's actual wandering (a genuine ~1.5× mechanical-vs-observable ambiguity; softening k_tr→~0.017 recovers search at a small force cost). k_ext 0.64 vs 0.99 = the fixture-observable offset 4G §6 already flagged (both stiff/load-bearing).
- **Phase D — length transfer:** k_ax `1/L` scales EXACTLY at L20 (class B) but overpredicts 2× at L60 (70 vs 35.5 measured, class C — the longer beam's bending compliance softens the axial). k_tr `1/L³` within 18–31%. L60 buckling demonstrated (F_crit 1.97 pN, regime agreement 89%). ⇒ single transferable form NOT established across all L (downward to tweezers L20 = B; upward to exposed L60 = C).
- **Phase E — reduced mat (validation, NOT calibration):** cal-4F avgBound 1.73 vs 4G 1.53 (+13%, PASS ±15%), continuity 0.82 vs 0.80 (PASS), bind rate 1.55 = 1.55 (exact). Over-counts LOAD-BEARING (1.73 vs 1.03) — symmetric-stiff ⇒ always-taut (100% vs 4G 67%).
- **Phase F — cost:** cal-4F **1.34 µs/step vs 4G 12/58/157 µs at L20/40/60 = 9–117× cheaper** (43× at L40), identical to orig-4F (+1 softplus branch), analytic force + analytic diagonal tangent ⇒ GPU-friendly.
- **OUTCOME B ⇒ RECOMMENDATION: ADOPT calibrated 4F-L40 for LOAD/force-dominated production (gliding, contraction)** with documented uncertainty + periodic 4G spot-checks. Caveats: (1) search envelope ~30% tight ⇒ soften k_tr for recruitment-dominated studies; (2) retain a distinct L60 fixture (form does not transfer upward). No canonical setting changed; overlay default-off.
```
./scripts/run_lasertrap.sh -exp4i -out RUN_LOGS/twobody_4g_to_4f_calibration/csv   # full calibration (CPU, ~35 min)
./scripts/run_lasertrap.sh -exp4i -smoke                                            # quick smoke (L40 only)
```

### 2026-07-15 — EXPERIMENT 4H: blinded single-motor laser-tweezers validation of the 4G explicit-S2 model (PRODUCER; no verdict)

Default-off `-exp4h` / `-twobody-tweezers-blinded`, CPU-only. PRODUCER role: executed a preregistered blinded experiment + exported complete data for a separate analyst; **no scientific interpretation written**. Validated head/converter/lever/F8/gate/Lymn–Taylor chemistry+constants/RNG/filament mechanics/fixed-anchor reference/`BoA-v1ref` UNTOUCHED (new methods only; `g4*` fields read only by `s2*`/`h4*`). `BoA-v1ref` byte-clean. Report: `docs/TWOBODY_TWEEZERS_4H_PRODUCER.md`; export `TWEEZERS_4G_BLINDED/`; key `UNBLIND_KEY_DO_NOT_GIVE_ANALYST.json` (repo root, outside export).

- **Gate 0 (bending calibration) — RESOLVED: retain CURRENT_4G only (one blinded block).** The ~4–5× `kTrans` "excess" is a PROBE-DEFINITION artifact — the probe pins the tip's full 3D position (incl AXIAL), forbidding bending foreshortening, so a transverse displacement engages the fixed-contour STRETCH (free-tip axial ≡ EA/L, resolution-exact 70–420 pN/nm). The bending EI is CORRECT: the clamped-FREE (force-controlled, free-tip) endpoint stiffness CONVERGES to the continuum 3EI/L³ under l0 refinement (L=40: 0.71→0.84→0.91× at l0=10→5→2.5 nm). No material/EI correction justified. `gate0_bending_calibration.csv`.
- **Blinding:** the 4 fixtures (one rigid-support reference + three explicit-S2 of distinct free length) permuted to anonymous labels A/B/C/D by a withheld seed; mapping+seed only in the key (verified: NO label→identity string in any export file; the manifest states the design generically with identities WITHHELD).
- **Assay 1 (unloaded event-resolved stroke, trap 0.05):** ≥200 accepted events/label DONE (A/B/C/D = 200/200/200/200 bound). Every listed observable recorded per event incl nonproductive/censored (retained via `failClass`): bind/stroke/release/detach steps, dwell/Pi-release times, converter rotation, head/filament/pivot displacement + recoil, S2 contour/e2e/curvature/bending energy/axial strain+energy, F8 ext/force, substrate reaction peak+final, completion. `event_data.csv` (800 events), `force_displacement.csv`, 12 trajectories.
- **Assay 2 (local bound-state stiffness):** pre/post-stroke × loads {0,1,3} pN × perturbations {0.25,0.5,1.0} nm, central slopes + hysteresis + MATCHED-POSE fixed-anchor eval + component tangents (F8, converter, bind, S2 axial EA/L, trap). `local_stiffness.csv` (72), `component_tangents.csv` (24). The k_ext diagnostic is exported (S2 labels measure ≈0.99, matched-pose fixed ≈0.62) for the analyst — producer does NOT interpret.
- **Assay 3 (force clamp):** loads {0,0.5,1,2,3,4,5,−0.5,−1,−2} pN, ≥100 events/label/load DONE (40 cells × 100). Aggregates: strokeProb, displacement dist, latency, lifetime, completion, recoil, tension/compression fractions, bending, work, detachment-pathway counts. `force_clamp.csv`.
- **Assay 4 (trap sweep):** traps {0.02,0.05,0.10} pN/nm — unloaded stroke + post-stroke local stiffness. `trap_sweep.csv` (12).
- **Numerical controls — all pass/observational:** fixed-seed restart bit-identical; contour conserved (≤0.011 nm); beam force balance ≈0 (≤8e-13 pN); zero S2 force on actin (by construction); no nucleotide/binding material switch; polarity covariance; half-dt deterministic-stroke replication; free-tip segmentation convergence; compression-buckling probe (observational). `numerical_controls.csv`.
- **Export:** `checksums.sha256` over 21 files (all verify). Structural `NaN`/`inf` in the rigid-reference fixture's S2-specific columns (EA/L→∞; empty S2 strain) are documented absences, NOT failures. Completion: ALL preregistered runs completed; NO missing data; NO numerical failures.

### 2026-07-15 — EXPERIMENT 4G: MD-informed EXPLICIT fixed-contour S2 geometry — OUTCOME A (partial): decoupling emerges from geometry; slack-to-taut relocates to buckling

Default-off `-exp4g` / `-twobody-explicit-s2`, **CPU-only** (the two-body arc is CPU-only; disclosed). Tests whether 4F's search-mobile/load-bearing **decoupling** emerges from an **explicit fixed-contour S2 coiled coil** rather than 4F's two PRESCRIBED Cartesian springs. The free proximal S2 (L∈{10,20,40,60} nm) is a discretized **extensible-elastica beam** (M=L/10 segments, stiff per-segment stretch ks, finite per-joint bending kb), clamped at a supported emergence point (position + tangent — the support does NOT rotate), distal node = the validated motor pivot. **No active stroke, no actin interaction, no nucleotide dependence.** Coupled `(3M+2)`-DOF linearly-implicit solve {nodes, φ, ψ} with a **full numeric beam tangent** + the F8/converter/bind coupling. `g4On=false` ⇒ `stepS2` delegates to `stepC` **bit-identical (max|Δ|=0)**. **Validated head/converter/lever/F8/gate/Lymn–Taylor chemistry+constants/RNG/filament mechanics/`BoA-v1ref` UNTOUCHED** (new methods only; `g4*` fields read ONLY by `s2*`/`stepS2`/`s2SolveM`). Verified: only `TwoBodyConverterMotor.java` (+1 dispatch line) modified; `BoA-v1ref` byte-clean (0 changes). Deliverables: `docs/TWOBODY_MD_INFORMED_S2.md`, `RUN_LOGS/twobody_md_informed_s2/`, `scripts/twobody4g_analyze.py`, 5 viewer dirs `threejs_twobody4g_{search,capture,stroke,mat,compare4f}` (render the ACTUAL explicit S2 segments, coloured blue=slack/compressed→red=taut/tensioned).

- **§2 MD map (AMK 2008 + Brizendine 2021):** K_ax(60)≈70 pN/nm, k_lat(60)≈0.01 pN/nm; clamped-free cantilever scaling K_ax∝1/L, k_lat=3EI/L³. ⇒ material constants EA=4.2 nN, EI=7.2e-28 N·m² (**Lp≈175 nm**), per-seg ks=420 pN/nm, kb=7.2e-20 N·m. Not tuned to gliding velocity (starting constraints).
- **§1 references (Gate 1/2):** fixed anchor (6.91/0.645/0), 4E free tail (1.07/0.010/−6.74), 4F no-slack (6.92/0.641/0.07), 4F short-slack (6.74/0.600/0.15) all reproduced. `stepS2(g4On=false)` ≡ `stepC` bit-identical.
- **§3/§6/§10 geometry:** the beam **conserves contour** (≤0.01 nm under stroke + bending) and is **STRAIGHT at rest** — a stiff-stretch fixed-contour beam does **NOT hold a rest slack** (it straightens and repositions the pivot; the soft F8 cannot hold P against straightening). **Emergent anisotropy matches the MD scaling:** axial tension k = ks/M ∝1/L (420/210/105/70 pN/nm exact); soft-transverse bending k ≈∝1/L³ (8.84→1.21→0.17→**0.052** pN/nm, → the MD k_lat≈0.01 for L=60). Tension/compression **asymmetry via Euler buckling** at L=60 (kComp 0.68 ≪ kTens 70, 100× soft; crit=π²EI/L²). **The slack-to-taut is NOT a rest property — it relocates to compression buckling.** (Found+fixed a real bug: stretch-only-tangent + explicit bending froze the beam at a 360 pN non-equilibrium; the full numeric tangent — stretch AND bending implicit — gives the true equilibrium, contour exactly L, net force 0.)
- **§5/§8 SEARCH vs LOAD (decisive, single-motor):** **DECOUPLED for L≥40 nm** — stroke **105 %**, k_ext **154 %** (0.99; the stiff beam is effectively rigid along the load axis ⇒ k_ext near the F8-limited value, a flagged fixture offset ABOVE the rigid-anchor 0.645, not a series-compliance loss), pivot recoil **≤0.01 nm**, capture **576/864 nm²**, search rmsLat **10.9/16.1 nm**. **Short S2 (L≤20) = stiff link** (full transmission, small capture = the tweezers/strongly-supported boundary condition). L is the assay boundary-condition knob (tweezers↔gliding-search), motor core unchanged.
- **§9–13 dense mat (CPU, REDUCED scale — DISCLOSED: 2.5×0.6 µm @ 400/µm², N=600, 0.06 s ×4 ep, short S2; per-motor (3M+2) beam solve makes the full 4×1@1000 mat impractical on 1 CPU core; GPU is the follow-up, gated on parity):** explicit S2 recruits **3.3–4.3×** more chemically-bound motors AND they are **load-bearing** (100 % taut at L≤20; 69 % at L=40) — the opposite of the 4E trap (more binding AND more force). Continuity 0.33→0.80.
- **§14 controls all PASS:** emergent-tangent consistency (rel err 0), action–reaction (Σ internal + node-0 reaction ≈0; beam acts on P↔substrate, **0 force on actin**; filament sees F8 only), **NO binding-state/nucleotide stiffness switch** (beam ks/kb/l0/contour identical bound vs unbound — the §17.6 requirement), polarity/rotation covariance, fixed-seed bit-identical. **§15 dt:** stroke/k_ext/recoil/contour-drift dt-invariant over {5e-6,2.5e-6,1.25e-6}; fastest mode (stretch τ≈1.1e-8 s) handled implicitly.
- **CONTROLLING OUTCOME A (partial):** the decoupling **emerges from explicit geometry with no prescribed springs** (the MD 1/L stretch vs 1/L³ bending anisotropy), reproducing 4F Outcome A for exposed S2; it is NOT Outcome E (4F did not need an artificial force law). The one honest correction: 4F's *rest slack-to-taut* does not survive a stiff beam — it relocates to a compression-buckling asymmetry. **Recommendation: retain BOTH the explicit MD-informed S2 (gliding/exposed-tail model, L≥40) and the fixed anchor (tweezers/strongly-supported model) as assay-conditioned variants.** No canonical change; 4F stays a valid phenomenological fit. Follow-ups (not done): GPU full-mat production (after parity), a torsional S2 DOF, resolving the k_ext>fixed fixture offset.

### 2026-07-15 — EXPERIMENT 4F: supported two-region tail (search-mobile, load-bearing) — OUTCOME A: CLEAN DECOUPLING

Default-off `-exp4f` / `-twobody-supported-s2-tail`, CPU-only. The follow-up 4E flagged ("a state-dependent catch-stiffening tail"). 4E failed because its passive tail was **isotropic** — the softness that enlarged the capture volume (search) was the *same* softness that absorbed the power stroke (load). **4F fixes this with an ANISOTROPIC + NONLINEAR tail (still passive, still nucleotide-INDEPENDENT — no state switch).** The movable pivot P is held by TWO separate elements about rest P0=A: (1) a **SUPPORTED DISTAL TAIL** — a slack-to-taut AXIAL law along the load axis b̂ (soft within a slack δ, TAUT/stiff beyond; qL=(P−P0)·b̂ = the spec tension coordinate; tension/compression asymmetric; substrate floor); (2) a **FLEXIBLE PROXIMAL S2 HINGE** — a soft TRANSVERSE (econv,ê_up) search spring with smooth finite-extension (bounds inversion). 5-DOF linearly-implicit solve `(A_drag+K)Δq=F` reusing the validated 4E structure, with the anisotropic-nonlinear force + its analytic tangent in K. `supOn=false` ⇒ `stepSup` delegates to `stepC` **bit-identical**. **Validated head/converter/lever/F8/gate/Lymn–Taylor chemistry+constants/force-ordering/RNG/filament mechanics/`BoA-v1ref` UNTOUCHED** (new methods only; `sup*` fields read ONLY by `supForce`/`stepSup`/`supForceM`/`supSolveM`). Verified: only `TwoBodyConverterMotor.java` (+1 dispatch line) modified; 4E re-runs Gate-1 bit-identical + TRADE-OFF; BoA-v1ref byte-clean. Deliverables: `docs/TWOBODY_SUPPORTED_S2_TAIL.md`, `RUN_LOGS/twobody_supported_s2_tail/`, 6 viewer dirs `threejs_twobody4f_*`.

- **§1 references (Gate 1/2):** fixed anchor reproduced (stroke 6.91, k_ext 0.645, recoil 0); 4E free tail reproduced (stroke 1.07, k_ext 0.010, pivot give −6.74). `stepSup(supOn=false)` ≡ `stepC` **max|Δ|=0**.
- **§5 the ONE licensed stiffness refinement:** k_ext saturates at the fixed-anchor ceiling by **k_taut≈20 pN/nm** (74→99→(91) % over k_taut 10/20/40 — the 40 dip is paired-estimator noise). Since **search is transverse, k_taut does NOT affect search** — it only sets axial load transmission. Selected k_taut=20 (minimal sufficient). No motor/gate/kinetic constant touched; only δ varies across conditions.
- **§4/§8 SEARCH vs LOAD (decisive, single-motor):** no-slack (δ=0) / short-slack (δ=1.5): stroke **100 % / 98 %**, k_ext **99 % / 93 %**, pivot recoil **0.07 / 0.15 nm**, capture area **783 / 1110 nm²** (fixed = zero-width line). Mod/excess-slack (δ≥3.5): stroke ≤77 %, k_ext ≤11 %, recoil ≥1.8 nm — the stroke falls INSIDE the slack and is absorbed (the 4E regime, included by design as the failure edge). Sharp transition between δ=1.5 and 3.5.
- **§9–11 dense mat (4000 motors, 1000/µm², active-set UNION cull ⇒ ~450 solved/step of 4000 — CPU-tractable, NO GPU port needed, same method as 4D-ii):** recruitment fold (avgBound vs fixed 0.62): noslack **8.10×**, short **8.29×**, mod 7.82×, excess 7.32×. **The §11 distinction (LOAD-BEARING = pivot taut, kAxTan≥2 pN/nm):** noslack load-bearing **5.02 (100 % of bound = 8.1× more than fixed)**, short 3.23 (63 %), mod 1.73 (36 %), **excess 0.80 (18 %, ≈ the fixed 0.62 — recruits but INERT, the 4E trap)**. So the low-slack tail delivers a genuine multi-fold increase in LOAD-BEARING (not merely chemically-bound) attachments; excess-slack does not.
- **§14 controls:** Jacobian≡force (central-diff), action–reaction (0 tail force on actin — the tail acts only on P↔substrate; the filament sees the motor ONLY through F8), hinge-locked (kSoftTr×1000 → capture 1110→35 nm² ⇒ search freedom drives capture), fixed-seed bit-identical, polarity/rotation covariant. **§15 dt:** stroke/k_ext/recoil dt-invariant over {5e-6,2.5e-6,1.25e-6}. No penetration/inversion/blowup.
- **ALL 12 GATES PASS. CONTROLLING OUTCOME A** — the first tail that recruits AND preserves the single-molecule mechanics; a clean recruitment-map shift satisfying the CURRENT_STATE §5 requirement 4E violated. **Recommendation: provisionally ADOPT the supported tail (short slack δ=1.5 nm, k_taut=20 pN/nm) as a non-canonical candidate; test with a longer filament.** No canonical change (a non-canonical prototype). GPU decision (jba): CPU active-set cull chosen — a full GPU port of the two-body pipeline is unnecessary for tractability.

### 2026-07-14 — EXPERIMENT 4E: passive myosin-TAIL geometry as a recruitment mechanism — TRADE-OFF (recruitment gain and stroke loss share the same compliance)

Default-off `-exp4e` / `-twobody-tail-recruitment`, CPU-only. Replaces the two-body motor's FIXED calibration anchor with a passive compliant **tail** (rod S→movable pivot P, rest length lTail, stretch k_tail, bend κ_tail toward
tHat=ê_up); P becomes a 3-DOF movable point, advanced by a 5-DOF linearly-implicit solve `(A_drag+K)Δq=F` that generalises the validated 2×2 φ,ψ solve (F8 Gauss–Newton stiffness now couples the pivot). Rest `P=S+lTail·tHat=A`
⇒ pre-stroke pose unchanged; reduces to the fixed anchor as lTail→0 or stiffness→∞. **Validated motor/converter/lever/F8/gate/Lymn–Taylor chemistry+constants/force-ordering/RNG/`BoA-v1ref` UNTOUCHED** (new methods only; tail
fields read ONLY by stepTail/stepGlideTail, never stepC/stepGlide2D). Verified: only `TwoBodyConverterMotor.java` (+1 dispatch line) modified; the 5 canonical files + BoA-v1ref byte-clean; 3C reproduces its k_ext limits, 4A
0-forbidden. Deliverables: `docs/TWOBODY_TAIL_RECRUITMENT.md`, `scripts/twobody4e_analyze.py`, `RUN_LOGS/twobody_tail_recruitment/`.
- **Q1 audit (Gate 1):** the current effective surface-anchor→lever-pivot distance = **0.00 nm** — the anchor IS the pivot (C=A+L_B·ûB, φ pivots about fixed A ⇒ NO tail). Fixed baseline reproduced exactly: k_ext=0.645, stroke
  6.91, transverse 0.09, preload 0. `stepTail(tailOn=false)` delegates to stepC **bit-identical** (max|Δ|=0).
- **Q2 capture volume (Gate 4):** grows STRONGLY with lTail — free tail (κ=4, ±60° swing) footprint 0→148→445→1491→5381→**20363 nm²** and lateral reach 0→…→**138.6 nm** for lTail 0/5/10/20/40/80. The fixed-anchor motor has
  **zero transverse capture width** (a line); the tail gives the 2D footprint. **Growth REQUIRES the free swing** (stiff-bend κ=400 → ±8° cone → ~20× slower growth).
- **Q3 recruitment (Gate 5, moving-filament mat, free tail):** avgBound fixed **0.230 → 10nm 1.320 (5.74×)** → 20nm 0.965 → 40nm 0.641 → 80nm 0.454; continuity 0.20→0.69; binds/mot/s 1.56→9.30. **YES the tail increases binding
  rate, mean bound, continuity — peaks at a SHORT ~10nm tail**, declines for longer (pivot wanders off the actin plane).
- **Q4 series compliance:** dominated by the **BENDING κ_tail, not stretch k_tail** (the stroke is AXIAL, the tail TRANSVERSE ⇒ k_tail nearly irrelevant). k_ext(lTail, k=2): κ=400 → 0.645/0.62/0.56/0.39/0.18/0.06; free κ=4 →
  0.645/0.13/0.038/0.010/0.003/0.0006. **Even a stiff-bend 20nm tail drops k_ext 39%**; a free 20nm tail 98%.
- **Q5 stroke absorption:** YES, progressively. Delivered stroke (free κ=4): 6.9→4.75→2.75→**1.07**→0.32→0.08 nm; the pivot **recoils barbed-ward** instead of the actin advancing (at 20nm free: pivot give −6.74nm, actin +1.07).
  A stiff-bend tail resists (6.36nm at 20nm) but an 80nm stiff tail still loses 58%.
- **Q6 pathology (Gate 6):** no blow-up/buckling/tangling; the F8 gate keeps captures stereospecific. Tilt moderate at the optimum (10nm avgTilt 42°, occasional maxTilt 138° = brief short-tail near-inversion, flagged). **The real
  "pathology" is the OPPOSITE of strain: mechanically WEAK captures** (k_ext ~0.01, stroke ~1nm), not over-strained ones.
- **Q7 verdict (Gate 7 CHECK — the crux):** **NO single passive tail both recruits well AND preserves the mechanics.** The recruitment gain (free swing → capture volume) and the stroke loss (same compliance absorbs the axial
  load) share ONE cause. Stiff-bend preserves the stroke but ±8° swing ⇒ little recruitment; free recruits 5.7× but collapses k_ext to 0.010 / stroke to 1.1nm. **Recruitment operating point (20nm free): stroke 1.07nm, k_ext
  0.010, 98% series compliance ⇒ mechanics COLLAPSED.** A recruited motor is bound-but-inert ⇒ more-bound ≠ more-force.
- **CONTROLLING OUTCOME: TRADE-OFF** (not a free win). Connects to CURRENT_STATE §5: the tail IS a leftward recruitment-map shift but VIOLATES the §5 requirement that a clean recruitment-map change preserve intrinsic single-motor
  mechanics. **What would work (not built): a state-dependent catch-STIFFENING tail** (floppy for search, rigid on load) — a passive linear element cannot decouple the search and load modes. **No canonical change; fixed anchor
  stays the reference.**

### 2026-07-14 — EXPERIMENT 4D-ii: full-length active-motor coverage on the dense 2D mat (audit + correction) — the defect was VIEWER-ONLY; simulation cull was already contour-complete

Audit + correction of 4D's candidate-selection + rendering geometry (the 4D viewer showed articulation concentrated at the filament MIDPOINT). Default-off `-exp4d2` / `-twobody-fullcoverage-mat`, CPU-only; **motor, chemistry,
gate, filament, density, constants, `BoA-v1ref` untouched**; the new sim cull + viewer fix are GATED so `-exp4d` is byte-identical (cullMode=0 default, fullViewer=false). Deliverables: `docs/TWOBODY_FULLCOVERAGE_MAT.md`,
`scripts/twobody4d2_analyze.py`, `RUN_LOGS/twobody_fullcoverage_mat/`, viewer `~/Code/SoftBox/threejs_twobody4d2_{fullcoverage,bruteforce_check}`.
- **PHASE 1 DIAGNOSIS (5-set audit):** chemistry-active = ALL N (no cull); binding candidates = 4D whole-chain AABB(all 12 seg)+30nm on the ANCHOR = **contour-complete (NOT the bug)**; force-active = bound only (unchanged);
  **viewer articulated = `|anchor.x − filament MIDPOINT| < 0.6µm` window ⇒ CLIPPED the ends of the 2.1µm filament = THE BUG (case 2 viewer-only + case 5 center-coordinate)**; viewer posts = the rest.
- **PHASE 2 FIX:** grid-accelerated per-segment UNION cull — a motor is a candidate iff its SITE is within queryR of ANY live segment (shortest clamped distance, not filament-center); used for both the sim cull (cullMode=1)
  and the viewer articulation (no midpoint window). **queryR=30nm DERIVED** (F8 swing ~5 + head ~4.5 + gate 6.5 + margin 5 ≈ 21, ×safety).
- **PHASE 3 brute-force validation (4000-motor brute):** **(a) COMPLETENESS — 50 new bindings, MISSED by the union cull = 0** ⇒ the cull omits NO brute-accepted binding (the required result). (b) avgBound brute 0.796 vs union
  0.992 (Δ flips sign across runs ⇒ chaotic sampling noise, not a freezing bias — the active-set freezes far-motor search ⇒ chaotic divergence, statistical-equivalence standard not bit-identity). **PASS.**
- **PHASE 4 coverage:** **12/12 segments covered (100%)**, longest uncovered run 0, per-segment 9–18 candidates; end/mid ratio 0.61 (a GEOMETRIC effect — mid-segments flanked both sides — NOT a culling artifact; both ends
  well-covered). Bound motors spread across the contour.
- **PHASE 5 dense viewer:** mat enlarged to 4×1µm (4000 motors, density UNCHANGED) so the contour stays over the lawn (filament x∈[−1.19,0.86] vs mat [−2,2]). **Viewer fix VERIFIED:** articulated motors span x∈[−1.34,0.99]
  (full contour), **253 articulated near the ends** (|x−mid|>0.7µm) where the 4D midpoint rule gave 0; all 4000 rendered (~620 articulated + ~3380 posts).
- **CLASSIFICATION: correction complete; defect was RENDERING-ONLY.** 4D's simulation candidate set was already contour-complete; the midpoint concentration was a viewer articulation bug. The 4D scientific conclusions STAND;
  this hardens the cull (per-segment union, brute-validated) + fixes the viewer. **NEXT: the post-4D continuity/velocity study on the (now audited) 2D-mat assay, or a longer 8–15µm filament.**

### 2026-07-14 — EXPERIMENT 4D: flexible filament gliding over a dense 2D myosin mat — FEASIBILITY DEMONSTRATED (glides pointed-first, multi-section engagement, contour conserved); flexible ≈ rigid at canonical actin stiffness

Corrects 4C's assay limitations (rigid rod in a narrow doubly-confined strip) WITHOUT changing the motor: a genuinely FLEXIBLE chain filament (nSeg=12, ~2.11 µm, canonical `ChainBendingForceSystem` F3+F4 bending
+ full Brownian, free ends, z-ONLY surface ⇒ x/y/rotation/bending FREE) glides over a TRUE 2D motor lawn (ρ=1000/µm² over 3.0×1.0 µm ⇒ N=3000 motors). Non-canonical, default-off `-exp4d` / `-twobody-flexible-mat-gliding`,
CPU-only; **two-body motor, canonical chemistry+constants, chain law, RNG, force order, `BoA-v1ref` untouched** (new `Glide2D` path; 4B/4C `Multi`/`stepMulti` unchanged). Deliverables: `docs/TWOBODY_FLEXIBLE_MAT_GLIDING.md`,
`scripts/twobody4d_analyze.py`, `RUN_LOGS/twobody_flexible_mat_gliding/`, viewer `~/Code/SoftBox/threejs_twobody4d_{flexible_active,flexible_control,flexible_nobind,rigid_active}` (chain + 3000-motor lawn).
- **APPARATUS (canonical chain reused):** `FilamentStore(12)` straight along +x (MONOMER_CT=64, segLen 0.176 µm), `ChainBendingForceSystem` F3 link + F4 bending (canonical actin Lp≈17 µm — NOT softened), rot-Brownian on end
  segments only; z-only confinement `−kz·z` per segment (kz=2 pN/nm); step zero→brownian→chain→gather→z→integrate ONCE→derive (verified). Motors bind the NEAREST segment (local tangent for the material coord + signed force
  via `bondForces`); the CSR gather (keyed by boundSeg, general over nSeg) sums reactions per segment. **Active-set spatial cull:** only ~377 of 3000 motors (within the filament bbox+30 nm) run mechanics/step. Rigid-rod mode
  (1 segment) = condition D. **Verified: contour conserved (2.106=2.106), forces to correct segment/material coord, once/step integration, no stale rigid axis** (orientation gate uses the lab frame — a flagged approximation
  valid for the bend<1° semiflexible filament).
- **RESULT — FEASIBILITY DEMONSTRATED (primary dt=2.5e-6, 6 ep × 0.5 s):** (1) recruited to the dense 2D mat — avgBound 0.38, continuity 0.30 (**better than the 4C strip 0.15** at the same density; 2D lawn ⇒ more reachable
  motors); (2) **pointed-first polarity** (per-stroke disp·p̂ +1.08 nm flex / +1.88 nm rigid); (3) **flexibility does NOT increase availability — flexible ≈ rigid** (avgBound 0.379 vs 0.388) because canonical actin at 2 µm is
  nearly rigid (bend 0.25°, e2e/contour 0.986) — honest physics, not softened; (4) bends gently + explores laterally (yExplore 112 nm) WITHOUT leaving the surface (RMS z 1.1 nm), contour conserved, no buckling; (5) **multi-section
  engagement — up to 7 distinct segments bound to independent motors at once** (mean 1.24); (6) no snagging/buckling/conflict (joint gaps bounded ~9.6 nm); (7) flexible vs rigid: same recruitment + both pointed-first, but
  **flexible per-stroke COM displacement is SMALLER** (compliance absorbs the stroke into local bending — the filament-level compliance story). **Both controls clean:** B(no-motor) avgBound 0/vel~0, C(binding-disabled, full
  mat) avgBound 0/vel~0 — the anchor lawn creates NO directed motion. 0 forbidden transitions. dt-stable (polarity/bending/stroke); engagement carries the ~15% coarse-dt bias.
- **CLASSIFICATION: feasibility positive; 4C assay limitations corrected.** The 2D-mat + flexible-chain assay is realistic and works; the flexibility effect on recruitment is small at canonical stiffness (2 µm actin is stiff).
  **NEXT (recommended, NOT started): a continuity/velocity study on the validated 2D-mat assay** (velocity vs reachable-count/duty, toward a regression vs the fine-dt canonical curve) — OR, to isolate a real flexibility effect,
  **a longer filament (8–15 µm, Lp/contour<1)**. Keep dt ≤ 5e-6.

### 2026-07-14 — EXPERIMENT 4C: first low-density gliding of the cycling two-body motor — FEASIBILITY DEMONSTRATED (recruitment + pointed-first polarity + completing strokes); motion recruitment-limited

The first FREE-filament gliding tests: a free Brownian filament glides over a sparse bed of INDEPENDENT cycling 4A two-body motors. Reuses the 4B `Multi` machinery VERBATIM (per-motor 4A cycle, canonical CSR gather, ONE
filament integrate/step) + two changes: (i) filament Brownian ON (`filBrown`); (ii) the restoring end-traps become a SURFACE — axial (glide) FREE (kAx=0), transverse y,z softly confined to the motor plane (kTr=2 pN/nm).
Non-canonical, default-off `-exp4c` / `-twobody-lowdensity-gliding`, CPU-only; **4A motor, canonical kinetics, RNG, force order, and `BoA-v1ref` untouched** (5 canonical files byte-clean; 4B byte-identical — the `filBrown`
line is guarded off). Deliverables: `docs/TWOBODY_LOWDENSITY_GLIDING.md`, `scripts/twobody4c_analyze.py`, `RUN_LOGS/twobody_lowdensity_gliding/`, viewer frames `~/Code/SoftBox/threejs_twobody4c_{control,low,medium,highlow}`.
- **APPARATUS (canonical gliding, reused):** density motors/µm² → `nMot=density·bedX(2.6µm)·bedY(±8nm strip)` (250/500/1000 ⇒ 10/21/42); random anchors placed by the 3E construction to reach `(ax,ay,0)`; a motor binds
  only when the gliding filament passes within the 3E gate reach (recruitment EMERGES from the gate). Filament = 1µm rigid rod, Brownian ON; surface = `applyTraps3D` kAx=0/kTr=2 (transverse pin, axial glide); force via the
  CSR gather; **filament integrated ONCE/step after summation** (verified). Velocity = net-displacement rate + the Brownian-FREE per-stroke directed displacement·p̂ (polarity); OLS-slope kept as a noise-dominated diagnostic.
- **BUG FOUND+FIXED:** `stepMulti` never advanced the FILAMENT RNG step index ⇒ with Brownian ON, `BrownianForceSystem` redrew the SAME force every step ⇒ a spurious constant ~165 µm/s DC drift (visible even in the
  0-motor control). Fixed by advancing `fil.counts` each step (guarded by `filBrown` ⇒ 4B byte-identical). Post-fix control = proper bounded Brownian (COM ±60 nm/0.2s, D_par≈0.031 µm²/s, net vel ~0).
- **RESULT — FEASIBILITY DEMONSTRATED (primary dt=2.5e-6, 8 ep × 0.20 s):** (1) recruitment YES, scales with density — reachable 7/16/32, avgBound 0.047/0.093/0.169; (2) **polarity POINTED-FIRST at every density** —
  per-stroke directed disp·p̂ **+3.19/+3.34/+3.32 nm** (Brownian-free, dt-stable); (3) motion INTERMITTENT — continuity (frac ≥1 bound) 0.05/0.08/0.15, longest gap 200→160→64 ms (shrinks with density); (4) continuity needs
  MORE than 1000/µm² (limited by the low single-motor duty ~0.013, search+recovery-dominated — the 4B carry-forward); (5) **stroke completion ≈1.0** (0.99/0.99/1.01) — strokes complete before ADP release/detachment;
  (6) drift/rotation minimal — RMS z/y ~1 nm, tilt 0.1° (well confined, no contact loss); (7) polarity+completion dt-stable, avgBound carries the ~17% coarse-dt variation. **0 forbidden transitions; control ≈0.** Net velocity
  Brownian-noise-limited at low duty (the per-stroke disp is the robust signal). Phase-1 `-3js` frames (control/low/medium/highlow, ~1500 frames each) written for direct inspection.
- **CLASSIFICATION: feasibility positive, motion recruitment-limited** (not force-limited) — the cycling two-body motor glides a free filament in the correct polarity with completing strokes + clean mechanics; sustained
  continuous gliding is not reached at ≤1000/µm² (low duty). **NEXT (recommended, NOT started): a focused recruitment/continuity study** (continuity vs reachable-count / duty at higher density or wider reach) to find where
  coverage becomes continuous, THEN a proper gliding velocity vs the fine-dt canonical curve as a REGRESSION. Keep dt ≤ 5e-6.

### 2026-07-14 — EXPERIMENT 4B: sparse multi-motor (N=1..4) composition of the 4A cycle — OUTCOME A (independent composition); proceed to low-density gliding

N∈{1,2,3,4} INDEPENDENT 4A two-body motors share ONE trap-held filament, interacting ONLY through the shared filament mechanics + the canonical CSR force gather — NO motor–motor coupling, NO shared chemistry, NO
cooperative rates, NO tuning. Non-canonical, default-off `-exp4b` / `-twobody-sparse-multimotor`; **4A motor, canonical kinetics, joint arithmetic, force order, RNG, and `BoA-v1ref` untouched** (5 canonical files
byte-clean). Deliverables: `docs/TWOBODY_SPARSE_MULTIMOTOR.md`, `scripts/twobody4b_analyze.py`, `RUN_LOGS/twobody_sparse_multimotor/`.
- **ARCHITECTURE (reused, audited):** ONE `FilamentStore(1)` (trap-held, Brownian off, moves under motor load) integrated ONCE/step; ONE `MotorStore(N)` (motor m head at slot 3m+2; per-motor boundSeg/bindArc/
  nucleotideState/forceDotFil/state); `cycleLymnTaylor` per-motor (wang-hash keyed on m ⇒ independent chemistry streams); `CrossBridgeSystem.bondForces` writes bondData[m·STRIDE], the CSR gather (histogram/scan/
  scatter/segGather keyed by boundSeg) sums every bound motor's seg reaction into fil.forceSum — order-independent, no atomics, no double-count. Per-motor generalized coords (φ_m,ψ_m): bound → the 4A F8 implicit
  solve, free → the 3E Brownian search (independent salt per motor). Binding = the 3E gate, ADP·Pi-only, from the PRE-integration filament state; latches live pose + material coord (no teleport). Detach zeroes only
  motor m's bondData row.
- **GEOMETRY (fixed pre-production):** filament half 0.5 µm; anchors below the filament at axial spacing 0.15 µm (transverse 0), interior sites; none begin bound; barbedDir NOT in the stroke sign. Traps 0.05 pN/nm
  dual-end (3F/4A value, restoring ⇒ NO net translocation — a trapped assay, not gliding). dt primary 5e-6, subset 2.5e-6.
- **RESULT — OUTCOME A (independent composition), 24 ep × 300k steps/N:** per-motor bound fraction **FLAT with N** (0.0126/0.0123/0.0127/0.0123); mean bound ∝ N; ADP·Pi dwell 0.10 ms, ATP-free 10.0 ms, **ADP dwell
  ~1.25 ms FLAT with N (NO catch-locking)**; native stochastic stroke **~2.9 nm flat** (vs clean-capture 6.9 nm; smaller/broader as 4A); **productive 72% flat**, near-zero/backward 5–6% flat; **0 forbidden, 0 stalls,
  0 numerical instability** at every N. Occupancy near-independent-binomial with a SMALL filament-mediated positive co-binding correlation (P(≥2) ~2× binomial but tiny absolute, ≤0.0017) — mechanical, NOT biochemical
  cooperativity. mean |motor force| 0.65 pN flat; longest co-bound 3.1 ms (transient).
- **CONTROLS (all pass):** N=1 reproduces 4A; **force balance Σ_i F_{i,∥} = gathered filament force to ≤8.7e-7 pN** (order-independent, no double-count); **inactive-neighbor: N=4 with only motor0 active gives the
  active motor's bound frac 0.0125 vs N=1 0.0126, Δ=0.0001** (idle motors don't perturb chemistry/force); **fixed-seed bit-identical**; index/anchor-permutation ensemble-invariant; catch-modulated ADP release tracks
  the UNCHANGED canonical onADP·⟨g(F)⟩ at all N (dominant +0.25 pN bin matches to a few %). Polarity: net disp trap-nulled ⇒ read from the pointedward productive fraction + inherited 3C/3D covariance.
- **dt-convergence (N=2, 5e-6 vs 2.5e-6):** composition/independence dt-STABLE; ADP dwell 1.26→1.05 ms (bound frac 0.0137→0.0161) keeps the inherited 4A ~17% coarse-dt release-dwell bias (converging); native stroke
  dt-stable (2.84→2.90 nm). Both dt give Outcome A. **All 11 pass criteria met.**
- **NEXT (recommended, NOT started): low-density single-filament GLIDING** — free the filament (surface/bed, small carpet), measure velocity vs the fine-dt canonical curve as a REGRESSION (not a fit). Carry-forward:
  native stroke 2.9 nm < clean 6.9 nm; low duty ~0.013 is search+recovery-dominated (gliding will be recruitment-limited); use dt ≤ 5e-6.

### 2026-07-14 — EXPERIMENT 4A: canonical nucleotide cycle ported onto the two-body motor — FULL PASS (single-molecule cycle closed; 3E/3F mechanics preserved)

Closed the minimal stochastic biochemical cycle of the validated 3C–3F two-body motor by **REUSING the canonical `NucleotideCycleSystem.cycleLymnTaylor` kernel VERBATIM** over the two-body motor's own
1-motor `MotorStore` — NO second chemistry framework, NO new rate constants, NO renamed states. The two-body mechanics (`stepC` bound / `stepU` search / the 3E gate) are the thin mechanical ADAPTER.
Non-canonical, default-off `-exp4a` / `-twobody-cycle`; **canonical motor, production defaults, joint arithmetic, force order, RNG, and `BoA-v1ref` untouched** (`NucleotideCycleSystem`/`MotorStore`/
`CrossBridgeSystem`/`GlidingHarness`/`CanonicalMotorHarness` byte-clean). Deliverables: `docs/TWOBODY_BIOCHEMICAL_CYCLE.md`, `scripts/twobody4a_analyze.py`, `RUN_LOGS/twobody_biochemical_cycle/`.
- **PHASE 1 (source map, traced not commented):** the ratified `-lymntaylor` cycle — states NONE/ATP/ADPPi/ADP (`MotorStore:145`); rates `setNucParams` (atpOn 2e4, onATP/offATP 100, onPi 1e4/offPi 0,
  onADP/offADP 1e3); ADP→NONE = onADP·g(F), g=αCatch·e^(−F·xCatch/kT)+αSlip·e^(+F·xSlip/kT) (`setKinParams`, unchanged); **bind ONLY in ADP·Pi** (welded `ADPPI_BIND`); **cocking isCocked()=!isADPPi**;
  **F = forceDotFil = Dot(F8_head, seg.uVec)** (`CrossBridgeSystem:204`, bondData[12]) — the SAME quantity+sign the canonical bond kernel writes; **one-step-stale** force; ATP-binding IS detachment (NONE→ATP).
- **ADAPTER:** θ_s (converter target) switched by state via `isCocked` — ADP·Pi = −30° (uncocked), else +30° (cocked) ⇒ the Pi-release ADP·Pi→ADP swing IS the validated +60°/6.9 nm 3D/3F stroke (emergent,
  no direct position write). forceDotFil copied from `cm.bondData[12]` each step; binding = the 3E stereospecific gate gated on `state==ADPPi`; detach drops the bond (`bondForces:100` `if(s<0) continue`),
  φ/ψ/A preserved (no teleport); recovery = off-fil ATP→ADPPi (re-primes/uncocks the lever) → waits primed in ADP·Pi → Brownian rebind. Order matches canonical gliding **bind→cycle→bond/integrate**.
- **PHASE 3 (single-motor, all PASS):** (A) 6 cycles, **0 forbidden transitions**, actin walks pointed-ward ~5 nm/cycle. (B) 40 ep × 400k steps: **915 full cycles, 0 stalls, 0 NaN, 0 forbidden**; dwells
  NONE-bound 0.050 (canon 0.050), ADP·Pi-bound 0.097 (0.100), ATP-free 9.66 (10.0), ADP-bound 4.40 ms (load-modulated). (C) **force-clamp catch-slip**: +barbed/opposing → forceDotFil>0 → **catch (slower)**,
  assisting → forceDotFil<0 → **slip (faster)** — sign ESTABLISHED from geometry+force flow; censoring-aware measRate tracks canonical onADP·⟨g(F)⟩ (excess over g(⟨F⟩) = Jensen thermal-fluctuation term,
  NOT re-tuning; xCatch untouched). (D) ATP detach n=200: latency 0.053 ms (canon 0.050), via NONE→ATP 200/200, bond removed ONCE 200/200, no pos-jump 200/200, **zero residual F8** 200/200. (E) recovery/
  rebind n=40: recovered→ADP·Pi 40/40, rebound 40/40, 2nd stroke pointed-first 39/40 (multiple cycles, no manual reset).
- **PHASE 4 (mechanics protected, cycle ON, n=118):** capture 0.983, cap preload 1.49→relaxed 0.10 pN, **stroke 6.90±0.04 nm**, transverse 0.10 nm, **k_ext 0.624 pN/nm**, force·b̂ −0.664 pN, pointed 118/118
  — every 3E/3F observable AT baseline. **No mechanical regression** (adapter reuses `stepC`/the 3E gate; the chemistry only KEYS the same θ_s switch 3F applied manually).
- **PHASE 5:** max per-step transition prob 0.40/0.20/0.10/0.05 (<1) at dt {2e-5..2.5e-6}; ADP dwell converges dt→0 (rate 721/731 /s at the two finest, ~1% apart; production 1e-5 ~28% high — the known
  coarse-dt bias); **fixed-seed bit-identical, different seed differs**; executed order reported; 3C/3E/3F byte-identical. **Classification: FULL PASS** (all 10 pass criteria).
- **NEXT (recommended, NOT started):** begin **sparse multi-motor cycling** (2–4 independent cycling two-body motors, one filament, no gliding-density sweep) — the catch-slip law is already validated here, so
  a deeper catch-slip run is redundant and there is no integration defect to repair. Flags (not defects): bound-ADP dwell is load-modulated (~4.4 ms resting-strain catch); τ-avg left at ratified default
  (instantaneous); force-cap detachment diagnostic kept OFF.

### 2026-07-14 — EXPERIMENT 3G-B: REALISTIC-ONLY tweezers HOLDOUT — step+polarity survive twin-free; stiffness becomes weakly identifiable (twin-free pipeline hides a real systematic)

The 3G-A-recommended follow-up: a small SEALED **realistic-only** optical-tweezers holdout (NO ideal twins) with a BETTER perturbation protocol, analysed once by a FROZEN strategy, to test whether
the developed analysis recovers step / pre&post stiffness / polarity without the noise-free twins that assisted 3G-A. Non-canonical, default-off `-exp3gb`; canonical + `BoA-v1ref` untouched.
Deliverables: `docs/TWOBODY_BLIND_TWEEZERS_HOLDOUT.md`; frozen pipeline `scripts/frozen_tweezers_holdout_analysis/` (+SPEC+hashes); sealed pkg + truth + scorecard + census `RUN_LOGS/twobody_blind_tweezers_holdout/`.
- **FROZE THE ANALYSIS FIRST** (before generating): copied the 3G-A analyst pipeline verbatim, removed ONLY the ideal-twin code paths (every threshold/filter/exclusion/estimator preserved),
  wrote `FROZEN_ANALYSIS_SPEC.md`, hashed. Validated it reproduces 3G-A's realistic numbers. The pre/post-difference verdict uses the analyst's OWN ideal-free criterion (0.15 pN/nm inversion resolution).
- **HOLDOUT** (`-exp3gb`, fresh seeds, restart bit-identical): 75 stroke + 18 bound-no-release + 27 controls + 3 calibration; k_ax 0.02/0.05/0.10; 5 preloads {−1,−0.5,0,+0.5,+1}. Improved perturbation =
  common-mode trap **steps HELD ≥5.5·τ_det** (23/9.2/4.6 ms), **force-matched** amplitudes, 2 amps + sign. Realistic traces ONLY; truth computed off-trace (never saved as a readable trace).
- **DESIGN FINDING (fixed):** held steps ADJACENT to the stroke make the frozen stroke-detector (tuned on 3G-A short spikes) latch onto the held-step relaxation TAILS → step failed (−3.0 vs 7.85).
  Root-caused (detector fired at 45ms, true stroke 153ms), redesigned so the stroke sits in a LONG QUIET stable dwell with perturbations POST-stroke only, separated → level-A detection 25/25.
- **BLIND run by a SEPARATE CC session** (no source/truth access), verified after the fact by a transcript **CENSUS** (`private_truth/census_blind_session.py`) = CLEAN (touched only the handoff dir);
  frozen pipeline re-verified UNMODIFIED (12/12 hashes), 142 sealed files intact. [jba correctly required a real separate session + post-hoc census rather than a same-machine sub-agent.]
- **SCORECARD vs external k_xb truth (NOT the 1.0 F8 spring):** step **7.00 [6.53,7.44]** vs 7.85 (−11%, CI narrowly misses; robust ±0.04nm to cal, 0.6nm over 14× k_post); **k_pre 0.577 [0.221,0.934]**
  (−10%, CI covers); **k_post 0.651 [0.595,0.708]** (+4%, CI covers); **pointed-first** ✓ (55/59); pre/post diff correctly **NOT identifiable**. Detection 58/75 (level A/B 81%, C 29%); 5/21 control false-attach, 0 false strokes.
- **THE REAL FINDINGS (blind analyst, running the frozen method unchanged then diagnosing read-only):** (1) held steps break the attach detector's mask→interpolate → **5 false attachments** (f_att=0.30
  zero-FP point doesn't transfer from short pulses); (2) the E0 systematic-floor stage **silently NaNs 11/21 controls** (measured detached chunk var 15.5 > model ceiling 11.8 at level A — the realistic
  variance model under-predicts by ~30%, drift residual), so the k_post floor is unmeasured AT level A and is −0.14 where checkable (level C); (3) force-matched held steps are **sub-thermal** (disp
  1.5/0.6/0.3nm vs sd 11/6.5/4.4) → the improved perturbation did NOT rescue stiffness (E2 dead), stiffness rests on variance+mean-shift.
- **IDEAL-TWIN VERDICT:** step + polarity (DC observables) survive twin-free unchanged; the **stiffness loses its forward-model validation** — the 30% variance-model gap would be immediately visible
  as a twin-vs-realistic mismatch but surfaces twin-free only as a silent NaN + an under-estimated systematic floor. "A twin-free package leaves the DC observables intact and quietly guts the
  variance-based ones." Point estimates landed within CI by E1/E3 robustness, NOT because the twin-free pipeline could prove them.
- **DECISION:** main 3G-A conclusions (step ≈7nm, stiffness ≈0.6, pointed-first, tiny pre/post diff unresolved) SURVIVE without twins, but post-stroke stiffness is weakly identifiable with an
  unmeasured systematic. Two named, fixable frozen-pipeline failures (held-step/attach mask; E0 variance-ceiling NaN) ⇒ a co-designed **newly-blinded future test** (drift-aware variance model +
  held-step-aware detector). No canonical/model change. Full-scale digital twin still waits on the closed stochastic biochemical cycle (per 3G-A synthesis).

### 2026-07-14 — FINE-dt FREE-GLIDING DENSITY SWEEP: canonical curve is CLASS-B bounded-approach, already biological at ρ≈2000; fine dt's real effect is de-biasing production bistability (NOT the mean)

Directly measured the canonical free-gliding velFitX–density curve at production dt=1e-5 vs fine dt=5e-6 (matched 0.6 s, matbox-50, coltol-8), to answer whether the fine-dt curve
plateaus — **without** inferring from the V₀/1.4 closure. Ran on a **pristine build of commit f537972** (the FINE-dt V₀ reference) in an isolated worktree so jba's uncommitted
J2/orthogonalizeY WIP stayed untouched and the numerics matched the clamp reference. GPU primary + deterministic CPU basin arbiter; **51 runs, 0 NaN**. Deliverables:
`FINE_DT_FREE_GLIDE_PLAN.md`, `FINE_DT_FREE_GLIDE_RESULTS.md`, `FINE_DT_FREE_GLIDE_figure.png`, `scripts/finedt_{benchmark,free_glide_sweep,conv_extension}.sh` + `scripts/finedt_analyze.py`,
raw `RUN_LOGS/finedt_cells/`. Canonical stack UNMODIFIED; no parameter changed; `BoA-v1ref` byte-clean.
- **THE MEAN BARELY MOVES:** |ΔV(5e-6−1e-5)| < 3% across d1000–6000; significant −12% only at d500. **The −19% clamp-V₀ shift does NOT transfer to free glide** (measured, not assumed) —
  because two individually-non-converged channels **compensate**: fine dt raises occupancy (avgB) but lowers per-bound efficiency (velPerBound), net ≈0 on velFitX. Confirmed low AND high density.
- **THE REAL FINE-dt EFFECT = de-biasing production BISTABILITY at high ρ:** at d8000 production seeds scatter 9.03/10.90/**13.84** (spread 4.81; s0=13.84 has fullMat=YES ⇒ a genuine
  fast basin, not coverage), fine dt collapses to 8.99/8.99/9.13 (spread **0.15**, ~32× variance cut). The one clean-coverage production seed (9.03) = the fine-dt value. **CPU arbiter refutes
  the GPU fast basin** (CPU d8000@1e-5 s0 = 9.93 vs GPU 13.84) ⇒ 13.84 is a GPU last-bit basin-tip artifact (the CLAUDE.md hazard); production d8000 is un-fittable (MM V∞ CI [13.6, **223**]),
  drop it → V∞=14.04 = the fine-dt asymptote. **Fine dt is REQUIRED to characterize the dense curve.**
- **SATURATION = CLASS B (bounded approach), fine-dt arm:** MM (Hill n≈1) strongly preferred (AICc −12 vs linear/power/log); **V∞=14.1 [13.4,14.6]**, **ρ½≈4565** [3931,5137], LOO-stable. BUT
  sampled only to **64% of V∞ at d8000** (top increment +13% ≫ noise) ⇒ V∞ is a model extrapolation ABOVE the data; directly observed top = **9.0 µm/s**. Not A (top increment material), not
  C (curvature resolved), not D (velPerBound decline is real tug-of-war, avgB rising — no engagement collapse), not E.
- **dt=5e-6 IS converged (Q4):** the apparent high-ρ 5e-6→2.5e-6 climb (+8% at d4000, 2 seeds) **did NOT survive the 1.25e-6 check** (d4000 ladder 6.41→6.35→6.97→**6.70**; the +9.7% reversed
  −3.8%; 1.25e-6 seeds straddle 6.37/7.04) — a small-sample chaotic fluctuation, not a systematic drift. The 1.25e-6 point did its job (refuted a spurious signal). velFitX converged within the
  ~±5–10% seed envelope; class E NOT supported.
- **BIOLOGY (Q5):** the unmodified fine-dt curve crosses biological ~4 µm/s at **ρ≈1800–2000** (physiological mat density). **The canonical model is ALREADY in the biological operating range —
  no xCatch or J2 intervention is motivated to reach biological velocity.** The open item is numerical (bistability at production dt), not physical; V₀/1.4 (9.5) and V₀ (13.3) bracket the
  in-range top (9.0) and extrapolated V∞ (14) only coincidentally.
- **matbox-50 control:** benign no-op at d2000 (ON 4.52 / OFF 4.38, within noise); the d8000 ON/OFF gap is the same production basin-tip, not a chamber effect. matbox fixes the y-coverage escape
  (established sweep VIOLATED at d8000) but NOT the free-x-runway graze at fast high-ρ glide (marginal flags d6000–8000, fine-dt YES/VIOLATED seeds agree ⇒ non-corrupting). Benchmark: GPU
  306→130→40 steps/s (d500→8000), CPU ~11× slower; 3 GPU seeds practical, 4 not.

### 2026-07-14 — EXPERIMENT 3G-A: fuller SEALED blinded optical-tweezers dataset GENERATED (supersedes 3G's package); NO analysis; NO canonical change

Rebuilt the 3G blind challenge to the fuller spec (`-exp3ga`/`-twobody-tweezers2`, default-off). **GENERATED + SEALED ONLY — no blinded analysis, no step/stiffness estimation
here** (blinding genuine). `RUN_LOGS/twobody_blind_tweezers/{PREREGISTRATION.md, blind_package/, private_truth/, generation_and_audit.log}`; report `docs/TWOBODY_BLIND_TWEEZERS_GENERATION.md`.
3C/3D/3E/3F byte-identical; `BoA-v1ref` clean; canonical untouched.
- **Frozen truth definitions (external, not assigned constants):** event step (pre→post COM plateau); unloaded step (zero-preload intercept + compliance-free zero-trap-stiffness
  intercept ≈7.9 nm); apparent stiffness (raw follow fraction); **crossbridge stiffness k_xb=2k_ax(Δ−δ)/δ** measured BOTH sides — **pre-stroke ≈0.64, post-stroke ≈0.62 pN/nm** (small
  real pre>post); truth is k_xb NOT the assigned F8 spring (1.0).
- **Matrix:** 165 stroke events (55/stiffness ≥50) at k_ax{0.02,0.05,0.10}×preload{−1,−0.5,0,+0.5,+1 pN, assist/zero/oppose} + 15 bound-no-release (attach,no-stroke, unlabeled in
  raw_traces) + 21 controls (9 no_motor / 6 motor_present / 6 no_motor_perturbation_calibration) + 3 long calibration. **Two-sided perturbations** (pre-stroke ADP·Pi + post-stroke
  ADP dwells) at amplitudes 1/2/3 nm × timescales ~3 ms & ~0.6 ms. Two datasets: ideal_observable_only (50 kHz noise-free) + instrument_realistic (20 kHz + 3 kHz LP + 0.8 nm noise +
  2.5 nm drift + ±10% calib), paired by trace ID in the specified subtree layout.
- **Physics (generator-side):** step scales with trap stiffness (7.5/6.9/6.0 nm = compliance division of intrinsic ~8 nm), weakly load-dependent (~1%/pN, honest not inflated);
  attachment=variance drop; deterministic k_xb clean-linear. Recovering step/stiffness from thermal+instrument noise is the analyst's identifiability question — NOT pre-judged.
- **Validation:** natural stereospecific capture (every event); polarity-correct (165/165 pointed-first); pre-transition trap force ~0 at preload 0; perturbations linear (≤0.5 nm bead);
  **restart reproducible (bit-identical)**; copy-without-repo; leakage audit CLEAN (no forbidden fields/names; honest control labels; randomized tr_<hash> IDs); MANIFEST_SHA256 verifies (412 files).
- **PENDING (separate session):** independent analyst (blind_package ONLY) → analyst_results.json → `python3 private_truth/unblind_compare.py` → `docs/TWOBODY_BLIND_TWEEZERS.md`.
  NO detachment/catch-release/ensemble until the blinded result is reviewed.

### 2026-07-14 — EXPERIMENTS 3F/3G: native Pi-release stroke on natural captures (3F) + blinded optical-tweezers challenge GENERATED & SEALED (3G); NO canonical change

**3F (Pi-release stroke, `-exp3f`/`-twobody-pistroke`):** extends 3E — search→ADP·Pi capture→pre-stroke relax→ADP·Pi→ADP (θ_s −30°→+30°, the FIXED material-frame ordering of
3C–3D, an absolute target, NOT from barbedDir)→post-stroke dwell — on NATURALLY captured motors (not reconstructed ideal). NO ADP-release/ATP/catch-slip/recovery/gliding/ensembles.
Default-off; 3C/3D/3E byte-identical; `BoA-v1ref` clean. Report `docs/TWOBODY_PI_STROKE.md`; artifacts `RUN_LOGS/twobody_pi_stroke/`.
- **PRELOAD DISTINCTION (resolved before any blinded data):** **capture** preload = F8 bond force at latch = **1.49±0.39 pN**; **relaxed** preload = after the passive ADP·Pi
  relaxation = **0.10±0.03 pN** (~15× drop — the pose relaxes the bond onto the material site); **pre-transition TRAP force** = the AXIAL force the trap reads = **−0.03±0.15 pN
  (~0)** because the relaxed F8 bond is nearly PERPENDICULAR to actin ⇒ ~0 axial projection. So the externally-observable pre-stroke tension is ~0 (a clean force-free step baseline).
- **118/118 natural captures RETAIN the 3D mechanics:** axial **6.90±0.04 nm**, transverse 0.10±0.06, force-on-actin·b̂ **−0.66±0.15 pN (pointed)**, k_ext(ADP) 0.624±0.004 pN/nm,
  load-sensitive (6.90→6.67 nm @2pN opposing), stable dwell (<0.5 nm drift). Chemistry-integration (the ADP·Pi→ADP switch) VALIDATED on real captures.
**3G (blinded tweezers GENERATOR, `-exp3g`/`-twobody-tweezers`):** a Langevin dual-trap actin-dumbbell (filament between 2 endpoint traps, filament Brownian ON) driven by natural
captures + the 3F Pi-stroke. **GENERATED + SEALED ONLY — this session does NOT analyze** (blinding kept genuine). `RUN_LOGS/twobody_blind_tweezers/{blind_package,private_truth}`.
- 48 events (×2 datasets: `ideal_observable_only` 50 kHz noise-free + `instrument_realistic` 20 kHz, 3 kHz low-pass, 0.8 nm noise, 2.5 nm drift, ±10% calib) at k_ax {0.02,0.05,0.10}
  × preload {0,1 pN} × 8, + 3 long calibration traces + 3 no-motor controls. Randomized IDs (`tr_<hash>`), SHA-256 manifests in both dirs.
- **Blinding verified:** blind exports ONLY {t, bead1, bead2, trapL_cmd, trapR_cmd} + calibrated k + polarity marker + sampling/filter metadata; NO binding/Pi times, chemical states,
  angles, head coords, F8 ext, assigned stiffness, or expected step leak; private truth not referenced from the package.
- **Physics (generator-side): calibration recovers k by equipartition (0.021/0.056/0.102); attachment = clear variance drop (~6.9→2.6 nm rms @0.05); pointed-first step ~7 nm
  matches noise-free truth; step scales with trap stiffness (7.5/6.9/6.0 nm @0.02/0.05/0.10 = compliance division of an intrinsic ~8 nm stroke); kext_true≈0.62 (NOT the F8 spring 1.0).**
- **PENDING (separate session):** independent analyst (blind_package ONLY) → `analyst_results.json` → `python3 private_truth/unblind_compare.py` → `docs/TWOBODY_BLIND_TWEEZERS.md`.
  Analyst prompt `blind_package/BLIND_ANALYST_PROMPT.md`; notes `RUN_LOGS/twobody_blind_tweezers/GENERATION_NOTES.md`. **No detachment/catch-release/ensemble until the blinded result is reviewed.**

### 2026-07-14 — EXPERIMENT 3E: Brownian + stereospecific binding capture (frozen ADP·Pi) — binding naturally recruits the 3D +30° basin; chemistry LICENSED; NO canonical change

Adds normal Brownian motion + a stereospecific binding SEARCH to the two-body motor, frozen in the ADP·Pi pre-stroke state, and shows it NATURALLY recruits poses at the
validated 3D basin WITHOUT snapping/reconstructing. Default-off `[NON-CANONICAL TWO-BODY PROTOTYPE]` (`softbox/TwoBodyConverterMotor.java`, `-exp3e`/`-twobody-capture`); NO Pi-
release/stroke/ADP/ATP/catch-slip/gliding; NO canonical change; `BoA-v1ref` byte-clean; **3C & 3D preserved byte-identical** (3E is new methods only). CPU-only ≈7 s. Report
`docs/TWOBODY_BINDING_CAPTURE.md`; artifacts `RUN_LOGS/twobody_binding_capture/` (+ `PREREGISTRATION.md`, `exp3e_summary.png`).
- **SEARCH:** the neck-lever Brownian-SWINGS about the FIXED tail anchor ((φ,ψ) diffuse under FDT torques + converter spring on θ=ψ−φ; common swing free). The converter SLAVES
  ψ≈φ+θ_s (θ_s=−30°) ⇒ near φ≈30° the head presents ψ≈0 at the actin site — the stereospecific basin. Trap-held filament (Brownian off). All gates in the LOCAL ACTIN MATERIAL
  FRAME (via bhat/eup/econv ⇐ Rm) ⇒ rotation-covariant, no world-axis bias. NAMED explicit tolerances (dist 3nm, ψ<25°, φ<25°, θ<20°, preload<2pN, E<15kT), NOT tuned to a rate.
- **LATCH not snap:** on all-pass, latch boundSeg + bindArc = the CURRENT material coordinate, KEEP (φ,ψ); the F8/converter/bind potentials then relax it (θ_s frozen, no Δθ). No
  teleport, no coord change, no setting φ=30°.
- **STUDY (260 dilute multi-seed episodes):** 252 captures (96.9%); capture pose **φ=29.0±11.2° ψ=−1.1±8.3°** (AT the +30°/0° basin), E=1.9±1.8 kT, preload 1.46 pN, bindArc
  499±3 nm interior, **0% implausible (E>15kT)**; genuine search (capture-time 632±2806 steps, tail ~24k). Rejection-by-gate: the orient(ψ)/lever(φ)/energy gates do the selecting.
- **REPLAY (separate, 140 native captures + 3D Δθ=60° stroke):** **140/140** pointed-first, 5-8nm axial (6.91±0.05), ≤2nm transverse (0.12±0.07), stiffness 0.5-2 (0.644±0.004),
  low preload, load-sensitive ⇒ native captured poses PRESERVE the 3D mechanics. The basin is REACHED by binding, not imposed.
- **CONTROLS:** spatial-only (orient gates OFF) → 100% accept but poses scatter (φ=±66° ψ=±63°) + **28.3% implausible** (E up to ~1000 kT) = why stereospecificity matters (rejects
  non-physical bonds); narrow(±8°)/broad(±55°) control capture SPREAD/realism but NOT the replayed stroke (relaxation basin absorbs the spread ⇒ 6.90-6.91 nm across all windows);
  polarity+rot90+rot3D acceptance ALL 0.983 (covariant, no world-axis); restart bit-identical; timestep — acceptance ≈dt-robust, relaxation dt-EXACT (φ→30.0°/ψ→0.0°/conDist→0).
- **DECISION:** binding naturally recruits the basin; tolerance width does NOT strongly control stroke/stiffness (relaxation-basin-absorbed); native captures preserve 3D mechanics
  ⇒ **the search is ready for the ADP·Pi→ADP transition — chemistry integration LICENSED as the next experiment.** **No canonical change; production defaults + `BoA-v1ref` unchanged.**

### 2026-07-14 — EXPERIMENT 3D: axial-stroke geometry remapping of the 3C two-body motor — Outcome A (geometry alone solves it); chemistry LICENSED; NO canonical change

Realigns the 3C lever swing so the arc SAGITTA (transverse) cancels, hitting the target 5–8 nm axial with ≤1–2 nm transverse. Default-off `[NON-CANONICAL TWO-BODY PROTOTYPE]`
(`softbox/TwoBodyConverterMotor.java`, `-exp3d`/`-twobody-axial`); NO chemistry/binding/detachment/catch-slip/gliding; NO canonical change; `BoA-v1ref` byte-clean; **3C preserved
byte-identical** (build3c refactored into a parameterized build3core with 3C-constant defaults + an η DOF gated off in stepC ⇒ all 7 3C CSVs reproduce bit-for-bit). CPU-only ≈6 s.
Report `docs/TWOBODY_AXIAL_GEOMETRY.md`; artifacts `RUN_LOGS/twobody_geometry_search/` (+ `PREREGISTRATION.md`, `exp3d_summary.png`).
- **MECHANISM (the whole result):** the 3C transverse (~6 nm) is the lever ARC SAGITTA — its swing (φ:−30°→−88°) sits entirely on the pointed side of vertical. In the strong-binding
  limit a lever point moves axial=L_B(sinφ_post−sinφ_pre), transverse=L_B(cosφ_post−cosφ_pre). **Symmetrizing the swing about the vertical (φ_pre=Δθ/2)** ⇒ transverse=0,
  axial=−2·L_B·sin(Δθ/2). Pure GEOMETRY: only the PRE-STROKE lever lean moves (−30°→+30°); the FIXED motor-frame Δθ SIGN is unchanged (barbedDir still out of target/torque/routing).
- **STAGE 1 (kinematic Pareto):** the symmetric prediction is EXACT — along φ_pre=Δθ/2 transverse≡0. Feasible Pareto front = that line: (20°,40°)→5.5, (25°,50°)→6.8, (30°,60°)→8.0 nm
  axial, all transverse 0. Refine picks **φ_pre=+30°, Δθ=60°** (keeps 3C's converter stroke ⇒ the ONLY change is the re-aim). [Fixed a false-positive overlap gate: head-center-near-
  filament-axis is normal binding contact, NOT an overlap — the real check is the head crossing to the filament's far side + head/anchor interpenetration.]
- **STAGE 2 (full mechanics, leader φ_pre=+30°/Δθ=60°, ref k_F8=1/κ_conv=128/κ_bind=512):** **k_ext=0.645 pN/nm** (bracket 0.45–1.40; skeletal), **stroke=6.91 nm** (3C 3.62),
  **transverse=0.09 nm** (3C 6.02 — ~65× down), **preload≈0**, iso-stall 4.78 pN, τ≈0.20 ms. Δφ=−57° (lever swing)/Δψ=+0.8° (head actin-aligned) = the lever-arm mechanism.
  **Load** (opposing→barbed): stroke 6.91→4.57 nm, completion 0.96→0.71, genForce→4.84 pN (load-sensitive, no servo; transverse ≤1.9 nm at 5 pN). **Polarity:** force-on-actin pointed,
  glide pointed-first; swap reverses world/preserves relative; rot90 & rot3D covariant. 7 candidates all in-band + handedness OK.
- **STAGE 3 (η) NOT NEEDED:** geometry solved both requirements. Bounded T1-passive `[COARSE-GRAINED TRANSVERSE REGISTRATION]` probe on the 3C geometry (illustrative only) reduces
  its 6.02→3.22 nm transverse at soft k_η=0.1 pN/nm — a partial, softness-dependent absorption; the symmetric geometry removes the sagitta at source with zero added compliance ⇒ not adopted.
- **TIMESTEP/LEDGER:** external stroke/transverse/peak+plateau force/angles **dt-INVARIANT** to <0.001 nm across {5e-6…5e-7}; work-ledger residual converges O(dt) (0.39→0.10) — the same
  fast-lever discrete-dissipation artifact as 3C, NOT a leak; fixed anchor does zero work (never integrated).
- **OUTCOME A / chemistry LICENSED** on this geometry (add binding/ADP·Pi→ADP Δθ-switch/ADP-release/ATP-detach one at a time; fine-dt gliding = regression target). Caveats: k_ext≤k_F8;
  transverse grows modestly under heavy load; fast lever needs finer dt for the ledger only; fixed anchor still defers substrate compliance. **No canonical change; production defaults + `BoA-v1ref` unchanged.**

### 2026-07-13 — EXPERIMENT 3C: topologically faithful 2-DOF head–converter–lever motor + material-frame handedness — Outcome B (viable AFTER flipping the material-frame ordering; the earlier stroke was BACKWARD); chemistry LICENSED; NO canonical change

Replaces the 3A/3B PINNED CAM (fixed neck-lever + single arm swinging about a fixed pivot; stroke sign taken tautologically from b̂) with the intended head–converter–lever
topology, and decides handedness from MEASURED pre/post trajectories. Default-off `[NON-CANONICAL TWO-BODY PROTOTYPE]` (`softbox/TwoBodyConverterMotor.java`, `-exp3c`); NO chemistry;
NO canonical change; FDT bit-unchanged (Gate 1); `BoA-v1ref` untouched. CPU-only ≈1.9 s. **ALL 26 gates PASS.** Report `docs/TWOBODY_TOPOLOGY_CORRECTION.md`; artifacts `RUN_LOGS/twobody_topology/`.
- **TOPOLOGY:** Body A = ellipsoid motor domain 9×5.5×4.5 nm carrying MATERIAL points r_F8=(3.5,1.5)/r_conv=(−3.5,−1.5) nm, |Δ|=7.6 nm (NO rod, NO head-arm body); Body B = neck-lever
  L_B=8 nm rotating (orientation FREE) about a FIXED-POSITION anchor. Generalized coords φ (lever about anchor), ψ (head); converter joint CLOSED by construction (C=A+L_B·ûB(φ);
  x_H=C−R(ψ)r_conv; x_F8=C+R(ψ)(r_F8−r_conv)), θ=ψ−φ, U_conv=½κ_conv(θ−θ_s)². Passive stereospecific U_bind=½κ_bind(ψ−ψ_actin)². Production F8. LINEARLY-IMPLICIT 2×2 with the F8
  Gauss-Newton stiffness k·J⊗J (REQUIRED — the tiny head makes the F8/head mode τ<dt otherwise).
- **HANDEDNESS (load-bearing, non-tautological):** Δθ FIXED in the motor frame (b̂ used ONLY in binding pose + analysis — access audit: postTargetSel=0, converterTorque=0, routing=0
  ⇒ Gate 8). MEASURED: original Δθ=−60° ⇒ F8·b̂=+7.60 nm BARBEDWARD (biologically REVERSED); corrected by FLIPPING the material-frame ordering (Δθ→+60°, Outcome B — NOT force/displacement
  ×−1) ⇒ F8·b̂=−3.98 nm POINTEDWARD, filament glide·p̂=+3.62 (pointed-first). **The earlier 3B/legacy stroke direction was BACKWARD.**
- **LEVER-ARM MECHANISM (M2):** strongly-bound head (κ_bind≳128) expresses the stroke as NECK-LEVER SWING (Δφ≈−58°, Δψ≈0) with the head actin-aligned — the biological lever swing;
  free head (κ_bind=0) splits (Δφ−24°/Δψ+34°, the soft regime). **Passive k_ext free-head 0.008 → bound-head 0.954 pN/nm** (skeletal for κ_bind≳128). Active stroke 3.62 nm, load-sensitive
  (→1.85 at 5 pN), preload 0, no servo. Ledger converges O(dt) to 3.1% @2.5e-7 (fast lever/converter discretization, not a leak).
- **LEGACY compare (M5):** corrected3C k_ext=0.64/Δφ=−57.6°(swings)/glide+3.62(POINTED); legacyCam k_ext=0.99/Δφ=0(frozen)/glide−0.43(BARBED) — **the cam and the articulated motor have
  OPPOSITE handedness for the same Δθ** (why the 3B tautology hid the reversal).
- **RENDERING:** typed `objects` JSON (physical_body/physical_bond/diagnostic_vector, each labeled; role NOT from color/width/order); the 3B "long diagonal line" was the pinned-cam
  head-arm (converter→F8 rod) — REMOVED (the ellipsoid material points encode the separation). 10 typed `-3js` sequences.
- **⇒ OUTCOME B (clearly corrected, on the CORRECTED topology not the legacy cam) ⇒ chemistry integration LICENSED** (add binding/nucleotide-target-switch/detachment/gliding one at a
  time on this faithful + polarity-correct prototype; fine-dt gliding = regression NOT fitting target). Caveats: large lever-arc transverse ~6 nm (flatter geometry later); fast converter
  needs finer dt; fixed anchor defers substrate compliance. NO canonical change.

### 2026-07-13 — EXPERIMENT 3B: biological two-body geometry remap + EXPLICIT actin polarity — Outcome A (scaled + polarity-correct); chemistry LICENSED; NO canonical change

Remaps the Exp-3A two-body prototype to a defensible coarse-grained myosin geometry AND makes actin barbed/pointed EXPLICIT in state/logs/JSON/viewer/tests. Default-off
`[NON-CANONICAL TWO-BODY PROTOTYPE]` (`softbox/TwoBodyConverterMotor.java`, `-exp3b`); NO chemistry/binding/gliding added; NO canonical change; FDT bit-unchanged (Gate 1);
`BoA-v1ref` untouched. CPU-only ≈1.1 s. **All 20 gates PASS.** Report `docs/TWOBODY_GEOMETRY_POLARITY.md`; artifacts `RUN_LOGS/twobody_geometry_polarity/`.
- **POLARITY AUDIT (executed code, Part V):** UNAMBIGUOUS — end1=coord−½L·uVec, end2=coord+½L·uVec; bindArc(s) increases end1→end2; **barbed=end2=+uVec, pointed=end1=−uVec**
  (`AxLockGateHarness`/`AgingHarness`/`directedSwing` "pointed→barbed"); canonical glide=pointed-first(−uVec)="correct". ⇒ **Outcome C refuted.** Exp-3A's +θ stroke dragged
  the filament +x=BARBED-first=biologically BACKWARD ⇒ 3B defines the working stroke via the stored b̂ (sweep F8 point toward pointed=−b̂).
- **GEOMETRY REMAP (Gates 2/3):** motor-domain ellipsoid 9×5.5×4.5 nm (semi-axes 4.5/2.75/2.25, hydro r≈4.6 nm) · **R_A=8 nm** (converter→F8 arm, DISTINCT from motor size) ·
  neck–lever **L_B=8 nm** (renamed; NOT S2/tail) · rigid calibration anchor. General-frame build (bhat/eup/econv) ⇒ arbitrary orientation.
- **PASSIVE (remapped):** rigid α=1.000 (Gate 14); k_ext = series(k_F8, κ/R_A²) EXACT after remap (resid ~1e-5), monotonic 0→k_F8 (κ=64→0.500, 576→0.900, ∞→1.000);
  trap+dt invariant (Gates 15/18). Active stroke pointed-directed 2.6–6.2 nm ~90% geometric, completion 0.96 (Gate 16).
- **POLARITY MECHANICS (measured dot products vs stored b̂/p̂):** **P1** clamped: F on actin·b̂=−3.518 pN (POINTED, Gate 9), F on motor·b̂=+3.518 pN (BARBED, Gate 8),
  Newton-exact · **P2** free filament glide·p̂=+4.388 nm (POINTED-FIRST, Gate 10) · **P3** swap b̂ (geometry fixed) ⇒ world −4.388→+4.388 REVERSES (Gate 11) · **P4** rotate
  assay 90°+3D ⇒ glide 4.388 / force −3.518 BIT-IDENTICAL to unrotated (COVARIANT, Gate 12) · **P5** reverse target ⇒ −4.388 (nonbiological sign control) · **P6** load vs b̂:
  completion resist 0.821<free 0.959<assist 1.081 (Gate 17). **⇒ Gate 13 no-world-axis-bias PASS** (depends only on stored b̂ + geometry, not ±x).
- **LEDGER (Gate 19):** the remapped converter is FASTER (γ_θ ∝ R_A²; R_A 10→8 shrinks γ_θ ⇒ τ_conv<dt for stiff κ) ⇒ the coarse-dt work residual is a DISCRETIZATION
  artifact (explicit F8-torque lags), converging 0.003→**0.0017** as dt→0 (κ=64); filament-rot dissipation=0 (centred attach). A real remap consequence (finer dt for stroke
  dynamics), NOT an energy leak.
- **Geometry controls:** R_A 6/8/10 → k_ext 0.780/0.667/0.561, glide 5.91/4.39/3.50 nm, force −4.99/−3.52/−2.75 pN — R_A trades stiffness↔stroke; **polarity signs invariant.**
- **Viewer/JSON (Gates 4/5/6/20):** every `-3js` frame carries an explicit `"polarity"` block (barbed/pointed coords+dirs, material-coord direction, motor-step/force-on-actin/
  glide vectors + b̂/p̂ projections) + distinct endpoint SHAPES (barbed broad cap / pointed narrow) + arrows; 8 matched sequences; serialization exact (P0).
- **The two-body kinematic decomposition DIFFERS from canonical (fixed neck-lever + swinging F8 grip vs canonical fixed grip + swinging lever) but the OBSERVABLE is identical**
  (force on actin pointed, glide pointed-first). **OUTCOME A ⇒ chemistry integration LICENSED** (add binding/nucleotide/detachment/gliding one at a time on this scaled +
  polarity-explicit prototype; fine-dt gliding = regression target). Caveats: k_ext≤k_F8 ceiling; stall F8-limited; rigid anchor (real substrate compliance deferred); faster
  converter needs finer dt. NO canonical change.

### 2026-07-13 — EXPERIMENT 3A: two-body converter motor, optical-trap characterization — Outcome A (VIABLE); NO canonical change

First replacement-motor prototype. A DEFAULT-OFF `[NON-CANONICAL TWO-BODY PROTOTYPE]` (`softbox/TwoBodyConverterMotor.java`, `-exp3a`): head + fixed lever-tail,
ONE converter DOF θ with elastic potential ½κ_θ(θ−θ_s)², production F8 (align OFF), rigid anchor — **NO J1/J2/F9/F10/AXLOCK/DIRSWING/XB_IMPLICIT2**. CPU-only ≈1.3 s.
**New file + 1-line dispatch; NO tracked/canonical source changed; FDT bit-unchanged (Gate 1 PASS); `BoA-v1ref` untouched.** Report `docs/TWOBODY_CONVERTER_TRAP_CHARACTERIZATION.md`;
artifacts `RUN_LOGS/twobody_converter/`. All 16 gates PASS.
- **Design:** a rigid head arm of radius R_A=10 nm pivots about a FIXED converter point P (=rigidly-anchored lever-tail's proximal end); F8 attach at radius R_A; θ the single
  in-plane coordinate (roll/out-of-plane KINEMATICALLY locked, yVec≡ŷ ⇒ 1 DOF, no ball joint, no hidden mode). Converter integrated SEMI-IMPLICITLY (unconditionally stable
  all κ; κ→∞ ⇒ θ→θ_s rigid limit). Effective converter stiffness at the F8 point = κ_θ/R_A². F8 = production `bondForces`, material-latched bindArc (never relatched).
- **Stage 0/1:** zero preload exact (F8=0, convTorque=0); rigid converter (θ frozen) reproduces the Exp-2B fixed-head reference **α=1.000** (Gate 4). ⇒ Outcome F (hidden
  compliance) REFUTED, force routing correct.
- **Stage 2/3 — the two-body motor is a CLEAN EXACT SERIES SPRING:** k_ext = 1/(1/k_F8 + R_A²/κ_θ), matching measured to ~1e-5. Monotonic 0 (κ=0, the free-rotating sphere)
  → k_F8 (κ→∞, fixed head). **k_ext ≤ k_F8 (F8 is the stiff series ceiling).** @k_F8=1: κ=100→0.500, κ=1000→**0.909 pN/nm (skeletal band)** vs the canonical three-body ~0.02.
  Trap-invariant (k_motor=0.500 flat). Gates 5/6/7/12 PASS. (One stiff-F8/soft-converter corner ill-conditioned → small negative k_ext, reported not clipped.)
- **Stage 4 — finite external stroke:** a θ_s: θ_pre→θ_post shift (NOT a trajectory/servo/re-aim/relatch) sweeps the F8 point, dragging the material-latched filament. External
  stroke 2.5–8.1 nm (~90% of geometric R_A·sinθ_post); **completion RISES with κ (99% at κ=1000)**. Gate 8.
- **Stage 5 — load/stall:** opposing force clamp 0–5 pN drops converter completion **0.92→0.12** (Gate 9), generated force rises to match; NOT a servo (Gate 10); fully
  reversible. Isometric stall **2.6 pN — F8-COMPLIANCE-LIMITED** (half the rigid κΔθ/R_A=5.24 bound; ceiling k_F8·R_A·sinθ_post≈5 pN). Under a constant-force clamp the filament
  stroke is ~isotonic (load pre-deflects the soft converter); the stall shows in COMPLETION + isometric force.
- **Ledger (Gate 14):** active-stroke ΔU_target = ΔU_conv+ΔU_F8+ΔU_trap+dissipation, **residual 0.2%** — the injected converter free energy is mostly DISSIPATED by the head
  swinging through γ_θ (adding converter rotational dissipation was required to close it; was 0.65 without). Timestep dt-invariant (Gate 13); sign reverses (Gate 15); 6 `-3js` (Gate 16).
- **THE HEADLINE — Outcome A (VIABLE) + Outcome D (tradeoff) REFUTED:** a stiff converter (κ≳300 pN·nm/rad²) simultaneously gives skeletal-range stiffness (0.75–0.91·k_F8) AND a
  near-complete load-sensitive stroke (both RISE with κ — no conflict). The two-body reaches ~0.9 pN/nm (vs canonical ~0.02) because it has ONE compliance + a RIGID anchor —
  realizing the Exp-2A "collapse to a stiffer two-body" recommendation and the Exp-2B fixed-head finding. **DECISION: PROCEED to chemistry integration** (converter stiff, k_F8
  chosen for the target combined series stiffness; fine-dt gliding = regression target, not fitting target). NO canonical change made. Caveats: k_ext≤k_F8 ceiling; stall
  F8-compliance-limited; rigid anchor (real substrate compliance deferred — the next series-compliance term).

### 2026-07-13 — EXPERIMENT 2B: fake-coupler stiffness-transfer ladder — Outcome A (exact F8 recovers ~100%); the 1.0→0.65 loss is HEAD-SIDE compliance, NOT F8 geometry; NO model change

Deliberately non-biological diagnostic: how a coded F8 spring becomes the blinded-inferred stiffness as we add, one at a time, 3D geometry, bond orientation,
material attachment, filament translation/rotation, off-axis torque, trap compliance, and a fixed spherical head — and where the settled Exp-2A **1.0→0.65**
(H8 frozen-motor / F8-only) loss localizes. CPU-only, ≈3.5 s. **New behaviour ONLY in new `softbox/LaserTrapFakeCoupler.java` + a 1-line `-exp2b` dispatch in
the untracked `LaserTrapHarness` + `scripts/lasertrap_fake_analyze.py`; NO tracked/canonical source changed; FDT regression bit-unchanged (Gate 1 PASS);
`BoA-v1ref` untouched; default-off, `[NON-CANONICAL FAKE COUPLER]`-labelled.** Report `docs/LASER_TRAP_FAKE_COUPLER_LADDER.md`; artifacts `RUN_LOGS/lasertrap_fake_coupler/`.
- **Exact F8 (Gate 3):** every F8 arm calls the PRODUCTION `CrossBridgeSystem.bondForces` with the F9/F10 alignment coeff = 0 (`xbParams[2]=0`) ⇒ the pure
  **zero-rest-length** Hookean spring `F=myoSpring·(site−tip)` + its exact R×F torque, NOT reimplemented. Head = a frozen (or, F7, freely-rotating-about-a-pinned-
  centre) MotorStore head sub-body; seg reaction via the production CSR `segGather`; traps = validated `applyTraps3D`; blinded paired-±step estimator (Exp-2A).
- **THE STRUCTURAL FACT:** F8 is zero-rest ⇒ its linear stiffness tensor is ISOTROPIC `k·I` ⇒ orientation/preload/off-axis-torque cost NOTHING at linear order; any
  loss must be MOTION of the parts. The ladder measured this instead of assuming `cos²θ`.
- **Transfer curve F0/F1/F6 (trap 0.05):** α = k_blinded/k_assigned = **1.000** for k_F8 ≤ 1.5; at k ≥ 2 k_motor is **UNIDENTIFIABLE** (follow<5%, trap-limited —
  reported NaN not clipped, Exp-1b Outcome D); k_obs saturates toward k_trap (instrument limit, NOT coupler saturation). **F0 (scalar spring) ≡ F1 (axial F8) ≡ F6
  (fixed sphere)** — all recover 100%.
- **Localization (native stage-E, k=1; bonds span 13–157°, 2–7 nm, n=48):** F1 axial 1.000 · **F2 orient 1.0000 · F2 +native preload 1.0000 · F3 +filament rotation
  1.0000 · F4 off-axis z-bond ¼/⅛L rot-ON 0.9999/0.9995 (filRot 0.4–0.5°, torque ~1e-22 N·m) · F5 torque-cancel 1.0000** — every pure-F8 geometry recovers full k.
  **ONLY F9 (canonical frozen motor = Exp-2A H8) = 0.6534.** ⇒ the 0.65 loss is NOT F8 spring/orientation/preload/attachment/torque/filament-rotation; it is the
  **head-side canonical machinery** (F9/F10 alignment torques + XB_IMPLICIT2 coupled solve) that F9 carries and the pure-F8 arms omit.
- **Spherical head (the mechanism, direct):** **F6 fixed = full k**; **F7 free-rotating = α 0.48→0.14→0.042, k_motor SATURATES ≈0.17 pN/nm** as k_F8 rises (a
  fixed-magnitude head-rotation compliance in series — the many-body analog of the canonical body); **F8 orientation-restrained → recovers F6** (validates the
  rotational reading). ⇒ **Outcome G + E.**
- **Is 0.65 constant?** NO. Fixed-F8 arms α=1.000; the head-compliance analog (F7 / real motor) α FALLS with k (saturates) ⇒ 0.65 is one point on a saturating curve,
  not a transmission constant. **B/C/D/H all REFUTED; controlling = A, with G+E as the loss mechanism.** Confirms & mechanistically explains Exp-2A Outcome E.
- **Closure/dt/Brownian:** F1 centred net force/torque = 0, work-ramp resid 2.3e-6 (Gate 13); F1 & F6 dt-invariant (Gate 12); Brownian F1/F6 mean-Ftrap~0 rms~0.2 pN,
  F7 rectifies (~0.28 pN), |F8|~2.4–3.3 pN light-head inflation (detachment off ⇒ high-F overrepresented; NO 12 pN cap referenced). 8 `-3js` sequences (Gate 14).
- **DECISIONS:** fixed spherical head (F6) is the clean full-stiffness reference for a two-body prototype; a rolling head NEEDS an explicit orientation potential
  (F7 too soft); stiffen the head/body pathway BEFORE touching F8 (F7 saturation ⇒ raising F8 alone can't overcome a compliant head). **NO canonical F8 change made
  or recommended.** Recommendation for the anchor/two-body study recorded in the report §13.

### 2026-07-13 — EXPERIMENT 2A: native-pose DYNAMIC compliance localization (time-resolved trap + one-DOF holds) — Outcome E (distributed), F8 is skeletal-stiff; NO model change

First step of the mechanical-stiffness intervention study: localize WHICH freedom causes the Exp-1b softness before adding any spring. CPU-only,
**new behaviour only in untracked `LaserTrapHarness` (`-exp2a`) + `scripts/lasertrap_localization_analyze.py`; no tracked/canonical source changed; FDT
regression bit-unchanged (Gate 1 PASS); `BoA-v1ref` untouched; default-off.** Full run ≈30 s (40 snapshots/stage, n=20 used). Report
`docs/LASER_TRAP_COMPLIANCE_LOCALIZATION.md`; artifacts `RUN_LOGS/lasertrap_compliance_localization/`.
- **Time-resolved blinded estimator** `k_obs(t)=ΔF_trap/Δx_cmd` (paired ±) + compliance-corrected `k_motor(t)=paired ΔF-slope/paired filament-follow-slope`
  (NaN, not clipped, when follow<5%). **Diagnostic HOLDS H0–H8** = exact kinematic projections pinning ONE DOF to its captured value each step, reporting
  reaction force/torque/work; `[NON-CANONICAL DIAGNOSTIC HOLD]`, default-off, NOT models.
- **Stage 1 (Gate 2) — estimator VALIDATED:** motor-free peak `k_obs`=k_eff exactly, plateau→0; **known scalar-spring recovered to 100.0% across 3 springs×3
  traps, FLAT vs trap** ⇒ the Exp-1b trap-dependence is genuine multibody ill-conditioning, NOT estimator failure; synthetic reproduces Exp-1 (0.0019/0.0040).
- **Stage 2 baseline:** early `k_obs@10µs`≈0.099≈k_trap (the INSTRUMENT, not the motor) decaying to the soft plateau ⇒ **Outcome A (bandwidth) REFUTED**
  (no stiff-early MOTOR mode); plateau stiffens ~1.6× A→E; trap-dependence persists (Outcome-D caveat); native pose persists (frozen-relaxed ≈ minimal-settle); linear.
- **Stage 3 holds (compliance-corrected `k_motor`@plateau, mean B,E):** H0 0.019 · **H2 rod-rotation(anchor pivot) 0.054 (leading single DOF)** · H7 rigid-chain
  0.027 · **H8 frozen-motor/F8-only 0.653 — INSIDE skeletal 0.5–2!** ⇒ **F8 + attachment geometry are NOT the ceiling (Outcome F REFUTED)**; the softness is the
  **~34×-softer articulated body IN SERIES with F8**. J1/J2/head holds ≈0 (reaffirms J2-null); even locking all internal articulation (H7) recovers ~1% of the
  H0→H8 gap; H2 alone ~5%. **Holds quasi-neutral** (reaction work ~1e-18–1e-20 J, H2 injects 0 force) ⇒ genuine transmission change, not immobilization (Gates 5,10).
- **Gates:** G1–G11 all PASS (G8 H2 improves across full trap bracket; G9 dt-stable, H8 +3.5% reported). Telemetry: motion taken up by anchor extension + F8, joints small.
- **OUTCOME E (distributed architectural compliance)** with B flavour (rod pivot about the tail anchor = leading single contributor). **Ranked next:** (1) rod-orientation
  restraint about the anchor (cheap/interpretable/partial); (2) tail-anchor trans+rot stiffening; (3) **simplified two-body spherical-head prototype — LICENSED** (no single
  hold, nor the internal rigid-chain, reaches skeletal; only whole anchor/rod-body rigidification does — but F8 itself is already skeletal-stiff, so the two-body job is to
  remove the series articulated-body compliance, NOT fix F8); (4) J1/J2 transmission NOT recommended; (5) attachment geometry NOT the limiter. **No intervention implemented.**

### 2026-07-13 — EXPERIMENT 1b: native-pose BLINDED optical-trap stiffness — Outcome D (not uniquely identifiable) over B (canonical soft mode); NO model change

Measurement/identifiability follow-up to Exp-1's flag that its synthetic vertical pose (J2=0°/62.9° collinear) is non-native. CPU-only,
**new files only — `git diff` on tracked source EMPTY** (canonical untouched; `BoA-v1ref` untouched; default-off). Extended `LaserTrapHarness`
(`-nativegen`,`-exp1b`); `LaserTrapSystem` reused. Four ordered stages: generate native poses → snapshot A–E → blinded 3D-trap replay → analyse.

- **Native generation (Gate 2):** composed the EXACT canonical stepOrig WITH binding/cycle/release ON (Lymn-Taylor, ADP·Pi-only bind), filament
  velocity-clamped v=0, **dilute 64-motor bed** (clamped filament ⇒ independent single-molecule episodes, the episode-kernel trick — the unbound
  free-jointed arm floods a 0.1 µm sphere so 1-motor binding is diffusion-limited). Canonical geometry (anchor −0.05, fil z=0). 60 snapshots/stage × 4 seeds.
- **Gate 4 coordinate reconciliation:** SAME atan2 unsigned J2 formula as the native audit + Exp-1; native means **A/ADP·Pi 104°, E/ADP 142°**
  (match audit 103°/122°); Exp-1's **0°/62.9° = collinear synthetic assembly**, non-native. Same formula, different poses.
- **Gate 3 restart fidelity PASS; Gate 6 power PASS.**
- **BLINDED stiffness (external observables only; k=ΔF_trap/Δx_fil):** median **A 0.0154 → E 0.0310 pN/nm** (stiffens ~2× with age, tracks J2),
  broad (p5–p95 ~0.002–0.056), fracNeg 2–7%, 0 unstable. **Native ~8× stiffer than synthetic (0.0019/0.0040) — refutes a pure synthetic artifact —
  but still 16–65× below skeletal (0.5–2 pN/nm); ~0% of events reach skeletal.**
- **Gate 7 trap-invariance FAIL ⇒ Outcome D:** compliance-corrected k = 0.017/0.025/0.029 at trap 0.02/0.05/0.10 pN/nm (49% spread) — the motor is
  comparable-to-softer than the trap ⇒ correction ill-conditioned ⇒ NOT uniquely identifiable. **Gate 8 timestep PASS (0.3%).** Gate 10: native
  pose PERSISTS (drift ~2 nm); native-instantaneous ill-posed (relaxation transient), relaxed is the identifiable value.
- **Secondary (unblinded AFTER freezing primary):** within stage E, r(k,anchorExt)=+0.37, r(k,|F8|)=+0.37, r(k,J2)=−0.18…−0.28 ⇒ tail-anchor +
  F8 load are the leading compliance predictors (the anchored body pivots; consistent with Exp-1 + episode kernel). Brownian |F8| median 3.4 pN,
  p99 7.5, tail ~8.9 pN (thermal rectification; detachment OFF ⇒ high-force overrepresented; NO 12 pN cap in canonical, `-forcecapdetach` is separate).
- **OUTCOME D (not uniquely identifiable) over B (canonical soft mode), C flavour (pose/age spread), NOT A (native≠synthetic-artifact).** Gates 1–6,8,9,11
  PASS; G7 FAIL (=the Outcome-D signal); G10 reported. **NO model change.** Intervention is scientifically motivated but NOT implemented; ranked
  candidate compliance sources (tail-anchor pivot #1, articulated J1/J2 #2, F8 projection #3) for a later one-factor default-off study gated on the
  blinded trap observable. Report `docs/LASER_TRAP_NATIVE_POSE_STIFFNESS.md`; prereg+log+CSV+figure `RUN_LOGS/lasertrap_native/`; viewer `threejs_native_*/`.

### 2026-07-13 — EXPERIMENT 1 (+Stage 0b): 3D trap + passive forced-bound canonical-motor compliance — CONDITIONAL PASS; Exp-2 licensed

Ordered two-stage compliance measurement, CPU-only (GPU reserved for the fine-dt sweep). **New files only — `git diff` on tracked
source EMPTY** (no canonical/production file touched; `BoA-v1ref` untouched). Extended `LaserTrapSystem` (`applyTraps3D`: vector
diagonal-tensor trap, kTr=0 recovers the axial-only Exp-0 law) + `LaserTrapHarness` (`-0b`, `-exp1`).

- **Stage 0b (3D trap dumbbell, no motor) — all 6 tests PASS:** k_effAx=2kAx, k_effTr=2kTr, **kθ=kTr·L²/2·1e-6 matched to 0.1%**,
  translational covariance FDT-correct (axial 1.00/transY 0.97/transZ 0.87), angular sub-thermal by the known **BRotCoeff=0.5** knob
  (reported, NOT gated on naive kT/kθ), deterministic axial τ dt-invariant 0.10%. Pretension 5 nm (0.25 pN) stabilizes orientation
  (kθ rises) without changing axial k_eff (Gate 4).
- **Experiment 1 — EXACT canonical composition (Gate 5 by construction):** the `GlidingHarness.stepOrig` mechanics subset (joints→
  anchor→bondForces[F8/F9@90/AXLOCK]→applyHeadForce→directedSwing→integrate→XB_IMPLICIT2 couple) with the springs baking verbatim;
  passive = forced-bound at the material midpoint (boundSeg=0, bindArc=½segLen, material-latched), binding/release/cycle removed,
  nucleotide frozen (DIRSWING retained as the fixed state's potential). NO surrogate motor equations.
- **Key results:** both states equilibrate to plateau (ADP·Pi F8=0.153 pN lever-0°; **ADP F8=0.320 pN, DIRSWING→lever-60° J1=59° J2=63°**).
  Force–displacement LINEAR/SYMMETRIC (±0.5–4 nm). **k_motor,eff = 0.0019 (ADP·Pi) vs 0.0040 (ADP) pN/nm — +113%, ADP ~2.1× stiffer**
  (state-dependent compliance differs measurably). **Series identity k_obs=1/(1/k_trap+1/k_motor) EXACT (resid 0.000) ⇒ whole-motor
  stiffness cleanly IDENTIFIABLE** (Gate 11). Timestep dt-invariant 0.03% (Gate 9). Pretension-independent (1/5/10 nm). **Absolute
  stiffness ~100–1000× SOFTER than skeletal (~0.5–2 pN/nm)** — dominated by the anchored articulated body PIVOTING about the tail
  anchor (near-vertical F8 bond; the filament follows the command ~98%, F8 barely changes, head-chain translates with it); a real
  finding with a geometry caveat (may not represent the load-bearing engaged cross-bridge). **J2 accommodates (~0.65°/nm; 63° in ADP)
  but does NOT set incremental stiffness ⇒ reinforces the settled near-neutral-hinge result.**
- **Controls:** axial-only trap gives **0 stiffness** (filament escapes transversely) vs 3D 0.0018 — materially different, 3D essential.
  Brownian: deterministic |F8|=0.15 pN vs stochastic-mean 3.54 pN = light-sphere-head thermal RECTIFICATION (magnitude), not a basin shift.
- **Gates:** G1–G7,G9,G10,G11 **PASS**; **G8 energy CONDITIONAL** (multibody rotational/joint-mover dissipation not captured; force/disp
  closure exact). **Outcome CONDITIONAL PASS.** Conditions carried to Exp-2: (a) absolute stiffness geometry-dependence (§7.1), (b) energy
  closure approximate. **Experiment 2 (deterministic active working-stroke) LICENSED.** Report `docs/LASER_TRAP_PASSIVE_MOTOR_COMPLIANCE.md`;
  prereg+logs+CSVs+figure `RUN_LOGS/lasertrap_motor/`; viewer `threejs_lasertrap_motor_{adppi,adp}/`. `run_lasertrap.sh -0b|-exp1`.

### 2026-07-13 — EXPERIMENT 0: virtual optical-trap FILAMENT calibration assay — all 7 gates PASS; CONDITIONAL PASS (Exp-1 licensed)

Setup-validation experiment BEFORE any motor: prove the optical-trap geometry, force balance, thermal equilibrium,
relaxation, timestep behaviour, energy accounting, logging, and visualization are correct. **No motor / chemistry /
binding / crosslinker / turnover.** **CPU-ONLY** (GPU reserved for the fine-dt gliding sweep, ~89% util; the harness
refuses `-gpu`). **New files only — `git diff` on tracked files EMPTY** (canonical bytecode unchanged; `BoA-v1ref`
untouched): `softbox/LaserTrapSystem.java` (endpoint trap force+torque, the `ContainmentSystem` r-in-metres
convention), `softbox/LaserTrapHarness.java`, `scripts/run_lasertrap.sh`, `scripts/lasertrap_analyze.py`.

- **Scene:** ONE rigid actin rod (`FilamentStore` n=1, L≈1 µm) between two axial-projected harmonic traps on the
  derived endpoints (`F=−k[(x−x0)·f̂]f̂`), composing the SHARED drag/Brownian/rigid-rod-Langevin/derive systems.
  Rotation LEFT FREE and MEASURED (not suppressed). A single rigid segment cannot carry internal strain ⇒ A2 trivially
  clean. The optional `applyAxisPrep` orientation term stayed **OFF** (not needed).
- **Analytic backbone (derived, not assumed):** the two endpoint x-offsets ±(L/2)u_x cancel in the force sum ⇒
  `F_net=−(k_L+k_R)(x_c−x_eq)` orientation-independent ⇒ **k_eff = k_L+k_R** (NOT one trap stiffness), effective drag
  = γ_∥, `⟨δx²⟩=kT/k_eff`, `τ=γ_∥/(1e6·k_eff)`, `x_eq=F/k_eff`.
- **Phase A (deterministic):** A1 net/torque = 0, no drift; A2 follows to ≤1.2e-7 µm, internal strain 0; A3 tension =
  k·d EXACT with correct signs, net 0; A4 τ_meas 1.31344 ms vs pred 1.31844 ms (0.38%); A5 x_eq=F/k_eff <0.001%,
  **R²=1.0000000**, linear both signs. Deterministic dt-ladder: τ 1.31344→1.31594→1.31719 ms (→1.31844), **finest-two
  |Δτ|/τ = 0.09%** (noise-free timestep leg); k_eff invariant <0.003%.
- **Phase B (thermal, canonical Brownian, 8 paired seeds × dt{1e-5,5e-6,2.5e-6} × 0.3 s):** equipartition var/var_pred
  = **0.985 ± 0.018 (n=24)**; timestep paired Δvar/var_pred (finest two) = −0.050±0.032 (within noise, reported
  directly). Angular excursion 3.4–7.1° RMS, transverse 0.1 µm RMS — no tumbling/escape (axial-only traps don't confine
  transverse/orientation; a real 3D bead trap would).
- **Phase C (step recovery):** 2/5/8/11 nm × 3 stiffnesses — raw step fully recovered, peak force = k_eff·step, τ∝1/k
  (3.29/1.31/0.654 ms). Observation operator (moving-avg 50 + downsample 20) kept SEPARATE from raw, NOT tuned.
- **Energy (Gate 7):** free-relax ΔU = dissipation (residual 0.38%); differential ramp W_center = ΔU + dissipation
  (residual 0.0%).
- **Gates:** G1 default-path (empty diff + FDT CPU regression unchanged) · G2 force balance · G3 equipartition · G4
  relaxation · G5 timestep (deterministic 0.09% + thermal within noise) · G6 viz (230 `-3js` frames, dumbbell + traps +
  attachments + connectors + scale in the EXISTING viewer schema; geometry numerically verified: 0→+40→0 nm) · G7
  energy — **all PASS.**
- **Outcome: CONDITIONAL PASS.** All gates green; the ONE carry-forward condition is that axial-projected traps do NOT
  confine transverse/orientation and a forced-bound motor loads those DOF ⇒ Exp-1 must choose full-3D traps / weak axis
  prep / monitored-free explicitly (do NOT tune it). Single-rod idealization + provisional stiffness bracket
  (0.02/0.05/0.10 pN/nm, NOT biological) are the other carry-forward notes. **Experiment 1 (passive forced-bound motor)
  LICENSED.** Report `docs/LASER_TRAP_FILAMENT_CALIBRATION.md`; figure `RUN_LOGS/lasertrap/lasertrap_summary.png`;
  prereg + logs + CSVs `RUN_LOGS/lasertrap/`. Runner CPU, ~1.2 s full assay; no canonical default changed.

### 2026-07-12 — FINE-dt V₀ REFERENCE: canonical rigid-clamp force zero is operationally converged at ≈13.3±0.5 µm/s; production dt overestimates it by +2.7 µm/s (+20%)

Well-powered follow-up to the under-seeded timestep ladder in `TIMESTEP_SERVO_AUDIT`. **Measurement-only — NO physical-model or production-setting change:** ran the existing default-off CPU `-vclamp` harness at dt={1e-5, 5e-6, 2.5e-6, 1.25e-6} s with the canonical `SPHEREHEAD+AXLOCK+DIRSWING+XB_IMPLICIT2+LYMN_TAYLOR` stack; F9 frozen at 90°, DIRSWING 0°→60°, J1/J2 native angular springs off; `BoA-v1ref` untouched. Used the validated d2000/matbox50 compact clamp, **constant 80 ms physical duration** with constant 26.7 ms physical warm-up, step counts 8k/16k/32k/64k, **8 paired seeds identical across every arm**, and a refined near-zero velocity grid v={10,12,13,14,15,16,18} µm/s (+8 for production): 232 runs total.

* **Fixed-velocity convergence — decisive anchor:** production→5e-6 lowers f̄_available significantly at v=12/14/16 by approximately −0.07/−0.07/−0.08 pN, whereas both subsequent halvings are statistically unresolved at all three velocities. The three fine force–velocity curves are mutually near-coincident; the production curve is shifted upward.
* **Joint local fit f=a+bv, V₀=−a/b:** **16.07 [15.23,17.60]** µm/s at dt=1e-5 → **13.53 [12.58,14.66]** → **13.01 [12.43,13.55]** → **13.41 [12.76,14.21]** at the three fine timesteps. The fine estimates overlap, span only 0.52 µm/s, and are stable to leave-one-seed-out, dropping either outer velocity, and five alternative fit windows.
* **Canonical numerical statement:** over the tested fine-timestep range, the rigid-clamp force zero is operationally stable at **V₀_fine≈13.3±0.5 µm/s**. The routine production timestep gives **V₀_prod≈16.1 µm/s**, an upward bias of **ΔV₀≈+2.7 µm/s (+20%)**, exceeding the pre-registered ±2 µm/s tolerance. Production dt remains useful for routine qualitative work, but its quoted V₀ is not numerically converged.
* **Mechanism of the dt shift @v=12:** as dt decreases, mean bound count rises **3.98→5.39**, lifetime rises **0.52→0.63 ms**, attachment frequency falls **1514→1308 s⁻¹**, and net episode impulse falls **+0.087→+0.030 pN·ms**. Finer dt therefore gives **more and longer attachments but less net forward impulse per attachment**. The isolated DIRSWING stroke is already dt-converged; the residual bias lies in sustained cross-bridge force during continuous sliding.
* **Interpretation:** a finite force zero exists at every timestep, so the model’s capacity for density-dependent gliding saturation is unchanged. Refinement moves the quantitative ceiling downward but does not remove the ceiling. Do **not** infer the asymptotic free-gliding plateau by dividing clamp V₀ by the prior ~1.4 finite-density force-balance discrepancy; measure the free-gliding plateau directly.
* **Status:** **resolved fine-timestep reference**, not a formal proof of the mathematical dt→0 limit. Report the empirical result as **13.3±0.5 µm/s over dt≤5e-6 s**, and attach the +2.7 µm/s timestep bias wherever the production value ≈16 is quoted. No routine dt change made. Report: `docs/FINE_DT_V0_REFERENCE.md`; figure: `docs/FINE_DT_V0_REFERENCE.png`; raw logs: `RUN_LOGS/v0fine/`; pre-registration: `RUN_LOGS/v0fine/PREREG.txt`.


### 2026-07-12 — FINE-dt V₀ REFERENCE: converged rigid-clamp force-zero = 13.3 [12.8,13.8] µm/s; production dt biases V₀ +2.7 (+20%) HIGH (Outcome A; E rejected)
Well-powered follow-up to the servo audit's under-powered Part-6b V₀ ladder ("~13±2", n=2–3). **Measurement-only —
NO code change** (ran the existing default-off `-vclamp` harness at 4 timesteps; motor model / rates / force laws /
kinetics / production dt all unchanged; `BoA-v1ref` untouched; CPU basin arbiter). Canonical stack confirmed default
(SPHEREHEAD+AXLOCK+DIRSWING+XB_IMPLICIT2+LYMN_TAYLOR; F9 frozen 90°, DIRSWING 0°↔60°, J1/J2 angular off). d2000
compact clamp bed + matbox 50 (the validated FORCE_VELOCITY geometry), **80 ms physical window held constant**
(warm-up=M/3 ⇒ constant *physical* equilibration — the Part-6a lesson), steps ∝1/dt (8k/16k/32k/64k), **8 paired seeds
identical across arms**, v∈{10,12,13,14,15,16,18}(+8 prod). 232 points, ~7.5 core-h.
- **Fixed-velocity paired convergence (the robust anchor):** Δf̄(dt→dt/2) paired within seed — the **1e-5→5e-6 drop is
  SIGNIFICANT** at v=12/14/16 (−0.07/−0.07/−0.08 pN), but **5e-6→2.5e-6 and 2.5e-6→1.25e-6 are BOTH non-significant**
  (CIs span 0). Force converged by 5e-6.
- **Joint local linear fit** f=a+bv → V₀=−a/b, paired-seed bootstrap: **16.07 [15.2,17.6] (1e-5) → 13.53 → 13.01 →
  13.41 [12.8,14.2] (1.25e-6).** Three fine arms compatible (spread 0.52, overlapping CIs) ⇒ plateau. Stable under
  leave-one-seed-out, drop-lowest-v, drop-highest-v, and across 5 fit windows (fine arms ±0.45).
- **dt-limit models:** Model A (plateau over finest 3) = 13.32 SELECTED (fixed-v force flat below 5e-6). Model B
  first-order intercept 12.4, Model C p=2 intercept 13.0 — both bound the reference in [12.4,13.0]; 4 levels don't
  justify a free exponent. **Reference V₀_fine = 13.3 [12.8, 13.8] µm/s** (combined paired bootstrap 13.43 [13.20,13.67],
  widened for the between-arm+window systematic).
- **Production bias ΔV₀ = +2.68 [+1.60,+4.18] µm/s (+20%) ⇒ EXCEEDS the pre-registered ±2 tolerance ⇒ Outcome E
  (production adequate) REJECTED.** **Outcome A (resolved fine-dt reference).**
- **Mechanism (dt→0 @v=12):** N_bound RISES 3.98→5.39, lifetime 0.52→0.63 ms, J_attach FALLS 1514→1308, net episode
  impulse FALLS into drag +0.087→+0.030 — more/longer attachments, less net forward impulse per attachment = the
  cross-bridge sustained-force-under-sliding (duty) ceiling, NOT the stroke (the stroke is dt-converged, servo-audit
  Part 6a). Confirms the servo-audit decomposition at 8× power.
- **Consequence (informational):** V₀_fine/1.4 ≈ 9.5 µm/s operative free-glide ceiling (vs 16/1.4≈11.4 at production dt)
  — nearer biological Vmax~8 (ζ_eff flagged approximate: measured at production dt). Fix to earn 13.3 in production =
  the standing cross-bridge SUB-STEP, NOT a routine dt cut (1.25e-6 = 8× wall). Report `docs/FINE_DT_V0_REFERENCE.md` +
  `.png`; raw `RUN_LOGS/v0fine/`; pre-reg `RUN_LOGS/v0fine/PREREG.txt`. Extends `docs/TIMESTEP_SERVO_AUDIT.md`,
  `docs/FORCE_VELOCITY_TEST.md`, `docs/FORCE_BALANCE_CLOSURE.md`.

### 2026-07-12 — TIMESTEP & SERVO-WORK AUDIT: canonical DIRSWING is a CONVERGED FINITE STROKE (not a servo); production-dt V₀ is ~25–45% HIGH (Outcome A + E, C-rider; B refuted)
Audited whether canonical DIRSWING is a converged finite stroke or a dt-dependent active servo, and whether the
production-dt mechanics are timestep-converged. New default-off byte-identical instrumentation (`-vclamp 8` FVROW
`fbar_avail=0.13541` unchanged): **`-servoaudit`** — deterministic single forced-bound motor (all Brownian off), the
only clean substrate (zeroing motor Brownian in the CARPET kills binding — thermal search is what reaches the
filament); per-step rotation-vector distributions, per-channel MIDPOINT signed work, cumulative DIRSWING work vs age,
closed-cycle perturbation. Plus **`-detmotor`** and a stochastic-`-vclamp` **V₀ dt-ladder** (1e-5→1.25e-6, seeds).
- **DIRSWING = CONVERGED FINITE STROKE (Outcome A).** ~98% of its work is done in the first 0.1 ms after ADP·Pi→ADP
  (95%-acquisition at 0.06 ms; post-acquisition remainder ~4–5%); cumulative W_DIR plateaus (~5.15 kT). It is a FIXED
  rotational spring (springs `swingParams[4]=−refDt` ⇒ dt cancels to refDt) — target-active, but a target that stays
  active is NOT a servo.
- **SERVO REFUTED (Outcome B).** Closed-cycle displacement challenge (out→hold→back→hold ×2, amp 4/8/12 nm): net
  DIRSWING work over the closed cycle is small, **≤0 (dissipative), dt-stable, NEVER net-positive** (−0.008/−0.038/−0.094
  kT @1e-5, essentially unchanged to 1.25e-6). No repeated net-positive energy injection.
- **Per-step rotation is a benign ∝dt discretization artifact (Outcome E).** Lever max/step 13.03→6.59→3.31→1.67 as dt
  halves (exactly ∝dt); the physical-time trajectory converges. Bounds the prior doc's "±30°/step thermal-off" head
  jitter (deterministic-constraint part ≤13°/step, dt-vanishing; rest was thermal). Estimator artifact (F) ruled out
  (robust rotation vectors + midpoint work). Deterministic run also REMOVES the head-jitter confound the disambiguation
  PART 4 flagged: clean NET channel work — DIR R=1.00/W⁻=0 (one-time conversion), F8 R=14.3 (reversible bond breathing,
  net≈0), F9 net-negative ⊥-maintenance, F10 net-positive plane reorientation.
- **Pose / single-motor force / stroke-work CONVERGE** (θ_J1 49.9°, |F8| 2.57 pN, cumW_DIR ~5.15 kT flat to <1% over 8×
  dt) — once the pre-stroke equilibration is held at constant PHYSICAL time (constant STEP-count gives a spurious ±8%
  non-monotone from the slow post-stroke settling mode — an equilibration-window artifact, not mechanics).
- **ENSEMBLE V₀ is NOT dt-converged (Outcome C rider).** f̄_available(v) zero-crossing: **17.8 (1e-5)** → noisy **~13±2**
  (11.8/15.3/12.4) at finer dt — a ~25–45% production-dt overestimate. Robust fixed-v read: at v=16 the carpet still
  DRIVES forward (+0.028 pN) at 1e-5 but is in NET DRAG (−0.01…−0.13 pN) at ALL finer dt. Decomposed: occupancy Nbound
  RISES as dt→0 (0.54→0.75) yet per-bound drive FALLS → the shift is the **cross-bridge sustained-force-under-sliding /
  duty dt-ceiling** (`dt-faithful-ceiling`, `gliding-reconvergence`), NOT the stroke (the static single-motor stroke
  converges). Recommended fix = the standing cross-bridge sub-step, NOT a DIRSWING change.
- **Verdict:** no persistent-servo behavior and no stroke-mechanics non-convergence ⇒ **do NOT replace DIRSWING.** The
  quantitative gliding ceiling remains dt-unconverged at production dt (localized to the cross-bridge duty clock).
  Audit-only; canonical model unchanged; `BoA-v1ref` untouched. Report: `docs/TIMESTEP_SERVO_AUDIT.md`; code
  `-servoaudit`/`-detmotor` in `GlidingHarness`; runs `RUN_LOGS/servo_ladder_eqconst.txt`, `RUN_LOGS/servo_full_1e-5.txt`,
  `RUN_LOGS/v0_ladder.txt`; `scripts/run_servo_v0_ladder.sh`.

### 2026-07-12 — CANONICAL STROKE DISAMBIGUATION: ONE power stroke (DIRSWING), NOT two; F9 is a FROZEN-90° ⊥-maintainer — and the PLATEAU_ORIGIN_AUDIT "F9-dominant stroke" was a 120°-vs-90° DIAGNOSTIC BUG (Outcome A + E)
Resolved a suspected inconsistency: the code audit said SPHEREHEAD freezes F9 at 90° with DIRSWING the stroke, but
PLATEAU_ORIGIN_AUDIT reconstructed a dominant nucleotide-switched 90°→120° F9 "stroke." Audited the LIVE production
kernel (`-strokeaudit`, default-off byte-identical: snapshots the exact pose `bondForces` uses, reads the actual
`bondData` it wrote, reconciles per channel).
- **LIVE-CONFIRMED (v=0 AND v=16, every step): `xbParams[9]=f9Frozen=1` ⇒ `restF9=90°` in ALL nucleotide states.**
  Recompute of the head torque with restF9=90 matches live `bondData[3..5]` to **0.0000 pN·nm**; restF9=120 is off by a
  fixed **52 pN·nm**. **F9 is nucleotide-INDEPENDENT — the ⊥-maintainer, NOT a stroke.**
- **DIRSWING is the SOLE nucleotide-triggered stroke:** its lever target switches **0°→60° exactly at ADP·Pi→ADP**
  (`swRest` 0→60 at the transition step, `swErr` then decays 88°→10° over ~0.1 ms = the lever swinging). J1 angular
  converter is OFF (`jointParams[3]=0`, replaced by DIRSWING). **⇒ ONE stroke, not two. Outcome B (two strokes) REFUTED.**
- **PART 4 work:** raw τ·ω confounded by head-jitter (both channels large), but DIRSWING work is **front-loaded**
  (pre+stroke 136 ≫ plateau 22, transition-active) vs F9 **duration-proportional** (plateau 112, ongoing constraint);
  the decisive discriminator is the target change (DIRSWING yes / F9 no).
- **OUTCOME A (one stroke) + OUTCOME E (my prior diagnostic bug).** `plateauBalance()` used `cocked?120:90` ignoring
  `f9Frozen` ⇒ inflated T9 62→ (true) **24 pN·nm**, F9 dev −35°→ (true) **−5.9°**. **FIXED** (reads f9Frozen; live d90=0).
  **The "F9-dominant stroke" claim is RETRACTED**; corrected, F8's counter-torque is **F9-⊥ (24) + AXLOCK (23)
  co-dominant**, neither a stroke. **PLATEAU_ORIGIN_AUDIT structural conclusions SURVIVE** (constraint-set, thermal-scale
  ~2.3 kT, frustrated stroke, AXLOCK load-bearing, the 3 fidelity levers) — only the F9 mislabel was wrong. PART 5
  two-stroke ablation NOT triggered (F9 not a stroke). **Audit-only; `BoA-v1ref` untouched.** Report:
  `docs/CANONICAL_STROKE_DISAMBIGUATION.md`; correction banner on `docs/PLATEAU_ORIGIN_AUDIT.md`; code `-strokeaudit` +
  `plateauBalance()` fix in `GlidingHarness`.

### 2026-07-12 — PLATEAU-ORIGIN AUDIT: the V₀-setting prestress is a CONSTRAINT-SET, thermal-scale, FRUSTRATED-F9-stroke residual (largely honest emergent mechanics; 3 named idealization levers)
Follow-up to EPISODE_KERNEL: what MAINTAINS the bound-ADP forward F8-strain plateau that sets V₀, and is it defensible?
Extended `-epkernel` with a **pose-derived plateau-band force/torque/energy balance** (`plateauBalance()`: re-computes
the exact canonical-stack torques — bondForces F8/F9/F10-AXLOCK, directedSwing, MotorJointSystem J1/J2, TailAnchor — from
the body pose; touches NO kernel ⇒ default-off **byte-identical**, PART-1 completeness identity unchanged). Grid v=0,16 ×
neck 40/60/80 × 4 seeds. **Framing correction carried:** m_f is survival-conditioned; no causal mechanics/kinetics split
re-asserted.
- **CONSTRAINT-SET, not stroke-set (decisive):** the whole balance RE-EMERGES across a **1.9× nominal neck-swing change**
  (F8 |F| 3.95/3.99/4.00, F9 torque 62/63/64, AXLOCK 23/23/23, dev9 −35/−36/−37, devAx 26/25/25 — all flat). Only Tsw
  (smallest term) and devJ1 (no restoring torque) track neck. Torque-level confirmation of the kernel's neck-angle
  disconnection.
- **Dominant torque = F9** (the nucleotide rest-angle switch 90°→120° = the modeled power stroke, T9≈62 pN·nm), balanced
  by F8-restoring (TH≈33) + **AXLOCK** (hF10≈23, 37% of counter-torque) + a near-complete swing (≈6). J1 angular converter
  is OFF (jointParams[3]=0, replaced by DIRSWING) ⇒ Tj1=0 (faithful, verified vs sc.jointParams).
- **The F9 stroke is FRUSTRATED:** the bound head sits ~85° (F8-anchoring + AXLOCK win), NOT the 120° target ⇒ the nominal
  reorientation is ~fully absorbed; residual = a SMALL forward strain (**0.82 pN axial of a 79%-transverse 3.95 pN**),
  storing only **~2.3 kT**. NOT a large artificial preload. d_relax≈0.7nm ≪ d_peak≈6nm because the free J1/J2 rotational
  hinges (angular springs off) let the lever/rod recoil + F8 holds the bound head → the stroke isn't held.
- **Verdict — primarily OUTCOME 1 (honest emergent mechanics):** dominated by a PHYSICAL mechanism (F9 stroke),
  thermal-scale, re-emergent ⇒ V₀≈16 is a fairly honest consequence of the modeled stroke; NO single idealized artifact,
  NO smoking gun. **3 named, licensed fidelity-preserving levers (NOT built):** (A) AXLOCK's lab-n̂=+Z axial-plane lock
  (gliding-assay idealization, co-sets forward directedness) → make it physical/compliant; (B) the dt-dependent fracMove
  F9/AXLOCK torques (∝1/dt, magnitude un-converged) → dt-converge (dt line); (C) the free J1/J2 hinges (lever-arm doesn't
  hold the stroke) → a stiff transmitting lever. Primary Vmax lever stays the catch-slip shape (VMAX_SENSITIVITY).
- **PART 3 (statistical debt closed):** paired per-seed xCatch ΔV₀ = **−8.65 [−10.14, −7.17]** (bootstrap; all 4 seeds
  −6.7…−10.6, dwarfs seed SD 1.80). **Audit/logging only; no constraint change; `BoA-v1ref` untouched.** Report:
  `docs/PLATEAU_ORIGIN_AUDIT.md`; code `plateauBalance()`+PLATROW in `GlidingHarness`; runs `RUN_LOGS/2026-07-12_plateau_grid.txt`.

### 2026-07-12 — EPISODE-KERNEL ACCOUNTING: V₀≈16 is a bound-state F8-STRAIN BALANCE carried by the force WAVEFORM, not d/τ, not lifetime; the nominal stroke is DECOUPLED from the effective d_eff
"Explain the ceiling, don't correlate with it." Built the full AGE-RESOLVED episode kernel (`-epkernel`, additive to the
`-vclamp`/`-prestroke` clamp; default-off **byte-identical**, completeness identity Σbins≡completed+censored idErr ≤
5e-15 across all runs ⇒ no force dropped): per attachment-age `a` → survival `S(a|v)`, age-conditioned mean glide force
`m_f(a,v)`, F8 tip−site strain, converter angle, per-state nucleotide fraction, catch-slip hazard; per episode → T,
ADP-lifetime, sign-reversal age/distance, I₊/I₋/net, effective actin-level working displacement d_peak/d_relax, bind &
stroke-onset snapshots. CPU deterministic, d2000 `-matbox 50`, v=0…20, 4 paired seeds.
- **RECONSTRUCTION GATE PASS:** `⟨I(v)⟩ = ∫S·m_f da` reproduces the measured per-episode impulse (0.1–2 %) and its zero;
  kernel V₀ 15.98 ≡ measured 15.92 ≡ force-f̄ zero 16.00.
- **NESTED-MODEL VERDICT: only Model 3 (measured kernel) survives.** Model 1 `d_eff/T̄` ≈ 8.9 and Model 2
  `2d_eff·E[T]/E[T²]` ≈ 9.2 both FAIL at the low-v anchor (they only "match" 16 if fed the v=16 self-consistent values —
  circular). Why: the **force waveform is a two-phase transient+plateau, NOT `k(d−vt)`** (a fast stroke spike absorbed in
  ~0.05 ms + a sustained bound-ADP plateau); the **dwell is not exponential and reshapes with v** (CV 0.96→0.75).
- **DECISIVE COUNTERFACTUAL — the WAVEFORM carries V₀, survival only SCALES:** freeze S at its v=0 shape, let only
  `m_f(a,v)` move ⇒ impulse zero stays **V₀=16.1**; freeze `m_f` at v=0, let only S move ⇒ impulse **never crosses zero**
  (survival shortening drops magnitude ~40 % but makes no zero). ⇒ V₀ is a **force/cross-bridge-strain balance**: the
  sustained ADP-plateau holds a net-forward F8 strain (~+0.5 pN at v=0) that the sliding-induced resistive strain erodes
  to zero at v≈16 (strain `mrel` tracks `m_f` one-for-one).
- **The nominal stroke is DECOUPLED from the effective d_eff (PART 3, Claude's hypothesis CONFIRMED):** d_peak ≈ 6 nm but
  **d_relax ≈ 0.7 nm** (compliant body absorbs ~85 % — the per-episode view of `STROKE_COMPLETION_STRAIN_TEST` PART 1); a
  **1.9× nominal neck-angle swing (40°→80°) moves d_eff ~2 % and V₀ < 10 %.** xCatch (2.5→1.0 nm) drops V₀ 16→7.8 with
  **d_eff unchanged**, by **steepening `m_f(a,v)`** (lower catch-force-sensitivity ⇒ resistive back-strained heads persist
  ⇒ survivor-conditioned force falls faster with v). ⇒ **xCatch is the dominant lever because it is the knob on the
  survivor-conditioned force waveform** — unifying `VMAX_SENSITIVITY_25C` (catch-slip shape dominant) with the waveform
  finding. **Logging only (PART 3 existing flags); no model change; `BoA-v1ref` untouched.** Report:
  `docs/EPISODE_KERNEL_ACCOUNTING.md`; code `-epkernel` in `GlidingHarness`; runs `RUN_LOGS/2026-07-12_epkernel_*.txt`.

### 2026-07-11 — STROKE-COMPLETION-vs-STRAIN TEST: load-dependent completion is the c·f̄ AMPLITUDE null, NOT a V₀ lever (measure-before-build → DON'T build)
Does strain-dependent stroke COMPLETION move the force–velocity ZERO (a real, fidelity-preserving V₀ lever) or only
scale AMPLITUDE (the c·f̄ null that worsens the density problem)? The one hypothesis surviving `PRESTROKE_DISTORTION`.
**Verdict: OUTCOME 2 — it does NOT move the zero; it mildly scales amplitude. Do not build** (nor the Option-4
converter re-architecture it would take to test a lever this shows is null).
- **PART 1 prerequisite (does the EXISTING stroke already stall under load?) — NO, it completes FULLY at every load.**
  New `-strokeload` (single-motor quasi-static, default-off byte-identical) + a read-only converter-completion
  accumulator in the `-vclamp` clamp (θ_J1=∠(û_lever,û_head), 60°=full; FVROW byte-identical). Canonical DIRSWING
  completion is **flat at ~100–108 % of 60° across v=0→20** (65.2°→60.0°, only ~8 % decline). Quasi-static: a static
  filament offset ±24 nm is **ABSORBED** by the compliant J1/J2/anchor springs (|s_ax|<0.08 nm, segFx≈0.05 pN) ⇒ the
  stroke force is **transient**, completion held ~98 % — so the load-dependence read MUST be the sliding clamp, not a
  static offset. ⇒ DIRSWING's "perfect axial advantage": no existing partial-completion to duplicate or strengthen.
- **PART 2 decisive cut (`-strokecomp E*`: θ_eff=θ_u+(θ_c−θ_u)·exp(−max(0,s_res)/E*), s_res=(tip−site)·f̂; default-off
  byte-identical) — the ZERO does NOT move (multi-seed).** f̄_available(v) grid, canonical vs E*=4/2/1, d2000 `-matbox
  50`. **3-seed V₀: canonical 15.1, E*=2 14.8 (Δ−0.2 n.s.), E*=1 13.8 (Δ−1.3, < 1 SD n.s.)** — the single-seed E*=1
  "11.9" was a **seed-0 low draw** (seeds 1,2: 14.1, 15.2). The **v=0** (no v-load) force is mildly cut −7/−11 %
  (amplitude present at zero velocity) and completion cut as a **parallel down-shift** (65°→48°, same shallow slope,
  NOT steepened) that barely moves f̄ ⇒ lever-completion is a **weak, loosely-coupled** force handle. Low-load in-spec
  check FAILS: every arm degrades the unloaded stroke without buying a robust V₀ shift.
- **Root cause:** the F8 axial strain the knob keys on is dominated by the **stroke's own tip-advance** (s_res>0 at
  v=0), so throttling completion is a **negative feedback on the productive stroke**, firing at all v ⇒ uniform
  amplitude, not zero-movement. The velocity-driven strain increment is tiny (`f8ax` −0.93→−1.43 nm, v0→20). ⇒ V₀≈16
  is a **STATIC over-drive** (stroke size / duty / rigid-clamp), matching `FORCE_VELOCITY_TEST`/`FORCE_BALANCE_CLOSURE`.
  `BoA-v1ref` untouched; production byte-identical. Report `docs/STROKE_COMPLETION_STRAIN_TEST.md`; raw
  `RUN_LOGS/2026-07-11_strokecomp_sweep.txt` + `_seeds.txt` + `strokecomp_analyze.py`.

### 2026-07-11 — PRE-STROKE DISTORTION ANALYSIS: no competence metric earns a geometry-gated commitment step (BAIL — honest negative)
Does a pre-stroke strain/competence metric justify a mechanically-gated stroke-commitment ("bailout") step — i.e. does
pre-stroke distortion **(a) rise with sliding velocity AND (b) predict unproductive episodes at FIXED velocity**, or
would a gate be an efficiency-reducer dressed as mechanism? **Verdict: BAIL** — the gate is NOT the missing mechanism.
- **STEP 0 — RE-RUN required.** Existing head-swing logs have the episode *outcome* only as an **aggregated mean**
  (`Iattach`) + commanded geometry (`-swingdiag`); **no** pre-stroke mechanical variables and **no per-episode pairing**
  ⇒ Q2 impossible from existing data. New `-prestroke` (additive, default-off **byte-identical**, FVROW verified): at
  each episode's first ADP·Pi→ADP transition capture the PRIMARY sign-only **virtual axial work** `whead=wsw+wf9`
  (`w=(τ̂×û_head)·(−x̂)·angle`, >0 productive; the **F9 head-reorientation is the productive channel**, stroke ∝
  HEAD_LEN — a first cut on the `directedSwing` lever channel was uniformly-signed, the 87°-artifact trap, avoided) +
  ΔU_stroke, F8 axial strain, J1 misalignment; paired with the episode net impulse. DIRSWING arm, v={0,2,4,8,12,16,20}
  ×seeds0–3, 12k steps, d2000 `-matbox 50`. **15 586 episodes**; FVROW reproduces V₀≈16 (setup validated).
- **THREE-QUESTION VERDICT (no metric passes all three as *mechanism*).** **Q1 (worsen with v): NO** for the virtual
  work — `wf9` incompetent-frac FLAT 0.020→0.029 (v0→20) ⇒ a **static filter, not a saturation mechanism** (can't lower
  V₀). **Q2 (predict at fixed v): YES (weak–moderate)** — within-v r(`wf9`,imp)≈0.30–0.36 (real, n≈2200), r(`f8ax`)≈0.28,
  r(`whead`)≈0.10. **Q3 (v0 population): YES** — frac(whead>0)=0.52, frac(wf9-productive)=0.98 (not an axis artifact).
  The only metric that **grows with v is F8 axial strain** (−0.93→−1.43 nm) — but that is cross-bridge **LOAD, not
  assembly distortion**; a gate on it duplicates the existing force-dependent catch-slip release + 12 pN break cap
  (efficiency-reducer, not new mechanism) and targets the wrong tail.
- **VARIANT-B RECONCILIATION (decisive).** Per-episode impulse ceiling is a **UNIFORM decline via collapse of the
  PRODUCTIVE tail** — p90 1.73→0.51 (Δ−1.22), p50 +0.12→−0.12 (zero @v≈16=V₀), **p10 only −0.34→−0.59 (Δ−0.25)**; ~⅘ of
  the change is in the upper half. **No emergent gate-able incompetent sub-population.** A *pre-stroke* gate cannot
  rescue productive episodes losing steam mid-attachment. Matches `FORCE_BALANCE_CLOSURE.md` PART 2 (impulse crosses
  zero at V₀, intrinsic to one attachment, not recruitment-masked).
- **⇒ Geometric commitment gating is NOT the missing mechanism; the ~1.4× over-Vmax is the STATIC over-drive
  (stroke/duty/rigid-clamp geometry), not a velocity-gated commitment step.** Three-arm follow-up + soft-`k_eff(ΔU/E*)`
  form left UNBUILT (ΔU_stroke flat with v, r≈0.13). No gate built. `BoA-v1ref` untouched. Report:
  `docs/PRESTROKE_DISTORTION_ANALYSIS.md`; raw `RUN_LOGS/2026-07-11_prestroke_grid.txt` + `prestroke_analyze.py`.

### 2026-07-11 — HEAD-FRAME SWING vs DIRSWING (force–velocity): H2 static penalty, NOT the fidelity path — the deferred-Stage-2 alternative FAILS
The accepted head-frame-swing experiment (does a less-idealized stroke lose **axial** productivity as the filament
slides ⇒ lower V₀ *through more realistic geometry*?). Single factor: swap `directedSwing` (swing ref f̂ = filament
axis) → the pre-existing `directedSwingHeadFrame` (swing ref ŷ_head×û_head = head frame), canonical stack otherwise
fixed. New flags `-headswing` (selector) + `-swingdiag` (additive commanded-geometry diagnostics: q_DIR, q_HF,
off-axis ∠(p̂_HF,f̂), transverse impulse); default-off **byte-identical** (git-stash pre/post FVROW char-identical).
`-vclamp` clamp force–velocity, CPU deterministic, `-matbox 50` d2000, VARIANT A full-binding, 15k steps, paired CRN
seeds 0–3, v-grid {0,2,4,8,12,16,20} refined ≤1 µm/s around each zero (104 runs).
- **STEP-0 audit (`docs/HEADFRAME_SWING_CODE_AUDIT.md`):** SINGLE FACTOR (only the −sinθ swing reference differs;
  θ/stiffness/torque-form/F8/F9/F10/AXLOCK/release/binding/cycle byte-identical). **AXLOCK does NOT override the swing
  target** — F9 (head.uVec), AXLOCK/F10 (head.yVec), swing (lever) are 3 distinct torque channels; coupling is indirect
  (through the head pose, across steps) ⇒ H3 NOT foreordained. Both targets live-recomputed (not frozen).
- **RESULT — H2 (static penalty), NOT H1, NOT H3.** V₀,DIR=16.07 [15.8,17.1], V₀,HF=11.70 [11.4,12.5],
  **ΔV₀=−4.35 [−4.95,−3.88]** (bootstrap 5000, paired). But the three H1 velocity-dependence signatures ALL fail:
  off-axis ∠(p̂_HF,f̂) is **FLAT ~87°** across all v (86.8°@0→87.6°@20, NOT +≥5°); q_HF slope ≈0 (axialProd ~+0.04,
  chronically non-axial); **D(v) POSITIVE at every v** (+0.05…+0.13, 11/12 excl 0 — head-frame is *less* velocity-
  sensitive, opposite of H1). Static v=0 deficit −34% (f̄ 0.435 vs 0.655). q_DIR≡−1.000 exact (DIRSWING commands a
  perfectly axial ref at all v). Episodes 555–697/run (≥100), right-censored <0.6%.
- **Mechanism (static, flagged):** the canonical F9(90°)+AXLOCK locks do NOT settle the head into û_head=±ẑ ⇒
  ŷ_head×û_head is ≈⊥ f̂ (~87° off) at ALL velocities (same in BOTH modes). The recast's "locked ⇒ ŷ_head×û_head=f̂"
  ideal is not realized ⇒ the head-frame swing sweeps largely off-glide; retains ~65% of DIRSWING drive via cosθ·û_head.
- **VERDICT:** head-frame is a **worse** stroke (less efficient, velocity-independently), not a ceiling mechanism and
  not fidelity-increasing — the OPPOSITE of the Stage-2-deferred goal. Its lower V₀ is a pure static-offset artifact,
  no velocity feedback. **No follow-on licensed** (H1 rejected ⇒ no free-glide validation; H3 rejected ⇒ no AXLOCK-
  relax experiment). If ever revisited, fix the STATIC v=0 geometry first (why locked û_head≠±ẑ), not force–velocity.
  Report `docs/HEADFRAME_SWING_VELOCITY_TEST.md`; runs `RUN_LOGS/swing_*`, analysis `RUN_LOGS/2026-07-11_headframe_swing_analysis.txt`.
  **⇒ The principled fidelity path pointed to by the Stage-2 deferral does NOT reach the band; return to Stage-2 fitted
  calibration is now the remaining lever (explicit PI-chosen provenance tradeoff).**

### [Vmax→25°C calibration] Stage 2 — DEFERRED BY CHOICE (not incomplete): decline to hit the band by falsifying measured rates

**Decision:** We can reach the biological gliding band (V₀ → ~4–8 µm/s, target ~4.2) with the Stage-1-shortlisted knobs — but we are choosing NOT to, because every path does so by moving experimentally-MEASURED parameters off their measured values:
- **ADP-release ↓ ~3× (1000→300/s) → V₀≈8** — clean mechanically (touches no PINNED quantity) but 300/s is **sub-skeletal** (skeletal ~500–1000/s) ⇒ drifts the motor's identity toward smooth/NMII kinetics.
- **xCatch/αCatch ↓** (stronger movers) — overrides **Guo&Guilford-measured catch-bond constants**, reshaping the measured lifetime-vs-load curve.

**Why deferred rather than done:** Stage 1's real finding is that the current motor architecture, with all rates at their measured skeletal 25°C values, has an **intrinsic ceiling ABOVE the biological gliding band**. Forcing V₀ into the band by tuning the measured constants would *hide* that finding behind a fitted number. We bank the finding instead: **we know how to lower V₀ and are declining the version that falsifies measured kinetics.** Candidate sets (A: ADP-only, B: catch-shape-only, C: split) are specified and buildable if we ever want the fitted version, but not pursued now.

**The principled alternative — next experiment:** pursue the band by making the motor MORE physically faithful, not less. The accepted **head-frame-swing vs DIRSWING** experiment tests whether a less-idealized stroke (head-frame, not continuously re-aimed at the filament each step) loses axial productivity as the filament slides (H1). If H1 holds, V₀ falls **through more realistic geometry** — the opposite provenance sign from Stage-2 tuning (which buys speed by *degrading* fidelity; head-swing would buy it by *increasing* fidelity). "Relax the swing efficiency" and "make the stroke more biological" are the same change if head-frame is the more realistic stroke. **⇒ Run the head-swing experiment next; return to Stage 2 only if head-swing does NOT reach the band and a fitted calibration becomes necessary (and then as an explicit, PI-chosen provenance tradeoff, documented as such).**

**Open target parameter (unchanged, still to pin if/when calibration resumes):** ionic strength — ~4.2 µm/s is the ~50 mM number; physiological ~150 mM is lower.

**Status:** Stage 0 (audit) + Stage 1 (sensitivity) done/committed. Stage 2 deferred-by-choice. Next: head-frame-swing experiment (its own accepted proposal).

# 2026-07-11 — Vmax calibration STAGE 1 (clamp V₀ sensitivity screen): V₀ is RELEASE-kinetics-limited, NOT stroke-limited
One-at-a-time clamp-V₀ sensitivity screen over the eligible (non-PINNED) knobs (per the STEP-0 audit), to rank which
move the ceiling. `-vclamp` f̄_available, CPU, `-matbox 50`, d1000 (V₀ density-indep, faster), adaptive grid
{0,4,8,12,16,20}, seed 0 (+seed-1 confirm). Added measurement-only default-off byte-identical override flags
`-adprate/-pirate/-atpdetrate/-koff/-acatch/-aslip/-xslip` (+ reused `-neckangle/-myospring/-xcatch`); baseline
byte-identical (additive gated branches only). Report `docs/VMAX_SENSITIVITY_25C.md`; raw
`RUN_LOGS/2026-07-11_vmax_sens_seed{0,1}.txt`. `BoA-v1ref` untouched.
**2-seed pooled log-sensitivities S=Δln V₀/Δln p (baseline V₀ 12.4/15.2 d1000, seed scatter significant):** xCatch
**0.69** ≫ αCatch **0.41** [robust top] » mid-cluster ADP-release 0.28 ≈ Pi-release 0.25 ≈ **neck-angle/stroke 0.20**
≈ myoSpring 0.17 ≈ ATP-detach 0.16 [seed-scattered, not cleanly ordered] » nulls αSlip 0.02 ≈ xSlip −0.02 ≈ **kOff
0.00**. **HEADLINES (survive 2 seeds):** (1) **catch-slip SHAPE (xCatch, αCatch) DOMINATES V₀** (robust #1/#2) ⇒ the
ceiling is **release-kinetics-limited**; **stroke size (neck-angle) is only mid-cluster (0.20), NOT the dominant
lever** — refutes naïve V≈d/τ_on "stroke is the lever". (2) **kOff has ZERO V₀ effect (0.00 both seeds)** — the clean
amplitude null (scales catch+slip ⇒ moves detach rate not the zero). (3) the **myoSpring CONTROL is NOT null (0.17
both)** — stiffness couples mildly into the zero (3-body geom + load-dependent release, audit H5). **Cleanest lever to
lower V₀ into ~4–8: slow ADP release ~3× (1000→300/s ⇒ V₀≈8 both seeds, stroke PINNED-untouched)** — maps to d/τ_on,
touches no PINNED quantity, though drifts sub-skeletal toward NMII; xCatch/αCatch move V₀ more but are
Guo&Guilford-measured catch-bond constants (provenance-expensive). **Neck-angle demoted: mid-cluster AND stroke-bound**
(5–8 nm ⇒ neck ~43–70°). **VERDICT: release dynamics dominate V₀, not stroke ⇒ Stage 2 builds candidate sets on the
release kinetics (slower ADP clock ± re-shaped catch), NOT stroke. Caveat: screen-precision; mid-cluster order needs
Stage-2 bootstrap.**

# 2026-07-11 — Vmax→25 °C calibration STEP 0 (READ-ONLY provenance audit): rates are a multi-temperature SKELETAL stitch, NOT single-T ⇒ temperature is NOT the lever (H1 near-dead)
Read-only gating audit before any temperature calibration — two frozen provenance tables. NO code/runs/`-tempC`.
Reports: `docs/MOTOR_PARAMETER_PROVENANCE_25C.md` (Table 2), `docs/GLIDING_TARGET_25C.md` (Table 1). `BoA-v1ref`
reference-only.
**TABLE 2 (model rates):** the live gliding motor (SPHEREHEAD+AXLOCK+DIRSWING+XB_IMPLICIT2+LYMN_TAYLOR default) is
**skeletal throughout**, at a MIX of ≥4 inherited temperatures: kT EXPLICIT **25 °C** (`Env.java:25`, PINNED); the
nucleotide cycle = **Howard 2001 Table 14-2** "shaded path" (rabbit fast skeletal) which is itself a **compilation
STITCH** — ATP-side (atpOn 2e4, hydrolysis 100) from Lymn & Taylor 1971 at **20 °C** + ADP release (onADP 1e3, the
τ_on/velocity-limiter) from Siemankowski & White 1985 at **~15 °C**; catch-slip (kOff 100, α 0.92/0.08, xCatch 2.5nm,
xSlip 0.4nm) = **Guo & Guilford 2006 = RAT skeletal HMM, "room temperature" (no numeric °C)**. ⚠️ Corrections: Env's
"Stam 2015" catch-slip cite = Stam 2015 *Biophys J* (NOT PNAS), a **simulation** (consumer, not the experimental
source = Guo&Guilford); the `NMII_BIOLOGY.md` NMII intent was **NEVER parameterized** into the gliding rates (code is
skeletal). Unitary force ~5 pN (Finer'94, PINNED) + working stroke ~5–8 nm (PINNED) = validation metrics, never
knobs. **H1 KEY:** the velocity-limiting ADP release (1e3/s) is **ALREADY ~25 °C-equivalent** (Siemankowski 15 °C
≥500/s × Q₁₀~2 → ~1000/s); round textbook values not a precise single-T set; and **NO per-transition Q₁₀ is cleanly
available** (only emergent velocity Q₁₀≈2.38 [JAP 2005, fast skel 10–25 °C] + overall ATPase Ea≈66 kJ/mol) ⇒ a
rigorous per-transition warm-up can't even be built without primary pulls (Nyitrai 2006 ADP-release T-dep paywalled),
and the one step that sets velocity needs no warm-up.
**TABLE 1 (target):** condition-matched 25 °C skeletal gliding Vmax = **≈4.2 µm/s** (Rossi 2012, fast rat skel HMM,
25 °C, 2 mM ATP, ~50 mM ionic — the one exact anchor; Kron&Spudich 3–4, Anson ~4 bracket it); spread **3–5 µm/s**.
Dominant hidden variable = IONIC STRENGTH (clean numbers at ~50 mM; V falls toward physiological 150 mM per Homsher
1992 ⇒ <4 there). The provisional "1.5–4" band's LOW end unsupported for skeletal 25 °C/low-salt; ~4 is the anchor.
The model's directed glide already sits in 3–5 µm/s (BIO_BOUNDCOUNT/FORCE_BALANCE) ⇒ little "missing Vmax."
**GATE VERDICT: rates are a mixed-temperature SKELETAL stitch, not one T ⇒ "apply one coherent 25 °C shift" is the
WRONG frame; temperature is near-certainly NOT the lever (H1 dead on arrival — ADP release already ~25 °C, no
per-transition Q₁₀, target already met). DO NOT run a temperature-scaling calibration; if a velocity change is
needed the lever is the mechanochemical operating point (stroke/duty/force-balance), not °C.** Sensitivity screen /
candidate sets / free-glide confirmation are moot under this verdict (were deferred pending this gate).

# 2026-07-11 — FORCE_BALANCE_CLOSURE: the clamp f̄(v) predicts free gliding to a factor ~1.4 (rigid clamp OVER-predicts ~40% on the arbiter) ⇒ model coherent, calibration is the frame (operative ceiling ~11–12); V₀≈16 density-independent; B-impulse crosses at V₀
Gated the "just calibrate V₀" leap by CLOSING the force balance N·f̄(v*)=F_drag(v*) against observed free-glide
speeds, + fixing the P_bound>1 denominator, + locating the Variant-B impulse zero, + a bootstrapped V₀ CI.
Measurement/analysis only; additive/default-off byte-identical (`-vdrag` + denominator fix + impulse SEM; default
path unchanged; `BoA-v1ref` untouched).
**PART 1 — balance CLOSES.** `-vdrag` (bare-filament drag, no motors): ζ_eff=0.401 pN/(µm/s), measured≡analytic
1e6·Σγ_par (overdamped v=F/γ exact, dt cancels; aeta=0.1 Pa·s ⇒ drag NOT anomalously low). Solving
avgBound(ρ)·f̄_bound(v*)=ζ_eff·v* with the clamp per-BOUND force curve: v*_pred≈5.2(d2000)/8.2(d4000).
**Arbiter-consistent (CPU clamp vs CPU free) d2000: observed velFitX 3.65±0.60 (3 seeds 3.49/3.01/4.45) ⇒
pred/obs ≈ 1.4 (range 1.2–1.7) — the rigid clamp OVER-predicts by ~40%.** (Vs GPU-free 4.40 the ratio is only
1.18, but that MIXES IN the separate ~20% CPU↔GPU gliding basin difference — the clean same-runner comparison is
~1.4; d4000 uses GPU-free 6.70 ⇒ ratio 1.22 is a LOWER bound, CPU would be ~1.4–1.5.) Case = "predicted >
observed": the clamp reproduces MAGNITUDE + DENSITY-SCALING (same ~1.4 ballpark both densities), model broadly
self-consistent, BUT the rigid-clamp idealization overstates the free-glide drive by ~1.4× (flexing/rotating/
wandering free filament ~40% less efficient than the rigid clamp) ⇒ **operative free-glide ceiling ~V₀/1.4 ≈
11–12, NOT the clamp's 16.** NOT a double-count/N-mismatch/order-of-mag miss. Observed grid (GPU): d1000
2.24/2.68, d2000 4.40/4.62, d4000 6.70/9.33, d8000 9.19/18.4; CPU d2000 velFitX 3.65±0.60 (arbiter, ~20% below
GPU = the basin diff).
**PART 2 — B-impulse crosses at V₀.** Primary = net impulse per COMPLETE episode ⟨I_ep⟩=∫f_g dt (not conditional
force). v=12→20: +0.163,+0.156,+0.056,+0.026,+0.022 pN·ms ⇒ crosses zero ~16–18, COINCIDENT with V₀≈16 ⇒ the
ceiling is intrinsic to ONE attachment, NOT recruitment-masked (attachment-path masking REFUTED at the impulse
level; individual seeds already negative by v=16).
**PART 3 — denominator fixed.** N_available=N_reach∪N_bound (bound⊆available) ⇒ occupancy renamed
`bound_to_available`=0.83≤1 (was the impossible 1.01); does not move the force zero.
**PART 4 — V₀ bootstrapped.** 4 seeds v=13/14/15: **V₀=15.95[15.0,20.3](d2000)/16.08[15.1,18.4](d4000)** —
density-INDEPENDENT ≈16 (refines the single-seed 14; seed0 was low). Crossing **slopes MATCH −0.034/−0.037** ⇒
the earlier ~3× single-seed slope discrepancy was SAMPLING noise, no hidden density effect.
**VERDICT: the clamp f̄(v) predicts free gliding to a factor ~1.4 (same order + density-scaling) ⇒ model broadly
self-consistent, calibration is the right frame; BUT the rigid clamp OVER-predicts by ~40% (flexure/rotation the
clamp omits) ⇒ operative ceiling ~11–12 (≈1.4× biological Vmax ~8), not the clamp's 16. The ~40% rigid-vs-free
geometric gap is the residual to chase, not a missing collective effect or a new state.** Integrity note: my first
pass reported "~20%" using GPU-free; the arbiter-consistent (CPU/CPU) gap is ~40% — the ~20% conflated the CPU↔GPU
basin difference with the geometry gap. Report `docs/FORCE_BALANCE_CLOSURE.md`; raw
`RUN_LOGS/2026-07-11_freeglide_obs.txt`, `_fvB_impulse.txt`, `_fvA_V0fine.txt`. New: `GlidingHarness` `-vdrag`
(runDragCal) + N_available denominator fix + per-episode impulse SEM (all default-off byte-identical).

# 2026-07-10 — FORCE_VELOCITY_TEST: velocity-clamp reveals the intrinsic ceiling EXISTS at V₀≈14 µm/s (density-independent) ⇒ outcome 2 (calibration), NOT a missing weak state
"Measure before build." Velocity-CLAMP the gliding filament (`-vclamp <v>`, `GlidingHarness.runForceVelocity`,
CPU) — hold it rigid+straight, thermal off, advance only its COM at −v·dt (a kinematic velocity SOURCE that does
NOT respond to motor force). Because the clamped filament ignores its forceSum, the carpet motors couple only to
the prescribed trajectory ⇒ mutually INDEPENDENT ⇒ the whole carpet = a bank of replicated single-motor episodes
(GPT efficient design). Measured `f̄_available(v)` (mean glide force per AVAILABLE motor, unbound=0), sweep
v∈[−12,+12] (extended to +18), d2000+d4000. **Additive/flag-gated, default-off byte-identical** (new fields +
dispatch branch + one method; no shared kernel, no default line touched; default d2000 grid velFitX 4.07/avgB 4.56
reproduces baseline; `BoA-v1ref` untouched).
**PART 1 (Variant A, full binder):** f̄_available is a clean monotone force–velocity curve — +3.0 pN (v=−12) →
+0.10 (v=+12) → **crosses ZERO at V₀≈14 µm/s** (d2000 14.3 / d4000 14.8 — **density-INDEPENDENT to ~3%**) → negative
(resistive) beyond. So **the intrinsic force–velocity ceiling EXISTS** — it just sits ~1.8× the biological Vmax
(~8) and JUST ABOVE the ±12 window (why free-gliding v*<V₀ climbed with N toward the fixed V₀ and never looked
saturated). P_bound stays high (~0.95–1.0) at all v ⇒ decline is per-head FORCE (⟨I_attach⟩ 3.82→0.05 pN·ms,
lifetime 1.32→0.52 ms), not engagement; J_attach rises (760→1874) refilling the pool.
**PART 2 (x_bind):** the axial mismatch at attach `x_bind = s_head−s_site` is a **delta at ~0 (sd≈0) INDEPENDENT
of v** (bindArc≡head projection ⇒ 0 by construction; the ±0.12 nm mean = the one-step read slip v·dt). The
strain-erasing binder + committed no-pre-stroke-off-ramp cycle (cycleLymnTaylor: onPi=1e4/s fixed, only the
POST-stroke ADP→NONE is load-gated) are REAL — but they only set V₀ HIGH, they do NOT abolish the ceiling.
**PART 3 (Variant B, `-fvepisode`, recruitment removed):** one attachment episode/motor, no rebinding — f̄_bound
STAYS POSITIVE to +12 (pooled +0.42±0.17 @+12, +0.72 @+6; K-robust @300/700/1500), even HIGHER than A (fresh
isolated heads vs A's co-bound tug-of-war crowd) ⇒ **the ceiling is INTRINSIC, NOT recruitment-masked.**
**VERDICT — outcome 2: architecture SOUND (real density-independent ceiling at V₀≈14), V₀ mis-calibrated ~1.8×
high ⇒ CALIBRATION (stroke size / rates / stiffness), NOT a new state. jba's "no ceiling ⇒ build a single weak /
pre-stroke-strain-gate state" is REFUTED — the ceiling exists and B does not uncover a masked one. DO NOT build.**
(Method notes: compact clamp bed x±3/y±0.1 µm — thin-y faithful for per-available since only the ~50 nm capture
band binds; single-seed B was noise near 0, powered pool is +0.42. All on the deterministic CPU runner = the
basin arbiter; the +14/+15 sign change is resolved, not float noise.) Report `docs/FORCE_VELOCITY_TEST.md`; raw
`RUN_LOGS/2026-07-10_fvtest.txt` (A ±12), `_fvA_highv.txt` (locates V₀), `_fvB_clean.txt` (B pooled + K-robust).
New: `GlidingHarness` `-vclamp`/`-fvepisode` + `runForceVelocity`; `scripts/run_fvtest.sh`, `scripts/run_fvB_clean.sh`.

# 2026-07-10 — BIO_BOUNDCOUNT_TEST: at the BIOLOGICAL bound-head count (⟨N_b⟩≈1.5) the model GLIDES directionally + density-independent — it was OVER-BOUND (outcome a)
Reframed the ceiling question around a real biological number: motility assays run at ⟨N_b⟩≈2 (frequent full
detachment, duty ~0.02–0.05, velocity FLAT across 1500–2500 µm⁻²); the canonical model is OVER-BOUND ~10× there
(baseline avgBound ~4.75 @ d2000). Test: tune `-glidekon` so ⟨N_b⟩ lands at the biological ~1.5, confine the
filament to the lawn with `-matbox 50` (so a momentarily-detached filament stays in reach — the chamber is
LOAD-BEARING here, it makes "stall" vs "drift-out-of-reach" separable), and read DIRECTED motion (netX/velFitX),
NOT instSteady (Brownian-inflated at low duty). **No code changed** (reuses `-glidekon`/`-matbox`/`-grid`, all
default-off byte-identical; `BoA-v1ref` untouched). **STEP 1** (single-seed 15k) — avgBound∈[1,2] reachable at every
density; operating kOn ∝ 1/density to hold ⟨N_b⟩≈1.5: d1500→7e5, d2000→5e5(1.46), d2500→4e5(1.46). **STEP 2**
(3-seed 30k at the operating points) — **VERDICT: OUTCOME (a), OVER-BOUND.** At ⟨N_b⟩≈1.3–1.6, net-directed
**netX = −1.66 / −1.90 / −1.92 µm/s** (d1500/d2000/d2500, all 9 seeds directed −x, none collapses toward 0) and
**velFitX = 1.28 / 1.64 / 1.32** — **density-FLAT** (the ~2× density climb the crossover sweep saw at *fixed* kOn is
GONE once the head count is held fixed = saturation). netX/instSteady≈0.29 ⇒ ~29% directed / ~71% Brownian jitter,
the biological lightly-bound picture; continuity P(≥1)=1−e^−1.5≈0.78 (~22% fully detached), duty order 0.02–0.05.
The filament GLIDES at the biological head count — **kOn (operating point) is the fix for a density-independent
glide; NO steric co-occupancy cap / displacement clutch is required to reach it.** Outcome (b) "can't-glide-sparse /
per-attachment-advance defect" REFUTED (sparse binding → continuous directed transport). Caveat: *absolute* speed
still rises with head count (velFitX 1.6 @⟨N_b⟩1.5 vs 4.6 @over-bound 4.75) ⇒ the model doesn't saturate in
AMPLITUDE — Vmax calibration is a separate, non-blocking matter; the saturation this test establishes is
density-independence at FIXED head count. CPU arbiter (d2000/kOn5e5/seed0, 15k) confirms it's not a GPU float
artifact: CPU velFitX 2.453 / netX −2.185 / avgB 1.487 ≡ GPU 2.447 / −2.187 / 1.461 (<2%, no basin split — the
chamber closed the out-of-plane fragility). Report:
`docs/BIO_BOUNDCOUNT_TEST.md`; raw `RUN_LOGS/2026-07-10_bio_boundcount_step{1,2}.txt`.

# 2026-07-10 — GLIDEKON_CROSSOVER: restoring a finite binding rate does NOT saturate the velocity–density curve — it only SCALES (ceiling is STRUCTURAL)
Turned the one lever never turned (`BINDING_RATE_SURVEY`: canonical bind is DETERMINISTIC ⇒ effective kOn=∞ ⇒
permanent supply-limitation). Re-routed the canonical gliding bind to a finite-kOn binder and tested the timescale
crossover. **STEP 1** — new `-glidekon <k>` (`GlidingHarness`, CPU + GPU): routes the canonical `else` bind branch
from `bindNearest` to **`BindingDetectionSystem.bindRateGated`** = `bindRate` VERBATIM (pBind=1−exp(−kOn·Δl·dt),
kOn=kinParams[14], "BRAT" draw) + the baseline's `-nobind`/ADP·Pi guards so high-kOn reproduces the deterministic
set; formulation A (headPrev≡head, exact at dt=1e-5 ⇒ mot.head passed twice; V2OneX proves same-buffer-twice lowers).
Default-off ⇒ byte-identical (only an `else if` added). `bindRate`+V2OneXHarness byte-untouched. **Faithfulness** (d4000
10k GPU): `-glidekon 1e8` avgBsteady 10.61≈baseline 10.49, dwell 0.628ms, velFitX 6.07 within single-seed spread ✓.
**STEP 2** kOn* ≈ (1/τ_on)/Δl ≈ 1667/0.012µm ≈ 1.4e5; ladder {1.5e6,1.5e5,1.5e4}+baseline (estimate ~5× low but the
100×-span bracketed the crossover). **STEP 3** (2 density × 4 kOn, GPU 15k, `-matbox 50`): the decisive ratio
velFitX(d8000)/velFitX(d2000) — baseline(det) **1.85**, kOn 1.5e6 **2.08**, 1.5e5 **2.16**, 1.5e4 **1.10**. **NO
CROSSOVER:** every ENGAGED kOn climbs ~2× (no flatten); the 1.10 is COLLAPSE (avgB 0.1–0.26 unbound, velFitX ~0.7 —
both-near-zero trivial flatten, not the engaged ~d/τ_on plateau). velFitX+avgB fall TOGETHER as kOn drops (shrinking-pool
fingerprint, identical to coltol); dwell flat ~0.6ms (kOn touches capture not release); occupancy-½ (~1.5e6) is on the
ladder and still climbs 2.08×. The refill clock IS turnable (avgB 4.75→0.11, 5.6× starvation) but turning it collapses
the pool, never saturates velocity. **⇒ timescale hypothesis REFUTED on the clean rate-side test; the ceiling is
STRUCTURAL — bound-head POPULATION (steric co-occupancy / displacement clutch), matching DETACHMENT_CEILING_CODEREAD +
AZIMUTHAL_FALLOFF.** GPU-trust: 5.6× avgB suppression across both densities is kinetic starvation no basin-flip fakes;
CPU arbiter task-scoped to "if crossover appears" (it didn't). NEXT: build the steric co-occupancy cap / displacement
clutch — test whether IT installs the density-independent ceiling no kinetic/geometric/rate lever could. New:
`bindRateGated`, `-glidekon`, `scripts/run_glidekon_crossover.sh`. Report `docs/GLIDEKON_CROSSOVER.md`; raw
`RUN_LOGS/2026-07-10_glidekon_crossover.txt`. `BoA-v1ref` untouched; production byte-unchanged.

# 2026-07-10 — BINDING_RATE_SURVEY: the canonical gliding bind is DETERMINISTIC (effective kOn = ∞) — no refill clock to turn
READ-ONLY code survey settling why every eligibility-gate sweep (coltol / azimuthal / recruit-shed) only SCALED and
none crossed into release-limited saturation. **Verdict: on the canonical `-gpu -full -grid` path a head binds
DETERMINISTICALLY the step it becomes eligible — effective per-encounter kOn is INFINITE.** Canonical binder =
`BindingDetectionSystem.bindNearest` (dispatched at `GlidingHarness.java:745`, the `else` after CANONICAL/AZ_FALLOFF/
AZ_GATE all default-false); accept line `BindingDetectionSystem.java:394` `if (bestSeg>=0){ boundSeg.set(m,bestSeg);
bindArc.set(m,bestArc);}` — nearest reachable segment, **no RNG, no `u<kOn·dt`**. The historical finite-kOn binder
`bindRate` (`:818`, `pBind=1−exp(−kOn·Δl·dt)`, reads `kinParams[14]=kOn`, default 0) EXISTS and is wired but is
ORPHANED — only `V2OneXHarness -ratesearch`; `bindCanonicalTwoPoint` (`:586`) carries a kOn gate too but only under
`-canonical` (off). `bindKinetics` binding is also deterministic (only its RELEASE draws). Refractory
`kinParams[10]=MYO_REBIND_TIME=1e-5 s` = ceil(/dt) ≈ **1 step (0.01–0.1 ms) ≪ τ_on ~0.6 ms** ⇒ negligible. The ONLY
real refill clock is the **ADP·Pi recovery gate ~10 ms** (`nucParams[2]=100/s`; welded on for Lymn–Taylor via
`GlidingHarness.java:310`→`kinParams[20]=1`, enforced `BindingDetectionSystem.java:374`) — but it gates RE-binding of
SPENT heads (out-of-reach in the −x glide wake, `DENSE_DUTY_GAP`), NOT the deterministic capture of the fresh primed
pool the sweeps ride. ⇒ **no tunable per-site refill clock exists on the canonical path**; eligibility gates can only
change pool SIZE, never per-encounter RATE — the timescale/regime hypothesis was never testable with them.
**Localization to restore a finite swept kOn (scope only):** re-route the canonical `else` branch to `bindRate` behind
a new `-glidekon <k>` (kOn>0; passes `mot.headPrev`; default off ⇒ byte-identical) — the historical hook, already
validated — or a smaller single-site wang-hash gate at accept `:394` (salt-pattern reused from `bindNearestFalloff`).
Report: `docs/BINDING_RATE_SURVEY.md`. READ-ONLY; no edits/runs; `BoA-v1ref` untouched.

# 2026-07-10 — COLTOL_REGIME_SWEEP: shrinking the capture radius does NOT cross into release-limited saturation
Tested jba's timescale/regime intuition — does shrinking coltol (the geometric REFILL lever) slow refill until
refill-time ≳ τ_on, flipping the dense bed from supply-limited (site-occupancy ~0.85) to release-limited, where
velFitX saturates at ≈ d/τ_on (density-independent)? **PART 0** — built `-matbox <nm>`, a MAT-SIZED reach-preserving
chamber (new flag, wraps `ContainmentSystem` over the filament; z half-width 50 nm LOOSE, y-walls at the mat extent
±bYhalf, x free; default-off byte-identical) to ISOLATE the shrinking-coltol out-of-plane disengagement confound
(`GLIDING_OUTOFPLANE...`) WITHOUT pinning the plane. Isolation gate PASS: `-matbox 50` vs unconfined ENGAGED
(coltol8 d2000 -full, 3 seeds) match — avgBound Δ−1.6%, meanReach Δ−0.4%, occupancy Δ−1.4%, velFitX within seed
scatter (seed1 identical 4.239=4.239); it isolates, does not intervene. **PART 1** (coltol {8,6,4,3,2} × d {2000,
4000,8000}, chamber ON, GPU single-seed 30k): **NO crossover.** The regime indicator occupancy `avgBound/meanReach`
goes the OPPOSITE way — it **RISES** with shrinking coltol (0.83→2.70, *past 1.0*), density-flat, never toward the
~0.06 duty; because coltol shrinks the instantaneous geometric window `meanReach` (24→3 @d8000) faster than the
persistent bound set `avgBound` — a bound head is dragged out of the 2 nm window but stays bound for its ~0.6 ms
dwell ⇒ avgBound > meanReach ⇒ occupancy > 1 (window-thinning, exactly `DENSE_DUTY_GAP`'s "not a real duty"). velFitX
and avgBound DROP TOGETHER (shrinking-pool fingerprint, not the ceiling's flat-vel/climbing-avgB), and the
velFitX-density curve **keeps climbing at EVERY coltol** (≈doubles d2000→d4000; even c2 climbs 2.11→4.29→6.65) ⇒ no
density-independence, no saturation. Filament stays CONTINUOUSLY ENGAGED (avgBound ≥2.4, inst 7–9 µm/s, `fullMat=YES`
throughout) ⇒ outcome #3 (coltol just scales the pool), NOT over-restriction. **The mat-box made d8000 coverage-CLEAN
(`fullMat=YES`, velFitX 13.5) — the recruit-shed sweep had d8000 VIOLATED at -full; the chamber delivered its purpose.**
**Mechanistic core: coltol is a pool-SIZE lever, not a refill-CLOCK lever** — it sets how many heads can reach an
opening site, not how fast the site refills; dwell/detach are FLAT (~0.6 ms / ~1600/s) at every coltol ⇒ a reachable
site still refills faster than it releases even in a 2 nm window (occupancy ≥0.83, rising >1) ⇒ the system NEVER
leaves supply-limited. Per-bound efficiency RISES as coltol shrinks (fewer co-bound heads → less tug-of-war; the
mirror of `RECRUIT_SHED`) but the pool shrinks faster ⇒ net velFitX falls. **CPU basin-arbiter @c2 d2000 30k** (extreme
point; coltol is a scalar so all arms share hot-kernel structure, low flip hazard, but the geometric extreme gets a
check): avgBound 2.403 vs GPU 2.405 (0.08%, SAME basin), velFitX 2.282 vs 2.106 (+8%, within SEM), occupancy 3.08 vs
2.70 (>1 reproduced), `fullMat=YES` ⇒ real, not a GPU artifact. **VERDICT: coltol alone does NOT reach the crossover —
it just scales the pool; occupancy rises past 1 rather than falling; velocity stays density-climbing, never saturates
at d/τ_on. A per-site BINDING-RATE cut would be needed, and even that only scales (`AZIMUTHAL_FALLOFF`/`RECRUIT_SHED`).
The missing ceiling is STRUCTURAL — the bound-head POPULATION (steric co-occupancy / displacement clutch), not the
capture geometry.** No default change (diagnostic). Report `docs/COLTOL_REGIME_SWEEP.md`; new `-matbox`,
`scripts/run_coltol_regime_{part0,sweep,arbiter}.sh`; `BoA-v1ref` untouched.

# 2026-07-10 — RECRUIT_SHED_BALANCE: slowing the shed of back-strained brake heads does NOT create a velocity ceiling
Tested jba's reconciling hypothesis — does the ensemble stay net-forward (no d/τ_on ceiling, velFitX ∝ N) only
because spent back-strained brake heads are SHED as fast as they form? New flag `-brakehold <s>` scales αCatch
(`kinParams[1]`), the SIGNED catch-slip term `αCatch·e^(−F·xCatch/kT)` that EXPLODES for a post-stroke back-strained
head (F<0) ⇒ sheds resisting heads; s<1 slows that shed (brakes persist). Default 1.0 = skeletal Guo–Guilford anchor,
guarded `!= 1.0` ⇒ **byte-identical canonical (verified: no-flag ≡ -brakehold 1.0 bit-identical GRID_ROW).** PART A
(s ∈ {1.0,0.3,0.1,0.03} × d ∈ {2000,4000,8000}, coltol=8, GPU single-seed 30k): the lever WORKS mechanically —
dwell 0.63→5.7 ms, detach 1592→175/s, avgBound 5→50 (brakes retained) — **and the retained brakes BITE** (per-bound
efficiency collapses 0.74→0.087 @d2000, 0.585→0.121 @d4000 ⇒ resistance genuinely AGGREGATES). **But velFitX does
NOT cap:** flat ~4 @d2000 (a coincidental efficiency-collapse≈recruitment cancellation), **rising +56% @coverage-clean
d4000** (6.23→9.70; recruitment 7.5× OUTRUNS the 4.8× efficiency collapse). Slowing shed **STEEPENS** the
velFitX-vs-density curve (d2000→d4000 slope +61%→+122%), the opposite of a ceiling, and climbs PAST the d/τ_on≈6–8
anchor, not toward it. Even 33×-below-skeletal (s=0.03) installs no cap ⇒ no capping slip value at any multiple.
**d8000 slow-shed EXCLUDED** (`fullMat=VIOLATED`, filament ~2 µm off-bed in y; the 22–27 µm/s "explosion" is
edge-corruption — first sweep grep dropped COV_ROW, caught in the d4000/d8000 coverage re-run). PART B control
(`-azfalloff 16`, recruitment reduction): scales velFitX+avgBound down ~density-independently, both still climb,
kinetics unchanged ⇒ doesn't cap either (reproduces AZIMUTHAL_FALLOFF_INCREMENT3 in-batch). **CPU basin-arbiter @the
decisive d4000 s=0.03** (steepest slip, coverage-clean, avgB≈80): velFitX 9.063 vs GPU 9.703 (~7%), avgB 78.5 vs 80.0
(~2%), fullMat=YES, stable on the deterministic runner ⇒ **same HIGH basin, the rise is real, not a GPU artifact.**
**VERDICT: the missing ceiling is NOT a shed-side rate-balance.** Resistance aggregates (per-bound collapse) but
co-bound recruitment is UNBOUNDED, so retention adds heads faster than each loses efficiency ⇒ net never caps. The
controlling lever is the bound-head POPULATION (steric co-occupancy / a force→displacement clutch), not the shed
rate — confirming DETACHMENT_CEILING_CODEREAD (force-summation, no displacement clutch) + the falloff control. Both
the recruit knob and the shed knob fail ⇒ the ceiling is STRUCTURAL. No default change (diagnostic). z-unconfined.
Report `docs/RECRUIT_SHED_BALANCE.md`; new `-brakehold`, `scripts/run_recruit_shed_sweep.sh`, `run_recruit_shed_cov.sh`;
`BoA-v1ref` untouched.

# 2026-07-09 — STROKE_DRAG_PROBE: the cross-bridge attachment is MATERIAL-LATCHED, not a conveyor (C1/C2/C3 refuted)
Closed the one gap DETACHMENT_CEILING_CODEREAD left open: is the F8 filament-side foot a frozen MATERIAL label
transformed by the segment's current pose (Lagrangian ⇒ strain accrues, head resists past the stroke), or
RE-DERIVED to nearest/perp-foot each step (Eulerian ⇒ conveyor belt, strokes forever)? **PART A** (`EomStabilityHarness
-dragprobe`, single bound head, motor frozen, Brownian OFF, deterministic): advancing the filament +x through/past
the ~7 nm stroke, the real-code F_x is a clean linear cross-bridge spring — **+10 pN @Δ=−10nm → 0 @Δ=0 → −24 pN
@Δ=+24nm, slope EXACTLY −k_F8 (−1.0 pN/nm)** — and the attachment world position ap_x tracks the filament 1:1
(pinned to a receding material point). A synthetic CONVEYOR control (bindArc re-derived to the frozen tip's perp-foot
each Δ) holds F_x≈0 for all Δ with ap_x under the fixed tip — the C1 signature, and NOT what the code does. **PART B**
(read-only): `bindArc` is written ONLY at the free→bound transition — every binder guards `boundSeg != FREE_BINDABLE
⇒ continue` (bindNearest:368, bindKinetics:325, +Azim/Falloff/CanonicalTwoPoint/Rate/NodeAware), and `bondForces`
:113-118 builds the site as `segCenter + (bindArc−½segLen)·segUVec` from the CURRENT pose — the transformed-material
(Lagrangian) branch; the Eulerian re-solve is absent. Stroke target (directedSwing:251-253) = a latched
nucleotide-state-FIXED orientation (translation-invariant), not sustained-force (C3) nor re-neutralizing (C2). ⇒ the
bound head RESISTS when dragged past its stroke; C1/C2/C3 all REFUTED, attachment + stroke are CORRECT. The V∝N /
missing d/τ_on ceiling is the orthogonal force-summation-without-displacement-clutch + high-duty fact (do NOT "fix"
the attachment). Additive `-dragprobe` mode, default byte-identical. Report: docs/STROKE_DRAG_PROBE.md.

# 2026-07-09 — Dense duty gap CLOSED: 0.85 is a thin-window ENRICHMENT artifact, per-head duty ~0.06 (recovery NOT bypassed)
Closed the unclosed reconciliation step in DUTY_RATIO_DIAGNOSIS (was the dense 0.06→0.85 gap enrichment or a
bypassed 10ms recovery?). **PART 1 (READ-ONLY, existing coltol=8 sweep log + code) — closes it, no run.** The crux
number: `meanReach ≈ 1.17×avgBound at EVERY density` (d1000 2.89/3.33, d4000 11.0/13.0, d8000 20.8/24.0) — a THIN,
bound-dominated reachable window, NEVER hundreds. Arithmetic closes exactly: τ_on 0.6ms / τ_off≥10ms ⇒ per-head
duty r≈0.057; the ~16.7× recovering (ATP) heads per filament (avgB×10/0.6 ≈184 @d4000) would push meanReach to
~194 IF reachable, but it's 13 ⇒ they're in the glide WAKE, out of reach, dropped from the denominator. duty_reported/r_perhead
= (engaged pool)/meanReach ≈ 17.7/1.18 ≈ 15 ⇒ 0.057×15 ≈ 0.85 ✓ (no residual). **Recovery is ENFORCED, not
bypassed:** bruteReachable is purely geometric/state-blind (BindingDetectionSystem:260-282, so meanReach DOES
count recovering heads — their absence is real), AND the ADP·Pi gate is welded on (GlidingHarness:310
LYMN_TAYLOR⇒ADPPI_BIND, kinParams[20]=1) + enforced in the dense binder bindNearestFalloff (BindingDetectionSystem:502/509,
`if adppiGate && state≠ADPPI continue`) ⇒ a just-released ATP head can't rebind for ~10ms ⇒ r≤0.057 hard bound.
**VERDICT: thin-window enrichment artifact; dense per-head duty ~0.06; motor low-duty; recovery enforced; PART 2
NOT needed.** Matches single-molecule `in-reach-while-free≈0`. ⇒ no-saturation is force-summation/recruitment
(DETACHMENT_CEILING_CODEREAD), not kinetics. Report: `docs/DENSE_DUTY_GAP.md`.

# 2026-07-09 — Azimuthal-binding Inc 3: GRADED orientational falloff — DOES NOT saturate at any steepness
Replaced Inc-2's hard antiparallel cutoff with a GRADED orientational affinity `a(s)=((−headU·n̂+1)/2)^n`,
MAX-combined over reachable sites (`a_best=b_best^n`, NOT sum — sum reintroduces the washout), applied as a
bind-rate multiplier via a NEW race-free wang-hash draw (salt "AZBD", drawn LAST; the gliding bind was
deterministic-nearest with no existing draw). Reuses the Inc-2 scan/interp/handedness (−166.5°/mon LEFT) verbatim;
gliding-only `bindNearestFalloff` (bindNearest's ~20 callers + Inc-2 `bindNearestAzim` untouched); `-azfalloff <n>`,
default OFF byte-identical; `BoA-v1ref` untouched. n=0 recovers baseline (aggregate — the draw decorrelates ⇒ not
bit-identical; d4000 10.93/6.72 ≈ 10.69/6.44 ✓). PTX-clean; localWork=64 already on the bind task.
**THE FINDING (single-seed 30k density-response, d1000→d8000):** graded falloff does NOT cap. avgB climbs
monotonically at n=16 (1.9→15.1) AND n=32 (1.5→13.1); the throttle is a roughly **density-INDEPENDENT scale-down**
(~0.7× avgB @n16, ~0.65× @n32, ~0.55× @n64) — a plateau never forms. MAX-combine DID fix the Inc-2 washout (ratio
now FLAT vs the hard gate's RISING 0.80→0.91) — real improvement — but flat-ratio still ⇒ avgB=baseline×const ⇒
climbs. n-response @d4000 monotone-diminishing (n=0→64: avgB 10.9→6.1), never a low cap. GPU≡CPU @d4000 n16 agree
(7.89/4.44 vs 7.44/3.92, no basin flip). **Mechanism:** MAX-combine finds the best azimuth in a multi-turn reach
window regardless of density ⇒ binding depends on head orientation (density-independent), NOT segment packing ⇒
orientation throttles per-head propensity, not co-occupancy. **VERDICT: orientation CLOSED as the saturation lever
at any sharpness; the lever is STERIC co-occupancy exclusion** (head footprint) — converges with the concurrent
`DETACHMENT_CEILING_CODEREAD` (force-summation ∝N, no displacement clutch; fix = clutch/steric cap, not kinetic/
orientational retune). Report: `docs/AZIMUTHAL_FALLOFF_INCREMENT3.md`; raw
`RUN_LOGS/2026-07-09_azimuthal_falloff_sweep.txt`; driver `scripts/run_azimuthal_falloff_sweep.sh`.

# 2026-07-09 — Duty-ratio diagnosis: the 0.85 is a MEASUREMENT ARTIFACT, not a τ_off rate bug
Settled whether the dense-sim duty~0.85 (vs skeletal ~0.05) is an intrinsic rate bug (τ_off too short) or a
denominator artifact. **PART A (read-only):** the STATS_STEADY "duty" = `avgBoundSteady/meanReach`
(GlidingHarness:2731) = "fraction of ENGAGEABLE (reachable) heads bound at any instant" — a conditional SPATIAL
occupancy of the reachable-head pool (meanReach = mean #heads with reachCount>0, :2564/:2610), NOT the temporal
r=τ_on/(τ_on+τ_off). The reachable set is bound-head-ENRICHED (a bound head is always reachable for its whole
dwell; free heads flit in/out) ⇒ the ratio structurally overstates occupancy. τ_off is PHYSICALLY TIMED, not
collapsed: the 1-step refractory (kinParams[10]=ceil(MYO_REBIND_TIME 1e-5/dt)=1 step=0.01ms) is negligible, but
the REAL gate is the off-fil ATP→ADP·Pi hydrolysis recovery offATP=100/s (~10ms, MotorStore:388) enforced by the
WELDED ADP·Pi bind-gate (kinParams[20]); onADP=1e3/s ⇒ τ_on~1ms. **PART B (single-motor CPU, `-single`, 40k, 256
heads, concurrent-safe — no GPU):** intrinsic single-head duty = **0.0050 (0.5%)**, t_on 0.91ms, τ_off ~180ms
(attach 5.5/s) — skeletal-class, 170× BELOW 0.85. **FORK VERDICT: MEASUREMENT ARTIFACT** — motor is low-duty; do
NOT slow rebinding (would stall the glide). Anchored τ_on~0.9ms ⇒ d/τ_on ~6-8µm/s to test dense velocity against;
no-saturation stays in force-summation/recruitment (DETACHMENT_CEILING_CODEREAD). Caveats: `-single` runs Config-1
(kinetics shared w/ sphere-head ⇒ duty transfers); `-fext` is INERT on the Lymn-Taylor path (feeds only legacy
catchSlipRelease kinParams[18], not cycleLymnTaylor) ⇒ T2 load-sensitivity read from code (:449-452), not measured.
Report: `docs/DUTY_RATIO_DIAGNOSIS.md`.

# 2026-07-09 — Detachment-ceiling code read (READ-ONLY): T1/T2/T3 all PRESENT; V=d/τ_on absent for an ORTHOGONAL reason
READ-ONLY diagnostic (no edits/runs; concurrent with the falloff sweep) of whether the canonical motor has the
kinetic detachment-limited speed ceiling. Traced the live default stack (`bondForces` F9-frozen-90° + `directedSwing`
+ `cycleLymnTaylor`; GlidingHarness:77-79/255/757/762/735). **All three targets check out:** T1 backward drag IS
representable (F8 = bidirectional Hookean anchored to the FIXED material `bindArc`, seg-side −F resists a dragged
filament; CrossBridgeSystem:114-204 — and the sweep's per-bound 0.95→0.46 collapse IS that drag, delivered); T2
detachment IS strain-DIRECTION-signed (Guo–Guilford catch-slip on signed forceDotFil, NucleotideCycleSystem:449-452;
the |F|>12pN `-forcecapdetach` is opt-in/non-canon); T3 powerstroke target is FIXED-orientation (cosθ·û_head−sinθ·f̂,
θ nucleotide-switched; re-neutralizes only in angle, invariant under translation ⇒ doesn't track the filament).
**Prompt's T1/T3-missing hypothesis REFUTED.** But V=d/τ_on still fails (velFitX∝N, no plateau) for reasons ORTHOGONAL
to T1/T2/T3: (1) overdamped force-SUMMATION with NO per-head displacement clutch — the stroke is a force, heads only
add force ⇒ N heads ⇒ ∝N glide, no cap; (2) the model runs HIGH-duty ~0.85 (density-flat dwell 0.6ms/detach 1600/s),
the wrong regime for the low-duty ceiling; (3) T2 working CORRECTLY sheds back-strained brakes ⇒ sustains the
forward bias ⇒ works AGAINST a plateau. Duty is high & has no code path to climb with density (velocity climbs via
avgBound recruitment, not duty). τ_on/duty ALREADY logged (stats[2m]/[2m+1] ⇒ STATS_STEADY_ROW); the deferred probe
= vary onADP/atpOn at fixed d4000, read velFitX-vs-dwell (predicted: insensitive ⇒ no ceiling). Fix = build the
force→displacement stroke-limit clutch / steric co-occupancy cap (the AZIMUTHAL_GATE §Verdict lever), NOT a τ_on
retune and NOT "fixing" T1/T3. Report: `docs/DETACHMENT_CEILING_CODEREAD.md`.

# 2026-07-09 — Azimuthal-binding Inc 2: orientational bind gate in gliding — UNDER-RESTRICTIVE at Δ=45°
Wired the springs-continuum roll spring (Inc 1/1b) into the gliding path + added the orientational binding gate
(a free head binds only where its uVec is antiparallel within Δ to a presented actin-site radial n̂(s), Option-2
scan of reachable sites; intra-segment helix interp φ=twistRate·(arc−½segLen), LEFT-handed −166.5°/mon).
Flag-gated `-azimbind`/`-azaccept`/`-rollonly`, **default OFF byte-identical** (default d1000 s0 velFitX 2.635 ≈
baseline 2.687). Gliding-only `bindNearestAzim` (bindNearest's ~20 callers untouched); two roll tasks
(dampRoll/rollForces) added to the gliding graph + CPU mirror. `BoA-v1ref` untouched.
**PART A (roll-into-gliding checkpoint) PASS:** roll-on/gate-off ≈ baseline — CLEAN at d4000 (6.60/10.67 vs
6.44/10.69); d1000 s0 GPU dip (0.884) is a sparse-density BASIN artifact, **CPU-arbiter decisive** (CPU rollonly
2.269 ≈ CPU baseline 1.796, no flip). Roll is glide-invariant (nothing canonical reads seg.yVec under AXLOCK;
isotropic perp-drag ⇒ roll-covariant). **PART C cost negligible** (+0.5%/+1.0% at d1000/d8000; GPU PTX clean).
**PART D — the finding:** at biologically-central Δ=45° the gate is a MODEST, density-WEAKENING throttle
(avgB ~0.80–0.91× baseline, velFitX ~0.68–0.84×, ratios RISING with density) — **avgBound NOT capped, velFitX
NOT saturated; both keep climbing** (avgB 0.19→18.5 over d100→d8000, 3-seed 60k). Δ-ladder (d4000, 10k): 90°≈
baseline, 45° −28%, 15° −51%, 5° hard-cap −83% ⇒ gate is real+tunable but caps only at implausibly narrow Δ.
**Mechanism:** Option-2 over a multi-turn axial window ⇒ accept collapses to "head uVec within Δ of the radial
plane," which loosens as density rises ⇒ throttles per-head propensity, NOT co-occupancy ⇒ can't flatten the
curve. **Verdict: the orientational constraint alone is insufficient to saturate; the physically-right next
lever is the deferred STERIC exclusion (head footprint, caps co-occupancy directly).** Sweep stopped after
d8000 s0 (trend unambiguous; jba concurred exact high-density figures don't change the verdict). Report:
`docs/AZIMUTHAL_GATE_INCREMENT2.md`; raw `RUN_LOGS/2026-07-09_azimuthal_gate_sweep.txt`; driver
`scripts/run_azimuthal_gate_sweep.sh`.

# 2026-07-09 — Azimuthal-binding Inc 1b: roll coupling converted to the SPRINGS-CONTINUUM form (dt-honest)
Converted Inc-1's fraction-per-step roll coupling (the one such law smuggled back after the canonical collapse)
to the canonical springs object — a FIXED stiffness `k_roll = f·γ_roll_red/refDt` via the `springify(f)=f·(dt/
refDt)` convention (GlidingHarness §PAIRS_SPRINGS): mode 2 uses `refDt` in the denominator instead of `dt` ⇒ dt
CANCELS ⇒ dt-independent stiffness. Now the DEFAULT roll form (`-fraction` for the old law). All in
`RollSpringSystem`/`RollSpringHarness` (new files) ⇒ canonical/production byte-identical (FDT re-PASS);
`BoA-v1ref` untouched. **Three checks GREEN:** (1) refDt-equivalence — springs ≡ fraction at 1e-5 BYTE-IDENTICAL
(std 2.54°, CPU≡GPU Δ1.76e-5° identical), all Inc-1 numbers stand; (2) stability — springs M=200000 (2.0 s) std
±2.57°, ratio 1.022, no NaN, ROUTE (A); (3) **dt-convergence (the payoff)** at dt {1e-5,5e-6,2.5e-6,1e-6}:
FRACTION std 2.40→1.68→1.17→0.74 (∝√dt, FREEZES as dt→0 — the artifact) vs SPRINGS 2.40→2.15→2.05→2.00
(dt-STABLE, converges to the equipartition ~2.0° — dt-honest like the canonical model). CPU≡GPU bit-identical on
the reformed spring. No fraction-per-step exception remains; the roll frame is dt-honest for Inc 2 (off-axis
bond). Report: `docs/ROLL_SPRING_PROTOTYPE.md` §SPRINGS-CONTINUUM CONVERSION.

# 2026-07-09 — Azimuthal-binding Inc 1: torsional-roll spring (physical twist) — ROUTE (A) VIABLE
Risk-first prototype of the inter-segment torsional-roll spring in ISOLATION (no binding/off-axis-bond/motors/
turnover), answering: is there a roll stiffness giving a COHERENT helical twist STABLE at production dt=1e-5?
**YES — outcome (A).** New files only (`RollSpringSystem`, `RollSpringHarness`, `scripts/run_rollspring.sh`) ⇒
canonical/production byte-identical by construction; `BoA-v1ref` untouched. Spring = the dt-robust
**fraction-per-step** family (α=f, stable f<2 — NOT raw Hooke, whose `dt_crit ∝ γ_roll/k` on the tiny roll drag
`bRGx=4πηR²L≈1.4e-24` rings by α≈0.7, blows up α>2). Rest twist = actin 13/6 `−166.5°/mon` (LEFT-handed, derived
fresh; v1 screw sign not lifted), ×32 mon wrapped = coarse **+72°/joint** (true handedness aliases to the
deferred intra-segment interpolation). Race-free owner±equal-opposite `torqueSum`→`bwx` (the ChainBending
two-block PTX pattern; a free-end inner-loop `continue` mis-lowers — fixed). Thermostat `-rolldamp` cools the
roll Brownian kick. **Results (64-seg, roll kicked on ALL segs = hard test):** long run **M=200000 (=2.0 s sim)
std ±2.57° about 72°, no NaN, ratio 1.022 STABLE**; fraction sweep coherent+stable f∈[0.1,1.0] (best ±2.3°@0.5),
raw-Hooke narrow/rings; thermostat is the coherence knob (std∝rolldamp: 0.1→±2.5°, 1.0→±24°); **no-spring
control f=0 → std 93° scrambled (outcome C) ⇒ the spring MAKES the coherence**; **CPU≡GPU bit-identical**
(1-step exact; perturb-relax max Δ 1.76e-5°). Op point: fraction f≈0.5, rolldamp≈0.1. ⇒ proceed to Inc 2
(off-axis bond). Bring-up bug caught: measurement pulled only `uVec` not `yVec` (stale GPU frame read trivially
"coherent"). Report: `docs/ROLL_SPRING_PROTOTYPE.md`.

# 2026-07-09 — Viscosity (aeta) sensitivity probe — glide is REGIME-DEPENDENT drag-sensitive
3×3 probe (aeta {0.05,0.10,0.20} Pa·s × 3 seeds) at d1000/coltol10/60k, GPU canonical default. velFitX (µm/s,
±SEM): 3.266±0.360 / 2.825±0.054 / 1.579±0.078. **Net glide is NOT a single power law in η — a knee at the
default η≈0.1:** WEAK below (η 0.05→0.10, p≈−0.2, reproduces the standing "drag-insensitive η⁻⁰·¹⁸, cycle/
tug-of-war-limited" verdict) but STRONG above (η 0.10→0.20, p≈−0.84, near drag-limited η⁻¹). avgBound
~viscosity-independent (p≈+0.13); inst ∝ η⁻⁰·⁵ (thermal-jitter-dominated ⇒ use velFitX not inst). REFINES (not
overturns) the drag-insensitive memory. Caveats: aeta=0.05 seed0 low-engagement outlier inflates that point's
SEM; 3 seeds / 3 points / 0.6 s window; `-aeta` is a diagnostic non-faithful lever (rescales FDT amplitude too).
Report: `docs/VISCOSITY_SENSITIVITY_FINDINGS.md`; raw `RUN_LOGS/2026-07-09_aeta_sensitivity.txt`; driver
`scripts/run_aeta_sensitivity.sh`.

# 2026-07-09 — Wider density sweep @ coltol=8 nm — the AZIMUTHALLY-UNAWARE PRE-REFINEMENT BASELINE
Swept motor density {100,250,500,1000,2000,4000,6000,8000} µm⁻² at **coltol=8 nm** (tighter capture ⇒ a NEW,
lower-engagement curve, not an extension of the coltol=10 first stab), 3 seeds, M=60000 (0.6 s), canonical
DEFAULT model on GPU (bare `run_gliding.sh -gpu -full -grid -coltol 8 -density D -seed s 60000`; new driver
`scripts/run_canonical_density_sweep_coltol8.sh`). The CONTROL measured before jba adds specific helical/azimuthal
binding sites — "the model's own ceiling," not biology validation. **All 24 GPU runs clean — no NaN/blow-up at
any density.** velFitX (µm/s, ±SEM) rises MONOTONICALLY, **no plateau/no turnover through d8000:**
0.13→0.32→1.01→2.69→4.39→6.44→7.95→**9.38** (SEM 0.05/0.08/0.12/0.05/0.11/0.13/0.20/0.21) — a DECELERATING
climb (+143/213/166/63/47/23/18%) toward a Vmax that lies ABOVE ~9.4 (unreached at d8000). avgBsteady climbs
~linearly (0.14→20.2, tracking meanReach 0.2→24.6, ~82–88% of reachable bound). **HEADLINE (new vs the
d2000-capped first stab): the saturation is a per-head EFFICIENCY collapse, not motor slowdown/instability** —
per-bound flat ~0.95 through d1000 then COLLAPSES 0.81→0.60→0.50→0.46 above d2000 (the co-bound tug-of-war),
while avgBound keeps rising. Kinetics DENSITY-INDEPENDENT (dwell ~0.60 ms, duty ~0.85, detach ~1600/s flat) ⇒
collective mechanics, not a rate change. inst NOT density-flat at high d (6.4→7.5 through d2000, then 8.6/9.8/11.5).
**Collective-load STABLE on -xbimplicit2 alone — -segimplicit NOT needed/not fired** (avgBound≈21 @ d8000, no
blow-up). **Coverage: fullMat=YES d100–d6000 + 2/3 d8000 seeds; d8000 seed1 fullMat=VIOLATED (runMinMargin
−0.204 µm — leading edge ran off the bed at ~9.4 µm/s over 0.6 s) ⇒ d8000 is a MEASUREMENT-GEOMETRY limit (bed
length/window), NOT physics.** CPU d4000 basin-arbiter (20k, 54:51): velFitX 6.469 vs GPU 6.439 (**0.5%**),
avgBound ~7% lower (shorter earlier steady window, not a basin flip) ⇒ **same HIGH basin, dense curve
trustworthy.** VRAM a non-issue (~800 MiB @ d8000, 214k motors). Per-seed GPU wall: 1.9/2.3/3.0/4.5/7.4/13.2/
18.9/24.8 min (d100→d8000); full sweep ~3.8 h. No BAIL. Report: `docs/DENSITY_SWEEP_coltol8.md`; raw
`RUN_LOGS/2026-07-09_canonical_density_sweep_coltol8.txt`; driver `scripts/run_canonical_density_sweep_coltol8.sh`.

# 2026-07-09 — First canonical velocity–density sweep (FIRST STAB — short window)
Swept motor density {100,250,500,1000,2000} µm⁻² at coltol=10 nm, 3 seeds, M=60000 (0.6 s), canonical DEFAULT
model on GPU (bare `run_gliding.sh -gpu -full -grid -coltol 10 -density D -seed s 60000` — springs+Lymn-Taylor+
ADP·Pi-bind+xbimplicit2 all default-on; verified bare≡explicit-GATE2 byte-identical). STEP 1: the flagged
`run_densesweep.sh` was a PRE-COLLAPSE GHOST (drives `DenseGlidingHarness -scale`, no density/coltol/GRID_ROW) —
NOT reused; wrote a clean driver `scripts/run_canonical_density_sweep.sh`. **RESULT — sensible, biologically-
plausible curve.** velFitX (µm/s) rises monotonically & saturates above d1000: 0.14→0.47→1.10→**2.83**→3.82
(±SEM 0.03/0.06/0.19/0.05/0.36); d1000 reproduces the capstone baseline exactly (2.825±0.054, at Vmax of the
skeletal band ~2.9). avgBound tracks it (0.17→0.57→1.44→3.32→5.34; deficit channel). **per-bound ~flat
(0.72–0.87)** ⇒ the rise is ENGAGEMENT-driven, not motor-speed; **instantaneous speed ~density-independent
(6.4→7.4, +15% over 20×)** — the classic gliding-assay signature (near v1's 8.33). Curve is SIGMOIDAL/threshold-
like (slow foot <d500 where avgBound<1), NOT hyperbolic — MM over-predicts the low end 2–3× (rough Vmax~5.9/
KM~1090/half-max~d1090, Vmax not reached at d2000). CPU d1000-arbiter: velFitX 2.867 vs GPU 2.895 (0.97%), avgB
3.286 vs 3.332 (1.4%) — SAME HIGH basin, curve trustworthy. Flags: d500/d2000 wide seed scatter (short-window
velFitX noise); first stab (0.6 s/3-seed) ⇒ longer+more-seeded confirm (+d4000) warranted. No BAIL. Report:
`DENSITY_SWEEP.md`; raw `RUN_LOGS/2026-07-08_canonical_density_sweep.txt`; driver
`scripts/run_canonical_density_sweep.sh`.

# 2026-07-08 — CAPSTONE: canonical model established; the dt-convergence arc resolved

This entry is the current state of the project. It supersedes the dt-convergence / thermostat / bistability /
canonical-collapse arc (now archived in JOURNAL_ARCHIVE.md) — read this, not the blow-by-blow, unless you need a specific result.

## The canonical model (one path, honestly declared in code)
- **Motor:** sphere-head, neck powerstroke (axial-swing-locked, f̂-directed). Hard-won over many geometries; the
  lay-the-neck-near-the-filament families all failed and are gone.
- **Constraints:** Hookean linear + torsional **springs** (fixed stiffness), no fraction-per-step / rate machinery.
- **Kinetics:** Lymn-Taylor cycle with **ADP·Pi-only binding** welded in (ATP-binding-driven detachment; catch-slip
  demoted to ADP-release load modulation).
- **Cross-bridge:** coupled per-segment implicit solve (`-xbimplicit2`), canonical for the dense/ring regime it
  scales to.
- The code now **declares** this as the default (no post-parse promotion, no flag-jungle); a fresh reader gets the
  right model from the source instead of reconstructing it from flags.

## The honest canonical gliding baseline
**≈ 2.83 µm/s** (GPU 3-seed mean velFitX 2.825 ± 0.054; CPU spot-check 2.867; per-bound ~0.85), at **Vmax of the
skeletal band** (~1.5–4 µm/s, Vmax ~2.9). Measured on the trustworthy transcendental-free springs path
(GPU≡CPU basin verified). *Caveat:* 0.6 s / 3-seed is a solid but short steady window — a longer, more-seeded
confirm is warranted before this is quoted as a formal validation number.

## Why the dt drama is over (the arc, compressed)
The "~2× dt-bias" that launched the arc was **not** a motor defect — it was two artifacts, now removed:
1. **A frozen skeleton.** The structural joints + tail anchor were fraction-per-step and *froze* (variance→0) as
   dt→0, artificially stiffening the fine-dt motor and inflating fine-dt glide. Reformulating them as
   dt-convergent springs removed it.
2. **A wrong-basin baseline.** The GPU `-ratefix` path landed in a LOW glide basin via an `exp/log`-in-a-hot-kernel
   PTX-scheduling artifact, amplified by a **bistable** operating point. The transcendental-free springs path lands
   in the correct HIGH basin. Prior GPU tables carrying `-ratefix` (the old "per-bound 0.736" baseline) were
   reading the wrong basin.

With both fixed, **production dt=1e-5 per-bound is ~converged**. The residual production-dt gap now lives in
**avgBound (engagement, ~−27% vs the fine-dt limit)** — the real open item, and it's kinetics / capture geometry,
**not** numerics.

## Findings retired (do not re-litigate)
- **Integration order:** 2nd-order (trapezoidal) was null in the assay. No sub-step needed for gliding.
- **Thermostat / Euler-Maruyama variance correction:** the F8-bond correction is physically real and, per the
  uniform equilibrium gate, *required* for equilibrium modes / *forbidden* for driven modes — but its measured GPU
  "effect" (`-allnoise`) was the basin-flip artifact, not physics; correcting every mode nets ~0 at production dt.
  Dropped. `-segimplicit` retained for the dense/ring collective-load regime.
- **Convex detachment-rate sampling:** dt-flat. Not the residual.

## Reproducibility discipline (codified in CLAUDE.md)
The gliding steady state at this operating point is **bistable and knife-edge** — basin selection by last-bit PTX
scheduling is not physically controlled. Standing rule: **GPU numbers need a CPU-arbiter cross-check** for
structure-differing A/Bs, reported results, and periodic spot-checks. Springs is the transcendental-free path that
makes the GPU internally deterministic. (Bistability origin: most likely a GPU execution artifact selecting a state
the CPU won't spontaneously enter; a genuine chaotic attractor is not *fully* excluded — the CPU-seed-from-GPU-
microstate test was infrastructure-bailed — but the practical verdict is runner-independent: run the deterministic
transcendental-free path.) Consequence worth remembering: prior GPU A/Bs whose arms differed in kernel structure
(much of the `-allnoise`/`-thermcorr` record) may have compared basins, not physics.

## Code state — canonical collapse COMPLETE (Stages 1–3, byte-identical throughout)
- **Rollback tags (all pushed):** `pre-canonical-collapse-2026-07-08` (5b60b0a) · `post-stage1-pre-deletion-2026-07-08`
  (1b87c72) · `post-stage2-2026-07-08` (a10a871).
- **Removed** (preserved in git): rate machinery, noise-correction family, failed integrators
  (`-xbimplicit`/`-xbdash`/`-xbsat`), superseded kinetics (`-atprecharge` etc.), `-freshread`, motor recasts
  (`-hfswing`/`-rollsign`/`-mhatset`), failed motor geometries.
- **Kept:** `-segimplicit` (ring) · `-legacymotor` + `BoA-v1ref` (regression references, v1 bit-parity is no longer
  a validation gate) · `-forcecapdetach` (renamed from `-faithfulrelease`; threshold `-detachcap`, default 12 pN;
  opt-in, not in canon) · opt-outs `-legacycycle`/`-explicitxb`/`-nosprings`/`-allowbindany` · `-tauavg` +
  `ATP_RELEASE` (feed kept phase-2 paths) · all diagnostics, each MARKED `[NON-CANONICAL DIAGNOSTIC]` where it
  diverges (all 8 own-scene diagnostics deliberately probe non-canonical scenes — none silently pass as canonical).
- **`coltol`/`density`:** swept experimental parameters — loud placeholder warning, no canonical/meaningful default.

## Deferred future work (each its own task)
- **The avgBound engagement deficit** — the live scientific open item (kinetics / capture geometry).
- A **longer, more-seeded canonical gliding validation** number vs the band.
- Phase-2 two-point canonical motor (`-canonical`/`-config1`/`-perphead`, opt-in).
- `-forcecapdetach` **promotion** decision (a 12 pN force-cap detachment in canon re-baselines avgBound ~7.6→6.5).
- Springs **fine-dt / physical-stiffness recalibration** (only if running below refDt, or giving the stiffnesses
  persistence-length / bending-modulus meaning).
- Optional cosmetic sweep of the remaining documented-orphaned methods.

## Flagship (unchanged)
Whole-cell contractile ring formation via protein nodes, faithful to fission-yeast SCPR. Gliding was the validation
vehicle; it now sits on solid canonical footing, and the integrator canon (`-xbimplicit2` + `-segimplicit`) was
chosen to scale to the intense-tug-of-war/dense regime the ring lives in.

_Housekeeping (2026-07-08): the dt-convergence → canonical-collapse arc (96 entries) was archived to
`JOURNAL_ARCHIVE.md` (commit `30d101e`), and the base-dir experiment drivers were reorganized in the following
reorg commit — retained investigation drivers → `scripts/archive/`, retired dead-end drivers → `removeMe/`; LIVE
subsystem/benchmark drivers stay at the repo root. See those dirs' READMEs._

## 2026-06-24 — CROSSOVER DIAGNOSIS: the high-scale v2-GPU/v1-GPU crossover is an ARTIFACT of v1-GPU under-binding (verdict (a), ~100%), MEASUREMENT-ONLY
Re-analyzed the 4-way grid + re-measured v2 per-graph timing (uncommitted `-pergraph` instrument) + READ v1's GPU binding path (BoA-v1ref, read-only; no edits). **Cheap cut:** per-doubling step-time ratios v2-GPU 1.85/1.98/2.05 (≈ ideal 2.0 = honest ∝1/N) vs v1-GPU **1.25/1.34/1.52 (impossible for a full-work path ⇒ dropped work)**. **Thrust 1 — lead C confirmed:** v1-GPU binds 53/41/27/**19%** of v1-CPU @ 1/2/4/8× (near-FROZEN absolute bound 245→573 vs v1-CPU 459→3067); v2 control has NO divergence (v2-GPU/v2-CPU 118→162%, both track scene). **Work-normalized (bound-motors processed/wall-s = steps/s×bound): v2-GPU 4.7–6.1× AHEAD at EVERY scale — the crossover VANISHES.** Raw crossover ~4×; **work-normalized crossover: NONE in [1×,8×]**. **WHY v1-GPU under-binds:** `MyoMotor.bindTimer` is `static` (ONE global, `:73`), reset to 0 by any release (`MyoFilLink:315`), refractory gate `bindTimer<myoRebindTime(=1e-5=dt)` (`MyoMotor:455`) enforced SERIALLY on the host during the GPU bind-unpack (`GPUMotorBinding:~1840`) — the device `bindKernel` is purely geometric/first-hit (`:643-760`); rising releases/step gate out a growing fraction of candidates ⇒ widening deficit. = the documented BoA `bindTimer` static-global race (RESIDUAL_DOSSIER Part 2), a BoA concern NOT a v2 issue. **Thrust 2 — v2 per-graph scaling (re-measured, reproduces doc steps/s):** TOTAL **p=0.98 (HONESTLY LINEAR)**; dominant `fdFil` (62%@8×) p=0.98 dead-linear; only minor super-linear leans `fdXForm` (xlink formation broad-phase, p=1.11) + `fdBind` (motor reachable broad-phase, last-doubling 2.63×) — the real v2 high-density targets but NOT the crossover. **Verdict: (a) artifact, ~100%; faint (b) named but non-causal.** Report: `CROSSOVER_DIAGNOSIS_FINDINGS.md`. Instrument uncommitted (production byte-unaffected when `-pergraph` absent); BoA-v1ref/v1scratch byte-clean.

## 2026-06-24 — STANDARD 1× BENCHMARK: four-way (v1/v2 × CPU/GPU) sweep 1×/2×/4×/8×, MEASUREMENT-ONLY
**Parity gate PASSED** after 2 scene-param standardizations (NOT model edits): v2 myosin reach `REACH 0.025→0.006 µm` (v1 `Env.myoColTol`, was 4.2× over-reach ⇒ bound 1971 vs v1 ~550) + v2 crosslink on-rate `XLINK_ON_RATE 10→40` (v1 pf_1x, ⇒ pForm 0.00995→**0.0392 == v1**). All other ~30 params verified 1:1 (dt 1e-5, box ±3.5355, 64 mono/seg → 0.1755 µm segLen, crosslink reach 0.0108 µm ALREADY matched, aeta 0.1, 24 myo/node, deterministic bind, catch-slip kOff=100, 12 pN cap, treadmill OFF). Added `-reach`/`-xlonrate` CLI flags + flipped the two defaults. Post-fix **v2-CPU bound count tracks v1-CPU within ~10% at all scales** (506/915/1567/2757 vs 459/894/1611/3067) — work-matched. v1 measured via `BOA_STEP_PROFILE=1 BOA_PROFILE_WARMUP=50` (warmup-excluded ms/step); v2 harness excludes warmup natively. **Steady-state steps/s 1×/2×/4×/8×:** v1-CPU 30.4/18.7/10.9/5.86 · v1-GPU 21.4/17.1/12.8/8.4 · v2-CPU 15.3/7.6/3.8/1.8 · v2-GPU **49.5/26.7/13.5/6.6**. **Crossovers:** v1 GPU>CPU at 2–4× · v2-GPU always > v2-CPU (≥3.2×) · cross-engine v2-GPU vs v1-GPU cross at ~4×. **3 named leads (flagged, NOT chased):** (A) v2-CPU single-thread runner 2.0→3.3× slower than v1's 16-thread pool, gap WIDENS with scale; (B) v2-GPU's lead over v1-GPU erodes 2.31×→0.79× across scale; (C) v1's OWN CPU≢GPU binding diverges+widens (v1-GPU binds 53%→19% of v1-CPU @ 1×→8×) — v1-internal confound that flatters v1-GPU at scale. VRAM: v2-GPU 508 MiB(1×)/1420(8×) vs v1-GPU flat ~1.6–1.8 GB (v2 far leaner). All 16 cells MEASURED (none extrapolated); all stable/finite/conserving. Report: `STANDARD_BENCHMARK_4WAY_FINDINGS.md`. Scaled v1 PFs in `/tmp/v1_fdt_diag/pf_{2,4,8}x*`.

## 2026-06-24 — V2OneX GPU chained-split DONE (device-resident, all gates green): v2-GPU 50 steps/s
Branch `v2onex-gpu-split`, **Part 2 committed.** Wired `runGpu` to a device-resident 5-graph chained split (`buildPlanSplit`/`stepSplit` + `blkBind`/`blkStruct`/`blkFil`/`blkInteg`/`blkXForm` + `buildSplitScheduler` + `hostNodeCSR`/`hostSegCSR`), a faithful PORT of FullSystemDemo's split adapted to V2OneX's clean subset (ONE grid-bound node-shell motor population, crosslinkers ON, NO turnover/nucleation/free-minifilament). **Lowered on PTX the FIRST attempt** — no Graph-resize, no CUDA 701, no executeAlloc NPE (the `V2ONEX_GPU_FINDINGS.md` partition + GridScheduler re-keying were correct as written; the only iteration was adding the CPU≡GPU `-cmp`/`-brownoff` validation harness). Partition: G0 fdBind (publish+grid+reach+bind+cycle) · G1 fdStruct (zero/brown + joints/dimer/tether/node-gather/bond) · G2 fdFil (chain + seg-gather + xlink force/2-pass, the xlink link-state UPLOADER) · G3 fdInteg (confine/integrate/derive) · G4 fdXForm (device filID + crosslinker FORMATION, **cadence-gated SINK** t%100==0). **Gates (aorus RTX 5070):** (1) lowers clean; (2) device-resident — per-step host xfer = `mot.boundSeg` ≈38 KB (CSR-host) + render pulls at report cadence, NO full-state copy (`-devicecsr` also resident, 46.3 steps/s, CSR bit-identical); (3) **CPU≡GPU** via `-cmp -brownoff` — bound-set Δ ≤ 5/9600, coord Δ a BOUNDED chaotic plateau (1.2e-2→8.7e-2 µm over 200 steps, slow Lyapunov, no NaN/divergence — the accepted many-body op-ordering decorrelation); Brownian-ON bound-count trajectories match ±5/2141 (0.2%) over 1500 steps; (4) work parity — bound heads ±3–5 every checkpoint (CPU 2141 = GPU 2141 @ 1500), links same small-N regime; (5) **v2-GPU = 50.0 steps/s** (warm-excluded) = **3.6× v2-CPU 13.7, 2.3× v1-GPU 22** (four-way: v1-CPU 29 / v1-GPU 22 / v2-CPU 13.7 / v2-GPU 50.0); (6) disclosure honored (runGpu device-resident, no silent fallback). **No per-execute creep** (no fdNuc carrier; fdXForm a throttled SINK; steps/s flat 50.2→50.0 over 1500→2000). New `-cmp`/`-brownoff`/`-devicecsr` flags; FullSystemDemo + all validated paths untouched; no shared-kernel/physics edit. Report: `V2ONEX_GPU_FINDINGS.md` (gate table filled).

Last updated: 2026-06-24

## 2026-06-24 — V2OneX: IC parity fix DONE; GPU chained-split PLANNED (paused before behavioral commit)
Branch `v2onex-gpu-split`. **Part 1 (committed):** v2 IC now places filaments by v1's `makeRandomFilament` (two random box points → in-plane axis) — z-poke 1580→0/10000, box geometry already matched (v1 `rdmPtInside`=±boxXDim/2). Re-baseline v2-CPU 1× = **13.7 steps/s** (v1-CPU 29). **Part 2 (PAUSED, no behavioral commit):** the device-resident `runGpu` port is fully SPECIFIED (`V2ONEX_GPU_FINDINGS.md`) — 5 chained graphs fdBind·fdStruct·fdFil·fdInteg + gated fdXForm SINK (V2OneX = clean subset of FullSystemDemo's split, minus turnover/nucleation/minifilament; node-shell binding == the mot2 GRID path). Held back from a blind one-shot commit because the ≈80-buffer per-graph residency bookkeeping + GridScheduler keying + CPU≡GPU bit-validation need GPU-in-the-loop iteration (the template's own executeAlloc-NPE/CUDA-701 lessons), and the bail rule forbids committing unvalidated behavioral code. `runGpu` left unchanged (still discloses the blocker — gate-6 compliant, not silent). Next GPU-attached session: §'fast path'.

Last updated: 2026-06-24

## 2026-06-24 — NEW "1x" CONTRACTILITY BENCHMARK STANDARD (declared by jba) + v1 CPU/GPU baseline
**THE 1x SCENE (find it here):** a shallow-slab contractility test — **box 7.071×7.071×0.5 µm = 25 µm³**;
**400 protein nodes**, each carrying **24 singlet myosins** (`numNodeMyos:24`, `numNodeMyoDimers:0`) = **9600 myosins**;
**1000 filaments × 10 segments** (`minFilLength:1.72`/`maxFilLength:1.82`) ≈ **10000 segments** (balanced so #segs ≈ #myo
≈ 10k); **crosslinking ON** (`xLinkOnRate:40`, `xLinkConc:1.0`); **aeta=0.1**; **treadmilling/biochem OFF**
(`noMonomersSimd:true` — static IC filaments, NO formin nucleation); random placement (`rdmPtInside`). v1 PF:
`/tmp/v1_fdt_diag/pf_1x` (scratch build `/tmp/v1scratch` = BoA-v1ref + a ThreeJSWriter crosslink-emit addition;
BoA-v1ref byte-clean). **v1 baseline (10000 steps): CPU 29 steps/s (349.7 s, 2.25 GB); GPU 22 steps/s (457.0 s incl.
~40-60 s JIT warmup, 4.24 GB); GPU/CPU=0.77×.** **Crossover finding:** the v1 GPU device path is kernel-launch-bound at
this scale — scaling 250→1000 filaments barely moved GPU (29→22 steps/s, work nearly free) but cratered CPU
(180→29), so GPU/CPU climbed 0.16×→0.77×; "1x" sits AT the CPU/GPU crossover (GPU overtakes only at larger scale —
v1max 16× was GPU 386 vs CPU 52, ~7×). GPU runs fine with `noMonomersSimd:true` for this singlet-myosin/no-minifil
config (no `Graph resize`). CPU 516 vs GPU 324 crosslinks = expected float32/RNG-ordering divergence (aggregate, not
bit-identical). **v2 (SoftBox) 1x harness BUILT — `softbox.V2OneXHarness` + `run_1x.sh`** (new files only, no shared
edits; pure composition of validated subsystems): ONE shared `FilamentStore` of 1000 static IC chain filaments (10
seg, random pose, biochemically inert — no growth/depoly/aging/sever/nucleation) that BOTH the 400×24 node singlet
myosins bind (grid binding + CrossBridge + nucleotide cycle/stroke + node gather) AND crosslinkers link; containment;
aeta=0.1 (Constants default). Scene built EXACT: 400 nodes / 10000 segs / 9600 myo / 40000 xlink slots, box 25 µm³.
**v2 CPU baseline: 13.9 steps/s** (vs v1 CPU 29 → **v2 ~2× slower** — grid-binding + per-step formation overhead;
profiling follow-up). Stable, no NaN, binding climbs 235→930 heads (contractile). **Parity deviations flagged:** (a)
**filament orientation** — v2 uses uniform-random orientation; v1's `makeRandomFilament` places by two random
in-box endpoints ⇒ in-plane bias (≤~17° tilt in the 0.5 µm slab), so v2 has 1580/10000 segs poking past ±z (bounded)
where v1 fits the slab — fix for EXACT parity = match v1's endpoint placement; (b) segLen 0.1755 vs nominal 0.176
(integer-monomer, 0.3%); (c) crosslinks slow to form from random placement (0 @200 steps vs v1's 516 @10k — compare
at 10k); (d) **GPU path = TODO** (the FullSystemDemo `Graph resize` single-TaskGraph blocker; CPU is the v1-comparable
baseline). `run_1x.sh -cpu -steps N` (`-gpu` falls back to CPU w/ notice).

## 2026-06-24 — DILUTE single-free-body FDT diagnostic EXECUTED: v1 free bodies move at CORRECT FDT (gate PASSES)
Ran the recommended clean diagnostic on byte-clean `BoA-v1ref` (CPU, external `/tmp` PF — no repo edit): **1 free
filament (single 0.194 µm rod) + 1 free node** (`numNodeMyos=0`, bare sphere, **known D=5e-15** control) in a 3 µm
empty box at **aeta=1.0**, **treadmilling OFF** (`noMonomersSimd=1` rigid rods; all poly/depoly/aging/sever/nucleation/
crosslink rates 0), full Brownian. 30k steps, 1204 frames @0.25 ms. **RESULT — bare-amplitude probe (per-frame MSD,
1203 samples): filament 1.007× FDT, node 0.990×; node fit recovers its set D 5.22e-15 vs 5.00e-15 (1.04×) ⇒ validates
pipeline + v1 amplitude.** Filament tracks the node at every lag (long-lag MSD rollover appears in BOTH ⇒ single-traj
statistics, not a filament deficit). Rotational 0.23× = the deliberate `BRotCoeff=0.5` amplitude (by design). **VERDICT:
v1's free bodies move at correct translational FDT ⇒ the dense-scene sub-FDT (prior entry) was network CONFINEMENT,
confirmed by removing it ⇒ NOT a free-body suppression, no fix needed ⇒ go build the matched benchmark scene.** Frames
viewable: `threejs_output_v1fdt_diag` (sim_server). `BoA-v1ref` byte-clean; no code change. Report:
`V1_STRAIGHT_FILAMENT_FINDINGS.md` §D.

## 2026-06-24 — v1 two filament populations + free-body FDT@aeta=1.0 check: CONFOUNDED BY CONFINEMENT (observation-only)
Gate before scene-matching: do v1's genuinely FREE bodies move at correct FDT amplitude (aeta=1.0 fixed yardstick),
or are they suppressed? Measured from existing GPU render frames `/tmp/v1max/threejs_v1_16x_free/` (23 frames,
0.022 s; the named `threejs_output_v1_16x_diag` doesn't exist — this is the matching freemotion render). No new run,
no edits. **Measurement 1 — TWO populations CONFIRMED:** free-IC **6095/6219 (98 %)**, mean 0.191 µm, actively
treadmilling+splitting (segment count grows **1984→6219**); formin-nucleated **124 (2 %)**, short stubs (mean
0.066/median 0.011 µm), count matches `kNodeNuc·400·0.022≈88–124`. Both prior single-population readings were real.
**Measurement 2 — FDT check CONFOUNDED:** per-segment displacement is treadmill/split-dominated (free-IC full set
reads ~8× ABOVE FDT — artifact); the constant-length+isolated (uncrosslinkable, un-tethered) subset reads ~0.17×
FDT but is **survivorship-biased toward stuck filaments**. **Decisive control = the NODE** (`nodeTransDiff=5e-15`
set directly ⇒ amplitude correct *by construction*): it STILL reads **0.06× that D** with a **plateauing MSD**
(1.9→5.5 nm² over 1–8 ms vs free 30→240) ⇒ confined to a ~2 nm cage by its own network — so **"below FDT" is NOT
diagnostic of an amplitude bug here; sub-FDT motion is network CONFINEMENT**, plus aeta=1.0 making true FDT small
(~7 nm/1 ms). Also: **no free myosin population exists** — all 7200 myosins are node-anchored (`minifilaments:0`;
`onFil=0` = unbound-from-filament, still node-tethered). **VERDICT: NOT a demonstrable free-body suppression; gate
INCONCLUSIVE from these frames** (1 ms/22 ms resolution + dense network can't isolate the bare amplitude). Do NOT
declare/fix a bug. **Recommend (flagged, not run): a dilute single-free-body diagnostic** (1 filament + 1 node in an
empty box, aeta=1.0, dump EVERY step, MSD vs 6Dt) to read the bare amplitude cleanly. Category stays (c) by design
(short+confined) with the caveat that free-FDT amplitude is **unverified** at this resolution. No code change.
Report: `V1_STRAIGHT_FILAMENT_FINDINGS.md` (Addendum A–C).

## 2026-06-24 — Why are v1's "free" filaments straight while treadmilling? CODE READ (observation-only)
Question gated by two prior wrong inferences ⇒ code read, not a mechanism guess. Scene = `/tmp/v1max`
`v1max_16x_freemotion` GPU render (`BoxOfActin -r -gpu`). **Leading hypothesis ("GPU Brownian gate zeroes their
thermal scale") FALSIFIED.** `Env.brownianFilMotionOff` is **never set** (no PF key, no code assignment — `bFilOff`
always false); per-segment `f.brownianOff` is **benchmark-only** (`makeStraightChain` `:4002` + `-deflect` `:2917`,
re-derived complete). The GPU gate (`GPUMoveThing.java:6399-6433/6511-6525`) applies **full translational Brownian**
to every node-scene filament. **Scene-ground-truth correction (from PF + frame):** this is **NOT a free-filament
assay** — it's a 400-node formin (`forminsPerNode:6`, `kNodeNuc:10`, release 1/s) + crosslinker (`xLinkOnRate:40`,
`xLinkTransAttn:1.0`, `maxLinksOnSeg:10`) network; the 7200 myosins = 400 nodes × 18 motors (jba's hub observation
✓); 0 minifil; 6219 short segments (mean **0.188 µm** ≪ Lp~10 µm). **"Barely moving" = two BY-DESIGN constraints,
both faithful CPU+GPU:** (M1, dominant) formin/node attachment slaves the filament to a near-stationary node
(`nodeTransDiff:5e-15` ⇒ node RMS/frame `sqrt(2·5e-15·1e-3)≈3.2 nm`, matching the recon's 3–5 nm); (M2) crosslink
Brownian attenuation `1/(1+xLinkTransAttn·linkedToCt)` (`linkedToCt`=crosslinker degree, `FilSegment.java:626,635`
CPU / `:6519-6522` GPU). **84/16 split** = constrained (attached/crosslinked, barely move) vs uncrosslinked-free
(full FDT ~80 nm) — a boolean, not a uniform gate; and 80 nm is invisible at a 16 µm/0.022 s field. **"STRAIGHT"** =
short+stiff + **end-segments-only rotational Brownian** (`rScale=0` when `(filAtEnd1&&filAtEnd2)`=interior;
`filAtEnd*`="has a linked neighbour at that end", `:2818-2832`) — **this convention AGREES with v2**
(`DiffusionHarness.java:543-544`), NOT a divergence. **The ONE real v1↔v2 divergence: v2 OMITS the crosslink
Brownian attenuation** (v2's `filLinkCt` feeds only the force-law `fracMove`, never `brownTransScale/brownRotScale`)
⇒ v2's crosslinked filaments are thermally louder by design. **Category (c) BY DESIGN** (no flag tripped, no bug;
short + genuinely constrained). No code change. Report: `V1_STRAIGHT_FILAMENT_FINDINGS.md`.

## 2026-06-24 — CSR-host promoted to the PRODUCTION DEFAULT (re-validate + re-baseline)
Branch `cadence-gate-fdturn` (the probe work FF-merged onto it). jba signed off on making CSR-host the default; per
CLAUDE.md a default flip re-baselines prior validation numbers, so this is the full job (not a flag flip). **Default
now = full CSR-host:** (1) the STATIC node-attach CSR-inverse (`attachNode` fixed) is host-precomputed once,
UNCONDITIONAL (`hostNodeCSR`; the 3 device scans never built) — pure win ~+2–3 % all scales; (2) the DYNAMIC
node-shell seg-gather CSR (`boundSeg`-keyed) is host-built each step (`hostSegCSR`, from `boundSeg` pulled after
`fdBind`) + re-uploaded `EVERY_EXECUTION` into `fdFil`. **`-devicecsr`** reverts (2) to the device path (static (1)
stays — bit-identical to device, so `-devicecsr` reproduces the old default's RESULTS exactly); **`-megakernel`
stays OPT-IN.** **Crossover investigation (the load-bearing decision):** an isolated warm-session draw read the
dynamic part −4.7 % at 1× (the bail trigger), but a **controlled 3-config back-to-back** (old-device(97) / static-
only(94) / full(91), same thermal state/scale) showed the dynamic part **net-positive at EVERY tested scale**
(+3.5/+3.5/+8.6/+8.3/+5.9 % at 1/2/4/8/16×) — the −4.7 % was a thermal outlier, so the bail toward static-only was
NOT taken; full CSR-host ships as default. **Re-baseline (controlled, vs old full-device): +6.8/+7.2/+7.0/+10.5/+7.1 %
at 1/2/4/8/16×** ⇒ new-default v2 steps/s **67.7/47.9/30.4/16.9/9.0** (v2/v1 ≈ 0.86/0.69/0.54/0.45/0.45 — narrows but
doesn't reverse v1's lead; the §4(b) work asymmetry still dominates, confirmed by the megakernel probe). **Re-validated
on the new default AND `-devicecsr`:** CPU≡GPU AGREE (identical aggregate — CSR is pure-integer ⇒ host==device bit-for-
bit), conservation EXACT, 0 phantoms, no NaN; `-cpu` arithmetic unchanged (cpuStep always computed CSR host-side);
constituent spot-check (node harness) green; `BoA-v1ref` byte-clean. **Creep guard:** the `EVERY_EXECUTION` re-upload
is WITHIN the existing `fdFil` execute() (not a new execute) ⇒ no new per-execute creep carrier; 4000-step window
showed no anomalous decay (the §8 creep is ~0.005 % at that horizon, sub-noise). **Scale caveat (flagged):** the
dynamic round-trip's copy traffic grows ∝ scale (≈350 KB/step at 16×) — re-verify net-positive at ring-scale before a
very large run; `-devicecsr` is the escape hatch. `SCALE_SWEEP_FINDINGS`/`V1_MAXIMAL_BENCHMARK §3` carry re-baseline
banners. Report: `MEGAKERNEL_PROBE_FINDINGS.md` (UPDATE banner); log `RUN_LOGS/2026-06-24_csrhost_default_rebaseline.txt`.

---
Older increment history (through 2026-06-23) and the archived dt-convergence / canonical-collapse arc
(2026-06-24 dt-convergence study → 2026-07-08 canonical collapse; superseded by the capstone above)
live in JOURNAL_ARCHIVE.md.
