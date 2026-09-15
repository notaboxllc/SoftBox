"""
DOES THE TARGET ACTIN PROTOMER TWIST UNDER THE BOUND HEAD?  (the §7.4 blind spot)

§7.4 superposed 8RBF onto 8R9V using ALL actin CA atoms, which by construction REMOVES any rotation of actin
itself. But epsStroke moves the ACTIN MATERIAL SITE -- so if the chirality lives in the target protomer twisting
under the head, that method was blind to it.

METHOD (fixed before the result):
  1. superpose 8RBF -> 8R9V on chain C CA only. C is the protomer myosin NEVER touches (0 contacts within 5 A),
     so it is an unbiased reference frame.
  2. in that frame, compute the residual rigid-body rotation of chain B (the TARGET actin, 65 contacts) and
     chain D (the ANCILLARY, 30 contacts) between the two nucleotide states.
  3. project each rotation onto the filament axis (from the B->C helical screw operator).
     The AXIAL COMPONENT is the structural analogue of epsStroke.
  4. control: chain C's own residual must be ~0 by construction; report it as the noise floor.
"""
import numpy as np, math
def parse(fn):
    return [(p[18],int(p[16]),p[3],p[2],float(p[10]),float(p[11]),float(p[12]))
            for p in (l.split() for l in open(fn) if l.startswith('ATOM'))]
def ca(at,ch): return {r:np.array([x,y,z]) for c,r,n,e,x,y,z in at if c==ch and n=='CA'}
def kabsch(P,Q):
    pc,qc=P.mean(0),Q.mean(0)
    V,S,Wt=np.linalg.svd((P-pc).T@(Q-qc)); d=np.sign(np.linalg.det(V@Wt))
    R=V@np.diag([1,1,d])@Wt; return R, qc-pc@R
def axis_angle(R):
    ang=math.degrees(math.acos(max(-1,min(1,(np.trace(R)-1)/2))))
    w,v=np.linalg.eig(R); n=np.real(v[:,np.argmin(np.abs(w-1))]); n/=np.linalg.norm(n)
    aa=np.array([R[2,1]-R[1,2],R[0,2]-R[2,0],R[1,0]-R[0,1]])
    if aa@n<0: n,ang=-n,ang   # keep n as the positive-rotation axis
    return n,ang
A=parse('8r9v.cif'); B=parse('8rbf.cif')

# filament axis from the B->C screw in 8R9V
cb,cc=ca(A,'B'),ca(A,'C'); k=sorted(set(cb)&set(cc))
Rs,ts=kabsch(np.array([cb[i] for i in k]),np.array([cc[i] for i in k]))
w,v=np.linalg.eig(Rs); fax=np.real(v[:,np.argmin(np.abs(w-1))]); fax/=np.linalg.norm(fax)
if ts@fax<0: fax=-fax
print(f"filament axis ({fax[0]:+.4f},{fax[1]:+.4f},{fax[2]:+.4f})")

# 1. superpose on chain C only
a,b=ca(A,'C'),ca(B,'C'); kc=sorted(set(a)&set(b))
Rc,tc=kabsch(np.array([b[i] for i in kc]),np.array([a[i] for i in kc]))
rms=np.sqrt((((np.array([b[i] for i in kc])@Rc+tc)-np.array([a[i] for i in kc]))**2).sum(1).mean())
print(f"reference superposition on chain C only: {len(kc)} CA, RMSD {rms:.3f} A\n")

print(f"{'chain':>6} {'role':>12} {'nCA':>5} {'resid RMSD':>11} {'rot angle':>10} {'AXIAL comp':>12}")
for ch,role in (('B','TARGET'),('D','ancillary'),('C','ref/control')):
    p,q=ca(A,ch),ca(B,ch); kk=sorted(set(p)&set(q))
    P=np.array([q[i] for i in kk])@Rc+tc          # 8RBF chain, in the C-aligned frame
    Q=np.array([p[i] for i in kk])                 # 8R9V chain
    r0=np.sqrt(((P-Q)**2).sum(1).mean())
    Rr,tr=kabsch(P,Q)                              # residual motion of this protomer
    n,ang=axis_angle(Rr)
    axial=ang*float(n@fax)                         # signed component about the filament axis
    print(f"{ch:>6} {role:>12} {len(kk):>5} {r0:>10.3f}A {ang:>9.3f}d {axial:>+11.3f}d")
