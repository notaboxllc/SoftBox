# GPU motor-port — Production-sweep provenance & isolation record

Captured 2026-07-16 ~00:11–00:20 local, at the start of the concurrent GPU-port engineering task,
BEFORE any development. This record freezes exactly what the authoritative overnight sweep is,
so the GPU work can be proven not to have disturbed it.

## 1. Active production processes (the authoritative sweep — DO NOT TOUCH)

| PID | role | started | cmd |
|-----|------|---------|-----|
| 4167901 | driver (bash) | Jul15 ~18:13 | `./scripts/run_canonical_gliding.sh all` |
| 11673 | **explicit-S2 sweep JVM** | Jul15 22:35 | `LaserTrapHarness -motor explicit-s2-l40 -glide -out RUN_LOGS/twobody_canonical_gliding/tierB -density 1000 -matx 4 -maty 1 -dur 0.1 -seeds 8` |
| 19586 | calibrated force-balance JVM (manual) | Jul15 ~23:32 | `LaserTrapHarness -motor calibrated-s2-l40 -glide -forcebalance -density 3000 -matx 20 -maty 1 -dur 2.0 -seeds 2 -tag long2s -out RUN_LOGS/twobody_canonical_gliding/forcebalance` |

The driver `run_canonical_gliding.sh all` is at the `tierBx` phase (explicit tierB, PID 11673).
Only the `denssx` phase remains after it: 3 more explicit runs
(`-density {200,700,1500} -matx 4 -dur 0.12 -seeds 3`, ~3 h) — **each launched as a FRESH JVM.**

## 2. Executable / toolchain

- **Java:** OpenJDK 21.0.11+10-1-22.04.2-Ubuntu (`/usr/lib/jvm/java-21-openjdk-amd64`)
- **TornadoVM:** `$TORNADOVM_HOME=/home/jba/Code/TornadoVM/dist/tornadovm-4.0.1-dev-ptx-linux-amd64/tornadovm-4.0.1-dev-ptx`
- **Launch:** `java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx2G -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.LaserTrapHarness ...`
- The two-body arc (`LaserTrapHarness`) is **CPU-only** — `run_lasertrap.sh` runs the sequential runner
  (no TornadoVM device execution). GPU is idle (nvidia-smi: 1 % util, 218 MiB — desktop only).

## 3. THE ISOLATION HAZARD (identified + mitigated)

Both the `-cp "...:."` (run_lasertrap.sh) **and** the argfile `--module-path .` reference the current
working directory. `scripts/build.sh` compiles `softbox/*.java` **in place** into
`/home/jba/Code/SoftBox/softbox/*.class`. Every driver phase launches a **new JVM** that loads those
`.class` files at launch. Therefore:

> **Rebuilding in `/home/jba/Code/SoftBox` would corrupt the still-pending `denssx` explicit JVMs.**
> All GPU development builds happen ONLY in the isolated worktree below. The sweep's cwd is never built into.

Live build fingerprint (must stay unchanged until the sweep ends):
- `softbox/LaserTrapHarness.class` mtime **2026-07-15 21:15:20**, sha256 `e8e6dd37…251179e`
- `softbox/TwoBodyConverterMotor.class` sha256 `9560867a…3f7a3b77a`
- `softbox/MotorModel.class` sha256 `e5efb827…8b91a0eed`
- aggregate of all `softbox/*.class` (194 files): sha256 `09af9ae7…bef783f91`

Verify anytime with:
```
stat -c '%y' /home/jba/Code/SoftBox/softbox/LaserTrapHarness.class   # must read 2026-07-15 21:15:20
( cd /home/jba/Code/SoftBox/softbox && ls *.class | sort | xargs sha256sum | sha256sum )  # 09af9ae7...
```

## 4. Source state pinned

- Repo HEAD: `67892a4` on branch `dt-convergence-study`.
- Working tree carries an **uncommitted** 346-line addition to `softbox/TwoBodyConverterMotor.java`
  (this IS compiled into the running build). Saved to `scratchpad/running_TwoBodyConverterMotor.diff`.
- Other untracked (not compiled into classes / not motor-core): `UNBLIND_KEY_DO_NOT_GIVE_ANALYST.json`,
  `docs/TWOBODY_CANONICAL_GLIDING.md`, `scripts/{plot_canonical_gliding.py,run_canonical_gliding.sh,run_forcebalance.sh}`,
  `unblind_comparison.txt`.

## 5. Isolated development environment (created)

- **Worktree:** `/home/jba/Code/SoftBox-gpu`, new branch `gpu-motor-port` off `67892a4`.
- The running uncommitted `TwoBodyConverterMotor.java` diff was **re-applied** into the worktree, so its
  source byte-matches the running build (verified with `diff`). The GPU port references exactly what runs.
- **Build dir = the worktree itself** (`.class` land in `/home/jba/Code/SoftBox-gpu/softbox/`, isolated
  from the sweep's `.` classpath). Verified: an isolated `nice -n19 ./scripts/build.sh` (4.2 s) wrote
  classes into the worktree and left the sweep's class mtimes untouched.
- **Dev output dir:** `/home/jba/Code/SoftBox-gpu/RUN_LOGS/gpu_port_dev/` (never the sweep's
  `RUN_LOGS/twobody_canonical_gliding/`).
- GPU-invocation log: `docs/gpu_port/GPU_INVOCATION_LOG.md` (every GPU call during the concurrent phase).

## 6. Resource policy in force while the sweep runs

- 16 cores, load ~2.3 (2 JVMs ≈ 1 core each + driver). Ample CPU headroom; builds are `nice -n19`.
- GPU free → tiny smoke tests only, logged, aborted if util/mem climbs. **No** large GPU benchmarks,
  long trajectories, ensemble CPU/GPU comparisons, or heavy profiling until the sweep finishes.
- Never: `build.sh` in the sweep cwd, clean commands, symlink/jar/class replacement in the sweep tree,
  or writes under `RUN_LOGS/twobody_canonical_gliding/`.

## 7. Per-model CPU cost (from existing sweep logs — no new runs)

`wall` = seconds of wall-time per simulated second (the sweep's own reported metric):

| model | scene | active motors/step | wall (s/sim-s) | note |
|-------|-------|--------------------|----------------|------|
| explicit-s2-l40 | matx12 d1000 | ~369–377 | **10500–11275** | 14-DOF beam solve/motor/step dominates |
| calibrated-s2-l40 | matx12 d1000 | ~447 | **540** | analytic 5-DOF pivot |
| calibrated-s2-l40 | density sweep d100→d1500 | 14→687 | 16.9→424.5 | ~linear in motor count |
| fixed-anchor | (matched) | — | < calibrated | no tail solve |

Registry per-motor-step cost: explicit **58 µs** vs calibrated **1.34 µs** (≈43×); fixed ~1.0 µs.
The measured wall ratio explicit/calibrated ≈ **19×** at matched matx12 (activeMotors differ + fixed
harness overhead). **The explicit beam solve is the single dominant GPU-port target.**
