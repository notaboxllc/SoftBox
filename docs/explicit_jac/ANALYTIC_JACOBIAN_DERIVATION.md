# B3 — Exact analytic residual + Jacobian of the EXPLICIT_S2_L40 beam

Companion to `BEAM_EQUATIONS_FROZEN.md` (B2). Derives, in closed form, the SAME internal node force
(residual) and its exact tangent (= energy Hessian) that `s2Solve` builds by nested finite differences.
Implemented in `softbox/ExplicitBeamAnalytic.java` (double precision); validated in B4
(`ExplicitBeamJacobianHarness`, results in `RUN_LOGS/explicit_jac/gate.txt`).

**Conventions.** Node coords `x` in µm; a length in µm → m by ×1e-6. Total energy
`E = E_stretch + E_bend + E_floor` [J]. Internal node force `F = −∂E/∂x_m` [N]; the code writes it as
`F = −(∂E/∂x_µm)·1e6`. The backward-Euler tangent assembled in `s2Solve` is
**K = −∂F/∂x_m = ∂²E/∂x_m² = the energy Hessian** [N/m]. So *the analytic Jacobian is the energy Hessian*;
`s2Solve`’s `Msys` beam block equals `K` (the drag `γ/dt·I` and the F8 block are added separately). Free
DOF are nodes 1..M (node 0 fixed ⇒ its rows/cols are dropped — exactly the free–free block of the full
Hessian, which is what the frozen FD tangent samples).

---

## 1. Stretch block (exact, matches the frozen analytic force)

Per segment `i`, `b = x_{i+1} − x_i` (µm), `ℓ = |b|` (µm), `ℓ_m = ℓ·1e-6`, `u = b/ℓ`. Energy
`E = ½ ks (ℓ_m − ℓ0_m)²`, tension `t = ks(ℓ_m − ℓ0_m)` [N].

**Force** (identical to `s2NodeForces` `:6299–6300`, hence bit-reproduces the frozen stretch force):
```
F_i = +t·u ,   F_{i+1} = −t·u        (N)
```
Derivation: `∂ℓ/∂x_i = −u`, `∂ℓ_m/∂ℓ = 1e-6`, `∂E/∂x_{i,µm} = t·1e-6·(−u)`, so
`F_i = −∂E/∂x_{i,m} = −(∂E/∂x_{i,µm})·1e6 = +t·u`. ✔

**Hessian** (the standard spring tangent, `p_i` in metres):
```
∂²E/∂p_i²      =  ks·(u uᵀ) + (t/ℓ_m)·(I − u uᵀ)          [N/m]
∂²E/∂p_{i+1}²  =  same
∂²E/∂p_i∂p_{i+1} = −[ ks·(u uᵀ) + (t/ℓ_m)·(I − u uᵀ) ]
```
The `ks·uuᵀ` term is the axial (material) stiffness; `(t/ℓ_m)(I−uuᵀ)` is the geometric stiffness — positive
under tension, **negative under compression** (the buckling channel), zero at rest (`t=0`). Symmetric by
construction. Implemented in `ExplicitBeamAnalytic.addStretchHess`.

---

## 2. Bending block (the core new derivation)

Each joint contributes `E = ½ kb θ²`, `θ = acos(c)`, `c = (a·b)/(l_a l_b)` (interior joints, `a,b` the two
incident segments) or `c = (t·b0)/|b0|` (clamped joint 0, `t = g4Tan` fixed unit vector, `a→t`, `l_a→1`).

### 2.1 Reduce the transcendental to two well-behaved scalars

Write `E = g(c)` with `g(c) = ½ kb (acos c)²`. Then, **exactly**,
```
∇E     = g'(c) ∇c
Hess E = g''(c) ∇c ⊗ ∇c + g'(c) ∇²c
```
with (using `θ = acos c`, `s = sinθ = √(1−c²) ≥ 0` on `θ∈[0,π]`)
```
g'(c)  = −kb·θ/s                      ≡ −kb·A1(θ)
g''(c) =  kb·(1 − θ·c/s)/s²           ≡  kb·A2(θ)
```
Derivation of `g''`: `g'(c) = kb·θ·(dθ/dc) = −kb θ/s`; then
`g''(c) = −kb·d/dc(θ/s) = −kb·[(dθ/dc)·s − θ·(ds/dc)]/s²` with `dθ/dc=−1/s`, `ds/dc=−c/s`, giving
`g''(c) = −kb[−1 + θc/s]/s² = kb(1 − θc/s)/s²`. ✔

**Key property — the transcendental is confined to the two finite scalars `A1, A2`.** `∇c` and `∇²c` are
**rational** functions of the coordinates (§2.2) — no `acos`, no `1/s` singularity. The gradient direction is
therefore always well-defined; only the two *scalar* multipliers carry `θ`, and both are finite as `θ→0`:
```
A1(θ) = θ/sinθ            → 1      A1 = 1 + θ²/6 + 7θ⁴/360 + …
A2(θ) = (1 − θcotθ)/sin²θ → 1/3    A2 = 1/3 + (2/15)θ² + (2/63)θ⁴ + …
```
`A2`’s numerator `1 − θcotθ = θ²/3 + θ⁴/45 + …` cancels catastrophically against 1 for small θ, so `A2` is
evaluated from the series below `θ < 5e-2` and directly above; `A1` is direct except `θ < 1e-3` (series).
Implemented in `ExplicitBeamAnalytic.a1/a2`. These series are the exact Taylor expansions of the same
functions, so no energy is altered.

### 2.2 Gradient and Hessian of `c` (rational, exact for all non-degenerate segments)

With `â = a/l_a`, `b̂ = b/l_b`, and `D ≡ ∂c/∂a`, `E ≡ ∂c/∂b`:
```
D = ∂c/∂a = b/(l_a l_b) − c·a/l_a²
E = ∂c/∂b = a/(l_a l_b) − c·b/l_b²
```
Second derivatives (`p,q` component indices; all symmetric where required; derived by direct
differentiation, verified in B4):
```
∂²c/∂a_p∂a_q = −(a_p b_q + b_p a_q)/(l_a³ l_b) − c·δ_pq/l_a² + 3c·a_p a_q/l_a⁴          (Haa)
∂²c/∂b_p∂b_q = −(b_p a_q + a_p b_q)/(l_b³ l_a) − c·δ_pq/l_b² + 3c·b_p b_q/l_b⁴          (Hbb)
∂²c/∂a_p∂b_q =  δ_pq/(l_a l_b) − b_p b_q/(l_a l_b³) − a_p a_q/(l_a³ l_b) + c·a_p b_q/(l_a² l_b²)   (Hab)
```
`Haa`, `Hbb` are symmetric; `∂²c/∂b∂a = Habᵀ`. (Cross-checked numerically to ~1e-9 in B4.)

### 2.3 Map segment derivatives to node derivatives (constant incidence)

`a = x_j − x_{j−1}`, `b = x_{j+1} − x_j` are linear in the nodes with constant incidence
`(s_a, s_b)`: node `j−1 → (−1, 0)`, node `j → (+1, −1)`, node `j+1 → (0, +1)`. Hence
```
∇c :  ∂c/∂x_{j−1} = −D ,  ∂c/∂x_j = D − E ,  ∂c/∂x_{j+1} = E
∇²c:  [∂²c/∂x_α∂x_β] = s_a(α)s_a(β)·Haa + s_b(α)s_b(β)·Hbb + s_a(α)s_b(β)·Hab + s_b(α)s_a(β)·Habᵀ
```

### 2.4 Assemble force and Hessian (with the µm→SI factors)

```
F_node = kb·A1·1e6·(∂c/∂x_node)              [N]        (= −∂E/∂x_m, since −g' = kb·A1)
K_αβ   = 1e12·kb·[ A2·(∂c/∂x_α)(∂c/∂x_β)ᵀ − A1·(∂²c/∂x_α∂x_β) ]   [N/m]
```
The `1e6` (force) and `1e12` (Hessian) convert the per-µm / per-µm² `c`-derivatives to per-m / per-m² —
exactly the frozen code’s `·1e6` on `−dE/dx` and the tangent’s `·1e6` on `−dF/dx`. Implemented in
`addBendForce` / `addBendHess`.

### 2.5 Clamped joint 0 (special case)

`a → t = g4Tan` (fixed **unit** vector ⇒ `l_a = 1`, `t` node-independent), only node 1 free through
`b0 = x_1 − x_0`:
```
∂c/∂b0 = t/l_b0 − c·b0/l_b0²
∂²c/∂b0² = −(b0_p t_q + t_p b0_q)/l_b0³ − c·δ_pq/l_b0² + 3c·b0_p b0_q/l_b0⁴
```
(the `Hbb` formula with `a→t`, `l_a→1`). Force/Hessian on node 1 use the same `A1(θ0), A2(θ0)` scalars.

### 2.6 Small-angle / singularity behaviour — proof it is the exact gradient of the SAME energy

- **θ→0 (nearly straight).** `∇c → 0` at exactly straight (`c=1` is a maximum of `c`), and `A1→1`,
  `A2→1/3` are finite ⇒ `F_bend → 0` and `K_bend → 1e12·kb·(−1·∇²c|_{θ=0})`, a finite limit. The naive
  `d(acos c)/dc = −1/√(1−c²)` blows up, but here `1/s` never appears alone — it is absorbed into the finite
  `A1, A2`. Because `A1, A2` are the exact Taylor series of `θ/sinθ` and `(1−θcotθ)/sin²θ`, the analytic
  force/Hessian are the **exact** derivatives of `½kb(acos c)²`; the series only removes floating-point
  cancellation, it does not change the function. The B4 step-size sweep confirms the analytic tangent is the
  `h→0` limit even for a `θ ≈ 1e-4` pose (clean `Δ ∝ h²` over 4 decades).
- **θ→π (genuine singularity).** `s→0` with `θ ≠ 0` makes `A1 = π/s → ∞` — a real cusp of `½kbθ²` (a 180°
  fold). This is a property of the *energy*, not of the discretization, and is not reached by a valid stiff
  beam (`kb·A1·∇c` would be an enormous restoring force long before). No pose in the ensemble approaches it.
- **`acos` clamp / degenerate skips.** The analytic side reproduces the frozen clamp `c∈[−1,1]` and the
  `l<1e-12`, `len<1e-15` skips (`ExplicitBeamAnalytic` uses the same guards) ⇒ identical limiting behaviour.
- **Tangent normalization / near-singular lengths.** `1/l_a, 1/l_b` appear (as in the frozen `u=b/len`);
  the same `len<1e-15`/`l<1e-12` guards bound them. No additional regularization is introduced — none is
  needed to match the frozen model.

**No alteration of the beam energy/residual was required** to obtain a clean Jacobian (the B4 STOP condition
did not trigger).

---

## 3. Floor block (exact, one-sided)

When `z = x_j·ê_up < g4floorZ`: `pen_m = (g4floorZ − z)·1e-6`, `E = ½ kfloor·pen_m²`.
```
F_j     = kfloor·pen_m·ê_up                    [N]   (= frozen s2NodeForces :6305–6306)
K_jj    = kfloor·(ê_up ⊗ ê_up)                 [N/m] (rank-1, active only while penetrating)
```
`∂pen_m/∂x_{j,m} = −ê_up`, so `∂²E/∂x_m² = kfloor·ê_up⊗ê_up`. The penalty is **C¹** at `z = g4floorZ` (the
Hessian jumps from 0 to `kfloor·ê_up⊗ê_up`); away from that kink the block is exact. Implemented in
`addFloorForce` / `addFloorHess`.

---

## 4. Node drag, Brownian, and the F8/converter block

- **Drag** adds `aN = γ_node/dt` to each free-node diagonal (unchanged, `s2Solve` `:6371–6372`); it is not
  part of `−∂F/∂q` but is the backward-Euler mass/drag term. Reproduced identically.
- **Brownian** is a pure RHS term (`brownTorque`), independent of the Jacobian. Reproduced identically.
- **F8 / converter / bind block is already closed-form** (a Gauss–Newton linearization `kfSI·JᵀJ` +
  converter `kc` + bind `kb` + angle drag, with the frozen **`E = eup`** rotation-axis convention). It is
  **not** the FD bottleneck, so it is reproduced **verbatim** in `ExplicitBeamAnalytic` (geometric Jacobian
  `Jphi = eup×(C−P)`, `Jpsi = eup×(xF8−C)`; generalized F8 torques `Qphi = eup·((C−P)×F8h)·1e-6`,
  `Qpsi = eup·((xF8−C)×F8h)·1e-6`). B4 validates the geometric Jacobian against a central difference of the
  finite `eup`-axis rotation of `(C−P)`/`(xF8−C)` (max Δ = 1.3e-15 µm) and the generalized-force identity
  `Q ≡ Jcol·F8h·1e-6` (Δ = 0). The Gauss–Newton block deliberately omits the curvature term
  `∂J/∂q` — matching the frozen model exactly (the hard constraint: same coupling), not the true F8-energy
  Hessian.

---

## 5. Symmetry and definiteness

`Hess E` is symmetric by construction (each block above is symmetric or an explicit `X + Xᵀ`). B4 measures
max relative asymmetry `7.0e-17` (machine). Positive-definiteness is not required (compression can make the
geometric stretch term and the floor-off regions indefinite) — the frozen solve handles that via the
`γ/dt·I` drag regularization, unchanged here.

---

## 6. Equivalence summary

| block | analytic form | equals frozen | validated (B4, normwise) |
|---|---|---|---|
| stretch force | `±t·u` | identical closed form | 2.9e-11 |
| stretch Hessian | `ks·uuᵀ + (t/ℓ)(I−uuᵀ)` | = FD tangent limit | 4.2e-9 |
| bend force | `kb·A1·1e6·∇c` | = `−dE_bend/dx` (replaces code FD) | 8.4e-12 |
| bend Hessian | `1e12·kb·[A2·∇c∇cᵀ − A1·∇²c]` | = nested-FD tangent limit | 1.3e-8 |
| floor force / Hessian | `kfloor·pen·ê_up` / `kfloor·ê_upê_upᵀ` | identical | 1.6e-11 / 2.3e-13 |
| F8 block | Gauss–Newton (`E=eup`) verbatim | identical (already analytic) | J 1.3e-15 µm, Q 0 |

The analytic residual reproduces the frozen `s2NodeForces` to **8e-10** normwise (limited by the frozen
bend FD), and the analytic tangent reproduces the frozen nested-FD tangent to **1.1e-6** normwise (limited
by the code’s `h=1e-5` FD truncation — precisely the error the analytic form removes). Against a clean
Richardson oracle of the analytic force, the analytic tangent matches to **~4e-9**. Gate: **PASS**.
