# Site-lattice audit for a left-handed (Vilfan) twirl — 2026-11-05

**Question (jba).** Can the canonical site lattice support Vilfan's target-zone twirl at all, or does it remove
it by construction? Desk audit, plus one CPU geometry check and a toy model. No model or code change. No GPU run.

## Bottom line

1. **Handedness is preserved.** `every4` steps +54° per 10.8 nm. That is the right-handed long-pitch strand,
   and the step is well under 180°, so it does not alias. The CPU audit `ChiAwareCaptureAudit -nsite` gives
   +54.000° per site. The site normals are unit, outward and radial, at 3.5 nm, and they are carried by the
   filament. The partner on the second strand (+1 subunit, −166.5°) mirrors correctly (`sbP[33]`).
2. **Site density is half of native, and the reason for it is wrong.** `every4` two-strand keeps subunits 4k and
   4k+1 and drops 4k+2 and 4k+3.
   - The code comment calls the same-strand infill "INSIDE the head" (`ExplicitCompleteMatHarness` around line
     398). Decorated actin is 1:1 with every subunit occupied, so there is no steric exclusion
     (`docs/twirl/ACTIN_SITE_LATTICE_LITERATURE_BASIS.md` §3).
   - That document's §6.1 (2026-08-27) already found the lattice gives **1 site per target zone, not 3**, and
     loses the 5.5 nm doublet that Steffen 2001 measured. It recommended every-subunit sites. That was never
     applied.
3. **Toy model: the sparse lattice weakens Vilfan's attachment bias 2–4×. It does not remove it.**
   - Setup: one motor, filament translating pointed-end first, axial weight with kF8 = 1 pN/nm, angular weight at
     canonical k_ori (α = 5.45). The first-passage ⟨ϑ⟩ at attachment (`scripts/vilfan_lattice_toy.py`):

     | k_A·τ (τ = 36 nm / v) | native | every4 two-strand | ratio |
     |---|---|---|---|
     | 3 | −0.059 | −0.016 | 3.8 |
     | 10 | −0.12 to −0.16 | −0.057 | 2–3 |
     | 30 | −0.25 | −0.13 | 1.9 |

   - The sign reverses under mirroring. Sampling scatter between runs is about ±30 %.
   - The sign is negative in the true gliding direction. By a hand mapping that has not been audited, that is
     left-handed, which is Vilfan's prediction.
4. **A bigger suppressor than the lattice: our effective target zone is about 10× broader than Vilfan's.**
   - Bound-head occupancy by site facing is about 53 % lower, 35 % side and 12 % upper. That holds in both ψ = 0
     low-ATP runs and in canon v4 lawn 04 at saturating ATP.
   - For a wrapped normal, that split implies σ ≈ 83°, i.e. α_eff ≈ 0.5. Vilfan uses α = 4–8, and Steffen
     measured a σ of about 31°.
   - At α = 0.5 the toy bias is about zero on both lattices: +0.010 native, +0.021 every4.
   - Measured from frames (below), the zone is about 85° wide. **Not because of the missing sterics:** bound
     motors' S2 and lever stay outside the actin (frac < 3.5 nm from the axis: 0.00 lower, 0.00 side, 0.02 upper).
     The heads reach the sides and top by the S2 going round the filament, which sits about 28 nm up on a
     40 nm S2. The angular preference belongs to the head, and the free S2 swivel plus the pliant hinge let the
     head point wherever the S2 can take it. Vilfan's preference is fixed to the coverslip.
5. **Duty ratio is not the barrier at 20 µM** (measured below: k_D/k_A ≈ 4, against Vilfan's own 2 at 20 µM).

## Implication for LOWATP_PAIR

As canon stands, the ψ = 0 arm tests a broad-zone version of Vilfan's mechanism, where the toy predicts about
zero bias. A null there would not exclude the mechanism. Even with Vilfan's narrow zone, the toy's expected
bias at our k_A·τ ≈ 1.5 is about −1 to −3°, against a per-lawn resolution of about 1.8°.

## Measurement (2026-11-05, `scripts/capture_zone_rate.py`, existing frames only)

ϑ is the signed azimuth of each bound site (the F8 point) about the filament axis, measured from the
coverslip direction. Frames are 1 ms apart. At 20 µM almost every capture is caught (frame-detected/exact
≈ 0.99). At 2 mM about 70 % are caught (episodes about 1 ms), and k_A is scaled to the exact count.

| run | captures ϑ: lower/side/upper | circular sd | mean ϑ at capture | k_A (/s per unbound motor, base within 30 nm) | bound fraction | k_D/k_A |
|---|---|---|---|---|---|---|
| ψ0 lawn 02, 20 µM d400 | 0.50/0.36/0.14 | 84.5° | +0.9 ± 1.8° | 21.9 | 0.19 | ≈4 |
| ψ0 lawn 03, 20 µM d400 | 0.50/0.35/0.15 | 87.4° | +2.2 ± 1.9° | 21.0 | 0.19 | ≈4 |
| ψ+4 lawn 02 pilot, 20 µM d400 | 0.47/0.37/0.15 | 88.7° | −2.5 ± 2.3° | 22.9 | 0.20 | ≈4 |
| canon v4 lawn 02, 2 mM d1600 | 0.49/0.37/0.15 | 86.9° | −5.3 ± 1.8° | 15.6 | 0.016 | ≈60 |
| canon v4 lawn 03 | 0.52/0.35/0.13 | 82.1° | +0.7 ± 1.7° | 16.1 | 0.017 | ≈60 |
| canon v4 lawn 04 | 0.53/0.33/0.14 | 83.3° | −1.0 ± 1.7° | 15.9 | 0.017 | ≈60 |

- **The zone is about 85° wide everywhere**: at both ATP levels, both ψ, and filament heights of 28 and 40 nm.
  α_eff ≈ 0.5, against Vilfan's 4–8 and Steffen's measured σ of about 31°.
- **k_A is about 22 /s at low ATP and 16 /s at saturating**, and roughly flat out to a base offset of 40 nm,
  falling to zero by 55 nm. Vilfan uses 50 /s. At 20 µM, k_D/k_A ≈ 4 (Vilfan's parameters give 2). At 5 µM it
  would be about 1. **The duty ratio is not what is missing.**
- **No attachment bias is resolved at ψ0** (pooled +1.5 ± 1.3°). The mean-ϑ SE is naive, per capture.
- Bound-path clearance (`scripts/bound_path_clearance.py`, ψ0 lawn 02, every 10th frame): minimum distance of
  the S2 plus lever to the axis, p50 10.6 / 10.6 / 9.6 nm for lower / side / upper. Inside the actin radius:
  0 / 0 / 2 %.

## Possible model changes (each a jba decision; none made)

- **Every-subunit sites** (the literature document's recommendation). Cost: the capture loop scans about 2× the
  sites in its window. Re-baselines engagement.
- **Motor–actin sterics** (built in SoftBox-dock, not canon). The clearance check says they would do little
  for the zone: the bound paths already clear the actin.
- **What would narrow the zone is the orientation stiffness of the motor relative to the coverslip** (the S2
  swivel, the hinge). No measurement exists of how freely HMM heads reach round a filament in a gliding assay.
  Steffen's 31° is S1 adsorbed to a bead (no S2), and Vilfan assumed it. The indirect evidence favours a broad
  zone for tethered myosin (`docs/twirl/HMM_BINDING_AZIMUTH_LITERATURE_2026-11-05.md`).

## Not done

The toy has no 3D reach, no pool depletion and no bound torque, so it ranks the lattices. It does not predict a
twirl. The capture-ϑ SE is naive. The bound-path check covers the S2 and lever, not the head sphere, which
overlaps the actin radius by construction in every class.
