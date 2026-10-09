#!/usr/bin/env python3
"""
ATTACHMENT-EPISODE FORCE BALANCE (2026-10-08) -- reads <run>/episodes.tsv written by SiteNormalLongGlideHarness -episodes.

Detachment-limited gliding predicts that, at steady speed, each head's axial force averaged over its whole attachment is
~zero (it pushes after its stroke, then is dragged until ATP releases it). This script asks WHICH heads are net drag:
it splits the total axial IMPULSE (mean force x duration; + = pushing the filament forward, - = dragging) by
  - stroke-axis orientation at binding, cos(stroke, barbed)   (sideways strokers stroke weakly but drag fully?)
  - S2 orientation, cos(S2 chord E->P, barbed)               (does the tether take up drag or stroke?)
  - nucleotide state (pre-stroke ADP.Pi, post-stroke ADP, rigor)
Usage: scripts/episode_forces.py <run_dir> [--tmin S]   (episodes that BEGIN before tmin are skipped: start transient)
"""
import argparse, csv, os, statistics as st

ap = argparse.ArgumentParser(); ap.add_argument('run'); ap.add_argument('--tmin', type=float, default=0.0)
a = ap.parse_args()
rows = list(csv.DictReader(open(os.path.join(a.run, 'episodes.tsv')), delimiter='\t'))
rows = [r for r in rows if float(r['t_bind_s']) >= a.tmin]
if not rows: raise SystemExit('no episodes')

def f(x): return None if x == 'NA' else float(x)
tot = sum(float(r['meanF_pN']) * float(r['dur_s']) for r in rows)
dur = sum(float(r['dur_s']) for r in rows)
print(f'{len(rows)} episodes (t_bind >= {a.tmin} s), {sum(int(r["censored"]) for r in rows)} still bound at the end')
print(f'total bound time {dur:.4f} head-s; net axial impulse {tot*1e3:+.3f} pN.ms; time-averaged force per bound head {tot/dur:+.3f} pN')
print(f'mean episode {1e3*dur/len(rows):.2f} ms; fraction of episodes net-negative {sum(1 for r in rows if float(r["meanF_pN"])<0)/len(rows):.2f}')

# per state: impulse = mean force in state x (fraction of episode in state) x duration
for key, frac in (('meanF_ADPPi_pN', 'frac_ADPPi'), ('meanF_ADP_pN', 'frac_ADP'), ('meanF_rigor_pN', 'frac_rigor')):
    imp = sum((f(r[key]) or 0.0) * float(r[frac]) * float(r['dur_s']) for r in rows)
    tim = sum(float(r[frac]) * float(r['dur_s']) for r in rows)
    print(f'  {key[6:-3]:6s}: {100*tim/dur:5.1f} % of bound time, impulse {imp*1e3:+8.3f} pN.ms, mean force {imp/max(tim,1e-30):+.3f} pN')

def table(col, edges, label):
    print(f'\nby {label}:')
    print('  bin              episodes  bound-time%  impulse pN.ms  mean F pN   mean dur ms')
    for lo, hi in zip(edges[:-1], edges[1:]):
        s = [r for r in rows if lo <= float(r[col]) < hi or (hi == edges[-1] and float(r[col]) == hi)]
        if not s: continue
        d = sum(float(r['dur_s']) for r in s); imp = sum(float(r['meanF_pN']) * float(r['dur_s']) for r in s)
        print(f'  [{lo:+.2f},{hi:+.2f})   {len(s):8d}  {100*d/dur:10.1f}  {imp*1e3:+12.3f}  {imp/d:+9.3f}  {1e3*d/len(s):9.2f}')

table('cos_stroke', [-1.0, 0.0, 0.25, 0.5, 0.75, 0.9, 1.0001], 'cos(stroke axis, barbed) at binding')
table('cos_S2', [-1.0, -0.5, 0.0, 0.5, 1.0001], 'cos(S2 chord, barbed) at binding')
