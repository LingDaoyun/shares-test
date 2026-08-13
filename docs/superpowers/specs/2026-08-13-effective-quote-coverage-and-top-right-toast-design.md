# Effective Quote Coverage and Top-Right Toast Design

## Goal

Stop manual short-term scans from being permanently blocked by an impossible
market-coverage denominator while preserving the V4 95% data-quality gate.
Manual-scan lifecycle notifications must also appear in the top-right corner,
where the user expects transient system feedback.

## Confirmed Root Cause

The EastMoney A-share list reports 5,895 rows. A live read on 2026-08-13
confirmed that 355 of those rows have no usable current price and include
retired, suspended, pending-listing, and non-tradable transfer records. They
cannot participate in a current short-term decision.

The current implementation nevertheless uses all 5,895 rows as the effective
quote denominator. It also paginates by live turnover (`f6`), so the ordering
can change between page requests. The reproduced scan fetched 5,533 usable
quotes and reported `5533 / 5895 = 93.86%`, which made the 95% gate impossible
to pass even when the live market data was effectively complete.

A read-only control request sorted by security code (`f12`) returned all 5,895
rows with 5,895 unique symbols, 5,540 usable prices, 355 zero or invalid
prices, and no duplicates. This validates both parts of the repair: stable
pagination and an effective-current-quote denominator.

## Selected Approach

Keep one fail-closed workflow with two independent quality checks:

1. **Raw transport completeness:** the provider-reported universe must be
   fetched completely and uniquely when a full-market decision is requested.
2. **Effective quote coverage:** the 95% ratio is calculated only over rows
   that could have a current market price. Known rows with no usable current
   price are excluded from this denominator.

The 95% threshold remains exactly `0.95`. Missing raw rows are never assumed to
be non-tradable: until fetched, each missing row remains in the effective
denominator and raw transport incompleteness independently keeps execution
blocked.

Alternatives were rejected as follows:

- lowering the threshold would hide the denominator error and weaken V4;
- introducing a second quote provider or a security-master service would add
  operational complexity that is unnecessary for this confirmed defect;
- retrying the current turnover-sorted pages would still permit order drift
  and would not fix the 355 non-current rows in the denominator.

## Quote Acquisition

`EastMoneyClient` continues using the existing A-share market filter and a
maximum page size of 100. Quote-list pagination changes from turnover sort
`fid=f6` to security-code sort `fid=f12`, retaining one deterministic direction
for every page in a snapshot.

The raw `AshareQuoteSnapshot` contract keeps its present meaning:

- `expectedCount`: provider-reported raw row count;
- `fetchedCount`: unique raw rows fetched;
- `missingCount`: raw expected minus unique raw fetched;
- `complete`: true only when the provider reported a positive, consistent
  total and every raw row was fetched uniquely.

The paginator must not stop merely because one page contributes no new symbol
while more provider rows remain. A repeated/no-progress page is an incomplete
raw snapshot, not successful completion. Existing request limits and failure
handling remain fail-closed.

## Effective Coverage Semantics

`ShortTermService` derives the decision coverage from the raw snapshot:

- `excludedNoPriceCount` is the number of fetched raw rows whose parsed latest
  price is null, zero, negative, or otherwise outside the existing usable-price
  bounds;
- `effectiveExpectedCount` is
  `rawExpectedCount - excludedNoPriceCount`;
- `effectiveFetchedCount` is the number of unique, usable A-share context
  quotes that satisfy the existing point-in-time checks at the decision time;
- `effectiveMissingCount` is
  `max(0, effectiveExpectedCount - effectiveFetchedCount)`;
- `coverageRatio` is
  `effectiveFetchedCount / effectiveExpectedCount`, rounded with the existing
  four-decimal policy.

Because only known no-price rows are subtracted, raw rows that were not fetched
remain in `effectiveExpectedCount`. A malformed row with a usable price is also
not silently excluded: it remains in the effective denominator but cannot
enter the numerator unless it passes the existing A-share context and
point-in-time rules.

`ShortTermCoverageSnapshot` exposes additive audit fields alongside its current
effective fields:

- `rawExpectedCount`;
- `rawFetchedCount`;
- `excludedNoPriceCount`;
- `rawComplete`.

The existing `expectedCount`, `fetchedCount`, `missingCount`, and
`coverageRatio` fields become the effective-current-quote measure. Existing
Java callers that construct the seven-field record keep a compatibility
constructor; production report construction supplies all audit fields
explicitly. The JSON change is additive and requires no database migration.

For the reproduced complete snapshot, the expected presentation is:

- raw acquisition: `5895 / 5895`, complete;
- excluded without usable current price: `355`;
- effective quote coverage: `5540 / 5540 = 100.00%`.

## Reliability and Blocking Reasons

Execution remains reliable only when all existing requirements pass, including
source identity, freshness, requested full universe, count consistency,
point-in-time semantics, and (for full-market execution) raw completeness.

The final-result gate reports distinct failures:

- `COVERAGE_BELOW_95` only when the effective ratio is absent or below 95%;
  its message includes the actual percentage and counts, for example
  `全市场有效行情覆盖率 93.86%（5533/5895）低于95%`;
- `QUOTE_UNIVERSE_INCOMPLETE` when the effective ratio is at least 95% but the
  raw snapshot is incomplete; its message includes raw fetched and expected
  counts;
- `QUOTE_COVERAGE_UNRELIABLE` when the ratio and raw completeness pass but
  another coverage reliability condition fails.

Existing downstream failures such as missing cutoff, wrong trade date, future
cutoff, and stale quotes retain their present codes. Manual and scheduled final
result evaluation share the same fail-closed semantics.

The report note clearly distinguishes the two measures. A successful example
reads conceptually as `有效行情覆盖 5540/5540；行情源原始抓取 5895/5895；无有效现价排除 355`.
This information stays in the durable report; the floating notification uses
the terminal server message and reason code without duplicating the full
diagnostic payload.

## Top-Right Notification Placement

The existing global `ToastViewport` remains the only notification surface.
Its fixed position changes from bottom-right to top-right:

- desktop offset: `top-6 right-6`;
- notification width, stacking gap, z-index, keyed replacement, persistence,
  close behavior, live-region semantics, and animation remain unchanged;
- notifications stack downward from the top-right;
- the removed manual-scan status card is not restored.

This positioning applies to all global Toast messages so the application has
one predictable notification location rather than a manual-scan exception.

## Error Handling and Compatibility

- Quote-source request failures and inconsistent provider totals continue to
  fail closed.
- Raw page loss or duplicate symbols cannot be converted into apparent 100%
  effective coverage.
- Zero-price rows are excluded only after they are actually fetched and parsed.
- Candidate ranking, scan thresholds, polling intervals, scheduled scan
  control, database records, and model configuration are unchanged.
- The K-line endpoint's occasional SSL failures are a separate latency and
  technical-review issue and are outside this defect repair.
- No external model is invoked during implementation or acceptance.

## Testing

Backend tests must prove:

- quote-list requests use `fid=f12`;
- a stable multi-page snapshot preserves every unique raw symbol;
- complete raw input with 355 no-price rows produces an effective denominator
  that excludes exactly those 355 rows and can pass the unchanged 95% gate;
- missing raw rows remain conservatively represented and raw incompleteness
  blocks execution even if the effective ratio is at least 95%;
- malformed or future-dated usable-price rows remain coverage misses;
- a genuine effective ratio below 95% returns `COVERAGE_BELOW_95` with actual
  percentage and counts;
- raw incompleteness and other reliability failures return their distinct
  reason codes;
- the existing exact 95% boundary still passes.

Frontend tests must prove:

- `ToastViewport` contains `top-6` and `right-6` and no longer contains
  `bottom-6`;
- persistent manual-scan progress and terminal messages retain the keyed
  replacement and close behavior already specified;
- the manual status card remains absent.

## Acceptance

Before release:

1. focused backend and frontend tests pass;
2. the complete API test suite passes;
3. the complete React test suite and production build pass;
4. the Vue production build passes because it shares the repository release;
5. a live manual scan fetches a raw-complete stable universe, reports the raw
   and effective counts, and does not emit `COVERAGE_BELOW_95` solely because
   of known no-price securities;
6. a deliberately incomplete or below-95% fixture remains blocked;
7. the progress and terminal notification are visibly positioned in the
   top-right and remain dismissible;
8. deployment preserves the existing database volume and unrelated services.
