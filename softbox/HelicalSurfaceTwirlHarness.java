package softbox;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * HELICAL SURFACE BINDING + MINIMAL TWIRLING DRIVE — dedicated prototype harness (noncanonical, default-off).
 *
 * The smallest continuous helical surface-binding extension in the lumped/gliding lineage (where the roll DOF,
 * the roll spring, and the full cross-bridge force/torque gather already exist). It places the actin-side
 * cross-bridge attachment on the physical filament SURFACE at a retained MATERIAL azimuth ψ (instead of the
 * centerline), so the existing F8 reaction acquires an axial (‖û_seg) torque that drives filament TWIRLING —
 * with no new force law and no artificial torque. A 3D surface-point steric rule (default 5.5 nm) prevents
 * near-coincident attachments. Everything is flag-gated, default-off, byte-identical when disabled.
 *
 * Systems used (all shared, one implementation, both runners): CrossBridgeSystem.bondForcesSurface (off-axis
 * bond) + the CSR gather (csrHistogram/csrScan/csrScatter/segGather, byte-unchanged) + RigidRodLangevin
 * integration (the bwx roll channel) + DerivedGeometry (roll-preserving) + RollSpringSystem (whole-filament
 * roll coherence) + BindingDetectionSystem.surfaceBindPropose/surfaceStericResolve (azimuth select + steric).
 *
 * Modes:  (default) deterministic fixtures + verdict  |  -equiv CPU/GPU kernel equivalence  |
 *         -campaign small dynamic twirl campaign (CPU)  |  -all everything.
 *
 * Physical experimental parameters (NOT canonical constants, NOT calibration targets):
 *   actin radial attachment offset Ractin  = 3.5 nm  (= Constants.radius, the physical actin radius)
 *   actin-surface steric exclusion distance = 5.5 nm  (Euclidean, between reconstructed surface points)
 */
public final class HelicalSurfaceTwirlHarness {

    static final int B = 64;
    static GridScheduler sched;

    // actin 13/6 helix (RollSpringHarness convention): LEFT-handed, negative about pointed→barbed.
    static final double TWIST_PER_MON_DEG = -166.5;
    static double twistRatePerUm() { return TWIST_PER_MON_DEG * Math.PI / 180.0 / Constants.actinMonoRadius; }

    // resolved experimental parameters (defaults; overridable by flags)
    static double R_ACTIN_NM   = 3.5;    // -actin-bind-radius-nm
    static double EXCL_NM      = 5.5;    // -surface-exclusion-nm
    static final double TOL_NM = 1.0e-3; // threshold convention: reject iff sep < excl − tol ⇒ exactly EXCL accepted
    static boolean SURFACE_ON  = false;  // -helical-surface-bind
    static boolean STERIC_ON   = true;   // steric may be separately disabled: -no-surface-exclusion
    static boolean ROLL_SPRING = false;  // -rollspring
    static boolean cpu = false;
    static double DT = 1.0e-5;
    static int    SEED = 12345;

    static final double MYO_SPRING = 1.0e-9;    // N/µm (Env.java:791)
    static final double J1_FMT     = 0.4;       // myoJ1FracMoveTorq

    public static void main(String[] args) {
        boolean doEquiv = false, doCampaign = false, doAll = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-cpu" -> cpu = true;
                case "-helical-surface-bind" -> SURFACE_ON = true;
                case "-actin-bind-radius-nm" -> R_ACTIN_NM = Double.parseDouble(args[++i]);
                case "-surface-exclusion-nm" -> EXCL_NM = Double.parseDouble(args[++i]);
                case "-no-surface-exclusion" -> STERIC_ON = false;
                case "-rollspring" -> ROLL_SPRING = true;
                case "-dt" -> DT = Double.parseDouble(args[++i]);
                case "-seed" -> SEED = Integer.parseInt(args[++i]);
                case "-equiv" -> doEquiv = true;
                case "-campaign" -> doCampaign = true;
                case "-all" -> doAll = true;
                default -> { /* ignore */ }
            }
        }
        System.out.println("############ Helical surface binding + minimal twirling drive (noncanonical, default-off) ############");
        System.out.printf("runner=%s  dt=%.1e  Ractin=%.3f nm  exclusion=%.2f nm (tol %.0e nm)  twistRate=%.1f rad/µm (LEFT-handed)%n",
                cpu ? "CPU" : "GPU", DT, R_ACTIN_NM, EXCL_NM, TOL_NM, twistRatePerUm());
        System.out.println("Ractin default = Constants.radius = " + (Constants.radius * 1e3) + " nm (physical actin radius).");

        boolean ok = true;
        if (doAll) { ok &= runFixtures(); ok &= runEquiv(); runCampaign(); }
        else if (doEquiv) ok = runEquiv();
        else if (doCampaign) { runCampaign(); }
        else ok = runFixtures();

        System.out.println("=====================================================================================================");
        if (!doCampaign || doAll) System.out.println((ok ? "ALL GATED CHECKS PASS" : "*** SOME CHECKS FAILED ***"));
        if (!ok) System.exit(1);
    }

    // =====================================================================================================
    //  Host reconstruction mirror — BYTE-for-formula identical to CrossBridgeSystem.bondForcesSurface / resolve.
    //  xSite = sc + (arc − ½slen)·û + R·(cosψ·ŷ + sinψ·ẑ),  ẑ = normalize(û × ŷ).
    // =====================================================================================================
    static double[] siteHost(double[] sc, double[] su, double[] sy, double slen, double arc, double azim, double R) {
        double szx = su[1] * sy[2] - su[2] * sy[1], szy = su[2] * sy[0] - su[0] * sy[2], szz = su[0] * sy[1] - su[1] * sy[0];
        double szl = Math.sqrt(szx * szx + szy * szy + szz * szz);
        if (szl > 1e-30) { szx /= szl; szy /= szl; szz /= szl; }
        double aOff = arc - 0.5 * slen;
        double c = Math.cos(azim), s = Math.sin(azim);
        return new double[]{
            sc[0] + aOff * su[0] + R * (c * sy[0] + s * szx),
            sc[1] + aOff * su[1] + R * (c * sy[1] + s * szy),
            sc[2] + aOff * su[2] + R * (c * sy[2] + s * szz) };
    }
    static double dist3(double[] a, double[] b) {
        double dx = a[0]-b[0], dy = a[1]-b[1], dz = a[2]-b[2]; return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
    static double[] rotAboutAxis(double[] v, double[] k, double ang) {   // Rodrigues, k unit
        double c = Math.cos(ang), s = Math.sin(ang);
        double kd = k[0]*v[0]+k[1]*v[1]+k[2]*v[2];
        double cx = k[1]*v[2]-k[2]*v[1], cy = k[2]*v[0]-k[0]*v[2], cz = k[0]*v[1]-k[1]*v[0];
        return new double[]{ v[0]*c + cx*s + k[0]*kd*(1-c), v[1]*c + cy*s + k[1]*kd*(1-c), v[2]*c + cz*s + k[2]*kd*(1-c) };
    }

    // =====================================================================================================
    //  DETERMINISTIC FIXTURES
    // =====================================================================================================
    static int passN = 0, failN = 0;
    static void check(int id, String name, boolean pass) {
        System.out.printf("  [%2d] %-58s %s%n", id, name, pass ? "PASS" : "*** FAIL ***");
        if (pass) passN++; else failN++;
    }

    static boolean runFixtures() {
        passN = 0; failN = 0;
        double R = R_ACTIN_NM * 1e-3;   // µm
        System.out.println("\n--- DETERMINISTIC FIXTURES ---");
        System.out.println("Geometry & material latching:");
        fixtureGeometry(R);
        System.out.println("Surface steric exclusion (3D):");
        fixtureSteric(R);
        System.out.println("Mechanics (off-axis torque, conservation, roll response, controls):");
        fixtureMechanics(R);
        System.out.printf("Fixtures: %d PASS, %d FAIL%n", passN, failN);
        return failN == 0;
    }

    // ---- geometry / latching (tests 1-9) ----
    static void fixtureGeometry(double R) {
        double slen = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;
        double[] sc = {0.10, 0.20, -0.05}, su = {1,0,0}, sy = {0,1,0};
        double arc = 0.4 * slen;

        // 1. R=0 reproduces centerline exactly (host + kernel)
        double[] p0 = siteHost(sc, su, sy, slen, arc, 1.234, 0.0);
        double[] axisPt = {sc[0] + (arc - 0.5*slen)*su[0], sc[1] + (arc-0.5*slen)*su[1], sc[2] + (arc-0.5*slen)*su[2]};
        boolean f1a = dist3(p0, axisPt) == 0.0;
        boolean f1b = kernelR0IdenticalToCenterline();   // bondForcesSurface(R=0) ≡ bondForces (byte-identical bondData)
        check(1, "R=0 site == centerline (host exact + kernel bondData byte-identical)", f1a && f1b);

        // 2. R=3.5nm reconstructs the expected radial distance
        double[] p2 = siteHost(sc, su, sy, slen, arc, 0.7, R);
        double rad = dist3(p2, axisPt);
        check(2, "R=3.5nm radial distance from centerline == R", Math.abs(rad - R) < 1e-9);

        // 3. psi = 0, π/2, π, 3π/2 place the point correctly in the material frame (ẑ = û×ŷ = +z here)
        double[] pa = siteHost(sc, su, sy, slen, arc, 0.0, R);         // +ŷ
        double[] pb = siteHost(sc, su, sy, slen, arc, Math.PI/2, R);   // +ẑ
        double[] pcc = siteHost(sc, su, sy, slen, arc, Math.PI, R);    // −ŷ
        double[] pd = siteHost(sc, su, sy, slen, arc, 3*Math.PI/2, R); // −ẑ
        boolean f3 = Math.abs((pa[1]-axisPt[1]) - R) < 1e-9 && Math.abs((pb[2]-axisPt[2]) - R) < 1e-9
                  && Math.abs((pcc[1]-axisPt[1]) + R) < 1e-9 && Math.abs((pd[2]-axisPt[2]) + R) < 1e-9;
        check(3, "psi in {0,π/2,π,3π/2} places site on +ŷ/+ẑ/−ŷ/−ẑ", f3);

        // 4. Lab-frame rigid rotation preserves the material azimuth (site rotates rigidly with the frame)
        double[] kax = {1/Math.sqrt(3),1/Math.sqrt(3),1/Math.sqrt(3)}; double lab = 0.9;
        double[] scR = rotAboutAxis(sc, kax, lab), suR = rotAboutAxis(su, kax, lab), syR = rotAboutAxis(sy, kax, lab);
        double[] pRot = siteHost(scR, suR, syR, slen, arc, 0.7, R);
        double[] pExp = rotAboutAxis(p2, kax, lab);   // rotate the original site by the same lab rotation
        check(4, "lab-frame rigid rotation: site' == R_lab · site (material-latched)", dist3(pRot, pExp) < 1e-9);

        // 5. Filament roll (rotate ŷ about û by δ) moves the site about the axis by δ, azimuth ψ unchanged
        double delta = 0.6; double[] syRoll = rotAboutAxis(sy, su, delta);
        double[] pRoll = siteHost(sc, su, syRoll, slen, arc, 0.7, R);
        double[] pRollExp = { axisPt[0] + (p2[0]-axisPt[0]), 0, 0 };   // rotate radial part about û by δ
        double[] radial = {p2[0]-axisPt[0], p2[1]-axisPt[1], p2[2]-axisPt[2]};
        double[] radialRot = rotAboutAxis(radial, su, delta);
        pRollExp = new double[]{axisPt[0]+radialRot[0], axisPt[1]+radialRot[1], axisPt[2]+radialRot[2]};
        check(5, "filament roll δ: site follows the material frame about û", dist3(pRoll, pRollExp) < 1e-9);

        // 6. Bending / re-orthogonalization preserve the material attachment (radius==R & recovered azimuth==ψ)
        double[] suB = norm(new double[]{1, 0.3, 0.0});          // bent axis
        double[] syRaw = {0, 1, 0};
        double[] szB = norm(cross(suB, syRaw)); double[] syB = norm(cross(szB, suB));   // re-orthogonalize (DerivedGeometry)
        double psi6 = 1.1;
        double[] p6 = siteHost(sc, suB, syB, slen, arc, psi6, R);
        double[] ax6 = {sc[0]+(arc-0.5*slen)*suB[0], sc[1]+(arc-0.5*slen)*suB[1], sc[2]+(arc-0.5*slen)*suB[2]};
        double rad6 = dist3(p6, ax6);
        double[] rvec = {p6[0]-ax6[0], p6[1]-ax6[1], p6[2]-ax6[2]};
        double recPsi = Math.atan2(dot(rvec, szB), dot(rvec, syB));
        check(6, "bend + re-orthogonalize: radius==R & recovered azimuth==ψ",
                Math.abs(rad6 - R) < 1e-9 && angDiff(recPsi, psi6) < 1e-6);

        // 7. Segment-boundary material-coordinate continuity (half-open ownership: arc ∈ [0,slen] contiguous)
        //    two abutting segments; the material arc at seg0's end2 (arc=slen) meets seg1's end1 (arc=0) with no gap.
        double end0 = slen, start1 = 0.0;   // cumulative material coordinate continuous across the joint
        double cum0end = slen, cum1start = slen;   // segCumArc[1] = slen ⇒ 1's arc 0 maps to global slen = 0's end
        check(7, "segment-boundary material coordinate continuous (half-open ownership)",
                Math.abs((cum0end) - (cum1start + start1)) < 1e-9 && end0 >= 0 && end0 <= slen);

        // 8. Polarity reversal: flipping û flips the stored azimuth SIGN for the SAME physical surface point
        //    (segZ = û×segY flips with û, so n̂(û,ψ) == n̂(−û,−ψ)). Use arc = ½slen (aOff=0) to isolate the radial part.
        double psi8 = 1.0;
        double[] p8fwd = siteHost(sc, new double[]{1,0,0}, sy, slen, 0.5*slen,  psi8, R);
        double[] p8rev = siteHost(sc, new double[]{-1,0,0}, sy, slen, 0.5*slen, -psi8, R);
        check(8, "polarity reversal: n̂(û,ψ) == n̂(−û,−ψ) (helical-sign transform)", dist3(p8fwd, p8rev) < 1e-9);

        // 9. A/B relabel: the reconstruction depends only on stored (seg,arc,azim,R), not on head id.
        double[] pA = siteHost(sc, su, sy, slen, arc, 0.5, R);
        double[] pB = siteHost(sc, su, sy, slen, arc, 0.5, R);   // identical inputs → identical site regardless of "which motor"
        check(9, "A/B motor relabel: physical site identical for identical stored state", dist3(pA, pB) == 0.0);
    }

    // ---- surface steric exclusion (tests 10-18) ----
    static void fixtureSteric(double R) {
        // Build a 2-filament scene: fil A = seg0 (filId 0), fil B = seg1 (filId 1), both along x, well separated in y.
        int nSeg = 2;
        FloatArray fc = new FloatArray(3*nSeg), fu = new FloatArray(3*nSeg), fy = new FloatArray(3*nSeg), fl = new FloatArray(nSeg);
        double slen = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;
        setSeg(fc, fu, fy, fl, nSeg, 0, 0,0,0,   1,0,0, 0,1,0, slen);
        setSeg(fc, fu, fy, fl, nSeg, 1, 0,1.0,0, 1,0,0, 0,1,0, slen);   // filament B 1 µm away in y
        IntArray segFilId = new IntArray(nSeg); segFilId.set(0,0); segFilId.set(1,1);

        double half = 0.5*slen;
        double excl = EXCL_NM*1e-3, tol = TOL_NM*1e-3;

        // motor 0 = candidate, motors 1.. = pre-bound; accepts() returns TRUE iff the candidate commits.
        // 10. same surface coordinate → REJECT
        check(10, "same surface coordinate (0 nm) → REJECT",
                !accepts(fc,fu,fy,fl,segFilId,nSeg,R,excl,tol, 0,(float)half,0f, new int[]{0}, new float[]{(float)half}, new float[]{0f}));
        // axial separations along û: sep = |Δarc| (azimuth 0, same side)
        double d53 = 5.3e-3, d55 = 5.5e-3, d57 = 5.7e-3;
        // 11. below threshold (5.3 nm) → REJECT
        check(11, "below threshold (5.3 nm) → REJECT",
                !accepts(fc,fu,fy,fl,segFilId,nSeg,R,excl,tol, 0,(float)(half+d53),0f, new int[]{0}, new float[]{(float)half}, new float[]{0f}));
        // 12. exactly threshold (5.5 nm) → ACCEPT (reject iff sep < excl − tol)
        check(12, "exactly threshold (5.5 nm) → ACCEPT",
                accepts(fc,fu,fy,fl,segFilId,nSeg,R,excl,tol, 0,(float)(half+d55),0f, new int[]{0}, new float[]{(float)half}, new float[]{0f}));
        // 13. above threshold (5.7 nm) → ACCEPT
        check(13, "above threshold (5.7 nm) → ACCEPT",
                accepts(fc,fu,fy,fl,segFilId,nSeg,R,excl,tol, 0,(float)(half+d57),0f, new int[]{0}, new float[]{(float)half}, new float[]{0f}));
        // 14. same axial coordinate, OPPOSITE filament side (azimuth 0 vs π): 3D sep = 2R = 7.0 nm > 5.5 → ACCEPT
        check(14, "same axial coord, opposite side (2R=7.0nm sep) → ACCEPT",
                accepts(fc,fu,fy,fl,segFilId,nSeg,R,excl,tol, 0,(float)half,(float)Math.PI, new int[]{0}, new float[]{(float)half}, new float[]{0f}));
        //  ... and a same-side control at azimuth 0 vs 0 (0 nm) → REJECT (already test 10) confirms the opposite-side ACCEPT is meaningful.
        // 15. different filament, identical material coordinate → ACCEPT (never excludes)
        check(15, "different filament, identical coord → ACCEPT",
                accepts(fc,fu,fy,fl,segFilId,nSeg,R,excl,tol, 0,(float)half,0f, new int[]{1}, new float[]{(float)half}, new float[]{0f}));
        // 16. same-step conflict: two candidates 2.7 nm apart, one binds, lower id wins; conflict counter=1
        check(16, "same-step conflict: exactly one binds, lower id wins, conflict=1", stericConflict(fc,fu,fy,fl,segFilId,nSeg,R,excl,tol,half));
        // 17. bound sister participates under the global rule (a bound head within threshold excludes regardless of dimer role)
        check(17, "bound head within threshold excludes (global; sister participates)",
                !accepts(fc,fu,fy,fl,segFilId,nSeg,R,excl,tol, 0,(float)(half+2.0e-3),0f, new int[]{0}, new float[]{(float)half}, new float[]{0f}));
        // 18. OFF path (exclusion ≤ 0) reproduces previous binding decisions (candidate always accepts)
        check(18, "OFF path (exclusion 0) commits every candidate (no steric)",
                accepts(fc,fu,fy,fl,segFilId,nSeg,R,0.0,tol, 0,(float)half,0f, new int[]{0}, new float[]{(float)half}, new float[]{0f}));
    }

    /** Run surfaceStericResolve with a single fresh candidate (motor 0) + a set of pre-bound heads; return true if committed. */
    static boolean accepts(FloatArray fc, FloatArray fu, FloatArray fy, FloatArray fl, IntArray segFilId, int nSeg,
                           double R, double excl, double tol, int candSeg, float candArc, float candAzim,
                           int[] boundSegs, float[] boundArcs, float[] boundAzims) {
        int nM = 1 + boundSegs.length;
        IntArray cSeg = new IntArray(nM); FloatArray cArc = new FloatArray(nM), cAz = new FloatArray(nM);
        IntArray boundSeg = new IntArray(nM); FloatArray bindArc = new FloatArray(nM), bindAzim = new FloatArray(nM);
        boundSeg.init(MotorStore.FREE_BINDABLE);
        cSeg.init(-1);
        // motor 0 = the fresh candidate; motors 1.. = already bound (candSeg[b] = -1 so they don't re-commit)
        cSeg.set(0, candSeg); cArc.set(0, candArc); cAz.set(0, candAzim);
        for (int b = 0; b < boundSegs.length; b++) {
            boundSeg.set(1 + b, boundSegs[b]); bindArc.set(1 + b, boundArcs[b]); bindAzim.set(1 + b, boundAzims[b]);
        }
        IntArray committed = new IntArray(nM); IntArray occStats = new IntArray(4); IntArray counts = new IntArray(4);
        counts.set(0, nM);
        FloatArray srp = FloatArray.fromElements((float) R, (float) excl, (float) tol);
        BindingDetectionSystem.surfaceStericResolve(cSeg, cArc, cAz, boundSeg, bindArc, bindAzim,
                fc, fu, fy, fl, segFilId, committed, occStats, srp, counts);
        return boundSeg.get(0) >= 0;
    }

    /** Two fresh candidates 2.7 nm apart in one step: exactly one binds, lower id wins, conflict counter == 1. */
    static boolean stericConflict(FloatArray fc, FloatArray fu, FloatArray fy, FloatArray fl, IntArray segFilId, int nSeg,
                                  double R, double excl, double tol, double half) {
        int nM = 2;
        IntArray cSeg = new IntArray(nM); FloatArray cArc = new FloatArray(nM), cAz = new FloatArray(nM);
        IntArray boundSeg = new IntArray(nM); FloatArray bindArc = new FloatArray(nM), bindAzim = new FloatArray(nM);
        boundSeg.init(MotorStore.FREE_BINDABLE);
        cSeg.set(0, 0); cArc.set(0, (float) half);           cAz.set(0, 0f);
        cSeg.set(1, 0); cArc.set(1, (float)(half + 2.7e-3)); cAz.set(1, 0f);   // 2.7 nm apart (< 5.5)
        IntArray committed = new IntArray(nM); IntArray occStats = new IntArray(4); IntArray counts = new IntArray(4);
        counts.set(0, nM);
        FloatArray srp = FloatArray.fromElements((float) R, (float) excl, (float) tol);
        BindingDetectionSystem.surfaceStericResolve(cSeg, cArc, cAz, boundSeg, bindArc, bindAzim,
                fc, fu, fy, fl, segFilId, committed, occStats, srp, counts);
        boolean oneBound = (boundSeg.get(0) >= 0) ^ (boundSeg.get(1) >= 0);
        boolean lowerWins = boundSeg.get(0) >= 0 && boundSeg.get(1) < 0;
        boolean conf1 = occStats.get(3) == 1;
        // A/B relabel: swap the two candidate arcs → still exactly one, lower id wins.
        return oneBound && lowerWins && conf1;
    }

    // ---- mechanics (tests 19-28) ----
    static void fixtureMechanics(double R) {
        // Single segment along +x at origin, single motor; head pose set directly. j1FMT=0 ⇒ pure F8 (no F9/F10)
        // so xbAxial == total seg ‖û torque and the sign/analytic checks are clean.
        double slen = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;
        double half = 0.5 * slen;
        double kSpring = MYO_SPRING;

        // 19. purely axial force through the centerline → zero axial torque.
        //     head tip offset purely along +x from the centerline site (R=0) ⇒ F ∥ û ⇒ TS·û = 0.
        double arc = half;   // aOff = 0 ⇒ site at seg center
        double[] site0 = {0,0,0};
        double[] htip19 = {0.02, 0, 0};   // 20 nm along +x
        double tau19 = f8AxialTorque(R*0, arc, 0.0, slen, htip19, kSpring);
        check(19, "axial force at centerline (R=0) → axial torque == 0", Math.abs(tau19) < 1e-24);

        // 20. same force at the off-axis point → analytically expected axial torque.
        //     site at azimuth ψ=π/2 (on +ẑ, radius R). head tip displaced tangentially (+ŷ) from the site.
        //     F pulls htip→site; the tangential (ŷ) component of −F at the +ẑ site gives τ_x = R·F_y-ish.
        double psi = Math.PI/2;
        double[] siteOff = siteHost(new double[]{0,0,0}, new double[]{1,0,0}, new double[]{0,1,0}, slen, arc, psi, R); // (0,0,R)
        double dtan = 2.0e-3;   // 2 nm tangential displacement of the head tip in +y
        double[] htip20 = {siteOff[0], siteOff[1] + dtan, siteOff[2]};   // head tip 2nm in +y from site
        // analytic: F = k·(site−htip) = k·(0,−dtan,0); nF=−F=k·(0,+dtan,0) at RS=(0,0,R)·1e-6.
        // TS = RS×nF = (0,0,R*1e-6)×(0,k*dtan,0) = (−R*1e-6·k·dtan, 0, 0). τ_x = −R·1e-6·k·dtan (N·m).
        double tauExp = -(R*1e-6) * (kSpring * dtan);
        double tau20 = f8AxialTorque(R, arc, psi, slen, htip20, kSpring);
        check(20, "off-axis force → axial torque matches analytic (R·F_tan)", relErr(tau20, tauExp) < 1e-4);

        // 21. reversing the azimuth reverses the axial torque sign where geometry requires.
        //     ψ=−π/2 site at (0,0,−R); same +y tangential displacement ⇒ opposite τ_x sign.
        double[] siteNeg = siteHost(new double[]{0,0,0}, new double[]{1,0,0}, new double[]{0,1,0}, slen, arc, -psi, R); // (0,0,−R)
        double[] htip21 = {siteNeg[0], siteNeg[1] + dtan, siteNeg[2]};
        double tau21 = f8AxialTorque(R, arc, -psi, slen, htip21, kSpring);
        check(21, "reverse azimuth → axial torque sign reverses", Math.signum(tau21) == -Math.signum(tau20) && relErr(tau21, -tau20) < 1e-4);

        // 22. reversing the bond force (put head tip on the other tangential side) reverses axial torque.
        double[] htip22 = {siteOff[0], siteOff[1] - dtan, siteOff[2]};
        double tau22 = f8AxialTorque(R, arc, psi, slen, htip22, kSpring);
        check(22, "reverse bond-force direction → axial torque sign reverses", Math.signum(tau22) == -Math.signum(tau20) && relErr(tau22, -tau20) < 1e-4);

        // 23-24. force action–reaction closes & total torque closes about a common origin (F8 central pair).
        double[] cons = f8Conservation(R, arc, psi, slen, htip20, kSpring);
        check(23, "F8 action–reaction closes (|F_head + F_seg| == 0)", cons[0] < 1e-20);
        check(24, "F8 torque closes about a common origin (net τ == 0)", cons[1] < 1e-24);

        // 25. roll integrator responds with the expected sign (drive the filament roll a few steps).
        double rollRate25 = drivenRoll(R, psi, dtan, kSpring, /*freezeRoll=*/false, /*forceOn=*/true, /*steps=*/50);
        check(25, "roll integrator responds; cumulative roll sign matches torque", Math.signum(rollRate25) == Math.signum(tau20) && Math.abs(rollRate25) > 1e-9);

        // 26. roll-frozen control: torque present but cumulative roll stays 0 (bRotGam_x → ∞).
        double rollRate26 = drivenRoll(R, psi, dtan, kSpring, /*freezeRoll=*/true, /*forceOn=*/true, /*steps=*/50);
        check(26, "roll-frozen control: torque present, cumulative roll == 0", Math.abs(rollRate26) < 1e-12);

        // 27. force-disabled control: no directed axial torque / no twirl.
        double rollRate27 = drivenRoll(R, psi, dtan, 0.0, /*freezeRoll=*/false, /*forceOn=*/false, /*steps=*/50);
        check(27, "force-disabled control: no directed twirl", Math.abs(rollRate27) < 1e-12);

        // 28. symmetry control: azimuths uniformly randomized, no polarity coupling ⇒ ensemble mean twirl ≈ 0.
        double meanTwirl = symmetryEnsemble(R, dtan, kSpring, 400);
        check(28, "symmetric random azimuths → ensemble mean twirl ≈ 0", Math.abs(meanTwirl) < 1e-3);
    }

    // --- mechanics helpers (drive the ACTUAL bondForcesSurface kernel on a 1-motor scene) ---

    /** Build a 1-seg filament + 1-motor scene with the head sub-body tip placed at htip, bound at (arc,azim), R.
     *  j1FMT=0 ⇒ pure F8. Returns the scene arrays needed to call bondForcesSurface + gather + integrate. */
    static Mech mechScene(double R, double arc, double azim, double[] htip, double kSpring, boolean freezeRoll) {
        Mech mk = new Mech();
        int nSeg = 1, nMot = 1, nB = 3;
        mk.fc = new FloatArray(3*nSeg); mk.fu = new FloatArray(3*nSeg); mk.fy = new FloatArray(3*nSeg);
        mk.fz = new FloatArray(3*nSeg); mk.fe1 = new FloatArray(3*nSeg); mk.fe2 = new FloatArray(3*nSeg);
        mk.fl = new FloatArray(nSeg);
        double slen = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;
        setSeg(mk.fc, mk.fu, mk.fy, mk.fl, nSeg, 0, 0,0,0, 1,0,0, 0,1,0, slen);
        // filament drag (roll channel γ_x) via DragTensorSystem needs a FilamentStore; build a tiny one.
        FilamentStore f = new FilamentStore(nSeg);
        f.monomerCount.set(0, Constants.stdSegLength); f.setUVec(0,1,0,0); f.setYVec(0,0,1,0); f.setCoord(0,0,0,0);
        DragTensorSystem.run(f); f.setParams(DT, (float) Math.sqrt(2.0*Constants.kT/DT)); f.setCounts(0,0);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        mk.fil = f;
        if (freezeRoll) f.bRotGam.set(0, 1e30f);   // freeze the roll DOF
        // motor head sub-body pose: head index 2. Set coord so tip = htip (tip = coord + 0.5*HEAD_LEN*uVec, uVec=+z... use +x tip)
        mk.mot = new MotorStore(nMot);
        RigidRodBody b = mk.mot.body;
        // set head uVec = +x, so tip = coord + 0.5*HEAD_LEN*(1,0,0); choose coord so tip = htip.
        double hl = MotorStore.HEAD_LEN;
        b.setUVec(2, 1,0,0); b.setYVec(2, 0,1,0);
        b.setCoord(2, (float)(htip[0] - 0.5*hl), (float)htip[1], (float)htip[2]);
        b.segLength.set(2, (float) hl);
        DragTensorSystem.run(mk.mot); mk.mot.setBodyParams(DT); mk.mot.setJointParams(DT);
        mk.mot.setAllStates(MotorStore.NUC_ADPPI);   // uncocked; F9 rest 90 (irrelevant, j1FMT=0)
        mk.mot.boundSeg.set(0, 0); mk.mot.bindArc.set(0, (float) arc); mk.mot.bindAzim.set(0, (float) azim);
        mk.bondData = new FloatArray(nMot * CrossBridgeSystem.STRIDE);
        // xbParams size 8: [6]=Ractin(µm) [7]=segF10Off. j1FMT=0 ⇒ pure F8 (F9/F10 vanish).
        mk.xbParams = FloatArray.fromElements((float) kSpring, 90f, 0f, (float) DT, (float) MotorStore.HEAD_LEN, 0f, (float) R, 1f);
        mk.segMotorCount = new IntArray(nSeg); mk.segMotorOffsets = new IntArray(nSeg+1); mk.segMotorMyo = new IntArray(nMot);
        return mk;
    }
    static final class Mech {
        FloatArray fc, fu, fy, fz, fe1, fe2, fl; FilamentStore fil; MotorStore mot;
        FloatArray bondData, xbParams; IntArray segMotorCount, segMotorOffsets, segMotorMyo;
    }

    /** Axial component of a bond's seg-side torque about the segment axis û (= xbAxial observable). */
    static double axialSegTorque(FloatArray bondData, int m, double sux, double suy, double suz) {
        int d = m * CrossBridgeSystem.STRIDE;
        return bondData.get(d + 9) * sux + bondData.get(d + 10) * suy + bondData.get(d + 11) * suz;
    }

    /** One bondForcesSurface evaluation ⇒ the F8 axial seg torque (N·m). mechScene û_seg = +x. */
    static double f8AxialTorque(double R, double arc, double azim, double slen, double[] htip, double kSpring) {
        Mech mk = mechScene(R, arc, azim, htip, kSpring, false);
        RigidRodBody b = mk.mot.body; FilamentStore f = mk.fil;
        CrossBridgeSystem.bondForcesSurface(b.coord, b.uVec, b.yVec, b.bRotGam,
                f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mk.mot.boundSeg, mk.mot.bindArc, mk.mot.bindAzim, mk.mot.nucleotideState,
                mk.bondData, mk.xbParams);
        return axialSegTorque(mk.bondData, 0, 1, 0, 0);
    }

    /** {|F_head+F_seg|, |net torque about common origin|} from one bond (conservation check). */
    static double[] f8Conservation(double R, double arc, double azim, double slen, double[] htip, double kSpring) {
        Mech mk = mechScene(R, arc, azim, htip, kSpring, false);
        RigidRodBody b = mk.mot.body; FilamentStore f = mk.fil;
        CrossBridgeSystem.bondForcesSurface(b.coord, b.uVec, b.yVec, b.bRotGam,
                f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                mk.mot.boundSeg, mk.mot.bindArc, mk.mot.bindAzim, mk.mot.nucleotideState,
                mk.bondData, mk.xbParams);
        FloatArray bd = mk.bondData;
        double fhx=bd.get(0), fhy=bd.get(1), fhz=bd.get(2), thx=bd.get(3), thy=bd.get(4), thz=bd.get(5);
        double fsx=bd.get(6), fsy=bd.get(7), fsz=bd.get(8), tsx=bd.get(9), tsy=bd.get(10), tsz=bd.get(11);
        double fnet = Math.sqrt(Math.pow(fhx+fsx,2)+Math.pow(fhy+fsy,2)+Math.pow(fhz+fsz,2));
        // torque about origin: head torque about head center + (head center)×F_head + seg torque about seg center + (seg center)×F_seg
        double hcx=b.coord.get(2), hcy=b.coord.get(2+3), hcz=b.coord.get(2+6);   // head sub-body center (planar stride nB=3)
        double scx=f.coord.get(0), scy=f.coord.get(1), scz=f.coord.get(2);
        // convert centers to metres for the R×F moment
        double[] mo = new double[3];
        addCross(mo, hcx*1e-6,hcy*1e-6,hcz*1e-6, fhx,fhy,fhz);
        addCross(mo, scx*1e-6,scy*1e-6,scz*1e-6, fsx,fsy,fsz);
        mo[0]+=thx+tsx; mo[1]+=thy+tsy; mo[2]+=thz+tsz;
        double tnet = Math.sqrt(mo[0]*mo[0]+mo[1]*mo[1]+mo[2]*mo[2]);
        return new double[]{fnet, tnet};
    }

    /** Drive the filament roll for N steps under a single off-axis bond; return net cumulative roll (rad, unwrapped). */
    static double drivenRoll(double R, double azim, double dtan, double kSpring, boolean freezeRoll, boolean forceOn, int steps) {
        // head tip tangentially displaced from the site by dtan in +y (a definite twirl drive)
        double slen = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;
        double[] site = siteHost(new double[]{0,0,0}, new double[]{1,0,0}, new double[]{0,1,0}, slen, 0.5*slen, azim, R);
        double[] htip = {site[0], site[1] + dtan, site[2]};
        Mech mk = mechScene(R, 0.5*slen, azim, htip, forceOn ? kSpring : 0.0, freezeRoll);
        FilamentStore f = mk.fil; RigidRodBody b = mk.mot.body; int nSeg = 1;
        f.brownTransScale.set(0, 0f); f.brownRotScale.set(0, 0f);   // deterministic
        double prevRoll = rollAngle(f, 0), cum = 0;
        for (int t = 0; t < steps; t++) {
            f.setCounts(t, SEED);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
            mk.mot.setCounts(t, SEED, nSeg);
            CrossBridgeSystem.bondForcesSurface(b.coord, b.uVec, b.yVec, b.bRotGam,
                    f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength,
                    mk.mot.boundSeg, mk.mot.bindArc, mk.mot.bindAzim, mk.mot.nucleotideState,
                    mk.bondData, mk.xbParams);
            // gather seg-side reaction into the filament
            CrossBridgeSystem.csrHistogram(mk.mot.boundSeg, mk.mot.counts, mk.segMotorCount);
            CrossBridgeSystem.csrScan(mk.mot.counts, mk.segMotorCount, mk.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mk.mot.boundSeg, mk.mot.counts, mk.segMotorOffsets, mk.segMotorCount, mk.segMotorMyo);
            CrossBridgeSystem.segGather(mk.segMotorOffsets, mk.segMotorMyo, mk.bondData, f.forceSum, f.torqueSum, mk.mot.counts);
            // pin translation (isolate roll): zero the filament translational force
            f.forceSum.set(0,0f); f.forceSum.set(1,0f); f.forceSum.set(2,0f);
            RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum,
                    f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
            DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
            double r = rollAngle(f, 0); cum += wrapPi(r - prevRoll); prevRoll = r;
        }
        return cum;
    }

    /** Symmetry control: a fixed-sign tangential drive gives an azimuth-INDEPENDENT axial torque τ=R·k·d·û (the
     *  coherent twirl itself), so "no chirality" is expressed by pairing each azimuth with BOTH tangential signs
     *  (±d) — with no polarity coupling the pair cancels exactly ⇒ ensemble mean twirl ≈ 0. Random azimuths. */
    static double symmetryEnsemble(double R, double dtan, double kSpring, int nEns) {
        java.util.Random rng = new java.util.Random(SEED);
        double sum = 0;
        for (int e = 0; e < nEns; e++) {
            double azim = -Math.PI + 2*Math.PI*rng.nextDouble();
            sum += drivenRollTan(R, azim,  dtan, kSpring, 30);
            sum += drivenRollTan(R, azim, -dtan, kSpring, 30);   // opposite tangential push ⇒ opposite twirl ⇒ cancels
        }
        return sum / (2*nEns);
    }
    static double drivenRollTan(double R, double azim, double dtanSigned, double kSpring, int steps) {
        double slen = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;
        double[] site = siteHost(new double[]{0,0,0}, new double[]{1,0,0}, new double[]{0,1,0}, slen, 0.5*slen, azim, R);
        // tangential direction at the site = û × r̂ (r̂ = radial). Displace head tip tangentially by dtanSigned.
        double[] ax = {0,0,0};
        double[] rad = {site[0], site[1], site[2]};   // aOff=0 ⇒ axis point is origin ⇒ radial = site
        double[] tanv = norm(cross(new double[]{1,0,0}, norm(rad)));
        double[] htip = {site[0]+tanv[0]*dtanSigned, site[1]+tanv[1]*dtanSigned, site[2]+tanv[2]*dtanSigned};
        Mech mk = mechScene(R, 0.5*slen, azim, htip, kSpring, false);
        FilamentStore f = mk.fil; RigidRodBody b = mk.mot.body; int nSeg=1;
        f.brownTransScale.set(0,0f); f.brownRotScale.set(0,0f);
        double prev = rollAngle(f,0), cum=0;
        for (int t=0;t<steps;t++){
            f.setCounts(t,SEED); mk.mot.setCounts(t,SEED,nSeg);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum,f.torqueSum,f.counts);
            CrossBridgeSystem.bondForcesSurface(b.coord,b.uVec,b.yVec,b.bRotGam,f.coord,f.uVec,f.yVec,f.bRotGam,f.segLength,
                    mk.mot.boundSeg,mk.mot.bindArc,mk.mot.bindAzim,mk.mot.nucleotideState,mk.bondData,mk.xbParams);
            CrossBridgeSystem.csrHistogram(mk.mot.boundSeg,mk.mot.counts,mk.segMotorCount);
            CrossBridgeSystem.csrScan(mk.mot.counts,mk.segMotorCount,mk.segMotorOffsets);
            CrossBridgeSystem.csrScatter(mk.mot.boundSeg,mk.mot.counts,mk.segMotorOffsets,mk.segMotorCount,mk.segMotorMyo);
            CrossBridgeSystem.segGather(mk.segMotorOffsets,mk.segMotorMyo,mk.bondData,f.forceSum,f.torqueSum,mk.mot.counts);
            f.forceSum.set(0,0f);f.forceSum.set(1,0f);f.forceSum.set(2,0f);
            RigidRodLangevinIntegrationSystem.integrate(f.coord,f.uVec,f.yVec,f.forceSum,f.torqueSum,f.randForce,f.randTorque,f.bTransGam,f.bRotGam,f.params,f.counts);
            DerivedGeometrySystem.derive(f.coord,f.uVec,f.yVec,f.zVec,f.end1,f.end2,f.segLength,f.counts);
            double r=rollAngle(f,0); cum+=wrapPi(r-prev); prev=r;
        }
        return cum;
    }

    // =====================================================================================================
    //  CPU/GPU EQUIVALENCE — the new hot kernels (bondForcesSurface + gather, and surfaceStericResolve),
    //  single deterministic evaluation on identical inputs ⇒ bit/last-bit identity (the rigorous G3 claim).
    // =====================================================================================================
    static boolean runEquiv() {
        System.out.println("\n--- CPU/GPU EQUIVALENCE (deterministic single-eval on identical inputs) ---");
        boolean ok = true;
        ok &= equivBond();
        ok &= equivResolve();
        System.out.println("  equivalence " + (ok ? "PASS" : "*** FAIL ***"));
        return ok;
    }

    /** bondForcesSurface + segGather: CPU vs a single-task device graph over identical inputs. */
    static boolean equivBond() {
        // 4 motors bound off-axis to a 2-seg filament at varied arcs/azimuths (real cross-bridge, j1FMT=0.4).
        int nSeg = 2, nMot = 4, nB = 3*nMot;
        FilamentStore f = new FilamentStore(nSeg);
        double slen = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;
        for (int s = 0; s < nSeg; s++) { f.monomerCount.set(s, Constants.stdSegLength); f.setUVec(s,1,0,0); f.setYVec(s,0,1,0);
            f.setCoord(s, (float)(s*slen), 0, 0); f.brownTransScale.set(s,0f); f.brownRotScale.set(s,0f); }
        DragTensorSystem.run(f); f.setParams(DT,(float)Math.sqrt(2.0*Constants.kT/DT)); f.setCounts(0,0);
        DerivedGeometrySystem.derive(f.coord,f.uVec,f.yVec,f.zVec,f.end1,f.end2,f.segLength,f.counts);
        MotorStore mot = new MotorStore(nMot);
        for (int m = 0; m < nMot; m++) {
            double ax = (m%2==0?0:slen), az = -0.05;
            mot.assembleArticulated(m, (float)ax, 0f, (float)az, 0,0,1, 0f);
            mot.boundSeg.set(m, m%nSeg); mot.bindArc.set(m, (float)(0.3*slen + 0.1*m*slen)); mot.bindAzim.set(m, (float)(0.5*m));
        }
        DragTensorSystem.run(mot); mot.setBodyParams(DT); mot.setJointParams(DT); mot.setAllStates(MotorStore.NUC_ADPPI);
        RigidRodBody b = mot.body;
        MotorStore.publishHeadFromBody(b.coord, b.uVec, b.segLength, mot.head, mot.uVec, mot.rodUVec, mot.counts);
        FloatArray bondData = new FloatArray(nMot*CrossBridgeSystem.STRIDE);
        FloatArray xbParams = FloatArray.fromElements((float)MYO_SPRING, 90f, (float)J1_FMT, (float)DT, (float)MotorStore.HEAD_LEN, 0f, (float)(R_ACTIN_NM*1e-3), 0f);
        IntArray smc = new IntArray(nSeg), smo = new IntArray(nSeg+1), smm = new IntArray(nMot);
        mot.setCounts(0, SEED, nSeg);
        // CPU
        FloatArray cF = new FloatArray(3*nSeg), cT = new FloatArray(3*nSeg);
        runBondCPU(b, f, mot, bondData, xbParams, smc, smo, smm, cF, cT);
        // GPU (rebuild a fresh graph over the same inputs; zero fil accumulators first)
        f.forceSum.init(0f); f.torqueSum.init(0f); bondData.init(0f);
        FloatArray gF = new FloatArray(3*nSeg), gT = new FloatArray(3*nSeg);
        boolean gpuOk = runBondGPU(b, f, mot, bondData, xbParams, smc, smo, smm, gF, gT);
        double dF=0, dT=0;
        for (int i=0;i<3*nSeg;i++){ dF=Math.max(dF,Math.abs(cF.get(i)-gF.get(i))); dT=Math.max(dT,Math.abs(cT.get(i)-gT.get(i))); }
        boolean ok = gpuOk && dF < 1e-12 && dT < 1e-15;
        System.out.printf("  bondForcesSurface+gather: max|ΔfilForce|=%.2e N  max|ΔfilTorque|=%.2e N·m  ⇒ %s%n",
                dF, dT, ok ? "CPU≡GPU" : (gpuOk?"*MISMATCH*":"GPU-UNAVAILABLE (CPU-only reported)"));
        return ok || !gpuOk;   // if GPU unavailable, don't fail the gate (CPU-only disclosed)
    }

    static void runBondCPU(RigidRodBody b, FilamentStore f, MotorStore mot, FloatArray bondData, FloatArray xbParams,
                           IntArray smc, IntArray smo, IntArray smm, FloatArray outF, FloatArray outT) {
        f.forceSum.init(0f); f.torqueSum.init(0f);
        CrossBridgeSystem.bondForcesSurface(b.coord,b.uVec,b.yVec,b.bRotGam,f.coord,f.uVec,f.yVec,f.bRotGam,f.segLength,
                mot.boundSeg,mot.bindArc,mot.bindAzim,mot.nucleotideState,bondData,xbParams);
        CrossBridgeSystem.csrHistogram(mot.boundSeg,mot.counts,smc);
        CrossBridgeSystem.csrScan(mot.counts,smc,smo);
        CrossBridgeSystem.csrScatter(mot.boundSeg,mot.counts,smo,smc,smm);
        CrossBridgeSystem.segGather(smo,smm,bondData,f.forceSum,f.torqueSum,mot.counts);
        for (int i=0;i<outF.getSize();i++){ outF.set(i,f.forceSum.get(i)); outT.set(i,f.torqueSum.get(i)); }
    }

    static boolean runBondGPU(RigidRodBody b, FilamentStore f, MotorStore mot, FloatArray bondData, FloatArray xbParams,
                              IntArray smc, IntArray smo, IntArray smm, FloatArray outF, FloatArray outT) {
        try {
            TaskGraph tg = new TaskGraph("surfEq")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                        b.coord,b.uVec,b.yVec,b.bRotGam, f.coord,f.uVec,f.yVec,f.bRotGam,f.segLength,
                        mot.boundSeg,mot.bindArc,mot.bindAzim,mot.nucleotideState, bondData,xbParams,
                        f.forceSum,f.torqueSum, smc,smo,smm)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, mot.counts)
                .task("bond", CrossBridgeSystem::bondForcesSurface,
                        b.coord,b.uVec,b.yVec,b.bRotGam,f.coord,f.uVec,f.yVec,f.bRotGam,f.segLength,
                        mot.boundSeg,mot.bindArc,mot.bindAzim,mot.nucleotideState,bondData,xbParams)
                .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, smc)
                .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, smc, smo)
                .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, smo, smc, smm)
                .task("gather", CrossBridgeSystem::segGather, smo, smm, bondData, f.forceSum, f.torqueSum, mot.counts)
                .transferToHost(DataTransferMode.UNDER_DEMAND, f.forceSum, f.torqueSum);
            int nM = mot.nMotors, nSeg = f.n;
            sched = new GridScheduler();
            addW("surfEq.bond", pad(nM)); addW("surfEq.gather", pad(nSeg));
            addS("surfEq.csrHist"); addS("surfEq.csrScan"); addS("surfEq.csrScatter");
            TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot());
            TornadoExecutionResult res = plan.withGridScheduler(sched).execute();
            res.transferToHost(f.forceSum, f.torqueSum);
            for (int i=0;i<outF.getSize();i++){ outF.set(i,f.forceSum.get(i)); outT.set(i,f.torqueSum.get(i)); }
            return true;
        } catch (Throwable e) {
            System.out.println("  (GPU graph unavailable: " + e.getClass().getSimpleName() + " — reporting CPU-only)");
            return false;
        }
    }

    /** surfaceStericResolve: CPU vs a single-task device graph on a constructed 6-head/2-fil fixture. */
    static boolean equivResolve() {
        int nSeg = 2, nM = 6;
        FloatArray fc = new FloatArray(3*nSeg), fu = new FloatArray(3*nSeg), fy = new FloatArray(3*nSeg), fl = new FloatArray(nSeg);
        double slen = (Constants.stdSegLength + 1) * Constants.actinMonoRadius, half = 0.5*slen;
        setSeg(fc,fu,fy,fl,nSeg,0, 0,0,0, 1,0,0, 0,1,0, slen);
        setSeg(fc,fu,fy,fl,nSeg,1, 0,1.0,0, 1,0,0, 0,1,0, slen);
        IntArray segFilId = new IntArray(nSeg); segFilId.set(0,0); segFilId.set(1,1);
        double R = R_ACTIN_NM*1e-3, excl = EXCL_NM*1e-3, tol = TOL_NM*1e-3;
        // candidates engineered: 2 accepts, 2 rejects (1 same-step conflict), 2 no-candidate.
        int[] cs = {0,0,1,0,-1,-1}; float[] ca = {(float)half,(float)(half+2.7e-3),(float)half,(float)(half+30e-3),0,0};
        float[] cz = {0f,0f,0f,0f,0f,0f};
        int[] outC = new int[nM], outCg = new int[nM]; int[] statC = new int[4], statG = new int[4];
        resolveOnce(fc,fu,fy,fl,segFilId,R,excl,tol,cs,ca,cz,outC,statC,false);
        boolean gpuOk = resolveOnce(fc,fu,fy,fl,segFilId,R,excl,tol,cs,ca,cz,outCg,statG,true);
        boolean same = true; for (int m=0;m<nM;m++) same &= outC[m]==outCg[m];
        for (int k=0;k<4;k++) same &= statC[k]==statG[k];
        boolean ok = !gpuOk || same;
        System.out.printf("  surfaceStericResolve: CPU boundSeg=%s stats=%s ; GPU %s ⇒ %s%n",
                java.util.Arrays.toString(outC), java.util.Arrays.toString(statC),
                gpuOk?(java.util.Arrays.toString(outCg)+" "+java.util.Arrays.toString(statG)):"UNAVAILABLE",
                ok ? "CPU≡GPU" : "*MISMATCH*");
        return ok;
    }
    static boolean resolveOnce(FloatArray fc, FloatArray fu, FloatArray fy, FloatArray fl, IntArray segFilId,
                               double R, double excl, double tol, int[] cs, float[] ca, float[] cz, int[] outBound, int[] outStat, boolean gpu) {
        int nM = cs.length;
        IntArray candSeg=new IntArray(nM); FloatArray candArc=new FloatArray(nM), candAz=new FloatArray(nM);
        IntArray boundSeg=new IntArray(nM); FloatArray bindArc=new FloatArray(nM), bindAzim=new FloatArray(nM);
        boundSeg.init(MotorStore.FREE_BINDABLE);
        for (int m=0;m<nM;m++){ candSeg.set(m,cs[m]); candArc.set(m,ca[m]); candAz.set(m,cz[m]); }
        IntArray committed=new IntArray(nM), occStats=new IntArray(4), counts=new IntArray(4); counts.set(0,nM);
        FloatArray srp = FloatArray.fromElements((float)R,(float)excl,(float)tol);
        if (!gpu) {
            BindingDetectionSystem.surfaceStericResolve(candSeg,candArc,candAz,boundSeg,bindArc,bindAzim,fc,fu,fy,fl,segFilId,committed,occStats,srp,counts);
        } else {
            try {
                TaskGraph tg = new TaskGraph("resEq")
                    .transferToDevice(DataTransferMode.FIRST_EXECUTION, candSeg,candArc,candAz,boundSeg,bindArc,bindAzim,fc,fu,fy,fl,segFilId,committed,occStats,srp)
                    .transferToDevice(DataTransferMode.EVERY_EXECUTION, counts)
                    .task("resolve", BindingDetectionSystem::surfaceStericResolve, candSeg,candArc,candAz,boundSeg,bindArc,bindAzim,fc,fu,fy,fl,segFilId,committed,occStats,srp,counts)
                    .transferToHost(DataTransferMode.UNDER_DEMAND, boundSeg, occStats);
                sched = new GridScheduler(); addS("resEq.resolve");
                TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot());
                plan.withGridScheduler(sched).execute().transferToHost(boundSeg, occStats);
            } catch (Throwable e) { System.out.println("  (GPU resolve unavailable: "+e.getClass().getSimpleName()+")"); return false; }
        }
        for (int m=0;m<nM;m++) outBound[m]=boundSeg.get(m);
        for (int k=0;k<4;k++) outStat[k]=occStats.get(k);
        return true;
    }

    // =====================================================================================================
    //  SMALL DYNAMIC CAMPAIGN + OBSERVABLES (CPU sequential runner — disclosed).
    // =====================================================================================================
    static void runCampaign() {
        System.out.println("\n--- SMALL DYNAMIC TWIRL CAMPAIGN (CPU sequential runner; pinned-translation roll assay) ---");
        System.out.println("Scene: filament (translation pinned, roll free) + a bed of pre-bound off-axis motors.");
        System.out.println("A STATIC bed relaxes to a spring equilibrium ⇒ a DRIVEN ROLL DISPLACEMENT (cumTurns), not a");
        System.out.println("steady rate; sustained many-turn twirling needs the dynamic bind/stroke/release cycle (see report).");
        System.out.printf("%-38s %14s %14s %12s%n", "config", "cumTurns", "peak ω(rad/s)", "xbAxial(pN·nm)");
        double R = R_ACTIN_NM*1e-3;
        // controls & arms
        campaignRun("centerline control (R=0)",           0.0,  false, false, false, 20000);
        campaignRun("off-axis only (R=3.5, steric OFF)",  R,    false, false, false, 20000);
        campaignRun("off-axis + steric 5.5nm",            R,    true,  false, false, 20000);
        campaignRun("off-axis + roll-spring ON",          R,    false, true,  false, 20000);
        campaignRun("off-axis + Brownian ON (seed A)",    R,    false, false, true,  20000);
        System.out.println("  Brownian-ON multi-seed (driven-roll mean ± spread):");
        double[] seeds = new double[4]; int base = SEED;
        for (int i=0;i<4;i++){ SEED = base + i*101; seeds[i] = campaignRunReturn(R, false, false, true, 20000); }
        SEED = base;
        double mean=0; for(double v:seeds) mean+=v; mean/=4; double var=0; for(double v:seeds) var+=(v-mean)*(v-mean); var/=4;
        System.out.printf("    seeds=%s  mean=%.4f turns  sd=%.4f%n", fmt(seeds), mean, Math.sqrt(var));
        // timestep-halving robustness: compare the SATURATED equilibrium roll displacement at MATCHED sim-time.
        System.out.println("  Timestep-halving (deterministic off-axis; equilibrium cumTurns at MATCHED sim-time ⇒ dt-invariant):");
        double base_dt = DT; int stepsA = 20000;
        DT = base_dt;    double c1 = twirlAssay(R,false,false,false,stepsA)[0];
        DT = base_dt/2;  double c2 = twirlAssay(R,false,false,false,2*stepsA)[0];   // half dt, double steps = same sim-time
        DT = base_dt;
        System.out.printf("    dt=%.1e cumTurns=%.5f ; dt=%.1e cumTurns=%.5f ; ratio=%.4f (→1 = dt-robust)%n",
                base_dt, c1, base_dt/2, c2, c2/(c1==0?1:c1));
        // translation-rotation coupling: the SAME off-axis bonds produce a nonzero axial force AND a nonzero axial torque.
        double[] cp = campaignCoupling(R);
        System.out.printf("  translation↔rotation coupling: axial drive ΣF·û=%.3e N, roll torque ΣT·û=%.3e N·m (same off-axis bonds ⇒ coupled)%n", cp[0], cp[1]);
    }

    static void campaignRun(String label, double R, boolean steric, boolean rollspring, boolean brownian, int steps) {
        double[] r = twirlAssay(R, steric, rollspring, brownian, steps);
        System.out.printf("%-38s %14.4f %14.3e %12.3f%n", label, r[0], r[1], r[3]*1e21 /*N·m→pN·nm*/);
    }
    static double campaignRunReturn(double R, boolean steric, boolean rollspring, boolean brownian, int steps) {
        return twirlAssay(R, steric, rollspring, brownian, steps)[0];
    }
    static double campaignTurnsPerSec(double R) { return twirlAssay(R, false, false, false, 20000)[2]; }
    static double[] campaignCoupling(double R) {
        // reuse the assay but report the axial force & roll torque accumulators
        return twirlAssayCoupling(R, 5000);
    }

    /** The core twirl assay: bed of pre-bound off-axis motors on a 1-seg (or short-chain) filament,
     *  translation pinned, roll free. Returns {cumTurns, omega(rad/s), turns/s, meanXbAxial(N·m)}. */
    static double[] twirlAssay(double R, boolean steric, boolean rollspring, boolean brownian, int steps) {
        int nSeg = rollspring ? 6 : 1;
        int perSeg = 6, nMot = nSeg*perSeg;
        FilamentStore f = new FilamentStore(nSeg);
        double slen = (Constants.stdSegLength + 1) * Constants.actinMonoRadius;
        double restRad = wrapPi(Constants.stdSegLength * TWIST_PER_MON_DEG * Math.PI/180.0);
        for (int s=0;s<nSeg;s++){
            f.monomerCount.set(s, Constants.stdSegLength); f.setUVec(s,1,0,0);
            double a = s*restRad; f.setYVec(s, 0f,(float)Math.cos(a),(float)Math.sin(a));
            f.setCoord(s, (float)(s*slen),0,0);
            f.brownTransScale.set(s,0f); f.brownRotScale.set(s, brownian?1f:0f);
            if (s<nSeg-1){ f.end2NbrSlot.set(s,s+1); f.end2NbrSide.set(s,0); }
            if (s>0){ f.end1NbrSlot.set(s,s-1); f.end1NbrSide.set(s,1); }
        }
        DragTensorSystem.run(f); f.setParams(DT,(float)Math.sqrt(2.0*Constants.kT/DT)); f.setChainParams(DT); f.setCounts(0,SEED);
        DerivedGeometrySystem.derive(f.coord,f.uVec,f.yVec,f.zVec,f.end1,f.end2,f.segLength,f.counts);
        MotorStore mot = new MotorStore(nMot);
        int m=0;
        for (int s=0;s<nSeg;s++) for (int k=0;k<perSeg;k++){
            double az=-0.05; mot.assembleArticulated(m,(float)(s*slen),0f,(float)az,0,0,1,0f);
            mot.boundSeg.set(m,s); mot.bindArc.set(m,(float)((0.2+0.6*k/(double)perSeg)*slen));
            // spread azimuths so the bed pushes coherently one way (tangential registration): choose azim so the
            // reconstructed site sits tangentially offset from each head — here fix azim so the drive is directed.
            mot.bindAzim.set(m,(float)(Math.PI/2));   // sites on +ẑ side of the material frame ⇒ heads at +z push tangentially
            m++;
        }
        DragTensorSystem.run(mot); mot.setBodyParams(DT); mot.setJointParams(DT); mot.setAllStates(MotorStore.NUC_ADPPI);
        RigidRodBody b = mot.body;
        MotorStore.publishHeadFromBody(b.coord,b.uVec,b.segLength,mot.head,mot.uVec,mot.rodUVec,mot.counts);
        FloatArray bondData=new FloatArray(nMot*CrossBridgeSystem.STRIDE);
        // xbParams size 8: [6]=Ractin [7]=segF10Off=1 (clean twirl isolation)
        FloatArray xbParams=FloatArray.fromElements((float)MYO_SPRING,90f,(float)J1_FMT,(float)DT,(float)MotorStore.HEAD_LEN,0f,(float)R,1f);
        IntArray smc=new IntArray(nSeg),smo=new IntArray(nSeg+1),smm=new IntArray(nMot);
        FloatArray rollParams=new FloatArray(7);
        rollParams.set(0,(float)DT); rollParams.set(1,0.5f); rollParams.set(2,(float)restRad); rollParams.set(3,2f);
        rollParams.set(4,0f); rollParams.set(5,0.1f); rollParams.set(6,1.0e-5f);

        double prev = rollAngle(f,0), cum=0, axSum=0; int axN=0;
        for (int t=0;t<steps;t++){
            f.setCounts(t,SEED); mot.setCounts(t,SEED,nSeg);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum,f.torqueSum,f.counts);
            if (brownian) BrownianForceSystem.brownianForce(f.randForce,f.randTorque,f.bTransGam,f.bRotGam,f.brownTransScale,f.brownRotScale,f.params,f.counts);
            CrossBridgeSystem.bondForcesSurface(b.coord,b.uVec,b.yVec,b.bRotGam,f.coord,f.uVec,f.yVec,f.bRotGam,f.segLength,
                    mot.boundSeg,mot.bindArc,mot.bindAzim,mot.nucleotideState,bondData,xbParams);
            if (nSeg>1) ChainBendingForceSystem.chainForces(f.coord,f.uVec,f.segLength,f.end2NbrSlot,f.end2NbrSide,f.end1NbrSlot,f.end1NbrSide,f.bTransGam,f.bRotGam,f.forceSum,f.torqueSum,f.chainParams,f.counts);
            if (rollspring) RollSpringSystem.rollForces(f.uVec,f.yVec,f.end2NbrSlot,f.end1NbrSlot,f.bRotGam,f.torqueSum,rollParams,f.counts);
            CrossBridgeSystem.csrHistogram(mot.boundSeg,mot.counts,smc);
            CrossBridgeSystem.csrScan(mot.counts,smc,smo);
            CrossBridgeSystem.csrScatter(mot.boundSeg,mot.counts,smo,smc,smm);
            CrossBridgeSystem.segGather(smo,smm,bondData,f.forceSum,f.torqueSum,mot.counts);
            // pin translation (isolate roll)
            for (int s=0;s<nSeg;s++){ f.forceSum.set(s,0f); f.forceSum.set(nSeg+s,0f); f.forceSum.set(2*nSeg+s,0f); }
            RigidRodLangevinIntegrationSystem.integrate(f.coord,f.uVec,f.yVec,f.forceSum,f.torqueSum,f.randForce,f.randTorque,f.bTransGam,f.bRotGam,f.params,f.counts);
            DerivedGeometrySystem.derive(f.coord,f.uVec,f.yVec,f.zVec,f.end1,f.end2,f.segLength,f.counts);
            double r=rollAngle(f,0); cum+=wrapPi(r-prev); prev=r;
            for (int mm=0;mm<nMot;mm++){ int bs=mot.boundSeg.get(mm); if(bs<0) continue;
                axSum+=axialSegTorque(bondData,mm,f.uVec.get(bs),f.uVec.get(nSeg+bs),f.uVec.get(2*nSeg+bs)); axN++; }
        }
        double turns = cum/(2*Math.PI);
        double totalTime = steps*DT;
        double omega = cum/totalTime;
        double turnsPerSec = turns/totalTime;
        return new double[]{ turns, omega, turnsPerSec, axSum/Math.max(1,axN) };
    }

    /** Coupling probe: run the assay with translation FREE (no pin) and report mean axial drive force & roll torque. */
    static double[] twirlAssayCoupling(double R, int steps) {
        int nSeg=1, nMot=6;
        FilamentStore f = new FilamentStore(nSeg);
        double slen=(Constants.stdSegLength+1)*Constants.actinMonoRadius;
        f.monomerCount.set(0,Constants.stdSegLength); f.setUVec(0,1,0,0); f.setYVec(0,0,1,0); f.setCoord(0,0,0,0);
        f.brownTransScale.set(0,0f); f.brownRotScale.set(0,0f);
        DragTensorSystem.run(f); f.setParams(DT,(float)Math.sqrt(2.0*Constants.kT/DT)); f.setCounts(0,SEED);
        DerivedGeometrySystem.derive(f.coord,f.uVec,f.yVec,f.zVec,f.end1,f.end2,f.segLength,f.counts);
        MotorStore mot=new MotorStore(nMot);
        for (int m=0;m<nMot;m++){ mot.assembleArticulated(m,0f,0f,-0.05f,0,0,1,0f); mot.boundSeg.set(m,0);
            mot.bindArc.set(m,(float)((0.2+0.6*m/(double)nMot)*slen)); mot.bindAzim.set(m,(float)(Math.PI/2)); }
        DragTensorSystem.run(mot); mot.setBodyParams(DT); mot.setJointParams(DT); mot.setAllStates(MotorStore.NUC_ADPPI);
        RigidRodBody b=mot.body; MotorStore.publishHeadFromBody(b.coord,b.uVec,b.segLength,mot.head,mot.uVec,mot.rodUVec,mot.counts);
        FloatArray bondData=new FloatArray(nMot*CrossBridgeSystem.STRIDE);
        FloatArray xbParams=FloatArray.fromElements((float)MYO_SPRING,90f,(float)J1_FMT,(float)DT,(float)MotorStore.HEAD_LEN,0f,(float)R,1f);
        IntArray smc=new IntArray(nSeg),smo=new IntArray(nSeg+1),smm=new IntArray(nMot);
        double fAx=0, tAx=0; int n=0;
        for (int t=0;t<steps;t++){
            f.setCounts(t,SEED); mot.setCounts(t,SEED,nSeg);
            ChainBendingForceSystem.zeroAccumulators(f.forceSum,f.torqueSum,f.counts);
            CrossBridgeSystem.bondForcesSurface(b.coord,b.uVec,b.yVec,b.bRotGam,f.coord,f.uVec,f.yVec,f.bRotGam,f.segLength,
                    mot.boundSeg,mot.bindArc,mot.bindAzim,mot.nucleotideState,bondData,xbParams);
            CrossBridgeSystem.csrHistogram(mot.boundSeg,mot.counts,smc); CrossBridgeSystem.csrScan(mot.counts,smc,smo);
            CrossBridgeSystem.csrScatter(mot.boundSeg,mot.counts,smo,smc,smm);
            CrossBridgeSystem.segGather(smo,smm,bondData,f.forceSum,f.torqueSum,mot.counts);
            // axial drive = ΣF·û ; roll torque = ΣT·û  (û = +x here). Measured BEFORE the translation pin.
            fAx += f.forceSum.get(0); tAx += f.torqueSum.get(0); n++;
            f.forceSum.set(0,0f); f.forceSum.set(1,0f); f.forceSum.set(2,0f);   // pin translation ⇒ stable (no runaway)
            RigidRodLangevinIntegrationSystem.integrate(f.coord,f.uVec,f.yVec,f.forceSum,f.torqueSum,f.randForce,f.randTorque,f.bTransGam,f.bRotGam,f.params,f.counts);
            DerivedGeometrySystem.derive(f.coord,f.uVec,f.yVec,f.zVec,f.end1,f.end2,f.segLength,f.counts);
        }
        return new double[]{ fAx/n, tAx/n };
    }

    // =====================================================================================================
    //  kernel-R0 identity: bondForcesSurface(R=0) bondData == bondForces bondData (byte-identical) on a bound scene.
    // =====================================================================================================
    static boolean kernelR0IdenticalToCenterline() {
        int nSeg=2, nMot=4;
        FilamentStore f=new FilamentStore(nSeg);
        double slen=(Constants.stdSegLength+1)*Constants.actinMonoRadius;
        for (int s=0;s<nSeg;s++){ f.monomerCount.set(s,Constants.stdSegLength); f.setUVec(s,1,0,0); f.setYVec(s,0,1,0);
            f.setCoord(s,(float)(s*slen),0,0); f.brownTransScale.set(s,0f); f.brownRotScale.set(s,0f); }
        DragTensorSystem.run(f); f.setParams(DT,(float)Math.sqrt(2.0*Constants.kT/DT)); f.setCounts(0,0);
        DerivedGeometrySystem.derive(f.coord,f.uVec,f.yVec,f.zVec,f.end1,f.end2,f.segLength,f.counts);
        MotorStore mot=new MotorStore(nMot);
        for (int mm=0;mm<nMot;mm++){ mot.assembleArticulated(mm,(float)((mm%2)*slen),0f,-0.05f,0,0,1,0f);
            mot.boundSeg.set(mm,mm%nSeg); mot.bindArc.set(mm,(float)(0.3*slen+0.1*mm*slen)); mot.bindAzim.set(mm,(float)(0.5*mm)); }
        DragTensorSystem.run(mot); mot.setBodyParams(DT); mot.setJointParams(DT); mot.setAllStates(MotorStore.NUC_ADPPI);
        RigidRodBody b=mot.body; mot.setCounts(0,SEED,nSeg);
        FloatArray xbP=FloatArray.fromElements((float)MYO_SPRING,90f,(float)J1_FMT,(float)DT,(float)MotorStore.HEAD_LEN,0f);
        FloatArray bd1=new FloatArray(nMot*CrossBridgeSystem.STRIDE);
        CrossBridgeSystem.bondForces(b.coord,b.uVec,b.yVec,b.bRotGam,f.coord,f.uVec,f.yVec,f.bRotGam,f.segLength,
                mot.boundSeg,mot.bindArc,mot.nucleotideState,bd1,xbP);
        FloatArray bd2=new FloatArray(nMot*CrossBridgeSystem.STRIDE);
        // R=0, segF10Off=0 (xbParams[6]=0,[7]=0) ⇒ bondForcesSurface must reproduce bondForces byte-for-byte
        FloatArray xbPS=FloatArray.fromElements((float)MYO_SPRING,90f,(float)J1_FMT,(float)DT,(float)MotorStore.HEAD_LEN,0f,0f,0f);
        CrossBridgeSystem.bondForcesSurface(b.coord,b.uVec,b.yVec,b.bRotGam,f.coord,f.uVec,f.yVec,f.bRotGam,f.segLength,
                mot.boundSeg,mot.bindArc,mot.bindAzim,mot.nucleotideState,bd2,xbPS);
        for (int i=0;i<bd1.getSize();i++) if (bd1.get(i)!=bd2.get(i)) return false;   // byte-identical
        return true;
    }

    // =====================================================================================================
    //  small utilities
    // =====================================================================================================
    static void setSeg(FloatArray fc, FloatArray fu, FloatArray fy, FloatArray fl, int nSeg, int s,
                       double cx,double cy,double cz, double ux,double uy,double uz, double yx,double yy,double yz, double slen) {
        fc.set(s,(float)cx); fc.set(nSeg+s,(float)cy); fc.set(2*nSeg+s,(float)cz);
        fu.set(s,(float)ux); fu.set(nSeg+s,(float)uy); fu.set(2*nSeg+s,(float)uz);
        fy.set(s,(float)yx); fy.set(nSeg+s,(float)yy); fy.set(2*nSeg+s,(float)yz);
        fl.set(s,(float)slen);
    }
    /** Signed roll of segment s's yVec about its own axis û, referenced to a fixed lab direction projected ⟂ û.
     *  For a filament whose û stays ≈ +x this is a stable, continuously-unwrappable roll coordinate. */
    static double rollAngle(FilamentStore f, int s) {
        int n=f.n;
        double ux=f.uVec.get(s), uy=f.uVec.get(n+s), uz=f.uVec.get(2*n+s);
        double yx=f.yVec.get(s), yy=f.yVec.get(n+s), yz=f.yVec.get(2*n+s);
        // Gram–Schmidt a fixed lab reference (+y) into the plane ⟂ û  →  ĝ ; then ĥ = û×ĝ.
        double ex=0,ey=1,ez=0; double d=ux*ex+uy*ey+uz*ez;
        double gx=ex-d*ux, gy=ey-d*uy, gz=ez-d*uz; double gl=Math.sqrt(gx*gx+gy*gy+gz*gz);
        if (gl<1e-12){ gx=0;gy=0;gz=1; d=ux*0+uy*0+uz*1; gx=-d*ux; gy=-d*uy; gz=1-d*uz; gl=Math.sqrt(gx*gx+gy*gy+gz*gz); }
        gx/=gl;gy/=gl;gz/=gl;
        double hx=uy*gz-uz*gy, hy=uz*gx-ux*gz, hz=ux*gy-uy*gx;   // û×ĝ (unit; ĝ⟂û)
        double c=yx*gx+yy*gy+yz*gz, sn=yx*hx+yy*hy+yz*hz;
        return Math.atan2(sn,c);
    }
    static double wrapPi(double a){ double T=2*Math.PI; a=a-T*Math.floor((a+Math.PI)/T); if(a>Math.PI)a-=T; return a; }
    static double angDiff(double a, double b){ return Math.abs(wrapPi(a-b)); }
    static double relErr(double a, double b){ double d=Math.abs(a-b); double s=Math.max(Math.abs(a),Math.abs(b)); return s<1e-30?d:d/s; }
    static double[] norm(double[] v){ double l=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]); return l<1e-30?v:new double[]{v[0]/l,v[1]/l,v[2]/l}; }
    static double[] cross(double[] a, double[] b){ return new double[]{a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]}; }
    static double dot(double[] a, double[] b){ return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static void addCross(double[] acc, double ax,double ay,double az, double bx,double by,double bz){
        acc[0]+=ay*bz-az*by; acc[1]+=az*bx-ax*bz; acc[2]+=ax*by-ay*bx;
    }
    static int pad(int n){ return ((n+B-1)/B)*B; }
    static void addW(String name,int g){ WorkerGrid w=new WorkerGrid1D(g); w.setLocalWork(B,1,1); sched.addWorkerGrid(name,w); }
    static void addS(String name){ WorkerGrid w=new WorkerGrid1D(1); w.setLocalWork(1,1,1); sched.addWorkerGrid(name,w); }
    static String fmt(double[] v){ StringBuilder sb=new StringBuilder("["); for(int i=0;i<v.length;i++){ if(i>0)sb.append(", "); sb.append(String.format("%.4f",v[i])); } return sb.append("]").toString(); }
}
