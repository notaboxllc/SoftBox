package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * The entity-agnostic spatial body view (increment 3).
 *
 * Represents ANY collidable body as a bounding SPHERE — center (planar SoA) +
 * boundingRadius — plus a back-pointer (ownerStore, ownerSlot) to the store/slot
 * that published it. This is the canonical input the spatial grid + broad-phase
 * consume; they know nothing about FilSegment / FilamentStore. Nodes, membrane,
 * and motors will register into this same view in later increments — it is the
 * extensible seam. FilamentStore is the ONLY publisher this increment
 * (FilamentStore.publishToBodyView).
 *
 * Layout: center is planar SoA with plane stride = capacity (the X-plane is
 * [0,cap), Y-plane [cap,2cap), Z-plane [2cap,3cap)), matching the FilamentStore
 * convention. Kernels recover cap as center.getSize()/3 and the active body count
 * S from counts[1]; bodies [0,S) are live, [S,cap) are padding.
 *
 * ownerStore ids (room to grow): 0 = FilamentStore, 1 = MotorStore (inc 4a).
 * Reserved for nodes/membrane. The grid/broad-phase never read ownerStore/ownerSlot —
 * those are for the narrow-phase consumer (BindingDetectionSystem, inc 4a) to resolve
 * a candidate body back to its physical object and to FILTER candidate pairs by store
 * (motor↔segment). Two publishers now register into this same view (FilamentStore +
 * MotorStore); the grid/broad-phase code is entity-agnostic and unchanged.
 */
public final class SpatialBodyView {

    public static final int STORE_FILAMENT    = 0;
    public static final int STORE_MOTOR       = 1;
    public static final int STORE_CROSSLINKER = 2;   // inc 5 (recon §2); broad-phase publisher arrives with formation (5c)

    public final int capacity;
    public int count;                       // active bodies [0,count)

    public final FloatArray center;         // planar SoA, 3*capacity (centerX|centerY|centerZ)
    public final FloatArray boundingRadius; // capacity
    public final IntArray   ownerStore;     // capacity
    public final IntArray   ownerSlot;      // capacity

    public SpatialBodyView(int capacity) {
        this.capacity = capacity;
        this.count = 0;
        center        = new FloatArray(3 * capacity);
        boundingRadius = new FloatArray(capacity);
        ownerStore    = new IntArray(capacity);
        ownerSlot     = new IntArray(capacity);
        center.init(0f);
        boundingRadius.init(0f);
        ownerStore.init(-1);
        ownerSlot.init(-1);
    }

    // planar index helpers (plane stride = capacity)
    public int planeX(int i) { return i; }
    public int planeY(int i) { return capacity + i; }
    public int planeZ(int i) { return 2 * capacity + i; }

    public float centerX(int i) { return center.get(planeX(i)); }
    public float centerY(int i) { return center.get(planeY(i)); }
    public float centerZ(int i) { return center.get(planeZ(i)); }
}
