# B2 — Frozen equations of the EXPLICIT_S2_L40 beam

Scope: freeze, **exactly and with line references**, the equations the explicit-S2 beam solver evaluates,
so the analytic residual + Jacobian (B3/B4) can be proven to represent the SAME physical model. All quotes
are from `softbox/TwoBodyConverterMotor.java` (the authoritative CPU oracle) with the GPU mirror in
`softbox/TwoBodyGpuKernels.java`. **Nothing here is derived from an informal model — every equation is the
real code.** The finite-difference solver remains authoritative; B3/B4 add new files only.

## 0. Model identity and dimensions

- Canonical model: **EXPLICIT_S2_L40** (`MotorModel.EXPLICIT_S2_L40`, `motorModelId=explicit-s2-l40`,
  `serialize=…;canonVersion=1;refFreeLenNm=40`). Frozen params: `MotorModel.ExplicitS2Params.frozenL40()`
  (`softbox/MotorModel.java:312`), cross-checked bit-for-bit against the live `EXP4G_*` constants by
  `TwoBodyConverterMotor.assertFrozenParamsConsistent`.
- Free contour length **L = 40 nm**, segment length **l0 = 10 nm**, so **M = round(L/l0) = 4 segments**,
  **5 nodes (0..M)**, and the coupled system has **3M+2 = 14 DOF**.
- `dt = 2.5e-6` s. Solver = "(3M+2)-DOF implicit beam solve with a numeric beam tangent (M=4 ⇒ 14 DOF)".
- **Units (load-bearing).** Node coordinates `nd[j]` are **µm** (world). Stiffnesses are **SI**:
  `g4ks` [N/m], `g4kb` [N·m], `g4kfloor` [N/m]. A length in µm → m by ×1e-6; a per-µm derivative → per-m
  by ×1e6. Returned node forces are **N**; the assembled tangent is **N/m**; the solve increment `dq` is in
  **metres** (then `nd += dq·1e6`).

## 1. Frozen constants (`TwoBodyConverterMotor.java:6241–6248`)

```java
static final double EXP4G_KAX_REF_PNNM = 70.0;   // axial stretch stiffness of the L_ref=60 nm free S2
static final double EXP4G_KLAT_REF_PNNM= 0.01;   // lateral endpoint (bending) stiffness at L_ref=60 nm
static final double EXP4G_LREF_NM      = 60.0;   // reference free-S2 length for the MD scaling
static final double EXP4G_L0_NM        = 10.0;   // beam segment (discretization) length — fixed across L
static final double EXP4G_RNODE_NM     = 5.0;    // beam-node Stokes drag radius
static final double EXP4G_EA_SI        = EXP4G_KAX_REF_PNNM*1e-3 * EXP4G_LREF_NM*1e-9;                  // 4.2e-9 N
static final double EXP4G_EI_SI        = EXP4G_KLAT_REF_PNNM*1e-3 * Math.pow(EXP4G_LREF_NM*1e-9,3)/3.0; // 7.2e-28 N·m²
```

Per-segment/per-joint stiffnesses set at build (`buildS2M`, `:6266–6268`):

```java
cm.g4M=M; cm.g4Lc=L; cm.g4slack=slack; cm.g4l0=L/M;                       // l0 µm
cm.g4ks=EXP4G_EA_SI/(cm.g4l0*1e-6); cm.g4kb=EXP4G_EI_SI/(cm.g4l0*1e-6);   // SI ks N/m, kb N·m
cm.g4gammaNode=6*Math.PI*Constants.aeta*(EXP4G_RNODE_NM*1e-9); cm.gammaP=cm.g4gammaNode;
```

Numerically (verified by the harness banner): `g4l0 = 0.01 µm`, `g4ks = 0.42 N/m` (= EA/l0),
`g4kb = 7.2e-20 N·m` (= EI/l0), `g4gammaNode ≈ 9.42e-9 N·s/m`. The floor (`:6271`):

```java
cm.g4floorZ=dot(cm.g4E,cm.eup)-0.05; cm.g4kfloor=20.0*1e-3;   // 50 nm below emergence (µm); SI N/m (=0.02)
```

## 2. Node indexing, clamps, boundary conditions

- **Node 0 = clamped emergence** `E = A − (L−slack)·b̂` (`:6270`), with a **fixed emergence tangent**
  `g4Tan = b̂` (toward the pivot, `:6269`). Node 0 is re-pinned every step and is **not a free DOF**:
  `s2Solve` at `:6396` `cm.g4Node[0]=cm.g4E.clone();` (GPU mirror `TwoBodyGpuKernels.java:414`).
- **Nodes 1..M are the free DOF** (12 node DOF); the solve updates them (`:6394`).
- **Node M = the motor pivot P (= A)**: `cm.A=cm.g4Node[M]; cm.P=cm.A;` (`:6337`, `:6397`).
- **φ, ψ = converter angles** (2 DOF, indices `iPhi=nF`, `iPsi=nF+1`, `nF=3M`, `:6376`).
- L40 build uses **slack = 0** for the golden fixtures (straight rest chord, no seeded bow).

## 3. Segment vectors, lengths, unit tangents

For segment `i` (nodes i, i+1), from `s2NodeForces` (`:6298`):

```java
double[] b=sub(nd[i+1],nd[i]); double len=Math.sqrt(dot(b,b)); if(len<1e-15) continue;
double f=ks*(len*1e-6-l0m); double[] u=scl(b,1.0/len);
```

`b` [µm], `len=|b|` [µm], `u=b/len` (dimensionless unit tangent), degenerate-segment skip `len<1e-15`.

## 4. Stretch energy / force (`s2NodeForces` `:6298–6300`)

Force law (**analytic already** in the frozen code):

```java
double f=ks*(len*1e-6-l0m); double[] u=scl(b,1.0/len);          // l0m = g4l0*1e-6 (m)
for(int k=0;k<3;k++){ F[i][k]+=f*u[k]; F[i+1][k]-=f*u[k]; }
```

This is `F = −∂E/∂x` of the per-segment energy **E_stretch = ½·ks·(len_m − l0_m)²** (ks = EA/l0 [N/m],
`len_m = len·1e-6`). Tension `f = ks·(len_m − l0_m)` [N]; node i gets `+f·u`, node i+1 gets `−f·u`.

## 5. Bending energy (`s2BendEnergy` `:6285–6293`)

```java
static double s2BendEnergy(Cmot cm,double[][] nd){
    int M=cm.g4M; double kb=cm.g4kb, E=0;
    double[] b0=sub(nd[1],nd[0]); double l0=Math.sqrt(dot(b0,b0));
    if(l0>1e-12){ double c=Math.max(-1,Math.min(1,dot(cm.g4Tan,b0)/l0)); double th=Math.acos(c); E+=0.5*kb*th*th; }
    for(int j=1;j<M;j++){ double[] a=sub(nd[j],nd[j-1]), b=sub(nd[j+1],nd[j]);
        double la=Math.sqrt(dot(a,a)), lb=Math.sqrt(dot(b,b)); if(la<1e-12||lb<1e-12) continue;
        double c=Math.max(-1,Math.min(1,dot(a,b)/(la*lb))); double th=Math.acos(c); E+=0.5*kb*th*th; }
    return E;
}
```

**E_bend = Σ_j ½·kb·θ_j²**, one term per joint, θ = exterior angle (0 = straight):

- **Clamped joint 0**: between the fixed unit tangent `g4Tan` and `b0 = nd[1]−nd[0]`;
  `c = clamp(g4Tan·b0 / |b0|)`, `θ = acos(c)`. Only **node 1** is a free variable here (node 0, g4Tan fixed).
- **Interior joints j = 1..M−1**: between `a = nd[j]−nd[j−1]` and `b = nd[j+1]−nd[j]`;
  `c = clamp(a·b / (|a||b|))`, `θ = acos(c)`. Free variables **nodes j−1, j, j+1**.
- **Regularization / clamps (must be reproduced):** `acos` argument clamped to `[−1,1]`
  (`Math.max(-1,Math.min(1,…))`); degenerate joints skipped (`la<1e-12 || lb<1e-12`, and `l0>1e-12` for
  joint 0). GPU mirror `s2BendEnergyK` (`TwoBodyGpuKernels.java:440`) is an exact replica.

**Bending force in the frozen code is a central finite difference of this energy** (`s2NodeForces`
`:6301–6304`) — this is the FD cost B3 replaces:

```java
double h=1e-5;   // µm central-difference of the bending energy per coordinate
for(int j=0;j<=M;j++) for(int k=0;k<3;k++){ double sav=nd[j][k];
    nd[j][k]=sav+h; double Ep=s2BendEnergy(cm,nd); nd[j][k]=sav-h; double Em=s2BendEnergy(cm,nd); nd[j][k]=sav;
    F[j][k]+= -((Ep-Em)/(2*h))*1e6; }   // −dE/dx (J/µm → N)
```

## 6. Substrate floor penalty (`s2NodeForces` `:6305–6306`)

```java
for(int j=0;j<=M;j++){ double z=dot(nd[j],cm.eup); if(z<cm.g4floorZ){ double pen=(cm.g4floorZ-z)*1e-6; double fk=cm.g4kfloor*pen;
    for(int k=0;k<3;k++) F[j][k]+=fk*cm.eup[k]; } }
```

One-sided penalty **E_floor = Σ_j ½·kfloor·pen_j²**, `pen = (floorZ − z)·1e-6` [m] active only when
`z = nd_j·ê_up < g4floorZ`; force `fk·ê_up` [N] upward. A **C¹ kink** at `z = g4floorZ` (the Hessian jumps).

## 7. Node drag + Brownian (`s2Solve` `:6371–6373`)

```java
double aN=cm.g4gammaNode/cm.dt;
for(int j=1;j<=M;j++){ int fb=j-1; for(int k=0;k<3;k++){ Msys[3*fb+k][3*fb+k]+=aN;
    if(brownian) F[3*fb+k]+=brownTorque(cm.g4gammaNode,cm.dt,seed,t,0x4711L+((long)j*131+k)*7919L); } }
```

Implicit drag adds `aN = γ_node/dt` [N/m] to each free-node diagonal. Brownian FDT force
`brownTorque(γ,dt,…) = sqrt(2·kT·γ/dt)·g` (`:2174–2178`, `g` a hashed unit Gaussian) added to the RHS
(deterministic given seed/step). Brownian is a pure RHS term — it does **not** enter the Jacobian.

## 8. The (3M+2) Newton/backward-Euler system (`s2Solve` `:6358–6398`)

RHS = internal node force on the free nodes (`:6363–6364`):

```java
double[][] Fn=s2NodeForces(cm,cm.g4Node);
for(int j=1;j<=M;j++) for(int k=0;k<3;k++) F[3*(j-1)+k]=Fn[j][k];
```

Beam tangent = **nested central finite difference of the whole node force** (`:6365–6369`) — the object B3
replaces analytically:

```java
double hh=1e-5;   // µm central-difference of the beam force over the free DOF
for(int jc=1;jc<=M;jc++) for(int kc=0;kc<3;kc++){ int col=3*(jc-1)+kc; double sav=cm.g4Node[jc][kc];
    cm.g4Node[jc][kc]=sav+hh; double[][] Fp=s2NodeForces(cm,cm.g4Node);
    cm.g4Node[jc][kc]=sav-hh; double[][] Fm=s2NodeForces(cm,cm.g4Node); cm.g4Node[jc][kc]=sav;
    for(int jr=1;jr<=M;jr++) for(int kr=0;kr<3;kr++) Msys[3*(jr-1)+kr][col] += -((Fp[jr][kr]-Fm[jr][kr])/(2*hh))*1e6; }
```

So the assembled system is `Msys·dq = F` with **Msys = (γ/dt)·I − ∂F/∂q + [F8/converter block]**, i.e. a
backward-Euler/Newton step `(γ/dt·I + K)·dq = F(q)` where **K = −∂F/∂q = the energy Hessian ∂²E/∂q²**. This
nested FD is `~M·3 = 12` force evaluations, each itself `~3(M+1)·? ≈ 30` energy evaluations for the bending
part — the cost, and the reason the merged `explicitBeamStep` GPU kernel (973 inlined nodes) exceeds
TornadoVM's 600-node inline cap. The GPU mirror `explicitBeamStep` (`TwoBodyGpuKernels.java:328`) builds the
same system with `s2NodeForcesK`/`s2BendEnergyK` and the same nested FD (`:366–376`).

## 9. F8 / converter / bind endpoint coupling (`s2Solve` `:6374–6392`)

This block is **already closed-form** (a Gauss–Newton linearization, NOT finite-differenced). Reproduced,
not re-derived. It couples the pivot node M (`pB = 3*(M−1)`) and φ, ψ (`iPhi, iPsi`).

Geometric Jacobian of the head attach point (`:6378–6381`), **note the rotation axis is `E = eup`**
(`:6359` `double[] E=cm.eup;`) — a deliberate frozen choice flagged in the GPU mirror (`:384`):

```java
double[] Jphi=crs(E,sub(C,cm.P)), Jpsi=crs(E,sub(xF8,C));            // µm
double[][] J={ {1,0,0, dot(Jphi,{1,0,0})*1e-6, dot(Jpsi,{1,0,0})*1e-6}, …3×5… };
```

Stiffness assembly (`:6382–6387`): `kfSI = kF8Code·1e6` (F8 spring), `kc = kconvCode` (converter), `kb =
kbindCode` (bind):

```java
for(int i=0;i<5;i++) for(int jj=0;jj<5;jj++){ double kij=kfSI*(J[0][i]*J[0][jj]+J[1][i]*J[1][jj]+J[2][i]*J[2][jj]);
    Msys[map[i]][map[jj]]+=kij; }                        // Gauss–Newton JᵀJ (map = {pB,pB+1,pB+2,iPhi,iPsi})
Msys[iPhi][iPhi]+=kc; Msys[iPhi][iPsi]-=kc; Msys[iPsi][iPhi]-=kc; Msys[iPsi][iPsi]+=kc+kb;
double aphi=cm.gammaPhi/cm.dt, apsi=cm.gammaPsi/cm.dt; Msys[iPhi][iPhi]+=aphi; Msys[iPsi][iPsi]+=apsi;
```

RHS (`:6388–6392`): the external head force `F8h` on the pivot, its generalized-torque projections about
`E = eup`, and the converter/bind spring residuals:

```java
double th=cm.psi-cm.phi;
double QphiF8=dot(E,crs(sub(C,cm.P),F8h))*1e-6, QpsiF8=dot(E,crs(sub(xF8,C),F8h))*1e-6;
F[pB]+=F8h[0]; F[pB+1]+=F8h[1]; F[pB+2]+=F8h[2];
F[iPhi]+=QphiF8+kc*(th-cm.thetaS); F[iPsi]+=QpsiF8-kc*(th-cm.thetaS)-kb*(cm.psi-cm.psiActin);
```

`F8h` is the cross-bridge bond force, computed **before** the solve (`stepS2` `:6338–6339`,
`:6351`) and passed as a **constant** for this step (search step uses `F8h = 0`, `:6354`). The derived
geometry `C, xF8` come from `geomC` (`:1243–1250`): `C = A + lb·û_B`, `û_B = ê_up·cosφ + b̂·sinφ`,
`xF8 = C + R_conv(ψ)·(…)`.

## 10. Solve + update (`s2Solve` `:6393–6397`)

```java
double[] dq=solveLin(Msys,F,n);                                        // Gauss–Jordan, partial pivot (:5002)
for(int j=1;j<=M;j++){ int fb=j-1; for(int k=0;k<3;k++) cm.g4Node[j][k]+=dq[3*fb+k]*1e6; }   // m→µm
cm.phi+=dq[iPhi]; cm.psi+=dq[iPsi];
cm.g4Node[0]=cm.g4E.clone(); cm.A=cm.g4Node[M]; cm.P=cm.A; geomC(cm);
```

`solveLin` (`:5002–5009`) is Gauss–Jordan with partial pivoting; the GPU `solveN`
(`TwoBodyGpuKernels.java:484`) is an exact replica. The convergence criterion is the **single implicit
Newton step per time step** (stretch + bending implicit ⇒ each step is a Newton step toward force
equilibrium; the beam holds its contour). There is no inner iteration to convergence within a step.

## 11. What B3 must reproduce EXACTLY (the frozen contract)

The analytic residual + Jacobian must equal, term for term:

| quantity | frozen source | frozen method |
|---|---|---|
| stretch force | `s2NodeForces` `:6298–6300` | analytic (closed form) |
| bending energy | `s2BendEnergy` `:6285–6293` | closed form (`½kbθ²`, `θ=acos`) |
| bending force | `s2NodeForces` `:6301–6304` | **central FD of energy** ← replaced |
| floor force | `s2NodeForces` `:6305–6306` | analytic (one-sided) |
| beam tangent K | `s2Solve` `:6365–6369` | **nested central FD of force** ← replaced |
| node drag / Brownian | `s2Solve` `:6371–6373` | `aN=γ/dt`; RHS Brownian |
| F8/converter block | `s2Solve` `:6374–6392` | closed form (Gauss–Newton, `E=eup`) — reproduced verbatim |
| node indexing / clamps | node0 pinned + `g4Tan`; nodes 1..M free; node M=P; φ,ψ | `:6337,6396,6397` |

The physical model is **unchanged**: same L/l0/M/topology, same `½kb θ²` bending, same `½ks(Δl)²` stretch,
same floor, same F8/converter/bind coupling with `E = eup`, same clamps/regularization, same
single-Newton-step convergence, same canonical model id.
