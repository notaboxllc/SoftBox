#!/usr/bin/env python3
"""Bite-test readout: the material head-patch basis fix + converter azimuth 0/45/90.

Reads each arm's trajectory_summary.csv (last completed row) and its log's closing block.
Reports the quantities a BITE test can actually resolve at 20k steps (n=1, single seed):
engagement (avgBound, captures, residence), health (invalid/solverFail/force excursions),
and the roll channel (turns/um) -- with displacement shown but NOT read as a velocity."""
import csv, os, re, sys, math

OUT = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/motor_audit/campaigns_2026-09/PATCHBASIS_BITE"
ARMS = [("caz00lab", "alpha=0  LEGACY lab patch basis", 0.0),
        ("caz00",    "alpha=0  material basis (FIX)",   0.0),
        ("caz45",    "alpha=45 material basis",        45.0),
        ("caz90",    "alpha=90 material basis",        90.0)]

def traj(name):
    f = os.path.join(OUT, name, "trajectory_summary.csv")
    if not os.path.exists(f): return None
    rows = list(csv.DictReader(open(f), delimiter='\t'))
    return rows[-1] if rows else None

def logblock(name):
    f = os.path.join(OUT, name + ".log")
    if not os.path.exists(f): return {}
    txt = open(f, errors='replace').read()
    g = {}
    for key, pat in (("avgBound",   r"avgBound = ([-\d.]+)"),
                     ("captures",   r"captures = (\d+)"),
                     ("detach",     r"detachments = (\d+)"),
                     ("strokes",    r"strokes = (\d+)"),
                     ("resid_us",   r"mean residence = [\d.]+ steps \(([\d.]+) us\)"),
                     ("fax_pN",     r"mean axial force per bound head = ([-\d.]+) pN"),
                     ("turns_um",   r"turns/um = ([-\d.]+)"),
                     ("turns",      r"net roll = [-\d.]+ rad \(([-\d.]+) turns\)"),
                     ("invalid",    r"invalid = (\d+)"),
                     ("solverFail", r"solverFail = (\d+)"),
                     ("peak_pN",    r"peak ([\d.]+) pN at step"),
                     ("exc12",      r"steps >12pN = (\d+)"),
                     ("vfit",       r"full-run LS velocity = ([-+\d.]+) um/s"),
                     ("fwd",        r"net forward = ([-+\d.]+) um"),
                     ("class",      r"CLASS = (\w)")):
        m = re.search(pat, txt)
        if m: g[key] = m.group(1)
    g["running"] = "STOP=" not in txt
    return g

print(f"\nBITE TEST  {OUT}   (n=1 per arm, single seed -- engagement/health only; v is NOT resolved at 20k steps)\n")
hdr = f"{'arm':<10}{'alpha':>6}{'arm nm':>8}{'avgB':>7}{'capt':>6}{'resid us':>10}{'fax pN':>8}{'turns/um':>10}{'fwd um':>9}{'peak pN':>9}{'inv/fail':>10}  note"
print(hdr); print("-"*len(hdr))
rows = {}
for name, label, alpha in ARMS:
    g = logblock(name); t = traj(name)
    if not g:
        print(f"{name:<10}{alpha:>6.0f}  -- not started --"); continue
    if g.get("running"):
        step = t["step"] if t else "0"
        print(f"{name:<10}{alpha:>6.0f}{7.0*math.cos(math.radians(alpha)/2):>8.2f}   (running, step {step})")
        continue
    rows[name] = g
    print(f"{name:<10}{alpha:>6.0f}{7.0*math.cos(math.radians(alpha)/2):>8.2f}"
          f"{float(g.get('avgBound',0)):>7.3f}{int(g.get('captures',0)):>6d}{float(g.get('resid_us',0)):>10.1f}"
          f"{float(g.get('fax_pN',0)):>8.2f}{float(g.get('turns_um',0)):>10.2f}{float(g.get('fwd',0)):>9.4f}"
          f"{float(g.get('peak_pN',0)):>9.2f}{g.get('invalid','?')+'/'+g.get('solverFail','?'):>10}  {label}")

a, b = rows.get("caz00lab"), rows.get("caz00")
if a and b:
    print("\nPATCH-BASIS FIX, read at alpha=0 (legacy lab -> material):")
    for k, lab in (("avgBound","avgBound"),("captures","captures"),("resid_us","residence us"),
                   ("turns_um","turns/um"),("fax_pN","axial force pN")):
        x, y = float(a.get(k,0)), float(b.get(k,0))
        r = (y/x) if x else float('nan')
        print(f"   {lab:<14} {x:>9.3f} -> {y:>9.3f}   ratio {r:>6.3f}")
    print("   (the prediction the fix makes: the legacy basis BRAKES roll -- a head binding after the filament")
    print("    has rolled starts frustrated -- so |turns/um| should be >= legacy once roll has accumulated.")
    print("    At 20k steps the accumulated roll is small, so a null here is UNDERPOWERED, not a refutation.)")
