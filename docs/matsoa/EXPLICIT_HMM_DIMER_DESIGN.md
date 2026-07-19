# Explicit HMM-like myosin dimer — design + archaeology + S2 audit (`explicit-hmm-dimer-s2-l40`)

Two-headed HMM-like myosin dimer built from the **flagship explicit S2 motor** (`explicit-s2-l40`,
`EXPLICIT_S2_L40`). New, **default-off** architecture; the single-head explicit model is preserved
byte-identical (new files only — `TwoBodyConverterMotor.java`/`MotorModel.java` are NOT edited).

## §1 Repository archaeology (what exists; what to reuse; what to avoid)

There are **two disjoint motor worlds** in SoftBox and they do not meet:

- **World A — the articulated 3-body motor** (`MotorStore` rod→lever→head + J1/J2 joints). *Every*
  existing dimer/minifilament class is built on it: `DimerStore`, `DimerCouplingSystem`,
  `MyosinDimerHarness`, `DimerGlideHarness`, `MiniFilament{Store,System,Harness}`, `MiniGlideHarness`
  — faithful ports of v1 `MyosinDimer`/`MyoMiniFilament`. v1 models a dimer as **two independent rods
  coupled by springs + a 160° lever-align torque** (`MyosinDimer.enforceParallel`,
  `applyRodCoupling*`). **There is NO physical shared tail anywhere in v1 or SoftBox** — "dimer" has
  always been emergent from inter-rod coupling.
- **World B — the explicit `TwoBodyConverterMotor`** (single head, one converter DOF θ, semi-implicit
  beam). It **deliberately diverges** from the articulated pathway (`TwoBodyConverterMotor.java:19-20`)
  and has **zero** notion of a dimer, two heads, a fork, or a shared coiled coil (`grep dimer|HMM|
  twoHead|coiledCoil` = 0).

**Verdict (from the archaeology agent):** a new explicit-HMM dimer **cannot reuse any existing dimer
class** — all assume the obsolete `3m/3m+1/3m+2` articulated sub-body layout + the v1 `moveC`
damping-limited PAIRS springs the explicit motor discarded. Coupling two explicit converter heads to a
**common shared S2** is genuinely new code with no prior art. The clean composition is: **two explicit
converter heads (each its own θ, pivot P) whose two pivots tie to a common junction via a shared S2
beam forked from the 4G beam** — a Y-topology where the 4G beam's single distal pivot becomes a fork
feeding two short proximal branches.

Reusable (model-agnostic): the disjoint-pairing idea (`DimerStore.java:18-20`, race-free self-writes);
the single-ended CSR gather (`MiniFilamentSystem.java:137-172`) *for a later minifilament-of-dimers*;
the `myosins` viewer channel (two entries per dimer + beam `segments`); the harness *validation
template* (isometric bed → force arithmetic → rest hold → relaxation → CPU determinism → all-OFF≡single).

## §2 S2 beam parameter provenance audit (what the beam represents)

Traced through `MotorModel.ExplicitS2Params.frozenL40()` (`MotorModel.java:307-338`) and the live
constants `EXP4G_*` (`TwoBodyConverterMotor.java:6263-6270`, cross-checked bit-for-bit by
`assertFrozenParamsConsistent`):

| quantity | value | meaning |
|---|---|---|
| `EA` (axial stretch modulus) | **4.2e-9 N** | = K_ax(60 nm)·60 nm = 70 pN/nm · 60 nm |
| `EI` (bending rigidity) | **7.2e-28 N·m²** | = k_lat(60 nm)·(60 nm)³/3; Lp = EI/kT ≈ 175 nm |
| per-segment `ks = EA/l0` | 0.42 N/m (420 pN/nm) | l0 = 10 nm fixed |
| per-joint `kb = EI/l0` | 7.2e-20 N·m/rad² | |
| torsion | **none** | planar beam, twist not modelled |
| node drag | `6πη·aeta·r`, r = 5 nm | per-node Stokes only |

**Answer to the key question — the parameters represent the native PAIRED S2 coiled-coil treated as
one effective whole-S2 elastic beam element, NOT a single α-helical strand.** Verbatim evidence:
`TwoBodyConverterMotor.java:1230` "discretized extensible-elastica beam representing the free proximal
**S2 coiled coil**"; `:6263` "axial stretch stiffness of the L_ref=60 nm **free S2**"; MD source
Adamovic–Mijailović–Karplus 2008, whose subject is the myosin-II **S2 subdomain** = the two-chain
coiled coil, so 70 pN/nm axial / 0.01 pN/nm lateral are properties of the coiled-coil **dimer as a
unit**. A single beam per motor (`nodes/motor = M+1 = 5`), no ×2 factor anywhere; no "strand" /
"single-helix" / "two-chain" framing in code or docs.

**⇒ Implementation assumption confirmed: the dimer's shared paired-S2 region REUSES EA/EI as-is and
must NOT be doubled.** (Doubling would be correct only for a single-strand parameterisation, which the
evidence contradicts.) Caveat carried over: the beam is frozen at L = 40 nm; do not naively 1/L-rescale
(bending compliance softens the effective axial reaction ~2× below ks/M at longer L).

## §3 Target dimer topology (`ExplicitHmmDimer`)

```
 head A (converter φ_A,ψ_A; F8 spring → actin site A)
     |  proximal branch A  (Ma segments, S2 material)
     \
      fork node F ── shared paired-S2 beam (Ms segments) ── clamped emergence E (anchor, node 0)
     /
     |  proximal branch B  (Mb segments)
 head B (converter φ_B,ψ_B; F8 spring → actin site B)
```

Default sizing: `l0 = 10 nm`; **shared Ms = 3 (30 nm)** + **branch Ma = Mb = 1 (10 nm)** ⇒ each head's
tail path E→P is 40 nm (matches single-head L40), with **most of S2 shared** as required. Branch
material **starts equal to the shared S2 material** (EA/EI reused) per the task; a separate
`branchEA/branchEI` field makes retuning trivial after characterisation.

### Generalized coordinates (one vector q, n = 3·NF + 4)
Nodes (world µm): `nd[0]` = emergence E (clamped, fixed + tangent `g4Tan`, NOT a DOF); free nodes
`nd[1..NF]` with `NF = Ms+Ma+Mb`. `nd[Ms]` = fork F; `nd[Ms+Ma]` = pivot P_A; `nd[Ms+Ma+Mb]` = pivot
P_B. Free node j → node-DOF base `3(j−1)`. Angles: `iPhiA=3NF, iPsiA=3NF+1, iPhiB=3NF+2, iPsiB=3NF+3`.

### Energy assembly (reuse the single-head element math on a graph, not a chain)
- **Stretch** (analytic, per segment): shared `(0,1)…(Ms−1,Ms)`, branch A `(Ms,Ms+1)…`, branch B
  `(Ms, Ms+Ma+1)…`. `f = ks(len−l0)` along the bond — identical to `s2NodeForces` per segment.
- **Bending** `½kb θ²`, rest 0 (FD of energy over node coords, the permanent oracle path): clamped
  emergence (g4Tan vs 0→1); shared interior; **two fork hinges** at F (shared-last→branchA-first, and
  shared-last→branchB-first); branch interiors. A rest-0 fork means the branches resist splaying with
  finite `kb` — a defensible first cut (the initial pose supplies the splay; the two-head binding holds
  it). A rest fork half-angle is a trivial future knob.
- **Floor / node drag / Brownian**: per free node (shared global up axis `eup`), exactly as single-head.
- **Two F8/converter blocks**: the single-head `{pivot, φ, ψ}` Gauss–Newton + converter + angle-drag
  block (`s2Solve` lines 6416-6434) applied **twice** — once at (P_A, φ_A, ψ_A) with F8h_A and once at
  (P_B, φ_B, ψ_B) with F8h_B. The pivot nodes are ordinary free beam nodes that ALSO receive the F8
  block (exactly as node M does in the single head).

**All head↔head coupling flows through the shared beam node DOF** — this is the physical content: a
load on head A transmits down branch A to the fork, through the shared coiled coil to the anchor, AND
up branch B to head B (the tug between heads through the shared S2). No inter-rod springs, no atomics.

### Beam tangent
FD central-difference of the general node-force residual over the free-node DOF — the **FD oracle path**
(`ExplicitSolver.FD`, `s2Solve` lines 6406-6410) generalised to the graph. Provably consistent with the
residual (nested-FD, the permanent oracle); cheap at n≈19 for CPU characterisation. (An analytic forked
Hessian is a later optimisation, not needed for correctness.)

### F8h input (imposed filament geometry, mirroring the single-head slice)
Each head's cross-bridge is a spring to a **fixed actin site defined as its settled pre-stroke xF8**:
`F8h = couple·kF8·(xActin − xF8)`, `couple`→0 on detach (recoil). The converter swing (θ_s: pre→ADP)
stretches the spring ⇒ genuine force generation. This reuses the exact single-head device-slice
construction (`ExplicitSingleHeadHarness.runTrajCPU`), so the head physics is byte-faithful and only
the shared-tail topology is new.

### Parameters (frozen, provenance-tagged)
Shared + branch: `EA = EXP4G_EA_SI = 4.2e-9 N`, `EI = EXP4G_EI_SI = 7.2e-28 N·m²`, `l0 = 10 nm`,
`rNode = 5 nm`, `dt ≤ 2.5e-6 s` — all inherited from `explicit-s2-l40` unchanged. Distinct dimer
Brownian salts (`0x484D…`) avoid collision with the single-head (`0x4711/41/42`) and mat
(`0x4811/41/42`) salts.

## Characterisation gates (before any retuning)
1. **Isometric rest hold** — relaxed converters, both heads bound; the forked beam holds shape
   (bounded, non-growing joint gaps; near-zero head force at rest).
2. **Stroke / force generation** — ramp both θ_s pre→ADP; per-head working stroke + peak force in the
   single-head ballpark (~−7.7 nm, ~7.9 pN), read off the shared-tail dimer.
3. **Single-head equivalence sanity** — compare a dimer head to the isolated single-head slice.
4. **Shared-tail coupling** — stroke ONLY head A; show head B's pivot/force responds through the shared
   beam (the mechanical tug), vanishing in a stiff-decoupling control.
5. **Force balance / determinism** — node-force + reaction sum ≈ 0 at rest; CPU bit-reproducible.

Report: `docs/matsoa/EXPLICIT_HMM_DIMER_FINDINGS.md` (written after the run).
