# Soft Box Project Journal

Last updated: 2026-06-13

## 2026-06-13 — Increment 2b: filament characterization toolkit (manual tuning)
Ported v1's filament-characterization MEASUREMENT side (deflection ratio, relaxation time τ,
persistence length Lp) as a manual-tuning instrument + the BRotCoeff fidelity fix. **The auto-tune /
coefficient-search loop was deliberately NOT ported** (v1 DeflectionTuner*/the `eitherTunerActive`
block — cleanly separable; left in v1 for a later decision). Lp and τ are instruments validated
against v1's *measurement*, not biological-target gates.

**BRotCoeff fidelity fix.** v1 applies rotational Brownian only to chain-end segments (≥1 free end)
scaled by BRotCoeff=0.5 (interior=0; FilSegment.moveThing:633-642, `if(!filAtEnd1|!filAtEnd2)`,
Env.java:583). v2 chain/Lp paths now use `interior?0:BRotCoeff` (was 1.0) — completes the 2a interior-
vs-end correction. Free chain bend RMS 3.54°→1.98° (less end jitter, matches v1's appearance). Static
deflection ratio unaffected (Brownian off) and **inc-1 FDT still PASS** (it uses bare 1.0, not
Constants.BRotCoeff) — both re-verified.

**τ (DeflectionSupport / -deflect).** Load → steady → release (counts[3]=0 gates extForce in
seedAccumulators, no buffer re-upload) → 1/e crossing of the decay (log-interpolated) = τ_meas;
τ_theo = N·ζ_perp·span³/(EI·π⁴), ζ_perp=midSeg bTransGam.y (port of v1 BoxOfActin.java:2933). Result:
τ_theo=0.05697 s (v1 prints 0.057 — exact, same formula); τ_meas=0.05652 s, **τ_meas/τ_theo=0.992**.

**Lp (-lp / measureLp).** Port of v1 accumulateLpData + computeLpMeas: free Brownian 539-seg/48-µm
chain (matches v1 testLpFilLength=48, monomerCt=32 — both `static final`, so v2 must match), tangent
correlation C(k)=⟨u_i·u_{i+k}⟩ EWMA(α), Lp=−1/slope of weighted (w=C²) log-fit over C_k>0.01.

**Unified entry point (-characterize):** one command → `{deflection ratio, τ_meas/τ_theo, Lp_meas}`
for the current coeffs (override fracR/fmt via flags, BRotCoeff via Constants). ~40 s. Example output:
ratio 0.9983, τ_meas/τ_theo 0.9920, Lp_meas 1441 µm.

**Cross-validation vs v1 (fixtures/filament_characterization_v1.md):**
- ratio: ≤0.05% across fracR (TIGHT, from the deflection-benchmark session).
- τ: τ_theo exact; τ_meas/τ_theo=0.992. (v1's τ_meas needs an interactive force-release, not headlessly
  capturable; the deterministic relaxation is pinned by the ≤0.04% static-ratio match.)
- Lp: the **C(s) measurement reproduces v1 to <0.05%** (C(1) 0.9987 vs 0.9989, C(538) 0.7366 vs 0.7370
  at fmt=0.265 — proving instrument + physics faithful). BUT the **scalar Lp_meas is ill-conditioned**
  (v1 785 µm vs v2 1441 µm, ~2×): the uncalibrated chain is far stiffer than its 48-µm contour, so C
  barely decays and 1/slope is noise-dominated — intrinsic to the metric (present in v1; why v1 has the
  auto-tune and treats Lp as a diagnostic). NOT a port bug (C(s) match proves it); NOT bailed. Lp is a
  faithfully-ported diagnostic, not a tightly-reproducing scalar at uncalibrated coeffs.

So: ratio + τ are tight quantitative cross-checks; Lp's C(s) is tight, its scalar is an honest
ill-conditioned diagnostic. Manual-tuning instrument complete; auto-tune deferred.

Open / next: increment 3 (spatial grid + broad-phase). Still deferred: the auto-tune loop (planner
decision), and whether to expose aeta/segment-length as -characterize flags (currently fracR/fmt only).

## 2026-06-13 — Deflection benchmark: v2 ≡ v1 force/torque coding (+ low-fracR float32 fix)
Validated the 2a chain force law against v1's deflection benchmark, settled a fracR-direction
puzzle, and found+fixed a float32 precision limit at very low fracR (stiff filaments). This is the
foundation of 2b (pins + load); the full ratio/τ/LP fixture is still 2b.

**Setup (replicates v1 -bmDiag exactly).** `softbox/DeflectionSupport.java` (seedAccumulators puts the
load on the midpoint forceSum; pinEndpoints does v1's `incCoord(anchor-endpoint)` hard endpoint
snap-back each step -> pinned-pinned, free rotation) + `runDeflection` (-deflect flag). 11 seg ×
32-mon (segLen 0.0891 µm, span 0.9801), Brownian off, F = 48·EI·frac/span² on the midpoint center,
EI = kT·Lp (Constants.EI, Lp=15 µm), frac=0.01. v1 built read-only to /tmp/v1classes (worktree never
touched). Measured obs = perpendicular distance of the midpoint center from the anchor line, averaged
over the converged 2nd half (jitter quantified — both v1 and v2 are steady, ≤1.3% pk-pk at default
coeffs; jitter is parameter-dependent in general).

**fracR direction — RESOLVED: bigger fracR = softer (jba was right).** v1 deflection ratio rises
0.392→0.998→2.190→2.777 as fracR 0.025→0.1→0.4→0.8 (the loaded benchmark; v1's Env.java:135 "bigger
= stiffer" comment is misleading). The earlier free-chain sweep looked flat/opposite ONLY because
interior rotational Brownian (the 2a-FIX bug) swamped the fracR signal; post-fix the free chain
softens with fracR too (v2 3.50°@0.1 → 6.23°@0.8), matching v1's free LP chain (2.71°→9.83°). No sign
error — fracR enters only via the (byte-identical) F3 lever torque; in a *free* chain its effect is
weak (link forces are tiny without a load), strong under *load*.

**Identical-coding proof + low-fracR float32 limit + fix.** v2 reproduces v1's deflection ratio:
| fracR | v1 | v2 acos(dot) | v2 asin(\|cross\|) poly |
|---|---|---|---|
| 0.025 | 0.39198 | 0.40038 (2.1%) | **0.39184 (0.04%)** |
| 0.1 | 0.99842 | 0.99986 (0.14%) | **0.99831 (0.01%)** |
| 0.4 | 2.19003 | 2.19046 (0.02%) | **2.18990 (0.006%)** |
| 0.8 | 2.77652 | 2.77681 (0.01%) | **2.77639 (0.005%)** |
With the original `acos(dot)` bending-angle calc, v2 matched v1 to ≤0.14% for fracR≥0.1 but drifted
to 2.1% at fracR=0.025 — a real, converged gap growing as fracR→0. Root cause: **float32 catastrophic
cancellation in acos(dot)** for small joint angles (cos t = 1 − t²/2, so the angle lives in the
cancelling 1−dot part; ~half the digits lost). Fix (`ChainBendingForceSystem.angleFromSinCos`):
recover the angle from `|cross| = sin t ~ t` (first-order, float32-safe) via a hand-rolled
`accurateAsin` (Taylor seed + 2 Newton passes — PTX has no Math.asin/atan2, same reason v1 hand-rolled
accurateAcos; verified Math.atan2 throws "unimplemented" on the PTX backend). Hybrid: asin(|cross|)
for small angles (|cross|≤|dot|), accurateAcos(dot) mid-range. Result: low-fracR gap 2.1%→**0.04%**,
and it tightened every other point + killed the residual jitter. So the force/torque CODING is
identical to v1 (≤0.04% across the loaded range); the prior low-fracR drift was float32, now mitigated.

**Why it matters going forward (jba):** stiff filaments — microtubules (Lp ~ 1–6 mm, ~100× actin) —
live in the small-joint-angle regime where acos(dot) float32 breaks down. The asin-polynomial keeps
the angle accurate ~100s× stiffer before float32 bites, without going to a full double-precision
pose. Kept as the default (a v2 numerical-robustness improvement over v1's plain acos; mathematically
the same angle). Free-chain connectivity + FDT re-verified PASS after the change.

Open / next: ready to move on to the **next BoA→SoftBox port**. Still-open 2b items: the full
deflection ratio/τ + LP/persistence-length fixture, and the `BRotCoeff=0.5` end-segment rotational
Brownian calibration (v2 currently uses 1.0).

## 2026-06-13 — Increment 2a FIX: smooth bend (interior rotational Brownian)
jba reported the chain bent with a visually "not smooth" awkwardness. Root cause: the harness set
`brownRotScale = 1` for **every** segment, so interior segments each got an independent rotational
Brownian kick — rotating segment k+1 opens joint k and closes joint k+1, making **adjacent joints
bend in opposite directions (zigzag)**. v1 deliberately gates this: `rScale = (filAtEnd1 &&
filAtEnd2) ? 0 : rs` ("only apply brownian torques to end filaments.. best matches expected angular
correlations"). Fix: rotational Brownian only on chain-end segments (≥1 free end); interior segments
reorient only via the deterministic chain torques responding to (collective, smooth) translational
Brownian. Objective confirmation — adjacent-joint bend-vector correlation: **−0.157 (zigzag) →
+0.652 (smooth arc)**; bend RMS 9.6°→3.5°, end-to-end/contour 0.984→0.992; connectivity still PASS,
FDT free-rod path unchanged. (The 3.5° vs WLC 8.76° gap is a Brownian-magnitude/fracMoveTorq
**calibration** matter for 2b, not a 2a smoothness/connectivity concern.)

Also added diagnostics this session: `-dt <s>`, `-fracR <v>`, `-fmt <v>` overrides and an RMS-bend
stiffness readout. Sign audit (prompted by jba): the F3 lever (end2 `+uVec`, end1 `−uVec`) and F4
torsion (both ends) in `ChainBendingForceSystem` are **byte-identical to v1's device
`chainPairForcesKernel`** (and agree with v1 CPU) — no porting sign error. Sweeps: decreasing
fracMoveTorq softens (19.9°@0.05 → 7.7°@0.6) as expected (F4 restoring, confirmed); fracR has a weak,
non-monotonic effect on free-chain bending (min near 0.4) — note this is the opposite of "increasing
fracR softens"; v1's own Env.java comment says "bigger numbers are stiffer", so flag for jba whether
v1's fracR convention is intended (its calibrated role is the pinned deflection test = 2b, not the
free thermal chain).

## 2026-06-13 — Increment 2a: linked filament chain (connectivity first) — PASS
Activated the inert `end1Nbr*/end2Nbr*` topology (no storage reshape) and ported v1's real PAIRS
chain force law. A free Brownian chain holds together as a connected, semiflexible filament.
**Deflection assay (ratio/τ) and persistence length are deliberately deferred to 2b** — this increment
gates only on connectivity (visual + joint-gap), not calibration.

- **Force law ported:** v1 **device** kernel `GPUMoveThing.chainPairForcesKernel`
  (`GPUMoveThing.java:1551-1896`) — F3 link spring + F4 bending/torsion — into
  `softbox/ChainBendingForceSystem.java`, cross-checked against the CPU reference
  `FilSegment.addLinkForces`/`addTorsionSpringForces`. Ported the device version because it is
  already the per-segment, self-write, read-only-neighbor, NO-atomic kernel: each joint is computed
  from both segments' perspectives, **owner = lower slot index** defines the canonical link direction
  so the two are exactly anti-parallel (Newton-3); each segment applies +F (owner) / −F (non-owner)
  to its OWN slot only. `accurateAcos` ported verbatim. Internals double (as v1), pose read float,
  forceSum written float. Lab-frame forces → forceSum/torqueSum; integration transforms lab→body.
- **Side decode (the A1 trap) — mapping + verification.** `end?NbrSide==0` → my end glued to
  neighbor's **end1** (tip = ncoord − L/2·nu); `==1` → neighbor's **end2** (+L/2·nu). Matches v1
  `FilSegment.setEnd*Links:2818-2832`. Chain wired head-to-tail: my end2→next.end1 (side 0), my
  end1→prev.end2 (side 1), sentinel −1 at the two free ends. Verified THREE ways: (1) code-level check
  of the wired side values vs v1's derivation (OK); (2) runtime joint-continuity gap stays bounded;
  (3) **negative control** — deliberately flipping the side flags makes the gap diverge to 0.20 µm
  (>0.5·segLen) and the chain collapse (end-to-end/contour 0.16), which the test correctly FAILS. So
  the bounded PASS is meaningful, not trivial.
- **TaskGraph order:** `zero accumulators → brownian + chain (fill; independent/self-only writes) →
  integrate (reads forceSum+randForce) → derived (refreshes end1/end2 for next step's chain reads)`.
  Chain forces at step N read step-(N−1) derived geometry, as in v1. `zeroAccumulators` is a new first
  task (forceSum/torqueSum are now written, so they must be cleared each step).
- **Force-coverage audit** (each force applied exactly once):
  | source | frame | path | applied |
  |---|---|---|---|
  | Brownian randForce/randTorque | body | BrownianForceSystem writes → integration reads | once |
  | F3 link spring | lab | ChainBendingForceSystem self-write `+=` → integration reads | once / joint / segment |
  | F4 bending/torsion | lab | same | once / joint / segment |
  Action-reaction: for a joint (i,j), both threads compute the SAME owner-perspective `linkUVec` from
  the same geometry, so segment i gets +F and j gets −F (equal-and-opposite); each writes only its own
  slot → no atomics, no double-count. F4 torsion likewise (+/− across the pair by the side-consistent
  cross products).
- **Validation (16-segment free chain, monomerCt=64, segLen 0.1755 µm, 40 000 steps, fracMove=0.5,
  fracR=0.1, fracMoveTorq=0.265, aeta=0.1, filTorqSpring inactive → damped F4):** side-decode OK; max
  joint-continuity gap **0.0685 µm**, bounded (<0.5·segLen=0.0878) and **stationary** (mean
  0.0223→0.0238 µm, no growth over 4 s); no NaN; segment count conserved. The equilibrium joint
  "breathing" is ~0.022 µm thermal (≈8× actinMonoRadius — actinMonoRadius is just the spring's
  link-point offset, not the thermal amplitude). **Visually connected + semiflexible.** Bonus sign:
  end-to-end/contour = 0.98, matching the wormlike-chain value for L=2.8 µm at Lp=15 µm (v1's
  persistence length) — bending stiffness already in the right regime, though calibration is 2b's job.
- FDT free-rod path (inc 1) re-verified **unchanged** (−2.52/−1.15/+0.08/−1.80 %, PASS). Adding
  `chainParams` to FilamentStore is additive (not in the FDT graph). `view_run.sh`-style watch:
  `./run_gpu.sh -chain <dir> [nSeg [M]]` dumps frames + reports the gap; `threejs_chain*/` gitignored.

Open / next: increment **2b** — pins + midpoint force + the deflection ratio/τ (and LP) fixture, layered
on this already-correct chain force law.

## 2026-06-13 — Increment 1.5: file-based Three.js frame output (watch the rods)
Output-only — get eyes on the sim before chain/bending. Ported v1 `ThreeJSWriter`'s `segments`
emission into `softbox/FrameWriter.java` (a host IO utility, not a device system) and reuse the v1
viewer + server **verbatim** (`sim_server.py`, `sim_viewer_boa.html` copied unchanged, md5 confirmed).

- **Schema** (`segments` only, per constraint): `{"frame":N,"t":T,"bounds":{xDim,yDim,zDim},
  "segments":[{"id","end1":[x,y,z],"end2":[x,y,z],"r","notADPRatio":1.0,"cofilinCount":0}]}`,
  files `frame_%06d.json`, output-dir auto-increment (`.NNN`) ported from v1. Verified in the viewer
  JS that `myosins`/`minifilaments`/`nodes`/`contractility` are all `if(...)`-guarded and `bounds` is
  optional — a segments-only frame renders with **no viewer modification** (no empty arrays needed).
  `r = actinWidth/2 = 0.0035 µm` (Constants.radius), as v1. Per-segment JSON is one method so a future
  generic "bodies+links" schema is a localized swap (deferred to the planner, pre-motors).
- **end1/end2** are the derived geometry (end1 = coord − L/2·uVec, end2 = coord + L/2·uVec — same
  formula as `DerivedGeometrySystem`). FrameWriter reconstructs them on the host from the
  already-pulled canonical pose (coord+uVec) + segLength, so the output path adds **no device
  transfer** beyond the harness's existing output-cadence `coord/uVec` `UNDER_DEMAND` pull.
- **Bounds:** fixed cube, side = 2·(clusterHalf + 5·√(2·D∥·T_total)) — sized to ~5σ of the expected
  diffusive spread over the run; framing only, not physics (free rods have no walls). Viewer builds
  the box from frame 0.
- **Wiring:** `-3js <dir>` flag in `DiffusionHarness`. Present → a dedicated viz run (default N=200 in
  a compact 0.3-µm cluster, random orientations, both Brownian components ON at bare amplitude),
  frames written at the existing output-cadence pull. **Absent → FDT path byte-for-byte unchanged**:
  re-ran `./run_gpu.sh`, got the identical inc-1 numbers (−2.52 / −1.15 / +0.08 / −1.80 %, PASS).
- **Verified render-ready:** `./view_run.sh 200 4000` → 201 frames, 0 non-finite coords across
  241 200 values, segment length 0.1755 µm, midpoint spread grew isotropically 0.17→0.32 µm std
  (anisotropy 1.05); the magnitude matches FDT (effective isotropic D≈0.088 µm²/s ⇒ predicted final
  std 0.317 vs measured ~0.32). `sim_server.py scan_runs` detects the folder (201 frames). Frame
  output is gitignored (`threejs_output*/`).

Open / next: increment 2 (actin chain / bending forces) — unchanged from inc-1's note. The generic-vs-
per-type frame schema decision is deferred to the planner, before motors land.

## 2026-06-13 — Increment 1: filament rigid-rod overdamped Langevin slice — FDT PASS
First real code. `softbox/` package: SoA component-array core + four named systems as TornadoVM PTX
kernels, validated against the fluctuation–dissipation (Einstein) relation on the aorus RTX 5070.
Built/ran with the v1 toolchain (Java 21 + TornadoVM 4.0.1-dev PTX, `--enable-preview`, `-g`,
`@tornado-argfile`). No chain/bending forces, neighbors, walls, motors, membrane, or biochem.

**Component-array layout (FilamentStore — the canonical store).** Pose `coord`/`uVec`/`yVec`;
derived (recomputed, NOT source of truth) `zVec`/`end1`/`end2`; geometry `monomerCount`→`segLength`;
body-frame diagonal drag/diffusion `bTransGam`/`bRotGam`/`bTransDiff`/`bRotDiff`; per-step
accumulators `forceSum`/`torqueSum` (deterministic, zeroed) + `randForce`/`randTorque` (Brownian);
per-rod `brownTransScale`/`brownRotScale`; inert chain topology `end1NbrSlot/Side`,`end2NbrSlot/Side`
(sentinel −1 = free; integer (slot,side) from birth per migration-doc A1, ready for increment 2 to
read without reshaping).

**FLAGGED layout decision — planar SoA, not one-array-per-component.** TornadoVM's `task()` tops out
at 15 args (`TornadoFunctions$Task15`); the integration kernel needs ~27 component planes, so strictly
separate `xPos[]`/`yPos[]`/`zPos[]` FloatArrays are impossible to launch. Each vector quantity is one
device buffer in planar `[X-plane | Y-plane | Z-plane]` layout (x's contiguous, then y's, then z's) —
genuine SoA (coalesced, non-interleaved), NOT AoS `[x0 y0 z0 x1…]`. Named `coordX/Y/Z()` plane
accessors keep each component findable. The architectural invariant (no per-object fields, no AoS
interleave, device-resident) holds; the packing is a device-arity accommodation only.

**Four systems (free functions over arrays; each one identifiable physics).**
1. `DragTensorSystem` (host, runs once) — ports `FilSegment.calculateProperties()` line-for-line.
2. `BrownianForceSystem` (device) — fills `randForce`/`randTorque` body-frame from diffusion tensors +
   the REUSED v1 device RNG (Wang hash + Box-Muller, keyed `(slot,stepCount,runSeed)`; verbatim from
   `GPUMoveThing.moveThingKernel`/`wangHash`, per RESIDENCY_INVENTORY §3 — no new RNG invented).
3. `RigidRodLangevinIntegrationSystem` (device) — overdamped Euler: body force = Rᵀ·forceSum +
   randForce → `bVeloc = 1e6·bForce/bTransGam`; body torque → `deltaBAng` rotation of uVec/yVec;
   port of `FilSegment.moveThing()` fused with the v1 device integration, minus inlined Brownian.
4. `DerivedGeometrySystem` (device) — recompute `zVec`/`end1`/`end2` and re-orthogonalize `yVec`
   (port of `Thing.recomputeDerivedSoA`). Output only this increment.
All four run as kernels over resident arrays in one TaskGraph (brownian→integrate→derived); pose is
FIRST_EXECUTION + pulled UNDER_DEMAND only at output cadence — **no per-step host pose pull**.

**Force-coverage audit (this slice has exactly two force sources).**
| force source | path | applied | notes |
|---|---|---|---|
| deterministic `forceSum`/`torqueSum` (lab) | init 0; no kernel writes it | genuinely **zero** | free rod: no chain/wall/motor/node forces exist yet |
| Brownian `randForce`/`randTorque` (body) | `BrownianForceSystem` writes once/step; `RigidRodLangevinIntegrationSystem` reads once | **exactly once** | no double-count; a 2× would give 4× D (+300%), not the observed ∓2% |
Verdict: every force applied on exactly one path — never zero-by-accident (the zero is real), never
twice. The FDT pass at the bare amplitude confirms no missing/extra factor.

**Code-fidelity cross-check (γ formula, code-level not run).** `DragTensorSystem.run()` reproduces
`FilSegment.calculateProperties()` (v1ref `FilSegment.java:420-441`) arithmetic byte-for-byte:
`bTransGam.x=(2πη·Lₘ)/(ln(Lₘ/2rₘ)+aParallel)`, `.y=.z=(4πη·Lₘ)/(…+aOrthog)`, `bRotGam.x=4πη·rₘ²·Lₘ`,
`.y=.z=(πη·Lₘ³)/(3(…+aTurning))`, then Einstein `D=kT/γ`; same min-length clamp (`stdSegLength·halfmono`
for a free/at-end rod), same `length=(monomerCt+1)·actinMonoRadius`, same constants (Boltz, tempK,
aeta=0.1, aParallel/aOrthog/aTurning). Only diff: SoftBox reads `Constants.*` where v1 reads
`Env.*.getValue()` (identical values). FDT self-consistency + faithful γ together pin both the
amplitude→drag coupling and the tensor values.

**Measurement protocol.** N=8192 free rods, monomerCt=64 (L=0.1755 µm), dt=1e-4 s, aeta=0.1 Pa·s.
- Config T (translational anisotropy): rotational Brownian OFF, orientation frozen along lab-x so body
  axes ≡ lab axes; M=20000 steps, pose pulled every 200; per-axis MSD slope through origin → D = slope/2.
- Config R (rotational): translational Brownian OFF; M=4000, uVec pulled every 20; orientational
  autocorrelation C(t)=⟨uₓ(t)⟩ fit to exp(−2D_rot·t) over C∈(0.2,0.95) (22 samples).
Both B-coefficients set to 1.0 so the bare relation D=kT/γ holds; v1's production BTransCoeff=1/
BRotCoeff=0.5 (and the lone-segment rot-Brownian-off rule) are biological persistence-length tuning
knobs, deliberately out of scope for the amplitude-coupling check.

**Validation numbers (measured vs FDT prediction from the SAME γ arrays; tol 5%).** γ_par=3.649e-08,
γ_perp=5.430e-08 N·s/m; γ_rot⊥=2.211e-22 N·m·s/rad.
| quantity | measured | FDT D=kT/γ | relErr |
|---|---|---|---|
| D_trans∥ (µm²/s) | 0.10996 | 0.11280 | −2.52% |
| D_trans⊥ y (µm²/s) | 0.07494 | 0.07581 | −1.15% |
| D_trans⊥ z (µm²/s) | 0.07587 | 0.07581 | +0.08% |
| D_rot⊥ (rad²/s) | 18.280 | 18.615 | −1.80% |
**FDT VALIDATION PASS.** Tolerance 5% justified a priori: float32 (v1 GPU path precision) + ~1/√N
ensemble noise + O(D·dt) first-order-Euler bias. The small consistent negative bias (≈−1 to −2.5%) is
that Euler/float32 systematic, not a wrong factor (which would be tens of %). Per Lesson 6 we did NOT
reach for double precision — the magnitude argument says a wrong integration factor is far likelier
than float rounding moving D by a measurable amount, and here the factor is right.

Two TornadoVM gotchas worth recording: (1) a kernel method literally named `kernel` collides with an
OpenCL/PTX reserved token — rename (we use `brownianForce`/`integrate`/`derive`); (2) the default
block size overflows the register file for these (RNG-/trig-heavy) kernels → CUDA 701
(LAUNCH_OUT_OF_RESOURCES) — set an explicit `WorkerGrid1D` localWork=64 via a `GridScheduler` keyed
`"rodLangevin.<task>"` (matches v1 `MOVE_KERNEL_BLOCK_SIZE`). Run log: `RUN_LOGS/2026-06-13_inc1_fdt.log`.

Open / next: increment 2 — actin chain / bending-force system. Starts READING the inert
`end1Nbr*/end2Nbr*` topology arrays laid down here (no storage reshape). The derived end1/end2
sign convention (end1 = coord − L/2·uVec) is now the one chain forces will read.

## 2026-06-13 — Increment 0: workspace scaffolded
Soft Box repo initialized at `~/Code/SoftBox` as a new repo (not a BoA branch). Frozen-v1 reference
set up as a read-only `git worktree` at `~/Code/BoA-v1ref`, detached at tag
`softbox-filref-2026-06-13` (pinned at v1 `main` HEAD; will re-point to `biology-production-v1` once
the v1 finish line is reached). `CLAUDE.md` seeded with the architecture invariants (integer-ID
entities, SoA component arrays as source of truth, systems as free functions, device residency from
day one), the reference/oracle discipline (fixtures frozen as data, not v1 code; read current main
for physics; reconciliation on v1 physics changes), the porting discipline (force-coverage audit,
minimal-repro first), and the proposed increment sequence (filament slice first). No physics yet.

Open / next: increment 1 — planner to design the FilSegment component-array layout + rigid-rod
Langevin integration system, with the v1 deflection / relaxation / LP benchmarks as the fixture.
