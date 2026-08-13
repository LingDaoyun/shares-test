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
