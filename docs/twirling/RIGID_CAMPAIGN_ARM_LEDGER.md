# Rigid skew-torque re-validation — arm ledger

Campaign commit `4a38db7` (simulation code identical to `30e8891`; the only delta is derived-CSV
naming/atomicity, which no arm executes). Every arm: 10 µM ATP, 400 heads/µm², η = 0.01 Pa·s,
`filSegs=1`, FDT thermostat, rigor rupture OFF, `-rot-decomp`, GPU device-resident, monitored wrapper,
dt = 2.5e-7 s, 25 % equilibration.

States: `planned` · `running` · `complete` · `failed` · `rerun` · `reused`

## Pre-run sanity

| arm | record | state |
|---|---|---|
| 15° sanity +ε | `rigid_thfdt_u0010.00_e0150_r0400.0_d00010000_p_101` | **complete** |
| 15° sanity −ε | `rigid_thfdt_u0010.00_e0150_r0400.0_d00010000_n_101` | **complete** |

## Phase 1 — ±15° anchor, 100 ms/arm

| seed | +ε | −ε | state |
|---|---|---|---|
| 101 | `..._e0150_..._d00100000_p_101` | `..._n_101` | **complete** |
| 102 | `..._p_102` (**rerun** — 1st attempt killed at 87 783/400 000 steps when concurrency was reverted; no partial record written) | `..._n_102` | **complete** |
| 103 | `..._p_103` | `..._n_103` | **complete** |
| 104 | `..._p_104` | `..._n_104` | **complete** |
| 105–108 | (borderline extension) | | **not triggered** — A15 passed at |m|/SEM 2.32 with stable leave-one-out |

**Phase 1 closed: A15 PASS.** 8/8 arms complete, 0 failed, 0 invalid, 0 solver failures, 3.43 h GPU wall.

## Phase 2 — ±5° high-seed, 100 ms/arm

| seed | +ε | −ε | state |
|---|---|---|---|
| 101 | `..._e0050_..._d00100000_p_101` | `..._n_101` | reused (pre-existing, commit `30e8891`) |
| 102 | `..._p_102` | `..._n_102` | reused (pre-existing, commit `30e8891`) |
| 103–104 | `..._e0050_..._d00100000_p_103/104` | `..._n_103/104` | **complete** (batch 1) |
| 105–106 | | | **complete** (batch 2) |
| 107–108 | | | **complete** (batch 3) — n=8 evaluated, early success NOT met |
| 109–110 | | | **complete** (batch 4) — n=10 evaluated, early success NOT met |
| 111–112 | | | **complete** (batch 5) |
| 113–116 | | | **not run** — extension authorized only for L5-SUGGESTIVE / L5-UNDERPOWERED; result is L5-PRESENT |

**Phase 2 closed: L5-PRESENT at n = 12.** 20 new arms + 4 reused, 0 failed, 0 invalid, 0 solver failures,
7.81 h GPU wall.

## Campaign totals

| block | arms newly run | reused | GPU wall |
|---|---|---|---|
| 15° sanity (10 ms) | 2 | 0 | 0.09 h |
| 15° anchor (100 ms) | 8 | 0 | 3.43 h |
| 5° high-seed (100 ms) | 20 | 4 | 7.81 h |
| **total** | **30** | **4** | **11.33 h** (cap 20 h; hard arm max 44, used 30) |
