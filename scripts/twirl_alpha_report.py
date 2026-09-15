#!/usr/bin/env python3
"""Read the eps=0 alpha-twirl arms AS THEY RUN: accumulated turns + drift-vs-diffusion z.

z = net roll / (sd(per-row dRoll) * sqrt(N)) -- net roll over a window is NOT a rotation rate, so an arm is
only twirling if its accumulated roll clears its OWN random-walk spread. Sign fraction of the increments is
the companion check (0.5 = diffusive)."""
import csv,os,math,sys,statistics as st
O=sys.argv[1] if len(sys.argv)>1 else "RUN_LOGS/motor_audit/campaigns_2026-09/TWIRL_ALPHA_EPS0"
ARMS=[("a60_nat_20260901","a=60 native s1"),("a60_flp_20260901","a=60 FLIPPED s1"),
      ("a60_nat_20260902","a=60 native s2"),("a60_flp_20260902","a=60 FLIPPED s2"),
      ("a00_nat_20260901","a=0  native s1"),("a00_flp_20260901","a=0  FLIPPED s1")]
print(f"\n{'arm':<18}{'t_s':>7}{'fwd um':>9}{'turns':>9}{'turns/um':>10}{'z':>7}{'signfrac':>10}{'v um/s':>9}")
R={}; T_={}
for tag,lab in ARMS:
    f=os.path.join(O,tag,"trajectory_summary.csv")
    if not os.path.exists(f): print(f"{lab:<18}   (not started)"); continue
    rows=[r for r in csv.DictReader(open(f),delimiter='\t') if r.get("step","").isdigit()]
    if len(rows)<4: print(f"{lab:<18}   (starting: {len(rows)} rows)"); continue
    tr=[float(r["rollTurns"]) for r in rows]; d=[tr[i+1]-tr[i] for i in range(len(tr)-1)]
    net=tr[-1]-tr[0]; sd=st.stdev(d) if len(d)>1 else float('nan')
    z=net/(sd*math.sqrt(len(d))) if sd and sd==sd and sd>0 else float('nan')
    sf=sum(1 for x in d if x>0)/len(d)
    fwd=float(rows[-1]["fwd_um"]); T=float(rows[-1]["t_s"])
    # turns/um is a RATIO with a small, SIGN-CHANGING denominator early in a run: at fwd ~ -0.06 um it
    # manufactured a spurious sign reversal and the mirror test read "REVERSES" on pure noise. Raw TURNS is
    # the primary quantity; turns/um is only meaningful once the filament has actually travelled.
    R[tag]=net/fwd if fwd>0.5 else float('nan')
    print(f"{lab:<18}{T:>7.3f}{fwd:>9.3f}{net:>9.2f}{(net/fwd if abs(fwd)>0.05 else float('nan')):>10.2f}"
          f"{z:>7.2f}{sf:>10.2f}{float(rows[-1]['vfit_um_s']):>9.3f}")
    T_[tag]=net
print("\nMIRROR TEST on RAW TURNS (turns/um needs fwd > 0.5 um to mean anything):")
for s_ in ("20260901","20260902"):
    n,f=T_.get(f"a60_nat_{s_}"),T_.get(f"a60_flp_{s_}")
    if n is not None and f is not None:
        print(f"  alpha=60 seed {s_}: native {n:+8.2f} turns   flipped {f:+8.2f}   "
              f"{'reverses' if n*f<0 else 'SAME SIGN'}   odd=(n-f)/2 {(n-f)/2:+.2f}")
n,f=T_.get("a00_nat_20260901"),T_.get("a00_flp_20260901")
if n is not None and f is not None:
    print(f"  alpha=0  null  : native {n:+8.2f} turns   flipped {f:+8.2f}   odd {(n-f)/2:+.2f}")
print("\nMIRROR TEST on turns/um (blank until the arms have travelled):")
for s in ("20260901","20260902"):
    n,f=R.get(f"a60_nat_{s}"),R.get(f"a60_flp_{s}")
    if n is not None and f is not None and n==n and f==f:
        print(f"  alpha=60 seed {s}: native {n:+8.2f} turns/um   flipped {f:+8.2f}   "
              f"{'REVERSES' if n*f<0 else 'SAME SIGN -- not lattice-borne'}   odd=(n-f)/2 {(n-f)/2:+.2f}")
n,f=R.get("a00_nat_20260901"),R.get("a00_flp_20260901")
if n is not None and f is not None and n==n and f==f:
    print(f"  alpha=0  seed 20260901 (null): native {n:+8.2f}   flipped {f:+8.2f}   odd {(n-f)/2:+.2f}")
print("\n  z ~ 1 means the accumulated roll is indistinguishable from its own random walk.")
