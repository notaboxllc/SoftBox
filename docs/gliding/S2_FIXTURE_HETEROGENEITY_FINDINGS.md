# Mechanically free S2 length as an assay fixture — homogeneous and heterogeneous lawns

**Scope:** whether replacing the current homogeneous 40 nm free-S2 lawn with a plausible heterogeneous lawn
changes the core **gliding** predictions, their variability, or their density dependence. Twirling is an
auxiliary phenotype read from the same runs and never used to select a distribution.

---

## 1. Executive conclusion

**STATUS: AUDIT + STUDY-A IMPLEMENTATION + HOMOGENEOUS MAP COMPLETE. Study B not started.**

**CORE GLIDING RESULT — mean gliding speed IS sensitive to mechanically free S2 length.** The per-seed trend
across 25–50 nm is **dv/dL = −0.0319 ± 0.0087 (µm/s)/nm, 3.69σ, 88 % of seeds** — i.e. |v| *rises* with free
length, from −2.25 µm/s at 25 nm to −3.09 µm/s at 50 nm, a **37 % change across the plausible range**. The
widest paired contrast (50 − 25 nm) is −0.844 ± 0.333 µm/s (2.54σ, 6/8 seeds). **Provisional classification H3
(mean-sensitive)** — provisional because the dt/2 subset is not yet run and the effect's likely carrier (the
post-stroke tail) is exactly the dt-sensitive channel of §23.13a. Velocity *variability* shows no clean length
dependence (CV 0.19–0.39, non-monotone), so this is **not** H2. Density dependence is **unmeasured**.

The audit was the gating deliverable and it **passes decisively on feasibility**: per-motor free S2 length is a
**data-only** change. `params` is already a per-motor planar buffer and both runners already read it per motor,
so heterogeneity needs **no kernel edit, no buffer-size change and no TaskGraph topology change**. It also
surfaced **one real hazard** — a legacy scalar-assuming code path that would silently ignore heterogeneity —
which must be guarded before any campaign.

Nothing about gliding, variance, density saturation or twirling is claimed here. Sections 6–19 are scaffolded
and explicitly unrun.

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

| per-seed slope vs L | value | σ | seeds same sign |
|---|---|---|---|
| **dv/dL** | **−0.03194 ± 0.00866 (µm/s)/nm** | **3.69** | **88 %** |
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

### 6.3 Secondary twirling — reported, but NOT an ε-ODD measurement

The map runs a **single ε sign**, so its `tau`, `OmegaFit`, `J_stroke` and `J_total` columns are raw
(ε-EVEN + ε-ODD) values, **not** the ε-ODD chiral quantities of §§23–25, and cannot be read as a twirl
amplitude. They are recorded for completeness only. A true ε-ODD twirl-vs-L map requires ±ε at every length —
double the campaign — and has **not** been run. Additionally `Q_omega` here is 0.08–0.17 rather than ≈1: that is
**expected**, not a defect — the §§22–25 transport identity `Ω = ⟨τ⟩/γ_roll` was established for the ONE-segment
rigid scene, whereas this map uses the 12-segment filament where roll is averaged over segments and γ_roll is
segment 0's. **No twirling conclusion is drawn from this map.**

## 7. dt-refinement subset — NOT RUN

**Required before H3 is believed.** §23.13a established that the long post-stroke tail is the dt-sensitive
channel, and `postLife` trends with L here (+0.67 steps/nm), so the gliding trend's likely carrier is exactly
the quantity that moved under refinement before. The 30 / 40 / 50 nm dt/2 subset at matched physical duration is
the immediate next step.

## 8. Homogeneous classification — **H3 (mean-sensitive), PROVISIONAL**

**H3** — free length materially shifts mean speed: dv/dL resolved at 3.69σ, ~37 % over 25–50 nm.
**Not H1** (mean is not robust). **Not H2** (CV non-monotone, no clean variance dependence). **H4 unresolved** —
engagement and stroke flux trend with L at 88 % seed agreement but only 1.4σ. **H5 not assessable** — the twirl
columns are single-sign (§6.3). **H6 not excluded** — this is why the classification is provisional; the dt/2
subset (§7) must confirm the ranking survives. **Not H7** — the response is smooth and monotone in trend with no
isolated extrema or discontinuities, and numerical health is perfect at every length.

**Study B is NOT authorised yet.** The brief's gate is "do not introduce heterogeneous lawns until Study A passes
numerical and interpretive review", and §7 is outstanding.

## 9–19. NOT YET IMPLEMENTED OR RUN

The D0–D5 distribution campaign (9–10, the mechanism is built and gated but no heterogeneous lawn has been
RUN);
mean-matched lawns (11); stratified recruitment/load/torque enrichment (12); mixed lawn versus post-hoc weighted
average of homogeneous arms (13); density dependence (14); secondary twirling consequences (15); CPU/GPU
equivalence on a broad mixed lawn (16); biological interpretation and limits (17); next recommendation (18);
experiments deliberately not run (19).

**Exact next step:** the §7 dt/2 subset at 30 / 40 / 50 nm. If the dv/dL ranking survives, H3 is confirmed and
Study B opens; if it does not, the classification becomes **H6 (timestep-confounded)** and the homogeneous
result must be restated as a numerical sensitivity rather than a fixture result. Also outstanding before any
heterogeneity claim: the density subset (saturation shift), a true ±ε twirl-vs-L map, and mixed-lawn CPU/GPU
equivalence.
