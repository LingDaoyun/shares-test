import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { shouldApplySelectedCaseOperation } from '../src/lib/tradeReview.ts'

const pageSource = await readFile(new URL('../src/pages/TradeReviewPage.tsx', import.meta.url), 'utf8')

assert.equal(
  shouldApplySelectedCaseOperation({ id: 7, caseId: 'case-a' }, 7, 'case-b'),
  false,
  'a completed operation for case A must not update visible detail state after case B is selected'
)

assert.equal(
  shouldApplySelectedCaseOperation({ id: 7, caseId: 'case-a' }, 7, 'case-a'),
  true,
  'the current operation for the selected case must be allowed to update visible detail state'
)

assert.equal(
  shouldApplySelectedCaseOperation({ id: 7, caseId: 'case-a' }, 8, 'case-a'),
  false,
  'a superseded operation must not update visible detail state'
)

assert.match(
  pageSource,
  /useLayoutEffect\(\(\) => \{\s*selectedIdRef\.current = selectedId/,
  'selected case identity must synchronize in a layout effect, before passive effects can observe stale selection'
)

assert.match(
  pageSource,
  /const selectCase = useCallback\(\(caseId: string\) => \{\s*selectedIdRef\.current = caseId\s*setSelectedId\(caseId\)/,
  'explicit case selection must update the operation guard before scheduling React state'
)
