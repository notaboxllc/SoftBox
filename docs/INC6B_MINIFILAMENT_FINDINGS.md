# Increment 6b — myosin minifilament (backbone owns N dimers, single-ended one-pass gather): findings

**Status: DONE (2026-06-17). All 5 gates PASS on GPU + CPU.** The central favorable recon finding realized.
v2 = SoftBox (`softbox/`); v1 oracle = `BoA-v1ref` (read-only, byte-clean). Plan: `INC6_MYOSTRUCT_RECON.md`
§2 (minifilament = single-ended, reuse the `CrossBridge` CSR one pass), §3b (v1 `MyoMiniFilament`), §6.

## What 6b is
A **minifilament** = a rigid-rod **backbone** (length 0.180 µm, radius 0.005 µm) that **owns** N dimers
(v1 `numMyoDimersEachEnd=8` ⇒ 16 dimers / 32 heads), each dimer's `myo1.myoRod.end1` tethered to a
body-local **axial** attachment point near a backbone end. Validated on a pre-placed **isometric bed**,
**static** assembly, heads **FREE** (no cross-bridge / glide). The backbone is the **3rd `RigidRodBody`
instance** (FilamentStore #1, MotorStore #2): the shared Brownian / integration / derived / drag systems
run over it UNCHANGED — the entity-agnostic abstraction held a third time.

## The architecture (recon §2, confirmed) — SINGLE-ENDED, one pass: LESS machinery than the crosslinker
The backbone **owns** its dimers — one consumer, many writers, each dimer keyed to exactly one backbone
(`headBackboneSlot`) — the **same shape as motor→segment** (`boundSeg`), **NOT** the crosslinker
double-ended two-pass. So:
1. **tether** (`MiniFilamentSystem.tether`): each dimer self-writes the rod-side force+torque into its own
   rod slot (additive with its 6a rod-coupling self-write; one writer/rod ⇒ race-free, no atomics) and
   stores the **backbone-side reaction** in `miniData[6·d..]`.
2. **CSR-inverse** keyed by `headBackboneSlot`: `CrossBridgeSystem.csrHistogram/csrScan/csrScatter`
   **REUSED VERBATIM** (pure int-key/counts ops; passed a `miniCounts` with `[0]=nDimers, [3]=nBackbones`).
3. **`backboneGather`**: each backbone sums its dimers' stored reactions into its `forceSum`/`torqueSum`
   (the `segGather` pattern). **One pass — no second pass, no compound key, no new gather machinery.**

## Faithful port (component-port; v1 = the per-component oracle)
- **Tether force law** (v1 `constrainEnd1/End2Dimers`, `MyoMiniFilament.java:436-528`): `F =
  myoMiniFilFracMove·1e-6·strain / (dt·(1/rod.bTransGam.y + 1/bb.bTransGam.y))` — note **plain
  perpendicular drag, NO `moveCoeff` projection** (simpler than the dimer rod-coupling); applied at
  `myo1.myoRod.end1` toward the attach point, equal-and-opposite on the backbone.
- **Alignment torque**: `myoMiniFilAlign·(π/180)·ang / ((1/rod.bRotGam.y + 1/bb.bRotGam.y)·dt)`, restoring
  `rod.uVec` to the **±backbone axis** (−uVec at end1, +uVec at end2), rest angle 0; `accurateAcos`
  (device-safe), v1 `checkPt3D` → degenerate-axis guard.
- **Axial placement** (v1 `makeMyosinDimers:393-424`): the attach offset is **purely axial** (y=z=0 in the
  body frame) ⇒ `attach = backbone.coord + axial·backbone.uVec` — needs only the backbone `uVec` (not
  yVec/zVec), which keeps the tether kernel within the 15-arg cap.
- **v1 defaults** (verified 2026-06-17): `myoMiniFilFracMove=0.07` (`Env.java:167`), `myoMiniFilAlign=0.01`
  (`:173`), `numMyoDimersEachEnd=8` (`:371`), backbone `length=0.180`/`radius=0.005`/`headZone=0.05`.

## Validation (co-developed small-scale vs `BoA-v1ref`, not fixtures)

| # | gate | result |
|---|---|---|
| **A** | **gather == brute** + **tether arithmetic** vs an independent v1 **double** reference + **momentum** | gather == `bruteGather` **bit-identical (Δ=0.0)**; tether vs v1 ref **maxRel 3.7e-8** (≪0.1%); momentum (gathered backbone force + Σ rod self-writes) = **2.0e-19 N** (≈0, equal-and-opposite). |
| **B** | **isometric hold** | Brownian-off: rest is an **exact fixed point** (max tether strain 8.9e-8 µm); Brownian-on: **bounded thermal** steady state (max strain 2.8e-2 µm < 0.10 — the soft tether (coeff 0.07) lets each dimer rod jiggle, no fly-apart). |
| **C** | **CPU≡GPU** | deterministic (500 steps, Brownian off): max\|Δmotor\| = **4.5e-6 µm**, max\|Δbackbone\| = **5.4e-7 µm** (float32 last-bit, non-chaotic). |
| **D** | **FDT self-consistency** | tether strain **stationary** (halves μ1=3.21e-2, μ2=3.28e-2 µm, Δ2.2%) + bounded — the assembled minifilament sits at the fracMove scheme's own FDT steady state (per the carry-forward, **dt is a physics parameter**; no dt-independent ½kT anchor — cf. §6a-thermo). v1 cross-check informational. |
| **E** | **all-OFF ≡ HEAD** | tether off ⇒ the motor body evolves identically to a bare 6a dimer-bed run, **bit-identical (Δ=0.0)**. |

**Force-coverage audit:** tether spring — +F rod / −F backbone (once, gate A momentum); alignment torque —
+τ rod / −τ backbone (once); the backbone-side reactions reach the backbone via the single gather (gate A
gather==brute). No force zero-dropped, none double-applied.

## Files
New: `MiniFilamentStore.java`, `MiniFilamentSystem.java`, `MiniFilamentHarness.java`, `run_minifil.sh`. No
existing file touched (`CrossBridgeSystem` CSR REUSED VERBATIM, byte-unchanged; production / `GlidingHarness`
byte-unchanged; `BoA-v1ref` byte-clean). `STORE_MINIFILAMENT` deferred (static assembly ⇒ no broad-phase
publisher this increment).
```
./run_minifil.sh              # GPU + CPU cross-check (8 backbones × 16 dimers): gates A–E
./run_minifil.sh -cpu         # CPU runner only (triage)
./run_minifil.sh -3js threejs_minifil -n 4   # viewer (backbone + dimer carpet)
```

## TornadoVM notes (reuse)
- The tether math is **inlined into the top-level @Parallel kernel** (the 6a 600-node inline-cap pattern);
  only `accurateAcos` is an inlined helper.
- `CrossBridge.csr{Histogram,Scan,Scatter}` are reused VERBATIM as graph tasks via `addSingle` worker grids
  (WorkerGrid1D(1)); the parallel kernels use `pad(n)`/localWork=64.
- The 15-arg tether kernel is at the cap (motor body 7 + backbone coord/uVec/invDragY 3 + per-dimer 5);
  the axial-only attach (no yVec/zVec) + a precomputed `bbInvDragY` were what kept it ≤15.

## Next
- **The glide integration** (recon check #4, OUT of scope here): heads binding/walking on actin → force
  transmission *through a bound head* to the backbone; + dynamic minifilament assembly/`myoMiniLifetime`.
- **6c nodes** (separately, when unblocked — needs a fresh v1 node snapshot per the recon settledness gate;
  reachable on a fixed anchor without the membrane subsystem).
