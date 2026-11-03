package softbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PAIRS chain-coefficient lookup (2026-10-30, jba: "we need a lookup table if we're going to be hopping around with
 * viscosities and deltaTs"). Reads params/pairs_table.tsv (format documented in the file header).
 *
 * The chain law moves a fixed fraction per step, so its stiffness is k = f*gamma/dt. With gamma proportional to eta,
 * the discrete chain map depends on the condition only through dt/eta, so a row matches any (eta, dt) with the same
 * dt/eta at the same monomers per segment. No interpolation: a condition with no row is an error, never a silent default.
 * Only rows whose Brownian dt equals their stepping dt match, since SoftBox applies Brownian every step.
 */
public final class PairsTable {
    public static final String DEFAULT_FILE = "params/pairs_table.tsv";
    static final double REL_TOL = 1e-6;

    public record Row(int mono, double eta, double dt, double brownDt, double fracMove, double fracR, double fracMoveTorq,
                      String lpTarget, String lpSoftbox, String source) {
        double dtOverEta() { return dt / eta; }
        boolean verified() { return !lpSoftbox.equals("-"); }
        public String describe() {
            return String.format(Locale.US, "fracMove %.6g fracR %.6g fracMoveTorq %.6g (row: mono %d, eta %.4g, dt %.4g; "
                    + "Lp target %s um, SoftBox-measured %s; %s)", fracMove, fracR, fracMoveTorq, mono, eta, dt,
                    lpTarget, verified() ? lpSoftbox + " um" : "NOT YET", source);
        }
    }

    static List<Row> load(String file) {
        List<String> lines;
        try { lines = Files.readAllLines(Path.of(file)); } catch (IOException e) { throw new UncheckedIOException("PAIRS table " + file, e); }
        List<Row> rows = new ArrayList<>();
        int ln = 0;
        for (String l : lines) {
            ln++;
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] c = l.split("\t");
            if (c.length < 10) throw new IllegalArgumentException(file + ":" + ln + ": expected 10 tab-separated columns, got " + c.length);
            rows.add(new Row(Integer.parseInt(c[0].trim()), Double.parseDouble(c[1]), Double.parseDouble(c[2]), Double.parseDouble(c[3]),
                    Double.parseDouble(c[4]), Double.parseDouble(c[5]), Double.parseDouble(c[6]), c[7].trim(), c[8].trim(), c[9].trim()));
        }
        return rows;
    }

    static boolean rel(double a, double b) { return Math.abs(a / b - 1.0) < REL_TOL; }

    /** The row for (mono, eta, dt). An exact (eta, dt) match wins over a dt/eta-equivalent one. Throws if none. */
    public static Row lookup(String file, int mono, double eta, double dt) {
        List<Row> rows = load(file);
        Row best = null;
        for (Row r : rows) {
            if (r.mono != mono || !rel(r.brownDt, r.dt) || !rel(r.dtOverEta(), dt / eta)) continue;
            if (best == null || (rel(r.eta, eta) && rel(r.dt, dt))) best = r;
        }
        if (best != null) return best;
        StringBuilder sb = new StringBuilder(String.format(Locale.US,
                "no PAIRS row in %s for mono %d at eta %.4g Pa.s, dt %.4g s (dt/eta %.4g). Rows present:%n", file, mono, eta, dt, dt / eta));
        for (Row r : rows) sb.append(String.format(Locale.US, "  mono %d  eta %.4g  dt %.4g  brown_dt %.4g  dt/eta %.4g%s%n",
                r.mono, r.eta, r.dt, r.brownDt, r.dtOverEta(), rel(r.brownDt, r.dt) ? "" : "  (Brownian sub-stepped: unusable)"));
        sb.append("Tune a set for this dt/eta (BoA benchmark) and add a row, or pass -pairs <fracMove> <fracR> <fracMoveTorq>.");
        throw new IllegalArgumentException(sb.toString());
    }

    /** Install a looked-up row as the FilamentStore overrides; returns the one-line description for logs. */
    public static String install(String file, int mono, double eta, double dt) {
        Row r = lookup(file, mono, eta, dt);
        FilamentStore.OV_FRACMOVE = r.fracMove; FilamentStore.OV_FRACR = r.fracR; FilamentStore.OV_FMT = r.fracMoveTorq;
        if (!r.verified()) System.err.printf(Locale.US, "  WARNING: PAIRS row for mono %d dt/eta %.4g has no SoftBox-measured Lp yet%n", mono, dt / eta);
        return r.describe();
    }

    private PairsTable() {}
}
