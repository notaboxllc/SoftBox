# v1 Contractility-Assay Survey — scene config + readout definitions (SURVEY ONLY)

**Purpose.** Ground-truth v1's *minimal contractility assay* — its scene config and the **exact**
definitions of its readouts — so the planner can scope a v2 contractile assay that reproduces v1's
readout set on the two-anchored-filament + minifilament geometry. **Survey only; no code, no runs,
no edits. `BoA-v1ref` byte-clean** (read at HEAD `8e23789`, tag `softbox-filref-2026-06-13`).

All file:line refs are in `~/Code/BoA-v1ref/boxOfActin/`, verified by direct read.

---

## 0. TL;DR — where the assay lives

| Concern | Source |
|---|---|
| Scene builder (minifilament) | `BoxOfActin.makeContractilityAssay()` — `BoxOfActin.java:3289-3345` |
| Stand-alone defaults | `applyContractilityDefaults()` — `BoxOfActin.java:3266-3287` |
| Assay state struct | `static class ContractAssay` — `BoxOfActin.java:141-168` |
| Filament chain builder | `FilSegment.makeStraightChain()` — `FilSegment.java:3992-4015` |
| Anchor pin (snap-back) | `Pin` class `:132-138`; `applyBenchmarkPins()` `:2236-2263` |
| **Tension capture** | `captureContractilityTension()` — `BoxOfActin.java:3453-3465` |
| **Running stats** | `accumulateContractilityStats()` — `BoxOfActin.java:3485-3507` |
| Bound-head count | `contractBoundMotors()` `:3511-3520` → `MyoMiniFilament.countBoundMotors()` `:317-332` |
| Cumulative-mean helpers | `contractAvg{Bound,Tension,TensionA,TensionB}()` `:3529-3532` |
| Step-loop hook | `BoxOfActin.java:1433-1435` (called after force-gather, before integrate/pin) |
| Frame JSON / HUD block | `ThreeJSWriter.java:258-279` |
| `[STATS]` console line | `reportContractilityStats()` `:3537-3543` |
| Params | `Env.java:275-292` |
| Param files | `ParameterFiles/contractilityAssay` (CPU), `contractilityAssay_gpu` (GPU), `…_noMotor`, `…_reversed` |

The **node** assay (`makeNodeContractilityAssay()` `:3379-3447`) is the v2-inc-6c analog: it reuses
the *same* anti-parallel pinned-filament scaffold, Pin registry, and **the same tension/stats readout
path verbatim** — only the load source differs (protein node carrying surface myosins vs the bipolar
minifilament). Documented here for completeness; the minifilament path is the focus.

---

## 1. Scene config (the minimal contractile unit)

### Geometry / chamber
- **Box** `4.0 × 0.3 × 0.2 µm` (long X = filament axis; thin Y/Z laterally confines the minifilament).
  `applyContractilityDefaults` `:3269-3271`; both param files set the same.
- **dt = 1e-5 s**, small + stable (`:3268`).

### The two filaments (anti-parallel, plus-ends pinned outward)
- **Count = 2**; each is an **N=13-segment** rigid straight chain (`contractFilNSegs` default 13,
  `Env.java:281`). Segment length = `(monCt+1)·halfmono` with `monCt = stdSegLength = 64`
  (`filSegLength:64` in the pf, `makeStraightChain` `:3993-3994`) ⇒ **Lfil ≈ 2.28 µm** per filament
  (the pf comment: `13 × (64+1)·0.0027 µm ≈ 2.28 µm`).
- **Arrangement = anti-parallel, overlapping centrally.** Built by `makeStraightChain(n, outerPt,
  buildDir, uVec, brownOff=true)` (`:3314`, `:3323`):
  - **Filament A** — body on **+X** side, pinned at the +X wall, **+yOff (= +0.05 µm)** in Y;
    `buildDirA = (−1,0,0)` (inward); normal polarity `uVecA = (+1,0,0)` ⇒ **plus end (end2) at the
    +X wall**, pinned. `anchorPtA = (anchorX, +yOff, 0)`. (`:3311-3317`)
  - **Filament B** — mirror image: body on **−X**, pinned at the −X wall, **−yOff**; `buildDirB =
    (+1,0,0)`; `uVecB = (−1,0,0)`; `anchorPtB = (−anchorX, −yOff, 0)`. (`:3320-3326`)
  - `brownOff=true` ⇒ **per-segment Brownian forcing is suppressed on the filaments** for a clean
    axial-tension readout (`makeStraightChain` `:4002`, `FilSegment.java:3990-3991`).
- **anchorX = boxXDim/2 − margin = 2.0 − 0.10 = 1.90 µm.** `margin = 0.10 µm` insets the pinned plus
  end from the box wall so the boundary force never touches the anchor segment (clean axial readout).
  (`:3302-3305`)
- **Central overlap ≈ 2·Lfil − 2·anchorX ≈ 2·2.28 − 3.80 ≈ 0.76 µm** (`:3334`). A `[CONTRACT][WARN]`
  fires if overlap < minifilament span (`:3341-3344`).
- **Y offset** `contractFilYOffset = 0.05 µm` (filaments at y = ±0.05; minifilament axis at y = 0).
  `Env.java:282`.

### The anchoring (what "anchor A / B" physically are)
- An **anchor = a hard end-pin on the outer (plus-end) terminal segment** of each filament
  (`contract.anchorSegA = filA[0]`, `:3315`; B `:3324`). The pinned endpoint is chosen by
  `pinEnd = sign(buildDir·uVec) > 0 ? end1 : end2` (`:3316`, `:3325`) and registered in `pinRegistry`
  as a `Pin{seg, whichEnd, anchorPt}` (`Pin` `:132-138`).
- **The pin is a position snap, not a spring.** `applyBenchmarkPins()` (`:2236-2263`), run **after**
  integration each step, hard-resets the chosen endpoint back to its fixed lab-frame `anchorPt` via
  `incCoord(anchor − currentEndpoint)` + `initialize()` (+ `markPoseDirty` on GPU). There is **no
  anchor spring constant** — it is an ideal isometric clamp. ⇒ the "anchor force" is **not** a spring
  restoring force; it is the **reaction the clamp must supply** (see §2).

### The minifilament (the load)
- **Exactly one** bipolar `MyoMiniFilament`, placed at the **overlap centre (0,0,0)**, oriented along
  **+X** (`:3331`), omitted under the `contractNoMotor` control.
- `numMyoDimersEachEndOfMiniFil = 8` (8 dimers per end; pf), `myoMiniLifetime = 1e9` (turnover
  effectively off — the minifilament does not stochastically die over the run).

### Static-field discipline
- **Actin turnover OFF** (filaments are static phalloidin-stiff rods): `initialFilaments`, `kATPOn1/2`,
  `capRate`, `cofilinRate`, `kHydrolysis`, etc. all zeroed (`:3275-3283` and the pf tail).
- **CPU vs GPU residency knob:** CPU uses `noMonomersSimd:true` (rigid static rods); **GPU MUST use
  `noMonomersSimd:false`** so the per-step host-pose sync (the minifilament GPU cohesion fix) stays
  on — otherwise the minifilament blows apart (`:3254-3258`, warning `:3290-3294`).

### Controls
- `contractNoMotor` — omit the minifilament (zero-tension negative control) (`Env.java:279`, `:3330`).
- `contractReversePolarity` — flip `uVec` so plus ends point **inward** ⇒ extension not contraction
  (`Env.java:280`, `:3307,3312,3321`).

### Relevant params + defaults
- Assay params: `Env.java:275-292` — `contractilityAssay`, `contractNoMotor`,
  `contractReversePolarity`, `contractFilNSegs (13)`, `contractFilYOffset (0.05)`.
- Stand-alone defaults (`applyContractilityDefaults` `:3266-3287`): `deltaT 1e-5`, box `4.0×0.3×0.2`,
  `stdSegLength 64`, `toFileInterval 100`, `runTime 0.2` (20k steps), turnover rates 0,
  `noMonomersSimd` inactive.
- Param-file scene (`ParameterFiles/contractilityAssay[_gpu]`, the values the 5× viewer used):
  `runTime 0.5` (50k steps), `toFileInterval 500`, `fracMove 0.0573`, `fracR 1.0`,
  `fracMoveTorq 0.01`, `aeta 0.1` Pa·s, `BTransCoeff 1.0`, `BRotCoeff 0.3`,
  `numMyoDimersEachEndOfMiniFil 8`, `myoMiniLifetime 1e9`, and the binding knobs
  `myosinStallForce 2.0`, `myosinReleaseRate 10.0`, `myoFBRBase 0.1`, `myoFBRExp 0.4`.
- Cross-bridge mechanics (global myosin params, `Env.java`): `myoSpring` default `1e-9 N/µm` (`:793`),
  hard break-force `myoBreakForce 12 pN` (`:801`), catch-slip base `kOff 100 /s` (`:824`).

---

## 2. Readout definitions (the headline — reproduce these in v2)

All readouts hang off the `ContractAssay` struct (`:141-168`), updated **once per step** at
`BoxOfActin.java:1435`, **after the per-step force gather and before `moveThing` integrates / the pin
snaps the endpoint back** (`:1433-1434`). That ordering is load-bearing: at that instant the anchor
segment's `forceSum` is exactly the reaction the clamp will have to supply.

### tension A / B (`tensionA_pN`, `tensionB_pN`) — the per-anchor primitives
`captureContractilityTension()` (`:3453-3465`):
```
forceA = (anchorSegA.forceSumX, forceSumY, forceSumZ)          // gathered net force on the anchor seg, N
addDeviceJointForce(anchorSegA, forceA)                        // GPU: + device chain (jointForceSum) reaction
tensionA_pN = Dot(forceA, buildDirA) * 1e12                    // project onto INWARD dir; ×1e12 N→pN
```
(B identical with `buildDirB`.) **Sign convention: positive = contractile** (net force pulling the
anchor inward, along `buildDir`); negative = extensile. **Units pN.**
- `addDeviceJointForce()` (`:3473-3480`) is a GPU-only fixup: the chain F3/F4 reaction is computed
  on-device into a separate `jointForceSum` and never gathered into the host `soaForceSum` the readout
  projects, so on GPU it is read back (`GPUMoveThing.readDeviceJointForce`) and added. **No-op on
  CPU** (the chain force is already in `forceSum` there). *This is a key v2 wiring flag — see §4.*

### tension (mean) — the headline scalar
`meanTension = 0.5 * (|tensionA_pN| + |tensionB_pN|)` — the **abs**-averaged two-anchor tension
(`accumulateContractilityStats` `:3488`; recomputed identically in `ThreeJSWriter:275`). pN.
- **avg (cumulative):** `contractAvgTension() = sumTension / statSamples` — a true time-average,
  accumulated every step (`:3492`, `:3530`).
- **recent:** `ewmaTension`, an EWMA with `α = STAT_EWMA_ALPHA = 0.005` (≈ 200-step / ~2 ms window)
  (`:3496-3503`, α at `:172`). Seeded to the first sample (`ewmaInit`).
- **peak:** `peakTension = max meanTension over the run` (`:3504`).
- Also tracked but not in the HUD panel: `contractAvgTensionA/B()` = `sumTensionA/B / statSamples` —
  **signed** cumulative per-anchor means (no abs) (`:3493-3494`, `:3531-3532`).

### anchor A / B (panel) = `tensionA_pN` / `tensionB_pN`
The instantaneous per-anchor signed axial reaction (pN) from `captureContractilityTension` above.
The panel's "anchor A / B" is exactly these two scalars (frame JSON `tensionA_pN`/`tensionB_pN`,
`ThreeJSWriter:265`); the anchor *points* `anchorA/B{x,y,z}` are also emitted (`:264,266-267`).

### bound heads (`instBound`)
`instBound = contractBoundMotors()` (`:3487`). For the minifilament assay that is
`mini.countBoundMotors()` (`:3513`), which counts **`myo.myoMotor.onFil`** over both ends' dimers
(`numMyoDimersEachEnd` dimers × 2 heads each) (`MyoMiniFilament.java:317-332`). **"bound" ⟺ `onFil`**
— the v1 equivalent of v2's `boundSeg ≥ 0`.
- **avg (cumulative):** `contractAvgBound() = sumBound / statSamples` (`:3491`, `:3529`).
- **recent:** `ewmaBound` (same α = 0.005) (`:3497,3501`).
- **peak:** `peakBound = max instBound over the run` (`:3505`).

### first bind @ (`firstBindStep`)
`if (firstBindStep < 0 && instBound > 0) firstBindStep = Env.counter;` — the global step counter at
the first step any head is `onFil`; stays −1 until then (`:3506`).

### Other quantities the assay computes
- `hasMotor` (bool) — true when a load source is present (false under `contractNoMotor`)
  (`contractHasMotor()` `:3524-3526`).
- Scene counters in the frame/HUD: `step` (`Env.counter`), `simTime` (`Env.simulationTime`),
  plus the generic segment/myosin/minifilament counts the viewer already shows.
- `[STATS]` console trace at frame cadence (`reportContractilityStats` `:3537-3543`):
  `tensionA, tensionB, boundMotors, avgBound, ewmaBound, avgTension, ewmaTension, peakTension`.

### Frame JSON schema (the exact panel feed) — `ThreeJSWriter.java:262-277`
```
"contractility": { tensionA_pN, tensionB_pN, anchorA{x,y,z}, anchorB{x,y,z} },
"stats": { step, simTime, boundHeads, peakBound, avgBound, ewmaBound,
           meanTension_pN, avgTension_pN, ewmaTension_pN, peakTension_pN,
           firstBindStep, hasMotor }
```

---

## 3. Snapshot currency / settledness

- **Present + current in `BoA-v1ref` @ `softbox-filref-2026-06-13`** — verified by reading the code
  directly from the frozen worktree (HEAD `8e23789`). The 2026-06-11 `contractility_view_5x` frames
  **predate the 06-13 freeze**, so the viewer snapshot is captured by it.
- **No drift vs active BoA.** Diffed `BoxOfActin.makeContractilityAssay()` (scene) and the readout
  path (`STAT_EWMA_ALPHA`, the `meanTension = 0.5·(|A|+|B|)` formula, the `firstBindStep` logic)
  between `BoA-v1ref` and `BoA-active` (`~/Code/BoA` @ `274ceea`): **identical** (only line numbers
  shifted, active is ~+80 lines higher). The assay + instrumentation are settled.
- **Emergent-quantitative vs structural (per the §8 / inc-5 posture):**
  - **Emergent — judge by physics/qualitatively in v2, NOT a v1 bit-match:** `tensionA/B`,
    `meanTension`, all `avg/ewma/peak` tensions, `boundHeads` and its `avg/ewma/peak`, `firstBindStep`.
    v1's minifilament/myosin contractility was **not** experimentally calibrated to an absolute
    contractile tension (same caveat that made v1 a non-quantitative crosslinker oracle), so v2's
    tension *magnitude* should be adjudicated by physics (force balance, sign, plateau behaviour,
    no-motor → ~0, reversed-polarity → extensile), **not** by reproducing v1's pN number.
  - **Structural counters — exact:** `step`, `simTime`, `hasMotor`, segment/myosin/minifilament
    counts, and the scene geometry itself.

---

## 4. v2 plug-in assessment

What v2 already has, and what each readout needs wired:

| Readout | v2 has | Needs wiring |
|---|---|---|
| **bound heads** (`onFil` count) | `CrossBridge` bound-state `boundSeg ≥ 0`; the minifilament (6b) owns its dimers' heads (`headBackboneSlot`) | Count heads with `boundSeg ≥ 0` across the minifilament's dimers — a host-side reduction; trivial. v2's `boundSeg ≥ 0` is the exact analog of v1 `onFil`. |
| **anchor A / B tension** | Per-segment `forceSum`/`torqueSum` from the validated `CrossBridge` `segGather` (force lands on segments); pinned-filament + per-step snap pattern already used in 4b-iii / dimer-glide / mini-glide | (a) the **two-anchored anti-parallel-filament scene** (not yet built — mini-glide is a *single* pinned filament); (b) read the **outer terminal segment's `forceSum` projected on the inward `buildDir`, BEFORE the pin snap**, ×1e12 for pN. |
| **tension (mean) + avg/ewma/peak** | the two scalars above | Pure host-side bookkeeping: `0.5(|A|+|B|)`, a cumulative sum/count, an EWMA (α=0.005), running maxima — replicate `accumulateContractilityStats` 1:1. No new physics. |
| **first bind @** | the bound-head count + step counter | Same trivial bookkeeping. |
| **counters / hasMotor** | step, sim time, store sizes | Direct. |

**Two flags for the planner:**

1. **The anchor force must include the chain (F3/F4) reaction, and it lives where v2's force-pull
   reads.** v1 hit exactly this on GPU: the device chain reaction sits in a *separate* `jointForceSum`
   not gathered into the host `forceSum` the readout projects, so v1 reads it back and adds it
   (`addDeviceJointForce` `:3473-3480`). v2's analog question: does the anchor segment's `forceSum`
   (as read for the tension projection) already contain **both** the cross-bridge gather **and** the
   chain bending/link reaction at the read point? If v2 keeps a separate chain-force accumulator (cf.
   `ChainBendingForceSystem`), the tension read must sum both, and on the device path needs a
   frame-cadence pull of the anchor segment's force (v2 is otherwise device-resident — this is a
   small, bounded, output-frame-boundary read, exactly v1's pattern).

2. **"Anchor force" is a FILAMENT end-pin reaction, not a `TailAnchor`.** The prompt floats
   `TailAnchor` for anchor forces — that is a mismatch. v1's anchor is the **hard end-pin on the
   filament's plus-end segment** (`applyBenchmarkPins`), and the force read is that **segment's
   `forceSum`** projected inward. v2's `TailAnchor` (4b-i) anchors the *motor bed* and is unrelated.
   The contractile assay's anchor force = the pinned-filament-segment reaction, read pre-snap — which
   v2 already produces; it just isn't projected/recorded yet.

**Nothing in the readout set requires a quantity v2 cannot expose.** The only genuinely *new* build
items are (i) the two-anchored anti-parallel-filament + central-minifilament scene (the
"first genuinely contractile test" already flagged as next in CLAUDE.md inc 6 — both ends engaging
opposite-polarity filaments), and (ii) capturing the anchor-segment force pre-snap; everything else
is bookkeeping over quantities v2 already computes.

---

## Constraints honored
Survey only — no code, no runs, no edits. `BoA-v1ref` byte-clean (read-only). File:lines verified by
direct read. Flagged (not designed) the two v2 wiring items; the v2 contractile-assay build is the
planner's to scope from this.
