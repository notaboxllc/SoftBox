# Mechanically free S2 length as an assay fixture — homogeneous and heterogeneous lawns

**Scope:** whether replacing the current homogeneous 40 nm free-S2 lawn with a plausible heterogeneous lawn
changes the core **gliding** predictions, their variability, or their density dependence. Twirling is an
auxiliary phenotype read from the same runs and never used to select a distribution.

---

## 1. Executive conclusion

**STATUS: COMPLETE — audit, Study A (homogeneous map, H3 confirmed under dt refinement) and Study B
(first heterogeneous test, D4 vs D5) are all run and written up. §§9–19 report Study B.**

**CORE GLIDING RESULT — mean gliding speed IS sensitive to mechanically free S2 length.** With both ε signs
present the proper ε-EVEN estimator gives **dv_even/dL = −0.02171 ± 0.00754 (µm/s)/nm, 2.88σ, 88 % of seeds** —
|v| *rises* with free length, −2.341 µm/s at 25 nm → −2.819 at 50 nm, **≈ 20 % across the plausible range**.
(The single-ε arm alone read 3.69σ / 37 %; the ε-even value is the smaller and correct one and supersedes it.)
Pairwise comparisons against 40 nm resolve nothing (≤1.82σ) — only the matched-seed trend test resolves this.
**Classification H3 (mean-sensitive), CONFIRMED under dt refinement** — the likely carrier (the post-stroke
tail) is exactly the dt-sensitive channel of §23.13a, so the full six-length dt/2 map was run (§7.1): the sign
is preserved, the paired slope change is 0.54σ and the pooled slope is 3.28σ. Velocity *variability*
shows no clean length dependence (CV 0.19–0.39, non-monotone) ⇒ **not H2**. Density dependence **unmeasured**.

**SECONDARY TWIRLING RESULT — there is NO resolved length dependence of twirling.** The ε-ODD map looks jagged
(Ω_odd = −12.0, −17.2, −17.1, −4.0, −12.7, −2.9 rad/s at 25–50 nm) but a χ² test against a single common value
gives **χ²/dof = 1.63 on 5 dof — consistent with pure seed noise** about −11.3 rad/s; the trend is
dΩ_odd/dL = +0.410 ± 0.210 (1.95σ), unresolved. **The twirl sign is NEGATIVE at every length** (preserved), and
no S2-dependent twirl peak exists to be believed — so the mirror control the brief requires before crediting
such a peak was **deliberately not spent**. **H5 (twirling-sensitive only) is REFUTED.**

The audit was the gating deliverable and it **passes decisively on feasibility**: per-motor free S2 length is a
**data-only** change. `params` is already a per-motor planar buffer and both runners already read it per motor,
so heterogeneity needs **no kernel edit, no buffer-size change and no TaskGraph topology change**. It also
surfaced **one real hazard** — a legacy scalar-assuming code path that would silently ignore heterogeneity —
which must be guarded before any campaign.

**STUDY B HEADLINE (§§9–19): a heterogeneous lawn behaves like its MEAN.** D4 (25 % @ 30 nm + 75 % @ 40 nm)
versus mean-matched homogeneous D5 (37.5 nm), 24 matched seeds, 96/96 records, gives
**Δ_mean(v) = +0.005 ± 0.152 µm/s (0.03σ)**, 50 % seed sign ⇒ **B1**. Within D4 the short class is
under-recruited 13–15 % and under-propulsive 19 % yet carries **38 % more axial torque than its deposited
share** — a selection signature that never reaches the population endpoints (partial B4). **Δ_mix (1.08σ,
n = 8) is underpowered, so B3 is untested rather than refuted.** Density dependence remains **unmeasured and
explicitly not gated**.

## 2. Scientific scope and restraint

The 40 nm free S2 length is an **idealised assay boundary condition**, not a measured quantity. "Free S2 length"
is read as the *effective mechanically unsupported span* between the substrate-supported part of HMM and the
converter/S2 junction — **not** a literal contour length lying flat on the substrate.

A nonspecifically adsorbed HMM lawn plausibly carries **quenched** heterogeneity in that effective span. This
study must separate: (1) mean effect, (2) variance at fixed mean, (3) skew / short-supported subpopulation,
(4) recruitment selection, (5) load-bearing selection, (6) genuine mixed-population mechanical interaction.

**The initial distributions are fixture-sensitivity constructs, not biologically calibrated lawns.** No
distribution may be preferred because it produces a desired twirling pitch.

## 3. Source-code and parameter audit (COMPLETE)

### 3.1 Where free S2 length enters

`ChiralSiteHarness.build()` → `TwoBodyConverterMotor.buildS2Mat(density, dt, **40.0**, EXPLICIT_GLIDE_SLACK_NM,
seed, rigid)`. Inside `buildS2Mat`:

```
M    = round(Lnm / EXP4G_L0_NM)          EXP4G_L0_NM = 10 nm      ⇒ M = 4 at L = 40 nm
l0   = L / M                                                       ⇒ 10 nm
ks   = EXP4G_EA_SI / (l0·1e-6)           EA = 4.2e-9 N             ⇒ 0.4200 N/m
kb   = EXP4G_EI_SI / (l0·1e-6)           EI = 7.2e-28 N·m²         ⇒ 7.200e-20 N·m
g4E[m]    = A[m] − (L − slack)·b̂         (per-motor emergence point)
g4Node[m] = M+1 nodes interpolated E→P, + parabolic bow when slack > 0
g4floorZ  = min_m(E_m·ê_up) − 0.05        (GLOBAL substrate plane)
queryR    = G4_QUERYR + L + 0.01          (GLOBAL neighbour-search radius)
```

### 3.2 Does every motor currently share the same values? **Yes — but only because they are written that way.**

`ExplicitCompleteMatHarness.packExMat`:

```java
double[] pr = ExplicitMatSolveHarness.paramArr(G);          // ONE 17-element row from Glide2D scalars
for (int m = 0; m < N; m++) {
    …
    for (int c = 0; c < 17; c++) e.params.set(c*N + m, pr[c]);   // SAME row copied to every motor
}
```

`params` is a **planar per-motor buffer** (17 comps × N). The homogeneity is in the *fill loop*, not in the
storage. Layout (`paramArr`): `0 lb, 1 rF8x, 2 rF8y, 3 rConvx, 4 rConvy, 5 kF8Code, 6 kconv, 7 kbind,
8 gammaPhi, 9 gammaPsi, 10 dt, 11 **g4ks**, 12 **g4l0**, 13 **g4kb**, 14 g4floorZ, 15 g4kfloor, 16 g4gammaNode`.

### 3.3 Do the kernels read per-motor? **Yes, already.**

`TwoBodyBeamAnalyticGpu.beamRelaxAnalytic:95`, `matBeamGeom:311` and `matS2SolveStep:949` all read

```java
double ks = params.get(11*nM + m), l0um = params.get(12*nM + m), kbend = params.get(13*nM + m);
double floorZ = params.get(14*nM + m), kfloor = params.get(15*nM + m), gNode = params.get(16*nM + m);
```

⇒ **assigning different `ks_i`, `l0_i`, `kb_i` per motor requires no kernel change whatsoever.**

### 3.4 One physics implementation, two runners — no CPU/GPU divergence risk **on this path**

The CPU gliding runner (`ExplicitCompleteMatHarness.stepGlidingCPU`) calls
`TwoBodyBeamAnalyticGpu.matS2SolveStep` / `matBeamGeom` **directly** — the same kernel methods the TaskGraph
wires. Both runners therefore consume the identical per-motor `params`. This is the project's standing
device-agnostic invariant and it means heterogeneity is CPU/GPU-consistent by construction.

### 3.5 **HAZARD — a legacy path that silently assumes one shared length**

`TwoBodyConverterMotor.s2NodeForcesM:6771` and `s2SolveM:6785` read the **scalars**:

```java
double ks = G.g4ks, l0m = G.g4l0*1e-6;                                        // s2NodeForcesM
ExplicitBeamAnalytic.beamTangentFree(M, G.g4ks, G.g4kb, G.g4l0, …)            // s2SolveM
```

These serve `TwoBodyConverterMotor.stepGlideS2`, the older standalone CPU mat stepper. **Under a heterogeneous
lawn that path would silently use the global scalars and ignore every per-motor assignment.** Verified: the
ExplicitCompleteMat path never calls it (`stepGlideS2` appears in `ExplicitCompleteMatHarness` only inside a
comment describing task order), and `ChiralSiteHarness` never references it. **Mitigation required before any
campaign:** make the heterogeneous feature refuse to run — or assert — on any path that routes through
`s2SolveM`/`s2NodeForcesM`, rather than relying on the current call graph staying that way.

This is the direct answer to the audit question *"does any current code silently assume identical S2 element
lengths across motors?"* — **yes, and it is named above.**

### 3.6 M must stay GLOBAL — buffer strides depend on it

```java
int nodeStride = 3*(M + 1), sysStride = (3*M + 2)*(3*M + 3);     // packExMat
e.exCounts = IntArray.fromElements(N, 1, M, nSeg);               // M is a single scalar for all motors
```

Per-motor `M` would require ragged node/scratch buffers and a kernel-topology change — an invasive redesign. So
**hold M = 4 fixed and vary `l0_i = L_i / M`**, exactly as the brief instructs. Consequence: at L ∈ [25, 50] nm
the element length spans 6.25–12.5 nm around the `EXP4G_L0_NM = 10 nm` reference discretisation. That is a
*discretisation-resolution* variation of one continuum beam, not a material change — EA and EI stay fixed, which
is the intended reading.

### 3.7 Emergence geometry is already per-motor

`g4E[m]` and `g4Node[m][j]` are per-motor arrays, and `frame[9..11]` carries `g4E` per motor. So the
supported-span/emergence geometry can follow `L_i` without new storage. **The consistency requirement is real
and easy to get wrong:** varying `L_i` must simultaneously update `l0_i`, `ks_i`, `kb_i`, `g4E[m]` and
`g4Node[m][*]`. Changing L while retaining 40 nm stiffness or emergence geometry is the obvious failure mode and
a validation fixture must exclude it.

### 3.8 Globals that need explicit handling under heterogeneity

| quantity | current | under heterogeneity |
|---|---|---|
| `queryR = G4_QUERYR + L + 0.01` | global, from the single L | **must use max L_i** or the neighbour search becomes non-conservative for the longest motors |
| `g4floorZ = min_m(E_m·ê_up) − 0.05` | global (one substrate plane) | stays global; it is a min over emergence points and moves with the **shortest** span. Physically correct — one substrate — but it is a coupling between the distribution and every motor's floor term, and must be reported |
| `G.g4l0 / g4ks / g4kb` scalars | the source of `paramArr` | become the *lawn mean* or are retired; anything still reading them (§3.5) is a bug surface |

### 3.9 Feasibility verdict

**Per-motor free S2 length is implementable as DATA ONLY:** populate `params[11..13]` and `g4E`/`g4Node` per
motor at initialisation. **No kernel edit. No GPU buffer-size change. No TaskGraph topology change. No new
per-step device work.** The Study-A stopping rule *"per-motor length cannot be implemented without an invasive
kernel redesign"* is **not** triggered.

Two conditions carry forward into implementation: **hold M fixed** (§3.6) and **guard the legacy scalar path**
(§3.5).

## 4. Meaning of mechanically free S2 length

Recorded so the campaign cannot drift into over-claiming: `L` is the *effective mechanically unsupported span*
in this model — the distance from the clamped emergence point `g4E` (where the substrate-supported portion ends,
with a clamped tangent `g4Tan = b̂`) to the motor pivot `P = node[M]` at the converter/S2 junction. It sets the
compliance of the path between substrate and converter. It is **not** measured, **not** a literal contour
length, and **not** independently calibrated. Varying it at fixed EA/EI varies *how much of one continuum beam
is free to bend and stretch*, which is the fixture question being asked.

## 5. Homogeneous per-motor implementation (COMPLETE)

The audit's verdict held exactly: **the entire mechanical change is three lines in `packExMat`** writing
`params[11..13]` per motor when a lawn is set. No kernel edit, no buffer-size change, no TaskGraph change.

`ExplicitCompleteMatHarness.applyS2Lawn(G)` assigns a quenched per-motor `L_i` from a discrete distribution
(class lengths + weights) and rebuilds every dependent quantity **together**: `l0_i = L_i/M` with **M held
global** (audit §3.6), `ks_i = EA/l0_i`, `kb_i = EI/l0_i`, the per-motor emergence point
`g4E[m] = P_m − (L_i − slack)·b̂`, and the node chain. EA and EI are fixed — one continuum material, different
unsupported spans. Exact class counts (largest-remainder, never multinomial noise) are deterministically
shuffled with a **private counter-based fixture stream** that touches no chemistry or Brownian RNG. Globals per
§3.8: `queryR` uses **max L_i**; `g4floorZ` stays global as one substrate plane.

Flags: `-s2-lawn "35,40,45"`, `-s2-lawn-weights`, `-s2-lawn-seed`, `-s2-fixtures`, `-s2-map`. Default **off**.

### 5.1 Validation gates — 10 / 10 PASS [`-s2-fixtures`]

| gate | result |
|---|---|
| degenerate 100 % @ 40 nm ≡ feature OFF | **byte-identical** (`max\|dparams\|` = 0, `max\|dnodes\|` = 0) |
| per-motor `l0_i`, `ks_i`, `kb_i` continuum formulas | exact, rel err **0.00e+00** |
| emergence geometry follows `L_i` (\|P−E\| = L_i − slack) | rel err 2.92e-15 |
| exact class counts | 240 / 720 / 240 of 1200 for 20/60/20 % |
| deterministic for a fixed fixture seed | identical |
| different fixture seed reassigns | 662 / 1200 changed |
| uncorrelated with anchor x / y / motor id | r = +0.0108 / +0.0099 / +0.0090 |
| **QUENCHED** — no `L_i` changes over 200 stepped steps | drift **0.00e+00** |
| **legacy scalar path refuses the lawn** (audit §3.5) | throws `IllegalStateException` |
| **per-motor homogeneous @30 nm ≡ global length sweep @30 nm** | **byte-identical** |

The last two close the audit's named hazards by test rather than by assumption: the §3.5 path would have
silently used the global scalars, and the homogeneous-per-motor identity is what makes the map's zero-width
40 nm reference trustworthy.

## 6. Homogeneous response map [`sA_map_n8_gpu.txt`]

Canonical gliding scene — **12-segment filament, filament Brownian ON**, density 400 heads/µm², N = 1200,
dt = 2.5e-6 s, 8000 steps, 25 % equilibration, 8 matched seeds, GPU device-resident, monitored, resume-safe
(one atomic record per (L, seed)). Converter mechanism: the §25.7-confirmed **linear ramp**, unmodified.
**48/48 records, 0 invalid, 0 solver failures, no fallback, no crash.**

### 6.1 Core gliding

| L (nm) | v ± SEM (µm/s) | CV | median | IQR width | 95 % CI |
|---|---|---|---|---|---|
| 25 | −2.248 ± 0.281 | 0.354 | −2.070 | 0.886 | [−2.82, −1.78] |
| 30 | −2.502 ± 0.172 | 0.194 | −2.580 | 0.672 | [−2.82, −2.19] |
| 35 | −2.400 ± 0.329 | 0.387 | −2.659 | 0.257 | [−2.82, −1.74] |
| **40 (reference)** | −2.946 ± 0.405 | 0.389 | −3.382 | 1.429 | [−3.66, −2.16] |
| 45 | −2.777 ± 0.264 | 0.269 | −2.686 | 0.725 | [−3.24, −2.27] |
| 50 | −3.091 ± 0.213 | 0.195 | −3.174 | 0.649 | [−3.48, −2.68] |

**Pairwise comparisons against 40 nm resolve nothing** (dV = +0.699/1.29σ, +0.444/0.95σ, +0.546/1.82σ,
+0.169/0.31σ, −0.145/0.42σ at 25/30/35/45/50 nm). Reporting only those would have concluded "no effect".

**The matched-seed TREND test does resolve it**, and is the correct statistic here because every length shares
the same seed set:

*This table is the **single-ε (+ε only)** arm, retained as the record of what was measured first. Its dv/dL is
**superseded by the ε-EVEN value in §6.3** (−0.02171 ± 0.00754, 2.88σ), which is the correct and smaller estimate
and the one carried into §1 and §8.*

| per-seed slope vs L (single-ε arm) | value | σ | seeds same sign |
|---|---|---|---|
| **dv/dL** *(superseded — see §6.3)* | **−0.03194 ± 0.00866 (µm/s)/nm** | **3.69** | **88 %** |
| d(avgBound)/dL | +0.01074 ± 0.00733 /nm | 1.46 | 88 % |
| d(strokes/s)/dL | +13.10 ± 9.50 (1/s)/nm | 1.38 | 88 % |
| d(postLife)/dL | +0.668 ± 0.523 steps/nm | 1.28 | 50 % |

Widest paired contrast, 50 − 25 nm: **−0.844 ± 0.333 µm/s (2.54σ), 6/8 seeds**.

**Longer free S2 ⇒ faster gliding**, ~37 % over 25→50 nm. Engagement and stroke flux trend the same way
(88 % seed agreement) but are **not resolved** individually at n = 8.

### 6.2 Variability, engagement and flux

| L (nm) | avgBound | strokes/s | episode rate /s | preLife | postLife | invalid+solver |
|---|---|---|---|---|---|---|
| 25 | 2.532 | 3417 | 3183 | 40.2 | 241.2 | 0.0 |
| 30 | 2.434 | 3125 | 2933 | 42.0 | 247.6 | 0.0 |
| 35 | 2.778 | 3608 | 3433 | 42.4 | 250.9 | 0.0 |
| 40 | 2.778 | 3658 | 3442 | 41.0 | 255.4 | 0.0 |
| 45 | 2.599 | 3442 | 3200 | 39.9 | 251.2 | 0.0 |
| 50 | 2.809 | 3675 | 3458 | 39.9 | 261.5 | 0.0 |

Velocity CV is **0.19–0.39 with no monotone length dependence** — the fluctuation magnitude is not obviously an
L effect, so **H2 is not supported**. Engagement stays within ~15 % across a 2× span of free length.

### 6.3 Secondary twirling — the ε-ODD map (both signs, 96/96 records)

The −ε arm was added at the identical code revision (`git diff` over `softbox/` empty against the commit that
produced the +ε arm), so the signs pair legitimately; the resume logic reused all 48 +ε records.

| L (nm) | τ_odd ± SEM (N·m) | Ω_odd ± SEM (rad/s) | σ | seed sign | J_stroke_odd | J_total_odd |
|---|---|---|---|---|---|---|
| 25 | −2.998e-22 ± 2e-22 | −11.995 ± 5.684 | 2.11 | 88 % | −3.818e-27 | −9.876e-26 |
| 30 | −6.087e-22 ± 1e-22 | −17.188 ± 5.481 | 3.14 | 75 % | −6.880e-27 | −1.758e-25 |
| 35 | −4.940e-22 ± 1e-22 | −17.123 ± 4.432 | 3.86 | 100 % | −9.310e-27 | −1.494e-25 |
| 40 | −1.564e-22 ± 2e-22 | −4.014 ± 6.537 | 0.61 | 75 % | −5.169e-27 | −6.839e-26 |
| 45 | −4.377e-22 ± 2e-22 | −12.728 ± 3.369 | 3.78 | 88 % | −9.423e-27 | −1.215e-25 |
| 50 | −2.382e-22 ± 1e-22 | −2.947 ± 4.273 | 0.69 | 63 % | −1.116e-26 | −5.394e-26 |

**The jaggedness is noise, not structure.** χ² of the six Ω_odd against one common value = **8.14 on 5 dof
(χ²/dof = 1.63)** — consistent with pure seed scatter about **−11.34 rad/s**. The trend is
**dΩ_odd/dL = +0.410 ± 0.210 (1.95σ)**, unresolved; `dτ_odd/dL` 1.09σ; `dJ_stroke_odd/dL` 1.33σ;
`dJ_total_odd/dL` 1.19σ with only 38 % seed agreement (i.e. random). **Sign is NEGATIVE at all six lengths.**

ε-ODD phase budget (N·m·s per episode) — `J_post_late` dominates `J_total` and carries the scatter:

| L | J_pre | J_stroke | J_post_early | J_post_late |
|---|---|---|---|---|
| 25 | +9.884e-27 | −3.818e-27 | −3.154e-26 | −7.328e-26 |
| 30 | −1.673e-26 | −6.880e-27 | −3.459e-26 | −1.176e-25 |
| 35 | −8.795e-27 | −9.310e-27 | −3.562e-26 | −9.565e-26 |
| 40 | +5.719e-27 | −5.169e-27 | −4.344e-26 | −2.550e-26 |
| 45 | −1.198e-26 | −9.423e-27 | −3.156e-26 | −6.853e-26 |
| 50 | −1.060e-26 | −1.116e-26 | −1.943e-26 | −1.275e-26 |

`v_odd` is small and sign-inconsistent (+0.09 … −0.27 µm/s against `v_even` ≈ −2.3…−3.1), confirming the
propulsive channel stays ε-even as §21.4 requires.

**Because no S2-dependent twirl peak survives the χ² test, the mirror control the brief mandates before
crediting such a peak was deliberately not run** — there is no peak to credit. **H5 is refuted.**

*Scene caveat:* `Q_omega` here is 0.08–0.17, not ≈1. That is **expected**, not a defect — the §§22–25 transport
identity was established for the ONE-segment rigid scene, whereas this map uses the canonical 12-segment
Brownian filament where roll is averaged over segments and γ_roll is segment 0's. These Ω_odd values are
therefore **not** comparable to the §25.7 one-segment numbers.

## 7. dt-refinement subset at 30 / 40 / 50 nm — RUN, and **INCONCLUSIVE BY CONSTRUCTION**
[`sA_dthalf_n8_gpu.txt`]

dt/2 = 1.25e-6 s, 16 000 steps (matched 20 ms), same 8 seeds, both ε signs, dt-tagged records (`s2maph_*`) that
cannot collide with the production set. 48/48 complete, 0 invalid, 0 solver, no crash.

| L (nm) | v_even (prod dt) | v_even (dt/2) | Δ | Ω_odd (prod dt) | Ω_odd (dt/2) |
|---|---|---|---|---|---|
| 30 | −2.656 | −2.805 | **−0.149** | −17.188 | −19.903 |
| 40 | −2.915 | −2.754 | **+0.162** | −4.014 | −9.335 |
| 50 | −2.819 | −2.739 | **+0.080** | −2.947 | −3.118 |

| trend on this subset | value | σ | seeds |
|---|---|---|---|
| production dt | −0.00816 ± 0.01035 (µm/s)/nm | **0.79** | 50 % |
| dt/2 | +0.00329 ± 0.01703 (µm/s)/nm | **0.19** | 63 % |

**The gate as specified cannot decide H3 vs H6, and the reason is the subset, not the physics.** Re-fitting the
*production-dt* 6-point map restricted to {30, 40, 50} reproduces −0.00815 — i.e. **the prescribed subset sits on
the flattest part of the response**. It fails to resolve the trend even at production dt (0.79σ), where the full
25–50 nm map resolves it at 2.88σ. A test that cannot see the effect at the reference timestep cannot tell us
whether refinement destroys it. **Reporting "the trend flipped sign at dt/2" as a refutation would be wrong** —
both subset numbers are consistent with zero and with each other.

**What the subset does establish** is a per-length dt systematic of **0.08–0.16 µm/s (3–6 % of v)**. The whole
length effect across 30→50 nm is only ≈0.26 µm/s, so over that window the dt systematic is *comparable to the
signal*. Propagated onto the 6-point slope, an uncorrelated ±0.15 µm/s per-point shift contributes
≈0.008 (µm/s)/nm — the same order as the 0.0075 statistical SEM. **So dt uncertainty roughly doubles the error
budget on dv/dL and cannot be neglected.**

**Ω_odd** shifts with dt at every length (−17.2→−19.9, −4.0→−9.3, −2.9→−3.1) but keeps its **negative sign**,
consistent with §6.3's conclusion that the twirl is noise-dominated rather than length-structured.

### 7.1 The full six-length dt/2 map — **H3 CONFIRMED** [`sA_dthalf6_n8_gpu.txt`]

96/96 dt/2 records (the 30/40/50 subset was reused; only 25/35/45 were newly run). Matched lever arm, matched
seeds, matched 20 ms physical duration.

| L (nm) | v_even (prod dt) | v_even (dt/2) | Δ | Ω_odd (prod) | Ω_odd (dt/2) |
|---|---|---|---|---|---|
| 25 | −2.341 | −2.246 | +0.095 | −11.99 | −9.42 |
| 30 | −2.656 | −2.805 | −0.149 | −17.19 | −19.90 |
| 35 | −2.715 | −2.603 | +0.111 | −17.12 | **+4.22 (SIGN FLIP)** |
| 40 | −2.915 | −2.754 | +0.162 | −4.01 | −9.33 |
| 45 | −3.058 | −2.753 | +0.305 | −12.73 | −16.23 |
| 50 | −2.819 | −2.739 | +0.080 | −2.95 | −3.12 |

| estimator | dv_even/dL (µm/s)/nm | σ | seeds |
|---|---|---|---|
| production dt | **−0.02171 ± 0.00754** | 2.88 | 88 % |
| dt/2 | **−0.01406 ± 0.00815** | 1.73 | 75 % |
| **paired change under refinement** | **+0.00764 ± 0.01412** | **0.54** | 6/8 |
| **pooled over both timesteps** | **−0.01788 ± 0.00545** | **3.28** | — |

**The gate is met.** The sign is **preserved** (negative at both timesteps); the slope **does not significantly
change** under refinement (paired Δ = 0.54σ, i.e. the 65 % magnitude ratio is not a resolved reduction); and
pooling both timesteps resolves the effect at **3.28σ**. **⇒ H3 CONFIRMED; H6 excluded.**

*Stated honestly:* the dt/2 arm **alone** is 1.73σ and does not independently establish the effect. The
confirmation rests on the conjunction of (a) sign preservation, (b) no resolved slope change, and (c) the pooled
3.28σ — not on the dt/2 arm standing by itself.

**Twirl under refinement — a further nail in H5.** Ω_odd at 35 nm **flips sign** (−17.12 → +4.22) while the other
five lengths keep sign. A per-length twirl value that is not even *sign-stable* under timestep refinement at
n = 8 confirms §6.3: the twirl here is noise about a common value, not a length-structured response.

## 8. Homogeneous classification — **H3 (mean-sensitive), CONFIRMED**

**H3** — free length materially shifts mean speed: the ε-EVEN slope is
**dv_even/dL = −0.02171 ± 0.00754 (µm/s)/nm, 2.88σ, ~20 % over 25–50 nm**. (The single-ε arm read 3.69σ / 37 %;
that figure is SUPERSEDED — the ε-even estimator is the correct one and is the smaller of the two.)
**Not H1** (mean is not robust). **Not H2** (CV non-monotone, no clean variance dependence). **H4 unresolved** —
engagement and stroke flux trend with L at 88 % seed agreement but only 1.4σ. **H5 REFUTED** — with both ε
signs run (§6.3), the twirl map is consistent with a single common value (χ²/dof = 1.63 on 5 dof) and the
trend is 1.95σ, unresolved. **H6 EXCLUDED** — the full six-length dt/2 map (§7.1) preserves the
sign, shows no resolved slope change (0.54σ) and pools to 3.28σ. (The earlier three-point subset was
inconclusive by construction, §7.) **Not H7** — the response is smooth and monotone in trend with no
isolated extrema or discontinuities, and numerical health is perfect at every length.

A further caveat on the trend model: v_even reads −2.341, −2.656, −2.715, −2.915, −3.058, −2.819 — rising in
magnitude to 45 nm then falling at 50. The 45→50 drop (0.24 µm/s) is within the per-point SEMs, so the data are
consistent with monotone-then-plateau, but **a linear slope is a summary of a possibly non-monotone response**,
not a validated functional form.

**Study B is now AUTHORISED.** Study A has passed numerical and interpretive review: the homogeneous response is
smooth (no discontinuities), numerically healthy at every length (0 invalid / 0 solver in 192 records), and the
mean-gliding effect survives dt refinement (§7.1).

**Study B was AUTHORISED on this basis and is now COMPLETE** — Study A passed numerical and interpretive
review (smooth response, 0 invalid / 0 solver in 192 records, effect surviving dt refinement), and the sharpest
first comparison — **D4 (25 % @ 30 nm + 75 % @ 40 nm, mean 37.5) versus D5 (homogeneous 37.5 nm)** — was run and
is reported in §§9–19. Because Study A establishes that the mean matters, a resolved difference there would have
been direct evidence of mixed-population mechanical interaction; **none was found (0.03σ)**. Still outstanding
after Study B: the **density subset** (saturation shift), which §15 records as explicitly NOT gated, and a
**24-seed post-hoc null** to power Δ_mix (§18). The ±ε twirl map is DONE (§6.3) and shows no length dependence.

## 9. Study B implementation and campaign

D4 = 25 % @ 30 nm + 75 % @ 40 nm (realised **exactly 300/900**, mean **exactly 37.5000 nm**);
D5 = homogeneous 37.5 nm. Canonical Study-A gliding scene verbatim (12-segment filament, filament Brownian ON,
400 heads/µm², N = 1200, dt = 2.5e-6 s, 8000 steps, 25 % equilibration), confirmed linear converter ramp at
ε = ±15°, **24 matched seeds, both ε signs**, GPU device-resident, monitored, resume-safe (one atomic record per
(arm, ε sign, seed)). **96/96 records; 0 invalid, 0 solver failures, no fallback, no crash.**

Class-stratified telemetry was added: each episode carries its motor's quenched `L_i` (`F_LNM`), and per-step
accumulators split binding events, bound motor-steps, stroke events, axial propulsive force, total |axial force|
and axial torque by class. Class membership is decided by the lawn's own midpoint, so it is exact for any
two-class lawn.

## 10. D4/D5 validation and CPU/GPU equivalence

All twelve gates pass at the unchanged revision. The ten standing Study-A gates (§5.1) plus:

| gate | result |
|---|---|
| D4 class counts exactly 300 / 900 | **PASS** |
| D4 realised mean exactly 37.5000 nm | **PASS** |
| **broad mixed-lawn full-graph CPU/GPU equivalence** | **PASS** — `bindMism = 0`, `convFlagMism = 0`, `max\|dSegTorque\| = 2.18e-24 N·m`, `max\|dFilCoord\| = 5.96e-08 µm`, `firstDiv = none`, bound CPU = GPU = **12**, no fallback (`sB_equiv_gpu.txt`) |

## 11. Mean-matched D4 versus D5 gliding — **CORE RESULT**

| arm | v_even (µm/s) | σ | median | IQR | 95 % CI | n |
|---|---|---|---|---|---|---|
| **D4 heterogeneous** | **−2.766 ± 0.133** | 20.79 | −2.722 | [−3.014, −2.372] | [−3.027, −2.517] | 24 |
| **D5 homogeneous 37.5** | **−2.771 ± 0.148** | 18.72 | −2.777 | [−3.125, −2.374] | [−3.070, −2.485] | 24 |
| post-hoc 0.25·L30 + 0.75·L40 | −2.850 ± 0.219 | 13.01 | −2.892 | [−3.198, −2.627] | [−3.218, −2.414] | **8** |

| comparison | value | σ | 95 % CI | seed sign | n |
|---|---|---|---|---|---|
| **Δ_mean = D4 − D5 [PRIMARY]** | **+0.005 ± 0.152** | **0.03** | [−0.291, +0.293] | 50 % | 24 |
| Δ_mix = D4 − post-hoc | −0.152 ± 0.140 | 1.08 | [−0.406, +0.111] | 75 % | 8 |
| Δ_curve = D5 − post-hoc | −0.117 ± 0.177 | 0.66 | [−0.464, +0.187] | 50 % | 8 |

**Heterogeneity does not matter beyond the mean.** Δ_mean is **0.03σ** with 50 % seed sign — about as close to an
exact null as this assay produces — and its CI excludes any effect larger than ±0.29 µm/s (≈10 % of v).

Variability and flux are likewise unchanged: CV(v) 0.236 (D4) vs 0.262 (D5); avgBound 2.847 vs 2.965;
strokes/s 3642 vs 3725; episode rate 3457 vs 3528 /s.

**Power limitation, stated rather than buried:** the post-hoc null requires seed-matched homogeneous 30 and
40 nm records, and the Study-A map has **8** seeds against this campaign's **24**. Δ_mix and Δ_curve are
therefore strictly paired on the **8 overlapping seeds only** (`sbPosthoc` returns NaN beyond that, so pairing
can never be manufactured). **Δ_mix at 1.08σ is unresolved because it is underpowered, not because interaction
is excluded.** B3 is untested, not refuted.

## 12. Class-specific recruitment and load sharing

Within D4, per-seed class shares against the 25 % / 75 % deposited fractions (E > 1 ⇒ over-represented):

| quantity | 30 nm share | 40 nm share | **E(30)** | **E(40)** |
|---|---|---|---|---|
| deposited | 0.2500 | 0.7500 | 1.000 | 1.000 |
| binding events | 0.2133 | 0.7867 | **0.853** | 1.049 |
| bound motor-steps | 0.2166 | 0.7834 | **0.866** | 1.045 |
| stroke events | 0.2163 | 0.7837 | **0.865** | 1.045 |
| **axial propulsive force** | 0.2035 | 0.7965 | **0.814** | 1.062 |
| total \|axial force\| | 0.2186 | 0.7814 | **0.874** | 1.042 |
| **axial torque** | 0.3456 | 0.6544 | **1.383** | 0.872 |
| stroke episodes | 0.2176 | 0.7824 | 0.870 | 1.043 |

**A modest, highly consistent selection effect exists even though the population endpoints do not move.** The
short (30 nm) class is **under-recruited by 13–15 %** (binding, bound time, strokes, episodes all E ≈ 0.85–0.87)
and **under-propulsive by 19 %** (E = 0.814) — the long class dominates recruitment and propulsion in proportion
slightly above its deposited share.

**The exception is chiral torque: the short class carries 38 % MORE axial torque than its deposited share**
(E = 1.383 vs 0.872). So the 30 nm motors bind less and push less, yet contribute disproportionately to the
chiral channel. That asymmetry nevertheless does **not** propagate to a population twirl difference (§14).

*The `J_total` enrichment row is omitted as uninterpretable:* the two classes' per-seed summed `J_total` have
opposite signs, so their "shares" (2.33 / −1.33) leave [0,1] and the ratio is degenerate. Class-resolved
`J_total` needs a signed-magnitude treatment, not a share.

## 13. Mixed lawn versus post-hoc homogeneous prediction

Δ_mix = −0.152 ± 0.140 µm/s (1.08σ, 75 % seed sign, n = 8) and Δ_curve = −0.117 ± 0.177 (0.66σ, n = 8). Both
central values are negative — the mixed lawn and the mean-matched homogeneous lawn each glide slightly *faster*
than the weighted homogeneous prediction — but **neither is resolved**, and at n = 8 neither could be. **No
mixed-population interaction is claimed, and none is excluded.** The post-hoc construction is a weighted average
of independent homogeneous simulations and is **not** a simulated mixed lawn.

## 14. Secondary twirling consequences

| arm | Ω_odd (rad/s) | σ | τ_odd (N·m) | σ |
|---|---|---|---|---|
| D4 | −9.786 ± 3.478 | 2.81 | −3.244e-22 ± 9.19e-23 | 3.53 |
| D5 | −8.358 ± 3.120 | 2.68 | −3.658e-22 ± 1.10e-22 | 3.33 |
| post-hoc (n = 8) | −7.308 ± 4.805 | 1.52 | — | — |

Δ_mean(Ω_odd) = **−1.428 ± 4.805 (0.30σ)**; Δ_mix(Ω_odd) = −6.502 ± 8.718 (0.75σ, n = 8);
Δ_mean(τ_odd) = +4.14e-23 ± 1.42e-22 (0.29σ). Both arms twirl negative at ≈2.7–2.8σ, consistent with the
established converter mechanism. **No resolved change in mean twirling, no broadening of variability.** The
short class's torque over-representation (§12) does not translate into a population effect. **No mirror control
is warranted** — nothing here is resolved or unexpectedly large.

## 15. Study-B decision and density-follow-up gate

**B1 — no heterogeneity effect** on the primary endpoint: Δ_mean(v) = 0.03σ with a tight CI, and no resolved
change in variability, engagement or event flux. **Partial B4** as a secondary observation: a consistent but
modest class asymmetry (short class E ≈ 0.81–0.87 in recruitment and propulsion, E = 1.38 in torque) that does
not reach the population endpoints — it is a *selection signature*, not a selection-*dominated* lawn.
**B3 is untested, not refuted** (§11 power limitation). Not B5, not B6, not B8.

**Density follow-up: NOT gated.** None of the four triggers fired — no resolved Δ_mean, no resolved Δ_mix, no
strong class-specific dominance, no saturation-approach signal.

## 16. Numerical health and provenance

96/96 records, 0 invalid, 0 solver failures, no fallback, no crash. Each record carries git revision, boot id,
recorder session, dt, steps, equilibration, density and the realised lawn. **Defect found and corrected during
this study:** the first Study-B pass shipped a silently-failed source edit — an indentation mismatch meant the
per-step class accumulators were computed but never copied onto the result, so every per-step class field
persisted as 0. It was caught because the enrichment table printed `NaN` rather than a plausible number. D4 was
re-run with the fix; the 48 records were verified **bit-identical to the originals across all 10 physics fields**
(`glide`, `tau`, `omegaFit`, `avgBound`, `strokeRatePerS`, the four `J` phases, `nEp`), confirming the fix was
inert and that §11's gliding result was never affected.

## 17. Biological interpretation and limits

D4/D5 is a **fixture-sensitivity construct**, not a calibrated adsorption distribution. The result — that a
two-class 30/40 nm lawn behaves like its mean — licenses only the modelling statement that *for this assay, at
this density, over this length range*, the fixed-length idealisation at the correct mean is adequate. It says
nothing about real HMM adsorption geometry, and a broader, skewed, or continuous distribution could behave
differently. The twirl numbers remain non-biological (15° is a diagnostic skew; this is not a motility assay;
turns/µm is not a pitch prediction).

## 18. Exact next recommendation

**Raise the post-hoc null to 24 seeds by extending the homogeneous 30 and 40 nm arms from 8 to 24 matched
seeds** (32 records ≈ 15 min). That is the cheapest way to convert Δ_mix from "unresolved because underpowered"
into an actual test of mixed-population interaction, and it reuses the existing D4/D5 campaign unchanged. Only
if Δ_mix then resolves does B3 become live and the density panel worth gating.

Do **not** widen the distribution, add classes, or run the density panel first: Δ_mean is a tight null, so the
informative next move is powering the one comparison that is currently blind, not adding new arms.

## 19. Experiments deliberately not run

D1/D2/D3 lawns; D5-vs-D4 at other densities; the density panel; a mirror control (§14 — unwarranted); 48-seed
extension (adaptive rule not met: Δ_mean central effect is ~0, not 1.5–3σ); dt/2 for Study B; skewed or
continuous distributions; any tuning of fractions or lengths.
