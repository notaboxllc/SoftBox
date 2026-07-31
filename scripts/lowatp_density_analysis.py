#!/usr/bin/env python3
"""
LOW-ATP GLIDING OCCUPANCY VERSUS MOTOR DENSITY -- analysis.

Reads the atomic per-arm records written by ChiralSiteHarness -atp-density-map
(RUN_LOGS/chiral_sites/lowatp/atpden_r<rho>_u<atp>_d<durus>_p_<seed>.tsv) together with their
two sidecars:

  *.nbhist.tsv  the FULL simultaneous bound-head occupancy histogram P(N_b), one count per
                measurement step  -- the distribution whose mean is the record's avgBound
  *.trace.tsv   the dense prefix trace (50 us sampling): tS, meanRoll, glideProj, cumBinds,
                cumStrokes, cumDetach, cumBoundSteps -- from which the first/second-half
                consistency, block velocity variability and stall/backward fractions are derived

Nothing here reruns or modifies the simulation; every number is a reduction of stored records.

Usage:  python3 scripts/lowatp_density_analysis.py [--atp 10] [--dur-ms 200] [--out RUN_LOGS/lowatp]
"""
import argparse, csv, glob, math, os, re, sys

REC_DIR = "RUN_LOGS/chiral_sites/lowatp"

# The completed Vilfan target-zone ladder's occupancy brackets. EXPLORATORY overlay only -- these are
# read off a different (Vilfan target-zone) model and are not thresholds of this assay.
VILFAN_WEAK = 13.8      # depletion weak / near-zero through here
VILFAN_OPER = 29.0      # clear partial depletion / operational point

COARSE = [("P(0)", 0, 0), ("P(1)", 1, 1), ("P(2)", 2, 2), ("P(3-5)", 3, 5),
          ("P(6-10)", 6, 10), ("P(11-20)", 11, 20), ("P(>20)", 21, 10**9)]


def read_record(path):
    v, prov = {}, ""
    with open(path) as fh:
        for line in fh:
            if line.startswith("#"):
                prov = line[1:].strip()
                continue
            p = line.rstrip("\n").split("\t")
            if len(p) == 2:
                try:
                    v[p[0]] = float(p[1])
                except ValueError:
                    pass
    return (v, prov) if v.get("COMPLETE") == 1.0 else (None, prov)


def read_hist(path):
    """-> list of (nb, steps)."""
    out = []
    if not os.path.exists(path):
        return out
    with open(path) as fh:
        for line in fh:
            if line.startswith("#") or line.startswith("nb\t"):
                continue
            p = line.split("\t")
            if len(p) >= 2:
                out.append((int(p[0]), int(p[1])))
    return out


def hist_stats(h):
    n = sum(c for _, c in h)
    if n == 0:
        return None
    mean = sum(k * c for k, c in h) / n
    var = sum((k - mean) ** 2 * c for k, c in h) / n
    cum, med = 0, 0
    for k, c in h:
        cum += c
        if cum > n // 2:
            med = k
            break
    coarse = {}
    for name, lo, hi in COARSE:
        coarse[name] = sum(c for k, c in h if lo <= k <= hi) / n
    le2 = sum(c for k, c in h if k <= 2) / n
    return dict(n=n, mean=mean, sd=math.sqrt(var), median=med, coarse=coarse, le2=le2,
                p0=coarse["P(0)"], p1=coarse["P(1)"])


def read_trace(path):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path) as fh:
        next(fh, None)
        for line in fh:
            p = line.split("\t")
            if len(p) >= 7:
                rows.append([float(x) for x in p[:7]])
    return rows


def trace_stats(rows, dur_s, equil_frac, dt):
    """First/second-half consistency, block velocity variability, stall + backward fractions."""
    if not rows:
        return {}
    t0 = equil_frac * dur_s
    m = [r for r in rows if r[0] >= t0 - 1e-12]
    if len(m) < 4:
        return {}
    tS = [r[0] for r in m]
    gl = [r[2] for r in m]
    cb = [r[6] for r in m]          # cumulative bound motor-steps
    span = tS[-1] - tS[0]
    half = tS[0] + 0.5 * span
    i_half = min(range(len(tS)), key=lambda i: abs(tS[i] - half))

    def vel(i, j):
        return (gl[j] - gl[i]) / (tS[j] - tS[i]) if tS[j] > tS[i] else float("nan")

    def occ(i, j):
        ds = (tS[j] - tS[i]) / dt
        return (cb[j] - cb[i]) / ds if ds > 0 else float("nan")

    out = dict(v_first=vel(0, i_half), v_second=vel(i_half, len(tS) - 1),
               nb_first=occ(0, i_half), nb_second=occ(i_half, len(tS) - 1),
               v_full=vel(0, len(tS) - 1))

    # block statistics at two timescales. The FINE scale is thermal-noise dominated and is reported as
    # an upper bound on "stalled or backward"; the COARSE scale is the honest transport statement.
    for tag, blk_ms in (("fine", 1.0), ("coarse", span * 1e3 / 10.0)):
        bs = blk_ms * 1e-3
        if span < 8 * bs:                 # too few blocks to say anything about variability
            continue
        edges, t = [], tS[0]
        while t < tS[-1] - 0.5 * bs:
            edges.append(t)
            t += bs
        vs = []
        for e in edges:
            i = min(range(len(tS)), key=lambda k: abs(tS[k] - e))
            j = min(range(len(tS)), key=lambda k: abs(tS[k] - (e + bs)))
            if j > i:
                vs.append(vel(i, j))
        if not vs:
            continue
        mu = sum(vs) / len(vs)
        sd = math.sqrt(sum((x - mu) ** 2 for x in vs) / max(1, len(vs) - 1))
        # gliding is in -x (pointed-leading); "not forward" = displacement >= 0
        notfwd = sum(1 for x in vs if x >= 0) / len(vs)
        out[f"vblk_{tag}_mean"] = mu
        out[f"vblk_{tag}_sd"] = sd
        out[f"vblk_{tag}_notfwd"] = notfwd
        out[f"vblk_{tag}_n"] = len(vs)
        out[f"vblk_{tag}_ms"] = blk_ms
    return out


def classify(rec, hs, ts):
    """G1 robust / G2 intermittent but net-directed / G3 marginal / G4 none.

    Defined from trajectory AND occupancy, not velocity alone."""
    v = rec["glide"]
    notfwd = ts.get("vblk_coarse_notfwd", 1.0)
    vsd = ts.get("vblk_coarse_sd", float("inf"))
    vmu = ts.get("vblk_coarse_mean", 0.0)
    cv = abs(vsd / vmu) if vmu else float("inf")
    p0 = hs["p0"] if hs else float("nan")
    disp = abs(v) * 0.15   # net displacement over a 150 ms analysed window, um
    if v >= 0 or disp < 0.005:
        return "G4", "no net directed travel"
    if notfwd == 0.0 and cv < 0.5 and p0 < 0.01:
        return "G1", "every block net-forward, stable block velocity, detached time <1%"
    if notfwd <= 0.25 and vmu < 0:
        return "G2", f"net-directed, {notfwd:.0%} of blocks stalled/backward"
    if notfwd <= 0.5:
        return "G3", f"weak/bursty: {notfwd:.0%} of blocks stalled or backward"
    return "G4", "no meaningful directed travel"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--atp", type=float, default=10.0)
    ap.add_argument("--dur-ms", type=float, default=200.0)
    ap.add_argument("--out", default="RUN_LOGS/lowatp")
    a = ap.parse_args()

    dur_us = int(round(a.dur_ms * 1e3))
    pat = os.path.join(REC_DIR, f"atpden_r*_u{a.atp:07.2f}_d{dur_us:08d}_p_*.tsv")
    paths = sorted(p for p in glob.glob(pat)
                   if not p.endswith((".nested.tsv", ".trace.tsv", ".nbhist.tsv")))
    if not paths:
        sys.exit(f"no density records match {pat}")

    arms = []
    for p in paths:
        rec, prov = read_record(p)
        if rec is None:
            print(f"  (incomplete record skipped: {os.path.basename(p)})")
            continue
        base = p[:-4]
        hs = hist_stats(read_hist(base + ".nbhist.tsv"))
        dt = rec["dt"]
        ts = trace_stats(read_trace(base + ".trace.tsv"), rec["durationS"], rec["equilFrac"], dt)
        g, why = classify(rec, hs, ts)
        seed = int(re.search(r"_p_(\d+)\.tsv$", p).group(1))
        arms.append(dict(path=p, id=os.path.basename(base), rec=rec, hist=hs, tr=ts,
                         cls=g, why=why, seed=seed, rho=rec["density"], prov=prov))
    arms.sort(key=lambda x: (-x["rho"], x["seed"]))

    os.makedirs(a.out, exist_ok=True)
    W = []

    def emit(s=""):
        print(s)
        W.append(s)

    emit("=" * 108)
    emit(f"LOW-ATP GLIDING OCCUPANCY vs MOTOR DENSITY   [ATP] = {a.atp:g} uM   duration = {a.dur_ms:g} ms")
    emit("=" * 108)
    r0 = arms[0]["rec"]
    emit(f"  analysed window = {r0['durationS']*1e3*(1-r0['equilFrac']):.1f} ms  "
         f"(warm-up {r0['durationS']*1e3*r0['equilFrac']:.1f} ms = {r0['equilFrac']:.0%})   "
         f"dt = {r0['dt']:.3e} s   eta = {r0['eta']:g} Pa.s   eps = +15 deg (single sign)")
    emit(f"  filament: 12 segments x 64 monomers, contour ~2.11 um, Brownian ON   "
         f"mat 3.0 x 1.0 um = 3.0 um^2")
    emit("")

    # ---------------- occupancy + gliding table -------------------------------------------------
    emit("--- OCCUPANCY AND GLIDING ---")
    emit(f"  {'rho':>6} {'nMot':>6} {'seed':>5} {'meanNb':>8} {'medNb':>6} {'sdNb':>6} "
         f"{'v um/s':>9} {'v1st':>8} {'v2nd':>8} {'Nb1st':>7} {'Nb2nd':>7} "
         f"{'resid ms':>9} {'att/s':>7} {'det/s':>7} {'cls':>4}")
    for x in arms:
        r, ts, hs = x["rec"], x["tr"], x["hist"]
        emit(f"  {x['rho']:6.0f} {r['nMotors']:6.0f} {x['seed']:5d} "
             f"{r['avgBound']:8.3f} {(hs['median'] if hs else float('nan')):6.0f} "
             f"{(hs['sd'] if hs else float('nan')):6.2f} "
             f"{r['glide']:+9.4f} {ts.get('v_first', float('nan')):+8.4f} "
             f"{ts.get('v_second', float('nan')):+8.4f} "
             f"{ts.get('nb_first', float('nan')):7.2f} {ts.get('nb_second', float('nan')):7.2f} "
             f"{r['residenceS']*1e3:9.4f} {r['bindsPerS']:7.0f} {r['detachPerS']:7.0f} {x['cls']:>4}")
    emit("")

    # ---------------- P(N_b) --------------------------------------------------------------------
    emit("--- P(N_b): SIMULTANEOUS BOUND-HEAD OCCUPANCY DISTRIBUTION ---")
    hdr = "  " + f"{'rho':>6} {'seed':>5} " + " ".join(f"{n:>9}" for n, _, _ in COARSE) + f" {'P(<=2)':>9}"
    emit(hdr)
    for x in arms:
        hs = x["hist"]
        if not hs:
            emit(f"  {x['rho']:6.0f} {x['seed']:5d}   (no histogram)")
            continue
        emit("  " + f"{x['rho']:6.0f} {x['seed']:5d} " +
             " ".join(f"{hs['coarse'][n]:9.5f}" for n, _, _ in COARSE) + f" {hs['le2']:9.5f}")
    emit("")

    # ---------------- attachment / detachment ---------------------------------------------------
    emit("--- ATTACHMENT AND DETACHMENT ---")
    emit(f"  {'rho':>6} {'seed':>5} {'attach/s':>10} {'attach N':>10} {'detach/s':>10} {'detach N':>10} "
         f"{'resid ms':>9} {'preLife us':>11} {'fATP':>6}")
    for x in arms:
        r = x["rec"]
        meas = r["durationS"] * (1 - r["equilFrac"])
        dall = r["detachAtp"] + r["detachRigor"] + r["detachOther"]
        emit(f"  {x['rho']:6.0f} {x['seed']:5d} {r['bindsPerS']:10.0f} {r['bindsPerS']*meas:10.0f} "
             f"{r['detachPerS']:10.0f} {r['detachPerS']*meas:10.0f} {r['residenceS']*1e3:9.4f} "
             f"{r['preLifeS']*1e6:11.2f} {(r['detachAtp']/dall if dall else float('nan')):6.3f}")
    emit("")

    # ---------------- stall / backward + block variability --------------------------------------
    emit("--- TRANSPORT QUALITY (from the 50 us trace; gliding is -x, so 'not forward' = displacement >= 0) ---")
    emit(f"  {'rho':>6} {'seed':>5} {'blk ms':>7} {'v mean':>9} {'v SD':>9} {'CV':>7} {'notfwd':>8} "
         f"| {'blk ms':>7} {'v mean':>9} {'v SD':>9} {'notfwd':>8}")
    for x in arms:
        t = x["tr"]
        emit(f"  {x['rho']:6.0f} {x['seed']:5d} "
             f"{t.get('vblk_coarse_ms', float('nan')):7.1f} {t.get('vblk_coarse_mean', float('nan')):+9.4f} "
             f"{t.get('vblk_coarse_sd', float('nan')):9.4f} "
             f"{abs(t.get('vblk_coarse_sd', float('nan'))/t.get('vblk_coarse_mean', float('nan'))):7.2f} "
             f"{t.get('vblk_coarse_notfwd', float('nan')):8.3f} | "
             f"{t.get('vblk_fine_ms', float('nan')):7.1f} {t.get('vblk_fine_mean', float('nan')):+9.4f} "
             f"{t.get('vblk_fine_sd', float('nan')):9.4f} {t.get('vblk_fine_notfwd', float('nan')):8.3f}")
    emit("")

    # ---------------- classification ------------------------------------------------------------
    emit("--- GLIDING CLASSIFICATION ---")
    for x in arms:
        emit(f"  rho={x['rho']:6.0f} seed={x['seed']}  {x['cls']}  ({x['why']})")
    emit("")

    # ---------------- linearity -----------------------------------------------------------------
    by_rho = {}
    for x in arms:
        by_rho.setdefault(x["rho"], []).append(x)
    anchor = max(by_rho)
    nb_anchor = sum(y["rec"]["avgBound"] for y in by_rho[anchor]) / len(by_rho[anchor])
    emit("--- LINEARITY: N_b(rho) vs the proportional expectation N_b(anchor).rho/anchor ---")
    emit(f"  {'rho':>6} {'n':>3} {'meanNb':>9} {'proportional':>13} {'ratio':>7} {'Nb/rho x1000':>13}")
    for rho in sorted(by_rho, reverse=True):
        xs = by_rho[rho]
        nb = sum(y["rec"]["avgBound"] for y in xs) / len(xs)
        pred = nb_anchor * rho / anchor
        emit(f"  {rho:6.0f} {len(xs):3d} {nb:9.3f} {pred:13.3f} {nb/pred:7.3f} {1000*nb/rho:13.3f}")
    emit("")

    # ---------------- Vilfan overlay ------------------------------------------------------------
    emit("--- VILFAN OCCUPANCY OVERLAY (exploratory brackets from the completed target-zone ladder) ---")
    emit(f"  weak/near-zero depletion through N_b ~ {VILFAN_WEAK};  crossover {VILFAN_WEAK}-{VILFAN_OPER};  "
         f"operational point N_b ~ {VILFAN_OPER}")
    for rho in sorted(by_rho, reverse=True):
        xs = by_rho[rho]
        nb = sum(y["rec"]["avgBound"] for y in xs) / len(xs)
        band = ("BELOW the weak-depletion bracket" if nb < VILFAN_WEAK
                else "inside the crossover" if nb < VILFAN_OPER else "at/above the operational point")
        cls = "/".join(sorted({y["cls"] for y in xs}))
        emit(f"  rho={rho:6.0f}  meanNb={nb:7.3f}  {band:34s}  gliding={cls}")
    emit("")

    # ---------------- CSV ------------------------------------------------------------------------
    csv_path = os.path.join(a.out, f"density_occupancy_{a.atp:g}uM_{a.dur_ms:g}ms.csv")
    with open(csv_path, "w", newline="") as fh:
        cols = (["id", "density", "nMotors", "seed", "atpUM", "durationS", "measS", "meanNb", "medianNb",
                 "sdNb"] + [n for n, _, _ in COARSE] +
                ["P_le2", "glide", "v_first", "v_second", "nb_first", "nb_second",
                 "vblk_ms", "vblk_sd", "vblk_notfwd", "vfine_notfwd",
                 "attachPerS", "attachN", "detachPerS", "detachN", "residenceMs", "preLifeUs",
                 "fATP", "invalid", "solverFail", "rateCap", "class"])
        w = csv.writer(fh)
        w.writerow(cols)
        for x in arms:
            r, hs, t = x["rec"], x["hist"], x["tr"]
            meas = r["durationS"] * (1 - r["equilFrac"])
            dall = r["detachAtp"] + r["detachRigor"] + r["detachOther"]
            w.writerow([x["id"], r["density"], r["nMotors"], x["seed"], r["atpUM"], r["durationS"], meas,
                        r["avgBound"], hs["median"] if hs else "", hs["sd"] if hs else ""]
                       + [hs["coarse"][n] if hs else "" for n, _, _ in COARSE]
                       + [hs["le2"] if hs else "", r["glide"], t.get("v_first", ""), t.get("v_second", ""),
                          t.get("nb_first", ""), t.get("nb_second", ""), t.get("vblk_coarse_ms", ""),
                          t.get("vblk_coarse_sd", ""), t.get("vblk_coarse_notfwd", ""),
                          t.get("vblk_fine_notfwd", ""), r["bindsPerS"], r["bindsPerS"] * meas,
                          r["detachPerS"], r["detachPerS"] * meas, r["residenceS"] * 1e3,
                          r["preLifeS"] * 1e6, (r["detachAtp"] / dall if dall else ""),
                          r["invalid"], r["solverFail"], r["rateCapWarns"], x["cls"]])
    emit(f"  wrote {csv_path}")

    # ---------------- health --------------------------------------------------------------------
    bad = [(x["id"], x["rec"]["invalid"], x["rec"]["solverFail"], x["rec"]["rateCapWarns"],
            x["rec"]["detachRigor"], x["rec"]["detachOther"]) for x in arms
           if x["rec"]["invalid"] or x["rec"]["solverFail"] or x["rec"]["rateCapWarns"]
           or x["rec"]["detachRigor"] or x["rec"]["detachOther"]]
    emit("")
    emit("--- HEALTH ---")
    emit(f"  arms = {len(arms)};  invalid/solver/rate-cap/rigor/other-detach anomalies: "
         f"{'NONE' if not bad else bad}")

    txt = os.path.join(a.out, f"density_occupancy_{a.atp:g}uM_{a.dur_ms:g}ms.txt")
    with open(txt, "w") as fh:
        fh.write("\n".join(W) + "\n")
    print(f"  wrote {txt}")

    # ---------------- plots ---------------------------------------------------------------------
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception as e:                                   # plots are optional, tables are not
        print(f"  (matplotlib unavailable, plots skipped: {e})")
        return

    rhos = sorted(by_rho)
    nbs = [sum(y["rec"]["avgBound"] for y in by_rho[r]) / len(by_rho[r]) for r in rhos]
    vs = [sum(y["rec"]["glide"] for y in by_rho[r]) / len(by_rho[r]) for r in rhos]
    le2 = [sum((y["hist"]["le2"] if y["hist"] else float("nan")) for y in by_rho[r]) / len(by_rho[r])
           for r in rhos]

    def scatter_seeds(ax, key):
        for r in rhos:
            for y in by_rho[r]:
                val = (y["rec"]["avgBound"] if key == "nb" else
                       y["rec"]["glide"] if key == "v" else
                       (y["hist"]["le2"] if y["hist"] else float("nan")))
                ax.plot(r, val, "o", ms=4, mfc="none", color="0.45", zorder=3)

    # 1. mean N_b vs density, with the Vilfan overlay
    fig, ax = plt.subplots(figsize=(7.0, 5.0))
    ax.axhspan(0, VILFAN_WEAK, color="#cfe8d5", alpha=0.55, zorder=0,
               label=f"weak / near-zero depletion (N_b <= {VILFAN_WEAK})")
    ax.axhspan(VILFAN_WEAK, VILFAN_OPER, color="#fdeec2", alpha=0.65, zorder=0,
               label=f"crossover ({VILFAN_WEAK}-{VILFAN_OPER})")
    ax.axhline(VILFAN_OPER, color="#c07a2a", ls="--", lw=1.2, zorder=1,
               label=f"Vilfan operational point (N_b ~ {VILFAN_OPER})")
    ax.plot(rhos, nbs, "-", color="#2b6cb0", lw=1.8, zorder=4)
    ax.plot(rhos, nbs, "s", color="#2b6cb0", ms=6, zorder=5, label="SoftBox, 10 uM ATP")
    scatter_seeds(ax, "nb")
    prop = [nbs[-1] * r / rhos[-1] for r in rhos]
    ax.plot(rhos, prop, ":", color="#888", lw=1.4, zorder=2, label="proportional to density")
    for r, n, x in zip(rhos, nbs, [by_rho[r][0] for r in rhos]):
        ax.annotate(x["cls"], (r, n), textcoords="offset points", xytext=(6, -12), fontsize=9, color="#444")
    ax.set_xlabel("motor-head surface density (heads/um^2)")
    ax.set_ylabel("mean simultaneous bound heads  N_b")
    ax.set_title("Low-ATP gliding: occupancy vs motor density (10 uM ATP)")
    ax.set_ylim(bottom=0)
    ax.legend(fontsize=8, loc="upper left", framealpha=0.9)
    ax.grid(alpha=0.25)
    f1 = os.path.join(a.out, "density_1_meanNb_vs_density.png")
    fig.tight_layout(); fig.savefig(f1, dpi=150); plt.close(fig)

    # 2. velocity vs density
    fig, ax = plt.subplots(figsize=(7.0, 5.0))
    ax.axhline(0, color="0.6", lw=1)
    ax.plot(rhos, vs, "-o", color="#8b2f5f", lw=1.8, ms=6, label="net gliding velocity")
    scatter_seeds(ax, "v")
    for r, v, x in zip(rhos, vs, [by_rho[r][0] for r in rhos]):
        ax.annotate(x["cls"], (r, v), textcoords="offset points", xytext=(6, 6), fontsize=9, color="#444")
    ax.set_xlabel("motor-head surface density (heads/um^2)")
    ax.set_ylabel("gliding velocity (um/s)   [negative = pointed-leading = forward]")
    ax.set_title("Low-ATP gliding: velocity vs motor density (10 uM ATP)")
    ax.legend(fontsize=9); ax.grid(alpha=0.25)
    f2 = os.path.join(a.out, "density_2_velocity_vs_density.png")
    fig.tight_layout(); fig.savefig(f2, dpi=150); plt.close(fig)

    # 3. P(N_b <= 2) vs density
    fig, ax = plt.subplots(figsize=(7.0, 5.0))
    ax.plot(rhos, le2, "-o", color="#356859", lw=1.8, ms=6)
    scatter_seeds(ax, "le2")
    ax.set_xlabel("motor-head surface density (heads/um^2)")
    ax.set_ylabel("fraction of analysed time with N_b <= 2")
    ax.set_title("Weak-attachment time vs motor density (10 uM ATP)")
    ax.grid(alpha=0.25)
    f3 = os.path.join(a.out, "density_3_pNb_le2_vs_density.png")
    fig.tight_layout(); fig.savefig(f3, dpi=150); plt.close(fig)

    # 4. the P(N_b) distributions themselves
    fig, ax = plt.subplots(figsize=(7.5, 5.0))
    for r in sorted(by_rho, reverse=True):
        x = by_rho[r][0]
        h = read_hist(os.path.join(REC_DIR, x["id"] + ".nbhist.tsv"))
        if not h:
            continue
        n = sum(c for _, c in h)
        ax.plot([k for k, _ in h], [c / n for _, c in h], lw=1.6, label=f"{r:.0f} heads/um^2")
    ax.axvline(VILFAN_WEAK, color="#c07a2a", ls=":", lw=1.2)
    ax.axvline(VILFAN_OPER, color="#c07a2a", ls="--", lw=1.2)
    ax.set_xlabel("simultaneous bound heads N_b")
    ax.set_ylabel("fraction of analysed time")
    ax.set_title("P(N_b) by motor density (10 uM ATP); dotted/dashed = Vilfan brackets")
    ax.legend(fontsize=9); ax.grid(alpha=0.25)
    f4 = os.path.join(a.out, "density_4_PNb_distributions.png")
    fig.tight_layout(); fig.savefig(f4, dpi=150); plt.close(fig)

    for f in (f1, f2, f3, f4):
        print(f"  wrote {f}")


if __name__ == "__main__":
    main()
