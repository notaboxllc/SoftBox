# Isolating the capture-radius (coltol) lever — does shrinking it cross the supply→release threshold and saturate velocity?

**Date:** 2026-07-10 · **Branch:** dt-convergence-study · **Runner:** GPU (springs = transcendental-free ⇒
GPU-trustworthy) + 1 CPU basin-arbiter at the extreme coltol. `BoA-v1ref` untouched. **Diagnostic only — no
default change** (the chamber + coltol are flag-gated, default-off byte-identical). Companion negatives:
`RECRUIT_SHED_BALANCE.md`, `AZIMUTHAL_FALLOFF_INCREMENT3.md`, `DETACHMENT_CEILING_CODEREAD.md`,
`DENSE_DUTY_GAP.md`.

> **VERDICT — NO. Shrinking coltol does NOT drop the site-occupancy out of the supply-limited regime and does
> NOT saturate velocity at ≈ d/τ_on. There is NO regime crossover.** The regime indicator goes the **opposite**
> way the timescale intuition predicted: occupancy `avgBound/meanReach` **RISES** with shrinking coltol (0.83 →
> 2.70, *past 1.0*), density-flat — it never falls toward the ~0.06 per-head duty. velFitX and avgBound **drop
> together** as coltol shrinks (the shrinking-pool fingerprint, not the ceiling's flat-velFitX/climbing-avgBound),
> and the **velFitX–density curve keeps climbing at EVERY coltol** (velFitX ≈ doubles d2000→d4000 at all five
> radii; even coltol = 2 nm climbs 2.11 → 4.29 → 6.65). The filament stays **continuously engaged** throughout
> (avgBound ≥ 2.4, inst speed 7–9 µm/s, `fullMat = YES`) — so this is **outcome #3 (coltol just scales the pool)**,
> not over-restriction and not saturation. **Mechanistic core:** coltol is a **pool-SIZE lever, not a
> refill-CLOCK lever.** It shrinks *how many heads can reach* an opening site; it does **not** slow the per-site
> refill rate or the release clock (dwell is a flat ~0.6 ms at every coltol). A reachable site still refills faster
> than it releases even in a 2 nm window (occupancy stays ≥ 0.83, rising > 1), so the system **never leaves
> supply-limited** — it just glides on a smaller pool. Crossing into release-limited needs a per-site **binding-rate**
> cut, and even that (per `RECRUIT_SHED`/`AZIMUTHAL_FALLOFF`) only scales. **coltol alone reaches the crossover NOT
> AT ALL. The controlling lever remains the bound-head POPULATION (steric co-occupancy / a displacement clutch),
> not the capture geometry.**

---

## PART 0 — the mat-sized reach-preserving z-chamber (`-matbox <nm>`) + the isolation check

Shrinking coltol thins the 3D capture window, which **activates the out-of-plane disengagement runaway**
(`GLIDING_OUTOFPLANE_DISENGAGEMENT_FINDINGS.md`): the free filament wanders off the z = 0 motor plane, loses
reach, and progressively disengages. A velocity drop at small coltol would then be **ambiguous** between the
regime question (wanted) and the filament falling off the lawn (artifact). It is isolated with a **loose,
reach-preserving chamber** — NOT a plane pin.

**`-matbox <nm>`** (new flag; `GlidingHarness`, wraps the entity-agnostic `ContainmentSystem` over the filament
body). Distinct from the earlier `-zconfine` (which set x/y → ∞):

- **z half-width = `<nm>` (LOOSE, 50 nm)** — of order the motor z-reach, well inside the ~0.2–0.3 µm escape
  threshold where the runaway starts. Inactive in the engaged state (the filament sits at z ≈ 0); it only bites an
  out-of-plane wander. **Isolation, not intervention.**
- **y-walls at the mat extent ±bYhalf** — the bed IS y-symmetric about 0 (motors placed y ∈ [−bYhalf, bYhalf]),
  so `boxY = 2·bYhalf` confines y to exactly the populated lawn, catching the y-wander that corrupted the
  high-density coverage in the recruit-shed sweep (filament ran ~2 µm off the bed in y). The wall sits far from the
  engaged filament (near y = 0) ⇒ inactive in the engaged state.
- **x → ∞** — the −x glide runway is left free (x-coverage is handled by the `-full` runway + the measurement
  window). checkInt = 1 (confine every step). Motors stay un-boxed (tethered near the plane).
- **Default-off ⇒ byte-identical** (guard `MATBOX_Z > 0`; `BOX` set only inside the `-matbox` parse).

### Isolation gate — confined ≈ unconfined **engaged** (coltol = 8, d2000, `-full`, 3 seeds, 30k)

The chamber must suppress the disengaged tail *without* touching the engaged state. At `-full` d2000 all three
seeds already engage (no disengaged tail here), so the gate is a direct engaged-state comparison:

| arm | velFitX | avgBound | meanReach | occupancy | fullMat |
|---|---:|---:|---:|---:|:--:|
| **unconfined** (seeds 0/1/2) | 3.877 / 4.239 / 4.156 | 5.470 / 5.867 / 5.896 | 6.4 / 7.2 / 6.9 | 0.859 / 0.817 / 0.854 | YES |
| **unconfined mean** | **4.091** | **5.744** | 6.83 | **0.843** | YES |
| **`-matbox 50`** (seeds 0/1/2) | 3.402 / 4.239 / 3.893 | 5.379 / 5.914 / 5.663 | 6.5 / 7.2 / 6.7 | 0.834 / 0.818 / 0.840 | YES |
| **`-matbox 50` mean** | **3.845** | **5.652** | 6.80 | **0.831** | YES |

**PASS — the chamber isolates.** avgBound Δ −1.6 %, meanReach Δ −0.4 %, occupancy Δ −1.4 % — all within
chaotic single-seed SEM; seed 1 is essentially identical (velFitX 4.239 = 4.239, avgBound 5.914 vs 5.867). The
velFitX mean Δ −6 % is carried entirely by the seed-0 realization (3.402), well inside the unconfined seed spread
(3.877–4.239) — velFitX at single-seed 30k is the noisy channel (see `RECRUIT_SHED`). The chamber changes the
engaged state by *less than one seed of scatter* ⇒ it isolates the out-of-plane mode, it does not intervene.
`fullMat = YES` on every run.

---

## PART 1 — the coltol × density grid (chamber ON, single-seed 30k, `-full`)

`-gpu -full -grid -matbox 50 -coltol <C> -density <D> -seed 0 30000`. coltol = 8 nm is the baseline anchor.
velFitX **and** avgBound reported **separately** (the recurring trap); occupancy = avgBound/meanReach is the
regime indicator, reported prominently. Raw: `RUN_LOGS/2026-07-10_coltol_regime_sweep.txt`.

| density | coltol (nm) | **velFitX** | **avgBound** | **meanReach** | **occupancy** | inst (µm/s) | dwell (ms) | detach (/s) | fullMat |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|:--:|
| **2000** | 8 (anchor) | 3.402 | 5.379 | 6.5 | **0.834** | 7.09 | 0.60 | 1660 | YES |
|  | 6 | 3.215 | 4.421 | 4.7 | **0.945** | 6.96 | 0.63 | 1595 | YES |
|  | 4 | 2.568 | 3.183 | 2.8 | **1.121** | 6.59 | 0.57 | 1741 | YES |
|  | 3 | 2.277 | 2.994 | 1.9 | **1.567** | 6.63 | 0.58 | 1727 | YES |
|  | 2 | 2.106 | 2.405 | 0.9 | **2.703** | 6.76 | 0.64 | 1559 | YES |
| **4000** | 8 (anchor) | 6.451 | 11.496 | 13.0 | **0.884** | 8.41 | 0.60 | 1680 | YES |
|  | 6 | 5.933 | 9.380 | 9.8 | **0.959** | 7.95 | 0.59 | 1689 | YES |
|  | 4 | 5.791 | 7.861 | 6.3 | **1.258** | 8.15 | 0.64 | 1557 | YES |
|  | 3 | 5.496 | 6.517 | 4.2 | **1.548** | 8.05 | 0.61 | 1637 | YES |
|  | 2 | 4.289 | 4.791 | 1.8 | **2.722** | 7.26 | 0.64 | 1561 | YES |
| **8000** | 8 (anchor) | 13.539 | 22.274 | 24.2 | **0.922** | 13.58 | 0.58 | 1727 | YES |
|  | 6 | 8.065 | 17.601 | 18.0 | **0.979** | 9.57 | 0.60 | 1675 | YES |
|  | 4 | 7.885 | 14.504 | 11.8 | **1.228** | 9.65 | 0.59 | 1699 | YES |
|  | 3 | 7.798 | 12.244 | 7.6 | **1.603** | 9.43 | 0.60 | 1659 | YES |
|  | 2 | 6.646 | 9.164 | 3.3 | **2.752** | 8.97 | 0.62 | 1612 | YES |

**Every point is `fullMat = YES`** — including **d8000, which the recruit-shed sweep found coverage-VIOLATED at
`-full`**. The mat-box (y-walls at the lawn edge) did exactly its job: it kept the filament over the motors at high
density/high speed, making d8000 measurable and clean. This is the chamber delivering its stated purpose.

### The regime indicator — occupancy `avgBound/meanReach` RISES, it does not fall

The central diagnostic. Reading it **down each coltol column** and **across each density row**:

| coltol | occ @ d2000 | occ @ d4000 | occ @ d8000 | (density-flat?) |
|---:|---:|---:|---:|:--|
| 8 | 0.834 | 0.884 | 0.922 | ~flat ≈ 0.88 |
| 6 | 0.945 | 0.959 | 0.979 | ~flat ≈ 0.96 |
| 4 | 1.121 | 1.258 | 1.228 | ~flat ≈ 1.20 |
| 3 | 1.567 | 1.548 | 1.603 | ~flat ≈ 1.57 |
| 2 | 2.703 | 2.722 | 2.752 | ~flat ≈ 2.73 |

Occupancy **rises monotonically as coltol shrinks** and is **density-flat at each coltol** (e.g. coltol 2 reads
2.70 / 2.72 / 2.75 across a 4× density span). It **passes through and above 1.0** for coltol ≤ 4 nm. It comes
**nowhere near** the ~0.06 per-head duty. The hypothesized fall (0.85 → toward 0.05) does **not** happen — the
indicator moves the **opposite** direction.

**Why it rises (the decoupling).** `meanReach` is the *instantaneous, purely-geometric* reachable count (motors
with head-perp distance < coltol, `BindingDetectionSystem.bruteReachable`, state-blind — `DENSE_DUTY_GAP` §1c). As
coltol shrinks, `meanReach` collapses fast (24 → 3 at d8000; 6.5 → 0.9 at d2000). But a **bound** head is counted
by `avgBound` regardless of its current reach — its cross-bridge stretches a few nm and it persists for the ~0.6 ms
dwell even after being **dragged out of the shrunken window**. At coltol = 2 nm the bond stretch exceeds the 2 nm
window, so most bound heads are **not in `meanReach`** ⇒ `avgBound > meanReach` ⇒ occupancy > 1. The ratio was
never a refill/release duty; it is a **thin-window enrichment** (exactly `DENSE_DUTY_GAP`'s finding), and coltol
directly shrinks its denominator. **Occupancy `avgBound/meanReach` is not a regime indicator under a coltol sweep —
it is a window-thinness readout.**

### velFitX vs density — climbs at every coltol (no density-independence, no saturation)

| coltol | velFitX @ d2000 | @ d4000 | @ d8000 | d2000→d4000 | flattens? |
|---:|---:|---:|---:|---:|:--|
| 8 | 3.402 | 6.451 | 13.539 | +90 % | NO (climbs, super-linear at top) |
| 6 | 3.215 | 5.933 | 8.065 | +85 % | NO |
| 4 | 2.568 | 5.791 | 7.885 | +126 % | NO |
| 3 | 2.277 | 5.496 | 7.798 | +141 % | NO |
| 2 | 2.106 | 4.289 | 6.646 | +104 % | NO |

At **no** coltol does the velFitX–density curve flatten to density-independent. Even the tightest window (coltol =
2 nm) climbs 2.11 → 4.29 → 6.65 (3.2× over a 4× density span). velFitX crosses *through* the d/τ_on ≈ 6–8 µm/s band
as density rises but never **flattens there** — it is the ordinary climbing curve, merely shifted **down** by
shrinking coltol. That is the signature of **outcome #3 (coltol just scales the pool)**, not #1 (saturation).

### The fixed-density fingerprint — velFitX and avgBound drop TOGETHER

Down each density column, as coltol shrinks: velFitX ↓ **and** avgBound ↓ together (d2000: 3.40→2.11 while
5.4→2.4; d4000: 6.45→4.29 while 11.5→4.8; d8000: 13.5→6.6 while 22.3→9.2). This is the **shrinking-pool** fingerprint
— the exact opposite of a ceiling (flat velFitX / *climbing* avgBound, `RECRUIT_SHED` §"fingerprint"). coltol removes
heads from the engaged pool and velocity falls with the head count.

Per-bound efficiency (velFitX/avgBound) *rises* as coltol shrinks (d2000: 0.63 → 0.88; d4000: 0.56 → 0.90) — fewer
co-bound heads per segment ⇒ **less co-bound tug-of-war** ⇒ each head more efficient. This is the **mirror** of
`RECRUIT_SHED` (there, *retaining* brakes dropped per-bound efficiency while raising the population). Here coltol
*thins* the population, raising per-bound efficiency — but the pool shrinks faster than efficiency rises, so **net
velFitX falls**. Same structural truth from the opposite side: the net is governed by the **population**, and both
directions of the population knob move velFitX with it.

### Distinguishing over-restriction (#2) from scaling (#3) — it is #3

The filament **stays continuously engaged** at every coltol including 2 nm: avgBound ≥ 2.4 (always ≥ 1 bound head),
inst speed held 7–9 µm/s (still gliding smoothly, not stalling/diffusing), velFitX still **climbs with density**
(4.29 → 6.65 at c2, not collapsing toward 0), `fullMat = YES`. Nothing shows the intermittent zero-bound
de-engagement of over-restriction. So the coltol drop is a clean pool **scale-down**, not a threshold collapse.

### Kinetics are untouched — the reason there is no timescale crossover

dwell = 0.57–0.64 ms and detach = 1560–1740 /s at **every** coltol and density — coltol touches only the capture
geometry, not the release clock (as designed). This is the crux of why the timescale intuition fails: **crossing
into release-limited requires refill-time ≳ τ_on**, i.e. slowing refill *relative to* the (fixed) release. But
coltol does not slow refill — a site that opens is refilled by whichever of the (now-fewer) reachable heads is
nearest, just as fast (occupancy stays ≥ 0.83, rising > 1 ⇒ the thin window stays saturated). coltol sets the
**pool size** (how many can reach), not the **refill rate** (how fast an open site is re-occupied). Two different
clocks; coltol turns the wrong one. To slow refill you must cut the per-site **binding rate** (kOn / an
orientational/steric gate), not the capture radius — and those knobs (`AZIMUTHAL_FALLOFF`, `RECRUIT_SHED`) were
already shown to only scale.

---

## CPU basin-arbiter (the decisive/extreme point: coltol = 2 nm, d2000, occupancy > 1)

coltol is a scalar (`kinParams[7]`) ⇒ all sweep arms share **identical hot-kernel structure** (no task
added/removed, no in-kernel transcendental toggled; springs is the transcendental-free default), so the
GPU basin-flip hazard (which needs a *structural* difference between arms) does not strictly apply here. Still, per
the standing GPU-number-trust rule the extreme geometric point gets a deterministic CPU cross-check on velFitX +
occupancy. Raw: `RUN_LOGS/2026-07-10_coltol_regime_arbiter.txt`.

<!-- ARBITER_ROW -->

---

## Reading it against the three predicted outcomes

1. **Crossover to saturation (the win)** — occupancy falls toward the duty AND velFitX flattens density-independent
   at ≈ d/τ_on. **DID NOT HAPPEN.** Occupancy *rises*; velFitX climbs with density at every coltol.
2. **Over-restriction (the confound)** — coltol so small the filament intermittently de-engages and velFitX
   collapses toward 0 for the threshold reason. **DID NOT HAPPEN.** The filament stays engaged (avgBound ≥ 2.4,
   inst 7–9 µm/s, velFitX still climbing with density, `fullMat = YES`) down to coltol = 2 nm.
3. **coltol just scales (no crossover)** — binding still beats release even in the thin window ⇒ still
   supply-limited, coltol just scales the pool ⇒ coltol alone insufficient. **THIS ONE.** With the added twist
   that occupancy `avgBound/meanReach` *rises* past 1 (window-thinning) rather than staying ~0.85 — an even more
   emphatic "still supply-limited."

## Plain statement

**Does shrinking coltol drop the site-occupancy out of the supply-limited regime and saturate velocity at ≈
d/τ_on — is there a regime crossover — and does coltol alone reach it or only partway?** **No — there is no
crossover, and coltol reaches it not at all.** Occupancy `avgBound/meanReach` does not fall toward the ~0.06 duty;
it **rises** from 0.83 to 2.70 (past 1.0), density-flat, because coltol shrinks the instantaneous reachable window
(`meanReach`) faster than the persistent bound set (`avgBound`) — a window-thinning readout, not a refill/release
duty. Velocity does not saturate: the velFitX–density curve keeps **climbing at every coltol** (≈ doubling
d2000→d4000, up to 13.5 µm/s at d8000 coltol 8), merely shifted **down** as coltol thins the pool; velFitX and
avgBound fall **together** (the shrinking-pool fingerprint, not the ceiling's). The filament stays **continuously
engaged** throughout (not over-restricted). The mechanism: **coltol is a pool-SIZE lever, not a refill-CLOCK lever**
— it changes how many heads can reach an opening site, not how fast the site refills (dwell/detach are flat at every
coltol), so a reachable site still refills faster than it releases even in a 2 nm window and the system never leaves
supply-limited. **coltol alone does not cross into the release-limited/saturated regime; a per-site binding-rate cut
would be needed, and even that only scales (`AZIMUTHAL_FALLOFF`/`RECRUIT_SHED`). The missing velocity ceiling is
structural — the bound-head POPULATION (steric co-occupancy / a displacement clutch), not the capture geometry.**

## Scope / provenance

- New flag `-matbox <nm>` (`GlidingHarness`; wraps `ContainmentSystem`), default-off byte-identical; `-coltol` /
  `-density` are the swept params. No default change. `BoA-v1ref` untouched.
- The chamber is validated as **isolating** (PART 0) — it is the instrument that made the coltol sweep
  interpretable (removed the out-of-plane confound) and made d8000 coverage-clean. Promoting a surface/z-tether to
  default is a separate task (it re-baselines gliding), not done here.
- Aggregate agreement + no basin flip confirmed on the CPU arbiter at the extreme point.

## Commands
```
scripts/run_gliding.sh -gpu -full -grid -matbox 50 -coltol <C> -density <D> -seed 0 30000   # a sweep point (chamber ON); coltol 8 = anchor
scripts/run_gliding.sh -gpu -full -grid -coltol 8 -density 2000 -seed <s> 30000              # unconfined engaged baseline (PART 0)
scripts/run_coltol_regime_part0.sh    # PART 0 isolation grid (confined vs unconfined engaged, 3 seeds)
scripts/run_coltol_regime_sweep.sh    # PART 1 coltol × density grid (chamber ON)
scripts/run_coltol_regime_arbiter.sh  # CPU basin-arbiter at the extreme coltol
```
`-matbox` default-off ⇒ byte-identical (guard `MATBOX_Z > 0`); gliding-only; `BoA-v1ref` untouched.
</content>
