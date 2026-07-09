# removeMe/ — retired dead-end drivers (DEAD; jba deletes manually)

Relocated here from the repo root in the base-directory reorg (2026-07-08). These drivers exercise
**flags that were DELETED in the canonical collapse (Stage 2)** and therefore **cannot run against
current code**. Each backs a **dropped or failed-lever** investigation whose *conclusion* is preserved
in the findings docs — the driver itself is detritus.

**Safe to delete.** Nothing here is a live reproduce-path for a kept result. Everything is recoverable
from git history / the tag **`pre-canonical-collapse-2026-07-08`** if ever needed. This directory is
committed (tracked) only so it survives until you delete it manually.

## Contents (driver → removed flag → dropped/failed finding)
- **Noise-correction family (dropped — nets ~0 at production dt; the measured GPU effect was the
  basin-flip artifact):**
  - `run_allnoise_ab.sh`, `run_allnoise_ident.sh`, `run_allnoise_parity.sh`, `run_allnoise_step2cpu.sh`
    — `-allnoise` → `CONSTRAINED_VARIANCE_PROBE.md`
  - `run_bondnoise_ab.sh` — `-bondnoise` → `CONSTRAINED_VARIANCE_PROBE.md`
  - `run_thermcorr_ab.sh`, `run_thermcorr_dtvanish.sh`, `run_thermcorr_dtvanish_full.sh` — `-thermcorr`
  - `run_syswide_step2.sh`, `run_syswide_step3.sh` — `-syswide`/`-syswiderb`
  - `run_overshoot_A.sh` — `-thermcorr`/`-uninoise`/`-ratefix` (overshoot diagnosis, thermostat arc)
- **Failed integrator levers (superseded by the kept `-xbimplicit2`/`-segimplicit`):**
  - `run_xbdash.sh` — `-xbdash` (Kelvin-Voigt dashpot) → `CROSSBRIDGE_DASHPOT_FINDINGS.md`
  - `run_xbsat.sh` — `-xbsat` (saturating spring) → `SATURATED_CROSSBRIDGE_DIAGNOSTIC_FINDINGS.md`
  - `run_xbimplicit.sh`, `run_xbimplicit_gliding.sh`, `run_xbimpl_bracket.sh` — `-xbimplicit` (head-only)
    → `IMPLICIT_CROSSBRIDGE_FINDINGS.md`
