# Staged Brownian-Noise Ablation of the Vilfan Target-Zone Mechanism

**Authoritative report for this increment** (2026-07-24, branch `gpu-mat-bottlenecks-explicit-singlehead`). Sole
Markdown report for this task. Asks which Brownian forcing channels destroy the phase coherence that Vilfan's
moving-target-zone mechanism requires, by ablating them one physical subsystem / one binding state / one
force-vs-torque channel at a time. **Noncanonical, flag-gated, default-off, byte-identical when disabled. No new
force law, torsional registry, roll spring, lateral stroke, binding-axis preference, axial confinement spring or
fitted parameter was added. No canonical default, parameter, chemistry, force law, S2/stroke mechanics, dt, RNG
stream, event ordering or `MotorModel.CANON_VERSION` changed. Nothing was tuned.**

Predecessor: `docs/VILFAN_TARGET_ZONE_BINDING_AND_TWIRLING_FINDINGS.md` (Stage A = **A2**, the dynamic null, with
the measured root cause: the target-zone phase decorrelates ~105× faster than it drifts). This report executes
that report's §19 "exact next smallest step" — measure the axial phase-coherence budget and attribute it to a
*body* — in the ablation form the task specifies.

---

## 1. Baseline, runner, hardware, device flags

- **Baseline commit:** `8d2f318` (`docs(target-zone): resolve the motion endpoints …`), branch
  `gpu-mat-bottlenecks-explicit-singlehead`. During this campaign the GPU crash-monitoring work that was already
  present in the working tree was committed on top as `a7d0955` + `01566e6`
  (`feat(gpu): always-on TornadoVM/GPU crash monitoring (diagnostic; default-OFF)`). That code was in the tree for
  **every** run reported here, so all arms are internally consistent; it is diagnostic-only and default-off, and
  the §14.1 regression gates reproduce the pre-increment numbers exactly, confirming it changes no physics. The
  effective baseline for this increment's diff is therefore `01566e6`.
- **aorus**; Java 21 (OpenJDK 21.0.11, `--enable-preview`), TornadoVM 4.0.1-dev **PTX**, **NVIDIA RTX 5070**,
  driver 595.71.05.
- **Required device flags** (already encoded in `scripts/run_vilfan_targetzone.sh`, unchanged by this increment):
  `-Dtornado.enable.fma=false` (the upstream PTX FMA lowering defect at `matS2SolveStep`),
  `-Dtornado.recover.bailout=false` (**a lowering failure THROWS — no silent sequential fallback**),
  `-Dtornado.tvm.maxbytecodesize=65536`, `-Xmx8G`.
- **Runner disclosure.** The deterministic fixtures run on the CPU runner. **Every campaign arm runs
  device-resident on the GPU** through the full explicit-S2 gliding `TaskGraph` (`-gpu`); no arm fell back, and
  `bailout=false` guarantees a fallback would have thrown instead.
- **Dirty at start (pre-existing, untouched here):** `softbox/ExplicitCompleteMatHarness.java` /
  `softbox/ExplicitTwirlGlidingHarness.java` edits from the lowering task, plus the untracked GPU
  crash-recorder scripts and `softbox/CrashTrace*.java` / `softbox/TornadoCrashDiagnostic.java`.

---

## 2. Brownian-source audit

Every stochastic term in the explicit-S2 single-head gliding step was located by reading the step order
(`ExplicitCompleteMatHarness.stepGlidingCPU` / `buildGlidingGraph`) and every kernel it calls. The step contains
**exactly five** RNG-bearing kernels; three of them are Brownian, two are not.

### 2.1 Where each source enters the mechanics

| # | Stochastic source | Kernel / task | Enters the mechanics as | RNG keying (salt) | Bodies / DOFs affected |
|---|---|---|---|---|---|
| 1 | **Filament translational Brownian force** | `BrownianForceSystem.brownianForce` → `randForce[0..2]` | body-frame force added inside `RigidRodLangevinIntegrationSystem.integrate` | wang32, `(segment,step,seed)` | every filament segment; component **0 is AXIAL** (along `uVec`), 1/2 **TRANSVERSE** |
| 2 | **Filament rotational Brownian torque** | same kernel → `randTorque[0..2]` | body-frame torque in `integrate` | same draw (the sin half of the same three Box–Muller pairs) | component **0 is ROLL about the body-fixed axial direction** (it drives the `yVec` rotation about `uVec`); 1/2 are tumble/bend |
| 3 | **Motor S2 beam-node Brownian force** | `TwoBodyBeamAnalyticGpu.matS2SolveStep` | added to the implicit Newton RHS at each of the 3·M beam-node DOFs | wang64, `0x4811 + (m·1009 + j·131 + k)·7919`, keyed `(seed, t)` | the explicit S2 beam nodes j=1..M |
| 4 | **Motor generalized `phi` Brownian torque** | `matS2SolveStep` | added to the RHS at the `phi` generalized coordinate | wang64, `0x4841 + m·7919` | the converter/lever rotation about the base axis `eup` |
| 5 | **Motor generalized `psi` Brownian torque** | `matS2SolveStep` | added to the RHS at the `psi` generalized coordinate | wang64, `0x4842 + m·7919` | the head rotation about `eup` |
| — | *target-zone attachment hazard* (NOT Brownian) | `matTargetZone` | accept/reject of a geometric candidate | wang64, `0x545A4244` ("TZBD") + m·7919 | **kept ACTIVE in every arm** |
| — | *chemistry* (NOT Brownian) | `NucleotideCycleSystem.cycleLymnTaylor` | nucleotide transitions / release | wang32, `(m,step,seed) ^ 0x4E55 / 0x4D54 / 0x52465241` | **kept ACTIVE in every arm** |

**Everything else in the step is deterministic** and was confirmed so by inspection: `matBeamGeom`,
**`matBindExplicit` (the canonical bind is a purely geometric 8-gate predicate — it contains no RNG at all)**,
`matSurfaceAzim`, `matSurfaceStericPrune`, `matCock`, `matPlaceHeadExplicit`, `bondForces`/`bondForcesSurface`,
the CSR stages, `chainForces`, `matZConfine`, `integrate`, `orthogonalizeY`, `derive`, `matReduceBlocks/Final`.

### 2.2 The audit's load-bearing structural finding

The task asked for head / neck-lever / converter / explicit-S2-beam-node / tail-anchor / generalized-coordinate
Brownian to be inspected separately. In this lineage they are **not separate channels**:

- The motor sub-bodies (`MotorStore.body` rod / lever / head) are **not Brownian-integrated at all**. Their pose is
  **overwritten every step** by `matPlaceHeadExplicit` from the beam solution, so they carry no independent thermal
  DOF. There is no head Brownian, no neck Brownian and no separate converter Brownian to mask.
- The **tail / motor anchor is pinned**: `matS2SolveStep` re-imposes `nodes[0] = (gEx,gEy,gEz)` every Newton
  iteration, so the anchor node receives no Brownian force.
- Consequently **the motor's entire thermal content is sources 3–5**: the S2 beam nodes plus the two generalized
  rotational coordinates. Masking those three is masking *all* motor Brownian.
- **"Pose jitter indirectly introduced by stochastic mechanics"** — including the jitter of the head's axial
  perpendicular-foot coordinate `bindArc` — is therefore a *derived* quantity of sources 1–5 only, and the
  measurement in §9 decomposes it accordingly.

### 2.3 Which source is active in which arm

`ON` = the canonical thermal term; `OFF` = the term is multiplied by zero (the draw is skipped; see §4).

| Source | A full | B primary | C fil-off | D bound-quiet | E all-off | F1 (=A, α=0) | F2 (=B, α=0) | G axial | H roll | I trans+bend |
|---|---|---|---|---|---|---|---|---|---|---|
| 1a filament AXIAL force | ON | OFF | OFF | ON | OFF | ON | OFF | **ON** | OFF | OFF |
| 1b filament TRANSVERSE force | ON | OFF | OFF | ON | OFF | ON | OFF | OFF | OFF | **ON** |
| 2a filament ROLL torque (about body `u`) | ON | OFF | OFF | ON | OFF | ON | OFF | OFF | **ON** | OFF |
| 2b filament other rotational torque | ON | OFF | OFF | ON | OFF | ON | OFF | OFF | OFF | **ON** |
| 3–5 motor Brownian, **UNBOUND** head | ON | ON | ON | ON | OFF | ON | ON | ON | ON | ON |
| 3–5 motor Brownian, **BOUND** head | ON | OFF | ON | OFF | OFF | ON | OFF | OFF | OFF | OFF |
| target-zone hazard RNG | ON | ON | ON | ON | ON | (α=0 ⇒ no draw) | (α=0 ⇒ no draw) | ON | ON | ON |
| chemistry RNG | ON | ON | ON | ON | ON | ON | ON | ON | ON | ON |

**B and E differ in exactly one channel — the UNBOUND-motor Brownian.** That single-channel contrast is the
decisive comparison of this report (§9.2).

---

## 3. State-dependent masking design

Two additive mechanisms, both default-off:

**(a) Filament channels — a separate mask kernel, the canonical Brownian kernel untouched.**
`BrownianForceSystem.brownChannelMask(randForce, randTorque, chanMask, counts)` is a **new** task inserted
*between* the existing `brown` and `integ` tasks. It scales the already-generated body-frame components:

```
randForce[0]  *= mAxial      randTorque[0]  *= mRoll        (body frame = the integrator's frame:
randForce[1]  *= mTransverse randTorque[1]  *= mOtherRot      component 0 ∥ uVec, 1/2 ∥ yVec/zVec)
randForce[2]  *= mTransverse randTorque[2]  *= mOtherRot
```

`brownianForce` itself is **byte-unchanged**; there is no second RNG and no second physics implementation. The
mask task is **only wired when a channel is actually disabled** (`brownChanOn()`), so the default graph is
byte-identical, and a mask of exactly `1.0f` is an IEEE-754 identity multiply (fixture BR101).

**(b) Motor channels — the minimal binding-state mask inside `matS2SolveStep`.** The motor Brownian enters the
implicit Newton **right-hand side**, not a separate buffer, so it can only be masked in place. The edit is three
lines: read `matc[3]` as a policy word and derive a per-motor `brownM` from `boundSeg[m]`:

```java
int brownM = brownOn;
if (bnd) { if ((mPolicy & 1) != 0) brownM = 0; }   // bit0: bound-motor Brownian OFF
else     { if ((mPolicy & 2) != 0) brownM = 0; }   // bit1: unbound-motor Brownian OFF
```

`brownM` then replaces `brownOn` at the three stochastic RHS sites (beam nodes, `phi`, `psi`) and **nowhere else**.
`matc[3] == 0` ⇒ `brownM == brownOn` ⇒ arithmetically bit-identical to the canonical kernel.

**A masked bound motor is NOT immobilised.** Every deterministic term is untouched: the stretch/bend/floor beam
assembly, the Hessian, the node and generalized drag diagonals, the F8 cross-bridge reaction and its generalized
moments `QphiF8`/`QpsiF8`, the converter spring, the bind spring, the Gauss–Jordan solve, the pose writeback, the
`forceDotFil`/`forceMag` reaction, `matCock` (the power stroke), the Lymn–Taylor chemistry and detachment. No
coordinate is pinned, no position overwritten, no velocity zeroed, the solver is not bypassed. Only the stochastic
thermal term is removed. Fixture BR105/BR109/BR110/BR111 verify this directly.

**Timing.** The mask reads `boundSeg[m]` inside `s2solve`, which runs **after** `bind` → `tzone` → `chem` in the
same step. So `boundSeg` already carries this step's binding, this step's target-zone accept/reject and this
step's detachment ⇒ **the transition takes effect immediately on the FREE→bound step, and normal unbound search
resumes on the detachment step.** Fixture BR105/BR107 audit this at every step of a real trajectory.

---

## 4. RNG behaviour

- **Draws are SKIPPED, not drawn-and-discarded** — and skipping is provably stream-neutral here, because **every**
  RNG in this step is **counter-based and stateless**: a pure function of `(entity, step, seed, salt)`. There is no
  sequential generator state, so omitting a draw cannot shift any other draw, in any other stream, for any other
  entity. (This is the same property the project already relies on in `BrownianForceSystem`'s `tS==0 && rS==0`
  early-out and in `NucleotideCycleSystem`'s pre-empted draws.)
- **Stream separation is by construction:** filament Brownian `(segment,step,seed)` wang32; motor Brownian wang64
  salts `0x4811/0x4841/0x4842 + m·…`; target-zone accept wang64 salt `0x545A4244`; chemistry wang32 salts
  `0x4E55 / 0x4D54 / 0x52465241`. Disabling a Brownian channel touches none of the others.
- **Attachment-hazard RNG, chemistry RNG and the target-zone private RNG all remain ACTIVE in every arm** —
  verified as fixtures BR110/BR111 (chemistry fires; candidates are offered *and* rejected under the primary
  policy) and by the per-arm candidate/acceptance counts in §6.
- **Cross-arm event identity is NOT claimed and is not attainable.** The chemistry and hazard draws are keyed
  identically across arms, but their *arguments* (load, geometry, whether a head is a candidate at all) depend on
  the trajectory, which the ablation legitimately changes. **Matched seed ensembles are used throughout, not
  trajectory identity.**

---

## 5. Experimental arms

Matched geometry, density (ρ = 800 heads/µm²), duration, chemistry, target-zone parameters (`alphaPsi = 6` unless
stated), seed policy (seeds `101 + 101·i`) and output settings. Surface binding ON, steric OFF, roll spring OFF,
no bound registry — identical to the Stage-A campaign so the arms are directly comparable to it.

| Arm | Policy name | Filament Brownian | Unbound motor | Bound motor | alphaPsi |
|---|---|---|---|---|---|
| **A** | `full` | ON (all 4 channels) | ON | ON | 6 |
| **B** | `unbound-search-only` | **OFF** (all 4) | ON | **OFF** | 6 |
| **C** | `filament-off` | **OFF** (all 4) | ON | ON | 6 |
| **D** | `bound-motor-quiet` | ON | ON | **OFF** | 6 |
| **E** | `all-off` | **OFF** | **OFF** | **OFF** | 6 |
| **F1** | `full` | ON | ON | ON | **0** |
| **F2** | `unbound-search-only` | **OFF** | ON | **OFF** | **0** |
| **G** | `fil-axial-only` | axial force only | ON | OFF | 6 |
| **H** | `fil-roll-only` | roll torque only | ON | OFF | 6 |
| **I** | `fil-transverse-bend` | transverse force + bend torque | ON | OFF | 6 |

G/H/I are run on the **Arm-B motor policy** (bound-motor Brownian off) so that the filament channel under test is
the only remaining noise source acting on the *bound* complex; this is stated because the task left the motor
setting for the decomposed arms unspecified.

Arm E uses the **unmodified initial geometry** — no repositioning, no binding-parameter change. Binding does occur
in it (§6), just rarely, so its duration was extended rather than its parameters altered.

---

## 6. Deterministic fixtures (12/12 PASS)

`./scripts/run_vilfan_targetzone.sh -brownian-fixtures` — log `RUN_LOGS/vilfan_brownian/fixtures.txt`.

| # | Fixture | Result |
|---|---|---|
| 101 | mask `(1,1,1,1)` is an **exact identity** on `randForce`/`randTorque` | PASS |
| 102 | mask `(1,0,0,0)`: axial force bit-unchanged, every other channel **exactly zero** | PASS |
| 103 | mask `(0,0,1,0)`: body-axial ROLL torque bit-unchanged, every other channel **exactly zero** | PASS |
| 104 | policy `full` ⇒ trajectory **bit-identical** to the canonical (unmasked) path (200 steps, coord + reduction + bound-set hashes) | PASS |
| 105 | **BOUND motor gets exactly zero Brownian; UNBOUND motor keeps it** — every step, every motor, over a real 400-step trajectory | PASS |
| 106 | the mask is **not vacuous**: canonical Brownian measurably perturbs 22/22 bound and 239 978/239 978 unbound motor-samples | PASS |
| 107 | FREE→bound **and** bound→FREE transitions occur inside the audited window ⇒ the switching **timing** is covered | PASS |
| 108 | Arm B: filament Brownian force **and** torque exactly zero on every segment, every step (`max|rand| = 0.000e+00`) | PASS |
| 109 | Arm B: deterministic filament force (`9.85e-12 N`) and cross-bridge force (`6.96e-12 N`) remain **nonzero** | PASS |
| 110 | Arm B: Lymn–Taylor **chemistry continues to fire** | PASS |
| 111 | Arm B: the **target-zone hazard continues to run** (candidates offered *and* rejected: 25 offered, 24 rejected) | PASS |
| 112 | Arm B with `alphaPsi = 0`: every canonical bind is kept (`accFrac = 1.0000`) ⇒ the α=0 path stays canonical w.r.t. the target zone | PASS |

Fixture 105's method is the strongest of these: at **every step** of a real Arm-B trajectory, `matS2SolveStep` is
replayed on private copies three ways from the identical post-step state — (i) canonical, (ii) bound-mask on,
(iii) Brownian entirely off — and per motor it is required that a bound head satisfies **(ii) ≡ (iii) exactly**
while an unbound head satisfies **(ii) ≡ (i) exactly**. Because `boundSeg` at replay time is the one the real
solver saw in that same step, this simultaneously fixes the transition timing.

## 7. CPU/GPU validation and device residency

`./scripts/run_vilfan_targetzone.sh -brownian-equiv -target-zone-alpha 6` — log `RUN_LOGS/vilfan_brownian/equiv.txt`.
The **full** target-zone gliding graph (with the mask task wired) is built and executed device-resident against the
CPU runner under four policies, with `-Dtornado.recover.bailout=false` so a lowering failure throws.

```
policy                 alpha   200 device-resident steps
full                     6     bindMism=0 acceptMism=0 max|Δpsi0|=5.2e-06  maxΔfil=1.82e-02 µm  masked|rand|=0  firstDiv=t=181  PASS
unbound-search-only      6     bindMism=0 acceptMism=0 max|Δpsi0|=0.0e+00  maxΔfil=5.96e-08 µm  masked|rand|=0  firstDiv=none   PASS
filament-off             6     bindMism=0 acceptMism=0 max|Δpsi0|=0.0e+00  maxΔfil=5.96e-08 µm  masked|rand|=0  firstDiv=none   PASS
all-off                  6     bindMism=0 acceptMism=0 max|Δpsi0|=0.0e+00  maxΔfil=5.96e-08 µm  masked|rand|=0  firstDiv=none   PASS
```

- **The graph lowers and runs device-resident under every policy; no fallback occurred and none was permitted.**
- **Attachment events and accept/reject decisions are EXACTLY identical CPU↔GPU** in every arm.
- **A striking secondary result:** with filament Brownian off, the CPU and GPU trajectories stay **bit-close for
  all 200 steps** (`firstDiv = none`, `maxΔfilCoord = 5.96e-08 µm`, `max|Δpsi0| = 0`), whereas the full-Brownian
  arm decorrelates at `t = 181`. The documented explicit-S2 chaotic decorrelation is therefore *driven by the
  thermal forcing*, and the ablation arms are a **stronger** CPU/GPU equivalence regime than the canonical one,
  not a weaker one.
- **Disabled channels contribute exactly zero** stochastic force/torque on the device path too (`masked|rand| =
  0.0e+00`, checked every step), and active channels retain their existing statistics (fixtures 102/103, and the
  `full` arm reproduces the pre-increment numbers `max|Δpsi0|=5.2e-06`, `firstDiv=t=181` digit-for-digit).

---

## 8. Attachment-flux results (10 seeds × 6000 steps, ρ = 800, device-resident)

`./scripts/run_vilfan_targetzone.sh -ablation -seeds 10 -steps 6000 -density 800 -target-zone-alpha 6 -gpu`
— log `RUN_LOGS/vilfan_brownian/ablation_main_d800.txt`. 0 invalid states and 0 solver failures in every arm.

```
arm                        glide µm/s     avgB  detach/step accFrac   ⟨Δψ⟩acc ± SEM    ⟨Δψ⟩cand  leadAcc−leadCand ± SEM (sign)  lead/trail
A  full baseline           -3.394±0.229   5.89     0.020     0.139   +0.0012 ± 0.0167   +0.0077    -0.0024 ± 0.0228  (7/10)      613/626
B  PRIMARY clean test      -1.717±0.070   7.72     0.017     0.125   +0.0104 ± 0.0085   -0.0480    -0.0188 ± 0.0139  (7/10)      532/549
C  filament off, mot on    -2.382±0.099   6.45     0.018     0.122   +0.0045 ± 0.0150   -0.0752    -0.0325 ± 0.0191  (8/10)      541/587
D  bound quiet, fil on     -2.259±0.154   7.41     0.020     0.154   +0.0033 ± 0.0121   +0.0154    +0.0023 ± 0.0200  (5/10)      619/618
E  fully deterministic     -0.495±0.106   0.88     0.002     0.013   -0.3067 ± 0.0753   -0.2050    +0.0686 ± 0.0519  (6/10)       70/41
F1 target zone OFF (arm A) -3.555±0.181   7.13     0.025     1.000   -0.0374 ± 0.0536   -0.0374    +0.0000 (by defn)           795/759
F2 target zone OFF (arm B) -1.730±0.077   9.36     0.021     1.000   +0.0215 ± 0.0530   +0.0215    +0.0000 (by defn)           659/686
```

**Arm A reproduces the pre-increment Stage-A campaign digit-for-digit** (glide, avgBound, accFrac, `⟨Δψ⟩acc`,
`leadAcc−leadCand`, `τnet`, cancellation, and the full 12-bin mismatch histogram
`[822 845 754 734 641 627 627 671 714 771 844 825]` are identical to
`docs/VILFAN_TARGET_ZONE_BINDING_AND_TWIRLING_FINDINGS.md` §10), as does F1. This is the strongest available
demonstration that the `full` policy is the canonical path.

### 8.1 Did Arm B restore the kinetic mechanism? **NO.**

1. **Mean signed attachment phase:** `⟨Δψ⟩acc = +0.0104 ± 0.0085` — **1.2σ**, indistinguishable from zero and
   the same size as Arm A's.
2. **Leading/trailing flux asymmetry:** the paired statistic `leadAcc − leadCand = −0.0188 ± 0.0139` — **1.4σ**,
   and **negative**, i.e. *trailing*-shifted, where the mechanism predicts positive. Raw counts 532 leading vs
   549 trailing (Poisson σ ≈ 33 on the difference of 17).
3. **Arms C and D are also null** (`−0.0325 ± 0.0191` and `+0.0023 ± 0.0200`), so neither the filament noise alone
   nor the bound-motor noise alone was the blocker.
4. **What the target zone still does, in every arm,** is exactly what Stage A found: it sharply narrows the
   accepted azimuth (Arm B accepted-azimuth histogram `[6273 28491 34268 14105 5317 860 366 1917]`, versus a flat
   distribution for the azimuth-blind arm) and produces the pool-depletion notch (candidates 541–552 in the four
   bins with `|Δψ| < 1` against 750–929 at `|Δψ| > 1.5`). Stereospecificity is real; directional bias is not.

### 8.2 Engagement is not the failure mode

Removing the noise **increases** occupancy and **decreases** speed: `avgBound` 5.89 (A) → 7.72 (B), `detach/step`
0.020 → 0.017, `accFrac` 0.139 → 0.125, glide −3.394 → −1.717 µm/s. A quieter bound complex is strained into
detachment less often but is also driven less hard. **Arm B is a healthy, well-engaged, fully dynamic gliding
assay** — its null is not an artefact of collapsed statistics (1081 attachments over the ensemble).

**Arm E is the exception and must be reported as such.** With the unbound search noise removed, `avgBound`
collapses to 0.88 and `accFrac` to 0.013 — only 111 accepted attachments over 10 seeds × 6000 steps. Its
`⟨Δψ⟩acc = −0.3067 ± 0.0753` is 4.1σ from zero, but its *candidate pool* is itself strongly asymmetric
(`⟨Δψ⟩cand = −0.2050`), so the interpretable paired statistic is `leadAcc − leadCand = +0.0686 ± 0.0519`
(1.3σ) — **the sign the mechanism predicts, but not resolved.** §11 extends Arm E's duration rather than touching
any parameter.

## 9. Phase-coherence decomposition — the mechanism-level result

Per-step motion of the target-zone phase of a *persisting* geometric candidate (a head offered on two consecutive
steps). `SITE` restricts to pairs whose attachment site did not change (`|Δ bindArc| < 20 nm`); the **same-site
fraction is 0.991–1.000 in every arm**, so the perpendicular-foot candidate definition does **not** destroy site
persistence — that sub-hypothesis of Outcome N3 is excluded.

**Sign convention verified, not assumed.** Both conventions were computed. `resid(A)`
(`ΔΔψ − [−twistRate·ΔbindArc] − [−Δroll]`) is 0.017–0.069 rad/step while `resid(B)` (opposite signs) is
1.43–1.56 — a factor 20–80. Convention A is correct, and the numbers below use it.

```
arm   drift/step  ⟨|ΔΔψ|⟩   axial     roll     residual  noise/drift  median|Δarc|  D_ax(med)   ⟨residence⟩  P(mono)  ac1
A       0.00913   1.11458   1.11404  0.05503   0.05754      122        0.903 nm    3.59e-01     1.78 steps   0.314   -0.112
B       0.00462   0.84978   0.84997  0.01622   0.01664      184        0.656 nm    1.90e-01     1.98 steps   0.252   -0.145
C       0.00641   0.83596   0.83592  0.03529   0.03581      130        0.653 nm    1.88e-01     2.03 steps   0.255   -0.121
D       0.00608   1.13642   1.13654  0.05601   0.05903      187        0.944 nm    3.93e-01     1.74 steps   0.308   -0.105
E       0.00134   0.01801   0.01801  0.00711   0.00712       13        0.005 nm    3.65e-05   147.77 steps   0.583   +0.717
```
*(rad/step; `D_ax` in µm²/s from the robust median form `σ = median|Δarc|/0.6745`, `D = σ²/2dt`.)*

### 9.1 Arm B does not restore phase coherence — it makes the ratio worse

`⟨|ΔΔψ|⟩` falls only 1.115 → 0.850 rad/step (−24 %), while the deterministic drift falls 0.00913 → 0.00462
(−49 %, because the glide halves). **The noise-to-drift ratio therefore rises from 122 to 184.** One step still
displaces the target-zone phase by ~14 % of a full helical turn. Successive attachment attempts still sample an
effectively uncorrelated phase (lag-1 autocorrelation of the increment `ac1 = −0.145`, i.e. white-to-slightly-
anticorrelated; `P(monotone advance) = 0.252`, consistent with random signs).

### 9.2 Attribution to a body — the decisive numbers

The axial channel is **99.98 %** of the budget in every arm (`axial` ≈ `⟨|ΔΔψ|⟩` to four digits); roll is 1–5 %
and the residual 2–6 %. Splitting the axial jitter of the candidate coordinate by source (median `|Δarc|`):

| Removed | `median|Δarc|` | Share of A's jitter removed |
|---|---|---|
| nothing (A) | 0.903 nm | — |
| filament Brownian (C) | 0.653 nm | **28 %** |
| filament + bound-motor Brownian (B) | 0.656 nm | 27 % (bound-motor contributes **≈ 0**) |
| bound-motor only (D) | 0.944 nm | 0 % (within seed scatter) |
| **+ unbound-motor Brownian (E)** | **0.005 nm** | **99.4 %** |

**The bound-motor Brownian contributes nothing to this coordinate, for a structural reason:** a *persisting
candidate* is by construction an **unbound** head — it was offered, the hazard rejected it, and it was returned
to the FREE pool. The phase that the target-zone mechanism must track is therefore a property of a head that is
**required to be noisy in order to search for actin at all**. Arms B and E differ in exactly this one channel,
and it accounts for **~72 %** of the jitter directly and for essentially all of the remainder after the filament
is frozen.

### 9.3 The coherence budget

`t_c = 2·D_ax / v²` is the time at which the diffusive phase spread `twistRate·√(2 D_ax t)` equals the drift
`|twistRate·v|·t`; a leading/trailing asymmetry is only available if the candidate residence is `≳ t_c`.
Computed from the **ensemble-mean** `D_ax` and `glide` (the per-seed ratio is heavy-tailed and its per-seed
average, printed in the log, must not be read — some Arm-E seeds have `v ≈ 0`, sending their own `t_c` to
infinity):

| Arm | `t_c` (median `D_ax`) | `t_c` (mean-square `D_ax`) | ⟨residence⟩ | residence / `t_c` |
|---|---|---|---|---|
| A | 24 900 steps | 24 900 | 1.78 | 7 × 10⁻⁵ |
| **B** | **51 600 steps** | 54 800 | 1.98 | **4 × 10⁻⁵** |
| C | 26 500 steps | 27 200 | 2.03 | 8 × 10⁻⁵ |
| D | 61 600 steps | 58 200 | 1.74 | 3 × 10⁻⁵ |
| **E** | **119 steps** | 3 250 | **147.8** | **1.24  (0.045 on the conservative estimator)** |

**Arm E is the only arm in which the candidate residence reaches the phase-coherence time** — by a factor
2 × 10⁴ over Arms A–D on the like-for-like estimator. Its increment autocorrelation is `ac1 = +0.717` (a
genuinely correlated, drifting phase) against `−0.11 … −0.15` (white) in every noisy arm, and `P(mono) = 0.583`
against 0.25–0.31.

## 10. Torque and twirling results

```
arm   τnet ± SEM (N·m)          seeds same sign   Σ|τ|       cancel   frac heads τ>0   turns ± SEM      per-seg roll SD   coherentRoll
A     +3.925e-22 ± 2.5e-22          7/10       3.07e-20      78.2       0.5055       +0.705 ± 0.371     3.370 turns        0.287
B     -8.889e-22 ± 2.4e-22          9/10       8.42e-21       9.5       0.4610       -0.103 ± 0.154     1.248 turns        0.352
C     +2.711e-22 ± 3.8e-22          7/10       2.97e-20     109.7       0.5047       +1.118 ± 0.273     2.370 turns        0.506
D     -5.527e-23 ± 2.1e-22          6/10       1.97e-20     357.0       0.4990       +0.723 ± 0.348     2.648 turns        0.382
E     +5.391e-23 ± 7.6e-23          5/10       5.95e-22      11.0       0.5316       -0.148 ± 0.096     0.659 turns        0.516
F1    -2.356e-22 ± 2.8e-22          6/10       3.92e-20     166.5       0.4988       +0.275 ± 0.277     3.191 turns        0.244
F2    -3.812e-22 ± 2.4e-22          7/10       9.38e-21      24.6       0.4768       +0.295 ± 0.175     1.111 turns        0.466
```

### 10.1 A net axial torque DOES emerge in Arm B — and it is NOT the target-zone mechanism (Outcome N5 caveat)

Arm B is the first arm in this lineage with a net axial torque resolved from zero: `τnet = −8.9e-22 ± 2.4e-22`
N·m, **3.7σ, with 9/10 seeds of the same sign**, and a cancellation ratio of 9.5 (against 78–357 in the noisy
arms). Quieting the filament and the bound complex genuinely stops the per-head torques from cancelling.

**But the target-zone-off control shows the same thing.** F2 — *identical* Brownian policy, `alphaPsi = 0`, i.e.
the target-zone hazard switched off — gives `τnet = −3.8e-22 ± 2.4e-22` of the **same sign**, 7/10 seeds. The
target-zone-attributable difference is `−5.1e-22 ± 3.4e-22`, **1.5σ — not resolved**. The torque is therefore a
property of the *quiet off-axis surface-binding scene*, not of the angular hazard.

The most likely origin is the scene limitation already flagged in the Stage-A report (§18 limitation 2): **every
motor shares one base orientation frame** `bhat/econv/eup`, so every head's binding direction lies in the same
plane and the off-axis F8 reactions carry a common geometric bias. Thermal noise previously masked it. **This is
recorded as an Outcome-N5 finding and explicitly not attributed to the Vilfan mechanism.**

### 10.2 No twirling, in any arm

A twirling claim requires all five of: reproducible sign across seeds, nonzero ensemble mean, coherent roll along
the filament, a stable translation–rotation relation, and a target-zone-off control lacking the signal. **Cumulative
turns fail at least two of these in every arm:**

- Arm B's `turns = −0.103 ± 0.154` is **0.7σ** — not resolved, and of *opposite sign* to its own resolved torque.
- **Roll is not coherent along the filament anywhere:** the per-segment roll standard deviation is 0.66–3.37
  turns against ensemble means of 0.10–1.12, so `|mean|/SD = 0.24–0.52 < 1` in every arm. The filament twists
  internally far more than it rotates as a body.
- The target-zone-off controls carry comparable turns (F1 +0.275 ± 0.277, F2 +0.295 ± 0.175), and F2's *exceeds*
  its own Arm-B counterpart.
- `turns/µm` remains unusable at these run lengths (the glide distance is ~0.03–0.05 µm), with SEMs of 6–117.

**No pitch is claimed and none is claimed to be biological.** `RollSpringSystem` was never enabled.

## 11. Second-stage filament decomposition (Arms G/H/I)

Run only after A–F, 10 seeds × 6000 steps, device-resident — log
`RUN_LOGS/vilfan_brownian/ablation_decompose_d800.txt`. Each arm re-enables **one** filament Brownian channel
group on top of the Arm-B motor policy (bound-motor Brownian off), so the channel under test is the only noise
acting on the bound complex.

```
arm                       glide µm/s    avgB  accF  median|Δarc|  ⟨|ΔΔψ|⟩  axial    roll     leadAcc−leadCand ± SEM   τnet ± SEM (same sign)
B  (no filament noise)    -1.717±0.070  7.72  0.125   0.656 nm    0.84978  0.84997  0.01622   -0.0188 ± 0.0139   -8.89e-22±2.4e-22 (9/10)
G  axial force only       -2.179±0.061  6.87  0.151   0.897 nm    1.10652  1.10679  0.01863   -0.0250 ± 0.0170   -7.09e-22±2.0e-22 (10/10)
H  roll torque only       -1.663±0.101  7.77  0.121   0.661 nm    0.84105  0.84037  0.01766   -0.0081 ± 0.0171   -5.97e-22±2.1e-22 (8/10)
I  transverse + bend      -1.753±0.065  8.54  0.130   0.685 nm    0.87007  0.87027  0.05621   +0.0179 ± 0.0107   -8.21e-22±2.6e-22 (9/10)
A  (all filament noise)   -3.394±0.229  5.89  0.139   0.903 nm    1.11458  1.11404  0.05503   -0.0024 ± 0.0228   +3.93e-22±2.5e-22 (7/10)
```

### 11.1 The filament's whole contribution to the target-zone phase is the AXIAL translational channel

Re-enabling **only** the axial Brownian force (Arm G) restores the phase jitter essentially to the full-noise
value: `median|Δarc|` 0.656 → **0.897 nm** against Arm A's 0.903 nm, and `⟨|ΔΔψ|⟩` 0.850 → **1.107** against
A's 1.115. Roll noise alone (H) changes nothing (0.661 nm, 0.841 rad/step ≈ Arm B); transverse + bend (I) adds
only ~4 % (0.685 nm, 0.870 rad/step). So the complete attribution of the candidate-coordinate phase jitter is:

| Channel | Share of the total phase jitter |
|---|---|
| **UNBOUND-motor Brownian** (beam nodes + `phi` + `psi` of a searching head) | **≈ 72 %** |
| Filament **axial** translational Brownian force | ≈ 27 % |
| Filament transverse + bending Brownian | ≈ 3 % |
| Filament **roll** Brownian torque | ≈ 0 % |
| **BOUND-motor Brownian** | **≈ 0 %** (structurally — a candidate is unbound) |

This is the quantitative answer to the task's question "which Brownian forcing channels destroy phase coherence".
Note the two channels a twirling mechanism would most naturally be blamed on — filament **roll** and **bound**-motor
noise — contribute **nothing**.

### 11.2 The decomposition confirms the N5 torque caveat

Every bound-motor-quiet arm carries the **same negative** `τnet` with high seed agreement — B −8.9e-22 (9/10),
G −7.1e-22 (**10/10**), H −6.0e-22 (8/10), I −8.2e-22 (9/10) — including **F2 at `alphaPsi = 0`** (−3.8e-22,
7/10). Conversely the arms with bound-motor Brownian ON (A, C) show no resolved torque, and Arm D (bound quiet
but *all* filament noise on) washes it back out (−5.5e-23, 6/10). The torque therefore tracks **"is the bound
complex thermally quiet?"**, not **"is the target zone on?"**. It is not a Vilfan torque.

### 11.3 Extended Arm E — the coherent arm, at 4× duration

Arm E's short-campaign statistics were too thin to test the flux endpoint (111 attachments), so **its duration was
extended 4× and nothing else was changed** — no parameter, no binding gate, no repositioning, exactly as the task
requires. 10 seeds × 24 000 steps, device-resident; log `RUN_LOGS/vilfan_brownian/armE_extended_a6.txt`.

```
single: all-off  alpha=6   glide -0.360±0.045 | avgB 0.65 | detach/step 0.001 | accF 0.004
                           ⟨Δψ⟩acc -0.2990±0.0458 | ⟨Δψ⟩cand -0.4174 | leadAcc−leadCand +0.0475±0.0687 (6/10)
                           lead/trail 204/91 | τnet +1.35e-22±3.3e-23 (9/10) | cancel 3.2 | turns -0.2397±0.1331
  phase: drift 0.00097 | ⟨|ΔΔψ|⟩ 0.00854 | axial 0.00854 | roll 0.00158 | resid(A) 0.00159 | ac1 +0.676
         median|Δarc| 0.0003 nm | ⟨residence⟩ 508.4 steps | P(mono) 0.554 | pairs 149950 runs 352
  candidate histogram: [19521 27784 50203 5985 652 108 | 67 225 1687 3941 14470 25664]   (LEAD | TRAIL)
  accepted  histogram: [    0     0     5   41  76 82 | 50  29   11    1     0     0]
```

**Phase coherence is confirmed restored, decisively.** `⟨|ΔΔψ|⟩ = 0.0085` rad/step against a drift of 0.00097 —
a noise/drift ratio of **8.8**, versus 122–187 in every noisy arm. `ac1 = +0.676` (strongly correlated),
`P(mono) = 0.554`, mean candidate residence **508 steps**, `median|Δarc| = 0.3 pm`. This is the regime the
mechanism needs.

**The signed attachment-flux asymmetry is nevertheless STILL NOT RESOLVED, and the raw `⟨Δψ⟩` must not be
misread.** `⟨Δψ⟩acc = −0.2990 ± 0.0458` is 6.5σ from zero and looks like a large effect. It is not one:
**the candidate pool itself is `⟨Δψ⟩cand = −0.4174`**, and the histograms show why — 104 253 of 150 004 candidate
samples (69.5 %) sit on the leading side, and the accepted counts are 204/91 = **69.2 % leading**. The paired
statistic that cancels the pool asymmetry, which is the actual endpoint, is
**`leadAcc − leadCand = +0.0475 ± 0.0687` — 0.7σ, 6/10 seeds.** The apparent bias is inherited from the candidate
geometry, not created by the hazard.

**Why Arm E's candidate pool is so skewed, stated as the limitation it is.** With all Brownian forcing removed the
same handful of heads persists as a candidate for hundreds of steps (508 on average, 172 of 352 runs are ≥8
steps), so the 150 000 "candidate samples" are ~352 statistically independent encounters, massively
autocorrelated, each frozen near whatever phase the initial geometry gave it. **Arm E buys coherence at the cost
of ergodicity**: `avgBound` 0.65, `accFrac` 0.004, glide −0.36 µm/s. The two properties are not separable here —
both follow from removing the search noise — so Arm E can demonstrate that the coherence budget is *reachable*
but cannot, at any duration, deliver a well-sampled flux measurement.

### 11.4 The extended Arm-E `alphaPsi = 0` control — and what it proves about the pool skew

Same policy, same duration, target zone OFF; log `RUN_LOGS/vilfan_brownian/armE_extended_a0.txt`.

```
single: all-off  alpha=0   glide +0.038±0.032 | avgB 0.94 | accF 1.000
                           ⟨Δψ⟩acc = ⟨Δψ⟩cand +0.1089±0.0925 | lead/trail 222/219 | τnet -2.14e-23±3.2e-23 (4/10)
  candidate histogram: [34 28 39 29 45 24 | 37 38 45 41 45 36]     ← FLAT and balanced
  candidate-residence histogram: [441 0 0 0 0 0 0 0]               ← every encounter lasts exactly 1 step
```

**The scene itself is unbiased.** With the hazard off, the Arm-E candidate distribution is **flat across all
twelve `Δψ` bins**, `⟨Δψ⟩cand = +0.1089 ± 0.0925` (1.2σ, consistent with zero) and leading/trailing is 222/219.

**Therefore the 69.5 % leading skew of the `alphaPsi = 6` candidate pool (§11.3) is created by the hazard's own
rejection, not by the geometry** — and the mechanism is visible in the residence histograms. At `alphaPsi = 0`
every candidate is accepted immediately, so every run has length exactly 1 (441 of 441). At `alphaPsi = 6` a
badly-registered head is rejected and, with no Brownian forcing to move it away, **stays a candidate for hundreds
of steps and is re-counted every step** (172 of 352 runs are ≥8 steps long). The 150 000 "candidate samples" are
therefore a few hundred stuck heads sampled repeatedly, weighted by how badly registered they are. This is a
sampling pathology of the fully-deterministic arm, and it is why the paired statistic — not the raw `⟨Δψ⟩` — is
the only trustworthy endpoint there.

### 11.5 A resolved, target-zone-dependent torque in Arm E — from azimuth concentration, not from flux bias

One endpoint *does* separate from its control in Arm E:

```
Arm E, alphaPsi = 6 : τnet = +1.35e-22 ± 3.3e-23 N·m   (9/10 seeds same sign, 4.1σ)   cancel 3.2
Arm E, alphaPsi = 0 : τnet = -2.14e-23 ± 3.2e-23 N·m   (4/10 seeds,           0.7σ)   cancel 24.0
difference                +1.56e-22 ± 4.6e-23                                          3.4σ
```

This is the **first target-zone-attributable net axial torque anywhere in this lineage**. It must not be claimed
as the Vilfan mechanism, for three reasons that the same run supplies:

1. **The flux endpoint it is supposed to come from is null** in the very same arm
   (`leadAcc − leadCand = +0.0475 ± 0.0687`). A Vilfan torque is downstream of a leading/trailing attachment
   asymmetry; there isn't one.
2. **It is fully explained by azimuth concentration.** The accepted-azimuth histogram is sharply peaked at
   `alphaPsi = 6` (`[4319 8259 11713 6143 526 0 0 485]`) and flat at `alphaPsi = 0`
   (`[5609 7301 4351 8455 4629 4501 5188 5366]`); the cancellation ratio falls 24.0 → 3.2 accordingly. With
   `avgBound = 0.65`, "net torque" is essentially the torque of the occasional *single* bound head, and a single
   head's off-axis F8 torque has no reason to average to zero once its attachment face is constrained.
3. **It is confounded by the shared motor base frame** (Stage-A limitation 2): all motors share one
   `bhat/econv/eup`, so concentrating the accepted *material* azimuth also concentrates it around a common
   *laboratory* direction. This is the same confound as §10.1 and must be removed — by giving each motor a random
   base azimuth — before any torque in a quiet scene can be interpreted.

No twirling accompanies it: `turns = −0.2397 ± 0.1331` (1.8σ, **opposite in sign to the torque**) and
`coherentRoll = 0.516 < 1` (roll is not coherent along the filament).

## 12. Symmetry controls

| Control | Result | Verdict |
|---|---|---|
| 1. Filament polarity reversal (`û → −û`) | `deltaPsi` flips sign exactly (fixture 7: +0.5500 / −0.5500) | PASS (unchanged — `matTargetZone` is byte-unchanged) |
| 2. Gliding-direction reversal | kinematic rig under the same kernel: `v = +2.5 → −2.5 µm/s` flips `⟨Δψ⟩` **+0.0568 → −0.0579** while the leading fraction stays 0.530 | PASS |
| 3. Mirrored helical handedness (dynamic, Arm-B policy, 1 seed × 3000 steps) | LEFT `⟨Δψ⟩acc = −0.0126`, `leadAcc = 0.444`, `τnet = +7.0e-22`; MIRRORED `+0.0335`, `0.533`, `τnet = −5.6e-22` — every quantity changes sign as required | **consistent with the expected chirality reversal, but 1 seed ⇒ underpowered** (the 10-seed SEM on `⟨Δψ⟩acc` is ±0.009); not interpreted as a signal |
| 4. Rigid laboratory rotation | `deltaPsi` invariant to 2e-4 rad; the accept decision is identical over 12 rotations (fixtures 5, 13) | PASS (unchanged) |
| 5. No-translation control | kinematic rig `v = 0` ⇒ `⟨Δψ⟩ = −0.0010` over 40 000 steps | PASS |
| 6. `alphaPsi = 0` | Arms F1/F2: `accFrac = 1.000`, `leadAcc − leadCand ≡ 0` by construction, and (§10.1) they carry the *same* torque sign as their `alphaPsi = 6` counterparts | PASS — and it is what exposes the N5 caveat |
| 7. Matched seed ensembles | all arms use seeds `101 + 101·i`, i = 0…9, matched across arms | PASS |
| 8. Registry / roll spring zero | Stage B never entered; `RollSpringSystem` never enabled | PASS (trivially) |

The mirrored-handedness control is the one place where the dynamic assay shows the expected chiral reversal in
every channel simultaneously; it is reported because it is informative, and flagged as underpowered because one
seed cannot resolve a ±0.009 quantity.

## 13. Verdict against the decision logic (N1–N5)

**Did Arm B restore the kinetic mechanism? NO. Did it restore sustained twirling? NO.**

| Outcome | Applies? | Evidence |
|---|---|---|
| **N1** clean rescue | **NO** | Arm B restores neither phase coherence (§9.1: noise/drift *rose* 122 → 184) nor flux asymmetry (§8.1: `leadAcc − leadCand = −0.0188 ± 0.0139`) nor twirling (§10.2). |
| **N2** kinetic rescue only | **NO** | requires Arm B to restore coherent drift and flux bias; it restored neither. |
| **N3** no rescue despite a quiet bound state and filament | **YES — and its required diagnosis is completed** | see below. |
| **N4** deterministic arm works, Brownian-search arm fails | **YES for N4's stated conclusion; NO for its premise "Arm E works"** | see below. |
| **N5** target-zone-off arm also shows torque | **YES — active, twice** | §10.1 (Arm B's 3.7σ torque is matched in sign by its `alphaPsi = 0` control F2; the target-zone increment is 1.5σ) and §11.5 (Arm E's torque *is* target-zone-dependent at 3.4σ but is explained by azimuth concentration under a shared base frame, not by flux bias). |

### 13.1 N3's diagnosis, item by item (the task's four candidates)

- **"Unbound motor search noise randomizes the candidate phase before binding" — CONFIRMED, and it is the
  dominant channel (≈ 72 %, §11.1).** A persisting target-zone candidate is *by construction* an unbound head, so
  the phase the mechanism must track is a property of a head that has to be noisy in order to find actin at all.
  Arms B and E differ in exactly this one channel and it moves `median|Δarc|` from 0.656 nm to 0.005 nm.
- **"The perpendicular-foot candidate definition itself destroys site persistence" — EXCLUDED.** The same-site
  fraction is 0.991–1.000 in every arm; candidates keep their attachment site, the phase moves *on* that site.
- **"Candidate residence is too short" — CONFIRMED as a symptom, not a cause.** Residence is 1.74–2.03 steps
  against `t_c ≈ 25 000–62 000` steps in Arms A–D (ratio ~5 × 10⁻⁵). It becomes 508 steps in Arm E, but only
  because the same heads get stuck (§11.4).
- **"Deterministic solver motion causes the phase jitter" — EXCLUDED.** With every Brownian channel off, the
  residual phase motion is 0.0085 rad/step (§11.3), ~1 % of Arm B's 0.850.

### 13.2 The precise statement this campaign supports

> **The Brownian forcing that destroys the Vilfan target-zone phase is, dominantly (≈ 72 %), the Brownian motion
> of the UNBOUND, searching motor — the noise the model requires in order to bind at all. The filament's
> contribution (≈ 28 %) is almost entirely its AXIAL translational channel. Filament roll noise and BOUND-motor
> noise contribute essentially nothing. Making the bound complex and the filament mechanically quiet — the
> task's primary hypothesis — therefore cannot rescue the mechanism, and in fact worsens the noise-to-drift ratio
> because it halves the glide. Removing the search noise as well does restore full phase coherence, but it
> simultaneously collapses engagement and ergodicity, so no arm of this experiment delivers a resolved
> leading/trailing attachment-flux asymmetry.**

This is N4's conclusion ("Brownian motion required for unbound motor search is itself sufficient to destroy
target-zone coherence under the current candidate-coordinate definition") established quantitatively, while
explicitly declining N4's premise that Arm E "works" — Arm E works for *coherence* only.

## 14. Canonical-status statement

Entirely **noncanonical, flag-gated, default-off, byte-identical when disabled**. No canonical default, parameter,
chemistry, force law, S2/stroke mechanics, dt, RNG stream, event ordering, manifest or `MotorModel.CANON_VERSION`
changed; **`MotorModel.CANON_VERSION` was not bumped**. No parameter was tuned; `alphaPsi`, density, stiffness,
drag and chemistry are exactly the Stage-A values. `BoA-v1ref` was not touched.

Diff shape — additive only:

- `softbox/BrownianForceSystem.java` — **new** `brownChannelMask` kernel. `brownianForce` is **byte-unchanged**.
- `softbox/TwoBodyBeamAnalyticGpu.java` — `matS2SolveStep` gains `int mPolicy = matc.get(3)`, a three-line
  per-motor `brownM` derivation, and `brownOn` → `brownM` at the **three stochastic RHS sites only**. With
  `matc[3] == 0` the arithmetic is bit-identical. No other kernel changed; `matTargetZone`, `matBindExplicit`,
  `matSurfaceAzim`, `matSurfaceStericPrune` are byte-unchanged.
- `softbox/ExplicitCompleteMatHarness.java` — six `BR_*` statics + `brownChanOn()`/`motorBrownPolicy()`/
  `setBrownianPolicy()`/`resetBrownianPolicy()`/`brownianPolicyString()`; `ExMat.brChan` (a 4-float mask);
  `matc` widened to 4 with `[3] = motorBrownPolicy()`; one gated call in `stepGlidingCPU`; one gated task + one
  gated transfer + one gated worker-grid entry in `buildGlidingGraph`. With the defaults every one of these is
  inert and the graph is the pre-increment graph.
- `softbox/ExplicitMatSolveHarness.java` — its private `matc` widened to 4 (`[3] = 0`, canonical).
- `softbox/VilfanTargetZoneHarness.java` — the ablation policies, fixtures, equivalence mode and campaign. The
  pre-existing `-fixtures` / `-equiv` / `-stageA` / `-dt` / `-3js` modes are unchanged.
- `scripts/run_vilfan_targetzone.sh` — documentation header only (the launch flags are unchanged and still carry
  the required `-Dtornado.enable.fma=false -Dtornado.recover.bailout=false -Dtornado.tvm.maxbytecodesize=65536`).
- **new**: this report, `RUN_LOGS/vilfan_brownian/`.

**No parallel target-zone implementation was created.** `matTargetZone`, the harness's target-zone rig, the
fixtures and the Stage-A campaign code are reused as they stand.

### 14.1 Regression suite (all PASS, with the pre-increment numbers)

Run after the campaign; logs `RUN_LOGS/vilfan_brownian/regress_*.txt`.

| Check | Result |
|---|---|
| Canonical explicit-S2 device gate, `run_singlehead_gpu.sh -gliding` | `§8 bind-identity mism=0 ⇒ PASS \| §10 gliding binds=11 detach=10 boundC=1/1 maxΔfil=1.3e-01 firstDiv=t=4 invalid=0 ⇒ PASS`, **`COMPLETE-MAT GATE: PASS`** — **identical** to the values in the Stage-A report §17 and `EXPLICIT_S2_GPU_LOWERING_REGRESSION_FINDINGS.md` §11 |
| Canonical explicit-S2 device gate, `-traj` | `§7 one-step Δnode=1.3e-08 ⇒ PASS \| §8 300 steps maxΔnode=4.9e-08 firstDiv=none ⇒ PASS`, **`COMPLETE-MAT GATE: PASS`** |
| Stage-A target-zone fixtures | **16 PASS, 0 FAIL** (unchanged) |
| Legacy helical-surface twirl fixtures | **28 PASS, 0 FAIL** (unchanged) |
| Stage-A device equivalence (`-equiv`) | `surface OFF max|Δpsi0|=2.9e-06 firstDiv=t=4`; `surface ON max|Δpsi0|=5.2e-06 firstDiv=t=181` — **digit-for-digit the pre-increment values** |
| Stage-A dynamic campaign | Arms A and F1 of §8 reproduce the pre-increment 10-seed Stage-A campaign digit-for-digit, including the full 12-bin mismatch histogram |

Off-path identity is therefore **verified, not asserted**: fixtures 101/104 (exact identity of the mask kernel and
of the `full`-policy trajectory), the `-brownian-equiv` `full` row, the four canonical gates above, and the
digit-for-digit reproduction of the entire predecessor campaign.


---

## 14.2 Logs and 3js output

All campaign logs live in `RUN_LOGS/vilfan_brownian/`:

```
fixtures.txt                  the 12 deterministic Brownian-mask fixtures
equiv.txt                     device residency + CPU/GPU equivalence under four policies
pilot_d800.txt                the 1-seed engagement pilot (run before committing the ensemble)
ablation_main_d800.txt        arms A–F, 10 seeds × 6000 steps, device-resident
ablation_decompose_d800.txt   arms G/H/I (+ A–F re-run), 10 seeds × 6000 steps
armE_extended_a6.txt          arm E at 4× duration, 10 seeds × 24 000 steps, alphaPsi = 6
armE_extended_a0.txt          the matched arm-E target-zone-OFF control
regress_completemat_gliding.txt, regress_completemat_traj.txt,
regress_stageA_fixtures.txt, regress_helical.txt     the §14.1 regression suite
3js_armA.txt, 3js_armB.txt    the viewer renders
```

Four matched movies (density 150, seed 101, 6000 steps, stride 40 ⇒ **151 frames each**), identical in every
respect except the Brownian policy and the target zone, so the ablation is directly watchable — the Arm-B
filament visibly stops jittering while the unbound motors keep searching:

```
threejs_vilfan_brownA_targetzone   Arm A  full Brownian, alphaPsi = 6   (fil ax/tr/roll/othRot ON, motor unbound+bound ON)
threejs_vilfan_brownA_blind        Arm F1 full Brownian, target zone OFF
threejs_vilfan_brownB_targetzone   Arm B  PRIMARY clean test, alphaPsi = 6   (filament OFF, unbound ON, bound OFF)
threejs_vilfan_brownB_blind        Arm F2 same policy, target zone OFF
```
Each render logs its resolved Brownian policy alongside the frame count, so a movie cannot be mislabelled.

Frames carry the actin segments, per-segment material-frame roll ticks, the off-axis cross-bridge lines, the local
actin surface-normal marker, and the motor binding-direction marker coloured by the angular mismatch — all
visualisation only, never force-bearing.

**Generate:**
```
./scripts/run_vilfan_targetzone.sh -3js threejs_vilfan_brownB -steps 6000 -stride 40 -density 150 \
    -seed 101 -target-zone-alpha 6 -brownian-policy unbound-search-only
```
**View:** from `~/Code`: `python3 SoftBox/sim_server.py 8000`, then open
`http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → the `vilfan_brown` runs).

---

## 15. Statistical uncertainty

- All arm-level quantities are **mean ± SEM over 10 independent seeds** (`101 + 101·i`), matched across arms;
  `n − 1` denominator. Seed-level sign agreement is reported alongside every torque and paired-flux value.
- **Counting statistics.** Arms A–D/F/G–I have 1000–1600 attachments per arm; Poisson σ on a leading/trailing
  difference of ~1100 events is ≈ 33, i.e. a leading fraction resolution of ≈ 0.015 — the same order as the
  quoted SEMs, so the two agree. Arm E has 111 (short) / 295 (extended) attachments.
- **The paired statistic `leadAcc − leadCand` is the endpoint**, not `⟨Δψ⟩acc`: it cancels candidate-pool
  asymmetry, which §11.3/§11.4 show can be large and hazard-induced.
- **Multiple comparisons.** Eleven arms were tested against ~4 endpoints each. `Arm I`'s
  `leadAcc − leadCand = +0.0179 ± 0.0107` (1.7σ, the mechanism's sign) is the largest positive flux statistic
  anywhere in the campaign; at this number of comparisons a 1.7σ excursion is expected by chance, it has no
  coherent mechanism (Arm I is the *noisiest* of G/H/I), and it is **not** claimed as a signal.
- **Autocorrelation.** Candidate samples are not independent (§11.4); per-seed SEMs are the correct error bar,
  raw candidate counts are not.
- **The cancellation ratio `Σ|τ|/|Στ|` remains ill-conditioned** where `τnet` is consistent with zero, exactly as
  the Stage-A report established. It is quoted only for arms whose `τnet` is itself resolved (B, G, E-α6).
- **The mirrored-handedness dynamic control is 1 seed** and is reported as underpowered, not as evidence.

## 16. Limitations

1. **Arm E is not ergodic.** Coherence and sampling cannot be separated in it (§11.3/§11.4); it demonstrates that
   the coherence budget is reachable, not that the mechanism operates.
2. **The shared motor base frame is now load-bearing.** With the noise removed, the torque endpoint is confounded
   by all motors sharing one `bhat/econv/eup` (§10.1, §11.5). This was a cosmetic limitation in Stage A; it is
   the first-order obstacle to any torque claim in a quiet scene.
3. **Bound-motor Brownian could not be shown to matter — because it structurally cannot** for a *candidate*
   coordinate. It may still matter for a *bound* head's registry, which is Stage B, which remains blocked
   (`docs/VILFAN_TARGET_ZONE_BINDING_AND_TWIRLING_FINDINGS.md` §12: no head rotational DOF about the bond).
4. **Cross-arm trajectory identity is impossible** (§4); all comparisons are matched ensembles.
5. **`D_ax` is a candidate-coordinate wander, not a filament diffusivity**, and the mean-square and median
   estimators differ by up to 30× in Arm E; both are reported and the conclusions hold on either.
6. **Single density, single filament length, single motor model, single dt** (ρ = 800, explicit-s2-l40,
   dt = 2.5 µs). The Stage-A dt study already showed the coherence ratio *worsens* at finer dt.
7. **No axial site search** and **one-angle mismatch only** — inherited Stage-A limitations, unchanged here.
8. Arm E's initial geometry permits binding without repositioning, but only barely (`accFrac = 0.004`).

## 17. Exact next smallest step

**Do not add a torsional registry, a roll spring, a lateral stroke, an axial confinement spring, or any new force
to chase the Arm-E torque.** Two of this campaign's findings say what to do instead, and the first is cheap:

> **Give each motor an independent random base azimuth (`bhat/econv/eup` per motor) and re-run Arms B, F2, and
> the two Arm-E arms unchanged.**

This is a **scene** change, not a physics change — no force law, no parameter, no chemistry — and it is the
single measurement that decides whether the resolved torques of §10.1 and §11.5 are a lab-frame artefact of the
shared frame (predicted: they collapse to zero) or a real property of concentrated stereospecific attachment
(predicted: they survive with the same sign). **Until it is done, no torque from a quiet-scene arm can be
interpreted**, and it is the cheapest possible discriminator.

If the torques survive that control, the mechanism to pursue is **not** the kinetic one: this campaign has now
shown the kinetic target-zone route is closed at biological drag and temperature, because the coherence it needs
is destroyed by the very search noise that makes binding possible (§13.2), and no timestep refinement helps
(Stage-A §15). The remaining route is the **mechanical** one — Stage B — which does not depend on kinetic phase
coherence at all, and which stays blocked on **giving the explicit-S2 head a rotational degree of freedom about
the bond** inside `matS2SolveStep`. That remains the single highest-value structural change.

---

## Completion summary

- **Question asked:** which Brownian forcing channels destroy the Vilfan moving-target-zone phase coherence, and
  does making the bound actomyosin complex and the filament mechanically quiet restore the mechanism?
- **Answer: the UNBOUND, searching motor's own Brownian motion (≈ 72 %), plus the filament's AXIAL translational
  channel (≈ 27 %). Bound-motor noise and filament roll noise contribute essentially nothing. The primary
  hypothesis (Arm B) does NOT restore the mechanism — it makes the noise-to-drift ratio worse.**
- **Arm B restored the kinetic mechanism? NO.** `leadAcc − leadCand = −0.0188 ± 0.0139` (1.4σ, wrong sign);
  `⟨Δψ⟩acc = +0.0104 ± 0.0085` (1.2σ); noise/drift 122 → 184.
- **Arm B restored sustained twirling? NO.** Roll is incoherent along the filament in every arm
  (`|mean|/SD = 0.16–0.52`); `turns = −0.103 ± 0.154` (0.7σ), opposite in sign to its own torque.
- **Classification: N3 (with its diagnosis completed) + N4's conclusion on the coherence question + an active
  N5 caveat on both resolved torques.** Not N1, not N2.
- **Fixtures:** 12/12 PASS, including a per-step per-motor audit proving a bound head receives exactly zero
  Brownian and an unbound head keeps it, with both transition directions covered.
- **CPU/GPU:** the full graph is device-resident under every policy, no fallback (`bailout=false`); attachment
  events and accept decisions exactly CPU≡GPU; the quiet arms are **bit-close for all 200 steps** where the
  canonical arm decorrelates at t = 181.
- **Canonical status:** noncanonical, flag-gated, default-off, byte-identical when disabled; `CANON_VERSION` not
  bumped; Arm A and F1 reproduce the pre-increment Stage-A campaign digit-for-digit.
- **Exact next smallest step:** per-motor random base azimuth (a scene change, no new physics), then re-run
  Arms B / F2 / E — the cheapest discriminator for the two N5 torque confounds.