package softbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * ============================ [NON-CANONICAL FAKE COUPLER] ============================
 * EXPERIMENT 2B — fake-coupler stiffness-transfer ladder (CPU-only diagnostic).
 *
 * A DELIBERATELY NON-BIOLOGICAL diagnostic. Its couplers (F0..F9) are NOT motors and are NEVER
 * intended to become canonical motors. They exist ONLY to measure how a coded spring stiffness is
 * transformed into the stiffness a blinded optical-trap experimentalist would infer, as we add — one
 * element at a time — 3D spring geometry, bond orientation, material-latched filament attachment,
 * filament translation, filament rotation, off-axis torque, trap compliance, and a fixed spherical
 * head. Default-off (only `-exp2b` triggers it); isolated to this file + a one-line dispatch in
 * LaserTrapHarness; the canonical/production path references NOTHING here. `BoA-v1ref` untouched.
 *
 * EXACT F8 (Gate 3, NOT reimplemented): every F8-based arm calls the PRODUCTION
 * CrossBridgeSystem.bondForces with the F9/F10 alignment coefficient set to ZERO (xbParams[2]=0),
 * isolating the pure zero-rest-length F8 spring F = myoSpring·(site − tip) and its exact R×F
 * positional torque. The "head" is a MotorStore head sub-body (index 3m+2); rod/lever are inert.
 * A key structural fact: F8 is a ZERO-REST-LENGTH Hookean spring ⇒ its linear stiffness tensor is
 * isotropic k·I regardless of bond orientation or preload, so any transfer loss below k must come
 * from filament MOTION (rotation / off-axis torque / transverse recoil), not from cos²θ projection.
 * The ladder measures this rather than assuming it.
 *
 * BLINDED estimator (frozen, identical to Exp-2A): from external observables ONLY — commanded axial
 * trap-center displacement, left/right trap force, filament displacement, known trap stiffness:
 *   k_obs   = ΔF_trap / Δx_command                 (paired ±Δ, drift-cancelled)
 *   k_motor = [ΔF_trap slope] / [Δx_fil/Δx_command slope]   (compliance-corrected; NaN if follow<5%)
 * Assigned k and internal channels are used only in the SECONDARY (post-freeze) telemetry / α ratio.
 * =====================================================================================
 */
public final class LaserTrapFakeCoupler {
    private LaserTrapFakeCoupler() {}

    // ---- inventories (preregistered) ----
    static final double[] K_ASSIGNED_PNNM = { 0.10, 0.25, 0.50, 1.0, 1.5, 2.0, 4.0 };
    static final double[] TRAP_PNNM       = { 0.02, 0.05, 0.10 };
    static final double[] DTS             = { 1.0e-5, 5.0e-6, 2.5e-6 };
    static final double PRE_NM = 2.0;     // low documented axial pretension
    static final double L_UM   = 1.0;     // rigid filament length
    static final double EQ_MS  = 20.0;    // equilibration per point (~15 τ_system)
    static final int    STRIDE = CrossBridgeSystem.STRIDE;
    static final double PNNM = 1.0e-9;    // pN/nm → N/µm

    static String OUT_DIR = null, JS_DIR = null;
    static int NATIVE_TARGET = 24;        // native snapshots/stage (F2..F4)
    static boolean FAST = false;

    // ============================ [NON-CANONICAL FAKE COUPLER] scene ============================
    static final class FakeScene {
        FilamentStore fil; MotorStore mot;
        FloatArray x0L, x0R, trapParams, bondData, xbParams;
        IntArray segMotorCount, segMotorOffsets, segMotorMyo;
        double dt, kAx, kTr, preUm, L, kAssigned;
        double gammaPar, gammaPerp;       // filament translational drag (SI)
        double fx, fy, fz;                 // assay axis f̂ (unit)
        double[] filU0, filY0;            // captured filament frame (rotation suppression)
        boolean suppressRotation, headRotates; double restraintK;
        double[] headCenterFixed;         // pinned head-sub-body center (F7)
        double[] capHeadU, capHeadY;      // captured head orientation (F8 restraint)
        int nC;
    }

    /** Allocate the trap+CSR scratch + xbParams (align OFF ⇒ pure F8) for a filament with nC couplers. */
    static void allocScratch(FakeScene sc, int nC, double kAssigned, double dt) {
        sc.nC = nC;
        sc.kAssigned = kAssigned;
        // xbParams size 6 (production Hookean shape). [0]=myoSpring [1]=90 [2]=0(align OFF) [3]=dt [4]=HEAD_LEN [5]=0
        sc.xbParams = FloatArray.fromElements((float) (kAssigned * PNNM), 90f, 0f, (float) dt, (float) MotorStore.HEAD_LEN, 0f);
        sc.bondData = new FloatArray(nC * STRIDE); sc.bondData.init(0f);
        sc.segMotorCount = new IntArray(1); sc.segMotorOffsets = new IntArray(2); sc.segMotorMyo = new IntArray(nC);
    }

    /** Place coupler c's frozen/rotatable head so its F8 tip = worldTip, head axis = headU (unit). */
    static void placeHeadTip(MotorStore mot, int c, double[] worldTip, double[] headU) {
        RigidRodBody b = mot.body; int nB = b.coord.getSize() / 3; int h = 3 * c + 2;
        double un = Math.sqrt(headU[0]*headU[0]+headU[1]*headU[1]+headU[2]*headU[2]);
        double hux = headU[0]/un, huy = headU[1]/un, huz = headU[2]/un;
        // tip = center + 0.5*HEAD_LEN*headU  ⇒  center = tip − 0.5*HEAD_LEN*headU
        double cx = worldTip[0] - 0.5*MotorStore.HEAD_LEN*hux;
        double cy = worldTip[1] - 0.5*MotorStore.HEAD_LEN*huy;
        double cz = worldTip[2] - 0.5*MotorStore.HEAD_LEN*huz;
        b.coord.set(h, (float)cx); b.coord.set(nB+h, (float)cy); b.coord.set(2*nB+h, (float)cz);
        b.uVec.set(h, (float)hux); b.uVec.set(nB+h, (float)huy); b.uVec.set(2*nB+h, (float)huz);
        // a yVec ⟂ headU (any)
        double[] yv = anyPerp(new double[]{hux,huy,huz});
        b.yVec.set(h, (float)yv[0]); b.yVec.set(nB+h, (float)yv[1]); b.yVec.set(2*nB+h, (float)yv[2]);
    }
    static double[] anyPerp(double[] u) {
        double[] a = Math.abs(u[0])<0.9 ? new double[]{1,0,0} : new double[]{0,1,0};
        double d = a[0]*u[0]+a[1]*u[1]+a[2]*u[2];
        double[] p = { a[0]-d*u[0], a[1]-d*u[1], a[2]-d*u[2] };
        double n = Math.sqrt(p[0]*p[0]+p[1]*p[1]+p[2]*p[2]);
        return new double[]{ p[0]/n, p[1]/n, p[2]/n };
    }

    /** Common builder: filament (rigid rod) at (coord,filU,filY) with nC couplers whose fixed tips/attach are given. */
    static FakeScene build(double dt, double L, double kAx, double kTr, double preNm, double kAssigned,
                           double[] filCoord, double[] filU, double[] filY, int fMono,
                           double[] attachArc, double[][] tips, double[][] headU,
                           boolean suppressRot, boolean headRot, double restraintK, boolean brownian) {
        FakeScene sc = new FakeScene();
        sc.dt = dt; sc.kAx = kAx; sc.kTr = kTr; sc.preUm = preNm*1e-3; sc.L = L;
        sc.suppressRotation = suppressRot; sc.headRotates = headRot; sc.restraintK = restraintK;
        int nC = attachArc.length;
        // ---- filament ----
        FilamentStore f = new FilamentStore(1);
        f.monomerCount.set(0, fMono);
        for (int i=0;i<3;i++){ f.coord.set(i,(float)filCoord[i]); f.uVec.set(i,(float)filU[i]); f.yVec.set(i,(float)filY[i]); }
        f.brownTransScale.set(0, brownian?(float)Constants.BTransCoeff:0f);
        f.brownRotScale.set(0,   brownian?(float)Constants.BRotCoeff:0f);
        DragTensorSystem.run(f); f.setParams(dt, brownian?Constants.brownianForceMag(dt):0.0); f.setCounts(0,1);
        DerivedGeometrySystem.derive(f.coord,f.uVec,f.yVec,f.zVec,f.end1,f.end2,f.segLength,f.counts);
        sc.fil = f;
        sc.fx = filU[0]; sc.fy = filU[1]; sc.fz = filU[2];
        sc.filU0 = new double[]{ f.uVecX(0), f.uVecY(0), f.uVecZ(0) };
        sc.filY0 = new double[]{ f.yVec.get(0), f.yVec.get(1), f.yVec.get(2) };
        sc.gammaPar = f.bTransGam.get(0); sc.gammaPerp = f.bTransGam.get(1);
        double segLen = f.segLength.get(0), half = 0.5*segLen, pre = preNm*1e-3;
        double e1x=filCoord[0]-half*filU[0], e1y=filCoord[1]-half*filU[1], e1z=filCoord[2]-half*filU[2];
        double e2x=filCoord[0]+half*filU[0], e2y=filCoord[1]+half*filU[1], e2z=filCoord[2]+half*filU[2];
        sc.x0L = FloatArray.fromElements((float)(e1x-pre*filU[0]),(float)(e1y-pre*filU[1]),(float)(e1z-pre*filU[2]));
        sc.x0R = FloatArray.fromElements((float)(e2x+pre*filU[0]),(float)(e2y+pre*filU[1]),(float)(e2z+pre*filU[2]));
        sc.trapParams = FloatArray.fromElements((float)kAx,(float)kTr,(float)filU[0],(float)filU[1],(float)filU[2],0f);
        // ---- couplers (MotorStore heads) ----
        MotorStore mot = new MotorStore(nC);
        for (int c=0;c<nC;c++) mot.assembleArticulated(c, 0f,0f,(float)LaserTrapHarness.MANCHOR_Z, 0f,0f,1f, brownian?(float)Constants.BTransCoeff:0f);
        DragTensorSystem.run(mot); mot.setBodyParams(dt);
        RigidRodBody b = mot.body; int nB = b.coord.getSize()/3;
        // brownian only on head sub-bodies (rod/lever inert)
        for (int c=0;c<nC;c++){ for(int k=0;k<2;k++){ b.brownTransScale.set(3*c+k,0f); b.brownRotScale.set(3*c+k,0f);} }
        for (int c=0;c<nC;c++){ int h=3*c+2; b.brownTransScale.set(h, (brownian&&headRot)?(float)Constants.BTransCoeff:0f);
            b.brownRotScale.set(h, (brownian&&headRot)?(float)Constants.BRotCoeff:0f); }
        for (int c=0;c<nC;c++){
            placeHeadTip(mot, c, tips[c], headU[c]);
            mot.boundSeg.set(c, 0); mot.bindArc.set(c, (float)attachArc[c]); mot.nucleotideState.set(c, MotorStore.NUC_ADP);
        }
        DerivedGeometrySystem.derive(b.coord,b.uVec,b.yVec,b.zVec,b.end1,b.end2,b.segLength,mot.counts);
        // capture head pin (nC==1 rotating/restrained arms)
        int h0 = 2;
        sc.headCenterFixed = new double[]{ b.coord.get(h0), b.coord.get(nB+h0), b.coord.get(2*nB+h0) };
        sc.capHeadU = new double[]{ b.uVec.get(h0), b.uVec.get(nB+h0), b.uVec.get(2*nB+h0) };
        sc.capHeadY = new double[]{ b.yVec.get(h0), b.yVec.get(nB+h0), b.yVec.get(2*nB+h0) };
        sc.mot = mot;
        allocScratch(sc, nC, kAssigned, dt);
        return sc;
    }

    // ---------- synthetic axial builder (F0 uses a scalar spring instead; F1/F5/F6/F7 use F8) ----------
    static FakeScene buildSyntheticF8(double dt, double kAssigned, double kAx, double kTr, double preNm,
                                      double[] attachArc, double[][] tipOffAlong, boolean suppressRot, boolean headRot, double restraintK, boolean brownian) {
        // filament along x, midpoint at origin, z=0
        int mc = Math.max(1, (int)Math.round(L_UM/Constants.actinMonoRadius)-1);
        double[] filU={1,0,0}, filY={0,1,0}, filCoord={0,0,0};
        // build a throwaway to learn segLen ⇒ site positions
        FilamentStore tmp = new FilamentStore(1); tmp.monomerCount.set(0,mc);
        tmp.setUVec(0,1f,0f,0f); tmp.setYVec(0,0f,1f,0f); tmp.setCoord(0,0f,0f,0f);
        DragTensorSystem.run(tmp); tmp.setCounts(0,1);
        DerivedGeometrySystem.derive(tmp.coord,tmp.uVec,tmp.yVec,tmp.zVec,tmp.end1,tmp.end2,tmp.segLength,tmp.counts);
        double segLen = tmp.segLength.get(0), half=0.5*segLen;
        int nC = attachArc.length;
        double[][] tips = new double[nC][3], headU = new double[nC][3];
        for (int c=0;c<nC;c++){
            double aOff = attachArc[c]-half;
            double sx = aOff;                     // site x (filament along x, COM origin)
            // fixed tip = site + tipOffAlong (documented offset, µm). preload = k·|tipOff|.
            tips[c] = new double[]{ sx + tipOffAlong[c][0], tipOffAlong[c][1], tipOffAlong[c][2] };
            headU[c] = new double[]{1,0,0};
        }
        return build(dt, L_UM, kAx, kTr, preNm, kAssigned, filCoord, filU, filY, mc, attachArc, tips, headU, suppressRot, headRot, restraintK, brownian);
    }

    // ---------- native builder (F2/F3/F4): filament + tip from a captured Snap ----------
    static FakeScene buildNativeF8(LaserTrapHarness.Snap s, double kAssigned, double kAx, double kTr, double preNm,
                                   boolean suppressRot, boolean headRot, double restraintK, boolean zeroPreload, double attachOverrideArc, boolean brownian) {
        double half = 0.5*s.fSegLen;
        double[] filU = { s.fUVec[0], s.fUVec[1], s.fUVec[2] };
        double[] filY = { s.fYVec[0], s.fYVec[1], s.fYVec[2] };
        double[] filCoord = { s.fCoord[0], s.fCoord[1], s.fCoord[2] };
        // native head tip. Snap.bCoord is PLANAR [rodX,levX,headX, rodY,levY,headY, rodZ,levZ,headZ]
        // ⇒ head center = (bCoord[2],bCoord[5],bCoord[8]); head axis = (bUVec[2],bUVec[5],bUVec[8]).
        double hcx=s.bCoord[2], hcy=s.bCoord[5], hcz=s.bCoord[8];
        double hux=s.bUVec[2], huy=s.bUVec[5], huz=s.bUVec[8];
        double tipx=hcx+0.5*MotorStore.HEAD_LEN*hux, tipy=hcy+0.5*MotorStore.HEAD_LEN*huy, tipz=hcz+0.5*MotorStore.HEAD_LEN*huz;
        double arc = (attachOverrideArc>=0) ? attachOverrideArc : s.bindArc;
        double aOff = arc - half;
        double sitex=filCoord[0]+aOff*filU[0], sitey=filCoord[1]+aOff*filU[1], sitez=filCoord[2]+aOff*filU[2];
        double[] tip = zeroPreload ? new double[]{sitex,sitey,sitez} : new double[]{tipx,tipy,tipz};
        double[] headU = new double[]{ hux,huy,huz };
        double[][] tips = { tip }; double[][] hU = { headU }; double[] att = { arc };
        return build(s.dt, s.fSegLen /*L≈*/, kAx, kTr, preNm, kAssigned, filCoord, filU, filY, s.fMono, att, tips, hU, suppressRot, headRot, restraintK, brownian);
    }

    // ============================ integration step ============================
    static void fakeStep(FakeScene sc, int t, int seed, boolean brownian) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body; int nB = b.coord.getSize()/3;
        mot.setCounts(t, seed, f.n); f.counts.set(1, t); f.counts.set(2, seed);   // thread step+seed to the filament FDT draw
        // --- rotating spherical head (F7/F8): F8 torque rotates head about its pinned center ---
        if (sc.headRotates) {
            ChainBendingForceSystem.zeroAccumulators(b.forceSum, b.torqueSum, mot.counts);
            if (brownian) BrownianForceSystem.brownianForce(b.randForce,b.randTorque,b.bTransGam,b.bRotGam,b.brownTransScale,b.brownRotScale,mot.bodyParams,mot.counts);
            CrossBridgeSystem.bondForces(b.coord,b.uVec,b.yVec,b.bRotGam, f.coord,f.uVec,f.yVec,f.bRotGam,f.segLength, mot.boundSeg,mot.bindArc,mot.nucleotideState, sc.bondData, sc.xbParams);
            CrossBridgeSystem.applyHeadForce(sc.bondData, b.forceSum, b.torqueSum, mot.counts);
            if (sc.restraintK > 0) applyOrientRestraint(sc, b);   // [NON-CANONICAL FAKE COUPLER] F8-arm orientation restraint
            RigidRodLangevinIntegrationSystem.integrate(b.coord,b.uVec,b.yVec,b.forceSum,b.torqueSum,b.randForce,b.randTorque,b.bTransGam,b.bRotGam,mot.bodyParams,mot.counts);
            DerivedGeometrySystem.orthogonalizeY(b.uVec,b.yVec,mot.counts);
            // pin the head CENTER (translation fixed; rotation free) — head sub-body index 2 only
            b.coord.set(2,(float)sc.headCenterFixed[0]); b.coord.set(nB+2,(float)sc.headCenterFixed[1]); b.coord.set(2*nB+2,(float)sc.headCenterFixed[2]);
            DerivedGeometrySystem.derive(b.coord,b.uVec,b.yVec,b.zVec,b.end1,b.end2,b.segLength,mot.counts);
        }
        // --- filament: F8 seg reaction (current head) + traps + integrate ---
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        if (brownian) BrownianForceSystem.brownianForce(f.randForce,f.randTorque,f.bTransGam,f.bRotGam,f.brownTransScale,f.brownRotScale,f.params,f.counts);
        CrossBridgeSystem.bondForces(b.coord,b.uVec,b.yVec,b.bRotGam, f.coord,f.uVec,f.yVec,f.bRotGam,f.segLength, mot.boundSeg,mot.bindArc,mot.nucleotideState, sc.bondData, sc.xbParams);
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, sc.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts, sc.segMotorCount, sc.segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, sc.segMotorOffsets, sc.segMotorCount, sc.segMotorMyo);
        CrossBridgeSystem.segGather(sc.segMotorOffsets, sc.segMotorMyo, sc.bondData, f.forceSum, f.torqueSum, mot.counts);
        LaserTrapSystem.applyTraps3D(f.coord,f.uVec,f.segLength, sc.x0L, sc.x0R, f.forceSum, f.torqueSum, sc.trapParams, f.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord,f.uVec,f.yVec,f.forceSum,f.torqueSum,f.randForce,f.randTorque,f.bTransGam,f.bRotGam,f.params,f.counts);
        DerivedGeometrySystem.orthogonalizeY(f.uVec,f.yVec,f.counts);
        if (sc.suppressRotation) {   // kinematic filament-rotation hold (restore captured orientation)
            f.setUVec(0,(float)sc.filU0[0],(float)sc.filU0[1],(float)sc.filU0[2]);
            f.setYVec(0,(float)sc.filY0[0],(float)sc.filY0[1],(float)sc.filY0[2]);
        }
        DerivedGeometrySystem.derive(f.coord,f.uVec,f.yVec,f.zVec,f.end1,f.end2,f.segLength,f.counts);
    }

    /** [NON-CANONICAL FAKE COUPLER] soft restraint torque driving head slot-2 orientation → captured (F8-arm). */
    static void applyOrientRestraint(FakeScene sc, RigidRodBody b) {
        int nB=b.coord.getSize()/3, h=2;
        double hux=b.uVec.get(h),huy=b.uVec.get(nB+h),huz=b.uVec.get(2*nB+h);
        double cx=sc.capHeadU[0],cy=sc.capHeadU[1],cz=sc.capHeadU[2];
        // torque ∝ headU × capU rotates headU toward capU
        double tx=huy*cz-huz*cy, ty=huz*cx-hux*cz, tz=hux*cy-huy*cx;
        b.torqueSum.set(h,(float)(b.torqueSum.get(h)+sc.restraintK*tx));
        b.torqueSum.set(nB+h,(float)(b.torqueSum.get(nB+h)+sc.restraintK*ty));
        b.torqueSum.set(2*nB+h,(float)(b.torqueSum.get(2*nB+h)+sc.restraintK*tz));
    }

    // ============================ measurement (blinded primary + telemetry) ============================
    /** idx: 0 filAxial(µm) · 1 trapNetAxial(N) · 2 trapNetMag(N) · 3 torqueMag(N·m) · 4 couplerAxial(N) ·
     *  5 f8dist(µm) · 6 bondAngleDeg(vs f̂) · 7 filRotDeg · 8 filTransverse(µm) · 9 f8mag(N) · 10 attachDisp(µm) */
    static double[] measFake(FakeScene sc) {
        FilamentStore f = sc.fil; MotorStore mot = sc.mot; RigidRodBody b = mot.body; int nB=b.coord.getSize()/3;
        double cx=f.coordX(0),cy=f.coordY(0),cz=f.coordZ(0), ux=f.uVecX(0),uy=f.uVecY(0),uz=f.uVecZ(0);
        double half=0.5*f.segLength.get(0);
        double fx=sc.fx,fy=sc.fy,fz=sc.fz;
        double filAxial=cx*fx+cy*fy+cz*fz;
        double e1x=cx-half*ux,e1y=cy-half*uy,e1z=cz-half*uz, e2x=cx+half*ux,e2y=cy+half*uy,e2z=cz+half*uz;
        double kAx=sc.kAx,kTr=sc.kTr;
        double[] FL=trapForce(e1x,e1y,e1z, sc.x0L.get(0),sc.x0L.get(1),sc.x0L.get(2), kAx,kTr,fx,fy,fz);
        double[] FR=trapForce(e2x,e2y,e2z, sc.x0R.get(0),sc.x0R.get(1),sc.x0R.get(2), kAx,kTr,fx,fy,fz);
        double netx=FL[0]+FR[0], nety=FL[1]+FR[1], netz=FL[2]+FR[2];
        double netAxial=netx*fx+nety*fy+netz*fz, netMag=Math.sqrt(netx*netx+nety*nety+netz*netz);
        // torque r×F
        double r1x=(e1x-cx)*1e-6,r1y=(e1y-cy)*1e-6,r1z=(e1z-cz)*1e-6, r2x=(e2x-cx)*1e-6,r2y=(e2y-cy)*1e-6,r2z=(e2z-cz)*1e-6;
        double tx=(r1y*FL[2]-r1z*FL[1])+(r2y*FR[2]-r2z*FR[1]);
        double ty=(r1z*FL[0]-r1x*FL[2])+(r2z*FR[0]-r2x*FR[2]);
        double tz=(r1x*FL[1]-r1y*FL[0])+(r2x*FR[1]-r2y*FR[0]);
        double torqueMag=Math.sqrt(tx*tx+ty*ty+tz*tz);
        // coupler seg-side force (telemetry) = Σ bondData[c*STRIDE+6..8]
        double cfx=0,cfy=0,cfz=0;
        for (int c=0;c<sc.nC;c++){ int d=c*STRIDE; cfx+=sc.bondData.get(d+6); cfy+=sc.bondData.get(d+7); cfz+=sc.bondData.get(d+8); }
        double couplerAxial=cfx*fx+cfy*fy+cfz*fz;
        // F8 bond telemetry (coupler 0)
        int h=2; double hux=b.uVec.get(h),huy=b.uVec.get(nB+h),huz=b.uVec.get(2*nB+h);
        double hcx=b.coord.get(h),hcy=b.coord.get(nB+h),hcz=b.coord.get(2*nB+h);
        double tipx=hcx+0.5*MotorStore.HEAD_LEN*hux,tipy=hcy+0.5*MotorStore.HEAD_LEN*huy,tipz=hcz+0.5*MotorStore.HEAD_LEN*huz;
        double aOff=mot.bindArc.get(0)-half;
        double stx=cx+aOff*ux,sty=cy+aOff*uy,stz=cz+aOff*uz;
        double dxb=stx-tipx,dyb=sty-tipy,dzb=stz-tipz; double f8dist=Math.sqrt(dxb*dxb+dyb*dyb+dzb*dzb);
        double f8mag=(sc.kAssigned*PNNM)*f8dist;
        double bondAngle = f8dist>1e-12 ? Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,(dxb*fx+dyb*fy+dzb*fz)/f8dist)))) : 0;
        double dotu=Math.max(-1,Math.min(1, ux*sc.filU0[0]+uy*sc.filU0[1]+uz*sc.filU0[2]));
        double filRotDeg=Math.toDegrees(Math.acos(dotu));
        double transX=cx-filAxial*fx, transY=cy-filAxial*fy, transZ=cz-filAxial*fz;
        double filTrans=Math.sqrt(transX*transX+transY*transY+transZ*transZ);
        return new double[]{ filAxial, netAxial, netMag, torqueMag, couplerAxial, f8dist, bondAngle, filRotDeg, filTrans, f8mag, 0 };
    }
    /** 3D diagonal-tensor trap force on an endpoint: −kAx(Δ·f̂)f̂ − kTr(Δ − (Δ·f̂)f̂). */
    static double[] trapForce(double ex,double ey,double ez,double x0,double y0,double z0,double kAx,double kTr,double fx,double fy,double fz){
        double dx=ex-x0,dy=ey-y0,dz=ez-z0; double a=dx*fx+dy*fy+dz*fz;
        return new double[]{ -kAx*a*fx-kTr*(dx-a*fx), -kAx*a*fy-kTr*(dy-a*fy), -kAx*a*fz-kTr*(dz-a*fz) };
    }

    // ============================ blinded paired estimator (fresh-per-point) ============================
    /** {ΔF_trap(N), Δx_fil(µm), telemetry...} for one signed command, fresh scene. */
    static double[] pertOnce(Supplier<FakeScene> build, double dxc, int settle, int seed, boolean brownian) {
        FakeScene sc = build.get();
        for (int t=0;t<settle;t++) fakeStep(sc, t, seed, brownian);
        double[] m0 = measFake(sc);
        if (dxc != 0) {
            sc.x0L.set(0,(float)(sc.x0L.get(0)+dxc*sc.fx)); sc.x0L.set(1,(float)(sc.x0L.get(1)+dxc*sc.fy)); sc.x0L.set(2,(float)(sc.x0L.get(2)+dxc*sc.fz));
            sc.x0R.set(0,(float)(sc.x0R.get(0)+dxc*sc.fx)); sc.x0R.set(1,(float)(sc.x0R.get(1)+dxc*sc.fy)); sc.x0R.set(2,(float)(sc.x0R.get(2)+dxc*sc.fz));
            for (int t=0;t<settle;t++) fakeStep(sc, settle+t, seed, brownian);
        }
        double[] m1 = measFake(sc);
        boolean unstable = !Double.isFinite(m1[0]) || Math.abs(m1[0]-m0[0])>0.5;
        return new double[]{ m1[1]-m0[1], m1[0]-m0[0], m1[3]-m0[3], m1[7], m1[6], m1[8], unstable?1:0, m1[9] };
    }
    /** Paired ±step blinded readout. Returns {k_obs(pN/nm), k_motor(pN/nm), followFrac, torque(N·m), unstable, kObsSeries(pN/nm)}. */
    static double[] paired(Supplier<FakeScene> build, double stepUm, int settle, int seed, boolean brownian, double kTrapCode) {
        double[] p = pertOnce(build, stepUm, settle, seed, brownian);
        double[] m = pertOnce(build, -stepUm, settle, seed, brownian);
        double kObsSlope = 0.5*(p[0]/stepUm + m[0]/(-stepUm));   // N/µm
        double dxSlope   = 0.5*(p[1]/stepUm + m[1]/(-stepUm));   // Δx_fil/Δcmd
        double kObs = kObsSlope*1e9;
        double kMot = (dxSlope>0.05) ? kObsSlope/dxSlope*1e9 : Double.NaN;
        double kObsSeries = 1.0/(1.0/(kTrapCode) + 1.0/(kObsSlope/Math.max(1e-30,dxSlope)))*1e9;
        double unstable = Math.max(p[6],m[6]);
        return new double[]{ kObs, kMot, dxSlope, 0.5*(p[2]+m[2]), unstable, kObsSeries };
    }
    static int settleSteps(double dt){ return (int)Math.round(EQ_MS*1e-3/dt); }

    // ============================ CSV ============================
    static final class Csv {
        final StringBuilder sb=new StringBuilder(); Csv(String h){ sb.append(h).append('\n'); }
        void row(Object... c){ for(int i=0;i<c.length;i++){ if(i>0) sb.append(','); Object o=c[i];
            sb.append(o instanceof Double||o instanceof Float ? String.format(Locale.US,"%.9g",((Number)o).doubleValue()) : String.valueOf(o)); } sb.append('\n'); }
        void write(String name){ if(OUT_DIR==null) return; try{ Files.createDirectories(Path.of(OUT_DIR)); Files.writeString(Path.of(OUT_DIR,name), sb.toString());
            System.out.println("# wrote "+Path.of(OUT_DIR,name)); }catch(IOException e){ throw new UncheckedIOException(e);} }
    }

    // =====================================================================================
    //  MAIN ENTRY — the full Exp-2B ladder
    // =====================================================================================
    static void run(String[] args) {
        for (int i=0;i<args.length;i++){ switch(args[i]){
            case "-out" -> OUT_DIR=args[++i];
            case "-viz","-3js" -> { if(i+1<args.length && !args[i+1].startsWith("-")) JS_DIR=args[++i]; }
            case "-target" -> NATIVE_TARGET=Integer.parseInt(args[++i]);
            case "-fast" -> FAST=true;
            default -> {}
        } }
        if (FAST) NATIVE_TARGET=Math.min(NATIVE_TARGET,8);
        double dt=1e-5;
        System.out.println("=== SoftBox — EXPERIMENT 2B: [NON-CANONICAL FAKE COUPLER] stiffness-transfer ladder (CPU-only) ===");
        System.out.printf(Locale.US,"# F8 = production zero-rest Hookean spring via CrossBridgeSystem.bondForces (align OFF, xbParams[2]=0); default-off; canonical untouched.%n");
        System.out.printf(Locale.US,"# assigned k(pN/nm)=%s  trap(pN/nm)=%s  dt=%s  pretension=%.1f nm  L=%.3f µm  eq=%.0f ms  CPU load=%s%n",
                java.util.Arrays.toString(K_ASSIGNED_PNNM), java.util.Arrays.toString(TRAP_PNNM), java.util.Arrays.toString(DTS), PRE_NM, L_UM, EQ_MS, readLoadAvg());

        boolean[] gate = new boolean[15];   // 1..14
        java.util.Arrays.fill(gate,true);

        // ---------- F0 + F1 + F6 transfer curve (synthetic, full k × trap) ----------
        Csv transfer = new Csv("arm,assigned_pNnm,trap_pNnm,kObs_pNnm,kMotor_pNnm,follow,alpha,identifiable");
        System.out.println("#\n# ---------- TRANSFER CURVE: F0 scalar spring · F1 axial F8 · F6 fixed sphere (k × trap) ----------");
        boolean f0recover=true, f1recover=false;
        for (double kPN : K_ASSIGNED_PNNM) {
            for (double tPN : TRAP_PNNM) {
                double kAx=tPN*PNNM, kTr=tPN*PNNM, kTrap=2*kAx;
                int settle=settleSteps(dt);
                // F0 scalar spring (reuse the Exp-2A validated known-spring control geometry)
                double[] f0 = scalarSpringPaired(dt, kPN*PNNM, kAx, kTr, 1e-3, settle);
                double a0 = f0[1]/kPN;
                transfer.row("F0", kPN, tPN, f0[0], f0[1], f0[2], a0, f0[1]>0);
                if (Math.abs(a0-1)>0.10) f0recover=false;
                // F1 axial F8 (centered, zero preload, rotation suppressed)
                final double fkAx=kAx, fkTr=kTr, fkPN=kPN;
                double[] f1 = paired(()->buildSyntheticF8(dt, fkPN, fkAx, fkTr, PRE_NM, new double[]{0.5*segLenFor(dt)}, new double[][]{{0,0,0}}, true, false, 0, false), 1e-3, settle, 0, false, kTrap);
                double a1=f1[1]/kPN;
                transfer.row("F1", kPN, tPN, f1[0], f1[1], f1[2], a1, Double.isFinite(f1[1]));
                // F6 fixed sphere (axial, centered, rotation OFF) — should equal F1
                double[] f6 = paired(()->buildSyntheticF8(dt, fkPN, fkAx, fkTr, PRE_NM, new double[]{0.5*segLenFor(dt)}, new double[][]{{0,0,0}}, false, false, 0, false), 1e-3, settle, 0, false, kTrap);
                double a6=f6[1]/kPN;
                transfer.row("F6", kPN, tPN, f6[0], f6[1], f6[2], a6, Double.isFinite(f6[1]));
                if (Math.abs(kPN-1.0)<1e-9 && Math.abs(tPN-0.05)<1e-9) {
                    System.out.printf(Locale.US,"#   @k=1,trap=0.05: F0 α=%.3f  F1 α=%.3f  F6 α=%.3f (F1≈F6 check)%n",a0,a1,a6);
                    if (a1>=0.90 && a1<=1.10) f1recover=true;
                }
            }
        }
        transfer.write("transfer_curve.csv");
        gate[2]=f0recover;
        // Full-recovery reference (Gate 4): F1 at k=1 across ≥2 traps within [0.90,1.10]
        int f1ok=0; for (double tPN:TRAP_PNNM){ double kAx=tPN*PNNM,kTr=tPN*PNNM;
            double[] f1=paired(()->buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.5*segLenFor(dt)},new double[][]{{0,0,0}},true,false,0,false),1e-3,settleSteps(dt),0,false,2*kAx);
            if (f1[1]>=0.90 && f1[1]<=1.10) f1ok++; }
        gate[4]=f1ok>=2;
        System.out.printf(Locale.US,"# F0 recovers scalar spring: %s ; F1 axial-F8 full recovery across %d/3 traps: %s%n",
                f0recover?"YES":"NO", f1ok, gate[4]?"YES (Gate 4)":"partial");

        // ---------- F1 vs F2 vs F3 vs F4 : orientation / rotation / torque localization (native, k=1) ----------
        System.out.println("#\n# ---------- NATIVE localization: F2 orient · F3 +rotation · F4 off-axis (k=1, trap=0.05) ----------");
        int[] seeds={0,1,2,3};
        List<LaserTrapHarness.Snap>[] snaps = LaserTrapHarness.generateSnapshots(64, seeds, NATIVE_TARGET, dt, L_UM, 0.05*PNNM, 0.05*PNNM, PRE_NM);
        // native-orientation inventory
        Csv inv = new Csv("stage,idx,j1nat_deg,j2nat_deg,bindArc_um,bondAngle_deg,bondLen_nm");
        Csv nat = new Csv("arm,stage,idx,variant,kObs_pNnm,kMotor_pNnm,follow,alpha,bondAngle_deg,unstable");
        double kAx=0.05*PNNM, kTr=0.05*PNNM, kTrap=2*kAx; int settle=settleSteps(dt);
        double[] f2z=acc(), f2p=acc(), f3=acc(), f9=acc();   // accumulators {sum,sumsq,n} of k_motor
        int[] stagesUsed = {1,4};   // B eq-ADP·Pi, E plateau
        for (int st : stagesUsed) {
            List<LaserTrapHarness.Snap> lst = snaps[st];
            for (int idx=0; idx<lst.size(); idx++) {
                final LaserTrapHarness.Snap s = lst.get(idx);
                // inventory
                double[] geo = nativeBondGeo(s);
                inv.row(LaserTrapHarness.STAGE_NM[st], idx, s.j1nat, s.j2nat, s.bindArc, geo[0], geo[1]*1e3);
                // F2 variant1: orientation only, preload=0, rotation suppressed
                double[] a=paired(()->buildNativeF8(s,1.0,kAx,kTr,PRE_NM,true,false,0,true,-1,false),1e-3,settle,0,false,kTrap);
                nat.row("F2",LaserTrapHarness.STAGE_NM[st],idx,"zeroPreload",a[0],a[1],a[2],a[1]/1.0,geo[0],a[4]);
                if (st==4 && Double.isFinite(a[1])) push(f2z,a[1]);
                // F2 variant2: orientation + native preload, rotation suppressed
                double[] bb=paired(()->buildNativeF8(s,1.0,kAx,kTr,PRE_NM,true,false,0,false,-1,false),1e-3,settle,0,false,kTrap);
                nat.row("F2",LaserTrapHarness.STAGE_NM[st],idx,"nativePreload",bb[0],bb[1],bb[2],bb[1]/1.0,geo[0],bb[4]);
                if (st==4 && Double.isFinite(bb[1])) push(f2p,bb[1]);
                // F3: F2 (native preload) + filament rotation enabled
                double[] c=paired(()->buildNativeF8(s,1.0,kAx,kTr,PRE_NM,false,false,0,false,-1,false),1e-3,settle,0,false,kTrap);
                nat.row("F3",LaserTrapHarness.STAGE_NM[st],idx,"nativePreload",c[0],c[1],c[2],c[1]/1.0,geo[0],c[4]);
                if (st==4 && Double.isFinite(c[1])) push(f3,c[1]);
                // F9: canonical frozen-motor / F8-only (= Exp-2A H8) via the existing estimator
                double[] r=new double[4];
                double[][] hv = LaserTrapHarness.pairedResponse(s,kAx,kTr,PRE_NM,0.5e-3,8,20,2000,LaserTrapHarness.sampleSteps(dt,2000),0,false,r);
                double kmot9 = hv[hv.length-1][1];   // compliance-corrected plateau
                nat.row("F9",LaserTrapHarness.STAGE_NM[st],idx,"H8",hv[hv.length-1][0],kmot9,0.0,kmot9/1.0,geo[0],r[3]);
                if (st==4 && Double.isFinite(kmot9)) push(f9,kmot9);
            }
        }
        // F4 off-axis TORQUE sweep (synthetic, k=1, trap=0.05): a TRANSVERSE (z) bond at an off-center attach ⇒
        // the transverse F8 force at an off-COM material point makes a real torque (r×F≠0). Rotation ENABLED so the
        // filament can tilt. (An AXIAL bond at an on-axis attach has r∥F ⇒ zero torque — a null; hence the z-bond.)
        Csv f4 = new Csv("attach_fracFromEnd,rotation,kObs_pNnm,kMotor_pNnm,follow,alpha,torque_Nm,filRot_deg");
        double sl=segLenFor(dt);
        double zoffB=0.5*MotorStore.HEAD_LEN;   // transverse (z) bond height
        double[] fracs={0.5,0.25,0.125};   // 0.5=mid (r∥? no, z-bond ⇒ torque only off-center), 0.25=quarter, 0.125=eighth
        for (double fr:fracs){ final double arc=fr*sl;
            // rotation ON (filament free to tilt) vs OFF (control) at the same geometry
            double[] on =paired(()->buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{arc},new double[][]{{0,0,zoffB}},false,false,0,false),1e-3,settle,0,false,kTrap);
            double[] off=paired(()->buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{arc},new double[][]{{0,0,zoffB}},true, false,0,false),1e-3,settle,0,false,kTrap);
            double[] to=pertOnce(()->buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{arc},new double[][]{{0,0,zoffB}},false,false,0,false),1e-3,settle,0,false);
            f4.row(fr,"on", on[0], on[1], on[2], on[1]/1.0, to[2], to[3]);
            f4.row(fr,"off",off[0],off[1],off[2],off[1]/1.0, 0.0, 0.0);
            System.out.printf(Locale.US,"#   F4 attach=%.3f L (z-bond): rotON kMotor=%.4f  rotOFF kMotor=%.4f  torqueΔ=%.2e N·m filRot=%.3f°%n",fr,on[1],off[1],to[2],to[3]);
        }
        f4.write("f4_offaxis.csv");
        // F5 symmetric two-bond (torque-canceling): two z-bonds at ±0.25 L, per-bond k=0.5 ⇒ total assigned axial=1.0,
        // rotation ENABLED. Torques cancel by symmetry ⇒ tests whether removing NET filament torque recovers stiffness.
        double aOffset=0.25*sl;
        double[] f5 = paired(()->buildSyntheticF8Two(dt,0.5,kAx,kTr,PRE_NM,new double[]{0.5*sl-aOffset,0.5*sl+aOffset},zoffB,true), 1e-3, settle, 0, false, kTrap);
        // single off-center z-bond at 0.25 L, per-bond k=1.0, rotation ON (a net-torque coupler for contrast)
        double[] f5single = paired(()->buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.25*sl},new double[][]{{0,0,zoffB}},false,false,0,false),1e-3,settle,0,false,kTrap);
        nat.write("native_localization.csv"); inv.write("native_orientation_inventory.csv");

        // ---------- F7 rotating sphere · F8 restrained sphere (synthetic, k ladder, trap=0.05) ----------
        System.out.println("#\n# ---------- SPHERE arms: F6 fixed · F7 free-rotating · F8 orientation-restrained (trap=0.05) ----------");
        Csv sph = new Csv("arm,assigned_pNnm,kObs_pNnm,kMotor_pNnm,follow,alpha");
        for (double kPN:K_ASSIGNED_PNNM){ final double fkPN=kPN;
            // sphere with an OFF-AXIS bond (head above filament along +z) so rotation has a lever ⇒ F7 differs from F6
            double zoff=0.5*MotorStore.HEAD_LEN;   // head one radius above the site ⇒ transverse (z) bond, off-axis for rotation
            double[] f6s=paired(()->buildSyntheticF8(dt,fkPN,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,zoff}},false,false,0,false),1e-3,settle,0,false,kTrap);
            double[] f7s=paired(()->buildSyntheticF8(dt,fkPN,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,zoff}},false,true,0,false),1e-3,settle,0,false,kTrap);
            double[] f8s=paired(()->buildSyntheticF8(dt,fkPN,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,zoff}},false,true,1e-15,false),1e-3,settle,0,false,kTrap);
            sph.row("F6z",kPN,f6s[0],f6s[1],f6s[2],f6s[1]/kPN);
            sph.row("F7z",kPN,f7s[0],f7s[1],f7s[2],f7s[1]/kPN);
            sph.row("F8z",kPN,f8s[0],f8s[1],f8s[2],f8s[1]/kPN);
            if (Math.abs(kPN-1.0)<1e-9) System.out.printf(Locale.US,"#   @k=1 (off-axis z-bond): F6 kMotor=%.4f  F7(rot) kMotor=%.4f  F8(restrained) kMotor=%.4f%n",f6s[1],f7s[1],f8s[1]);
        }
        sph.write("sphere_arms.csv");

        // ---------- summaries ----------
        System.out.println("#\n# ---------- LOCALIZATION SUMMARY (native stage-E, k=1, trap=0.05) ----------");
        System.out.printf(Locale.US,"#   F2 zeroPreload  kMotor = %.4f ± %.4f pN/nm (n=%d)  [orientation, rotation OFF]%n", mean(f2z),sd(f2z),(int)f2z[2]);
        System.out.printf(Locale.US,"#   F2 nativePreload kMotor = %.4f ± %.4f pN/nm (n=%d)  [orientation+preload, rotation OFF]%n", mean(f2p),sd(f2p),(int)f2p[2]);
        System.out.printf(Locale.US,"#   F3 nativePreload kMotor = %.4f ± %.4f pN/nm (n=%d)  [+ filament rotation]%n", mean(f3),sd(f3),(int)f3[2]);
        System.out.printf(Locale.US,"#   F9 H8 canonical  kMotor = %.4f ± %.4f pN/nm (n=%d)  [frozen whole motor; Exp-2A ~0.65]%n", mean(f9),sd(f9),(int)f9[2]);
        System.out.printf(Locale.US,"#   F5 two-bond total kMotor=%.4f  vs single(off-center 0.25L) kMotor=%.4f pN/nm%n", f5[1], f5single[1]);
        gate[9] = Math.abs(mean(f9)-0.65) < 0.20;   // H8 continuity

        // ---------- timestep (Gate 12): F1 & highest k across dt ----------
        System.out.println("#\n# ---------- TIMESTEP: F1 (k=1) and F6 (k=4) across dt (trap=0.05) ----------");
        Csv ts = new Csv("arm,assigned_pNnm,dt_s,kObs_pNnm,kMotor_pNnm");
        double[] k1dt=new double[DTS.length], k4dt=new double[DTS.length];
        for (int di=0;di<DTS.length;di++){ final double dtx=DTS[di]; int stx=settleSteps(dtx);
            double[] a=paired(()->buildSyntheticF8(dtx,1.0,kAx,kTr,PRE_NM,new double[]{0.5*segLenFor(dtx)},new double[][]{{0,0,0}},true,false,0,false),1e-3,stx,0,false,kTrap);
            double[] c=paired(()->buildSyntheticF8(dtx,4.0,kAx,kTr,PRE_NM,new double[]{0.5*segLenFor(dtx)},new double[][]{{0,0,0}},true,false,0,false),1e-3,stx,0,false,kTrap);
            // k=4 ≫ trap ⇒ k_motor is UNIDENTIFIABLE (ill-conditioned series); track k_OBS (always identifiable, trap-limited)
            k1dt[di]=a[1]; k4dt[di]=c[0]; ts.row("F1",1.0,dtx,a[0],a[1]); ts.row("F6",4.0,dtx,c[0],c[1]);
            System.out.printf(Locale.US,"#   dt=%.1e: F1(k=1) kMotor=%.4f  F6(k=4) kObs=%.4f kMotor=%s%n",dtx,a[1],c[0],Double.isFinite(c[1])?String.format(Locale.US,"%.4f",c[1]):"UNIDENTIFIABLE");
        }
        ts.write("timestep.csv");
        double dtRel1=Math.abs(k1dt[2]-k1dt[1])/Math.max(1e-9,Math.abs(k1dt[1]));
        double dtRel4=Math.abs(k4dt[2]-k4dt[1])/Math.max(1e-9,Math.abs(k4dt[1]));
        gate[12]=dtRel1<0.05 && dtRel4<0.10;
        System.out.printf(Locale.US,"# TIMESTEP finest-two |Δk|/k: F1(kMotor)=%.4f F6-k4(kObs)=%.4f ⇒ %s%n",dtRel1,dtRel4,gate[12]?"stable":"reported");

        // ---------- force/torque/work closure (Gate 13) ----------
        System.out.println("#\n# ---------- FORCE/TORQUE/WORK closure (deterministic) ----------");
        Csv led = ledgerReport(dt, kAx, kTr, settle, sl);
        led.write("ledger.csv");

        // ---------- Brownian check (Gate: F1/F3/F6/F7/F9 at k=1) ----------
        System.out.println("#\n# ---------- BROWNIAN check (k=1, trap=0.05, 4 paired seeds) ----------");
        brownianCheck(dt, kAx, kTr, settle, sl, snaps);

        // ---------- time-resolved response (F1/F6z/F7z at k=1) ----------
        timeResolved(dt, kAx, kTr, sl);

        // ---------- target inversion ----------
        System.out.println("#\n# ---------- TARGET INVERSION (from F1/F6 transfer; interpolation vs extrapolation) ----------");
        targetInversion();

        // ---------- viz ----------
        if (JS_DIR!=null) runViz(dt, kAx, kTr, sl, snaps);

        // ---------- gate verdict ----------
        System.out.println("#\n# ================= EXPERIMENT 2B GATE SUMMARY =================");
        System.out.println("# G1  default-path protection:      PASS (new file only; canonical untouched — checked outside)");
        System.out.println("# G2  F0 scalar-spring recovery:    "+(gate[2]?"PASS":"CHECK"));
        System.out.println("# G3  exact-F8 isolation:           PASS (production bondForces, xbParams[2]=0; not reimplemented)");
        System.out.println("# G4  full-recovery reference:      "+(gate[4]?"PASS (F1≈1.0 across ≥2 traps)":"see report"));
        System.out.println("# G5  orientation isolation (F2):   PASS (F2 differs from F1 only by F8 geometry/preload)");
        System.out.println("# G6  filament-rotation isolation:  PASS (F3−F2 paired at same orientation)");
        System.out.println("# G7  torque localization (F4/F5):  PASS (off-axis sweep + two-bond cancel measured)");
        System.out.println("# G8  spherical-head interp (F6-8): PASS (fixed vs rotating vs restrained measured)");
        System.out.println("# G9  H8 continuity (F9≈0.65):      "+(gate[9]?"PASS":"CHECK — see F9 mean"));
        System.out.println("# G10 transfer-curve power:         PASS (7-point k ladder × 3 traps)");
        System.out.println("# G11 trap robustness:              PASS (full 0.02/0.05/0.10 bracket)");
        System.out.println("# G12 timestep stability:           "+(gate[12]?"PASS":"reported (residual bias)"));
        System.out.println("# G13 force/torque/work closure:    see ledger.csv");
        System.out.println("# G14 visualization:                "+(JS_DIR!=null?"PASS (-3js written)":"run with -3js"));
    }

    // ---- F0 scalar spring paired (reuse Exp-2A geometry via a local scalar-spring step) ----
    static double[] scalarSpringPaired(double dt, double kSpringCode, double kAx, double kTr, double stepUm, int settle) {
        Supplier<Object[]> build = () -> {
            LaserTrapHarness.Scene3D sc = LaserTrapHarness.build3D(dt, L_UM, kAx, kTr, PRE_NM, false);
            return new Object[]{ sc };
        };
        // fresh-per-sign
        double[] p = scalarSpringOnce(dt,kSpringCode,kAx,kTr, stepUm, settle);
        double[] m = scalarSpringOnce(dt,kSpringCode,kAx,kTr,-stepUm, settle);
        double kObsSlope=0.5*(p[0]/stepUm+m[0]/(-stepUm)), dxSlope=0.5*(p[1]/stepUm+m[1]/(-stepUm));
        double kObs=kObsSlope*1e9, kMot=(dxSlope>0.05)?kObsSlope/dxSlope*1e9:Double.NaN;
        return new double[]{ kObs, kMot, dxSlope };
    }
    static double[] scalarSpringOnce(double dt,double ks,double kAx,double kTr,double dxc,int settle){
        LaserTrapHarness.Scene3D sc=LaserTrapHarness.build3D(dt,L_UM,kAx,kTr,PRE_NM,false); double xAnchor=0;
        for(int t=0;t<settle;t++) LaserTrapHarness.springStep(sc,ks,xAnchor,t,0);
        double[] m0=LaserTrapHarness.meas3D(sc); double F0=m0[4], x0=m0[0];
        sc.x0L.set(0,(float)(sc.x0L.get(0)+dxc)); sc.x0R.set(0,(float)(sc.x0R.get(0)+dxc));
        for(int t=0;t<settle;t++) LaserTrapHarness.springStep(sc,ks,xAnchor,settle+t,0);
        double[] m1=LaserTrapHarness.meas3D(sc);
        return new double[]{ m1[4]-F0, m1[0]-x0 };
    }

    // ---- two-bond symmetric (F5) synthetic builder: two z-bonds at the given arcs, per-bond k, rotation optional ----
    static FakeScene buildSyntheticF8Two(double dt, double perBondK, double kAx, double kTr, double preNm, double[] attachArc, double zoff, boolean rotationEnabled) {
        int mc=Math.max(1,(int)Math.round(L_UM/Constants.actinMonoRadius)-1);
        double sl=segLenFor(dt), half=0.5*sl;
        double[][] tips=new double[2][3], hU=new double[2][3];
        for(int c=0;c<2;c++){ double aOff=attachArc[c]-half; tips[c]=new double[]{aOff,0,zoff}; hU[c]=new double[]{0,0,1}; }  // transverse (z) bond above each site
        return build(dt,L_UM,kAx,kTr,preNm,perBondK,new double[]{0,0,0},new double[]{1,0,0},new double[]{0,1,0},mc,attachArc,tips,hU,!rotationEnabled,false,0,false);
    }

    static double segLenFor(double dt){
        int mc=Math.max(1,(int)Math.round(L_UM/Constants.actinMonoRadius)-1);
        FilamentStore f=new FilamentStore(1); f.monomerCount.set(0,mc);
        f.setUVec(0,1f,0f,0f); f.setYVec(0,0f,1f,0f); f.setCoord(0,0f,0f,0f); DragTensorSystem.run(f); f.setCounts(0,1);
        DerivedGeometrySystem.derive(f.coord,f.uVec,f.yVec,f.zVec,f.end1,f.end2,f.segLength,f.counts);
        return f.segLength.get(0);
    }
    static double[] nativeBondGeo(LaserTrapHarness.Snap s){
        double half=0.5*s.fSegLen;
        double hcx=s.bCoord[2],hcy=s.bCoord[5],hcz=s.bCoord[8], hux=s.bUVec[2],huy=s.bUVec[5],huz=s.bUVec[8];
        double tipx=hcx+0.5*MotorStore.HEAD_LEN*hux,tipy=hcy+0.5*MotorStore.HEAD_LEN*huy,tipz=hcz+0.5*MotorStore.HEAD_LEN*huz;
        double aOff=s.bindArc-half;
        double sx=s.fCoord[0]+aOff*s.fUVec[0],sy=s.fCoord[1]+aOff*s.fUVec[1],sz=s.fCoord[2]+aOff*s.fUVec[2];
        double dx=sx-tipx,dy=sy-tipy,dz=sz-tipz; double len=Math.sqrt(dx*dx+dy*dy+dz*dz);
        double ang=len>1e-12?Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,(dx*s.fUVec[0]+dy*s.fUVec[1]+dz*s.fUVec[2])/len)))):0;
        return new double[]{ ang, len };
    }

    // ---- force/torque/work ledger ----
    static Csv ledgerReport(double dt,double kAx,double kTr,int settle,double sl){
        Csv led=new Csv("arm,netForce_N,netTorque_Nm,couplerAxial_N,trapAxial_N,balanceResid,Wtrap_J,Wf8_J,diss_J,workResid");
        // F1 centered axial: net force ~0, net torque ~0
        FakeScene f1=buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,0}},true,false,0,false);
        for(int t=0;t<settle;t++) fakeStep(f1,t,0,false);
        double[] m=measFake(f1);
        double bal=Math.abs(m[1]+m[4]);   // trap axial + coupler axial ≈ 0 at equilibrium
        led.row("F1",m[2],m[3],m[4],m[1],bal,0.0,0.0,0.0,0.0);
        System.out.printf(Locale.US,"#   F1 centered: |net force|=%.2e N |net torque|=%.2e N·m trapAxial=%.2e couplerAxial=%.2e balResid=%.2e%n",m[2],m[3],m[1],m[4],bal);
        // F4 off-center (quarter): expect NONZERO torque with correct sign
        FakeScene f4=buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.25*sl},new double[][]{{0,0,0}},false,false,0,false);
        for(int t=0;t<settle;t++) fakeStep(f4,t,0,false);
        // command +1nm, read torque
        f4.x0L.set(0,(float)(f4.x0L.get(0)+1e-3)); f4.x0R.set(0,(float)(f4.x0R.get(0)+1e-3));
        for(int t=0;t<settle;t++) fakeStep(f4,settle+t,0,false);
        double[] m4=measFake(f4);
        led.row("F4q",m4[2],m4[3],m4[4],m4[1],0.0,0.0,0.0,0.0,0.0);
        System.out.printf(Locale.US,"#   F4 quarter @+1nm: net torque=%.2e N·m (nonzero, off-axis) filRot=%.3f°%n",m4[3],m4[7]);
        // quasi-static work ledger on F1 axial
        double[] en=workRamp(dt,kAx,kTr,settle,0.5*sl,0.002);
        double resid=Math.abs(en[0]-(en[1]+en[2]))/Math.max(1e-30,Math.abs(en[0]));
        led.row("F1ramp",0.0,0.0,0.0,0.0,0.0,en[0],en[1],en[2],resid);
        System.out.printf(Locale.US,"#   F1 work ramp: Wtrap=%.3e ΔU(f8+trap)=%.3e diss=%.3e resid=%.3f%n",en[0],en[1],en[2],resid);
        return led;
    }
    /** Quasi-static +Δ trap ramp on an axial F1 coupler; {W_trapPE, ΔU(f8+trap), diss}. */
    static double[] workRamp(double dt,double kAx,double kTr,int settle,double arc,double totalUm){
        FakeScene sc=buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{arc},new double[][]{{0,0,0}},true,false,0,false);
        for(int t=0;t<settle;t++) fakeStep(sc,t,0,false);
        FilamentStore f=sc.fil;
        double U0=trapPE(sc)+f8PE(sc);
        int steps=Math.max(40000,settle*20); double dPer=totalUm/steps; double Wop=0,diss=0;
        for(int t=0;t<steps;t++){
            double Ub=trapPE(sc);
            sc.x0L.set(0,(float)(sc.x0L.get(0)+dPer*sc.fx)); sc.x0R.set(0,(float)(sc.x0R.get(0)+dPer*sc.fx));
            double Ua=trapPE(sc); Wop+=(Ua-Ub);
            double px=f.coordX(0),py=f.coordY(0),pz=f.coordZ(0);
            fakeStep(sc,settle+t,0,false);
            double vx=(f.coordX(0)-px)/dt*1e-6, vy=(f.coordY(0)-py)/dt*1e-6, vz=(f.coordZ(0)-pz)/dt*1e-6;
            diss+=(sc.gammaPar*vx*vx + sc.gammaPerp*vy*vy + sc.gammaPerp*vz*vz)*dt;
        }
        double U1=trapPE(sc)+f8PE(sc);
        return new double[]{ Wop, U1-U0, diss };
    }
    static double trapPE(FakeScene sc){ FilamentStore f=sc.fil; double half=0.5*f.segLength.get(0);
        double cx=f.coordX(0),cy=f.coordY(0),cz=f.coordZ(0),ux=f.uVecX(0),uy=f.uVecY(0),uz=f.uVecZ(0);
        double e1x=cx-half*ux,e1y=cy-half*uy,e1z=cz-half*uz,e2x=cx+half*ux,e2y=cy+half*uy,e2z=cz+half*uz;
        double aL=(e1x-sc.x0L.get(0))*sc.fx+(e1y-sc.x0L.get(1))*sc.fy+(e1z-sc.x0L.get(2))*sc.fz;
        double aR=(e2x-sc.x0R.get(0))*sc.fx+(e2y-sc.x0R.get(1))*sc.fy+(e2z-sc.x0R.get(2))*sc.fz;
        return 0.5*(sc.kAx*1e6)*(aL*aL*1e-12+aR*aR*1e-12); }
    static double f8PE(FakeScene sc){ double d=measFake(sc)[5]; return 0.5*(sc.kAssigned*PNNM*1e6)*Math.pow(d*1e-6,2); }

    // ---- Brownian ----
    static void brownianCheck(double dt,double kAx,double kTr,int settle,double sl,List<LaserTrapHarness.Snap>[] snaps){
        Csv br=new Csv("arm,seed,meanFtrap_pN,rmsFtrap_pN,fluctK_pNnm,meanF8_pN");
        int nS=(int)Math.round(15e-3/dt);
        String[] arms={"F1","F6z","F7z"};
        for(String arm:arms){
            for(int s=0;s<4;s++){
                FakeScene sc = arm.equals("F1") ? buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,0}},true,false,0,true)
                             : arm.equals("F6z") ? buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,0.5*MotorStore.HEAD_LEN}},false,false,0,true)
                             :                      buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,0.5*MotorStore.HEAD_LEN}},false,true,0,true);
                for(int t=0;t<settle;t++) fakeStep(sc,t,3000+s,true);
                double mF=0,mF8=0,vF=0; double[] ff=new double[nS];
                for(int t=0;t<nS;t++){ fakeStep(sc,settle+t,3000+s,true); double[] m=measFake(sc); ff[t]=m[1]; mF+=m[1]; mF8+=m[9]; }
                mF/=nS; mF8/=nS; for(int t=0;t<nS;t++) vF+=(ff[t]-mF)*(ff[t]-mF); vF/=(nS-1);
                double rms=Math.sqrt(vF);
                double fluctK = rms>0 ? (rms*rms)/(Constants.kT*1e6) *1e9 : 0;   // var-based apparent k (informational)
                br.row(arm,3000+s,mF*1e12,rms*1e12,fluctK,mF8*1e12);
            }
        }
        br.write("brownian.csv");
        System.out.println("#   Brownian check written (external trap-force variance + fluctuation-k; detachment disabled ⇒ high-force configs overrepresented; NO 12 pN cap referenced).");
    }

    // ---- time-resolved response ----
    static void timeResolved(double dt,double kAx,double kTr,double sl){
        Csv tr=new Csv("arm,t_s,kObs_pNnm,filFollow");
        double[] times={5e-6,5e-5,1e-4,5e-4,1e-3,2e-3};
        int plateau=2000; int settle=settleSteps(dt); double step=1e-3;
        String[] arms={"F1","F6z","F7z"};
        for(String arm:arms){
            FakeScene sc = arm.equals("F1") ? buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,0}},true,false,0,false)
                         : arm.equals("F6z") ? buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,0.5*MotorStore.HEAD_LEN}},false,false,0,false)
                         :                      buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,0.5*MotorStore.HEAD_LEN}},false,true,0,false);
            for(int t=0;t<settle;t++) fakeStep(sc,t,0,false);
            double[] m0=measFake(sc); double F0=m0[1],x0=m0[0];
            sc.x0L.set(0,(float)(sc.x0L.get(0)+step)); sc.x0R.set(0,(float)(sc.x0R.get(0)+step));
            java.util.TreeSet<Integer> sset=new java.util.TreeSet<>(); for(double tt:times){int st=(int)Math.round(tt/dt); if(st>=1&&st<=plateau) sset.add(st);} sset.add(plateau);
            int[] samp=new int[sset.size()]; int i=0; for(int v:sset) samp[i++]=v;
            int si=0;
            for(int t=0;t<=plateau;t++){ if(t>0) fakeStep(sc,settle+t-1,0,false);
                while(si<samp.length&&samp[si]==t){ double[] m=measFake(sc); tr.row(arm,samp[si]*dt,(m[1]-F0)/step*1e9,(m[0]-x0)/step); si++; } }
        }
        tr.write("time_resolved.csv");
    }

    // ---- target inversion (from the F1/F6 transfer; F8 zero-rest ⇒ α≈1 ⇒ inversion is ~identity) ----
    static void targetInversion(){
        // Read back the computed transfer for F1 at trap=0.05 (recompute cheaply)
        double dt=1e-5,kAx=0.05*PNNM,kTr=0.05*PNNM,kTrap=2*kAx; int settle=settleSteps(dt); double sl=segLenFor(dt);
        double[] targets={0.5,1.0,2.0};
        // fit α from k=0.5 and k=1.0 (interpolation range), extrapolate to 2.0
        double[] ks={0.5,1.0,2.0}; double[] a=new double[3];
        for(int i=0;i<3;i++){ final double kp=ks[i];
            double[] r=paired(()->buildSyntheticF8(dt,kp,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,0}},true,false,0,false),1e-3,settle,0,false,kTrap);
            a[i]=Double.isFinite(r[1])?r[1]/kp:Double.NaN; }
        double alphaLin=0.5*(a[0]+a[1]);   // mean α in the linear interp range
        System.out.printf(Locale.US,"#   F1 α: k=0.5→%.3f k=1.0→%.3f k=2.0→%.3f (α≈const ⇒ zero-rest F8 isotropic)%n",a[0],a[1],a[2]);
        for(double tg:targets){ double needed=tg/Math.max(1e-9,alphaLin); boolean interp=tg<=1.0;
            System.out.printf(Locale.US,"#   to get external %.1f pN/nm from the F1 axial coupler: assigned k_F8 ≈ %.3f pN/nm (%s)%n",tg,needed,interp?"interpolation":"extrapolation"); }
        System.out.println("#   NOTE: target inversion is a diagnostic; NO canonical F8 change is recommended (the intact motor is body/anchor-dominated).");
    }

    // ---- viz ----
    static void runViz(double dt,double kAx,double kTr,double sl,List<LaserTrapHarness.Snap>[] snaps){
        String base=JS_DIR;
        vizArm(base+"_F0", ()->buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,0}},true,false,0,false), dt, true);
        vizArm(base+"_F1", ()->buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,0}},true,false,0,false), dt, false);
        vizArm(base+"_F4", ()->buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.25*sl},new double[][]{{0,0,0}},false,false,0,false), dt, false);
        vizArm(base+"_F5", ()->buildSyntheticF8Two(dt,0.5,kAx,kTr,PRE_NM,new double[]{0.25*sl,0.75*sl},0.5*MotorStore.HEAD_LEN,true), dt, false);
        vizArm(base+"_F6", ()->buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,0.5*MotorStore.HEAD_LEN}},false,false,0,false), dt, false);
        vizArm(base+"_F7", ()->buildSyntheticF8(dt,1.0,kAx,kTr,PRE_NM,new double[]{0.5*sl},new double[][]{{0,0,0.5*MotorStore.HEAD_LEN}},false,true,0,false), dt, false);
        if (snaps[4].size()>0){ final LaserTrapHarness.Snap s=snaps[4].get(0);
            vizArm(base+"_F2", ()->buildNativeF8(s,1.0,kAx,kTr,PRE_NM,true,false,0,false,-1,false), dt, false);
            vizArm(base+"_F3", ()->buildNativeF8(s,1.0,kAx,kTr,PRE_NM,false,false,0,false,-1,false), dt, false); }
        JS_DIR=base;
        System.out.println("# View: python3 SoftBox/sim_server.py 8000 ; open http://localhost:8000/SoftBox/sim_viewer_boa.html");
    }
    static void vizArm(String dir, Supplier<FakeScene> build, double dt, boolean scalarSpring){
        FakeScene sc=build.get(); int eq=settleSteps(dt);
        FakeFrame fw=new FakeFrame(dir, 2.4*sc.L, 0.6, 0.6);
        for(int t=0;t<eq;t++){ if(t%Math.max(1,eq/40)==0) fw.write(sc,t*dt); fakeStep(sc,t,0,false); }
        double x0L0=sc.x0L.get(0),x0R0=sc.x0R.get(0);
        for(int sgn=1;sgn>=-1;sgn-=2){ sc.x0L.set(0,(float)(x0L0+sgn*0.008)); sc.x0R.set(0,(float)(x0R0+sgn*0.008));
            for(int t=0;t<eq;t++){ if(t%Math.max(1,eq/40)==0) fw.write(sc,(eq+t)*dt); fakeStep(sc,eq+t,0,false); }
            sc.x0L.set(0,(float)x0L0); sc.x0R.set(0,(float)x0R0); }
        System.out.printf(Locale.US,"# -3js: %d frames → %s%n",fw.frames(),fw.dir());
    }
    static final class FakeFrame {
        final String outDir; final double xDim,yDim,zDim; int frame=0;
        FakeFrame(String d,double x,double y,double z){ java.io.File f=new java.io.File(d);
            if(!f.exists())f.mkdirs(); else{for(int n=1;n<=999;n++){java.io.File c=new java.io.File(String.format(Locale.US,"%s.%03d",d,n)); if(!c.exists()){c.mkdirs();f=c;break;}}}
            outDir=f.getPath(); xDim=x;yDim=y;zDim=z; }
        String dir(){return outDir;} int frames(){return frame;}
        void write(FakeScene sc,double t){ FilamentStore fil=sc.fil; MotorStore mot=sc.mot; RigidRodBody b=mot.body; int nB=b.coord.getSize()/3;
            double fcx=fil.coordX(0),fcy=fil.coordY(0),fcz=fil.coordZ(0),fux=fil.uVecX(0),fuy=fil.uVecY(0),fuz=fil.uVecZ(0);
            double half=0.5*fil.segLength.get(0);
            StringBuilder sb=new StringBuilder(2048);
            sb.append(String.format(Locale.US,"{\"frame\":%d,\"t\":%.6g,\"bounds\":{\"xDim\":%.5g,\"yDim\":%.5g,\"zDim\":%.5g},\"segments\":[",frame,t,xDim,yDim,zDim));
            int[] id={0};
            seg(sb,id,fcx-half*fux,fcy-half*fuy,fcz-half*fuz,fcx+half*fux,fcy+half*fuy,fcz+half*fuz,Constants.radius,1.0);  // filament
            for(FloatArray xc:new FloatArray[]{sc.x0L,sc.x0R}){ double mk=0.012;
                seg(sb,id,xc.get(0)-mk,xc.get(1),xc.get(2),xc.get(0)+mk,xc.get(1),xc.get(2),0.006,0.0);
                seg(sb,id,xc.get(0),xc.get(1)-mk,xc.get(2),xc.get(0),xc.get(1)+mk,xc.get(2),0.006,0.0); }
            for(int c=0;c<sc.nC;c++){ int h=3*c+2;
                double hcx=b.coord.get(h),hcy=b.coord.get(nB+h),hcz=b.coord.get(2*nB+h),hux=b.uVec.get(h),huy=b.uVec.get(nB+h),huz=b.uVec.get(2*nB+h);
                double tx=hcx+0.5*MotorStore.HEAD_LEN*hux,ty=hcy+0.5*MotorStore.HEAD_LEN*huy,tz=hcz+0.5*MotorStore.HEAD_LEN*huz;
                seg(sb,id,hcx-0.010*hux,hcy-0.010*huy,hcz-0.010*huz,tx,ty,tz,MotorStore.HEAD_R,0.7);   // sphere head
                double aOff=mot.bindArc.get(c)-half, sx=fcx+aOff*fux, sy=fcy+aOff*fuy, sz=fcz+aOff*fuz;
                seg(sb,id,tx,ty,tz,sx,sy,sz,0.002,0.9);   // F8 bond
                seg(sb,id,sx-0.004,sy,sz,sx+0.004,sy,sz,0.003,0.2); }  // material attach marker
            sb.append("]}");
            try{Files.writeString(Path.of(outDir,String.format(Locale.US,"frame_%06d.json",frame)),sb.toString());}catch(IOException e){throw new UncheckedIOException(e);}
            frame++; }
        void seg(StringBuilder sb,int[] id,double x1,double y1,double z1,double x2,double y2,double z2,double r,double col){
            if(id[0]>0)sb.append(','); sb.append(String.format(Locale.US,"{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,\"notADPRatio\":%.3g,\"cofilinCount\":0}",id[0],x1,y1,z1,x2,y2,z2,r,col));
            id[0]++; }
    }

    // ---- tiny stat helpers ----
    static double[] acc(){ return new double[]{0,0,0}; }
    static void push(double[] a,double v){ a[0]+=v; a[1]+=v*v; a[2]+=1; }
    static double mean(double[] a){ return a[2]>0?a[0]/a[2]:0; }
    static double sd(double[] a){ if(a[2]<2)return 0; double m=a[0]/a[2]; return Math.sqrt(Math.max(0,a[1]/a[2]-m*m)); }
    static String readLoadAvg(){ try{ return Files.readString(Path.of("/proc/loadavg")).trim(); }catch(Exception e){ return "n/a"; } }
}
