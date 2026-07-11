import { useEffect } from 'react'
import { create } from 'zustand'
import { CheckCircle2, AlertCircle, Info, X } from 'lucide-react'

type ToastType = 'success' | 'error' | 'info' | 'warning'

interface ToastItem {
  id: number
  type: ToastType
  message: string
}

interface ToastStore {
  toasts: ToastItem[]
  push: (type: ToastType, message: string) => void
  remove: (id: number) => void
}

let nextId = 1

export const useToastStore = create<ToastStore>((set) => ({
  toasts: [],
  push: (type, message) => {
    const id = nextId++
    set((s) => ({ toasts: [...s.toasts, { id, type, message }] }))
  },
  remove: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) }))
}))

// 对外便捷 API，替代旧版 ElMessage
export const toast = {
  success: (m: string) => useToastStore.getState().push('success', m),
  error: (m: string) => useToastStore.getState().push('error', m),
  info: (m: string) => useToastStore.getState().push('info', m),
  warning: (m: string) => useToastStore.getState().push('warning', m)
}

const config = {
  success: { icon: CheckCircle2, cls: 'text-success' },
  error: { icon: AlertCircle, cls: 'text-danger' },
  info: { icon: Info, cls: 'text-brand-500' },
  warning: { icon: AlertCircle, cls: 'text-warning' }
} as const

function ToastRow({ item }: { item: ToastItem }) {
  const remove = useToastStore((s) => s.remove)
  const { icon: Icon, cls } = config[item.type]
  useEffect(() => {
    const timer = setTimeout(() => remove(item.id), 3200)
    return () => clearTimeout(timer)
  }, [item.id, remove])
  return (
    <div className="card flex items-center gap-2.5 px-4 py-3 shadow-float animate-fade-in">
      <Icon className={`h-4 w-4 shrink-0 ${cls}`} />
      <span className="text-sm text-ink-900">{item.message}</span>
      <button type="button" onClick={() => remove(item.id)} className="ml-1 text-ink-400 hover:text-ink-600">
        <X className="h-3.5 w-3.5" />
      </button>
    </div>
  )
}

export function ToastViewport() {
  const toasts = useToastStore((s) => s.toasts)
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
