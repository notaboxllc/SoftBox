# CROSS-BRIDGE STIFFNESS SENSITIVITY SWEEP — is a softer (still measured-valid) myoSpring behaviorally indistinguishable from the default 1 pN/nm? The cheap cut before any PAIRS build.

**MEASUREMENT-ONLY, additive flag, no physics edit.** Varies ONLY the cross-bridge stiffness `myoSpring`
(`xbParams[0]`, the Hookean F8 spring `F = myoSpring·dist`) via a new `-myospring <pN/nm>` flag on both
harnesses, at the validated **dt=1e-5**. The F8 force LAW, the binding search, the catch-slip rate FORMULA,
the 12 pN cap, and the **production default (1 pN/nm) are all UNCHANGED** — with no flag the harnesses are the
committed benchmark. `BoA-v1ref` untouched. Sole occupant of aorus; **v2-GPU device-resident both scenes**
(gliding 23-kernel graph; contractile = the 5-graph chained split, 82.5 steps/s @ the 0.5× scene). Driver
`run_xbstiff.sh`; raw `RUN_LOGS/2026-06-26_xbstiff_gliding.txt`, `_contractile.txt`. Companions:
`DT_CONVERGENCE_FINDINGS.md`, `RELEASE_FORCE_INPUT_FINDINGS.md`, `BOUND_THERMAL_CORRELATION_FINDINGS.md`.

> **Reference-posture note (2026-06-26, added with `SATURATED_CROSSBRIDGE_DIAGNOSTIC_FINDINGS.md`):** the
> "right" myoSpring (≈1 pN/nm) here is the value that reproduces **v1's gliding behavior** (avgBound ≈7.5,
> the gliding velocity) — an **unpublished, internal v1 reference** (a conference-poster comparison), **NOT
> experimentally validated ground truth**. The **sensitivity** finding below — the motor is sharply, not
> loosely, constrained by its emergent gliding behavior — **stands** regardless. Only the framing that 1 pN/nm
> is *experimentally* calibrated was an overclaim and has been softened to "v1-reference" throughout.

## VERDICT — NOT a free win. 0.5 pN/nm is DECISIVELY distinguishable from 1 pN/nm in BOTH scenes ⇒ keep 1 pN/nm; the PAIRS-saturation build is the dt lever.
The motor is **highly sensitive** to cross-bridge stiffness across the measured 0.5–2 pN/nm range — far
outside any seed scatter. Halving `myoSpring` does buy the cross-bridge a 2× reduction in the `k·dt` overshoot
(§3) — exactly as the linear `r = k·dt/γ` algebra predicts — but it **does not preserve behavior**: it
over-binds 3.5×, drops the glide velocity 36 %, and breaks the v1-reference operating point. The
softer spring is not a different-but-equal myosin; it is a **detuned** one. ⇒ the cheap "adopt 0.5, run plain
Hookean at 1e-4" path is **rejected**; the cross-bridge stiffness genuinely matters, so the lever for a larger
faithful dt is a **PAIRS-saturation element** (preserve the 1-pN/nm force up to threshold, saturate the
displacement above), NOT a softer spring.

---

## The sweep — myoSpring ∈ {0.5, 1.0, 2.0} pN/nm at dt=1e-5

`1 pN/nm = 1.0e-9 N/µm = 1.0e-3 N/m` (the production default; `-myospring 1.0` is byte-equivalent to no flag).

### Scene 1 — GLIDING assay (the clean interpretable observable; 4×1 v1box, 2000 heads, 10000 steps = 0.1 s, n=4 seeds)
v1's exact `GlidingAssayEvaluator` (`-grid`): **NET** = net-centroid-displacement / time (the honest glide).

| myoSpring | NET velocity µm/s (mean ± SEM, n=4) | avgBound | note |
|---|---|---|---|
| **0.5 pN/nm** | **2.45 ± 0.11** | **24.4** | over-binds 3.5×; **−36 % velocity** |
| **1.0 pN/nm (default)** | **3.80 ± 0.17** | **6.9** | the v1-reference peak; avgBound ≈ v1's 7.5 |
| **2.0 pN/nm** | **0.53** (netX **positive** → no directed glide) | **0.25** | binding collapses; glide gone |

Per-seed (netXY µm/s | avgBound): 0.5 → (2.23|21.9)(2.42|22.3)(2.75|26.6)(2.38|26.7); 1.0 →
(3.60|6.36)(3.47|6.88)(3.91|7.14)(4.23|7.31); 2.0 → (0.54|0.18)(0.81|0.34)(0.24|0.21)(0.52|0.28).

- **Velocity is non-monotonic and peaks at the default 1.0 pN/nm.** Too soft (0.5) → weak per-motor force +
  over-binding drag ⇒ 0.64× velocity. Too stiff (2.0) → the cross-bridge is already in the catch-explosion
  collapse at 1e-5 (§2) ⇒ essentially no sustained binding (avgBound 0.25) and **no directed glide** (netX
  flips positive — pure wander, not a −x glide).
- **avgBound is tightly pinned to the stiffness:** 24.4 / 6.9 / 0.25 across 0.5 / 1.0 / 2.0. Only the default
  reproduces v1's avgBound (≈7.5 — an unpublished internal v1 reference, NOT experimentally validated) and the committed v2 baseline (4×1 net 4.02 ± 0.15,
  avgBound 7.20, `GLIDING_4biv_FINDINGS.md`). **`-myospring 1.0` lands in that committed envelope (3.80 ± 0.17,
  avgBound 6.9) — the default-unchanged confirmation.**

### Scene 2 — CONTRACTILE / dt-study scene (release & motor internals; box 5.0, 200 nodes × 24 singlets, 500 fil, dt=1e-5, simT 0.10, n=3 seeds)

| myoSpring | bound | off-rate /s (catch-slip) | fmgMean pN (\|F8\| stretch) | meanF pN | p10 pN | fracNeg | catchFrac | fracOverCap | RgXY | state |
|---|---|---|---|---|---|---|---|---|---|---|
| **0.5** | **1116** | **141** | 2.36 | +0.17 | −1.80 | 0.46 | 0.94 | 0.00 | 1.69 | tight tail; **looks dt-converged** |
| **1.0** | **418** | **422** (tot 503) | 4.50 | +0.10 | −3.76 | 0.51 | 0.98 | 0.00 | 1.69 | the known 1e-5 overshoot |
| **2.0** | **11** | tot **~39 000** | 12.1 | mixed | −7 | 0.46 | 0.99 | 0.38–0.55 | 1.69 | **catch-explosion collapse** |

(n=3 means; per-seed bound: 0.5 → 1116/1099/1132; 1.0 → 400/462/391; 2.0 → 16/11/7. Off-rate, fmgMean, p10
each ≤3 % seed scatter at 0.5 and 1.0 — the differences below are ≫ scatter.)

- **bound count spans 1116 → 418 → 11 (100×) across the measured range.** The softer spring binds 2.7× MORE
  than the default; the stiffer unbinds almost everything.
- **off-rate spans 141 → 422 → ~39 000 /s.** Softer → far fewer over-stretched bonds → far lower catch-slip
  release; stiffer → the negative-load catch term `e^{−F·xCatch}` explodes on the dt-inflated overshoot.
- **the signed-load tail (the catch input) tracks stiffness directly:** p10 −1.8 / −3.8 / −7 pN; fmgMean
  (mean \|F8\| stretch) 2.4 / 4.5 / 12.1 pN; fracOverCap 0 / 0 / ~0.5. The whole load distribution rigidly
  scales with `myoSpring`.
- **RgXY (the contraction-extent proxy) is flat at ~1.69 at every stiffness** — non-discriminating in this
  sparse scene (the `DT_CONVERGENCE_FINDINGS.md` observation); the contractile signal lives in the bound
  population + release internals, not the macroscopic extent.

---

## §2 — Per-stiffness dt-threshold (where the plain Hookean goes unstable)

Explicit-overdamped stability of `x_{n+1} = (1 − r)x_n`, `r = myoSpring·dt/γ_head`, needs `r < 2`
(γ_head = 6π·aeta·R_head = **1.885e-8 N·s/m**). So `dt_threshold = 2·γ_head / myoSpring`:

| myoSpring | k (N/m) | **dt_threshold (r=2)** | r at dt=1e-5 | r at dt=1e-4 |
|---|---|---|---|---|
| 0.5 pN/nm | 0.5e-3 | **7.54e-5** | 0.27 | **2.65 (unstable)** |
| 1.0 pN/nm | 1.0e-3 | **3.77e-5** | 0.53 | 5.31 |
| 2.0 pN/nm | 2.0e-3 | **1.885e-5** | 1.06 | 10.6 |

**Correction to the task's premise:** the task's "at 0.5 pN/nm, dt=1e-4 the ratio drops to r≈1.3, stable" is
an arithmetic slip — `r = 0.5e-3·1e-4/1.885e-8 = 2.65`, which is **above** the `r=2` boundary, so 0.5 pN/nm is
deterministically **unstable** at 1e-4. The largest dt at which the **softer Hookean is deterministically
stable is ~7.6e-5** (where r=2), NOT 1e-4.

**And the operative limit is lower still — the catch-explosion second ceiling.** The cross-bridge overshoot
collapses BINDING (the catch term explosion) well before the deterministic stability ceiling: at k=1.0 the
dt-study showed binding collapse at **dt ≥ 2e-5** (bound ~18), far below the deterministic 3.8e-5. By the `k·dt`
scaling (§3) this collapse is governed by the product `k·dt`, so at k=0.5 it moves only to **dt ≈ 2–4e-5**
(`k·dt` matching the k=1.0 @ ~2e-5 onset), **NOT to 1e-4**. So even setting behavior aside, "free 1e-4 with a
softer Hookean" fails twice: (a) deterministic instability at r=2.65, and (b) the catch-explosion binding
collapse at ~2–4e-5.

---

## §3 — The mechanistic core: stiffness and dt enter the cross-bridge ONLY via the product r = k·dt/γ

The sweep is a clean cross-check of the dt-convergence study. The explicit cross-bridge overshoot scales with
`r = myoSpring·dt/γ_head`, so **varying stiffness at fixed dt traces the SAME curve as varying dt at fixed
stiffness, at matched `k·dt`.** Confirmed:

| this sweep (vary k @ dt=1e-5) | bound, off-rate | the dt-study (vary dt @ k=1.0) at matched k·dt | bound, off-rate |
|---|---|---|---|
| k=0.5 → k·dt ≡ 0.5×(1e-5) | **1116, 141** | dt≈5e-6 (·2e-6) converged regime | 905–1093, 175–204 |
| k=1.0 → k·dt ≡ 1.0×(1e-5) | **418, 422** | dt=1e-5 reference (identical run) | 400–470, 427 |
| k=2.0 → k·dt ≡ 2.0×(1e-5) | **11, ~39 000** | dt=2e-5 collapse | ~18, ~6900 |

So **softening `myoSpring` 2× is numerically equivalent to halving dt** for the cross-bridge — which is exactly
why it looked like a candidate free win. The catch is that the dt-converged target (`B*≈1050`, off-rate ~160 at
k=1.0) is reached at fine dt **without changing the force the motor exerts**, whereas softening k reaches a
similar-looking bound population by **halving that force** — and the force is what the v1-reference
gliding behavior depends on. Same overshoot reduction, different physics.

---

## §4 — Why the contractile "0.5 looks converged" does NOT make it the right value

A tempting misread: in the contractile scene, k=0.5 @ 1e-5 (bound 1116, off-rate 141, tight p10 −1.8, 0 %
over-cap) sits **closer to the dt-converged k=1.0 reference** (bound ~1050, off-rate ~160) than the default
k=1.0 @ 1e-5 (bound 418, off-rate 422, the overshoot) does. So isn't the softer spring "more converged"?

**No — that is the `k·dt` coincidence, not a calibration.** The gliding assay is the arbiter because it is the
**v1-reference observable** (v1's avgBound ≈ 7.5, the gliding velocity — an unpublished internal v1 comparison,
NOT experimentally validated ground truth), and there k=1.0 matches (avgBound 6.9,
velocity peak) while k=0.5 over-binds to **avgBound 24.4** and glides 36 % slower. The reason: the motor's other
parameters (kOff, the catch-slip α/x constants, the reach) were **jointly calibrated with k=1.0 @ 1e-5** so that
the (overshot) cross-bridge yields the right emergent gliding. Softening k breaks that joint calibration —
binding triples. A softer spring would require **re-calibrating kOff et al.** to recover the gliding behavior;
it is not a drop-in. The contractile "converged look" is the cross-bridge overshoot being smaller, not the
system being more faithful — the gliding avgBound proves the system is now mis-calibrated.

---

## §5 — Sensitivity characterization (for the methods paper)

Across the measured 0.5–2 pN/nm range, the motor is **sharply, not loosely, constrained** to ≈1 pN/nm:

- **Glide velocity** is **non-monotonic, peaked at 1.0 pN/nm**: 2.45 / 3.80 / 0.53 µm/s at 0.5 / 1.0 / 2.0
  (0.64× / 1.0× / collapse). A ~10–15 % stiffness error moves velocity by ≫ the ±5 % seed SEM.
- **avgBound** is monotone-decreasing and steep: 24.4 / 6.9 / 0.25. Only k≈1.0 reproduces v1's reference 7.5 (unpublished).
- **Contractile bound count** spans 100× (1116 / 418 / 11); **off-rate** spans 280× (141 / 422 / 39 000).
- The qualitative "myoSpring ≈ 1 pN/nm gives about-right behavior" choice is therefore **tightly pinned by the
  gliding calibration** — the behavior constrains the parameter far more sharply than the "chosen by
  observation, measured range 0.5–2" framing suggests. The upper measured bound (2 pN/nm) is in fact
  **unreachable at the validated dt=1e-5** (catch-explosion collapse), an independent indication that the
  operating point sits near the soft end of the measured range, at ≈1 pN/nm.

---

## §6 — Stability / runner / regression

- All 21 runs stable, finite, conserving (no NaN; escXY=0) except the **expected** k=2.0 @ 1e-5 binding
  collapse (a real physical/numerical collapse, not a crash — the catch-explosion, bound 7–16, off-rate-tot
  ~39 000/s, fracOverCap ~0.5).
- **Device-residency disclosed up front:** both scenes ran v2-GPU device-resident (gliding 23-kernel graph;
  contractile 5-graph chained split, 82.5 steps/s, 9980 steps in ~121 s each), no `-cpu` fallback.
- **Default unchanged:** `-myospring 1.0` reproduces the committed gliding baseline (net 3.80 ± 0.17 / avgBound
  6.9 in the `GLIDING_4biv` 4.02 ± 0.15 / 7.20 envelope) and the committed dt-study 1e-5 reference (bound ~418,
  off-rate ~422) — the flag is a faithful override, the production path is byte-unchanged with no flag.

## Reproduce
```
./run_xbstiff.sh gliding       # 4×1 gliding, myoSpring ∈ {0.5,1,2} × seeds 1–4 (GRID_ROW net velocity, avgBound)
./run_xbstiff.sh contractile   # 0.5× dt-study scene, myoSpring ∈ {0.5,1,2} × seeds 1–3 (DTROW/RELROW/SLHIST internals)
./run_xbstiff.sh all
# single override (either harness):
./run_gliding.sh -gpu -v1box -grid -myospring 0.5 -seed 1 10000
./run_1x.sh -gpu -dtconv -nodes 200 -nfil 500 -box 5.0 -dt 1e-5 -myospring 0.5 -seed 1 -steps 10000
```
Flag: `-myospring <pN/nm>` on `GlidingHarness` + `V2OneXHarness` (default-off ⇒ production byte-unchanged).
Raw: `RUN_LOGS/2026-06-26_xbstiff_gliding.txt`, `_contractile.txt`.

## Bottom line for the thesis (§9 — the cross-bridge dt ceiling)
A softer-but-still-measured-valid cross-bridge stiffness is **not** a free route to a larger faithful dt. The
`k·dt` overshoot scaling means halving `myoSpring` halves the overshoot (≈ halving dt) — but it **detunes the
motor** (gliding velocity −36 %, avgBound +250 %, breaking the v1-reference operating point) and still
does not reach 1e-4 (deterministic instability at r=2.65 + the catch-explosion collapse at ~2–4e-5). The
behavior tightly constrains `myoSpring ≈ 1 pN/nm`. ⇒ the dt headroom must come from a **PAIRS-saturation
cross-bridge** that preserves the 1-pN/nm force up to a stretch threshold and saturates above it (capping the
overshoot WITHOUT softening the force the motor feels) — or the sub-stepped/implicit cross-bridge — the same
lever the release-wing studies (`RELEASE_FORCE_INPUT`, `BOUND_THERMAL_CORRELATION`) independently converged on.
This is a third, independent confirmation that the cross-bridge force magnitude is load-bearing and cannot be
traded away for stability.
