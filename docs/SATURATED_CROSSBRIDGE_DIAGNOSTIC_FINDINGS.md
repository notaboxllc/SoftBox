# SATURATED CROSS-BRIDGE as a DIAGNOSTIC — what saturation shape/magnitude lets 2 pN/nm @ 1e-5 recover its OWN faithfully-integrated self?

**MEASUREMENT-ONLY, additive flag `-xbsat`, default-off (production byte-unchanged).** A flag-gated **saturating
modification of the existing Hookean F8** cross-bridge spring (`fmag = myoSpring·dist` in `CrossBridgeSystem.bondForces`).
The plain Hookean stays the default; the binding search, the catch-slip rate FORMULA, the F9/F10 alignment torques,
and the 12 pN break cap are all **untouched**. `BoA-v1ref` untouched. Sole occupant of aorus; **v2-GPU
device-resident** (the 0.5× dt-study contractile scene, the 5-graph chained split, ~84 steps/s). Companions:
`DT_CONVERGENCE_FINDINGS.md`, `CROSSBRIDGE_STIFFNESS_SWEEP_FINDINGS.md`, `RELEASE_FORCE_INPUT_FINDINGS.md`,
`BOUND_THERMAL_CORRELATION_FINDINGS.md`.

## The idea — use the 2 pN/nm @ 1e-5 collapse as a clean bench
The 2 pN/nm @ 1e-5 binding collapse (bound ~10 contractile, off-rate ~39 000/s) is a **controlled, reproducible
instance of the overshoot→catch detonation** we would face pushing dt up. By the `r = k·dt/γ` scaling it is the SAME
overshoot event as k=1.0 @ 2e-5, studied at the convenient, fast, otherwise well-behaved dt=1e-5. The explicit step
`x_{n+1}=(1−k·dt/γ)x_n` overshoots as `r→1` (at k=2 pN/nm, dt=1e-5: r=1.06); the overshoot manufactures spurious
large **negative (compressive)** load excursions, and the catch term `e^(−F·xCatch)` (F = signed `forceDotFil`)
**detonates on negative load** → instant unbinding → collapse. A saturating F8 caps the force the over-stretched bond
exerts; since that force is also what displaces the head each step, the cap dynamically **bounds the overshoot**.

**This is an INTEGRATION-fidelity test, not a calibration one.** The target is NOT "the right stiffness" (v1's
gliding is an unpublished internal reference, not experimentally validated — see the reference-posture note in
`CROSSBRIDGE_STIFFNESS_SWEEP_FINDINGS.md`). The target is the **fine-dt 2 pN/nm motor** — what 2 pN/nm does when the
overshoot is *integrated away* (faithful fine-dt), not clipped away. The question is purely: does saturation make the
overshooting 2 pN/nm @ 1e-5 reproduce its OWN faithfully-integrated self — the bound count AND the **signed-load
distribution, especially the negative tail the catch reads**? i.e. did it **tame the overshoot while keeping the
force**, vs **clip the force into a different motor**?

## The saturating force law (`-xbsat <mode> <Fmax_pN> <onset_pN>`)
Parameterized, gated by `xbParams` SIZE (size-6 ⇒ `satMode=0` ⇒ plain Hookean, **byte-identical**; size-9 only when
`-xbsat` is set). Caps the |F8| spring magnitude; the force DIRECTION is unchanged ⇒ F, both alignment torques, and
`forceDotFil` all rescale consistently. Four modes (sharpness × symmetry):

| mode | shape | side |
|---|---|---|
| 1 | smooth tanh (Hookean below `onset`, →`Fmax` asymptote) | symmetric on \|F\| |
| 2 | hard clip `min(fmag,Fmax)` | symmetric on \|F\| |
| 3 | smooth tanh | **compression-only** (forceDotFil<0 — the side the catch detonates on) |
| 4 | hard clip | **compression-only** |

`onset` is the stretch/force where saturation begins (tanh only; hard clip ignores it). Asymmetry is physically
motivated: a real cross-bridge bears tension but **buckles / cannot transmit large compression** — and compression is
exactly where the catch detonates.

---

## STAGE 1 — the FAITHFUL (fine-dt) 2 pN/nm REFERENCE (the target)
2 pN/nm, plain Hookean, integrated at small dt on the 0.5× dt-study scene (box 5.0, 200 nodes × 24 singlet, 500 fil),
matched sim-time 0.06 s, n=2 seeds. By `k·dt`, k=2@2e-6 ≡ k=1@4e-6 and k=2@1e-6 ≡ k=1@2e-6 (the dt-study converged
plateau). The two fine dt's **agree** (the plateau is reached). Steady-state (simT ≥ 0.042) mean over both dt × n=2:

| metric | faithful 2 pN/nm reference | the 2 pN/nm @ 1e-5 collapse (for contrast) |
|---|---|---|
| bound | **~396** (370 @2e-6 → 420 @1e-6, mild climb) | ~10 |
| off-rate (catch-slip) /s | **~340** | ~39 000 |
| catchFrac | ~0.97 | ~0.99 |
| `fmgMean` (\|F8\| stretch) pN | **4.33** | ~13 |
| `fmgMax` pN | **~11.1** (rare >12) | ~26 |
| signed-load `meanF` pN | +0.36 | mixed/neg |
| signed-load **`p10`** pN | **−3.31** | ~−12 |
| `fracNeg` | **0.45** | ~0.46 |
| `meanNeg` pN | **−2.15** | ~−5.7 |

The faithful 2 pN/nm motor is a genuinely **stiffer, higher-release, lower-bound** motor than the faithful 1 pN/nm
motor (B*≈1050, off~160) — the stiffer spring generates more load → more catch-slip release → fewer bound. That is
expected and correct: the test is whether 2 pN/nm recovers **this** self, not k=1's.

**The distribution to recover is TIGHT and smooth:** mean \|F8\| 4.3 pN, max ~11 pN, no pile-up; signed-load p10 −3.3,
meanNeg −2.1. The collapse's distribution is inflated and fat-tailed (mean 13, max 26, p10 −12).

---

## STAGE 2 — saturation sweep at 2 pN/nm @ dt=1e-5: recover the DISTRIBUTION (not just the count)
Each config: 2 pN/nm, dt=1e-5, the same 0.5× scene, steady-state (simT ≥ 0.05) mean. **Target (faithful ref):**
bound **396**, off **340**, fmgMean **4.33**, fmgMax **11.1**, p10 **−3.31**, fracNeg **0.45**, meanNeg **−2.15**.

| config | bound | offCS /s | fmgMean | fmgMax | p10 | fracNeg | meanNeg | fracOverCap |
|---|---|---|---|---|---|---|---|---|
| **m=0 COLLAPSE-REF** (no sat) | 12 | 420* | 11.6 | 21.4 | −2.1* | 0.39 | −2.3 | 0.30 |
| | | | | | | | | |
| m=2 hardclip Fmax=4 | 756 | 211 | 3.91 | **4.00** | −3.16 | 0.47 | −1.97 | 0 |
| m=2 hardclip Fmax=5 | 602 | 305 | 4.85 | **5.00** | −3.77 | 0.46 | −2.37 | 0 |
| **m=2 hardclip Fmax=6** | **428** | 450 | 5.71 | **6.00** | −4.78 | 0.48 | −2.97 | 0 |
| m=2 hardclip Fmax=8 | 182 | 1094 | 7.39 | **8.00** | −6.05 | 0.47 | −3.93 | 0 |
| | | | | | | | | |
| m=1 tanh Fmax=4 o=0 | 792 | 203 | 3.70 | **4.00** | −2.98 | 0.49 | −1.88 | 0 |
| m=1 tanh Fmax=5 o=0 | 663 | 266 | 4.46 | **5.00** | −3.65 | 0.46 | −2.30 | 0 |
| m=1 tanh Fmax=6 o=3 | 466 | 426 | 5.56 | **6.00** | −4.37 | 0.45 | −2.85 | 0 |
| m=1 tanh Fmax=8 o=0 | 280 | 699 | 6.42 | **7.98** | −5.34 | 0.48 | −3.36 | 0 |
| | | | | | | | | |
| m=3 asym-tanh Fmax=4..8 | **30–44** | 165–526 | 6.5–10.1 | 18–24 | −2.4…−4.8 | 0.35–0.57 | −1.5…−2.5 | 0.12–0.31 |
| m=4 asym-clip Fmax=4..8 | **25–32** | 192–593 | 7.8–10.0 | 20–23 | −3.3…−4.4 | 0.39–0.50 | −1.7…−2.5 | 0.16–0.23 |

`*` the collapse off-rate-CS snapshot (420) under-reads the true ~39 000/s total (the cap fires every step at
fracOverCap 0.30; the catch-slip sub-rate alone is shown). Full collapse internals in `CROSSBRIDGE_STIFFNESS_SWEEP`.

**Three things the sweep shows:**

1. **Compression-only asymmetry (modes 3, 4) does NOT even recover binding** — bound stays **25–44** (vs collapse
   ~12, ref ~396), fmgMax stays 18–24, fracOverCap 0.12–0.31. Saturating only the compressive side (forceDotFil<0)
   leaves the **tensile** overshoot to drive the head displacement; the head still over-runs each step and the
   resulting geometry still manufactures the negative-load excursions that detonate the catch. ⇒ the saturation must
   bound the **symmetric magnitude** (which is what bounds the head's per-step displacement, i.e. the overshoot
   itself). The physically-appealing "buckle in compression" asymmetry is **ineffective** against this collapse.

2. **Symmetric saturation (modes 1, 2) DOES recover binding + directed glide** (secondary confirm below) — but
   only along a **one-parameter (Fmax) trade-off** that the faithful reference does **not** sit on. Monotone in Fmax:
   smaller Fmax → more bound + lower force; larger Fmax → fewer bound + higher force. There is **no Fmax that hits the
   reference on more than one axis at a time:**
   - **Fmax≈4–5 matches fmgMean (3.7–4.9) and the negative tail (p10 −3.0…−3.8 ≈ ref −3.3)** — but **over-binds
     1.5–2×** (bound 600–800 vs 396) and drops the off-rate too far (203–305 vs 340).
   - **Fmax≈6 matches the COUNT (bound 428 ≈ 396)** — but the distribution is a **different motor**: fmgMean +32 %
     (5.7 vs 4.3), the negative tail **too deep** (p10 −4.8 vs −3.3, meanNeg −3.0 vs −2.1), off-rate +32 % (450).
   - You cannot match the count **and** the signed-load tail with one static cap. This is the **count-match trap**
     the task named, realized.

3. **The decisive structural signature — every recovering config PILES the stretch against its ceiling.** In all
   symmetric runs `fmgMax = Fmax` **exactly** (4.00 / 5.00 / 6.00 / 8.00) — the entire bound population sits at the
   cap, because the 1e-5 overshoot still drives every head hard against whatever ceiling is set. The **faithful**
   reference has `fmgMean 4.3` with `fmgMax 11.1` and **no pile-up** — a smooth thermal/geometric spread with a real
   tail (max/mean ≈ 2.6). A static cap converts that smooth tail into a wall (max/mean ≈ 1.1–1.4). The faithful
   shape is **structurally unreachable** by an instantaneous magnitude cap.

### Secondary confirm — gliding (does engagement return?)
4×1 v1-box, 2000 heads, `-grid` NET velocity + avgBound, n=2, at 2 pN/nm:

| | avgBound | netX µm/s | directed glide? |
|---|---|---|---|
| no sat (collapse) | ~0.2 | **+0.5** | NO — pure wander (the §Scene-1 k=2 collapse) |
| hardclip Fmax=6 (count-match) | ~10 | **−3.1** | YES — directed −x glide restored |
| tanh Fmax=5 (mean-match) | ~19 | **−2.7** | YES — glide restored, but over-binds |

Symmetric saturation **restores engagement and the directed −x glide** that the collapse had lost — but the avgBound
inherits the same Fmax trade-off (count-match ~10, mean-match over-binds to ~19), echoing the contractile result.
Saturation is a viable **stability band-aid** (it un-collapses the motor); it is **not** a faithful reproduction.

---

## VERDICT — the valuable INTEGRATION-not-force-law NULL
**No static saturation of F8 recovers the faithfully-integrated 2 pN/nm distribution.** The recovering (symmetric)
forms either match the count with a ceiling-piled, too-stiff, too-negative-tailed distribution (Fmax≈6) or match the
force/tail with a 1.5–2× over-bound population (Fmax≈4–5); none reproduce the faithful `fmgMax≈11` smooth tail (all
pile at the cap), and the count vs negative-tail axes trade off along Fmax with the reference on neither. The
compression-only asymmetry does not recover binding at all.

**The mechanistic reason — the overshoot and the real force OVERLAP in magnitude and cannot be separated by any
instantaneous threshold.** The faithful 2 pN/nm motor genuinely exerts up to ~11 pN (a real tail above its 4.3 pN
mean). The 1e-5 overshoot inflates stretches into the ~6–26 pN range. These **overlap in the 6–11 pN band**:
- a cap **above 11 pN** (preserving the real force tail) is **too high to tame the overshoot** (the collapse already
  sits at fmgMean 13);
- a cap **below ~6 pN** (taming the overshoot) **destroys the real force tail** and over-binds.

There is no static, instantaneous force-magnitude law that keeps the real ≤11 pN force while removing the spurious
≥6 pN overshoot, because they occupy the same magnitudes. ⇒ **the fix must live in the INTEGRATION** (sub-stepping /
implicit cross-bridge), not the force law — the **same wall** the force-averaging (`RELEASE_FORCE_INPUT_FINDINGS.md`)
and the constraint-aware thermal-correlation (`BOUND_THERMAL_CORRELATION_FINDINGS.md`) attacks independently hit.
This is now established **cheaply, on the fast 2 pN/nm @ 1e-5 bench**, rather than discovered expensively at 1e-4.

**For the methods paper / thesis §9 (the cross-bridge dt ceiling):** saturating the cross-bridge is a **third
independent force-law lever that fails to buy faithful dt headroom** — joining the softer-spring detune
(`CROSSBRIDGE_STIFFNESS_SWEEP`), the release-force averaging (`RELEASE_FORCE_INPUT`), and the thermal correlation
(`BOUND_THERMAL_CORRELATION`). All four converge on the same conclusion: the cross-bridge force magnitude is
load-bearing and entangled with the overshoot; the headroom must come from a **better integrator of the cross-bridge
sub-system**, not a reshaping of its force law.

### Follow-on (NOT this task)
Whether the recovering saturation lets **1 pN/nm reach 1e-4** (validated the same way against a fine-dt 1 pN/nm
reference) is the separate next question. By the same overlap argument it is expected to fail identically, but it is
unmeasured here.

---

## Stability / runner / regression
- Flag-gated, default-off: with no `-xbsat`, `xbParams` is size-6 ⇒ `CrossBridgeSystem.bondForces` reads `satMode=0`
  ⇒ the plain Hookean F8, **byte-identical**. All 16 other harnesses allocate size-6 ⇒ unaffected. The no-flag 2 pN/nm
  @ 1e-5 run reproduces the published collapse (`CROSSBRIDGE_STIFFNESS_SWEEP_FINDINGS.md` §Scene-2 k=2 row) exactly.
- **CPU≡GPU**: the saturation is a per-bond scalar on `fmag` (the tanh via `Math.exp`, which lowers on the PTX backend
  — same as the catch-slip `e^(−F·xCatch)`); both runners exercised, no NaN, GPU device-resident at 84 steps/s.
- **Device-residency disclosed up front**: the 0.5× contractile scene runs the v2-GPU 5-graph chained split
  device-resident (no `-cpu` fallback).

## Reproduce
```
./run_xbsat.sh stage1     # fine-dt 2 pN/nm reference (k=2 @ dt 2e-6 & 1e-6, n=2)
./run_xbsat.sh stage2     # saturation sweep @ 2 pN/nm dt=1e-5 (mode×Fmax×onset)
./run_xbsat.sh final <mode> <Fmax_pN> <onset_pN>   # multi-seed confirm of a chosen form
# single override (either harness):
./run_1x.sh -gpu -dtconv -nodes 200 -nfil 500 -box 5.0 -dt 1e-5 -myospring 2.0 -xbsat 2 6 0 -seed 1 -steps 8000
./run_gliding.sh -gpu -v1box -grid -myospring 2.0 -xbsat 2 6 0 -seed 1 10000   # secondary glide confirm
```
Raw: `RUN_LOGS/2026-06-26_xbsat_stage1_finedt.txt`, `_stage2_sweep.txt`.
