# Increment 5a — passive crosslinker static spring + double-ended fil↔fil gather (findings)

**Status: DONE / green.** The crosslinker store + static translational spring force law + the new
double-ended filament↔filament gather are built, wired into both runners (CPU sequential + GPU
TaskGraph), and validated by the two static port-equivalence checks. `BoA-v1ref` byte-clean; production
(`GlidingHarness`) byte-unchanged. Builds on the inc-4 CSR-inverse gather template and the inc-1
integrator, both reused verbatim.

Scope is **5a only**: links are pre-placed and static. OUT (later sub-increments): formation /
`checkToLink` / broad-phase segment↔segment (5c); Bell unbinding / `ckLinkBreak` (5b); torsional spring
`applyTorsionForce` (deferred); Arp2/3 (5d).

---

## 1. What was built

- **`CrosslinkerStore.java`** — SoA store. Topology by INTEGER FILAMENT SLOTS (never class identity):
  `linkFilA[k]`/`linkFilB[k]` + arcs `loc1[k]`/`loc2[k]`; static per-filament link count `filLinkCt`
  (v1 `getLinkCt`, for `fracMove`); per-link reaction scratch `xlinkData` (stride 12: A-force[0..2],
  A-torque[3..5], B-force[6..8], B-torque[9..11]); `xlParams` = {restLength, fracMoveBase, dt}.
- **`CrosslinkerSystem.java`** — `linkForces` (the spring, faithful FilLink port), `segGatherA` /
  `segGatherB` (the two gather passes), `bruteGather` (the exactness reference).
- **`CrosslinkerHarness.java` + `run_xlink.sh`** — the two checks, CPU≡GPU, all-OFF≡HEAD.
- **`SpatialBodyView.STORE_CROSSLINKER = 2`** (recon §2; additive — the broad-phase publisher arrives
  with formation in 5c).

`GlidingHarness` is left **byte-unchanged** (strongest "production path untouched"). "Wire into
GlidingHarness buildScene/CPU-step/GPU-buildPlan" was honoured as the three-point wiring PATTERN,
applied in a dedicated harness — exactly as every prior increment (`run_motor`/`motorbody`/`xbridge`/
`stroke`). The 5a checks need a 2-parallel-filament micro-scene the gliding bed can't host, and the
gliding 23-kernel single TaskGraph is near its bytecode limit. ("check harness wiring" = recon
silent-in-scope.)

---

## 2. The gather design (planner's decision — implemented as specified)

**Two-pass single-ended CSR-inverse gather**, reusing the validated `CrossBridgeSystem` CSR template
(`csrHistogram → csrScan → csrScatter`) **verbatim**, run twice with two key arrays:

1. Each crosslinker computes its spring reaction **once** in `linkForces` and **self-writes** both side
   reactions into its own `xlinkData` row — `{+forceVec, +torque}` tagged to `linkFilA[k]`,
   `{−forceVec, −torque}` tagged to `linkFilB[k]` (self-write ⇒ race-free, exactly like motor `bondData`).
2. **Pass A:** CSR-inverse keyed by `linkFilA` → `segGatherA` sums each filament's A-side reactions into
   `forceSum`/`torqueSum`.
3. **Pass B:** CSR-inverse keyed by `linkFilB` → `segGatherB` sums each filament's B-side reactions into
   the **same** `forceSum`/`torqueSum` (additive `+=`).

Each pass is structurally identical to the proven motor→segment gather (different key + payload source),
so **race-free / no-atomics / no-KernelContext / bit-identical-CPU↔GPU** carry over by construction. The
single-pass unified-2N-CSR alternative was not needed — two-pass hit no wall. **No scope expansion, no
bail.**

Result: the two-pass gather == an O(nLinks·nSeg) brute per-link sum, **bit-identical** (same value, same
order) on both runners.

---

## 3. The force law — faithful FilLink.applyTransForce port + the porting subtlety (RESOLVED)

Port of v1 `FilLink.applyTransForce` (`BoA-v1ref/boxOfActin/FilLink.java:198-217`), minus the strain
track + `ckLinkBreak` (5b) and torsion (deferred):

```
pt1 = end1(filA) + loc1·uVec(filA);   pt2 = end1(filB) + loc2·uVec(filB)
linkVec = pt2 − pt1;   linkLength = |linkVec|
stretch = max(linkLength − restLength, 0)                 restLength = 0.0125 µm (FilLink.java:28)
fracMove = 0.4 / max(linkCt(A), linkCt(B), 1)             (0.4 = FilLink.java:208)
curForceMag = fracMove·1e-6·stretch / ( dt · (1/γ1 + 1/γ2) )   γ = bTransGam.x (PARALLEL drag of each fil)
forceVec = curForceMag · linkVec                          ← linkVec NOT normalized (v1 quirk; ported verbatim)
A-side: +forceVec at pt1 (force + positional torque about filA COM, R in metres)
B-side: −forceVec at pt2 (force + positional torque about filB COM)
```

**The bail-critical porting subtlety — RESOLVED, no distortion, commit.** v1's force is "damping-limited":
the `/dt` and `1/(1/γ1+1/γ2)` encode "relax toward rest by `fracMove` per step" expressed as a force. The
force→position path was traced end-to-end:

- v1 `FilLink.incForceSum(F, pt)` → adds `F` to `forceSum` + positional torque `r×F` (r in metres). →
- v1 `FilSegment.moveThing` (`FilSegment.java:609-685`): `bVeloc = 1e6·bForceSum/bTransGam` (µm/s);
  `incCoord(dt, veloc)` ⇒ `pos += dt·v`.
- v2 `RigidRodLangevinIntegrationSystem.integrate` is the **line-for-line port** of that `moveThing`
  (`v = 1e6·F/γ; pos += dt·v`), already FDT/deflection/chain-validated since inc 1.

So the `/dt` in the force law **cancels** the `·dt` in integration ⇒ the per-step relaxation is
**dt-INDEPENDENT**. Drag appears in **both** the force-law denominator (`1/Σγ⁻¹`) and the integrator
(`÷γ`) — this is v1's **design**, not a double-count. v2 reproduces v1's effective relaxation exactly
because it ports **both** unchanged. The `forceSum → RigidRodLangevinIntegration` path **can** represent
v1's damping-limited form faithfully — verified by check #2 below (analytic match + perfect
dt-invariance). **No bail.**

### Force-coverage audit (one path each — never zero, never doubled)
| force | head/A-side | other/B-side | path |
|---|---|---|---|
| F (translational spring) | +forceVec on filA (gatherA, once) | −forceVec on filB (gatherB, once) | equal-and-opposite by construction (`nF = −F` in float, exact) |
| positional torque | r_A × (+forceVec) on filA | r_B × (−forceVec) on filB | once each (gather == brute, bit-identical) |

(Torsional spring `applyTorsionForce` is OUT of 5a scope — deferred.)

---

## 4. Validation results (numbers for the planner) — `./run_xlink.sh`, dt=1e-5, M=4000

All gates **PASS** on the GPU TaskGraph + CPU cross-check, and on `-cpu` alone.

**CHECK #1 — rest hold (equal-and-opposite reactions land; no spurious force at rest).**
- (a) Exact rest (sep = restLength, Brownian off): **max|Δcoord| = 0.00 µm over 4000 steps** — zero force
  at zero stretch ⇒ zero velocity, zero motion. No spurious rest force.
- (b) Stretched release (sep 0.0250 → 0.0125 µm, Brownian off): **max COM-z drift = 1.8e-7 µm =
  0.0014 % of the relaxation** — equal-and-opposite reactions keep the pair's COM stationary while the
  separation relaxes toward restLength. (The 0.0014 % is sub-ULP geometry: `loc=0.5·L` lands the attach
  point at the COM to within a float ULP, leaving a sub-fm torque — float32, ≪ 0.1 % logic floor.)

**CHECK #2 (DECISIVE) — perpendicular-stretch relaxation: decay constant + dt-independence.**
Decay rate from v1's exact arithmetic: `Δε = −k(L)·ε`, `k(L) = fracMove·(γ_par/γ_perp)·L`
(γ_par via the force-law denominator; γ_perp via the perpendicular integration; the extra `L` = v1's
non-normalized linkVec). For the std 32-monomer segment: γ_par = 2.3885e-8, γ_perp = 3.3088e-8 N·s/m.
- **measured per-step decay rate k = 0.00365176** vs **analytic (v1 arithmetic, L-matched) = 0.00365172**
  → **match = 0.0012 % of the decay rate** (≪ 0.1 % ⇒ float32, faithful port).
- **decay constant τ = 273.84 steps = 2.738 ms** (at dt = 1e-5).
- **dt-independence: k(dt/10) = 0.00365176, Δ = 0.0000 %** — the damping-limited `/dt·dt` cancellation is
  faithfully reproduced (the sharpest probe of the porting subtlety; a double-counted or omitted drag
  would scale with dt).

**GATHER EXACT** — two-pass CSR gather == brute per-link sum: **max|gather−brute| = 0.00** (force and
torque), bit-identical, all sampled steps, both runners.

**CPU≡GPU** — the crosslinker **force+gather is BIT-IDENTICAL** CPU↔GPU (forceSum diff = 0.00 exactly at
steps 1/10/100 — the bail-critical "two-pass CSR can't be made bit-identical CPU↔GPU" is **disproven**).
The pose diverges only by integration **float32 last-bit accumulation** over the (non-chaotic) 4000-step
relaxation, saturating at **9.31e-10 µm ≈ 0.5 ULP at the 0.0125-µm coord scale** (worst relative
6.5e-8 ≪ 0.1 % logic floor) — the standing CPU≡GPU standard ("bit-identical to printed precision").

**all-OFF≡HEAD** — the crosslinker pipeline run over nLinks=0 (tasks present, empty link set) is
**bit-identical** (max|Δcoord| = 0.00, max|ΔuVec| = 0.00, Brownian on, 1500 steps) to the bare filament
path, on **both** GPU and CPU. The crosslinker code is a true no-op when no links are placed. Production
(`GlidingHarness`) is additionally byte-unchanged.

---

## 5. v1 oracle posture for the decay constant (deferral, documented)

The decay constant is **fully determined** by the force law + integrator, both confirmed faithful:
- the per-step **force value** was numerically matched to v1's `applyTransForce` formula bit-exactly
  (one step: 3.91865e-15 N = `curForceMag·linkLength`, the value v1 would compute);
- the **integrator** is the inc-1 line-for-line port of v1 `FilSegment.moveThing`, FDT/deflection/
  chain-validated;
- the running v2 **matches the analytic-from-v1-code decay to 0.0012 %** and is **dt-invariant**.

A full v1-`FilSegment` standalone run (constructing v1's object graph — Crucible/`theBox`, `WorkerScratch`
pool, monomer bookkeeping, Env parameter system — for a 2-segment scene) was **assessed as
disproportionately invasive for 5a and deferred** — the same judgment the project applied to the §6.8
fp64 standalone ("wildly invasive … bail per the prompt"). Under the porting-equivalence oracle posture
(CLAUDE.md "Oracle posture", inc-5-onward), the captured-and-reproduced v1 micro-behavior is the
damping-limited relaxation whose τ is set by the force law + drag — established analytically from v1's
exact code and reproduced by v2 to float precision. The broader v1 machinery becomes natural in 5b/5c
(formation/unbinding need it anyway); a direct v1-`FilSegment` decay capture can be added there if the
planner wants an independent run-time number.

---

## 6. Carry-forward / open

- The two-pass gather (keyed by integer filament slots, race-free, bit-identical CPU↔GPU) is **proven
  before any formation/unbinding dynamics get built on top of it** — the 5a goal.
- v1's `getLinkCt` accumulates per-step as links are processed (order/thread-dependent for
  multi-link-per-segment); 5a uses the **total static count** (= 1 here ⇒ fracMove = 0.4 exactly, the
  intended single-link value). Multi-link-per-segment `fracMove` faithfulness is a **5c (formation)**
  concern, flagged.
- `restLength` is commented "nm" in v1 (`FilLink.java:28`) but the value 0.0125 is **µm** (12.5 nm) —
  consistent with `linkLength` in µm. Ported as 0.0125 µm.
- Next: 5b (Bell strain unbinding `ckLinkBreak`), 5c (formation + broad-phase segment↔segment +
  `STORE_CROSSLINKER` publisher), torsion (`applyTorsionForce`), 5d (Arp2/3).
