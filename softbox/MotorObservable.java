package softbox;

import java.util.Locale;

/**
 * ===================== CANONICAL MOTOR STIFFNESS / FORCE OBSERVABLE NAMES =====================
 * Standardized, unambiguous names for the two-body motor's stiffness and force observables. The 4H
 * investigation (docs/TWOBODY_TWEEZERS_4H_PRODUCER.md, docs/TWOBODY_4G_TO_4F_CALIBRATION.md §1) showed
 * that "k_ext" had been overloaded across DIFFERENT generalized-coordinate measurements — a pinned axial
 * response, an external-lever whole-crossbridge stiffness, and a free-tip bending stiffness are not the
 * same quantity. This enum keeps them separate so a report never conflates them.
 *
 * There is ONE common force-on-actin observable ({@link #F8_FORCE_ON_ACTIN}) shared across all models.
 *
 * These are NAMING/definition constants (no measurement code lives here); the measurement primitives are
 * the validated {@code supKext}/{@code s2Kext}, {@code supForce[3]}/{@code s2AxialTangent},
 * {@code calS2SampleTrans}, {@code segGather}, etc. in {@link TwoBodyConverterMotor}.
 * ============================================================================================
 */
public enum MotorObservable {

    /** Whole-crossbridge stiffness: perturb the trap axially, measure the filament restoring-force slope.
     *  FIXTURE-DEPENDENT (a mobile-pivot fixture reports a different value than a fixed anchor — the 4G §6 /
     *  4I "fixture-observable offset": explicit 0.99 vs calibrated 0.64 vs skeletal fixed-anchor). */
    EXTERNAL_CROSSBRIDGE_STIFFNESS("externalCrossbridgeStiffness", "pN/nm",
        "whole-crossbridge stiffness (supKext / s2Kext): trap axial perturbation → filament restoring-force slope"),

    /** The pivot's axial tangent stiffness under load (the implicit-K axial diagonal / axial series response).
     *  NOT the same coordinate as the external lever stiffness (the 4H lesson). */
    PIVOT_AXIAL_TANGENT("pivotAxialTangent", "pN/nm",
        "pivot axial tangent stiffness (supForce[3] / s2AxialTangent): axial F8–S2 series response at the pivot"),

    /** Free-axial transverse bending stiffness the search pivot actually feels (pivot pinned transverse,
     *  axial free to foreshorten). NOT the full-3D pin that engages the stiff stretch (the 4H artifact). */
    FREE_TIP_BENDING_STIFFNESS("freeTipBendingStiffness", "pN/nm",
        "free-tip transverse bending stiffness (k_tr; calS2SampleTrans): free-axial transverse reaction slope ≈ 3EI/L³"),

    /** The one common force-on-actin observable across all models: the axial F8 force gathered onto the
     *  bound segment (segGather · b̂). */
    F8_FORCE_ON_ACTIN("f8ForceOnActin", "pN",
        "force transmitted to actin through the F8 cross-bridge (segGather axial component · b̂); common to all models"),

    /** The substrate-support reaction. Zero DIRECT force on actin for every model (explicit: node-0
     *  reaction; calibrated: floor + tail reaction; fixed-anchor: the rigid anchor reaction). */
    SUBSTRATE_SUPPORT_REACTION("substrateSupportReaction", "pN",
        "substrate-support reaction (4G node-0 / 4F floor+tail / fixed-anchor rigid anchor); zero direct force on actin"),

    /** The assay-input trap stiffness (trapParams). */
    TRAP_STIFFNESS("trapStiffness", "pN/nm",
        "trap stiffness — assay input (trapParams), 0.05 pN/nm default in the two-body arc");

    /** The deprecated generic name that these observables replace — never expose this in a report. */
    public static final String DEPRECATED_GENERIC = "k_ext";

    private final String canonicalName;
    private final String unit;
    private final String definition;

    MotorObservable(String canonicalName, String unit, String definition) {
        this.canonicalName = canonicalName; this.unit = unit; this.definition = definition;
    }

    public String canonicalName() { return canonicalName; }
    public String unit() { return unit; }
    public String definition() { return definition; }

    /** A one-line "name (unit) — definition" description. */
    public String describe() {
        return String.format(Locale.US, "%s (%s) — %s", canonicalName, unit, definition);
    }

    /** Markdown table of all canonical observables (for the observable-naming doc / report headers). */
    public static String markdownTable() {
        StringBuilder sb = new StringBuilder("| observable | unit | definition |\n|---|---|---|\n");
        for (MotorObservable o : values())
            sb.append("| `").append(o.canonicalName).append("` | ").append(o.unit)
              .append(" | ").append(o.definition).append(" |\n");
        sb.append("\n> Deprecated: the generic `").append(DEPRECATED_GENERIC)
          .append("` — resolve to `externalCrossbridgeStiffness`, `pivotAxialTangent`, or "
                + "`freeTipBendingStiffness` (they are different generalized-coordinate measurements).\n");
        return sb.toString();
    }
}
