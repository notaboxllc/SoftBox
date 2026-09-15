"""
MOTOR-SIDE test of epsStroke (jba's picture): does the MYOSIN's own actin-binding anchor move TANGENTIALLY
about the filament axis between primed (ADP.Pi) and post-stroke (ADP)?

WHY THIS IS A DIFFERENT TEST FROM §7.4. The F8 bond is zero-rest: F = k*(x_site - x_F8). Displacing the ACTIN
site by +d and the MOTOR anchor by -d give IDENTICAL strain -- the bond cannot tell which end moved. §7.4 tested
only the actin side (interface centroid 0.2 deg; protomer 0.33 deg). This tests the motor side.

METHOD: superpose 8RBF -> 8R9V on ACTIN CA only (so the actin frame is common and myosin motion is what remains);
decompose every myosin CA displacement into axial / radial / TANGENTIAL about the filament axis (screw operator);
report the profile by residue and the summary for the actin-CONTACT residues (the F8 anchor region).

SCALE: an equivalent eps follows from d_t = R_actin * eps with R_actin = 3.5 nm:
    eps =  4 deg  ->  2.44 A tangential      eps = 15 deg  ->  9.16 A tangential
Coordinate noise from the reference superposition is ~0.5-0.8 A, so 4 deg IS resolvable.
"""
import numpy as np, math
def parse(fn):
    return [(p[18],int(p[16]),p[3],p[2],float(p[10]),float(p[11]),float(p[12]))
            for p in (l.split() for l in open(fn) if l.startswith('ATOM'))]
def kabsch(P,Q):
    pc,qc=P.mean(0),Q.mean(0)
    V,S,Wt=np.linalg.svd((P-pc).T@(Q-qc)); d=np.sign(np.linalg.det(V@Wt))
    return V@np.diag([1,1,d])@Wt, qc-pc@(V@np.diag([1,1,d])@Wt)
A=parse('8r9v.cif'); B=parse('8rbf.cif')
ACT=('B','C','D')
ca=lambda at,chs:{(c,r):np.array([x,y,z]) for c,r,n,e,x,y,z in at if c in chs and n=='CA'}
a,b=ca(A,ACT),ca(B,ACT); k=sorted(set(a)&set(b))
R,t=kabsch(np.array([b[i] for i in k]),np.array([a[i] for i in k]))
rms=np.sqrt((((np.array([b[i] for i in k])@R+t)-np.array([a[i] for i in k]))**2).sum(1).mean())
print(f"actin superposition: {len(k)} CA, RMSD {rms:.3f} A  <- the noise floor")

# filament axis + origin from the B->C screw
cb={r:v for (c,r),v in a.items() if c=='B'}; cc={r:v for (c,r),v in a.items() if c=='C'}
kk=sorted(set(cb)&set(cc))
Rs,ts=kabsch(np.array([cb[i] for i in kk]),np.array([cc[i] for i in kk]))
w,v=np.linalg.eig(Rs); n=np.real(v[:,np.argmin(np.abs(w-1))]); n/=np.linalg.norm(n)
if ts@n<0: n=-n
origin=np.linalg.pinv(Rs-np.eye(3)).T@((ts@n)*n-ts)

# actin-contact myosin residues (the F8 anchor region), 5 A
acth=np.array([[x,y,z] for c,r,nm,e,x,y,z in A if c in ACT and e!='H'])
myoA={r:np.array([x,y,z]) for c,r,nm,e,x,y,z in A if c=='A' and nm=='CA'}
allA=[(r,np.array([x,y,z])) for c,r,nm,e,x,y,z in A if c=='A' and e!='H']
contact=set()
P=np.array([p[1] for p in allA]); d=np.linalg.norm(P[:,None,:]-acth[None,:,:],axis=2).min(1)
for (r,_),dd in zip(allA,d):
    if dd<5.0: contact.add(r)
myoB={r:np.array([x,y,z]) for c,r,nm,e,x,y,z in B if c=='A' and nm=='CA'}
km=sorted(set(myoA)&set(myoB))

def decomp(p0,p1):
    dv=(p1@R+t)-p0
    z=dv@n
    rad0=p0-origin; rad0=rad0-(rad0@n)*n; rad0/=np.linalg.norm(rad0)
    tang=np.cross(n,rad0)
    return z, dv@rad0, dv@tang
rows=[(r,)+decomp(myoA[r],myoB[r]) for r in km]
def summ(sel,lbl):
    s=[x for x in rows if x[0] in sel]
    if not s: return
    ax=np.mean([x[1] for x in s]); rd=np.mean([x[2] for x in s]); tg=np.mean([x[3] for x in s])
    tgsd=np.std([x[3] for x in s])
    eps=math.degrees(tg/35.0)   # 3.5 nm = 35 A
    print(f"  {lbl:28} n={len(s):4d}  axial {ax:+7.2f}  radial {rd:+7.2f}  TANGENTIAL {tg:+7.2f} +-{tgsd:5.2f} A  -> eps_equiv {eps:+6.2f} deg")
print(f"\n{'region':30}{'':6}{'mean displacement (A), actin frame':>40}")
summ(contact, "ACTIN-CONTACT (F8 anchor)")
summ(set(r for r in km if r <= 600), "motor-domain core (<=600)")
summ(set(r for r in km if r > 690), "converter region (>690)")
summ(set(km), "whole myosin chain")
print(f"\n  reference: eps= 4 deg needs +2.44 A tangential ; eps=15 deg needs +9.16 A ; noise floor ~{rms:.2f} A")
