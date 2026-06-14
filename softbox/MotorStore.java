package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * The SoA canonical store for myosin motors (increment 4a) — the source of truth,
 * device-resident. Motors are the SECOND entity type and the second publisher into
 * the entity-agnostic SpatialBodyView; the spatial grid / broad-phase need ZERO
 * changes (they read only the view's bounding spheres). All motor↔segment type logic
 * lives in the consumer (BindingDetectionSystem), which filters broad-phase candidate
 * pairs by ownerStore.
 *
 * Layout mirrors FilamentStore: each vector quantity is one device buffer in PLANAR
 * SoA (x-plane | y-plane | z-plane, stride = nMotors). Ported from v1 MyoMotor /
 * MyosinFixed (anchor = the fixed rod tail; head = bindTip = the motor's free end that
 * drops onto a filament segment).
 *
 * THIS INCREMENT (4a): binding detection + bind/unbind kinetics only. Bound motors
 * apply NO force — there is no power stroke, no surface confinement, no gliding
 * velocity (all 4b). So anchor/head/uVec/rodUVec are static over a run; only the
 * bound-state evolves (deterministic bind when a segment is in reach, stochastic
 * catch-slip release — which at zero force reduces exactly to k_off = kOff = 100/s).
 *
 * Bound-state encoding (single int, race-free per-motor ownership):
 *   boundSeg[m] >= 0  -> bound to that filament segment slot
 *   boundSeg[m] == -1 -> free and bindable
 *   boundSeg[m] == -2 -> free, in the one-step rebind refractory (v1 myoRebindTime;
 *                        1e-5 s < dt=1e-4 s rounds to a single-step cooldown)
 */
public final class MotorStore {

    public final int nMotors;  // entities are integer IDs 0..nMotors-1

    // ================= Articulated body (increment 4b-i) =================
    // v1's 3-body myosin: rod (tail, anchored) → lever (neck) → head, each a shared
    // rigid-rod Langevin body. Sub-body flat index: 3m=rod, 3m+1=lever, 3m+2=head. The
    // shared integration / Brownian / derived systems run over body.* unchanged. The
    // 4a binding interface (head/uVec/rodUVec below) is a PUBLISHED projection of this
    // body (publishHeadFromBody) — the head sub-body is the source of truth.
    public final RigidRodBody body;   // 3*nMotors sub-bodies

    // v1 myosin geometry (Env.java:776-778; MyoRod/MyoLever/MyoMotor radius fields).
    public static final double ROD_LEN = 0.080, LEVER_LEN = 0.008, HEAD_LEN = 0.020;   // µm
    public static final double ROD_R   = 0.003, LEVER_R   = 0.002, HEAD_R   = 0.010;   // µm

    // bodyParams (float): [0]=dt [1]=brownianForceMag (= sqrt(2kT/dt)), for the shared
    //   Brownian + integration systems over the articulated sub-bodies.
    public final FloatArray bodyParams;
    // jointParams (float): [0]=dt; J1(lever-motor) [1]=fracMove [2]=fracR [3]=fracMoveTorq
    //   [4]=restAngleDeg; J2(rod-lever) [5]=fracMove [6]=fracR [7]=fracMoveTorq [8]=restAngleDeg;
    //   [9]=anchorFracMove [10]=stallForcePN. Motor-SPECIFIC physics (localized, not shared).
    public final FloatArray jointParams;

    public int rodIdx(int m)   { return 3 * m; }
    public int leverIdx(int m) { return 3 * m + 1; }
    public int headIdx(int m)  { return 3 * m + 2; }

    // ================= 4a binding interface (a projection of the body) =================
    // ---- Pose, planar 3*nMotors ----
    public final FloatArray head;     // motor head tip = v1 bindTip (body-view center)
    public final FloatArray uVec;     // motor head axis (for the align-with-filament gate)
    public final FloatArray rodUVec;  // motor rod axis  (for the rod-not-pointing-away gate)
    public final FloatArray anchor;   // fixed anchor (rod tail); for the viewer link only

    // ---- Per-motor reach (bind distance = v1 myoColTol; also the body bounding radius) ----
    public final FloatArray reach;    // nMotors

    // ---- Bound-state (source of truth) ----
    public final IntArray   boundSeg; // nMotors; see encoding above
    public final FloatArray bindArc;  // nMotors; arc position (µm) of the bind site on the segment

    // ---- Per-motor cumulative stats (race-free; host sums for the off-rate gate) ----
    //   stats[2m]   = # steps this motor began the step bound
    //   stats[2m+1] = # release events fired
    public final IntArray   stats;    // 2*nMotors

    // ---- Kernel scalar params ----
    // kinParams (float): [0]=kOff [1]=alphaCatch [2]=alphaSlip [3]=xCatch [4]=xSlip
    //   [5]=kT [6]=dt [7]=myoColTol(reach) [8]=alignTol [9]=forceDotFil(=0 this increment)
    public final FloatArray kinParams;
    // counts (int): [0]=nMotors [1]=stepCount [2]=runSeed [3]=nSeg
    public final IntArray   counts;
    // publishParams (int): [0]=baseSlot (the motor block's first body-view slot)
    public final IntArray   publishParams;

    public static final int FREE_BINDABLE = -1;
    public static final int FREE_COOLDOWN = -2;

    public MotorStore(int nMotors) {
        this.nMotors = nMotors;
        body = new RigidRodBody(3 * nMotors);
        bodyParams  = new FloatArray(2);
        jointParams = new FloatArray(11);
        head    = new FloatArray(3 * nMotors);
        uVec    = new FloatArray(3 * nMotors);
        rodUVec = new FloatArray(3 * nMotors);
        anchor  = new FloatArray(3 * nMotors);
        reach   = new FloatArray(nMotors);
        boundSeg = new IntArray(nMotors);
        bindArc  = new FloatArray(nMotors);
        stats    = new IntArray(2 * nMotors);
        kinParams = new FloatArray(10);
        counts    = new IntArray(4);
        publishParams = new IntArray(1);

        head.init(0f); uVec.init(0f); rodUVec.init(0f); anchor.init(0f); reach.init(0f);
        boundSeg.init(FREE_BINDABLE);
        bindArc.init(0f);
        stats.init(0);
    }

    // ---- planar SoA index helpers (X-plane | Y-plane | Z-plane, stride nMotors) ----
    public int planeX(int m) { return m; }
    public int planeY(int m) { return nMotors + m; }
    public int planeZ(int m) { return 2 * nMotors + m; }

    public void setHead(int m, float x, float y, float z) {
        head.set(planeX(m), x); head.set(planeY(m), y); head.set(planeZ(m), z);
    }
    public void setAnchor(int m, float x, float y, float z) {
        anchor.set(planeX(m), x); anchor.set(planeY(m), y); anchor.set(planeZ(m), z);
    }
    public void setUVec(int m, float x, float y, float z) {
        uVec.set(planeX(m), x); uVec.set(planeY(m), y); uVec.set(planeZ(m), z);
    }
    public void setRodUVec(int m, float x, float y, float z) {
        rodUVec.set(planeX(m), x); rodUVec.set(planeY(m), y); rodUVec.set(planeZ(m), z);
    }
    public float headX(int m) { return head.get(planeX(m)); }
    public float headY(int m) { return head.get(planeY(m)); }
    public float headZ(int m) { return head.get(planeZ(m)); }
    public float anchorX(int m) { return anchor.get(planeX(m)); }
    public float anchorY(int m) { return anchor.get(planeY(m)); }
    public float anchorZ(int m) { return anchor.get(planeZ(m)); }

    /** v1 kinetics constants (Env.java; Guo–Guilford 2006 catch-slip release). The catch/slip
     *  weights + x-distances are carried for forward-compat with 4b's force coupling; at zero
     *  force (this increment) the release probability reduces EXACTLY to kOff·dt. */
    public void setKinParams(double myoColTol, double alignTol, double dt) {
        kinParams.set(0, 100.0f);     // kOff        (Env.java:822)  s^-1
        kinParams.set(1, 0.92f);      // alphaCatch  (Env.java:806)
        kinParams.set(2, 0.08f);      // alphaSlip   (Env.java:810)
        kinParams.set(3, 2.5e-9f);    // xCatch      (Env.java:814)  m
        kinParams.set(4, 0.4e-9f);    // xSlip       (Env.java:818)  m
        kinParams.set(5, (float) Constants.kT);
        kinParams.set(6, (float) dt);
        kinParams.set(7, (float) myoColTol);  // bind reach = v1 myoColTol (Env.java:755)
        kinParams.set(8, (float) alignTol);   // myoMotorAlignWithFilTolerance (Env.java:149)
        kinParams.set(9, 0.0f);       // forceDotFil — zero this increment (no power stroke)
    }
    public void setCounts(int stepCount, int runSeed, int nSeg) {
        counts.set(0, nMotors);
        counts.set(1, stepCount);
        counts.set(2, runSeed);
        counts.set(3, nSeg);
    }
    public void setBaseSlot(int baseSlot) { publishParams.set(0, baseSlot); }

    // ================= Articulated-body setup (increment 4b-i) =================
    public void setBodyParams(double dt) {
        bodyParams.set(0, (float) dt);
        bodyParams.set(1, (float) Math.sqrt(2.0 * Constants.kT / dt));
    }

    /** Joint constants from v1: J1/J2 PAIRS coeffs (Env.java:145-159), rest angles
     *  (Myosin.java:16 uncockedLever_MotorAngle=0; :300 rod-lever=96), stall cap (6 pN).
     *  FIXED uncocked state this increment (state switching is 4b-iii). J2 fracMoveTorq=0
     *  ⇒ the rod-lever angular spring is OFF (free hinge), connection-spring only. */
    public void setJointParams(double dt) {
        jointParams.set(0, (float) dt);
        jointParams.set(1, 0.4f); jointParams.set(2, 0.4f); jointParams.set(3, 0.4f); jointParams.set(4, 0.0f);   // J1 lever-motor
        jointParams.set(5, 0.4f); jointParams.set(6, 0.4f); jointParams.set(7, 0.0f); jointParams.set(8, 96.0f);  // J2 rod-lever
        jointParams.set(9, 0.4f);    // anchor spring coeff (= myoJ2FracMove)
        jointParams.set(10, 6.0f);   // myosinStallForce (pN) — J1 torque cap
    }

    /**
     * Assemble motor m standing on the bed: rod tail (rod.end1) at the anchor point, all
     * three sub-bodies collinear along the unit dir (the uncocked rest shape, J1 rest 0°).
     * Per-sub-body Brownian scales: rod + head ON (v1 attn 1.0), lever OFF (v1
     * MyoLever.moveThing Brownian commented out). Also stores the bed anchor (4a `anchor`).
     */
    public void assembleArticulated(int m, float ax, float ay, float az,
                                    float dx, float dy, float dz, float brownScale) {
        float px = -dy, py = dx, pz = 0f;             // any unit vector ⟂ dir
        float pm = (float) Math.sqrt(px * px + py * py + pz * pz);
        if (pm < 1.0e-4f) { px = 1f; py = 0f; pz = 0f; pm = 1f; }
        px /= pm; py /= pm; pz /= pm;

        int rod = rodIdx(m), lever = leverIdx(m), head = headIdx(m);
        float rOff = 0.5f * (float) ROD_LEN;
        float lOff = (float) ROD_LEN + 0.5f * (float) LEVER_LEN;
        float hOff = (float) (ROD_LEN + LEVER_LEN) + 0.5f * (float) HEAD_LEN;
        body.setCoord(rod,   ax + rOff * dx, ay + rOff * dy, az + rOff * dz);
        body.setCoord(lever, ax + lOff * dx, ay + lOff * dy, az + lOff * dz);
        body.setCoord(head,  ax + hOff * dx, ay + hOff * dy, az + hOff * dz);
        body.setUVec(rod, dx, dy, dz); body.setUVec(lever, dx, dy, dz); body.setUVec(head, dx, dy, dz);
        body.setYVec(rod, px, py, pz); body.setYVec(lever, px, py, pz); body.setYVec(head, px, py, pz);

        setAnchor(m, ax, ay, az);
        body.brownTransScale.set(rod, brownScale);   body.brownRotScale.set(rod, brownScale);
        body.brownTransScale.set(lever, 0f);         body.brownRotScale.set(lever, 0f);
        body.brownTransScale.set(head, brownScale);  body.brownRotScale.set(head, brownScale);
    }

    /**
     * The "repoint": publish the 4a binding interface (head bindTip, head axis, rod axis)
     * from the articulated sub-bodies — the head sub-body is the source of truth. Inert this
     * increment (no filament), wired up for 4b-ii. head bindTip = head.coord + ½·HEAD_LEN·head.uVec.
     */
    public static void publishHeadFromBody(
            FloatArray bodyCoord, FloatArray bodyUVec, FloatArray bodySegLength,
            FloatArray head, FloatArray uVec, FloatArray rodUVec, IntArray counts) {
        int nM = head.getSize() / 3;
        int nB = bodyCoord.getSize() / 3;            // = 3*nM
        for (@Parallel int m = 0; m < nM; m++) {
            int h = 3 * m + 2, r = 3 * m;
            float hl = bodySegLength.get(h);
            head.set(m,          bodyCoord.get(h)          + 0.5f * hl * bodyUVec.get(h));
            head.set(nM + m,     bodyCoord.get(nB + h)     + 0.5f * hl * bodyUVec.get(nB + h));
            head.set(2 * nM + m, bodyCoord.get(2 * nB + h) + 0.5f * hl * bodyUVec.get(2 * nB + h));
            uVec.set(m, bodyUVec.get(h));   uVec.set(nM + m, bodyUVec.get(nB + h));   uVec.set(2 * nM + m, bodyUVec.get(2 * nB + h));
            rodUVec.set(m, bodyUVec.get(r)); rodUVec.set(nM + m, bodyUVec.get(nB + r)); rodUVec.set(2 * nM + m, bodyUVec.get(2 * nB + r));
        }
    }

    /**
     * Device step: publish each motor into the entity-agnostic SpatialBodyView as a
     * bounding sphere — the SECOND publisher (FilamentStore is the first). center = motor
     * head (bindTip); boundingRadius = reach (= myoColTol), so a motor within reach of a
     * segment's capsule lands within the segment body sphere + reach ⇒ the broad-phase
     * (unchanged) is a conservative superset and the consumer filters to the exact set.
     * Motors occupy body slots [baseSlot, baseSlot+nMotors); ownerStore = STORE_MOTOR,
     * ownerSlot = motor slot. Plain method over the SoA arrays — GPU TaskGraph and the
     * -cpu runner identically.
     */
    public static void publishToBodyView(
            FloatArray head,
            FloatArray reach,
            FloatArray center,
            FloatArray boundingRadius,
            IntArray   ownerStore,
            IntArray   ownerSlot,
            IntArray   publishParams,
            IntArray   counts) {
        int nM  = head.getSize() / 3;
        int cap = center.getSize() / 3;
        int base = publishParams.get(0);
        for (@Parallel int m = 0; m < nM; m++) {
            int b = base + m;
            center.set(b,           head.get(m));          // X plane (src stride nM -> dst stride cap)
            center.set(cap + b,     head.get(nM + m));     // Y plane
            center.set(2 * cap + b, head.get(2 * nM + m)); // Z plane
            boundingRadius.set(b, reach.get(m));
            ownerStore.set(b, SpatialBodyView.STORE_MOTOR);
            ownerSlot.set(b, m);
        }
    }
}
