package softbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validation harness for the GPU crash-trace instrumentation ({@link CrashTrace} +
 * {@link TornadoCrashDiagnostic}). NO GPU work, NO physics — it exercises the logger's lifecycle against a stub
 * {@code AutoCloseable} plan and then verifies a produced trace file offline.
 *
 * <pre>
 *   -emit             run one synthetic instrumented lifecycle; prints TRACE_PATH=&lt;file&gt;
 *   -verify &lt;file&gt;    check an existing trace (from -emit OR from a real monitored GPU run)
 * </pre>
 * {@code -verify} runs in a SECOND JVM (see {@code scripts/run_crashtrace_validate.sh}) so the shutdown-hook
 * markers of the emitting process are on disk before they are checked — a same-process check could not see them.
 */
public final class CrashTraceValidationHarness {

    public static void main(String[] args) {
        String verify = null; boolean emit = false; int holdSec = 7;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-emit"    -> emit = true;
                case "-verify"  -> verify = args[++i];
                case "-hold"    -> holdSec = Integer.parseInt(args[++i]);
                default -> { }
            }
        }
        if (verify != null) { System.exit(verify(Path.of(verify)) ? 0 : 1); }
        if (!emit) { System.out.println("usage: CrashTraceValidationHarness -emit | -verify <traceFile>"); System.exit(2); }

        // ---- synthetic instrumented lifecycle (stub plan; no TornadoVM involvement) ----
        if (!TornadoCrashDiagnostic.init("crashtrace-validate", args)) {
            System.out.println("*** tracing NOT active — pass -gpu-crash-trace (or SOFTBOX_GPU_CRASH_TRACE=1) ***");
            System.exit(2);
        }
        Path tp = TornadoCrashDiagnostic.tracePath();
        System.out.println("TRACE_PATH=" + tp);

        TornadoCrashDiagnostic.simDt(2.5e-6);
        TornadoCrashDiagnostic.context("campaignArm", "validation");
        TornadoCrashDiagnostic.context("seed", 101);

        final boolean[] closed = { false };
        AutoCloseable stubPlan = () -> closed[0] = true;

        TornadoCrashDiagnostic.planConstructionBegin("graph=stub");
        TornadoCrashDiagnostic.planConstructionEnd(stubPlan, "stub=true");

        int steps = 40;
        TornadoCrashDiagnostic.executeLoopBegin("stub", 0, steps - 1, "executeCallsPlanned=" + steps);
        for (int t = 0; t < steps; t++) {
            TornadoCrashDiagnostic.beforeExecute(t);
            try { Thread.sleep(holdSec * 1000L / steps); } catch (InterruptedException ignored) { }   // let heartbeats land
            TornadoCrashDiagnostic.afterExecute(t);
        }
        TornadoCrashDiagnostic.executeLoopEnd("");

        TornadoCrashDiagnostic.resultProcessingBegin("");
        TornadoCrashDiagnostic.resultProcessingEnd("");
        TornadoCrashDiagnostic.gpuWorkDeclaredFinished("stub=true");
        TornadoCrashDiagnostic.closePlan(stubPlan, "graph=stub");
        System.out.println("stub plan closed=" + closed[0]);
        TornadoCrashDiagnostic.exit(closed[0] ? 0 : 1);
    }

    // ================================================================================ offline verification

    /** Required markers, in the order they must appear (subsequence match — other markers may interleave). */
    private static final String[] ORDER = {
        "TRACE_OPEN", "PROGRAM_START", "LAUNCH_CONFIG",
        "PLAN_CONSTRUCTION_BEGIN", "PLAN_CONSTRUCTION_END",
        "PLAN_EXECUTE_BEGIN", "PLAN_EXECUTE_END",
        "RESULT_PROCESSING_BEGIN", "RESULT_PROCESSING_END",
        "GPU_WORK_DECLARED_FINISHED",
        "PRE_CLOSE_PAUSE_BEGIN", "PRE_CLOSE_PAUSE_END",
        "PLAN_CLOSE_BEGIN", "PLAN_CLOSE_END",
        "POST_CLOSE_PAUSE_BEGIN", "POST_CLOSE_PAUSE_END",
        "NORMAL_MAIN_RETURN",
        "SHUTDOWN_HOOK_ENTERED", "SHUTDOWN_HOOK_COMPLETED",
    };

    static boolean verify(Path file) {
        List<String> lines;
        try { lines = Files.readAllLines(file); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        System.out.println("# crash-trace verification: " + file.toAbsolutePath() + "  (" + lines.size() + " markers)");

        List<String> phases = new ArrayList<>();
        boolean fields = true, seqOk = true; long expect = 1;
        for (String ln : lines) {
            if (ln.isBlank()) continue;
            for (String k : new String[]{ "seq=", "elapsedMs=", "pid=", "thread=", "phase=" })
                if (!ln.contains(k)) { fields = false; System.out.println("  missing " + k + " in: " + ln); }
            String s = field(ln, "seq=");
            try { if (Long.parseLong(s) != expect++) seqOk = false; } catch (Exception e) { seqOk = false; }
            phases.add(field(ln, "phase="));
        }

        boolean order = true; int at = 0;
        for (String want : ORDER) {
            int found = -1;
            for (int i = at; i < phases.size(); i++) if (phases.get(i).equals(want)) { found = i; break; }
            if (found < 0) { System.out.println("  MISSING/out-of-order marker: " + want); order = false; }
            else at = found + 1;
        }

        boolean tail = phases.size() >= 2
                && phases.get(phases.size() - 1).equals("SHUTDOWN_HOOK_COMPLETED")
                && phases.get(phases.size() - 2).equals("SHUTDOWN_HOOK_ENTERED");
        boolean heartbeat = phases.contains("HEARTBEAT");
        boolean noWriteFail = !phases.contains("TRACE_WRITE_FAILED");
        boolean flagsOk = !phases.contains("LAUNCH_CONFIG_WARNING");
        String cfg = lines.stream().filter(l -> l.contains("phase=LAUNCH_CONFIG ")).findFirst().orElse("");
        boolean fmaOff = cfg.contains("tornado.enable.fma=false");
        boolean bailOff = cfg.contains("tornado.recover.bailout=false");
        boolean bcOk = cfg.contains("tornado.tvm.maxbytecodesize=65536") || cfg.contains("maxbytecodesize=131072")
                || cfg.contains("maxbytecodesize=262144");
        // the last durable execute call must be identifiable
        String lastExec = lines.stream().filter(l -> l.contains("phase=EXECUTE_CALL_END")).reduce((a, b) -> b).orElse("");

        p("marker fields complete (ts/seq/elapsedMs/pid/thread/phase)", fields);
        p("sequence numbers strictly consecutive from 1", seqOk);
        p("required marker order (execute/result/close/post-close/shutdown distinguishable)", order);
        p("trace survived JVM shutdown (last two markers are the shutdown hook)", tail);
        p("heartbeat present", heartbeat);
        p("no TRACE_WRITE_FAILED", noWriteFail);
        p("no LAUNCH_CONFIG_WARNING (explicit-S2 device flags present)", flagsOk);
        System.out.println("  launch config: fma=false:" + fmaOff + " bailout=false:" + bailOff + " bytecodeCap>=65536:" + bcOk);
        System.out.println("  final durable execute marker: " + (lastExec.isEmpty() ? "(none)" : lastExec.trim()));

        boolean ok = fields && seqOk && order && tail && noWriteFail;
        System.out.println(ok ? "=== CRASH-TRACE VALIDATION: PASS ===" : "=== CRASH-TRACE VALIDATION: FAIL ===");
        return ok;
    }

    private static void p(String name, boolean ok) {
        System.out.printf(Locale.US, "  [%s] %s%n", ok ? "PASS" : "FAIL", name);
    }

    private static String field(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return "";
        int j = line.indexOf(' ', i + key.length());
        return j < 0 ? line.substring(i + key.length()) : line.substring(i + key.length(), j);
    }
}
