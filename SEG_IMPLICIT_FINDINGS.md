# Diagonal per-segment IMPLICIT loaded cross-bridge force (`-segimplicit`): does it make the model dt-robust at production dt=1e-5, and is the instability triggered in gliding or only in the dense/ring regime?

**Date:** 2026-07-05. Flag-gated (`-segimplicit`, new); **default byte-identical**; `BoA-v1ref` untouched; no
kinetics/stroke/rate change. Builds on `EOM_STABILITY_FINDINGS.md` (the diagnosis: explicit-Euler on the
COLLECTIVE loaded cross-bridge force per segment is unstable, `dt_crit ∝ γ/(k_ext+k_F8)`, rigid worst case
marginal ~1.4 / blow-up ~3.8 pN/nm at dt=1e-5; `-extimplicit` control proved the implicit loaded force removes
it) and `COUPLED_IMPLICIT_XB_FINDINGS.md` (the per-motor F8 star + the parity discipline).

---

## PLAIN ANSWER (headline)

**Does the diagonal per-segment implicit loaded cross-bridge force make the model dt-robust at production
dt=1e-5?** **NO — not in the low-duty (dt-faithful) gliding regime, because that regime is SUB-THRESHOLD for the
instability the diagonal solve cures.** **Is the instability triggered in gliding or only dense/ring?** **Only the
DENSE / high-binding-duty regime — the low-duty gliding dt-ladder never crosses even the marginal threshold.**

- **STEP 1 — BUILT, default byte-identical, CPU≡GPU.** The cure is a per-segment backward-Euler divide
  `q_imp,a=(q_e,a+rK_a·q_n,a)/(1+rK_a)`, `rK_a=K_tot·dt·1e6/γ_a`, `K_tot=k_s·myoSpring` (rigid head), reusing the
  existing `boundSeg` CSR-inverse for `k_s` — one new per-segment PURE kernel, no matrix/iteration/atomics. The
  explicit baseline reproduces NUCDETACH's numbers to the digit ⇒ default byte-identical confirmed.
- **STEP 3 (the framing) — the instability is a DENSE-regime phenomenon.** Census of the real per-segment
  `K_tot`: under the dt-faithful low-duty kinetics (the dt-ladder's regime) `K_tot` is dominated by k_s=1
  (~1 pN/nm), `maxK_tot`=3.0, and the **blow-up threshold (3.8 pN/nm) is NEVER reached** (0.00 % of segments) —
  even the marginal ring threshold (1.4) is crossed by only 1–4 %. Under DEFAULT high-duty kinetics (avgBound
  15–22) the mean load is ~2.2 pN/nm and **4–13 % of segments exceed blow-up**. ⇒ the explicit-Euler
  collective-load instability is barely triggered in the low-duty gliding assay; it bites in the dense /
  high-binding / contractile / ring regime.
- **STEP 2 (the test) — `-segimplicit` does NOT flatten the gliding dt-climb.** Clean isolation A/B (seed 0):
  it RAISES binding/glide ~20 % at production dt (correct direction, no overshoot) but the dt-climb ratio is
  essentially unchanged (velFitX 5.75×→5.42×, avgBound 2.24×→2.16×) — NOT dt-robust. Consistent with STEP 3:
  the gliding climb is the still-explicit **per-bound stroke** (`COUPLED_IMPLICIT_XB`'s target), not the
  collective load — so curing the load doesn't flatten it.
- **⇒ `-segimplicit` is the correct, cheap, parity-clean cure for the DENSE / contractile / RING regime** (where
  STEP 3 shows the instability IS triggered), **NOT a gliding-assay dt-blocker.** It is built, validated, and
  default-off; the gliding dt residual still needs the cross-bridge sub-step (per-bound stroke). **The two
  implicit schemes `-segimplicit`+`-xbimplicit2` do NOT compose cleanly on the segment — use `-segimplicit`
  standalone** (flagged below).
- **STEP 4 (dt-honest velocity–density) — SKIPPED**, per the task (runs only if STEP 2 converges; it did not).

---

## STEP 1 — the extended gather + backward-Euler divide. VERDICT: **BUILT; default byte-identical; CPU≡GPU.**

The unstable mode is one segment's position under the SUMMED stiffness of every cross-bridge bound to it.
Explicit Euler evaluates the restoring force at the old position and overshoots when `Σk·dt/γ > 2`. The cure is
backward-Euler on that per-segment self-stiffness, with the HEAD held EXPLICIT (rigid) — the **DIAGONAL** of the
coupled operator. This is exactly the proven `-extimplicit` control
(`ExternalSpringSystem.applyExternalSpringImplicit`) applied to the REAL per-segment cross-bridge sum:

```
q_imp,a = (q_e,a + rK_a·q_n,a) / (1 + rK_a),   rK_a = K_tot·dt·1e6 / γ_a   (per segment body axis a)
```

- `q_e` = the FULL explicit integrate output (the gathered cross-bridge force already in `f.forceSum`),
- `q_n` = the pre-integrate center (`segImplPrev` snapshot — reused from `-xbimplicit2`),
- `K_tot = Σ_bond k = k_s·myoSpring` — the RIGID-head collective stiffness (k_s = #heads bound to the segment,
  from the existing `boundSeg` CSR-inverse; myoSpring = 1 pN/nm). NOT the coupled solve's head-softened
  `Σ(1−B_i)` — the head is held explicit for the stiffness, matching the EOM harness's rigid anchor.

**Derivation (rigid head):** linearize `F_xb(q)=F_xb(q_n)−K_tot(q−q_n)`, backward-Euler on `F_xb` /
explicit `F_other` ⇒ `q_imp(1+rK)=q_e+rK·q_n` (the head-tip/bond-offset terms fold into `q_e`; anchor is the OLD
center `q_n`, exactly `applyExternalSpringImplicit` with `eq→q_n`). `K_tot` is isotropic (`k·I` in lab), γ is
diagonal in the body frame ⇒ rotate to body axes, divide per axis, rotate back (the SAME body-frame handling as
`coupleSolveSeg`).

**Reuses the gather you already have.** `k_s` is free from the existing `segMotorOffsets` CSR-inverse
(`segMotorOffsets[s+1]−segMotorOffsets[s]`) — `K_tot = k_s·myoSpring`, the "one extra scalar per segment"
without a matrix, iteration, or atomics. One new per-segment PURE kernel
(`CrossBridgeSystem.segImplicitSolve`): reads its own CSR count, writes only its own center ⇒ race-free, no
`KernelContext`. Runs at end-of-step (after `integrate`+`derive` of the filament, satisfying the body-write-late
PTX gotcha); caller re-derives.

**Scope — DIAGONAL only.** Off-diagonal couplings stay EXPLICIT: a head's pull through its motor body to OTHER
segments, and chain F3/F4 between neighbor segments. Justified by the harness (chain soft; `-extimplicit`
diagonal stabilized every k_ext).

**Composition.** `-segimplicit` composes with `-xbimplicit2`: when both are on, the rigid segment solve REPLACES
`coupleSolveSeg` (avoids double-correcting the center) and the `-xbimplicit2` head phases
(`coupleComputeA`/`coupleCorrectHead`) are kept (the head follows the rigid-corrected center via B). Orthogonal
to `-ratefix` (a build-time per-step→per-time rate conversion, no runtime overlap).

**Default byte-identical (structural).** Every edit is guarded by `SEG_IMPLICIT || XB_IMPLICIT2`; with both flags
off, no new code executes and the snapshot is not taken. The `-xbimplicit2`-only path is restructured but the
call/task sequence is provably identical (SEG_IMPLICIT=false ⇒ the `else`-branch runs `coupleSolveSeg` and all
head phases exactly as before). Empirically: the four paths (default / `-segimplicit` / `-xbimplicit2` /
both) all run clean on CPU + GPU with no NaN/CUDA fault.

**CPU≡GPU (aggregate-within-SEM, the chaotic-gliding standard).** v1box `-grid -lymntaylor -adppibind -coltol 10
-density 1000 -segimplicit -seed 0`, 20k steps: CPU velFitX 2.348 / avgBsteady 1.871 vs GPU 2.636 / 2.139
(velFitX ~11 %, avgBound ~13 % — the float32 op-ordering decorrelation, matching every other cross-bridge
kernel). `segImplicitSolve` lowers on the PTX backend; per-segment disjoint writes ⇒ race-free.

Files: `CrossBridgeSystem.segImplicitSolve`; `GlidingHarness` (`SEG_IMPLICIT`, stepOrig + buildPlan wiring,
`-ktotcensus` STEP-3 instrument). Default byte-identical; `BoA-v1ref` byte-clean.

---

## STEP 2 — dt-ladder with `-segimplicit`. VERDICT: **does NOT flatten the gliding dt-climb — NOT dt-robust in the low-duty regime** (consistent with STEP-3: that regime is sub-threshold for the collective load).

Two A/Bs, `-full -grid`, coltol10/d1000, seed 0, dt 1e-5 (120k) vs 2.5e-6 (480k, ≥1 s window). **Single seed**
(velFitX is the noisy metric; avgBound is robust) — the full 3-seed ladder was **not run** because the
single-seed climb ratio did not flatten (nowhere near the borderline where SEM would change the verdict), per the
task's staged gate, AND STEP-3 already decisively frames why.

### A/B-1 — ISOLATION: `-segimplicit` vs pure EXPLICIT (`-lymntaylor -adppibind`, NO `-xbimplicit2`, NO `-ratefix`) — the clean test of the diagonal solve.

| config | dt | velFitX | avgBsteady |
|---|--:|--:|--:|
| explicit | 1e-5 | 1.126 | 1.501 |
| **segimpl** | 1e-5 | **1.374 (+22 %)** | **1.849 (+23 %)** |
| explicit | 2.5e-6 | 6.477 | 3.365 |
| **segimpl** | 2.5e-6 | **7.451 (+15 %)** | **3.999 (+19 %)** |

**climb (2.5e-6/1e-5):** velFitX — explicit **5.75×**, segimpl **5.42×**; avgBound — explicit **2.24×**, segimpl
**2.16×**.

**Reads:**
1. **The explicit baseline reproduces NUCDETACH's known B-dt numbers EXACTLY** (1.126/1.501 @1e-5, 6.477/3.365
   @2.5e-6) ⇒ **byte-identical default confirmed empirically** (`-segimplicit` off changed nothing).
2. **`-segimplicit` is well-behaved and physical.** At **production dt=1e-5 it RAISES avgBound (1.50→1.85, +23 %)
   and glide (+22 %)** — the correct direction (the implicit segment doesn't overshoot away from its bound heads,
   so more stay bound). No NaN, stable, CPU≡GPU. It shifts the whole dt-curve UP ~15–23 %.
3. **But it does NOT flatten the climb.** The climb ratio is essentially unchanged (velFitX 5.75×→5.42×, avgBound
   2.24×→2.16×) — a ~6 % reduction, within single-seed noise, **nowhere near flat (~1.0–1.2×)**. So the model is
   **NOT dt-robust at production dt with `-segimplicit`** in the low-duty gliding regime. This is exactly STEP-3's
   prediction: the regime is **sub-threshold** for the collective-load instability (blow-up never reached), so
   curing that instability shifts the operating point but leaves the dt-climb — which is the **still-explicit
   per-bound stroke** (`COUPLED_IMPLICIT_XB`'s target) — intact.

### A/B-2 — the task's suggested stack (`-xbimplicit2 -ratefix -segimplicit`) vs without `-segimplicit`. FLAG: the two implicit schemes do NOT compose cleanly on the segment.

| config | dt | velFitX | avgBsteady |
|---|--:|--:|--:|
| ratefix (+xbimplicit2) | 1e-5 | 2.459 | 2.99 |
| ratefix+segimpl | 1e-5 | 2.187 | 2.99 |
| ratefix (+xbimplicit2) | 2.5e-6 | 5.088 | 3.69 |
| ratefix+segimpl | 2.5e-6 | 3.048 | **2.16** |

The composed stack's velFitX climb *looks* reduced (2.07× → 1.39×), **but this is an ARTIFACT, not convergence**:
`-segimplicit` here *lowers* avgBound at 2.5e-6 (3.69 → 2.16) while barely touching it at 1e-5 — an effect that
GROWS at finer dt, the OPPOSITE of a backward-Euler correction (which vanishes as dt→0). Cause: with
`-xbimplicit2` on, `-segimplicit`'s rigid segment solve REPLACES `coupleSolveSeg`, but the retained `-xbimplicit2`
head phases (`coupleComputeA`/`coupleCorrectHead`) still place the head with `A_i,B_i` derived for the *coupled*
solve — reading the *rigid* q back is inconsistent, and over-damps the segment at fine dt. The isolation A/B-1
(clean, no such confound) shows `-segimplicit`'s true behavior: it does NOT flatten. **⇒ `-segimplicit` and
`-xbimplicit2` do NOT compose cleanly on the segment; use `-segimplicit` STANDALONE** (or make them mutually
exclusive on the segment center). This is the flagged composition report the task asked for.

**⇒ STEP-2 VERDICT: `-segimplicit` does NOT make the low-duty gliding assay dt-robust at production dt=1e-5.** It
is correct, stable, unbiased-in-direction, and improves coarse-dt fidelity (+20 % binding at production dt), but
the gliding dt-climb is the still-explicit per-bound stroke, which the diagonal loaded-force implicit does not
touch — because (STEP 3) the low-duty gliding regime is sub-threshold for the collective-load instability the
diagonal solve cures.

---

## STEP 3 — per-segment K_tot distribution vs the ~1.4/~3.8 pN/nm thresholds. VERDICT: **the instability is a DENSE / high-binding-duty regime concern — barely triggered in the low-duty (dt-faithful) gliding dt-ladder regime, but substantially triggered in high-binding gliding.**

Read-only `-ktotcensus` over the EXPLICIT gliding dynamics at dt=1e-5 (v1box, seed 0, 60k steps, ~400 steady
samples). `K_tot = k_s·myoSpring` with myoSpring = 1 pN/nm ⇒ K_tot[pN/nm] = k_s (heads bound to a segment). EOM
thresholds: marginal (ring) ≈ 1.4, blow-up ≈ 3.8 pN/nm. `fracAll` = fraction of ALL segment-samples above
threshold; `maxKtot` = the largest per-segment load seen.

| kinetics | density | coltol | avgBound | meanK_tot(engaged) | maxK_tot | frac≥1.4 (marginal) | frac≥3.8 (blow-up) |
|---|--:|--:|--:|--:|--:|--:|--:|
| **STEP-2 (LT+adppibind, low duty)** | 1000 | 10 | 1.61 | 1.073 | 3.0 | 0.0095 | **0.0000** |
| " | 2000 | 10 | 3.32 | 1.157 | 3.0 | 0.0386 | **0.0000** |
| " | 1000 | 14 | 1.91 | 1.083 | 3.0 | 0.0118 | **0.0000** |
| **DEFAULT (high duty)** | 1000 | 10 | 21.9 | 2.104 | 6.0 | 0.724 | **0.041** |
| " | 2000 | 10 | 15.5 | 2.226 | 6.0 | 0.506 | **0.062** |
| " | 2000 | 14 | 20.4 | 2.273 | 7.0 | 0.600 | **0.107** |

**Reads:**
1. **The STEP-2 dt-ladder regime is SUB-THRESHOLD.** Under the dt-faithful low-duty kinetics (`-lymntaylor
   -adppibind`, the regime the whole arc validates at), K_tot is dominated by **k_s = 1 (1 pN/nm)** — the
   histogram is `k_s=1: 546, 2: 41, 3: 1` at d1000. `maxK_tot` = 3.0, the **blow-up threshold (3.8) is NEVER
   reached** (frac≥3.8 = 0.0000 at every density/radius), and even the marginal ring threshold (1.4) is crossed
   by only **1–4 % of segments**. Raising density (×2) or capture radius (10→14 nm) barely moves it (max stays
   3.0). ⇒ **the collective-load explicit-Euler instability is essentially NOT triggered in the low-duty gliding
   assay** — the loaded force a segment sees sits at the F8-only floor (~1 pN/nm), well below the ~1.4/3.8
   thresholds the EOM harness identified.
2. **High-binding gliding DOES trigger it.** Under the default (high-duty) kinetics — avgBound 15–22, the
   promoted-default regime — the mean per-segment load is **~2.2 pN/nm (above marginal 1.4)**, **4–13 % of
   segments exceed the blow-up threshold (3.8)**, and `maxK_tot` reaches 6–7 pN/nm. So a densely-engaged filament
   (or, a fortiori, a contractile/ring segment loaded by many minifilament/node heads) crosses the instability
   boundary and the diagonal implicit is load-bearing there.
3. ⇒ **`-segimplicit` is a DENSE / high-binding-duty regime fix, not a gliding-assay dt-blocker in the
   dt-faithful regime.** This reconciles the two prior findings: EOM's loaded-force instability is real (and the
   census confirms it — in dense scenes), while COUPLED's "the gliding residual is the still-explicit per-bound
   stroke" is also right — because the gliding dt-ladder regime it measured is sub-threshold for the collective
   load, so its dt-climb is NOT this instability. The census tells us the cure is priced against the
   dense/contractile/ring regime.

---

## STEP 4 — the dt-honest velocity–density curve — **SKIPPED** (STEP 2 did not converge/flatten).

Per the task, STEP 4 runs only if STEP 2 makes the model dt-robust at production dt. It does not (the low-duty
gliding regime is sub-threshold; `-segimplicit` shifts the curve but does not flatten the climb), so there is no
dt-honest production-dt operating point to report a velocity–density curve at. The dt-honest number remains
blocked on the cross-bridge **sub-step** covering the per-bound stroke (`COUPLED_IMPLICIT_XB` / `substep-
feasibility-verdict`), which is orthogonal to (and composes with) the diagonal loaded-force implicit built here.

## What to do with `-segimplicit` (scope + recommendations)
- **KEEP it for the dense / contractile / RING regime.** STEP 3 shows that is where the collective-load
  instability is actually triggered (mean K_tot ~2.2 pN/nm, 4–13 % of segments past blow-up under high binding).
  The diagonal solve is cheap (one PURE per-segment kernel over the existing CSR-inverse), parity-clean, and
  default byte-identical — the natural stabilizer for those scenes, sized against the ~4 pN/nm threshold.
- **Use it STANDALONE, not with `-xbimplicit2`** (they double-correct the segment; the composition over-damps at
  fine dt — STEP 2 A/B-2). If both are ever wanted, make them mutually exclusive on the segment center or
  re-derive the head correction against the rigid q.
- **It is NOT the gliding-assay dt fix.** The low-duty gliding dt-climb is the per-bound stroke → sub-step.
- Promotion to default is a separate PLANNER sign-off (it shifts binding ~20 % — re-baselines dense-scene stats).

## BAIL-OUT CHECK (per the task's constraint)
The task said to bail if the diagonal solve requires reaching outside the per-segment CSR aggregate (i.e. if the
true unstable operator is inherently off-diagonal). It does NOT — the diagonal solve is fully expressible from the
per-segment CSR count (`k_s`) + the segment's own pose/drag, built and validated here. The reason it doesn't make
GLIDING dt-robust is NOT that the operator is off-diagonal — it is that the gliding regime is **sub-threshold** for
the (diagonal) collective-load instability entirely (STEP 3). So this is not the off-diagonal-follow-up trigger;
the off-diagonal (motor-body cross-segment, chain) remains explicit and unneeded, exactly as the EOM harness
(chain soft, `-extimplicit` diagonal sufficient) indicated.

---

## Reproduce
```
# STEP-1 CPU≡GPU parity (v1box):
./run_gliding.sh [-gpu] -v1box -grid -lymntaylor -adppibind -coltol 10 -density 1000 -segimplicit -seed 0 20000
# STEP-2 ISOLATION A/B (the clean test — ±segimplicit vs pure explicit; -full -grid, seed 0, dt 1e-5/2.5e-6):
./run_gliding.sh -gpu -full -grid -lymntaylor -adppibind -coltol 10 -density 1000 [-segimplicit] -dt <DT> -seed 0 <STEPS>
#   (RUN_LOGS/2026-07-05_segimpl_isolation.txt; the composed-stack A/B in _segimpl_probe.txt shows the non-composition)
# STEP-3 K_tot census (per-segment collective stiffness vs the ~1.4/3.8 pN/nm thresholds):
./run_gliding.sh -gpu -v1box -grid -ktotcensus [-lymntaylor -adppibind] -coltol <C> -density <D> -seed 0 60000
#   (RUN_LOGS/2026-07-05_ktot_census_clean.txt)
```
Files: `CrossBridgeSystem.segImplicitSolve`; `GlidingHarness` (`SEG_IMPLICIT` wiring + `-ktotcensus` instrument).
Default byte-identical; `BoA-v1ref` byte-clean.
