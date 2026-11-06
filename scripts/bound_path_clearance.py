import json,glob,sys,math,numpy as np
def axis_frame(fil, p):
    """closest filament segment to point p: returns (foot, u, dist2D-signed-side inputs)."""
    best = None
    for s in fil:
        a = np.array(s['end1']); b = np.array(s['end2']); u = b - a; L = np.linalg.norm(u); u /= L
        t = np.clip(np.dot(p - a, u), 0, L); foot = a + t * u; d = np.linalg.norm(p - foot)
        if best is None or d < best[0]: best = (d, foot, u)
    return best[1], best[2]

def theta_of(r, u):
    ref = np.array([0, 0, -1.0]); ref = ref - np.dot(ref, u) * u; ref /= np.linalg.norm(ref)
    r = r - np.dot(r, u) * u
    return math.atan2(np.dot(np.cross(ref, r), u), np.dot(ref, r))


fdir=sys.argv[1]; N=int(sys.argv[2]); files=sorted(glob.glob(fdir+'/frame_*.json'))[::10]
def segdist(p,q,fil):
    # min distance between segment pq and filament axis polyline (sampled)
    best=1e9
    for t in np.linspace(0,1,21):
        x=p+(q-p)*t
        for s in fil:
            a=np.array(s['end1']);b=np.array(s['end2']);u=b-a;L=np.linalg.norm(u);u/=L
            tt=np.clip(np.dot(x-a,u),0,L);best=min(best,np.linalg.norm(x-(a+tt*u)))
    return best*1e3
res={'lower':[],'side':[],'upper':[]}
for fn in files:
    j=json.load(open(fn)); fil=[s for s in j['segments'] if not s['motorSeg']][:24]
    beams=[s for s in j['segments'] if s['motorSeg'] and abs(s['r']-0.0009)<1e-6]
    art=[m for m in j['myosins'] if m['id']<N and m['motor']['state']!='anchor']; M=len(beams)//len(art)
    f8={m['id']-N:np.array(m['motor']['end1']) for m in j['myosins'] if m['id']>=N}
    for i,m in enumerate(art):
        if not m['bound']: continue
        p=f8[m['id']]; foot,u=axis_frame(fil,p); th=abs(math.degrees(theta_of(p-foot,u)))
        cls='lower' if th<60 else ('upper' if th>120 else 'side')
        # path: S2 beam nodes then lever P->C ; head centre
        pts=[np.array(beams[i*M+k]['end1']) for k in range(M)]+[np.array(beams[i*M+M-1]['end2']),np.array(m['lever']['end2'])]
        dpath=min(segdist(pts[k],pts[k+1],fil) for k in range(len(pts)-1))
        hc=np.array(m['motor']['end1']); _,_=axis_frame(fil,hc)
        dh=min(segdist(hc,hc,fil),1e9)
        res[cls].append((dpath,dh))
for k,v in res.items():
    v=np.array(v); print(f"{k:6s} n={len(v):4d}  S2+lever min dist to axis (nm): p10/p50 {np.percentile(v[:,0],10):5.1f}/{np.percentile(v[:,0],50):5.1f}  frac<3.5 nm (inside actin) {np.mean(v[:,0]<3.5):.2f}   head centre p10/p50 {np.percentile(v[:,1],10):5.1f}/{np.percentile(v[:,1],50):5.1f}  frac<8.5 nm (head sphere overlaps actin) {np.mean(v[:,1]<8.5):.2f}")

