# Bound-head pose / converter placement — state as of 2026-09-11

Written as a context-clearing handoff. Split strictly by what is VERIFIED vs what is NOT.

## ESTABLISHED (verified; n>=3 or bit-level check)

**Head tilt changes gliding velocity strongly and monotonically.** Rigid filament, eps=0, triad bond,
density 500 heads/um^2, eta=0.1, 200k steps, 3 seeds. Absolute velocities (um/s, positive =
pointed-leading = correct direction):

| tilt | s1 | s2 | s3 | mean +/- SEM |
|---|---|---|---|---|
| -30 (converter BARBED) | 3.28 | 3.64 | 4.64 | 3.86 +/- 0.41 |
| -20 | 1.84 | 2.60 | 2.76 | 2.40 +/- 0.29 |
| -10 | 1.25 | 1.58 | 1.91 | 1.58 +/- 0.19 |
| 0 (current model) | 0.81 | 1.07 | 1.66 | 1.18 +/- 0.25 |
| +10 | 0.65 | 0.70 | 0.90 | 0.75 +/- 0.08 |
| +20 | 0.13 | -0.02 | 0.45 | 0.19 +/- 0.14 |
| +30 (converter POINTED) | -0.26 | -0.18 | -0.38 | -0.27 +/- 0.06 |

Every seed moves the same way at every step. Context: in-vitro target ~4.2 um/s at 25 C; the
calibrated surrogate reproduces ~2.3 um/s — both measured near density 2000, NOT 500, so this is
order-of-magnitude context, not a matched comparison.

**Sign conventions, both verified empirically (NOT by algebra — I got this backwards once):**
- `converter axial offset = -7.0*sin(tilt) nm`, positive = BARBED. So NEGATIVE tilt = converter
  barbed-side = the biological pose. Verified by measuring the converter position directly from
  `-3js` frame geometry: -2.21 nm at tilt +45, predicted -2.47.
- `vfit_um_s > 0` = pointed-leading = correct glide direction (`fwd = -bhat`,
  SiteNormalLongGlideHarness:595).
- Corrected in-code at `SiteNormalBindSystem` (comment) and the startup banner, both of which had
  it inverted.

**convaz moves the converter as intended, but attenuated.** 3-way decomposition from frame geometry
at convaz +/-60: radial +1.73/+1.79 (predicted +1.75, exact), circumferential -0.26/+1.12
(predicted 0, sd 1.75 — consistent with zero), axial +0.96/-1.59 (predicted +/-3.03). Direction
correct; amplitude 30-50% of nominal because finite `kbind` lets the head rotate to relieve the
imposed offset. So the convaz sweep spans only ~+/-1 nm of REAL axial displacement vs the tilt
sweep's +/-3.5 nm.

## NOT ESTABLISHED (do not build on these)

- **Mechanism of the tilt gain — open.** Sterics are RULED OUT: there is no excluded-volume force
  between head and filament and the binding-time gate `g6` is retired, so head "depth" exerts no
  force. Converter position is UNTESTED at tilt-comparable displacement (convaz is attenuated).
  Remaining hypothesis: head ORIENTATION sets how the converter's psi-swing maps into F8
  displacement and the couple on the filament. Not demonstrated.
- **Is -30 the optimum?** Unknown — the curve is still climbing there. Wide points (-45,-60) have
  NOT run.
- **RESOLVED 2026-09-12 — NO SUPPRESSION (ratio 0.991). This item is CLOSED; do not re-run it at eps=1.5.**
  At eps=8, matched to the `RIGID_EPS_LADDER` e8 pair, triad odd/eps = -1.486 vs single -1.500, both arms
  individually resolved (z = -7.08 / +6.39). The eps=1.5 arms below were BOTH under the diffusion floor
  (|z| <= 2.7, increment sign fraction ~0.5) — the 0.085 was a ratio of two unresolved quantities. See JOURNAL
  2026-09-12. Original text, for the record:
- **Triad suppresses twirling: TRIAD/SINGLE = 0.085 (12x) — n=1 ONLY.** The single-spring reference
  arm in that same pair came in at odd/eps = -2.10 vs the established ladder's ~-1.38 interpolation
  at eps=1.5, i.e. the reference itself is 50% off at one seed. Needs n>=3.
- **convaz effect on gliding: no trend, n=1, underpowered** (see attenuation above). Not a null.
- **eps=0 derived handedness: UNRESOLVED.** The MSD exponent alpha FAILED its positive control —
  at eps=1.5 where twirl is real (-3.15 turns/um), alpha = 0.96, indistinguishable from the eps=0
  runs. alpha cannot separate rotation from diffusion in this assay; that whole line is retracted.
  Mirror antisymmetry is the estimator with discriminating power.

## RUNNING / QUEUED
- `TRIAD_EPS15` seed 2 (triad twirl suppression -> n=2)
- `HEADTILT_SWEEP` convaz arms, seeds 2-3 (underpowered but worth the error bars)
- NOT started: wide tilt points (-45,-60,+45,+60); tilt x eps>0 (does the biological pose restore
  the twirl the triad suppresses?); convaz at raised `kbind` to beat the attenuation.

## ENVIRONMENT
- `nvidia-smi` is dead: driver/library mismatch (loaded module 595.84 vs on-disk 595.91.07 after a
  package update without reboot). CUDA/PTX compute is UNAFFECTED — runs stay GPU device-resident.
  Reboot at a convenient break. This is NOT the Xid 79 fault, which jba fixed by reseating the card
  (0 events in 12 days).
- GPU currently 4-way contended (~33 steps/s/arm vs ~83 at 2-way).

## FLAGS ADDED THIS SESSION
`-headtilt <deg>` (bound-head orientation tilt), `-convaz <deg>` + `-convaz-nocomp` (converter
azimuth on the head; holds head depth and gammaPsi, compensates the converter arm via
dtheta x 1/cos(b/2)). Both default 0 = byte-identical to the prior model. Both bite-tested ACTIVE.
