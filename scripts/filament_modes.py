#!/usr/bin/env python3
"""Filament bending stiffness from viewer frames (Gittes, Mickey, Nettleton & Howard 1993, J Cell Biol 120:923).

usage: filament_modes.py [-skip T_s] [-nmax N] DIR [DIR ...]   (DIR = a -3js frame directory)

For each frame the chain (non-motor segments, ordered by id) is projected on the lawn plane (x,y). The in-plane tangent
angle theta(s) of each segment is decomposed into cosine modes, a_n = sqrt(2/L) * sum_k theta_k cos(n pi s_k / L) b.
For a thermal worm-like chain, var(a_n) = (L/n pi)^2 / Lp (free ends, 2D projection of small 3D fluctuations), so
Lp_n = q_n^-2 / var(a_n) is flat in n; q_n^-1 uses the discrete-chain form b / (2 sin(n pi b / 2L)). Motor-driven bending is not thermal: there Lp_n is an "apparent" stiffness, and a dip
in a band of n shows where the active bending lives.

Also reported: joint angles (total rms -> Lp_joint = 2b/<theta^2>, 3D, segment scale), and the lag-1 correlation of
signed in-plane joint angles (0 for a discrete WLC; positive = bends spread over neighbouring joints = smooth arcs;
negative = zig-zag). Frames are time-correlated: n_eff is a crude per-mode effective sample count from the lag-1
autocorrelation of a_n, used for the +/- on Lp_n.
"""
import glob, json, math, sys

def load(d, skip):
    fs = sorted(glob.glob(d.rstrip('/') + '/frame_*.json'))
    out = []
    for f in fs:
        j = json.load(open(f))
        if j.get('time', j.get('t', 1e9)) < skip:
            continue
        seg = sorted([s for s in j['segments'] if not s.get('motorSeg')], key=lambda s: s['id'])
        out.append(seg)
    return out

def stats(x):
    n = len(x); m = sum(x) / n
    v = sum((t - m) ** 2 for t in x) / max(n - 1, 1)
    r1 = 0.0
    if n > 2 and v > 0:
        r1 = sum((x[i] - m) * (x[i + 1] - m) for i in range(n - 1)) / ((n - 1) * v)
    r1 = max(0.0, min(r1, 0.95))
    return m, v, n * (1 - r1) / (1 + r1)

def analyse(d, skip, nmax):
    frames = load(d, skip)
    if not frames:
        print(f"{d}: no frames"); return
    N = len(frames[0]); A = {n: [] for n in range(1, nmax + 1)}
    j3 = []; jip = []; lag = [0.0, 0.0]
    for seg in frames:
        u = []
        for s in seg:
            v = [q - p for p, q in zip(s['end1'], s['end2'])]
            L = math.sqrt(sum(c * c for c in v)); u.append([c / L for c in v])
        b = sum(math.dist(s['end1'], s['end2']) for s in seg) / N
        Lc = N * b
        # in-plane tangent angles, unwrapped, relative to their mean
        th = [math.atan2(t[1], t[0]) for t in u]
        for k in range(1, N):
            while th[k] - th[k - 1] > math.pi: th[k] -= 2 * math.pi
            while th[k] - th[k - 1] < -math.pi: th[k] += 2 * math.pi
        mth = sum(th) / N; th = [t - mth for t in th]
        for n in A:
            A[n].append(math.sqrt(2 / Lc) * sum(th[k] * math.cos(n * math.pi * (k + 0.5) * b / Lc) * b for k in range(N)))
        d_ip = [th[k + 1] - th[k] for k in range(N - 1)]
        jip += d_ip
        for k in range(len(d_ip) - 1):
            lag[0] += d_ip[k] * d_ip[k + 1]; lag[1] += d_ip[k] ** 2
        for k in range(N - 1):
            c = max(-1.0, min(1.0, sum(x * y for x, y in zip(u[k], u[k + 1]))))
            j3.append(math.acos(c))
    r3 = math.sqrt(sum(t * t for t in j3) / len(j3)); rip = math.sqrt(sum(t * t for t in jip) / len(jip))
    j3s = sorted(j3)
    print(f"\n{d.rstrip('/').split('/')[-1]}: {len(frames)} frames, {N} segments, b {b*1000:.1f} nm, contour {Lc:.3f} um")
    print(f"  joints: 3D rms {math.degrees(r3):.1f} deg (p95 {math.degrees(j3s[int(.95*len(j3s))]):.1f}, max {math.degrees(j3s[-1]):.1f}, "
          f">30 deg {100*sum(t>math.radians(30) for t in j3)/len(j3):.1f} %)  -> Lp_joint {2*b/r3**2:.1f} um ; "
          f"in-plane rms {math.degrees(rip):.1f} deg -> Lp {b/rip**2:.1f} um ; lag-1 corr {lag[0]/lag[1]:+.2f}")
    print("  mode n :" + "".join(f"{n:>9d}" for n in A))
    row_lp = []; row_e = []; row_ne = []
    for n in A:
        m, v, ne = stats(A[n])
        q = b / (2 * math.sin(n * math.pi * b / (2 * Lc)))
        lp = q * q / v if v > 0 else float('inf')
        row_lp.append(lp); row_e.append(lp * math.sqrt(2 / max(ne - 1, 1))); row_ne.append(ne)
    print("  Lp_n um:" + "".join(f"{x:9.1f}" for x in row_lp))
    print("  +/-    :" + "".join(f"{x:9.1f}" for x in row_e))
    print("  n_eff  :" + "".join(f"{x:9.0f}" for x in row_ne))

if __name__ == '__main__':
    a = sys.argv[1:]; skip = 0.0; nmax = 10
    while a and a[0].startswith('-'):
        if a[0] == '-skip': skip = float(a[1]); a = a[2:]
        elif a[0] == '-nmax': nmax = int(a[1]); a = a[2:]
        else: sys.exit(__doc__)
    for d in a:
        analyse(d, skip, nmax)
