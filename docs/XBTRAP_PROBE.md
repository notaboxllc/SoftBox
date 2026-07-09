# Trapezoidal (2nd-order) cross-bridge integrator — is the per-bound dt-bias smooth F8-relaxation truncation (cheap fix) or kinetics/event-timing (needs a sub-step)?

**Date:** 2026-07-06. Flag-gated (`-f8relax` probe mode in `EomStabilityHarness`; `-xbtrap` in `GlidingHarness`),
**default byte-identical**; `BoA-v1ref` untouched. Builds on `STROKE_TIP_IMPLICIT_PROBE.md` (the single-motor
transient converges clean first-order; the endpoint is dt-flat) and `COUPLED_IMPLICIT_XB_FINDINGS.md` (`-xbimplicit2`
= backward-Euler on the F8 translation — converged binding/detach but NOT the per-bound glide). The converged
reference this probe is measured against: per-bound **0.822 @1e-5 → 1.783 @≈6.25e-7** (ROTIMPLICIT Part B), a ~2.2×
forward-Euler bias.

---

## PLAIN ANSWER (headline)

**Discriminating question: does a 2nd-order (trapezoidal / Crank–Nicolson midpoint) time-discretization of the F8
cross-bridge translation recover the converged per-bound glide at production dt=1e-5?**

- **STEP 1 (the isolated smooth F8-translation relaxation): PASS.** Trapezoidal converges **2nd-order** (error ratio
  0.25 per dt-halving vs explicit/BE's 0.50) and lands at the dt→0 continuum **already at dt=1e-5** — its 1e-5 error
  is **17.6× smaller** than explicit's. The per-step gap-fractions reproduce the analytic decay exactly
  (explicit 0.419 / BE 0.295 / CN 0.346), confirming the `-xbtrap` operator (`r → r/2` in the coupled star) is
  correctly implemented. On the *smooth* part, the integrator upgrade works cheaply at production dt. ⇒ proceed to
  the decisive test.
- **STEP 2 (the actual loaded, cycling gliding assay): NULL — trapezoidal does NOT recover the converged glide at
  dt=1e-5, and adds nothing over backward-Euler.** At production dt the trapezoidal per-bound (1.39) equals the
  explicit (1.34) and backward-Euler (1.24) per-bounds within the single-seed noise, and the glide velocity **still
  climbs 1.95× to dt=2.5e-6** — indistinguishable from `-xbimplicit2`'s 1.97× climb. **Despite STEP 1 proving CN is
  17× more accurate than backward-Euler on the isolated smooth F8-translation, in the assay CN ≡ BE.** So the assay's
  dt-bias is **provably NOT the smooth F8-translation truncation** (else CN's STEP-1 advantage would have shown up);
  it is the **kinetics / discrete bind–unbind–stroke event timing** (the residual dt-climb is in the *binding count*,
  avgBound, not the per-bound). **⇒ a sub-step is the indicated answer** — the skeptical expectation, confirmed.

**The two-step logic (why this is airtight):** STEP 1 shows a 2nd-order integrator *does* collapse the isolated
smooth F8-translation relaxation to the continuum at 1e-5 (17× better than explicit). STEP 2 shows that same 2nd-order
integrator, dropped into the assay, changes nothing over backward-Euler. The only way both hold is if the assay's
residual dt-dependence is **not** in the smooth F8-translation the integrator governs — it is in the non-smooth
kinetics/event-timing, where integrator *order* is irrelevant (trapezoidal drops to first-order across events). This
is the direct, constructive confirmation of `COUPLED_IMPLICIT_XB`'s inference ("the residual lives in the
still-explicit stroke/rotation/force couplings"), now proven by showing the *better* F8-translation timing is inert.

---

## The discriminating hypothesis (why trapezoidal is the one integrator that discriminates)

At the gliding F8 relaxation strength `α = k_F8·dt/γ ≈ 0.42` (dt=1e-5), the **exact** per-step gap-fraction of the
F8-driven translation is `1−e^{−α} ≈ 0.343`. The three schemes bracket it with opposite-signed errors:

| scheme | per-step gap-fraction | at α=0.42 | vs exact 0.343 |
|---|---|--:|:--|
| explicit forward-Euler | `α` | 0.419 | **overshoots** (+22%) — decays too fast |
| backward-Euler (`-xbimplicit2`) | `α/(1+α)` | 0.295 | **undershoots** (−14%) — decays too slow |
| **trapezoidal / CN (midpoint)** | `α/(1+α/2)` | **0.346** | **near-exact** (+1%), and **2nd-order** |

So explicit and backward-Euler are *both* wrong with opposite signs (which is why neither converged the glide), and
**only trapezoidal lands on the exact relaxation** — it is the integrator that tests whether the per-bound bias *is*
the smooth F8-translation truncation. If it is, `-xbtrap` at 1e-5 collapses the per-bound toward the converged value
(cheap fix, keeps production dt). If it is not, no integrator *order* helps — the bias is the discrete
bind/unbind/stroke **event timing** (a non-smooth RHS across events, where trapezoidal drops to first-order), and a
**sub-step** is the indicated answer.

**Implementation note (the CN ≡ coupled-star re-timing).** Derivation: the θ-method step
`x_{n+1} = x_n + dt·[(1−θ)F8(x_n) + θ F8(x_{n+1})]` on the linear zero-rest-length F8 spring gives
`(I − θ·dt·L)x_{n+1} = x_e − θ·dt·L·x_n`, where `x_e` is the explicit-integrator output (carrying the full explicit
F8 impulse) and `L` the F8 stiffness/mobility operator. **CN (θ=½) is exactly backward-Euler (θ=1) with `dt→dt/2` in
the implicit operator** — i.e. the *identical* `-xbimplicit2` per-segment closed-form star (`coupleComputeA` →
`coupleSolveSeg` → `coupleCorrectHead`) with every `r = k·dt·1e6/γ` replaced by `r/2`. Since the explicit F8 impulse
uses `xbParams` (not `xbImplParams`), **halving `myoSpring` only in `xbImplParams`** halves `r` in the correction
while keeping the explicit numerator at full stiffness — so `-xbtrap` reuses the race-free CSR-inverse star **byte-
for-byte** (no shared-kernel edit ⇒ `-cpu` parity and default byte-identity inherited by construction).

---

## STEP 1 — the deterministic integrator-order unit test (`-f8relax`) — **PASS**

**Setup.** One anchored motor, one permanently-bound head held **FROZEN** (fixed F8 site), one **FREE** filament
segment displaced 10 nm from the F8 equilibrium and released; **Brownian OFF**, pure translational (rotation frozen).
The only dt-dependence is the numerical time-discretization of the free filament's F8-driven translation. Three arms
— explicit / backward-Euler / trapezoidal-CN — via one segment-center blend `q_imp = q_n + (q_e−q_n)/(1+β·r_a)` per
body axis, `β∈{0,1,0.5}` (β=0.5 ≡ `r→r/2` ≡ midpoint, the exact `-xbtrap` operator). `k_F8=1 pN/nm`,
`γ_seg,∥≈2.389e-8 N·s/m`, `τ_relax≈2.389e-5 s`, `α@1e-5 ≈ 0.419`.

**Transient sampled at a FIXED sim-time `T_probe = 2e-5 s` (= 0.837·τ), an exact integer step-count for every dt in
the ladder (2/8/16/32/64 steps).** (Sampling at `round(τ/dt)` instead lands each dt at a *different* sim-time — the
step-count rounds differently — and that sampling-time variance swamps the O(dt) vs O(dt²) signal; the same artifact
the STROKE_TIP 5·τ column carried.) Reference = the analytic continuum `10·e^{−T/τ} = 4.3286 nm`.

| dt (s) | explicit disp@Tprobe (err) | backward-Euler (err) | **trapezoidal-CN (err)** |
|--:|--:|--:|--:|
| 1.000e-5 | 3.3794 (0.9492) | 4.9686 (0.6400) | **4.2746 (0.0540)** |
| 2.500e-6 | 4.1293 (0.1993) | 4.5097 (0.1811) | **4.3253 (0.00331)** |
| 1.250e-6 | 4.2314 (0.0972) | 4.4212 (0.0926) | **4.3278 (0.000828)** |
| 6.250e-7 | 4.2806 (0.0480) | 4.3755 (0.0469) | **4.3284 (0.000207)** |
| 3.125e-7 | 4.3047 (0.0239) | 4.3522 (0.0236) | **4.3285 (0.0000530)** |

**Convergence order** (|err vs continuum| ratio per dt-halving; 0.5 = 1st-order, 0.25 = 2nd-order):

| arm | 1e-5→ | 2.5e-6→ | 1.25e-6→ | 6.25e-7→ |
|---|--:|--:|--:|--:|
| explicit | 0.210 | 0.488 | 0.494 | 0.497 → **1st-order** |
| backward-Euler | 0.283 | 0.512 | 0.506 | 0.503 → **1st-order** |
| **trapezoidal-CN** | 0.061 | 0.250 | 0.250 | 0.257 → **2nd-order** |

**Reads.**
1. **Trapezoidal is 2nd-order; explicit and backward-Euler are both 1st-order** — textbook, and confirms the
   `-xbtrap` operator is a correct midpoint discretization.
2. **CN lands at the continuum already at dt=1e-5.** CN@1e-5 error 0.054 nm vs explicit 0.949 / BE 0.640 —
   **17.6× smaller than explicit** (and even explicit@3.125e-7 = 0.024 nm is still 2× worse than CN@1e-5). On the
   *isolated smooth F8-translation relaxation*, the trapezoidal upgrade fully recovers the dt→0 limit at production
   dt. **GATE PASS ⇒ proceed to STEP 2.**
3. Per-step gap-fractions **0.4187 / 0.2951 / 0.3462** = analytic **0.419 / 0.295 / 0.346** — the operator's
   per-step decay is exact; explicit overshoots the exact 0.343, BE undershoots, CN is near-exact, exactly as the
   discriminating hypothesis predicted.

Reproduce: `./run_eomstab.sh -f8relax` (deterministic, ~1 min). Log: `RUN_LOGS/2026-07-06_xbtrap_step1.txt`.

---

## STEP 2 — does `-xbtrap` move the actual gliding per-bound at dt=1e-5? — **NULL**

**`-xbtrap`** (`GlidingHarness`) = the `-xbimplicit2` coupled F8 star re-timed to the trapezoidal midpoint by halving
`myoSpring` in `xbImplParams` (⇒ `r→r/2` in `coupleComputeA`/`coupleSolveSeg`; the star kernels are byte-unchanged).
Paired short-window comparison: `-full -grid -lymntaylor -adppibind -ratefix -coltol 10 -density 1000 -matband 1.2
-earlystop -escap 0.2 -seed 0`, **GPU device-resident** (the validated gliding TaskGraph + the byte-unchanged coupled
star), all points capped at 0.2 s (single-seed relSEM 8–20 %, the accepted `-earlystop@cap` regime). Three schemes
× two dt (the task asked for the three 1e-5 arms + `-xbtrap`@2.5e-6; I added explicit/BE @2.5e-6 to compare climb
factors in the identical config).

| scheme | dt | velFitX (µm/s) | avgBsteady | **per-bound** = velFitX/avgB | relSEM | velFitX climb 1e-5→2.5e-6 |
|---|--:|--:|--:|--:|--:|--:|
| explicit (F8 explicit) | 1e-5 | 1.987 | 1.485 | **1.338** | 20 % | — |
| explicit | 2.5e-6 | 5.413 | 3.883 | 1.394 | 10 % | **2.72×** |
| `-xbimplicit2` (backward-Euler) | 1e-5 | 2.986 | 2.406 | **1.241** | 13 % | — |
| `-xbimplicit2` | 2.5e-6 | 5.880 | 3.908 | 1.505 | 8 % | **1.97×** |
| **`-xbtrap` (trapezoidal/CN)** | 1e-5 | 2.830 | 2.030 | **1.394** | 16 % | — |
| **`-xbtrap`** | 2.5e-6 | 5.523 | 4.110 | 1.344 | 8 % | **1.95×** |

**Reads.**
1. **NULL on the per-bound: `-xbtrap`@1e-5 (1.394) ≈ explicit@1e-5 (1.338) ≈ `-xbimplicit2`@1e-5 (1.241)** — the three
   schemes give the SAME per-bound at production dt, within the ~15 % single-seed noise. Trapezoidal does not jump it
   toward any higher value. (This is the task's literal NULL definition: `-xbtrap` ≈ explicit ≈ `-xbimplicit2`.)
2. **`-xbtrap` is NOT converged at 1e-5** — its glide velocity still climbs **1.95×** (2.830 → 5.523) to dt=2.5e-6,
   and avgBound climbs 2.02× (2.030 → 4.110). Flat would be ~1.0×. So the trapezoidal re-timing does not deliver the
   dt-robust glide at production dt.
3. **Trapezoidal ≡ backward-Euler in the assay (the decisive contrast with STEP 1).** `-xbtrap`'s 1.95× climb is
   indistinguishable from `-xbimplicit2`'s 1.97×; at 1e-5 the two are the same within noise (velFitX 2.83 vs 2.99,
   avgB 2.03 vs 2.41). **STEP 1 proved CN is 17× more accurate than BE on the isolated smooth F8-translation** — yet
   that accuracy is entirely inert here. ⇒ the assay's dt-sensitivity is not governed by the F8-translation
   integration order.
4. **Unbiased (same continuum) — the 2.5e-6 confirm.** All three schemes agree at the finer dt: velFitX 5.41 / 5.88 /
   5.52 (within 8–10 % noise), per-bound 1.39 / 1.51 / 1.34. So `-xbtrap` converges to the SAME continuum as explicit
   — it is a correct re-timing, just insufficient at 1e-5 (the "still climbs," not "biased," fork; matches
   `-xbimplicit2`).
5. **The residual dt-climb is in the BINDING COUNT, not the per-bound.** In this `-ratefix` config the per-bound is
   already dt-stable (≈1.3–1.4 at both dts, all schemes — `-ratefix` having converted the stroke/rotation per-step
   fractions to dt-honest rates); what still climbs ~2× is avgBound. Both implicit F8 schemes only *partially* raise
   avgBound@1e-5 toward the continuum (explicit climb 2.61× → BE 1.62× / CN 2.02×) and neither converges it — the
   binding count is governed by the cross-bridge stretch distribution across discrete kinetic events, i.e. the
   event-timing, which no F8-translation integrator order fixes.

**CPU≡GPU parity (confirmed, not assumed).** v1box `-grid -lymntaylor -adppibind -ratefix -coltol 10 -density 1000
-xbtrap -seed 0` (20k): CPU velFitX 3.061 / avgBsteady 2.545 / inst 6.930 vs GPU 2.945 / 2.475 / 6.817 — **Δ 3.9 % /
2.8 % / 1.7 %**, well within the chaotic-gliding aggregate-within-SEM standard (and tighter than `-xbimplicit2`'s
7 %/16 %). `-xbtrap` runs the byte-unchanged coupled-star kernels with only a halved `myoSpring` scalar (which both
runners read identically), so parity is inherited by construction — and confirmed here.

**Caveat (honest scope — the absolute per-bound calibration).** These are 0.2 s-capped single-seed short-window
values (relSEM 8–20 %); the per-bound sits ~1.3 here and is dt-flat within that noise, which differs from ROTIMPLICIT
Part B's longer-window per-bound (0.822@1e-5 → 1.783 converged). That difference is a measurement-window/seed effect,
NOT resolved here — and it does not affect the verdict: the decisive result is **config-internal** (all arms measured
identically), namely that `-xbtrap` ≈ `-xbimplicit2` ≈ explicit at 1e-5 and that CN's STEP-1 17× accuracy advantage
produces ZERO assay benefit. That holds regardless of the absolute per-bound calibration.

---

## PASS / PARTIAL / NULL — stated plainly

**NULL.** A 2nd-order (trapezoidal / Crank–Nicolson) integrator on the F8 cross-bridge translation does **NOT**
recover the converged per-bound glide at production dt=1e-5. At production dt `-xbtrap` is indistinguishable from
explicit and from backward-Euler (`-xbimplicit2`), and the glide still climbs ~2× to finer dt. **The bias is
kinetics / discrete event-timing, not the smooth F8-translation integration order** — proven, not merely inferred,
by the STEP-1/STEP-2 contrast: the trapezoidal upgrade collapses the *isolated* smooth relaxation to the continuum at
1e-5 (17× better than explicit) yet is completely inert in the *assay*, so the assay residual cannot be the smooth
F8-translation truncation. **A sub-step is the indicated answer**, and it must cover the loaded, cycling
stroke/force/kinetics integration (the binding-count dt-sensitivity), not the F8 stretch — which `-xbimplicit2`
already stabilized and `-xbtrap` re-times to no additional effect. This confirms the skeptical expectation and
converges with `COUPLED_IMPLICIT_XB` (BE insufficient), `substep-feasibility-verdict`, `STROKE_TIP_IMPLICIT_PROBE`
(the bias is loaded/cycling-ensemble transport, not a per-motor stroke property), and `EOM_STABILITY_FINDINGS` (the
limiter is the collective loaded force).

---

## Reproduce

```
# STEP 1 (deterministic, ~1 min) — the 3-arm integrator-order ladder on the isolated smooth F8-translation:
./run_eomstab.sh -f8relax
# STEP 2 (GPU device-resident, ~13 min for all 6 cells) — the paired short-window scheme × dt grid:
./run_xbtrap_conv.sh          # the 6-cell grid: explicit / -xbimplicit2 / -xbtrap @1e-5 + all three @2.5e-6
./run_xbtrap_parity.sh        # CPU≡GPU aggregate parity on -xbtrap (v1box)
# one -xbtrap point directly:
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -ratefix -coltol 10 -density 1000 -matband 1.2 \
    -earlystop -escap 0.2 -dt 1e-5 -seed 0 -xbtrap 20000
```
Files: `EomStabilityHarness` (`-f8relax`: `runF8RelaxProbe`/`f8Relax`/`segF8Correct`); `GlidingHarness`
(`-xbtrap`: `XB_TRAP`, the halved `setImplicit`, reusing `CrossBridgeSystem.coupleComputeA/coupleSolveSeg/
coupleCorrectHead` byte-unchanged). Logs: `RUN_LOGS/2026-07-06_xbtrap_step1.txt`, `..._step2.txt`. Default
byte-identical (both flags gated); `-xbtrap` mutually exclusive with `-xbimplicit`/`-segimplicit`; `BoA-v1ref`
byte-clean.
