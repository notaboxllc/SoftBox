# deltaT SINGLE-SOURCE AUDIT — findings (2026-06-22)

Read-only audit. **Two stale-copy instances of the chain-dt class found** (one known/documented, one
NEW). Neither is fixed here — fixing either **re-baselines validated numbers** (the Pause+document
boundary), so both are flagged for jba below. Everything else (the biochem cadence, KIN, the
nucleotide cycle, motor joints, containment, crosslinker formation) derives from one authoritative dt
correctly.

---

## 1. The authoritative dt source

`Constants.deltaT = 1e-4 s` (`Constants.java:30`, ported from `Env.deltaT_init:110`) is THE declared
production timestep. But it is **not enforced as a single global dt**: `dt` is threaded as a
per-call **parameter** (constructor args + `setParams`/`set*Params` methods that write it into each
store's params array). The real authoritative dt **per simulation** is *the harness's stepping
`dt`*, which legitimately differs from `Constants.deltaT`:

- Motor-bearing harnesses (articulated-motor stiffness) step at **`dt = 1e-5`** — gliding, contractile,
  dimer/minifil/node, Test B, and **the inc-7 turnover + Ring3x3 layer**.
- Pure-diffusion / crosslinker-bundle harnesses step at **`Constants.deltaT = 1e-4`**.

So the architecture is *"one authoritative dt per simulation, plumbed through params arrays."* This is
sound **as long as every dt-dependent quantity reads the same stepping dt** — which is exactly where
the two stale copies below fail.

---

## 2. dt-dependent quantity → derivation table

| # | Quantity | Where | Derives from | Verdict |
|---|----------|-------|--------------|---------|
| 1 | Integrator step `coord += dt·v` | `RigidRodLangevinIntegrationSystem` (`params[0]`) | harness dt (each harness passes its dt to `setParams`) | ✓ |
| 2 | **Brownian amplitude `sqrt(2kT/dt)`** (`params[1]`) | `BrownianForceSystem:57` reads it; produced by `Constants.brownianForceMag()` **or** inline `Math.sqrt(2kT/dt)` | **MIXED** — inline form ✓; `Constants.brownianForceMag()` hardcodes `Constants.deltaT` ✗ | **STALE — §3.A** |
| 3 | **Chain force `chainParams[0]`** (F3/F4 `/dt`) | `FilamentStore.setChainParams()` | hardcodes `Constants.deltaT`; harness must override | **STALE — §3.B** |
| 4 | Motor body/joint Brownian + `fracMove` (`k=coeff·γ/dt`) | `MotorStore.setBodyParams/setJointParams` (`:237` `sqrt(2kT/dt)`) | passed dt | ✓ |
| 5 | Node/minifilament backbone Brownian | `NodeStore:126`, `MiniFilamentStore:107` (`sqrt(2kT/dt)`) | passed dt | ✓ |
| 6 | Nucleotide cycle `rate·dt`; refractory `ceil(myoRebindTime/dt)` | `NucleotideCycleSystem` (`nucParams[0]`, `kinParams[6,10]`) | passed dt | ✓ |
| 7 | Containment push `mag = …·γ/collisionDeltaT`; cadence `collisionCheckInt = collisionDeltaT/dt` | `ContainmentSystem`, `boxParams` | `collisionDeltaT` (v1 force-time const = 1e-4) + harness dt for the cadence; self-consistent (force/τ × checkInt × dt = intended push) | ✓ (declared cadence) |
| 8 | Crosslinker formation `P = 1−exp(−kon·conc·dtCheck)`, `dtCheck = dt·crosslinkCheckInt` | `CrosslinkerStore`/`CrosslinkerBundleHarness` | harness dt; bundle harness steps at `Constants.deltaT`, `checkInt=100` ⇒ `dtCheck=0.01` (its own biochem cadence) | ✓ |
| 9 | **Biochem turnover cadence** `biochemCheckInt = round(biochemDeltaT/dt)`; `P = rate·biochemDeltaT` | `GrowthStore`/`DepolyStore`/`AgingStore`/`SeverStore` | `Constants.biochemDeltaT` (=1e-3) + harness dt | ✓ (declared multiple — §5) |

---

## 3. The two stale copies (the chain-dt class)

Both are the failure mode the chain-dt precedent warns about: **a dt-dependent quantity holding the
hardcoded `Constants.deltaT` (1e-4) while the simulation steps at `dt = 1e-5`** ⇒ the force law is
silently rescaled. Both are confined to the dt=1e-5 harnesses; the dt=1e-4 paths are correct.

### 3.A — `Constants.brownianForceMag()` (NEW finding; the more severe of the two)

```java
// Constants.java:62
public static double brownianForceMag() { return Math.sqrt(2.0 * kT / deltaT); }   // deltaT = 1e-4, HARDCODED
```

The FDT amplitude must use the **stepping** dt: `Var(Δx_rand) ∝ (brownianForceMag·dt)² / γ = 2kT·dt/γ`
requires `brownianForceMag = sqrt(2kT/dt_step)`. A harness stepping at `dt=1e-5` but passing
`Constants.brownianForceMag()` (computed at 1e-4) gets **`Var ∝ (1e-5)²/1e-4` vs the correct
`∝ 1e-5` — i.e. the filaments are 10× too COLD** (effective T = kT/10).

**Affected (step 1e-5, pass `Constants.brownianForceMag()`):** `Ring3x3Harness:231`,
`TestBScprHarness:450`, `TreadmillHarness:104`, `AgingHarness:93`, `GrowthHarness:88`,
`DepolyHarness:95`, `SeveringHarness:97`, `DeadSlotReuseHarness:115`, `FilamentBirthHarness:76`,
`NodeNucleationHarness:88`.

**Smoking gun (internal inconsistency):** in `Ring3x3Harness` the **motors** get the correct amplitude
(`mot.setBodyParams(dt)` → `sqrt(2kT/1e-5)`, line 358) while the **filaments** in the same simulation
get `sqrt(2kT/1e-4)` (line 231) — there is no physical reason for filaments to be 10× colder than the
motors they bind. This is a defect, not intent.

**Why gates still pass:** the affected harnesses gate on **conservation / rate / lifecycle / CPU≡GPU**,
all insensitive to the absolute Brownian amplitude (CPU and GPU share the same wrong amplitude ⇒ still
bit-identical). The formin-seed filaments are additionally Brownian-**damped** by an *empirically tuned*
`bornScale` (e.g. `Ring3x3:234` `BTransCoeff/30`); that tuning partly absorbs the amplitude error, so
the *observed* wander is whatever was tuned to with the wrong base — which is exactly why fixing it
**re-baselines** the seed-wander / SCPR-thermal-search behavior.

### 3.B — `FilamentStore.setChainParams()` (KNOWN class; Test B documented it)

```java
// FilamentStore.java:343
chainParams.set(0, (float) Constants.deltaT);   // HARDCODED 1e-4
```

The chain F3/F4 force is `∝ coeff·γ·strain/chainParams[0]`; the integrator then multiplies by the
stepping dt. If `chainParams[0]=1e-4` but stepping at `1e-5`, the chain relaxes 10× too slowly ⇒ a
**10× too-soft filament** (the exact Test B `bindTimer`-era bug, `TestBScprHarness:452`).

Harnesses must override `chainParams.set(0, dt)` after `setChainParams()`. **Correctly overridden:**
`Ring3x3:233`, `DiffusionHarness:317/552`, and the manual-set production paths
(`GlidingHarness:146`, `ContractileAssayHarness:225/485`, `DimerGlideHarness:453`,
`NodeContractileHarness:191`); `TestBScprHarness:455` overrides only under `-chaindtfix`/`-v1pairs`
(default still buggy — *documented*). **NOT overridden (stale at dt=1e-5):** `AgingHarness:94`,
`GrowthHarness:89`, `DepolyHarness:96`, `SeveringHarness:98`, `TreadmillHarness:105`,
`DeadSlotReuseHarness:116` — all build connected chains (split children / treadmilling) so the
mismatch is **live** (10× too-soft chain). `FilamentBirthHarness:78/393` and `NodeNucleationHarness:89`
also don't override but build **no chains** (free seeds, neighbors −1) ⇒ moot but latent.

As with §3.A, the affected harnesses gate on conservation/rate/lifecycle/CPU≡GPU, not chain
mechanical fidelity, so the gates pass despite the wrong stiffness.

---

## 4. KIN / rate-boost — RATE-SPACE ✓

`Ring3x3Harness.KIN` (default 100) scales **rate constants only** — `k_on`, `k_off1`
(`:271-272`), the aging `pH`/`pD` (`:278-279`), the depoly-proxy `pATP`/`pADP` (`:280-281`), the
cofilin `p_cof` (`:285`), and the nucleation rate (`:266`). It **leaves `dt` and `biochemDeltaT`
untouched** (verified — no `KIN` multiplies any timestep). It therefore cannot distort the
biochem-vs-mechanics ratio *via the clock*; it scales every biochem rate equally so C_c and all rate
**ratios** are preserved (as the harness header states). ✓ Done in rate space, per jba's principle.

## 5. Biochem cadence — DECLARED DERIVED MULTIPLE ✓

`Constants.biochemDeltaT = 1.0e-3` (`Constants.java:76`) is an **independent constant** (faithful to
v1's separate `Env.biochemDeltaT_init`), equal to **10·deltaT** (1e-3 = 10·1e-4). The turnover stores
derive `biochemCheckInt = round(biochemDeltaT/dt)` (=100 at dt=1e-5, =10 at dt=1e-4) and the
per-cadence probability `P = rate·biochemDeltaT` from `(rate, biochemDeltaT)` — **never a stale copy**
(`GrowthStore:46-47,82`, `DepolyStore:45-46,77`, `AgingStore:49,56-57,63-64`, `SeverStore:48,65`). Because
`deltaT` exactly divides `biochemDeltaT`, `checkInt·dt == biochemDeltaT` exactly for every dt in use, so
the nominal `biochemDeltaT` used in `P` matches the actual interval. ✓

*Minor structural note (not a bug):* `biochemDeltaT` is stored as an independent literal rather than
declared as `N·deltaT`. It is consistent today (exact 10×) but would silently drift if `deltaT` were
changed without re-deriving `biochemDeltaT`. A defensive improvement would be
`biochemDeltaT = BIOCHEM_N * deltaT` (with `BIOCHEM_N = 10`), making the multiple explicit. Same
applies to the crosslinker `crosslinkCheckInt` and containment `collisionDeltaT` (each correct today,
each an independent literal).

---

## 6. Recommendation (for jba — NOT applied; re-baselines validated numbers)

Both §3 stale copies share one root cause: a dt-dependent quantity defaulting to the hardcoded
`Constants.deltaT` instead of requiring the caller's stepping dt. The clean root fix:

1. `Constants.brownianForceMag(double dt)` (require dt; delete/deprecate the no-arg form) — forces every
   caller to pass its authoritative dt.
2. `FilamentStore.setChainParams(double dt)` (require dt; set `chainParams[0] = dt`) — eliminates the
   override-or-bug footgun.

For harnesses already at the correct dt (gliding, contractile, Diffusion, Ring3x3's chain) this is a
**no-op**. For the dt=1e-5 turnover/ring/TestB harnesses it **changes the Brownian temperature and
chain stiffness** (stiffer chain, 10× warmer / `sqrt(10)`× larger Brownian amplitude) ⇒ it
**re-baselines** their tuned seed-wander, SCPR thermal-search reach, and coalescence numbers. Per the
audit's Pause+document boundary, this is **flagged, not executed.** A secondary question for jba: the
pure-turnover harnesses (Aging/Depoly/Growth/Sever/Treadmill/DeadSlot) carry **no motors** — if they
were stepped at `Constants.deltaT = 1e-4` instead of the inherited `1e-5`, **both stale copies would
vanish for free** (the hardcoded 1e-4 would then be correct), with no force-law change.

**Production paths are unaffected:** gliding/contractile/dimer/minifil/node-glide all set the Brownian
amplitude and `chainParams[0]` from their own dt (or pin filaments with amplitude 0). The defect is
confined to the inc-7 validation/turnover harnesses and the Ring3x3 experiment.
