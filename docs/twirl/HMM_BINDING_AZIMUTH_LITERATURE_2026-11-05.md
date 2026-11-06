# Where do HMM heads bind around a gliding filament? Literature, 2026-11-05

**Question (jba).** Our bound heads land all round the lower half and sides of the filament (zone circular sd
about 85°, `docs/twirling/LATTICE_AUDIT_2026-11-05.md`). Vilfan's twirl needs a zone about 31° wide, measured
from the coverslip. Is there a measurement for HMM or myosin II in a gliding assay?

## Bottom line

1. **I found no direct measurement of binding azimuth in a gliding assay**, for HMM or for whole myosin II.
2. **Vilfan's narrow zone comes from Steffen 2001, and Steffen used S1, not HMM.** The S1 was adsorbed to a
   1.5 µm glass bead and had no S2, and the filament's rotation was constrained in the trap. So the 31° is the
   preference of an S1 with its adsorption fixed. It is not the zone of a tethered HMM.
3. **The indirect evidence points to a broad zone for tethered myosin, not a narrow one.** None of it is
   quantitative. So our 85° is not contradicted, and narrowing it now would be fitting to Vilfan, not following
   data.
4. **Beausang's left-handed twirl used whole myosin II on poly-L-lysine, not HMM.** Only about 20 % of
   filaments twirled. The one HMM gliding twirl measurement (Sase 1997, 2 mM ATP) found a pitch of about 1 µm
   but could not determine handedness.

## Sources

| source | system | what it says about azimuth |
|---|---|---|
| Steffen, Smith, Simmons & Sleep 2001, *PNAS* 98:14949 | **S1** adsorbed on a 1.5 µm glass bead; actin dumbbell in a trap | Zone of 3 monomers, half-width α^−1/2 with α = 3.05–3.7 (about 31°). "The actin filament is not able to rotate freely"; the rotational position is constrained for most dumbbells. Does not say which side of the filament the head binds. |
| Vilfan 2009, *Biophys J* (arXiv 0906.0784) | model | Preference is relative to the surface: binding "has its maxima for actin subunits oriented downwards". α = 4, 6, 8, with Steffen's 3.7 called "a lower estimate". Each motor is a point under the filament; no S2 and no reach round the filament. |
| Sase et al. 1997, *PNAS* 94:5646 | rabbit skeletal **HMM** on polylysine glass, 2 mM ATP | About 1 revolution per µm; handedness not determined. Myosin "runs" and skips protomers; each kick is "primarily along the axis", with torque below about 10 % of the sliding force. Weak interactions may occur "indiscriminately over any part of the actin surface" (their suggestion, not a measurement). |
| Beausang et al. 2008, *Biophys J* 95:5820 | **whole myosin II** (rabbit fast skeletal) on poly-L-lysine, 5–20 µM ATP, 0.1–0.5 µm/s | Left-handed in 94 of 97 twirling filaments, pitch −0.47 ± 0.2 µm; about 20 % of filaments twirled. Mechanisms discussed: step size relative to the helix, Vilfan's target zone (personal communication) and **drag from rigor heads**. None is singled out. |
| Katayama 1998, *J Mol Biol* 278:349 | rabbit **HMM** on mica, quick-freeze deep-etch EM during motility | With ATP, "all the HMM molecules were found attached to actin by one head with the majority projecting perpendicular to the filament axis". A deep-etch replica images the exposed upper surface, so these bound heads were visible beside the filament, not hidden under it. Qualitative; no azimuth histogram. |
| Warshaw et al. 1998, *PNAS* 95:8034 | smooth muscle myosin on nitrocellulose, single-fluorophore polarization in motility | The strongly bound in-plane angle ranges 35–59° between molecules. The authors read it as "the angle at which randomly adhered myosin has to reach out and attach to actin". In-plane only; nothing round the filament. |
| Persson et al. 2010, *Langmuir* (HMM height) | **HMM** on TMCS silane | Actin 38 ± 2 nm above the surface; catalytic sites on average 20–30 nm up. That is the mean over all heads, mostly unbound, and gives no bound azimuth. Our filament sits at about 28 nm. |
| Adamovic, Mijailovich & Karplus 2008, *Biophys J* 94:3779 | MD of S2 | S2 bending stiffness 0.010–0.012 pN/nm. Moving a head 90° round actin costs about 0.05 kT (our literature basis §5). |
| Wu et al. 2010 (insect flight muscle tomography) | muscle | 22 % of strong-binding heads outside the target zone; "substantial azimuthal flexibility … would permit strong binding bridges to form virtually anywhere". The muscle zone is set by the thick-filament lattice, which a gliding assay does not have. |
| Sato et al. 2023, *Sci Rep* 13:19908 | *Drosophila* myosin IC on a fluid lipid bilayer | Left-handed corkscrew, pitch 0.58 ± 0.16 µm at 1 mM ATP and about 10 nm/s. The authors invoke target-zone binding "on one side of the filament" but do not measure it. |

## What follows for the model

- **Zone width.** Nothing measured says the HMM zone in gliding is narrow. The 31° that Vilfan used belongs to S1
  adsorbed to a bead. Narrowing our zone, for example by stiffening the S2 swivel or the hinge, has no
  experimental basis at present. It would be a change chosen to make Vilfan's mechanism work.
- **The S2 is what matters.** Steffen's S1 had no S2. Every tethered system above shows heads reaching out (in
  Warshaw, Katayama and Wu). That matches the reach round the filament we see.
- **Beausang's own leading alternative is rigor-head drag.** That ties the left-handed twirl to the rigor
  population and to the still-OPEN rigor-rupture decision. It would also put the twirl at low ATP, where rigor
  dwells are long.
- **Whole myosin versus HMM.** Beausang's myosin II was whole myosin. Its heads sit on a long rod adsorbed to
  polylysine, so their tether is different from HMM's. Our motor is HMM-like (40 nm S2). No HMM low-ATP
  handedness measurement was found.

## Not found or not read

- No gliding-assay measurement of bound-head azimuth: no EM tomography of a motility assay, and no polarization
  study around the filament axis.
- Full text of Katayama 1998 not read (paywalled); abstract only.
- Kunitake, Sakurazawa & Honda 2010 (*BioSystems* 103:79) report up-and-down filament motion on HMM. Not read.
- Haraguchi et al. 2026 (*PNAS* 123:e2508686123): skeletal myosin II gliding paths are straight (curvature
  0.47 ± 8.8 °/µm). It does not address twirl.
