# Viscosity diagnostic — is the promoted motor's ~3 µm/s glide cycle-limited or drag-limited?

**Date:** 2026-07-02. Flag-gated param sweep (`-aeta <Pa·s>`, **default 0.1 ⇒ byte-identical by
construction**) on the promoted default motor. **No default changed; no release/stroke/kinetics change;
`BoA-v1ref` untouched.** GPU device-resident `-full` bed (13.37×2 µm, ~26 740 motors), **d1000**, dt 1e-5
unless noted, the LS-centroid `LONG_ROW` estimator (`PROPER_SPEED_ANALYSIS.md`). The mat is seedable
(`-seed 0/1/2`) ⇒ sweep spreads are over 3 distinct mat draws. Raw logs: `RUN_LOGS/2026-07-02_viscosity_probe.txt`,
`…_sweep.txt`, `…_dtconfirm.txt`, `…_dtcontrol.txt`.

**This is a DIAGNOSTIC, not a speed knob.** η = aeta = 0.1 Pa·s is a **physical** parameter; the sweep does
**not** adopt a changed η to hit a number — it only asks which regime the glide is in.

---

## VERDICT — the glide is CYCLE / TUG-OF-WAR-limited, NOT drag-limited.

Lowering the filament/medium viscosity does **not** scale the glide up the way a drag-limited regime
demands (`v ∝ 1/η`). At matched numerical stability the glide's viscosity response is **weak and strongly
sublinear** — `v ∝ η^-0.18` over the clean ×0.5 range (drag-limited would be `η^-1`, i.e. +100 % for 2×
lower η; observed **+14 %**). Lower viscosity acts almost entirely by **recruiting more bound heads**, whose
along-filament forces then **mutually cancel** (per-bound drift collapses), so the net glide stays pinned in
a narrow ~2–3.5 µm/s band. **The ~3 µm/s is the motor's real operating glide, not a drag artifact hiding a
faster V₀.** Filament drag is not eating the missing speed — the **co-bound tug-of-war** is. This is the
**5th independent lever** (after density, neck angle, cycle rate, capture radius) to hit the same
`net ≈ avgBound × per-bound-drift` ceiling.

**Secondary finding surfaced by the diagnostic (a dt-convergence flag, not a viscosity result):** the
production operating point **dt = 1e-5 is under-converged in avgBound** — refining dt 2× at fixed viscosity
**quadruples** avgBound (14.8 → 56.7) and *lowers* the glide to ~2.2 µm/s (more heads ⇒ deeper tug-of-war).
The ~3.0 at dt=1e-5 is partly propped up by numerical under-binding. **The regime verdict is robust to this:**
at both the under-converged 3.0 and the more-converged 2.2, the glide is tug-of-war-pinned and
viscosity-insensitive. Reinforces `dt-faithful-ceiling` and the cross-bridge sub-step direction.

---

## STEP 1 — the η sweep at the production dt=1e-5 (3 mat draws): η↓ *lowers* the glide (whip regime)

| aeta (×base) | net \|v_axial\| (mean ± SD) | avgBound | per-bound drift | inst (jitter) | axialFrac | n(clean) |
|--:|--:|--:|--:|--:|--:|--:|
| **0.10 (×1)** | **3.07 ± 0.12** | 14.9 | 0.206 | 6.3–6.6 | 0.99–1.00 | 3 |
| **0.05 (×0.5)** | **0.96 ± 0.35** | 4.20 | ~0.23 | 9.9–10.5 | 0.93–1.00 | 3 |
| **0.025 (×0.25)** | ~0.25 (unbound noise) | 0.15 | — | 13.5–16.0 | 0.12–0.64 | 3 |

(Per-draw: aeta0.10 v=3.023/3.225/2.951, avgB=14.77/14.57/15.38; aeta0.05 v=1.446/0.737/0.682,
avgB=4.11/3.99/4.49; aeta0.025 v=0.334/0.018/0.392, avgB=0.24/0.03/0.17. All fullMat=YES.)

1. **η↓ does NOT raise the glide — it collapses it.** Over ×1→×0.25 the net glide falls **−69 % → −92 %**,
   driven by an avgBound collapse **14.9 → 4.2 → 0.15**. At ×0.25 the filament is essentially **unbound**
   (avgBound 0.15, axialFrac 0.12–0.64 ⇒ the "velocity" is undirected drift noise, not a glide).
2. **This is a dt-instability (whip) artifact, not physics.** At fixed dt=1e-5 the filament's per-step
   displacement scales as `1/γ ∝ 1/aeta` (deterministic) and `1/√γ` (Brownian) — the **jitter speed rises**
   6.3 → 16.0 as η falls. Lower drag lets the filament translate too far per step, over-stretching the
   cross-bridge of a bound head faster than the 1e-5 step can track ⇒ the force-dependent catch-slip / 12 pN
   cap fires ⇒ the head detaches. This is exactly the `dt-faithful-ceiling` mechanism: **rescaling drag down
   ≡ raising the effective step** for the explicit cross-bridge. No NaN, but binding is destroyed.
3. So the naive dt=1e-5 sweep is **confounded** — lowering aeta simultaneously reduces numerical stability
   (`dt/aeta` falls 1e-4 → 2.5e-5), so it cannot separate a viscosity effect from a whip artifact. The
   task's flagged outcome ("if lowering η introduces whip … that itself is a regime signal") — and it says
   the glide is **not** drag-limited (a drag-limited glide would *rise* as η falls; this one collapses).

## STEP 2 — the dt-co-scaled series (hold `dt/aeta` = 1e-4 ⇒ constant cross-bridge stability): the clean viscosity test

Holding the stability-relevant ratio `dt/aeta` fixed keeps the integrator equally faithful while preserving
the physical lower-viscosity diffusion (`D = kT/γ ∝ 1/aeta` over a fixed physical time). seed0, 1.5 s each:

| aeta | dt | net \|v_axial\| | avgBound | per-bound drift | fullMat |
|--:|--:|--:|--:|--:|:--|
| 0.10 | 1e-5 | 3.02 | 14.8 | 0.204 | YES (baseline) |
| 0.05 | 5e-6 | **3.43** | **28.4** | 0.121 | YES |
| 0.025 | 2.5e-6 | (4.62)\* | 34.3 | (0.135) | **VIOLATED** (v excluded) |

(\* aeta0.025 grazed the bed edge — marginY_lo −0.002 — so its velocity is edge-corrupted and excluded per
the standing coverage rule; avgBound, a low-variance readout, is kept.)

1. **Proves the STEP-1 collapse was numerical.** With the step made faithful, the ×0.5 binding doesn't just
   recover from the whipped 4.1 — it reaches **28.4**, ~2× the baseline. The dt=1e-5 sweep's avgBound crash
   was a pure dt-artifact, as predicted.
2. **The cycle/tug-of-war signature.** Halving viscosity (at constant stability) **doubles avgBound**
   (14.8 → 28.4) yet the net glide is **flat (+14 %)**; per-bound drift **halves** (0.204 → 0.121). The
   invariant `net ≈ avgBound × per-bound-drift` holds under the viscosity knob: lower drag recruits more
   co-bound heads that cancel. Scaling exponent **`v ∝ η^-0.18`** (clean ×0.5) — nowhere near drag-limited
   `η^-1`. Including the edge-violated ×0.25 gives at most `η^-0.31`; still far below 1.

## STEP 3 — the dt-only control (aeta=0.10 fixed, refine dt): disentangles the STEP-2 avgBound rise

The STEP-2 faithful runs co-vary dt **and** aeta. A dt-only control (baseline viscosity at the refined dt)
separates them — and exposes a convergence issue at the operating point:

| aeta | dt | `dt/aeta` | net \|v_axial\| | avgBound | per-bound drift | fullMat |
|--:|--:|--:|--:|--:|--:|:--|
| 0.10 | 1e-5 | 1e-4 | 3.02 | 14.8 | 0.204 | YES (operating point) |
| 0.10 | 5e-6 | 5e-5 | **2.20** | **56.7** | 0.039 | YES |

1. **The operating dt=1e-5 is NOT converged in avgBound.** Refining dt 2× at the *same* viscosity
   **quadruples** avgBound (14.8 → 56.7): the 1e-5 cross-bridge overshoot numerically detaches ~¾ of the
   physically-bound heads. (5e-6 is "more converged," not necessarily fully — the true converged avgBound
   is ≥57 and the converged glide ≲2.2.)
2. **More heads ⇒ LOWER glide — the tug-of-war, unmistakably.** Quadrupling avgBound (via finer dt) drops
   the glide 3.02 → 2.20 and craters per-bound drift 0.204 → 0.039. Whether the extra heads come from finer
   dt (this control) or lower η (STEP 2), the net glide is pinned by co-bound cancellation.
3. **Re-attribution of STEP 2.** The avgBound rise in the faithful series is part dt-refinement, part a real
   viscosity effect (a less viscous medium lets the filament yield compliantly, so bonds over-stretch less
   and survive longer). Both raise head count; the tug-of-war absorbs it either way. **The drag-limited-vs-
   cycle-limited verdict does not depend on the split** — the glide is viscosity-insensitive at every
   convergence level tested.

---

## Synthesis — which regime, stated plainly

- **NOT drag-limited.** The glide does not scale with `1/η` (`v ∝ η^-0.18`, vs `η^-1` for drag-limited).
  There is no faster V₀ hidden behind filament drag; reducing the drag mostly recruits more mutually-
  cancelling co-bound heads. **~3 µm/s is the motor's real operating glide, not a drag artifact.** The speed
  question is closed on the physics side: the ceiling is the co-bound **tug-of-war**, not the medium.
- **Is the tug-of-war ceiling itself drag-mediated?** Partly, but not exploitably. Lower filament drag *does*
  let each head move the filament a little more (the +14 %, the small `η^-0.18` residual) and *does* raise
  engagement — but the added engagement deepens the cancellation, so the net barely moves. Breaking the
  ceiling needs raising per-head **directed** efficiency without adding co-bound cancellation (the stroke
  mechanism / reducing mutual resistance), not lowering viscosity.
- **The `net ≈ avgBound × per-bound-drift` invariant is now confirmed by a 5th independent lever**
  (viscosity), across a 4× viscosity range *and* a 2× dt-convergence range: the glide stays in a ~2.0–3.5
  band while avgBound ranges 15 → 57.

## Flag for the planner (dt-convergence study — the branch this sits on)
The production **dt=1e-5 under-binds ~4×** vs the dt-converged avgBound (14.8 vs ≥57 at aeta=0.1), and the
converged glide is **~2.2 µm/s, not 3.0** (more heads ⇒ deeper tug-of-war). The prior Phase-2 lever numbers
(density/angle/rate/radius, all at dt=1e-5, ~3.0) are at this **under-converged** operating point — the
tug-of-war *conclusion* strengthens with convergence, but the **absolute ~3.0 is dt-sensitive**. A converged
avgBound requires **sub-stepping the cross-bridge** (`substep-feasibility-verdict`, `dt-faithful-ceiling`);
naïvely refining dt globally is prohibitive at production scale. Recommend a converged re-baseline of the
operating glide once the cross-bridge sub-step lands.

## Runs (for the record)
```
GlidingHarness -gpu -full -grid -density 1000 -aeta <0.1|0.05|0.025> -seed <0..2> 150000          # STEP 1 (dt=1e-5)
GlidingHarness -gpu -full -grid -density 1000 -aeta 0.05  -dt 5e-6   -seed 0 300000               # STEP 2 (dt/aeta held)
GlidingHarness -gpu -full -grid -density 1000 -aeta 0.025 -dt 2.5e-6 -seed 0 600000               # STEP 2 (edge-violated)
GlidingHarness -gpu -full -grid -density 1000 -aeta 0.1   -dt 5e-6   -seed 0 300000               # STEP 3 (dt-only control)
```
**Validation.** *Default byte-identical:* `-aeta` scales the FILAMENT drag tensors only, via the
FDT-consistent `applyAeta` (`bTransGam`/`bRotGam` ×r, `bTransDiff`/`bRotDiff` ×1/r; BrownianForceSystem
derives its amplitude from the drag ⇒ FDT preserved); `AETA == Constants.aeta` ⇒ `r == 1.0` early-returns ⇒
a no-flag run is untouched (verified: aeta=0.1 reproduces the d1000 seed-0 baseline −3.023/14.77 exactly).
*Isolation-clean:* filament drag only — motor sub-body drag + head kinetics UNTOUCHED, so the sweep tests
exactly whether **filament** drag caps the glide. *dt-stability:* no NaN anywhere; the dt=1e-5 low-η
"collapse" is the avgBound-whip (documented as the regime signal), not a blow-up. `BoA-v1ref` byte-clean.

New flag: `-aeta <Pa·s>` (filament/medium viscosity; default 0.1 ⇒ byte-identical). Diagnostic only,
**no promotion** — η is a physical parameter, not a speed dial.
