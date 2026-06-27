## 2026-06-24 — Four-way standard-benchmark sweep (v1/v2 × CPU/GPU): v2-GPU wins at low density; high-scale crossover is PROVISIONAL (confounded by lead C)

**Instrument.** The biochem-free 1× standard contractility scene (7.07³-slab, 400 nodes ×24 myo, 1000 fil ×10
seg, crosslinking on, aeta 0.1, treadmilling OFF), swept 1×–8× on all four paths. First fully work-matched
v1-vs-v2 comparison in the project — biochem-off removes the treadmill/nucleation confounds that made every
prior comparison's "work" ambiguous.

**Parity gate caught a real mismatch first (the recurring failure mode, caught BEFORE it corrupted the result).**
v2 was over-binding **4.2×** (1971 bound heads vs v1's ~550) on two unmatched scene params:
- myosin reach `0.025 → 0.006 µm` (= v1 `Env.myoColTol`)
- crosslink on-rate `10 → 40` (= v1 `pf_1x`; `pForm 0.00995 → 0.0392`, exactly v1).
Post-fix v2-CPU bound-count tracks v1-CPU within ~10% ⇒ work-matched ⇒ the grid below is comparable. These are
now the V2OneX defaults (the old 0.025/10 were an unmatched first guess); `-reach`/`-xlonrate` flags retained.
**The viz approved pre-sweep was the over-binding scene — regenerate at corrected parity.**

**The grid — steady-state steps/s (warmup-excluded, work-matched):**

| scale | v1-CPU | v1-GPU | v2-CPU | v2-GPU |
|---|---|---|---|---|
| 1× | 30.4 | 21.4 | 15.3 | **49.5** |
| 2× | 18.7 | 17.1 | 7.6 | **26.7** |
| 4× | 10.9 | 12.8 | 3.8 | 13.5 |
| 8× | 5.86 | **8.4** | 1.8 | 6.6 |

Raw read: v2-GPU wins decisively at 1–2×, ties ~4×, behind by 8×. v2-GPU/v1-GPU ratio 2.31× → 0.79× (cross ~4×).

**Architecture thesis CONFIRMED at low density (the clean win).** At 1× **v1-GPU is slower than its own CPU**
(21.4 < 30.4) — v1's monolithic GPU path is launch-overhead-bound and underwater — while **v2-GPU is faster than
its own CPU** (49.5 vs 15.3, ≥3.2× at every scale) and beats v1-GPU outright below 4×. That gap *is* the
SoftBox bet: device-resident SoA state amortizing across steps vs v1's per-step launch tax. No per-execute creep
(the `fdNuc` carrier isn't in this biochem-off partition; only the throttled `fdXForm` sink is gated).

**The high-scale crossover is PROVISIONAL — three leads, flagged not chased:**
- **A — v2 sequential path (known, not a defect):** v2-CPU is 2.0→3.3× slower than v1-CPU across scale —
  single-thread debug runner vs v1's 16-thread pool + CSR scans growing ∝ scale on one core. v2's bet is the
  GPU; v2-CPU is a validation instrument. File, don't chase.
- **B — v2-GPU's lead over v1-GPU erodes** 2.31× → 0.79×. The real scaling question — but **entangled with C**,
  so its true slope can't be read yet.
- **C — v1-GPU UNDER-BINDS v1-CPU, widening (the load-bearing caveat):** v1-GPU binds only **53% (1×) → 19%
  (8×)** of v1-CPU's bound motors (a v1-internal CPU≢GPU divergence). So **v1-GPU's high-scale rate is partly a
  lighter workload flattering it** — the device-level v2-GPU-vs-v1-GPU comparison is **NOT fully work-matched at
  high scale.** "v1-GPU scales better" may be "v1-GPU sheds binding work faster." Same pattern as the
  node-centric benchmark (v1 looking fast by computing less). **This is a v1-internal correctness issue (a
  BoA-active concern), and it GATES the high-scale read** — until resolved, the 8× crossover over-credits
  v1-GPU and the work-matched device crossover is unknown.

**Clean, UN-confounded win — VRAM (widens in v2's favor at scale).** v2-GPU 508 MiB (1×) → 1.42 GB (8×),
right-sized and scaling with the scene; v1-GPU flat ~1.6–1.8 GB (its fixed static-array floor). Not contaminated
by any binding asymmetry; compounds in v2's favor beyond the tested range — the SoA-residency advantage clean.

**Verdict.** v2-GPU is a clear winner at the densities tested up to the crossover, and the residency thesis is
now *measured* (v2-GPU > v2-CPU where v1-GPU < v1-CPU). Whether v2-GPU "scales worse" at high density is
**unresolved**, because v1-GPU's own under-binding (lead C) flatters its high-scale numbers. **Lead C must be
understood before this grid becomes the canonical v1-vs-v2 scaling story.** The VRAM advantage and the
low-density throughput win stand regardless.

**Committed:** parity-corrected V2OneX defaults + `STANDARD_BENCHMARK_4WAY_FINDINGS.md`. `BoA-v1ref` byte-clean.
**Open:** lead C (v1-GPU under-binding) as the gate on the high-scale comparison; the full scale sweep beyond 8×
once C is understood; regenerate the GPU viz at corrected parity.
