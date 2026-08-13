# Path-B actin-site accessibility — near-side / side / far-side telemetry

**Telemetry audit. No physics changed.** No capture gate, site selection, capture radius, lattice, occupancy
rule, motor geometry, skew, chemistry, stochastic draw, ordering, force law or timestep was altered. The one
code addition is a **default-off, trajectory-inert host-side reduction** over buffers the measurement loop
already reads back.

- **Worktree:** `/home/jba/Code/SoftBox` · **Branch:** `gpu-mat-bottlenecks-explicit-singlehead`
- **Base commit:** `3119000` (working tree carries this audit's telemetry + docs only)
- **Date:** 2026-08-11
- **Predecessor:** `docs/attachment/CANONICAL_ACTIN_ATTACHMENT_AUDIT.md` (hazard H1)
- **Raw telemetry:** `RUN_LOGS/attachment_audit/path_b_accessibility/`
- **Driver log:** `RUN_LOGS/access_audit_main.txt`

---

## 1. Executive verdict

**Classification: A1 — PRESENT BUT MOSTLY INERT (for torque), with a material occupancy caveat.**

Measured over **8 seeds × 20.00 ms** at a parameter-exact replica of the production η = 0.01 ladder cell
(48 arms' worth of bound-head sampling; **0 invalid, 0 solver failures** throughout):

1. **Far-side attachment is common, not rare.** Attachment events are **statistically indistinguishable from
   uniform** around the filament circumference (FAR bind fraction 0.323–0.344 vs the 1/3 null, all within
   ~1 SEM), and far-side sites hold **32.1 % of bound-head time**. Summed by hemisphere, the lawn-facing half
   carries only **1.12–1.15×** the occupancy of the away-facing half. The current model treats the top and
   bottom of the filament as very nearly equally accessible.
2. **Far-side bonds do NOT oppose the chiral torque — they carry the SAME sign.** All three accessibility
   classes contribute negative ε-odd axial torque, with per-seed sign concordance 6/8 for FAR against the
   total. **The predecessor audit's hazard-H1 speculation that far-side bonds "dilute" the torque and that
   measured τ_odd is "plausibly a lower bound" is REFUTED in direction** (§9.3).
3. **But their contribution is a minority and is itself unresolved.** FAR carries **17.7 %** of the ε-odd
   torque (ratio of means) while occupying 32.1 % of bound time — i.e. a far-side head is only **0.55×** as
   chirally productive as the population average, and its mean axial bond force is **~0** (1.1 × 10⁻¹⁴ N vs
   2.3 × 10⁻¹³ N for NEAR). The FAR-class odd torque is **1.28 σ** from zero at n = 8.
4. **Instantaneous-contribution accounting:** removing the FAR class retains **82.4 %** of the ε-odd torque
   (−1.347 × 10⁻¹⁷ vs −1.636 × 10⁻¹⁷ N·m). That ~18 % shift is **smaller than the ~40 % relative uncertainty
   on the total itself** (total = 2.53 σ).

**Therefore:** the missing far-side accessibility rule is **a modest realism issue for torque magnitude, and a
material one for occupancy**. It is **not** a load-bearing source of error in the sign, the mechanism, or the
existence of the twirling result. Twirling conclusions stand; **occupancy/duty-ratio statements inherit a real
~32 % geometrically-implausible population**.

**Does the site-first accessibility upgrade need to precede further mechanistic twirling work?** **No — not as
a blocker.** It should precede any *quantitative* claim about bound-head occupancy or about the absolute
magnitude of the chiral torque. See §14.

---

## 2. Geometry and classification definition

### 2.1 The measured substrate geometry (Phase 0 — verified, not assumed)

From the built production scene (`ChiralSiteHarness -access-static`, 12-segment chain, density 400 heads/µm²):

```
eup (assay substrate normal, read from the scene)   = (0.000, 0.000, 1.000)
mean motor anchor z                                 = -0.0099 µm
motor body slot-0 z (MotorStore assembly)           = -0.0100 µm   (LaserTrapHarness.MANCHOR_Z = -0.0500 µm)
ideal head-site plane z                             = +0.0000 µm
mean filament centreline z                          = +0.0000 µm
actin radius R_actin                                =  0.0035 µm
```

⇒ **motors sit BELOW the filament and `eup` points AWAY from the lawn.** Therefore a site whose outward radial
normal satisfies `n·eup < 0` faces the lawn. **CONFIRMED — not assumed.**

Note the filament centreline sits at the *same height* as the motors' ideal head-site plane (both z = 0); the
lawn-facing surface of the actin is at z = −3.5 nm and the far surface at +3.5 nm. The head therefore does not
approach from far below the filament — it approaches at axis height. This is why the far-side question is not
obviously self-answering.

### 2.2 The classification metrics

Both metrics are reconstructed with **exactly** the expression `CrossBridgeSystem.bondForcesSurface` and
`ChiralSiteSystem.tangentialForce` use, so the reported normal **is** the bond's own moment-arm direction —
nothing is independently re-derived (`ChiralSiteSystem.accessMetrics`).

```
nHat  = cos(bindAzim)·segY + sin(bindAzim)·segZ ,  segZ = segU × segY     (outward radial material normal)
pHat  = normalize(eup − (eup·u) u)                                         ("away from the lawn", ⊥ filament axis)
qHat  = u × pHat
cosB  = nHat·pHat                    site ORIENTATION relative to the lawn
beta  = atan2(nHat·qHat, cosB)       beta = 0 ⇒ points away from the lawn; ±180° ⇒ points at the lawn
aApp  = nHat·(xF8 − xSite)           head's APPROACH side of the site's own tangent plane (nm)
roll  = atan2(segY·qHat, segY·pHat)  the filament's own material roll phase in the same frame
```

`cosB` and `aApp` are **kept separate and never collapsed**: the first says which way the site faces, the
second says which side of that site the head is actually on.

### 2.3 Preregistered class boundaries

Chosen before any data was taken, and deliberately symmetric so the three classes cover **equal 120° arcs of
the filament circumference** — the uniform-azimuth null is therefore exactly **1/3 : 1/3 : 1/3**:

| class | condition | meaning |
|---|---|---|
| **FAR** | `cosB > +0.5` (\|β\| < 60°) | site normal points away from the lawn |
| **SIDE** | `\|cosB\| ≤ 0.5` (60° ≤ \|β\| ≤ 120°) | site faces laterally |
| **NEAR** | `cosB < −0.5` (\|β\| > 120°) | site normal points toward the lawn |

The **continuous** distribution is retained as an 18-bin β histogram (20° bins) plus a 16-bin `aApp` histogram,
so no conclusion depends on the threshold.

Why not tune the threshold to the `every3` lattice: with rise 8.10 nm and −166.5°/monomer, the azimuthal
advance per site is 3 × (−166.5°) = **−499.5° ≡ −139.5°**, whose orbit modulo 360° closes only after 80 sites
(4.5° resolution). Site azimuths along a 2.1 µm filament are therefore effectively **dense and uniform** on the
circle, so no lattice-aligned binning is preferable to equal arcs.

### 2.4 Sign verification of the approach metric (Phase 3)

`ChiralSiteHarness -access-static`, deterministic, no trajectory advanced:

| fixture | cosB | β (deg) | class | n·eup (raw) | aApp (nm) | |
|---|---|---|---|---|---|---|
| site faces AWAY (+z) | +1.000000 | +0.00 | FAR | +1.000000 | +1.0000 | PASS |
| site faces SIDE (+y) | +0.000000 | −90.00 | SIDE | +0.000000 | +1.0000 | PASS |
| site faces LAWN (−z) | −1.000000 | +180.00 | NEAR | −1.000000 | +1.0000 | PASS |
| site faces SIDE (−y) | −0.000000 | +90.00 | SIDE | −0.000000 | +1.0000 | PASS |
| head placed ON the filament axis | — | — | — | — | **−3.5000** | PASS (= −R_actin exactly) |

**Sign established: `aApp > 0` ⇔ the head approaches the site from OUTSIDE the filament (the physically
accessible direction); `aApp < 0` ⇔ the head is on the interior side of the site's tangent plane.**

> **Correction to the predecessor report.** `CANONICAL_ACTIN_ATTACHMENT_AUDIT.md` §13 item 3 and §14.5
> proposed the accessibility condition as `n_site·(x_F8 − x_site) < 0`. **That sign is wrong** — it selects
> exactly the inaccessible half-space. The correct condition is **`> 0`**. Both places have been corrected in
> that report, with a pointer here. The error was in a *proposal*, not in any executed code, so no result is
> affected.

---

## 3. Inertness validation (Phase 2)

`run_gpu_monitored.sh run_chiral_sites.sh -access-inert -gpu -eta 0.01 -steps 4000 -seed 101` — the same arm
and seed run twice, telemetry OFF then ON, on the **device-resident production graph**.

All 25 pre-existing scalar outputs **bit-identical** (printed to 17 significant figures), per-block arrays
identical, `invalid = 0`, `solverFail = 0` in both:

```
avgBound   16.969000000000000  =  16.969000000000000
glide     -16.010615560743542  = -16.010615560743542
omegaFit  -74.298712662671730  = -74.298712662671730
tau      4.3258570531154610e-23 = 4.3258570531154610e-23
turns     -0.043609304939456690 = -0.043609304939456690
tauPos   4.6637333505558950e-20 = 4.6637333505558950e-20
tauNeg  -4.6594074935027790e-20 = -4.6594074935027790e-20
… (bindsPerStep, detachPerStep, strokesPerStep, tauPull, tauDrag, vFilMean, misAbs,
   coherence, rollR2, ft, fax, cancel, tauPerHead, nTauPos, nTauNeg, omegaLegacy,
   meanResidenceSteps, per-block arrays: all identical)
```

**Independent consistency check:** the telemetry's own attachment-event count equals the pre-existing
`bindsPerStep` total exactly (**15 = 15**), confirming the telemetry counts the same events the harness
already counted rather than a differently-defined set.

**PHASE 2: PASS — the telemetry is trajectory-inert.** This is structural, not incidental: the reduction runs
after the physics for the step is complete, reads only already-transferred buffers, writes only into local
accumulators, draws no random numbers, and adds no device task or transfer.

### 3.1 One measurement caveat (stated, bounded, not corrected)

`outGeom` (the head's `xF8`) is written by `matBeamGeom` at the *start* of the step, while `filCoord/filUVec/
filYVec` are read *after* integration. `aApp` therefore mixes head and filament poses one timestep apart. At
the production step (dt = 2.5 × 10⁻⁷ s) that is a filament roll of **1.75 × 10⁻⁵ rad (0.001°)** and an axial
travel of **0.001 nm** — five orders of magnitude below the 12 nm capture radius and the 3.5 nm site offset.
The class assignment (`cosB`, from `bindAzim` and the material frame) is unaffected by this at all, since both
are read post-step.

---

## 4. Existing-data reuse (Phase 4)

**Checked before launching anything.** The existing Path-B records cannot answer the question:

| source | contents | sufficient? |
|---|---|---|
| `RUN_LOGS/chiral_sites/lowatp/*.tsv` (low-[ATP] ladder) | aggregated key/value scalars: `tau`, `omega`, `glide`, `avgBound`, per-head torque decompositions by **nucleotide state / stroke phase / puller-dragger** | **no** — no per-head site, azimuth or normal |
| `*.trace.tsv` | 5-column coarse time trace (`tS, meanRoll, glideProj, cumBinds, cumStrokes, cumDetach, cumBoundSteps`) | no |
| `*.nbhist.tsv` | histogram of instantaneous bound-head count | no |
| `*.nested.tsv` | nested-window prefix trace | no |
| `RUN_LOGS/chiral_sites/s25powered/etamap_*` (viscosity ladder) | same aggregated schema | no |
| `threejs_conv_twirl_*/frame*.json` | rendered geometry only (`segments`: endpoints+radius; `myosins`: endpoints+radius+colour), and a reduced 26-motor viewer scene | no |

**No existing record carries `bindSite`, `bindAzim`, the filament material frame, or per-bond torque.** The
per-head decompositions that *do* exist were reduced on the fly and the site information was never retained.
⇒ **short telemetry runs are required; nothing was rerun that could have been read.**

---

## 5. Measurement configuration

The audit deliberately reproduces a **production η = 0.01 eta-map cell parameter-for-parameter**, verified
against the stored record header `RUN_LOGS/chiral_sites/s25powered/etamap_s8000_e0100_101.tsv`:

```
# … density=400 eta=0.01 dt=2.5E-7 steps=80000   equil=0.25 blocks=5
```

| knob | audit | production ladder |
|---|---|---|
| lattice | `every3` (rise 8.10 nm), `SITE_MODE = 2` | same |
| surface bond | ON, `R_actin` = 3.5 nm | same |
| head roll DOF | ON, registry `K = 0` | same |
| site exclusivity | ON | same |
| actin-side skews | `εbind = εstroke = 0` | same |
| converter skew | ±15° (and 0 control), **linear progress ramp** | same (`CONV_RAMP_ARM = RAMP_LINEAR`) |
| filament | 12 segments, Brownian ON | same |
| density | 400 heads/µm² (⇒ 1600 heads) | same |
| viscosity / dt | η = 0.01 Pa·s, dt = 2.5 × 10⁻⁷ s | same |
| duration | 80 000 steps = **20.00 ms**, equil 0.25 | same |
| rigor rupture | OFF (this lineage never sets it) | same |
| runner | GPU device-resident `buildGlidingGraph(prod)`, no CPU fallback | same |
| seeds | 2 (101, 102) | 8–24 |

Arms: **ε = 0** (achiral control), **ε = +15°**, **ε = −15°** — matched seeds, so the ε-EVEN and ε-ODD
components can be separated exactly as every twirling claim in this project does.

**Executed:** seeds 101–102 first (`RUN_LOGS/access_audit_main.txt`), then — because the ε-odd torque *changed
sign between those two seeds* and no attribution was supportable — extended to seeds 103–108
(`RUN_LOGS/access_audit_ext.txt`), giving **n = 8**, the same seed count at which the production eta-map
resolved Ω_odd at η = 0.01. **24 arms total, 0 invalid, 0 solver failures.** Pooled tables:
`RUN_LOGS/attachment_audit/path_b_accessibility/pooled_n8.txt` (`scripts/access_pool.py`).

Per-arm sanity against production: the ε = +15° arms give glide ≈ −4.20 to −4.47 µm/s, reproducing the
published saturating-ATP η = 0.01 value of −4.19 µm/s, and Ω in the −20 to −80 rad/s band the ladder reports.

---

## 6. Site-orientation distribution (the continuous readout)

Pooled n = 8 occupancy, 18 × 20° bins in β (β = 0 ⇒ site normal points **away** from the lawn; β = ±180 ⇒
points **at** the lawn). Uniform null = 5.556 % per bin.

| β (deg) | ε=0 | ε=+15° | ε=−15° |
|---:|---:|---:|---:|
| −170 | 4.130 | 3.910 | 4.169 |
| −150 | 4.410 | 4.273 | 4.290 |
| −130 | 4.870 | 5.065 | 4.539 |
| −110 | 5.557 | 6.367 | 5.611 |
| −90 | **7.635** | **8.379** | **7.889** |
| −70 | **7.323** | **7.628** | **7.725** |
| −50 | 5.955 | 5.860 | 6.028 |
| −30 | 4.943 | 5.112 | 5.196 |
| −10 | 5.178 | 5.103 | 4.835 |
| +10 | 5.100 | 5.286 | 4.687 |
| +30 | 5.236 | 5.142 | 5.019 |
| +50 | 5.649 | 6.046 | 5.870 |
| +70 | **7.153** | **7.110** | **7.140** |
| +90 | **7.851** | 6.829 | **7.946** |
| +110 | 5.826 | 5.297 | 5.744 |
| +130 | 4.571 | 4.375 | 4.494 |
| +150 | 4.324 | 4.208 | 4.223 |
| +170 | 4.287 | 4.011 | 4.596 |

**The distribution is bimodal at the LATERAL azimuths (β ≈ ±70…±90°) and is depressed at BOTH poles** — the
lawn-facing pole (β ≈ ±170°: 3.9–4.6 %) and the away-facing pole (β ≈ ±10°: 4.7–5.3 %) are *both* below the
uniform null.

| arm | away-from-lawn hemisphere | toward-lawn hemisphere | ratio |
|---|---:|---:|---:|
| ε = 0 | 46.54 % | 53.46 % | 1.149× |
| ε = +15° | 47.29 % | 52.71 % | 1.115× |
| ε = −15° | 46.50 % | 53.50 % | 1.151× |

⇒ **only a ~1.12–1.15× preference for the lawn-facing half of the filament.**

**Mechanistic cause, identified from the scene construction (not inferred from the histogram).** Each motor's
anchor is placed so that the **reference-pose F8 point lies at z = 0 — the filament CENTRELINE**, not at the
filament's lower surface (`buildGlide2D`: `site = {ax, ay, 0}`, `A[m] = site − dworld0 − L_B·û_B`; measured
mean anchor z = −9.9 nm, ideal head-site plane z = 0, filament centreline z = 0). The head therefore
approaches at **axis height**, which makes the two lateral surface points nearest and leaves the top and
bottom of the filament **equidistant**. The lateral bimodality and the near-absent near/far asymmetry are both
direct consequences of that placement. This is a *scene* property, not a gate property.

---

## 7. Attachment-event distribution

Mean ± SEM over 8 seeds (seed = the independent unit); uniform-azimuth null = 0.3333 in every class by
construction of the 120° bins.

| arm | NEAR | SIDE | FAR |
|---|---|---|---|
| ε = 0 | 0.3291 ± 0.0061 | 0.3268 ± 0.0110 | **0.3441 ± 0.0078** |
| ε = +15° | 0.3372 ± 0.0109 | 0.3398 ± 0.0158 | **0.3230 ± 0.0190** |
| ε = −15° | 0.3388 ± 0.0105 | 0.3353 ± 0.0096 | **0.3258 ± 0.0079** |

**Attachment is statistically indistinguishable from uniform around the circumference** — every class in every
arm lies within ~1.4 SEM of 1/3. There is no detectable accessibility selection at the moment of attachment.

---

## 8. Bound-occupancy distribution

| arm | NEAR | SIDE | FAR |
|---|---|---|---|
| ε = 0 | 0.2652 ± 0.0075 | 0.4136 ± 0.0063 | **0.3212 ± 0.0064** |
| ε = +15° | 0.2583 ± 0.0159 | 0.4155 ± 0.0111 | **0.3262 ± 0.0128** |
| ε = −15° | 0.2632 ± 0.0125 | 0.4202 ± 0.0122 | **0.3165 ± 0.0097** |

Occupancy is **SIDE-enriched** (0.414–0.420 vs 0.333, ≈ 7 σ) and **NEAR-depleted** (0.258–0.265, ≈ 6–9 σ);
**FAR sits at ≈ 0.32, i.e. essentially at the uniform null**. Mean residence is nearly class-independent
(NEAR 0.59, SIDE 0.65, FAR 0.62 ms), so the occupancy pattern is set by attachment azimuth, not by
class-dependent lifetime.

**Headline number: 32.1 % of all bound-head time in the production Path-B configuration is spent on sites
whose outward normal points into the away-from-lawn 120° cone.**

---

## 9. Torque contribution by accessibility class

ε-odd (chiral) axial torque, `τ_odd = ½(τ(+ε) − τ(−ε))`, seed-paired, seed as the independent unit, n = 8:

| class | τ_odd (N·m) | σ | per-seed sign |
|---|---|---:|---|
| NEAR | −5.896 × 10⁻¹⁸ ± 3.921 × 10⁻¹⁸ | 1.50 | 6/8 negative |
| SIDE | −7.578 × 10⁻¹⁸ ± 1.682 × 10⁻¹⁸ | **4.51** | **8/8 negative** |
| FAR | −2.888 × 10⁻¹⁸ ± 2.252 × 10⁻¹⁸ | 1.28 | 6/8 negative |
| **TOTAL** | **−1.636 × 10⁻¹⁷ ± 6.467 × 10⁻¹⁸** | **2.53** | 6/8 negative |

The total's 2.53 σ at n = 8 is consistent with the production eta-map's 2.29 σ for Ω_odd at η = 0.01 and n = 8
— i.e. this replica is neither noisier nor cleaner than the campaign it mirrors.

**9.1 All three classes carry the same-signed chiral torque.** No class opposes the others.

**9.2 Far-side bonds are under-productive per head.**

| class | occupancy share | τ_odd share | τ_odd per bound sample (N·m) | relative productivity |
|---|---:|---:|---:|---:|
| NEAR | 0.2607 | 0.3603 | −3.797 × 10⁻²⁴ | 1.382 |
| SIDE | 0.4183 | 0.4632 | −3.042 × 10⁻²⁴ | 1.107 |
| **FAR** | **0.3210** | **0.1765** | **−1.511 × 10⁻²⁴** | **0.550** |

A far-side head generates **roughly half** the chiral torque of an average bound head, and its **mean axial
bond force is ~0** (+1.117 × 10⁻¹⁴ N, versus +2.255 × 10⁻¹³ N NEAR and −2.063 × 10⁻¹³ N SIDE). Far-side bonds
are mechanically the most inert population in the model.

**9.3 Retraction of the predecessor audit's H1 direction.**
`CANONICAL_ACTIN_ATTACHMENT_AUDIT.md` §14.3 recorded, as an *unquantified inference*, that far-side bonds
"would **dilute** the coherent chiral torque … so the measured τ_odd is plausibly a **lower bound**."
**That inference is wrong in direction.** Far-side bonds carry the same sign as the rest, so they *add* to the
measured torque; removing them lowers it. The correct statement is that τ_odd is, if anything, a mild
**over**-estimate. The reason the original guess failed is now clear and is a design feature, not an accident:
`ChiralSiteSystem` expresses the skew in the **local site frame**, so — as its own class javadoc states — the
circumferential component is same-signed "regardless of which side of the filament the motor sits on". The
telemetry **confirms that design intent held in a dynamic gliding assay.**

**9.4 Why n = 2 was not enough, stated plainly.** The two-seed pilot gave ε-odd totals of −2.67 × 10⁻¹⁷ and
**+2.49 × 10⁻¹⁷** — opposite signs — and a FAR share of 36.4 %. At n = 8 the FAR share is **17.7 %**. The
n = 2 value was noise and is reported here only to document why the extension was run.

---

## 10. Offline counterfactual torque accounting (Phase 7)

**BOOKKEEPING ONLY.** These sums remove a class's *instantaneous contribution* from the measured trajectory.
They do **not** predict what the dynamics would have been had those bonds never formed: the freed heads would
have rebound elsewhere (attachment is reach-limited, and the pool is far from saturated), the filament would
have followed a different trajectory, and occupancy would have redistributed. Read as attribution, not
prediction.

| quantity | value (N·m) |
|---|---|
| `τ_odd` ALL | −1.63621 × 10⁻¹⁷ ± 6.467 × 10⁻¹⁸ |
| `τ_odd` FAR REMOVED (`near+side`) | −1.34743 × 10⁻¹⁷ ± 5.437 × 10⁻¹⁸ |
| `τ_odd` NEAR ONLY | −5.896 × 10⁻¹⁸ ± 3.921 × 10⁻¹⁸ |
| **retained fraction with FAR removed** | **0.824** |

Answering the four Phase-7 questions directly:

- *Are far-side heads contributing torque with the opposite sign?* **No** — same sign, 6/8 seed concordance.
- *Are they diluting the native chiral torque?* **No.**
- *Are they reinforcing it?* **Yes, weakly** — 17.7 % of the total, itself only 1.28 σ from zero.
- *Is their net contribution negligible?* **For torque, nearly so**: an 18 % shift, well inside the ~40 %
  relative uncertainty on the total. **For occupancy, no**: 32 % of bound-head time.

Achiral controls for scale: the ε-EVEN background is −8.109 × 10⁻¹⁸ ± 2.826 × 10⁻¹⁸, and the independent
ε = 0 arm gives −1.943 × 10⁻¹⁸ ± 8.694 × 10⁻¹⁸ (consistent with zero). The ε-EVEN background is about half
the ε-odd signal — the known reason every twirling claim here uses the ± difference.

---

## 11. Puller/dragger cross-tab and roll-phase dependence

**11.1 Axial role is independent of accessibility class.** In every arm and every class the puller fraction is
**0.517–0.521** and the dragger fraction 0.479–0.483 — flat to <1 % across NEAR/SIDE/FAR. Far-side attachment
is therefore **not** preferentially associated with a particular axial mechanical role. This is consistent
with, and independent of, the project's existing finding that chiral torque is decoupled from axial role.

**11.2 Roll-phase dependence — present, and necessarily post-acceptance.** Class occupancy fractions vary
with the filament's own material roll phase (8 × 45° bins; e.g. at ε = 0, FAR ranges 0.256–0.357 across
bins). **This cannot be capture accessibility**: the capture gate never reads `filYVec` (established in
`CANONICAL_ACTIN_ATTACHMENT_AUDIT.md` §5 and unchanged). It arises entirely from **post-acceptance lattice
snapping and site occupancy** — the rolling lattice changes *which discrete site* is nearest to an
already-accepted head. Stated explicitly so it is not mistaken for an accessibility effect.

---

## 12. A0–A5 classification

**A1 — PRESENT BUT MOSTLY INERT**, with an explicit occupancy qualifier.

- Not **A0**: far-side attachments are not rare (32 % of bound time, ~33 % of events).
- Not **A2**: they do not oppose the dominant chiral torque — same sign, 6/8 concordance.
- Not **A3**: they reinforce it only weakly (17.7 %, 1.28 σ) and are the *least* productive class per head
  (0.55×) with ~zero mean axial force.
- Not **A4**: they do not contribute a large fraction of the torque; the 18 % accounting shift is inside the
  total's own uncertainty. **However**, they *do* contribute a large fraction of **occupancy**, which is the
  one channel where the A4 concern partially applies (§13).
- Not **A5**: the question is resolved at n = 8 for the quantities that matter.

---

## 13. Publication impact

**Twirling / chiral-torque results: qualitatively reliable; torque magnitude provisional at the ~20 % level.**
The sign, the mirror reversal, the local-frame mechanism and the existence of the effect are untouched by this
defect — far-side bonds are same-signed, so they cannot have manufactured the result. An accessibility upgrade
would be expected to *reduce* τ_odd by roughly 18 % on this accounting, which is within the existing
seed-to-seed uncertainty of every published Path-B torque number.

**Occupancy-based results require an explicit caveat.** Any statement of bound-head count, duty ratio, or
occupancy-versus-density — notably `docs/twirling/LOW_ATP_DENSITY_OCCUPANCY.md`, where SoftBox occupancy is
compared against the Vilfan weak-depletion bracket — currently includes a **~32 % population of far-side
attachments that a planar-lawn assay would not permit**. The comparison is to a model (Vilfan) whose entire
premise is surface-restricted accessibility, so this is the one place the defect is directly load-bearing:
the reported N_b is inflated relative to a surface-accessible-only count. That does not invalidate the
D1 conclusion (gliding persists below the bracket) — removing far-side heads moves N_b *down*, i.e. further
below the bracket, strengthening rather than weakening it — but the number must be quoted with the caveat.

**Path-A gliding-saturation results are out of scope here and are not affected**: Path A binds on the
centreline, where "near" and "far" do not exist as distinct states.

---

## 14. Proposed next step (PROPOSAL ONLY — not implemented)

The A1 verdict does **not** trigger the mandatory Phase-11 upgrade (that is reserved for A2/A3/A4). The
recommendation is therefore **conditional and modest**:

**Do not block further mechanistic twirling work on this.** The mechanism, sign and mirror behaviour are
unaffected. Continue the twirling programme.

**Do fix it before any quantitative occupancy claim is published**, and prefer the smallest geometry-derived
change. Two candidates, in increasing cost:

1. **Scene-level (cheapest, and it addresses the actual cause).** The near/far symmetry originates in the
   motor anchor placement putting the reference-pose F8 point at the filament **centreline** (z = 0). Placing
   it at the filament's **lower surface** (z = −R_actin) would make the lawn-facing sites genuinely nearest
   without touching any gate, kernel or parameter. This is a scene-geometry correction, not a new physical
   rule — but it **does** re-baseline recruitment and must be treated as such.
2. **Gate-level (the §13 item-3 proposal of the predecessor audit, sign now corrected).** Add
   `n̂_site·(x_F8 − x_site) > 0` — the head must approach the site from outside the filament — evaluated at
   the *selected site*, which requires moving site selection inside the gate. **Note from §4 of this report:
   this test must be applied at the moment of CAPTURE, not to bound heads**, because on a bound head the F8
   spring drives `aApp` to ≈ 0 with ~2 nm thermal spread (≈ 50 % of bound samples sit formally negative). A
   naive standing application of the same inequality to bound heads would detach half the population.

Observables that would need re-baselining if either is adopted: **binding probability, avgBound, attachment
flux, occupancy-versus-density, and τ_odd/Ω_odd magnitude** (glide velocity is expected to move least, since
far-side heads carry ~zero mean axial force). No new fitted parameter is introduced by either option.

**Not recommended:** adding a Vilfan angular hazard, or any new fitted angular gate, to solve this. The defect
is geometric and has a geometric fix.

---

## Appendix — reproduction

```
./scripts/run_chiral_sites.sh -access-static                                  # Phase 0/3 (CPU, no trajectory)
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -access-inert -gpu -eta 0.01 -steps 4000 -seed 101
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -access-audit -gpu -eta 0.01 -steps 80000 -seeds 2 -seed 101 -density 400
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -access-audit -gpu -eta 0.01 -steps 80000 -seeds 6 -seed 103 -density 400
python3 scripts/access_pool.py RUN_LOGS/attachment_audit/path_b_accessibility/per_seed_s101_102.tsv \
                               RUN_LOGS/attachment_audit/path_b_accessibility/per_seed_s103_108.tsv
```

Code added (all telemetry-only, default-off): `ChiralSiteSystem.accessMetrics` (read-only helper),
`ChiralSiteHarness.ACCESS_TELEM` + `AccessTel` + the in-loop reduction + `-access-static` / `-access-inert` /
`-access-audit`, and `scripts/access_pool.py`. **No kernel, no TaskGraph, no parameter, no default changed.**
