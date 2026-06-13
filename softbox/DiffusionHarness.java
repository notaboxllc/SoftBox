package softbox;

import uk.ac.manchester.tornado.api.GridScheduler;
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.TornadoExecutionResult;
import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.WorkerGrid1D;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;

/**
 * Increment-1 diffusion harness + FDT (Einstein) validation.
 *
 * Runs N independent free rods device-resident for M steps (no per-step host pose
 * pull — pose is FIRST_EXECUTION on device, pulled UNDER_DEMAND only at output
 * cadence) and measures diffusion two ways, then checks each measured D against the
 * value implied by the SAME drag tensors the kernel used (D = kT/gamma):
 *
 *  Config T (translational anisotropy): rotational Brownian OFF, orientation frozen
 *    along lab-x. Body axes == lab axes for all steps, so lab-frame MSD per axis
 *    isolates D_par (along uVec) and D_perp. MSD_axis(t) = 2 D_axis t.
 *
 *  Config R (rotational): translational Brownian OFF. Orientational autocorrelation
 *    C(t) = <uVec(t).uVec(0)> = <uVec_x(t)> (since uVec(0)=(1,0,0)) decays as
 *    exp(-2 D_rot t), giving the transverse rotational diffusion D_rot,perp.
 *
 * Both B-coefficients are 1.0 here so the bare FDT relation D = kT/gamma holds (v1's
 * production BTransCoeff=1/BRotCoeff=0.5 are biological tuning knobs, out of scope for
 * the amplitude-coupling check). Pass tolerance: 5% (covers float32 + ~1/sqrt(N)
 * ensemble noise + O(D dt) Euler bias; tight enough to catch a wrong amplitude factor,
 * which would be tens of percent off).
 */
public final class DiffusionHarness {

    // run sizing (tunable; defaults give ~2.2% ensemble statistical error)
    static int N = 8192;
    static int M_TRANS = 20000;   static int CAD_TRANS = 200;
    static int M_ROT   = 4000;    static int CAD_ROT   = 20;
    static int MONOMER_CT = 64;
    static final double TOL = 0.05;
    // explicit block size — the TornadoVM default overflows the register file for these
    // register-heavy kernels (CUDA_ERROR_LAUNCH_OUT_OF_RESOURCES / 701). Matches v1's
    // MOVE_KERNEL_BLOCK_SIZE (GPUMoveThing.java:985).
    static final int BLOCK_SIZE = 64;
    static GridScheduler sched;

    public static void main(String[] args) {
        // parse optional "-3js <dir>" (frame-dump viz mode); remaining args are positional N [M].
        String jsDir = null;
        java.util.List<String> pos = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-3js")) { jsDir = args[++i]; }
            else pos.add(args[i]);
        }
        if (jsDir != null) {
            int vizN = pos.size() >= 1 ? Integer.parseInt(pos.get(0)) : 200;
            int vizM = pos.size() >= 2 ? Integer.parseInt(pos.get(1)) : 20000;
            runViz(jsDir, vizN, vizM);
            return;
        }
        // --- FDT path: byte-for-byte unchanged from inc 1 when -3js is absent ---
        if (pos.size() >= 1) N = Integer.parseInt(pos.get(0));
        if (pos.size() >= 2) { M_TRANS = Integer.parseInt(pos.get(1)); M_ROT = M_TRANS / 5; }

        System.out.println("=== Soft Box increment 1 — filament rigid-rod Langevin FDT validation ===");
        System.out.printf("N=%d rods, monomerCt=%d, dt=%.1e s, aeta=%.3g Pa-s, kT=%.4e J%n",
                N, MONOMER_CT, Constants.deltaT, Constants.aeta, Constants.kT);

        boolean okT = runTranslational();
        boolean okR = runRotational();

        System.out.println();
        System.out.println("=== FDT VALIDATION " + (okT && okR ? "PASS" : "FAIL") + " (tolerance "
                + (int) (TOL * 100) + "%) ===");
        if (!(okT && okR)) {
            System.out.println("BAIL-OUT: measured D outside FDT tolerance — integration or Brownian-"
                    + "amplitude coupling is wrong. Commit nothing; report.");
            System.exit(1);
        }
    }

    /** Build the per-step device graph: brownian -> integrate -> derived, pose resident. */
    private static TornadoExecutionPlan buildPlan(FilamentStore s) {
        TaskGraph tg = new TaskGraph("rodLangevin")
            // canonical pose + constant drag/length/scales/params: upload once, stay resident
            .transferToDevice(DataTransferMode.FIRST_EXECUTION,
                    s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2, s.segLength,
                    s.forceSum, s.torqueSum, s.randForce, s.randTorque,
                    s.bTransGam, s.bRotGam, s.brownTransScale, s.brownRotScale, s.params)
            // step counter changes every step
            .transferToDevice(DataTransferMode.EVERY_EXECUTION, s.counts)
            .task("brownian", BrownianForceSystem::brownianForce,
                    s.randForce, s.randTorque, s.bTransGam, s.bRotGam,
                    s.brownTransScale, s.brownRotScale, s.params, s.counts)
            .task("integrate", RigidRodLangevinIntegrationSystem::integrate,
                    s.coord, s.uVec, s.yVec, s.forceSum, s.torqueSum,
                    s.randForce, s.randTorque, s.bTransGam, s.bRotGam, s.params, s.counts)
            .task("derived", DerivedGeometrySystem::derive,
                    s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2, s.segLength, s.counts)
            // pose pulled only on explicit demand (output cadence)
            .transferToHost(DataTransferMode.UNDER_DEMAND,
                    s.coord, s.uVec, s.yVec, s.zVec, s.end1, s.end2);

        // explicit per-task worker grid (block size 64) — see BLOCK_SIZE note
        WorkerGrid wB = new WorkerGrid1D(s.n); wB.setLocalWork(BLOCK_SIZE, 1, 1);
        WorkerGrid wI = new WorkerGrid1D(s.n); wI.setLocalWork(BLOCK_SIZE, 1, 1);
        WorkerGrid wD = new WorkerGrid1D(s.n); wD.setLocalWork(BLOCK_SIZE, 1, 1);
        sched = new GridScheduler("rodLangevin.brownian", wB);
        sched.addWorkerGrid("rodLangevin.integrate", wI);
        sched.addWorkerGrid("rodLangevin.derived", wD);

        ImmutableTaskGraph itg = tg.snapshot();
        return new TornadoExecutionPlan(itg);
    }

    // ----------------------------------------------------------------- viz (frame dump)
    /**
     * Viz run (increment 1.5): low rod count in a compact cluster, both Brownian
     * components ON (bare FDT amplitude), frames written at output cadence for the v1
     * viewer. Separate from the FDT validation; reuses the same device graph + the
     * existing output-cadence UNDER_DEMAND pose pull (no new sync, no per-step pull).
     */
    private static void runViz(String dir, int vizN, int vizM) {
        int cad = Math.max(1, vizM / 200);   // aim for ~200 frames
        System.out.println("=== Soft Box increment 1.5 — Three.js frame dump (viz run) ===");
        System.out.printf("N=%d rods, M=%d steps, cadence=%d, dt=%.1e s%n",
                vizN, vizM, cad, Constants.deltaT);

        FilamentStore s = new FilamentStore(vizN);
        java.util.Random rng = new java.util.Random(20260613L);
        final double clusterHalf = 0.3;   // microns — compact starting cluster
        for (int i = 0; i < vizN; i++) {
            s.monomerCount.set(i, MONOMER_CT);
            // random unit uVec (uniform on sphere)
            double z = 2 * rng.nextDouble() - 1, phi = 2 * Math.PI * rng.nextDouble();
            double rr = Math.sqrt(Math.max(0.0, 1 - z * z));
            double ux = rr * Math.cos(phi), uy = rr * Math.sin(phi), uz = z;
            // yVec: any unit vector perpendicular to uVec
            double ax, ay, az;
            if (Math.abs(uz) < 0.9) { ax = 0; ay = 0; az = 1; } else { ax = 1; ay = 0; az = 0; }
            double dot = ax * ux + ay * uy + az * uz;
            double yx = ax - dot * ux, yy = ay - dot * uy, yz = az - dot * uz;
            double yn = Math.sqrt(yx * yx + yy * yy + yz * yz);
            yx /= yn; yy /= yn; yz /= yn;
            s.setUVec(i, (float) ux, (float) uy, (float) uz);
            s.setYVec(i, (float) yx, (float) yy, (float) yz);
            s.setCoord(i,
                    (float) ((rng.nextDouble() - 0.5) * 2 * clusterHalf),
                    (float) ((rng.nextDouble() - 0.5) * 2 * clusterHalf),
                    (float) ((rng.nextDouble() - 0.5) * 2 * clusterHalf));
            s.brownTransScale.set(i, 1.0f);
            s.brownRotScale.set(i, 1.0f);
        }
        DragTensorSystem.run(s);
        s.setParams(Constants.deltaT, Constants.brownianForceMag());

        // View box: FIXED, sized to ~5 sigma of the expected diffusive spread over the run.
        // Framing only (no walls; not physics). The viewer builds the box from frame 0.
        double Dpar = DragTensorSystem.fdtPrediction(s, 0)[0] * 1e12;   // um^2/s
        double rms = Math.sqrt(2 * Dpar * vizM * Constants.deltaT);
        double dim = 2 * (clusterHalf + 5 * rms);
        System.out.printf("  view box %.3g um cube (clusterHalf %.2f + 5*sqrt(2 Dpar T) %.2f); framing only%n",
                dim, clusterHalf, 5 * rms);

        TornadoExecutionPlan plan = buildPlan(s);
        s.setCounts(0, 4242);
        FrameWriter fw = new FrameWriter(dir, dim, dim, dim);

        fw.writeFrame(s, 0.0);   // frame 0 = initial pose (host already holds coord/uVec)
        TornadoExecutionResult res = null;
        for (int step = 0; step < vizM; step++) {
            s.counts.set(1, step);
            res = plan.withGridScheduler(sched).execute();
            if ((step + 1) % cad == 0) {
                res.transferToHost(s.coord, s.uVec);   // existing output-cadence pull
                fw.writeFrame(s, (step + 1) * Constants.deltaT);
            }
        }
        System.out.printf("wrote %d frames to %s%n", fw.framesWritten(), fw.dir());
        System.out.println("view: cd ~/Code/SoftBox && python3 sim_server.py 8000");
        System.out.println("then open http://localhost:8000/sim_viewer_boa.html  (Recent picker -> newest)");
    }

    private static FilamentStore freshStore(double transScale, double rotScale) {
        FilamentStore s = new FilamentStore(N);
        for (int i = 0; i < N; i++) {
            s.monomerCount.set(i, MONOMER_CT);
            s.setCoord(i, 0f, 0f, 0f);
            s.setUVec(i, 1f, 0f, 0f);   // long axis along lab-x
            s.setYVec(i, 0f, 1f, 0f);
            s.brownTransScale.set(i, (float) transScale);
            s.brownRotScale.set(i, (float) rotScale);
        }
        DragTensorSystem.run(s);                 // fill gamma/diff + segLength (host, once)
        s.setParams(Constants.deltaT, Constants.brownianForceMag());
        return s;
    }

    // ----------------------------------------------------------------- translational
    private static boolean runTranslational() {
        System.out.println("\n--- Config T: translational (rot Brownian OFF, orientation frozen +x) ---");
        FilamentStore s = freshStore(Constants.BTransCoeff /*=1*/, 0.0);
        // force the FDT-bare amplitude: trans scale = 1 exactly
        for (int i = 0; i < N; i++) s.brownTransScale.set(i, 1.0f);

        TornadoExecutionPlan plan = buildPlan(s);
        s.setCounts(0, 12345);

        int samples = M_TRANS / CAD_TRANS + 1;
        double[] t = new double[samples];
        double[] msdX = new double[samples], msdY = new double[samples], msdZ = new double[samples];
        int rec = 0;
        TornadoExecutionResult res = null;

        for (int step = 0; step <= M_TRANS; step++) {
            if (step % CAD_TRANS == 0) {
                if (step > 0) res.transferToHost(s.coord);  // UNDER_DEMAND pose pull (cadence only)
                double sx = 0, sy = 0, sz = 0;
                for (int i = 0; i < N; i++) {
                    double x = s.coordX(i), y = s.coordY(i), z = s.coordZ(i);
                    sx += x * x; sy += y * y; sz += z * z;
                }
                t[rec] = step * Constants.deltaT;
                msdX[rec] = sx / N; msdY[rec] = sy / N; msdZ[rec] = sz / N;
                rec++;
            }
            if (step == M_TRANS) break;
            s.counts.set(1, step);
            res = plan.withGridScheduler(sched).execute();
        }

        // MSD_axis = 2 D_axis t  -> slope through origin / 2
        double Dx = slopeThroughOrigin(t, msdX) / 2.0;   // D_parallel  (microns^2/s)
        double Dy = slopeThroughOrigin(t, msdY) / 2.0;   // D_perp
        double Dz = slopeThroughOrigin(t, msdZ) / 2.0;   // D_perp

        double[] fdt = DragTensorSystem.fdtPrediction(s, 0);  // SI m^2/s
        double predPar  = fdt[0] * 1e12;   // -> microns^2/s
        double predPerp = fdt[1] * 1e12;

        System.out.printf("  gamma_par=%.4e  gamma_perp=%.4e  (N s/m)%n",
                s.bTransGam.get(s.planeX(0)), s.bTransGam.get(s.planeY(0)));
        boolean ok = true;
        ok &= report("D_trans_parallel (x)", Dx, predPar, "um^2/s");
        ok &= report("D_trans_perp (y)",     Dy, predPerp, "um^2/s");
        ok &= report("D_trans_perp (z)",     Dz, predPerp, "um^2/s");
        return ok;
    }

    // ----------------------------------------------------------------- rotational
    private static boolean runRotational() {
        System.out.println("\n--- Config R: rotational (trans Brownian OFF) ---");
        FilamentStore s = freshStore(0.0, 1.0);  // rot scale = 1 exactly (bare FDT)

        TornadoExecutionPlan plan = buildPlan(s);
        s.setCounts(0, 67890);

        int samples = M_ROT / CAD_ROT + 1;
        double[] t = new double[samples];
        double[] c = new double[samples];   // C(t) = <uVec_x>
        int rec = 0;
        TornadoExecutionResult res = null;

        for (int step = 0; step <= M_ROT; step++) {
            if (step % CAD_ROT == 0) {
                if (step > 0) res.transferToHost(s.uVec);
                double su = 0;
                for (int i = 0; i < N; i++) su += s.uVecX(i);
                t[rec] = step * Constants.deltaT;
                c[rec] = su / N;
                rec++;
            }
            if (step == M_ROT) break;
            s.counts.set(1, step);
            res = plan.withGridScheduler(sched).execute();
        }

        // C(t) = exp(-2 D_rot t) -> fit ln C vs t over the resolved window
        int cnt = 0;
        for (int k = 0; k < rec; k++) if (c[k] > 0.2 && c[k] < 0.95) cnt++;
        double[] tw = new double[cnt], lw = new double[cnt];
        int j = 0;
        for (int k = 0; k < rec; k++) {
            if (c[k] > 0.2 && c[k] < 0.95) { tw[j] = t[k]; lw[j] = Math.log(c[k]); j++; }
        }
        double slope = olsSlope(tw, lw);     // = -2 D_rot
        double Drot = -slope / 2.0;          // rad^2/s

        double[] fdt = DragTensorSystem.fdtPrediction(s, 0);
        double predRot = fdt[3];             // kT/bRotGam.y, rad^2/s

        System.out.printf("  gamma_rot_perp=%.4e (N m s/rad);  fit window: %d/%d samples, C in (0.2,0.95)%n",
                s.bRotGam.get(s.planeY(0)), cnt, rec);
        return report("D_rot_perp", Drot, predRot, "rad^2/s");
    }

    // ----------------------------------------------------------------- helpers
    private static double slopeThroughOrigin(double[] x, double[] y) {
        double sxy = 0, sxx = 0;
        for (int i = 0; i < x.length; i++) { sxy += x[i] * y[i]; sxx += x[i] * x[i]; }
        return sxy / sxx;
    }

    private static double olsSlope(double[] x, double[] y) {
        int n = x.length;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) { sx += x[i]; sy += y[i]; sxx += x[i] * x[i]; sxy += x[i] * y[i]; }
        return (n * sxy - sx * sy) / (n * sxx - sx * sx);
    }

    private static boolean report(String name, double measured, double predicted, String unit) {
        double relErr = Math.abs(measured - predicted) / predicted;
        boolean ok = relErr <= TOL;
        System.out.printf("  %-22s measured=%.5e  FDT=%.5e  %s  relErr=%+.2f%%  %s%n",
                name, measured, predicted, unit, 100 * (measured - predicted) / predicted,
                ok ? "OK" : "*** OUT OF TOLERANCE ***");
        return ok;
    }
}
