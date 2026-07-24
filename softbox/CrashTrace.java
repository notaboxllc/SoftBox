package softbox;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DURABLE, append-only phase trace for post-hard-freeze forensics (diagnostic instrumentation ONLY).
 *
 * <p>Design constraints (from the GPU crash-diagnostic handoff):
 * <ul>
 *   <li><b>ONE append-mode {@link FileChannel} is used for BOTH the write and the {@code force}.</b> A
 *       {@code BufferedWriter} + a separate channel on the same file is explicitly rejected: the two objects hold
 *       independent file positions and independent buffers, so {@code force()} on the second channel can return
 *       having flushed nothing the writer had staged. One channel, {@code write()} then {@code force(true)}, is the
 *       only way to know the bytes reached storage before the marker call returns.</li>
 *   <li>Every marker is forced with {@code force(true)} (data AND metadata) so a marker survives an abrupt power
 *       loss / hard freeze as reliably as the filesystem permits.</li>
 *   <li>Every marker is mirrored to {@code System.err} and flushed, so the launcher's captured stderr carries the
 *       same sequence even if the trace file is lost.</li>
 * </ul>
 * The marker frequency is low (a handful per run plus a 5 s heartbeat), so the per-marker fsync cost is
 * negligible — see the overhead section of {@code docs/TORNADOVM_GPU_CRASH_MONITORING_IMPLEMENTATION.md}.
 *
 * <p>This class writes log lines. It performs NO GPU work, NO recovery, and makes NO claim about root cause.
 */
public final class CrashTrace {

    private final Path path;
    private final FileChannel ch;          // the ONE channel: append writes + force
    private final long startNanos;         // trace-open monotonic origin (NOT process uptime)
    private final long pid;
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean closed = false;
    private volatile boolean writeFailed = false;

    public CrashTrace(Path path) throws IOException {
        this.path = path.toAbsolutePath();
        Path parent = this.path.getParent();
        if (parent != null) Files.createDirectories(parent);
        // ONE channel, append mode: write + force on the same object.
        this.ch = FileChannel.open(this.path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        this.startNanos = System.nanoTime();
        this.pid = ProcessHandle.current().pid();
        mark("TRACE_OPEN", "file=" + this.path);
    }

    public Path path() { return path; }
    public long pid()  { return pid; }

    /** Milliseconds since the trace was opened (trace-relative elapsed time — deliberately NOT called "uptime"). */
    public long elapsedMs() { return (System.nanoTime() - startNanos) / 1_000_000L; }

    public void mark(String phase) { mark(phase, ""); }

    /**
     * Format one UTF-8 line, write it through the channel, force(true) it, mirror it to stderr, flush stderr.
     * Synchronized: markers come from the main thread, the heartbeat thread and the shutdown hook.
     */
    public synchronized void mark(String phase, String details) {
        long seq = sequence.incrementAndGet();
        String line = String.format(
                "%s seq=%06d elapsedMs=%d pid=%d thread=%s phase=%s%s%n",
                Instant.now(), seq, elapsedMs(), pid,
                sanitize(Thread.currentThread().getName()),
                phase,
                (details == null || details.isEmpty()) ? "" : " " + sanitize(details));
        if (!closed) {
            try {
                ByteBuffer buf = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
                while (buf.hasRemaining()) ch.write(buf);
                ch.force(true);                  // data + metadata to storage before we return
            } catch (Throwable t) {
                if (!writeFailed) {              // report once; never recurse into mark()
                    writeFailed = true;
                    System.err.println("TRACE_WRITE_FAILED file=" + path + " error=" + t);
                    System.err.flush();
                }
            }
        }
        System.err.print(line);
        System.err.flush();
    }

    /** Emitted by the owner when a durable write is known to have failed (kept as a first-class marker name). */
    public void markWriteFailed(String details) { mark("TRACE_WRITE_FAILED", details); }

    /** Idempotent. Safe to call from a shutdown hook after the final markers have been written. */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try { ch.force(true); } catch (Throwable ignored) { }
        try { ch.close(); } catch (Throwable ignored) { }
    }

    static String sanitize(String v) {
        if (v == null) return "";
        return v.replace('\n', ' ').replace('\r', ' ');
    }
}
