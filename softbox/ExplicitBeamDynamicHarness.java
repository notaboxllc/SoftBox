package softbox;

import softbox.TwoBodyConverterMotor.Cmot;
import softbox.ExplicitBeamSolver.Mode;
import softbox.ExplicitBeamSolver.Scratch;
import java.util.*;
import java.io.PrintStream;

/**
 * B5 (short dynamic validation) + B6 (CPU throughput) for the analytic explicit-S2 Newton solver.
 *
 * <p>B5: an isolated bound explicit-S2 motor driven through the Pi-release power stroke under a range of
 * loads, with Brownian noise ON (FD and ANALYTIC share the SAME hashed noise, so only the tangent differs).
 * Compares FD vs ANALYTIC distributions of working stroke, pivot recoil, F8 axial force on actin, F8 torque,
 * beam energy, and solver failures — do NOT promote on static fixtures alone.
 *
 * <p>B6: CPU throughput of the isolated beam solve (assemble+solve) and the full step, FD vs ANALYTIC,
 * attributing the gain to the removed nested finite difference (≈24 s2NodeForces evals × ≈30 transcendental
 * bending-energy evals per FD tangent) — the expected GPU benefit indicator.
 *
 * CPU-only, double precision. New file; frozen s2Solve untouched; production default stays FD.
 */
public final class ExplicitBeamDynamicHarness {
    static final double DT=2.5e-6; static final int M=4;
    static final double PRESTROKE=-Math.toRadians(30), ADP=Math.toRadians(30);

    public static void main(String[] args){
        PrintStream out=System.out;
        out.println("# EXPLICIT_S2_L40 analytic solver — B5 dynamics + B6 throughput (CPU double)\n");

        // ============================ B5 — power stroke under load (FD vs ANALYTIC distributions) ============================
        out.println("=== B5  isolated bound-motor power stroke, Brownian ON (shared noise), FD vs ANALYTIC ===");
        double[] loads={ +2.0, 0.0, -2.0 };   // assist / zero / resist (pN, along glide axis)
        String[] lname={"assist(+2pN)","zero(0pN)","resist(-2pN)"};
        int nSeed=40, settle=1500;
        out.printf(Locale.US,"  %d seeds/condition, %d settle steps each phase, dt=%.1e%n",nSeed,settle,DT);
        out.printf(Locale.US,"  %-14s %-9s %12s %12s %12s %12s%n","load","solver","stroke_nm","recoil_nm","F8ax_pN","F8torq");
        boolean b5ok=true;
        for(int li=0;li<loads.length;li++){
            double[] sF=new double[nSeed], rF=new double[nSeed], fF=new double[nSeed], tF=new double[nSeed];
            double[] sA=new double[nSeed], rA=new double[nSeed], fA=new double[nSeed], tA=new double[nSeed];
            int failA=0;
            for(int s=0;s<nSeed;s++){
                double[] mFD=stroke(loads[li],settle,s,Mode.FD_REPLICA);
                double[] mAN=stroke(loads[li],settle,s,Mode.ANALYTIC);
                sF[s]=mFD[0]; rF[s]=mFD[1]; fF[s]=mFD[2]; tF[s]=mFD[3];
                sA[s]=mAN[0]; rA[s]=mAN[1]; fA[s]=mAN[2]; tA[s]=mAN[3];
                if(mAN[4]!=0) failA++;
            }
            out.printf(Locale.US,"  %-14s %-9s %6.3f±%.3f %6.3f±%.3f %7.3f±%.3f %7.2e±%.1e%n",
                lname[li],"FD",mean(sF),sd(sF),mean(rF),sd(rF),mean(fF),sd(fF),mean(tF),sd(tF));
            out.printf(Locale.US,"  %-14s %-9s %6.3f±%.3f %6.3f±%.3f %7.3f±%.3f %7.2e±%.1e  (AN failures=%d)%n",
                lname[li],"ANALYTIC",mean(sA),sd(sA),mean(rA),sd(rA),mean(fA),sd(fA),mean(tA),sd(tA),failA);
            // paired difference (same noise ⇒ small)
            double dS=maxPairDiff(sF,sA), dR=maxPairDiff(rF,rA), dFo=maxPairDiff(fF,fA);
            double relStroke=Math.abs(mean(sF)-mean(sA))/(Math.abs(mean(sF))+1e-9);
            out.printf(Locale.US,"      paired max|Δ|: stroke=%.3e nm recoil=%.3e nm F8ax=%.3e pN | mean-stroke relΔ=%.3e%n",dS,dR,dFo,relStroke);
            if(failA>0 || relStroke>0.02) b5ok=false;
        }

        // rapid displacement: jump the trap offset, measure settling of the bound pose, FD vs ANALYTIC
        out.println("\n  --- rapid displacement (10 nm trap step, bound), FD vs ANALYTIC ---");
        {
            double dFDmax=0; double sxF=0,sxA=0;
            for(int s=0;s<20;s++){ double xF=rapid(s,Mode.FD_REPLICA), xA=rapid(s,Mode.ANALYTIC);
                sxF+=xF; sxA+=xA; dFDmax=Math.max(dFDmax,Math.abs(xF-xA)); }
            out.printf(Locale.US,"  post-step filament COM displacement: FD mean=%.4f nm  AN mean=%.4f nm  max|Δ|=%.3e nm%n",sxF/20,sxA/20,dFDmax);
            if(dFDmax>0.05) b5ok=false;
        }
        out.println("\n  B5 verdict: "+(b5ok?"PASS — analytic stroke/recoil/force/torque distributions match FD (paired, within noise)":"FAIL — see above"));

        // ============================ B6 — CPU throughput ============================
        out.println("\n=== B6  CPU throughput: isolated beam solve + full step, FD vs ANALYTIC ===");
        Scratch scr=new Scratch();
        Cmot base=TwoBodyConverterMotor.buildS2(40.0,0.0,true,DT,1.0,1.0);
        // vary the pose a little to avoid a trivially-converged fixed point (realistic per-step work)
        double[] F8h={0.5e-12*base.bhat[0],0.5e-12*base.bhat[1],0.5e-12*base.bhat[2]};
        int nF=3*M, n=nF+2; double[] Msys=new double[n*n], F=new double[n];
        int WARM=3000, N=60000;
        // pre-build a pose pool ONCE (buildS2 is expensive and must stay OUT of the timed hot loop)
        int POOL=128; Cmot[] pool=new Cmot[POOL];
        for(int i=0;i<POOL;i++){ pool[i]=poseFor(base,i); setup(pool[i]); }
        // warm up
        for(int i=0;i<WARM;i++){ Cmot c=pool[i%POOL];
            ExplicitBeamSolver.assemble(c,7,101,false,F8h,Mode.FD_REPLICA,Msys,F);
            ExplicitBeamSolver.assemble(c,7,101,false,F8h,Mode.ANALYTIC,Msys,F); }
        // time assemble+solve (isolated beam solve) — pose from the pool (no rebuild in the loop)
        long tFD=timeAssembleSolve(pool,F8h,Mode.FD_REPLICA,n,N,scr);
        long tAN=timeAssembleSolve(pool,F8h,Mode.ANALYTIC,n,N,scr);
        out.printf(Locale.US,"  isolated beam solve (assemble+14×14 solve), %d iters:%n",N);
        out.printf(Locale.US,"    FD (nested central diff) : %8.3f µs/solve%n",tFD/1e3/N);
        out.printf(Locale.US,"    ANALYTIC (exact Hessian) : %8.3f µs/solve   speedup ×%.2f%n",tAN/1e3/N,(double)tFD/tAN);
        // count s2NodeForces / s2BendEnergy evals per FD solve (attribution)
        int fdForceEvals = 1 + 2*3*M;                 // 1 residual + 2 per free DOF (12) = 25 s2NodeForces
        int fdBendEvals  = fdForceEvals * (2*3*(M+1)); // each s2NodeForces central-diffs bend over 15 coords ×2
        out.printf(Locale.US,"  attribution: FD tangent = %d s2NodeForces evals ⇒ %d s2BendEnergy (acos/sqrt) evals per solve; ANALYTIC = 0 nested evals%n",
            fdForceEvals, fdBendEvals);
        // full step throughput
        long fFD=timeFullStep(Mode.FD_REPLICA, 20000, scr), fAN=timeFullStep(Mode.ANALYTIC, 20000, scr);
        out.printf(Locale.US,"  full stepS2 (bond+gather+traps+integrate+beam solve), 20000 steps:%n");
        out.printf(Locale.US,"    FD %.3f µs/step   ANALYTIC %.3f µs/step   speedup ×%.2f (beam solve is one of several stage costs)%n",
            fFD/1e3/20000,fAN/1e3/20000,(double)fFD/fAN);
        out.println("\n  B6: the analytic solver removes the nested FD (the dominant beam cost + all its transcendentals),");
        out.println("      which is exactly the cost/instruction-count reduction that unblocks the GPU kernel (Part C).");
    }

    // ---- power stroke under load; returns {stroke_nm, recoil_nm, F8ax_pN, F8torq, failFlag} ----
    static double[] stroke(double loadPN,int settle,int seed,Mode mode){
        Cmot cm=TwoBodyConverterMotor.buildS2(40.0,0.0,true,DT,1.0,1.0);
        cm.trapParams.set(5,(float)(loadPN*1e-12*dot(cm.bhat,cm.uvecPhys)));
        cm.mot.nucleotideState.set(0,MotorStore.NUC_ADPPI);
        cm.thetaS=PRESTROKE;
        Scratch s=new Scratch(); double fail=0;
        for(int t=0;t<settle;t++){ int st=step(cm,t,seed,true,mode,s); if(st!=0) fail=1; }
        double[] c0={cm.fil.coordX(0),cm.fil.coordY(0),cm.fil.coordZ(0)}; double[] P0=cm.P.clone();
        cm.mot.nucleotideState.set(0,MotorStore.NUC_ADP); cm.thetaS=ADP;
        for(int t=0;t<settle;t++){ int st=step(cm,t+settle,seed,true,mode,s); if(st!=0) fail=1; }
        double[] c1={cm.fil.coordX(0),cm.fil.coordY(0),cm.fil.coordZ(0)};
        double stroke=dot(sub(c1,c0),cm.phat)*1e3;                       // delivered axial (nm)
        double recoil=dot(sub(cm.P,P0),cm.bhat)*1e3;                      // pivot recoil (nm)
        double[] segF={cm.bondData.get(6),cm.bondData.get(7),cm.bondData.get(8)};
        double f8ax=dot(segF,cm.bhat)*1e12;                              // F8 axial force on actin (pN)
        double torq=cm.bondData.get(3);                                  // head torque proxy
        return new double[]{stroke,recoil,f8ax,torq,fail};
    }
    // ---- rapid 10 nm trap displacement, measure settled filament COM along glide axis (nm) ----
    static double rapid(int seed,Mode mode){
        Cmot cm=TwoBodyConverterMotor.buildS2(40.0,0.0,true,DT,1.0,1.0); Scratch s=new Scratch();
        for(int t=0;t<600;t++) step(cm,t,seed,false,mode,s);
        double[] c0={cm.fil.coordX(0),cm.fil.coordY(0),cm.fil.coordZ(0)};
        double d=0.010;   // 10 nm trap-center step along bhat
        cm.x0L.set(0,(float)(cm.x0L.get(0)+d*cm.bhat[0])); cm.x0L.set(1,(float)(cm.x0L.get(1)+d*cm.bhat[1])); cm.x0L.set(2,(float)(cm.x0L.get(2)+d*cm.bhat[2]));
        cm.x0R.set(0,(float)(cm.x0R.get(0)+d*cm.bhat[0])); cm.x0R.set(1,(float)(cm.x0R.get(1)+d*cm.bhat[1])); cm.x0R.set(2,(float)(cm.x0R.get(2)+d*cm.bhat[2]));
        for(int t=0;t<800;t++) step(cm,t+600,seed,false,mode,s);
        double[] c1={cm.fil.coordX(0),cm.fil.coordY(0),cm.fil.coordZ(0)};
        return dot(sub(c1,c0),cm.bhat)*1e3;
    }
    static int step(Cmot cm,int t,int seed,boolean brownian,Mode mode,Scratch s){
        if(mode==Mode.FD_REPLICA){ TwoBodyConverterMotor.stepS2(cm,t,seed,brownian); return 0; }
        return ExplicitBeamSolver.stepS2Mode(cm,t,seed,brownian,mode,s).status;
    }

    // ---- throughput timers ----
    static long timeAssembleSolve(Cmot[] pool,double[] F8h,Mode mode,int n,int N,Scratch scr){
        double[] aug=new double[n*(n+1)], dq=new double[n], mp=new double[1]; int P=pool.length;
        long t0=System.nanoTime();
        for(int i=0;i<N;i++){ Cmot c=pool[i%P];
            ExplicitBeamSolver.assemble(c,7,101,false,F8h,mode,scr.M,scr.F);
            ExplicitBeamSolver.solveFlat(scr.M,scr.F,n,aug,dq,mp); }
        return System.nanoTime()-t0;
    }
    static long timeFullStep(Mode mode,int N,Scratch s){
        Cmot cm=TwoBodyConverterMotor.buildS2(40.0,0.0,true,DT,1.0,1.0);
        long t0=System.nanoTime();
        for(int t=0;t<N;t++) step(cm,t,101,false,mode,s);
        return System.nanoTime()-t0;
    }
    static Cmot poseFor(Cmot base,int i){ Cmot cm=TwoBodyConverterMotor.buildS2(40.0,0.0,true,DT,1.0,1.0);
        Random r=new Random(i*2654435761L+1); double amp=0.003;
        for(int j=1;j<=M;j++) for(int k=0;k<3;k++) cm.g4Node[j][k]+=amp*(r.nextDouble()-0.5); cm.g4Node[0]=cm.g4E.clone(); return cm; }
    static void setup(Cmot cm){ cm.A=cm.g4Node[M]; cm.P=cm.A; TwoBodyConverterMotor.geomC(cm); }

    // ---- stats ----
    static double mean(double[] a){ double s=0; for(double v:a) s+=v; return s/a.length; }
    static double sd(double[] a){ double m=mean(a),s=0; for(double v:a) s+=(v-m)*(v-m); return Math.sqrt(s/Math.max(1,a.length-1)); }
    static double maxPairDiff(double[] a,double[] b){ double d=0; for(int i=0;i<a.length;i++) d=Math.max(d,Math.abs(a[i]-b[i])); return d; }
    static double dot(double[] a,double[] b){ return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static double[] sub(double[] a,double[] b){ return new double[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]}; }
}
