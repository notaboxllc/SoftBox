# BINDING-SEARCH SURVEY — is the flat-motor sparse binding a transcription/path BUG (H1) or a real geometric-duty cost (H2)?

**2026-06-29. READ-ONLY / MEASUREMENT-ONLY. No physics edit, no retune, no default flip; `BoA-v1ref` byte-clean;
no code committed. Triggered by the speed-density finding (flat θ=0 avgBound 2.78 @ density 4000 vs the 4b config-1's
~19 at the same density+kOn — "~7× sparser"; KM ~470× experimental).**

## BOTTOM LINE (one line)
**NEITHER H1 nor H2 as posed.** The binding SEARCH/kOn/capture/dt is **byte-identical** across config-1, perphead/
flat, the single-molecule calibration, CPU `stepOrig`, and GPU `buildPlan` (no transcription bug, no CPU-GPU
divergence) — and config-1 and flat use the **identical binder** (no "two captures vs one"). The "**7× sparser than
config-1**" is a **CONFLATION across the ATP-release-fix boundary**: the 4b config-1 avgBound 19 (`13aa8fd`,
2026-06-27) PRE-DATES the ATP-release fix (`61d2f18`, 2026-06-29); under the CURRENT code config-1 binds the SAME as
flat (~2 at density 4000 — measured below). The real deficit (KM ~470×) is a **DUTY/LIFETIME cost** (reaction-limited
kOn=2e5 + the ATP-release fix shortening the bound lifetime), NOT the binding search or geometry. **One flag for the
planner:** the kOn=2e5 / 1.5%-duty calibration is **STALE** — it was tuned on config-1 + the plain `cycle`, but the
benchmark runs perphead + `cycleAtpDetach`, so the benchmark's realized duty < the calibrated 1.5% (a
calibration↔benchmark divergence in the LIFETIME, not the search). Re-calibrating kOn on the current cycle is the
planner's call; nothing actioned here.

---

## PART A — the binding decision (read-only)

### A.1 — the end-to-end binding decision (current code; CPU `stepOrig` ≡ GPU `buildPlan`)
Per step, for an unbound head (`boundSeg == FREE_BINDABLE`), in order:
1. **Publish search point** — `MotorStore.publishHeadFromBody`: the search point is the **head TIP** =
   `bodyCoord[head] + 0.5·HEAD_LEN·bodyUVec[head]` (head.end2), one point; also publishes head uVec + rod uVec.
   Geometry-agnostic (config-1 / perphead / default all publish the same tip).
2. **Candidate search** — `BindingDetectionSystem.bruteReachable` (the gliding path uses brute O(N²) reach, NOT the
   grid; `-grid` is a measurement flag, unrelated). For each (head, segment) it calls `reachTestDistSq`:
   - α-foot: project the tip onto the segment, `alpha∈[0,1]` else reject (off the segment ends);
   - **capture distance:** `conDist² < myoColTol²`, **`myoColTol = kinParams[7] = 0.006 µm = 6 nm`** (set by
     `setKinParams(0.006, …)`; = v1 `Env.myoColTol`). A **6 nm sphere around the single tip point.**
   - orientation gates: `motDotFil ≥ alignTol` (`kinParams[8] = −0.4`) and `rodDotFil ≥ 0` (rod must point toward
     the filament). Returns `conDist²` if reachable.
3. **Release** (runs before bind in the default order) — `catchSlipRelease`/`…Avg`.
4. **Commit** — `bindNearest` (default v1) OR **`bindCanonicalTwoPoint`** (config-1 AND perphead/flat, both
   `CANONICAL`). `bindCanonicalTwoPoint`:
   - **single-point tip search** over the candidate list (the SAME `reachTestDistSq` gates), nearest reachable
     segment → `tipArc`;
   - **Version-B formability gate:** `rearArc = tipArc − HEAD_LEN`; if `rearArc < 0` OR `tipArc > segLen` ⇒ **bind
     FAILS** (the rear pin site, HEAD_LEN=20 nm behind the tip, must land on the same segment). Rejects a 20 nm band
     at each segment's e1 end (~11 % of a 175 nm segment) — **identical for config-1 and flat.**
   - **reaction-limited kOn gate:** `chord = 2·√(myoColTol² − conDist²)`;
     **`pBind = 1 − exp(−kOn · chord · kinParams[6])`**, `kOn = kinParams[14]` (set to 2e5 by `-kon`),
     **`kinParams[6] = dt` (the caller's dt, single-sourced — NO hardcoded `Constants.deltaT`).** RNG = wang-hash
     `(motor,step,seed)` salt `0x43314B4F` ("C1KO") ⇒ reproducible CPU↔GPU. **`kOn ≤ 0` ⇒ P=1 (saturated
     bind-on-contact).**
   - commit: `boundSeg=bestSeg, bindArc=tipArc, bindArc2=rearArc, canonSnap=1`.
5. **Snap** — `snapCanonicalHead` (all `CANONICAL`, both config-1 and flat): rotate the head to lie ALONG the
   segment, both point-springs at the captured perpendicular distance, clear `canonSnap`. (perphead additionally runs
   `snapPerpRest` BEFORE the snap to freeze the orientation target — writes `perpRest` only, no body move, does not
   affect binding.)

**dt audit:** the bind probability uses `kinParams[6]`; the cycle/release use `nucParams[0]`/`kinParams` — all set
from the caller's `DT` (`setKinParams(…,DT)`, `setNucParams(DT)`). No hardcoded `Constants.deltaT`. ✓

### A.2 — capture cross-section
A **single 6 nm sphere around the head tip** (one point), + the two orientation gates + the formability band. NOT an
interface/extent. Identical for config-1, perphead/flat, default. `density` (motors/µm²) → bed motor count =
`density · bedArea`; accessibility per filament is set by which tips fall within 6 nm of the 2-µm filament line — the
SAME geometry for all motor types.

### A.3 — differential across the arc (binding-decision files)
`git diff 13aa8fd..HEAD -- BindingDetectionSystem.java` over the search/commit (`reachTestDistSq`,
`bindCanonicalTwoPoint`, `myoColTol`, `kOn`, `chord`, `pBind`, `rearArc`) is **EMPTY** — **the binding search/commit
is byte-identical to the 4b-calibration baseline.** The kOn reaction-limited gate has been stable since `c00464e`
(Version-B binder). The ONE binding-files change between the 4b baseline (avgBound 19) and the benchmark (avgBound
~2) is **`61d2f18` adding `NucleotideCycleSystem.cycleAtpDetach`** (the ATP-release fix) — a **LIFETIME** change in
the cycle, NOT a binding-search change.

| commit | date | touched | effect on bind rate | effect on lifetime |
|---|---|---|---|---|
| `c00464e` Version-B binder + reaction-limited kOn | pre-4b | bind search + catch | set the kOn gate | — |
| `13aa8fd` 4b kOn re-tune (kOn=2e5, duty 1.5%) | 06-27 | GlidingHarness/MotorStore | **baseline (avgB 19)** | plain cycle |
| `61d2f18` ATP-state audit → **ATP-release fix** + perphead | 06-29 | **`cycleAtpDetach`** + perphead | **none (search unchanged)** | **shortened ~6-9×** |

### A.4 — "two capture chances vs one" (the 7× structural test) → REFUTED
The two-point (config-1) head does **NOT** get two searches. `reachTestDistSq` + the tip search is **single-point**
(the head tip) for BOTH config-1 and flat. The "two-point" is the **BOND** (tip pin + rear pin), formed from ONE tip
capture + the formability test (the rear is a *consequence*, not a second search). `bindCanonicalTwoPoint` is the
**identical binder** for config-1 and perphead/flat (both `CANONICAL`); the capture radius (6 nm), the `bindArc`
computation, and the formability band are identical. **There is no geometric "2 searches vs 1" 7×.**

### A.5 — calibration-path vs benchmark-path diff (the H1 spine)
| gate | single-molecule calibration (`-single`, CPU) | GPU gliding benchmark (`buildPlan`) | CPU `stepOrig` | identical? |
|---|---|---|---|---|
| search point | `publishHeadFromBody` tip | same | same | ✓ |
| candidate search | `bruteReachable` | `bruteReachable` | `bruteReachable` | ✓ |
| capture radius | `myoColTol=6 nm` | 6 nm | 6 nm | ✓ |
| binder | `bindCanonicalTwoPoint` | `bindCanonicalTwoPoint` | `bindCanonicalTwoPoint` | ✓ |
| kOn / P formula | `1−exp(−kOn·chord·dt)` | same | same | ✓ |
| dt source | `kinParams[6]=DT` | `kinParams[6]=DT` | `kinParams[6]=DT` | ✓ |
| snap | `snapCanonicalHead` | `snapCanonicalHead` | `snapCanonicalHead` | ✓ |
| **release/cycle** | **plain `cycle`** | **`cycleAtpDetach`** | **`cycleAtpDetach`** | **✗ (LIFETIME, intentional)** |

**The binding SEARCH/kOn/capture transfers identically (no H1 search bug; CPU≡GPU identical kernels).** The ONLY
divergence is the **cycle** (calibration `cycle` vs benchmark `cycleAtpDetach`) — a LIFETIME effect, the intentional
ATP-release fix, applied to the benchmark after the kOn calibration ⇒ the realized duty in the benchmark ≠ the
calibrated 1.5 %. **This is a calibration↔benchmark divergence in the lifetime, not a transcription bug in the rate.**

**v1 context (BoA-v1ref, read-only, one paragraph):** v1 binds a FREE single myosin head — `GPUMotorBinding`
nearest-reachable segment within `myoColTol` from the head point, no two-point/formability collapse (that is a v2
canonical-motor divergence), force-only release. The v2 single-tip search + 6 nm reach is the faithful port of v1's
single-head reach; the two-point formability + the ATP-release detach are the documented v2 divergences. v1 is the
intended-original reference for the SEARCH only, not a rate oracle.

---

## PART B — diagnostics

### B.2 / the conflation test — config-1 vs flat, ATP on/off, CURRENT code (GPU, density 4000, 20k, seed 0)
| config | ATP-release | avgBound (steady) | reading |
|---|---|---|---|
| **config-1** | **ON (current)** | **1.73** | the current regime — NOT the 4b's 19 |
| **config-1** | **OFF** (`-noatprelease`) | **15.19** | reproduces the 4b regime (~19) |
| **flat θ=0** | **ON (the benchmark)** | **1.91** | ≈ config-1 ON (1.73) |
| **flat θ=0** | **OFF** | **20.35** | ≈ config-1 OFF (15.19); if anything slightly MORE |

**Decisive:** config-1 ≈ flat in BOTH regimes (ON 1.73≈1.91; OFF 15≈20). The geometry makes **no** binding-density
difference (flat is marginally HIGHER, not 7× lower). The **ATP-release fix alone flips avgBound ~9-11×**
(config-1 1.73→15.19 = 8.8×; flat 1.91→20.35 = 10.7×) — it is the ENTIRE driver of the "7× sparser than config-1."
The 4b config-1 "19" was the ATP-OFF regime; the speed-density flat "2.78" is the ATP-ON regime. **Conflation
confirmed.** Raw: `RUN_LOGS/2026-06-29_bind_conflation.txt`.

### B.1 capture funnel — _(structurally identical binder ⇒ funnel identical between config-1 and flat; the divergence is downstream in lifetime, confirmed by B.2)_

### B.3 geometric accessibility — the search is NOT the bottleneck
The SAME geometric search gave avgBound **19** (config-1, density 4000, pre-ATP) — a healthy engagement — proving
the 6 nm capture cross-section + accessibility at this density is ample. The deficit appears only with the
ATP-release lifetime cut. So the ~470× KM is **NOT** an accessibility/units/cross-section error; it is duty/lifetime.

### B.4 capture radius / search width — `myoColTol = 6 nm`, **identical** between the calibration path and the
benchmark path (same `kinParams[7]`, same `setKinParams(0.006,…)` call). The search chord that enters P is
`2·√(6nm² − d⊥²)` (benchmark) vs the calibration's printed max `2·6nm` (d⊥=0 theoretical) — same formula, the
benchmark just uses the actual d⊥ (physically correct).

---

## H1 / H2 VERDICT
- **H1 (transcription / path divergence in the binding search): REFUTED.** Search, capture radius, kOn probability,
  dt source, and commit are byte-identical across config-1 / perphead / single-molecule / CPU / GPU; the search is
  byte-identical to the 4b baseline; CPU≡GPU use the same kernels. No bug in the bind rate.
- **H2 ("two captures vs one" geometric 7×): REFUTED.** config-1 and flat use the identical single-tip binder; the
  "7×" is a pre/post-ATP-fix conflation (config-1's 19 was pre-fix; under current code config-1 ≈ flat ≈ 2).
- **What IS true (the real deficit):** a **DUTY/LIFETIME** cost — the reaction-limited kOn=2e5 is intrinsically
  sparse, and the ATP-release fix shortened the bound lifetime ~6-9×, together pushing KM ~470× experimental. The
  lever is duty (kOn + lifetime), NOT the binding geometry, NOT a transcription bug.

## FLAG for the planner (not actioned — needs sign-off; re-baselines validation)
The **kOn=2e5 / 1.5%-duty calibration is STALE**: tuned on config-1 + plain `cycle` (`13aa8fd`), but the benchmark
runs perphead + `cycleAtpDetach` (`61d2f18`) ⇒ the benchmark's realized duty differs from the calibrated 1.5 %. This
is the "calibrated rate not transferring" in the LIFETIME (the single-molecule harness still reports 1.5 % because it
uses the plain `cycle`). **Re-calibrating kOn on the current ATP-release cycle (and the perphead bond) is the path to
fix the duty** — a planner decision (it re-baselines every prior duty/avgBound/velocity number). Nothing changed here.
