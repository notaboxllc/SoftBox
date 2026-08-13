# Canonical Actin-Attachment Path Audit — flexible-S2 gliding model

**Read-only audit. No physics changed, no parameter tuned, no run launched, no code modified.**

- **Worktree:** `/home/jba/Code/SoftBox` (the primary SoftBox worktree)
- **Branch:** `gpu-mat-bottlenecks-explicit-singlehead`
- **Commit:** `3119000da43ce2bfa526878102fdb6ec7f334b28` — *"docs(lowatp): D1 — gliding below the Vilfan bracket, and our mechanism is per-head"*
- **Working tree:** clean at audit start
- **Date:** 2026-08-11

**Worktree/branch resolution.** There is no `INVESTIGATION_STATE.md` in this repository; the equivalent
controlling handoff document is **`docs/CURRENT_STATE.md`** (last full revision 2026-07-13, patched since).
The canonical publication gliding path was resolved from `CLAUDE.md` §"GPU production gate is PER-ASSAY-CLASS",
`docs/canonical_freeze/GPU_CANONICAL_PRODUCTION_SIGNOFF.md` §2 (production runners/builders table), and the
most recent campaign commits on this branch — **not** guessed. Twelve sibling worktrees exist
(`softbox-vilfan-*`, `softbox-rigid-filament-*`, `softbox-lowatp-*`); their status is covered in §9. The
current publication-facing gliding/twirling campaigns (viscosity, low-[ATP], density-occupancy) all ran on
**this** branch.

**Supersession.** This report **does not supersede** `docs/helical_binding/ACTIN_HELICAL_BINDING_AUDIT.md`
(2026-07-23) — that document remains authoritative for the *azimuth/roll infrastructure inventory and its git
provenance*. This report is a distinct, later audit with a different purpose: it traces the **currently
executing attachment path of the publication gliding model** after ~3 weeks of subsequent development
(the discrete-site, helical-surface, target-zone and converter-skew drops). **Where the two disagree, this
report is current**, and the specific corrections are listed in §14.

---

## 1. Executive verdict

**There are TWO canonical, simultaneously-live attachment architectures on this branch, and they are not the
same model.** They are not silently merged here.

| | **Path A — frozen production gliding** | **Path B — chiral-site twirling campaign** |
|---|---|---|
| Driver | `ExplicitCompleteMatHarness -production-cell` (`run_singlehead_gpu.sh`) · `ExplicitHmmDimerGlidingHarness -production-cell` (`run_hmm_gpu.sh`) | `ChiralSiteHarness` (`run_chiral_sites.sh -eta-map` / `-atp-map` / `-atp-density-map` / `-twirl` / `-conv-*`) |
| Actin for attachment | **continuous centerline**, isotropic cylinder of radius `FIL_R`=3.5 nm | **discrete helical material-frame sites** (`every3`, rise 8.1 nm) on the actin **surface** at R=3.5 nm |
| Azimuth read during capture | **no** | **no** (the capture gate is Path A's, unchanged) — azimuth enters only **after** acceptance |
| Attachment point | on the centerline | **off-axis**, on the actin surface at a retained material azimuth |
| Retained state | `(boundSeg, bindArc)` | `(boundSeg, bindArc, bindAzim, bindSite)` + head roll frame |
| Occupancy | none (single-head); sister-head-only 5.4 nm axial (dimer) | **exclusive: one head per (filament, site id)** |
| Current results it generated | density-saturation sweeps, L40/L60 sensitivity, rigor-rupture gliding impact, mechanical-interference/force-balance | viscosity campaign + mirror control, low-[ATP] transfer, density-occupancy screen, all twirling/converter-skew work |

**The plain answer to "what does the canonical flexible-S2 gliding motor bind to today?"**

> In the **frozen production gliding path (A)** the motor binds to a **continuous, azimuth-symmetric cylinder
> centered on the filament centerline**. The attachment coordinate is a single scalar arc length along the
> segment axis. There is no monomer, no helix, no azimuth, no site identity and no occupancy.
>
> In the **twirling campaign path (B)** the motor binds to a **discrete helical lattice of material-frame sites
> on the actin surface**, one head per site, with a latched integer site id — **but the decision of whether a
> head may attach at all is still made by Path A's azimuth-blind continuous gate.** The lattice acts as a
> *post-acceptance snap + 12 nm capture veto + occupancy filter*, not as the accessibility model.

**Classification: R4, decomposing into R2 (Path A) and R1 (Path B).** Urgency: **HIGH-VALUE BUT NOT
BLOCKING**, with one **disclosure obligation** that is publication-relevant (§11, §13).

---

## 2. Canonical gliding call path

### 2.1 Path A1 — single-head explicit-S2-L40 (the frozen density-sweep production cell)

```
scripts/run_singlehead_gpu.sh  -production-cell -density D -seed S -steps N
   └─ softbox.ExplicitCompleteMatHarness.main
        └─ runProductionCell                          ExplicitCompleteMatHarness.java:1524
             ├─ TwoBodyConverterMotor.buildS2Mat(density, dt, L=40nm, slack=1.5nm, seed)
             │     └─ buildGlide2D                    TwoBodyConverterMotor.java:4453   (scene: lawn + filament)
             ├─ packExMat(Gd, 1)                      ExplicitCompleteMatHarness.java:~415  (bindP/params/frame/chiP…)
             └─ buildGlidingGraph(ed, prod=true)      ExplicitCompleteMatHarness.java:774   (device-resident TaskGraph)
```

Per-step task chain actually executed (GPU graph `glide`, `buildGlidingGraph` L799–L859; the CPU runner
`stepGlidingCPU` L714–L772 calls the **same static methods in the same order**):

```
beamGeom   TwoBodyBeamAnalyticGpu.matBeamGeom        ← detached-head pose from anchor + φ,ψ + base triad
bind       TwoBodyBeamAnalyticGpu.matBindExplicit    ← THE ATTACHMENT GATE  (L435)
chem       NucleotideCycleSystem.cycleLymnTaylorRigor (RUPTURE_MODE=1 canonical default)
cock       MatSoaSlice.matCock                       ← θ_s rest switch (the stroke)
place      TwoBodyBeamAnalyticGpu.matPlaceHeadExplicit
bond       CrossBridgeSystem.bondForces              ← ON-AXIS F8 cross-bridge (L59)
zeroAcc→csrZero→csrHist→csrReduce→csrScan→csrScatter→segGather   ← force/torque gather
chain → zconf → brown → integ → orthoY → derive      ← filament mechanics (roll channel bwx live)
s2solve    TwoBodyBeamAnalyticGpu.matS2SolveStep     ← implicit explicit-S2 beam + φ,ψ
redBlk → redFin                                      ← reduced observables
```

Detached-head search → candidate → gate → latch, in detail:

| stage | code | what it does |
|---|---|---|
| head pose | `matBeamGeom` | `C`, `xF8`, `xH` from the **motor** anchor, beam node M, and (φ,ψ). **No filament read.** |
| candidate enumeration | `matBindExplicit` L456–470 | linear scan over **all `nSeg` segments**; `foot = (xF8−c)·û`; `footC = clamp(foot,−half,half)`; winner = min distance to the **clamped closest point on the centerline**. One candidate per head. |
| candidate representation | L472–481 | `conDist` = ‖xF8 − closest centerline point‖; `bindArcV = footC + half` ∈ [0, segLength]; `surf = (conDist − FIL_R)·1e3` nm |
| gate | L489–496 | 8-gate AND, all deterministic, no RNG |
| latch | L497 | `boundSeg[m]=s; bindArc[m]=bindArcV` |
| bound force geometry | `CrossBridgeSystem.bondForces` | attach point `ap = segCenter + (bindArc−half)·û` — **on the centerline** |

`active[m]` is forced to 1 for every motor in the production graph (L776) — **no broad-phase cull** in the
canonical gliding cell (`matCull` exists but is not wired here); the banner states this explicitly.

### 2.2 Path A2 — HMM/forked dimer (the matched dimer production cell)

```
scripts/run_hmm_gpu.sh -production-cell …
   └─ softbox.ExplicitHmmDimerGlidingHarness  →  runProductionCell / buildProductionGpu
        device kernel:  ExplicitHmmDimerGpuKernel.dimerBindGate      (L398)
                          └─ gateHead(...)                            (L341)
        CPU oracle:     ExplicitHmmDimerGlidingHarness.stepDimers §1  (L349–L392)
                          └─ TwoBodyConverterMotor.nearestSeg2D + gate2D
```

`gateHead` reproduces the identical `nearestSeg2D` + 8-gate contract in `float`, then applies the **intra-dimer
(sister-head) 5.4 nm axial occupancy veto** on the filament-global material coordinate (`cumLen[s]+bindArc`)
before committing. One work-item per dimer, head A then head B (B sees A's commit).

### 2.3 Path B — chiral discrete-site / twirling campaign

```
scripts/run_chiral_sites.sh  -eta-map | -atp-map | -atp-density-map | -twirl | -conv-campaign …
   └─ softbox.ChiralSiteHarness
        └─ runEtaMap / runAtpMap / runTwirlCampaign / runConvLive …
             └─ runTwirlArm(TArm, seed, steps)           ChiralSiteHarness.java:1849
                  ├─ cfg(a.mode=2, roll=true, …)         ChiralSiteHarness.java:370
                  │     ExplicitCompleteMatHarness.SITE_MODE = 2       (= "every3" lattice)
                  │     ExplicitCompleteMatHarness.HEAD_ROLL = true
                  │     ExplicitCompleteMatHarness.SURFACE_ON = (siteMode>0) = true
                  │     ExplicitCompleteMatHarness.SURF_STERIC = false ; SURF_EXCL_NM = 0
                  │     ExplicitCompleteMatHarness.TZ_ON = false
                  ├─ build(seed) → TwoBodyConverterMotor.buildS2Mat (same scene builder as Path A)
                  └─ ExplicitCompleteMatHarness.buildGlidingGraph(e, prod=true)   ← SAME builder
```

The task chain is Path A's with **five conditionally-inserted tasks**:

```
[convFrame]  ChiralSiteSystem.convFrameStep        (convSkewOn: ±ε converter stroke-plane rotation — MOTOR side)
 beamGeom
 bind        TwoBodyBeamAnalyticGpu.matBindExplicit    ←  UNCHANGED azimuth-blind gate
[siteSnap]   ChiralSiteSystem.siteSnap             (siteOn)   ← lattice snap + 12 nm capture veto + latch site id
[siteOcc]    ChiralSiteSystem.siteOccupancyResolve (siteOn)   ← exclusive one-head-per-site
[surfPrune]  TwoBodyBeamAnalyticGpu.matSurfaceStericPrune (surfOn; exclusion = 0 in this campaign ⇒ inert)
 chem → [strokeSkew] → cock → [convFrame2] → place
 bond        CrossBridgeSystem.bondForcesSurface   (surfOn)   ← OFF-AXIS attachment at Ractin
[headRoll]   ChiralSiteSystem.headRollStep         (chiralOn) ← head roll DOF + registry couple
 …gather / filament mechanics / s2solve identical…
```

**Frozen arm configuration actually used by the viscosity, low-[ATP] and density-occupancy campaigns**
(`TArm(tag, epsB, rand, mirror, filBrown, segs)` → `TArm(tag, 2, true, 0.0, epsB, 0.0, …)`,
`ChiralSiteHarness.java:1644`):

| knob | value |
|---|---|
| `SITE_MODE` | **2 = `every3`** (rise = 3·2.7 = 8.10 nm) |
| `SURFACE_ON` | **true**, `R_ACTIN_NM` = 3.5 nm |
| `HEAD_ROLL` | true, `HEAD_ROLL_BROWN` true |
| `REG_K` (bound registry stiffness) | **0** in the eta/ATP ladders ⇒ registry couple inert |
| `EPS_BIND_DEG` / `EPS_STROKE_DEG` | 0 / 0 (actin-side skews OFF) |
| `CONV_SKEW_DEG` | **±15°** (eta-map, ATP ladder), ±5° in pilots — MOTOR-side |
| `CONV_SKEW_RAMP` | `RAMP_LINEAR` |
| `SITE_EXCLUSIVE` | **true** |
| `SITE_CAPTURE_NM` | 12.0 |
| `SITE_SEARCH_HALF` | 3 sites either side |
| `MIRROR_SIGN` | +1 (−1 in the mirror control) |
| `RAND_BASE_AZ` | **false** |
| `SURF_STERIC` | **false**, `SURF_EXCL_NM` = 0 |
| `TZ_ON` | false |
| filament | 12 segments, filament Brownian **ON** |
| rigor rupture | **OFF** in this lineage (`RUPTURE_MODE` never set here — the canonical default-ON is applied only in `ExplicitCompleteMatHarness.main`) |

---

## 3. Current actin representation

Answers to the Phase-2 questions, **traced, not inferred**.

### 3.1 Path A (frozen production gliding) — CURRENT CANONICAL

1. **Continuous or discrete?** **Continuous centerline.** No monomer, no site index, no lattice.
2. **Discrete spacing/twist/index/frame?** N/A — none exists on this path.
3. **Continuous: how is axial position chosen? Is azimuth represented?**
   `foot = (xF8 − c)·û`, `footC = clamp(foot, −half, +half)`, `bindArc = footC + half`. The perpendicular
   offset vector `(xF8 − closest point)` **is computed** (to form `conDist`) and its **direction is discarded** —
   only its magnitude survives. Azimuth is represented **nowhere** on this path.
4. **Binding point:** the **filament centerline**. `CrossBridgeSystem.bondForces` uses
   `ap = segCenter + (bindArc − half)·û`. The filament *surface* enters only as a scalar offset in the
   distance gate (`surf = conDist − FIL_R`), i.e. actin is an **azimuth-symmetric rod**.
5. **Surface normal / binding-face orientation on the candidate?** **No.** The candidate carries
   `(segment, arc, conDist)` only.
6. **Retained after attachment:** `boundSeg` (int) + `bindArc` (float, µm from segment end1). Nothing else.
7. **When the filament translates/rotates/bends:** the bound point is **re-derived every step** from the live
   `filCoord[s]`, `filUVec[s]` and `bindArc` ⇒ it follows the **segment's rigid-body motion** (translation,
   tumble, and chain bending via the segment's own pose). It does **not** follow segment **roll**, because an
   on-axis point is roll-invariant. Site position follows **segment centerline only**.

### 3.2 Path B (chiral-site twirling) — CURRENT CANONICAL FOR THE TWIRLING/VISCOSITY/LOW-ATP RESULTS

1. **Hybrid — and the hybrid boundary is the load-bearing fact.**
   *Acceptance* is continuous and centerline-based (`matBindExplicit`, unchanged). *Placement* is discrete,
   helical and off-axis (`ChiralSiteSystem.siteSnap`).
2. **Discrete geometry** (`ChiralSiteSystem.siteSnap` L185–240, parameters from `ExplicitCompleteMatHarness`
   L352–357 + `packExMat` L482–483):
   - **axial rise:** `every3` ⇒ 3 × `Constants.actinMonoRadius` = 3 × 2.70 nm = **8.10 nm**
     (`native` = 2.70 nm, `every4` = 10.80 nm, `stair9-45/90` = 9.00 nm)
   - **twist:** analytic native helix, `TWIST_PER_MON_DEG = −166.5°` (actin 13/6, **LEFT-handed**) ⇒
     `twistRate = −1076.29 rad/µm`; azimuthal advance per `every3` site = 3 × (−166.5°) = −499.5° ≡ **−139.5°**
   - **site index:** `k = round(globalArc / rise)` with `globalArc = segCumArc[s] + localArc` — a
     **filament-global integer**, stable across segment boundaries and across steps
   - **material frame:** yes. `uSite = filUVec[s]`; `nSite = cos φ·segY + sin φ·segZ` with
     `segZ = uSite × segY` and `φ = twistRate·(localArc − half)`; site position
     `xSite = c + (localArc−half)·û + R_actin·n̂`. The frame **translates, bends, tumbles and rolls with the
     filament**; nothing is stored per site.
3. N/A (discrete).
4. **Binding point:** the **actin surface**, `R_actin = 3.5 nm` off axis (`= Constants.radius`), at the
   retained material azimuth. This is the twirl **drive**: the segment lever `RS = xSite − c` gains a
   perpendicular component ⇒ `TS = RS × (−F)` acquires a nonzero ‖û component that `segGather` +
   the integrator's `bwx = torqueSum·û/γ_x` channel converts into filament roll.
5. **Surface normal on the candidate:** yes for the **snap** (each enumerated site carries `n̂`), **no** for
   the **gate** (the gate ran earlier and never saw a site).
6. **Retained after attachment:** `boundSeg` (int) · `bindArc` (float, the **site's** axial coordinate) ·
   `bindAzim` (float, rad — the site azimuth in the segment material frame, plus `mirror·εbind`) ·
   `bindSite` (int, **persistent filament-global site id**) · `headRef`/`headOmega` (the head's own
   parallel-transported roll frame). **Established bonds are never re-snapped** — the site id is held for the
   whole attachment.
7. **When the filament moves:** `CrossBridgeSystem.bondForcesSurface` (L232–276) reconstructs
   `xSite = c + (bindArc−half)·û + R_actin·(cos ψ·segY + sin ψ·segZ)` from the **live** `filCoord/filUVec/filYVec`
   each step. So the bound site follows **segment centerline + segment material frame + segment roll + chain
   bending** (bending at segment granularity — the site rides its owning rigid segment). It does **not** follow
   a per-monomer coordinate, and `bindArc` is not re-mapped across a segment boundary (segment topology and
   lengths are static in the gliding assay, so this is exact there).

---

## 4. Current motor capture gates

Identical on **both** paths (Path B does not modify the gate). `matBindExplicit` L489–496; parameters from
`packExMat` L443–445 (`bindP`).

| gate | expression | production threshold | frame |
|---|---|---|---|
| eligibility | `active ∧ ¬noBind ∧ boundSeg==−1 ∧ nuc==ADP·Pi(2)` | — | — |
| **g0** distance | `surf = (conDist − FIL_R)·1e3 < dBind` | `FIL_R = 3.5 nm`, `dBind = 3.0 nm` ⇒ **conDist < 6.5 nm** | filament, isotropic |
| **g1** binding-face ψ | `|ψ − ψ_actin|·deg < 25°` | 25° | motor-internal; `ψ_actin` is a **per-motor config constant = 0** |
| **g2** neck-lever φ | `|φ − PHI_PRE|·deg < 25°` | `PHI_PRE = +30°`, 25° | motor-internal |
| **g3** converter θ | `|(ψ−φ) − θ_s|·deg < 20°` | 20° | motor-internal |
| **g4** preload | `k_F8·conDist·1e12 < 2.0 pN` | 2.0 pN | filament distance × motor stiffness |
| **g5** energy | `(½κ_conv Δθ² + ½κ_bind Δψ²)/kT < 15` | 15 kT | motor-internal |
| **g6** head side | `(xH − c)·ê_up·1e3 < A_SEMI[2]·1e3` | **2.25 nm** | **lab-fixed ê_up = +z** |
| **g7** in-segment arc | `bindArc ∈ (ε, segLength−ε)` | ε = 1e-6 µm (machine) | filament |

Binding is a **deterministic geometric AND** — there is no `P_bind` roll, no RNG in the gate, and therefore no
stochastic accessibility model. Recruitment is controlled entirely by how often the Brownian motor pose enters
the acceptance region.

**g6 is the only approach-direction rule anywhere in the model**, and it is a **lab-frame half-space test**
(the head center may not sit more than 2.25 nm above the segment center along the global +z), not an
actin-frame accessibility rule. See §6.

---

## 5. Azimuth / helical accessibility

### 5.1 The decisive statements

- **Path A: azimuth does not exist.** `matBindExplicit` / `matBindGateOnly` / `dimerBindGate` do not take
  `filYVec` as a parameter at all. Changing the filament material roll while holding everything else fixed
  changes **nothing**: not the candidate segment, not `conDist`, not `bindArc`, not any gate, not the accepted
  attachment, and not the bound force geometry (an on-axis point is roll-invariant). This is settled by
  signature inspection — **no fixture is required or informative.**
- **Path B: azimuth exists and is read, but only AFTER acceptance.** `siteSnap` reads `filYVec` and rolls the
  lattice with the filament. Rolling the filament therefore changes **which site is nearest**, hence
  `bindSite`, `bindArc`, `bindAzim` — and can change *whether the bond survives at all* (via the 12 nm capture
  veto) and *whether it is blocked* (via site occupancy). It does **not** change whether the head passes the
  8-gate capture test.
- **Consequence to state carefully:** Path B has **azimuth-dependent site selection and azimuth-dependent
  post-hoc survival**, but **not azimuth-dependent capture**. It is *not* a stereospecific accessibility model
  in the Vilfan sense; the Vilfan-style angular hazard exists separately and is default-OFF (§9).

### 5.2 Azimuth read/write table

| quantity | available? | read during **search** (candidate enumeration) | read during **gate** | read **after attachment** | production value / flag |
|---|---|---|---|---|---|
| filament material roll (`filYVec`) | yes (always integrated, roll-preserving `derive`) | **A: no · B: yes** (`siteSnap`) | **no** (both) | **A: no · B: yes** (`bondForcesSurface`) | always live |
| site azimuth (`bindAzim`) | **A: no · B: yes** | B: derived at snap | **no** | **B: yes** (bond geometry, `headRoll` registry) | B: latched at bind |
| site surface normal `n̂` | **A: no · B: yes** | B: yes (per enumerated site) | **no** | B: implicitly via `bindAzim` | B only |
| local actin tangent `û` | yes | **yes** (both) | yes (g0, g7 via `foot`) | yes | always |
| motor-head orientation (φ, ψ) | yes | no | **yes** (g1/g2/g3/g5) | yes | 25/25/20°, 15 kT |
| binding-face alignment (`ψ_actin`) | yes | no | **yes** (g1, g5) | yes | **per-motor constant 0** — carries no actin information |
| converter orientation (θ = ψ−φ) | yes | no | **yes** (g3, g5) | yes | 20°, θ_s from `cockP` |
| explicit-S2 geometry | yes | yes (sets `xF8`/`xH` via `matBeamGeom`) | indirectly (all gates read the pose) | yes (`matS2SolveStep`) | L=40 nm, EA/EI from AMK-2008 MD |
| head roll DOF (`headRef`,`headOmega`) | **A: no · B: yes** | no | no | **B: yes** (registry couple; `REG_K = 0` in the eta/ATP ladders ⇒ couple inert, coordinate still integrated) | B only |
| motor base azimuth | yes | fixed | fixed | fixed | **`RAND_BASE_AZ = false`** ⇒ *every* single-head motor shares the identical lab triad b̂=+x, ê_conv=+y, ê_up=+z |

**The last row is a substantive idealization** and is easy to miss: in the canonical single-head lawn there is
**no orientational disorder among motors** — all stroke planes are coplanar with the lab x–z plane and the
filament starts along +x. The HMM-dimer mat mode *does* assign a per-dimer random azimuth
(`sc.dimerBhat[m/2]`, `ExplicitHmmDimerGlidingHarness.java:668`); the single-head lawn does not unless
`-randomize-motor-base-azimuth` is passed (used only as a control arm in the chiral campaign).

---

## 6. Steric / occupancy rules

| class | implemented? | active in Path A? | active in Path B? | flag / default | code |
|---|---|---|---|---|---|
| **A. site occupancy** (two heads on the exact same site) | **yes, Path B only** | n/a (no sites) | **YES — exclusive, one head per (filament, site id)** | `SITE_EXCLUSIVE = true` when `SITE_MODE > 0` | `ChiralSiteSystem.siteOccupancyResolve` L249 |
| **B. footprint / minimum axial separation** | yes, three variants | **single-head: NO** (`OCC_EXCL_NM = 0`) · **dimer: sister-head only, 5.4 nm, ON in every mode** | superseded by A (`SURF_EXCL_NM = 0`) | `-occupancy-exclusion-nm` (default 0) · dimer `EXCL` (default on) · `-occupancy-global` (default off) | `matBindGateOnly`+`matOccupancyResolve` L522/L600 · `gateHead` L386–392 · `stepDimers` L359–367 |
| **C. sister-head exclusion** (two heads of one HMM) | **yes** | **dimer: YES**, 5.4 nm signed axial on the filament-global material coordinate | n/a (single-head) | on by default in the dimer lineage | `ExplicitHmmDimerGpuKernel.gateHead` L386–392 |
| **D. global motor–motor exclusion** (heads of *different* motors) | **yes, but default OFF** | **NO** | **partially — via site exclusivity (A), which is global across motors** | `-occupancy-global` (dimer) / `-occupancy-exclusion-nm` (single-head) | `matOccupancyResolve`, `stepDimers` L359 |
| **E. true 3-D excluded volume** (head/S2/converter bodies colliding) | **NO — does not exist anywhere in the repository** | no | no | — | grep for `excludedVolume`/`selfCollision`/`motorMotor` returns nothing |
| **F. surface occlusion** (far-side actin unreachable from a planar lawn) | **NO** — only the g6 lab-frame half-space proxy | g6 only | g6 only | `A_SEMI[2] = 2.25 nm`, hard-wired | `matBindExplicit` L495 |

**Do not conflate B with steric realism.** Class E does not exist. Class F does not exist as an occlusion or
line-of-sight rule. The 3-D surface-point steric rule that *does* exist
(`matSurfaceStericPrune`, 5.5 nm Euclidean between reconstructed surface points) is **switched off in the
production twirling campaign** (`cfg()` sets `SURF_STERIC = false; SURF_EXCL_NM = 0`), making that kernel an
inert pass-through there — site exclusivity replaces it by design, and that substitution is documented in
`cfg()`'s own comment.

---

## 7. Planar-lawn geometry

Traced from `TwoBodyConverterMotor.buildGlide2D` (L4453) and `buildS2Mat`.

- **Lawn plane:** motor bodies are assembled at `z = MANCHOR_Z = −0.050 µm` (`LaserTrapHarness:1014`,
  the canonical `fixedMyosinZValue`); anchors `A[m]` are then placed so the **ideal head site** sits at
  `(a_x, a_y, 0)` with `a_x, a_y` uniform over the lawn rectangle. Lawn = `G4_MX × G4_MY` µm
  (mutable; 4.0 × 1.0 µm in the standard mat), density `N = round(ρ·area)`.
- **Substrate normal:** `ê_up = +z`, shared by every motor (`eupP`). A one-sided **floor penalty**
  (`g4floorZ`, `g4kfloor`) keeps explicit-S2 beam nodes above the coverslip
  (`TwoBodyBeamAnalyticGpu` L211, L339).
- **Filament:** centerline initialized on the **z = 0 plane** along +x; `matZConfine` applies a z-only
  restoring force `F_z = −k_z·z` with `k_z = G4_KZ = 2.0 pN/nm` — the coverslip constraint. x, y, in-plane
  rotation and bending are free; filament Brownian is ON.
- **Allowed head search volume:** unbounded in principle — the head pose is (φ, ψ) about the anchored base
  plus the explicit-S2 beam's node displacements. There is **no reach cap** in the production cell
  (`active[m] ≡ 1`, no cull). `queryR = G4_QUERYR = 30 nm` exists as a broad-phase radius for the *culled*
  variants and for `applyS2Lawn`'s conservatism, but is **not applied** in `-production-cell`.
- **Can a motor "reach through" the filament?** Geometrically yes — nothing forbids the head from occupying
  the filament's volume. The only restraint is **g6**: the head center may not exceed `+2.25 nm` above the
  segment center along +z. Since the actin radius is 3.5 nm, g6 keeps the head center below the top of the
  filament, so a head cannot bind while sitting on the far (upper) side.
- **Can far-side actin sites be selected?**
  - **Path A:** the question is vacuous — the attachment is on the axis; there is no near/far side.
  - **Path B:** **yes, in principle.** `siteSnap` enumerates the `±3` sites either side of the head's
    perpendicular foot and picks the **3-D nearest** within the 12 nm capture radius, with **no near-side
    preference and no occlusion test**. Near and far sites at the same arc are 2·R = **7.0 nm** apart; the
    head's F8 point is within 6.5 nm of the axis (g0), so a far-side site is typically 7–10 nm away — inside
    the 12 nm capture radius. When no near-side site falls in the ±3-site axial window (spacing 8.10 nm ⇒
    window ±24.3 nm), a **far-side site can win**. This is an accessibility **incompleteness**, not a bug and
    not currently quantified.
- **Search distance metric:** Euclidean 3-D throughout (`conDist` to the clamped closest point; `bestD2` to
  the site surface point). Not radial-in-the-actin-frame, not projected.
- **Does binding orientation penalize impossible approach vectors?** Only through g6 (lab half-space) and
  indirectly through g1/g2/g3/g5 (motor-internal pose, which the anchored base geometry correlates with
  approach direction). There is **no actin-frame approach-vector penalty**.

**Static examples at fixed geometry** (evaluated from the code, no run):

| configuration | Path A verdict | Path B verdict |
|---|---|---|
| site facing the lawn (near side, −z) | vacuous (on-axis attach); head binds iff the 8 gates pass | snap selects it (3-D nearest); bond placed at −z surface, correct |
| site side-facing (±y) | vacuous; gates unaffected by azimuth | snap selects it if nearest; bond placed laterally — allowed, no penalty |
| site facing away (far side, +z) | vacuous; the head itself is kept below `z_axis + 2.25 nm` by g6 | **eligible** — no occlusion rule; wins whenever it is the 3-D nearest site within 12 nm |

---

## 8. Single-molecule vs gliding comparison

The single-molecule / tweezers attachment path is
`TwoBodyConverterMotor.gateMetrics` (L2206) + `gatePasses` (L2228), driven by `run3e`/`run3f`/the tweezers
producers and by `LaserTrapHarness` via the `MotorModel` registry (`-motor fixed-anchor |
explicit-s2-l40 | calibrated-s2-l40`).

**Finding: the single-molecule and gliding capture rules are the SAME rules, term for term.**
`matBindExplicit` is a device port of `gateMetrics`+`gatePasses`; `MatSoaSlice.matGeomGate` and
`ExplicitHmmDimerGpuKernel.gateHead` are further ports of the same contract; `bindP` is filled from the same
`Tol` defaults and the same `TwoBodyConverterMotor` constants (`PHI_PRE_3E`, `A_SEMI[2]`, `bindMargin()`,
`Constants.radius`, `Constants.kT`).

|  | **SINGLE-MOLECULE (3E/3F, tweezers)** | **CANONICAL GLIDING — Path A** | **TWIRLING CAMPAIGN — Path B** |
|---|---|---|---|
| head–site distance gate | `surf < 3.0 nm` (to centerline−`FIL_R`) | **identical** | **identical** |
| binding-face orientation (ψ) | `< 25°` vs per-motor `ψ_actin` | **identical** | **identical** |
| neck/lever angle (φ) | `< 25°` vs `PHI_PRE_3E = +30°` | **identical** | **identical** |
| converter angle/error (θ) | `< 20°` | **identical** | **identical** |
| preload gate | `< 2.0 pN` | **identical** | **identical** |
| energy gate | `< 15 kT` | **identical** | **identical** |
| head-side steric (g6) | `(xH−c)·ê_up < 2.25 nm` | **identical** | **identical** |
| in-segment margin (g7) | machine-ε half-open ownership | **identical** | **identical** |
| discrete site | **no** | **no** | **YES** (`every3`, 8.10 nm) |
| helical azimuth | **no** | **no** | **YES**, post-acceptance |
| off-axis site | **no** (centerline) | **no** (centerline) | **YES**, R = 3.5 nm |
| live Brownian pose latch (no teleport) | **yes** — the pose that passed is the pose that is kept | **yes** | **yes**, plus a lattice snap of the *actin-side* coordinate |
| retained site identity | `(seg, arc)` | `(seg, arc)` | `(seg, arc, azimuth, global site id)` |
| steric occupancy | n/a (one motor) | none (single-head) / sister-only 5.4 nm (dimer) | exclusive per site |
| material-frame tracking | segment centerline | segment centerline | segment centerline **+ material frame + roll** |
| candidate enumeration | one segment | nearest of `nSeg` segments, clamped-closest-point | same, then ±3 lattice sites within the segment |

**Verdict for Phase 4:** the motor validated in the blind tweezers challenge **is** attaching under the same
geometric rules in the gliding assay. The difference between the assays is **not** the capture gate; it is
what happens to the actin-side coordinate *after* capture (Path B) and the presence of neighbours (occupancy).
This is a materially better position than an R4-by-capture-divergence and should be stated that way.

---

## 9. CPU vs GPU comparison

| lineage | CPU runner | GPU device | relationship |
|---|---|---|---|
| **Single-head explicit-S2 (Path A + Path B)** | `ExplicitCompleteMatHarness.stepGlidingCPU` L714 | `buildGlidingGraph` L774 | **SHARED CODE — the same `static` methods, referenced by the same names, in the same order.** `bind` is `TwoBodyBeamAnalyticGpu::matBindExplicit` on both. Candidate enumeration, search radius, ordering, early exit, tie-breaking (`d2 < bd` strict, first-min on ties), gate arithmetic (`double`), and the retained `(boundSeg, bindArc)` are identical by construction. The only difference is float32/PTX op-ordering in the downstream mechanics. |
| **Path B extras** | same static methods (`ChiralSiteSystem.siteSnap` / `siteOccupancyResolve` / `headRollStep`, `CrossBridgeSystem.bondForcesSurface`) | same, as tasks | **shared code.** Recorded validation: `run_chiral_sites.sh -equiv` and `-twirl-equiv` — *"every discrete decision is EXACT CPU↔GPU: the accepted site IDs, the binding decisions, and the retained state"* (`docs/DISCRETE_ACTIN_SITE_CHIRAL_BINDING_AND_STROKE_FINDINGS.md` §11, §20.5). |
| **HMM/forked dimer (Path A2)** | `ExplicitHmmDimerGlidingHarness.stepDimers` §1 — object path, `double`, sequential over **all N heads in index order**, with the optional D1/D2 rear-bias and `-occupancy-global` branches | `ExplicitHmmDimerGpuKernel.dimerBindGate`/`gateHead` — flat `float` kernel, **one work-item per dimer**, head A then head B, **D0 only** | **TWINS, NOT SHARED CODE.** Equivalent in D0 with `occGlobal` off (the only cross-head coupling is the sister veto, which is per-dimer). They are **not** equivalent if `-occupancy-global` or the D1/D2 rear rules are used — those exist on the CPU path only. Kept in sync by the recorded T3/T4 binding-decision equivalence (device == CPU oracle, 1797 heads) rather than by construction. |
| **Occupancy-exclusion variant (single-head)** | `matBindGateOnly` (shared) | same | `matBindGateOnly` is a **hand-duplicated twin** of `matBindExplicit` carrying a `KEEP IN SYNC` comment. **Verified in this audit: the two are currently in sync**, line for line (L456–497 vs L537–579). This is a maintenance hazard, not a defect. |

**No CPU/GPU disagreement was found.** Two structural notes are recorded above as maintenance risks
(the dimer twins, and the `matBindGateOnly` duplication) — neither is a bug and neither is being changed.

---

## 10. Other helical / azimuthal / surface-binding implementations in the repo

| implementation | location | canonical? | reusable | rigid-only? | flexible-compatible | GPU | chemistry | single-head / HMM |
|---|---|---|---|---|---|---|---|---|
| **Discrete helical sites + head roll DOF + registry + local chiral offsets** | `ChiralSiteSystem` (+ `ExplicitCompleteMatHarness.SITE_MODE`/`chiP`) | **YES for Path B** (default-off in `main`, ON in every `ChiralSiteHarness` campaign arm) | **the reference implementation** | no — works with the 12-segment chain | **yes** | **yes**, tasks in `buildGlidingGraph`, CPU↔GPU decision-exact | yes (runs inside the Lymn–Taylor loop) | single-head only |
| **Continuous helical surface binding** (azimuth select + 3-D surface steric) | `TwoBodyBeamAnalyticGpu.matSurfaceAzim` / `matSurfaceStericPrune`, `ExplicitCompleteMatHarness.SURFACE_ON` | flag-gated; **the off-axis bond half is ON in Path B**, the azimuth-scan half is bypassed when `siteOn()` | yes | no | yes | yes | yes | single-head |
| **Vilfan-style stereospecific target-zone hazard** | `TwoBodyBeamAnalyticGpu.matTargetZone`, `VilfanTargetZoneHarness`, `ExplicitCompleteMatHarness.TZ_ON` | **noncanonical, default-off; explicitly `TZ_ON=false` in every chiral campaign arm** | **yes — this is the only true azimuth-dependent *accessibility* model in the repo** | no | yes | yes | yes | single-head |
| **Continuous local co-occupancy exclusion** | `matBindGateOnly` + `matOccupancyResolve`, `OCC_EXCL_NM` | noncanonical, default-off | yes | no | yes | yes | yes | single-head |
| **Sister-head 5.4 nm axial exclusion** | `ExplicitHmmDimerGpuKernel.gateHead` L386 | **canonical for the dimer class, ON by default** | yes | no | yes | yes | yes | **HMM only** |
| **Lumped-lineage continuous azimuthal gates** (Inc-2 hard cutoff, Inc-3 graded falloff) | `BindingDetectionSystem.bindNearestAzim` / `bindNearestFalloff`, `kinParams[22..26]` | **noncanonical, default-off** (`setKinParams` never writes 22–26 ⇒ 0) — and in a **different lineage** (`GlidingHarness`), not the explicit-S2 path | template only | no | yes | yes | yes | lumped point-motor |
| **Lumped-lineage surface binding** | `BindingDetectionSystem.surfaceBindPropose` / `surfaceStericResolve`, `HelicalSurfaceTwirlHarness` | **prototype, noncanonical** | superseded by the explicit port | no | yes | yes | yes | lumped |
| **Roll spring + roll thermostat** | `RollSpringSystem.rollForces` / `dampRoll` | **noncanonical, default-off**; explicitly *not* used in the explicit twirl arms | yes | no | yes | yes | n/a | any |
| **Explicit-S2 gliding twirl port harness** | `ExplicitTwirlGlidingHarness` | **prototype driver** (drives Path A's pipeline with `SURFACE_ON`) | yes | no | yes | yes | yes | single-head |
| **Rigid single-segment twirling assay** | `ChiralSiteHarness -twirl*` (`FIL_SEGS = 1`, `FIL_BROWN = false`) | **noncanonical diagnostic arm of Path B** | yes | **rigid by configuration, not by code** — the same kernels run on 12 segments | yes | yes | yes | single-head |
| **Rigid-filament skew/ratchet extensions** | worktrees `softbox-rigid-filament-skew-twirling` (+`RigidRollDecomposition.java`), `softbox-rigid-filament-torsional-ratchet` | **off-branch prototypes** — not on `HEAD` | partly | rigid-focused | unknown | unknown | yes | single-head |
| **Vilfan reduced/complete reference ladder** | worktrees `softbox-vilfan-*` (`VilfanCompleteSystem`, `VilfanGradedBindingSystem`, `VilfanTargetZoneDeterministicHarness`, `LawnRecycleSystem`, `AssayConstraintSystem`) | **off-branch prototypes** | **yes — the V0/V1 ladder of the paper brief** | reduced-assay | partly | partly | yes | single-head |

### 10.1 The rigid twirling site model, verified

`ChiralSiteHarness -twirl*` configures `FIL_SEGS = 1`, `FIL_BROWN = false`, `cfg(2, true, …)`. It uses the
**same `ChiralSiteSystem` lattice** as the flexible campaign — there is no separate rigid site implementation
on this branch. Verified values:

- **axial rise** — `every3` = 3 × `Constants.actinMonoRadius` = **8.10 nm** (native 2.70 nm)
- **twist** — `TWIST_PER_MON_DEG = −166.5°` per monomer, **left-handed**, ⇒ `twistRate = −1076.29 rad/µm`;
  φ(site) = `twistRate·(localArc − ½segLen)` for native/every-N modes, or `k·stairPhase` for the idealized
  staircase modes (`stair9-45` = 45°/site, `stair9-90` = 90°/site at 9.00 nm rise)
- **off-axis radius** — `R_actin = R_ACTIN_NM·1e-3` = **3.5 nm** = `Constants.radius`, the physical actin radius
  (explicitly *not* v1's 0.8 nm render offset)
- **material-frame storage** — nothing per-site is stored. The lattice is regenerated analytically from
  `filUVec`/`filYVec` each time it is needed; what is stored per *bond* is `(bindArc, bindAzim, bindSite)`
- **mirror behaviour** — `MIRROR_SIGN = −1` performs a genuine reflection: `chiTwist = −twist` **and**
  `chiStair = −stairPhase` **and** the site tangential sense `tSite = mirror·(uSite × nSite)` flips, and
  `bindAzim = φ + mirror·εbind`. The recorded mirror control reversed the sign of Ω_odd, turns/µm and τ_odd.

### 10.2 What could and could not be reused directly

**Reusable as-is in the flexible-S2 gliding model** (already is, in Path B): the whole `ChiralSiteSystem`
lattice + snap + exclusivity + head roll DOF; `bondForcesSurface`; the material-frame `bindAzim` retention.

**Reusable but not yet in the canonical accessibility path:** `matTargetZone` (the only azimuth-dependent
*capture* hazard); `matSurfaceStericPrune` (3-D surface-point steric, currently disabled in campaign arms);
`matOccupancyResolve` (continuous footprint exclusion, default off).

**Cannot be copied directly:** the lumped-lineage `bindNearestAzim`/`bindNearestFalloff` gates — they operate
on `BindingDetectionSystem`'s point-motor reach predicate, not on the explicit-S2 8-gate contract, and the
recorded Inc-2/Inc-3 result is that an orientation-only throttle **cannot** cap co-occupancy or bend the
velocity–density curve. `RollSpringSystem` is compatible but deliberately unused in the explicit twirl arms
(the explicit cross-bridge is pure-F8, and the arms want no additional torsional coupling).

---

## 11. Static fixture results

**None were written or run, deliberately.** Every Phase-10 question was resolved exactly by code tracing, and
the audit rule forbids redundant code:

| proposed fixture | resolved by | answer |
|---|---|---|
| **A. roll test** | signature inspection: `filYVec` is not a parameter of `matBindExplicit`/`matBindGateOnly`/`gateHead`/`matGeomGate` | **Path A: candidate availability is exactly invariant under filament roll.** Path B: `siteSnap` reads `filYVec` ⇒ site selection, `bindArc`, `bindAzim`, capture survival and occupancy all move with roll; the 8-gate capture decision still does not. |
| **B. far-side test** | `siteSnap` L212–225: pure 3-D nearest within `capture`, no near-side term, no occlusion | **Path A: vacuous** (on-axis). **Path B: far-side sites are eligible.** Near/far separation 7.0 nm; head within 6.5 nm of the axis; capture radius 12 nm ⇒ eligible. Frequency **not quantified** — flagged in §12/§14. |
| **C. occupancy test** | `siteOccupancyResolve` L249, `gateHead` L386, `runProductionCell` banner | Same site: **blocked** (Path B). Adjacent site (8.10 nm): allowed. 5.5 nm away: not representable in Path B (off-lattice); in the dimer path a **sister** head at <5.4 nm is vetoed, a **non-sister** head is not. Path A single-head: **all allowed, no rule**. |
| **D. material-frame test** | `bondForcesSurface` L261–276 reconstructs from live `filCoord/filUVec/filYVec` each step | Bound site follows translation, tumble, chain bending (segment granularity) and, in Path B only, **roll**. |
| **E. single-molecule vs gliding eligibility** | `gateMetrics`/`gatePasses` vs `matBindExplicit` term-by-term (§8) | **Identical decision** for identical geometry. |

If a *quantitative* far-side-selection rate is wanted later, the smallest honest instrument is a counter on
`siteSnap` (`sign(n̂·ê_up)` of the winning site) — telemetry only, no physics.

---

## 12. Publication-impact map

| current result | driver + flags | actin model | discrete helix | surface sites | sterics | azimuthal gate | motor skew |
|---|---|---|---|---|---|---|---|
| **Explicit flexible-S2 single-head gliding saturation** (`docs/canonical_freeze/L60_SINGLE_HEAD_DENSITY_SWEEP_FINDINGS.md`, `docs/matsoa/SINGLE_HEAD_GPU_DENSITY_SWEEP_LONG_FINDINGS.md`) | `ExplicitCompleteMatHarness -production-cell -backend gpu` | **continuous centerline** | no | no | **none** | no | no |
| **Calibrated-surrogate gliding saturation** (`docs/matsoa/EXPLICIT_VS_CALIBRATED_COST.md`, `MotorModel` `calibrated-s2-l40`) | `MatSoaSlice.buildTrajGraph` / `-motor calibrated-s2-l40` | continuous centerline | no | no | none | no | no |
| **HMM/dimer gliding saturation** (`docs/canonical_freeze/L60_HMM_DIMER_DENSITY_SWEEP_FINDINGS.md`, `docs/matsoa/EXPLICIT_HMM_DIMER_GPU_DENSITY_SWEEP_LONG_FINDINGS.md`) | `ExplicitHmmDimerGlidingHarness -production-cell -backend gpu -branchEA 0.03` | continuous centerline | no | no | **sister-head 5.4 nm axial only** | no | no |
| **Mechanical-interference / propulsive-vs-dragging force balance** (`docs/FORCE_BALANCE_CLOSURE.md`, `docs/PHASE2_FORCE_DECOMPOSITION_FINDINGS.md`, `-forcebalance`) | single-head/dimer gliding, Path A | continuous centerline | no | no | none / sister-only | no | no |
| **S2 fixture heterogeneity (Study A/B)** (`docs/gliding/S2_FIXTURE_HETEROGENEITY_FINDINGS.md`) | `-s2-lawn …` on Path A/B scenes | continuous centerline (gate) | — | — | — | no | no |
| **Viscosity campaign + mirror control** (`docs/VISCOSITY_SENSITIVITY_FINDINGS.md` Part II) | `run_chiral_sites.sh -eta-map` / `-eta-mirror` | **discrete every3 helix** | **yes** | **yes, R=3.5 nm** | **site-exclusive** | no | **±15° converter skew, linear ramp** |
| **Low-[ATP] condition transfer** (`docs/twirling/LOW_ATP_GLIDING_TWIRLING_FINDINGS.md`) | `run_chiral_sites.sh -atp-map -atp-uM …` | **discrete every3 helix** | **yes** | **yes** | **site-exclusive** | no | **±15°, linear ramp**; rigor rupture **OFF** |
| **Vilfan occupancy comparison / density-occupancy screen** (`docs/twirling/LOW_ATP_DENSITY_OCCUPANCY.md`) | `run_chiral_sites.sh -atp-density-map` | **discrete every3 helix** | **yes** | **yes** | **site-exclusive** | no | **+ε only** |
| **All twirling trajectories with the flexible motor** (`docs/DISCRETE_ACTIN_SITE_CHIRAL_BINDING_AND_STROKE_FINDINGS.md` §20–§25, `docs/TWIRLING_MECHANISM_ATLAS_FINDINGS.md`) | `run_chiral_sites.sh -twirl* / -conv-*` | **discrete every3 helix** | **yes** | **yes** | **site-exclusive** | no | **yes** |
| **Vilfan target-zone ladder (V0/V1)** | `VilfanTargetZoneHarness` on-branch (Stage A) + the `softbox-vilfan-*` worktrees | continuous centerline + **continuous angular hazard** | no | (surface arms optional) | optional | **YES — the only one** | no |
| **Blind tweezers / force clamp / ADP lifetime / SM4 rigor** | `LaserTrapHarness`, `TwoBodyConverterMotor -exp3*/-exp4*`, `Sm4ForceLifetimeHarness` | continuous centerline, single segment | no | no | n/a | no | no |

**The manuscript-relevant statement this table forces:**

> The density-saturation / force-balance results and the twirling results were produced by the **same motor and
> the same capture gate** but by **different actin-side attachment models** (continuous centerline vs discrete
> off-axis helical lattice with site exclusivity). Any text implying one unified actin representation across
> both result families is inaccurate as written and must either disclose the difference or be supported by a
> matched control.

---

## 13. Recommended unification architecture (proposal only — NOT implemented)

Target: one attachment layer serving blind tweezers, flexible-S2 gliding, HMM/dimer and twirling.

**MUST HAVE** — each is geometry-derived, adds no fitted parameter:

1. **One actin-side attachment representation for every assay:** the discrete material-frame lattice already in
   `ChiralSiteSystem`, promoted from a post-acceptance snap to the *single* actin representation, with
   `SITE_MODE = off` retained only as the explicit legacy/regression mode. *(Realism; no new parameter — rise
   and twist are structural constants, R = `Constants.radius`.)*
2. **Move site selection INSIDE the capture gate.** Today the gate scores a centerline point and the lattice
   corrects it afterwards, which is why azimuth cannot influence recruitment. The minimal change is to run the
   bounded site scan first and evaluate g0/g4 against the **selected site's surface point** instead of the
   clamped centerline point. *(Realism; g0's `dBind` and g4's `preloadPn` keep their current values and
   meaning — but note their *effective* reach changes, so this is a re-baselining change, see §14.)*
3. **A geometry-derived accessibility test replacing the lab-frame g6 proxy:** require
   `n̂_site · (x_F8 − x_site) > 0` — i.e. the head must approach the site from **outside** the filament — plus
   *(**sign corrected 2026-08-11**: this report originally wrote `< 0`. The sign was verified against the built
   scene in `docs/attachment/PATH_B_SITE_ACCESSIBILITY_TELEMETRY.md` §2 — a head on the filament axis gives
   exactly `−R_actin`, so the accessible direction is **positive**.)*
   the existing floor constraint. *(Pure geometry, no parameter.)*
4. **Retained material site identity everywhere**, including the tweezers and dimer paths, so that
   "same site" is a well-defined predicate in all assays. *(Already implemented in Path B.)*
5. **Sister-head compatibility:** express the HMM 5.4 nm sister veto as *site-index* separation on the shared
   lattice rather than a continuous arc difference, so single-head and dimer occupancy use one rule.
   *(Realism; the 5.4 nm literal is retired in favour of "not the same site and not the adjacent site", which
   must be checked to reproduce the current dimer behaviour before adoption.)*

**SHOULD HAVE:**

6. **Global (motor–motor) site exclusivity in the single-head gliding path** — already implemented
   (`siteOccupancyResolve`) and already global; it simply is not active in Path A. *(No new parameter.)*
7. **Orientational disorder in the lawn** (`RAND_BASE_AZ` on by default for many-motor scenes). The dimer mat
   already does this per dimer. *(Scene realism; deterministic seed, no fitted parameter.)*
8. **Far-side occlusion by S2 geometry rather than by rule** — with (3) in place, a far-side site is reachable
   only if the beam can actually route the head there; this becomes an emergent constraint. *(No parameter.)*

**NICE TO HAVE:**

9. Per-monomer nucleotide/site state (needed only if cofilin/tropomyosin coupling is ever wanted).
10. Azimuth-graded capture affinity (the Vilfan `matTargetZone` hazard promoted into the unified gate).
    **This one introduces a fitted parameter (`alphaPsi`) and must stay separately switchable and separately
    justified.**
11. True 3-D excluded volume between motor domains (class E) — expensive, and the pairwise cost is the reason
    it has never been built.

**Explicitly separated: realism vs new tuning.** Items 1–9 are geometry-derived or already-implemented and
introduce **no new fitted quantity**. Items 10 (and any softening of `dBind`/`preloadPn` to compensate for
item 2's reach change) **are** new tuning and must not be bundled with the realism upgrade. Prefer the
geometry-derived constraint (item 3) over any new fitted angular gate.

---

## 14. R0–R5 classification, hazards, and next action

### 14.1 Classification

**Primary: R4 — multiple canonical paths with materially different attachment models**, decomposing as:

- **Path A (frozen production gliding): R2** — stereospecific *motor* capture is present and validated
  (head/converter/S2 gating, blind-tweezers-recovered mechanics), but **actin is effectively continuous,
  centerline and azimuth-insensitive**, with **no meaningful occupancy** in the single-head class.
- **Path B (chiral-site twirling): R1** — discrete off-axis helical material-frame geometry with retained site
  identity and exclusive occupancy **is present**, but **accessibility selection is incomplete**: capture is
  azimuth-blind, there is no far-side/occlusion rule, and the 3-D surface steric is disabled in favour of
  site exclusivity.

The R4 is **mitigated, not spurious**: the two paths share one capture gate and one scene builder, and Path B
is literally Path A plus additive default-off layers. What differs is the actin-side representation and
occupancy — which is exactly what an attachment-realism claim would rest on.

### 14.2 Urgency

**HIGH-VALUE BUT NOT BLOCKING**, with one caveat that *is* publication-relevant.

Applying the stated standard:
- *Does a main scientific claim materially depend on an omitted geometric feature?* The twirling claim depends
  on off-axis chiral geometry — **which is present** in the path that produced it. The density-saturation
  claim is a recruitment/mechanics claim that does not assert helical accessibility. ⇒ **not blocking.**
- *Would the paper imply the same motor is tested across assays when the attachment rules actually differ?*
  The **capture rules do not differ** (§8) — so the tweezers→gliding transfer claim is sound. But the
  **actin-side representation and occupancy do differ between the saturation results and the twirling
  results**. ⇒ **a disclosure obligation, not a re-run obligation**, provided the manuscript states which
  actin representation produced which figure.

### 14.3 Hazards found (documented, NOT fixed — no physics-changing correction made)

None of these is an intended-gate-bypass, a CPU/GPU disagreement, a lost site identity, a wrong frame, a
roll-propagation failure, or a dishonoured steric flag. They are incompletenesses and maintenance risks:

| # | item | code path | effect | magnitude / direction |
|---|---|---|---|---|
| H1 | **Far-side sites are eligible with no occlusion test** | `ChiralSiteSystem.siteSnap` L212–225 | a bond can be placed on the coverslip-facing-away surface | **QUANTIFIED 2026-08-11 — and the direction guessed here was WRONG.** See `docs/attachment/PATH_B_SITE_ACCESSIBILITY_TELEMETRY.md`: far-side bonds are **32.1 % of bound-head time** but carry the **SAME-signed** chiral torque (the skew is expressed in the local site frame), contributing **17.7 %** of τ_odd at 0.55× per-head productivity. Removing them **lowers** τ_odd by ~18 %, so τ_odd is a mild **over**-estimate, not a lower bound. Verdict **A1** (modest for torque, material for occupancy). *The original text below is retained for provenance: "would dilute the coherent chiral torque … plausibly a lower bound" — **refuted**.* |
| H2 | **`SURF_STERIC` disabled in campaign arms** | `ChiralSiteHarness.cfg` L382–383 | the 3-D surface-point steric (5.5 nm) is wired but inert; only same-site collisions are excluded | two heads on *adjacent* sites (8.10 nm) are permitted at ~8.1 nm surface separation — physically tight but not impossible. Affects occupancy-vs-density readings. |
| H3 | **No orientational disorder in the single-head lawn** | `RAND_BASE_AZ = false` (default), `buildGlide2D` L4459 | every motor's stroke plane is coplanar with the lab x–z plane | a real idealization of the assay; the chiral campaign has a randomized-base control arm, so the sensitivity is at least testable. |
| H4 | **HMM dimer CPU and GPU bind logic are hand-maintained twins** | `stepDimers` §1 vs `dimerBindGate` | equivalent only in D0 with `-occupancy-global` off; the CPU path carries D1/D2 rear rules and a global occupancy branch the device kernel does not implement | no current production result uses those CPU-only branches on the device path. |
| H5 | **`matBindGateOnly` duplicates `matBindExplicit` arithmetic** | `TwoBodyBeamAnalyticGpu` L522 vs L435 | drift hazard | **verified in sync at this commit** (line-for-line). |
| H6 | **`ψ_actin` carries no actin information** | `bindP`/`q[3N+m]`, `G.psiActin[m] = 0` | g1 and g5 read a per-motor constant, so the "binding-face orientation" gate is a *motor pose* gate, not an actin-registry gate | this is the natural coupling point if actin helical phase is ever to constrain the bound-head roll. |

### 14.4 Corrections to the 2026-07-23 audit

`docs/helical_binding/ACTIN_HELICAL_BINDING_AUDIT.md` is accurate for its date; three of its headline
statements are **no longer true of the current branch**:

- *"No discrete helical-site model exists"* → **superseded.** `ChiralSiteSystem` implements one, and it is
  active in every current twirling/viscosity/low-[ATP] campaign arm.
- *"Off-axis bond placement (the twirl DRIVE) is NOT built"* → **superseded.**
  `CrossBridgeSystem.bondForcesSurface` builds it and it is active in Path B.
- *"Retained per-attachment site identity NOT built"* → **superseded.** `bindSite` is a latched
  filament-global integer.

Its conclusion that **the canonical binding gate never reads filament azimuth remains exactly true** — and is
now the single most important structural fact in this audit.

### 14.5 Next action

**Recommendation: yes, resolve the attachment architecture before further gliding/twirling mechanistic work —
but as a *disclosure + one bounded upgrade*, not a rebuild.**

1. **Immediately (documentation only):** record in `docs/CURRENT_STATE.md` and in the paper brief which actin
   representation produced which result family (done in this audit's cross-reference).
2. **Cheapest real information (telemetry only, no physics):** add a near/far counter to `siteSnap` to
   quantify H1. Until that number exists, every Path B chiral-torque magnitude should be quoted as a lower
   bound.
3. **The one bounded upgrade worth doing before more twirling mechanism work:** MUST-HAVE items 2 + 3 —
   move site selection inside the gate and replace g6 with the geometry-derived `n̂_site·(x_F8−x_site) > 0`
   *(sign corrected 2026-08-11 — see §13 item 3)*
   accessibility test. This is the change that makes accessibility azimuth-dependent *causally* rather than
   *cosmetically*, and it is the precondition for a fair Vilfan comparison (the paper brief's Stage V1).
   It **re-baselines recruitment** and therefore requires re-running the Path B campaigns — which is why it
   should be decided deliberately, not slipped in.
4. **Do not** promote the discrete lattice into the frozen Path A production cell without an explicit
   re-baselining decision: it would invalidate the density-saturation curves.

**No code was changed and no run was launched by this audit.**

---

## Appendix A — file/line index

| subject | file:line |
|---|---|
| canonical bind gate (device + CPU, shared) | `softbox/TwoBodyBeamAnalyticGpu.java:435` `matBindExplicit` |
| gate-only twin (occupancy path) | `softbox/TwoBodyBeamAnalyticGpu.java:522` `matBindGateOnly` |
| continuous footprint exclusion | `softbox/TwoBodyBeamAnalyticGpu.java:600` `matOccupancyResolve` |
| continuous helical azimuth select | `softbox/TwoBodyBeamAnalyticGpu.java:670` `matSurfaceAzim` |
| 3-D surface steric prune | `softbox/TwoBodyBeamAnalyticGpu.java:714` `matSurfaceStericPrune` |
| Vilfan target-zone hazard | `softbox/TwoBodyBeamAnalyticGpu.java:839` `matTargetZone` |
| discrete-site snap + latch | `softbox/ChiralSiteSystem.java:185` `siteSnap` |
| exclusive site occupancy | `softbox/ChiralSiteSystem.java:249` `siteOccupancyResolve` |
| head roll DOF + registry couple | `softbox/ChiralSiteSystem.java:306` `headRollStep` |
| converter stroke-plane rotation | `softbox/ChiralSiteSystem.java:512` `convFrameStep` |
| off-axis cross-bridge (twirl drive) | `softbox/CrossBridgeSystem.java:232` `bondForcesSurface` |
| on-axis cross-bridge (canonical) | `softbox/CrossBridgeSystem.java:59` `bondForces` |
| alternate double gate (traj/calibrated path) | `softbox/MatSoaSlice.java:314` `matGeomGate`; latch `:85` `matBind` |
| HMM dimer device gate + sister veto | `softbox/ExplicitHmmDimerGpuKernel.java:341` `gateHead`, `:398` `dimerBindGate` |
| HMM dimer CPU gate + occupancy branches | `softbox/ExplicitHmmDimerGlidingHarness.java:349–392` |
| single-molecule gate metrics / passes | `softbox/TwoBodyConverterMotor.java:2206` `gateMetrics`, `:2228` `gatePasses` |
| nearest-segment ownership (host) | `softbox/TwoBodyConverterMotor.java:4537` `nearestSeg2D` |
| lawn / filament scene construction | `softbox/TwoBodyConverterMotor.java:4453` `buildGlide2D` |
| gliding TaskGraph (production) | `softbox/ExplicitCompleteMatHarness.java:774` `buildGlidingGraph` |
| gliding CPU runner (same methods) | `softbox/ExplicitCompleteMatHarness.java:714` `stepGlidingCPU` |
| frozen production cell + config gate | `softbox/ExplicitCompleteMatHarness.java:1524` `runProductionCell` |
| chiral feature switches + lattice constants | `softbox/ExplicitCompleteMatHarness.java:122–133, 348–357, 443–445, 482–483` |
| twirling campaign arm configuration | `softbox/ChiralSiteHarness.java:370` `cfg`, `:1640` `TArm`, `:1849` `runTwirlArm`, `:4034` `runEtaMap`, `:4621` `runAtpMap` |
| lumped-lineage azimuthal gates (default-off) | `softbox/BindingDetectionSystem.java:413, 491` |
