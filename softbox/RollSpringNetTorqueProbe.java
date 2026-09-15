package softbox;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * CPU probe: does RollSpringSystem.rollForces conserve ROLL ANGULAR MOMENTUM on the gliding chain topology?
 *
 * The system's contract is that every joint is computed from BOTH segments' perspectives about the SAME
 * u_owner, owner writing +Tmag and other -Tmag, so sum_i (T_i . u_i) should vanish for a straight chain and
 * the NET torque vector should vanish identically. A non-zero net is angular-momentum injection: the filament
 * would gain roll every step, compounding once the segments lock together -- the signature seen in the
 * -rollspring gliding run (steady ~-15 turns/um vs the rigid touchstone's -5.2).
 *
 * Also checks the END joints specifically (segment 0 has only end2, segment N-1 only end1) since an asymmetry
 * there is the leading suspect.
 */
public final class RollSpringNetTorqueProbe {

    public static void main(String[] args) {
        int N = 12;
        double dt = 1.25e-6, f = 0.5, refDt = 1e-5;
        double segLen = 0.1755;
        double rest = ExplicitCompleteMatHarness.rollRestRad(segLen);

        for (String scen : new String[]{"straight-zero-twist", "at-rest-twist", "random-twist"}) {
            FloatArray uVec = new FloatArray(3*N), yVec = new FloatArray(3*N);
            FloatArray bRotGam = new FloatArray(3*N), torque = new FloatArray(3*N);
            IntArray e2 = new IntArray(N), e1 = new IntArray(N), counts = IntArray.fromElements(N,0,0,N);
            java.util.Random rng = new java.util.Random(12345);

            for (int i = 0; i < N; i++) {
                uVec.set(i,1f); uVec.set(N+i,0f); uVec.set(2*N+i,0f);          // all along +x
                double phi = switch (scen) {
                    case "straight-zero-twist" -> 0.0;
                    case "at-rest-twist"       -> i * rest;
                    default                    -> rng.nextDouble()*2*Math.PI;
                };
                yVec.set(i,0f); yVec.set(N+i,(float)Math.cos(phi)); yVec.set(2*N+i,(float)Math.sin(phi));
                bRotGam.set(i,1.4e-24f); bRotGam.set(N+i,1.4e-24f); bRotGam.set(2*N+i,1.4e-24f);
                torque.set(i,0f); torque.set(N+i,0f); torque.set(2*N+i,0f);
                e2.set(i, i < N-1 ? i+1 : -1);
                e1.set(i, i > 0   ? i-1 : -1);
            }
            FloatArray rp = FloatArray.fromElements((float)dt,(float)f,(float)rest,2f,0f,0f,(float)refDt);
            RollSpringSystem.rollForces(uVec, yVec, e2, e1, bRotGam, torque, rp, counts);

            double sx=0, sy=0, sz=0, absSum=0, maxAbs=0;
            for (int i = 0; i < N; i++) {
                double tx=torque.get(i), ty=torque.get(N+i), tz=torque.get(2*N+i);
                sx+=tx; sy+=ty; sz+=tz;
                double m=Math.sqrt(tx*tx+ty*ty+tz*tz); absSum+=m; maxAbs=Math.max(maxAbs,m);
            }
            double net=Math.sqrt(sx*sx+sy*sy+sz*sz);
            System.out.printf(java.util.Locale.US,
                "%-22s NET |sum T| = %.6e   max|T_i| = %.6e   sum|T_i| = %.6e   ratio net/sum = %.3e%n",
                scen, net, maxAbs, absSum, absSum>0 ? net/absSum : 0.0);
            System.out.printf("   per-segment T.u : ");
            for (int i = 0; i < N; i++) System.out.printf("%+.2e ", torque.get(i));
            System.out.println();
        }
        System.out.println("\nPASS if NET |sum T| is at float round-off vs max|T_i| (ratio <~1e-6).");
        System.out.println("Non-zero NET => roll angular momentum injected every step => the filament spins up.");
    }
}
