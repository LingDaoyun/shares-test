# Manual Scan Floating Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every manual short-term scan one bottom-right floating notification that shows progress immediately and later explains success, no-candidate, blocked, preview, pending, or failure outcomes without restoring the removed status card.

**Architecture:** Extend the existing global Zustand Toast store with backward-compatible keyed replacement, caller-selected duration, persistence, keyed dismissal, and accessible live-region semantics. Keep the short-term scan store as the lifecycle owner: it publishes one keyed progress Toast, replaces it with the terminal outcome, preserves scheduled-scan Toast behavior, and clears only an active manual progress Toast if a newer scheduled snapshot takes control.

**Tech Stack:** React 18, TypeScript 5.6, Zustand 5, Vitest 3, jsdom, Vite 6, Docker Compose

## Global Constraints

- Use the existing bottom-right `ToastViewport`; do not add a modal, page card, second floating panel, or backend endpoint.
- The stable manual-scan Toast key is exactly `short-term-manual-scan`.
- The progress copy is exactly `短线扫描已开始，正在获取实时行情…` and remains until replacement or manual dismissal.
- `FINAL_READY` with candidates uses the existing completion copy, then auto-dismisses after exactly 5,000 ms.
- `FINAL_READY` with zero candidates, `NO_TRADE`, `DATA_BLOCKED`, `CACHE_PREVIEW`, `FINAL_PENDING`, and `PRESELECT_READY` are persistent warnings requiring manual dismissal.
- Job creation failure, polling failure, job state `FAILED`, and an invalid successful response without its required report are persistent errors requiring manual dismissal.
- A terminal outcome must create a new notification even if the user manually closed the running notification.
- Existing unkeyed calls such as `toast.success(message)` retain the 3,200 ms default.
- `persistent: true` takes precedence over `durationMs`.
- Information and success notifications use polite status semantics; warning and error notifications use alert semantics; the close button is keyboard accessible and labelled `关闭通知`.
- Keep the removed manual status card absent, and preserve the manual button loading state, compact loader, inline error/result fallback, background polling, navigation restoration, and scheduled status UI.
- Do not change scan APIs, polling interval, backend result states, database records, candidate ranking, V4 decision gates, or scheduled scan control.
- Do not invoke an external model during implementation or acceptance.
- Rebuild only the Web container after all tests pass; do not replace the API container or database volume.
- Push `main` only after focused tests, complete tests, production builds, live interaction, and remote-divergence checks pass.

---

### Task 1: Add Keyed and Persistent Toast Lifecycle Semantics

**Files:**
- Create: `apps/web-react/src/components/ui/Toast.test.tsx`
- Modify: `apps/web-react/src/components/ui/Toast.tsx:5-74`

**Interfaces:**
- Consumes: existing `ToastViewport`, Zustand `create`, React `useEffect`, and the four convenience methods `toast.success/error/info/warning`.
- Produces: exported `ToastOptions { key?: string; durationMs?: number; persistent?: boolean }`; `toast.<type>(message: string, options?: ToastOptions): void`; `toast.dismiss(key: string): void`; unchanged 3,200 ms behavior for calls without options.

- [ ] **Step 1: Write focused failing Toast tests**

Create `apps/web-react/src/components/ui/Toast.test.tsx` with this complete test suite:

```tsx
// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast, ToastViewport, useToastStore } from './Toast'

describe('Toast lifecycle', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.useFakeTimers()
    useToastStore.setState({ toasts: [] })
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
    act(() => root.render(<ToastViewport />))
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    useToastStore.setState({ toasts: [] })
    vi.useRealTimers()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  it('keeps the 3200 ms default for existing unkeyed calls', () => {
    act(() => toast.info('普通通知'))
    expect(host.textContent).toContain('普通通知')

    act(() => vi.advanceTimersByTime(3199))
    expect(host.textContent).toContain('普通通知')

    act(() => vi.advanceTimersByTime(1))
    expect(host.textContent).not.toContain('普通通知')
  })

  it('keeps a persistent alert until its accessible close button is used', () => {
    act(() => toast.warning('需要人工确认', { persistent: true }))

    const alert = host.querySelector('[role="alert"]')
    expect(alert?.textContent).toContain('需要人工确认')
    expect(alert?.getAttribute('aria-live')).toBe('assertive')

    act(() => vi.advanceTimersByTime(60_000))
    expect(host.textContent).toContain('需要人工确认')

    const close = host.querySelector('button[aria-label="关闭通知"]')
    expect(close).not.toBeNull()
    act(() => close?.dispatchEvent(new MouseEvent('click', { bubbles: true })))
    expect(host.textContent).not.toContain('需要人工确认')
  })

  it('replaces a keyed row and restarts it with the new 5000 ms policy', () => {
    act(() => toast.info('扫描中', { key: 'scan', persistent: true }))
    act(() => toast.success('扫描完成', { key: 'scan', durationMs: 5000 }))

    expect(useToastStore.getState().toasts).toHaveLength(1)
    expect(host.textContent).not.toContain('扫描中')
    expect(host.textContent).toContain('扫描完成')
    expect(host.querySelector('[role="status"]')?.getAttribute('aria-live')).toBe('polite')

    act(() => vi.advanceTimersByTime(4999))
    expect(host.textContent).toContain('扫描完成')

    act(() => vi.advanceTimersByTime(1))
    expect(host.textContent).not.toContain('扫描完成')
  })

  it('lets persistence override a supplied duration and supports keyed dismissal', () => {
    act(() => toast.error('不会自动消失', {
      key: 'persistent-error',
      durationMs: 10,
      persistent: true
    }))

    act(() => vi.advanceTimersByTime(10_000))
    expect(host.textContent).toContain('不会自动消失')

    act(() => toast.dismiss('persistent-error'))
    expect(host.textContent).not.toContain('不会自动消失')
  })

  it('recreates a terminal notification after the running row was manually closed', () => {
    act(() => toast.info('扫描中', { key: 'scan', persistent: true }))
    const close = host.querySelector('button[aria-label="关闭通知"]')
    act(() => close?.dispatchEvent(new MouseEvent('click', { bubbles: true })))
    expect(host.textContent).not.toContain('扫描中')

    act(() => toast.warning('扫描无结果：行情不足', {
      key: 'scan',
      persistent: true
    }))

    expect(useToastStore.getState().toasts).toHaveLength(1)
    expect(host.textContent).toContain('扫描无结果：行情不足')
  })
})
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
cd apps/web-react
npm test -- src/components/ui/Toast.test.tsx
```

Expected: FAIL because the convenience methods do not accept lifecycle options, keyed replacement and `toast.dismiss` do not exist, all rows currently auto-dismiss after 3,200 ms, and the close button lacks its accessible label.

- [ ] **Step 3: Implement the backward-compatible Toast contract**

Replace `apps/web-react/src/components/ui/Toast.tsx` with:

```tsx
import { useEffect } from 'react'
import { create } from 'zustand'
import { CheckCircle2, AlertCircle, Info, X } from 'lucide-react'

type ToastType = 'success' | 'error' | 'info' | 'warning'

export interface ToastOptions {
  key?: string
  durationMs?: number
  persistent?: boolean
}

interface ToastItem {
  id: number
  key?: string
  type: ToastType
  message: string
  durationMs: number | null
}

interface ToastStore {
  toasts: ToastItem[]
  push: (type: ToastType, message: string, options?: ToastOptions) => void
  remove: (id: number) => void
  dismiss: (key: string) => void
}

const DEFAULT_TOAST_DURATION_MS = 3200
let nextId = 1

export const useToastStore = create<ToastStore>((set) => ({
  toasts: [],
  push: (type, message, options = {}) => {
    const durationMs = options.persistent
      ? null
      : (options.durationMs ?? DEFAULT_TOAST_DURATION_MS)
    set((state) => {
      const existing = options.key
        ? state.toasts.find((item) => item.key === options.key)
        : undefined
      if (existing) {
        return {
          toasts: state.toasts.map((item) => item.id === existing.id
            ? { ...item, type, message, durationMs }
            : item)
        }
      }
      return {
        toasts: [...state.toasts, {
          id: nextId++,
          key: options.key,
          type,
          message,
          durationMs
        }]
      }
    })
  },
  remove: (id) => set((state) => ({
    toasts: state.toasts.filter((item) => item.id !== id)
  })),
  dismiss: (key) => set((state) => ({
    toasts: state.toasts.filter((item) => item.key !== key)
  }))
}))

// 对外便捷 API，替代旧版 ElMessage
export const toast = {
  success: (message: string, options?: ToastOptions) => {
    useToastStore.getState().push('success', message, options)
  },
  error: (message: string, options?: ToastOptions) => {
    useToastStore.getState().push('error', message, options)
  },
  info: (message: string, options?: ToastOptions) => {
    useToastStore.getState().push('info', message, options)
  },
  warning: (message: string, options?: ToastOptions) => {
    useToastStore.getState().push('warning', message, options)
  },
  dismiss: (key: string) => useToastStore.getState().dismiss(key)
}

const config = {
  success: { icon: CheckCircle2, cls: 'text-success' },
  error: { icon: AlertCircle, cls: 'text-danger' },
  info: { icon: Info, cls: 'text-brand-500' },
  warning: { icon: AlertCircle, cls: 'text-warning' }
} as const

function ToastRow({ item }: { item: ToastItem }) {
  const remove = useToastStore((state) => state.remove)
  const { icon: Icon, cls } = config[item.type]
  const assertive = item.type === 'warning' || item.type === 'error'

  useEffect(() => {
    if (item.durationMs === null) return
    const timer = window.setTimeout(() => remove(item.id), item.durationMs)
    return () => window.clearTimeout(timer)
  }, [item, remove])

  return (
    <div
      role={assertive ? 'alert' : 'status'}
      aria-live={assertive ? 'assertive' : 'polite'}
      aria-atomic="true"
      className="card flex items-center gap-2.5 px-4 py-3 shadow-float animate-fade-in"
    >
      <Icon className={`h-4 w-4 shrink-0 ${cls}`} />
      <span className="min-w-0 flex-1 text-sm text-ink-900">{item.message}</span>
      <button
        type="button"
        aria-label="关闭通知"
        onClick={() => remove(item.id)}
        className="ml-1 text-ink-400 hover:text-ink-600"
      >
        <X className="h-3.5 w-3.5" />
      </button>
    </div>
  )
}

export function ToastViewport() {
  const toasts = useToastStore((state) => state.toasts)
  return (
    <div className="pointer-events-none fixed bottom-6 right-6 z-50 flex w-80 flex-col gap-2">
      {toasts.map((item) => (
        <div key={item.id} className="pointer-events-auto">
          <ToastRow item={item} />
        </div>
      ))}
    </div>
  )
}
```

The `ToastRow` effect deliberately depends on the `item` object. A keyed replacement preserves its row ID but creates a new object, so changing a persistent progress Toast into a 5-second success Toast always starts a fresh timer, even if the visible text repeats.

- [ ] **Step 4: Run focused Toast tests and existing Toast consumers**

Run:

```bash
cd apps/web-react
npm test -- src/components/ui/Toast.test.tsx src/components/tradefeedback/BuyEntryButton.test.tsx
```

Expected: both test files pass; the existing unkeyed callers still produce one 3,200 ms Toast and require no call-site changes.

- [ ] **Step 5: Commit the Toast lifecycle API**

```bash
git add apps/web-react/src/components/ui/Toast.tsx apps/web-react/src/components/ui/Toast.test.tsx
git commit -m "feat: add persistent keyed toast lifecycle"
```

### Task 2: Publish the Complete Manual Scan Lifecycle

**Files:**
- Create: `apps/web-react/src/store/shortTermScanStore.test.ts`
- Modify: `apps/web-react/src/store/shortTermScanStore.ts:23-303`
- Modify: `apps/web-react/src/pages/ShortTermPage.test.tsx:30-37,891,1030,1092`
- Verify: `apps/web-react/src/pages/ShortTermPage.tsx:153-161`

**Interfaces:**
- Consumes: Task 1's `toast.<type>(message, options)` and `toast.dismiss(key)` APIs; scan job `status`, `resultStatus`, `message`, `blockedReasons`, and optional `report`; existing generation-based ownership and 1,500 ms polling.
- Produces: one keyed persistent progress Toast; `manualScanOutcome(snapshot)` status mapping; persistent warning/error replacement; 5,000 ms candidate success replacement; scheduled takeover dismissal only while a manual run is active.

- [ ] **Step 1: Add failing store lifecycle tests**

Create `apps/web-react/src/store/shortTermScanStore.test.ts`:

```ts
// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchLatestShortTermScheduledSnapshot,
  fetchShortTermScanJob,
  startShortTermScanJob
} from '../api/client'
import type { ShortTermParams } from '../api/client'
import { toast } from '../components/ui/Toast'
import type {
  ShortTermReport,
  ShortTermScanJobStatus,
  ShortTermScheduledSnapshot,
  ShortTermSnapshotStatus
} from '../types'
import { resetShortTermScanStoreForTest, useShortTermScanStore } from './shortTermScanStore'

vi.mock('../api/client', () => ({
  fetchLatestShortTermScheduledSnapshot: vi.fn(),
  fetchShortTermScanJob: vi.fn(),
  startShortTermScanJob: vi.fn()
}))

vi.mock('../components/ui/Toast', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
    dismiss: vi.fn()
  }
}))

const persistentOptions = {
  key: 'short-term-manual-scan',
  persistent: true
}

const successOptions = {
  key: 'short-term-manual-scan',
  durationMs: 5000
}

function report(candidateCount: number): ShortTermReport {
  return {
    candidateCount,
    candidates: [],
    dataCutoffAt: '2026-08-13T14:49:20+08:00'
  } as unknown as ShortTermReport
}

function job(overrides: Partial<ShortTermScanJobStatus> = {}): ShortTermScanJobStatus {
  return {
    jobId: 'manual-1',
    status: 'RUNNING',
    tradeDate: '2026-08-13',
    resultStatus: 'RUNNING',
    strategyVersion: 'short-term-right-side-v4-transparent-ranking',
    blockedReasons: [],
    createdAt: '2026-08-13T14:48:00+08:00',
    startedAt: '2026-08-13T14:48:01+08:00',
    finishedAt: null,
    message: '扫描任务已创建',
    report: null,
    ...overrides
  }
}

function scheduledFinal(): ShortTermScheduledSnapshot {
  return {
    tradeDate: '2026-08-13',
    stage: 'FINAL',
    status: 'FINAL_READY',
    strategyVersion: 'short-term-right-side-v4-transparent-ranking',
    message: '计划任务完成',
    dataCutoffAt: '2026-08-13T14:49:20+08:00',
    startedAt: '2026-08-13T14:47:00+08:00',
    completedAt: '2026-08-13T14:49:30+08:00',
    blockedReasons: [],
    report: report(1)
  }
}

async function runWith(completed: ShortTermScanJobStatus) {
  vi.mocked(startShortTermScanJob).mockResolvedValue(job())
  vi.mocked(fetchShortTermScanJob).mockResolvedValue(completed)
  await useShortTermScanStore.getState().runManualScan({} as ShortTermParams)
}

describe('shortTermScanStore manual notifications', () => {
  beforeEach(() => {
    resetShortTermScanStoreForTest()
  })

  afterEach(() => {
    resetShortTermScanStoreForTest()
    vi.clearAllMocks()
  })

  it('shows persistent progress then a five-second success with candidate count', async () => {
    await runWith(job({
      status: 'SUCCEEDED',
      resultStatus: 'FINAL_READY',
      finishedAt: '2026-08-13T14:49:30+08:00',
      message: '手动扫描完成',
      report: report(2)
    }))

    expect(toast.info).toHaveBeenCalledWith(
      '短线扫描已开始，正在获取实时行情…',
      persistentOptions
    )
    expect(toast.success).toHaveBeenCalledWith(
      '手动扫描完成，已生成 2 个候选',
      successOptions
    )
  })

  it('turns a defensive zero-candidate FINAL_READY result into a persistent warning', async () => {
    await runWith(job({
      status: 'SUCCEEDED',
      resultStatus: 'FINAL_READY',
      finishedAt: '2026-08-13T14:49:30+08:00',
      message: '扫描完成但没有候选',
      report: report(0)
    }))

    expect(toast.success).not.toHaveBeenCalled()
    expect(toast.warning).toHaveBeenCalledWith(
      expect.stringContaining('手动扫描完成，未生成合格候选'),
      persistentOptions
    )
    expect(toast.warning).toHaveBeenCalledWith(
      expect.stringContaining('扫描完成但没有候选'),
      persistentOptions
    )
  })

  it.each([
    ['NO_TRADE', '当前没有满足全部条件的标的', [], '未生成合格候选'],
    ['DATA_BLOCKED', '行情覆盖不足', ['覆盖率 82%', '分钟线缺失'], '数据质量已阻断'],
    ['CACHE_PREVIEW', '休市期间使用缓存', [], '不是当前买点'],
    ['FINAL_PENDING', '尾盘结果仍待认证', [], '暂未形成可操作结果'],
    ['PRESELECT_READY', '当前仅完成预选', [], '暂未形成可操作结果']
  ] as Array<[ShortTermSnapshotStatus, string, string[], string]>) (
    'publishes a persistent warning for %s with the server explanation',
    async (resultStatus, message, blockedReasons, meaning) => {
      const allowsNoReport = resultStatus === 'DATA_BLOCKED'
        || resultStatus === 'FINAL_PENDING'
        || resultStatus === 'PRESELECT_READY'
      await runWith(job({
        status: 'SUCCEEDED',
        resultStatus,
        finishedAt: '2026-08-13T14:49:30+08:00',
        message,
        blockedReasons,
        report: allowsNoReport ? null : report(0)
      }))

      expect(toast.warning).toHaveBeenCalledWith(
        expect.stringContaining(meaning),
        persistentOptions
      )
      expect(toast.warning).toHaveBeenCalledWith(
        expect.stringContaining(message),
        persistentOptions
      )
      for (const reason of blockedReasons) {
        expect(toast.warning).toHaveBeenCalledWith(
          expect.stringContaining(reason),
          persistentOptions
        )
      }
      expect(toast.error).not.toHaveBeenCalled()
    }
  )

  it('publishes a persistent error when the job reports FAILED', async () => {
    await runWith(job({
      status: 'FAILED',
      resultStatus: 'FAILED',
      finishedAt: '2026-08-13T14:49:30+08:00',
      message: '行情服务超时',
      blockedReasons: ['新浪行情不可用']
    }))

    expect(toast.error).toHaveBeenCalledWith(
      expect.stringContaining('行情服务超时'),
      persistentOptions
    )
    expect(toast.error).toHaveBeenCalledWith(
      expect.stringContaining('新浪行情不可用'),
      persistentOptions
    )
  })

  it('publishes a persistent error when job creation fails', async () => {
    vi.mocked(startShortTermScanJob).mockRejectedValue(new Error('创建任务网络中断'))

    await useShortTermScanStore.getState().runManualScan({} as ShortTermParams)

    expect(toast.error).toHaveBeenCalledWith(
      '手动扫描失败：创建任务网络中断',
      persistentOptions
    )
  })

  it('publishes a persistent error when polling fails', async () => {
    vi.mocked(startShortTermScanJob).mockResolvedValue(job())
    vi.mocked(fetchShortTermScanJob).mockRejectedValue(new Error('轮询连接断开'))

    await useShortTermScanStore.getState().runManualScan({} as ShortTermParams)

    expect(toast.error).toHaveBeenCalledWith(
      '手动扫描失败：轮询连接断开',
      persistentOptions
    )
  })

  it('treats FINAL_READY without a report as a persistent protocol error', async () => {
    await runWith(job({
      status: 'SUCCEEDED',
      resultStatus: 'FINAL_READY',
      finishedAt: '2026-08-13T14:49:30+08:00',
      message: '服务端标记完成',
      blockedReasons: ['报告字段为空'],
      report: null
    }))

    expect(toast.error).toHaveBeenCalledWith(
      expect.stringContaining('没有返回报告'),
      persistentOptions
    )
    expect(toast.error).toHaveBeenCalledWith(
      expect.stringContaining('报告字段为空'),
      persistentOptions
    )
  })

  it('dismisses only an active manual progress Toast when a newer scheduled result takes control', async () => {
    useShortTermScanStore.setState({
      origin: 'MANUAL',
      loading: true,
      snapshot: {
        ...scheduledFinal(),
        stage: 'MANUAL',
        status: 'RUNNING',
        startedAt: '2026-08-13T10:00:00+08:00',
        completedAt: null,
        report: null
      },
      report: null
    })
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue(scheduledFinal())

    await useShortTermScanStore.getState().refreshScheduledSnapshot()

    expect(toast.dismiss).toHaveBeenCalledWith('short-term-manual-scan')
    expect(useShortTermScanStore.getState().origin).toBe('SCHEDULED')
  })

  it('does not dismiss a completed persistent manual warning during later scheduled refresh', async () => {
    useShortTermScanStore.setState({
      origin: 'MANUAL',
      loading: false,
      snapshot: {
        ...scheduledFinal(),
        stage: 'MANUAL',
        status: 'DATA_BLOCKED',
        startedAt: '2026-08-13T10:00:00+08:00',
        completedAt: '2026-08-13T10:01:00+08:00',
        report: null
      },
      report: null
    })
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue(scheduledFinal())

    await useShortTermScanStore.getState().refreshScheduledSnapshot()

    expect(toast.dismiss).not.toHaveBeenCalled()
  })

  it('keeps scheduled completion on the existing unkeyed Toast contract', async () => {
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue(scheduledFinal())

    await useShortTermScanStore.getState().refreshScheduledSnapshot()

    expect(toast.success).toHaveBeenCalledWith('计划任务完成，已生成 1 个候选')
    expect(toast.success).not.toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({ key: 'short-term-manual-scan' })
    )
  })
})
```

- [ ] **Step 2: Update page-test expectations to the approved Toast signature**

In the mocked Toast object at the top of `apps/web-react/src/pages/ShortTermPage.test.tsx`, add `dismiss`:

```ts
vi.mock('../components/ui/Toast', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
    dismiss: vi.fn()
  }
}))
```

Add this constant after the mock:

```ts
const manualSuccessToastOptions = {
  key: 'short-term-manual-scan',
  durationMs: 5000
}
```

Replace the three success assertions at the existing lines around 891, 1030, and 1092 with:

```ts
expect(toast.success).toHaveBeenCalledWith(
  '手动分析已完成，已生成当前时点候选，已生成 1 个候选',
  manualSuccessToastOptions
)
```

```ts
expect(toast.success).toHaveBeenCalledWith(
  expect.stringContaining('已生成 1 个候选'),
  manualSuccessToastOptions
)
```

```ts
expect(toast.success).toHaveBeenCalledWith(
  '手动扫描完成，已生成 1 个候选',
  manualSuccessToastOptions
)
```

Do not weaken the adjacent assertions that the manual status card is absent and the background result is restored.

- [ ] **Step 3: Run the lifecycle and page regressions and verify RED**

Run:

```bash
cd apps/web-react
npm test -- src/store/shortTermScanStore.test.ts src/pages/ShortTermPage.test.tsx
```

Expected: the new store tests fail because manual start, non-`FINAL_READY` outcomes, failures, and scheduled takeover do not yet call the new Toast methods; existing page success assertions fail because the store does not pass the 5,000 ms keyed options.

- [ ] **Step 4: Add manual lifecycle constants and scheduled-takeover cleanup**

Add these constants immediately below the scan-store module variables in `apps/web-react/src/store/shortTermScanStore.ts`:

```ts
const MANUAL_SCAN_TOAST_KEY = 'short-term-manual-scan'
const MANUAL_SCAN_PERSISTENT_TOAST = {
  key: MANUAL_SCAN_TOAST_KEY,
  persistent: true
} as const
const MANUAL_SCAN_SUCCESS_TOAST = {
  key: MANUAL_SCAN_TOAST_KEY,
  durationMs: 5000
} as const
```

In `refreshScheduledSnapshot`, capture the current state once and dismiss only an active manual progress Toast before switching control:

```ts
const current = get()
const takesControl = scheduledSnapshotTakesControl(current, scheduledSnapshot)
if (!takesControl) {
  set({ scheduledSnapshot })
  return
}
if (current.origin === 'MANUAL' && current.loading) {
  toast.dismiss(MANUAL_SCAN_TOAST_KEY)
}
manualRunGeneration += 1
clearPollTimer()
```

Immediately after `clearPollTimer()` at the start of `runManualScan`, publish the progress state:

```ts
toast.info(
  '短线扫描已开始，正在获取实时行情…',
  MANUAL_SCAN_PERSISTENT_TOAST
)
```

Because keyed replacement searches only currently visible rows, a terminal push creates a fresh notification when the user previously closed the progress row.

- [ ] **Step 5: Add deterministic terminal message construction**

Insert these helpers below `snapshotFromManualJob` and before `notifySnapshotCompleted`:

```ts
type ManualScanOutcome = {
  tone: 'success' | 'error' | 'warning'
  message: string
  options: typeof MANUAL_SCAN_PERSISTENT_TOAST | typeof MANUAL_SCAN_SUCCESS_TOAST
}

function uniqueNonBlank(values: string[]) {
  const seen = new Set<string>()
  return values
    .map((value) => value.trim())
    .filter((value) => {
      if (!value || seen.has(value)) return false
      seen.add(value)
      return true
    })
}

function outcomeMessage(
  prefix: string,
  serverMessage: string,
  blockedReasons: string[],
  fallback: string
) {
  const details = uniqueNonBlank([serverMessage, ...blockedReasons])
    .filter((detail) => !prefix.includes(detail) && !detail.includes(prefix))
  return `${prefix}：${details.length > 0 ? details.join('；') : fallback}`
}

function manualScanOutcome(snapshot: ShortTermScheduledSnapshot): ManualScanOutcome {
  const candidateCount = snapshot.report?.candidateCount
  if (snapshot.status === 'FINAL_READY' && candidateCount !== undefined && candidateCount > 0) {
    return {
      tone: 'success',
      message: snapshotCompletionToastMessage(snapshot),
      options: MANUAL_SCAN_SUCCESS_TOAST
    }
  }

  if (snapshot.status === 'FAILED') {
    return {
      tone: 'error',
      message: outcomeMessage(
        '手动扫描失败',
        snapshot.message,
        snapshot.blockedReasons,
        '扫描任务未完成'
      ),
      options: MANUAL_SCAN_PERSISTENT_TOAST
    }
  }

  const warning = (() => {
    switch (snapshot.status) {
      case 'FINAL_READY':
      case 'NO_TRADE':
        return ['手动扫描完成，未生成合格候选', '当前条件下没有满足全部规则的标的'] as const
      case 'DATA_BLOCKED':
        return ['手动扫描未生成结果，数据质量已阻断', '行情数据质量未通过'] as const
      case 'CACHE_PREVIEW':
        return ['手动扫描仅返回缓存预览，不是当前买点', '当前实时行情不可用'] as const
      case 'FINAL_PENDING':
        return ['手动扫描暂未形成可操作结果', '最终结果仍待截止认证'] as const
      case 'PRESELECT_READY':
        return ['手动扫描暂未形成可操作结果', '当前仅完成预选'] as const
      case 'RUNNING':
        return ['手动扫描返回了非终态结果', '请稍后重新扫描'] as const
    }
  })()

  return {
    tone: 'warning',
    message: outcomeMessage(
      warning[0],
      snapshot.message,
      snapshot.blockedReasons,
      warning[1]
    ),
    options: MANUAL_SCAN_PERSISTENT_TOAST
  }
}

function publishManualScanOutcome(snapshot: ShortTermScheduledSnapshot) {
  const outcome = manualScanOutcome(snapshot)
  toast[outcome.tone](outcome.message, outcome.options)
  return outcome
}

function manualResultMayOmitReport(status: ShortTermScheduledSnapshot['status']) {
  return status === 'NO_TRADE'
    || status === 'DATA_BLOCKED'
    || status === 'FINAL_PENDING'
    || status === 'PRESELECT_READY'
}

function missingReportMessage(job: ShortTermScanJobStatus) {
  return outcomeMessage(
    '短线扫描任务已完成，但没有返回报告',
    job.message,
    job.blockedReasons,
    '服务端未返回报告数据'
  )
}

function publishManualScanFailure(message: string, blockedReasons: string[] = []) {
  toast.error(
    outcomeMessage('手动扫描失败', message, blockedReasons, '扫描任务未完成'),
    MANUAL_SCAN_PERSISTENT_TOAST
  )
}
```

Then replace `notifySnapshotCompleted` with this branch, leaving scheduled completion de-duplication unchanged:

```ts
function notifySnapshotCompleted(origin: ShortTermScanOrigin, snapshot: ShortTermScheduledSnapshot) {
  if (origin === 'MANUAL') {
    publishManualScanOutcome(snapshot)
    return
  }
  if (snapshot.status !== 'FINAL_READY') return
  const key = [
    origin,
    snapshot.tradeDate,
    snapshot.completedAt ?? '',
    snapshot.report?.candidateCount ?? -1
  ].join('|')
  if (completionToastKey === key) return
  completionToastKey = key
  toast.success(snapshotCompletionToastMessage(snapshot))
}
```

This intentionally leaves all scheduled notifications on the existing unkeyed 3,200 ms path.

- [ ] **Step 6: Route every manual terminal and exception path through the outcome publisher**

Replace the current `job.status === 'SUCCEEDED'` branch inside `poll` with:

```ts
if (job.status === 'SUCCEEDED') {
  if (!job.report && !manualResultMayOmitReport(job.resultStatus)) {
    const message = missingReportMessage(job)
    set((current) => ({
      snapshot: current.snapshot ? {
        ...current.snapshot,
        status: 'FAILED',
        strategyVersion: job.strategyVersion,
        blockedReasons: job.blockedReasons,
        message,
        completedAt: job.finishedAt
      } : current.snapshot,
      error: message,
      loading: false
    }))
    publishManualScanFailure(message)
    return
  }

  const manualSnapshot = snapshotFromManualJob(job)
  const visibleReport = visibleSnapshotReport(manualSnapshot)
  const outcome = manualScanOutcome(manualSnapshot)
  set({
    snapshot: manualSnapshot,
    report: visibleReport,
    error: visibleReport === null ? outcome.message : '',
    loading: false
  })
  notifySnapshotCompleted('MANUAL', manualSnapshot)
  return
}
```

Replace the existing `job.status === 'FAILED'` branch with:

```ts
if (job.status === 'FAILED') {
  const message = job.message || '短线右侧实时扫描失败'
  set((current) => ({
    snapshot: current.snapshot ? {
      ...current.snapshot,
      status: 'FAILED',
      strategyVersion: job.strategyVersion,
      blockedReasons: job.blockedReasons,
      message,
      completedAt: job.finishedAt
    } : current.snapshot,
    error: message,
    loading: false
  }))
  publishManualScanFailure(message, job.blockedReasons)
  return
}
```

After the state update in the polling `catch`, add:

```ts
publishManualScanFailure(message)
```

After the state update in the outer job-creation `catch`, add the same call:

```ts
publishManualScanFailure(message)
```

Do not change the `ownsRun()` guards: a superseded manual request must not overwrite state or emit a stale terminal notification.

- [ ] **Step 7: Run focused lifecycle, page, and Toast tests and verify GREEN**

Run:

```bash
cd apps/web-react
npm test -- src/components/ui/Toast.test.tsx src/store/shortTermScanStore.test.ts src/pages/ShortTermPage.test.tsx
```

Expected: all three test files pass. Confirm the page regression still proves `手动最终结果已就绪` and `手动重算` are absent, scheduled pulse/status tests remain green, and every manual outcome contains the server message or blocked reason.

- [ ] **Step 8: Type-check through the production build**

Run:

```bash
cd apps/web-react
npm run build
```

Expected: `tsc -b` and `vite build` exit 0 with no Toast signature or scan-status exhaustiveness errors.

- [ ] **Step 9: Commit the manual scan lifecycle**

```bash
git add apps/web-react/src/store/shortTermScanStore.ts apps/web-react/src/store/shortTermScanStore.test.ts apps/web-react/src/pages/ShortTermPage.test.tsx
git commit -m "feat: show manual scan lifecycle notifications"
```

### Task 3: Verify, Deploy, Exercise the UI, and Push Main

**Files:**
- Verify: `apps/web-react/src/components/ui/Toast.tsx`
- Verify: `apps/web-react/src/components/ui/Toast.test.tsx`
- Verify: `apps/web-react/src/store/shortTermScanStore.ts`
- Verify: `apps/web-react/src/store/shortTermScanStore.test.ts`
- Verify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Verify: `apps/web-react/src/pages/ShortTermPage.test.tsx`
- Deploy from: `docker-compose.yml`

**Interfaces:**
- Consumes: committed React artifacts on local `main`, the existing `shares-test` Compose project, `ai-stock-web`, `ai-stock-api`, and the local `/short-term` route.
- Produces: a fully verified local deployment and an `origin/main` ref equal to the tested local `main` commit.

- [ ] **Step 1: Run complete repository verification**

Run:

```bash
mvn -pl apps/api test
(cd apps/web-react && npm test -- --reporter=dot && npm run build)
(cd apps/web && npm run build)
```

Expected: backend tests, the complete React suite, and both production frontend builds exit 0. Existing non-fatal Vue dependency-annotation or bundle-size warnings may remain, but there must be no failed test or build command.

- [ ] **Step 2: Confirm patch and repository integrity before deployment**

Run:

```bash
git diff --check
git status --short --branch
git log --oneline --decorate -5
```

Expected: no diff-check errors, no uncommitted implementation files, and `main` contains the approved design, implementation plan, Toast API commit, and manual lifecycle commit.

- [ ] **Step 3: Rebuild and replace only the Web container**

Run:

```bash
docker compose -p shares-test build web
docker compose -p shares-test up -d --no-deps web
```

Expected: `ai-stock-web` is recreated from the verified source. `ai-stock-api` is not recreated and the database volume remains attached and unchanged.

- [ ] **Step 4: Verify deployed service health and port mapping**

Run:

```bash
docker compose -p shares-test ps
docker port ai-stock-web
curl --fail --silent --show-error http://127.0.0.1:19080/actuator/health
```

Expected: Web and API containers are healthy, Web is exposed at `127.0.0.1:5176`, and the API returns `{"status":"UP"}`.

- [ ] **Step 5: Exercise the real manual scan UI with browser inspection**

Use the `browser:control-in-app-browser` skill to open `http://127.0.0.1:5176/short-term`, then:

1. Click `重新扫描` once.
2. Immediately verify one bottom-right notification says `短线扫描已开始，正在获取实时行情…`.
3. Verify there is still no large card containing `手动扫描执行中`, `手动最终结果已就绪`, or `手动重算`.
4. Verify the `重新扫描` button and compact loader still expose running state.
5. Wait for the existing scan job's terminal response without triggering any external model.
6. If candidates exist, verify the same notification position shows the candidate count and disappears after about 5 seconds.
7. If no candidates, data blocking, cache preview, pending state, or failure occurs, verify the floating warning/error includes a concrete reason, remains visible beyond 5 seconds, and closes with the keyboard-accessible `关闭通知` button.
8. Verify the result or inline error remains available after the Toast is closed.

Expected: there is never more than one visible `short-term-manual-scan` notification, the terminal outcome is understandable without the removed card, and scheduled status presentation remains unchanged.

- [ ] **Step 6: Check the remote before pushing**

Run:

```bash
git fetch origin
git rev-list --left-right --count main...origin/main
```

Expected: the right-hand count is `0`; stop and reconcile normally if `origin/main` gained commits. Do not force-push.

- [ ] **Step 7: Push and prove local/remote equality**

Run:

```bash
git push origin main
git fetch origin
test "$(git rev-parse main)" = "$(git rev-parse origin/main)"
git status --short --branch
```

Expected: push succeeds, the hash comparison exits 0, and the final status is clean `main...origin/main` with no ahead/behind marker.
