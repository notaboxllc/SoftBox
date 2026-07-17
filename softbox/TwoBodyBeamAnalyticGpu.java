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
            int base = m * SYS_STRIDE;
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

                // ---------- F8 / converter / bind block (E = eup axis, matches s2Solve) ----------
                int pB = 3*(M-1), iPhi = nF, iPsi = nF+1;
                double cpx=Cx-Px, cpy=Cy-Py, cpz=Cz-Pz, fcx=xF8x-Cx, fcy=xF8y-Cy, fcz=xF8z-Cz;
                double Jphix=uy*cpz-uz*cpy, Jphiy=uz*cpx-ux*cpz, Jphiz=ux*cpy-uy*cpx;
                double Jpsix=uy*fcz-uz*fcy, Jpsiy=uz*fcx-ux*fcz, Jpsiz=ux*fcy-uy*fcx;
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
                double QphiF8=(ux*caFx+uy*caFy+uz*caFz)*1e-6, QpsiF8=(ux*fcFx+uy*fcFy+uz*fcFz)*1e-6;
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

    // =============================================== beam geom (pre-solve) + explicit head placement (mat)
    /** Compute the beam-derived head geometry (C, xH, xF8) from the CURRENT beam state — the device analog of
     *  {@code geom2D} (pre-solve). Writes outGeom [0..2]=C, [3..5]=xH, [6..8]=xF8 (planar c·nM+m). Same
     *  closed form as matS2SolveStep's final block (validated). Lets placeHead + bondForces run BEFORE the solve. */
    public static void matBeamGeom(DoubleArray nodes, DoubleArray frame, DoubleArray params, DoubleArray q,
                                   IntArray counts, DoubleArray outGeom) {
        int nM = counts.get(0), M = counts.get(2);
        for (@Parallel int m = 0; m < nM; m++) {
            double bx=frame.get(m), by=frame.get(nM+m), bz=frame.get(2*nM+m);
            double ex=frame.get(3*nM+m), ey=frame.get(4*nM+m), ez=frame.get(5*nM+m);
            double ux=frame.get(6*nM+m), uy=frame.get(7*nM+m), uz=frame.get(8*nM+m);
            double lb=params.get(m), rF8x=params.get(nM+m), rF8y=params.get(2*nM+m), rCx=params.get(3*nM+m), rCy=params.get(4*nM+m);
            double phi=q.get(m), psi=q.get(nM+m);
            double Px=nodes.get((3*M)*nM+m), Py=nodes.get((3*M+1)*nM+m), Pz=nodes.get((3*M+2)*nM+m);
            double cphi=Math.cos(phi), sphi=Math.sin(phi);
            double uBx=ux*cphi+bx*sphi, uBy=uy*cphi+by*sphi, uBz=uz*cphi+bz*sphi;
            double Cx=Px+uBx*lb, Cy=Py+uBy*lb, Cz=Pz+uBz*lb;
            double d0x=bx*(rF8x-rCx)+ux*(rF8y-rCy), d0y=by*(rF8x-rCx)+uy*(rF8y-rCy), d0z=bz*(rF8x-rCx)+uz*(rF8y-rCy);
            double cpsi=Math.cos(psi), spsi=Math.sin(psi);
            double xF8x=Cx+(d0x*cpsi+(ey*d0z-ez*d0y)*spsi), xF8y=Cy+(d0y*cpsi+(ez*d0x-ex*d0z)*spsi), xF8z=Cz+(d0z*cpsi+(ex*d0y-ey*d0x)*spsi);
            double rcx=bx*rCx+ux*rCy, rcy=by*rCx+uy*rCy, rcz=bz*rCx+uz*rCy;
            double xHx=Cx-(rcx*cpsi+(ey*rcz-ez*rcy)*spsi), xHy=Cy-(rcy*cpsi+(ez*rcx-ex*rcz)*spsi), xHz=Cz-(rcz*cpsi+(ex*rcy-ey*rcx)*spsi);
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
     * <p>{@code matc}: [t, seed, brownOn]. {@code counts}: [nM, maxIt, M, _] (maxIt=1 for a single production step).
     * Layout identical to beamRelaxAnalytic (nodes/frame/q/params/sys/outGeom). STRIDE = 13 (CrossBridgeSystem).
     */
    public static void matS2SolveStep(DoubleArray nodes, DoubleArray frame, DoubleArray q, FloatArray bondData,
                                      IntArray boundSeg, DoubleArray params, DoubleArray sys, DoubleArray outGeom,
                                      FloatArray forceDotFil, FloatArray forceMag, IntArray matc, IntArray counts) {
        int nM = counts.get(0), maxIt = counts.get(1), M = counts.get(2);
        int STRIDE = 13;
        int nF = 3 * M, n = nF + 2, W = n + 1;
        double tol = 3e-7;
        long tt = matc.get(0), seed = matc.get(1); int brownOn = matc.get(2);
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
            double f8x = bnd ? bondData.get(dB) : 0.0, f8y = bnd ? bondData.get(dB + 1) : 0.0, f8z = bnd ? bondData.get(dB + 2) : 0.0;
            double phi = q.get(m), psi = q.get(nM + m), thetaS = q.get(2 * nM + m), psiActin = q.get(3 * nM + m);
            int base = m * SYS_STRIDE;
            int st = 0, itDone = maxIt;
            for (int it = 0; it < maxIt; it++) {
                for (int i = 0; i < n; i++) for (int j = 0; j < W; j++) sys.set(base + i * W + j, 0.0);
                double Px = nodes.get((3 * M) * nM + m), Py = nodes.get((3 * M + 1) * nM + m), Pz = nodes.get((3 * M + 2) * nM + m);
                double cphi = Math.cos(phi), sphi = Math.sin(phi);
                double uBx = ux * cphi + bx * sphi, uBy = uy * cphi + by * sphi, uBz = uz * cphi + bz * sphi;
                double Cx = Px + uBx * lb, Cy = Py + uBy * lb, Cz = Pz + uBz * lb;
                double d0x = bx * (rF8x - rCx) + ux * (rF8y - rCy), d0y = by * (rF8x - rCx) + uy * (rF8y - rCy), d0z = bz * (rF8x - rCx) + uz * (rF8y - rCy);
                double cpsi = Math.cos(psi), spsi = Math.sin(psi);
                double xF8x = Cx + (d0x * cpsi + (ey * d0z - ez * d0y) * spsi);
                double xF8y = Cy + (d0y * cpsi + (ez * d0x - ex * d0z) * spsi);
                double xF8z = Cz + (d0z * cpsi + (ex * d0y - ey * d0x) * spsi);
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
                if (brownOn != 0) for (int j=1;j<=M;j++){ int fb=(j-1)*3;
                    for (int k=0;k<3;k++){ long salt = 0x4811L + ((long)m*1009 + (long)j*131 + k)*7919L;
                        addF(sys,base,W,n, fb+k, brownTorqueD(gNode, dt, seed, tt, salt)); } }
                // F8 / converter / bind block
                int pB = 3*(M-1), iPhi = nF, iPsi = nF+1;
                double cpx=Cx-Px, cpy=Cy-Py, cpz=Cz-Pz, fcx=xF8x-Cx, fcy=xF8y-Cy, fcz=xF8z-Cz;
                double Jphix=uy*cpz-uz*cpy, Jphiy=uz*cpx-ux*cpz, Jphiz=ux*cpy-uy*cpx;
                double Jpsix=uy*fcz-uz*fcy, Jpsiy=uz*fcx-ux*fcz, Jpsiz=ux*fcy-uy*fcx;
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
                double QphiF8=(ux*caFx+uy*caFy+uz*caFz)*1e-6, QpsiF8=(ux*fcFx+uy*fcFy+uz*fcFz)*1e-6;
                addF(sys,base,W,n, pB+0, f8x); addF(sys,base,W,n, pB+1, f8y); addF(sys,base,W,n, pB+2, f8z);
                addF(sys,base,W,n, iPhi, QphiF8 + kc*(th2-thetaS));
                addF(sys,base,W,n, iPsi, QpsiF8 - kc*(th2-thetaS) - kbnd*(psi-psiActin));
                if (brownOn != 0) { addF(sys,base,W,n, iPhi, brownTorqueD(gPhi, dt, seed, tt, 0x4841L + (long)m*7919L));
                                    addF(sys,base,W,n, iPsi, brownTorqueD(gPsi, dt, seed, tt, 0x4842L + (long)m*7919L)); }
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
            forceDotFil.set(m, bnd ? bondData.get(dB + 12) : 0.0f);
            forceMag.set(m, bnd ? (float) Math.sqrt(f8x*f8x + f8y*f8y + f8z*f8z) : 0.0f);
        }
    }
}
