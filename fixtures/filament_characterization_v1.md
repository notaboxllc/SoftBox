# v1 filament-characterization reference fixtures

Captured from BoA v1 at tag `softbox-filref-2026-06-13` (built read-only to /tmp/v1classes;
worktree `~/Code/BoA-v1ref` never modified). These are v1's *measurements* for matched filaments —
the bar for the v2 instruments is "reproduces v1's measurement," NOT a biological target.

Filament: monomerCt=32 (segLen 0.0891 µm), aeta=0.1 Pa·s, dt=1e-4 s, fracMove=0.5, BRotCoeff=0.5,
EI = kT·Lp with Lp=15 µm (EI=6.1746e-26 N·m²). Coeffs as noted per metric.

## Deflection ratio (obs/exp), 11-seg pinned chain, Brownian off, fracMoveTorq=0.265
| fracR | v1 ratio | v2 ratio | Δ |
|---|---|---|---|
| 0.025 | 0.39198 | 0.39184 | 0.04% |
| 0.1   | 0.99842 | 0.99831 | 0.01% |
| 0.4   | 2.19003 | 2.18990 | 0.006% |
| 0.8   | 2.77652 | 2.77639 | 0.005% |
v1 source: BoxOfActin -bmDiag ([BMDIAG] ratio=...). v2: -deflect. Tolerance: ≤0.05% (TIGHT).

## Relaxation time τ, 11-seg pinned chain, fracR=0.1, fracMoveTorq=0.265
- v1 τ_theo = 0.057 s (printed `[BENCH] τ_theo=...`; formula N·ζ_perp·span³/(EI·π⁴), ζ_perp=midSeg bTransGam.y=3.309e-8).
- v2 τ_theo = 0.05697 s  → EXACT match (same analytic formula).
- v2 τ_meas = 0.05652 s, τ_meas/τ_theo = 0.9920 (1/e crossing of the release decay).
- v1 τ_meas: triggered by an interactive force-release (WebSocket HUD), not headlessly capturable at the
  tag; the deterministic relaxation is validated by the ≤0.04% static-ratio match (identical dynamics)
  and v2's τ_meas/τ_theo≈0.99. Tolerance: τ_theo exact; τ_meas/τ_theo within ~2% of 1.0.

## Persistence length Lp (tangent-correlation C(s) + weighted log-fit), 539-seg/48-µm free chain, fracR=0.1
The C(s) MEASUREMENT reproduces v1 tightly; the derived scalar Lp_meas is ILL-CONDITIONED (the
uncalibrated chain is far stiffer than its 48-µm contour, so C barely decays and 1/slope is
noise-dominated — present in v1 too; calibrating Lp→15 µm is the auto-tune's job, deliberately NOT
ported). v1 Lp computed by applying v1's exact estimator (EWMA + w=C² log-fit) to v1's LP-chain frames.

fracMoveTorq=0.265 (default, stiff/near-straight, well-equilibrated):
| quantity | v1 | v2 |
|---|---|---|
| C(1)   | 0.9989 | 0.9987 |   (<0.05% — tangent correlation matches)
| C(538) | 0.7370 | 0.7366 |   (<0.06%)
| Lp_meas (scalar) | 785 µm | 1441 µm |  (~2×; ill-conditioned, NOT a tight match)

Verdict: C(s) (the measured quantity) reproduces v1 to <0.05% → instrument + physics faithful. The
scalar Lp_meas is a noisy diagnostic on this too-short/too-stiff chain (large scatter in BOTH v1 and
v2); it is explicitly not gated on an absolute value.
