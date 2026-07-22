# SM4 — ADP assisting-load production grid (fixed-anchor, non-buckling fixture)

**Status: COMPLETE. n = 200/cell × 12 forces = 2400 events. 0 censored, 0 invalid, 0 solver failures.**
**CPU-only, 31.6 s wall. Code rev `73d82bf`, scratch build `e667650b54f9`.**
**NO physics parameter was changed during data generation.**

---

## 1. Why this fixture

`explicit-s2-l40` **cannot** be used for the assisting arm: its S2 beam buckles in compression, so
the bond never experiences the requested assisting load (SM4 smoke: realized saturated at −2…−3 pN
for requests up to −10 pN; SM6 quantifies the buckling onset at −7.5 nm). The `fixed-anchor` fixture
has a rigid tail and therefore **isolates bond kinetics from S2 compressive mechanics** — which is
precisely the separation this arm requires.

**This fixture identity is retained on every row of `event_table.csv` (`fixture` column) and in every
batch JSON. Fixed-anchor assisting data are NEVER pooled with explicit-S2 opposing data.**

---

## 2. Requested vs realized load — the fixture transmits faithfully

| F req (pN) | realized bond load (pN) | filament max displacement (nm) |
|---:|---:|---:|
| 0 | +0.691 | 0.000 |
| −1 | −0.173 | 0.000 |
| −2 | −1.040 | 0.000 |
| −3 | −1.911 | 0.000 |
| −4 | −2.786 | 0.000 |
| −5 | −3.664 | 0.000 |
| −6 | −4.545 | 0.000 |
| −8 | −6.315 | 0.000 |
| −10 | −8.094 | 0.000 |
| −15 | −12.565 | 0.000 |
| −20 | −17.056 | 0.000 |
| −25 | −21.556 | 0.000 |

**Strictly linear, no saturation anywhere**: realized = 0.691 − 0.891·|request|, R² ≈ 1. The 0.891
slope is the fixture's transmission ratio; the +0.691 pN intercept is the fixture's built-in
`PRE_NM = 2.0 nm` preload. The filament does not move at all (0.000 nm), so the bond load is imposed
cleanly.

Contrast with `explicit-s2-l40` at overlapping low assisting loads (SM4 smoke, same prepared state):

| F req | explicit realized | fixed-anchor realized | explicit |F8| total |
|---:|---:|---:|---:|
| −3 | −1.943 | −1.911 | 6.46 |
| −6 | −2.174 | −4.545 | 7.97 |
| −10 | −3.081 | −8.094 | 7.86 |

The two fixtures **agree at −3 pN** (−1.94 vs −1.91) and diverge sharply beyond it. Note that on the
explicit fixture the *total* |F8| is 6.5–8 pN while the *axial* component is only 2–3 pN — the bond
force is largely off-axis, because the laterally-compliant beam redirects it. The fixed-anchor
fixture has no such path.

---

## 3. Survival results

| F req (pN) | realized (pN) | RMST (ms) | RMST 95% CI | median (ms) | censored |
|---:|---:|---:|---|---:|---:|
| 0 | +0.691 | 1.4348 | [1.223, 1.647] | 0.9475 | 0 |
| −1 | −0.173 | 0.9281 | [0.812, 1.045] | 0.6750 | 0 |
| −2 | −1.040 | 0.6046 | [0.529, 0.680] | 0.4025 | 0 |
| −3 | −1.911 | 0.3675 | [0.323, 0.412] | 0.2775 | 0 |
| −4 | −2.786 | 0.2401 | [0.211, 0.269] | 0.1950 | 0 |
| −5 | −3.664 | 0.1662 | [0.151, 0.182] | 0.1400 | 0 |
| −6 | −4.545 | 0.1213 | [0.109, 0.133] | 0.1025 | 0 |
| −8 | −6.315 | 0.0757 | [0.068, 0.084] | 0.0625 | 0 |
| −10 | −8.094 | 0.0570 | [0.051, 0.064] | 0.0400 | 0 |
| −15 | −12.565 | **0.0517** | [0.045, 0.058] | 0.0375 | 0 |
| −20 | −17.056 | **0.0517** | [0.045, 0.058] | 0.0375 | 0 |
| −25 | −21.556 | **0.0517** | [0.045, 0.058] | 0.0375 | 0 |

Lifetime falls monotonically — the expected slip-side behaviour — and then **hits a hard floor of
0.0517 ms that is identical at −15, −20 and −25 pN.**

### The floor is the force-independent terminal step

Attachment lifetime = T(ADP→NONE, force-dependent) + T(NONE→ATP, force-INDEPENDENT at `atpOn` = 2e4/s
⇒ 0.05 ms). Once assisting load makes the ADP step essentially instantaneous, total lifetime is
entirely the terminal rigor step. **0.0517 ms ≈ 1/atpOn.** Beyond ~−10 pN this arm is therefore
**blind**: the observable no longer contains information about the force-dependent step.

---

## 4. Kinetic fit — and a correction to the expected identifiability

### 4a. Which parameter this arm actually constrains

The task anticipated that the assisting arm would identify `xSlip`. **It does not, and cannot.**
With

```
g(F) = aCatch·exp(−F·xCatch/kT) + aSlip·exp(+F·xSlip/kT)
```

at **negative** F the *catch* exponential **diverges** while the slip term decays. So the assisting
arm is dominated by the catch branch and carries almost no slip information. This is a property of
the coded law, confirmed by the data:

| parameter | fitted | coded | verdict |
|---|---|---|---|
| **xCatch (nm)** | **2.4693 ± 0.0643** | 2.5 | **−0.5σ — recovered, ±2.6 %** |
| k0 (1/s) | 1044.6 ± 41.6 | 1000 | +1.1σ ✔ |
| xSlip (nm) | 9.44 ± 110 | 0.4 | **UNIDENTIFIABLE** (SE 12× the value) |

χ²/dof = 0.17. **This arm gives the single most precise xCatch determination in the campaign
(±2.6 %)** — better than the opposing arm's ±7.6 % — because the divergent catch exponential is a
much stiffer constraint than the decaying one.

**`xSlip` is identified on the OPPOSING arm** (falling limb beyond the optimum), where the production
grid returned 0.4095 ± 0.0251 nm. See the opposing-arm findings.

### 4b. No Jensen correction is needed here

The within-event load SD is **0.000 pN**: `stepC` (the fixed-anchor mechanics path) is deterministic —
it applies no Brownian force. The convexity bias that inflated the explicit arm's `k0` by 36 % is
therefore structurally absent, which is why the raw fit already returns k0 = 1045 ± 42 against a
coded 1000. This is a useful cross-check: **the two fixtures agree on xCatch (2.47 ± 0.06 vs
2.27 ± 0.17) and on k0 (1045 ± 42 vs 1041 ± 54) by two independent routes, one thermal and one
athermal.**

### 4c. Timestep resolution limit — cells that were excluded

At −15, −20 and −25 pN the measured ADP-step rate is pinned at exactly **400 000/s = 1/dt**: the
transition fires in the first timestep, so the rate is a discretisation artefact carrying no
information. These three cells are **excluded from all fits** (recorded in
`fit_results.json → dropped_cells`). Fitting through them (as a naive fit does) inflates χ²/dof from
0.17 to 1113 and drives xCatch to a meaningless 0.01 nm.

**⇒ The identifiable assisting window on this fixture is |F| ≲ 10 pN, bounded by dt, not by the
fixture.** Extending it requires a smaller dt, not a different fixture.

---

## 5. Health

0 invalid states, 0 solver failures, 0 forbidden transitions, 0 censored events / 2400. Detachment
pathway `NONE(rigor)->ATP` for 100 % of events.

---

## 6. Pooling rule

A joint kinetic fit across both arms is **permissible only** with fixture identity retained, the
kinetic law evaluated against realized bond load, and the fixture-specific mechanics handled
explicitly (explicit-S2 is thermal and buckles; fixed-anchor is athermal and does not). The two arms
are reported separately here and are **not pooled**. Their independent agreement on xCatch and k0 is
presented as a cross-check, not as a combined estimate.
