# Is the canonical gliding bind deterministic (kOn = ∞) or rate-limited? — READ-ONLY code survey

**Date:** 2026-07-10 · **Branch:** dt-convergence-study · **READ-ONLY** (no edits, no runs — concurrent-safe).
`BoA-v1ref` reference-only. Companion negatives that this survey *explains*: `COLTOL_REGIME_SWEEP.md`,
`RECRUIT_SHED_BALANCE.md`, `AZIMUTHAL_FALLOFF_INCREMENT3.md`, `DENSE_DUTY_GAP.md`.

## Plain statement (up front)

**On the canonical gliding path a head binds DETERMINISTICALLY the step it becomes eligible — the effective per-encounter
kOn is INFINITE.** Once a candidate motor↔segment pair passes the geometric reach predicate (and, on the Lymn–Taylor
default, the ADP·Pi nucleotide gate), it is accepted **unconditionally with no `u < kOn·dt` draw**. There is **no
per-encounter binding-rate clock** on the canonical path. This is exactly why every eligibility-gate sweep
(orientation, coltol, brake-hold/recruit-shed) only **scaled the pool** and none crossed into the release-limited /
saturated regime: those gates change *which / how many* heads are eligible, but any eligible head still binds
**instantly**, so a reachable site refills as fast as the standing primed pool allows — **the refill clock the timescale
hypothesis wanted to turn does not exist to be turned.**

One clock *does* exist, but it is the wrong one for the sweeps: the **ADP·Pi nucleotide-recovery gate** (~10 ms) delays
**re-binding of a just-released (spent) head**, which has hydrolysis to complete. It does **not** throttle the
deterministic capture of the never-bound primed heads the density sweeps ride on (a gliding filament sweeps onto fresh
ADP·Pi heads and leaves its spent heads in its −x wake, out of reach — `DENSE_DUTY_GAP.md`). So operationally the
capture is **supply(density)-limited with instantaneous, deterministic acceptance**.

The historical rate binder **exists and is wired for a kOn draw** (`BindingDetectionSystem.bindRate`, `:818`) but is
**orphaned** off the canonical path (reachable only via `-ratesearch` in `V2OneXHarness`). Restoring a finite, swept
kOn is therefore a small, localized change — the hook is already there.

---

## Q1 — Which binder does the canonical default gliding path call?

**Canonical default = `-gpu -full -grid`** with no azimuthal flags (springs / Lymn–Taylor+ADP·Pi / xbimplicit2 /
sphere-head, AXLOCK, DIRSWING). The per-step bind dispatch is `GlidingHarness.java:737-745`:

```java
if (CANONICAL)                                            // -canonical  (Phase-2 two-point motor; OFF by default)
    BindingDetectionSystem.bindCanonicalTwoPoint(...);
else if (AZ_FALLOFF)                                      // -azfalloff  (Inc-3 graded affinity; OFF)
    BindingDetectionSystem.bindNearestFalloff(...);
else if (AZ_GATE)                                         // -azimbind   (Inc-2 hard azimuthal gate; OFF)
    BindingDetectionSystem.bindNearestAzim(...);
else                                                      // ← THE CANONICAL DEFAULT PATH
    BindingDetectionSystem.bindNearest(mot.head, mot.uVec, mot.rodUVec, f.end1, f.end2,
        sc.reachSeg, sc.reachCount, mot.boundSeg, mot.bindArc, mot.nucleotideState, mot.kinParams, mot.counts);
```

With `CANONICAL = false` (`:48`), `AZ_FALLOFF = false` (`:170`), `AZ_GATE = false` (`:168`), the canonical path
falls through to **`bindNearest`** (`GlidingHarness.java:745` → `BindingDetectionSystem.java:356`).

## Q2 — At the final accept, deterministic or a rate draw?

**DETERMINISTIC.** `bindNearest`'s decision chain (`BindingDetectionSystem.java:356-396`), in order:

1. `if (boundSeg.get(m) != FREE_BINDABLE) continue;` — only a free, non-refractory head (`:366`).
2. `if (kinParams.get(19) > 0.5f) continue;` — `-nobind` measurement control, default 0 (`:368`).
3. `if (adppiGate && nucleotideState.get(m) != NUC_ADPPI) continue;` — the ADP·Pi gate (`:374`; see Q5).
4. Loop over broad-phase candidates; keep the **nearest** passing `reachTestDistSq(...) ≥ 0` (the v1 geometric
   reach predicate: `head-perp distance < myoColTol` **and** `motDotFil ≥ alignTol` **and** `rodDotFil ≥ 0`).
5. **Accept — the exact line (`BindingDetectionSystem.java:394`):**

```java
if (bestSeg >= 0) { boundSeg.set(m, bestSeg); bindArc.set(m, bestArc); }
```

There is **no random number** anywhere in `bindNearest` — no wang-hash, no `u < kOn·dt`, no `u < pBind`. The nearest
reachable segment is bound the same step, unconditionally. **Effective per-encounter kOn = ∞.**

(For contrast: the `-azfalloff` binder had to **add** an RNG draw precisely because none existed —
`AZIMUTHAL_FALLOFF_INCREMENT3.md`: *"The gliding bind path is deterministic-nearest with no existing draw, so a draw was
added"* (a race-free `u < a_best`, salt "AZBD"). That draw lives only in `bindNearestFalloff`, off the canonical path.)

## Q3 — Is there a historical kOn rate, and is it consumed on the canonical path or bypassed?

**A rate binder exists and is fully wired for a kOn draw — but it is orphaned off the canonical path.**

- **`bindRate` (`BindingDetectionSystem.java:818-912`)** — the "binding-search reformulation." It reads
  **`kOn = kinParams.get(14)`** (`:830`), computes a swept-path-average chord Δl through the capture sphere, and
  draws twice:
  - **`pBind = 1.0f − exp(−totalRate·dt)`** with `totalRate = kOn·(chordSum/NSAMP)` (`:870`), accepted by a wang-hash
    `if (u >= pBind) continue;` (`:872-874`, salt "BRAT" 0x42524154);
  - a second draw selects the segment ∝ its rate (`:877`).
  Accept line `:910`: `if (chosen >= 0) { boundSeg.set(m, chosen); bindArc.set(m, chosenArc); }`. **This is the
  historical finite-kOn binder.**
  **Call sites:** only `V2OneXHarness` under **`-ratesearch`**. **Not on the canonical GlidingHarness path.**

- **`bindKinetics` (`:292-348`)** — faithful v1 kinetics. Its **binding is also deterministic** (nearest reachable,
  accept `:345`); only its **release** is stochastic (`u < pOff`, `pOff = kOff·dt`, `:319-322`). No kOn draw.
  **Call sites:** the eight isometric / non-gliding motor harnesses (`MotorBindingHarness`, `MotorStrokeHarness`,
  `MotorXBridgeHarness`, `DimerGlideHarness`, `MiniGlideHarness`, `ProteinNodeHarness`, `EomStabilityHarness`,
  `CanonicalMotorHarness`). **Not on the canonical gliding path.**

- **`bindCanonicalTwoPoint` (`:586`)** — the Phase-2 two-point motor. It **does** carry a reaction-limited kOn gate
  (`pBind = 1 − exp(−kOn·Δl·dt)`, `:636-642`, salt "C1KO"), reading the same `kinParams[14]=kOn`. **On the canonical
  path only under `-canonical`** (OFF by default) — so also bypassed for the bare `-gpu -full -grid` run.

**The kOn parameter:** `kinParams[14]`, units µm⁻¹·s⁻¹, **default 0.0** (`MotorStore.setSearchParams`, `:340-342`;
exposed via `-kon`, `GlidingHarness.java:240`). It is consumed **only** inside `bindRate` / `bindCanonicalTwoPoint`,
neither of which the canonical default reaches. **So a historical kOn draw exists, is correctly wired, and is
completely bypassed on the canonical gliding path.**

## Q4 — The refractory gate timescale (`kinParams[10] = MYO_REBIND_TIME`)

**Negligible — ≈ 1 step, far below τ_on.**

- `MYO_REBIND_TIME = 1.0e-5 s` (`MotorStore.java:127`); `kinParams[10] = ceil(MYO_REBIND_TIME / dt)`
  (`MotorStore.java:272`). At the gliding dt (1e-5) that is `ceil(1.0) = 1` step; at Constants dt=1e-4 it is
  `ceil(0.1) = 1` step. **Either way ≈ 1 step = 0.01–0.1 ms.**
- Consumed as a countdown in `NucleotideCycleSystem` release paths: release sets `boundSeg = FREE_COOLDOWN` +
  `cooldown[m] = refractorySteps`; each step decrements, and at 0 → `FREE_BINDABLE` (`NucleotideCycleSystem.java:203-209`).
  (`bindKinetics` inlines the same one-step COOLDOWN→BINDABLE flip, `:323-324`.)
- **vs τ_on ≈ 0.6 ms** (measured dwell, `DENSE_DUTY_GAP.md`, `COLTOL_REGIME_SWEEP.md`): the refractory is ~6–60×
  **shorter** than a single bound dwell. It is a one-step de-bounce, **not** a meaningful detached residence. **It
  imposes essentially no refill delay.**

## Q5 — The real refill clock: the ADP·Pi nucleotide-recovery gate (and why it doesn't save the sweeps)

The one genuine refill delay on the canonical path is **nucleotide**, not kinetic:

- `adppiGate = kinParams.getSize() > 20 && kinParams.get(20) > 0.5f` (`BindingDetectionSystem.java:366`); enforced by
  `if (adppiGate && nucleotideState.get(m) != NUC_ADPPI) continue;` (`:374`). Only a head in **ADP·Pi (NUC_ADPPI=2)**
  can strong-bind.
- On the **canonical Lymn–Taylor default it is welded on**: `if (LYMN_TAYLOR && !ALLOW_BIND_ANY) ADPPI_BIND = true`
  (`GlidingHarness.java:310`) ⇒ `kinParams[20]=1` (`DENSE_DUTY_GAP.md` §1c).
- A just-released head is in NONE/ATP and must hydrolyze ATP→ADP·Pi at **100 /s ⇒ τ ≈ 10 ms** (`nucParams[2]`,
  `MotorStore.java:387`) before it can rebind. So τ_off ≥ ~10 ms, per-head duty ~0.06 (`DENSE_DUTY_GAP.md`).

**Why this ~10 ms clock does not turn the sweeps into a release-limited regime:** it gates **re-binding of spent
heads**, and a gliding filament almost never re-binds a spent head — it glides forward onto **fresh, never-bound
ADP·Pi heads** and leaves the recovering ones in its −x wake, out of geometric reach (`DENSE_DUTY_GAP.md`: the ~17×
recovering heads are absent from `meanReach`). The capture the density/coltol sweeps measure is the **deterministic,
instantaneous** acceptance of the standing primed pool. The nucleotide clock throttles the reuse channel the sweeps
don't ride. Hence: **there is no per-encounter binding rate on the canonical path, and the only real refill clock
acts on the wrong population** — exactly the "eligibility gates only scale" signature of `COLTOL_REGIME_SWEEP.md`,
`RECRUIT_SHED_BALANCE.md`, `AZIMUTHAL_FALLOFF_INCREMENT3.md`.

## Effective-kOn verdict

**INFINITE (permanent supply-limitation) for the capture channel the sweeps exercise.** A reachable, primed,
non-refractory head binds the step it becomes eligible, deterministically, with no rate draw. Combined with the fact
that (a) the refractory `kinParams[10]` is ~1 step (negligible) and (b) the ~10 ms ADP·Pi recovery only delays
**re-binding of out-of-reach spent heads**, the system has **no tunable per-site refill clock**. Every eligibility
gate (orientation / coltol / brake-hold) can only change the eligible **pool size**, never the per-encounter binding
**rate** — so none can push occupancy off the supply-limited branch, which is precisely what those three sweeps
observed. **The timescale/regime-crossover hypothesis was not testable with the eligibility gates because the refill
clock they assume does not exist on the canonical path.**

## Q6 — Localization: restoring a finite, swept kOn

Two clean options; the first is the historically-correct hook.

1. **Re-route to `bindRate` (preferred — the hook already exists).** `bindRate` (`:818`) already: reads
   `kinParams[14]=kOn`, computes the swept-path chord (it takes an extra `headPrev` buffer for Δl), draws
   `u < 1−exp(−kOn·Δl·dt)`, and is race-free / CPU≡GPU-safe (wang-hash, salt "BRAT"). The change is a **one-branch
   edit in `GlidingHarness`**: add a `-glidekon <k>` flag that, when `kOn > 0`, routes the canonical `else` branch
   (`:745`) to `bindRate` instead of `bindNearest` (passing `mot.headPrev`, already maintained for the swept path).
   Default `kOn = 0` / flag off ⇒ **byte-identical** to today's `bindNearest`. kOn becomes the swept saturation
   parameter. (Note: `binding-search-reformulation` found kOn is **not a dt lever** — capture flux is already
   ≈dt-invariant — but its use as a **saturation** lever, throttling refill to cross into release-limited, is
   untested and is the open question this survey enables.)

2. **Single-site gate on `bindNearest` (smallest surgical change).** Add, immediately before the accept at
   `BindingDetectionSystem.java:394`, a race-free wang-hash draw gated by a new param — e.g.
   `if (kOnGate && u ≥ 1−exp(−kOn·dt)) { /* no bind this step */ }` — reusing the exact salt/draw pattern already
   proven in `bindNearestFalloff` (salt "AZBD"). Default param 0 ⇒ always-bind ⇒ byte-identical (modulo the RNG-draw
   decorrelation the `-azfalloff n=0` recovery already characterized as aggregate-equal). This gates the **single
   accept site** without needing the `headPrev` swept-chord machinery, at the cost of using a per-step (not
   per-encounter-length) rate.

**Recommendation:** option 1 — `bindRate` is the historical, physically-motivated (reaction-limited, path-swept)
binder, is already validated (`-ratesearch`), and needs only to be re-wired to the canonical gliding branch behind a
swept `-glidekon`. This is the direct test of "does a finite per-site kOn cut cross into release-limited saturation,
where every eligibility gate only scaled."

---

## Provenance / scope

- READ-ONLY survey. No edits, no runs, aorus untouched. `BoA-v1ref` reference-only.
- Crux facts (the two the task asked to pin): canonical path binder = **`bindNearest`** (`GlidingHarness.java:745`);
  accept line = **`BindingDetectionSystem.java:394` `boundSeg.set(m, bestSeg)` — deterministic, no draw**.
- The finite-kOn binder `bindRate` (`:818`, `pBind = 1−exp(−kOn·Δl·dt)`, `kinParams[14]`) exists and is wired but is
  **bypassed** (only `V2OneXHarness -ratesearch`).
- Refractory `kinParams[10]` = ~1 step (0.01–0.1 ms) ≪ τ_on ~0.6 ms (negligible). Real refill clock = ADP·Pi
  recovery ~10 ms, but it gates **spent-head reuse**, not fresh-pool capture.
</content>
</invoke>
