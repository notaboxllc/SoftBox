# Experiment 4E — passive myosin-tail geometry as a recruitment mechanism

**Date:** 2026-07-14 · **Branch:** `dt-convergence-study` · **Commit at start:** `e17b5a4` · **Runner:** CPU
sequential only (`-gpu` unused). **Hardware:** aorus, one core. **Wall-clock:** full experiment ≈ 10 min. **dt:**
5e-6 (mechanics + mat). **Trap:** 0.05 pN/nm.

**Default-off, non-canonical prototype** (`[NON-CANONICAL TWO-BODY PROTOTYPE]`), `-exp4e` /
`-twobody-tail-recruitment` in `softbox/TwoBodyConverterMotor.java`. **The validated two-body motor domain,
converter, neck–lever, F8 spring, stereospecific binding gate, canonical Lymn–Taylor chemistry + kinetic
constants, force ordering, RNG behaviour, and `BoA-v1ref` are UNTOUCHED** (4E is new methods only; the tail fields
on `Cmot`/`Glide2D` are read ONLY by `stepTail`/`stepGlideTail`, never by the validated `stepC`/`stepGlide2D`).
Verified: `git status` shows only `TwoBodyConverterMotor.java` (+ one dispatch line in `LaserTrapHarness.java`)
modified; `BoA-v1ref` byte-clean; the five canonical files (`CrossBridgeSystem`, `NucleotideCycleSystem`,
`MotorStore`, `GlidingHarness`, `CanonicalMotorHarness`) untouched; 3C reproduces its exact `k_ext` limits and 4A
runs 0-forbidden. **This is a GEOMETRY + MECHANICS study, not a kinetic-tuning study** (no kinetic constant, gate
tolerance, converter geometry, or chemistry was changed — the tail is the ONLY added element).

---

## 0. The tail model and controlling outcome

The validated two-body motor's proximal end is a **fixed-position calibration anchor** `A`: the neck–lever pivots
about `A` (φ), and `A` is rigidly pinned in space (3C/3D/3E carried "the fixed-position anchor defers real
substrate compliance" as an explicit caveat). **Experiment 4E replaces that fixed anchor with a passive compliant
TAIL** between a fixed surface attachment `S` and a now-**movable** pivot `P`:

```
surface attachment S ── passive tail (rod, rest length lTail) ── movable pivot P ── neck-lever L_B=8nm ── converter ── head/F8 ── actin
                        stretch k_tail, bend κ_tail (rest dir tHat = ê_up)
```

`P` is a full 3-DOF movable point (b̂/econv/ê_up); the head–converter–lever assembly hangs off `P` and stays
planar in its local frame. The tail applies a stretch spring (toward `|P−S| = lTail`) and a bending spring
(toward the rest direction `tHat = ê_up`, i.e. up toward actin). The 5-DOF `(P_b, P_e, P_up, φ, ψ)` overdamped
state is advanced by a linearly-implicit solve `(A_drag + K)Δq = F`, `K = k_F8·JᵀJ + converter + bind + tail`
(the exact generalisation of the validated 2×2 φ,ψ solve; the F8 Gauss–Newton stiffness now couples the pivot).
**Rest configuration `P = S + lTail·tHat = A` ⇒ the pre-stroke geometry is unchanged; the tail reduces to the
validated fixed anchor as `lTail→0` or `(k_tail, κ_tail)→∞`.** `barbedDir` does not enter the stroke target.

**CONTROLLING OUTCOME: TRADE-OFF.** A passive tail **does** enlarge the capture volume and **strongly increases
recruitment** (binding rate, mean bound, continuity) — up to **5.7× more motors bound** at a 10 nm tail. **But the
recruitment gain comes from the very compliance/swing that also absorbs the power stroke:** the recruitment-optimal
(free-swinging) tail collapses the delivered stroke **6.9 → 1.1 nm** and the whole-crossbridge stiffness **0.645 →
0.010 pN/nm** (98 % series compliance). **The two requirements share one physical cause and pull in opposite
directions — no swept passive-tail geometry both recruits well AND preserves the validated single-molecule
mechanics.** A recruited motor here is *bound but mechanically inert*, so the recruitment increase does **not**
translate into more delivered force. This is a recruitment-map change that **also degrades intrinsic single-motor
mechanics** — failing the CURRENT_STATE §5 clean-recruitment-map criterion.

---

## 1. Q1 — the present anchor geometry (audit)

**The current effective surface-anchor→lever-pivot distance is exactly 0.00 nm.** The anchor `A` **is** the lever
pivot: `C = A + L_B·ûB(φ)`, and φ pivots about the fixed `A`. There is no tail element — the neck–lever's proximal
end is rigidly pinned in position (only its orientation φ is free). The 3A "Body B lever–tail" was collapsed to a
single fixed pivot point in 3C+.

Fixed-anchor baseline (reproduced exactly): **k_ext = 0.645 pN/nm**, delivered stroke **6.91 nm**, transverse
**0.09 nm**, pre-stroke F8 preload **0.000 pN**, external pre-stroke trap force **0.000 pN** — the validated
3D/3E/3F/4A numbers.

**Gate 1 (regression):** `stepTail` with `tailOn=false` delegates to `stepC` and reproduces it **bit-for-bit**
(max|Δ| = 0.00e+00 over a full settle) ⇒ the tail path is a strict superset; the validated fixed-anchor motor is
recovered exactly.

---

## 2. Q2 — capture volume vs tail length

Geometric reachable F8-point footprint in the mat plane as the tail swings within its thermal cone
(β₀ = √(2kT/κ_tail), capped at 60°) about `tHat`, × the lever/head gate windows (φ ± 25°, ψ ± 25°)
(`capture_volume.csv`):

| lTail (nm) | free tail κ=4: swing cone | footprint area (nm²) | lateral reach (nm) | stiff-bend κ=400 area (nm²) |
|---:|---:|---:|---:|---:|
| 0 (fixed) | ±0° | **0** (a line, zero width) | 0 | 0 |
| 5 | ±60° | 148 | 8.7 | 14 |
| 10 | ±60° | 445 | 17.3 | 32 |
| 20 | ±60° | 1 491 | 34.6 | 81 |
| 40 | ±60° | 5 381 | 69.3 | 227 |
| 80 | ±60° | 20 363 | 138.6 | 715 |

**Yes — a longer tail enlarges the capture volume, strongly.** The fixed-anchor motor has **zero transverse
capture width** (its reachable set is a ~8 nm line along the filament axis); the tail is what gives a genuine 2D
footprint. The lateral (transverse) reach grows **~linearly** with lTail (≈ lTail·sinβ₀) and the area
**~quadratically**. **The growth REQUIRES the free swing:** a stiff-bend tail (κ=400) has a swing cone of only
±8°, so its footprint grows ~20× more slowly (lateral reach 22.9 nm at 80 nm vs 138.6 nm free). The capture-volume
enlargement is inseparable from tail floppiness. **Gate 4 PASS.**

---

## 3. Q3 — recruitment on a moving-filament mat

A rigid filament glides over a bed of tail motors (2.5 × 0.3 µm mat, 200 µm⁻², N=150; free-swing tail k_tail=2
pN/nm, κ_tail=4; 0.06 s × 3 episodes; the pivot diffuses under the tail spring + Brownian while unbound, so the
tail SEARCHES a larger area) (`recruitment_mat.csv`):

| condition | avgBound | (× fixed) | continuity | binds/motor/s | maxTilt | avgTilt |
|---|---:|---:|---:|---:|---:|---:|
| **fixed anchor** | 0.230 | 1.0× | 0.203 | 1.56 | — | — |
| tail 10 nm | **1.320** | **5.74×** | 0.685 | 9.30 | 138° | 42° |
| tail 20 nm | 0.965 | 4.20× | 0.605 | 6.78 | 93° | 31° |
| tail 40 nm | 0.641 | 2.79× | 0.448 | 4.56 | 59° | 22° |
| tail 80 nm | 0.454 | 1.98× | 0.285 | 3.67 | 32° | 15° |

**Yes — the tail increases binding rate, mean number bound, and attachment continuity** (each bind produces one
stroke, so binds/s = strokes/s — the tail also raises the productive-stroke flux). The effect **peaks at a short
tail (~10 nm, 5.7×)** and declines for longer tails: a very long tail wanders its pivot too far from the actin
plane and spends more time off-target, so the *net* engagement falls even though the geometric capture volume keeps
growing. **Handoff/continuity** rises from 0.20 (fixed) to 0.69 (10 nm tail) — the mobile pivot keeps a motor
reachable to a passing filament section for longer. **Gate 5 PASS.**

**Critical caveat (see §5):** these "bound" motors are mechanically compliant (k_ext ≈ 0.01–0.04 pN/nm, stroke
≈ 1–3 nm), so **more-bound does not mean more-force** — the recruitment count rises while the per-motor mechanical
output collapses.

---

## 4. Q4 — extra series compliance from the tail

The tail adds a series spring between the F8 cross-bridge and the substrate. Whole-crossbridge stiffness k_ext
(pN/nm) vs tail geometry (k_tail=2; `single_molecule_tail.csv`):

| lTail (nm) | k_ext, stiff-bend κ=400 | k_ext, free κ=4 |
|---:|---:|---:|
| 0 (fixed) | 0.645 | 0.645 |
| 5 | 0.619 | 0.129 |
| 10 | 0.555 | 0.038 |
| 20 | 0.392 | 0.010 |
| 40 | 0.180 | 0.003 |
| 80 | 0.057 | 0.0006 |

**The added series compliance is dominated by the BENDING stiffness κ_tail, not the stretch stiffness k_tail.**
The power stroke is **axial** (along b̂), while the tail points **up** (ê_up) — so the axial stroke load is resisted
by the tail's resistance to **swinging** (bending), and the stretch stiffness k_tail is nearly irrelevant (varying
k_tail 10→0.1 pN/nm barely moves k_ext at fixed κ_tail — e.g. at 20 nm / κ=400, k_ext = 0.392 for every k_tail).
The series compliance **grows with lTail** (longer lever-arm for the transverse give). **Even a stiff-bend 20 nm
tail drops k_ext 39 %** (0.645 → 0.392); a free 20 nm tail drops it **98 %** (0.645 → 0.010). **Gates 2/3** (a
stiff-bend tail is the least-bad — it keeps k_ext in the skeletal band and the stroke within 1.5 nm) PASS, but the
finding is that **any** tail adds significant compliance.

---

## 5. Q5 — does the tail absorb the stroke?

**Yes — progressively, and the free tail absorbs nearly all of it.** Delivered stroke (filament COM · p̂) and the
pivot's axial recoil (k_tail=2; `single_molecule_tail.csv`):

| lTail (nm) | stroke, stiff-bend κ=400 | stroke, free κ=4 | pivot axial give, free κ=4 |
|---:|---:|---:|---:|
| 0 (fixed) | 6.91 | 6.91 | 0.00 |
| 5 | 6.87 | 4.75 | −2.50 |
| 10 | 6.76 | 2.75 | −4.81 |
| 20 | 6.36 | **1.07** | **−6.74** |
| 40 | 5.14 | 0.32 | −7.63 |
| 80 | 2.91 | 0.08 | −7.90 |

Mechanism: when the head strokes, the Newton reaction pulls the pivot **barbed-ward**. A stiff pivot holds and the
actin advances (delivered stroke ≈ 6.9 nm); a compliant pivot **recoils** instead, so the actin barely moves. At
the recruitment operating point (20 nm, free) the pivot recoils **−6.74 nm** while the actin advances only **+1.07
nm** — the tail absorbs 98 % of the stroke into its own give (`series compliance = 98 %`). The converter still
*completes* (completion → 0.99), but the delivered external displacement collapses — exactly the compliant-give
story of Exp 2A/3C, now dominated by the tail.

---

## 6. Q6 — strained captures, tilt, buckling, tangling, opposing states?

No numerical blow-up, no buckling, no tangling (the pivot is a single point held by a convex potential; the F8
gate still enforces stereospecificity, so captures are not misoriented-high-strain). Tilt is moderate at the
recruitment optimum (10 nm: avgTilt 42°, occasional maxTilt 138° = a brief near-inversion of the short tail; 20
nm: avgTilt 31°, maxTilt 93°) — flagged but not pathological. **The real "pathology" is the opposite of strain: the
recruited captures are mechanically WEAK** — low-force, high-compliance attachments (k_ext ≈ 0.01, stroke ≈ 1 nm)
rather than over-strained ones. There is no opposing-force runaway (no motor is driven backward against the gate).
**Gate 6 PASS** (finite, no inversion beyond the flagged transient, avgTilt < 90° at the optimum). A very long
tail would eventually let the pivot cross the actin plane (an inversion pathology), which is why the sweep is
capped at 80 nm.

---

## 7. Q7 — is there a tail geometry that improves recruitment while preserving the mechanics?

**No — not with a single passive linear tail, over the swept range.** The recruitment gain (Q2/Q3) comes from the
**free swing** that enlarges the capture volume; the stroke loss (Q4/Q5) comes from the **same compliance**
absorbing the axial load. They are one physical property viewed two ways:

- a **stiff-bend** tail preserves the stroke (κ=400, 20 nm: stroke 6.36 nm, k_ext 0.392) **but its swing cone
  collapses to ±8°**, so it barely enlarges the capture volume (§2) → little recruitment gain;
- a **free** tail recruits 5.7× **but collapses k_ext to 0.010 pN/nm and the stroke to 1.1 nm** (§4/§5).

**Recruitment operating point (20 nm, k_tail=2, free bend): delivered stroke 1.07 nm, k_ext 0.010 pN/nm, 98 %
series compliance ⇒ single-molecule mechanics COLLAPSED. Gate 7 CHECK.** Because a linear passive spring's
stiffness is the *same* for the search (unbound, transverse) and the load (bound, axial) modes, no passive
`(lTail, k_tail, κ_tail)` decouples them. **Connection to CURRENT_STATE §5:** the tail *is* a leftward
recruitment-map shift (it moves the effective density knee), but it **violates the §5 requirement that a clean
recruitment-map change preserve intrinsic single-motor force–velocity** — so it is not the clean accessibility
knob §5 seeks.

---

## 8. What would work (recommendation, not built)

The requirement is a linker that is **compliant/mobile for the unbound search but stiff for the bound load** — i.e.
a **state-dependent (catch-stiffening) tail** whose bending stiffness rises on binding, which a passive linear
element cannot be. Equivalent framings: (i) an **active/tensioned** tail that stiffens under the stroke; (ii)
decoupling search-reach from load-transmission (the neck–lever is explicitly ruled out by the task, and lengthening
it is the same trap). Absent such a mechanism, the fixed anchor + raising nominal motor density remains the honest
recruitment lever — with the corrected reading that added binding does not add force unless the added attachments
are load-bearing. **No canonical change is made; the fixed-anchor motor stays the reference.**

---

## 9. Gate summary

| # | gate | result |
|---|---|---|
| 1 | tail-OFF ≡ fixed anchor (bit-identical regression) | **PASS** (max|Δ|=0) |
| 2 | a stiff-bend tail preserves the stroke (within 1.5 nm) | PASS |
| 3 | a stiff-bend tail keeps k_ext in the skeletal band | PASS (0.39, borderline) |
| 4 | capture volume grows with lTail | **PASS** (0 → 20 363 nm²) |
| 5 | recruitment (avgBound) increases with tail | **PASS** (5.74×) |
| 6 | no blow-up / inverted-tail pathology | PASS (flagged transient tilt) |
| 7 | the recruitment-OPTIMAL tail ALSO preserves the mechanics | **CHECK** (stroke 1.1 nm, k_ext 0.010) |
| 8 | canonical / BoA-v1ref untouched | **PASS** |

**Controlling outcome: TRADE-OFF.** Passive tail geometry is a real recruitment enhancer whose gain is
inseparable from the compliance that absorbs the stroke; it is not a free win and does not, by itself, produce a
motor that both recruits well and delivers force.

---

## 10. Deliverables and reproduction

```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp4e -out RUN_LOGS/twobody_tail_recruitment/csv   # full experiment (~10 min, CPU)
./scripts/run_lasertrap.sh -exp4e -fast                                         # quick smoke
python3 scripts/twobody4e_analyze.py RUN_LOGS/twobody_tail_recruitment/csv \
        RUN_LOGS/twobody_tail_recruitment/exp4e_summary.png
# regressions (unchanged): ./scripts/run_lasertrap.sh -exp3c ; -exp4a -fast ; -exp4d -smoke
```

**Artifacts** (`RUN_LOGS/twobody_tail_recruitment/`): `exp4e_full.log`, `exp4e_summary.png`,
`csv/{single_molecule_tail, capture_volume, recruitment_mat}.csv`. Source:
`softbox/TwoBodyConverterMotor.java` (`run4e` + `buildTail`/`stepTail`/`tailForce`/`solveLin`/`tailStroke`/
`tailKext` + `phase4e{Audit,SingleMolecule,CaptureVolume,Recruitment}` + `captureFootprint` + the `TailMat` mat
via `buildTailMat`/`stepGlideTail`/`tailSolveM`/`measureTailMat` + the tail fields on `Cmot`/`Glide2D`), one
dispatch line in `softbox/LaserTrapHarness.java`.
