# Experiment 3C — Topologically Faithful Two-Body Head–Converter–Lever Motor + Material-Frame Handedness

**Date:** 2026-07-13 · **Branch:** `dt-convergence-study` · **Commit at start:** `2170aff937130c232ca999a6b5f87233f58b5776`
· **Runner:** CPU sequential only (single-threaded; `-gpu` unused). **Hardware:** aorus, 16 threads (one core).
**CPU load** ~1.3–2.1. **Wall-clock:** full experiment ≈ **1.9 s**. **dt:** {1e-5,5e-6,2.5e-6,1e-6}(+5e-7,2.5e-7 for
the ledger). **Trap:** {0.02,0.05,0.10} pN/nm.

**Default-off, non-canonical prototype** (`[NON-CANONICAL TWO-BODY PROTOTYPE]`) extending
`softbox/TwoBodyConverterMotor.java` (`-exp3c`/`-twobody-topology`). **No chemistry/binding-search/detachment/
catch-slip/gliding added; not promoted to canonical. No canonical model change; production defaults unchanged;
`BoA-v1ref` untouched.** Replaces the 3A/3B **pinned cam** with the intended head–converter–lever topology and
decides the power-stroke handedness from **measured pre/post material-point trajectories**, not from `barbedDir`.

---

## 0. Framing

- **What 3B established:** explicit barbed/pointed metadata; material coordinate increasing toward the stored
  barbed end; polarity-relative force/glide tests; rotation covariance; series passive mechanics after the
  geometry remap.
- **Why 3A/3B was a pinned cam:** the neck–lever was fixed in orientation; only a single "head arm" swung about a
  fixed pivot (the converter point), with F8 at the arm's far end (radius R_A). One rigid arm about one fixed
  pin — no second moving body, no genuine converter joint, the ellipsoid decorative. And 3B took the stroke sign
  from `barbedDir` (sweep F8 toward −b̂), making polarity reversal partly tautological.

---

## 1. Corrected topology (Parts I–III; Gates 2–7)

- **Body A — ellipsoidal motor domain** 9×5.5×4.5 nm (semi-axes 4.5/2.75/2.25 nm), material frame. **Material
  points** r_F8 = (+3.5,+1.5) nm (actin-binding face) and r_conv = (−3.5,−1.5) nm (converter), **|r_F8−r_conv| =
  7.6 nm — the converter→F8 distance encoded by two points ON the ellipsoid; NO rod, NO separate head-arm body**
  (Gate 3).
- **Body B — neck–lever** L_B = 8 nm, proximal at the converter joint, distal at a **fixed-position calibration
  anchor** whose **orientation is free — the lever rotates about the anchor** (Gate 4; downstream tail/S2/surface
  compliance deferred).
- **Generalized coordinates** (planar): φ = neck–lever angle about the anchor A; ψ = motor-domain orientation.
  **Converter joint closed by construction:** C = A + L_B·û_B(φ); x_H = C − R(ψ)r_conv; x_F8 = C + R(ψ)(r_F8−r_conv)
  ⇒ max joint error **0.0 µm** over all tests (Gate 5). **θ = ψ − φ**, `U_conv = ½κ_conv(θ−θ_s)²`.
- **Passive stereospecific head–actin orientation** `U_bind = ½κ_bind(ψ − ψ_actin)²` (ψ_actin captured at
  binding, rotates with actin, not re-aimed, not a second active coordinate; Gate 6).
- **F8:** exact production `bondForces` (align OFF); actin site material-latched (fixed bindArc, never relatched;
  Gate 7). F8 force → generalized torques Q_φ = ê_conv·((C−A)×F8), Q_ψ = ê_conv·((x_F8−C)×F8). **Linearly-implicit
  2×2** solve `(a·I + K_F8 + K_conv + K_bind)Δq = F` with the F8 Gauss–Newton stiffness `k_F8·J⊗J` — required for
  stability (the tiny motor domain makes the F8/head mode τ<dt otherwise). κ_bind→∞ = actin-fixed head; κ_conv→∞ =
  rigid converter.

---

## 2. Fixed motor-frame handedness (Part IV; Gate 8) + `barbedDir` access audit

Δθ_motor is a **fixed motor-frame constant** (θ_s decreases 60° in the material frame). **`barbedDir` access audit
(instrumented):**

| phase | b̂ accessed? |
|---|---|
| (1) binding-pose construction | **yes (297)** — LEGITIMATE (stereospecific grip on actin) |
| (2) pre-target init | yes (297) — only via the pose geometry; the target = ψ−φ (motor frame) |
| (3) **post-target selection** | **0** |
| (4) **converter torque evaluation** | **0** |
| (5) **force routing** | **0** |
| (6) output analysis | 2 — LEGITIMATE (projections, after freezing) |

**Gate 8 PASS:** b̂ is NOT in target selection, converter torque, or force routing ⇒ the stroke sign is a genuine
motor-frame property; the polarity result is **non-tautological**.

---

## 3. Measured handedness — the stroke was BACKWARD (Parts V, VI; Outcome-B correction)

Pre/post material-point trajectory audit (anchor fixed, filament free), decided from **measured** projections:

- **Original Δθ = −60°:** F8 point Δ·b̂ = **+7.60 nm → BARBEDWARD (biologically reversed)**; filament COM Δ·p̂ =
  −6.91 nm (barbed-first); force-on-actin·b̂ = +0.69 pN (barbed). **Classified polarity-reversed.**
- **Correction (Outcome B):** flip the **fixed motor-frame pre/post ordering** (Δθ → +60°) — NOT force×(−1), NOT
  displacement×(−1), NOT a `barbedDir` negation. After the flip: F8 point Δ·b̂ = **−3.98 nm → POINTEDWARD ✓**;
  filament COM Δ·p̂ = **+3.62 nm (pointed-first) ✓**.

**Corrected trajectory table** (`trajectory_audit.csv`, all points move pointedward −b̂):

| point | Δ·b̂ (nm) | Δ·p̂ (nm) | transverse (nm) |
|---|---:|---:|---:|
| actin attach / filament COM | −3.62 | +3.62 | 6.0 |
| F8 point | −3.98 | +3.98 | 6.6 |
| motor center | −7.62 | +7.62 | 0.24 |
| converter / neck-lever prox | −7.64 | +7.64 | 0.19 |
| anchor | 0.00 | 0.00 | 0.00 (fixed) |

**The earlier 3B/legacy stroke direction was biologically backward.** (The large transverse ~6 nm is the lever's
arc; the filament transverse is confined by the 3D trap — noted as a geometry characteristic, not excessive for
viability, but a candidate for a flatter geometry later.)

---

## 4. Angular decomposition (M2) — the lever-arm mechanism

At κ_conv=128, the fixed Δθ partitions between neck-lever swing (Δφ) and head rotation (Δψ) by κ_bind:

| κ_bind (pN·nm/rad²) | Δφ neck-lever | Δψ head | completion |
|---:|---:|---:|---:|
| 0 (free head) | −24.3° | +33.6° | 0.96 |
| 32 | −54.8° | +3.0° | 0.96 |
| 128 | −57.0° | +0.8° | 0.96 |
| 512 | −57.6° | **+0.2°** | 0.96 |
| ∞ | −57.8° | +0.0° | 0.96 |

**A strongly bound head (κ_bind ≳ 128) expresses the stroke predominantly as neck–lever swing (Δφ≈−58°) with the
motor domain nearly actin-aligned (Δψ≈0) — the biological lever-arm swing** (M2 satisfied by measurement). The
free head (κ_bind=0) splits into head rotation + lever swing (the soft, free-rotating-head regime, M3).

---

## 5. Passive stiffness map (Part XI; Gates 11–13) + free/bound limits (M3/M4)

k_ext at (k_F8=1, κ_conv=1000): κ_bind **0→0.008** (free-head SOFT, M3), 32→0.247, 128→0.551, 512→0.806,
**∞→0.954** (actin-fixed head → the fixed-head F8 stiffness limit, M4). Monotonic in κ_bind. **k_ext in the
0.5–2 pN/nm skeletal range is reached** for κ_bind ≳ 128 (Gate 13). The passive stiffness is NOT the simple 3B
two-spring series (the binding orientation is a third element) — measured, not assumed.

---

## 6. Active stroke + load (Parts XII, XIII; Gates 14–17)

κ_bind=512, κ_conv=128: unloaded external stroke **3.62 nm** (Gate 14). Opposing load (defined vs the actin
frame) 0→5 pN reduces the external stroke **3.62 → 1.85 nm** (glide·p̂) — **load-sensitive (Gate 15)**, not a
servo (varies with load, Gate 16); the converter completion stays ~0.93–0.96 (it completes, but the load reduces
the *delivered* displacement — the same isotonic feature as 3A). Pre-stroke **preload = 0** (F8 = 0.000 pN,
converter/binding relaxed; Gate 17). Reversible.

---

## 7. Polarity tests (Part XIV; Gates 18–22) — non-tautological

| test | measured | verdict |
|---|---|---|
| **P1** actin-fixed | force-on-actin·b̂ = −3.35 pN (POINTED); force-on-motor·b̂ = +3.35 pN (motor pushed BARBED) | Gate 19 + Gate 18 PASS* |
| **P2** anchor-fixed glide | glide·p̂ = +3.62 nm (POINTED-FIRST); Δx_F8·b̂ < 0 | Gate 20 PASS |
| **P3** reverse polarity (Δθ UNCHANGED) | world glide reverses (−3.62 → +3.62); in-label pointed-first preserved | Gate 21 PASS |
| **P4** rotate assay (90° + 3D) | glide·p̂ = 3.62 (both); force-on-actin pointed (rot3D) — invariant | Gate 22 PASS |
| **P5** reverse target sign (control) | glide·p̂ reverses (−6.91) — nonbiological sign control | — |
| **P6** load vs actin frame | completion resist < free < assist | PASS |

*Gate 18 note (task-acknowledged): with **both** the actin clamped **and** the anchor fixed the *geometric*
trajectory is over-constrained; the barbed-directed motor-relative motion is reported via the **force-on-motor**
(the Newton pair of the pointed force-on-actin) and the attempted lever coordinate — a single strongly-bound
non-processive stroke translates motor+filament together, so a net barbed motor-vs-actin slip is not the observable.

---

## 8. Legacy comparison (M5; Gate 25) — the cam and the articulated motor have OPPOSITE handedness

Matched (κ_conv=128, κ_bind=512, k_F8=1, same Δθ):

| model | k_ext | neck-lever Δφ | head Δψ | glide·p̂ |
|---|---:|---:|---:|---:|
| **corrected 3C** | 0.64 | **−57.6° (SWINGS)** | +0.2° | **+3.62 nm (POINTED)** |
| **legacy cam (3B)** | 0.99 | 0.0° (FROZEN) | +11.9° | **−0.43 nm (BARBED)** |

The legacy cam not only freezes the lever (single swinging body) but glides the **opposite** way for the **same**
converter stroke — i.e. the cam and the articulated topology have **opposite handedness**. This is exactly why the
3B stroke sign (chosen tautologically from b̂) hid the reversal.

---

## 9. Work ledger (Part XVI; Gate 24) + timestep (Part XVII; Gate 23)

The lever and converter are **fast** (small drags), so the discrete-dissipation estimate under-counts at coarse
dt (fast-mode discretization) — a **converging O(dt) artifact, not a leak**: residual **0.329 → 0.248 → 0.174 →
0.097 → 0.056 → 0.031** as dt 1e-5 → 2.5e-7. Closes to **3.1% at dt=2.5e-7 (Gate 24 PASS)**. The injected
converter free energy ΔU_target = ΔU_conv + ΔU_bind + ΔU_F8 + ΔU_trap + dissipation (φ + ψ + filament); the fixed
anchor does zero work. Passive k_ext dt-invariant (finest-two 0.0%) and trap-robust (Gate 23).

---

## 10. Rendering integrity (Parts VIII, IX; Gates 10, 26)

Every `-3js` frame carries a typed **`objects`** array: `kind` ∈ {`physical_body` (motor_domain, neck_lever,
converter_joint, fixed_anchor), `physical_bond` (actin_filament, F8), `diagnostic_vector` (toward_barbed,
toward_pointed, force_on_actin, converter_axis, pre→post arrows)}, each with `type`/`label` — role is **not**
inferred from color/width/order. The motor is also rendered through the viewer's `myosins` channel (ellipsoid head
via the "Motor head" slider) and the actin via `isBarbedEnd` (cyan-+ toggle). **The 3B "long diagonal line" was the
pinned-cam head-arm (the converter→F8 swinging rod); it is removed** — the corrected topology has no such body (the
separation is two material points on the ellipsoid). 10 matched sequences (pre/post/overlay/diagnostic/free/bound/
glide/swap/rot90/rot3D).

---

## 11. Gate verdict — all 26 PASS

G1 canonical protection · G2 true two-body topology · G3 no converter→F8 rod · G4 movable neck-lever · G5 explicit
converter coord · G6 passive head-actin orientation · G7 fixed material attachment · G8 fixed motor-frame stroke
sign · G9 pre/post trajectory audit · G10 rendering integrity · G11 free-head limit · G12 bound-head limit · G13
viable passive stiffness · G14 viable active stroke · G15 load sensitivity · G16 no servo · G17 low preload · G18
barbed-directed motor-relative motion (force-based, over-constraint noted) · G19 pointed-directed force on actin ·
G20 pointed-end-first glide · G21 no polarity tautology · G22 rotational covariance · G23 timestep/trap robustness
· G24 work closure · G25 legacy comparison · G26 visual validation — **PASS.**

---

## 12. Controlling outcome & separated statements

**CONTROLLING OUTCOME: B — corrected mechanics work, but the original target handedness was reversed.** The
ellipsoidal head + moving neck–lever + fixed motor-frame converter stroke + passive strongly-bound orientation are
**mechanically and (after correction) directionally viable** — skeletal-range stiffness (0.5–0.95 pN/nm bound), a
finite load-sensitive stroke (3.6 nm, halving under 5 pN), correct barbed/pointed handedness **after flipping the
material-frame pre/post ordering**, low preload, dt-convergent work closure. **The earlier (3A/3B, and the legacy
cam) stroke direction was biologically backward** — surfaced here only because the corrected topology decides
handedness from measured trajectories with a fixed (non-`barbedDir`) stroke sign.

Separated statements:
- **Body dimensions:** motor domain ellipsoid 9×5.5×4.5 nm; neck–lever L_B = 8 nm; anchor fixed-position.
- **Material points:** r_F8 = (+3.5,+1.5) nm, r_conv = (−3.5,−1.5) nm (|Δ| = 7.6 nm, no rod).
- **Generalized coords:** φ (neck-lever about anchor), ψ (motor domain), θ = ψ − φ; U_conv, U_bind.
- **Fixed motor-frame ordering:** original Δθ = −60° (backward) → corrected Δθ = +60° (forward).
- **`barbedDir` uses:** binding pose + analysis only; NOT target/torque/routing (Gate 8).
- **Passive map:** k_ext free-head 0.008 → bound-head 0.954 (κ_bind), skeletal for κ_bind ≳ 128.
- **Head vs neck motion:** bound head Δφ≈−58°, Δψ≈0 (lever swing).
- **Trajectory:** F8 −3.98 nm·b̂ (pointed); filament +3.62 nm·p̂ (pointed-first).
- **Stroke vs load:** 3.62 → 1.85 nm (0–5 pN). **Stall:** approached; converter completes but delivered stroke → small.
- **Preload:** 0. **Force/glide polarity:** force-on-actin pointed, glide pointed-first.
- **Reversal (P3):** world reverses, relative preserved. **Rotated assay (P4):** covariant.
- **Legacy:** cam frozen-lever, opposite handedness (−0.43 nm barbed).
- **Ledger:** converges O(dt) to 3.1%. **Timestep:** dt-invariant passive.

---

## 13. Decision — chemistry integration licensed

**Outcome B is a clearly corrected result on the CORRECTED topology (not the legacy cam) ⇒ chemistry integration is
scientifically licensed.** A later experiment may add — one at a time, on this topologically-faithful, polarity-
correct prototype — normal binding, ADP·Pi→ADP target switching (a state-dependent Δθ_s of the *fixed* motor-frame
sign now established), ADP release, ATP detachment, load-dependent lifetime, and free-gliding density sweeps, with
the fine-dt gliding curve as a **regression** target (preserve ensemble motion while correcting single-molecule
mechanics), not a fitting target. **The project does NOT need to settle for a deliberately cam-like motor** — the
biologically recognizable head–converter–lever topology is viable. **Caveats:** (i) k_ext ≤ k_F8 for the F8
element; (ii) the large lever-arc transverse (~6 nm) is a candidate for a flatter geometry; (iii) the fast lever/
converter needs finer dt for the ledger/stroke dynamics; (iv) the fixed-position anchor defers real substrate
compliance. **No canonical change made.**

---

## Artifacts & commands
- Preregistration: `RUN_LOGS/twobody_topology/PREREGISTRATION.md`; log `RUN_LOGS/twobody_topology/exp3c_full.log`.
- CSVs (`.../csv/`): `passive_map`, `angular_decomposition`, `active_load`, `trajectory_audit`, `polarity_tests`,
  `legacy_compare`, `ledger`. Figure: `RUN_LOGS/twobody_topology/exp3c_summary.png`. Viewer:
  `~/Code/SoftBox/threejs_twobody3c_{preStroke,postStroke,overlay,diagnostic,freeHead,boundHead,glide,swapPolar,rot90,rot3D}/`.
```
./scripts/build.sh
./scripts/run_lasertrap.sh -exp3c -out RUN_LOGS/twobody_topology/csv -3js ~/Code/SoftBox/threejs_twobody3c
./scripts/run_gpu.sh -cpu                         # Gate-1 regression (FDT, CPU)
python3 scripts/twobody3c_analyze.py RUN_LOGS/twobody_topology/csv RUN_LOGS/twobody_topology/exp3c_summary.png
```
**View:** `python3 SoftBox/sim_server.py 8000` from `~/Code`, open
`http://localhost:8000/SoftBox/sim_viewer_boa.html` (Recent picker → newest; tick "Barbed ends" for the cyan +).
