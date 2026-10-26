# Motor binding vs the literature, gate by gate (2026-10-26)

Companion to `MOTOR_MODEL_AND_BINDING_2026-10-26.md` (the model description). This is our own assessment.

How each source was checked: **FT** = full text read; **ABS** = abstract only; **SEC** = secondary source, unverified.
The literature pass was a single search session; anything marked ABS/SEC should be checked before it carries weight.

## 1. Benchmarks

The best available benchmark comes from two-headed rabbit skeletal HMM gliding assays. Single-headed myosin needs about
2× the density for continuous movement (Ito et al. 1999 Biophys J 76:985, Dictyostelium, ABS).

| quantity | literature | model (400 heads/µm², 2.1 µm filament) |
|---|---|---|
| duty ratio, unloaded | 0.05 (Uyeda, Kron & Spudich 1990 JMB 214:699, ABS); 0.038 ± 0.005 skeletal (Harris & Warshaw 1993 JBC 268:14764, ABS) | — |
| width of the band of heads that can reach the filament | ≈ 30 nm (Månsson 2016 JMRC 37:181, FT; Rastogi et al. 2016 Sci Rep 6:32043, FT) | — |
| heads within reach | ρ·d·L ≈ 400 × 0.030 × 2.1 ≈ **25** *[derived]* | — |
| **mean heads bound** | f·N ≈ **1.0–1.3** *[derived]* | **0.13–0.31** |
| bound lifetime | t_s ≈ d/v ≈ 10 nm / 5 µm/s ≈ **2 ms** *[derived]*; ADP release 350 /s in a model fit (Walcott, Warshaw & Debold 2012 Biophys J 103:501, FT) | **0.63–1.13 ms** measured; 1.15 ms unloaded *[derived]* |
| attachments per second, whole filament | ≈ 25 heads × 25 /s ≈ **600 /s** *[derived]* | **210–390 /s** |
| typical assay density | 1000 heads/µm² (Toyoshima 1990, via Månsson 2016); ~5000 active heads/µm² (Månsson 2016, FT); 6500 HMM/µm² near jamming (Persson et al. 2010 Langmuir 26:9927, FT) | 400 heads/µm² |
| actin height above the surface | 38 ± 2 nm for HMM (Persson 2010, FT) | 11–44 nm, emergent |

Reading:
- Engagement is about **4–8× below** the benchmark; a further ~2× allowance for single heads would narrow this to 2–4×.
- The shortfall splits between **attachment flux (~1.5–3× low)** and **bound lifetime (~2× short)**.
- If the model's reach is wider than 30 nm (its cull radius is 110 nm and the S2 + lever + head span ~55 nm), more than
  25 heads are within reach and the per-head attachment rate is lower still. Not measured.
- 400 heads/µm² is at the sparse end of real assays.

## 2. Gate by gate

| model element | model | literature | assessment |
|---|---|---|---|
| **Capture law** | deterministic: bind the instant every gate passes, no on-rate | Two steps. **Weak binding** (A-state) is fast, in rapid equilibrium, electrostatic (loop 2) and orientation-tolerant: K_w ≈ 12, ≈ 2.5–3 kT (Månsson 2016, FT; Duke 1999 PNAS 96:2770, FT); not stereospecific (Craig, Greene & Eisenberg 1985 PNAS 82:3247, ABS). Weak attachment rates 10²–10⁵ /s depending on ionic strength (Schoenberg 1988 Biophys J 54:135, ABS). **Strong binding** is then gated by Pi release; models use an effective strong-attachment rate of **~40 /s** (Duke 1999; Walcott 2012, both FT). | **Structural difference.** The model has no weak-binding state: it requires the strong, stereospecific geometry at the moment of first contact. In reality a head first binds loosely in a broad range of poses and then has time (ms) to find the strong pose. This is probably the root of the restrictiveness. |
| Binding nucleotide | ADP·Pi only | M·ADP·Pi (and M·ATP) bind weakly; strong binding follows Pi release | Consistent for strong binding. |
| Pi release when bound | 1e4 /s (stroke within ~0.1 ms of capture) | 75 ± 5 /s from acto-S1·ADP·Pi at 20 °C (White, Belknap & Webb 1997 Biochemistry 36:11828, ABS) | Fast in the model, but harmless only because capture already demands the strong pose. Coupled to the capture-law question. |
| **Distance + preload (g0, g4)** | hard sphere: F8 within **2 nm** of the site (preload < 2 pN at 1 pN/nm) | Duke 1999: heads bind anywhere, Boltzmann-weighted by the strain energy (K = 1.3 pN/nm). Månsson 2019 JGP 151:578 (FT, partial): weak binding allowed for strain −2.8 to +18.2 nm. | **More restrictive.** 2 nm equals the model bond's own thermal σ (√(kT/k_F8) ≈ 2.0 nm per axis). A bond already in equilibrium would sit inside the window ~19 % of the time. Literature uses a soft (Boltzmann) window several nm wide. |
| Site lattice | every 4th monomer per strand, 2 strands: ≈ 6.7 sites per 36 nm | Rigidly mounted S1 binds **3 monomers per 36 nm, one strand** (Steffen, Smith, Simmons & Sleep 2001 PNAS 98:14949, FT) | Site count is comparable or generous. The problem is combining a 10.8 nm site spacing with a 2 nm capture sphere: the head must hit a point. |
| **Orientation (g1′)** | hard 25° cone, head axis vs site normal | Gaussian target function, half-width **≈ 31°** for a rigidly mounted S1; "a head attached through S2 is likely to adopt a wider range of orientations" (Steffen 2001, FT) | **Narrower, and hard instead of soft.** Moderate. |
| Accessibility (g8) | approach from outside the site | geometric | Reasonable. |
| Converter (g3) | \|θ − θ_s\| < 20° (≈ 2 thermal σ) | none found | Mild; passes ~95 % of thermal poses *[derived]*. |
| **Polarity (g9)** | stroke axis within **25°** of barbed (default 90°; set to 25° by `-swivel-dock`, copied from the orientation tolerance) | **No polarity restriction at binding found.** A single one-headed myosin attaches across all orientations; displacement ~10 nm at 5–10°, ~0 at 90°, ~5 nm antiparallel (Tanaka et al. 1998 Biophys J 75:1886, ABS; direction of the antiparallel displacement not verified). Actin moves both ways on native thick filaments (Sellers & Kachar 1990 Science 249:406, ABS). Randomly adsorbed S1 moves actin (Toyoshima et al. 1987 Nature 328:536, ABS). | **No biological basis for 25°.** The literature supports "misoriented heads bind but displace less", not "misoriented heads cannot bind". The 25° value has no provenance; it was inherited. The dock energy in g5 already charges stroke-axis misalignment, so g9 charges it a second time. |
| **Energy budget (g5)** + dock stiffness | 15 kT budget; orientation and dock terms at **k_B = 512 pN·nm/rad²** (62 kT/rad²) | Motor-domain/actin target function α = κ/kT ≈ 3.4 /rad² → κ ≈ 14 pN·nm/rad²; torsional stiffness quoted as ~4e-20 N·m (~40 pN·nm/rad) (Steffen 2001, FT; the two numbers differ by definition, not checked) | **The model's orientation stiffness at capture is ~13–37× stiffer than measured.** k_B = 512 and kc = 128 are prototype sweep picks with no literature provenance. With a measured-scale stiffness, the 15 kT budget would almost never reject a pose (at 14 pN·nm/rad², 25° costs 0.3 kT). |
| Commit tested on nearest site only | other passing sites are never tried | — | Numerical artefact; no biological meaning. |
| Occupancy | one head per site | — | Reasonable. |
| Bound lifetime | ADP release 1000 /s with catch-slip | t_s ≈ 2 ms implied by d/v; 350 /s in Walcott 2012's fit | ~2× short. Part of frozen chemistry; changing it needs its own justification, not this assay. |
| F8 stiffness | 1.0 pN/nm | 0.3 (Walcott 2012), 1.3 (Duke 1999), 2.8 (Månsson 2019); neck 1–2 pN/nm (Steffen 2001) | Within the literature range. |

## 3. Conclusions

1. The deficit is real against the literature, not just against our older models: roughly 4–8× too few heads bound at
   400 heads/µm² (2–4× allowing for single heads).
2. The most likely cause is structural. **The model demands the strong, stereospecific pose (2 nm, 25°, 25°, stiff
   dock) at the instant of first contact.** Real myosin binds weakly and loosely first, then converts.
3. Individual items with no biological basis:
   - **the 25° polarity gate** (literature: no polarity restriction at binding);
   - **the dock/orientation stiffness k_B = 512** (13–37× stiffer than Steffen 2001);
   - **commit on the nearest site only** (an artefact).
4. Items that are more restrictive than the literature:
   - the 2 nm hard capture sphere (literature: soft, several nm);
   - the hard 25° orientation cone (literature: soft, ±31°, wider when tethered).
5. Bound lifetime is ~2× short, but that is frozen chemistry and a separate question.

This agrees with the earlier single-motor finding (model description §7): the budget and polarity gate refuse every
candidate for a lone motor, and simply removing them traps the head in a gimbal lock. That is why the weak → strong
design (`WEAK_BINDING_DESIGN.md`) was drafted. The present literature pass supports that direction.

## 4. Suggested order of work (nothing changed yet)

1. **Measure before changing.** Add a first-failing-gate counter to the production path, or run the existing CPU
   single-motor tool (`-stroke-fixture -fix-search`, which relaxes one gate at a time). This shows which gate actually
   dominates the rejections. Without it, the ranking above is reasoning, not measurement.
2. Candidate structural changes, each motivated by the literature above:
   - polarity gate back to 90°, or removed (the dock energy already charges misalignment);
   - evaluate every passing site, not only the nearest;
   - check k_B against Steffen 2001 and the structural literature;
   - longer term: a weak-binding state with Boltzmann-weighted acceptance and an attachment rate (Duke 1999 style),
     replacing the hard geometric gates.
3. Separately, consider a denser lawn (≥ 1000 heads/µm²) as the standard scene, matching real assays.

## 5. Gaps in this literature pass

- Uyeda 1990, Toyoshima 1990 and Harada 1990: density and length numbers not verified (abstracts only).
- Geeves 1991 and Geeves & Holmes 1999 weak-binding constants: not read.
- No verified second-order on-rate for M·ADP·Pi binding actin.
- No direct count of how many monomers per 36 nm a surface-tethered HMM head can reach.
- Tanaka 1998 full text not read: direction of the antiparallel displacement and event frequency vs angle unverified.
- Temperatures across sources range from 0 to 30 °C, and ionic strength changes weak-attachment rates ~100×.
