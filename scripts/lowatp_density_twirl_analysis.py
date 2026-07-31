#!/usr/bin/env python3
"""
DOES THE SKEWED-CONVERTER CHIRAL MECHANISM SURVIVE AT LOW BOUND-HEAD OCCUPANCY?

Analysis-only; no new simulation. Reads matched +/-eps arm PAIRS at 10 uM ATP and 200 ms across three
motor densities and asks whether the eps-ODD (chiral) response is PER-HEAD or COLLECTIVE:

  * a PER-HEAD mechanism predicts   tau_odd  proportional to N_b   =>  tau_odd / N_b INVARIANT with density
  * a COLLECTIVE mechanism (of the target-zone-depletion kind) predicts tau_odd / N_b FALLING as occupancy
    drops, and vanishing below whatever occupancy the collective effect needs

This is the discriminator that matters for whether we may fault a collective explanation for requiring a
large bound population: our own mechanism has to be shown to work when the population is small.

Record sources (all at 10 uM, 200 ms, eta = 0.01, eps = +/-15 deg, native lattice):
  400 heads/um^2 : the COMPLETED low-ATP ladder, atp_u0010.00_d00200000_{p,n}_<seed>       (reused, not rerun)
  100, 50        : atpden_r0100.0_/ r0050.0_u0010.00_d00200000_{p,n}_<seed>                (this campaign)

The eps-ODD estimator is defined ONLY on a matched pair at the same seed:
      X_odd(seed) = 0.5 * [ X(+eps, seed) - X(-eps, seed) ]
and the seed is the independent statistical unit throughout.

Usage: python3 scripts/lowatp_density_twirl_analysis.py [--seeds 101,102,103,104] [--out RUN_LOGS/lowatp]
"""
import argparse, csv, math, os, statistics, sys

REC_DIR = "RUN_LOGS/chiral_sites/lowatp"
DUR_US = 200000
ATP = 10.0
NSEG = 12

# t critical values, two-sided 95 %, by degrees of freedom (n-1)
TCRIT = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306,
         9: 2.262, 10: 2.228, 11: 2.201, 12: 2.179, 15: 2.131, 20: 2.086, 23: 2.069}


def rid(rho, sgn, seed):
    """Record id. 400 lives in the completed ladder's namespace; the new densities in the density namespace."""
    s = "p" if sgn > 0 else "n"
    if abs(rho - 400.0) < 1e-9:
        return f"atp_u{ATP:07.2f}_d{DUR_US:08d}_{s}_{seed}"
    return f"atpden_r{rho:06.1f}_u{ATP:07.2f}_d{DUR_US:08d}_{s}_{seed}"


def read(id_):
    p = os.path.join(REC_DIR, id_ + ".tsv")
    if not os.path.exists(p):
        return None
    v = {}
    for line in open(p):
        if line.startswith("#"):
            continue
        a = line.rstrip("\n").split("\t")
        if len(a) == 2:
            try:
                v[a[0]] = float(a[1])
            except ValueError:
                pass
    return v if v.get("COMPLETE") == 1.0 else None


def stat(xs):
    """mean, sd, sem, sigma (=|mean|/sem), t-based 95% CI half-width, n."""
    xs = [x for x in xs if x is not None and math.isfinite(x)]
    n = len(xs)
    if n == 0:
        return dict(n=0, mean=float("nan"), sd=float("nan"), sem=float("nan"),
                    sigma=float("nan"), ci=float("nan"))
    m = statistics.mean(xs)
    if n == 1:
        return dict(n=1, mean=m, sd=float("nan"), sem=float("nan"), sigma=float("nan"), ci=float("nan"))
    sd = statistics.stdev(xs)
    sem = sd / math.sqrt(n)
    t = TCRIT.get(n - 1, 2.0)
    return dict(n=n, mean=m, sd=sd, sem=sem,
                sigma=(abs(m) / sem if sem > 0 else float("inf")), ci=t * sem)


def welch(a, b):
    """Welch t and an approximate two-sided p, for 'are these two per-head ratios different?'."""
    a = [x for x in a if math.isfinite(x)]
    b = [x for x in b if math.isfinite(x)]
    if len(a) < 2 or len(b) < 2:
        return float("nan"), float("nan")
    ma, mb = statistics.mean(a), statistics.mean(b)
    va, vb = statistics.variance(a) / len(a), statistics.variance(b) / len(b)
    if va + vb <= 0:
        return float("nan"), float("nan")
    t = (ma - mb) / math.sqrt(va + vb)
    # normal approximation to the two-sided p (adequate for a qualitative read at these n)
    p = math.erfc(abs(t) / math.sqrt(2))
    return t, p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seeds", default="101,102,103,104")
    ap.add_argument("--densities", default="400,100,50")
    ap.add_argument("--out", default="RUN_LOGS/lowatp")
    a = ap.parse_args()
    seeds = [int(s) for s in a.seeds.split(",")]
    densities = [float(x) for x in a.densities.split(",")]

    W = []

    def emit(s=""):
        print(s)
        W.append(s)

    emit("=" * 112)
    emit("IS THE SKEWED-CONVERTER CHIRAL TORQUE PER-HEAD OR COLLECTIVE?  eps-ODD response vs motor density")
    emit(f"[ATP] = {ATP:g} uM   200 ms arms (150 ms analysed)   eta = 0.01 Pa.s   eps = +/-15 deg   native lattice")
    emit("=" * 112)
    emit("  eps-ODD estimator: X_odd(seed) = 0.5*[X(+eps,seed) - X(-eps,seed)]; the SEED is the statistical unit.")
    emit("  400 heads/um^2 is the COMPLETED low-ATP ladder, reused unchanged (comparability proven: 80/81 fields")
    emit("  byte-identical on a fresh rerun, trace byte-identical).")
    emit("")

    per_density = {}
    for rho in densities:
        rows = []
        for s in seeds:
            p, m = read(rid(rho, +1, s)), read(rid(rho, -1, s))
            if p is None or m is None:
                emit(f"  rho={rho:.0f} seed={s}: INCOMPLETE PAIR "
                     f"(+eps {'ok' if p else 'MISSING'}, -eps {'ok' if m else 'MISSING'}) -- never paired unmatched")
                continue
            tau_odd = 0.5 * (p["tau"] - m["tau"])
            om_odd = 0.5 * (p["omegaFit"] - m["omegaFit"])
            v_even = 0.5 * (p["glide"] + m["glide"])
            nb = 0.5 * (p["avgBound"] + m["avgBound"])
            # turns per um along the glide path: Omega_odd / (2 pi |v_even|)
            turns = om_odd / (2 * math.pi * abs(v_even)) if v_even else float("nan")
            rows.append(dict(seed=s, tau_odd=tau_odd, om_odd=om_odd, v_even=v_even, nb=nb,
                             turns=turns, tau_per_head=tau_odd / nb if nb else float("nan"),
                             om_per_head=om_odd / nb if nb else float("nan"),
                             invalid=p["invalid"] + m["invalid"],
                             solver=p["solverFail"] + m["solverFail"]))
        if rows:
            per_density[rho] = rows

    if not per_density:
        sys.exit("no complete +/-eps pairs found")

    # ---------------- per-seed detail ------------------------------------------------------------
    emit("--- PER-SEED eps-ODD RESPONSE ---")
    emit(f"  {'rho':>6} {'seed':>5} {'N_b':>8} {'tau_odd (N.m)':>15} {'Omega_odd':>11} {'v_even':>9} "
         f"{'turns/um':>10} {'tau_odd/N_b':>14}")
    for rho in densities:
        for r in per_density.get(rho, []):
            emit(f"  {rho:6.0f} {r['seed']:5d} {r['nb']:8.2f} {r['tau_odd']:+15.4e} {r['om_odd']:+11.2f} "
                 f"{r['v_even']:+9.4f} {r['turns']:+10.3f} {r['tau_per_head']:+14.4e}")
    emit("")

    # ---------------- aggregate ------------------------------------------------------------------
    emit("--- AGGREGATE BY DENSITY (mean +/- SEM, t-based 95% CI, sigma = |mean|/SEM) ---")
    agg = {}
    for rho in densities:
        rows = per_density.get(rho, [])
        if not rows:
            continue
        A = {k: stat([r[k] for r in rows]) for k in
             ("nb", "tau_odd", "om_odd", "v_even", "turns", "tau_per_head")}
        A["signfrac_tau"] = sum(1 for r in rows if r["tau_odd"] < 0) / len(rows)
        A["signfrac_om"] = sum(1 for r in rows if r["om_odd"] < 0) / len(rows)
        A["n"] = len(rows)
        agg[rho] = A
        emit(f"  rho = {rho:.0f} heads/um^2   (n = {A['n']} matched pairs)")
        emit(f"     mean N_b       = {A['nb']['mean']:8.2f} +/- {A['nb']['sem']:.2f}")
        emit(f"     tau_odd        = {A['tau_odd']['mean']:+.4e} +/- {A['tau_odd']['sem']:.2e} N.m   "
             f"({A['tau_odd']['sigma']:.2f} sigma, {A['signfrac_tau']:.0%} of seeds negative)")
        emit(f"     Omega_odd      = {A['om_odd']['mean']:+8.2f} +/- {A['om_odd']['sem']:.2f} rad/s   "
             f"({A['om_odd']['sigma']:.2f} sigma, {A['signfrac_om']:.0%} of seeds negative)")
        emit(f"     v_even         = {A['v_even']['mean']:+8.4f} +/- {A['v_even']['sem']:.4f} um/s")
        emit(f"     turns per um   = {A['turns']['mean']:+8.3f} +/- {A['turns']['sem']:.3f}")
        emit(f"     tau_odd / N_b  = {A['tau_per_head']['mean']:+.4e} +/- {A['tau_per_head']['sem']:.2e} N.m/head "
             f"({A['tau_per_head']['sigma']:.2f} sigma)")
        emit("")

    # ---------------- THE DISCRIMINATOR ----------------------------------------------------------
    emit("=" * 112)
    emit("THE DISCRIMINATOR — is tau_odd/N_b invariant with occupancy (PER-HEAD) or falling (COLLECTIVE)?")
    emit("=" * 112)
    ref = max(agg) if agg else None
    if ref is not None:
        r0 = agg[ref]["tau_per_head"]["mean"]
        emit(f"  {'rho':>6} {'n':>3} {'mean N_b':>9} {'tau_odd/N_b':>14} {'+/- SEM':>11} {'ratio to 400':>13} "
             f"{'Welch t':>9} {'p (approx)':>11}")
        base = [r["tau_per_head"] for r in per_density[ref]]
        for rho in sorted(agg, reverse=True):
            A = agg[rho]
            cur = [r["tau_per_head"] for r in per_density[rho]]
            t, p = welch(cur, base) if rho != ref else (float("nan"), float("nan"))
            emit(f"  {rho:6.0f} {A['n']:3d} {A['nb']['mean']:9.2f} {A['tau_per_head']['mean']:+14.4e} "
                 f"{A['tau_per_head']['sem']:11.2e} {A['tau_per_head']['mean']/r0:13.3f} "
                 f"{t:9.2f} {p:11.3f}")
        emit("")
        emit("  READING:")
        emit("    * tau_odd/N_b statistically indistinguishable across density, and tau_odd itself resolved at")
        emit("      low occupancy  =>  PER-HEAD mechanism: it works with few heads, just proportionally weaker.")
        emit("    * tau_odd/N_b falling significantly as occupancy drops  =>  a COLLECTIVE component exists and")
        emit("      our mechanism carries the same kind of occupancy requirement we would fault elsewhere.")
        emit("    * tau_odd unresolved at low occupancy while tau_odd/N_b is consistent  =>  UNDERPOWERED, not")
        emit("      refuted; report the bound and the seeds needed, do not claim either verdict.")
    emit("")

    # ---------------- health ---------------------------------------------------------------------
    bad = [(rho, r["seed"], r["invalid"], r["solver"]) for rho in per_density
           for r in per_density[rho] if r["invalid"] or r["solver"]]
    emit("--- HEALTH ---")
    npair = sum(len(v) for v in per_density.values())
    emit(f"  matched pairs = {npair} ({2*npair} arms);  invalid/solver anomalies: {'NONE' if not bad else bad}")

    os.makedirs(a.out, exist_ok=True)
    txt = os.path.join(a.out, "density_twirl_perhead.txt")
    with open(txt, "w") as fh:
        fh.write("\n".join(W) + "\n")
    print(f"\n  wrote {txt}")

    csvp = os.path.join(a.out, "density_twirl_perhead.csv")
    with open(csvp, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["density", "seed", "N_b", "tau_odd", "omega_odd", "v_even", "turns_per_um",
                    "tau_odd_per_head", "invalid", "solverFail"])
        for rho in densities:
            for r in per_density.get(rho, []):
                w.writerow([rho, r["seed"], r["nb"], r["tau_odd"], r["om_odd"], r["v_even"],
                            r["turns"], r["tau_per_head"], r["invalid"], r["solver"]])
    print(f"  wrote {csvp}")

    # ---------------- plots ----------------------------------------------------------------------
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception as e:
        print(f"  (matplotlib unavailable, plots skipped: {e})")
        return

    rhos = sorted(agg)
    fig, axes = plt.subplots(1, 3, figsize=(15.5, 4.8))

    ax = axes[0]
    ax.axhline(0, color="0.6", lw=1)
    ax.errorbar(rhos, [agg[r]["tau_odd"]["mean"] for r in rhos],
                yerr=[agg[r]["tau_odd"]["ci"] for r in rhos], fmt="-o", color="#2b6cb0", capsize=4)
    for r in rhos:
        for row in per_density[r]:
            ax.plot(r, row["tau_odd"], "o", ms=3.5, mfc="none", color="0.5", zorder=1)
    ax.set_xscale("log"); ax.set_xticks(rhos); ax.set_xticklabels([f"{r:.0f}" for r in rhos])
    ax.set_xlabel("motor density (heads/um^2)"); ax.set_ylabel("tau_odd (N.m)")
    ax.set_title("chiral torque falls with occupancy"); ax.grid(alpha=0.25)

    ax = axes[1]
    ax.axhline(0, color="0.6", lw=1)
    if rhos:
        ref = max(rhos)
        ax.axhspan(agg[ref]["tau_per_head"]["mean"] - agg[ref]["tau_per_head"]["ci"],
                   agg[ref]["tau_per_head"]["mean"] + agg[ref]["tau_per_head"]["ci"],
                   color="#cfe0f3", alpha=0.7, zorder=0, label="400 heads/um^2 95% CI")
    ax.errorbar(rhos, [agg[r]["tau_per_head"]["mean"] for r in rhos],
                yerr=[agg[r]["tau_per_head"]["ci"] for r in rhos], fmt="-s", color="#8b2f5f", capsize=4,
                zorder=3, label="tau_odd / N_b")
    for r in rhos:
        for row in per_density[r]:
            ax.plot(r, row["tau_per_head"], "o", ms=3.5, mfc="none", color="0.5", zorder=1)
    ax.set_xscale("log"); ax.set_xticks(rhos); ax.set_xticklabels([f"{r:.0f}" for r in rhos])
    ax.set_xlabel("motor density (heads/um^2)"); ax.set_ylabel("tau_odd / N_b (N.m per bound head)")
    ax.set_title("THE DISCRIMINATOR: flat = per-head"); ax.legend(fontsize=8); ax.grid(alpha=0.25)

    ax = axes[2]
    ax.axhline(0, color="0.6", lw=1)
    ax.errorbar([agg[r]["nb"]["mean"] for r in rhos], [agg[r]["om_odd"]["mean"] for r in rhos],
                yerr=[agg[r]["om_odd"]["ci"] for r in rhos], fmt="-o", color="#356859", capsize=4)
    ax.axvline(13.8, color="#c07a2a", ls=":", lw=1.3, label="Vilfan weak bracket (13.8)")
    ax.axvline(29.0, color="#c07a2a", ls="--", lw=1.3, label="Vilfan operational (29)")
    ax.set_xlabel("mean bound heads N_b"); ax.set_ylabel("Omega_odd (rad/s)")
    ax.set_title("chiral rotation vs occupancy"); ax.legend(fontsize=8); ax.grid(alpha=0.25)

    f = os.path.join(a.out, "density_twirl_perhead.png")
    fig.tight_layout(); fig.savefig(f, dpi=150); plt.close(fig)
    print(f"  wrote {f}")


if __name__ == "__main__":
    main()
