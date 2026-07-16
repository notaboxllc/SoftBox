package softbox;

import softbox.TwoBodyConverterMotor.Cmot;

/**
 * B3/B4 — EXACT analytic residual (internal node force) + Jacobian (tangent stiffness = energy Hessian)
 * for the EXPLICIT_S2_L40 fixed-contour beam, in DOUBLE precision.
 *
 * <p>This class computes, in CLOSED FORM, the SAME beam energy / force / tangent that the frozen
 * finite-difference solver in {@link TwoBodyConverterMotor} evaluates numerically:
 * <ul>
 *   <li>{@link TwoBodyConverterMotor#s2BendEnergy}   — bending energy E = Σ ½ kb θ²  (θ = acos joint angle)
 *   <li>{@link TwoBodyConverterMotor#s2NodeForces}   — stretch (analytic) + bending (central FD of E) + floor
 *   <li>{@link TwoBodyConverterMotor#s2Solve}         — the (3M+2) system whose beam tangent is a nested central FD.
 * </ul>
 * NOTHING here changes the physical model. All constants (g4ks, g4kb, g4l0, g4kfloor, g4floorZ, eup,
 * g4Tan, g4M) are READ from a Cmot built by {@link TwoBodyConverterMotor#buildS2} — i.e. the frozen
 * MotorModel/EXP4G parameters — they are NOT re-hardcoded here.
 *
 * <p><b>Units (identical to the frozen code).</b> Node coordinates {@code nd[j]} are in µm (world).
 * Stiffnesses are SI: {@code g4ks} in N/m, {@code g4kb} in N·m, {@code g4kfloor} in N/m. Returned
 * FORCES are in N. Returned TANGENT (Hessian) blocks are in N/m (i.e. ∂²E/∂x_m², coordinates in metres).
 * A length in µm converts to m by ×1e-6; a per-µm derivative converts to per-m by ×1e6.
 *
 * <p><b>Sign conventions.</b> The internal node force is F = −∂E/∂x_m (restoring). The Newton/backward-Euler
 * tangent assembled in {@code s2Solve} is K = −∂F/∂x_m = ∂²E/∂x_m² = the energy Hessian (positive-definite
 * away from buckling / floor kinks). So the analytic tangent IS the energy Hessian.
 *
 * <p><b>Small-angle / singularity handling.</b> The gradient/Hessian of ½kbθ² with θ=acos(c) contain the
 * factors A1(θ)=θ/sinθ and A2(θ)=(1−θ·cosθ/sinθ)/sin²θ. Both are FINITE as θ→0 (A1→1, A2→1/3) but are
 * 0/0 in that limit and A2 suffers catastrophic cancellation for small θ. They are evaluated from a Taylor
 * series below a threshold and directly above it. The gradient of the SCALAR c = (a·b)/(la·lb) is a rational
 * function (no transcendental) and is exact for all non-degenerate segments; the whole angle dependence is
 * carried by the two well-behaved scalars A1, A2. This is provably the exact gradient/Hessian of the SAME
 * ½kbθ² energy (see docs/explicit_jac/ANALYTIC_JACOBIAN_DERIVATION.md). The genuine singularities are θ→π
 * (a 180° fold, sinθ→0 with θ≠0 — a real cusp of ½kbθ²) and the floor kink at z=g4floorZ (C¹); neither is
 * reached by a valid stiff-beam pose.
 */
final class ExplicitBeamAnalytic {

    // ---- scalar angle factors (finite at θ→0) --------------------------------------------------
    /** A1(θ) = θ/sinθ. Direct for θ away from 0; series 1 + θ²/6 + 7θ⁴/360 near 0. */
    static double a1(double th){
        double t2=th*th;
        if(th<1e-3) return 1.0 + t2/6.0 + 7.0*t2*t2/360.0;
        return th/Math.sin(th);
    }
    /** A2(θ) = (1 − θ·cotθ)/sin²θ. Direct away from 0; series 1/3 + (2/15)θ² + (2/63)θ⁴ near 0
     *  (the numerator 1−θcotθ = θ²/3+θ⁴/45+… cancels catastrophically against 1 for small θ). */
    static double a2(double th){
        if(th<5e-2){ double t2=th*th; return 1.0/3.0 + (2.0/15.0)*t2 + (2.0/63.0)*t2*t2; }
        double s=Math.sin(th);
        return (1.0 - th*Math.cos(th)/s)/(s*s);
    }

    // ---- tiny vector helpers (double, self-contained) ------------------------------------------
    private static double dot(double[] a,double[] b){ return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    private static double clamp1(double x){ return x<-1?-1:(x>1?1:x); }

    // ===========================================================================================
    //  ENERGY (for the FD oracle + the equivalence proof) — MUST equal the frozen decomposition.
    // ===========================================================================================
    /** Stretch energy E = Σ ½ ks (len_m − l0_m)²  (SI J). Its gradient is the frozen analytic stretch force. */
    static double stretchEnergy(Cmot cm,double[][] nd){
        int M=cm.g4M; double ks=cm.g4ks, l0m=cm.g4l0*1e-6, E=0;
        for(int i=0;i<M;i++){ double[] b={nd[i+1][0]-nd[i][0],nd[i+1][1]-nd[i][1],nd[i+1][2]-nd[i][2]};
            double len=Math.sqrt(dot(b,b)); if(len<1e-15) continue; double d=len*1e-6-l0m; E+=0.5*ks*d*d; }
        return E;
    }
    /** Bending energy — EXACT replica of {@link TwoBodyConverterMotor#s2BendEnergy} (the oracle also calls
     *  the frozen method directly; this replica documents the analytic energy being differentiated). */
    static double bendEnergy(Cmot cm,double[][] nd){
        int M=cm.g4M; double kb=cm.g4kb, E=0;
        double[] b0={nd[1][0]-nd[0][0],nd[1][1]-nd[0][1],nd[1][2]-nd[0][2]}; double l0=Math.sqrt(dot(b0,b0));
        if(l0>1e-12){ double c=clamp1(dot(cm.g4Tan,b0)/l0); double th=Math.acos(c); E+=0.5*kb*th*th; }
        for(int j=1;j<M;j++){ double[] a={nd[j][0]-nd[j-1][0],nd[j][1]-nd[j-1][1],nd[j][2]-nd[j-1][2]};
            double[] b={nd[j+1][0]-nd[j][0],nd[j+1][1]-nd[j][1],nd[j+1][2]-nd[j][2]};
            double la=Math.sqrt(dot(a,a)), lb=Math.sqrt(dot(b,b)); if(la<1e-12||lb<1e-12) continue;
            double c=clamp1(dot(a,b)/(la*lb)); double th=Math.acos(c); E+=0.5*kb*th*th; }
        return E;
    }
    /** Substrate floor penalty energy E = Σ ½ kfloor·pen²  (one-sided, pen=(floorZ−z)_m for z<floorZ). */
    static double floorEnergy(Cmot cm,double[][] nd){
        int M=cm.g4M; double E=0; for(int j=0;j<=M;j++){ double z=dot(nd[j],cm.eup);
            if(z<cm.g4floorZ){ double pen=(cm.g4floorZ-z)*1e-6; E+=0.5*cm.g4kfloor*pen*pen; } }
        return E;
    }
    static double totalEnergy(Cmot cm,double[][] nd){ return stretchEnergy(cm,nd)+bendEnergy(cm,nd)+floorEnergy(cm,nd); }

    // ===========================================================================================
    //  ANALYTIC INTERNAL NODE FORCE  F[0..M]  (SI N) — the residual RHS. Same layout as s2NodeForces.
    //  block ∈ {"stretch","bend","floor","total"} selects a sub-block for per-block validation.
    // ===========================================================================================
    static double[][] nodeForces(Cmot cm,double[][] nd,String block){
        int M=cm.g4M; double[][] F=new double[M+1][3];
        boolean all=block.equals("total");
        if(all||block.equals("stretch")) addStretchForce(cm,nd,F);
        if(all||block.equals("bend"))    addBendForce(cm,nd,F);
        if(all||block.equals("floor"))   addFloorForce(cm,nd,F);
        return F;
    }
    static double[][] nodeForces(Cmot cm,double[][] nd){ return nodeForces(cm,nd,"total"); }

    private static void addStretchForce(Cmot cm,double[][] nd,double[][] F){
        int M=cm.g4M; double ks=cm.g4ks, l0m=cm.g4l0*1e-6;
        for(int i=0;i<M;i++){ double[] b={nd[i+1][0]-nd[i][0],nd[i+1][1]-nd[i][1],nd[i+1][2]-nd[i][2]};
            double len=Math.sqrt(dot(b,b)); if(len<1e-15) continue;
            double f=ks*(len*1e-6-l0m); double s=1.0/len;
            for(int k=0;k<3;k++){ double uk=b[k]*s; F[i][k]+=f*uk; F[i+1][k]-=f*uk; } }
    }
    private static void addFloorForce(Cmot cm,double[][] nd,double[][] F){
        int M=cm.g4M; for(int j=0;j<=M;j++){ double z=dot(nd[j],cm.eup);
            if(z<cm.g4floorZ){ double fk=cm.g4kfloor*(cm.g4floorZ-z)*1e-6; for(int k=0;k<3;k++) F[j][k]+=fk*cm.eup[k]; } }
    }
    private static void addBendForce(Cmot cm,double[][] nd,double[][] F){
        int M=cm.g4M; double kb=cm.g4kb;
        // clamped joint 0 (fixed tangent g4Tan vs bond b0=nd1−nd0); only node 1 free
        {
            double[] b0={nd[1][0]-nd[0][0],nd[1][1]-nd[0][1],nd[1][2]-nd[0][2]}; double lb=Math.sqrt(dot(b0,b0));
            if(lb>1e-12){ double[] t=cm.g4Tan; double c=clamp1(dot(t,b0)/lb); double th=Math.acos(c); double A1=a1(th);
                // ∂c/∂b0 = t/lb − c·b0/lb²   (per µm)
                double invlb=1.0/lb, c_lb2=c*invlb*invlb;
                for(int k=0;k<3;k++){ double gc=t[k]*invlb - c_lb2*b0[k];  F[1][k]+=kb*A1*1e6*gc; } }
        }
        for(int j=1;j<M;j++){
            double[] a={nd[j][0]-nd[j-1][0],nd[j][1]-nd[j-1][1],nd[j][2]-nd[j-1][2]};
            double[] b={nd[j+1][0]-nd[j][0],nd[j+1][1]-nd[j][1],nd[j+1][2]-nd[j][2]};
            double la=Math.sqrt(dot(a,a)), lb=Math.sqrt(dot(b,b)); if(la<1e-12||lb<1e-12) continue;
            double invla=1.0/la, invlb=1.0/lb; double c=clamp1(dot(a,b)*invla*invlb); double th=Math.acos(c); double A1=a1(th);
            double[] D=new double[3], Ee=new double[3];   // ∂c/∂a , ∂c/∂b (per µm)
            double c_la2=c*invla*invla, c_lb2=c*invlb*invlb, iab=invla*invlb;
            for(int k=0;k<3;k++){ D[k]=b[k]*iab - c_la2*a[k]; Ee[k]=a[k]*iab - c_lb2*b[k]; }
            // node gradients of c: node j−1 = −D ; node j = D−E ; node j+1 = E
            for(int k=0;k<3;k++){
                F[j-1][k]+= kb*A1*1e6*(-D[k]);
                F[j][k]  += kb*A1*1e6*( D[k]-Ee[k]);
                F[j+1][k]+= kb*A1*1e6*( Ee[k]);
            }
        }
    }

    // ===========================================================================================
    //  ANALYTIC BEAM TANGENT (energy Hessian K = ∂²E/∂x_m²) over the FREE DOF (nodes 1..M).
    //  Returns a (3M)×(3M) matrix in N/m, indexed [3(jr−1)+kr][3(jc−1)+kc] — EXACTLY the block the
    //  frozen s2Solve builds by nested central FD. block selects stretch/bend/floor/total.
    // ===========================================================================================
    static double[][] beamTangentFree(Cmot cm,double[][] nd,String block){
        int M=cm.g4M; int nF=3*M;
        double[][] Hfull=new double[3*(M+1)][3*(M+1)];   // Hessian over ALL nodes 0..M
        boolean all=block.equals("total");
        if(all||block.equals("stretch")) addStretchHess(cm,nd,Hfull);
        if(all||block.equals("bend"))    addBendHess(cm,nd,Hfull);
        if(all||block.equals("floor"))   addFloorHess(cm,nd,Hfull);
        double[][] K=new double[nF][nF];   // free-free block (nodes 1..M), node 0 dropped (fixed)
        for(int r=0;r<nF;r++) for(int col=0;col<nF;col++) K[r][col]=Hfull[3+r][3+col];
        return K;
    }
    static double[][] beamTangentFree(Cmot cm,double[][] nd){ return beamTangentFree(cm,nd,"total"); }

    /** scatter a 3×3 block B (N/m) into Hfull at node-block (αNode,βNode). */
    private static void scatter(double[][] H,int aN,int bN,double[][] B){
        int ao=3*aN, bo=3*bN; for(int p=0;p<3;p++) for(int q=0;q<3;q++) H[ao+p][bo+q]+=B[p][q];
    }

    private static void addStretchHess(Cmot cm,double[][] nd,double[][] H){
        int M=cm.g4M; double ks=cm.g4ks, l0m=cm.g4l0*1e-6;
        for(int i=0;i<M;i++){ double[] b={nd[i+1][0]-nd[i][0],nd[i+1][1]-nd[i][1],nd[i+1][2]-nd[i][2]};
            double len=Math.sqrt(dot(b,b)); if(len<1e-15) continue; double lenM=len*1e-6; double s=1.0/len;
            double[] u={b[0]*s,b[1]*s,b[2]*s}; double t=ks*(lenM-l0m);   // tension (N)
            double toverL=t/lenM;                                        // geometric stiffness (N/m)
            double[][] Kb=new double[3][3];
            for(int p=0;p<3;p++) for(int q=0;q<3;q++){ double uu=u[p]*u[q]; double id=(p==q)?1.0:0.0;
                Kb[p][q]= ks*uu + toverL*(id-uu); }
            double[][] nKb=new double[3][3]; for(int p=0;p<3;p++) for(int q=0;q<3;q++) nKb[p][q]=-Kb[p][q];
            scatter(H,i,i,Kb); scatter(H,i+1,i+1,Kb); scatter(H,i,i+1,nKb); scatter(H,i+1,i,nKb);
        }
    }
    private static void addFloorHess(Cmot cm,double[][] nd,double[][] H){
        int M=cm.g4M; double[] e=cm.eup;
        for(int j=0;j<=M;j++){ double z=dot(nd[j],e); if(z<cm.g4floorZ){ double[][] B=new double[3][3];
            for(int p=0;p<3;p++) for(int q=0;q<3;q++) B[p][q]=cm.g4kfloor*e[p]*e[q]; scatter(H,j,j,B); } }
    }
    /** Bending Hessian. For each joint: Hess E = kb·A2·∇c⊗∇c − kb·A1·∇²c, evaluated in N/m
     *  (∇c per-µm ⇒ ×1e12 to reach per-m²). ∇c, ∇²c are the exact algebraic derivatives of c=(a·b)/(la·lb). */
    private static void addBendHess(Cmot cm,double[][] nd,double[][] H){
        int M=cm.g4M; double kb=cm.g4kb;
        // ---- clamped joint 0: only node 1 (t=g4Tan fixed unit, la→1) ----
        {
            double[] b0={nd[1][0]-nd[0][0],nd[1][1]-nd[0][1],nd[1][2]-nd[0][2]}; double lb=Math.sqrt(dot(b0,b0));
            if(lb>1e-12){ double[] t=cm.g4Tan; double invlb=1.0/lb; double c=clamp1(dot(t,b0)*invlb);
                double th=Math.acos(c); double A1=a1(th), A2=a2(th);
                double c_lb2=c*invlb*invlb;
                double[] gc=new double[3]; for(int k=0;k<3;k++) gc[k]=t[k]*invlb - c_lb2*b0[k];   // ∂c/∂b0
                // ∂²c/∂b0²  = −(b0⊗t + t⊗b0)/lb³ − c/lb² I + 3c b0⊗b0/lb⁴
                double ilb2=invlb*invlb, ilb3=ilb2*invlb, ilb4=ilb2*ilb2;
                double[][] Hc=new double[3][3];
                for(int p=0;p<3;p++) for(int q=0;q<3;q++){ double id=(p==q)?1.0:0.0;
                    Hc[p][q]= -(b0[p]*t[q]+t[p]*b0[q])*ilb3 - c*id*ilb2 + 3.0*c*b0[p]*b0[q]*ilb4; }
                double[][] B=new double[3][3];
                for(int p=0;p<3;p++) for(int q=0;q<3;q++) B[p][q]=1e12*kb*(A2*gc[p]*gc[q] - A1*Hc[p][q]);
                scatter(H,1,1,B);
            }
        }
        // ---- interior joints 1..M−1 (nodes j−1,j,j+1) ----
        for(int j=1;j<M;j++){
            double[] a={nd[j][0]-nd[j-1][0],nd[j][1]-nd[j-1][1],nd[j][2]-nd[j-1][2]};
            double[] b={nd[j+1][0]-nd[j][0],nd[j+1][1]-nd[j][1],nd[j+1][2]-nd[j][2]};
            double la=Math.sqrt(dot(a,a)), lb=Math.sqrt(dot(b,b)); if(la<1e-12||lb<1e-12) continue;
            double invla=1.0/la, invlb=1.0/lb; double c=clamp1(dot(a,b)*invla*invlb);
            double th=Math.acos(c); double A1=a1(th), A2=a2(th);
            double c_la2=c*invla*invla, c_lb2=c*invlb*invlb, iab=invla*invlb;
            double[] D=new double[3], Ee=new double[3];   // ∂c/∂a , ∂c/∂b
            for(int k=0;k<3;k++){ D[k]=b[k]*iab - c_la2*a[k]; Ee[k]=a[k]*iab - c_lb2*b[k]; }
            // second derivatives of c w.r.t (a,b): Haa, Hbb, Hab  (Hab_{pq}=∂²c/∂a_p∂b_q)
            double ila2=invla*invla, ila3=ila2*invla, ila4=ila2*ila2;
            double ilb2=invlb*invlb, ilb3=ilb2*invlb, ilb4=ilb2*ilb2;
            double[][] Haa=new double[3][3], Hbb=new double[3][3], Hab=new double[3][3];
            for(int p=0;p<3;p++) for(int q=0;q<3;q++){ double id=(p==q)?1.0:0.0;
                Haa[p][q]= -(a[p]*b[q]+b[p]*a[q])*ila3*invlb - c*id*ila2 + 3.0*c*a[p]*a[q]*ila4;
                Hbb[p][q]= -(b[p]*a[q]+a[p]*b[q])*ilb3*invla - c*id*ilb2 + 3.0*c*b[p]*b[q]*ilb4;
                Hab[p][q]=  id*iab - b[p]*b[q]*invla*ilb3 - a[p]*a[q]*ila3*invlb + c*a[p]*b[q]*ila2*ilb2; }
            // node gradients of c and incidence (sa,sb): node j−1 (−1,0), node j (+1,−1), node j+1 (0,+1)
            int[] nodes={j-1,j,j+1}; int[] sa={-1,1,0}, sb={0,-1,1};
            double[][] gcN=new double[3][3];   // ∇c per node (rows: node index 0..2, cols: xyz)
            for(int k=0;k<3;k++){ gcN[0][k]=-D[k]; gcN[1][k]=D[k]-Ee[k]; gcN[2][k]=Ee[k]; }
            for(int ai=0;ai<3;ai++) for(int bi=0;bi<3;bi++){
                double[][] Hc=new double[3][3];   // node-block Hessian of c
                for(int p=0;p<3;p++) for(int q=0;q<3;q++){
                    double v = sa[ai]*sa[bi]*Haa[p][q] + sb[ai]*sb[bi]*Hbb[p][q]
                             + sa[ai]*sb[bi]*Hab[p][q] + sb[ai]*sa[bi]*Hab[q][p];   // Hab^T for (b,a)
                    Hc[p][q]=v; }
                double[][] B=new double[3][3];
                for(int p=0;p<3;p++) for(int q=0;q<3;q++)
                    B[p][q]=1e12*kb*(A2*gcN[ai][p]*gcN[bi][q] - A1*Hc[p][q]);
                scatter(H,nodes[ai],nodes[bi],B);
            }
        }
    }

    // ===========================================================================================
    //  F8 / converter / bind ENDPOINT COUPLING — reproduced VERBATIM from s2Solve (already closed
    //  form; NOT the FD bottleneck). Returned so the full assembled system can be A/B'd, and so the
    //  geometric Jacobian (Jphi,Jpsi) + generalized F8 torques can be validated against geomC FD.
    //  cm must have current C,P,xF8 (call geomC first). Returns {J (3×5), QphiF8, QpsiF8}.
    // ===========================================================================================
    static double[] geomJacobianColPhi(Cmot cm){ double[] E=cm.eup; return crs(E,sub(cm.C,cm.P)); }
    static double[] geomJacobianColPsi(Cmot cm){ double[] E=cm.eup; return crs(E,sub(cm.xF8,cm.C)); }
    static double genForcePhi(Cmot cm,double[] F8h){ return dot(cm.eup,crs(sub(cm.C,cm.P),F8h))*1e-6; }
    static double genForcePsi(Cmot cm,double[] F8h){ return dot(cm.eup,crs(sub(cm.xF8,cm.C),F8h))*1e-6; }
    private static double[] sub(double[] a,double[] b){ return new double[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]}; }
    private static double[] crs(double[] a,double[] b){ return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]}; }
}
