# Does making ONLY the F8-tip orientation implicit collapse the per-bound dt-bias — or is a sub-step needed?

**Date:** 2026-07-06. New code path only (`EomStabilityHarness -stroke` / `-stroketipimplicit`); **default
byte-identical** (the `-stroke` branch is gated; the default relaxation grid reproduces
`EOM_STABILITY_FINDINGS.md` exactly — 0/1/5/15/30/50 pN·nm⁻¹ MONO/RING/GROW map + dt_crit unchanged);
`BoA-v1ref` untouched. A **research probe to pick the approach**, not a production build — no CSR-gather star,
no `-cpu` parity gate, no float32 audit.

Follows `ROTIMPLICIT_FEASIBILITY.md` (which recommended "a targeted F8-tip-only rotational isolation" before
committing to the heavy dense 6-DOF star) and `STROKE_DT_RATE_DIAGNOSIS.md` (the residual per-bound glide climb
is BOTH a real F9/F10 rate — handled by `-alignrate` — AND a residual rotational stiffness). The converged
reference this probe is measured against: per-bound **0.822 (dt=1e-5) → 1.783 (converged ≈6.25e-7)**, a bounded
**~2.2×** forward-Euler bias (ROTIMPLICIT Part B).

---

## PLAIN ANSWER (headline)

**STEP 1 BAILS: the per-bound dt-bias is NOT reproduced as a standalone per-motor stroke property.** A single
anchored motor driving one deterministic power stroke into a **free** filament (viscous-drag-only load, Brownian
off, rate-fix on) delivers a per-stroke net displacement that is **dt-FLAT** — **−1.723 nm at dt=1e-5 →
−1.760 nm at dt=3.125e-7, a 1.02× variation over a 32× dt range** — versus the ~2.2× gliding per-bound bias.

- **⇒ Do NOT build STEP 2 (the F8-tip implicit) on this harness.** There is no isolated single-stroke bias to
  collapse: a free permanently-bound head relaxes its delivered working stroke to the **same dt-independent
  geometric endpoint** at every dt (F8→0 at equilibrium ⇒ the settled displacement is fixed by geometry, not by
  the integration scheme).
- **The dt-dependence is real but lives in the NON-EQUILIBRIUM transient, not the equilibrium stroke.** The
  filament position sampled at a fixed 5·τ_relax after firing is **strongly** dt-dependent (+0.083 nm @1e-5 →
  −0.718 nm @3.125e-7 — even the *sign* differs at coarse dt) and converges **first-order in dt** (Δ-from-limit
  halves per dt-halving). That transient is the drag-limited approach (the filament's own translational
  relaxation is only α = k_F8·dt/γ ≈ 0.42-resolved at 1e-5) plus the head-orientation transient — but it **decays
  to the same endpoint**, so it contributes **zero** to a single free stroke's net delivered displacement.
- **⇒ WHICH PATH.** The per-bound bias is a property of the **loaded, continuously-cycling, never-equilibrating**
  gliding transport — a single motor that relaxes to equilibrium cannot express it (net transport needs the
  bind→stroke→unbind→rebind ratchet; a permanently-bound head just oscillates, net 0 per cock/uncock cycle).
  Since the F8-tip **equilibrium** stroke is dt-robust in isolation, the heavy single-motor F8-tip rotational
  star (ROTIMPLICIT §A4) is **not** justified by an isolable per-motor stiffness. **The indicated lever is the
  sub-step acting on the collective LOADED transport** (the full nonlinear loaded response, no linearization
  gamble) — consistent with `EOM_STABILITY_FINDINGS.md` ("the limiter is the collective loaded force, not any one
  motor-internal coupling") and `jacobi-cobound-scheme-risk` (the co-bound tug-of-war is the ensemble locus).

---

## STEP 1 — the driven single-motor ladder (explicit, rate-fix on)

**Setup.** One anchored motor (tail-anchored bed motor) + one **permanently-bound** head on a **free** single
filament segment. No external spring — the only load is the filament's own viscous drag (γ_seg,∥ ≈ 2.389e-8
N·s·m⁻¹; k_F8 = 1 pN·nm⁻¹ ⇒ τ_relax ≈ γ/k_F8 ≈ 2.39e-5 s). **Brownian OFF ⇒ deterministic, no seeds.** Stroke
law reused verbatim from the validated gliding default: **SPHEREHEAD** (F9 frozen at 90°) + **AXLOCK** (F10→ŝ,
head-only) + **DIRSWING** (neck 0°→60°). **RATE-FIX ON** (`-strokerate` refDt=1e-5 + `-alignrate`) so the stroke
and F9/F10/axlock rates are dt-honest and the ONLY residual dt-dependence is the numerical F8-tip orientation
integration.

**Measurement.** Settle uncocked (ADPPI ⇒ neck 0°); fire the stroke (→ cocked ADP ⇒ neck 60°); integrate the
free-filament response; record the net segment displacement.

| dt (s) | settled (nm) | peak (nm) | at 5·τ_relax (nm) | vs coarse |
|--:|--:|--:|--:|--:|
| 1.000e-5  | −1.72333 | −1.72357 | +0.08301 | 1.000× |
| 2.500e-6  | −1.73958 | −1.73961 | −0.43520 | 1.009× |
| 1.250e-6  | −1.74825 | −1.74827 | −0.59979 | 1.014× |
| 6.250e-7  | −1.75556 | −1.75556 | −0.67589 | 1.019× |
| 3.125e-7  | −1.76032 | −1.76032 | −0.71810 | 1.021× |

**Reads:**
1. **Settled (the gate observable) is FLAT — 1.021× over 32× dt.** The stroke fires and delivers a real,
   correctly-signed working stroke (−x = barbed-ward = the glide direction; ~1.72 nm delivered through the
   1 pN·nm⁻¹ cross-bridge), so the harness is not null — yet the delivered net displacement is dt-robust. `peak ≈
   settled` (monotone approach, α<1, no overshoot). This is a **geometric endpoint**: a free filament ⇒ F8=0 at
   equilibrium ⇒ tip = bound site ⇒ the displacement is fixed by the head's final (rate-fixed, hence
   dt-independent) orientation, not by the integration step size. **The gliding per-bound bias (0.82→1.78, 2.2×)
   is NOT reproduced** — a 60× separation between the 1.02× here and the 1.2× (=120%) glide climb.
2. **The transient (5·τ) IS strongly dt-dependent and converges O(dt).** At coarse dt the drag-limited filament
   has barely moved (even wrong-signed) at a fixed sim-time; as dt→0 it tracks the true approach. Δ-from-limit
   (≈−0.75 nm): 0.83 → 0.31 → 0.15 → 0.074 → 0.032, ratio ≈0.5 per dt-halving = **clean first-order explicit-Euler
   error**, NOT a stiffness runaway. This is the real forward-Euler dt-dependence — but it lives in the
   **approach**, decays to the same endpoint, and so nets to zero over a single equilibrated stroke.

**GATE verdict: BAIL (displacement flat vs dt).** The per-bound bias is not a standalone per-motor stroke
property. Per the probe's STEP-1 bail rule: **stop, commit nothing for STEP 2, report the flat ladder.**

## STEP 2 — NOT BUILT (correctly)

STEP 2 asked: make ONLY the F8-tip orientation implicit; does the dt=1e-5 per-stroke displacement collapse to the
converged value? **There is nothing to collapse** — the STEP-1 explicit per-stroke displacement is already
dt-flat (1.02×), i.e. it *is* its own converged value. Making the F8-tip implicit cannot change a quantity that
is already dt-independent. Building the single-motor F8-tip backward-Euler star here would test a null. (`-stroketipimplicit`
runs the STEP-1 explicit ladder and prints this note rather than executing an unbuilt STEP 2.)

## PASS / PARTIAL / NULL verdict — stated plainly

**NULL-in-isolation.** Making the F8-tip orientation implicit does **not** make the single free driven motor
dt-robust, because the single free driven motor is **already** dt-robust in its net delivered stroke — the
F8-tip equilibrium stroke carries no dt-bias. **A pure F8-tip implicit does NOT address the per-bound bias, and
neither is a single-motor sub-step the right frame:** the ~2.2× per-bound bias is a **loaded, non-equilibrium,
cycling-ensemble transport** effect (the filament never relaxes; the dt-dependent transient — confirmed real and
first-order here — accumulates only under sustained co-bound load / continuous rebinding). **A sub-step is still
needed, but it must act on the collective loaded transport, not one motor's F8-tip stiffness.** This directly
answers ROTIMPLICIT's open question — the F8-tip fraction of the residual is **not** isolably large in a single
motor ⇒ the recommendation swings toward the **sub-step over the heavy dense 6-DOF rotational star**.

**Caveat (honest scope).** This probe used the free (viscous-drag-only) single-head case the prompt specified. It
proves the *equilibrium* delivered stroke is dt-flat and localizes the real dt-dependence to the *non-equilibrium
transient*; it does **not** by itself apportion that transient between the F8-tip head orientation and the
filament's own translational relaxation (α≈0.42 at 1e-5) — the free single-stroke conflates them and always
equilibrates, so it cannot. What it decisively rules out is the hypothesis the probe was built to test: that the
per-bound bias is a standalone per-motor stroke property fixable by a single-motor F8-tip implicit. It is not.

## Reproduce

```
./run_eomstab.sh -stroke              # the STEP-1 ladder (FREE filament, rate-fix on, Brownian off) → BAIL (flat)
./run_eomstab.sh -stroketipimplicit   # prints the STEP-1-bailed note, runs the explicit ladder for reference
```
Files: `EomStabilityHarness` (`-stroke`/`-stroketipimplicit`: `runStrokeProbe`/`drivenStroke`/`cpuStrokeStep`,
reusing `CrossBridgeSystem.bondForces`+`directedSwing` with size-11 axlock `xbParams` + size-5 rate-fixed
`swingParams`). Log: `RUN_LOGS/2026-07-06_stroke_tip_implicit_probe.txt`. Default byte-identical; `BoA-v1ref`
byte-clean.
