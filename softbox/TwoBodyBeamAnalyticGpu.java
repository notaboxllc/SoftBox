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
            int base = m * (n * W);   // per-item scratch = n*(n+1), M-generic (=210 at M=4 [L40], =420 at M=6 [L60])
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
