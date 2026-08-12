# Short-Term Chip UI Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all chip-distribution presentation from the short-term page while keeping backend and API compatibility intact.

**Architecture:** Treat chip data as an accepted but unrendered response field. Remove the candidate-row tags, detail section, and now-unreferenced chart component without changing short-term scoring, persistence, DTOs, or strategy versions.

**Tech Stack:** React 18, TypeScript 5, Vitest, Vite.

## Global Constraints

- Keep `ShortTermCandidate.chip` and chip score fields in frontend response types.
- Do not change backend code, persisted reports, strategy versions, ranking, or recommendation actions.
- Render no chip label, placeholder, verification state, chart, cost distribution, or missing-data message.

---

### Task 1: Remove Short-Term Chip Presentation

**Files:**
- Modify: `apps/web-react/src/pages/ShortTermPage.test.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Delete: `apps/web-react/src/components/shortterm/ChipDistributionChart.tsx`
- Delete: `apps/web-react/src/components/shortterm/ChipDistributionChart.test.tsx`

**Interfaces:**
- Consumes: Existing `ShortTermCandidate` responses, including optional `chip` data.
- Produces: A short-term page that ignores chip data and renders all other candidate information unchanged.

- [x] **Step 1: Write the failing page test**

Replace the chip presentation assertions with a test that supplies a populated
`candidate.chip`, renders the row, opens the detail overlay, and asserts that
the text does not contain `筹码`, `排序贡献 21.50`, `距成本 +3.17%`,
`主筹码峰`, or `最近上方筹码区`.

- [x] **Step 2: Run the focused test to verify it fails**

Run:

```bash
npm --prefix apps/web-react test -- ShortTermPage.test.tsx -t "does not render chip data"
```

Expected: FAIL because the current row and detail overlay still render chip diagnostics.

- [x] **Step 3: Remove the presentation code**

In `ShortTermPage.tsx`:

- remove the `ChipDistributionChart` import;
- remove chip-only type imports;
- remove `<ChipSummaryTags chip={candidate.chip} />`;
- remove `<ChipStructurePanel candidate={candidate} />`;
- remove `ChipSummaryTags`, `ChipStructurePanel`, and `chipVerificationTone`.

Delete the chart component and its isolated component test after it has no remaining imports.

- [x] **Step 4: Run focused tests to verify they pass**

Run:

```bash
npm --prefix apps/web-react test -- ShortTermPage.test.tsx
```

Expected: PASS with no chip text rendered by the short-term page.

- [x] **Step 5: Run production build and source scan**

Run:

```bash
npm --prefix apps/web-react run build
rg -n "筹码|ChipDistributionChart|ChipSummaryTags|ChipStructurePanel" apps/web-react/src --glob '!types.ts'
```

Expected: build succeeds; the source scan returns no short-term presentation references.

- [x] **Step 6: Commit**

```bash
git add docs/superpowers/specs/2026-08-12-short-term-chip-ui-removal-design.md \
  docs/superpowers/plans/2026-08-12-short-term-chip-ui-removal.md \
  apps/web-react/src/pages/ShortTermPage.tsx \
  apps/web-react/src/pages/ShortTermPage.test.tsx \
  apps/web-react/src/components/shortterm/ChipDistributionChart.tsx \
  apps/web-react/src/components/shortterm/ChipDistributionChart.test.tsx
git commit -m "refactor: remove short-term chip presentation"
```
