package softbox;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Part C — FLAT, GPU-compatible ANALYTIC explicit-S2 beam kernel (double precision).
 *
 * <p>Runs the validated CPU ANALYTIC beam solve ({@link ExplicitBeamAnalytic} residual + exact energy
 * Hessian + {@link ExplicitBeamSolver#solveFlat} 14×14 Gauss–Jordan) as a device {@code @Parallel} kernel:
 * per-thread beam relaxation to steady state. It is written in the SoftBox "one method, two runners" idiom
 * — called directly as a plain Java loop it IS the CPU runner (bit-faithful to the device by construction);
 * wrapped in a TaskGraph it is the GPU kernel. This is the kernel the FD path could NOT lower (its nested
 * finite-difference beam-force helper compiled to 973 nodes, over TornadoVM's 600-node inline cap); the
 * analytic form removes the nested FD entirely.
 *
 * <p><b>Lowering-safe structure.</b> NO {@code double[][]}, NO {@code new} / dynamic allocation inside the
 * kernel: all per-thread scratch is a caller-provided FLAT {@code DoubleArray sys} slice
 * ({@code m*SYS_STRIDE + i*W + j}, the augmented 14×15 system). Bounded loops with RUNTIME bounds ({@code n},
 * {@code M} passed via counts ⇒ not force-unrolled). Small inlineable helpers ({@code dacos}, {@code a1},
 * {@code a2}, {@code addK}, {@code addF}). {@code accurateAcos} (Newton-refined) replaces {@code Math.acos}
 * (which does not lower on the PTX backend). Explicit convergence + singular-matrix failure flags.
 *
 * <p><b>Units (identical to the frozen code / ExplicitBeamAnalytic).</b> Nodes µm; g4ks N/m, g4kb N·m,
 * g4kfloor N/m; force N; tangent N/m; dq m (nodes) / rad (angles).
 *
 * <p><b>Buffer layout</b> (planar, comp·nM + m unless noted):
 * <ul>
 *   <li>{@code nodes} : 3(M+1)=15 comps (node j comp k at (3j+k)·nM+m) — the beam state, updated in place.
 *   <li>{@code frame} : bhat(0..2), econv(3..5), eup(6..8), g4E(9..11), g4Tan(12..14).
 *   <li>{@code q}     : phi, psi, thetaS, psiActin — phi,psi updated.
 *   <li>{@code F8h}   : head force (x,y,z), constant over the relaxation.
 *   <li>{@code params}: lb,rF8x,rF8y,rConvx,rConvy,kF8Code,kconvCode,kbindCode,gammaPhi,gammaPsi,dt,g4ks,g4l0,g4kb,g4floorZ,g4kfloor,g4gammaNode.
 *   <li>{@code sys}   : per-thread scratch, stride SYS_STRIDE = 14*15 = 210.
 *   <li>{@code outGeom}: C(0..2), xH(3..5), xF8(6..8) — written.
 *   <li>{@code status}: 0 ok / 1 singular (per thread).  {@code iters}: converged-at iteration (per thread).
 *   <li>{@code counts}: [nM, maxIt, M, convergedFlagUnused].  tol is fixed at 3e-7 µm (matches the CPU gate).
 * </ul>
 */
public final class TwoBodyBeamAnalyticGpu {

    public static final int SYS_STRIDE = 14 * 15;   // n*(n+1) for M=4 (n=14, W=15)

    // ------------------------------------------------------------------ device-safe scalar helpers
    /** PTX-safe acos (Newton-refined) — Math.acos does not lower on the PTX backend. VERBATIM the
     *  ChainBendingForceSystem/CrossBridgeSystem device acos (double-accurate after 2 Newton steps). */
    static double dacos(double x) {
        if (x > 1.0)  x = 1.0;
        if (x < -1.0) x = -1.0;
        double y;
        if (x > 0.95) { double t = 1.0 - x; if (t < 0.0) t = 0.0; y = Math.sqrt(2.0 * t); }
        else if (x < -0.95) { double t = 1.0 + x; if (t < 0.0) t = 0.0; y = 3.141592653589793 - Math.sqrt(2.0 * t); }
        else { double ax = (x < 0.0) ? -x : x; double p = (-0.0187293 * ax + 0.0742610) * ax - 0.2121144;
               p = (p * ax + 1.5707963); p = p * Math.sqrt(1.0 - ax); y = (x < 0.0) ? (3.141592653589793 - p) : p; }
        double s = Math.sin(y);
        if (s > 1.0e-12 || s < -1.0e-12) y = y + (Math.cos(y) - x) / s;
        s = Math.sin(y);
        if (s > 1.0e-12 || s < -1.0e-12) y = y + (Math.cos(y) - x) / s;
        return y;
    }
    /** A1(θ)=θ/sinθ (finite at θ→0). */
    static double a1(double th) { double t2 = th * th;
        if (th < 1e-3) return 1.0 + t2 / 6.0 + 7.0 * t2 * t2 / 360.0;
        return th / Math.sin(th); }
    /** A2(θ)=(1−θcotθ)/sin²θ (finite, series near 0 to dodge cancellation). */
    static double a2(double th) {
        if (th < 5e-2) { double t2 = th * th; return 1.0 / 3.0 + (2.0 / 15.0) * t2 + (2.0 / 63.0) * t2 * t2; }
        double s = Math.sin(th); return (1.0 - th * Math.cos(th) / s) / (s * s); }
    /** add val to K[r][c] (augmented sys), free-DOF only (r,c ≥ 0). */
    static void addK(DoubleArray sys, int base, int W, int r, int c, double val) {
        if (r >= 0 && c >= 0) sys.set(base + r * W + c, sys.get(base + r * W + c) + val); }
    /** add val to RHS F[r], free-DOF only. */
    static void addF(DoubleArray sys, int base, int W, int nn, int r, double val) {
        if (r >= 0) sys.set(base + r * W + nn, sys.get(base + r * W + nn) + val); }

    // ================================================================= the device relaxation kernel
    public static void beamRelaxAnalytic(DoubleArray nodes, DoubleArray frame, DoubleArray q, DoubleArray F8h,
                                         DoubleArray params, DoubleArray sys, DoubleArray outGeom,
                                         IntArray status, IntArray iters, IntArray counts) {
        int nM = counts.get(0), maxIt = counts.get(1), M = counts.get(2);
        int nF = 3 * M, n = nF + 2, W = n + 1;
        double tol = 3e-7;   // µm max node move per step (matches the CPU converged criterion)
        for (@Parallel int m = 0; m < nM; m++) {
            double bx = frame.get(m), by = frame.get(nM + m), bz = frame.get(2 * nM + m);
            double ex = frame.get(3 * nM + m), ey = frame.get(4 * nM + m), ez = frame.get(5 * nM + m);   // econv (geomC rotation axis)
            double ux = frame.get(6 * nM + m), uy = frame.get(7 * nM + m), uz = frame.get(8 * nM + m);   // eup (F8 generalized-force axis)
            double gEx = frame.get(9 * nM + m), gEy = frame.get(10 * nM + m), gEz = frame.get(11 * nM + m);
            double gTx = frame.get(12 * nM + m), gTy = frame.get(13 * nM + m), gTz = frame.get(14 * nM + m);
            double lb = params.get(m), rF8x = params.get(nM + m), rF8y = params.get(2 * nM + m);
            double rCx = params.get(3 * nM + m), rCy = params.get(4 * nM + m);
            double kF8Code = params.get(5 * nM + m), kc = params.get(6 * nM + m), kbnd = params.get(7 * nM + m);
            double gPhi = params.get(8 * nM + m), gPsi = params.get(9 * nM + m), dt = params.get(10 * nM + m);
            double ks = params.get(11 * nM + m), l0um = params.get(12 * nM + m), kbend = params.get(13 * nM + m);
            double floorZ = params.get(14 * nM + m), kfloor = params.get(15 * nM + m), gNode = params.get(16 * nM + m);
            double l0m = l0um * 1e-6, aN = gNode / dt, aphi = gPhi / dt, apsi = gPsi / dt;
            double f8x = F8h.get(m), f8y = F8h.get(nM + m), f8z = F8h.get(2 * nM + m);
            double phi = q.get(m), psi = q.get(nM + m), thetaS = q.get(2 * nM + m), psiActin = q.get(3 * nM + m);
            int base = m * (n * W);   // per-item scratch = n*(n+1), M-generic (=210 at M=4 [L40], =420 at M=6 [L60])
            int st = 0, itDone = maxIt;

            for (int it = 0; it < maxIt; it++) {
                // zero the augmented system
                for (int i = 0; i < n; i++) for (int j = 0; j < W; j++) sys.set(base + i * W + j, 0.0);

                // geomC : P = node M, C, xF8   (needed for the F8 block)
                double Px = nodes.get((3 * M) * nM + m), Py = nodes.get((3 * M + 1) * nM + m), Pz = nodes.get((3 * M + 2) * nM + m);
                double cphi = Math.cos(phi), sphi = Math.sin(phi);
                double uBx = ux * cphi + bx * sphi, uBy = uy * cphi + by * sphi, uBz = uz * cphi + bz * sphi;
                double Cx = Px + uBx * lb, Cy = Py + uBy * lb, Cz = Pz + uBz * lb;
                double d0x = bx * (rF8x - rCx) + ux * (rF8y - rCy), d0y = by * (rF8x - rCx) + uy * (rF8y - rCy), d0z = bz * (rF8x - rCx) + uz * (rF8y - rCy);
                double cpsi = Math.cos(psi), spsi = Math.sin(psi);
                double xF8x = Cx + (d0x * cpsi + (ey * d0z - ez * d0y) * spsi);   // rotConv about econv (matches geomC)
                double xF8y = Cy + (d0y * cpsi + (ez * d0x - ex * d0z) * spsi);
                double xF8z = Cz + (d0z * cpsi + (ex * d0y - ey * d0x) * spsi);

                // ---------- STRETCH (force + Hessian) over segments 0..M-1 ----------
                for (int i = 0; i < M; i++) {
                    double ax = nodes.get((3*(i+1))*nM+m) - nodes.get((3*i)*nM+m);
                    double ay = nodes.get((3*(i+1)+1)*nM+m) - nodes.get((3*i+1)*nM+m);
                    double az = nodes.get((3*(i+1)+2)*nM+m) - nodes.get((3*i+2)*nM+m);
                    double len = Math.sqrt(ax*ax+ay*ay+az*az); if (len < 1e-15) continue;
                    double s = 1.0/len, uxx = ax*s, uyy = ay*s, uzz = az*s;
                    double lenM = len*1e-6, t = ks*(lenM - l0m), tL = t/lenM;
                    int ri = (i>=1)?(i-1)*3:-1000, rj = i*3;            // free rows for node i, i+1
                    // force: node i += t*u, node i+1 -= t*u
                    addF(sys,base,W,n, ri+0, t*uxx); addF(sys,base,W,n, ri+1, t*uyy); addF(sys,base,W,n, ri+2, t*uzz);
                    addF(sys,base,W,n, rj+0,-t*uxx); addF(sys,base,W,n, rj+1,-t*uyy); addF(sys,base,W,n, rj+2,-t*uzz);
                    // Hessian block Kb[p][q] = ks*u_p u_q + tL*(I-uu)
                    for (int p=0;p<3;p++) for(int qq=0;qq<3;qq++){
                        double up = (p==0)?uxx:((p==1)?uyy:uzz), uq=(qq==0)?uxx:((qq==1)?uyy:uzz);
                        double id=(p==qq)?1.0:0.0; double kb2 = ks*up*uq + tL*(id-up*uq);
                        addK(sys,base,W, ri+p, ri+qq, kb2); addK(sys,base,W, rj+p, rj+qq, kb2);
                        addK(sys,base,W, ri+p, rj+qq,-kb2); addK(sys,base,W, rj+p, ri+qq,-kb2);
                    }
                }
                // ---------- BEND clamped joint 0 (node1 only, fixed tangent g4Tan) ----------
                {
                    double b0x = nodes.get(3*nM+m)-nodes.get(m), b0y = nodes.get((4)*nM+m)-nodes.get(nM+m), b0z = nodes.get(5*nM+m)-nodes.get(2*nM+m);
                    double lbb = Math.sqrt(b0x*b0x+b0y*b0y+b0z*b0z);
                    if (lbb > 1e-12) {
                        double ilb=1.0/lbb, c = gTx*b0x+gTy*b0y+gTz*b0z; c*=ilb; if(c>1)c=1; if(c<-1)c=-1;
                        double th = dacos(c), A1=a1(th), A2=a2(th); double clb2=c*ilb*ilb;
                        double g0x=gTx*ilb-clb2*b0x, g0y=gTy*ilb-clb2*b0y, g0z=gTz*ilb-clb2*b0z;   // ∂c/∂b0
                        int r1=0;   // node1 free row base
                        addF(sys,base,W,n, r1+0, kbend*A1*1e6*g0x); addF(sys,base,W,n, r1+1, kbend*A1*1e6*g0y); addF(sys,base,W,n, r1+2, kbend*A1*1e6*g0z);
                        double ilb2=ilb*ilb, ilb3=ilb2*ilb, ilb4=ilb2*ilb2;
                        for (int p=0;p<3;p++) for(int qq=0;qq<3;qq++){
                            double b0p=(p==0)?b0x:((p==1)?b0y:b0z), b0q=(qq==0)?b0x:((qq==1)?b0y:b0z);
                            double tp=(p==0)?gTx:((p==1)?gTy:gTz), tq=(qq==0)?gTx:((qq==1)?gTy:gTz);
                            double id=(p==qq)?1.0:0.0;
                            double Hc = -(b0p*tq+tp*b0q)*ilb3 - c*id*ilb2 + 3.0*c*b0p*b0q*ilb4;
                            double gp=(p==0)?g0x:((p==1)?g0y:g0z), gq=(qq==0)?g0x:((qq==1)?g0y:g0z);
                            addK(sys,base,W, r1+p, r1+qq, 1e12*kbend*(A2*gp*gq - A1*Hc));
                        }
                    }
                }
                // ---------- BEND interior joints j=1..M-1 ----------
                for (int j=1;j<M;j++){
                    double ax = nodes.get((3*j)*nM+m)-nodes.get((3*(j-1))*nM+m);
                    double ay = nodes.get((3*j+1)*nM+m)-nodes.get((3*(j-1)+1)*nM+m);
                    double az = nodes.get((3*j+2)*nM+m)-nodes.get((3*(j-1)+2)*nM+m);
                    double bxx = nodes.get((3*(j+1))*nM+m)-nodes.get((3*j)*nM+m);
                    double byy = nodes.get((3*(j+1)+1)*nM+m)-nodes.get((3*j+1)*nM+m);
                    double bzz = nodes.get((3*(j+1)+2)*nM+m)-nodes.get((3*j+2)*nM+m);
                    double la = Math.sqrt(ax*ax+ay*ay+az*az), lbn = Math.sqrt(bxx*bxx+byy*byy+bzz*bzz);
                    if (la<1e-12 || lbn<1e-12) continue;
                    double ila=1.0/la, ilb=1.0/lbn, iab=ila*ilb;
                    double c = (ax*bxx+ay*byy+az*bzz)*iab; if(c>1)c=1; if(c<-1)c=-1;
                    double th=dacos(c), A1=a1(th), A2=a2(th);
                    double cla2=c*ila*ila, clb2=c*ilb*ilb;
                    double Dx=bxx*iab-cla2*ax, Dy=byy*iab-cla2*ay, Dz=bzz*iab-cla2*az;      // ∂c/∂a
                    double Ex=ax*iab-clb2*bxx, Ey=ay*iab-clb2*byy, Ez=az*iab-clb2*bzz;      // ∂c/∂b
                    // node grads of c: node0(jm1)=-D, node1(j)=D-E, node2(jp1)=E
                    double gc0x=-Dx,gc0y=-Dy,gc0z=-Dz, gc1x=Dx-Ex,gc1y=Dy-Ey,gc1z=Dz-Ez, gc2x=Ex,gc2y=Ey,gc2z=Ez;
                    int rJm1=(j-1>=1)?(j-2)*3:-1000, rJ=(j-1)*3, rJp1=j*3;
                    // force = kb·A1·1e6·∇c
                    addF(sys,base,W,n, rJm1+0,kbend*A1*1e6*gc0x); addF(sys,base,W,n, rJm1+1,kbend*A1*1e6*gc0y); addF(sys,base,W,n, rJm1+2,kbend*A1*1e6*gc0z);
                    addF(sys,base,W,n, rJ+0,  kbend*A1*1e6*gc1x); addF(sys,base,W,n, rJ+1,  kbend*A1*1e6*gc1y); addF(sys,base,W,n, rJ+2,  kbend*A1*1e6*gc1z);
                    addF(sys,base,W,n, rJp1+0,kbend*A1*1e6*gc2x); addF(sys,base,W,n, rJp1+1,kbend*A1*1e6*gc2y); addF(sys,base,W,n, rJp1+2,kbend*A1*1e6*gc2z);
                    double ila2=ila*ila, ila3=ila2*ila, ila4=ila2*ila2, ilb2=ilb*ilb, ilb3=ilb2*ilb, ilb4=ilb2*ilb2;
                    // Hess E = kb·A2·∇c⊗∇c − kb·A1·∇²c ; node-pair loop over {jm1,j,jp1} with incidence (sa,sb)
                    for (int ai=0; ai<3; ai++){
                        int rA = (ai==0)?rJm1:((ai==1)?rJ:rJp1);
                        double saA=(ai==0)?-1.0:((ai==1)?1.0:0.0), sbA=(ai==0)?0.0:((ai==1)?-1.0:1.0);
                        for (int bi=0; bi<3; bi++){
                            int rB = (bi==0)?rJm1:((bi==1)?rJ:rJp1);
                            double saB=(bi==0)?-1.0:((bi==1)?1.0:0.0), sbB=(bi==0)?0.0:((bi==1)?-1.0:1.0);
                            for (int p=0;p<3;p++){
                                double ap=(p==0)?ax:((p==1)?ay:az), bp=(p==0)?bxx:((p==1)?byy:bzz);
                                double gAp=(p==0)?((ai==0)?gc0x:((ai==1)?gc1x:gc2x)):((p==1)?((ai==0)?gc0y:((ai==1)?gc1y:gc2y)):((ai==0)?gc0z:((ai==1)?gc1z:gc2z)));
                                for (int qq=0;qq<3;qq++){
                                    double aq=(qq==0)?ax:((qq==1)?ay:az), bq=(qq==0)?bxx:((qq==1)?byy:bzz);
                                    double id=(p==qq)?1.0:0.0;
                                    double Haa=-(ap*bq+bp*aq)*ila3*ilb - c*id*ila2 + 3.0*c*ap*aq*ila4;
                                    double Hbb=-(bp*aq+ap*bq)*ilb3*ila - c*id*ilb2 + 3.0*c*bp*bq*ilb4;
                                    double Hab_pq= id*iab - bp*bq*ila*ilb3 - ap*aq*ila3*ilb + c*ap*bq*ila2*ilb2;   // ∂²c/∂a_p∂b_q
                                    double Hab_qp= id*iab - bq*bp*ila*ilb3 - aq*ap*ila3*ilb + c*aq*bp*ila2*ilb2;   // ∂²c/∂a_q∂b_p
                                    double Hc = saA*saB*Haa + sbA*sbB*Hbb + saA*sbB*Hab_pq + sbA*saB*Hab_qp;
                                    double gBq=(qq==0)?((bi==0)?gc0x:((bi==1)?gc1x:gc2x)):((qq==1)?((bi==0)?gc0y:((bi==1)?gc1y:gc2y)):((bi==0)?gc0z:((bi==1)?gc1z:gc2z)));
                                    addK(sys,base,W, rA+p, rB+qq, 1e12*kbend*(A2*gAp*gBq - A1*Hc));
                                }
                            }
                        }
                    }
                }
                // ---------- FLOOR (free nodes 1..M) ----------
                for (int j=1;j<=M;j++){
                    double z = nodes.get((3*j)*nM+m)*ux + nodes.get((3*j+1)*nM+m)*uy + nodes.get((3*j+2)*nM+m)*uz;
                    if (z < floorZ){ double fk = kfloor*(floorZ - z)*1e-6; int r=(j-1)*3;
                        addF(sys,base,W,n, r+0, fk*ux); addF(sys,base,W,n, r+1, fk*uy); addF(sys,base,W,n, r+2, fk*uz);
                        for(int p=0;p<3;p++){ double ep=(p==0)?ux:((p==1)?uy:uz);
                            for(int qq=0;qq<3;qq++){ double eq=(qq==0)?ux:((qq==1)?uy:uz); addK(sys,base,W, r+p, r+qq, kfloor*ep*eq); } }
                    }
                }
                // ---------- node drag diagonal ----------
                for (int r=0;r<nF;r++) addK(sys,base,W, r, r, aN);

                // ---------- F8 / converter / bind block (E = econv, the EXACT geometry axis) ----------
                // REPAIRED 2026-08-12: was E = eup, orthogonal to the true Jacobian for planar converter geometry.
                // See matS2SolveStep's axis note and docs/motor/RESTORED_3D_HEAD_TILT_DOF.md.
                int pB = 3*(M-1), iPhi = nF, iPsi = nF+1;
                double cpx=Cx-Px, cpy=Cy-Py, cpz=Cz-Pz, fcx=xF8x-Cx, fcy=xF8y-Cy, fcz=xF8z-Cz;
                double Jphix=ey*cpz-ez*cpy, Jphiy=ez*cpx-ex*cpz, Jphiz=ex*cpy-ey*cpx;
                double Jpsix=ey*fcz-ez*fcy, Jpsiy=ez*fcx-ex*fcz, Jpsiz=ex*fcy-ey*fcx;
                double J03=Jphix*1e-6,J04=Jpsix*1e-6,J13=Jphiy*1e-6,J14=Jpsiy*1e-6,J23=Jphiz*1e-6,J24=Jpsiz*1e-6;
                double kfSI=kF8Code*1e6;
                // J columns: col0=(1,0,0),col1=(0,1,0),col2=(0,0,1),col3=(J03,J13,J23),col4=(J04,J14,J24)
                // GN block kfSI*J^T J into map {pB,pB+1,pB+2,iPhi,iPsi}
                // row/col 0..4 → dof: 0→pB,1→pB+1,2→pB+2,3→iPhi,4→iPsi
                // precompute J as J[3][5]
                // we add kfSI*(J[0][i]J[0][j]+J[1][i]J[1][j]+J[2][i]J[2][j])
                for (int i=0;i<5;i++){
                    double Ji0=(i==0)?1:0, Ji1=(i==1)?1:0, Ji2=(i==2)?1:0;
                    if(i==3){Ji0=J03;Ji1=J13;Ji2=J23;} if(i==4){Ji0=J04;Ji1=J14;Ji2=J24;}
                    int di=(i<3)?(pB+i):(i==3?iPhi:iPsi);
                    for (int jj=0;jj<5;jj++){
                        double Jj0=(jj==0)?1:0, Jj1=(jj==1)?1:0, Jj2=(jj==2)?1:0;
                        if(jj==3){Jj0=J03;Jj1=J13;Jj2=J23;} if(jj==4){Jj0=J04;Jj1=J14;Jj2=J24;}
                        int dj=(jj<3)?(pB+jj):(jj==3?iPhi:iPsi);
                        addK(sys,base,W, di, dj, kfSI*(Ji0*Jj0+Ji1*Jj1+Ji2*Jj2));
                    }
                }
                addK(sys,base,W, iPhi,iPhi, kc); addK(sys,base,W, iPhi,iPsi,-kc); addK(sys,base,W, iPsi,iPhi,-kc); addK(sys,base,W, iPsi,iPsi, kc+kbnd);
                addK(sys,base,W, iPhi,iPhi, aphi); addK(sys,base,W, iPsi,iPsi, apsi);
                double th = psi - phi;
                double caFx=cpy*f8z-cpz*f8y, caFy=cpz*f8x-cpx*f8z, caFz=cpx*f8y-cpy*f8x;
                double fcFx=fcy*f8z-fcz*f8y, fcFy=fcz*f8x-fcx*f8z, fcFz=fcx*f8y-fcy*f8x;
                double QphiF8=(ex*caFx+ey*caFy+ez*caFz)*1e-6, QpsiF8=(ex*fcFx+ey*fcFy+ez*fcFz)*1e-6;   // E = econv (repaired)
                addF(sys,base,W,n, pB+0, f8x); addF(sys,base,W,n, pB+1, f8y); addF(sys,base,W,n, pB+2, f8z);
                addF(sys,base,W,n, iPhi, QphiF8 + kc*(th-thetaS));
                addF(sys,base,W,n, iPsi, QpsiF8 - kc*(th-thetaS) - kbnd*(psi-psiActin));

                // ---------- solve (Gauss–Jordan, partial pivot) in place ----------
                for (int c=0;c<n;c++){
                    int p=c; double bestv=sys.get(base+c*W+c); if(bestv<0)bestv=-bestv;
                    for(int r=c+1;r<n;r++){ double v=sys.get(base+r*W+c); if(v<0)v=-v; if(v>bestv){bestv=v;p=r;} }
                    if(p!=c){ for(int k=0;k<W;k++){ double tmp=sys.get(base+c*W+k); sys.set(base+c*W+k, sys.get(base+p*W+k)); sys.set(base+p*W+k, tmp); } }
                    double piv=sys.get(base+c*W+c); double apiv=piv<0?-piv:piv;
                    if(!(apiv>1e-300)) st=1;
                    for(int r=0;r<n;r++){ if(r==c) continue; double fac=sys.get(base+r*W+c)/piv;
                        for(int k=c;k<W;k++) sys.set(base+r*W+k, sys.get(base+r*W+k)-fac*sys.get(base+c*W+k)); }
                }
                // ---------- apply increment + convergence ----------
                double mv=0;
                for (int j=1;j<=M;j++){ int fb=(j-1)*3;
                    for(int k=0;k<3;k++){ double dqm=sys.get(base+(fb+k)*W+n)/sys.get(base+(fb+k)*W+(fb+k));
                        double dnode=dqm*1e6; double idx=(3*j+k); nodes.set((int)idx*nM+m, nodes.get((3*j+k)*nM+m)+dnode);
                        double amv=dnode<0?-dnode:dnode; if(amv>mv) mv=amv; } }
                double dphi=sys.get(base+iPhi*W+n)/sys.get(base+iPhi*W+iPhi);
                double dpsi=sys.get(base+iPsi*W+n)/sys.get(base+iPsi*W+iPsi);
                phi+=dphi; psi+=dpsi;
                // re-pin node0 = g4E
                nodes.set(m, gEx); nodes.set(nM+m, gEy); nodes.set(2*nM+m, gEz);
                if (mv < tol){ itDone = it+1; break; }
            }

            // final geomC → outGeom
            double Px2=nodes.get((3*M)*nM+m), Py2=nodes.get((3*M+1)*nM+m), Pz2=nodes.get((3*M+2)*nM+m);
            double cphi=Math.cos(phi), sphi=Math.sin(phi);
            double uBx=ux*cphi+bx*sphi, uBy=uy*cphi+by*sphi, uBz=uz*cphi+bz*sphi;
            double Cx=Px2+uBx*lb, Cy=Py2+uBy*lb, Cz=Pz2+uBz*lb;
            double d0x=bx*(rF8x-rCx)+ux*(rF8y-rCy), d0y=by*(rF8x-rCx)+uy*(rF8y-rCy), d0z=bz*(rF8x-rCx)+uz*(rF8y-rCy);
            double cpsi=Math.cos(psi), spsi=Math.sin(psi);
            double xF8x=Cx+(d0x*cpsi+(ey*d0z-ez*d0y)*spsi), xF8y=Cy+(d0y*cpsi+(ez*d0x-ex*d0z)*spsi), xF8z=Cz+(d0z*cpsi+(ex*d0y-ey*d0x)*spsi);
            double rcx=bx*rCx+ux*rCy, rcy=by*rCx+uy*rCy, rcz=bz*rCx+uz*rCy;
            double xHx=Cx-(rcx*cpsi+(ey*rcz-ez*rcy)*spsi), xHy=Cy-(rcy*cpsi+(ez*rcx-ex*rcz)*spsi), xHz=Cz-(rcz*cpsi+(ex*rcy-ey*rcx)*spsi);
            outGeom.set(m,Cx); outGeom.set(nM+m,Cy); outGeom.set(2*nM+m,Cz);
            outGeom.set(3*nM+m,xHx); outGeom.set(4*nM+m,xHy); outGeom.set(5*nM+m,xHz);
            outGeom.set(6*nM+m,xF8x); outGeom.set(7*nM+m,xF8y); outGeom.set(8*nM+m,xF8z);
            q.set(m,phi); q.set(nM+m,psi);
            status.set(m, st); iters.set(m, itDone);
        }
    }

    // ================================================================= reduced OBSERVABLES (device)
    /**
     * Reduced beam observables from the RESIDENT node array — so the beam state never leaves the device
     * (only these scalars cross). EXACT flat replica of {@link ExplicitBeamAnalytic}'s stretch/bend/floor
     * energy (same formulas, same units) using the device-safe {@code dacos}. NO solve, NO state mutation.
     *
     * <p>{@code obs} stride 5 per motor (comp·nM+m): [0]=stretchE(J) [1]=bendE(J) [2]=floorE(J)
     * [3]=totalE(J) [4]=contour(µm). frame/params share the beamRelaxAnalytic layout (eup=frame 6..8,
     * g4Tan=frame 12..14; ks=params 11, l0µm=params 12, kb=params 13, floorZ=params 14, kfloor=params 15).
     */
    public static void beamObserve(DoubleArray nodes, DoubleArray frame, DoubleArray params,
                                   IntArray counts, DoubleArray obs) {
        int nM = counts.get(0), M = counts.get(2);
        for (@Parallel int m = 0; m < nM; m++) {
            double ux = frame.get(6*nM+m), uy = frame.get(7*nM+m), uz = frame.get(8*nM+m);       // eup (floor normal)
            double gTx = frame.get(12*nM+m), gTy = frame.get(13*nM+m), gTz = frame.get(14*nM+m); // g4Tan (clamp0 tangent)
            double ks = params.get(11*nM+m), l0um = params.get(12*nM+m), kb = params.get(13*nM+m);
            double floorZ = params.get(14*nM+m), kfloor = params.get(15*nM+m);
            double l0m = l0um * 1e-6;
            double stretchE = 0, bendE = 0, floorE = 0, contour = 0;
            // stretch + contour over segments 0..M-1
            for (int i = 0; i < M; i++) {
                double ax = nodes.get((3*(i+1))*nM+m) - nodes.get((3*i)*nM+m);
                double ay = nodes.get((3*(i+1)+1)*nM+m) - nodes.get((3*i+1)*nM+m);
                double az = nodes.get((3*(i+1)+2)*nM+m) - nodes.get((3*i+2)*nM+m);
                double len = Math.sqrt(ax*ax+ay*ay+az*az); contour += len;
                if (len >= 1e-15) { double d = len*1e-6 - l0m; stretchE += 0.5*ks*d*d; }
            }
            // bend clamped joint 0 (g4Tan vs b0=node1-node0)
            {
                double b0x = nodes.get(3*nM+m)-nodes.get(m), b0y = nodes.get(4*nM+m)-nodes.get(nM+m), b0z = nodes.get(5*nM+m)-nodes.get(2*nM+m);
                double lbb = Math.sqrt(b0x*b0x+b0y*b0y+b0z*b0z);
                if (lbb > 1e-12) { double c = (gTx*b0x+gTy*b0y+gTz*b0z)/lbb; if(c>1)c=1; if(c<-1)c=-1; double th = dacos(c); bendE += 0.5*kb*th*th; }
            }
            // bend interior joints 1..M-1
            for (int j = 1; j < M; j++) {
                double ax = nodes.get((3*j)*nM+m)-nodes.get((3*(j-1))*nM+m), ay = nodes.get((3*j+1)*nM+m)-nodes.get((3*(j-1)+1)*nM+m), az = nodes.get((3*j+2)*nM+m)-nodes.get((3*(j-1)+2)*nM+m);
                double bx = nodes.get((3*(j+1))*nM+m)-nodes.get((3*j)*nM+m), by = nodes.get((3*(j+1)+1)*nM+m)-nodes.get((3*j+1)*nM+m), bz = nodes.get((3*(j+1)+2)*nM+m)-nodes.get((3*j+2)*nM+m);
                double la = Math.sqrt(ax*ax+ay*ay+az*az), lb = Math.sqrt(bx*bx+by*by+bz*bz);
                if (la >= 1e-12 && lb >= 1e-12) { double c = (ax*bx+ay*by+az*bz)/(la*lb); if(c>1)c=1; if(c<-1)c=-1; double th = dacos(c); bendE += 0.5*kb*th*th; }
            }
            // floor penalty over nodes 0..M
            for (int j = 0; j <= M; j++) {
                double z = nodes.get((3*j)*nM+m)*ux + nodes.get((3*j+1)*nM+m)*uy + nodes.get((3*j+2)*nM+m)*uz;
                if (z < floorZ) { double pen = (floorZ - z)*1e-6; floorE += 0.5*kfloor*pen*pen; }
            }
            obs.set(m, stretchE); obs.set(nM+m, bendE); obs.set(2*nM+m, floorE); obs.set(3*nM+m, stretchE+bendE+floorE); obs.set(4*nM+m, contour);
        }
    }

    /**
     * A zeroed per-motor converter-frame buffer (stride 13): flag {@code [12] = 0} for every motor, so
     * {@link #matBeamGeom} and {@link #matS2SolveStep} take the VERBATIM canonical branch over the base
     * {@code frame}. This is what every caller that does not use the noncanonical converter-stroke-plane
     * rotation passes, and it is why adding the parameter is byte-preserving.
     */
    public static DoubleArray identityConvFrame(int nM) {
        DoubleArray a = new DoubleArray(13 * Math.max(1, nM)); a.init(0.0); return a; }

    // =============================================== beam geom (pre-solve) + explicit head placement (mat)
    /** Compute the beam-derived head geometry (C, xH, xF8) from the CURRENT beam state — the device analog of
     *  {@code geom2D} (pre-solve). Writes outGeom [0..2]=C, [3..5]=xH, [6..8]=xF8 (planar c·nM+m). Same
     *  closed form as matS2SolveStep's final block (validated). Lets placeHead + bondForces run BEFORE the solve. */
    public static void matBeamGeom(DoubleArray nodes, DoubleArray frame, DoubleArray params, DoubleArray q,
                                   IntArray counts, DoubleArray outGeom, DoubleArray convF) {
        int nM = counts.get(0), M = counts.get(2);
        for (@Parallel int m = 0; m < nM; m++) {
            double bx=frame.get(m), by=frame.get(nM+m), bz=frame.get(2*nM+m);
            double ex=frame.get(3*nM+m), ey=frame.get(4*nM+m), ez=frame.get(5*nM+m);
            double ux=frame.get(6*nM+m), uy=frame.get(7*nM+m), uz=frame.get(8*nM+m);
            double lb=params.get(m), rF8x=params.get(nM+m), rF8y=params.get(2*nM+m), rCx=params.get(3*nM+m), rCy=params.get(4*nM+m);
            double phi=q.get(m), psi=q.get(nM+m);
            double Px=nodes.get((3*M)*nM+m), Py=nodes.get((3*M+1)*nM+m), Pz=nodes.get((3*M+2)*nM+m);
            double cphi=Math.cos(phi), sphi=Math.sin(phi);
            double cpsi=Math.cos(psi), spsi=Math.sin(psi);
            double Cx, Cy, Cz, xF8x, xF8y, xF8z, xHx, xHy, xHz;
            if (convF.get(12*nM+m) == 0.0) {   // CANONICAL branch — VERBATIM, byte-identical
                double uBx=ux*cphi+bx*sphi, uBy=uy*cphi+by*sphi, uBz=uz*cphi+bz*sphi;
                Cx=Px+uBx*lb; Cy=Py+uBy*lb; Cz=Pz+uBz*lb;
                double d0x=bx*(rF8x-rCx)+ux*(rF8y-rCy), d0y=by*(rF8x-rCx)+uy*(rF8y-rCy), d0z=bz*(rF8x-rCx)+uz*(rF8y-rCy);
                xF8x=Cx+(d0x*cpsi+(ey*d0z-ez*d0y)*spsi); xF8y=Cy+(d0y*cpsi+(ez*d0x-ex*d0z)*spsi); xF8z=Cz+(d0z*cpsi+(ex*d0y-ey*d0x)*spsi);
                double rcx=bx*rCx+ux*rCy, rcy=by*rCx+uy*rCy, rcz=bz*rCx+uz*rCy;
                xHx=Cx-(rcx*cpsi+(ey*rcz-ez*rcy)*spsi); xHy=Cy-(rcy*cpsi+(ez*rcx-ex*rcz)*spsi); xHz=Cz-(rcz*cpsi+(ex*rcy-ey*rcx)*spsi);
            } else {                            // ROTATED CONVERTER FRAME (ChiralSiteSystem.convFrameStep)
                double cb0=convF.get(m), cb1=convF.get(nM+m), cb2=convF.get(2*nM+m);
                double ce0=convF.get(3*nM+m), ce1=convF.get(4*nM+m), ce2=convF.get(5*nM+m);
                double cu0=convF.get(6*nM+m), cu1=convF.get(7*nM+m), cu2=convF.get(8*nM+m);
                double of0=convF.get(9*nM+m), of1=convF.get(10*nM+m), of2=convF.get(11*nM+m);
                double uBx=cu0*cphi+cb0*sphi, uBy=cu1*cphi+cb1*sphi, uBz=cu2*cphi+cb2*sphi;
                Cx=Px+of0+uBx*lb; Cy=Py+of1+uBy*lb; Cz=Pz+of2+uBz*lb;
                double d0x=cb0*(rF8x-rCx)+cu0*(rF8y-rCy), d0y=cb1*(rF8x-rCx)+cu1*(rF8y-rCy), d0z=cb2*(rF8x-rCx)+cu2*(rF8y-rCy);
                xF8x=Cx+(d0x*cpsi+(ce1*d0z-ce2*d0y)*spsi); xF8y=Cy+(d0y*cpsi+(ce2*d0x-ce0*d0z)*spsi); xF8z=Cz+(d0z*cpsi+(ce0*d0y-ce1*d0x)*spsi);
                double rcx=cb0*rCx+cu0*rCy, rcy=cb1*rCx+cu1*rCy, rcz=cb2*rCx+cu2*rCy;
                xHx=Cx-(rcx*cpsi+(ce1*rcz-ce2*rcy)*spsi); xHy=Cy-(rcy*cpsi+(ce2*rcx-ce0*rcz)*spsi); xHz=Cz-(rcz*cpsi+(ce0*rcy-ce1*rcx)*spsi);
            }
            outGeom.set(m,Cx); outGeom.set(nM+m,Cy); outGeom.set(2*nM+m,Cz);
            outGeom.set(3*nM+m,xHx); outGeom.set(4*nM+m,xHy); outGeom.set(5*nM+m,xHz);
            outGeom.set(6*nM+m,xF8x); outGeom.set(7*nM+m,xF8y); outGeom.set(8*nM+m,xF8z);
        }
    }

    /**
     * NECK–HEAD TILT (noncanonical, DEFAULT-OFF): {@link #matBeamGeom} plus ONE additional orientational
     * coordinate {@code chi} that rotates the HEAD — and only the head — about the neck–head joint C.
     *
     * <h3>Why it exists</h3>
     * The two-body head's long axis is {@code eBind = R_econv(psi)·p1}, so {@code eBind · econv = 0} for every
     * psi: one fixed motor can only face directions on a single lab-fixed great circle (2.9 % of 4pi measured).
     * The sphere-head motor this topology replaced explored 97.8 % of 4pi. Report:
     * {@code docs/motor/MYOSIN_HEAD_ORIENTATION_DOF_HISTORY.md}.
     *
     * <h3>The coordinate</h3>
     * <pre>
     *   e0   = normalize(xF8 - xH)        the CURRENT (psi-only) head long axis;  e0 . econv = 0
     *   that = e0 x econv                 unit by construction (e0 ⊥ econv), lies IN the psi plane, ⊥ e0
     *   head rotates rigidly about C by chi around that
     *     =>  eBind(psi, chi) = cos(chi)·e0(psi) + sin(chi)·econv
     * </pre>
     * so {@code (psi, chi)} are spherical coordinates of the head axis with {@code econv} as the pole:
     * psi is the azimuth (the existing in-plane swing, i.e. the stroke plane) and chi the polar tilt out of it.
     * The sign follows from the natural axis {@code e0 x econv}; nothing is hard-coded against it.
     *
     * <h3>What rotates and what does NOT</h3>
     * Only the two head-side offsets {@code (xF8 - C)} and {@code (xH - C)} are rotated, rigidly and about the
     * SAME axis, so the head moves as one rigid body about the neck–head joint. C itself, the lever/neck
     * ({@code phi}, {@code uB}), the converter plane, the S2 beam nodes, the base triad and the anchors are
     * untouched — this is a neck–head DOF, not a base rotation. {@code theta = psi - phi} therefore keeps its
     * meaning and the stroke plane is unchanged; chi is orthogonal to it.
     *
     * <h3>Byte identity</h3>
     * {@code chi == 0} skips the rotation branch entirely, so the emitted geometry is bit-for-bit the 7-argument
     * {@link #matBeamGeom}. The original kernel is NOT modified and remains the wired default.
     */
    public static void matBeamGeomTilt(DoubleArray nodes, DoubleArray frame, DoubleArray params, DoubleArray q,
                                       IntArray counts, DoubleArray outGeom, DoubleArray convF, DoubleArray chiHead) {
        int nM = counts.get(0), M = counts.get(2);
        for (@Parallel int m = 0; m < nM; m++) {
            double bx=frame.get(m), by=frame.get(nM+m), bz=frame.get(2*nM+m);
            double ex=frame.get(3*nM+m), ey=frame.get(4*nM+m), ez=frame.get(5*nM+m);
            double ux=frame.get(6*nM+m), uy=frame.get(7*nM+m), uz=frame.get(8*nM+m);
            double lb=params.get(m), rF8x=params.get(nM+m), rF8y=params.get(2*nM+m), rCx=params.get(3*nM+m), rCy=params.get(4*nM+m);
            double phi=q.get(m), psi=q.get(nM+m);
            double Px=nodes.get((3*M)*nM+m), Py=nodes.get((3*M+1)*nM+m), Pz=nodes.get((3*M+2)*nM+m);
            double cphi=Math.cos(phi), sphi=Math.sin(phi);
            double cpsi=Math.cos(psi), spsi=Math.sin(psi);
            double Cx, Cy, Cz, xF8x, xF8y, xF8z, xHx, xHy, xHz;
            double ecx, ecy, ecz;                       // the converter axis this motor actually uses
            if (convF.get(12*nM+m) == 0.0) {            // CANONICAL branch — VERBATIM from matBeamGeom
                double uBx=ux*cphi+bx*sphi, uBy=uy*cphi+by*sphi, uBz=uz*cphi+bz*sphi;
                Cx=Px+uBx*lb; Cy=Py+uBy*lb; Cz=Pz+uBz*lb;
                double d0x=bx*(rF8x-rCx)+ux*(rF8y-rCy), d0y=by*(rF8x-rCx)+uy*(rF8y-rCy), d0z=bz*(rF8x-rCx)+uz*(rF8y-rCy);
                xF8x=Cx+(d0x*cpsi+(ey*d0z-ez*d0y)*spsi); xF8y=Cy+(d0y*cpsi+(ez*d0x-ex*d0z)*spsi); xF8z=Cz+(d0z*cpsi+(ex*d0y-ey*d0x)*spsi);
                double rcx=bx*rCx+ux*rCy, rcy=by*rCx+uy*rCy, rcz=bz*rCx+uz*rCy;
                xHx=Cx-(rcx*cpsi+(ey*rcz-ez*rcy)*spsi); xHy=Cy-(rcy*cpsi+(ez*rcx-ex*rcz)*spsi); xHz=Cz-(rcz*cpsi+(ex*rcy-ey*rcx)*spsi);
                ecx=ex; ecy=ey; ecz=ez;
            } else {                                    // ROTATED CONVERTER FRAME — VERBATIM from matBeamGeom
                double cb0=convF.get(m), cb1=convF.get(nM+m), cb2=convF.get(2*nM+m);
                double ce0=convF.get(3*nM+m), ce1=convF.get(4*nM+m), ce2=convF.get(5*nM+m);
                double cu0=convF.get(6*nM+m), cu1=convF.get(7*nM+m), cu2=convF.get(8*nM+m);
                double of0=convF.get(9*nM+m), of1=convF.get(10*nM+m), of2=convF.get(11*nM+m);
                double uBx=cu0*cphi+cb0*sphi, uBy=cu1*cphi+cb1*sphi, uBz=cu2*cphi+cb2*sphi;
                Cx=Px+of0+uBx*lb; Cy=Py+of1+uBy*lb; Cz=Pz+of2+uBz*lb;
                double d0x=cb0*(rF8x-rCx)+cu0*(rF8y-rCy), d0y=cb1*(rF8x-rCx)+cu1*(rF8y-rCy), d0z=cb2*(rF8x-rCx)+cu2*(rF8y-rCy);
                xF8x=Cx+(d0x*cpsi+(ce1*d0z-ce2*d0y)*spsi); xF8y=Cy+(d0y*cpsi+(ce2*d0x-ce0*d0z)*spsi); xF8z=Cz+(d0z*cpsi+(ce0*d0y-ce1*d0x)*spsi);
                double rcx=cb0*rCx+cu0*rCy, rcy=cb1*rCx+cu1*rCy, rcz=cb2*rCx+cu2*rCy;
                xHx=Cx-(rcx*cpsi+(ce1*rcz-ce2*rcy)*spsi); xHy=Cy-(rcy*cpsi+(ce2*rcx-ce0*rcz)*spsi); xHz=Cz-(rcz*cpsi+(ce0*rcy-ce1*rcx)*spsi);
                ecx=ce0; ecy=ce1; ecz=ce2;
            }
            // ---- NECK–HEAD TILT: rotate the head rigidly about C. chi == 0 ⇒ exactly matBeamGeom. ----------
            double chi = chiHead.get(m);
            if (chi != 0.0) {
                double e0x = xF8x - xHx, e0y = xF8y - xHy, e0z = xF8z - xHz;
                double el = Math.sqrt(e0x*e0x + e0y*e0y + e0z*e0z);
                if (el > 1.0e-20) {
                    double ie = 1.0 / el; e0x*=ie; e0y*=ie; e0z*=ie;
                    // tilt axis t = e0 x econv (unit: e0 ⊥ econv by construction of the psi rotation)
                    double tx = e0y*ecz - e0z*ecy, ty = e0z*ecx - e0x*ecz, tz = e0x*ecy - e0y*ecx;
                    double tl = Math.sqrt(tx*tx + ty*ty + tz*tz);
                    if (tl > 1.0e-20) {
                        double it = 1.0 / tl; tx*=it; ty*=it; tz*=it;
                        double c = Math.cos(chi), s = Math.sin(chi), omc = 1.0 - c;
                        // Rodrigues on the F8-side offset
                        double ax = xF8x - Cx, ay = xF8y - Cy, az = xF8z - Cz;
                        double da = tx*ax + ty*ay + tz*az;
                        double rax = ax*c + (ty*az - tz*ay)*s + tx*da*omc;
                        double ray = ay*c + (tz*ax - tx*az)*s + ty*da*omc;
                        double raz = az*c + (tx*ay - ty*ax)*s + tz*da*omc;
                        // Rodrigues on the head-centre offset — SAME axis, SAME angle ⇒ rigid head rotation
                        double hx = xHx - Cx, hy = xHy - Cy, hz = xHz - Cz;
                        double dh = tx*hx + ty*hy + tz*hz;
                        double rhx = hx*c + (ty*hz - tz*hy)*s + tx*dh*omc;
                        double rhy = hy*c + (tz*hx - tx*hz)*s + ty*dh*omc;
                        double rhz = hz*c + (tx*hy - ty*hx)*s + tz*dh*omc;
                        xF8x = Cx + rax; xF8y = Cy + ray; xF8z = Cz + raz;
                        xHx  = Cx + rhx; xHy  = Cy + rhy; xHz  = Cz + rhz;
                    }
                }
            }
            outGeom.set(m,Cx); outGeom.set(nM+m,Cy); outGeom.set(2*nM+m,Cz);
            outGeom.set(3*nM+m,xHx); outGeom.set(4*nM+m,xHy); outGeom.set(5*nM+m,xHz);
            outGeom.set(6*nM+m,xF8x); outGeom.set(7*nM+m,xF8y); outGeom.set(8*nM+m,xF8z);
        }
    }

    /** Explicit device head placement — faithful port of {@code placeHead2D}: head sub-body (h=3m+2) coord = xH,
     *  uVec = normalize(xF8 − xH), yVec = perp3(uVec). Reads the explicit outGeom ([3..5]=xH, [6..8]=xF8) and
     *  writes the FLOAT MotorStore.body so the shared {@code bondForces} consumes the beam-derived head geometry.
     *  {@code eupP} = the fallback axis. Placed for every bound motor (boundSeg≥0). */
    public static void matPlaceHeadExplicit(DoubleArray outGeom, IntArray boundSeg, DoubleArray eupP, IntArray counts,
                                            FloatArray motCoord, FloatArray motUVec, FloatArray motYVec) {
        int N = counts.get(0), nB = 3 * N;
        double eupx = eupP.get(0), eupy = eupP.get(1), eupz = eupP.get(2);
        for (@Parallel int m = 0; m < N; m++) {
            if (boundSeg.get(m) < 0) continue;
            int h = 3 * m + 2;
            double xHx = outGeom.get(3*N+m), xHy = outGeom.get(4*N+m), xHz = outGeom.get(5*N+m);
            double dx = outGeom.get(6*N+m) - xHx, dy = outGeom.get(7*N+m) - xHy, dz = outGeom.get(8*N+m) - xHz;
            double L = Math.sqrt(dx*dx+dy*dy+dz*dz);
            double uvx, uvy, uvz;
            if (L > 1e-12) { uvx = dx/L; uvy = dy/L; uvz = dz/L; } else { uvx = eupx; uvy = eupy; uvz = eupz; }
            double ax = Math.abs(uvx) < 0.9 ? 1 : 0, ay = Math.abs(uvx) < 0.9 ? 0 : 1, az = 0;
            double dd = ax*uvx + ay*uvy + az*uvz;
            double yx = ax - dd*uvx, yy = ay - dd*uvy, yz = az - dd*uvz;
            double yl = Math.sqrt(yx*yx+yy*yy+yz*yz); yx/=yl; yy/=yl; yz/=yl;
            motCoord.set(h,(float)xHx); motCoord.set(nB+h,(float)xHy); motCoord.set(2*nB+h,(float)xHz);
            motUVec.set(h,(float)uvx); motUVec.set(nB+h,(float)uvy); motUVec.set(2*nB+h,(float)uvz);
            motYVec.set(h,(float)yx); motYVec.set(nB+h,(float)yy); motYVec.set(2*nB+h,(float)yz);
        }
    }

    // ============================================================ explicit FREE BINDING (deterministic 8-gate)
    /**
     * Device port of the production explicit bind gate ({@code stepGlideS2} L6806–6810, over the beam geometry).
     * For every ACTIVE, unbound (boundSeg=FREE_BINDABLE=−1), ADP·Pi (nuc=2), bindable (noBind=0) motor: find the
     * nearest filament segment ({@code nearestSeg2D}) and evaluate the 8 DETERMINISTIC gates ({@code gate2D});
     * bind (boundSeg=s, bindArc) iff all pass. NO stochastic draw — binding is deterministic ⇒ no RNG, no RNG
     * stream shift in chemistry/Brownian. Geometry (C/xH/xF8 in {@code outGeom}) comes from {@code matBeamGeom}
     * (C/xF8/xH depend only on phi/psi, NOT thetaS ⇒ the same geom serves the gate and the mechanics). Race-free
     * (each motor writes only its own boundSeg/bindArc; several may bind the same segment — the CSR handles it).
     *
     * <p>{@code bindP}: [dBindNm, psiDeg, phiDeg, thetaDeg, preloadPn, energyKt, FIL_R, PHI_PRE_3E, aSemiZ, kT,
     * margin, orientOn]. {@code eupP}: the shared up-axis (headSide). counts=[N,_,_,nSeg].
     */
    public static void matBindExplicit(IntArray active, IntArray noBind, IntArray boundSeg, IntArray nuc,
                                       DoubleArray outGeom, DoubleArray q, FloatArray filCoord, FloatArray filUVec,
                                       FloatArray filSegLength, DoubleArray params, DoubleArray bindP, DoubleArray eupP,
                                       FloatArray bindArc, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3);
        double dBindNm = bindP.get(0), psiDeg = bindP.get(1), phiDeg = bindP.get(2), thetaDeg = bindP.get(3);
        double preloadPn = bindP.get(4), energyKt = bindP.get(5), FIL_R = bindP.get(6), PHI_PRE = bindP.get(7);
        double aSemiZ = bindP.get(8), kT = bindP.get(9), margin = bindP.get(10); int orientOn = (int) bindP.get(11);
        // CANONICAL FILAMENT-SEGMENT OWNERSHIP (bindP[12]: 0=canonical half-open, 1=legacy — regression only).
        // Canonical = clamped-closest-point nearest-seg + bindArc=footC+half∈[0,segLength] + machine-ε margin (bindP[10]).
        // Fixes the ownership-handoff gap (legacy first-min + half+0.02 overlap could own points past a joint ⇒
        // bindArc>segLength). Interior points BYTE-IDENTICAL to legacy (footC=foot); differs only for beyond-end/joint
        // points (clamped ⇒ correct handoff to the interior neighbour).
        boolean halfOpen = bindP.get(12) < 0.5; double eps = margin;
        double eupx = eupP.get(0), eupy = eupP.get(1), eupz = eupP.get(2), DEG = 180.0 / Math.PI;
        for (@Parallel int m = 0; m < N; m++) {
            if (active.get(m) != 1 || noBind.get(m) == 1 || boundSeg.get(m) != -1 || nuc.get(m) != 2) continue;
            double xF8x = outGeom.get(6*N+m), xF8y = outGeom.get(7*N+m), xF8z = outGeom.get(8*N+m);
            double xHx = outGeom.get(3*N+m), xHy = outGeom.get(4*N+m), xHz = outGeom.get(5*N+m);
            // nearest segment: legacy = perp distance, foot within half+0.02; half-open = distance to CLAMPED closest point.
            int best = -1; double bd = 1e9;
            for (int s = 0; s < nSeg; s++) {
                double half = 0.5 * filSegLength.get(s);
                double cx = filCoord.get(s), cy = filCoord.get(nSeg+s), cz = filCoord.get(2*nSeg+s);
                double ux = filUVec.get(s), uy = filUVec.get(nSeg+s), uz = filUVec.get(2*nSeg+s);
                double dx = xF8x-cx, dy = xF8y-cy, dz = xF8z-cz; double foot = dx*ux+dy*uy+dz*uz;
                double d2;
                if (halfOpen) {
                    double footC = foot < -half ? -half : (foot > half ? half : foot);   // clamp to segment
                    double qx = dx-footC*ux, qy = dy-footC*uy, qz = dz-footC*uz; d2 = qx*qx+qy*qy+qz*qz;
                } else {
                    if (foot > half+0.02 || foot < -(half+0.02)) continue;
                    double px = dx-foot*ux, py = dy-foot*uy, pz = dz-foot*uz; d2 = px*px+py*py+pz*pz;
                }
                if (d2 < bd) { bd = d2; best = s; }
            }
            if (best < 0) continue;
            int s = best; double half = 0.5 * filSegLength.get(s);
            double cx = filCoord.get(s), cy = filCoord.get(nSeg+s), cz = filCoord.get(2*nSeg+s);
            double ux = filUVec.get(s), uy = filUVec.get(nSeg+s), uz = filUVec.get(2*nSeg+s);
            double e1x = cx-half*ux, e1y = cy-half*uy, e1z = cz-half*uz;
            double dx = xF8x-cx, dy = xF8y-cy, dz = xF8z-cz; double foot = dx*ux+dy*uy+dz*uz;
            double footC = halfOpen ? (foot < -half ? -half : (foot > half ? half : foot)) : foot;
            double axx = cx+footC*ux, axy = cy+footC*uy, axz = cz+footC*uz;
            double conDist = Math.sqrt((xF8x-axx)*(xF8x-axx)+(xF8y-axy)*(xF8y-axy)+(xF8z-axz)*(xF8z-axz));
            double bindArcV = halfOpen ? (footC+half) : ((xF8x-e1x)*ux+(xF8y-e1y)*uy+(xF8z-e1z)*uz);
            double surf = (conDist - FIL_R) * 1e3;
            double phi = q.get(m), psi = q.get(N+m), thetaS = q.get(2*N+m), psiActin = q.get(3*N+m);
            double psiErr = Math.abs(psi-psiActin)*DEG, phiErr = Math.abs(phi-PHI_PRE)*DEG, thetaErr = Math.abs((psi-phi)-thetaS)*DEG;
            double kF8 = params.get(5*N+m), kconv = params.get(6*N+m), kbind = params.get(7*N+m);
            double preload = kF8 * conDist * 1e12;
            double dth = (psi-phi)-thetaS, dpa = psi-psiActin;
            double eKt = (0.5*kconv*dth*dth + 0.5*kbind*dpa*dpa) / kT;
            double headSide = ((xHx-cx)*eupx+(xHy-cy)*eupy+(xHz-cz)*eupz)*1e3;
            boolean g0 = surf < dBindNm;
            boolean g1 = orientOn == 0 || psiErr < psiDeg;
            boolean g2 = orientOn == 0 || phiErr < phiDeg;
            boolean g3 = orientOn == 0 || thetaErr < thetaDeg;
            boolean g4 = preload < preloadPn;
            boolean g5 = orientOn == 0 || eKt < energyKt;
            boolean g6 = headSide < aSemiZ * 1e3;
            boolean g7 = bindArcV > eps && bindArcV < 2*half - eps;
            if (g0 && g1 && g2 && g3 && g4 && g5 && g6 && g7) { boundSeg.set(m, s); bindArc.set(m, (float) bindArcV); }
        }
    }

    // ============================================================ CONTINUOUS LOCAL ACTIN CO-OCCUPANCY EXCLUSION
    // Named noncanonical experimental extension (docs/helical_binding/CONTINUOUS_OCCUPANCY_EXCLUSION_FINDINGS.md).
    // DEFAULT OFF ⇒ the canonical single-head path never invokes these two kernels ⇒ byte-identical. NOT a discrete-
    // site model, NOT a monomer lattice, NOT a helical-site model: a continuous minimum-separation rule on the
    // filament-GLOBAL material coordinate. See §3 of the findings for the exact semantics.
    //
    // Two-stage split (both runners identical): (1) matBindGateOnly = the EXACT matBindExplicit 8-gate contract,
    // but DEFERS commit — it writes the geometric candidate to scratch (candInt/candArc) instead of boundSeg; then
    // (2) matOccupancyResolve = a single-thread SERIAL pass that commits candidates in ascending head-id order,
    // treating each committed head as occupied for later candidates in the SAME step (the PREFERRED deterministic
    // sequential rule ⇒ exactly one of two same-region same-step candidates binds; lowest head-id wins).

    /**
     * GATE-ONLY twin of {@link #matBindExplicit} for the occupancy-exclusion path. IDENTICAL 8-gate arithmetic —
     * <b>KEEP IN SYNC with {@code matBindExplicit}</b> — but instead of committing {@code boundSeg}/{@code bindArc}
     * it writes the geometric candidate to scratch: {@code candInt[m]}=candidate segment (−1 if none),
     * {@code candInt[N+m]}=accept flag (0/1 = passed all 8 gates AND eligible), {@code candArc[m]}=candidate arc
     * ({@code bindArc}, µm). The subsequent {@link #matOccupancyResolve} decides which candidates actually bind.
     * Race-free (each motor writes only its own two candInt slots + candArc slot). {@code candInt} is fully
     * written for every m (no stale carry) so it needs no per-step clear.
     */
    public static void matBindGateOnly(IntArray active, IntArray noBind, IntArray boundSeg, IntArray nuc,
                                       DoubleArray outGeom, DoubleArray q, FloatArray filCoord, FloatArray filUVec,
                                       FloatArray filSegLength, DoubleArray params, DoubleArray bindP, DoubleArray eupP,
                                       IntArray candInt, DoubleArray candArc, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3);
        double dBindNm = bindP.get(0), psiDeg = bindP.get(1), phiDeg = bindP.get(2), thetaDeg = bindP.get(3);
        double preloadPn = bindP.get(4), energyKt = bindP.get(5), FIL_R = bindP.get(6), PHI_PRE = bindP.get(7);
        double aSemiZ = bindP.get(8), kT = bindP.get(9), margin = bindP.get(10); int orientOn = (int) bindP.get(11);
        boolean halfOpen = bindP.get(12) < 0.5; double eps = margin;
        double eupx = eupP.get(0), eupy = eupP.get(1), eupz = eupP.get(2), DEG = 180.0 / Math.PI;
        for (@Parallel int m = 0; m < N; m++) {
            candInt.set(m, -1); candInt.set(N + m, 0);                              // default: no candidate this step
            if (active.get(m) != 1 || noBind.get(m) == 1 || boundSeg.get(m) != -1 || nuc.get(m) != 2) continue;
            double xF8x = outGeom.get(6*N+m), xF8y = outGeom.get(7*N+m), xF8z = outGeom.get(8*N+m);
            double xHx = outGeom.get(3*N+m), xHy = outGeom.get(4*N+m), xHz = outGeom.get(5*N+m);
            int best = -1; double bd = 1e9;
            for (int s = 0; s < nSeg; s++) {
                double half = 0.5 * filSegLength.get(s);
                double cx = filCoord.get(s), cy = filCoord.get(nSeg+s), cz = filCoord.get(2*nSeg+s);
                double ux = filUVec.get(s), uy = filUVec.get(nSeg+s), uz = filUVec.get(2*nSeg+s);
                double dx = xF8x-cx, dy = xF8y-cy, dz = xF8z-cz; double foot = dx*ux+dy*uy+dz*uz;
                double d2;
                if (halfOpen) {
                    double footC = foot < -half ? -half : (foot > half ? half : foot);
                    double qx = dx-footC*ux, qy = dy-footC*uy, qz = dz-footC*uz; d2 = qx*qx+qy*qy+qz*qz;
                } else {
                    if (foot > half+0.02 || foot < -(half+0.02)) continue;
                    double px = dx-foot*ux, py = dy-foot*uy, pz = dz-foot*uz; d2 = px*px+py*py+pz*pz;
                }
                if (d2 < bd) { bd = d2; best = s; }
            }
            if (best < 0) continue;
            int s = best; double half = 0.5 * filSegLength.get(s);
            double cx = filCoord.get(s), cy = filCoord.get(nSeg+s), cz = filCoord.get(2*nSeg+s);
            double ux = filUVec.get(s), uy = filUVec.get(nSeg+s), uz = filUVec.get(2*nSeg+s);
            double e1x = cx-half*ux, e1y = cy-half*uy, e1z = cz-half*uz;
            double dx = xF8x-cx, dy = xF8y-cy, dz = xF8z-cz; double foot = dx*ux+dy*uy+dz*uz;
            double footC = halfOpen ? (foot < -half ? -half : (foot > half ? half : foot)) : foot;
            double axx = cx+footC*ux, axy = cy+footC*uy, axz = cz+footC*uz;
            double conDist = Math.sqrt((xF8x-axx)*(xF8x-axx)+(xF8y-axy)*(xF8y-axy)+(xF8z-axz)*(xF8z-axz));
            double bindArcV = halfOpen ? (footC+half) : ((xF8x-e1x)*ux+(xF8y-e1y)*uy+(xF8z-e1z)*uz);
            double surf = (conDist - FIL_R) * 1e3;
            double phi = q.get(m), psi = q.get(N+m), thetaS = q.get(2*N+m), psiActin = q.get(3*N+m);
            double psiErr = Math.abs(psi-psiActin)*DEG, phiErr = Math.abs(phi-PHI_PRE)*DEG, thetaErr = Math.abs((psi-phi)-thetaS)*DEG;
            double kF8 = params.get(5*N+m), kconv = params.get(6*N+m), kbind = params.get(7*N+m);
            double preload = kF8 * conDist * 1e12;
            double dth = (psi-phi)-thetaS, dpa = psi-psiActin;
            double eKt = (0.5*kconv*dth*dth + 0.5*kbind*dpa*dpa) / kT;
            double headSide = ((xHx-cx)*eupx+(xHy-cy)*eupy+(xHz-cz)*eupz)*1e3;
            boolean g0 = surf < dBindNm;
            boolean g1 = orientOn == 0 || psiErr < psiDeg;
            boolean g2 = orientOn == 0 || phiErr < phiDeg;
            boolean g3 = orientOn == 0 || thetaErr < thetaDeg;
            boolean g4 = preload < preloadPn;
            boolean g5 = orientOn == 0 || eKt < energyKt;
            boolean g6 = headSide < aSemiZ * 1e3;
            boolean g7 = bindArcV > eps && bindArcV < 2*half - eps;
            if (g0 && g1 && g2 && g3 && g4 && g5 && g6 && g7) { candInt.set(m, s); candInt.set(N + m, 1); candArc.set(m, bindArcV); }
        }
    }

    /**
     * SERIAL (single-thread) resolve pass for the continuous local co-occupancy exclusion. Commits the geometric
     * candidates from {@link #matBindGateOnly} in ascending head-id order. A candidate at filament-global material
     * coordinate {@code s_candidate} is REJECTED iff another eligible bound head on the SAME filament occupies
     * {@code s_bound} with {@code |s_candidate − s_bound| < exclusion − tol} (boundary = exactly exclusion is
     * ALLOWED). "Bound heads on the same filament" = every head already committed (from prior steps AND from
     * earlier-id candidates accepted THIS pass) whose segment maps to the same filament id; the candidate excludes
     * itself. Sister heads of a dimer participate (they are just bound heads on the same filament). Deterministic:
     * lowest head-id wins a same-step conflict; no RNG, no lottery.
     *
     * <p>{@code candInt}: candSeg=[m], candAccept=[N+m] (from gate-only). {@code candArc}: candidate arc (µm).
     * {@code boundSeg}/{@code bindArc}: the authoritative bond state (READ existing + WRITE new binds).
     * {@code segCumArc[s]}: filament-global cumulative arc offset at the pointed-end of segment s (µm, static).
     * {@code segFilId[s]}: connected-component filament id of segment s (static). {@code occP}: [exclNm, tolNm].
     * {@code occStats} (zeroed at start): [0]=geometric candidates, [1]=occupancy rejects, [2]=accepted,
     * [3]=same-step conflicts (rejects caused by a lower-id head that bound THIS step). counts=[N,_,_,nSeg].
     */
    public static void matOccupancyResolve(IntArray candInt, DoubleArray candArc, IntArray boundSeg, FloatArray bindArc,
                                           FloatArray segCumArc, IntArray segFilId, DoubleArray occP, IntArray occStats,
                                           IntArray counts) {
        int N = counts.get(0);
        double exclNm = occP.get(0), tolNm = occP.get(1);
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int geomCand = 0, occRej = 0, accepted = 0, conflicts = 0;
            for (int m = 0; m < N; m++) {
                if (candInt.get(N + m) != 1) continue;                             // not a gate-passing candidate
                geomCand++;
                int cs = candInt.get(m);
                double candMat = segCumArc.get(cs) + candArc.get(m);
                int candFil = segFilId.get(cs);
                boolean veto = false, byNewBind = false;
                for (int j = 0; j < N; j++) {
                    if (j == m) continue;                                          // never veto self
                    int bs = boundSeg.get(j);
                    if (bs < 0) continue;                                          // j not currently bound
                    if (segFilId.get(bs) != candFil) continue;                     // different physical filament
                    double jMat = segCumArc.get(bs) + bindArc.get(j);
                    double dNm = Math.abs(candMat - jMat) * 1e3;
                    if (dNm < exclNm - tolNm) { veto = true; byNewBind = candInt.get(N + j) == 1; break; }
                }
                if (veto) { occRej++; if (byNewBind) conflicts++; }
                else { boundSeg.set(m, cs); bindArc.set(m, (float) candArc.get(m)); accepted++; }
            }
            occStats.set(0, geomCand); occStats.set(1, occRej); occStats.set(2, accepted); occStats.set(3, conflicts);
        }
    }

    /**
     * HOST precompute (once per build; topology + per-segment length are static in the gliding assay) of the two
     * static maps the occupancy resolve reads: {@code segCumArc[s]} = filament-global cumulative arc offset at the
     * pointed end of segment s (µm), and {@code segFilId[s]} = connected-component filament id of segment s. The
     * material coordinate of a bond (s, bindArc) is then {@code segCumArc[s] + bindArc}, continuous across segment
     * boundaries and IDENTICAL to {@link ExplicitHmmDimer3jsHarness#filMatCoordUm}'s chain-walk convention (walk
     * from the pointed terminal end1NbrSlot==SENTINEL via end2NbrSlot). Rings/orphans fall back to index order.
     */
    static void computeMaterialMaps(FilamentStore f, int nSeg, FloatArray segCumArc, IntArray segFilId) {
        for (int s = 0; s < nSeg; s++) { segCumArc.set(s, 0f); segFilId.set(s, -1); }
        int fid = 0;
        for (int start = 0; start < nSeg; start++) {
            if (f.end1NbrSlot.get(start) != FilamentStore.SENTINEL_NO_NBR) continue;   // only chain heads (pointed terminals)
            double cum = 0; int cur = start, guard = 0;
            while (cur >= 0 && guard++ <= nSeg) {
                if (segFilId.get(cur) >= 0) break;                                     // already visited (ring guard)
                segCumArc.set(cur, (float) cum); segFilId.set(cur, fid);
                cum += f.segLength.get(cur);
                int nxt = f.end2NbrSlot.get(cur); cur = (nxt == FilamentStore.SENTINEL_NO_NBR) ? -1 : nxt;
            }
            fid++;
        }
        // fallback for any segment not reached from a pointed terminal (ring/degenerate): its own filament, arc 0.
        for (int s = 0; s < nSeg; s++) if (segFilId.get(s) < 0) { segFilId.set(s, fid++); segCumArc.set(s, 0f); }
    }

    // ===============================================================================================
    // HELICAL SURFACE BINDING (noncanonical, flag-gated, default-off) — the explicit-S2 gliding port.
    // Two kernels layered ON TOP of the canonical bind (matBindExplicit is UNCHANGED, so binding DECISIONS +
    // the axial bindArc are byte-identical): (1) matSurfaceAzim selects+retains a material-frame azimuth at the
    // bind transition; (2) matSurfaceStericPrune optionally enforces the 3D actin-surface steric rule.
    // ===============================================================================================
    static final int SURF_NJ = 4;   // fixed ±4-monomer helical scan half-width (PTX-safe, NOT a discrete site index)

    /** Select + retain the material-frame azimuth ψ at the bind transition (FREE→bound). Reference point = xF8
     *  (outGeom[6N..8N], the F8 head-side anchor the bind gate uses). Reuses the continuous helical scan: among the
     *  presented sites n̂(s)=cosφ·segY+sinφ·segZ at arcs footArc+j·monoSp (φ=twistRate·(arc−½segLen)), pick the one
     *  best agreeing with the head's ⊥ direction p̂=(xF8−axis). Keeps the CANONICAL axial bindArc (gliding
     *  translation unchanged); only ADDS the azimuth. justBound[m]=1 on the fresh bind (for the steric prune).
     *  surfP: [0]=Ractin(µm) [1]=twistRate(rad/µm, LEFT-handed) [2]=monoSp(µm). One implementation, both runners. */
    public static void matSurfaceAzim(IntArray boundSeg, IntArray prevBound, IntArray justBound,
            DoubleArray outGeom, FloatArray filCoord, FloatArray filUVec, FloatArray filYVec, FloatArray filSegLength,
            FloatArray bindArc, FloatArray bindAzim, DoubleArray surfP, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3);
        double twistRate = surfP.get(1), monoSp = surfP.get(2);
        for (@Parallel int m = 0; m < N; m++) {
            int bs = boundSeg.get(m), pb = prevBound.get(m);
            int jb = 0;
            if (bs >= 0 && pb < 0) {          // freshly bound this step
                jb = 1; int s = bs;
                double fx = outGeom.get(6*N+m), fy = outGeom.get(7*N+m), fz = outGeom.get(8*N+m);   // xF8
                double cx = filCoord.get(s), cy = filCoord.get(nSeg+s), cz = filCoord.get(2*nSeg+s);
                double ux = filUVec.get(s), uy = filUVec.get(nSeg+s), uz = filUVec.get(2*nSeg+s);
                double yx = filYVec.get(s), yy = filYVec.get(nSeg+s), yz = filYVec.get(2*nSeg+s);
                double zx = uy*yz-uz*yy, zy = uz*yx-ux*yz, zz = ux*yy-uy*yx;
                double zl = zx*zx+zy*zy+zz*zz; if (zl > 1e-30) { double iz = 1.0/Math.sqrt(zl); zx*=iz; zy*=iz; zz*=iz; }
                double half = 0.5*filSegLength.get(s);
                double dxx = fx-cx, dyy = fy-cy, dzz = fz-cz;
                double foot = dxx*ux + dyy*uy + dzz*uz;
                double px = dxx-foot*ux, py = dyy-foot*uy, pz = dzz-foot*uz;
                double pl = Math.sqrt(px*px+py*py+pz*pz); if (pl > 1e-20) { px/=pl; py/=pl; pz/=pl; }
                double footArc = bindArc.get(m);                       // CANONICAL axial arc (from end1, ∈[0,2·half])
                double bestReg = -2.0, selAzim = twistRate*(footArc-half);
                for (int j = -SURF_NJ; j <= SURF_NJ; j++) {
                    double arc = footArc + j*monoSp;
                    boolean inWin = (arc >= 0.0) && (arc <= 2.0*half);
                    double phi = twistRate*(arc-half);
                    double c = Math.cos(phi), sn = Math.sin(phi);
                    double nx = c*yx+sn*zx, ny = c*yy+sn*zy, nz = c*yz+sn*zz;
                    double reg = px*nx + py*ny + pz*nz;
                    if (inWin && reg > bestReg) { bestReg = reg; selAzim = phi; }
                }
                // store UNWRAPPED (consumed only via cos/sin ⇒ periodic; avoids Math.floor which doesn't lower on PTX)
                bindAzim.set(m, (float) selAzim);
            }
            justBound.set(m, jb);
            prevBound.set(m, bs);
        }
    }

    /** 3D actin-surface steric prune (single-thread serial, ascending id, lowest-id wins). A FRESHLY-bound head is
     *  UNBOUND if its reconstructed surface point lies within (exclusion − tol) of another bound head's surface
     *  point on the SAME filament. Established bonds are untouched; a higher-id fresh head never blocks a lower-id
     *  one. stericP: [0]=Ractin(µm) [1]=exclusion(µm; ≤0 ⇒ no steric) [2]=tol(µm). occStats:[cand,rej,acc,conf]. */
    public static void matSurfaceStericPrune(IntArray boundSeg, IntArray justBound, IntArray prevBound, FloatArray bindArc, FloatArray bindAzim,
            FloatArray filCoord, FloatArray filUVec, FloatArray filYVec, FloatArray filSegLength, IntArray segFilId,
            DoubleArray stericP, IntArray occStats, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3);
        double Ractin = stericP.get(0), excl = stericP.get(1), tol = stericP.get(2), thr = excl - tol;
        for (@Parallel int gid = 0; gid < 1; gid++) {
            int cand = 0, rej = 0, conf = 0;
            for (int m = 0; m < N; m++) {
                if (justBound.get(m) != 1) continue;
                int s = boundSeg.get(m); if (s < 0) continue;
                cand++;
                // surface point of m
                double scx = filCoord.get(s), scy = filCoord.get(nSeg+s), scz = filCoord.get(2*nSeg+s);
                double sux = filUVec.get(s), suy = filUVec.get(nSeg+s), suz = filUVec.get(2*nSeg+s);
                double syx = filYVec.get(s), syy = filYVec.get(nSeg+s), syz = filYVec.get(2*nSeg+s);
                double szx = suy*syz-suz*syy, szy = suz*syx-sux*syz, szz = sux*syy-suy*syx;
                double szl = szx*szx+szy*szy+szz*szz; if (szl > 1e-30) { double iz = 1.0/Math.sqrt(szl); szx*=iz; szy*=iz; szz*=iz; }
                double aOff = bindArc.get(m) - 0.5*filSegLength.get(s);
                double az = bindAzim.get(m); double cc = Math.cos(az), ss = Math.sin(az);
                double px = scx + aOff*sux + Ractin*(cc*syx+ss*szx);
                double py = scy + aOff*suy + Ractin*(cc*syy+ss*szy);
                double pz = scz + aOff*suz + Ractin*(cc*syz+ss*szz);
                int candFil = segFilId.get(s);
                boolean reject = false, byFresh = false;
                for (int b = 0; b < N; b++) {
                    if (b == m) continue;
                    int bsg = boundSeg.get(b); if (bsg < 0) continue;
                    if (justBound.get(b) == 1 && b > m) continue;                  // higher-id fresh doesn't block lower-id
                    if (segFilId.get(bsg) != candFil) continue;
                    double bcx = filCoord.get(bsg), bcy = filCoord.get(nSeg+bsg), bcz = filCoord.get(2*nSeg+bsg);
                    double bux = filUVec.get(bsg), buy = filUVec.get(nSeg+bsg), buz = filUVec.get(2*nSeg+bsg);
                    double byx = filYVec.get(bsg), byy = filYVec.get(nSeg+bsg), byz = filYVec.get(2*nSeg+bsg);
                    double bzx = buy*byz-buz*byy, bzy = buz*byx-bux*byz, bzz = bux*byy-buy*byx;
                    double bzl = bzx*bzx+bzy*bzy+bzz*bzz; if (bzl > 1e-30) { double iz = 1.0/Math.sqrt(bzl); bzx*=iz; bzy*=iz; bzz*=iz; }
                    double baOff = bindArc.get(b) - 0.5*filSegLength.get(bsg);
                    double bz2 = bindAzim.get(b); double bc = Math.cos(bz2), bs2 = Math.sin(bz2);
                    double qx = bcx + baOff*bux + Ractin*(bc*byx+bs2*bzx);
                    double qy = bcy + baOff*buy + Ractin*(bc*byy+bs2*bzy);
                    double qz = bcz + baOff*buz + Ractin*(bc*byz+bs2*bzz);
                    double dx = px-qx, dy = py-qy, dz = pz-qz; double sep = Math.sqrt(dx*dx+dy*dy+dz*dz);
                    if (sep < thr) { reject = true; if (justBound.get(b) == 1) byFresh = true; }
                }
                if (reject) { boundSeg.set(m, -1); prevBound.set(m, -1); rej++; if (byFresh) conf++; }   // pruned ⇒ rebinds fresh
            }
            occStats.set(0, cand); occStats.set(1, rej); occStats.set(2, cand - rej); occStats.set(3, conf);
        }
    }

    // ===============================================================================================
    // VILFAN-STYLE STEREOSPECIFIC TARGET-ZONE BINDING (noncanonical, flag-gated, default-off).
    // Layered ON TOP of the canonical bind (matBindExplicit is UNCHANGED ⇒ the 8-gate geometric candidate set +
    // the axial bindArc are byte-identical); this kernel applies a CONTINUOUS angular-compatibility hazard to the
    // candidate BEFORE the attachment is allowed to persist, and retains the registry state Stage B needs.
    // NOT a discrete site lattice, NOT a monomer index, NOT an absolute-azimuth preference.
    //
    // LOCAL ACTIN BINDING FRAME (at the canonical attachment arc `bindArc` on segment s):
    //     uActin = filUVec[s]                                  (pointed→barbed material tangent)
    //     nActin = cos φ · segY + sin φ · segZ ,  segZ = uActin × segY ,  φ = twistRate·(bindArc − ½segLen)
    //     tActin = uActin × nActin                             ⇒ (nActin, tActin, uActin) right-handed
    // nActin is the OUTWARD radial surface normal of the analytic helical presentation actually attached to —
    // the same φ later retained as `bindAzim`. It translates/bends/rotates/ROLLS with the filament material frame
    // (segY is the rolling material reference) and flips sign convention correctly under polarity reversal.
    //
    // MOTOR BINDING FRAME: the head long axis  eBind = normalize(xF8 − xH)  (both from `outGeom`, i.e. from the
    // explicit-S2 beam pose — real simulated orientation state, NOT a lab-frame constant). The direction a
    // compatible actin surface must FACE is  mHat = −eBind  (the outward normal must point back along the head).
    // LIMITATION (reported, not concealed): the explicit-S2 head has NO free rotational DOF about its own long
    // axis (matPlaceHeadExplicit synthesises the head yVec from a lab-fixed perpendicular), so a full 3-DOF
    // stereospecific compatibility is NOT representable. The smallest valid mismatch coordinate is therefore the
    // ONE-angle azimuthal mismatch in the plane ⊥ uActin; head PITCH (the eBind·uActin component) is not part of it.
    //
    // SIGNED ANGULAR MISMATCH (rotationally covariant, material-frame, wrapped to (−π,π]):
    //     mPerp  = mHat − (mHat·uActin)·uActin , normalised
    //     deltaPsi = sign( (nActin × mPerp)·uActin ) · acos( clamp(nActin·mPerp) )
    // deltaPsi = 0 is the PREFERRED (perfectly registered) state. Under a proper rigid rotation of the whole
    // scene deltaPsi is INVARIANT; under filament polarity reversal or a mirror it changes SIGN. It is NEVER an
    // absolute actin azimuth. Computed with the hand-rolled `dacos` (PTX-safe; no Math.atan2).
    //
    // ATTACHMENT HAZARD:  wPsi = exp(−0.5 · alphaPsi · deltaPsi²)   (alphaPsi = Kpsi/kB T, dimensionless)
    // The candidate persists iff u < wPsi with u a DEDICATED counter-based wang-hash variate keyed (seed,t,motor)
    // — a private stream (salt 0x545A4244 "TZBD"), so no existing RNG stream is shifted and CPU↔GPU decisions are
    // bit-identical by construction. alphaPsi = 0 ⇒ wPsi ≡ 1 ⇒ EVERY canonical bind is kept ⇒ binding decisions
    // byte-identical to the canonical gate (no renormalisation, no recruitment preservation — a finite alphaPsi
    // genuinely LOSES binds, and that loss is measured, not hidden).
    // On REJECT the head is returned to the FREE pool (boundSeg=−1) and may retry next step: that finite
    // attachment kinetics + pool depletion is exactly what creates the leading/trailing attachment-flux asymmetry.
    // ===============================================================================================
    static final long TZ_SALT = 0x545A4244L;   // "TZBD" — private target-zone accept stream

    /** Stable arcsin (Taylor seed + 2 Newton steps) — the ChainBendingForceSystem form; PTX has no Math.asin. */
    static double tzAsin(double s) {
        double s2 = s * s;
        double y = s * (1.0 + s2 * (0.16666666666666666 + s2 * (0.075 + s2 * 0.044642857142857)));
        double cy = Math.cos(y); if (cy > 1.0e-12) y = y + (s - Math.sin(y)) / cy;
        cy = Math.cos(y);        if (cy > 1.0e-12) y = y + (s - Math.sin(y)) / cy;
        return y;
    }
    /** Angle between two unit vectors from |cross|² and dot — float32-stable at BOTH ends (verbatim the
     *  validated {@code ChainBendingForceSystem.angleFromSinCos} form). */
    static double tzAngle(double sin2, double cos) {
        double s = Math.sqrt(sin2); double ac = cos < 0.0 ? -cos : cos;
        if (s <= ac) { double base = tzAsin(s); return cos >= 0.0 ? base : (Math.PI - base); }
        return dacos(cos);
    }

    /** PTX-safe uniform in (0,1) from the same 64-bit wang hash the Brownian forcing uses (see brownTorqueD). */
    static double wangU01(long ep, long t, long salt) {
        long h = ((ep * 2654435761L) ^ (t * 40503L) ^ (salt * 0x9E3779B1L)); h ^= (h >>> 13); h *= 0x9E3779B1L; h ^= (h >>> 16);
        return ((h & 0xFFFFFF) + 1) / 16777217.0;
    }

    /**
     * Stage A — graded target-zone attachment hazard + retained registry. Runs immediately after the canonical
     * bind, over every motor. For a FRESHLY bound head (boundSeg≥0 && prevBound&lt;0) it builds the local actin
     * binding frame at the canonical attachment arc, forms the signed mismatch deltaPsi against the motor binding
     * frame, weights the attachment by wPsi, and either KEEPS the bind (retaining bindAzim=φ and bindPsi0=deltaPsi)
     * or RELEASES it. Established bonds are untouched. Race-free: motor m writes only its own slots.
     *
     * <p>{@code tzP}: [0]=twistRate (rad/µm, signed, LEFT-handed) [1]=alphaPsi (dimensionless) [2]=hardGateRad
     * (&gt;0 ⇒ an OPTIONAL binary |deltaPsi| cutoff DIAGNOSTIC instead of the graded hazard) [3]=diagOn (≠0 ⇒
     * write tzDiag).
     * <p>{@code tzDiag} (stride 4 per motor, fully written every step ⇒ no stale carry): [0]=deltaPsi (rad),
     * [1]=wPsi, [2]=1 accepted / 0 rejected, [3]=1 iff this motor was a geometric candidate this step.
     * <p>{@code matc}: [t, seed, brownOn]. {@code counts}: [N,_,_,nSeg].
     */
    public static void matTargetZone(IntArray boundSeg, IntArray prevBound, IntArray justBound,
            DoubleArray outGeom, FloatArray filCoord, FloatArray filUVec, FloatArray filYVec, FloatArray filSegLength,
            FloatArray bindArc, FloatArray bindAzim, FloatArray bindPsi0, DoubleArray tzP, FloatArray tzDiag,
            IntArray matc, IntArray counts) {
        int N = counts.get(0), nSeg = counts.get(3);
        double twistRate = tzP.get(0), alphaPsi = tzP.get(1), hardGate = tzP.get(2); int diagOn = (int) tzP.get(3);
        long tt = matc.get(0), seed = matc.get(1);
        for (@Parallel int m = 0; m < N; m++) {
            int bs = boundSeg.get(m), pb = prevBound.get(m);
            int jb = 0;
            if (diagOn != 0) { tzDiag.set(4*m, 0f); tzDiag.set(4*m+1, 0f); tzDiag.set(4*m+2, 0f); tzDiag.set(4*m+3, 0f); }
            if (bs >= 0 && pb < 0) {                      // freshly bound this step = a geometric candidate
                int s = bs;
                double cx = filCoord.get(s), cy = filCoord.get(nSeg+s), cz = filCoord.get(2*nSeg+s);
                double ux = filUVec.get(s), uy = filUVec.get(nSeg+s), uz = filUVec.get(2*nSeg+s);
                double yx = filYVec.get(s), yy = filYVec.get(nSeg+s), yz = filYVec.get(2*nSeg+s);
                double zx = uy*yz-uz*yy, zy = uz*yx-ux*yz, zz = ux*yy-uy*yx;
                double zl = zx*zx+zy*zy+zz*zz; if (zl > 1e-30) { double iz = 1.0/Math.sqrt(zl); zx*=iz; zy*=iz; zz*=iz; }
                double half = 0.5*filSegLength.get(s);
                // actin-side local binding frame at the ATTACHMENT arc (no axial search: the target zone is swept
                // past the fixed motor by relative sliding — the temporal moving-target-zone picture).
                double phi = twistRate*(bindArc.get(m) - half);
                double cph = Math.cos(phi), sph = Math.sin(phi);
                double nx = cph*yx + sph*zx, ny = cph*yy + sph*zy, nz = cph*yz + sph*zz;
                // motor-side binding direction mHat = −normalize(xF8 − xH)
                double ex = outGeom.get(6*N+m) - outGeom.get(3*N+m);
                double ey = outGeom.get(7*N+m) - outGeom.get(4*N+m);
                double ez = outGeom.get(8*N+m) - outGeom.get(5*N+m);
                double el = Math.sqrt(ex*ex+ey*ey+ez*ez);
                double mx = 0, my = 0, mz = 0;
                if (el > 1e-20) { double ie = -1.0/el; mx = ex*ie; my = ey*ie; mz = ez*ie; }
                double mu = mx*ux + my*uy + mz*uz;                   // head pitch (discarded — see the limitation)
                double px = mx - mu*ux, py = my - mu*uy, pz = mz - mu*uz;
                double pl = Math.sqrt(px*px+py*py+pz*pz);
                double dpsi = 0.0;
                if (pl > 1e-12) {
                    double ip = 1.0/pl; px*=ip; py*=ip; pz*=ip;
                    double cs = nx*px + ny*py + nz*pz; if (cs > 1) cs = 1; if (cs < -1) cs = -1;
                    double crx = ny*pz - nz*py, cry = nz*px - nx*pz, crz = nx*py - ny*px;
                    double sgn = crx*ux + cry*uy + crz*uz;
                    // float32-stable magnitude: asin(|cross|) near 0/π, acos(dot) mid-range — the project's
                    // validated angleFromSinCos form. Plain acos(dot) is ill-conditioned at BOTH ends (the
                    // dacos Newton step divides by sin y → device/host last-bit noise is amplified there).
                    double mag = tzAngle(crx*crx + cry*cry + crz*crz, cs);
                    dpsi = sgn < 0 ? -mag : mag;
                }
                double w;
                if (hardGate > 0.0) { double ad = dpsi < 0 ? -dpsi : dpsi; w = ad < hardGate ? 1.0 : 0.0; }
                else                 w = Math.exp(-0.5*alphaPsi*dpsi*dpsi);
                boolean keep = true;
                if (w < 1.0) keep = wangU01(seed, tt, TZ_SALT + (long) m * 7919L) < w;
                if (keep) { jb = 1; bindAzim.set(m, (float) phi); bindPsi0.set(m, (float) dpsi); }
                else      { boundSeg.set(m, -1); bs = -1; }
                if (diagOn != 0) { tzDiag.set(4*m, (float) dpsi); tzDiag.set(4*m+1, (float) w);
                                   tzDiag.set(4*m+2, keep ? 1f : 0f); tzDiag.set(4*m+3, 1f); }
            }
            justBound.set(m, jb);
            prevBound.set(m, bs);
        }
    }

    // ============================================================ MAT gliding Stage-10: matS2SolveStep
    /** brownTorque — EXACT 64-bit long wang-hash copy (TwoBodyConverterMotor.brownTorque L2174; the same one
     *  that already lowers on PTX inside MatStep7). Returns the FDT force sqrt(2 kT γ/dt)·g (N). */
    static double brownTorqueD(double gamma, double dt, long ep, long t, long salt) {
        long h = ((ep * 2654435761L) ^ (t * 40503L) ^ (salt * 0x9E3779B1L)); h ^= (h >>> 13); h *= 0x9E3779B1L; h ^= (h >>> 16);
        double u1 = ((h & 0xFFFFFF) + 1) / 16777217.0; h ^= (h << 7); double u2 = (((h >>> 8) & 0xFFFFFF) + 1) / 16777217.0;
        double g = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
        return Math.sqrt(2 * Constants.kT * gamma / dt) * g;   // identical to TwoBodyConverterMotor.brownTorque / MatSoaSlice.brownTorqueD
    }

    /**
     * PRODUCTION explicit gliding Stage-10 = the {@link #beamRelaxAnalytic} assembly (the SAME canonical
     * {@link ExplicitBeamAnalytic} residual+Hessian — no second derivative implementation) for ONE implicit
     * Newton step, PLUS the mat Brownian forcing (salts 0x4811/0x4841/0x4842 + m·7919, via the already-lowering
     * {@link #brownTorqueD}) + the forceDotFil/forceMag reaction writeback. Faithful device port of
     * {@code TwoBodyConverterMotor.s2SolveM}. F8h comes from the shared cross-bridge stage: {@code bondData}
     * ({@code m·STRIDE+0..2}) when {@code boundSeg[m]≥0}, else 0.
     *
     * <p>{@code matc}: [t, seed, brownOn, motorBrownPolicy]. {@code motorBrownPolicy} (noncanonical, default 0 ⇒
     * bit-identical) is a binding-state-dependent Brownian mask: bit0 zeroes the Brownian RHS of every BOUND motor,
     * bit1 of every UNBOUND motor. {@code counts}: [nM, maxIt, M, _] (maxIt=1 for a single production step).
     * Layout identical to beamRelaxAnalytic (nodes/frame/q/params/sys/outGeom). STRIDE = 13 (CrossBridgeSystem).
     */
    public static void matS2SolveStep(DoubleArray nodes, DoubleArray frame, DoubleArray q, FloatArray bondData,
                                      IntArray boundSeg, DoubleArray params, DoubleArray sys, DoubleArray outGeom,
                                      FloatArray forceDotFil, FloatArray forceMag, IntArray matc, IntArray counts,
                                      DoubleArray convF) {
        int nM = counts.get(0), maxIt = counts.get(1), M = counts.get(2);
        int STRIDE = 13;
        int nF = 3 * M, n = nF + 2, W = n + 1;
        double tol = 3e-7;
        long tt = matc.get(0), seed = matc.get(1); int brownOn = matc.get(2);
        // NONCANONICAL, default-0 BINDING-STATE-DEPENDENT Brownian mask (the minimal motor Brownian ablation mask;
        // matc[3]: bit0 ⇒ a BOUND motor gets zero Brownian, bit1 ⇒ an UNBOUND motor gets zero Brownian). It gates
        // ONLY the three stochastic RHS terms below (S2 beam-node force, generalized phi torque, generalized psi
        // torque) — every deterministic term, the Hessian, the drag diagonal, the F8 reaction, the solve and the
        // writeback are untouched, so a masked bound motor still relaxes elastically, strokes, bears load and
        // detaches. matc[3] == 0 ⇒ brownM == brownOn ⇒ arithmetic bit-identical to the canonical path.
        int mPolicy = matc.get(3);
        // F8 GENERALIZED-FORCE AXIS (matc[4]): 1 = econv (DEFAULT — the geometry's own rotation axis, i.e. the
        // EXACT Jacobian), 0 = the legacy eup convention retained for byte-reproducing pre-repair runs.
        // The converter geometry is uB = R_econv(phi)*eup and xF8 - C = R_econv(psi)*d0, so the exact columns are
        // d C /d phi = econv x (C - P) and d xF8/d psi = econv x (xF8 - C). Projecting the (purely TRANSLATIONAL)
        // F8 spring onto phi/psi about eup instead put the generalized force along an axis ORTHOGONAL to the true
        // one whenever the converter geometry is planar, so an in-plane bond force produced exactly ZERO phi/psi
        // load. F8's stiffness and rest length are untouched; only the chain-rule projection is corrected.
        // Report: docs/motor/RESTORED_3D_HEAD_TILT_DOF.md, "F8 VIRTUAL-WORK AXIS REPAIR".
        int axMode = matc.get(4);
        for (@Parallel int m = 0; m < nM; m++) {
            double bx = frame.get(m), by = frame.get(nM + m), bz = frame.get(2 * nM + m);
            double ex = frame.get(3 * nM + m), ey = frame.get(4 * nM + m), ez = frame.get(5 * nM + m);
            double ux = frame.get(6 * nM + m), uy = frame.get(7 * nM + m), uz = frame.get(8 * nM + m);
            double gEx = frame.get(9 * nM + m), gEy = frame.get(10 * nM + m), gEz = frame.get(11 * nM + m);
            double gTx = frame.get(12 * nM + m), gTy = frame.get(13 * nM + m), gTz = frame.get(14 * nM + m);
            double lb = params.get(m), rF8x = params.get(nM + m), rF8y = params.get(2 * nM + m);
            double rCx = params.get(3 * nM + m), rCy = params.get(4 * nM + m);
            double kF8Code = params.get(5 * nM + m), kc = params.get(6 * nM + m), kbnd = params.get(7 * nM + m);
            double gPhi = params.get(8 * nM + m), gPsi = params.get(9 * nM + m), dt = params.get(10 * nM + m);
            double ks = params.get(11 * nM + m), l0um = params.get(12 * nM + m), kbend = params.get(13 * nM + m);
            double floorZ = params.get(14 * nM + m), kfloor = params.get(15 * nM + m), gNode = params.get(16 * nM + m);
            double l0m = l0um * 1e-6, aN = gNode / dt, aphi = gPhi / dt, apsi = gPsi / dt;
            int bs = boundSeg.get(m); boolean bnd = bs >= 0; int dB = m * STRIDE;
            // binding-state-dependent Brownian gate — takes effect on the SAME step as the FREE→bound transition
            // (boundSeg already carries this step's bind/target-zone/chemistry decisions when s2solve runs).
            int brownM = brownOn;
            if (bnd) { if ((mPolicy & 1) != 0) brownM = 0; }
            else     { if ((mPolicy & 2) != 0) brownM = 0; }
            double f8x = bnd ? bondData.get(dB) : 0.0, f8y = bnd ? bondData.get(dB + 1) : 0.0, f8z = bnd ? bondData.get(dB + 2) : 0.0;
            double phi = q.get(m), psi = q.get(nM + m), thetaS = q.get(2 * nM + m), psiActin = q.get(3 * nM + m);
            // TRUE CONVERTER-STROKE-PLANE ROTATION (noncanonical, default-off). skewF == 0 ⇒ every branch below
            // takes the VERBATIM canonical path over the base frame ⇒ arithmetically byte-identical. When on,
            // the converter block (geometry AND its Jacobians/generalized forces) is the canonical block
            // conjugated by the site-frame rotation R built in ChiralSiteSystem.convFrameStep; the BEAM's own
            // frame data (floor normal eup, clamp tangent g4Tan, anchor g4E) is NOT rotated.
            double skewF = convF.get(12 * nM + m);
            double cb0 = 0, cb1 = 0, cb2 = 0, ce0 = 0, ce1 = 0, ce2 = 0, cu0 = 0, cu1 = 0, cu2 = 0, of0 = 0, of1 = 0, of2 = 0;
            if (skewF != 0.0) {
                cb0 = convF.get(m);          cb1 = convF.get(nM + m);      cb2 = convF.get(2 * nM + m);
                ce0 = convF.get(3 * nM + m); ce1 = convF.get(4 * nM + m);  ce2 = convF.get(5 * nM + m);
                cu0 = convF.get(6 * nM + m); cu1 = convF.get(7 * nM + m);  cu2 = convF.get(8 * nM + m);
                of0 = convF.get(9 * nM + m); of1 = convF.get(10 * nM + m); of2 = convF.get(11 * nM + m);
            }
            int base = m * (n * W);   // per-item scratch = n*(n+1), M-generic (=210 at M=4 [L40], =420 at M=6 [L60])
            int st = 0, itDone = maxIt;
            for (int it = 0; it < maxIt; it++) {
                for (int i = 0; i < n; i++) for (int j = 0; j < W; j++) sys.set(base + i * W + j, 0.0);
                double Px = nodes.get((3 * M) * nM + m), Py = nodes.get((3 * M + 1) * nM + m), Pz = nodes.get((3 * M + 2) * nM + m);
                double cphi = Math.cos(phi), sphi = Math.sin(phi);
                double cpsi = Math.cos(psi), spsi = Math.sin(psi);
                double Cx, Cy, Cz, xF8x, xF8y, xF8z;
                // the phi ARM (C−P without the gauge offset) and the generalized-force axis, set per branch.
                // aex/aey/aez is the TRUE converter rotation axis regardless of the legacy-axis toggle — the
                // S2->lever joint below always differentiates the real geometry, never the legacy convention.
                double cpx, cpy, cpz, gux, guy, guz, aex, aey, aez;
                if (skewF == 0.0) {
                    double uBx = ux * cphi + bx * sphi, uBy = uy * cphi + by * sphi, uBz = uz * cphi + bz * sphi;
                    Cx = Px + uBx * lb; Cy = Py + uBy * lb; Cz = Pz + uBz * lb;
                    double d0x = bx * (rF8x - rCx) + ux * (rF8y - rCy), d0y = by * (rF8x - rCx) + uy * (rF8y - rCy), d0z = bz * (rF8x - rCx) + uz * (rF8y - rCy);
                    xF8x = Cx + (d0x * cpsi + (ey * d0z - ez * d0y) * spsi);
                    xF8y = Cy + (d0y * cpsi + (ez * d0x - ex * d0z) * spsi);
                    xF8z = Cz + (d0z * cpsi + (ex * d0y - ey * d0x) * spsi);
                    cpx = Cx - Px; cpy = Cy - Py; cpz = Cz - Pz;
                    if (axMode != 0) { gux = ex; guy = ey; guz = ez; }   // EXACT: the geometry rotates about econv
                    else             { gux = ux; guy = uy; guz = uz; }   // LEGACY eup convention
                    aex = ex; aey = ey; aez = ez;
                } else {
                    double uBx = cu0 * cphi + cb0 * sphi, uBy = cu1 * cphi + cb1 * sphi, uBz = cu2 * cphi + cb2 * sphi;
                    Cx = Px + of0 + uBx * lb; Cy = Py + of1 + uBy * lb; Cz = Pz + of2 + uBz * lb;
                    double d0x = cb0 * (rF8x - rCx) + cu0 * (rF8y - rCy), d0y = cb1 * (rF8x - rCx) + cu1 * (rF8y - rCy), d0z = cb2 * (rF8x - rCx) + cu2 * (rF8y - rCy);
                    xF8x = Cx + (d0x * cpsi + (ce1 * d0z - ce2 * d0y) * spsi);
                    xF8y = Cy + (d0y * cpsi + (ce2 * d0x - ce0 * d0z) * spsi);
                    xF8z = Cz + (d0z * cpsi + (ce0 * d0y - ce1 * d0x) * spsi);
                    // the gauge offset is a constant rest-geometry translation ⇒ it must NOT enter ∂xF8/∂phi
                    cpx = uBx * lb; cpy = uBy * lb; cpz = uBz * lb;
                    // the generalized-force axis rotates WITH the geometry (the site-frame-conjugated converter axis)
                    if (axMode != 0) { gux = ce0; guy = ce1; guz = ce2; }   // EXACT
                    else             { gux = cu0; guy = cu1; guz = cu2; }   // LEGACY
                    aex = ce0; aey = ce1; aez = ce2;
                }
                // STRETCH
                for (int i = 0; i < M; i++) {
                    double ax = nodes.get((3*(i+1))*nM+m) - nodes.get((3*i)*nM+m);
                    double ay = nodes.get((3*(i+1)+1)*nM+m) - nodes.get((3*i+1)*nM+m);
                    double az = nodes.get((3*(i+1)+2)*nM+m) - nodes.get((3*i+2)*nM+m);
                    double len = Math.sqrt(ax*ax+ay*ay+az*az); if (len < 1e-15) continue;
                    double s = 1.0/len, uxx = ax*s, uyy = ay*s, uzz = az*s;
                    double lenM = len*1e-6, tS = ks*(lenM - l0m), tL = tS/lenM;
                    int ri = (i>=1)?(i-1)*3:-1000, rj = i*3;
                    addF(sys,base,W,n, ri+0, tS*uxx); addF(sys,base,W,n, ri+1, tS*uyy); addF(sys,base,W,n, ri+2, tS*uzz);
                    addF(sys,base,W,n, rj+0,-tS*uxx); addF(sys,base,W,n, rj+1,-tS*uyy); addF(sys,base,W,n, rj+2,-tS*uzz);
                    for (int p=0;p<3;p++) for(int qq=0;qq<3;qq++){
                        double up = (p==0)?uxx:((p==1)?uyy:uzz), uq=(qq==0)?uxx:((qq==1)?uyy:uzz);
                        double id=(p==qq)?1.0:0.0; double kb2 = ks*up*uq + tL*(id-up*uq);
                        addK(sys,base,W, ri+p, ri+qq, kb2); addK(sys,base,W, rj+p, rj+qq, kb2);
                        addK(sys,base,W, ri+p, rj+qq,-kb2); addK(sys,base,W, rj+p, ri+qq,-kb2);
                    }
                }
                // BEND clamped joint 0
                {
                    double b0x = nodes.get(3*nM+m)-nodes.get(m), b0y = nodes.get((4)*nM+m)-nodes.get(nM+m), b0z = nodes.get(5*nM+m)-nodes.get(2*nM+m);
                    double lbb = Math.sqrt(b0x*b0x+b0y*b0y+b0z*b0z);
                    if (lbb > 1e-12) {
                        double ilb=1.0/lbb, c = gTx*b0x+gTy*b0y+gTz*b0z; c*=ilb; if(c>1)c=1; if(c<-1)c=-1;
                        double th = dacos(c), A1=a1(th), A2=a2(th); double clb2=c*ilb*ilb;
                        double g0x=gTx*ilb-clb2*b0x, g0y=gTy*ilb-clb2*b0y, g0z=gTz*ilb-clb2*b0z;
                        int r1=0;
                        addF(sys,base,W,n, r1+0, kbend*A1*1e6*g0x); addF(sys,base,W,n, r1+1, kbend*A1*1e6*g0y); addF(sys,base,W,n, r1+2, kbend*A1*1e6*g0z);
                        double ilb2=ilb*ilb, ilb3=ilb2*ilb, ilb4=ilb2*ilb2;
                        for (int p=0;p<3;p++) for(int qq=0;qq<3;qq++){
                            double b0p=(p==0)?b0x:((p==1)?b0y:b0z), b0q=(qq==0)?b0x:((qq==1)?b0y:b0z);
                            double tp=(p==0)?gTx:((p==1)?gTy:gTz), tq=(qq==0)?gTx:((qq==1)?gTy:gTz);
                            double id=(p==qq)?1.0:0.0;
                            double Hc = -(b0p*tq+tp*b0q)*ilb3 - c*id*ilb2 + 3.0*c*b0p*b0q*ilb4;
                            double gp=(p==0)?g0x:((p==1)?g0y:g0z), gq=(qq==0)?g0x:((qq==1)?g0y:g0z);
                            addK(sys,base,W, r1+p, r1+qq, 1e12*kbend*(A2*gp*gq - A1*Hc));
                        }
                    }
                }
                // BEND interior joints
                for (int j=1;j<M;j++){
                    double ax = nodes.get((3*j)*nM+m)-nodes.get((3*(j-1))*nM+m);
                    double ay = nodes.get((3*j+1)*nM+m)-nodes.get((3*(j-1)+1)*nM+m);
                    double az = nodes.get((3*j+2)*nM+m)-nodes.get((3*(j-1)+2)*nM+m);
                    double bxx = nodes.get((3*(j+1))*nM+m)-nodes.get((3*j)*nM+m);
                    double byy = nodes.get((3*(j+1)+1)*nM+m)-nodes.get((3*j+1)*nM+m);
                    double bzz = nodes.get((3*(j+1)+2)*nM+m)-nodes.get((3*j+2)*nM+m);
                    double la = Math.sqrt(ax*ax+ay*ay+az*az), lbn = Math.sqrt(bxx*bxx+byy*byy+bzz*bzz);
                    if (la<1e-12 || lbn<1e-12) continue;
                    double ila=1.0/la, ilb=1.0/lbn, iab=ila*ilb;
                    double c = (ax*bxx+ay*byy+az*bzz)*iab; if(c>1)c=1; if(c<-1)c=-1;
                    double th=dacos(c), A1=a1(th), A2=a2(th);
                    double cla2=c*ila*ila, clb2=c*ilb*ilb;
                    double Dx=bxx*iab-cla2*ax, Dy=byy*iab-cla2*ay, Dz=bzz*iab-cla2*az;
                    double Ex=ax*iab-clb2*bxx, Ey=ay*iab-clb2*byy, Ez=az*iab-clb2*bzz;
                    double gc0x=-Dx,gc0y=-Dy,gc0z=-Dz, gc1x=Dx-Ex,gc1y=Dy-Ey,gc1z=Dz-Ez, gc2x=Ex,gc2y=Ey,gc2z=Ez;
                    int rJm1=(j-1>=1)?(j-2)*3:-1000, rJ=(j-1)*3, rJp1=j*3;
                    addF(sys,base,W,n, rJm1+0,kbend*A1*1e6*gc0x); addF(sys,base,W,n, rJm1+1,kbend*A1*1e6*gc0y); addF(sys,base,W,n, rJm1+2,kbend*A1*1e6*gc0z);
                    addF(sys,base,W,n, rJ+0,  kbend*A1*1e6*gc1x); addF(sys,base,W,n, rJ+1,  kbend*A1*1e6*gc1y); addF(sys,base,W,n, rJ+2,  kbend*A1*1e6*gc1z);
                    addF(sys,base,W,n, rJp1+0,kbend*A1*1e6*gc2x); addF(sys,base,W,n, rJp1+1,kbend*A1*1e6*gc2y); addF(sys,base,W,n, rJp1+2,kbend*A1*1e6*gc2z);
                    double ila2=ila*ila, ila3=ila2*ila, ila4=ila2*ila2, ilb2=ilb*ilb, ilb3=ilb2*ilb, ilb4=ilb2*ilb2;
                    for (int ai=0; ai<3; ai++){
                        int rA = (ai==0)?rJm1:((ai==1)?rJ:rJp1);
                        double saA=(ai==0)?-1.0:((ai==1)?1.0:0.0), sbA=(ai==0)?0.0:((ai==1)?-1.0:1.0);
                        for (int bi=0; bi<3; bi++){
                            int rB = (bi==0)?rJm1:((bi==1)?rJ:rJp1);
                            double saB=(bi==0)?-1.0:((bi==1)?1.0:0.0), sbB=(bi==0)?0.0:((bi==1)?-1.0:1.0);
                            for (int p=0;p<3;p++){
                                double ap=(p==0)?ax:((p==1)?ay:az), bp=(p==0)?bxx:((p==1)?byy:bzz);
                                double gAp=(p==0)?((ai==0)?gc0x:((ai==1)?gc1x:gc2x)):((p==1)?((ai==0)?gc0y:((ai==1)?gc1y:gc2y)):((ai==0)?gc0z:((ai==1)?gc1z:gc2z)));
                                for (int qq=0;qq<3;qq++){
                                    double aq=(qq==0)?ax:((qq==1)?ay:az), bq=(qq==0)?bxx:((qq==1)?byy:bzz);
                                    double id=(p==qq)?1.0:0.0;
                                    double Haa=-(ap*bq+bp*aq)*ila3*ilb - c*id*ila2 + 3.0*c*ap*aq*ila4;
                                    double Hbb=-(bp*aq+ap*bq)*ilb3*ila - c*id*ilb2 + 3.0*c*bp*bq*ilb4;
                                    double Hab_pq= id*iab - bp*bq*ila*ilb3 - ap*aq*ila3*ilb + c*ap*bq*ila2*ilb2;
                                    double Hab_qp= id*iab - bq*bp*ila*ilb3 - aq*ap*ila3*ilb + c*aq*bp*ila2*ilb2;
                                    double Hc = saA*saB*Haa + sbA*sbB*Hbb + saA*sbB*Hab_pq + sbA*saB*Hab_qp;
                                    double gBq=(qq==0)?((bi==0)?gc0x:((bi==1)?gc1x:gc2x)):((qq==1)?((bi==0)?gc0y:((bi==1)?gc1y:gc2y)):((bi==0)?gc0z:((bi==1)?gc1z:gc2z)));
                                    addK(sys,base,W, rA+p, rB+qq, 1e12*kbend*(A2*gAp*gBq - A1*Hc));
                                }
                            }
                        }
                    }
                }
                // FLOOR
                for (int j=1;j<=M;j++){
                    double z = nodes.get((3*j)*nM+m)*ux + nodes.get((3*j+1)*nM+m)*uy + nodes.get((3*j+2)*nM+m)*uz;
                    if (z < floorZ){ double fk = kfloor*(floorZ - z)*1e-6; int r=(j-1)*3;
                        addF(sys,base,W,n, r+0, fk*ux); addF(sys,base,W,n, r+1, fk*uy); addF(sys,base,W,n, r+2, fk*uz);
                        for(int p=0;p<3;p++){ double ep=(p==0)?ux:((p==1)?uy:uz);
                            for(int qq=0;qq<3;qq++){ double eq=(qq==0)?ux:((qq==1)?uy:uz); addK(sys,base,W, r+p, r+qq, kfloor*ep*eq); } }
                    }
                }
                // node drag diagonal + node Brownian RHS
                for (int r=0;r<nF;r++) addK(sys,base,W, r, r, aN);
                if (brownM != 0) for (int j=1;j<=M;j++){ int fb=(j-1)*3;
                    for (int k=0;k<3;k++){ long salt = 0x4811L + ((long)m*1009 + (long)j*131 + k)*7919L;
                        addF(sys,base,W,n, fb+k, brownTorqueD(gNode, dt, seed, tt, salt)); } }
                // F8 / converter / bind block  (axis = gu*, the generalized-force axis of the branch above)
                int pB = 3*(M-1), iPhi = nF, iPsi = nF+1;
                double fcx=xF8x-Cx, fcy=xF8y-Cy, fcz=xF8z-Cz;
                double Jphix=guy*cpz-guz*cpy, Jphiy=guz*cpx-gux*cpz, Jphiz=gux*cpy-guy*cpx;
                double Jpsix=guy*fcz-guz*fcy, Jpsiy=guz*fcx-gux*fcz, Jpsiz=gux*fcy-guy*fcx;
                double J03=Jphix*1e-6,J04=Jpsix*1e-6,J13=Jphiy*1e-6,J14=Jpsiy*1e-6,J23=Jphiz*1e-6,J24=Jpsiz*1e-6;
                double kfSI=kF8Code*1e6;
                for (int i=0;i<5;i++){
                    double Ji0=(i==0)?1:0, Ji1=(i==1)?1:0, Ji2=(i==2)?1:0;
                    if(i==3){Ji0=J03;Ji1=J13;Ji2=J23;} if(i==4){Ji0=J04;Ji1=J14;Ji2=J24;}
                    int di=(i<3)?(pB+i):(i==3?iPhi:iPsi);
                    for (int jj=0;jj<5;jj++){
                        double Jj0=(jj==0)?1:0, Jj1=(jj==1)?1:0, Jj2=(jj==2)?1:0;
                        if(jj==3){Jj0=J03;Jj1=J13;Jj2=J23;} if(jj==4){Jj0=J04;Jj1=J14;Jj2=J24;}
                        int dj=(jj<3)?(pB+jj):(jj==3?iPhi:iPsi);
                        addK(sys,base,W, di, dj, kfSI*(Ji0*Jj0+Ji1*Jj1+Ji2*Jj2));
                    }
                }
                addK(sys,base,W, iPhi,iPhi, kc); addK(sys,base,W, iPhi,iPsi,-kc); addK(sys,base,W, iPsi,iPhi,-kc); addK(sys,base,W, iPsi,iPsi, kc+kbnd);
                addK(sys,base,W, iPhi,iPhi, aphi); addK(sys,base,W, iPsi,iPsi, apsi);
                double th2 = psi - phi;
                double caFx=cpy*f8z-cpz*f8y, caFy=cpz*f8x-cpx*f8z, caFz=cpx*f8y-cpy*f8x;
                double fcFx=fcy*f8z-fcz*f8y, fcFy=fcz*f8x-fcx*f8z, fcFz=fcx*f8y-fcy*f8x;
                double QphiF8=(gux*caFx+guy*caFy+guz*caFz)*1e-6, QpsiF8=(gux*fcFx+guy*fcFy+guz*fcFz)*1e-6;
                addF(sys,base,W,n, pB+0, f8x); addF(sys,base,W,n, pB+1, f8y); addF(sys,base,W,n, pB+2, f8z);
                addF(sys,base,W,n, iPhi, QphiF8 + kc*(th2-thetaS));
                addF(sys,base,W,n, iPsi, QpsiF8 - kc*(th2-thetaS) - kbnd*(psi-psiActin));
                if (brownM != 0) { addF(sys,base,W,n, iPhi, brownTorqueD(gPhi, dt, seed, tt, 0x4841L + (long)m*7919L));
                                    addF(sys,base,W,n, iPsi, brownTorqueD(gPsi, dt, seed, tt, 0x4842L + (long)m*7919L)); }
                // ================= S2 -> LEVER TERMINAL BEND JOINT (moment continuity at the distal node) ======
                // The lever P->C is the TERMINAL ORIENTATION of the S2 mechanical chain, so the joint at the
                // distal beam node carries a bending moment exactly like every interior joint of the beam. It
                // reuses the beam's OWN kbend (= EI/l0, AMK 2008) — NO new stiffness is introduced — and an
                // unstrained angle theta0 read off the as-built geometry (params[17N+m]), which is the same kind
                // of build-time geometric constant as the CLAMPED joint 0's rest tangent g4Tan. theta0 < 0
                // disables the joint (the legacy zero-moment pin). Without it nothing at all constrains the lever
                // angle phi: k_conv ties psi to phi and the head potential is neck-relative, both PURELY
                // RELATIVE, so (phi,psi) -> (phi+d, psi+d) was an exact zero-energy free rotation.
                //   E = 1/2 kbend (theta - theta0)^2 ,  cos theta = sHat . uB
                //   sHat = (node M - node M-1)/|.|      uB = (C - P)/lb      d uB/d phi = econv x uB
                // Both arms are LIVE geometry, so the joint is frame-covariant by construction and contains no
                // lab reference. Residual AND exact Hessian (nodes M-1, M and phi) are assembled, so it is
                // treated implicitly like the rest of the beam.
                double thL0 = params.get(17 * nM + m);
                if (thL0 >= 0.0 && M >= 2) {
                    double ilb = 1.0 / lb;
                    double uBx = cpx * ilb, uBy = cpy * ilb, uBz = cpz * ilb;
                    double aLx = nodes.get((3*M)*nM+m)     - nodes.get((3*(M-1))*nM+m);
                    double aLy = nodes.get((3*M+1)*nM+m)   - nodes.get((3*(M-1)+1)*nM+m);
                    double aLz = nodes.get((3*M+2)*nM+m)   - nodes.get((3*(M-1)+2)*nM+m);
                    double La = Math.sqrt(aLx*aLx + aLy*aLy + aLz*aLz);
                    if (La > 1e-12) {
                        double iL = 1.0/La, sxL = aLx*iL, syL = aLy*iL, szL = aLz*iL;
                        double cL = sxL*uBx + syL*uBy + szL*uBz; if (cL > 1) cL = 1; if (cL < -1) cL = -1;
                        double thJ = dacos(cL), snJ = Math.sin(thJ);
                        if (snJ > 1e-6) {
                            double dth = thJ - thL0;
                            double A1L = dth/snJ, A2L = (1.0 - dth*cL/snJ)/(snJ*snJ);
                            double gxL = (uBx - cL*sxL)*iL, gyL = (uBy - cL*syL)*iL, gzL = (uBz - cL*szL)*iL;
                            double wxL = aey*uBz - aez*uBy, wyL = aez*uBx - aex*uBz, wzL = aex*uBy - aey*uBx;
                            double cphL = sxL*wxL + syL*wyL + szL*wzL;          // dc/d phi
                            int rM = (M-1)*3, rMm = (M-2)*3;
                            addF(sys,base,W,n, rM+0,  kbend*A1L*1e6*gxL); addF(sys,base,W,n, rM+1,  kbend*A1L*1e6*gyL); addF(sys,base,W,n, rM+2,  kbend*A1L*1e6*gzL);
                            addF(sys,base,W,n, rMm+0,-kbend*A1L*1e6*gxL); addF(sys,base,W,n, rMm+1,-kbend*A1L*1e6*gyL); addF(sys,base,W,n, rMm+2,-kbend*A1L*1e6*gzL);
                            addF(sys,base,W,n, iPhi,  kbend*A1L*cphL);
                            double iL2 = iL*iL;
                            for (int p=0;p<3;p++){
                                double upL=(p==0)?uBx:((p==1)?uBy:uBz), spL=(p==0)?sxL:((p==1)?syL:szL);
                                double gpL=(p==0)?gxL:((p==1)?gyL:gzL), wpL=(p==0)?wxL:((p==1)?wyL:wzL);
                                for (int qq=0;qq<3;qq++){
                                    double uqL=(qq==0)?uBx:((qq==1)?uBy:uBz), sqL=(qq==0)?sxL:((qq==1)?syL:szL);
                                    double gqL=(qq==0)?gxL:((qq==1)?gyL:gzL);
                                    double idL=(p==qq)?1.0:0.0;
                                    double HcL = (-(upL*sqL + spL*uqL) + 3.0*cL*spL*sqL - cL*idL)*iL2;
                                    double kv = 1e12*kbend*(A2L*gpL*gqL - A1L*HcL);
                                    addK(sys,base,W, rM+p,  rM+qq,  kv);  addK(sys,base,W, rMm+p, rMm+qq, kv);
                                    addK(sys,base,W, rM+p,  rMm+qq,-kv);  addK(sys,base,W, rMm+p, rM+qq, -kv);
                                }
                                double HmL = (wpL - cphL*spL)*iL;
                                double kmix = 1e6*kbend*(A2L*gpL*cphL - A1L*HmL);
                                addK(sys,base,W, rM+p,  iPhi,  kmix); addK(sys,base,W, iPhi, rM+p,   kmix);
                                addK(sys,base,W, rMm+p, iPhi, -kmix); addK(sys,base,W, iPhi, rMm+p, -kmix);
                            }
                            addK(sys,base,W, iPhi, iPhi, kbend*(A2L*cphL*cphL + A1L*cL));
                        }
                    }
                }
                // solve (Gauss–Jordan)
                for (int c=0;c<n;c++){
                    int p=c; double bestv=sys.get(base+c*W+c); if(bestv<0)bestv=-bestv;
                    for(int r=c+1;r<n;r++){ double v=sys.get(base+r*W+c); if(v<0)v=-v; if(v>bestv){bestv=v;p=r;} }
                    if(p!=c){ for(int k=0;k<W;k++){ double tmp=sys.get(base+c*W+k); sys.set(base+c*W+k, sys.get(base+p*W+k)); sys.set(base+p*W+k, tmp); } }
                    double piv=sys.get(base+c*W+c); double apiv=piv<0?-piv:piv;
                    if(!(apiv>1e-300)) st=1;
                    for(int r=0;r<n;r++){ if(r==c) continue; double fac=sys.get(base+r*W+c)/piv;
                        for(int k=c;k<W;k++) sys.set(base+r*W+k, sys.get(base+r*W+k)-fac*sys.get(base+c*W+k)); }
                }
                double mv=0;
                for (int j=1;j<=M;j++){ int fb=(j-1)*3;
                    for(int k=0;k<3;k++){ double dqm=sys.get(base+(fb+k)*W+n)/sys.get(base+(fb+k)*W+(fb+k));
                        double dnode=dqm*1e6; nodes.set((3*j+k)*nM+m, nodes.get((3*j+k)*nM+m)+dnode);
                        double amv=dnode<0?-dnode:dnode; if(amv>mv) mv=amv; } }
                double dphi=sys.get(base+iPhi*W+n)/sys.get(base+iPhi*W+iPhi);
                double dpsi=sys.get(base+iPsi*W+n)/sys.get(base+iPsi*W+iPsi);
                phi+=dphi; psi+=dpsi;
                nodes.set(m, gEx); nodes.set(nM+m, gEy); nodes.set(2*nM+m, gEz);
                if (mv < tol){ itDone = it+1; break; }
            }
            // final geomC → outGeom + reaction writeback
            double Px2=nodes.get((3*M)*nM+m), Py2=nodes.get((3*M+1)*nM+m), Pz2=nodes.get((3*M+2)*nM+m);
            double cphi=Math.cos(phi), sphi=Math.sin(phi);
            double cpsi=Math.cos(psi), spsi=Math.sin(psi);
            double Cx, Cy, Cz, xF8x, xF8y, xF8z, xHx, xHy, xHz;
            if (skewF == 0.0) {   // CANONICAL branch — VERBATIM, byte-identical
                double uBx=ux*cphi+bx*sphi, uBy=uy*cphi+by*sphi, uBz=uz*cphi+bz*sphi;
                Cx=Px2+uBx*lb; Cy=Py2+uBy*lb; Cz=Pz2+uBz*lb;
                double d0x=bx*(rF8x-rCx)+ux*(rF8y-rCy), d0y=by*(rF8x-rCx)+uy*(rF8y-rCy), d0z=bz*(rF8x-rCx)+uz*(rF8y-rCy);
                xF8x=Cx+(d0x*cpsi+(ey*d0z-ez*d0y)*spsi); xF8y=Cy+(d0y*cpsi+(ez*d0x-ex*d0z)*spsi); xF8z=Cz+(d0z*cpsi+(ex*d0y-ey*d0x)*spsi);
                double rcx=bx*rCx+ux*rCy, rcy=by*rCx+uy*rCy, rcz=bz*rCx+uz*rCy;
                xHx=Cx-(rcx*cpsi+(ey*rcz-ez*rcy)*spsi); xHy=Cy-(rcy*cpsi+(ez*rcx-ex*rcz)*spsi); xHz=Cz-(rcz*cpsi+(ex*rcy-ey*rcx)*spsi);
            } else {              // ROTATED CONVERTER FRAME
                double uBx=cu0*cphi+cb0*sphi, uBy=cu1*cphi+cb1*sphi, uBz=cu2*cphi+cb2*sphi;
                Cx=Px2+of0+uBx*lb; Cy=Py2+of1+uBy*lb; Cz=Pz2+of2+uBz*lb;
                double d0x=cb0*(rF8x-rCx)+cu0*(rF8y-rCy), d0y=cb1*(rF8x-rCx)+cu1*(rF8y-rCy), d0z=cb2*(rF8x-rCx)+cu2*(rF8y-rCy);
                xF8x=Cx+(d0x*cpsi+(ce1*d0z-ce2*d0y)*spsi); xF8y=Cy+(d0y*cpsi+(ce2*d0x-ce0*d0z)*spsi); xF8z=Cz+(d0z*cpsi+(ce0*d0y-ce1*d0x)*spsi);
                double rcx=cb0*rCx+cu0*rCy, rcy=cb1*rCx+cu1*rCy, rcz=cb2*rCx+cu2*rCy;
                xHx=Cx-(rcx*cpsi+(ce1*rcz-ce2*rcy)*spsi); xHy=Cy-(rcy*cpsi+(ce2*rcx-ce0*rcz)*spsi); xHz=Cz-(rcz*cpsi+(ce0*rcy-ce1*rcx)*spsi);
            }
            outGeom.set(m,Cx); outGeom.set(nM+m,Cy); outGeom.set(2*nM+m,Cz);
            outGeom.set(3*nM+m,xHx); outGeom.set(4*nM+m,xHy); outGeom.set(5*nM+m,xHz);
            outGeom.set(6*nM+m,xF8x); outGeom.set(7*nM+m,xF8y); outGeom.set(8*nM+m,xF8z);
            q.set(m,phi); q.set(nM+m,psi);
            forceDotFil.set(m, bnd ? bondData.get(dB + 12) : 0.0f);
            forceMag.set(m, bnd ? (float) Math.sqrt(f8x*f8x + f8y*f8y + f8z*f8z) : 0.0f);
        }
    }

    // ===============================================================================================
    // DYNAMIC 3-D HEAD ORIENTATION (noncanonical, DEFAULT-OFF). Report: docs/motor/RESTORED_3D_HEAD_TILT_DOF.md
    //
    // matS2SolveStepTilt = matS2SolveStep with ONE extra generalized coordinate, the neck-head tilt chi,
    // solved in the SAME implicit block (n = 3M+2 -> 3M+3). The ORIGINAL kernel is untouched and remains
    // the wired default; this one is only built when the feature is on.
    //
    //   HEAD KINEMATICS.  The head pose is the rotation Q(psi,chi) = R(econv, psi) . R(t0, chi) applied to the
    //   fixed head-frame offsets, with t0 = p1hat x econv. (Rotating about the psi-DEPENDENT axis
    //   that = e0(psi) x econv AFTER the psi rotation is identical to rotating about the FIXED t0 BEFORE it —
    //   conjugation — which is why the two-angle parameterisation is exact and gimbal-free away from
    //   chi = +-pi/2.)  Consequences used below, all exact for every chi:
    //       eBind      = cos(chi)*e0(psi) + sin(chi)*econv        e0(psi) = R(econv,psi).p1hat
    //       omega_psi  = econv        (unit)          d xF8/d psi = econv x (xF8 - C)
    //       omega_chi  = that(psi)    (unit)          d xF8/d chi = that  x (xF8 - C)
    //   omega_psi . omega_chi = 0 EXACTLY, everywhere.
    //
    //   MOBILITY (derived, not fitted).  The head is a sphere of rotational drag gamma_r = 8*pi*eta*R^3 and
    //   translational drag gamma_t = 6*pi*eta*R; its centre offset from the pivot is rho = xH - C, and because
    //   r_conv = -r_F8 exactly in this motor, rho = |r_conv| * eBind — PARALLEL to the head axis. Hence
    //       Gamma_ij = gamma_r (w_i.w_j) + gamma_t (w_i x rho).(w_j x rho)
    //       Gamma_chichi = gamma_r + gamma_t|rho|^2                    = the existing gamma_psi, EXACTLY
    //       Gamma_psipsi = gamma_r + gamma_t|rho|^2 cos^2(chi)         = gamma_r + (gamma_psi - gamma_r) cos^2 chi
    //       Gamma_psichi = 0                                            EXACTLY, everywhere
    //   so the 2x2 metric IS diagonal but Gamma_psipsi is configuration-dependent. Because Gamma_psipsi depends
    //   on chi and NOT on psi (and Gamma_chichi is constant), the mobility divergence kT*grad.M vanishes
    //   identically, so the plain Ito update with FDT amplitudes sqrt(2 kT Gamma_ii/dt) is Boltzmann-consistent
    //   with no spurious-drift correction. At chi = 0, Gamma_psipsi = gamma_psi ⇒ byte-identical forcing.
    //
    //   HEAD ORIENTATION POTENTIAL.  ONE functional form for both binding states:
    //       U = 1/2 * k * theta^2 ,   theta = angle(eBind, eTarget)
    //       DETACHED : k = k_det   , eTarget = c1*n1 + c2*n2 + c3*n3   — the LIVE NECK/CONVERTER FRAME (below)
    //       BOUND    : k = k_bind  , eTarget = e0(psiActin)            — the HISTORICAL base-frame target,
    //                  i.e. the minimal 3-D extension of today's 1/2 k_bind (psi - psiActin)^2, to which it
    //                  reduces EXACTLY at chi = 0. No retarget to the site normal (that is the next task).
    //   Both generalized head torques come from the SAME U (no independent hand-chosen springs):
    //       lambda   = k * theta / sin(theta)            (-> k as theta -> 0)
    //       Q_psi    = lambda * (d eBind/d psi . eTarget) = lambda * cos(chi) * ((econv x e0) . eTarget)
    //       Q_chi    = lambda * (d eBind/d chi . eTarget) = lambda * ((cos(chi) econv - sin(chi) e0) . eTarget)
    //   and, DETACHED only, the equal-and-opposite reaction on the frame carriers (phi and the distal beam
    //   element), also from the same U — so the detached potential is CONSERVATIVE, not a one-sided spring:
    //       Q_phi    = lambda * (eBind . d eTarget/d phi)
    //       F_(node M) = -F_(node M-1) = lambda * beta * n3 / |s| ,  beta = eBind.(c2 n3 - c3 n2)/|g|
    //   (the bound target is base-frame, so it has no carriers and no reaction, exactly as today).
    //   Jacobian: the exact small-angle Hessian in these coordinates, k cos^2(chi) on (psi,psi) and k on
    //   (chi,chi), with NO cross term (d eBind/d psi . d eBind/d chi = 0). At chi = 0 this is the legacy
    //   `kbnd` entry. The weak detached reaction terms are carried in the residual only (explicit): they are
    //   ~2 orders below the beam/drag diagonal, and the residual — not the Jacobian — fixes the physics.
    //
    //   LIVE NECK/CONVERTER FRAME (the point of the whole exercise). Built from LIVE mechanical geometry only:
    //       n1 = uB = (C - P)/lb                     the lever/neck axis entering the head at the pivot C
    //       shat = (node M - node M-1)/|.|           the distal S2 tangent — the only live roll reference
    //       n2 = normalize(shat - (shat.n1) n1)      roll about the neck, set by the S2-lever plane
    //       n3 = n1 x n2
    //   No lab axis and no stored base triad enters. It rotates with a rigid rotation of the whole motor, with
    //   the lever (phi), and — the coupling the analytic reduction had lost — with S2 BENDING, which rolls the
    //   frame about n1. (c1,c2,c3) are the native head pose resolved in THIS frame at build time, so the rest
    //   direction co-rotates with the neck and generates no torque from rigid motion of the motor.
    //
    //   matc[4] = F8 generalized-force axis: 1 = econv (the geometry's own rotation axis, i.e. the exact
    //   Jacobian), 0 = eup (the LEGACY convention of matS2SolveStep / s2SolveM — retained ONLY so the chi
    //   increment can be measured against an otherwise identical baseline; see the report's AXIS section).
    //   params[17N+m] = gamma_r (the head's sphere-only rotational drag). restC[m],[N+m],[2N+m] = c1,c2,c3.
    // ===============================================================================================
    public static void matS2SolveStepTilt(DoubleArray nodes, DoubleArray frame, DoubleArray q, FloatArray bondData,
                                          IntArray boundSeg, DoubleArray params, DoubleArray sys, DoubleArray outGeom,
                                          FloatArray forceDotFil, FloatArray forceMag, IntArray matc, IntArray counts,
                                          DoubleArray convF, DoubleArray chiHead, DoubleArray restC) {
        int nM = counts.get(0), maxIt = counts.get(1), M = counts.get(2);
        int STRIDE = 13;
        int nF = 3 * M, n = nF + 3, W = n + 1;
        double tol = 3e-7;
        long tt = matc.get(0), seed = matc.get(1); int brownOn = matc.get(2);
        int mPolicy = matc.get(3); int axMode = matc.get(4);
        for (@Parallel int m = 0; m < nM; m++) {
            double bx = frame.get(m), by = frame.get(nM + m), bz = frame.get(2 * nM + m);
            double ex = frame.get(3 * nM + m), ey = frame.get(4 * nM + m), ez = frame.get(5 * nM + m);
            double ux = frame.get(6 * nM + m), uy = frame.get(7 * nM + m), uz = frame.get(8 * nM + m);
            double gEx = frame.get(9 * nM + m), gEy = frame.get(10 * nM + m), gEz = frame.get(11 * nM + m);
            double gTx = frame.get(12 * nM + m), gTy = frame.get(13 * nM + m), gTz = frame.get(14 * nM + m);
            double lb = params.get(m), rF8x = params.get(nM + m), rF8y = params.get(2 * nM + m);
            double rCx = params.get(3 * nM + m), rCy = params.get(4 * nM + m);
            double kF8Code = params.get(5 * nM + m), kc = params.get(6 * nM + m), kbnd = params.get(7 * nM + m);
            double gPhi = params.get(8 * nM + m), gPsi = params.get(9 * nM + m), dt = params.get(10 * nM + m);
            double ks = params.get(11 * nM + m), l0um = params.get(12 * nM + m), kbend = params.get(13 * nM + m);
            double floorZ = params.get(14 * nM + m), kfloor = params.get(15 * nM + m), gNode = params.get(16 * nM + m);
            double gRot = params.get(18 * nM + m);   // row 17 is now the S2->lever rest angle
            double l0m = l0um * 1e-6, aN = gNode / dt, aphi = gPhi / dt;
            int bs = boundSeg.get(m); boolean bnd = bs >= 0; int dB = m * STRIDE;
            int brownM = brownOn;
            if (bnd) { if ((mPolicy & 1) != 0) brownM = 0; }
            else     { if ((mPolicy & 2) != 0) brownM = 0; }
            double f8x = bnd ? bondData.get(dB) : 0.0, f8y = bnd ? bondData.get(dB + 1) : 0.0, f8z = bnd ? bondData.get(dB + 2) : 0.0;
            double phi = q.get(m), psi = q.get(nM + m), thetaS = q.get(2 * nM + m), psiActin = q.get(3 * nM + m);
            double chi = chiHead.get(m);
            double c1 = restC.get(m), c2 = restC.get(nM + m), c3 = restC.get(2 * nM + m);
            double skewF = convF.get(12 * nM + m);
            double cb0 = 0, cb1 = 0, cb2 = 0, ce0 = 0, ce1 = 0, ce2 = 0, cu0 = 0, cu1 = 0, cu2 = 0, of0 = 0, of1 = 0, of2 = 0;
            if (skewF != 0.0) {
                cb0 = convF.get(m);          cb1 = convF.get(nM + m);      cb2 = convF.get(2 * nM + m);
                ce0 = convF.get(3 * nM + m); ce1 = convF.get(4 * nM + m);  ce2 = convF.get(5 * nM + m);
                cu0 = convF.get(6 * nM + m); cu1 = convF.get(7 * nM + m);  cu2 = convF.get(8 * nM + m);
                of0 = convF.get(9 * nM + m); of1 = convF.get(10 * nM + m); of2 = convF.get(11 * nM + m);
            }
            int base = m * (n * W);
            int st = 0;
            for (int it = 0; it < maxIt; it++) {
                for (int i = 0; i < n; i++) for (int j = 0; j < W; j++) sys.set(base + i * W + j, 0.0);
                double Px = nodes.get((3 * M) * nM + m), Py = nodes.get((3 * M + 1) * nM + m), Pz = nodes.get((3 * M + 2) * nM + m);
                double cphi = Math.cos(phi), sphi = Math.sin(phi);
                double cpsi = Math.cos(psi), spsi = Math.sin(psi);
                double cchi = Math.cos(chi), schi = Math.sin(chi);
                // ---- branch geometry: base vectors, lever, head basis ----------------------------------
                double abx, aby, abz, aex, aey, aez, aux, auy, auz, ofx, ofy, ofz;
                if (skewF == 0.0) { abx=bx; aby=by; abz=bz; aex=ex; aey=ey; aez=ez; aux=ux; auy=uy; auz=uz; ofx=0; ofy=0; ofz=0; }
                else              { abx=cb0; aby=cb1; abz=cb2; aex=ce0; aey=ce1; aez=ce2; aux=cu0; auy=cu1; auz=cu2; ofx=of0; ofy=of1; ofz=of2; }
                double uBx = aux*cphi + abx*sphi, uBy = auy*cphi + aby*sphi, uBz = auz*cphi + abz*sphi;
                double Cx = Px + ofx + uBx*lb, Cy = Py + ofy + uBy*lb, Cz = Pz + ofz + uBz*lb;
                // p1hat = normalize(b*rF8x + u*rF8y) ; e0 = R(econv, psi) p1hat ; that = e0 x econv
                double p1x = abx*rF8x + aux*rF8y, p1y = aby*rF8x + auy*rF8y, p1z = abz*rF8x + auz*rF8y;
                double pn = Math.sqrt(p1x*p1x + p1y*p1y + p1z*p1z); double ipn = 1.0/pn; p1x*=ipn; p1y*=ipn; p1z*=ipn;
                double q1x = aey*p1z - aez*p1y, q1y = aez*p1x - aex*p1z, q1z = aex*p1y - aey*p1x;   // econv x p1hat
                double e0x = cpsi*p1x + spsi*q1x, e0y = cpsi*p1y + spsi*q1y, e0z = cpsi*p1z + spsi*q1z;
                double thx = e0y*aez - e0z*aey, thy = e0z*aex - e0x*aez, thz = e0x*aey - e0y*aex;   // that = e0 x econv
                // eBind = cos(chi) e0 + sin(chi) econv
                double ebx = cchi*e0x + schi*aex, eby = cchi*e0y + schi*aey, ebz = cchi*e0z + schi*aez;
                // head-side offsets: R(that, chi) applied to the psi-rotated rest offsets
                double d0x = abx*(rF8x-rCx) + aux*(rF8y-rCy), d0y = aby*(rF8x-rCx) + auy*(rF8y-rCy), d0z = abz*(rF8x-rCx) + auz*(rF8y-rCy);
                double pd0x = d0x*cpsi + (aey*d0z - aez*d0y)*spsi, pd0y = d0y*cpsi + (aez*d0x - aex*d0z)*spsi, pd0z = d0z*cpsi + (aex*d0y - aey*d0x)*spsi;
                double rcx0 = abx*rCx + aux*rCy, rcy0 = aby*rCx + auy*rCy, rcz0 = abz*rCx + auz*rCy;
                double prcx = rcx0*cpsi + (aey*rcz0 - aez*rcy0)*spsi, prcy = rcy0*cpsi + (aez*rcx0 - aex*rcz0)*spsi, prcz = rcz0*cpsi + (aex*rcy0 - aey*rcx0)*spsi;
                double omc = 1.0 - cchi;
                double dd1 = thx*pd0x + thy*pd0y + thz*pd0z;
                double fcx = pd0x*cchi + (thy*pd0z - thz*pd0y)*schi + thx*dd1*omc;
                double fcy = pd0y*cchi + (thz*pd0x - thx*pd0z)*schi + thy*dd1*omc;
                double fcz = pd0z*cchi + (thx*pd0y - thy*pd0x)*schi + thz*dd1*omc;
                double dd2 = thx*prcx + thy*prcy + thz*prcz;
                double hcx = prcx*cchi + (thy*prcz - thz*prcy)*schi + thx*dd2*omc;
                double hcy = prcy*cchi + (thz*prcx - thx*prcz)*schi + thy*dd2*omc;
                double hcz = prcz*cchi + (thx*prcy - thy*prcx)*schi + thz*dd2*omc;
                double xF8x = Cx + fcx, xF8y = Cy + fcy, xF8z = Cz + fcz;
                // the phi arm (C-P without the gauge offset) and the F8 generalized-force axis
                double cpx = uBx*lb, cpy = uBy*lb, cpz = uBz*lb;
                double gux, guy, guz;
                if (axMode != 0) { gux = aex; guy = aey; guz = aez; }     // exact: the geometry's own axis
                else             { gux = aux; guy = auy; guz = auz; }     // legacy matS2SolveStep convention
                // STRETCH
                for (int i = 0; i < M; i++) {
                    double ax = nodes.get((3*(i+1))*nM+m) - nodes.get((3*i)*nM+m);
                    double ay = nodes.get((3*(i+1)+1)*nM+m) - nodes.get((3*i+1)*nM+m);
                    double az = nodes.get((3*(i+1)+2)*nM+m) - nodes.get((3*i+2)*nM+m);
                    double len = Math.sqrt(ax*ax+ay*ay+az*az); if (len < 1e-15) continue;
                    double s = 1.0/len, uxx = ax*s, uyy = ay*s, uzz = az*s;
                    double lenM = len*1e-6, tS = ks*(lenM - l0m), tL = tS/lenM;
                    int ri = (i>=1)?(i-1)*3:-1000, rj = i*3;
                    addF(sys,base,W,n, ri+0, tS*uxx); addF(sys,base,W,n, ri+1, tS*uyy); addF(sys,base,W,n, ri+2, tS*uzz);
                    addF(sys,base,W,n, rj+0,-tS*uxx); addF(sys,base,W,n, rj+1,-tS*uyy); addF(sys,base,W,n, rj+2,-tS*uzz);
                    for (int p=0;p<3;p++) for(int qq=0;qq<3;qq++){
                        double up = (p==0)?uxx:((p==1)?uyy:uzz), uq=(qq==0)?uxx:((qq==1)?uyy:uzz);
                        double id=(p==qq)?1.0:0.0; double kb2 = ks*up*uq + tL*(id-up*uq);
                        addK(sys,base,W, ri+p, ri+qq, kb2); addK(sys,base,W, rj+p, rj+qq, kb2);
                        addK(sys,base,W, ri+p, rj+qq,-kb2); addK(sys,base,W, rj+p, ri+qq,-kb2);
                    }
                }
                // BEND clamped joint 0
                {
                    double b0x = nodes.get(3*nM+m)-nodes.get(m), b0y = nodes.get((4)*nM+m)-nodes.get(nM+m), b0z = nodes.get(5*nM+m)-nodes.get(2*nM+m);
                    double lbb = Math.sqrt(b0x*b0x+b0y*b0y+b0z*b0z);
                    if (lbb > 1e-12) {
                        double ilb=1.0/lbb, c = gTx*b0x+gTy*b0y+gTz*b0z; c*=ilb; if(c>1)c=1; if(c<-1)c=-1;
                        double th = dacos(c), A1=a1(th), A2=a2(th); double clb2=c*ilb*ilb;
                        double g0x=gTx*ilb-clb2*b0x, g0y=gTy*ilb-clb2*b0y, g0z=gTz*ilb-clb2*b0z;
                        int r1=0;
                        addF(sys,base,W,n, r1+0, kbend*A1*1e6*g0x); addF(sys,base,W,n, r1+1, kbend*A1*1e6*g0y); addF(sys,base,W,n, r1+2, kbend*A1*1e6*g0z);
                        double ilb2=ilb*ilb, ilb3=ilb2*ilb, ilb4=ilb2*ilb2;
                        for (int p=0;p<3;p++) for(int qq=0;qq<3;qq++){
                            double b0p=(p==0)?b0x:((p==1)?b0y:b0z), b0q=(qq==0)?b0x:((qq==1)?b0y:b0z);
                            double tp=(p==0)?gTx:((p==1)?gTy:gTz), tq=(qq==0)?gTx:((qq==1)?gTy:gTz);
                            double id=(p==qq)?1.0:0.0;
                            double Hc = -(b0p*tq+tp*b0q)*ilb3 - c*id*ilb2 + 3.0*c*b0p*b0q*ilb4;
                            double gp=(p==0)?g0x:((p==1)?g0y:g0z), gq=(qq==0)?g0x:((qq==1)?g0y:g0z);
                            addK(sys,base,W, r1+p, r1+qq, 1e12*kbend*(A2*gp*gq - A1*Hc));
                        }
                    }
                }
                // BEND interior joints
                for (int j=1;j<M;j++){
                    double ax = nodes.get((3*j)*nM+m)-nodes.get((3*(j-1))*nM+m);
                    double ay = nodes.get((3*j+1)*nM+m)-nodes.get((3*(j-1)+1)*nM+m);
                    double az = nodes.get((3*j+2)*nM+m)-nodes.get((3*(j-1)+2)*nM+m);
                    double bxx = nodes.get((3*(j+1))*nM+m)-nodes.get((3*j)*nM+m);
                    double byy = nodes.get((3*(j+1)+1)*nM+m)-nodes.get((3*j+1)*nM+m);
                    double bzz = nodes.get((3*(j+1)+2)*nM+m)-nodes.get((3*j+2)*nM+m);
                    double la = Math.sqrt(ax*ax+ay*ay+az*az), lbn = Math.sqrt(bxx*bxx+byy*byy+bzz*bzz);
                    if (la<1e-12 || lbn<1e-12) continue;
                    double ila=1.0/la, ilb=1.0/lbn, iab=ila*ilb;
                    double c = (ax*bxx+ay*byy+az*bzz)*iab; if(c>1)c=1; if(c<-1)c=-1;
                    double th=dacos(c), A1=a1(th), A2=a2(th);
                    double cla2=c*ila*ila, clb2=c*ilb*ilb;
                    double Dx=bxx*iab-cla2*ax, Dy=byy*iab-cla2*ay, Dz=bzz*iab-cla2*az;
                    double Ex=ax*iab-clb2*bxx, Ey=ay*iab-clb2*byy, Ez=az*iab-clb2*bzz;
                    double gc0x=-Dx,gc0y=-Dy,gc0z=-Dz, gc1x=Dx-Ex,gc1y=Dy-Ey,gc1z=Dz-Ez, gc2x=Ex,gc2y=Ey,gc2z=Ez;
                    int rJm1=(j-1>=1)?(j-2)*3:-1000, rJ=(j-1)*3, rJp1=j*3;
                    addF(sys,base,W,n, rJm1+0,kbend*A1*1e6*gc0x); addF(sys,base,W,n, rJm1+1,kbend*A1*1e6*gc0y); addF(sys,base,W,n, rJm1+2,kbend*A1*1e6*gc0z);
                    addF(sys,base,W,n, rJ+0,  kbend*A1*1e6*gc1x); addF(sys,base,W,n, rJ+1,  kbend*A1*1e6*gc1y); addF(sys,base,W,n, rJ+2,  kbend*A1*1e6*gc1z);
                    addF(sys,base,W,n, rJp1+0,kbend*A1*1e6*gc2x); addF(sys,base,W,n, rJp1+1,kbend*A1*1e6*gc2y); addF(sys,base,W,n, rJp1+2,kbend*A1*1e6*gc2z);
                    double ila2=ila*ila, ila3=ila2*ila, ila4=ila2*ila2, ilb2=ilb*ilb, ilb3=ilb2*ilb, ilb4=ilb2*ilb2;
                    for (int ai=0; ai<3; ai++){
                        int rA = (ai==0)?rJm1:((ai==1)?rJ:rJp1);
                        double saA=(ai==0)?-1.0:((ai==1)?1.0:0.0), sbA=(ai==0)?0.0:((ai==1)?-1.0:1.0);
                        for (int bi=0; bi<3; bi++){
                            int rB = (bi==0)?rJm1:((bi==1)?rJ:rJp1);
                            double saB=(bi==0)?-1.0:((bi==1)?1.0:0.0), sbB=(bi==0)?0.0:((bi==1)?-1.0:1.0);
                            for (int p=0;p<3;p++){
                                double ap=(p==0)?ax:((p==1)?ay:az), bp=(p==0)?bxx:((p==1)?byy:bzz);
                                double gAp=(p==0)?((ai==0)?gc0x:((ai==1)?gc1x:gc2x)):((p==1)?((ai==0)?gc0y:((ai==1)?gc1y:gc2y)):((ai==0)?gc0z:((ai==1)?gc1z:gc2z)));
                                for (int qq=0;qq<3;qq++){
                                    double aq=(qq==0)?ax:((qq==1)?ay:az), bq=(qq==0)?bxx:((qq==1)?byy:bzz);
                                    double id=(p==qq)?1.0:0.0;
                                    double Haa=-(ap*bq+bp*aq)*ila3*ilb - c*id*ila2 + 3.0*c*ap*aq*ila4;
                                    double Hbb=-(bp*aq+ap*bq)*ilb3*ila - c*id*ilb2 + 3.0*c*bp*bq*ilb4;
                                    double Hab_pq= id*iab - bp*bq*ila*ilb3 - ap*aq*ila3*ilb + c*ap*bq*ila2*ilb2;
                                    double Hab_qp= id*iab - bq*bp*ila*ilb3 - aq*ap*ila3*ilb + c*aq*bp*ila2*ilb2;
                                    double Hc = saA*saB*Haa + sbA*sbB*Hbb + saA*sbB*Hab_pq + sbA*saB*Hab_qp;
                                    double gBq=(qq==0)?((bi==0)?gc0x:((bi==1)?gc1x:gc2x)):((qq==1)?((bi==0)?gc0y:((bi==1)?gc1y:gc2y)):((bi==0)?gc0z:((bi==1)?gc1z:gc2z)));
                                    addK(sys,base,W, rA+p, rB+qq, 1e12*kbend*(A2*gAp*gBq - A1*Hc));
                                }
                            }
                        }
                    }
                }
                // FLOOR
                for (int j=1;j<=M;j++){
                    double z = nodes.get((3*j)*nM+m)*ux + nodes.get((3*j+1)*nM+m)*uy + nodes.get((3*j+2)*nM+m)*uz;
                    if (z < floorZ){ double fk = kfloor*(floorZ - z)*1e-6; int r=(j-1)*3;
                        addF(sys,base,W,n, r+0, fk*ux); addF(sys,base,W,n, r+1, fk*uy); addF(sys,base,W,n, r+2, fk*uz);
                        for(int p=0;p<3;p++){ double ep=(p==0)?ux:((p==1)?uy:uz);
                            for(int qq=0;qq<3;qq++){ double eq=(qq==0)?ux:((qq==1)?uy:uz); addK(sys,base,W, r+p, r+qq, kfloor*ep*eq); } }
                    }
                }
                // node drag diagonal + node Brownian RHS
                for (int r=0;r<nF;r++) addK(sys,base,W, r, r, aN);
                if (brownM != 0) for (int j=1;j<=M;j++){ int fb=(j-1)*3;
                    for (int k=0;k<3;k++){ long salt = 0x4811L + ((long)m*1009 + (long)j*131 + k)*7919L;
                        addF(sys,base,W,n, fb+k, brownTorqueD(gNode, dt, seed, tt, salt)); } }
                // ---- F8 / converter block over the THREE angular coordinates -----------------------------
                int pB = 3*(M-1), iPhi = nF, iPsi = nF+1, iChi = nF+2;
                double Jphix=guy*cpz-guz*cpy, Jphiy=guz*cpx-gux*cpz, Jphiz=gux*cpy-guy*cpx;
                double Jpsix, Jpsiy, Jpsiz;
                if (axMode != 0) { Jpsix=aey*fcz-aez*fcy; Jpsiy=aez*fcx-aex*fcz; Jpsiz=aex*fcy-aey*fcx; }
                else             { Jpsix=guy*fcz-guz*fcy; Jpsiy=guz*fcx-gux*fcz; Jpsiz=gux*fcy-guy*fcx; }
                double Jchix=thy*fcz-thz*fcy, Jchiy=thz*fcx-thx*fcz, Jchiz=thx*fcy-thy*fcx;
                double J03=Jphix*1e-6,J04=Jpsix*1e-6,J05=Jchix*1e-6;
                double J13=Jphiy*1e-6,J14=Jpsiy*1e-6,J15=Jchiy*1e-6;
                double J23=Jphiz*1e-6,J24=Jpsiz*1e-6,J25=Jchiz*1e-6;
                double kfSI=kF8Code*1e6;
                for (int i=0;i<6;i++){
                    double Ji0=(i==0)?1:0, Ji1=(i==1)?1:0, Ji2=(i==2)?1:0;
                    if(i==3){Ji0=J03;Ji1=J13;Ji2=J23;} if(i==4){Ji0=J04;Ji1=J14;Ji2=J24;} if(i==5){Ji0=J05;Ji1=J15;Ji2=J25;}
                    int di=(i<3)?(pB+i):(i==3?iPhi:(i==4?iPsi:iChi));
                    for (int jj=0;jj<6;jj++){
                        double Jj0=(jj==0)?1:0, Jj1=(jj==1)?1:0, Jj2=(jj==2)?1:0;
                        if(jj==3){Jj0=J03;Jj1=J13;Jj2=J23;} if(jj==4){Jj0=J04;Jj1=J14;Jj2=J24;} if(jj==5){Jj0=J05;Jj1=J15;Jj2=J25;}
                        int dj=(jj<3)?(pB+jj):(jj==3?iPhi:(jj==4?iPsi:iChi));
                        addK(sys,base,W, di, dj, kfSI*(Ji0*Jj0+Ji1*Jj1+Ji2*Jj2));
                    }
                }
                // ---- head-orientation potential: ONE form, target chosen by binding state ----------------
                // CANONICAL SITE-NORMAL BOUND TARGET (noncanonical, flag-gated per motor by restC[6N+m]).
                // When the flag is set the bound branch uses eTarget = -n_boundSite (written by
                // SiteNormalBindSystem.siteCoupleStep from the LATCHED site's live material frame) and the
                // orientation coordinate becomes the head-local +x axis xHeadHat instead of eBind. When the
                // flag is 0 every expression below is the VERBATIM legacy arithmetic (delta = 0 ⇒ the two
                // coincide algebraically, but the legacy branch is retained literally so OFF is byte-identical).
                // Report: docs/motor/SITE_NORMAL_HEAD_BINDING.md.
                double etx, ety, etz;
                double n1x=uBx, n1y=uBy, n1z=uBz;                 // the lever/neck axis (unit by construction)
                double n2x=0,n2y=0,n2z=0, n3x=0,n3y=0,n3z=0, gN=1.0, sLen=1.0, n1px=0,n1py=0,n1pz=0;
                if (bnd) {
                    if (restC.get(6*nM+m) != 0.0) {
                        etx = restC.get(3*nM+m); ety = restC.get(4*nM+m); etz = restC.get(5*nM+m);
                    } else {
                        double ca = Math.cos(psiActin), sa = Math.sin(psiActin);
                        etx = ca*p1x + sa*q1x; ety = ca*p1y + sa*q1y; etz = ca*p1z + sa*q1z;
                    }
                } else {
                    // LIVE NECK FRAME from the distal S2 element
                    int jm = (M >= 2) ? (M-1) : 0;
                    double sx = Px - nodes.get((3*jm)*nM+m), sy = Py - nodes.get((3*jm+1)*nM+m), sz = Pz - nodes.get((3*jm+2)*nM+m);
                    sLen = Math.sqrt(sx*sx + sy*sy + sz*sz); if (sLen < 1e-18) sLen = 1e-18;
                    double isl = 1.0/sLen; sx*=isl; sy*=isl; sz*=isl;
                    double pn1 = sx*n1x + sy*n1y + sz*n1z;
                    double gx = sx - pn1*n1x, gy = sy - pn1*n1y, gz = sz - pn1*n1z;
                    gN = Math.sqrt(gx*gx + gy*gy + gz*gz);
                    if (gN < 1e-9) {   // degenerate (S2 tangent parallel to the lever): documented fallback
                        gx = aex - (aex*n1x+aey*n1y+aez*n1z)*n1x; gy = aey - (aex*n1x+aey*n1y+aez*n1z)*n1y; gz = aez - (aex*n1x+aey*n1y+aez*n1z)*n1z;
                        gN = Math.sqrt(gx*gx + gy*gy + gz*gz); if (gN < 1e-18) gN = 1e-18;
                    }
                    double ig = 1.0/gN; n2x = gx*ig; n2y = gy*ig; n2z = gz*ig;
                    n3x = n1y*n2z - n1z*n2y; n3y = n1z*n2x - n1x*n2z; n3z = n1x*n2y - n1y*n2x;
                    etx = c1*n1x + c2*n2x + c3*n3x; ety = c1*n1y + c2*n2y + c3*n3y; etz = c1*n1z + c2*n2z + c3*n3z;
                    n1px = -aux*sphi + abx*cphi; n1py = -auy*sphi + aby*cphi; n1pz = -auz*sphi + abz*cphi;
                }
                // ORIENTATION COORDINATE — ONE convention for every branch.
                // r_F8 lies ON the head's long axis (r_F8 = (+3.5, 0) nm), so
                //     eBind = normalize(xF8 - xH) = R(psi,chi) a_hat = xHeadHat
                // identically, for every psi and chi. There is no second head direction and no fixed
                // eBind->xHeadHat rotation. (The 23.19859 deg offset that used to exist was an artefact of the
                // unintended transverse F8 component; see the F8 long-axis note in TwoBodyConverterMotor.)
                double dOri = ebx*etx + eby*ety + ebz*etz; if (dOri > 1.0) dOri = 1.0; if (dOri < -1.0) dOri = -1.0;
                double thOri = dacos(dOri);
                // lambda = k*theta/sin(theta): finite at theta -> 0 (series), floored near theta -> pi so the
                // 0/0 at the antipodal saddle cannot amplify round-off (the TORQUE lambda*sin(theta) stays <= k*pi).
                double sOri = Math.sin(thOri); if (sOri < 1.0e-6) sOri = 1.0e-6;
                double lam = (thOri < 1.0e-3) ? kbnd * (1.0 + thOri * thOri / 6.0) : kbnd * thOri / sOri;
                // d eBind/d psi = cos(chi) (econv x e0) ; d eBind/d chi = cos(chi) econv - sin(chi) e0
                // psi and chi rotate the head RIGIDLY about econv and about that, so for the head-fixed head
                // axis:  d/dpsi = econv x eBind = cos(chi)(econv x e0) ,  d/dchi = that x eBind =
                // cos(chi) econv - sin(chi) e0.
                double dEcx = cchi*aex - schi*e0x, dEcy = cchi*aey - schi*e0y, dEcz = cchi*aez - schi*e0z;
                double dEpx = aey*e0z - aez*e0y, dEpy = aez*e0x - aex*e0z, dEpz = aex*e0y - aey*e0x;
                double QpsiOri = lam * cchi * (dEpx*etx + dEpy*ety + dEpz*etz);
                double QchiOri = lam * (dEcx*etx + dEcy*ety + dEcz*etz);
                double QphiOri = 0.0;
                if (!bnd) {
                    // d eTarget/d phi through n1(phi) and the Gram-Schmidt slip of n2, n3
                    int jm2 = (M >= 2) ? (M-1) : 0;
                    double isl2 = 1.0/sLen;
                    double shx = (Px - nodes.get((3*jm2)*nM+m))*isl2;
                    double shy = (Py - nodes.get((3*jm2+1)*nM+m))*isl2;
                    double shz = (Pz - nodes.get((3*jm2+2)*nM+m))*isl2;
                    double dpn1 = shx*n1px + shy*n1py + shz*n1pz;   // d(shat.n1)/dphi = shat . n1'
                    double pn1 = shx*n1x + shy*n1y + shz*n1z;
                    double dgx = -dpn1*n1x - pn1*n1px, dgy = -dpn1*n1y - pn1*n1py, dgz = -dpn1*n1z - pn1*n1pz;
                    double proj = n2x*dgx + n2y*dgy + n2z*dgz;
                    double dn2x = (dgx - n2x*proj)/gN, dn2y = (dgy - n2y*proj)/gN, dn2z = (dgz - n2z*proj)/gN;
                    double dn3x = (n1py*n2z - n1pz*n2y) + (n1y*dn2z - n1z*dn2y);
                    double dn3y = (n1pz*n2x - n1px*n2z) + (n1z*dn2x - n1x*dn2z);
                    double dn3z = (n1px*n2y - n1py*n2x) + (n1x*dn2y - n1y*dn2x);
                    double dtx = c1*n1px + c2*dn2x + c3*dn3x, dty = c1*n1py + c2*dn2y + c3*dn3y, dtz = c1*n1pz + c2*dn2z + c3*dn3z;
                    QphiOri = lam * (ebx*dtx + eby*dty + ebz*dtz);
                    // the equal-and-opposite reaction on the distal S2 element (a transverse force couple)
                    double beta = (ebx*(c2*n3x - c3*n2x) + eby*(c2*n3y - c3*n2y) + ebz*(c2*n3z - c3*n2z)) / gN;
                    double fN = lam * beta / (sLen * 1e-6);
                    int rP = 3*(M-1), rPm = (M >= 2) ? 3*(M-2) : -1000;
                    addF(sys,base,W,n, rP+0, fN*n3x); addF(sys,base,W,n, rP+1, fN*n3y); addF(sys,base,W,n, rP+2, fN*n3z);
                    addF(sys,base,W,n, rPm+0,-fN*n3x); addF(sys,base,W,n, rPm+1,-fN*n3y); addF(sys,base,W,n, rPm+2,-fN*n3z);
                }
                // converter spring + orientation Hessian + drag diagonal
                addK(sys,base,W, iPhi,iPhi, kc); addK(sys,base,W, iPhi,iPsi,-kc); addK(sys,base,W, iPsi,iPhi,-kc); addK(sys,base,W, iPsi,iPsi, kc);
                // orientation Hessian = the exact Gauss-Newton form k * (d eBind/d qi . d eBind/d qj) at the
                // minimum. The (psi,chi) directions are orthogonal for every chi, so there is NO cross term.
                addK(sys,base,W, iPsi,iPsi, kbnd*cchi*cchi); addK(sys,base,W, iChi,iChi, kbnd);
                double gPsiChi = gRot + (gPsi - gRot)*cchi*cchi;     // Gamma_psipsi(chi)
                addK(sys,base,W, iPhi,iPhi, aphi); addK(sys,base,W, iPsi,iPsi, gPsiChi/dt); addK(sys,base,W, iChi,iChi, gPsi/dt);
                double th2 = psi - phi;
                double caFx=cpy*f8z-cpz*f8y, caFy=cpz*f8x-cpx*f8z, caFz=cpx*f8y-cpy*f8x;
                double fcFx=fcy*f8z-fcz*f8y, fcFy=fcz*f8x-fcx*f8z, fcFz=fcx*f8y-fcy*f8x;
                double QphiF8=(gux*caFx+guy*caFy+guz*caFz)*1e-6;
                double QpsiF8 = (axMode != 0 ? (aex*fcFx+aey*fcFy+aez*fcFz) : (gux*fcFx+guy*fcFy+guz*fcFz))*1e-6;
                double QchiF8=(thx*fcFx+thy*fcFy+thz*fcFz)*1e-6;
                addF(sys,base,W,n, pB+0, f8x); addF(sys,base,W,n, pB+1, f8y); addF(sys,base,W,n, pB+2, f8z);
                addF(sys,base,W,n, iPhi, QphiF8 + kc*(th2-thetaS) + QphiOri);
                addF(sys,base,W,n, iPsi, QpsiF8 - kc*(th2-thetaS) + QpsiOri);
                addF(sys,base,W,n, iChi, QchiF8 + QchiOri);
                if (brownM != 0) { addF(sys,base,W,n, iPhi, brownTorqueD(gPhi, dt, seed, tt, 0x4841L + (long)m*7919L));
                                    addF(sys,base,W,n, iPsi, brownTorqueD(gPsiChi, dt, seed, tt, 0x4842L + (long)m*7919L));
                                    addF(sys,base,W,n, iChi, brownTorqueD(gPsi, dt, seed, tt, 0x4843L + (long)m*7919L)); }
                // ===== S2 -> LEVER TERMINAL BEND JOINT — identical to matS2SolveStep's block (see there) =====
                double thL0 = params.get(17 * nM + m);
                if (thL0 >= 0.0 && M >= 2) {
                    double aLx = nodes.get((3*M)*nM+m)   - nodes.get((3*(M-1))*nM+m);
                    double aLy = nodes.get((3*M+1)*nM+m) - nodes.get((3*(M-1)+1)*nM+m);
                    double aLz = nodes.get((3*M+2)*nM+m) - nodes.get((3*(M-1)+2)*nM+m);
                    double La = Math.sqrt(aLx*aLx + aLy*aLy + aLz*aLz);
                    if (La > 1e-12) {
                        double iL = 1.0/La, sxL = aLx*iL, syL = aLy*iL, szL = aLz*iL;
                        double cL = sxL*uBx + syL*uBy + szL*uBz; if (cL > 1) cL = 1; if (cL < -1) cL = -1;
                        double thJ = dacos(cL), snJ = Math.sin(thJ);
                        if (snJ > 1e-6) {
                            double dth = thJ - thL0;
                            double A1L = dth/snJ, A2L = (1.0 - dth*cL/snJ)/(snJ*snJ);
                            double gxL = (uBx - cL*sxL)*iL, gyL = (uBy - cL*syL)*iL, gzL = (uBz - cL*szL)*iL;
                            double wxL = aey*uBz - aez*uBy, wyL = aez*uBx - aex*uBz, wzL = aex*uBy - aey*uBx;
                            double cphL = sxL*wxL + syL*wyL + szL*wzL;
                            int rM = (M-1)*3, rMm = (M-2)*3;
                            addF(sys,base,W,n, rM+0,  kbend*A1L*1e6*gxL); addF(sys,base,W,n, rM+1,  kbend*A1L*1e6*gyL); addF(sys,base,W,n, rM+2,  kbend*A1L*1e6*gzL);
                            addF(sys,base,W,n, rMm+0,-kbend*A1L*1e6*gxL); addF(sys,base,W,n, rMm+1,-kbend*A1L*1e6*gyL); addF(sys,base,W,n, rMm+2,-kbend*A1L*1e6*gzL);
                            addF(sys,base,W,n, iPhi,  kbend*A1L*cphL);
                            double iL2 = iL*iL;
                            for (int p=0;p<3;p++){
                                double upL=(p==0)?uBx:((p==1)?uBy:uBz), spL=(p==0)?sxL:((p==1)?syL:szL);
                                double gpL=(p==0)?gxL:((p==1)?gyL:gzL), wpL=(p==0)?wxL:((p==1)?wyL:wzL);
                                for (int qq=0;qq<3;qq++){
                                    double uqL=(qq==0)?uBx:((qq==1)?uBy:uBz), sqL=(qq==0)?sxL:((qq==1)?syL:szL);
                                    double gqL=(qq==0)?gxL:((qq==1)?gyL:gzL);
                                    double idL=(p==qq)?1.0:0.0;
                                    double HcL = (-(upL*sqL + spL*uqL) + 3.0*cL*spL*sqL - cL*idL)*iL2;
                                    double kv = 1e12*kbend*(A2L*gpL*gqL - A1L*HcL);
                                    addK(sys,base,W, rM+p,  rM+qq,  kv);  addK(sys,base,W, rMm+p, rMm+qq, kv);
                                    addK(sys,base,W, rM+p,  rMm+qq,-kv);  addK(sys,base,W, rMm+p, rM+qq, -kv);
                                }
                                double HmL = (wpL - cphL*spL)*iL;
                                double kmix = 1e6*kbend*(A2L*gpL*cphL - A1L*HmL);
                                addK(sys,base,W, rM+p,  iPhi,  kmix); addK(sys,base,W, iPhi, rM+p,   kmix);
                                addK(sys,base,W, rMm+p, iPhi, -kmix); addK(sys,base,W, iPhi, rMm+p, -kmix);
                            }
                            addK(sys,base,W, iPhi, iPhi, kbend*(A2L*cphL*cphL + A1L*cL));
                        }
                    }
                }
                // solve (Gauss–Jordan)
                for (int c=0;c<n;c++){
                    int p=c; double bestv=sys.get(base+c*W+c); if(bestv<0)bestv=-bestv;
                    for(int r=c+1;r<n;r++){ double v=sys.get(base+r*W+c); if(v<0)v=-v; if(v>bestv){bestv=v;p=r;} }
                    if(p!=c){ for(int k=0;k<W;k++){ double tmp=sys.get(base+c*W+k); sys.set(base+c*W+k, sys.get(base+p*W+k)); sys.set(base+p*W+k, tmp); } }
                    double piv=sys.get(base+c*W+c); double apiv=piv<0?-piv:piv;
                    if(!(apiv>1e-300)) st=1;
                    for(int r=0;r<n;r++){ if(r==c) continue; double fac=sys.get(base+r*W+c)/piv;
                        for(int k=c;k<W;k++) sys.set(base+r*W+k, sys.get(base+r*W+k)-fac*sys.get(base+c*W+k)); }
                }
                double mv=0;
                for (int j=1;j<=M;j++){ int fb=(j-1)*3;
                    for(int k=0;k<3;k++){ double dqm=sys.get(base+(fb+k)*W+n)/sys.get(base+(fb+k)*W+(fb+k));
                        double dnode=dqm*1e6; nodes.set((3*j+k)*nM+m, nodes.get((3*j+k)*nM+m)+dnode);
                        double amv=dnode<0?-dnode:dnode; if(amv>mv) mv=amv; } }
                double dphi=sys.get(base+iPhi*W+n)/sys.get(base+iPhi*W+iPhi);
                double dpsi=sys.get(base+iPsi*W+n)/sys.get(base+iPsi*W+iPsi);
                double dchi=sys.get(base+iChi*W+n)/sys.get(base+iChi*W+iChi);
                phi+=dphi; psi+=dpsi; chi+=dchi;
                if (chi >  1.5533430342749532) chi =  1.5533430342749532;   // |chi| <= 89 deg: keep away from the
                if (chi < -1.5533430342749532) chi = -1.5533430342749532;   // spherical-coordinate pole
                nodes.set(m, gEx); nodes.set(nM+m, gEy); nodes.set(2*nM+m, gEz);
                if (mv < tol) break;
            }
            // final geometry (chi-aware) → outGeom + reaction writeback
            double Px2=nodes.get((3*M)*nM+m), Py2=nodes.get((3*M+1)*nM+m), Pz2=nodes.get((3*M+2)*nM+m);
            double cphi=Math.cos(phi), sphi=Math.sin(phi);
            double cpsi=Math.cos(psi), spsi=Math.sin(psi);
            double cchi=Math.cos(chi), schi=Math.sin(chi);
            double abx, aby, abz, aex, aey, aez, aux, auy, auz, ofx, ofy, ofz;
            if (skewF == 0.0) { abx=bx; aby=by; abz=bz; aex=ex; aey=ey; aez=ez; aux=ux; auy=uy; auz=uz; ofx=0; ofy=0; ofz=0; }
            else              { abx=cb0; aby=cb1; abz=cb2; aex=ce0; aey=ce1; aez=ce2; aux=cu0; auy=cu1; auz=cu2; ofx=of0; ofy=of1; ofz=of2; }
            double uBx=aux*cphi+abx*sphi, uBy=auy*cphi+aby*sphi, uBz=auz*cphi+abz*sphi;
            double Cx=Px2+ofx+uBx*lb, Cy=Py2+ofy+uBy*lb, Cz=Pz2+ofz+uBz*lb;
            double p1x = abx*rF8x + aux*rF8y, p1y = aby*rF8x + auy*rF8y, p1z = abz*rF8x + auz*rF8y;
            double pn = Math.sqrt(p1x*p1x + p1y*p1y + p1z*p1z); double ipn = 1.0/pn; p1x*=ipn; p1y*=ipn; p1z*=ipn;
            double q1x = aey*p1z - aez*p1y, q1y = aez*p1x - aex*p1z, q1z = aex*p1y - aey*p1x;
            double e0x = cpsi*p1x + spsi*q1x, e0y = cpsi*p1y + spsi*q1y, e0z = cpsi*p1z + spsi*q1z;
            double thx = e0y*aez - e0z*aey, thy = e0z*aex - e0x*aez, thz = e0x*aey - e0y*aex;
            double d0x = abx*(rF8x-rCx) + aux*(rF8y-rCy), d0y = aby*(rF8x-rCx) + auy*(rF8y-rCy), d0z = abz*(rF8x-rCx) + auz*(rF8y-rCy);
            double pd0x = d0x*cpsi + (aey*d0z - aez*d0y)*spsi, pd0y = d0y*cpsi + (aez*d0x - aex*d0z)*spsi, pd0z = d0z*cpsi + (aex*d0y - aey*d0x)*spsi;
            double rcx0 = abx*rCx + aux*rCy, rcy0 = aby*rCx + auy*rCy, rcz0 = abz*rCx + auz*rCy;
            double prcx = rcx0*cpsi + (aey*rcz0 - aez*rcy0)*spsi, prcy = rcy0*cpsi + (aez*rcx0 - aex*rcz0)*spsi, prcz = rcz0*cpsi + (aex*rcy0 - aey*rcx0)*spsi;
            double omc = 1.0 - cchi;
            double dd1 = thx*pd0x + thy*pd0y + thz*pd0z;
            double fcx = pd0x*cchi + (thy*pd0z - thz*pd0y)*schi + thx*dd1*omc;
            double fcy = pd0y*cchi + (thz*pd0x - thx*pd0z)*schi + thy*dd1*omc;
            double fcz = pd0z*cchi + (thx*pd0y - thy*pd0x)*schi + thz*dd1*omc;
            double dd2 = thx*prcx + thy*prcy + thz*prcz;
            double hcx = prcx*cchi + (thy*prcz - thz*prcy)*schi + thx*dd2*omc;
            double hcy = prcy*cchi + (thz*prcx - thx*prcz)*schi + thy*dd2*omc;
            double hcz = prcz*cchi + (thx*prcy - thy*prcx)*schi + thz*dd2*omc;
            outGeom.set(m,Cx); outGeom.set(nM+m,Cy); outGeom.set(2*nM+m,Cz);
            outGeom.set(3*nM+m,Cx-hcx); outGeom.set(4*nM+m,Cy-hcy); outGeom.set(5*nM+m,Cz-hcz);
            outGeom.set(6*nM+m,Cx+fcx); outGeom.set(7*nM+m,Cy+fcy); outGeom.set(8*nM+m,Cz+fcz);
            q.set(m,phi); q.set(nM+m,psi); chiHead.set(m,chi);
            forceDotFil.set(m, bnd ? bondData.get(dB + 12) : 0.0f);
            forceMag.set(m, bnd ? (float) Math.sqrt(f8x*f8x + f8y*f8y + f8z*f8z) : 0.0f);
        }
    }
}
