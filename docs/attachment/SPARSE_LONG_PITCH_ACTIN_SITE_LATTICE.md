# Sparse long-pitch actin binding-site lattice

**Actin-side geometry correction + verification. No motor parameter was retuned, no chemistry was changed,
no fitted angular parameter was introduced, no new steric law was added.**

- **Worktree:** `/home/jba/Code/SoftBox` · **Branch:** `gpu-mat-bottlenecks-explicit-singlehead` · base commit `3119000`
- **Date:** 2026-08-12
- **Raw data:** `RUN_LOGS/attachment_audit/sparse_long_pitch_sites/`
- **Figures:** `docs/attachment/figures/sparse_long_pitch_sites/`
- **Predecessors:** `CANONICAL_ACTIN_ATTACHMENT_AUDIT.md` · `PATH_B_SITE_ACCESSIBILITY_TELEMETRY.md` ·
  `FILAMENT_Z_SLAB_AND_ACCESSIBILITY_RERUN.md`

---

## 1. Executive verdict

**The repository already contained an `every4` lattice mode with the right axial rise. It was NOT the desired
effective lattice, for two independent reasons, both now fixed; the sparse long-pitch geometry is verified
numerically and visually from the exact coordinates the capture kernels use.**

| # | finding | status |
|---|---|---|
| 1 | `every4` rise was already `4 x 2.7 = 10.80 nm` and its per-site twist already `4 x (-166.5) = -666 deg = +54 deg` | **already correct — reused, not rebuilt** |
| 2 | the site AZIMUTH was referenced to **each segment's own centre**, so the helix phase **restarted at every segment boundary** (a +22.5 deg step, 11 times along the canonical filament) | **FIXED** — new filament-global convention, flag-gated, legacy default preserved |
| 3 | a site whose global arc landed **exactly** on a segment junction was rounded outside **both** neighbours by float32 and **vanished from the lattice** (3 of 195 sites for `every4`) | **FIXED** — float32-robust membership + clamp, site-aware path only |
| 4 | site-first capture (enumerate real sites → gate against the actual site → bind) already existed from the previous task and was **default-off** | **reused unchanged**, verified against `every4` |
| 5 | the scientific Path-B configuration was `every3` (8.1 nm, -139.5 deg/site) | **switched to sparse `every4` + global phase**; `every3` retained as `-legacy-lattice` |

**Final verified geometry:** one effective site every **10.800 nm** axially, advancing **+54.000 deg** each
step, one 360 deg revolution every **72.000 nm** (6.667 sites), on the actin surface at **R = 3.500 nm** —
**one continuous helix with zero phase discontinuities across all 11 segment boundaries and zero missing or
duplicated sites.** Nearest-neighbour 3-D site separation **11.258 nm**, i.e. **1.61x a nominal 7 nm head
diameter**, so a head footprint contains **exactly one** effective site.

**No hard stop was triggered:** the slab boundary stays on, the harmonic z well was not reintroduced,
site-aware capture never falls back to centreline acceptance, site identity survives attachment, CPU and GPU
choose identical sites, and `invalid = solverFail = 0` in every run reported here.

---

## 2. Why the prior `every3` visualization was conceptually misleading

The `every3` lattice is **not** sparse in the sense that matters. Its numbers, measured from the same code:

| | `every3` (legacy) | `every4` (candidate) |
|---|---|---|
| axial rise | 8.100 nm | **10.800 nm** |
| azimuthal advance per site | **-139.500 deg** | **+54.000 deg** |
| 360 deg repeat | **20.90 nm** (2.581 sites) | **72.00 nm** (6.667 sites) |
| sites on the 2.106 µm filament | 261 | 196 |
| 3-D nearest-neighbour distance | 10.428 nm | 11.258 nm |

A **-139.5 deg** step per site means consecutive sites land almost on opposite sides of the filament and the
sequence closes on itself after **2.58** sites. Rendered, that reads as a *dense ring of surface points
jumping rapidly around the circumference every 8 nm* — which is exactly the picture that prompted this
correction, and it invites the false reading that the motor has many nearby azimuthal alternatives. It does
not; but the visualization made the lattice look like an azimuthally near-isotropic surface rather than a
sparse track.

The **+54 deg** step of `every4` produces the opposite and correct impression: a slow, orderly, sparse
staircase that takes almost 7 sites and 72 nm to come back round — a *long-pitch helical track*.

**The prior visualization was a failed geometry sanity check, not evidence that the site-aware implementation
was unusable.** All of the site-first capture machinery survived intact and is reused verbatim (§7).

---

## 3. Biological abstraction versus literal molecular claim

What is encoded, and only this:

> The motor sees a sparse sequence of discrete effective actin-binding sites following the long-pitch helical
> geometry of F-actin. Usable sites are ~10.8 nm apart axially, and the sequence rotates with the actin
> helical twist. A site is a discrete material-frame point on the actin surface; one head may occupy it.

**Not claimed and not implemented:** an explicit four-monomer cleft, an artificial pocket between spheres, an
atomic actin representation, separate protofilament objects, or any fitted cleft-angle parameter. "One
effective site per four native monomer rises" is an **effective steric/accessibility abstraction** chosen to
reproduce two load-bearing facts — **sterically sparse attachment opportunities** and **the helical rotation
of those opportunities along the filament** — and nothing about the true microscopic location of the myosin
epitope is being asserted.

Everything is derived from the repository's own native actin constants; nothing in the geometry is
hard-coded:

```
Constants.actinMonoRadius            = 2.7000 nm     (native axial monomer rise)
TWIST_PER_MON_DEG                    = -166.5000 deg (actin 13/6, LEFT-handed)
Constants.radius                     = 3.5000 nm     (actin radius; the site surface radius)
rise(every4)  = 4 x actinMonoRadius  = 10.800 nm
dPhi (every4) = 4 x (-166.5 deg)     = -666 deg  =  +54.000 deg (mod 360)
```

---

## 4. Existing `every4` code audit

Traced through `ExplicitCompleteMatHarness.siteRise/siteStairPhase/packExMat`,
`ChiralSiteSystem.siteSnap` (legacy capture) and `ChiralSiteSystem.siteGateA` (site-aware capture).

| Phase-1 question | verdict **before** this task | action |
|---|---|---|
| 1. exactly one candidate site every ~10.8 nm? | **YES** — `rise = 4 x actinMonoRadius`, sites enumerated at `k*rise` | none |
| 2. does each site advance by the native four-monomer phase? | **NO** — the phase was `twistRate*(localArc - halfSeg)`, continuous *within* a segment but restarted at each segment centre | **FIXED** (§4.1) |
| 3. a single sequence, not several tracks at one axial position? | **YES** — one site per integer `k`, one azimuth per `k`; no alternative azimuths exist at a given axial position | none |
| 4. continuous across flexible-filament segment boundaries? | **NO**, twice over: a **+22.5 deg azimuth step** at each of the 11 boundaries, and a **site dropped** wherever a site's global arc coincided with a junction | **FIXED** (§4.1, §4.2) |
| 5. does filament roll rotate the whole sequence in the material frame? | **YES** — the lattice is regenerated each step from live `filUVec`/`filYVec`; nothing per-site is stored | none |
| 6. site identity globally unique and retained through binding? | **YES** — `k = round(globalArc/rise)` from `segCumArc[s] + localArc`, latched into `bindSite`, never re-snapped | none |
| 7. does mirroring reverse handedness correctly? | **YES** — `MIRROR_SIGN = -1` negates the twist rate, the staircase phase and the site tangential sense | none |

So five of the seven properties were already right, and the lattice mode itself was **reused, not rebuilt.**
The two corrections are the smallest that make questions 2 and 4 true.

### 4.1 Correction A — the site azimuth convention

`ChiralSiteSystem`, "SITE AZIMUTH CONVENTION":

```
SEGMENT-RELATIVE (LEGACY, default)   phi(k) = twistRate * (localArc - halfSegLength)
FILAMENT-GLOBAL  (candidate)         phi(k) = k * (twistRate * rise)      wrapped to (-2pi, 2pi)
```

The twist accumulated across one 65-monomer segment is `65 x -166.5 = -10822.5 deg = -22.5 deg (mod 360)` —
not a whole turn — so the legacy reference inserts a constant **+22.5 deg** azimuth discontinuity at every
boundary. The global convention is a pure function of the filament-global site index, so the helix runs
unbroken; for `every4` it is exactly `k x +54 deg`, reproducing the target table.

Selected by `ExplicitCompleteMatHarness.SITE_PHASE_GLOBAL` (`-site-phase global|segment`), packed as
`chiP[25]` / `sbP[25]`. **Default `false`.** With the flag off the added branch is not taken and the legacy
expression is evaluated verbatim, so every pre-2026-08-12 configuration is byte-identical by construction;
the legacy path was re-run as a regression guard (§10).

Both conventions coincide up to one global constant on a single-segment (rigid) filament, which is why the
rigid twirl diagnostics never exposed this.

**Scoping rule (found the hard way).** The convention travels **with the lattice the caller asks for**:
`ChiralSiteHarness.cfg` sets `SITE_PHASE_GLOBAL = (siteMode == PATH_B_SITE_MODE) && PATH_B_PHASE_GLOBAL`. An
earlier version applied the new convention unconditionally, which silently gave the many internal diagnostics
that hard-code `cfg(2, ...)` an `every3` lattice with a *global* phase — a combination that is neither the
legacy nor the candidate geometry — and broke standing fixtures 21 and 24 of the `-fixtures` suite. With the
scoping rule those diagnostics keep the historical segment-relative convention and the suite is back to
**24 PASS / 0 FAIL**. An explicit `-site-lattice every3 -site-phase global` still works, because it makes
`every3` the campaign lattice.

### 4.2 Correction B — a site could vanish at a segment junction

`segCumArc` and `segLength` are `float32`. For the canonical filament `65 x 10.8 nm = 702.0 nm = 4 x 175.5 nm`
exactly, so sites `k = 65, 130, 195` land on junctions — and after float32 rounding they fell *outside* both
neighbours' `la in [0, segLength]` test and disappeared. Measured before the fix: 195 sites instead of 196,
one `d(axial)` gap of 21.6 nm, and a spurious 108 deg azimuth step.

`siteGateA` now admits `la in [-segTol, segLength + segTol]` with
`segTol = SITE_SEG_TOL_UM = 1e-5 µm = 0.01 nm` and clamps `la` into the segment.

`segTol` is **not a physical parameter and is fitted to nothing**: it is ~40x the float32 resolution of the
packed arc at this filament length and 1e-3 of the site spacing. In the same edit the machine-epsilon `g7`
end-exclusion is applied only when a **larger legacy margin** is configured, because its role — half-open arc
ownership — is already served by the global site index on this path. Both neighbours of a junction site may
now offer the same `k`; that is harmless because identity is the global index and the two reconstructions
agree to float32.

**Scope:** the fix is in the **site-aware** capture path only. The legacy `siteSnap` retains the fragility
deliberately, so historical configurations stay byte-identical. This is recorded as a known limitation (§16).

---

## 5. Final sparse-site geometry

From `./scripts/run_chiral_sites.sh -site-geometry`
(`RUN_LOGS/attachment_audit/sparse_long_pitch_sites/P1_site_geometry.txt`):

```
LATTICE: every4 / filament-global phase
  rise = 4 x 2.700 nm = 10.8000 nm  |  d(azimuth)/site = -666.0000 deg (wrapped +54.0000 deg)
  long-pitch repeat (one 360 deg revolution) = 6.667 sites = 72.000 nm
  filament: 12 segments x 175.5000 nm = 2.1060 um contour; 196 effective sites; surface radius 3.500 nm
```

The site frame is unchanged from the validated implementation:

```
uSite = filUVec[s]                                (pointed->barbed material tangent)
nSite = cos(phi)*segY + sin(phi)*segZ             (outward radial material normal, segZ = uSite x segY)
xSite = segCentre + (localArc - half)*uSite + R_actin * nSite
```

so the lattice translates, bends, tumbles and **rolls** with the filament, and nothing per-site is stored.

---

## 6. Numeric spacing/twist verification

Printed by the same generator the kernels use (first 14 of 196 sites; `x` is a lab coordinate, the filament
starts at `x = -1.053 µm` along +x):

| k | seg | gArc nm | localArc nm | azim deg | d azim | x µm | y nm | z nm | d(k-1) nm |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0 | 0 | 0.0000 | 0.0000 | -0.000 | — | -1.05300 | 3.5000 | 0.0000 | — |
| 1 | 0 | 10.8000 | 10.8000 | 54.000 | 54.000 | -1.04220 | 2.0572 | 2.8316 | 11.2579 |
| 2 | 0 | 21.6000 | 21.6000 | 108.000 | 54.000 | -1.03140 | -1.0816 | 3.3287 | 11.2579 |
| 3 | 0 | 32.4000 | 32.4000 | 162.000 | 54.000 | -1.02060 | -3.3287 | 1.0816 | 11.2579 |
| 4 | 0 | 43.2000 | 43.2000 | -144.000 | 54.000 | -1.00980 | -2.8316 | -2.0572 | 11.2579 |
| 5 | 0 | 54.0000 | 54.0000 | -90.000 | 54.000 | -0.99900 | -0.0000 | -3.5000 | 11.2579 |
| 6 | 0 | 64.8000 | 64.8000 | -36.000 | 54.000 | -0.98820 | 2.8316 | -2.0572 | 11.2579 |
| 7 | 0 | 75.6000 | 75.6000 | 18.000 | 54.000 | -0.97740 | 3.3287 | 1.0816 | 11.2579 |
| 8 | 0 | 86.4000 | 86.4000 | 72.000 | 54.000 | -0.96660 | 1.0816 | 3.3287 | 11.2579 |
| 9 | 0 | 97.2000 | 97.2000 | 126.000 | 54.000 | -0.95580 | -2.0572 | 2.8316 | 11.2579 |
| 10 | 0 | 108.0000 | 108.0000 | 180.000 | 54.000 | -0.94500 | -3.5000 | 0.0000 | 11.2579 |
| 11 | 0 | 118.8000 | 118.8000 | -126.000 | 54.000 | -0.93420 | -2.0572 | -2.8316 | 11.2579 |
| 12 | 0 | 129.6000 | 129.6000 | -72.000 | 54.000 | -0.92340 | 1.0816 | -3.3287 | 11.2579 |
| 13 | 0 | 140.4000 | 140.4000 | -18.000 | 54.000 | -0.91260 | 3.3287 | -1.0816 | 11.2579 |

**Acceptance criteria, over all 195 gaps of the whole 2.106 µm filament:**

| criterion | required | measured | verdict |
|---|---|---|---|
| axial spacing | ~10.8 nm | **[10.800000, 10.800000] nm** | PASS |
| azimuthal advance | ~ native four-monomer phase (+54 deg mod 360) | **[+54.000000, +54.000000] deg** | PASS |
| one long-pitch revolution | 70–75 nm | **72.000 nm** (6.667 sites) | PASS |
| surface radius | current actin radius | **3.500 nm** = `Constants.radius` | PASS |
| duplicate / local alternative sites | none | **0 duplicates, 0 index gaps** | PASS |
| azimuth continuity across 11 segment boundaries | continuous | **0 discontinuities, max deviation 0.000000 deg** | PASS |
| axial continuity across boundaries | `d(axial) = rise` | max `|d(axial) - rise| = 1.4e-13 nm` | PASS |

**Host-twin gate.** The table, the figures and the fixtures are produced by `ChiralSiteSystem.sitePhaseHost`,
which is the host twin of the expression inlined in the two capture kernels. It is gated against the kernel
by driving real captures onto interior sites and comparing the latched azimuth:
`|bindAzim(kernel capture) - sitePhaseHost| = 0.000e+00 rad` for every lattice reported here. **The table IS
the code's lattice**, not a re-derivation of it.

---

## 7. Site-aware capture path

The capture order is now, with no post-hoc snap anywhere in it:

```
detached head pose (matBeamGeom)
  -> nearest segment by the existing clamped-closest-point ownership rule
  -> enumerate the +-searchHalf sparse helical sites around the head's perpendicular foot
  -> reject sites approached through the actin interior            (g8, pure geometry)
  -> reject sites failing the distance / preload gates AT THE SITE  (g0, g4)
  -> select the NEAREST surviving site                              (siteGateA)
  -> apply the unchanged stereospecific motor gates                 (g1/g2/g3/g5, siteCommitB)
  -> bind directly to that site, latch (boundSeg, bindArc, bindAzim, bindSite)
  -> exclusive one-head-per-site occupancy                          (siteOccupancyResolve)
```

The legacy `matBindExplicit -> siteSnap` pair (centreline acceptance followed by a snap onto the lattice) is
**not wired** when `SITE_AWARE` is on — verified in both the CPU runner (`stepGlidingCPU`) and the device
graph (`buildGlidingGraph`), where the two are mutually exclusive branches. The banner prints
`bindPath=site-aware` vs `bindPath=centreline+snap(LEGACY)` so a run can never be misattributed.

**The prior task's site-first implementation was reused unchanged and works with `every4` as written** — the
lattice enters it only through `rise`, `twistRate`, `stairPhase`, `Ractin` and the phase flag.

### Phase-7 gate audit — what changed and what did not

| gate | evaluated against | threshold | changed? |
|---|---|---|---|
| eligibility (`active`, `!noBind`, `boundSeg == -1`, `nuc == ADP.Pi`) | motor state | — | no |
| **g8 accessibility** (new) | `nSite . (xF8 - xSite) > -accTol` | `accTol = 1e-3 nm` (machine-scale) | **new, pure geometry, no fitted parameter** |
| **g0 distance** | `|xF8 - xSite|` — the **actual site** | **3.0 nm, unchanged** | **meaning** changed (was distance to the centreline minus `FIL_R`) |
| **g4 preload** | `kF8 * |xF8 - xSite|` — the real F8 bond extension | **2.0 pN, unchanged** | **meaning** changed (was distance to the **axis**) |
| g6 head side | `(xH - segCentre) . eup < A_SEMI[2]` | 2.25 nm | no — verbatim |
| g7 in-segment arc | tolerant membership + clamp; legacy margin honoured only if larger than `segTol` | machine-epsilon | **§4.2** |
| g1 binding-face psi | motor pose only | 25 deg | no |
| g2 neck-lever phi | motor pose only | 25 deg | no |
| g3 converter theta | motor pose only | 20 deg | no |
| g5 energy | motor pose only | 15 kT | no |

**No threshold was retuned and no angular parameter was introduced.** g0 and g4 keep their numerical values;
what changed is that they now measure what their names say — head-to-actin-**surface** separation and the F8
bond extension at capture — instead of a distance to the filament axis. Because the legacy g4 required the
head within 2 nm of the centreline of a 3.5 nm-radius filament (i.e. inside the actin), this is a
**re-baselining** of recruitment, quantified in §12. Every purely motor-internal gate was left alone.

---

## 8. Steric / footprint analysis

Computed over all 196 sites (`-site-geometry`), with a nominal **7 nm** myosin-head diameter used **only as a
comparison scale** — no exclusion law reads it:

| quantity | `every4` (candidate) | `every3` (legacy) |
|---|---|---|
| axial nearest-neighbour separation | 10.800 nm | 8.100 nm |
| circumferential displacement (chord at R = 3.5 nm, `2R sin(dPhi/2)`) | **3.178 nm** | 6.567 nm |
| full 3-D nearest-neighbour distance (`k +- 1`) | **11.258 nm** | 10.428 nm (range 10.061–10.428) |
| distance to the **second**-nearest site — the other immediate neighbour | **11.258 nm** | 10.428 nm |
| distance to the next shell (`k +- 2`) | **22.330 nm** | 16.83 nm |
| ratio (3-D nearest neighbour) / (7 nm head diameter) | **1.61x** | 1.49x |
| max number of OTHER sites within 7 nm of any site | **0** | 0 |

**A head footprint contains exactly one effective site.** The nearest alternative sits 1.61x a head diameter
away and the next shell 3.2x. The sparse lattice therefore already encodes the local steric limitation,
quantitatively and not by assumption.

**Phase-15 answer — no additional steric law is needed, and none was added.** One head per site plus a
10.8 nm rise already produces real discrete sparsity: the maximum number of other sites inside any 7 nm
footprint is **0**, so an extra exclusion radius could only remove sites that are already unreachable. The
continuous 3-D surface steric (`SURF_STERIC`) stays **false** with `SURF_EXCL_NM = 0`, exactly as in the
existing campaign configuration. One honest caveat: at the panel's density the measured site occupancy is
~0.8 % (§12), so exclusivity is rarely the binding constraint there — the sparsity is doing its work through
*availability of reachable sites*, not through contention.

---

## 9. Static fixtures

`./scripts/run_chiral_sites.sh -site-fixtures`
(`RUN_LOGS/attachment_audit/sparse_long_pitch_sites/P10_fixtures_every4.txt`), run on the candidate
configuration (`every4`, filament-global phase, site-aware capture, z slab on), 12-segment filament,
deterministic, no trajectory advanced:

| fixture | test | result |
|---|---|---|
| **A** one reachable site | head 1.0 nm outside a near-side site | bound; **exactly 1** of 13 enumerated sites acceptable — **PASS** |
| **C** outside-approach | head 1.0 nm **inside** the same site | rejected by g8 — **PASS** |
| **C'** far-side, interior approach | far-side site reached through the actin | rejected by g8 — **PASS** |
| **A'** far-side, exterior approach | far-side site approached from outside | **accepted** (accessibility is local geometry, not a forbidden azimuth) — **PASS** |
| **A''** preload | 2.5 nm outside a near-side site | rejected by g4 — **PASS** |
| **D** roll | 90 deg rigid material roll of the filament | site id 8 retained, `bindArc`/`bindAzim` **unchanged**, lab-frame site azimuth rotated **+90.0000 deg** — **PASS** |
| **B** between sites | head midway between k=8 and k=9 (5.40 nm from each) | **0 of 13** sites reachable, nearest 5.796 nm, kernel returns no candidate — **PASS** |
| **E** occupancy | two heads at the same site k=8 | head 0 bound, head 1 **released** — **PASS** |
| **E'** occupancy | two heads at k=8 and k=9 | **both bound** — adjacent sites are separate physical sites — **PASS** |
| **F** segment boundary | whole-filament enumeration | 196 sites, 11 boundary-crossing pairs, **0 duplicates, 0 gaps**, max `|d(axial) - rise|` = 1.4e-13 nm, max azimuth deviation **0.000000 deg** — **PASS** |

Fixture **B** is the sparsity statement made concrete: a head parked exactly between two sites sees **no**
acceptable site at all, not a dense set of artificial alternatives.

---

## 10. CPU/GPU validation

Per `docs/CPU_GPU_VALIDATION_POLICY.md`, a **new hot kernel branch** and a **structural geometry change**
both trigger confirmation. Bounded runs only — no campaign was launched.

| run | configuration | site ids | bind decisions | `bindAzim` | verdict |
|---|---|---|---|---|---|
| `-twirl-equiv -site-aware on` | `every4` global, **1 segment**, 300 device-resident steps | `siteIdMism = 0` | `bindMism = 0` | `max|dAzim| = 0.00e+00` | **PASS** |
| `-twirl-equiv -site-aware on -equiv-segs 12` | `every4` global, **12 segments** (exercises boundaries), 300 steps | `siteIdMism = 0` | `bindMism = 0` | `max|dAzim| = 0.00e+00` | **PASS** |
| `-twirl-equiv -legacy-lattice` (regression guard) | `every3`, segment-relative, **centreline+snap** — the historical path | `siteIdMism = 0` | `bindMism = 0` | `max|dAzim| = 0.00e+00` | **PASS** |

All device-resident, no CPU fallback, `finite = true`, masked-channel Brownian exactly 0 on both runners.
The candidate site IDs, the winner site ID, the accept/reject decision and the retained identity agree
**exactly**; only the downstream float32 mechanics differ at the usual last-bit level
(`max|dFilCoord| <= 8.9e-7 µm`, `max|dCumRoll| <= 1.5e-5 rad`).

Raw: `P11_cpu_gpu_equiv_1seg.txt`, `P11_cpu_gpu_equiv_12seg.txt`, `P14_legacy_regression.txt`.

**Standing-suite regression guard.** `./scripts/run_chiral_sites.sh -fixtures` (the 24 chiral-site mechanism
fixtures — registry couples, askew bind/stroke, moment identities) returns **24 PASS / 0 FAIL**, the same as
before this task. It caught the scoping bug described in §4.1 and was re-run green after the fix.

---

## 11. Geometry figures

All four are plotted by `scripts/plot_sparse_sites.py` **directly from the TSVs the site generator writes**
(`sites_every4_global.tsv`, `filament_every4_global.tsv`, `meta_every4_global.tsv`). No generative graphics,
no molecular art, no decorative actin structure.

| figure | file | what it shows |
|---|---|---|
| **1 — sparse site helix** | `figures/sparse_long_pitch_sites/fig1_sparse_site_helix.png` | translucent actin cylinder (R = 3.5 nm) over 120 nm with only the effective sites plotted and labelled `k = 0..11`; one point every 10.8 nm, progressively rotating |
| **2 — unwrapped lattice (the key verification)** | `fig2_unwrapped_lattice.png` | axial position vs site azimuth for the candidate (top) and the legacy `every3` (middle), segment boundaries marked; **bottom panel removes the wrap**: a single straight sparse diagonal, fitted slope **5.0000 deg/nm ⇒ 360 deg every 72.00 nm**, running unbroken through the boundary |
| **3 — footprint sanity** | `fig3_footprint.png` | four adjacent sites with a 7 nm head-diameter scale bar and 7 nm footprint circles; 10.8 nm axial / 11.26 nm 3-D annotated |
| **4 — axial progression** | `fig4_axial_progression.png` | eight end-on views, `k = 0..7`, making the +54 deg per 10.8 nm advance visually obvious |

Figure 2's bottom panel is the single most decisive image: the sparse diagonal is straight, uniform, and
crosses the segment boundary without a step.

---

## 12. Short `every3` versus `every4` dynamic comparison

**Compatibility panel, not a production campaign.** `./scripts/run_chiral_sites.sh -lattice-compare -gpu
-steps 8000 -seeds 2`, GPU device-resident, 12 arms, 6.1 min wall-clock.
**Identical in both arms:** site-aware capture ON, validated z slab ON, 12-segment filament, filament
Brownian ON, density 400 heads/µm², dt 2.5e-6 s, 8000 steps (20 ms physical), 2 matched seeds,
eps in {0, +15, -15} deg, linear progress ramp. The **only** difference is the actin-side lattice.

`invalid = 0`, `solverFail = 0` in all 12 arms.

**eps-EVEN channel** (gliding / engagement; mean of +eps and -eps, then over seeds; +- is SEM over 2 seeds):

| quantity | `every3` (legacy) | `every4` (sparse) | ratio 4/3 |
|---|---|---|---|
| glide (µm/s) | -1.690 +- 0.095 | **-1.411 +- 0.056** | 0.835 |
| avgBound | 1.850 +- 0.033 | **1.578 +- 0.233** | 0.853 |
| attachment flux (binds/s) | 2483 +- 83 | **2033 +- 0** | 0.819 |
| site occupancy (bound heads / site) | 0.0071 +- 0.0001 | **0.0081 +- 0.0012** | 1.137 |
| filament mean z (nm) | 10.00 +- 3.23 | **10.94 +- 0.49** | 1.094 |
| NEAR bind fraction | 0.363 +- 0.026 | 0.388 +- 0.026 | 1.067 |
| SIDE bind fraction | 0.409 +- 0.007 | 0.375 +- 0.058 | 0.916 |
| FAR bind fraction | 0.227 +- 0.033 | 0.237 +- 0.032 | 1.044 |
| NEAR bound fraction | 0.413 +- 0.058 | 0.407 +- 0.049 | 0.986 |
| SIDE bound fraction | 0.386 +- 0.058 | 0.359 +- 0.036 | 0.929 |
| FAR bound fraction | 0.201 +- 0.000 | 0.234 +- 0.013 | 1.164 |

**eps-ODD channel** (chiral; `(X(+eps) - X(-eps))/2`):

| quantity | `every3` (legacy) | `every4` (sparse) |
|---|---|---|
| tau_odd (N·m) | -4.29e-22 +- 2.3e-22 | -3.62e-22 +- 1.9e-22 |
| Omega_odd (rad/s) | -16.15 +- 3.14 | -9.73 +- 4.96 |
| turns_odd | -3.18e-2 +- 1.7e-2 | -2.64e-2 +- 1.4e-2 |
| glide_odd (µm/s) | -1.06e-1 +- 1.3e-1 | +3.12e-2 +- 2.0e-1 |

**Reading (n = 2 — direction and magnitude only, not significance):**

1. **Recruitment falls ~18 %** and **glide falls ~17 %** on the sparser lattice. Both are expected and
   coherent: `every4` presents **25 % fewer sites** (196 vs 261) and the sparser azimuthal track means a head
   is less often within 3 nm of a site. Site occupancy *rises* 14 %, i.e. the surviving sites are used
   slightly harder — the loss is availability, not per-site efficacy.
2. **Accessibility is essentially unchanged.** All six NEAR/SIDE/FAR fractions move by less than their SEM.
   The lawn-facing preference of the z-slab study survives (NEAR bound 0.41 vs FAR 0.23, against a uniform
   null of 1/3), and the sparse lattice neither creates nor removes it. Attachment **events** remain much
   closer to uniform than bound **time**, exactly as the predecessor report found.
3. **Filament mean z is unchanged** (10.0 -> 10.9 nm, well inside the seed scatter): the slab is intact and
   the height remains motor-selected.
4. **The chiral channel keeps its sign and order of magnitude.** tau_odd and Omega_odd are negative in both
   lattices; `every4` is 0.84x on tau_odd and 0.60x on Omega_odd, both within the 2-seed scatter. **No
   quantitative twirling claim is made from this panel** — it establishes compatibility, not a value.

Raw: `P12_lattice_compare.txt`, records under `compare/`.

---

## 13. Zero-skew observations

> **RESOLVED 2026-08-12 — the lead below was powered and did NOT reproduce.**
> `docs/twirling/ZERO_SKEW_SPARSE_LATTICE_MIRROR_GPU.md` ran the recommended n = 24 matched native/mirror
> pairs at eps = 0 on the GPU. **tau_mirror_odd = −1.60e−23 ± 8.50e−23 N·m (0.19 sigma, 95 % CI includes zero,
> signs exactly 12−/12+, sign-test p = 1.00).** The 2/2 seed reversal recorded below is a small-n artifact:
> the campaign had > 80 % power against its effect size and its 95 % CI **excludes** the pilot value.
> Classification **M0**. Read this section as the pilot that motivated the test, not as a finding.

At **eps = 0** every deliberate skew is off (`CONV_SKEW_DEG = EPS_BIND_DEG = EPS_STROKE_DEG = 0`), so the only
chirality left in the model is **the actin lattice's own helical sense**. Measured without a prior:

| quantity (eps = 0) | `every3` | `every4` | per-seed sign |
|---|---|---|---|
| mean axial deterministic torque tau (N·m) | -5.91e-22 +- 6.4e-23 | -2.85e-22 +- 4.9e-23 | negative 4/4 |
| angular velocity Omega (rad/s) | -42.0 +- 22.0 | -35.9 +- 24.9 | negative 4/4 |
| turns over the measurement window | -9.8e-2 +- 3.7e-2 | -7.6e-2 +- 3.6e-2 | negative 4/4 |
| glide (µm/s) | -1.462 +- 0.316 | -0.921 +- 0.050 | negative 4/4 |
| avgBound | 1.454 +- 0.151 | 1.350 +- 0.150 | — |
| NEAR / SIDE / FAR **bind** fraction | 0.36 / 0.30 / 0.34 | 0.40 / 0.36 / 0.25 | — |
| NEAR / FAR **bound** fraction | 0.367 / 0.189 | 0.366 / 0.262 | — |

**Site-azimuth distribution at eps = 0.** Attachment events are close to the uniform 1/3 null (`every4`
0.40/0.36/0.25 NEAR/SIDE/FAR); bound *time* is enriched NEAR and depleted FAR (0.37/0.26), i.e. the
azimuthal asymmetry lives in **residence**, not in **capture** — the same split the z-slab study reported for
`every3`, and it is not created by the sparse lattice.

**A nonzero mean torque IS present at eps = 0, so the mirror control was run** (a reflection of the lattice,
not new physics): `./scripts/run_chiral_sites.sh -lattice-compare -lattice-mirror -gpu -steps 8000 -seeds 2`,
matched seeds, `MIRROR_SIGN = -1`.

| lattice | quantity | native | mirror | sum | per-seed signs (native/mirror) |
|---|---|---|---|---|---|
| `every3` | tau (N·m) | -5.91e-22 | **+7.80e-23** | -5.13e-22 | `--` `-+` (1 of 2 reversed) |
| `every3` | Omega (rad/s) | -42.0 | -22.9 | -64.9 | `--` `-+` |
| `every4` | tau (N·m) | -2.85e-22 | **+4.45e-22** | +1.60e-22 | `-+` `-+` (**2 of 2 reversed**) |
| `every4` | Omega (rad/s) | -35.9 | -3.69 | -39.6 | `--` `-+` |
| `every4` | turns | -7.62e-2 | -2.26e-2 | -9.88e-2 | `--` `-+` |

**Honest reading — the result is suggestive on torque and unresolved on rotation:**

- For the sparse `every4` lattice the **axial torque reverses sign under reflection in 2 of 2 matched seeds**,
  which is what a genuinely lattice-geometric chiral torque would do. For `every3` the mean flips but only
  1 of 2 seeds reverses.
- The **angular velocity does NOT reverse** in either lattice: it stays negative in the mean and reverses in
  only 1 of 2 seeds, though its magnitude shrinks markedly (`every4` -35.9 -> -3.7 rad/s). At eps = 0 the
  measured |Omega| is *larger* than the eps-odd half-difference of the skewed arms (35.9 vs 9.7 rad/s), which
  says the zero-skew rotation signal is dominated by achiral thermal rotation at this sample size.
- **n = 2 per arm is far too small to conclude anything.** For calibration, the viscosity campaign needed
  n = 24 and *still* found Omega_odd unresolved at the canonical viscosity (1.06 sigma, 58 % seed sign).
  Nothing here is a detection.

**Verdict for Phase 13: realistic binding geometry alone has NOT been shown to generate twirling, and no
physics was added to try to make it.** The torque sign reversal is a lead worth powering properly; the
rotation is consistent with zero. The measurement was taken without bias and the null outcome is reported as
such.

Not measured here: the correlation between selected site azimuth and S2 strain, and any systematic azimuthal
relaxation during attachment/stroke. Both need per-episode telemetry that the compatibility panel does not
collect; they are listed in §17.

---

## 14. Effect on gliding / occupancy / torque, relative to `every3`

| channel | change (`every4` / `every3`) | interpretation |
|---|---|---|
| glide speed | **0.835x** | fewer attachment opportunities per unit length |
| avgBound | **0.853x** | 25 % fewer sites, partly offset by higher per-site use |
| attachment flux | **0.819x** | the dominant carrier of the change |
| site occupancy | **1.137x** | surviving sites are used slightly harder |
| NEAR/SIDE/FAR (bind and bound) | all within SEM | accessibility unaffected by the lattice change |
| filament mean z | 1.094x (within scatter) | slab intact, height still motor-selected |
| tau_odd | 0.84x, same sign | chiral channel preserved |
| Omega_odd | 0.60x, same sign | preserved, within 2-seed scatter |

Nothing changed qualitatively. The sparser lattice costs roughly a sixth of the recruitment and of the glide
speed, and leaves the accessibility structure and the chiral channel intact.

---

## 15. Historical-result implications

**History is not rewritten and no historical campaign was re-run.**

- **All previous Path-B campaigns used `every3` with the segment-relative azimuth convention and the legacy
  centreline-acceptance + snap capture**: the viscosity map and its mirror control, the low-[ATP] transfer,
  the density-occupancy screen, and every twirling / converter-skew arm.
- **Those results remain valid for the model they were computed under.** They are correctly described as
  results of a *dense short-pitch* lattice with a *post-hoc snap*, and the reports that state that should not
  be edited to claim otherwise.
- **Quantitative twirling values must be regenerated before being attributed to the sparse long-pitch
  geometry.** The §12 panel establishes compatibility (same signs, same order of magnitude, no pathology);
  it is explicitly not a replacement value for any published number.
- `-legacy-lattice` reproduces the historical lattice exactly, and with `SITE_AWARE` off it reproduces the
  historical capture path as well; the regression guard in §10 confirms the legacy path still runs and still
  agrees CPU/GPU.

---

## 16. Remaining limitations

1. **The legacy `siteSnap` path retains the §4.2 float32 hole.** Deliberate: fixing it would perturb every
   historical configuration. Any future work that reuses `siteSnap` on a filament whose segment length is a
   near-integer multiple of the site rise will silently lose those sites.
2. **`segTol = 0.01 nm` is a numerical tolerance, not physics.** It is documented as such at both its
   definition and its use, and it scales safely to filaments a few times longer; a filament ~40x longer would
   need it re-derived from the float32 resolution.
3. **n = 2 everywhere in §12/§13.** Sufficient for a compatibility panel and for direction; insufficient for
   any quantitative or chirality claim.
4. **Only the canonical scene was tested** — one density (400 heads/µm²), one viscosity, one S2 length,
   12 segments, 20 ms.
5. **The zero-skew mirror control was run only for eps = 0 at n = 2**, and only for tau, Omega and turns.
6. **`bindAzim` is stored as float32.** The global-phase branch wraps to `(-2pi, 2pi)` before storing, so the
   stored azimuth carries ~1e-7 rad of resolution; the legacy staircase modes never wrapped and store larger
   magnitudes with correspondingly coarser resolution. Not a defect in the candidate path; worth knowing.
7. **No per-episode azimuth/strain correlation was collected** (§13).
8. **The lattice is still one track per filament with no per-monomer state** — no nucleotide, tropomyosin or
   cofilin occupancy on the actin side, by design.

---

## 17. Recommended next scientific step

> **DONE 2026-08-12 — outcome M0 (no effect).** Item 1 below was executed exactly as specified (n = 24, gated
> on per-seed sign reversal of tau) and returned a clean null; item 2's telemetry was collected in the same
> runs and shows **no azimuth→S2-strain coupling**, which explains the null. Item 3 is therefore not
> triggered. See `docs/twirling/ZERO_SKEW_SPARSE_LATTICE_MIRROR_GPU.md` §14 for the superseding next steps
> (η = 0.01 Pa·s; a non-zero registry stiffness `REG_K`).

**Power the zero-skew mirror control.** It is the only measurement in this report that returned a lead rather
than a settled fact, and it addresses the actual scientific goal — whether realistic binding geometry alone
can generate twirling.

Concretely, and in priority order:

1. **eps = 0, native vs mirror, `every4`, n = 16–24 matched seeds, 20 ms** (~2 x 24 x 30 s ≈ 25 min on GPU
   per arm set). Gate on the **per-seed sign reversal of tau**, not on the sum being consistent with zero —
   the standing lesson from the viscosity mirror control is that the `sigma(sum) < 2` criterion is
   weak-power and passes trivially on a noisy measurement.
2. **Add site-azimuth vs S2-strain telemetry to the episode budget** so the mechanism question (does the
   selected azimuth bias the strain the motor develops?) can be answered from the same runs rather than
   inferred.
3. Only if (1) resolves: regenerate the headline twirling numbers on the sparse lattice, and re-state the
   viscosity and low-[ATP] conclusions for the corrected geometry.

**Not recommended:** adding any further steric law (§8 shows the geometry already provides the sparsity),
adding a Vilfan-style angular hazard (a fitted parameter, and out of scope here), or tuning any motor
parameter to move these numbers.

---

## Appendix — commands

```bash
# geometry verification + numeric table + figure data (no trajectory, no GPU)
./scripts/run_chiral_sites.sh -site-geometry

# figures, straight from the emitted coordinates
python3 scripts/plot_sparse_sites.py

# static capture fixtures A-F on the candidate configuration
./scripts/run_chiral_sites.sh -site-fixtures

# CPU/GPU equivalence of site enumeration + capture decisions
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -twirl-equiv -site-aware on -gpu
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -twirl-equiv -site-aware on -equiv-segs 12 -gpu
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -twirl-equiv -legacy-lattice -gpu   # regression

# bounded every3-vs-every4 compatibility panel + the zero-skew mirror control
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -lattice-compare -gpu -steps 8000 -seeds 2
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -lattice-compare -lattice-mirror -gpu -steps 8000 -seeds 2
./scripts/run_chiral_sites.sh -lattice-compare-report -steps 8000 -seeds 2   # re-report from records

# the whole candidate Path-B geometry in one switch
./scripts/run_chiral_sites.sh -path-b-candidate ...    # = -site-lattice every4 -site-phase global -site-aware on -z-slab on
# the historical configuration
./scripts/run_chiral_sites.sh -legacy-lattice ...      # = every3 + segment-relative phase
```
