# Experiment 3G-B — Realistic-only optical-tweezers holdout

**Date:** 2026-07-14 · **Branch:** `dt-convergence-study` · **Runner:** CPU-only (non-canonical,
default-off `-exp3gb`). **Canonical motor, production defaults, and `BoA-v1ref` untouched.**

This is the compact follow-up recommended by the 3G-A synthesis: a **small, sealed, realistic-only
holdout with no ideal twins available to the analyst**, using a **better stiffness-perturbation
protocol**, analysed once by a **frozen** strategy. It answers one question: *does the
already-developed analysis recover the compliance-free step, the pre/post whole-crossbridge
stiffness, and the stroke polarity **without** the noise-free ideal traces that assisted 3G-A?*

---

## 1. Design and discipline

**The analysis was frozen BEFORE any holdout trace was generated.** The 3G-A independent-analyst
pipeline was copied verbatim into `scripts/frozen_tweezers_holdout_analysis/`; the **only** edits
removed the ideal-twin code paths (every threshold, filter, exclusion, compliance equation,
calibration method, and primary estimator preserved). Specification: `FROZEN_ANALYSIS_SPEC.md`;
hashes: `FROZEN_ANALYSIS_SHA256.txt`. Where an ideal-free decision rule was needed (the pre/post
stiffness-difference verdict), the analyst's own *ideal-free* criterion was transcribed (the
~0.15 pN/nm systematic resolution of the ill-conditioned variance inversion), not re-invented.

**Holdout generation** (`-exp3gb`, fresh seeds/IDs, restart bit-identical): a dual-trap actin
dumbbell driven by the validated 3C–3F two-body motor and native Pi-release stroke. Instrument-
realistic traces **only** — the package contains **no ideal traces**; truth is computed on the
noise-free deterministic dumbbell (`extStep`/`extXbStiffness`), never saved as an analyst-readable
trace. **75 stroke events + 18 bound-no-release + 27 controls (12 no-motor / 6 motor-present /
9 perturbation-calibration) + 3 calibration recordings.** Three trap stiffnesses 0.02/0.05/0.10
pN/nm (A/B/C); five preloads {−1,−0.5,0,+0.5,+1} pN (assisting/near-zero/opposing).

**Improved perturbation protocol.** Replacing 3G-A's short (unreached-plateau) pulses: **common-mode
trap steps held ≥ 5.5 detached relaxation times** per level (2297/919/460 steps ≈ 23/9.2/4.6 ms),
amplitudes chosen by **force** (comparable across stiffness), two small amplitudes + a sign flip
(linearity). A design iteration was required and is itself a finding (§4): the held steps must be
**separated from the working stroke by a long quiet stable dwell**, because the frozen stroke
detector (tuned on 3G-A's *short spikes*) otherwise latches onto the held-step relaxation tails.

**Blinding + independence.** The sealed package was handed to a **separate Claude Code session** with
no access to the source, the generator, the private truth, or any prior report. Independence was
**verified after the fact by a transcript census** (`private_truth/census_blind_session.py`): the
session's tool calls touched only files inside the handoff directory — **CLEAN**. The frozen
pipeline was re-verified **unmodified** (12/12 hashes) and all 142 sealed files intact, both before
and after the run.

---

## 2. Result — scorecard vs private truth

Stiffness is scored against the **external whole-crossbridge k_xb** (pre 0.643, post 0.625 pN/nm),
**not** the assigned F8 spring (1.0 pN/nm). "CI covers" = the analyst 95% interval contains truth.

| quantity | truth | blind estimate | error | CI covers |
|---|---|---|---|---|
| trap k — A | 0.0200 | 0.0167 | **−16.5 %** | (SE 0.004; A is 21 % below *supplied*, beyond ±10 %) |
| trap k — B | 0.0500 | 0.0491 | −1.8 % | ✓ |
| trap k — C | 0.1000 | 0.1037 | +3.7 % | ✓ |
| **zero-load step** | **7.85** (comp.-free) / 7.52 (level-A) | **7.00 nm [6.53, 7.44]** | **−10.9 %** / −6.9 % | **✗** (CI narrowly misses) |
| **pre-stroke stiffness** | 0.643 | **0.577 [0.221, 0.934]** | −10.2 % | **✓** |
| **post-stroke stiffness** | 0.625 | **0.651 [0.595, 0.708]** | +4.2 % | **✓** |
| **pre/post difference** | +0.018 (2.9 %) | −0.062, judged **NOT identifiable** | — | correct call |
| **polarity** | pointed-first | **pointed-first** (55/59 neg.) | — | **MATCH** |

**Event detection:** 58/75 true strokes detected (77 %), 17 missed (23 %, almost all level C:
detection 81 %/81 %/29 % at A/B/C), 0 undetermined; **1 false stroke** on a bound-no-release trace.
**Control false positives:** the frozen detector reports **5/21 = 0.238** — all **false
*attachments*** on perturbation-calibration controls, **0 false strokes** on any control.

### Point estimates land near truth; the analyst (correctly) trusts them unevenly

- **Step and polarity are robustly recovered.** d₀ = 7.00 nm moves ±0.04 nm under the full ±10 %
  calibration and 0.6 nm over a 14× k_post sweep — the analyst defends it "without reservation." It
  is ~7–11 % low (see §3) and its realistic-only CI is narrow enough to *just* miss the compliance-
  free 7.85 nm. Polarity is a 55:4 sign, invariant to calibration/compliance/filtering: **the motor
  pulls the filament toward its pointed end, translocating toward the barbed end.**
- **Stiffness point estimates are within CI of truth** (pre −10 %, post +4 %), **but the blind
  analyst refused to endorse the pipeline's unqualified "identifiable"** — and was right to (§3).

---

## 3. What the holdout exposed (the real findings)

The independent analyst ran the frozen method unchanged and then **diagnosed** (read-only) two
genuine frozen-pipeline failures and one instrument-model gap. These are the substance of the
experiment:

1. **Held-step perturbations break the frozen attachment detector (5 false attachments).** The
   detector's 6 ms variance window and 0.6 ms mask-tail were frozen against 3G-A's *short* pulses;
   the 3G-B *held* steps (23/9.2/4.6 ms) get fully masked, `masked_rolling_var` falls below its
   `nmin` and interpolates a smooth low value across the gap → an "attachment" on a no-motor control.
   The `f_att = 0.30` "zero-false-positive" operating point **does not transfer** to the improved
   protocol. It does not contaminate the primary estimates (those use settled samples only), but the
   honest control false-positive rate is 24 %, not 0.

2. **The systematic-floor (E0) stage silently fails at the primary trap level.** `km_from_var`
   returns **NaN for 11 of 21 controls** — including all level-A and level-B no-motor controls —
   because the *measured* detached chunk variance exceeds the forward model's ceiling (15.5 vs
   11.8 nm² at level A: the model under-predicts realistic detached variance by ~30 %). The adopted
   floor (0.043 pN/nm) is therefore a `nanmedian` over only the level-C controls that inverted; where
   the estimator *can* be checked against a known zero (level C, no motor) it reads **−0.14 pN/nm** —
   3× the adopted floor and 22 % of the reported k_post. So the k_post ±0.06 CI is a statistical
   interval around an estimator whose **systematic is unmeasured at the level where it is applied.**

3. **The improved perturbation did NOT rescue stiffness inference — it is below thermal SNR here.**
   Force-matched amplitudes give *displacement* amplitudes 1.5/0.6/0.3 nm at A/B/C against a detached
   thermal sd of 11.1/6.5/4.4 nm (per-step SNR ≈ 0.1). The perturbation-response estimator (E2) is
   non-functional (returns 0.07–0.16 pN/nm and NaN on the calibration controls), and the bare-dumbbell
   unit-gain check collapses (fitted gains 2.8 ± 3.1, 17 ± 13, −21 ± 22 where physics requires 1.0).
   This is intrinsic to km ≫ 2k, not a new defect — but it means the stiffness rests entirely on the
   **variance (E1) and mean-shift (E3)** estimators, which need no perturbation, and the improved
   protocol's contribution to *stiffness* is essentially nil while it *created* failure mode (1).

4. **Frozen prose ≠ live numbers.** A few narrative fields in the emitted JSON are 3G-A boilerplate
   whose claims the holdout's own numbers contradict (e.g. `consistent_with_supplied: true` where
   level A is 21 % off; "three estimators agree / level-independent" where they span 4–10×). The
   analyst correctly trusted the **numeric** fields and flagged the prose. (Freeze imperfection: some
   `method`/`notes` strings in `08_results.py` were left dataset-agnostic-but-not-recomputed; the
   scored quantities are unaffected.)

---

## 4. Did removing the ideal twins matter? (the primary question)

**On the step and polarity: no.** These are DC observables — a rigid-body displacement and its sign —
measured on level-A traces where the compliance correction is ~6 %. They need no noise-free
reference; the analyst "would have quoted the same number with twins available."

**On the stiffness: yes, decisively — and in a way the CI does not show.** The single biggest loss is
the **validation of the variance forward model.** With ideal twins, the 30 % model-vs-realistic
detached-variance discrepancy (finding 2) would have been *immediately visible* as a twin-vs-realistic
mismatch; without them it surfaces only as silent NaNs, a `RuntimeWarning`, and a systematic floor
computed from whichever controls happened to invert. In the analyst's words: *without ideal twins the
pipeline reports a post-stroke stiffness whose systematic error it cannot measure at the level where
it is reported, and does so without flagging the failure.* **A twin-free package leaves the DC
observables (step, polarity) intact and quietly guts the variance-based ones (stiffness) — which is
exactly what this holdout was built to expose.**

That the stiffness point estimates nonetheless landed within their CIs of truth (pre −10 %, post +4 %)
is a property of the robustness of E1/E3, **not** something the twin-free pipeline could have *proven*.

---

## 5. Decision

Against the pre-registered outcome buckets, the holdout lands on a **composite**, closest to
*"step survives, stiffness becomes weakly identifiable"*:

- **The main 3G-A single-molecule conclusions survive without ideal twins** — compliance-free step
  ≈ 7 nm (recovered, ~10 % low, robust), whole-crossbridge stiffness ≈ 0.6 pN/nm (both dwells,
  within CI of the k_xb truth, and correctly *not* confused with the 1.0 pN/nm F8 spring),
  pointed-first polarity, and the tiny pre/post stiffness difference correctly judged non-identifiable.
- **BUT the twin-free stiffness pipeline hides a real, unmeasured systematic** (the E0 forward-model
  failure), so post-stroke stiffness is *weakly* identifiable at best, and its stated ±0.06 CI is not
  trustworthy. The step and polarity are unaffected.
- **The improved held-step perturbation did not rescue stiffness** (sub-thermal SNR at km ≫ 2k) and
  **introduced a control false-positive failure** in the frozen detector.

**Recommendation.** Do not treat the frozen pipeline as production-ready for twin-free stiffness. The
two concrete, named failures — (a) the held-step/attachment-detector mask interaction, and (b) the
E0 variance-model ceiling that NaNs at the soft traps — are **specific and fixable**, but fixing them
is a method change and therefore belongs to a **newly-blinded future test** (with a co-designed
detector for held-step perturbations and a drift-aware variance model). Per the 3G-A synthesis, the
full publication-grade digital twin should still wait until the motor has a **closed stochastic
biochemical cycle** (ADP release, ATP detachment, recovery, duty ratio) — at which point the
frequency-domain stiffness protocol can be co-designed rather than bolted onto a frozen detector.

**No canonical or model change is implied.** The two-body motor's externally observable step,
stiffness, and polarity remain independently recoverable from realistic tweezers observables alone;
the holdout's value is the precise map of *what the ideal twins had been silently underwriting.*

---

## 6. Reproduction

```
# regenerate the sealed realistic-only holdout (restart bit-identical)
./scripts/build.sh
./scripts/run_lasertrap.sh -exp3gb -out RUN_LOGS/twobody_blind_tweezers_holdout
( cd RUN_LOGS/twobody_blind_tweezers_holdout/blind_package && sha256sum -c MANIFEST_SHA256.txt )

# the frozen realistic-only analysis pipeline (ships inside the blind package)
scripts/frozen_tweezers_holdout_analysis/  ·  FROZEN_ANALYSIS_SPEC.md  ·  FROZEN_ANALYSIS_SHA256.txt
#   cd <package>/analysis && ./run_frozen.sh   ->  analyst_results_holdout.json

# census an independent analyst session (verify it read nothing outside the package)
python3 RUN_LOGS/twobody_blind_tweezers_holdout/private_truth/census_blind_session.py [transcript.jsonl]

# score a frozen analyst result against the private (externally-observable) truth
python3 RUN_LOGS/twobody_blind_tweezers_holdout/private_truth/unblind_compare_holdout.py \
        <analyst_results_holdout.json>
```

**Artifacts.** Sealed package + private truth: `RUN_LOGS/twobody_blind_tweezers_holdout/`. Frozen
analysis (canonical copy): `scripts/frozen_tweezers_holdout_analysis/`. Independent analyst run
(results, `REPORT_HOLDOUT.md`, diagnostics, output hashes): `~/Desktop/twobody_holdout_review_3gb/`.
Predecessor: `docs/TWOBODY_BLIND_TWEEZERS_GENERATION.md` (3G-A), `docs/TWOBODY_BLIND_TWEEZERS_SYNTHESIS.md`.
