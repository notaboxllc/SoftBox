#!/usr/bin/env python3
"""
Vilfan graded competing-site binding — analysis.

Two things this script must get right, because the harness could not:

1. **s_TZ, the axial target-zone coordinate.** The task defines it as the signed axial displacement from
   the nearest K_total maximum, referenced to the direction of imposed translation — NOT as the
   site-to-motor mismatch xi. The harness records xi and the motor's material arc coordinate; the zone
   centre is recovered here from the run's own K_total(arc) landscape, and s_TZ is measured against it.

2. **Vilfan's sign convention for xi.** Vilfan's analytical section defines xi_i = x_M - X - i*a, i.e.
   MOTOR minus SITE. The harness records xi = SITE minus MOTOR (the form that appears squared in Eq 1).
   So xi_Vilfan = -xi_harness. Vilfan predicts <xi_Vilfan> = K'_th/(K+K'_th) * <x_A> with <x_A> > 0, so
   the published expectation for the harness's xi is NEGATIVE.

Usage: python3 ANALYSIS/vilfan_graded_binding/analyze_graded.py [RUN_DIR] [OUT_DIR]
"""
import csv, glob, math, os, sys
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

RUN = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/vilfan_graded_binding"
OUT = sys.argv[2] if len(sys.argv) > 2 else "ANALYSIS/vilfan_graded_binding"
os.makedirs(OUT, exist_ok=True)

C_A, C_B, C_C, C_N = "#2f6fb2", "#c2603a", "#8a8f98", "#4c4f56"
plt.rcParams.update({"figure.dpi": 130, "font.size": 9, "axes.grid": True, "grid.alpha": 0.25,
                     "axes.spines.top": False, "axes.spines.right": False})

RISE_NM = 2.7                      # SoftBox frozen actin rise
REPEAT_NM = 13 * RISE_NM           # one target-zone repeat = 35.1 nm
# Vilfan Eq 16 conversion, using L = half-pitch of the actin superhelix ~ the zone repeat
KT = 4.14
K_LONG = 0.5                       # pN/nm
ALPHA = 4.0
L_NM = REPEAT_NM
KTH_PRIME = (math.pi ** 2 / L_NM ** 2) * ALPHA * KT      # pN/nm
XI_OVER_XA = KTH_PRIME / (K_LONG + KTH_PRIME)


def save(fig, name):
    fig.tight_layout(); fig.savefig(os.path.join(OUT, name), bbox_inches="tight"); plt.close(fig)
    print("  wrote", os.path.join(OUT, name))


def msem(x):
    x = np.asarray([v for v in x if v == v], float)
    if x.size == 0:
        return float("nan"), float("nan"), 0
    return x.mean(), (x.std(ddof=1) / math.sqrt(x.size) if x.size > 1 else 0.0), x.size


def load_events():
    out = {}
    for p in sorted(glob.glob(os.path.join(RUN, "events", "*.csv"))):
        with open(p) as f:
            rows = list(csv.DictReader(f))
        if rows:
            out[os.path.basename(p)] = rows
    return out


def load_csv(name):
    p = os.path.join(RUN, name)
    if not os.path.exists(p):
        return []
    with open(p) as f:
        return list(csv.DictReader(f))


# --------------------------------------------------------------------------- zone centre from the data
def zone_centre_phase(rows):
    """Recover the K_total maximum's phase within one repeat, from the run's own attachment records.

    Each attachment carries the motor's material arc coordinate and the K_total in force at that moment.
    Binning K_total against arc-modulo-repeat and taking the peak gives the zone centre without assuming
    any analytic groove — the operational definition the task requires.
    """
    arc = np.array([float(r["motor_arc_um"]) for r in rows]) * 1e3      # nm
    kt = np.array([float(r["k_total_per_s"]) for r in rows])
    ok = np.isfinite(arc) & np.isfinite(kt) & (kt > 0)
    if ok.sum() < 20:
        return None, None, None
    ph = np.mod(arc[ok], REPEAT_NM)
    nb = 26
    edges = np.linspace(0, REPEAT_NM, nb + 1)
    idx = np.clip(np.digitize(ph, edges) - 1, 0, nb - 1)
    prof = np.array([kt[ok][idx == b].mean() if (idx == b).sum() else np.nan for b in range(nb)])
    if np.all(np.isnan(prof)):
        return None, None, None
    centre = 0.5 * (edges[np.nanargmax(prof)] + edges[np.nanargmax(prof) + 1])
    return centre, edges, prof


def s_tz(rows, centre, vdir):
    """Signed axial offset from the zone centre, referenced to the direction of imposed translation.

    The motor is fixed and the filament translates at +v along its own axis, so the motor's MATERIAL arc
    coordinate decreases with time. 'Before the centre' therefore means 'still at larger arc', and the sign
    is flipped so that BEFORE < 0, as the task specifies.
    """
    arc = np.array([float(r["motor_arc_um"]) for r in rows]) * 1e3
    d = np.mod(arc - centre + 0.5 * REPEAT_NM, REPEAT_NM) - 0.5 * REPEAT_NM
    return -np.sign(vdir) * d


def analyse_events(ev, label_filter):
    keys = [k for k in ev if label_filter(k)]
    rows = [r for k in keys for r in ev[k]]
    if not rows:
        return None
    centre, edges, prof = zone_centre_phase(rows)
    if centre is None:
        return None
    vdir = float(rows[0]["v_dir"])
    s = s_tz(rows, centre, vdir)
    xi = np.array([float(r["xi_nm"]) for r in rows])
    th = np.array([float(r["theta_rad"]) for r in rows])
    life = np.array([float(r["life_steps"]) for r in rows])
    nb, na = int((s < 0).sum()), int((s > 0).sum())
    return dict(n=len(rows), centre=centre, edges=edges, prof=prof, s=s, xi=xi, theta=th, life=life,
                A=(nb - na) / (nb + na) if nb + na else float("nan"), nb=nb, na=na)


def main():
    print("reading", RUN)
    ev = load_events()
    print(f"  event files = {len(ev)}")

    # ---------------- static landscape figures -------------------------------------------------
    for tag in ("native", "mirror"):
        sc = load_csv(f"landscape_scan_{tag}.csv")
        if not sc:
            continue
        d = np.array([float(r["disp_um"]) for r in sc]) * 1e3
        k = np.array([float(r["K_total_per_s"]) for r in sc])
        th = np.array([float(r["wmean_theta_rad"]) for r in sc])
        fig, ax = plt.subplots(2, 1, figsize=(6.4, 4.6), sharex=True)
        ax[0].plot(d, k, color=C_A, lw=1.5)
        ax[0].set_ylabel("$K_{total}$  (s$^{-1}$)")
        ax[0].set_title(f"(2) total attachment propensity over one 13-subunit repeat — {tag} lattice",
                        loc="left")
        ax[1].plot(d, th, color=C_B, lw=1.3)
        ax[1].axhline(0, color=C_N, lw=0.8)
        ax[1].set_ylabel("rate-weighted $\\langle\\vartheta\\rangle$ (rad)")
        ax[1].set_xlabel("filament axial displacement (nm)")
        save(fig, f"fig02_Ktotal_{tag}.png")

        ht = load_csv(f"landscape_heat_{tag}.csv")
        if ht:
            dd = np.array([float(r["disp_um"]) for r in ht]) * 1e3
            jj = np.array([float(r["j_offset"]) for r in ht])
            kk = np.array([float(r["k_i_per_s"]) for r in ht])
            ud, uj = np.unique(dd), np.unique(jj)
            M = np.full((len(uj), len(ud)), np.nan)
            di = {v: i for i, v in enumerate(ud)}; ji = {v: i for i, v in enumerate(uj)}
            for a, b, c in zip(dd, jj, kk):
                M[ji[b], di[a]] = c
            fig, ax = plt.subplots(figsize=(6.6, 3.4))
            im = ax.pcolormesh(ud, uj, M, shading="nearest", cmap="magma")
            fig.colorbar(im, ax=ax, label="$k_i$ (s$^{-1}$)")
            ax.set_xlabel("filament axial displacement (nm)"); ax.set_ylabel("site offset from the motor")
            ax.set_title(f"(1) per-site attachment hazard — {tag} lattice", loc="left")
            ax.grid(False)
            save(fig, f"fig01_heatmap_{tag}.png")

    # ---------------- control-landscape comparison ---------------------------------------------
    combos = [("", "full graded (α=4)", C_A), ("angular-neutral/", "angular-neutral (α=0)", C_B),
              ("longitudinal-neutral/", "longitudinal-neutral (K=0)", C_C)]
    fig, ax = plt.subplots(figsize=(6.4, 3.2))
    any_ok = False
    for sub, lab, col in combos:
        sc = load_csv(os.path.join(sub, "landscape_scan_native.csv"))
        if not sc:
            continue
        any_ok = True
        d = np.array([float(r["disp_um"]) for r in sc]) * 1e3
        k = np.array([float(r["K_total_per_s"]) for r in sc])
        ax.plot(d, k, color=col, lw=1.6, label=f"{lab}  (modulation {(k.max()-k.min())/k.max():.3f})")
    if any_ok:
        ax.set_xlabel("filament axial displacement (nm)"); ax.set_ylabel("$K_{total}$ (s$^{-1}$)")
        ax.set_title("(2b) both published energy terms are required for target zones", loc="left")
        ax.legend(frameon=False, fontsize=8)
        save(fig, "fig03_landscape_controls.png")
    else:
        plt.close(fig)

    # ---------------- attachment statistics ----------------------------------------------------
    groups = [("graded native", lambda k: "zoneon" in k and "mir+1" in k),
              ("graded mirrored", lambda k: "zoneon" in k and "mir-1" in k)]
    tidy = [["group", "n_events", "zone_centre_nm", "A_TZx", "mean_s_TZ_nm", "sem_s_TZ_nm",
             "mean_xi_harness_nm", "sem_xi", "mean_xi_Vilfan_nm", "mean_theta_rad", "sem_theta",
             "mean_life_steps", "kD_over_kA", "vilfan_predicted_xi_nm"]]
    for name, filt in groups:
        a = analyse_events(ev, filt)
        if a is None:
            continue
        ms_, ss_, _ = msem(a["s"]); mx, sx, _ = msem(a["xi"]); mt, st, _ = msem(a["theta"])
        life_s = a["life"].mean() * 2.5e-6
        kd = 1.0 / life_s if life_s > 0 else float("nan")
        kd_ka = kd / 50.0
        # Vilfan Fig 5B: <x_A> peaks ~2 nm at kD/kA~0.1 and decays; a crude 1/(1+kD/kA) scaling of the peak
        xa = 2.0 / (1.0 + kd_ka)
        tidy.append([name, a["n"], f"{a['centre']:.3f}", f"{a['A']:.4f}", f"{ms_:.4f}", f"{ss_:.4f}",
                     f"{mx:.4f}", f"{sx:.4f}", f"{-mx:.4f}", f"{mt:.5f}", f"{st:.5f}",
                     f"{a['life'].mean():.1f}", f"{kd_ka:.3f}", f"{-XI_OVER_XA*xa:.4f}"])
        print(f"  {name}: n={a['n']}  A_TZx={a['A']:+.4f}  <s_TZ>={ms_:+.3f}±{ss_:.3f} nm  "
              f"<xi>={mx:+.3f}±{sx:.3f} nm  <theta>={mt:+.5f}±{st:.5f}  kD/kA={kd_ka:.2f}")
        # figures
        fig, ax = plt.subplots(1, 2, figsize=(9.0, 3.2))
        ax[0].hist(a["s"], bins=30, color=C_A, alpha=0.8)
        ax[0].axvline(0, color=C_B, lw=1.4, label="zone centre")
        ax[0].set_xlabel("$s_{TZ}$ (nm)   [BEFORE < 0]"); ax[0].set_ylabel("attachments")
        ax[0].set_title(f"(3) axial zone coordinate at attachment — {name}", loc="left", fontsize=8)
        ax[0].legend(frameon=False, fontsize=8)
        ax[1].hist(np.degrees(a["theta"]), bins=30, color=C_B, alpha=0.8)
        ax[1].axvline(0, color=C_N, lw=1.0)
        ax[1].set_xlabel("angular mismatch $\\vartheta$ at attachment (deg)")
        ax[1].set_title(f"(6) angular mismatch — {name}", loc="left", fontsize=8)
        save(fig, f"fig04_attach_{name.replace(' ', '_')}.png")
    with open(os.path.join(OUT, "tidy_attachment.csv"), "w", newline="") as f:
        csv.writer(f).writerows(tidy)
    print("  wrote", os.path.join(OUT, "tidy_attachment.csv"))

    # ---------------- real vs shadow (no-depletion) ---------------------------------------------
    for name in ("binding_only.csv", "dynamic.csv", "ladder.csv"):
        rows = load_csv(name)
        if not rows:
            continue
        out = [["arm", "mirror", "v_um_s", "n_seeds", "attach", "A_TZx", "A_TZx_sem", "mean_xi_nm",
                "sem_xi", "mean_theta_rad", "sem_theta", "tau_Nm", "sem_tau", "omega_rad_s", "sem_omega",
                "turns_per_um", "avg_bound", "attach_rate_hz", "shadow_mean_xi_nm"]]
        seen = []
        for r in rows:
            if r["arm"] not in seen:
                seen.append(r["arm"])
        for arm in seen:
            g = [r for r in rows if r["arm"] == arm]
            def col(k):
                return [float(r[k]) for r in g if r[k] not in ("", "NaN")]
            a, asem, _ = msem(col("A_TZx")); xi, xis, _ = msem(col("mean_xi_nm"))
            th, ths, _ = msem(col("mean_theta_rad")); tq, tqs, _ = msem(col("tau_Nm"))
            om, oms, _ = msem(col("omega_rad_s")); tp, _, _ = msem(col("turns_per_um"))
            ab, _, _ = msem(col("avg_bound")); ar, _, _ = msem(col("attach_rate_hz"))
            sh, _, _ = msem(col("shadow_mean_xi_nm"))
            out.append([arm, g[0]["mirror"], g[0]["v_um_s"], len(g), int(sum(col("attach"))),
                        f"{a:.4f}", f"{asem:.4f}", f"{xi:.4f}", f"{xis:.4f}", f"{th:.5f}", f"{ths:.5f}",
                        f"{tq:.4e}", f"{tqs:.4e}", f"{om:.3f}", f"{oms:.3f}", f"{tp:.4f}",
                        f"{ab:.3f}", f"{ar:.2f}", f"{sh:.4f}"])
        p = os.path.join(OUT, "tidy_" + name)
        with open(p, "w", newline="") as f:
            csv.writer(f).writerows(out)
        print("  wrote", p)
    print("done")


if __name__ == "__main__":
    main()
