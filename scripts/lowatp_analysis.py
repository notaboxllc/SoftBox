#!/usr/bin/env python3
"""Low-[ATP] gliding and twirling — primary analysis and publication plots.

Reads the atomic per-arm records written by ChiralSiteHarness (-atp-map) from
RUN_LOGS/chiral_sites/lowatp/ and produces:

  * tidy CSV tables (per-record, per-seed derived, per-ATP summary)
  * the seven required plots
  * a text summary block that is pasted into
    docs/twirling/LOW_ATP_GLIDING_TWIRLING_FINDINGS.md

The seed is the independent statistical unit throughout: every eps-EVEN / eps-ODD
combination and every turns-per-um ratio is formed PER SEED and only then averaged.

Usage:  python3 scripts/lowatp_analysis.py --duration-ms 100 [--mirror] [--outdir ...]
"""
import argparse, glob, math, os, re, sys
from collections import defaultdict

REC_DIR = "RUN_LOGS/chiral_sites/lowatp"
ID_RE = re.compile(r"atp_u(?P<uM>[0-9.]+)_d(?P<dur>\d+)_(?P<mir>m_)?(?P<sgn>[pnz])_(?P<seed>\d+)\.tsv$")


def read_record(path):
    vals, complete = {}, False
    prov = ""
    with open(path) as fh:
        for line in fh:
            if line.startswith("#"):
                prov = line[1:].strip(); continue
            p = line.rstrip("\n").split("\t")
            if len(p) != 2:
                continue
            if p[0] == "COMPLETE":
                complete = True; continue
            try:
                vals[p[0]] = float(p[1])
            except ValueError:
                pass
    return (vals, prov) if complete else (None, prov)


def load(duration_ms, mirror):
    dur_us = int(round(duration_ms * 1000))
    out = defaultdict(dict)          # (uM, seed) -> {sign: vals}
    prov = {}
    for path in sorted(glob.glob(os.path.join(REC_DIR, "*.tsv"))):
        m = ID_RE.search(os.path.basename(path))
        if not m or int(m.group("dur")) != dur_us:
            continue
        if bool(m.group("mir")) != mirror:
            continue
        vals, pr = read_record(path)
        if vals is None:
            continue
        key = (float(m.group("uM")), int(m.group("seed")))
        out[key][m.group("sgn")] = vals
        prov[key] = pr
    return out, prov


def msn(xs):
    xs = [x for x in xs if x is not None and math.isfinite(x)]
    n = len(xs)
    if n == 0:
        return float("nan"), float("nan"), 0
    mean = sum(xs) / n
    if n < 2:
        return mean, float("nan"), n
    var = sum((x - mean) ** 2 for x in xs) / (n - 1)
    return mean, math.sqrt(var / n), n


def sigma(m, s):
    return abs(m) / s if s and math.isfinite(s) and s > 0 else float("nan")


def sign_frac(xs):
    xs = [x for x in xs if math.isfinite(x)]
    if not xs:
        return float("nan")
    mean = sum(xs) / len(xs)
    if mean == 0:
        return float("nan")
    return sum(1 for x in xs if x * mean > 0) / len(xs)


def derive(recs):
    """(uM -> list of per-seed derived dicts). Only seeds with BOTH eps signs are used."""
    per = defaultdict(list)
    for (uM, seed), d in sorted(recs.items()):
        if "p" not in d or "n" not in d:
            continue
        p, n = d["p"], d["n"]
        v_even = 0.5 * (p["glide"] + n["glide"])
        v_odd = 0.5 * (p["glide"] - n["glide"])
        om_odd = 0.5 * (p["omegaFit"] - n["omegaFit"])
        om_even = 0.5 * (p["omegaFit"] + n["omegaFit"])
        tau_odd = 0.5 * (p["tau"] - n["tau"])
        turns = om_odd / (2 * math.pi * abs(v_even)) if v_even else float("nan")
        j_odd = {k: 0.5 * (p[k] - n[k]) for k in ("jPre", "jStroke", "jEarly", "jLate")}
        avg = lambda k: 0.5 * (p[k] + n[k])
        det_tot = avg("detachAtp") + avg("detachRigor") + avg("detachOther")
        per[uM].append(dict(
            seed=seed, v_even=v_even, v_odd=v_odd, om_odd=om_odd, om_even=om_even,
            tau_odd=tau_odd, turns=turns,
            j_pre=j_odd["jPre"], j_stroke=j_odd["jStroke"], j_early=j_odd["jEarly"],
            j_late=j_odd["jLate"], j_total=sum(j_odd.values()),
            avgBound=avg("avgBound"), attachRate=avg("bindsPerS"), strokeRate=avg("strokeRatePerS"),
            detachRate=avg("detachPerS"), residence_ms=avg("residenceS") * 1e3,
            preLife_us=avg("preLifeS") * 1e6, postLife_us=avg("postLifeS") * 1e6,
            occNone=avg("occNoneB"), occAtp=avg("occAtpB"), occAdpPi=avg("occAdpPiB"), occAdp=avg("occAdpB"),
            fATP=avg("detachAtp") / det_tot if det_tot else float("nan"),
            fRupture=avg("detachRigor") / det_tot if det_tot else float("nan"),
            fOther=avg("detachOther") / det_tot if det_tot else float("nan"),
            atpOn=p["atpOnEff"], eta=p["eta"], dt=p["dt"], durationS=p["durationS"],
            invalid=p["invalid"] + n["invalid"], solverFail=p["solverFail"] + n["solverFail"],
            rateCap=p["rateCapWarns"] + n["rateCapWarns"],
            nEp=p["nEp"] + n["nEp"], epRate=avg("epRate"),
        ))
    return per


FIELDS = ["v_even", "v_odd", "om_odd", "om_even", "tau_odd", "turns", "avgBound", "attachRate",
          "strokeRate", "detachRate", "residence_ms", "preLife_us", "postLife_us",
          "occNone", "occAtp", "occAdpPi", "occAdp", "fATP", "fRupture", "fOther",
          "j_pre", "j_stroke", "j_early", "j_late", "j_total", "epRate"]


def summarize(per):
    rows = []
    for uM in sorted(per, reverse=True):
        seeds = per[uM]
        row = {"atpUM": uM, "atpOn": seeds[0]["atpOn"], "n": len(seeds)}
        for f in FIELDS:
            m, s, n = msn([d[f] for d in seeds])
            row[f] = m; row[f + "_sem"] = s
            row[f + "_sigma"] = sigma(m, s)
            row[f + "_sgn"] = sign_frac([d[f] for d in seeds])
        row["pitch_um"] = 1.0 / row["turns"] if row["turns"] else float("nan")
        row["invalid"] = sum(d["invalid"] for d in seeds)
        row["solverFail"] = sum(d["solverFail"] for d in seeds)
        row["rateCap"] = sum(d["rateCap"] for d in seeds)
        row["nEp"] = sum(d["nEp"] for d in seeds)
        rows.append(row)
    return rows


def paired(per, ref_uM, field):
    """Per-seed paired difference field(uM) - field(ref) over the seeds both share."""
    out = {}
    ref = {d["seed"]: d[field] for d in per.get(ref_uM, [])}
    for uM, seeds in per.items():
        if uM == ref_uM:
            continue
        diffs = [d[field] - ref[d["seed"]] for d in seeds if d["seed"] in ref]
        out[uM] = msn(diffs)
    return out


def write_csv(path, rows, cols):
    with open(path, "w") as fh:
        fh.write(",".join(cols) + "\n")
        for r in rows:
            fh.write(",".join(("%.10g" % r[c]) if isinstance(r[c], float) else str(r[c]) for c in cols) + "\n")


def plots(rows, per, outdir, tag):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception as e:                                   # plot-ready data is still written
        print("  (matplotlib unavailable: %s — CSV plot-ready data only)" % e)
        return []
    x = [r["atpUM"] for r in rows]
    made = []

    def fig(name, ycols, ylabel, title, logx=True, hline=None):
        f, ax = plt.subplots(figsize=(5.2, 3.6))
        for col, lab, style in ycols:
            y = [r[col] for r in rows]
            e = [r.get(col + "_sem", float("nan")) for r in rows]
            ax.errorbar(x, y, yerr=e, marker="o", capsize=3, label=lab, **style)
        if hline is not None:
            ax.axhline(hline, color="0.6", lw=0.8, ls=":")
        if logx:
            ax.set_xscale("log")
        ax.set_xlabel("[ATP]  (µM)")
        ax.set_ylabel(ylabel)
        ax.set_title(title, fontsize=9)
        if len(ycols) > 1:
            ax.legend(fontsize=7, frameon=False)
        ax.grid(alpha=0.25, lw=0.5)
        f.tight_layout()
        p = os.path.join(outdir, "%s_%s.png" % (tag, name))
        f.savefig(p, dpi=180); plt.close(f); made.append(p)

    fig("1_gliding_vs_atp", [("v_even", "v_even", {})], "gliding speed  (µm/s)",
        "Gliding speed vs [ATP]", hline=0.0)
    fig("2_omega_vs_atp", [("om_odd", "Omega_odd", {})], "angular velocity  (rad/s)",
        "eps-ODD angular velocity vs [ATP]", hline=0.0)
    fig("3_turns_vs_atp", [("turns", "turns per µm", {})], "turns per µm",
        "Rotation per unit distance vs [ATP]", hline=0.0)
    fig("5_engagement_vs_atp", [("avgBound", "avgBound", {}), ("occNone", "rigor occupancy x10", {})],
        "bound heads / occupancy", "Bound population vs [ATP]")
    fig("5b_flux_vs_atp", [("attachRate", "attachments/s", {}), ("strokeRate", "strokes/s", {}),
                           ("detachRate", "detachments/s", {})], "events per second",
        "Event flux vs [ATP]")
    fig("6_residence_vs_atp", [("residence_ms", "residence (ms)", {}),
                               ("preLife_us", "pre-stroke (µs)", {}), ("postLife_us", "post-stroke (µs)", {})],
        "lifetime", "Bound lifetimes vs [ATP]")
    fig("6b_cause_vs_atp", [("fATP", "ATP-triggered", {}), ("fRupture", "rigor rupture", {})],
        "fraction of detachments", "Detachment cause vs [ATP]")
    fig("7_torque_vs_atp", [("tau_odd", "tau_odd (N·m)", {})], "tau_odd  (N·m)",
        "eps-ODD axial torque vs [ATP]", hline=0.0)
    fig("7b_impulse_vs_atp", [("j_total", "J_total_odd", {}), ("j_pre", "J_pre", {}),
                              ("j_stroke", "J_stroke", {}), ("j_early", "J_early", {}), ("j_late", "J_late", {})],
        "angular impulse  (N·m·s)", "eps-ODD impulse budget vs [ATP]", hline=0.0)

    # 4: pitch (turns/µm) vs gliding speed — one point per (ATP, seed) plus the per-ATP means
    f, ax = plt.subplots(figsize=(5.2, 3.6))
    for uM in sorted(per, reverse=True):
        ax.scatter([d["v_even"] for d in per[uM]], [d["turns"] for d in per[uM]], s=14,
                   alpha=0.55, label="%g µM" % uM)
    ax.errorbar([r["v_even"] for r in rows], [r["turns"] for r in rows],
                xerr=[r["v_even_sem"] for r in rows], yerr=[r["turns_sem"] for r in rows],
                fmt="k-o", lw=1.2, ms=5, capsize=3, label="per-[ATP] mean")
    ax.axhline(0, color="0.6", lw=0.8, ls=":")
    ax.set_xlabel("gliding speed  (µm/s)"); ax.set_ylabel("turns per µm")
    ax.set_title("Rotation per distance vs gliding speed", fontsize=9)
    ax.legend(fontsize=7, frameon=False); ax.grid(alpha=0.25, lw=0.5)
    f.tight_layout()
    p = os.path.join(outdir, "%s_4_turns_vs_speed.png" % tag)
    f.savefig(p, dpi=180); plt.close(f); made.append(p)
    return made


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--duration-ms", type=float, required=True)
    ap.add_argument("--mirror", action="store_true")
    ap.add_argument("--outdir", default="RUN_LOGS/lowatp")
    a = ap.parse_args()
    os.makedirs(a.outdir, exist_ok=True)
    tag = "lowatp_d%dms%s" % (round(a.duration_ms), "_mirror" if a.mirror else "")

    recs, prov = load(a.duration_ms, a.mirror)
    if not recs:
        print("no complete records for duration %g ms (mirror=%s)" % (a.duration_ms, a.mirror))
        return 1
    per = derive(recs)
    rows = summarize(per)

    seed_rows = [dict(atpUM=uM, **d) for uM in sorted(per, reverse=True) for d in per[uM]]
    write_csv(os.path.join(a.outdir, tag + "_per_seed.csv"), seed_rows,
              ["atpUM", "seed", "atpOn"] + FIELDS + ["invalid", "solverFail", "rateCap", "nEp"])
    write_csv(os.path.join(a.outdir, tag + "_summary.csv"), rows,
              ["atpUM", "atpOn", "n"] + sum([[f, f + "_sem", f + "_sigma", f + "_sgn"] for f in FIELDS], [])
              + ["pitch_um", "invalid", "solverFail", "rateCap", "nEp"])

    ref = max(r["atpUM"] for r in rows)
    print("\n===== LOW-[ATP] PRIMARY ANALYSIS  (duration %g ms, %s lattice) ====="
          % (a.duration_ms, "MIRROR" if a.mirror else "native"))
    print("%10s %10s %4s %20s %8s %20s %8s %7s" %
          ("[ATP] µM", "atpOn /s", "n", "v_even ± SEM", "sigma", "Omega_odd ± SEM", "sigma", "sgn%"))
    for r in rows:
        print("%10.4g %10.4g %4d %11.4f±%8.4f %8.2f %11.2f±%8.2f %8.2f %7.0f" %
              (r["atpUM"], r["atpOn"], r["n"], r["v_even"], r["v_even_sem"], r["v_even_sigma"],
               r["om_odd"], r["om_odd_sem"], r["om_odd_sigma"], 100 * r["om_odd_sgn"]))
    print("\n%10s %20s %8s %7s %16s %14s" %
          ("[ATP] µM", "turns/µm ± SEM", "sigma", "sgn%", "signed pitch µm", "tau_odd"))
    for r in rows:
        res = r["turns_sigma"] >= 2.0
        print("%10.4g %11.4f±%8.4f %8.2f %7.0f %16s %+14.4e" %
              (r["atpUM"], r["turns"], r["turns_sem"], r["turns_sigma"], 100 * r["turns_sgn"],
               ("%+.4f" % r["pitch_um"]) if res else "(not resolved)", r["tau_odd"]))
    print("\n%10s %9s %9s %11s %11s %11s %11s %8s %8s" %
          ("[ATP] µM", "avgBound", "rigorOcc", "attach/s", "strokes/s", "detach/s", "resid ms", "fATP", "fRupt"))
    for r in rows:
        print("%10.4g %9.3f %9.4f %11.0f %11.0f %11.0f %11.4f %8.4f %8.4f" %
              (r["atpUM"], r["avgBound"], r["occNone"], r["attachRate"], r["strokeRate"],
               r["detachRate"], r["residence_ms"], r["fATP"], r["fRupture"]))
    print("\n%10s %12s %12s %12s %12s" % ("[ATP] µM", "preLife µs", "postLife µs", "occ ADP·Pi", "occ ADP"))
    for r in rows:
        print("%10.4g %12.3f %12.3f %12.4f %12.4f" %
              (r["atpUM"], r["preLife_us"], r["postLife_us"], r["occAdpPi"], r["occAdp"]))
    print("\n---- paired per-seed differences vs the reference [ATP] = %g µM ----" % ref)
    print("%10s %22s %8s | %22s %8s" % ("[ATP] µM", "d(v_even)", "sigma", "d(turns/µm)", "sigma"))
    dv, dt = paired(per, ref, "v_even"), paired(per, ref, "turns")
    for uM in sorted(dv, reverse=True):
        m1, s1, _ = dv[uM]; m2, s2, _ = dt[uM]
        print("%10.4g %13.4f±%8.4f %8.2f | %13.4f±%8.4f %8.2f" %
              (uM, m1, s1, sigma(m1, s1), m2, s2, sigma(m2, s2)))
    print("\n---- numerical health ----")
    for r in rows:
        print("  [ATP]=%8.4g µM : invalid=%d  solverFail=%d  rateCapWarn=%d  episodes=%d"
              % (r["atpUM"], r["invalid"], r["solverFail"], r["rateCap"], r["nEp"]))

    made = plots(rows, per, a.outdir, tag)
    print("\nwrote: %s_per_seed.csv, %s_summary.csv%s" %
          (tag, tag, ("" if not made else ", " + str(len(made)) + " plots")))
    for p in made:
        print("  " + p)
    return 0


if __name__ == "__main__":
    sys.exit(main())
