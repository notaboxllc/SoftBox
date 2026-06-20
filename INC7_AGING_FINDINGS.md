# Increment 7 — AGING build (A): per-segment nucleotide proxy + nucleotide-dependent depoly rates — FINDINGS

**Status: DONE (2026-06-20). 5 gates PASS GPU + CPU; aging matches the analytic cascade to 6.6e-5; the new
nucleotide-asymmetric `C_c` matched to first principles to 0.8%; conservation EXACT; CPU≡GPU; fixed-rate baseline
bit-identical; default-OFF.** Filaments now **age** (per-segment ATP→ADP-Pi→ADP, watchable as a cascade along the
filament) and the aging **drives the pointed-end depoly rate** (the nucleotide-asymmetric off-rate). The aging
layer of the simplified turnover machinery (recon `INC7_TURNOVER_RECON.md` §2; the §2 representation fork
resolved). Run: `./run_aging.sh [-cpu]`. Log: `RUN_LOGS/2026-06-20_aging_proxy_validation.txt`. **No default flip.**

## DECISION (confirmed by jba before building)
The **3-component per-segment proxy** `(f_ATP, f_ADPPi, f_ADP)` summing to 1 (the recommended option), so the full
ATP→ADP-Pi→ADP cascade is carried; the PHYSICS reads only `f_ADP`, the intermediate `f_ADPPi` costs ~one extra
per-segment scalar and buys the visible cascade for a future band-aware viewer. **Viewer constraint surfaced +
accepted:** the verbatim v1 viewer (forbidden to fork) renders ONE channel (`seg.notADPRatio`, `ageColor`
red↔young), so the watchable output today is the ADP *gradient* barbed→pointed; a *distinct* ADP-Pi color band
needs a viewer change. The frame hook emits `notADPRatio = f_ATP + f_ADPPi` for the gradient AND the raw
`(fATP, fADPPi, fADP)` as extra JSON fields the current viewer ignores (for a future viewer / analysis).

## What was built (a NEW v2 representation — flagged)
The proxy is a **new v2 representation** — v1 carries a per-monomer `Monomer` list (3-state `nucleotideState`)
and the inc-6 assays ran `noMonomersSimd=true` (no monomers at all). The proxy is faithful to v1's per-monomer
cascade **KINETICS in AGGREGATE**, NOT a per-monomer bit-match (recon §2 / CLAUDE.md §8). **All ADDITIVE** — no
existing kernel touched; the new kernels ride on existing per-cadence outputs and write only their own array.

- **`AgingStore`** — the per-segment composition `nucFrac[3·filCap]` (planar: ATP | ADPPi | ADP), the cascade
  params (`pH = kHydrolysis·biochemΔt`, `pD = kDissociation·biochemΔt`, DERIVED each cadence — never stale), the
  cadence fire flag, and the nucleotide-dependent depoly rate params (`pATP = kATPOff1·biochemΔt`,
  `pADP = kADPOff1·biochemΔt`).
- **`AgingSystem`** — 3 device-agnostic kernels (GPU TaskGraph ≡ `-cpu`):
  - **`age`** — every ACTIVE segment, one cadence of the cascade (forward-Euler of the two-step linear ODE):
    `f_ATP' = f_ATP·(1−pH)`, `f_ADPPi' = f_ADPPi·(1−pD) + f_ATP·pH`, `f_ADP' = 1 − f_ATP' − f_ADPPi'`. The **remainder
    form** for `f_ADP` (≡ `f_ADP + f_ADPPi·pD` in real arithmetic) ANCHORS the per-segment sum to 1 each cadence
    (removes float32 two-place-cancellation drift — see Notes).
  - **`growthAtp`** — a grown barbed tip (`grewFlag=1`, `monomerCount` already ++'d to M) gained one fresh ATP
    monomer ⇒ reweight: `f' = f·(M−1)/M` (+`1/M` for ATP). Sum conserved.
  - **`splitInheritNuc`** — a split child inherits its parent's composition. Mirrors `GrowthSystem.splitWire`'s
    iteration EXACTLY (per accepted request `r`: parent `Gs=r`, child `Cs=freeList[rankOffsets[r]]`) over the SAME
    rank/free-list arrays ⇒ **no GrowthSystem edit**; the parent fraction is intensive (unchanged by the
    monomerCount halving) so copying it is the faithful inheritance.
- **`DepolySystem.depolyProxy`** — the nucleotide-dependent pointed-end depoly. IDENTICAL to `depoly()` except the
  per-event probability is per-segment: `P = pATP·(1−f_ADP) + pADP·f_ADP` (the pointed tip's `f_ADP`), instead of
  the single FIXED `depolyParams[0]`. **`depoly()` is byte-unchanged** ⇒ the Stage-1 fixed-rate baseline preserved.
- **`FrameWriter.writeFrame(FilamentStore, AgingStore, t)`** — the additive viewer hook (existing method
  byte-unchanged): emits `notADPRatio = f_ATP+f_ADPPi` + the raw composition, skips FREE slots.
- **`Constants`** — `kHydrolysis = 0.3/s`, `kDissociation = 1.0/s` (additions only, no existing value changed).
- **`AgingHarness`** + **`run_aging.sh`** — the 5-gate validation, both runners.

## The PREDICTION — computed BEFORE measuring (first principles, §8)
The pointed-most segment is the OLDEST: at steady state the filament transit time (≈ L_ss / k_off ≈ tens–hundreds
of seconds) ≫ the cascade aging time (1/kH + 1/kD ≈ 4.3 s), so the pointed segment is **≈100% ADP** ⇒ its off-rate
≈ **kADPOff1**. Hence the new critical concentration:
> **C_c = kADPOff1 / k_on = 2.7 / 11.6 = 0.232759 µM**  (≈ **3.4×** the Stage-1 fixed `C_c = kATPOff1/k_on = 0.0690`).

With the **same segment-granularity death-floor correction** as Stage 1 (a pointed segment born at `stdSegLength=32`
loses 30 monomers at rate `k_off` then dies en-masse returning the last 2 ⇒ effective off-rate ×32/30):
> **C_c_eff = (32/30)·C_c = 0.248276 µM**  [p_ss_eff ≈ 150 free monomers].

Computed from fixed model constants, NOT fitted. The measurement is adjudicated against `C_c_eff`.

## Gates (all PASS, GPU + CPU)
**A — aging kinetics (vs the analytic cascade) + CPU≡GPU.** A freshly-ATP segment held STATIC ages
ATP→ADP-Pi→ADP; the aggregate composition at t=1/3/5/10 s matches the analytic two-step ODE
(`f_ATP=e^{−kH t}`, `f_ADPPi=kH/(kD−kH)(e^{−kH t}−e^{−kD t})`, `f_ADP=1−…`) to **max 6.6e-5** (forward-Euler error
≪ 1%). Composition-sum after 5000 cadences = **1.0000000**. **CPU≡GPU aging** (10000 cadences) max|Δcomposition|
= **5.96e-8** (float32 last-bit).

**B — nucleotide-asymmetric C_c (predicted + measured).** Proxy-driven treadmill, n=6 seeds, 14τ, last 50%:
| total (mono) | [actin]_ss (µM) | vs C_c_eff | vs Stage-1 fixed C_c | L_ss vs predicted |
|---|---|---|---|---|
| 350 | 0.246928 | **0.5%** | 258% | 201 vs 200 |
| 500 | 0.253699 | 2.2% | 268% | 347 vs 350 |

Mean **0.250314 µM** vs **C_c_eff 0.248276** (**0.8%**); **invariant across totals** (spread 1.4%); clearly the
**kADPOff1** rate (≈3.4× the Stage-1 fixed C_c), NOT the ATP rate. The aging drives the pointed end to ADP ⇒ the
asymmetric off-rate ⇒ a NEW critical concentration, matched to first principles — **not tuned**.

**C — conservation EXACT.** 200000 aged cadences (grow took 570, turnover returned 543 monomers):
`Σnow == monInit + taken − returned` exact (integer ledger), sampled every 20000 cadences.

**D — CPU≡GPU full proxy pipeline (§8 aggregate-within-SEM).** 40000 cadences of the full 15-task device-resident
pipeline (age → depolyProxy → … → splitWire → splitInheritNuc): [actin] CPU=GPU=0.229160, L CPU=GPU=212, and in
fact **bit-identical lifecycle** at this horizon (the float32-last-bit nucFrac diff did not flip a depoly decision
over 40k cadences). The §8 aggregate-within-SEM standard is the *gate* (the proxy rate is a float32 feeding a
wang-hash decision ⇒ chaotic decorrelation is possible over longer horizons); bit-identicality here is a bonus.

**E — fixed-rate baseline preserved.** With depoly in FIXED mode but the aging kernels running, the lifecycle is
**bit-identical** to the Stage-1 path (30000 cadences: monomer/state/link mismatches 0, pool match) — the proxy
writes ONLY `nucFrac`, which the fixed-rate `depoly()` never reads.

## Lock-step / cadence discipline (new code only)
`pH`, `pD`, `pATP`, `pADP` are all **DERIVED** from `(rate, biochemDeltaT)` each cadence (`AgingStore.refresh` /
the fixed depoly-rate params), never stale copies. Aging shares the depoly biochem cadence (`fires`). No per-assay
`deltaT`/`biochemDeltaT` is assigned (read, not set).

## Race-freedom (no atomics / no KernelContext — `-cpu` safe)
`age` — per-active-slot self-write (locals read before write ⇒ order-independent). `growthAtp` — per-grown-tip
self-write. `splitInheritNuc` — per accepted split writes the child slot; distinct tips ⇒ distinct children. All
per-slot, no atomics.

## Notes / flags for the planner
- **The per-segment proxy averages the within-segment gradient (flagged).** Pointed depoly removes the OLDEST
  (most-ADP) terminal monomer, but the per-segment proxy carries no intra-segment gradient (that is the optional
  Stage-4 per-monomer fidelity), so depoly leaves the segment's composition fraction UNCHANGED. Negligible at
  steady state (pointed ≈100% ADP) and the consistent intensive-fraction choice. The proxy reproduced the
  aggregate cascade to 6.6e-5 (gate A) ⇒ the within-segment gradient does NOT matter at this granularity (the
  recon §2 resolution confirmed: a single per-segment scalar — here 3 for the visible cascade — suffices).
- **float32 sum-anchoring (a real, principled fix).** The naive forward-Euler `f_ADP += f_ADPPi·pD` drifted the
  per-segment sum to 1.0000181 over 5000 cadences (the `pD` terms cancel exactly in real arithmetic but are
  computed in two places in float32). Computing `f_ADP = 1 − f_ATP' − f_ADPPi'` is algebraically identical and
  anchors the sum to 1 each cadence (sum = 1.0000000). Identical physics, no accumulation.
- **C_c → C_c_eff offset (+6.7%) is the same death-floor granularity as Stage 1** — computed, NOT tuned. The
  measured 0.8% match is at `C_c_eff`.
- **Default-OFF overall** (no validated assay runs aging); `BoA-v1ref` byte-clean; production untouched. Stage-1
  depoly/growth regressions re-run PASS.
- **Next (Prompt B): cofilin severing** (the en-masse whole-segment dissolve off the ADP-ratio) to complete the
  watchable turnover system. The proxy's `f_ADP` (per-segment ADP-fraction) is exactly the input the dissolve
  threshold reads (recon §1e / §2).

## TL;DR
Filaments **age** — a per-segment 3-component nucleotide proxy `(f_ATP, f_ADPPi, f_ADP)` runs the ATP→ADP-Pi→ADP
cascade (matched to the analytic ODE to 6.6e-5) — and the aging **drives the pointed-end depoly rate**: at steady
state the pointed segment is ≈100% ADP ⇒ the off-rate is `kADPOff1`, giving a **new nucleotide-asymmetric
treadmilling `C_c = kADPOff1/k_on`** (granularity-corrected `C_c_eff = 0.2483 µM`, measured **0.2503, 0.8%**,
≈3.4× the Stage-1 fixed C_c, invariant across totals). Conservation EXACT; CPU≡GPU (aging 5.96e-8; full pipeline
bit-identical at 40k cadences / §8 aggregate-within-SEM); fixed-rate baseline bit-identical; the viewer shows the
ADP gradient. A NEW v2 representation faithful to v1's per-monomer aging **in aggregate** (§8). 5 gates PASS both
runners. **No default flip.**
