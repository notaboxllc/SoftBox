#!/usr/bin/env python3
"""
FILAMENT HEIGHT FROM VIEWER FRAMES  (2026-10-07)

Reconstructs the filament's instantaneous height and tilt from a run's `-3js` frames, because the trajectory's
`zmin`/`zmax` columns are RUNNING EXTREMES since t=0 (SiteNormalLongGlideHarness), not the current height.

For every frame it reads the filament segment(s) (`segments[]` with motorSeg false), and reports:
  centre z, each end's axis z, tilt (deg from the plane), the lowest and highest SURFACE point
  (end z -/+ R*sqrt(1-uz^2)), their clearance above the floor / below the ceiling, and the bound-head count.
Walls come from the frame's `bounds.zMin/zMax` (frames written since 2026-10-06); for older frames pass
`--floor-nm` (and optionally `--ceiling-nm`). Heights are in nm. A wall is "in contact" when the surface is
within --contact-nm (default 0.5 nm) of it.

Usage:
  scripts/filament_height.py <run_dir or frames_dir> [--stride N] [--floor-nm Z] [--ceiling-nm Z]
                             [--windows 0,0.05,0.25,0.5,1,2,3.5] [--workers 6] [--no-tsv]
Writes <run_dir>/filament_height.tsv (one row per sampled frame) unless --no-tsv.
"""
import argparse, glob, json, math, os, statistics as st, sys
from multiprocessing import Pool


def frames_dir(path):
    for cand in (path, os.path.join(path, 'simviewer', 'longrun')):
        if glob.glob(os.path.join(cand, 'frame_*.json')):
            return cand
    sys.exit(f'no frame_*.json under {path}')


def read(fn):
    d = json.load(open(fn))
    segs = [s for s in d['segments'] if not s.get('motorSeg')]
    b = d.get('bounds', {})
    # whole-filament ends: the end1 of the first segment and end2 of the last (rigid rod: one segment)
    e1, e2 = segs[0]['end1'], segs[-1]['end2']
    R = segs[0].get('r', 0.0035)
    lo_s = hi_s = None; zs = []
    for s in segs:
        u = [s['end2'][k] - s['end1'][k] for k in range(3)]
        L = math.sqrt(sum(c * c for c in u)) or 1.0
        rr = R * math.sqrt(max(0.0, 1 - (u[2] / L) ** 2))
        for e in (s['end1'], s['end2']):
            lo = e[2] - rr; hi = e[2] + rr
            lo_s = lo if lo_s is None else min(lo_s, lo)
            hi_s = hi if hi_s is None else max(hi_s, hi)
        zs.append(0.5 * (s['end1'][2] + s['end2'][2]))
    dx = [e2[k] - e1[k] for k in range(3)]
    horiz = math.hypot(dx[0], dx[1])
    tilt = math.degrees(math.atan2(dx[2], horiz)) if horiz > 0 else 90.0
    nb = sum(1 for m in d.get('myosins', []) if m.get('bound'))
    return (d['t'], 1000 * st.mean(zs), 1000 * e1[2], 1000 * e2[2], tilt, 1000 * lo_s, 1000 * hi_s, nb,
            b.get('zMin'), b.get('zMax'))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('run')
    ap.add_argument('--stride', type=int, default=1)
    ap.add_argument('--floor-nm', type=float)
    ap.add_argument('--ceiling-nm', type=float)
    ap.add_argument('--contact-nm', type=float, default=0.5)
    ap.add_argument('--windows', default='0,0.05,0.25,0.5,1,1.5,2,2.5,3,3.5')
    ap.add_argument('--workers', type=int, default=6)
    ap.add_argument('--no-tsv', action='store_true')
    a = ap.parse_args()
    fd = frames_dir(a.run)
    fs = sorted(glob.glob(os.path.join(fd, 'frame_*.json')))[::a.stride]
    with Pool(a.workers) as p:
        rows = p.map(read, fs, chunksize=8)
    zmin_b, zmax_b = rows[0][8], rows[0][9]
    floor = a.floor_nm if a.floor_nm is not None else (1000 * zmin_b if zmin_b is not None else None)
    ceil = a.ceiling_nm if a.ceiling_nm is not None else (1000 * zmax_b if zmax_b is not None else None)
    out = []
    for (t, zc, z1, z2, tilt, lo, hi, nb, _, _) in rows:
        clr_lo = lo - floor if floor is not None else float('nan')
        clr_hi = ceil - hi if ceil is not None else float('nan')
        out.append((t, zc, z1, z2, tilt, lo, hi, clr_lo, clr_hi, nb,
                    int(floor is not None and clr_lo <= a.contact_nm), int(ceil is not None and clr_hi <= a.contact_nm)))
    if not a.no_tsv:
        rd = a.run if not a.run.rstrip('/').endswith('longrun') else os.path.dirname(os.path.dirname(a.run.rstrip('/')))
        path = os.path.join(rd, 'filament_height.tsv')
        with open(path, 'w') as f:
            f.write('t_s\tcentre_z_nm\tend1_z_nm\tend2_z_nm\ttilt_deg\tlowest_surface_nm\thighest_surface_nm'
                    '\tclearance_floor_nm\tclearance_ceiling_nm\tbound_heads\tfloor_contact\tceiling_contact\n')
            for r in out:
                f.write('\t'.join(f'{v:.6g}' if isinstance(v, float) else str(v) for v in r) + '\n')
        print(f'wrote {path} ({len(out)} frames)')
    print(f'walls: floor {floor if floor is None else round(floor, 2)} nm, ceiling {ceil if ceil is None else round(ceil, 2)} nm'
          + ('' if floor is not None else '  (no bounds.zMin in frames: pass --floor-nm for clearances)'))
    w = [float(x) for x in a.windows.split(',')]
    print('window (s)      frames  centre z mean (SD)   clearance above floor mean (min)   |tilt| mean (max)   floor contact  ceiling contact  heads')
    for lo_t, hi_t in zip(w[:-1], w[1:]):
        s = [r for r in out if lo_t <= r[0] < hi_t]
        if len(s) < 2:
            continue
        cz = [r[1] for r in s]; cl = [r[7] for r in s]; tl = [abs(r[4]) for r in s]
        print(f'{lo_t:5.2f}-{hi_t:<5.2f}    {len(s):6d}  {st.mean(cz):+7.1f} ({st.pstdev(cz):5.1f})     '
              f'{st.mean(cl):7.1f} ({min(cl):6.1f})                    {st.mean(tl):5.2f} ({max(tl):5.2f})       '
              f'{100 * st.mean(r[10] for r in s):5.1f} %        {100 * st.mean(r[11] for r in s):5.1f} %     {st.mean(r[9] for r in s):5.1f}')


if __name__ == '__main__':
    main()
