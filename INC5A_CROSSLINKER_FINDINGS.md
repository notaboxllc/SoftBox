# Increment 5a — passive crosslinker static spring + double-ended fil↔fil gather (findings)

> **Increment 5b (Bell-model unbinding + link-lifecycle death half) is appended at the bottom of this
> file** — chose to §-extend the inc-5 findings rather than spawn `INC5B_*`.

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

---

# Increment 5b — Bell-model crosslinker unbinding + link-lifecycle (death half)

**Status: DONE / green.** On 5a's pre-placed-link scene: the EWMA(boxcar) strain track + the Bell
off-rate + per-step stochastic break (wang-hash) + the lifecycle DEATH half (sentinel self-write) + the
one `if(active)` gather guard. Wired into both runners. `BoA-v1ref` byte-clean; production
(`GlidingHarness`) byte-unchanged. (Same harness/script as 5a — `run_xlink.sh` now runs 5a **and** 5b.)

Scope is **death only**. OUT (→ 5c): formation / `checkToLink` / broad-phase FIL×FIL candidates /
free-slot allocation / stream-compaction. Links remain pre-placed; 5b only lets them *die*.

## 1. The lifecycle contract (items 1–4 implemented; item 5 = 5c)
- **Pool = fixed-capacity SoA** — `CrosslinkerStore(capacity C, nSeg)`. The SoA arrays + the CSR loop
  bound are `counts[0]=C`. (For the 5b scenes C = #pre-placed; the field is shaped so 5c's
  formation/allocation extends it with no rework.)
- **One authoritative sentinel-encoded lifecycle field** — `IntArray linkState`, mirroring motor
  `boundSeg`: `>=0` = `LINK_ACTIVE`, `<0` = `LINK_FREE` (free/dead, inert, allocatable in 5c). Single
  source of lifecycle truth (no scattered booleans). Unused capacity slots start `LINK_FREE` with a
  negative key (`linkFilA=-1`) so the CSR skips them (key<0) AND the gather guard skips them.
- **Exactly one `if(active)` gather guard** — `segGatherA`/`segGatherB` each gained one branch
  (`if (linkState[li] < 0) continue;`); `bruteGather` gained the matching guard (it is the reference).
  The CSR template (`CrossBridgeSystem.csrHistogram/Scan/Scatter`) stays **reused verbatim** — dead
  links keep their key ≥0 so they remain in the CSR index; the gather guard is what drops their payload.
  This was the **only** change to the proven 5a gather (no pause/report needed).
- **Death = self-write** — a link breaking this step self-writes `LINK_FREE` into its own `linkState[k]`
  (race-free, no allocation, no compaction; the data stays in place, just inert).

## 2. The strain track is a BOXCAR, not an exponential EWMA (a discovery)
v1's `strainTrack` is `ValueTracker(Env.filLinkStrainToAve=10)` — a **10-slot sliding-window (boxcar)
circular buffer**, `averageVal() = sum(all 10)/10` **always** (initial zeros included until filled), NOT
an exponential EWMA (the recon's "EWMA" label is loose). Ported faithfully as `strainHist[k*10+p]` +
`strainPlace[k]` (the proven `forceDotHist`/`forceDotPlace` ring; the circular write sequence is
bit-identical to v1's `registerValue` pre-check-wrap). `STRAIN_WIN=10`.

## 3. Force law (faithful FilLink port) — `CrosslinkerSystem.unbind`
Per ACTIVE link, mirroring v1's per-step order (`applyTransForce` register → `ckLinkBreak`):
strain = max(linkLength−restLength,0)/restLength → push into the boxcar → aveStrain = sum/10 →
`k_off = linkOffConst + linkOffCoeff·exp(aveStrain·linkOffExp)` → `P_break = k_off·dt` →
wang-hash draw `u<P_break` ⇒ death self-write. v1 calls `ckLinkBreak` **before** applying force (returns
on break), so `unbind` runs **before** `linkForces` in the step: a link breaking this step is FREE before
the gather ⇒ contributes no force this step (matches v1). A dead link is skipped entirely — no strain
update, no break draw, no force (inert).

**RNG:** the reused v1 wang-hash, keyed per `(link, step, seed)` with salt **`0x584C4B42` ("XLKB")**,
distinct from the motor salts (NUC `0x4E55` / refractory `0x52465241` / release `0x4D54`). `u =
(h>>>1)/2147483647f`. Integer mixer ⇒ bit-identical CPU↔GPU, race-free (no atomics, no KernelContext).
One draw/link/step ⇒ the break kernel uses a `WorkerGrid1D localWork=64` (CLAUDE.md RNG gotcha; absent in
5a which had no RNG).

**Note — k_off at strain 0 is 2 /s, not 1.** With Env defaults (const=1, coeff=1, exp=2),
`k_off(0) = 1 + 1·exp(0) = 2 /s`. The prompt's "rate ≈ linkOffConst" at strain 0 is approximate; the
faithful v1 formula gives const+coeff at zero strain. Validated against the actual formula (2 /s).

## 4. Validation (numbers for the planner) — `./run_xlink.sh`, all gates PASS (GPU + CPU)
**CHECK #1 (GATE) — P_break + EWMA arithmetic vs v1.** (a) `k_off`/`P_break` at aveStrain
{0, 0.25, 0.5, 1, 2} vs v1's literal `ckLinkBreak` formula: **max Δ = 0.000 %** (constants/formula
faithful). (b) EWMA step-for-step (40-step ramp+hold, 10-slot boxcar) v2 (float32 storage) vs v1
`ValueTracker` (double): **max k_off Δ = 2.6e-6 %** (float32 storage floor, ≪ 0.1 %).

**CHECK #3 — empirical off-rate vs k_off·dt** (frozen pose, 20 000 links/strain, dt=1e-4, warmup 30,
3000 steps):
| strain | k_off (/s) | P_emp/step | k_off·dt | Δ% | deaths |
|---|---|---|---|---|---|
| 0.0 | 2.0000 | 2.0274e-4 | 2.0000e-4 | 1.37 % | 9 039 |
| 0.5 | 3.7183 | 3.7172e-4 | 3.7183e-4 | 0.030 % | 13 317 |
| 1.0 | 8.3891 | 8.3886e-4 | 8.3891e-4 | 0.0054 % | 17 989 |

The empirical break fraction/step matches `k_off·dt` across the Bell exp. The strain-0 1.37 % is sampling
noise (~1.3σ; fewest deaths ⇒ largest relative CI ≈ 1/√9039 = 1.05 %) — exactly the expected statistical
behavior (Δ shrinks monotonically as deaths grow). Spans strain 0 (k_off=2) + two nonzero strains.

**DEATH→INERT (contract items 3–4, full force pipeline).** A loaded link (|force| 1.49e-13 N while
active) breaks (step 35), self-writes `LINK_FREE`, and thereafter the gathered force is **exactly 0**
(`|gathered|=0`, `|brute|=0`, `gather−brute=0`) — the one guard makes it inert end-to-end.

**CPU≡GPU (break path).** The wang-hash break draw is **bit-identical** on both runners: after 400 steps,
GPU dead = CPU dead = 854, **0 mismatched links**. (No KernelContext; integer RNG.)

**all-OFF≡HEAD.** (a) 5b with unbinding OFF ≡ the 5a path, **bit-identical** (Δcoord/ΔuVec = 0, Brownian
on, GPU + CPU) — the lifecycle field + gather guard are no-ops when no link dies. (b) 5a's own
all-OFF≡HEAD (crosslinker pipeline over nLinks=0 ≡ bare filament) **still holds**, and all 5a gates
(rest hold, decay constant 0.0012 %, gather bit-exact, CPU≡GPU) reproduce unchanged.

## 5. v1 oracle posture (running-v1 oracle DEFERRED to 5c — carried forward)
No formation yet ⇒ no running-v1 bundle needed. 5b is validated against v1's **formula/arithmetic**
(`ckLinkBreak` + `ValueTracker`), the analytic-oracle posture (same as 5a). The k_off/P_break/EWMA are
bit-exact to v1's expression (0.000 % / float32 floor), and the empirical rate matches `k_off·dt` through
the actual kernel. **The running-v1-oracle remains deferred to 5c**, where formation steady-state
(formation ≈ dissolution, link-count plateau vs `xLinkConc`) genuinely needs a running v1 bundle — carry
this flag forward.

## 6. Carry-forward / open
- **fracMove on death (deferred to 5c).** v1's `getLinkCt` is recomputed each step, so a death changes
  surviving links' `fracMove = 0.4/max(ct)`. 5b keeps `filLinkCt` static (deaths don't recompute it) —
  the decisive 5b gates don't depend on it (off-rate is force-free; death-inert has no survivors). The
  dynamic link-count → fracMove recompute pairs naturally with formation (counts change a lot) in 5c.
- **Lifecycle field shaped for 5c.** `linkState >= 0 = ACTIVE`, `<0 = FREE/DEAD`. 5c formation allocates
  FREE→ACTIVE on the same field and may subdivide the negative space (like `boundSeg`'s `FREE_COOLDOWN`)
  with no change to the `>=0`=ACTIVE contract or the gather guard.
- Next: 5c (formation + broad-phase FIL×FIL + free-slot allocation + the `STORE_CROSSLINKER` publisher +
  the running-v1 steady-state oracle), torsion (`applyTorsionForce`), 5d (Arp2/3).
