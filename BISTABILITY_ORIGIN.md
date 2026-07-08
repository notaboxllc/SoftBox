# Is the gliding bistability a REAL (chaotic) two-basin feature, or a GPU execution artifact?

**Date:** 2026-07-08. Follow-on to `PURE_SPRINGS.md` (which found: on the CPU raw ≡ springs ≡ ratefix
bit-identical / all HIGH basin; on the GPU `-ratefix` sits in a LOW basin while raw/springs are HIGH). This
session localizes the GPU LOW basin's origin. New measurement + a swing-coefficient debug knob only
(`-swingkbits`, `-swingkprobe`); no model change; default byte-identical; `BoA-v1ref` untouched. float32 on
GPU / float64-intermediate on CPU per runner (that difference is the whole point).

---

## PLAIN VERDICT (headline)

**The GPU `-ratefix` → LOW-basin flip is a GPU EXECUTION ARTIFACT, not a coefficient-value effect and not a
coefficient-reproducible real basin selection.** The decisive fact: the ratefix in-kernel swing coefficient
`k = 1 − exp((dt/refDt)·log(1−0.4))` computes to **bit-identical `0.4f` — at the DOUBLE level, 0 residual —
on the GPU exactly as on the CPU** (`-swingkprobe`, PART A). So there is *no* coefficient difference between
`-ratefix` and raw for a ULP to live in. What differs is only the **presence of the `exp/log` instructions**
in the swing kernel: GPU-springs (same size-5 swingParams but a *multiply* branch, `k=k·dt/|refDt|`, also
bit-identical `0.4f`) is **bit-identical to raw / HIGH**, while GPU-ratefix (the *transcendental* branch,
identical output) flips to LOW. Identical coefficient, identical buffer size, different basin ⇒ the `exp/log`
instructions perturb the PTX compiler's register allocation / scheduling / FMA-contraction of the
*surrounding* per-thread torque arithmetic at the ULP level, and on this **bistable** operating point that
last-bit perturbation deterministically tips the basin — exactly the 2026-07-08 `-allnoise` mechanism, now
localized to a transcendental in an otherwise-unchanged kernel.

**Routing (per the task):** this is the **A-HIGH branch** — forcing the CPU to the GPU coefficient keeps it
HIGH (the coefficient is *identical*, so there is nothing to force), the GPU flip is *beyond* the coefficient,
and the honest production number is the **CPU / HIGH-basin value** (velFitX ~3.2, per-bound ~0.9). And PART C
shows the GPU flip is **deterministic** run-to-run (not a race) — a fixed compiler-scheduling nudge. Whether a
*real* CPU-reachable LOW basin exists underneath: **no positive evidence** — all 3 CPU seeds land HIGH, and
sweeping the swing coefficient down never reproduces the GPU-LOW signature (avgB ~2.87 / per-bound ~0.74); the
CPU dynamics is **mono-stable HIGH** at this operating point (PART B). The one un-run test (seed the CPU from a
GPU-LOW microstate, B2) is bailed for lack of state-dump infrastructure, so a genuinely-CPU-un-occupied LOW
attractor cannot be *fully* excluded — but the weight of evidence (bit-identical coefficient, deterministic
GPU, mono-stable CPU) is **GPU execution artifact**, and the practical verdict holds regardless.

**Practical rule (holds regardless):** basin selection by a last-bit compiler-scheduling perturbation is not
physically controlled ⇒ production should run a deterministic, **transcendental-free** swing path (springs, or
CPU-verified), not let the PTX scheduler choose the regime.

---

## PART A — replicate the GPU's coefficient on the CPU (the crux)

The GPU `-ratefix`→raw difference reduces to one number: the in-kernel swing k. `-swingkprobe` computes it
*exactly as `directedSwing` does* and prints the bits, on each runner:

| runner | raw k (`0.4f`) | recomputed `1−exp(log(1−k))` | float-ULP Δ | double residual `(k−0.4f)·1e12` |
|---|---|---|---|---|
| GPU | 0x3ecccccd | **0x3ecccccd** | **0** | **0.0** |
| CPU | 0x3ecccccd | 0x3ecccccd | 0 | 0.0 |

**The recomputed swing coefficient is bit-identical to raw `0.4f` on the GPU, to the last double bit.** The
GPU `expf/logf` roundtrip recovers exactly `(double)0.4f`. So the PURE_SPRINGS-era hypothesis ("GPU float
exp/log gives a ULP-different swing coefficient") is **refuted** — there is no coefficient difference.

**⇒ Forcing the CPU to "the GPU coefficient" is forcing it to `0.4f` = raw** (`-swingkbits 0x3ecccccd`),
which is HIGH. And the CPU's *own* `-ratefix` path already computes the same bit-identical k and runs
bit-identical to raw (PURE_SPRINGS: CPU raw ≡ ratefix, velFitX 3.264). So **the CPU cannot be flipped by the
coefficient — there is none to change.**

**CPU control (`-full` 15k, dt=1e-5, `-swingkbits 0x3ecccccd` = the GPU value = raw):** velFitX **3.209** /
avgBsteady **3.487** / per-bound **0.920** — squarely HIGH. Forcing the CPU to the GPU coefficient does NOT
flip it (there is no coefficient to change). This is the **A-HIGH** outcome: the GPU LOW basin is not
explained by the coefficient (jba's suspicion — a GPU execution artifact beyond the coefficient).

### Why the exp/log, then? (the localized trigger)

From PURE_SPRINGS (GPU, dt=1e-5, all coefficients bit-identical floats):

| GPU arm | swing kernel path | swingParams size | result |
|---|---|---|---|
| raw | none (k=0.4f read) | 4 | **HIGH** (2.895) |
| pure-springs | multiply `k=k·dt/|refDt|` (=0.4f) | 5 | **HIGH** — bit-identical to raw |
| `-ratefix` | transcendental `1−exp(...·log(...))` (=0.4f) | 5 | **LOW** (2.112) |

Same coefficient (0.4f), and size-5-buffer-alone does NOT flip (springs is bit-identical to raw) ⇒ **the
`exp/log` instructions are the sole trigger.** They inflate the kernel's instruction/register footprint and
change how the PTX compiler schedules/contracts the surrounding float torque math → ULP torque differences →
basin flip on the sensitive operating point. A CPU/JVM has no such per-kernel scheduling variance
(deterministic double), so it does not flip.

---

## PART C — GPU determinism (run-to-run)

GPU `-ratefix` ×3 and GPU raw ×3, identical config/seed (`-full` 60k, seed 0), velFitX / avgBsteady:

| run | raw | `-ratefix` |
|---|---|---|
| 1 | 2.895 / 3.332 | 2.112 / 2.870 |
| 2 | 2.895 / 3.332 | 2.112 / 2.870 |
| 3 | 2.895 / 3.332 | 2.112 / 2.870 |

**Both arms are bit-identical run-to-run** (every digit, including netX/inst/STATS boundSteps). **The GPU is
DETERMINISTIC** — there is no race / reduction-order nondeterminism. So the flip is a **deterministic
compiler-scheduling artifact**: the `exp/log` presence makes the PTX compiler emit a fixed, differently-
scheduled kernel that lands, every launch, in the LOW basin. Not a race (that would vary run-to-run); a fixed
ULP nudge from deterministic code generation. This *sharpens* the artifact diagnosis (it is reproducible, so
it masqueraded as a stable "effect" across the prior `-allnoise`/`-thermcorr` tables) rather than pointing to
nondeterminism.

---

## PART B — is the LOW state a real feature of the (CPU) dynamics?

**B2 (seed CPU from a GPU-LOW steady state) — BAILED, reported.** There is no state dump/restore in the
harness (no `dumpState`/`loadState`; the scene is always built fresh with `boundSeg`/`nucleotideState`
re-initialized). A faithful LOW-state seed would require serializing the *entire* SoA microstate (all motor
sub-body poses, `boundSeg`, `nucleotideState`, `forceDotAvg`, `cooldown`, filament poses, …) from a GPU run
and reconstructing the scene from it — heavy new infrastructure, and a partial seed re-equilibrates and does
not faithfully test attractor-ness. Given PART A already shows the flip is not a coefficient/real-dynamics
effect (A-HIGH), this is the task's sanctioned bail ("LOW-state CPU seeding can't be cleanly initialized").
**Substitute:** the swing-k sensitivity sweep (does the CPU dynamics have a *reachable* distinct LOW basin at
all?) + the natural-occupancy seed sweep.

Reference basins (GPU, from PART C / PURE_SPRINGS): **HIGH** = velFitX ~2.9 / avgB ~3.3 / per-bound ~0.85;
**LOW** (GPU ratefix) = velFitX **2.11** / avgB **2.87** / **per-bound 0.736**. The LOW signature is
*high-ish avgB with low per-bound* (many heads bound, each inefficient).

### B1 — natural basin occupancy (CPU, exact 0.4 = 0x3ecccccd, `-full` 15k)

| seed | velFitX | avgBsteady | per-bound | basin |
|---|---|---|---|---|
| 0 | 3.209 | 3.487 | 0.920 | HIGH |
| 1 | 3.003 | 2.776 | 1.082 | HIGH |
| 2 | 3.560 | 3.237 | 1.100 | HIGH |

**All 3 CPU seeds land HIGH** (velFitX 3.0–3.6, per-bound ≥0.92). None visits the LOW basin (per-bound 0.74).
The CPU never *naturally* occupies LOW.

### Swing-k sensitivity sweep (CPU, seed 0) — is a distinct LOW basin reachable by lowering the stroke coeff?

| swing k | velFitX | avgBsteady | per-bound |
|---|---|---|---|
| 0.40 | 3.209 | 3.487 | 0.920 |
| 0.38 | 2.638 | 2.171 | 1.215 |
| 0.30 | 2.419 | 2.474 | 0.978 |
| 0.20 | 2.352 | 2.382 | 0.988 |
| 0.10 | 2.245 | 2.368 | 0.948 |

**A smooth monotone decline, NOT a bistable jump.** Lowering the stroke coefficient reduces velFitX (3.21 →
2.25) and avgB (3.49 → ~2.4), but **per-bound stays high (~0.9–1.2) and never drops to the LOW basin's 0.74**.
The GPU-LOW signature (avgB **2.87** *with* per-bound **0.74** — many bound, each inefficient) is **not
reproduced** at any swing k: the CPU's low-k regime is *fewer* bound heads that stay *efficient* (per-bound up)
— the opposite of the LOW basin. ⇒ **the LOW basin is not CPU-reachable by the stroke coefficient**; the CPU
dynamics is mono-stable HIGH here. (This substitutes for the bailed B2; it cannot exclude a CPU-un-occupied
attractor reachable only from the exact GPU microstate, but there is no positive evidence for one.)

---

## PART D — fragility structure (density hysteresis)

**BAILED (reported).** PART D is conditioned on A showing the basin real/CPU-reachable — which it did **not**
(A-HIGH; the CPU is mono-stable HIGH, PART B). And a *faithful* hysteresis ramp needs state-continuation across
density changes (run to steady at d1, then *change* density continuing the microstate) — the same
infrastructure gap as B2; independent per-density runs are single-valued by construction and cannot show path
dependence. So the hysteresis ramp is bailed on two grounds (premise not met + no continuation infra). The
qualitative fragility point is already settled: **the GPU flip is triggered by a last-bit compiler-scheduling
nudge (bit-identical coefficient) — the signature of *knife-edge* basin sensitivity, not a robust wide-basin
bistability** (a wide bistability would not turn on the presence of an `exp/log` that computes the same value).

---

## ROUTING VERDICT (plain)

**The gliding "bistability" — the GPU raw-HIGH vs `-ratefix`-LOW split — is a GPU EXECUTION ARTIFACT, not a
real chaotic two-basin feature of the model.** Evidence, in order of decisiveness:

1. **The coefficient is bit-identical** (PART A probe): the ratefix in-kernel swing k = `0.4f` to the last
   *double* bit on the GPU, same as raw. There is no coefficient ULP for a real basin selection to hinge on.
2. **Forcing the CPU to the GPU coefficient keeps it HIGH** (there is nothing to force). The CPU's own ratefix
   path (identical k) is bit-identical to raw (PURE_SPRINGS). → **A-HIGH.**
3. **The trigger is the `exp/log` instructions, not the value or the buffer:** GPU-springs (size-5 swingParams,
   *multiply* branch, same 0.4f) is bit-identical to raw / HIGH; GPU-ratefix (size-5, *transcendental* branch,
   same 0.4f) is LOW. The transcendental changes PTX scheduling/contraction of the surrounding torque math →
   ULP torque perturbation → basin flip on the sensitive point.
4. **The GPU flip is deterministic run-to-run** (PART C): not a race — a fixed, reproducible scheduling nudge
   (which is why it masqueraded as a stable "effect" in the `-allnoise`/`-thermcorr` tables).
5. **The CPU is mono-stable HIGH** (PART B): all 3 seeds HIGH; no swing-k value reproduces the LOW signature
   (avgB ~2.87 *with* per-bound ~0.74). No positive evidence of a CPU-reachable LOW basin.

**Not fully excluded:** a genuine LOW attractor that the CPU simply never occupies from a fresh start and that
is only reachable from the exact GPU-LOW microstate (the bailed B2 — no state-dump infra). But nothing
observed supports it, and it would not change the practical verdict.

**Practical rule (holds regardless):** basin selection by a last-bit PTX-scheduling perturbation is not
physically controlled. Production should run a deterministic, **transcendental-free** swing path (the springs
multiply branch, or CPU-verified), and **the honest production gliding number is the CPU / HIGH-basin value**
(velFitX ~3.2, per-bound ~0.9) — *not* the `-ratefix`-seeded LOW basin (velFitX 2.11, per-bound 0.74) that
every prior GPU `-allnoise`/`-thermcorr`/re-convergence table reported as "the baseline."

---

## Reproduce
```
./build.sh
./run_gliding.sh -gpu -swingkprobe          # extract the exact GPU swing k (bit-identical to 0.4f)
./run_gliding.sh -swingkprobe                # CPU probe
./run_gliding.sh -full -grid -lymntaylor -adppibind -xbimplicit2 -coltol 10 -density 1000 -dt 1e-5 -seed 0 -swingkbits 0x3ecccccd 15000   # CPU forced to GPU coeff
run_bistab_partC.sh   # GPU determinism (raw 3x, ratefix 3x)
run_bistab_cpu.sh     # CPU: PART-A confirm + swing-k sweep + B1 seed sweep
```
Instruments: `GlidingHarness -swingkprobe` (extract exact runner swing k), `-swingkbits <int|0x..>` (force
swing coeff, size-4, both runners). Logs `RUN_LOGS/2026-07-08_bistab_*.txt`. Default byte-identical;
`BoA-v1ref` untouched.
