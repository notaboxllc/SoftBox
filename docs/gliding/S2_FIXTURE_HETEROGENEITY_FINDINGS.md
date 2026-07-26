# Mechanically free S2 length as an assay fixture — homogeneous and heterogeneous lawns

**Scope:** whether replacing the current homogeneous 40 nm free-S2 lawn with a plausible heterogeneous lawn
changes the core **gliding** predictions, their variability, or their density dependence. Twirling is an
auxiliary phenotype read from the same runs and never used to select a distribution.

---

## 1. Executive conclusion

**STATUS: SECTION 3 (SOURCE AUDIT) COMPLETE. No implementation, no campaign, no result.**

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

## 5–19. NOT YET IMPLEMENTED OR RUN

Homogeneous per-motor implementation (5); homogeneous response map over 25–50 nm on the canonical gliding assay
(6); the dt/2 subset at 30 / 40 / 50 nm (7); the H1–H7 classification (8); per-motor quenched assignment with a
dedicated deterministic stream (9); the D0–D5 distribution fixtures and their 15 validation gates (10);
mean-matched lawns (11); stratified recruitment/load/torque enrichment (12); mixed lawn versus post-hoc weighted
average of homogeneous arms (13); density dependence (14); secondary twirling consequences (15); CPU/GPU
equivalence on a broad mixed lawn (16); biological interpretation and limits (17); next recommendation (18);
experiments deliberately not run (19).

**No result in this document should be cited — there are none yet.** The next step is §5: implement the
per-motor assignment with M fixed and the §3.5 guard, then run the Study-A validation fixtures before any
campaign.
