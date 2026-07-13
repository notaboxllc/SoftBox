#!/usr/bin/env python3
"""Stdlib-only analysis for RUN_LOGS/j2_conformation.

Prints machine-reproducible TSV tables for the native angle audit, deterministic
ladder, force-velocity screen, episode kernel, and density-saturation fit.
"""
from __future__ import annotations

import argparse
import glob
import math
import os
import re
import statistics as st

NUM = r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?"


def kv(line: str) -> dict[str, float | str]:
    out: dict[str, float | str] = {}
    for key, value in re.findall(rf"(\w+)=([^\s]+)", line):
        try:
            out[key] = float(value.rstrip(","))
        except ValueError:
            out[key] = value
    return out


def lines(path: str, prefix: str):
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if line.startswith(prefix):
                yield line.rstrip()


def ci95(values: list[float]) -> float:
    if len(values) < 2:
        return 0.0
    t = {2: 12.706, 3: 4.303, 4: 3.182, 5: 2.776, 6: 2.571,
         7: 2.447, 8: 2.365}.get(len(values), 1.96)
    return t * st.stdev(values) / math.sqrt(len(values))


def pearson(xs: list[float], ys: list[float]) -> float:
    if len(xs) < 2:
        return math.nan
    mx, my = st.mean(xs), st.mean(ys)
    den = math.sqrt(sum((x-mx)**2 for x in xs) * sum((y-my)**2 for y in ys))
    return sum((x-mx)*(y-my) for x, y in zip(xs, ys)) / den if den else math.nan


def native(root: str) -> None:
    paths = glob.glob(os.path.join(root, "native_clamp", "v*_s*.log"))
    groups: dict[tuple[float, str], list[dict]] = {}
    hist: dict[float, int] = {}
    episodes: list[dict] = []
    ages: dict[tuple[float, float], list[dict]] = {}
    for path in paths:
        vm = re.search(r"/v([^/_]+)_s\d+\.log$", path)
        path_v = float(vm.group(1)) if vm else math.nan
        for line in lines(path, "J2SUMMARY"):
            d = kv(line); groups.setdefault((float(d["vclamp_v"] if "vclamp_v" in d else re.search(r"vclamp_v("+NUM+")", line).group(1)), str(d["state"])), []).append(d)
        for line in lines(path, "J2HIST"):
            if "vclamp_v0.000" in line:
                d = kv(line); hist[float(d["angle_deg"])] = hist.get(float(d["angle_deg"]), 0) + int(d["n"])
        for line in lines(path, "J2AGE"):
            d = kv(line)
            ages.setdefault((path_v, float(d["ageLo_ms"])), []).append(d)
        episodes.extend(kv(x) for x in lines(path, "J2EP"))
    print("\n[NATIVE_SUMMARY]\nv\tstate\tn\tmean_deg\tsd_deg\tseed_p05_mean\tseed_p25_mean\tseed_median_mean\tseed_p75_mean\tseed_p95_mean\tseed_median_CI95")
    for (v, state), q in sorted(groups.items()):
        n = sum(int(x["n"]) for x in q)
        mean = sum(float(x["mean"])*int(x["n"]) for x in q)/n
        var = sum(int(x["n"])*(float(x["sd"])**2+(float(x["mean"])-mean)**2) for x in q)/n
        meds = [float(x["median"]) for x in q]
        print(f"{v:g}\t{state}\t{n}\t{mean:.6f}\t{math.sqrt(var):.6f}\t"
              f"{st.mean(float(x['p05']) for x in q):.4f}\t{st.mean(float(x['p25']) for x in q):.4f}\t"
              f"{st.mean(meds):.4f}\t{st.mean(float(x['p75']) for x in q):.4f}\t"
              f"{st.mean(float(x['p95']) for x in q):.4f}\t{ci95(meds):.4f}")
    if hist:
        total, carry = sum(hist.values()), 0
        median = math.nan
        for angle in sorted(hist):
            carry += hist[angle]
            if carry >= (total+1)//2:
                median = angle; break
        mean = sum(a*n for a, n in hist.items())/total
        sd = math.sqrt(sum(n*(a-mean)**2 for a, n in hist.items())/total)
        print(f"NATIVE_REST\tn={total}\tpooled_mean_deg={mean:.6f}\tpooled_sd_deg={sd:.6f}\tpooled_median_deg={median:.1f}")
    if episodes:
        print("NATIVE_EPISODE_CORRELATIONS")
        for key in ("life_ms", "impulse", "peakDisp_nm", "relaxDisp_nm", "meanF8ax_pN", "meanF8tr_pN", "meanSwErr"):
            q = [e for e in episodes if "mean" in e and key in e]
            print(f"meanJ2:{key}\tn={len(q)}\tr={pearson([float(e['mean']) for e in q],[float(e[key]) for e in q]):+.6f}")
    if ages:
        print("NATIVE_AGE\tv\tageLo_ms\tageHi_ms\tn\tmeanJ2_deg\tF8ax_pN\tF8tr_pN\tswingErr_deg\ttipSite_nm")
        selected = {0.0, .025, .05, .10, .20, .30, .50, .75, 1.0}
        for (v, lo), q in sorted(ages.items()):
            if round(lo, 3) not in selected: continue
            count = sum(int(x["n"]) for x in q)
            wmean = lambda key: sum(int(x["n"])*float(x[key]) for x in q)/count
            print(f"NATIVE_AGE\t{v:g}\t{lo:.3f}\t{max(float(x['ageHi_ms']) for x in q):.3f}\t{count}\t{wmean('mean'):.6f}\t{wmean('F8ax_pN'):+.6f}\t{wmean('F8tr_pN'):.6f}\t{wmean('swErr'):.6f}\t{wmean('tipSite_nm'):+.6f}")


def energy_ladder() -> None:
    kt = 1.380662e-23 * 298.15 * 1e21  # pN nm
    print("\n[ENERGY_LADDER]\nkappa_pNnm_rad\tE10_kT\tE20_kT\tE30_kT\tE24pre_kT")
    for k in (0, .01, .03, .1, .3, 1, 3, 10, 30, 100, 300):
        e = lambda deg: .5*k*math.radians(deg)**2/kt
        print(f"{k:g}\t{e(10):.6f}\t{e(20):.6f}\t{e(30):.6f}\t{e(24.3568):.6f}")


def native_glide(root: str) -> None:
    summaries: dict[tuple[float, str], list[dict]] = {}
    episodes: dict[float, list[dict]] = {}
    ages: dict[tuple[float, float], list[dict]] = {}
    for path in glob.glob(os.path.join(root, "native_glide", "d*_s*.log")):
        m = re.search(r"/d([^/_]+)_s\d+\.log$", path)
        if not m: continue
        rho = float(m.group(1))
        for line in lines(path, "J2SUMMARY"):
            q = kv(line); summaries.setdefault((rho, str(q["state"])), []).append(q)
        episodes.setdefault(rho, []).extend(kv(x) for x in lines(path, "J2EP"))
        for line in lines(path, "J2AGE"):
            q = kv(line); ages.setdefault((rho, float(q["ageLo_ms"])), []).append(q)
    if not summaries: return
    print("\n[NATIVE_GLIDE_SUMMARY]\ndensity\tstate\tn\tmean_deg\tsd_deg\tseed_p05_mean\tseed_p25_mean\tseed_median_mean\tseed_p75_mean\tseed_p95_mean\taxis_coherence")
    for (rho, state), q in sorted(summaries.items()):
        n = sum(int(x["n"]) for x in q)
        mean = sum(int(x["n"])*float(x["mean"]) for x in q)/n
        var = sum(int(x["n"])*(float(x["sd"])**2+(float(x["mean"])-mean)**2) for x in q)/n
        print(f"{rho:g}\t{state}\t{n}\t{mean:.6f}\t{math.sqrt(var):.6f}\t"
              f"{st.mean(float(x['p05']) for x in q):.6f}\t{st.mean(float(x['p25']) for x in q):.6f}\t"
              f"{st.mean(float(x['median']) for x in q):.6f}\t{st.mean(float(x['p75']) for x in q):.6f}\t"
              f"{st.mean(float(x['p95']) for x in q):.6f}\t{st.mean(float(x['axisCoherence']) for x in q):.6f}")
    print("NATIVE_GLIDE_EPISODE_CORRELATIONS\tdensity\tpair\tn\tr")
    for rho, ep in sorted(episodes.items()):
        for key in ("life_ms", "impulse", "peakDisp_nm", "relaxDisp_nm", "meanF8ax_pN", "meanF8tr_pN", "meanSwErr"):
            q = [x for x in ep if "mean" in x and key in x]
            print(f"NATIVE_GLIDE_EPISODE_CORRELATIONS\t{rho:g}\tmeanJ2:{key}\t{len(q)}\t{pearson([float(x['mean']) for x in q],[float(x[key]) for x in q]):+.6f}")
    print("NATIVE_GLIDE_AGE\tdensity\tageLo_ms\tageHi_ms\tn\tmeanJ2_deg\tF8ax_pN\tF8tr_pN\tswingErr_deg\ttipSite_nm")
    selected = {0.0, .025, .05, .10, .20, .30, .50, .75, 1.0}
    for (rho, lo), q in sorted(ages.items()):
        if round(lo, 3) not in selected: continue
        count = sum(int(x["n"]) for x in q)
        wmean = lambda key: sum(int(x["n"])*float(x[key]) for x in q)/count
        print(f"NATIVE_GLIDE_AGE\t{rho:g}\t{lo:.3f}\t{max(float(x['ageHi_ms']) for x in q):.3f}\t{count}\t{wmean('mean'):.6f}\t{wmean('F8ax_pN'):+.6f}\t{wmean('F8tr_pN'):.6f}\t{wmean('swErr'):.6f}\t{wmean('tipSite_nm'):+.6f}")


def single(root: str) -> None:
    rows = []
    for path in glob.glob(os.path.join(root, "single_motor", "k*_dt*.log")):
        m = re.search(r"k([^_]+)_dt(.+)\.log$", path)
        if not m: continue
        k, dt = float(m.group(1)), float(m.group(2))
        pre = next((kv(x) for x in lines(path, "J2DET phase=pre")), None)
        for line in lines(path, "J2SLIDE"):
            d = kv(line); rows.append((k, dt, pre, d))
    print("\n[SINGLE_MOTOR]\nkappa\tdt\tv\tpreJ2\tpreE_kT\tpreTorque\tmeanJ2\tpeakDisp\trelaxDisp\tfinalDisp\tmaxF8\tmaxF8tr\tmaxTorque\tmaxE\tdirWork_kT\tanchor_nm\tJ1_deg\tDIRSWING_error_deg")
    for k, dt, pre, d in sorted(rows, key=lambda x: (x[0], x[1], float(x[3]["v"]))):
        print(f"{k:g}\t{dt:g}\t{float(d['v']):g}\t{float(pre['j2_deg']):.5f}\t{float(pre['energy_kT']):.6f}\t{float(pre['torque_pNnm']):+.5f}\t{float(d['meanJ2_deg']):.5f}\t{float(d['peakDisp_nm']):+.5f}\t{float(d['relaxDisp_nm']):+.5f}\t{float(d['finalDisp_nm']):+.5f}\t{float(d['maxF8_pN']):.5f}\t{float(d['maxF8tr_pN']):.5f}\t{float(d['maxTorque_pNnm']):.5f}\t{float(d['maxEnergy_pNnm']):.5f}\t{float(d['dirWork_kT']):+.5f}\t{float(d['anchor_nm']):.5f}\t{float(d['j1_deg']):.5f}\t{float(d['swErr_deg']):.5f}")


def read_fv(directory: str) -> dict[tuple[float, float, int], dict]:
    rows = {}
    for path in glob.glob(os.path.join(directory, "k*_v*_s*.log")):
        m = re.search(r"k([^_]+)_v([^_]+)_s(\d+)\.log$", path)
        if not m: continue
        row = next((kv(x) for x in lines(path, "FVROW")), None)
        if row: rows[float(m.group(1)), float(m.group(2)), int(m.group(3))] = row
    return rows


def fv(root: str) -> None:
    for name in ("fv_screen", "fv_powered"):
        rows = read_fv(os.path.join(root, name))
        if not rows: continue
        print(f"\n[{name.upper()}]\nkappa\tv\tnseed\tfbar_avail\tCI95\tfbar_bound\tNbound\tJattach_per_s\tlife_ms\tIattach\tnEpisode")
        for k, v in sorted(set((k, v) for k, v, _ in rows)):
            q = [d for (kk, vv, _), d in rows.items() if kk == k and vv == v]
            a = [float(x["fbar_avail"]) for x in q]
            print(f"{k:g}\t{v:g}\t{len(q)}\t{st.mean(a):+.6f}\t{ci95(a):.6f}\t{st.mean(float(x['fbar_bound']) for x in q):+.6f}\t{st.mean(float(x['Nbound']) for x in q):.4f}\t{st.mean(float(x['Jattach']) for x in q):.5f}\t{st.mean(float(x['life_ms']) for x in q):.5f}\t{st.mean(float(x['Iattach']) for x in q):+.6f}\t{sum(int(x['nEp']) for x in q)}")
        # Per-seed local linear zeros. Use the compact 10..16 bracket when present.
        print("LOCAL_ZERO\tkappa\tnseed\tV0_mean\tV0_CI95\tslope_mean")
        zero_estimates = {}
        for k in sorted(set(x[0] for x in rows)):
            estimates = []
            for seed in sorted(set(s for kk, _, s in rows if kk == k)):
                q = sorted((v, float(d["fbar_avail"])) for (kk, v, s), d in rows.items() if kk == k and s == seed and 10 <= v <= 16)
                if len(q) < 2: continue
                mx, my = st.mean(x for x, _ in q), st.mean(y for _, y in q)
                den = sum((x-mx)**2 for x, _ in q); slope = sum((x-mx)*(y-my) for x, y in q)/den
                if slope: estimates.append((seed, mx-my/slope, slope))
            if estimates:
                zero_estimates[k] = {seed: (z, slope) for seed, z, slope in estimates}
                z = [x[1] for x in estimates]
                print(f"LOCAL_ZERO\t{k:g}\t{len(z)}\t{st.mean(z):.6f}\t{ci95(z):.6f}\t{st.mean(x[2] for x in estimates):+.6f}")
        if 0.0 in zero_estimates:
            print("FV_ZERO_PAIRED\tkappa\tnpair\tdelta_V0\tdelta_V0_CI95\tdelta_slope\tdelta_slope_CI95")
            for k in sorted(set(zero_estimates)-{0.0}):
                seeds = sorted(set(zero_estimates[0.0]) & set(zero_estimates[k]))
                dz = [zero_estimates[k][s][0]-zero_estimates[0.0][s][0] for s in seeds]
                ds = [zero_estimates[k][s][1]-zero_estimates[0.0][s][1] for s in seeds]
                if dz:
                    print(f"FV_ZERO_PAIRED\t{k:g}\t{len(dz)}\t{st.mean(dz):+.6f}\t{ci95(dz):.6f}\t{st.mean(ds):+.6f}\t{ci95(ds):.6f}")
        baseline = {(v, seed): float(d["fbar_avail"])
                    for (k, v, seed), d in rows.items() if k == 0}
        print("FV_FORCE_PAIRED\tkappa\tv\tnpair\tdelta_fbar_avail\tdelta_fbar_CI95")
        for k in sorted(set(x[0] for x in rows)-{0.0}):
            for v in sorted(set(x[1] for x in rows if x[0] == k)):
                delta = [float(d["fbar_avail"])-baseline[v, seed]
                         for (kk, vv, seed), d in rows.items()
                         if kk == k and vv == v and (v, seed) in baseline]
                if delta:
                    print(f"FV_FORCE_PAIRED\t{k:g}\t{v:g}\t{len(delta)}\t{st.mean(delta):+.6f}\t{ci95(delta):.6f}")
        mech = {}
        for path in glob.glob(os.path.join(root, name, "k*_v*_s*.log")):
            m = re.search(r"k([^_]+)_v([^_]+)_s(\d+)\.log$", path)
            q = next((kv(x) for x in lines(path, "J2MECH")), None)
            a = next((kv(x) for x in lines(path, "J2SUMMARY") if " state=ALL " in x), None)
            if m and q: mech[float(m.group(1)), float(m.group(2)), int(m.group(3))] = (q, a)
        if mech:
            print("FV_MECH\tkappa\tv\tnseed\tJ2_deg\tF8mag\tF8ax\tF8tr\tDIRSWING_error_deg\tanchor_nm\tmeanEnergy_pNnm\tmaxEnergy_pNnm\tmaxTorque_pNnm")
            for k, v in sorted(set((k, v) for k, v, _ in mech)):
                q = [x for (kk, vv, _), x in mech.items() if kk == k and vv == v]
                mean = lambda idx, key: st.mean(float(x[idx][key]) for x in q if x[idx] is not None)
                print(f"FV_MECH\t{k:g}\t{v:g}\t{len(q)}\t{mean(1,'mean'):.6f}\t{mean(0,'meanF8mag_pN'):.6f}\t{mean(0,'meanF8ax_pN'):+.6f}\t{mean(0,'meanF8tr_pN'):.6f}\t{mean(0,'meanSwErr_deg'):.6f}\t{mean(0,'meanAnchor_nm'):.6f}\t{mean(0,'meanEnergy_pNnm'):.6f}\t{mean(0,'maxEnergy_pNnm'):.6f}\t{mean(0,'maxTorque_pNnm'):.6f}")


def kernel(root: str) -> None:
    print("\n[KERNEL_EPISODES]\nkappa\tv\tn\tlife_ms\tIpos\tIneg\tInet\treversal_ms\treversal_frac\trel_range_nm\trel_last_nm")
    for k in (0, 3):
        for v in (0, 8, 12, 14, 16, 18):
            ep = []
            for path in glob.glob(os.path.join(root, "kernel", f"k{k}_v{v}_s*.log")):
                ep.extend(kv(x) for x in lines(path, "EPROW"))
            if not ep: continue
            rev = [float(x["rev"])*.005 for x in ep if float(x["rev"]) >= 0]
            mean = lambda key: st.mean(float(x[key]) for x in ep)
            print(f"{k}\t{v}\t{len(ep)}\t{mean('T')*.005:.6f}\t{mean('Ipos'):+.6f}\t{mean('Ineg'):+.6f}\t{mean('Inet'):+.6f}\t{st.mean(rev) if rev else math.nan:.6f}\t{len(rev)/len(ep):.5f}\t{st.mean(float(x['relmax'])-float(x['relmin']) for x in ep):.6f}\t{mean('rellast'):+.6f}")
    print("KERNEL_PLATEAU\tkappa\tv\tnseed\tf8mag\tf8ax\tF8torque\tF9torque\tAXLOCKtorque\tDIRSWINGtorque\tJ2dev_deg\tJ2torque_proxy\tanchor_nm")
    for k in (0, 3):
        for v in (0, 8, 12, 14, 16, 18):
            q = []
            for path in glob.glob(os.path.join(root, "kernel", f"k{k}_v{v}_s*.log")):
                q.extend(kv(x) for x in lines(path, "PLATROW"))
            if not q: continue
            mean = lambda key: st.mean(float(x[key]) for x in q)
            jt = k*math.radians(mean("devJ2"))
            print(f"KERNEL_PLATEAU\t{k}\t{v}\t{len(q)}\t{mean('f8mag'):.6f}\t{mean('f8ax'):+.6f}\t{mean('TH'):.6f}\t{mean('T9'):.6f}\t{mean('hF10'):.6f}\t{mean('Tsw'):.6f}\t{mean('devJ2'):+.6f}\t{jt:+.6f}\t{mean('anchor'):.6f}")


def mm_fit(points: list[tuple[float, float]]) -> tuple[float, float, float]:
    best = (math.inf, math.nan, math.nan)
    for i in range(5001):
        half = math.exp(math.log(1.0) + i/5000*(math.log(1e6)-math.log(1.0)))
        f = [rho/(rho+half) for rho, _ in points]
        vinf = sum(fi*y for fi, (_, y) in zip(f, points))/sum(fi*fi for fi in f)
        sse = sum((y-vinf*fi)**2 for fi, (_, y) in zip(f, points))
        if sse < best[0]: best = (sse, vinf, half)
    return best[1], best[2], best[0]


def density(root: str) -> None:
    rows = {}
    mech = {}
    angle = {}
    coverage = {}
    for path in glob.glob(os.path.join(root, "density", "k*_d*_s*.log")):
        m = re.search(r"k([^_]+)_d([^_]+)_s(\d+)\.log$", path)
        if not m: continue
        key = float(m.group(1)), float(m.group(2)), int(m.group(3))
        g = next((kv(x) for x in lines(path, "  GRID_ROW")), None)
        j = next((kv(x) for x in lines(path, "J2MECH")), None)
        s = next((kv(x) for x in lines(path, "  STATS_STEADY_ROW")), None)
        c = next((kv(x) for x in lines(path, "  COV_ROW")), None)
        a = {}
        for line in lines(path, "J2SUMMARY"):
            q = kv(line)
            a[str(q["state"])] = q
        if g: rows[key] = (g, s)
        if j: mech[key] = j
        if a: angle[key] = a
        if c: coverage[key] = c
    if not rows: return
    print("\n[DENSITY]\nkappa\tdensity\tnseed\tvelocity\tCI95\tdirectionalCoherence\tavgBound\tmeanReach\tforcePerBound\tF8mag\tF8ax\tF8tr\tmaxF8\tmaxF8tr\tmeanJ2_deg\tDIRSWING_error_deg\tmeanAnchor_nm\tmeanJ2Energy\tmaxJ2Energy\tmaxJ2Torque\tmaxAnchor_nm\tcoverageOK")
    means = {}
    for k, rho in sorted(set((k, rho) for k, rho, _ in rows)):
        q = [(g, s, mech.get((kk, rr, seed)), angle.get((kk, rr, seed), {}),
              coverage.get((kk, rr, seed)))
             for (kk, rr, seed), (g, s) in rows.items() if kk == k and rr == rho]
        vel = [float(g["velFitX"]) for g, _, _, _, _ in q]; means[k, rho] = st.mean(vel)
        val = lambda source, key: st.mean(float(x[source][key]) for x in q if x[source] is not None)
        aval = lambda state, key: st.mean(float(x[3][state][key]) for x in q if state in x[3])
        coherence = st.mean(abs(float(x[0]["netX"]))/float(x[0]["netXY"])
                            for x in q if float(x[0]["netXY"]) > 0)
        ok = sum(1 for x in q if x[4] is not None and str(x[4].get("fullMat")) == "YES")
        print(f"{k:g}\t{rho:g}\t{len(q)}\t{st.mean(vel):+.6f}\t{ci95(vel):.6f}\t{coherence:.6f}\t{val(0,'avgBsteady'):.5f}\t{val(1,'meanReach'):.5f}\t{val(2,'meanFg_pN'):+.6f}\t{val(2,'meanF8mag_pN'):.6f}\t{val(2,'meanF8ax_pN'):+.6f}\t{val(2,'meanF8tr_pN'):.6f}\t{val(2,'maxF8_pN'):.6f}\t{val(2,'maxF8tr_pN'):.6f}\t{aval('ALL','mean'):.6f}\t{val(2,'meanSwErr_deg'):.6f}\t{val(2,'meanAnchor_nm'):.6f}\t{val(2,'meanEnergy_pNnm'):.6f}\t{val(2,'maxEnergy_pNnm'):.6f}\t{val(2,'maxTorque_pNnm'):.6f}\t{val(2,'maxAnchor_nm'):.6f}\t{ok}/{len(q)}")
    print("DENSITY_KINETICS\tkappa\tdensity\tdetachRatePerS\tdwell_ms\tduty")
    for k, rho in sorted(set((k, rho) for k, rho, _ in rows)):
        q = [s for (kk, rr, _), (_, s) in rows.items() if kk == k and rr == rho and s is not None]
        if q:
            print(f"DENSITY_KINETICS\t{k:g}\t{rho:g}\t{st.mean(float(x['detachRatePerS']) for x in q):.6f}\t{st.mean(float(x['dwellMs']) for x in q):.6f}\t{st.mean(float(x['duty']) for x in q):.6f}")
    print("DENSITY_ANGLE\tkappa\tdensity\tstate\tn\tmean_deg\tsd_deg\tmedian_deg\taxis_coherence")
    for k, rho in sorted(set((k, rho) for k, rho, _ in rows)):
        keys = [(kk, rr, seed) for kk, rr, seed in rows if kk == k and rr == rho]
        for state in ("ADPPi", "ADP", "ALL"):
            q = [angle[x][state] for x in keys if x in angle and state in angle[x]]
            if not q: continue
            n = sum(int(x["n"]) for x in q)
            mean = sum(int(x["n"])*float(x["mean"]) for x in q)/n
            var = sum(int(x["n"])*(float(x["sd"])**2+(float(x["mean"])-mean)**2) for x in q)/n
            print(f"DENSITY_ANGLE\t{k:g}\t{rho:g}\t{state}\t{n}\t{mean:.6f}\t{math.sqrt(var):.6f}\t{st.mean(float(x['median']) for x in q):.6f}\t{st.mean(float(x['axisCoherence']) for x in q):.6f}")
    print("DENSITY_PAIRED\tkappa\tdensity\tnpair\tdelta_velocity\tCI95")
    baseline = {(rho, seed): float(g["velFitX"])
                for (k, rho, seed), (g, _) in rows.items() if k == 0}
    for k in sorted(set(x[0] for x in rows) - {0.0}):
        for rho in sorted(set(x[1] for x in rows if x[0] == k)):
            delta = [float(g["velFitX"])-baseline[rho, seed]
                     for (kk, rr, seed), (g, _) in rows.items()
                     if kk == k and rr == rho and (rho, seed) in baseline]
            if delta:
                print(f"DENSITY_PAIRED\t{k:g}\t{rho:g}\t{len(delta)}\t{st.mean(delta):+.6f}\t{ci95(delta):.6f}")
    print("DENSITY_INCREMENT\tkappa\trho_lo\trho_hi\tdelta_velocity")
    for k in sorted(set(x[0] for x in means)):
        p = sorted((rho, v) for (kk, rho), v in means.items() if kk == k)
        for (r0, v0), (r1, v1) in zip(p, p[1:]):
            print(f"DENSITY_INCREMENT\t{k:g}\t{r0:g}\t{r1:g}\t{v1-v0:+.6f}")
    print("DENSITY_FIT\tkappa\tVinf\trho_half\tSSE_MM\tVinf_omit_high\trho_half_omit_high\tSSE_linear")
    for k in sorted(set(x[0] for x in means)):
        p = sorted((rho, v) for (kk, rho), v in means.items() if kk == k)
        vinf, half, sse = mm_fit(p); vo, ho, _ = mm_fit(p[:-1])
        mx, my = st.mean(x for x, _ in p), st.mean(y for _, y in p)
        slope = sum((x-mx)*(y-my) for x, y in p)/sum((x-mx)**2 for x, _ in p); intercept = my-slope*mx
        lin = sum((y-intercept-slope*x)**2 for x, y in p)
        print(f"DENSITY_FIT\t{k:g}\t{vinf:.6f}\t{half:.3f}\t{sse:.6f}\t{vo:.6f}\t{ho:.3f}\t{lin:.6f}")
    # The strong arm is fidelity-gated at rho=4000. Report the same domain for every arm rather than silently
    # comparing its fit with baseline/weak fits that include the rejected, coverage-violated rho=8000 point.
    print("DENSITY_FIT_MATCHED4000\tkappa\tVinf\trho_half\tSSE_MM\tSSE_linear")
    for k in sorted(set(x[0] for x in means)):
        p = sorted((rho, v) for (kk, rho), v in means.items() if kk == k and rho <= 4000)
        vinf, half, sse = mm_fit(p)
        mx, my = st.mean(x for x, _ in p), st.mean(y for _, y in p)
        slope = sum((x-mx)*(y-my) for x, y in p)/sum((x-mx)**2 for x, _ in p); intercept = my-slope*mx
        lin = sum((y-intercept-slope*x)**2 for x, y in p)
        print(f"DENSITY_FIT_MATCHED4000\t{k:g}\t{vinf:.6f}\t{half:.3f}\t{sse:.6f}\t{lin:.6f}")
    print("DENSITY_FIT_SEEDS\tkappa\tnseed\tVinf_mean\tVinf_CI95\trho_half_mean\trho_half_CI95")
    fits = {}
    for k in sorted(set(x[0] for x in rows)):
        fits[k] = []
        for seed in sorted(set(seed for kk, _, seed in rows if kk == k)):
            p = sorted((rho, float(g["velFitX"])) for (kk, rho, ss), (g, _) in rows.items()
                       if kk == k and ss == seed)
            if len(p) >= 3:
                fits[k].append(mm_fit(p)[:2])
        if fits[k]:
            vi = [x[0] for x in fits[k]]; rh = [x[1] for x in fits[k]]
            print(f"DENSITY_FIT_SEEDS\t{k:g}\t{len(vi)}\t{st.mean(vi):.6f}\t{ci95(vi):.6f}\t{st.mean(rh):.3f}\t{ci95(rh):.3f}")
    if 0.0 in fits:
        print("DENSITY_FIT_PAIRED\tkappa\tnpair\tdelta_Vinf\tdelta_Vinf_CI95\tdelta_rho_half\tdelta_rho_half_CI95")
        for k in sorted(set(fits)-{0.0}):
            q = [(a[0]-b[0], a[1]-b[1]) for a, b in zip(fits[k], fits[0.0])]
            if q:
                dv = [x[0] for x in q]; dr = [x[1] for x in q]
                print(f"DENSITY_FIT_PAIRED\t{k:g}\t{len(q)}\t{st.mean(dv):+.6f}\t{ci95(dv):.6f}\t{st.mean(dr):+.3f}\t{ci95(dr):.3f}")


def consistency(root: str) -> None:
    print("\n[CPU_GPU_CONSISTENCY]\trunner\tvelocity\tavgBound\tmeanReach\tforcePerBound\tF8mag\tF8ax\tF8tr\tJ2mean\tJ2sd\tJ2energy\tmaxJ2torque\tanchor_nm\tdetachRate\tdwell_ms")
    for runner in ("cpu", "gpu"):
        path = os.path.join(root, "consistency", f"{runner}.log")
        if not os.path.exists(path):
            continue
        grid = next((kv(x) for x in lines(path, "  GRID_ROW")), None)
        mech = next((kv(x) for x in lines(path, "J2MECH")), None)
        stats = next((kv(x) for x in lines(path, "  STATS_STEADY_ROW")), None)
        angle = next((kv(x) for x in lines(path, "J2SUMMARY") if " state=ALL " in x), None)
        if not all((grid, mech, stats, angle)):
            continue
        print(f"CPU_GPU_CONSISTENCY\t{runner}\t{float(grid['velFitX']):+.6f}\t"
              f"{float(grid['avgBsteady']):.6f}\t{float(stats['meanReach']):.6f}\t"
              f"{float(mech['meanFg_pN']):+.6f}\t{float(mech['meanF8mag_pN']):.6f}\t"
              f"{float(mech['meanF8ax_pN']):+.6f}\t{float(mech['meanF8tr_pN']):.6f}\t"
              f"{float(angle['mean']):.6f}\t{float(angle['sd']):.6f}\t"
              f"{float(mech['meanEnergy_pNnm']):.6f}\t{float(mech['maxTorque_pNnm']):.6f}\t"
              f"{float(mech['meanAnchor_nm']):.6f}\t{float(stats['detachRatePerS']):.6f}\t"
              f"{float(stats['dwellMs']):.6f}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="RUN_LOGS/j2_conformation")
    ap.add_argument("--section", choices=("all", "native", "single", "fv", "kernel", "density", "consistency"), default="all")
    args = ap.parse_args()
    if args.section in ("all", "native"): native(args.root); native_glide(args.root); energy_ladder()
    if args.section in ("all", "single"): single(args.root)
    if args.section in ("all", "fv"): fv(args.root)
    if args.section in ("all", "kernel"): kernel(args.root)
    if args.section in ("all", "density"): density(args.root)
    if args.section in ("all", "consistency"): consistency(args.root)


if __name__ == "__main__":
    main()
