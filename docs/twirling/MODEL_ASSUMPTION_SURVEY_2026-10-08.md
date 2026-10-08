# Model assumption survey: site-normal gliding / twirling assay (2026-10-08)

**Why.** In two weeks we found, one at a time, six assay-setup assumptions that had been carried over from older models without
anyone deciding them for this assay:
1. rigor rupture was off;
2. every motor shared one orientation;
3. rotational Brownian ran at half FDT;
4. the ATP binding constant was 4× too high;
5. the slab walls acted only at the filament centre;
6. the S2 departed horizontally, with its floor 50 nm below the filament's (about half the near-filament heads sat below the glass).

Each discovery cost days of runs. jba asked for one deliberate pass before more long runs. All six are fixed and are not repeated here.

**Scope.** `SiteNormalLongGlideHarness` → `ChiralSiteHarness.build()` → `ExplicitCompleteMatHarness` → `TwoBodyConverterMotor` (explicit
S2), GPU kernels in `MatSoaSlice` / `ChiralSiteSystem` / `TwoBodyBeamAnalyticGpu`, as run on 2026-10-08:
- d800, η 0.01 Pa·s, dt 1.25e-7 s;
- triad patch, ε −1.25°, random base azimuth;
- S2 at one surface with a 0.1×kb hinge.

**Method.** Four read-only code and doc reviews ran in parallel, one per area: lawn/motor geometry, filament/environment, motor
chemistry/kinetics, numerics/runner. The high-ranked claims were spot-checked by hand where noted. **Nothing was edited or run for this survey.**

**Provenance labels:**
- **LIT**: cited literature or structure.
- **DELIB**: a deliberate choice with a recorded rationale.
- **INH**: inherited from an older model or default, with no decision recorded for this assay.

**Relevance** is to gliding speed and to twirl pitch and handedness, at low and at saturating [ATP].

**Already stated limitations, not re-listed:**
- single-headed motors;
- rigid single-segment 2.1 µm rod;
- η = 0.01 Pa·s;
- uncalibrated `k_det`;
- sparse every-4th-subunit site lattice;
- GPU bound-branch CPU/GPU gate not green;
- heads have no surface exclusion of their own;
- S2 length 40 vs ~60 nm;
- dt 1.25e-7 under-binds ~25%;
- the base-hinge stiffness scan, deferred by jba.

---

## Ranked findings

### Tier 1: could decide the result

**1. Twirl handedness is an INPUT (stroke skew ε = −1.25°).** Verified by hand.
- **Magnitude.** It comes from cryo-EM: the motor-side residues holding the F8 bond shift tangentially by +0.77 ± 3.34 Å
  between the two states, which is ε_equiv **+1.25°** at **1.2σ, unresolved**.
  - Source: `docs/twirl/ACTIN_SITE_LATTICE_LITERATURE_BASIS.md:600-611`.
- **Sign.** It was *chosen* to give a left-handed roll.
  - Source: `JOURNAL.md:200`, "sign chosen LEFT-handed".
  - No one has established whether the structure's "+" corresponds to the model's "+".
- **Calibration.** The calibration of ε against Beausang's pitch is marked INVALIDATED (`JOURNAL.md:430`), yet −1.25° is still the
  production value (`SiteNormalLongGlideHarness:162`).
- **Consequence.** Current runs can measure twirl *magnitude and persistence at an imposed handedness*. They cannot test handedness.
  The July viscosity mirror control (sign flips with the mirror) was a different lineage and does not settle this.
- **Action (jba's call).** Either work out the sign mapping from the structure to the model's bind-azimuth convention, or treat
  handedness as a free input and run the +ε mirror as a long run.

**2. Rigor rupture now sets low-[ATP] rigor lifetime, and the mode was promoted on saturating-ATP evidence only.**
- **The rates.** Mode 1 (Guo & Guilford 2006 rigor fit): k0 = 140 /s at zero load; catch 0.9071 @ 1.5 nm, slip 0.0929 @ 0.5 nm.
  - Source: `ExplicitCompleteMatHarness.java:2381`, `ChiralSiteHarness.java:146-151`, `NucleotideCycleSystem.java:546-558`.
- **Where it was justified.** Promotion relied on rupture being <2% of detachments at saturating ATP.
- **What happens at 10 µM.**
  - It is the main exit from rigor: 2026-10-03 pilots measured 65–90% of detachments as rupture, with ~5 ms residence.
  - Without rupture, ATP binding alone would give ~42 ms residence.
- **Unverified.** The reviewer says unloaded rigor acto-S1 dissociation in bulk is far slower than 140 /s. If so, the zero-load end
  is an extrapolation of trap fits. Nobody has checked this against bulk rigor dissociation data yet.
- **Rebinding.** A ruptured head leaves nucleotide-free. Only primed ADP·Pi heads can bind (`ChiralSiteSystem.java:804`), so it
  waits ~42 ms for ATP, then ~10 ms of hydrolysis, before it can rebind. A real nucleotide-free head rebinds actin fast and strongly.
- **Combined effect.** Both effects lower rigor drag at low ATP, which could make low-ATP gliding too ATP-insensitive.
- **Stale doc.** `docs/twirling/LOW_ATP_GLIDING_TWIRLING_FINDINGS.md §2.8` ("a free nucleotide-free head is essentially
  unreachable") was written with rupture OFF and is stale.
- **Actions.**
  - Literature check on the zero-load rigor off-rate.
  - Decide whether nucleotide-free heads may bind.
  - The planned v-vs-[ATP] comparison with rupture on and off on matched lawns.
- **Catch-slip input (MED).** The catch-slip input is the instantaneous force (EMA α = 0), and the thermal F8 force noise is
  √(kT·k_F8) ≈ 2 pN.
  - That inflates the mean rupture rate by ~30%; the promotion regression recovered k0 146–173 vs 140.
  - Assisting (negative) loads are unclamped.
  - Action: histogram forceDotFil for rigor heads at rupture.

**3. Bound-head solve: one linear-implicit Newton step on a stiff mode, and an explicit filament roll reaction.**
- **The solve.** `exCounts[1] = maxIt = 1` (`ExplicitCompleteMatHarness:856`; `TwoBodyBeamAnalyticGpu:1446-1812`). This is
  deliberate per `docs/matsoa/EXPLICIT_MATS2SOLVE_CONTRACT.md:50`.
  - The bind-orientation stiffness kbind has a = k·dt/γ ≈ 1.7 at dt 1.25e-7. That figure is scaled from the η-audit's 3.46 at
    2.5e-7 and was not re-measured.
  - Linear-implicit Euler gives a stationary variance of (kT/k)·2/(2+a), so bound-head orientation fluctuations come out at
    **~0.54× equipartition**. I checked the algebra by hand.
  - Relaxation is a fixed fraction per step, so a Class-I spring behaves Class-II-like. The η-audit checked only stability.
- **The roll reaction.** The filament's roll reaction uses the start-of-step pose against the implicitly-solved head (siteCouple,
  `SiteNormalBindSystem.java:163-229`).
  - Per bound head a ≈ 0.02, so Σa ≈ 0.1–0.4 at 5–20 bound heads.
  - This is the rotational analogue of the 4b-iv Jacobi residual, acting on exactly the channel twirl is measured in.
- **Action.** A matched-lawn dt/2 (or maxIt = 2–3) comparison of roll per µm and of bound-angle variance against equipartition.

### Tier 2: could shift the numbers

**4. Cull radius sized for the clamped S2.**
- **The radius.** queryR = 30 + 40 + 10 = 80 nm, measured in xy from the motor's site (`MatSoaSlice.java:61-100`;
  `TwoBodyConverterMotor:4460-4464`). The 30 nm was derived for a fixed-anchor motor.
- **The concern.** With the soft hinge, the geometry reviewer estimates a head can sit ~95–105 nm from its site. Culled motors are
  completely frozen, so they never get the chance to reach the filament.
- **Why the existing gate misses it.** Gate C only checks where frozen heads already are.
- **Disagreement.** The numerics reviewer rated this LOW, but on outrunning and relaxation grounds, not reach.
- **Check RUNNING (2026-10-08).**
  - `-cullprobe` on a no-cull GPU run (d800, 2 mM, 6×4 lawn, 0.02 s): `RUN_LOGS/motor_audit/campaigns_2026-10/TWIRL_S2FIX/cullcheck/`.
  - It counts new binds by motors whose site is beyond queryR, and far-site heads within capture reach. Both must be 0.

**5. Actin radius 3.5 nm (`Constants.java:38-39`).**
- **Provenance.** INH from v1 `Env.java:530` (actinWidth 7 nm). F-actin is ~7–10 nm across, and the repo's own literature doc uses
  "~4 nm" (`ACTIN_SITE_LATTICE_LITERATURE_BASIS.md:211`).
- **Why it matters.** It is the stroke-to-roll moment arm, and roll drag γ_roll = 4πηR²L. If the twirl is drag-limited, turns/µm
  scale about 1/R, so 4–4.5 nm would give 15–30% less twirl. This is the reviewer's estimate, not measured.
- **Action.** A sensitivity check at R = 4–4.5 nm, once Tier 1 is settled.

**6. No near-wall hydrodynamic correction.**
- **What's there.** The bulk Tirado–García de la Torre rod drag (`DragTensorSystem.java:33-45`, INH verbatim from v1, uncited) is
  applied 10–60 nm above the lawn.
- **Estimate.** A slender-body near-wall estimate raises axial and lateral drag ~1.6–2.3×, with roll ≲2%. Given v ∝ η^−0.2, that is
  ~10–15% slower gliding and a correspondingly shorter pitch. Lawn crowding is also unmodelled.

**7. Unexamined constants and homogeneity.**
- **S2→lever joint rest angle `leverRest0`.**
  - Taken from the as-built pose (`TwoBodyConverterMotor:6866-6875`), so it is set by the 1.5 nm sag and the flat-S2 build.
  - Hand estimate ≈ 80°.
  - It sets how every lever presents relative to its S2. INH; MED–HIGH.
- **Stiffnesses.**
  - k_F8 = 1.0 pN/nm (v1 gliding match, "not ground truth").
  - kconv 128 and kbind 512 pN·nm/rad² (sweep picks, no provenance).
  - These set per-head roll torque and the rupture-force noise; none was examined in a rigor-dominated regime.
- **Homogeneous lawn.** One anchor height, identical S2/lever/stiffness for every motor; `applyS2Lawn` is off. The quenched-lawn
  gate therefore samples only position and azimuth.
- **Head dimensions.**
  - Lever 8 nm; head drag radius 4.6 nm; ellipsoid 9×5.5×4.5 nm.
  - INH from v1 and marked ASSUMED (`docs/MOTOR_PARAMETER_PROVENANCE_25C.md:93`). The head is about half the S1 motor-domain size.
- **Capture gates.**
  - Binding is a deterministic geometric AND with no finite k_on.
  - The site-capture 12 nm has no recorded basis.
  - Effective k_on is gate volume × sampling rate, which is the probable mechanism of the known dt under-binding.
- **Beausang 2008 conditions are not recorded in the repo.** Temperature, ionic strength, buffer viscosity (methylcellulose?) and the
  two-headed whole myosin are missing.
  - kT is 298.15 K; the ATP constant is quoted at 20 °C, 100 mM KCl.
  - Action: pull them from the paper and record them.

### Tier 3: housekeeping

- **Runner order mismatch** (`ExplicitCompleteMatHarness ~1551` vs `~1723`): GPU runs surfPrune before chem, CPU runs chem before
  surfPrune. It is still live (`JOURNAL.md:169`) and blocks a meaningful whole-step CPU/GPU gate.
- **χ clamp |χ| ≤ 89°** (`TwoBodyBeamAnalyticGpu:1809-1810`) has no hit counter.
- **The tilt solve's singular-pivot status `st` is never exported** (`TwoBodyBeamAnalyticGpu:1796`). The "0 solver failures" tally
  does not cover `matS2SolveStepTilt`.
- **Stale comments:**
  - `SiteNormalLongGlideHarness:594` says "NO stroke skew, NO randomized base azimuth".
  - `SiteNormalLongGlideHarness:134` says "NO cull on the device path".
- `ACTIN_SITE_LATTICE_LITERATURE_BASIS.md:725` says "SoftBox models non-muscle myosin II", but the rates are fast skeletal.
- Lawn footprint defaults to 7×1 µm (`-mx/-my`). The campaign scripts override it.

### Checked and fine (no action)

- **Cull outrun** is impossible: at 6 µm/s × dt the filament moves 0.75 pm/step, against a ~10 ms buffer crossing.
- **No non-FDT Brownian scaling** remains on rod, motor or roll.
- **Viscosity scaling** covers roll consistently.
- **Actin helix:** left-handed genetic, right-handed long-pitch, correct. The rise is 2.70 vs 2.75 nm, a deliberately deferred
  difference.
- **RNG keys:** no structured collisions.
- **`-resident/-leanreadback/-chunkocc`:** gated byte-identical.
- **Z-slab end walls:** no roll component.
- **12 pN cap:** off and not read on this path.
- **F10 alignment:** off.
- **Chain PAIRS:** inert with one segment.
- **Registry spring:** 0.

---

## Implications for the runs started 2026-10-08

`campaigns_2026-10/TWIRL_S2FIX/d800_atp10_s2fix` and `d800_atp2000_s2fix`:
- **Handedness:** both twirl at the handedness imposed by item 1.
- **Low-ATP arm:** its glide and twirl depend on item 2's rupture extrapolation and the no-rebind rule.
- **Engagement:** both depend on item 4 until the cull probe reports.
- **Comparisons:** the saturating vs low-ATP difference is a fair comparison at fixed model assumptions. Absolute pitch vs Beausang
  is not yet meaningful (items 1, 3, 5).

## Suggested order

1. **Item 4.** The probe is running now; if it fails, restart the d800 runs with a larger queryR.
2. **Items 1 and 2: jba's decisions.** Work out the ε sign mapping, or run a long mirror arm. Check the rigor off-rate literature
   first, then decide on nucleotide-free binding.
3. **Item 3.** Matched-lawn dt/2 roll and variance check.
4. **Items 5 and 6.** Sensitivity checks.
5. **Item 7 and Tier 3.** Record, then fix opportunistically.
