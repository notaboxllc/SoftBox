# Concurrency trial — two skew processes on one GPU

**Verdict: two-process skew concurrency REJECTED. The campaign runs one skew arm at a time.**

The authorized retention criteria are technically met, but the measurement shows the gain is
overwhelmingly *redistribution* of a host-bound ceiling away from the repository owner's unrelated
thermal-mechanism campaign, not new device throughput. The serial plan fits the runtime budget with
room, so the trade buys nothing the campaign needs.

## Method

Throughput is read from the crash-diagnostic heartbeat (`executeIndexStarted` vs wall clock), which
counts `plan.execute()` calls = simulation steps, per process. `scratch_gpu_rate.sh` restarts its
accumulation whenever `executeIndex` resets, so an arm boundary cannot corrupt a rate estimate.

1. one skew arm alone (seed 101, ±15°, 100 ms) — 285.8 s window, GPU otherwise carrying only the
   pre-existing thermal-mechanism process;
2. matched partner launched (seed 102) — 235.7 s window with both skew processes live;
3. per-process and aggregate rates compared.

## Result

| configuration | skew #1 | skew #2 | thermal (unrelated) | **skew aggregate** | all processes |
|---|---|---|---|---|---|
| 1 skew process | 272.0 | — | 270.6 | **272.0** | 542.6 |
| 2 skew processes | 191.3 | 191.2 | 190.3 | **382.5** | 573.1 |

(steps/s)

- skew aggregate **+40.6 %** (criterion: ≥ +25 % — **met**)
- each skew arm slowed **−29.7 %** (criterion: ≤ 40 % — **met**)
- GPU memory 797 MiB of 12227 MiB; 51 °C; 58 W of 250 W; SM clock 2910 MHz, no throttle reason active
- no CUDA error, no Xid, no NVRM, recorder healthy throughout (criteria — **met**)
- **unrelated thermal-mechanism process: −29.7 %** (criterion "remains healthy" — running correctly,
  but paying for essentially all of the gain)

## Why the gain is not real capacity

**Total throughput across all three processes rose only 5.6 %** (542.6 → 573.1 steps/s). Of the
+110.5 steps/s the skew study gained, **80.3 came out of the thermal campaign** — ~73 % is
redistribution, ~27 % is new work.

GPU utilisation was **unchanged at 53 %** across both configurations. That is *not* the diagnostic it
looks like: `nvidia-smi` utilisation is the fraction of sampled time with at least one kernel resident,
not SM occupancy, and CLAUDE.md documents this path as **kernel-launch-bound, not work-bound** (fixed
~115–130 µs host cost per launch against a ~8000 launches/s ceiling; ~50 kernels and ~14
`EVERY_EXECUTION` transfers per step). A launch-bound graph pins that metric at partial utilisation
while leaving the SMs idle, so utilisation can neither confirm nor refute added capacity. The
steps/s ledger above is the measurement that decides it — and it says the device is near a *shared
host-side* ceiling, exactly as the unchanged utilisation hinted.

## Decision

Reverted to one skew process. Serial cost for the full authorized tree is ≈ 11.4 h at 272 steps/s
(28 arms × 400 000 steps) against a 20 h cap, so two-process scheduling would have saved ≈ 3.3 h I do
not need while costing the owner's concurrent campaign ~30 % of its rate for the duration.

The second process (seed 102, 87 783 of 400 000 steps into `p_102`) was terminated with `SIGTERM` by
exact PID. It produced the **documented §5b teardown signature** — exit 134, `SIGSEGV` in
`libcuda.so.1`, core dumped, `hs_err_pid3350710.log` — from killing a JVM blocked mid-`execute()`.
This is **not** a device fault: the shutdown hook ran to `SHUTDOWN_HOOK_COMPLETED`, and there was no
Xid and no NVRM message. `atpWrite` is temp-file + atomic rename, so the interrupted arm left **no
partial record and no stray `.tmp`**; seed 102 was simply re-run later from the serial chain.

Concurrency affected wall-clock scheduling only. No model parameter, RNG key, record identity or
statistical treatment differs between the trial arms and the rest of the campaign.
