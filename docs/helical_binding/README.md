# docs/helical_binding

The **actin azimuth / helical-binding / filament-twirling audit**, consolidated into a maintainable structure.

## Contents

- **`ACTIN_HELICAL_BINDING_AUDIT.md`** — the **authoritative current report**. It supersedes the five original
  audit components and is the single Markdown source of truth for this subject. Update *this* report as
  understanding changes.
- **`ACTIN_HELICAL_BINDING_CODE_MAP.csv`** — the **maintained structured reference**: every relevant file /
  line-range / lineage / data-ownership / read-write / active-vs-default-off / canonical-vs-noncanonical entry.
  Keep file/line detail here, not in prose.
- **`archive/audit_2026-07-23/`** — the **original audit components, retained for provenance only** (superseded;
  not current, not authoritative):
  - `ACTIN_AZIMUTH_AND_HELICAL_SITE_AUDIT.md`
  - `ACTIN_BINDING_PATH_TRACE.md`
  - `TWIRLING_DOF_AND_OBSERVABLE_AUDIT.md`
  - `ACTIN_AZIMUTH_GIT_HISTORY.md`
  - `EXISTING_HELICAL_WORK_VERDICT.md`

**Future work:** implementation findings should **update the authoritative report** (`ACTIN_HELICAL_BINDING_AUDIT.md`)
or create a **clearly distinct implementation/result document** with its own independently-maintained purpose —
**not** another parallel summary of the same audit.

## Documentation policy

- **One authoritative Markdown report per bounded audit or implementation task.**
- **Structured file/line mappings belong in CSV** (not scattered through prose).
- **Raw logs and generated data belong under run directories** (`RUN_LOGS/…`), not in `docs/`.
- **Additional Markdown files require an independently maintained purpose** — do not add a Markdown file that
  merely restates an existing report.
- **When a new report supersedes an older one, move the older report into a dated archive subfolder** (e.g.
  `archive/audit_YYYY-MM-DD/`) **and state the supersession explicitly** in the new authoritative report.
- **Do not create separate Markdown files merely because different investigative subtasks were run in parallel**
  — consolidate them into the one authoritative report (with details in the CSV).
