# GPU hard freeze — 2026-07-26, Xid 79 + 154 (node reboot required)

**One line:** the RTX 5070 fell off the PCIe bus mid-kernel during the §25 Stage-4 converter-skew screen; the
host required a reboot. The run died at step 6660/8000 with `exit=134` (SIGABRT). **No scientific result is
implicated** — this is the standing hard-freeze issue CLAUDE.md's mandatory monitoring exists to capture.

## 1. Evidence archive

| item | value |
|---|---|
| archive | `/home/jba/gpu-crash-case-20260726-082223.tar.gz` |
| case directory | `/home/jba/gpu-crash-case-20260726-082223` (sources NOT deleted) |
| collected by | `./scripts/collect_gpu_crash_case.sh`, run BEFORE any further GPU work (CLAUDE.md rule) |
| java trace | `tornado-crash-traces/run-20260726T151035Z-pid1137054-chiral-actin-sites.log` |
| repo revision | `c6f44ac` on `gpu-mat-bottlenecks-explicit-singlehead` (+ uncommitted `ChiralSiteHarness.java`) |
| recorder coverage | `20260724T075653Z` .. `2026-07-26T15:22:15Z` (continuous across the event) |
| recorder session after reboot | `20260726T151845Z` (fresh) |

## 2. What was running

`./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -conv-ramp-screen -gpu
-converter-stroke-skew-deg 15 -seeds 8 -steps 8000` — the §25 Stage-4 five-arm, 8-matched-seed full-cycle
budget screen, device-resident on `buildGlidingGraph(prod)`, one plan per (arm, seed).

Launcher flags as required: `-Dtornado.enable.fma=false -Dtornado.recover.bailout=false
-Dtornado.tvm.maxbytecodesize=65536`. The monitored wrapper was the outermost launcher and the external
recorder was RUNNING at launch (verified).

**Progress at death:** arm **A (always-active)** completed, both ±ε. Crash occurred during arm **B (binary
gated)**. Arms **C/D/E** (linear, smoothstep, delayed) never ran.

## 3. Failure signature

Final durable marker (the window in which the failure occurred):

```
2026-07-26T15:17:11.363519767Z seq=000329 elapsedMs=396157 pid=1137054 thread=gpu-crash-heartbeat
  phase=HEARTBEAT state=EXECUTING executeIndexStarted=6660 executeIndexCompleted=6659
  lastStepStarted=6660 lastStepCompleted=6659
```

`state=EXECUTING` with `executeIndexStarted` one ahead of `executeIndexCompleted` ⇒ the process died **inside
kernel execution / device transfer / `execute()` internals** — NOT in result handling, `plan.close()` or JVM
shutdown. This is a different window from the previously-suspected teardown paths.

Kernel log:

```
Jul 26 08:17:15 NVRM: Xid (PCI:0000:01:00): 79, GPU has fallen off the bus.
Jul 26 08:17:15 NVRM: Xid (PCI:0000:01:00): 154, GPU recovery action changed from 0x0 (None)
                                                 to 0x2 (Node Reboot Required)
```

followed ~23 s later by the GSP teardown cascade — `rpcSendMessage failed with status 0x0000000f`,
`rpcRmApiFree_GSP: GspRmFree failed`, and `nvAssertFailedNoLog: (status == NV_OK) || (status ==
NV_ERR_GPU_IN_FULLCHIP_RESET)` at `rs_client.c:844`, `rs_server.c:259`, `rs_server.c:1375`. 151 GSP/heartbeat
lines in the previous boot. The trailing `apport-gtk` segfault is collateral (crash reporter), not causal.

## 4. Attribution — stated with its limits

The collection script's own caveat governs: **timing evidence localises the WINDOW, not the root cause.**
Xid 79 can originate from the driver/GSP firmware, the PCIe link, power delivery, the motherboard, or the GPU
itself.

Three observations argue this is the standing hardware/driver issue rather than anything the §25 work
introduced:

1. the failure is a **bus-level Xid with a mandated host reboot**, not a lowering error, a
   `TornadoInternalError`, or an arithmetic/NaN fault;
2. the **identical device graph** ran ~4 h of §23/§24 campaigns plus the §25 `h3` CPU/GPU equivalence check
   without incident, immediately before;
3. the §25 progress ramp adds **no TaskGraph task, no buffer and no device work** — only branches inside an
   already-wired kernel (`convFrameStep`), and `chiP` grew from 21 to 25 doubles.

This is recorded as evidence, not as a diagnosis. The monitoring rule stays in force.

## 5. Scientific impact

**None on any recorded result.** Specifically:

- The §25 **CPU/GPU equivalence with the smoothstep ramp completed cleanly before the crash**
  (`h3_ramp_equiv_gpu.txt`): `bindMism = 0`, `convFlagMism = 0`, `max|dConvFrame| = 1.54e-06`,
  `max|dSegTorque| = 1.51e-24 N·m`, `max|dFilCoord| = 5.96e-08 µm`, `firstDiv = none`, bound CPU = 9 / GPU = 9,
  **PASS, device-resident, no fallback**. The ramp's device path is validated.
- §25 Stages 0–3 are deterministic **CPU** work (12/12 gates) and are untouched.
- The partial `h4` log is **not** used for any claim: a five-arm matched-seed comparison cannot be assembled
  from one completed arm, and splicing arm A across a reboot into arms measured later would break the
  matched-seed premise. The screen is re-run in full.

## 6. Actions taken

1. `collect_gpu_crash_case.sh` run before any further GPU work (evidence above).
2. Post-reboot gates verified: recorder RUNNING on a fresh session; `nvidia-smi` responsive (378 MiB, 0 %).
3. §25 Stage-4 screen **re-launched in full (all five arms)** rather than resumed, so the matched-seed
   differences remain internally consistent within one boot.
