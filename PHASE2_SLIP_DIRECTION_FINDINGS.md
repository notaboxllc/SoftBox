# Phase-2 — does the catch-slip release post-stroke heads? (load-direction diagnosis + brake measurement)

**2026-06-29. MEASUREMENT-ONLY. `-brakediag` is additive / flag-gated / CPU-runner, default & every other path
BYTE-IDENTICAL; no physics edit, no retune, no default flip; `BoA-v1ref` byte-clean; nothing committed. Operating
point = the dense ATP-recharge run: `-perphead -headtilt 0 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -atprecharge`,
density 3000, matbed, dt=1e-5 (avgBound ≈ 15.2, the task's "binding-solved" point with netX only ≈ −0.46 µm/s).**

---

## TL;DR — the brake hypothesis is REFUTED; the deficit is NOT a catch-slip sign bug. BAIL on the fix.
- **Part A (code trace):** the catch load `forceDotFil` is **signed**, not a magnitude. It is the **J1 lever strain**
  `(κ/L)·(60° − θ)`, *not* the head's actual axial force on the filament. Convention (confirmed by the `-csrecal`
  calibration): **`forceDotFil > 0` = resisting → catch arm (held, lifetime rises to the +6 pN peak);
  `forceDotFil < 0` = assisting → the catch exponential `e^(−F·xCatch)` detonates → fast release.** ⇒ the bond is
  **already directional**, and an assisting-loaded head **already** routes to fast release. There is no sign to flip.
- **Part B (dense empirics) — TWO independent refutations:**
  1. **Held post-stroke heads do NOT brake.** Binned by time-since-stroke, **LONG-held (>3 ms) ADP heads ASSIST on
     average** (mean axial force on the filament **−0.059 pN**, − = glide/forward), they do not strain backward. The
     ensemble net per-bound-head force is **−0.031 pN (forward)**. The only mild *braking* bin is the brief FRESH
     (≤1 ms) post-stroke window (+0.045 pN) — tiny and transient, the opposite of a long-lived held brake.
  2. **Braking heads are NOT mis-scored into the catch.** Of all braking heads (Fx>0), **57% read SLIP (release)
     vs 43% read CATCH (held)** — braking heads read slip *more* often, not less. The 2×2 (axial-force-sign ×
     catch-load-sign) cross-tab is **statistically independent to ~1 pp** (observed 21.2/28.3/23.7/26.9 % vs
     independence-expected 22.2/27.3/22.7/27.9 %). **The catch load `forceDotFil` carries essentially no information
     about the real axial-force direction** — it cannot be "mis-scoring" a quantity it doesn't track.
  3. **Releases are not clustered at resisting load.** The signed load that fired each release is ~symmetric — **42 %
     resist-side / 58 % assist-side** (if anything assist-biased, i.e. the catch correctly sheds assisting heads
     faster). No "the catch holds the brakes, releases only at resist" signature.
- **Verdict = BAIL (both task bail-outs trigger):** the bond is already correctly signed (**bail-out 1**) AND there is
  no brake — held heads don't resist (**bail-out 2**). **Part C is NOT applied; Part D does not run.** The real lever
  is **elsewhere** — see "Where the deficit actually is."

---

## Part A — the bond's load input (read-only code trace)

**Where it's computed:** `CrossBridgeSystem.bondForcesCanonicalConfig1Perp` (the active flat-θ=0 / perphead path),
`GlidingHarness.java:376` → `CrossBridgeSystem.java:383`:
```
double forceDot = (kappa / leverM) * (-deflRad);   // deflRad = (θ − θ_rest)·DEG2RAD ;  resisting (θ<rest) ⇒ +
```
stored to `bondData[d+12]` and read into `forceDotFil`, which `catchSlipReleaseAvgRecharge`
(`NucleotideCycleSystem.java:421`) feeds **signed** into the Guo–Guilford rate:
```
F    = Favg + fExt            // signed
rate = kOff·(αCatch·e^(−F·xCatch/kT) + αSlip·e^(+F·xSlip/kT))
```

1. **Signed or magnitude?** **Signed.** `F = forceDotFil` (a time-averaged EMA of it, plus the `-fext` injection),
   never `|·|`. The catch term `e^(−F·xCatch/kT)` and the slip term `e^(+F·xSlip/kT)` respond to the *sign* of F.
2. **Which arm is which?** Lifetime `1/rate` is **minimised at F ≈ +6 pN** (the calibrated peak, `-csrecal` re-run
   below: 56.8 ms at +6.0 pN, rise→peak→fall). **F > 0 = resisting = the catch arm** (held up to the peak, then slip
   beyond). **F < 0 = assisting → the catch term `e^(−F·xCatch)` blows up → fast release.** `xCatch = 2.5 nm`,
   `xSlip = 0.40 nm`, αCatch/αSlip = 11.5, kOff = 100/s (the 4c Guo & Guilford calibration).
3. **Where does a post-stroke head land?** The code maps "θ above its 60° cocked rest" → F<0 → fast release, and "θ
   below rest" → F>0 → catch/held. **So per the code, an over-strained-forward (assisting) head already routes to the
   slip/fast-release side** — there is no sign inversion to correct. Whether real braking heads actually read F>0 is
   an empirical question Part B answers (they don't — see the cross-tab).
4. **Calibration-consistency:** the `-csrecal`/`-fext` calibration defines `F_ext > 0` as **resisting** sustained load
   and adds it to the *same* `forceDotFil` (1:1, same units) — so the code's sign convention **matches** Guo &
   Guilford's resisting-load axis. Re-running `-csrecal` reproduces the curve exactly (peak 56.8 ms @ +6.0 pN), so any
   hypothetical fix would only be allowed to touch the *assisting* (F<0) side — but no fix is warranted (see verdict).

**Flag (diagnostic mislabel, no physics impact):** `GlidingHarness.cyclediag` (line ~1805) prints `forceDotFil>0` as
"assist", the **opposite** of `CrossBridgeSystem`, `diagnose` (line ~1731 "positive (catch/load-resisting)"), and the
calibration. The `-brakediag` instrument and this report use the correct convention (>0 = resisting = catch). The
`cyclediag` label should be corrected (left untouched here to keep the change surface to the measurement instrument).

---

## Part B — the brake decomposition (the empirical "before"), dense operating point

`-brakediag` (CPU, measurement-only): per bound head, per post-warmup step, it reads the **actual axial seg-side force
the head puts on its filament** (`bondData[d+6]` = Fx; the matbed glides −x ⇒ Fx<0 ASSISTS, Fx>0 BRAKES) and the
**signed catch load `forceDotFil`**, binned by **time-since-stroke** (the ADPPi→ADP stamp), plus the
**release-events-vs-signed-load** histogram. (Raw: `RUN_LOGS/2026-06-29_brakediag_d3000.txt`; filament uVec_x = +0.997
⇒ axial ≈ Fx; 151 517 bound-steps over 10 000 post-warm steps ⇒ avgBound 15.2.)

### Force on the filament, by time-since-stroke
| bin | bound-steps | mean Fx (pN) | mean axial (pN) | mean forceDotFil (pN) | % Fd>0 (catch) | % brake (Fx>0) |
|---|---|---|---|---|---|---|
| PRE-stroke (ADPPi) | 29 925 | **+0.036** | +0.031 | −0.224 | 43.2 | 51.2 |
| FRESH (≤1 ms post-stroke) | 14 477 | **+0.045** | +0.046 | −0.083 | 46.3 | 51.5 |
| MID (1–3 ms) | 23 718 | **−0.067** | −0.067 | −0.150 | 43.8 | 48.6 |
| **LONG (>3 ms, the held state)** | **83 397** | **−0.059** | **−0.053** | −0.108 | 45.5 | 48.8 |
| **ensemble (per bound head)** | 151 517 | **−0.031** | — | — | — | — |

⇒ **the long-held post-stroke heads (the supposed brakes) ASSIST** (−0.059 pN). The net is forward (−0.031 pN/head).
The brake hypothesis predicts the opposite (long-held → backward/resist). **Refuted.**

### The mis-score cross-tab (all bound steps): axial-force sign × catch-load sign
| | CATCH (Fd>0, held) | SLIP (Fd<0, release) | marginal |
|---|---|---|---|
| **BRAKE (Fx>0)** | 21.2 % | 28.3 % | 49.5 % |
| **ASSIST (Fx<0)** | 23.7 % | 26.9 % | 50.5 % |
| **marginal** | 44.9 % | 55.1 % | |

- **Of braking heads: 57.2 % read SLIP, 42.8 % read CATCH** — braking heads are *more* likely to read the
  fast-release side, the opposite of a mis-score.
- **The table is statistically independent** (observed vs independence-expected within ~1 pp) ⇒ **`forceDotFil` (the
  lever strain) is decoupled from the real axial force** — the catch load and the transport force are nearly
  orthogonal signals. A directional fix on `forceDotFil` therefore cannot select braking heads.
- LONG-held only: 48.8 % of long-held steps brake (≈ balanced, not a brake majority), and of those only 44.2 % read
  catch.

### Release events vs the signed load that fired them (n = 175)
| strong-RESIST (>+1 pN) | mild-resist (0..+1) | mild-assist (−1..0) | strong-ASSIST (<−1 pN) |
|---|---|---|---|
| 9.1 % | 33.1 % | 36.0 % | 21.7 % |

⇒ releases span the whole load range, **slightly assist-biased (57.7 % assist-side)** — consistent with the catch
*correctly* shedding assisting heads a bit faster, **not** with "the catch holds the brakes." (Releases by
time-since-stroke track population, LONG 52 % ≈ its 55 % share of bound-steps — no anomaly.)

---

## Why there's no brake — and where the deficit actually is

Two facts make the brake mechanically impossible here, and reframe the deficit:

1. **The loads are an order of magnitude too small to matter.** The real per-head axial force is **~0.03–0.06 pN** and
   the catch load (lever strain) is **~0.1–0.2 pN** — both far below the **±1–6 pN** scale where transport happens and
   where the catch/slip arms differentiate (kT ≈ 4 pN·nm; the catch exponent at 0.1 pN is `0.1·2.5/4 ≈ 0.06` ⇒ ~6 %
   off the unloaded rate). So **the catch-slip operates near its unloaded floor (~100–140/s) for essentially every
   bound head, brake or assist** — release is effectively load-*insensitive* in this regime, so it cannot be selectively
   holding anything.
2. **The catch reads the wrong quantity, but harmlessly.** `forceDotFil` is the J1 **lever strain**, not the head's
   axial pin force; the cross-tab shows they're decoupled. This is a *design* feature of config1/perphead ("PAIRS
   pins, J1 reports"), already flagged in `PHASE2_CATCH_DCALIB` as "the external-load→J1-strain mechanical gain is the
   force-velocity benchmark, deferred." It is **not** a sign bug.

**The deficit is force-MAGNITUDE / duty / coherence, not release direction** — the same lever the prior work already
isolated:
- The articulated motor delivers **~0.05 pN axial per head** into the gliding ensemble, vs the old point-motor's
  ~pN-scale push at comparable occupancy (the task's ~17× per-head transport gap). At the (near-unloaded) gliding
  velocity each head sits where force→0 on its own force–velocity curve and the filament glides faster than the strain
  can build — expected force–velocity behaviour, low per-head force.
- This is consistent with `PHASE2_SPEED_DENSITY` (OUTCOME 2: KM ~470× too high, **duty/binding-density** the lever)
  and `PHASE2_POSTFIX_VEL_FORCEDECOMP` (the transverse:axial misprojection — heads spend force transversely). The
  recharge model raises *occupancy* (avgBound 15) without raising *per-head transport force*, so high avgBound +
  low netX is the honest signature of a force-coupling / duty deficit, **not** a brake.

---

## Verdict against the task's outcomes
- **Brake hypothesis: REFUTED.** Long-held post-stroke heads assist (not resist); braking heads read slip more than
  catch; the catch load is decoupled from the real axial force; releases are not resist-clustered.
- **Sign bug: NONE (bail-out 1).** The bond is already signed correctly — assisting load already routes to fast
  release; the resisting catch peak (+6 pN) is intact (`-csrecal` reproduced exactly).
- **⇒ Part C (the slip-direction fix) is NOT applied; Part D does not run.** Applying a directional fix would be a
  no-op at best (the sign is already correct) and a model change at worst (it can't select braking heads, which it
  doesn't track) — needs planner sign-off, not a faithfulness edit.

## For the planner — the real next levers (NOT actioned here)
1. **Per-head transport FORCE magnitude / duty** — the deficit is here. Revisit the kOn/duty calibration on the
   *current* ATP-recharge cycle (the `BINDING_SEARCH_SURVEY` stale-calibration flag) and/or the lever→filament
   force-coupling efficiency (the `PHASE2_POSTFIX_VEL_FORCEDECOMP` transverse:axial misprojection). avgBound is
   already "solved" — chase **per-head axial force × coherence**, not occupancy.
2. **The catch-input mechanical gain** (deferred in `PHASE2_CATCH_DCALIB`): the catch reads ~0.1 pN lever strain while
   the real loads are ~0.05 pN and the calibration scale is ±1–6 pN — the catch-slip is force-*insensitive* in the
   gliding regime. Whether the catch *should* read the actual axial cross-bridge load (the force-velocity benchmark)
   is a model decision for the planner, distinct from this faithfulness check.
3. **The separate search-radius / weak-binding-reach throughput lever** remains deferred (its own physical anchor), as
   the task specified — not bundled here.
4. **Trivial cleanup:** correct the `cyclediag` "assist(>0)/resist(<0)" label (it is backwards).

## CPU-fallback disclosure
`-brakediag` runs on the **`-cpu` sequential runner** only (a host-side measurement instrument; it reads `forceDotFil`
+ `bondData` after each CPU `step()`, exactly like `diagnose`/`cyclediag`). Dense run: 20 880 motors × 12 000 steps,
~6 min wall. No GPU/device path involved; no large/long device run. CPU≡GPU is not at issue (no new physics kernel —
the measurement reads the existing, already-CPU≡GPU-validated `forceDotFil`/`bondData`).

## What changed (additive; measurement-only; default & `BoA-v1ref` byte-unchanged; nothing committed)
- `GlidingHarness.java`: `-brakediag` flag + `brakeDiagnose()` + `binOf()` (the Part-B instrument). Gated behind the
  default-false `BRAKEDIAG`; every other mode/path byte-identical. No kernel, no force law, no rate, no default flipped.

## Reproduce
```
# Part B — dense brake decomposition (the operating point; avgBound ≈ 15.2, the verdict):
./run_gliding.sh -brakediag -perphead -headtilt 0 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -atprecharge -matbed -density 3000 -dt 1e-5 12000
# sparse cross-check (v1box):
./run_gliding.sh -brakediag -perphead -headtilt 0 -kon 2e5 -tauavg 0.5 -xcatch 2.5 -atprecharge -v1box -dt 1e-5 6000
# Part A — the preserved RESISTING-side catch curve (peak 56.8 ms @ +6.0 pN), the calibration any fix must keep:
./run_gliding.sh -csrecal -perphead -headtilt 0 -kon 2e5 -tauavg 0.5 -xcatch 2.5 14000
```
