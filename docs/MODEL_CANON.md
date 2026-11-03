# Model canon — the site-normal gliding / twirling model

**Current: canon v4 (2026-11-03).** Source of truth: `softbox/ModelCanon.java`. This document gives the rules and
the reasons behind them. The table in the code is authoritative; this file never restates its values except in the
version history below.

## Why

Realism features were repeatedly lost by accident while other parts of the model changed. The case that forced this
was the contact-patch triad. It was an opt-in flag, `-head-chain` replaced the system it used, and on 2026-10-25 it
dropped out, leaving only a script comment. Because the banner printed nothing when it was off, its absence went
unseen for a week (JOURNAL 2026-11-02).

The fix is structural. The model is a list of rows, and every run checks the values it actually resolved against that
list before any wall-clock is spent.

## What a run does

1. **Preset.** `ModelCanon.PRESET` is prepended to the command line of every `-run` / `-chain-equiv` invocation of
   `SiteNormalLongGlideHarness`. Flags given later override it. Everything else canonical is already a code default.
2. **Check.** Once the scene is fully resolved (after `cfg()`, which clobbers several fields), `ModelCanon.verify()`
   compares each row's resolved value with canon.
   - A row that differs and is **not** declared with `-deviate <id>[,<id>...]` stops the run with the list.
   - `verifyKernel()` then confirms that κ, the dock stiffness, ψ and the capture k_ori reached the buffers the
     kernels actually read.
3. **Record.** The manifest is printed in the log (rows, values, bases, deviations), and written to
   `run_config.json` under `"canon"`.

`-canon none` switches the preset and the check off and prints `NON-CANONICAL`. Use it for regression replays and for
old scripts. Opt-out flags for the canonical on-switches: `-no-head-chain`, `-no-rate-bind`, `-no-swivel-dock`,
`-no-head-floor`, `-no-f8-tip-fix`, `-no-rollspring`, `-no-pairs`. Using one still requires `-deviate` of its row.

**Scope.**

| Kind | Meaning | Checked? |
|---|---|---|
| MODEL | motor and actin physics | yes |
| FIXTURE | assay physics held fixed: solvent, walls, coverslip, random lawn | yes |
| NUMERICS | dt, cull reach | yes |
| conditions | [ATP], density, lawn size, filament length, seeds, steps, viewer | recorded only; what a campaign varies |
| runner | GPU flags | recorded only |

## Running

```
scripts/canon_glide.sh 02 <out_dir> [assay flags, -deviate ..., -steps N]   # canonical reference assay, GPU, monitored
scripts/canon_gate.sh                                                       # golden-trajectory gate (10k steps, lawn 02)
```

Campaign scripts pass **assay conditions only**. Restating model flags in a campaign script is how features drifted
before.

## Changing the model (the rule)

A change to a canonical value, a new realism feature, or the removal of one is a **decision by jba**. Never make it as
a side effect. To make one:

1. Edit the row(s) in `ModelCanon.java`, or add a row, and bump `VERSION`. A feature with no row is not part of the
   model.
2. Re-record the golden: `scripts/canon_gate.sh -record`.
3. Add a version entry below and a JOURNAL entry. State the basis (citation or emergent test) and what it
   re-baselines.
4. Before a campaign on a code change that should be physics-neutral, `scripts/canon_gate.sh` must PASS.

Before launching any new arm, diff its flags against the parent arm. Any feature that changes or disappears is raised
with jba (memory: never-silently-drop-features).

## Version history

### v4 — 2026-11-03 (ψ sign from structure)

- **ψ = +4°** (right-handed swing), was −4°. Decision by jba after the sign check under v3 below: both the myosin-5a
  structure (Klebl; our 8R9V→8RBF measurement) and the docked myosin II structures (Beausang 2008) give a
  right-handed swing. Both isoforms twirl left-handed in experiment.
- **Twirl handedness is now an OUTPUT.** If the model twirls right-handed, it is reported as such: the swing tilt alone
  does not account for the observed left-handed twirl, and another mechanism is missing. This is Beausang's own
  conclusion. Their candidates are drag from rigor heads and step size relative to the actin helix.
- Supersedes the 2026-10-08 ruling that handedness is an input, for the swing-tilt mechanism.
- Golden re-recorded (`fixtures/canon/v4_lawn02_10k.golden`); gate PASS. Nothing else changed.
- Reference lawns 02–04: `RUN_LOGS/motor_audit/campaigns_2026-11/CANON_V4/`.

### v3 — 2026-11-03 (first manifest; model promoted to `main`)

The model was imported from the uncommitted `~/Code/SoftBox-pairs` working copy. The import was gated bit-identical to
flexT lawn 02 through 10,000 steps. Lineage of the copies: SoftBox-dock → -weak → -weak-ep → -pairs. The copies are
now read-only history.

Decided in v3:
- **Swing-plane tilt ψ = −4°** (`-swing-tilt`). The lever swing plane leaves the actin axis by ψ, so the stroke
  gains a circumferential component of stroke·sin ψ.
  - **Basis:** Klebl et al. 2025, *Nature* 642:519 (time-resolved cryo-EM, actomyosin-5a, 8R9V/8RBF): the lever
    swings ~93° "predominantly along the actin axis, and is displaced azimuthally by only 4° right-handed", with
    "a small (4°) right-handed component to the lever swing".
  - **Our measurement on 8R9V/8RBF** (actin-CA superposition, screw-operator axis):
    - total swing 88–92°;
    - ψ = asin|ω·u| = 4.5–6.4° from the lever-helix direction (residues 753–767; only ~2 nm of lever is modelled);
    - about 2° from the rigid-body rotation axis of the converter.
  - So 4° is a fair central value of a 2–6° structural bracket.
  - **Caveats:** this is a myosin-5a number. No myosin II measurement was found. In a coarse model where the
    converter axis is fixed in the head, ψ rotates the whole docked head about its long axis.
  - **Sign:** the sign is an input (handedness is not predicted by this model; jba 2026-10-08). −4° gives the
    left-handed twirl seen for myosin II (Beausang 2008).
  - **Sign checked (2026-11-03): the canonical sign is opposite to the structures.**
    - Model: in the dock frame (`HeadChainSystem.siteCouple`), ψ < 0 turns the stroke axis so that
      s·(u×n) < 0, a left-handed swing. That matches the measured left-handed twirl at ψ = −10°.
    - Structure: on 8R9V→8RBF in the same frame, the lever tip moves +53 Å axially and +5.9 Å along u×n. That is a
      right-handed swing, ψ ≈ +4.5 to +6.4°, which agrees with Klebl. The screw sense does not depend on which way
      the polarity of u is assigned.
    - Myosin II: Beausang 2008 (*Biophys J* 95:5820) says the docked myosin II structures move Lys843 "with a slight
      (~20º) right-handed tilt", that "a tilt in the other direction would be required" for the left-handed twirl
      they measured for myosin II (pitch 0.47 µm) and myosin V (1.7 µm), and so the twirl "is not explained by the
      path of the power stroke". They also report only weak coupling between linear and angular velocity.
    - So it is not an isoform difference: both isoforms have a right-handed swing and both twirl left-handed. Klebl
      supports the *size* of ψ, not the left-handed sign. At −4° the canonical sign is a phenomenological input.
      Decision with jba.
  - **Twirl is reported, not matched.** We compare with Beausang but do not fit to it.
- **Stroke skew ε = 0** (`-stroke-skew`). Retired as a chirality input. The conserved-core contacts of 8R9V/8RBF do
  not move (−0.2°; docs/twirl/ACTIN_SITE_LATTICE_LITERATURE_BASIS.md). Under the head chain it gave no resolved
  twirl, while ψ does (JOURNAL 2026-11-03).
- **Capture bookkeeping fix.** Rate capture now scores the stroke axis against the ψ-turned dock target, not the
  bare filament axis. Before, every head attached about ψ away from its target. The cost was ~0.09 kT, absorbed by
  the pliant hinge, but it was wrong. The fix is bit-identical at ψ = 0. `RateBindSystem` rbC rows 8–10, sbP[47].
- **Carried as canon** (each row in `ModelCanon.java` with its basis):
  - head chain (κ 23.4, Billington 2014); dock 512; F8 tip fix;
  - rate capture k0 1e6 /s per site with k_ori in series (22.4); release 30 µs force average;
  - no motor–actin sterics; triad off; head floor;
  - S2 swivel, S2 on the coverslip, base hinge 0.1·kb; random motor orientation;
  - every-4 two-strand sites; PAIRS 0.1/1.0/0.003 (thermal Lp ~10 µm); roll spring 40; rotational Brownian at FDT;
  - η 0.01 Pa·s; z-slab walls at the rod ends, 0–200 nm; dt 1.25e-7 s.
- **OPEN:** rigor rupture. It is off, as in every glide/twirl run since the head chain. Decide before the low-ATP
  canon.
- **Not changed:** the GPU banner still says the bound-branch whole-step CPU/GPU gate is not green. The head-chain
  `-chain-equiv` acceptance (decisions identical, filament ≤1e-6 µm) is the current evidence. Retiring the banner is
  a separate decision.
