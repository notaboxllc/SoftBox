package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * The SoA canonical store for filament segments — the source of truth.
 *
 * Layout decision (FLAGGED): TornadoVM's task() functional interfaces top out at
 * 15 arguments (TornadoFunctions$Task15). A strictly one-FloatArray-per-component
 * store would give the integration system ~27 array parameters — impossible to
 * launch. So each vector quantity is one device buffer in PLANAR SoA layout:
 *
 *     [ x0 x1 ... x(N-1) | y0 y1 ... y(N-1) | z0 z1 ... z(N-1) ]
 *
 * The x-plane is contiguous, then the y-plane, then the z-plane. This is genuine
 * SoA — the component planes are separate and coalesced, NOT interleaved AoS
 * ([x0 y0 z0 x1 y1 z1 ...]). The planeX/planeY/planeZ index helpers and the named
 * coordX()/uVecX()/... accessors keep each component a findable, named unit. The
 * planar packing is purely a device-arity accommodation; the architectural
 * invariant (no per-object fields, no AoS interleave, device-resident) holds.
 *
 * Chain topology (end1Nbr / end2Nbr fields) is laid down now but inert this increment —
 * sentinel -1 = no neighbor (free rod). Stored as integer (slot, side) from birth
 * to permanently avoid v1's Pt3D pointer-alias orientation-decode trap (migration
 * doc Aliasing audit A1). Increment 2 starts READING these without reshaping.
 */
public final class FilamentStore {

    public final int n;  // number of segments (entities are integer IDs 0..n-1)

    // ---- Pose (canonical source of truth), planar 3N ----
    public final FloatArray coord;   // segment centers (microns)
    public final FloatArray uVec;    // long axis (unit)
    public final FloatArray yVec;    // reference perpendicular (unit)

    // ---- Derived (recomputed each step by DerivedGeometrySystem; NOT source of truth) ----
    public final FloatArray zVec;    // = uVec x yVec
    public final FloatArray end1;    // = coord - (segLength/2) uVec
    public final FloatArray end2;    // = coord + (segLength/2) uVec

    // ---- Geometry ----
    public final IntArray   monomerCount;  // per segment
    public final FloatArray segLength;     // microns; = (monomerCount+1)*actinMonoRadius

    // ---- Drag / diffusion (body-frame diagonal), planar 3N ----
    public final FloatArray bTransGam;   // translational drag (SI: N s / m)
    public final FloatArray bRotGam;     // rotational drag    (SI: N m s / rad)
    public final FloatArray bTransDiff;  // = kT / bTransGam   (Einstein)
    public final FloatArray bRotDiff;    // = kT / bRotGam

    // ---- Per-step accumulators, planar 3N ----
    public final FloatArray forceSum;    // deterministic lab-frame force (zeroed each step)
    public final FloatArray torqueSum;   // deterministic lab-frame torque
    public final FloatArray randForce;   // Brownian, body-frame (filled by BrownianForceSystem)
    public final FloatArray randTorque;  // Brownian, body-frame

    // ---- Per-segment Brownian scale (BTransCoeff/BRotCoeff and future attenuations) ----
    public final FloatArray brownTransScale;  // size n
    public final FloatArray brownRotScale;    // size n

    // ---- Chain topology (inert this increment; sentinel -1 = no neighbor) ----
    public final IntArray   end1NbrSlot;  // size n
    public final IntArray   end1NbrSide;  // size n  (0 -> neighbor.end1, 1 -> neighbor.end2)
    public final IntArray   end2NbrSlot;  // size n
    public final IntArray   end2NbrSide;  // size n

    // ---- Kernel scalar params ----
    // params (float): [0]=dt, [1]=brownianForceMag (= sqrt(2kT/dt))
    public final FloatArray params;
    // counts (int):   [0]=n, [1]=stepCount, [2]=runSeed
    public final IntArray   counts;

    public static final int SENTINEL_NO_NBR = -1;

    public FilamentStore(int n) {
        this.n = n;
        coord = new FloatArray(3 * n);
        uVec  = new FloatArray(3 * n);
        yVec  = new FloatArray(3 * n);
        zVec  = new FloatArray(3 * n);
        end1  = new FloatArray(3 * n);
        end2  = new FloatArray(3 * n);

        monomerCount = new IntArray(n);
        segLength    = new FloatArray(n);

        bTransGam  = new FloatArray(3 * n);
        bRotGam    = new FloatArray(3 * n);
        bTransDiff = new FloatArray(3 * n);
        bRotDiff   = new FloatArray(3 * n);

        forceSum   = new FloatArray(3 * n);
        torqueSum  = new FloatArray(3 * n);
        randForce  = new FloatArray(3 * n);
        randTorque = new FloatArray(3 * n);

        brownTransScale = new FloatArray(n);
        brownRotScale   = new FloatArray(n);

        end1NbrSlot = new IntArray(n);
        end1NbrSide = new IntArray(n);
        end2NbrSlot = new IntArray(n);
        end2NbrSide = new IntArray(n);

        params = new FloatArray(2);
        counts = new IntArray(3);

        // zero the accumulators explicitly (free rod: forceSum/torqueSum stay zero)
        forceSum.init(0f);
        torqueSum.init(0f);
        randForce.init(0f);
        randTorque.init(0f);

        // free rods: no neighbors
        end1NbrSlot.init(SENTINEL_NO_NBR);
        end1NbrSide.init(SENTINEL_NO_NBR);
        end2NbrSlot.init(SENTINEL_NO_NBR);
        end2NbrSide.init(SENTINEL_NO_NBR);
    }

    /** True for a free rod: end has no chain neighbor. (Inert use this increment.) */
    public boolean filAtEnd1(int i) { return end1NbrSlot.get(i) == SENTINEL_NO_NBR; }
    public boolean filAtEnd2(int i) { return end2NbrSlot.get(i) == SENTINEL_NO_NBR; }

    // ---- planar SoA index helpers (X-plane | Y-plane | Z-plane) ----
    public int planeX(int i) { return i; }
    public int planeY(int i) { return n + i; }
    public int planeZ(int i) { return 2 * n + i; }

    // ---- named component accessors (host side) ----
    public float coordX(int i) { return coord.get(planeX(i)); }
    public float coordY(int i) { return coord.get(planeY(i)); }
    public float coordZ(int i) { return coord.get(planeZ(i)); }
    public float uVecX(int i)  { return uVec.get(planeX(i)); }
    public float uVecY(int i)  { return uVec.get(planeY(i)); }
    public float uVecZ(int i)  { return uVec.get(planeZ(i)); }

    public void setCoord(int i, float x, float y, float z) {
        coord.set(planeX(i), x); coord.set(planeY(i), y); coord.set(planeZ(i), z);
    }
    public void setUVec(int i, float x, float y, float z) {
        uVec.set(planeX(i), x); uVec.set(planeY(i), y); uVec.set(planeZ(i), z);
    }
    public void setYVec(int i, float x, float y, float z) {
        yVec.set(planeX(i), x); yVec.set(planeY(i), y); yVec.set(planeZ(i), z);
    }

    public void setParams(double dt, double brownianForceMag) {
        params.set(0, (float) dt);
        params.set(1, (float) brownianForceMag);
    }
    public void setCounts(int stepCount, int runSeed) {
        counts.set(0, n);
        counts.set(1, stepCount);
        counts.set(2, runSeed);
    }
}
