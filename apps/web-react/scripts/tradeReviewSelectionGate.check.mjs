import assert from 'node:assert/strict'
import { shouldApplySelectedCaseOperation } from '../src/lib/tradeReview.ts'

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
