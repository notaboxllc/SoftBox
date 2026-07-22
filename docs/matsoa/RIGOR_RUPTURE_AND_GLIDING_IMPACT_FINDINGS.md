# Rigor mechanical-rupture pathway + gliding-impact assessment

**Date:** 2026-07-22 · **Default:** OFF (no production default changed) · **Gliding impact: NEGLIGIBLE**
(seed-expansion complete; pooled resolved-density effect +0.33 % ± 1.56 %, |t|=0.21) · **Blind SM4 study
MAY PROCEED.**

This document is the master record for the flag-gated, force-dependent rigor-rupture pathway: the exact
code change, the force law, the fitted parameters, the literature target, the default-off regression, the
isolated rigor assay, the gliding ON/OFF comparison, the single-head-vs-dimer sensitivity, the decision
classification, and the blind-study-readiness verdict.

---

## 1. Exact code change

| file | change |
|---|---|
| `softbox/MotorStore.java` | new **`rigorParams`** (FloatArray[12], default 0 ⇒ OFF) + **`ruptureStats`** (IntArray[2·N]); `setRigorRupture(...)` / `disableRigorRupture()`. Params stored **wholly separate** from the ADP `kinParams`. |
| `softbox/NucleotideCycleSystem.java` | new kernel **`cycleLymnTaylorRigor`** = byte-copy of `cycleLymnTaylor` + the rigor pathway. **`cycleLymnTaylor` is UNTOUCHED.** |
| `softbox/Sm4ForceLifetimeHarness.java` | `-rigor-rupture` / `-atpfree` / `-rigor-model` / `-rk0 -raC -rxC -raS -rxS`; per-event cause accounting (`detach_cause`, `t_rigor_rupture_s`, `force_at_rupture_pn`, `rate_cap_warnings`). |
| `softbox/ExplicitCompleteMatHarness.java` | single-head gliding: chem-kernel dispatch (CPU `stepGlidingCPU` + GPU `buildGlidingGraph` task) + `rigorParams`/`ruptureStats` device transfer + per-motor rupture/force-at-rupture readback + JSON fields. |
| `softbox/ExplicitHmmDimerGlidingHarness.java` | dimer gliding (CPU): chem dispatch + rupture cause counter (`RIGORROW` output). Distinct from the pre-existing `-ruptureMode` **HMM-dimer strain failsafe**. |
| `scripts/` | `sm4_rigor_fit.py`, `rigor_gliding_analysis.py`, `rigor_gliding_plots.py`, drivers `run_rigor_gliding_{singlehead,dimer,expand}.sh`; `sm4_analysis.py` extended with the new event fields. |

**The new pathway (only when `-rigor-rupture` is on, only for a BOUND `NUC_NONE` head):**
```
bound rigor (NUC_NONE) --k_rigor(F)--> detached (mechanical rupture)
```
It does **not** touch `atpOn`, the ADP→NONE catch-slip, or any bound/unbound state other than rigor.

## 2. Force-law choice

Two-pathway catch-slip (model 0; default). `F` = **realized instantaneous** axial bond load `forceDotFil`
(never the requested clamp). Sign: **+opposing/barbed = the catch side**.
```
k_rigor(F) = k0 · [ aCatch·exp(−F·xCatch/kT) + aSlip·exp(+F·xSlip/kT) ]      g(0)=aCatch+aSlip=1
```
A one-path Bell (`model 1`: `k0·exp(+F·xSlip/kT)`) is supported for comparison but never substituted; the
isolated assay decisively prefers the two-pathway form (ΔAIC ≥ 461).

**Competing hazards / update order (no event-order bias, no new RNG draw).** ATP binding (`atpOn`) and
rupture (`k_rigor`) compete via a **single-uniform partition** of the cycle's existing draw `u`:
`[0,pAtp)`→ATP (identical to the flag-off band), `[pAtp,pAtp+pRig)`→rupture, with `pAtp=atpOn·dt`,
`pRig=k_rigor·dt`. Exact rate-proportional probabilities, no tie-break, **zero new RNG draws**. Steps with
`pAtp+pRig` above `rigorParams[9]=0.2` are flagged in `ruptureStats[2m+1]` (substep/abort signal), never
silently clipped. Observed `rate_cap_warnings = 0` everywhere.

## 3. Fitted rigor parameters + literature target

**Literature target — Guo & Guilford 2006 (PNAS 103:26).** Rat skeletal HMM; **rigor = nucleotide-free
(ATP-free)**; room temp (~20–25 °C); 25 mM KCl/25 mM imidazole/1 mM EGTA/4 mM MgCl₂, pH 7.4; laser trap,
step + ramp loads; **loading perpendicular to actin**; quantity = bond lifetime. Table-2 rigor Bell fit →
mapped target **k0=140/s, aCatch=0.9071 (xCatch=1.5 nm), aSlip=0.0929 (xSlip=0.5 nm), f_crit≈7.1 pN**.
Ambiguities flagged (blocker rule): (a) Fig-4A digitized lifetimes (~s) disagree with the Table-2 Bell fit
(~7 ms) — anchored on the authors' fit; (b) their load is **perpendicular**, ours is the **axial**
projection ⇒ a force-law-**shape** match is claimed, not identical absolute lifetimes.

**Recovered (isolated ATP-free assay, ADP params held fixed; n=200/cell, both fixtures):**

| fixture | k0 (/s) | xCatch (nm) | xSlip (nm) | peak (pN) | χ²/dof | ΔAIC vs Bell |
|---|---:|---:|---:|---:|---:|---:|
| target (G&G) | 140 | 1.50 | 0.50 | 6.99 | — | — |
| fixed-anchor | 146.2 ± 11.0 | 1.63 ± 0.16 | 0.51 ± 0.03 | 6.88 | 0.05 | 461 |
| explicit-s2-l40 | 172.5 ± 12.7 | 1.54 ± 0.14 | 0.55 ± 0.03 | 7.44 | 0.20 | 551 |

`xCatch` recovered ≈ exactly; peak brackets 7.1 pN; two-pathway decisively preferred. The k0 upward bias is
Jensen thermal convexity (Brownian-OFF → k0=145; larger for the compliant beam). Full detail:
`docs/matsoa/SM4_RIGOR_MECHANICAL_RUPTURE_FINDINGS.md`.

**Frozen production parameters for the pathway** = the mapped Guo & Guilford Table-2 values
(k0=140, aCatch=0.9071, xCatch=1.5 nm, aSlip=0.0929, xSlip=0.5 nm), the harness defaults.

## 4. Default-OFF regression (flag OFF) — ALL PASS

- **SM4 byte-identity:** 280 flag-off events (rigor+adp × forces × dirs) hash-identical to the pre-change
  build on every physics-bearing field. Only additive JSON fields differ.
- **Deterministic chemistry / motor regression:** `-motor-regression` Gates A–F **max|Δ|=0** (common core,
  registry≡frozen builders, serialize/restart, live stroke).
- **SM4 ADP cells (0, 6, 10 pN):** with the flag **ON at saturating ATP**, the ADP→NONE waiting time is
  **bit-identical** to OFF; total ADP lifetime matches to ≤0.002 % (a 0.5 % rupture-channel leakage at 10 pN
  — the expected competing hazard, within SEM). ADP catch-slip unchanged.
- **SM6 force-extension:** structurally independent (0 chemistry references); runs clean (1341 points);
  byte-identity follows a fortiori from the SM4 flag-off proof + additive-only MotorStore change.
- **No new detachment causes / no RNG drift when OFF:** `ruptureStats` written only by the rigor kernel;
  the partition adds no RNG draw.

**Flag ON:** rigor lifetime becomes force-dependent (recovers the fitted law); zero-load ≈ target; ADP
unchanged; SM6 unchanged; **0 invalid states, 0 solver failures, 0 rate-cap warnings** (no phantom
detachments).

## 5. Isolated rigor assay (Part A)

Catch-slip force-lifetime recovered on both fixtures; lifetime peaks at realized ≈ 7.5 pN (≈ Guo & Guilford
f_crit 7.1 pN) then falls. Deliverables per fixture dir: `event_table.csv`, `survival_summary.csv`,
`kaplan_meier.csv`, `hazard.csv`, `detachment_cause_summary.csv`, `fit_results.json`,
`parameter_covariance.csv`. Timestep sensitivity (half-dt: rate dt-invariant) and thermal sensitivity
(Brownian-OFF isolates the Jensen k0 inflation) both done.

## 6. Gliding impact (Part C) — paired ON/OFF

**Key mechanistic facts (both architectures share the chemistry kernel):**
- At **saturating ATP** (gliding), rigor rupture is a **rare competing channel**: a bound head spends only
  ~50 µs in `NUC_NONE` before ATP binding detaches it (nuc-NONE occupancy ≈ 2e-4), so rupture (k≈140/s,
  ~7 ms) fires only in the rare tail — **<2 % of all detachments** (3–43 events per 40 000-step cell).
- **When no rupture fires, ON ≡ OFF bit-identical** (no new RNG draw; identical ATP band). Confirmed at
  small scale (0 ruptures → identical velocity + bound heads).
- Force-at-rupture is ≈ 0 pN: ruptures occur on **low-load** post-stroke NONE heads (spent heads).

**Single-head (explicit-s2-l40, GPU device-resident), FULL matrix — 24 paired cells (2 seeds at d250; up
to 10 seeds at the resolved densities after the expansion). Paired ON−OFF (same seed):**

| density | n seeds | mean bound | paired %Δv | ±SE | \|t\| | significant? | OFF chaos env. | within? | rupture frac |
|---:|---:|---:|---:|---:|---:|:--:|---:|:--:|---:|
| 250 | 2 | 1.7 | −12.9 | 17.4 | 0.74 | no | 17.6 % | yes | 0.021 |
| 700 | 6 | 5.2 | −2.5 | 4.9 | 0.50 | no | 7.3 % | yes | 0.016 |
| 1500 | 10 | 10.9 | +1.2 | 1.5 | 0.78 | no | 3.9 % | yes | 0.019 |
| 3000 | 6 | 21.1 | +1.7 | 2.1 | 0.80 | no | 3.7 % | yes | 0.015 |

**Pooled over the resolved densities (mean bound > 4 heads, n = 22 paired seeds): +0.33 % ± 1.56 %
(|t| = 0.21)** — the systematic velocity effect is **statistically indistinguishable from zero**. No density
is significant (all |t| < 1); every paired delta lies **within the flag-OFF chaotic envelope**; bound-head
change ≤ 1.8 %; rupture fraction ≤ 2.1 % (only exceeds 2 % at d250, where the total detachment count is
tiny). The gliding steady state is **chaotic and bistable** (CLAUDE.md GPU-number-trust rule): flag-OFF
seeds scatter ±(3.7–28) % and per-seed ON/OFF deltas swing −30…+8.5 %, but these average to zero.

> **The seed expansion was decisive.** An initial 2-seed matrix showed a *spurious* d1500 signal (+4.8 %,
> |t|≈9 — both seeds coincidentally landed +4.3/+5.4 %). Expanding d1500 to 10 seeds regressed it to
> +1.2 % ± 1.5 % (|t|=0.78). The hypothesized "rupture clears spent-head drag" mechanism is therefore
> **below the chaotic noise floor (< 2 %)**, not a resolvable systematic effect.

**Dimer (explicit HMM dimer, CPU-only = the deterministic basin arbiter):** reduced paired ON/OFF set —
velocity deltas **< 0.06 %** (d250 s101: **exactly 0.000 %**, because 0 ruptures fired ⇒ ON ≡ OFF
bit-identical, as designed; d250 s102 +0.06 %; d700 s101 +0.02 %). The dimer's per-cell `rupture_frac` uses
a bind-count proxy denominator that is unreliable at low counts (2 ruptures ÷ a small proxy → an inflated
0.14 that is a denominator artifact, **not** a 14 % rupture rate); the **velocity** is the clean metric and
it is essentially zero. The deterministic arbiter thus **corroborates** the GPU single-head verdict.

Metrics captured per cell (both architectures): velocity, mean bound heads, attachment lifetime, nucleotide
occupancy, time in NUC_NONE, ATP turnover, velocity/bound-head, rigor-rupture count, rupture fraction,
force-at-rupture, max bond load, invalid states, solver failures. Plots (velocity ON-vs-OFF, %Δv vs density,
bound change, rupture fraction, force-at-rupture, single-head-vs-dimer): `rigor_gliding_singlehead/plots/`.

## 7. Decision classification (Part D) — **NEGLIGIBLE**

- Absolute (pooled, resolved-density) velocity change **+0.33 % ± 1.56 %, |t|=0.21** (< 2 %, not
  significant). ✅
- **No systematic density trend** (paired deltas scatter around zero; every density |t| < 1). ✅
- **Rare** rigor rupture (≤ 2.1 %, at ~0 pN low load; a bound head is in NUC_NONE only ~50 µs before ATP
  binding). ✅
- **No meaningful bound-head change** (≤ 1.8 %). ✅

All four Negligible criteria are met. **Action (per the Part-D playbook): proceed to the blinded SM4 study;
retain the existing gliding results with this documented sensitivity check.** The pathway is default-OFF and
changes no production default; the sensitivity check shows that enabling it does not perturb gliding beyond
the intrinsic chaotic envelope.

## 8. Blind-study readiness (Part E) checklist

| requirement | status |
|---|---|
| rigor pathway parameters frozen | ✅ Guo & Guilford Table-2 mapping, harness defaults |
| default-off regression passes | ✅ byte-identical SM4; Gates A–F max|Δ|=0; SM6 unchanged |
| ADP assay unchanged | ✅ ADP→NONE bit-identical flag ON vs OFF |
| detachment-cause accounting validated | ✅ distinct `rigor_rupture` cause; 100 % in ATP-free; <2 % at saturating ATP |
| experimental protocol documented | ✅ Guo & Guilford conditions + ambiguities (§3) |
| analysis pipeline runs without planted params | ✅ `sm4_rigor_fit.py` recovers the law from data alone |
| **gliding impact classified** | ✅ **NEGLIGIBLE** (pooled +0.33 % ± 1.56 %, |t|=0.21; both architectures) |

**Verdict: the blinded SM4 force study MAY PROCEED.** Every readiness criterion is met: parameters frozen,
default-off regression byte-identical, ADP assay unchanged, cause accounting validated, protocol documented,
analysis pipeline recovers the law from data alone, and the gliding impact is classified **Negligible** on
both the GPU single-head path (pooled +0.33 % ± 1.56 %, not significant) and the deterministic CPU dimer
arbiter (< 0.06 %). The rigor pathway stays **default OFF**; it changes no production default and does not
perturb gliding beyond the intrinsic chaotic/bistable envelope.

## 9. Reproduce
```
./scripts/build.sh
# isolated rigor validation
java … softbox.Sm4ForceLifetimeHarness -motor fixed-anchor -rigor-rupture -atpfree \
     -states rigor -forces 0,1,2,3,4,5,6,8,10,15,20,25 -dirs opposing,assisting -events 200 -maxdwell 300 \
     -outdir RUN_LOGS/motor_validation/sm4_rigor_fixedanchor
python3 scripts/sm4_analysis.py  RUN_LOGS/motor_validation/sm4_rigor_fixedanchor
python3 scripts/sm4_rigor_fit.py RUN_LOGS/motor_validation/sm4_rigor_fixedanchor
# gliding impact
./scripts/run_rigor_gliding_singlehead.sh          # GPU single-head, densities 250/700/1500/3000 × seeds × ON/OFF
CLASSES=<scratch> ./scripts/run_rigor_gliding_dimer.sh   # CPU dimer (arbiter), reduced set
python3 scripts/rigor_gliding_analysis.py RUN_LOGS/motor_validation/rigor_gliding_singlehead single-head
python3 scripts/rigor_gliding_plots.py    RUN_LOGS/motor_validation/rigor_gliding_singlehead RUN_LOGS/motor_validation/rigor_gliding_dimer
```
