package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 6 (contractile assay): the hard position-snap end-pin — a faithful port of v1's
 * BoxOfActin.applyBenchmarkPins (BoA-v1ref:2236-2263). After integration each step, a pinned
 * filament segment's plus endpoint (end2) is snapped back to its fixed lab-frame anchor by a
 * pure TRANSLATION of the whole segment (v1 incCoord(anchor − endpoint) + reinitialize). It is
 * NOT a spring: it is an ideal isometric clamp on a position, so the segment may still rotate
 * about the pinned endpoint and the reaction the clamp supplies (= the tension transmitted down
 * the chain) is read from the segment's forceSum BEFORE this snap, exactly as v1 does.
 *
 * Device-agnostic: one thread per pin, reads the post-derive end2, writes coord/end1/end2 by the
 * same delta (a translation leaves uVec/yVec/zVec and end1−end2 separation unchanged, so no
 * re-derive is needed — the shifted end1/end2 already equal coord ± ½·len·uVec). No atomics, no
 * KernelContext; runs identically on the GPU TaskGraph (as the LAST task of the step) and the
 * sequential -cpu runner. Snaps end2 (the plus/barbed tip, by the contractile-assay convention
 * uVec→plus, so end2 = coord + ½·len·uVec is always the plus end).
 *
 * pinPt is interleaved [x0,y0,z0, x1,y1,z1, ...] (nPin is tiny). pinCounts[0] = nPin.
 */
public final class PinSystem {
    private PinSystem() {}

    public static void snap(FloatArray coord, FloatArray end1, FloatArray end2,
                            IntArray pinSeg, FloatArray pinPt, IntArray pinCounts) {
        int N = coord.getSize() / 3;
        int nPin = pinCounts.get(0);
        for (@Parallel int p = 0; p < nPin; p++) {
            int s = pinSeg.get(p);
            int sx = s, sy = N + s, sz = 2 * N + s;
            double dx = (double) pinPt.get(3 * p)     - (double) end2.get(sx);
            double dy = (double) pinPt.get(3 * p + 1) - (double) end2.get(sy);
            double dz = (double) pinPt.get(3 * p + 2) - (double) end2.get(sz);
            coord.set(sx, (float) (coord.get(sx) + dx));
            coord.set(sy, (float) (coord.get(sy) + dy));
            coord.set(sz, (float) (coord.get(sz) + dz));
            end1.set(sx, (float) (end1.get(sx) + dx));
            end1.set(sy, (float) (end1.get(sy) + dy));
            end1.set(sz, (float) (end1.get(sz) + dz));
            end2.set(sx, pinPt.get(3 * p));
            end2.set(sy, pinPt.get(3 * p + 1));
            end2.set(sz, pinPt.get(3 * p + 2));
        }
    }
}
