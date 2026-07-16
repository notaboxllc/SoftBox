# Frozen host semantics — two-body gliding hot path (the device-SoA contract)

The device-resident motor-mat SoA implementation MUST reproduce this contract **bit-for-decision** (every
branch/gate/state-transition identical; float arithmetic within the CPU≡GPU standard). All line references
are `softbox/TwoBodyConverterMotor.java` on `motor-mat-gpu-soa` (off main `5b0fb59`) unless noted.

## 0. Two step drivers, one shared skeleton

- **Calibrated:** `stepGlideSup(Glide2D G,int t,int seed,Tol tol)` — L5835–5863.
- **Explicit:** `stepGlideS2(Glide2D G,int t,int seed,Tol tol)` — L6777–6803.

They are **byte-for-byte identical for stages 1–8, 10, 11**; they differ ONLY at **stage 9 (Step-7
solve)**: calibrated calls `supSolveM` (L5791), explicit calls `s2SolveM` (L6736). So model dispatch is a
single stage-9 swap — it does NOT thread a per-motor branch through the other stages (see SOA_SCHEMA §
dispatch). `stepGlide2D` (L4549) is the fixed-anchor control (Step-7 = the 2×2 stepC-analog); same skeleton.

**Actual per-step execution order** (both drivers), which the device chain must preserve:

```
1  cull                unionActive(G)                                    L5837 / L6779
2  (bind block, fused over active∧bindable∧ADP·Pi motors, host for(m)):  L5838–5843 / L6780–6785
     thetaS[m]=PRESTROKE_THETAS ; geom2D(G,m) ; s=nearestSeg2D(G,m) ; gm=gate2D(G,m,s) ; 8-gate AND → bind
3  chemistry            NucleotideCycleSystem.cycleLymnTaylor(...)        L5845 / L6787
4  cocking update       thetaS[m]=thetaS4a(nuc[m])  (all m)               L5846 / L6788
5  place                geom2D(G,m); placeHead2D(G,m)  (active m)         L5847 / L6789
6  F8/converter force    CrossBridgeSystem.bondForces(...) → G.bondData   L5848 / L6790
7  CSR force gather      zeroAccumulators; csrHistogram; csrScan;         L5849–5853 / L6791–6795
                         csrScatter; segGather → f.forceSum/torqueSum
8  chain + z-confine     chainForces (if !rigid); z-confine host for(s)   L5854–5855 / L6796–6797
9  filament Langevin     brownianForce; integrate; orthogonalizeY;        L5857–5860 / L6799–6802
                         derive
10 Step-7 solve (active) supSolveM | s2SolveM  (m)                        L5861 / L6803
11 (measurement is OUTSIDE the step, in measureSupMat/measureS2Mat)       L5867 / L6804
```

Mapping to the task's 12 logical stages: (1 cull)=stage-1; (2 filament/segment geometry)=read of
`FilamentStore` produced by stage-9 `derive` of the PREVIOUS step (no separate compute); (3 nearest-site)=
`nearestSeg2D`; (4 gate)=`gate2D`; (5 candidate placement)=`geom2D`; (6 stochastic binding)=the 8-gate AND
(**deterministic** — see §Ambiguities); (7 nucleotide)=`cycleLymnTaylor`; (8 F8/converter)=`bondForces`;
(9 Step-7)=`supSolveM`/`s2SolveM`; (10 CSR gather)=`csr*`/`segGather`; (11 Langevin)=integrate block;
(12 measurement)=`measureSupMat`/`measureS2Mat`. Note code order fuses (3)(4)(5)(6) into the stage-2 bind
block and runs Step-7 (9) LAST, after the filament Langevin (11) — preserve that.

---

## Stage 1 — cull (`unionActive`, L4721; grid `initMatGrid` L4703; `siteSegDist2` L4715)

- **Semantics:** `active[m] = (boundSeg[m] ≥ 0)  OR  (site_m within queryR of ANY live segment)`. Bound
  motors are unconditionally active. The union query is grid-accelerated: for each segment `s`, stamp its
  AABB±queryR into the mat grid and set `active[m]=true` for any un-active motor `m` in a touched cell whose
  `siteSegDist2(G,m,s) ≤ queryR²`.
- **Site:** `siteX[m],siteY[m]` = the motor's IDEAL head position (fixed per motor, set at build L4493), NOT
  the live anchor. 2D (mat-plane) distance: foot clamped to `[-half,half]`, `d² = |site−(c+foot·u)|²_xy`.
- **queryR composition (cull margin):** `G4_QUERYR = 0.030 µm` (L4423) = F8 swing ~5 nm + head ~4.5 + gate
  ~6.5 + margin ~5, **doubled for safety** (L4748). Grid cell `gcell = max(queryR, 0.02)` (L4705); grid
  origin inset by `queryR` (L4706). `cullMode`: 0=legacy whole-chain AABB (tests anchor), **1=per-segment
  UNION (the mat default, `initMatGrid`)**, 2=brute (validation ref).
- **RNG:** none (deterministic). **Failure:** none (pure set membership).
- **State written:** `active[N]` (bool). Grid `cellStart[nc+1]`, `cellMotor[N]` are rebuilt when the site
  layout changes (static in a run ⇒ built ONCE at build; the union is recomputed each step against the moved
  filament).

## Stage 2/3/4/5/6 — bind block (fused host loop, L5838–5843)

Guarded by `active[m] && !noBind[m] && boundSeg[m]==FREE_BINDABLE(−1) && nuc[m]==NUC_ADPPI`. Per eligible m:
1. `thetaS[m] = PRESTROKE_THETAS` (−30°, L2164).
2. `geom2D(G,m)` (L4504) — recompute `C_[m], xF8_[m], xH_[m]` from `A[m], phi[m], psi[m]` (the frame is
   lab-fixed: `bhat=+x, eup=+z, econv=+y`; `C=A+L_B·û_B`, `û_B=eup·cosφ+bhat·sinφ`; `xF8=C+R(ψ)(r_F8−r_conv)`;
   `xH=C−R(ψ)·r_conv`; `R(ψ)` = `rotConv` about `econv`).
3. `s = nearestSeg2D(G,m)` (L4520) — nearest chain segment to `xF8_[m]` by min perpendicular distance with
   foot interior (`|foot| ≤ half+0.02`); returns −1 if none. **Data-dependent loop over nSeg** (flag).
4. `gm = gate2D(G,m,s)` (L4531) → `{surf,bindArc,psiErr,phiErr,thetaErr,preload,eKt,headSide,foot·1e3,conDist·1e3}`.
5. **8-gate acceptance (deterministic AND):**
   `g0 surf<dBindNm(3.0) ; g1 psiErr<psiDeg(25) ; g2 phiErr<phiDeg(25) ; g3 thetaErr<thetaDeg(20) ;
   g4 preload<preloadPn(2.0) ; g5 eKt<energyKt(15.0) ; g6 headSide<A_SEMI[2]·1e3 (steric, 4.5 nm) ;
   g7 bindArc∈(margin, 2·half−margin), margin=0.05`. Tolerances = `new Tol()` defaults (L2167).
6. On all-8-true: `boundSeg[m]=s ; bindArc[m]=gm[1]`.
- **RNG:** none — **binding is DETERMINISTIC geometric acceptance** (no P_bind roll). **Failure:** `s<0` ⇒
  `continue` (no bind). **State written:** `boundSeg[m]`, `bindArc[m]`, and `C_/xF8_/xH_[m]` (via geom2D).

## Stage 7 — nucleotide chemistry (`NucleotideCycleSystem.cycleLymnTaylor`)

- **Called once over ALL N motors** (no cull) after `mot.setCounts(t,seed,nSeg)`. States NONE/ATP/ADP·Pi/ADP
  (`0/1/2/3`); the Lymn–Taylor cycle + load-gated ADP→NONE release + refractory cooldown.
- **RNG (canonical, per Part 3.6):** `wangHash( (m*1000003) ^ (step*999983) ^ (seed*7919) ^ SALT )`, with:
  - **NUC (cycle) salt `0x4E55`** — NucleotideCycleSystem L46,107.
  - **release salt `0x4D54`** — L195,265.
  - **refractory salt `0x52465241`** ("RFRA") — L176,251.
- **State transitions:** advances `nucleotideState[m]`; on release `boundSeg[m] → FREE_COOLDOWN(−2)`,
  decremented per step to `FREE_BINDABLE(−1)` after `refractorySteps` (MotorStore L124,139–140). Writes
  `stats`, `forceDotAvg`, `avgInit`, `cooldown`. **This is the ONLY per-motor stochastic STATE machine;
  binding+cull are deterministic.** Load gate reads `forceDotFil[m]` (set by Step-7 of the PREVIOUS step).

## Stage 8 (cocking) — `thetaS[m] = thetaS4a(nuc[m])` for ALL m (L5846/L6788)

`thetaS4a` (L3301): `NUC_ADPPI → PRESTROKE_THETAS(−30°)` else `ADP_THETAS(+30°, L2472)`. The +60° swing is
the emergent power stroke (rest-angle switch, not a force). Deterministic.

## Stage 9 (place) — `geom2D(G,m); placeHead2D(G,m)` for active m (L5847/L6789)

`placeHead2D` (L4512) writes the head sub-body pose into `MotorStore.body` SoA (`b.coord/uVec/yVec` at
`headIdx(m)`) from `xH_[m]`/`xF8_[m]` so `bondForces` reads it. Deterministic, no RNG.

## Stage 10 — F8 + converter force (`CrossBridgeSystem.bondForces`, L5848/L6790)

Already a device kernel over `MotorStore.body` + `FilamentStore` SoA. Writes `G.bondData` (stride 13 per
motor: [0..2]=head F, [3..5]=head τ, [6..8]=seg F, [9..11]=seg τ, [12]=forceDotFil). Skips `boundSeg<0`.
The head rest angle is state-dependent (`isCocked = nuc≠ADP·Pi`). No RNG.

## Stage 11 — CSR force gather (L5849–5853/L6791–6795)

`zeroAccumulators` → `csrHistogram`(boundSeg) → `csrScan` → `csrScatter` → `segGather` builds the
segment→bound-motors CSR-inverse and sums each bound motor's stored `bondData` reaction into
`f.forceSum/torqueSum` (race-free, no atomics; the scatter visits motors in index order ⇒ bit-identical to
brute). **The serial single-thread CSR scans are the known scaling caveat** (use the chunked-parallel CSR
variant on device if the serial scan dominates). No RNG.

## Stage 12 — chain + z-confine (L5854–5855/L6796–6797)

- `chainForces` (if `!rigid`) — F3 link + F4 bending PAIRS over `end1/2Nbr*` topology (device kernel).
- **z-confine host loop** `for(s): forceSum[2·nSeg+s] −= G4_KZ·coordZ(s)` (`G4_KZ=2.0 pN/nm` coverslip
  normal, L4418). **This is a host for(s) loop — must become a kernel or fold into integrate.** No RNG.

## Stage 13 — filament Langevin (L5857–5860/L6799–6802)

`f.counts[1]=t; f.counts[2]=seed` then `BrownianForceSystem.brownianForce` → `RigidRodLangevinIntegration
System.integrate` → `DerivedGeometrySystem.orthogonalizeY` → `derive`. All device kernels over
`FilamentStore` SoA. Filament Brownian keyed by `(step=t, seed)` in `f.counts` via the FDT amplitude
`sqrt(2kT γ/dt)`. This moves the filament; the motor Step-7 (next stage) uses PRE-integrate motor geometry.

## Stage 14 — Step-7 model solve (active m only; else zero the force diagnostics)

Runs LAST. Uses `F8h = bondData[m][0..2]` (from stage 10, pre-integrate) and `C_[m]/xF8_[m]` (from stage 9
geom2D). Decoupled from the filament integrate within the step.

### 14a. CALIBRATED `supSolveM(G,m,t,seed,bound)` — L5791–5818
5-DOF movable-pivot implicit solve over `{P_b,P_e,P_up,φ,ψ}` (P=`A[m]`). Builds `K=kfSI·JᵀJ` +
converter(`kc`)/bind(`kb`) + the anisotropic sup-tail tangent `supForceM(G,m)` (L5772: softplus axial
slack-to-taut + transverse + floor, returns `{F(3),kAxTan,kTrTan,kFloorTan}`) on the diagonal + `γ/dt·I`
(`aP=supGammaP/dt, aphi=gammaPhi/dt, apsi=gammaPsi/dt`); RHS = F8/sup force + converter/bind restoring +
**Brownian**; `solveLin(M,F,5)`; update `A[m]+=B·dq0·1e6 (+E·dq1 +U·dq2); φ[m]+=dq3; ψ[m]+=dq4; geom2D`.
- **RNG (canonical mat calibrated salts, Part 3.6):**
  `F[0..2] += brownTorque(supGammaP, dt, seed, t, 0x5F1L|0x5F2L|0x5F3L + m·7919L)` ;
  `F[3] += brownTorque(gammaPhi, dt, seed, t, 0x5F4L + m·7919L)` ;
  `F[4] += brownTorque(gammaPsi, dt, seed, t, 0x5F5L + m·7919L)` (L5808–5809).
- On `bound`: `forceDotFil[m]=bondData[m][12]`, `forceMag[m]=|F8h|`; else both 0 (L5816–5817).

### 14b. EXPLICIT `s2SolveM(G,m,t,seed,bound)` — L6736–6761
(3M+2)-DOF linearly-implicit beam Newton step (M=`g4M`); free nodes `1..M`, node M = pivot `A[m]`, node 0
re-pinned to `g4E[m]`. RHS = beam internal forces `s2NodeForcesM(G,nd)` (L6729: analytic stretch + bending
via central-diff of energy + substrate floor); the numeric beam tangent = central FD (`hh=1e-5`) of
`s2NodeForcesM` over each free node coord (nested FD); node drag `aN=g4gammaNode/dt` on the diagonal;
F8/converter/bind Gauss–Newton block on `{pivot, φ, ψ}`; `solveLin(Msys,F,3M+2)`; update nodes/φ/ψ, re-pin
node 0, `A[m]=nd[M]`, `geom2D`.
- **RNG (canonical mat explicit salts, Part 3.6):**
  node draws `F[3(j−1)+k] += brownTorque(g4gammaNode, dt, seed, t, 0x4811L + (m·1009 + j·131 + k)·7919L)`
  for `j=1..M, k=0..2` (L6745) ;
  `F[iPhi] += brownTorque(gammaPhi, dt, seed, t, 0x4841L + m·7919L)` ;
  `F[iPsi] += brownTorque(gammaPsi, dt, seed, t, 0x4842L + m·7919L)` (L6754–6755).
- On `bound`: same force diagnostics as 14a.

### `brownTorque(γ, dt, ep, t, salt)` — L2174
`h = (ep*2654435761)^(t*40503)^(salt*0x9E3779B1); h^=(h>>>13); h*=0x9E3779B1; h^=(h>>>16);`
`u1=((h&0xFFFFFF)+1)/16777217.0; h^=(h<<7); u2=(((h>>>8)&0xFFFFFF)+1)/16777217.0;`
`g=sqrt(−2 ln u1)·cos(2π u2); return sqrt(2·kT·γ/dt)·g;`. `ep=seed`, `t=step`. All-long hash (bit-exact).

---

## RNG salt table (the canonical mat salts — the Part-3.6 authority)

| where | quantity | salt (mat) | key |
|---|---|---|---|
| chemistry | NUC cycle | `0x4E55` | `wangHash((m·1000003)^(step·999983)^(seed·7919)^salt)` |
| chemistry | release | `0x4D54` | same |
| chemistry | refractory | `0x52465241` | same |
| calibrated Step-7 | pivot b/e/up | `0x5F1L/0x5F2L/0x5F3L + m·7919L` | `brownTorque(γ,dt,seed,t,salt)` |
| calibrated Step-7 | φ / ψ | `0x5F4L / 0x5F5L + m·7919L` | same |
| explicit Step-7 | beam node (j,k) | `0x4811L + (m·1009 + j·131 + k)·7919L` | same |
| explicit Step-7 | φ / ψ | `0x4841L / 0x4842L + m·7919L` | same |
| filament | Brownian | `f.counts` (step,seed) FDT | `BrownianForceSystem` |

**Salt-family reconciliation (CRITICAL — Part 3.6 decision).** The SINGLE-motor validation kernels use a
DIFFERENT salt family: calibrated `calibratedStep` uses `0x4F1L..0x4F5L` (fixed, no `m` term, L5524/5613);
explicit `s2Solve` uses `0x4711L + (j·131+k)·7919L`, `0x4741L`, `0x4742L` (L6373/6392). **The device MAT
kernel MUST use the MAT salts above (`0x5F*`+m, `0x4811/0x4841/0x4842`+m), NOT the single-motor `0x4F*` /
`0x47*`.** Reusing the scalarized `calibratedStep` body for `matStep7` requires swapping its salt constants
to the `0x5F*`+m·7919 family (the arithmetic is identical; only the salt literals + the `m·7919` per-motor
offset differ). Same for the explicit beam. Bit-for-decision on chemistry+Brownian depends on this.

## Measurement definitions (stage 12 — host reductions; keep host, cadence-fed)

- **Velocity (canonical assay):** `measureGlide`/`measureGlideModel` (L4239/L7955) — **LS slope of the
  filament COM·b̂ vs time** over the fit window; **negative = pointed-first = correct glide**. (Never
  `longWindowSpeedXY`.) Reported µm/s.
- **Recruitment/load-bearing (`measureSupMat` L5867 / `measureS2Mat` L6804):** per step accumulate over m —
  `nb` = #bound (`boundSeg≥0`); a bind event = `boundSeg≥0 && prevB[m]<0`. **LOAD-BEARING:** calibrated =
  `supForceM(G,m)[3]·1e3 ≥ SUP_LOADBEAR_KTAN(2.0 pN/nm)` (pivot taut/transmitting); explicit = beam taut
  (`contour − endToEnd < 1e-3 µm`); fixed-anchor always. Outputs `{avgBound, contFrac(1−frac steps with
  nb==0), bindsPerMotorPerS, avgLoadBearing, fracLoadBearing, pGe1(frac steps nb≥1), candPerStep(candAcc/
  candSteps), reachAtRest}`. `candAcc/candSteps` accumulated in stage-1 (L5837).
- **Divergence guard:** `if(!Double.isFinite(coordX(0))) break` (blow-up → episode truncation).

## Ambiguities / deliberate device-port decisions (flag for the implementer)

1. **"Stochastic binding" is DETERMINISTIC** in the two-body path (8-gate AND, no P_bind roll). The device
   `matBind` must be deterministic; do NOT introduce a binding RNG. (Un-binding IS stochastic — in chemistry.)
2. **Salt-family reconciliation** (above): mat salts `0x5F*`+m / `0x4811/0x4841/0x4842`+m, not single-motor
   `0x4F*`/`0x47*`. Non-negotiable for bit-for-decision.
3. **`nearestSeg2D` is a data-dependent search** over `nSeg` with an early `continue` (foot-interior test).
   On device: bounded loop over `nSeg` (small, ~12) with a running argmin — no dynamic allocation; reproduce
   the `|foot|≤half+0.02` reject and the `<bd` first-min tie-break exactly.
4. **Cull scatter→gather race:** `unionActive` writes `active[m]=true` from multiple segments (idempotent OR).
   The device equivalent must be a race-free set-OR (per-motor: active iff bound OR min-over-segments
   siteSegDist2 ≤ queryR²) — invert to a per-motor gather over the motor's grid neighborhood, OR an
   idempotent scatter (writing `true` only, order-independent). Preserve `cullMode=1` union semantics + the
   grid-cell membership + the `gcell=max(queryR,0.02)` binning.
5. **Step-7 runs LAST, uses PRE-integrate motor geometry + pre-integrate `bondData` F8** — the device chain
   must keep `C_/xF8_` (stage-9 geom2D) and `bondData` (stage-10) live until stage-14; the filament integrate
   (stage-13) must not overwrite them. Same decoupling I verified for single-motor `calibratedStep`.
6. **z-confine host loop** (stage-12) has no kernel form yet — fold into integrate or a tiny `matZConfine`.
7. **`geom2D` runs up to 3×/step per motor** (bind block, place, and inside Step-7's final line). The device
   port may compute it inside each consuming kernel or cache `C_/xF8_/xH_` in SoA — either is fine as long as
   the value each stage reads matches the host (the bind block uses geom2D with `thetaS=PRESTROKE`; the place
   uses geom2D with the cocking-updated `thetaS` — but geom2D does NOT read thetaS, only φ/ψ/A, so the two
   calls differ only if φ/ψ/A changed between them; they don't within a step until Step-7). Cache is safe.
8. **`forceDotFil[m]` cross-step dependency:** chemistry (stage-7) reads `forceDotFil[m]` set by Step-7 of the
   PREVIOUS step. The SoA array must persist across steps (device-resident) — do not zero it between steps
   except as Step-7 does for inactive motors.
