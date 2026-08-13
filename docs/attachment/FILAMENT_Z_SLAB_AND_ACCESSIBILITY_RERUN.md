# Filament z-boundary upgrade (hard slab) + Path-B accessibility re-audit

**Boundary-condition change + A/B re-audit. The attachment gate, `siteSnap`, the 12 nm capture veto, site
exclusivity, motor anchors, the reference F8 plane, chemistry, RNG ordering and every motor parameter are
UNCHANGED.** The one physics change is the filament's own vertical boundary condition, and it is **default-off**.

- **Worktree:** `/home/jba/Code/SoftBox` · **Branch:** `gpu-mat-bottlenecks-explicit-singlehead` · base `3119000`
- **Date:** 2026-08-12 · **Raw data:** `RUN_LOGS/attachment_audit/z_slab/`
- **Predecessors:** `CANONICAL_ACTIN_ATTACHMENT_AUDIT.md` · `PATH_B_SITE_ACCESSIBILITY_TELEMETRY.md`

---

## 1. Executive verdict

**Z1–Z2 (modest-to-clear emergent height selection) · accessibility stays A1 · an explicit site-level
accessibility rule IS still required.**

1. **The motor ensemble does choose a height, and it is not zero.** With the harmonic well removed, mean
   z_COM = **+11.11 nm** (arm range +10.26…+12.21, SD ≈ 11.4 nm) — well above the old pinned 0 ± 1.4 nm, and
   far below the +30 nm midpoint a *free* filament would relax to. The height is **motor-selected, not
   wall-imposed**.
2. **The walls barely act.** Lower wall **0.325 %** of segment-steps, upper wall **0.016 %**. Not Z3, not Z4.
3. **Far-side occupancy fell — but only modestly.** FAR bound-time **0.3213 → 0.2701**, a **−16.0 % relative
   change at 5.26 σ**. It did **not** collapse. NEAR rose 0.2622 → 0.3439 (+31.1 %, 5.38 σ).
4. **Attachment events did not change at all.** FAR bind fraction 0.3310 → 0.3376 (+2.0 %, **0.50 σ**);
   NEAR 0.44 σ; SIDE 0.10 σ. **Attachment remains statistically uniform around the circumference.**
5. **Chiral torque did not change materially** — τ_odd −1.636e−17 → −1.274e−17, a −22 % shift at **0.55 σ**.
   It did, however, become **better resolved** (2.53 σ → **3.85 σ**, 7/8 seed sign agreement), because
   removing the artificial pin roughly halved the seed-to-seed SD.

**Therefore: the previous near/far symmetry was NOT primarily caused by the harmonic z pinning.** Freeing the
height sharpened the lawn-facing preference from **1.14× to 1.61×** and moved ~5 points of bound time from FAR
to NEAR, but left the *attachment* distribution uniform and left 27 % of bound time on far-side sites. **A
site-level accessibility rule remains a real geometric gap.**

**Recommendation: adopt the slab for future Path-B work** (§17), and keep it default-off for Path A pending a
test.

---

## 2. Previous z-boundary behaviour (Phase A0)

Traced from the built scene, not assumed (`run_chiral_sites.sh -z-audit`;
`RUN_LOGS/attachment_audit/z_slab/A0_z_geometry_audit.txt`).

**The filament interior potential — the only term this task replaces:**

`MatSoaSlice.matZConfine` applies `Fz = −kz·z_com` **per segment at the centre**, with
`kz = G4_KZ = 2.0 pN/nm`. So **z = 0 was an energetic minimum**, and the thermal pin was

> RMS z = sqrt(kT/kz) = **1.435 nm** — *less than half the 3.5 nm actin radius.*

That is tight enough to hold the filament's centreline within ±1.4 nm of the plane containing every motor's
reference F8 point, which is exactly the geometry that made the top and bottom of the filament equidistant.

**Motor-side terms — traced and PRESERVED (none touched):**

| term | value | scope |
|---|---|---|
| S2 emergence plane `⟨z(g4E)⟩` | **−9.93 nm** — the *lawn plane* | motors project from here |
| pivot/anchor plane `⟨z(A)⟩` | −9.93 nm | fixed, never integrated |
| motor-body assembly z | −50.00 nm (`MANCHOR_Z`) | `assembleArticulated` |
| S2 beam-node floor `g4floorZ` | −59.93 nm, k = **20 pN/nm** | one-sided, **beam nodes only** |
| reference F8 plane | z = 0 by construction | unchanged |

**Filament drag/diffusion at the production condition** (η = 0.01 Pa·s, dt = 2.5e−7 s, 12 × 0.1755 µm):
γ_par = 3.649e−9, γ_perp = 5.430e−9 N·s/m; per-step z displacement **0.046 nm per pN**; D_z = 7.581e−13 m²/s
⇒ a *free* segment would explore RMS 174 nm in 20 ms.

---

## 3. New wall / slab definition (Phase A1)

New kernel `MatSoaSlice.matZSlab` (default-off; `ExplicitCompleteMatHarness.Z_SLAB`).

**Interior: exactly flat.** When neither wall is engaged the accumulator is **not written at all** — no `+= 0`.
Verified to `max |Fz| = 0.000e+00 N` over 36 probed heights (§5).

**Walls act on the SEGMENT SURFACE, not the centre.** For a capped cylinder of half-length h, radius R, axis û,
the exact z half-extent is

```
ext = h·|u_z| + R·sqrt(1 − u_z²)
```

(the maximum over the surface of `t·u_z + r(cosθ·e1_z + sinθ·e2_z)` with `e1_z² + e2_z² = 1 − u_z²`). This is
**exact for a straight segment**, hence conservative for any tilt; chain bending is covered because every
segment is tested independently.

**Wall law — the in-repo fracMove idiom, not a new fitted stiffness:**

```
F = frac · pen · γ_perp / (1e6 · dt)
```

which removes a fixed **fraction** of the penetration per step, because the integrator advances z by
`1e6·F·dt/γ`. It is therefore **automatically dt- and viscosity-consistent** (the project's single-source-dt
rule), carries no biological content, and is the same construction `ContainmentSystem` and v1's box law use.

**The reaction is a PURE z FORCE** applied through the segment's force accumulator — **no torque, no tangential
component** — so a wall contact cannot inject tangential or angular momentum *by construction*.

**`frac = 0.9`, anchored on an existing in-repo value.** At the production dt/η this gives
`k_eff = 19.55 pN/nm`, matching the **20 pN/nm substrate floor already used for the motor-side S2 beam**
(`g4kfloor`). It was raised from an initial 0.5 (k_eff 10.9 pN/nm) when the p99 penetration marginally missed
tolerance — **the gate was not relaxed to make it pass**; the wall was stiffened onto an independent anchor.

---

## 4. Wall geometry and dimensions (Phase A2)

| | surface limit | centreline limit |
|---|---|---|
| **lower wall** | lawn plane = **−9.93 nm** | −6.43 nm |
| **upper wall** | lawn + 80 nm = **+70.07 nm** | +66.57 nm |

Free centreline travel **73.0 nm**; the filament is initialized (unchanged) at **z = 0**, i.e. 6.43 nm above the
floor.

**Sizing rationale, from measured quantities and stated before the runs.** The lower wall is the plane the
motors project from — actin below it would be inside the lawn. The upper wall is declared a **practical
simulation boundary, not a chamber thickness**: the free S2 contour is 40 nm, so the engageable band is of that
order, and 80 nm sits well above it while still bounding escape (a free filament would otherwise explore
±174 nm in 20 ms). **Outcome check (§7): the upper wall was hit on 0.016 % of segment-steps — the intended
"rarely touched" regime — so the slab was never widened or narrowed after the fact.**

Deterministic compliance at this wall: 0.051 / 0.256 / 0.512 / 1.023 nm penetration under a constant
1 / 5 / 10 / 20 pN push.

---

## 5. Free-filament validation (Phase A5) — **PASS**

`run_chiral_sites.sh -z-free` (`A5_free_filament_validation.txt`). Binding disabled, so motors exert exactly
zero force on the filament and density is a pure cost multiplier (run at 10 heads/µm²; filament physics
identical).

| gate | result |
|---|---|
| **A5.1/A5.7 interior flatness** | 36 heights over [−6.43, +66.57] nm: **max \|Fz\| = 0.000e+00 N** — PASS |
| **A5.4 lower-wall contact** | 2 nm penetration ⇒ Fz = +3.909e−11 N (upward) — PASS |
| **A5.5 upper-wall contact** | 2 nm penetration ⇒ Fz = −3.909e−11 N (downward) — PASS |
| **no-contact control** | 1 nm inside the floor ⇒ Fz = **exactly 0.0** — PASS |
| **A5.6 tilt/bend** | 45° segment whose centre is legal-if-flat: z half-extent 64.52 nm vs 3.50 nm flat ⇒ the surface rule catches it, Fz = +1.173e−09 N — PASS |
| **A5.3 unbiased diffusion** | centre start: mean drift **−2.45 ± 4.77 nm ⇒ zero** within 2.5 SEM — PASS. Single-seed RMS **11.69 nm** vs the old well's **1.43 nm** pin |
| **A5.2 no restoring potential (control)** | z = 0 start drifts **+23.43 ± 4.48 nm** toward the +30.1 nm slab midpoint — reflecting-boundary relaxation, **not** a potential |
| **non-penetration** | median 0.370 nm, **p99 1.719 nm**, max lower 2.634 / upper 2.288 nm; tolerance = one Brownian z step (0.616 nm), p99 < 3 steps — PASS |
| **A5.8 dt robustness** | 10 pN steady penetration 0.512 nm at dt, **0.256 nm at dt/2** — the wall **stiffens** under refinement, never softens — PASS |

**Method note (an error I made and corrected).** The diffusion gate initially failed with +23 nm drift. That
was a **mis-specified gate, not a defect**: an unbiased confined walk started 6.4 nm above the floor and
66.6 nm below the ceiling *must* relax toward the midpoint. The gate now starts at the slab centre; the z = 0
start is retained as the documented control above. Interior flatness — the decisive test — was exact throughout.

---

## 6. CPU/GPU validation (Phase A5.9) — **PASS**

`matZSlab` is a single `static` method invoked identically by `stepGlidingCPU` and the `zslab` graph task, so
CPU and GPU share the code by construction. Measured on a matched 20 000-step production arm
(`A5_9_cpu_gpu_wall_check_{cpu,gpu}.txt`):

| quantity | GPU | CPU |
|---|---|---|
| glide (ε=0) | −3.566 µm/s | −3.566 µm/s |
| Ω_fit | −438.93 rad/s | −438.93 rad/s |
| avgBound | 6.14 | 6.14 |
| τ | −1.1707e−21 | −1.1707e−21 |
| mean z_COM | +4.300 nm | +4.300 nm |
| z SD / range | 4.196 nm / [−3.988, +15.241] | identical |
| lower-wall contact | 0.4956 % | 0.4956 % |
| mean wall reaction | 8.5771e−12 N | 8.5773e−12 N |

Identical to printed precision on every channel across all three arms (ε = 0/+15/−15).

---

## 7. Motor-bound emergent height (Phase A6)

Production configuration, n = 8 seeds × 3 arms × 20 ms (`B_rerun_console.txt`):

| quantity | value |
|---|---|
| **mean z_COM** | **+11.11 nm** (arm range +10.26 … +12.21) |
| z SD | 11.36 nm |
| observed excursion | [−5.78, +64.94] nm (slab allows −6.43 … +66.57) |
| **lower-wall contact** | **0.325 %** of segment-steps (mean pen 0.478 nm, max 3.20 nm) |
| **upper-wall contact** | **0.016 %** of segment-steps (mean pen 0.51 nm, max 3.13 nm) |

Three reference points make this a genuine *selection*:

- the **old** boundary pinned it at 0 ± 1.4 nm;
- a **free** filament in this slab equilibrates to the **+30 nm** midpoint (§5);
- the motors hold it at **+11 nm**, i.e. between the two and much closer to the lawn.

The 20 ms pilot at n = 1 gave +4.3 to +8.5 nm; the n = 8 20 ms means are +10.3 to +12.2 nm. The difference is
the slow upward relaxation from the z = 0 start — the height is still equilibrating on the 20 ms timescale, so
**+11 nm should be read as a lower bound on the settled height, not a converged value.** A longer run would
sharpen it; it is not needed for the accessibility question.

---

## 8. Vertical force balance (Phase A7)

| channel | value |
|---|---|
| mean motor Fz on the filament | **−3.29e−13 N = −0.329 pN** (net **downward**, toward the lawn) |
| mean wall reaction | **+3.46e−13 N** (lower +3.65e−13, upper +1.88e−14) |
| upper-wall share of the reaction | **5 %** |

The bound motor ensemble pulls actin **toward** the lawn with ~0.33 pN, and that is balanced almost entirely by
the **lower** wall. So the vertical operating point is set by *motor pull down vs steric floor*, with Brownian
motion supplying the 11 nm spread — exactly the intended physics, and the upper wall is nearly irrelevant.

---

## 9. Old vs new: attachment-event distribution

Paired on the same 8 seeds; seed is the independent unit (`B4_old_vs_new.txt`).

| quantity | OLD (harmonic) | NEW (slab) | paired diff | rel | σ |
|---|---|---|---|---|---|
| attachment fraction NEAR | 0.3351 ± 0.0050 | 0.3296 ± 0.0104 | −0.0055 ± 0.0125 | −1.6 % | 0.44 |
| attachment fraction SIDE | 0.3340 ± 0.0071 | 0.3329 ± 0.0064 | −0.0011 ± 0.0105 | −0.3 % | 0.10 |
| **attachment fraction FAR** | **0.3310 ± 0.0092** | **0.3376 ± 0.0087** | +0.0066 ± 0.0132 | +2.0 % | **0.50** |

**Attachment is still statistically uniform around the circumference, and did not move.** This is the single
most important line in the report: the *capture* step is azimuth-blind, and freeing the height did not change
that, because the gate never reads azimuth at all.

**Continuous β distribution (pooled over arms; β = 0 ⇒ normal points away from the lawn; null = 5.556 %):**

| β (deg) | OLD | NEW | Δ |
|---:|---:|---:|---:|
| −170 | 4.068 | 5.408 | +1.340 |
| −150 | 4.323 | 5.790 | +1.467 |
| −130 | 4.826 | 6.188 | +1.362 |
| −110 | 5.852 | 7.039 | +1.187 |
| −90 | 7.975 | 7.035 | −0.940 |
| −70 | 7.562 | 5.743 | −1.818 |
| −50 | 5.947 | 4.764 | −1.183 |
| −30 | 5.085 | 4.474 | −0.611 |
| −10 | 5.038 | 4.240 | −0.798 |
| +10 | 5.026 | 4.466 | −0.560 |
| +30 | 5.131 | 4.342 | −0.789 |
| +50 | 5.859 | 4.751 | −1.108 |
| +70 | 7.134 | 5.557 | −1.577 |
| +90 | 7.533 | 6.897 | −0.636 |
| +110 | 5.617 | 6.750 | +1.132 |
| +130 | 4.478 | 5.893 | +1.415 |
| +150 | 4.251 | 5.336 | +1.085 |
| +170 | 4.295 | 5.327 | +1.031 |

| | OLD | NEW |
|---|---|---|
| away-from-lawn hemisphere | 46.78 % | **38.34 %** |
| toward-lawn hemisphere | 53.22 % | **61.66 %** |
| **ratio** | **1.138×** | **1.608×** |

**The shape changed qualitatively.** OLD was *lateral-bimodal and pole-symmetric* (peaks at ±70…±90°, troughs
at BOTH poles). NEW is *lawn-hemisphere enriched*: every bin with |β| > 90° rose by ~+1.0 to +1.5 points and
every bin with |β| < 90° fell. **The lateral bimodality weakened and a genuine near-side preference appeared —
but only to 1.61×, not to exclusion.**

---

## 10. Old vs new: bound occupancy

| quantity | OLD | NEW | paired diff | rel | σ |
|---|---|---|---|---|---|
| occupancy NEAR | 0.2622 ± 0.0057 | 0.3439 ± 0.0129 | +0.0817 ± 0.0152 | **+31.1 %** | **5.38** |
| occupancy SIDE | 0.4165 ± 0.0050 | 0.3861 ± 0.0074 | −0.0304 ± 0.0085 | −7.3 % | **3.58** |
| **occupancy FAR** | **0.3213 ± 0.0058** | **0.2701 ± 0.0076** | **−0.0513 ± 0.0097** | **−16.0 %** | **5.26** |

Supporting changes:

| quantity | OLD | NEW | rel | σ |
|---|---|---|---|---|
| avgBound | 6.117 ± 0.100 | 5.257 ± 0.270 | −14.0 % | 2.85 |
| attachment events (total) | 442.0 ± 9.9 | 375.3 ± 20.9 | −15.1 % | 3.17 |
| mean residence (ms) | 0.6253 ± 0.0085 | 0.6341 ± 0.0086 | +1.4 % | 0.73 |
| glide (µm/s) | −4.919 ± 0.204 | −3.869 ± 0.397 | −21.3 % in magnitude | 2.37 |

**Reading:** occupancy redistributed from FAR/SIDE toward NEAR, at 5 σ. Engagement fell ~14–15 % overall
(fewer attachments, unchanged residence) and glide slowed ~21 % — the filament now sits ~11 nm higher, so a
motor must reach further, which costs recruitment. **This is a real, physically sensible consequence of freeing
the height, and it means the slab is not a free change: it re-baselines avgBound, attachment flux and glide.**

---

## 11. Old vs new: torque

| quantity | OLD | NEW | paired diff | rel | σ |
|---|---|---|---|---|---|
| τ_odd NEAR (1e−18 N·m) | −5.896 ± 3.921 | −6.206 ± 2.031 | −0.310 ± 3.648 | +5.3 % | 0.08 |
| τ_odd SIDE | −7.578 ± 1.682 | −2.952 ± 1.294 | +4.627 ± 1.943 | −61.0 % | 2.38 |
| τ_odd FAR | −2.888 ± 2.252 | −3.580 ± 4.089 | −0.693 ± 5.374 | +24.0 % | 0.13 |
| **τ_odd TOTAL** | **−16.362 ± 6.467** | **−12.738 ± 3.310** | **+3.624 ± 6.603** | **−22.2 %** | **0.55** |

- **The chiral torque did not change materially** (0.55 σ). Its ~22 % reduction is not resolved.
- **It became better resolved:** significance rose **2.53 σ → 3.85 σ**, sign agreement 6/8 → **7/8**, and the
  seed SD roughly halved. Removing an artificial pin *reduced* the variance of the chiral observable.
- The only resolved per-class change is **SIDE −61 % (2.38 σ)**, consistent with SIDE losing occupancy.
- **FAR share of τ_odd rose 0.177 → 0.281**, and the FAR-class torque is now **0.88 σ / 5-of-8** — i.e. *less*
  resolved than before. Far-side bonds remain a minority, same-signed, unresolved contributor.
- eps-EVEN background −1.96e−18 ± 5.00e−18 and the independent ε = 0 control −2.27e−18 ± 5.56e−18 are both
  consistent with zero — a cleaner achiral baseline than the old boundary gave.

---

## 12. Height–accessibility coupling

The class-fraction-versus-z_COM cross-tab is written per arm in `B_rerun_console.txt`. The pooled picture is
consistent with the §9/§10 result: the NEAR fraction rises and the FAR fraction falls as z_COM increases, which
is the expected geometry (lifting the filament off the lawn puts its lower surface closer to the head's
approach height than its upper surface). The effect is **monotone but shallow** — over the sampled 11 ± 11 nm
band it moves the FAR fraction by only a few points, which is why a **+11 nm** height shift bought only a
**−5-point** change in far-side occupancy.

**Extrapolation, stated as such:** to suppress far-side binding geometrically would require the filament to sit
high enough that a head at the reference F8 plane cannot reach the upper surface at all. That is a *tens of nm*
displacement, not 11 nm, and the motors do not produce it — they pull **down** (§8).

---

## 13. Z0–Z5 classification

**Z1–Z2, closer to Z2.**

- Not **Z0**: the height is not centred near 0 — it moved to +11.11 nm and the distribution is broad
  (SD 11.4 nm), against an old pin of 0 ± 1.4 nm.
- **Z1/Z2**: motor binding shifts the mean height substantially and measurably changes accessibility
  (occupancy at 5 σ), but accessibility remains **broadly symmetric** (attachment uniform; hemisphere ratio only
  1.61×). It sits between the two definitions: the height selection is clear (Z2-like), the accessibility
  consequence is partial (Z1-like).
- Not **Z3**: the walls act on 0.325 % (lower) / **0.016 %** (upper) of segment-steps and the upper wall carries
  5 % of the reaction. The height is set by motors, not walls.
- Not **Z4**: the filament never approached escape; peak excursion +64.9 nm against a +66.6 nm ceiling occurred
  in the tail, and 99.98 % of segment-steps were free of the upper wall.
- Not **Z5**: resolved.

**Caveat:** §7's +11 nm is still relaxing at 20 ms, so the settled height may be somewhat higher. That would
push the classification toward Z2, not away from it.

---

## 14. A0–A5 reclassification

**A1 — PRESENT BUT MOSTLY INERT — unchanged.**

- Far-side attachment is still not rare: **33.8 % of events, 27.0 % of bound time** (was 33.1 % / 32.1 %).
- Far-side bonds still carry the **same-signed** chiral torque; they neither oppose (not A2) nor dominate
  (not A4).
- Their torque share rose to 28.1 % but is **less** resolved than before (0.88 σ, 5/8), so they are not
  established as amplifying either (not A3).
- Removing their instantaneous contribution now retains **71.9 %** of τ_odd (was 82.4 %) — still an accounting
  attribution, not a prediction.

**What changed is the diagnosis of the CAUSE, not the classification.** The previous report identified the
harmonic pin plus the centreline-height F8 plane as the likely origin of near/far symmetry. **The pin was only
a minor contributor:** removing it entirely, and letting the motors choose the height, moved far-side occupancy
by 5 points and left far-side *attachment* untouched. The dominant cause is therefore the remaining piece —
**the capture gate never reads azimuth, so accessibility is decided before any site exists.**

---

## 15. Publication implications

- **Twirling sign, mechanism and mirror behaviour: unaffected**, and the chiral torque is now *better*
  resolved (3.85 σ vs 2.53 σ) under a more defensible boundary condition.
- **τ_odd magnitude: robust to the boundary condition** at the 22 % / 0.55 σ level. Combined with the previous
  audit's ~18 % far-side accounting, the honest statement remains that Path-B torque magnitudes carry a
  ~20–30 % systematic geometric uncertainty.
- **Occupancy and glide are NOT robust to the boundary condition**: avgBound −14 %, attachment flux −15 %,
  glide −21 %. Any published number of these kinds is conditional on the harmonic boundary. In particular the
  **Vilfan-bracket occupancy comparison** (`LOW_ATP_DENSITY_OCCUPANCY.md`) would shift downward under the slab —
  which, as before, moves N_b *further below* the bracket and strengthens rather than weakens D1.
- **The far-side caveat stands, at 27 % instead of 32 %.**

---

## 16. Recommendation on an explicit site-level accessibility rule

**Still needed.** The decision logic from the task resolves cleanly:

- far-side occupancy did **not** fall strongly (−16 % relative; 27 % remains);
- far-side **attachment** did not fall at all (0.50 σ);
- the filament did **not** migrate until a wall dominated (0.016 % upper-wall contact).

⇒ the middle branch applies: **the missing site-level accessibility logic is a real geometric gap that free
vertical motion does not close.** The reason is now precisely understood and is not about height: the 8-gate
capture test is evaluated against the **clamped centreline point** and never sees a site, so no amount of
vertical freedom can make capture azimuth-aware.

**Smallest sufficient next step (proposal only, not implemented):** move site selection inside the gate and add
the geometry-derived approach condition `n̂_site·(x_F8 − x_site) > 0` **evaluated at capture** (sign verified in
`PATH_B_SITE_ACCESSIBILITY_TELEMETRY.md` §2.4; it must not be applied as a standing test to bound heads, where
the F8 spring drives that quantity to ≈ 0). No new fitted parameter. Do **not** add a Vilfan angular hazard.

---

## 17. Recommendation on Path-A adoption

**NEEDS TEST — do not adopt for Path A in this task, and do not change the global default.**

The slab is *physically* at least as appropriate for Path A (a harmonic well pinning actin to a preferred
height is no more defensible for centreline binding than for surface binding). But:

- Path A's frozen density-saturation results were all produced under the harmonic well;
- the slab changes **avgBound −14 %, attachment flux −15 %, glide −21 %** on Path B, and Path A's headline
  observable *is* velocity-versus-density — so adoption would re-baseline the entire frozen sweep;
- Path A binds on the **centreline**, so it gains none of the accessibility benefit that motivates the change
  here.

The change is therefore **default-off** (`Z_SLAB = false` ⇒ `matZConfine` is wired exactly as before,
byte-identical) and is selected explicitly with `-z-slab on`. A bounded Path-A spot check at one density would
be the right next step if adoption is ever considered; it was **not** run here, and no Path-A sweep was launched.

---

## Appendix — reproduction and code

```
./scripts/run_chiral_sites.sh -z-audit -eta 0.01                      # A0/A2 geometry trace + wall sizing
./scripts/run_chiral_sites.sh -z-free  -eta 0.01                      # A5 free-filament gates
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -access-inert -gpu -z-slab on -eta 0.01 -steps 4000 -seed 101
./scripts/run_gpu_monitored.sh ./scripts/run_chiral_sites.sh -access-audit -gpu -z-slab on -eta 0.01 -steps 80000 -seeds 8 -seed 101 -density 400
python3 scripts/access_pool.py    RUN_LOGS/attachment_audit/z_slab/per_seed_zslab_s101_108.tsv
python3 scripts/access_compare.py --old RUN_LOGS/attachment_audit/path_b_accessibility/per_seed_s101_102.tsv \
                                        RUN_LOGS/attachment_audit/path_b_accessibility/per_seed_s103_108.tsv \
                                  --new RUN_LOGS/attachment_audit/z_slab/per_seed_zslab_s101_108.tsv
```

**Code added:** `MatSoaSlice.matZSlab` (new kernel, wired only when `Z_SLAB`); `ExplicitCompleteMatHarness`
`Z_SLAB`/`Z_SLAB_LO_NM`/`Z_SLAB_HI_NM`/`Z_SLAB_FRAC`/`Z_LAWN_UM` + `e.zsP` + the conditional CPU/GPU wiring;
`ChiralSiteHarness` `-z-slab`/`-z-slab-lo-nm`/`-z-slab-hi-nm`/`-z-audit`/`-z-free` + height/wall telemetry in
`AccessTel`; `scripts/access_compare.py`. **`Z_SLAB=false` reproduces the legacy path exactly** — the harmonic
task is wired unchanged and no new buffer is transferred.
