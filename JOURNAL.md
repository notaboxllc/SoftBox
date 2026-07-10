# Soft Box Project Journal

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
