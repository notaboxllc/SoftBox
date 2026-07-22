package softbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SM4 — FORCE-DEPENDENT ATTACHMENT LIFETIME (the {@code xCatch} assay), CPU-only.
 *
 * <p>Measures actomyosin ATTACHMENT LIFETIME under a CONSTANT axial load, from a NAMED PREPARED
 * NUCLEOTIDE STATE, using the frozen explicit two-body motor and the existing chemistry kernel
 * ({@link NucleotideCycleSystem#cycleLymnTaylor}). Nothing here retunes physics: the harness only
 * (a) builds a bound motor through the centralized {@link TwoBodyConverterMotor#buildBoundMotor}
 * builder, (b) installs the canonical rates via {@code initChem4a}, (c) OVERRIDES the initial
 * nucleotide state to the requested prepared state, (d) applies a constant external axial force
 * through the existing {@code trapParams[5]} channel, and (e) waits for NATURAL detachment.
 *
 * <h2>Mechanism (why this assay reads what it reads)</h2>
 * Under the canonical Lymn-Taylor cycle there is EXACTLY ONE detachment pathway: a BOUND head that
 * enters {@code NUC_ATP} detaches (ATP binding releases the rigor head). The Guo &amp; Guilford
 * catch-slip term {@code g(F) = aCatch*exp(-F*xCatch/kT) + aSlip*exp(+F*xSlip/kT)} is the LOAD
 * MODULATION of the {@code ADP -> NONE} step, NOT a release pathway. Consequently:
 * <ul>
 *   <li><b>ADP prepared state</b>: lifetime = T(ADP-&gt;NONE; rate {@code onADP*g(F)}, FORCE-DEPENDENT)
 *       + T(NONE-&gt;ATP; rate {@code atpOn}, force-independent). A two-step (hypoexponential) lifetime
 *       whose first step carries ALL the force dependence. This is the xCatch/xSlip calibration channel.</li>
 *   <li><b>RIGOR prepared state</b> ({@code NUC_NONE}): lifetime = T(NONE-&gt;ATP) only — a single
 *       exponential at {@code atpOn}, <b>structurally force-INDEPENDENT</b>. The model has no
 *       force-dependent rigor-rupture pathway. See the findings document: this is a real structural
 *       limitation for calibrating the rigor arm of Guo &amp; Guilford, not a defect of the apparatus.</li>
 *   <li><b>CYCLING</b> ({@code NUC_ADPPi}): the full natural attachment — stroke then release.</li>
 * </ul>
 *
 * <h2>Force sign convention (EXACT)</h2>
 * The external force is applied through {@code Cmot.trapParams[5]}: a deterministic axial force in
 * NEWTONS added to the filament at its COM along {@code cm.uvecPhys} (no torque). The project
 * convention is barbed = end2 = {@code +uVec}. Therefore:
 * <pre>
 *   signedForce &gt; 0  =&gt;  force toward the BARBED (+uVec) end  =&gt;  OPPOSING  (resisting load; catch side)
 *   signedForce &lt; 0  =&gt;  force toward the POINTED (-uVec) end  =&gt;  ASSISTING (slip side)
 * </pre>
 * This matches the existing {@code adpReleaseAssay} convention ("+barbed = opposing"). The REALIZED
 * load carried by the cross-bridge is read as {@code forceDotFil = bondData[12]} = Dot(F8 head force,
 * seg.uVec), reported in pN; validation A confirms it tracks the request and flips sign on reversal.
 *
 * <h2>Emergency / rupture handling</h2>
 * The {@code ExplicitHmmDimerGpuParams.RUPTURE_MODE} failsafe is on the HMM-DIMER GPU path and is
 * structurally NOT on this two-body CPU code path. The two-body break-force cap
 * ({@code kinParams[12]}, {@code setFaithfulRelease}) defaults OFF and is left OFF here; the run
 * reports its resolved state explicitly. No emergency handling is enabled globally.
 *
 * <pre>
 *   ./scripts/run_sm4.sh -smoke                 # apparatus-validation smoke matrix (CPU)
 *   ./scripts/run_sm4.sh -states rigor,adp -forces 0,3,6,10 -events 10
 *   ./scripts/run_sm4.sh -selftest              # gate checks only (fast)
 * </pre>
 */
public final class Sm4ForceLifetimeHarness {

    // ---------------------------------------------------------------- prepared states
    enum Prep {
        RIGOR("rigor", MotorStore.NUC_NONE),
        ADP("adp", MotorStore.NUC_ADP),
        CYCLING("cycling", MotorStore.NUC_ADPPI);
        final String id; final int nuc;
        Prep(String id, int nuc) { this.id = id; this.nuc = nuc; }
        static Prep of(String s) {
            for (Prep p : values()) if (p.id.equalsIgnoreCase(s)) return p;
            throw new IllegalArgumentException("unknown prepared state '" + s + "' (rigor|adp|cycling)");
        }
        /** Converter target for the prepared state (the model's own cocking map). */
        double thetaS() { return TwoBodyConverterMotor.thetaS4a(nuc); }
    }

    /** The transitions cycleLymnTaylor can legally produce in ONE step (self-loops included).
     *  Anything else is a forbidden jump and is counted as an invalid state. */
    static boolean legalTransition(int a, int b) {
        if (a == b) return true;
        return (a == MotorStore.NUC_NONE  && b == MotorStore.NUC_ATP)     // ATP uptake => detachment
            || (a == MotorStore.NUC_ATP   && b == MotorStore.NUC_ADPPI)   // hydrolysis recovery
            || (a == MotorStore.NUC_ADPPI && b == MotorStore.NUC_ADP)     // Pi release / power stroke
            || (a == MotorStore.NUC_ADP   && b == MotorStore.NUC_NONE);   // ADP release (the catch-modulated step)
    }

    static String nucName(int s) {
        return switch (s) {
            case MotorStore.NUC_NONE -> "NONE(rigor)";
            case MotorStore.NUC_ATP -> "ATP";
            case MotorStore.NUC_ADPPI -> "ADPPi";
            case MotorStore.NUC_ADP -> "ADP";
            default -> "INVALID(" + s + ")";
        };
    }

    // ---------------------------------------------------------------- one event record
    static final class Event {
        int id; String state; double reqF; String dir; double signedF;
        double rfMean, rfSd, rfMaxDev; int seed;
        double tAttach, tDetach, lifetime; int censored;
        String pathway = "", detachState = "";
        double tAdpRelease = Double.NaN;          // isolates the FORCE-DEPENDENT sub-step (ADP->NONE)
        double maxDispNm, maxBondForcePn, maxAxialLoadPn;
        int stepsRun, invalidStates, solverFailures;
        boolean valid = true; String abortReason = "";
    }

    // ---------------------------------------------------------------- config
    static final class Cfg {
        MotorModel model = MotorModel.EXPLICIT_S2_L40;
        double dt = 2.5e-6, kAx = 0.05, kTr = 0.05;
        int events = 10, seedBase = 9100;
        double maxDwellMs = 20.0;                  // observation window (right-censoring horizon)
        boolean brownian = true;
        String outDir = "RUN_LOGS/motor_validation/sm4_force_lifetime";
        String rev = "unknown";
        String buildId = "unknown";                // scratch-build identifier (provenance for isolated builds)
        boolean resume = false;                    // skip cells whose batch JSON already exists (resumability)
        String runClass = "APPARATUS-VALIDATION SMOKE (NOT calibration data)";
        List<Prep> states = new ArrayList<>();
        List<Double> forces = new ArrayList<>();
        List<String> dirs = new ArrayList<>();
    }

    public static void main(String[] args) {
        Cfg c = new Cfg();
        boolean selftest = false, smoke = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-selftest" -> selftest = true;
                case "-smoke" -> smoke = true;
                case "-dt" -> c.dt = Double.parseDouble(args[++i]);
                case "-events" -> c.events = Integer.parseInt(args[++i]);
                case "-seed" -> c.seedBase = Integer.parseInt(args[++i]);
                case "-maxdwell" -> c.maxDwellMs = Double.parseDouble(args[++i]);
                case "-outdir" -> c.outDir = args[++i];
                case "-rev" -> c.rev = args[++i];
                case "-buildid" -> c.buildId = args[++i];
                case "-resume" -> c.resume = true;
                case "-runclass" -> c.runClass = args[++i];
                case "-nobrownian" -> c.brownian = false;
                case "-motor" -> c.model = MotorModel.fromId(args[++i]);
                case "-states" -> { for (String s : args[++i].split(",")) c.states.add(Prep.of(s.trim())); }
                case "-forces" -> { for (String s : args[++i].split(",")) c.forces.add(Double.parseDouble(s.trim())); }
                case "-dirs" -> { for (String s : args[++i].split(",")) c.dirs.add(s.trim()); }
                default -> { }
            }
        }
        if (c.states.isEmpty()) { c.states.add(Prep.RIGOR); c.states.add(Prep.ADP); }
        if (c.forces.isEmpty()) { c.forces.add(0.0); c.forces.add(3.0); c.forces.add(6.0); c.forces.add(10.0); }
        if (c.dirs.isEmpty()) { c.dirs.add("opposing"); c.dirs.add("assisting"); }

        System.out.println("=== SoftBox — SM4: force-dependent attachment lifetime (the xCatch assay) — CPU-only ===");
        System.out.println("# RUN CLASS: " + c.runClass);

        // Frozen-config abort gate, mirroring ExplicitHmmDimerGpuValidation's production gate. The rupture
        // failsafe is not wired into the two-body CPU path at all, but a non-default global is a signal that
        // something else in the session changed it -- refuse rather than silently produce lifetimes under it.
        if (ExplicitHmmDimerGpuParams.RUPTURE_MODE != 0 || ExplicitHmmDimerGpuParams.EMERGENCY_ON) {
            System.out.printf(Locale.US, "  *** ABORT (frozen-config violation): rupture failsafe active "
                    + "(RUPTURE_MODE=%d, EMERGENCY_ON=%s) ***%n",
                    ExplicitHmmDimerGpuParams.RUPTURE_MODE, ExplicitHmmDimerGpuParams.EMERGENCY_ON);
            System.exit(3);
        }
        if (Math.abs(c.dt - 2.5e-6) > 1e-12) {
            System.out.printf(Locale.US, "  *** NOTE: dt=%.3e differs from the production 2.5e-6 ***%n", c.dt);
        }
        if (selftest) { System.exit(selftest(c) ? 0 : 1); }
        if (smoke || true) { System.exit(runMatrix(c) ? 0 : 1); }
    }

    // ================================================================ apparatus

    /** Build a bound motor in the requested PREPARED state at the requested constant load, then
     *  equilibrate the MECHANICS at that load with the CHEMISTRY FROZEN (no cycle kernel calls), so
     *  that t=0 is a genuine, load-equilibrated, uncontaminated prepared state. */
    static TwoBodyConverterMotor.Cmot prepare(Cfg c, Prep prep, double signedForcePN, int seed) {
        TwoBodyConverterMotor.Cmot cm = TwoBodyConverterMotor.buildBoundMotor(c.model, c.dt);
        TwoBodyConverterMotor.initChem4a(cm, c.dt, true);          // canonical Env rates; bound; ADPPi
        cm.mot.nucleotideState.set(0, prep.nuc);                   // PREPARED STATE override
        cm.thetaS = prep.thetaS();
        cm.mot.boundSeg.set(0, 0);
        cm.mot.forceDotFil.set(0, 0f); cm.mot.forceMag.set(0, 0f);
        cm.mot.forceDotAvg.set(0, 0f); cm.mot.avgInit.set(0, 0); cm.mot.cooldown.set(0, 0);
        // CONSTANT axial external force (N). The dot(bhat,uvecPhys) factor is the POLARITY-SAFE form used
        // throughout the two-body code (+1 when swap=false, -1 when swap=true), so "+ = opposing/barbed"
        // holds regardless of the frame the motor was built in.
        double polarity = TwoBodyConverterMotor.dot(cm.bhat, cm.uvecPhys);
        cm.trapParams.set(5, (float) (signedForcePN * 1e-12 * polarity));

        int settle = TwoBodyConverterMotor.settleSteps(c.dt);
        for (int t = 0; t < settle; t++) {
            mech(cm, c, t, seed);
            cm.mot.forceDotFil.set(0, cm.bondData.get(12));
        }
        return cm;
    }

    /** Model-correct mechanics step. stepS2/stepSup self-delegate to stepC when their fixture is off,
     *  so this dispatch is exact for all three canonical models. No physics is duplicated here. */
    static void mech(TwoBodyConverterMotor.Cmot cm, Cfg c, int t, int seed) {
        switch (c.model) {
            case EXPLICIT_S2_L40 -> TwoBodyConverterMotor.stepS2(cm, t, seed, c.brownian);
            case CALIBRATED_S2_L40 -> TwoBodyConverterMotor.stepSup(cm, t, seed, c.brownian);
            default -> TwoBodyConverterMotor.stepC(cm, t, seed);
        }
    }

    /** Run ONE attachment event to natural detachment or to right-censoring at the window edge. */
    static Event runEvent(Cfg c, Prep prep, double reqF, String dir, int idx) {
        double signedF = "assisting".equals(dir) ? -Math.abs(reqF) : Math.abs(reqF);
        int seed = c.seedBase + idx;
        Event e = new Event();
        e.id = idx; e.state = prep.id; e.reqF = reqF; e.dir = dir; e.signedF = signedF; e.seed = seed;
        e.tAttach = 0.0;

        TwoBodyConverterMotor.Cmot cm;
        try {
            cm = prepare(c, prep, signedF, seed);
        } catch (RuntimeException ex) {
            e.valid = false; e.abortReason = "prepare-failed: " + ex; return e;
        }

        // --- C: an event ALREADY DETACHED at initialization must abort as INVALID ---
        if (cm.mot.boundSeg.get(0) < 0) {
            e.valid = false; e.abortReason = "already-detached-at-init"; return e;
        }
        // --- B: the requested nucleotide state must actually be live at t=0 (chemistry was frozen) ---
        int s0 = cm.mot.nucleotideState.get(0);
        if (s0 != prep.nuc) {
            e.valid = false;
            e.abortReason = "illegal-pre-t0-transition: prepared " + nucName(prep.nuc) + " but t0=" + nucName(s0);
            return e;
        }

        int maxSteps = (int) Math.round(c.maxDwellMs * 1e-3 / c.dt);
        if (maxSteps < 1) {   // a window shorter than one timestep cannot observe anything -- refuse, don't emit lifetime 0
            e.valid = false;
            e.abortReason = String.format(Locale.US, "observation-window-%.3g-ms-shorter-than-dt-%.3g-s", c.maxDwellMs, c.dt);
            return e;
        }
        double[] p0 = { cm.fil.coordX(0), cm.fil.coordY(0), cm.fil.coordZ(0) };

        double fSum = 0, fSq = 0, fMaxDev = 0; long fN = 0;
        boolean detached = false;
        int t = 0;
        for (; t < maxSteps; t++) {
            // realized cross-bridge axial load BEFORE this step's chemistry draw (the value the rate law reads)
            double fPn = cm.mot.forceDotFil.get(0) * 1e12;
            if (!Double.isFinite(fPn)) { e.solverFailures++; }
            else {
                fSum += fPn; fSq += fPn * fPn; fN++;
                fMaxDev = Math.max(fMaxDev, Math.abs(Math.abs(fPn) - Math.abs(signedF)));
                e.maxAxialLoadPn = Math.max(e.maxAxialLoadPn, Math.abs(fPn));
            }

            int sBefore = cm.mot.nucleotideState.get(0);
            cm.mot.setCounts(t, seed, cm.fil.n);
            NucleotideCycleSystem.cycleLymnTaylor(cm.mot.nucleotideState, cm.mot.boundSeg,
                    cm.mot.forceDotFil, cm.mot.forceDotAvg, cm.mot.avgInit, cm.mot.cooldown,
                    cm.mot.stats, cm.mot.nucParams, cm.mot.kinParams, cm.mot.counts);
            int sAfter = cm.mot.nucleotideState.get(0);

            if (sAfter < 0 || sAfter > 3 || !legalTransition(sBefore, sAfter)) e.invalidStates++;
            // the FORCE-DEPENDENT sub-step, timestamped separately (ADP -> NONE)
            if (sBefore == MotorStore.NUC_ADP && sAfter == MotorStore.NUC_NONE && Double.isNaN(e.tAdpRelease))
                e.tAdpRelease = (t + 1) * c.dt;

            if (cm.mot.boundSeg.get(0) < 0) {                    // --- DETACHMENT, recorded EXACTLY ONCE ---
                e.tDetach = (t + 1) * c.dt;
                e.lifetime = e.tDetach - e.tAttach;
                e.censored = 0;
                e.pathway = nucName(sBefore) + "->" + nucName(sAfter);
                e.detachState = nucName(sAfter);
                detached = true; t++;
                break;
            }

            cm.thetaS = TwoBodyConverterMotor.thetaS4a(sAfter);
            mech(cm, c, t, seed);
            cm.mot.forceDotFil.set(0, cm.bondData.get(12));
            double bx = cm.bondData.get(0), by = cm.bondData.get(1), bz = cm.bondData.get(2);
            double bmag = Math.sqrt(bx * bx + by * by + bz * bz);
            cm.mot.forceMag.set(0, (float) bmag);
            if (!Double.isFinite(bmag)) e.solverFailures++;
            else e.maxBondForcePn = Math.max(e.maxBondForcePn, bmag * 1e12);

            double[] pn = { cm.fil.coordX(0), cm.fil.coordY(0), cm.fil.coordZ(0) };
            if (!Double.isFinite(pn[0]) || !Double.isFinite(pn[1]) || !Double.isFinite(pn[2])) e.solverFailures++;
            else {
                double d = Math.abs(TwoBodyConverterMotor.dot(TwoBodyConverterMotor.sub(pn, p0), cm.bhat)) * 1e3;
                e.maxDispNm = Math.max(e.maxDispNm, d);
            }
        }
        if (!detached) {                                          // --- RIGHT-CENSORED (preserved, not discarded) ---
            e.tDetach = Double.NaN;
            e.lifetime = maxSteps * c.dt;
            e.censored = 1;
            e.pathway = "censored";
            e.detachState = nucName(cm.mot.nucleotideState.get(0));
        }
        e.stepsRun = t;
        e.rfMean = fN > 0 ? fSum / fN : Double.NaN;
        e.rfSd = fN > 1 ? Math.sqrt(Math.max(0, fSq / fN - (fSum / fN) * (fSum / fN))) : 0.0;
        e.rfMaxDev = fMaxDev;
        return e;
    }

    // ================================================================ matrix driver

    static boolean runMatrix(Cfg c) {
        long t0 = System.currentTimeMillis();
        TwoBodyConverterMotor.Cmot probe = TwoBodyConverterMotor.buildBoundMotor(c.model, c.dt);
        TwoBodyConverterMotor.initChem4a(probe, c.dt, true);
        String frozen = frozenConfig(c, probe);
        String chash = sha256(frozen).substring(0, 16);
        System.out.print(frozen);
        System.out.println("# config_hash=" + chash + "  code_rev=" + c.rev);

        try { Files.createDirectories(Path.of(c.outDir)); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }

        int nCells = 0, nEv = 0, nInvalid = 0;
        for (Prep prep : c.states) {
            for (double f : c.forces) {
                // at F=0 the two directions are the SAME physical cell -> emit once
                List<String> dirs = (f == 0.0) ? List.of("none") : c.dirs;
                for (String dir : dirs) {
                    String fnPre = String.format(Locale.US, "sm4_batch_%s_%s_F%05.1f.json", prep.id, dir, f);
                    Path pPre = Path.of(c.outDir, fnPre);
                    if (c.resume && Files.exists(pPre) && Files.isReadable(pPre)) {
                        try {
                            String body = Files.readString(pPre);
                            if (body.trim().endsWith("}")) {          // complete file => keep it, skip the cell
                                System.out.printf(Locale.US, "#   [resume: skip %s %s F=%.1f -- %s exists]%n",
                                        prep.id, dir, f, fnPre);
                                nCells++;
                                continue;
                            }
                        } catch (IOException ignored) { /* fall through and recompute */ }
                    }
                    List<Event> evs = new ArrayList<>();
                    for (int i = 0; i < c.events; i++) {
                        Event e = runEvent(c, prep, f, dir, i);
                        evs.add(e);
                        if (!e.valid) nInvalid++;
                    }
                    nEv += evs.size(); nCells++;
                    writeBatch(c, chash, prep, f, dir, evs, pPre);
                    summarizeCell(prep, f, dir, evs);
                }
            }
        }
        double wall = (System.currentTimeMillis() - t0) / 1000.0;
        System.out.printf(Locale.US, "%n# SM4 matrix done: %d cells, %d events, %d invalid, %.1f s wall.%n",
                nCells, nEv, nInvalid, wall);
        System.out.println("# → run: python3 scripts/sm4_analysis.py " + c.outDir);
        return true;
    }

    static void summarizeCell(Prep prep, double f, String dir, List<Event> evs) {
        int nd = 0, nc = 0; double sum = 0; double rf = 0; int nrf = 0;
        for (Event e : evs) {
            if (!e.valid) continue;
            if (e.censored == 0) nd++; else nc++;
            sum += e.lifetime;
            if (Double.isFinite(e.rfMean)) { rf += e.rfMean; nrf++; }
        }
        int n = nd + nc;
        System.out.printf(Locale.US,
                "#   %-8s %-9s F=%5.1f pN | n=%2d detach=%2d cens=%2d | mean obs %7.4f ms | realized load %+7.3f pN%n",
                prep.id, dir, f, n, nd, nc, n > 0 ? sum / n * 1e3 : Double.NaN, nrf > 0 ? rf / nrf : Double.NaN);
    }

    // ================================================================ output

    static String frozenConfig(Cfg c, TwoBodyConverterMotor.Cmot cm) {
        MotorStore m = cm.mot;
        StringBuilder s = new StringBuilder();
        s.append("# ---- FROZEN CONFIGURATION (SM4) ----\n");
        s.append(String.format(Locale.US, "# motor_model=%s  runner=CPU  dt=%.3e s  settle=%d steps  brownian=%s%n",
                c.model.id(), c.dt, TwoBodyConverterMotor.settleSteps(c.dt), c.brownian));
        s.append(String.format(Locale.US, "# chemistry_kernel=NucleotideCycleSystem.cycleLymnTaylor (single release pathway: bound+ATP => detach)%n"));
        s.append(String.format(Locale.US, "# nucParams: dt=%.3e atpOn=%.4g onATP=%.4g offATP=%.4g onPi=%.4g offPi=%.4g onADP=%.4g offADP=%.4g  (/s)%n",
                m.nucParams.get(0), m.nucParams.get(1), m.nucParams.get(2), m.nucParams.get(3),
                m.nucParams.get(4), m.nucParams.get(5), m.nucParams.get(6), m.nucParams.get(7)));
        s.append(String.format(Locale.US, "# kinParams: kOff=%.4g aCatch=%.4g aSlip=%.4g xCatch=%.4g nm xSlip=%.4g nm kT=%.4g J dt=%.3e%n",
                m.kinParams.get(0), m.kinParams.get(1), m.kinParams.get(2),
                m.kinParams.get(3) * 1e9, m.kinParams.get(4) * 1e9, m.kinParams.get(5), m.kinParams.get(6)));
        s.append(String.format(Locale.US, "# kinParams: bindReach=%.4g um  alignTol=%.4g  refractory=%d steps  emaAlpha=%.4g (0 => instantaneous F)%n",
                m.kinParams.get(7), m.kinParams.get(8), (int) m.kinParams.get(10), m.kinParams.get(17)));
        s.append(String.format(Locale.US, "# g(0)=aCatch+aSlip=%.6f (must be 1.0 exactly)%n",
                m.kinParams.get(1) + m.kinParams.get(2)));
        s.append(String.format(Locale.US, "# NOT READ by cycleLymnTaylor (structurally inert on this path): "
                        + "kOff[0]=%.4g, breakForce[11]=%.4g pN, breakCap[12]=%s, fExt[18]=%.4g%n",
                m.kinParams.get(0), m.kinParams.get(11) * 1e12,
                m.kinParams.get(12) > 0.5f ? "ON" : "OFF",
                m.kinParams.getSize() > 18 ? m.kinParams.get(18) : 0f));
        s.append(String.format(Locale.US, "# thetaS: prestroke(ADPPi)=%.3f deg  poststroke(ADP/NONE)=%.3f deg%n",
                Math.toDegrees(TwoBodyConverterMotor.PRESTROKE_THETAS), Math.toDegrees(TwoBodyConverterMotor.ADP_THETAS)));
        s.append(String.format(Locale.US, "# rupture/emergency: RUPTURE_MODE=%d EMERGENCY_ON=%s (HMM-DIMER GPU path; "
                        + "structurally NOT wired into TwoBodyConverterMotor.stepC/stepSup/stepS2 -- inert here). "
                        + "Two-body break-cap is never read by cycleLymnTaylor. Detachment is 100%% natural chemistry.%n",
                ExplicitHmmDimerGpuParams.RUPTURE_MODE, ExplicitHmmDimerGpuParams.EMERGENCY_ON));
        s.append(String.format(Locale.US, "# window: maxDwell=%.4g ms (%d steps) = right-censoring horizon; events/cell=%d; seedBase=%d%n",
                c.maxDwellMs, (int) Math.round(c.maxDwellMs * 1e-3 / c.dt), c.events, c.seedBase));
        s.append("# sign: trapParams[5] = axial external force (N) at filament COM along +uVec; barbed=end2=+uVec.\n");
        s.append("#       signedForce>0 => toward BARBED => OPPOSING (catch side); signedForce<0 => ASSISTING (slip side).\n");
        return s.toString();
    }

    static String signConvention() {
        return "External force enters through `Cmot.trapParams[5]`: a deterministic axial force in newtons applied "
             + "to the filament at its COM along `cm.uvecPhys`, no torque. Project convention: barbed = end2 = `+uVec`. "
             + "**signedForce > 0 => force toward the BARBED end => OPPOSING (resisting) load, the CATCH side.** "
             + "**signedForce < 0 => force toward the POINTED end => ASSISTING load, the SLIP side.** "
             + "This matches the existing `adpReleaseAssay` convention (\"+barbed = opposing\"). The realized "
             + "cross-bridge load is `forceDotFil = bondData[12] = Dot(F8 head force, seg.uVec)`, reported in pN.";
    }

    static void writeBatch(Cfg c, String chash, Prep prep, double f, String dir, List<Event> evs, Path p) {
        StringBuilder s = new StringBuilder();
        s.append("{\n");
        s.append(String.format(Locale.US, "  \"assay\": \"SM4-force-lifetime\",%n"));
        s.append(String.format(Locale.US, "  \"run_class\": \"%s\",%n", esc(c.runClass)));
        s.append(String.format(Locale.US, "  \"motor_model\": \"%s\",%n", c.model.id()));
        s.append(String.format(Locale.US, "  \"runner\": \"cpu\",%n"));
        s.append(String.format(Locale.US, "  \"chemistry_kernel\": \"NucleotideCycleSystem.cycleLymnTaylor\",%n"));
        s.append(String.format(Locale.US, "  \"prepared_state\": \"%s\",%n", prep.id));
        s.append(String.format(Locale.US, "  \"requested_force_pn\": %.6g,%n", f));
        s.append(String.format(Locale.US, "  \"force_dir\": \"%s\",%n", dir));
        s.append(String.format(Locale.US, "  \"dt\": %.6e,%n", c.dt));
        s.append(String.format(Locale.US, "  \"brownian\": %s,%n", c.brownian));
        s.append(String.format(Locale.US, "  \"max_dwell_ms\": %.6g,%n", c.maxDwellMs));
        s.append(String.format(Locale.US, "  \"rupture_mode_desc\": \"two-body break-cap OFF; HMM-dimer RUPTURE_MODE=%d not on this path\",%n",
                ExplicitHmmDimerGpuParams.RUPTURE_MODE));
        s.append(String.format(Locale.US, "  \"sign_convention\": \"%s\",%n", esc(signConvention())));
        s.append(String.format(Locale.US, "  \"scope_note\": \"%s\",%n", esc(
                "SMOKE/apparatus-validation scope. These runs validate the apparatus (force clamp, prepared states, "
              + "first-passage and censoring logic, survival estimators). They are NOT calibration data and must not be "
              + "used to set xCatch, xSlip, or pathway rates. The literature-ready grid (0,1,2,3,4,5,6,8,10,15,20,25 pN "
              + "x {rigor,adp} x {opposing,assisting}) is supported by this harness but deliberately NOT run here.")));
        s.append(String.format(Locale.US, "  \"config_hash\": \"%s\",%n", chash));
        s.append(String.format(Locale.US, "  \"code_rev\": \"%s\",%n", esc(c.rev)));
        s.append(String.format(Locale.US, "  \"build_id\": \"%s\",%n", esc(c.buildId)));
        // FIXTURE IDENTITY is carried on every record: fixed-anchor and explicit-S2 transmit load
        // differently, so their data must never be pooled without retaining which fixture produced it.
        s.append(String.format(Locale.US, "  \"fixture\": \"%s\",%n", c.model.id()));
        s.append("  \"events\": [\n");
        for (int i = 0; i < evs.size(); i++) {
            Event e = evs.get(i);
            s.append("    {");
            s.append(String.format(Locale.US, "\"event_id\": %d, \"state\": \"%s\", \"requested_force_pn\": %.6g, "
                            + "\"force_dir\": \"%s\", \"force_signed_pn\": %.6g, \"seed\": %d, ",
                    e.id, e.state, e.reqF, e.dir, e.signedF, e.seed));
            s.append(String.format(Locale.US, "\"realized_force_mean_pn\": %s, \"realized_force_sd_pn\": %s, "
                            + "\"realized_force_max_dev_pn\": %s, ",
                    num(e.rfMean), num(e.rfSd), num(e.rfMaxDev)));
            s.append(String.format(Locale.US, "\"t_attach_s\": %.9g, \"t_detach_s\": %s, \"lifetime_s\": %.9g, "
                            + "\"censored\": %d, ",
                    e.tAttach, num(e.tDetach), e.lifetime, e.censored));
            s.append(String.format(Locale.US, "\"detach_pathway\": \"%s\", \"detach_state\": \"%s\", "
                            + "\"t_adp_release_s\": %s, ",
                    e.pathway, e.detachState, num(e.tAdpRelease)));
            s.append(String.format(Locale.US, "\"max_disp_nm\": %.6g, \"max_bond_strain_nm\": %.6g, "
                            + "\"max_bond_force_pn\": %.6g, \"max_axial_load_pn\": %.6g, ",
                    e.maxDispNm, e.maxDispNm, e.maxBondForcePn, e.maxAxialLoadPn));
            s.append(String.format(Locale.US, "\"steps_run\": %d, \"invalid_states\": %d, \"solver_failures\": %d, "
                            + "\"valid\": %s, \"abort_reason\": \"%s\"",
                    e.stepsRun, e.invalidStates, e.solverFailures, e.valid, esc(e.abortReason)));
            s.append(i + 1 < evs.size() ? "},\n" : "}\n");
        }
        s.append("  ]\n}\n");
        try { Files.writeString(p, s.toString()); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    static String num(double v) { return Double.isFinite(v) ? String.format(Locale.US, "%.9g", v) : "null"; }
    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }

    // ================================================================ gates (validation A/B/C/E)

    static boolean selftest(Cfg c) {
        boolean ok = true;
        System.out.println("# ---- SM4 apparatus gates (CPU) ----");

        // ---- B: prepared states are actually initialized, and no illegal transition before t=0 ----
        for (Prep p : new Prep[]{ Prep.RIGOR, Prep.ADP, Prep.CYCLING }) {
            TwoBodyConverterMotor.Cmot cm = prepare(c, p, 0.0, c.seedBase);
            int s0 = cm.mot.nucleotideState.get(0);
            boolean bound = cm.mot.boundSeg.get(0) >= 0;
            boolean good = (s0 == p.nuc) && bound;
            ok &= good;
            System.out.printf(Locale.US, "  [%s] B prepared-state %-8s -> t0 state %-12s bound=%s%n",
                    good ? "PASS" : "FAIL", p.id, nucName(s0), bound);
        }

        // ---- A: force clamp tracks the request, and the SIGN reverses on reversal ----
        System.out.println("  --- A force clamp (realized cross-bridge axial load vs request) ---");
        double[] probe = { 0.0, 3.0, 6.0, 10.0 };
        double prevOpp = Double.NaN, prevAsst = Double.NaN;
        for (double f : probe) {
            TwoBodyConverterMotor.Cmot a = prepare(c, Prep.ADP, +f, c.seedBase);
            TwoBodyConverterMotor.Cmot b = prepare(c, Prep.ADP, -f, c.seedBase);
            double fa = a.mot.forceDotFil.get(0) * 1e12, fb = b.mot.forceDotFil.get(0) * 1e12;
            System.out.printf(Locale.US, "    F=%5.1f pN | opposing realized %+8.4f pN | assisting realized %+8.4f pN%n", f, fa, fb);
            if (f > 0) {
                boolean signFlip = Math.signum(fa) != Math.signum(fb) && Math.abs(fa) > 1e-6 && Math.abs(fb) > 1e-6;
                boolean monotone = Double.isNaN(prevOpp) || Math.abs(fa) > Math.abs(prevOpp);
                ok &= signFlip && monotone;
                System.out.printf(Locale.US, "      [%s] sign reverses on direction reversal; |load| grows with request%n",
                        (signFlip && monotone) ? "PASS" : "FAIL");
            }
            prevOpp = fa; prevAsst = fb;
        }

        // ---- C: first-passage logic — detach recorded once; censoring preserved ----
        System.out.println("  --- C first-passage / censoring ---");
        Cfg tiny = shallowCopy(c); tiny.maxDwellMs = 0.02;   // 8 steps at dt=2.5e-6 => guarantees censoring
        Event ce = runEvent(tiny, Prep.ADP, 0.0, "none", 0);
        boolean censOk = ce.censored == 1 && Double.isNaN(ce.tDetach) && ce.lifetime > 0 && ce.valid;
        ok &= censOk;
        System.out.printf(Locale.US, "    [%s] short window -> censored=%d, t_detach=%s, lifetime=%.6g s (preserved, not discarded)%n",
                censOk ? "PASS" : "FAIL", ce.censored, Double.isNaN(ce.tDetach) ? "NaN" : "set", ce.lifetime);

        Cfg degen = shallowCopy(c); degen.maxDwellMs = 1e-4;  // sub-timestep window => must REFUSE, not emit lifetime 0
        Event ge = runEvent(degen, Prep.ADP, 0.0, "none", 0);
        boolean degenOk = !ge.valid && ge.abortReason.startsWith("observation-window");
        ok &= degenOk;
        System.out.printf(Locale.US, "    [%s] sub-timestep window -> refused as invalid (%s)%n",
                degenOk ? "PASS" : "FAIL", ge.abortReason);

        Cfg lng = shallowCopy(c); lng.maxDwellMs = 40.0;     // generous window => genuine detachment
        Event de = runEvent(lng, Prep.RIGOR, 0.0, "none", 0);
        boolean detOk = de.censored == 0 && Double.isFinite(de.tDetach)
                && Math.abs(de.lifetime - de.tDetach) < 1e-15 && de.valid;
        ok &= detOk;
        System.out.printf(Locale.US, "    [%s] long window  -> detached once at t=%.6g s via %s%n",
                detOk ? "PASS" : "FAIL", de.tDetach, de.pathway);

        // ---- B(2): rigor and ADP are DISTINGUISHABLE ----
        System.out.println("  --- B(2) rigor vs ADP distinguishability (unloaded, n=12) ---");
        double mr = meanLifetimeMs(lng, Prep.RIGOR, 0.0, "none", 12);
        double ma = meanLifetimeMs(lng, Prep.ADP, 0.0, "none", 12);
        boolean dist = Double.isFinite(mr) && Double.isFinite(ma) && ma > 2.0 * mr;
        ok &= dist;
        System.out.printf(Locale.US, "    [%s] rigor %.4f ms vs ADP %.4f ms (ADP must be markedly longer: it carries the extra ADP->NONE step)%n",
                dist ? "PASS" : "FAIL", mr, ma);

        System.out.println("\n# SM4 gates: " + (ok ? "ALL PASS" : "FAILURES PRESENT"));
        return ok;
    }

    static double meanLifetimeMs(Cfg c, Prep p, double f, String dir, int n) {
        double s = 0; int k = 0;
        for (int i = 0; i < n; i++) {
            Event e = runEvent(c, p, f, dir, i);
            if (e.valid) { s += e.lifetime; k++; }
        }
        return k > 0 ? s / k * 1e3 : Double.NaN;
    }

    static Cfg shallowCopy(Cfg c) {
        Cfg d = new Cfg();
        d.model = c.model; d.dt = c.dt; d.kAx = c.kAx; d.kTr = c.kTr;
        d.events = c.events; d.seedBase = c.seedBase; d.maxDwellMs = c.maxDwellMs;
        d.brownian = c.brownian; d.outDir = c.outDir; d.rev = c.rev; d.runClass = c.runClass;
        return d;
    }

    private Sm4ForceLifetimeHarness() { }
}
