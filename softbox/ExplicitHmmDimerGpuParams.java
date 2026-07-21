package softbox;

/**
 * ============ EXPLICIT-HMM-DIMER GPU BACKEND — shared immutable parameter + validation gate ============
 * The single source of truth for the GPU-backend contract of {@link ExplicitHmmDimer} /
 * {@link ExplicitHmmDimerGlidingHarness}. Both the CPU oracle and the device path key off THIS class so
 * they cannot silently diverge (task §21). Physical constants are NOT duplicated here — they live in
 * {@link ExplicitHmmDimer} / {@link TwoBodyConverterMotor}; this class fixes only (a) the promotion gate,
 * (b) the runtime backend selector, (c) the FROZEN forked topology the first kernel is specialized to, and
 * (d) the standing-config descriptor, with a self-check ({@link #assertStandingConfig}) that trips if the
 * harness build parameters ever drift from the values the GPU kernel was validated against.
 *
 * PROMOTION GATE ({@link #DEVICE_VALIDATED}): starts FALSE. While false, {@code -backend gpu} is REFUSED
 * unless {@link #EXPERIMENTAL_OVERRIDE} is set (task §1/§19). It flips to true ONLY after every mandatory
 * gate passes (deterministic fixtures V1–V8, moving-actin, CPU/GPU aggregate equivalence, polarity + A/B
 * symmetry, occupancy exclusion, compliance health, D0 directionality, zero invalid states / solve
 * failures, and a meaningful speedup). CPU stays the permanent oracle after promotion.
 * ========================================================================================================
 */
public final class ExplicitHmmDimerGpuParams {

    // ---------------- promotion gate (task §1, §19) ----------------
    /** Master gate. FALSE until ALL mandatory validation gates pass. Do NOT flip as a side effect. */
    public static final boolean DEVICE_VALIDATED = false;
    /** Experimental escape hatch: allows -backend gpu while un-validated (explicit, opt-in). Never a default. */
    public static boolean EXPERIMENTAL_OVERRIDE = false;

    // ---------------- runtime backend selector (task §1/§22) ----------------
    public enum Backend {
        /** Current {@link ExplicitHmmDimer#solve} object solver + existing CPU orchestration, unchanged (the oracle). */
        CPU,
        /** G2/G3 HYBRID: device-resident forked-dimer MECHANICS only; CPU owns bind/chem/gather/actin. */
        GPU_MECH,
        /** G4 (experimental): the full device-resident timestep (bind+chem+bondforce+gather+actin on device). */
        GPU_FULL_EXPERIMENTAL,
        /** Run matched CPU and GPU paths from identical initial states and compare (task §9–§11). */
        GPU_VALIDATE,
        /** Back-compat alias for the un-validated raw device path (== GPU_MECH gate). */
        GPU;

        public static Backend parse(String s) {
            if (s == null) return CPU;
            switch (s.toLowerCase()) {
                case "cpu":                   return CPU;
                case "gpu-mech":              return GPU_MECH;
                case "gpu-full-experimental": return GPU_FULL_EXPERIMENTAL;
                case "gpu-validate":          return GPU_VALIDATE;
                case "gpu":                   return GPU;
                default: throw new IllegalArgumentException("unknown -backend '" + s + "' (cpu|gpu-mech|gpu-full-experimental|gpu-validate)");
            }
        }
    }

    /** Tangent variant for the device path (task §6). FD = exact CPU replica; ANALYTIC = flat-scratch Hessian. */
    public enum Tangent { FINITE_DIFFERENCE, ANALYTIC }

    /** Working precision of the device solve (task §5). FLOAT is the primary device path: the raw-SI ill-scaling
     *  that made double look necessary is removed by PHYSICALLY SCALED coordinates/residuals (nm, pN, rad), which
     *  put every matrix entry at O(1)–O(500). DOUBLE is retained ONLY as the CPU oracle + diagnostic comparison. */
    public enum Precision { FLOAT, DOUBLE, MIXED }

    // ---------------- FROZEN forked topology the first kernel is specialized to (task §2, §7) ----------------
    // Standing assay: shared S2 = 3 segments, one branch segment per head, symmetric Y fork.
    public static final int MS = 3;                 // shared-S2 segment count
    public static final int MA = 1;                 // branch-A segment count
    public static final int MB = 1;                 // branch-B segment count
    public static final int NF = MS + MA + MB;      // free-node count = 5 (nodes 1..NF; node 0 = clamped emergence)
    public static final int NODES = NF + 1;         // total nodes incl. clamped emergence = 6
    public static final int NDOF = 3 * NF + 4;      // solve dimension = 19 (15 node DOFs + phiA,psiA,phiB,psiB)
    public static final int W = NDOF + 1;           // augmented-matrix width (RHS column) = 20
    /** Per-work-item flat augmented-system scratch stride (row-major NDOF×W). */
    public static final int SYS_STRIDE = NDOF * W;  // 19*20 = 380 doubles per active dimer

    // ---------------- standing physical config (task "Standing physical configuration") ----------------
    // These are the values the GPU kernel is validated against; the harness build MUST match (self-check below).
    public static final double STANDING_DT          = 2.5e-6;
    public static final double STANDING_ALPHA_DEG   = 10.0;   // fork rest half-angle
    public static final double STANDING_SPLAY_DEG   = 16.0;
    public static final double STANDING_BRANCH_LEN_NM = 10.0;
    public static final double STANDING_BREI        = 0.25;   // reference branch bending multiplier
    public static final double STANDING_BRANCH_EA   = 0.03;   // branchEA=0.03 ⇒ eff. axial ≈ 12.6 pN/nm
    public static final double STANDING_FORK_K      = 1.0;
    public static final double STANDING_EXCLUSION_NM = 5.4;   // same-filament occupancy exclusion (ON)
    public static final int    STANDING_DMODE       = 0;      // D0 directional mode

    // ---------------- current backend defaults (task §5/§6) ----------------
    public static final Tangent   DEFAULT_TANGENT   = Tangent.FINITE_DIFFERENCE;
    public static final Precision DEFAULT_PRECISION = Precision.FLOAT;   // primary device path (scaled units)

    // ---------------- physical scaling (float primary path) ----------------
    // The flat FLOAT kernel works in nm / pN / rad; conversions from the object oracle's µm / SI-N / rad:
    public static final double NM_PER_UM   = 1e3;     // length:   µm → nm
    public static final double PN_PER_N    = 1e12;    // force:    N  → pN
    public static final double PNNM_PER_NM = 1e3;     // stiffness N/m → pN/nm      (= PN_PER_N / NM_PER_UM · 1e-6? see note)
    public static final double PNNM_PER_NM_RAD2 = 1e21; // torsional N·m/rad² → pN·nm/rad²

    /**
     * Guard: a requested-but-unavailable GPU backend fails clearly; it NEVER silently falls back to CPU (§1, §20).
     * Call this at harness startup after parsing -backend.
     */
    public static void requireUsable(Backend b) {
        boolean deviceProd = b == Backend.GPU || b == Backend.GPU_MECH || b == Backend.GPU_FULL_EXPERIMENTAL;
        if (deviceProd && !(DEVICE_VALIDATED || EXPERIMENTAL_OVERRIDE)) {
            throw new IllegalStateException(
                "-backend " + b + " refused: ExplicitHmmDimerGpuParams.DEVICE_VALIDATED=false. " +
                "Use -backend gpu-validate to cross-check, or -gpu-experimental to force the un-validated device path.");
        }
    }

    /**
     * Self-check (§21): trip if the harness build parameters drift from the frozen standing config the GPU kernel
     * was validated against. Tolerances are exact-equality on the config knobs (they are compile-time constants).
     */
    public static void assertStandingConfig(int ms, int ma, int mb, double dt, double alphaDeg, double branchLenNm,
                                            double branchEA, double forkK, double exclusionNm, int dmode) {
        StringBuilder err = new StringBuilder();
        if (ms != MS || ma != MA || mb != MB) err.append("topology(").append(ms).append(',').append(ma).append(',').append(mb).append(")!=(3,1,1) ");
        if (dt != STANDING_DT) err.append("dt=").append(dt).append("!=").append(STANDING_DT).append(' ');
        if (alphaDeg != STANDING_ALPHA_DEG) err.append("alpha=").append(alphaDeg).append(' ');
        if (branchLenNm != STANDING_BRANCH_LEN_NM) err.append("branchLen=").append(branchLenNm).append(' ');
        if (branchEA != STANDING_BRANCH_EA) err.append("branchEA=").append(branchEA).append(' ');
        if (forkK != STANDING_FORK_K) err.append("forkK=").append(forkK).append(' ');
        if (exclusionNm != STANDING_EXCLUSION_NM) err.append("exclusion=").append(exclusionNm).append(' ');
        if (dmode != STANDING_DMODE) err.append("dmode=").append(dmode).append(' ');
        if (err.length() > 0)
            throw new IllegalStateException("GPU standing-config drift (kernel not validated for this config): " + err);
    }

    private ExplicitHmmDimerGpuParams() {}
}
