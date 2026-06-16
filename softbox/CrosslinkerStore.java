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
    public final FloatArray xlinkData;  // nLinks * STRIDE

    // ---- kernel scalar params: [0]=restLength(µm) [1]=fracMoveBase(0.4) [2]=dt ----
    public final FloatArray xlParams;

    // ---- counts for the reused CrossBridge CSR template: [0]=nLinks, [3]=nSeg ----
    public final IntArray   counts;

    public CrosslinkerStore(int nLinks, int nSeg) {
        this.nLinks = nLinks;
        linkFilA  = new IntArray(Math.max(1, nLinks));
        linkFilB  = new IntArray(Math.max(1, nLinks));
        loc1      = new FloatArray(Math.max(1, nLinks));
        loc2      = new FloatArray(Math.max(1, nLinks));
        filLinkCt = new IntArray(nSeg);
        xlinkData = new FloatArray(Math.max(1, nLinks) * STRIDE);
        xlinkData.init(0f);
        xlParams  = new FloatArray(3);
        counts    = new IntArray(4);
        counts.set(0, nLinks);
        counts.set(3, nSeg);
    }

    /** restLength = 0.0125 µm (v1 FilLink.java:28), fracMoveBase = 0.4 (v1 FilLink.java:208). */
    public void setParams(double restLength, double fracMoveBase, double dt) {
        xlParams.set(0, (float) restLength);
        xlParams.set(1, (float) fracMoveBase);
        xlParams.set(2, (float) dt);
    }

    /** Pre-place a static crosslinker (5a). filA/filB are integer filament slots (distinct,
     *  per v1's filID!=filID exclusion); loc1/loc2 are microns along each segment from end1. */
    public void setLink(int k, int filA, double loc1Um, int filB, double loc2Um) {
        linkFilA.set(k, filA);
        linkFilB.set(k, filB);
        loc1.set(k, (float) loc1Um);
        loc2.set(k, (float) loc2Um);
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
            int a = linkFilA.get(k), b = linkFilB.get(k);
            filLinkCt.set(a, filLinkCt.get(a) + 1);
            filLinkCt.set(b, filLinkCt.get(b) + 1);
        }
    }
}
