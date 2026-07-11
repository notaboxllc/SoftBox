# Motor-parameter provenance (Vmax→25 °C calibration, STEP 0 — READ-ONLY gating audit)

**Date:** 2026-07-11 · **Branch:** dt-convergence-study · **READ-ONLY** (no edits, no runs, no `-tempC`/`-paramfile`).
`BoA-v1ref` reference-only. Companion: `GLIDING_TARGET_25C.md` (the experimental target, Table 1). This is
Table 2 + the bottom-line gate: **what temperature(s) are the current kinetic rates actually at, is the isoform
consistent, and is a coherent-25 °C re-derivation even the right lever?**

> **BOTTOM LINE — the rates are NOT a single temperature, and temperature is very likely NOT the lever (H1 near-dead
> on arrival).** The live kinetics are a **multi-temperature, mostly-skeletal STITCH**, not one coherent set: the
> **thermal denominator kT is explicitly 25 °C**, but the **nucleotide cycle is Howard 2001 Table 14-2 — itself a
> compilation of ATP-side steps at ~20 °C (Lymn & Taylor 1971) welded to ADP release at ~15 °C (Siemankowski &
> White 1985)**, and the **catch-slip is Guo & Guilford 2006 (rat skeletal HMM, "room temperature," no numeric
> °C)**. So there is **no single "current effective temperature" to correct FROM** — the premise of "apply one
> consistent shift" fails; this is the "untangle a mix of inherited temperatures" case. **Worse for H1: the
> velocity-limiting step is ADP release, coded at 1×10³/s, which is ALREADY consistent with ~25 °C** (Siemankowski's
> ~15 °C lower-limit ≥500/s × Q₁₀≈2 over 10 °C ≈ ~1000/s) — so a coherent-25 °C re-derivation would **not
> meaningfully raise the rate that sets velocity.** And **no per-transition Q₁₀ is cleanly available** in accessible
> literature (only the emergent gliding velocity, Q₁₀≈2.4, and overall actin-activated ATPase, Ea≈66 kJ/mol) — so a
> rigorous per-transition warm-up cannot even be built without primary-source pulls. **⇒ Do NOT frame the
> calibration as a temperature shift. If the model glides slow, the lever is the mechanochemical operating point
> (stroke/duty/force per the force-balance work), not a °C correction.** (The `NMII_BIOLOGY.md` isoform intent is
> aspirational and was NEVER parameterized into the gliding path — the live code is skeletal throughout; flag, don't
> chase.)

---

## Live default motor stack (what these parameters drive)

The production gliding motor (no flags) is `SPHEREHEAD + AXLOCK + DIRSWING + XB_IMPLICIT2 + LYMN_TAYLOR` (all
field-default `true`; `GlidingHarness.java:44,78-86`). The nucleotide cycle = `cycleLymnTaylor` reading `nucParams`;
the release = the Guo–Guilford catch modulating the ADP→NONE rate; the cross-bridge = the sphere-head F8 spring +
`directedSwing`. **Config-1 (κ force-matched to Finer'94) + `-kon`/`-tauavg`/`-xcatch` are a SEPARATE, flag-gated
Phase-2 *calibration* path (sign-off, not defaulted)** — noted below but not the live rates.

## TABLE 2 — model-parameter provenance

Value units as coded. **Class:** PINNED (measured under matching conditions ⇒ a validation metric, never a knob) /
BOUNDED (measured, but a defensible interval) / TRANSFERRED (imported from another isoform/temperature/assay) /
ASSUMED (chosen for modeling/numerical reasons). All slot citations are `MotorStore.java` (SoftBox) → `Env.java`
(BoA-v1ref).

### A. Nucleotide cycle — Howard 2001 Table 14-2 "shaded path" (`Env.java:835`), rabbit **fast skeletal** (S1/HMM)

| Symbol (slot) | Value | Meaning | Primary source · isoform · **T** | Q₁₀/Ea | Class |
|---|---|---|---|---|---|
| `atpOnMyo` `nucParams[1]` | 2×10⁴ /s | NONE→ATP: ATP-induced detachment (the sole LT release) | Lymn & Taylor 1971 (rabbit skel HMM, **20 °C**; "too fast to measure," 2e4 an accepted lower-bound) | none measured (FLAG) | **TRANSFERRED** |
| `myoOnFilATP_ADPPi` `[2]` | 100 /s | ATP→ADP·Pi hydrolysis, on-fil | Lymn & Taylor 1971 / White & Taylor 1976 (rabbit skel, **~20 °C**) | none isolated (FLAG) | **TRANSFERRED** |
| `myoOffFilATP_ADPPi` `[3]` | 100 /s | off-fil hydrolysis (sets the ~10 ms detached recovery = τ_off gate) | as above | — | **TRANSFERRED** |
| `myoOnFilADPPi_ADP` `[4]` | 1×10⁴ /s | ADP·Pi→ADP: **Pi release / powerstroke**, on-fil | White & Taylor 1976 / Howard T14-2 (rabbit skel, **~20 °C assumed**) | only a fiber proxy (k₊₁ Q₁₀≈4, Kawai); **no solution Pi-release Q₁₀** (FLAG) | **TRANSFERRED** |
| `myoOffFilADPPi_ADP` `[5]` | 0 | off-fil Pi release (disabled ⇒ head stays primed off-fil) | v1 choice (`Env.java:851`, "//0.1") | n/a | **ASSUMED** |
| `myoOnFilADP_None` `[6]` | 1×10³ /s | ADP→NONE: **ADP release — the velocity-limiting step (τ_on≈1 ms)** | Siemankowski, Wiseman & White 1985 (rabbit skel S1, **~15 °C**; lower-limit ≥500/s) | Nyitrai 2006 measured T-dependence but **numeric Q₁₀ UNRECOVERABLE** (FLAG — primary-pull needed) | **TRANSFERRED** |
| `myoOffFilADP_None` `[7]` | 1×10³ /s | off-fil ADP release | as above | — | **TRANSFERRED** |

**Note on the velocity-limiter (H1 test):** the coded 1×10³/s ≈ Siemankowski's 15 °C ≥500/s scaled up by ~Q₁₀ 2
over 10 °C — i.e. **already ~25 °C-equivalent**. The round textbook values (2e4/1e4/1e3/100) are order-of-magnitude
compilation numbers, not a precise single-T dataset.

### B. Force-dependent release (catch-slip) — Guo & Guilford 2006 (`Env.java:803`), **rat** skeletal HMM, "room temp"

| Symbol (slot) | Value | Meaning | Source · isoform · **T** | Class |
|---|---|---|---|---|
| `kOff` `kinParams[0]` | 100 /s | base detachment rate (multiplies the catch-slip) | Guo & Guilford 2006 (rat skel HMM, **"room temperature," no numeric °C**) | **TRANSFERRED** |
| `alphaCatch` `[1]` | 0.92 | catch-term weight (load-stabilized) | Guo & Guilford 2006 | **TRANSFERRED** |
| `alphaSlip` `[2]` | 0.08 | slip-term weight (load-accelerated) | Guo & Guilford 2006 | **TRANSFERRED** |
| `xCatch` `[3]` | 2.5 nm | catch force-sensitivity distance | Guo & Guilford 2006 | **TRANSFERRED** |
| `xSlip` `[4]` | 0.4 nm | slip force-sensitivity distance | Guo & Guilford 2006 | **TRANSFERRED** |
| `kT` `[5]` | 298.15 K → 4.116×10⁻²¹ J | the Boltzmann denominator in every `exp(F·x/kT)` and Brownian scale | **`Env.java:25` `tempK=298.15 // 25 °C` — EXPLICIT** | **PINNED (25 °C by construction)** |
| `myosinBreakForce` `[11]` | 12 pN | deterministic force-cap detach (default OFF, `[12]=0`) | v1 `Env.java:799` ("use this to prevent stiffness") | **ASSUMED** |

⚠️ **Citation correction:** `Env.java:803` credits the catch-slip *values* to "Stam et al 2015." That is **Stam et
al. 2015 *Biophysical Journal* 108:1997 — a computational simulation** (co-authored by a J. Alberts), **not** a
single-molecule experiment and with **no temperature**. It is a *consumer* of catch-slip parameters; the **primary
experimental source is Guo & Guilford 2006** (correctly cited on the same line for xCatch/xSlip). Treat Stam 2015 as
a model provenance, not a measured T.

### C. Binding / geometry (lumped-model handles)

| Symbol (slot) | Value | Meaning | Source | Class |
|---|---|---|---|---|
| `myoColTol` `kinParams[7]` | 0.006 µm (6 nm) | capture tolerance (perp distance for bind) | v1 `Env.java:826` (lumped binding knob) | **ASSUMED** (dominant bind-rate knob) |
| `alignTol` `[8]` | −0.4 cos | motor–filament alignment gate | v1 `Env.java:784` | **ASSUMED** |
| `myoRebindTime` `[10]` | 1×10⁻⁵ s (1 step) | post-release refractory (NOT the real τ_off gate — that's the ~10 ms recovery, `DUTY_RATIO_DIAGNOSIS.md`) | v1 `Env.java:832` | **ASSUMED** |
| `kOn` `[14]` | 0 default (geometric bind-on-contact) | reaction-limited attachment rate | Phase-2 calib target (`-kon 2e5` sign-off, flag-gated) | **ASSUMED/BOUNDED** (not live) |

### D. Mechanics / stroke geometry

| Symbol | Value | Meaning | Source · isoform · T | Class |
|---|---|---|---|---|
| `MYO_SPRING` (myoSpring) | 1×10⁻⁹ N/µm = **1 pN/nm** | cross-bridge (F8) Hookean stiffness | v1 `Env.java:791` lumped; skeletal single-molecule ~0.3–2 pN/nm (Veigel, Kaya & Higuchi ~1.8) | **BOUNDED** |
| `NECK_ANGLE` (swingParams[3]) | 60° | cocked neck rest angle (sets emergent stroke) | modeling geometry (`GlidingHarness.java:99`); step ≈ 2·L·sin(θ/2) | **ASSUMED** |
| working stroke (emergent) | ~7 nm (measured in-model) | power-stroke displacement | validation vs skeletal ~5–8 nm (Finer'94, Molloy'95, Norstrom'10) | **PINNED (validation metric)** |
| unitary force ~5 pN / `KAPPA` (Config-1 only) | 3.82×10⁻²⁰ N·m/rad | J1 torsional stiffness force-matched to 5 pN stall | **Finer, Simmons & Spudich 1994** (rabbit skel, optical trap, room temp) | **PINNED (force is a validation metric)**; κ ASSUMED-from-pinned-force |
| geometry: rod 80 nm / lever 8 nm / head 20 nm | — | the 3-body articulated myosin | v1 `Env.java:776-777`; lumped abstraction | **ASSUMED** |

---

## The three key outputs the audit had to produce

**(a) What effective temperature are the current rates at — single, or a mix?** **A MIX of at least four inherited
temperatures**, none of which is a single coherent "current effective T":
- kT / Brownian / the `exp(F·x/kT)` denominators: **25 °C** (explicit, PINNED).
- Nucleotide cycle: a **compilation stitch** — ATP-side (dissociation, hydrolysis) at **~20 °C** (Lymn & Taylor
  1971) + ADP release at **~15 °C** (Siemankowski & White 1985); Howard's table header T is unrecoverable but its
  constituents are demonstrably ~15–20 °C, and the coded values are round order-of-magnitude numbers.
- Catch-slip: **Guo & Guilford 2006 "room temperature"** (rat skeletal, no numeric °C).
- Isoform: **skeletal throughout** (rabbit Lymn-Taylor/Siemankowski/Finer; rat Guo&Guilford/Rossi) — a benign
  rat↔rabbit fast-skeletal cross-species mix, NOT the NMII of `NMII_BIOLOGY.md` (aspirational, never wired into the
  gliding rates). The lone NMII citation (Stam 2015) is a mis-attributed simulation, not a live isoform source.

**(b) Which parameters are eligible to vary (knobs) vs PINNED (validation metrics)?**
- **PINNED (never knobs):** kT = 25 °C; unitary force ~5 pN (Finer'94); working stroke ~5–8 nm.
- **Eligible (BOUNDED/TRANSFERRED/ASSUMED):** all seven nucleotide rates (TRANSFERRED), the five catch-slip
  parameters + kOff (TRANSFERRED), `myoSpring` (BOUNDED, 0.3–2 pN/nm), `myoColTol`/`kOn`/`NECK_ANGLE`/`alignTol`/
  `myoRebindTime`/break-force (ASSUMED).

**(c) Which eligible rates have a defensible source-T + Q₁₀ for a 25 °C re-derivation?** **Essentially none at the
per-transition level.** A clean, accessible, isoform-matched Q₁₀/Ea exists ONLY for the **emergent gliding velocity**
(Q₁₀ ≈ 2.38, fast skeletal 10–25 °C, J Appl Physiol 2005; Anson 1992: Ea 50±5 kJ/mol above ~15.4 °C) and the
**overall actin-activated ATPase** (Ea ≈ 66 kJ/mol, Stein 1982 / Rall & Woledge). For **ADP release, Pi release,
ATP-induced dissociation, and the hydrolysis step individually there is NO cleanly-measured Q₁₀/Ea in accessible
sources** (Nyitrai 2006 measured ADP-release T-dependence but the number is paywalled — a required primary pull).
So a rigorous per-transition warm-up is not even constructible today without those pulls — and the one step that
matters for velocity (ADP release) already sits at ~25 °C-equivalent.

---

## Plain statement (the gate verdict)

**What temperature are the current kinetic rates actually at, and is a consistent-25 °C re-derivation the right
lever?** The rates are **not at one temperature** — they are a **skeletal, multi-temperature stitch**: kT explicitly
25 °C, the nucleotide cycle a Howard-Table-14-2 compilation of ~20 °C ATP-side steps + ~15 °C ADP release, and the
catch-slip a "room-temperature" rat-skeletal study. **There is no single effective temperature to correct FROM**, so
"apply one coherent 25 °C shift" is the wrong frame — untangling the mix is the finding. **And temperature is very
likely not the missing lever: the velocity-limiting ADP-release rate (1×10³/s) is already ~25 °C-equivalent**, no
per-transition Q₁₀ is cleanly available to justify a warm-up of the other steps, and the condition-matched 25 °C
skeletal gliding target (~4.2 µm/s, `GLIDING_TARGET_25C.md`) is modest and near where the model already operates.
**⇒ H1 (a 25 °C temperature correction unlocks Vmax) is near-dead on arrival. Do NOT run a temperature-scaling
calibration; if the model needs a velocity change, the lever is the mechanochemical operating point
(stroke/duty/force-balance), not °C.** The one legitimate temperature-consistency chore is documentation-level:
record that kT is 25 °C while the imported cycle is ~15–20 °C, and either (i) accept the ~5–10 °C stitch as within
the model's coarseness, or (ii) if a coherent-25 °C cycle is ever wanted, do it **per-transition from primary
sources with measured Q₁₀** (ADP release via Nyitrai 2006; velocity/ATPase via the confirmed Q₁₀≈2.4 / Ea≈66
kJ/mol) — never by a single blanket factor.

## Provenance sources (with the corrections this audit found)
- Nucleotide cycle: Howard 2001 *Mechanics of Motor Proteins* Table 14-2 (rabbit fast skeletal; **multi-T
  compilation**, header T unrecoverable) → Lymn & Taylor 1971 *Biochemistry* 10:4617 (**20 °C**), White & Taylor
  1976 *Biochemistry* 15:5818 (~20 °C), Siemankowski, Wiseman & White 1985 *PNAS* 82:658 (ADP release, **~15 °C**).
- Catch-slip: Guo & Guilford 2006 *PNAS* 103:9844 (**rat** skeletal HMM, "room temperature"). Env.java's "Stam 2015"
  = Stam et al. 2015 *Biophys J* 108:1997 (**simulation, not experiment; no T** — a consumer, not the source).
- Unitary force: Finer, Simmons & Spudich 1994 *Nature* 368:113 (rabbit skeletal, ~5 pN).
- Q₁₀: gliding velocity Q₁₀ ≈ 2.38 (J Appl Physiol 2005, fast skeletal 10–25 °C); Anson 1992 *JMB* (Ea 50±5 kJ/mol
  above 15.4 °C); actin-activated ATPase Ea ≈ 66 kJ/mol (Stein 1982; Rall & Woledge 1990). **Per-transition Q₁₀s:
  not available in accessible sources — FLAGGED, not guessed.**
- Model temperature: SoftBox `Constants.java:26` `tempK=298.15` = BoA `Env.java:25` (25 °C, explicit).
</content>
