# Phase-2 — sphere-head on the GPU dense mat: the REAL gliding assay

**2026-06-30. Flag-gated `-spherehead`; default-off ⇒ production BYTE-IDENTICAL (regression confirmed:
default d1000 velFitX 5.703 unchanged); `BoA-v1ref` byte-clean. The glide is adjudicated ONLY on the full
dense GPU mat (the standing rule) — the prior 40-motor CPU "+X gate" is rejected and was WRONG (see below).**

## STEP 1 — the GPU sphere-head path (built; the efficient faithful realization)
The corrected three-body sphere-head = **F8 tip anchor + the head-vs-actin angle held FIXED at 90° (the
compliant ⊥ perp-maintainer, no F9 power stroke) + the J1 converter neck-swing (0°↔60°) as the stroke**.
GlidingHarness's default motor already runs F8+F9+F10+J1 on the validated **device-resident dense-mat
TaskGraph** (the CSR gather, bind-on-proximity, the nucleotide cycle, the catch-slip release, velFitX, the
`-nobind` floor, the density ladder). So the sphere-head is realized on that exact GPU path by **freezing
F9's rest at 90°** (`xbParams[9]=1`): the head-vs-actin angle stops switching (F9 → the compliant
perp-maintainer), and the **J1 neck-swing becomes the only stroke**. F8 (tip anchor, 1 pN/nm), F10
(roll/azimuth — the orientation reference the single-motor harness lacked), the gather, and the catch-slip
load (axial F·seg.uVec) are all the validated device kernels — **no new gather, no new device code**, so
the `-cpu` runner stays valid and CPU≡GPU is the existing machinery. `-spherehead` is one flag; default-off
⇒ byte-identical.

## The single-density MAT gate (full bed, GPU device-resident, d1000, seed 0, 20k) — PASS, and −X
| motor | velFitX (µm/s) | netX | avgBound | fullMat | capFires |
|---|---|---|---|---|---|
| default (F9 stroke) | 5.703 | −5.870 | 10.9 | — | — |
| **`-spherehead` (J1 stroke)** | **+1.536** | **−1.004** | **14.8** | YES | 0 |

velFitX **positive = −x glide = CORRECT direction.** The sphere-head **glides −X at ~1.5 µm/s** on the
real dense mat — slower than the default's 5.7 (the J1 neck-swing is a weaker stroke than the F9
head-reorientation), but **directionally correct, high duty (avgB 14.8), and dt=1e-5 stable at mat scale**
(fullMat=YES, capFires=0).

**The prior 40-motor CPU "+X" gate was WRONG** (as the task warned): at reduced scale, without F10's
azimuth reference and the collective dense-mat dynamics, the stroke direction did not establish. On the
full mat it is unambiguously −X. **No glide verdict is taken from anything smaller than the full mat.**

## STEP 2 — the density ladder (GPU device-resident, full-mat, 40k, n=3, dt=1e-5)
velFitX = −slope of the steady-2nd-half LS fit (positive = −x glide). All cells fullMat=YES, capFires=0.

| density | nMot | avgBsteady | **velFitX ± SEM (µm/s)** | netX (mean) | sign |
|---|---|---|---|---|---|
| 200  | 1392  | 4.8  | **0.41 ± 0.12** | −0.68 | −X |
| 500  | 3480  | 9.3  | **1.10 ± 0.13** | −1.15 | −X |
| 1000 | 6960  | 14.9 | **0.91 ± 0.05** | −1.16 | −X |
| 2000 | 13920 | 18.7 | **0.53 ± 0.12** | −0.52 | −X |
| **floor (`-nobind`)** | 3480 | 0.0 | **−0.38** (one realization; ≈0/+x diffusion noise) | −0.03 | — |

- **EVERY density glides −X** (velFitX > 0, netX < 0), **unambiguously above the floor** (the no-bind
  filament's LS-slope is −0.38 = a slight +x diffusion artifact; the glide signal is +0.4…+1.1, a clean
  −x separation of ~0.8–1.5 µm/s). **dt=1e-5 STABLE at mat scale** everywhere (fullMat=YES, capFires=0,
  no whip/NaN even at 13920 motors) — the banked implicit/substep fix is NOT needed here.
- **Non-monotonic:** velFitX **peaks at d500 (~1.1) and DROPS at high density** (d2000 → 0.53) while
  avgBound rises monotonically 4.8 → 18.7. Classic over-binding / tug-of-war: past ~d500 the extra bound
  heads (including those in the +x recovery phase) increasingly resist each other, so duty rises but net
  directed velocity falls. The transport-efficiency-per-head is the lever, not more binding.

### CPU≡GPU (d500, 20k, n=3)
| runner | velFitX (mean) | avgBound |
|---|---|---|
| GPU | 1.12 (1.50/0.77/1.10) | 10.1 |
| CPU | 1.81 (1.78/1.74/1.92) | 9.3 |

Both **−X**, same order of magnitude, avgBound agrees (10.1 vs 9.3). The velFitX differs more than the
default motor's residual (GPU ~40% below CPU) — the **one-step-stale SoA-force parallel-scheme residual**
(GLIDING_4biv), to which the weaker J1 neck-swing stroke is evidently more sensitive than the F9 stroke.
Sign + magnitude agree (the chaotic-gliding standard); the larger precise gap is flagged.

## Adjudication vs the skeletal anchor — GLIDE −X, but SLOW
- **GLIDES −X:** yes, unambiguously, at every density, multi-seed, above floor, CPU≡GPU sign-agree,
  dt-stable. **The three-body sphere-head (J1 neck-swing stroke, head held ⊥, F8 anchor, F10 azimuth) is
  a working −x gliding motor on the real dense GPU mat.** The earlier 40-motor "+X" is rejected/wrong.
- **but SLOW:** velFitX peaks at ~**1.1 µm/s (d500)** — **below the 5–8 µm/s skeletal band** and ~5× the
  default F9 motor's 5.7 on the same bed. The J1 neck-swing is a weaker stroke than the F9
  head-reorientation (consistent with STROKE_VS_ARMLENGTH: the converter contributes less tip throw), and
  at high density the over-binding/tug-of-war erodes it further (the non-monotonic drop).
- **Threshold:** glide is clear from d200 up (avgBound ≥ ~5), but the speed never reaches skeletal; the
  density that maximizes velFitX (~d500) is in the Uyeda 100–300 ballpark, but the saturation speed is ~1
  µm/s, not 5–8.

**VERDICT: glide-but-slow (−X, ~1 µm/s peak; directionally correct + stable, sub-skeletal speed).** Next
lever is per-head transport efficiency (the weak J1 stroke + the high-density tug-of-war), NOT direction
or binding. The duty-cycle "release-while-cocked" idea is now a *speed* refinement on a working −x motor,
not a direction fix.

## CPU-fallback disclosure
The ladder + floor + the GPU half of CPU≡GPU run **device-resident on the GPU TaskGraph** (the validated
GlidingHarness `buildPlan`, ~23 kernels, no per-step host pull). The CPU≡GPU CPU half uses the `-cpu`
sequential runner (same kernels). No silent CPU fallback.

## Reproduce
```
./run_gliding.sh -gpu -spherehead -matbed -density <D> -dt 1e-5 -grid -seed <s> 40000   # the assay
./run_gliding.sh -gpu -spherehead -nobind -matbed -density 500 -dt 1e-5 -grid -seed <s> 40000   # floor
./run_gliding.sh     -spherehead -matbed -density 500 -dt 1e-5 -grid -seed 0 20000        # CPU half of CPU≡GPU
```
