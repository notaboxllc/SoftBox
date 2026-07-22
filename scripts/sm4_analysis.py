#!/usr/bin/env python3
"""
SM4 force-dependent attachment-lifetime analysis.

Reads the per-batch event JSON written by softbox.Sm4ForceLifetimeHarness and emits:
  event_table.csv        one row per ATTACHMENT EVENT (the unit of analysis)
  survival_summary.csv   one row per (state, force, direction) CELL
  kaplan_meier.csv       the KM step function per cell (times, S, CI, at-risk)
  hazard.csv             piecewise-constant empirical hazard per cell
  ANALYSIS.md            human-readable report incl. force-clamp + prepared-state validation

  python3 scripts/sm4_analysis.py RUN_LOGS/motor_validation/sm4_force_lifetime

Statistical discipline: a simulation TIMESTEP is not an independent sample. Every
statistic below is computed over attachment events, with right-censoring preserved.
"""
import sys, os, json, glob, csv, math
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sm4_survival import summarize, empirical_hazard   # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/motor_validation/sm4_force_lifetime"

EVENT_FIELDS = [
    "event_id", "batch", "fixture", "state", "requested_force_pn", "force_dir", "force_signed_pn",
    "realized_force_mean_pn", "realized_force_sd_pn", "realized_force_max_dev_pn",
    "seed", "t_attach_s", "t_detach_s", "lifetime_s", "censored",
    "detach_pathway", "detach_state", "detach_cause", "t_adp_release_s", "t_rigor_rupture_s",
    "force_at_rupture_pn", "rate_cap_warnings",
    "max_disp_nm", "max_bond_strain_nm", "max_bond_force_pn", "max_axial_load_pn",
    "steps_run", "invalid_states", "solver_failures", "valid", "abort_reason",
    "dt", "code_rev", "build_id", "config_hash",
]


def load_batches(d):
    batches = []
    for f in sorted(glob.glob(os.path.join(d, "sm4_batch_*.json"))):
        try:
            batches.append((os.path.basename(f), json.load(open(f))))
        except Exception as e:
            print(f"  (unreadable {f}: {e})")
    return batches


def collect_events(batches):
    ev = []
    for fname, b in batches:
        rev = b.get("code_rev", "?")
        chash = b.get("config_hash", "?")
        dt = b.get("dt", float("nan"))
        fixture = b.get("fixture", b.get("motor_model", "?"))
        build = b.get("build_id", "?")
        for e in b.get("events", []):
            r = dict(e)
            r.setdefault("batch", fname)
            r.setdefault("code_rev", rev)
            r.setdefault("config_hash", chash)
            r.setdefault("dt", dt)
            r.setdefault("fixture", fixture)     # fixture identity retained on EVERY row (never pool blindly)
            r.setdefault("build_id", build)
            ev.append(r)
    return ev


def write_event_table(ev, path):
    with open(path, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=EVENT_FIELDS, extrasaction="ignore")
        w.writeheader()
        for e in ev:
            w.writerow({k: e.get(k, "") for k in EVENT_FIELDS})


def cell_key(e):
    return (e["state"], float(e["requested_force_pn"]), e["force_dir"])


def group_cells(ev):
    g = {}
    for e in ev:
        if not e.get("valid", True):
            continue
        g.setdefault(cell_key(e), []).append(e)
    return dict(sorted(g.items(), key=lambda kv: (kv[0][0], kv[0][2], kv[0][1])))


def fmt(x, p=4):
    if x is None:
        return ""
    try:
        if isinstance(x, float) and (math.isnan(x) or math.isinf(x)):
            return ""
        return f"{x:.{p}g}"
    except Exception:
        return str(x)


def main():
    batches = load_batches(OUT)
    if not batches:
        print(f"no sm4_batch_*.json under {OUT}")
        return 1
    ev = collect_events(batches)
    if not ev:
        print("no events found")
        return 1

    n_invalid = sum(1 for e in ev if not e.get("valid", True))
    write_event_table(ev, os.path.join(OUT, "event_table.csv"))
    cells = group_cells(ev)

    # ---------------------------------------------------------------- survival per cell
    summ_rows, km_rows, hz_rows = [], [], []
    for (state, f_req, direction), es in cells.items():
        t = np.array([float(e["lifetime_s"]) for e in es])
        c = np.array([int(e["censored"]) for e in es])
        s = summarize(t, c)
        km = s["km"]

        rf = np.array([float(e["realized_force_mean_pn"]) for e in es])
        dev = np.array([float(e["realized_force_max_dev_pn"]) for e in es])
        sd = np.array([float(e["realized_force_sd_pn"]) for e in es])

        # detachment pathway breakdown (observed detachments only)
        paths = {}
        for e in es:
            if int(e["censored"]) == 0:
                paths[e.get("detach_pathway", "?")] = paths.get(e.get("detach_pathway", "?"), 0) + 1

        summ_rows.append(dict(
            state=state, requested_force_pn=f_req, force_dir=direction,
            n=s["n"], n_events=s["n_events"], n_censored=s["n_censored"],
            realized_force_mean_pn=float(np.mean(rf)) if rf.size else float("nan"),
            realized_force_sd_pn=float(np.mean(sd)) if sd.size else float("nan"),
            realized_force_max_dev_pn=float(np.max(dev)) if dev.size else float("nan"),
            force_err_pn=(float(np.mean(np.abs(rf))) - f_req) if rf.size else float("nan"),
            median_s=s["median"], median_ci_lo=s["median_ci_lo"], median_ci_hi=s["median_ci_hi"],
            p90_s=s["p90"], p99_s=s["p99"],
            rmst_s=s["rmst"], rmst_se=s["rmst_se"],
            rmst_ci_lo=s["rmst_ci_lo"], rmst_ci_hi=s["rmst_ci_hi"], rmst_tau_s=s["rmst_tau"],
            mean_naive_s=s["mean_naive"], mean_naive_sem=s["mean_naive_sem"],
            mean_naive_valid=s["mean_naive_valid"],
            detach_pathways=";".join(f"{k}={v}" for k, v in sorted(paths.items())),
            invalid_states=sum(int(e.get("invalid_states", 0)) for e in es),
            solver_failures=sum(int(e.get("solver_failures", 0)) for e in es),
        ))

        for i in range(km["times"].size):
            km_rows.append(dict(state=state, requested_force_pn=f_req, force_dir=direction,
                                time_s=km["times"][i], n_risk=km["n_risk"][i],
                                n_events=km["n_events"][i], n_censored=km["n_censored"][i],
                                survival=km["surv"][i], se=km["se"][i],
                                ci_lo=km["ci_lo"][i], ci_hi=km["ci_hi"][i]))

        for h in empirical_hazard(t, c, n_bins=6):
            hz_rows.append(dict(state=state, requested_force_pn=f_req, force_dir=direction, **h))

    def dump(rows, name, fields=None):
        p = os.path.join(OUT, name)
        if not rows:
            open(p, "w").write("")
            return p
        fields = fields or list(rows[0].keys())
        with open(p, "w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=fields, extrasaction="ignore")
            w.writeheader()
            for r in rows:
                w.writerow(r)
        return p

    dump(summ_rows, "survival_summary.csv")
    dump(km_rows, "kaplan_meier.csv")
    dump(hz_rows, "hazard.csv")

    # ---------------------------------------------------------------- ANALYSIS.md
    md = []
    md.append("# SM4 — force-dependent attachment lifetime: analysis\n")
    b0 = batches[0][1]
    md.append(f"- code revision: `{b0.get('code_rev','?')}`")
    md.append(f"- config hash: `{b0.get('config_hash','?')}`")
    md.append(f"- motor model: `{b0.get('motor_model','?')}`  |  dt = {fmt(b0.get('dt'))} s"
              f"  |  runner: {b0.get('runner','cpu')}")
    md.append(f"- rupture failsafe: **{b0.get('rupture_mode_desc','?')}**")
    md.append(f"- batches: {len(batches)}  |  events: {len(ev)}  |  invalid/aborted: {n_invalid}")
    md.append(f"- **RUN CLASS: {b0.get('run_class','?')}**\n")

    md.append("## Sign convention\n")
    md.append(b0.get("sign_convention", "(not reported)") + "\n")

    md.append("## A. Force-clamp validation\n")
    md.append("Realized force is the mean over the attached window of the axial component of the applied "
              "clamp force actually delivered to the system. `max_dev` is the largest single-sample "
              "deviation from the request within an event.\n")
    md.append("| state | dir | requested (pN) | realized mean (pN) | mean SD (pN) | max dev (pN) | rel err |")
    md.append("|---|---|---:|---:|---:|---:|---:|")
    for r in summ_rows:
        req = r["requested_force_pn"]
        rel = (abs(r["realized_force_mean_pn"]) - req) / req if req > 0 else float("nan")
        md.append(f"| {r['state']} | {r['force_dir']} | {fmt(req)} | {fmt(r['realized_force_mean_pn'])} | "
                  f"{fmt(r['realized_force_sd_pn'])} | {fmt(r['realized_force_max_dev_pn'])} | {fmt(rel,3)} |")
    md.append("")

    md.append("## B. Survival summary (event-level, right-censoring preserved)\n")
    md.append("`RMST` = restricted mean survival time over the observation window — the censoring-correct "
              "location statistic. The naive mean is reported **only** when the cell has zero censoring; "
              "otherwise it is withheld as biased-low.\n")
    md.append("| state | dir | F (pN) | n | events | censored | median (ms) | RMST (ms) | RMST 95% CI | naive mean (ms) | pathways |")
    md.append("|---|---|---:|---:|---:|---:|---:|---:|---|---:|---|")
    for r in summ_rows:
        mean_txt = f"{r['mean_naive_s']*1e3:.4g}" if r["mean_naive_valid"] else "withheld (censored)"
        med_txt = f"{r['median_s']*1e3:.4g}" if np.isfinite(r["median_s"]) else "n.i."
        ci = (f"[{r['rmst_ci_lo']*1e3:.3g}, {r['rmst_ci_hi']*1e3:.3g}]"
              if np.isfinite(r["rmst_ci_lo"]) else "")
        md.append(f"| {r['state']} | {r['force_dir']} | {fmt(r['requested_force_pn'])} | {r['n']} | "
                  f"{r['n_events']} | {r['n_censored']} | {med_txt} | {r['rmst_s']*1e3:.4g} | {ci} | "
                  f"{mean_txt} | {r['detach_pathways']} |")
    md.append("\n`n.i.` = median not identifiable (survival never reached 0.5 within the window).\n")

    md.append("## C. Lifetime vs force\n")
    for state in sorted({r["state"] for r in summ_rows}):
        for d in sorted({r["force_dir"] for r in summ_rows if r["state"] == state}):
            rows = [r for r in summ_rows if r["state"] == state and r["force_dir"] == d]
            rows.sort(key=lambda r: r["requested_force_pn"])
            if not rows:
                continue
            md.append(f"\n**{state} / {d}**\n")
            md.append("| F (pN) | RMST (ms) | median (ms) | events/censored |")
            md.append("|---:|---:|---:|---:|")
            for r in rows:
                med_txt = f"{r['median_s']*1e3:.4g}" if np.isfinite(r["median_s"]) else "n.i."
                md.append(f"| {fmt(r['requested_force_pn'])} | {r['rmst_s']*1e3:.4g} | {med_txt} | "
                          f"{r['n_events']}/{r['n_censored']} |")
            best = max(rows, key=lambda r: r["rmst_s"] if np.isfinite(r["rmst_s"]) else -1)
            md.append(f"\nPeak RMST at F = {fmt(best['requested_force_pn'])} pN "
                      f"({best['rmst_s']*1e3:.4g} ms) — catch-side/slip-side structure is only "
                      f"interpretable once the full production grid is run.\n")

    md.append("\n## D. Health\n")
    tot_inv = sum(r["invalid_states"] for r in summ_rows)
    tot_sf = sum(r["solver_failures"] for r in summ_rows)
    md.append(f"- invalid states: **{tot_inv}**")
    md.append(f"- solver failures: **{tot_sf}**")
    md.append(f"- aborted-at-init (already detached / illegal prepared state): **{n_invalid}**\n")

    md.append("## E. Scope\n")
    md.append(b0.get("scope_note", "") + "\n")

    open(os.path.join(OUT, "ANALYSIS.md"), "w").write("\n".join(md) + "\n")

    print(f"wrote event_table.csv ({len(ev)} events), survival_summary.csv ({len(summ_rows)} cells), "
          f"kaplan_meier.csv ({len(km_rows)} rows), hazard.csv ({len(hz_rows)} rows), ANALYSIS.md")
    print(f"  invalid/aborted events: {n_invalid} | invalid_states {tot_inv} | solver_failures {tot_sf}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
