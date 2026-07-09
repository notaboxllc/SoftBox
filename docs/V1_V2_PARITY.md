# V1 ↔ V2 Comparison Parity Contract

**Standing reference — read before setting up ANY v2 assay that is validated against a v1 (`BoA-v1ref`) run.**
Set every parameter below *up front*, in one pass, not one failed iteration at a time. Linked from `CLAUDE.md`.
This is a living document: add any new comparison-relevant parameter the moment it appears.

## The discipline
1. **The v1 reference is a pinned, recorded fixture — not a hand-run one-off.** A comparison matches a *specific*
   v1 config whose every parameter is recorded (below). Don't chase a one-off; pin it.
2. **Tier-1 universal constants are GLOBAL** — set once, read everywhere, **never assigned per-assay**.
3. **dt-dependent coefficients DERIVE from the global `deltaT`** — no stale copies (this is the chain-dt bug
   class: `chainParams[0]` left at the 1e-4 default while stepping at 1e-5 → 10× too-soft filament, silent).
4. **Every comparison run records its full config to its output** (provenance), so a mismatch is visible *before*
   it costs a run.
5. **Accepted divergences (below) are NOT matched** — known v2 architectural differences, not bugs to chase.

---

## Tier 1 — Universal physical constants — GLOBAL, must match v1, never per-assay
| constant | v1 reference value | v2 sourcing |
|---|---|---|
| `deltaT` | 1e-5 | global authoritative; assays read only |
| viscosity (`aeta`) | 0.1 | global; assays read only |
| temperature | **[CC: extract from v1 ref]** | global; assays read only |
| (any other sim-wide physics constant CC finds) | **[CC: extract]** | global |

## Tier 2 — Model / discretization — must match v1 for a fair comparison
| parameter | v1 reference value | v2 status |
|---|---|---|
| segment length (mono/seg) | **64** | **MUST set 64** — v2 default is 32; this is the recurring miss |
| PAIRS regime | v1 `twoNodeFormin` used hand-tuned **0.0573 / 1.0 / 0.01 / BRotCoeff 0.3** ("straight-filament" override) | **DECISION (jba):** pin the canonical reference to these overrides, or to Env defaults (fracMove 0.5 / fracR 0.1 / fracMoveTorq 0.265 / filTorqSpring 1e-20). *Both sides must use the chosen regime.* |
| filament torsion spring | **[CC: extract]** | match |
| (other discretization params CC finds) | **[CC: extract]** | match |

## Tier 3 — Scenario — must match the specific v1 reference run
| parameter | v1 `twoNodeFormin` value | note |
|---|---|---|
| box size | **[CC: extract]** | |
| myosin count / composition | **[CC: extract — n singlet / n dimer]** | |
| inter-node gap | **[CC: extract]** | (frame analysis put the run near ~1.2 µm scale, ±3× uncertain) |
| filament length / aim | **[CC: extract]** | |
| growth on/off | fixed-length (growth OFF) per frame analysis — **[CC: confirm]** | |
| step count / runtime | **[CC: extract — the analyzed run was ~0.86 s]** | match the runtime |
| seed handling | **[CC: extract]** | match seed(s) for a like-for-like trajectory |

## Accepted divergences — do NOT match (known v2 architecture)
- **SoA one-step-stale force scheme** — a small reproducible residual vs v1's sequential fresh-force update. Not a bug.
- **float32** (vs v1's wider precision in places) — the deliberate GPU tradeoff. Measure, don't chase.
- **Scheme-1 direct load injection** (once adopted) — an authorized physics deviation in node force transmission.
- (segment-count / geometry differences, if any remain after Tier 2 — **[TBD, record here]**)

---

## Pre-run checklist — CC works through this before any v1→v2 comparison run
- [ ] every Tier-1 constant comes from the global source (grep the harness: it assigns *none* of them)
- [ ] every Tier-2 parameter matches the pinned reference — **segment length = 64 explicitly checked**
- [ ] every Tier-3 parameter matches the specific v1 reference run
- [ ] all dt-dependent coefficients derived from global `deltaT` (`chainParams[0] = deltaT`, fracMove-family, …)
- [ ] the run writes its full resolved config to the output (provenance)
- [ ] only the accepted divergences differ; if anything else differs, **stop and report** before running

---

## To complete this contract (CC, one pass, read-only on `BoA-v1ref`)
Extract the authoritative values for every **[CC: extract]** cell above from the `BoA-v1ref` `twoNodeFormin`
reference setup, and propose pinning a **canonical reference config** (recorded, reproducible) so future
comparisons match a fixture rather than a hand-run. Report the filled table; do not edit `BoA-v1ref`.
