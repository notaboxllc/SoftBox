# ACTIN_AZIMUTH_GIT_HISTORY

**Read-only git-history audit (2026-07-23).** Repo `/home/jba/Code/SoftBox`, current branch
`gpu-mat-bottlenecks-explicit-singlehead`. Nothing modified; no branch checkouts (inspection via
`git log`/`git show`/`git diff` with explicit refs).

## Headline

**All azimuthal / roll-spring / helical-binding infrastructure landed in ONE foundational commit — `9c0f1d9`
(2026-07-10) — which is on BOTH the current branch and `main` (and `origin/main`).** There is **no
branch-exclusive azimuth work** in either direction: `main..HEAD` and `HEAD..main`, filtered to the azimuth
files, both return empty. Two later commits touch adjacent code (`bdc5019`, `2170aff`); both are also on the
current branch and main. The keyword `twirl` matches **nothing** in history — the concept is spelled
`twist`/`twistRate`/`helical`.

## Feature map

| Feature | Intro commit | Date | On current branch? | On main? | Later mods |
|---|---|---|---|---|---|
| `softbox/RollSpringSystem.java` | `9c0f1d9` | 2026-07-10 | YES | YES | none |
| `softbox/RollSpringHarness.java` | `9c0f1d9` | 2026-07-10 | YES | YES | none |
| `docs/AZIMUTHAL_BINDING_BUILD_READ.md` | `9c0f1d9` | 2026-07-10 | YES | YES | none |
| `docs/AZIMUTHAL_BINDING_READINESS.md` | `9c0f1d9` | 2026-07-10 | YES | YES | none |
| `docs/AZIMUTHAL_GATE_INCREMENT2.md` | `9c0f1d9` | 2026-07-10 | YES | YES | none |
| `docs/AZIMUTHAL_FALLOFF_INCREMENT3.md` | `9c0f1d9` | 2026-07-10 | YES | YES | none |
| BindingDetection azim methods (`bindNearestAzim`, `bindNearestFalloff`) | `9c0f1d9` | 2026-07-10 | YES | YES | `bdc5019` (+113 lines, `-glidekon` finite-rate binder) |
| `MotorStore.java` kinParams[22–26] | `9c0f1d9` | 2026-07-10 | YES | YES | none to those indices |
| GlidingHarness `AZ_GATE`/`AZ_FALLOFF`/`ROLL_SPRING` + `-azimbind`/`-azfalloff`/`-rollonly` | `9c0f1d9` | 2026-07-10 | YES | YES | `bdc5019` (+6), `2170aff` (+322/−8, J2/fine-dt study) |
| keyword `twirl` | — | — | — | — | **no matches in any branch** |

### Full file-set introduced in `9c0f1d9`
`docs/AZIMUTHAL_BINDING_BUILD_READ.md`, `docs/AZIMUTHAL_BINDING_READINESS.md`,
`docs/AZIMUTHAL_FALLOFF_INCREMENT3.md`, `docs/AZIMUTHAL_GATE_INCREMENT2.md`,
`docs/GLIDING_OUTOFPLANE_DISENGAGEMENT_FINDINGS.md`, `docs/ROLL_SPRING_PROTOTYPE.md`,
`scripts/run_azimuthal_falloff_sweep.sh`, `scripts/run_azimuthal_gate_sweep.sh`, `scripts/run_rollspring.sh`,
`softbox/BindingDetectionSystem.java` (+159), `softbox/GlidingHarness.java` (+99), `softbox/MotorStore.java`
(+4), `softbox/RollSpringHarness.java` (+431), `softbox/RollSpringSystem.java` (+186).

### `MotorStore` kinParams[22–26] — exact meaning (from the `9c0f1d9` diff)
```
// AZIMUTHAL (Inc 2): [22]=cos(Δ accept), [23]=twistRate rad/µm (signed, LEFT-handed),
//                    [24]=monomer spacing µm, [25]=azGate(0/1). Default 0 ⇒ gate off ⇒ byte-identical.
// AZIMUTHAL (Inc 3): [26]=falloff steepness n (graded orientational affinity a=b^n,
//                    b=max_s (1−headU·n̂)/2). n=0 ⇒ a≡1 ⇒ baseline.
```

## Later-modifying commits (both on current branch AND main)

- **`bdc5019`** — "gliding kOn line: finite-rate binder (`-glidekon`) + the biological bound-count reframe"
  (2026-07-10). Adds +113 lines to `BindingDetectionSystem.java`, +6 to `GlidingHarness.java`. Does **not**
  touch the RollSpring files.
- **`2170aff`** — "Complete fine-dt and J2 conformation study" (2026-07-13). Modifies only
  `GlidingHarness.java` (+322/−8); the `RollSpring` `-S` token hit is a build-note/varargs comment, not a
  physics change.

## Cross-branch spread (`RollSpringSystem` intro `9c0f1d9`)

`git branch -a --contains 9c0f1d9` reports it on: `dt-convergence-study`, `explicit-analytic-jac`,
`explicit-analytic-production`, `gpu-device-bench`, **`gpu-mat-bottlenecks-explicit-singlehead` (current)**,
`gpu-motor-port`, `main`, `mat-soa-float-step7`, `motor-mat-gpu-soa`, and the corresponding remotes
(`origin/dt-convergence-study`, `origin/gpu-mat-bottlenecks-explicit-singlehead`, `origin/main`,
`origin/motor-mat-gpu-soa`). `git merge-base --is-ancestor` confirms `9c0f1d9`, `bdc5019`, `2170aff` are each
ancestors of HEAD, main, and origin/main.

## Abandoned / unmerged / branch-exclusive azimuth work

**None.**
- `git log --oneline main..HEAD -- <azim files>` → empty (nothing azimuth-related on the current branch that
  isn't on main).
- `git log --oneline HEAD..main -- <azim files>` → empty (nothing on main missing from current).
- `git log --oneline --all -- softbox/RollSpring*.java` → only `9c0f1d9` (never modified on any branch since
  introduction).

The infrastructure sits **below** the current branch's ~20-commit divergence from main (`git log --oneline
main..HEAD | wc -l` = 20; none of those 20 post-fork commits touch the azimuth/roll files), so it is
identical on both branches.

## Notes / caveats

- `9c0f1d9`'s subject is a gliding velocity-ceiling investigation and does **not** mention azimuth; the
  azimuth/roll drop rode in as part of that larger commit — findable only by file/token search, not by
  subject.
- `twist` also matches an unrelated older commit `fd47f47` (2026-07-01, sphere-head mhat sign) — flagged only
  so the token hit isn't mistaken for azimuth work.
