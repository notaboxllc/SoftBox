package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * PERIODIC MOTOR-LAWN RE-ENTRY — a fixture feature that makes the motor field effectively unbounded.
 *
 * <p><b>Noncanonical, flag-gated, DEFAULT-OFF, and an explicitly authorised relaxation of the
 * "fixture geometry unchanged" freeze.</b> It exists because a prescribed-translation study of a
 * <i>steady</i> twirl is impossible on a finite lawn: with {@code MATX = 2.0 µm} and a 2.106 µm filament,
 * both centred on the origin, the engaged overlap decays linearly and reaches ZERO at 2.053 µm of travel,
 * so the window on which steadiness is judged contains no motors at all.
 *
 * <h2>What it does, and what it deliberately does not do</h2>
 * A motor that has fallen behind the filament's trailing end by more than {@code margin} is translated
 * <b>forward along the filament axis by an integer number of lawn periods</b>. Because the displacement is
 * an exact multiple of the lawn length, the motor field is the original random lawn <b>tiled periodically</b>
 * — so the areal density, the spacing statistics and the per-motor geometry are all <b>exactly</b> what they
 * were, at every instant. Nothing is redrawn, no RNG stream is touched, and no motor property changes.
 *
 * <ul>
 *   <li><b>Only unbound motors are moved.</b> A bound head is never teleported. The recycle threshold sits
 *       well behind the filament's trailing end, so a motor eligible for recycling cannot reach the
 *       filament anyway; the {@code boundSeg < 0} test is belt-and-braces.</li>
 *   <li><b>The jump clears the whole filament.</b> The displacement must exceed the filament contour plus
 *       the margin, or a recycled motor would re-appear <i>underneath</i> the filament mid-span. With a
 *       2.0 µm period and a 2.106 µm filament that means jumping <b>two</b> periods, not one.</li>
 *   <li><b>The motor is translated rigidly.</b> Its S2 beam nodes, its clamped emergence point and the
 *       anchor the Vilfan attachment law reads all move together, so the motor arrives in exactly the
 *       relaxed state it had, merely one lawn-tile further along.</li>
 *   <li><b>It introduces one artificial length scale</b> — the tiling period. That is stated as a limitation;
 *       the period (2.0 µm) is ~57× the 35.1 nm target-zone repeat and ~123× the ±6-site candidate window,
 *       and 2000/35.1 = 56.98 is not close to an integer, so it does not commensurate with the lattice.</li>
 * </ul>
 *
 * <p>{@code recP}: [0] lawn period (µm), [1] periods per jump, [2] margin behind the trailing end (µm),
 * [3] enabled (1/0), [4] recycle counter (accumulated, written back). {@code counts}: [N, _, M, nSeg].
 */
public final class LawnRecycleSystem {
    private LawnRecycleSystem() {}

    /**
     * Translate every eligible motor forward by an integer number of lawn periods.
     *
     * <p>Single-threaded by construction: it accumulates a shared counter and the work per motor is a
     * handful of adds, so there is nothing to gain from parallelism and a shared reduction to avoid.
     */
    public static void recycle(IntArray boundSeg, DoubleArray nodes, DoubleArray frame,
                               DoubleArray gradData, FloatArray filCoord, FloatArray filUVec,
                               FloatArray filSegLength, DoubleArray recP, IntArray counts) {
        int N = counts.get(0), M = counts.get(2), nSeg = counts.get(3);
        if (recP.get(3) == 0.0) return;
        double period = recP.get(0), nJump = recP.get(1), margin = recP.get(2);
        double disp = period * nJump;
        for (@Parallel int gid = 0; gid < 1; gid++) {
            // filament axis and trailing station, from segment 0 (the fixture is a single rigid rod)
            double ux = filUVec.get(0), uy = filUVec.get(nSeg), uz = filUVec.get(2 * nSeg);
            double cx = filCoord.get(0), cy = filCoord.get(nSeg), cz = filCoord.get(2 * nSeg);
            double half = 0.5 * filSegLength.get(0);
            double filCentreAx = cx * ux + cy * uy + cz * uz;
            double trailAx = filCentreAx - half;
            double moved = 0;
            for (int m = 0; m < N; m++) {
                if (boundSeg.get(m) >= 0) continue;                       // never move a bound head
                double ax = gradData.get(m), ay = gradData.get(N + m), az = gradData.get(2 * N + m);
                double motAx = ax * ux + ay * uy + az * uz;
                if (motAx >= trailAx - margin) continue;                  // still ahead of the cutoff
                double dx = disp * ux, dy = disp * uy, dz = disp * uz;
                // 1) the S2 beam nodes, including the pivot P at j = M
                for (int j = 0; j <= M; j++) {
                    nodes.set((3 * j) * N + m,       nodes.get((3 * j) * N + m) + dx);
                    nodes.set((3 * j + 1) * N + m,   nodes.get((3 * j + 1) * N + m) + dy);
                    nodes.set((3 * j + 2) * N + m,   nodes.get((3 * j + 2) * N + m) + dz);
                }
                // 2) the clamped emergence point carried in the per-motor frame (slots 9..11)
                frame.set(9 * N + m,  frame.get(9 * N + m) + dx);
                frame.set(10 * N + m, frame.get(10 * N + m) + dy);
                frame.set(11 * N + m, frame.get(11 * N + m) + dz);
                // 3) the substrate anchor the Vilfan attachment law reads
                gradData.set(m, ax + dx); gradData.set(N + m, ay + dy); gradData.set(2 * N + m, az + dz);
                moved++;
            }
            recP.set(4, recP.get(4) + moved);
        }
    }

    /**
     * Is one lawn tile long enough to cover the whole filament?
     *
     * <p>The jump is always <b>exactly one period</b>, because that is what makes the motor field a genuine
     * periodic tiling: in a field of period {@code P}, a motor leaving the rear at {@code x} is the same
     * motor the field already has at {@code x + P}. Landing "underneath" the filament is therefore correct,
     * not a defect — an unbound motor's beam sits at its relaxed pose, which is exactly the state a motor
     * that had always been there would be in.
     *
     * <p>Jumping <i>two</i> periods to avoid landing under the filament is what breaks the tiling: it opens
     * a motor-free gap of {@code 2P − contour} ahead of the leading end, which is precisely the engagement
     * dip observed in the first recycle probe (bound population 2.20 -> 0.48 -> 1.36 across 3 µm).
     *
     * <p>The real requirement is on the tile LENGTH: motors occupy
     * {@code [trail − margin, trail − margin + P]}, so covering the filament needs
     * {@code P >= contour + margin}. With 120 motors in a 2.0 µm tile and a 2.106 µm filament this fails by
     * 5.3 % — a 2.0 µm tile simply does not contain enough motors to fill a 2.106 µm window at that density.
     */
    public static boolean tileCoversFilament(double period, double contour, double margin) {
        return period >= contour + margin;
    }
}
