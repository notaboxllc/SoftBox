# Two-stage actin binding (weak → strong) — design draft

Status: DRAFT for jba's review, 2026-10-25. Nothing built. SoftBox-dock, default-off flag when built.

## Why

The single-motor search fixture (`-fix-search`) found that a motor never binds in the swivel-dock scene:
every geometric candidate is refused by the 15 kT budget (orientation charged at the bound-state
k_bind ≈ 125 kT/rad², plus the dock energy) and by the polarity gate. Removing both (`-capture-lit`) lets
motors bind, but a head captured off-axis is then dragged by the bound springs into a χ ≈ −89° gimbal lock
within ~40 steps and makes no stroke (lit0/lit2, `RUN_LOGS/motor_audit/search/`). A one-step capture
either refuses nearly everything or forces the head through a large angle. The literature says capture is
two-step.

## What the literature says

| Observation | Source | Number |
|---|---|---|
| Binding is two-step: weak collision complex (A-state) isomerizes to a stereospecific strong state (R-state) | Geeves; Conibear & Geeves 1998 (PMC1299766); Knetsch 1999 (three-state, electrostatic collision complex) | in ATP/ADP·Pi states the A→R equilibrium is strongly to the weak side |
| Weakly attached heads are orientationally disordered and rotate freely | Thomas-lab EPR, Berger & Thomas 1994 (PMC1281895) | large-amplitude rotations, correlation times 1–20 µs |
| Weak attachment is electrostatic (loop 2 lysines ↔ actin acidic N-terminus) | van Dijk 1999; Yengo 2004 | ionic-strength dependent |
| Weak cross-bridges are mechanically attached: they add fibre stiffness at fast stretch, so they bear force | Brenner et al. 1982 PNAS 79:7288; Brenner 1986 (PMID 3022835) | at 20 mM ionic strength 60–90 % of rigor-attached heads are attached in relaxed fibres; at 170 mM, 2–10 % |
| Weak-state kinetics (M·ATP, rabbit psoas) | Schoenberg 1988 Biophys J 54:135 (PMC1330323) | low salt: k_att 10⁴–10⁵ s⁻¹, k_det ≈ 10⁴ s⁻¹. Physiological salt: k_att ~100× lower, k_det only 2–3× lower (≈3–5×10³ s⁻¹), K 30–40× weaker |
| Angular selectivity of productive binding | Steffen et al. 2001 PNAS 98:14949 | rate ∝ exp(−α φ²/2), α = 3.0–3.7 (half-width ~30°); Vilfan 2009 uses α = 4–8 |

Not found: a per-head stiffness for the weak state, or a measured polarity tolerance.

## The model

States per motor: FREE → WEAK → STRONG (the current bound state), and WEAK → FREE.

1. **FREE → WEAK (collision complex).** Unbound ADP·Pi head whose F8 comes within `d_w` of a lattice site
   from outside (existing g8 accessibility + g0 distance). No orientation condition (weak binding is
   non-stereospecific). Deterministic on contact, like the current g0, or at rate `k_on,w` (open choice;
   Schoenberg's 10²–10³ s⁻¹ is a fibre rate including the search, so contact-deterministic is the closer
   analogue).
2. **WEAK state mechanics.** An isotropic zero-rest spring between F8 and the site, stiffness `k_w`. **The
   force goes through the existing bond path, so the head gets +F and the segment gets −F through the same
   gather (equal and opposite by construction).** No orientation spring, no dock, no triad, no stroke:
   head ψ/χ and the frame azimuth stay Brownian. Motor Brownian stays ON.
3. **WEAK → FREE** at `k_off,w` ≈ 4×10³ s⁻¹ (Schoenberg, physiological salt). Lifetime ≈ 0.25 ms ≈ 200 steps
   at dt = 1.25 µs, so it is resolved. Not force-gated (no data).
4. **WEAK → STRONG** while weak, at rate `k_ws · exp(−α θ²/2) · exp(−α μ²/2)`, with θ = head axis vs −n_site
   and μ = stroke axis vs barbed, α = 3.7 (Steffen). The polarity factor reuses α as a stated ASSUMPTION
   (no measurement). Entry also needs the existing g4 preload condition. On entry the current bound state
   takes over unchanged (k_bind, dock, triad, chemistry). Because entry happens only inside the window, the
   bound springs reorient the head by at most ~30°, which should remove both the forced χ lock and the
   uncharged free swing (the `-headtilt` lesson). That is the first thing to check.
5. **Chemistry.** Pi release (the stroke) only from STRONG. Weak-state ADP·Pi does not cycle.
   Unchanged otherwise.

## Parameters

| Parameter | Value | Basis |
|---|---|---|
| `k_off,w` | 4×10³ s⁻¹ | Schoenberg 1988, physiological ionic strength |
| α | 3.7 | Steffen 2001 (lower bound) |
| `d_w` | 3 nm (current g0) | model choice; ~ a few Debye lengths (λ_D ≈ 0.75 nm at 0.17 M) plus site size |
| `k_w` | **unpinned.** Proposal: set so thermal excursion √(kT/k_w) ≈ `d_w`/2, i.e. k_w ≈ 1.8 pN/nm for 1.5 nm, or ≈ 0.5 pN/nm for 3 nm | no measurement found; must not exceed the strong F8 stiffness |
| `k_ws` | **unpinned.** 10²–10³ s⁻¹ plausible (force-generation step ~40 s⁻¹ in fibre fits; must be ≫ k_off,w·P_window for capture to happen) | choose, then test sensitivity; never fit to gliding speed |

## Cost

One per-motor state flag, one extra branch in the bond kernel (scaled isotropic spring, other terms off),
a rate test per weak motor per step. Weak motors take the bound force path, so the per-step bound count
rises by roughly (weak lifetime × weak attachment rate). Expected small. CPU first; GPU path later
(hot-kernel change ⇒ CPU≡GPU confirmation required before any campaign).

## Validation (before any gliding)

1. Force coverage: the weak spring force on the head equals minus the gathered force on the segment
   (gather == brute, momentum ≈ 0).
2. Weak dwell is exponential at `k_off,w`.
3. FDT: a weakly held head's orientation distribution matches the free head's (no hidden orientation torque).
4. Single-motor movie: search → weak (head jiggling on the site) → strong → stroke → release, several motors.
   Report filament motion between strong entry and Pi release (free-swing check) and χ after entry.
5. Only then gliding, with the existence gate (independent lawns).

## Open questions for jba

- `k_w` and `k_ws` have no measured values. Accept the choices above as stated assumptions?
- Polarity in the window: reuse α (assumption) or a hard cone?
- Keep the weak→strong rate Boltzmann-weighted (Steffen) or a hard window plus a rate?
