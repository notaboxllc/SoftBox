#!/usr/bin/env python3
"""Filament height check for site-normal gliding runs (jba 2026-10-29: report height in EVERY gliding analysis;
a run can fail to glide for height alone).

Reads <run>/trajectory_summary.csv (z_mean_nm = window-mean ABSOLUTE filament axis z; the lawn offset is recovered from the log; plus floor/ceiling
contact fractions) and prints height percentiles next to the run's net forward displacement and avgBound.
Works on runs from before the in-harness FILAMENT HEIGHT summary line existed.

The default 12-32 nm band (axis above lawn) brackets where the d800 rate-capture motor glided in the 2026-10-28 clamp scan (lawn02). It is a
prompt to look, not a model constant; change it with --band lo,hi when the motor or scene changes.

usage: glide_height.py [--burn 0.005] [--band 12,32] RUN_DIR [RUN_DIR ...]
"""
import csv, re, sys, os

def lawn_offset(d, rows):
    """z_mean_nm is ABSOLUTE z; the lawn plane (mean S2 emergence) is not in run_config. Recover it by pairing the log's
    'filament axis z X nm above lawn' lines with the trajectory z_now_nm at the preceding step line."""
    log = d + '.log'
    if not os.path.exists(log): return None
    zn = {int(x['step']): float(x['z_now_nm']) for x in rows if x.get('z_now_nm') not in (None, '', 'NaN')}
    L = open(log, errors='replace').read().splitlines(); offs = []
    for i, l in enumerate(L):
        m = re.search(r'filament axis z ([-\d.]+) nm above lawn', l)
        if not m: continue
        for j in range(i - 1, max(0, i - 4), -1):
            s = re.match(r'\s+step\s+(\d+)', L[j])
            if s and int(s.group(1)) in zn: offs.append(float(m.group(1)) - zn[int(s.group(1))]); break
    if not offs: return None
    offs.sort(); return offs[len(offs) // 2]

def main(argv):
    burn, lo, hi, runs = 0.005, 12.0, 32.0, []
    i = 0
    while i < len(argv):
        if argv[i] == '--burn': burn = float(argv[i + 1]); i += 2
        elif argv[i] == '--band': lo, hi = map(float, argv[i + 1].split(',')); i += 2
        else: runs.append(argv[i].rstrip('/')); i += 1
    print("%-36s %5s %7s  height p10/p50/p90 (nm)  in-band  floor  ceil   fwd_um  avgB" % ("run", "rows", "t_end"))
    for d in runs:
        try:
            r = list(csv.DictReader(open(d + '/trajectory_summary.csv'), delimiter='\t'))
        except OSError as e:
            print("%-36s %s" % (d, e)); continue
        if not r or 'z_mean_nm' not in r[0]:
            print("%-36s no z_mean_nm column (pre-height-tracking run)" % d); continue
        w = [x for x in r if float(x['t_s']) >= burn and x['z_mean_nm'] not in ('', 'NaN')]
        if not w:
            print("%-36s no rows after burn-in %.4f s" % (d, burn)); continue
        off = lawn_offset(d, r)
        if off is None:
            print("%-36s cannot recover lawn plane from %s.log (no 'above lawn' lines)" % (d, d)); continue
        z = sorted(float(x['z_mean_nm']) + off for x in w); n = len(z)
        inb = sum(lo <= v < hi for v in z) / n
        fl = sum(float(x['floor_contact_frac']) for x in w) / n
        ce = sum(float(x['ceiling_contact_frac']) for x in w) / n
        last = r[-1]
        flag = []
        if not (lo <= z[n // 2] < hi): flag.append("MEDIAN OUTSIDE BAND")
        if ce > 0.01: flag.append("ON CEILING")
        print("%-36s %5d %7.4f  %6.1f %6.1f %6.1f          %5.1f%% %5.1f%% %5.1f%% %+7.3f %5.2f  %s" % (
            d.split('/')[-1], n, float(last['t_s']), z[n // 10], z[n // 2], z[(9 * n) // 10],
            100 * inb, 100 * fl, 100 * ce, float(last['fwd_um']), float(last['avgBound']), " ".join(flag)))
    print("(height = window-mean axis height above the lawn plane, recovered per run from the log; band %.0f-%.0f nm; rows after t >= %.4f s)" % (lo, hi, burn))

if __name__ == '__main__':
    main(sys.argv[1:])
