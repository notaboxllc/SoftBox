# Briefing: the unexplained filament roll — state as of 2026-09-18

**Audience:** a reviewer coming to this repo cold, asked to look over the recent work.
**Author:** Claude Opus 5, written at jba's request for a second opinion.
**Posture:** this is a hand-off written by the person who made the mistakes described in §4. Treat §3
(deterministic) differently from §5 (statistical), and be suspicious of §5 specifically.

---

## 1. Where to start

| | |
|---|---|
| `CLAUDE.md` | project context, invariants, build/run. Long. §"Model-development discipline — biology first" is the one that governs *how* we are supposed to be working. |
| `JOURNAL.md` | newest-first log. The **2026-09-18** entry is this investigation. |
| `docs/motor/SITE_NORMAL_HEAD_BINDING.md` | the motor model everything here runs on. |
| `docs/motor/SITE_NORMAL_LONG_GLIDING_ASSAY.md` | the assay. |

Build: `./scripts/build.sh` (Java 21 + TornadoVM PTX). **`RUN_LOGS/` is gitignored**, so none of the
trajectory data quoted below is in the repo — ask jba for `RUN_LOGS/motor_audit/campaigns_2026-09/`.

## 2. The question

A gliding-assay filament, driven by a lawn of myosin motors, **accumulates net roll about its own axis**
that we cannot account for. Real myosin does twirl actin — that is the phenomenon this whole study is
chasing — so a roll is not automatically wrong. What is suspicious about *this* one is that it appears to
keep the same sign when the actin lattice is mirrored (i.e. achiral), which is the signature of a modelling
artifact rather than of myosin's handedness.

The decisive framing we have been using: **is this an ATP-driven rectification (legitimate physics) or a
passive ratchet (a bug)?**

Scene: mat 10x2 um, 4000 motors at 200/um^2 (70.7 nm mean spacing, queryR 80 nm), ONE filament, 1 segment,
2.106 um contour, eta 0.01 Pa.s, dt 1.25e-7. Roughly **71 motors in reach and ~1.0-1.4 bound at any
instant**. Observable: `rollTurns` in each arm's `trajectory_summary.csv`.

## 3. What is ESTABLISHED (deterministic — no seeds, no statistics)

### 3a. The surface triad had no reachable zero-energy state

`softbox/CrossBridgeSystem.java:873` `bondForcesSurfaceTriad`. The motor head attaches to actin through
three zero-rest springs of k/3 at a patch radius rho = 2.26 nm. The **actin-side** contacts are placed ON
the filament cylinder — an arc at radius `Ractin` = 3.5 nm, `azv = az0 + offT/Ractin`. The **head-side**
anchors were a **flat** triangle in the head's transverse plane, `ht + offA*hy + offT*hz`. Those two
triangles are not congruent, so the bond had no attainable rest state.

Reproduce in ~1 s, no GPU, no scene, no seed:

```
./scripts/run_site_normal_long_glide.sh -triad-zerostrain
```

```
  conform      |sum ext| nm    roll torque        verdict
  OFF                1.0725     -7.822e-13     FRUSTRATED
  ON                 0.0000      0.000e+00    STRAIN-FREE
```

Per-vertex extensions at the ideal pose are 0.72 / 0.18 / 0.18 nm (vertex arcs +37.0 / -18.5 / -18.5 deg).
They are **unequal**, so the pose that ought to be the energy minimum carries a net force *and* a negative
roll torque — same sign as the observed drift. (The torque magnitude printed there is NOT physical:
`myoSpring` is set to 3 so `k3 = 1` and the force reads out directly as summed extension. Only the OFF vs
ON contrast means anything.)

### 3b. The fix, and what it does away from the ideal pose

`-triad-conform` (`TRIAD_CONFORM`, `ExplicitCompleteMatHarness.java:246`, carried as `triP[3]`,
**default OFF**) lays the head-side vertices on the same cylinder: `offT*hz` becomes
`R*sin(d)*hz + R*(1-cos d)*hu`, `d = offT/R`. At the canonical pose `hu = -n_site`, so the added term is the
inward radial sag the arc has and the flat triangle lacked. Kernel change at
`CrossBridgeSystem.java:888` (hoisted constants) and `:1000` (the anchor).

```
./scripts/run_site_normal_long_glide.sh -triad-tiltscan
```

- **Axial tilt: the frustration roll torque is CONSTANT** (-7.8221e-13 from -40 to +40 deg). The 3-fold
  layout makes it pose-independent — so it is a *steady twist per bound motor*, not a rectification.
- **Azimuthal tilt:** steep and nearly odd (+4.2e-12 .. -5.2e-12), zero crossing near -7 deg. Its symmetric
  average never cancels (-7.8e-13 .. -5.1e-13, always negative).
- **Axial force is EXACTLY zero at every pose** (Sum offA = 0 over 0/+-120 deg) => the defect has **no direct
  glide component**. Conversely `conform` *adds* an odd axial restoring force the flat triad structurally
  lacked — so glide numbers may legitimately move under the fix for reasons unrelated to the artifact.

**Regression:** conform OFF reproduces the old geometry exactly — 9 rows x 40 columns byte-identical to
`ALPHA_LONG/ap60`, 7 of them with motors bound through this kernel.

**Note for the reviewer:** rolling the filament moves all three actin contacts by `R*theta` tangentially
regardless of rho, so the roll-channel stiffness is `3*k3*R^2` either way. This is why halving rho left the
roll unchanged, and it means the fix does not weaken the channel the triad exists for.

jba has agreed the strain-free triad should become canonical pending the test in §6. It has **not** been
flipped to default yet.

## 4. Two methodological defects found while doing this — READ BEFORE TRUSTING ANY ROLL NUMBER

### 4a. The harness misreported which processor it used

`SiteNormalLongGlideHarness.banner()` (`:446`) printed `EXECUTION = CPU SITE-NORMAL PRODUCTION PATH ... No
GPU work is launched` **unconditionally**. That is false whenever `-gpu` is passed: `runLong()` at `:765`
force-sets `SITE_NORMAL_DEVICE_OK`, so `ExplicitCompleteMatHarness.java:1530`'s refusal does not fire and the
run goes device-resident on a path whose own runtime message reads *"the bound-branch CPU/GPU gate is not
green; treat trajectories as a device smoke/visualisation, not a measurement."*

Every GPU-flagged roll arm ran there. I read the banner and repeated "CPU path" to jba for several
exchanges. Fixed — the banner now reports the path actually taken. **The roll is not a device artifact**:
the CPU `TWIRL_ALPHA_EPS0` arms reproduce it.

Related context: `siteNormalOn()` is `ExplicitCompleteMatHarness.java:113`; the device stack is *wired* and
the isolated lowering risk is retired (`ExplicitMatSolveTiltHarness`, 1.7e-9 um / 4.3e-7 rad vs the CPU
runner). What is missing is only the whole-step CPU/GPU equivalence gate.

### 4b. The roll error bars were wrong, and the dominant variance is quenched

Reported uncertainty used `SE(net) = sd(dRoll)*sqrt(N)`, which assumes independent increments. A bound motor
persists across many output rows, so they are correlated. `scripts/roll_estimator.py` replaces this with
batch means (block, scan block length, read the plateau).

Blocking alone did not close the gap, and **why** is the more important finding. Four CPU arms at ONE
configuration (alpha=60, triad, eta 0.01, eps 0):

```
    +5.85   -15.92   -12.36   -10.72   turns/s      <- same configuration, different seed

    blocked within-arm SE (thermal)  +/- 5.0     <- shrinks with run length
    across-seed sd (measured, n=4)   +/- 9.67
    => QUENCHED component            +/- 8.3     (73% of the variance)
```

`ChiralSiteHarness.build(seed)` lays a **different motor lawn per seed**. No single trajectory can see that
spread. Run length is therefore nearly useless — a 1 s arm carries SE 9.7 and a 16 s arm still carries 8.4.

**Design consequence:** pair at matched seeds. Same seed = same lawn, so the quenched term cancels in the
difference. Unpaired difference sd 13.7 (17 pairs to resolve 10 turns/s at 3 sigma); **paired sd 7.1 (4
pairs)**.

## 5. What is RETRACTED

All of the following were n=1 against n=1 and re-score to below 2 sigma against the measured +/-9.67:

| claim | turns/s | z I quoted | z honest |
|---|---|---|---|
| alpha=0 is the unique null | +0.38 | 0.12 | **0.04** |
| alpha=-60 is the strongest arm | -16.42 | 4.81 | **1.70** |
| the roll does not need the power stroke | -9.29 | 2.11 | **0.96** |
| removing the triad kills the roll | -1.30 | 0.28 | **0.13** |
| Brownian-off kills the roll | -1.29 | 0.42 | **0.13** |
| patch size does not matter | -8.26 | 1.83 | **0.85** |
| the conform fix has not killed it | -10.69 | 2.08 | **1.11** |

The "roll does not need the power stroke" one is worth singling out: at matched attachment count the normal
motor and one with zero lever swing agreed to **four significant figures** (-4.805e-3 vs -4.808e-3
turns/attachment) while the glide halved. That is a striking coincidence if it is one — but it is one arm
against one arm, and it should be re-run paired before anyone believes it.

The mirror decomposition — the test that would actually separate artifact from genuine chiral twirling — has
n=2 per side: **EVEN (achiral) -8.29 +/- 4.8 (1.7 sigma), ODD (chiral) +3.25 +/- 4.8 (0.7 sigma)**. Neither
the achirality nor the chirality of this roll is established.

**What stands:** a negative roll EXISTS. Pooled over 8 non-zero-alpha arms across both runners,
**-9.79 +/- 2.59 turns/s (3.8 sigma)**. And all of §3.

## 6. In flight

`TRIAD_CONFORM/conform` (seed 20260901) plus `CONFORM_PAIRED/{frust,conform}_2026090{2,3}` — three matched
pairs of frustrated vs strain-free triad at alpha=60, 1.0 s each. Scripts: `scripts/triad_conform_test.sh`,
`scripts/conform_paired_seeds.sh`. Both arms of a pair share a runner, so device-path artifacts cancel along
with the lawn: **quote the difference, never the absolute level.**

Also running: 4 CPU `TWIRL_ALPHA_EPS0` arms (`scripts/twirl_alpha_eps0_cpu.sh`), the native/mirror pairs at
alpha=60 that are the only validated-runner measurement of this configuration, ~40% through a 3 s target.

## 7. Where a fresh reviewer should push

1. **Attack §3a directly.** Is the arc-vs-chord reading right, and is `R*sin(d)*hz + R*(1-cos d)*hu` the
   correct conforming construction given `hu = -n_site`? One command, one second, no GPU. If this is wrong
   the whole hypothesis dies cheaply.
2. **Attack §4b.** The 73%-quenched claim rests on four arms at one configuration plus the fact that `seed`
   reaches `ChiralSiteHarness.build`. If it is wrong, the paired design is wrong and the retractions in §5
   are too harsh.
3. **The unexplained residue.** Even taking §3 at face value, a *constant* torque per bound motor does not
   obviously explain why alpha=0 showed no roll while alpha!=0 did, since a symmetric azimuthal pose
   distribution never cancels the frustration. Either alpha=0 parks the head at the -7 deg zero crossing, or
   something else opposes it there, or the frustration is not the cause. This is the live hole.
4. **Is anything else in the bond similarly frustrated?** The triad was found by asking "does this have a
   reachable rest state?" That question has not been asked of the other couplings.
5. **The failure pattern in my own work here** — repeatedly reporting an effect at 1-3 sigma and retracting
   it a few hours later — is documented as a standing issue. If a claim in this repo is not accompanied by a
   resolution statement, distrust it.
