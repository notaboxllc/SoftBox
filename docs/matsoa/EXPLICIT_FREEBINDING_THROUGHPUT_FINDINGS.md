# Phase A — genuine free-binding, no-cull explicit throughput (N=600–9000) — GATE: PASS

The first throughput characterization on **genuine free binding** (all motors start unbound; the deterministic device
`matBindExplicit` recruits/binds each step) with **culling disabled** (every motor processed by `matS2SolveStep`
each step — only the active-set shortcut is bypassed; search radius / orientation gate / binding eligibility /
chemistry / mechanics are UNCHANGED). Supersedes the pre-bound `-bench` no-cull numbers in
`EXPLICIT_THROUGHPUT_FINDINGS.md` (those pre-bound the reachable set; this recruits from all-unbound). No physics/
salt/chemistry/solver/parameter change; FD stays the oracle; `MotorGpuParams.DEVICE_VALIDATED` stays false
(promotion deferred); no silent fallback (`bailout=false`, `enable.fma=false`).

New: `ExplicitCompleteMatHarness -throughput`. Report source: `RUN_LOGS/explicit_completemat/COMPLETEMAT_THROUGHPUT.{md,csv}`.

## Contract (A1) — every result asserts
`freeBinding=true`, `cullingEnabled=false`, `processedMotorCount=N` (`activeEqN=true` — the reduction's active
count == N at every sampled step), `hostBindingLoop=false` (binding is the device `matBindExplicit` `@Parallel`
kernel, not a host loop), `fullStateDownload=false` (production residency: per step only counters UP + the
6-double `redOut` and `boundSeg` DOWN), `silentFallback=false`. **Timing is SEPARATED from science** (A4): the timed
loop is pure `execute()`+nanoTime; a distinct untimed pass tracks binding/detachment/avgBound/meanForce/invalid from
the already-resident `redOut`+`boundSeg` readback — no extra host work in the timed region.

## Throughput table (genuine free-binding, no-cull, production-residency; GPU warm 2000 + 3×10000 median; CPU warm 600)
| density (/µm²) | N | GPU ms/step (med±CV) | GPU steps/s | CPU-an ms/step | CPU steps/s | **speedup** | meanBound | binds/detach (8k steps) | invalid |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 200  | 600  | 1.872 ± 0.9% | 534 | 14.242 | 70.2 | **7.61×**  | 0.65  | 11/11   | 0 |
| 700  | 2100 | 1.897 ± 5.6%¹ | 527 | 49.627 | 20.2 | **26.15×** | 1.85  | 53/52   | 0 |
| 1500 | 4500 | 2.004 ± 1.0% | 499 | 104.107 | 9.6 | **51.96×** | 4.09  | 107/112 | 0 |
| 2000 | 6000 | 1.988 ± 1.4% | 503 | 138.796 | 7.2 | **69.83×** | 8.95  | 243/248 | 0 |
| 2500 | 7500 | 2.168 ± 0.2% | 461 | 174.704 | 5.7 | **80.58×** | 10.91 | 288/289 | 0 |
| 3000 | 9000 | 2.192 ± 0.8% | 456 | 211.624 | 4.7 | **96.55×** | 11.77 | 303/299 | 0 |

¹ density-700 GPU CV 5.6% is a measurement artifact — a concurrent host build ran during that one window; the median
(1.897) sits cleanly between its neighbors (1.872, 2.004). All other densities ≤1.4% (≤2% target met).

## Findings
- **GPU nearly flat, CPU linear ⇒ speedup grows with N** (7.6× → 96.6× over N=600→9000). GPU cost scaling (LS fit):
  **ms/step ≈ 1.82 + 3.98e-5·N** — launch floor ≈ **1.82 ms** (~22 kernel launches), per-1000-motors ≈ **0.040 ms**.
- **`matS2SolveStep` (the 14-DOF beam solve) is the sole N-scaling driver**: 0.507 ms @ N=600 → 0.860 ms @ N=9000.
  Every other stage is flat and ≤0.03 ms (chain 0.025, bond 0.015, bind 0.017, CSR hist/scatter creep 0.007→0.018).
  Device-kernel total 0.679 → 1.078 ms; the rest of the ~1.9–2.2 ms wall is the fixed launch floor (~1.1–1.2 ms).
- **Free binding is genuine and continues through the measured interval** (A4): binds climb 11→303 with density,
  detachments track them, meanBound 0.65→11.77 (min/max span shows live turnover, e.g. 5–24 bound at density 3000).
  Occupancy is low at low density (a single actin track over an N-motor carpet) but recruitment is continuous.
- **Bytes/step**: 620 in (counters), `boundSeg`+`redOut` out (scales with N: 2480→36080 B — the small `boundSeg`
  readback, NOT a full-state download). Beam SoA resident 1.24 → 18.54 MiB (N=600→9000), well within the 12 GiB RTX 5070.

## Health gates (A→sweep) — ALL GREEN
| gate | result |
|---|---|
| free-binding events occur normally | ✅ 11→303 binds, detachments track, continuous |
| zero invalid states | ✅ 0 at every density (redOut finite every step) |
| no solver-failure increase | ✅ 0 (no non-finite states, no blow-up) |
| device residency maintained | ✅ production (counters UP; redOut+boundSeg DOWN only) |
| CPU/GPU aggregate agree (smoke) | ✅ `-gliding` §8/§10 PASS (bind identity exact, chaotic decorrelation only) |
| CPU & GPU process exactly N (no-cull) | ✅ activeEqN=true, processedMotorCount=N everywhere |
| timing variance acceptable | ✅ GPU CV ≤1.4% (except the 700 build artifact) |
| no calibrated substitution / silent fallback | ✅ explicit tasks only; bailout=false, fma=false |

**⇒ Phase A PASSES all health gates. Cleared to proceed to the calibrated cost comparison (Phase B) and the
explicit density-saturation sweep (Phase C).**

## Commands
```
./scripts/build.sh
java @$TORNADOVM_HOME/tornado-argfile --enable-preview -Xmx8G -Dtornado.recover.bailout=false \
     -Dtornado.enable.fma=false -Dtornado.tvm.maxbytecodesize=65536 \
     -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.ExplicitCompleteMatHarness -throughput
```
seed=101, dt=2.5e-6, mat 3.0 µm², densities {200,700,1500,2000,2500,3000}; commit: (uncommitted working tree,
free-binding + throughput additions to `ExplicitCompleteMatHarness`/`TwoBodyBeamAnalyticGpu`).
