# Phase-2 POST-FIX re-measure — clean glide velocity (config1 + perphead) + the force decomposition RE-RUN on the now-cycling motor

**2026-06-29. MEASUREMENT-ONLY. Post-fix motor (ATP-release coupling ON, the config1/perphead default); dt=1e-6;
same bed for both motors; v1 is NOT a quantitative anchor (the only velocity anchor is the EXPERIMENT — skeletal
Vmax ~2.9 µm/s + the rising→saturating density shape). NO physics edit, no retune, no geometry change, no default
flip. Additive measurement instrumentation only (a productive-phase readout in the existing `-forcedecomp`).
`BoA-v1ref` read-only / byte-clean.**

---

## TL;DR
- **Premise holds.** On this run both motors have **bound-in-ATP 0.0 %**, an **ADP-dominated bound set** (config1
  93.4 %, perphead 93.9 % — the strained, force-generating state) and an **exercised catch-slip** (release 678/548
  /s, real signed forceDotFil). The slack-ATP regime is gone.
- **Part 1 (velocity).** Clean multi-seed velFitX/netX, same matbed bed, dt=1e-6, 40k, GPU device-resident:
  **perphead glides −x, ~1.2 → 2.5 µm/s, RISING with density, stable** (low SEM, no blow-ups). **config1 glides
  +x, ~1.6 µm/s, but is numerically FRAGILE** — the dt=1e-6 whip **recurs at density 1000** (1 of 3 seeds
  exploded), high seed-variance, no clean density trend. **Opposite glide signs CONFIRMED** (config1 +x, perphead
  −x). Both are the **same order as the ~2.9 µm/s experimental Vmax**.
- **Part 2 (force decomposition).** The single-motor `-forcedecomp` instrument **reproduces the OLD numbers
  bit-for-bit** (config1 **22:1**, perphead **2.35:1** transverse:axial) — because it **never ran the gliding
  cycle / ATP-release / catch-slip** (single motor, manual nucleotide states, Brownian off). **So 22:1 / 2.35:1
  were NOT artifacts of the broken slack-ATP regime** — they are intrinsic stroke GEOMETRY. Read **through the
  productive phase** (the strained ADP dwell the catch-slip now governs): perphead delivers **⟨axial⟩ ≈ +2.0 pN
  (transverse:axial ~2.3:1)**, config1 delivers **⟨axial⟩ ≈ 0 (sign-unstable, transverse:axial 24–800:1)**.
  Per-cycle axial impulse **perphead +0.0036 pN·s vs config1 ≈ −4e-5 pN·s ⇒ perphead ~80–100× more axial.**
- **Adjudication = OUTCOME (a):** on the correctly-cycling motor, **perphead still clearly delivers more axial
  (transport) force than config1.** The old perphead-vs-config1 difference is **NOT** a broken-state artifact —
  the geometry change matters.

---

## Premise check (`-diag`, v1box, dt=1e-6, 40k, CPU)
| motor | bound-in-ATP | ADP % | mean bound lifetime | catch-slip release | net glide | avgBound | forceDotFil resisting |
|---|---|---|---|---|---|---|---|
| **config1** | 0.0 % | 93.4 % | 1.474 ms | 678 /s | **+1.83 µm/s (+x)** | 2.91 | 45.8 % |
| **perphead** | 0.0 % | 93.9 % | 1.824 ms | 548 /s | **−1.38 µm/s (−x)** | 4.03 | 47.5 % |

Both ADP-dominated, catch-slip genuinely load-driven — the post-fix premise is satisfied for both motors. (These
match `PHASE2_ATP_RELEASE_COUPLING_FINDINGS`.) The 1.47/1.82 ms lifetimes set the productive window in Part 2.

---

## Part 1 — clean glide velocity (config1 + perphead, post-fix)

Directed velocity by the post-settling least-squares estimator (`velFitX`, steady 2nd-half slope) and the honest
net (`netX` = signed Δx/time; **− = −x glide, + = +x glide**). Same FULL-MAT bed for both motors (`-matbed`,
5.80×1.20 µm²), dt=1e-6, 40k steps, n=3 seeds, two densities. **GPU device-resident** `buildPlan` (see disclosure).
All cells `fullMat=YES` **except** the one noted blow-up.

| motor | density | **netX (signed, µm/s)** | velFitX (µm/s)¹ | avgBound (steady) | n |
|---|---|---|---|---|---|
| **config1** (glides +x) | 500 | **+1.65 ± 0.53** | −2.08 ± 0.88 | 2.48 ± 0.51 | 3 |
| **config1** | 1000 | **+0.82 ± 0.81** | −0.43 ± 1.56 | 4.52 ± 0.79 | **2** ² |
| **perphead** (glides −x) | 500 | **−1.22 ± 0.10** | +1.79 ± 0.10 | 4.14 ± 0.06 | 3 |
| **perphead** | 1000 | **−2.54 ± 0.51** | +2.51 ± 0.59 | 6.80 ± 0.76 | 3 |

¹ velFitX sign convention is **positive = −x glide** (it is −slope), so perphead is +, config1 is − — both are
internally consistent with netX. ² config1 @ density 1000, **seed 0 numerically EXPLODED** (velFitX ~1e18,
`fullMat=VIOLATED`) — the dt=1e-6 whip; that seed is excluded, leaving n=2 (one of which glides ≈0).

**Reading (anchored to the EXPERIMENT, not v1):**
- **Sign — CONFIRMED opposite, on a clean converged multi-seed measurement:** config1 glides **+x**, perphead
  glides **−x** (matches the `-diag` net velocities above). Not a one-seed accident.
- **Magnitude:** both motors are **the same order as the ~2.9 µm/s skeletal Vmax** (perphead −1.2…−2.5; config1
  +0.8…+1.7) — neither is anomalously fast or stalled.
- **Density SHAPE:** **perphead RISES with density** (−1.22 → −2.54 µm/s; avgBound 4.1 → 6.8) — the
  experiment-like rising trend, **stable and low-variance** across all 6 cells. **config1 does NOT show a clean
  rising trend** and is **numerically fragile** — the dt=1e-6 whip **recurs at the higher density** (1/3 seeds
  blew up) and the surviving seeds have very high run-to-run variance (one glides ≈0). config1's whip is therefore
  **not fully tamed by the ATP-release fix** at density > 500; it is density/seed-dependent (the fix only removed
  it at the v1box/500 operating point reported in the ATP findings).

---

## Part 2 — force decomposition RE-RUN, through the PRODUCTIVE phase (config1 + perphead)

`-forcedecomp` (single motor, gliding/transport topology, dt=1e-6, Brownian OFF). **It reproduces the OLD numbers
bit-for-bit** (below). **Why — the decisive point:** this instrument fires ONE stroke on a held filament with the
nucleotide states **set by hand** (ADPPi → fire) — it **never executes the gliding cycle, the ATP-release
coupling, or the catch-slip release**. The "broken slack-ATP regime" was an **ensemble** property of the gliding
runs; it **never entered this single-motor instrument**. The OLD decomposition was therefore **already** read on a
**strained, firing stroke**, not on a slack ADP·Pi-at-≈0-strain head. ⇒ **The fix cannot have changed it, and did
not — the ratios are intrinsic stroke geometry, not slack-ATP artifacts.**

### (i) Settled isometric (the OLD method — exact reproduction)
| variant | sustained axial (pN) | sustained transverse (pN) | **transverse : axial** | OLD value |
|---|---|---|---|---|
| **PERP-HEAD** | +1.943 | 4.556 | **2.35 : 1** | 2.35:1 ✓ identical |
| **CONFIG-1** | +0.036 | 0.789 | **22 : 1** | 22:1 ✓ identical |
| DEFAULT v1 (ref) | −0.045 | 0.084 | 1.86 : 1 | ~1.9:1 ✓ |

### (ii) THROUGH the productive phase (the strained ADP dwell the catch-slip governs; first 0.5/1/2 ms)
The catch-slip now sets a **short** bound lifetime (config1 1.47 ms, perphead 1.82 ms — far shorter than the ~60 ms
isometric settle), so the **transport-relevant** force is the strained powerstroke dwell, not a fully-relaxed
equilibrium. Productive-phase means:

| variant | window | ⟨axial⟩ (pN) | ⟨transverse⟩ (pN) | transverse:axial |
|---|---|---|---|---|
| **PERP-HEAD** | 0.5/1/2 ms | +2.08 / +2.01 / **+1.98** | 4.78 / 4.67 / 4.61 | 2.30 / 2.32 / **2.33 : 1** |
| **CONFIG-1** | 0.5/1/2 ms | −0.113 / −0.038 / **−0.001** | 1.08 / 0.93 / 0.86 | 9.5 / 24 / **806 : 1** (axial≈0, sign-flips) |
| DEFAULT v1 (ref) | 0.5/1/2 ms | −0.696 / −0.371 / −0.208 | 0.57 / 0.33 / 0.21 | 0.82 / 0.88 / 0.99 : 1 |

(Firing transient, t=0: perphead axial peaks **+4.24 pN**, config1 +0.33, default +2.48 — all decay within
~0.1–0.3 ms to the values above.) **The ratios did NOT change once the head cycles correctly** — perphead holds
~2.3:1 transverse:axial through the whole productive dwell (it reaches its 87.9° ⊥ fixed point in ~0.3 ms and
holds); config1 stays axial-≈-zero (≥24:1, the axial residual even **crosses zero** within the dwell).

### (iii) Net axial impulse per productive cycle (= ⟨axial⟩ × measured bound lifetime)
| variant | ⟨axial⟩_productive | × lifetime | **per-cycle axial impulse** |
|---|---|---|---|
| **PERP-HEAD** | +1.98 pN | 1.824 ms | **+0.0036 pN·s** |
| **CONFIG-1** | ≈ −0.025 pN (sign-unstable) | 1.474 ms | **≈ −4e-5 pN·s (≈ 0)** |

⇒ **perphead delivers ~80–100× more axial impulse per productive cycle than config1.**

---

## The adjudication (stated; NOT fixed)
**On the correctly-cycling, ADP-dominated motor, does perphead still deliver more axial (transport) force than
config1?** → **YES — OUTCOME (a): perphead still clearly more axial; the geometry change matters.**
- Perphead's axial force/impulse exceeds config1's by ~50× (settled) to ~80–100× (per productive cycle), and its
  transverse:axial (2.3:1) is far better than config1's (≥22:1, axial≈0) — **through the productive phase**, not
  just at one isometric pose.
- This is **NOT** an artifact of the broken slack-ATP state (outcome (b) rejected): the single-motor instrument is
  **independent of the ensemble bound-state distribution** and reproduces 22:1 / 2.35:1 exactly post-fix.
- Outcome (c) is rejected: config1 is not better — its productive-phase axial force is essentially zero and
  sign-unstable.

## Sign confound (FLAG — pre-existing, NOT resolved here)
The single-motor force decomposition predicts the **ensemble glide sign for config1 but NOT for perphead**:
- **config1:** single-motor axial **+x**  ↔  ensemble glides **+x** (consistent).
- **perphead:** single-motor axial **+x**  ↔  ensemble glides **−x** (OPPOSES). perphead's −x ensemble glide is a
  **dynamic / collective effect**, not the single-motor isometric force (the released-filament cross-check also
  pushes +x). This is the already-flagged perphead polarity/uperp-direction question
  (`PHASE2_PERP_HEAD_FINDINGS` flag #2) — a calibration call, **not adjudicated here**. So perphead "delivers more
  axial force" is a statement about **magnitude/transport-efficiency**, and the **transport DIRECTION** of the
  perphead ensemble is set elsewhere.

## What changed (additive; measurement-only; default & `BoA-v1ref` byte-unchanged)
- `GlidingHarness.decompRun`: a **productive-phase readout** — finer early time-course sampling + windowed
  ⟨axial⟩/⟨transverse⟩ means and a per-cycle axial impulse over the first {0.5,1,2} ms. **No kernel, no physics, no
  force law touched** (pure post-loop arithmetic on the already-computed `decompose()` outputs). The settled-state
  numbers are byte-identical to the prior `-forcedecomp`.

## CPU-fallback disclosure
- **Part 1 velocity:** GPU **device-resident** `buildPlan` (~24 kernels, no per-step host pull; host reads
  fil.coord + boundSeg at the OUT_INT=100 sample cadence only), 3480 motors @ density 500 / 6960 @ density 1000.
  12 runs (config1+perphead × 500/1000 × 3 seeds), 40k steps each, sequential on the GPU.
- **Premise (`-diag`) + Part 2 (`-forcedecomp`):** CPU host-side (no TaskGraph) — diag is 2000 motors × 40k (~2
  min); forcedecomp is a single motor, sub-second.

## Reproduce
```
./run_gliding.sh -diag -config1  -v1box -dt 1e-6 40000        # premise: bound-in-ATP 0.0%, ADP 93.4%, +1.83 µm/s
./run_gliding.sh -diag -perphead -v1box -dt 1e-6 40000        # premise: bound-in-ATP 0.0%, ADP 93.9%, −1.38 µm/s
./run_gliding.sh -gpu -config1  -matbed -density 500  -dt 1e-6 -grid -seed 0 40000   # Part 1 (vary -density 500/1000, -seed 0/1/2)
./run_gliding.sh -gpu -perphead -matbed -density 1000 -dt 1e-6 -grid -seed 0 40000
./run_gliding.sh -perphead -forcedecomp                       # Part 2: perphead + config1 + default, settled + productive-phase + per-cycle impulse
```
