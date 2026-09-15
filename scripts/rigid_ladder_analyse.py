import csv,os,sys
O=sys.argv[1] if len(sys.argv)>1 else "RUN_LOGS/motor_audit/campaigns_2026-09/RIGID_EPS_LADDER"
def series(n):
    f=os.path.join(O,n,"trajectory_summary.csv")
    if not os.path.exists(f): return None
    r=list(csv.DictReader(open(f),delimiter='\t'))
    s=[(abs(float(x["fwd_um"])), float(x["rollTurns"]), float(x["vfit_um_s"]), float(x["avgBound"])) for x in r if abs(float(x["fwd_um"]))>0]
    return s or None
print(f"{'eps':>5}{'pos t/um':>10}{'neg t/um':>10}{'eps-ODD':>10}{'eps-even':>10}{'pitch_um':>10}{'v':>7}{'avgB':>7}")
rows=[]
for E in (1,2,4,8):
    p,n=series(f"e{E}_pos"),series(f"e{E}_neg")
    if not p or not n: print(f"{E:5d}   (incomplete)"); continue
    tp=p[-1][1]/p[-1][0]; tn=n[-1][1]/n[-1][0]
    odd=(tp-tn)/2; even=(tp+tn)/2
    rows.append((E,odd))
    pit = 1/abs(odd) if odd else float('nan')
    print(f"{E:5d}{tp:10.3f}{tn:10.3f}{odd:10.3f}{even:10.3f}{pit:10.3f}{p[-1][2]:7.3f}{p[-1][3]:7.2f}")
print("\nexperiment (Beausang 2008): 2.1 turns/um, pitch 0.47 +/- 0.19 um")
if rows:
    print("linearity  odd/eps: " + "  ".join(f"e{E}:{o/E:+.3f}" for E,o in rows))
    d=dict(rows)
    if 4 in d and d[4]: print(f"eps for 2.1 turns/um (from eps=4): {4*2.1/abs(d[4]):.2f} deg")
print("\n--- ACCELERATION: local turns/um in 0.2 um windows (pos arms) ---")
for E in (1,2,4,8):
    s=series(f"e{E}_pos")
    if not s: continue
    out=[]; prev=(0.0,0.0)
    for e in [0.2*k for k in range(1,11)]:
        sel=[q for q in s if q[0]<=e]
        if not sel: continue
        cur=(sel[-1][0],sel[-1][1])
        if cur[0]-prev[0]>1e-6:
            out.append(f"{(cur[1]-prev[1])/(cur[0]-prev[0]):+.1f}"); prev=cur
    print(f"  eps={E:<3} " + " ".join(out))
