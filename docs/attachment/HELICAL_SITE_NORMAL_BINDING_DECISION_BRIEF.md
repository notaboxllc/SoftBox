# Helical-site normal binding — decision brief

**Status: Phase 0/1 complete, implementation NOT started. Two decisions are required before any physics is
changed. Nothing in the repository has been modified except the addition of a read-only audit mode.**

- **Worktree:** `/home/jba/Code/SoftBox` · **Branch:** `gpu-mat-bottlenecks-explicit-singlehead` · base commit `3119000`
- **Date:** 2026-08-12
- **Console evidence:** `RUN_LOGS/attachment_audit/helical_site_normal_binding/P0_provenance_audit.txt`
- **Reproduce:** `./scripts/run_chiral_sites.sh -normal-audit`
- **Predecessor:** `docs/attachment/BOUND_MOTOR_HELICAL_GEOMETRY_VISUAL_AUDIT.md`
- **To be superseded by:** `docs/attachment/HELICAL_SITE_NORMAL_BINDING_AND_TORQUE.md` once decided.

---

## 1. Headline

**The intended law was never lost. It is present, it constrains exactly the vector we thought it did, and it
is still running today — but it is referenced to a lab-fixed direction instead of to the bound site, and it
returns no reaction to actin.**

**However, restoring it literally is blocked by a kinematic fact:** the motor head's `eBind` direction is
confined to a single lab-fixed plane, so a strict `dot(eBind, n_site) ≥ cos 25°` gate is **unsatisfiable for
70 % of the lattice by construction**. That is Decision 1.

---

## 2. What is settled — measured, not inferred

### 2.1 `ψ` *is* the orientation angle of `eBind`

Analytic identity from `TwoBodyConverterMotor.geomC` / `geom2D`:

```
xF8 − xH = R_econv(ψ) · ( b̂·rF8x + ê_up·rF8y )      ⇒   eBind = R_econv(ψ) · p̂1
p̂1 = normalize(b̂·rF8x + ê_up·rF8y) = (+0.9191, 0.0000, +0.3939)
```

Gated against the **live `matBeamGeom` kernel** at ψ ∈ {0, 0.37, −0.61}:

```
max | eBind(kernel) − eBind(analytic) |  =  1.396e-14      ⇒ IDENTITY CONFIRMED
```

So `g1 = |ψ − ψ_actin| < ψ_deg` is genuinely an **eBind orientation gate**, not a proxy for one. This settles
Phase 4: the historical law and the intended law constrain **the same degree of freedom**, so the correct
action is to *retarget* `g1`, not to add a second gate beside it. Adding a separate gate would
double-constrain ψ.

### 2.2 Both parameters exist and have provenance (Phase 15)

| parameter | value | units | code location | original purpose | reuse |
|---|---|---|---|---|---|
| capture angular tolerance | **25** | deg | `TwoBodyConverterMotor.Tol.psiDeg` → packed `bindP[1]`; consumed by `gatePasses` g1, `MatSoaSlice.matGeomGate`, `ChiralSiteSystem.siteCommitB` | Exp 3C/3D/3E stereospecific binding capture; `GATE_NAMES[1] = "orient(ψ)"`, commented *"binding-face orientation"* | **unchanged** |
| bound restoring stiffness | **512** (= 5.12e-19 N·m/rad²) | pN·nm/rad² | `TwoBodyConverterMotor.build3core(…, kbindPN = 512, …)` via `buildGlide2D`; `KAPPA_CODE = 1e-21`; consumed as `params[7N+m]` | the bound head's angular elasticity about its binding-face orientation; also the `g5` energy term | **unchanged** |
| companion converter stiffness | 128 | pN·nm/rad² | same builder (`kconvPN`) | converter coordinate θ | untouched |

Neither parameter needs to be invented. **The Phase-0 hard stop on provenance is cleared.**

### 2.3 The bound restoring torque is not missing — it is *active*

`MatSoaSlice.matS2SolveStep` residual:

```
a45 = QpsiF8 − kconv·(θ − θ_s) − kbind·(ψ − ψ_actin)
```

`U = ½·k_bind·(ψ − ψ_actin)²` has been running in **every Path-B campaign to date** — viscosity map, low-[ATP]
transfer, density screen, all twirling arms, and the zero-skew mirror study.

### 2.4 What actually degenerated

- `ψ_actin = 0` for **all 1200** lawn motors (`buildGlide2D`), about the **lab-fixed** `econv = +ŷ`.
- Because `ψ_actin` is constant, `U` does not depend on the filament's orientation ⇒ **no reaction torque is
  returned to the segment**. This is the defect, and it is the same defect the visual audit surfaced from the
  other side.
- The *other* historical alignment torques, F9/F10 in `CrossBridgeSystem.bondForcesSurface`, are separately
  dead on this path: `j1FMT = xbParams[2] = 0` exactly. They are **not** the law we are restoring (F9 pins the
  polar angle to the filament **axis**; F10 is head **roll** — Phase 6 territory).

---

## 3. The blocker (Decision 1)

Measured from the built scene:

```
| eBind · econv |  =  0.000e+00   (exactly, for every ψ)
```

`eBind` is confined to the plane ⊥ `econv`, and `econv = +ŷ` is **lab-fixed and shared by every motor**
(`RAND_BASE_AZ` is off — this is the "no orientational disorder in the single-head lawn" hazard already on
record in `CURRENT_STATE §9d`). Therefore, for **any** ψ:

```
max_ψ  dot(eBind, n_site)  =  sqrt( 1 − (n_site · econv)² )  =  | sin(site azimuth) |
```

Over the 196 effective sites of the canonical filament (20 distinct azimuths, 18° apart):

| site azimuth | # sites | best achievable angle to `n_site` | can ever pass 25°? |
|---:|---:|---:|---|
| −162° | 9 | 72.00° | no |
| −144° | 10 | 54.00° | no |
| −126° | 10 | 36.00° | no |
| **−108°** | 9 | **18.00°** | **YES** |
| **−90°** | 10 | **0.00°** | **YES** |
| **−72°** | 10 | **18.00°** | **YES** |
| −54° | 9 | 36.00° | no |
| −36° | 10 | 54.00° | no |
| −18° | 10 | 72.00° | no |
| 0° | 10 | 90.00° | no |
| +18° | 10 | 72.00° | no |
| +36° | 10 | 54.00° | no |
| +54° | 10 | 36.00° | no |
| **+72°** | 10 | **18.00°** | **YES** |
| **+90°** | 10 | **0.00°** | **YES** |
| **+108°** | 10 | **18.00°** | **YES** |
| +126° | 10 | 36.00° | no |
| +144° | 9 | 54.00° | no |
| +162° | 10 | 72.00° | no |
| +180° | 10 | 90.00° | no |

**59 of 196 sites = 30.1 % reachable. 14 of 20 azimuths are unreachable no matter what the motor does.**

Implementing the specification literally would therefore discard ~70 % of the lattice and pin all attachment
to the filament's top and bottom — and it would *look* like "the intended geometry now acts mechanically"
while actually being an artifact of the motor's missing degree of freedom.

**Note on a tempting escape that the task explicitly forbids.** Targeting the *projection* of `n_site` into
the motor's plane would make the law satisfiable everywhere at zero cost — but the task rules this out
("do not choose the outward normal that best matches the head", "do not rotate `n_site` toward `eBind`"), and
rightly so: it would accept a head 90° away from its site normal. It is listed below only as option **1D** so
the planner can rule on it explicitly rather than have it excluded by me.

---

## 4. DECISION 1 — the lawn frame

### Option 1A — enable `RAND_BASE_AZ`, keep the gate strict  *(recommended)*

Turn on the existing default-off per-motor base-azimuth randomisation. Each motor's whole base geometry is
rotated rigidly about its own pivot around `ê_up` by a deterministic per-motor angle χ; nothing about its
internal mechanics changes, only which way its base plane faces. Then `econv(χ) = ẑ × b̂(χ)` and

```
max_ψ dot(eBind, n_site) = sqrt( 1 − cos²φ · cos²χ )
```

so **every** site azimuth becomes reachable by *some* fraction of the lawn:

| site azimuth φ | fraction of motors that can reach it within 25° |
|---:|---:|
| 0° / 180° | ~28 % |
| ±36° | ~35 % |
| ±72° | ~85 % |
| ±90° | 100 % |

- **For:** the strict 3-D gate stays literal and unweakened; no parameter invented; the flag already exists,
  is documented as *"a SCENE control for the shared-base-frame artifact, not physics"*, and is arguably what
  the lawn should always have had (a real myosin lawn is not crystallographically aligned).
- **Against:** the scene differs from every previous Path-B campaign, so a naive before/after panel would
  confound the disorder with the normal law. **Mitigation: run a 3-arm panel** — (i) current, (ii) disorder
  only, (iii) disorder + normal law — which separates them cleanly at the same cost.
- **Risk:** disorder alone will change recruitment and glide; that must be measured and attributed, not
  assumed small.

### Option 1B — strict gate, lawn unchanged

Implement exactly as specified and accept 30.1 % reachability.

- **For:** maximum fidelity to the written spec; changes nothing about the scene; the outcome is an honest
  statement — *this motor cannot stereospecifically bind most helical sites*.
- **Against:** recruitment falls several-fold; attachment becomes bimodal at ±90°; the result is a finding
  about a missing degree of freedom rather than a working corrected model. Any subsequent twirling number
  would be dominated by the top/bottom selection, not by chirality.

### Option 1C — add the missing out-of-plane head DOF

Give the head the tilt coordinate it lacks, so `eBind` can leave the plane and genuinely reach any `n_site`.

- **For:** the only option that makes the intended law *physically* satisfiable for the real motor.
- **Against:** a genuine model extension with a new coordinate and a new drag/stiffness — no provenance,
  and explicitly out of scope per the task's Phases 6 and 14. Would need its own arc.

### Option 1D — target the in-plane projection of `n_site`  *(forbidden by the task; listed for a ruling)*

Retarget `ψ_actin` to `atan2(n·p̂2, n·p̂1)` — the best alignment the motor can actuate — and report the
out-of-plane residual as a diagnostic instead of a rejection.

- **For:** preserves the historical law *exactly* (same coordinate, same tolerance, same stiffness), makes it
  site-referenced everywhere, costs nothing, and never rejects a site for a reason the motor cannot fix.
- **Against:** it is *not* "eBind aligned with n_site". A site at azimuth 0° would accept a head pointing 90°
  away from its normal. The task forbids it.
- **Middle position worth a ruling:** apply the historical **25° tolerance to the in-plane error** (what the
  motor controls) **and** additionally report — or separately gate, at a stated threshold — the out-of-plane
  residual. That makes the missing DOF explicit and measurable instead of silently absorbing it.

---

## 5. DECISION 2 — how the actin reaction is produced

Both options must deliver an equal-and-opposite torque on the bound segment; the completion rule requires it.

### Option 2A — retarget `ψ_actin`, derive the reaction from the same potential  *(recommended)*

Write `ψ_actin` per bound motor from the live site normal; the **existing** `k_bind` spring in
`matS2SolveStep` then does the restoring work with **no new stiffness**. Because `ψ_actin` now depends on the
filament's material frame, `U` becomes a function of filament orientation and the reaction follows
conservatively:

```
U = ½ · k_bind · ( ψ − ψ_a(n_site) )²

  on the motor    :  −∂U/∂ψ      = − k_bind ( ψ − ψ_a )                  ← already in matS2SolveStep
  on the filament :  −∂U/∂φ_roll = + k_bind ( ψ − ψ_a ) · dψ_a/dφ_roll   ← NEW, about û_seg
```

with

```
dψ_a/dφ_roll = [ (t̂·p̂2)(n̂·p̂1) − (t̂·p̂1)(n̂·p̂2) ] / [ (n̂·p̂1)² + (n̂·p̂2)² ]      t̂ = dn̂/dφ
```

returned through the `bondData[d+9..11]` segment-torque slot that `CrossBridgeSystem.segGather` already sums
— the same path `headRollStep` uses.

- **For:** zero new parameters; conservative by construction, so energy/torque consistency and reaction
  closure are gated identities rather than hopes; no change to the solve's structure (the term is already in
  the 5×5 Jacobian).
- **Against:** the reaction lands on the **axial (roll-driving)** channel — i.e. it directly feeds the
  twirling observable that §9g found null. That is a reason for care, **not** a reason to tune it. It must be
  measured with the stiffness frozen at its historical value.
- **Watch:** `ψ_a` must be written before the solve consumes it, with one consistent ordering on both runners
  (the `headRollStep` / `convFrameStep` precedent).

### Option 2B — a separate explicit `eBind × n_site` torque kernel

Leave `ψ_actin` alone; add a standalone restoring torque on the head with an equal-and-opposite segment
reaction.

- **For:** reads more like the written spec; independent of the solve; robust cross/dot formulation is
  natural.
- **Against:** needs a **new stiffness with no provenance** (violating the task's own rule), and it
  double-constrains ψ unless the historical `k_bind` term is disabled — which would itself be a silent
  physics change to every prior campaign's model.

---

## 6. Smaller rulings the planner should make at the same time

1. **`g1` retarget vs. retain.** §2.1 settles this as Phase-4 case **A** (same DOF). Recommendation: when the
   corrected law is ON, `g1`'s lab-referenced form is **replaced**, not supplemented. Confirm.
2. **Out-of-plane residual.** Reject, or measure-and-report? See the middle position in 1D. This is the single
   most consequential sub-ruling after Decision 1.
3. **Flags / backward compatibility.** Proposed: `SITE_NORMAL_ORIENT_GATE` and `SITE_NORMAL_BOUND_TORQUE`,
   independently switchable, **both default-off**, OFF ≡ today byte-for-byte, Path A untouched. Confirm you
   want two flags rather than one combined switch — separate flags let the panel attribute capture vs.
   mechanics.
4. **Relationship to the twirling programme.** Restoring this law creates a **new chiral torque channel** that
   did not exist when §9g measured its null. Recommendation: freeze `k_bind` at 512 pN·nm/rad², report the
   torque that falls out, and do **not** revisit the mirror campaign in the same task (task Phase 14).
5. **Compatibility-run conditions.** Task specifies η = 0.01 Pa·s, every4 + global phase, site-aware, slab on,
   no motor skew, `invalid = solverFail = 0`. With option 1A the panel becomes 3-arm rather than 2-arm.

---

## 7. Recommendation in one line

**1A + 2A + (2) measure-and-report the out-of-plane residual**: enable the existing lawn disorder so the
strict site-normal gate is physically satisfiable, retarget `ψ_actin` so the historical 25° tolerance and
512 pN·nm/rad² stiffness are reused **unchanged**, derive the actin reaction conservatively from the same
potential, and separate the disorder from the law with a 3-arm panel. **No parameter is invented anywhere in
this path.**

---

## 8. What is already built and safe to keep

- `./scripts/run_chiral_sites.sh -normal-audit` — the read-only Phase 0/1 audit that produced everything
  above. Additive, changes no default, touches no kernel.
- The bound-motor visual-audit machinery (`-bound-viz`, `bound_motor_geometry_viewer.html`,
  `scripts/plot_bound_motor_audit.py`) is directly reusable for Phases 10–11; it already draws `n_site` and
  `eBind` per bound head and reports the angular error, so the visual acceptance test needs no new viewer.

## 9. Open questions I could not answer from the code

1. Was the lawn *intended* to be orientationally ordered? `RAND_BASE_AZ`'s comment calls the shared frame an
   "artifact", which suggests not — but no report ever justifies the default.
2. Is there provenance anywhere for an **out-of-plane** head tilt stiffness? I found none; if one exists in a
   two-body experiment I did not reach, option 1C becomes much cheaper.
3. Was `ψ_actin` ever intended to be per-motor and actin-derived? Every builder in the current tree sets it to
   a constant, and the HMM dimer copies a per-head value that is itself initialised from a constant.
