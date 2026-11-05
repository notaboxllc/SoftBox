#!/usr/bin/env python3
"""Stitch a SiteNormal run that was resumed one or more times (scripts/park_queue.sh layout).

<run>/seg_01, seg_02, ... hold the earlier segments' trajectory_summary.csv; seg_k/checkpoint_at_resume.bin is the
checkpoint segment k+1 resumed from. -resume resets the roll accumulator (and avgBound/captures/...); fwd_um is
position-based and continuous. This keeps each segment's rows up to the next segment's start step and offsets
rollRad/rollTurns so roll is cumulative from t = 0.
usage: stitch_resume.py <run_dir> [pre_segment_dir ...]   (extra dirs = segments that predate seg_01, oldest first)
writes <run_dir>/trajectory_stitched.csv and prints whole-run fwd, roll, turns/um.
"""
import csv, struct, sys, pathlib
import numpy as np

run = pathlib.Path(sys.argv[1])
segs = [pathlib.Path(p) for p in sys.argv[2:]] + sorted(p for p in run.glob('seg_[0-9][0-9]') if p.is_dir())
def rows(p): return list(csv.DictReader(open(p / 'trajectory_summary.csv'), delimiter='\t'))
def ckstep(p):
    f = p / 'checkpoint_at_resume.bin'
    return struct.unpack('>i', open(f, 'rb').read(4))[0] if f.exists() else None
parts = [(s, rows(s)) for s in segs if (s / 'trajectory_summary.csv').exists()] + [(run, rows(run))]
out, off = [], 0.0
for i, (p, r) in enumerate(parts):
    nxt = parts[i+1][1][0] if i + 1 < len(parts) else None
    end = ckstep(p) if ckstep(p) is not None else (int(nxt['step']) if nxt else None)
    if end is None and nxt is not None: end = int(nxt['step'])
    st = np.array([int(x['step']) for x in r]); ro = np.array([float(x['rollRad']) for x in r])
    keep = [x for x in r if end is None or int(x['step']) <= end]
    for x in keep:
        x = dict(x); x['rollRad'] = '%.6f' % (float(x['rollRad']) + off); x['rollTurns'] = '%.6f' % (float(x['rollRad']) / (2*np.pi))
        x['segment'] = str(i); out.append(x)
    if end is not None and len(st) > 1:
        off += float(np.interp(end, st, ro)) if end <= st[-1] else float(ro[-1])
cols = list(out[0].keys())
with open(run / 'trajectory_stitched.csv', 'w', newline='') as f:
    w = csv.DictWriter(f, fieldnames=cols, delimiter='\t'); w.writeheader(); w.writerows(out)
t = np.array([float(x['t_s']) for x in out]); fw = np.array([float(x['fwd_um']) for x in out]); rr = np.array([float(x['rollRad']) for x in out])
print('%s: %d segments, t %.4f..%.4f s, fwd %+.4f um, roll %+.3f turns, turns/um %+.3f, LS v %.3f um/s'
      % (run.name, len(parts), t[0], t[-1], fw[-1], rr[-1]/(2*np.pi), (rr[-1]/(2*np.pi))/fw[-1] if fw[-1] else float('nan'),
         np.polyfit(t, fw, 1)[0]))
