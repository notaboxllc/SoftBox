# Handoff: twirl / roll investigation — resume point as of 2026-09-23

**Read this first, then `docs/ROLL_INVESTIGATION_BRIEF.md` (closed post-mortem) and
`docs/ROLL_INVESTIGATION_SECOND_OPINION.md` (independent reviewer + replies).**

---

## 1. RUN THIS NEXT

```bash
./scripts/gpu-crash-recorder.sh --status     # must be RUNNING before any GPU work
./scripts/rollclamp_twirl.sh                 # 7 arms, ~1 day
```

Committed, reasoned in its own header, launched-but-stopped before a reboot on 2026-09-23. Nothing
of it ran. Seven arms at 1.0 s:

| arm | density | eps | roll Brownian |
|---|---|---|---|
| d200_eps0 / epsP4 / epsM4 | 200 (avgBound ~1) | 0 / +4 / −4 | **OFF** |
| d800_eps0 / epsP4 / epsM4 | 800 (avgBound ~6–10) | 0 / +4 / −4 | **OFF** |
| d800_eps0_fdt | 800 | 0 | ON (FDT control) |

**Verify before trusting any arm** — three flags have been silently inert in this project
(`-dtheta`, and `-noise-seed` twice). Each arm's log must show:

```
  BROWNIAN POLICY: ... roll=OFF ...        <- for the -norollbrownian arms
  STROKE SKEW eps = +4.000 deg             <- and -4.000 on its partner
  SCENE: N=16000 motors                    <- d800 on a 10 um mat
  RNG KEYING: lawn seed N ; per-step RNG seed M
```

Read: `eps_odd = (R(+4) − R(−4))/2`, and the `d800` vs `d200` contrast.

## 2. WHY THIS DESIGN — the finding that reframed everything

The eps=0 "diffusive twirl" is **not an artefact**. It is free rotational Brownian motion, and our
runs reproduce the analytic value:

```
  gamma_roll = 4*pi*eta*L*R^2 = 3.24e-24 N.m.s     (R^2 -- actin is 3.5 nm THIN)
  D_roll     = kT/gamma       = 1278 rad^2/s
  theory  8.05 turns RMS at 1 s     measured  6.87
```

A 2.1 µm filament held by ~1 bound head genuinely tumbles several turns per second. Detecting a
~1 turn/µm twirl under that needs **~95 s on a ~286 µm mat** — unreachable.

**The real assay escapes this through ENGAGEMENT, not solvent drag.** A 20 µm filament in water has
about the same `gamma_roll` as ours (10× length cancels 10× viscosity). What differs is that at full
lawn density 10–50 heads are bound simultaneously and the cross-bridges clamp roll to a rigid
surface — which is both what makes twirl observable and what generates it.

**We deliberately run `avgBound ≈ 1`.** Sound for gliding velocity; it is precisely the regime in
which roll is unconstrained and twirl is unmeasurable. **That is a scoping statement about what this
model can be asked, and it outlives the twirl question.** Hence density 800 (jba's publication
density, `avgBound ~6-10` scaling as ~density^1.28 from the measured d050..d400 probe).

`-norollbrownian` kills only the axial Brownian torque and keeps the drag, leaving
`roll rate = tau_motor / gamma_roll` — a clean deterministic readout of the chiral torque with no
diffusive background. **Non-FDT, diagnostic only**; the `_fdt` arm says what that torque is worth
against real thermal noise.

## 3. WHAT IS CANONICAL NOW (changed this session)

- **Conforming triad is DEFAULT** (2026-09-19). The head-side triad vertices lie on the actin
  cylinder, so head-on-site is a true zero-energy state. The legacy flat triangle had 1.0725 nm of
  summed extension and a negative roll torque at the pose that should have been its minimum.
  Promoted on **mechanical** grounds — it does NOT explain the roll. `-triad-flat` restores the old
  geometry byte-identically. Regression across three lawns: glide +0.057±0.392, avgBound
  +0.046±0.422, attachments +135±590 — all null.
- `-noise-seed` keys the per-step RNG independently of the lawn (`build(seed)`), for same-lawn /
  different-noise controls. Verified live and byte-identical when unset.
- New diagnostics: `-triad-zerostrain`, `-triad-tiltscan` (pure geometry, ~1 s, no scene/runner).
- The runner banner now reports the **actual** execution path, and the RNG keying is echoed.

## 4. MEASUREMENT DISCIPLINE — hard-won, do not relearn

- **THE EXISTENCE GATE** (now in `CLAUDE.md`): resolve an emergent phenotype across INDEPENDENT
  quenched realizations before explaining it. Hierarchy:
  `rows < episodes < trajectories on ONE lawn < INDEPENDENT lawns`. ~73 % of roll variance is
  quenched in the motor lawn and is **immune to run length**. A one-lawn design cannot fail to look
  self-consistent — that is what produced a week of corroborating-but-wrong ablations.
- **Roll increments are autocorrelated**; naive `sd*sqrt(N)` understates by ~2.4×. Use
  `scripts/roll_estimator.py` (batch means + `ensemble()` + `paired()`).
- **An n≈3 variance estimate is a pilot, not a plan** — the 95 % chi-square interval on sigma at 2 df
  spans ~6×, which moved one campaign estimate from 16 to 2203 arms.
- **Benchmark on an IDLE machine.** A contended measurement said the mat was nearly free; on an idle
  machine, GPU rate ~ N^-0.38 (the device cull MASKS: `matCull` iterates the full mat and the ~30
  tasks after it launch over N and early-return). CPU ~ N^-0.19 (it COMPACTS). GPU still wins
  everywhere realistic (~2.9× baseline). `-workers 8` is SLOWER than `-workers 4`.
- **Diff two arms early.** All three inert-flag incidents surfaced only from byte-identical output.
- **Never launch a >100 s run in the foreground** — the 120 s tool timeout SIGSEGVs TornadoVM in
  `libcuda` and pollutes the `hs_err` canary.

## 5. WHAT IS SETTLED, AND WHAT IS NOT

**Settled.** No large unphysical roll: 8 independent lawns, corrected triad, alpha=60, eps=0 gave
`+1.90 ± 3.05 turns/s`, 95 % CI `[-5.30, +9.11]`, 5 positive / 3 negative. The `-9.53` that drove the
investigation was one draw. Lawn 20260901 is `z_lawn = -1.46` — unusually negative, **not** anomalous.
The eps=0 roll is diffusive: `|rate| ~ T^-0.50` across 24× in T, decorrelating at ~0.5-1 s (jba's
"the effective lawn is the LOCAL one", confirmed).

**Not settled.** Whether the model twirls at all. The CI above does not exclude the ~1 turn/µm
biological scale. The 2×2 handedness square is complete but one-lawn, so its dominant parity-invariant
term is inseparable from that lawn's offset; no chiral component resolved, converter-odd ≈ 0.
`-flip-helix` mirrors the ACTIN LATTICE ONLY — `convaz` carries motor-side handedness, so it is not a
full-parity control (brief §8). **Landmine:** `siteStairPhase` overrides `twistRate` for SITE_MODE 4/5,
so on a stair lattice `-flip-helix` is completely INERT and would look exactly like "achiral". We run
mode 3.

## 6. OPEN, IN ROUGH PRIORITY

1. Run §1. Does the engaged regime (d800) show a clean eps-odd torque with the background removed?
2. If yes: does it survive at FDT (the `_fdt` arm), and does `avgBound ~6-10` change gliding enough to
   need a re-baseline?
3. The reviewer's §18 point: eps-odd tests **response to an imposed chiral perturbation**, not native
   twirling. For native twirl at eps=0 the right control is a **true full-parity mirror** — a CODE
   task (specify the transform structurally), not a compute task.
4. `-filsegs 1` is a rigid rod with no torsional mechanics. Fine for "is chiral torque generated",
   cannot give a quantitative twirling pitch. That needs multi-segment + `-rollspring`.
5. Housekeeping: ~20 `hs_err_pid*.log` in the repo root, mostly self-inflicted foreground timeouts
   (tell: `elapsed time` ~120 s vs hours). They mask a real fault; clear them.

## 7. MACHINE

aorus1, 8 physical / 16 logical cores; one RTX 5070. One arm ≈ 1 core (~100 % pcpu) and they share the
GPU, so **>8 arms is real oversubscription** — it cost ~1.7× at 14 arms. Nothing was running at
handoff; jba rebooted for unexplained screen blanking (GPU was clean: 48 °C, 0 Xid this boot, so it
did not look like the old Xid-79 fault — check `dmesg | grep -i xid` after the reboot).
