# Vmax calibration STAGE 1 — which eligible parameters move V₀? (clamp sensitivity screen)

**Date:** 2026-07-11 · **Branch:** dt-convergence-study · **Runner:** CPU (deterministic arbiter), `-vclamp`,
`-matbox 50`, d1000 (V₀ density-independent per `FORCE_BALANCE_CLOSURE.md` — ~2× faster). **Measurement/screen
only — no candidate sets, no attempt to hit ~4.2, no mechanism build.** Flag-gated overrides default-off
byte-identical. `BoA-v1ref` untouched. Builds on STEP 0 (`MOTOR_PARAMETER_PROVENANCE_25C.md` eligibility;
`GLIDING_TARGET_25C.md` target ~4.2).

> **VERDICT — the CATCH-SLIP SHAPE is the dominant V₀ lever; stroke size is NOT.** 2-seed pooled log-sensitivities
> `S = Δln V₀/Δln p`: **xCatch (0.69) ≫ αCatch (0.41)** [robust top tier] » a **seed-scattered mid-cluster** ADP-
> release (0.28) ≈ Pi-release (0.25) ≈ **neck-angle/stroke (0.20)** ≈ myoSpring (0.17) ≈ ATP-detach (0.16) » **clean
> nulls** αSlip (0.02) ≈ xSlip (−0.02) ≈ **kOff (0.00)**. **Three findings that survive 2 seeds:** (1) **the catch-slip
> SHAPE (xCatch, αCatch) dominates V₀** — robustly #1/#2 both seeds — i.e. the ceiling is **release-kinetics-limited**;
> **stroke size (neck angle) is only mid-cluster (S≈0.20), NOT the dominant lever**, refuting the naïve V≈d/τ_on
> "stroke is the prime lever" — and it is PINNED-bound besides (below). (2) **kOff has ZERO V₀ effect (S=0.00 both
> seeds)** — the clean amplitude null (scales both catch+slip ⇒ moves the detach rate, not the zero). (3) The
> **myoSpring "control" is NOT null (S=0.17 both seeds)** — cross-bridge stiffness couples mildly into the zero
> (3-body geometry + load-dependent release, the audit's H5). **To lower V₀ from ~12–16 into the ~4–8 band the
> eligible levers are (a) slow ADP release (~3× → V₀≈8 both seeds, keeps the stroke PINNED — the cleanest) and/or
> (b) lower the catch-slip shape xCatch/αCatch (strongest movers, but Guo&Guilford-measured catch-bond constants).**
> **Caveat: the mid-cluster (S≈0.15–0.28) is within seed noise — its internal order needs Stage-2 precision.** Stage
> 2 builds coherent candidate sets on the RELEASE kinetics, not the stroke.

---

## Method

- **Clamp V₀ readout:** velocity clamp (`-vclamp`), `f̄_available(v)` primary (unbound=0, fixed occupancy
  denominator), CPU, `-matbox 50`, **d1000**. Adaptive v-grid **{0, 4, 8, 12, 16, 20} µm/s** (brackets a moving zero
  from a few µm/s to >20). 8000 steps, **seed 0** (screen-level; seed-1 confirmation below). V₀ = the linear-
  interpolated zero-crossing of f̄_available(v).
- **One-at-a-time:** each eligible (non-PINNED) parameter perturbed **low / high** within its documented bound, all
  others at baseline. Baseline V₀ measured once. **Overrides are new measurement-only flags** (`-adprate`, `-pirate`,
  `-atpdetrate`, `-koff`, `-acatch`, `-aslip`, `-xslip`; plus existing `-neckangle`, `-myospring`, `-xcatch`),
  default 0/−1 ⇒ **byte-identical** (verified: a no-flag clamp run is unchanged; only additive gated branches).
- **PINNED never varied:** unitary force ~5 pN, emergent stroke 5–8 nm (a validation *bound* on neck-angle, not a
  knob), kT = 25 °C. Clamp V₀ and free-gliding v* stay separate observables (no V₀/1.4).

## TABLE — V₀ sensitivity (baseline V₀ ≈ 12.4 µm/s, d1000 seed 0), ranked by |S|

| rank | parameter (class) | slot | p_lo → V₀_lo | p_hi → V₀_hi | **S = Δln V₀/Δln p** | reading |
|---:|---|---|---|---|---:|---|
| 1 | **xCatch** (TRANSFERRED) | kin[3] | 1 nm → **5.72** | 5 nm → **19.30** | **+0.76** | catch force-sensitivity — **the dominant V₀ lever**; ↓xCatch ⇒ ↓V₀ |
| 2 | **αCatch** (TRANSFERRED) | kin[1] | 0.46 → 10.40 | 1.0 → 13.30 | **+0.32** | catch weight; ↓ ⇒ ↓V₀ |
| 3 | **ADP release** (TRANSFERRED) | nuc[6/7] | 300/s → **7.93** | 3000/s → **16.17** | **+0.31** | the τ_on/velocity-limiter; **slow it ⇒ ↓V₀** (into the band) |
| 4 | ATP-detach (TRANSFERRED) | nuc[1] | 5e3 → 10.07 | 5e4 → 15.61 | +0.19 | faster detach ⇒ ↑V₀ |
| 5 | **myoSpring** (BOUNDED, **CONTROL**) | — | 0.3 → 10.07 | 2.0 → 13.38 | **+0.15** | **NOT null** — stiffness couples mildly into V₀ (H5) |
| 6 | **neck-angle → stroke** (ASSUMED) | swing[3] | 40° → 11.44 | 80° → 12.66 | **+0.15** | **stroke is a WEAK lever**; and constrained (see below) |
| 7 | Pi-release (TRANSFERRED) | nuc[4] | 3e3 → 9.41 | 3e4 → 12.90 | +0.14 | pre-stroke timing; weak |
| 8 | αSlip (TRANSFERRED) | kin[2] | 0.032 → 11.95 | 0.40 → 13.65 | +0.05 | negligible at these loads |
| 9 | xSlip (TRANSFERRED) | kin[4] | 0.16 nm → 12.40 | 2.0 nm → 11.73 | −0.02 | negligible |
| 10 | **kOff** (TRANSFERRED) | kin[0] | 30/s → 12.40 | 300/s → 12.40 | **0.00** | **pure amplitude — ZERO V₀ effect** (the clean null) |

*(Screen-level single-seed at d1000; baseline V₀≈12.4 here vs the bootstrapped ~16 at d2000 — the absolute offset is
single-seed + coarse-grid + density; the **relative ranking** is the deliverable. A seed-1 confirmation run is
appended below.)*

### Seed-1 confirmation + 2-seed pooled S (robustness)

Seed-1 baseline V₀ = 15.19 (vs seed-0 12.40 — single-seed scatter ±~1.4 on the absolute V₀; the ranking, not the
absolute, is the deliverable). Pooled S = mean of the two seeds:

| parameter | S(seed0) | S(seed1) | **S(pooled)** | robustness |
|---|---:|---:|---:|---|
| **xCatch** | 0.76 | 0.62 | **0.69** | **ROBUST #1** |
| **αCatch** | 0.32 | 0.50 | **0.41** | **ROBUST #2** |
| ADP-release | 0.31 | 0.24 | **0.28** | mid-cluster; direction robust (V₀_lo≈8 both) |
| Pi-release | 0.14 | 0.35 | **0.25** | mid-cluster (seed-scattered) |
| neck-angle/stroke | 0.15 | 0.24 | **0.20** | mid-cluster (seed-scattered) |
| myoSpring (control) | 0.15 | 0.18 | **0.17** | **ROBUST non-null** |
| ATP-detach | 0.19 | 0.12 | **0.16** | mid-cluster |
| αSlip | 0.05 | −0.00 | **0.02** | **ROBUST ≈null** |
| xSlip | −0.02 | −0.01 | **−0.02** | **ROBUST ≈null** |
| **kOff** | 0.00 | 0.00 | **0.00** | **ROBUST null (amplitude)** |

**What survives 2 seeds:** (i) **catch-slip SHAPE (xCatch ≫ αCatch) is the DOMINANT lever** — robustly #1/#2; (ii)
**kOff / xSlip / αSlip are clean ≈nulls**; (iii) **myoSpring is a mild non-null** (H5). **What does NOT cleanly
rank-order at screen precision:** the **mid-cluster S≈0.15–0.28 — ADP-release, Pi-release, neck-angle/stroke,
myoSpring, ATP-detach — are within seed noise of one another** (e.g. Pi-release 0.14↔0.35, neck-angle 0.15↔0.24). So
the honest statement is **tiered, not a strict order**: catch-slip shape (top) » a moderate mixed cluster of
kinetic-timing + stroke » amplitude nulls. **Stage-2 precision (bootstrapped V₀, more seeds) is needed to order
within the mid-cluster.**


## Shortlist — knobs that materially move V₀ toward the band, with constraints

To bring V₀ from ~12–16 down toward the biological gliding band (~4–8 µm/s), ranked by usefulness (leverage × how
cleanly the eligible bound permits it, and whether it collaterally breaks a PINNED/other observable):

1. **ADP-release rate ↓ (nuc[6/7], S=0.31)** — the cleanest lever. Slowing ~3× (1000→300/s) lands V₀≈7.9, top of the
   band, and **keeps the stroke PINNED** (untouched) and the catch-bond shape intact. Caveat: 300/s is *sub*-skeletal
   (skeletal ADP release ~500–1000/s), i.e. it drifts the isoform toward smooth/NMII — a provenance cost to weigh in
   Stage 2, but it maps directly to the d/τ_on picture and touches no PINNED quantity.
2. **xCatch ↓ (kin[3], S=0.76)** — the strongest mover (1 nm → V₀ 5.7). But xCatch=2.5 nm is a **Guo&Guilford-measured
   catch distance**; lowering it reshapes the catch-bond lifetime-vs-load curve (a biophysical observable), so it is a
   powerful but provenance-expensive knob — flag for Stage 2, not a free ride.
3. **αCatch ↓ (kin[1], S=0.32)** — modest, same catch-bond-shape provenance caveat as xCatch.

**NOT on the shortlist:** neck-angle/stroke (weak AND stroke-bound, below); kOff (no V₀ effect); αSlip/xSlip/Pi-release
(negligible); ATP-detach (moderate but faster-detach *raises* V₀, wrong direction, and it is already fast).

## Stiffness control result (the falsifiable prediction)

**Predicted null (f̄ = k·g(v) ⇒ k scales amplitude, not the zero g(V₀)=0): REFUTED — mildly.** `myoSpring` gave
**S=0.15** (V₀ 10.07 at 0.3 pN/nm → 13.38 at 2.0 pN/nm): softer cross-bridge ⇒ lower V₀. So stiffness is **not a clean
amplitude knob** — the three-body geometry + load-dependent (signed catch-slip) release couples `k` into the force
zero, exactly the audit's H5 caveat ("measure, don't assume"). It is a *mild* real coupling (rank 5, tied with
neck-angle), not a primary lever. **The genuinely null amplitude knob is `kOff` (S=0.00)** — it scales both catch and
slip terms equally, moving the overall detach *rate* but not the *shape*/zero, so it changes occupancy/amplitude with
no effect on V₀ (consistent with `COLTOL_REGIME_SWEEP.md`/`GLIDEKON_CROSSOVER.md`: pool/amplitude knobs don't move the
ceiling).

## Neck-angle → emergent stroke (the PINNED-stroke constraint)

Emergent stroke estimated geometrically and anchored to the measured 7 nm at 60° (directedSwing: stroke ≈
HEAD_LEN·sin(θ/2)·0.7 = 14·sin(θ/2) nm; HEAD_LEN=20 nm):

| neck angle | est. emergent stroke | in 5–8 nm bound? | V₀ |
|---:|---:|:--:|---:|
| 40° | ~4.8 nm | **NO (below 5)** | 11.44 |
| 60° (base) | ~7.0 nm (measured anchor) | yes | ~12.4 |
| 80° | ~9.0 nm | **NO (above 8)** | 12.66 |

**In-bound neck-angle window ≈ 43–70° (stroke 5–8 nm).** Two constraints jointly demote stroke: (1) **it is
PINNED-bound** — leaving 43–70° violates the 5–8 nm validation stroke; (2) **it is only a mid-cluster V₀ lever**
(pooled S≈0.20, below the dominant catch-slip shape and within seed noise of the kinetic-timing steps), even
evaluated at the out-of-bound extremes (V₀ moves ~11–16 across 40°→80° depending on seed). **So stroke-via-neck-angle
is neither the dominant lever nor a *free* one** — Stage 2 should not pursue stroke size as the Vmax lever. *(The
stroke estimate is geometric-anchored to the measured 7 nm at 60°; a precise per-angle stroke measurement is a
low-priority Stage-2 refinement given the mid-cluster S.)*

## Plain bottom line

**Which non-PINNED knob(s) can bring V₀ from ~12–16 toward ~4–8, and is stroke-size or kinetic-timing the dominant
lever?** **RELEASE dynamics dominate, not stroke size.** The one robust top tier (both seeds) is the **catch-slip
SHAPE (xCatch S≈0.69, αCatch ≈0.41)**; **stroke-via-neck-angle is only mid-cluster (S≈0.20) AND PINNED-bound** — not
the dominant lever, refuting "make the stroke smaller to lower Vmax"; **kOff has zero V₀ effect** (amplitude null) and
**myoSpring is a mild non-null (H5, small)**. The **cleanest eligible path** to lower V₀ into the band is **slowing
ADP release ~3× (→ V₀≈8 both seeds, stroke untouched)** — it maps directly to d/τ_on and touches no PINNED quantity,
though it drifts sub-skeletal; **xCatch/αCatch move V₀ more but are Guo&Guilford-measured catch-bond constants**
(provenance-expensive). **⇒ Stage 2 builds coherent candidate sets on the release kinetics — a slower ADP-release
clock ± a re-shaped catch — NOT the stroke**, adjudicated against the ~4.2 µm/s target at a deliberately chosen ionic
strength and re-confirmed with the bootstrapped V₀ + free-glide check. (Screen caveat: single-seed-class precision;
the mid-cluster order needs Stage-2 seeds/bootstrap.)

## Commands
```
scripts/run_vmax_sens.sh                                   # the full one-at-a-time screen (seed 0)
scripts/run_gliding.sh -matbox 50 -density 1000 -vclamp 8 -adprate 300 8000   # a single perturbed clamp point
```
Raw: `RUN_LOGS/2026-07-11_vmax_sens_seed0.txt` (+ `_seed1.txt`). New flags `-adprate/-pirate/-atpdetrate/-koff/
-acatch/-aslip/-xslip` (measurement-only, default-off byte-identical); reused `-neckangle/-myospring/-xcatch`.
</content>
