#!/usr/bin/env python3
"""SET-A speed-density: parse GRID_ROW lines, aggregate velFitX per density (mean±SEM over seeds),
fit Michaelis-Menten V = Vmax*N/(KM+N) (no scipy: closed-form Vmax | grid over KM, weighted), plot."""
import sys, re, glob
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

files = sys.argv[1:] if len(sys.argv) > 1 else ["PHASE2_SETA_multiseed.txt"]
rows = {}   # density -> list of (velFitX, avgB)
nbad = 0
for fn in files:
    for ln in open(fn):
        m = re.search(r"GRID_ROW.*density=(\S+).*velFitX=(\S+).*\binst=(\S+).*avgB=(\S+)", ln)
        if not m: continue
        d = float(m.group(1)); v = float(m.group(2)); inst = float(m.group(3)); ab = float(m.group(4))
        # drop numerical blowups: huge velFitX, or huge instantaneous speed (NaN propagation at high density)
        if abs(v) > 50 or not np.isfinite(v) or abs(inst) > 500 or not np.isfinite(inst):
            nbad += 1; continue
        rows.setdefault(d, []).append((v, ab))
print(f"(dropped {nbad} blown-up/NaN runs)")

dens = sorted(rows)
N = np.array(dens)
V = np.array([np.mean([r[0] for r in rows[d]]) for d in dens])
nseed = np.array([len(rows[d]) for d in dens])
SD = np.array([np.std([r[0] for r in rows[d]], ddof=1) if len(rows[d]) > 1 else 0.0 for d in dens])
SEM = np.array([sd/np.sqrt(n) if n > 1 else max(0.05, 0.15*abs(v)) for sd, n, v in zip(SD, nseed, V)])
AB = np.array([np.mean([r[1] for r in rows[d]]) for d in dens])

print("density  nseed  V(mean)  SEM    avgB")
for d, n, v, e, ab in zip(N, nseed, V, SEM, AB):
    print(f"{d:7.0f}  {n:4d}  {v:7.3f}  {e:5.3f}  {ab:5.2f}")

# --- is there even a rising-saturating signal? (directed glide must be consistently one sign & rise) ---
# convention: a real −x glide is velFitX>0. Report whether the curve rises above the noise floor.
noise = np.median(SEM)
# a real directed glide is consistently ONE sign and rises in magnitude with density. Here velFitX sign-flips
# across density (noise around 0) ⇒ no coherent curve. Require sign-consistency AND a monotone-ish |V| rise.
sign_consistent = np.all(V > 0) or np.all(V < 0)
rises = sign_consistent and (np.max(np.abs(V)) > 3*noise)
print(f"\nnoise floor (median SEM) = {noise:.3f} µm/s ; |V| range = [{V.min():.3f},{V.max():.3f}] ; rising-saturating signal: {rises}")
if not rises:
    print("==> NO rising-saturating Michaelis-Menten form: directed velocity is flat/near-zero (directedness collapse).")

# --- MM fit (attempted regardless, for the record): fixed KM -> Vmax closed-form; grid KM, min weighted SSE ---
w = 1.0/np.maximum(SEM, 1e-3)**2
def fit_km(km):
    g = N/(km+N)
    vmax = np.sum(w*V*g)/np.sum(w*g*g)
    sse = np.sum(w*(V - vmax*g)**2)
    return vmax, sse
kms = np.linspace(1, 30000, 60000)
res = [fit_km(km) for km in kms]
sses = np.array([r[1] for r in res])
i = int(np.argmin(sses))
KM = kms[i]; VMAX = res[i][0]
# crude 1-sigma on KM from SSE rise of 1 (chi2)
lo = kms[0]; hi = kms[-1]
below = np.where(sses <= sses[i] + 1.0)[0]
if len(below): lo, hi = kms[below[0]], kms[below[-1]]
print(f"\nMM fit:  Vmax = {VMAX:.3f} µm/s   KM = {KM:.0f} motors/µm²  (KM 1σ ~ [{lo:.0f},{hi:.0f}])")
print(f"V(KM)= {VMAX*0.5:.3f} (half-max check)")
print(f"Walcott/Warshaw skeletal control Vmax ~ 2.9 µm/s  -> model/exp = {VMAX/2.9:.2f}x")

# --- Plot 1: V vs density (+ MM fit only if a real rising-saturating signal exists) ---
fig, ax = plt.subplots(figsize=(7.5,5))
ax.errorbar(N, V, yerr=SEM, fmt='o', ms=7, color='tab:blue', capsize=3, label='SoftBox SET-A directed glide (velFitX ± SEM)', zorder=3)
ax.axhline(0, ls='-', color='k', lw=0.8, alpha=0.5)
ax.axhline(2.9, ls='--', color='gray', alpha=0.8, label='Walcott/Warshaw Vmax ~2.9 µm/s (skeletal)')
if rises:
    xx = np.linspace(0, N.max()*1.05, 400)
    ax.plot(xx, VMAX*xx/(KM+xx), '-', color='tab:red', lw=2, label=f'MM fit: Vmax={VMAX:.2f}, KM={KM:.0f}')
    ax.axhline(VMAX, ls=':', color='tab:red', alpha=0.6); ax.axvline(KM, ls=':', color='tab:green', alpha=0.6)
else:
    ax.text(0.5, 0.92, 'NO rising-saturating MM form — directed glide flat near 0\n(co-bound tug-of-war, not transport)',
            transform=ax.transAxes, ha='center', va='top', color='tab:red', fontsize=10,
            bbox=dict(boxstyle='round', fc='mistyrose', ec='tab:red'))
ax.set_xlabel('motor density  (motors / µm²)'); ax.set_ylabel('directed gliding velocity  (µm/s)')
ax.set_title('SET-A skeletal speed–density (force-matched κ, kinetics FROZEN — clean read)')
ax.legend(loc='lower right'); ax.grid(alpha=0.3); ax.set_xlim(0, N.max()*1.05)
fig.tight_layout(); fig.savefig('seta_speed_density.png', dpi=130); print('wrote seta_speed_density.png')

# --- Plot 2: the directedness diagnostic — directed velFitX vs the (exploding) instantaneous wander + avgBound ---
# (the units-robust normalized MM overlay is only meaningful with a fitted KM/Vmax; absent that, show WHY: binding
#  rises with density while directed velocity stays ≈0 and the thermal/tug-of-war wander explodes.)
fig2, ax2 = plt.subplots(figsize=(7.5,5))
ax2.errorbar(N, V, yerr=SEM, fmt='o-', ms=7, color='tab:blue', capsize=3, label='directed glide velFitX (µm/s)', zorder=3)
ax2.axhline(0, ls='-', color='k', lw=0.8, alpha=0.5)
ax2.set_xlabel('motor density (motors / µm²)'); ax2.set_ylabel('directed velocity (µm/s)', color='tab:blue')
ax2.tick_params(axis='y', labelcolor='tab:blue')
ax3 = ax2.twinx()
ax3.plot(N, AB, 's--', color='tab:green', label='avgBound (engagement)')
ax3.set_ylabel('avgBound', color='tab:green'); ax3.tick_params(axis='y', labelcolor='tab:green')
ax2.set_title('SET-A: strong binding rises with density, directed glide stays ≈0')
l1,la1 = ax2.get_legend_handles_labels(); l2,la2 = ax3.get_legend_handles_labels()
ax2.legend(l1+l2, la1+la2, loc='upper left'); ax2.grid(alpha=0.3)
fig2.tight_layout(); fig2.savefig('seta_speed_density_normalized.png', dpi=130); print('wrote seta_speed_density_normalized.png (directedness diagnostic)')
