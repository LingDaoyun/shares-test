import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { create } from 'zustand'
import { CheckCircle2, AlertCircle, Info, Loader2, X } from 'lucide-react'

type ToastType = 'success' | 'error' | 'info' | 'warning' | 'loading'

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
    const durationMs = options.persistent || type === 'loading'
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
  loading: (message: string, options?: ToastOptions) => {
    useToastStore.getState().push('loading', message, options)
  },
  dismiss: (key: string) => useToastStore.getState().dismiss(key)
}

const config = {
  success: { icon: CheckCircle2, cls: 'text-success', spin: false },
  error: { icon: AlertCircle, cls: 'text-danger', spin: false },
  info: { icon: Info, cls: 'text-brand-500', spin: false },
  warning: { icon: AlertCircle, cls: 'text-warning', spin: false },
  loading: { icon: Loader2, cls: 'text-brand-500', spin: true }
} as const

function ToastRow({ item }: { item: ToastItem }) {
  const remove = useToastStore((state) => state.remove)
  const { icon: Icon, cls, spin } = config[item.type]
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
      className="card relative flex items-center gap-2.5 overflow-hidden px-4 py-3 shadow-float animate-toast-in"
    >
      <Icon className={`h-4 w-4 shrink-0 ${cls} ${spin ? 'animate-spin' : ''}`} />
      <span className="min-w-0 flex-1 text-sm text-ink-900">{item.message}</span>
      <button
        type="button"
        aria-label="关闭通知"
        onClick={() => remove(item.id)}
        className="ml-1 text-ink-400 hover:text-ink-600"
      >
        <X className="h-3.5 w-3.5" />
      </button>
      {item.type === 'loading' && (
        <span
          aria-hidden="true"
          className="absolute bottom-0 h-0.5 w-2/5 rounded-full bg-brand-400 animate-toast-sweep"
        />
      )}
    </div>
  )
}

const DRAG_THRESHOLD_PX = 3

export function ToastViewport() {
  const toasts = useToastStore((state) => state.toasts)
  const stackRef = useRef<HTMLDivElement | null>(null)
  const dragState = useRef<{
    pointerId: number
    startX: number
    startY: number
    baseX: number
    baseY: number
    moved: boolean
  } | null>(null)
  // null = 默认锚点（顶栏垂直居中，由 AppShell 写入 --toast-top）；拖拽后固定在用户放置的位置
  const [offset, setOffset] = useState<{ x: number; y: number } | null>(null)

  // 通知从无到有时回到默认锚点，避免上一次拖放的位置影响后续通知
  const wasEmpty = useRef(toasts.length === 0)
  useEffect(() => {
    const isEmpty = toasts.length === 0
    if (wasEmpty.current && !isEmpty) {
      setOffset(null)
    }
    wasEmpty.current = isEmpty
  }, [toasts])

  useEffect(() => {
    const onMove = (event: globalThis.PointerEvent) => {
      const drag = dragState.current
      if (!drag || event.pointerId !== drag.pointerId) return
      const dx = event.clientX - drag.startX
      const dy = event.clientY - drag.startY
      if (!drag.moved && Math.abs(dx) + Math.abs(dy) < DRAG_THRESHOLD_PX) return
      drag.moved = true
      const next = {
        x: Math.max(0, Math.min(drag.baseX + dx, window.innerWidth - 80)),
        y: Math.max(0, Math.min(drag.baseY + dy, window.innerHeight - 48))
      }
      setOffset(next)
    }
    const onUp = (event: PointerEvent) => {
      if (dragState.current?.pointerId !== event.pointerId) return
      dragState.current = null
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    window.addEventListener('pointercancel', onUp)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('pointercancel', onUp)
    }
  }, [])

  const startDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return
    if ((event.target as HTMLElement).closest('button')) return
    const stack = stackRef.current
    if (!stack) return
    // 居中态下 stack 是全宽锚点（left=0），必须取卡片自身的位置作拖拽基准，
    // 否则一旦移动就会闪跳到屏幕左侧。
    const anchor = offset === null ? stack.firstElementChild ?? stack : stack
    const rect = anchor.getBoundingClientRect()
    dragState.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      baseX: rect.left,
      baseY: rect.top,
      moved: false
    }
  }

  const resetCenter = () => setOffset(null)

  return (
    <div className="pointer-events-none fixed inset-0 z-50">
      <div
        ref={stackRef}
        className={
          offset
            ? 'fixed flex flex-col gap-2'
            : 'absolute inset-x-0 flex flex-col items-center gap-2 px-4'
        }
        style={offset ? { left: offset.x, top: offset.y } : { top: 'var(--toast-top, 4rem)' }}
      >
        {toasts.map((item) => (
          <div
            key={item.id}
            className="pointer-events-auto w-96 max-w-full cursor-grab touch-none select-none active:cursor-grabbing"
            onPointerDown={startDrag}
            onDoubleClick={resetCenter}
            title="可拖动调整位置，双击回到顶部居中"
          >
            <ToastRow item={item} />
          </div>
        ))}
      </div>
    </div>
  )
}
