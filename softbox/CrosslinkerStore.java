package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Increment 5a: the crosslinker SoA store — a passive double-ended link between two
 * filament segments. Faithful to v1 FilLink (BoA-v1ref/boxOfActin/FilLink.java).
 *
 * Each crosslinker k connects filament slot {@code linkFilA[k]} at arc {@code loc1[k]}
 * (microns from end1) to filament slot {@code linkFilB[k]} at arc {@code loc2[k]}. The
 * coupling is keyed by INTEGER FILAMENT SLOTS, never class identity (architecture
 * invariant) — exactly the v1 design (fil1/fil2 are FilSegment references; here they are
 * integer slots into the one FilamentStore).
 *
 * 5a scope: links are PRE-PLACED and STATIC for the whole run. No formation / Bell
 * unbinding / torsion / Arp2/3 (5b/5c/later). So no biochemical state is stored — only
 * the static topology + attachment arcs + the per-step reaction scratch.
 *
 * The reaction is computed ONCE per crosslinker and the two side-reactions are
 * SELF-WRITTEN into this store's own xlinkData slots (race-free, exactly like the motor
 * bondData self-write). The two-pass CSR-inverse gather (keyed by linkFilA, then linkFilB)
 * then sums each filament's reactions into its forceSum/torqueSum — see CrosslinkerSystem.
 *
 * xlinkData stride 12 (planar within a link is unnecessary — one row per link):
 *   [0..2]   = A-side force   (lands on linkFilA)
 *   [3..5]   = A-side torque  (about filA COM, SI N·m)
 *   [6..8]   = B-side force   (lands on linkFilB; = −A-side force, Newton's 3rd law)
 *   [9..11]  = B-side torque  (about filB COM)
 */
public final class CrosslinkerStore {

    public static final int STRIDE = 12;

    public final int nLinks;

    // ---- static topology + attachment (integer slots + arc positions) ----
    public final IntArray   linkFilA;   // filament slot of side A
    public final IntArray   linkFilB;   // filament slot of side B
    public final FloatArray loc1;       // arc along filA from its end1 (microns)
    public final FloatArray loc2;       // arc along filB from its end1 (microns)

    // ---- per-filament total link count (v1 getLinkCt): fracMove = 0.4/max(ctA,ctB,1) ----
    // Static this increment: filled once at buildScene = number of links touching the
    // filament (as either side). Sized to the filament count, not the link count.
    public final IntArray   filLinkCt;

    // ---- per-link reaction scratch (self-written by CrosslinkerSystem.linkForces) ----
    public final FloatArray xlinkData;  // capacity * STRIDE

    // ---- LIFECYCLE: one authoritative sentinel-encoded per-slot field (5b death half; 5c birth).
    //      Mirrors motor boundSeg: >=0 = ACTIVE, <0 = sentinel (FREE/DEAD). This single field is the
    //      source of lifecycle truth (not scattered booleans). 5b sets ACTIVE→FREE on a Bell break
    //      (self-write, no compaction). 5c (formation) will allocate FREE→ACTIVE on the SAME field;
    //      it may subdivide the negative space (e.g. a distinct -2) like boundSeg's FREE_COOLDOWN
    //      without disturbing the >=0=ACTIVE contract. ----
    public static final int LINK_ACTIVE = 0;    // >=0 ⇒ active (5b uses the constant 0 marker)
    public static final int LINK_FREE   = -1;   // <0  ⇒ free/dead (inert; allocatable in 5c)
    public final IntArray   linkState;          // capacity

    // ---- Bell strain track: v1 ValueTracker(filLinkStrainToAve=10) — a 10-slot BOXCAR (sliding-
    //      window) circular buffer, NOT an exponential EWMA. averageVal = sum(all 10)/10 always
    //      (initial zeros included until filled). Mirrors the proven forceDotHist/forceDotPlace ring. ----
    public static final int STRAIN_WIN = 10;    // v1 Env.filLinkStrainToAve
    public final FloatArray strainHist;         // capacity * STRAIN_WIN, init 0 (v1 vals[] init 0)
    public final IntArray   strainPlace;        // capacity circular pointer, init 0

    // ---- kernel scalar params: [0]=restLength(µm) [1]=fracMoveBase(0.4) [2]=dt ----
    public final FloatArray xlParams;

    // ---- Bell off-rate params (5b): [0]=linkOffConst [1]=linkOffCoeff [2]=linkOffExp [3]=dt
    //      [4]=restLength(µm).  k_off = const + coeff·exp(aveStrain·exp);  P_break = k_off·dt. ----
    public final FloatArray offParams;

    // ---- counts for the reused CrossBridge CSR template + the break RNG:
    //      [0]=capacity (nM for csr*), [1]=step, [2]=seed, [3]=nSeg.  csr* read only [0]/[3];
    //      [1]/[2] feed the wang-hash — so the CSR template is reused VERBATIM. ----
    public final IntArray   counts;

    // ====== 5c-i FORMATION / ALLOCATOR (Design A: scan-rank free-list, no compaction) ======
    // A formation phase: (1) build a free-list = the FREE slots in index order via the validated
    // csrScan prefix-sum + a stream-compaction scatter; (2) rank accepted requests via csrScan over
    // accept-flags; (3) request rank r claims freeList[r], writes its payload, flips FREE→ACTIVE
    // (distinct ranks → distinct free slots ⇒ one writer per slot, race-free, no atomics); (4) clamp
    // nAccepted to nFree. Existing ACTIVE links never move (Design-A invariant). These arrays + the
    // request arrays are forward-compatible with 5c-ii (broad-phase fills the requests instead of the
    // synthetic driver). reqCap = max form-requests per step.
    public final int        reqCap;
    public final IntArray   reqFilA, reqFilB;   // request payload: filament slots
    public final FloatArray reqLoc1, reqLoc2;   // request payload: attachment arcs (µm)
    public final IntArray   acceptFlag;         // reqCap; per-request accept (1/0); consumed by the rank scan
    public final IntArray   rankOffsets;        // reqCap+1; exclusive prefix sum of acceptFlag (dense rank); [K]=nAccepted
    public final IntArray   rankScanCounts;     // csrScan counts for the rank scan ([3]=K this step)
    public final IntArray   freeCount;          // capacity; freeFlags writes 1 per FREE slot (csrScan input)
    public final IntArray   freeOffsets;        // capacity+1; exclusive prefix sum of freeCount; [C]=nFree
    public final IntArray   freeList;           // capacity; FREE slot indices compacted in index order
    public final IntArray   freeScanCounts;     // csrScan counts for the free-list scan ([3]=C)
    public final IntArray   allocCounts;        // [0]=C(capacity) [1]=K(requests this step) [2]=STRAIN_WIN

    public CrosslinkerStore(int nLinks, int nSeg) { this(nLinks, nSeg, 1); }

    public CrosslinkerStore(int nLinks, int nSeg, int reqCap) {
        this.nLinks = nLinks;                       // = pool CAPACITY C (the SoA size + CSR loop bound)
        int cap = Math.max(1, nLinks);
        linkFilA  = new IntArray(cap);
        linkFilB  = new IntArray(cap);
        loc1      = new FloatArray(cap);
        loc2      = new FloatArray(cap);
        filLinkCt = new IntArray(nSeg);
        xlinkData = new FloatArray(cap * STRIDE);
        xlinkData.init(0f);
        linkState   = new IntArray(cap);
        strainHist  = new FloatArray(cap * STRAIN_WIN);
        strainPlace = new IntArray(cap);
        offParams = new FloatArray(5);
        xlParams  = new FloatArray(3);
        counts    = new IntArray(4);
        counts.set(0, nLinks);
        counts.set(3, nSeg);

        // formation/allocator block
        this.reqCap = Math.max(1, reqCap);
        reqFilA = new IntArray(this.reqCap); reqFilB = new IntArray(this.reqCap);
        reqLoc1 = new FloatArray(this.reqCap); reqLoc2 = new FloatArray(this.reqCap);
        acceptFlag = new IntArray(this.reqCap);
        rankOffsets = new IntArray(this.reqCap + 1);
        rankScanCounts = new IntArray(4);
        freeCount = new IntArray(cap); freeOffsets = new IntArray(cap + 1); freeList = new IntArray(cap);
        freeScanCounts = new IntArray(4); freeScanCounts.set(3, cap);   // free-list scan bound = capacity C
        allocCounts = new IntArray(4);
        allocCounts.set(0, cap);              // C
        allocCounts.set(2, STRAIN_WIN);       // W

        // unused slots start FREE with a negative key ⇒ skipped by the CSR (key<0) and the gather guard.
        linkState.init(LINK_FREE);
        linkFilA.init(-1);
        linkFilB.init(-1);
        strainHist.init(0f);
        strainPlace.init(0);
        acceptFlag.init(0);
    }

    /** Set the number of form-requests K this formation step (the rank-scan bound + alloc K). */
    public void setRequestCount(int K) {
        rankScanCounts.set(3, K);
        allocCounts.set(1, K);
    }

    /** restLength = 0.0125 µm (v1 FilLink.java:28), fracMoveBase = 0.4 (v1 FilLink.java:208). */
    public void setParams(double restLength, double fracMoveBase, double dt) {
        xlParams.set(0, (float) restLength);
        xlParams.set(1, (float) fracMoveBase);
        xlParams.set(2, (float) dt);
    }

    /** Bell off-rate (5b): v1 Env defaults linkOffConst=1 /s, linkOffCoeff=1 /s, linkOffExp=2
     *  (Env.java:679/680/681). k_off(aveStrain) = const + coeff·exp(aveStrain·exp). */
    public void setOffParams(double linkOffConst, double linkOffCoeff, double linkOffExp, double dt, double restLength) {
        offParams.set(0, (float) linkOffConst);
        offParams.set(1, (float) linkOffCoeff);
        offParams.set(2, (float) linkOffExp);
        offParams.set(3, (float) dt);
        offParams.set(4, (float) restLength);
    }

    /** Per-step RNG keys for the wang-hash break draw (mirrors MotorStore.setCounts step/seed). */
    public void setCounts(int step, int seed) {
        counts.set(1, step);
        counts.set(2, seed);
    }

    /** Pre-place a static crosslinker (5a) and mark it ACTIVE (5b lifecycle). filA/filB are integer
     *  filament slots (distinct, per v1's filID!=filID exclusion); loc1/loc2 are microns from end1. */
    public void setLink(int k, int filA, double loc1Um, int filB, double loc2Um) {
        linkFilA.set(k, filA);
        linkFilB.set(k, filB);
        loc1.set(k, (float) loc1Um);
        loc2.set(k, (float) loc2Um);
        linkState.set(k, LINK_ACTIVE);
    }

    /** Compute the static per-filament link count (v1 getLinkCt) once: total links touching
     *  each filament as either side. For the 5a checks (one link, two filaments) this is 1
     *  each ⇒ fracMove = 0.4 exactly, matching v1's single-link case with no order/thread
     *  dependence. (v1 accumulates linkCt within a step as links are processed, so multi-
     *  link-per-segment fracMove is order/thread-dependent in v1 — a formation-era (5c)
     *  faithfulness concern, not 5a.) */
    public void computeFilLinkCt() {
        filLinkCt.init(0);
        for (int k = 0; k < nLinks; k++) {
            if (linkState.get(k) < 0) continue;     // skip FREE/DEAD slots (key would be -1)
            int a = linkFilA.get(k), b = linkFilB.get(k);
            filLinkCt.set(a, filLinkCt.get(a) + 1);
            filLinkCt.set(b, filLinkCt.get(b) + 1);
        }
    }
}
