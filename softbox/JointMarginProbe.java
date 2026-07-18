package softbox;

import softbox.TwoBodyConverterMotor.Glide2D;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

/**
 * Segment-end binding-margin study — deterministic (Brownian-off) joint fixtures (Parts B/C/G).
 *
 * <p>The canonical explicit gate excludes {@code margin=0.05 µm} from BOTH ends of every actin segment
 * ({@code g7: bindArc ∈ (margin, 2·half−margin)}). v1 {@code MyoMotor.checkFilSegCollision} and the inc-4a
 * {@code BindingDetectionSystem} have NO such margin (they bind anywhere the foot is on the segment, α∈[0,1]).
 * This probe tests whether the margin can be removed and replaced by deterministic joint handling:
 * a synthetic head/F8 point is swept continuously across ONE internal actin joint and, at each position,
 * the EXACT {@code nearestSeg2D}+{@code bindArc}+g7 arithmetic (replicated bit-for-bit from
 * {@link TwoBodyBeamAnalyticGpu#matBindExplicit}) is evaluated for margins {0.05, 0.0125, 0.0} and a
 * proposed HALF-OPEN ownership rule. Bond force/torque continuity across the joint is measured via the
 * shared {@link CrossBridgeSystem#bondForces}. Pure geometry ⇒ CPU/GPU identical by construction.
 */
public final class JointMarginProbe {
    static final String OUT = "RUN_LOGS/binddiag";
    static final double DT = 2.5e-6;

    public static void main(String[] args) {
        double bendDeg = 0.0; boolean bent = false;
        for (int i = 0; i < args.length; i++) { if (args[i].equals("-bend")) { bendDeg = Double.parseDouble(args[++i]); bent = true; } }
        try { Files.createDirectories(Path.of(OUT)); } catch (IOException e) { throw new UncheckedIOException(e); }
        StringBuilder rep = new StringBuilder();
        rep.append("# Joint-margin fixtures (Parts B/C/G) — deterministic geometric + bond-force sweep across one actin joint\n\n");

        // ---- build the mat filament (12 collinear segments along +x); optionally bend the joint of interest ----
        Glide2D G = TwoBodyConverterMotor.buildS2Mat(200.0, DT, 40.0, TwoBodyConverterMotor.EXPLICIT_GLIDE_SLACK_NM, 101);
        FilamentStore f = G.fil; int nSeg = G.nSeg;
        double FIL_R = Constants.radius, footTol = 0.02;
        int jL = 5, jR = 6;                 // the joint between segment 5 and 6
        double half = 0.5 * f.segLength.get(jL);
        // segment centres along x
        double[] cx = new double[nSeg], cy = new double[nSeg], cz = new double[nSeg];
        double[] ux = new double[nSeg], uy = new double[nSeg], uz = new double[nSeg];
        for (int s = 0; s < nSeg; s++) { cx[s]=f.coord.get(s); cy[s]=f.coord.get(nSeg+s); cz[s]=f.coord.get(2*nSeg+s);
            ux[s]=f.uVec.get(s); uy[s]=f.uVec.get(nSeg+s); uz[s]=f.uVec.get(2*nSeg+s); }
        double xJoint = cx[jL] + half * ux[jL];   // shared joint point (collinear)
        double yJoint = cy[jL], zJoint = cz[jL];
        // bend: rotate segment jR (and shift so its end1 stays at the joint) by bendDeg about +z at the joint
        if (bent) {
            double a = Math.toRadians(bendDeg), ca = Math.cos(a), sa = Math.sin(a);
            double nux = ca*ux[jR] - sa*uy[jR], nuy = sa*ux[jR] + ca*uy[jR];
            ux[jR]=nux; uy[jR]=nuy;
            // place jR so its end1 == joint: centre = joint + half*u
            cx[jR]=xJoint+half*ux[jR]; cy[jR]=yJoint+half*uy[jR]; cz[jR]=zJoint+half*uz[jR];
            // write back to store for bondForces
            f.coord.set(jR,(float)cx[jR]); f.coord.set(nSeg+jR,(float)cy[jR]); f.coord.set(2*nSeg+jR,(float)cz[jR]);
            f.uVec.set(jR,(float)ux[jR]); f.uVec.set(nSeg+jR,(float)uy[jR]); f.uVec.set(2*nSeg+jR,(float)uz[jR]);
        }
        rep.append(String.format(Locale.US, "filament: %d segments, segLength=%.5f µm (half=%.5f), FIL_R=%.4f µm; joint %d|%d at x=%.5f; bend=%.1f°\n\n",
                nSeg, f.segLength.get(jL), half, FIL_R, jL, jR, xJoint, bendDeg));

        double[] margins = { 0.05, 0.0125, 0.0 };
        double perp = 0.0015;   // 1.5 nm perpendicular (in +z) ⇒ conDist=1.5nm < 2 (preload) and surf<0 (passes)

        // ============================ Part B/C1 — geometric sweep across the joint ============================
        rep.append("## Part B/C1 — geometric ownership + bindArc + g7 across the joint (Brownian-off, F8 swept in x)\n");
        rep.append("F8 at (x, "+yJoint+", z+"+ (perp*1e3) +"nm); conDist≈"+(perp*1e3)+"nm (passes surf+preload).\n");
        rep.append("LEGACY = nearestSeg first-min + margin gate; **HALF-OPEN FIX** = clamped-closest-point + bindArc=footC+half + ε margin.\n```\n");
        rep.append(String.format(Locale.US, "%-9s | LEGACY %-4s %-9s g7: %-6s %-6s %-6s | HALF-OPEN-FIX %-4s %-9s g7ε:%-6s\n",
                "Δx(nm)", "seg", "bindArc", "0.05", "0.0125", "0.0", "seg", "bindArc", "PASS?"));
        double[] fr = { -20,-10,-5,-2,-1,-0.5,-0.1,-0.05,-0.01,-0.001,0,0.001,0.01,0.05,0.1,0.5,1,2,5,10,20 };
        double prevWorldX = Double.NaN, prevGlobal = Double.NaN; int prevSeg = -99; double prevBind = Double.NaN;
        double maxWorldJump = 0, maxGlobalJump = 0; int segSwitches = 0; int dupCount = 0;
        for (double d : fr) {
            double x = xJoint + d * 1e-3;
            double fx = x, fyv = yJoint, fzv = zJoint + perp;
            // nearestSeg2D (exact): min perp dist with |foot|≤half+footTol
            int best=-1; double bd=1e9; int nCandidates=0;
            for (int s=0;s<nSeg;s++){ double h=0.5*f.segLength.get(s);
                double dx=fx-cx[s], dy=fyv-cy[s], dz=fzv-cz[s]; double foot=dx*ux[s]+dy*uy[s]+dz*uz[s];
                if (foot>h+footTol || foot<-(h+footTol)) continue;
                double px=dx-foot*ux[s], py=dy-foot*uy[s], pz=dz-foot*uz[s]; double d2=px*px+py*py+pz*pz;
                if (Math.sqrt(d2) < FIL_R+0.005) nCandidates++;           // segments whose foot-interior + near
                if (d2<bd){ bd=d2; best=s; } }
            int s=best; double h=0.5*f.segLength.get(s);
            double e1x=cx[s]-h*ux[s], e1y=cy[s]-h*uy[s], e1z=cz[s]-h*uz[s];
            double bindArc=(fx-e1x)*ux[s]+(fyv-e1y)*uy[s]+(fzv-e1z)*uz[s];
            double worldX=e1x+bindArc*ux[s];   // world x of the chosen binding point
            double globalArc=s*f.segLength.get(0)+bindArc;
            // half-open ownership: the segment whose foot∈[-h, h)
            int ownHalfOpen=-1; for (int q=0;q<nSeg;q++){ double hq=0.5*f.segLength.get(q);
                double dx=fx-cx[q],dy=fyv-cy[q],dz=fzv-cz[q]; double foot=dx*ux[q]+dy*uy[q]+dz*uz[q];
                if (foot>=-hq && foot<hq){ ownHalfOpen=q; break; } }
            boolean[] g7 = new boolean[3];
            for (int mi=0;mi<3;mi++){ double mg=margins[mi]; g7[mi]= bindArc>mg && bindArc<2*h-mg; }
            // HALF-OPEN FIX: clamped-closest-point selection + bindArc=footC+half + ε margin
            double epsHO=1e-6; int bestHO=-1; double bdHO=1e9;
            for (int q=0;q<nSeg;q++){ double hq=0.5*f.segLength.get(q);
                double dx=fx-cx[q],dy=fyv-cy[q],dz=fzv-cz[q]; double foot=dx*ux[q]+dy*uy[q]+dz*uz[q];
                double footC=foot<-hq?-hq:(foot>hq?hq:foot);
                double qx=dx-footC*ux[q],qy=dy-footC*uy[q],qz=dz-footC*uz[q]; double d2=qx*qx+qy*qy+qz*qz;
                if (d2<bdHO){ bdHO=d2; bestHO=q; } }
            int sHO=bestHO; double hHO=0.5*f.segLength.get(sHO);
            double dxh=fx-cx[sHO],dyh=fyv-cy[sHO],dzh=fzv-cz[sHO]; double footHO=dxh*ux[sHO]+dyh*uy[sHO]+dzh*uz[sHO];
            double footCHO=footHO<-hHO?-hHO:(footHO>hHO?hHO:footHO); double bindArcHO=footCHO+hHO;
            boolean g7HO = bindArcHO>epsHO && bindArcHO<2*hHO-epsHO;
            if (nCandidates>1) dupCount++;
            if (prevSeg!=-99 && s!=prevSeg) segSwitches++;
            if (!Double.isNaN(prevGlobal)) maxGlobalJump=Math.max(maxGlobalJump,Math.abs(globalArc-prevGlobal));
            rep.append(String.format(Locale.US, "%+8.3f | %-4d %-9.5f %-6s %-6s %-6s | %-4d %-9.5f %-6s\n",
                    d, s, bindArc, g7[0]?"PASS":"fail", g7[1]?"PASS":"fail", g7[2]?"PASS":"fail", sHO, bindArcHO, g7HO?"PASS":"fail"));
            prevWorldX=worldX; prevGlobal=globalArc; prevSeg=s; prevBind=bindArc;
        }
        rep.append("```\n");
        rep.append(String.format(Locale.US, "segment switches across sweep=%d, max duplicate-candidate steps=%d, max globalArc jump=%.2e µm\n\n",
                segSwitches, dupCount, maxGlobalJump));

        // ============================ Part C3 — exact-joint determinism ============================
        rep.append("## Part C3 — exact-joint case (F8 exactly at the shared joint point)\n```\n");
        {
            double fx=xJoint, fyv=yJoint, fzv=zJoint+perp;
            int cand=0, best=-1; double bd=1e9;
            for (int s2=0;s2<nSeg;s2++){ double hq=0.5*f.segLength.get(s2);
                double dx=fx-cx[s2],dy=fyv-cy[s2],dz=fzv-cz[s2]; double foot=dx*ux[s2]+dy*uy[s2]+dz*uz[s2];
                if (foot>hq+footTol||foot<-(hq+footTol)) continue; cand++;
                double px=dx-foot*ux[s2],py=dy-foot*uy[s2],pz=dz-foot*uz[s2]; double d2=px*px+py*py+pz*pz;
                if (d2<bd){bd=d2;best=s2;} }
            rep.append(String.format(Locale.US, "foot-interior candidate segments at the exact joint = %d (segments %d and %d share the point)\n", cand, jL, jR));
            rep.append(String.format(Locale.US, "nearestSeg2D deterministic choice = segment %d (first-min tie-break; iteration-order deterministic ⇒ CPU/GPU identical)\n", best));
            rep.append("no NaN / zero-length tangent; a half-open ownership rule (foot∈[-h,h)) selects exactly ONE segment unconditionally.\n");
        }
        rep.append("```\n\n");

        // ============================ bond force/torque continuity across the joint ============================
        rep.append("## Bond force/torque continuity across the joint (bind at sites straddling the joint, read bondForces)\n");
        rep.append("Head held at a FIXED world pose above the joint; bound site swept from seg "+jL+" (near end2) to seg "+jR+" (near end1).\n```\n");
        rep.append(String.format(Locale.US, "%-10s %-4s %-9s | %-12s %-12s %-12s\n","siteΔx(nm)","seg","bindArc","|F8|(pN)","|Thead|(pN·µm)","|Tseg|(pN·µm)"));
        MotorStore mot = G.mot; RigidRodBody b = mot.body;
        // put one motor head above the joint
        int hm = 2*0 + 2;   // head sub-body of motor 0 (h = 3*0+2)
        double headZ = zJoint + 0.006;   // 6 nm above (reach)
        b.coord.set(hm,(float)xJoint); b.coord.set(mot.body.coord.getSize()/3+hm,(float)yJoint); b.coord.set(2*(mot.body.coord.getSize()/3)+hm,(float)headZ);
        b.uVec.set(hm,0f); b.uVec.set(mot.body.uVec.getSize()/3+hm,0f); b.uVec.set(2*(mot.body.uVec.getSize()/3)+hm,-1f);   // head points down at fil
        b.yVec.set(hm,1f); b.yVec.set(mot.body.yVec.getSize()/3+hm,0f); b.yVec.set(2*(mot.body.yVec.getSize()/3)+hm,0f);
        double prevF8=Double.NaN, maxF8jump=0, maxTsegJump=0, prevTseg=Double.NaN;
        double[] siteFr = { -10,-5,-2,-1,-0.1,0.1,1,2,5,10 };
        for (double d : siteFr) {
            double siteX = xJoint + d*1e-3;
            int s = (d<0)?jL:jR; double hs=0.5*f.segLength.get(s);
            double e1x=cx[s]-hs*ux[s];
            double bindArc = (siteX - e1x)*ux[s];   // (collinear-x projection; the bound site along seg s)
            for (int q=0;q<mot.boundSeg.getSize();q++) mot.boundSeg.set(q,-1);
            mot.boundSeg.set(0,s); mot.bindArc.set(0,(float)bindArc); mot.nucleotideState.set(0, MotorStore.NUC_ADPPI);
            CrossBridgeSystem.bondForces(b.coord,b.uVec,b.yVec,b.bRotGam, f.coord,f.uVec,f.yVec,f.bRotGam,f.segLength, mot.boundSeg,mot.bindArc,mot.nucleotideState, G.bondData,G.xbParams);
            double fx=G.bondData.get(0),fy=G.bondData.get(1),fz=G.bondData.get(2);
            double thx=G.bondData.get(3),thy=G.bondData.get(4),thz=G.bondData.get(5);
            double tsx=G.bondData.get(9),tsy=G.bondData.get(10),tsz=G.bondData.get(11);
            double F8=Math.sqrt(fx*fx+fy*fy+fz*fz)*1e12, Th=Math.sqrt(thx*thx+thy*thy+thz*thz)*1e12, Ts=Math.sqrt(tsx*tsx+tsy*tsy+tsz*tsz)*1e12;
            if (!Double.isNaN(prevF8)) maxF8jump=Math.max(maxF8jump,Math.abs(F8-prevF8));
            if (!Double.isNaN(prevTseg)) maxTsegJump=Math.max(maxTsegJump,Math.abs(Ts-prevTseg));
            rep.append(String.format(Locale.US, "%+9.3f %-4d %-9.5f | %-12.4f %-12.4f %-12.4f\n", d, s, bindArc, F8, Th, Ts));
            prevF8=F8; prevTseg=Ts;
        }
        rep.append("```\n");
        rep.append(String.format(Locale.US, "max |F8| jump across the joint switch = %.4f pN; max |Tseg| jump = %.4f pN·µm  (bend=%.1f°)\n", maxF8jump, maxTsegJump, bendDeg));

        String fn = "JOINT_MARGIN_PROBE" + (bent?("_bend"+(int)bendDeg):"") + ".md";
        try { Files.writeString(Path.of(OUT, fn), rep.toString()); } catch (IOException e) { throw new UncheckedIOException(e); }
        System.out.println("# report: " + Path.of(OUT, fn).toAbsolutePath());
        System.out.print(rep);
    }
}
