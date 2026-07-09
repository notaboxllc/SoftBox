# IMPLICIT CROSS-BRIDGE — the integrator lever the five force-law/noise failures pointed at

**MEASUREMENT-ONLY / ADDITIVE, flag-gated `-xbimplicit` (default-off ⇒ explicit Hookean, byte-identical).**
Bound-head cross-bridge STRETCH update only; thermal force explicit/untouched; catch-slip formula untouched;
F9/F10 torques explicit; `BoA-v1ref` untouched. Sole occupant of aorus. Instruments: `V2OneXHarness`
(contractile, `-dtconv` signed-load readout) + `GlidingHarness` (`-v1box` NET velocity / avgBound). Raw:
`RUN_LOGS/2026-06-26_xbimplicit_contractile.txt`, `RUN_LOGS/2026-06-26_xbimplicit_gliding.txt`.

## The idea — make the SPRING implicit, leave the NOISE explicit

Five prior levers (softer spring, release-averaging, thermal-correlation, static saturation, explicit dashpot —
`RESEARCH_THESIS §10a`) all tried to fix the stiff cross-bridge from OUTSIDE the integrator, and all five failed:
the deterministic overshoot is inseparable from the real force/thermal fluctuation in the instantaneous domain.
The convergent lesson: **the fix is in the integration.** The cheapest correct form is **implicit integration of
the cross-bridge spring**:

- Explicit overdamped stretch update: `x_{n+1} = (1−r)·x_n`, `r = k·dt/γ` → overshoots past r=1, diverges past r=2.
- **Implicit** (spring evaluated at the NEW position): `x_{n+1} = x_n/(1+r)` → always in (0,1), unconditionally
  stable, never overshoots, for any dt. **No velocity, no finite-difference, no thermal-velocity term** — so it
  sidesteps the dashpot's fatal flaw (the dashpot's anti-thermal `≈γ_xb·√(2D/dt)` force) entirely.

## What was built — the closed-form, one-division locally-implicit step

The myosin head is a Stokes **SPHERE ⇒ ISOTROPIC** translational drag γ_head. With `site` and every other
coupling held EXPLICIT (at x_n), the linearly-implicit overdamped Euler solve of the central cross-bridge spring
`F=k(site−tip)` on the head CENTER collapses to a scalar blend (the `site` term cancels; isotropy makes r scalar):

```
c_imp = (c_exp + r·c_n) / (1 + r),     r = myoSpring·dt·1e6 / γ_head   ( = k·dt/γ )
```

- `c_n` = pre-integration head center (`CrossBridgeSystem.snapshotHeadCenter`, before `integrate(b)`).
- `c_exp` = the EXPLICIT integrator's result — carries the explicit spring + joints + cross-bridge torque + the
  **THERMAL** kick (at its standard explicit `√(2kT/dt)` amplitude), all at x_n.
- `CrossBridgeSystem.implicitCorrect` applies the blend to bound heads only, AFTER `integrate(b)`, BEFORE `derive`.

**Structural placement:** it modifies the bound head's TRANSLATIONAL update (post-integration position correction),
not the force accumulation. The shared `RigidRodLangevinIntegrationSystem` is **byte-unchanged** (no entity-specific
leak into the shared integrator). Device-agnostic (one closed-form scalar op per bound head; no atomics/KernelContext).
Wired into `V2OneXHarness` (CPU + the 5-graph GPU split, in `fdInteg`) and `GlidingHarness` (CPU + the GPU graph).

**Why this ≠ the dashpot (which failed):** no velocity is computed. The `(1+r)` denominator is the spring's own
resistance to ALL motion this step — the correct implicit behaviour. The thermal force enters `c_exp` explicitly at
the FDT amplitude (NOT damped at source, NOT cooled); the spring resists it, exactly as a stiff bond should.

**The thermal consequence (derived, then measured).** Because the blend divides the WHOLE displacement (drive +
thermal) by (1+r), the discrete stretch is an AR(1) with `a_i=1/(1+r)` and noise `σ/(1+r)`, giving stationary
variance `σ²/(r(2+r))` vs explicit `σ²/(r(2−r))`. So implicit runs `(2−r)/(2+r)≈0.58×` COLDER at 1e-5: explicit
OVER-heats the stretch by `1/(1−r/2)`, implicit UNDER-heats by `1/(1+r/2)` — they BRACKET the continuum `kT/k`,
the same way `1/(1+r)=0.65` and `1−r=0.47` bracket the true `e^{−r}=0.59`. Implicit's colder stretch ⇒ fewer
spurious negative-load excursions ⇒ less catch-exponential detonation ⇒ binding preserved at coarse dt.

## TL;DR verdict — STABLE-BUT-UNFAITHFUL, with a real sub-order-of-magnitude benefit

The cheap locally-implicit cross-bridge spring is **unconditionally stable at every dt** (by construction) and
**removes the explicit overshoot detonation**, but it **does NOT buy the order-of-magnitude faithful dt** that was
hoped for. Concretely:

- **Stage 1 WIN (at 1e-5):** implicit reproduces the fine-dt converged reference **markedly better than explicit** —
  on the bound count (727 vs 400 toward the converged ~1050), the cross-bridge tension (3.50 vs 4.68 toward 2.8 pN),
  AND the signed-load **negative tail** the catch reads (p10 = −2.73 pN vs explicit −4.13, fine-dt −2.21). It
  roughly **halves the 1e-5 dt-error** and recovers the distribution SHAPE — exactly the predicted mechanism (kill
  the head's stretch overshoot ⇒ narrow the spurious negative-load excursions the catch exponential detonates on).
- **Stage 2 STABLE-BUT-UNFAITHFUL (at ≥2e-5):** implicit is perfectly stable (bounded, no NaN) but binding **still
  collapses** — bound 42 / 23 / 20 at 2e-5 / 5e-5 / 1e-4 vs the converged ~1050, only ~2× better than explicit at
  each dt, both ≪ converged. The signed-load tail blows out (p10 → −7…−10 pN). **Stability ≠ accuracy:** the big
  `1/(1+r)=0.16` implicit steps don't resolve the load fluctuations the catch needs.
- **WHY (the residual is MULTI-SOURCE, only one source is the head):** head-only implicit removes the HEAD's stretch
  overshoot, but the load the catch reads (`forceDotFil`) is inflated ∝ dt by **three** sources — (a) the explicit
  **SITE motion** (the segment + chain under collective load — the operator-split error), (b) the start-of-step
  force **STALENESS**, (c) thermal under-resolution. Head-implicit fixes only (a-for-the-head). The residual
  `fmgMean` at 2e-5 (7.0 pN, still 2.5× the converged 2.8 despite the implicit head) is the signature that the head
  is no longer the dominant error above 1e-5.
- **The split error MATTERS — proven by the gliding↔contractile contrast** (not by a reduced-pair A/B, which is the
  flagged next step). In **contractile** (dense, slow sites) implicit@1e-5 recovers **69%** of the converged binding;
  in **gliding** (one fast-GLIDING filament = a fast site) implicit@1e-5 recovers ~58% AND collapses at 2e-5
  *identically to explicit*. The benefit is scene-dependent precisely because head-implicit/site-explicit only works
  when the site is slow.

**So:** the integrator IS the right place (stable, overshoot-free, distribution recovered at 1e-5) — but the CHEAP
local form is insufficient for a large faithful dt. The headroom needs the residual sources attacked: the
**fully-coupled implicit solve** (head+site+chain together — Cytosim's route) and/or **sub-stepping the
cross-bridge+release inner loop**. The reduced-pair (head+site) is the intermediate lever (flagged, NOT built).

## STAGE 0 — the fine-dt reference (the converged target)

**0.5× dt-study scene** (box 5.0, 200 nodes × 24 singlets, 500 fil × 10 seg, crosslinkers ON, aeta 0.1, 12 pN cap
ON), GPU device-resident, matched SIM-TIME. EXPLICIT at fine dt (the spring then relaxes faithfully, r→0):

| scheme/dt | bound (simT 0.10) | fmgMean (pN) | signed-load p10 / p90 (pN) | fracNeg | offRateCS |
|---|---|---|---|---|---|
| **explicit 1e-6 (REF)** | **~1050** | **~2.8** | **−2.21 / 2.77** | **0.45** | **~165–180** |
| explicit 2e-6 (conv check) | ~1093 | ~2.8 | ≈ ref | ~0.45 | ~170 |
| explicit 5e-7 | ~970 | ~2.8 | ≈ ref | ~0.45 | ~170 |

(The 1e-6 reference is still rising slowly at simT 0.10 — bound 890@0.06 → ~1050; the prior `dt_below2` sweep
confirms the ~1050 plateau. The **signed-load distribution is stationary from simT 0.03**: p10 −2.2, p90 2.6–2.8,
fracNeg 0.45–0.48, meanNeg −1.45.) **Note the converged operating point binds ~2.5× MORE than the explicit-1e-5
calibration** (the v1-matched 1e-5 point is itself far from the dt→0 fixed point — the `DT_CONVERGENCE` finding).

## STAGE 1 — implicit at dt=1e-5 reproduces the fine-dt reference better than explicit

At 1e-5, implicit `1/(1+r)=0.65` and explicit `1−r=0.47` bracket the true `e^{−r}=0.59` (r=0.531) — so implicit
will NOT match the *explicit*-1e-5 motor; the correct reference is fine-dt. Measured (contractile, simT 0.10):

| metric | fine-dt REF (1e-6) | explicit 1e-5 | **implicit 1e-5** | implicit verdict |
|---|---|---|---|---|
| bound count | ~1050 | 400 (38%) | **727 (69%)** | halves the error |
| fmgMean (pN) | 2.8 | 4.68 | **3.50** | toward converged |
| signed-load p10 (neg tail, pN) | −2.21 | −4.13 | **−2.73** | tail nearly recovered |
| signed-load p90 (pN) | 2.77 | 4.10 | **3.21** | toward converged |
| meanNeg (pN) | −1.45 | −2.54 | **−1.82** | toward converged |
| catch-slip off-rate (/s) | ~170 | 427 | **259** | toward converged |

**Implicit@1e-5 is decisively more faithful than explicit@1e-5** on every channel — bound count, force magnitude,
and the negative-load TAIL the catch exponential reads. The narrowing is exactly the AR(1) variance prediction
(`σ²/(r(2+r))` vs explicit `σ²/(r(2−r))`, 0.58× at r=0.53): implicit's colder, overshoot-free stretch produces fewer
spurious negative excursions ⇒ less catch detonation ⇒ binding preserved. **Gliding (CPU, v1box) confirms:**
avgBound 11.7 (implicit) vs 7.1 (explicit) vs ~20 fine-dt — same direction, ~1.6× recovery.

**Caveat (operating-point shift):** implicit@1e-5 (727) ≠ explicit@1e-5 (400) — adopting implicit re-baselines the
v1 calibration (which was tuned at explicit 1e-5). Implicit is more *converged* but breaks the existing calibration.

## STAGE 2 — implicit at 1e-4: STABLE, but UNFAITHFUL (the crux)

Implicit is stable at 1e-4 by construction (`1/(1+5.3)=0.16`, bounded, no detonation). The open question — faithful,
or stable-but-unfaithful? **Answer: stable-but-unfaithful.** Contractile dt sweep (GPU, matched simT 0.10):

| dt | EXPL bound | IMPL bound | EXPL fmg | IMPL fmg | EXPL p10 | IMPL p10 | EXPL fracOverCap | IMPL fracOverCap |
|---|---|---|---|---|---|---|---|---|
| 1e-5 | 400 | **727** | 4.68 | 3.50 | −4.13 | −2.73 | 0.000 | 0.000 |
| 2e-5 | 20 | 42 | 9.87 | 7.01 | −6.7 | −7.0 | 0.30 | **0.048** |
| 5e-5 | 8 | 23 | 11.9 | 13.1 | −7.6 | −10.2 | 0.50 | 0.61 |
| 1e-4 | 11 | 20 | 25.9 | 11.2 | −8.7 | −10.2 | — | 0.35 |
| **fine ref** | **1050** | **1050** | **2.8** | **2.8** | **−2.2** | **−2.2** | **0.00** | **0.00** |

- **Binding collapses for BOTH** above 1e-5: implicit holds ~2× more bound than explicit at every coarse dt, but
  both are 25–50× below the converged 1050. Implicit is **stable but does not reproduce the fine-dt reference** at
  1e-4 (bound 20 vs 1050; signed-load p10 −10.2 vs −2.2 — a blown-out negative tail).
- **The overshoot IS suppressed** (implicit fracOverCap 0.048 vs explicit 0.30 at 2e-5; implicit fmgMax 37 vs the
  explicit-cap-off runaway) — so the *mechanism* works; it just isn't sufficient. The residual `fmgMean` 7.0 pN at
  2e-5 (vs converged 2.8) is the explicit **site + staleness** load the head-implicit cannot reach.

### Largest faithful dt (the bracket)

bound count vs the converged ~1050 (contractile, simT 0.10), implicit vs explicit across the fine→coarse transition:

| dt | EXPL bound (% of conv) | IMPL bound (% of conv) |
|---|---|---|
| 2e-6 | ~1093 (conv) | ~1050 (conv) |
| 5e-6 | 915 (87%) | **1024 (97%)** |
| 1e-5 | 400 (38%) | 727 (69%) |
| 1.5e-5 | — | ~260 (25%) |
| 2e-5 | 20 (2%) | 42 (4%) |

**Largest faithful dt (within ~3–5% of the converged 1050): implicit ≈ 5e-6, explicit ≈ 2–3e-6 — implicit buys
roughly 2×.** Both schemes converge to the same fine-dt motor (implicit→explicit as r→0; IMPL@2e-6 ≈ EXPL fine-dt,
the consistency check). The implicit advantage is largest in the 5e-6–1e-5 band (97%/69% vs 87%/38%) and gone by
2e-5 (both collapsed). **This is a ~2× finer-dt-equivalent at the converged level, NOT the order-of-magnitude outer
dt the cheap integrator lever was hoped to buy** — consistent with the multi-source residual above.

### The operator-split error — head-implicit/site-explicit, and why it caps the benefit

The cheap first cut holds the **site explicit** during the head's implicit stretch step (an O(dt) coupling error at
the head↔site boundary). Evidence that this is load-bearing above 1e-5:
1. **gliding vs contractile:** the gliding site (a single filament that GLIDES fast) makes the split error large —
   implicit@1e-5 recovers less (58% vs contractile's 69%) and collapses at 2e-5 *exactly like explicit*. The
   contractile sites (dense network, slow) make it small — implicit helps most there. The split error is therefore
   **real and scene-dependent**, scaling with how fast the site moves between force evaluations.
2. **the residual force:** at 2e-5 the head is implicit yet `fmgMean` is still 7.0 pN (2.5× converged) — the head is
   no longer the dominant dt-error; the explicit site + start-of-step staleness are.

**The reduced-pair refinement was NOT built.** It would relax the *reduced* stretch coordinate (mobility
`γ_red=γ_h·γ_s/(γ_h+γ_s)`, here ~0.6·γ_h since γ_seg≈2–3×γ_head ⇒ r_red≈1.6×r_head — a non-trivial change) and
distribute the implicit relaxation to head AND site by mobility fraction. Correcting the site needs the
segment→bound-motors gather (multiple motors/segment, race-free) — real machinery. It is flagged as the intermediate
lever, but by the analysis it addresses only source (a) (pair compliance), not (b) staleness or the *collective*
site motion (the site moves under MANY motors + the chain, not its single-bond mobility) — so it cannot by itself
rescue the ≥2e-5 collapse. The decisive levers are the **fully-coupled implicit solve** and **release sub-stepping**.

## CPU≡GPU + stability

- **Stability:** implicit is unconditionally stable at every dt tested (1e-6 → 1e-4) — bounded, finite, no
  wall-escape, by construction (`1/(1+r)∈(0,1)` for any r). fmgMax stays bounded (37 pN at 1e-4 vs the explicit
  cap-off runaway 84–103 pN).
- **CPU≡GPU:** the implicit kernels (`snapshotHeadCenter`, `implicitCorrect`) are pure closed-form scalar ops
  (no atomics/KernelContext) and lower identically on both runners. On the deterministic (`-brownoff`) cross-check
  the implicit-ON CPU↔GPU divergence (6.2e-3 µm over 30 steps, noxlink) **equals the implicit-OFF baseline**
  (6.6e-3 µm) — implicit adds **no** new CPU/GPU disagreement; the residual is the pre-existing chaotic float32
  decorrelation of this many-body scene (the CLAUDE aggregate-within-SEM standard). Contractile results above are
  GPU; gliding results are CPU — consistent across runners.
- **Default byte-identity:** all wiring is `if (XB_IMPLICIT)`-gated; with `-xbimplicit` unset, no kernel runs, no
  buffer joins any graph ⇒ byte-identical to the committed benchmark. `BoA-v1ref` untouched.

## Verdict — banked for future selves

**The integrator is the right place, but the cheap local form is not enough.** Implicit cross-bridge integration is
unconditionally stable, kills the overshoot detonation, and at 1e-5 halves the dt-error and recovers the signed-load
negative tail (a clean Stage-1 win over explicit). But it is **stable-but-unfaithful above ~1e-5**: binding still
collapses by 2e-5 because the catch reads a load inflated by the **explicit site + start-of-step staleness + thermal
under-resolution**, of which head-only implicit fixes only the head's share. **No order-of-magnitude faithful dt is
bought** — implicit buys roughly a 2–3× finer-dt-equivalent at the converged level, and ~2× more binding at every
coarse dt, not a 10× outer-dt increase.

**Where the cross-bridge dt headroom actually lives (for a future self):**
1. **Fully-coupled implicit solve** (head + site + chain advanced together) — attacks the operator-split (site)
   residual the cheap form leaves. This is Cytosim's implicit-integration + stiff-bond-as-constraint route,
   re-derived here from the bottom up (cf. `RESEARCH_THESIS §10b`).
2. **Sub-step the cross-bridge + nucleotide-release inner loop** at ~1e-5 while the rest advances at a larger outer
   dt — attacks the temporal-resolution (the catch needs the fast load fluctuations) AND the staleness residual.
3. **Reduced-pair (head+site) implicit** — the intermediate, needs the seg-gather; addresses pair compliance only.

We may stay at explicit 1e-5 regardless — this task established *what the cheap integrator lever does and doesn't
buy*, so the next attempt starts from the coupled solve / sub-step, not another local closed-form.

## Reproduce
```
./run_xbimplicit.sh ref     # fine-dt EXPLICIT reference (contractile, GPU) — bound/signed-load converged target
./run_xbimplicit.sh sweep   # IMPL + EXPL dt sweep {1e-5,2e-5,5e-5,1e-4} (contractile, GPU)
./run_xbimplicit_gliding.sh # gliding wing (v1box, CPU): fine-dt ref + IMPL/EXPL sweep (NET velocity + avgBound)
./run_1x.sh -gpu -xbimplicit -dtconv -nodes 200 -nfil 500 -box 5.0 -dt 1e-5 -seed 1 -steps 10000   # one implicit point
./run_gliding.sh -v1box -xbimplicit 5000     # one gliding implicit point
```
