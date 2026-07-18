# Reach / preload / in-segment binding sensitivity — explicit motor (`explicit-s2-l40`)

**Follow-on to `EXPLICIT_BINDING_RESOLUTION_FINDINGS.md`** (which found angular gates non-limiting and reach/preload +
in-segment placement the dominant recruitment filters). **Objective:** quantify how preload, distance, and in-segment
placement thresholds control recruitment and gliding, and identify a modest, defensible change that shifts the density
curve left WITHOUT degrading attachment quality or high-density mechanics.

**Method:** the deterministic CPU runner (byte-faithful free-binding sequence of `stepGlidingCPU`; the UNMODIFIED
`matBindExplicit` decision) + a GPU device-resident path for the high-density velocity panel. The harness exposes ONLY
three diagnostic parameters (`-rbind`, `-preload`, `-segmargin`) overriding `bindP[0]/[4]/[10]`; **canonical defaults
UNCHANGED** (this is a calibration study, no production change). Angular gates, chemistry, detachment, stroke, beam
physics, timestep, solver — all fixed. New file only (`ExplicitBindDiagHarness` + drivers); `BoA-v1ref` byte-clean;
production untouched; **0 invalid states / 0 solver failures across all runs**.

---

## Headline
**Reducing ONLY the in-segment margin (0.05 → ~0.0125 µm) raises recruitment +57–79 % (meanBound 2.85→4.48 @ρ700,
0.77→1.24 @ρ200) with onset-force quality UNCHANGED (F8 p99 7.16→7.11 pN), continuity improved, 0 invalid.** It
recovers physically-valid mid-filament binding sites that the discretization end-margin artificially excluded — a
low-mechanical-cost lever. **Preload is NOT a clean lever** (loosening degrades onset force — p99 up to 8.26 — and
barely/does-not raise recruitment; tightening cuts it). **Distance is a pure no-op** (the 2 pN preload gate caps
`conDist<2.0 nm`, always tighter than the distance gate's `conDist<6.5 nm`, so distance never binds).

---

## §1 — Frozen binding contract (deliverable 1)
Dumped live from the default scene (`-mode contract`; full text `RUN_LOGS/binddiag/CONTRACT.md`). Gate arithmetic
`TwoBodyBeamAnalyticGpu.matBindExplicit` L412–461; thresholds `bindP` set `ExplicitCompleteMatHarness.packExMat`
L76–77. **Deterministic — NO binding RNG.** Bind update sets ONLY `boundSeg=s`, `bindArc=bindArcV` (beam pose
continuous).

| gate | value | meaning |
|--|--|--|
| distance `bindP[0]` | **3.0 nm** | `surf=(conDist−FIL_R)·1e3 < 3` ⇔ `conDist < 6.5 nm` |
| psi/phi/theta `bindP[1..3]` | 25/25/20° | angular (fixed this study) |
| **preload `bindP[4]`** | **2.0 pN** | `preload=kF8·conDist·1e12 < 2` ⇔ **`conDist < 2.0 nm`** |
| energy `bindP[5]` | 15 kT | torsional attach energy |
| FIL_R `bindP[6]` | 0.0035 µm | filament radius |
| head-side `bindP[8]` | 0.00225 µm | `headSide < 2.25 nm` |
| **segMargin `bindP[10]`** | **0.05 µm** | `bindArc ∈ (margin, 2·half−margin)`; half=0.08775 µm ⇒ window [50,125.5] nm = **43 % of segment** |

**The two DISTINCT spring quantities (task §1):** the gate `preload` and the realized bond `F8` are computed with the
**same spring constant** (`kF8 = myoSpring = 1.0e-9` code), but at **different geometry**: `preload = kF8·conDist`
uses the PRE-bind F8-to-actin-axis distance (`<2 nm` ⇒ preload gate); the realized **onset F8 = myoSpring·(head-tip −
bound-site)** is computed AFTER `matPlaceHeadExplicit` + `matCock`, and the post-cock tip extension is ~6.3 nm (⇒ onset
F8 ≈ 6.3 pN, NOT the 2 pN gate value). So the 2 pN "preload" gate is a pre-bind reach proxy, NOT the realized bond
force. **Release/detachment:** `cycleLymnTaylor` catch-slip (Guo–Guilford); the 12-pN break cap `kinParams[12]` is
**OFF** in the gliding default (breakForce 1.2e-11 N present but disabled).

## §2 — Parameterized diagnostic (deliverable 2)
`ExplicitBindDiagHarness` `-rbind <nm>` / `-preload <pN>` / `-segmargin <µm>` override exactly `bindP[0]/[4]/[10]`;
defaults 3.0/2.0/0.05. No duplicated binding logic (same `matBindExplicit` on CPU and, uploaded FIRST_EXECUTION, on
GPU — verified: preload=0.1 nm ⇒ meanBound→0.1 on device). Every output records the resolved parameters + N + window.

## §3/§4 — One-at-a-time sensitivity + recruitment (deliverables 3, 4, 5, 6)
Recruitment assay, production dt, 3 seeds, 5 ms equil + 15 ms measure. `newlyAdm` = binds failing the DEFAULT contract
(25/25/20 & 3 nm & 2 pN & 0.05 µm) admitted by the looser setting. onset F8 = realized bond force (pN).

### Preload (pN) — `di3.0 sm0.05` fixed
| preload | ρ200 bindRate (fold) | ρ200 onsetF8 med/p99 | ρ700 bindRate (fold) | ρ700 onsetF8 med/p99 | ρ700 newlyAdm |
|--:|--:|--:|--:|--:|--:|
| 1.0 | 1.33 (0.82×) | 6.26 / 6.58 | 1.41 (0.92×) | 6.25 / 6.62 | 0.0 |
| 1.5 | 1.63 (1.00×) | 6.25 / 6.75 | 1.54 (1.00×) | 6.23 / 6.89 | 0.0 |
| **2.0 (def)** | 1.63 (1.00×) | 6.40 / 7.11 | 1.54 (1.00×) | 6.33 / 7.19 | 0.0 |
| 2.5 | 1.56 (0.95×) | 6.58 / 7.28 | 1.51 (0.98×) | 6.46 / 7.35 | 23.5 |
| 3.0 | 1.63 (1.00×) | 6.79 / 7.80 | 1.59 (1.03×) | 6.67 / 7.69 | 36.0 |
| 4.0 | 1.37 (0.84×) | 7.22 / 8.23 | 1.75 (1.13×) | 7.05 / 8.26 | 51.5 |

**Preload verdict:** loosening gives NO recruitment gain at ρ200 (non-monotonic, peaks at default) and only ~+13 % at
ρ700/4pN — but at **every** loosening step the onset-force distribution shifts up (p99 7.1→8.3, med 6.3→7.2) and
`newlyAdm` climbs (0→51), i.e. more, worse-geometry attachments. Tightening (1 pN) cuts recruitment. **Not a clean
lever.**

### Distance (nm) — `pl2.0 sm0.05` fixed — a pure NO-OP
Every value 2.0→5.0 nm yields **byte-identical** recruitment at BOTH densities (ρ200 bindRate 1.630, ρ700 1.630;
meanBound / onset F8 / newlyAdm=0 identical); only `loo_preload` shifts. **Reason:** the 2 pN preload gate caps
`conDist<2.0 nm`, always tighter than distance's `conDist<5.5–8.5 nm` — distance never sole-binds. **Not a useful lever
(loosening); redundant with preload.**

### In-segment margin (µm) — `rBind3.0 pl2.0` fixed — THE LEVER
| segMargin | ρ200 bindRate (fold) | ρ200 meanBound | ρ200 onsetF8 p99 | ρ700 bindRate (fold) | ρ700 meanBound | ρ700 onsetF8 p99 | ρ700 continuity |
|--:|--:|--:|--:|--:|--:|--:|--:|
| **0.05 (def)** | 1.63 (1.00×) | 0.77 | 7.11 | 1.63 (1.00×) | 2.85 | 7.16 | 0.962 |
| 0.0375 | 1.93 (1.18×) | 0.92 | 7.05 | 1.92 (1.18×) | 3.11 | 7.16 | 0.963 |
| 0.025 | 2.17 (1.33×) | 1.04 | 7.21 | 2.44 (1.50×) | 3.94 | 7.19 | 0.996 |
| **0.0125** | 2.56 (1.57×) | 1.24 | 7.15 | 2.91 (1.79×) | 4.48 | 7.11 | 0.996 |
| 0.0 | 2.44 (1.50×) | 1.06 | 7.26 | 3.06 (1.88×) | 4.84 | 7.13 | 1.000 |

**In-segment verdict:** reducing the margin **monotonically raises recruitment** (bindRate +57–79 % at 0.0125,
meanBound +57–61 %) while **onset F8 stays flat (p99 7.1–7.2 unchanged)**, continuity stable/improved, lifetime stable,
0 invalid. The extra binds are physically-valid mid-filament sites (near the discretization segment joints) the margin
artificially excluded — SAME reach quality (`conDist<2 nm` preload still enforced). **Sweet spot ~0.0125:** at ρ200,
margin=0 is slightly *worse* than 0.0125 (continuity 0.70 vs 0.81 — binding exactly on a joint is marginally less
stable), so a small nonzero margin keeps a joint buffer while opening the segment. **The clean, low-mechanical-cost
recruitment lever.**

## §5 — Attachment-quality distributions (deliverable 7) + §E guard
Across ALL settings: `immediateDetach = 0`, `invalid = 0`, no solver failures. Onset torque ~0 at bind (pre-stroke),
XB extension tracks onset F8 (≈6.3 nm), attach (torsional) energy 0.3–0.5 kT, lifetime 240–370 steps, dragFrac
0.34–0.44 (minority-opposing fraction, stable). **The only setting family that shifts the onset-force / attach-energy
distributions up is PRELOAD loosening** (p99 F8 7.1→8.3, attachE up, newlyAdm up). **Reduced in-segment margin does
NOT** — its newly-admitted binds are mechanically indistinguishable from default (onset F8 p99 flat). No setting admits
immediate-detach or dragging-dominated attachments.

## §7 — Selected two-parameter combinations (deliverable 8)
| combo | rBind/preload/segMargin | ρ200 meanBound (bindRate) | ρ700 meanBound (bindRate) | onset F8 p99 | newlyAdm ρ700 | note |
|--|--|--:|--:|--:|--:|--|
| default | 3.0/2.0/0.05 | 0.77 (1.63) | 2.85 (1.63) | 7.23 | 0 | baseline |
| **c1 margin-only** | 3.0/2.0/**0.0125** | **1.24 (2.56)** | **4.48 (2.91)** | **7.21** | 50 | **clean winner — +57–78 %, onset F8 unchanged** |
| c2 margin+preload | 3.0/2.5/0.025 | 1.27 (2.41) | 4.40 (2.67) | 7.54 | 65 | preload adds an onset-force cost, no extra recruitment |
| c3 distance (control) | **4.0**/2.0/0.05 | 0.77 (1.63) | 2.85 (1.63) | 7.24 | 0 | **byte-identical to default — no-op confirmed** |
| c4 aggressive | 3.0/**3.0**/**0.0** | 1.39 (2.67) | 5.40 (3.39) | 7.78 | 98 | most recruitment but onset F8 p99 up (preload cost) + joint-binding |

**Combination rationale:** c1 (in-segment margin alone) captures nearly all the recruitment gain of the aggressive c4
at NO onset-force cost (p99 7.21 vs default 7.23); adding preload (c2/c4) only degrades onset force without extra
benefit; distance (c3) does nothing. **c1 = the recommended change.**

## §8 — Reduced gliding density panel (deliverable 9)
Signed velocity (centroid-x LS slope, µm/s; negative = correct pointed-leading glide) + meanBound + continuity, 3
seeds. **CPU (deterministic) 100–700; GPU (device-resident) 1500/3000** for the plateau. **0 invalid across all runs.**

| density | default −v (mB, cont) | **c1 sm0.0125** −v (mB, cont) | sm0.025 −v (mB, cont) |
|--:|--:|--:|--:|
| 100 | +0.15 (0.3, 0.24) | **−0.14 (0.6, 0.42)** | −0.06 (0.5, 0.41) |
| 200 | −0.45 (0.7, 0.46) | **−0.71 (1.1, 0.67)** | −1.09 (1.0, 0.65) |
| 400 | −1.27 (1.4, 0.77) | **−1.94 (2.4, 0.90)** | −1.78 (2.0, 0.88) |
| 700 | −2.16 (2.8, 0.94) | **−2.43 (4.2, 0.98)** | −2.70 (4.1, 0.96) |
| 1500 (GPU) | −3.09 (5.3, 0.99) | **−3.35 (8.3, 1.00)** | −3.57 (8.0, 1.00) |
| 3000 (GPU) | −4.14 (11.6, 1.00) | **−3.83 (18.3, 1.00)** | −4.11 (16.0, 1.00) |

- **Left-shift confirmed at every density 100–1500:** the candidate glides FASTER, at HIGHER occupancy, with BETTER
  continuity (d400: −1.94 vs −1.27 µm/s, mB 2.4 vs 1.4, cont 0.90 vs 0.77). The onset of reliable gliding moves left —
  default first clearly glides at ρ200, the candidate already glides at ρ100.
- **High-density plateau PRESERVED:** at ρ3000 the velocity is the SAME for all three (−4.14 / −3.83 / −4.11 µm/s ≈
  the explicit −4 µm/s plateau from `EXPLICIT_DENSITY_SWEEP`), even though the candidate's occupancy is much higher
  (mB 18.3 vs 11.6). At high density velocity is Vmax/cycle-limited (the `force-velocity-ceiling` regime), so the extra
  bound motors from the extra sites do NOT raise the plateau — **the curve shifts LEFT, not up.**
- dragFrac candidate ≈ default (0.40→0.41, negligible), continuity ≥ default everywhere, 0 invalid.
- _(GPU 1500/3000 velocities carry the documented gliding bistability; used here for the DIFFERENCE default-vs-candidate
  at matched seeds and the plateau level — all three settings agreeing at ~−4 µm/s makes the plateau-preservation robust,
  and meanBound — far less basin-sensitive — cleanly separates candidate 18.3 from default 11.6.)_

## §9/§10 — Saturation shift + overfitting guard (deliverable 10)
**§9 main criterion — MET:** the candidate (in-segment margin 0.0125) shifts the density response LEFT (higher
continuity + speed at 100–700; reliable gliding at lower density; saturation approached at lower density) while
PRESERVING the ρ3000 plateau speed (~−4 µm/s), the working stroke / load-bearing mechanics (unchanged — no stroke/beam
edit), and the onset-force / attach-energy distributions (onset F8 p99 7.1–7.2, flat), with no rise in invalid states
or solver failures.

**§10 overfitting guard:** the change is a SINGLE parameter (in-segment margin) motivated by a mechanistic cause (it
recovers physically-valid mid-filament sites the discretization end-margin excluded), NOT fitted to a target
saturation density. **Observables used to justify it (calibration):** recruitment (meanBound/bindRate) + gliding
velocity + continuity. **Independent observables that were NOT tuned and are preserved (validation):** onset-force
distribution (flat), attachment lifetime (240–370 steps, stable), attach energy (0.3–0.5 kT), high-density plateau
velocity (~−4 µm/s), dragFrac (~0.41), dt-dependence (§11 — not worsened), transient geometry (no stroke/beam change).
No single saturation number was targeted; the candidate is preferred because it improves recruitment across the whole
low-density range at zero mechanical cost.

## §11 — dt cross-check (deliverable 11) — the candidate does NOT worsen dt-dependence
ρ700, recruit assay, 3 seeds, matched physical duration at dt=2.5e-6 and dt/2.

| config | dt | bindRate | meanBound | continuity | onset F8 p99 | invalid |
|--|--:|--:|--:|--:|--:|--:|
| default (0.05) | dt | 1.630 | 2.854 | 0.962 | 7.16 | 0 |
| default (0.05) | dt/2 | 1.831 (**+12 %**) | 3.036 (+6 %) | 0.977 | 7.13 | 0 |
| **c1 (0.0125)** | dt | 2.910 | 4.477 | 0.996 | 7.11 | 0 |
| **c1 (0.0125)** | dt/2 | 2.899 (**−0.4 %**) | 4.956 (+11 %) | 0.997 | 7.25 | 0 |

**Verdict:** the candidate's **bind flux is MORE dt-robust** than the default (dt→dt/2 change −0.4 % vs +12 %);
meanBound dt-sensitivity is comparable (+11 % vs +6 %); onset F8 p99 and continuity are stable across dt for both; 0
invalid. The reduced margin does **not** introduce a stronger timestep dependence — it slightly reduces the bind-flux
dt-sensitivity documented in the resolution study (by making binding site-limited rather than reach-transit-limited).

---

## §12 — Decision (deliverable 12)
| parameter | classification |
|--|--|
| **distance** | **NOT A USEFUL LEVER** (no-op — preload subsumes it) |
| **preload** | **USEFUL BUT CHANGES ATTACHMENT MECHANICS** (loosening degrades onset force; small/no recruitment gain) |
| **in-segment margin** | **USEFUL WITH LOW MECHANICAL COST** (+57–79 % recruitment, onset force unchanged, continuity improved) |

**Recommendation: reduce ONLY the in-segment margin (0.05 → ~0.0125 µm).** Keep preload (2 pN) and distance (3 nm)
unchanged. This is the modest, defensible change that shifts recruitment up by recovering physically-valid binding
sites, with no onset-force / energy / lifetime / stability degradation. Do NOT relax preload (it trades onset-force
quality for little recruitment) and do NOT touch distance (inert). **All confirmations passed:** §8 shows the density
curve shifts LEFT (faster + higher occupancy + better continuity at 100–1500) with the ρ3000 plateau PRESERVED
(~−4 µm/s); §11 shows the candidate does not worsen (slightly improves) the dt-dependence; the quality guard is clean
(onset F8 flat, 0 immediate-detach, 0 invalid, dragFrac unchanged). **Adoption of the reduced margin as a canonical
default is a separate, sanctioned decision** — this study establishes it is safe and beneficial; the canonical defaults
remain UNCHANGED here.

## §13/§14 — commands, seeds, files (deliverables 13, 14)
```
./scripts/build.sh
./scripts/run_binddiag.sh -mode contract                      # §1 frozen contract
./scripts/binddiag_run_reach.sh                                # §3/§4 one-at-a-time (102 runs)
./scripts/binddiag_run_combo.sh                                # §7 combos (24 runs)
EQ=12000 MS=20000 ./scripts/binddiag_run_glide8.sh             # §8 density panel (CPU 100-700 + GPU 1500/3000)
./scripts/binddiag_run_dtcheck.sh                              # §11 dt cross-check
python3 scripts/binddiag_reach_aggregate.py                    # → tables
```
seeds {101,202,303}; recruit windows 5 ms equil / 15 ms measure @ dt=2.5e-6; densities 200/700 (one-at-a-time),
100–3000 (§8). **Modified/new files:** `softbox/ExplicitBindDiagHarness.java` (+`-rbind/-preload/-segmargin/-gpu/
contract` modes, enriched recruit/glide), `scripts/run_binddiag.sh`, `scripts/binddiag_run_{reach,combo,glide8,dtcheck}.sh`,
`scripts/binddiag_reach_aggregate.py`. No shared system / production file touched. **Commit:** _[on request]_.

## Final status block
- `DOMINANT RECRUITMENT LEVER:` **the in-segment placement margin** (`bindP[10]`) — LOO 5464 @ρ700; reducing it
  0.05→0.0125 µm gives +57–79 % recruitment.
- `PRELOAD SENSITIVITY:` non-monotonic, weak on recruitment (≤+13 % @ρ700/4pN, ≤−18 % tightening); **every loosening
  step raises onset F8 (p99 7.1→8.3) + admits worse-geometry binds.** A quality knob, not a clean recruitment lever.
- `DISTANCE SENSITIVITY:` **none — byte-identical 2→5 nm** (preload caps conDist tighter; distance never sole-binds).
- `ATTACHMENT-QUALITY IMPACT:` reduced margin = **NONE** (onset F8 p99 flat 7.1–7.2, 0 immediate-detach, 0 invalid,
  continuity ↑); preload loosening = **onset-force + energy distributions shift up.**
- `LOW-DENSITY GLIDING SHIFT:` **LEFT-SHIFTED** — candidate glides faster + higher occupancy + better continuity at
  every ρ100–1500 (ρ400: −1.94 vs −1.27 µm/s, mB 2.4 vs 1.4, cont 0.90 vs 0.77); reliable gliding onset moves ρ200→ρ100.
- `HIGH-DENSITY PLATEAU PRESERVED:` **YES** — ρ3000 velocity ~−4 µm/s for default and candidate alike (−4.14 vs −3.83),
  despite higher candidate occupancy (mB 18.3 vs 11.6): the curve shifts LEFT, not up (Vmax-limited plateau).
- `RECOMMENDED THRESHOLDS:` **rBind 3.0 (unchanged), preload 2.0 (unchanged), segMargin 0.05 → ~0.0125 µm** (the sole
  beneficial, low-cost change).
- `NEXT STEP:` a single-change adoption decision for the in-segment margin (0.0125 µm) as canonical default — safe +
  beneficial per §8/§10/§11; do NOT open a preload/distance calibration campaign (preload trades quality for little,
  distance is inert).
```
