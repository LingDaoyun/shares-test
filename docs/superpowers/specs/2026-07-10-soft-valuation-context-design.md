# Soft Valuation Context Strategy Design

**Status:** Approved direction on 2026-07-10

**Scope:** Amend the long-value and short-right-side parts of
`2026-07-10-explainable-multifactor-point-in-time-validation-design.md`.
This document does not weaken common tradability, evidence, liquidity, audit,
or risk gates.

## 1. Problem

The current implementation gives PE and PB more influence than their labels
suggest:

- the short-term pre-ranking score assigns 30% to valuation before K-line data
  is inspected;
- the universal long-value score assigns 25% to valuation, while its
  "financial" proxy also rewards PE and PB, so valuation is counted twice;
- the long-value screening action requires both PE and PB to be below fixed
  ceilings;
- the short-term pre-filter removes a stock when both ratios exceed fixed
  multiples of user thresholds.

These rules can miss profitable growth companies and cyclical companies whose
current earnings are temporarily depressed. They also give a low PE too much
credit even when it reflects peak-cycle earnings or a value trap.

## 2. Alternatives Considered

### A. Raise the fixed ceilings

This is the smallest code change, but it only moves the cliff. A stock at 119
times earnings passes while one at 121 times fails, even if their quality and
growth evidence are otherwise identical. Rejected.

### B. Remove valuation completely

This prevents PE/PB false negatives, but removes price discipline and makes it
harder to identify expectations that already require an unusually optimistic
future. Rejected.

### C. Use valuation as a soft, strategy-aware context

PE/PB do not determine eligibility or directly authorize a buy. They become a
small explanatory factor, interpreted relative to industry, history, growth,
cash flow, and cycle position. Extreme values produce warnings and scenario
requirements, not automatic exclusion. Selected.

## 3. Common Contract

Introduce a strategy-neutral valuation context:

```text
ValuationContext
  score                 0-100 contextual score, never a probability
  state                 CHEAP | FAIR | STRETCHED | DISTORTED | MISSING
  applicableModel       STANDARD | FINANCIAL | CYCLICAL | EARLY_GROWTH
  rawPe                 signed provider value; negative values are preserved
  rawPb                 signed provider value; invalid equity is explained
  peReference
  pbReference
  industryPercentile
  historyPercentile
  normalizedEarningsUsed
  warnings[]
  evidence[]
```

`maxPe` and `maxPb` remain temporarily in compatible APIs, but are renamed in
the UI and explanations to **PE reference band** and **PB reference band**.
Crossing a band changes the context score and warning text only.

Raw provider values must be retained before positive-value normalization.
`DISTORTED` and `MISSING` are different states: a negative PE is evidence of
current losses, while a missing PE means the source did not provide a usable
value. The implementation must not collapse both cases through a
`firstPositive` helper.

The only valuation-related hard block is not a high multiple. It is an
unresolved data integrity problem, such as a contradictory share count,
negative book equity used in a PB comparison without explanation, or missing
financial evidence for an attempted buy action.

## 4. Long-Value V2 Amendment

### 4.1 Eligibility

PE and PB must not exclude a stock from long-term research. A negative PE means
"current earnings are negative or the ratio is not meaningful" rather than
"the company has no long-term value".

The core long-value pool still requires verifiable business quality and risk
evidence before a buy-like action. Companies with depressed or negative
current earnings enter one of these research states:

```text
NORMALIZED_CYCLE_RESEARCH  mature cyclical business; normalize a full cycle
TURNAROUND_RESEARCH        company-specific recovery is required
EARLY_GROWTH_RESEARCH      current earnings do not represent mature economics
```

### 4.2 Initial weights

| Factor family | Weight | Notes |
|---|---:|---|
| Financial quality and cash conversion | 30% | ROIC/ROE stability, OCF/net income, margins, accruals |
| Durable growth | 20% | revenue, normalized profit and per-share cash-flow growth |
| Competitive position | 15% | unit cost, share, product mix, R&D and execution evidence |
| Balance sheet and capital allocation | 15% | leverage, interest cover, dilution, dividends and reinvestment |
| Valuation context | 10% | industry/history percentiles and scenario valuation; no fixed cliff |
| Catalyst and timing | 10% | cycle, policy, capacity, order or price evidence |

Valuation is therefore not duplicated inside financial quality.

### 4.3 Cyclical companies

For agriculture, commodities, shipping, chemicals and similar industries,
the model uses 5-10 years where available and covers at least one full cycle:

- normalize selling price, unit cost, utilization and operating margin;
- calculate normalized earnings and operating cash flow at current scale;
- distinguish cost-curve advantage from a temporary product-price windfall;
- inspect capacity, inventory, supply exits, leverage and capital expenditure;
- run bear/base/bull scenarios instead of treating current PE as fair value.

Muyuan Foods (`002714`) is a regression scenario, not a whitelist. Its current
PE may be negative or distorted during a hog-cycle trough, so that fact alone
must not exclude it. It can receive a buy-like result only when normalized
earnings, cost advantage, balance-sheet resilience, supply-cycle evidence and
data completeness pass the same published rules as every other company.

## 5. Short-Right-Side Amendment

### 5.1 Pre-ranking before K-line retrieval

The preliminary score changes from valuation-led selection to tradable market
leadership:

| Input | Current | New |
|---|---:|---:|
| Valuation context | 30% | 10% |
| Liquidity | 22% | 30% |
| Non-chase position | 18% | 25% |
| Dynamic direction heat | 30% | 35% |

The simultaneous high-PE/high-PB exclusion is removed. Illiquidity, ST/delisting
risk, invalid prices and excessive same-day chase risk remain exclusions.

### 5.2 Final score after K-line and financial review

| Factor | New weight |
|---|---:|
| Right-side structure | 40% |
| Volume confirmation | 20% |
| Dynamic direction heat | 15% |
| Financial quality floor | 20% |
| Valuation context | 5% |

Risk penalties remain outside these weights. A stretched valuation may lower
confidence or require a tighter invalidation rule, but cannot override a valid
right-side structure by itself. Conversely, a low PE/PB cannot create a short
buy signal without structure, volume and quality confirmation.

## 6. Decision Semantics

- `screeningAction` describes research eligibility only.
- `todayAdvice` remains the single user-facing action.
- Long-term and short-term advice remain separate; they may legitimately say
  `BUILD_POSITION` and `WAIT_PULLBACK` at the same time.
- Without portfolio state, a new position is `LIGHT_TRIAL` or
  `BUILD_POSITION`, never `ADD`.
- A high PE/PB warning cannot be displayed as an exclusion after the stock has
  passed the soft valuation model.

## 7. UI Changes

- Rename PE/PB upper-limit controls to reference bands and add an inline note
  that they affect context scores, not eligibility.
- Replace "PE/PB threshold passed" with a valuation-context label and evidence.
- Show cyclical companies as `周期盈利失真` when current multiples are not
  meaningful.
- Keep factor weights and each contribution visible in stock details.

## 8. Error Handling

- Missing PE/PB produces `MISSING` with a neutral contextual score; it does not
  become zero and does not exclude the stock.
- Negative PE produces `DISTORTED`; the strategy must select a cyclical,
  turnaround or early-growth model before a buy-like action.
- Missing normalized-cycle evidence keeps a cyclical candidate at
  `EVIDENCE_REVIEW`.
- Missing financial-quality evidence closes the buy gate even when price and
  momentum are strong.

## 9. Tests

### Long term

- A high-PE/high-PB company is not excluded solely by reference bands.
- A negative-PE cyclical fixture can enter normalized-cycle research.
- A low-PE company with weak cash conversion cannot receive a buy-like action.
- PE/PB are not counted again in the financial-quality score.

### Short term

- A liquid, hot-direction, right-side fixture survives extreme PE/PB values.
- The same fixture can still be downgraded by chase risk or weak financials.
- A low-PE fixture without a right-side structure cannot receive `ADD` or
  `LIGHT_TRIAL`.
- Preliminary and final weights sum to 100% and are exposed in evidence.

### Regression and integration

- No symbol-specific whitelist or score bonus exists for `002714`.
- Existing full-market coverage accounting remains unchanged.
- Backward-compatible API parameters continue to work while UI labels use
  reference-band semantics.
- Full backend tests, frontend build, Docker health checks and live mode scans
  pass.

## 10. Delivery Order

1. P0.1: remove valuation hard exclusions, adjust weights and explanations,
   rename UI controls, and add regression tests.
2. P1: persist point-in-time financial and factor snapshots so quality does not
   rely on quote proxies.
3. P2: implement industry-neutral long-value factors and normalized cyclical
   valuation.
4. P4: publish weights only after rolling out-of-sample validation; until then,
   label them as initial research parameters.

## 11. Research Basis

- MSCI combines several standardized value descriptors instead of a single
  fixed PE/PB cliff, and separates value from quality factors:
  https://www.msci.com/eqb/methodology/meth_docs/MSCI_Enhanced_Value_Indexes_Methodology_Book_June2017.pdf
- MSCI describes quality using profitability, earnings stability and leverage:
  https://www.msci.com/indexes/group/quality-indexes
- Damodaran recommends normalizing earnings across a full 5-10 year cycle for
  mature cyclical businesses:
  https://pages.stern.nyu.edu/~adamodar/New_Home_Page/valquestions/normearn.htm
- Momentum evidence does not remove crash risk, supporting explicit chase and
  regime penalties rather than valuation-led short-term selection:
  https://www.nber.org/papers/w20660
- Muyuan Foods' official 2025 annual report and 2026 first-quarter report are
  primary evidence for the regression scenario:
  https://static.cninfo.com.cn/finalpage/2026-03-28/1225042507.PDF
  https://static.cninfo.com.cn/finalpage/2026-04-22/1225136604.PDF
