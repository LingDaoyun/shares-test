# System Detail Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the four persistent right-side detail panes with one accessible, reusable, click-outside-to-close detail overlay pattern.

**Architecture:** Add a portal-based `DetailOverlay` UI primitive that owns backdrop, keyboard, focus, and scroll-lock behavior while each page keeps its existing business detail component. Remove automatic first-row selection and render the four detail surfaces through the common overlay, leaving nested trade-operation dialogs at a higher z-index.

**Tech Stack:** React 18, TypeScript, React DOM portal, Tailwind CSS, Lucide React, Vitest, Vite, Docker Compose.

## Global Constraints

- Cover only short-term candidates, market-scan candidates, watchlist analysis, and trade-review case details.
- Desktop overlay width is capped at `1180px` and height at `88dvh`; mobile height is capped at `92dvh` with visible viewport margins.
- Backdrop click, close icon, and `Escape` close the detail overlay; clicks inside it do not.
- Do not change backend interfaces, scoring, recommendation logic, or business operations.
- Existing fill-edit and cancellation dialogs remain above the detail overlay.
- Do not auto-open the first item after list data loads.

---

### Task 1: Reusable Detail Overlay

**Files:**
- Create: `apps/web-react/src/components/ui/DetailOverlay.tsx`
- Create: `apps/web-react/src/components/ui/DetailOverlay.test.tsx`

**Interfaces:**
- Consumes: React `ReactNode`, `createPortal`, Lucide `X`.
- Produces: `DetailOverlay({ open, title, subtitle, onClose, children, initialFocusRef })` and pure helpers `isBackdropClose` and `isEscapeClose`.

- [ ] **Step 1: Write failing tests for semantics and close predicates**

```tsx
expect(isBackdropClose(backdrop, backdrop)).toBe(true)
expect(isBackdropClose(child, backdrop)).toBe(false)
expect(isEscapeClose('Escape')).toBe(true)
expect(renderToStaticMarkup(<DetailOverlay open title="股票详情" onClose={() => {}}>正文</DetailOverlay>))
  .toContain('aria-modal="true"')
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `npm --prefix apps/web-react test -- DetailOverlay.test.tsx`

Expected: FAIL because `DetailOverlay.tsx` does not exist.

- [ ] **Step 3: Implement the portal dialog**

```tsx
export function isBackdropClose(target: EventTarget | null, currentTarget: EventTarget | null) {
  return target === currentTarget
}

export function isEscapeClose(key: string) {
  return key === 'Escape'
}

export function DetailOverlay({ open, title, subtitle, onClose, children }: DetailOverlayProps) {
  // Lock body scroll while open, close on Escape, focus the dialog,
  // restore the trigger focus on cleanup, and portal the frame to body.
}
```

The frame uses `fixed inset-0 z-50`, a neutral `bg-ink-900/35` backdrop, a `max-w-[1180px]`, `max-h-[88dvh]` white dialog, a sticky header, and an independently scrollable body. The close button uses the Lucide `X` icon and `aria-label="关闭详情"`.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `npm --prefix apps/web-react test -- DetailOverlay.test.tsx`

Expected: all detail-overlay tests pass.

---

### Task 2: Short-Term and Market-Scan Candidate Details

**Files:**
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Modify: `apps/web-react/src/pages/MarketScanPage.tsx`

**Interfaces:**
- Consumes: `DetailOverlay` from Task 1.
- Produces: full-width candidate lists with click-open detail overlays.

- [ ] **Step 1: Add a failing source-contract test**

Create `apps/web-react/src/components/ui/detailOverlayIntegration.test.ts` that reads no files but tests the exported pure selection rule:

```ts
expect(resolveDetailSelection(items, null, item => item.symbol)).toBeNull()
expect(resolveDetailSelection(items, '600000', item => item.symbol)?.symbol).toBe('600000')
expect(resolveDetailSelection(items, 'missing', item => item.symbol)).toBeNull()
```

Add `resolveDetailSelection` to `DetailOverlay.tsx` only after observing RED.

- [ ] **Step 2: Remove first-item auto-selection**

Delete both effects that replace a missing `selectedSymbol` with `report.candidates[0].symbol`. Resolve the selected item only when `selectedSymbol` is non-null and clear it when refreshed candidates no longer contain it.

- [ ] **Step 3: Replace split layouts**

Use a single full-width candidate `Card` and render:

```tsx
<DetailOverlay
  open={selected !== null}
  title={selected ? `${selected.name} ${selected.symbol}` : '候选详情'}
  subtitle={selected?.industry ?? undefined}
  onClose={() => setSelectedSymbol(null)}
>
  {selected ? (
    <CandidateDetail
      candidate={selected}
      backtestSummary={backtestBySymbol.get(selected.symbol)}
      backtestLoading={backtestLoading}
      backtestError={backtestError}
      weightProfile={report.weightProfile}
      generatedAt={report.generatedAt}
      tradeCaptureToken={report.tradeCaptureTokens?.[selected.symbol] ?? null}
    />
  ) : null}
</DetailOverlay>
```

The market-scan page uses the same wrapper with its existing
`<CandidateDetail candidate={selected} />` body.

Preserve all existing short-term backtest, Agent, evidence, watchlist, trade-review, valuation, and advice content.

- [ ] **Step 4: Verify focused tests and TypeScript build**

Run: `npm --prefix apps/web-react test -- DetailOverlay.test.tsx detailOverlayIntegration.test.ts`

Run: `npm --prefix apps/web-react run build`

Expected: tests and build pass.

---

### Task 3: Watchlist Detail Overlay

**Files:**
- Modify: `apps/web-react/src/pages/WatchlistPage.tsx`

**Interfaces:**
- Consumes: `DetailOverlay` and `resolveDetailSelection`.
- Produces: full-width watchlist with on-demand analysis detail.

- [ ] **Step 1: Remove automatic watchlist selection**

Keep `selectedSymbol` null after initial load. If a selected entry disappears, clear selection; do not select `entries[0]`.

- [ ] **Step 2: Open details only from an entry interaction**

Preserve mouse, `Enter`, and space activation. Move the existing active review card, analysis result, and decision history into `DetailOverlay`.

- [ ] **Step 3: Preserve deletion and analysis behavior**

Keep delete-button propagation blocked. If deleting the currently open entry succeeds, close the overlay. Keep refresh and active-analysis actions inside the detail content.

- [ ] **Step 4: Verify tests and build**

Run: `npm --prefix apps/web-react test`

Run: `npm --prefix apps/web-react run build`

Expected: all frontend tests and production build pass.

---

### Task 4: Trade Review Detail Overlay and Nested Dialogs

**Files:**
- Modify: `apps/web-react/src/pages/TradeReviewPage.tsx`

**Interfaces:**
- Consumes: `DetailOverlay`.
- Produces: full-width review table and asynchronously populated case-detail overlay.

- [ ] **Step 1: Remove the right-side aside and scroll-to behavior**

Delete `detailPaneRef` and `scrollIntoView`. Keep `selectedId` as the open-overlay key and keep request-sequence guards unchanged.

- [ ] **Step 2: Render async states in the overlay**

```tsx
<DetailOverlay
  open={selectedId !== null}
  title={selected ? `${selected.symbol} ${selected.companyName}` : '复盘详情'}
  onClose={() => setSelectedId(null)}
>
  {detailLoadingId === selected?.caseId && !isTradeCaseDetail(selected) ? (
    <Loader text="详情加载中" className="py-12" />
  ) : isTradeCaseDetail(selected) ? (
    <CaseDetail
      tradeCase={selected}
      error={detailError}
      action={mutation?.kind ?? null}
      busy={mutation !== null}
      onAddFill={(returnFocus) => { if (!mutation) setFillModal({ returnFocus }) }}
      onEditFill={(fill, returnFocus) => { if (!mutation) setFillModal({ fill, returnFocus }) }}
      onDeleteFill={(fill) => void removeFill(fill)}
      onRefresh={() => void runCaseAction('refresh', selected.caseId)}
      onRequestCancel={(returnFocus) => setCancelDialog({ caseId: selected.caseId, symbol: selected.symbol, returnFocus })}
    />
  ) : (
    <div role="alert">{detailError || '复盘详情暂时不可用'}</div>
  )}
</DetailOverlay>
```

Do not close the overlay while refreshing or mutating a case.

- [ ] **Step 3: Keep nested operation dialogs above details**

Retain `FillModal` and `CancelPlanDialog` at `z-[60]`, while `DetailOverlay` remains at `z-50`. Escape and backdrop events from nested dialogs must stop propagation so they close only the nested dialog.

- [ ] **Step 4: Verify the complete frontend suite**

Run: `npm --prefix apps/web-react test`

Run: `npm --prefix apps/web-react run build`

Expected: all tests pass and Vite emits a production bundle without TypeScript errors.

---

### Task 5: Runtime and Responsive Verification

**Files:**
- Modify only if runtime verification reveals a defect in files from Tasks 1-4.

**Interfaces:**
- Consumes: final frontend bundle and existing Docker Compose services.
- Produces: verified desktop/mobile interaction on all four routes.

- [ ] **Step 1: Rebuild the web container**

Run: `docker compose up -d --build web`

Expected: `ai-stock-web` is recreated and healthy; API remains healthy.

- [ ] **Step 2: Verify routes and interactions**

Check `/#/short-term`, `/#/market`, `/#/watchlist`, and `/#/trade-review` at desktop and `390px` mobile widths. On each applicable route, confirm list width, click-open behavior, internal scrolling, backdrop close, close icon, and no horizontal overflow.

- [ ] **Step 3: Verify service health and patch integrity**

Run: `curl --fail http://127.0.0.1:19080/actuator/health`

Run: `curl --fail --head http://127.0.0.1:5176/`

Run: `git diff --check`

Expected: API reports `UP`, web returns `200`, and diff check is clean.
