package softbox;

/**
 * Physical constants, ported VERBATIM from BoA v1 (boxOfActin/Env.java and
 * boxOfActin/FilSegment.java at tag softbox-filref-2026-06-13). These are the
 * numbers the FDT check rests on; do not "round" or "clean" them — they must
 * match v1 so that Soft Box reproduces v1's diffusion coefficients.
 *
 * Provenance (file:line in ~/Code/BoA-v1ref):
 *   Boltz, tempK          Env.java:24,25
 *   deltaT (deltaT_init)  Env.java:110
 *   aeta  (aeta_init)     Env.java:404      (viscosity, Pa-s; 1e-3 is water)
 *   actinMonoDiam/Radius  Env.java:528,529
 *   actinWidth            Env.java:530
 *   stdSegLength          Env.java:574      (monomers per segment, int)
 *   BTransCoeff/BRotCoeff Env.java:580,583
 *   aParallel/aOrthog/aTurning  FilSegment.java:89,90,91
 *   radius = actinWidth/2 FilSegment.java:78
 *   halfmono = actinMonoRadius  FilSegment.java:77
 */
public final class Constants {
    private Constants() {}

    // --- thermodynamics ---
    public static final double Boltz = 1.380662e-23;   // J/K   (Env.java:24)
    public static final double tempK = 298.15;         // K     (Env.java:25)
    public static final double kT    = Boltz * tempK;  // J     (Einstein D = kT/gamma)

    // --- integration ---
    public static final double deltaT = 1e-4;          // s     (Env.java:110)

    // --- medium ---
    public static final double aeta = 0.1;             // Pa-s  (Env.java:404)

    // --- actin geometry (microns) ---
    public static final double actinMonoDiam   = 0.0054;                 // Env.java:528
    public static final double actinMonoRadius = actinMonoDiam / 2.0;    // Env.java:529  (= halfmono)
    public static final double actinWidth      = 0.007;                  // Env.java:530  (diameter)
    public static final double radius          = actinWidth / 2.0;       // FilSegment.java:78

    // --- segment defaults ---
    public static final int stdSegLength = 32;         // monomers/segment (Env.java:574)

    // --- wormlike-chain bending (Env.java:572-573): EI = kT*Lp ---
    public static final double persistenceLength = 15.0;          // microns (Env.java:572)
    public static final double EI = kT * (persistenceLength * 1.0e-6);  // N*m^2 (SI)

    // --- empirical rod-drag fit constants (dimensionless) FilSegment.java:89-91 ---
    public static final double aParallel = -0.20;
    public static final double aOrthog   =  0.84;
    public static final double aTurning  = -0.662;

    // --- Brownian amplitude coefficients (v1 production tuning knobs) Env.java:580,583 ---
    // NOTE: BRotCoeff=0.5 is a v1 biological persistence-length tuning knob, NOT part of
    // the FDT relation. The increment-1 amplitude-coupling validation runs with both
    // coefficients = 1.0 so the bare relation D = kT/gamma holds exactly. See JOURNAL.md.
    public static final double BTransCoeff = 1.0;
    public static final double BRotCoeff   = 0.5;

    // Brownian force magnitude prefactor used by the device kernel: sqrt(2 kT / dt).
    // (v1 GPUMoveThing.java:6786-6789; randForce = brownianForceMag * sqrt(gamma) * g.)
    public static double brownianForceMag() {
        return Math.sqrt(2.0 * kT / deltaT);
    }

    // --- inc 6c B2: actin nucleation + the implicit-actin pool (v1 Env / Crucible) ---
    public static final double AvogadroNum = 6.022e23;            // /mol (v1 Env.AvogadroNum)
    public static final double kNodeNuc    = 10.0;                // /node-s (Env.kNodeNuc_init:895)
    public static final int    actinSeed   = 3;                   // monomers to seed a filament (Env.actinSeed_init:539)
    public static final double nodeTetherDetachRate = 0.001;      // /s  (Env.nodeTetherDetachRate_init:602; v1 default INACTIVE)
    public static final double actinConcInit = 15.0;              // µM  (Env.actinConc_init:405)
    public static final double nodeRadius  = 0.05;                // µm  (Env.nodeRadius_init:430)
    public static final double fracMove    = 0.5;                 // PAIRS move coeff (Env.fracMove_init:134); the node-tether spring coeff

    // --- inc 6c: actin POLYMERIZATION (barbed-end elongation; v1 FilSegment / Env) ---
    public static final double biochemDeltaT      = 1.0e-3;       // s   (Env.biochemDeltaT_init:111) — the biochem clock
    public static final double kATPOn2WithFormin  = 11.6;         // µM⁻¹s⁻¹ barbed-end on-rate at a formin/node (Env.java:718)
    public static final int    minMonomerCt       = 30;           // interior min-length clamp (FilSegment.java:411)

    // --- inc 7 Stage 1: actin TURNOVER — pointed-end (end1) depolymerization (v1 FilSegment / Env) ---
    // Stage 1 uses a FIXED depoly rate (nucleotide-dependent rates are Stage 3). Default = the pointed-end
    // ATP-off rate kATPOff1 (the gentlest, most-stable baseline, faithful to a fresh ATP filament's pointed-off).
    public static final double kATPOff1 = 0.8;                    // /s pointed-end (end1) ATP-off rate (Env.kATPOff1_init:688)
    public static final double kADPOff1 = 2.7;                    // /s pointed-end (end1) ADP-off rate (Env.kADPOff1_init:690; Stage 3)

    // --- inc 7 Aging build (A): nucleotide hydrolysis cascade (per-segment proxy). v1 Monomer.hydrolize:182 /
    // dissociate:189 — the ATP→ADP-Pi→ADP aging that, in aggregate, makes the pointed end mostly ADP at steady
    // state (⇒ the nucleotide-asymmetric off-rate kADPOff1). Rates from Env.java (recon §1c).
    public static final double kHydrolysis   = 0.3;              // /s ATP → ADP-Pi   (Env.kHydrolysis_init)
    public static final double kDissociation = 1.0;              // /s ADP-Pi → ADP   (Env.kDissociation_init)

    // --- inc 7 Severing build (B): cofilin en-masse whole-segment dissolve (v1 FilSegment.checkCofilinDissolve:3741
    // + Monomer.cofilinBinding:243; recon §1e/§3b). Cofilin decorates ADP monomers at cofilinConc·cofilinRate·dt
    // (resisted by bundling /(bundleStableFactor·linkCt)); a segment with cofilinCt/monomerCt > cofilinRatio
    // dissolves en masse. The per-segment proxy tracks a cofilin FRACTION f_cof (the aggregate of the per-monomer
    // Bernoulli over ADP-non-cofilin monomers). Default cofilinRatio = 1.0 ⇒ dissolve OFF (a ratio can't exceed 1).
    public static final double cofilinRate        = 0.1;        // µM⁻¹s⁻¹ cofilin binding rate (Env.cofilinRate_init:748)
    public static final double cofilinConc        = 3.0;        // µM     cofilin concentration  (Env.cofilinConc_init:751)
    public static final double bundleStableFactor = 2.0;        // bundled fils resist cofilin /(factor·linkCt) (Env:628)
    public static final double cofilinRatio       = 1.0;        // dissolve threshold; 1.0 = OFF (Env.cofilinRatio_init:754)
}
