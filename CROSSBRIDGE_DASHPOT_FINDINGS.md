# CROSS-BRIDGE DASHPOT (Kelvin-Voigt) — does a velocity-discriminating internal damper change gliding / extend the dt ceiling?

**MEASUREMENT-ONLY, additive flag `-xbdash <γ_mult>` (+ `-xbdashmech`), default byte-identical Hookean.** Adds a
**parallel dashpot** to the F8 cross-bridge spring (`CrossBridgeSystem.dashpotForces`, a new kernel run after
`bondForces`, before `applyHeadForce`/gather/register):
```
F8 = myoSpring·stretch  +  γ_xb·d(stretch)/dt          (Kelvin-Voigt: spring ∥ dashpot)
```
where the dashpot force `γ_xb·(b_n − b_{n-1})/dt` (b = site − head_tip, the bond vector) is computed from a
**per-bond stored previous bond vector** (`MotorStore.xbPrevStretch`/`xbDashInit`, the `forceDotAvg`/`avgInit`
reset-on-unbind pattern). `γ_xb = γ_mult · γ_head` (the head's own translational drag, read per-head in SI ⇒
unit-consistent with the integrator). The dashpot is **the one velocity-discriminating lever** — history-aware,
not magnitude-based — so it is not killed a priori by the magnitude-overlap argument that sank the four force-law
levers ([[saturated-crossbridge-not-a-lever]] + softer-spring / release-averaging / thermal-correlation). It adds
drag to the **stretch mode only** ⇒ `r = k·dt/(γ_head+γ_xb)` drops without softening the spring and without slowing
the FREE head's search diffusion. **Hookean (γ_mult=0) stays the default** ⇒ the dashpot kernel is not wired ⇒
production byte-unchanged; F8 translational ONLY; binding search / catch-slip formula / F9-F10 / 12 pN cap
untouched; `BoA-v1ref` untouched. v2-GPU device-resident gliding (23+1 kernels). Sole occupant of aorus.

`-xbdashmech`: the dashpot adds its MECHANICAL force/torque but does NOT feed `forceDotFil` (the catch reads the
SPRING load only) — isolates the overshoot-suppression mechanism from the explicit dashpot's catch-detonation
artifact.

## VERDICT (preview) — the explicit dashpot is DETUNING, not benign, and does NOT extend the dt ceiling
The fifth lever fails, for a NEW and instructive reason. Unlike the four force-law levers (which fail because the
overshoot overlaps the real force in MAGNITUDE), the dashpot fails because it cannot distinguish the **deterministic
overshoot velocity** from the **thermal random-walk velocity** of the Brownian head. The explicit finite-difference
`(b_n − b_{n-1})/dt` is dominated, at the binding-relevant fine dt, by the thermal velocity `√(2D/dt)`, so the
dashpot injects a spurious force `≈ γ_xb·√(2·kT·γ_head/dt)` (≈ γ_xb·3.9 pN at dt=1e-5 — as large as the whole
cross-bridge signal). That force fights the thermostat and destabilizes the bound head, collapsing binding at
EVERY γ_xb≥0.1. (Full Stage 2 dt-extension verdict below.)

---

## The mechanism — why a dashpot is different, and why the explicit one fails anyway
- **`γ_head ≈ 1.885e-8 N·s/m`** (head sphere drag `6π·aeta·R_head`, the value behind the `r` diagnostic).
  `r = k·dt/(γ_head+γ_xb)`. To suppress overshoot (`r<1`) at dt=1e-4 with `k=1e-3 N/m` needs `γ_xb ≳ 4·γ_head`.
- **Why not killed by the magnitude-overlap argument:** the dashpot discriminates on stretch *velocity*, and adds
  drag only to the stretch mode (a free head has no site ⇒ no dashpot ⇒ the search diffusion is untouched). In
  principle it lowers `r` without softening the spring.
- **Why it fails in practice — the thermal-velocity catastrophe:** the bound head is a *Brownian* coordinate. Its
  per-step displacement is dominated not by the deterministic overshoot but by the thermal random walk
  `Δb_th ≈ √(2D·dt)`, whose finite-difference "velocity" `Δb_th/dt = √(2D/dt)` **diverges as dt→0**. So the explicit
  dashpot exerts `F_th ≈ γ_xb·γ_head·√(2D/dt) = γ_xb·√(2·kT·γ_head/dt)`:

  | dt | thermal head velocity √(2D/dt) | F_th at γ_xb=1·γ_head | F_th at γ_xb=4 |
  |---|---|---|---|
  | 1e-5 | 2.1e-4 m/s | **3.9 pN** | 15.8 pN |
  | 1e-4 | 6.6e-5 m/s | 1.2 pN | **5.0 pN** |

  At the calibrated dt=1e-5 there is **no deterministic overshoot to suppress** (Hookean r=0.53), so the dashpot
  contributes only this ≈γ_xb·3.9 pN of anti-thermal force — comparable to the entire cross-bridge spring signal
  (mean |F8| ≈ 4 pN) — which cools the bound mode and inflates the load the catch reads ⇒ collapse. (`F_th`
  *decreases* ∝1/√dt at coarser dt — the basis for the Stage-2 test, where a real overshoot exists.)

---

## STAGE 1 — gliding at dt=1e-5: BENIGN or DETUNING? (4×1 v1-box, 2000 heads, `-grid` NET, n=2)
Hookean reference (γ_xb=0): **NET ≈ 3.8 µm/s, avgBound ≈ 6.9** (here netX −3.6/−3.3, avgB 6.6/6.5).

| γ_xb (×γ_head) | mode | avgBound (n=2) | netX µm/s | glide? |
|---|---|---|---|---|
| **0 (Hookean)** | — | **6.6 / 6.5** | **−3.6 / −3.3** | YES (reference) |
| 0.1 | literal | 1.96 / 0.80 | −1.5 / −0.4 | degraded 3–8× |
| 0.25 | literal | 0.65 / 0.39 | −0.04 / +0.03 | collapsed |
| 0.5 | literal | 0.28 / 0.16 | +0.9 / +0.5 | collapsed (netX positive) |
| 1 | literal | 0.10 / 0.18 | +1.9 / +1.7 | collapsed |
| 2 | literal | 0.04 / 0.10 | +1.1 / +2.5 | collapsed |
| 4 | literal | 0.00 / 0.00 | — | fully unbound |
| 0.25 | mech-only | 0.41 / 0.43 | −0.8 / −5.0 | collapsed (marginally less bad) |
| 1 | mech-only | _(pending)_ | | |

**DETUNING — there is NO benign range.** Even the smallest tested γ_xb=0.1 degrades binding 3–8× (avgB 6.6→~1);
γ_xb≥0.5 collapses it (avgB <0.3) and the directed glide vanishes (netX flips positive = wander). The collapse is
**not** the catch detonation: `-xbdashmech` (catch reads spring-only) is only **marginally** less destructive
(avgB ~0.4 vs ~0.6) and still collapses — and the 12 pN cap is OFF by default in gliding — so it is the
**mechanical** anti-thermal force itself perturbing the bound head and inflating the spring stretch. This is the
explicit dashpot's own coarse-dt fragility (the task's flagged caveat), here dominating already at the FINE dt.

---

## STAGE 2 — does γ_xb extend the faithful dt toward 1e-4? NO.
At each coarser dt: Hookean (γ=0) vs the γ_xb chosen to bring `r_eff<1` (literal + mech-only), gliding `-grid`, n=2.
`r_hookean = 53000·dt`: 2e-5→1.06, 5e-5→2.65, 1e-4→5.30; γ_xb picked so `γ_eff` brings `r_eff≈0.5–0.9`.

| dt | r_hookean | config | avgBound (n=2) | glide? |
|---|---|---|---|---|
| **2e-5** | 1.06 | Hookean (γ=0) | **0.47 / 0.24** | collapsed (the gliding scene's binding already fails at 2e-5) |
| 2e-5 | →0.42 | γ_xb=1 literal | 0.26 / 0.06 | worse |
| 2e-5 | →0.42 | γ_xb=1 mech | 0.06 / 0.12 | worse |
| **5e-5** | 2.65 | Hookean | 0.16 / 0.14 | collapsed |
| 5e-5 | →0.53 | γ_xb=4 literal | 0.00 / 0.06 | collapsed |
| 5e-5 | →0.53 | γ_xb=4 mech | 0.02 / 0.00 | collapsed |
| **1e-4** | 5.30 | Hookean | 0.02 / 0.00 | collapsed |
| 1e-4 | →0.59 | γ_xb=8 literal | 0.00 / 0.00 | unbound |
| 1e-4 | →0.59 | γ_xb=8 mech | 0.00 / 0.00 | unbound |

- **No γ_xb at any dt recovers the faithful glide.** avgBound never returns toward the ~6.9 reference; it stays
  collapsed (≤0.5) at every dt and every γ_xb, and the dashpot makes the (already-collapsed) Hookean 2e-5 case
  *worse*, not better.
- **There is no numerical blow-up to stabilize.** The coarse-dt failure mode is **binding collapse**, not a NaN: an
  unbound head exerts no force, so the cross-bridge spring never runs the geometry to infinity — every run stays
  finite (instSteady 2–10 µm/s = thermal wander, not directed glide; no `NON-FINITE`). So even the "stable but
  unfaithful" consolation the dashpot might have offered does not exist — the motor simply unbinds.
- **Why coarse dt does not rescue the dashpot:** the thermal-velocity force `F_th ∝ γ_xb·√(γ_head/dt)` does shrink
  ∝1/√dt at coarser dt (≈γ_xb·1.2 pN at 1e-4 vs 3.9 at 1e-5) — but the γ_xb needed to suppress the now-large
  deterministic overshoot grows proportionally (γ_xb≈8 at 1e-4), so the product `F_th` lands right back at
  ≈10 pN. The two effects cancel: the dashpot strength required to fix the overshoot reintroduces an anti-thermal
  force that collapses binding. The lever has no operating point.

---

## Secondary — the signed-load distribution (V2OneX `-dtconv`, contractile, k=1 pN/nm, CPU, dt=1e-5)
Does the damping keep the negative tail bounded? **No — it INFLATES it.** (The dashpot is wired into the V2OneX CPU
runner only.)

| config | bound | fmgMean pN | fmgMax pN | signed-load p10 pN | fracOverCap | capHits |
|---|---|---|---|---|---|---|
| **Hookean** | **383** | **4.23** | 10.1 | **−3.5** | 0.00 | 1244 |
| γ_xb=1 literal | **4** | **10.6** | 19.8 | **−11.4** | 0.50 | 10095 |
| γ_xb=1 mech-only | **3** | 5.3 | 5.7 | (collapsed) | 0.00 | 10485 |

The dashpot **collapses the contractile bound population 383→3–4** and **inflates** the cross-bridge load
(fmgMean 4.2→10.6 pN, the catch input p10 −3.5→−11.4) — the opposite of bounding the negative tail. The cap fires
~10 000× (the inflated mechanical force trips the 12 pN break even in mech-only, since the cap reads |F8 head| which
includes the dashpot). Same verdict as gliding, at the production stiffness and in a second scene.

## VERDICT — the explicit cross-bridge dashpot DETUNES gliding and does NOT extend the dt ceiling (the fifth lever, a new failure mode)
**Stage 1 (1e-5): DETUNING, no benign range.** Every γ_xb≥0.1 degrades binding (3–8× at 0.1; collapse at ≥0.5);
the directed glide is lost. **Stage 2 (dt extension): NO extension.** No γ_xb at 2e-5/5e-5/1e-4 recovers binding;
the dashpot makes the already-fragile coarse-dt gliding worse, and there is no NaN for it to prevent.

**The new, instructive reason this lever fails (distinct from the four force-law levers).** The four magnitude-based
levers failed because the overshoot overlaps the real force in MAGNITUDE. The dashpot is genuinely different — it
discriminates on stretch *velocity* — but it fails because the bound head is a **Brownian** coordinate whose
explicit finite-difference velocity `(b_n−b_{n-1})/dt` is dominated by the **thermal random-walk velocity**
`√(2D/dt)`, not the deterministic overshoot. So the explicit dashpot exerts a spurious anti-thermal force
`F_th ≈ γ_xb·√(2·kT·γ_head/dt)` — comparable to the whole cross-bridge signal — that cools the bound mode and
collapses binding. At fine dt there is no overshoot to suppress (only harm); at coarse dt the γ_xb needed to
suppress the overshoot scales up exactly enough to keep `F_th≈10 pN` (the cancellation that leaves no operating
point). **An explicit dashpot is the wrong tool for a thermally-fluctuating coordinate.**

**The flagged follow-on (NOT built):** a **semi-implicit dashpot** — treat the dashpot term implicitly so it adds
true drag to the head's *equation of motion* (raising the effective integration drag, FDT-consistent) instead of
injecting an explicit force computed from a noisy finite-difference velocity. That is an INTEGRATOR change (the
implicit/sub-step direction the other four levers also point to), not a force-law term — consistent with the
overall conclusion that the cross-bridge dt headroom lives in the integration, not the force law.

## Stability / runner / regression
- **Default byte-identical:** with no `-xbdash`, `dashpotForces` is never wired (CPU) / never added to the TaskGraph
  (GPU) ⇒ the Hookean path is unchanged. Confirmed: no-flag gliding reproduces the committed baseline (avgB ~6.6,
  NET ~3.6); the xbridge regression (gather==brute exact, CPU≡GPU bit-identical) passes with the new MotorStore
  arrays present.
- **CPU≡GPU:** aggregate-within-SEM (the chaotic-gliding standard). γ_xb=1 @1e-5: GPU avgB 0.098 / CPU 0.078,
  netX +1.91 / +1.92 — both runners reproduce the same collapse (the dashpot kernel lowers identically; microstate
  decorrelates at float32 last-bit, like all gliding).
- **GPU device-resident:** the dashpot is one extra nM-sized kernel inserted between `bond` and `applyHead`
  (worker grid `gliding.dash`, pad(nM)); lowers clean on PTX (no RNG/trig), no NaN.
- **V2OneX (secondary signed-load):** the dashpot is wired into the **CPU runner only** (the 5-graph GPU split is
  not dash-wired; `-xbdash -gpu` forces `-cpu` with a notice).

## Reproduce
```
./run_xbdash.sh stage1     # γ_xb sweep @ dt=1e-5 (benign or detuning?)
./run_xbdash.sh stage2     # dt extension toward 1e-4 (does γ_xb keep the glide stable+faithful?)
# single override:
./run_gliding.sh -gpu -v1box -grid -xbdash 1 -seed 1 5000              # literal
./run_gliding.sh -gpu -v1box -grid -xbdash 1 -xbdashmech -seed 1 5000  # mech-only
```
Raw: `RUN_LOGS/2026-06-26_xbdash_stage1.txt`, `_stage2.txt`.
