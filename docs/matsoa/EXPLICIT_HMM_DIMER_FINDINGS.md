# Explicit HMM-like myosin dimer — characterisation findings (`explicit-hmm-dimer-s2-l40`)

First two-headed HMM-like myosin dimer built from the flagship explicit S2 motor. **NEW default-off
architecture; the single-head explicit model is preserved BYTE-IDENTICAL** (only new files added —
`TwoBodyConverterMotor.java`/`MotorModel.java` are UNMODIFIED, git-verified). CPU-only (the explicit
two-body arc is CPU-only). Design + archaeology + S2-audit: `EXPLICIT_HMM_DIMER_DESIGN.md`.

## What was built
A **forked "Y" beam**: two explicit converter heads (each its own θ_s converter + pivot + F8
cross-bridge, byte-faithful to the single head) whose pivots tie to a **common junction via a shared
paired-S2 beam forked from the 4G beam**. Topology (default): shared **Ms=3 (30 nm)** + two proximal
branches **Ma=Mb=1 (10 nm)**, splay 25° ⇒ each head's tail path E→P is 40 nm (matches single-head L40),
with **most of S2 shared**. All head↔head coupling flows through the shared beam node DOF — **no
inter-rod springs, no atomics** (contrast the obsolete World-A `DimerCouplingSystem`).

One coupled overdamped Newton step per timestep over `q = {nd[1..NF], φ_A,ψ_A, φ_B,ψ_B}` (n = 3·NF+4 =
19 at the default sizing): stretch analytic + two F8/converter Gauss–Newton blocks (implicit); bending +
floor explicit (FD-oracle tangent), stabilised by node drag. New files: `softbox/ExplicitHmmDimer.java`
(struct + forked solver), `softbox/ExplicitHmmDimerHarness.java` (gates), `scripts/run_hmm_dimer.sh`.

## S2-audit result carried into the build (design §2)
`EA=4.2e-9 N, EI=7.2e-28 N·m²` **already represent the PAIRED S2 coiled coil as one effective element**
(Adamovic–Mijailović–Karplus 2008 = the S2-subdomain two-chain unit; code labels it "free S2 coiled
coil"; one beam per motor, no ×2 anywhere). ⇒ the shared paired-S2 region **REUSES EA/EI as-is, NOT
doubled**. Branches start equal to the shared material (task instruction), behind a separate field for
trivial retuning.

## Characterisation — ALL 5 GATES PASS (`./scripts/run_hmm_dimer.sh`)

| gate | result | numbers |
|---|---|---|
| 1 — isometric rest hold | **PASS** | max joint gap **0.000 nm** (bounded, non-growing); rest head force **0.001 pN** (unloaded free-equilibrium, spring rest = xF8); contour drift <0.5 nm |
| 2 — stroke / force generation | **PASS** | head A **−7.64 nm / 7.87 pN**, head B **−7.64 nm / 7.87 pN**; symmetric (rel <1e-3); shared fork axial motion 0.11 nm (both heads pull the shared tail) |
| 3 — single-head equivalence | **PASS** | dimer head **−7.64 nm / 7.87 pN** vs isolated single-head slice **−7.70 nm / 7.92 pN** (same machinery/schedule) — a **~0.8 % stiffer** dimer head, exactly as expected (the shared tail is loaded by both branches at the fork) |
| 4 — shared-tail coupling | **PASS** | stroke **only head A** ⇒ head B induced force **Δ0.105 pN**, pivot **Δ0.101 nm**, via shared-fork motion **0.053 nm** (A stroke → branch A → fork → shared S2 → branch B → head B); vanishes vs the no-stroke baseline |
| 5 — force balance + determinism | **PASS** | Σ internal node forces **1.6e-12 pN** (self-balancing); CPU **bit-reproducible** across independent runs |

## Interpretation (before any retuning)
- The dimer head reproduces the validated single-head working stroke (~−7.7 nm) and peak force (~7.9
  pN) to <1 %, confirming the head physics ported unchanged and the shared tail is a **mild** stiffening
  (both branches share the coiled coil, so each head sees a slightly stiffer effective support than an
  isolated 40 nm tail).
- **Gate 4 is the new physics working**: the two heads are genuinely **mechanically coupled through the
  shared S2** — a load on one head is transmitted to the other through the fork and shared beam. This is
  the HMM behaviour that no prior SoftBox/v1 dimer had (all earlier "dimers" were two independent rods
  coupled by springs; there was no physical shared tail anywhere — archaeology §1).
- The characterisation deliberately used **reused (un-retuned) parameters** throughout. The shared/branch
  material split, the fork rest angle (currently 0), and the splay are the natural first retuning knobs,
  now that the completed dimer is characterised.

## Deferred / next (flagged, not done)
- **GPU / device path** — none yet (like `explicit-s2-l40`, CPU-only). The coupled forked solve is a
  small dense linear system per dimer; a device port mirrors the single-head persistent-SoA slice.
- **MotorModel registry entry** — kept OUT for now (would touch `MotorModel.java`'s exhaustive switches;
  the dimer is a distinct architecture, not a two-body-arc tail fixture). Add once the model is promoted.
- **Gliding / walking** — this characterisation is isometric (fixed actin sites, mirroring the
  single-head slice). Dynamic binding + a real filament + processive walking is the follow-on, reusing
  the same forked solver with per-head `CrossBridgeSystem.bondForces` in place of the fixed-actin spring.
- **Branch softening + fork rest angle** — the branches biologically are more compliant than the paired
  coiled coil; a `branchEA/branchEI` retune + a nonzero fork half-angle are the first physical knobs.
- **Viewer** — two `myosins` entries per dimer + the shared-beam `segments` (the archaeology-identified
  render contract); not wired yet.
