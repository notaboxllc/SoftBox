package softbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * FIRST device-resident motor-mat SoA VERTICAL SLICE (calibrated-only, experimental).
 *
 * <p>Builds the calibrated gliding mat (`buildSupMat`) as the host oracle and validates each new device
 * kernel in ISOLATION (Part 5): identical flat SoA in → host stage (the FROZEN semantics in
 * `TwoBodyConverterMotor`) + GPU stage → compare every changed field, verify unrelated fields unchanged.
 * All GPU runs require `-Dtornado.recover.bailout=false` (a lowering failure throws — no silent CPU
 * fallback). Motor SoA stays device-resident; only compact filament state + scalar controls + reduced
 * outputs cross the bus. Does NOT flip {@code MotorGpuParams.DEVICE_VALIDATED} (experimental).
 *
 * <p>Stage 1 (matCull) implemented + gated here. Cull distance is computed in DOUBLE to match the host
 * `siteSegDist2` bit-for-decision (the active SET must be identical). The grid acceleration
 * (`initMatGrid`) is a pure optimization — the per-motor brute union gather here yields the IDENTICAL
 * active set (cullMode=1 grid ≡ cullMode=2 brute), and is race-free (per-motor gather, no scatter).
 */
public final class MatSoaSlice {
    private MatSoaSlice() {}

    static final String OUTDIR = "RUN_LOGS/matsoa";

    // ===============================================================================================
    // KERNEL — Stage 1: matCull.  active[m] = (boundSeg[m] >= 0) OR (site_m within queryR of ANY segment).
    //   Reproduces unionActive (TwoBodyConverterMotor.L4721) as a race-free per-motor gather.
    //   site: DoubleArray planar 2N (x=[m], y=[N+m]) — the fixed ideal head site (host G.siteX/Y are double).
    //   filCoord/filUVec/filSegLen: FilamentStore FloatArrays (planar: X=[s], Y=[nSeg+s]).
    //   cullParams[0] = queryR^2 (double, = G.queryR^2). counts = {N, t, seed, nSeg}. active[N] written.
    // ===============================================================================================
    public static void matCull(IntArray boundSeg, DoubleArray site,
                               FloatArray filCoord, FloatArray filUVec, FloatArray filSegLen,
                               DoubleArray cullParams, IntArray counts, IntArray active) {
        int N = counts.get(0);
        int nSeg = counts.get(3);
        double qr2 = cullParams.get(0);
        for (@Parallel int m = 0; m < N; m++) {
            int a;
            if (boundSeg.get(m) >= 0) {
                a = 1;
            } else {
                double sx = site.get(m), sy = site.get(N + m);
                a = 0;
                for (int s = 0; s < nSeg; s++) {
                    double half = 0.5 * (double) filSegLen.get(s);
                    double cx = filCoord.get(s), cy = filCoord.get(nSeg + s);
                    double ux = filUVec.get(s), uy = filUVec.get(nSeg + s);
                    double dx = sx - cx, dy = sy - cy;
                    double foot = dx * ux + dy * uy;
                    foot = foot < -half ? -half : (foot > half ? half : foot);   // == Math.max(-half,Math.min(half,foot)), reinterpret-free
                    double px = dx - foot * ux, py = dy - foot * uy;
                    double d2 = px * px + py * py;
                    if (d2 <= qr2) a = 1;
                }
            }
            active.set(m, a);
        }
    }

    // ===============================================================================================
    // KERNEL — Stage 3: matBind (DETERMINISTIC — no RNG).  Applies the candidate from matGeomGate under the
    //   exact host eligibility guard: active ∧ !noBind ∧ boundSeg==FREE_BINDABLE(-1) ∧ nuc==NUC_ADPPI(2)
    //   ∧ candAccept==1 → boundSeg=candSeg, bindArc=candBindArc. (Binding is a geometric AND, not a P_bind roll.)
    //   flags: active=[m]; noBind=[m]. cand: candSeg=[m], candAccept=[N+m]. bindArc: MotorStore.bindArc (float).
    // ===============================================================================================
    public static void matBind(IntArray active, IntArray noBind, IntArray boundSeg, IntArray nucState,
                               IntArray candInt, DoubleArray candBindArc, FloatArray bindArc, IntArray counts) {
        int N = counts.get(0);
        for (@Parallel int m = 0; m < N; m++) {
            if (active.get(m) == 1 && noBind.get(m) == 0 && boundSeg.get(m) == -1 && nucState.get(m) == 2
                    && candInt.get(N + m) == 1) {
                boundSeg.set(m, candInt.get(m));
                bindArc.set(m, (float) candBindArc.get(m));
            }
        }
    }

    static double dabs(double x) { return x < 0 ? -x : x; }              // reinterpret-free |x| (Math.abs uses doubleToRawLongBits)
    static double deg(double x) { return x * 180.0 / Math.PI; }          // == JDK Math.toDegrees(angrad) = angrad*180.0/PI

    // ===============================================================================================
    // KERNEL — Stage 8 (cocking): matCock.  thetaS[m] = thetaS4a(nuc[m]) (stepGlideSup L5846): nuc==NUC_ADPPI(2)
    //   ⇒ PRESTROKE_THETAS(−30°) else ADP_THETAS(+30°). Writes pose4[2N+m]. Runs after chemistry, before place.
    //   cockP[0]=PRESTROKE_THETAS, cockP[1]=ADP_THETAS.
    // ===============================================================================================
    public static void matCock(IntArray nucState, DoubleArray pose4, DoubleArray cockP, IntArray counts) {
        int N = counts.get(0);
        double pre = cockP.get(0), adp = cockP.get(1);
        for (@Parallel int m = 0; m < N; m++) pose4.set(2 * N + m, nucState.get(m) == 2 ? pre : adp);
    }

    // --- reinterpret-free double helpers for matStep7 (mirror the validated calibratedStep substitutions) ---
    static double log1pC(double x) { double u = 1.0 + x; return (u == 1.0) ? x : Math.log(u) * (x / (u - 1.0)); }
    static double softposD(double x, double s) {
        if (s <= 0) return (0.0 >= x ? 0.0 : x);
        double z = x / s; if (z > 30) return x; if (z < -30) return s * Math.exp(z);
        return s * log1pC(Math.exp(z));
    }
    static double softpos_dD(double x, double s) {
        if (s <= 0) return x > 0 ? 1 : 0;
        double z = x / s; if (z > 30) return 1; if (z < -30) return Math.exp(z);
        return 1.0 / (1.0 + Math.exp(-z));
    }
    /** brownTorque — EXACT double wang-hash copy (TwoBodyConverterMotor.brownTorque L2174); ep=seed, t=step. */
    static double brownTorqueD(double gamma, double dt, long ep, long t, long salt) {
        long h = ((ep * 2654435761L) ^ (t * 40503L) ^ (salt * 0x9E3779B1L));
        h ^= (h >>> 13); h *= 0x9E3779B1L; h ^= (h >>> 16);
        double u1 = ((h & 0xFFFFFF) + 1) / 16777217.0;
        h ^= (h << 7);
        double u2 = (((h >>> 8) & 0xFFFFFF) + 1) / 16777217.0;
        double g = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
        return Math.sqrt(2 * Constants.kT * gamma / dt) * g;
    }

    // ===============================================================================================
    // KERNEL — Stage 7: matStep7 (DOUBLE, MAT salts).  Faithful port of supSolveM (L5791): analytic 5-DOF
    //   movable-pivot solve — geomC geometry + supForceM softplus tail (compensated log1pC) + kfSI·JᵀJ +
    //   converter/bind springs + diagonal γ/dt tangent + 5 brownTorque draws (MAT salts 0x5F1..0x5F5+m·7919L)
    //   + hand-unrolled 5×5 Gauss–Jordan. E=econv (calibrated Jacobian axis). No double[][]/alloc.
    //   pose4: phi=[m] psi=[N+m] thetaS=[2N+m] psiActin=[3N+m].  anchor: 3N (pivot P; updated).
    //   sp: 0..8 bhat/econv/eup, 9 lb,10 rF8x,11 rF8y,12 rCx,13 rCy, 14 kF8Code,15 kconv,16 kbind,
    //       17 supGammaP,18 gammaPhi,19 gammaPsi,20 dt, 21 ksoftAx,22 ktautAx,23 delta,24 ksoftTr,25 kfeTr,
    //       26 rMax,27 kfloor,28 smoothAx,29 smoothTr,30 compFrac,31 floorZ,32 buckleCrit,33 kcompPost,34 smoothBuck.
    //   supP0: 3N.  bondData: 13N (F8h=[13m..13m+2], forceDotFil=[13m+12]).  boundSeg: [m] (bound iff ≥0).
    //   OUT: anchor,pose4(phi,psi) updated; geomOut(9N) C/xF8/xH; forceOut: forceDotFil=[m] forceMag=[N+m].
    // ===============================================================================================
    public static void matStep7(DoubleArray pose4, DoubleArray anchor, DoubleArray sp, DoubleArray supP0,
                                FloatArray bondData, IntArray boundSeg, IntArray active, IntArray counts,
                                DoubleArray geomOut, FloatArray forceDotFilOut, FloatArray forceMagOut) {
        int N = counts.get(0);
        int tt = counts.get(1), seed = counts.get(2);
        double bx = sp.get(0), by = sp.get(1), bz = sp.get(2);
        double ex = sp.get(3), ey = sp.get(4), ez = sp.get(5);
        double ux = sp.get(6), uy = sp.get(7), uz = sp.get(8);
        double lb = sp.get(9), rF8x = sp.get(10), rF8y = sp.get(11), rCx = sp.get(12), rCy = sp.get(13);
        double kF8Code = sp.get(14), kc = sp.get(15), kb = sp.get(16);
        double gP = sp.get(17), gPhi = sp.get(18), gPsi = sp.get(19), dt = sp.get(20);
        double ksoftAx = sp.get(21), ktautAx = sp.get(22), supDelta = sp.get(23), ksoftTr = sp.get(24), kfeTr = sp.get(25);
        double supRmax = sp.get(26), supKfloor = sp.get(27), smoothAx = sp.get(28), smoothTr = sp.get(29);
        double compFrac = sp.get(30), floorZ = sp.get(31), buckleCrit = sp.get(32), kcompPost = sp.get(33), smoothBuck = sp.get(34);
        for (@Parallel int m = 0; m < N; m++) {
            double phi = pose4.get(m), psi = pose4.get(N + m), thetaS = pose4.get(2 * N + m), psiActin = pose4.get(3 * N + m);
            double Px = anchor.get(m), Py = anchor.get(N + m), Pz = anchor.get(2 * N + m);
            boolean act = active.get(m) == 1;   // host: supSolveM runs only for active motors (else pose frozen, forces 0)
            boolean bound = boundSeg.get(m) >= 0;
            double f8x = 0, f8y = 0, f8z = 0;
            if (bound) { f8x = bondData.get(13 * m); f8y = bondData.get(13 * m + 1); f8z = bondData.get(13 * m + 2); }
            double cphi = Math.cos(phi), sphi = Math.sin(phi);
            double uBx = ux * cphi + bx * sphi, uBy = uy * cphi + by * sphi, uBz = uz * cphi + bz * sphi;
            double Cx = Px + uBx * lb, Cy = Py + uBy * lb, Cz = Pz + uBz * lb;
            double cpsi = Math.cos(psi), spsi = Math.sin(psi);
            double d0x = bx * (rF8x - rCx) + ux * (rF8y - rCy), d0y = by * (rF8x - rCx) + uy * (rF8y - rCy), d0z = bz * (rF8x - rCx) + uz * (rF8y - rCy);
            double xF8x = Cx + (d0x * cpsi + (ey * d0z - ez * d0y) * spsi);
            double xF8y = Cy + (d0y * cpsi + (ez * d0x - ex * d0z) * spsi);
            double xF8z = Cz + (d0z * cpsi + (ex * d0y - ey * d0x) * spsi);
            double cpx = Cx - Px, cpy = Cy - Py, cpz = Cz - Pz;
            double fcx = xF8x - Cx, fcy = xF8y - Cy, fcz = xF8z - Cz;
            double Jphix = ey * cpz - ez * cpy, Jphiy = ez * cpx - ex * cpz, Jphiz = ex * cpy - ey * cpx;
            double Jpsix = ey * fcz - ez * fcy, Jpsiy = ez * fcx - ex * fcz, Jpsiz = ex * fcy - ey * fcx;
            double J03 = (Jphix * bx + Jphiy * by + Jphiz * bz) * 1e-6, J04 = (Jpsix * bx + Jpsiy * by + Jpsiz * bz) * 1e-6;
            double J13 = (Jphix * ex + Jphiy * ey + Jphiz * ez) * 1e-6, J14 = (Jpsix * ex + Jpsiy * ey + Jpsiz * ez) * 1e-6;
            double J23 = (Jphix * ux + Jphiy * uy + Jphiz * uz) * 1e-6, J24 = (Jpsix * ux + Jpsiy * uy + Jpsiz * uz) * 1e-6;
            double kfSI = kF8Code * 1e6;
            double K00 = kfSI, K11 = kfSI, K22 = kfSI;
            double K03 = kfSI * J03, K04 = kfSI * J04, K13 = kfSI * J13, K14 = kfSI * J14, K23 = kfSI * J23, K24 = kfSI * J24;
            double K33 = kfSI * (J03 * J03 + J13 * J13 + J23 * J23);
            double K34 = kfSI * (J03 * J04 + J13 * J14 + J23 * J24);
            double K44 = kfSI * (J04 * J04 + J14 * J14 + J24 * J24);
            double sp0x = supP0.get(m), sp0y = supP0.get(N + m), sp0z = supP0.get(2 * N + m);
            double dx = Px - sp0x, dy = Py - sp0y, dz = Pz - sp0z;
            double qL = dx * bx + dy * by + dz * bz;
            double dTx = dx - bx * qL, dTy = dy - by * qL, dTz = dz - bz * qL;
            double rT = Math.sqrt(dTx * dTx + dTy * dTy + dTz * dTz);
            double ksoft = ksoftAx * 1e6, ktaut = ktautAx * 1e6, ksoftTrD = ksoftTr * 1e6, kfeTrD = kfeTr * 1e6, kfloor = supKfloor * 1e6;
            double dm = supDelta * 1e-6, smA = smoothAx * 1e-6, smT = smoothTr * 1e-6, rMaxm = supRmax * 1e-6, qm = qL * 1e-6;
            double Frest, kAxTan;
            if (qm >= 0) { Frest = ksoft * qm + ktaut * softposD(qm - dm, smA); kAxTan = ksoft + ktaut * softpos_dD(qm - dm, smA); }
            else {
                double a = -qm;
                if (buckleCrit > 0) {
                    double k0 = ksoft + ktaut, kpost = kcompPost, acrit = buckleCrit / (1e-30 >= k0 ? 1e-30 : k0), sB = smoothBuck;
                    Frest = -(k0 * a - (k0 - kpost) * softposD(a - acrit, sB)); kAxTan = k0 - (k0 - kpost) * softpos_dD(a - acrit, sB);
                } else {
                    double kcc = compFrac; Frest = -(ksoft * kcc * a + ktaut * kcc * softposD(a - dm, smA));
                    kAxTan = ksoft * kcc + ktaut * kcc * softpos_dD(a - dm, smA);
                }
            }
            double Fsx = bx * (-Frest), Fsy = by * (-Frest), Fsz = bz * (-Frest);
            double kTrTan;
            if (rT > 1e-9) { double rTm = rT * 1e-6; double Frad = ksoftTrD * rTm + kfeTrD * softposD(rTm - rMaxm, smT);
                kTrTan = ksoftTrD + kfeTrD * softpos_dD(rTm - rMaxm, smT); Fsx += dTx * (-Frad / rT); Fsy += dTy * (-Frad / rT); Fsz += dTz * (-Frad / rT); }
            else kTrTan = ksoftTrD;
            double zoff = dx * ux + dy * uy + dz * uz; double kFloorTan = 0;
            if (zoff < -floorZ) { double pen = (-floorZ - zoff) * 1e-6; Fsx += ux * (kfloor * pen); Fsy += uy * (kfloor * pen); Fsz += uz * (kfloor * pen); kFloorTan = kfloor; }
            double aP = gP / dt, aphi = gPhi / dt, apsi = gPsi / dt;
            double a00 = K00 + kAxTan + aP, a01 = 0, a02 = 0, a03 = K03, a04 = K04;
            double a10 = 0, a11 = K11 + kTrTan + aP, a12 = 0, a13 = K13, a14 = K14;
            double a20 = 0, a21 = 0, a22 = K22 + (kTrTan + kFloorTan) + aP, a23 = K23, a24 = K24;
            double a30 = K03, a31 = K13, a32 = K23, a33 = (K33 + kc) + aphi, a34 = K34 - kc;
            double a40 = K04, a41 = K14, a42 = K24, a43 = K34 - kc, a44 = (K44 + (kc + kb)) + apsi;
            double th = psi - phi;
            double caF_x = cpy * f8z - cpz * f8y, caF_y = cpz * f8x - cpx * f8z, caF_z = cpx * f8y - cpy * f8x;
            double fcF_x = fcy * f8z - fcz * f8y, fcF_y = fcz * f8x - fcx * f8z, fcF_z = fcx * f8y - fcy * f8x;
            double QphiF8 = (ex * caF_x + ey * caF_y + ez * caF_z) * 1e-6;
            double QpsiF8 = (ex * fcF_x + ey * fcF_y + ez * fcF_z) * 1e-6;
            double a05 = (f8x * bx + f8y * by + f8z * bz) + (Fsx * bx + Fsy * by + Fsz * bz);
            double a15 = (f8x * ex + f8y * ey + f8z * ez) + (Fsx * ex + Fsy * ey + Fsz * ez);
            double a25 = (f8x * ux + f8y * uy + f8z * uz) + (Fsx * ux + Fsy * uy + Fsz * uz);
            double a35 = QphiF8 + kc * (th - thetaS);
            double a45 = QpsiF8 - kc * (th - thetaS) - kb * (psi - psiActin);
            a05 += brownTorqueD(gP, dt, seed, tt, 0x5F1L + (long) m * 7919L);
            a15 += brownTorqueD(gP, dt, seed, tt, 0x5F2L + (long) m * 7919L);
            a25 += brownTorqueD(gP, dt, seed, tt, 0x5F3L + (long) m * 7919L);
            a35 += brownTorqueD(gPhi, dt, seed, tt, 0x5F4L + (long) m * 7919L);
            a45 += brownTorqueD(gPsi, dt, seed, tt, 0x5F5L + (long) m * 7919L);
            int p; double best, tv, piv, fac, s;
            p = 0; best = dabs(a00);
            tv = dabs(a10); if (tv > best) { best = tv; p = 1; } tv = dabs(a20); if (tv > best) { best = tv; p = 2; }
            tv = dabs(a30); if (tv > best) { best = tv; p = 3; } tv = dabs(a40); if (tv > best) { best = tv; p = 4; }
            if (p == 1) { s=a00;a00=a10;a10=s; s=a01;a01=a11;a11=s; s=a02;a02=a12;a12=s; s=a03;a03=a13;a13=s; s=a04;a04=a14;a14=s; s=a05;a05=a15;a15=s; }
            else if (p == 2) { s=a00;a00=a20;a20=s; s=a01;a01=a21;a21=s; s=a02;a02=a22;a22=s; s=a03;a03=a23;a23=s; s=a04;a04=a24;a24=s; s=a05;a05=a25;a25=s; }
            else if (p == 3) { s=a00;a00=a30;a30=s; s=a01;a01=a31;a31=s; s=a02;a02=a32;a32=s; s=a03;a03=a33;a33=s; s=a04;a04=a34;a34=s; s=a05;a05=a35;a35=s; }
            else if (p == 4) { s=a00;a00=a40;a40=s; s=a01;a01=a41;a41=s; s=a02;a02=a42;a42=s; s=a03;a03=a43;a43=s; s=a04;a04=a44;a44=s; s=a05;a05=a45;a45=s; }
            piv = a00;
            fac = a10 / piv; a10 -= fac*a00; a11 -= fac*a01; a12 -= fac*a02; a13 -= fac*a03; a14 -= fac*a04; a15 -= fac*a05;
            fac = a20 / piv; a20 -= fac*a00; a21 -= fac*a01; a22 -= fac*a02; a23 -= fac*a03; a24 -= fac*a04; a25 -= fac*a05;
            fac = a30 / piv; a30 -= fac*a00; a31 -= fac*a01; a32 -= fac*a02; a33 -= fac*a03; a34 -= fac*a04; a35 -= fac*a05;
            fac = a40 / piv; a40 -= fac*a00; a41 -= fac*a01; a42 -= fac*a02; a43 -= fac*a03; a44 -= fac*a04; a45 -= fac*a05;
            p = 1; best = dabs(a11); tv = dabs(a21); if (tv > best) { best = tv; p = 2; } tv = dabs(a31); if (tv > best) { best = tv; p = 3; } tv = dabs(a41); if (tv > best) { best = tv; p = 4; }
            if (p == 2) { s=a10;a10=a20;a20=s; s=a11;a11=a21;a21=s; s=a12;a12=a22;a22=s; s=a13;a13=a23;a23=s; s=a14;a14=a24;a24=s; s=a15;a15=a25;a25=s; }
            else if (p == 3) { s=a10;a10=a30;a30=s; s=a11;a11=a31;a31=s; s=a12;a12=a32;a32=s; s=a13;a13=a33;a33=s; s=a14;a14=a34;a34=s; s=a15;a15=a35;a35=s; }
            else if (p == 4) { s=a10;a10=a40;a40=s; s=a11;a11=a41;a41=s; s=a12;a12=a42;a42=s; s=a13;a13=a43;a43=s; s=a14;a14=a44;a44=s; s=a15;a15=a45;a45=s; }
            piv = a11;
            fac = a01 / piv; a01 -= fac*a11; a02 -= fac*a12; a03 -= fac*a13; a04 -= fac*a14; a05 -= fac*a15;
            fac = a21 / piv; a21 -= fac*a11; a22 -= fac*a12; a23 -= fac*a13; a24 -= fac*a14; a25 -= fac*a15;
            fac = a31 / piv; a31 -= fac*a11; a32 -= fac*a12; a33 -= fac*a13; a34 -= fac*a14; a35 -= fac*a15;
            fac = a41 / piv; a41 -= fac*a11; a42 -= fac*a12; a43 -= fac*a13; a44 -= fac*a14; a45 -= fac*a15;
            p = 2; best = dabs(a22); tv = dabs(a32); if (tv > best) { best = tv; p = 3; } tv = dabs(a42); if (tv > best) { best = tv; p = 4; }
            if (p == 3) { s=a20;a20=a30;a30=s; s=a21;a21=a31;a31=s; s=a22;a22=a32;a32=s; s=a23;a23=a33;a33=s; s=a24;a24=a34;a34=s; s=a25;a25=a35;a35=s; }
            else if (p == 4) { s=a20;a20=a40;a40=s; s=a21;a21=a41;a41=s; s=a22;a22=a42;a42=s; s=a23;a23=a43;a43=s; s=a24;a24=a44;a44=s; s=a25;a25=a45;a45=s; }
            piv = a22;
            fac = a02 / piv; a02 -= fac*a22; a03 -= fac*a23; a04 -= fac*a24; a05 -= fac*a25;
            fac = a12 / piv; a12 -= fac*a22; a13 -= fac*a23; a14 -= fac*a24; a15 -= fac*a25;
            fac = a32 / piv; a32 -= fac*a22; a33 -= fac*a23; a34 -= fac*a24; a35 -= fac*a25;
            fac = a42 / piv; a42 -= fac*a22; a43 -= fac*a23; a44 -= fac*a24; a45 -= fac*a25;
            p = 3; best = dabs(a33); tv = dabs(a43); if (tv > best) { best = tv; p = 4; }
            if (p == 4) { s=a30;a30=a40;a40=s; s=a31;a31=a41;a41=s; s=a32;a32=a42;a42=s; s=a33;a33=a43;a43=s; s=a34;a34=a44;a44=s; s=a35;a35=a45;a45=s; }
            piv = a33;
            fac = a03 / piv; a03 -= fac*a33; a04 -= fac*a34; a05 -= fac*a35;
            fac = a13 / piv; a13 -= fac*a33; a14 -= fac*a34; a15 -= fac*a35;
            fac = a23 / piv; a23 -= fac*a33; a24 -= fac*a34; a25 -= fac*a35;
            fac = a43 / piv; a43 -= fac*a33; a44 -= fac*a34; a45 -= fac*a35;
            piv = a44;
            fac = a04 / piv; a04 -= fac*a44; a05 -= fac*a45;
            fac = a14 / piv; a14 -= fac*a44; a15 -= fac*a45;
            fac = a24 / piv; a24 -= fac*a44; a25 -= fac*a45;
            fac = a34 / piv; a34 -= fac*a44; a35 -= fac*a45;
            double dqB = a05 / a00, dqE = a15 / a11, dqU = a25 / a22, dqPhi = a35 / a33, dqPsi = a45 / a44;
            if (act) {   // apply the pivot/angle update only for active motors (inactive stay frozen — host stepGlideSup)
                Px += (bx * dqB + ex * dqE + ux * dqU) * 1e6; Py += (by * dqB + ey * dqE + uy * dqU) * 1e6; Pz += (bz * dqB + ez * dqE + uz * dqU) * 1e6;
                phi += dqPhi; psi += dqPsi;
            }
            double c2 = Math.cos(phi), s2 = Math.sin(phi);
            double uBx2 = ux * c2 + bx * s2, uBy2 = uy * c2 + by * s2, uBz2 = uz * c2 + bz * s2;
            double Cx2 = Px + uBx2 * lb, Cy2 = Py + uBy2 * lb, Cz2 = Pz + uBz2 * lb;
            double cp2 = Math.cos(psi), sp2 = Math.sin(psi);
            double e0x = bx * (rF8x - rCx) + ux * (rF8y - rCy), e0y = by * (rF8x - rCx) + uy * (rF8y - rCy), e0z = bz * (rF8x - rCx) + uz * (rF8y - rCy);
            double xF8x2 = Cx2 + (e0x * cp2 + (ey * e0z - ez * e0y) * sp2);
            double xF8y2 = Cy2 + (e0y * cp2 + (ez * e0x - ex * e0z) * sp2);
            double xF8z2 = Cz2 + (e0z * cp2 + (ex * e0y - ey * e0x) * sp2);
            double r0x = bx * rCx + ux * rCy, r0y = by * rCx + uy * rCy, r0z = bz * rCx + uz * rCy;
            double xHx = Cx2 - (r0x * cp2 + (ey * r0z - ez * r0y) * sp2);
            double xHy = Cy2 - (r0y * cp2 + (ez * r0x - ex * r0z) * sp2);
            double xHz = Cz2 - (r0z * cp2 + (ex * r0y - ey * r0x) * sp2);
            anchor.set(m, Px); anchor.set(N + m, Py); anchor.set(2 * N + m, Pz);
            pose4.set(m, phi); pose4.set(N + m, psi);
            geomOut.set(m, Cx2); geomOut.set(N + m, Cy2); geomOut.set(2 * N + m, Cz2);
            geomOut.set(3 * N + m, xF8x2); geomOut.set(4 * N + m, xF8y2); geomOut.set(5 * N + m, xF8z2);
            geomOut.set(6 * N + m, xHx); geomOut.set(7 * N + m, xHy); geomOut.set(8 * N + m, xHz);
            if (act && bound) { forceDotFilOut.set(m, bondData.get(13 * m + 12)); forceMagOut.set(m, (float) Math.sqrt(f8x * f8x + f8y * f8y + f8z * f8z)); }
            else { forceDotFilOut.set(m, 0f); forceMagOut.set(m, 0f); }
        }
    }

    // ===============================================================================================
    // KERNEL — Stage 2: matGeomGate (DOUBLE, bit-for-decision).  geom2D → nearestSeg2D → gate2D → 8-gate AND.
    //   pose: phi=[m], psi=[N+m], psiActin=[2N+m].  anchor: planar 3N.
    //   filCoord/filUVec: planar 3*nSeg (X=[s],Y=[nSeg+s],Z=[2nSeg+s]).  filSegLen: [s].
    //   params: bhat(0..2) econv(3..5) eup(6..8) lb(9) rF8x(10) rF8y(11) rCx(12) rCy(13) FIL_R(14) A_SEMI2(15)
    //           kF8Code(16) kconvCode(17) kbindCode(18) PHI_PRE(19) kT(20) prestrokeThetaS(21)
    //           dBindNm(22) psiDeg(23) phiDeg(24) thetaDeg(25) preloadPn(26) energyKt(27) margin(28) nearMargin(29)
    //   geomOut: Cx=[m] Cy=[N+m] Cz=[2N+m] xF8x=[3N+m] xF8y=[4N+m] xF8z=[5N+m] xHx=[6N+m] xHy=[7N+m] xHz=[8N+m]
    //   candInt: candSeg=[m] (-1 if none) candAccept=[N+m] (0/1).  candBindArc: [m].
    // ===============================================================================================
    public static void matGeomGate(DoubleArray anchor, DoubleArray pose,
                                   FloatArray filCoord, FloatArray filUVec, FloatArray filSegLen,
                                   DoubleArray params, IntArray counts,
                                   DoubleArray geomOut, IntArray candInt, DoubleArray candBindArc) {
        int N = counts.get(0), nSeg = counts.get(3);
        double bx = params.get(0), by = params.get(1), bz = params.get(2);
        double ex = params.get(3), ey = params.get(4), ez = params.get(5);
        double ux0 = params.get(6), uy0 = params.get(7), uz0 = params.get(8);
        double lb = params.get(9), rF8x = params.get(10), rF8y = params.get(11), rCx = params.get(12), rCy = params.get(13);
        double FIL_R = params.get(14), A2 = params.get(15), kF8 = params.get(16), kc = params.get(17), kb = params.get(18);
        double PHI_PRE = params.get(19), kT = params.get(20), pth = params.get(21);
        double dBind = params.get(22), psiDeg = params.get(23), phiDeg = params.get(24), thetaDeg = params.get(25);
        double preloadPn = params.get(26), energyKt = params.get(27), margin = params.get(28), nearMargin = params.get(29);
        for (@Parallel int m = 0; m < N; m++) {
            double phi = pose.get(m), psi = pose.get(N + m), psiAct = pose.get(3 * N + m);   // pose4 layout: φ[m] ψ[N+m] θs[2N+m] ψa[3N+m]
            double Ax = anchor.get(m), Ay = anchor.get(N + m), Az = anchor.get(2 * N + m);
            // --- geom2D ---
            double cphi = Math.cos(phi), sphi = Math.sin(phi);
            double uBx = ux0 * cphi + bx * sphi, uBy = uy0 * cphi + by * sphi, uBz = uz0 * cphi + bz * sphi;
            double Cx = Ax + uBx * lb, Cy = Ay + uBy * lb, Cz = Az + uBz * lb;
            double cpsi = Math.cos(psi), spsi = Math.sin(psi);
            double d0x = bx * (rF8x - rCx) + ux0 * (rF8y - rCy), d0y = by * (rF8x - rCx) + uy0 * (rF8y - rCy), d0z = bz * (rF8x - rCx) + uz0 * (rF8y - rCy);
            double xF8x = Cx + (d0x * cpsi + (ey * d0z - ez * d0y) * spsi);
            double xF8y = Cy + (d0y * cpsi + (ez * d0x - ex * d0z) * spsi);
            double xF8z = Cz + (d0z * cpsi + (ex * d0y - ey * d0x) * spsi);
            double r0x = bx * rCx + ux0 * rCy, r0y = by * rCx + uy0 * rCy, r0z = bz * rCx + uz0 * rCy;
            double xHx = Cx - (r0x * cpsi + (ey * r0z - ez * r0y) * spsi);
            double xHy = Cy - (r0y * cpsi + (ez * r0x - ex * r0z) * spsi);
            double xHz = Cz - (r0z * cpsi + (ex * r0y - ey * r0x) * spsi);
            geomOut.set(m, Cx); geomOut.set(N + m, Cy); geomOut.set(2 * N + m, Cz);
            geomOut.set(3 * N + m, xF8x); geomOut.set(4 * N + m, xF8y); geomOut.set(5 * N + m, xF8z);
            geomOut.set(6 * N + m, xHx); geomOut.set(7 * N + m, xHy); geomOut.set(8 * N + m, xHz);
            // --- nearestSeg2D over xF8 ---
            int best = -1; double bd = 1e9;
            for (int s = 0; s < nSeg; s++) {
                double half = 0.5 * (double) filSegLen.get(s);
                double cx = filCoord.get(s), cy = filCoord.get(nSeg + s), cz = filCoord.get(2 * nSeg + s);
                double su = filUVec.get(s), sv = filUVec.get(nSeg + s), sw = filUVec.get(2 * nSeg + s);
                double dx = xF8x - cx, dy = xF8y - cy, dz = xF8z - cz;
                double foot = dx * su + dy * sv + dz * sw;
                if (dabs(foot) > half + 0.02) continue;
                double px = dx - foot * su, py = dy - foot * sv, pz = dz - foot * sw;
                double d2 = px * px + py * py + pz * pz;
                if (d2 < bd) { bd = d2; best = s; }
            }
            int accept = 0; double bindArc = 0;
            if (best >= 0) {
                // --- gate2D on 'best' ---
                double half = 0.5 * (double) filSegLen.get(best);
                double cx = filCoord.get(best), cy = filCoord.get(nSeg + best), cz = filCoord.get(2 * nSeg + best);
                double su = filUVec.get(best), sv = filUVec.get(nSeg + best), sw = filUVec.get(2 * nSeg + best);
                double e1x = cx - half * su, e1y = cy - half * sv, e1z = cz - half * sw;
                double foot = (xF8x - cx) * su + (xF8y - cy) * sv + (xF8z - cz) * sw;
                double axx = cx + foot * su, axy = cy + foot * sv, axz = cz + foot * sw;
                double conDist = Math.sqrt((xF8x - axx) * (xF8x - axx) + (xF8y - axy) * (xF8y - axy) + (xF8z - axz) * (xF8z - axz));
                bindArc = (xF8x - e1x) * su + (xF8y - e1y) * sv + (xF8z - e1z) * sw;
                double surf = (conDist - FIL_R) * 1e3;
                double psiErr = deg(dabs(psi - psiAct));
                double phiErr = deg(dabs(phi - PHI_PRE));
                double th = (psi - phi) - pth;
                double thetaErr = deg(dabs(th));
                double preload = kF8 * conDist * 1e12;
                double eKt = (0.5 * kc * (th * th) + 0.5 * kb * ((psi - psiAct) * (psi - psiAct))) / kT;
                double headSide = ((xHx - cx) * ux0 + (xHy - cy) * uy0 + (xHz - cz) * uz0) * 1e3;
                boolean g0 = surf < dBind, g1 = psiErr < psiDeg, g2 = phiErr < phiDeg, g3 = thetaErr < thetaDeg;
                boolean g4 = preload < preloadPn, g5 = eKt < energyKt, g6 = headSide < A2 * 1e3, g7 = bindArc > margin && bindArc < 2 * half - margin;
                if (g0 && g1 && g2 && g3 && g4 && g5 && g6 && g7) accept = 1;
            }
            candInt.set(m, best); candInt.set(N + m, accept); candBindArc.set(m, bindArc);
        }
    }

    // ===============================================================================================
    // KERNEL — Stage 5 bridge: matPlaceHead. Mirrors placeHead2D (L4512): head center = xH, uVec =
    //   normalize(xF8-xH), yVec = perp3(uVec). Reads mat geomOut (DOUBLE) → writes MotorStore.body (FLOAT)
    //   at h=3m+2 (nB=3N) so the existing float bondForces consumes it. Active-guarded.
    // ===============================================================================================
    public static void matPlaceHead(DoubleArray geomOut, IntArray active, DoubleArray eupP, IntArray counts,
                                    FloatArray motCoord, FloatArray motUVec, FloatArray motYVec) {
        int N = counts.get(0), nB = 3 * N;
        double eupx = eupP.get(0), eupy = eupP.get(1), eupz = eupP.get(2);
        for (@Parallel int m = 0; m < N; m++) {
            if (active.get(m) != 1) continue;
            int h = 3 * m + 2;
            double xHx = geomOut.get(6 * N + m), xHy = geomOut.get(7 * N + m), xHz = geomOut.get(8 * N + m);
            double dx = geomOut.get(3 * N + m) - xHx, dy = geomOut.get(4 * N + m) - xHy, dz = geomOut.get(5 * N + m) - xHz;
            double L = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double uvx, uvy, uvz;
            if (L > 1e-12) { uvx = dx / L; uvy = dy / L; uvz = dz / L; } else { uvx = eupx; uvy = eupy; uvz = eupz; }
            double ax = dabs(uvx) < 0.9 ? 1 : 0, ay = dabs(uvx) < 0.9 ? 0 : 1, az = 0;
            double dd = ax * uvx + ay * uvy + az * uvz;
            double yx = ax - dd * uvx, yy = ay - dd * uvy, yz = az - dd * uvz;
            double yl = Math.sqrt(yx * yx + yy * yy + yz * yz);
            yx /= yl; yy /= yl; yz /= yl;
            motCoord.set(h, (float) xHx); motCoord.set(nB + h, (float) xHy); motCoord.set(2 * nB + h, (float) xHz);
            motUVec.set(h, (float) uvx); motUVec.set(nB + h, (float) uvy); motUVec.set(2 * nB + h, (float) uvz);
            motYVec.set(h, (float) yx); motYVec.set(nB + h, (float) yy); motYVec.set(2 * nB + h, (float) yz);
        }
    }

    // KERNEL — Stage 8 bridge: matZConfine. forceSum[2·nSeg+s] -= kzCode·coordZ(s) (stepGlideSup L5855). zP[0]=kzCode.
    public static void matZConfine(FloatArray filCoord, FloatArray filForceSum, FloatArray zP, IntArray counts) {
        int nSeg = counts.get(3);
        float kz = zP.get(0);
        for (@Parallel int s = 0; s < nSeg; s++) { int iz = 2 * nSeg + s; filForceSum.set(iz, filForceSum.get(iz) - kz * filCoord.get(iz)); }
    }

    // KERNEL — Stage 9: matReduce. Single-thread reduced measurements: [0]=nBound [1..3]=COM [4]=Σ forceDotFil [5]=nActive.
    public static void matReduce(IntArray boundSeg, IntArray active, FloatArray forceDotFil, FloatArray filCoord,
                                 IntArray counts, DoubleArray redOut) {
        int N = counts.get(0), nSeg = counts.get(3);
        for (@Parallel int r = 0; r < 1; r++) {
            int nb = 0, na = 0; double load = 0;
            for (int m = 0; m < N; m++) { if (boundSeg.get(m) >= 0) { nb++; load += forceDotFil.get(m); } if (active.get(m) == 1) na++; }
            double cx = 0, cy = 0, cz = 0;
            for (int s = 0; s < nSeg; s++) { cx += filCoord.get(s); cy += filCoord.get(nSeg + s); cz += filCoord.get(2 * nSeg + s); }
            redOut.set(0, nb); redOut.set(1, cx / nSeg); redOut.set(2, cy / nSeg); redOut.set(3, cz / nSeg); redOut.set(4, load); redOut.set(5, na);
        }
    }

    // ===============================================================================================
    public static void main(String[] args) {
        Path dir = Path.of(OUTDIR);
        try { Files.createDirectories(dir); } catch (IOException ex) { throw new UncheckedIOException(ex); }
        boolean bailoutOff = "false".equals(System.getProperty("tornado.recover.bailout"));
        if (!bailoutOff) System.out.println("!! WARNING: run with -Dtornado.recover.bailout=false — else a lowering failure SILENTLY falls back to CPU.");
        System.out.println("=== MAT-SOA VERTICAL SLICE (calibrated) — Part 5 isolated stage gates ===");
        StringBuilder log = new StringBuilder();
        log.append("# MAT-SOA VERTICAL SLICE — isolated stage gates (calibrated, RTX 5070 / PTX)\n");
        log.append("# bailout disabled: ").append(bailoutOff).append("  | device mem start ").append(gpuMemUsed()).append(" MiB\n\n");

        boolean compose = false, traj = false; for (String a : args) { if (a.equals("-compose")) compose = true; if (a.equals("-traj")) traj = true; }
        if (compose) { boolean ok = compositionProbe(log, dir); try { Files.writeString(dir.resolve("COMPOSITION_PROBE.md"), log.toString()); } catch (IOException ex) { throw new UncheckedIOException(ex); } System.exit(ok ? 0 : 1); return; }
        if (traj) {
            System.out.println("=== PART 6 — device-resident calibrated GPU trajectory (bailout=false) ===");
            boolean ok = trajectory(log, 200, 11, 3000);           // small case first
            if (ok) ok &= trajectory(log, 700, 11, 2000);          // production-like density
            try { Files.writeString(dir.resolve("TRAJECTORY.md"), log.toString()); } catch (IOException ex) { throw new UncheckedIOException(ex); }
            System.out.println("# report: " + dir.resolve("TRAJECTORY.md").toAbsolutePath());
            System.exit(ok ? 0 : 1); return;
        }

        boolean cullOk = gateCull(log);
        boolean geomOk = gateGeomGate(log);
        boolean bindOk = gateBind(log);
        boolean step7Ok = gateStep7(log);
        boolean bridgeOk = gateBridges(log);

        try { Files.writeString(dir.resolve("STAGE_GATES.md"), log.toString()); } catch (IOException ex) { throw new UncheckedIOException(ex); }
        System.out.println("\n=== SLICE STATUS ===");
        System.out.printf(Locale.US, "Stage 1 matCull      (active-set identity):        %s%n", cullOk ? "PASS" : "FAIL");
        System.out.printf(Locale.US, "Stage 2 matGeomGate  (geom+nearest+gate identity): %s%n", geomOk ? "PASS" : "FAIL");
        System.out.printf(Locale.US, "Stage 3 matBind      (bind-event identity):        %s%n", bindOk ? "PASS" : "FAIL");
        System.out.printf(Locale.US, "Stage 7 matStep7     (5-DOF solve vs supSolveM):   %s%n", step7Ok ? "PASS" : "FAIL");
        System.out.printf(Locale.US, "Bridges placeHead/zConfine/reduce:                 %s%n", bridgeOk ? "PASS" : "FAIL");
        System.out.println("# report: " + dir.resolve("STAGE_GATES.md").toAbsolutePath());
        System.exit(cullOk && geomOk && bindOk && step7Ok && bridgeOk ? 0 : 1);
    }

    // ================================================================================================
    //  PART 6 — device-resident multi-step calibrated trajectory + stepwise CPU-vs-GPU comparison.
    //  The device single-graph = the mat-kernel PIPELINE; the CPU reference = the SAME methods as plain
    //  loops ("one impl two runners"). Both from identical IC ⇒ differ only by GPU-FMA (Stage-7 finding).
    // ================================================================================================
    static final class MatState {
        DoubleArray site, pose4, anchor, supP0, geomOut, candArc, redOut, eupP, cullP, gateP, step7P, cockP;
        IntArray active, noBind, candInt, mc;
        FloatArray zP;
    }
    static MatState packMat(TwoBodyConverterMotor.Glide2D G) {
        int N = G.N, nSeg = G.nSeg; MatState s = new MatState();
        s.site = new DoubleArray(2 * N); s.pose4 = new DoubleArray(4 * N); s.anchor = new DoubleArray(3 * N);
        s.supP0 = new DoubleArray(3 * N); s.geomOut = new DoubleArray(9 * N); s.candArc = new DoubleArray(N); s.redOut = new DoubleArray(6);
        s.eupP = DoubleArray.fromElements(G.eup[0], G.eup[1], G.eup[2]);
        s.active = new IntArray(N); s.noBind = new IntArray(N); s.candInt = new IntArray(2 * N);
        for (int m = 0; m < N; m++) {
            s.site.set(m, G.siteX[m]); s.site.set(N + m, G.siteY[m]);
            s.pose4.set(m, G.phi[m]); s.pose4.set(N + m, G.psi[m]); s.pose4.set(2 * N + m, G.thetaS[m]); s.pose4.set(3 * N + m, G.psiActin[m]);
            s.anchor.set(m, G.A[m][0]); s.anchor.set(N + m, G.A[m][1]); s.anchor.set(2 * N + m, G.A[m][2]);
            s.supP0.set(m, G.supP0[m][0]); s.supP0.set(N + m, G.supP0[m][1]); s.supP0.set(2 * N + m, G.supP0[m][2]);
            s.noBind.set(m, G.noBind[m] ? 1 : 0);
        }
        s.cullP = DoubleArray.fromElements(G.queryR * G.queryR); s.gateP = packGeomGateParams(G); s.step7P = packStep7Params(G);
        s.cockP = DoubleArray.fromElements(TwoBodyConverterMotor.PRESTROKE_THETAS, TwoBodyConverterMotor.ADP_THETAS);
        s.zP = FloatArray.fromElements((float) G.kzCode);
        s.mc = new IntArray(4); s.mc.set(0, N); s.mc.set(3, nSeg);
        return s;
    }
    /** CPU-runner step = the device pipeline as plain Java calls (same 20 kernels, same order). */
    static void stepMatCPU(TwoBodyConverterMotor.Glide2D G, MatState s, int t, int seed) {
        int nSeg = G.nSeg; MotorStore mot = G.mot; FilamentStore f = G.fil; RigidRodBody b = mot.body;
        s.mc.set(1, t); s.mc.set(2, seed);
        matCull(mot.boundSeg, s.site, f.coord, f.uVec, f.segLength, s.cullP, s.mc, s.active);
        matGeomGate(s.anchor, s.pose4, f.coord, f.uVec, f.segLength, s.gateP, s.mc, s.geomOut, s.candInt, s.candArc);
        matBind(s.active, s.noBind, mot.boundSeg, mot.nucleotideState, s.candInt, s.candArc, mot.bindArc, s.mc);
        mot.setCounts(t, seed, nSeg);
        NucleotideCycleSystem.cycleLymnTaylor(mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts);
        matCock(mot.nucleotideState, s.pose4, s.cockP, s.mc);
        matPlaceHead(s.geomOut, s.active, s.eupP, s.mc, b.coord, b.uVec, b.yVec);
        CrossBridgeSystem.bondForces(b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum, f.torqueSum, f.counts);
        CrossBridgeSystem.csrHistogram(mot.boundSeg, mot.counts, G.segCount);
        CrossBridgeSystem.csrScan(mot.counts, G.segCount, G.segOff);
        CrossBridgeSystem.csrScatter(mot.boundSeg, mot.counts, G.segOff, G.segCount, G.segMyo);
        CrossBridgeSystem.segGather(G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts);
        ChainBendingForceSystem.chainForces(f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts);
        matZConfine(f.coord, f.forceSum, s.zP, s.mc);
        f.counts.set(1, t); f.counts.set(2, seed);
        BrownianForceSystem.brownianForce(f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts);
        DerivedGeometrySystem.orthogonalizeY(f.uVec, f.yVec, f.counts);
        DerivedGeometrySystem.derive(f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts);
        matStep7(s.pose4, s.anchor, s.step7P, s.supP0, G.bondData, mot.boundSeg, s.active, s.mc, s.geomOut, mot.forceDotFil, mot.forceMag);
        matReduce(mot.boundSeg, s.active, mot.forceDotFil, f.coord, s.mc, s.redOut);
    }
    static GridScheduler trajSched;
    static TornadoExecutionPlan buildTrajGraph(TwoBodyConverterMotor.Glide2D G, MatState s) {
        MotorStore mot = G.mot; FilamentStore f = G.fil; RigidRodBody b = mot.body;
        TaskGraph tg = new TaskGraph("traj")
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                s.site, s.pose4, s.anchor, s.supP0, s.geomOut, s.candArc, s.redOut, s.eupP, s.active, s.noBind, s.candInt,
                s.cullP, s.gateP, s.step7P, s.cockP, s.zP,
                b.coord, b.uVec, b.yVec, b.bRotGam,
                f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.forceSum, f.torqueSum,
                f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.chainParams,
                f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                mot.bindArc, mot.forceMag, mot.forceDotAvg, mot.avgInit, mot.cooldown,
                mot.stats, mot.nucParams, mot.kinParams, G.bondData, G.xbParams, G.segCount, G.segOff, G.segMyo)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, s.mc, mot.counts, f.counts,
                mot.boundSeg, mot.nucleotideState, mot.forceDotFil, f.coord)   // VALIDATION reads these back each step (below)
            .task("matCull", MatSoaSlice::matCull, mot.boundSeg, s.site, f.coord, f.uVec, f.segLength, s.cullP, s.mc, s.active)
            .task("matGeomGate", MatSoaSlice::matGeomGate, s.anchor, s.pose4, f.coord, f.uVec, f.segLength, s.gateP, s.mc, s.geomOut, s.candInt, s.candArc)
            .task("matBind", MatSoaSlice::matBind, s.active, s.noBind, mot.boundSeg, mot.nucleotideState, s.candInt, s.candArc, mot.bindArc, s.mc)
            .task("chem", NucleotideCycleSystem::cycleLymnTaylor, mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts)
            .task("matCock", MatSoaSlice::matCock, mot.nucleotideState, s.pose4, s.cockP, s.mc)
            .task("matPlaceHead", MatSoaSlice::matPlaceHead, s.geomOut, s.active, s.eupP, s.mc, b.coord, b.uVec, b.yVec)
            .task("bondForces", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams)
            .task("zeroAcc", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
            .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, G.segCount)
            .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, G.segCount, G.segOff)
            .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, G.segOff, G.segCount, G.segMyo)
            .task("segGather", CrossBridgeSystem::segGather, G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts)
            .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
            .task("zconf", MatSoaSlice::matZConfine, f.coord, f.forceSum, s.zP, s.mc)
            .task("brown", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
            .task("integ", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
            .task("orthoY", DerivedGeometrySystem::orthogonalizeY, f.uVec, f.yVec, f.counts)
            .task("derive", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
            .task("matStep7", MatSoaSlice::matStep7, s.pose4, s.anchor, s.step7P, s.supP0, G.bondData, mot.boundSeg, s.active, s.mc, s.geomOut, mot.forceDotFil, mot.forceMag)
            .task("matReduce", MatSoaSlice::matReduce, mot.boundSeg, s.active, mot.forceDotFil, f.coord, s.mc, s.redOut)
            .transferToHost(DataTransferMode.EVERY_EXECUTION, s.redOut, mot.boundSeg, mot.nucleotideState, f.coord, f.uVec);
        int N = G.N, nSeg = G.nSeg, pn = ((N + 63) / 64) * 64, ps = ((nSeg + 63) / 64) * 64;
        trajSched = new GridScheduler();
        String[] pnT = {"matCull", "matGeomGate", "matBind", "chem", "matCock", "matPlaceHead", "bondForces", "matStep7"};
        for (String nm : pnT) addW(trajSched, "traj." + nm, pn);
        String[] psT = {"zeroAcc", "segGather", "chain", "zconf", "brown", "integ", "orthoY", "derive"};
        for (String nm : psT) addW(trajSched, "traj." + nm, ps);
        addW(trajSched, "traj.csrHist", 64); addW(trajSched, "traj.csrScan", 64); addW(trajSched, "traj.csrScatter", 64); addW(trajSched, "traj.matReduce", 64);
        return new TornadoExecutionPlan(tg.snapshot());
    }

    static boolean trajectory(StringBuilder log, double density, int seed, int steps) {
        double dt = 2.5e-6;
        System.out.printf(Locale.US, "%n--- Part 6 trajectory: density=%.0f seed=%d steps=%d (device single-graph vs CPU-runner) ---%n", density, seed, steps);
        log.append(String.format(Locale.US, "## Part-6 trajectory density=%.0f seed=%d steps=%d\n", density, seed, steps));
        TwoBodyConverterMotor.Glide2D Gd = TwoBodyConverterMotor.buildSupMat(density, dt, 0.0, seed);
        TwoBodyConverterMotor.Glide2D Gc = TwoBodyConverterMotor.buildSupMat(density, dt, 0.0, seed);   // identical IC
        int N = Gd.N, nSeg = Gd.nSeg;
        MatState sd = packMat(Gd), sc = packMat(Gc);
        TornadoExecutionPlan plan;
        try { plan = buildTrajGraph(Gd, sd); } catch (Throwable ex) { log.append("- graph build FAILED: " + oneLine(ex.getMessage()) + "\n"); System.out.println("  graph build FAILED"); return false; }
        int firstBoundDiv = -1, firstNucDiv = -1, firstNbDiv = -1; double maxComD = 0, maxFilD = 0;
        int t0BoundMis = -1, t0NucMis = -1, bMisAtFirst = 0; double filDbeforeFirst = 0, filDsoFar = 0;
        long tCold = 0;
        for (int t = 0; t < steps; t++) {
            sd.mc.set(1, t); Gd.mot.setCounts(t, seed, nSeg); Gd.fil.counts.set(1, t); Gd.fil.counts.set(2, seed);
            long t0 = System.nanoTime();
            try { plan.withGridScheduler(trajSched).execute(); } catch (Throwable ex) { Throwable r = ex; while (r.getCause() != null && r.getCause() != r) r = r.getCause(); log.append("- device execute FAILED @t=" + t + ": `" + r.getClass().getName() + "`: " + oneLine(r.getMessage()) + "\n"); System.out.println("  device execute FAILED @t=" + t + ": " + oneLine(r.getMessage())); return false; }
            if (t == 0) tCold = System.nanoTime() - t0;
            stepMatCPU(Gc, sc, t, seed);
            int nbD = (int) sd.redOut.get(0), nbC = (int) sc.redOut.get(0);
            if (nbD != nbC && firstNbDiv < 0) firstNbDiv = t;
            int boundMis = 0, nucMis = 0;
            for (int m = 0; m < N; m++) { if (Gd.mot.boundSeg.get(m) != Gc.mot.boundSeg.get(m)) boundMis++; if (Gd.mot.nucleotideState.get(m) != Gc.mot.nucleotideState.get(m)) nucMis++; }
            if (t == 0) { t0BoundMis = boundMis; t0NucMis = nucMis; }   // t=0: identical IC ⇒ any mismatch here is SEMANTIC
            if (boundMis > 0 && firstBoundDiv < 0) { firstBoundDiv = t; bMisAtFirst = boundMis; filDbeforeFirst = filDsoFar; }
            if (nucMis > 0 && firstNucDiv < 0) firstNucDiv = t;
            for (int i = 1; i <= 3; i++) maxComD = Math.max(maxComD, Math.abs(sd.redOut.get(i) - sc.redOut.get(i)));
            double filStep = 0; for (int i = 0; i < 3 * nSeg; i++) filStep = Math.max(filStep, Math.abs(Gd.fil.coord.get(i) - Gc.fil.coord.get(i)));
            filDsoFar = filStep; maxFilD = Math.max(maxFilD, filStep);
        }
        // RIGOROUS classification: at t=0 both runners see the IDENTICAL IC ⇒ identical discrete outputs. Any t=0
        // discrete mismatch = SEMANTIC (bridge/salt/order bug — HARD STOP). Divergence only at t≥1 (after matStep7's
        // ~1.68e-7 FMA pose drift feeds the next gate) = the EXPECTED float-FMA chaotic decorrelation (FINE).
        boolean t0Identical = (t0BoundMis == 0 && t0NucMis == 0);
        int firstDisc = min3(firstBoundDiv, firstNucDiv, firstNbDiv);
        boolean semantic = !t0Identical;
        String cls = !t0Identical ? "SEMANTIC — DISCRETE DIVERGENCE AT t=0 ON IDENTICAL IC (HARD STOP): boundMis=" + t0BoundMis + " nucMis=" + t0NucMis
                : firstDisc < 0 ? "no discrete divergence in " + steps + " steps (identical trajectory)"
                : "float-FMA chaotic decorrelation — t=0 IDENTICAL, first discrete divergence @t=" + firstDisc + " (boundMis=" + bMisAtFirst + " of " + N + ", filament drift before=" + String.format(Locale.US, "%.1e", filDbeforeFirst) + " µm)";
        System.out.printf(Locale.US, "  t=0 identical=%b | first div boundSeg@%d nuc@%d nBound@%d (boundMis@first=%d/%d) | maxComΔ=%.2e maxFilΔ=%.2e%n  %s | cold %.0f ms%n",
                t0Identical, firstBoundDiv, firstNucDiv, firstNbDiv, bMisAtFirst, N, maxComD, maxFilD, cls, tCold / 1e6);
        log.append(String.format(Locale.US, "- t=0 identical=%b; first div boundSeg@%d nuc@%d nBound@%d (boundMis@first=%d/%d, filDrift-before=%.1e µm); maxComΔ=%.2e maxFilΔ=%.2e; **%s**; cold %.0f ms\n",
                t0Identical, firstBoundDiv, firstNucDiv, firstNbDiv, bMisAtFirst, N, filDbeforeFirst, maxComD, maxFilD, cls, tCold / 1e6));
        log.append("- residency: FIRST_EXECUTION uploads once; per step only mc/counts up + redOut/boundSeg/nuc/coord DOWN (validation reads; production keeps only redOut). No full-mat UPLOAD/step.\n");
        return !semantic;
    }
    static int min3(int a, int b, int c) { int r = Integer.MAX_VALUE; if (a >= 0) r = Math.min(r, a); if (b >= 0) r = Math.min(r, b); if (c >= 0) r = Math.min(r, c); return r == Integer.MAX_VALUE ? -1 : r; }

    // ---------- Step 2 — COMPOSITION-RISK PROBE: does the full ~19-task double mat loop lower as a SINGLE graph? ----------
    static boolean compositionProbe(StringBuilder log, Path dir) {
        System.out.println("=== COMPOSITION-RISK PROBE — full calibrated mat loop as a SINGLE TaskGraph (bailout=false) ===");
        log.append("# COMPOSITION-RISK PROBE — full per-step calibrated mat loop, single TaskGraph\n\n");
        double dt = 2.5e-6; int seed = 11;
        TwoBodyConverterMotor.Glide2D G = TwoBodyConverterMotor.buildSupMat(200, dt, 0.0, seed);   // small: 1 filament, N=600
        int N = G.N, nSeg = G.nSeg; MotorStore mot = G.mot; FilamentStore f = G.fil; RigidRodBody b = mot.body;
        // warm a little so state is realistic (host)
        for (int tt = 0; tt < 200; tt++) TwoBodyConverterMotor.stepGlideSup(G, tt, seed, new TwoBodyConverterMotor.Tol());
        for (int m = 0; m < N; m++) TwoBodyConverterMotor.geom2D(G, m);
        // mat SoA (NOTE: probe tests LOWERING — pose3 for matGeomGate + pose4 for matStep7 are separate here;
        // a correct trajectory unifies phi/psi into one buffer + routes matStep7→mot.forceDotFil. See report.)
        DoubleArray site = new DoubleArray(2 * N), pose4 = new DoubleArray(4 * N),
                anchor = new DoubleArray(3 * N), supP0 = new DoubleArray(3 * N), geomOut = new DoubleArray(9 * N),
                candArc = new DoubleArray(N), redOut = new DoubleArray(6),
                eupP = DoubleArray.fromElements(G.eup[0], G.eup[1], G.eup[2]);
        IntArray active = new IntArray(N), noBind = new IntArray(N), candInt = new IntArray(2 * N);
        for (int m = 0; m < N; m++) {
            site.set(m, G.siteX[m]); site.set(N + m, G.siteY[m]);
            pose4.set(m, G.phi[m]); pose4.set(N + m, G.psi[m]); pose4.set(2 * N + m, G.thetaS[m]); pose4.set(3 * N + m, G.psiActin[m]);
            anchor.set(m, G.A[m][0]); anchor.set(N + m, G.A[m][1]); anchor.set(2 * N + m, G.A[m][2]);
            supP0.set(m, G.supP0[m][0]); supP0.set(N + m, G.supP0[m][1]); supP0.set(2 * N + m, G.supP0[m][2]);
            noBind.set(m, G.noBind[m] ? 1 : 0);
        }
        DoubleArray cullP = DoubleArray.fromElements(G.queryR * G.queryR);
        DoubleArray gateP = packGeomGateParams(G), step7P = packStep7Params(G);
        FloatArray zP = FloatArray.fromElements((float) G.kzCode);
        IntArray mc = new IntArray(4); mc.set(0, N); mc.set(1, 200); mc.set(2, seed); mc.set(3, nSeg);
        mot.setCounts(200, seed, nSeg); f.counts.set(1, 200); f.counts.set(2, seed);
        log.append("Tasks (19): matCull, matGeomGate, matBind, cycleLymnTaylor, matPlaceHead, bondForces, zeroAccumulators, ")
           .append("csrHistogram, csrScan, csrScatter, segGather, chainForces, matZConfine, brownianForce, integrate, ")
           .append("orthogonalizeY, derive, matStep7, matReduce. N=").append(N).append(" nSeg=").append(nSeg).append("\n\n");
        String memB = gpuMemUsed();
        try {
            TaskGraph tg = new TaskGraph("matloop")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    site, pose4, anchor, supP0, geomOut, candArc, redOut, eupP, active, noBind, candInt,
                    cullP, gateP, step7P, zP,
                    b.coord, b.uVec, b.yVec, b.bRotGam,
                    f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.forceSum, f.torqueSum,
                    f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.chainParams,
                    f.end1NbrSlot, f.end1NbrSide, f.end2NbrSlot, f.end2NbrSide,
                    mot.boundSeg, mot.bindArc, mot.nucleotideState, mot.forceDotFil, mot.forceMag, mot.forceDotAvg, mot.avgInit, mot.cooldown,
                    mot.stats, mot.nucParams, mot.kinParams, G.bondData, G.xbParams, G.segCount, G.segOff, G.segMyo)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, mc, mot.counts, f.counts)
                .task("matCull", MatSoaSlice::matCull, mot.boundSeg, site, f.coord, f.uVec, f.segLength, cullP, mc, active)
                .task("matGeomGate", MatSoaSlice::matGeomGate, anchor, pose4, f.coord, f.uVec, f.segLength, gateP, mc, geomOut, candInt, candArc)
                .task("matBind", MatSoaSlice::matBind, active, noBind, mot.boundSeg, mot.nucleotideState, candInt, candArc, mot.bindArc, mc)
                .task("chem", NucleotideCycleSystem::cycleLymnTaylor, mot.nucleotideState, mot.boundSeg, mot.forceDotFil, mot.forceDotAvg, mot.avgInit, mot.cooldown, mot.stats, mot.nucParams, mot.kinParams, mot.counts)
                .task("matPlaceHead", MatSoaSlice::matPlaceHead, geomOut, active, eupP, mc, b.coord, b.uVec, b.yVec)
                .task("bondForces", CrossBridgeSystem::bondForces, b.coord, b.uVec, b.yVec, b.bRotGam, f.coord, f.uVec, f.yVec, f.bRotGam, f.segLength, mot.boundSeg, mot.bindArc, mot.nucleotideState, G.bondData, G.xbParams)
                .task("zeroAcc", ChainBendingForceSystem::zeroAccumulators, f.forceSum, f.torqueSum, f.counts)
                .task("csrHist", CrossBridgeSystem::csrHistogram, mot.boundSeg, mot.counts, G.segCount)
                .task("csrScan", CrossBridgeSystem::csrScan, mot.counts, G.segCount, G.segOff)
                .task("csrScatter", CrossBridgeSystem::csrScatter, mot.boundSeg, mot.counts, G.segOff, G.segCount, G.segMyo)
                .task("segGather", CrossBridgeSystem::segGather, G.segOff, G.segMyo, G.bondData, f.forceSum, f.torqueSum, mot.counts)
                .task("chain", ChainBendingForceSystem::chainForces, f.coord, f.uVec, f.segLength, f.end2NbrSlot, f.end2NbrSide, f.end1NbrSlot, f.end1NbrSide, f.bTransGam, f.bRotGam, f.forceSum, f.torqueSum, f.chainParams, f.counts)
                .task("zconf", MatSoaSlice::matZConfine, f.coord, f.forceSum, zP, mc)
                .task("brown", BrownianForceSystem::brownianForce, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.brownTransScale, f.brownRotScale, f.params, f.counts)
                .task("integ", RigidRodLangevinIntegrationSystem::integrate, f.coord, f.uVec, f.yVec, f.forceSum, f.torqueSum, f.randForce, f.randTorque, f.bTransGam, f.bRotGam, f.params, f.counts)
                .task("orthoY", DerivedGeometrySystem::orthogonalizeY, f.uVec, f.yVec, f.counts)
                .task("derive", DerivedGeometrySystem::derive, f.coord, f.uVec, f.yVec, f.zVec, f.end1, f.end2, f.segLength, f.counts)
                .task("matStep7", MatSoaSlice::matStep7, pose4, anchor, step7P, supP0, G.bondData, mot.boundSeg, active, mc, geomOut, mot.forceDotFil, mot.forceMag)
                .task("matReduce", MatSoaSlice::matReduce, mot.boundSeg, active, mot.forceDotFil, f.coord, mc, redOut)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, redOut);
            GridScheduler sc = new GridScheduler();
            int pn = ((N + 63) / 64) * 64, ps = ((nSeg + 63) / 64) * 64;
            addW(sc, "matloop.matCull", pn); addW(sc, "matloop.matGeomGate", pn); addW(sc, "matloop.matBind", pn);
            addW(sc, "matloop.chem", pn); addW(sc, "matloop.matPlaceHead", pn); addW(sc, "matloop.bondForces", pn);
            addW(sc, "matloop.zeroAcc", ps); addW(sc, "matloop.csrHist", 64); addW(sc, "matloop.csrScan", 64);
            addW(sc, "matloop.csrScatter", 64); addW(sc, "matloop.segGather", ps); addW(sc, "matloop.chain", ps);
            addW(sc, "matloop.zconf", ps); addW(sc, "matloop.brown", ps); addW(sc, "matloop.integ", ps);
            addW(sc, "matloop.orthoY", ps); addW(sc, "matloop.derive", ps); addW(sc, "matloop.matStep7", pn); addW(sc, "matloop.matReduce", 64);
            TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot());
            long t0 = System.nanoTime();
            plan.withGridScheduler(sc).execute();
            double ms = (System.nanoTime() - t0) / 1e6;
            System.out.printf(Locale.US, "SINGLE-GRAPH: LOWERS+RUNS ✓ [%.0f ms cold, 19 tasks, N=%d]  redOut nBound=%.0f nActive=%.0f%n", ms, N, redOut.get(0), redOut.get(5));
            log.append(String.format(Locale.US, "**RESULT: the 19-task double mat loop LOWERS + RUNS as a SINGLE TaskGraph** [%.0f ms cold]. No Graph-resize. ", ms));
            log.append("Residency: all motor/filament SoA uploaded FIRST_EXECUTION; only mc/mot.counts/f.counts (small) EVERY_EXECUTION; only redOut (6 doubles) read back — NO full mat transfer/step. mem ").append(memB).append("→").append(gpuMemUsed()).append(" MiB.\n");
            return true;
        } catch (Throwable ex) {
            Throwable root = ex; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            boolean resize = String.valueOf(root.getMessage()).contains("resize") || String.valueOf(ex.getMessage()).contains("resize");
            System.out.printf(Locale.US, "SINGLE-GRAPH: %s — %s: %s%n", resize ? "GRAPH-RESIZE (needs chaining)" : "FAILED", root.getClass().getName(), oneLine(root.getMessage()));
            log.append(String.format(Locale.US, "**RESULT: single graph %s** — `%s`: %s\n", resize ? "hit Graph-resize (must split into chained TaskGraphs)" : "failed", root.getClass().getName(), oneLine(root.getMessage())));
            return false;
        }
    }
    static void addW(GridScheduler sc, String name, int global) { WorkerGrid w = new WorkerGrid1D(Math.max(1, global)); w.setLocalWork(64, 1, 1); sc.addWorkerGrid(name, w); }

    // ---------- Step 1 — bridge-kernel isolated gates (placeHead / zConfine / reduce) ----------
    static boolean gateBridges(StringBuilder log) {
        System.out.println("\n--- Bridge kernels: matPlaceHead / matZConfine / matReduce (CPU-vs-GPU) ---");
        log.append("## Bridge kernels (matPlaceHead, matZConfine, matReduce)\n");
        double dt = 2.5e-6; int seed = 11, warm = 1500;
        TwoBodyConverterMotor.Glide2D G = TwoBodyConverterMotor.buildSupMat(200, dt, 0.0, seed);
        int N = G.N, nSeg = G.nSeg; MotorStore mot = G.mot; FilamentStore f = G.fil;
        for (int tt = 0; tt < warm; tt++) TwoBodyConverterMotor.stepGlideSup(G, tt, seed, new TwoBodyConverterMotor.Tol());
        for (int m = 0; m < N; m++) TwoBodyConverterMotor.geom2D(G, m);
        TwoBodyConverterMotor.unionActive(G);
        int nB = 3 * N;
        // pack geomOut (from current C_/xF8_/xH_) + active
        DoubleArray geomOut = new DoubleArray(9 * N); IntArray active = new IntArray(N);
        for (int m = 0; m < N; m++) {
            geomOut.set(m, G.C_[m][0]); geomOut.set(N + m, G.C_[m][1]); geomOut.set(2 * N + m, G.C_[m][2]);
            geomOut.set(3 * N + m, G.xF8_[m][0]); geomOut.set(4 * N + m, G.xF8_[m][1]); geomOut.set(5 * N + m, G.xF8_[m][2]);
            geomOut.set(6 * N + m, G.xH_[m][0]); geomOut.set(7 * N + m, G.xH_[m][1]); geomOut.set(8 * N + m, G.xH_[m][2]);
            active.set(m, G.active[m] ? 1 : 0);
        }
        DoubleArray eupP = DoubleArray.fromElements(G.eup[0], G.eup[1], G.eup[2]);
        IntArray counts = new IntArray(4); counts.set(0, N); counts.set(1, warm); counts.set(2, seed); counts.set(3, nSeg);
        // --- host placeHead2D reference (into MotorStore.body clones) ---
        for (int m = 0; m < N; m++) if (G.active[m]) { TwoBodyConverterMotor.geom2D(G, m); TwoBodyConverterMotor.placeHead2D(G, m); }
        float[] hC = new float[3 * nB], hU = new float[3 * nB], hY = new float[3 * nB];
        for (int i = 0; i < 3 * nB; i++) { hC[i] = mot.body.coord.get(i); hU[i] = mot.body.uVec.get(i); hY[i] = mot.body.yVec.get(i); }
        // --- device placeHead into FRESH body arrays ---
        FloatArray dC = new FloatArray(3 * nB), dU = new FloatArray(3 * nB), dY = new FloatArray(3 * nB);
        for (int i = 0; i < 3 * nB; i++) { dC.set(i, mot.body.coord.get(i)); dU.set(i, mot.body.uVec.get(i)); dY.set(i, mot.body.yVec.get(i)); }
        boolean placeOk = true, zOk = true, redOk = true;
        try {
            TaskGraph tg = new TaskGraph("place").transferToDevice(DataTransferMode.FIRST_EXECUTION, geomOut, active, eupP)
                    .transferToDevice(DataTransferMode.EVERY_EXECUTION, counts)
                    .task("ph", MatSoaSlice::matPlaceHead, geomOut, active, eupP, counts, dC, dU, dY)
                    .transferToHost(DataTransferMode.EVERY_EXECUTION, dC, dU, dY);
            GridScheduler sc = new GridScheduler(); WorkerGrid w = new WorkerGrid1D(N); w.setLocalWork(1, 1, 1); sc.addWorkerGrid("place.ph", w);
            new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(sc).execute();
        } catch (Throwable ex) { placeOk = false; log.append("- matPlaceHead LOWERS=NO: " + oneLine(ex.getMessage()) + "\n"); }
        float phMax = 0;
        if (placeOk) for (int m = 0; m < N; m++) if (G.active[m]) { int h = 3 * m + 2;
            phMax = Math.max(phMax, Math.abs(dC.get(h) - hC[h])); phMax = Math.max(phMax, Math.abs(dU.get(h) - hU[h])); phMax = Math.max(phMax, Math.abs(dY.get(h) - hY[h])); }
        placeOk = placeOk && phMax < 1e-5;
        System.out.printf(Locale.US, "  matPlaceHead: maxΔ(float head pose)=%.2e %s%n", phMax, placeOk ? "PASS" : "FAIL");
        log.append(String.format(Locale.US, "- matPlaceHead: double geom → float MotorStore.body; maxΔ=%.2e → %s\n", phMax, placeOk ? "PASS" : "FAIL"));
        // --- matZConfine ---
        FloatArray fs = new FloatArray(3 * nSeg); for (int i = 0; i < 3 * nSeg; i++) fs.set(i, (float) (0.01 * (i + 1)));
        float[] hFs = new float[3 * nSeg]; for (int i = 0; i < 3 * nSeg; i++) hFs[i] = fs.get(i);
        for (int s = 0; s < nSeg; s++) { int iz = 2 * nSeg + s; hFs[iz] = (float) (hFs[iz] - G.kzCode * f.coordZ(s)); }
        FloatArray zP = FloatArray.fromElements((float) G.kzCode);
        try {
            TaskGraph tg = new TaskGraph("zc").transferToDevice(DataTransferMode.FIRST_EXECUTION, f.coord, zP)
                    .transferToDevice(DataTransferMode.EVERY_EXECUTION, fs, counts).task("zc", MatSoaSlice::matZConfine, f.coord, fs, zP, counts)
                    .transferToHost(DataTransferMode.EVERY_EXECUTION, fs);
            GridScheduler sc = new GridScheduler(); WorkerGrid w = new WorkerGrid1D(nSeg); w.setLocalWork(1, 1, 1); sc.addWorkerGrid("zc.zc", w);
            new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(sc).execute();
        } catch (Throwable ex) { zOk = false; log.append("- matZConfine LOWERS=NO: " + oneLine(ex.getMessage()) + "\n"); }
        float zMax = 0; if (zOk) for (int i = 0; i < 3 * nSeg; i++) zMax = Math.max(zMax, Math.abs(fs.get(i) - hFs[i]));
        zOk = zOk && zMax < 1e-6;
        System.out.printf(Locale.US, "  matZConfine: maxΔ=%.2e %s%n", zMax, zOk ? "PASS" : "FAIL");
        log.append(String.format(Locale.US, "- matZConfine: maxΔ=%.2e → %s\n", zMax, zOk ? "PASS" : "FAIL"));
        // --- matReduce ---
        int hNb = 0, hNa = 0; double hLoad = 0, hcx = 0, hcy = 0, hcz = 0;
        for (int m = 0; m < N; m++) { if (mot.boundSeg.get(m) >= 0) { hNb++; hLoad += mot.forceDotFil.get(m); } if (G.active[m]) hNa++; }
        for (int s = 0; s < nSeg; s++) { hcx += f.coordX(s); hcy += f.coordY(s); hcz += f.coordZ(s); }
        DoubleArray redOut = new DoubleArray(6);
        try {
            TaskGraph tg = new TaskGraph("red").transferToDevice(DataTransferMode.FIRST_EXECUTION, mot.boundSeg, active, mot.forceDotFil, f.coord)
                    .transferToDevice(DataTransferMode.EVERY_EXECUTION, counts).task("red", MatSoaSlice::matReduce, mot.boundSeg, active, mot.forceDotFil, f.coord, counts, redOut)
                    .transferToHost(DataTransferMode.EVERY_EXECUTION, redOut);
            GridScheduler sc = new GridScheduler(); WorkerGrid w = new WorkerGrid1D(1); w.setLocalWork(1, 1, 1); sc.addWorkerGrid("red.red", w);
            new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(sc).execute();
        } catch (Throwable ex) { redOk = false; log.append("- matReduce LOWERS=NO: " + oneLine(ex.getMessage()) + "\n"); }
        redOk = redOk && (int) redOut.get(0) == hNb && (int) redOut.get(5) == hNa && Math.abs(redOut.get(1) - hcx / nSeg) < 1e-6;
        System.out.printf(Locale.US, "  matReduce: nBound %d==%d nActive %d==%d COMxΔ=%.2e %s%n", (int) redOut.get(0), hNb, (int) redOut.get(5), hNa, Math.abs(redOut.get(1) - hcx / nSeg), redOk ? "PASS" : "FAIL");
        log.append(String.format(Locale.US, "- matReduce: nBound=%d(host %d) nActive=%d(host %d) → %s\n", (int) redOut.get(0), hNb, (int) redOut.get(5), hNa, redOk ? "PASS" : "FAIL"));
        return placeOk && zOk && redOk;
    }

    static DoubleArray packStep7Params(TwoBodyConverterMotor.Glide2D G) {
        DoubleArray p = new DoubleArray(35);
        p.set(0, G.bhat[0]); p.set(1, G.bhat[1]); p.set(2, G.bhat[2]);
        p.set(3, G.econv[0]); p.set(4, G.econv[1]); p.set(5, G.econv[2]);
        p.set(6, G.eup[0]); p.set(7, G.eup[1]); p.set(8, G.eup[2]);
        p.set(9, G.lb); p.set(10, G.rF8[0]); p.set(11, G.rF8[1]); p.set(12, G.rConv[0]); p.set(13, G.rConv[1]);
        p.set(14, G.kF8Code); p.set(15, G.kconvCode); p.set(16, G.kbindCode);
        p.set(17, G.supGammaP); p.set(18, G.gammaPhi); p.set(19, G.gammaPsi); p.set(20, G.dt);
        p.set(21, G.supKsoftAx); p.set(22, G.supKtautAx); p.set(23, G.supDelta); p.set(24, G.supKsoftTr); p.set(25, G.supKfeTr);
        p.set(26, G.supRmax); p.set(27, G.supKfloor); p.set(28, G.supSmoothAx); p.set(29, G.supSmoothTr);
        p.set(30, G.supCompFrac); p.set(31, G.supFloorZ); p.set(32, G.supBuckleCrit); p.set(33, G.supKcompPost); p.set(34, G.supSmoothBuck);
        return p;
    }

    // ---------- Part 5.4 — Step-7 (5-DOF movable-pivot) vs host supSolveM (double bit-for-decision) ----------
    static boolean gateStep7(StringBuilder log) {
        System.out.println("\n--- Part 5.4: matStep7 vs host supSolveM (pose/geometry/force, double) ---");
        log.append("## Stage 7 — matStep7 (double, MAT salts 0x5F1..0x5F5+m·7919) vs supSolveM\n");
        double dt = 2.5e-6; boolean allPass = true; int warm = 1500;
        int[][] scen = { {200, 11}, {700, 11} };
        for (int[] sc : scen) {
            double density = sc[0]; int seed = sc[1];
            TwoBodyConverterMotor.Glide2D G = TwoBodyConverterMotor.buildSupMat(density, dt, 0.0, seed);
            int N = G.N, nSeg = G.nSeg; MotorStore mot = G.mot;
            for (int tt = 0; tt < warm; tt++) TwoBodyConverterMotor.stepGlideSup(G, tt, seed, new TwoBodyConverterMotor.Tol());
            for (int m = 0; m < N; m++) TwoBodyConverterMotor.geom2D(G, m);   // freshen C_/xF8_ = geom2D(current pose)
            TwoBodyConverterMotor.unionActive(G);
            int T = warm;   // the test Step-7 index (mat salts key on t)
            // --- snapshot pre-Step7 state → pack device buffers (BEFORE host mutation) ---
            DoubleArray pose4 = new DoubleArray(4 * N), anchor = new DoubleArray(3 * N), supP0 = new DoubleArray(3 * N);
            IntArray boundSeg = new IntArray(N), active = new IntArray(N);
            FloatArray bondData = new FloatArray(13 * N);
            int nActive = 0, nBound = 0;
            for (int m = 0; m < N; m++) {
                pose4.set(m, G.phi[m]); pose4.set(N + m, G.psi[m]); pose4.set(2 * N + m, G.thetaS[m]); pose4.set(3 * N + m, G.psiActin[m]);
                anchor.set(m, G.A[m][0]); anchor.set(N + m, G.A[m][1]); anchor.set(2 * N + m, G.A[m][2]);
                supP0.set(m, G.supP0[m][0]); supP0.set(N + m, G.supP0[m][1]); supP0.set(2 * N + m, G.supP0[m][2]);
                boundSeg.set(m, mot.boundSeg.get(m)); active.set(m, G.active[m] ? 1 : 0);
                for (int k = 0; k < 13; k++) bondData.set(13 * m + k, G.bondData.get(m * 13 + k));
                if (G.active[m]) nActive++; if (mot.boundSeg.get(m) >= 0) nBound++;
            }
            DoubleArray sp = packStep7Params(G);
            IntArray counts = new IntArray(4); counts.set(0, N); counts.set(1, T); counts.set(2, seed); counts.set(3, nSeg);
            DoubleArray geomOut = new DoubleArray(9 * N); FloatArray fdfOut = new FloatArray(N), fmagOut = new FloatArray(N);
            // --- CPU-runner matStep7 (plain loop = the SAME method) on clones, to separate arithmetic-form from GPU-FMA ---
            DoubleArray cPose = new DoubleArray(4 * N), cAnchor = new DoubleArray(3 * N), cGeom = new DoubleArray(9 * N);
            FloatArray cFdf = new FloatArray(N), cFmag = new FloatArray(N);
            for (int i = 0; i < 4 * N; i++) cPose.set(i, pose4.get(i));
            for (int i = 0; i < 3 * N; i++) cAnchor.set(i, anchor.get(i));
            matStep7(cPose, cAnchor, sp, supP0, bondData, boundSeg, active, counts, cGeom, cFdf, cFmag);
            // --- host reference: supSolveM for active (stepGlideSup L5861) ---
            double[][] hA = new double[N][3]; double[] hPhi = new double[N], hPsi = new double[N], hFDF = new double[N], hFMag = new double[N];
            double[][] hC = new double[N][3], hF8 = new double[N][3], hH = new double[N][3];
            for (int m = 0; m < N; m++) {
                if (G.active[m]) TwoBodyConverterMotor.supSolveM(G, m, T, seed, mot.boundSeg.get(m) >= 0);
                else { mot.forceDotFil.set(m, 0f); mot.forceMag.set(m, 0f); }
                hA[m] = G.A[m].clone(); hPhi[m] = G.phi[m]; hPsi[m] = G.psi[m];
                hC[m] = G.C_[m].clone(); hF8[m] = G.xF8_[m].clone(); hH[m] = G.xH_[m].clone();
                hFDF[m] = mot.forceDotFil.get(m); hFMag[m] = mot.forceMag.get(m);
            }
            // --- device ---
            try {
                TaskGraph tg = new TaskGraph("step7")
                        .transferToDevice(DataTransferMode.FIRST_EXECUTION, sp, supP0, bondData, boundSeg, active)
                        .transferToDevice(DataTransferMode.EVERY_EXECUTION, pose4, anchor, counts)
                        .task("matStep7", MatSoaSlice::matStep7, pose4, anchor, sp, supP0, bondData, boundSeg, active, counts, geomOut, fdfOut, fmagOut)
                        .transferToHost(DataTransferMode.EVERY_EXECUTION, pose4, anchor, geomOut, fdfOut, fmagOut);
                GridScheduler sched = new GridScheduler();
                WorkerGrid w = new WorkerGrid1D(N); w.setLocalWork(1, 1, 1); sched.addWorkerGrid("step7.matStep7", w);
                new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(sched).execute();
            } catch (Throwable ex) {
                Throwable root = ex; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                System.out.printf(Locale.US, "  density=%.0f seed=%d N=%d: LOWERS=NO — %s: %s%n", density, seed, N, ex.getClass().getName(), oneLine(ex.getMessage()));
                log.append(String.format(Locale.US, "- density=%.0f seed=%d N=%d: **LOWERS=NO** — `%s`: %s\n", density, seed, N, root.getClass().getName(), oneLine(root.getMessage())));
                return false;
            }
            // --- compare (active motors): GPU-vs-host, CPU-vs-host (arithmetic form), GPU-vs-CPU (pure FMA) ---
            double gPose = 0, gGeom = 0, gForce = 0, cPoseD = 0, gcPose = 0;
            for (int m = 0; m < N; m++) if (G.active[m]) {
                gPose = Math.max(gPose, Math.abs(pose4.get(m) - hPhi[m])); gPose = Math.max(gPose, Math.abs(pose4.get(N + m) - hPsi[m]));
                gPose = Math.max(gPose, Math.abs(anchor.get(m) - hA[m][0])); gPose = Math.max(gPose, Math.abs(anchor.get(N + m) - hA[m][1])); gPose = Math.max(gPose, Math.abs(anchor.get(2 * N + m) - hA[m][2]));
                cPoseD = Math.max(cPoseD, Math.abs(cPose.get(m) - hPhi[m])); cPoseD = Math.max(cPoseD, Math.abs(cPose.get(N + m) - hPsi[m]));
                cPoseD = Math.max(cPoseD, Math.abs(cAnchor.get(m) - hA[m][0])); cPoseD = Math.max(cPoseD, Math.abs(cAnchor.get(N + m) - hA[m][1])); cPoseD = Math.max(cPoseD, Math.abs(cAnchor.get(2 * N + m) - hA[m][2]));
                gcPose = Math.max(gcPose, Math.abs(pose4.get(m) - cPose.get(m))); gcPose = Math.max(gcPose, Math.abs(anchor.get(m) - cAnchor.get(m)));
                for (int c = 0; c < 3; c++) { gGeom = Math.max(gGeom, Math.abs(geomOut.get(c * N + m) - hC[m][c]));
                    gGeom = Math.max(gGeom, Math.abs(geomOut.get((3 + c) * N + m) - hF8[m][c])); gGeom = Math.max(gGeom, Math.abs(geomOut.get((6 + c) * N + m) - hH[m][c])); }
                gForce = Math.max(gForce, Math.abs(fdfOut.get(m) - hFDF[m])); gForce = Math.max(gForce, Math.abs(fmagOut.get(m) - hFMag[m]));
            }
            // PASS: force bit-for-decision (F8 identical); pose/geom double last-bit AMPLIFIED by the ill-conditioned 5-DOF
            // solve (the physics is equally sensitive on the host). Classify float-vs-semantic: force<1e-15 ⇒ no semantic error.
            boolean pass = gForce < 1e-15 && gGeom < 1e-6 && gPose < 1e-5 && cPoseD < 1e-5;
            allPass &= pass;
            System.out.printf(Locale.US, "  density=%.0f seed=%d N=%d active=%d bound=%d: GPUvsHost poseΔ=%.2e geomΔ=%.2e forceΔ=%.2e | CPUvsHost poseΔ=%.2e | GPUvsCPU poseΔ=%.2e %s%n",
                    density, seed, N, nActive, nBound, gPose, gGeom, gForce, cPoseD, gcPose, pass ? "PASS" : "FAIL");
            log.append(String.format(Locale.US, "- density=%.0f seed=%d N=%d active=%d bound=%d: GPUvsHost poseΔ=%.2e geomΔ=%.2e forceΔ=%.2e; CPUvsHost(arith-form) poseΔ=%.2e; GPUvsCPU(FMA) poseΔ=%.2e → %s\n",
                    density, seed, N, nActive, nBound, gPose, gGeom, gForce, cPoseD, gcPose, pass ? "PASS" : "FAIL"));
        }
        log.append("\n");
        return allPass;
    }

    // ---------- Part 5.3 (binding half) — bind-event IDENTITY (chained matGeomGate→matBind on device) ----------
    static boolean gateBind(StringBuilder log) {
        System.out.println("\n--- Part 5.3: matGeomGate→matBind vs host bind block (bind-event identity) ---");
        log.append("## Stage 3 — matBind (deterministic bind-event identity; chained geom→bind on device)\n");
        double dt = 2.5e-6; boolean allPass = true; int warm = 2500;
        int[][] scen = { {200, 11}, {200, 12}, {700, 11} };
        for (int[] sc : scen) {
                double density = sc[0]; int seed = sc[1];
                TwoBodyConverterMotor.Glide2D G = TwoBodyConverterMotor.buildSupMat(density, dt, 0.0, seed);
                int N = G.N, nSeg = G.nSeg; FilamentStore f = G.fil;
                // warm up on the host (real stepGlideSup) so poses evolve to realistic in-range configurations
                for (int tt = 0; tt < warm; tt++) TwoBodyConverterMotor.stepGlideSup(G, tt, seed, new TwoBodyConverterMotor.Tol());
                // make ALL motors eligible (unbind + ADP·Pi) so warmed in-range poses re-ACCEPT ⇒ exercises the accept=1 path
                for (int m = 0; m < N; m++) { G.mot.boundSeg.set(m, MotorStore.FREE_BINDABLE); G.mot.nucleotideState.set(m, MotorStore.NUC_ADPPI); }
                TwoBodyConverterMotor.unionActive(G);
                // --- host bind block (stepGlideSup L5838-5843) as the reference ---
                int[] hBound = new int[N]; double[] hArc = new double[N];
                for (int m = 0; m < N; m++) { hBound[m] = G.mot.boundSeg.get(m); hArc[m] = G.mot.bindArc.get(m); }
                for (int m = 0; m < N; m++) if (G.active[m] && !G.noBind[m] && G.mot.boundSeg.get(m) == MotorStore.FREE_BINDABLE && G.mot.nucleotideState.get(m) == MotorStore.NUC_ADPPI) {
                    G.thetaS[m] = TwoBodyConverterMotor.PRESTROKE_THETAS; TwoBodyConverterMotor.geom2D(G, m);
                    int s = TwoBodyConverterMotor.nearestSeg2D(G, m); if (s < 0) continue;
                    double[] gm = TwoBodyConverterMotor.gate2D(G, m, s); double half = 0.5 * f.segLength.get(s);
                    boolean g0 = gm[0] < 3.0, g1 = gm[2] < 25, g2 = gm[3] < 25, g3 = gm[4] < 20, g4 = gm[5] < 2.0, g5 = gm[6] < 15.0,
                            g6 = gm[7] < A_SEMI2 * 1e3, g7 = gm[1] > 0.05 && gm[1] < 2 * half - 0.05;
                    if (g0 && g1 && g2 && g3 && g4 && g5 && g6 && g7) { hBound[m] = s; hArc[m] = gm[1]; }
                }
                // --- device: chained matGeomGate → matBind, sharing device buffers (no intermediate host round-trip) ---
                DoubleArray anchor = new DoubleArray(3 * N), pose = new DoubleArray(4 * N);
                IntArray active = new IntArray(N), noBind = new IntArray(N), boundSeg = new IntArray(N), nuc = new IntArray(N);
                FloatArray bindArc = new FloatArray(N);
                for (int m = 0; m < N; m++) {
                    anchor.set(m, G.A[m][0]); anchor.set(N + m, G.A[m][1]); anchor.set(2 * N + m, G.A[m][2]);
                    pose.set(m, G.phi[m]); pose.set(N + m, G.psi[m]); pose.set(2 * N + m, G.thetaS[m]); pose.set(3 * N + m, G.psiActin[m]);
                    active.set(m, G.active[m] ? 1 : 0); noBind.set(m, G.noBind[m] ? 1 : 0);
                    boundSeg.set(m, G.mot.boundSeg.get(m)); nuc.set(m, G.mot.nucleotideState.get(m)); bindArc.set(m, G.mot.bindArc.get(m));
                }
                DoubleArray params = packGeomGateParams(G);
                IntArray counts = new IntArray(4); counts.set(0, N); counts.set(1, 0); counts.set(2, seed); counts.set(3, nSeg);
                DoubleArray geomOut = new DoubleArray(9 * N); IntArray candInt = new IntArray(2 * N); DoubleArray candArc = new DoubleArray(N);
                try {
                    TaskGraph tg = new TaskGraph("bind")
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, anchor, pose, f.coord, f.uVec, f.segLength, params, active, noBind, nuc)
                            .transferToDevice(DataTransferMode.EVERY_EXECUTION, counts, boundSeg, bindArc)
                            .task("matGeomGate", MatSoaSlice::matGeomGate, anchor, pose, f.coord, f.uVec, f.segLength, params, counts, geomOut, candInt, candArc)
                            .task("matBind", MatSoaSlice::matBind, active, noBind, boundSeg, nuc, candInt, candArc, bindArc, counts)
                            .transferToHost(DataTransferMode.EVERY_EXECUTION, boundSeg, bindArc);
                    GridScheduler sched = new GridScheduler();
                    WorkerGrid w1 = new WorkerGrid1D(N); w1.setLocalWork(1, 1, 1); sched.addWorkerGrid("bind.matGeomGate", w1);
                    WorkerGrid w2 = new WorkerGrid1D(N); w2.setLocalWork(1, 1, 1); sched.addWorkerGrid("bind.matBind", w2);
                    new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(sched).execute();
                } catch (Throwable ex) {
                    Throwable root = ex; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    System.out.printf(Locale.US, "  density=%.0f seed=%d N=%d: LOWERS=NO — %s: %s%n", density, seed, N, ex.getClass().getName(), oneLine(ex.getMessage()));
                    log.append(String.format(Locale.US, "- density=%.0f seed=%d N=%d: **LOWERS=NO** — `%s`: %s\n", density, seed, N, root.getClass().getName(), oneLine(root.getMessage())));
                    return false;
                }
                int bMis = 0, arcMis = 0, nBindHost = 0, firstB = -1;
                for (int m = 0; m < N; m++) {
                    if (hBound[m] >= 0 && G.mot.boundSeg.get(m) < 0) nBindHost++;   // host bind events this step
                    if (boundSeg.get(m) != hBound[m]) { bMis++; if (firstB < 0) firstB = m; }
                    if (hBound[m] >= 0 && Math.abs(bindArc.get(m) - hArc[m]) > 1e-6) arcMis++;
                }
                boolean pass = (bMis == 0);
                allPass &= pass;
                System.out.printf(Locale.US, "  density=%.0f seed=%d N=%d: hostBindEvents=%d boundSegMism=%d bindArcMism=%d %s%n",
                        density, seed, N, nBindHost, bMis, arcMis, pass ? "PASS" : ("FAIL@" + firstB));
                log.append(String.format(Locale.US, "- density=%.0f seed=%d N=%d: hostBindEvents=%d boundSegMism=%d bindArcMism(>1e-6)=%d → %s\n",
                        density, seed, N, nBindHost, bMis, arcMis, pass ? "PASS" : "FAIL"));
        }
        // --- POSITIVE PATH: synthetic ideal-pose motors (natural single-step binds are ~1e-5/motor ⇒ ~0). Place a
        //     batch at the ideal pre-stroke pose over cycling segments so the gate ACCEPTS; verify accept=1 identity. ---
        {
            TwoBodyConverterMotor.Glide2D G = TwoBodyConverterMotor.buildSupMat(200, dt, 0.0, 7);
            int N = G.N, nSeg = G.nSeg; FilamentStore f = G.fil;
            int K = Math.min(N, 96);
            double lb = G.lb; double cph = Math.cos(TwoBodyConverterMotor.PHI_PRE_3E), sph = Math.sin(TwoBodyConverterMotor.PHI_PRE_3E);
            for (int m = 0; m < K; m++) {
                int s = m % nSeg;
                double tx = f.coordX(s), ty = f.coordY(s), tz = f.coordZ(s);              // target on the segment axis
                double uBx = G.eup[0] * cph + G.bhat[0] * sph, uBy = G.eup[1] * cph + G.bhat[1] * sph, uBz = G.eup[2] * cph + G.bhat[2] * sph;
                double d0x = G.bhat[0] * (G.rF8[0] - G.rConv[0]) + G.eup[0] * (G.rF8[1] - G.rConv[1]);
                double d0y = G.bhat[1] * (G.rF8[0] - G.rConv[0]) + G.eup[1] * (G.rF8[1] - G.rConv[1]);
                double d0z = G.bhat[2] * (G.rF8[0] - G.rConv[0]) + G.eup[2] * (G.rF8[1] - G.rConv[1]);
                G.A[m] = new double[]{ tx - lb * uBx - d0x, ty - lb * uBy - d0y, tz - lb * uBz - d0z };   // psi=0 ⇒ xF8 = target
                G.phi[m] = TwoBodyConverterMotor.PHI_PRE_3E; G.psi[m] = 0; G.psiActin[m] = 0; G.thetaS[m] = TwoBodyConverterMotor.PRESTROKE_THETAS;
                G.active[m] = true; G.mot.boundSeg.set(m, MotorStore.FREE_BINDABLE); G.mot.nucleotideState.set(m, MotorStore.NUC_ADPPI);
            }
            for (int m = 0; m < N; m++) G.active[m] = (m < K);   // isolate the synthetic set
            int[] hBound = new int[N]; for (int m = 0; m < N; m++) hBound[m] = G.mot.boundSeg.get(m);
            for (int m = 0; m < K; m++) {
                TwoBodyConverterMotor.geom2D(G, m); int s = TwoBodyConverterMotor.nearestSeg2D(G, m); if (s < 0) continue;
                double[] gm = TwoBodyConverterMotor.gate2D(G, m, s); double half = 0.5 * f.segLength.get(s);
                boolean g0 = gm[0] < 3.0, g1 = gm[2] < 25, g2 = gm[3] < 25, g3 = gm[4] < 20, g4 = gm[5] < 2.0, g5 = gm[6] < 15.0,
                        g6 = gm[7] < A_SEMI2 * 1e3, g7 = gm[1] > 0.05 && gm[1] < 2 * half - 0.05;
                if (g0 && g1 && g2 && g3 && g4 && g5 && g6 && g7) hBound[m] = s;
            }
            int nAcc = 0; for (int m = 0; m < N; m++) if (hBound[m] >= 0) nAcc++;
            // device chained geom→bind
            DoubleArray anchor = new DoubleArray(3 * N), pose = new DoubleArray(4 * N);
            IntArray active = new IntArray(N), noBind = new IntArray(N), boundSeg = new IntArray(N), nuc = new IntArray(N);
            FloatArray bindArc = new FloatArray(N);
            for (int m = 0; m < N; m++) { anchor.set(m, G.A[m][0]); anchor.set(N + m, G.A[m][1]); anchor.set(2 * N + m, G.A[m][2]);
                pose.set(m, G.phi[m]); pose.set(N + m, G.psi[m]); pose.set(2 * N + m, G.thetaS[m]); pose.set(3 * N + m, G.psiActin[m]);
                active.set(m, G.active[m] ? 1 : 0); noBind.set(m, G.noBind[m] ? 1 : 0); boundSeg.set(m, MotorStore.FREE_BINDABLE); nuc.set(m, G.mot.nucleotideState.get(m)); bindArc.set(m, 0f); }
            DoubleArray params = packGeomGateParams(G);
            IntArray counts = new IntArray(4); counts.set(0, N); counts.set(1, 0); counts.set(2, 7); counts.set(3, nSeg);
            DoubleArray geomOut = new DoubleArray(9 * N); IntArray candInt = new IntArray(2 * N); DoubleArray candArc = new DoubleArray(N);
            boolean lowered = true; String err = "";
            try {
                TaskGraph tg = new TaskGraph("bindsyn")
                        .transferToDevice(DataTransferMode.FIRST_EXECUTION, anchor, pose, f.coord, f.uVec, f.segLength, params, active, noBind, nuc)
                        .transferToDevice(DataTransferMode.EVERY_EXECUTION, counts, boundSeg, bindArc)
                        .task("matGeomGate", MatSoaSlice::matGeomGate, anchor, pose, f.coord, f.uVec, f.segLength, params, counts, geomOut, candInt, candArc)
                        .task("matBind", MatSoaSlice::matBind, active, noBind, boundSeg, nuc, candInt, candArc, bindArc, counts)
                        .transferToHost(DataTransferMode.EVERY_EXECUTION, boundSeg, bindArc);
                GridScheduler sched = new GridScheduler();
                WorkerGrid w1 = new WorkerGrid1D(N); w1.setLocalWork(1, 1, 1); sched.addWorkerGrid("bindsyn.matGeomGate", w1);
                WorkerGrid w2 = new WorkerGrid1D(N); w2.setLocalWork(1, 1, 1); sched.addWorkerGrid("bindsyn.matBind", w2);
                new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(sched).execute();
            } catch (Throwable ex) { lowered = false; Throwable r = ex; while (r.getCause() != null && r.getCause() != r) r = r.getCause(); err = r.getClass().getName() + ": " + oneLine(r.getMessage()); }
            int mis = 0; if (lowered) for (int m = 0; m < N; m++) if (boundSeg.get(m) != hBound[m]) mis++;
            boolean pass = lowered && mis == 0 && nAcc > 0;
            allPass &= pass;
            System.out.printf(Locale.US, "  synthetic ideal-pose: hostAccepts=%d boundSegMism=%d %s%s%n", nAcc, mis, pass ? "PASS" : "FAIL", lowered ? "" : (" LOWERS=NO " + err));
            log.append(String.format(Locale.US, "- synthetic ideal-pose (positive path): hostAccepts=%d, boundSegMism=%d → %s\n", nAcc, mis, pass ? "PASS" : "FAIL"));
        }
        log.append("- Chained geom→bind on device (buffers shared, no intermediate host transfer); binding is DETERMINISTIC (no RNG). ")
           .append("Natural single-step binds ~1e-5/motor (⇒0); the accept=1 path is verified synthetically. bindArc float, 1e-6.\n\n");
        return allPass;
    }

    // ---------- Part 5.2 — geom + nearest + gate IDENTITY ----------
    static boolean gateGeomGate(StringBuilder log) {
        System.out.println("\n--- Part 5.2: matGeomGate vs host geom2D+nearestSeg2D+gate2D ---");
        log.append("## Stage 2 — matGeomGate (geom + nearest-seg discrete selection + 8-gate identity)\n");
        double dt = 2.5e-6;
        boolean allPass = true;
        for (double density : new double[]{ 200, 700 }) {
            for (int seed : new int[]{ 11, 12, 13 }) {
                TwoBodyConverterMotor.Glide2D G = TwoBodyConverterMotor.buildSupMat(density, dt, 0.0, seed);
                int N = G.N, nSeg = G.nSeg; FilamentStore f = G.fil;
                // --- host oracle: geom2D → nearest → gate → 8-gate, with thetaS=PRESTROKE (the bind-block value) ---
                int[] hSeg = new int[N], hAcc = new int[N]; double[] hArc = new double[N];
                double[][] hC = new double[N][3], hF8 = new double[N][3], hH = new double[N][3];
                for (int m = 0; m < N; m++) {
                    G.thetaS[m] = TwoBodyConverterMotor.PRESTROKE_THETAS;
                    TwoBodyConverterMotor.geom2D(G, m);
                    hC[m] = G.C_[m].clone(); hF8[m] = G.xF8_[m].clone(); hH[m] = G.xH_[m].clone();
                    int s = TwoBodyConverterMotor.nearestSeg2D(G, m); hSeg[m] = s;
                    if (s >= 0) {
                        double[] gm = TwoBodyConverterMotor.gate2D(G, m, s); double half = 0.5 * f.segLength.get(s);
                        boolean g0 = gm[0] < 3.0, g1 = gm[2] < 25, g2 = gm[3] < 25, g3 = gm[4] < 20, g4 = gm[5] < 2.0, g5 = gm[6] < 15.0,
                                g6 = gm[7] < A_SEMI2 * 1e3, g7 = gm[1] > 0.05 && gm[1] < 2 * half - 0.05;
                        hAcc[m] = (g0 && g1 && g2 && g3 && g4 && g5 && g6 && g7) ? 1 : 0; hArc[m] = gm[1];
                    }
                }
                // --- pack device SoA ---
                DoubleArray anchor = new DoubleArray(3 * N), pose = new DoubleArray(4 * N);
                for (int m = 0; m < N; m++) {
                    anchor.set(m, G.A[m][0]); anchor.set(N + m, G.A[m][1]); anchor.set(2 * N + m, G.A[m][2]);
                    pose.set(m, G.phi[m]); pose.set(N + m, G.psi[m]); pose.set(2 * N + m, G.thetaS[m]); pose.set(3 * N + m, G.psiActin[m]);
                }
                DoubleArray params = packGeomGateParams(G);
                IntArray counts = new IntArray(4); counts.set(0, N); counts.set(1, 0); counts.set(2, seed); counts.set(3, nSeg);
                DoubleArray geomOut = new DoubleArray(9 * N); IntArray candInt = new IntArray(2 * N); DoubleArray candArc = new DoubleArray(N);
                long dev0 = System.nanoTime();
                try {
                    TaskGraph tg = new TaskGraph("geomgate")
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, anchor, pose, f.coord, f.uVec, f.segLength, params)
                            .transferToDevice(DataTransferMode.EVERY_EXECUTION, counts)
                            .task("matGeomGate", MatSoaSlice::matGeomGate, anchor, pose, f.coord, f.uVec, f.segLength, params, counts, geomOut, candInt, candArc)
                            .transferToHost(DataTransferMode.EVERY_EXECUTION, geomOut, candInt, candArc);
                    GridScheduler sched = new GridScheduler();
                    WorkerGrid w = new WorkerGrid1D(N); w.setLocalWork(1, 1, 1); sched.addWorkerGrid("geomgate.matGeomGate", w);
                    new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(sched).execute();
                } catch (Throwable ex) {
                    Throwable root = ex; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    System.out.printf(Locale.US, "  density=%.0f seed=%d N=%d: LOWERS=NO — %s: %s%n", density, seed, N, ex.getClass().getName(), oneLine(ex.getMessage()));
                    log.append(String.format(Locale.US, "- density=%.0f seed=%d N=%d: **LOWERS=NO** — `%s`: %s\n", density, seed, N, root.getClass().getName(), oneLine(root.getMessage())));
                    return false;
                }
                double devMs = (System.nanoTime() - dev0) / 1e6;
                // --- compare ---
                int segMis = 0, accMis = 0; double maxGeom = 0, maxArc = 0; int firstSeg = -1, firstAcc = -1;
                for (int m = 0; m < N; m++) {
                    if (candInt.get(m) != hSeg[m]) { segMis++; if (firstSeg < 0) firstSeg = m; }
                    if (candInt.get(N + m) != hAcc[m]) { accMis++; if (firstAcc < 0) firstAcc = m; }
                    maxGeom = Math.max(maxGeom, Math.abs(geomOut.get(m) - hC[m][0]));
                    maxGeom = Math.max(maxGeom, Math.abs(geomOut.get(3 * N + m) - hF8[m][0]));
                    maxGeom = Math.max(maxGeom, Math.abs(geomOut.get(4 * N + m) - hF8[m][1]));
                    maxGeom = Math.max(maxGeom, Math.abs(geomOut.get(6 * N + m) - hH[m][0]));
                    if (hSeg[m] >= 0) maxArc = Math.max(maxArc, Math.abs(candArc.get(m) - hArc[m]));
                }
                boolean pass = (segMis == 0 && accMis == 0);
                allPass &= pass;
                System.out.printf(Locale.US, "  density=%.0f seed=%d N=%d: segMism=%d acceptMism=%d maxGeomΔ=%.2e maxArcΔ=%.2e %s [%.1f ms]%n",
                        density, seed, N, segMis, accMis, maxGeom, maxArc, pass ? "PASS" : ("FAIL seg@" + firstSeg + " acc@" + firstAcc), devMs);
                log.append(String.format(Locale.US, "- density=%.0f seed=%d N=%d: segMism=%d acceptMism=%d maxGeomΔ=%.2e (double bit-for-decision) maxArcΔ=%.2e → %s\n",
                        density, seed, N, segMis, accMis, maxGeom, maxArc, pass ? "PASS" : "FAIL"));
            }
        }
        log.append("- Discrete identity (nearest-seg selection + 8-gate accept) required exact; geom Δ is double last-bit (op-order).\n\n");
        return allPass;
    }

    static final double A_SEMI2 = 0.00225;   // A_SEMI[2] µm (steric gate g6)

    static DoubleArray packGeomGateParams(TwoBodyConverterMotor.Glide2D G) {
        DoubleArray p = new DoubleArray(30);
        p.set(0, G.bhat[0]); p.set(1, G.bhat[1]); p.set(2, G.bhat[2]);
        p.set(3, G.econv[0]); p.set(4, G.econv[1]); p.set(5, G.econv[2]);
        p.set(6, G.eup[0]); p.set(7, G.eup[1]); p.set(8, G.eup[2]);
        p.set(9, G.lb); p.set(10, G.rF8[0]); p.set(11, G.rF8[1]); p.set(12, G.rConv[0]); p.set(13, G.rConv[1]);
        p.set(14, Constants.radius); p.set(15, A_SEMI2); p.set(16, G.kF8Code); p.set(17, G.kconvCode); p.set(18, G.kbindCode);
        p.set(19, TwoBodyConverterMotor.PHI_PRE_3E); p.set(20, Constants.kT); p.set(21, TwoBodyConverterMotor.PRESTROKE_THETAS);
        p.set(22, 3.0); p.set(23, 25); p.set(24, 25); p.set(25, 20); p.set(26, 2.0); p.set(27, 15.0); p.set(28, 0.05); p.set(29, 0.02);
        return p;
    }

    // ---------- Part 5.1 — cull active-set IDENTITY ----------
    static boolean gateCull(StringBuilder log) {
        System.out.println("\n--- Part 5.1: matCull vs host unionActive (active-set identity) ---");
        log.append("## Stage 1 — matCull (active-set identity vs unionActive)\n");
        double dt = 2.5e-6;
        boolean allPass = true;
        // scenarios: small deterministic mats at several densities/seeds; exercises active/inactive/bound/boundary.
        int[] seeds = { 11, 12, 13 };
        double[] densities = { 200, 700 };
        for (double density : densities) {
            for (int seed : seeds) {
                TwoBodyConverterMotor.Glide2D G = TwoBodyConverterMotor.buildSupMat(density, dt, 0.0, seed);
                int N = G.N, nSeg = G.nSeg;
                // exercise the bound branch too: deterministically pre-bind ~1/8 of motors to a valid segment
                for (int m = 0; m < N; m += 8) G.mot.boundSeg.set(m, m % nSeg);
                // --- host oracle ---
                TwoBodyConverterMotor.unionActive(G);
                // --- pack flat SoA (motor state stays here after this; only counts re-upload per step) ---
                DoubleArray site = new DoubleArray(2 * N);
                IntArray boundSeg = new IntArray(N);
                for (int m = 0; m < N; m++) { site.set(m, G.siteX[m]); site.set(N + m, G.siteY[m]); boundSeg.set(m, G.mot.boundSeg.get(m)); }
                FilamentStore f = G.fil;
                DoubleArray cullParams = DoubleArray.fromElements(G.queryR * G.queryR);
                IntArray counts = new IntArray(4); counts.set(0, N); counts.set(1, 0); counts.set(2, seed); counts.set(3, nSeg);
                IntArray active = new IntArray(N); active.init(0);

                // --- device: real TaskGraph, motor SoA persistent, filament small, only counts EVERY_EXECUTION ---
                long dev0 = System.nanoTime();
                try {
                    TaskGraph tg = new TaskGraph("cull")
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, boundSeg, site, f.coord, f.uVec, f.segLength, cullParams)
                            .transferToDevice(DataTransferMode.EVERY_EXECUTION, counts)
                            .task("matCull", MatSoaSlice::matCull, boundSeg, site, f.coord, f.uVec, f.segLength, cullParams, counts, active)
                            .transferToHost(DataTransferMode.EVERY_EXECUTION, active);
                    GridScheduler sched = new GridScheduler();
                    WorkerGrid w = new WorkerGrid1D(N); w.setLocalWork(1, 1, 1); sched.addWorkerGrid("cull.matCull", w);
                    TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot());
                    plan.withGridScheduler(sched).execute();
                } catch (Throwable ex) {
                    Throwable root = ex; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    System.out.printf(Locale.US, "  density=%.0f seed=%d N=%d: LOWERS=NO — %s: %s%n", density, seed, N, ex.getClass().getName(), oneLine(ex.getMessage()));
                    log.append(String.format(Locale.US, "- density=%.0f seed=%d N=%d: **LOWERS=NO** — `%s`: %s\n", density, seed, N, root.getClass().getName(), oneLine(root.getMessage())));
                    return false;
                }
                double devMs = (System.nanoTime() - dev0) / 1e6;

                int nActiveHost = 0, mism = 0, firstMism = -1;
                for (int m = 0; m < N; m++) {
                    int h = G.active[m] ? 1 : 0;
                    if (h == 1) nActiveHost++;
                    if (active.get(m) != h) { mism++; if (firstMism < 0) firstMism = m; }
                }
                boolean pass = (mism == 0);
                allPass &= pass;
                System.out.printf(Locale.US, "  density=%.0f seed=%d N=%d nSeg=%d: activeHost=%d mismatches=%d %s [%.1f ms]%n",
                        density, seed, N, nSeg, nActiveHost, mism, pass ? "PASS" : ("FAIL@" + firstMism), devMs);
                log.append(String.format(Locale.US, "- density=%.0f seed=%d N=%d nSeg=%d: activeHost=%d, mismatches=%d → %s (bytes/step: counts=16 up, active=%d down; no full mat transfer)\n",
                        density, seed, N, nSeg, nActiveHost, mism, pass ? "PASS" : "FAIL", 4 * N));
            }
        }
        log.append("- Residency: boundSeg/site/fil.coord/fil.uVec/fil.segLength/cullParams uploaded FIRST_EXECUTION (once); ")
           .append("only `counts` (16 B) re-uploads EVERY_EXECUTION and `active` (4N B) is read back — NO full per-motor state transfer.\n\n");
        return allPass;
    }

    static String oneLine(String s) { return s == null ? "(none)" : s.replaceAll("\\s+", " ").trim(); }
    static String gpuMemUsed() {
        try { Process p = new ProcessBuilder("nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits").start();
            String out = new String(p.getInputStream().readAllBytes()).trim(); p.waitFor();
            return out.isEmpty() ? "?" : out.split("\\R")[0].trim(); } catch (Exception e) { return "?"; }
    }
}
