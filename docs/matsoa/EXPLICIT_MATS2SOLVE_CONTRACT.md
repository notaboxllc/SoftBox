# Frozen production coupling contract — explicit gliding Stage-10 (`s2SolveM`)

The CPU production explicit gliding path already exists (mat/batched). This freezes its exact behaviour so the
device port `matS2SolveStep` reproduces it. Source: `softbox/TwoBodyConverterMotor.java`. No equation/param/
salt/chemistry/binding/convergence change is permitted in the port.

## Methods + lines
- **`s2SolveM(Glide2D G,int m,int t,int seed,boolean bound)`** — L6756–6788. The per-motor coupled explicit
  Step-7 over the mat SoA. ONE implicit Newton step per timestep (drag-limited).
- **`buildS2Mat(density,dt,Lnm,slackNm,seed)`** — L6789–6802. Builds the explicit gliding mat: `g4M=M`,
  `g4l0=L/M`, `g4ks=EA/l0`, `g4kb=EI/l0`, `g4gammaNode=6πηR_node`, `g4Tan=bhat` (shared), per-motor `g4E[m]`
  (emergence node) + `g4Node[m][M+1][3]` (initial catenary between `g4E[m]` and the anchor `A[m]=P`).
- **`stepGlideS2(G,t,seed,tol)`** — L6803–6831. The full step: `unionActive` (cull) → per-motor bind gate
  (`geom2D`/`nearestSeg2D`/`gate2D`, host) → chemistry (`cycleLymnTaylor`) → `thetaS[m]=thetaS4a(nuc)` →
  `geom2D`+`placeHead2D` → `bondForces` → CSR (`csrHistogram/Scan/Scatter/segGather`) → `chainForces` →
  z-confine → `brownianForce`+`integrate`+`orthogonalizeY`+`derive` (filament) → per-motor `s2SolveM`.

## Generalized coordinates (per motor, n = 3M+2 DOF)
- free beam nodes 1..M (world µm): DOF `3(j−1)+k`; node M = the pivot P; node 0 re-pinned to `g4E[m]`.
- `phi` (DOF iPhi=3M), `psi` (DOF iPsi=3M+1). `thetaS[m]`, `psiActin[m]` are inputs (not solved).

## Residual RHS `F` (N) — `s2NodeForcesM` + endpoint coupling
- beam internal node force `Fn[j]` (stretch analytic + bending + floor) on rows `3(j−1)+k`.
- `F[pB..pB+2] += F8h` (pB=3(M−1), the pivot block); `F[iPhi] += Qphi + kc(θ−θs) + brownφ`;
  `F[iPsi] += Qpsi − kc(θ−θs) − kb(psi−psiActin) + brownψ`; θ=psi−phi;
  `Qphi=eup·((C−P)×F8h)·1e-6`, `Qpsi=eup·((xF8−C)×F8h)·1e-6`.
- node Brownian: `F[3(j−1)+k] += brownTorque(g4gammaNode,dt,seed,t, 0x4811 + (m·1009 + j·131 + k)·7919)`.

## Jacobian/Hessian `Msys` (N/m) — `ExplicitBeamAnalytic.beamTangentFree` (the SINGLE canonical derivative impl)
- beam energy Hessian K over the 3M free node DOF (stretch + bend + floor), param overload
  `beamTangentFree(M,g4ks,g4kb,g4l0,g4kfloor,g4floorZ,eup,g4Tan,nd)`.
- node drag: `Msys[r][r] += g4gammaNode/dt`.
- F8 Gauss–Newton block `kfSI·JᵀJ` on `{pB,pB+1,pB+2,iPhi,iPsi}` (kfSI=kF8Code·1e6, J from Jphi/Jpsi);
  converter `Msys[iPhi][iPhi]+=kc; [iPhi][iPsi]−=kc; [iPsi][iPhi]−=kc; [iPsi][iPsi]+=kc+kb`;
  angle drag `[iPhi][iPhi]+=gammaPhi/dt; [iPsi][iPsi]+=gammaPsi/dt`.

## F8h / bond input + reaction writeback
- `F8h = bound ? bondData[m·STRIDE + 0..2] : {0,0,0}` (STRIDE=CrossBridgeSystem.STRIDE=13).
- after solve: `nd[j]+=dq·1e6`; `phi+=dq[iPhi]; psi+=dq[iPsi]`; re-pin `nd[0]=g4E[m]`; `A[m]=nd[M]`; `geom2D`.
- reaction: `forceDotFil[m] = bound ? bondData[d+12] : 0`; `forceMag[m] = bound ? |F8h| : 0`.

## MAT RNG salts (CANONICAL — confirmed, distinct from the single-head 0x4711/0x4741/0x4742)
- node: `0x4811L + ((long)m·1009 + j·131 + k)·7919L`
- phi:  `0x4841L + m·7919L`
- psi:  `0x4842L + m·7919L`
- RNG = `brownTorque` (64-bit long wang-hash → Box–Muller Gaussian × sqrt(2kT·γ/dt)). The device copy
  `brownTorqueD` is ALREADY validated to lower on PTX (used in `MatStep7`/`TwoBodyGpuKernels`).

## Update order / convergence / failure
- ONE Newton step per timestep (implicit drag). No inner convergence loop in production gliding (the drag +
  the many timesteps provide the relaxation). `solveLin` = Gauss elimination with partial pivot; a zero pivot
  ⇒ singular flag (no rollback — the step is applied; a failure is recorded, matching `s2Solve`).
- classification (diagnostic): load-bearing / bending-dominated from the stretch-vs-bend energy split (the
  same as the single-head slice); not a solved quantity.

## Shared mat arrays read/written by the coupled Step-10
- READ: `bondData` (F8h + forceDotFil), `boundSeg`, the beam SoA (nodes, frame, params), `phi/psi/thetaS/psiActin`.
- WRITE: beam nodes, `phi`, `psi`, `mot.forceDotFil`, `mot.forceMag` (consumed by chemistry catch-slip next step).
- The FILAMENT update + CSR gather + chemistry + cull are the SHARED stages (identical to the calibrated mat),
  independent of the Step-10 internals; only the head PLACEMENT (`placeHead2D`, from the beam geom) and the
  bind gate (`geom2D/gate2D`) are explicit-specific (needed for the gliding case, not the pre-bound §7 case).

## Port strategy (device `matS2SolveStep`)
= `beamRelaxAnalytic`'s exact assembly (the SAME closed-form residual+Hessian — already validated to lower and
to match `s2Solve` to 9.1e-9 µm in the single-head gate) run for ONE Newton step, PLUS the three
`brownTorqueD` draws at the mat salts + the `forceDotFil`/`forceMag` writeback. It reuses the canonical
`ExplicitBeamAnalytic` math (no second derivative implementation); equivalence to `s2SolveM` is proven by the
one-step CPU/GPU gate (§6). Flags: `-Dtornado.recover.bailout=false -Dtornado.enable.fma=false`.
