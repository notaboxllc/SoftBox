#!/usr/bin/env python3
"""
Rigor-rupture GLIDING IMPACT analysis (Part C/D): paired ON/OFF comparison + decision-gate classification.

    python3 scripts/rigor_gliding_analysis.py <rundir> [single-head|dimer]

single-head: reads <rundir>/off/cell_d<D>_s<S>.json and <rundir>/on/cell_d<D>_s<S>.json (GPU cells).
dimer:       reads <rundir>/rigorrows.csv (RIGORROW lines captured from the dimer harness).

Emits <rundir>/rigor_gliding_paired.csv, <rundir>/rigor_gliding_summary.json, and prints the
Part-D classification (Negligible / Modest / Material) with the paired-seed velocity deltas.
"""
import sys, os, json, glob, csv, math

RUNDIR = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/motor_validation/rigor_gliding_singlehead"
ARCH = sys.argv[2] if len(sys.argv) > 2 else "single-head"


def load_singlehead(d):
    cells = {}
    for cond in ("off", "on"):
        for f in glob.glob(os.path.join(d, cond, "cell_d*_s*.json")):
            try:
                j = json.load(open(f))
            except Exception:
                continue
            key = (int(j["density"]), int(j["seed"]))
            cells.setdefault(key, {})[cond] = j
    return cells


def _sd(xs):
    if len(xs) < 2:
        return 0.0
    m = sum(xs) / len(xs)
    return math.sqrt(sum((x - m) ** 2 for x in xs) / (len(xs) - 1))


RESOLVED_BOUND = 4.0   # a density's ensemble velocity is trustworthy only with >~4 mean bound heads
                       # (fewer ⇒ a single head dominates ⇒ the trajectory is chaos-dominated, not a mean).


def classify(pairs):
    """Part D gate — CHAOS-AWARE, STATISTICAL. Gliding is chaotic AND bistable (CLAUDE.md GPU-number-trust
    rule): a per-seed ON/OFF velocity delta is dominated by BASIN SCATTER. The systematic effect is therefore
    the PAIRED (ON−OFF, same seed) mean per density, tested for significance (|t|), and pooled over the
    RESOLVED densities (mean bound heads > RESOLVED_BOUND) where the velocity is a meaningful ensemble mean.
    A per-density delta that stays within the flag-OFF seed-scatter (chaotic envelope) carries no systematic
    signal. Rupture fraction and bound-head change corroborate."""
    if not pairs:
        return "UNCLASSIFIED (no paired cells)", {}
    by_d = {}
    for p in pairs:
        by_d.setdefault(p["density"], []).append(p)
    per_density = []
    for d, ps in sorted(by_d.items()):
        voff = [p["vel_off"] for p in ps if p["vel_off"]]
        boff = [p["bound_off"] for p in ps]; bon = [p["bound_on"] for p in ps]
        mvoff = sum(voff) / len(voff) if voff else float("nan")
        diffs = [100 * (p["vel_on"] - p["vel_off"]) / abs(p["vel_off"]) for p in ps if p["vel_off"]]
        m = sum(diffs) / len(diffs) if diffs else float("nan")
        sd = _sd(diffs); se = sd / math.sqrt(len(diffs)) if len(diffs) > 1 else float("nan")
        t = m / se if (se and math.isfinite(se) and se > 0) else float("nan")
        off_env = 100 * _sd(voff) / max(1e-9, abs(mvoff)) if voff else 0.0
        mean_bound = sum(boff) / len(boff)
        per_density.append(dict(density=d, n_seeds=len(ps), mean_bound_off=mean_bound,
                                paired_mean_pct=m, paired_se_pct=se, t_stat=t,
                                significant=(math.isfinite(t) and abs(t) >= 2.0),
                                off_chaos_envelope_pct=off_env,
                                within_chaos_envelope=(abs(m) <= max(off_env, 2.0)) if math.isfinite(m) else True,
                                bound_change_pct=100 * abs(sum(bon)/len(bon) - mean_bound) / max(1e-9, abs(mean_bound)),
                                max_rupture_frac=max(p.get("rupture_frac", 0.0) for p in ps),
                                resolved=(mean_bound >= RESOLVED_BOUND)))
    resolved = [pd for pd in per_density if pd["resolved"]]
    # pooled paired difference over ALL resolved-density seeds (the definitive systematic estimate)
    pooled = [100 * (p["vel_on"] - p["vel_off"]) / abs(p["vel_off"])
              for p in pairs if p["vel_off"] and (sum(pp["bound_off"] for pp in by_d[p["density"]]) /
                                                  len(by_d[p["density"]])) >= RESOLVED_BOUND]
    pm = sum(pooled) / len(pooled) if pooled else float("nan")
    pse = _sd(pooled) / math.sqrt(len(pooled)) if len(pooled) > 1 else float("nan")
    pt = pm / pse if (pse and math.isfinite(pse) and pse > 0) else float("nan")
    any_sig = any(pd["significant"] for pd in resolved)
    max_res_abs = max((abs(pd["paired_mean_pct"]) for pd in resolved), default=0.0)
    max_rf = max((pd["max_rupture_frac"] for pd in per_density), default=0.0)
    max_bpct = max((pd["bound_change_pct"] for pd in resolved), default=0.0)
    stats = dict(per_density=per_density, resolved_bound_threshold=RESOLVED_BOUND,
                 pooled_resolved_mean_pct=pm, pooled_resolved_se_pct=pse, pooled_resolved_t=pt,
                 pooled_n=len(pooled), any_resolved_density_significant=any_sig,
                 max_resolved_abs_pct=max_res_abs, max_bound_change_pct=max_bpct, max_rupture_frac=max_rf)
    # NEGLIGIBLE: no resolved density shows a SIGNIFICANT systematic delta, the pooled resolved effect is not
    # significant (|t|<2) and small (<2%), rupture is rare (~<2.5%), and bound-head change is small.
    pooled_neg = (not math.isfinite(pt)) or (abs(pt) < 2.0) or (abs(pm) < 2.0)
    if (not any_sig) and pooled_neg and max_res_abs < 5.0 and max_rf < 0.025 and max_bpct < 5.0:
        return "NEGLIGIBLE", stats
    # MODEST: a reproducible, significant systematic 2-5% change with saturation preserved.
    if math.isfinite(pt) and abs(pt) >= 2.0 and abs(pm) <= 5.0 and max_res_abs <= 5.0:
        return "MODEST", stats
    return "MATERIAL", stats


def run_singlehead(d):
    cells = load_singlehead(d)
    pairs = []
    for (dens, seed), cc in sorted(cells.items()):
        if "off" not in cc or "on" not in cc:
            continue
        off, on = cc["off"], cc["on"]
        pairs.append(dict(
            density=dens, seed=seed,
            vel_off=off.get("vel_prod"), vel_on=on.get("vel_prod"),
            vel_delta=(on.get("vel_prod", 0) - off.get("vel_prod", 0)),
            bound_off=off.get("mean_bound_heads"), bound_on=on.get("mean_bound_heads"),
            lifetime_off_ms=off.get("lifetime_mean_ms"), lifetime_on_ms=on.get("lifetime_mean_ms"),
            atp_turn_off=off.get("atp_turnover"), atp_turn_on=on.get("atp_turnover"),
            nuc_none_off=off.get("nuc_occ_none"), nuc_none_on=on.get("nuc_occ_none"),
            ruptures=on.get("rigor_ruptures", 0),
            rupture_frac=on.get("rigor_rupture_frac_of_detach", 0.0),
            force_at_rupture_mean=on.get("force_at_rupture_mean_pn", 0.0),
            force_at_rupture_max=on.get("force_at_rupture_max_pn", 0.0),
            peak_load_off=off.get("peak_load_pn"), peak_load_on=on.get("peak_load_pn"),
            rate_cap_warnings=on.get("rate_cap_warnings", 0),
            vpb_off=off.get("vel_per_bound_head"), vpb_on=on.get("vel_per_bound_head"),
        ))
    with open(os.path.join(d, "rigor_gliding_paired.csv"), "w", newline="") as fh:
        if pairs:
            w = csv.DictWriter(fh, fieldnames=list(pairs[0].keys())); w.writeheader(); [w.writerow(p) for p in pairs]
    cls, stats = classify(pairs)
    out = dict(architecture="single-head", run_dir=d, n_pairs=len(pairs), classification=cls, stats=stats, pairs=pairs)
    json.dump(out, open(os.path.join(d, "rigor_gliding_summary.json"), "w"), indent=2, default=float)

    print(f"\n=== SINGLE-HEAD rigor-gliding paired ON/OFF ({len(pairs)} cells) ===")
    print(f"{'dens':>6}{'seed':>6}{'vel_off':>10}{'vel_on':>10}{'Δvel%':>8}{'bound_off':>10}{'bound_on':>10}{'ruptures':>9}{'rup_frac':>9}{'Fmax@rup':>9}")
    for p in pairs:
        dvp = 100 * (p["vel_on"] - p["vel_off"]) / abs(p["vel_off"]) if p["vel_off"] else 0.0
        print(f"{p['density']:>6}{p['seed']:>6}{p['vel_off']:>10.4f}{p['vel_on']:>10.4f}{dvp:>8.3f}"
              f"{p['bound_off']:>10.3f}{p['bound_on']:>10.3f}{p['ruptures']:>9d}{p['rupture_frac']:>9.5f}{p['force_at_rupture_max']:>9.2f}")
    print(f"\n  --- per-density PAIRED ON−OFF (systematic effect) vs flag-OFF chaotic envelope ---")
    print(f"  {'dens':>6}{'n':>4}{'bound':>7}{'paired%Δv':>11}{'±SE':>8}{'|t|':>7}{'sig?':>6}{'OFF-chaos%':>12}{'within?':>8}{'resolved?':>10}{'rup_frac':>9}")
    for pd in stats["per_density"]:
        print(f"  {pd['density']:>6}{pd['n_seeds']:>4}{pd['mean_bound_off']:>7.1f}{pd['paired_mean_pct']:>11.2f}"
              f"{pd['paired_se_pct']:>8.2f}{abs(pd['t_stat']):>7.2f}{str(pd['significant']):>6}"
              f"{pd['off_chaos_envelope_pct']:>12.2f}{str(pd['within_chaos_envelope']):>8}{str(pd['resolved']):>10}{pd['max_rupture_frac']:>9.5f}")
    print(f"\n  POOLED over resolved densities (mean bound > {stats['resolved_bound_threshold']:.0f} heads, n={stats['pooled_n']} seeds): "
          f"{stats['pooled_resolved_mean_pct']:+.2f}% ± {stats['pooled_resolved_se_pct']:.2f} (|t|={abs(stats['pooled_resolved_t']):.2f})")
    print(f"  any resolved density significant (|t|>=2)? {stats['any_resolved_density_significant']}  |  "
          f"max resolved |Δv| = {stats['max_resolved_abs_pct']:.2f}%  max |Δbound| = {stats['max_bound_change_pct']:.2f}%  "
          f"max rupture-frac = {stats['max_rupture_frac']:.5f}")
    print(f"\n  CLASSIFICATION: {cls}")
    return out


def run_dimer(d):
    rows = []
    p = os.path.join(d, "rigorrows.csv")
    if not os.path.exists(p):
        print(f"  (no rigorrows.csv in {d})"); return None
    for line in open(p):
        if not line.startswith("RIGORROW"):
            continue
        f = line.strip().split(",")
        rows.append(dict(density=float(f[1]), seed=int(f[2]), nDim=int(f[3]), heads=int(f[4]), steps=int(f[5]),
                         rigor_on=f[6] == "true", vel=float(f[7]), bound=float(f[8]), ruptures=int(f[9]),
                         rupture_frac=float(f[10]), rup_fmean=float(f[11]), rup_fmin=float(f[12]),
                         rup_fmax=float(f[13]), cap=int(f[14]), twoFrac=float(f[15]), cont=float(f[16])))
    pairs = {}
    for r in rows:
        pairs.setdefault((r["density"], r["seed"]), {})["on" if r["rigor_on"] else "off"] = r
    plist = []
    for (dens, seed), cc in sorted(pairs.items()):
        if "off" not in cc or "on" not in cc:
            continue
        off, on = cc["off"], cc["on"]
        plist.append(dict(density=dens, seed=seed, vel_off=off["vel"], vel_on=on["vel"],
                          bound_off=off["bound"], bound_on=on["bound"], ruptures=on["ruptures"],
                          rupture_frac=on["rupture_frac"], force_at_rupture_max=on["rup_fmax"]))
    cls, stats = classify(plist)
    print(f"\n=== DIMER rigor-gliding paired ON/OFF ({len(plist)} cells) ===")
    print(f"{'dens':>6}{'seed':>6}{'vel_off':>10}{'vel_on':>10}{'Δvel%':>8}{'bound_off':>10}{'bound_on':>10}{'ruptures':>9}{'rup_frac':>9}")
    for p_ in plist:
        dvp = 100 * (p_["vel_on"] - p_["vel_off"]) / abs(p_["vel_off"]) if p_["vel_off"] else 0.0
        print(f"{p_['density']:>6.0f}{p_['seed']:>6}{p_['vel_off']:>10.4f}{p_['vel_on']:>10.4f}{dvp:>8.3f}"
              f"{p_['bound_off']:>10.3f}{p_['bound_on']:>10.3f}{p_['ruptures']:>9d}{p_['rupture_frac']:>9.5f}")
    print(f"\n  CLASSIFICATION: {cls}  (max |Δvel|={stats.get('max_vel_change_pct',0):.3f}%, max rupture-frac={stats.get('max_rupture_frac',0):.5f})")
    json.dump(dict(architecture="dimer", classification=cls, stats=stats, pairs=plist),
              open(os.path.join(d, "rigor_gliding_summary_dimer.json"), "w"), indent=2, default=float)
    return plist


if __name__ == "__main__":
    if ARCH == "dimer":
        run_dimer(RUNDIR)
    else:
        run_singlehead(RUNDIR)
