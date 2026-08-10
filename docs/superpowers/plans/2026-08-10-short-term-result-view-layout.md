# 短线结果区横版展示与记忆型模块开关 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让短线页结果区支持本机记忆的模块勾选开关，并把核心摘要模块改成横版展示。

**Architecture:** 在短线页增加一个轻量的本地偏好层，负责读写 5 个显示开关。页面本身继续从现有 `report` 渲染，但把结果区拆成“控制条 + 横版摘要 + 辅助模块”三层，默认只展示最重要的三块。

**Tech Stack:** React, TypeScript, Vitest, browser `localStorage`, existing Tailwind utility classes.

## Global Constraints

- 不修改后端接口。
- 展示偏好只保存在本机浏览器。
- 默认隐藏“方法”和“今日资金去向”。
- “市场情绪”“扫描快照”“热门方向”默认展示。
- 结果区在桌面端优先横向排布，移动端允许堆叠。

---

### Task 1: Local view preferences

**Files:**
- Create: `apps/web-react/src/lib/shortTermViewPreferences.ts`
- Test: `apps/web-react/src/lib/shortTermViewPreferences.test.ts`

**Interfaces:**
- Consumes: browser `localStorage`
- Produces: `loadShortTermViewPreferences()`, `saveShortTermViewPreferences()`, `defaultShortTermViewPreferences()`

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from 'vitest'
import { defaultShortTermViewPreferences, loadShortTermViewPreferences } from './shortTermViewPreferences'

describe('shortTermViewPreferences', () => {
  it('falls back to default hidden and visible states when storage is empty', () => {
    window.localStorage.removeItem('ai-stock.short-term.result-view.v1')
    expect(loadShortTermViewPreferences()).toEqual(defaultShortTermViewPreferences())
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm --prefix apps/web-react test -- src/lib/shortTermViewPreferences.test.ts`
Expected: FAIL because module does not exist yet.

- [ ] **Step 3: Write minimal implementation**

```ts
export type ShortTermViewPreferences = {
  methodologyVisible: boolean
  marketSentimentVisible: boolean
  fundFlowVisible: boolean
  snapshotVisible: boolean
  hotDirectionsVisible: boolean
}

const STORAGE_KEY = 'ai-stock.short-term.result-view.v1'

export function defaultShortTermViewPreferences(): ShortTermViewPreferences {
  return {
    methodologyVisible: false,
    marketSentimentVisible: true,
    fundFlowVisible: false,
    snapshotVisible: true,
    hotDirectionsVisible: true
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm --prefix apps/web-react test -- src/lib/shortTermViewPreferences.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add apps/web-react/src/lib/shortTermViewPreferences.ts apps/web-react/src/lib/shortTermViewPreferences.test.ts
git commit -m "feat: add short-term result view preferences"
```

### Task 2: Short-term result layout

**Files:**
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Test: `apps/web-react/src/pages/ShortTermPage.test.tsx`

**Interfaces:**
- Consumes: `ShortTermViewPreferences`
- Produces: result-view toggle bar, three horizontal summary cards, optional auxiliary cards

- [ ] **Step 1: Write the failing test**

```ts
it('defaults to hiding methodology and fund flow but shows the three summary cards', async () => {
  await renderPage(root)
  expect(document.body.textContent).not.toContain('方法')
  expect(document.body.textContent).not.toContain('今日资金去向')
  expect(document.body.textContent).toContain('市场情绪')
  expect(document.body.textContent).toContain('扫描快照')
  expect(document.body.textContent).toContain('热门方向')
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm --prefix apps/web-react test -- ShortTermPage.test.tsx`
Expected: FAIL because current page still renders all modules unconditionally.

- [ ] **Step 3: Write minimal implementation**

```tsx
const [viewPreferences, setViewPreferences] = useState(defaultShortTermViewPreferences())
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm --prefix apps/web-react test -- ShortTermPage.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add apps/web-react/src/pages/ShortTermPage.tsx apps/web-react/src/pages/ShortTermPage.test.tsx
git commit -m "feat: add short-term result layout toggles"
```

### Task 3: Verification and build

**Files:**
- None

**Interfaces:**
- Consumes: full short-term page flow
- Produces: verified UI change

- [ ] **Step 1: Run focused tests**

Run: `npm --prefix apps/web-react test -- --run`

- [ ] **Step 2: Run production build**

Run: `npm --prefix apps/web-react run build`

- [ ] **Step 3: Commit any final fixes**

```bash
git add -A
git commit -m "feat: polish short-term result view layout"
```
