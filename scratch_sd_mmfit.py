#!/usr/bin/env python3
# MM fit of the speed-density curve: V = Vmax * N/(KM+N); report V/Vmax vs N/KM vs the universal y=x/(1+x).
# Reads RUN_LOGS/2026-06-29_sd_stage1.txt rows: "density=<N> seed=<s> netX=<v> ... avgBsteady=<a> fullMat=<f>"
import sys, re, math
from collections import defaultdict

path = sys.argv[1] if len(sys.argv) > 1 else "RUN_LOGS/2026-06-29_sd_stage1.txt"
byD = defaultdict(list); byDb = defaultdict(list); byDi = defaultdict(list); byDs = defaultdict(list)
for ln in open(path):
    m = re.search(r"density=(\S+).*?netX=([-0-9.eE]+)", ln)
    if not m: continue
    if "fullMat=YES" not in ln:  # exclude blown-up cells
        continue
    D = float(m.group(1)); netx = float(m.group(2)); v = abs(netx)  # |netX| = glide speed
    byD[D].append(v); byDs[D].append(netx)
    ab = re.search(r"avgBsteady=([-0-9.eE]+)", ln)
    if ab: byDb[D].append(float(ab.group(1)))
    inst = re.search(r"instSteady=([-0-9.eE]+)", ln)
    if inst: byDi[D].append(float(inst.group(1)))

Ds = sorted(byD)
print("density(N)  n   netX mean±SD   |V|   avgBound   instSteady   grip-lock(inst/|netX|)")
data = []
for D in Ds:
    vs = byD[D]; sx = byDs[D]; mn = sum(vs)/len(vs); smn = sum(sx)/len(sx)
    sd = (sum((x-smn)**2 for x in sx)/max(1,len(sx)-1))**0.5 if len(sx)>1 else 0.0
    ab = sum(byDb[D])/len(byDb[D]) if byDb[D] else float('nan')
    ist = sum(byDi[D])/len(byDi[D]) if byDi[D] else float('nan')
    gl = ist/mn if mn > 1e-6 else float('inf')
    data.append((D, mn, sd, len(vs), ab))
    print(f"  {D:7.0f}  {len(vs)}   {smn:+6.3f}±{sd:4.2f}   {mn:5.3f}   {ab:6.2f}     {ist:6.2f}      {gl:6.1f}")

# --- MM fit V=Vmax*N/(KM+N) via Lineweaver-Burk-robust nonlinear grid (simple) ---
N = [d[0] for d in data]; V = [d[1] for d in data]
if len(N) >= 3:
    best = None
    for KM in [x*5 for x in range(1, 4001)]:           # KM 5..20000
        # Vmax from least-squares given KM: V = Vmax * f, f=N/(KM+N)
        f = [n/(KM+n) for n in N]
        num = sum(f[i]*V[i] for i in range(len(N))); den = sum(f[i]*f[i] for i in range(len(N)))
        if den == 0: continue
        Vmax = num/den
        sse = sum((V[i]-Vmax*f[i])**2 for i in range(len(N)))
        if best is None or sse < best[0]: best = (sse, Vmax, KM)
    sse, Vmax, KM = best
    print(f"\nMM fit: Vmax = {Vmax:.3f} µm/s,  KM = {KM:.0f} motors/µm²,  SSE={sse:.4f}")
    print("\n N/KM    V/Vmax(model)   universal x/(1+x)   |Δ|")
    for D in N:
        x = D/KM; yv = (V[N.index(D)]/Vmax); yu = x/(1+x)
        print(f" {x:6.3f}    {yv:7.3f}        {yu:7.3f}        {abs(yv-yu):.3f}")
    # saturation check: does V/Vmax reach >0.8 within the swept range?
    maxfrac = max(V)/Vmax
    print(f"\nmax V/Vmax in swept range = {maxfrac:.2f} (saturation near 1 ⇒ rises-and-saturates; <<1 ⇒ still climbing / deficit)")
    print(f"experiment anchor: Walcott/Warshaw Vmax≈2.9 µm/s, KM≈16 µm⁻² (model KM asterisked — entangled with kOn/duty)")
else:
    print("\n(need ≥3 densities for an MM fit)")
