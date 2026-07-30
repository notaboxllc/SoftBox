package softbox;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import softbox.VilfanCompleteSystem.Config;
import softbox.VilfanCompleteSystem.Result;

/**
 * FDT-CONSISTENT ROLL BROWNIAN MOTION — CPU only, default off.
 * <pre>
 *   -vilfan-roll -roll-gates   Stage A fixtures + Stage B full gate suite
 *   -vilfan-roll -pilot        Stage C two-seed pilot (independent + antisymmetric noise)
 *   -vilfan-roll -campaign     Stage E production
 *   -vilfan-roll -controls     Stage F controls
 *   -vilfan-roll -paper        Stage G optional paper-lattice check
 *   -vilfan-roll -list &lt;st&gt; | -arm &lt;id&gt;
 * </pre>
 */
public final class VilfanRollHarness {

    static final String RUNDIR = "RUN_LOGS/vilfan_roll";
    static final double WARMUP_S = 24.0, ANALYSIS_S = 120.0;

    public static void main(String[] a0) throws Exception {
        List<String> a = List.of(a0);
        if (!a.contains("-vilfan-roll")) { System.out.println("refusing: need -vilfan-roll"); return; }
        Files.createDirectories(Path.of(RUNDIR));
        System.err.println("=== VILFAN ROLL-BROWNIAN — CPU only, no CUDA/TornadoVM/TaskGraph ===");
        if (a.contains("-roll-gates")) { gates(); return; }
        if (a.contains("-regression")) { regression(); return; }
        if (a.contains("-pilot"))      { camp(pilotArms()); return; }
        if (a.contains("-campaign"))   { camp(prodArms()); return; }
        if (a.contains("-controls"))   { camp(ctlArms()); return; }
        if (a.contains("-paper"))      { camp(paperArms()); return; }
        int il = a.indexOf("-list");
        if (il >= 0) { for (Arm m : listFor(il+1 < a.size() ? a.get(il+1) : "all"))
            if (!Files.exists(Path.of(RUNDIR, m.id()+".json"))) System.out.println(m.id()); return; }
        int ia = a.indexOf("-arm");
        if (ia >= 0 && ia+1 < a.size()) { runOne(a.get(ia+1)); return; }
        System.out.println("no mode");
    }

    /* ===================== configuration ===================== */

    static Config base() {
        Config c = new Config();
        c.latP=37; c.latQ=80; c.aNm=2.7; c.latSign=-1;
        c.alpha=4.0; c.kD=5.0;
        c.mechanics="overdamped"; c.etaPaS=VilfanDrag.ETA_ASSAY;
        c.axialBrownian=false; c.rollBrownian=true;          // THE declared condition
        c.anchorDtS=5.0e-6; c.refineLevel=0;
        c.warmupTimeS=WARMUP_S; c.analysisTimeS=ANALYSIS_S;
        c.travelCapUm=20.0; c.maxEvents=20_000_000L;
        return c;
    }
    static Config paperBase(){ Config c=base(); c.latP=13; c.latQ=28; c.aNm=2.75; return c; }

    record Arm(String id, Config cfg) {}

    /**
     * Matched native/mirror pair.
     * <p>anti = true realises the MIRROR TRANSFORM OF THE STOCHASTIC EQUATION: under Theta -> -Theta
     * the Wiener path must transform as dW -> -dW, so the mirror arm shares the seed and sets
     * rollNoiseSign = -1. Same-signed roll noise would NOT be the mirror transform. Antisymmetric
     * pairs are a pathwise correctness gate, never an independent sample.
     * <p>anti = false offsets the mirror seed so the two roll paths are independent — the
     * inferential design.
     */
    static List<Arm> pair(String tag, Config b, long seed, boolean anti) {
        List<Arm> L = new ArrayList<>();
        Config n = b.copy(); n.seed=seed; n.latSign=-1; n.rollNoiseSign=+1;
        Config m = b.copy(); m.latSign=+1;
        if (anti) { m.seed=seed; m.rollNoiseSign=-1; } else { m.seed=seed+500_000L; m.rollNoiseSign=+1; }
        String sfx = anti ? "an" : "in";
        L.add(new Arm(String.format(Locale.ROOT,"%s_%s_s%d_native",tag,sfx,seed), n));
        L.add(new Arm(String.format(Locale.ROOT,"%s_%s_s%d_mirror",tag,sfx,seed), m));
        return L;
    }

    static final long[] PILOT={101,102};
    static final long[] PROD={101,102,103,104,105,106,107,108};

    static List<Arm> pilotArms(){ List<Arm> L=new ArrayList<>();
        for(long s:PILOT) L.addAll(pair("pilot",base(),s,false));
        for(long s:PILOT) L.addAll(pair("pilot",base(),s,true));
        for(long s:PILOT){ Config c=base(); c.seed=s; c.noDepletionControl=true;
            L.add(new Arm("pilotshadow_s"+s+"_native",c)); }
        return L; }
    static List<Arm> prodArms(){ List<Arm> L=new ArrayList<>();
        for(long s:PROD) L.addAll(pair("prod",base(),s,false));
        for(long s:PROD){ Config c=base(); c.seed=s; c.noDepletionControl=true;
            L.add(new Arm("prodshadow_s"+s+"_native",c)); }
        return L; }
    static List<Arm> ctlArms(){ List<Arm> L=new ArrayList<>();
        for(long s:PILOT){ Config c=base(); c.alpha=0.0;              L.addAll(pair("ctl_alpha0",c,s,false)); }
        for(long s:PILOT){ Config c=base(); c.dNm=0.0;                L.addAll(pair("ctl_d0",c,s,false)); }
        for(long s:PILOT){ Config c=base(); c.latP=0; c.latQ=1;       L.addAll(pair("ctl_achiral",c,s,false)); }
        for(long s:new long[]{101,102,103,104}){ Config c=base(); c.rollBrownian=false;
                                                                       L.addAll(pair("ctl_broff",c,s,false)); }
        return L; }
    static List<Arm> paperArms(){ List<Arm> L=new ArrayList<>();
        for(long s:PILOT) L.addAll(pair("paper",paperBase(),s,false)); return L; }
    static List<Arm> listFor(String st){ return switch(st){
        case "pilot"->pilotArms(); case "campaign"->prodArms(); case "controls"->ctlArms();
        case "paper"->paperArms(); default->allArms(); }; }
    static List<Arm> allArms(){ List<Arm> L=new ArrayList<>();
        L.addAll(pilotArms()); L.addAll(prodArms()); L.addAll(ctlArms()); L.addAll(paperArms()); return L; }

    static void camp(List<Arm> arms) throws IOException {
        for (Arm m: arms){ if(Files.exists(Path.of(RUNDIR,m.id()+".json"))){System.out.printf("  [skip] %s%n",m.id());continue;} run(m);} }
    static void runOne(String id) throws IOException {
        for (Arm m: allArms()) if(m.id().equals(id)){
            if(Files.exists(Path.of(RUNDIR,id+".json"))){System.out.printf("[skip] %s%n",id);return;} run(m); return; }
        System.out.println("unknown arm: "+id); }
    static void run(Arm m) throws IOException {
        System.out.printf("  [run] %s ... ",m.id()); System.out.flush();
        VilfanCompleteSystem sys=new VilfanCompleteSystem(m.cfg());
        Result R=sys.run(); R.armId=m.id();
        Path d=Path.of(RUNDIR,m.id()+".json"), t=Path.of(d+".tmp");
        Files.writeString(t, VilfanCompleteHarness.toJson(R,sys));
        Files.move(t,d,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        System.out.printf("v=%.5f om=%+.4f <xA>=%.4f <thA>=%+.5f wind=%.0f nEv=%d [%.0fs]%n",
            R.velUmPerS,R.omegaRadPerS,R.meanXa,R.meanThA,R.windingRad,R.nEvents,R.wallClockS); }

    /* ===================== gates ===================== */

    static int pass=0, fail=0;
    static void gate(String n, boolean ok, String d){
        System.out.printf("  [%s] %-54s %s%n", ok?"PASS":"FAIL", n, d); System.out.flush();
        if(ok) pass++; else fail++; }
    static double rel(double a,double b){ return Math.abs(a-b)/Math.max(1e-300,Math.abs(b)); }

    static void gates() throws IOException {
        System.out.println("\n== STAGE A — ANGULAR HYSTERESIS FIXTURES ==\n"); System.out.flush(); stageA();
        System.out.println("\n== STAGE B — FULL GATE SUITE ==\n"); System.out.flush();
        b1(); b2(); b3(); b4(); b6(); b7(); b8(); b9(); b10();
        System.out.printf("%n  gates: %d PASS, %d FAIL%n", pass, fail);
        if(fail>0) System.out.println("  *** NOT CLEAN — do not launch the pilot ***");
    }

    /* ---- Stage A fixtures ---- */
    static void stageA(){
        double[] B={0.0,0.01,0.025,0.05,0.10};
        int i05 = 3;                                        // the 0.05 rad band
        // 1 deterministic monotone roll: D/band confirmed forward crossings, zero backward
        VilfanRollBands m=new VilfanRollBands(B); m.start(0);
        for(int k=1;k<=1000;k++) m.update(k*0.001);          // +1.0 rad monotone
        gate("A1 monotone roll counts D/band forward, 0 backward",
             Math.abs(m.fwd[i05]-20)<=1 && m.bwd[i05]==0,   // +-1: the band edge is not exact in binary
             String.format(Locale.ROOT,"1.0 rad / 0.05 = 20 -> fwd %d bwd %d",m.fwd[i05],m.bwd[i05]));
        // 2 deterministic reversal
        VilfanRollBands r=new VilfanRollBands(B); r.start(0);
        for(int k=1;k<=200;k++) r.update(k*0.001);
        for(int k=199;k>=0;k--) r.update(k*0.001);
        gate("A2 reversal counts backward crossings",
             Math.abs(r.fwd[i05]-4)<=1 && Math.abs(r.bwd[i05]-r.fwd[i05])<=1,
             String.format(Locale.ROOT,"fwd %d bwd %d",r.fwd[i05],r.bwd[i05]));
        // 3 sub-band oscillation must NOT count
        VilfanRollBands o=new VilfanRollBands(B); o.start(0);
        for(int k=0;k<20000;k++) o.update(0.02*Math.sin(k*0.1));   // peak-to-peak 0.04 < one band 0.05
        gate("A3 sub-band oscillation counts nothing", o.total(i05)==0,
             String.format(Locale.ROOT,"amplitude 0.02 rad vs band 0.05: total %d (raw band-0: %d)",
                     o.total(i05), o.total(0)));
        // 4/5 single confirmed forward and backward
        VilfanRollBands f=new VilfanRollBands(B); f.start(0); f.update(0.06);
        gate("A4 one confirmed forward crossing", f.fwd[i05]==1 && f.bwd[i05]==0,
             String.format(Locale.ROOT,"fwd %d bwd %d",f.fwd[i05],f.bwd[i05]));
        VilfanRollBands g=new VilfanRollBands(B); g.start(0); g.update(-0.06);
        gate("A5 one confirmed backward crossing", g.bwd[i05]==1 && g.fwd[i05]==0,
             String.format(Locale.ROOT,"fwd %d bwd %d",g.fwd[i05],g.bwd[i05]));
        // 6 multiple crossings in one update (large jump)
        VilfanRollBands h=new VilfanRollBands(B); h.start(0); h.update(0.51);
        gate("A6 multiple confirmed crossings from one large step", h.fwd[i05]==10,
             String.format(Locale.ROOT,"0.51 rad / 0.05 -> fwd %d",h.fwd[i05]));
        // 7 Brownian path under nested refinement. VALIDITY CONDITION: a Schmitt-trigger count is
        // resolution-independent only when the band greatly exceeds the per-step RMS increment. For
        // a continuum Brownian path the expected count is 2*D*T/w^2; a coarse sampling UNDERCOUNTS
        // and converges upward. The fixture therefore runs in the regime band >> sigma_step, and the
        // production report must state which bands satisfy that condition (S-A3).
        double D=489.0, T=0.01, band=0.10;
        long[] tot=new long[4]; double[] sig=new double[4];
        for(int lvl=0;lvl<4;lvl++){
            int n=100000*(1<<lvl); double dt=T/n; double th=0;
            sig[lvl]=Math.sqrt(2*D*dt);
            VilfanRollBands q=new VilfanRollBands(B); q.start(0);
            for(int k=0;k<n;k++){ th += sig[lvl]*VilfanAxialBrownian.gauss(7,11,k+(long)lvl*10000000L);
                                  q.update(th); }
            tot[lvl]=q.total(4);
        }
        double want=2*D*T/(band*band);
        boolean stable = Math.abs((double)tot[3]-tot[2])/Math.max(1,tot[2]) < 0.10
                      && Math.abs(tot[3]-want)/want < 0.35;
        gate("A7 Brownian count stable under 8x refinement when band >> step", stable,
             String.format(Locale.ROOT,"0.10 rad band: %d %d %d %d (sigma_step %.4f..%.4f rad); continuum 2DT/w^2 = %.0f",
                     tot[0],tot[1],tot[2],tot[3],sig[0],sig[3],want));
        gate("A7b validity condition recorded: band must exceed the per-step RMS increment", sig[0]<0.25*band,
             String.format(Locale.ROOT,"sigma_step %.4f rad vs band %.2f rad", sig[0], band));
        gate("A8 band-0 row is jitter-dominated (labelled, never mechanistic)", o.total(0)>100 && o.total(i05)==0,
             String.format(Locale.ROOT,"sub-band oscillation: band-0 %d vs band-0.05 %d",o.total(0),o.total(i05)));
    }

    /* ---- B1 free rotational diffusion ---- */
    static void b1(){
        Config c=base(); double gT=VilfanDrag.gammaThetaWork(c.etaPaS,c.lengthUm,c.filRadiusUm);
        double D=c.kBT/gT; boolean okV=true,okM=true; StringBuilder sb=new StringBuilder();
        for(double dt: new double[]{1e-6,1e-5,1e-4,1e-3,1e-2}){
            int n=200000; double s1=0,s2=0;
            for(int i=0;i<n;i++){ double z=VilfanAxialBrownian.gauss(4242,VilfanCompleteSystem.STREAM_ROLL,i);
                double d=Math.sqrt(2*D*dt)*z; s1+=d; s2+=d*d; }
            double mean=s1/n, var=s2/n-mean*mean, want=2*D*dt;
            if(rel(var,want)>0.02) okV=false;
            if(Math.abs(mean)>4*Math.sqrt(want/n)) okM=false;
            sb.append(String.format(Locale.ROOT,"%.0e:%.4f ",dt,var/want)); }
        gate("B1a free roll variance = 2 DTheta t (5 decades)",okV,sb.toString().trim());
        gate("B1b free roll mean displacement = 0",okM,"");
        // winding-number distribution: turns = Theta/2pi should be N(0, 2Dt/(2pi)^2)
        int n=200000; double T=0.02, s1=0,s2=0;
        for(int i=0;i<n;i++){ double th=Math.sqrt(2*D*T)*VilfanAxialBrownian.gauss(99,VilfanCompleteSystem.STREAM_ROLL,i);
            double w=th/(2*Math.PI); s1+=w; s2+=w*w; }
        double wv=s2/n-(s1/n)*(s1/n), want=2*D*T/(4*Math.PI*Math.PI);
        gate("B1c winding-number distribution correct",rel(wv,want)<0.03,
             String.format(Locale.ROOT,"var(turns) %.5f vs %.5f over %.3g s",wv,want,T));
    }

    /* ---- B2 local OU equilibrium ---- */
    static void b2(){
        Config c=base(); double gT=VilfanDrag.gammaThetaWork(c.etaPaS,c.lengthUm,c.filRadiusUm);
        boolean okV=true,okA=true,okM=true; StringBuilder sb=new StringBuilder();
        for(int nb: new int[]{1,5,25,83}){
            double tau=gT/(nb*c.kTheta()), vI=c.kBT/(nb*c.kTheta()), dt=0.7*tau, x=0, eq=0.31;
            int n=400000; double s1=0,s2=0,sl=0,prev=x;
            for(int i=0;i<n;i++){ double z=VilfanAxialBrownian.gauss(555+nb,VilfanCompleteSystem.STREAM_ROLL,i);
                x=VilfanAxialBrownian.ouStep(eq,x,tau,vI,dt,z);
                if(i>1000){ s1+=x; s2+=x*x; sl+=(x-eq)*(prev-eq);} prev=x; }
            int m2=n-1000; double mean=s1/m2, var=s2/m2-mean*mean, ac=(sl/m2)/var, wantAC=Math.exp(-dt/tau);
            if(rel(var,vI)>0.03) okV=false;
            if(rel(ac,wantAC)>0.03) okA=false;
            if(Math.abs(mean-eq)>0.05*Math.sqrt(vI)*30) okM=false;
            sb.append(String.format(Locale.ROOT,"Nb%d:v%.3f/a%.3f ",nb,var/vI,ac/wantAC)); }
        gate("B2a local OU variance = kBT/(Nb Ktheta)",okV,sb.toString().trim());
        gate("B2b local OU mean = ThetaEq",okM,"");
        gate("B2c local OU autocorrelation = exp(-t/tauTheta)",okA,"ratios above");
    }

    /* ---- B3 FULL PERIODIC BOLTZMANN (mandatory) ---- */
    static void b3(){
        // One bound head at site i: U(Theta) = 0.5 Ktheta wrapPi(Theta+b)^2, a PERIODIC sawtooth well.
        // Free-run Theta with the exact local OU between crossings and compare the wrapped-angle
        // histogram with exp(-U/kBT) over a full 2pi period, permitting branch crossings.
        // The well must be SHALLOW enough that branch crossings actually occur, or the gate is
        // vacuous and merely repeats the local-OU gate B2. At the production stiffness
        // (alpha = 4, Nb = 83) the barrier at +-pi is 0.5*Nb*Ktheta*pi^2, astronomically large, so
        // this fixture uses alpha = 0.5 with Nb = 1, giving a barrier of ~2.5 kBT. That the
        // production barrier is unreachable is itself a physical finding, reported in the study.
        Config c=base(); c.alpha=0.5; VilfanCompleteSystem s=new VilfanCompleteSystem(c);
        double gT=VilfanDrag.gammaThetaWork(c.etaPaS,c.lengthUm,c.filRadiusUm);
        double Kth=c.kTheta(), kBT=c.kBT;
        int nb=1;                                     // ONE head => shallow well => crossings happen
        double tau=gT/(nb*Kth), vI=kBT/(nb*Kth);
        // dt must be small against the well half-width pi, or the single-branch OU step
        // misrepresents boundary crossings and the gate measures the FIXTURE rather than the method.
        // Measured: max rel dev 0.567 at 0.25*tau, 0.172 at 0.02*tau, i.e. ~sqrt(dt) as expected for a
        // single-branch step near a boundary. Production runs 0.049 rad steps with boundaries 28 sigma
        // away, so this error is a property of the FIXTURE, not of the production method.
        int NB=72; double[] hist=new double[NB]; long tot=0;
        double th=0; int n=200_000_000; double dt=0.0025*tau;
        long cross=0; double prevW=0;
        for(int i=0;i<n;i++){
            double w=VilfanCompleteSystem.wrapPi(th);
            double eq=th-w;                            // local minimum of the branch we are in
            double z=VilfanAxialBrownian.gauss(31337,VilfanCompleteSystem.STREAM_ROLL,i);
            th=VilfanAxialBrownian.ouStep(eq,th,tau,vI,dt,z);
            double w2=VilfanCompleteSystem.wrapPi(th);
            if(Math.abs(w2-prevW)>Math.PI) cross++;
            prevW=w2;
            if(i>100000){ int b=(int)((w2+Math.PI)/(2*Math.PI)*NB); if(b>=0&&b<NB){hist[b]++; tot++;} }
        }
        // analytic periodic Boltzmann over ONE period, and its variance. The reference variance is
        // NOT the unbounded harmonic kBT/(Nb Ktheta): the periodic well truncates the Gaussian at
        // +-pi, so the correct target is the second moment of the normalised periodic distribution,
        // computed here from the same reference used for the histogram.
        double[] want=new double[NB]; double Z=0, wantVar=0;
        for(int b=0;b<NB;b++){ double a=-Math.PI+(b+0.5)*2*Math.PI/NB;
            want[b]=Math.exp(-0.5*Kth*a*a/kBT); Z+=want[b]; wantVar+=a*a*want[b]; }
        wantVar/=Z;
        double maxdev=0; for(int b=0;b<NB;b++){ double p=hist[b]/tot, q=want[b]/Z;
            if(q>1e-4) maxdev=Math.max(maxdev,Math.abs(p-q)/q); }
        System.out.printf("       periodic reference: var %.5f (unbounded harmonic would be %.5f)%n",
                wantVar, kBT/Kth); System.out.flush();
        gate("B3a wrapped-angle histogram == exp(-U/kBT) over a full period", maxdev<0.10,
             String.format(Locale.ROOT,"max rel dev %.4f over bins with q>1e-4, %d branch crossings",maxdev,cross));
        gate("B3b branch crossings actually occurred (gate is not vacuous)", cross>1000,
             String.format(Locale.ROOT,"%d crossings in %d steps",cross,n));
        // invariance to starting branch integer
        double th2=2*Math.PI*3; double s1=0,s2=0; long m2=0;
        for(int i=0;i<8_000_000;i++){
            double w=VilfanCompleteSystem.wrapPi(th2); double eq=th2-w;
            double z=VilfanAxialBrownian.gauss(31337,VilfanCompleteSystem.STREAM_ROLL,i);
            th2=VilfanAxialBrownian.ouStep(eq,th2,tau,vI,dt,z);
            if(i>100000){ double ww=VilfanCompleteSystem.wrapPi(th2); s1+=ww; s2+=ww*ww; m2++; } }
        double var2=s2/m2-(s1/m2)*(s1/m2);
        gate("B3c invariant to the starting branch integer", rel(var2,wantVar)<0.06,
             String.format(Locale.ROOT,"started 3 turns up: var %.5f vs periodic reference %.5f",var2,wantVar));
    }

    /* ---- B4/B5 branch-crossing fixtures + missed-crossing control ---- */
    static void b4(){
        // production arms at four crossing tolerances: statistics must be stable
        int NL=3; double[] got=new double[NL]; double[] xa=new double[NL]; long[] cr=new long[NL];
        double[] miss=new double[NL];
        for(int lvl=0;lvl<NL;lvl++){
            Config c=base(); c.seed=909; c.warmupTimeS=1.0; c.analysisTimeS=4.0;
            c.refineLevel=lvl;
            Result R=new VilfanCompleteSystem(c).run();
            got[lvl]=R.omegaRadPerS; xa[lvl]=R.meanXa; cr[lvl]=R.nRollCross; miss[lvl]=R.maxMissProb;
            System.out.printf("       refine %d (dt %.2e): omega %+.5f  <xA> %.4f  crossings %d  maxMiss %.3e%n",
                lvl, c.anchorDtS/(1<<lvl), R.omegaRadPerS, R.meanXa, R.nRollCross, R.maxMissProb);
        }
        gate("B5a missed-crossing probability negligible at all tolerances",
             miss[0]<1e-6 && miss[NL-1]<1e-6,
             String.format(Locale.ROOT,"max over levels %.3e",Math.max(miss[0],Math.max(miss[1],miss[NL-1]))));
        // NOT a gate: refining shifts event times and decorrelates the trajectory, so cross-level
        // scatter measures sampling noise, not discretisation error. The valid instrument is the
        // fixed-path bias test B6.
        System.out.printf("     [info] <xA> across levels %.4f / %.4f / %.4f -- trajectory decorrelation,%n",
                xa[0],xa[1],xa[NL-1]);
        System.out.println("            NOT a convergence measure; see B6 for the fixed-path bias gate.");
    }

    /* ---- B6 fixed-path hazard bias ---- */
    static void b6(){
        boolean ok=true; String d0="";
        for(int lvl=0;lvl<3;lvl++){
            Config c=base(); c.seed=101; c.warmupTimeS=0.5; c.analysisTimeS=2.5; c.refineLevel=lvl;
            c.hazardCrossCheck=true; c.hazardCheckStride=37; c.hazardCheckDepth=4;
            Result R=new VilfanCompleteSystem(c).run();
            double z=Math.abs(R.xcheckBias)/Math.max(1e-30,R.xcheckBiasSem);
            System.out.printf("       dt=%.2e  bias %+.3e +- %.3e (%.2f sigma)  mean|rel| %.3e  n=%d%n",
                c.anchorDtS/(1<<lvl),R.xcheckBias,R.xcheckBiasSem,z,R.xcheckMeanRel,R.xcheckN); System.out.flush();
            if(z>4.0) ok=false;
            if(lvl==0) d0=String.format(Locale.ROOT,"%.2f sigma at the production step",z); }
        gate("B6 roll hazard quadrature UNBIASED on bridge-nested paths",ok,d0);
    }

    /* ---- B7 event continuity ---- */
    static void b7(){
        Config c=base(); c.seed=4242; c.warmupTimeS=1.0; c.analysisTimeS=3.0;
        Result R=new VilfanCompleteSystem(c).run();
        gate("B7a X continuous at every event",R.maxEventJumpX==0.0,
             String.format(Locale.ROOT,"max |dX| = %.3g nm over %d events",R.maxEventJumpX,R.nEvents));
        gate("B7b Theta continuous at every event and branch update",R.maxEventJumpTheta==0.0,
             String.format(Locale.ROOT,"max |dTheta| = %.3g rad",R.maxEventJumpTheta));
    }

    /* ---- B8 stochastic dynamic closure ---- */
    static void b8(){
        // pointwise gammaTheta*ThetaDot = sum M does NOT hold (the path is nondifferentiable).
        // Verify instead: conditional mean increment == deterministic drift, innovation mean 0,
        // innovation variance == the exact OU law.
        Config c=base(); double gT=VilfanDrag.gammaThetaWork(c.etaPaS,c.lengthUm,c.filRadiusUm);
        int nb=83; double tau=gT/(nb*c.kTheta()), vI=c.kBT/(nb*c.kTheta()), dt=0.8*tau, eq=0.17;
        int n=2_000_000; double s1=0,s2=0; double th=eq+0.4;
        for(int i=0;i<n;i++){
            double detPart=eq+(th-eq)*Math.exp(-dt/tau);
            double z=VilfanAxialBrownian.gauss(2024,VilfanCompleteSystem.STREAM_ROLL,i);
            double nx=VilfanAxialBrownian.ouStep(eq,th,tau,vI,dt,z);
            double innov=nx-detPart; s1+=innov; s2+=innov*innov; th=nx; }
        double im=s1/n, iv=s2/n-im*im, wantV=vI*(1-Math.exp(-2*dt/tau));
        gate("B8a innovation mean = 0",Math.abs(im)<4*Math.sqrt(iv/n),
             String.format(Locale.ROOT,"mean %.3e vs 4 SEM %.3e",im,4*Math.sqrt(iv/n)));
        gate("B8b innovation variance = exact OU law",rel(iv,wantV)<0.01,
             String.format(Locale.ROOT,"%.6g vs %.6g",iv,wantV));
        Config d=base(); d.rollBrownian=false; d.seed=777; d.warmupTimeS=1.0; d.analysisTimeS=3.0;
        Result R=new VilfanCompleteSystem(d).run();
        gate("B8c Brownian-OFF recovers deterministic torque closure",R.maxDynResidM<1e-8,
             String.format(Locale.ROOT,"max |gammaTh*Thdot - sumM| = %.3g pN*nm",R.maxDynResidM));
    }

    /* ---- B9 mirror correctness under ANTISYMMETRIC roll noise ---- */
    static void b9(){
        Config n=base(); n.seed=909; n.warmupTimeS=1.0; n.analysisTimeS=5.0; n.rollNoiseSign=+1;
        Config m=n.copy(); m.latSign=+1; m.rollNoiseSign=-1;      // dW -> -dW
        Result N=new VilfanCompleteSystem(n).run(), M=new VilfanCompleteSystem(m).run();
        gate("B9a X paths identical",Math.abs(N.xEnd-M.xEnd)<1e-9,
             String.format(Locale.ROOT,"dX %.3g nm",N.xEnd-M.xEnd));
        gate("B9b Theta paths exact negatives",Math.abs(N.thetaEnd+M.thetaEnd)<1e-9,
             String.format(Locale.ROOT,"%.9f vs %.9f",N.thetaEnd,M.thetaEnd));
        gate("B9c event counts and mirror-even statistics identical",
             N.nEvents==M.nEvents && Math.abs(N.meanXa-M.meanXa)<1e-9,
             String.format(Locale.ROOT,"%d vs %d events, <xA> %.6f vs %.6f",N.nEvents,M.nEvents,N.meanXa,M.meanXa));
        gate("B9d theta_A reversed, Omega_even numerical zero",
             Math.abs(N.meanThA+M.meanThA)<1e-12 && Math.abs(N.omegaRadPerS+M.omegaRadPerS)<1e-12,
             String.format(Locale.ROOT,"<thA> %+.6f/%+.6f  Omega_even %.3g",N.meanThA,M.meanThA,
                     0.5*(N.omegaRadPerS+M.omegaRadPerS)));
        // same-signed noise must NOT give pathwise antisymmetry (shows the gate is meaningful)
        Config w=n.copy(); w.latSign=+1; w.rollNoiseSign=+1;
        Result W=new VilfanCompleteSystem(w).run();
        gate("B9e same-signed roll noise is NOT the mirror transform",
             Math.abs(N.thetaEnd+W.thetaEnd)>1e-6,
             String.format(Locale.ROOT,"same-signed mirror Theta %.6f vs required %.6f",W.thetaEnd,-N.thetaEnd));
    }

    /* ---- B10 fast regression (bit-reproducibility + roll-OFF draws no noise) ---- */
    static void b10() throws IOException {
        Config c=base(); c.rollBrownian=false; c.seed=101; c.warmupTimeS=1.0; c.analysisTimeS=3.0;
        Result R=new VilfanCompleteSystem(c).run();
        gate("B10a roll-OFF draws no roll noise",R.nSubsteps==0,
             String.format(Locale.ROOT,"%d substeps",R.nSubsteps));
        Config b=base(); b.seed=31337; b.warmupTimeS=1.0; b.analysisTimeS=2.0;
        Result P=new VilfanCompleteSystem(b).run(), Q=new VilfanCompleteSystem(b.copy()).run();
        gate("B10b same seed bit-reproducible",P.xEnd==Q.xEnd&&P.thetaEnd==Q.thetaEnd&&P.nEvents==Q.nEvents,
             String.format(Locale.ROOT,"Theta %.17g vs %.17g, %d events",P.thetaEnd,Q.thetaEnd,P.nEvents));
        System.out.println("     (full-length regression against the committed deterministic and");
        System.out.println("      axial-Brownian records runs separately: -regression)");
    }

    /**
     * FULL-LENGTH regression, run as its own mode because each arm is a production-length run.
     * Reproduces the committed deterministic finite-drag record and shows the axial-Brownian-only
     * mode is unchanged when roll noise is off.
     */
    static void regression() throws IOException {
        System.out.println("\n== FULL-LENGTH REGRESSION ==\n");
        Config c=base(); c.rollBrownian=false; c.seed=101;
        c.warmupTimeS=0; c.analysisTimeS=0; c.warmupUm=1.0; c.travelUm=5.0;
        c.turnsTarget=8.0; c.travelCapUm=60.0;
        Result R=new VilfanCompleteSystem(c).run();
        Path ref=Path.of("DRAG_RECORDS","odnative_e0.01_s101_native.json");
        if(Files.exists(ref)){
            var w=VilfanCompleteHarness.parse(Files.readString(ref));
            double rv=rel(R.velUmPerS,VilfanCompleteHarness.d(w,"velUmPerS"));
            double ro=rel(R.omegaRadPerS,VilfanCompleteHarness.d(w,"omegaRadPerS"));
            double rx=rel(R.meanXa,VilfanCompleteHarness.d(w,"meanXaNm"));
            gate("R1 Brownian-OFF reproduces the committed deterministic record",
                 rv<1e-9&&ro<1e-9&&rx<1e-9,
                 String.format(Locale.ROOT,"rel dev v %.2e omega %.2e <xA> %.2e",rv,ro,rx));
        } else gate("R1 Brownian-OFF reproduces the committed deterministic record",false,"DRAG_RECORDS missing");
        Config ax=base(); ax.rollBrownian=false; ax.axialBrownian=true; ax.seed=101;
        Path aref=Path.of("AXIAL_RECORDS","pilot_in_s101_native.json");
        if(Files.exists(aref)){
            var w=VilfanCompleteHarness.parse(Files.readString(aref));
            Result A=new VilfanCompleteSystem(ax).run();
            double rv=rel(A.velUmPerS,VilfanCompleteHarness.d(w,"velUmPerS"));
            double rx=rel(A.meanXa,VilfanCompleteHarness.d(w,"meanXaNm"));
            gate("R2 axial-Brownian-only unchanged when roll noise is off",rv<1e-9&&rx<1e-9,
                 String.format(Locale.ROOT,"rel dev v %.2e <xA> %.2e",rv,rx));
        } else gate("R2 axial-Brownian-only unchanged when roll noise is off",false,"AXIAL_RECORDS missing");
        System.out.printf("%n  regression: %d PASS, %d FAIL%n",pass,fail);
    }
}
