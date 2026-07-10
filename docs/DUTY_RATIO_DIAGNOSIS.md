# Duty ratio: rate bug (τ_off too short) or measurement artifact? — counter audit + single-motor duty

**Date:** 2026-07-09 · **Branch:** dt-convergence-study · **PART A** READ-ONLY (no runs). **PART B** single-motor
**CPU** (deterministic, 256 independent heads, 40k steps; ran concurrent with the GPU falloff sweep — no GPU use,
`singleMolecule` short-circuits before any TaskGraph). `BoA-v1ref` reference-only.

## Fork verdict (up front): **MEASUREMENT ARTIFACT.**

The intrinsic single-head duty **measures 0.0050 (0.5 %)** — skeletal-class, **170× below the dense-sim 0.85**,
not above it. The dense "duty 0.85" is **not a duty ratio**: it is `avgBound / #reachable-heads` — the
instantaneous **occupancy of the reachable-head pool** (a spatial ratio), which the counter itself labels
"fraction of engageable heads bound at any instant" (`GlidingHarness.java:2731`). And τ_off is **physically
timed** (a ~10 ms off-filament ATP→ADP·Pi recovery, enforced by the welded ADP·Pi bind-gate, + a geometric
rebind search), **not** collapsed to the 1-step refractory. **⇒ Do NOT slow rebinding — the intrinsic motor is
already low-duty; the 17× gap was a denominator mirage.** The no-saturation problem lives in the
force-summation / recruitment picture (`DETACHMENT_CEILING_CODEREAD.md`), not in the duty.

---

## PART A — the dense-sim "duty" definition + what sets τ_off (READ-ONLY)

### A1 — Denominator: which heads, over what? **Conditional occupancy of REACHABLE heads.**

`STATS_STEADY_ROW` computes (`GlidingHarness.java:2729-2731`):

```
avgBoundSteady = bSteady / (M − warmStep)                 // mean # BOUND heads per step (steady window)
meanReach      = reachSum / reachN                        // mean # heads with reachCount>0 per step (:2564,:2610)
duty           = avgBoundSteady / meanReach               // "fraction of engageable heads bound at any instant"
```

The denominator is **`meanReach` = the mean count of geometrically-reachable heads** (a head is "reachable" when
a filament segment is within its perp capture window — `bruteReachable`), **not** all motor heads and **not** a
full-cycle time. So the reported 0.85 is **P(bound | currently reachable)** — a *spatial site-occupancy*
fraction, categorically different from the experimental single-molecule **r = τ_on/(τ_on+τ_off)** (a *temporal*
bound-fraction over the head's whole timeline, including the long detached-chemistry phase).

**Denominator bias (why the ratio reads high).** The reachable set is **enriched for bound heads**: a bound head
is *definitionally* reachable and stays in the reachable set for its entire dwell, whereas a free head enters the
reachable set only transiently as a segment passes overhead. So `avgBound/meanReach` structurally **overstates**
occupancy relative to the true fraction-of-time-bound. This is exactly the confound the single-molecule assay was
built to remove (its own comment, `:541`: "the duty is KINETICALLY gated, NOT geometry-confounded").

### A2 — Does the denominator span the full cycle? **No.**

`meanReach` is an **instantaneous count** of reachable heads per step, not bound-time / (bound-time + full
detached-chemistry time). It does not span the ~10 ms detached recovery at all — a recovering (ATP) head that
drifts out from under the filament simply leaves the `meanReach` count. The experimental r's long detached tail
is absent from this denominator.

### A3 — What sets τ_off? **A physically-timed ~10 ms recovery, NOT the 1-step refractory.**

Trace the detached→rebind path on the canonical Lymn-Taylor stack:

1. **1-step refractory** — `kinParams[10] = ceil(MYO_REBIND_TIME / dt)`, `MYO_REBIND_TIME = 1.0e-5 s`
   (`MotorStore.java:127, 272`). At dt=1e-5 that is **1 step = 0.01 ms** — negligible, and *by itself* it would
   indeed make τ_off ~1000× too short (the failure mode the task flagged). **But it is not what gates rebinding.**
2. **The real gate — off-filament hydrolysis recovery.** Release is `NONE→ATP` (Lymn-Taylor detachment,
   `NucleotideCycleSystem.java:461`), leaving the head in **ATP**. The **ADP·Pi bind-gate is welded on** under
   Lymn-Taylor (`GlidingHarness.java:87`, `kinParams[20]=1`): a head may bind actin **only in ADP·Pi**. So the
   detached head must first hydrolyze **ATP→ADP·Pi at the off-filament rate `offATP = 100/s` ⇒ ~10 ms**
   (`MotorStore.java:388`, `nucParams[3]`), then it **waits primed** (`offFilADPPi_ADP = 0`, `:390`) until it
   rebinds. **τ_off ≥ ~10 ms of mandatory detached chemistry** — a genuine detached-state residence, not a
   collapsed refractory.
3. **+ geometric rebind search** — the primed head must also drift back into capture reach (dominant in the
   sparse single-molecule geometry; ≈0 in the dense bed where a segment is always overhead).

So τ_off is set by **hydrolysis recovery (10 ms) + rebind search**, not the 1-step refractory. *v1ref* (reference
only) uses the same `myoRebindTime` (Env.java:832) plus its own nucleotide recovery — SoftBox's rebind timing is
faithful, not a shortcut.

### A4 — τ_on and the recomputed experimental duty

τ_on is set by the bound cycle's rate-limiter **ADP→NONE at `onADP = 1e3/s` ⇒ ~1 ms** (`MotorStore.java:391`,
`nucParams[6]`; the ADP·Pi→ADP powerstroke at 1e4/s and NONE→ATP detach at 2e4/s are faster tails). So the
**code-predicted intrinsic** r = τ_on/(τ_on+τ_off) ≈ 1 ms /(1 ms + 10 ms) ≈ **0.09 as a ceiling** (head always
in reach), falling further once the geometric search is included — i.e. **~0.05-class, not 0.85.** PART B
measures it directly.

---

## PART B — single-motor intrinsic duty (CPU, `-single -lymntaylor`, 40 000 steps, 256 heads, unloaded)

Command: `scripts/run_gliding.sh -single -lymntaylor 40000` (CPU-deterministic; Config-1 motor, FIXED filament,
canonical Lymn-Taylor + welded ADP·Pi bind-gate; warm = 10 000, measured over the last 30 000 steps).

| quantity | measured | skeletal ref |
|---|---|---|
| **duty r = τ_on/(τ_on+τ_off)** | **0.0050 (0.50 %)** | ~0.05 |
| τ_on (mean bound lifetime) | **0.91 ms** | ~1–5 ms |
| τ_off (derived: τ_on·(1/r − 1); attach-rate cross-check 5.5/s ⇒ 1/5.5 s) | **~180 ms** | ~tens of ms |
| mean forceDotFil (intrinsic load) | −0.36 pN (near-unloaded) | ~0 |

**The intrinsic single-head duty is 0.005 — if anything *below* skeletal, nowhere near 0.85.** τ_on ≈ 0.91 ms
matches the A4 prediction (onADP-limited ~1 ms). τ_off ≈ 180 ms here is **long** (the sparse single-molecule
geometry adds a big rebind-search wait on top of the 10 ms recovery — `in-reach fraction while free ≈ 0`), the
opposite of "collapsed." Both the direct temporal measurement and the attach-rate cross-check agree.

**Reconciling 0.005 (intrinsic) with 0.85 (dense).** Two independent factors, both denominator/definition, not
kinetics: (i) the dense denominator is `#reachable` **enriched for bound heads** (A1), and (ii) dense reachable
heads have **no rebind-search wait** (a segment is always overhead), so their occupancy is set by
τ_on/(τ_on+τ_recovery) ≈ 0.6 ms /(0.6+10) ≈ 0.06 — still far below 0.85, so the residual gap to 0.85 is the (i)
bound-head enrichment of the reachable set. **Either way, 0.85 is a reachable-pool occupancy, not the head's
duty ratio.**

**Load (T2) — mechanism present, but not isolable via this harness knob.** `-fext 3` (a +3 pN resisting load)
left t_on **identically 0.91 ms** — because `-fext` injects `kinParams[18]` into the *legacy* `catchSlipRelease`
only, and the canonical Lymn-Taylor cycle (`cycleLymnTaylor`) reads `forceDotFil`/`forceDotAvg` **directly and
ignores `kinParams[18]`**. So the load knob is **inert on the LT path** (a harness limitation, flagged — not a
physics result). The load-modulation of dwell **does** exist in code: the ADP→NONE rate is multiplied by the
signed Guo–Guilford catch-slip `g(F) = αCatch·e^(−F·xCatch/kT) + αSlip·e^(+F·xSlip/kT)`
(`NucleotideCycleSystem.java:449-452`) — a forward-strained (resisting) head's dwell lengthens (catch), a
back-strained one shortens (slip). Isolating it at the single-molecule level would need `-fext` wired into
`cycleLymnTaylor` (a one-line additive change, deferred — not in this read-only/reuse scope).

---

## Plain statement (the deliverable's bottom line)

**Is the intrinsic single-head duty ~0.05 or ~0.85?** **~0.005 (0.5 %) — skeletal-class, measured directly** on
a single motor. **Is τ_off physically timed or collapsed?** **Physically timed** — a mandatory ~10 ms
off-filament ATP→ADP·Pi hydrolysis recovery (`offATP=100/s`) enforced by the welded ADP·Pi bind-gate, plus the
rebind search; the 1-step (0.01 ms) refractory is present but is *not* what gates rebinding. **So the dense-sim
0.85 is a MEASUREMENT ARTIFACT** — `avgBound/#reachable`, a conditional occupancy of the (bound-head-enriched)
reachable pool, not the single-molecule duty ratio. **The motor is not high-duty; do NOT slow rebinding** (that
would over-suppress engagement and stall the glide). The anchored single-head **τ_on ≈ 0.9 ms** (⇒ d/τ_on ≈
6–8 µm/s for a 5–7 nm stroke) is the number to test the dense-sim velocity against — the no-saturation story
stays in force-summation / recruitment (`DETACHMENT_CEILING_CODEREAD.md`), not the duty.

**Two honest caveats.** (1) The `-single` assay runs the **Config-1** motor (F9-free J1-Hookean), not the
sphere-head production default — but duty is a *kinetic* quantity set by the **shared** Lymn-Taylor cycle + ADP·Pi
rebind gate, so the intrinsic-duty conclusion transfers; the cross-bridge *force law* differing does not change
τ_on/τ_off. (2) `-fext` is inert on the LT path (above), so the load-sensitivity of duty was read from code, not
measured — flag for a follow-up if the T2 dwell-vs-load curve is wanted at single-molecule scale.
</content>
