package softbox;


import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * GPU-crash lifecycle diagnostic — the single owner of the durable {@link CrashTrace}, the shutdown hook, the
 * heartbeat, the explicit {@code TornadoExecutionPlan} close, and the optional teardown pauses.
 *
 * <p><b>Diagnostic instrumentation only.</b> It records WHEN the process reached each lifecycle window so that,
 * after a hard freeze, the last durable marker localises the failure to GPU execution / result handling /
 * {@code plan.close()} / post-close cleanup / JVM shutdown / an unrelated period. It proves nothing about root
 * cause: an Xid 79 ("GPU has fallen off the bus") can equally arise from the NVIDIA driver or GSP firmware, PCIe
 * link, power delivery, the motherboard, or the GPU itself.
 *
 * <p><b>Inactive by default.</b> With tracing off every method here is a cheap no-op and the host program behaves
 * exactly as before — including keeping the project's historical behaviour of never calling {@code plan.close()}.
 * Activate with any of:
 * <pre>
 *   -gpu-crash-trace                     (application argument)
 *   SOFTBOX_GPU_CRASH_TRACE=1            (environment; what scripts/run_gpu_monitored.sh sets)
 *   -Dsoftbox.gpu.crash.trace=true       (JVM property)
 * </pre>
 * Options (argument | property | environment), resolved in that precedence order:
 * <pre>
 *   -gpu-crash-trace-dir &lt;path&gt;      softbox.gpu.crash.dir           SOFTBOX_GPU_CRASH_TRACE_DIR   (~/tornado-crash-traces)
 *   -gpu-crash-heartbeat-sec &lt;n&gt;     softbox.gpu.crash.heartbeat    SOFTBOX_GPU_CRASH_HEARTBEAT_SEC   (5; 0 = off)
 *   -gpu-crash-execute-every &lt;n&gt;     softbox.gpu.crash.execevery    SOFTBOX_GPU_CRASH_EXECUTE_EVERY   (0 = first+last only)
 *   -gpu-pre-close-pause-sec &lt;n&gt;     softbox.gpu.crash.preclose     SOFTBOX_GPU_PRE_CLOSE_PAUSE_SEC   (0)
 *   -gpu-post-close-pause-sec &lt;n&gt;    softbox.gpu.crash.postclose    SOFTBOX_GPU_POST_CLOSE_PAUSE_SEC  (0)
 *   -gpu-close-policy &lt;p&gt;            softbox.gpu.crash.closepolicy  SOFTBOX_GPU_CLOSE_POLICY          (normal)
 *   -gpu-crash-no-journal               softbox.gpu.crash.journal=false                                (journald mirror on)
 * </pre>
 * Close policies: {@code normal} (explicit {@code plan.close()} — the routine setting), {@code skip-explicit}
 * (no close; the plan becomes unreachable and may be freed by GC), {@code jvm-only} (no close AND a strong
 * reference is retained to JVM exit, so only native/JVM teardown can free it). The two non-normal policies are
 * controlled experiments (handoff test matrix D/E); they print a prominent warning and must never be used for
 * ordinary campaigns.
 */
public final class TornadoCrashDiagnostic {

    // ---------------------------------------------------------------- lifecycle states (heartbeat payload)
    public static final String ST_STARTING   = "STARTING";
    public static final String ST_PLAN_CTOR  = "PLAN_CONSTRUCTION";
    public static final String ST_EXECUTING  = "EXECUTING";
    public static final String ST_RESULT     = "RESULT_PROCESSING";
    public static final String ST_PRE_CLOSE  = "PRE_CLOSE_PAUSE";
    public static final String ST_CLOSING    = "PLAN_CLOSE";
    public static final String ST_POST_CLOSE = "POST_CLOSE_PAUSE";
    public static final String ST_RETURNED   = "MAIN_RETURNED";
    public static final String ST_SHUTDOWN   = "SHUTDOWN";

    private static volatile TornadoCrashDiagnostic INSTANCE;

    private final CrashTrace trace;
    private final String assay;
    private final int heartbeatSec, executeEvery, preCloseSec, postCloseSec;
    private final String closePolicy;
    private final boolean journalOn;
    private final Map<String, String> context = new LinkedHashMap<>();

    private volatile String state = ST_STARTING;
    private volatile long execStarted = -1, execCompleted = -1;   // executeIndex counters (in-memory, heartbeat-reported)
    private volatile int lastStepStarted = -1, lastStepCompleted = -1;
    private volatile boolean shuttingDown = false;
    private Thread heartbeat;
    private Object retainedPlan;                                   // jvm-only policy: prevent GC-driven free
    private double dt = 0;
    private long execLoopStart, execLoopEnd;                       // step bounds of the active loop
    private String graphName = "";

    private TornadoCrashDiagnostic(CrashTrace trace, String assay, int heartbeatSec, int executeEvery,
                                   int preCloseSec, int postCloseSec, String closePolicy, boolean journal) {
        this.trace = trace; this.assay = assay; this.heartbeatSec = heartbeatSec; this.executeEvery = executeEvery;
        this.preCloseSec = preCloseSec; this.postCloseSec = postCloseSec; this.closePolicy = closePolicy;
        this.journalOn = journal;
    }

    // ================================================================================ activation / init

    public static boolean active() { return INSTANCE != null; }

    /**
     * Open the trace, write PROGRAM_START with the full runtime/launch configuration, register the shutdown hook
     * IMMEDIATELY (before any GPU work), and start the heartbeat. No-op when tracing is not requested, and no-op on
     * a second call. Never throws: an instrumentation failure must not take down a campaign.
     */
    public static synchronized boolean init(String assay, String[] args) {
        if (INSTANCE != null) return true;
        if (!requested(args)) return false;
        try {
            int hb   = optInt(args, "-gpu-crash-heartbeat-sec", "softbox.gpu.crash.heartbeat", "SOFTBOX_GPU_CRASH_HEARTBEAT_SEC", 5);
            int ev   = optInt(args, "-gpu-crash-execute-every", "softbox.gpu.crash.execevery", "SOFTBOX_GPU_CRASH_EXECUTE_EVERY", 0);
            int pre  = optInt(args, "-gpu-pre-close-pause-sec", "softbox.gpu.crash.preclose", "SOFTBOX_GPU_PRE_CLOSE_PAUSE_SEC", 0);
            int post = optInt(args, "-gpu-post-close-pause-sec", "softbox.gpu.crash.postclose", "SOFTBOX_GPU_POST_CLOSE_PAUSE_SEC", 0);
            String pol = optStr(args, "-gpu-close-policy", "softbox.gpu.crash.closepolicy", "SOFTBOX_GPU_CLOSE_POLICY", "normal");
            if (!pol.equals("normal") && !pol.equals("skip-explicit") && !pol.equals("jvm-only")) {
                System.err.println("[gpu-crash-trace] unknown -gpu-close-policy '" + pol + "' — using 'normal'");
                pol = "normal";
            }
            boolean jrn = !hasFlag(args, "-gpu-crash-no-journal")
                    && !"false".equalsIgnoreCase(System.getProperty("softbox.gpu.crash.journal", "true"));

            String dir = optStr(args, "-gpu-crash-trace-dir", "softbox.gpu.crash.dir", "SOFTBOX_GPU_CRASH_TRACE_DIR",
                    Path.of(System.getProperty("user.home"), "tornado-crash-traces").toString());
            long pid = ProcessHandle.current().pid();
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US)
                    .format(ZonedDateTime.now(ZoneOffset.UTC));
            String name = "run-" + stamp + "-pid" + pid + "-" + safe(assay) + ".log";
            CrashTrace t = new CrashTrace(Path.of(dir, name));

            TornadoCrashDiagnostic d = new TornadoCrashDiagnostic(t, assay, hb, ev, pre, post, pol, jrn);
            INSTANCE = d;
            d.programStart(args);
            d.installShutdownHook();
            d.startHeartbeat();
            if (!pol.equals("normal")) {
                String warn = "*** NON-NORMAL CLOSE POLICY '" + pol + "' — controlled diagnostic ONLY, never a campaign ***";
                System.err.println(warn); System.out.println(warn);
                d.trace.mark("CLOSE_POLICY_NON_NORMAL", "policy=" + pol);
            }
            System.out.println("[gpu-crash-trace] " + t.path());
            return true;
        } catch (Throwable e) {
            System.err.println("[gpu-crash-trace] DISABLED — could not open trace: " + e);
            INSTANCE = null;
            return false;
        }
    }

    private static boolean requested(String[] args) {
        if (hasFlag(args, "-gpu-crash-trace")) return true;
        String p = System.getProperty("softbox.gpu.crash.trace");
        if (p != null && (p.isEmpty() || p.equalsIgnoreCase("true") || p.equals("1"))) return true;
        String e = System.getenv("SOFTBOX_GPU_CRASH_TRACE");
        return e != null && (e.equals("1") || e.equalsIgnoreCase("true"));
    }

    // ================================================================================ PROGRAM_START payload

    private void programStart(String[] args) {
        StringBuilder sb = new StringBuilder();
        kv(sb, "assay", assay);
        kv(sb, "pid", Long.toString(trace.pid()));
        kv(sb, "java", System.getProperty("java.runtime.version", System.getProperty("java.version")));
        kv(sb, "jvm", System.getProperty("java.vm.name") + "/" + System.getProperty("java.vm.version"));
        kv(sb, "os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        kv(sb, "cwd", System.getProperty("user.dir"));
        kv(sb, "traceFile", trace.path().toString());
        kv(sb, "heartbeatSec", Integer.toString(heartbeatSec));
        kv(sb, "executeEvery", Integer.toString(executeEvery));
        kv(sb, "preClosePauseSec", Integer.toString(preCloseSec));
        kv(sb, "postClosePauseSec", Integer.toString(postCloseSec));
        kv(sb, "closePolicy", closePolicy);
        trace.mark("PROGRAM_START", sb.toString());

        // --- launch configuration (the flags the explicit-S2 PTX path is known to require) ---
        String fma  = System.getProperty("tornado.enable.fma");
        String bail = System.getProperty("tornado.recover.bailout");
        String bcs  = System.getProperty("tornado.tvm.maxbytecodesize");
        StringBuilder lf = new StringBuilder();
        kv(lf, "tornado.enable.fma", String.valueOf(fma));
        kv(lf, "tornado.recover.bailout", String.valueOf(bail));
        kv(lf, "tornado.tvm.maxbytecodesize", String.valueOf(bcs));
        kv(lf, "tornado.version", prop("tornado.version", "unknown"));
        kv(lf, "tornadoHome", System.getenv().getOrDefault("TORNADOVM_HOME", prop("tornado.home", "unknown")));
        trace.mark("LAUNCH_CONFIG", lf.toString());

        // Required explicit-S2 device configuration (docs/EXPLICIT_S2_GPU_LOWERING_REGRESSION_FINDINGS.md):
        // FMA must be OFF (matS2SolveStep FMA-lowers to an ArithmeticLIRLowerable NPE on the PTX backend),
        // bailout OFF (a lowering failure THROWS instead of silently falling back to the sequential CPU runner),
        // and the raised bytecode cap for the large solver kernel. WARN ONLY — never silently changed here.
        StringBuilder miss = new StringBuilder();
        if (!"false".equals(fma))  kv(miss, "tornado.enable.fma", String.valueOf(fma) + "(want false)");
        if (!"false".equals(bail)) kv(miss, "tornado.recover.bailout", String.valueOf(bail) + "(want false)");
        long cap = 0; try { cap = bcs == null ? 0 : Long.parseLong(bcs.trim()); } catch (NumberFormatException ignored) { }
        if (cap < 65536) kv(miss, "tornado.tvm.maxbytecodesize", String.valueOf(bcs) + "(want >=65536)");
        if (miss.length() > 0) {
            trace.mark("LAUNCH_CONFIG_WARNING",
                    "missingOrUnexpected: " + miss + " note=explicit-S2 device graphs need these; NOT altered by the tracer");
        }

        StringBuilder as = new StringBuilder();
        for (String a : args) { if (as.length() > 0) as.append(' '); as.append(a); }
        trace.mark("ARGS", "argv=[" + as + "]");
        trace.mark("GIT", gitInfo());
        journal("PROGRAM_START assay=" + assay + " pid=" + trace.pid());
    }

    private static String prop(String k, String dflt) { String v = System.getProperty(k); return v == null ? dflt : v; }

    private static String gitInfo() {
        String rev = shell("git", "rev-parse", "HEAD");
        String branch = shell("git", "rev-parse", "--abbrev-ref", "HEAD");
        String dirty = shell("git", "status", "--porcelain");
        StringBuilder sb = new StringBuilder();
        kv(sb, "commit", rev.isEmpty() ? "unknown" : rev);
        kv(sb, "branch", branch.isEmpty() ? "unknown" : branch);
        kv(sb, "dirty", dirty.isEmpty() ? "clean" : ("dirty(" + dirty.split("\n").length + " files)"));
        return sb.toString();
    }

    private static String shell(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return ""; }
            return p.exitValue() == 0 ? out : "";
        } catch (Throwable t) { return ""; }
    }

    // ================================================================================ context / device info

    /** Attach campaign identity (seed, density, arm, outdir, ...) to every subsequent execute marker. */
    public static void context(String key, String value) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.context.put(key, CrashTrace.sanitize(value));
    }
    public static void context(String key, long value)   { context(key, Long.toString(value)); }
    public static void context(String key, double value) { context(key, String.format(Locale.US, "%.6g", value)); }

    /** Simulation dt, so execute markers can report simulationTime. */
    public static void simDt(double dtSeconds) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.dt = dtSeconds; context("dt", String.format(Locale.US, "%.4g", dtSeconds));
    }

    private String contextStr() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : context.entrySet()) kv(sb, e.getKey(), e.getValue());
        return sb.toString();
    }

    // ================================================================================ generic markers

    public static void mark(String phase) { mark(phase, ""); }
    public static void mark(String phase, String details) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.trace.mark(phase, details);
    }

    /** Update only the heartbeat's reported lifecycle state (no durable marker). */
    public static void state(String s) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.state = s;
    }

    // ================================================================================ plan construction

    public static void planConstructionBegin(String details) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.state = ST_PLAN_CTOR;
        d.trace.mark("PLAN_CONSTRUCTION_BEGIN", join(details, d.contextStr()));
        d.journal("PLAN_CONSTRUCTION_BEGIN");
    }

    /** {@code plan} may be null (construction failed); device identity is queried reflectively and never throws. */
    public static void planConstructionEnd(Object plan, String details) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.trace.mark("PLAN_CONSTRUCTION_END", join(details, join(deviceInfo(plan), d.contextStr())));
        d.journal("PLAN_CONSTRUCTION_END");
    }

    public static void planConstructionThrew(Throwable t) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.trace.mark("PLAN_CONSTRUCTION_THREW", "type=" + t.getClass().getName() + " message=" + CrashTrace.sanitize(t.getMessage()));
    }

    /**
     * Best-effort device identity, read AFTER the plan exists so the TornadoVM runtime is already initialised —
     * querying it earlier would change runtime initialisation order. Reflection keeps this class free of any
     * TornadoVM compile-time dependency (so it is testable without the runtime).
     */
    private static String deviceInfo(Object plan) {
        if (plan == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            Object dev = plan.getClass().getMethod("getDevice", int.class).invoke(plan, 0);
            if (dev != null) {
                kv(sb, "device", String.valueOf(dev));
                for (String m : new String[]{ "getPhysicalDevice", "getPlatformName", "getBackendName" }) {
                    try { kv(sb, m.replace("get", "dev"), String.valueOf(dev.getClass().getMethod(m).invoke(dev))); }
                    catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    // ================================================================================ execute window

    /**
     * Open the execute window. {@code stepEnd} is the index of the LAST {@code plan.execute()} the loop will make
     * (so the final call can always be marked durably); pass {@code stepStart == stepEnd} for a single execute.
     */
    public static void executeLoopBegin(String graph, int stepStart, int stepEnd, String details) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.graphName = graph; d.execLoopStart = stepStart; d.execLoopEnd = stepEnd;
        d.execStarted = -1; d.execCompleted = -1; d.lastStepStarted = -1; d.lastStepCompleted = -1;
        d.state = ST_EXECUTING;
        d.trace.mark("PLAN_EXECUTE_BEGIN",
                join("graphName=" + graph + " stepStart=" + stepStart + " stepEnd=" + stepEnd, join(details, d.contextStr())));
        d.journal("PLAN_EXECUTE_BEGIN graph=" + graph + " steps=" + stepStart + ".." + stepEnd);
    }

    /**
     * Called immediately BEFORE each {@code plan.execute()}. Always updates the in-memory counters the heartbeat
     * reports (so the last execute is localisable to ~one heartbeat interval at zero I/O cost); writes a durable
     * marker only for the FIRST call, the FINAL call, and every {@code -gpu-crash-execute-every} calls. No
     * per-timestep disk forcing.
     */
    public static void beforeExecute(int step) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        long idx = d.execStarted + 1; d.execStarted = idx; d.lastStepStarted = step;
        if (d.durableExecute(idx, step)) d.trace.mark("EXECUTE_CALL_BEGIN", d.execDetails(idx, step));
    }

    /** Called immediately AFTER each {@code plan.execute()} returns. */
    public static void afterExecute(int step) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        long idx = d.execCompleted + 1; d.execCompleted = idx; d.lastStepCompleted = step;
        if (d.durableExecute(idx, step)) d.trace.mark("EXECUTE_CALL_END", d.execDetails(idx, step));
    }

    private boolean durableExecute(long idx, int step) {
        if (idx == 0) return true;                                   // first call
        if (step >= execLoopEnd) return true;                        // final planned call
        return executeEvery > 0 && (idx % executeEvery) == 0;
    }

    private String execDetails(long idx, int step) {
        StringBuilder sb = new StringBuilder();
        kv(sb, "executeIndex", Long.toString(idx));
        kv(sb, "step", Integer.toString(step));
        kv(sb, "stepStart", Long.toString(execLoopStart));
        kv(sb, "stepEnd", Long.toString(execLoopEnd));
        if (dt > 0) kv(sb, "simulationTime", String.format(Locale.US, "%.6g", step * dt));
        kv(sb, "graphName", graphName);
        return join(sb.toString(), contextStr());
    }

    public static void executeLoopEnd(String details) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.trace.mark("PLAN_EXECUTE_END",
                join("executeCalls=" + (d.execCompleted + 1) + " lastStep=" + d.lastStepCompleted
                        + " graphName=" + d.graphName, join(details, d.contextStr())));
        d.journal("PLAN_EXECUTE_END calls=" + (d.execCompleted + 1));
    }

    public static void executeThrew(Throwable t) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.trace.mark("PLAN_EXECUTE_THREW", "executeIndexStarted=" + d.execStarted + " executeIndexCompleted=" + d.execCompleted
                + " step=" + d.lastStepStarted + " type=" + t.getClass().getName() + " message=" + CrashTrace.sanitize(t.getMessage()));
    }

    // ================================================================================ result handling

    public static void resultProcessingBegin(String details) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.state = ST_RESULT;
        d.trace.mark("RESULT_PROCESSING_BEGIN", join(details, d.contextStr()));
        d.journal("RESULT_PROCESSING_BEGIN");
    }

    public static void resultProcessingEnd(String details) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.trace.mark("RESULT_PROCESSING_END", join(details, d.contextStr()));
        d.journal("RESULT_PROCESSING_END");
    }

    public static void resultProcessingThrew(Throwable t) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.trace.mark("RESULT_PROCESSING_THREW", "type=" + t.getClass().getName() + " message=" + CrashTrace.sanitize(t.getMessage()));
    }

    /**
     * All device results the run relies on have been transferred to the host and consumed.
     *
     * <p>Synchronisation audit (this project): every instrumented graph declares its readbacks with
     * {@code transferToHost(DataTransferMode.EVERY_EXECUTION, ...)}, and {@code TornadoExecutionPlan.execute()} is
     * blocking — it returns only after the declared copy-outs have landed in the host arrays, which the harness
     * reads on the very next line. Result consumption IS the synchronisation; there is no separate device-sync call
     * in the normal path, so none is added here (adding one would change execution semantics and cost).
     */
    public static void gpuWorkDeclaredFinished(String details) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.trace.mark("GPU_WORK_DECLARED_FINISHED",
                join("syncModel=blocking-execute+EVERY_EXECUTION-copyout-consumed-by-host", join(details, d.contextStr())));
        d.journal("GPU_WORK_DECLARED_FINISHED");
    }

    // ================================================================================ close

    /**
     * The teardown window: PRE_CLOSE_PAUSE → PLAN_CLOSE → POST_CLOSE_PAUSE, under the configured close policy.
     *
     * <p>With tracing OFF this is a no-op and the plan is left exactly as the project has always left it (not
     * closed). With tracing ON and the routine {@code normal} policy the plan is closed EXPLICITLY, so the close
     * interval is bounded by two durable markers. A throw from {@code close()} is marked and then propagated —
     * the failure is never swallowed. Call this only after results are durable on disk.
     */
    public static void closePlan(AutoCloseable plan, String details) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.doClose(plan, details);
    }

    private void doClose(AutoCloseable plan, String details) {
        state = ST_PRE_CLOSE;
        trace.mark("PRE_CLOSE_PAUSE_BEGIN", "seconds=" + preCloseSec);
        sleepSec(preCloseSec);
        trace.mark("PRE_CLOSE_PAUSE_END", "seconds=" + preCloseSec);

        state = ST_CLOSING;
        if (plan == null) {
            trace.mark("PLAN_CLOSE_SKIPPED", "reason=no-plan policy=" + closePolicy);
        } else if (closePolicy.equals("normal")) {
            trace.mark("PLAN_CLOSE_BEGIN", join("policy=normal", join(details, contextStr())));
            journal("PLAN_CLOSE_BEGIN");
            try {
                plan.close();
            } catch (Throwable t) {
                trace.mark("PLAN_CLOSE_THREW", "type=" + t.getClass().getName() + " message=" + CrashTrace.sanitize(t.getMessage()));
                journal("PLAN_CLOSE_THREW " + t.getClass().getSimpleName());
                throw (t instanceof RuntimeException re) ? re : new RuntimeException(t);
            }
            trace.mark("PLAN_CLOSE_END", "policy=normal");
            journal("PLAN_CLOSE_END");
        } else {
            if (closePolicy.equals("jvm-only")) retainedPlan = plan;   // hold a strong ref so GC cannot free it
            trace.mark("PLAN_CLOSE_SKIPPED", "policy=" + closePolicy
                    + " WARNING=controlled-diagnostic-only-device-resources-intentionally-retained");
        }

        state = ST_POST_CLOSE;
        trace.mark("POST_CLOSE_PAUSE_BEGIN", "seconds=" + postCloseSec);
        sleepSec(postCloseSec);
        trace.mark("POST_CLOSE_PAUSE_END", "seconds=" + postCloseSec);
    }

    private void sleepSec(int s) {
        if (s <= 0) return;
        try { Thread.sleep(s * 1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ================================================================================ main return / exit

    public static void normalMainReturn(String details) {
        TornadoCrashDiagnostic d = INSTANCE; if (d == null) return;
        d.state = ST_RETURNED;
        d.trace.mark("NORMAL_MAIN_RETURN", join(details, d.contextStr()));
        d.journal("NORMAL_MAIN_RETURN " + details);
    }

    /** {@code NORMAL_MAIN_RETURN} then {@code System.exit(code)} — the shutdown hook still writes its markers. */
    public static void exit(int code) {
        normalMainReturn("exitCode=" + code);
        System.exit(code);
    }

    // ================================================================================ heartbeat

    private void startHeartbeat() {
        if (heartbeatSec <= 0) return;
        heartbeat = new Thread(() -> {
            while (!shuttingDown) {
                try { Thread.sleep(heartbeatSec * 1000L); } catch (InterruptedException e) { return; }
                if (shuttingDown) return;
                trace.mark("HEARTBEAT", "state=" + state
                        + " executeIndexStarted=" + execStarted + " executeIndexCompleted=" + execCompleted
                        + " lastStepStarted=" + lastStepStarted + " lastStepCompleted=" + lastStepCompleted);
            }
        }, "gpu-crash-heartbeat");
        heartbeat.setDaemon(true);        // never keeps the JVM alive; never touches TornadoVM
        heartbeat.setPriority(Thread.MIN_PRIORITY);
        heartbeat.start();
    }

    // ================================================================================ shutdown hook

    private void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shuttingDown = true;
            state = ST_SHUTDOWN;
            Thread h = heartbeat;
            if (h != null) h.interrupt();
            // The trace channel is deliberately still OPEN here: it is closed only at the END of this hook, so the
            // shutdown markers are written and forced through a live channel.
            trace.mark("SHUTDOWN_HOOK_ENTERED",
                    "executeIndexStarted=" + execStarted + " executeIndexCompleted=" + execCompleted
                            + " lastStepCompleted=" + lastStepCompleted + " closePolicy=" + closePolicy);
            journal("SHUTDOWN_HOOK_ENTERED");
            trace.mark("SHUTDOWN_HOOK_COMPLETED", "assay=" + assay);
            journal("SHUTDOWN_HOOK_COMPLETED");
            trace.close();                 // idempotent
        }, "gpu-crash-shutdown-hook"));
    }

    // ================================================================================ journald mirror

    /**
     * Mirror MAJOR lifecycle markers into journald so the Java timeline can be aligned with kernel NVRM/Xid
     * messages on one clock. Never called for HEARTBEAT or per-execute ticks (no process spawn on a hot path).
     * Any failure is ignored — the per-run file is the primary trace.
     */
    private void journal(String msg) {
        if (!journalOn) return;
        try {
            Process p = new ProcessBuilder("logger", "--tag", "tornado-teardown",
                    "pid=" + trace.pid() + " assay=" + assay + " " + msg)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Throwable ignored) { }
    }

    // ================================================================================ small helpers

    public static Path tracePath() { TornadoCrashDiagnostic d = INSTANCE; return d == null ? null : d.trace.path(); }

    private static void kv(StringBuilder sb, String k, String v) {
        if (sb.length() > 0) sb.append(' ');
        sb.append(k).append('=').append(CrashTrace.sanitize(v == null ? "" : v));
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        return a + " " + b;
    }

    private static String safe(String s) {
        if (s == null || s.isEmpty()) return "run";
        return s.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static boolean hasFlag(String[] args, String f) {
        if (args == null) return false;
        for (String a : args) if (f.equals(a)) return true;
        return false;
    }

    private static String optStr(String[] args, String flag, String prop, String env, String dflt) {
        if (args != null) for (int i = 0; i < args.length - 1; i++) if (flag.equals(args[i])) return args[i + 1];
        String p = System.getProperty(prop);
        if (p != null && !p.isEmpty()) return p;
        String e = System.getenv(env);
        if (e != null && !e.isEmpty()) return e;
        return dflt;
    }

    private static int optInt(String[] args, String flag, String prop, String env, int dflt) {
        String s = optStr(args, flag, prop, env, null);
        if (s == null) return dflt;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return dflt; }
    }
}
