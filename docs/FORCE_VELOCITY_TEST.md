# Does mean motor force cross zero under a velocity clamp — and if so, where? (measure before build)

**Date:** 2026-07-10 · **Branch:** dt-convergence-study · **Runner:** CPU (deterministic — the basin
arbiter; no GPU, so no float-op-ordering/basin hazard). `BoA-v1ref` untouched. **Measurement-only,
flag-gated, default-off byte-identical** (`-vclamp`/`-fvepisode` add fields + a dispatch branch + one
method; no shared kernel, no default-path line touched).

> **UPDATE (2026-07-11, `FORCE_BALANCE_CLOSURE.md`):** the single-seed V₀ ≈ 14 below is refined by a 4-seed
> bootstrap to **V₀ = 15.95 [15.0, 20.3] (d2000) / 16.08 [15.1, 18.4] (d4000)** — density-independent ≈ 16
> (seed 0 sat low). The crossing **slopes MATCH** across densities (−0.034 / −0.037); the earlier apparent
> ~3× slope difference was sampling noise. And the clamp curve **predicts free gliding to a
> factor ~1.4**: `avgBound·f̄_bound(v*) = ζ_eff·v*` (measured ζ_eff = 0.401 pN/(µm/s)) gives v*_pred ≈ 5.2/8.2
> vs **arbiter-consistent (CPU/CPU) observed 3.65 ± 0.60** ⇒ pred/obs ≈ 1.4 (the rigid clamp over-predicts by
> ~40 %; the naive ~20 % vs GPU-free conflates the CPU↔GPU basin diff). Model broadly self-consistent,
> calibration is the frame; operative free-glide ceiling ~V₀/1.4 ≈ 11–12 (not the clamp's 16). The Variant-B **impulse** ⟨I_ep⟩ crosses zero at ~16–18 (coincident with V₀) ⇒ ceiling intrinsic to
> one attachment, not recruitment-masked. Occupancy denominator fixed (`bound_to_available ≤ 1`). The verdict
> below (outcome 2, calibration not a new state) stands and is strengthened.

> **VERDICT — the intrinsic force–velocity ceiling EXISTS; it is density-independent and sits ~1.8× above
> the biological Vmax ⇒ CALIBRATION, not a missing state. Do NOT build the weak/commitment state.** The
> velocity clamp turns the ensemble mean force `f̄_available(v)` into a clean, monotone force–velocity curve
> that **crosses zero at V₀ ≈ +14 µm/s** (d2000 → 14.3, d4000 → 14.8 — **density-independent to ~3 %**) and
> goes **negative (resistive) beyond it**. So the model is **not** missing a ceiling — it has a real,
> density-independent one at V₀ ≈ 14 µm/s, ~1.8× the biological gliding Vmax (~8 µm/s). It simply does **not
> cross within the ±12 µm/s window the task specified** (V₀ is just above +12), which is why the free-gliding
> sims — operating at v* < V₀ and climbing toward the fixed V₀ as density rises — never *looked* like they
> saturated. **Variant B (one attachment episode, recruitment removed) ALSO stays positive to +12 and
> crosses near the same V₀** (its per-bound force is even *higher* than A's — fresh isolated heads vs A's
> co-bound tug-of-war crowd) ⇒ **the ceiling is intrinsic, NOT recruitment-masked — jba's "recruitment keeps
> offering fresh zero-strain bonds ⇒ no ceiling" hypothesis is REFUTED.** The strain-erasing binder is real
> (**PART 2: `x_bind` ≡ 0 at every velocity**), but it does **not abolish** the ceiling — it only sets V₀
> *high* (each fresh bond is maximally productive). **This is outcome 2: architecture sound, V₀ mis-calibrated
> ~1.8× high (stroke size / rates / stiffness) ⇒ the measurement says DON'T add a state.**

---

## Method — the velocity clamp (kinematic velocity source)

`-vclamp <v>` (`GlidingHarness.runForceVelocity`, CPU) holds the gliding filament **rigid, straight
(uVec=+x), thermal OFF**, and advances only its COM x at **−v·dt per step** (glide direction ĝ = −x̂;
`+v` ⇒ gliding forward, `−v` ⇒ dragged backward / anti-glide). The clamp pose is **re-imposed every step**,
so the filament's within-step force response (≤ ~nm) is discarded — a pure velocity source that does **not**
respond to motor force. **Because the clamped filament ignores its `forceSum`, the carpet motors couple to
nothing but the prescribed trajectory ⇒ they are mutually INDEPENDENT** — the whole carpet is a bank of
replicated single-motor episodes vs one sliding filament (GPT's efficient design; no literal one-molecule
run needed). The rigid clamp pins the filament on-plane by construction, so the `-matbox 50` bail ("clamp
can't hold on-plane") **cannot occur** (matbox passed for parity; redundant here).

- **Compact clamp bed** (`-vclamp` sets `x∈[−3,3] µm`, `y half 0.1 µm`): only motors within the ~50 nm head
  y-capture band ever reach the filament, so a thin-y bed is faithful for the *per-available* force (motors
  outside reach contribute 0 to the numerator and are excluded from the denominator) and cuts the motor
  count ~10× (d2000 → 2400, d4000 → 4800). x-runway ±3 µm fits the ≤1.8 µm window excursion.
- **Force sign.** `f_g(m) = −bondData[6]` = the seg-side x force the motor exerts ON the filament, projected
  on ĝ; **positive ⇒ the motor DRIVES the filament in the glide direction** (= `forceDotFil` for the +x
  filament, so f_g>0 ≡ the "driver" of `DETACHMENT_CEILING_CODEREAD.md`).
- **Primary quantity (unbound = 0):** `f̄_available = ⟨Σ_bound f_g⟩ / ⟨N_reach⟩`. Decomposition:
  `P_bound = ⟨N_bound⟩/⟨N_reach⟩`, `f̄_bound = ⟨Σ_bound f_g⟩/⟨N_bound⟩`, so `f̄_available = P_bound·f̄_bound`.
- 12 000 steps/point, warm-up M/3; steady-window accumulation. dt = 1e-5, canonical springs default stack
  (SPHEREHEAD/AXLOCK/DIRSWING, XB_IMPLICIT2, LYMN_TAYLOR).

---

## PART 1 — Variant A (full binding): `f̄_available(v)` crosses zero at V₀ ≈ 14 µm/s (density-independent)

`f̄_available` declines monotonically from strongly positive (backward-drag / anti-glide branch) through a
positive plateau within ±12, and **crosses zero at V₀ ≈ +14 µm/s** (extending the sweep to +18 to actually
locate it), going negative beyond. **V₀ is density-independent** (d2000 14.3, d4000 14.8). Full d2000 table
(seed 0; f̄_bound ≈ f̄_available because P_bound ≈ 1):

| v (µm/s) | −12 | −8 | −6 | −4 | −2 | 0 | +2 | +4 | +6 | +8 | +10 | +12 | +13 | **+14** | **+15** | +16 | +18 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **f̄_available (pN)** | +3.00 | +1.80 | +1.51 | +1.21 | +1.00 | +0.76 | +0.62 | +0.55 | +0.36 | +0.28 | +0.23 | +0.10 | +0.07 | **+0.010** | **−0.022** | −0.065 | −0.133 |
| **P_bound** | 1.01 | 0.99 | 1.01 | 0.99 | 0.98 | 0.96 | 0.97 | 0.96 | 0.95 | 0.96 | 0.98 | 0.97 | — | — | — | — | — |

**d4000 (density-independence): same shape, larger magnitude on the resistive/negative-v branch, converging
to d2000 for v ≳ −4, and the SAME zero-crossing:** f̄_available = −12:+4.90, 0:+0.92, +6:+0.45, +12:+0.21,
+13:+0.13, **+14:+0.082, +15:−0.017**, +16:−0.032, +18:−0.123 ⇒ **V₀(d4000) ≈ 14.8 µm/s ≈ V₀(d2000) 14.3.**

**Reading the decomposition — the decline is in per-head FORCE, not engagement:**
- **P_bound stays high (~0.95–1.0) at every v** — the model does NOT shed its bound population as sliding
  speeds up (the **high-duty** regime, `DETACHMENT_CEILING_CODEREAD.md`); so `f̄_available ≈ f̄_bound`.
- The decline is carried by **⟨I_attach⟩ (net axial impulse/episode): +3.82 → +0.054 pN·ms** and
  **lifetime 1.32 → 0.52 ms**, while **J_attach rises (760 → 1874 /s)** — recruitment refills as fast as
  shedding, holding P_bound up. `f̄_available ≈ J_attach·⟨I_attach⟩` (identity verified in-run).
- **`frac(+impulse)` falls only to 0.45 at +12, and the crossing is at +14** — a freshly-bound head starts
  unstrained (x_bind≈0, PART 2) and strokes forward before the sliding can strain it resistive, so most
  episodes stay net-positive right up to V₀. Beyond V₀ the sliding out-runs the stroke ⇒ net-resistive.

⇒ **Answer to the literal question: within ±12 µm/s, f̄_available does NOT cross zero (it stays positive,
+0.10 at +12) — but the crossing is real and just above the window, at V₀ ≈ 14 µm/s, density-independent.
The intrinsic force–velocity ceiling EXISTS.** (Reconciles the free-gliding "velocity ∝ N, no plateau":
free glide sits at v* < V₀ from the balance N·f̄(v*) = ζ·v*, climbing toward the *fixed* V₀ as N rises — so
it never *looks* saturated at the tested densities, but the clamp exposes V₀ directly.)

---

## PART 2 — the x_bind histogram: the binder ERASES axial strain, at every velocity (but it does NOT abolish the ceiling)

At each attachment we histogram `x_bind = s_head − s_site` (the axial mismatch between the head's arc
projection and the stored material site, nm), immediately after the bond forms.

| v (µm/s) | −12 | −8 | −6 | −4 | −2 | 0 | +2 | +4 | +6 | +8 | +10 | +12 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **x_bind mean (nm)** | −0.12 | −0.08 | −0.06 | −0.04 | −0.02 | **0.00** | +0.02 | +0.04 | +0.06 | +0.08 | +0.10 | +0.12 |
| **x_bind sd (nm)** | 5e-5 | 2e-5 | 4e-5 | 3e-5 | 3e-5 | **0.0** | 3e-5 | 3e-5 | 3e-5 | 3e-5 | 4e-5 | 5e-5 |

**The histogram is a delta at ~0 with zero width, and it does NOT broaden or bias with velocity.** In fact
`x_bind ≡ 0` at bind **by construction**: the binder sets `bindArc` = the head's own arc projection
(`BindingDetectionSystem.bindNearest:341-345`, `bestArc = numer/√denom`), so `s_head − s_site ≡ 0`; the
tiny non-zero mean is *entirely* the one-step read offset `v·dt` (±0.12 nm at ±12), accrued between the bond
forming and the post-step read (sd ≈ 0 confirms every bind is at the identical, uniform value). **So the
binder resets every attachment to zero axial strain regardless of sliding speed** — the strain-erasing
binder / "missing commitment stage" is REAL. **But PART 1 shows it does not abolish the ceiling** — it only
sets V₀ *high* (each fresh bond is maximally productive, so the ensemble stays positive up to V₀ ≈ 14), it
does not push V₀ to infinity. The co-bound tug-of-war + finite lifetime + the sliding out-running the stroke
still produce a finite V₀.

**Supporting code fact (the pre-stroke path).** On the canonical default cycle
(`NucleotideCycleSystem.cycleLymnTaylor`, LYMN_TAYLOR default-on) the pre-stroke path is `bind(ADP·Pi) →
ADP·Pi→ADP (powerstroke)` at the **fixed rate onPi = 1e4/s** — strain-INDEPENDENT, dwell 0.1 ms. The **only**
strain-dependent transition is **post-stroke** (`ADP→NONE`, load-modulated by the Guo–Guilford catch on
signed `forceDotFil`). So a bound head is **committed to the stroke regardless of pre-stroke strain**; there
is no pre-stroke off-ramp. This is a faithful description of the model, but — per PART 1/3 — it is *not* the
reason the free model looks ceiling-less (the ceiling exists at V₀ ≈ 14 anyway).

---

## PART 3 — Variant B (one episode, no rebinding): also positive to +12 ⇒ the ceiling is NOT recruitment-masked

`-fvepisode K`: rebinding blocked (`kinParams[19]=1`) except a fresh cohort re-armed every K steps
(≫ episode length ⇒ each trial is one attachment episode, no replacement). The cohort binds fresh
(x_bind≈0, same initial strain as A at every v), then ages under the clamp with **no replacement of shed
heads**. Pooled over seeds (K=300, 20 k steps, d2000; f̄_bound = per actually-bound head, the clean
denominator-free comparison):

| | v = +6 | v = +12 |
|---|---|---|
| **A f̄_bound (pN)** | +0.47 | **+0.105** |
| **B f̄_bound (pN)** | **+0.72 ± 0.30** (n=5) | **+0.42 ± 0.17** (n=6) |

**Variant B stays clearly POSITIVE through +12** — in fact **higher** than A, because B's bound heads are all
fresh, isolated single-molecule episodes, while A's steady-state population carries older *co-bound* heads in
the tug-of-war (the per-bound efficiency collapse of `DENSITY_SWEEP_coltol8.md`). **K-robustness** (v=12,
seed 0: K=300 → +0.23, K=700 → +0.13, K=1500 → +0.17) confirms f̄_bound is not a re-arm-cadence artifact.
So **removing recruitment does NOT reveal an earlier ceiling** — B crosses zero near the same V₀ as A
(slightly higher, given its higher per-head force). *(An earlier single-seed B sweep read ≈ 0 / −0.47 at
high v — that was small-N noise; the powered pooled value is +0.42, positive.)*

⇒ **The four-way read selects the second row, reframed as calibration not defect:**

| Variant A (full) | Variant B (one episode) | ⇒ Reading |
|---|---|---|
| no crossing *within ±12*; **crosses at V₀ ≈ 14** | no crossing within ±12; **also positive to +12, crosses near V₀** | **intrinsic ceiling EXISTS, density-independent, NOT recruitment-masked** ✅ |
| — | *becomes resistive at high v* (would ⇒ attachment-path / jba) | **REFUTED** — B stays positive |
| f̄_available ok but J_attach too high | | recruitment-only — no (removing it doesn't uncover a ceiling) |
| large negative force, short negative lifetime | | release/braking — the signed catch-slip *does* shed back-strained heads fast (why V₀ is finite-but-high), but it is not *missing* |

---

## Outcome → verdict

**Selected outcome: (2) — the zero-crossing sits above the experimental range ⇒ architecture is sound, the
unloaded speed is mis-calibrated ⇒ CALIBRATION (rates / stroke size / stiffness), NOT a new state.** The
intrinsic force–velocity ceiling **exists** at V₀ ≈ 14 µm/s and is **density-independent**; it is ~1.8× the
biological gliding Vmax (~8 µm/s). Both the full binder (A) and the recruitment-stripped one-episode variant
(B) exhibit it at the same V₀ — so it is intrinsic, not an artifact of continuous recruitment. The
strain-erasing binder (PART 2) and the committed (no-pre-stroke-off-ramp) cycle are real and set V₀ *high*,
but they do **not** remove the ceiling.

**So the "measure before build" question resolves against building:** jba's hypothesis — that the model has
*no* intrinsic ceiling because recruitment keeps offering fresh zero-strain bonds, requiring a single weak /
pre-stroke-strain-gate state — is **refuted by the data**: the ceiling is present (V₀ ≈ 14, density-independent)
and Variant B does not uncover a masked one. **No new weak/commitment state is justified.** If V₀ ≈ 14 vs
biological ~8 matters, the lever is **calibration** (a smaller effective stroke / softer cross-bridge / slower
kinetics lowers V₀), and the separate question of why *free* gliding runs at v* < V₀ (filament-drag balance,
availability, the clamped-vs-free geometry idealization) is where the free-gliding speed puzzle actually lives
— not in a missing motor state.

## Plain synthesis (the deliverable's bottom line)

**Does mean per-motor axial force cross zero within ±12 µm/s?** No — `f̄_available` stays positive through
+12 (+0.10 at +12) at both densities. **But it crosses zero just above the window, at V₀ ≈ 14 µm/s, and V₀
is density-independent (14.3 / 14.8 for d2000 / d4000)** — so **the intrinsic, density-independent
force–velocity ceiling EXISTS**; it is ~1.8× the biological Vmax. **Variant B (recruitment removed) stays
positive to +12 and crosses near the same V₀** — the ceiling is intrinsic, **not** masked by recruitment,
refuting the attachment-path hypothesis. The **x_bind histogram is a delta at ~0 independent of velocity**
(the binder resets every bond to zero strain, and there is no pre-stroke off-ramp) — real, but it only sets
V₀ high, it does not abolish the ceiling. **Verdict: outcome 2 — architecture sound, V₀ mis-calibrated ~1.8×
high ⇒ do NOT build the weak state; if anything, calibrate the stroke/rates/stiffness.** Every measurement is
on the deterministic CPU runner (the basin arbiter — the zero-crossing is a resolved sign change across
+14/+15, not float noise), and every flag is default-off byte-identical.

## Commands
```
scripts/run_gliding.sh -matbox 50 -density 2000 -vclamp 14 -seed 0 12000        # one A point near V₀ (d2000)
scripts/run_gliding.sh -matbox 50 -density 2000 -vclamp 12 -fvepisode 300 20000 # one Variant-B point
scripts/run_fvtest.sh                 # full Variant-A sweep (d2000+d4000, v∈[−12,+12])
scripts/run_fvB_clean.sh              # powered Variant-B + K-robustness
```
Raw: `RUN_LOGS/2026-07-10_fvtest.txt` (A ±12), `RUN_LOGS/2026-07-10_fvA_highv.txt` (A +13…+18, locates V₀),
`RUN_LOGS/2026-07-10_fvB_clean.txt` (B pooled + K-robustness). `-vclamp` / `-fvepisode` default-off ⇒
byte-identical; CPU-only measurement; `BoA-v1ref` untouched.
</content>
