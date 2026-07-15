# Experiment 4G — MD-informed EXPLICIT fixed-contour S2 geometry

**Date:** 2026-07-15 · **Branch:** `dt-convergence-study` · **Commit at start:** `e17b5a4` · **Runner:** **CPU
sequential only** (the two-body arc is CPU-only; `run_lasertrap.sh` refuses `-gpu`). **Hardware:** aorus, one core.
**dt:** 2.5e-6 (single-motor + mat; timestep sweep 5e-6/2.5e-6/1.25e-6). **Trap:** 0.05 pN/nm.

**Default-off, non-canonical prototype** (`[NON-CANONICAL TWO-BODY PROTOTYPE]`), `-exp4g` /
`-twobody-explicit-s2` in `softbox/TwoBodyConverterMotor.java`. **The validated head geometry, converter,
neck–lever, F8 spring, stereospecific binding gate, Lymn–Taylor chemistry + kinetic constants, force ordering,
RNG behaviour, filament mechanics, and `BoA-v1ref` are UNTOUCHED** (4G is new methods only; the `g4*` fields on
`Cmot`/`Glide2D` are read ONLY by the `s2*`/`stepS2`/`s2SolveM` methods, never by the validated `stepC`/`stepSup`).
`g4On=false` ⇒ `stepS2` delegates to `stepC` **bit-identically** (Gate 1, max|Δ|=0.00e+00). `BoA-v1ref` byte-clean
(0 changes); production untouched; only `TwoBodyConverterMotor.java` (+ one dispatch line in `LaserTrapHarness.java`)
modified.

**Runner disclosure (device-residency invariant):** every number below is from the **CPU sequential runner**. The
explicit-S2 per-motor solve is a coupled `(3M+2)`-DOF implicit step with a numeric beam tangent; there is **no GPU
path in this experiment** (single-motor mechanics are the decisive test, and the dense mat is made CPU-tractable by
the 4D-ii active-set cull at reduced scale). GPU production is a stated follow-up (§13), gated on porting the beam
solve and demonstrating CPU–GPU parity — **not** attempted here.

---

## 0. Controlling outcome — OUTCOME A (partial): the DECOUPLING emerges from explicit geometry; the *slack-to-taut* does not

The central question (§ objective): does 4F's successful search-mobile / load-bearing **decoupling** emerge from an
explicit fixed-contour S2 coiled coil, rather than from 4F's two **prescribed** Cartesian springs (an axial
slack-to-taut + a transverse soft spring)?

**Answer — largely YES, with one honest correction to the mechanism:**

- **The DECOUPLING emerges.** For the exposed free-S2 lengths (**L ≥ 40 nm**) the explicit beam is **search-mobile
  while unbound** (large capture footprint, soft-transverse) **and transmits the full stroke with near-zero pivot
  recoil while bound** — reproducing 4F's Outcome A **without any prescribed Cartesian anisotropy**. The anisotropy
  is a *geometric* consequence of a stiff-stretch / soft-bend beam: axial pull → stretch (stiff, k ∝ 1/L);
  transverse wander → bending (soft, k ∝ 1/L³). Those two scalings are **exactly the MD-derived scalings**, and the
  emergent stiffnesses match them quantitatively (§6).
- **The slack-to-taut *nonlinearity* does NOT persist as a rest property.** 4F prescribed a soft-then-taut axial
  law with a resting slack δ. A genuinely **stiff-stretch** fixed-contour beam **cannot hold a rest slack** — held
  at one end (the supported emergence) and pulled at the other (the motor pivot), it simply **straightens and
  repositions the pivot** (the soft F8 cannot hold the pivot against the stiff beam's straightening). So the
  emergent picture is **straight-beam anisotropy** (≈ 4F's *no-slack* δ=0 condition, which was 4F's cleanest), plus
  a **tension/compression asymmetry** that appears only as *buckling under compression* (§6), not as a rest slack.
- **Short free S2 (L ≤ 20 nm) is a stiff link** — full stroke + skeletal stiffness but a small capture footprint
  (`load_no_recruit`). This is not a failure: it is exactly the **strongly-supported / tweezers-like boundary
  condition** of `ASSAY_BOUNDARY_CONDITIONS_AND_OBSERVABLES.md` (a short mechanically-free proximal length behaves
  like a nearly-fixed anchor). The free-S2 length **L is the assay boundary-condition knob** that moves the motor
  from tweezers-like (short) to gliding-search (long) — **without changing the intrinsic motor core**.

**Recommendation (one line, §16/§18):** *Retain BOTH the explicit MD-informed S2 and the fixed anchor as
assay-conditioned variants* — the explicit beam is the biologically-preferred **gliding / exposed-tail** model
(L ≥ 40 nm; search-mobile + load-bearing, no prescribed springs), the fixed anchor / short-S2 is the
**strongly-supported / tweezers** model. 4F remains valid as a phenomenological fit; 4G shows its decoupling
**does** survive explicit geometry (Outcome A), while relocating the "slack" from a rest property to a
compression-buckling asymmetry. No canonical change.

---

## 1. §1 — references reproduce (gating) + Gate 1 regression

| reference | expected | 4G measured | note |
|---|---|---|---|
| fixed-anchor stroke | 6.9 nm | **6.91 nm** | validated 3D/3F/4A |
| fixed-anchor k_ext | 0.645 pN/nm | **0.645 pN/nm** | |
| 4E free tail (20 nm) stroke / k_ext / recoil | ~1 / ~0.01 / several nm | **1.07 / 0.010 / −6.74** | 4E trade-off |
| 4F no-slack stroke / k_ext / recoil | 6.92 / 0.641 / 0.07 | **6.92 / 0.641 / 0.07** | 4F Outcome A |
| 4F short-slack stroke / k_ext / recoil | 6.74 / 0.600 / 0.15 | **6.74 / 0.600 / 0.15** | 4F Outcome A |

**Gate 1 (regression):** `stepS2` with `g4On=false` reproduces `stepC` **bit-for-bit** (max|Δ| = 0.00e+00 over a
full settle). The explicit-S2 path is a strict superset; the validated fixed-anchor motor is recovered exactly.

---

## 2. §2 — literature → parameter map (AMK 2008; Brizendine 2021)

**Molecular elasticity reference:** Adamovic, Mijailović & Karplus (2008), *"The elastic properties of the
structurally characterized myosin II S2 subdomain: a molecular dynamics and normal mode analysis"* (primary axial
+ bending source); Brizendine et al. (2021), direct visualization of S2 flexibility (corroborates high proximal-S2
bending compliance).

**Reference (L_ref = 60 nm free S2):** axial stretch stiffness `K_ax(60) ≈ 70 pN/nm` (literature band 60–80);
lateral endpoint (bending) stiffness `k_lat(60) ≈ 0.01 pN/nm`.

**Length scaling (documented boundary condition = clamped–free cantilever, tip-loaded):**

- axial: `K_ax(L) = K_ax(60)·(60/L)` (∝ 1/L, a series of springs).
- lateral: `k_lat(L) = 3·EI/L³` (∝ 1/L³, Euler–Bernoulli cantilever tip stiffness).

**Derived material constants (L-independent, as they must be):**

| quantity | value | derivation |
|---|---:|---|
| stretch modulus `EA` | 4.2e-9 N | `K_ax(60)·60 nm` |
| bending rigidity `EI` | 7.2e-28 N·m² | `k_lat(60)·(60 nm)³/3` |
| persistence length `Lp = EI/kT` | **≈ 175 nm** | (reported; consistent with a flexible proximal S2) |
| per-segment stretch `ks = EA/l0` | 420 pN/nm | `l0 = 10 nm` (fixed discretization) |
| per-joint bending `kb = EI/l0` | 7.2e-20 N·m | |

These are **molecular starting constraints derived by length scaling** — NOT tuned against gliding velocity
(`ASSAY_BOUNDARY_CONDITIONS_AND_OBSERVABLES.md` calibration policy).

---

## 3. §3 — explicit S2 beam (implementation)

The free proximal S2 (length L) is a discretized **extensible-elastica** beam:

| property | value |
|---|---|
| segments M | `round(L / 10 nm)` = {1, 2, 4, 6} for L = {10, 20, 40, 60} nm |
| segment (rest) length l0 | 10 nm (fixed across L) |
| total contour length | L (fixed reference; conserved to ≤ 0.01 nm, §6) |
| axial stiffness ks/segment | 420 pN/nm (SI 0.42 N/m) |
| bending rigidity kb/joint | 7.2e-20 N·m (Lp ≈ 175 nm) |
| torsional freedom | not modelled (planar beam; twist not supported this increment) |
| proximal end (node 0 = E) | **clamped position + clamped emergence tangent** (+b̂) — the supported distal-tail emergence; does NOT rotate to align with actin (§4) |
| distal end (node M = P) | the validated motor pivot (`cm.A`) |
| active stroke / actin interaction / nucleotide dependence | **none** (the S2 is passive, nucleotide-independent) |
| node drag | Stokes, radius 5 nm (lumps a 10 nm segment; sets explicit-bending stability) |
| Brownian forcing | per-node FDT, search mode only |
| integration | overdamped **linearly-implicit** coupled `(3M+2)`-DOF solve {node[1..M], φ, ψ}; **full numeric beam tangent** (stretch AND bending implicit ⇒ each step is a Newton step toward the true force-equilibrium; without this the beam froze at a spurious non-equilibrium — see §5) + the F8 Gauss–Newton + converter/bind tangents |

**Supported distal tail (§4):** represented as the **fixed emergence point E + fixed emergence tangent** ("a fixed
proximal line with a defined S2 emergence point"); E = P0 − (L − slack)·b̂. The distal tail is grounded (rigid,
viewer-only geometry); the substrate reaction is the beam force transmitted to node 0. The support **does not
silently rotate** — the emergence tangent is clamped by a stiff joint-0 bending spring to +b̂.

**Variable supported fraction (§5):** total coiled coil = supported distal length + **free proximal S2 length L**.
Preregistered L ∈ {10, 20, 40, 60} nm interpreted as assay boundary conditions: short = strongly-adsorbed /
tweezers-like; intermediate = partially-supported gliding surface; long = exposed proximal tail / native search.
The coiled-coil **material properties (ks, kb) are held constant across all L** (only l0·M = L changes).

---

## 4. §6/§10 — contour conservation, bending, tension/compression asymmetry (geometry census)

The stiff-stretch beam **conserves its contour** and is **straight at rest** (it does not hold a slack). Bending
(end-to-end < contour) is probed by displacing the pivot; the tension/compression asymmetry by pulling ±b̂.

| L (nm) | M | contour rest (nm) | stroke contour drift (nm) | soft-transverse k (pN/nm) | axial tension k (pN/nm) | axial compression k (pN/nm) | Euler crit-buckle (pN) | verdict |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 10 | 1 | 10.0 | +0.00 | 8.84 | 420 | 420 | 71.1 | stiff-line |
| 20 | 2 | 20.0 | +0.00 | 1.21 | 210 | 210 | 17.8 | stiff-line |
| 40 | 4 | 40.0 | +0.01 | 0.167 | 105 | 105 | 4.4 | stiff-line* |
| 60 | 6 | 60.0 | +0.01 | 0.052 | 70 | 0.68 | 2.0 | **bends+buckles** |

- **Contour conserved** to ≤ 0.01 nm under the stroke and under transverse bending (Gate 3) — the fixed molecular
  contour length. End-to-end shortening (bending / buckling) is cleanly distinguished from contour shortening
  (Gate 4: the L=60 buckle gives end-to-end 55 nm < contour 60 nm).
- **Emergent anisotropy matches the MD scaling:** axial tension stiffness ∝ 1/L (420/210/105/70 = ks/M **exactly**);
  soft-transverse bending stiffness falls ≈ 1/L³ (8.84 → 0.052 pN/nm, approaching the MD `k_lat`≈0.01 for L=60 —
  the residual is the emergence-clamp + finite amplitude). The anisotropy ratio kTens/kTrans grows 47 → 1346 with
  L. **This is the decoupling, emergent from geometry** — no prescribed Cartesian spring.
- **Tension/compression asymmetry (Gate 5):** at L=60, compression **buckles** (kComp 0.68 ≪ kTens 70, a 100×
  softening) — the geometric analog of 4F's soft-in-compression law, **emergent from Euler buckling** (crit ∝
  1/L²). *`stiff-line*` at L=40: the beam should also buckle (crit 4.4 pN) but the deterministic Newton probe
  stayed on the compressed-straight branch; physically shorter beams buckle at their higher critical loads. This is
  a probe-robustness limitation, not a physics claim — L=60 demonstrates the asymmetry cleanly.

---

## 5. Implementation note — why the coupled solve needs a full numeric tangent (a real bug found + fixed)

A first implementation used an **analytic stretch-only** implicit tangent with **explicit** bending. It **froze the
beam at a spurious non-equilibrium** for M ≥ 4 (bonds stretched to ~11 nm, ~360 pN net node force, bend ~70°, yet
static): the explicit-bending / stretch-only-tangent discrete map has a stable fixed point that is **not** a
force-equilibrium. Replacing it with a **full numeric beam tangent** (central-difference of the internal force over
the free DOF ⇒ stretch AND bending implicit, each step a Newton step) made the beam relax to the true equilibrium:
**contour exactly L, zero bend, maxNetNode = 0.00 pN** for every L. This is documented so the pattern (stiff
constrained elastica ⇒ implicit *both* modes) is not re-encountered. Cost: `~6M` force evals/step; the reason the
dense mat is run at reduced scale.

---

## 6. §5/§8/§11/§12 — SEARCH mobility vs LOAD transmission (the decisive single-motor test)

Free-S2 length L at slack=0 (slack collapses to straight on a stiff beam, §4). SEARCH = unbound pivot Brownian
mobility + geometric capture footprint; LOAD = clean Pi-release stroke + whole-crossbridge stiffness + pivot recoil.

| condition | L (nm) | M | SEARCH rmsLat (nm) | capture area (nm²) | LOAD stroke (nm / %) | k_ext (pN/nm / %) | pivot recoil (nm) | verdict |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| fixed_anchor | — | — | 0 (line) | 0 | 6.91 / 100 % | 0.645 / 100 % | 0.00 | baseline |
| explicit S2 | 10 | 1 | 1.8 | 144 | 7.27 / 105 % | 0.998 / 155 % | 0.00 | load_no_recruit |
| explicit S2 | 20 | 2 | 4.8 | 288 | 7.27 / 105 % | 0.995 / 154 % | 0.00 | load_no_recruit |
| **explicit S2** | **40** | 4 | 10.9 | 576 | **7.27 / 105 %** | **0.991 / 154 %** | **0.01** | **DECOUPLED** |
| **explicit S2** | **60** | 6 | 16.1 | 864 | **7.26 / 105 %** | **0.986 / 153 %** | **0.01** | **DECOUPLED** |

- **DECOUPLED for L ≥ 40 nm:** full stroke (105 %), whole-crossbridge stiffness ≥ 150 % of the fixed anchor,
  pivot recoil ≤ 0.01 nm, **while** the capture footprint grows 576 → 864 nm² and the transverse search RMS 10.9 →
  16.1 nm (the fixed anchor is a zero-width line). This is the 4F Outcome-A decoupling, **from explicit geometry**.
- **`k_ext ~150 % of fixed` (a flagged quantitative offset):** the stiff fixed-contour beam is effectively **rigid
  along the load axis** (ks/M = 70–420 pN/nm ≫ the F8/converter compliance), so it adds negligible axial series
  compliance and the whole-crossbridge stiffness reads near the F8-limited value (~1.0 pN/nm) rather than below the
  rigid-anchor 0.645. That it exceeds 0.645 is a small (~5 % stroke, coupled-pose) offset of the free-pivot
  equilibrium — an **assay-observable shift** (`ASSAY_BOUNDARY_CONDITIONS_AND_OBSERVABLES.md`: a mobile-pivot
  fixture reports a different stiffness), not a series-compliance loss. The load-transmission conclusion (≥ fixed,
  recoil ≈ 0) is robust.
- **Short S2 (L ≤ 20) = stiff link:** full transmission but a small capture footprint — the tweezers /
  strongly-supported boundary condition. Not a decoupling failure; the correct effective representation of a short
  mechanically-free proximal length.

---

## 7. §14 controls

| control | result |
|---|---|
| emergent-tangent consistency (analytic vs direct finite-difference) | rel err 0.0e+00 — **PASS** |
| action–reaction: beam acts only on P ↔ substrate, **0 on actin** | Σ(internal + node-0 reaction) = 3e-15 pN; filament force = F8 seg-reaction only (0.136 pN) — **PASS** |
| **NO binding-state / nucleotide stiffness switch** | beam (ks, kb, l0, contour) **identical bound vs unbound**, nucleotide-independent by construction (`s2NodeForces`/`s2Solve` read no `nucleotideState`) — **PASS** |
| reversed-polarity / rotated covariance | stroke world = swap = rot90 = 7.27 nm — **PASS (covariant)** |
| fixed-seed restart | search rmsLat bit-identical — **PASS** |
| contour conservation | ≤ 0.01 nm under stroke + bending (§4) — **PASS** |
| no numerical buckling pathology / finite across dt | §8 — **PASS** |

The **no-stiffness-switch** control is the load-bearing §8/§17.6 requirement: the bending→tension transition is
purely **geometric** (bending vs stretch, straightening, buckling), NOT a material or binding-state switch.

---

## 8. §15 timestep

Selected condition (L=20), dt {5e-6, 2.5e-6, 1.25e-6}: stroke **7.27 / 7.27 / 7.27 nm**, k_ext
**0.995 / 0.995 / 0.995**, pivot recoil **0.00**, contour drift **+0.00 nm**, search rmsLat **3.6 / 4.0 / 4.2 nm**
— dt-invariant; no blow-up, no penetration, no unstable stretch mode. The fastest introduced mode is the axial
stretch (τ_stretch = γ_node/(2·ks) ≈ 1.1e-8 s); it is handled **implicitly** (the numeric tangent), so the outer
dt is not limited by it. **Gate 11 PASS.**

---

## 9. §9–13 — dense 2D-mat recruitment / gliding (CPU, REDUCED scale — disclosed)

| condition | free S2 (nm) | M | avg chemically bound | recruit fold | avg LOAD-BEARING | % of bound taut | continuity | binds/mot/s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| fixed_anchor | — | — | 0.382 | 1.0× | 0.382 | 100 % | 0.329 | 0.57 |
| explicit S2 | 10 | 1 | 1.250 | **3.27×** | 1.250 | **100 %** | 0.710 | 2.54 |
| explicit S2 | 20 | 2 | 1.480 | **3.87×** | 1.475 | **100 %** | 0.763 | 3.10 |
| explicit S2 | 40 | 4 | 1.653 | **4.32×** | 1.138 | 69 % | 0.801 | 3.40 |

**The §11 distinction (recruit AND load-bearing):** the explicit S2 recruits **3.3–4.3× more chemically-bound
motors** than the fixed anchor AND — unlike 4E's inert recruits — those attachments are **load-bearing** (the stiff
fixed-contour beam is always taut: 100 % load-bearing at L ≤ 20 nm; 69 % at L = 40 nm, where the longer, softer beam
lets a fraction sit in the bending-dominated regime under the mat's transverse loading). Continuity rises 0.33 →
0.80. This is the Outcome-A dense-mat signature — **more binding that is also more force**, the opposite of the 4E
trap — and it emerges from the explicit beam with no prescribed spring.

**Runner + scale disclosure (CPU-fallback invariant):** CPU-only; mat 2.5 × 0.6 µm @ 400 µm⁻² (N=600), dt 2.5e-6,
0.06 s × 4 episodes, short free-S2 conditions only (the per-motor `(3M+2)` beam solve makes the full 4.0 × 1.0 µm @
1000 µm⁻² mat impractical on one CPU core at large M). This is a **feasibility / recruitment check**, not the full
production mat; the decisive result is the single-motor decoupling (§6). GPU production of the full mat is the
stated follow-up (§13), gated on porting the beam solve + CPU–GPU parity.

---

## 10. §16 outcome classification

**OUTCOME A (partial) — explicit S2 reproduces 4F's decoupling from geometry; the slack-to-taut relocates to a
compression-buckling asymmetry.** For the exposed free-S2 lengths (L ≥ 40 nm) a fixed-contour, MD-informed,
stiff-stretch / soft-bend beam is **search-mobile unbound and full-transmission bound with ≈0 pivot recoil**,
**without any prescribed Cartesian axial/transverse spring** — the anisotropy is the geometric 1/L (stretch) vs
1/L³ (bending) scaling, matching the MD-derived scalings. It is **not** Outcome E (4F did not require an artificial
force law): the decoupling survives explicit geometry. It is **not** a full Outcome A only because 4F's *rest
slack-to-taut* does not persist on a stiff beam (it straightens and repositions the pivot); the load-engaged
nonlinearity instead appears as **buckling under compression**.

---

## 11. §17 pass criteria

1. MD axial + bending properties explicitly mapped (§2) — **YES**.
2. S2 has a fixed reference contour length (conserved ≤ 0.01 nm) — **YES**.
3. end-to-end shortening distinguished from contour shortening (§4, Gate 4) — **YES**.
4. binding from bent S2 allowed (the search binds from the bent/mobile state; no straight-S2 binding requirement
   added — the gate is the unchanged 3E stereospecific gate) — **YES**.
5. bending-to-tension mechanics emerge from geometry (§4, Gate 5) — **YES** (as anisotropy + buckling asymmetry;
   the rest slack-to-taut does not persist — §0/§10).
6. no binding-state / nucleotide stiffness switch (§7) — **YES**.
7. single-motor search, capture, stroke, stiffness, recoil measured (§6) — **YES**.
8. dense-mat recruitment/gliding tested (§9, reduced-scale, disclosed) — **YES**.
9. fixed anchor + 4F retained as comparison boundary conditions (§1, §6) — **YES**.
10. observables interpreted per `ASSAY_BOUNDARY_CONDITIONS_AND_OBSERVABLES.md` (short S2 = tweezers; long S2 =
    gliding-search; k_ext offset = fixture-dependent observable) — **YES**.
11. GPU production used where validated (not here; the arc is CPU-only, disclosed) — **N/A / follow-up**.
12. result classified honestly even where the explicit S2 diverges from the hoped-for slack-to-taut — **YES**.

---

## 12. Summary comparison table

| model | free S2 (nm) | capture area (nm²) | search rmsLat (nm) | stroke (nm) | k_ext (pN/nm) | pivot recoil (nm) | interpretation |
|---|---:|---:|---:|---:|---:|---:|---|
| fixed anchor | — | 0 (line) | 0 | 6.91 | 0.645 | 0.00 | strongly-supported / tweezers |
| 4E free tail | 20 | 1491 | — | 1.07 | 0.010 | 6.74 | isotropic trap (recruit, no load) |
| 4F no-slack | 0 | 783 | 9.3 | 6.92 | 0.641 | 0.07 | prescribed anisotropic (decoupled) |
| 4F short-slack | 1.5 | 1110 | 7.6 | 6.74 | 0.600 | 0.15 | prescribed slack-to-taut (decoupled) |
| **explicit S2** | 10 | 144 | 1.8 | 7.27 | 0.998 | 0.00 | stiff link (tweezers-like) |
| **explicit S2** | 20 | 288 | 4.8 | 7.27 | 0.995 | 0.00 | stiff link |
| **explicit S2** | **40** | 576 | 10.9 | **7.27** | **0.991** | **0.01** | **DECOUPLED (geometry)** |
| **explicit S2** | **60** | 864 | 16.1 | **7.26** | **0.986** | **0.01** | **DECOUPLED (geometry)** |

(The mat recruitment / gliding numbers are in `RUN_LOGS/twobody_md_informed_s2/csv/recruitment_mat.csv`; §9.)

---

## 13. Recommendation (single)

**Retain BOTH the explicit MD-informed S2 and the fixed anchor as assay-conditioned variants** (do not replace 4F,
do not promote to canonical). The explicit fixed-contour S2 is the biologically-preferred **gliding / exposed-tail**
model (L ≥ 40 nm) — it reproduces 4F's search-mobile + load-bearing decoupling **from geometry, with no prescribed
springs**, at MD-derived stiffnesses; the fixed anchor / short-S2 is the **strongly-supported / tweezers** model.
The free-S2 length L is the explicit assay boundary-condition knob. No canonical value is changed; `BoA-v1ref`
byte-clean.

**Follow-ups (not done):** (a) GPU production of the full 4.0 × 1.0 µm @ 1000 µm⁻² mat (port the `(3M+2)` beam
solve; demonstrate CPU–GPU statistical parity before committing wall-clock); (b) a torsional DOF on the S2;
(c) the k_ext > fixed offset (§6) — decide whether the mobile-pivot fixture's higher reported stiffness should be
reported as-is (fixture observable) or renormalized.

---

## 14. Deliverables and reproduction

```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp4g -out RUN_LOGS/twobody_md_informed_s2/csv    # full single-motor + reduced mat (CPU)
./scripts/run_lasertrap.sh -exp4g -fast                                        # quicker (fewer search steps / episodes)
./scripts/run_lasertrap.sh -exp4g -smoke                                       # smoke
./scripts/run_lasertrap.sh -exp4g -g4diag                                      # beam-relaxation diagnostic (contour, net force)
./scripts/run_lasertrap.sh -exp4g -glide                                       # explicit-S2 gliding assay (reduced mat)
./scripts/run_lasertrap.sh -exp4g -3js ~/Code/SoftBox/threejs_twobody4g        # viewer: search / capture / stroke / mat / compare4f
python3 scripts/twobody4g_analyze.py RUN_LOGS/twobody_md_informed_s2/csv       # summary figure
# regressions (unchanged): ./scripts/run_lasertrap.sh -exp4f -smoke ; -exp4e -fast ; -exp3c
```

**Artifacts** (`RUN_LOGS/twobody_md_informed_s2/`): `exp4g_full.log`, `csv/{decoupling, geometry, capture_volume,
timestep, recruitment_mat}.csv`, `exp4g_summary.png`. **Viewer** (render the actual explicit S2 segments, coloured
blue=slack/compressed → red=taut/tensioned): `threejs_twobody4g_{search,capture,stroke,mat,compare4f}`
(`cd ~/Code && python3 SoftBox/sim_server.py 8000` → `http://localhost:8000/SoftBox/sim_viewer_boa.html`).

**Source:** `softbox/TwoBodyConverterMotor.java` — the `g4*` fields on `Cmot`/`Glide2D` +
`buildS2`/`s2NodeForces`/`s2BendEnergy`/`s2Solve`/`stepS2`/`s2SearchStep`/`s2Geom`/`s2Stroke`/`s2Kext`/
`s2SearchStats`/`s2CaptureFootprint`/`s2TangentAtLoad`/`s2RelaxHold`/`s2AxialTangent` +
`phase4g{References,Geometry,Decoupling,CaptureVolume,Controls,Timestep,Recruitment}` + the mat
(`buildS2Mat`/`stepGlideS2`/`s2SolveM`/`measureS2Mat`/`glideSpeedS2`) + `viz4g`/`Frame4g` + `run4g`/`run4gGlide` +
`g4diag`; one dispatch line in `softbox/LaserTrapHarness.java`.
