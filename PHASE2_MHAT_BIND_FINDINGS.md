# Phase-2 sphere-head — the head-axis (mhat) sign: census, then bind-time init from polarity

**Date:** 2026-07-01. Flag-gated (`-mhatcensus`, `-mhatset`), default byte-identical; `BoA-v1ref` untouched
(NOT the reference — the sphere-head motor is the active model, FIXED_HEAD_NECK_STROKE_MOTOR §9). No
kinetics/geometry retune. All speeds = LONG_ROW LS-centroid drift along f̂ over the on-bed ≥1 s window
(PROPER_SPEED_ANALYSIS §2), GPU `-full` 14×2 bed, d1000, seed 0x6111D.

## The DOF (why there is a *second* free sign)
Under `-hfswing -rollsign` the head is oriented at bind by two compliant locks:
- **F9 ⊥ hold** pins `û_head ⊥ f̂` (the motor–actin angle → 90°/120°) — the |90°| magnitude, sign-free.
- **roll lock (`-rollsign`)** pins `ŷ_head = +ŝ` (ŝ = n̂bed×f̂, from polarity). PHASE2_ROLLSIGN: 99.5% +ŝ.

With `ŷ_head=+ŝ` (in-plane, ⊥ f̂) and `û_head ⊥ f̂` and `û_head ⊥ ŷ_head` (orthonormal body frame), `û_head`
is pinned to **±n̂bed (±ẑ)** — and that **sign is still free**. It is load-bearing because the head-frame
swing direction is `p = ŷ_head × û_head`:
- `û_head = +ẑ` ⇒ `p = (ẑ×f̂)×ẑ = f̂` ⇒ rear sweeps **barbed-ward** (productive, ≡ `-dirswing`).
- `û_head = −ẑ` ⇒ `p = −f̂` ⇒ rear sweeps **pointed-ward** (anti-productive, cancels the glide).

So `mhat = head.uVec` sign along ±n̂bed is a SECOND free sign, distinct from the roll sign, and the wrong
pole strokes backward. **Productive pole = +n̂bed = +ẑ (lab +Z).**

## STEP 0 — v2's mhat census + convention (measure before changing anything)
`GlidingHarness -gpu -full -grid -rollsign -mhatcensus -rollcensus -density 1000 150000`
(`CrossBridgeSystem`/`GlidingHarness.mhatTally`: sign of `head.uVec·n̂bed = head.uVec_z` over all bound-head
samples).

```
ROLL_CENSUS  head-samples=20733   +ŝ=99.5%   −ŝ=0.5%           (rollsign working; roll axis+sign fixed)
MHAT_CENSUS  head-samples=20733   +ẑ=52.4%   −ẑ=47.6%   ⇒ q=0.476, (1−2q)=0.047
MHAT per-sample +ẑ fraction: mean=0.5235  stdev=0.1441  (n=1499)
MHAT +ẑ-frac vs time: 0.13 0.78 0.27 0.53 0.80 0.78 0.36 0.71 0.46 0.50 0.38 0.36 0.92 0.57 0.64 0.43 0.40 0.22 0.43 0.79 0.75
```

**v2's mhat is ~50/50 (mean 0.52, sd 0.14), fluctuating with NO drift — exactly like BoA.** The diagnosis
matches; the STEP-1 bind-time fix applies. **Convention resolved:** v2's head-frame law
(`CrossBridgeSystem.directedSwingHeadFrame`) reads `motorUVec` at the head slot (`3m+2`) directly as the head
axis and forms `p = ŷ_head × û_head` from it — there is **no reversed `uVecR`-style variant** in v2's swing
law (unlike BoA's uVec/uVecR gotcha). So `motorUVec` IS the productive head axis; the pole to target is
`+ẑ` (verified: forcing +ẑ makes the head-frame swing barbed-ward — see STEP 2 census 100% +ẑ).

## STEP 1 — set the mhat sign AT BIND (init only, NO persistent torque)
`CrossBridgeSystem.setBindMhat` (`-mhatset`, implies `-rollsign`): at each **fresh bind** (`boundSeg`
free→bound, per-motor `prevBound` gate), orient the head to the fully stereospecific productive pose
`û_head=+ẑ`, `ŷ_head=+ŝ` (from polarity). Placed **LATE** in both runners (after `derive`, mirroring
`snapCanonicalHead`) — the body-pose-write-must-be-late PTX gotcha; takes effect next step. Per-motor pure
(writes only head slot `3m+2` + `prevBound[m]`) ⇒ race-free, no atomics, CPU≡GPU. **No new per-step torque.**

Two variants were needed (the first reintroduced a pin):

- **(1a) hard snap** — overwrite `û_head=+ẑ`, `ŷ_head=+ŝ` at bind, leaving the head center. This jumps the
  head tip ~½·HEAD_LEN (10 nm) off the just-bound site (the head arrives at an *un-oriented* pose — F9/F10
  have not acted yet), slamming the F8 spring ⇒ catch-slip detonates.
- **(1b) impulse-free** — reorient the head **about its bound tip**: preserve `htip = hc + ½·HEAD_LEN·û_old`,
  set `û_head=+ẑ`, `ŷ_head=+ŝ`, then `hc = htip − ½·HEAD_LEN·ẑ`. The F8 spring vector (site − tip) is
  UNCHANGED ⇒ no catch-slip release impulse (the head center + J1-end move instead; J1 does not feed the
  catch-slip). This is the physically-correct "reorient the bound head without disturbing the attachment."

## STEP 2 — LONG dense-mat assay (d1000, `-full`, LS-centroid; avgBound ~14 = the pin guardrail)

| model | v_axial | axial frac | avgBound | mhat +ẑ census (mean, sd) |
|---|---:|---:|---:|---:|
| `-dirswing` (f̂-referenced, the target) | **−3.02** | 0.996 | 14.8 | (n/a) |
| `-hfswing -rollsign` (baseline, mhat **free**) | −1.65 | 0.999 | 13.9 | 52.4% (0.52, 0.14) |
| `-mhatset` **(1a) hard snap, all binds** | −0.20 | 0.70 | **1.02** | 100.0% (1.00, 0.009) — HELD |
| `-mhatset` **(1b) impulse-free, all binds** | −1.03 | 0.991 | **4.33** | 99.8% (0.998, 0.024) — HELD |
| `-mhatset` **(1c) impulse-free, wrong-pole only** | −0.94 | 0.999 | **5.98** | 97.2% (0.975, 0.068) — HELD |

**Every `-mhatset` census HOLDS at ~100% +ẑ, flat vs time (all "1.00" per-sample)** — so the bind-time set
STICKS; the mhat sign does **not** decay back to 50/50 over the bound lifetime. **But avgBound never returns
to ~14** — it collapses (1a hard snap 13.9→1.0) or is strongly depressed (1b impulse-free →4.3), and the net
glide does **not** recover (−0.20 / −1.03, both *below* the free-sign baseline −1.65).

**Why setting the sign costs binding (the mechanism):** at the fresh-bind instant the head is **un-oriented**
(F9/F10 have not acted — they orient it compliantly over the ~0.6 ms bound lifetime), and the head is
**over-determined by TWO attachments**: F8 (head-tip↔filament site) AND J1 (head-end1↔lever). Reorienting it
to the +ẑ pole fights one of them:
- **(1a) hard snap** jumps the tip ~½·HEAD_LEN off the site ⇒ the **F8** spring detonates ⇒ catch-slip
  releases ⇒ avgBound→1.0. (A pin was reintroduced.)
- **(1b) impulse-free-on-F8** reorients about the tip (F8 preserved) but then moves head.end1 ~HEAD_LEN ⇒ the
  **J1** spring yanks the head over the next steps ⇒ the tip is dragged off the site ⇒ catch-slip still
  releases ⇒ avgBound→4.3. The impulse merely moved from F8 to J1; it did not vanish.

The wrong-pole heads arrive near −ẑ (fast rebinds keep their recent ±ẑ orientation), so flipping them to +ẑ is
intrinsically a **~180° reorientation** — there is no *gentle* pole-flip. The head's ±ẑ sign is not an
independent free knob you can reset at bind; it is over-determined by the two springs + the compliant locks.

**(1c) impulse-free, wrong-pole-only** (touch only the ~48% arriving at −ẑ; leave the ~52% already
productive exactly as-arrived — the minimal possible intervention): census still **HELD at 97.2% +ẑ** (no
decay), avgBound recovered to **5.98** (vs 4.33 when all binds are touched — as expected, fewer disturbances)
but still **far below 13.9**, and the glide is **−0.94** — still *below* the free-sign −1.65. Even the
minimal intervention that touches only the wrong-pole heads and leaves productive attachments untouched
depresses binding and lowers the net glide.

**The trend is monotonic in "amount of bind-time reorientation":**

```
intervention            avgBound   v_axial   census +ẑ
none (free sign)          13.9      −1.65      52%   ← baseline
wrong-pole only (1c)       6.0      −0.94      97%
impulse-free, all (1b)     4.3      −1.03     100%
hard snap, all (1a)        1.0      −0.20     100%
```

More reorientation ⇒ lower avgBound ⇒ lower net glide. The sign is always fixed (census ≥97%), but the
binding cost always dominates the per-head directedness gain. There is no setting that recovers −3.0 or even
matches the free-sign −1.65.

## The three-way fork — verdict
The task's three branches all presupposed **avgBound~14** (the pin guardrail). v2 lands in **none of them
cleanly** — it is a **fourth outcome**:

> **The mhat sign HOLDS once set (census ~100% +ẑ, no decay), but SETTING it at bind is not free — it costs
> avgBound (13.9→4.3, worse →1.0), so the net glide does not recover; it drops below the free-sign −1.65.**

- NOT branch A: avgBound does **not** stay ~14 and speed does **not** recover toward −3.0.
- NOT branch B ("census decays back"): the census does **not** decay — the sign is perfectly retained
  (99.8% held). What fails is binding, not retention.
- NOT branch C ("sign stays set, speed stays 2.15 unchanged"): speed does **not** stay put — it **falls**.

**FINAL VERDICT:** the mhat 50/50 is a real second free sign, and the bind-time set holds it perfectly, **but
it is NOT a recoverable speed lever** — every bind-time reorientation (minimal→maximal) depresses avgBound and
lowers the net glide, monotonically. The head-axis sign is over-determined by the head's two attachments and
cannot be stereospecifically set at bind without disturbing one. `-hfswing -rollsign` (−2.15/−1.65, mhat free)
is the honest head-referenced glide; `-dirswing` (−3.0) sidesteps the DOF by referencing f̂ directly.

**Consequence:** the mhat 50/50 is a **genuine second free sign** (STEP 0), but — unlike the roll sign, whose
bind-time lock (`-rollsign`) is *cheap* (avgBound/lifetime unchanged, PHASE2_ROLLSIGN) — the **head-axis sign
cannot be fixed by a cheap bind-time init**, because a stereospecific head axis requires reorienting an
un-oriented, doubly-tethered head, and that reorientation disturbs an attachment. A *persistent* torque to
hold it would pin-absorb (BoA). So **−2.15/−1.65 (`-hfswing -rollsign`) stands as the honest head-referenced
glide**, and the mhat free sign is a **structural DOF of the doubly-tethered sphere-head under the head-frame
law**, not a recoverable lever. `-dirswing`'s −3.0 remains the (f̂-referenced, orientation-independent)
modeling convenience that structurally sidesteps this DOF.

## Release pathway (the confound, pinned)
Every run here (`-rollsign`, `-dirswing`, `-mhatset`) uses the **default catch-slip-only-on-F8 release**:
`NucleotideCycleSystem.catchSlipRelease` (Guo–Guilford force-dependent catch-slip on `forceDotFil` =
`Dot(F8, seg.uVec)`, the anchor-spring load along the filament) + the standard 4-state
`NucleotideCycleSystem.cycle` (NONE→ATP→ADPPi→ADP, load-gated ADP→NONE). None of
LYMN_TAYLOR / ATP_RECHARGE / TAU_AVG / CONFIG1 is set. **This is the same catch-slip-on-F8 pathway as BoA's
fixed-head motor** (§9.6–9.7) — so the −3.02 / −1.65 / mhat spread is a pure stroke-/orientation-law
difference at identical release, directly comparable. (A clean v2→v1 catch-slip-only map for an
apples-to-apples *speed* comparison is a separate later task.)

## Run
```
GlidingHarness -gpu -full -grid -rollsign -mhatcensus -rollcensus -density 1000 150000   # STEP 0 census
GlidingHarness -gpu -full -grid -mhatset  -mhatcensus            -density 1000 150000     # STEP 2 (bind-time set)
GlidingHarness -gpu -full -grid -dirswing                       -density 1000 150000     # −3.0 reference
```
New: `-mhatcensus` (`GlidingHarness.mhatTally`), `-mhatset` (`CrossBridgeSystem.setBindMhat`, impulse-free
wrong-pole-only bind-time head-axis init). Default byte-identical; `BoA-v1ref` byte-clean.

**CPU≡GPU:** `setBindMhat` is per-motor PURE — motor m reads its own head slot (`3m+2`) + `filUVec[boundSeg]`
and writes only its own head coord/uVec/yVec + `prevBound[m]`; no reduction, no atomics, no KernelContext ⇒
**bit-identical CPU↔GPU by construction** (as for every other per-motor-pure gliding kernel). It is placed
LATE in both runners (after `derive`) so the CPU and GPU bind→set→next-step-bond timing is identical. The
mat-scale aggregate diverges only at the standing chaotic float-noise level (aggregate-within-SEM standard);
the CPU runner binds cleanly under `-mhatset` (smoke: avgBound ~6–7 at d500), consistent with the GPU runs.

## The pin-guardrail cross-check (why this is a real DOF finding, not an implementation bug)
The task's guardrail — "avgBound ~14, a drop means you reintroduced a pin" — is exactly what happened, but it
is **intrinsic**, not a coding slip: the wrong-pole heads arrive near −ẑ (fast rebinds retain their orientation),
so productive-pole alignment is a **~180° reorientation of a doubly-tethered head** with no gentle form. F8-first
(hard snap) and J1-first (impulse-free) both release the head; only the *route* of the impulse differs. This is
the structural signature of a genuinely over-determined DOF, not a fixable init — matching BoA's separate finding
that a *persistent* mhat torque pin-absorbs.
</content>
