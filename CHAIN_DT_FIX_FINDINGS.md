# CHAIN-dt CLASS FIX — `brownianForceMag(dt)` + `setChainParams(dt)` (2026-06-22)

The two chain-dt-class stale copies from `DELTAT_AUDIT_FINDINGS.md` (§3.A the FDT Brownian amplitude;
§3.B the chain stiffness) are **structurally eliminated** — both dt-dependent force-law quantities now
**require the caller's stepping dt**, so no caller can silently inherit `Constants.deltaT`. This is a
**faithfulness fix to v1** (v1 uses the dt-matched amplitude + chain dt), not a model change. The
turnover physics validations stand; the coalescence experiments re-baselined (qualitative survives).

## 1. The root fix (single source structurally enforced)

**`Constants.brownianForceMag(double dt)`** (was no-arg `brownianForceMag()` returning
`sqrt(2kT/deltaT)`). dt is now **required** — the no-arg form is deleted, so the FDT amplitude can
never silently use the hardcoded `Constants.deltaT`. Faithful to v1 `GPUMoveThing.java:6787-6789`
(`params.set(1, sqrt(2kT/dt))` with `dt = Env.deltaT`, v1's stepping dt).

**`FilamentStore.setChainParams(double dt)`** (was no-arg, hardcoding `chainParams[0]=Constants.deltaT`).
dt is now **required** and written to `chainParams[0]` — the override-or-bug footgun is gone. Faithful
to v1 `chainPairForcesKernel` (`chainParams[0]=dt`, the stepping dt).

**Every caller updated** to pass its stepping dt (27 call sites). The dt=1e-5 harnesses
(Aging/Growth/Depoly/Sever/Treadmill/DeadSlot/FilamentBirth/NodeNucleation/TestB/Ring3x3) now pass
`dt` ⇒ corrected (warmer/stiffer); `DiffusionHarness` steps at `Constants.deltaT` ⇒ passes it ⇒
**no-op**. The 21 inline `Math.sqrt(2kT/dt)` forms (gliding/contractile/motor/node stores) were already
single-source-correct (local dt) and are unchanged.

`TestBScprHarness`'s `-chaindtfix` flag (`CHAIN_DT_FIX`) and the "BUG:" comment are now **vestigial**
(`chainParams[0]=dt` holds by construction) — the conditional override + stale comment were removed
(the `V1_PAIRS` coefficient block is kept). *(One diagnostic still prints `chaindtfix=false` as a
label — cosmetic, the dt-fix is unconditionally applied.)*

## 2. The seed-Brownian — set faithful to v1 (the one judgment call, resolved: PROCEED)

The formin-seed filaments carried an empirical `bornScale = BTransCoeff/30` damping ("B2
dt-compensation"), **tuned against the wrong (10× cold) amplitude**. v1's formin/node-anchored-filament
Brownian model is decisive (`BoA-v1ref/boxOfActin/FilSegment.java:621-642`, `motherFil == null` branch):
such a filament gets the **FULL FDT Brownian** (`transScale = BTransCoeff`, `rotScale = BRotCoeff`) — the
**node tether does the holding, NO per-seed damping**. So the `/30` hack was removed and the seed set to
**full `BTransCoeff`** in the coalescence experiments (`Ring3x3Harness`, `TestBScprHarness`).

**Empirically verified v1's model is a clean match (the judgment call):** the full-FDT seed is **stable —
no flailing**. `NodeNucleationHarness` gate #8 (now at the corrected amplitude) shows the *undamped*
seed wander is only **4.0 nm** (bounded); Ring3x3 and Test B ran to completion with **conservation EXACT,
0 phantoms**. The B2 "flailing at full thermal" concern does not materialize: at dt=1e-5 the per-step
thermal displacement (`Var ∝ 2D·dt`) is 10× *smaller* than v1's at dt=1e-4, so the fracMove=0.5 node
tether holds it even more easily than v1's. *(NodeNucleationHarness's own seed/`dampingFactor` and its
mechanism gate #8 were left intact — it is a nucleation-mechanism harness, not a coalescence experiment;
gate #8 is a self-contained damped-vs-undamped comparison that still passes.)*

## 3. Validation

### Turnover physics validations — STAND (insensitive to amplitude/chain, all PASS, CPU)
| Harness | Key result |
|---|---|
| `filbirth` (B1) | allocator / born≡preplaced / binding+gather — PASS |
| `deadslot` | reuse correct, conservation EXACT, fix-off control — PASS |
| `depoly` | conservation EXACT, off-rate `P_emp 0.00082` vs `0.00080` — PASS |
| `growth` | split@64, contour 0.086→2.50 µm, no-op≡baseline — PASS |
| `aging` | proxy C_c 0.8% vs analytic, conservation, asymmetric C_c — PASS |
| `severing` | cofilin dissolve + combined turnover, conservation EXACT — PASS |
| `treadmill` | C_c from both directions (rel 0.6%), C_c invariance — PASS |
| `nodenuc` (B2) | rate 9.7%/Poisson, tether, dissolution, pool, damping #8 — PASS |

### CPU≡GPU — PRESERVED (the fix changes the value, not the determinism)
- `deadslot` GPU: lifecycle **bit-identical** (state=0 mon=0 seedNode=0 mismatches; nucFrac float32
  8.94e-08); reuse/active/Σmon/taken/ret all CPU==GPU.
- Test B: "CPU≡GPU agree".
- Ring3x3 GPU (device-resident ~58-kernel graph): scale/no-crash + CPU≡GPU aggregate — see JOURNAL.

### Production paths — BYTE-UNCHANGED
The production harnesses (gliding / contractile / dimer / minifil / node-glide / dense-gliding) **do not
call the changed methods** (they set `chainParams[0]=dt` manually and use inline `sqrt(2kT/dt)`), so they
are byte-unchanged by construction. Confirmed by re-run: `contractile` reproduces its regime (steady
~2.09 pN, crux/control/containment-bit-identity all PASS); `dimer` PASS; `minifil` PASS.

### Coalescence experiments — RE-BASELINED (qualitative survives; numbers shifted)
- **Test B′ aimed (2-node SCPR):** nodes approach **0.600 → 0.526 µm** (initial approach Δ=0.074 µm,
  **~17× the Brownian noise** rms 0.0043); self-capture **0.00**, cross-capture survives (peak 8, force
  14.9 pN); CPU≡GPU agree ⇒ *"STAGE 1 demonstrates SCPR capture-and-pull (nodes approach beyond noise)."*
  (pre-fix the convention-swap run reported 0.600→0.483, ~27× noise — magnitude shifted, the qualitative
  result and the convention-swap self-grab-elimination both hold.)
- **Ring3x3 KIN=1 faithful (9-node net):** **COALESCING** — net RMS extent shrinks **64.8%**
  (0.289 → 0.101 µm), all **9/9 nodes in one connected cluster**, scheme-0 soft tether sufficient (no
  scheme-1 signal), conservation EXACT, 0 phantoms. (pre-fix reported 78.5% at 300k steps; this 30k-step
  re-confirm is strongly coalescing — qualitative picture intact, as predicted "warmer search + stiffer
  aim plausibly strengthen coalescence".)

## 4. Net effect

The chain-dt class is **structurally eliminated**: there is no hardcoded dt in any force law — the FDT
Brownian amplitude and the chain stiffness both derive from the caller's stepping dt, matching v1. The
inc-7 turnover/ring filaments now run at the **physically correct temperature** (was 10× cold) on a
**correctly-stiff chain** (was 10× soft), with a **v1-faithful full-FDT formin seed** (the `/30` damping
hack removed). The ring work proceeds on a faithful filament thermal-search reach — the quantity its
`λ > A·w` capture criterion depends on. The turnover physics validations stand unchanged; the
coalescence experiments re-baselined with the qualitative SCPR/coalescence results surviving.
