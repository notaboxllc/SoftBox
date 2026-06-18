package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
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

    // ---- Shared rigid-rod body (inc 4b-i): the entity-agnostic pose/drag/accumulator/
    //      Brownian layout. The public arrays below ALIAS this body's arrays (same objects),
    //      so the shared systems run over either store and the validated FDT/deflection/chain
    //      paths see identical arrays. FilamentStore adds only actin-specific state. ----
    public final RigidRodBody body;

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

    // ---- External load (deflection test only; planar 3N, init 0; seeds forceSum) ----
    public final FloatArray extForce;

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
    // counts (int):   [0]=n, [1]=stepCount, [2]=runSeed, [3]=loadOn (deflection tau release)
    public final IntArray   counts;
    // chainParams (float, inc 2a): [0]=dt [1]=fracMove [2]=fracR [3]=fracMoveTorq
    //   [4]=filTorqSpringActive(0/1) [5]=filTorqSpring [6]=actinMonoRadius. Allocated
    //   always (tiny); only referenced by the chain TaskGraph, so the FDT path is
    //   unaffected. NOT a topology reshape — the end*Nbr* arrays are unchanged.
    public final FloatArray chainParams;

    public static final int SENTINEL_NO_NBR = -1;

    // ====== RUNTIME-BIRTH LIFECYCLE (increment 6c Stage B1) ======
    // FilamentStore was fully STATIC through inc 6 (fixed n, every slot a live segment). Stage B1 adds a
    // per-slot lifecycle so a slot can be born at runtime (the node nucleation source, B2). This mirrors the
    // crosslinker linkState sentinel (inc 5b) and reuses the inc-5 scan-rank free-list allocator VERBATIM
    // (CrossBridgeSystem.csrScan), one level up: the allocated thing is a whole filament, not a crosslink.
    //
    // SENTINEL: filState[i] >= 0 ⇒ ACTIVE (a live segment), < 0 ⇒ FREE (an empty, allocatable slot). DEFAULT
    // is ALL-ACTIVE (init 0), so every existing harness — which fills [0,n) with live segments and never frees
    // a slot — sees filState all-ACTIVE and is byte-unchanged / bit-identical (the no-op-when-all-active
    // guarantee). A store only gains FREE slots if a harness explicitly calls markFree().
    //
    // THE ACTIVE-GUARD IS DATA-DRIVEN, NOT A PER-KERNEL BRANCH (planner decision — keeps every shared device
    // kernel byte-unchanged ⇒ prior harnesses byte-unchanged). A FREE slot is held INERT by its data, not by an
    // if-branch inside integrate/Brownian/derive/chain: a FREE slot carries brownTransScale=brownRotScale=0
    // (markFree) ⇒ zero Brownian drive, no chain neighbors (sentinel -1) ⇒ a free rod contributing zero chain
    // force AND not a neighbor of any active segment, and is parked inside the box ⇒ containment no-ops. With
    // forceSum=torqueSum=0 the integrator yields v=0 ⇒ the slot never moves; derive harmlessly recomputes its
    // frozen pose. So every shared system treats a FREE slot as a quiescent free rod contributing exactly zero,
    // with NO shared-kernel edit. (The ONE branch B2 will need — keeping a FREE slot out of the broad-phase so a
    // motor can't bind a not-yet-born filament — lives in the publish path; B1's synthetic harness parks FREE
    // slots off the candidate set by construction. Flagged in the findings.)
    public static final int FIL_ACTIVE = 0;    // >=0 ⇒ live segment
    public static final int FIL_FREE   = -1;   // <0  ⇒ empty/allocatable slot
    public final IntArray filState;             // size n; lifecycle source of truth

    // ---- scan-rank free-list allocator scratch (Design A, inc 5c-i: no compaction, slot-stable) ----
    //   freeFlags  : freeCount[s] = (filState[s] FREE) ? 1 : 0
    //   csrScan    : freeOffsets = exclusive prefix of freeCount; freeOffsets[C] = nFree  (REUSED VERBATIM)
    //   freeScatter: freeList[freeOffsets[s]] = s for each FREE s (index order)
    //   csrScan    : rankOffsets = exclusive prefix of acceptFlag; rankOffsets[K] = nAccepted  (REUSED)
    //   allocate   : request r (accepted) claims freeList[rank<nFree], writes pose, turns on Brownian, flips
    //                FREE→ACTIVE. Distinct ranks → distinct slots ⇒ one writer/slot, race-free, no atomics.
    public final int        reqCap;             // max birth requests per allocation phase
    public final IntArray   freeCount;          // n;   freeFlags writes 1 per FREE slot (csrScan input)
    public final IntArray   freeOffsets;        // n+1; exclusive prefix of freeCount; [C]=nFree
    public final IntArray   freeList;           // n;   FREE slot indices compacted in index order
    public final IntArray   freeScanCounts;     // csrScan counts for the free-list scan ([3]=C=capacity)
    public final IntArray   allocCounts;        // [0]=C(capacity) [1]=K(requests this phase)
    public final FloatArray reqCoord;           // 3*reqCap planar: birth-request center (µm)
    public final FloatArray reqUVec;            // 3*reqCap planar: birth-request long axis (unit)
    public final FloatArray reqYVec;            // 3*reqCap planar: birth-request ref-perp (unit)
    public final IntArray   acceptFlag;         // reqCap; per-request accept (1/0); consumed by the rank scan
    public final IntArray   rankOffsets;        // reqCap+1; exclusive prefix of acceptFlag; [K]=nAccepted
    public final IntArray   rankScanCounts;     // csrScan counts for the rank scan ([3]=K this phase)
    public final FloatArray birthParams;        // [0]=born brownTransScale [1]=born brownRotScale

    public FilamentStore(int n) { this(n, 1); }

    public FilamentStore(int n, int reqCap) {
        this.n = n;
        this.reqCap = Math.max(1, reqCap);
        body = new RigidRodBody(n);
        // alias the shared rigid-rod arrays (same objects — existing code/harnesses unchanged)
        coord = body.coord;  uVec = body.uVec;  yVec = body.yVec;
        zVec  = body.zVec;   end1 = body.end1;  end2 = body.end2;
        segLength  = body.segLength;
        bTransGam  = body.bTransGam;  bRotGam = body.bRotGam;
        bTransDiff = body.bTransDiff; bRotDiff = body.bRotDiff;
        forceSum   = body.forceSum;   torqueSum = body.torqueSum;
        randForce  = body.randForce;  randTorque = body.randTorque;
        brownTransScale = body.brownTransScale;
        brownRotScale   = body.brownRotScale;

        monomerCount = new IntArray(n);
        extForce   = new FloatArray(3 * n);

        end1NbrSlot = new IntArray(n);
        end1NbrSide = new IntArray(n);
        end2NbrSlot = new IntArray(n);
        end2NbrSide = new IntArray(n);

        params = new FloatArray(2);
        counts = new IntArray(4);
        counts.set(3, 1);   // loadOn (default; deflection harness toggles for tau release)
        chainParams = new FloatArray(7);

        // zero the accumulators explicitly (free rod: forceSum/torqueSum stay zero)
        extForce.init(0f);
        forceSum.init(0f);
        torqueSum.init(0f);
        randForce.init(0f);
        randTorque.init(0f);

        // free rods: no neighbors
        end1NbrSlot.init(SENTINEL_NO_NBR);
        end1NbrSide.init(SENTINEL_NO_NBR);
        end2NbrSlot.init(SENTINEL_NO_NBR);
        end2NbrSide.init(SENTINEL_NO_NBR);

        // lifecycle: DEFAULT all-ACTIVE ⇒ existing harnesses unaffected (no-op-when-all-active)
        filState = new IntArray(n);
        filState.init(FIL_ACTIVE);
        freeCount      = new IntArray(n);
        freeOffsets    = new IntArray(n + 1);
        freeList       = new IntArray(n);
        freeScanCounts = new IntArray(4);
        freeScanCounts.set(3, n);          // free-list scan bound = capacity C
        allocCounts    = new IntArray(4);
        allocCounts.set(0, n);             // C = capacity
        reqCoord = new FloatArray(3 * this.reqCap);
        reqUVec  = new FloatArray(3 * this.reqCap);
        reqYVec  = new FloatArray(3 * this.reqCap);
        acceptFlag     = new IntArray(this.reqCap);
        rankOffsets    = new IntArray(this.reqCap + 1);
        rankScanCounts = new IntArray(4);
        birthParams    = new FloatArray(2);
        reqCoord.init(0f); reqUVec.init(0f); reqYVec.init(0f);
        acceptFlag.init(0);
    }

    // ---- lifecycle helpers (host side; the device allocator lives in FilamentBirthSystem) ----

    /** Mark slot FREE and make it INERT: zero its Brownian scale so the integrator can never move it.
     *  (Pose/segLength/drag/neighbors are left as-is — a FREE slot is simply parked + force-free; an
     *  ACTIVE filament will overwrite the pose at birth.) */
    public void markFree(int slot) {
        filState.set(slot, FIL_FREE);
        brownTransScale.set(slot, 0f);
        brownRotScale.set(slot, 0f);
    }

    /** The Brownian scale a born filament receives (written by FilamentBirthSystem.allocate). */
    public void setBirthParams(double bornBrownTransScale, double bornBrownRotScale) {
        birthParams.set(0, (float) bornBrownTransScale);
        birthParams.set(1, (float) bornBrownRotScale);
    }

    /** Stage a birth request r (planar): a seed filament to be born at (cx,cy,cz) oriented (u,y). The seed
     *  length / monomerCount / drag are pre-initialised identically in every slot at scene build (the seed is
     *  fixed-length — growth deferred to a later increment), so allocate writes only pose + Brownian + state. */
    public void setBirthRequest(int r, float cx, float cy, float cz,
                                float ux, float uy, float uz, float yx, float yy, float yz) {
        reqCoord.set(r, cx); reqCoord.set(reqCap + r, cy); reqCoord.set(2 * reqCap + r, cz);
        reqUVec.set(r, ux);  reqUVec.set(reqCap + r, uy);  reqUVec.set(2 * reqCap + r, uz);
        reqYVec.set(r, yx);  reqYVec.set(reqCap + r, yy);  reqYVec.set(2 * reqCap + r, yz);
        acceptFlag.set(r, 1);
    }

    /** Set the number of birth requests K this allocation phase (the rank-scan bound + alloc K). */
    public void setBirthRequestCount(int K) {
        rankScanCounts.set(3, K);
        allocCounts.set(1, K);
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

    /**
     * Device step (increment 3): publish each segment into the entity-agnostic
     * SpatialBodyView as a bounding sphere. center = segment coord; boundingRadius =
     * half-length + actin radius (the sphere that bounds the capsule). ownerStore =
     * STORE_FILAMENT(0), ownerSlot = segment slot, so the future narrow-phase consumer
     * can resolve a candidate body back to its FilSegment.
     *
     * One thread per segment. The filament coord is planar with stride n; the body view
     * center is planar with stride cap (= center.getSize()/3). FilamentStore is the only
     * publisher this increment, writing body slots [0,n) (baseSlot 0). viewParams[0] =
     * actinRadius. Plain method over the SoA arrays — runs on the GPU TaskGraph and the
     * sequential -cpu runner identically (the device-agnostic invariant).
     */
    public static void publishToBodyView(
            FloatArray coord,
            FloatArray segLength,
            FloatArray center,
            FloatArray boundingRadius,
            IntArray   ownerStore,
            IntArray   ownerSlot,
            FloatArray viewParams,
            IntArray   counts) {
        int n   = coord.getSize() / 3;
        int cap = center.getSize() / 3;
        float actinRadius = viewParams.get(0);
        for (@Parallel int i = 0; i < n; i++) {
            center.set(i,           coord.get(i));         // X plane (src stride n -> dst stride cap)
            center.set(cap + i,     coord.get(n + i));     // Y plane
            center.set(2 * cap + i, coord.get(2 * n + i)); // Z plane
            boundingRadius.set(i, 0.5f * segLength.get(i) + actinRadius);
            ownerStore.set(i, SpatialBodyView.STORE_FILAMENT);
            ownerSlot.set(i, i);
        }
    }

    /**
     * Increment 6c B2 — the LIFECYCLE-AWARE publish (the one deferred shared-kernel touch from B1). Identical
     * to the 8-arg publishToBodyView, except a FREE slot (filState[i] < 0) is published with ownerStore =
     * STORE_NONE so the narrow-phase (which filters by ownerStore) never treats a not-yet-born filament as a
     * binding candidate. When EVERY slot is ACTIVE this writes STORE_FILAMENT for all i — **bit-identical to the
     * 8-arg version** (the no-op-when-all-active guarantee), so existing all-active scenes are unaffected
     * whichever overload they call. Only B2's live binding scene (with FREE slots) needs this one; the 8-arg
     * stays for every existing caller (byte-unchanged).
     */
    public static void publishToBodyView(
            FloatArray coord,
            FloatArray segLength,
            FloatArray center,
            FloatArray boundingRadius,
            IntArray   ownerStore,
            IntArray   ownerSlot,
            FloatArray viewParams,
            IntArray   counts,
            IntArray   filState) {
        int n   = coord.getSize() / 3;
        int cap = center.getSize() / 3;
        float actinRadius = viewParams.get(0);
        for (@Parallel int i = 0; i < n; i++) {
            center.set(i,           coord.get(i));
            center.set(cap + i,     coord.get(n + i));
            center.set(2 * cap + i, coord.get(2 * n + i));
            boundingRadius.set(i, 0.5f * segLength.get(i) + actinRadius);
            // FREE slot ⇒ STORE_NONE (excluded from the narrow-phase); ACTIVE ⇒ STORE_FILAMENT (as the 8-arg).
            ownerStore.set(i, filState.get(i) < 0 ? SpatialBodyView.STORE_NONE : SpatialBodyView.STORE_FILAMENT);
            ownerSlot.set(i, i);
        }
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

    /** v1 deflection defaults (FilSegment/Env): fracMove=0.5, fracR=0.1, fracMoveTorq=0.265,
     *  filTorqSpring inactive (damped F4 branch). Carries continuously into 2b. */
    public void setChainParams() {
        chainParams.set(0, (float) Constants.deltaT);
        chainParams.set(1, 0.5f);     // fracMove
        chainParams.set(2, 0.1f);     // fracR
        chainParams.set(3, 0.265f);   // fracMoveTorq
        chainParams.set(4, 0.0f);     // filTorqSpring inactive -> damped torsion branch
        chainParams.set(5, 1.0e-20f); // filTorqSpring (unused while inactive)
        chainParams.set(6, (float) Constants.actinMonoRadius);
    }
}
