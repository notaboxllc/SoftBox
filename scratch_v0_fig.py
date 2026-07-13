#!/usr/bin/env python3
import glob, matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import scratch_v0_analyze as A

fbar, full = A.parse(glob.glob('RUN_LOGS/v0fine/pass1/*.txt')+glob.glob('RUN_LOGS/v0fine/pass2/*.txt'))
dts = sorted({dt for (dt,v) in fbar}, reverse=True)
allv = sorted({v for (dt,v) in fbar})
colors = {1e-5:'#d1495b', 5e-6:'#edae49', 2.5e-6:'#66a182', 1.25e-6:'#2e4057'}
labels = {1e-5:'1.0e-5 (production)', 5e-6:'5.0e-6', 2.5e-6:'2.5e-6', 1.25e-6:'1.25e-6'}

fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 4.6))
# panel 1: f-v curves
for dt in dts:
    vs = [v for v in allv if len(fbar.get((dt,v),{}))>=2]
    ms = [A.mean(list(fbar[(dt,v)].values())) for v in vs]
    es = [A.sem(list(fbar[(dt,v)].values())) for v in vs]
    ax1.errorbar(vs, ms, yerr=es, marker='o', ms=4, capsize=2, color=colors[dt], label=labels[dt])
ax1.axhline(0, color='k', lw=0.8, ls='--')
ax1.set_xlabel('clamp velocity v (µm/s)'); ax1.set_ylabel(r'$\bar f_{available}$ (pN)')
ax1.set_title('Force–velocity curves (8 seeds, ±SEM)'); ax1.legend(fontsize=8); ax1.grid(alpha=0.3)

# panel 2: V0 vs dt
win=[12,13,14,15,16,18]
seeds=sorted({s for (d,v,s) in full})
xs=[]; v0=[]; lo=[]; hi=[]
for dt in dts:
    r=A.fit_v0(fbar, dt, [v for v in win if len(fbar.get((dt,v),{}))>=2], seeds)
    b=A.bootstrap_v0(fbar, dt, [v for v in win if len(fbar.get((dt,v),{}))>=2], seeds)
    xs.append(dt); v0.append(r[2]); lo.append(r[2]-b[1]); hi.append(b[2]-r[2])
ax2.errorbar(xs, v0, yerr=[lo,hi], marker='s', ms=6, capsize=3, color='#2e4057', lw=1.5)
ax2.axhspan(12.8, 13.8, color='#66a182', alpha=0.18, label='fine-dt reference 13.3 [12.8,13.8]')
ax2.axhline(13.3, color='#66a182', lw=1, ls=':')
ax2.set_xscale('log'); ax2.set_xlabel('dt (s)'); ax2.set_ylabel(r'$V_0$ (µm/s)')
ax2.set_title(r'$V_0(dt)$: production high, fine-dt plateau'); ax2.legend(fontsize=8); ax2.grid(alpha=0.3)
for x,y in zip(xs,v0): ax2.annotate(f'{y:.1f}', (x,y), textcoords='offset points', xytext=(6,6), fontsize=8)

plt.tight_layout(); plt.savefig('docs/FINE_DT_V0_REFERENCE.png', dpi=110)
print("wrote docs/FINE_DT_V0_REFERENCE.png")
