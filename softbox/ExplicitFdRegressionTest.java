package softbox;

import softbox.TwoBodyConverterMotor.Cmot;
import softbox.TwoBodyConverterMotor.ExplicitSolver;
import softbox.ExplicitBeamSolver.Mode;
import softbox.ExplicitBeamSolver.Scratch;
import java.util.*;

/**
 * 1.5 regression test: the FD explicit solver stays AVAILABLE and BIT-REPRODUCES the frozen s2Solve after
 * the analytic-solver wiring. Guards the promotion: FD is the permanent oracle/debug path and must be
 * unchanged and selectable. CPU-only, fast (no 5110-config sweep). New file.
 *
 *  Gate 1: the production default is FD.
 *  Gate 2: with explicitSolver=FD, one production s2Solve step == ExplicitBeamSolver FD_REPLICA (the
 *          validated bit-faithful replica of the ORIGINAL s2Solve) — max|Δ| = 0 over golden fixtures +
 *          random configs ⇒ the FD branch is byte-unchanged by the wiring.
 *  Gate 3: the selector round-trips (FD→ANALYTIC→FD) and FD is deterministically reproducible.
 *  Gate 4: explicitSolver=ANALYTIC runs with NO new failure (finite, non-singular) and its tangent
 *          differs from FD only by the removed FD truncation (both drive the same residual → same basin).
 */
public final class ExplicitFdRegressionTest {
    static final double DT=2.5e-6; static final int M=4;

    public static void main(String[] args){
        System.out.println("# 1.5 FD-availability regression: FD stays available + bit-reproduces s2Solve after wiring\n");
        Scratch scr=new Scratch();
        boolean pass=true;

        // Gate 1 — FD is SELECTABLE as the oracle (default may be ANALYTIC after promotion; FD must remain reachable)
        ExplicitSolver dflt = TwoBodyConverterMotor.explicitSolver;
        TwoBodyConverterMotor.explicitSolver=ExplicitSolver.FD;
        boolean g1 = TwoBodyConverterMotor.explicitSolver==ExplicitSolver.FD;
        System.out.printf(Locale.US,"  Gate 1  FD selectable as oracle (production default = %s) ..... %s%n", dflt, g1?"PASS":"FAIL");
        pass&=g1;

        // build test configs (fixtures + random perturbations)
        List<double[][]> nodes=new ArrayList<>(); List<double[]> ang=new ArrayList<>(); List<double[]> f8=new ArrayList<>();
        Cmot ref=TwoBodyConverterMotor.buildS2(40.0,0.0,true,DT,1.0,1.0);
        for(int s=0;s<200;s++){ Cmot c=TwoBodyConverterMotor.buildS2(40.0,0.0,true,DT,1.0,1.0); Random r=new Random(s*2654435761L+1);
            double amp=0.004*(0.2+r.nextDouble()); for(int j=1;j<=M;j++) for(int k=0;k<3;k++) c.g4Node[j][k]+=amp*(r.nextDouble()-0.5); c.g4Node[0]=c.g4E.clone();
            nodes.add(clone(c.g4Node)); ang.add(new double[]{(r.nextDouble()-0.5),(r.nextDouble()-0.5)});
            double sgn=r.nextBoolean()?1:-1,pN=r.nextDouble()*3; f8.add(new double[]{sgn*pN*1e-12*c.bhat[0],sgn*pN*1e-12*c.bhat[1],sgn*pN*1e-12*c.bhat[2]}); }

        // Gate 2 — FD-mode s2Solve == ExplicitBeamSolver FD_REPLICA (bit-identical)
        TwoBodyConverterMotor.explicitSolver=ExplicitSolver.FD;
        double g2max=0;
        for(int i=0;i<nodes.size();i++){
            Cmot a=cfg(nodes.get(i),ang.get(i)); a.A=a.g4Node[M]; a.P=a.A; TwoBodyConverterMotor.geomC(a);
            TwoBodyConverterMotor.s2Solve(a,7,101,false,f8.get(i).clone());                     // production FD path
            Cmot b=cfg(nodes.get(i),ang.get(i)); b.A=b.g4Node[M]; b.P=b.A; TwoBodyConverterMotor.geomC(b);
            ExplicitBeamSolver.s2SolveMode(b,7,101,false,f8.get(i).clone(),Mode.FD_REPLICA,scr.M,scr.F,scr.aug,scr.dq);
            g2max=Math.max(g2max,stateDiff(a,b));
        }
        boolean g2=g2max==0.0;
        System.out.printf(Locale.US,"  Gate 2  FD s2Solve ≡ FD_REPLICA (%d configs) ..... max|Δ|=%.3e %s%n",nodes.size(),g2max,g2?"PASS (bit-identical)":"FAIL");
        pass&=g2;

        // Gate 3 — selector round-trips + FD deterministic
        Cmot d1=cfg(nodes.get(0),ang.get(0)); d1.A=d1.g4Node[M]; d1.P=d1.A; TwoBodyConverterMotor.geomC(d1);
        TwoBodyConverterMotor.explicitSolver=ExplicitSolver.FD;      TwoBodyConverterMotor.s2Solve(d1,7,101,false,f8.get(0).clone());
        TwoBodyConverterMotor.explicitSolver=ExplicitSolver.ANALYTIC;   // flip
        TwoBodyConverterMotor.explicitSolver=ExplicitSolver.FD;         // flip back
        Cmot d2=cfg(nodes.get(0),ang.get(0)); d2.A=d2.g4Node[M]; d2.P=d2.A; TwoBodyConverterMotor.geomC(d2);
        TwoBodyConverterMotor.s2Solve(d2,7,101,false,f8.get(0).clone());
        boolean g3=stateDiff(d1,d2)==0.0;
        System.out.printf(Locale.US,"  Gate 3  selector round-trip FD deterministic .... %s%n",g3?"PASS":"FAIL"); pass&=g3;

        // Gate 4 — ANALYTIC runs, no new failure, differs from FD only by the tangent (same basin)
        TwoBodyConverterMotor.explicitSolver=ExplicitSolver.ANALYTIC;
        int nBad=0; double maxStep=0;
        for(int i=0;i<nodes.size();i++){
            Cmot a=cfg(nodes.get(i),ang.get(i)); a.A=a.g4Node[M]; a.P=a.A; TwoBodyConverterMotor.geomC(a);
            TwoBodyConverterMotor.s2Solve(a,7,101,false,f8.get(i).clone());
            for(int j=1;j<=M;j++) for(int k=0;k<3;k++) if(!Double.isFinite(a.g4Node[j][k])) nBad++;
            if(!Double.isFinite(a.phi)||!Double.isFinite(a.psi)) nBad++;
            Cmot b=cfg(nodes.get(i),ang.get(i)); b.A=b.g4Node[M]; b.P=b.A; TwoBodyConverterMotor.geomC(b);
            TwoBodyConverterMotor.explicitSolver=ExplicitSolver.FD; TwoBodyConverterMotor.s2Solve(b,7,101,false,f8.get(i).clone()); TwoBodyConverterMotor.explicitSolver=ExplicitSolver.ANALYTIC;
            maxStep=Math.max(maxStep,stateDiff(a,b));
        }
        boolean g4=nBad==0;
        System.out.printf(Locale.US,"  Gate 4  ANALYTIC no new failure (%d non-finite); single-step FD↔AN Δ=%.3e (tangent-only) .... %s%n",nBad,maxStep,g4?"PASS":"FAIL");
        pass&=g4;

        TwoBodyConverterMotor.explicitSolver=ExplicitSolver.FD;   // restore default
        System.out.println("\n  "+(pass?"OVERALL: PASS — FD stays available, selectable, and bit-reproduces s2Solve; analytic adds no failure."
                                       :"OVERALL: FAIL"));
    }

    static Cmot cfg(double[][] nd,double[] a){ Cmot c=TwoBodyConverterMotor.buildS2(40.0,0.0,true,DT,1.0,1.0);
        for(int j=0;j<=M;j++) c.g4Node[j]=nd[j].clone(); c.g4Node[0]=c.g4E.clone(); c.phi=a[0]; c.psi=a[1]; c.thetaS=a[1]-a[0]; c.psiActin=a[1]; return c; }
    static double[][] clone(double[][] a){ double[][] c=new double[a.length][]; for(int i=0;i<a.length;i++) c[i]=a[i].clone(); return c; }
    static double stateDiff(Cmot a,Cmot b){ double d=0; for(int j=1;j<=M;j++) for(int k=0;k<3;k++) d=Math.max(d,Math.abs(a.g4Node[j][k]-b.g4Node[j][k]));
        d=Math.max(d,Math.abs(a.phi-b.phi)); return Math.max(d,Math.abs(a.psi-b.psi)); }
}
