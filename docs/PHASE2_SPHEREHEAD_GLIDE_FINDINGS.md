# Phase-2 — sphere-head: tail constraint vs v1 (Part 1), see it (Part 2), and the glide status (Part 3)

**2026-06-30. New files / flag-gated only (`SphereHeadSystem`, `SphereHeadHarness`) ⇒ production +
every other harness BYTE-IDENTICAL; `BoA-v1ref` byte-clean; CPU mechanism (per-head pure).**

## Standing rule (restated, and applied)
**No glide velocity is accepted unless it comes from the full myosin-mat gliding assay** (~2.9 µm runway,
velFitX over a long steady window, a thermal-floor control, n≥3 seeds + SEM, dt=1e-5). Single-motor step
measurements, short runs, and `-diag` velocities **do NOT count as glide.** The sphere-head gate (M1–M3,
PHASE2_SPHEREHEAD_FINDINGS) proved single-motor *kinematics* — necessary, NOT sufficient. **The
sphere-head is "gate passed, glide UNPROVEN."** Part 3 (the assay) is the next increment; it was **not
run this session**, so **no glide claim is made here.**

---

## PART 1 — the tail constraint vs v1: RESOLVED. The rigid clamp was UNFAITHFUL and is NOT needed.

**The concern (real):** the first sphere-head build (`PHASE2_SPHEREHEAD_FINDINGS`) clamped the rod/tail
**rigidly in position AND orientation** so the swing reaction wouldn't spin the rod. That is the **same
class of move** as the v2 two-point head pin and the BoA 180° orientation pin — add a rigid constraint so
the lever reaches its angle — which absorbed the stroke and killed transport twice this session.

**v1 (from `BoA-v1ref`, file:line):**
1. **Tail anchor = COMPLIANT point-spring, force only.** `MyosinFixed.applyRodFixedPtForce`
   (`MyosinFixed.java:51-67`): `F = myoJ2FracMove·1e-6·strain/(dt·(moveC1+moveC2))` pulling rod.end1
   toward the fixed bed point; **the torque line `:70 //myoRod.incTorqueSum(RcrossF)` is COMMENTED OUT**
   ⇒ position-only, NOT a rigid pose-clamp.
2. **Rod orientation = FREE.** `myoJ2FracMoveTorq = 0.00` (`Env.java:159`) ⇒ no J2 angular spring; the
   rod reorients freely, held only by the compliant point anchor + the J2 translational link + drag.
3. **The stroke (F9) reacts on the FILAMENT + the HEAD, NEVER on the rod** (`MyoFilLink.java:248` seg,
   `:251` motor head). The rod is decoupled from the stroke.

⇒ **v1's tail is COMPLIANT, and v1's stroke reacts on actin — not on the rod.** The rigid clamp was a
scaffolding artifact, and reacting the swing on the rod (which forced the clamp) was the un-faithful part.

**The faithful fix + re-run (M1/M3 with `-compliant`):** react the swing torque on the **FILAMENT**
(`SphereHeadSystem.swing` `reactOnFil`, mirroring F9) and drop the rigid clamp (use only the v1
compliant point-anchor). The lever then reorients about its own center (like v1's head under F9), the
reaction on actin, the rod/tail follow compliantly by drag.

| mode | M1 geometric step | M2 impulse-free | M3 free-filament step (dt=1e-6) | lever θ | pin? |
|---|---|---|---|---|---|
| RIGID clamp (old) | −9.18 nm | +0.000 | −9.18 nm (needs realistic drag at dt=1e-5) | →125° | rigid tail pin |
| **COMPLIANT (v1-faithful)** | **−8.13 nm** (axial, −X) | **+0.000** | **−4.22 nm** (−X, lever→125° clean) | **→125°** | **NONE** |

**The compliant, v1-faithful sphere-head DELIVERS the step with NO rigid pin** — the geometric throw
(−8.13 nm, skeletal, axial, −X) and a clean −X delivered step to a free filament (−4.22 nm at dt=1e-6;
~half the geometric throw because the compliant contact + compliant tail share the displacement, exactly
as a compliant linkage should). **The bail condition (a rigid orientation pin required to glide) is NOT
triggered.** Use the compliant tail for Parts 2–3.

**dt note (carry to Part 3):** at dt=1e-5 the compliant step is stable + correct (θ→125°) with realistic
ensemble-scale filament drag (`-filgam 20`: −0.45 nm/window, a slow heavy-filament relaxation the
ensemble accumulates across many motors), but the low-drag 1-seg **toy** filament goes explicit-stiffness
unstable (lever →26°, wrong). Same dt-ceiling family as before; the ensemble (heavy 11-seg filament)
should be stable but **must be re-checked at scale** (Part 3).

---

## PART 2 — SEE it (`-3js` viewer frames produced)
**Rendering fixes (first pass was unreadable — "can't see the neck", "pieces jump"):** (1) the 10 nm
sphere was *engulfing* the 8 nm neck → render the sphere as a small 3.5 nm marker so the lever shows;
(2) frames were sparse over a swing that completes in <100 steps + an unshown settle → pre-relax each
phase silently and sample DENSELY+SMOOTHLY (every 3 steps, 2 full recovery↔stroke cycles, 320 frames);
(3) the faithful ±2 nm swing is ~1 % of an 80 nm-rod scene → a labeled **lever exaggeration ×6 FOR THE
VIEWER ONLY** (`-vizexag`, default 6) makes the neck a clear short tube and the swing unmistakable.
**The measurements are byte-untouched** (no `-3js` ⇒ leverLen = LEVER_LEN; M1 still −8.13 nm).
- **PRIMARY — `threejs_spherehead/`** (320 frames, compliant, dt=1e-6, neck ×6): a long thin tube = the
  ROD/tail (bed-anchored); a short tube = the NECK/lever (the converter) **swinging in-plane (x-z)**; a
  small ball at the neck tip = the SPHERE on the actin filament. Across the power stroke the sphere steps
  **−X** (sphere x +7.4 → −8.0 nm exaggerated), lever θ→125°; the whole linkage flexes (compliant) and
  the filament moves — the signature of a compliant tail, NOT a rigid pin absorbing the swing.
- **Labeled artifact — `threejs_spherehead_rigid_overshoot/`** (320 frames, rigid, dt=1e-5, faithful
  scale `-vizexag 1`): the explicit-stiffness overshoot (lever flattens to 168°, filament −57 nm). Named
  so the artifact is not mistaken for the mechanism.
- **SECONDARY (small bound ensemble, bound-only toggle):** deferred to Part 3 — this harness is
  single-motor; the bound-only viewer toggle (added for the ensemble) applies to the Part-3 mat.
```
python3 SoftBox/sim_server.py 8000   # from ~/Code → http://localhost:8000/SoftBox/sim_viewer_boa.html
```

---

## PART 3 — the full-mat gliding assay (the ONLY glide proof): NOT YET RUN — next increment
Per the standing rule, **glide is UNPROVEN.** Part 3 was not run this session. The precise wiring plan
(the only substantive remaining work):
- Wire the **compliant** sphere-head into the multi-motor gliding harness: bind the sphere on proximity
  (reuse `bindNearest`, anchor at lever.end2); replace `bondForces` with `sphereBond` (anchor at
  lever.end2 ↔ fixed site); run `SphereHeadSystem.swing` reacting on the filament; reuse v1 catch-slip
  release + rebind (`catchSlipRelease` + `cycle`) with `forceDotFil` from the anchor.
- **The one real new GPU piece:** the cross-bridge gather currently applies the motor-side force to the
  HEAD sub-body (`applyHeadForce`) — the sphere routes it to the **LEVER** (an `applyLeverForce`); and
  the swing's filament-side torque reaction needs the CSR gather (race-free) like the seg force. On the
  CPU runner (sequential) direct `+=` is race-free; the GPU needs the gather.
- Then the matbed: ~2.9 µm −x runway, **velFitX** over the steady 2nd half, **thermal-floor control**
  (`-nobind`), **n≥3 seeds + SEM**, **dt=1e-5**, density ladder; read vs the skeletal anchor (Uyeda
  100–300 µm⁻², 5–8 µm/s). **Stage behind a single-density velFitX confirmation; log to `.last_run_status`.**
- **Re-check the dt=1e-5 stability AT ENSEMBLE SCALE** (the Part-1 dt note): the heavy 11-seg filament is
  predicted to damp the single-motor overshoot, but that is untested at scale. If whip/NaN appears
  (capFires>0, fullMat<YES), the banked implicit/substep cross-bridge fix applies — report it, do not
  silently drop dt.
- Report netX + velFitX ± SEM, sign, avgBound vs the thermal floor, per density — **the only result that
  answers "does the sphere-head glide."**

---

## Verdict
- **Part 1 (gate on the assay's validity): PASS.** v1's tail is compliant; the faithful compliant
  sphere-head delivers the −X skeletal step with **no rigid pin** (bail not triggered). The assay, when
  run, will be on the faithful mechanism.
- **Part 2: done** (PRIMARY compliant stroke + labeled artifact frames).
- **Part 3: NOT RUN ⇒ glide UNPROVEN** (standing rule). The ensemble wiring (esp. the lever-side gather)
  is the next increment.
- **Kinematics ✓, glide ✗ (unproven).** The sphere-head is single-motor-correct and v1-faithful; whether
  it converts that to ensemble transport is exactly what Part 3 must settle — and where every prior
  transport killer (duty collapse, tug-of-war, transverse projection under collective load) actually lived.

## Reproduce
```
java … softbox.SphereHeadHarness -compliant -dt 1e-6              # the faithful M1–M3 (Part 1)
java … softbox.SphereHeadHarness -compliant -filgam 20           # dt=1e-5 ensemble-drag stability
java … softbox.SphereHeadHarness -compliant -dt 1e-6 -3js threejs_spherehead          # PRIMARY frames
java … softbox.SphereHeadHarness -dt 1e-5 -3js threejs_spherehead_rigid_overshoot     # labeled artifact
```

## JOURNAL-ready line
`2026-06-30 — sphere-head Part 1 (tail constraint vs v1) RESOLVED: v1's tail is a COMPLIANT point-spring
(MyosinFixed.java:51-67, torque line commented out), rod orientation FREE (myoJ2FracMoveTorq=0,
Env.java:159), and the stroke reacts on the FILAMENT+head, NEVER the rod (MyoFilLink.java:248/251). The
build's rigid tail clamp was UNFAITHFUL; reacting the swing on the FILAMENT (v1's F9 pattern) + a
compliant tail (-compliant) DELIVERS the step with NO rigid pin: M1 −8.13 nm geometric (axial, −X), M2
impulse-free, M3 −4.22 nm to a free filament (lever→125° clean, dt=1e-6). Bail (rigid pin needed) NOT
triggered. Part 2: -3js frames produced (threejs_spherehead = compliant stroke; threejs_spherehead_rigid_
overshoot = labeled dt=1e-5 toy-filament −135 nm artifact). Part 3 (full-mat velFitX assay = the ONLY
glide proof) NOT RUN ⇒ glide UNPROVEN (standing rule); next increment = wire the compliant sphere-head
into the gliding mat (the one new GPU piece: route the motor-side gather to the LEVER + gather the swing's
filament-torque reaction) + re-check dt=1e-5 at ensemble scale. New files only ⇒ production byte-identical;
BoA-v1ref byte-clean. → PHASE2_SPHEREHEAD_GLIDE_FINDINGS.md.`
