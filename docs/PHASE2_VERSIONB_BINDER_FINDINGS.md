# Phase-2 step 1 — the DYNAMIC Version-B two-point binder in the live thermal gliding assay (calibration DEFERRED)

**2026-06-27. Flag-gated (`-canonical` / `-canondiag` on `GlidingHarness`); default byte-identical.** Wires the
**Version-B** two-point binder (JOURNAL 2026-06-27 decision) + `CrossBridgeSystem.bondForcesCanonical` into the
gliding assay **with thermal forces ON**, and characterizes what it natively does. **No calibration** — `kOn`
(geometric/saturated) and the catch constants/signal are UNCHANGED (reaction-limited `kOn` + catch-vs-lever-strain
are the NEXT phase-2 steps, informed by the native binding rate measured here).

## TL;DR
- **Dynamic two-point capture DOES form on the fly under thermal load** — motors tip-capture, collapse to the
  along-filament two-point pose, stroke (J1), and release. **~82–84 % of tip-captures form** the two-point pose;
  failures are **100 % the same-segment rear-runs-off-the-minus-end** case (mean fail tip-arc ≈ 10 nm < HEAD_LEN
  20 nm), clustering at each segment's e1, at the expected ≈ HEAD_LEN/segLen ≈ 11 % rate (the same-segment-only
  restriction; rear-on-a-neighbour is the flagged follow-on).
- **Legal-pose check PASS** (no gates-vs-canonical-pose inconsistency): the snapped along-filament head has
  `motDotFil = 1.0000 ≥ alignTol (−0.40)`, **0 violations**. The existing orientation gates ACCEPT the canonical
  pose.
- **Snap magnitude REPORTED:** head rotation at snap **mean ≈ 34°, range [1.5°, 113.5°]** (perpendicular→along-fil,
  up to ~90–113°); rear(J1-pivot) displacement **mean ≈ 11 nm, max ≈ 33 nm** — a transient on J1's *connection*
  spring (the fracMove damping-limited, dt-robust spring), NOT on the stiff cross-bridge. **The bind itself is
  GENTLE along the filament:** both F8 springs sit ⟂ the axis ⇒ `|forceDotFil|` at bind ≈ 0.0001 pN (the catch
  reads ~0 load at formation). `|net F8|` at bind ≈ 8 pN is the BOUNDED perpendicular pin (≤ 2·myoSpring·myoColTol
  = 12 pN).
- **It GLIDES (directed motility):** steady **+x ≈ 2.9–3.5 µm/s** (uncalibrated), **opposite the default motor's
  −x** — a consequence of the re-rigged stroke polarity (F9 removed, J1-driven lever). The mechanism drives motility.
- **THE KEY OPEN QUESTION — dt-gentleness under thermal load: only PARTIALLY survives. REPORTED (not fixed).**
  Phase-1's gentle +0.285 pN lever-strain was measured Brownian-OFF (deterministic pinned head). With thermal
  forces ON the bound head Brownian-wanders against the SOFT (1 pN/nm) two-point springs, so the lever-strain
  signal has a **heavy tail: mean ≈ −0.8 pN, mean|·| ≈ 5.6 pN, RMS ≈ 8.9 pN, max|·| ≈ 90–116 pN.** The two-point
  geometric pinning keeps the MEAN gentle but thermal fluctuation reintroduces large instantaneous excursions. This
  is the bail-boundary outcome ("report, don't fix"); it informs whether to deploy the banked implicit/sub-step
  tools at calibration time.
- **dt = 1e-5 STABLE** under thermal load (bounded, no NaN, 12k steps); also stable at 2e-5. **CPU≡GPU agree**
  (net velocity matches to printed precision; avgBound 0.64/0.644, within SEM). **Default byte-identical**
  (`run_stroke` gate-3 = 6.96 nm bit-for-bit; default gliding CPU −2.459/8.65 and GPU −x/8.60 unchanged; phase-1
  `run_canonical` all-green).

---

## The build (Version B)

The single-point tip search is **RETAINED VERBATIM** (the same `reachTestDistSq` gates as `bindNearest`: α-foot,
`conDist < myoColTol`, `motDotFil ≥ alignTol`, `rodDotFil ≥ 0`). The second (rear/J1-pivot) anchor is a CONSEQUENCE
of the rigid canonical head, not a second search:

1. **Tip capture** (existing search fires) → compute the **along-filament pose**: the head will lie ∥ the segment
   with the tip at site A (`bindArc`, the captured tip arc) and the rear at site B (`bindArc2 = bindArc − HEAD_LEN`,
   toward e1 — head must point +seg.uVec to pass `motDotFil ≥ alignTol`).
2. **Formability** = the rear lands on the SAME bound segment (`bindArc2 ≥ 0` and `bindArc ≤ segLen`). The
   rear-on-a-neighbour case is flagged out of scope (the follow-on); a terminal-segment rear runs off the end.
3. **Formable → Version-B immediate collapse:** register BOTH bonds (`bindArc` tip + `bindArc2` rear) in the same
   step AND snap the head to the along-filament pose (no single-point dwell, so the old head-pivot mechanism can
   never leak in at bind time). **Not formable → bind fails, search continues.**
4. **Release breaks both** (the existing canonical behavior — `boundSeg < 0`).

**The snap geometry.** The captured head is perpendicular (gliding-bed pose). The snap places the flat head so BOTH
point-springs sit at the captured perpendicular distance `conDist` (tip barely moves; `|F8a|=|F8b|=myoSpring·conDist`
≤ myoSpring·myoColTol, and `forceDotFil = (F8a+F8b)·segU ≈ 0` since both springs are ⟂ the axis ⇒ a gentle bind, no
along-filament load transient). The head's rear (J1 pivot) moves ~HEAD_LEN onto the axis ⇒ a transient on J1's
connection spring (fracMove damping-limited, dt-robust). `head.yVec = perpHat` (⟂ segU; F10 relaxes the roll).

### GPU-safety: the snap must run LATE (a load-bearing device-path finding)
The first build did the snap INSIDE the decision kernel (writing `b.coord/uVec/yVec` for the bound head). On the
**PTX backend this silently broke binding** (GPU avgBound **0.0000**, CPU 0.95). Bisected: search + formability +
the small `boundSeg/bindArc/bindArc2` writes lower fine (GPU avgBound 0.81); the breakage is triggered by a
**body-pose-writing task placed EARLY in the gliding graph** (before the force/integrate tasks). Splitting the snap
into its own small kernel did NOT fix it while the task stayed early — **the trigger is the graph POSITION, not the
kernel size or the body-write per se** (a no-op snap task placed early also zeroed binding; the decision kernel
alone binds). **Fix: place the `snapHead` task LATE — after `deriveMot`, like `integrate`** (where `integrate`/
`derive` already write the body fine). The snap therefore takes effect on the NEXT step's bond (a ≤1-step timing
delay). **The CPU runner snaps at the SAME late point** so CPU and GPU have identical snap timing (and CPU≡GPU
tracks to printed precision). Rule for future device work: **a body-pose-writing task must sit LATE in the gliding
TaskGraph (after the body integrate/derive), never before the force-accumulation tasks** — an early body-write task
silently breaks the PTX-lowered binding.

Two GPU-safe kernels:
- `BindingDetectionSystem.bindCanonicalTwoPoint` (DECISION): search + formability → writes `boundSeg/bindArc/bindArc2`
  + the per-motor `MotorStore.canonSnap` flag. 14 args.
- `BindingDetectionSystem.snapCanonicalHead` (SNAP): for `canonSnap==1` motors, rotate+translate the head sub-body
  along the segment, clear the flag. 11 args. Runs late.

Both race-free (each motor owns its head slot + bound-state), no atomics/KernelContext, device-agnostic.

## Characterization (`-canondiag`, thermal gliding, explicit Hookean F8 @ dt=1e-5, myoSpring=1 pN/nm; v1box 2000-motor bed)

| # | question | result |
|---|---|---|
| 1 | dynamic two-point capture forms live? | **YES** — 1488 formations / 1822 tip-captures over 12k steps |
| 2 | formation success + where failures cluster | **81.7 %** success; failures **100 % rear-off-minus-end** (mean fail tip-arc 10.5 nm < HEAD_LEN), ≈ HEAD_LEN/segLen 11.4 % expected |
| 1-chk | legal-pose under the gates | `motDotFil = 1.0000 ≥ −0.40`, **0 violations** — gates ACCEPT the pose |
| 2-chk | snap magnitude / transient | head rot mean 34°, max 113.5°; rear disp mean 11 nm, max 33.5 nm; bind `|forceDotFil|` ≈ 0.0001 pN (gentle); `|net F8|` ≤ 12 pN (bounded perpendicular pin) |
| 3 | native binding rate / avgBound (UNCALIBRATED) | **avgBound ≈ 0.6–1.0** / 2000 motors (≈ 0.05 % duty); formations/step ≈ 0.12. **LOW**, not over-binding — the thermal lever-strain tail (#5) drives fast catch/slip release ⇒ low duty (a coupled finding feeding the deferred kOn+catch calibration) |
| 4 | does it glide? | **YES — directed +x ≈ 2.9–3.5 µm/s** (opposite the default −x; uncalibrated) |
| 5 | dt-gentleness under THERMAL load (the key question) | **PARTIAL** — mean −0.8 pN gentle, but heavy tail: RMS 8.9 pN, **max\|·\| 90–116 pN** vs phase-1 deterministic +0.285 pN. The soft 1 pN/nm springs let the Brownian head reintroduce large instantaneous lever-strain spikes |
| 6 | dt stability | **STABLE** at 1e-5 (12k steps, no NaN) and 2e-5 |
| — | CPU≡GPU | net velocity matches to printed precision; avgBound 0.64/0.644 (within SEM) |
| — | default byte-identical | `run_stroke` 6.96 nm bit-for-bit; default gliding CPU −2.459/8.65 + GPU −x/8.60 unchanged; phase-1 `run_canonical` all-green |

## Bail boundaries — outcome
- **Gates reject the along-filament pose** → did NOT happen (motDotFil = 1.0, 0 violations).
- **Two-point formation rate very low** → did NOT happen (~82 % on a straight filament; failures only at segment ends).
- **dt-gentleness does NOT survive thermal forces** → **PARTIALLY HIT + REPORTED** (mean gentle, but max\|forceDotFil\|
  ~90–116 pN under thermal load — the soft-spring + Brownian-head tail; reported, NOT fixed — the implicit/sub-step
  tools are banked, deploy decided at calibration time).
- **Bind-time snap transient large/unbounded** → **REPORTED, bounded:** ~34° mean (up to 113°) head rotation, ~11 nm
  (max 33 nm) rear displacement; the along-filament load at bind is ~0 (gentle), the J1 connection-spring jolt is on
  the dt-robust fracMove spring.

## Calibration is the NEXT phase-2 step (deferred, NOT here)
Informed by the native binding rate above (low duty driven by the thermal lever-strain tail): the **reaction-limited
kOn** calibration and the **catch re-calibration against the lever-strain `forceDotFil`** — and the decision whether
to deploy the banked implicit/sub-step cross-bridge for the #5 thermal tail. The +x vs −x glide sign and the
rear-on-a-neighbour formation gap are also calibration-time reconciliations.

## What changed (additive; default byte-identical; `BoA-v1ref` untouched)
- `BindingDetectionSystem.java`: **new** `bindCanonicalTwoPoint` (decision) + `snapCanonicalHead` (the GPU-safe split).
- `MotorStore.java`: **new** `canonSnap` flag array (allocated/zeroed; read only by the canonical path).
- `GlidingHarness.java`: `-canonical` (swap bind→two-point + bond→canonical, CPU `stepOrig` + GPU `buildPlan`, snap
  placed LATE) and `-canondiag` (the native-binder characterization). Default (no flag) byte-unchanged.
- `run_canongliding.sh`: convenience wrapper.
```
./run_canongliding.sh -canondiag 12000        # native-binder characterization
./run_canongliding.sh -canonical 6000         # CPU glide probe
./run_canongliding.sh -canonical -gpu 6000    # GPU device-resident probe (CPU≡GPU)
```
