# Short/Long Buy Entry Design

## Goal

Add a direct buy-recording action to both recommendation detail views:

- Short-term right-side candidate detail.
- Long-term value candidate detail.

When the user clicks `买入`, the system opens a confirmation dialog for buy price, quantity, and execution time. Only after the user confirms does the app save a `BUY` fill into the existing trade review module.

## Current Context

Trade review already models:

- A server-verified recommendation case.
- One or more fills under that case.
- Ledger calculation and status projection from active fills.

The existing frontend `TradeReviewButton` creates or reuses a trade case from a server attestation token. The `TradeReviewPage` then lets the user add a fill manually. Short-term reports already expose `tradeCaptureTokens`. Long-term market scan reports do not yet expose those tokens, so long-term recommendations need the same attestation support before they can create verified trade cases.

## User Experience

Each detail header gets a primary `买入` button near the existing watchlist/review actions.

Clicking `买入` opens a modal:

- `买入价格`: defaults to the recommendation latest price when available.
- `买入股数`: defaults to `100`.
- `买入时间`: defaults to current Shanghai time.
- Direction is fixed to `BUY`.

The modal must require explicit confirmation. No buy record is written on button click alone.

After saving:

- If no matching trade case exists, create it from the attestation token.
- If a matching trade case already exists, reuse it.
- Add one `BUY` fill through the existing trade fill endpoint.
- Update the shared trade feedback store with the returned detail.
- Keep the user on the current recommendation detail.
- Show a success toast with a `去交易复盘查看` affordance if the existing toast/action pattern supports it; otherwise show a concise success toast and leave the normal trade review navigation available.

## Data Flow

```text
Recommendation detail
  -> Buy button
  -> Buy entry modal
  -> ensure trade case from attestation token
  -> POST /api/trade-cases/{caseId}/fills with BUY request
  -> merge returned TradeCaseDetail into trade feedback store
  -> toast success
```

The fill request uses the current existing contract:

```json
{
  "side": "BUY",
  "executedAt": "ISO timestamp",
  "price": 10.25,
  "quantity": 100
}
```

## Long-Term Attestation

Long-term `MarketScanReport` must gain:

```text
tradeCaptureTokens: Record<string, string>
```

The backend should issue tokens for each long-term candidate using the same `RecommendationAttestationService` pattern already used by short-term, tech tracking, mispricing, cycle, and daily signal modules.

Token source metadata should identify long-term recommendations consistently:

- `sourceModule`: `LONG_TERM`
- `ruleVersion`: use the market scan rule or strategy version currently available in the backend. If no explicit version exists, introduce a stable constant such as `long-term-value-v1`.
- `recommendedAt`: the same timestamp used by the report for the candidate/recommendation snapshot.
- `recommendedPrice`: candidate latest price.
- `recommendationAction`: candidate today advice action or screening action label, following existing attestation conventions.

If a token cannot be issued because price or timestamp is missing, the buy button must be disabled with a clear reason.

## Shared Frontend Component

Create a reusable buy-entry component instead of duplicating logic in pages.

Responsibilities:

- Display `买入` button.
- Disable itself when attestation token or recommendation time is unavailable.
- Open the modal with defaults.
- Validate positive price and positive integer quantity before calling the API.
- Convert the local Shanghai datetime input to an ISO timestamp using the existing trade review helpers.
- Ensure/reuse the trade case.
- Add the BUY fill.
- Merge the returned case detail into `useTradeFeedbackStore`.
- Surface API validation errors without swallowing field-level messages.

The existing `TradeReviewButton` remains available for "加入复盘" behavior. The new component records an actual buy fill and should not replace the existing button unless the page design becomes crowded. In the detail header, both can appear: `加入复盘` for watch-only tracking and `买入` for confirmed execution.

## Error Handling

The buy modal should handle these cases:

- Missing attestation token: button disabled, tooltip says recommendation lacks verified price/time.
- Missing recommendation time: button disabled.
- Invalid price, quantity, or datetime: inline validation before network calls.
- Case creation failure: show toast/error and keep modal open.
- Fill save failure: show toast/error and keep modal open with user input preserved.
- Duplicate clicks: disable save while the request is in flight.

Backend validation remains authoritative.

## Scope

In scope:

- Short-term detail buy button and modal.
- Long-term detail buy button and modal.
- Long-term report trade capture tokens.
- Shared frontend buy-entry component.
- Unit tests for frontend behavior.
- Backend tests for long-term token generation.

Out of scope:

- Sell shortcut from recommendation pages.
- Broker order placement.
- Automatic buy without user confirmation.
- Position sizing recommendations beyond the default quantity field.
- Changing trade review ledger semantics.

## Tests

Backend:

- `MarketScanServiceTest` or equivalent verifies long-term reports include trade capture tokens for eligible candidates.
- Token omission is covered when candidate price/time is not usable.

Frontend:

- Shared buy-entry component opens the modal with default price, quantity, and time.
- Saving creates/reuses a trade case, posts a `BUY` fill, and merges the returned detail.
- Short-term detail renders the buy button with the short-term token.
- Long-term detail renders the buy button with the long-term token.
- Missing token disables buy and explains why.

Regression:

- Existing trade review add/edit/delete fill behavior remains unchanged.
- Existing `加入复盘` button behavior remains unchanged.

## Acceptance Criteria

- A user can open a short-term candidate detail, click `买入`, confirm price/quantity/time, and see the trade review case move to holding after the BUY fill is saved.
- A user can do the same from a long-term candidate detail.
- The buy action is not persisted until the modal is confirmed.
- Long-term recommendations use server-issued attestation tokens rather than client-fabricated recommendation facts.
- All new behavior is covered by tests and existing trade review tests still pass.
