# TornadoVM / NVIDIA GPU Crash Monitoring — Implementation Report

**Authoritative implementation document** (2026-07-24, branch `gpu-mat-bottlenecks-explicit-singlehead`).
Always-on, low-overhead monitoring that can stay enabled during ORDINARY SoftBox GPU campaigns until another
naturally occurring hard freeze supplies evidence. **No stress test, no crash reproduction, no automatic GPU
recovery.**

> **Scope discipline — read first.** This is *diagnostic instrumentation*. It records **when** the process reached
> each lifecycle window so that, after a freeze, the last durable marker localises the failure to **GPU execution /
> result handling / `TornadoExecutionPlan.close()` / delayed post-close cleanup / JVM shutdown / an unrelated
> period**. It **does not and cannot** show that TornadoVM is the root cause. An **Xid 79** ("GPU has fallen off the
> bus") arises equally from the NVIDIA driver or GSP firmware, the PCIe link, power delivery, the motherboard, or
> the GPU itself. Timing evidence localises a **window**, never a cause.

Background: the workstation has intermittently hard-frozen during or shortly after TornadoVM workloads. The
previous boot's kernel log on this machine contains, verifiably:

```
NVRM: Xid (PCI:0000:01:00): 79, GPU has fallen off the bus.
NVRM: Xid (PCI:0000:01:00): 154, GPU recovery action changed from 0x0 (None) to 0x1 (GPU Reset Required)
```

The failure is **not reproducible on demand**, which is why this is passive monitoring rather than a stress rig.

---

## 1. Files changed

### New — Java (`softbox/`)

| File | Role |
|---|---|
| `CrashTrace.java` | The durable append-only phase logger. ONE `FileChannel` for both `write()` and `force(true)`; every marker mirrored to stderr; idempotent `close()`. |
| `TornadoCrashDiagnostic.java` | The lifecycle helper — flag/env parsing, trace ownership, shutdown hook, heartbeat, execute-window markers, explicit plan close, optional pauses, close policy, journald mirror. Every method is a no-op when tracing is off. |
| `CrashTraceValidationHarness.java` | Validation: `-emit` (synthetic instrumented lifecycle against a stub plan, no GPU) and `-verify <file>` (offline marker-order / field / shutdown-tail check). |

### New — scripts

| File | Role |
|---|---|
| `scripts/gpu-crash-recorder.sh` | External Linux recorder (Layer 2): kernel journal, NVIDIA telemetry, memory, extended NVIDIA + PCIe/AER state, recorder health. `--status` / `--stop`. |
| `scripts/systemd/softbox-gpu-crash-recorder.service` | Optional **user-level** systemd unit for the recorder. |
| `scripts/install_gpu_crash_recorder_user_service.sh` | Installs the unit into `~/.config/systemd/user/` and reloads the user manager. Runs nothing privileged. |
| `scripts/run_gpu_monitored.sh` | Campaign wrapper: checks the recorder, makes a run id + evidence dir, enables the Java trace via the environment, tees stdout/stderr, records command + environment, **preserves the child exit status**. |
| `scripts/collect_gpu_crash_case.sh` | Post-crash collector → one timestamped `~/gpu-crash-case-*.tar.gz`. |
| `scripts/run_crashtrace_validate.sh` | Runs the validation harness (emit in one JVM, verify in a second). |

### Modified — Java (integration only; no physics touched)

| File | Change |
|---|---|
| `ExplicitCompleteMatHarness.java` | `TornadoCrashDiagnostic.init(...)` as the first statement of `main`; `TornadoCrashDiagnostic.exit(rc)` at the two exit points; in `runProductionCell` — plan hoisted to a local so it can be closed, plan-construction / execute-window / result-processing / GPU-work-finished / close markers. |
| `ExplicitTwirlGlidingHarness.java` | `init(...)` in `main`; `normalMainReturn(...)` at the exits; full lifecycle around **both** device plans (`runEquivFullGraph`'s gliding graph and `runEquivKernels`' `surfEq` graph, the latter with `DEVICE_SYNC_BEGIN/END` around its `UNDER_DEMAND` `transferToHost`). |

**Nothing else was touched.** No physics, dt, RNG, event ordering, graph task ordering, kernel, buffer-transfer
mode, or scientific value changed. No `TaskGraph` was re-ordered. Other harnesses are untouched, and both
instrumented harnesses are byte-equivalent in behaviour with tracing off.

---

## 2. Java logger design (`CrashTrace`)

* **One append-mode `FileChannel` for both writing and forcing.** The handoff's sketch used a `BufferedWriter`
  plus a *separate* `FileChannel` on the same path; that is explicitly rejected here. Two objects mean two file
  positions and two buffers, so `force()` on the second channel can flush nothing the writer had staged. One
  channel — `write()` in a drain loop, then `force(true)` — is the only construction where the bytes are known to
  have reached storage before `mark()` returns.
* Per marker: format one UTF-8 line → `write` → `force(true)` (data **and** metadata) → `System.err.print` →
  `System.err.flush()`.
* Fields, all present on every line: **UTC wall-clock timestamp** (`Instant.now()`, nanosecond precision),
  **`seq`** (monotonic from 1), **`elapsedMs`** (trace-relative, from a `System.nanoTime()` origin captured at
  trace open — deliberately *not* labelled uptime), **`pid`**, **`thread`**, **`phase`**, optional `key=value`
  details.
* A write failure reports `TRACE_WRITE_FAILED` to stderr **once** and never recurses into `mark()`.
* `close()` is idempotent and forces once more before closing.

Example (real, from the validation run):

```
2026-07-24T07:59:19.437239581Z seq=000020 elapsedMs=7321 pid=10153 thread=main phase=PLAN_CLOSE_BEGIN policy=normal graph=glide dt=2.500e-06 campaignArm=single-head-production-cell density=300 seed=101 steps=2000 N=900 nSeg=12 rev=8d2f318
```

### File location and naming

Default `~/tornado-crash-traces/` (override: `-gpu-crash-trace-dir`, `-Dsoftbox.gpu.crash.dir`, or
`SOFTBOX_GPU_CRASH_TRACE_DIR`). One file per process:

```
run-<UTC-stamp>-pid<PID>-<assay>.log      e.g. run-20260724T075912Z-pid10153-explicit-s2-singlehead.log
```

### Logger lifetime

The trace must outlive `main`, so it is **not** wrapped in try-with-resources around `main()`. The lifecycle is:

1. open the trace (`TRACE_OPEN`);
2. **register the shutdown hook immediately**, before any GPU work;
3. run the program;
4. write `NORMAL_MAIN_RETURN`;
5. the shutdown hook writes `SHUTDOWN_HOOK_ENTERED` … `SHUTDOWN_HOOK_COMPLETED` through the **still-open** channel;
6. the channel is closed at the very **end** of the hook (idempotent).

`System.exit()` paths are covered: `TornadoCrashDiagnostic.exit(code)` writes `NORMAL_MAIN_RETURN` and then exits,
and the hook still runs.

---

## 3. Phase markers

Emitted with exactly these names:

```
TRACE_OPEN  PROGRAM_START  LAUNCH_CONFIG  [LAUNCH_CONFIG_WARNING]  ARGS  GIT
PLAN_CONSTRUCTION_BEGIN / PLAN_CONSTRUCTION_END          [PLAN_CONSTRUCTION_THREW]
PLAN_EXECUTE_BEGIN                                        [PLAN_EXECUTE_THREW]
  EXECUTE_CALL_BEGIN / EXECUTE_CALL_END   (per-call, gated — see §5)
PLAN_EXECUTE_END
[DEVICE_SYNC_BEGIN / DEVICE_SYNC_END]                     (only where an explicit sync exists — §6)
RESULT_PROCESSING_BEGIN / RESULT_PROCESSING_END           [RESULT_PROCESSING_THREW]
GPU_WORK_DECLARED_FINISHED
PRE_CLOSE_PAUSE_BEGIN / PRE_CLOSE_PAUSE_END
PLAN_CLOSE_BEGIN / PLAN_CLOSE_END                         [PLAN_CLOSE_THREW | PLAN_CLOSE_SKIPPED]
POST_CLOSE_PAUSE_BEGIN / POST_CLOSE_PAUSE_END
NORMAL_MAIN_RETURN
SHUTDOWN_HOOK_ENTERED / SHUTDOWN_HOOK_COMPLETED
HEARTBEAT                                                 [TRACE_WRITE_FAILED]
```

A stage that throws emits its `_THREW` marker **before** the exception propagates; the original failure is never
swallowed.

### `PROGRAM_START` / `LAUNCH_CONFIG` / `GIT` payload

`assay`, `pid`, full Java runtime version, JVM name/version, OS, cwd, trace file, heartbeat interval, execute-marker
cadence, pre/post-close pauses, close policy; then `tornado.enable.fma`, `tornado.recover.bailout`,
`tornado.tvm.maxbytecodesize`, `tornado.version`, `TORNADOVM_HOME`; then the complete `argv`; then git commit,
branch and dirty status. **Backend / device / GPU name** is logged at `PLAN_CONSTRUCTION_END` (real example:
`device=PTX -- NVIDIA GeForce RTX 5070`) — queried reflectively *after* the plan exists, because querying it earlier
would change TornadoVM runtime-initialisation order.

### Explicit-S2 launch-configuration check

`docs/EXPLICIT_S2_GPU_LOWERING_REGRESSION_FINDINGS.md` established the device path requires
`-Dtornado.enable.fma=false` (the `matS2SolveStep` FMA lowering defect), `-Dtornado.recover.bailout=false` (a
lowering failure THROWS instead of silently falling back to the sequential CPU runner) and
`-Dtornado.tvm.maxbytecodesize=65536`. The tracer **records** all three and emits `LAUNCH_CONFIG_WARNING` when any
is absent or unexpected. It **never changes them** — the launchers own that policy.

---

## 4. Lifecycle integration

Integration is deliberately narrow: one reusable helper plus the two primary explicit-S2 GPU launch paths
(`scripts/run_singlehead_gpu.sh` → `ExplicitCompleteMatHarness`, `scripts/run_explicit_twirl.sh` →
`ExplicitTwirlGlidingHarness`). There is **no single common execution-plan construction path** in this repo — 47
files build their own `TornadoExecutionPlan` — so a repository-wide edit was rejected in favour of the two
launchers named in scope. Extending coverage later is a 6-line change per entry point.

### Plan-close ownership (audited)

Before this work, **no instrumented harness ever called `plan.close()`**; plans are method-local and left to GC /
JVM exit (the single exception in the repo is `FullSystemDemoHarness:2309`, which closes and swallows). Ownership is
therefore unambiguous — the method that constructs the plan owns it — and in the instrumented paths the plan is now
hoisted to a local that the teardown window closes.

**Important consequence:** explicit close is applied **only when tracing is active**. With tracing off, teardown is
byte-for-byte the historical behaviour. With tracing on and the routine `normal` policy, `close()` is called
explicitly at the very end, *after* the cell JSON and `.done` marker are durable on disk, so the close interval is
bounded by `PLAN_CLOSE_BEGIN` / `PLAN_CLOSE_END`. A throw from `close()` is marked `PLAN_CLOSE_THREW` and
propagated (it would surface as a non-zero exit **after** results are written).

---

## 5. Repeated `plan.execute()` calls

Both instrumented campaigns call `plan.execute()` **once per physics timestep** (e.g. 40 000 calls in a production
cell). Forcing a marker per timestep is unacceptable, so:

* `PLAN_EXECUTE_BEGIN` / `PLAN_EXECUTE_END` bracket the **whole loop**, carrying `graphName`, `stepStart`,
  `stepEnd`, `executeCallsPlanned`, and the campaign context (`seed`, `density`, `steps`, `N`, `nSeg`, `rev`,
  `outdir`, `campaignArm`, `dt`).
* `EXECUTE_CALL_BEGIN` / `EXECUTE_CALL_END` are durable for the **first** call, the **final planned** call, and
  every *N*th call when `-gpu-crash-execute-every N` is set (default 0 = first + last only). Each carries
  `executeIndex`, `step`, `stepStart`, `stepEnd`, `simulationTime`, `graphName` + context.
* Every call updates cheap in-memory volatile counters that the **heartbeat** reports, so the in-flight execute is
  localisable to ~one heartbeat interval at zero I/O cost.
* `SHUTDOWN_HOOK_ENTERED` repeats the final counters.

**The exact final execute call is always identifiable** — durably marked by index, step and simulation time. A
single-execute run (e.g. `runEquivKernels`) gets one BEGIN/END pair.

A 2 000-step production cell produced **26 markers total** (3 execute markers, 1 heartbeat).

---

## 6. GPU completion semantics (audited)

`TornadoExecutionPlan.execute()` is blocking. The instrumented gliding graph declares its readbacks
`transferToHost(DataTransferMode.EVERY_EXECUTION, …)`, so the declared copy-outs have landed in the host arrays by
the time `execute()` returns — and the harness reads those arrays on the next line (`o.observe(...)`). **Result
consumption IS the synchronisation** for this path; there is no separate device-sync call in normal execution and
**none was added** (adding one would change execution semantics and cost). This is recorded in the marker itself:

```
GPU_WORK_DECLARED_FINISHED syncModel=blocking-execute+EVERY_EXECUTION-copyout-consumed-by-host status=ok
```

The one place an explicit synchronisation genuinely exists is `runEquivKernels`, whose `surfEq` graph declares its
copy-out `UNDER_DEMAND` and therefore performs an explicit `transferToHost(...)` after `execute()`. That call is
bracketed with `DEVICE_SYNC_BEGIN` / `DEVICE_SYNC_END`.

---

## 7. Heartbeat

Daemon thread, minimum priority, default **5 s** (`-gpu-crash-heartbeat-sec`, 0 disables). Never calls TornadoVM,
never writes more often than configured, is interrupted and stopped at the top of the shutdown hook, and never
keeps the JVM alive. Payload:

```
HEARTBEAT state=EXECUTING executeIndexStarted=890 executeIndexCompleted=889 lastStepStarted=890 lastStepCompleted=889
```

States: `STARTING`, `PLAN_CONSTRUCTION`, `EXECUTING`, `RESULT_PROCESSING`, `PRE_CLOSE_PAUSE`, `PLAN_CLOSE`,
`POST_CLOSE_PAUSE`, `MAIN_RETURNED`, `SHUTDOWN`. A trace whose last line is a `HEARTBEAT` distinguishes a sudden
system disappearance from an orderly shutdown or a main-thread exception.

---

## 8. Diagnostic options

| Option (argument) | Property | Environment | Default |
|---|---|---|---|
| `-gpu-crash-trace` | `softbox.gpu.crash.trace` | `SOFTBOX_GPU_CRASH_TRACE=1` | **OFF** |
| `-gpu-crash-trace-dir <path>` | `softbox.gpu.crash.dir` | `SOFTBOX_GPU_CRASH_TRACE_DIR` | `~/tornado-crash-traces` |
| `-gpu-crash-heartbeat-sec <n>` | `softbox.gpu.crash.heartbeat` | `SOFTBOX_GPU_CRASH_HEARTBEAT_SEC` | `5` |
| `-gpu-crash-execute-every <n>` | `softbox.gpu.crash.execevery` | `SOFTBOX_GPU_CRASH_EXECUTE_EVERY` | `0` (first + last) |
| `-gpu-pre-close-pause-sec <n>` | `softbox.gpu.crash.preclose` | `SOFTBOX_GPU_PRE_CLOSE_PAUSE_SEC` | `0` |
| `-gpu-post-close-pause-sec <n>` | `softbox.gpu.crash.postclose` | `SOFTBOX_GPU_POST_CLOSE_PAUSE_SEC` | `0` |
| `-gpu-close-policy <normal\|skip-explicit\|jvm-only>` | `softbox.gpu.crash.closepolicy` | `SOFTBOX_GPU_CLOSE_POLICY` | `normal` |
| `-gpu-crash-no-journal` | `softbox.gpu.crash.journal=false` | — | journald mirror ON |

**Routine campaign mode** is therefore: tracing on, heartbeat 5 s, **pre-close pause 0**, **post-close pause 0**,
**explicit `plan.close()` enabled**. No pauses are ever added to ordinary work.

`skip-explicit` (no close; plan becomes unreachable) and `jvm-only` (no close **and** a strong reference retained to
JVM exit so GC cannot free it) exist for the handoff's controlled experiments D/E. They print a prominent warning
to stdout **and** stderr, are stamped into the trace (`CLOSE_POLICY_NON_NORMAL`, `PLAN_CLOSE_SKIPPED`), and must
never be used in a campaign — they intentionally retain device resources.

---

## 9. External recorder (`scripts/gpu-crash-recorder.sh`)

Output root `~/gpu-crash-records/`, one timestamped session directory per start, with `current` symlinked to the
live session. Runs unprivileged.

| File | Content | Cadence |
|---|---|---|
| `kernel-live.log` | `stdbuf -oL journalctl -kf -o short-precise` (where NVRM / Xid / GSP messages land) | follow |
| `nvidia-smi-live.log` | timestamp, index, PCI bus id, pstate, temperature, power draw + limit, GPU/memory utilisation, memory used/total, graphics + memory clocks, PCIe gen + width, throttle reasons, **`rc=` exit status** | ~1 s |
| `memory-live.log` | `/proc/meminfo` | ~5 s |
| `nvidia-extended.log` | `nvidia-smi -q -d POWER,TEMPERATURE,CLOCK,PERFORMANCE`, `-d PCIE`, **sysfs PCIe link state** (`current/max_link_speed`, `current/max_link_width`, runtime status), **AER counters** (`aer_dev_correctable/fatal/nonfatal`), `lspci -vv` | ~60 s |
| `recorder-health.log` | recorder pid, children, load average; a "stopped" line on clean exit | ~30 s |
| `status` | recorder pid, session, start + `updated_utc`, host, journal/nvidia capability, bus id, intervals | ~30 s |
| `nvidia-smi-session-start.log`, `system-info.log` | full `nvidia-smi -q`, `uname`, distro, `/proc/driver/nvidia/version` | once |

Behaviour: PID-file single-instance lock (a second start refuses with exit 3); `--status` / `--stop`; SIGTERM/SIGINT
trap that kills all children and clears the PID file (the wait loop uses `sleep & wait` so the trap fires
immediately, not after the interval); capability probes that **report** rather than fail when the kernel journal or
`nvidia-smi` is unavailable; retention pruning of sessions older than `GPU_CRASH_RETAIN_DAYS` (default 14, `0`
disables) that never touches the live session. **No GPU reset, no system modification, no privileged action.**

Two environment findings on this machine: `nvidia-smi -q -d PCIE` is **rejected** by driver 595.71.05 (queried
separately and tolerated), and `lspci` shows `Capabilities: <access denied>` for a non-root user — hence the sysfs
link-state and AER reads, which **are** readable unprivileged here and are the more useful Xid-79 evidence anyway.

### User-level systemd service

`scripts/install_gpu_crash_recorder_user_service.sh` rewrites the unit's `ExecStart` to this checkout, installs it
to `~/.config/systemd/user/softbox-gpu-crash-recorder.service` and runs `systemctl --user daemon-reload`. Nothing
privileged, nothing system-wide.

```bash
./scripts/install_gpu_crash_recorder_user_service.sh
systemctl --user enable --now softbox-gpu-crash-recorder.service
systemctl --user status  softbox-gpu-crash-recorder.service
systemctl --user stop    softbox-gpu-crash-recorder.service
```

`Restart=on-failure`, `RestartSec=10`, `KillMode=mixed` + `TimeoutStopSec=20` (so the script's own trap flushes and
cleans up its children), logs appended to `~/gpu-crash-records/service.log`.

**Limitation — logout:** a user service stops when the last session ends unless lingering is enabled. That is a
privileged change and is **not** performed automatically:

```bash
sudo loginctl enable-linger $USER      # optional, your call
```

**Limitation — journal access:** kernel NVRM/Xid capture needs this user to be able to read the kernel journal
(`journalctl -k`). It **works today on this machine** (`journal_ok=yes`). If it ever stops working, the one-time
privileged fix is `sudo usermod -aG systemd-journal $USER` followed by a re-login; the recorder detects and reports
the condition rather than silently producing an empty log.

---

## 10. Monitored campaign wrapper (`scripts/run_gpu_monitored.sh`)

Wraps an existing launcher **without duplicating or altering its logic or arguments**:

1. checks the recorder (`--status`) and prints a loud warning if absent, then continues;
2. establishes `run_id = <UTC>-<pid>` and an evidence directory under `~/gpu-crash-records/monitored-runs/`;
3. exports `SOFTBOX_GPU_CRASH_TRACE=1` (+ trace dir, heartbeat) — via the **environment**, so the launcher's argv
   is passed through untouched;
4. runs the launcher, tee-ing stdout and stderr into the evidence directory (process substitution, so `$?` is the
   launcher's own status);
5. writes `run-meta.txt` (run id, UTC start/end, host, cwd, git commit/branch/dirty, recorder state and session,
   exit code, java trace path, final durable marker), `command.txt`, `environment.txt`;
6. **exits with the child's exit status.**

The Java trace path is taken from the child's own `[gpu-crash-trace] <path>` line rather than "newest file", so
overlapping monitored runs cannot be confused.

---

## 11. Post-crash collector (`scripts/collect_gpu_crash_case.sh`)

One command after a freeze + reboot. Creates `~/gpu-crash-case-YYYYMMDD-HHMMSS/` and archives it as
`~/gpu-crash-case-YYYYMMDD-HHMMSS.tar.gz`, gathering: `~/tornado-crash-traces`; all `~/gpu-crash-records` sessions
and monitored-run directories; the previous-boot kernel journal (`journalctl -b -1 -k`) plus a filtered
`previous-boot-nvidia-xid.log` (Xid / NVRM / GSP / nvidia / pcieport / AER); previous-boot `tornado-teardown`
journald markers; `journalctl --list-boots`; current system + full `nvidia-smi -q`; repo revision and dirty files.

It tolerates missing files and permission limits (it prints the `sudo` command to run instead when the previous-boot
journal is not readable), **never deletes a source log**, and prints a concise summary: archive path, latest Java
trace, **its final durable marker**, detected **Xid codes**, GSP/heartbeat line count, recorder coverage window, and
the marker → failure-window interpretation table.

A different previous boot can be selected: `./scripts/collect_gpu_crash_case.sh -2`.

---

## 12. Validation results

No crash reproduction was attempted. All checks below were run on 2026-07-24, RTX 5070 / driver 595.71.05,
JDK 21.0.11, TornadoVM 4.0.1-dev PTX, commit `8d2f318`.

### A. Logger validation — `./scripts/run_crashtrace_validate.sh`

`-emit` in one JVM, `-verify` in a second (so the emitting process's shutdown-hook markers are on disk):

```
[PASS] marker fields complete (ts/seq/elapsedMs/pid/thread/phase)
[PASS] sequence numbers strictly consecutive from 1
[PASS] required marker order (execute/result/close/post-close/shutdown distinguishable)
[PASS] trace survived JVM shutdown (last two markers are the shutdown hook)
[PASS] heartbeat present
[PASS] no TRACE_WRITE_FAILED
[PASS] no LAUNCH_CONFIG_WARNING (explicit-S2 device flags present)
=== CRASH-TRACE VALIDATION: PASS ===
```

### B. Real monitored explicit-S2 GPU run

`./scripts/run_gpu_monitored.sh ./scripts/run_singlehead_gpu.sh -production-cell -density 300 -seed 101 -steps 2000 …`

| Acceptance item | Result |
|---|---|
| external recorder starts | PASS (`journal_ok=yes`, `nvidia_smi_ok=yes`, bus `00000000:01:00.0`) |
| monitored wrapper detects it | PASS (`[monitored] recorder: running`) |
| Java trace created (unique name) | PASS `run-20260724T075912Z-pid10153-explicit-s2-singlehead.log` |
| required launch flags logged | PASS `fma=false bailout=false maxbytecodesize=65536`, no warning |
| phase markers in order | PASS (26 markers, verified by `-verify`) |
| trace survives JVM shutdown | PASS (`SHUTDOWN_HOOK_ENTERED` / `_COMPLETED` are the last two lines) |
| `plan.close()` explicit and observable | PASS (`PLAN_CLOSE_BEGIN` → `PLAN_CLOSE_END`, 7 ms) |
| device identity captured | PASS `device=PTX -- NVIDIA GeForce RTX 5070` |
| no silent CPU fallback | PASS (`bailout=false`; run reported device-resident, `invalid=0 solveFail=0`) |
| external logs cover the window | PASS (1 s NVIDIA samples + kernel follow spanning the run) |
| collection script produces an archive | PASS (84 KB `.tar.gz`) |

Second launcher, `./scripts/run_gpu_monitored.sh ./scripts/run_explicit_twirl.sh -equiv`: both device plans
instrumented — the surface-ON gliding graph **and** the isolated `surfEq` kernel graph (with `DEVICE_SYNC_*`) —
43 markers, gates unchanged (`max|ΔfilCoord|=5.00e-02 µm`, first divergence `t=4`, `Δboundseg=0`,
`max|Δbindazim|=0.00e+00`, `ALL GATED CHECKS PASS`), identical to an untraced control run of the same command.

### C. Scientific output unchanged (traced vs uninstrumented control)

Same command, `-density 300 -seed 101 -steps 2000`, instrumented vs control, comparing the 92-key cell JSON:

```
DIFFS (excluding wall-clock/timing keys): NONE
```

All 92 keys — `velProd`, `velPostEquil`, `meanBoundHeads`, binding/detach events, nucleotide occupancies,
`invalid`, `solveFail`, continuity, window velocities — are **identical**; the only differing keys are
`start_ms`, `end_ms`, `wall_s`, `steps_per_s`, `warm_compile_ms`. The `PARTCROW` summary lines are character
identical:

```
PARTCROW,1,300,101,900,2000,2.35267,1.99288,3.1730,0.7661,20,0,0,0.23000,9.06450,888.20500,2.50050,2000,0,ok
```

This is far stronger than the CPU/GPU aggregate standard — it is exact reproduction of the deterministic seeded
window, including every binding event over it.

### D. Journald mirror

```
Jul 24 01:02:12.419993 aorus1 tornado-teardown[12315]: pid=12082 assay=explicit-s2-twirl PLAN_CLOSE_BEGIN
Jul 24 01:02:12.424931 aorus1 tornado-teardown[12316]: pid=12082 assay=explicit-s2-twirl PLAN_CLOSE_END
Jul 24 01:02:12.442348 aorus1 tornado-teardown[12321]: pid=12082 assay=explicit-s2-twirl SHUTDOWN_HOOK_ENTERED
```

Java lifecycle and kernel NVRM messages are now on **one** clock. Major lifecycle markers only — never heartbeat,
never per-execute ticks.

### E. Recorder + systemd

Single-instance lock refuses a duplicate recorder (exit 3); `--stop` terminates within ~1 s and leaves **no orphan
children** and no stale PID file; `systemctl --user start/stop` verified active → inactive with clean teardown.
Collector correctly extracted the real previous-boot evidence: **`detected Xid codes : 79 154`**,
`GSP/heartbeat lines: 40`.

---

## 13. Runtime overhead

Per durable marker: ~3–7 ms wall (fsync + stderr flush + `logger` spawn for major markers). Marker counts are tiny:
a **2 000-step** cell wrote 26 markers; a **10 000-step** cell writes ~7 durable markers in the execute window
(first + last execute, ~5 heartbeats).

Throughput, 10 000-step production cells, two repeats each:

| Arm | steps/s | wall |
|---|---|---|
| control (uninstrumented) | 422.0, 382.3 | 23.7 s, 26.2 s |
| traced | 413.9, 451.8 | 24.2 s, 22.1 s |

The instrumentation cost (~30 ms of forced writes over a ~23 s run, **≈0.13 %**) is **below the run-to-run
scheduling/thermal noise** — the traced arm was, if anything, faster. Forcing the trace does **not** cause material
performance degradation, and no stop condition was triggered.

---

## 14. Routine operating procedure

### One-time setup

```bash
cd ~/Code/SoftBox
./scripts/install_gpu_crash_recorder_user_service.sh
systemctl --user enable --now softbox-gpu-crash-recorder.service
```

(Manual equivalent, no systemd: run `./scripts/gpu-crash-recorder.sh` in its own terminal.)
Optional, privileged, your choice: `sudo loginctl enable-linger $USER` so the recorder survives logout.

### Before GPU work

```bash
systemctl --user status softbox-gpu-crash-recorder.service     # or: ./scripts/gpu-crash-recorder.sh --status
```

### Running a campaign

```bash
./scripts/run_gpu_monitored.sh ./scripts/run_singlehead_gpu.sh -production-cell -density 300 -seed 101 -steps 40000 -outdir RUN_LOGS/<sweep> -rev <hash>
./scripts/run_gpu_monitored.sh ./scripts/run_explicit_twirl.sh -campaign
```

Proceed with ordinary GPU campaigns. **No teardown pauses. No deliberate crash attempts.**
(Tracing can also be enabled without the wrapper: `SOFTBOX_GPU_CRASH_TRACE=1 ./scripts/run_singlehead_gpu.sh …`,
or by appending `-gpu-crash-trace` to the launcher's arguments.)

### After a hard freeze and reboot

```bash
cd ~/Code/SoftBox
./scripts/collect_gpu_crash_case.sh
```

Then read (or share) `~/gpu-crash-case-*.tar.gz`. The script prints the final durable marker, the detected Xid
codes and the marker → failure-window interpretation table.

---

## 15. Limitations

1. **Timing evidence localises a window, never a cause.** Xid 79 remains consistent with driver/GSP firmware, PCIe,
   power delivery, motherboard or GPU faults; a marker inside `PLAN_CLOSE` would identify the software path present
   at failure, not the mechanism.
2. **Durability is best-effort.** `force(true)` is honoured by the filesystem and drive; a hard freeze can still
   lose the last write. Three independent records mitigate this: the trace file, the launcher's captured stderr,
   and journald.
3. **Marker resolution inside a long execute loop is one heartbeat (5 s)** unless `-gpu-crash-execute-every` is set.
   Per-timestep durable markers are deliberately not the default.
4. **Coverage is the two named launchers.** Other GPU harnesses (47 files construct plans) are untraced; extending
   is ~6 lines per entry point.
5. **Explicit `plan.close()` only happens when tracing is on.** A monitored run's teardown therefore differs from an
   unmonitored run's — that is the intended, disclosed diagnostic difference, and results are unaffected (§12C).
6. **`lspci` capability detail needs root**; sysfs link state and AER counters are used instead and are readable
   here. `nvidia-smi -q -d PCIE` is unsupported on driver 595.71.05.
7. **User systemd services stop at logout without linger**, which is a privileged opt-in left to the user.
8. The recorder retains 14 days of sessions by default; `~/gpu-crash-records` grows ~15 MB/day (mostly the 1 s
   NVIDIA sampler).

## 16. Safety notes

* No GPU reset, no driver reload, no automatic recovery, no BIOS/PCIe/power changes, no privileged action.
* Physics, dt, RNG, event ordering, graph task ordering and buffer deallocation are untouched; the default remains
  buffer deallocation as TornadoVM performs it.
* `plan.close()` is never silently skipped — the non-normal policies are opt-in, warn loudly, and are stamped in
  the trace.
* No per-timestep disk forcing.
* Instrumentation is off by default and can be disabled cleanly (drop the env var / flag) with zero code changes.
* Back up important simulation outputs before extended unattended GPU work; this monitoring preserves evidence, it
  does not prevent a freeze.
