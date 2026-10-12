# Paywalled articles to source (2026-10-08)

**Availability checked 2026-10-08 via Europe PMC.** Three are FREE and only need a browser download (my automated
downloads hit a bot check): Gebhardt 2006 (#3), Piazzesi 2007 (#4) and Tanaka 1998 (#7) -- links inline. The rest show
no free copy on Europe PMC; I did not find author manuscripts for Capitanio 2012 either. Capitanio's raw detachment
data ARE open (PMC6565606) and have already been read.

Papers whose full text I could not read, which bear on open model questions: rigor release direction, motor swivel,
low-[ATP] gliding, and Beausang comparison conditions. Drop PDFs in `docs/literature/` (or the repo root) and I will
read them. Citations marked *(from memory)* should be checked against the journal page. Some older Biophys J / PNAS /
JBC papers may already be free on PMC or the publisher site. Try that first.

## Priority 1: rigor / strongly-bound detachment vs load direction (the open rupture question)

1. **Capitanio M, Canepari M, Maffei M, Beneventi D, Monico C, Vanzi F, Bottinelli R, Pavone FS (2012).**
   *Ultrafast force-clamp spectroscopy of single molecules reveals load dependence of myosin working stroke.*
   Nature Methods 9:1013–1019. doi:10.1038/nmeth.2152. *(author list from memory)*
   - **Why:** fast-skeletal myosin at 15 µM ATP, axial loads −7…+7 pN. This is the closest match to Beausang's motor and
     [ATP].
   - **Extract:** detachment rates (k1 = ATP-limited, k2/k3 = premature) vs signed force; which sign is assisting;
     fitted distances.
2. **Eur Biophys J (2015)** (not the Lombardi group, as I first assumed). *The lifetime of the actomyosin complex in vitro under load corresponding
   to stretch of contracting muscle.* Springer, doi:10.1007/s00249-015-1048-3.
   - **Why:** lifetime under loads that mimic stretch (resisting) vs shortening.
   - **Extract:** rigor/ADP lifetime vs load magnitude and direction; myosin source; [ATP].
3. **Gebhardt JCM, Clemen AE-M, Jaud J, Rief M (2006).** *Myosin-V is a mechanical ratchet.* PNAS 103:8680–8685. **FREE:** https://www.pnas.org/content/pnas/103/23/8680.full.pdf (PMC1482639)
   - **Why:** direct forward vs backward pulling of strongly bound heads. Asymmetric rupture is a known myosin-V result.
   - **Extract:** rupture force/rate forward vs backward, nucleotide states, loading rates.
4. **Piazzesi G et al. (2007).** *Skeletal muscle performance determined by modulation of number of myosin motors rather
   than motor force or stroke size.* Cell 131:784–795 (PMID 18022371). **FREE:** http://www.cell.com/article/S0092867407012895/pdf
   - **Why:** fibre evidence on how negatively strained heads detach during shortening.
   - **Extract:** detachment rate vs strain, the model they fit.

## Priority 2: motor swivel / orientation (justifies or limits the free-azimuth S2 swivel)

5. **Toyoshima YY, Toyoshima C, Spudich JA (1989).** Nature 341:154–156 (PMID 2674720). *(title from memory: actin
   filaments moving in opposite directions on HMM-decorated tracks)*
   - **Extract:** how freely heads reorient; any speed difference by direction.
6. **Sellers JR, Kachar B (1990).** *Polarity and velocity of sliding filaments: control of direction by actin and of
   speed by myosin.* Science 249:406–408.
   - **Extract:** speed toward vs away from the bare zone; what limits backward-oriented heads.
7. **Tanaka H, Ishijima A, Honda M, Saito K, Yanagida T (1998).** *Orientation dependence of displacements by a single
   one-headed myosin relative to the actin filament.* Biophys J 75:1886–1894 (PMID 9746529). **FREE:** https://europepmc.org/articles/PMC1299859?pdf=render
   - **Extract:** step size vs myosin–actin angle (the ~10 / 0 / 5 nm result), so we can test whether free swivel
     overstates backward-tail motors.
8. **Gundapaneni D, Xu J, Root DD (2005).** J Struct Biol (PMID 15681228): S2 / S2–LMM hinge torsional flexibility.
   - **Extract:** rotation statistics, where the flexibility sits, any torsional stiffness estimate.
9. **Walker M, Knight P, Trinick J (1985).** J Mol Biol (PMID 2413217): myosin tail hinges at 44 / 76 nm. Also
   **Elliott A, Offer G (1978).** J Mol Biol (PMID 691054): head flexibility.
   - **Extract:** angle distributions at the head–rod junction and the S2 hinge (sets swivel and elevation stiffness).

## Priority 3: low-[ATP] / low-density gliding and comparison conditions

10. **Uyeda TQP, Kron SJ, Spudich JA (1990).** *Myosin step size: estimation from slow sliding movement of actin over low
    densities of heavy meromyosin.* J Mol Biol 214:699–710 (PMID 2143785).
    - **Why:** the classic detachment-limited picture at low density — directly our regime.
    - **Extract:** velocity vs density and [ATP], minimum motor number for continuous motion.
11. **Persson M et al. (2010).** FLIC measurement of actin height above HMM-coated TMCS surfaces *(details from memory;
    Langmuir or Nano Lett)*.
    - **Extract:** filament and head heights vs surface and [ATP] (our now-emergent filament height).
12. **ATP-binding rate vs ionic strength for fast-skeletal acto-S1.** The source behind our k = 2.4 /µM/s (Ritchie et al.
    1993 via Deacon et al. 2012) and anything at ~25 mM KCl (Beausang's buffer).
    - **Extract:** k_ATP at low salt and 22–25 °C.

## Already have / open access (no action)

- Guo & Guilford 2006 PNAS (PDF in repo root, read).
- Beausang et al. 2008 (arXiv 0810.3925, read).
- Nishizaka et al. 2000 Biophys J (abstract read; full text probably on PMC).
- PMC11657034 (cardiac / slow-skeletal, axial loads, read).
