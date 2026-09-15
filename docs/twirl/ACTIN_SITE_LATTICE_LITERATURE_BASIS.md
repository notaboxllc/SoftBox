> ⚠ **TORSIONAL COHERENCE INVALIDATION (2026-09-06)** — twirl MAGNITUDES in this document were
> measured with NO inter-segment torsional coupling (segments rolled independently; internal twist drift
> reached +9.1 turns). Absolute pitch / turns-per-µm / Ω values here are VOID pending re-measurement with
> `-rollspring`. Sign, antisymmetry and null results are unaffected. See
> `docs/twirl/TORSIONAL_COHERENCE_INVALIDATION.md`.

# The actin myosin-binding site lattice — literature basis and recommended parameters

**Date** 2026-08-27 · **Branch** `gpu-mat-bottlenecks-explicit-singlehead` · **Status: RECOMMENDATION, NOT YET
APPLIED.** No constant, flag, kernel or default was changed in producing this document. `Constants.actinMonoRadius`
and `TWIST_PER_MON_DEG` are load-bearing across every assay in the repo; nothing here should be applied without a
separate, gated increment.

**Why this exists.** Two campaigns disagree about which actin site lattice they run on, and neither lattice was
chosen from the structural literature:

- The **Vilfan target-zone lineage** (`docs/VILFAN_TARGET_ZONE_BINDING_AND_TWIRLING_FINDINGS.md`,
  `docs/VILFAN_BROWNIAN_NOISE_ABLATION_FINDINGS.md`, and the sibling worktrees) uses a **continuous** helical
  phase `phi = twistRate * bindArc` with `twistRate = -1076.3 rad/µm` — the native per-monomer twist, but with the
  target azimuth a continuous function of the head's arc position.
- The **current stroke-skew twirl campaign** (`docs/twirl/SITE_LATTICE_TWIRL_NULL.md`, `TWIRL_SKEW15`,
  `TWIRL_EPS_LADDER`) uses **discrete** `every4+partner` sites: 10.8 nm rise, +54°/site, 72 nm repeat, plus a
  +1-monomer partner on the opposite long-pitch strand.

They are not comparable, and the difference sits directly on the mechanism the Vilfan work was measuring. This
document establishes what the structural literature actually supports, and recommends a single lattice for both.

---

## 0. Recommendation

| parameter | recommended | current campaign | Vilfan lineage | confidence |
|---|---|---|---|---|
| rise per subunit | **2.75 nm** | 2.70 nm | 2.70 nm | consensus (2.74–2.76) |
| twist per subunit | **−166.6°** (left-handed genetic helix) | −166.5° | −166.5° (see §9 convention warning) | consensus (−166.5 to −167.3) |
| crossover repeat | **36.9 nm — DERIVED, do not set independently** | 36.0 nm | 36.0 nm | consensus |
| subunits per crossover | **13.43 (not 13)** | 13.33 | 13.33 | consensus |
| **site spacing** | **every subunit (2.75 nm)** | **10.8 nm pairs** | continuous | consensus — §2, §3 |
| **azimuthal selectivity** | **soft Gaussian, α = 3.42 (σ = 31°)** | n/a (no TZ) | α = 4 / 6 / 8 | §4, §5 |
| twist disorder | **quenched, 1–5°/subunit, parameterised** | rigid helix | rigid helix | **CONTESTED — §8** |

**The single substantive change is site density.** Sparseness should come from a soft azimuthal weight, not from
deleting sites. See §6 for the test that decides this.

**Update 2026-08-28 (§7.3):** the lattice is a NECESSARY SUBSTRATE but contributes no twirl of its own —
converter skew is null on this same lattice (1.0σ) where stroke skew reaches 13.2σ. The chirality MECHANISM, not
the lattice, is what changed.

---

## 1. Actin helical geometry — modern values

The textbook "13/6 helix, −166.15°, 27.5 Å, 36 nm crossover" is a rational-approximation idealization, not a
measurement. What cryo-EM changed since ~2009 is not the ballpark but the epistemic status: twist and rise are now
**refined free parameters** against 2.2–3.6 Å maps, and the refined value is reproducibly **more negative than
exact 13/6**. F-actin is an incommensurate helix near 13/6.

| structure | PDB | res (Å) | rise (Å) | twist (°) |
|---|---|---|---|---|
| exact 13/6 (ideal, not measured) | — | — | 27.5 | −166.154 |
| AMPPNP-F-actin, Chou & Pollard 2019 | 6DJM | 3.1 | 27.40 | −166.61 |
| ADP-Pi-F-actin, Chou & Pollard 2019 | 6DJN | 3.1 | 27.42 | −166.56 |
| ADP-F-actin, Chou & Pollard 2019 | 6DJO | 3.6 | 27.45 | −166.55 |
| bare F-actin (Sindelar lab), Gurel 2017 | 6BNO | 5.5 | 28.11 | −166.60 |
| acto-myo1b rigor, Mentes 2018 | 6C1D | 3.2 | 27.5 | −167.4 |
| actomyosin-V rigor, Pospich 2021 | 7PLT | 3.3 | 27.8 | −167.3 |
| cofilactin, Tanaka 2018 | 5YU8 | 3.8 | 27.6 | −162.1 |
| Galkin 2010 IHRSR canonical | — | — | 27.5 | −166.6 |
| fascin-bundled consensus, NSMB 2024 | — | — | — | −166.6 ± 0.4 |

**Established:** rise 2.74–2.76 nm, twist −166.5 to −167.3°. **Established null:** nucleotide state does *not*
change rise or twist measurably (Chou & Pollard span 0.05 Å and 0.06°; Oosterheert 2022 report no change across
Mg²⁺ nucleotide states). Nucleotide changes subunit *conformation*, not lattice *symmetry*.

**Lab-systematic, not biology:** the Sindelar-lab 28.06–28.11 Å rise sits ~2 % above everyone else's 27.4–27.6 Å.
Most plausibly a pixel-size calibration difference at 5.5 Å resolution. **Do not treat 27.4–28.1 Å as a physical
range.**

### 1.1 Two traps

**(a) Sign convention is not reliable in the PDB.** 3J8A and 5JLH are deposited with *positive* 166.4°/166.9°;
everything else negative. Li et al. 2025 audited 2,025 EMDB helical entries and found **~14 % carry errors**
(missing parameters, swapped rise/twist, wrong sign). Take the sign from physics — the genetic helix is
**left-handed**, the two long-pitch strands are **right-handed** — not from the deposition.

**(b) Crossover is hypersensitive to twist; do not set both.** The 2-start advance per subunit is the residual
`180° + twist` ≈ 13.4°, so a 0.5° twist error is a ~4 % crossover error:

| twist | subunits/crossover | crossover (nm @ 2.75 nm rise) |
|---|---|---|
| −166.15° (13/6) | 13.00 | 35.8 |
| −166.50° (**ours**) | 13.33 | 36.0 |
| −166.60° (**recommended**) | 13.43 | **36.9** |
| −167.00° | 13.85 | 38.1 |
| −167.14° (Vilfan 28/13) | 14.00 | 38.5 |

"36 nm crossover" and "−166.6°" are **not simultaneously exact**. Pick twist; let crossover follow.

---

## 2. The actomyosin interface — two protomers, same long-pitch strand

**Consensus, not contested.** One myosin motor domain engages **two actin protomers**, with a clear
primary/secondary asymmetry.

**Primary ("target") actin `n`** — helix-loop-helix (main hydrophobic anchor), HCM/cardiomyopathy loop (SD1/SD2
hydrophobic patch), loop 4 (→ K328, SD3), loop 2 (→ acidic N-terminus, SD1). Robert-Paganin 2021 call this the
**conserved core interface**.

**Ancillary ("Milligan") actin `n−2`** — loop 3 → SD1/SD2/D-loop of the neighbouring protomer. Robert-Paganin's
**ancillary interface**, *"the most variable interactions."*

**The neighbour is on the SAME long-pitch (two-start) strand, toward the pointed end — `n−2` in genetic-helix
index, 5.5 nm away.** Evidence chain:

- **Milligan 1996:** each actin target area spans *"about four actin monomers along ONE OF THE TWO LONG-PITCHED
  helical strands."*
- **Lorenz & Holmes 2010:** labels them AC3 (major) and AC1, *"located underneath AC3 in the actin helix"* —
  differing by 2 in genetic-helix index. Loop 3 *"moves toward AC1 (the Milligan contact)."*
- **Fujii & Namba 2017**, fully-decorated rigor at 5.2 Å: *"intimate interactions of a myosin head and two actin
  subunits **along one strand** of actin filament."*
- **Behrmann 2012:** *"a large interface involving two adjacent actin monomers."*

**Caution:** some review text renders this as "across both strands." That is imprecise — the contacted protomers
are `n` and `n−2`, consecutive along one two-start strand.

**Interface size.** Lorenz & Holmes 2010: total buried 1,653–2,016 Å²; primary 1,295–1,429 Å², secondary
358–595 Å² ⇒ the ancillary actin carries **~22–30 %**. Robert-Paganin 2021: mean **1,607.5 ± 58.1 Å²** across all
high-resolution rigor structures — remarkably tight across myosin classes.

**One real caveat.** The loop-3 / Milligan contact is **not universal** — Robert-Paganin classify it as variable
and note *"loop 3-mediated contacts are not a feature shared between all myosin isoforms."* For a generic
myosin II model, treat the secondary contact as an affinity/specificity modulator, not the anchor.

---

## 3. There is NO steric exclusion between adjacent sites

At saturating S1 in rigor, actin:S1 stoichiometry is **1:1** — every subunit simultaneously occupied. This is
classic decorated actin (Moore, Huxley & DeRosier 1970) and is exactly the specimen used for the modern
high-resolution structures (Behrmann 2012, von der Ecken 2016, Fujii & Namba 2017, Mentes 2018, Pospich 2021 all
reconstruct **fully decorated** filaments).

**⇒ A lattice that deletes actin subunits on steric grounds is unjustified.** Adjacent bound heads simply *share*
an actin: head `k`'s Milligan actin is head `k−1`'s target actin.

- **Interface footprint:** 2 subunits, ~5.5 nm axial extent, ~1,600 Å² buried.
- **Steric footprint:** effectively 1 subunit for occupancy purposes.

**This is the finding that invalidates `every4`.** Site sparseness must come from somewhere other than sterics.

---

## 4. The target zone — what it is, and how well it holds

**Origin.** Not Steffen. The idea originates with **Reedy 1968 / Taylor 1984** in insect flight muscle. Steffen
2001 is the first single-molecule demonstration.

**Steffen, Smith, Simmons & Sleep 2001** (PNAS 98:14949), optical-trap three-bead assay:

| quantity | value |
|---|---|
| monomers per zone | **3** |
| angular spacing along one strand | **180/7 ≈ 26°** |
| angular half-width | **~31°, from a SOFT GAUSSIAN** (not a cutoff) |
| period | **36–41 nm** (fits span 30–40) |
| monomer repeat resolved | **5.5 nm — and explicitly NO 2.75 nm modulation** ⇒ single strand |
| clean signature present in | **only ~30 % of records** |

**Independent re-measurement (one).** Suzuki & Ishiwata 2011 (*Biophys J* 101:2740), different lab, different
geometry: cross-bridge spacings *"36, 72, or 108 nm"*, reproduced in vitro with randomly distributed myosin, and
*"a stereospecific model composed of three actin protomers per target zone was shown to explain the experimental
results."*

**The count is assay-dependent, not simply settled:** 2 per strand (4 total) in situ by EM (Wu 2010) · 3 by
in-vitro trap, twice independently · "three or more" from muscle-force modelling. These plausibly measure
different things — occupancy, accessibility, force-per-zone.

### 4.1 The strict reading has WEAKENED since 2001

- **Wu 2010 finds 22 % of strong-binding heads OUTSIDE the zone**, and argues against the azimuthal explanation
  in its own text: *"Our sharply defined target zone implies a restrictive geometrical constraint on myosin head
  binding **that is contradicted by** the highly variable shape of strong binding cross-bridges."* And: *"if actin
  azimuth alone were the limiting factor, we would expect a **more gradual tapering** … **This we also do not
  see.**"* And: *"substantial azimuthal flexibility … **would permit strong binding bridges to form virtually
  anywhere along the actin 38.7 nm repeat**."*
- Nucleotide-dependent: in rigor the zone widens to **8 subunits (4 per strand)**.
- **Månsson 2019** argues Steffen's geometry is an **S1-flexibility artifact**, not an actin-geometry constraint.
- **Nobody has ever ablated it.** MUSICO (Mijailovich 2016) implements azimuth as a continuous weight `C_β` with
  **no cutoff angle stated anywhere**, inherited from Steffen and never removed as a control. Tanner, Daniel &
  Regnier 2007 contains **no occurrence of "target zone" or "azimuth"** and reproduces cooperative binding without
  an azimuthal term. **The necessity question is open.**

**⇒ The zone is a preference, not a gate.** It is *empirically sharper* than a pure azimuthal model predicts,
while the *azimuthal explanation* for it is contradicted by the same data. The field's reconciliation is a wide
capture funnel collapsing onto a narrow stereospecific pose (Ferenczi 2005 "roll and lock"; Arakelian 2015).

---

## 5. Why azimuth cannot be a geometric gate — the compliance number

**Adamovic, Mijailovich & Karplus 2008** (*Biophys J* 94:3779), myosin II S2 at 60 nm:

| | |
|---|---|
| axial stiffness | **60–80 pN/nm** |
| lateral / bending stiffness | **0.010–0.0122 pN/nm** |
| ratio | **~6,000–8,000×** |
| persistence length | 130–170 nm |

Moving a head 90° around actin at binding-site radius ~4 nm is a **6.3 nm arc**. Against `k⊥ = 0.010 pN/nm` that
costs **≈0.048 k_BT**. Free thermal lateral excursion of a 60 nm S2 is **~20 nm rms**.

**⇒ Getting the head to the right azimuthal PLACE is essentially free. The entire cost is re-ORIENTING it
stereospecifically. Geometry and chemistry are decoupled.**

Corroborating: **Brizendine 2021** (*JGP* 153:e202012751) — S2 pulls **59 ± 3 nm** off the filament backbone with
a head still bound. **Klebl 2025** (*Nature* 642:519), time-resolved cryo-EM: the lever swings ~93° *"predominantly
along the actin axis, and is displaced azimuthally by only 4°"*; interface **375 Å² primed → 729 Å² post-stroke**
— the weak state is azimuthally loose, the strong state is not.

**Contrast — the muscle-lattice ceiling does NOT transfer to a gliding assay.** Squire & Harford 1988: actin
presents *"an azimuthal range of actin binding sites of about 100°"* with the myosin origin swinging azimuthally
*"less than 60°"* — but that presupposes a thick-filament origin.

---

## 6. The decisive test — the target zone EMERGES from every-subunit sites

Sites at every subunit (rise 2.75 nm, twist −166.6°); retain those within ±31° of a fixed motor azimuth:

```
n=  0   z=  0.00 nm   phi=  +0.0°   strand A
n=  2   z=  5.50 nm   phi= +26.8°   strand A   (+5.5)
n= 13   z= 35.75 nm   phi=  -5.8°   strand B   (+30.2)
n= 15   z= 41.25 nm   phi= +21.0°   strand B   (+5.5)
n= 26   z= 71.50 nm   phi= -11.6°   strand A   (+30.2)
n= 28   z= 77.00 nm   phi= +15.2°   strand A   (+5.5)
```

**Every element of Steffen 2001 is reproduced from pure geometry plus one window parameter, with nothing fitted:**
zones every **35.75 nm** (measured 36–41); **2–3 monomers per zone at 5.5 nm** (measured 3 at 5.5 nm); each zone on
a **single strand** (measured: no 2.75 nm modulation); consecutive zones **alternating strands** — which is what
makes the period 36 nm rather than the 72 nm single-strand azimuthal repeat.

### 6.1 Our lattice options against the same window

Accessible sites over 150 nm, ±31°:

| lattice | acc. sites | axial positions (nm) | verdict |
|---|---|---|---|
| **every subunit** | **10** | 0, 5.5, **35.8, 41.2**, 71.5, 77.0, **107.2, 112.8**, 143.0, 148.5 | reproduces Steffen |
| `every2` one strand | 6 | 0, 5.5, 71.5, 77.0, 143.0, 148.5 | doublets right; **misses every other zone** (strand B) |
| **`every4+partner` (CURRENT)** | **5** | 0, 35.8, 77.0, 112.8, 143.0 | **1 site per zone, not 3; doublet destroyed** |
| `every4` one strand | 3 | 0, 77.0, 143.0 | worst |

**⇒ The current lattice under-supplies accessible sites by ~2× and eliminates the 5.5 nm intra-zone doublet.**

### 6.2 Reproduction

```python
import math
RISE=2.75; TW=-166.6; WIN=31.0
wrap=lambda a: (a%360)-360 if (a%360)>180 else (a%360)
for n in range(60):
    z, p = n*RISE, wrap(n*TW)
    if abs(p)<=WIN and z<=150: print(f'n={n:3d} z={z:6.2f} phi={p:+6.1f} {"A" if n%2==0 else "B"}')
```

### 6.3 `every2` and `every4` are the SAME helical track

Computed from repo constants (2.7 nm, −166.5°):

| N | rise (nm) | Δφ/site | azimuthal repeat | twistRate (rad/µm) |
|---|---|---|---|---|
| 1 | 2.70 | −166.5° | 5.8 nm | −1076.3 |
| **2** | **5.40** | **+27.0°** | **72 nm** | **+87.3** |
| 3 | 8.10 | −139.5° | 20.9 nm | −300.6 |
| **4** | **10.80** | **+54.0°** | **72 nm** | **+87.3** |
| 13 | 35.10 | −4.5° | — | −2.2 |

`every2` and `every4` share twistRate and repeat exactly — `every4` is `every2` with alternate sites deleted. The
choice between them is **density, not geometry**. (`every13` = 35.1 nm at only −4.5° drift is the classic ~36 nm
near-azimuthal recurrence the target-zone literature is built on.)

---

## 7. What our code already does right

`TwoBodyBeamAnalyticGpu.matTargetZone`:

```java
if (hardGate > 0.0) { double ad = dpsi < 0 ? -dpsi : dpsi; w = ad < hardGate ? 1.0 : 0.0; }
else                 w = Math.exp(-0.5*alphaPsi*dpsi*dpsi);
```

`ExplicitCompleteMatHarness:131`: *"TZ_ALPHA = alphaPsi = Kpsi/kB T (dimensionless; literature-scale values 4/6/8
— none canonical)."* The hard cutoff (`TZ_HARD_RAD`) is labelled **diagnostic only** and was off in every campaign.

**⇒ The functional form is already the correct soft Boltzmann weight. No port is needed.**

### 7.1 But every campaign ran STIFFER than the literature

`w = exp(−½·α·Δψ²)` is a Gaussian with **σ = 1/√α**:

| α | σ (°) | half-max (°) | provenance |
|---|---|---|---|
| **3.42** | **31.0** | 36.5 | **literature** — Steffen's 31° Gaussian half-width; Vilfan α ≈ 3.4 |
| 4.0 | 28.6 | 33.7 | our arm — 8 % narrower |
| **6.0** | **23.4** | 27.5 | **our PRIMARY arm — 25 % narrower** |
| 8.0 | 20.3 | 23.9 | our arm — 35 % narrower |

Note α = 1/σ² with σ = 31° gives **α = 3.42** — Steffen's half-width and Vilfan's α are the same number, an
independent consistency check.

**We bracketed the literature value from above and never ran at or below it.** The Stage-A null, the noise
ablation and the occupancy ladder all sat at **α = 6**. A narrower window admits fewer heads and — given the ~105×
phase decorrelation — is exactly what makes a thermally-jittered phase more likely to miss.

**This does NOT overturn the null.** α = 4 is only 8 % off and was also null, so the result is not knife-edge in
α. But the honest statement is **"null for α ≥ 4"**, not "null at the literature value" — which has never been run.

### 7.2 A second discrepancy with the published model

**Vilfan 2009 assumes no hard zone** — only actin's helix plus a Boltzmann elastic weight — and **his pitch formula
contains no motor density**, so twirl is predicted **density-independent** and does not require many bound heads.

This is in direct tension with our own occupancy ladder (dead below N_b ≈ 14, operational at 29;
`softbox-vilfan-low-occupancy-brownian`). Candidate resolutions, untested:

1. our α was stiffer than the model's (§7.1);
2. pitch is density-independent but *resolvability* is not — rotational Brownian swamps the coherent torque at low
   N_b (fits our Ω-link failing specifically at L12);
3. Vilfan's treatment is mean-field and omits something the stochastic implementation has.

**⇒ "Vilfan requires high occupancy" is a claim about OUR implementation, not about Vilfan's model.** Any write-up
of the occupancy ladder must say so.

---

## 7.3 RESOLVED (2026-08-28): it is the MECHANISM, not the lattice — and what the stroke skew actually is

The §7.2 question was settled by a single-factor experiment. `TWIRL_CONV_SKEW` ran converter skew ±15° (linear
ramp, matching the eta-map arm) on the **identical** harness, lattice, binding law, eta, dt, filament, density,
travel target, runner and seeds as the stroke-skew ladder's eps=15 rung. **Only the chirality channel differed.**

| | old lattice (`every3`, 20 ms, pre-§20.4 observable) | **new lattice** (`every4+partner`, site-normal, 1.2 µm travel) |
|---|---|---|
| **converter skew ±15°** | −0.219 turns/µm, 1.06σ (n=24, eta-map) | **+0.324 ± 0.339, 1.0σ — NULL, <1.00 at 2σ** |
| **stroke skew ±15°** | ±2° only: 1.9σ, never powered | **−4.043 ± 0.305, 13.2σ** |

Difference between channels under identical conditions: **−4.367 ± 0.456 = 9.6σ.** The converter arms are
healthy — 0 invalid, 0 solverFail, travel 1.177–1.190 µm, v 0.74–0.83 µm/s, avgBound 0.94–1.05 — a filament that
glides normally and does not twirl.

**⇒ The lattice, the site-normal binding law, the 20× longer travel and the repaired roll observable did NOT
rescue the converter channel.** None of them manufactured the stroke-skew signal.

**A hypothesis this REFUTES (recorded because it was mine).** I proposed that the new lattice supplies a
symmetric-null background, so eps produces signal "against nothing", explaining the *cleanliness* separately from
the magnitude. The noise floors are essentially identical between channels — odd-component SEM **0.305 (stroke)
vs 0.339 (converter)**. The new setup did not lower the noise. It is a pure signal-magnitude difference.

### 7.3.1 What the stroke skew IS — and what it is not

**It is not a spring whose rest length is the initial binding separation.** F8 is
`TwoBodyConverterMotor:37` *"the exact zero-rest Hookean spring"* (`xbParams[2]=0`), `F = k·(x_site − x_F8)`,
k ≈ 1 pN/nm — always pulling the head onto the site, with no preferred direction. That is precisely why
`epsBind` is null at the source (§20.13: *"a zero-rest-length point cross-bridge cannot carry a preferred
direction"*): binding to an already-offset site changes WHICH material point is targeted, not how far the spring
is stretched.

`epsStroke` differs in one respect only — it moves the target **after** the head is bound, at the ADP·Pi→ADP
transition (`ChiralSiteSystem.strokeSkew`, guarded on `bs >= 0`), which does stretch the spring. The site is a
material point on the filament surface,
`p = segCentre + arc·û + R_actin·(cos(bindAzim)·ŷ + sin(bindAzim)·ẑ)`, R_actin = 3.5 nm; advancing `bindAzim`
by eps rotates that target about the filament axis.

**It is NOT a strain maintained for the bound lifetime** (an earlier draft of this document said so; that was
wrong). The bond relaxes — and **the relaxation IS the twirl**. Measured over the six eps=15 ladder runs:

| quantity | value |
|---|---|
| imposed azimuthal advance per stroke | 15° |
| circumferential rest-offset `R_actin·eps` | 0.916 nm |
| bond force at full offset (1 pN/nm) | 0.916 pN |
| strokes per µm | ~1500 |
| **net filament roll per stroke** | **0.967°** |
| **efficiency** | **6.4%** |

**Why relaxation is incomplete.** Filament roll drag `γ = 4πηLa²` = 4π(0.1)(2.106 µm)(3.5 nm)² =
**3.24e−23 N·m·s** (matching the §20 measured value); one-bond torsional stiffness `k_θ = k_F8·R²` =
**1.22e−20 N·m/rad**; so **τ_relax = γ/k_θ ≈ 2.65 ms** against a **mean bound lifetime ~0.86 ms**. A head
detaches after ~0.33 relaxation times ⇒ `1 − e^(−0.33) ≈ 28%` recovery predicted for an isolated bond. Observed
6.4% — the one-bond estimate over-predicts ~4×, as expected once the ~27% of time with ≥2 heads bound (mutually
opposing), the axial gliding load competing for the same bond, and the skew firing partway through the
attachment are included. **The mechanism is partial relaxation cut short by detachment.**

### 7.3.2 Why the converter channel is weaker — series compliance (HYPOTHESIS, not tested)

Both channels regenerate strain at each stroke (§21.10 established this for the converter, distinguishing it from
`epsBind`). The asymmetry is **where the displacement is imposed**:

- **stroke skew** displaces the two ends of the F8 spring relative to each other by construction ⇒ force
  `k_F8·d` through the **stiff** element, 1 pN/nm;
- **converter skew** drives the head circumferentially through the lever with the **S2 beam in series**, and in
  series the soft element dominates. Our S2 is L = 40 nm with EA/EI from Adamovic 2008 (§5); scaling their 60 nm
  lateral stiffness 0.010–0.012 pN/nm by (60/40)³ gives ≈**0.034–0.041 pN/nm**, ~25–30× softer than the bond. Most
  of the converter's circumferential drive therefore deflects the motor's own S2 rather than the filament.

Predicted contrast ~25–30×; **measured ~12× in central value (≥4× as a bound)** — same sign, right order,
over-predicting, which is honest for a two-spring estimate ignoring lever geometry and the fact that F8 and S2 are
not purely in series. **The direct test is to instrument the displacement ACROSS F8 in both channels rather than
infer it from the twirl. Not done.**

### 7.3.2b THE SERIES-COMPLIANCE HYPOTHESIS IS NOT SUPPORTED (2026-09-02/03) — three interventions, three failures

§7.3.2 proposed SERIES COMPLIANCE as the reason the converter channel is null: the skewed swing pushes the head
circumferentially and the reaction runs back through a laterally soft S2 (~0.037 pN/nm at L = 40 nm) instead of
turning the filament, which is bonded at ~1 pN/nm — a 25–30× shortfall. That hypothesis has now been tested
directly and **it does not hold up.**

All three campaigns below ran the CANONICAL lattice and are matched to `TWIRL_CONV_SKEW` in every respect but the
intervention: `site_lattice: "every4+partner(two-strand)"`, `rand_base_azimuth: false`, eta 0.10, dt 1.25e-6,
d500, target 1.2 µm, linear converter ramp, same 3 seeds — verified from `run_config.json`, not assumed.

**Intervention 1 — BINDING-gated stiffening (`-s2-catch 20`). BROKE THE MOTOR.**
Bound motors got 20× the S2 bending stiffness; free motors kept the canonical value, so search was untouched.
Result after 7.7 h: **gliding REVERSED** (travel −0.20, −0.41, −0.27 µm — the filaments ran backwards),
`avgBound` 0.79–0.86 vs ~0.99, with **0 invalid / 0 solverFail**. Not numerically broken — *mechanically*
different. Any twirl number computed against negative travel is meaningless; the campaign was killed.
Preserved: `/tmp/CONV_S2CATCH_X20_motorbroken_*`.
**Diagnosis:** it stiffened every bound head including the lightly-loaded pre-stroke dwell, which is not a state
the real tether is stiff in.

**Intervention 2 — LOAD-gated stiffening (`-s2-loadcatch`), the physically motivated form.**
Scholz, Altmann, Antognozzi, Tischer, Hörber & Brenner (2005) *Biophys J* 88:360 **MEASURED** an asymmetric
myosin-tether stiffness — **≈0.04 pN/nm in extension vs ≈0.004 in compression, a 10× asymmetry**, and *"the
source of this low stiffness is located OUTSIDE the myosin head domain."* So `kb` ramps kb0 → kb0×10 as |F8|
goes 0 → F0: a free searching head is unloaded and soft, a loaded head stiffens, and a bound-but-lightly-loaded
head **stays soft** — which is what Intervention 1 got wrong. Factor 10 is the MEASURED value, not a guess.
Passed all five validation gates including **forward gliding**, which the binding gate failed.

**The F0 sweep — monotonic, NO operating window** (factor ×10, 300k steps, matched seed):

| F0 (pN) | baseline | 2 | 5 | 8 | 12 |
|---|---|---|---|---|---|
| v (µm/s) | 1.076 | 0.224 | 0.422 | 0.428 | 0.633 |
| **v / baseline** | 1.00 | **0.21** | 0.39 | 0.40 | **0.59** |

Velocity recovers only in proportion to how little stiffening is applied. Even at F0 = 12 pN — the force cap, so
only heads near breaking stiffen — gliding is still **41 % slower**. **This is the Exp 4E compliance trade-off for
the THIRD time:** transmission gain and glide loss share one beam, exactly as recruitment gain and stroke loss
did there.

**Intervention 3 — the load-gated twirl run itself (×10, F0 = 12 pN, the mildest gate):**

```
eps=+15  +0.292 +- 0.715   [1/3 negative]
eps=-15  -1.026 +- 0.838   [2/3 negative]
ODD      +0.659 +- 0.551 turns/um  (1.2 sigma)
VERDICT  NO REVERSAL RESOLVED — 2-sigma upper bound |twirl| < 1.76 turns/um
```

**An honest weakness in this test, stated because it cuts against the conclusion:** the gated runs are NOISIER
than the ungated ones (SEM 0.551 vs 0.339), so the 2σ bound is **LOOSER — 1.76 vs 1.00.** It excludes the
converter reaching epsStroke's 4.043 (≥2.3×), but it is a *less* sharp exclusion than the ungated campaign, not a
sharper one. Do not quote it as a tighter null.

**Summary of the converter channel across everything run:**

| test | odd (turns/µm) | σ |
|---|---|---|
| ungated (`TWIRL_CONV_SKEW`) | +0.324 ± 0.339 | 1.0 |
| binding-gated ×20 | — | **motor broke; glide reversed** |
| load-gated ×10, F0 = 12 (measured Scholz factor) | +0.659 ± 0.551 | 1.2 |
| **`epsStroke`, matched conditions** | **−4.043 ± 0.305** | **13.2** |

Historically also null at ε = 5°/15°/30° in the realistic assay and at ε = 1°/2° in the small-skew pilots (those
on the older `every3` lattice, so not directly comparable).

**⇒ TAKE THE CONVERTER NULL AT FACE VALUE.** It is not a compliance artifact. **Rotating the stroke plane does not
twirl this filament; displacing the bond's rest point does.** That is consistent with Klebl 2025 reporting the
azimuthal lever component as *"only 4°"* and with Beausang 2008's ~80 % of real filaments not twirling at all.

**Consequence for the dimer.** The proposed dimer port was motivated by series compliance — a shared S2 junction
and partner head supplying the transverse stiffness a free cantilever lacks. **That premise has now failed twice,
so the dimer port is poorly motivated** and should not be undertaken on this rationale.

### 7.3.3 What this does NOT establish

The 2σ bound of 1.00 turns/µm does **not** exclude the historical converter value of −0.219 turns/µm. The
converter channel is shown to be ≥4× weaker than the stroke channel, **not** to be zero. Both measurements at
eta = 0.10 remain consistent with a small converter twirl that neither assay resolves.

---

## 7.4 RECORDED NEGATIVE (2026-08-31): structure does NOT supply epsStroke's magnitude

**Question.** `epsStroke` is the azimuthal advance, about the actin filament axis, of the BOUND interface at the
ADP·Pi → ADP transition (§7.3.1). Its structural analogue is the azimuthal displacement of the myosin–actin
**contact centroid** between a primed (ADP·Pi) and a post-powerstroke (ADP) actin-bound structure. Can the
literature supply the number?

**Structures** — Klebl et al. 2025, *Nature* 642:519, time-resolved cryo-EM, same construct, both actin-bound:

| PDB | state | ligands | chains |
|---|---|---|---|
| **8R9V** | primed actomyosin-5a | ADP + PO4 (= ADP·Pi) | A = myosin-Va; B,C,D = actin |
| **8RBF** | post-powerstroke actomyosin-5a | ADP | A = myosin-Va; B,C,D = actin |

(8RBG is myosin alone, no actin — unusable.) This is **exactly the transition `ChiralSiteSystem.strokeSkew`
fires on**.

**Method (fixed before the result was inspected).** Superpose 8RBF on 8R9V using actin CA only; take the
filament axis and origin from the **helical screw operator** relating consecutive actin protomers; contacts =
myosin heavy atoms within CUTOFF of actin heavy atoms; `eps = Δφ` of the contact centroid about the axis;
repeat at CUTOFF ∈ {4.0, 4.5, 5.0} Å. Script: `scripts/epsstroke_structural_bracket.py`.

**A methodological note worth keeping.** The axis must come from the screw operator, **NOT from PCA** of the
actin cloud. Three protomers span only ~5.6 nm axially while F-actin is ~9–10 nm wide, so the PCA principal axis
is RADIAL, not axial. The first attempt did this and produced a consecutive-protomer advance of **−179.9°**
where actin demands ~±166.6° — the tell that the frame was wrong. The screw-operator axis self-validates:

```
B->C: twist +166.73 deg  rise 28.10 A      C->D: twist +166.72 deg  rise 27.91 A
B->D: twist  -26.53 deg  rise 56.01 A      (= 2 subunits; 2x166.73 = 333.46 = -26.54 -- the §6.3 same-strand advance)
```

### The result, and the control that overturns it

| cutoff (Å) | contacts pre → post | **eps, ALL contacts** | **eps, CONSERVED CORE** | core residues |
|---|---|---|---|---|
| 4.0 | 44 → 58 | **+4.36°** | **−0.21°** | 17 |
| 4.5 | 65 → 91 | **+5.92°** | **−0.20°** | 19 |
| 5.0 | 94 → 124 | **+6.00°** | **−0.23°** | 22 |

The all-contacts centroid appears to rotate by **+5.4° (spread 1.6°)**. Restricting to myosin residues in
contact in **BOTH** states — the contacts that could actually carry strain — gives **−0.2°, essentially zero and
marginally the opposite sign**.

**⇒ The +5.4° is an artifact of the interface GROWING ASYMMETRICALLY** (375 → 729 Å², Klebl's own numbers;
here 7–10 new residues joining on one side), **not of the interface rotating.**

### Why the distinction is mechanical, not pedantic

`strokeSkew` displaces an **existing** bond's rest point, straining it, and the filament rotates chasing it
(§7.3.1). Asymmetric patch growth adds **parallel attachments at new locations**; it does not strain the
existing one. The conserved contacts — the ones that could transmit a circumferential force — **do not move**.

**⇒ This structure pair does NOT support epsStroke as implemented.**

### The near-miss, recorded deliberately

**+5.4° falls almost exactly in the middle of the 3.3–7.7° band the randomised-base runs require to reproduce
the experimental pitch** (§9, k = 0.4626 ± 0.0607 turns/µm/deg). Reported without the control it would have read
as a striking independent convergence. It is not one. **Anyone re-running this must run the conserved-core
control before quoting a number.**

### What this does and does not establish

**Does NOT refute the mechanism.** The model's twirl is real, sign-reversing (7.6σ), ε-linear, and null at
ε = 0 irrespective of what motivates ε.

**Does NOT settle the question generally:** myosin-**Va**, not myosin II (our model is non-muscle myosin II — a
two-step class transfer); a single structure pair; and the primed state is the less well-ordered of the two.

**A real observation in passing:** the core interface *does* move **axially** (Δz ≈ −3.5 to −6.8 Å) and
**tightens radially** (Δr ≈ −1.3 to −2.3 Å). It simply does not rotate.

### 7.4.1 COMPLETION (2026-09-03): the other two channels, and why the first test was not enough

The original §7.4 measured only the **contact-centroid rotation** and concluded "structure does not supply
epsStroke's magnitude". That was too broad on one axis and incomplete on another. Both gaps are now closed.

**Gap 1 — superposing on actin hid any ACTIN-SIDE rotation.** §7.4 aligned 8RBF onto 8R9V using ALL actin CA
atoms, which by construction removes rotation of actin itself. But `epsStroke` moves the actin MATERIAL SITE, so
if the chirality lived in the target protomer twisting under the head, the method was blind to it.
Test (`scripts/epsstroke_protomer_test.py`): superpose on chain **C only** — the protomer myosin never touches
(0 contacts within 5 Å) — then measure each protomer's residual rotation about the filament axis.

| chain | role | residual RMSD | rotation | **axial component** |
|---|---|---|---|---|
| B | **TARGET actin** (65 contacts) | 0.772 Å | 0.579° | **+0.330°** |
| D | ancillary (30 contacts) | 0.533 Å | 0.300° | **−0.249°** |
| C | reference / control | 0.542 Å | 0.000° | 0.000° |

Opposite signs on the two contacted protomers — not a coherent twist — and both at the level of the 0.542 Å
reference noise. **This is an EXCLUSION, not an underpowered null:** at a mean radius of ~25 Å from the filament
axis a 3° protomer rotation displaces atoms by ~1.3 Å and a 7° rotation by ~3.1 Å, both above the 0.77 Å residual
floor. **The required 3–7° would have been visible.**

**Gap 2 — only the ACTIN side was ever tested.** The F8 bond is zero-rest, `F = k*(x_site − x_F8)`, so displacing
the actin site by +d and the MOTOR anchor by −d give IDENTICAL strain: **the bond cannot tell which end moved.**
jba's physical objection — a bound head does not plausibly "scoot" tangentially over actin, but an internal
conformational change carrying the head's anchor tangentially IS plausible — is therefore the SAME mechanism in
this model, and had never been checked. Test (`scripts/epsstroke_motorside_test.py`): superpose on actin, then
decompose every myosin CA displacement into axial / radial / tangential about the filament axis.

| region | axial (Å) | radial (Å) | **tangential (Å)** | eps_equiv |
|---|---|---|---|---|
| **actin-contact (F8 anchor)**, n=26 | −0.25 | +0.14 | **+0.77 ± 3.34** | **+1.25°** |
| motor-domain core (≤600), n=589 | −1.97 | −1.86 | +2.11 ± 3.77 | +3.45° |
| converter (>690), n=77 | **+27.00** | −4.15 | +3.43 ± 3.66 | +5.62° |
| whole chain, n=729 | +1.20 | −1.91 | +2.47 ± 3.77 | +4.04° |

**Method validation:** the converter shows **+27 Å AXIAL** displacement — the power stroke, in the right place
and nowhere else. The decomposition detects real motion.

**The relevant row is the first.** `epsStroke` changes the BOND's rest geometry, so what counts is the tangential
motion of the residues actually holding the F8 spring: **+0.77 Å, eps_equiv 1.25°, 1.2 sigma — unresolved, and
3–5× short.**

**⚠ A SECOND NEAR-MISS, recorded like the first.** The whole-chain row reads **eps_equiv +4.04°**, landing
squarely in the required 3–7° band. **It is not the relevant quantity.** It is bulk motion of the myosin molecule
in the actin frame — note the pattern: contacts move 0.77 Å while the body moves 2.11 Å and the converter
3.43 Å, i.e. a head ROTATING ABOUT A STATIONARY ANCHOR, which is what a stroke looks like, not an anchor sliding.
Quoting +4.04° would repeat the §7.4 error exactly. Also: per-residue scatter (±3.3–3.8 Å) exceeds the means, and
residues in a folded domain are NOT independent, so naive sqrt(n) errors overstate significance badly on the bulk
rows.

### 7.4.2 The three structural channels, together

| channel | measured | vs the 3–7° requirement |
|---|---|---|
| actin interface rotation (§7.4) | **0.2°** | 15–35× short |
| actin protomer rotation (§7.4.1) | **0.33°** | 9–21× short |
| motor-side F8-anchor tangential (§7.4.1) | **1.25°** (1.2σ) | 2–6× short |

All three realisations of the mechanism — the interface rotating, actin twisting beneath the head, and the
motor's own anchor shifting — come in **3–25× under** what the model requires. The motor-side channel is the
closest, so jba's physical picture is the best-supported of the three, but it is still unresolved and still short.

**Corollary for effort allocation:** building a motor-side variant kernel would have REPRODUCED the 7.6 sigma
without testing anything new, because the bond is indifferent to which end acquires the offset. Half an hour of
structure work answered it instead. Do not build mechanism variants that a zero-rest bond cannot distinguish.

### Consequence for the standing claim

ε is a **required value, not a predicted one**. The defensible statement rests on the controls:

> The skew mechanism produces twirling that reverses under ε-sign reversal (7.6σ), is null at ε = 0, scales
> linearly in ε, and reaches the experimentally observed magnitude for a skew of a few degrees — **a magnitude
> not derivable from structure by this method, and therefore stated as required rather than predicted.**

The **sign** is in the same position and is worse off: handedness enters as `mirror·eps` and this computation
did not establish a barbed-ward convention, so **left-handedness is not predicted either.**

---

## 8. Twist disorder — contested, and QUENCHED not thermal

**Egelman's original ~10° was retracted by its own authors.** Egelman & DeRosier 1992, verbatim: *"the magnitude of
this disorder is about 5-6 degrees per subunit, which is less than the 10-12 degrees that we originally proposed."*

Current estimates span ~5×, i.e. ~25× in variance:

| source | σ (twist/subunit) | method |
|---|---|---|
| Egelman & DeRosier 1992; Orlova & Egelman 2000 | **5–6°** | cumulative angular disorder, EM |
| ice-thickness-controlled | **2.9°** (thin) vs **6.0°** (thick) | same formalism |
| Fujii 2010 | **~2.5°** | cryo-EM (unverified at source — quoted via Galkin) |
| Fineberg 2024 | **5.28 ± 0.26°** | myosin-5 stride fitting |
| Bibeau 2023 (derived) | **~0.8–1.1°** | from L_T = 8.2–12.9 µm; **our arithmetic, not their printed number** |

The **ice-thickness dependence (2.9° thin vs 6.0° thick) is a strong hint** that much of the classical "variable
twist" is specimen-flattening artifact. Honest position: somewhere in **1–5°**, classical value an upper bound.

**The modern refinement: polymorphism is CONFORMATIONAL, not SYMMETRIC.** Galkin 2010 sorted filaments into six
structural modes — but all converge to the same helical symmetry (canonical −166.6°/27.5 Å; SD2-disordered
−166.7°/27.6 Å; tilted −166.8°/27.6 Å — a spread of **0.2° in twist**). What varies is intra-subunit (~30° hinge
rotation, ~18° D-loop). **The mean lattice is much better defined than the 1982 framing implies.**

### 8.1 Three findings that bear directly on modelling

1. **Myosin orders its own track.** Stokes & DeRosier 1987: intersubunit variability ~12° bare vs **~2° decorated**.
   The target zone may be partly self-created by bound myosin.
2. **The disorder is QUENCHED, not fast-thermal.** Orlova & Egelman 2000, *"F-actin retains a memory of angular
   order"* — very slow torsional transitions, memory persisting **many seconds**. **An off-register head cannot wait
   for the filament to twist into register on a cross-bridge timescale.** Modelling twist as a fast fluctuation is
   wrong.
3. **Motors accommodate by varying stride.** Fineberg 2024: myosin-5 *"strides spanning 22 to 34 actin subunits …
   cumulative angular disorder in F-actin accounts for the observed proportion of each stride length … both motor
   and track are soft materials."*

### 8.2 Register survival

Cumulative disorder is a random walk, σ_n = σ₁√n; register is lost at ~±30°:

| σ₁ | subunits | filament length |
|---|---|---|
| 5° (classical) | ~36 | **~100 nm** |
| 2.9° (thin ice) | ~107 | **~290 nm** |
| 1.0° (Bibeau-derived) | ~900 | **~2.5 µm** |

**Our filaments are ~2.1 µm contour.** At the classical value the far end is decorrelated from the near end; at the
modern value it is marginal. **Implement as a static quenched Gaussian random walk on φ with σ₁ a parameter — not
a hardcoded rigid helix, and not a fluctuating one.**

**Apparently novel:** across 116 papers citing Steffen and 59 citing Wu, **no paper connects actin variable twist to
the target zone.** Given that 1–5°/subunit accumulates to the zone half-width over a few tens of nm, this
connection appears to be ours to make.

---

## 9. Experimental twirling comparator — REVISED

**Primary comparator, unchanged in value:** myosin II, **0.47 ± 0.19 µm left-handed, ≈2.1 turns/µm, n = 94**
(Beausang 2008). **Un-replicated** — forward-citation walks over Nishizaka (94 citing), Sase (131), Beausang (61),
Vilfan (25) found **no remeasurement of myosin II twirling pitch since 2008**.

### 9.1 Three caveats that change how we compare

**(a) Averaging convention differs by 2×, and we use the wrong one.** Lewis 2012 (*JGP* 139:101) on myoV-6IQ:
twirlers-only pitch **1.4 ± 0.13 µm**, but *"when the pitches of both the twirling and non-twirling filaments …
are considered, an average left-handed pitch of **2.7 ± 0.64 µm** is calculated."* Experimental numbers are
**twirlers-only**; our odd component is an **ensemble mean over all seeds**.

**(b) Most real filaments do not twirl; all of ours do.** Beausang 2008: *"only 20% clearly twirled, thus
indicating that azimuthal rotation is not necessary for gliding."* Our ε = 15° campaign: 3/3 seeds twirl, sign
consistent. Combined with (a), our apparent 1.9× over-rotation is an **understatement**.

**(c) No comparator exists for the myosin we model.** Axial rotation has been measured for skeletal muscle
myosin II and myosin V/VI. **Verified absent for non-muscle myosin IIA, IIB, smooth muscle, *Dictyostelium*, and
cardiac myosin.** SoftBox models non-muscle myosin II. Beausang 2008 is a different protein.

### 9.2 Two uncorrected systematics in the source data

- **Rhodamine-phalloidin actin changes its twist under excitation light** — Sanchez, Kulic & Dogic 2010 (*PRL*
  104:098103): *"the twist of neighboring monomers changes by approximately 0.26 degrees."* **Every twirling
  measurement in this literature uses rhodamine-labelled, phalloidin-stabilized actin under continuous TIRF
  excitation, and none corrects for it.**
- **Torsional rigidity is ~3× cation-dependent** — F-Ca²⁺ (8.5 ± 1.3)×10⁻²⁶ vs F-Mg²⁺ (2.8 ± 0.3)×10⁻²⁶ N·m²
  (Yasuda 1996). **Physiological Mg²⁺-actin is the softer form; most models adopt the Ca²⁺ value.** Check which
  we use.

### 9.3 Strict-tracking prediction — independent confirmation of our own null

Beausang 2008: *"If they followed the long-pitch actin helix, then myosin II would twirl actin with a short
right-handed pitch (74 nm)"* — observed is ~6× longer **and opposite in sign**. This independently confirms
`docs/twirl/SITE_LATTICE_TWIRL_NULL.md`, where rigid register-tracking (13.9 turns/µm, 72 nm) was excluded at 82σ.

### 9.4 A validation target we can actually hit

**Lewis 2012: truncating myoV's lever arm from 6IQ to 4IQ flips ~38 % of filaments to right-handed** (56 % left /
38 % right, vs 100 % left for native). This is the **only demonstrated molecular determinant of twirl handedness**
anywhere in this literature, and a natural target for a lever-arm model.

### 9.5 Firewall

**Trajectory chirality (deg/µm) ≠ axial twirling (µm/rev).** Separate literature (Pyrpassopoulos 2012; Lebreton
2018; Haraguchi 2026). Haraguchi **explicitly rules out axial rotation** as the source of path curvature
(*"tracked both the leading and trailing ends … both ends followed the same trajectory"*). Do not mix them.

### 9.6 Convention warning

Measurements give −166.3 to −166.8°/subunit; **Vilfan uses the 28/13 convention, −167.14°.** That ~1°/subunit
accumulates to **~13° over one 36 nm repeat** — enough to move which monomer sits at zone centre. Our −166.5° is on
the measurement side; the Vilfan lineage may not be. **Pick one and state it.**

---

## 10. Implied changes, by campaign

**None applied. Each needs its own gated increment with a byte-identity regression.**

| # | change | scope | risk |
|---|---|---|---|
| 1 | `actinMonoRadius` 2.70 → 2.75 nm; `TWIST_PER_MON_DEG` −166.5 → −166.6° | **repo-wide, every assay** | **HIGH** — re-baselines everything. Do LAST, or not at all. |
| 2 | lattice `every4+partner` → every-subunit | site enumeration; both campaigns | MEDIUM — changes site supply ~2× |
| 3 | `TZ_ALPHA` 6 → **3.42**, swept 2.5–8 | flag only | LOW |
| 4 | quenched twist disorder, σ₁ parameterised 1–5° | new; per-filament static draw | MEDIUM — new physics, needs its own gate |
| 5 | occupancy as a deliberate Vilfan axis | campaign design | LOW |
| 6 | report twirlers-only **and** ensemble pitch separately | analysis only | LOW |

**Suggested order: 3 → 6 → 2 → 5 → 4 → 1.** Items 3 and 6 are nearly free and immediately sharpen the standing
Vilfan claim. Item 1 is a repo-wide re-baseline for a ~2 % geometric correction and should be weighed against the
cost of invalidating existing fixtures.

---

## 11. What is NOT established

1. **Whether azimuthal restriction is necessary at all.** Never ablated in any model (§4.1).
2. **Twist disorder magnitude** — 1° vs 5°, ~25× in variance, unreconciled (§8).
3. **Whether the ancillary loop-3 contact matters for myosin II** — class-dependent (§2).
4. **Whether myosin binding changes actin twist** — actomyosin structures refine 0.09°/0.05 Å more negative than
   bare filaments from the same lab; within refinement noise. **Do not build this in.**
5. **The Vilfan density-independence vs our occupancy threshold** (§7.2).
6. **Whether any of this transfers to non-muscle myosin II** (§9.1c).

## 12. Sourcing caveats

Compiled 2026-08-27 from three parallel literature reviews plus direct inspection of deposited mmCIF
`_em_helical_entity` records. Helical parameters in §1 and the code facts in §7 were verified directly. The
following are flagged **unverified at source**: Fujii 2010's 2.5° (quoted via Galkin), von der Ecken 2016 and
Mentes 2018 full texts (paywalled; titles/DOIs/resolutions confirmed), Capitanio 2012. **No numeric claim in this
document rests on them.**

One number was explicitly rejected: a reported "rabbit skeletal myosin II 0.47 ± 8.8 deg/µm" from Haraguchi 2026
could not be verified, and its numeric coincidence with the 0.47 µm twirl pitch is suspicious. **Not quoted.** Also
note the Haraguchi bioRxiv preprint says CCW while the published *PNAS* version says CW — cite the published
version only.

---

## References

**Actin structure / helical parameters**
1. Egelman EH, Francis N, DeRosier DJ (1982). *Nature* 298:131–135. PMID 7201078.
2. Egelman EH, DeRosier DJ (1992). *Biophys J* 63:1299–1305. PMID 1477281.
3. Orlova A, Egelman EH (2000). F-actin retains a memory of angular order. *Biophys J* 78:2180–2185. PMID 10733996.
4. Galkin VE, Orlova A, Schröder GF, Egelman EH (2010). Structural polymorphism in F-actin. *NSMB* 17:1318–1323. doi 10.1038/nsmb.1930.
5. Fujii T, Iwane AH, Yanagida T, Namba K (2010). *Nature* 467:724–728. PMID 20844487.
6. Chou SZ, Pollard TD (2019). *PNAS* 116:4265–4274. doi 10.1073/pnas.1807028115. PDB 6DJM/6DJN/6DJO.
7. Oosterheert W, Klink BU, Belyy A, Pospich S, Raunser S (2022). *Nature* 611:374–379. doi 10.1038/s41586-022-05241-8.
8. Tanaka K, et al. (2018). *Nat Commun* 9:1860. doi 10.1038/s41467-018-04290-w. PDB 5YU8.
9. Stokes DL, DeRosier DJ (1987). *J Cell Biol* 104:1005–1017. PMID 3558475.
10. Bibeau JP, et al. (2023). Twist response of actin filaments. *PNAS* 120(4):e2208536120.
11. Fineberg A, … Sellers JR, Knight PJ, Kukura P (2024). *PNAS* 121(13):e2401625121. PMID 38507449.
12. Li D, Pérez MM, Zhang X, Li J, Jiang W (2025). Validation of helical symmetry parameters in EMDB. *bioRxiv* 2025.05.18.654508.

**Actomyosin interface**
13. Moore PB, Huxley HE, DeRosier DJ (1970). *J Mol Biol* 50:279–295.
14. Milligan RA (1996). *PNAS* 93:21–26. PMID 8552606.
15. Lorenz M, Holmes KC (2010). The actin–myosin interface. *PNAS* 107:12529–12534.
16. Behrmann E, et al. (2012). *Cell* 150:327–338. PMID 22817895.
17. von der Ecken J, et al. (2016). *Nature* 534:724–728. doi 10.1038/nature18295.
18. Fujii T, Namba K (2017). *Nat Commun* 8:13969. doi 10.1038/ncomms13969.
19. Mentes A, et al. (2018). *PNAS* 115:1292–1297. doi 10.1073/pnas.1718316115.
20. Gurel PS, et al. (2017). *eLife* 6:e31125. doi 10.7554/eLife.31125.
21. Pospich S, Sweeney HL, Houdusse A, Raunser S (2021). *eLife* 10:e73724.
22. Robert-Paganin J, et al. (2021). *Nat Commun* 12:1892. doi 10.1038/s41467-021-22093-4.
23. Klebl DP, et al. (2025). *Nature* 642:519–526. PMID 40205053.
24. Ferenczi MA, et al. (2005). *Structure* 13:131–141. PMID 15642268.

**Target zone / compliance**
25. Steffen W, Smith D, Simmons R, Sleep J (2001). Mapping the actin filament with myosin. *PNAS* 98:14949–14954. PMID 11734631.
26. Suzuki M, Ishiwata S (2011). *Biophys J* 101:2740–2748. PMID 22261063.
27. Wu S, et al. (2010). [electron tomography target-zone study].
28. Månsson A (2019). [S1-flexibility critique of target-zone geometry].
29. Adamovic I, Mijailovich SM, Karplus M (2008). *Biophys J* 94:3779–3789. PMID 18234833.
30. Brizendine RK, Anuganti M, Cremo CR (2021). *J Gen Physiol* 153(3):e202012751. PMID 33439241.
31. Squire JM, Harford JJ (1988). *J Muscle Res Cell Motil* 9:344–358. PMID 3065359.
32. Mijailovich SM, et al. (2016). MUSICO. *J Gen Physiol* 148:459–488. PMID 27864330.
33. Tanner BCW, Daniel TL, Regnier M (2007). *PLoS Comput Biol* 3(7):e115. PMID 17630823.
34. Arakelian C, et al. (2015). [motor-domain roll, 26 ± 9°].

**Twirling**
35. Tanaka Y, Ishijima A, Ishiwata S (1992). *BBA* 1159:94–98. PMID 1390915.
36. Nishizaka T, Yagi T, Tanaka Y, Ishiwata S (1993). *Nature* 361:269–271.
37. Sase I, Miyata H, Ishiwata S, Kinosita K Jr (1997). *PNAS* 94:5646–5650.
38. Rosenberg SA, Quinlan ME, Forkey JN, Goldman YE (2005). *Acc Chem Res* 38:583–593. PMID 16028893.
39. Beausang JF, Schroeder HW, Nelson PC, Goldman YE (2008). *Biophys J* — myosin II twirling, 0.47 ± 0.19 µm.
40. Sun Y, et al. (2010). *NSMB* 17:485. — myosin X, 1018 ± 37 nm.
41. Sun Y, Goldman YE (2011). *Biophys J* 101:1–11. PMID 21723809. — review table; **mixes twirl and motor path**.
42. Lewis JH, Beausang JF, Sweeney HL, Goldman YE (2012). *J Gen Physiol* 139:101–120. PMID 22291144.
43. Vilfan A (2009). Twirling of actin filaments by myosins. *Biophys J* 97:1130–1137. arXiv:0906.0784.
44. Sanchez T, Kulic IM, Dogic Z (2010). *PRL* 104:098103. PMID 20367015.
45. Yasuda R, Miyata H, Kinosita K Jr (1996). *J Mol Biol* 263:227. PMID 8913303.
46. Tsuda Y, et al. (1996). *PNAS* 93:12937.
47. Pyrpassopoulos S, et al. (2012). *Curr Biol* 22:1688.
48. Lebreton G, et al. (2018). *Science* 362:949.
49. Haraguchi T, et al. (2026). *PNAS* 123:e2508686123. PMID 41604264. **Cite published version (CW), not preprint (CCW).**
50. Báez-Cruz FA, Ostap EM (2023). *JBC* 299:104961.

---

## Related SoftBox documents

- `docs/twirl/SITE_LATTICE_TWIRL_NULL.md` — the ε = 0 control; strict register-tracking excluded at 82σ (§9.3)
- `docs/VILFAN_TARGET_ZONE_BINDING_AND_TWIRLING_FINDINGS.md` — Stage A null, α = 6, the ~105× phase decorrelation
- `docs/VILFAN_BROWNIAN_NOISE_ABLATION_FINDINGS.md` — Arm E coherence restored at avgBound 0.65
- `docs/attachment/SPARSE_LONG_PITCH_ACTIN_SITE_LATTICE.md` — the `every4` design doc; honest that it is *"an
  effective steric/accessibility abstraction"* asserting nothing about epitope location
- `softbox-vilfan-low-occupancy-brownian/docs/twirling/VILFAN_LOW_OCCUPANCY_BROWNIAN_VALIDATION.md` — the occupancy
  ladder (N_25 ≈ 21, N_operational = 29); **carries a STATUS: INCOMPLETE banner**
- `softbox-vilfan-target-zone/docs/twirling/vilfan_target_zone/TARGET_ZONE_BUILD_AND_VALIDATION.md` — deterministic
  hard-gate study, A_TZ = +0.171 ± 0.025 (6.8σ) at avg_bound ≈ 7.6
