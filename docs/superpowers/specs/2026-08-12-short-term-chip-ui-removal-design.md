# Short-Term Chip UI Removal Design

## Goal

Remove the chip-distribution indicator from the short-term user interface while
its external source is unavailable. Users must no longer see chip verification,
estimated cost distribution, missing-data notices, or chip charts in either the
candidate list or candidate detail overlay.

## Selected Scope

This is a presentation-only removal:

- remove chip summary tags from short-term candidate rows;
- remove the chip structure and verification section from candidate details;
- remove the now-unused chip distribution chart component and its component test;
- keep API response types, backend calculations, persisted historical reports,
  strategy versions, and ranking fields compatible.

The frontend continues to accept responses containing `candidate.chip` and chip
score fields, but it does not render them. This preserves historical report
deserialization and allows a future data-source replacement without an API
migration.

## Non-Goals

- Do not change short-term eligibility, ranking, or action decisions.
- Do not remove backend chip calculations or database fields.
- Do not rewrite archived strategy versions.
- Do not replace chip evidence with a fabricated fallback.

## Verification

- A candidate containing a complete chip snapshot renders no chip-related text
  in either the row or detail overlay.
- A legacy candidate without chip data also renders no chip placeholder.
- The short-term page test suite passes.
- The React production build passes with no unused imports or type errors.
