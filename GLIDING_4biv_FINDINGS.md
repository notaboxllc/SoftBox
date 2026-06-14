# Increment 4b-iv (gliding assay) — findings for the planner

**Status: BAIL-OUT / FINDING. Nothing committed.** The gliding assembly works end-to-end (glides −x,
stable, avgBound matches v1), but the gliding **velocity** lands below v1's fixture. Per the bail-out
discipline ("a physics miss with a faithful config is a finding — report, do not tune"), this document
reports the localized cause and the diagnosis trail rather than tuning to hit the number. The working
tree holds the uncommitted gliding harness; the planner should decide whether to commit it as a
checkpoint, continue the diagnosis, or re-scope.

Everything in 4a–4b-iii remains committed and validated; this is purely about the 4b-iv integration.

---

## 1. What was built (uncommitted, in the working tree)

- `softbox/GlidingHarness.java` — the gliding harness (CPU runner): unpinned ~11-segment **chain**
  filament over a motor bed, dynamic per-step binding + the full cross-bridge/gather/stroke/cycle/
  catch-slip, measurement (centroid velocity + avgBound), a cheap probe, a `-diag` instrument, a
  `-3js` viewer. **No GPU TaskGraph yet** (CPU-only).
- `softbox/BindingDetectionSystem.bindNearest` — a bind-only kernel (geometric bind; the F-dependent
  catch-slip release is `NucleotideCycleSystem.catchSlipRelease`). Additive; existing paths verified
  unaffected (4b-iii stroke checkpoint + 4a binding still PASS).
- `run_gliding.sh`.

## 2. Config captured from v1 (faithful — the legitimate "matching")

From `ParameterFiles/glidingAssay500_val` + `FilSegment.makeGlidingAssayFilament` + `MyosinFixed`:
- dt = 1e-5; density 500 motors/µm²; `fixedMyosinZValue = −0.05`; **filament built at z = 0** (confirmed
  in v1 source), along +x (plus-end +x); 2 µm, 64-monomer segments ⇒ ~11 chain segments connected by
  the inc-2 chain forces; chain params `fracMove=0.5 / fracR=0.1 / fracMoveTorq=0.2`.
- Surface = **small** (the `MyosinFixed` rod-tail anchor + the filament held by its bound motors; no
  separate z-clamp; confirmed no filament-surface subsystem in v1).
- Binding is **purely geometric** (4a predicate), not nucleotide-state-gated.
- Glide direction **−x** (filament plus-end at +x; minus-end leads).
- v2 uses a density-faithful **patch** of bed around the filament's path (not the full 14×2 box) to keep
  the motor count tractable — avgBound/velocity are local to the filament, so this is faithful.

## 3. The cheap probe caught two things early (as intended)

1. **avgBound = 0** initially → a HARNESS BUG: I had omitted the **motor Brownian** in the gliding step
   (present for the filament, missing for the motors), so the articulated heads stood rigidly upright
   (head tip z ≈ 0.058) and never thermally reached the filament at z=0. **Fixed** (added
   `BrownianForceSystem.brownianForce` for the motor sub-bodies). Not a physics issue.
2. After the fix, it **glides −x, stably (no NaN)** over the full 10k-step (0.1 s) run.

## 4. The velocity finding (the core result)

| run | box (µm) | motors | avgBound | velocity (µm/s) |
|---|---|---|---|---|
| **v1 full assay (the fixture)** | 14×2 | 14000 | 7.64 / 7.21 | **8.33 / 8.23** (CPU/GPU) |
| **v1, matched box (ran this session, GPU)** | **4×1** | ~2000 | — | **6.66** |
| v2 (mine), narrow strip | ~3.4×0.2 | 343 | 8.3 | −3.4 (0.41×) |
| v2 (mine), wide bed | ~3.4×1.1 | 2107 | **6.85** | **−4.27** (0.51× vs fixture) |

**Two distinct contributors, now separated:**

**(a) Finite-size / bed-width — a CONFIG effect that v1 ALSO exhibits.** Running v1 at the *same* small
box (4×1) gives **6.66 µm/s, not 8.33** — v1 itself drops ~20% just from shrinking the box. The cause
(planner-noted from the viewer): the filament's ends rotate/wander toward the bed edges and lose motor
support; a narrow bed amplifies it. Widening v2's bed (±0.1 → ±0.55 µm in y) brought avgBound from 8.3
to **6.85 (now matching v1's 7.6)** and velocity 0.41× → 0.51×. **This is config-matching, not tuning.**

**(b) The genuine remaining gap is ~1.5×, not 2×.** Apples-to-apples (v2 at ~3.4×1.1 vs v1 at 4×1):
**4.27 vs 6.66 µm/s ⇒ 0.64×.** So against a same-box v1 the gap is ~1.5×, with everything else matching.

**(c) Big-box converged run (box ≈6×2 µm, full y-width, 30k steps / 0.3 s — for better statistics).**
v2: **velocity −4.0 µm/s, avgBound 7.47** (now matching v1's 7.6 essentially exactly); filament settles
to z ≈ −0.056 (onto the bed plane); stable, minimal rotation. Notably the wider box raised avgBound to
v1's value but left velocity ~unchanged (slightly LOWER) — **higher avgBound came with slightly lower
velocity**, the tug-of-war signature: v2's extra bound motors add drag (~50/50 assist/resist), whereas
v1 sustains high avgBound AND high velocity (its bound population is net-assisting / coordinated). That
coordination-under-motion is the crux of the remaining gap. (v1 big-box result pending at write time;
the harness default is now this big box.)

## 5. Mechanism diagnosis (the `-diag` instrument, wide bed, post-warmup)

```
velocity = −4.27 µm/s, avgBound = 6.85
bound-state distribution: NONE 0.5%  ATP 54.0%  ADPPi 3.0%  ADP 42.5%
forceDotFil (bound): mean +0.27 pN, 52.9% positive (assist) / 47% negative (resist)
mean bound-time = 126 steps (1.26 ms); catch-slip release rate 792/s
power strokes (ADPPi→ADP while bound) = 268/s per bound motor
filament advance per power stroke = 2.33 nm   (unloaded stroke ≈ 7 nm, validated 4b-iii)
```

Decomposition `velocity = avgBound × strokeRate × advancePerStroke` holds (6.85 × 268/s × 2.33 nm ≈
4.27 µm/s). So the gap is **not** binding/density/geometry (avgBound, direction, stability, dwell-times
all match v1). It is the **velocity coupling under motion**:
- **advance per power stroke = 2.33 nm vs the 7 nm unloaded stroke (0.33×)** — the bottleneck.
- The bound population is a near-balanced **tug-of-war (~53% assisting / 47% resisting)** ⇒ weak NET
  force ⇒ small advance. For v1 to advance ~2× more per stroke at the same avgBound, its bound
  population must be more asymmetrically assisting (resisting motors must clear faster / fewer bind
  mid-resist).
- Motors release fast (126 steps) via slip as the filament glides ~3 nm past them; ~⅔ of binds release
  during ATP **before ever reaching the ADPPi→ADP power stroke**.

**The levers that would raise advance-per-stroke are all FROZEN, validated constants** (chain
`fracMoveTorq`, `myoSpring=1e-9`, the catch-slip params, the 7 nm stroke). Changing any to chase the
number is the forbidden tuning. So the remaining ~1.5× is a faithful-config physics finding about
collective stroke-transmission / duty-under-motion.

## 6. Open questions for the planner

1. **The ~1.5× coupling gap** (v2 4.27 vs same-box v1 6.66): why does v1 advance ~2× more per stroke
   at the same avgBound? Candidate faithfulness checks (NOT tuning): (a) is the inc-2 chain force —
   calibrated for *deflection* at `fracMoveTorq=0.265` — faithful to v1 at the *gliding* `0.2` (a
   stiffer chain would transmit strokes better and resist local bending)? (b) does v1's catch-slip
   release the *resisting* (negative-forceDotFil) population as promptly as ours? (c) the filament z
   settles to ≈ −0.017 in v2 — does v1's stay at 0?
2. **Scale to the fixture.** To compare against the 8.33 fixture (not the 6.66 small-box value) needs
   the FULL 14×2 box (~14000 motors). v2 is CPU-only today; this needs the **GlidingHarness GPU
   TaskGraph** (not yet built) — which is also required for the prompt's GPU-throughput gate.
3. **Biochem cadence sanity.** v1's run stats show `biochemFireCt=10` over 10101 steps — that is the
   *FilSegment* poly/depoly gate (biochemCheckInt=1000). The *MyoMotor* nucleotide cycle must fire
   every step (else v1 couldn't glide, and our 4b-iii dwell-time gate matched at per-step cadence), but
   a direct confirm in v1 source is worth doing before trusting the cycle timing under motion.
4. **Commit policy.** The harness works (mechanism correct, avgBound/direction/stability match); the
   open item is the velocity magnitude. Bail-out says "commit nothing"; the planner may prefer to bank
   the working harness + this finding as an explicit partial checkpoint.

## 7. What is and isn't validated

| aspect | status |
|---|---|
| Assembly integrates (binding+cycle+cross-bridge+gather+stroke+chain+catch-slip) | ✓ works, stable |
| Glide direction −x | ✓ |
| avgBound vs v1 | ✓ 6.85 vs 7.6 (same-box) |
| Stability over the full run (no NaN/blow-up) | ✓ |
| dwell times / stroke / catch-slip / gather (4b-iii/ii) | ✓ (committed) |
| **Gliding velocity vs fixture** | ✗ — 0.64× vs same-box v1 (6.66), 0.51× vs full-box fixture (8.33) |
| CPU≡GPU on gliding | not tested (no GPU plan yet) |
| GPU throughput | not measured (no GPU plan yet) |

## 8. Reproduce

```
./run_gliding.sh 10000          # CPU probe: velocity + avgBound + y-spread
./run_gliding.sh -diag 10000    # the mechanism instrument (state dist, force balance, advance/stroke)
./run_gliding.sh -3js threejs_gliding   # viewer (v2)
# v1 reference (read-only worktree; libs + .class are gitignored build artifacts):
#   cd ~/Code/BoA-v1ref; build with `-encoding ISO-8859-1`; run BoxOfActin -r -gpu
#   -pf <box-4x1 param> -3js ~/Code/SoftBox/threejs_v1_gliding   → glidingVelocity=6.66
```
Viewer: `threejs_gliding` (v2, 4.27 µm/s) vs `threejs_v1_gliding` (v1 same box, 6.66 µm/s), same
nucleotide-state coloring.
