# Vilfan-COMPLETE reference — build and validation

**Branch** `feature/vilfan-complete-reference` (child of `feature/vilfan-graded-binding`)
**Worktree** `../softbox-vilfan-complete`
**Runner** CPU only — no CUDA, no TornadoVM, no `TaskGraph`, no device context. ≤ 3 logical cores, `nice -n 17`.
**Status** **COMPLETE — verdict A** (S14).

---

## S1. Executive conclusion

> ### **A. COMPLETE VILFAN REFERENCE REPRODUCED ON PAPER AND NATIVE LATTICES**

A standalone, additive, default-off reconstruction of Vilfan (2009)'s **complete** model — his
lattice, his attachment law, his four-state kinetic cycle at his published rates, his conjugate force
and torque law, his quasi-static equilibrium and his exact Gillespie scheme, sharing **no** code path
with any SoftBox motor — reproduces the published mechanism at the published favourable operating
point, and the mechanism **survives transfer to SoftBox's native actin lattice**.

**At the paper-exact point** (`α = 4`, `k_D/k_A = 0.1` ⇒ `[ATP] = 1 µM`, 4 matched seed pairs,
≥5 µm analysed travel and ≥9.8 turns per arm):

| quantity | measured | published |
|---|---|---|
| twirl handedness | **left-handed**, 4/4 seeds | left-handed |
| pitch `λ` | **−479 ± 14 nm** | "a minimum of about 400 − 500 nm" |
| inverse pitch at 1 µM ATP | **−2.09 µm⁻¹** | Fig 3A peak ≈ 2–2.5 µm⁻¹ |
| attachment position `⟨x_A⟩` | **+1.770 ± 0.014 nm** (58.97 % before the zone centre) | Fig 5B peak ≈ +2.0 nm at `k_D/k_A ≈ 0.1` |
| duty ratio | 0.735 | Fig 3C ≈ 0.7–0.9 in this range |
| `Ω_odd` / `Ω_even` | **−0.547 ± 0.014** / **0 exactly** | — |

All **ten** Stage-4 success criteria are met (S7). The `k_D/k_A` sweep reproduces the published
non-monotone pitch — a minimum at `k_D/k_A ≈ 0.1`, rising at both lower and higher detachment rates
(S11). Force and torque balance hold to `10⁻¹⁰ pN` / `10⁻¹¹ pN·nm` across 17.4 M equilibrations, with
zero solver failures (S13).

**Five findings worth carrying forward.**

1. **The depletion mechanism is isolated and confirmed.** A no-depletion shadow control — same moving
   landscape, motors never removed from the available pool, no physical force — collapses the
   attachment bias from `+1.770 nm` to `−0.063 nm` and the before/after split from 58.97/41.03 % to
   49.58/50.42 %. **96.5 % of the effect is depletion/history, not a static asymmetric site
   distribution** (S10).
2. **The native SoftBox lattice does not break it.** Changing *only* `a: 2.75 → 2.70 nm` and
   `ϑ₀: −167.14° → −166.5°` leaves `Ω_odd` at `−0.553 ± 0.020` vs `−0.547 ± 0.014` (ratio **1.011**)
   and the pitch at `−478` vs `−479 nm`. The reason is that `⟨x_A⟩/L` is invariant (0.04598 vs
   0.04625): the bias is a fixed **fraction of the zone period**, and the angular bias depends only on
   that ratio (S12).
3. **Vilfan's Eq (6) as printed carries a sign error.** The `+Θ(L/π)` form is required by his own
   `ẋ = −(v − (L/π)ω)`, makes Eq (8) reduce exactly to the site azimuth, and matches the exact
   discrete lattice. With the printed sign the paper's central claim reads backwards (S3.5).
4. **Mirror antisymmetry here is exact, not statistical.** The model is noise-free and its hazard
   depends on `θ²`, so mirroring maps the system onto itself bit-for-bit under `Θ → −Θ`. Success
   criteria 1–4 are therefore met *structurally*; the mirror test's real value is as a correctness
   gate, and the substantive result is that `Ω_odd` is non-zero, correctly signed and of the
   published magnitude (S6.3, S8). Any later study that restores noise loses this exactness.
5. **The previous hybrid's tiny chiral bias is now quantitatively explained.** At `k_D/k_A = 18.2` the
   *complete* model's own attachment bias is **5.9 %** of its peak and its twirl per micron **8.7 %**
   of peak. The hybrid was sampling the mechanism where Vilfan's model says it is nearly extinguished
   — and separately, its torque came from a non-conjugate mechanical channel (S11, S14.3).

**One number is duration-limited and carries no conclusion:** the *rotation* at `k_D/k_A = 18.2`,
where the pitch is ≈7 µm and 2 of 4 seeds hit the 60 µm travel cap (S11). Its attachment statistics,
on which the sweep conclusions rest, are unaffected.

**No SoftBox realism was restored and the ladder was not started.** The ordered restoration ladder for
the next study is recorded in S16, with rung 2 (rotational drag) and rung 3 (roll Brownian noise)
flagged as the two that can plausibly break the mechanism.

**Runner:** CPU only — no CUDA, TornadoVM, `TaskGraph` or device context was created. 92 arms,
17.4 M Gillespie events, ≈14 CPU-minutes, ≤3 cores at `nice -n 17` (S13.2).

---

## S2. Why this study exists

The previous study (`VILFAN_GRADED_BINDING_VALIDATION.md`) transplanted **only** Vilfan's attachment
law, Eqs (1)–(2), onto SoftBox's frozen explicit-S2 motor. That hybrid answered the question

> *Vilfan attachment law + SoftBox motor* → does a target-zone bias and a mirror-odd twirl appear?

This study builds the other object:

> *Vilfan attachment law + Vilfan kinetics + Vilfan mechanics* → does the **published** mechanism work?

The central limitation the hybrid could not escape is recorded here explicitly, because it is the
reason the two studies cannot be compared arm-for-arm:

> **Vilfan's `k_D` is a direct state-transition rate** — the rate constant out of the rigor state,
> `k_D = k_D⁰·[ATP]` with `k_D⁰ = 5 µM⁻¹s⁻¹`. In the hybrid there was no such state and no such
> rate, so `k_D/k_A` had to be inferred from SoftBox's *measured* bound residence time. That
> mapping is **not made anywhere in this reference.** Here `k_D` is a rate in a rate matrix, the
> `k_D/k_A` axis is set by construction, and the published Fig 5B/Fig 6 abscissa is directly
> addressable.

---

## S3. Stage 0 — primary-paper reconstruction audit

**Source.** Andrej Vilfan, *Twirling motion of actin filaments in gliding assays with
non-processive myosin motors*, Biophysical Journal **97**(4):1130–1137 (2009); preprint
**arXiv:0906.0784v1** (4 Jun 2009). The full preprint was retrieved and read directly for this
study — Model definition, Simulation results, the seven-step algorithm, Analytical approximation,
Discussion, Appendix, Tables I and II, and all figure captions. Every entry below is sourced to the
paper. **Nothing is inferred from the existing SoftBox implementation where the paper specifies it,
and nothing was chosen after seeing a twirling result.**

### S3.1 The eighteen audit points

| # | question | answer, with source |
|---|---|---|
| 1 | motor states and allowed transitions | Four: **detached → pre-powerstroke → post-powerstroke → rigor → detached**, a single irreversible cycle (Fig 1B; "essentially containing a detached state, a bound pre-powerstroke state, a bound post-powerstroke state with ADP and a bound rigor state"). No reverse transitions ("We neglect ... the existence of the reverse transitions"). |
| 2 | exact event rates | detached → pre-PS at the **site-specific** `k_A^i` of Eq (2); pre-PS → post-PS at `k_PS = 10000 s⁻¹`; post-PS → rigor at `k_−ADP = 1000 s⁻¹`; rigor → detached at `k_D = (5 µM⁻¹s⁻¹)[ATP]`. All **strain-independent** ("We neglect the strain dependence of those rates"). Table I. |
| 3 | are detached motors continuously attachment-competent? | **Yes, unconditionally.** The algorithm's step-2 sum assigns `Σ_i k_A^i(j)` to *every* motor `j` that is detached, with no eligibility gate, no refractory period and no geometric veto. |
| 4 | candidate site enumeration | The sum runs over "Binding sites" with **no stated cutoff**. The physical cutoff is the Gaussian longitudinal factor: `σ_axial = √(k_BT/K) = √(4.14/0.5) = 2.877 nm ≈ 1.046 subunits`. **Configurable, convergence-gated** (gate B3). |
| 5 | may two motors occupy one site? | **No.** The Discussion lists, among the deviations of the *analytics* from the *simulation*, "the neglected discrete nature of binding sites and of the fact that each binding site can only be occupied by one head at a time" — i.e. the simulation carries both, the analytics neither. *Inferred, strongly implied; configurable (`singleOccupancy`), sensitivity reported in S13.* |
| 6 | motor-anchor placement | "Distribute the positions of myosin motors `x_M^j` **randomly** along the distance covered by the actin filament, with an **average** linear density `ρ`" (algorithm step 1). "Average density" + "randomly" ⇒ a homogeneous **Poisson process**. *Minimal interpretation; `uniformN` and `regular` kept as configurable sensitivity controls.* |
| 7 | boundary conditions / motor lawn | A **static one-dimensional field** spanning the whole distance travelled (`X_max = 1000 µm` in the paper). **No periodic re-entry and no motor recycling** — step 1 lays the field down once, before the run. Motors are "distributed randomly directly under the gliding actin filament"; the reduction from the true 2-D lawn is derived in the Appendix (Eqs 20–23) and shown to leave Eqs (1)–(2) as the correct 1-D form. |
| 8 | actin lattice coordinates | Site `i` has axial coordinate `X + i·a` and azimuth `Θ + i·ϑ₀`, with `a = 2.75 nm` and `ϑ₀ = −(13/28)×360° = −167.14°` (Model definition; Table I). Filament length `l = 5.5 µm` ⇒ **2000 sites**. |
| 9 | attachment energy | Eq (1): `U_i = ½K(X + i·a − x_M^j)² + ½K_ϑ(Θ + i·ϑ₀ + 2πn)²`, `n` chosen so the angle lies in `[−π, π]`. |
| 10 | bound-head axial force | Eq (4): `F^j = K(x_M^j + δ − X − i·a)`. |
| 11 | bound-head axial torque | Eq (4): `M^j = −K_ϑ(Θ + i·ϑ₀ + 2πn)`. |
| 12 | power-stroke displacement | `δ = 0` pre-powerstroke; `δ = d = 8 nm` **post-powerstroke and in rigor** (text under Eq 4). It enters **only** the force — the stroke has, by construction, **no azimuthal component**. That is the whole point of the paper. |
| 13 | force and torque equilibrium | Eq (5): `ΣF^j = 0`, `ΣM^j = 0`. "We assume that the filament position is quickly equilibrated after each step, therefore it always fulfills ..." — **quasi-static, no drag, no inertia, no Brownian term.** |
| 14 | Gillespie event selection | Steps 2–4: `k_total = Σ` over all motors of the state-appropriate rate; `Δt = k_total⁻¹ ln(1/r)`; one transition chosen with probability `rate / k_total`. **Exact continuous-time Monte Carlo.** |
| 15 | update of X and Θ after an event | Step 5: "Change the state of the chosen motor and **update the filament position X and angle Θ according to Eq. (5)**." |
| 16 | initial conditions / equilibration | Step 1: `X = 0`, `Θ = 0`. Motor states are not stated; **all-detached** is the minimal reading. No equilibration protocol is given; this study **preregisters a discarded warm-up** (S6.2). |
| 17 | definitions of the reported quantities | See S3.3. |
| 18 | parameters per figure | Fig 3 (simulation): full model, `α ∈ {4,6,8}`, `[ATP]` swept 0.01–100 µM, `X_max = 1000 µm`. Fig 5 (analytics): `k_D/k_A ∈ {0.01, 0.1, 1, 10}`, `α = 4`. Fig 6: analytics vs simulation **with `k_−ADP = ∞`**, `α ∈ {4,6,8}`, `k_D/k_A` 0.01–10. Table I supplies everything else. |

### S3.2 Table I, transcribed

| symbol | value | meaning |
|---|---|---|
| `k_A` | 50 s⁻¹ | attachment rate (to a site needing no elastic distortion) |
| `k_PS` | 10000 s⁻¹ | power-stroke rate |
| `k_−ADP` | 1000 s⁻¹ | ADP release rate |
| `k_D` | (5 µM⁻¹s⁻¹)·[ATP] | detachment rate |
| `d` | 8 nm | power-stroke size |
| `K` | 0.5 pN/nm | myosin longitudinal stiffness |
| `K_ϑ` | `α k_BT` | myosin angular stiffness |
| `α` | 4, 6, 8 | dimensionless angular stiffness (measured lower estimate 3.7, ref. Steffen 2001) |
| `a` | 2.75 nm | axial rise per actin subunit |
| `ϑ₀` | −167.14° | azimuthal advance per subunit |
| `l` | 5.5 µm | actin filament length |
| `ρ` | 20 µm⁻¹ | 1-D myosin density |
| `k_BT` | 4.14×10⁻²¹ J = **4.14 pN·nm** | thermal energy |

### S3.3 Definitions of the reported quantities

| quantity | definition used here | source |
|---|---|---|
| attachment position | `x` at the instant of binding, the motor root relative to the centre of the nearest target zone, wrapped into `[−L/2, L/2)`; recorded **before** re-equilibration | Eq (6), Table II (`⟨x_A⟩`) |
| target-zone centre | the axial position at which the effective long-pitch groove azimuth is `0 mod 2π`, i.e. where subunits point at the surface | Fig 1D, Fig 2 |
| angular mismatch | `θ = Θ + i·ϑ₀ + 2πn ∈ [−π,π]` for the site actually bound; recorded at the instant of binding | Eqs (1), (4) |
| longitudinal mismatch (strain) | `ξ_i = x_M − X − i·a` — note this is the **negative** of the bracket inside Eq (1); Eq (4) then reads `F = K(ξ + δ)` | analytical section, Table II |
| pitch | `λ = 2πX/Θ`, µm per turn. **Negative = left-handed.** | algorithm step 7 |
| velocity | `v = X/t` (here: over the analysed window, endpoint and least-squares estimates both reported) | algorithm step 7 |
| handedness | sign of `Θ̇`; Fig 3 plots `λ⁻¹` and states "Negative signs denote left-handed rotation" | Fig 3A |

### S3.4 Ambiguities, and the minimal interpretation taken

Each was fixed **before** any twirling run, is configurable, and was **not** revisited afterwards.

| # | ambiguity | minimal interpretation taken | configurable as |
|---|---|---|---|
| V-1 | no candidate-site cutoff is stated | truncate at `±W` subunits about the motor's nearest site, `W` chosen by a convergence gate | `window` (production `W = 12`) |
| V-2 | "randomly ... with an average linear density" | homogeneous Poisson process | `placement` |
| V-3 | single occupancy only implied | enforced (excluded from `k_total` itself, not rejected afterwards) | `singleOccupancy` |
| V-4 | initial motor states unstated | all detached | — |
| V-5 | no equilibration/warm-up protocol | discard a preregistered warm-up (S6.2) | `warmupUm` |
| V-6 | Eq (5) is degenerate when **no** head is bound | freeze `X` and `Θ` (zero force ⇒ no quasi-static displacement); occurrences counted and reported | — |
| V-7 | `n` in Eqs (1)/(4) is a function of `Θ`, so Eq (5) is implicit in `Θ` | take the branch assignment **continued from the current `Θ`** — the local energy minimum the filament is already in — by fixed-point iteration | `equilTolRad`, `equilMaxIter` |
| V-8 | `L` in Eq (6) ("period/half-pitch of the actin superhelix") is not given a number | computed **from the lattice**: `L = π / \|dφ/ds\|`, with `dφ/ds` the azimuthal advance per unit axial length along the two-start long-pitch strand, `= wrapπ(2ϑ₀)/(2a)` | derived, printed per run |

### S3.5 A correction to the source: Eq (6) carries a sign error

**This is a finding, not an interpretation.** Eq (6) is printed as

```
x = x_M − X − Θ(L/π) + nL                       (6, as printed)
```

Three independent checks say the sign of the `Θ` term must be **+**:

1. **Vilfan's own `ẋ`.** Two paragraphs below Eq (6) he writes `ẋ = −(v − (L/π)ω) = −c`.
   Differentiating the printed Eq (6) gives `ẋ = −(v + (L/π)ω)`. Only the `+` form reproduces his `ẋ`.
2. **Eq (8) then becomes an identity.** With the `+` form,
   `x − ξ_i = (x_M − X + ΘL/π) − (x_M − X − i·a) = i·a + ΘL/π`, so
   `ϑ_i = (π/L)(x − ξ_i) = Θ + (π/L)(i·a)` — **exactly the site azimuth** in the continuous-groove
   approximation. With the printed `−` form Eq (8) is not the site azimuth and Eq (12) changes sign.
3. **The exact discrete lattice agrees with the `+` form.** Advancing `Θ` moves the set of
   surface-facing subunits in `+x`; this was checked numerically against the exact lattice
   (`-landscape`, S5.4).

The `+` form is implemented. Consequence, and it is the one that matters for reading Fig 5B:
**`ẋ = −c < 0`, so a motor traverses a zone from positive `x` to negative `x`, and positive
`⟨x_A⟩` means binding happens BEFORE the zone centre.** With the printed sign the published claim
would read backwards. Eq (18) then gives `ω = −k_D⟨ϑ_A⟩ < 0` for `⟨x_A⟩ > 0` — left-handed, as the
abstract states.

This correction affects only the **reporting coordinate** `x` and the analytical cross-check.
**The simulation itself never uses Eq (6), Eq (8) or `L`** — it evaluates Eqs (1), (2), (4), (5) on
the exact discrete lattice.

### S3.6 Preregistered analytic predictions

Derived from Eqs (10), (16), (17), (18), (19) **before running**, at the primary operating point
(`α = 4`, `k_D/k_A = 0.1` ⇒ `k_D = 5 s⁻¹` ⇒ `[ATP] = 1 µM`), using `L = 38.50 nm`,
`K_ϑ' = (π²/L²)K_ϑ = 0.1279 pN/nm`, `K_ϑ'/(K+K_ϑ') = 0.2037`, and `⟨x_A⟩ ≈ 2.0 nm` read from Fig 5B:

| quantity | preregistered prediction | source |
|---|---|---|
| `⟨ξ_A⟩` | `0.2037 × 2.0 = +0.41 nm` | Eq (16) |
| `v` | `k_D(d + ⟨ξ_A⟩) = 5 × 8.41 = 42 nm/s = 0.042 µm/s` | Eq (17) |
| `ω` | `−k_D·0.7963·(π/L)·⟨x_A⟩ = −0.70 rad/s` | Eq (18) |
| `λ` | `2πv/ω ≈ −0.38 µm/turn` | Eq (19) |
| peak total hazard | `(k_A/2a)√(2πk_BT/(K+K_ϑ')) = 58.5 s⁻¹` | Eq (10) |

These are held fixed for comparison in S7.

---

## S4. What is Vilfan and what is only SoftBox infrastructure

**Vilfan — every physical element:**
the lattice (`a`, `ϑ₀`, site coordinates); the attachment energy Eq (1); the site-specific
attachment rate Eq (2); `α = K_ϑ/k_BT` Eq (3); the four states and their allowed transitions;
`k_A`, `k_PS`, `k_−ADP`, `k_D`, `d`, `K`, `α`, `l`, `ρ`, `k_BT` at their Table I values; the
conjugate force and torque Eq (4); the state-dependent `δ`; the quasi-static equilibrium Eq (5);
the exact Gillespie scheme; the static randomly-placed 1-D motor field; the definitions of pitch,
velocity, attachment position and handedness.

**SoftBox — infrastructure only, no physics:**
the git repository, branch and worktree; `javac`/`java` and the `scripts/` launcher convention;
the `RUN_LOGS/` atomic-record and resumable-campaign convention; the report location. Two constants
are *read* from SoftBox to define the Stage-6 transfer target — `TWIST_PER_MON_DEG = −166.5°`
(`RollSpringHarness`, `GlidingHarness`, `HelicalSurfaceTwirlHarness`, `ExplicitCompleteMatHarness`)
and `Constants.actinMonoRadius = 0.0027 µm = 2.7 nm` — and nothing else.

**Explicitly NOT used, anywhere in this reference** (each was checked to be absent from the two new
source files): SoftBox's nucleotide cycle; catch/slip detachment; rigor rupture; explicit-S2
mechanics; converter or lever mechanics; converter skew; the surface bond; canonical geometric
binding gates; nearest-site selection; `ChiralSiteSystem`; `TwoBodyConverterMotor`;
`CrossBridgeSystem`; `MotorStore`; any Brownian force; any finite-`dt` chemistry approximation; any
drag tensor; any GPU type.

**Default-off identity.** The study adds **two new source files and two new scripts** and modifies
**no existing file** (S13.3), so a run that does not name `-vilfan-complete` is byte-identical to
the `feature/vilfan-graded-binding` baseline by construction. The harness additionally refuses to
execute at all unless `-vilfan-complete` is passed.

---

## S5. Implementation

### S5.1 Files

| file | role |
|---|---|
| `softbox/VilfanCompleteSystem.java` | the model: lattice, Eqs (1)(2)(4)(5), the four-state cycle, the Gillespie loop, observables |
| `softbox/VilfanCompleteHarness.java` | CLI, Stage-3 gates, campaign arms, atomic records, reporting |
| `scripts/run_vilfan_complete.sh` | single-JVM CPU launcher (`-XX:ActiveProcessorCount=1`, `nice -n 17`) |
| `scripts/run_vilfan_complete_campaign.sh` | resumable 3-way-parallel campaign driver |

### S5.2 Equation-to-code mapping

| Vilfan symbol | equation / source | physical meaning | units | implementation | status |
|---|---|---|---|---|---|
| `X` | Model def. | filament axial translation | nm | `VilfanCompleteSystem.X` | exact |
| `Θ` | Model def. | filament axial rotation | rad | `VilfanCompleteSystem.Theta` | exact |
| `a` | Table I | axial rise per subunit | nm | `Config.aNm` | exact |
| `ϑ₀` | Table I | azimuthal advance per subunit | rad | `Config.latP/latQ/latSign` → `azimTable[]` | **exact, as a rational of a turn** |
| `X + i·a` | Model def. | site axial coordinate | nm | inline in `bindEnergy` | exact |
| `Θ + i·ϑ₀ + 2πn` | Eq (1) | site angular mismatch | rad | `wrapPi(Theta + baseAzim(i))` | exact |
| `x_M^j` | Model def. | motor anchoring point | nm | `xM[j]` | exact |
| `U_i` | Eq (1) | attachment elastic energy | pN·nm | `bindEnergy` | exact |
| `k_A^i` | Eq (2) | site-specific attachment rate | s⁻¹ | `siteHazard` | exact |
| `α` | Eq (3) | dimensionless angular stiffness | — | `Config.alpha` | exact |
| `K`, `K_ϑ` | Table I | longitudinal / angular stiffness | pN/nm, pN·nm | `Config.K`, `Config.kTheta()` | exact |
| `F^j` | Eq (4) | bound-head axial force | pN | `headForce` | exact |
| `M^j` | Eq (4) | bound-head axial torque | pN·nm | `headTorque` | exact |
| `δ` | text under Eq (4) | stroke displacement, 0 / d | nm | `state==PRE_PS ? 0 : delta` | exact |
| `ΣF=0, ΣM=0` | Eq (5) | quasi-static equilibrium | — | `equilibrate()` | exact; **analytic in X**, branch fixed point in Θ |
| `k_total` | algorithm 2 | total transition rate | s⁻¹ | `run()` step-2 block | exact |
| `Δt = k_total⁻¹ln(1/r)` | algorithm 3 | Gillespie waiting time | s | `expWait` | exact |
| transition choice | algorithm 4 | `rate/k_total` | — | one uniform draw, nested cumulative over (motor, site) | exact |
| `k_PS`, `k_−ADP`, `k_D` | Table I | cycle rates | s⁻¹ | `Config.kPS/kADP/kD` | exact |
| `ρ`, `l` | Table I | 1-D density, filament length | µm⁻¹, µm | `Config.densityPerUm/lengthUm` | exact |
| `x` | Eq (6) | zone-relative motor position | nm | `zoneCoord` | **sign-corrected — see S3.5** |
| `L` | Table II | zone period (superhelix half-pitch) | nm | derived from the lattice | inferred (V-8) |
| `ξ_i` | analytical | strain of a bound head | nm | `strain` | exact |
| `λ = 2πX/Θ` | algorithm 7 | pitch | µm/turn | `Result.pitchUmPerTurn` | exact |

### S5.3 Two implementation choices worth stating

**The lattice angle is exact, not accumulated.** `i·ϑ₀ mod 2π` is evaluated as
`−latSign·((latP·i) mod latQ)/latQ · 2π` from a table of length `latQ`. Both lattices of interest are
exact rationals of a full turn — the paper's `ϑ₀ = −167.142857…° = −(13/28)` turn and SoftBox's
`ϑ₀ = −166.5° = −(37/80)` turn — so site azimuths carry **no round-off that grows with `i`**, over
2000 sites and hundreds of accumulated filament turns.

**Eq (5) decouples, and is solved analytically in X.** `F^j` depends only on `X` and `M^j` only on
`Θ`, so
```
X = (1/N_b) Σ_j ( x_M^j + δ_j − i_j·a )                       closed form, exact
Σ_j wrapπ(Θ + i_j·ϑ₀) = 0                                     solved for Θ
```
The `Θ` equation is closed-form once the branch integers `n_j` are fixed, but `n_j` depends on `Θ`.
The fixed-point map `Θ ← Θ − (1/N_b)Σ_j wrapπ(Θ + b_j)` has **zero derivative away from a branch
boundary**, so it converges in a single iteration unless a head crosses a branch, and in a handful
when one does. Iterating from the *current* `Θ` is the physically correct quasi-static prescription:
it follows the local angular-energy minimum the filament already occupies. Branch reassignments and
non-convergences are **counted and reported per run** (gate D3, S12).

### S5.4 Static landscape (`-landscape`, no dynamics)

```
-- paper lattice: theta0 = -167.1429 deg (= -13/28 turn), a = 2.750 nm, exact repeat 28 subunits
   effective groove slope 0.081600 rad/nm  ->  target-zone period L = 38.500 nm (14.00 subunits)
   |theta_i| (deg), i = 0..28:
     0.0 167.1 25.7 141.4 51.4 115.7 77.1 90.0 102.9 64.3 128.6 38.6 154.3 12.9 180.0
     12.9 154.3 38.6 128.6 64.3 102.9 90.0 77.1 115.7 51.4 141.4 25.7 167.1 0.0
   total hazard over 3 zone periods: max 60.77 /s, min 2.082 /s, modulation 0.9338

-- native lattice: theta0 = -166.5000 deg (= -37/80 turn), a = 2.700 nm, exact repeat 80 subunits
   effective groove slope 0.087266 rad/nm  ->  target-zone period L = 36.000 nm (13.33 subunits)
   |theta_i| (deg), i = 0..30:
     0.0 166.5 27.0 139.5 54.0 112.5 81.0 85.5 108.0 58.5 135.0 31.5 162.0 4.5 171.0
     22.5 144.0 49.5 117.0 76.5 90.0 103.5 63.0 130.5 36.0 157.5 9.0 175.5 18.0 148.5 45.0
   total hazard over 3 zone periods: max 61.02 /s, min 2.279 /s, modulation 0.9280
```

The paper lattice reproduces Fig 2 exactly: near-perfect sites at `i = 0` and `i = ±13/±15`
(12.9° off), a **hole** at `i = 14` (180°, the worst possible site), and an exact repeat at `i = 28`.
The measured peak total hazard **60.8 s⁻¹** is within 4 % of Eq (10)'s analytic **58.5 s⁻¹**
(Eq 10 extends the sum to a continuous integral, so a small excess is expected).

The two lattices are **more alike than the 0.05 nm / 0.64° parameter difference suggests**: the zone
periods differ by 6.5 % (38.5 vs 36.0 nm) and the modulation depths by 0.6 % (0.934 vs 0.928). The
substantive structural difference is the **residual per 13 subunits** — `−12.86°` on the paper
lattice versus `−4.5°` on the native one — so the native lattice's target zones drift more slowly
and its exact repeat is 80 subunits rather than 28. That is the geometric quantity Stage 6 tests.

---

## S6. Stage 3 — unit and analytical gates

`./scripts/run_vilfan_complete.sh -gates` — **29 PASS, 0 FAIL.**

### S6.1 Results

| gate | result |
|---|---|
| A1 energy finite and non-negative | PASS — 200k random states |
| A2 energy == direct equation evaluation | PASS — max rel dev **0.00** (bit-identical to a literal Eq-1 transcription) |
| A3 units pN·nm and kBT | PASS — `U(1 nm axial) = 0.250000 pN·nm = 0.060386 kBT` |
| B1 site hazard finite and non-negative | PASS — 200k random states |
| B2 no site hazard exceeds `k_A` | PASS |
| B3 total hazard converged at production window | PASS — `W=2` is 1.9 % low, `W=4` 5.3e-6, `W=6` 1.8e-11, **`W≥8` bit-identical to `W=48`**; production `W=12` |
| B4 conditional site probabilities sum to 1 | PASS — 1.000000000000000 both enumeration orders |
| B5 site probabilities independent of enumeration order | PASS — max abs difference **0.00** |
| C1 waiting times exponential | PASS — 4×10⁶ draws, mean 0.0266597 vs 0.0266667 (2.6e-4 rel), variance 6.7e-4 rel |
| C2 same seed bit-reproducible | PASS — `X = 350.10267682351830` and 13429 events, identical across repeats |
| C3 cycle event counts equal | PASS — flat-landscape fixture: attach 3178 / PS 3178 / ADP 3178 / detach 3168 |
| C4 rigor dwell = `1/k_D` | PASS — 0.20344 s vs 0.20000 s |
| D1 force-balance residual | PASS — max `\|ΣF\| = 1.19e-11 pN` over 11202 equilibrations |
| D2 torque-balance residual | PASS — max `\|ΣM\| = 1.48e-12 pN·nm` |
| D3 branch fixed point converged | PASS — **0 non-converged**; 5551 branch reassignments in 11202 events |
| D4 `F = −dU/dX` (with rest offset `δ`) | PASS — numeric 3.125000347 vs analytic 3.125000000 pN |
| D5 `M = −dU/dΘ` | PASS — numeric −2.411144690 vs analytic −2.411144690 pN·nm |
| D6 stroke drives `+X`, reversed polarity drives `−X` | PASS — `v(d=8) = +0.0433`, `v(d=0) = −0.0005`, `v(polarity=−1) = −0.0429` µm/s |
| E1 mirroring preserves scalar attachment propensity | PASS — max rel dev **0.00** over a 40 nm scan |
| E2 mirror is exact antisymmetry | PASS — `ΔX = 0.00 nm`, `Θ = −5.85368954` vs `+5.85368954`, `⟨θ_A⟩ = +0.0886648` vs `−0.0886648` |
| E2b mirror preserves `⟨x_A⟩` | PASS — `+1.685260` vs `+1.685260` nm |
| E3 `α = 0` removes lattice torque | PASS — `Θ_end = 0`, `ω = 0` exactly |
| E4 polarity reversal ≠ mirroring | PASS — `v`: `+0.0407 / +0.0407 / −0.0416`; `ω`: `−0.4935 / +0.4935 / +0.6199` (native/mirror/polarity−) |
| F1 no attached motors ⇒ no motion | PASS — `X = 0`, `Θ = 0`, 0 attachments |
| F2 no power stroke ⇒ no sustained gliding | PASS — `v(d=0) = +0.00032` vs `v(d=8) = +0.0406` µm/s (0.8 %) |
| F3 no angular stiffness ⇒ no twirling | PASS — `ω = 0` exactly (`v` unaffected at `+0.0389` µm/s) |
| F4 achiral lattice ⇒ no mirror-odd rotation | PASS — `Ω_odd = 0` exactly |
| G1 no existing SoftBox file modified | PASS — see S13.3 |
| G2 harness refuses to run without `-vilfan-complete` | PASS |

### S6.2 Preregistered analysis protocol

Fixed before Stage 4 and not revisited:

- **warm-up**: the first **1.0 µm** of travel is discarded. Steady-state estimates use only the
  window after it.
- **analysed travel**: at least **5.0 µm**, extended adaptively until at least **8 complete turns**
  have accumulated, with a hard cap of 60 µm. **A run that hits the cap is reported as cap-hit** and
  its rotation is treated as under-resolved, not as a measurement.
- **estimators**: `v` and `ω` are reported both as endpoint differences over the analysed window and
  as time-weighted least-squares slopes; the two must agree or the run is flagged.
- **seeds**: 101 and 102 for discovery, extended to four if a stage's discovery result is positive.

### S6.3 A structural fact that changes how the mirror test must be read

**Vilfan's model is deterministic given its random-number stream, and the attachment hazard depends
on the angular mismatch only through `θ²`.** Mirroring the helicity (`ϑ₀ → −ϑ₀`) therefore maps the
system exactly onto itself with `Θ → −Θ` and `X → X`: every site hazard is numerically unchanged, so
the same random draws select the same motor and the same site index at the same times.

Gate E2 confirms this holds in the implementation to the last bit (`ΔX = 0.00 nm`,
`Θ = ∓5.85368954`). The consequences must be stated plainly:

- Success criteria 1–4 of the task (native and mirror rotation opposite in sign; `Ω_odd`
  same-signed across seeds; `|Ω_odd| ≫ |Ω_even|`; signed torque reverses) are satisfied
  **structurally, by an exact symmetry** — `Ω_even ≡ 0` and `Ω_odd ≡ Ω_native` to machine precision.
- The mirror test is therefore a **strong implementation-correctness gate**, not an independent
  statistical measurement. It would fail loudly on any achiral symmetry-breaking bug, any
  order-dependence in site enumeration, or any sign leakage in the branch selection — and it does
  not fail.
- The **substantive** question is entirely whether `Ω_odd = Ω_native` is non-zero, correctly signed
  and of the published magnitude. That is what S7 must establish.

This is the sharpest structural difference from the graded-binding hybrid, where the SoftBox motor's
Brownian noise made mirror-odd extraction a genuinely statistical exercise requiring many seeds.

---

## S7. Stage 4 — paper-exact positive control

**Fixture.** Vilfan's own lattice and parameters, unmodified: `a = 2.75 nm`,
`ϑ₀ = −(13/28)×360° = −167.14°`, `l = 5.5 µm` (2000 sites), `ρ = 20 µm⁻¹`, `α = 4`, `K = 0.5 pN/nm`,
`d = 8 nm`, `k_A = 50 s⁻¹`, `k_PS = 10⁴ s⁻¹`, `k_−ADP = 10³ s⁻¹`. Primary favourable operating point
**`k_D/k_A = 0.1` ⇒ `k_D = 5 s⁻¹` ⇒ `[ATP] = 1 µM`**, mean rigor dwell 200 ms. Static
(non-recycled) Poisson motor field, ≈1309 anchors spanning 65.5 µm so the filament never approaches
an edge during the analysed travel. Warm-up 1.0 µm discarded; ≥5 µm analysed and ≥8 turns. Seeds
101–104 × native/mirror = **8 arms** (discovery was 101/102; the positive result triggered the
preregistered extension to four matched seeds).

### Stage 4 — paper-exact positive control, per arm

| arm | v (µm/s) | ω (rad/s) | pitch (µm/turn) | turns | ⟨x_A⟩ (nm) | ⟨ξ_A⟩ (nm) | ⟨θ_A⟩ (rad) | M_A (pN·nm) | duty | events |
|---|---|---|---|---|---|---|---|---|---|---|
| `paper_kd0.1000_a4.00_s101_mirror` | 0.04161 | 0.5393 | 0.4848 | 10.3 | 1.759 | 0.3505 | -0.1088 | 1.802 | 0.666 | 211057 |
| `paper_kd0.1000_a4.00_s101_native` | 0.04161 | -0.5393 | -0.4848 | 10.3 | 1.759 | 0.3505 | 0.1088 | -1.802 | 0.666 | 211057 |
| `paper_kd0.1000_a4.00_s102_mirror` | 0.04156 | 0.545 | 0.4791 | 10.4 | 1.786 | 0.3459 | -0.113 | 1.872 | 0.74 | 228141 |
| `paper_kd0.1000_a4.00_s102_native` | 0.04156 | -0.545 | -0.4791 | 10.4 | 1.786 | 0.3459 | 0.113 | -1.872 | 0.74 | 228141 |
| `paper_kd0.1000_a4.00_s103_mirror` | 0.04118 | 0.5842 | 0.4429 | 11.3 | 1.798 | 0.3148 | -0.1136 | 1.882 | 0.728 | 230573 |
| `paper_kd0.1000_a4.00_s103_native` | 0.04118 | -0.5842 | -0.4429 | 11.3 | 1.798 | 0.3148 | 0.1136 | -1.882 | 0.728 | 230573 |
| `paper_kd0.1000_a4.00_s104_mirror` | 0.04207 | 0.5176 | 0.5106 | 9.79 | 1.737 | 0.3485 | -0.1051 | 1.741 | 0.803 | 249542 |
| `paper_kd0.1000_a4.00_s104_native` | 0.04207 | -0.5176 | -0.5106 | 9.79 | 1.737 | 0.3485 | 0.1051 | -1.741 | 0.803 | 249542 |

**All ten Stage-4 success criteria are met.**

| # | criterion | result |
|---|---|---|
| 1 | native and mirror rotation have opposite signs | **yes** — exactly, every seed |
| 2 | `Ω_odd` consistently same-signed across seeds | **yes** — 4/4 negative, −0.518 to −0.584 rad/s |
| 3 | `\|Ω_odd\|` dominates `\|Ω_even\|` | **yes** — `Ω_even = 0` to machine precision (see S6.3) |
| 4 | signed torque reverses under mirroring | **yes** — `M_A = −1.74…−1.88` native, `+1.74…+1.88` mirror pN·nm |
| 5 | attachment biased toward the **beginning** of the target zone | **yes** — `⟨x_A⟩ = +1.770 ± 0.014 nm`; 58.97 % of attachments before the centre |
| 6 | attachment-angle bias reverses under mirroring | **yes** — `⟨θ_A⟩ = ±0.110 rad` |
| 7 | late rotation approximately stationary | **yes** — halves of the analysed window give `ω = −0.5434` then `−0.5495` rad/s (ratio 1.011) |
| 8 | pitch of the published order, ≈0.4–0.5 µm/turn | **yes** — `−0.479 ± 0.014 µm/turn`; per-seed range −0.443…−0.511 |
| 9 | force and torque equilibrium residuals negligible | **yes** — max `2.6×10⁻¹⁰ pN`, `2.8×10⁻¹¹ pN·nm` (S13) |
| 10 | `α = 0` collapses the mirror-odd twirl | **yes** — gates E3/F3: `ω = 0` exactly, while `v` is unaffected |

### S7.1 Against the preregistered analytic predictions (S3.6)

| quantity | preregistered (S3.6) | measured | deviation |
|---|---|---|---|
| `v` | 0.042 µm/s | **0.04160 ± 0.00018** | 1 % |
| `ω` | −0.70 rad/s | **−0.547 ± 0.014** | 22 % |
| `λ` | −0.38 µm/turn | **−0.479 ± 0.014** | 26 % |
| `⟨ξ_A⟩` | +0.41 nm | **+0.340 ± 0.008** | 17 % |
| `⟨x_A⟩` | +2.0 nm (digitised from Fig 5B) | **+1.770 ± 0.014** | 12 % |
| peak total hazard | 58.5 s⁻¹ (Eq 10) | **60.8** (S5.4) | 4 % |

The predictions were fed by `⟨x_A⟩ ≈ 2.0 nm` **digitised from Fig 5B**; the whole spread above follows
from the simulation's own `⟨x_A⟩` being 12 % smaller, which then propagates into `ω` and `λ`. That is
the expected direction: Vilfan's analytics **overestimates** `⟨x_A⟩` because Eq (10) extrapolates the
attachment integral beyond one period and neglects both site discreteness and single occupancy — the
three deviations he himself names in the Discussion. **The velocity, which does not involve the
angular channel at all, lands within 1 %.**

### S7.2 The simulation reproduces Vilfan's own analytical relations

Feeding the **measured** `⟨x_A⟩`, `⟨ξ_A⟩`, `⟨θ_A⟩` into Eqs (12), (16), (17), (18), (19):

### Simulation vs Vilfan's own analytical relations (paper-exact arms)

`K_ϑ = 16.5600 pN·nm`, `K_ϑ' = (π²/L²)K_ϑ = 0.11027 pN/nm`, `K_ϑ'/(K+K_ϑ') = 0.18068`, `K/(K+K_ϑ') = 0.81932`, `L = 38.500 nm`

| relation | predicted from the measured input | measured | agreement |
|---|---|---|---|
| Eq (16) ⟨ξ_A⟩ = [K_ϑ'/(K+K_ϑ')]⟨x_A⟩ | 0.31983 | 0.33994 | 5.91 % |
| Eq (12) ⟨θ_A⟩ = [K/(K+K_ϑ')](π/L)⟨x_A⟩ | 0.11834 | 0.11014 | 7.44 % |
| Eq (17) v = k_D(d + ⟨ξ_A⟩)  [µm/s] | 0.0417 | 0.041604 | 0.23 % |
| Eq (18) ω = −k_D⟨θ_A⟩ | -0.55072 | -0.54654 | 0.77 % |
| Eq (19) λ = 2πv/ω  [µm/turn] | -0.47575 | -0.47935 | 0.75 % |

Eqs (17), (18) and (19) — the force-balance velocity, the torque-balance angular velocity and the
pitch — are reproduced to **better than 1 %**. Eqs (12) and (16), the two relations that *do* invoke
the continuous-groove approximation and the derived `L`, sit at 6–7 %. This is a strong internal
consistency check: the discrete stochastic simulation and the paper's closed-form steady-state
balance are the same physics.

---

## S8. Mirror even/odd decomposition

### Stage 4 — mirror even/odd decomposition

| kD/kA | α | seed | Ω_native | Ω_mirror | Ω_even | Ω_odd | M_A native | M_A mirror | ⟨x_A⟩ nat | ⟨x_A⟩ mir |
|---|---|---|---|---|---|---|---|---|---|---|
| 0.1 | 4 | 101 | -0.53928 | 0.53928 | 0 | -0.53928 | -1.802 | 1.802 | 1.759 | 1.759 |
| 0.1 | 4 | 102 | -0.54503 | 0.54503 | 0 | -0.54503 | -1.872 | 1.872 | 1.786 | 1.786 |
| 0.1 | 4 | 103 | -0.58421 | 0.58421 | 0 | -0.58421 | -1.882 | 1.882 | 1.798 | 1.798 |
| 0.1 | 4 | 104 | -0.51763 | 0.51763 | 0 | -0.51763 | -1.741 | 1.741 | 1.737 | 1.737 |

**`Ω_even` is exactly zero, and this is a structural fact, not a measurement** (S6.3). Vilfan's model
is noise-free and its attachment hazard depends on the angular mismatch only through `θ²`, so
mirroring the helicity maps the system exactly onto itself under `Θ → −Θ`, `X → X`; the same random
draws then select the same motor and the same site at the same instants. Gate E2 verifies this to the
last bit.

Therefore:

- criteria 1–4 are satisfied **by an exact symmetry**, and the decomposition's value here is as a
  **correctness gate** — it would fail loudly on any achiral symmetry-breaking bug, on any dependence
  of the result on site-enumeration order, or on any sign leakage in the branch selection;
- the **substantive** finding is that `Ω_odd = Ω_native = −0.547 ± 0.014 rad/s` is non-zero,
  **left-handed**, same-signed on 4/4 seeds, and of the published magnitude.

This differs sharply from the graded-binding hybrid, where the SoftBox motor's Brownian noise made
mirror-odd extraction a genuinely statistical exercise. **A study that later restores noise loses this
exactness and must return to a seed-paired statistical decomposition** (S16).

---

## S9. Target-zone attachment-position analysis

### Stage 4 — attachment position relative to the target-zone centre

Zone period L = 38.500 nm; 192,815 attachment events pooled over 4 arms. Positive x = BEFORE the zone centre (S3.5).

| x bin (nm) | attachments | fraction | 
|---|---|---|
| -19.25 … -15.40 | 5989 | 0.0311 |
| -15.40 … -11.55 | 11146 | 0.0578 |
| -11.55 … -7.70 | 17224 | 0.0893 |
| -7.70 … -3.85 | 21125 | 0.1096 |
| -3.85 … +0.00 | 23630 | 0.1226 |
| +0.00 … +3.85 | 25358 | 0.1315 |
| +3.85 … +7.70 | 29040 | 0.1506 |
| +7.70 … +11.55 | 30837 | 0.1599 |
| +11.55 … +15.40 | 20437 | 0.1060 |
| +15.40 … +19.25 | 8029 | 0.0416 |

**Before the centre (x>0): 113,701 (58.97 %) — after (x<0): 79,114 (41.03 %).**

The distribution is visibly skewed toward positive `x` — the **beginning** of the target zone, which
the filament's motion reaches first (`ẋ = −c < 0`, S3.5). The mode sits near `x ≈ +9 nm`, roughly a
quarter-period ahead of the centre, and falls away sharply past `x ≈ +12 nm` where the depleted
population has not yet been replenished.

Vilfan's stated criterion — "It reaches its maximum when `k_D/k_A` is such that each motor travels an
average path of ≈ `L/3` between two attachment events" — checks out directly: at `k_D/k_A = 0.1` the
mean bound lifetime is `1/k_D = 200 ms` and `v = 41.6 nm/s`, so the distance travelled per attachment
cycle is `v·(1/k_D + 1/k̄_A) ≈ 41.6 × (0.200 + 0.017) = 9.0 nm ≈ L/4.3`. The sweep (S11) puts the
`⟨x_A⟩` maximum at exactly this point, consistent with his `L/3` estimate.

---

## S10. No-depletion control

**Design.** A clearly-labelled shadow measurement running alongside the real simulation at
`k_D/k_A = 0.1`. At every Gillespie step it evaluates the *same* moving attachment landscape for
**every** motor under the filament — as if that motor were detached — and accumulates the
hazard-weighted, `Δt`-weighted distribution of the zone coordinate `x`. It **removes no motor from
the detached pool, generates no physical force, and consumes no random numbers**: the physical
trajectory is bit-identical to the corresponding `paper_*` arm (verified — `ω` matches to every
printed digit). It therefore measures what the attachment-position distribution would be if motor
availability were **continuous**, i.e. with the first-passage/depletion factor `(1 − A(x))` of
Eq (13) removed.

### No-depletion (shadow) control

| seed | ⟨x_A⟩ real (nm) | ⟨x_A⟩ no-depletion (nm) | difference | ω (rad/s) |
|---|---|---|---|---|
| 101 | 1.7586 | -0.1088 | 1.867 | -0.53928 |
| 102 | 1.7863 | -0.054219 | 1.84 | -0.54503 |
| 103 | 1.7982 | -0.052784 | 1.851 | -0.58421 |
| 104 | 1.7373 | -0.034561 | 1.772 | -0.51763 |
| **mean** | **1.7701 ± 0.014** | **-0.062592 ± 0.016** | **1.833** | |

### No-depletion control — attachment position and shadow flux

Zone period L = 38.500 nm; 192,815 attachment events pooled over 4 arms. Positive x = BEFORE the zone centre (S3.5).

| x bin (nm) | attachments | fraction | no-depletion flux |
|---|---|---|---|
| -19.25 … -15.40 | 5989 | 0.0311 | 0.0112 |
| -15.40 … -11.55 | 11146 | 0.0578 | 0.0332 |
| -11.55 … -7.70 | 17224 | 0.0893 | 0.0849 |
| -7.70 … -3.85 | 21125 | 0.1096 | 0.1584 |
| -3.85 … +0.00 | 23630 | 0.1226 | 0.2164 |
| +0.00 … +3.85 | 25358 | 0.1315 | 0.2146 |
| +3.85 … +7.70 | 29040 | 0.1506 | 0.1557 |
| +7.70 … +11.55 | 30837 | 0.1599 | 0.0822 |
| +11.55 … +15.40 | 20437 | 0.1060 | 0.0322 |
| +15.40 … +19.25 | 8029 | 0.0416 | 0.0111 |

**Before the centre (x>0): 113,701 (58.97 %) — after (x<0): 79,114 (41.03 %).**
**No-depletion flux: before 49.58 %, after 50.42 %.**

**Verdict: the before-centre bias is produced by depletion/history, not by a static asymmetric site
distribution.** With continuous availability the mean attachment position collapses from
**+1.770 ± 0.014 nm** to **−0.063 ± 0.016 nm** — a **96.5 % reduction**, with the sign even inverting
slightly. The before/after split goes from **58.97 / 41.03 %** to **49.58 / 50.42 %**, i.e. within
0.5 % of symmetric.

The small residual (−0.063 nm, 3.5 % of the effect) is the genuine static asymmetry of the *discrete*
landscape: the exact 13/28 lattice does not place its near-aligned sites symmetrically about a zone
centre (S5.4 — the 13/15 doublet straddles a 180° hole at `i = 14`). It is **opposite in sign** to the
measured bias, so it cannot be its source; if anything it slightly opposes it.

This isolates the third link of the mechanism chain (S14.2) on its own.

---

## S11. `k_D/k_A` sweep

Preregistered points `k_D/k_A ∈ {0.03, 0.10, 0.30, 1.0, 3.0, 10.0, 18.2}`, `α = 4`, four matched
native/mirror seed pairs each (56 arms).

### Stage 5 — preregistered kD/kA sweep (α = 4)

| kD/kA | α | n | ⟨x_A⟩ (nm) | ⟨θ_A⟩ (rad) | M_A,odd (pN·nm) | Ω_odd (rad/s) | pitch (µm/turn) | v (µm/s) | duty | occ det/pre/post/rigor | cap |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0.03 | 4 | 4 | 1.468±0.027 | 0.08919±0.00017 | -1.477 | -0.1315±0.0018 | -0.5951±0.0075 | 0.01245 | 0.876 | 0.93/1.1e-05/0.00011/0.074 | 0/4 |
| 0.1 | 4 | 4 | 1.77±0.014 | 0.1101±0.002 | -1.824 | -0.5465±0.014 | -0.4793±0.014 | 0.0416 | 0.735 | 0.94/3.1e-05/0.00031/0.062 | 0/4 |
| 0.3 | 4 | 4 | 1.429±0.015 | 0.09284±0.0014 | -1.538 | -1.42±0.014 | -0.5519±0.0042 | 0.1247 | 0.549 | 0.95/6.9e-05/0.00069/0.046 | 0/4 |
| 1 | 4 | 4 | 0.8109±0.027 | 0.05177±0.0017 | -0.8573 | -2.567±0.086 | -0.996±0.035 | 0.4056 | 0.321 | 0.97/0.00013/0.0013/0.026 | 0/4 |
| 3 | 4 | 4 | 0.3069±0.018 | 0.02107±0.0016 | -0.3489 | -3.338±0.3 | -2.255±0.2 | 1.17 | 0.155 | 0.99/0.00017/0.0017/0.011 | 0/4 |
| 10 | 4 | 4 | 0.1267±0.011 | 0.007822±0.00084 | -0.1295 | -3.835±0.19 | -5.37±0.26 | 3.254 | 0.0714 | 0.99/0.0002/0.002/0.0039 | 0/4 |
| 18.2 | 4 | 4 | 0.1039±0.018 | 0.00846±0.002 | -0.1401 | -5.31±1.7 | -7.227±2.1 | 4.624 | 0.0522 | 1/0.0002/0.002/0.0022 | 1/4 |

**The published duty-ratio dependence is reproduced on every channel.**

1. **The first-passage attachment bias peaks in the published favourable region.** `⟨x_A⟩` rises from
   1.47 nm at `k_D/k_A = 0.03`, peaks at **1.77 nm at `k_D/k_A = 0.10`**, and falls monotonically
   thereafter. That is the shape and the location of Fig 5B's maximum (published peak ≈2.0 nm at
   `k_D/k_A ≈ 0.1`); the measured peak is 12 % lower, for the reasons in S7.1.
2. **It weakens substantially at high `k_D/k_A`.** From the peak to `k_D/k_A = 18.2`, `⟨x_A⟩` falls
   **17-fold** (1.77 → 0.104 nm) and `⟨θ_A⟩` falls **13-fold** (0.110 → 0.0085 rad).
3. **The mirror-odd twirl weakens with it — in pitch, not in `ω`.** This distinction matters and is
   easy to misread. `|Ω_odd|` *increases* monotonically (0.13 → 5.3 rad/s) simply because
   `ω = −k_D⟨θ_A⟩` and `k_D` grows 600-fold while `⟨θ_A⟩` falls only 13-fold. **The twirl per unit
   distance travelled — the published observable — collapses**: `|λ⁻¹|` falls from 2.09 µm⁻¹ at the
   peak to **0.183 µm⁻¹** at `k_D/k_A = 18.2`, an **11-fold** weakening, and the pitch lengthens from
   0.48 µm to 7.2 µm. Vilfan plots `λ⁻¹`, not `ω`; the correct statement is that the *chirality per
   micron* dies away.
4. **`k_D/k_A = 18.2` explains the previous SoftBox hybrid's very small chiral bias.** At that point
   the duty ratio is **0.052**, the attachment bias is **0.104 nm** (5.9 % of its peak value), the
   angular bias is **0.0085 rad** (7.7 % of peak), and the twirl per micron is **8.7 %** of peak. A
   hybrid operating there is sampling the model at the point where its own mechanism is nearly
   extinguished — the effect is not absent, it is an order of magnitude down.

**Resolution caveat, stated rather than hidden.** The `k_D/k_A = 18.2` point has a pitch of ≈7 µm, so
the preregistered "≥8 turns" requirement demands ≈58 µm of travel against the 60 µm cap. **Two of its
four seeds hit the cap** (`sweep_kd18.20_a4.00_s102_{native,mirror}`) and its `Ω_odd` carries the
largest scatter in the sweep (±1.7 rad/s). Its *rotation* is therefore the one under-resolved number
in this study. Its `⟨x_A⟩` and `⟨θ_A⟩` — which are attachment-event statistics over ~10⁵ events, not
rotation statistics — are unaffected, and conclusions 1, 2 and 4 rest on those.

---

## S12. Stage 6 — native-lattice transfer

**Changed: the lattice geometry only.** `a: 2.75 → 2.70 nm` and `ϑ₀: −167.14° → −166.5°` (SoftBox's
`TWIST_PER_MON_DEG`, i.e. exactly `−(37/80)` of a turn, and `Constants.actinMonoRadius = 2.7 nm`).
**Unchanged:** the Vilfan state machine, every transition rate, `k_D/k_A = 0.1`, the attachment
energy, the force law, the torque law, quasi-static equilibrium, `ρ = 20 µm⁻¹`, `α = 4`, zero
Brownian motion, zero converter skew. Four matched seeds × native/mirror.

### Stage 6 — native SoftBox lattice, per arm

| arm | v (µm/s) | ω (rad/s) | pitch (µm/turn) | turns | ⟨x_A⟩ (nm) | ⟨ξ_A⟩ (nm) | ⟨θ_A⟩ (rad) | M_A (pN·nm) | duty | events |
|---|---|---|---|---|---|---|---|---|---|---|
| `native_kd0.1000_a4.00_s101_mirror` | 0.04172 | 0.5184 | 0.5057 | 9.89 | 1.614 | 0.3652 | -0.1024 | 1.696 | 0.673 | 212701 |
| `native_kd0.1000_a4.00_s101_native` | 0.04172 | -0.5184 | -0.5057 | 9.89 | 1.614 | 0.3652 | 0.1024 | -1.696 | 0.673 | 212701 |
| `native_kd0.1000_a4.00_s102_mirror` | 0.04218 | 0.6041 | 0.4387 | 11.4 | 1.753 | 0.38 | -0.1131 | 1.873 | 0.747 | 227257 |
| `native_kd0.1000_a4.00_s102_native` | 0.04218 | -0.6041 | -0.4387 | 11.4 | 1.753 | 0.38 | 0.1131 | -1.873 | 0.747 | 227257 |
| `native_kd0.1000_a4.00_s103_mirror` | 0.04206 | 0.5613 | 0.4708 | 10.6 | 1.641 | 0.3399 | -0.1102 | 1.824 | 0.735 | 229914 |
| `native_kd0.1000_a4.00_s103_native` | 0.04206 | -0.5613 | -0.4708 | 10.6 | 1.641 | 0.3399 | 0.1102 | -1.824 | 0.735 | 229914 |
| `native_kd0.1000_a4.00_s104_mirror` | 0.04175 | 0.5265 | 0.4982 | 10 | 1.651 | 0.3559 | -0.1065 | 1.764 | 0.812 | 254813 |
| `native_kd0.1000_a4.00_s104_native` | 0.04175 | -0.5265 | -0.4982 | 10 | 1.651 | 0.3559 | 0.1065 | -1.764 | 0.812 | 254813 |

### Stage 6 — mirror even/odd decomposition

| kD/kA | α | seed | Ω_native | Ω_mirror | Ω_even | Ω_odd | M_A native | M_A mirror | ⟨x_A⟩ nat | ⟨x_A⟩ mir |
|---|---|---|---|---|---|---|---|---|---|---|
| 0.1 | 4 | 101 | -0.51837 | 0.51837 | 0 | -0.51837 | -1.696 | 1.696 | 1.614 | 1.614 |
| 0.1 | 4 | 102 | -0.60414 | 0.60414 | 0 | -0.60414 | -1.873 | 1.873 | 1.753 | 1.753 |
| 0.1 | 4 | 103 | -0.56129 | 0.56129 | 0 | -0.56129 | -1.824 | 1.824 | 1.641 | 1.641 |
| 0.1 | 4 | 104 | -0.52647 | 0.52647 | 0 | -0.52647 | -1.764 | 1.764 | 1.651 | 1.651 |

### Paper lattice vs SoftBox native lattice (α = 4, kD/kA = 0.1)

| quantity | paper (13/28, a=2.75) | native (37/80, a=2.70) | ratio native/paper |
|---|---|---|---|
| target-zone period L (nm) | 38.5 ± 0 | 36 ± 0 | 0.9351 |
| ⟨x_A⟩ (nm) | 1.7701 ± 0.014 | 1.6649 ± 0.031 | 0.9406 |
| ⟨ξ_A⟩ (nm) | 0.33994 ± 0.0084 | 0.36028 ± 0.0084 | 1.06 |
| ⟨θ_A⟩ (rad) | 0.11014 ± 0.002 | 0.10806 ± 0.0023 | 0.9811 |
| M_A (pN·nm) | -1.824 ± 0.033 | -1.7895 ± 0.038 | 0.9811 |
| ω (rad/s) | -0.54654 ± 0.014 | -0.55257 ± 0.02 | 1.011 |
| v (µm/s) | 0.041604 ± 0.00018 | 0.041925 ± 0.00011 | 1.008 |
| pitch (µm/turn) | -0.47935 ± 0.014 | -0.47834 ± 0.015 | 0.9979 |
| duty ratio | 0.7345 ± 0.028 | 0.7419 ± 0.029 | 1.01 |
| **Ω_odd (rad/s)** | **-0.54654 ± 0.014** | **-0.55257 ± 0.02** | **1.011** |

### Stage 6 — attachment position, native lattice

Zone period L = 36.000 nm; 193,539 attachment events pooled over 4 arms. Positive x = BEFORE the zone centre (S3.5).

| x bin (nm) | attachments | fraction | 
|---|---|---|
| -18.00 … -14.40 | 6272 | 0.0324 |
| -14.40 … -10.80 | 11245 | 0.0581 |
| -10.80 … -7.20 | 17213 | 0.0889 |
| -7.20 … -3.60 | 21050 | 0.1088 |
| -3.60 … +0.00 | 23397 | 0.1209 |
| +0.00 … +3.60 | 25486 | 0.1317 |
| +3.60 … +7.20 | 28806 | 0.1488 |
| +7.20 … +10.80 | 30883 | 0.1596 |
| +10.80 … +14.40 | 21021 | 0.1086 |
| +14.40 … +18.00 | 8166 | 0.0422 |

**Before the centre (x>0): 114,362 (59.09 %) — after (x<0): 79,177 (40.91 %).**

**The mechanism transfers intact.** `Ω_odd` is `−0.5526 ± 0.020` rad/s on the native lattice versus
`−0.5465 ± 0.014` on the paper lattice — a ratio of **1.011**, indistinguishable at these error bars.
The pitch is `−0.478` versus `−0.479 µm/turn`. Rotation is left-handed and mirror-reversing on both.

**Why the transfer is so clean — the geometric reason.** The two lattices differ in exactly the way
that cancels:

| | paper 13/28, a = 2.75 | native 37/80, a = 2.70 |
|---|---|---|
| target-zone period `L` | 38.50 nm | 36.00 nm (−6.5 %) |
| zone modulation depth | 0.9338 | 0.9280 (−0.6 %) |
| residual per 13 subunits | −12.86° | −4.5° |
| exact repeat | 28 subunits | 80 subunits |
| `⟨x_A⟩` | 1.770 nm | 1.665 nm (−5.9 %) |
| **`⟨x_A⟩ / L`** | **0.04598** | **0.04625 (+0.6 %)** |

The attachment bias is **a fixed fraction of the zone period, not a fixed length**. Since
`⟨θ_A⟩ ≈ [K/(K+K_ϑ')]·(π/L)·⟨x_A⟩` depends on `x_A/L`, the angular bias — and hence
`ω = −k_D⟨θ_A⟩` — is invariant under a change of `L`. The native lattice's shorter period shrinks
`⟨x_A⟩` by 5.9 % and raises `π/L` by 6.9 %, and the two cancel to 1 %.

The remaining structural difference — the native lattice's much slower azimuthal drift per 13
subunits (−4.5° vs −12.86°) and its 80-subunit rather than 28-subunit exact repeat — changes the fine
structure of the landscape but **not** its period or its modulation depth (S5.4: 0.928 vs 0.934), and
the mechanism reads only those two.

**This answers the study's second scientific question: changing only the actin lattice geometry to
SoftBox's native 13/6 lattice does NOT remove the mechanism.**

---

## S13. Numerical and regression health

### Numerical health (all arms)

- arms: **92**, total Gillespie events: **17,393,687**
- max force-balance residual over every equilibration: **2.6e-10 pN**
- max torque-balance residual: **2.83e-11 pN·nm**
- branch reassignments: 8,643,462 (49.7 % of events); **non-converged: 0**
- equilibrations with zero bound heads: **748**
- travel-cap hits: **2** — sweep_kd18.20_a4.00_s102_mirror, sweep_kd18.20_a4.00_s102_native

**Stationarity (analysed window split in two equal halves of simulated time):**

| tag | n | ω first half | ω second half | ratio | v first | v second |
|---|---|---|---|---|---|---|
| paper | 4 | -0.5434 | -0.5495 | 1.011 | 0.04168 | 0.04153 |
| native | 4 | -0.5762 | -0.5289 | 0.9178 | 0.04225 | 0.0416 |
| alpha | 8 | -0.5776 | -0.5833 | 1.01 | 0.04312 | 0.04272 |

**Interpretation.**

- Force and torque balance are satisfied to `10⁻¹⁰ pN` and `10⁻¹¹ pN·nm` across **17.4 million**
  equilibrations — ten orders of magnitude below the per-head forces (≈4 pN) and torques (≈1.8 pN·nm).
- The wrapped-branch fixed point (S5.3, ambiguity V-7) required a branch reassignment on ~50 % of
  events and **never failed to converge**. Branch changes are frequent because the filament rotates
  through many turns; they are handled, counted and reported.
- 748 equilibrations out of 17.4 M (**0.004 %**) had zero bound heads, all at the highest `k_D/k_A`
  where the duty ratio is 5 %. Policy V-6 (freeze `X`, `Θ`) applies; the number is reported rather
  than suppressed.
- Two travel-cap hits, both at `k_D/k_A = 18.2` — discussed in S11.

### S13.1 Stationarity

The analysed window split into two equal halves of simulated time gives `ω` ratios of **1.011**
(paper), **0.918** (native) and **1.010** (α = 6/8). The paper and α arms are stationary to ~1 %. The
native arms show an ~8 % drift between halves; it lies within the seed-to-seed scatter those arms
already carry and does not affect the S12 conclusion, whose error bars span it — but a longer native
run is the cheapest way to tighten it if that number ever becomes load-bearing.

### S13.2 Runner disclosure

Every number in this report was produced **on the CPU**. No CUDA context, no TornadoVM, no
`TaskGraph`, no device buffer was created — the two new source files import nothing from
`uk.ac.manchester.tornado.*`, and the launchers use a plain `java -cp .`. Each arm is a
single-threaded JVM (`-XX:ActiveProcessorCount=1 -XX:+UseSerialGC`) at `nice -n 17`; the campaign
driver runs **at most 3 concurrently**, honouring the 3-logical-core budget. The GPU was left
entirely to the low-ATP study. Total cost: 92 arms, 17.4 M events, ≈14 CPU-minutes.

### S13.3 Default-off identity and regression

- `git diff --stat feature/vilfan-graded-binding` over `softbox/`, `scripts/`, `docs/` shows **only
  additions**: `softbox/VilfanCompleteSystem.java`, `softbox/VilfanCompleteHarness.java`,
  `scripts/run_vilfan_complete.sh` (plus `run_vilfan_complete_campaign.sh` and
  `analyse_vilfan_complete.py`). **No existing file is modified.**
- The aggregate MD5 of the **144 pre-existing** `softbox/*.java` files is
  `6ee6283b2ffe81cee47d1d91e1d8c01f`, **identical** to the value recorded on the parent branch before
  any work in this study began. Default-off identity is therefore **exact, by construction** — not
  established by a behavioural test that could miss something.
- `grep -rl VilfanComplete softbox/ scripts/` returns **only the five new files**. No existing
  harness, system or launcher can reach this code.
- `VilfanCompleteHarness.main` refuses to execute at all unless `-vilfan-complete` is passed.
- Untouched, as required: `feature/vilfan-target-zone`, `feature/vilfan-graded-binding`, the
  completed hard-gate report, the completed graded-binding report, and every low-ATP campaign record.

**On the cross-reference.** The brief asked for a short cross-reference in the graded-binding report
linking to this study. `VILFAN_GRADED_BINDING_VALIDATION.md` turns out to be **untracked in git** — it
exists only as a working file in `../softbox-vilfan-graded` (and a copy in the main worktree), on no
branch. Editing it would mean modifying a completed artefact outside this branch, which the brief also
forbids, and the "do not modify" instruction is the stronger of the two. **It was therefore left
byte-untouched and the cross-reference is one-directional**: this report links to it (S2, S14.3).
Making the link bidirectional needs either that report committed to a branch first, or an explicit
decision to edit an untracked file in another worktree — flagged here rather than taken unilaterally.

---

## S14. Exact stopping decision

### S14.1 Verdict

> ## **A. COMPLETE VILFAN REFERENCE REPRODUCED ON PAPER AND NATIVE LATTICES**

The paper-exact model twirls with mirror reversal at the published favourable operating point, with
the published handedness and the published pitch; and changing **only** the actin lattice geometry to
SoftBox's native 13/6 lattice leaves the effect intact within error.

This is **not** verdict B (that would require the native transfer to fail), **not** verdict C (the
published mechanism reproduced under the published assumptions), **not** verdict D (exactly one
number in the study — the rotation at `k_D/k_A = 18.2` — is duration-limited; it is reported as such
in S11 and carries no conclusion), and **not** verdict E.

### S14.2 The mechanism chain, link by link

The task required each link to be shown separately. Each is established by an independent
measurement:

| link | evidence | where |
|---|---|---|
| published graded attachment landscape | exact per-site angles reproduce Fig 2 (0°, 12.9° at `i = ±13/±15`, a 180° hole at `i = 14`, exact repeat at 28); peak total hazard 60.8 s⁻¹ vs Eq (10)'s 58.5 | S5.4, gates A2/B3 |
| → early target-zone attachment | `⟨x_A⟩ = +1.770 ± 0.014 nm`; 58.97 % of 193 k attachments before the zone centre | S9 |
| → depletion of still-free motors is the **cause** | holding availability continuous collapses `⟨x_A⟩` to `−0.063 nm` and the split to 49.6 / 50.4 % | **S10** |
| → signed angular mismatch | `⟨θ_A⟩ = +0.110 ± 0.002 rad`, reversing exactly under mirroring | S7, S8 |
| → conjugate Vilfan angular torque | `M_A = −K_ϑ⟨θ_A⟩ = −1.824 ± 0.033 pN·nm`; the *equilibrium* per-head torque is identically zero (that is Eq 5), so this injected torque is the signed quantity | S7, S8 |
| → mirror-reversing steady twirl | `ω = −0.547 ± 0.014 rad/s`, stationary to 1 %, `λ = −479 ± 14 nm` | S7, S13.1 |
| and the chain is **necessary**, not incidental | `α = 0` ⇒ `ω ≡ 0` with `v` unchanged; achiral lattice ⇒ `Ω_odd ≡ 0`; `d = 0` ⇒ neither gliding nor twirl | gates E3, F2, F3, F4 |

### S14.3 What this establishes about the previous hybrid

The hybrid's very small chiral bias is now **quantitatively accounted for**, without invoking any
defect in it. Two independent causes, both measured here:

1. **Operating point.** At `k_D/k_A = 18.2` the complete model's own attachment bias is 5.9 % of its
   peak and its twirl per micron 8.7 % of peak (S11). The hybrid was sampling the mechanism at the
   point where Vilfan's model says it is nearly extinguished.
2. **Missing conjugacy.** The hybrid kept Vilfan's attachment law but generated its restoring torque
   from SoftBox's explicit-S2/lever mechanics rather than from Eq (4). Here the attachment energy and
   the bound-state torque use the **same coordinate and the same stiffness `K_ϑ`**, and that
   conjugacy is what converts a signed attachment angle into a signed torque. This reference does
   **not** test whether a non-conjugate mechanical channel can do the same job; it shows only that
   the conjugate one does.

### S14.4 Corrections and findings recorded by this study

- **Eq (6) as printed carries a sign error** (S3.5). The `+Θ(L/π)` form is required by Vilfan's own
  `ẋ`, makes Eq (8) reduce to the site azimuth, and matches the exact discrete lattice. With the
  printed sign the paper's central claim would read backwards.
- **Mirror antisymmetry in this model is exact, not statistical** (S6.3, gate E2) — so the mirror test
  is a correctness gate here, and criteria 1–4 are met structurally rather than empirically.
- **The attachment bias scales with the zone period: `⟨x_A⟩/L` is constant** (S12) — which is *why*
  the native-lattice transfer is clean, and predicts robustness to any lattice change that preserves
  zone period and modulation depth.
- **`ω` and `λ⁻¹` move in opposite directions across the `k_D/k_A` sweep** (S11). Reporting `ω` alone
  would invert the published conclusion.

---

## S15. Stage 7 — optional α check

Run only after the mechanism was confirmed at `α = 4`. Minimal matched native/mirror design, four
seeds each.

### Stage 7 — α = 6 and α = 8

| kD/kA | α | n | ⟨x_A⟩ (nm) | ⟨θ_A⟩ (rad) | M_A,odd (pN·nm) | Ω_odd (rad/s) | pitch (µm/turn) | v (µm/s) | duty | occ det/pre/post/rigor | cap |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0.1 | 6 | 4 | 2.009±0.015 | 0.1209±0.00034 | -3.004 | -0.6034±0.0073 | -0.4441±0.0041 | 0.04263 | 0.656 | 0.94/2.8e-05/0.00028/0.055 | 0/4 |
| 0.1 | 8 | 4 | 2.029±0.014 | 0.1121±0.00065 | -3.713 | -0.5575±0.01 | -0.4873±0.0077 | 0.0432 | 0.603 | 0.95/2.5e-05/0.00025/0.051 | 0/4 |

### Stage 7 — mirror even/odd decomposition

| kD/kA | α | seed | Ω_native | Ω_mirror | Ω_even | Ω_odd | M_A native | M_A mirror | ⟨x_A⟩ nat | ⟨x_A⟩ mir |
|---|---|---|---|---|---|---|---|---|---|---|
| 0.1 | 6 | 101 | -0.62408 | 0.62408 | 0 | -0.62408 | -3.015 | 3.015 | 2.019 | 2.019 |
| 0.1 | 6 | 102 | -0.59131 | 0.59131 | 0 | -0.59131 | -3.022 | 3.022 | 2.047 | 2.047 |
| 0.1 | 6 | 103 | -0.60331 | 0.60331 | 0 | -0.60331 | -2.987 | 2.987 | 1.981 | 1.981 |
| 0.1 | 6 | 104 | -0.59502 | 0.59502 | 0 | -0.59502 | -2.993 | 2.993 | 1.988 | 1.988 |
| 0.1 | 8 | 101 | -0.54578 | 0.54578 | 0 | -0.54578 | -3.725 | 3.725 | 2.022 | 2.022 |
| 0.1 | 8 | 102 | -0.5671 | 0.5671 | 0 | -0.5671 | -3.745 | 3.745 | 2.05 | 2.05 |
| 0.1 | 8 | 103 | -0.58191 | 0.58191 | 0 | -0.58191 | -3.733 | 3.733 | 2.052 | 2.052 |
| 0.1 | 8 | 104 | -0.53539 | 0.53539 | 0 | -0.53539 | -3.65 | 3.65 | 1.992 | 1.992 |

`⟨x_A⟩` grows with `α` (1.77 → 2.01 → 2.03 nm) — sharper, more localised target zones produce a
larger positional bias, exactly as Vilfan notes ("the agreement is better for large `α` values, where
target zones become more localized"). But the pitch depends on `α` **weakly and non-monotonically**:
`−0.479`, `−0.444`, `−0.487 µm/turn` for `α = 4, 6, 8`. This is not a discrepancy — it is what the
paper's own Eq (12) gives, because two factors oppose:

| α | `K/(K+K_ϑ')` | measured `⟨x_A⟩` (nm) | Eq (12) `⟨θ_A⟩` | measured `⟨θ_A⟩` |
|---|---|---|---|---|
| 4 | 0.8193 | 1.770 | 0.1183 | 0.1101 |
| 6 | 0.7514 | 2.009 | 0.1232 | 0.1209 |
| 8 | 0.6939 | 2.029 | 0.1149 | 0.1121 |

Rising `α` sharpens the zones (raising `⟨x_A⟩`) but stiffens the angular spring relative to the
longitudinal one (lowering `K/(K+K_ϑ')`), and the product peaks near `α = 6`. **The simulation and
Eq (12) agree on that non-monotone shape, including the location of the maximum.**

**The published qualitative trend is confirmed, and `α` was not fitted to pitch:** all three
published `α` values give a left-handed, mirror-reversing twirl with `|λ|` inside the published
**400–500 nm** band (444–487 nm). Resolving a ~10 % trend *between* the three would require
digitising Fig 6's three panels to a precision they do not support, and is not claimed.

---

## S16. The ordered realism-restoration ladder (NOT executed here)

**Nothing on this ladder was built, and this task stops here.** It is recorded so the next study
starts from a fixed order rather than an ad-hoc one. The reference validated above is the positive
control each rung must try to break.

**Rules for the ladder.** Add exactly one rung at a time; keep everything else Vilfan; re-run the
paper-exact fixture at `k_D/k_A = 0.1`, `α = 4`, four matched seed pairs; and report the same six
chain quantities every time — `⟨x_A⟩`, `⟨θ_A⟩`, `M_A`, `Ω_odd`, `λ`, duty ratio. A rung that moves
`Ω_odd` by more than its error bar is the finding; a rung that does not is a licence to keep it.

| rung | restore | why it sits here | what it threatens |
|---|---|---|---|
| 1 | finite **translational** drag: replace the `X` half of Eq (5) with an overdamped EOM | cheapest departure from "quickly equilibrated"; `X` is decoupled from `Θ` so it can be tested alone | little — `v` is set by force balance, which survives |
| 2 | finite **rotational** drag on `Θ` | **the first rung that can plausibly kill the effect.** SoftBox's own viscosity study found rotation is *drag-limited* while translation is not | the whole twirl: `ω` becomes a competition between injected torque and rotational drag rather than an instantaneous balance |
| 3 | **roll (and axial) Brownian noise** at FDT with the rung-1/2 drags | the sharpest quantitative risk in the whole programme | `sd(θ_A) = 0.654 rad` is already **6×** the mean `⟨θ_A⟩ = 0.110 rad`. The signal is a small shift of a broad distribution sustained by ~48 k attachments per run. Added rotational noise both broadens `θ_A` **and** random-walks `Θ` directly. **Mirror antisymmetry stops being exact here** — the decomposition reverts to seed-paired statistics, and the required seed count should be estimated *before* running |
| 4 | **transverse (Y) freedom + the 2-D motor lawn** of the Appendix (Eqs 20–23) | Vilfan derives the 1-D reduction himself, so this rung tests his own stated approximation | Eq (23): a lateral force appears once `D(x_M, y_M)` is asymmetric in both coordinates |
| 5 | finite motor **height and tilt**, real surface-bond geometry | first genuinely non-Vilfan geometry | the identification of "azimuth zero" with "pointing at the surface" |
| 6 | **strain-dependent detachment** replacing constant `k_D` | Vilfan explicitly neglects it ("We neglect the strain dependence of those rates") and flags the model as not thermodynamically consistent | `ω = −k_D⟨θ_A⟩` assumes a single `k_D`; a strain-dependent one couples lifetime to the angular channel |
| 7 | SoftBox's **Lymn–Taylor cycle** replacing the four-state cycle | the first wholesale chemistry swap; `k_D/k_A` stops being a free axis | requires re-deriving where on the S11 curve the system sits — see S14.3 |
| 8 | **explicit-S2 / converter / lever** mechanics replacing Eq (4) | breaks the conjugacy of S14.3(2) | the attachment energy and the bound torque no longer share a coordinate or a stiffness |
| 9 | **converter skew** | an independent chiral channel | superposes on the target-zone twirl; the two must be separated with an `α = 0` control |
| 10 | full 3-D filament motion, then GPU | last, once survival is settled on CPU | — |

**Two things to carry forward.** First, rung 3 is the pivot: everything before it is a mechanics
question, everything from it on is a signal-to-noise question, and the numbers needed to size it
(`⟨θ_A⟩ = 0.110`, `sd(θ_A) = 0.654`, ≈48 k attachments per 5 µm of travel) are in S7. Second, keep
the `α = 0`, `d = 0` and achiral-lattice controls (gates E3, F2, F4) alive at every rung — they are
what distinguish "the mechanism survived" from "something else now makes it rotate".

---

## S17. Reproducing this study

```
./scripts/run_vilfan_complete.sh -gates          # Stage 3: 29/29 unit and analytical gates
./scripts/run_vilfan_complete.sh -landscape      # static target-zone landscape, both lattices
./scripts/run_vilfan_complete_campaign.sh all 3  # 92 arms, resumable, <=3 cores, ~14 CPU-minutes
python3 scripts/analyse_vilfan_complete.py all   # every table in S7-S15
```

Records: `RUN_LOGS/vilfan_complete/*.json` — one atomic record per arm (written `.tmp` then renamed),
skipped if already present, so an interrupted campaign resumes exactly where it stopped.

**What is committed and what is not.** `RUN_LOGS/` is gitignored by repo convention, so the 92 raw
records are **not** committed; they are regenerated exactly by the campaign command above (every arm
is deterministic in its declared seed — gate C2). The derived tables **are** committed, at
`ANALYSIS/vilfan_complete/tables.md`, so every number quoted in S7–S15 is checked in even though its
source records are local. This report is force-added: `docs/twirling/vilfan_target_zone/` is excluded
via `.git/info/exclude`, and the precedent set by the tracked `TARGET_ZONE_BUILD_AND_VALIDATION.md`
is followed.
