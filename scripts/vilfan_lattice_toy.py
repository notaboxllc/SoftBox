import numpy as np
kT=4.11; kF8=1.0; alpha=22.4/kT            # canon k_ori 22.4 pN nm/rad^2 -> alpha 5.45
def lattice(kind, mirror=1, L=600):
    tw=np.radians(-166.5)*mirror; a=2.7
    i=np.arange(-L,L)
    if kind=='native': idx=i
    elif kind=='every4_2strand': idx=np.sort(np.concatenate([4*i,4*i+1]))
    elif kind=='every4_1strand': idx=4*i
    return idx*a, idx*tw
def wrap(t): return (t+np.pi)%(2*np.pi)-np.pi
rng=np.random.default_rng(1)
def bias(kind, kAtau, mirror=1, n=20000, v=1.0):
    # filament translates +x at v; motor at x=0, preferred azimuth 0. time unit: tau = 36 nm / v.
    x,th=lattice(kind,mirror); tau=36.0/abs(v); kA=kAtau/tau
    dt=0.02; T=np.arange(0,6*tau,dt)
    out=[]
    for _ in range(n//200):
        X0=rng.uniform(0,40); Th0=rng.uniform(-np.pi,np.pi)
        # weights for all times (vectorised over sites near)
        res=[]
        for t in T:
            dx=x+X0+v*t; m=np.abs(dx)<10
            d=wrap(th[m]+Th0); w=np.exp(-0.5*kF8*dx[m]**2/kT-0.5*alpha*d**2)
            res.append((w.sum(), (w*d).sum()))
        W=np.array(res)
        # many motors sharing this (X0,Th0): sample first passage
        H=np.cumsum(kA*W[:,0]*dt)
        u=-np.log(rng.uniform(size=200))
        k=np.searchsorted(H,u); k=k[k<len(T)]
        out.extend(W[k,1]/W[k,0])
    o=np.array(out); return o.mean(), o.std()/np.sqrt(len(o)), len(o)
for kAtau in [0.3,1,3,10,30]:
    r=[bias(k,kAtau) for k in ['native','every4_2strand','every4_1strand']]
    rm=bias('every4_2strand',kAtau,mirror=-1)
    print(f"kA*tau={kAtau:5}: native {r[0][0]:+.4f}±{r[0][1]:.4f}  every4-2s {r[1][0]:+.4f}±{r[1][1]:.4f}  every4-1s {r[2][0]:+.4f}±{r[2][1]:.4f}  every4-2s mirror {rm[0]:+.4f}")
