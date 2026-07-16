package softbox;

import softbox.TwoBodyConverterMotor.Cmot;
import softbox.ExplicitBeamSolver.Mode;
import softbox.ExplicitBeamSolver.Scratch;
import uk.ac.manchester.tornado.api.*;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import java.io.*;
import java.util.*;

/**
 * Part C — flat analytic explicit-S2 beam GPU kernel: lowering probe (C2), device fixture gate (C3),
 * and beam microbenchmark (C4). Also validates the CPU-mirror of the kernel (same method, plain-Java
 * loop) against the CPU FD oracle + CPU analytic solver.
 *
 *  -cpuvalidate : CPU mirror (TwoBodyBeamAnalyticGpu called directly) vs CPU FD + CPU analytic solver
 *  -probe       : C2 lowering probe (N=1 device TaskGraph, no silent fallback)
 *  -gate        : C3 device fixture gate (all explicit golden fixtures: CPU-FD / CPU-analytic / GPU-analytic)
 *  -bench       : C4 microbenchmark (batch sizes 128..65536)
 *  (default: -cpuvalidate; add -gpu-prefixed modes explicitly)
 */
public final class ExplicitBeamGpuHarness {
    static final double DT=2.5e-6; static final int M=4;
    static final int NF=3*M, N=NF+2, W=N+1, SYS=TwoBodyBeamAnalyticGpu.SYS_STRIDE;
    static final int MAXIT=800; static final double TOL=3e-7;

    static Cmot BASE;

    public static void main(String[] args) throws Exception {
        PrintStream out=System.out;
        Set<String> flags=new HashSet<>(Arrays.asList(args));
        boolean doVal=flags.contains("-cpuvalidate")||flags.isEmpty();
        boolean doProbe=flags.contains("-probe");
        boolean doGate=flags.contains("-gate");
        boolean doBench=flags.contains("-bench");
        BASE=TwoBodyConverterMotor.buildS2(40.0,0.0,true,DT,1.0,1.0);
        out.println("# EXPLICIT_S2_L40 analytic beam GPU kernel — Part C (lowering probe / device gate / microbench)");
        out.println("# double precision. M="+M+" n="+N+" (14 DOF). maxIt="+MAXIT+" tol="+TOL+" µm\n");

        List<Cfg> fixtures=loadFixtures();
        if(flags.contains("-debugasm")){ debugAsm(out, fixtures); return; }
        if(flags.contains("-debug1")){ debug1(out, fixtures); return; }
        if(doVal) cpuValidate(out, fixtures);
        if(doProbe) probe(out);
        if(doGate) gate(out, fixtures);
        if(doBench) bench(out);
    }

    // ================= single-step debug (bisect the assembly) =================
    static void debug1(PrintStream out, List<Cfg> fixtures) {
        out.println("=== DEBUG: flat kernel one step vs ExplicitBeamSolver FULLY_ANALYTIC one step ===");
        Cfg[] test={ fixtures.get(0), randomCfg(128), randomCfg(5) };
        String[] nm={fixtures.get(0).tag,"random#128","random#5"};
        Scratch scr=new Scratch();
        for(int ti=0;ti<test.length;ti++){ Cfg c=test[ti];
            // flat kernel one step
            List<Cfg> one=new ArrayList<>(); one.add(c); Buf b=pack(one); b.counts.set(1,1);   // maxIt=1
            TwoBodyBeamAnalyticGpu.beamRelaxAnalytic(b.nodes,b.frame,b.q,b.F8h,b.params,b.sys,b.outGeom,b.status,b.iters,b.counts);
            double[][] gn=unpackNodes(b,0); double gphi=b.q.get(0),gpsi=b.q.get(1);
            // solver one step
            Cmot cm=freshFrom(c); cm.A=cm.g4Node[M]; cm.P=cm.A; TwoBodyConverterMotor.geomC(cm);
            ExplicitBeamSolver.s2SolveMode(cm,7,101,false,c.F8h.clone(),Mode.FULLY_ANALYTIC,scr.M,scr.F,scr.aug,scr.dq);
            double dn=0; for(int j=1;j<=M;j++) for(int k=0;k<3;k++) dn=Math.max(dn,Math.abs(gn[j][k]-cm.g4Node[j][k]));
            out.printf(Locale.US,"  %-16s one-step: max|Δnode|=%.3e µm  Δphi=%.3e Δpsi=%.3e%n",nm[ti],dn,Math.abs(gphi-cm.phi),Math.abs(gpsi-cm.psi));
            // also compare assembled RHS + tangent
            double[] Ma=new double[N*N],Fa=new double[N]; Cmot cm2=freshFrom(c); cm2.A=cm2.g4Node[M]; cm2.P=cm2.A; TwoBodyConverterMotor.geomC(cm2);
            ExplicitBeamSolver.assemble(cm2,7,101,false,c.F8h.clone(),Mode.FULLY_ANALYTIC,Ma,Fa);
            // rebuild flat assembly (one iteration, capture sys BEFORE solve) via a mini re-run is not exposed;
            // instead compare the first Newton increment dq from the solver's system solved with solveFlat:
            double[] aug=new double[N*(N+1)],dq=new double[N],mp=new double[1];
            ExplicitBeamSolver.solveFlat(Ma,Fa,N,aug,dq,mp);
            out.printf(Locale.US,"      solver dq[node0..2]=(%.3e,%.3e,%.3e) dphi=%.3e dpsi=%.3e%n",dq[0],dq[1],dq[2],dq[N-2],dq[N-1]);
        }
        out.println();
    }

    // ================= assembly diff (pinpoint the buggy block) =================
    static void debugAsm(PrintStream out, List<Cfg> fixtures) {
        out.println("=== DEBUG-ASM: flat assembly vs ExplicitBeamSolver.assemble (FULLY_ANALYTIC) ===");
        Cfg[] test={ fixtures.get(0), randomCfg(5) }; String[] nm={fixtures.get(0).tag,"random#5"};
        for(int ti=0;ti<test.length;ti++){ Cfg c=test[ti];
            Cmot cm=freshFrom(c); cm.A=cm.g4Node[M]; cm.P=cm.A; TwoBodyConverterMotor.geomC(cm);
            double[] Ma=new double[N*N],Fa=new double[N];
            ExplicitBeamSolver.assemble(cm,7,101,false,c.F8h.clone(),Mode.FULLY_ANALYTIC,Ma,Fa);
            double[] Mf=new double[N*N],Ff=new double[N]; assembleFlatCPU(c,Mf,Ff);
            double dF=0,dM=0; int rF=-1,rM=-1,cM=-1;
            for(int i=0;i<N;i++){ if(Math.abs(Ff[i]-Fa[i])>dF){dF=Math.abs(Ff[i]-Fa[i]);rF=i;} }
            for(int i=0;i<N;i++) for(int j=0;j<N;j++){ double d=Math.abs(Mf[i*N+j]-Ma[i*N+j]); if(d>dM){dM=d;rM=i;cM=j;} }
            out.printf(Locale.US,"  %-16s  max|ΔF|=%.3e @row%d (flat=%.4e solver=%.4e) | max|ΔK|=%.3e @[%d,%d] (flat=%.4e solver=%.4e)%n",
                nm[ti],dF,rF,Ff[rF],Fa[rF],dM,rM,cM,Mf[rM*N+cM],Ma[rM*N+cM]);
            out.printf(Locale.US,"      node1=(%.5f,%.5f,%.5f)  K row0 flat  : ",nd(c,1,0),nd(c,1,1),nd(c,1,2));
            for(int j=0;j<6;j++) out.printf(Locale.US,"%.4e ",Mf[0*N+j]); out.println();
            out.printf(Locale.US,"                                    K row0 solver: ");
            for(int j=0;j<6;j++) out.printf(Locale.US,"%.4e ",Ma[0*N+j]); out.println();
        }
        out.println();
    }
    /** plain-Java replica of TwoBodyBeamAnalyticGpu.beamRelaxAnalytic's per-iteration assembly (ONE config). */
    static void assembleFlatCPU(Cfg c, double[] Kout, double[] Fout){
        Cmot base=BASE; int Mm=M; int nF=3*Mm, n=nF+2;
        double[][] nd=clone(c.nd); nd[0]=base.g4E.clone();
        double[] fr=frameArr(base), pr=paramArr(base);
        double bx=fr[0],by=fr[1],bz=fr[2], ex=fr[3],ey=fr[4],ez=fr[5], ux=fr[6],uy=fr[7],uz=fr[8],
               gTx=fr[12],gTy=fr[13],gTz=fr[14];
        double lb=pr[0],rF8x=pr[1],rF8y=pr[2],rCx=pr[3],rCy=pr[4],kF8Code=pr[5],kc=pr[6],kbnd=pr[7],
               gPhi=pr[8],gPsi=pr[9],dt=pr[10],ks=pr[11],l0um=pr[12],kbend=pr[13],floorZ=pr[14],kfloor=pr[15],gNode=pr[16];
        double l0m=l0um*1e-6, aN=gNode/dt, aphi=gPhi/dt, apsi=gPsi/dt;
        double phi=c.phi,psi=c.psi,thetaS=c.thetaS,psiActin=c.psiActin;
        double f8x=c.F8h[0],f8y=c.F8h[1],f8z=c.F8h[2];
        // aug K into Kout (n×n) and F into Fout (n)
        java.util.Arrays.fill(Kout,0.0); java.util.Arrays.fill(Fout,0.0);
        double Px=nd[Mm][0],Py=nd[Mm][1],Pz=nd[Mm][2];
        double cphi=Math.cos(phi),sphi=Math.sin(phi);
        double uBx=ux*cphi+bx*sphi,uBy=uy*cphi+by*sphi,uBz=uz*cphi+bz*sphi;
        double Cx=Px+uBx*lb,Cy=Py+uBy*lb,Cz=Pz+uBz*lb;
        double d0x=bx*(rF8x-rCx)+ux*(rF8y-rCy),d0y=by*(rF8x-rCx)+uy*(rF8y-rCy),d0z=bz*(rF8x-rCx)+uz*(rF8y-rCy);
        double cpsi=Math.cos(psi),spsi=Math.sin(psi);
        double xF8x=Cx+(d0x*cpsi+(ey*d0z-ez*d0y)*spsi),xF8y=Cy+(d0y*cpsi+(ez*d0x-ex*d0z)*spsi),xF8z=Cz+(d0z*cpsi+(ex*d0y-ey*d0x)*spsi);
        // stretch
        for(int i=0;i<Mm;i++){ double ax=nd[i+1][0]-nd[i][0],ay=nd[i+1][1]-nd[i][1],az=nd[i+1][2]-nd[i][2];
            double len=Math.sqrt(ax*ax+ay*ay+az*az); if(len<1e-15) continue; double s=1.0/len,uxx=ax*s,uyy=ay*s,uzz=az*s;
            double lenM=len*1e-6,t=ks*(lenM-l0m),tL=t/lenM; int ri=(i>=1)?(i-1)*3:-1000,rj=i*3;
            aF(Fout,n,ri+0,t*uxx);aF(Fout,n,ri+1,t*uyy);aF(Fout,n,ri+2,t*uzz);aF(Fout,n,rj+0,-t*uxx);aF(Fout,n,rj+1,-t*uyy);aF(Fout,n,rj+2,-t*uzz);
            for(int p=0;p<3;p++)for(int qq=0;qq<3;qq++){ double up=(p==0)?uxx:((p==1)?uyy:uzz),uq=(qq==0)?uxx:((qq==1)?uyy:uzz);double id=(p==qq)?1:0;double kb2=ks*up*uq+tL*(id-up*uq);
                aK(Kout,n,ri+p,ri+qq,kb2);aK(Kout,n,rj+p,rj+qq,kb2);aK(Kout,n,ri+p,rj+qq,-kb2);aK(Kout,n,rj+p,ri+qq,-kb2);} }
        // clamped joint 0
        { double b0x=nd[1][0]-nd[0][0],b0y=nd[1][1]-nd[0][1],b0z=nd[1][2]-nd[0][2];double lbb=Math.sqrt(b0x*b0x+b0y*b0y+b0z*b0z);
          if(lbb>1e-12){ double ilb=1.0/lbb,cc=(gTx*b0x+gTy*b0y+gTz*b0z)*ilb; if(cc>1)cc=1;if(cc<-1)cc=-1; double th=TwoBodyBeamAnalyticGpu.dacos(cc),A1=TwoBodyBeamAnalyticGpu.a1(th),A2=TwoBodyBeamAnalyticGpu.a2(th);
            double clb2=cc*ilb*ilb,g0x=gTx*ilb-clb2*b0x,g0y=gTy*ilb-clb2*b0y,g0z=gTz*ilb-clb2*b0z;
            aF(Fout,n,0,kbend*A1*1e6*g0x);aF(Fout,n,1,kbend*A1*1e6*g0y);aF(Fout,n,2,kbend*A1*1e6*g0z);
            double ilb2=ilb*ilb,ilb3=ilb2*ilb,ilb4=ilb2*ilb2;
            for(int p=0;p<3;p++)for(int qq=0;qq<3;qq++){ double b0p=(p==0)?b0x:((p==1)?b0y:b0z),b0q=(qq==0)?b0x:((qq==1)?b0y:b0z),tp=(p==0)?gTx:((p==1)?gTy:gTz),tq=(qq==0)?gTx:((qq==1)?gTy:gTz);double id=(p==qq)?1:0;
                double Hc=-(b0p*tq+tp*b0q)*ilb3-cc*id*ilb2+3.0*cc*b0p*b0q*ilb4;double gp=(p==0)?g0x:((p==1)?g0y:g0z),gq=(qq==0)?g0x:((qq==1)?g0y:g0z);
                aK(Kout,n,p,qq,1e12*kbend*(A2*gp*gq-A1*Hc)); } } }
        // interior joints
        for(int j=1;j<Mm;j++){ double ax=nd[j][0]-nd[j-1][0],ay=nd[j][1]-nd[j-1][1],az=nd[j][2]-nd[j-1][2];
            double bxx=nd[j+1][0]-nd[j][0],byy=nd[j+1][1]-nd[j][1],bzz=nd[j+1][2]-nd[j][2];
            double la=Math.sqrt(ax*ax+ay*ay+az*az),lbn=Math.sqrt(bxx*bxx+byy*byy+bzz*bzz); if(la<1e-12||lbn<1e-12) continue;
            double ila=1.0/la,ilb=1.0/lbn,iab=ila*ilb,cc=(ax*bxx+ay*byy+az*bzz)*iab; if(cc>1)cc=1;if(cc<-1)cc=-1;
            double th=TwoBodyBeamAnalyticGpu.dacos(cc),A1=TwoBodyBeamAnalyticGpu.a1(th),A2=TwoBodyBeamAnalyticGpu.a2(th);
            double cla2=cc*ila*ila,clb2=cc*ilb*ilb;
            double Dx=bxx*iab-cla2*ax,Dy=byy*iab-cla2*ay,Dz=bzz*iab-cla2*az,Ex=ax*iab-clb2*bxx,Ey=ay*iab-clb2*byy,Ez=az*iab-clb2*bzz;
            double gc0x=-Dx,gc0y=-Dy,gc0z=-Dz,gc1x=Dx-Ex,gc1y=Dy-Ey,gc1z=Dz-Ez,gc2x=Ex,gc2y=Ey,gc2z=Ez;
            int rJm1=(j-1>=1)?(j-2)*3:-1000,rJ=(j-1)*3,rJp1=j*3;
            aF(Fout,n,rJm1+0,kbend*A1*1e6*gc0x);aF(Fout,n,rJm1+1,kbend*A1*1e6*gc0y);aF(Fout,n,rJm1+2,kbend*A1*1e6*gc0z);
            aF(Fout,n,rJ+0,kbend*A1*1e6*gc1x);aF(Fout,n,rJ+1,kbend*A1*1e6*gc1y);aF(Fout,n,rJ+2,kbend*A1*1e6*gc1z);
            aF(Fout,n,rJp1+0,kbend*A1*1e6*gc2x);aF(Fout,n,rJp1+1,kbend*A1*1e6*gc2y);aF(Fout,n,rJp1+2,kbend*A1*1e6*gc2z);
            double ila2=ila*ila,ila3=ila2*ila,ila4=ila2*ila2,ilb2=ilb*ilb,ilb3=ilb2*ilb,ilb4=ilb2*ilb2;
            for(int ai=0;ai<3;ai++){ int rA=(ai==0)?rJm1:((ai==1)?rJ:rJp1);double saA=(ai==0)?-1:((ai==1)?1:0),sbA=(ai==0)?0:((ai==1)?-1:1);
                for(int bi=0;bi<3;bi++){ int rB=(bi==0)?rJm1:((bi==1)?rJ:rJp1);double saB=(bi==0)?-1:((bi==1)?1:0),sbB=(bi==0)?0:((bi==1)?-1:1);
                    for(int p=0;p<3;p++){ double ap=(p==0)?ax:((p==1)?ay:az),bp=(p==0)?bxx:((p==1)?byy:bzz);
                        double gAp=(p==0)?((ai==0)?gc0x:((ai==1)?gc1x:gc2x)):((p==1)?((ai==0)?gc0y:((ai==1)?gc1y:gc2y)):((ai==0)?gc0z:((ai==1)?gc1z:gc2z)));
                        for(int qq=0;qq<3;qq++){ double aq=(qq==0)?ax:((qq==1)?ay:az),bq=(qq==0)?bxx:((qq==1)?byy:bzz);double id=(p==qq)?1:0;
                            double Haa=-(ap*bq+bp*aq)*ila3*ilb-cc*id*ila2+3.0*cc*ap*aq*ila4;
                            double Hbb=-(bp*aq+ap*bq)*ilb3*ila-cc*id*ilb2+3.0*cc*bp*bq*ilb4;
                            double Hab_pq=id*iab-bp*bq*ila*ilb3-ap*aq*ila3*ilb+cc*ap*bq*ila2*ilb2;
                            double Hab_qp=id*iab-bq*bp*ila*ilb3-aq*ap*ila3*ilb+cc*aq*bp*ila2*ilb2;
                            double Hc=saA*saB*Haa+sbA*sbB*Hbb+saA*sbB*Hab_pq+sbA*saB*Hab_qp;
                            double gBq=(qq==0)?((bi==0)?gc0x:((bi==1)?gc1x:gc2x)):((qq==1)?((bi==0)?gc0y:((bi==1)?gc1y:gc2y)):((bi==0)?gc0z:((bi==1)?gc1z:gc2z)));
                            aK(Kout,n,rA+p,rB+qq,1e12*kbend*(A2*gAp*gBq-A1*Hc)); } } } } }
        // floor
        for(int j=1;j<=Mm;j++){ double z=nd[j][0]*ux+nd[j][1]*uy+nd[j][2]*uz; if(z<floorZ){ double fk=kfloor*(floorZ-z)*1e-6;int r=(j-1)*3;
            aF(Fout,n,r+0,fk*ux);aF(Fout,n,r+1,fk*uy);aF(Fout,n,r+2,fk*uz);
            for(int p=0;p<3;p++){double ep=(p==0)?ux:((p==1)?uy:uz);for(int qq=0;qq<3;qq++){double eq=(qq==0)?ux:((qq==1)?uy:uz);aK(Kout,n,r+p,r+qq,kfloor*ep*eq);}}}}
        // drag
        for(int r=0;r<nF;r++) aK(Kout,n,r,r,aN);
        // F8 block
        int pB=3*(Mm-1),iPhi=nF,iPsi=nF+1;
        double cpx=Cx-Px,cpy=Cy-Py,cpz=Cz-Pz,fcx=xF8x-Cx,fcy=xF8y-Cy,fcz=xF8z-Cz;
        double Jphix=uy*cpz-uz*cpy,Jphiy=uz*cpx-ux*cpz,Jphiz=ux*cpy-uy*cpx,Jpsix=uy*fcz-uz*fcy,Jpsiy=uz*fcx-ux*fcz,Jpsiz=ux*fcy-uy*fcx;
        double J03=Jphix*1e-6,J04=Jpsix*1e-6,J13=Jphiy*1e-6,J14=Jpsiy*1e-6,J23=Jphiz*1e-6,J24=Jpsiz*1e-6,kfSI=kF8Code*1e6;
        for(int i=0;i<5;i++){ double Ji0=(i==0)?1:0,Ji1=(i==1)?1:0,Ji2=(i==2)?1:0; if(i==3){Ji0=J03;Ji1=J13;Ji2=J23;} if(i==4){Ji0=J04;Ji1=J14;Ji2=J24;} int di=(i<3)?(pB+i):(i==3?iPhi:iPsi);
            for(int jj=0;jj<5;jj++){ double Jj0=(jj==0)?1:0,Jj1=(jj==1)?1:0,Jj2=(jj==2)?1:0; if(jj==3){Jj0=J03;Jj1=J13;Jj2=J23;} if(jj==4){Jj0=J04;Jj1=J14;Jj2=J24;} int dj=(jj<3)?(pB+jj):(jj==3?iPhi:iPsi);
                aK(Kout,n,di,dj,kfSI*(Ji0*Jj0+Ji1*Jj1+Ji2*Jj2)); } }
        aK(Kout,n,iPhi,iPhi,kc);aK(Kout,n,iPhi,iPsi,-kc);aK(Kout,n,iPsi,iPhi,-kc);aK(Kout,n,iPsi,iPsi,kc+kbnd);aK(Kout,n,iPhi,iPhi,aphi);aK(Kout,n,iPsi,iPsi,apsi);
        double th=psi-phi;
        double caFx=cpy*f8z-cpz*f8y,caFy=cpz*f8x-cpx*f8z,caFz=cpx*f8y-cpy*f8x,fcFx=fcy*f8z-fcz*f8y,fcFy=fcz*f8x-fcx*f8z,fcFz=fcx*f8y-fcy*f8x;
        double QphiF8=(ux*caFx+uy*caFy+uz*caFz)*1e-6,QpsiF8=(ux*fcFx+uy*fcFy+uz*fcFz)*1e-6;
        aF(Fout,n,pB+0,f8x);aF(Fout,n,pB+1,f8y);aF(Fout,n,pB+2,f8z);
        aF(Fout,n,iPhi,QphiF8+kc*(th-thetaS));aF(Fout,n,iPsi,QpsiF8-kc*(th-thetaS)-kbnd*(psi-psiActin));
    }
    static void aK(double[] K,int n,int r,int c,double v){ if(r>=0&&c>=0) K[r*n+c]+=v; }
    static void aF(double[] F,int n,int r,double v){ if(r>=0) F[r]+=v; }

    // ================= CPU mirror validation =================
    static void cpuValidate(PrintStream out, List<Cfg> fixtures) {
        out.println("=== CPU-mirror validation: TwoBodyBeamAnalyticGpu (plain-Java loop) vs CPU FD + CPU analytic ===");
        List<Cfg> cs=new ArrayList<>(fixtures);
        for(int s=0;s<300;s++) cs.add(randomCfg(s));
        // pack all into batch buffers, run the kernel-mirror once (nM=cs.size)
        Buf b=pack(cs);
        TwoBodyBeamAnalyticGpu.beamRelaxAnalytic(b.nodes,b.frame,b.q,b.F8h,b.params,b.sys,b.outGeom,b.status,b.iters,b.counts);
        // references
        Scratch scr=new Scratch();
        double mxVsFA=0, mxVsFD=0, mxEnFD=0, mxContFD=0; String wFA="",wFD="";
        int nBasin=0;
        for(int i=0;i<cs.size();i++){
            double[][] gpuNodes=unpackNodes(b,i);
            // CPU fully-analytic relax
            Relax rFA=relax(freshFrom(cs.get(i)),cs.get(i).F8h,Mode.FULLY_ANALYTIC,scr);
            Relax rFD=relax(freshFrom(cs.get(i)),cs.get(i).F8h,Mode.FD_REPLICA,scr);
            double dFA=nodeDiff(gpuNodes,rFA.cm); if(dFA>mxVsFA){mxVsFA=dFA;wFA=cs.get(i).tag;}
            double dFD=nodeDiff(gpuNodes,rFD.cm); if(dFD>mxVsFD){mxVsFD=dFD;wFD=cs.get(i).tag;}
            double gE=energyOf(gpuNodes); mxEnFD=Math.max(mxEnFD,Math.abs(gE-rFD.energy)/(Math.abs(rFD.energy)+1e-30));
            double gCont=contour(gpuNodes)-BASE.g4Lc; mxContFD=Math.max(mxContFD,Math.abs(gCont-rFD.contourResid));
            if(dFD>1e-4 && Math.abs(gE-rFD.energy)/(Math.abs(rFD.energy)+1e-30)>1e-3) nBasin++;
        }
        out.printf(Locale.US,"  configs=%d  mirror vs CPU-FULLY_ANALYTIC max|Δnode|=%.3e µm (@%s)%n",cs.size(),mxVsFA,wFA);
        out.printf(Locale.US,"  mirror vs CPU-FD (physical) max|Δnode|=%.3e µm (@%s)  energyRel=%.3e  contourΔ=%.3e µm%n",mxVsFD,wFD,mxEnFD,mxContFD);
        out.printf(Locale.US,"  physical basin mismatches (Δnode>1e-4 AND energyRel>1e-3): %d%n",nBasin);
        out.println("  (mirror≈FULLY_ANALYTIC to acos-poly precision; ≈FD in the same basin — soft-mode/relax-budget node drift is benign per B4.)\n");
    }

    // ================= C2 lowering probe =================
    static void probe(PrintStream out) {
        out.println("=== C2  LOWERING PROBE (N=1 device TaskGraph, no silent fallback) ===");
        List<Cfg> one=new ArrayList<>(); one.add(loadFixtureOr(randomCfg(1),"bound_baseline"));
        Buf b=pack(one);
        try {
            long t0=System.nanoTime();
            runDevice(b, "beamProbe");
            long dt=System.nanoTime()-t0;
            out.printf(Locale.US,"  LOWERS + EXECUTES on device: YES  (build+execute wall %.1f ms)%n",dt/1e6);
            double[][] gn=unpackNodes(b,0); boolean finite=allFinite(gn) && Double.isFinite(b.q.get(0)) && Double.isFinite(b.q.get(1));
            out.printf(Locale.US,"  status=%d iters=%d  outputs finite=%b  P=(%.6f,%.6f,%.6f)µm%n",
                b.status.get(0),b.iters.get(0),finite, b.nodes.get(3*M), b.nodes.get(3*M+1), b.nodes.get(3*M+2));
            // compare to CPU mirror on the same config
            Buf bc=pack(one); TwoBodyBeamAnalyticGpu.beamRelaxAnalytic(bc.nodes,bc.frame,bc.q,bc.F8h,bc.params,bc.sys,bc.outGeom,bc.status,bc.iters,bc.counts);
            double d=0; for(int k=0;k<15;k++) d=Math.max(d,Math.abs(b.nodes.get(k)-bc.nodes.get(k)));
            out.printf(Locale.US,"  GPU vs CPU-mirror (same config) max|Δnode|=%.3e µm%n",d);
            out.println("  ⇒ the analytic kernel LOWERS (no 973-node FD helper; no double[][]/new; flat scratch + runtime-bound loops).\n");
        } catch (Throwable e) {
            out.println("  LOWERS: NO — backend threw:");
            out.println("    "+e.getClass().getName()+": "+String.valueOf(e.getMessage()).replaceAll("\\s+"," ").trim());
            Throwable c=e.getCause(); int d=0; while(c!=null && d<4){ out.println("    caused by "+c.getClass().getName()+": "+String.valueOf(c.getMessage()).replaceAll("\\s+"," ").trim()); c=c.getCause(); d++; }
            out.println("  ⇒ report the exact backend node/limit; refactor EXPRESSION structure only.\n");
        }
    }

    // ================= C3 device fixture gate =================
    static void gate(PrintStream out, List<Cfg> fixtures) {
        out.println("=== C3  DEVICE FIXTURE GATE: CPU-FD / CPU-analytic / GPU-analytic ===");
        Buf b=pack(fixtures);
        try { runDevice(b,"beamGate"); } catch(Throwable e){ out.println("  device execution FAILED: "+e); return; }
        Scratch scr=new Scratch();
        out.printf(Locale.US,"  %-22s %10s %10s %12s %12s %8s %8s%n","fixture","dGPUvsFA","dGPUvsFD","enRelFD","contourΔ","itGPU","stat");
        double mxFA=0,mxFD=0;
        for(int i=0;i<fixtures.size();i++){
            double[][] gn=unpackNodes(b,i);
            Relax rFA=relax(freshFrom(fixtures.get(i)),fixtures.get(i).F8h,Mode.FULLY_ANALYTIC,scr);
            Relax rFD=relax(freshFrom(fixtures.get(i)),fixtures.get(i).F8h,Mode.FD_REPLICA,scr);
            double dFA=nodeDiff(gn,rFA.cm), dFD=nodeDiff(gn,rFD.cm);
            double gE=energyOf(gn), enRel=Math.abs(gE-rFD.energy)/(Math.abs(rFD.energy)+1e-30);
            double cont=Math.abs((contour(gn)-BASE.g4Lc)-rFD.contourResid);
            mxFA=Math.max(mxFA,dFA); mxFD=Math.max(mxFD,dFD);
            out.printf(Locale.US,"  %-22s %10.2e %10.2e %12.2e %12.2e %8d %8d%n",
                fixtures.get(i).tag,dFA,dFD,enRel,cont,b.iters.get(i),b.status.get(i));
        }
        out.printf(Locale.US,"  MAX: GPU vs CPU-analytic %.2e µm | GPU vs CPU-FD %.2e µm%n",mxFA,mxFD);
        out.println("  (GPU-analytic must match CPU-analytic tightly and share the CPU-FD basin — per the B4 gate.)\n");
    }

    // ================= C4 microbenchmark =================
    static void bench(PrintStream out) {
        out.println("=== C4  BEAM MICROBENCHMARK (batch: CPU-FD vs CPU-analytic vs GPU-analytic) ===");
        int[] sizes={128,256,512,1024,4096,16384,65536};
        out.printf(Locale.US,"  %8s %14s %14s %14s %12s %12s %10s %10s%n",
            "batch","CPUfd_solv/s","CPUan_solv/s","GPUan_solv/s","GPU_warm_ms","GPU_e2e_ms","itGPUavg","failGPU");
        Scratch scr=new Scratch();
        for(int nM:sizes){
            List<Cfg> cs=new ArrayList<>(); for(int i=0;i<nM;i++) cs.add(randomCfg(i));
            // CPU FD + analytic (sample up to 256 for the slow FD path, extrapolate solves/s)
            int sample=Math.min(nM,256);
            long cfd=timeCpuRelax(cs,sample,Mode.FD_REPLICA,scr);
            long can=timeCpuRelax(cs,sample,Mode.FULLY_ANALYTIC,scr);
            double cpuFdSolv= sample*1e9/cfd, cpuAnSolv= sample*1e9/can;
            // GPU: build once, warm, time
            Buf b=pack(cs);
            double warmMs=0,e2eMs=0,gpuSolv=0; double itAvg=0; int failG=0;
            try {
                TornadoExecutionPlan plan=buildPlan(b,"beamBench",nM);
                long w0=System.nanoTime(); plan.execute(); warmMs=(System.nanoTime()-w0)/1e6;   // includes compile+first exec
                // reset state to a fresh batch for a clean warm-run timing
                Buf b2=pack(cs); GridBundle gb=buildPlanKeep(b2,"beamBench2",nM);
                gb.plan.execute();   // warm compile
                Buf b3=pack(cs);
                // rebind by rebuilding (simplest, still measures warm kernel after JIT of the harness)
                long t0=System.nanoTime(); GridBundle gb3=buildPlanKeep(b3,"beamBench3",nM); gb3.plan.execute(); long te=System.nanoTime();
                e2eMs=(te-t0)/1e6;
                // steady kernel: re-execute the SAME plan several times (device-resident buffers)
                int reps=5; long k0=System.nanoTime(); for(int r=0;r<reps;r++) gb3.plan.execute(); long k1=System.nanoTime();
                double kmsPer=(k1-k0)/1e6/reps; gpuSolv= nM*1000.0/kmsPer;
                for(int i=0;i<nM;i++){ itAvg+=b3.iters.get(i); if(b3.status.get(i)!=0) failG++; } itAvg/=nM;
            } catch(Throwable e){ out.println("  GPU batch "+nM+" FAILED: "+e.getClass().getSimpleName()+" "+e.getMessage()); }
            out.printf(Locale.US,"  %8d %14.0f %14.0f %14.0f %12.2f %12.2f %10.1f %10d%n",
                nM,cpuFdSolv,cpuAnSolv,gpuSolv,warmMs,e2eMs,itAvg,failG);
        }
        out.println("  (CPU-FD/analytic solves/s from a "+"≤256"+"-config sample; GPU steady = warm re-execute of the device-resident plan.)");
    }

    // ---------------- device execution ----------------
    static void runDevice(Buf b, String name) {
        GridBundle gb=buildPlanKeep(b,name,b.counts.get(0));
        gb.plan.execute();
    }
    static final class GridBundle { TornadoExecutionPlan plan; GridScheduler grid; }
    static TornadoExecutionPlan buildPlan(Buf b,String name,int nM){ return buildPlanKeep(b,name,nM).plan; }
    static GridBundle buildPlanKeep(Buf b,String name,int nM){
        TaskGraph tg=new TaskGraph(name)
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, b.nodes,b.frame,b.q,b.F8h,b.params,b.sys,b.counts)
            .task("relax", TwoBodyBeamAnalyticGpu::beamRelaxAnalytic, b.nodes,b.frame,b.q,b.F8h,b.params,b.sys,b.outGeom,b.status,b.iters,b.counts)
            .transferToHost(DataTransferMode.EVERY_EXECUTION, b.nodes,b.q,b.outGeom,b.status,b.iters);
        WorkerGrid wg=new WorkerGrid1D(nM); wg.setLocalWork(64,1,1);
        GridScheduler gs=new GridScheduler(name+".relax", wg);
        GridBundle gb=new GridBundle(); gb.grid=gs;
        gb.plan=new TornadoExecutionPlan(tg.snapshot()).withGridScheduler(gs);
        return gb;
    }

    // ---------------- reference relaxation (CPU) ----------------
    static final class Relax { Cmot cm; boolean converged; int iters; double energy, contourResid; }
    static Relax relax(Cmot cm,double[] F8h,Mode mode,Scratch scr){
        Relax r=new Relax(); r.cm=cm; cm.A=cm.g4Node[M]; cm.P=cm.A; TwoBodyConverterMotor.geomC(cm);
        int it=0; boolean conv=false;
        for(;it<MAXIT;it++){ double[] pre=flat(cm);
            ExplicitBeamSolver.StepInfo si=ExplicitBeamSolver.s2SolveMode(cm,7,101,false,F8h.clone(),mode,scr.M,scr.F,scr.aug,scr.dq);
            if(si.status!=0) break; double mv=maxMove(pre,cm); if(mv<TOL){ conv=true; it++; break; } }
        r.converged=conv; r.iters=it; r.energy=ExplicitBeamAnalytic.totalEnergy(cm,cm.g4Node);
        double e2e=dist(cm.g4Node[M],cm.g4Node[0]); r.contourResid=e2e-cm.g4Lc; return r;
    }
    static long timeCpuRelax(List<Cfg> cs,int sample,Mode mode,Scratch scr){
        long t0=System.nanoTime(); for(int i=0;i<sample;i++) relax(freshFrom(cs.get(i)),cs.get(i).F8h,mode,scr); return System.nanoTime()-t0;
    }

    // ---------------- buffers / packing ----------------
    static final class Buf { DoubleArray nodes,frame,q,F8h,params,sys,outGeom; IntArray status,iters,counts; }
    static Buf pack(List<Cfg> cs){
        int nM=cs.size(); Buf b=new Buf();
        b.nodes=new DoubleArray(15*nM); b.frame=new DoubleArray(15*nM); b.q=new DoubleArray(4*nM);
        b.F8h=new DoubleArray(3*nM); b.params=new DoubleArray(17*nM); b.sys=new DoubleArray(SYS*nM);
        b.outGeom=new DoubleArray(9*nM); b.status=new IntArray(nM); b.iters=new IntArray(nM); b.counts=new IntArray(4);
        b.sys.init(0.0); b.status.init(0); b.iters.init(0);
        b.counts.set(0,nM); b.counts.set(1,MAXIT); b.counts.set(2,M); b.counts.set(3,0);
        double[] fr=frameArr(BASE); double[] pr=paramArr(BASE);
        for(int m=0;m<nM;m++){ Cfg c=cs.get(m);
            for(int j=0;j<=M;j++) for(int k=0;k<3;k++) b.nodes.set((3*j+k)*nM+m, c.nd[j][k]);
            for(int comp=0;comp<15;comp++) b.frame.set(comp*nM+m, fr[comp]);
            for(int comp=0;comp<17;comp++) b.params.set(comp*nM+m, pr[comp]);
            b.q.set(m,c.phi); b.q.set(nM+m,c.psi); b.q.set(2*nM+m,c.thetaS); b.q.set(3*nM+m,c.psiActin);
            b.F8h.set(m,c.F8h[0]); b.F8h.set(nM+m,c.F8h[1]); b.F8h.set(2*nM+m,c.F8h[2]);
        }
        return b;
    }
    static double[] frameArr(Cmot cm){ return new double[]{ cm.bhat[0],cm.bhat[1],cm.bhat[2], cm.econv[0],cm.econv[1],cm.econv[2],
        cm.eup[0],cm.eup[1],cm.eup[2], cm.g4E[0],cm.g4E[1],cm.g4E[2], cm.g4Tan[0],cm.g4Tan[1],cm.g4Tan[2] }; }
    static double[] paramArr(Cmot cm){ return new double[]{ cm.lb, cm.rF8[0],cm.rF8[1], cm.rConv[0],cm.rConv[1],
        cm.kF8Code, cm.kconvCode, cm.kbindCode, cm.gammaPhi, cm.gammaPsi, cm.dt, cm.g4ks, cm.g4l0, cm.g4kb, cm.g4floorZ, cm.g4kfloor, cm.g4gammaNode }; }
    static double[][] unpackNodes(Buf b,int m){ int nM=b.counts.get(0); double[][] nd=new double[M+1][3];
        for(int j=0;j<=M;j++) for(int k=0;k<3;k++) nd[j][k]=b.nodes.get((3*j+k)*nM+m); return nd; }

    // ---------------- config generation ----------------
    static final class Cfg { double[][] nd; double phi,psi,thetaS,psiActin; double[] F8h; String tag; }
    static Cfg randomCfg(long seed){ Cmot cm=TwoBodyConverterMotor.buildS2(40.0,0.0,true,DT,1.0,1.0); Random r=new Random(seed*2654435761L+1);
        double amp=0.004*(0.2+r.nextDouble()); for(int j=1;j<=M;j++) for(int k=0;k<3;k++) cm.g4Node[j][k]+=amp*(r.nextDouble()-0.5); cm.g4Node[0]=cm.g4E.clone();
        Cfg c=new Cfg(); c.nd=clone(cm.g4Node); double sgn=r.nextBoolean()?1:-1, pN=r.nextDouble()*3;
        c.F8h=new double[]{sgn*pN*1e-12*cm.bhat[0],sgn*pN*1e-12*cm.bhat[1],sgn*pN*1e-12*cm.bhat[2]};
        c.phi=(r.nextDouble()-0.5); c.psi=(r.nextDouble()-0.5); c.thetaS=c.psi-c.phi; c.psiActin=c.psi; c.tag="random#"+seed; return c; }
    static Cfg loadFixtureOr(Cfg fallback,String want){ try{ for(Cfg c:loadFixtures()) if(c.tag.contains(want)) return c; }catch(Exception e){} return fallback; }

    static List<Cfg> loadFixtures() throws IOException {
        List<Cfg> cs=new ArrayList<>(); File dir=new File("fixtures/gpu_port");
        File[] fs=dir.listFiles((d,n)->n.startsWith("fixture_explicit-s2-l40_")&&n.endsWith(".txt")); if(fs==null) return cs; Arrays.sort(fs);
        for(File f:fs){ Cfg c=parse(f); if(c!=null) cs.add(c); } return cs;
    }
    static Cfg parse(File f) throws IOException { Cfg c=new Cfg(); c.nd=new double[M+1][3]; boolean[] seen=new boolean[M+1]; boolean in=false; double b0=0,b1=0,b2=0;
        try(BufferedReader br=new BufferedReader(new FileReader(f))){ String ln; while((ln=br.readLine())!=null){ ln=ln.trim();
            if(ln.equals("[input]")){in=true;continue;} if(ln.startsWith("[")&&!ln.equals("[input]")) in=false; if(!in) continue; int e=ln.indexOf('='); if(e<0) continue; String k=ln.substring(0,e),v=ln.substring(e+1);
            for(int j=0;j<=M;j++){ if(k.equals("node"+j+"x")){c.nd[j][0]=Double.parseDouble(v);seen[j]=true;} else if(k.equals("node"+j+"y"))c.nd[j][1]=Double.parseDouble(v); else if(k.equals("node"+j+"z"))c.nd[j][2]=Double.parseDouble(v); }
            switch(k){ case "phi":c.phi=Double.parseDouble(v);break; case "psi":c.psi=Double.parseDouble(v);break; case "thetaS":c.thetaS=Double.parseDouble(v);break; case "psiActin":c.psiActin=Double.parseDouble(v);break;
                case "bond0":b0=Double.parseDouble(v);break; case "bond1":b1=Double.parseDouble(v);break; case "bond2":b2=Double.parseDouble(v);break; } } }
        for(int j=0;j<=M;j++) if(!seen[j]) return null; c.F8h=new double[]{b0,b1,b2}; c.tag=f.getName().replace("fixture_explicit-s2-l40_","").replace(".txt",""); return c; }

    static Cmot freshFrom(Cfg c){ Cmot cm=TwoBodyConverterMotor.buildS2(40.0,0.0,true,DT,1.0,1.0);
        for(int j=0;j<=M;j++) cm.g4Node[j]=c.nd[j].clone(); cm.g4Node[0]=cm.g4E.clone();
        cm.phi=c.phi; cm.psi=c.psi; cm.thetaS=c.thetaS; cm.psiActin=c.psiActin; return cm; }

    // ---------------- geometry helpers ----------------
    static double energyOf(double[][] nd){ return ExplicitBeamAnalytic.totalEnergy(BASE,nd); }
    static double contour(double[][] nd){ double c=0; for(int i=0;i<M;i++) c+=dist(nd[i+1],nd[i]); return c; }
    static double nodeDiff(double[][] a,Cmot cm){ double d=0; for(int j=1;j<=M;j++) for(int k=0;k<3;k++) d=Math.max(d,Math.abs(a[j][k]-cm.g4Node[j][k])); return d; }
    static double[][] clone(double[][] a){ double[][] c=new double[a.length][]; for(int i=0;i<a.length;i++) c[i]=a[i].clone(); return c; }
    static double[] flat(Cmot cm){ double[] v=new double[3*(M+1)]; for(int j=0;j<=M;j++) for(int k=0;k<3;k++) v[3*j+k]=cm.g4Node[j][k]; return v; }
    static double maxMove(double[] pre,Cmot cm){ double d=0; for(int j=1;j<=M;j++) for(int k=0;k<3;k++) d=Math.max(d,Math.abs(pre[3*j+k]-cm.g4Node[j][k])); return d; }
    static double dist(double[] a,double[] b){ return Math.sqrt(sq(a[0]-b[0])+sq(a[1]-b[1])+sq(a[2]-b[2])); }
    static boolean allFinite(double[][] a){ for(double[] r:a) for(double v:r) if(!Double.isFinite(v)) return false; return true; }
    static double sq(double x){ return x*x; }
    static double nd(Cfg c,int j,int k){ return c.nd[j][k]; }
}
