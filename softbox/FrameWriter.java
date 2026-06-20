package softbox;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Host-side IO utility (NOT a device system): writes per-frame Three.js JSON in the
 * v1 viewer's schema so the unmodified BoA viewer (sim_viewer_boa.html) + server
 * (sim_server.py) render Soft Box runs. Increment 1.5 — output only; touches no
 * physics, no kernel, no TaskGraph, no host-pull cadence.
 *
 * Schema (ported from v1 ThreeJSWriter.java, `segments` emission only):
 *   {"frame":N,"t":T,"bounds":{"xDim":..,"yDim":..,"zDim":..},
 *    "segments":[{"id":I,"end1":[x,y,z],"end2":[x,y,z],"r":R,
 *                 "notADPRatio":1.0,"cofilinCount":0}, ...]}
 * The viewer requires only `segments` (+ frame/t); myosins/minifilaments/nodes are
 * optional in its JS, so we emit none (out of scope this increment).
 *
 * end1/end2 are the DERIVED geometry (same formula as DerivedGeometrySystem:
 * end1 = coord - L/2*uVec, end2 = coord + L/2*uVec). We reconstruct them here on the
 * host from the already-pulled canonical pose (coord, uVec) + segLength — so the
 * output path adds NO device transfer beyond the harness's existing output-cadence
 * coord/uVec pull. r = actinWidth/2 (Constants.radius), exactly as v1.
 *
 * Extensibility: per-segment JSON lives in one method (appendSegment); a future
 * generic "bodies + links" schema is a localized swap, deliberately not built now.
 */
public final class FrameWriter {

    private final String outDir;
    private final double xDim, yDim, zDim;
    private final double radius = Constants.radius;
    private int frameNumber = 0;

    public FrameWriter(String requestedDir, double xDim, double yDim, double zDim) {
        this.outDir = resolveOutputDir(requestedDir);
        this.xDim = xDim; this.yDim = yDim; this.zDim = zDim;
        System.out.println("FrameWriter: output directory " + outDir);
    }

    /** Port of v1 ThreeJSWriter output-dir resolution: create, or auto-increment .NNN. */
    private static String resolveOutputDir(String dir) {
        File d = new File(dir);
        if (!d.exists()) {
            d.mkdirs();
            return d.getPath();
        }
        for (int n = 1; n <= 999; n++) {
            File candidate = new File(String.format(Locale.US, "%s.%03d", dir, n));
            if (!candidate.exists()) {
                candidate.mkdirs();
                return candidate.getPath();
            }
        }
        throw new IllegalStateException("FrameWriter: could not allocate an output dir for " + dir);
    }

    public int framesWritten() { return frameNumber; }
    public String dir() { return outDir; }

    /**
     * Write frame_NNNNNN.json for the current host pose. Reads coord/uVec (the host
     * mirror the harness already pulled at output cadence) + segLength; derives
     * end1/end2 on the host. No device interaction here.
     */
    public void writeFrame(FilamentStore s, double t) {
        StringBuilder sb = new StringBuilder(64 + 96 * s.n);
        sb.append(String.format(Locale.US, "{\"frame\":%d", frameNumber));
        sb.append(String.format(Locale.US, ",\"t\":%.6g", t));
        sb.append(String.format(Locale.US,
                ",\"bounds\":{\"xDim\":%.5g,\"yDim\":%.5g,\"zDim\":%.5g}", xDim, yDim, zDim));
        sb.append(",\"segments\":[");
        for (int i = 0; i < s.n; i++) {
            if (i > 0) sb.append(',');
            appendSegment(sb, s, i);
        }
        sb.append("]}");

        Path path = Path.of(outDir, String.format(Locale.US, "frame_%06d.json", frameNumber));
        try {
            Files.writeString(path, sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("FrameWriter: failed writing " + path, e);
        }
        frameNumber++;
    }

    /**
     * Increment 7 Aging build (A) viewer hook — write a frame coloured by the nucleotide-composition proxy. The
     * verbatim v1 viewer renders ONE channel, seg.notADPRatio (ageColor: 0=red/old/ADP ↔ 1=young/ATP), so we emit
     * notADPRatio = f_ATP + f_ADPPi (= 1 − f_ADP) ⇒ the ATP→ADP-Pi→ADP aging shows as a red gradient barbed→pointed
     * along the filament. We ALSO emit the raw composition (fATP/fADPPi/fADP) as extra JSON fields the current
     * viewer ignores — so a future band-aware viewer / external analysis can render the distinct ADP-Pi band. The
     * geometry path is identical to writeFrame(FilamentStore,double); this overload only changes the colour field.
     */
    public void writeFrame(FilamentStore s, AgingStore aging, double t) {
        StringBuilder sb = new StringBuilder(64 + 128 * s.n);
        sb.append(String.format(Locale.US, "{\"frame\":%d", frameNumber));
        sb.append(String.format(Locale.US, ",\"t\":%.6g", t));
        sb.append(String.format(Locale.US,
                ",\"bounds\":{\"xDim\":%.5g,\"yDim\":%.5g,\"zDim\":%.5g}", xDim, yDim, zDim));
        sb.append(",\"segments\":[");
        boolean first = true;
        for (int i = 0; i < s.n; i++) {
            if (s.filState.get(i) < 0) continue;          // skip FREE slots
            if (!first) sb.append(',');
            appendSegment(sb, s, aging, i);
            first = false;
        }
        sb.append("]}");
        Path path = Path.of(outDir, String.format(Locale.US, "frame_%06d.json", frameNumber));
        try {
            Files.writeString(path, sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("FrameWriter: failed writing " + path, e);
        }
        frameNumber++;
    }

    private void appendSegment(StringBuilder sb, FilamentStore s, AgingStore aging, int i) {
        double cx = s.coordX(i), cy = s.coordY(i), cz = s.coordZ(i);
        double ux = s.uVecX(i),  uy = s.uVecY(i),  uz = s.uVecZ(i);
        double half = s.segLength.get(i) * 0.5;
        double e1x = cx - half * ux, e1y = cy - half * uy, e1z = cz - half * uz;
        double e2x = cx + half * ux, e2y = cy + half * uy, e2z = cz + half * uz;
        double fATP = aging.fATP(i), fADPPi = aging.fADPPi(i), fADP = aging.fADP(i);
        double notADP = fATP + fADPPi;                     // 1 − f_ADP (the viewer's age channel)
        sb.append(String.format(Locale.US,
                "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,"
                + "\"notADPRatio\":%.3g,\"cofilinCount\":%d,\"fATP\":%.3g,\"fADPPi\":%.3g,\"fADP\":%.3g}",
                i, e1x, e1y, e1z, e2x, e2y, e2z, radius, notADP, 0, fATP, fADPPi, fADP));
    }

    private void appendSegment(StringBuilder sb, FilamentStore s, int i) {
        double cx = s.coordX(i), cy = s.coordY(i), cz = s.coordZ(i);
        double ux = s.uVecX(i),  uy = s.uVecY(i),  uz = s.uVecZ(i);
        double half = s.segLength.get(i) * 0.5;
        double e1x = cx - half * ux, e1y = cy - half * uy, e1z = cz - half * uz;
        double e2x = cx + half * ux, e2y = cy + half * uy, e2z = cz + half * uz;
        sb.append(String.format(Locale.US,
                "{\"id\":%d,\"end1\":[%.5g,%.5g,%.5g],\"end2\":[%.5g,%.5g,%.5g],\"r\":%.5g,"
                + "\"notADPRatio\":%.3g,\"cofilinCount\":%d}",
                i, e1x, e1y, e1z, e2x, e2y, e2z, radius, 1.0, 0));
    }
}
