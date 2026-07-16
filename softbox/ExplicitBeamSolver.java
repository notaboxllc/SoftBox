package softbox;

import softbox.TwoBodyConverterMotor.Cmot;

/**
 * B1/B2 — CPU double-precision ANALYTIC Newton solver for the EXPLICIT_S2_L40 beam.
 *
 * <p>Structurally and semantically identical to the frozen {@link TwoBodyConverterMotor#s2Solve}
 * (single linearly-implicit Newton step per timestep; the {@code γ/dt·I + tangent} system; node-0 re-pin;
 * F8/converter/bind endpoint coupling with the frozen {@code E=eup} axis; identical RHS) — the ONLY change
 * is that the beam tangent is the exact analytic energy Hessian ({@link ExplicitBeamAnalytic#beamTangentFree})
 * instead of the nested central finite difference. The frozen {@code s2Solve} is NOT modified or removed; it
 * remains the authoritative oracle. This is a NEW file; the production default stays FD.
 *
 * <p><b>Selection mechanism.</b> {@code Mode} carries {@code tangent∈{FD,ANALYTIC}} and
 * {@code residual∈{FD,ANALYTIC}}. {@link #assemble} builds the (3M+2) system for any mode; {@link #s2SolveMode}
 * runs one Newton step. Per B1 the accepted "analytic solver" is {@code tangent=ANALYTIC, residual=FD}
 * (frozen residual + analytic Hessian ⇒ IDENTICAL fixed point to the FD path, only the tangent changes).
 * {@code Mode.FD_REPLICA} reproduces {@code s2Solve} bit-for-bit (validated in the gate harness). A fully
 * analytic mode (residual=ANALYTIC) is also provided for the eventual GPU path (removes ALL finite differences).
 *
 * <p><b>Linear solve (B2).</b> {@link #solveFlat} is a fixed-size, allocation-free Gauss–Jordan with partial
 * pivoting on a FLAT {@code double[n*(n+1)]} augmented buffer (no multidimensional local arrays), numerically
 * identical to the frozen {@code solveLin} (so a matching tangent ⇒ bit-identical increment). It adds explicit
 * singularity DETECTION (a deterministic failure flag when a pivot underflows) but NO ridge/regularization
 * beyond the node-drag diagonal {@code aN=γ/dt} the FD path already carries — valid states are bit-unaffected.
 *
 * <p>Units match the frozen code exactly (nodes µm; ks N/m, kb N·m, kfloor N/m; force N; tangent N/m; dq m).
 */
final class ExplicitBeamSolver {

    static final int NMAX=14;   // 3M+2 with M=4

    enum Src { FD, ANALYTIC }
    static final class Mode {
        final Src tangent, residual; Mode(Src t,Src r){ tangent=t; residual=r; }
        static final Mode FD_REPLICA     = new Mode(Src.FD, Src.FD);          // ≡ s2Solve (bit-identical)
        static final Mode ANALYTIC       = new Mode(Src.ANALYTIC, Src.FD);    // B1 accepted analytic solver
        static final Mode FULLY_ANALYTIC = new Mode(Src.ANALYTIC, Src.ANALYTIC);
    }

    /** Per-step diagnostics (for the gate harness). */
    static final class StepInfo {
        int status;                 // 0 = ok, 1 = singular (deterministic failure flag)
        double minAbsPivot;         // smallest |pivot| encountered (conditioning proxy)
        double energyBefore, energyAfter;   // total beam energy (SI J) pre/post step
        double[] dq;                // the Newton increment (m for nodes, rad for φ,ψ)
        double[] Msys;              // flat assembled tangent (n*n) — for B3 comparison
        double[] F;                 // assembled RHS (n)
        int n;
    }

    // =============================================================================================
    //  Assemble the (3M+2) system  Msys·dq = F  for the given mode (matches s2Solve's layout exactly).
    //  Requires cm.C, cm.P, cm.xF8, cm.phi, cm.psi current (caller does A=node[M]; geomC — as stepS2 does).
    // =============================================================================================
    static void assemble(Cmot cm,int t,int seed,boolean brownian,double[] F8h,Mode mode,double[] Mout,double[] Fout){
        int M=cm.g4M, nF=3*M, n=nF+2; double[] E=cm.eup;
        java.util.Arrays.fill(Mout,0,n*n,0.0); java.util.Arrays.fill(Fout,0,n,0.0);

        // ---- residual RHS on free nodes 1..M ----
        double[][] Fn = (mode.residual==Src.ANALYTIC)
                ? ExplicitBeamAnalytic.nodeForces(cm,cm.g4Node)          // fully-analytic residual
                : TwoBodyConverterMotor.s2NodeForces(cm,cm.g4Node);      // frozen residual (B1 default)
        for(int j=1;j<=M;j++) for(int k=0;k<3;k++) Fout[3*(j-1)+k]=Fn[j][k];

        // ---- beam tangent K = −∂F/∂q (N/m) over the free DOF ----
        if(mode.tangent==Src.ANALYTIC){
            double[][] K=ExplicitBeamAnalytic.beamTangentFree(cm,cm.g4Node);   // exact energy Hessian
            for(int r=0;r<nF;r++) for(int c=0;c<nF;c++) Mout[r*n+c]+=K[r][c];
        } else {
            // exact replica of s2Solve's nested central FD (:6365-6369) — mutates+restores g4Node
            double hh=1e-5;
            for(int jc=1;jc<=M;jc++) for(int kc=0;kc<3;kc++){ int col=3*(jc-1)+kc; double sav=cm.g4Node[jc][kc];
                cm.g4Node[jc][kc]=sav+hh; double[][] Fp=TwoBodyConverterMotor.s2NodeForces(cm,cm.g4Node);
                cm.g4Node[jc][kc]=sav-hh; double[][] Fm=TwoBodyConverterMotor.s2NodeForces(cm,cm.g4Node); cm.g4Node[jc][kc]=sav;
                for(int jr=1;jr<=M;jr++) for(int kr=0;kr<3;kr++) Mout[(3*(jr-1)+kr)*n+col] += -((Fp[jr][kr]-Fm[jr][kr])/(2*hh))*1e6; }
        }

        // ---- node drag (implicit) + Brownian on free nodes (identical to s2Solve :6371-6373) ----
        double aN=cm.g4gammaNode/cm.dt;
        for(int j=1;j<=M;j++){ int fb=j-1; for(int k=0;k<3;k++){ Mout[(3*fb+k)*n+(3*fb+k)]+=aN;
            if(brownian) Fout[3*fb+k]+=TwoBodyConverterMotor.brownTorque(cm.g4gammaNode,cm.dt,seed,t,0x4711L+((long)j*131+k)*7919L); } }

        // ---- F8 + converter/bind coupling (identical to s2Solve :6374-6392; E=eup axis) ----
        int pB=3*(M-1), iPhi=nF, iPsi=nF+1;
        double[] C=cm.C, xF8=cm.xF8;
        double[] Jphi=crs(E,sub(C,cm.P)), Jpsi=crs(E,sub(xF8,C));
        double[][] J={ {1,0,0, dot(Jphi,new double[]{1,0,0})*1e-6, dot(Jpsi,new double[]{1,0,0})*1e-6},
                       {0,1,0, dot(Jphi,new double[]{0,1,0})*1e-6, dot(Jpsi,new double[]{0,1,0})*1e-6},
                       {0,0,1, dot(Jphi,new double[]{0,0,1})*1e-6, dot(Jpsi,new double[]{0,0,1})*1e-6} };
        double kfSI=cm.kF8Code*1e6, kc=cm.kconvCode, kb=cm.kbindCode;
        int[] map={pB,pB+1,pB+2,iPhi,iPsi};
        for(int i=0;i<5;i++) for(int jj=0;jj<5;jj++){ double kij=kfSI*(J[0][i]*J[0][jj]+J[1][i]*J[1][jj]+J[2][i]*J[2][jj]);
            Mout[map[i]*n+map[jj]]+=kij; }
        Mout[iPhi*n+iPhi]+=kc; Mout[iPhi*n+iPsi]-=kc; Mout[iPsi*n+iPhi]-=kc; Mout[iPsi*n+iPsi]+=kc+kb;
        double aphi=cm.gammaPhi/cm.dt, apsi=cm.gammaPsi/cm.dt; Mout[iPhi*n+iPhi]+=aphi; Mout[iPsi*n+iPsi]+=apsi;
        double th=cm.psi-cm.phi;
        double QphiF8=dot(E,crs(sub(C,cm.P),F8h))*1e-6, QpsiF8=dot(E,crs(sub(xF8,C),F8h))*1e-6;
        Fout[pB]+=F8h[0]; Fout[pB+1]+=F8h[1]; Fout[pB+2]+=F8h[2];
        Fout[iPhi]+=QphiF8+kc*(th-cm.thetaS); Fout[iPsi]+=QpsiF8-kc*(th-cm.thetaS)-kb*(cm.psi-cm.psiActin);
        if(brownian){ Fout[iPhi]+=TwoBodyConverterMotor.brownTorque(cm.gammaPhi,cm.dt,seed,t,0x4741L);
                      Fout[iPsi]+=TwoBodyConverterMotor.brownTorque(cm.gammaPsi,cm.dt,seed,t,0x4742L); }
    }

    // =============================================================================================
    //  B2 — flat, allocation-free Gauss–Jordan (partial pivot) with singularity detection.
    //  aug is a caller-provided scratch of length n*(n+1); returns 0 ok / 1 singular. dq filled (length n).
    //  Numerically identical arithmetic to the frozen solveLin (:5002-5009), just flat storage.
    // =============================================================================================
    static int solveFlat(double[] Mflat,double[] F,int n,double[] aug,double[] dq,double[] minPivOut){
        int W=n+1;
        for(int i=0;i<n;i++){ for(int c=0;c<n;c++) aug[i*W+c]=Mflat[i*n+c]; aug[i*W+n]=F[i]; }
        double minPiv=Double.MAX_VALUE; int status=0;
        for(int c=0;c<n;c++){
            int p=c; double best=Math.abs(aug[c*W+c]);
            for(int r=c+1;r<n;r++){ double v=Math.abs(aug[r*W+c]); if(v>best){ best=v; p=r; } }
            if(p!=c){ for(int k=0;k<W;k++){ double tmp=aug[c*W+k]; aug[c*W+k]=aug[p*W+k]; aug[p*W+k]=tmp; } }
            double piv=aug[c*W+c]; double apiv=Math.abs(piv);
            if(apiv<minPiv) minPiv=apiv;
            if(!(apiv>1e-300)){ status=1; }   // deterministic singularity flag (no ridge added)
            for(int r=0;r<n;r++){ if(r==c) continue; double fac=aug[r*W+c]/piv;
                for(int k=c;k<W;k++) aug[r*W+k]-=fac*aug[c*W+k]; }
        }
        for(int i=0;i<n;i++) dq[i]=aug[i*W+n]/aug[i*W+i];
        if(minPivOut!=null) minPivOut[0]=minPiv;
        return status;
    }

    // =============================================================================================
    //  One Newton step in the given mode. Applies the increment + node-0 re-pin + geomC (s2Solve tail).
    // =============================================================================================
    static StepInfo s2SolveMode(Cmot cm,int t,int seed,boolean brownian,double[] F8h,Mode mode,
                                double[] Mscr,double[] Fscr,double[] augScr,double[] dqScr){
        int M=cm.g4M, nF=3*M, n=nF+2;
        StepInfo si=new StepInfo(); si.n=n;
        si.energyBefore=ExplicitBeamAnalytic.totalEnergy(cm,cm.g4Node);
        assemble(cm,t,seed,brownian,F8h,mode,Mscr,Fscr);
        double[] minPiv=new double[1];
        si.status=solveFlat(Mscr,Fscr,n,augScr,dqScr,minPiv);
        si.minAbsPivot=minPiv[0];
        // apply increment (identical to s2Solve :6394-6397)
        for(int j=1;j<=M;j++){ int fb=j-1; for(int k=0;k<3;k++) cm.g4Node[j][k]+=dqScr[3*fb+k]*1e6; }
        cm.phi+=dqScr[nF]; cm.psi+=dqScr[nF+1];
        cm.g4Node[0]=cm.g4E.clone();
        cm.A=cm.g4Node[M]; cm.P=cm.A; TwoBodyConverterMotor.geomC(cm);
        si.energyAfter=ExplicitBeamAnalytic.totalEnergy(cm,cm.g4Node);
        si.dq=java.util.Arrays.copyOf(dqScr,n); si.Msys=java.util.Arrays.copyOf(Mscr,n*n); si.F=java.util.Arrays.copyOf(Fscr,n);
        return si;
    }
    /** Convenience: the B1 accepted analytic solver (frozen residual + analytic tangent). */
    static StepInfo s2SolveAnalytic(Cmot cm,int t,int seed,boolean brownian,double[] F8h,
                                    double[] Mscr,double[] Fscr,double[] augScr,double[] dqScr){
        return s2SolveMode(cm,t,seed,brownian,F8h,Mode.ANALYTIC,Mscr,Fscr,augScr,dqScr);
    }

    // Reusable scratch for a single motor (no per-step allocation).
    static final class Scratch {
        final double[] M=new double[NMAX*NMAX], F=new double[NMAX], aug=new double[NMAX*(NMAX+1)], dq=new double[NMAX];
    }

    // =============================================================================================
    //  Full-step replica of stepS2 (so a dynamic run can use the analytic solver WITHOUT touching
    //  existing code). Filament handling / bond / gather / traps / integrate / derive are IDENTICAL —
    //  the only swap is s2Solve → s2SolveMode. Returns the StepInfo of the beam solve.
    // =============================================================================================
    static StepInfo stepS2Mode(Cmot cm,int t,int seed,boolean brownian,Mode mode,Scratch s){
        FilamentStore f=cm.fil; MotorStore mot=cm.mot; RigidRodBody b=mot.body;
        mot.setCounts(t,seed,f.n); f.counts.set(1,t); f.counts.set(2,seed);
        cm.A=cm.g4Node[cm.g4M]; cm.P=cm.A; TwoBodyConverterMotor.geomC(cm); TwoBodyConverterMotor.placeHead3c(cm);
        CrossBridgeSystem.bondForces(b.coord,b.uVec,b.yVec,b.bRotGam, f.coord,f.uVec,f.yVec,f.bRotGam,f.segLength,
                mot.boundSeg,mot.bindArc,mot.nucleotideState, cm.bondData, cm.xbParams);
        ChainBendingForceSystem.zeroAccumulators(f.forceSum,f.torqueSum,f.counts);
        CrossBridgeSystem.csrHistogram(mot.boundSeg,mot.counts,cm.segMotorCount);
        CrossBridgeSystem.csrScan(mot.counts,cm.segMotorCount,cm.segMotorOffsets);
        CrossBridgeSystem.csrScatter(mot.boundSeg,mot.counts,cm.segMotorOffsets,cm.segMotorCount,cm.segMotorMyo);
        CrossBridgeSystem.segGather(cm.segMotorOffsets,cm.segMotorMyo,cm.bondData,f.forceSum,f.torqueSum,mot.counts);
        LaserTrapSystem.applyTraps3D(f.coord,f.uVec,f.segLength,cm.x0L,cm.x0R,f.forceSum,f.torqueSum,cm.trapParams,f.counts);
        RigidRodLangevinIntegrationSystem.integrate(f.coord,f.uVec,f.yVec,f.forceSum,f.torqueSum,f.randForce,f.randTorque,f.bTransGam,f.bRotGam,f.params,f.counts);
        DerivedGeometrySystem.orthogonalizeY(f.uVec,f.yVec,f.counts);
        if(cm.filFullClamp){ f.setCoord(0,(float)cm.clampCoord[0],(float)cm.clampCoord[1],(float)cm.clampCoord[2]);
            f.setUVec(0,(float)cm.clampU[0],(float)cm.clampU[1],(float)cm.clampU[2]); f.setYVec(0,(float)cm.clampY[0],(float)cm.clampY[1],(float)cm.clampY[2]); }
        DerivedGeometrySystem.derive(f.coord,f.uVec,f.yVec,f.zVec,f.end1,f.end2,f.segLength,f.counts);
        double[] F8h={cm.bondData.get(0),cm.bondData.get(1),cm.bondData.get(2)};
        return s2SolveMode(cm,t,seed,brownian,F8h,mode,s.M,s.F,s.aug,s.dq);
    }

    // ---- helpers ----
    private static double dot(double[] a,double[] b){ return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    private static double[] sub(double[] a,double[] b){ return new double[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]}; }
    private static double[] crs(double[] a,double[] b){ return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]}; }
}
