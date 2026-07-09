# Phase-2 step 2 — the COMPLETE Config-1 cross-bridge architecture (PAIRS attachments + Hookean J1 load), native characterization (calibration DEFERRED)

**2026-06-27. Flag-gated (`-config1` / `-config1diag` on `GlidingHarness`, `-config1` on `CanonicalMotorHarness`);
default byte-identical.** Builds the COMPLETE composed cross-bridge (MOTOR_BENCHMARK_TARGETS §6) instead of
diagnosing the half-built two-F8 motor's thermal tail (`PHASE2_VERSIONB_BINDER_FINDINGS` §5). **No calibration** —
`kOn`, the catch constants, and κ are UNCALIBRATED starting values.

## TL;DR — the architecture change WORKS (tail vanishes, duty recovers, polarity corrects)
| quantity | two-F8 canonical (half-built) | **Config 1 (PAIRS + Hookean J1)** |
|---|---|---|
| J1-strain / lever-strain tail RMS | 8.9 pN | **2.5 pN** (−3.5×) |
| …max\|forceDotFil\| | 90–116 pN | **17.9 pN** (−5–6×; bounded, dt-invariant) |
| native avgBound (duty) | ~0.6 / 2000 (0.03 %) | **~18 / 2000 (~0.9 %)** (30× — the tail WAS the duty killer) |
| glide direction | +x (soft-spring sliding ARTIFACT) | **−x** (CORRECT — matches v1 + the default motor) |
| dt = 1e-5 stability | stable | **stable** (Hookean J1, NO cap, no overshoot) |
| step ∝ lever | slope ~1.0 | **slope ~1.0 (preserved)** |

The two SOFT translational F8 springs (doing attachment AND load-signal, with the Brownian head dragged by the
fast gliding filament) were the thermal-tail source. Config 1 splits the jobs: **PAIRS pins hold the head rigidly
(dt-robustly, reporting no load); the Hookean J1 both drives the stroke (rest 0°↔60°) and reports the load (its
deflection).** The lever sits between the rigid head and the rod — NOT dragged by the filament — so its thermal
motion against J1 is small ⇒ the tail collapses.

---

## The build (Config 1)
- **Tip + rear attachments → PAIRS form** (`CrossBridgeSystem.bondForcesCanonicalConfig1`): each attachment
  (head.end2→siteA `bindArc`, head.end1→siteB `bindArc2`) is the dt-robust damping-limited connection
  `fmag = fracMove·1e-6·strain/(dt·(mcHead+mcSeg))` (the actin-layer PAIRS magnitude, `moveC` reused VERBATIM),
  at the attachment point with the full positional torque R×F. Two pins HEAD_LEN apart pin the head's position AND
  orientation; roll about the head axis is a free decoupled DOF, so **F9 and F10 are both dropped**. PAIRS reports
  NO load ⇒ the §6 gating question ("can PAIRS expose the load?") is SIDESTEPPED. `fracMove = 0.5` (UNCALIBRATED).
- **J1 → Hookean torsional spring** (`MotorJointSystem.joints`, config1 branch, flag-gated by `jointParams` size
  ≥ 14): `T = κ·(θ − θ_rest)`, NO `/dt`, **NO stall cap** (the stall force EMERGES from κ × stroke geometry). The
  rest still switches 0°↔60° with nucleotide state ⇒ J1 still DRIVES the stroke; its deflection IS the compliance.
  `κ = k_tip·L² = 1e-3·(8e-9)² ≈ 6.4e-20 N·m/rad` (preserves the ~1 pN/nm effective tip stiffness; UNCALIBRATED).
- **forceDotFil (catch load) → the signed J1 lever strain**: `(κ/L)·(θ_rest − θ)`, the lever-tip-equivalent force
  of the J1 deflection, signed so resisting (θ held below the cocked rest) is POSITIVE (matches the old +0.285 pN
  isometric sign). PAIRS pins, J1 reports — PAIRS never needs to expose a force.
- Additive/flag-gated; device-agnostic; CSR-inverse gather reused VERBATIM; body-pose snap stays LATE in the
  gliding graph (the PHASE2_VERSIONB GPU rule). The prior two-F8 canonical (`-canonical`) stays reachable for the
  before/after comparison. Default (no flag) byte-identical.

## Characterization (thermal gliding, explicit Hookean J1 @ dt=1e-5, v1box 2000-motor bed; κ, kOn, catch UNCALIBRATED)

**1. Does the thermal tail VANISH? — YES.** J1-strain load over bound motors: mean +1.0, mean|·| 2.0, RMS **2.5**,
p99 6.5, max **17.9 pN** — vs the two-F8 RMS 8.9 / max 90–116. The catastrophic heavy tail is gone; the lever (not
dragged by the filament) barely strains J1.

**2. dt-scaling of the J1-strain tail — BENIGN (no catastrophic ∝1/√dt MAX blow-up).** Over dt = 1e-5 / 5e-6 / 2e-6
(12k steps each): **max\|forceDotFil\| BOUNDED + ~dt-invariant** = 17.9 / 17.1 / 20.2 pN. The mean/RMS rise modestly
(RMS 2.5 → 4.8 → 5.5) — partly the longer bond lifetime at smaller dt with the UNCALIBRATED catch, partly the
unequal sim-time window (same step count ⇒ smaller settled window at small dt), NOT the dt-arc thermal-velocity
catastrophe (which would blow the MAX ∝1/√dt — it does not). STABLE at every dt. ⇒ the dangerous tail is tamed.

**3. J1 Hookean stability at 1e-5 — STABLE at the operating lever; unstable only at sub-biological short levers.**
Bounded, no NaN, no overshoot over 12k steps at lever = 8 nm (the default) and over the head sweep. **Bail touched
+ reported:** at lever = 4 nm (below the biological range) the pure Hookean J1 (no cap) goes NaN — the §6 caveat-(a)
drag-arm-vs-stiffness-arm mismatch (tiny lever rotational drag vs κ ⇒ r_rot > 1). At lever ≥ 8 nm it is stable; the
banked implicit `1/(1+r)` torsional update is the known fix IF a shorter lever is ever needed — NOT pre-applied.

**4. Does it GLIDE — YES, −x, and the polarity is CORRECTED.** Steady velocity ≈ −1.5 to −5 µm/s (UNCALIBRATED
magnitude, below v1's 8.33), **direction −x** — matching v1 and the default motor (the two-F8 canonical glided +x).
**Kinematic polarity argument:** the filament plus-end is +x; the head binds lying along +x (tip toward plus-end),
rod hanging to the fixed anchor. The converter swing (0°→60° on ADPPi→ADP) drives the lever/tail; with the head
**rigidly pinned** (PAIRS), the reaction is transmitted faithfully ⇒ the filament glides minus-end-first (−x), the
myosin "walking" toward the plus-end — the textbook gliding polarity. **The two-F8 +x was a soft-spring ARTIFACT**
(the compliant translational springs let the head SLIDE under the stroke, inverting the transmitted reaction); the
rigid pins remove the slide ⇒ Config 1 recovers the physically-correct −x. The complete architecture fixes the
polarity, not just the tail.

**5. Step ∝ lever still HOLDS** (`CanonicalMotorHarness -config1`): unloaded tail stroke = 8.0 / 16.0 / 24.0 / 32.0 nm
at lever 8 / 16 / 24 / 32 nm (**slope ~1.0**), FLAT in head (0.000), J1 carries 100 % — identical to the two-F8
canonical (the powerstroke geometry is unchanged by the spring-law swap, as predicted). The lever = 4 nm row is the
§(3) short-lever NaN edge artifact.

**6. Duty ratio — now SENSIBLE; the tail WAS the duty problem.** avgBound ~18 / 2000 (~0.9 %), up 30× from the
two-F8's ~0.6 (0.03 %). Confirms the soft-spring thermal tail was driving spurious fast catch/slip release and
crushing the duty. Config 1 now **OVER-binds** (~2× v1's 7.6) — the residual is the UNCALIBRATED geometric/saturated
`kOn` (capture rate too high), which the NEXT step (reaction-limited kOn) addresses. The catch side is no longer the
duty bottleneck.

**7. dt stability / CPU≡GPU / default byte-identical.**
- dt: STABLE at 1e-5 (and 5e-6, 2e-6) at the operating lever.
- **CPU≡GPU** (chaotic gliding, aggregate-within-SEM): CPU avgBound **18.7 ± 1.0** (3-seed, full-history); GPU
  **~16.6** (instantaneous-sparse-sampled, noisier 12.7–21.4); both glide −x (5/6 ensemble runs) — agree within the
  chaotic envelope. (GPU avgBound is output-cadence-sampled hence noisy; a per-step pull would tighten it but breaks
  residency.) GPU device-resident TaskGraph builds + runs at ~636 steps/s.
- **Default byte-identical:** `run_stroke` gate-3 = 6.96 nm bit-for-bit; default gliding CPU −2.459 / 8.65 unchanged;
  `run_dimer` all-PASS (shared `MotorJointSystem` config1 branch is size-gated ⇒ byte-identical for every other
  harness). `BoA-v1ref` byte-clean.

## Bail boundaries — outcome
- **J1 Hookean unstable / overshoots at 1e-5** → did NOT happen at the operating lever (8 nm); HIT only at sub-
  biological lever 4 nm (NaN, §6 caveat-a) — **reported, implicit fix NOT pre-applied.**
- **Thermal tail does NOT vanish** → did NOT happen; it dropped 3.5–6× (RMS 8.9→2.5, max 116→18).
- **PAIRS can't hold the head rigidly** → did NOT happen; the head is rigidly pinned (the tail collapse + the
  corrected −x polarity both confirm the rigid pin).
- **Step ∝ lever broken** → did NOT happen (slope ~1.0 preserved).

## Calibration is the NEXT step (deferred)
Config-1 native behavior is sound (tail gentle + bounded, stable, glides −x correctly, step∝lever holds). NEXT: the
reaction-limited **kOn** calibration (the duty now over-binds on the uncalibrated geometric kOn) + the catch
re-calibration against the J1-strain signal + the κ value (and the velocity magnitude). NOT here.

## What changed (additive; default byte-identical; `BoA-v1ref` untouched)
- `CrossBridgeSystem.java`: **new** `bondForcesCanonicalConfig1` (PAIRS attachments + J1-strain forceDotFil) + a
  private `moveC` (VERBATIM from MotorJointSystem). The default `bondForces`/`bondForcesCanonical` byte-unchanged.
- `MotorJointSystem.java`: a size-gated **config1 Hookean-J1 branch** (κ·deflection, no cap). Byte-identical for
  `jointParams` size ≤ 12 (every existing harness).
- `MotorStore.java`: **new** `enableConfig1(κ)` → `jointParamsC1` (size-14, Hookean J1). Default `jointParams`
  byte-unchanged.
- `GlidingHarness.java`: `-config1`/`-config1diag` (PAIRS+Hookean-J1 path, CPU + GPU), `-kappa` override; the
  `-canondiag` tail report extended (p99 + two-F8 before/after).
- `CanonicalMotorHarness.java`: `-config1` (the step∝lever re-confirm).
- `run_canongliding.sh`: `-config1` documented.
```
./run_canongliding.sh -config1diag 12000      # Config-1 native characterization (tail, duty, glide, polarity)
./run_canongliding.sh -config1 -gpu 6000      # GPU device-resident probe (CPU≡GPU)
./run_canonical.sh -config1                   # step∝lever re-confirm
./run_canongliding.sh -config1diag -dt 5e-6 12000   # dt-scaling of the J1-strain tail
```
