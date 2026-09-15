package softbox;

/**
 * Do the TWO springs of the two-point bond OPPOSE each other along the filament axis during a stroke?
 *
 * Established: pair=0 (both springs to the SAME site) reproduces the single-point motor EXACTLY; pair=1
 * (2.7 nm apart) is already fatal; the gliding force decomposition shows the PROPULSIVE component (faxNeg)
 * collapsing ~30-40% while the resisting component (faxPos) is untouched.
 *
 * Candidate mechanism: sites n and n-2 are ~5.4 nm apart AXIALLY, and the working stroke is ~5-7 nm -- so
 * over one stroke the head traverses about one site spacing. The two zero-rest springs therefore sample
 * DIFFERENT points of the stroke and can pull in OPPOSING axial directions while the head is driving.
 *
 * This is pure geometry + Hooke, so it needs no simulation: sweep the head tip through a stroke and report
 * the axial components F1.u and F2.u separately. If they anti-align over the propulsive part of the sweep,
 * the cancellation is demonstrated rather than asserted.
 */
public final class TwoSpringCancellationProbe {

    public static void main(String[] args) {
        double Ract  = ExplicitCompleteMatHarness.R_ACTIN_NM * 1e-3;   // um
        double monoSp = Constants.actinMonoRadius;                     // um per monomer
        double twistPerMon = ExplicitCompleteMatHarness.TWIST_PER_MON_DEG * Math.PI/180.0;
        double k = 1.0e-9;          // myoSpring (xbParams[0]) -- absolute scale is irrelevant to the SIGNS

        for (int pair : new int[]{0, 1, 2, 4}) {
            double arcA = 0.0,           azA = 0.0;
            double arcB = -pair*monoSp,  azB = -pair*twistPerMon;
            // sites on the actin surface: u = +x, y = +y, z = +z
            double pAx = arcA, pAy = Ract*Math.cos(azA), pAz = Ract*Math.sin(azA);
            double pBx = arcB, pBy = Ract*Math.cos(azB), pBz = Ract*Math.sin(azB);

            System.out.printf(java.util.Locale.US,
                "%npair=%d  siteA=(%.4f,%.4f,%.4f)  siteB=(%.4f,%.4f,%.4f)  axial sep %.2f nm, chord %.2f nm%n",
                pair, pAx,pAy,pAz, pBx,pBy,pBz, Math.abs(pAx-pBx)*1e3,
                Math.sqrt(Math.pow(pAx-pBx,2)+Math.pow(pAy-pBy,2)+Math.pow(pAz-pBz,2))*1e3);
            System.out.printf("  %10s %12s %12s %12s %10s%n","headX_nm","F1.u (pN)","F2.u (pN)","sum (pN)","opposed?");

            int opposedCt = 0, n = 0;
            // sweep the head tip across a working stroke (-8 .. +8 nm), anchored at the midpoint at x=0
            for (double xnm = -8; xnm <= 8.001; xnm += 2.0) {
                double hx = xnm*1e-3, hy = Ract, hz = 0.0;      // head tip just above the surface
                double F1u = k * (pAx - hx);                     // axial component of spring A
                double F2u = k * (pBx - hx);                     // axial component of spring B
                boolean opp = (F1u*F2u) < 0;
                if (opp) opposedCt++;
                n++;
                System.out.printf(java.util.Locale.US, "  %10.1f %12.4e %12.4e %12.4e %10s%n",
                        xnm, F1u*1e12, F2u*1e12, (F1u+F2u)*1e12, opp ? "OPPOSED" : "");
            }
            System.out.printf("  -> opposed at %d/%d sweep points%n", opposedCt, n);
        }
        System.out.println("\nF1.u and F2.u share a sign only where the head lies OUTSIDE the two sites.");
        System.out.println("Between them the springs pull opposite ways: the separation defines a DEAD ZONE");
        System.out.println("of width = the site spacing, inside which axial force is cancelled.");
    }
}
