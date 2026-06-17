# Increment 6 glide integration (part 2) — minifilament-glide: findings

**Status: DONE (2026-06-17). All 4 active gates PASS on GPU + CPU.** The 6b single-ended backbone gather
is now **load-bearing** — it carries the real cross-bridge force from bound, cycling heads through the
tether to the backbone. v2 = SoftBox (`softbox/`); v1 oracle = `BoA-v1ref` (read-only, byte-clean).
Combines 6b (`MiniFilamentStore`/`MiniFilamentSystem`) + dimer-glide (`CrossBridge` binding + the
`boundSeg`-gated `DimerCoupling`) on the 4b-iii pinned-filament setup. **No existing file touched** — a
new harness only.

## What this is
A pre-placed **static** minifilament (a rigid-rod backbone OWNING N dimers, 6b) whose dimer heads
**bind and walk** on a single **pinned filament** via the existing `CrossBridge` (4b-ii, byte-unchanged);
the **backbone gathers the collective load** through the 6b tether. The genuinely-new thing vs 6b
(isometric, free heads) and dimer-glide (no backbone) is **scale + load**: the full force-transmission
chain is now load-bearing end-to-end:

> bound head → `CrossBridge` → head body → J1/J2 → rod → dimer-coupling (6a) → minifilament tether (6b)
> → `backboneGather` (the single-ended one-pass CSR, 6b)

**This is the headline:** the gather that was validated *isometrically* in 6b now transmits a real load,
and still equals a brute per-dimer sum **bit-identically**, CPU≡GPU, momentum-conserving.

## v1 verification — NO minifilament-level binding gate (so nothing new to port)
Checked `BoA-v1ref/boxOfActin/MyoMiniFilament.java`: `constrainEnd1Dimers`/`constrainEnd2Dimers`
(`:436-528`) tether the dimers to the backbone **unconditionally** — the only per-dimer skips are
`cohesionOnDevice()` (device-offload bookkeeping) and `removeMe` (lifecycle), **never a binding state**.
`countBoundMotors` (`:317-332`) is diagnostic-only (mirrors the ThreeJS inspect payload). So the **ONLY**
binding-dependent gate anywhere in the myosin-structure couplings is the **per-dimer** lever-align
(`MyosinDimer.java:276`, already ported in dimer-glide). **No minifilament-level gate exists ⇒ no port
decision, no pause.**

## Geometry — the 6b rest config + a pinned filament over the end2 head-field (single-polarity engagement)
The backbone lies along **+x** at z=0 (FREE — no anchor; the tethers hold the structure). Its
6b-splayed dimers project heads up/down in z. A **+x-oriented** filament is placed just above the **end2**
up-head field. The v1 bind predicate's `rodDotFil ≥ 0` gate (`BindingDetectionSystem.reachTestDistSq:82`)
then admits **only the end2 dimers' UP heads** (their rods point +x); the end1 dimers' rods point −x
(`rodDotFil < 0` vs a +x filament) ⇒ they do **not** bind. So on a **single** filament exactly **one
polarity engages** — the correct physics. (Genuine bipolar **stall/contraction** — both ends tugging —
needs the **two-antiparallel-filament** geometry, the explicit next increment.) Result: 8 of 32 heads per
minifilament bind (one up-head per end2 dimer), enough to load the backbone richly.

## Per step (one physics, two runners) over motor body + backbone (FREE) + filament (PINNED)
`[cycle] → zeroMot,zeroBb → joints(J1/J2) → dimerCouple(boundSeg-gated) → tether(6b: rod self-write +
miniData) → bbCSR(headBackboneSlot) → backboneGather → bond(CrossBridge) → applyHead → integM,integB →
deriveM,deriveB → register → zeroFil → filCSR(boundSeg) → filGather`. Two **independent** single-ended
gathers run each step — backbone-keyed (`headBackboneSlot`, sums tether reactions) and segment-keyed
(`boundSeg`, sums cross-bridge reactions) — both reusing `CrossBridge.csr*` VERBATIM. The filament is
pinned (forces gathered for the gate, not integrated).

## Validation (co-developed small-scale vs `BoA-v1ref`, not fixtures)

Config: 4 minifilaments × 16 dimers (32 heads) on 4 pinned filament segments (gate #1 / CPU≡GPU);
1 minifilament for #2/#3. Brownian off (deterministic mechanism validation, as dimer-glide); dt=1e-5.

| # | gate | result |
|---|---|---|
| **#1** | **force transmission UNDER LOAD** (the HEADLINE): backbone gather==`bruteGather`, fil gather==`bruteGather`, momentum, CPU≡GPU | After 200 held-ADPPi steps build real tether strain: **backbone gather==brute bit-identical (Δ=0.0)** at a non-trivial **load 2.81e-14 N** (>0 ⇒ genuinely loaded; the tether is ~0 at step 1 since the rods haven't displaced yet); **fil gather==brute bit-identical (Δ=0.0)** (32/128 bound); **momentum** \|Σmotor+Σbackbone+Σfil\| = **9.8e-20 N** (≈0 — cross-bridge + tether are the only cross-entity forces, joints+dimer internal); **CPU≡GPU** (300 loaded steps) max\|Δmotor\|=**7.4e-7 µm**, max\|Δbackbone\|=**1.1e-7 µm** (float32 last-bit). |
| **#2** | **binding gates at population scale** (per-dimer lever-align; no minifilament-level gate) | 16 dimers set to mixed binding states (both-free / one-bound / both-bound): the lever-align **fires** in 11, is **suppressed** in the 5 both-bound dimers — **all match v1 `MyosinDimer:276`** bit-for-decision. Minifilament-level gate: **NONE** (verified, above). |
| **#3** | **bipolar collective** (emergent — observe, NOT gated on direction) | 1 minifilament, cycling + dynamic binding, 20k steps: the FREE backbone walks **Δx = +10.85 nm**, consistent with the **sign of the mean gathered net Fx** (motion tracks the gathered net); avgBound 8.00/32. Single-polarity engagement ⇒ the backbone walks (not stalls) — physically correct on one filament; stall needs antiparallel filaments. |
| **#5** | **all-OFF ≡ HEAD** | tether OFF ⇒ the motor body + filament evolve **bit-identically (Δ=0.0)** to the dimer-glide path; control: tether ON differs by **1.6e-2 µm** (the backbone coupling is real, not a silent no-op). |

(#4 CPU≡GPU folds into #1's deterministic cross-check. Brownian/FDT self-consistency of the free
backbone + tether DOFs is inherited unchanged from 6b gate D — adding cross-bridge load does not alter
the thermalization of the free coordinates; no new thermal physics here.)

**Force-coverage audit:** cross-bridge — +F head / −F segment (once, via filGather); tether spring —
+F rod / −F backbone (once, via backboneGather); tether align — +τ rod / −τ backbone (once); joints +
dimer-coupling internal to the motor body. Momentum gate confirms each cross-entity force applied
exactly once (Σ over all three bodies ≈ 0). No force zero-dropped, none double-applied.

## Files
New: `MiniGlideHarness.java`, `run_miniglide.sh`. **No existing file modified** — `CrossBridgeSystem`
(CSR + bond/applyHead), `MiniFilamentSystem` (tether + backboneGather), `DimerCouplingSystem` (the
`boundSeg` gate), `MotorStore`/`MiniFilamentStore`/`DimerStore` all reused **byte-unchanged**; production
/ `GlidingHarness` byte-unchanged; `BoA-v1ref` byte-clean. Structures default-off in production.
```
./run_miniglide.sh              # GPU + CPU cross-check: #1 gather-under-load, #2 gates, #3 bipolar, #5 all-OFF
./run_miniglide.sh -cpu         # CPU runner only (triage)
./run_miniglide.sh -3js threejs_miniglide   # viewer (a minifilament's heads walking on a pinned filament)
```
**Regression guard:** `run_dimer.sh` (6a, 6 gates), `run_minifil.sh` (6b, 5 gates), `run_dimerglide.sh`
(dimer-glide, 4 gates) all re-ran **bit-identical PASS** after this work (no existing source touched).

## Next within increment 6
- **The contractile two-antiparallel-filament geometry** — the minifilament's actual contractile
  function (both ends engaging opposite-polarity filaments, pulling them together) — the first genuinely
  contractile test; this is where bipolar **stall/contraction** appears.
- Dynamic minifilament assembly / `myoMiniLifetime`.
- **6c nodes** (needs a fresh post-stabilization v1 node snapshot per the recon settledness gate;
  reachable on a fixed anchor without the membrane subsystem).
