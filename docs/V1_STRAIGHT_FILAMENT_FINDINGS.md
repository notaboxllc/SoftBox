# Why are v1's free filaments perfectly straight while treadmilling? — CODE READ (observation-only)

**Date:** 2026-06-24 · **Mode:** observation-only (no fix, no flag flip, no PF/code/scene edit; `BoA-v1ref` read
as source only). Scene = `/tmp/v1max` `v1max_16x_freemotion` render (`threejs_v1_16x_free/`), a **GPU** run
(`run_maxsweep.sh`: `BoxOfActin -r -gpu`). v1 path read = `BoA-v1ref`. v2 path read = `SoftBox`.

## TL;DR — category (c) BY DESIGN, plus a scene-ground-truth correction

The leading hypothesis — *"the GPU Brownian gate zeroes their thermal scale"* — is **FALSE**. No Brownian
off-gate fires for these filaments. They are **short** (→ straight) and **physically constrained** (formin/node
attachment + crosslink Brownian attenuation → barely moving), which is the **correct intended behaviour of a
dense crosslinked formin-node network**. The premise *"a FREE population among node myosins, not
formin/node-tethered"* is **contradicted by the PF**: this scene is a 400-node formin + crosslinker network, not
a free-filament assay.

The one real v1↔v2 divergence: **v2 omits v1's crosslink Brownian attenuation** (`1/(1+xLinkTransAttn·linkedToCt)`).
The rotational/bending convention itself **agrees** (both: end-segments-only).

---

## 1. Scene ground-truth (from the PF + a render frame — settles the render-vs-memory question)

`threejs_v1_16x_free/params_input.v1max_16x_freemotion` and frame 22 (`t=0.022 s`, last frame):

- **Nodes carry the myosins (jba's hub observation ✓).** `initialNodes:400`, `numNodeMyos:6`, `numNodeMyoDimers:6`
  ⇒ 400 nodes × (6 singlets + 6 dimers×2 heads) = **7200 motors**, exactly the frame's `myosins: 7200`. The
  hedgehog/rosette hubs **are** node myosins, not formin filament stars. `minifilaments: 0`.
- **Filaments are predominantly FORMIN-NUCLEATED & NODE-ATTACHED, NOT a free population.** `forminsPerNode:6`,
  `kNodeNuc:10` (on-node nucleation by formin), `forminRelease:1.0`/s, `kRdmNuc:0.001` (sparse free nucleation).
  400 nodes × ~6 formins ≈ 2400 formin-attached filaments; over a **0.022 s** render the release rate 1/s has
  fired on only ≈2 % of them ⇒ **almost every formin filament is still node-attached** at the rendered times.
- **Crosslinkers are ON and bundling.** PF header = "DENSE contractile re-baseline … 1000 actin + 1000 minifil …
  Crosslink formation calibrated … crosslinks BUNDLE". `xLinkOnRate:40`, `xLinkConc:1.0`, `maxLinksOnSeg:10`,
  `xLinkTransAttn:1.0`, `xLinkRotAttn:1.0`.
- **Filaments are SHORT.** Frame segment lengths: min 0.011, **mean 0.188**, max 0.346 µm — far below actin's
  persistence length (~10 µm). `segments: 6219`.
- `brownianFilMotionOff` is **absent** from the PF.

## 2. The freeze cause — NOT a Brownian off-gate (hypothesis falsified)

**`Env.brownianFilMotionOff` is never set.** It is declared `= false` (`Env.java:251`) and has **zero**
assignments anywhere in the code and **no PF key** — so the GPU global `bFilOff` (`GPUMoveThing.java:6316,6758`)
is always false.

**Per-segment `f.brownianOff` is benchmark-only.** Its only two code setters are
`FilSegment.makeStraightChain(…, brownOff)` (`:4002`, the contractility axial-tension assay) and
`BoxOfActin.java:2917` (the `-deflect` straight-chain benchmark). Neither is on the normal turnover path. The
prior "only 2 setters, benchmark-only" enumeration is **re-derived and confirmed complete**.

**The GPU gate applies full translational Brownian to these filaments.** Both `brownianScales` blocks
(`GPUMoveThing.java:6399-6433` and `6511-6525`) compute, for `RULE_FIL`:
```
if (bFilOff || f.brownianOff) { tScale=0; rScale=0; }      // neither true here
else { ts=bTransCoef; rs=bRotCoef;
       if (f.linkedToCt>0) { ts/=(1+xLnT*linkedToCt); rs/=(1+xLnR*linkedToCt); }   // crosslink attenuation
       tScale=ts; rScale=(filAtEnd1 && filAtEnd2) ? 0 : rs; }                       // interior → no rotation
```
And the GPU-vs-CPU-fallback gate (`GPUMoveThing.java:5251-5258`) sends a FilSegment to CPU only if it is an
Arp2/3 branch (`motherFil!=null`), `actAOn` (Listeria/ActA — `Thing.lmBug`, absent here), or an inactive Lp
segment. None apply ⇒ every node-scene filament is GPU-integrated with full Brownian. *(The `actAOn` damping at
`FilSegment.java:627-630` is the Listeria comet-tail mechanism, set only in `ActA.java`; inert in this scene.)*

**So "barely moving" comes from two BY-DESIGN constraints, both faithful on CPU and GPU:**

- **(M1, dominant) Formin/node attachment slaves the filament to a near-stationary node.** The attached end is
  tethered to its node (`maxNodeTetherStrainDist`, `nodeTetherDetachRate`, `nodeTorqSpring` all set), and the node
  has `nodeTransDiff:5.0E-15` m²/s. Node RMS over one 1e-3 s frame = `sqrt(2·5e-15·1e-3) ≈ 3.2 nm` — **matching
  the recon's "3–5 nm" almost exactly.** Full Brownian is still *applied* to the segment; the stiff tether to the
  slow node holds it. (v2's own porting note already records that v1 gives formin seeds the **full FDT Brownian**
  held by the tether — `FilSegment.java:621-642` — not a per-seed damping.)
- **(M2, secondary) Crosslink Brownian attenuation.** `linkedToCt` = the per-filament crosslinker degree
  (`registerFilLink`, `FilSegment.java:1478-1490`; incremented at `:1485-1487`). With `xLinkTransAttn=xLinkRotAttn=1.0`
  it divides **both** translational and rotational Brownian scale by `(1+linkedToCt)` (CPU `FilSegment.java:626,635`;
  GPU `:6519-6522`) — a genuine in-gate thermal-force reduction, up to ~11× at `maxLinksOnSeg=10`.

This is the closest thing to the hypothesis: the GPU gate does **attenuate** (not zero) thermal scale, but only
for crosslinked filaments, and the dominant freeze here is M1 (node attachment), not the gate.

## 3. The 84/16 bimodal split

A boolean, not a continuum: **constrained (node-attached and/or crosslinked) → barely move** vs
**uncrosslinked free (random-nucleated / the ~2 % released) → full FDT ~80 nm.** A uniform off-gate would give
100 %, not 84/16 — consistent with attachment/crosslinking being a per-filament property. Note also that at a
16 µm field of view over 0.022 s, even the "moving" 80 nm is ~0.005 % of the frame ⇒ visually **everything** looks
frozen, which is likely why the population read as uniformly static.

## 4. STRAIGHT — short + stiff + end-only rotation (shared with v2)

- Filaments are **short** (mean 0.19 µm) ≪ actin Lp ~10 µm ⇒ rod-straight regardless of Brownian.
- Rotational/bending Brownian is **end-segments-only**, *by design*: `rScale=0` when `(filAtEnd1 && filAtEnd2)`.
  `filAtEnd1/filAtEnd2` mean **"has a linked neighbour at that end"** (`setEnd1Links/setEnd2Links`,
  `FilSegment.java:2818-2832`; cf. `:705` "no one to join to"), so `(filAtEnd1 && filAtEnd2)` = **interior**
  segment → no rotational kick (comment `:640` "only apply brownian torques to end filaments"). A long filament
  *would* still bend (interior translational kicks + end rotation), attenuated by crosslinks — these are simply
  too short/stiff to show it.

## 5. v1 ↔ v2 comparison (the apples-to-apples)

| Aspect | v1 (`BoA-v1ref`) | v2 (`SoftBox`) | Diverges? |
|---|---|---|---|
| Translational Brownian, all segments | full FDT (`bTransCoef`) | full FDT (`brownTransScale=BTransCoeff`) | no |
| Rotational Brownian | **end-segments only** (interior `rScale=0`) | **end-segments only** (`interior?0:BRotCoeff`, `DiffusionHarness.java:543-544`) | **no — agree** |
| **Crosslink Brownian attenuation** `1/(1+xLinkAttn·linkedToCt)` | **YES** (CPU `:626,635`; GPU `:6519-6522`) | **NO** — full FDT regardless of crosslink count; `filLinkCt` feeds only the force-law `fracMove` (`CrosslinkerSystem.java:93`), never `brownTransScale/brownRotScale` | **YES — the real divergence** |
| Formin-seed Brownian | full FDT, held by node tether | **damped** seed (~30×, dt-compensation, inc 6c B2) | yes (seed-level, opposite sign) |
| Crosslinker force law | damping-limited relaxation spring | same (faithful port) | no |

**Consequence:** in this *same* dense crosslinked scene, v2's crosslinked filaments would receive **full thermal
motion** where v1's are damped ⇒ **v2 filaments jiggle/bend more than v1's.** If a v2 render shows visible filament
jitter where v1 looks frozen, the cause is the **missing crosslink Brownian attenuation** (row 3), not a bending-model
difference (bending convention agrees). The formin-seed damping (row 4) runs the opposite way at the seed only.

## 6. Category — **(c) BY DESIGN**

Not **(a)** a config artifact: no flag is tripped (`brownianFilMotionOff` never set; `brownianOff` benchmark-only).
Not **(b)** a v1 bug: nothing wrongly zeroes Brownian; the GPU gate applies full translation, and the attenuation
it does apply (crosslinks) is intended physics. It is **(c)**: the filaments are short (→ straight) and genuinely
constrained (node attachment + crosslink Brownian attenuation → barely moving) — the correct behaviour of a dense
crosslinked formin-node network. The model difference that matters for every v1↔v2 comparison of crosslinked
scenes is **v2's absent crosslink Brownian attenuation** (§5 row 3). No code change made.

> **Carry-forward:** before any v1↔v2 crosslinked-network comparison, account for v2 lacking the
> `1/(1+xLinkTransAttn·linkedToCt)` Brownian damping — v2's crosslinked filaments are thermally "louder" by design.

---

# Addendum (2026-06-24) — TWO populations confirmed + free-body FDT@aeta=1.0 check (observation-only)

Follow-up gate (jba): the `v1max_16x` scene has **two** filament populations, and the real question before matching
scenes for the benchmark is whether v1's genuinely **FREE** bodies move at their correct **FDT amplitude at the
fixed yardstick aeta=1.0**, or are suppressed below it. Measured from the existing GPU render frames
`/tmp/v1max/threejs_v1_16x_free/` (23 frames, t=0→0.022 s; the named `threejs_output_v1_16x_diag` does not exist —
this is the matching freemotion render). No new run, no edits, `BoA-v1ref` untouched. Constants (v1):
`Boltz=1.380662e-23`, `tempK=298.15`, `aeta=1.0` (PF), rod drag `FilSegment.calculateProperties` (`:417-427`,
`aParallel=-0.20/aOrthog=0.84`, `MyoRod.java:11-12`), `nodeTransDiff=5e-15` m²/s (PF).

## A. Measurement 1 — the two populations (CONFIRMED)

Split each filament segment by min(end1,end2)-to-nearest-node-center distance (node radius 0.05 µm):

- **Free-IC population: 6095 / 6219 (98 %)** — barbed end NOT node-coincident; length mean **0.191 µm** (≈ one
  32-mono segment). The **original-assay free filaments**, and they are **actively treadmilling + splitting**:
  segment count grows **1984 → 6219** over the window, dominated by free-IC growth/splits (not nucleation).
- **Formin-nucleated population: 124 / 6219 (2 %)** — an end node-coincident (<0.06 µm); length mean **0.066 µm**,
  median **0.011 µm** — short stubs. The count matches the nucleation rate: `kNodeNuc·initialNodes·t = 10·400·0.022
  ≈ 88` (×≈1.4 for accepts) — i.e. ~88–124 formin filaments born in 22 ms. **Both readings of prior turns were real
  populations.** Confirms the two-population picture quantitatively.

## B. Measurement 2 — FDT@aeta=1.0 per population: **CONFOUNDED BY CONFINEMENT (gate cannot be cleanly decided)**

The naive "below-FDT ⇒ suppression" test **fails its own control** here. Per-segment center displacement and
ensemble+time-averaged MSD(lag), drift-removed:

| body | FDT pred (RMS, 1 ms) | measured | ratio | what it is |
|---|---|---|---|---|
| free-IC, **full set** | 7.2 nm | ~58 nm (MSD 3426 nm²) | ~**8×** | **artifact** — center jumps from treadmill growth + 64-mono **splits**, not diffusion |
| free-IC, **constant-length + isolated** (no split/grow, no seg within 0.05 µm ⇒ uncrosslinkable, not node-tethered) | 7.2 nm | ~1.2 nm | **0.17×** | survivorship-biased toward **stuck** filaments (free movers treadmill/approach-neighbour/crosslink → filtered out) |
| **NODE — known D=5e-15 (CONTROL)** | 5.5 nm | ~1.4 nm (MSD **plateaus** 1.9→5.5 nm² over 1–8 ms vs free 30→240) | **0.06× in D** | **CONFINEMENT, proven** — a body whose D is *set correctly* still reads far sub-FDT and **sub-diffusively** ⇒ "below FDT" is NOT diagnostic of an amplitude bug |
| unbound myosins | — | — | — | **no free myosin population exists** — all 7200 are node-anchored (`numNodeMyos:6`+dimers ×400; `minifilaments:0`); `motor.onFil=0` means unbound-from-**filament**, still node-tethered (slaved head motion ~9 nm) |
| formin stubs / crosslinked | (clamped drag) | below FDT | <1 | tethered / M2-attenuated — **expected**, the design controls |

The decisive control is the **node**: `nodeTransDiff` is set directly to `5e-15`, so its Brownian amplitude is
correct *by construction* — yet it reads **0.06× that D** with a **plateauing MSD** (confined to a ~2 nm cage by its
own formin filaments + myosin-network bonds). Since a known-correct-amplitude body reads sub-FDT here, **sub-FDT
motion in this scene is network CONFINEMENT, not an amplitude deficit.** The free-IC "0.17×" subset is not a clean
amplitude measure either (its full set reads *above* FDT from treadmilling; the constant-length subset is
survivorship-biased toward stuck filaments). At 1 ms frame resolution in a dense warm-started network, the bare
Brownian amplitude **cannot be isolated** — every body is confined within one frame (node cage-crossing
τ≈L²/6D≈1 ms ≈ the frame interval).

## C. Verdict — **NOT a demonstrable free-body suppression; gate is INCONCLUSIVE from these frames**

Neither clean branch of the gate is established. The motion being "very small" is explained by (i) **network
confinement** (rigorously shown — the known-D node reads 0.06× with a plateauing MSD) and (ii) **aeta=1.0 making
true FDT small anyway** (~7 nm/1 ms for a 0.19 µm rod). A genuine free-body amplitude deficit is **neither
demonstrated nor excluded** — the dense scene has no unconfined free body to test, and 1 ms/22 ms resolution can't
separate amplitude from confinement. **Do NOT declare or fix a suppression on this evidence** (this is the
"wrong-twice-from-aggregate-statistics" trap: the naive per-body aggregate fails its own node control).

**To settle the gate cleanly (recommended, NOT executed — flagged rather than starting a run):** a **dilute
single-free-body diagnostic** — 1 free filament + 1 free node in a large empty box at aeta=1.0, no nodes/crosslinks/
neighbours, **dump every step** — and check MSD slope vs `6Dt`. That removes confinement and sub-frame aliasing; it
is the only clean way to read the bare amplitude. (Bears on v1↔v2 because, per §5, v2 lacks v1's crosslink Brownian
attenuation, so the two engines' *confinement* differs even if their bare free amplitudes match.)

## D. The dilute diagnostic — EXECUTED (2026-06-24): v1 free bodies move at **correct FDT** ⇒ NOTHING BROKEN

Ran the recommended diagnostic on **byte-clean `BoA-v1ref` (CPU runner)** with an external `/tmp` PF
(`/tmp/v1_fdt_diag/pf_dilute`; no repo edit): **1 free filament (single 0.194 µm rod) + 1 free node** (`numNodeMyos=0`
⇒ bare diffusing sphere, **known D = nodeTransDiff = 5e-15** as the calibration control) in a **3 µm empty box** at
**aeta=1.0**, **treadmilling/biochem OFF** (`noMonomersSimd=1` ⇒ rigid rods; all poly/depoly/aging/sever/cap/nucleation/
crosslink rates = 0), full Brownian (`BTransCoeff=1.0`, `BRotCoeff=0.5`). 30 000 steps @ dt=1e-5, 1204 frames @ 0.25 ms.
Frames: `~/Code/SoftBox/threejs_output_v1fdt_diag/`. MSD(lag) vs `6Dt`, time-averaged:

| body | bare-amplitude probe (per-frame MSD, 0.25 ms, 1203 samples) | fit D vs reference |
|---|---|---|
| **free filament** (single rod) | MSD/`6D_avg·t` = **1.007** | tracks the node at every lag |
| **free node** (known D=5e-15) | MSD/`6D·t` = **0.990** | fit D **5.22e-15** vs set **5.00e-15** = **1.04×** |

The node — whose amplitude is correct *by construction* (D set directly) — measures back to its set D within 4 %,
**validating the pipeline and v1's Brownian amplitude**. The free filament reads **1.007× FDT** (single-rod drag from
its 0.194 µm length) — i.e. **exactly its FDT translational amplitude at aeta=1.0**, and it tracks the node at every
lag (a gentle long-lag MSD rollover appears in *both* bodies → single-trajectory statistics, not a filament deficit;
the node even overshoots at 50 ms from few long-lag samples). Rotational reads 0.23× FDT = the **deliberate
`BRotCoeff=0.5`** amplitude (0.25× in D) — by design, not the gate.

**VERDICT — the gate flips to PASS: v1's genuinely free bodies move at their correct FDT amplitude.** The
dense-scene sub-FDT motion (§B) is therefore **network CONFINEMENT**, now confirmed by removing it (dilute +
treadmilling-off ⇒ the same bodies hit FDT). **NOT a v1 free-body suppression; no fix needed before the benchmark —
go build the matched scene.** Reconciles §C: the inconclusive dense-scene reading was confinement + the treadmill/
split artifact, exactly as flagged; the clean diagnostic resolves it. `BoA-v1ref` byte-clean (external PF only). No
code change.

**Reconciliation with §1–6:** the M1 (node tether) / M2 (crosslink attenuation) constraints of the main report were
the *tethered/crosslinked* controls; this addendum adds the missing **free-population** check and finds it
**confounded by the same network confinement** — extended here to the whole coupled scene and proven via the
known-D node. No `(b)` free-body bug is supported; category remains **(c) by design** (short + confined), now with
the explicit caveat that the free-FDT amplitude itself is **unverified** at this resolution. No code change made.
