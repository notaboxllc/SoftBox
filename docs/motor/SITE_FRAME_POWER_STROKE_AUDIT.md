# Site-frame power-stroke polarity audit — why was the bound population ~50/50 pulling vs resisting?

**STATUS: RESOLVED. The 50/50 was a MEASUREMENT ARTEFACT, not a mechanical defect — and the artefact was mine.
The site-bound power stroke has the CORRECT axial polarity and it is invariant around the helical circle.**

- **Worktree:** `/home/jba/Code/SoftBox` · **Branch:** `gpu-mat-bottlenecks-explicit-singlehead`
- **Date:** 2026-08-13 · **Raw:** `RUN_LOGS/motor_audit/site_frame_power_stroke/`
- **Runner:** the **CPU sequential runner** throughout. **No GPU work was launched.**
- **AUDIT ONLY — nothing was tuned.** Head geometry, `R_F8`/`R_CONV`, the site lattice, `n_site`, the 25° gate,
  the g6/g2 retirement, `g3`, `g5`, `k_det`, `k_bind`, `xCatch`, the preload threshold, chemistry, the lever
  joint, S2 mechanics, viscosity, the detached rest pose and sterics are all untouched.
- **Reproduce:**
  ```bash
  cd ~/Code/SoftBox && ./scripts/build.sh
  TDIR=$TORNADOVM_HOME/share/java/tornado
  java --enable-preview -cp "$TDIR/tornado-api-4.0.1-dev.jar:." softbox.SiteFrameStrokeAudit -all
  #   passes: -sign  -fixture  -natural     knobs: -relax N  -azim N  -nat-steps N  -seed N  -out DIR
  ```

---

## 0. THE HEADLINE, AND A CORRECTION TO MY OWN PREVIOUS REPORT

`docs/motor/SITE_NORMAL_HEAD_BINDING.md` §14i reported the gliding smoke test as:

> *"the bound population is a 50/50 tug-of-war with no net axial bias … mean axial force pull `+3.8546e−14 N`
> vs drag `+3.8452e−14 N`, equal to 3 significant figures … at this duty the motor is not generating directed
> force, which is a resolved statement."*

**That is WITHDRAWN. It was wrong twice over.**

1. **`nPull`/`nDrag` are not a polarity observable.** `ChiralSiteHarness:2290` reads
   `double power = fax * vFil;` and classifies each bound sample by **mechanical power against the
   INSTANTANEOUS filament velocity**. When `vFil` is Brownian-dominated — which §14i itself established, with a
   6.41 µm/s noise floor — its sign is essentially random, so **`nPull`/`nDrag` tend to 50/50 BY CONSTRUCTION
   whatever the sign of `fax`**. The 51.2 % was measuring the filament's Brownian velocity, not the motor.
2. **The "equal to 3 significant figures" coincidence compared two different subgroups.** `fAxPull` and
   `fAxDrag` are the summed `fax` *within* the power-defined subgroups; their near-equality across a 3-seed
   average is arithmetically meaningless as a bias measure. The correct quantity is their **sum**, the net
   axial force, and per seed it is `−0.068 / +0.497 / −0.198 pN` — **sign-flipping and unresolved**, not
   "equal".

**The polarity observable that should have been reported is `r.fax`** (`ChiralSiteHarness:2388`), the signed
mean axial force on the filament. It existed and was not printed. It is now (§5).

---

## 1. PHASE 1 — THE AXIAL SIGN CONVENTION, pinned

Measured on the built scene, not assumed from names:

| quantity | value |
|---|---|
| `u_fil` | `(+1, 0, 0)` |
| `dot(b_hat, u_fil)` | **+1.000000** |
| `dot(end2 − end1, u_fil)` | **+0.1755 µm** ⇒ end2 lies at +`u_fil` |
| `d(site position)/d(bindArc) · u_fil` | **+0.1404 µm** ⇒ `bindArc` increases toward +`u_fil` |

```
    +u_fil = BARBED   (= end2)
    -u_fil = POINTED  (= end1)
```

matching the audited Exp-3B convention (`TwoBodyConverterMotor:676-680`): *"barbed = end2 = +uVec; pointed =
end1 = −uVec; … the working stroke sweeps the F8 point toward the POINTED direction (−b̂) ⇒ force on actin
toward pointed, free filament glides pointed-first, motor reaction toward barbed."*

**Motor force versus filament reaction, kept explicitly distinct:**

| channel | productive sign |
|---|---|
| **MOTOR** force on the head, `bondData[m·13 + 0..2]` | toward BARBED ⇒ `dot(F_motor, u_fil) > 0` |
| **FILAMENT** reaction on actin, `bondData[m·13 + 6..8]` (the `segGather` channel) | toward POINTED ⇒ **`fax = dot(F_fil, u_fil) < 0`** |
| free-filament glide, `centroidDot(f, b_hat)` | pointed-first ⇒ **`glide < 0`** |

**So the expected productive signs are `fax < 0` and `glide < 0`.**

---
## 2. PHASE 3/4/10 — THE DETERMINISTIC CANONICAL STROKE, at every helical azimuth

Motor Brownian OFF, 4000 relaxation steps per equilibrium, and **the real production state change**: the
nucleotide is set `ADP·Pi → ADP` and `matCock` + the implicit solver do the rest. **F8 is never translated by
hand.**

**Phase 4 done correctly — the axial coordinate is held FIXED.** Azimuth is swept by rotating the filament's
**rolling material frame** about its own axis, which rotates every site normal (`n_site = cos(bindAzim)·segY +
sin(bindAzim)·segZ`) while leaving `coord`, `uVec`, `end1` and `end2` — hence every site's axial coordinate —
untouched. All eight arms bind **the same site 155**.

> **A confounded first attempt, recorded because it would otherwise look like a result.** My first sweep walked
> along the `every4` lattice (site 156, 157, …). Each step advances the site **+10.8 nm axially** while the
> motor stays put, so it measured an increasingly stretched bond (`sF8` −11.7 → −73 nm, `fax` −10.7 → −67 pN),
> not the same configuration at a new azimuth. It is not reported as a covariance result.

### ISOMETRIC restraint (filament frozen — an infinitely stiff axial restraint; measures FORCE polarity)

| azim | `sF8_pre` (nm) | `sF8_post` (nm) | `Δ sF8` | `fax_pre` (pN) | **`fax_post` (pN)** | `Δ fax` (pN) |
|---:|---:|---:|---:|---:|---:|---:|
| 0° | −5.179 | −4.857 | +0.322 | −4.606 | **−4.954** | −0.348 |
| 45° | −4.068 | −3.790 | +0.278 | −3.685 | **−4.239** | −0.554 |
| 90° | −3.197 | −3.289 | −0.093 | −2.935 | **−3.236** | −0.301 |
| 135° | −7.093 | −11.019 | −3.927 | −5.416 | **−8.723** | −3.307 |
| 180° | −8.236 | −9.401 | −1.165 | −6.590 | **−9.754** | −3.164 |
| −135° | −8.347 | −12.778 | −4.431 | −6.491 | **−10.377** | −3.886 |
| −90° | −5.017 | −5.133 | −0.116 | −4.635 | **−5.106** | −0.471 |
| −45° | −5.444 | −4.639 | +0.806 | −4.847 | **−3.952** | +0.895 |
| **mean** | | | −1.041 | | **−6.293** | **−1.392** |

**`fax_post` is NEGATIVE at 8 of 8 azimuths** — the force on the filament points toward the **pointed** end at
every point on the helical circle. **The polarity is correct and azimuth-invariant.** The stroke's own
contribution `Δfax` is **−1.39 pN mean (6/8 negative)**, i.e. the stroke *adds* pointed-directed force.

> **Honest caveat on the baseline.** The fixture imposes the canonical *orientation* but does not equilibrate
> the F8 *position*, so the pre-stroke bond already carries −2.9…−6.6 pN of pointed-directed preload from a
> 3–8 nm residual offset. `fax_post` is therefore preload + stroke; `Δfax` is the stroke's own increment and is
> the cleaner number. Both have the same sign.

### FREE restraint (filament mobile, all Brownian off; measures DISPLACEMENT polarity)

`Δ sF8` mean **−2.16 nm (6/8 negative)**; `fax_post` mean **+0.11 pN, mixed 5/8**; filament axial displacement
mean +0.36 nm, mixed.

**This is the expected behaviour, not a contradiction.** A single motor on a *free* filament relaxes the whole
assembly to a new equilibrium, so the residual force tends to zero and its sign is set by wherever the system
stopped. **A force-polarity question must be asked under restraint; a displacement question under freedom.**
Under restraint the answer is unambiguous.

---
## 3. PHASE 11 — does the site-normal orientation torque leak axial force? NO, by construction

`SiteNormalBindSystem.siteCoupleStep` writes **exactly three slots** of `bondData`:

```
    bondData[d+9]  -= Tx        bondData[d+10] -= Ty        bondData[d+11] -= Tz
```

Those are the **segment-side TORQUE** slots. It never touches `d+6..8`, the segment-side **FORCE** slots — a
repo-wide grep confirms these are the only `bondData.set` calls in the file. The reaction is a **pure couple**
`T_fil = −λ(xHeadHat × eTarget)`, and `CrossBridgeSystem.segGather` sums forces and torques into separate
accumulators (`filForceSum` from `d+6..8`, `filTorqueSum` from `d+9..11`).

**The orientation law therefore injects ZERO axial force by construction.** It can reorient the filament; it
cannot translate it. No hand-added axial thrust exists anywhere in the site-normal path.

---

## 4. PHASE 12 — the historical 8.46 nm stroke vs the site-frame `ΔsF8`: DIFFERENT OBSERVABLES

| | what it measures | value |
|---|---|---|
| **historical stroke** (`LiveNeckHeadProbe -reg`) | the F8 point's **own** displacement, projected on `b̂`, for a head whose bond is free to stretch — an essentially **unloaded** stroke against a fixed site | **−8.458 nm** (pointed) |
| **site-frame `ΔsF8`** (this audit, isometric) | `dot(xF8 − x_site, u_fil)` **after** the F8 spring has resisted — the *residual* displacement once the bond opposes the stroke | **−1.04 nm** mean |

**They should not agree numerically, and their disagreement is not a defect.** The stroke has a fixed energy
budget; against a stiff-enough restraint it converts to *force* rather than displacement. That is exactly what
the isometric arm shows: displacement is suppressed 8× while the axial force moves by **Δfax = −1.39 pN** in
the same (pointed) direction. **The signs agree; only the partition between displacement and force differs.**
A clean numerical comparison would need the site-frame fixture run with the bond unloaded, which is the
historical measurement itself.

---

## 5. PHASE 13 — the NET axial force in the gliding scene, from the existing data

`fAxPull + fAxDrag` is the mean **total** axial force on the filament per step (the two buckets partition the
bound samples), so the previous smoke test already contains the answer:

| seed | net axial force on the filament |
|---:|---:|
| 101 | **−0.0684 pN** |
| 102 | **+0.4972 pN** |
| 103 | **−0.1978 pN** |
| **mean ± SEM** | **+0.077 ± 0.213 pN**, `\|mean\|/SEM = 0.36` ⇒ **NOT distinguishable from zero** |

**Sign-flipping and unresolved — the same underpowered window as the velocity, not a demonstration of zero
thrust.** `r.fax`, the signed mean, is now printed by `-glide-compat` with a significance test so this cannot
be misread again.

---

## 6. PHASE 5–9 — natural events: UNDERPOWERED, reported as such

600 000 steps × 12 motors = 7.2e6 motor-steps yielded **3 bound episodes** (capture rate ~1 per 2e6
motor-steps, consistent with the previous report). Classification: PRODUCTIVE 1 · POLARITY-REVERSED 1 ·
LOAD-REVERSED 1 · RESISTING 0 · NO-STROKE 0. Capture preload sign: 1 born pulling, 2 born resisting.

**n = 3 cannot support Phase 7's six-way decomposition, and all three events are the same motor at the same
site, so the Phase-8 anchor-geometry means are identical by construction (−36.10 nm for both classes) and carry
no information.** These numbers are recorded for completeness and **no conclusion is drawn from them**. The
mechanism question is answered by the deterministic fixture (§2), which does not depend on capture statistics.

**What this does establish:** at least one natural event is PRODUCTIVE, and the LOAD-REVERSED classification
(correct intrinsic stroke, opposing prior bond load) occurs — consistent with diagnosis B contributing, but at
n = 3 that is a hypothesis, not a measurement.

---

## 7. PHASE 16 — THE DIAGNOSIS

**PRIMARY: D — the force-sign telemetry was interpreted backwards (by me).**

`nPull`/`nDrag` classify bound samples by `fax × vFil`, mechanical power against the **instantaneous Brownian
filament velocity**. In a scene whose velocity noise floor is 6.41 µm/s and whose real drift is unresolved,
`sign(vFil)` is essentially a coin flip, so **the 51.2 % / 48.8 % split is what that statistic returns for ANY
force polarity.** It measured the filament's Brownian motion, not the motor. No mechanical defect is required
to explain the observation, and none was found.

**Explicitly REFUTED: A (wrong or variable stroke polarity).** Under restraint the force on the filament is
pointed-directed at **8 of 8 helical azimuths** (`fax_post` −3.24…−10.38 pN), the stroke's own increment is
pointed-directed (`Δfax` = −1.39 pN mean, 6/8), and the axial polarity is **invariant** to rotating the site
around the filament axis at fixed axial coordinate.

**Explicitly REFUTED: F (site-normal torque coupling).** The orientation reaction writes only torque slots;
zero axial force by construction (§3).

**Cannot be excluded, and not measured here: B (symmetric capture preload) and C (symmetric anchor-site
geometry).** Both are plausible secondary contributors — the natural-event sample (n = 3) is far too small, and
the deterministic fixture deliberately fixes the anchor-site geometry rather than sampling it. **E (kinetics
weighting resisting states) is untested.**

**Contribution split: D alone is sufficient to explain the reported 50/50. B/C/E remain open as contributors
to the *underlying force* distribution, which is a different and still-unresolved question.**

---

## 8. WHAT WAS NOT RUN, and why

- **PHASE 14 (Brownian-off gliding diagnostic) — not run.** The deterministic fixture in §2 is a *stronger*
  version of the same test: motor Brownian off, filament clamped, real chemistry, force measured directly. It
  already answers "is force generation correctly signed" without the ensemble. A Brownian-off many-motor run
  would add an ensemble-scale check and remains worth doing, but it is not what decides the polarity question.
- **PHASE 15 (power-stroke movie) — not produced.** Only 3 natural episodes exist in the audited horizon and
  the harness that exports fine-time frames is keyed to a single-motor capture; filming a *stroking* event
  needs a capture that also survives to stroke, which at the present rate is not reliably reachable without a
  much longer run. **The existing `threejs_sitenormal_bound_capture` (801 frames, natural capture) remains the
  run to inspect for capture geometry; it is not guaranteed to contain a stroke.**
- No density campaign, no twirling campaign, no gate/threshold/rest-pose changes. Nothing was tuned.
