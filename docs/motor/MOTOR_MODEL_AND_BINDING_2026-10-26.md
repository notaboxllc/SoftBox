# Single-headed myosin motor and its actin-binding scheme in the SoftBox gliding assay (2026-10-26)

This describes the model as it is. Judgements about whether it is biologically right are in a separate document
(`BINDING_VS_LITERATURE_2026-10-26.md`), so a reviewer can form an independent view first.

The model comes from reading the code (`~/Code/SoftBox-dock-gpu/softbox/`, uncommitted working copy). File:line
references are to that tree. Values marked *[derived]* are arithmetic from code constants, not printed by the code.

## 1. The assay being simulated

- In-vitro gliding assay: one actin filament (2.106 µm, 12 rigid segments of 64 monomers, semiflexible chain) over a
  lawn of **single-headed** myosin II motors anchored to a flat surface.
- Lawn: 6 × 4 µm, **400 heads/µm²** → 9600 motors, positions uniform random, each motor's base orientation
  (azimuth) random.
- Solvent viscosity η = 0.01 Pa·s (~10× water; chosen for cost), T = 298 K (kT = 4.116 pN·nm), dt = 1.25e-7 s.
- Saturating ATP (ATP binding 2e4 /s, see §4).
- The filament is confined between the lawn plane and a ceiling 70 nm above it; its height is otherwise free.
- Everything (filament, every motor) is Brownian at the fluctuation–dissipation level.

Command (one lawn):
```
SiteNormalLongGlideHarness -run -nohires -eta 0.01 -dt 1.25e-7 -convaz-nocomp -stroke-skew -1.25 -no-rupture
  -matx 6.0 -maty 4.0 -density 400 -swivel-dock -motor-steric -head-floor -stroke-window -115,-45 -f8-tip-fix
  -head-chain -gpu -devicecull -resident -leanreadback -seed <lawn>
```

## 2. Motor construction (base → tip)

| part | model | values | provenance (as stated in code) |
|---|---|---|---|
| Base E | fixed point on the lawn plane | — | — |
| Base joint | bending spring on S2 elevation only; azimuth free ("swivel") | 0.1 × kb = 7.2 pN·nm/rad | unmeasured; literature (Walker 1985, Hvidt 1982) only brackets it |
| S2 | explicit beam, 4 segments × 10 nm = **40 nm**, Brownian nodes | stretch EA ≈ 4.2 nN (420 pN/nm per segment), bend EI = 7.2e-28 N·m² (Lp ≈ 175 nm) *[derived]*; node drag 6πη·5 nm | EA/EI from MD (Adamovic et al. 2008); length "conditionally frozen" |
| S2 floor | one-sided spring keeping S2 nodes above the lawn plane | 20 pN/nm | — |
| S1–S2 junction | bending spring between last S2 segment and lever | kb = 72 pN·nm/rad; rest angle ≈ 82° *[derived]* | inherited |
| Lever | rigid rod from S2 tip P to converter point C | **8 nm** | — |
| Pliant hinge | out-of-plane bend β of lever relative to the converter axis | κ = 23.4 pN·nm/rad² | Billington et al. 2014 JMB 426:894 (detached S1) |
| Head roll about lever | free | 0 | — |
| Converter | in-plane angle θ between lever and head axis, harmonic about a nucleotide-set target θ_s | kc = **128 pN·nm/rad²** (thermal σ ≈ 10° *[derived]*) | prototype sweep pick (Exp 3C), no literature value |
| Motor domain | sphere (drag radius 4.6 nm, steric radius 3.0 nm); F8 binding point 7 nm from C along the head axis a | — | — |

**State and integration.** Each motor is integrated as one linear-implicit system: 4 S2 nodes (12 DOF) plus 5 rotations
(lever 2, head 3), with exact rigid-body angular mobility and correlated Brownian noise (Cholesky of the mobility).
State vectors: head axis **a**, converter axis **c** (fixed in the head, ⟂ a), lever direction **L**; stroke axis
**s** = ±(c × a).

**Sterics.** Head sphere (3.0 nm), lever capsule (1.5 nm) and S2 capsules (1.0 nm) repel the actin capsule (3.5 nm) at
20 pN/nm on overlap (the two nearest actin segments, within 75 nm). Head centre ≥ 3.0 nm and C ≥ 1.5 nm above the
lawn plane.

**Stroke.** On the bound ADP·Pi → ADP transition the converter target switches θ_s = −115° → −45° (a 70° lever swing;
lever-head collinear at −90°). Stroke energy ½·kc·(70°)² ≈ 23 kT *[derived]*. Detached heads never stroke (Pi release
off actin = 0). `-stroke-skew -1.25` rotates the bound site's azimuth by −1.25° at the stroke (chirality; twirl study).

**Cross-bridge (bound).**
- F8 bond: zero-rest-length linear spring, **k_F8 = 1.0 pN/nm**, from the head F8 point to the actin site point
  (on the actin surface, radius 3.5 nm). Provenance: matched to the v1 gliding model, "not ground truth".
- Stereospecific dock: the bound head's frame (a, c, s) is pulled to a target frame set by the site
  (a → −n_site, s → barbed direction u) with energy k_B(1 − cos φ_rot), **k_B = 512 pN·nm/rad²** (thermal σ ≈ 5°
  *[derived]*). Provenance: prototype sweep pick (Exp 3C), no literature value.

## 3. Actin binding sites

- Two-strand left-handed helix, 13/6 (−166.5° per monomer, 2.7 nm rise per monomer along the filament axis).
- **Only every 4th monomer on each strand is a site** ("every4"): 10.8 nm axial spacing per strand, +54° azimuth step
  per site, plus the partner-strand monomer (+2.7 nm, −166.5°). ≈ 390 sites on the filament *[derived]*; about 6.7 per
  36 nm half-repeat.
- Each site has an outward normal n_site (from the segment's rolling material frame) and a point 3.5 nm out along it.
- No face of the filament is blocked explicitly; accessibility comes from the gates below and from sterics.

## 4. Nucleotide cycle (one uniform draw per motor per step)

| transition | bound | detached |
|---|---|---|
| NONE → ATP (ATP binding; release from actin happens here) | 2.0e4 /s | 2.0e4 /s |
| ATP → ADP·Pi (hydrolysis / re-priming) | 100 /s | 100 /s |
| ADP·Pi → ADP (Pi release = **the stroke**) | 1.0e4 /s | 0 |
| ADP → NONE (ADP release) | 1.0e3 /s × g(F) | 1.0e3 /s |

- g(F) = 0.92·exp(−F·2.5 nm/kT) + 0.08·exp(+F·0.4 nm/kT), F = bond force along the filament (Guo & Guilford 2006
  constants).
- Only **ADP·Pi** heads may bind. After a release, 10 µs refractory.
- Rigor rupture and the 12 pN break cap are off.
- Unloaded bound lifetime ≈ 0.1 + 1.0 + 0.05 ≈ **1.15 ms** *[derived]*; a released head needs ~10 ms (hydrolysis) before
  it can rebind.
- These rates are part of a frozen "canonical chemistry" set (an internal no-tuning rule: chemistry is not adjusted to
  match a gliding number).

## 5. Binding scheme

**Capture is deterministic.** There is no on-rate and no random draw: a head binds in the first time step at which it
passes every gate below simultaneously. All stochasticity comes from Brownian motion of the motor and the filament.
On passing, the head is **not moved**; the F8 spring and dock pull it in.

Applied every step, in this order (thresholds from the named tolerance set `dBindNm=3.0, psiDeg=25, phiDeg=25,
thetaDeg=20, preloadPn=2.0, energyKt=15.0`, commented "named, explicit acceptance tolerances (NOT tuned to a canonical
binding rate)", TwoBodyConverterMotor.java:2206).

| # | gate | condition | threshold | where |
|---|---|---|---|---|
| 0 | cull (computational) | motor base within 110 nm (xy) of the filament | 110 nm | MatSoaSlice:75 |
| 1 | eligibility | active, unbound, not refractory, nucleotide = ADP·Pi | — | ChiralSiteSystem:804 |
| 2 | segment | only the segment nearest the F8 point is searched | — | CSS:809 |
| 3 | site candidates | 7 sites × 2 strands around the F8 foot | 14 | CSS:845 |
| 4a | accessibility (g8) | F8 point on the outside of the site (n·(x_F8 − x_site) > 0) | — | CSS:875 |
| 4b | distance (g0) | \|x_F8 − x_site\| < 3 nm | 3 nm | CSS:878 |
| 4c | preload (g4) | k_F8·d < 2 pN, i.e. **d < 2 nm** (stricter than g0) | 2 pN | CSS:879 |
| 4d | orientation (g1′) | angle between head axis a and −n_site < 25° | 25° | CSS:881–892 |
| 4e | winner | nearest site (3-D) among those passing 4a–4d | — | CSS:893 |
| 5a | converter (g3) | \|θ − θ_s\| < 20° (θ_s = −115° in ADP·Pi) | 20° | CSS:955 |
| 5b | polarity (g9) | angle between stroke axis s and the barbed direction < **25°** | 25° | CSS:958–962 |
| 5c | energy budget (g5) | [½kc(θ−θ_s)² + ½k_B·θ_bind² + ½k_B·mis²] / kT < 15, mis = angle(s, barbed) | 15 kT | CSS:944–970 |
| 6 | occupancy | a site holds one head; established bonds win, then lower motor id | — | CSS:297 |

Notes on the table:
- Gates 5a–5c are evaluated **only on the single winning site** from 4e. If it fails, other passing sites are not tried.
- The polarity gate's default is 90°. With `-swivel-dock` the code sets it to the site-normal tolerance (25°) when no
  explicit `-polarity-gate-deg` is given (SiteNormalLongGlideHarness:664). That 25° descends from an earlier
  head-angle gate (`psiDeg`).
- Energy budget coefficients *[derived]*: orientation and dock terms 62 kT/rad² each (11.8 kT at 25°); converter term
  15.6 kT/rad² (1.9 kT at 20°). With the converter term at 0, the budget allows θ_bind² + mis² ≤ (28°)², so the two 25°
  limits cannot both be used at once.
- For scale *[derived]*: the F8 spring's own thermal spread is √(kT/k_F8) ≈ 2.0 nm per axis, so the 2 nm capture sphere is
  about one thermal σ; a bond in thermal equilibrium would sit inside it only ~19 % of the time.
- Retired gates (still in code, inactive here): head-side height, lever angle |φ − φ_pre| < 25°, ψ-vs-actin angle.
- No per-gate rejection counters exist in the production kernels.

## 6. What the model produces at 400 heads/µm² (3 lawns × 0.1 s, GPU)

| lawn | mean heads bound | P(none bound) | attachments in 0.1 s | mean bound time | velocity (µm/s) |
|---|---|---|---|---|---|
| 02 | 0.31 | 0.76 | 39 | 810 µs | −1.44 |
| 03 | 0.13 | 0.88 | 21 | 629 µs | −0.83 |
| 04 | 0.27 | 0.82 | 24 | 1133 µs | −1.45 |

Velocity is not resolved at this run length (±~1 µm/s). Most of the filament's motion is Brownian while unbound.

Earlier versions of the same harness at the same density, before the stereospecific dock and the 25° polarity gate
(polarity 90°, no dock), had ~3.0 heads bound and ~3,600 attachments/s.

## 7. Earlier internal findings on binding (2026-10-25)

- A single-motor search fixture (`SiteFrameStrokeAudit -stroke-fixture -fix-search`, CPU, old head model before the head
  chain) found that in this swivel-dock scene **a lone motor never binds**: every geometric candidate was refused by the
  15 kT budget (orientation at the bound-state stiffness plus the dock energy) and by the polarity gate.
- Removing both (`-capture-lit`) let motors bind, but a head captured off-axis was then pulled by the bound springs into
  a gimbal-locked pose (χ ≈ −89°) within ~40 steps and made no stroke. Not re-tested with the head chain.
- A two-stage weak → strong design was drafted (`docs/motor/WEAK_BINDING_DESIGN.md`), and a CPU prototype
  (`softbox/WeakBindSystem.java`) exists on that single-motor fixture only. It is **not** in the gliding path described
  above.

## 8. Questions for a reviewer

1. Is a deterministic, all-gates-at-once capture an acceptable representation of myosin attachment, or should there be
   a weak-binding state and/or an attachment rate?
2. Is each threshold (2 nm, 25°, 25°, 20°, 15 kT) defensible from structural or kinetic data, and should any be a soft
   (Boltzmann-weighted) criterion rather than a hard cut?
3. Is a polarity restriction at the binding step biologically justified for a single head tethered by S2?
4. Are k_B = 512 and kc = 128 pN·nm/rad² consistent with measured motor-domain/actin and converter compliance?
5. Is an every-4th-monomer site lattice adequate, given the capture sphere is 2 nm?
6. Anything else in the construction (S2, hinge, lever length, chemistry) that would bias attachment.
