package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * The shared rigid-rod body layout (increment 4b-i) — the second-instance abstraction.
 *
 * A rigid-rod Langevin body is the common substrate of BOTH entity types: a filament
 * segment (FilamentStore) and a myosin sub-body — rod / lever / head (MotorStore). Each
 * is an overdamped rigid rod with a pose, a body-frame diagonal drag/diffusion tensor,
 * per-step force/torque accumulators, and a Brownian draw. This class factors that layout
 * (planar SoA, plane stride = n) so the SHARED device systems — RigidRodLangevinIntegration
 * System, BrownianForceSystem, DerivedGeometrySystem — run over EITHER store's arrays
 * unchanged (the one-implementation invariant: same method, different arrays).
 *
 * This holds ONLY the entity-agnostic rigid-rod arrays. Entity-specific state stays on the
 * owning store: filament chain topology / extForce (FilamentStore), motor joint topology /
 * bed anchor / bound-state (MotorStore). Per the sharpened abstraction invariant, the drag
 * VALUES are entity-specific physics (actin-rod vs myo-rod vs sphere) computed at init into
 * these arrays by per-entity drag setup; the shared systems only ever READ them.
 *
 * FilamentStore embeds one (n = #segments) and aliases its existing public arrays to these,
 * so the validated FDT/deflection/chain paths see the identical array objects. MotorStore
 * embeds one (n = 3·#motors: sub-body 3m=rod, 3m+1=lever, 3m+2=head).
 */
public final class RigidRodBody {

    public final int n;  // number of rigid-rod sub-bodies

    // ---- Pose (canonical), planar 3n ----
    public final FloatArray coord;   // body centers (microns)
    public final FloatArray uVec;    // long axis (unit)
    public final FloatArray yVec;    // reference perpendicular (unit)

    // ---- Derived (recomputed each step by DerivedGeometrySystem) ----
    public final FloatArray zVec;    // = uVec x yVec
    public final FloatArray end1;    // = coord - (segLength/2) uVec
    public final FloatArray end2;    // = coord + (segLength/2) uVec

    // ---- Geometry ----
    public final FloatArray segLength;   // microns

    // ---- Drag / diffusion (body-frame diagonal), planar 3n ----
    public final FloatArray bTransGam;
    public final FloatArray bRotGam;
    public final FloatArray bTransDiff;
    public final FloatArray bRotDiff;

    // ---- Per-step accumulators, planar 3n ----
    public final FloatArray forceSum;
    public final FloatArray torqueSum;
    public final FloatArray randForce;
    public final FloatArray randTorque;

    // ---- Per-body Brownian scale ----
    public final FloatArray brownTransScale;  // size n
    public final FloatArray brownRotScale;    // size n

    public RigidRodBody(int n) {
        this.n = n;
        coord = new FloatArray(3 * n);
        uVec  = new FloatArray(3 * n);
        yVec  = new FloatArray(3 * n);
        zVec  = new FloatArray(3 * n);
        end1  = new FloatArray(3 * n);
        end2  = new FloatArray(3 * n);
        segLength  = new FloatArray(n);
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

        coord.init(0f); uVec.init(0f); yVec.init(0f);
        zVec.init(0f); end1.init(0f); end2.init(0f); segLength.init(0f);
        bTransGam.init(0f); bRotGam.init(0f); bTransDiff.init(0f); bRotDiff.init(0f);
        forceSum.init(0f); torqueSum.init(0f); randForce.init(0f); randTorque.init(0f);
        brownTransScale.init(0f); brownRotScale.init(0f);
    }

    // ---- planar SoA index helpers (X-plane | Y-plane | Z-plane, stride n) ----
    public int planeX(int i) { return i; }
    public int planeY(int i) { return n + i; }
    public int planeZ(int i) { return 2 * n + i; }

    public void setCoord(int i, float x, float y, float z) {
        coord.set(i, x); coord.set(n + i, y); coord.set(2 * n + i, z);
    }
    public void setUVec(int i, float x, float y, float z) {
        uVec.set(i, x); uVec.set(n + i, y); uVec.set(2 * n + i, z);
    }
    public void setYVec(int i, float x, float y, float z) {
        yVec.set(i, x); yVec.set(n + i, y); yVec.set(2 * n + i, z);
    }
    public float coordX(int i) { return coord.get(i); }
    public float coordY(int i) { return coord.get(n + i); }
    public float coordZ(int i) { return coord.get(2 * n + i); }
    public float uVecX(int i)  { return uVec.get(i); }
    public float uVecY(int i)  { return uVec.get(n + i); }
    public float uVecZ(int i)  { return uVec.get(2 * n + i); }
    public float end1X(int i)  { return end1.get(i); }
    public float end1Y(int i)  { return end1.get(n + i); }
    public float end1Z(int i)  { return end1.get(2 * n + i); }
    public float end2X(int i)  { return end2.get(i); }
    public float end2Y(int i)  { return end2.get(n + i); }
    public float end2Z(int i)  { return end2.get(2 * n + i); }
}
