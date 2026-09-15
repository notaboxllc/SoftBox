# Disabling GSP firmware offload — procedure and rationale

> ## ⛔ OUTCOME 2026-08-29: THIS ROUTE DOES NOT WORK ON THIS MACHINE. DO NOT RETRY.
>
> The steps below were executed. **`NVreg_EnableGpuFirmware=0` is accepted but NOT honoured**, because this
> machine runs the **NVIDIA open kernel module**, which requires GSP by design.
>
> Evidence after the reboot:
> ```
> $ grep EnableGpuFirmware /proc/driver/nvidia/params
> EnableGpuFirmware: 0                      <- the parameter string was accepted
> $ nvidia-smi -q | grep -i "GSP Firmware"
> GSP Firmware Version : 595.84             <- but GSP is STILL RUNNING
> $ journalctl -k -b | grep "NVRM: loading"
> NVRM: loading NVIDIA UNIX Open Kernel Module for x86_64  595.84
> ```
> Installed: `nvidia-driver-595-open`, `nvidia-dkms-595-open`. **The param reads 0 while GSP is active — the
> `/proc` value reflects what the driver was TOLD, not what it DID.** Checking only `/proc/driver/nvidia/params`
> would have produced a false confirmation; the `nvidia-smi` cross-check is what caught it (credit: jba).
>
> **A proprietary (non-open) `nvidia-driver-595` exists in the archive, but switching to it is NOT a quick test
> and is probably not viable**: the RTX 5070 is Blackwell-generation, and NVIDIA's closed module dropped support
> for the newest architectures — attempting the swap risks leaving the GPU unusable. Not attempted.
>
> **The `/etc/modprobe.d/nvidia-gsp-off.conf` file is therefore a no-op and should be removed** so it does not
> mislead a later reader:
> ```bash
> sudo rm /etc/modprobe.d/nvidia-gsp-off.conf && sudo update-initramfs -u
> ```
> (No reboot needed just to remove it; it changes nothing either way.)
>
> **Status of the GSP hypothesis: UNTESTED, not refuted.** The kernel-log evidence in §"Why GSP is the suspect"
> still stands and is still the best available signature. It simply cannot be tested by this method on this
> driver.
>
> **What to do instead:** treat GPU faults as an operational hazard (the per-batch bus guard already handles them
> cleanly), and use the CPU runner when a campaign must not be interrupted. See the last section.


**Date** 2026-08-29 · **Machine** aorus1 · **GPU** RTX 5070 · **Driver** 595.84 · **Kernel** 5.15.0-190-generic

**Original framing (superseded by the banner above):** HYPOTHESIS TEST, not a known fix. This is a cheap, reversible way to discriminate between a GSP
firmware problem and a hardware/PCIe problem. Do not record it as a solution unless several days of mixed load
pass without a wedge.

---

## The commands

Run each in a terminal (or with `! ` in front from Claude Code).

**1. Create the driver option file**
```bash
sudo tee /etc/modprobe.d/nvidia-gsp-off.conf <<< 'options nvidia NVreg_EnableGpuFirmware=0'
```

**2. Rebuild the initramfs** — the option must be present at boot, since `nvidia` loads early
```bash
sudo update-initramfs -u
```

**3. Reboot**
```bash
sudo reboot
```

**4. VERIFY THE CHANGE TOOK.** This step is not optional — if the option did not apply you would be re-running
the same experiment while believing it had changed.
```bash
grep EnableGpuFirmware /proc/driver/nvidia/params
```
- **Want:** `EnableGpuFirmware: 0`
- **Currently reads:** `EnableGpuFirmware: 18`
- If it still reads `18`, the option did not take. Stop; do not draw conclusions from the next run.

Secondary check:
```bash
nvidia-smi -q | grep -i "GSP Firmware"
```
Expect `N/A` or absent, rather than `595.84`.

**5. Resume GPU work.** The `TWIRL_RANDBASE_LADDER` campaign directory is already cleaned (see below), so:
```bash
./scripts/twirl_randbase_ladder.sh
```

---

## To undo

```bash
sudo rm /etc/modprobe.d/nvidia-gsp-off.conf && sudo update-initramfs -u && sudo reboot
```

---

## Why GSP is the suspect

Every recorded wedge shows the GSP (the management processor on the card) ceasing to answer RPCs, in one of two
forms. From `~/gpu-crash-records/*/kernel-live.log`:

```
Aug 29 14:12  NVRM: GPU0 _issueRpcAndWait: rpcSendMessage failed with status 0x0000000f for fn 10
              NVRM: GPU0 rpcRmApiFree_GSP: GspRmFree failed
Aug 29 15:00  (same pair — the second crash of the day)
Aug 29 20:47  nvidia-modeset: ERROR: GPU:0: Error while waiting for GPU progress: 0x0000ca7d:0 2:0:4048:4044
              (repeating every ~5 s until reboot)
Aug 26 17:48  nvidia-modeset: ERROR: GPU:0: Error while waiting for GPU progress: 0x0000ca7d:0 2:0:4048:4044
              (identical signature, repeating)
Aug 24 15:41  NVRM: nvAssertFailedNoLog: Assertion failed: (status == NV_OK) || (status == NV_ERR_GPU_IN_FULLCHIP_RESET)
              NVRM: GPU0 _deviceTeardown: Disable of Cuda limit activation failed
```

Sequence: GSP stops answering → `nvidia-modeset` (the *display* driver) can no longer get the GPU to make
progress → only a reboot recovers it.

### The load-bearing observation: our workload is NOT the trigger

The monitored-run index (`~/gpu-crash-records/monitored-runs/`) contains **zero SoftBox GPU runs on Aug 25 or
Aug 26** — it jumps from Aug 24 to Aug 27. **The Aug 26 17:48 wedge happened with no SoftBox GPU work running at
all**, and carries the *identical* modeset signature to the Aug 29 crashes.

⇒ SoftBox runs die as a **consequence**, not a cause. That is why the failures appear to cluster at
`rpcRmApiFree_GSP` / `_deviceTeardown`: those are simply the RPCs that fail once the GSP is already wedged.

### A correlation found and DISCARDED

Of **102 run configs ever written** by this harness lineage, only the **6 from 2026-08-29** carry
`rand_base_azimuth: true` — and both randbase batches crashed. That looked like a strong lead, but it is
perfectly confounded with "today", and the Aug 26 zero-load wedge rules it out as a general explanation. **Do not
re-open the randbase hypothesis without new evidence.**

### What the fault rate actually looks like

Reboots: Jul 26 · Jul 27 · Jul 28 · **Aug 21 ×2** · Aug 26 · **Aug 29 ×2 (14:18, 20:48)**. Faults have occurred on
kernels **-186, -187 and -190**, all on driver **595.84** (upgraded 2026-07-24). So it is not a clean kernel
regression.

Against that, **Aug 27 08:45 → Aug 28 17:56 ran ~33 h of continuous GPU campaigns with no fault** (the eps-ladder
and the converter-skew campaign). Then two faults in one afternoon at **9 min and 34 min** of load.

**Consequence for interpreting the next run: one clean 8-hour rung is WEAK evidence.** The variance is larger
than that. Several days of mixed load without a wedge is the bar.

---

## State on the SoftBox side (already done, nothing to redo)

- Fault-2 evidence archived: `~/gpu-crash-case-20260829-205433.tar.gz`
- Fault-killed partial runs moved aside (NOT deleted): `/tmp/randbase_e15_fault2_*` and
  `/tmp/randbase_e15_faultkill_*`
- `RUN_LOGS/motor_audit/campaigns_2026-08/TWIRL_RANDBASE_LADDER/` is **empty and clean**

**Why the partials had to be moved rather than resumed:** `SiteNormalLongGlideHarness:708` writes trajectory rows
with `StandardOpenOption.APPEND`. Relaunching over a stale `trajectory_summary.csv` appends new rows to old ones,
producing a duplicate-step, time-goes-backwards trajectory that the analyser will fit a slope through without
complaint. **After ANY future fault, clear the interrupted arm's directory before relaunching** — do not let the
script's `-resume` path run into a stale trajectory file.

---

## If it crashes again anyway

The evidence then points at hardware (card, PCIe link, power delivery) rather than firmware, and the card wants
attention rather than further workarounds. The science can proceed meanwhile on the **CPU runner**, which touches
no GPU at all:

- randbase eps=15 rung, 6 runs, one batch ≈ **20–24 h** on CPU
- precedent: `TWIRL_SKEW15` seeds 902/903 were CPU replications for exactly this reason
- the runners agree — the GPU eps=15 rung matched the mixed-runner value to **0.13σ**
