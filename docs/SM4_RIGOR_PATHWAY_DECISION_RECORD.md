# Decision record — rigor detachment is force-independent in the current chemistry

**Status: MODEL LIMITATION, DOCUMENTED. No physics change implemented in this task (by instruction).**
**Date 2026-07-21 · code rev `73d82bf`.**

---

## 1. The finding

Rigor attachment lifetime is **exactly** force-independent. In the SM4 apparatus-validation matrix,
the rigor RMST was **bit-identical (0.04088 ms, median 0.0225 ms) across all seven force cells**
tested — 0, ±3, ±6, ±10 pN, both directions.

This is not a statistical near-miss; it is structural. Because the RNG is keyed on
`(motor, step, seed)` and rigor detachment reads no force, identical seeds produce identical
detachment steps regardless of load. A large rigor grid would add no information and was
deliberately **not run** (the 7-cell negative control above is sufficient and cheap).

Useful side effect: this makes rigor a **clean positive control** — it proves the RNG stream is
force-independent, so every ADP lifetime difference is causally attributable to load rather than to
seed drift.

---

## 2. Exact code path responsible

`softbox/NucleotideCycleSystem.java`, `cycleLymnTaylor` (declared at line 408).

The catch–slip factor is applied to exactly one transition — the ADP branch (lines ~453-455):

```java
} else { // ADP → NONE — base rate × catch g(F)
    float g = aCatch * (float) Math.exp(-Favg * xCatch / kT) + aSlip * (float) Math.exp(Favg * xSlip / kT);
    float rate = (bound ? onADP : offADP) * g;
    if (u < rate * dt) state = MotorStore.NUC_NONE;
}
```

The rigor state's only exit has **no force term at all** (line ~441):

```java
if (state == MotorStore.NUC_NONE) {
    if (u < atpOn * dt) state = MotorStore.NUC_ATP;   // ATP uptake (rigor) ⇒ detaches below if bound
}
```

and detachment itself is the single unconditional consequence of ending a step bound-and-in-ATP
(lines ~460-470). So for a prepared rigor head, lifetime is a pure exponential at `atpOn = 2e4/s`
(0.05 ms), with no load dependence anywhere on the path.

---

## 3. Why no harness-only calibration can fix it

The force dependence is absent from the **rate law**, not from the measurement. A harness can only
choose the prepared state, apply load, and observe. It cannot introduce a load term into a transition
whose coded rate is a constant. Concretely:

- Applying more force changes `forceDotFil`, but `forceDotFil` is never read on the `NUC_NONE` branch.
- `kinParams[18]` (`setExtLoad`) injects an external force into the *legacy* `catchSlipRelease*`
  kernels — **`cycleLymnTaylor` never reads it**, and those kernels are not on the explicit path.
- Preparing a different state does not help: any state that *is* force-dependent is by definition not
  rigor.

There is therefore **no minimum harness capability** that closes this gap. It requires a physics change.

---

## 4. Experimental behaviour that cannot currently be represented

- Force-dependent rigor bond lifetime, and in particular a rigor catch–slip optimum.
- Any rigor-state dynamic force spectroscopy (SM5): rupture-force distributions vs loading rate for
  the rigor bond would be flat/degenerate, since the bond has no mechanical failure channel.
- Any experiment in which ADP and rigor bonds are distinguished *by their load response* rather than
  by their mean lifetime.
- Nucleotide-free (rigor) mechanics under sustained high load — the model will hold the bond for
  ~0.05 ms regardless of load, then release on ATP binding.

---

## 5. Candidate future model changes

### (1) Force-dependent NONE→ATP transition
Multiply `atpOn` by its own load factor. **Cheapest**, single-line, keeps one reaction coordinate.
*Risk:* ATP binding is a chemical association step; making it mechanically gated conflates ATP
affinity with bond strength, and the fitted "distance parameter" would not correspond to any
structural coordinate. Also directly rescales unloaded gliding, since `atpOn` sets the detachment
clock for every cycling head.

### (2) Explicit mechanical rigor-rupture pathway
Add a parallel, force-driven rupture channel available from any bound state (a genuine Bell/Evans
term on the actomyosin interface), separate from the nucleotide cycle. **Most physically faithful**;
it is what the Guo & Guilford rigor measurement actually probes. *Risk:* introduces a second
detachment route that competes with the chemical one — every previously validated duty ratio,
avgBound and gliding number must be re-derived.

### (3) Separate bond-state model
Represent the actomyosin interface as its own state variable (weak/strong/ruptured) with its own
force-dependent kinetics, decoupled from the nucleotide state. **Most general and most expensive**;
it is the correct long-term structure if rigor and ADP are to be independently calibrated, but it is
a re-architecture, not a parameter change.

**Recommendation: (2), and only after (a) the tensile-arm calibration is settled and (b) a regression
suite exists.** Do not adopt (1) as a shortcut — it buys the lifetime curve at the price of
corrupting the ATP-binding constant that the rest of the cycle depends on.

---

## 6. Risk of double-counting force dependence

This is the central hazard and the reason no change is made here.

The ADP→NONE step **already carries the model's entire load dependence**, and the SM4 tensile grid
shows it is quantitatively correct (all four coded parameters recovered within 1.3σ). If a second
force-dependent channel is added *and* `xCatch`/`xSlip` are re-fitted against total lifetime, load
dependence will be counted twice: the fit will absorb some of the new channel's force sensitivity
into the ADP branch, silently changing a parameter that is currently validated.

Concretely, the failure mode is: add force-dependent rigor → total ADP-state lifetime becomes shorter
under load (both steps now accelerate) → refit → `xCatch` comes out too small → gliding speeds up →
someone "corrects" it elsewhere. **Any such change must be fitted to the ADP and rigor arms jointly,
with the ADP branch's parameters held at their independently-established values, and the new channel
constrained only by the rigor data.**

---

## 7. Regression suite required before any rigor physics change is accepted

1. **Byte-identity when disabled.** The new pathway must be flag-gated and default-off, and every
   existing harness must reproduce its current output bit-for-bit with the flag off.
2. **SM4 ADP tensile grid re-run** (`sm4_adp_opposing_production`): the Jensen-corrected fit must
   still recover k0, aCatch, xCatch, xSlip within their current uncertainties. This is the
   double-counting guard.
3. **SM4 assisting grid re-run** (`sm4_adp_assisting_fixed_anchor`): xCatch must remain 2.47 ± 0.06.
4. **SM4 rigor grid** (new, now meaningful): must reproduce the targeted experimental rigor
   force-lifetime curve, and must NOT be flat.
5. **Gliding**: unloaded gliding velocity and avgBound must be re-measured on the CPU arbiter (the
   standing GPU-number trust rule applies — a hot-kernel structural change of this kind is exactly
   the class that flips the bistable basin), and any change reported explicitly rather than absorbed.
6. **Duty ratio / attachment statistics** in the sparse-ensemble and dimer assays.
7. **SM6 unchanged**: this is a kinetics change only; the force-extension curves must be bit-identical.
8. **Conservation and health**: 0 invalid states, 0 solver failures, no phantom detachments.

Until items 1–8 exist and pass, the rigor limitation stands as documented behaviour, not a defect to
be patched.
