# Trade Review Realized P&L Design

## Goal

Turn trade review from a recommendation bookmark into a practical execution ledger. A user can open a short-term or long-term recommendation, record a real buy immediately, add later partial buys and sells, and see fee-adjusted results.

The account overview displays both open and closed cases, but aggregate profit and return include closed cases only. Open positions never contribute estimated or unrealized profit to the account result.

## Confirmed Accounting Rules

- Every buy and sell records execution time, unit price, quantity, source recommendation, and fees.
- Buy time defaults to the current Shanghai time; the latest/recommended price may prefill the price but remains editable.
- Partial buys and partial sells remain supported by the existing fill ledger.
- Commission is charged on both sides, stamp duty on sells only, and transfer fee on both sides.
- Commission rate, minimum commission, stamp-duty rate, and transfer-fee rate are configurable. Minimum commission accepts `0` for a no-minimum-commission channel.
- Fees are calculated and frozen when a fill is created. A later global settings change does not rewrite historical fills.
- Editing an existing fill reuses that fill's frozen fee policy and recalculates fee amounts from the corrected price and quantity.
- Per-case realized profit is net of all applicable buy and sell fees.
- A case contributes to the account overview only when its position is zero and it has at least one buy and one sell. `PLANNED`, `HOLDING`, and `CANCELLED` cases are excluded from aggregate profit and return.
- Open cases remain visible with position, average cost, invested amount, and completed partial-sale information. They show no unrealized or total-profit estimate.
- Account realized return equals aggregate net realized profit divided by aggregate closed-case buy cash outflow, including buy fees.

## Scope

### Included

- Direct buy entry from short-term and long-term recommendation detail overlays.
- Configurable fee policy and persisted fee snapshots per fill.
- Net realized ledger calculations for partial fills.
- Closed-only account overview on the trade-review page.
- Clear source labels for `短线推荐` and `长期价投`.
- Existing fill editing, deletion audit trail, pagination, and outcome feedback compatibility.

### Not Included

- Broker account synchronization or automatic order placement.
- Lot-level FIFO tax accounting. The existing weighted-average position model remains authoritative.
- Unrealized P&L in account totals.
- Cash balance, deposits, withdrawals, dividends, financing interest, or corporate actions.
- Automatic fee-policy history lookup by broker or execution date.

## Architecture

### 1. Fee policy

Add a singleton `trade_fee_settings` record and `TradeFeeSettingsService` with `GET` and `PUT` endpoints. The service validates non-negative values and exposes a `TradeFeePolicy` value object to fill creation.

Default values are operational defaults, not claims about every broker:

- commission rate: `0.025%`
- minimum commission: `5.00`
- stamp-duty rate: `0.05%`
- transfer-fee rate: `0.001%`

The UI explains that setting minimum commission to `0` enables no-minimum-commission accounting. Rates are entered as percentages and converted to decimal fractions exactly once in the backend.

### 2. Immutable fill fee snapshot

Extend active fills and fill revisions with:

- `gross_amount`
- `commission_rate`
- `minimum_commission`
- `stamp_duty_rate`
- `transfer_fee_rate`
- `commission_fee`
- `stamp_duty_fee`
- `transfer_fee`
- `total_fee`

The fee calculator uses `price * quantity` as gross amount. Each fee component is rounded to cents with `HALF_UP`; total fee is the sum of rounded components. Commission uses `max(gross * commissionRate, minimumCommission)` when the rate is positive. Stamp duty is zero for buys. Transfer fee applies to both directions.

Existing rows are backfilled with a zero-fee snapshot so historical gross results do not silently change during migration. New and corrected fills always carry a complete policy snapshot.

### 3. Net ledger

`TradeLedgerCalculator` continues to order fills by execution and creation time and maintains a weighted-average position. The weighted buy cost includes buy fees. A sell realizes:

`net sell proceeds - allocated fee-inclusive buy cost`

Sell fees are deducted from proceeds. The ledger exposes:

- open quantity
- fee-inclusive average cost per share
- open invested cost
- cumulative fees
- net realized profit
- closed buy cash outflow
- realized return when the case is closed
- `aggregateEligible`

For API compatibility, `unrealizedProfit` remains present but is always `null`. `totalProfit` equals net realized profit only when the case is closed and is `null` otherwise. Market outcome snapshots remain a separate strategy-evaluation feature and must not be confused with account profit.

### 4. Direct recommendation entry

Replace the current icon-only bookmark behavior on the short-term and long-term detail overlays with an explicit `录入买入` command. It opens a focused dialog containing:

- stock and recommendation source, read-only
- buy time, default current time
- buy price, prefilled from current recommendation data
- quantity, required positive integer
- calculated fee preview and net cash outflow

Submission uses one transactional endpoint that verifies the signed recommendation attestation, idempotently creates or finds the trade case, and appends the buy fill. A failed fill must not leave a new empty `PLANNED` case.

Short-term scheduled recommendations already receive fresh attestations when read. Long-term market-scan responses will gain server-issued attestation tokens and a `LONG_TERM_VALUE` recommendation source. The browser never constructs trusted recommendation facts itself.

After a successful entry, the dialog closes, the shared trade-feedback store updates, and the user can open the trade-review page for later partial fills or sells.

### 5. Account overview

Add a backend overview response calculated from all eligible cases, independent of list pagination:

- total case count
- holding case count
- closed case count
- aggregate-eligible closed case count
- aggregate closed buy cash outflow
- aggregate net realized profit
- aggregate realized return
- aggregate fees

The trade-review page presents this overview before the table. The table keeps all statuses visible and replaces `累计毛收益` with unambiguous fields:

- closed case: `净已实现收益` and `收益率`
- holding case: `持仓成本` and `未清仓，不计总览`
- planned/cancelled case: no profit value

No latest-price request is required to compute the account overview.

## Data Integrity and Concurrency

- Case creation plus the first buy fill runs in one transaction.
- Existing recommendation fingerprint uniqueness remains the idempotency boundary.
- Fill writes keep the existing case row lock and revision sequence rules.
- The fee settings row uses optimistic versioning or an update timestamp to avoid silent lost updates.
- Overselling remains rejected.
- A case that returns to zero becomes `CLOSED`; a later buy reopens it as `HOLDING` and immediately removes it from aggregate eligibility until it is flat again.
- Deleting or correcting a fill recalculates the case status and account overview from projected active fills.

## API Contract

Add:

- `GET /api/trade-fee-settings`
- `PUT /api/trade-fee-settings`
- `POST /api/trade-cases/entries`
- `GET /api/trade-cases/overview`

Extend fill and ledger views with fee and net-result fields. Existing case and fill endpoints remain available for later partial operations.

Long-term market-scan reports add a token map keyed by symbol, matching the established recommendation-attestation pattern used by other modules.

## User Interface

- The buy-entry dialog is keyboard accessible, closes on backdrop click and Escape, and nests safely above the stock detail overlay.
- Numeric controls have stable widths and inline validation; stock facts and source are not editable.
- Fee settings live on the trade-review page in a compact settings dialog, not in the recommendation workflow.
- The fee preview labels the applied minimum commission so a `5.00` versus `0.00` difference is visible before saving.
- Profit uses red for positive and green for negative, consistent with the existing A-share UI.
- Open positions never show an implied current profit, even when the system has a latest quote for strategy outcome evaluation.

## Failure Handling

- Missing or expired recommendation attestation disables direct entry and explains that the recommendation must be refreshed.
- Invalid fee settings return field-level validation errors and do not partially update the policy.
- Direct-entry failures preserve the dialog input for retry.
- If fee calculation cannot complete, the fill is rejected rather than stored with missing fee fields.
- Account overview calculation failures do not replace results with zero; the page shows a load error while retaining the case list.

## Test Strategy

### Backend

- Fee calculation for buy/sell, minimum `5`, minimum `0`, zero rate, component rounding, and large orders.
- Fee policy update validation and historical fill immutability.
- Weighted-average cost with multiple buys, partial sells, and different per-fill policies.
- Closed-only aggregate profit and denominator; holding cases contribute zero to account aggregates.
- Reopened closed cases leave the aggregate until flat again.
- Atomic direct entry, duplicate recommendation handling, expired token, and rollback on invalid fill.
- Long-term market-scan attestation ownership.
- Fill correction/deletion audit projection preserves fee snapshots and recalculates status.

### Frontend

- Short-term and long-term detail overlays open the buy dialog with correct source and default values.
- Fee preview distinguishes minimum `5` from `0`.
- Successful entry updates the store; failed entry retains inputs.
- Account overview uses the dedicated endpoint and labels open positions as excluded.
- Settings validation, save, reload, backdrop, Escape, focus return, and mobile layout.

### End-to-End

1. Open a short-term recommendation and record a partial buy.
2. Add another buy and partial sell in trade review.
3. Confirm the holding case remains outside aggregate profit.
4. Sell the remaining quantity and confirm net profit, fees, and return enter the overview.
5. Change minimum commission to `0`, add a new trade, and confirm old fees remain unchanged.

## Success Criteria

- A real buy can be recorded directly from both target recommendation modules.
- Every new fill has an auditable frozen fee snapshot.
- `0` minimum commission is accepted and reflected in previews and persisted results.
- Open positions are visible but never included in aggregate profit or return.
- Closed results are net of configured fees and reproducible from stored fills.
- Existing partial-fill and audit behavior remains intact.
