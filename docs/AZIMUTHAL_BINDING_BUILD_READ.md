# AZIMUTHAL_BINDING_BUILD_READ — final read before the build plan

**Read-only (2026-07-09).** No edits, no runs, `BoA-v1ref` reference-only. Companion to
`docs/AZIMUTHAL_BINDING_READINESS.md` (which classified the roll DOF as **FREE** and mapped the two bind
gates). This read resolves the two facts that size the build correctly: **(A)** whether v1 has a reusable
helix-frame convention worth adopting, and **(B)** whether **off-axis bond placement** — the link that lets
binding exert *axial* torque and thus twirl the filament — is localized or ripples.

**Stance:** v1 is a *reference, not a spec*. The goal is azimuthally-gated, site-limited binding **so that
filament twirling during glide is possible**. The four links that must all close for twirl:
1. **azimuthal gate** — head binds only within an acceptance window of the local site azimuth (throttle);
2. **off-axis attachment** — bond attaches at radius R at the site azimuth on the filament *surface* (the
   moment arm that lets binding exert axial torque) — **PART B crux**;
3. **coherent roll** — an inter-segment torsional-roll spring so segments roll as one filament;
4. **free-but-quiet roll** — roll integrated under that torque with the thermal kick damped.
The gate alone (1) throttles engagement but produces **zero twirl** without (2).

---

## PART A — v1 helix-frame convention: reuse the *magnitudes*, derive handedness fresh; roll-coherence & azimuth-binding are NEITHER present

### A partial, non-authoritative convention exists (rendering/branching only)
v1 has a per-monomer helix bookkeeping scalar `helixAng` — **distinct from the segment body frame** — used
only for monomer rendering (`ptFromHelixPos`) and Arp2/3 branch azimuth (`makeArpBranch`), never for the
segment frame or binding. Its **magnitudes are actin-faithful and reusable** (`Env.java`):

| Quantity | Value | Source |
|---|---|---|
| `helixWaveLength` (crossover / half-turn repeat) | **0.036 µm** | `Env.java:531` |
| `monsPerHelixTurn` = waveLength/monoRadius | **13.333** | `:533` |
| `helixAngInc` = π/monsPerHelixTurn | 0.2356 rad = **13.5°** | `:534` |
| **per-monomer azimuthal advance** = π − helixAngInc | 2.906 rad = **≈166.5°/monomer** | `FilSegment.java:1055,1065` |
| `helixMonOffset` (monomer-center radial offset) | **0.0008 µm** | `:536` |
| `helixPitch` = π/waveLength | 87.27 rad/µm | `:535` |

The lattice point is `pt = (pos, helixMonOffset·cos(curAng), helixMonOffset·sin(curAng))` in the body frame,
`curAng = initAng + pos·helixPitch` (`FilSegment.java:3547-3552`), then rotated body→lab via yVec/zVec.

### Handedness is genuinely ambiguous — do NOT lift v1's sign
- In the body frame, advancing toward the plus end rotates y→z (`cos`,`sin` with increasing `curAng`), i.e. a
  **right-handed screw in body coordinates** — but this is a *drawing* convention, not a validated −167°
  left-handed actin convention.
- The phase reference is **random per segment**: yVec is `Pt3D.RandomUnitVec(...)` at birth in **all three**
  `FilSegment` constructors including the split-child (`FilSegment.java:277,294,313`), and `helixAng` starts
  `2π·rand` (`:267`). v1 commits to **no coherent lab-frame handedness and no inter-segment phase coherence**.
- **⇒ Reuse the magnitudes (36 nm repeat, 13.333 mon/half-turn, ≈166.5°/mon, 0.0008 µm offset); derive the
  handedness fresh from 13/6 actin geometry (left-handed genetic helix) and impose v2's own coherent phase.**
  The sign is the whole point of the twirl direction — treat v1's screw as untrustworthy here.

### Roll coherence: v1 has NONE (confirms the readiness survey)
The only inter-segment orientation torque is the bending straightener `torsionVec = cross(uVec, nbrUVec)`
(`FilSegment.java:1816-1818`) — **⊥ uVec, zero axial-roll component**. A full census: yVec appears 5× in
`FilSegment.java` (3 random-at-birth, 2 integration); **no yVec↔yVec neighbour coupling anywhere**. v1 has
**no torsional-roll spring about the long axis**; each segment's roll is a free, uncoupled, random-at-birth
DOF. (The one roll-touching torque is *motor→segment*: `MyoFilLink.alignYVecTorque` aligns a bound motor's
yVec to the segment's, `MyoFilLink.java:258-280` — a template for the *shape* of a roll-coupling torque, but
it does not propagate roll along the filament.)

### Azimuth-aware binding: v1 has NONE
`MyoMotor.checkFilSegCollision` (`MyoMotor.java:381-423`) is a pure perpendicular-drop onto the filament
**axis**; gates read only uVec dots + perpendicular distance, never yVec/zVec. The stored attach param is
**axial arc length only** (`arcOnFil = alpha·|end2−end1|`, `:420-421`), and `MyoFilLink.updatePos` places the
bond **on the centerline** (`attachPt = end1 + posOnSeg·uVec`, `:282-291`). No "which side of the cylinder"
test — v1 is centerline / azimuth-agnostic, exactly like v2.

### VERDICT A
- **(a) Convention to adopt?** Reuse the actin **magnitudes** (table above); **v2 derives handedness fresh**
  (v1's screw sign is a non-authoritative rendering choice, phase is random-per-segment). The 36 nm / 13.333 /
  166.5° / 0.8 nm numbers save re-derivation and match actin.
- **(b) Roll coherence / azimuth binding?** **NEITHER exists in v1.** Azimuthally-gated binding + roll
  coherence + a twirling filament is **genuinely new v2 physics, not a port.** Reusable pieces are only (i) the
  helix magnitudes and (ii) the *shape* of `alignYVecTorque` as a torque template.

---

## PART B — off-axis bond placement: **LOCALIZED, and it AUTOMATICALLY feeds the axial-roll torque channel**

### The crux confirmed: F8 today produces zero axial torque (centerline attach)
In `CrossBridgeSystem.bondForces` the attach point is on the centerline `ap = sc + aOff·su`
(`:114-115`), so the segment lever `RS = (ap − sc) = aOff·su` is **∥ uVec** (`:139`). The segment torque
`TS = RS × nF` (`:141`) is therefore **⊥ u by construction → zero axial (‖u) component**. This is the missing
link the readiness survey flagged.

### Off-axis attach automatically drives roll — nothing downstream discards the axial torque
The full torque flows through unmodified:
- `bondForces` stores the **full 3-vector** seg torque `TS + T9 + sF10` into `bondData[d+9..11]` (`:200-202`).
- `segGather` sums the **full 3-vector** `bondData[d+9,10,11]` into `filTorqueSum` (`segGather`, all three
  components — verified, no component dropped).
- `RigidRodLangevinIntegrationSystem.integrate` projects the **whole** `filTorqueSum` onto the body axes;
  `bwx = (torqueSum·u)/bRotGam_x` (`:79`) is the roll rate, applied to yVec (`:103-110`).

So with off-axis attach `ap = sc + aOff·su + R·(cosψ·sy + sinψ·sz)`, `RS` gains a radial part → `TS = RS × nF`
gains a **‖u component whenever the F8 force has an azimuthal (tangential) component** → that lands in
`torqueSum·u` → `bwx` → yVec roll. **The axial-roll torque channel is already fully assembled and open; it is
simply fed zero today because RS ∥ u. No torque-assembly change is required.** (The physics: when a bound head
sits off to one azimuthal side, F8 pulls tangentially, and at radius R this is a twirl torque.)

> **Note — the roll channel is not entirely virgin.** F10 (`sF10 = +T10`, `t10 = seg.yVec × head.yVec`,
> `:156-168`) can already carry a small ‖u seg-side component (it aligns seg.yVec to head.yVec, rest 0) — an
> *elastic head↔seg roll coupling*, not a directed drive; and under `-axlock` the seg reaction is zeroed
> (`:190`). Off-axis F8 is the intended *directed* twirl driver.

### The `-xbimplicit2` coupled solve is NOT entangled (translation-only; offset held explicit)
The implicit cross-bridge solve operates on **head + segment CENTER translation only**; rotation is explicit:
- `implicitCorrect` (head-only): *"SCOPE: bound-head TRANSLATION only. Torque/rotation (the R×F8 positional
  torque + F9/F10 alignment) stay EXPLICIT"* (`:941-943`).
- `coupleSolveSeg` (coupled head+seg): *"F8 is zero-rest-length ⇒ isotropic stiffness ⇒ … the bond offset
  c_i CANCELS — held explicit"* (`:968-978`); it solves the segment **center** per body axis (diagonal
  translational drag `filBTransGam`, `:1050-1062`) and **never touches uVec/yVec**.
- **⇒ off-axis attach changes only the EXPLICIT positional torque (the channel the implicit solve deliberately
  leaves alone). The implicit solve already holds the offset explicit and center-couples the translation, so a
  larger/radial offset does not perturb its linear per-segment star.** Not entangled.

### Head-side reaction is localized (existing R×F, new F)
The head lever `RH = (htip − hcenter)` (`:137`) is head-internal geometry, **unchanged** by where on the
segment the bond attaches; only F changes direction (new `ap`). Head-side stays the existing `R×F`. Localized.

### The one real cost — attach-point duplication across ~5 sites
`ap = sc + aOff·su` (centerline) is repeated in every bond variant: `bondForces:114-115`,
`bondForcesCanonical` two-point `:495-496,:503-504`, `bondForcesCanonicalConfig1Perp:602-603`,
`bondForcesCanonicalConfig1:769-770,:783-784`, and the dashpot `:866-867`. **The off-axis edit must be
replicated across all of them** (mirrors the bind-predicate's 5-copy duplication from the readiness survey).

### Bond-azimuth capture (the quantity both the gate and off-axis placement need)
The ⊥-offset `(dx,dy,dz) = (cp − head)` computed in `reachTestDistSq` (`BindingDetectionSystem.java:74-76`)
and **discarded** is exactly the head's azimuthal position around the filament. Its angle relative to
`seg.yVec` (via `atan2(offset·seg.zVec, offset·seg.yVec)`) yields ψ. This is the **same quantity** the
azimuthal *gate* needs (accept iff the local site azimuth φ(s) faces the head's azimuth within the window).
Requirements to capture it:
- **Thread `filYVec` into the bind signatures** — the predicate currently takes only end1/end2, not the frame
  (readiness-survey flag; a signature change; replicate across the 5 predicate copies).
- **Store ψ as a per-motor `FloatArray` in `MotorStore`** — one new field on the `bindArc2`/`perpRest`
  precedent (flag-gated, default-unused, byte-identical; `MotorStore.java:76-93`). Store ψ as a **material**
  azimuth *relative to the (rolling) seg.yVec frame* so the attach point `ap = sc + aOff·su + R·(cosψ·sy(t) +
  sinψ·sz(t))` tracks the monomer as the segment rolls — that materiality is what makes the F8 restoring force
  twirl the filament rather than slide off-monomer.

> **Physics flag — moment-arm radius R.** Use the **physical filament radius** (~3.5–4 nm; `Constants.radius`)
> for the twirl moment arm, NOT v1's `helixMonOffset` = 0.8 nm (a rendering monomer-center displacement). The
> moment arm sets the twirl torque magnitude.

### VERDICT B
Off-axis bond placement is **(i) LOCALIZED** — an edit to the attach-point construction in the ~5 bond-force
sites — **and it automatically produces axial-roll torque** (the seg-side torque is assembled and gathered as
a full 3-vector and the integrator projects it onto u → `bwx`; nothing is dropped). It is **not**
torque-dropped (ii) and **not** implicit-entangled (iii): the implicit solve is translation-only and holds the
bond offset explicit. The only broadening is the ~5-site duplication (mechanical) and threading `filYVec` +
one new per-motor ψ field into binding.

---

## Build-piece sizing (now that the crux is assessed)

| Piece | Scope | Size | Notes / risk |
|---|---|---|---|
| **1. Azimuthal gate** | Thread `filYVec` into the bind predicate; compute ψ from the (already-computed, discarded) ⊥-offset; accept iff φ(s) faces the head within the window | **Medium** | Replicate across the **5 predicate copies** (PTX inlining bug forbids routing through the helper). Needs φ(s) = φ₀ + (2π/P)·s ⇒ the **φ₀ store + axial-distance walk** from the readiness survey. |
| **2. Off-axis bond + torque** | `ap = sc + aOff·su + R·(cosψ·sy + sinψ·sz)`; store material ψ per motor; use R = filament radius | **Small–Medium** | **Localized; auto-feeds `bwx` (verdict B).** Replicate across the **5 bond-force sites**. Implicit solve untouched. Head-side unchanged. This is the link that actually produces twirl. |
| **3. Torsional-roll spring** | New inter-segment system: couple neighbour rolls about u (resist relative yVec twist), initialized to the helical advance (≈166.5°/monomer, handedness derived fresh) | **Medium–Large** | **Genuinely new physics — no v1 port** (verdict A(b)). This is what makes segments roll as *one coherent filament* so summed off-axis torques twirl the whole thing. Template: the *shape* of `alignYVecTorque`. Chain infra exists (end1/end2 neighbour links) but the torque form + a coherent φ₀/helical init are new. **Largest unknown.** |
| **4. Roll-thermostat-off flag** | Damp the Brownian roll kick (`randTorque_x`) on the filament so the twirl is observable over thermal roll (`bRGx ∝ R²` is tiny ⇒ roll is thermally hot) | **Small** | A `brownRotScale`-style per-axis attenuation on the x (roll) component; flag-gated. Physics-tuning, not structural. Pairs with piece 3 (a stiff roll spring + damped kick = quiet coherent roll). |

**Sequencing intuition for the planner:** pieces **1 + 2** close the *throttle + drive* (gate limits
engagement; off-axis attach makes each bond exert axial torque) and are mostly localized/mechanical given the
readiness-survey φ₀ layer. Piece **3** (roll coherence) is the substantive new-physics build and the one to
prototype first for feasibility — without it, off-axis torques twirl each segment independently rather than
the filament as a whole. Piece **4** is a small observability tuning that lands with 3.

**No implementation performed. `BoA-v1ref` read-only throughout.**
