package softbox;

import java.io.PrintStream;
import java.util.Locale;

/**
 * ============================ CANONICAL TWO-BODY MOTOR MODEL REGISTRY ============================
 * The single, stable selection interface for the two-body S2-tail motor family. Three canonical
 * models, each an IMMUTABLE descriptor (frozen parameters + provenance + solver/cost metadata +
 * supported CPU/GPU paths + serialization id + viewer representation + known limitations):
 *
 *   • {@link #FIXED_ANCHOR}       — strongly-supported assay fixture (tweezers / regression baseline).
 *   • {@link #EXPLICIT_S2_L40}    — mechanistic reference: explicit fixed-contour MD-informed S2 beam.
 *   • {@link #CALIBRATED_S2_L40}  — production surrogate: cheap analytic movable-pivot law fitted to
 *                                    EXPLICIT_S2_L40 (Experiment 4I).
 *
 * DESIGN (per jba's canonicalization decision, 2026-07-15): this is a DESCRIPTOR + REGISTRY layer.
 * It does NOT move or fork the validated motor core. The validated head / converter / neck–lever / F8
 * spring / stereospecific binding gate / Lymn–Taylor chemistry + kinetic constants / force ordering /
 * RNG / actin interaction are SHARED across all three models and live unchanged in
 * {@link TwoBodyConverterMotor}; the models differ ONLY in the tail-support fixture branch inside
 * {@code Cmot} (fixed anchor / explicit beam / calibrated pivot). A full {@code CommonMotorCore} +
 * {@code MotorFixture} OOP extraction is DEFERRED (see {@code docs/MOTOR_MODELS.md} §"Deferred
 * extraction" for the trigger conditions).
 *
 * This class is PURE (no {@code Cmot} dependency): it holds the frozen numbers, resolves ids/aliases,
 * logs, and serializes. The one place that configures a {@code Cmot} from a model is the centralized
 * {@link TwoBodyConverterMotor#buildBoundMotor(MotorModel,double)} builder.
 *
 * The frozen values below are the SINGLE SOURCE OF TRUTH; they mirror the documented Experiment 4G
 * (docs/TWOBODY_MD_INFORMED_S2.md) and Experiment 4I (docs/TWOBODY_4G_TO_4F_CALIBRATION.md) results.
 * ================================================================================================
 */
public enum MotorModel {

    // ---- FIXED_ANCHOR: rigid substrate anchor; the pivot is pinned and never integrated (zero tail work). ----
    FIXED_ANCHOR(
        "fixed-anchor", "Fixed-anchor (strongly-supported assay fixture)",
        new FixedAnchorParams(
            "Rigid substrate anchor: the converter pivot P is pinned to the fixed anchor A and is "
          + "never integrated (the tail performs zero work by construction). No compliant tail DOF, "
          + "no S2 beam, no calibrated pivot law. The strongly-supported limit of the S2 family "
          + "(supOn=false / g4On=false)."),
        new Provenance("3A–3F, 4H", "", "4H (blinded tweezers)", Double.NaN,
            "assay-conditioned strongly-supported fixture", "", ""),
        new CostModel(/*usPerMotorStep*/ 1.0, /*internalDof*/ 0, /*nodesPerMotor*/ 1,
            /*cpu*/ true, /*gpu*/ false,
            "No tail solve; cheapest fixture. Two-body arc is CPU-only (run_lasertrap.sh refuses -gpu)."),
        "rigid anchor glyph (fixed substrate point + converter pivot; no beam, no reduced-surrogate label)",
        new String[]{
            "Infinitely stiff support: cannot represent a compliant/mobile S2 tail, so it under-recruits "
          + "and over-reports whole-crossbridge stiffness relative to the S2 models.",
            "Appropriate for tweezers-like strongly-supported assays and as a regression baseline, "
          + "NOT for free-search recruitment studies."},
        new String[]{"-exp4h", "-twobody-tweezers-blinded"}),

    // ---- EXPLICIT_S2_L40: the mechanistic reference beam (Experiment 4G at free length L = 40 nm). ----
    EXPLICIT_S2_L40(
        "explicit-s2-l40", "Explicit MD-informed S2 beam (L = 40 nm) — mechanistic reference",
        ExplicitS2Params.frozenL40(),
        new Provenance("4G", "Adamovic, Mijailović & Karplus 2008", "4H", 40.0,
            "canonical mechanistic reference", "", ""),
        new CostModel(/*usPerMotorStep*/ 58.0, /*internalDof*/ 14 /* =3M+2, M=4 */, /*nodesPerMotor*/ 5 /* M+1 */,
            /*cpu*/ true, /*gpu*/ false,
            "(3M+2)-DOF implicit beam solve with a numeric beam tangent per motor per step; CPU-only "
          + "(no GPU implementation). ~58 us/motor-step at L40 (≈43x the calibrated surrogate)."),
        "explicit beam: render the actual internal S2 nodes and segments (clamped emergence → distal pivot)",
        new String[]{
            "CPU-only — no GPU implementation; a GPU request must fail clearly, never silently fall back "
          + "or silently swap to the surrogate.",
            "~43x more costly per motor-step than CALIBRATED_S2_L40 at L40; cost grows with M (∝ free length).",
            "Frozen at free length L = 40 nm. Do NOT length-scale to L60 by a simple axial 1/L law: the "
          + "longer beam's bending compliance softens the effective axial reaction ~2x below ks/M (class C)."},
        new String[]{"-exp4g", "-twobody-explicit-s2"}),

    // ---- CALIBRATED_S2_L40: the production surrogate (Experiment 4I fit to EXPLICIT_S2_L40). ----
    CALIBRATED_S2_L40(
        "calibrated-s2-l40", "Calibrated S2 surrogate (L = 40 nm) — production surrogate",
        CalibratedS2Params.frozenL40(),
        new Provenance("4I", "Adamovic, Mijailović & Karplus 2008 (via EXPLICIT_S2_L40)", "4G spot-checks", 40.0,
            "canonical production surrogate",
            "relaxed pivot displacement / reaction (s2RelaxHold)", "EXPLICIT_S2_L40"),
        new CostModel(/*usPerMotorStep*/ 1.34, /*internalDof*/ 5 /* movable pivot 5-DOF implicit */, /*nodesPerMotor*/ 1,
            /*cpu*/ true, /*gpu*/ true,
            "Analytic anisotropic pivot force + analytic diagonal tangent; one 5x5 implicit pivot step. "
          + "~1.34 us/motor-step (≈43x cheaper than EXPLICIT_S2_L40 at L40). GPU-friendly (no inner beam "
          + "solve); the two-body arc harness is presently CPU-only."),
        "reduced surrogate: effective tether/pivot glyph labelled 'reduced surrogate' (NEVER an explicit beam)",
        new String[]{
            "Search RMS ≈ 29% smaller than EXPLICIT_S2_L40 (tighter unbound-search envelope).",
            "Capture footprint ≈ 38% smaller than EXPLICIT_S2_L40.",
            "Over-counts load-bearing state: the symmetric-stiff axial makes every bound motor taut, vs "
          + "≈67% taut for the explicit beam (which lets ~33% sit in the bending regime under transverse load).",
            "Close agreement with EXPLICIT_S2_L40 in axial force, axial tangent, tangent/continuity, unloaded "
          + "stroke (≈5%), pivot recoil, reduced-mat recruitment rate, and average binding — results "
          + "sensitive to search reach or the load-bearing-state fraction should be bracketed or 4G spot-checked.",
            "NOT a validated surrogate for L60 by simple length scaling (axial 1/L overpredicts ~2x; class C). "
          + "Retain a distinct L60 fixture for exposed long-tail assays."},
        new String[]{"-exp4f", "-twobody-supported-s2-tail", "-exp4i", "-twobody-s2-surrogate-calibration"});

    /** Canonicalization schema version (bump on any frozen-parameter or provenance change). */
    public static final int CANON_VERSION = 1;

    // ------------------------------------------------------------------ instance state
    private final String id;
    private final String displayName;
    private final FixtureParams params;
    private final Provenance provenance;
    private final CostModel cost;
    private final String viewerRepresentation;
    private final String[] limitations;
    private final String[] aliases;

    MotorModel(String id, String displayName, FixtureParams params, Provenance provenance,
               CostModel cost, String viewerRepresentation, String[] limitations, String[] aliases) {
        this.id = id; this.displayName = displayName; this.params = params;
        this.provenance = provenance; this.cost = cost;
        this.viewerRepresentation = viewerRepresentation; this.limitations = limitations; this.aliases = aliases;
    }

    // ------------------------------------------------------------------ accessors
    public String id() { return id; }
    public String displayName() { return displayName; }
    public FixtureParams params() { return params; }
    public Provenance provenance() { return provenance; }
    public CostModel cost() { return cost; }
    public String viewerRepresentation() { return viewerRepresentation; }
    public String[] limitations() { return limitations.clone(); }
    public String[] aliases() { return aliases.clone(); }

    /** The frozen explicit-beam parameters (only for EXPLICIT_S2_L40). */
    public ExplicitS2Params explicit() {
        if (!(params instanceof ExplicitS2Params p))
            throw new IllegalStateException(id + " has no explicit-S2 parameters");
        return p;
    }
    /** The frozen calibrated-surrogate parameters (only for CALIBRATED_S2_L40). */
    public CalibratedS2Params calibrated() {
        if (!(params instanceof CalibratedS2Params p))
            throw new IllegalStateException(id + " has no calibrated-surrogate parameters");
        return p;
    }

    public boolean cpuSupported() { return cost.cpu(); }
    public boolean gpuSupported() { return cost.gpu(); }

    // ------------------------------------------------------------------ resolution
    /** Resolve a public id or a historical alias to a model. Throws with a clear message otherwise. */
    public static MotorModel fromId(String token) {
        if (token == null) throw new IllegalArgumentException("null motor id");
        String t = token.trim().toLowerCase(Locale.US);
        for (MotorModel m : values()) {
            if (m.id.equals(t)) return m;
            for (String a : m.aliases) if (a.equals(t)) return m;
        }
        StringBuilder sb = new StringBuilder("unknown motor model '" + token + "'. Valid ids: ");
        for (MotorModel m : values()) sb.append(m.id).append(' ');
        sb.append("(aliases resolve historical -exp4f/-exp4g/-exp4h/-exp4i).");
        throw new IllegalArgumentException(sb.toString());
    }

    /** The result of scanning a command line for a motor selection. */
    public record Resolution(MotorModel model, String source, boolean explicit) {}

    /**
     * Scan an argv for a motor selection. Recognizes {@code -motor <id>} (an EXPLICIT selection) and the
     * historical {@code -exp4f/-exp4g/-exp4h/-exp4i} (and long) aliases (an IMPLICIT selection for
     * reproduction). Returns {@code null} if the command line names no motor at all — the caller then
     * applies its own historical default (do NOT force a universal default here).
     */
    public static Resolution scan(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-motor") && i + 1 < args.length)
                return new Resolution(fromId(args[i + 1]), "-motor " + args[i + 1], true);
        }
        for (int i = 0; i < args.length; i++) {
            for (MotorModel m : values())
                for (String a : m.aliases)
                    if (args[i].equals(a))
                        return new Resolution(m, a + " (historical reproduction alias)", false);
        }
        return null;
    }

    // ------------------------------------------------------------------ serialization / restart identity
    /** A compact one-line identity token for configs / checkpoints / trajectory exports / viewer metadata. */
    public String serialize() {
        double refLen = provenance.freeLenNm();
        return String.format(Locale.US, "motorModel=%s;canonVersion=%d;refFreeLenNm=%s",
            id, CANON_VERSION, Double.isNaN(refLen) ? "na" : fmt(refLen));
    }

    /** Parse a {@link #serialize()} token back to a model (ignores the version/refLen fields for lookup). */
    public static MotorModel parseSerialized(String token) {
        String idPart = token;
        for (String kv : token.split(";")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].trim().equals("motorModel")) idPart = p[1].trim();
        }
        return fromId(idPart);
    }

    /**
     * Restart / checkpoint compatibility: a checkpoint written under model {@code this} may be resumed
     * under {@code requested} only if they are the same model, UNLESS the caller explicitly opts into a
     * conversion. Fixture internal state (explicit S2 node coordinates vs. a reduced pivot) is not
     * interchangeable, so a mismatch must be REJECTED rather than silently reinterpreted.
     */
    public boolean isRestartCompatible(MotorModel requested) { return this == requested; }

    // ------------------------------------------------------------------ logging
    /** One-line banner naming the resolved model + source (always print this on any two-body run). */
    public String resolvedBanner(String source) {
        return String.format(Locale.US,
            "# MOTOR MODEL RESOLVED: %s  [id=%s, source=%s, status=%s]",
            displayName, id, source, provenance.status());
    }

    /** Full resolved-model + complete fixture configuration log (required on every run). */
    public void logResolved(PrintStream out, String source, double dt, double trapPnNm) {
        out.println("# =============================================================================");
        out.println(resolvedBanner(source));
        out.printf(Locale.US, "#   assay: dt=%.2e s, trap=%.3f pN/nm, runner=CPU (two-body arc)%n", dt, trapPnNm);
        out.println("#   provenance: " + provenance.oneLine());
        out.println("#   cost/memory: " + cost.oneLine());
        out.printf(Locale.US, "#   CPU=%s  GPU=%s%n", cost.cpu() ? "yes" : "no", cost.gpu() ? "yes (surrogate)" : "no");
        out.println("#   viewer: " + viewerRepresentation);
        out.println("#   fixture: " + params.summary());
        out.println("#   known limitations:");
        for (String l : limitations) out.println("#     - " + l);
        out.println("#   serialize: " + serialize());
        out.println("# =============================================================================");
    }

    /**
     * A JSON fragment identifying the model for viewer frame metadata. The {@code fixtureKind} lets the
     * viewer pick the render convention WITHOUT re-deriving it: an explicit beam (actual nodes+segments)
     * vs. a reduced-surrogate tether/pivot glyph (labelled "reduced surrogate") vs. a rigid fixed anchor.
     * A calibrated surrogate must NEVER be drawn as an explicit segmented beam.
     */
    public String viewerMetaJson() {
        double refLen = provenance.freeLenNm();
        String kind = switch (this) {
            case EXPLICIT_S2_L40 -> "explicit-beam";
            case CALIBRATED_S2_L40 -> "reduced-pivot";
            case FIXED_ANCHOR -> "fixed-anchor";
        };
        return String.format(Locale.US,
            "{\"motorModel\":\"%s\",\"fixtureKind\":\"%s\",\"freeS2LenNm\":%s,\"reducedSurrogate\":%b,"
          + "\"canonVersion\":%d,\"render\":\"%s\"}",
            id, kind, Double.isNaN(refLen) ? "null" : fmt(refLen), this == CALIBRATED_S2_L40,
            CANON_VERSION, viewerRepresentation);
    }

    /** Provenance block suitable as a leading comment header in an output/CSV/log file. */
    public String provenanceHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("# motor-model: ").append(id).append(" (").append(displayName).append(")\n");
        sb.append("# ").append(serialize()).append('\n');
        sb.append("# provenance: ").append(provenance.oneLine()).append('\n');
        sb.append("# fixture: ").append(params.summary()).append('\n');
        return sb.toString();
    }

    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e6) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.4g", v);
    }

    // ==================================================================================================
    //  Immutable descriptor records (frozen after construction — the single source of truth).
    // ==================================================================================================

    /** Marker for a fixture parameter block. */
    public interface FixtureParams { String summary(); }

    /** Provenance metadata attached to every model (fields not applicable to a model are "" / NaN). */
    public record Provenance(String sourceExperiment, String molecularReference, String validationExperiment,
                             double freeLenNm, String status, String calibrationCoordinate, String referenceModel) {
        public String oneLine() {
            StringBuilder sb = new StringBuilder();
            sb.append("source=Experiment ").append(sourceExperiment);
            if (!molecularReference.isEmpty()) sb.append("; molecular ref=").append(molecularReference);
            if (!validationExperiment.isEmpty()) sb.append("; validation=Experiment ").append(validationExperiment);
            if (!Double.isNaN(freeLenNm)) sb.append("; freeLen=").append(fmt(freeLenNm)).append(" nm");
            if (!referenceModel.isEmpty()) sb.append("; referenceModel=").append(referenceModel);
            if (!calibrationCoordinate.isEmpty()) sb.append("; calibrationCoord=").append(calibrationCoordinate);
            sb.append("; status=").append(status);
            return sb.toString();
        }
    }

    /** Solver / cost / memory metadata + supported runners. */
    public record CostModel(double usPerMotorStep, int internalDof, int nodesPerMotor,
                            boolean cpu, boolean gpu, String note) {
        public String oneLine() {
            return String.format(Locale.US, "%.2f us/motor-step, internalDOF=%d, nodes/motor=%d — %s",
                usPerMotorStep, internalDof, nodesPerMotor, note);
        }
    }

    /**
     * FROZEN explicit MD-informed S2 beam parameters (EXPLICIT_S2_L40). Mirrors the Experiment 4G
     * constants ({@code TwoBodyConverterMotor.EXP4G_*}); {@link TwoBodyConverterMotor#assertFrozenParamsConsistent}
     * cross-checks bit-for-bit against the live code constants.
     */
    public record ExplicitS2Params(double freeLenNm, double segLenNm, int nSegments,
                                   double eaSI, double eiSI, double kAxPerLpNnm, double persistenceLenNm,
                                   double nodeDragRadiusNm, String brownianPolicy, String boundaryConditions,
                                   String solverType, double dtRequired) implements FixtureParams {
        /** The canonical L = 40 nm block. EA/EI derive from the MD-informed L_ref = 60 nm moduli. */
        public static ExplicitS2Params frozenL40() {
            final double kAxRefPnNm = 70.0;   // axial stretch stiffness of the L_ref=60 nm free S2 (lit 60–80)
            final double kLatRefPnNm = 0.01;  // lateral (bending) endpoint stiffness of the L_ref=60 nm free S2
            final double lRefNm = 60.0;
            final double l0Nm = 10.0;         // beam segment (discretization) length
            final double Lnm = 40.0;
            int M = (int) Math.round(Lnm / l0Nm);            // = 4
            double eaSI = kAxRefPnNm * 1e-3 * lRefNm * 1e-9;                        // 4.2e-9 N
            double eiSI = kLatRefPnNm * 1e-3 * Math.pow(lRefNm * 1e-9, 3) / 3.0;    // 7.2e-28 N·m²
            double kAxPerL = eaSI / (Lnm * 1e-9) * 1e3;                             // EA/L (N/m) → pN/nm (=105 at L40)
            double lpNm = eiSI / Constants.kT * 1e9;                                // persistence length EI/kT
            return new ExplicitS2Params(Lnm, l0Nm, M, eaSI, eiSI, kAxPerL, lpNm, 5.0,
                "per-node overdamped Langevin FDT (kT); clamped emergence node fixed; distal node = motor pivot",
                "clamped supported emergence (fixed point + tangent) at node 0; distal node is the converter "
              + "pivot; fixed contour (stiff stretch); no material-state / nucleotide stiffness switch; zero "
              + "direct S2 force on actin",
                "(3M+2)-DOF implicit beam solve with a numeric beam tangent (M=4 ⇒ 14 DOF)",
                2.5e-6);
        }
        @Override public String summary() {
            return String.format(Locale.US,
                "explicit S2 beam: L=%.0f nm, %d segments of %.0f nm (%d nodes), EA=%.3e N, EI=%.3e N·m², "
              + "k_ax=EA/L=%.1f pN/nm, Lp=%.0f nm, node drag r=%.0f nm; %s; dt≤%.1e",
                freeLenNm, nSegments, segLenNm, nSegments + 1, eaSI, eiSI, kAxPerLpNnm, persistenceLenNm,
                nodeDragRadiusNm, solverType, dtRequired);
        }
    }

    /**
     * FROZEN calibrated movable-pivot surrogate parameters (CALIBRATED_S2_L40), fitted to EXPLICIT_S2_L40
     * in Experiment 4I (docs/TWOBODY_4G_TO_4F_CALIBRATION.md, Phase B "fitted_params"). These are the
     * documented FIT OUTPUTS, not the stale line-7127 code seed. No-slack anisotropic law; symmetric-stiff
     * compression (Euler buckling disabled in-range at L40 — an L60 feature); no binding/nucleotide switch.
     */
    public record CalibratedS2Params(double kAxTensionPnNm, double kAxCompressionPnNm, String compressionBranchState,
                                     double kTrPnNm, double kFeTrPnNm, double rMaxOnsetNm,
                                     double smoothAxNm, double smoothTrNm, double smoothBuckNm,
                                     double kFloorPnNm, double refFreeLenNm, String calibrationSource)
            implements FixtureParams {
        public static CalibratedS2Params frozenL40() {
            return new CalibratedS2Params(
                /*kAxTension*/     105.0,   // = ks/M = EA/L at L40 (exact)
                /*kAxCompression*/ 105.0,   // symmetric-stiff (4G-L40 stays on the straight-stiff branch)
                "symmetric-stiff (Euler buckling disabled in-range; F_crit≈4.44 pN is an L60 feature)",
                /*kTr*/            0.026,    // free-axial bending reaction slope (≈3EI/L³); search-observable ambiguity ~1.5x
                /*kFeTr*/          20.0,     // transverse finite-extension (runaway limiter; inactive over visited states)
                /*rMaxOnset*/      20.3,     // FE onset ≈ 2× search RMS (frozen to the documented 4I value)
                /*smoothAx*/       0.5,
                /*smoothTr*/       1.5,      // narrow so the FE tail does not leak inside visited states
                /*smoothBuck*/     0.8,
                /*kFloor*/         20.0,     // one-sided substrate floor
                /*refFreeLen*/     40.0,
                "Experiment 4I (2026-07-15): fit to EXPLICIT_S2_L40 relaxed-pivot reaction; k_ax=EA/L exact; "
              + "canonical calibration schema v" + CANON_VERSION);
        }
        @Override public String summary() {
            return String.format(Locale.US,
                "calibrated pivot surrogate (no-slack): k_ax(tension)=%.1f pN/nm, k_ax(compression)=%.1f pN/nm "
              + "[%s], k_tr=%.3f pN/nm, k_feTr=%.0f pN/nm @ rMax=%.1f nm, smoothings (ax/tr/buck)=%.1f/%.1f/%.1f nm, "
              + "floor=%.0f pN/nm, refFreeLen=%.0f nm; %s",
                kAxTensionPnNm, kAxCompressionPnNm, compressionBranchState, kTrPnNm, kFeTrPnNm, rMaxOnsetNm,
                smoothAxNm, smoothTrNm, smoothBuckNm, kFloorPnNm, refFreeLenNm, calibrationSource);
        }
    }

    /** FIXED_ANCHOR fixture (no free parameters — the rigid strongly-supported limit). */
    public record FixedAnchorParams(String description) implements FixtureParams {
        @Override public String summary() { return description; }
    }
}
