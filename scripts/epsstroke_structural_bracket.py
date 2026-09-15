"""
epsStroke STRUCTURAL BRACKET -- corrected axis (helical screw operator, not PCA).
8R9V primed (ADP.Pi) vs 8RBF post-powerstroke (ADP), actomyosin-5a, Klebl 2025.
Method frozen: superpose on actin CA; axis+origin from the B->C screw operator; contacts = myosin heavy
atoms within CUTOFF of actin heavy atoms; eps = dphi of the contact centroid about the filament axis.
"""
import numpy as np, math
def parse(fn):
    return [(p[18],int(p[16]),p[3],p[2],float(p[10]),float(p[11]),float(p[12]))
            for p in (l.split() for l in open(fn) if l.startswith('ATOM'))]
def kabsch(P,Q):
    pc,qc=P.mean(0),Q.mean(0)
    V,S,Wt=np.linalg.svd((P-pc).T@(Q-qc)); d=np.sign(np.linalg.det(V@Wt))
    R=V@np.diag([1,1,d])@Wt; return R, qc-pc@R
ACT=('B','C','D')
A=parse('8r9v.cif'); B=parse('8rbf.cif')
ca=lambda at,chs:{(c,r):np.array([x,y,z]) for c,r,n,e,x,y,z in at if c in chs and n=='CA'}
a,b=ca(A,ACT),ca(B,ACT); k=sorted(set(a)&set(b))
R,t=kabsch(np.array([b[i] for i in k]),np.array([a[i] for i in k]))
xf=lambda P: P@R+t
print(f"actin superposition on {len(k)} CA, RMSD "
      f"{np.sqrt(((xf(np.array([b[i] for i in k]))-np.array([a[i] for i in k]))**2).sum(1).mean()):.3f} A")

# axis + a point on it, from the B->C screw
cb={r:v for (c,r),v in a.items() if c=='B'}; cc={r:v for (c,r),v in a.items() if c=='C'}
kk=sorted(set(cb)&set(cc))
Rs,ts=kabsch(np.array([cb[i] for i in kk]),np.array([cc[i] for i in kk]))
w,v=np.linalg.eig(Rs); n=np.real(v[:,np.argmin(np.abs(w-1))]); n/=np.linalg.norm(n)
rise=float(ts@n)
if rise<0: n,rise=-n,-rise
aa=np.array([Rs[2,1]-Rs[1,2],Rs[0,2]-Rs[2,0],Rs[1,0]-Rs[0,1]])
twist=np.sign(aa@n)*math.degrees(math.acos(max(-1,min(1,(np.trace(Rs)-1)/2))))
origin=np.linalg.pinv(Rs-np.eye(3)).T@(rise*n-ts)      # point on the screw axis
print(f"screw operator: twist {twist:+.2f} deg, rise {rise:.2f} A, axis ({n[0]:+.4f},{n[1]:+.4f},{n[2]:+.4f})")
print(f"  -> +axis is the direction along which the helix advances by {twist:+.1f} deg/subunit")

ref=np.array([1.,0,0]); ref=ref-(ref@n)*n; ref/=np.linalg.norm(ref)
def cyl(p):
    d=p-origin; z=d@n; rad=d-z*n
    return np.linalg.norm(rad), math.degrees(math.atan2(np.cross(n,ref)@rad, ref@rad)), z
hv=lambda at,chs,tr=False:(xf(np.array([[x,y,z] for c,r,nm,e,x,y,z in at if c in chs and e!='H']))
                           if tr else np.array([[x,y,z] for c,r,nm,e,x,y,z in at if c in chs and e!='H']))
aA,aB=hv(A,ACT),hv(B,ACT,True); mA,mB=hv(A,('A',)),hv(B,('A',),True)
print(f"\n{'cutoff':>7} {'n_pre':>6} {'n_post':>7} {'phi_pre':>9} {'phi_post':>9} {'EPS(deg)':>10} {'dz(A)':>8} {'r_pre(A)':>9} {'r_post(A)':>10}")
out=[]
for cut in (4.0,4.5,5.0):
    f=lambda m,ac: m[(np.linalg.norm(m[:,None,:]-ac[None,:,:],axis=2)<cut).any(1)]
    cA,cB=f(mA,aA),f(mB,aB)
    rA,phA,zA=cyl(cA.mean(0)); rB,phB,zB=cyl(cB.mean(0))
    eps=(phB-phA+180)%360-180; out.append(eps)
    print(f"{cut:7.1f} {len(cA):6d} {len(cB):7d} {phA:9.2f} {phB:9.2f} {eps:10.2f} {zB-zA:8.2f} {rA:9.2f} {rB:10.2f}")
print(f"\n  eps across cutoffs: mean {np.mean(out):+.2f} deg, spread {max(out)-min(out):.2f} deg")

# ---- CONTROL: restrict to the CONSERVED CORE interface (myosin residues contacting actin in BOTH states).
# If the patch merely GROWS asymmetrically, the common-core centroid shift should be much smaller than 5.4 deg.
print("\n=== CONTROL: common-core interface only (residues in contact in BOTH states) ===")
resA=[(c,r,e,np.array([x,y,z])) for c,r,nm,e,x,y,z in A if c=='A' and e!='H']
resB_raw=[(c,r,e,np.array([x,y,z])) for c,r,nm,e,x,y,z in B if c=='A' and e!='H']
posB=xf(np.array([p[3] for p in resB_raw])); resB=[(c,r,e,posB[i]) for i,(c,r,e,_) in enumerate(resB_raw)]
print(f"{'cutoff':>7} {'nres_pre':>9} {'nres_post':>10} {'nres_common':>12} {'EPS_core':>10} {'EPS_all':>9}")
for cut,eps_all in zip((4.0,4.5,5.0),out):
    def cres(res,ac):
        s=set()
        P=np.array([p[3] for p in res]); d=np.linalg.norm(P[:,None,:]-ac[None,:,:],axis=2).min(1)
        for (c,r,e,_),dd in zip(res,d):
            if dd<cut: s.add(r)
        return s
    sA=cres(resA,aA); sB=cres(resB,aB); common=sA&sB
    if not common: print(f"{cut:7.1f}  (no common residues)"); continue
    pA=np.array([p[3] for p in resA if p[1] in common]).mean(0)
    pB=np.array([p[3] for p in resB if p[1] in common]).mean(0)
    _,phA,_=cyl(pA); _,phB,_=cyl(pB)
    e_core=(phB-phA+180)%360-180
    print(f"{cut:7.1f} {len(sA):9d} {len(sB):10d} {len(common):12d} {e_core:10.2f} {eps_all:9.2f}")
