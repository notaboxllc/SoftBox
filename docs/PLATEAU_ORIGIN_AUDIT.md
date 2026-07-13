# PLATEAU_ORIGIN_AUDIT — what maintains the bound-ADP forward F8-strain prestress that sets V₀?

> **⚠ CORRECTION (2026-07-12, `CANONICAL_STROKE_DISAMBIGUATION.md`) — the "F9-dominant stroke" framing below is
> WRONG and retracted.** This audit's `plateauBalance()` diagnostic used a nucleotide-switched F9 target
> (`cocked ? 120° : 90°`), but the live SPHEREHEAD production kernel **freezes F9 at a fixed 90°** (`xbParams[9]=
> f9Frozen=1`, nucleotide-INDEPENDENT — a ⊥-maintainer, NOT a stroke). Verified against the live `bondData`
> (recompute at 90° matches to 0.0000 pN·nm; 120° is off by 52). **Corrected: T9 ≈ 24 pN·nm (not 62), F9 dev ≈ −5.9°
> (not −35°).** So F9 is **not** the dominant torque and **not** a stroke — the head counter-torque to F8 is
> **F9-⊥ (≈24) + AXLOCK (≈23) co-dominant**, and the **single nucleotide-triggered power stroke is DIRSWING** (lever
> 0°→60°). **What SURVIVES this doc:** the audit's *structural* conclusions are unchanged — the prestress is
> **constraint-set** (re-emerges across neck angles), **thermal-scale** (~2.3 kT), a **frustrated** residual with the
> head near perpendicular, **AXLOCK is load-bearing** for the forward directedness (now clearly *co-dominant*, not
> second to F9), and the three licensed fidelity levers (AXLOCK plane / dt-stiff alignment torques / free J1-J2 hinges)
> stand. Read every "F9 stroke / F9 dominant" statement below as "**F9 ⊥-maintainer, co-dominant with AXLOCK**"; the
> dominant-torque tables are regenerated with the fixed diagnostic in `RUN_LOGS/2026-07-12_plateau_grid_f9fixed.txt`.


**Date:** 2026-07-12 · **Branch:** dt-convergence-study · **Runner:** CPU deterministic (velocity-clamp arbiter).
`BoA-v1ref` untouched. **AUDIT / LOGGING ONLY — no mechanism build, no constraint change, no calibration.** The
plateau balance is **pose-derived** (re-computes the exact force-law torques of the canonical stack from the body
pose; touches no kernel) ⇒ `-epkernel` stays default-off **byte-identical** (FVROW verified char-for-char; the PART-1
kernel completeness identity is unchanged, idErr ≤ 5e-15). Builds on `EPISODE_KERNEL_ACCOUNTING.md`.

> **BOTTOM LINE — the prestress is a CONSTRAINT-SET, thermal-scale, FRUSTRATED-stroke residual; largely OUTCOME 1
> (honest emergent mechanics) with three named idealization caveats that are the licensed fidelity-preserving levers.**
> The plateau forward strain is **constraint-set, not stroke-set** (decisive: the entire torque balance re-emerges
> essentially unchanged across a **1.9× nominal neck-swing change**, neck 40→80°). The **dominant torque is F9** — the
> nucleotide rest-angle switch (90°→120°, the *modeled power stroke*), T9 ≈ 62 pN·nm — balanced by the F8 restoring
> torque (TH ≈ 33) + the **AXLOCK** axial-plane lock (hF10 ≈ 23) + a nearly-completed swing (≈ 6). It is **NOT a single
> idealized artifact** (the dominant term is a physical mechanism) and **NOT a large artificial preload** (F8 stores only
> **~2.3 kT**; the forward axial component is **0.82 pN of a mostly-transverse 3.95 pN strain**). Crucially the F9 stroke
> is **largely FRUSTRATED** — the head equilibrates near perpendicular (~85°, **not** the 120° target) because F8
> (the head is *bound to actin*) + AXLOCK + the **free J1/J2 rotational hinges** absorb it; hence d_relax ≈ 0.7 nm ≪
> d_peak ≈ 6 nm. So V₀ ≈ 16 is a **fairly honest consequence of the modeled stroke mechanics**, and the calibration lever
> stays the **catch-slip shape** (`VMAX_SENSITIVITY_25C.md`), not a smoking-gun prestress to delete. **Three
> idealizations shape the residual and are the licensed next experiments (named, NOT built):** (A) the **AXLOCK**
> lab-`n̂=+Z` axial-plane lock (a gliding-assay-specific idealization, 37 % of the head counter-torque, co-sets the
> forward directedness); (B) the **dt-dependent fracMove alignment torques** (F9/AXLOCK ∝ 1/dt ⇒ the plateau *magnitude*
> at production dt is not dt-converged — ties to the dt-convergence line); (C) the **free J1/J2 rotational hinges**
> (angular springs off — the lever-arm does not transmit/hold the stroke, so the effective working displacement is
> intrinsically small). **Framing correction honored:** m_f is survival-conditioned; nothing below re-asserts a causal
> mechanics/kinetics split — PART 3 shows xCatch moves V₀ *through* the survivor-conditioned waveform.

---

## Method — the plateau-band force/torque/energy balance (`-epkernel` extension)

Extends the age-resolved episode kernel to re-compute, per bound head **in the plateau age band a ∈ [0.30, 0.50] ms**
(survivor-averaged), the full mechanical balance that *maintains* the residual forward F8 strain — using the **exact
force-law formulas** of the canonical stack (`bondForces` F8/F9/F10-AXLOCK, `directedSwing`, `MotorJointSystem` J1/J2,
`TailAnchorSystem`), read from the current pose. Pose-derived ⇒ no kernel touched ⇒ byte-identical. Self-consistency:
the recomputed head torques sum to a near-zero net (O(10) ≪ the individual O(60) terms) = the expected quasi-static
plateau equilibrium; and the recompute reproduces jointParams exactly (J1 angular converter **off**, jointParams[3]=0,
replaced by DIRSWING — confirmed against `sc.jointParams`). Grid: **v = 0 and 16 µm/s × neck angle 40/60/80° × seeds
0–3**, d2000 `-matbox 50`, 12 000 steps. Torques in pN·nm, forces pN, angles deg, F8 energy in kT.

---

## PART 1 — the plateau balance (which constraint holds the forward strain?)

Survivor-averaged over seeds (v = 0; the v = 16 column shows the ceiling):

| quantity | neck 40° | **neck 60°** | neck 80° | v=16 (neck 60°) | reading |
|---|---:|---:|---:|---:|---|
| **F8 \|F\| (pN)** | 3.95 | **3.99** | 4.00 | 3.91 | **re-emerges** (flat across 1.9× swing) |
| **F8 axial (pN)** | 0.71 | **0.82** | 0.93 | **0.09** | the V₀-setter; small, mostly-transverse; →0 at v=16 |
| **F8 energy (kT)** | 2.24 | **2.28** | 2.30 | 2.19 | **thermal-scale** prestress, not a large preload |
| **TH — F8 restoring torque** | 32.2 | **32.7** | 33.2 | 31.8 | balances F9-⊥ + AXLOCK |
| **T9 — F9 (⊥-maintainer @90°)** ⚠corrected | 23.5 | **23.7** | 23.8 | 22.0 | ⊥-maintainer, **co-dominant with AXLOCK** (was buggy 62) |
| **hF10 — AXLOCK** | 23.4 | **23.2** | 22.8 | 23.2 | **re-emerges**, co-dominant with F9-⊥ |
| **Tj1 — J1 angular** | 0.0 | **0.0** | 0.0 | 0.0 | **off** (canonical DIRSWING replaces it) |
| **Tsw — swing (DIRSWING = the stroke)** | 8.2 | **6.0** | 4.9 | 5.9 | small at plateau (nearly complete); only term tracking neck |
| **F9 dev (from fixed 90°)** ⚠corrected | −6.1 | **−6.7** | −7.5 | −1.6 | small ⊥ wobble (was buggy −35° from a wrong 120° target) |
| **AXLOCK dev** | 25.8 | **25.5** | 25.1 | 25.6 | actively straining (re-emerges) |
| **J1 dev (from 60°)** | −16.6 | **+5.9** | +26.8 | +2.3 | tracks neck but **no restoring torque** ⇒ inert |
| **J2 dev (from 96°)** | 21.0 | **23.0** | 22.5 | 23.6 | bent, but **free hinge** (fracMoveTorq=0) |
| **anchor (nm)** | 7.30 | **7.28** | 7.30 | 7.39 | modest, bounded |

**The plateau is CONSTRAINT-SET, not stroke-set (decisive).** Across a **1.9× change in the nominal neck stroke**
(`2·L·sin(θ/2)`, sin20°→sin40°), the F8 magnitude, the F9 torque, the AXLOCK torque, and both rest-deviations are
**flat**. The **only** quantities that track neck angle are `Tsw` (the residual swing torque, the *smallest* term) and
`devJ1` (the lever–head angle — which has **no restoring torque**, so it stores nothing). This is the torque-level
confirmation of the kernel's neck-angle disconnection.

**The head counter-torque to F8 is F9-⊥ + AXLOCK co-dominant** ⚠(corrected — this paragraph originally mis-said
"dominant F9 stroke, T9≈62"; see the banner). T9 ≈ **24 pN·nm** (F9 as the **fixed-90° ⊥-maintainer**, `f9Frozen=1`,
NOT a stroke) ≈ AXLOCK (23) + swing-remnant (6), together balancing TH (F8 restoring, 33). **The sole nucleotide-
triggered stroke is DIRSWING (lever 0°→60°), not F9.** F9-⊥ and AXLOCK are both physical/idealized constraints, not
strokes ⇒ no single idealized constraint solely imposes the prestress.

**The DIRSWING stroke's tip advance is largely ABSORBED** ⚠(corrected framing — NOT "F9 frustrated at 85° vs 120°":
F9's real target is 90° and the head sits ~84°, i.e. F9 is nearly *satisfied*). The head stays near perpendicular and
the DIRSWING lever swing largely completes (devSw ≈ 8°), but the tip (bound to actin via F8) barely advances because the
swing recoils into the free J1/J2 hinges + the F8 anchor. **The nominal stroke's tip advance is ~fully absorbed** — the
residual is a small forward strain (**0.82 pN axial of a 3.95 pN,
79 %-transverse** F8), storing only **~2.3 kT**. So the prestress is **modest and thermal-scale**, not a large
artificial preload.

**The forward directedness (the V₀-setter) is co-imposed by the directional idealizations.** The 0.82 pN forward
projection's *sign* comes from **DIRSWING** (the polarity-directed swing — physical) confined to the axial plane by
**AXLOCK** (`ŝ = n̂bed × seg.u`, `n̂bed` = lab +Z — a gliding-assay-specific idealization). Per
`axial-lock-necessary-not-sufficient`, the lock alone is null and DIRSWING is what makes the glide directed; AXLOCK
confines the plane. AXLOCK carries **37 %** of the head counter-torque and sits 25° off-target (load-bearing).

---

## PART 2 — where does the ~5.3 nm of stroke go, and is each compliance defensible?

**The same fact from the displacement side.** The kernel showed d_peak ≈ 6 nm relaxing to **d_relax ≈ 0.7 nm within
~5 steps**. PART 1 names where it goes:

- **Head reorientation → ~0.** F9's 30° nominal reorientation (90°→120°) is **fully absorbed**: the bound head stays
  ~perpendicular (85°). The rotational component of the stroke does no net tip advance because the head is *anchored to
  actin by F8* and resists — **physical** (a bound head really does resist reorientation).
- **Lever/rod swing → free-hinge counter-rotation.** The swing torque rotates the lever, but **J1 and J2 have their
  angular springs OFF** (jointParams[3]=[7]=0 — J1 replaced by DIRSWING, J2 free per v1). With no rotational stiffness
  the lever and rod **counter-rotate freely** to accommodate the head's motion instead of driving the tip against the
  F8 load. This is the dominant **displacement sink**.
- **Anchor → 7.3 nm.** The tail anchor (a fracMove position spring) gives 7.3 nm — modest, bounded.
- **Net surviving tip advance → 0.7 nm** (the small head-center translation that survives the free-hinge/anchor
  absorption).

**Defensibility of each compliance:**

| compliance | value | physically justified? |
|---|---|---|
| **F8 head-anchoring** (resists reorientation) | TH 33 pN·nm | **YES** — a bound head really resists; physical. |
| **free J1/J2 rotational hinges** | Tj1=Tj2=0 | **QUESTIONABLE** — the lever-arm has *no* rotational stiffness, so it cannot transmit/hold the stroke (a real lever arm is a stiff transmitter). v1-faithful (J2) + the DIRSWING design (J1), but this is why the stroke is not held. **The compliance most worth scrutiny.** |
| **F9 / AXLOCK fracMove alignment torques** | ∝ 1/dt | **dt-DEPENDENT** (the dt-stiffness family) — the plateau *magnitude* at production dt=1e-5 is not dt-converged. Numerical, not physical, in origin. |
| **AXLOCK reference plane** | lab n̂=+Z | **IDEALIZED** — a flat-coverslip gliding-assay reference; would not transfer to a 3D network. |
| **tail anchor** | 7.3 nm | **OK** — bounded fracMove spring. |

**Fidelity flag (per GPT's caution — report the trajectory, not a scalar).** The sustained working displacement
d_relax ≈ 0.7 nm is **far below** the ~5–8 nm biological working stroke, but d_peak ≈ 6 nm is *in range* — so **neither
scalar is "the working stroke."** The honest statement is the **trajectory**: a ~6 nm transient that the compliant
(free-hinged + F8-anchored) network relaxes ~85 % of within ~5 steps. Because the model's bound head **does not hold**
its stroke, both the plateau prestress magnitude and the effective working displacement are **quantitatively suspect**
(dt-un-converged, free-hinge-dependent), even though the **qualitative** forward-strain mechanism is sound.

---

## PART 3 — the paired per-seed xCatch ΔV₀ (statistical debt, analysis-only)

From the existing `EPISODE_KERNEL_ACCOUNTING` grids, per-seed V₀ (f̄_avail zero-crossing), baseline vs `-xcatch 1.0`:

| seed | baseline V₀ | xCatch V₀ | ΔV₀ |
|---:|---:|---:|---:|
| 0 | 14.26 | 7.55 | −6.71 |
| 1 | 15.76 | 6.12 | −9.64 |
| 2 | 18.87 | 8.23 | −10.63 |
| 3 | 16.36 | 8.74 | −7.62 |

**Paired ΔV₀ = −8.65 ± 0.90 (SEM), paired bootstrap 95 % CI [−10.14, −7.17]** (all 4 seeds strongly negative). The
effect dwarfs the seed SD (1.80); the CI excludes zero cleanly. *(Consistent with the point −8.2; xCatch moves V₀
**through** the survivor-conditioned force waveform — it releases back-strained/resistive heads faster, reshaping which
heads remain in `m_f(a,v)`. Mechanics and force-selective release stay coupled, per the framing correction.)*

---

## The three outcomes → verdict

- **Outcome 1 — emergent & largely defensible: SELECTED (primary).** The prestress re-emerges across a 1.9× neck-swing
  change (constraint-set), is dominated by a *physical* mechanism (the F9 rest-angle-switch stroke), is **thermal-scale**
  (~2.3 kT, 0.82 pN axial), and is a **frustrated** residual (the head cannot reorient because it is bound) — i.e. a
  fairly honest consequence of the modeled actomyosin mechanics. No single idealized constraint solely imposes it, and
  it is not a large artificial preload whose deletion would collapse V₀.
- **Outcome 2 — one idealized constraint: PARTIAL rider (named).** The **AXLOCK** axial-plane lock (lab `n̂=+Z`) is a
  gliding-assay-specific idealization that is **load-bearing** (37 % of the head counter-torque, 25° off-target) and
  co-sets the **forward directedness**. It is the cleanest single fidelity-preserving lever, but it is *not* the sole
  cause (F9 dominates the magnitude). **Licensed next experiment (NOT built):** derive the lock plane from the actin
  geometry / make it compliant, and remeasure V₀.
- **Outcome 3 — over-compliant network: PARTIAL rider (named).** The **free J1/J2 rotational hinges** let the stroke
  recoil (the lever-arm does not hold it → d_relax ≈ 0.7 nm), and the **fracMove F9/AXLOCK torques are dt-dependent**
  (magnitude un-converged). These are real, but the residual is still thermal-scale, so this is a *quantitative-fidelity*
  caveat, not a gross artifact. **Licensed next experiments (NOT built):** (i) dt-converged alignment torques (the
  dt-convergence line); (ii) a rotationally-stiff lever-arm that transmits/holds the stroke.

**Attribution succeeds and is genuinely a blend** (not the bail case): the balance is *not* evenly distributed — F9
clearly dominates — but F9 is a physical mechanism, so no single **idealized** constraint owns V₀. The prestress is a
frustrated-F9 residual, shaped at the margins by the AXLOCK idealization, the dt-stiff alignment torques, and the free
lever hinges.

## Plain bottom line

**What maintains the bound-ADP forward prestress that sets V₀?** A **frustrated power-stroke equilibrium**: the F9
nucleotide rest-angle switch (the modeled stroke) drives the head to reorient but is **balanced away** by the F8 spring
(the head is bound to actin) + the AXLOCK, leaving the head near perpendicular and only a **small, thermal-scale
(~2.3 kT, 0.82 pN axial), forward** residual strain — whose forward *direction* is co-imposed by the polarity-directed
DIRSWING and the AXLOCK axial-plane lock. It is **constraint-set** (re-emerges across a 1.9× neck-swing change), so the
nominal stroke knob is confirmed disconnected. It is **primarily an honest emergent consequence of the modeled
mechanics (outcome 1)** — *not* a single idealized artifact and *not* a large artificial preload — with three named,
licensed fidelity-preserving levers if V₀ is to be lowered: **(A)** a physical (non-lab-`+Z`) AXLOCK, **(B)** dt-converged
fracMove alignment torques, **(C)** a rotationally-stiff lever-arm (the free J1/J2 hinges currently prevent the stroke
from being held). None is a smoking gun; consistent with the kernel finding, the primary Vmax lever remains the
**catch-slip shape** (`VMAX_SENSITIVITY_25C.md`), which acts through the survivor-conditioned force waveform.

## Artifacts

- Code: `plateauBalance()` (pose-derived force/torque/energy recompute) + the plateau-band accumulator in `-epkernel`
  (`GlidingHarness`); `PLATROW` summary. Default-off **byte-identical**.
- Runs: `RUN_LOGS/2026-07-12_plateau_grid.txt` (v=0,16 × neck 40/60/80 × 4 seeds). Analysis:
  `RUN_LOGS/plateau_analyze.py`; PART-3 paired CI computed from the `EPISODE_KERNEL_ACCOUNTING` grids. Driver:
  `scripts/run_plateau_grid.sh`.
- Commands:
```
scripts/run_gliding.sh -matbox 50 -density 2000 -vclamp 0 -neckangle 60 -seed 0 -epkernel 12000   # one PLATROW
scripts/run_plateau_grid.sh RUN_LOGS/2026-07-12_plateau_grid.txt 12000                            # the balance grid
python3 RUN_LOGS/plateau_analyze.py RUN_LOGS/2026-07-12_plateau_grid.txt                          # re-emergence tables
```
- Constraints honored: audit/logging only (no mechanism build, no constraint change, no calibration); pose-derived
  recompute (no kernel touched) ⇒ default-off byte-identical; mechanics⇄release kept coupled (framing correction);
  full trajectory (d_peak/d_relax + transverse/axial split) reported, not a single scalar; paired CRN seeds; CPU
  deterministic; `BoA-v1ref` untouched. Attribution reported honestly as a dominant-physical-mechanism-with-named-
  idealization-caveats blend rather than forced onto a single constraint.
