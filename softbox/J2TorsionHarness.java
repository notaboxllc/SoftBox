package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/** Deterministic unit/contract test for the default-off physical J2 potential. */
public final class J2TorsionHarness {
    private J2TorsionHarness() {}

    private static FloatArray params(double dt, double kappaPNnm, double restDeg, boolean extended) {
        FloatArray p = new FloatArray(extended ? 16 : 11); p.init(0f);
        p.set(0,(float)dt); p.set(8,96f); p.set(10,6f);
        if (extended) { p.set(14,(float)(kappaPNnm*1e-21)); p.set(15,(float)Math.toRadians(restDeg)); }
        return p;
    }

    private static FloatArray evaluate(double dt, double kappa, int nucleotide, boolean extended) {
        return evaluate(dt, kappa, nucleotide, extended, false);
    }

    /** If xzPlane is true, rotate the same bend from XY into XZ without changing its scalar angle. */
    private static FloatArray evaluate(double dt, double kappa, int nucleotide, boolean extended, boolean xzPlane) {
        final int nB=3;
        FloatArray c=new FloatArray(9), u=new FloatArray(9), len=new FloatArray(3);
        FloatArray bt=new FloatArray(9), br=new FloatArray(9), f=new FloatArray(9), t=new FloatArray(9);
        IntArray nuc=new IntArray(1), counts=new IntArray(4);
        len.set(0,.050f);len.set(1,.020f);len.set(2,.020f);bt.init(1f);br.init(1f);nuc.set(0,nucleotide);
        double th=Math.toRadians(120), lx=Math.cos(th),lt=Math.sin(th);
        double ly=xzPlane?0:lt,lz=xzPlane?lt:0;
        u.set(0,1f);u.set(nB,0f);u.set(2*nB,0f);
        u.set(1,(float)lx);u.set(nB+1,(float)ly);u.set(2*nB+1,(float)lz);
        u.set(2,(float)lx);u.set(nB+2,(float)ly);u.set(2*nB+2,(float)lz);
        // rod.end2=(.025,0,0)=lever.end1; lever.end2=head.end1. Positional terms are also disabled.
        c.set(1,(float)(.025+.010*lx));c.set(nB+1,(float)(.010*ly));c.set(2*nB+1,(float)(.010*lz));
        c.set(2,(float)(.025+.030*lx));c.set(nB+2,(float)(.030*ly));c.set(2*nB+2,(float)(.030*lz));
        MotorJointSystem.joints(c,u,len,bt,br,f,t,nuc,params(dt,kappa,124,extended),counts);
        FloatArray out=new FloatArray(18);
        for(int i=0;i<9;i++){out.set(i,f.get(i));out.set(9+i,t.get(i));}
        return out;
    }

    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}

    public static void main(String[] args){
        FloatArray nativeOff=evaluate(1e-5,0,MotorStore.NUC_ADPPI,false);
        FloatArray extendedOff=evaluate(1e-5,0,MotorStore.NUC_ADP,true);
        for(int i=0;i<18;i++) require(Float.floatToRawIntBits(nativeOff.get(i))==Float.floatToRawIntBits(extendedOff.get(i)),"zero path differs at "+i);

        FloatArray a=evaluate(1e-5,3,MotorStore.NUC_ADPPI,true);
        FloatArray b=evaluate(2.5e-6,3,MotorStore.NUC_ADP,true);
        for(int i=0;i<9;i++){require(a.get(i)==0f,"physical J2 generated force");require(b.get(i)==0f,"physical J2 generated force (dt2)");}
        // torque z slots in packed output: rod=15, lever=16, head=17.
        double rod=a.get(15),lever=a.get(16),head=a.get(17),rod2=b.get(15),lever2=b.get(16);
        double expected=3e-21*Math.toRadians(120-124);
        require(Math.abs(rod+lever)<1e-27,"J2 torques are not equal/opposite");
        require(head==0,"J2 torque leaked to head");
        require(Math.abs((rod-expected)/expected)<2e-5,"wrong physical torque magnitude");
        require(Float.floatToRawIntBits((float)rod)==Float.floatToRawIntBits((float)rod2),"torque depends on dt/state");
        require(Float.floatToRawIntBits((float)lever)==Float.floatToRawIntBits((float)lever2),"opposite torque depends on dt/state");

        FloatArray rotated=evaluate(1e-5,3,MotorStore.NUC_ADPPI,true,true);
        // XY bend: rod torque lies on +/−Z (slot 15). XZ bend: it lies on +/−Y (slot 12).
        double rotatedRod=rotated.get(12), rotatedOther1=rotated.get(9), rotatedOther2=rotated.get(15);
        require(Math.abs((Math.abs(rotatedRod)-Math.abs(rod))/rod)<2e-5,"torque magnitude depends on bend plane");
        require(rotatedOther1==0 && rotatedOther2==0,"torque did not rotate with the instantaneous bend plane");
        System.out.printf("J2_TORSION_TEST PASS zero-byte-path=true force-free=true equal-opposite=true state-independent=true dt-independent=true plane-invariant=true torque_pNnm=%+.6f%n",rod*1e21);
    }
}
