# Binding-search timestep resolution + angular-gate sensitivity — DIAGNOSTIC study

**Model:** canonical explicit `explicit-s2-l40` (MD-informed S2 beam, L=40 nm), the persistent device-resident mat.
**Runner:** the deterministic CPU runner (host-side per-motor diagnostics; the exact free-binding kernel sequence
of `ExplicitCompleteMatHarness.stepGlidingCPU`, byte-faithful — the same `matBeamGeom`→`matBindExplicit`→…→
`matS2SolveStep` order). **No physics / salt / chemistry / gate change.** The binding decision is the UNMODIFIED
`matBindExplicit`; the angular panel only re-parameterizes the already-parameterized `bindP` thresholds
(phi/psi/theta degrees). New file only (`ExplicitBindDiagHarness` + `scripts/run_binddiag.sh` + drivers);
`BoA-v1ref` byte-clean; production untouched; canonical defaults UNCHANGED (this is a diagnostic/calibration study).

**Production binding contract (verified in code, `TwoBodyBeamAnalyticGpu.matBindExplicit` L412–461):** deterministic
once all EIGHT gates pass — distance `surf<3 nm`, `psiErr<25°`, `phiErr<25°`, `thetaErr<20°`, `preload<2 pN`,
`E<15 kT`, `headSide<aSemiZ`, `bindArc` in-segment (margin 0.05 µm); NO binding RNG. dt=2.5e-6 s.

**Scenes:** free-binding explicit gliding mat, single 12-segment actin track over an N-motor lawn (box 3.0×1.0 µm).
density 200 → N=600, density 700 → N=2100. 3 seeds each. dt ÷ {1,2,4} at matched physical duration.

---

## Headline result (one line)
**At production thresholds the three ANGULAR gates are essentially non-binding for recruitment** — over 2.6×10⁷
eligible motor-steps at density 700 the leave-one-out sole-failure count is phi=0, psi=1, theta=4 (vs preload 4679,
in-segment 5464). Recruitment is limited by **cross-bridge REACH (the distance-coupled `preload<2 pN` gate) and the
in-segment placement gate**, not by orientation. Moving the angular gates 0.6×–2.0× shifts binding rate by ≤±7%
(noise-level) and admits ≈0 new attachments. **Separately**, production dt modestly UNDER-samples binding: bind flux
rises ~+12 % and steady occupancy ~+8 % from dt→dt/2, converging by dt/2 (a bounded, dt/2-resolved effect, in the
known cross-bridge-substep family — not an angular-gate issue).

---

## Part A — unbound motor-domain per-step increments (deliverable 1, 2)
Per eligible unbound ADP·Pi motor, lab-frame displacement of the beam-derived F8/head/beam-end between consecutive
production steps (density-independent — pure single-motor thermal/configurational motion; density 200 ≡ 700 to 3 sig
figs). Filament advection separated. **Normalized by each gate width** in the `/gate` column.

| quantity | rms | med | p90 | p95 | p99 | max | rms/gate | p95/gate | p99/gate |
|--|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| **F8 disp (nm)** | 1.89 | 1.59 | 2.80 | 3.21 | 4.07 | 14.1 | **0.63** | 1.07 | 1.36 |
| head disp (nm) | 1.82 | 1.54 | 2.68 | 3.06 | 3.85 | 14.2 | 0.61 | 1.02 | 1.28 |
| beam-end/P disp (nm) | 1.42 | 1.19 | 2.13 | 2.44 | 3.06 | 14.2 | — | — | — |
| node0/clamp disp (nm) | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | (clamped end ≈0 ✓) | | |
| F8 ∥seg (nm) | 1.12 | 0.75 | 1.85 | 2.22 | 2.98 | 13.6 | 0.37 | 0.74 | 0.99 |
| F8 ⊥seg (nm) | 1.52 | 1.23 | 2.32 | 2.68 | 3.40 | 7.1 | 0.51 | 0.89 | 1.14 |
| **\|Δphi\| (°)** | 7.47 | 5.05 | 12.3 | 14.7 | 19.3 | 40.2 | 0.30 | 0.59 | 0.77 |
| \|Δpsi\| (°) | 3.32 | 2.23 | 5.44 | 6.48 | 8.55 | 18.5 | 0.13 | 0.26 | 0.34 |
| **\|Δtheta\| (°)** (θ:=ψ−φ) | 7.08 | 4.77 | 11.6 | 13.9 | 18.2 | 36.8 | 0.35 | **0.69** | **0.91** |
| Δ near-seg dist (nm) | 1.15 | 0.77 | 1.88 | 2.25 | 2.97 | 6.4 | 0.38 | 0.75 | 0.99 |
| — fil seg advection (nm) | 1.26 | 1.11 | 1.83 | 2.05 | 2.50 | 4.3 | (deterministic glide, separated) | | |
| — fil signed-x advection (nm) | 0.85 | 0.00 | 1.07 | 1.39 | 1.98 | 3.5 | (≈0 mean at low occupancy) | | |

- **Typical head step at production dt ≈ 1.6 nm (median), 1.9 nm rms = 0.63× the 3 nm distance gate;** p95 3.2 nm just
  reaches the gate, p99 4.1 nm exceeds it. So a single production step CAN traverse the distance gate, but only in the
  tail.
- The **theta coordinate moves fastest relative to its gate** (p95 0.69×, p99 0.91× of the 20° theta gate — the
  tightest gate meets the fastest coordinate). phi p99 0.77×; psi is slow (p99 0.34×).
- nearest-segment identity changes on **5.8 %** of increments (the motor sits between segments of the single track).
- Increments shrink with finer dt (F8 rms 1.89→1.58→1.27 nm at dt÷1/2/4; \|Δtheta\| 7.08→6.41→5.59°) — genuine
  sub-resolvable steps (mixed Brownian sqrt(dt) + deterministic relaxation).

## Part B — between-step missed binding opportunities (deliverable 3, 4)
Substep diagnostic: within each production interval, re-evaluate the full 8-gate geometry at fractions 1/4, 1/2, 3/4
from **interpolated generalized coordinates + interpolated filament pose** (near-gate intervals only — a >25 nm-out
motor cannot reach 3 nm mid-step). An interval is *substep-only* if neither production endpoint passes all gates but
an interior fraction does. `missedFraction = substepOnly / (endpointDetected + substepOnly)`.

| density | dt÷ | eligible steps | binds (flux) | substep-only | missedFraction | **flux vs dt÷1** |
|--:|--:|--:|--:|--:|--:|--:|
| 200 | 1 | 1.49e7 | 65 | 5 | 0.071 | 1.000× |
| 200 | 2 | 2.97e7 | 94 | 4 | 0.031 | 1.446× (noisy) |
| 200 | 4 | 5.94e7 | 85 | 5 | 0.057 | 1.308× (noisy) |
| **700** | **1** | 2.61e7 | **154** | 11 | **0.068** | **1.000×** |
| **700** | **2** | 5.20e7 | **173** | 11 | 0.058 | **1.123×** |
| **700** | **4** | 1.04e8 | **174** | 12 | 0.059 | **1.130×** |

- **The reliable measure is the actual bind flux** (density 700, ~154 binds/window): **+12 % from dt→dt/2, then flat
  dt/2→dt/4 (1.123→1.130)** — production dt undersamples binding flux by ~12 %, fully resolved by a single halving.
  meanBound (occupancy) tracks it: 2.85 (dt1) → 3.04 (dt2) → 3.11 (dt4) ≈ **+8 % converged**.
- **The interpolation-based missedFraction is ~6 % but dt-INVARIANT** (6.7 / 5.8 / 5.9 % at dt÷1/2/4). Its flatness
  means it is NOT the "sub-dt event that finer dt resolves" signal — it is a persistent ~6 % of near-gate intervals
  where the *linear* interpolation path dips through the gate box; part is interpolation geometry, not recoverable
  binds. So the flux (+12 %, dt/2-converged) is the trustworthy undersampling number, and missedFraction is an
  upper-ish companion.
- density 200 flux is small-count noisy (65/94/85 binds); density 700 is the trustworthy tier.
- **The substep-only misses are gated by preload/in-segment, NEVER angular** (endpoint-miss gate tally over the
  substep-only intervals: preload 2, in-segment 1, angular 0). Consistent with Part C.

**Episode persistence:** because binding is **deterministic**, every all-pass endpoint binds in the SAME step — no
motor is all-pass for 2 consecutive steps, so successful-encounter "episode duration" is definitionally 1 production
step (100 % <1 step). The meaningful persistence is in *near-gate* encounters: near-miss (exactly one gate failing
within 20 % of its width) occurs 28× per 8.7×10⁶ eligible-steps — transient. There is no reservoir of multi-step
near-gate dwells that production dt is stepping over.

## Part C — gate-by-gate bottleneck accounting (deliverable 5, 6)
Over all eligible unbound ADP·Pi motor-steps at production dt (3 seeds summed). Cumulative funnel in gate order;
leave-one-out = motor-steps where that gate is the SOLE failure (= extra binds admitted if only it were removed).

**density 700 (2.61×10⁷ eligible motor-steps):**

| gate | cumulative pass | cum % | marginal pass % | **leave-one-out (sole-fail)** |
|--|--:|--:|--:|--:|
| distance (<3 nm) | 57905 | 0.2222 % | 0.222 % | 0 |
| + phi (<25°) | 57905 | 0.2222 % | 100.0 % | **0** |
| + psi (<25°) | 57099 | 0.2191 % | 98.66 % | **1** |
| + theta (<20°) | 56645 | 0.2174 % | 98.17 % | **4** |
| + **preload (<2 pN)** | **5619** | 0.0216 % | 0.023 % | **4679** |
| + energy (<15 kT) | 5619 | 0.0216 % | 100.0 % | 0 |
| + headSide | 5618 | 0.0216 % | 63.6 % | 0 |
| + **in-segment** | **154** | 0.0006 % | 40.8 % | **5464** |

density 200 is identical in shape (LOO: preload 2218, in-segment 2420, phi/psi/theta 0/0/1).

- **Two gates do essentially all the rejecting: the distance-coupled `preload<2 pN` reach gate** (cuts the
  distance-survivors 56645→5619, ~10×; 4679 sole-failures) **and the in-segment placement gate** (cuts 5619→154,
  ~36×; 5464 sole-failures). Both are geometric-reach/placement, not orientation.
- **The angular gates are non-binding:** among motors already within reach, phi passes 100 %, psi 98.7 %, theta 98.2 %
  marginally; their sole-failure LOO is 0/1/4 (density 700) — removing any one angular gate admits ~0 extra binds.
- The **distance gate itself passes only 0.22 %** of eligible steps — the first, dominant filter is simply *being
  close enough*; `preload` then tightens the same conDist axis (2 pN / kF8 < 3 nm), and in-segment removes the ~57 %
  of segment length inside the 0.05 µm end-margins.
- Conditional pass probabilities: P(preload | dist·angles) ≈ 5619/56645 = **9.9 %**; P(in-seg | reach) ≈
  154/5619 = **2.7 %**; P(all-angles | dist) = 56645/57905 = **97.8 %**.

## Part D — angular-gate sensitivity matrix (deliverable 7, 8) + admitted-attachment quality (deliverable 9)
Recruitment assay (15 ms physical window, production dt, 3 seeds), every non-angular gate FIXED. Symmetric panel
0.6×–2.0× + one-at-a-time phi/psi/theta at 0.8/1.2/1.5. `newlyAdmitted` = binds that fail the DEFAULT (25/25/20)
angular gates but pass the looser setting.

**density 700 (bindRate = binds / motor / s; onset F8 = realized cross-bridge force at the first bound step):**

| setting | φ/ψ/θ | binds | meanBound | bindRate | newlyAdmitted | onset F8 med (pN) | onset F8 p99 | immed-detach |
|--|--|--:|--:|--:|--:|--:|--:|--:|
| sym0.6 | 15/15/12 | 48.7 | 2.40 | 1.545 | 0.0 | 6.43 | 7.17 | 0 |
| sym0.8 | 20/20/16 | 50.0 | 2.62 | 1.587 | 0.0 | 6.34 | 7.12 | 0 |
| **sym1.0 (default)** | 25/25/20 | 51.3 | 2.85 | 1.630 | 0.0 | 6.31 | 7.16 | 0 |
| sym1.2 | 30/30/24 | 47.7 | 2.42 | 1.513 | 1.7 | 6.41 | 7.22 | 0 |
| sym1.5 | 38/38/30 | 50.0 | 2.50 | 1.587 | 2.0 | 6.39 | 7.20 | 0 |
| sym2.0 | 50/50/40 | 50.0 | 2.50 | 1.587 | 2.0 | 6.35 | 7.20 | 0 |
| theta1.5 | 25/25/30 | 49.7 | 2.53 | 1.577 | 1.3 | 6.35 | 7.16 | 0 |

- **bindRate fold-change vs default (3 seeds): sym0.6 0.95×, sym0.8/1.5/2.0 0.97×, sym1.2 0.93× — all within ±7 %,
  non-monotonic ⇒ chaotic-trajectory NOISE, not a systematic shift.** Loosening 1.0×→2.0× admits at most 2 of ~50 binds
  (`newlyAdmitted` ≤2); tightening to 0.6× costs ~5 %. **psi0.8/1.2/1.5 are byte-identical to default** (1.630, 6.31 —
  loosening admits nothing).
- **phi-only and psi-only are byte-identical to default** (loosening admits nothing ⇒ identical deterministic
  trajectory: phi1.2≡phi1.5, psi0.8≡psi1.2≡psi1.5). **theta is the only marginally-active gate** (theta1.2/1.5 admit
  ~1 new bind; the only nonzero LOO in Part C).
- **Quality of newly-admitted attachments (Part E, deliverable 9):** onset F8 stays 6.2–6.6 pN and p99 ≤7.2 pN across
  ALL settings; immediate-detach = 0 everywhere; no solver failures, 0 invalid states. **Loosening the angular gates
  does NOT admit high-onset-force, high-energy, dragging, or immediately-detaching attachments** — the few extra binds
  (theta/sym≥1.2) are mechanically indistinguishable from the default population. (This is expected: the admitted set
  is preload/reach-limited, so the marginal angular candidates still satisfy the tight reach gate.)
- **Guard verdict (Part E):** no setting increases recruitment primarily by admitting mechanically-poor attachments —
  because no setting materially increases recruitment at all.

## Part D2 — reduced gliding velocity panel (deliverable 10)
Rough onset velocity (centroid-x LS slope; negative = pointed-leading = correct glide), selected settings, 3 seeds,
15k equil + 25k measured production-dt steps. **Not the final recalibrated density sweep.**

| setting | d100 (below onset) | d200 | d400 |
|--|--:|--:|--:|
| sym0.8 (20/20/16) | +0.13 (mB 0.36) | −0.90 (mB 0.74) | −1.47 (mB 1.29) |
| sym1.0 (default) | +0.13 (mB 0.33) | −0.65 (mB 0.80) | −1.32 (mB 1.41) |
| sym1.5 (38/38/30) | +0.08 (mB 0.42) | −0.61 (mB 0.83) | −1.39 (mB 1.40) |
| theta1.5 (25/25/30) | +0.15 (mB 0.38) | −0.67 (mB 0.77) | −1.36 (mB 1.40) |

- density 100 is **below gliding onset** — velocity sign varies by seed (meanBound <0.5, Brownian-dominated). Gliding
  turns clearly negative by density 200 (−0.6…−0.9 µm/s) and faster at 400 (−1.3…−1.5, mB ~1.4) — a clean, monotone
  density trend.
- **Velocity is angular-setting-invariant within seed noise at every density** (d400 spans −1.32…−1.47 across all four
  settings) — loosening (sym1.5, theta1.5) gives trajectories byte-identical to default (same binds, same slope),
  because the looser gates admit nothing new. Only *tightening* (sym0.8) perturbs it (chaotic divergence), not a
  systematic velocity shift.
- (d700 tier is a long-tail confirmatory CPU run; 100→400 already establishes onset density + angular-invariance +
  the monotone trend — d700 adds only density-trend context, not a new conclusion.)

---

## Part F — decision (deliverable 11)

**Temporal resolution classification:** the letter of the criteria (missedFraction >5 %, flux rises at smaller dt) →
**MINOR-to-MATERIAL undersampling (borderline)**. The trustworthy measure (density-700 actual bind flux) is **+12 %
from dt→dt/2, occupancy +8 %, and CONVERGED by dt/2** (dt/4 adds nothing). This is a bounded, single-halving-resolved
effect in the already-documented cross-bridge-substep / faithful-dt-ceiling family — NOT a coarse-search pathology and
NOT an angular-gate issue. The substep-interpolation missedFraction (~6 %, dt-invariant) is partly a
linear-path-crossing artifact and over-states the recoverable fraction.

**Each angular modification:**
- **phi gate:** fold-change in bindRate ≈1.00× across 0.8–1.5×; **zero** sole-failure LOO; loosening byte-identical.
  → **not a recruitment lever.**
- **psi gate:** ≈1.00× across 0.8–1.5×; LOO 0–1; loosening byte-identical. → **not a recruitment lever.**
- **theta gate:** the only marginally-active one — LOO 1–4, loosening admits ~1 bind, bindRate +2 % — still
  negligible and within seed noise. → **weakest limiter; a small potential knob, not a needed one.**
- **All three, quality:** no onset-force, energy, dragging, immediate-detach, or solver-health degradation at any
  setting (Part E).

**Recommendation:**
- **Keep production dt for the search itself** — the unbound motor-domain step (~1.6 nm/step) samples the gate volume
  adequately; there is NO reservoir of multi-step near-gate dwells being stepped over, and successful encounters bind
  in one step. The modest (~12 %) bind-flux / (~8 %) occupancy undersampling is a **cross-bridge / kinetics** dt effect
  (bind-then-immediately-churn), already tracked to the standing **cross-bridge substep** recommendation — address it
  there if/when the substep lands, not by refining the geometric search. dt/2 already converges it.
- **Keep all three angular gates at their current thresholds.** They are non-binding for recruitment; tightening buys
  a little specificity at ≤7 % recruitment cost, loosening buys essentially nothing. No change is warranted to shift
  recruitment or gliding onset.
- **A larger density-recalibration campaign keyed on the ANGULAR gates is NOT justified** — angular gates are not the
  recruitment limiter. **If** low-density recruitment is to be increased, the levers are the **reach (`preload<2 pN` /
  distance) and in-segment placement** gates (LOO 4679 / 5464), NOT orientation — that is where a future calibration
  campaign should point (out of scope here: the task fixed those gates).

---

## Final status block

- `PRODUCTION-DT SEARCH RESOLUTION:` **ADEQUATE for the geometric search; MINOR-to-MATERIAL (borderline) for bind
  flux** — actual bind flux +12 % / occupancy +8 % from dt→dt/2, CONVERGED by dt/2; substep-interpolation
  missedFraction ~6 % (dt-invariant, partly artifact). A bounded, dt/2-resolved kinetics effect, not a search pathology.
- `TYPICAL HEAD STEP AT PRODUCTION DT:` **~1.6 nm median, 1.9 nm rms (0.63× the 3 nm distance gate); p99 ~4 nm.**
  Fastest coordinate vs its gate = **theta (p99 0.91× of 20°).**
- `SUBSTEP-ONLY BINDING OPPORTUNITIES:` **rare — 11 per ~165 detected binds at density 700 (missedFraction 6.7 %),
  dt-invariant; all preload/in-segment-gated, never angular.** Actual finer-dt binds rise only ~12 % and plateau at
  dt/2.
- `DOMINANT BINDING GATE:` **cross-bridge REACH (`preload<2 pN`, LOO 4679) and IN-SEGMENT placement (LOO 5464)** — the
  distance-coupled geometric gates. **NOT the angular gates** (phi/psi/theta LOO 0/1/4).
- `ANGULAR-GATE SENSITIVITY:` **negligible — bindRate within ±7 % (noise) over 0.6×–2.0×; loosening admits ≈0 new binds
  (phi/psi byte-identical); theta marginally the most active (+2 %).**
- `QUALITY OF NEWLY ADMITTED ATTACHMENTS:` **indistinguishable from default — onset F8 6.2–6.6 pN, p99 ≤7.2 pN,
  0 immediate-detach, 0 solver failures, 0 invalid at every setting.** No mechanically-poor attachments admitted.
- `RECOMMENDED BINDING SETTINGS:` **keep production dt; keep phi/psi/theta = 25/25/20 unchanged.** No angular change
  shifts recruitment or gliding onset meaningfully.
- `NEXT STEP:` **no angular recalibration campaign.** If recruitment is to be raised, target the reach/in-segment
  gates (not orientation); the ~12 % dt bind-flux undersampling belongs to the standing cross-bridge-substep work
  (dt/2-resolved), not the geometric search.

## Reproduce
```
./scripts/build.sh
./scripts/run_binddiag.sh -mode abc     -density 700 -seed 101 -dtdiv 1 -equilms 5 -measms 15   # Part A/B/C one config
./scripts/binddiag_run_abc.sh       # Part A/B/C matrix: density{200,700}×seed{101,202,303}×dt÷{1,2,4}
./scripts/binddiag_run_recruit.sh   # Part D1/E angular panel (15 settings × 2 densities × 3 seeds)
./scripts/binddiag_run_glide.sh     # Part D2 reduced velocity panel
python3 scripts/binddiag_aggregate.py   # → RUN_LOGS/binddiag/AGGREGATED.md
```
