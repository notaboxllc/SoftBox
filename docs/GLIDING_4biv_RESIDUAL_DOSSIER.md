# Gliding 4b-iv net-glide residual — investigation dossier + BoA `bindTimer` bug report

**Status:** 4b-iv CLOSED. The residual is accepted as the irreducible parallel-scheme remainder.
**Audience:** (Part 1) a future SoftBox/v2 session that wants to revisit the residual without re-deriving
the context; (Part 2) a Claude Code session **in the BoA repo** that will fix v1's broken refractory.
**Source:** synthesized from `GLIDING_4biv_FINDINGS.md` §6.1–6.12 + `JOURNAL.md` (2026-06-15/16).
Numbers below are quoted from those committed docs.

---

## PART 1 — The SoftBox 4b-iv net-glide residual

### 1.1 The observable and the number
In the gliding assay, **v2 (SoftBox) net glide is 0.874× v1's** — a small, box-uniform shortfall (~−13%):

| | v2-GPU | v1-GPU (oracle) | ratio |
|---|---|---|---|
| net glide, full 0.1 s (n: v2=24, v1=16) | **4.000** µm/s (SD 0.408) | **4.578** µm/s (SD 0.472) | **0.874** |
| gap | — | −0.578 ± 0.145 µm/s | **−4.0 σ** |
| box-uniformity | 4×1 → 0.87×, 14×2 → 0.87× (identical across boxes) | | |

It is **real and systematic** (−4σ pooled) but **small and within v1's own chaotic envelope**: 22/24 v2
runs fall inside v1's range; v1's same-seed assist-fraction SD is ~3.3 pp (Lyapunov divergence); v2 is
bit-reproducible. The distributions overlap heavily. The earned framing is "robust within v1's ensemble,"
not "v2 is wrong."

**Crucial control already in hand:** v1-GPU vs v1-CPU net = **4.578 vs 4.581 (0.0 σ, 0.999×)**. v1-CPU is
the serialized, race-free path (see Part 2); it nets identically to racy v1-GPU. So none of v1's own
CPU/GPU differences — including its refractory race — move v1's net.

### 1.2 What was tried, and excluded (the full chain)

| § | hypothesis | test | result |
|---|---|---|---|
| 6.1a–6.2 | a per-step force-law / rate / constant differs | force-coverage audit; cycle rates; occupancy | every channel FAITHFUL given identical state; occupancy (ATP/ADP) matches |
| 6.3 | the cross-bridge force itself differs | `-forcetest`: feed v1 compute-time config into v2 | force vector matches v1 to **float32** (Δ ≤0.15% smallest comp, ≤0.013% dominant) |
| 6.4/6.5 | force-vs-state **staleness** (parallel reads start-of-step state) | `-freshread`: freshen the 4 stale reads (catch-slip rate, release decision, cycle ADP-gate, new-bind first force) | moved assist-fraction **+0.43 pp** but **net unmoved** |
| 6.6 | rebind refractory strength | dt-fix (`f2402b2`); `-norefractory` bracket | directedness untouched; flagged a "~4–6% net-favorable" refractory fix (later **contradicted**, see 1.4) |
| 6.7 | (characterization) | n=24/16 variance ensemble | the −13%/−4σ number above; gap is v2-vs-v1, **full-size on the sequential CPU path too** (not a GPU-reduction artifact) |
| 6.8 | **float32 precision** | `-forcebias` susceptibility probe | **RULED OUT.** float32-scale coherent bias moves net ~140× too little; producing the gap needs **1.37% coherent directed force**; float32 error is ~0.01% and incoherent. **Precision floor ≲0.1%.** |
| 6.9 | a localized **assist-balance constant** | `-assistlog` joint decomposition, n=6 | the "2–3 pp assist deficit" was an **n=4 artifact**; at n=6 v1=51.98% / v2=52.63% (v2 *higher*); every per-state/per-pose rate and bindArc/poseAngle/load distribution matches within v1's 3.3 pp spread. **No constant to hunt.** |
| 6.10 | v1's **12 pN break-force release** (a real missing branch) | port faithfully behind `-faithfulrelease`; A/B n=16 | cap fires ~0.5%; **net unmoved** (−0.13, wrong way, ≤1σ); avgBound −5σ (real re-patterning) but assist & net flat. **Not the residual.** |
| 6.11 | v2's refractory too strong (rate-faithful fix) | `-faithfulrefractory` (p=0.31) + cap, 2×2 factorial n=16 | refractory net effect **±0.16, ≤1σ, sign-unstable** → §6.6's 4–6% does **not** survive n=16 |
| 6.12 | v1's refractory **race** inflates v1's net (confound) | lengthen `myoRebindTime` in both codes to suppress the race, then compare | **gate failed** — v1's block rate is window-independent (~0.27–0.34 across a 1000× sweep); race can't be lengthened away. Confound separately bounded **0.0σ** by the v1-CPU vs v1-GPU control (§1.1). |

### 1.3 What net glide is NOT sensitive to (the decoupling that pins the conclusion)
- **assist/resist balance** — `-freshread` and the full §6.9 decomposition move it without moving net.
- **avgBound (bound-population size)** — the force-cap drops avgBound **−17% (−5σ)** with net flat.
- **the four force-vs-state stale reads** — freshened, net unmoved (§6.4).
- **refractory block rate (0% ↔ 31%)** — v1-CPU vs v1-GPU net 0.0σ; v2 refractory sweep net ≤1σ.

Net is therefore set by something **other than** the bound population's count or sign-balance — most
consistent with **per-cycle stroke displacement/timing under the parallel update**, not population
statistics.

### 1.4 Conclusion
The −13% is the **irreducible parallel-scheme remainder**: v2's parallel SoA kernels compute every force
from start-of-step state and then integrate (one-step-stale, Jacobi-like), whereas v1's sequential OOP
loop updates each motor against freshly-updated neighbor state (Gauss–Seidel-like). On a chaotic
many-body trajectory the two schemes produce slightly different *mean* directedness. This is the
deliberate SoA/GPU-residency tradeoff — **architectural, not a bug, not precision, not a tunable
constant** — in the same category as the float32 decision. It is accepted; 4b-iv is closed.

**Faithfulness items resolved.** The 12 pN force-cap is a real v1 feature → **promote `-faithfulrelease`**.
v1's refractory is a non-physical race artifact (Part 2) → **keep v2's clean 1-step block**; v2 not
matching v1's refractory is v2 correctly *not* inheriting a v1 bug, documented as a deliberate de-racing
divergence.

### 1.5 Corrected / superseded claims (read before trusting older entries)
- §6.2's "v1 assist 54.4%" was an **n=4 draw**; true ≈52% at n=6 (§6.9). There is no assist deficit.
- §6.8's neat cross-check ("1.37% coherent force ≈ the 2–3 pp assist deficit") is **coincidental** — the
  assist deficit was a small-n artifact. §6.8's *core* result (precision ruled out) stands.
- §6.6's "~4–6% net attributable to the refractory" did **not** survive n=16 (§6.11). §6.6's primary
  verdict (refractory acts on binding quantity, not directedness) does stand.

### 1.6 If anyone ever wants to *positively* pin the mechanism (optional, low priority)
Everything above excludes-by-elimination; the positive mechanism is inferred, not demonstrated. Two
handles remain, both with caveats:
- **Direct scheme test:** build a Gauss–Seidel (sequential fresh-force) v2 variant and compare to the
  production parallel v2. If it closes the gap, the staleness is confirmed as the cause. Cost: invasive,
  and it defeats the GPU-residency purpose — diagnostic only.
- **Net-load thread:** §6.9's mean net load (v2 0.231 vs v1 0.317 pN) leaned v2-low but was unresolved at
  n=6 and may sit below the force-balance noise floor. Resolving it at n≈24 (magnitude-weighted, by
  state/pose) is the cheapest positive probe — but it may simply not resolve, which would itself confirm
  the residual lives only in the integrated mean.
- **Carry-forward rule:** in the contractile-ring work, a **>0.1% systematic** discrepancy is a logic
  signal, not float32 (the §6.8 precision floor).

### 1.7 Instruments built during the hunt (all default-off; breadcrumbs for a future session)
`-forcebias` (§6.8), `-assistlog` (§6.9), `-faithfulrelease` (§6.10, branch
`forcecap-faithful-release-6.10`), `-faithfulrefractory` p=0.31 (§6.11), `-refractorysteps N` (§6.12,
reverted), `-freshread` (§6.4), `-norefractory` (§6.6). Raw under `RUN_LOGS/2026-06-1{5,6}_4biv_*`.

---

## PART 2 — BoA v1 `bindTimer` static-global race (for a BoA CC session to fix)

> **Scope guardrails — read first.**
> - This fix is for the **active BoA research repo**, NOT `BoA-v1ref` (the frozen oracle), which must
>   stay byte-clean.
> - SoftBox was validated against the **frozen v1ref**, which has this bug baked in. Fixing BoA-active
>   does **not** retroactively change SoftBox's oracle or any SoftBox validation result.
> - Fixing it **changes v1's behavior** (a real refractory where there currently is ~none) → it
>   re-baselines any BoA fixtures sensitive to binding quantity. Net glide is *not* one of them: the bug
>   contributes **0.0σ to v1 net** (v1-CPU 4.581 vs v1-GPU 4.578), so it affects avgBound/binding-quantity,
>   not directedness — but verify on the BoA side.
> - File:line references are **as observed in `BoA-v1ref`**; BoA-active line numbers may have drifted —
>   grep for the symbols.

### 2.1 The bug
`bindTimer` is a **static class-global** variable (observed `MyoMotor.java:73/179/455`; reset in
`MyoFilLink.java:315`; intended period `myoRebindTime` in `Env.java:832`, default `1e-5 s` = one dt). It is
meant to implement a per-head rebind refractory (a released head waits `myoRebindTime` before it may
rebind). It does not, for two reasons:

1. **It is global, not per-motor.** Every motor's `step()` advances the *same* counter by `deltaT`, so it
   accumulates ≈ N·deltaT ≈ **0.13 s per simulation step** (N ≈ 13.4k motors). That per-step accumulation
   **dwarfs every physical window** (1e-5 … 1e-2 s tested).
2. **It is reset to 0 by *any* release.** Combined with (1), at the moment a head checks its refractory the
   global timer is bimodal: ≈0 (some motor just released → "blocked" for any window) or ≫ window (no recent
   release → "never blocked").

### 2.2 The symptom (measured, §6.12)
- Effective block rate is **window-independent**: ~0.27–0.34 across a **1000×** sweep of `myoRebindTime`
  (1e-5 → 1e-2 s). Changing the parameter does nothing.
- The block rate is set by **racy reset-vs-check concurrency**, not by the parameter:
  **~0.31 on GPU (parallel), 0% on CPU (sequential, the timer always overruns).** The parameter is
  effectively vestigial.

### 2.3 Intended behavior
A **per-head** refractory of duration `myoRebindTime`: after a head releases at time `t₀`, it is blocked
from rebinding until `t − t₀ ≥ myoRebindTime`. No shared mutable global; deterministic; honors the
parameter; identical on CPU and GPU.

### 2.4 Fix direction (confirm against BoA-active structure; do not treat as a literal patch)
Replace the static class-global `bindTimer` with **per-motor state** — e.g. a per-head
`lastReleaseTime` (or a per-head countdown), set on release, and a rebind gate
`if (currentTime − lastReleaseTime < myoRebindTime) { skip rebind }`. This removes the shared-global race
(each head reads/writes only its own field) and makes the refractory honor `myoRebindTime` on both paths.
Verify: (a) the effective block rate now scales with `myoRebindTime` (no longer ~0.31-flat); (b) CPU and
GPU agree; (c) the change to v1 gliding net is negligible (expected, per §2.1 guardrail) while avgBound
shifts — record the new baseline if BoA validation depends on it.

### 2.5 Decision left to the human
Whether BoA-active and the frozen `BoA-v1ref` oracle should ever be **re-frozen** at a corrected baseline
is a separate call with downstream SoftBox re-validation implications. This document only describes the
bug and the fix; it does not authorize re-freezing the oracle.
