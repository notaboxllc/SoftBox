# MOTOR BENCHMARK TARGETS — the experimental validation set, and what the three-rigid-body model can legitimately claim

Purpose: the standing reference the motor benchmark runs against. Two things at once — (1) the verified
experimental targets (current literature, not the 7-yr-old poster), and (2) a hard-nosed classification of which
targets the model **tunes to**, which it should **produce emergently**, and which are **calibrated lumped** or
**out of scope** — because the methods-paper thesis ("emergent, not prescribed") lives entirely in that
distinction. Sources are named inline; exact curves to be extracted from the primary papers before runs.

---

## 0. Two target sets — do not conflate them
- **SET A — generic myosin-II single-molecule + small-ensemble mechanochemistry** (skeletal/smooth muscle, the
  classic optical-trap corpus). This is the **methods-paper** target: does the articulated motor reproduce
  *canonical* myosin-II behavior? Cleanest thesis test (well-measured, isoform-controlled).
- **SET B — fission-yeast Myo2 / cytokinetic ring** (the flagship). Different motor: ~20× slower, low duty,
  **node-clustered (does NOT form minifilaments)**, the SCPR frame. Re-parameterize to this for the emergence
  work; do NOT use SET A's fast skeletal numbers for the ring.

---

## 1. The observables + verified targets (with sources)

| # | observable | SET A target (myosin-II) | SET B target (Myo2) | source |
|---|---|---|---|---|
| 1 | working stroke / step | **~5–11 nm, method-dependent** (trap ~5–10; crystal/x-ray 10–12 unloaded; trap may under-read ~2×) | (lever-length-set; Myo2 two-headed, 65 nm stiff rod) | Finer'94, Veigel'98 (5.5), Kaya-Higuchi'10 (~8, nonlinear), Sleep'05, PNAS'06 |
| 2 | cross-bridge stiffness | **~1 pN/nm in TENSION; dramatically softer in COMPRESSION (S2 buckling)** (range 0.7–5) | similar order | Veigel'98 (0.7), **Kaya-Higuchi'10 (nonlinear/asymmetric)**, Howard |
| 3 | force-dependent detachment | **Bell form k=k₀·e^(−F·d/kT); d≈2.7 nm; 1 pN halves detachment (24→12/s)** | (force-sensitive; node anchoring load-dependent) | **Veigel'03**, perspective'16, eLife'21 |
| 4 | duty ratio | **~0.01–0.02 unloaded; rises to ~0.06–0.07 under load**; dwell ~10 ms | low | Howard, Finer'94, poster |
| 5 | peak force / motor | **~5 pN** | ~few pN | Finer'94 |
| 6 | force–velocity (ensemble) | **concave / inverted-hyperbolic**; holds single→ensemble (independent force generators) | concave | Debold'05, AJP'19, eLife'21, JGP'23 |
| 7 | speed vs motor density | rises, **saturates, then can reverse** (actin-detachment-limited) | — | Howard 13.3b, PMC'17/'12 |
| 8 | unloaded velocity V₀ | fast (skeletal µm-scale) | **~0.4 µm/s** (formin ~20 nm/s ≈ 5% of V₀) | **Lord & Pollard'04** |
| 9 | structure (SET B) | — | **two heads, 65 nm stiff rod + ~30 nm flexible tail; 7S; node-clustered, NO minifilaments** | Pollard lab EM/FCS'17 |

Modern structural note (#3): the catch/slip two-pathway structure your model uses is now the **standard** NM-II
ensemble formulation (eLife'21: catch force-scale ~1.66 pN + slip pathway, force = motor stiffness × strain) —
the field adopted your release structure. #2's compression-softness (S2 rod buckling under negative strain,
Kaya-Higuchi) is the measured basis for the tensile/compressive asymmetry the dt-arc saturated-spring probe kept
running into.

---

## 2. What the THREE-BODY MODEL does with each target — the tunable / emergent classification (the core analysis)

The model's **inputs** (things you set): the geometry of the 3 rigid bodies (head, **neck/lever-arm length L**,
rod/tail), the cross-bridge spring stiffness `myoSpring`, the orthogonal/alignment springs, the **powerstroke
conformational change**, the **nucleotide-cycle rates** (on, hydrolysis, Pi-release, ADP-release base), the
**catch-slip parameters** (d, k₀, amplitudes), and the binding kOn. Everything else should be **output**.

| observable | model treatment | reasoning / the bright line |
|---|---|---|
| **cross-bridge stiffness (#2)** | **TUNABLE INPUT** (~1 pN/nm) | a direct spring constant. The *asymmetry* (compression-soft) is a candidate EMERGENT property — see §3/§4. |
| **step size (#1)** | **EMERGENT *iff* the powerstroke is a converter ROTATION; PRESCRIBED if it is a declared rest-length shift** | THE pivotal design point — see §3. The old poster *declared* 5.5 nm (prescribed). The three-body win requires step to fall out of (lever length × converter swing). |
| **peak force / motor (#5)** | **EMERGENT — derived, = k × strain (≈ k × step)** | NOT independently tunable. You set stiffness and (via geometry) step; force falls out. Good: force can't be cheated; ~1 pN/nm × ~5 nm ≈ 5 pN lands on Finer'94 for free. |
| **nucleotide-cycle rates (#—)** | **TUNABLE INPUTS** (from Howard/literature) | measured biochemical rates are legitimately *set*, not emergent. Using measured rates is not "prescription" in the thesis sense — prescription is fitting *mechanical/force-velocity* behavior post-hoc. |
| **duty ratio (#4)** | **SEMI-EMERGENT** (cycle rates in → bound-fraction out, modulated by force-dependence) | the cycle rates are inputs, but the duty ratio is the cycle-averaged *output*, and its **rise under load** (0.02→0.06) is an emergent consequence of #3. A validation that the cycle assembles correctly, not a free knob. |
| **force-dependent detachment d (#3)** | **CALIBRATED LUMPED bond** (the *load* is emergent/real from the cross-bridge strain; the *sensitivity* d is tuned) | per RESEARCH_THESIS §4: release is a deliberately lumped calibrated bond. The thesis claim is NOT "d emerges" — it is "the load feeding the calibrated bond is **mechanically real**, not a prescribed force-velocity curve." The model must reproduce the **functional form** (exponential in the real load) with d calibrated to ~2.7 nm. *Whether d is partly emergent from lever geometry is the §3 stretch question.* |
| **force–velocity curve (#6)** | **EMERGENT ENSEMBLE** | the payoff. Concave/inverted-hyperbolic must *emerge* from bind/stroke/release under load across the population — never fitted. This is where "transferability across conditions from one parameterization" is won. |
| **speed–density (#7)** | **EMERGENT ENSEMBLE** | saturation (+ reversal) must emerge from motor crowding / ADL, not be imposed. |
| **unloaded velocity V₀ (#8)** | **EMERGENT** (step × cycle rate) but **CALIBRATED via the rates** | V₀ ≈ step × detachment rate; set by inputs, but a consistency check (does the assembled cycle give the right V₀?). |

**The thesis bright line, restated against this table:** using *measured biochemical rates* and a *measured
stiffness* as inputs is fine — that is parameterization, not prescription. **Prescription** is what the model
must AVOID: a fitted force-velocity curve, a declared step size, a hand-tuned `k_off(F)` that *is* the answer.
The three-body claim is that **#5 (force), #6 (f-v), #7 (speed-density), and the load-rectification of #4** all
**emerge** from the mechanics + the measured rates, where the prescribed (Cytosim-Hand / old-poster) motor must
*input* the step and *fit* the force-velocity. The benchmark is honest only if those four are never tuned to
match — only the inputs (geometry, stiffness, rates, d) are set, and the four emergent observables are *predicted*.

---

## 3. Structure → function across isoforms — the lever-arm hypothesis as the thesis SHOWCASE

This is the sharpest available demonstration of "emergent vs prescribed," and it is exactly the user's question
("one isoform makes less force, differs in neck length — can the model explain one as a consequence of the
other?"). The answer is **yes — and it is the single best argument the three-body model can make** — *if* the
powerstroke is implemented correctly.

**The canonical structure-function chain (the lever-arm hypothesis).** A myosin converter undergoes a roughly
**isoform-invariant angular swing** Δθ (~70°); the neck/lever arm of length **L** converts that rotation into a
linear working stroke. The mechanics give **three coupled consequences of the single structural parameter L**:
- **step ∝ L** (longer lever → bigger stroke): myosin-V (long, ~6 IQ) ~36 nm; myosin-II (short, ~2 IQ) ~5–10 nm.
- **stiffness ∝ ~1/L²** (a longer beam is more compliant): longer lever → softer cross-bridge.
- **stall force ∝ ~1/L** (= stiffness × stroke ∝ L⁻²·L): **longer lever → LESS force.**
So **a longer neck trades force for displacement, at roughly conserved work per stroke (τ·Δθ).** That IS the
user's hypothetical, real: the isoform that makes less force is the one with the longer neck, and the model can
*explain one as a consequence of the other* — but only structurally.

**The clean experimental test (isoform confounds removed): engineered lever-length variants.** Myosin-II vs
myosin-V differ in MANY things (processivity, dimerization, duty ratio), so they are a *confounded* comparison.
The clean literature test is **engineered lever-arm constructs** (same motor head + same kinetics, only L
changed — Anson'96, Ruff/Manstein'01 "genetically engineered amplifier domains," Purcell/Sweeney/Spudich'02),
which show **step size scales ~linearly with lever length**. THIS is the benchmark: in the model, set only the
**neck rigid-body length L**, hold head + kinetics fixed, and ask whether the **emergent step (and force, and
stiffness)** scale as L, 1/L, 1/L². If they do, the lever-arm hypothesis is an **emergent geometric consequence**
in the model — a result a prescribed-step model **structurally cannot produce.**

**The hinge — and the design implication you must decide.** This emergent prediction is available *only if*:
1. **The powerstroke is a CONVERTER ROTATION** (an angular conformational change Δθ at the head↔neck joint),
   NOT a declared linear step / rest-length shift. If the powerstroke is currently implemented as "shift the
   co-linear spring's rest length by 5.5 nm" (the old poster's mechanism), then **step is prescribed and does NOT
   emerge from L** — and the showcase prediction is unavailable. **Converting the powerstroke to a rotational
   conformational change at the neck joint is the single change that unlocks the entire structure→function
   thesis.** (It is also more physically faithful — myosin generates force by lever rotation.)
2. **For the stiffness/force scaling**, the cross-bridge compliance must arise (at least partly) from the
   **neck's flexural compliance**, not solely from an independently-set `myoSpring`. If `myoSpring` is set
   independent of L, then step∝L can emerge (from #1) but stiffness∝1/L² will NOT (it's pinned by the set
   constant). **Honest status:** the current model sets `myoSpring` directly ⇒ the *step∝L* prediction is
   reachable with a rotational powerstroke; the *stiffness∝1/L²* and *force∝1/L* predictions need stiffness to
   derive from neck geometry — a deeper refinement. Decide how far to push: **step∝L alone is already a strong,
   clean, prescribed-model-beating result.**

**Bonus structure→function targets (stretch, flagged honestly):**
- **Stiffness compression-asymmetry from rod buckling (Kaya-Higuchi'10).** The real cross-bridge is much softer
  in compression because the S2 rod **buckles** under negative strain. The model HAS a rod rigid body — if it
  buckles/rotates under compression rather than transmitting force, the measured asymmetry could **emerge**. This
  is directly tied to the dt arc (the catch detonated on *compressive* overshoot; the real motor is *soft* in
  compression, so a faithful rod would not transmit that load) — potentially killing two birds. Stretch target;
  depends on rod articulation.
- **d (force-sensitivity) vs lever geometry / transition-state position.** Across myosin-I isoforms d ranges
  hugely (myo1b ~18 nm, ultra-sensitive; myo1c insensitive), and the structural interpretation (PNAS'25) is the
  **position of the transition state along the lever swing**. Whether the three-body lever can place a
  transition state to make d partly emergent is almost certainly **below the lumped-bond resolution** (§4) —
  list as out-of-reach-but-noted, not a target.

---

## 4. Honest boundaries — what the model structurally should NOT claim
- **Sub-stroke timing / fast kinetics** (cardiac stroke <200 µs post-binding, Woody'19; two-step strokes) —
  below the coarse model's temporal resolution. Not a target.
- **Processivity / two-head gating** (myosin-V hand-over-hand) — requires inter-head mechanical communication the
  single-motor model does not resolve. Do not benchmark myosin-V processivity; use myosin-V only as a
  lever-length *data point* for step∝L, not a processivity test.
- **Molecular d (force-sensitivity magnitude) as emergent** — calibrated, per §2/§4.
- **Absolute biochemical rates as emergent** — these are measured inputs.

---

## 5. Benchmark sequence (single-molecule → ensemble → isoform)
1. **Single-motor mechanics (SET A):** emergent step (vs 5–11 nm), emergent force (vs ~5 pN), the catch-slip
   release reproducing **Veigel'03 d≈2.7 nm "1 pN halves detachment"** functional form (load real, d calibrated),
   duty ratio (0.02→0.06 under load). The cleanest emergence tests; isolate the motor before populations.
2. **Ensemble (SET A):** **concave force-velocity** (Debold'05) and **speed-density saturation** — must EMERGE
   from populations under load, from the §1 single-molecule parameterization, with NO ensemble-level tuning
   (the transferability standard).
3. **Structure→function (the showcase):** with a **rotational powerstroke**, sweep neck length L and test the
   emergent **step∝L** (vs engineered-lever data), and if stiffness is made geometry-derived, **force∝1/L**.
   This is the prescribed-model-beating result.
4. **Re-parameterize to SET B (Myo2)** only when moving to the flagship ring: slow V₀ (~0.4 µm/s),
   node-clustering (no minifilaments), the SCPR frame.

**The decisive thesis observable** is step 2 read against step 1: *does one parameterization (geometry +
stiffness + measured rates + calibrated d), fixed at the single-molecule level, predict the ensemble
force-velocity and speed-density across conditions* — where the prescribed motor must re-fit per condition. Plus
step 3 as the structural clincher prescription cannot reach.

---

## 6. Banked cross-bridge refinement: the composed architecture (tip→PAIRS + J1→Hookean) — a CALIBRATION-TIME project, NOT now

Two refinements discussed for getting the **force∝1/L** half of the lever-arm chain (§3) compose into one
coherent cross-bridge architecture. Recording it so it isn't lost; it is explicitly **deferred** (it re-opens
binding, release calibration, and v1 parity at once — a ground-up cross-bridge rebuild, appropriate only when the
cross-bridge is already being re-touched at experimental-calibration time).

**The current cross-bridge (for contrast).** A single raw-Hookean F8 tip spring (`myoSpring·dist`, zero rest
length, 1 pN/nm) does THREE jobs at once: (1) holds the head-tip↔site attachment, (2) provides ALL the
cross-bridge compliance, (3) supplies the load the catch/ADP-gate reads (`forceDotFil = Dot(F8, seg.uVec)`). It
is the model's ONLY raw stiff spring ⇒ it sets the dt ceiling (the entire dt arc). And because its stiffness is a
set constant (not geometry-derived), varying arm length gives **force∝L** (wrong sign vs the real **force∝1/L**).

**The composed architecture — a clean division of labor:**
- **Tip → PAIRS** (the validated, dt-robust `fracMove/(moveC·dt)` damping-limited formulation from the actin
  layer): the tip becomes a **geometric attachment constraint** — it HOLDS the head on its site, stiff but
  dt-robust. This **converts the one raw-Hookean stiff element into the dt-robust class**, potentially
  **dissolving the original F8 dt ceiling** — a *replacement* of the raw spring (cleaner than the dashpot /
  implicit / sub-step *workarounds*), reusing a formulation already trusted and published.
- **J1 → Hookean** (a converter-spring with a nucleotide-switched rest angle, calibrated κ, §3): the lever joint
  becomes the **compliance AND the load signal** — it both drives the powerstroke (rest angle 0°→60°) and gives
  elastically under load (κ·Δθ). The catch reads the **J1 deflection/torque** as load instead of the F8 tension —
  arguably MORE physical, since the real force-sensitivity is lever/converter strain, not actin-bond stretch.
- **Why this is more faithful than today's single F8:** the attachment bond is genuinely stiff
  (PAIRS-appropriate, geometric); the measured ~1 pN/nm compliance genuinely lives in the **lever**
  (J1-appropriate); and `k_tip = κ/L²` ⇒ **stiffness∝1/L² and force∝1/L emerge** — the §3 force-length tradeoff,
  for free, from one calibrated κ.

**κ estimate and stability (from the F8 numbers).** `κ = k_tip·L²`; with k_tip = 1 pN/nm and L ≈ 8–28 nm ⇒
**κ ≈ 1e-19 – 1e-18 N·m/rad (order pN·nm/rad)** — the right order for a single myosin lever. Stability: because
the calibrated joint torsion encodes the SAME tip stiffness F8 already carries, the lever-arm factor **cancels**
in the rotational stability ratio (`r_rot = κ·dt/γ_rot = k_tip·dt/γ` to leading order) ⇒ **steady-state stability
at 1e-5 is ~unchanged** (no blow-up from κ magnitude). Two real caveats: (a) a drag-arm-vs-stiffness-arm mismatch
could push r_rot a few× off the F8 value (only ~4× headroom from r≈0.5 to the r≈2 ceiling); (b) **dropping the J1
stall cap** (a pure Hookean joint never saturates) **re-exposes the transient-overshoot instability the cap was
suppressing** — the SAME explicit-stiff-overshoot regime as the F8 catch detonation, relocated to J1. The
consolation: the validated implicit `1/(1+r)` update transfers directly to a Hookean J1 (same overdamped stiff
spring, rotational), so the fix is known if needed.

**The stall cap becomes an emergence opportunity.** The current cap *prescribes* the stall force; a Hookean J1
lets the stall force **emerge** (κ × stroke geometry). Ideally the physical force ceiling then comes from the
**emergent load-gated ADP→NONE release** (the motor lets go before pushing unphysically hard) rather than a torque
clip — fewer prescribed quantities, more thesis-coherent — but this couples stall to the release calibration.

**THE ONE GATING QUESTION (cheap, answer before any of this).** Does PAIRS expose a **clean, unclipped bond
tension** for the catch/ADP-gate to read — or does its velocity-limiting **clip the high-load tail** the catch
needs? PAIRS maintains *geometry*; the cross-bridge needs the bond *tension* as a kinetic signal. If PAIRS's
per-step correction limit clips high-load excursions, it silently re-runs the **failed saturated-cross-bridge
lever** (matched count, fat-tail distribution failure) by another route. If PAIRS cannot carry the load signal
faithfully, the composed architecture does not work and F8-Hookean stays. This question gates the whole redesign.

**Status:** banked. Large payoff (emergent force∝1/L + dissolved F8 dt ceiling + a more faithful compliance
picture), but it is two coupled structural changes that re-baseline v1 parity and re-validate the motor from the
binding search up. **Calibration-time project, not now.** Available-now result remains the §3 **step∝L** measured
on the current explicit-Hookean motor (no architecture change) — see `STROKE_VS_ARMLENGTH_FINDINGS.md` (the
measured slope, and the finding that the model's effective amplifying arm is HEAD_LEN, not LEVER_LEN).
