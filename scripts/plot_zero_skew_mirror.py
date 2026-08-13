#!/usr/bin/env python3
"""Figures for the powered zero-skew native-vs-mirror campaign.

Reads the per-arm TSV records written by `ChiralSiteHarness -zsm-campaign` and plots them. Analysis only:
nothing here re-derives physics, and every number comes from the campaign records.

  python3 scripts/plot_zero_skew_mirror.py [recordDir] [outDir] [seed0] [steps]
"""
import sys, os, glob, math
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

DIR = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/chiral_sites/zero_skew_sparse_mirror"
OUT = sys.argv[2] if len(sys.argv) > 2 else "docs/twirling/figures/zero_skew_sparse_mirror"
SEED0 = int(sys.argv[3]) if len(sys.argv) > 3 else 7001
STEPS = int(sys.argv[4]) if len(sys.argv) > 4 else 8000
os.makedirs(OUT, exist_ok=True)

NAT, MIR = "#1f77b4", "#d62728"
T95 = [0, 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179,
       2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064]


def t95(n):
    return T95[min(n - 1, len(T95) - 1)] if n >= 2 else float("nan")


def load(seed, arm):
    p = os.path.join(DIR, f"zsm_{arm}_s{seed}_n{STEPS}.tsv")
    if not os.path.isfile(p):
        return None
    d = {}
    with open(p) as fh:
        for line in fh:
            if line.startswith("#") or "\t" not in line:
                continue
            k, v = line.rstrip("\n").split("\t")
            d[k] = float(v)
    return d


def load_ep(seed, arm):
    p = os.path.join(DIR, f"zsm_{arm}_s{seed}_n{STEPS}_ep.tsv")
    if not os.path.isfile(p):
        return None
    return np.genfromtxt(p, delimiter="\t", names=True)


seeds, nat, mir = [], [], []
s = SEED0
while True:
    a, b = load(s, "native"), load(s, "mirror")
    if a is None or b is None:
        break
    seeds.append(s); nat.append(a); mir.append(b)
    s += 1
n = len(seeds)
if n == 0:
    sys.exit("no complete matched pairs found")
print(f"loaded {n} matched pairs, seeds {seeds[0]}..{seeds[-1]}")


def col(key, arms):
    return np.array([a.get(key, np.nan) for a in arms])


def stat(v):
    v = v[np.isfinite(v)]
    if len(v) == 0:
        return np.nan, np.nan, 0, np.nan, np.nan
    m = v.mean()
    if len(v) == 1:
        return m, np.nan, 1, np.nan, np.nan
    sem = v.std(ddof=1) / math.sqrt(len(v))
    h = t95(len(v)) * sem
    return m, sem, len(v), m - h, m + h


tau_n, tau_m = col("tau", nat), col("tau", mir)
tau_odd = 0.5 * (tau_n - tau_m)
tau_even = 0.5 * (tau_n + tau_m)
om_n, om_m = col("omegaFit", nat), col("omegaFit", mir)
om_odd = 0.5 * (om_n - om_m)

# ------------------------------------------------- Figure 1: per-seed native vs mirror torque, matched pairs
fig, ax = plt.subplots(figsize=(11, 5))
x = np.arange(n)
for i in x:
    ax.plot([i, i], [tau_n[i], tau_m[i]], color="0.7", lw=1.0, zorder=1)
ax.scatter(x, tau_n, s=42, color=NAT, zorder=3, label="native (MIRROR_SIGN = +1)")
ax.scatter(x, tau_m, s=42, color=MIR, marker="s", zorder=3, label="mirror (MIRROR_SIGN = -1)")
ax.axhline(0, color="k", lw=0.9)
mn, _, _, _, _ = stat(tau_n); mm, _, _, _, _ = stat(tau_m)
ax.axhline(mn, color=NAT, ls="--", lw=1.0, alpha=0.7)
ax.axhline(mm, color=MIR, ls="--", lw=1.0, alpha=0.7)
ax.set_xticks(x); ax.set_xticklabels([str(s) for s in seeds], rotation=90, fontsize=7)
ax.set_xlabel("seed"); ax.set_ylabel("mean deterministic axial torque  tau  (N·m)")
ax.set_title(f"Figure 1 — per-seed native vs mirror axial torque, matched pairs (eps = 0, n = {n})\n"
             f"dashed = arm means: native {mn:+.3e}, mirror {mm:+.3e} N·m", fontsize=10.5)
ax.legend(fontsize=9); ax.grid(alpha=0.25, axis="y")
fig.tight_layout(); fig.savefig(os.path.join(OUT, "fig1_pairs_torque.png"), dpi=170); plt.close(fig)

# ------------------------------------------------------------- Figure 2: per-seed mirror-odd torque + mean/CI
fig, ax = plt.subplots(figsize=(11, 5))
m, sem, nn, lo, hi = stat(tau_odd)
cols = [NAT if v < 0 else MIR for v in tau_odd]
ax.bar(x, tau_odd, color=cols, alpha=0.75, zorder=2)
ax.axhline(0, color="k", lw=0.9, zorder=3)
ax.axhline(m, color="k", ls="--", lw=1.4, zorder=4, label=f"mean {m:+.3e}")
ax.axhspan(lo, hi, color="0.5", alpha=0.22, zorder=1, label="95 % t CI")
neg = int((tau_odd < 0).sum()); pos = int((tau_odd > 0).sum())
ax.set_xticks(x); ax.set_xticklabels([str(s) for s in seeds], rotation=90, fontsize=7)
ax.set_xlabel("seed"); ax.set_ylabel("tau_mirror_odd = 0.5 (tau_nat - tau_mir)   (N·m)")
ax.set_title(f"Figure 2 — per-seed mirror-odd torque (PRIMARY endpoint)\n"
             f"mean {m:+.4e} ± {sem:.2e} N·m   |mean|/SEM = {abs(m)/sem:.2f}   "
             f"95 % CI [{lo:+.3e}, {hi:+.3e}]   signs {neg}-/{pos}+", fontsize=10.5)
ax.legend(fontsize=9); ax.grid(alpha=0.25, axis="y")
fig.tight_layout(); fig.savefig(os.path.join(OUT, "fig2_mirror_odd_torque.png"), dpi=170); plt.close(fig)

# -------------------------------------------------------------------- Figure 3: sequential convergence vs n
fig, axs = plt.subplots(2, 1, figsize=(9.5, 8), sharex=True)
for ax, v, lab in ((axs[0], tau_odd, "tau_mirror_odd  (N·m)"),
                   (axs[1], tau_even, "tau_mirror_even  (N·m)   [control]")):
    ms, los, his = [], [], []
    for k in range(2, n + 1):
        mm2, ss, _, l, h = stat(v[:k])
        ms.append(mm2); los.append(l); his.append(h)
    kk = np.arange(2, n + 1)
    ax.fill_between(kk, los, his, color="0.55", alpha=0.25, label="95 % t CI")
    ax.plot(kk, ms, "-o", ms=4, color="k", label="cumulative mean")
    ax.axhline(0, color="#d62728", lw=1.0, ls="--")
    ax.set_ylabel(lab); ax.grid(alpha=0.25); ax.legend(fontsize=8.5)
    for lad in (8, 12, 16, 20, 24):
        if lad <= n:
            ax.axvline(lad, color="0.8", lw=0.8, zorder=0)
axs[1].set_xlabel("number of matched seeds n   (grey verticals = the preregistered ladder)")
axs[0].set_title("Figure 3 — sequential convergence of the primary endpoint and its mirror-even control",
                 fontsize=11)
fig.tight_layout(); fig.savefig(os.path.join(OUT, "fig3_sequential.png"), dpi=170); plt.close(fig)

# ----------------------------------------------------- Figure 4: angular velocity (SECONDARY endpoint)
fig, axs = plt.subplots(1, 2, figsize=(12, 4.8))
for i in x:
    axs[0].plot([i, i], [om_n[i], om_m[i]], color="0.7", lw=1.0, zorder=1)
axs[0].scatter(x, om_n, s=38, color=NAT, zorder=3, label="native")
axs[0].scatter(x, om_m, s=38, color=MIR, marker="s", zorder=3, label="mirror")
axs[0].axhline(0, color="k", lw=0.9)
axs[0].set_xticks(x); axs[0].set_xticklabels([str(s) for s in seeds], rotation=90, fontsize=6.5)
axs[0].set_xlabel("seed"); axs[0].set_ylabel("omegaFit (rad/s)")
axs[0].set_title("native vs mirror angular velocity", fontsize=10)
axs[0].legend(fontsize=8.5); axs[0].grid(alpha=0.25, axis="y")
m4, s4, _, l4, h4 = stat(om_odd)
axs[1].bar(x, om_odd, color=[NAT if v < 0 else MIR for v in om_odd], alpha=0.75, zorder=2)
axs[1].axhline(0, color="k", lw=0.9, zorder=3)
axs[1].axhline(m4, color="k", ls="--", lw=1.4, zorder=4)
axs[1].axhspan(l4, h4, color="0.5", alpha=0.22, zorder=1)
axs[1].set_xticks(x); axs[1].set_xticklabels([str(s) for s in seeds], rotation=90, fontsize=6.5)
axs[1].set_xlabel("seed"); axs[1].set_ylabel("Omega_mirror_odd (rad/s)")
axs[1].set_title(f"mirror-odd angular velocity: {m4:+.2f} ± {s4:.2f}  "
                 f"(|m|/SEM = {abs(m4)/s4:.2f})", fontsize=10)
axs[1].grid(alpha=0.25, axis="y")
fig.suptitle("Figure 4 — angular velocity (SECONDARY endpoint; not required to resolve)", fontsize=11)
fig.tight_layout(rect=(0, 0, 1, 0.94))
fig.savefig(os.path.join(OUT, "fig4_angular_velocity.png"), dpi=170); plt.close(fig)

# ------------------------------------------------------------------ Figure 5: site azimuth x torque
NB = 18
centres = np.array([math.degrees((b + 0.5) * 2 * math.pi / NB - math.pi) for b in range(NB)])
occ_n = np.array([np.nansum(col(f"hBeta{b}", nat)) for b in range(NB)])
occ_m = np.array([np.nansum(col(f"hBeta{b}", mir)) for b in range(NB)])
tb_n = np.array([[a.get(f"hBetaTau{b}", np.nan) for b in range(NB)] for a in nat])
tb_m = np.array([[a.get(f"hBetaTau{b}", np.nan) for b in range(NB)] for a in mir])
hb_n = np.array([[a.get(f"hBeta{b}", np.nan) for b in range(NB)] for a in nat])
hb_m = np.array([[a.get(f"hBeta{b}", np.nan) for b in range(NB)] for a in mir])
with np.errstate(invalid="ignore", divide="ignore"):
    per_n = np.where(hb_n > 0, tb_n / hb_n, np.nan)      # mean torque per bound sample, per seed
    per_m = np.where(hb_m > 0, tb_m / hb_m, np.nan)
odd_b = 0.5 * (per_n - per_m)
mo = np.array([stat(odd_b[:, b])[0] for b in range(NB)])
so = np.array([stat(odd_b[:, b])[1] for b in range(NB)])
fig, axs = plt.subplots(2, 1, figsize=(10, 7.6), sharex=True)
w = 360.0 / NB * 0.42
axs[0].bar(centres - w / 2, occ_n, width=w, color=NAT, label="native")
axs[0].bar(centres + w / 2, occ_m, width=w, color=MIR, label="mirror")
axs[0].set_ylabel("bound samples (all seeds)")
axs[0].set_title("Figure 5 — site azimuth vs torque\ntop: occupancy by site azimuth; "
                 "beta = 0 points AWAY from the lawn, |beta| = 180 toward it", fontsize=10.5)
axs[0].legend(fontsize=9); axs[0].grid(alpha=0.25, axis="y")
axs[1].errorbar(centres, mo, yerr=so, fmt="o-", ms=5, color="k", capsize=3)
axs[1].axhline(0, color="#d62728", lw=1.0, ls="--")
axs[1].set_xlabel("site azimuth beta (deg)")
axs[1].set_ylabel("mirror-odd mean torque per bound sample (N·m)")
axs[1].grid(alpha=0.25)
axs[1].set_title("bottom: mirror-odd component of the per-sample torque in each azimuth bin", fontsize=10)
fig.tight_layout(); fig.savefig(os.path.join(OUT, "fig5_azimuth_torque.png"), dpi=170); plt.close(fig)

# ------------------------------------------------------- Figure 6: site azimuth x S2 strain (episodes)
eps_n = [load_ep(s, "native") for s in seeds]
eps_m = [load_ep(s, "mirror") for s in seeds]
have = any(e is not None and e.size > 0 for e in eps_n)
if have:
    def gather(es):
        az, ext, bend, tau = [], [], [], []
        for e in es:
            if e is None or e.size == 0:
                continue
            e = np.atleast_1d(e)
            az.append(np.degrees(e["azim"])); ext.append(e["s2Ext"])
            bend.append(e["s2Bend"]); tau.append(e["tauAx"])
        if not az:
            return (np.array([]),) * 4
        return (np.concatenate(az), np.concatenate(ext), np.concatenate(bend), np.concatenate(tau))

    az_n, ext_n, bend_n, ta_n = gather(eps_n)
    az_m, ext_m, bend_m, ta_m = gather(eps_m)

    def wrap(a):
        a = np.mod(a + 180.0, 360.0) - 180.0
        return a

    az_n, az_m = wrap(az_n), wrap(az_m)
    NB2 = 12
    edges = np.linspace(-180, 180, NB2 + 1)
    ctr = 0.5 * (edges[:-1] + edges[1:])

    def binmean(a, v):
        out = np.full(NB2, np.nan); err = np.full(NB2, np.nan)
        for i in range(NB2):
            sel = (a >= edges[i]) & (a < edges[i + 1]) & np.isfinite(v)
            if sel.sum() >= 3:
                out[i] = v[sel].mean(); err[i] = v[sel].std(ddof=1) / math.sqrt(sel.sum())
        return out, err

    fig, axs = plt.subplots(3, 1, figsize=(10, 10), sharex=True)
    for ax, vn, vm, lab in ((axs[0], ext_n, ext_m, "S2 axial extension"),
                            (axs[1], bend_n, bend_m, "S2 transverse / bend proxy"),
                            (axs[2], ta_n, ta_m, "episode axial torque (N·m)")):
        a, ae = binmean(az_n, vn); b, be = binmean(az_m, vm)
        ax.errorbar(ctr, a, yerr=ae, fmt="o-", ms=5, color=NAT, capsize=3, label="native")
        ax.errorbar(ctr, b, yerr=be, fmt="s--", ms=5, color=MIR, capsize=3, label="mirror")
        ax.set_ylabel(lab); ax.grid(alpha=0.25); ax.legend(fontsize=8.5)
    axs[2].axhline(0, color="k", lw=0.9)
    axs[2].set_xlabel("binding-site azimuth at attachment (deg, material frame)")
    axs[0].set_title(f"Figure 6 — site azimuth vs S2 strain and episode torque\n"
                     f"{len(az_n)} native + {len(az_m)} mirror episodes", fontsize=10.5)
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "fig6_azimuth_strain.png"), dpi=170); plt.close(fig)
else:
    print("no episode records — figure 6 skipped")

# --------------------------------------------------------------- Figure 7: attachment-age resolved torque
AB = 10
lo_e = [2 ** b - 1 for b in range(AB)]
an = np.array([[a.get(f"ageTau{b}", np.nan) for b in range(AB)] for a in nat])
am = np.array([[a.get(f"ageTau{b}", np.nan) for b in range(AB)] for a in mir])
nn_ = np.array([[a.get(f"ageN{b}", np.nan) for b in range(AB)] for a in nat])
keep = [b for b in range(AB) if np.nansum(nn_[:, b]) > 0]
odd_a = 0.5 * (an - am)
ma = np.array([stat(odd_a[:, b])[0] for b in keep])
sa = np.array([stat(odd_a[:, b])[1] for b in keep])
mn_ = np.array([stat(an[:, b])[0] for b in keep])
mm_ = np.array([stat(am[:, b])[0] for b in keep])
fig, axs = plt.subplots(2, 1, figsize=(9.5, 7.4), sharex=True)
xb = np.arange(len(keep))
axs[0].plot(xb, mn_, "o-", color=NAT, label="native")
axs[0].plot(xb, mm_, "s--", color=MIR, label="mirror")
axs[0].axhline(0, color="k", lw=0.9)
axs[0].set_ylabel("mean axial torque (N·m)"); axs[0].legend(fontsize=9); axs[0].grid(alpha=0.25)
axs[0].set_title("Figure 7 — attachment-age resolved torque\n(bin b collects heads of age [2^b - 1, 2^(b+1) - 1) steps)",
                 fontsize=10.5)
axs[1].errorbar(xb, ma, yerr=sa, fmt="o-", ms=5, color="k", capsize=3)
axs[1].axhline(0, color="#d62728", lw=1.0, ls="--")
axs[1].set_ylabel("mirror-odd torque (N·m)"); axs[1].grid(alpha=0.25)
axs[1].set_xticks(xb)
axs[1].set_xticklabels([f"{lo_e[b]}–{2**(b+1)-2}" for b in keep], rotation=45, fontsize=8)
axs[1].set_xlabel("attachment age (steps)")
fig.tight_layout(); fig.savefig(os.path.join(OUT, "fig7_age_torque.png"), dpi=170); plt.close(fig)

print("wrote:", ", ".join(sorted(os.listdir(OUT))))
