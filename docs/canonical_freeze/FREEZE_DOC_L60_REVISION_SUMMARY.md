# FREEZE-DOC L60 REVISION SUMMARY

**Date 2026-07-23 · canon v2.** A **documentation and evidence-classification correction only** — incorporating the
completed L60 exposed-S2 sensitivity study into the canonical-freeze docs **conservatively and consistently**.
**No code, parameter, manifest, or canonical default was changed** (see §4). The core freeze decision is
preserved: L40 canonical, CONDITIONALLY FROZEN — GEOMETRY; L60 a declared alternative boundary-condition
sensitivity (not a competing/tuned baseline); EA/EI frozen; Outcome 1; canon v2 not reopened.

## 1. What changed (scope)
The revision (a) upgrades the **single-head** ensemble conclusions to DEMONSTRATED; (b) **downgrades the dimer arm**
to a qualitative stress test (Vmax/ρ½ UNRESOLVED / not freeze-grade); (c) adds the **numerical-completion vs
physical-admissibility** distinction for the L60 dimer branch excursions; (d) softens **mechanistic** buckling/
opposition language to beam-level-demonstrated + head-resolved-not-counted; (e) makes **rigor-mode provenance**
explicit; (f) flags the **L40-comparator fit provenance** discrepancy; (g)–(h) removes stale "open / no new run"
wording and reframes the open-list. Files edited: the 8 named docs + `S2_MD_PROVENANCE_CORRECTION.md`
(Part-K "all freeze documents" clause).

## 2. Before → after (substantive wording changes)

| # | Doc(s) | Before | After |
|---|---|---|---|
| A | single-head findings / comparison / S2 decision | ensemble conclusions "demonstrated robust" (both arms lumped) | **single-head DEMONSTRATED** (saturation, negligible Vmax +2.6 %, ρ½ +20 % modest geometry-dependent shift, continued recruitment); classified per item |
| B | dimer findings / comparison / inventory / classification / open-bio / closure | dimer slowdown + ρ½ + saturation "**SUPPORTED** (noise-limited)" | dimer slowdown/slower-than-single **QUALITATIVELY SUPPORTED**; ρ½ **SUGGESTIVE/DIRECTIONAL**; saturation **TENDENCY, not quantitatively demonstrated**; **Vmax/ρ½ UNRESOLVED / not freeze-grade** |
| B | dimer findings / comparison | fitted dimer Vmax ratio 1.76 / ρ½ ratio 5.84 (as results) | **explicitly FIT ARTIFACTS**, "must not be quoted as biological quantities" |
| C | dimer findings / comparison / S2 decision | branch excursions = "numerically stable … variance-noising" | **numerical completion (0 invalid/solver) ≠ physical admissibility**; maxGap ~3700 nm, peak branch force ~2.2e4 pN are **outside plausible HMM geometry/force**; solver recovered but velocities **physically contaminated**; the reduced dimer arm is a **qualitative stress test, not a definitive ensemble fit** |
| D | single-head findings / comparison | "L60 increases the buckled population / isolates compressive heads / reduces resisting force"; "higher peak loads = buckling signature" | "the **L60 beam mechanics predict a larger buckling-prone compressive population**"; "**consistent with** weaker transmission of compressive reaction forces"; "reduced internal opposition = **mechanistically-consistent inference**, head-resolved telemetry not collected"; **peak-load increase alone is NOT proof of buckling** |
| D | single-head findings / comparison | (implicit) beam + head effects lumped | split: beam-level **DEMONSTRATED** (k_ax/kComp/buckle/stroke); head-resolved taut/buckled/work/ATP **PREDICTED, NOT COUNTED** |
| E | single-head findings / comparison / S2 decision | "the mode-0 baseline is a valid reference" | explicit: **historical L40 baseline is mode 0; L60 sweep is mode 1; validated negligible non-reshaping mode difference permits its use without re-running; mode 1 is canonical, mode 0 is only the immutable historical baseline / legacy-disable — NOT canonical** |
| F | single-head findings / comparison | "L40 Vmax 4.77 / ρ½ 418" (as the canonical long-sweep fit) | **"L40 comparator dataset used in the declared L60 sensitivity"**; differs from the canonical summary 4.404/352 because the baseline was **partially regenerated** (4 densities re-run at an unstamped rev 2026-07-22); **REQUIRES PROVENANCE RECONCILIATION BEFORE PUBLICATION** |
| G | S2 decision | "Production gliding was run **only at L40** through canon v2 …" | "Before the declared L60 sensitivity study, production gliding had been run only at L40. **The completed L60 study now permits the following evidence reclassification** …" |
| G | model-freeze | banner "no new run required" / "optional §6.2 sensitivity" | "the declared L60 gliding sensitivity has been **RUN** … Outcome 1" (removed "no new run required") |
| H | model-freeze Q8 / open-bio | exposed S2 length listed as **open parameter (1)** | "**bounded geometry uncertainty, CONDITIONALLY FROZEN at L40, declared sensitivity COMPLETED**"; removed from the open-parameter list; remaining L60 work = **paper-strengthening follow-ups, not freeze blockers** |
| I | comparison | (various verdict wording) | the exact required final-verdict paragraph inserted verbatim |
| J | comparison / S2 decision | mixed labels | the required evidence-label table (DEMONSTRATED / QUALITATIVELY SUPPORTED / SUGGESTIVE / UNRESOLVED / PREDICTED-NOT-COUNTED / MECHANISTICALLY-CONSISTENT-INFERENCE) |

## 3. Unresolved provenance inconsistencies (flagged, NOT silently resolved)

1. **L40 comparator fit 4.77/418 vs canonical summary 4.404/352 — REQUIRES PROVENANCE RECONCILIATION BEFORE
   PUBLICATION.** Both are the *same analysis script* (`single_head_density_analysis.py`, unchanged since rev
   51f0add) on the *same* `single_head_density_sweep_long` directory — but the current directory is a
   **mixed-revision dataset**: 36 cells at rev `73d82bf` (9 densities, 2026-07-21) + **16 cells re-run at an
   UNSTAMPED rev (`code_rev="unknown"`) on 2026-07-22** at densities {250, 700, 1500, 3000}, with higher
   high-density velocities (ρ3000 3.91→4.23) that shift the extrapolated Vmax/ρ½ up. **The exact code/config change
   behind the 2026-07-22 partial regeneration is not determinable from the metadata.** Downstream consequence:
   `CANONICAL_PARAMETER_MANIFEST.json` and `CLAUDE.md` still quote the pre-regeneration **4.404/352**, while the L60
   comparison uses **4.77/418** — a live documentation inconsistency left unresolved per the "do not change the
   manifest" constraint. (The L60/L40 *ratios* are internally self-consistent, so Outcome 1 is unaffected.)
2. **Baseline rupture-mode is attributed by era, not by stamp** — the historical L40 cells lack a `rupture_mode`
   field (mode-0-era); the mode-0 attribution rests on their pre-canon-v2 provenance, not an explicit value.
3. **Dimer L60 Vmax/ρ½ are unresolved** (not a doc inconsistency but an evidence gap): the reduced 4×2×10k CPU grid
   + physically-inadmissible branch excursions make the dimer fit non-freeze-grade; a definitive value needs a
   physically-admissible run (substep / NDOF=25 GPU kernel) + more seeds.

## 4. Confirmation — no parameter / code / manifest change

**This revision changed documentation prose only.** Verified:
- No `softbox/*.java` change (no code, no parameter, no canonical default).
- No change to `CANONICAL_PARAMETER_MANIFEST.json` or `L60_CANONICAL_SENSITIVITY_MANIFEST.json` (the canonical L40
  default `S2_length_L=40` and all frozen values are untouched).
- No change to the evidence data (`L60_DENSITY_FITS.csv`, `L60_GLIDING_CELLS.csv`) or the raw run logs — the dimer
  fit values remain as computed; only their **interpretation/classification** in the prose was corrected.
- L40 remains canonical, CONDITIONALLY FROZEN — GEOMETRY; L60 remains a declared sensitivity; EA/EI frozen; canon
  v2 not reopened; Outcome 1 preserved.
