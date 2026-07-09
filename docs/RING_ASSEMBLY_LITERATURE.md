# Contractile-Ring Assembly — Literature & the Clump-vs-Ring Problem
### A SoftBox reference for the ring phase

**Purpose.** A standing reference to resume the contractile-ring thread once SoftBox's machinery (turnover +
crosslinkers + a cortical-confinement geometry) is assembled. It captures the fission-yeast SCPR literature, the
quantitative ring-vs-clump criterion, the mechanisms that decide the outcome, and how each maps onto what SoftBox
has already built.

**Context that motivates it.** An earlier BoA ring exploration produced *robust* node coalescence to the division
plane but **surface clumping** — a localized cortical cluster — rather than an even circumferential ring, on a
correctly-sized, shell-confined 3D pill-shaped cortex. So confinement was present and was *not* the missing piece;
the failure was the canonical SCPR clump. This document is the literature answer to "tuning or missing mechanism?"

---

## 1. The system (fission-yeast SCPR)
- **Nodes:** membrane-bound protein complexes anchored to the cortex by Mid1/anillin (~65–200 nodes), forming a
  broad **equatorial band** (~2–3 µm wide).
- **SCPR — Search, Capture, Pull, Release** (Vavylonis et al. 2008): node-bound formin **Cdc12** (processive at
  the barbed end) nucleates randomly-oriented "searching" filaments; **myosin-II (Myo2)** in a *neighboring* node
  captures a filament (capture radius ~100 nm) and walks toward its barbed end, **pulling** the nodes together;
  the connection then **releases**. Repeated cycles condense the band into a ring in ~10 min.
- **Release routes (in vivo):** (a) myosin-II unbinding (low duty ratio, <15% strongly bound); (b) **cofilin
  severing** of the filament; (c) formin displacement from the barbed end.

## 2. The clump-vs-ring problem (the central failure)
Without sufficient **release**, SCPR condenses the band into **clumps** (discontinuous cortical aggregates) instead
of a ring. Many ring-assembly perturbations and mutants produce exactly this — it is the canonical SCPR failure
mode, and it is the failure the BoA model hit.

## 3. The quantitative criterion (the key result)
**Ojkic & Vavylonis, Phys. Rev. Lett. 105:048102 (2010):** a band condenses into a **ring** when the filament
reach exceeds the band width; **wide bands are unstable to clump formation** via Poisson density fluctuations.
Precisely:

> **λ > A·w**,  where **λ = v_pol · t_turn** (mean filament length = polymerization speed × turnover time) is the
> interaction range, **w** is the band width, and **A ~ O(1)**.

**Interpretation.** Filaments must reach *across* the band. If λ < A·w, local density bumps each seed their own
aggregate → clumps; if λ > A·w, condensation is coherent → ring. **Knobs:** turnover time `t_turn` (sets λ), formin
number/processivity (filament length/number), and band width `w`.

## 4. The mechanisms that channel condensation into an even ring
- **Release / turnover (the R):** keeps connections transient so aggregation doesn't run away to a point. Cofilin
  severing is a principal route — *severing-defective mutants clump*.
- **Crosslinkers (α-actinin Ain1, fimbrin Fim1):** stabilize transient linear elements and regulate filament
  orientation within bundles → they form the coherent circumferential bundle and prevent collapse to clumps.
  Without them (e.g. ain1Δ), nodes and linear structures collapse into clumps (Laporte et al. 2012; the local-
  alignment mechanism, Ojkic et al. 2011).
- **Cortical confinement:** nodes confined to the cortex (membrane anchoring; Bidone et al. 2014 used a ~5 pN
  confining force toward the boundary) move *on the surface* rather than collapsing to the 3D center. *(BoA already
  had this.)*
- **The full recipe (Bidone, Tang & Vavylonis 2014):** semiflexible filaments from cortical formins + myosin
  capture + crosslinker bundling/alignment + actin turnover + cortical confinement → robust ring. Varying myosin
  activity and crosslinker concentration moves the morphology among **clumps, rings, and meshworks** — i.e. the
  outcome is a phase diagram in (myosin pull, crosslinker density, release/turnover rate, confinement).

## 5. Mapping to SoftBox / BoA
- **The BoA clumping (surface, correctly confined) was most likely:** (a) **λ < A·w** — filament reach too short
  for the band width (turnover too fast / formins too few), undiagnosable at the time without a turnover layer to
  *control* λ; and/or (b) **missing crosslinker-driven bundling/alignment** — SCPR pull + release without the
  bundling collapses to clumps regardless of tuning.
- **SoftBox now has the three levers:**
  - **REACH (λ):** the turnover layer (growth + depoly + aging) sets the treadmilling reach — validated via the
    critical-concentration work. *λ in the criterion IS this reach* ⇒ the λ > A·w criterion is directly testable.
  - **RELEASE:** cofilin severing (validated) + myosin unbinding.
  - **BUNDLING:** the inc-5 crosslinkers.
- **Remaining piece:** the **cortical-confinement geometry** for the ring (a later increment; BoA's pill-cortex is
  the precedent).

## 6. Forward experimental plan (the ring)
- **Precursor — done:** the 3×3 net (local capture-coalescence) — coalesces; scheme-0 soft tether transmits the
  collective load; dead-slot fix holds at scale. Then: the full-turnover net (aging + severing in).
- **The ring experiment:** a **circumferential band** of nodes (spread around the cylinder at a realistic width
  `w`) on the cortical-confinement geometry, with all machinery on (formin nucleation + treadmilling + severing-
  release + crosslinkers). **Independently control λ (via turnover time) and w (band width); sweep λ across A·w;
  map the ring↔clump boundary** and compare to the λ > A·w prediction — a first-principles validation in the
  spirit of the critical-concentration check.
- **Watch for:** the morphology phase (clump / ring / meshwork) as a function of crosslinker density, release
  rate, and myosin pull, per Bidone 2014.

## Key references
- Vavylonis D, Wu J-Q, Hao S, O'Shaughnessy B, Pollard TD. *Assembly mechanism of the contractile ring for
  cytokinesis by fission yeast.* Science **319**:97–100 (2008).
- Ojkic N, Vavylonis D. *Kinetics of myosin node aggregation into a contractile ring.* Phys. Rev. Lett.
  **105**:048102 (2010). — the **λ > A·w** criterion.
- Ojkic N, Wu J-Q, Vavylonis D. *Model of myosin node aggregation into a contractile ring: the effect of local
  alignment.* J. Phys. Condens. Matter **23**:374103 (2011).
- Laporte D, Ojkic N, Vavylonis D, Wu J-Q. *α-Actinin and fimbrin cooperate with myosin II to organize actomyosin
  bundles during contractile-ring assembly.* Mol. Biol. Cell **23**:3094–3110 (2012).
- Bidone TC, Tang H, Vavylonis D. *Dynamic network morphology and tension buildup in a 3D model of cytokinetic
  ring assembly.* Biophys. J. **107**:2618–2628 (2014). — the confined 3D model closest to SoftBox's target.
- Wu J-Q, Pollard TD et al. — node-protein stoichiometry / band quantitation (e.g. Wu et al. 2006 JCB; Laplante et
  al. 2016 PNAS; node-number-scales-with-cell-size, 2022).
- Pollard TD, Wu J-Q. *Mechanisms of contractile-ring assembly in fission yeast and beyond* (review).

*(Citations are from the published abstracts/figures; consult the originals for parameter values before using
them as SoftBox targets — fold confirmed values into `V1_V2_PARITY.md` or a ring-parameters doc when the ring
experiment is set up.)*
