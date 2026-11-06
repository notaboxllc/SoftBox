#!/usr/bin/env python3
"""Capture target-zone width and attachment rate from SiteNormalLongGlideHarness viewer frames.

Usage: capture_zone_rate.py <frame_dir> <run_dir> [label]

Reads only existing outputs (frames + run_config.json + trajectory_summary.csv). Per frame it takes
every bound motor's F8 point (frame entry id N+m, which sits on the bound actin site) and its S2 base
(node 0 of its beam). theta = signed azimuth of the site about the local filament axis u (pointed->barbed),
measured from the coverslip direction (-z), right-hand positive about u.

  zone     : theta of bound sites (occupancy) and of NEW captures (bound now, not in the previous frame)
  k_A(r)   : captures per unbound motor-second, binned by the base's lateral offset r from the filament axis.
             unbound motor-time in a bin = density * L_fil * bin_width * dt - bound motors in that bin.
             Frame-detected captures miss episodes shorter than one frame interval, so k_A is also given
             scaled to the exact capture count from the trajectory (column 'captures').
"""
import json, os, sys, glob, math
import numpy as np

fdir, rdir = sys.argv[1], sys.argv[2]
label = sys.argv[3] if len(sys.argv) > 3 else os.path.basename(rdir.rstrip('/'))
cfg = json.load(open(os.path.join(rdir, 'run_config.json')))
N = int(cfg['n_motors']); rho = float(cfg['density_heads_per_um2'])
nfil = int(cfg.get('fil_segments', 24))

files = sorted(glob.glob(os.path.join(fdir, 'frame_*.json')))
def axis_frame(fil, p):
    """closest filament segment to point p: returns (foot, u, dist2D-signed-side inputs)."""
    best = None
    for s in fil:
        a = np.array(s['end1']); b = np.array(s['end2']); u = b - a; L = np.linalg.norm(u); u /= L
        t = np.clip(np.dot(p - a, u), 0, L); foot = a + t * u; d = np.linalg.norm(p - foot)
        if best is None or d < best[0]: best = (d, foot, u)
    return best[1], best[2]

def theta_of(r, u):
    ref = np.array([0, 0, -1.0]); ref = ref - np.dot(ref, u) * u; ref /= np.linalg.norm(ref)
    r = r - np.dot(r, u) * u
    return math.atan2(np.dot(np.cross(ref, r), u), np.dot(ref, r))

def lateral(fil, base):
    """signed in-plane distance of a base from the filament axis projection (nm); + = left of u (z up)."""
    best = None
    for s in fil:
        a = np.array(s['end1'][:2]); b = np.array(s['end2'][:2]); u = b - a; L = np.linalg.norm(u); u /= L
        t = np.clip(np.dot(base[:2] - a, u), 0, L); d = base[:2] - (a + t * u)
        side = np.sign(u[0] * d[1] - u[1] * d[0]); dist = np.linalg.norm(d)
        if best is None or dist < best[0]: best = (dist, side * dist)
    return best[1] * 1e3

occ_th, occ_r, cap_th, cap_r = [], [], [], []
prev = None; times = []; Lfil = None; nb_series = []
for fn in files:
    j = json.load(open(fn)); times.append(j['t'])
    fil = [s for s in j['segments'] if not s['motorSeg']][:nfil]
    beams = [s for s in j['segments'] if s['motorSeg'] and abs(s['r'] - 0.0009) < 1e-6]
    art = [m for m in j['myosins'] if m['id'] < N and m['motor']['state'] != 'anchor']
    M = len(beams) // max(1, len(art))
    if Lfil is None:
        Lfil = sum(np.linalg.norm(np.array(s['end2']) - np.array(s['end1'])) for s in fil)
    f8 = {m['id'] - N: np.array(m['motor']['end1']) for m in j['myosins'] if m['id'] >= N}
    bound = {}
    for i, m in enumerate(art):
        if not m['bound']: continue
        p = f8[m['id']]; foot, u = axis_frame(fil, p)
        base = np.array(beams[i * M]['end1'])
        bound[m['id']] = (theta_of(p - foot, u), lateral(fil, base))
    for th, r in bound.values(): occ_th.append(th); occ_r.append(r)
    if prev is not None:
        for mid in bound.keys() - prev.keys():
            cap_th.append(bound[mid][0]); cap_r.append(bound[mid][1])
    nb_series.append(len(bound)); prev = bound

occ_th = np.array(occ_th); cap_th = np.array(cap_th); occ_r = np.array(occ_r); cap_r = np.array(cap_r)
T = times[-1] - times[0]; dtf = T / (len(times) - 1)
# exact captures over the same interval, from the trajectory
tr = np.genfromtxt(os.path.join(rdir, 'trajectory_summary.csv'), names=True, delimiter='\t')
sel = (tr['t_s'] >= times[0] - 1e-9) & (tr['t_s'] <= times[-1] + 1e-9)
exact = tr['captures'][sel][-1] - tr['captures'][sel][0] if sel.sum() > 1 else float('nan')
scale = exact / max(1, len(cap_th))

def zone(th):
    a = np.degrees(np.abs(th))
    return (np.mean(a < 60), np.mean((a >= 60) & (a <= 120)), np.mean(a > 120))
def circ_sd(th):
    R = np.hypot(np.mean(np.cos(th)), np.mean(np.sin(th))); return math.degrees(math.sqrt(-2 * math.log(R)))

print(f"=== {label}: {len(files)} frames, t {times[0]:.4f}-{times[-1]:.4f} s (frame dt {dtf*1e3:.3f} ms), "
      f"L_fil {Lfil:.3f} um, rho {rho:.0f}/um^2")
for nm, th in (('bound occupancy', occ_th), ('new captures', cap_th)):
    lo, sd, up = zone(th)
    mean = math.degrees(math.atan2(np.mean(np.sin(th)), np.mean(np.cos(th))))
    se = math.degrees(np.std(th) / math.sqrt(len(th)))
    print(f"  {nm:16s} n={len(th):6d}  lower/side/upper = {lo:.3f}/{sd:.3f}/{up:.3f}  circular sd {circ_sd(th):5.1f} deg"
          f"  mean theta {mean:+6.2f} deg (naive se {se:.2f})")
h, e = np.histogram(np.degrees(cap_th), bins=12, range=(-180, 180))
print("  capture theta histogram (30 deg bins from -180):", ' '.join(str(x) for x in h))
print(f"  captures: frame-detected {len(cap_th)}, exact {exact:.0f} (scale {scale:.2f}); mean bound {np.mean(nb_series):.1f}")
bins = np.arange(-80, 81, 10)
print("  base lateral offset r (nm) | captures | bound frac | unbound motor-s | k_A detected /s | k_A exact-scaled /s")
for a, b in zip(bins[:-1], bins[1:]):
    nc = np.sum((cap_r >= a) & (cap_r < b))
    nbound = np.sum((occ_r >= a) & (occ_r < b)) * dtf                     # bound motor-seconds
    tot = rho * Lfil * (b - a) * 1e-3 * T                                  # motor-seconds of lawn in the strip
    unb = tot - nbound
    print(f"   [{a:+4d},{b:+4d})  {nc:6d}   {nbound/tot:6.3f}   {unb:8.3f}   {nc/unb:9.1f}   {nc*scale/unb:9.1f}")
inner = (np.abs(cap_r) < 30); innerb = (np.abs(occ_r) < 30)
tot = rho * Lfil * 60e-3 * T; unb = tot - innerb.sum() * dtf
print(f"  |r|<30 nm: k_A = {inner.sum()*scale/unb:.1f} /s per unbound motor (exact-scaled); bound fraction {innerb.sum()*dtf/tot:.3f}")
